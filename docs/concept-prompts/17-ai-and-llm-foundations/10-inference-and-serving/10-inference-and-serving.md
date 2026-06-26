# LLM Inference & Serving

> A definitive engineering-handbook chapter for a senior backend developer who wants to fully master how large language models actually run in production — from first principles to deep internals, tuning, capacity math, and debugging.

---

## 1. Overview & where it fits

**LLM inference** is the act of running a trained large language model *forward* to produce output tokens, given some input. **LLM serving** is everything around that act: turning a single model into a multi-tenant, low-latency, high-throughput network service that many concurrent users hit over HTTP/gRPC, with batching, scheduling, memory management, autoscaling, observability, and cost control.

Training gets the headlines, but **inference is where the money is spent at steady state.** A model is trained once (or periodically) and then served billions of times. For most companies running LLMs in production, inference dominates the recurring GPU bill. A 1% improvement in tokens-per-second-per-GPU translates directly into a 1% reduction in fleet size and cost. This is why an entire engineering discipline — serving stacks like vLLM, TGI, and TensorRT-LLM — exists purely to make inference faster and cheaper without retraining anything.

**The problem it solves.** A raw model checkpoint (a big bag of weight matrices) plus a naive PyTorch loop *can* generate text, but it will:
- waste almost all of the GPU's compute (low utilization),
- serve one request at a time or batch poorly,
- run out of GPU memory unpredictably,
- have terrible tail latency,
- and cost 10–50x more per token than a tuned stack.

Serving infrastructure exists to fix all of that.

**When you reach for this.** Any time you need to run an open-weights model (Llama, Mistral, Qwen, Gemma, DeepSeek, etc.) yourself — because of cost at scale, data residency/privacy, latency control, fine-tuned/custom models, air-gapped environments, or simply avoiding vendor lock-in. If you only call a hosted API (OpenAI, Anthropic, Google), you still benefit enormously from understanding this chapter: it explains *why* your bills look the way they do, why TTFT and output length matter so much, why long prompts are cheap-ish but long outputs are expensive, and how to design your application to be inference-efficient.

**One-paragraph mental model.** An LLM generates text one token at a time. Each new token requires a full forward pass over the network, but — crucially — that pass attends to *all previous tokens*. To avoid recomputing the entire history every step, the model stores per-token intermediate state called the **KV cache**, which grows linearly with sequence length and is the single biggest consumer of GPU memory during serving. Generation has two phases with completely different performance characteristics: a one-shot, compute-heavy **prefill** that ingests the whole prompt in parallel, and a long, memory-bandwidth-bound **decode** loop that emits one token per step. A good serving stack squeezes throughput out of this asymmetry by **batching many requests together continuously**, managing KV-cache memory like an operating system manages RAM (PagedAttention), and optionally **quantizing** weights to fit more model/requests per GPU and **speculating** ahead to emit multiple tokens per step.

> *Adjacent term — "token":* LLMs do not operate on characters or words but on **tokens**: subword chunks produced by a tokenizer (e.g. byte-pair encoding). A rough rule of thumb for English is ~4 characters or ~0.75 words per token. "Hello world" is ~2–3 tokens. Counting tokens (not words) is how you measure prompt size, output length, cost, and memory.

> *Adjacent term — "forward pass / inference":* Running data through the network from input to output to compute a result, with no weight updates. Contrast with the **backward pass** (backpropagation) used in training. Inference is forward-only, so it needs far less memory than training (no gradients, no optimizer state) — but a large model can still need tens to hundreds of GB.

---

## 2. Foundations from first principles

### 2.1 What an LLM is, mechanically

A modern LLM is (almost always) a **decoder-only Transformer**. Strip away the marketing and it is:

1. A **token embedding table**: maps each token ID to a vector of dimension `d_model` (e.g. 4096).
2. A stack of `N` **Transformer layers** (e.g. 32, 80, 126 layers depending on model size). Each layer has two sublayers:
   - **Self-attention**, which lets each token "look at" earlier tokens.
   - A **feed-forward network (FFN/MLP)**, two big matrix multiplies with a nonlinearity, applied position-wise.
   - Plus **normalization** (LayerNorm or RMSNorm) and **residual connections** (add the input back to the output of each sublayer).
3. A final **output projection** ("LM head") that maps the last layer's hidden vector to a **logit** for every token in the vocabulary (e.g. 128,000 logits).

> *Adjacent term — "logit":* An unnormalized score. For each candidate next token the model emits one logit; higher means "more likely." A **softmax** turns the whole logit vector into a probability distribution that sums to 1.

> *Adjacent term — "vocabulary / vocab size":* The fixed set of tokens the model knows, typically 32k–256k entries. The output layer produces one number per vocab entry, so a big vocab makes the final matmul and the logits tensor large.

> *Adjacent term — "hidden state / hidden dimension (`d_model`):* The width of the vectors flowing through the network. Bigger `d_model` → more capacity, more compute, more memory.

### 2.2 Self-attention, just enough to understand caching

Inside each attention sublayer, every token's hidden vector is linearly projected into three vectors:
- **Q (query)** — "what am I looking for?"
- **K (key)** — "what do I offer to others?"
- **V (value)** — "what content do I carry?"

For each token, attention computes a similarity (dot product) between its **Q** and the **K** of every *earlier* token, softmaxes those scores into weights, and uses them to take a weighted sum of the earlier tokens' **V** vectors. That weighted sum is the attention output for that token.

The phrase "every earlier token" is the entire reason the KV cache exists. To generate token #1001, the model needs the K and V vectors of tokens #1..#1000. Those K/V vectors only depend on each token's own hidden state at that layer — they don't change when later tokens arrive. So you compute them once and **cache** them.

> *Adjacent term — "causal / autoregressive":* Decoder-only LLMs are **causal**: token `t` may attend only to tokens `≤ t`, never the future. Generation is **autoregressive**: the model predicts the next token, appends it to the input, and repeats — each output becomes part of the next step's input.

> *Adjacent term — "attention head / multi-head attention (MHA):* The Q/K/V projections are split into multiple parallel "heads," each learning different relationships, then concatenated. A model might have 32 query heads. The number of K/V heads matters a lot for cache size (see GQA below).

### 2.3 The two phases: prefill vs decode

This distinction governs everything about serving performance.

**Prefill (a.k.a. prompt processing, "context encoding").**
You feed the entire prompt (say 1,000 tokens) into the model **at once, in parallel**. All 1,000 positions are processed in a single forward pass. This:
- computes the K and V for all 1,000 prompt tokens and writes them into the KV cache,
- produces the logits for the *last* position, from which the first output token is sampled.

Prefill is **compute-bound (GPU-FLOP-bound)**: you're doing big dense matrix multiplies over many tokens at once, so arithmetic intensity is high and the GPU's tensor cores are well fed. The time to finish prefill largely determines **TTFT** (time to first token).

**Decode (a.k.a. generation, the autoregressive loop).**
After prefill, you generate the rest one token at a time. Each decode step:
1. takes the single most recent token,
2. runs it through all layers, computing its Q/K/V,
3. appends its K/V to the cache,
4. attends over the *entire cache* (all prior tokens),
5. produces logits, samples one new token,
6. repeats until an end-of-sequence token or the max length.

Decode is **memory-bandwidth-bound**, not compute-bound. Why? In each step you process only **one** new token but you must **read all the model weights** (tens of GB) from GPU memory and **read the entire KV cache**. The actual arithmetic for one token is tiny relative to the bytes moved. The GPU's tensor cores sit mostly idle waiting for memory. This is the core reason single-request decode wastes the GPU — and the core reason **batching** is the master trick: if you push many requests' decode steps through together, you read the weights once and amortize them across the whole batch, dramatically raising utilization.

> *Adjacent term — "compute-bound vs memory-bound":* A kernel is **compute-bound** if the bottleneck is arithmetic throughput (FLOP/s); **memory-bound** if it's data movement (GB/s from HBM). The crossover is captured by **arithmetic intensity** (FLOPs per byte) and visualized with the **roofline model**. Prefill ≈ compute-bound; decode ≈ memory-bound.

> *Adjacent term — "HBM (High Bandwidth Memory)":* The fast on-package GPU memory (e.g. an NVIDIA A100 80GB has ~2.0 TB/s HBM bandwidth; H100 ~3.35 TB/s). Decode speed is roughly `HBM_bandwidth / (bytes_to_read_per_token)`, which is why bandwidth, not raw FLOPs, sets per-token latency for small batches.

### 2.4 The KV cache and why it dominates memory

For **each layer**, for **each token**, the model stores one K vector and one V vector. So the cache size is:

```
kv_bytes = 2 (K and V)
         × num_layers
         × num_kv_heads × head_dim       (= the K/V hidden size per layer)
         × seq_len
         × dtype_bytes                    (2 for FP16/BF16, 1 for FP8/INT8)
         × batch_size                     (summed over all concurrent sequences)
```

Worked example — Llama-3-8B-style model at FP16:
- `num_layers = 32`, `num_kv_heads = 8` (it uses GQA — see below), `head_dim = 128` ⇒ K/V hidden = 8×128 = 1024.
- Per token, per layer: `2 × 1024 × 2 bytes = 4096 bytes = 4 KiB`.
- Across 32 layers: `32 × 4096 = 131,072 bytes ≈ 128 KiB per token`.
- A single 8,000-token sequence: `8000 × 128 KiB ≈ 1.0 GiB` of KV cache.
- 64 such concurrent sequences: ~**64 GiB** of KV cache — already exceeding the weights of an 8B model (~16 GB in FP16).

That is the punchline: **at scale, the KV cache, not the model weights, is what fills the GPU.** Serving design is largely KV-cache memory management.

> *Adjacent term — "GQA (Grouped-Query Attention) / MQA (Multi-Query Attention)":* Tricks to shrink the cache. In vanilla MHA there are as many K/V heads as query heads. **MQA** uses a single K/V head shared by all query heads (smallest cache, some quality loss). **GQA** is the middle ground: groups of query heads share one K/V head (e.g. 32 query heads, 8 K/V heads = 4:1). Almost all modern models (Llama 2 70B onward, Llama 3, Mistral, Qwen2) use GQA specifically to cut KV-cache size 4–8x. This is why the example above used 8 K/V heads, not 32.

### 2.5 Sampling: how the next token is chosen

Given the logits, you pick a token. Common controls:
- **Greedy:** always take the argmax. Deterministic, can be repetitive.
- **Temperature (`T`):** divides logits before softmax. `T<1` sharpens (more deterministic), `T>1` flattens (more random), `T=0` ≈ greedy.
- **Top-k:** keep only the `k` highest-probability tokens, renormalize, sample.
- **Top-p (nucleus):** keep the smallest set of tokens whose cumulative probability ≥ `p` (e.g. 0.9), sample from those.
- **Repetition / frequency / presence penalties:** down-weight tokens already produced to reduce loops.
- **min-p, typical sampling, mirostat:** newer schemes; less universal.

Sampling cost is negligible compared to the forward pass, but it affects *determinism* (important for caching and testing) and output length (which dominates cost).

---

## 3. How it works internally — the serving lifecycle, step by step

This section is the heart of the chapter. We trace a request end-to-end through a modern continuous-batching engine (vLLM-style), then drill into each mechanism.

### 3.1 The naive baseline (and why it's bad)

A toy server that handles one request at a time:

```
loop forever:
  req = queue.pop()
  tokens = tokenize(req.prompt)
  state = prefill(model, tokens)           # one big forward pass
  out = []
  while not done:
      tok = decode_step(model, state)       # one token, GPU mostly idle
      out.append(tok)
  return detokenize(out)
```

Problems:
- **One request at a time** ⇒ GPU at maybe 2–5% utilization during decode.
- **Static batching** (group N requests, run them in lockstep) helps, but every request in the batch must finish before any result is returned and before the next batch starts. A batch of 8 where 7 produce 10 tokens and 1 produces 500 tokens wastes the slot of the 7 short ones for the entire 500-token tail ("**head-of-line blocking**" / "ragged batch" problem).

### 3.2 Continuous (in-flight) batching — the key idea

Modern engines run a **scheduler loop** that operates at the granularity of **a single decode step**, not a whole request. Pseudocode of the engine "step":

```
running = []          # sequences currently generating
waiting = []          # admitted but not yet started (need prefill)
while True:
    # 1. Admission: pull new requests if KV-cache memory allows
    while waiting and can_allocate_kv(waiting[0]):
        running.append(prefill_or_schedule(waiting.pop(0)))

    # 2. Build a batch out of all running sequences' NEXT step
    batch = [seq.next_step() for seq in running]

    # 3. One fused forward pass over the heterogeneous batch
    logits = model.forward(batch)          # mixes prefill + decode work

    # 4. Sample one token per sequence
    for seq, logit in zip(running, logits):
        tok = sample(logit, seq.params)
        seq.append(tok)
        stream_token_to_client(seq, tok)   # SSE/gRPC streaming

    # 5. Retire finished sequences, free their KV blocks immediately
    running = [s for s in running if not s.finished()]
    free_kv_blocks(finished)
```

The magic: **a sequence that finishes at step 12 frees its slot immediately, and a brand-new request can join at step 13** — no waiting for the whole batch. This is "continuous" or "in-flight" batching. It typically delivers **5–20x higher throughput** than static batching at similar latency.

> *Adjacent term — "head-of-line blocking":* When a slow item at the front of a queue stalls everything behind it. Static batching suffers from it (long generations block short ones); continuous batching removes it.

> *Adjacent term — "SSE (Server-Sent Events) / streaming":* A one-way HTTP streaming protocol where the server pushes tokens to the client as they're generated, so the user sees text appear incrementally rather than waiting for the full response. This is why TTFT (latency to the first chunk) matters so much for UX.

### 3.3 Prefill vs decode scheduling, and chunked prefill

A subtlety: prefill is a big, bursty, compute-heavy chunk; decode steps are tiny. If a long prompt arrives, naively running its full prefill in one step **stalls all the in-flight decodes** behind it (they wait for the giant prefill matmul). This causes **inter-token latency spikes** for everyone.

Solutions:
- **Prefill/decode prioritization:** decide each step whether to spend it on admitting a new prefill or advancing decodes.
- **Chunked prefill (a.k.a. "piggybacking" / split prefill):** break a long prompt's prefill into fixed-size chunks (e.g. 512 tokens) and interleave those chunks with ongoing decode steps in the same fused batch. This smooths latency: existing users keep getting tokens while the newcomer's prompt is ingested over several steps. vLLM exposes this as `enable_chunked_prefill` and a `max_num_batched_tokens` budget.
- **Disaggregated / split prefill-decode serving (P/D disaggregation):** run prefill on one pool of GPUs and decode on another, transferring the KV cache between them over fast interconnect. Because the two phases have different bottlenecks (compute vs bandwidth), specializing hardware/pools per phase can improve goodput and isolate latency. Used in large production systems (e.g. designs influenced by Splitwise/DeepSeek-style serving); supported in newer vLLM and in NVIDIA Dynamo. Tradeoff: KV transfer cost and added complexity.

### 3.4 PagedAttention — KV cache as virtual memory

The breakthrough behind vLLM (Kwon et al., 2023). The problem it solves: naive serving allocates one **contiguous** KV buffer per request sized to the *maximum* possible length. That causes massive waste:
- **Internal fragmentation:** a request that only generates 50 of a reserved 2,048 tokens wastes the other 1,998 slots.
- **External fragmentation:** freed contiguous chunks of varying sizes can't be reused cleanly.
- Real systems wasted **60–80%** of KV memory this way.

**PagedAttention** borrows the operating-system idea of **paging**: split the KV cache into fixed-size **blocks** (e.g. 16 tokens each). A sequence's logical token positions map to a **block table** pointing at physical blocks, which need not be contiguous. Memory is allocated **on demand, one block at a time**, as the sequence grows. The attention kernel is rewritten to gather K/V across non-contiguous blocks using the block table.

Benefits:
- Near-zero waste (only the last partially-filled block of each sequence is "wasted"), enabling **2–4x more concurrent sequences** in the same GPU memory.
- **Copy-on-write block sharing:** if multiple requests share a common prefix (e.g. the same system prompt, or parallel samples `n>1` from one prompt), they can **share the physical KV blocks** for the prefix, only diverging (and copying) when their tokens differ. This is the foundation of **prefix caching / automatic prefix caching (APC)** — reuse the KV of a repeated system prompt across requests, saving prefill compute.

> *Adjacent term — "paging / virtual memory":* In operating systems, the kernel splits memory into fixed-size pages and maps a process's contiguous virtual addresses to scattered physical pages via a page table, so memory needn't be contiguous and can be allocated lazily. PagedAttention applies the exact same indirection to the KV cache, with "blocks" as pages and a "block table" as the page table.

> *Adjacent term — "fragmentation":* Wasted memory. **Internal** = reserved-but-unused space inside an allocation. **External** = free space exists but is split into pieces too small/awkward to satisfy a request. PagedAttention crushes both.

### 3.5 The fused attention kernel (FlashAttention)

Underneath, the attention computation itself is implemented with **FlashAttention** (Dao et al.) or similar fused kernels.

> *Adjacent term — "FlashAttention":* A GPU kernel that computes attention without ever materializing the full `seq_len × seq_len` attention-score matrix in HBM. It tiles the computation, keeping blocks in fast on-chip SRAM and using an online-softmax trick, which makes attention **memory-IO-aware**: far less HBM traffic, lower memory footprint (linear instead of quadratic in sequence length for the intermediate), and big speedups. FlashAttention-2 and -3 (H100-tuned, FP8-capable) are the current standard. PagedAttention integrates a paged variant of these kernels.

### 3.6 Quantization in the inference path

To fit bigger models and more requests, weights (and sometimes activations and the KV cache) are stored in lower precision. Covered fully in §7; here is where it sits in the pipeline: quantized weights are **dequantized on the fly** inside fused matmul kernels (or computed in low precision directly on hardware that supports it, like FP8 on H100). This cuts the bytes read per decode step — directly attacking the memory-bandwidth bottleneck — and shrinks the weight footprint so more KV cache fits.

### 3.7 Speculative decoding in the loop

Decode is bandwidth-bound and emits one token per expensive forward pass. **Speculative decoding** breaks that one-token-per-pass limit by using a cheap **draft** to guess several tokens ahead, then verifying them with **one** pass of the big model. Covered in detail in §7.4. In the engine loop it appears as a modified decode step that may advance a sequence by multiple tokens at once.

### 3.8 Multi-GPU execution: tensor, pipeline, and expert parallelism

A model too big for one GPU is **sharded** across several.

- **Tensor parallelism (TP):** split each weight matrix across GPUs; every GPU does part of every layer; results are combined with an **all-reduce** each layer. Low latency, but needs very fast interconnect (NVLink) because of per-layer communication. Typical within a single node (TP=2/4/8).

> *Adjacent term — "all-reduce / collective":* A group communication where all GPUs combine their partial results (e.g. sum) and every GPU ends up with the combined result. Implemented by NCCL over NVLink/InfiniBand. It's the recurring tax of tensor parallelism.

> *Adjacent term — "NVLink / NVSwitch":* NVIDIA's high-bandwidth GPU-to-GPU interconnect (hundreds of GB/s to ~900 GB/s on H100 systems), far faster than PCIe (~64 GB/s gen5). TP performance lives or dies on this.

- **Pipeline parallelism (PP):** assign different *layers* to different GPUs; a request flows GPU0→GPU1→… Less communication, but introduces "pipeline bubbles" (idle time) unless many microbatches are in flight. Used across nodes when interconnect is slower.
- **Expert parallelism (EP):** for **Mixture-of-Experts (MoE)** models, distribute the experts across GPUs.

> *Adjacent term — "Mixture of Experts (MoE)":* A model where each FFN layer has many "expert" sub-networks but a router activates only a few per token (e.g. 8 experts total, top-2 used). This gives a huge **total** parameter count but a small **active** parameter count per token, so compute (and thus decode cost) is low relative to capacity — but **all** experts' weights still occupy GPU memory. Examples: Mixtral 8×7B, DeepSeek-V3, Qwen MoE. MoE complicates batching (different tokens hit different experts → load imbalance) and memory math (memory ∝ total params, compute ∝ active params).

- **Data parallelism (DP):** simplest of all — run multiple **independent replicas** of the whole model behind a load balancer for horizontal scale. Different from training DP; here it's just "more copies."

### 3.9 State machine of a single sequence

```
RECEIVED → (tokenize) → WAITING (in admission queue)
   → admitted when KV blocks available →
PREFILL (possibly chunked across steps)
   → first token sampled →
DECODING (loop; one or more tokens per engine step)
   → may be PREEMPTED/SWAPPED if memory pressure (KV evicted to CPU or recomputed) →
   → resumes DECODING →
FINISHED (EOS, max_tokens, stop string, or client disconnect)
   → free KV blocks → detokenize tail → close stream
```

> *Adjacent term — "preemption / swapping / recomputation":* Under memory pressure, an engine may pause a running sequence and either **swap** its KV blocks out to CPU RAM (move back later) or **recompute** them from scratch on resume (cheaper to store, costs compute). vLLM supports both policies. This is how it stays stable instead of OOM-crashing when demand spikes.

---

## 4. The complete toolkit

### 4.1 Key serving stacks at a glance

| Stack | Origin | License | Best at | Notable features | Caveats |
|---|---|---|---|---|---|
| **vLLM** | UC Berkeley / community | Apache-2.0 | General-purpose high-throughput serving | PagedAttention, continuous batching, chunked prefill, prefix caching, broad model & quant support, OpenAI-compatible API | Optimal but not always the absolute lowest latency vs TensorRT-LLM |
| **TensorRT-LLM** | NVIDIA | Apache-2.0 (lib) | Lowest latency / highest throughput on NVIDIA GPUs | Compiled CUDA engines, in-flight batching, FP8/INT4, custom kernels, multi-GPU | NVIDIA-only; build/compile step per model+GPU; less flexible |
| **TGI (Text Generation Inference)** | Hugging Face | Apache-2.0 | Easy HF-ecosystem deployment | Continuous batching, tensor parallel, many quant formats, simple ops | Historically a hair behind vLLM on throughput; tightly HF-coupled |
| **SGLang** | community | Apache-2.0 | Structured/multi-call workloads, fast prefix reuse | RadixAttention (prefix-tree KV reuse), strong throughput, structured output | Newer, smaller ecosystem |
| **NVIDIA Dynamo / Triton** | NVIDIA | Apache-2.0 | Production datacenter orchestration | P/D disaggregation, KV-aware routing, multi-backend, autoscaling | Heavier to operate |
| **llama.cpp / Ollama** | community | MIT | Local / CPU / Apple Silicon / single-user | GGUF quant, runs on laptops, tiny footprint | Not built for high-concurrency datacenter serving |
| **DeepSpeed-MII / FasterTransformer (legacy)** | Microsoft / NVIDIA | varied | Specialized cases | — | Largely superseded |

> *Adjacent term — "OpenAI-compatible API":* vLLM, TGI, SGLang, etc. expose `/v1/chat/completions` and `/v1/completions` endpoints matching OpenAI's schema, so existing client SDKs and apps work against your self-hosted model by just changing the base URL. This is the de-facto integration standard.

### 4.2 vLLM — core knobs (the one you'll use most)

CLI: `vllm serve <model>` (or the older `python -m vllm.entrypoints.openai.api_server`).

| Flag / param | Purpose | Typical / default |
|---|---|---|
| `--model` | HF repo id or local path | required |
| `--tensor-parallel-size` (`tp`) | shard model across N GPUs in one node | 1 |
| `--pipeline-parallel-size` (`pp`) | pipeline stages across GPUs/nodes | 1 |
| `--dtype` | compute dtype (`auto`/`bfloat16`/`float16`) | auto |
| `--quantization` | `awq`, `gptq`, `fp8`, `bitsandbytes`, etc. | none |
| `--kv-cache-dtype` | KV cache precision (`auto`/`fp8`/`fp8_e5m2`) | auto (= model dtype) |
| `--gpu-memory-utilization` | fraction of GPU mem the engine may use | 0.90 |
| `--max-model-len` | max context (prompt+gen) per request | model's max |
| `--max-num-seqs` | cap on concurrent sequences in a batch | engine-dependent (e.g. 256) |
| `--max-num-batched-tokens` | token budget per engine step (prefill+decode) | tied to chunked prefill |
| `--enable-chunked-prefill` | interleave long prefills with decode | on by default in recent versions |
| `--enable-prefix-caching` | reuse KV for shared prefixes (APC) | off/on by version |
| `--swap-space` | CPU RAM (GiB) for KV swapping on preemption | 4 |
| `--block-size` | PagedAttention block size (tokens) | 16 |
| `--max-num-seqs`, `--scheduler-delay` | scheduler tuning | — |
| `--served-model-name` | name clients use in API calls | model id |
| `--api-key` | bearer token auth | none |
| `--speculative-config` / draft-model flags | enable speculative decoding | off |
| `--enforce-eager` | disable CUDA graph capture (debugging) | off (graphs on) |

> Version note: flag names and defaults change across vLLM releases (the engine was rewritten around the "V1" architecture). **Always check `vllm serve --help` for your installed version.** Treat the table as directional.

### 4.3 Sampling / request parameters (OpenAI-compatible)

| Param | Effect | Notes |
|---|---|---|
| `max_tokens` / `max_completion_tokens` | cap output length | dominates cost & latency |
| `temperature` | randomness | 0 ≈ greedy/deterministic |
| `top_p`, `top_k` | nucleus / top-k truncation | top_k is a vLLM/HF extension to the OpenAI schema |
| `stop` | stop strings | early termination |
| `frequency_penalty`, `presence_penalty` | reduce repetition | |
| `n` | number of samples per prompt | shares prefill KV via PagedAttention |
| `logprobs` / `top_logprobs` | return token probabilities | for eval/debug |
| `stream` | SSE token streaming | improves perceived latency |
| `seed` | reproducibility (best-effort) | true determinism is hard on GPU |
| `guided_json` / `response_format` | constrained/structured output | grammar-based decoding |

### 4.4 Observability surface (Prometheus metrics exposed by vLLM/TGI)

| Metric (representative) | Meaning |
|---|---|
| `vllm:time_to_first_token_seconds` | TTFT histogram |
| `vllm:time_per_output_token_seconds` | inter-token latency (TPOT/ITL) |
| `vllm:e2e_request_latency_seconds` | full request latency |
| `vllm:num_requests_running` / `_waiting` | live load & queue depth |
| `vllm:gpu_cache_usage_perc` | KV cache utilization |
| `vllm:prompt_tokens_total` / `:generation_tokens_total` | throughput counters |
| `vllm:num_preemptions_total` | how often sequences are evicted (pressure signal) |
| `vllm:request_success_total` (by finish reason) | EOS vs length vs abort |

### 4.5 Quantization toolkits

| Tool / format | Type | Where used |
|---|---|---|
| **GPTQ** | post-training weight quant (4-bit), per-group, calibration-based | weights only; broad support |
| **AWQ** (Activation-aware Weight Quant) | PTQ 4-bit, protects salient weights using activation stats | weights; popular for quality |
| **bitsandbytes** | on-the-fly 8/4-bit (NF4), zero calibration | quick experiments, QLoRA |
| **FP8 (E4M3/E5M2)** | hardware-native 8-bit float on H100/Ada | weights+activations+KV; very fast |
| **GGUF + k-quants** | llama.cpp format (Q4_K_M, Q5_K_M, …) | local/CPU |
| **SmoothQuant** | W8A8, migrates activation outliers into weights | datacenter INT8 |
| **AutoRound / GPTQModel / llm-compressor** | tooling to produce the above | offline quantization pipelines |

> *Adjacent term — "PTQ vs QAT":* **Post-Training Quantization** quantizes an already-trained model (fast, no retraining; GPTQ/AWQ are PTQ). **Quantization-Aware Training** simulates quantization during training for better accuracy at very low bit-widths (expensive). Serving almost always uses PTQ.

---

## 5. Code examples by use case

### 5.1 Launching a production vLLM server (CLI) and calling it from Java

Start the server (single GPU, 8B model, OpenAI-compatible):

```bash
# Serve Llama-3.1-8B-Instruct on one GPU, 90% of VRAM for the engine,
# 8k context, prefix caching + chunked prefill on, behind an API key.
vllm serve meta-llama/Llama-3.1-8B-Instruct \
  --gpu-memory-utilization 0.90 \
  --max-model-len 8192 \
  --enable-prefix-caching \
  --enable-chunked-prefill \
  --max-num-seqs 256 \
  --api-key "$VLLM_API_KEY" \
  --served-model-name llama3-8b \
  --port 8000
```

Call it from Java using the standard `java.net.http.HttpClient` (JDK 11+), streaming tokens via SSE:

```java
// File: LlmStreamingClient.java  (JDK 17+, no external deps)
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class LlmStreamingClient {
    private static final String BASE = "http://localhost:8000/v1/chat/completions";
    private static final String API_KEY = System.getenv("VLLM_API_KEY");

    public static void main(String[] args) throws Exception {
        // OpenAI-compatible chat payload. "stream":true makes the server push SSE chunks.
        String body = """
          {
            "model": "llama3-8b",
            "messages": [{"role":"user","content":"Explain the KV cache in two sentences."}],
            "max_tokens": 200,
            "temperature": 0.2,
            "stream": true
          }""";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))   // fail fast on connect
            .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE))
            .timeout(Duration.ofSeconds(120))         // generous overall deadline for long outputs
            .header("Authorization", "Bearer " + API_KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        // Stream line-by-line; each SSE event is "data: {json}\n\n", terminated by "data: [DONE]".
        HttpResponse<java.util.stream.Stream<String>> resp =
            client.send(req, HttpResponse.BodyHandlers.ofLines());

        resp.body().forEach(line -> {
            if (!line.startsWith("data: ")) return;
            String json = line.substring(6);
            if (json.equals("[DONE]")) return;
            // Minimal extraction; in real code use Jackson and parse choices[0].delta.content.
            int i = json.indexOf("\"content\":\"");
            if (i >= 0) {
                int start = i + 11;
                int end = json.indexOf("\"", start);
                if (end > start) System.out.print(unescape(json.substring(start, end)));
            }
        });
        System.out.println();
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
```

Why it matters: streaming dramatically lowers *perceived* latency — the user sees TTFT-worth of text in ~50–300 ms instead of waiting for the whole completion. Always set both a short connect timeout and a long overall deadline; LLM responses are slow but the *connection* should fail fast.

### 5.2 Non-streaming call with proper retries & timeouts (Java + Jackson)

```java
// Robust, blocking client with bounded retries on transient 5xx / connection errors.
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class LlmClient {
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper om = new ObjectMapper();
    private final String url, key, model;

    public LlmClient(String url, String key, String model) {
        this.url = url; this.key = key; this.model = model;
    }

    public String complete(String prompt, int maxTokens) throws Exception {
        ObjectNode payload = om.createObjectNode();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", 0.0);            // deterministic-ish for testability
        var msgs = payload.putArray("messages");
        var m = msgs.addObject(); m.put("role", "user"); m.put("content", prompt);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload)))
            .build();

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    JsonNode root = om.readTree(r.body());
                    return root.path("choices").get(0).path("message").path("content").asText();
                }
                if (r.statusCode() == 429 || r.statusCode() >= 500) {
                    // Backpressure or transient server error: back off with jitter.
                    if (attempts >= 4) throw new RuntimeException("LLM failed: " + r.statusCode());
                    Thread.sleep((long) (Math.pow(2, attempts) * 100 + Math.random() * 100));
                    continue;
                }
                throw new RuntimeException("Non-retryable " + r.statusCode() + ": " + r.body());
            } catch (java.io.IOException e) {
                if (attempts >= 4) throw e;
                Thread.sleep((long) (Math.pow(2, attempts) * 100));
            }
        }
    }
}
```

Note: HTTP **429** from vLLM/your gateway is the polite "I'm at capacity / queue full" signal — treat it as backpressure, back off, and consider shedding load rather than hammering.

### 5.3 Offline batch inference (Python, vLLM) — for evals / bulk jobs

When you don't need a server (nightly evals, dataset labeling), use the in-process engine: it batches everything for maximum throughput.

```python
# pip install vllm
from vllm import LLM, SamplingParams

# One process owns the GPU; the engine batches all prompts internally.
llm = LLM(model="meta-llama/Llama-3.1-8B-Instruct",
          gpu_memory_utilization=0.90,
          max_model_len=4096,
          enable_prefix_caching=True)   # shared system prompts reuse KV

sp = SamplingParams(temperature=0.0, max_tokens=128)
prompts = [f"Summarize record #{i} ..." for i in range(10_000)]

# generate() schedules all 10k prompts through continuous batching automatically.
outputs = llm.generate(prompts, sp)
for o in outputs:
    print(o.outputs[0].text)
```

This single call will saturate the GPU far better than 10,000 sequential HTTP requests, because the engine sees all the work up front.

### 5.4 Serving a quantized model to fit a bigger model on smaller GPUs

```bash
# Serve a 70B model 4-bit AWQ-quantized so it fits on a single 48GB GPU
# instead of needing 2x80GB at FP16.
vllm serve TheBloke/Llama-2-70B-Chat-AWQ \
  --quantization awq \
  --max-model-len 4096 \
  --gpu-memory-utilization 0.92 \
  --port 8000
# Tradeoff: ~small quality drop, big cost drop. Validate on YOUR eval set before shipping.
```

```bash
# FP8 on H100/Ada: hardware-native, near-lossless, fast. KV cache in FP8 too.
vllm serve meta-llama/Llama-3.1-70B-Instruct \
  --quantization fp8 \
  --kv-cache-dtype fp8 \
  --tensor-parallel-size 2 \
  --max-model-len 8192
```

### 5.5 Speculative decoding to cut latency

```bash
# Use a small draft model to propose tokens, verified by the big target model.
# Speeds up decode when the draft agrees often (e.g. boilerplate, code).
vllm serve meta-llama/Llama-3.1-70B-Instruct \
  --tensor-parallel-size 4 \
  --speculative-config '{"model":"meta-llama/Llama-3.2-1B-Instruct","num_speculative_tokens":5}'
# Exact flag syntax varies by version; some versions use --speculative-model + --num-speculative-tokens.
```

### 5.6 Capacity probe / load test before going live

```bash
# Drive synthetic load to find the throughput knee and p99 latency at target QPS.
# vLLM ships a benchmark; you can also use a generic tool.
python -m vllm.entrypoints.benchmarks.benchmark_serving \
  --backend vllm --model llama3-8b \
  --dataset-name random --num-prompts 1000 \
  --request-rate 20            # requests/sec; sweep this to find saturation
# Watch p50/p99 TTFT and TPOT; the QPS where p99 latency hockey-sticks is your safe ceiling.
```

### 5.7 Kubernetes deployment with GPU + autoscaling skeleton

```yaml
# Minimal vLLM Deployment; HPA/KEDA scales replicas on a custom metric (e.g. queue depth).
apiVersion: apps/v1
kind: Deployment
metadata: { name: vllm-llama3 }
spec:
  replicas: 2
  selector: { matchLabels: { app: vllm-llama3 } }
  template:
    metadata: { labels: { app: vllm-llama3 } }
    spec:
      containers:
      - name: vllm
        image: vllm/vllm-openai:latest
        args: ["--model","meta-llama/Llama-3.1-8B-Instruct",
               "--gpu-memory-utilization","0.9","--max-model-len","8192"]
        ports: [{ containerPort: 8000 }]
        resources:
          limits: { nvidia.com/gpu: 1 }      # one GPU per pod
        readinessProbe:                       # don't route until model is loaded
          httpGet: { path: /health, port: 8000 }
          initialDelaySeconds: 60             # model load can take a minute+
          periodSeconds: 10
```

```yaml
# KEDA ScaledObject scaling on vLLM's running+waiting requests (better than CPU/GPU%).
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: vllm-scaler }
spec:
  scaleTargetRef: { name: vllm-llama3 }
  minReplicaCount: 1
  maxReplicaCount: 8
  cooldownPeriod: 300                          # avoid thrashing on a topic with cold starts
  triggers:
  - type: prometheus
    metadata:
      serverAddress: http://prometheus:9090
      query: "sum(vllm:num_requests_waiting)"  # queue depth = the right scaling signal
      threshold: "20"
```

Key operational point: **scale LLM workloads on queue depth / GPU KV utilization, not on CPU%.** CPU is irrelevant; the GPU is the bottleneck, and "requests waiting" is the truest pressure signal. Cold starts are slow (pull multi-GB image + load weights), so set generous `initialDelaySeconds`, long cooldowns, and consider keeping a warm minimum.

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Maximize batch occupancy.** Throughput comes from keeping many sequences decoding together. Tune `--max-num-seqs` and `--max-num-batched-tokens` to your latency SLA. More batch = more throughput but higher per-token latency.
- **Right-size `--max-model-len`.** A huge max context reserves KV-cache headroom and caps concurrency. Set it to what you actually need.
- **Use prefix caching** for repeated system prompts / RAG templates — it skips prefill compute for the shared prefix and is nearly free to enable.
- **Quantize when bandwidth-bound** (large models, decode-heavy). FP8 on H100 is the easiest big win with minimal quality loss; AWQ/GPTQ for older GPUs.
- **CUDA graphs** (on by default) cut per-step launch overhead — keep them unless debugging (`--enforce-eager` disables them).
- **Keep outputs short.** Output length is the dominant cost/latency lever. `max_tokens`, good stop sequences, and prompts that discourage rambling save real money.

### 6.2 Correctness & concurrency
- **Determinism is hard.** Even at `temperature=0`, GPU floating-point reductions, batch composition, and kernel choice can make outputs vary run to run. Don't assume bit-exact reproducibility; pin versions and use `seed` for best-effort.
- **Tokenizer parity.** Counting/truncating with the *exact* tokenizer the model uses. A mismatch causes context-overflow errors or silent truncation.
- **Concurrency is the engine's job, not yours.** Don't try to "batch" at the application layer by concatenating prompts — that breaks per-request controls and confuses billing/safety. Send independent requests; let continuous batching do its thing.

### 6.3 Memory
- **Budget VRAM = weights + activations + KV cache + framework overhead.** Use the math in §7.5. Leave headroom (`--gpu-memory-utilization 0.9`, not 0.99) — the last few % is fragmentation and CUDA workspace.
- **Watch `num_preemptions_total`.** Frequent preemptions mean you're oversubscribed; reduce `max-num-seqs` or `max-model-len`, add GPUs, or shed load.

### 6.4 Security
- **Authenticate** (`--api-key` or, better, a gateway with per-tenant keys). An open inference endpoint is an open wallet.
- **Prompt injection & data exfiltration** are application-layer risks: untrusted text in the prompt can subvert instructions, especially with tools/RAG. Treat model output as untrusted; sandbox any tool the model can call.
- **PII / data residency**: a major reason to self-host. Ensure logs don't capture prompts/outputs in plaintext where that violates policy.
- **Resource exhaustion / DoS**: cap `max_tokens` server-side, rate-limit per tenant, and set request timeouts — a single request asking for 100k tokens can hog a slot for minutes.
- **Multi-tenant isolation**: with prefix caching, be aware that cached KV blocks are shared by prefix — fine for a shared system prompt, but don't let one tenant's *private* prefix be reused by another. Most engines key cache by content, which is safe, but verify.

### 6.5 Observability
- Track the **four golden inference metrics**: TTFT (p50/p99), TPOT/ITL (p50/p99), throughput (tokens/sec, requests/sec), and **goodput** (requests meeting their SLA). Plus queue depth and KV utilization.
- **Log finish reasons** (EOS vs length cap vs abort). A spike in "length" finishes means truncation is hurting quality; a spike in aborts means clients are timing out.
- Correlate **TTFT with prompt length** (prefill cost) and **TPOT with batch size** (decode contention).

### 6.6 Cost
- Cost ≈ `(GPU $/hr) / (tokens/sec) `. Improving tokens/sec/GPU via batching/quantization is the lever.
- **Output tokens cost more than input tokens** (decode is serial and slow; prefill is parallel and fast). Design prompts and outputs accordingly.
- **Spot/preemptible GPUs** can cut cost 60–90% for batch/eval workloads that tolerate interruption; keep on-demand for latency-critical serving.
- Compare self-host vs API at *your* sustained utilization — see §8.

### 6.7 Testing
- **Golden-set eval** on every model/quant/version change: a fixed prompt set scored automatically (exact-match, embedding similarity, or LLM-as-judge). Quantization especially can silently degrade quality — never ship a quant without re-evaluating.
- **Load/soak tests** to find the throughput knee and check for memory leaks/preemption storms over hours.
- **Determinism caveat** (above) means snapshot tests must allow tolerance, not exact strings.

### 6.8 Production hardening
- Health/readiness probes that reflect *model loaded*, not just *process up*.
- Graceful shutdown (drain in-flight sequences before terminating).
- Separate **gateway** (auth, rate-limit, routing, retries, logging) from **engine** (vLLM). Don't put business logic in the engine.
- Pin model + engine + CUDA driver versions; treat a model+quant+GPU combo as an immutable artifact.

### 6.9 Anti-patterns
- Serving with raw `transformers` `.generate()` in a `for` loop for production (no batching → 10–50x waste).
- Static batching with mismatched generation lengths (head-of-line blocking).
- Setting `max_model_len` to the model max "just in case" (kills concurrency).
- Autoscaling on CPU% (irrelevant) instead of queue depth.
- Shipping a 4-bit quant without an eval gate.
- Ignoring streaming and then complaining about latency.

---

## 7. Advanced topics & deep internals

### 7.1 Throughput vs latency metrics — defined precisely

| Metric | Definition | Driven by |
|---|---|---|
| **TTFT** (Time To First Token) | time from request sent to first output token | prefill cost (∝ prompt length), queueing, batch admission |
| **TPOT / ITL** (Time Per Output Token / Inter-Token Latency) | average gap between successive output tokens | decode step time (∝ batch size, model size, bandwidth) |
| **E2E latency** | total time to full response | ≈ TTFT + (output_tokens − 1) × TPOT |
| **Throughput** | tokens/sec or requests/sec across the whole server | batch occupancy, model/quant, GPU |
| **Goodput** | throughput counting only requests that met SLA | the metric that actually matters in prod |

A critical, non-obvious truth: **latency and throughput trade off.** Bigger batches raise throughput (more tokens/sec total) but raise TPOT for each user (more sequences share each weight-read, but the step takes longer and there's more contention). You tune the batch size / token budget to sit at the best point on that curve for your SLA.

### 7.2 GPU memory math — the formulas you'll actually use

**Weights:** `weight_bytes = num_params × bytes_per_param`.
- FP16/BF16 = 2 bytes/param. 8B → 16 GB; 70B → 140 GB; 405B → 810 GB.
- FP8/INT8 = 1 byte. 70B → 70 GB.
- INT4 = 0.5 byte. 70B → 35 GB.

**KV cache per token** (§2.4): `2 × num_layers × num_kv_heads × head_dim × dtype_bytes`.

**KV cache total:** `× seq_len × concurrent_sequences`.

**Activations / workspace:** transient; modest for inference (FlashAttention avoids the quadratic blowup). Budget a few GB.

**Total fit check:**
```
VRAM_needed ≈ weights
            + (KV_per_token × max_concurrent_seqs × avg_seq_len)
            + activations_workspace
            + CUDA/runtime overhead (~1–2 GB)
```

> *Adjacent term — "GiB vs GB / GPU 80GB":* Vendors mix decimal (GB, 10⁹) and binary (GiB, 2³⁰) units. An "80GB" A100/H100 is ~80×10⁹ bytes ≈ 74.5 GiB usable, minus reserved. Don't plan to fill 100%.

### 7.3 Worked capacity reasoning (end to end)

**Scenario:** serve Llama-3.1-8B (FP16) on one A100 80GB. How many concurrent users at 4k-token average sequences?

1. Weights: 8B × 2 B = **16 GB**.
2. Reserve overhead + activations ≈ **4 GB**. Engine budget at 0.9 util ≈ 72 GB → usable for KV ≈ 72 − 16 − 4 = **~52 GB**.
3. KV per token (from §2.4): **128 KiB** (0.000128 GiB).
4. KV per 4k sequence: 4096 × 128 KiB = **512 MiB ≈ 0.5 GiB**.
5. Concurrent sequences ≈ 52 GiB / 0.5 GiB = **~100+ sequences** of KV headroom.

So memory allows ~100 concurrent 4k-context sequences. Whether you *should* run that many depends on latency: at, say, ~6k decode-tokens/sec aggregate for 8B on A100, 100 concurrent users each wanting 200 tokens means ~20,000 output tokens to produce; at 6k tok/s that's ~3.3 s of pure decode shared — fine for batch, maybe too slow for chat p99. **Memory sets the ceiling; latency SLA sets the operating point below it.** (Throughput numbers are hardware/version specific — measure yours; don't trust a blog's.)

**Scenario 2:** 70B FP16 needs 140 GB > one 80GB GPU. Options: (a) TP=2 across 2×80GB (70 GB weights/GPU + KV), (b) FP8 → 70 GB fits one GPU with little KV room, (c) AWQ INT4 → 35 GB on one GPU with healthy KV room (accept small quality loss). The decision is a memory + quality + cost optimization.

### 7.4 Speculative decoding — deep dive

The serial decode bottleneck: one expensive target-model forward pass yields one token. Speculative decoding amortizes that pass over several tokens.

**Draft-model speculative decoding:**
1. A small, cheap **draft model** (e.g. 1B) autoregressively proposes `γ` tokens (e.g. 5).
2. The big **target model** runs **one** forward pass over all `γ` proposed tokens *in parallel* (like a mini-prefill), producing the target's probability for each position.
3. A **verification** step (rejection sampling) accepts the longest prefix of proposed tokens that the target "agrees" with, and corrects the first disagreement. Crucially, this preserves the **exact target distribution** — output quality is identical to non-speculative decoding (it's not an approximation).
4. Net effect: you advance by (accepted+1) tokens per target pass. If the draft is right ~70% of the time, you might get 2–3x speedup on decode.

**Variants:**
- **Self-speculative / Medusa:** add extra "heads" to the model that predict multiple future tokens, no separate draft model.
- **EAGLE / EAGLE-2/3:** predict at the feature level for higher accept rates; state-of-the-art speedups.
- **Lookahead decoding / n-gram / prompt-lookup:** use n-grams from the prompt itself as the draft — great for tasks with repetition (code edits, RAG quoting), zero extra model.

**Caveats:** speedup depends entirely on **acceptance rate**, which depends on task and draft quality. Under heavy batching, speculative decoding's benefit shrinks (the GPU is already busy, less idle bandwidth to exploit), and the extra verification work can even *hurt* throughput at high load. It's a **latency** optimization for low-to-moderate batch, not always a throughput win.

### 7.5 Quantization — deep dive

**Why it helps inference twice over:** (1) smaller weights → fewer bytes read per decode step → faster (decode is bandwidth-bound); (2) smaller footprint → more room for KV cache → higher concurrency.

| Method | Bits | Calibration | Quality | Speed | Notes |
|---|---|---|---|---|---|
| **FP8 (E4M3)** | 8 | minimal | near-lossless | very high (H100/Ada native) | the default choice on modern NVIDIA GPUs |
| **INT8 / SmoothQuant** | 8 | yes (activation stats) | near-lossless | high | older GPUs; W8A8 |
| **GPTQ** | 4 (3/2) | yes (Hessian-based) | small loss at 4-bit | high | weights only; widely available |
| **AWQ** | 4 | yes (activation-aware) | small loss, often best 4-bit quality | high | protects salient channels |
| **bitsandbytes NF4** | 4 | none | decent | moderate | great for QLoRA & quick tests |
| **GGUF k-quants** | 2–8 | none | varies | CPU/Apple optimized | llama.cpp ecosystem |

> *Adjacent term — "outliers" in activations:* A few activation channels have extreme magnitudes; naively quantizing them destroys accuracy. SmoothQuant migrates that difficulty into weights; AWQ keeps salient weight channels in higher precision. These tricks are why modern PTQ at 4-bit barely loses quality.

**KV-cache quantization:** store the cache itself in FP8/INT8 (`--kv-cache-dtype fp8`) to roughly halve KV memory → more concurrency or longer context. Small, usually-acceptable quality cost; validate.

**The non-negotiable rule:** **always re-evaluate** a quantized model on your own task. "Barely loses quality" is true on benchmarks and false on some specific tasks (math, code, long-context reasoning). Gate quants behind an eval.

### 7.6 Long context, RoPE scaling, and the cache cost

> *Adjacent term — "RoPE (Rotary Positional Embeddings)":* How most modern LLMs encode token position, by rotating Q/K vectors by a position-dependent angle. **RoPE scaling** (linear, NTK, YaRN) lets a model trained at, say, 8k context run at 32k–128k by adjusting those frequencies. The cost: KV cache grows linearly with context, so 128k context is **16x** the KV memory of 8k — long context is expensive primarily because of the cache. This is why long-context serving leans on GQA, KV quantization, and sometimes KV eviction/compression (e.g. attention-sink + sliding-window approaches like StreamingLLM, or H2O-style eviction).

### 7.7 Structured / constrained decoding

Forcing output to match a grammar/JSON schema (`guided_json`, Outlines, XGrammar) works by **masking logits** at each step to only allow tokens valid under the grammar's current state — a finite-state machine over the vocabulary. Useful for tool-calling and reliable JSON. Slight per-step overhead to compute the allowed-token mask; modern implementations precompute it efficiently.

### 7.8 CUDA graphs, kernel fusion, compilation
- **CUDA graphs** record a sequence of GPU kernel launches once and replay them, eliminating per-step CPU launch overhead (significant when each decode step is microseconds of work but many kernels).
- **TensorRT-LLM** compiles the model into an optimized engine ahead of time, fusing kernels and selecting the best implementations per GPU — lowest latency, at the cost of an offline build step per (model, GPU, precision, max-batch) combination.
- vLLM's "V1" rewrite focused on reducing CPU overhead (the scheduler) so the GPU isn't starved between steps.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Self-hosted vs API

| Dimension | Self-hosted (vLLM/TGI/TRT-LLM) | Hosted API (OpenAI/Anthropic/etc.) |
|---|---|---|
| **Upfront effort** | high (infra, ops, tuning) | ~zero |
| **Per-token cost at scale** | low (you amortize GPUs) | higher per token |
| **Per-token cost at low/spiky use** | high (idle GPUs) | low (pay per use) |
| **Latency control** | full (your hardware, your batching) | vendor-controlled |
| **Data residency / privacy** | full control | depends on vendor terms |
| **Model choice** | any open weights, fine-tunes | vendor's menu |
| **Frontier quality** | best open models lag the very top closed ones (gap narrowing) | access to top closed models |
| **Ops burden / on-call** | yours | vendor's |
| **Scaling spikes** | you must provision/autoscale GPUs | elastic, instant |

**Rule of thumb:** API for prototypes, low/spiky volume, and when you need frontier closed-model quality. Self-host when you have **sustained high volume** (GPUs stay busy), strict **data/latency** requirements, or need **custom/fine-tuned** models. The crossover is roughly where your steady-state GPU utilization is high enough that `GPU_$ / your_tokens` beats `API_$ / token`. Compute it with real numbers, not vibes.

### 8.2 Which serving stack

| Use when… | Pick |
|---|---|
| General self-hosting, broad model support, want the community default | **vLLM** |
| You need absolute lowest latency on NVIDIA and can afford the build step | **TensorRT-LLM** |
| You're all-in on Hugging Face and want simple ops | **TGI** |
| Heavy prefix reuse / agentic multi-call / structured output | **SGLang** |
| Datacenter-scale orchestration, P/D disaggregation, KV-aware routing | **NVIDIA Dynamo / Triton** |
| Laptop / CPU / single user / Apple Silicon | **llama.cpp / Ollama** |

### 8.3 Quantization decision
- **H100/Ada available?** → FP8 first (easy, fast, near-lossless).
- **Older GPU (A100/A10/T4), need to fit a big model?** → AWQ or GPTQ INT4.
- **Quick experiment / QLoRA?** → bitsandbytes NF4.
- **CPU / laptop?** → GGUF k-quants via llama.cpp.
- **Quality-critical task (math/code)?** → prefer FP8 or 8-bit over 4-bit; always eval.

### 8.4 Latency vs throughput optimization
- Optimize **latency** (low batch, speculative decoding, smaller/quantized model, fewer TP all-reduces): interactive chat, copilots.
- Optimize **throughput** (big batch, high `max-num-seqs`, accept higher TPOT): bulk processing, evals, async pipelines.

---

## 9. Failure modes & debugging

### 9.1 CUDA OOM at startup or under load
- **Symptom:** `CUDA out of memory` on launch or after concurrency rises.
- **Cause:** weights + KV + workspace exceed VRAM; `gpu-memory-utilization` too high; `max-model-len`/`max-num-seqs` too large; another process on the GPU.
- **Diagnose:** `nvidia-smi` (see who owns memory), engine startup logs (it prints KV-cache blocks allocated), the memory math in §7.2.
- **Fix:** lower `--max-num-seqs` / `--max-model-len`, quantize, lower util to leave headroom, or add GPUs (TP).

### 9.2 Preemption / swap storms
- **Symptom:** TPOT spikes, throughput collapses, `vllm:num_preemptions_total` climbing.
- **Cause:** oversubscribed KV cache — too many long sequences for available memory; the scheduler keeps evicting and recomputing.
- **Diagnose:** watch `gpu_cache_usage_perc` near 100% with high preemption counts.
- **Fix:** reduce concurrency cap, cap `max_tokens`, shed load (return 429), add capacity.

### 9.3 High TTFT
- **Cause:** long prompts (heavy prefill), queueing behind other prefills, no chunked prefill, cold cache.
- **Diagnose:** correlate TTFT with prompt length and `num_requests_waiting`.
- **Fix:** enable chunked prefill, enable prefix caching for shared system prompts, add replicas, route long prompts to a separate pool (P/D disaggregation).

### 9.4 High / jittery inter-token latency
- **Cause:** big prefills landing mid-batch and stalling decodes; batch too large; preemption.
- **Fix:** chunked prefill, lower batch/token budget, isolate prefill.

### 9.5 Throughput far below expectation
- **Cause:** batching not happening (sequential client requests, or app-level serialization), model not on GPU, eager mode (graphs off), bad TP/interconnect (PCIe instead of NVLink → all-reduce bottleneck).
- **Diagnose:** check `num_requests_running` (should be >1 under load), `nvidia-smi` GPU util, `nvidia-smi topo -m` (NVLink topology), profile with Nsight Systems.
- **Fix:** send concurrent requests, ensure CUDA graphs on, verify NVLink, right-size batch.

### 9.6 Quality regression after a change
- **Symptom:** outputs worse after enabling a quant, upgrading the engine, or changing KV dtype.
- **Diagnose:** run the golden eval set; compare against the previous artifact.
- **Fix:** roll back the quant/KV-dtype, or accept after re-tuning; never ship without the eval gate.

### 9.7 Garbage / repeated / truncated output
- **Cause:** wrong chat template / tokenizer mismatch (very common — model expects a specific prompt format); `max_tokens` too low (truncation); missing stop tokens (rambling); bad sampling params.
- **Diagnose:** log the *exact* templated prompt sent to the model; check finish_reason.
- **Fix:** use the model's official chat template, set proper stop tokens, raise `max_tokens` if `finish_reason=length`.

### 9.8 Real-world incident patterns
- **The 100k-token request:** one client sets `max_tokens` huge (or a runaway agent loops), monopolizing a slot for minutes and starving others. *Lesson:* enforce server-side `max_tokens` caps and per-request timeouts.
- **The PCIe surprise:** a 70B TP=4 deployment is mysteriously slow; topology check reveals GPUs are PCIe-connected, not NVLink, so per-layer all-reduce dominates. *Lesson:* verify interconnect before choosing TP degree.
- **The silent quant regression:** a team ships INT4 to save cost; aggregate metrics look fine but math/code accuracy quietly drops, surfacing as customer complaints weeks later. *Lesson:* task-specific eval gate on every quant.
- **The cold-start cascade:** an autoscale event spins new pods that take 90s to load weights; meanwhile the queue backs up and clients time out, triggering retries that worsen load. *Lesson:* warm minimum replicas, long cooldowns, scale on queue depth with lead time, and backpressure (429) instead of unbounded queueing.

---

## 10. Interview drill

**Q1. Walk me through what happens, mechanically, when an LLM generates a 200-token answer to a 1,000-token prompt.**
*Model answer:* First a **prefill** pass ingests all 1,000 prompt tokens in parallel, computing and caching their K/V at every layer and producing logits for the last position → first output token (this sets TTFT). Then a **decode** loop runs 199 more times; each step feeds the latest token through all layers, appends its K/V to the cache, attends over the whole cache, samples one token. Prefill is compute-bound; decode is memory-bandwidth-bound (reads all weights + cache per token).
- *Follow-up: Why is decode bandwidth-bound?* Because it processes one token but must read all model weights and the full KV cache from HBM; arithmetic is tiny relative to bytes moved, so the GPU waits on memory.
- *Follow-up: How does batching change that?* Batching many sequences' decode steps reads the weights once and amortizes across the batch, raising arithmetic intensity and GPU utilization → much higher throughput.
- *Follow-up: What does that imply for cost?* Output tokens are the expensive, serial part; throughput per GPU is set by how well you batch decode.

**Q2. What is the KV cache, how big is it, and why does it matter?**
*Model answer:* Cached per-layer K and V vectors for every past token so attention needn't recompute them. Size = `2 × layers × kv_heads × head_dim × dtype_bytes × seq_len × batch`. It grows linearly with context and concurrency and, at scale, exceeds the model weights — making KV memory the binding constraint on concurrency and context length.
- *Follow-up: How do GQA/MQA help?* They reduce the number of K/V heads (shared across query heads), cutting cache size 4–8x with minor quality cost.
- *Follow-up: How do you shrink it further?* KV-cache quantization (FP8/INT8), eviction/compression (sliding window, attention sinks), and PagedAttention to eliminate fragmentation waste.

**Q3. Explain PagedAttention and the problem it solves.**
*Model answer:* Naive serving reserves a contiguous max-length KV buffer per request, wasting 60–80% to internal/external fragmentation. PagedAttention pages the cache into fixed-size blocks mapped by a per-sequence block table (like OS virtual memory), allocating on demand and allowing non-contiguous storage and prefix block sharing. Result: 2–4x more concurrent sequences and the basis for prefix caching.
- *Follow-up: How does prefix sharing work?* Identical prefixes (system prompts, parallel samples) point to the same physical blocks via copy-on-write; they diverge only when tokens differ, saving prefill compute and memory.
- *Follow-up: What's the cost?* A custom paged attention kernel and block-table indirection; negligible vs the memory savings.

**Q4. Define TTFT, TPOT, and throughput, and explain the latency/throughput tradeoff. (senior-signal)**
*Model answer:* TTFT = time to first token (≈ prefill + queueing). TPOT/ITL = average inter-token time (≈ decode step time). Throughput = tokens/sec across the server. Larger batches raise throughput but raise TPOT (more contention per step) and can raise TTFT (admission queueing). You pick the batch/token budget that maximizes throughput subject to your p99 TTFT/TPOT SLA — i.e., maximize **goodput**, not raw throughput.
- *Follow-up: How would you tune for a chat copilot vs a nightly eval?* Copilot: small batch, speculative decoding, quantized small model for low TPOT/TTFT. Eval: max batch, high concurrency, accept high TPOT for max throughput.
- *Follow-up: Why does prompt length hit TTFT but not TPOT much?* Prefill cost scales with prompt length; once decoding, each step is one token regardless of history length (though larger cache slightly slows attention).

**Q5. Self-host vs API — how do you decide? (senior-signal)**
*Model answer:* Compare per-token economics at your *sustained* utilization, plus data/latency/model requirements. API wins for prototypes, spiky/low volume, and frontier closed-model quality. Self-host wins at high sustained volume (GPUs stay busy), strict data residency/latency, or custom fine-tunes. The crossover is where `GPU_$/your_tokens < API_$/token`; below that utilization, idle GPUs make self-hosting more expensive.
- *Follow-up: Hidden costs of self-hosting?* Ops/on-call, autoscaling cold starts, capacity for spikes, eval/quant pipelines, GPU availability/lead times.
- *Follow-up: A hybrid?* Yes — self-host the bulk/steady traffic, burst to an API for spikes or route hard queries to a stronger closed model.

**Q6. What's speculative decoding and when does it help? (senior-signal)**
*Model answer:* A draft proposes several tokens; the target verifies them in one parallel pass and accepts the agreed prefix via rejection sampling that preserves the exact target distribution (no quality loss). It speeds up **latency** when the GPU has idle bandwidth (low/moderate batch) and acceptance rate is high. Under heavy batching it can fail to help or even hurt, since the GPU is already saturated and verification adds work.
- *Follow-up: What sets the speedup?* Acceptance rate × draft cost; task-dependent (high for code/boilerplate, lower for open-ended).
- *Follow-up: Alternatives without a draft model?* Medusa heads, EAGLE, and prompt-lookup/n-gram drafting.

**Q7. Walk through quantization options and the risk.**
*Model answer:* FP8 (H100/Ada, near-lossless, fast), INT8/SmoothQuant (older GPUs), GPTQ/AWQ INT4 (fit big models, small quality loss, AWQ usually best 4-bit quality), bitsandbytes NF4 (quick/QLoRA), GGUF (CPU/local). It helps decode (fewer bytes read) and concurrency (smaller footprint → more KV). The risk: silent task-specific quality regression — always gate with an eval on your own data.
- *Follow-up: Can you quantize the KV cache?* Yes, FP8/INT8 KV roughly halves cache memory for more concurrency/context, with validation.
- *Follow-up: PTQ vs QAT?* Serving uses PTQ (no retraining); QAT is for pushing very low bit-widths in training.

**Q8. How would you size GPUs to serve Llama-70B to N concurrent users? (senior-signal)**
*Model answer:* Compute weights (140 GB FP16 → needs TP=2×80GB, or FP8 70GB on one, or INT4 35GB on one). Then KV per token via the formula, × avg seq len × N for KV memory; ensure it fits remaining VRAM at ~0.9 util. Then check the latency: measured tokens/sec/GPU vs N users × output tokens / SLA. Memory sets the ceiling; latency SLA sets the operating point. Add replicas (data parallel) for more users/throughput.
- *Follow-up: Why not just use TP=8?* More TP means more per-layer all-reduce overhead and diminishing returns; cross-node TP over slow links can hurt. Use the minimum TP that fits, then scale out with replicas.
- *Follow-up: Where does MoE change the math?* Memory ∝ total params (all experts resident) but compute ∝ active params, so a big-total/small-active MoE is cheap to decode but memory-hungry, and batching is harder due to expert load imbalance.

**Q9. How do you autoscale an LLM serving fleet?**
*Model answer:* Scale on **queue depth / KV utilization**, not CPU%. Use a custom metric (e.g. `num_requests_waiting`) via KEDA/HPA. Account for slow cold starts (multi-GB image + weight load): warm minimum replicas, long cooldowns, predictive/lead-time scaling, and backpressure (429) over unbounded queues. Separate gateway from engine.
- *Follow-up: Why not GPU%?* GPU% is noisy and can be high even when latency is fine, or misleading during prefill bursts; queue depth maps directly to user-felt latency.
- *Follow-up: Spot instances?* Good for batch/eval (interruptible); keep on-demand for latency-critical serving.

**Q10. A request stream is producing latency spikes for everyone whenever a long prompt arrives. Diagnose and fix.**
*Model answer:* A big prefill is monopolizing engine steps and stalling in-flight decodes (head-of-line blocking at the step level). Fix with **chunked prefill** to interleave the long prompt's ingestion with ongoing decode, and/or **P/D disaggregation** to run prefill on a separate pool. Verify via TTFT/TPOT correlated with incoming prompt length.
- *Follow-up: What if it's instead frequent preemptions?* Then it's memory oversubscription — reduce concurrency/`max_tokens`, add capacity.
- *Follow-up: How do you confirm chunked prefill helped?* TPOT variance drops and p99 ITL flattens under mixed long/short prompts.

**Q11. Why might `temperature=0` not give identical outputs across runs?**
*Model answer:* GPU floating-point non-associativity in parallel reductions, batch-composition-dependent kernel paths, and kernel/algorithm selection can change low-order bits, occasionally flipping an argmax tie. True bit-exact reproducibility isn't guaranteed; pin versions and use `seed` for best-effort, design tests with tolerance.
- *Follow-up: Why does batch composition matter?* The same sequence may be computed in a differently-shaped fused batch, choosing different kernels/reduction orders.

**Q12. Explain tensor vs pipeline parallelism and when you'd use each.**
*Model answer:* TP splits each weight matrix across GPUs with a per-layer all-reduce — low latency but communication-heavy, so it needs NVLink and is used within a node. PP splits layers across GPUs/nodes — less communication but pipeline bubbles unless many microbatches flow, used across slower links. Use minimal TP to fit the model on fast-interconnect GPUs, PP to span nodes, and data-parallel replicas to scale throughput.
- *Follow-up: What kills TP performance?* Slow interconnect (PCIe vs NVLink) makes all-reduce dominate.
- *Follow-up: How does this interact with quantization?* Quantizing to fit on fewer GPUs reduces or eliminates TP communication overhead — often a bigger win than raw FLOP savings.

---

## 11. Glossary

- **Activation:** intermediate tensor produced as data flows through the network; transient at inference.
- **All-reduce:** collective op where all GPUs combine partial results and each ends with the sum; the recurring cost of tensor parallelism.
- **Arithmetic intensity:** FLOPs per byte of memory traffic; determines compute- vs memory-bound behavior.
- **Autoregressive:** generating one token at a time, each conditioned on all previous tokens.
- **AWQ (Activation-aware Weight Quantization):** 4-bit PTQ that protects salient weight channels using activation statistics.
- **Batching (static / continuous / in-flight):** grouping requests to share a forward pass; continuous batching schedules per-step, admitting/retiring sequences mid-flight.
- **bitsandbytes:** library for on-the-fly 8/4-bit quantization (NF4), no calibration.
- **Block / block table (PagedAttention):** fixed-size KV chunk and its mapping table; the "page" and "page table" of the KV cache.
- **Causal attention:** token attends only to itself and earlier tokens.
- **Chunked prefill:** splitting a long prompt's prefill into chunks interleaved with decode to smooth latency.
- **Compute-bound / memory-bound:** bottlenecked by arithmetic vs by data movement.
- **Continuous batching:** see batching.
- **CUDA graph:** recorded-and-replayed sequence of GPU kernel launches that removes per-step launch overhead.
- **Decode:** the autoregressive token-by-token generation phase; memory-bandwidth-bound.
- **Determinism:** reproducibility of outputs; hard to guarantee bit-exactly on GPUs.
- **Disaggregation (P/D):** running prefill and decode on separate GPU pools, transferring KV between them.
- **EAGLE / Medusa:** speculative-decoding methods that avoid a separate draft model (extra heads / feature-level drafts).
- **FFN / MLP:** the feed-forward sublayer of a Transformer block.
- **FlashAttention:** IO-aware fused attention kernel that avoids materializing the full score matrix in HBM.
- **FP8 / E4M3 / E5M2:** 8-bit floating formats; hardware-native on H100/Ada; fast, near-lossless quantization.
- **Fragmentation (internal/external):** wasted memory inside allocations or split into unusable pieces.
- **Goodput:** throughput counting only requests that met their SLA.
- **GPTQ:** Hessian-based 4-bit weight PTQ.
- **GQA (Grouped-Query Attention):** groups of query heads share one K/V head; shrinks KV cache 4–8x.
- **Greedy / temperature / top-k / top-p:** sampling controls for next-token selection.
- **HBM (High Bandwidth Memory):** fast on-package GPU memory; its bandwidth caps decode speed.
- **Head (attention head):** one of several parallel attention subspaces.
- **Hidden state / `d_model`:** the width of vectors flowing through the network.
- **Inference:** forward-only execution of a trained model.
- **ITL (Inter-Token Latency):** see TPOT.
- **KV cache:** stored per-layer K and V vectors for past tokens; dominates serving memory.
- **Logit:** unnormalized per-token score before softmax.
- **MoE (Mixture of Experts):** model with many experts, few activated per token; memory ∝ total params, compute ∝ active params.
- **MQA (Multi-Query Attention):** single shared K/V head; smallest cache.
- **NCCL:** NVIDIA's collective communication library implementing all-reduce etc.
- **NVLink / NVSwitch:** high-bandwidth GPU interconnect critical for tensor parallelism.
- **OpenAI-compatible API:** the de-facto `/v1/chat/completions` HTTP schema many engines implement.
- **PagedAttention:** OS-paging-style KV-cache management eliminating fragmentation and enabling prefix sharing.
- **Pipeline parallelism (PP):** distributing layers across GPUs/nodes.
- **Prefill:** the parallel prompt-ingestion phase; compute-bound; sets TTFT.
- **Prefix caching (APC):** reusing KV of shared prompt prefixes across requests.
- **Preemption / swap / recompute:** pausing a sequence under memory pressure, moving its KV to CPU or recomputing it on resume.
- **PTQ / QAT:** Post-Training Quantization vs Quantization-Aware Training.
- **Quantization:** representing weights/activations/KV in lower precision to save memory/bandwidth.
- **RadixAttention:** SGLang's prefix-tree KV reuse.
- **RoPE / RoPE scaling:** rotary positional embeddings and methods to extend context length.
- **Roofline model:** chart relating arithmetic intensity to achievable performance (compute vs memory bound).
- **Sampling:** choosing the next token from logits.
- **Self-attention:** mechanism letting tokens attend to other tokens via Q/K/V.
- **SmoothQuant:** W8A8 INT8 quantization that migrates activation outliers into weights.
- **Softmax:** turns logits into a probability distribution.
- **Speculative decoding:** drafting multiple tokens and verifying them in one target pass; preserves the target distribution.
- **SSE (Server-Sent Events):** HTTP streaming used to push tokens incrementally.
- **Tensor parallelism (TP):** splitting each weight matrix across GPUs with per-layer all-reduce.
- **TGI (Text Generation Inference):** Hugging Face's serving stack.
- **Token / tokenizer / vocabulary:** subword unit, the component that produces them, and the fixed set of them.
- **TensorRT-LLM:** NVIDIA's compiled, low-latency serving library.
- **TTFT (Time To First Token):** latency to the first output token.
- **TPOT (Time Per Output Token):** average inter-token latency.
- **Transformer (decoder-only):** the architecture underlying modern LLMs.
- **vLLM:** community high-throughput serving engine built around PagedAttention and continuous batching.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Two phases:** Prefill = parallel, compute-bound, sets **TTFT**. Decode = serial, **memory-bandwidth-bound**, one token per pass, sets **TPOT**.

**KV cache bytes/token** = `2 × layers × kv_heads × head_dim × dtype_bytes`. Grows linearly with context × concurrency; **dominates memory at scale**. Shrink with GQA/MQA, FP8 KV, PagedAttention (kills fragmentation).

**Master throughput trick:** continuous/in-flight batching — schedule per *step*, admit/retire mid-flight, no head-of-line blocking. 5–20x over static batching.

**PagedAttention:** KV as OS pages → ~no waste, 2–4x concurrency, prefix sharing → prefix caching.

**Weights memory:** FP16 = 2B/param (8B→16GB, 70B→140GB), FP8/INT8 = 1B, INT4 = 0.5B.

**Quantization:** FP8 (H100, easy/near-lossless) > AWQ/GPTQ INT4 (fit big models, small loss) > bnb NF4 (quick) > GGUF (CPU). **Always eval after quantizing.**

**Speculative decoding:** draft + one-pass verify, exact distribution; latency win at low/moderate batch, fades at high load.

**Metrics:** TTFT, TPOT/ITL, throughput (tok/s), **goodput** (SLA-meeting). Watch queue depth, KV usage, preemptions.

**Multi-GPU:** TP (split matrices, all-reduce, NVLink, low latency) | PP (split layers, cross-node) | DP (replicas for scale). Use minimal TP to fit, scale out with replicas.

**Autoscale on queue depth / KV%, not CPU%.** Cold starts are slow → warm minimums, long cooldowns, backpressure (429).

**Stacks:** vLLM (default), TensorRT-LLM (lowest latency, NVIDIA, build step), TGI (HF-easy), SGLang (prefix-heavy), Dynamo/Triton (datacenter), llama.cpp/Ollama (local).

**Self-host vs API:** API for spiky/low/frontier; self-host for sustained-high-volume/data/latency/custom. Crossover = `GPU_$/your_tokens < API_$/token`.

**Cost levers (biggest first):** keep outputs short (output tokens dominate) → batch well → quantize → right-size context → spot for batch jobs.

### 12.2 Self-test (no answers — recall practice)

1. Derive the KV-cache-bytes-per-token formula and compute it for a model with 80 layers, 8 KV heads, head_dim 128, BF16. Then give the total for 50 concurrent 16k-token sequences and state whether it fits an 80GB GPU alongside 70B FP16 weights.
2. Explain precisely why decode is memory-bandwidth-bound while prefill is compute-bound, and how continuous batching changes the decode arithmetic intensity.
3. You see TPOT spikes only when long prompts arrive. Name the mechanism and two distinct fixes, and say how you'd confirm each worked from metrics.
4. Your team wants to move a 70B model from 2×80GB FP16 to a single 80GB GPU. List the options, their memory footprints, the quality risk, and the one gate you must run before shipping.
5. When does speculative decoding stop helping, and why? When would it actively hurt?
6. Design the autoscaling signal and policy for a chat product with bursty traffic and 90-second cold starts. Justify each choice.
7. Give the self-host-vs-API crossover condition in a formula and list three non-token costs that shift the decision toward API.

---

*End of chapter. For deeper follow-ups, you can request: more code examples (e.g. gRPC, structured-output JSON, multi-LoRA serving), a step-by-step trace through the vLLM scheduler internals, or a set of spaced-repetition flashcards.*

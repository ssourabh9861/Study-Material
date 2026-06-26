# Fine-Tuning vs RAG

> An engineering handbook chapter for senior backend developers who want to *fully master* the decision space between fine-tuning, retrieval-augmented generation (RAG), prompt engineering, and long-context — well enough to design, operate, debug, teach, and pass any interview.

---

## 1. Overview & where it fits

### What this chapter is about

Large Language Models (LLMs) — neural networks trained to predict the next token of text — ship as **frozen, general-purpose** artifacts. A model like Llama 3, Mistral, GPT-4o, or Claude knows an enormous amount about the public internet up to its training cutoff, but it knows **nothing** about:

- *Your* private data (your codebase, your customer records, your internal wiki).
- *Facts that changed* after its training cutoff (yesterday's stock price, this morning's incident).
- *Your* preferred output format, tone, or domain conventions, unless they happen to match the public average.

So the central engineering problem is: **how do you take a frozen, general model and make it behave correctly on *your* task, with *your* data, in *your* format?**

There are four primary levers, and the discipline of this chapter is knowing which lever to pull, when, and in what combination:

| Lever | One-line description | Changes the weights? | Adds runtime context? |
|---|---|---|---|
| **Prompt engineering** | Write better instructions / examples in the prompt | No | Yes (static, in prompt) |
| **Long-context** | Stuff lots of data directly into a huge context window | No | Yes (a lot, in prompt) |
| **RAG** | Retrieve relevant data at query time and inject it | No | Yes (dynamic, retrieved) |
| **Fine-tuning** | Continue training the model on your data | Yes | No (knowledge baked into weights) |

> **Token** — the atomic unit an LLM reads and writes. Roughly ¾ of an English word on average (so ~100 tokens ≈ 75 words). Models are billed and limited in tokens, not characters. "Context window" is measured in tokens.

### The problem each lever solves

- **Prompt engineering** solves *"the model can do this, I just need to ask correctly."*
- **Long-context** solves *"I have a moderate, bounded amount of reference material I want available right now."*
- **RAG** solves *"the relevant knowledge is large, changes often, or is private — fetch the right slice per query."*
- **Fine-tuning** solves *"I want the model to internalize a behavior, style, format, or skill so reliably and cheaply that I shouldn't have to re-explain it every call."*

### The single most important mental model

**Fine-tuning changes *how* the model behaves; RAG changes *what* the model knows at answer time.**

Said differently, the classic and accurate analogy:

- **Fine-tuning is studying for an exam** — you internalize patterns, skills, formats, and a way of thinking. You can't easily "study in" a fact that changes daily.
- **RAG is an open-book exam** — you don't memorize; you look up the relevant page when the question arrives.

A subtler but crucial corollary that trips up most teams: **Fine-tuning is excellent at teaching *form* and *behavior*; it is unreliable and expensive for teaching *facts*.** RAG is excellent at supplying *facts* at runtime; it does nothing to change the model's intrinsic style or skills. **They are complementary, not competing.** The expert answer to "fine-tuning *or* RAG?" is very often "**both, plus a good prompt**."

### When you reach for each (the 10-second version)

- Reach for **prompt engineering first, always.** It is the cheapest, fastest, most reversible lever. Exhaust it before anything else.
- Reach for **RAG** when the model needs *knowledge it doesn't have* — private, fresh, large, or citation-requiring.
- Reach for **fine-tuning** when the model needs to *behave differently* — a consistent format, a domain style, a narrow classification skill, tool-call syntax, or when you want to shrink the prompt and cut latency/cost.
- Reach for **long-context** when you have a bounded blob of reference material (a contract, a few docs) that fits and you'd rather not build retrieval infrastructure.

The rest of this chapter makes each of these precise, shows the internals, gives you the toolkit and code, and builds a decision framework you can defend in a design review or an interview.

---

## 2. Foundations from first principles

We build up from zero. If you already know transformers and gradient descent, skim; the terms are defined so a newcomer can follow.

### 2.1 What an LLM actually is

An LLM is a function. Conceptually:

```
P(next_token | all_previous_tokens) = model(tokens; weights)
```

It takes a sequence of tokens and outputs a probability distribution over the next token. You sample from that distribution, append the chosen token, and repeat. That loop is **autoregressive generation**.

> **Autoregressive** — "regressing on itself": each new output depends on the outputs so far. The model generates one token at a time, feeding each back in.

The "knowledge" and "skills" of the model live entirely in its **weights** (also called **parameters**) — billions of floating-point numbers learned during training. A "7B model" has ~7 billion parameters; a "70B" has ~70 billion.

> **Parameter / weight** — a single learnable number inside the network. During training, these numbers are nudged so the model's predictions match the training data better. After training they are frozen, and inference just multiplies inputs by these numbers.

### 2.2 The transformer, just enough

Modern LLMs are **transformers**. The two ideas you must hold:

1. **Embeddings** — every token is mapped to a vector (a list of numbers, e.g. 4096-dimensional) that encodes its meaning in context.
   > **Embedding** — a numeric vector representation of a piece of text (token, sentence, document) such that *semantically similar* text lands at *nearby points* in the vector space. This is the backbone of RAG retrieval.

2. **Attention** — for each token, the model computes how much it should "attend to" every other token in the context, blending their information. This is why context matters: the model literally reads the whole window to decide the next token.
   > **Self-attention** — the mechanism by which each token gathers information from all other tokens in the context window. Its cost grows roughly with the *square* of the sequence length (O(n²)), which is why very long contexts are expensive.

The model is a stack of transformer **layers** (e.g. 32, 80). Each layer has attention plus a **feed-forward network (FFN/MLP)** — a couple of big matrices that do most of the "knowledge storage."

> **MLP / feed-forward** — multilayer perceptron; the dense matrix-multiply part of each layer. Empirically a lot of factual knowledge is stored in these matrices.

### 2.3 The training stages (so you know what "fine-tuning" continues)

LLMs are built in stages:

1. **Pretraining** — train on trillions of tokens of general text to predict the next token. This is where the bulk of world knowledge and language ability is learned. Costs millions of dollars and thousands of GPUs. You almost never do this.
   > **GPU** — graphics processing unit; the parallel hardware that does the matrix math. Training LLMs uses clusters of high-end GPUs (e.g. NVIDIA H100/A100).

2. **Supervised fine-tuning (SFT)** — train on curated `(instruction, ideal response)` pairs so the model follows instructions instead of just continuing text. Turns a "base" model into an "instruct"/"chat" model.
   > **SFT** — supervised fine-tuning. "Supervised" = you provide the correct answer for each input. This is the kind of fine-tuning most application teams do.

3. **Preference optimization / RLHF** — further align the model to human preferences (helpful, harmless, honest) using techniques like RLHF or DPO.
   > **RLHF** — reinforcement learning from human feedback: humans rank model outputs; the model is trained to prefer the higher-ranked ones.
   > **DPO** — direct preference optimization: a simpler, RL-free way to train on preference pairs `(prompt, chosen, rejected)`. Increasingly used because it's stable and cheap.

**"Fine-tuning" in this chapter usually means stage 2 (SFT) applied by you, on top of an already-instruct-tuned model, to specialize it.** Sometimes it includes DPO.

### 2.4 What "fine-tuning" means mechanically

Fine-tuning = **continue gradient descent on your data**, starting from the published weights.

> **Gradient descent** — the training algorithm. For each training example you compute how wrong the model was (the *loss*), compute the *gradient* (the direction to nudge each weight to reduce the loss), and take a small step. Repeat over the dataset many times.
> **Loss** — a single number measuring prediction error. For LLMs it's typically **cross-entropy**: how surprised the model was by the correct next token. Lower = better.
> **Epoch** — one full pass over your training dataset. Fine-tuning usually runs 1–4 epochs.
> **Learning rate** — the size of each gradient step. Too high = unstable / forgets everything; too low = learns nothing. For fine-tuning this is small (e.g. 1e-5 to 2e-4 depending on method).

#### Full fine-tuning vs parameter-efficient fine-tuning (PEFT)

**Full fine-tuning** updates *every* weight in the model.

- Pros: maximum capacity to change behavior.
- Cons: you must store optimizer state for *all* parameters. The rule of thumb for the **Adam optimizer** is roughly **~16 bytes per parameter** during training (weights + gradients + two optimizer moments, in mixed precision). A 7B model thus needs **~112 GB** of GPU memory just for state — beyond a single consumer GPU, and you get a *full new copy* of the model per task (14 GB+ each in 16-bit).
  > **Adam / AdamW** — the standard optimizer for deep learning. It keeps two running statistics ("moments") per parameter, which is why training memory is several times the model size. AdamW is Adam with corrected weight decay; it's the default.
  > **Optimizer state** — the extra bookkeeping numbers the optimizer maintains per parameter. This, not the weights, dominates training memory.

**Parameter-efficient fine-tuning (PEFT)** updates a *tiny* fraction of parameters (often <1%) while freezing the rest. The dominant technique is **LoRA**.

##### LoRA (Low-Rank Adaptation)

The insight: the *update* you apply to a big weight matrix during fine-tuning is empirically **low-rank** — it can be well-approximated by the product of two skinny matrices.

For a weight matrix `W` of shape `d × k`, instead of learning a full update `ΔW` (also `d × k`, huge), LoRA learns:

```
ΔW = B · A          # B is d×r, A is r×k, with rank r ≪ min(d,k)
W_effective = W_frozen + (α/r) · (B · A)
```

- `r` is the **rank** (commonly 8, 16, 32, 64). Larger `r` = more capacity, more params, more memory.
- `α` (alpha) is a **scaling factor** controlling how strongly the adapter affects the model; the effective scale is `α/r`. A common heuristic is `α = 2·r` or `α = r`.
- Only `A` and `B` are trained; `W` stays frozen. For a 4096×4096 matrix with r=8, you train 4096·8 + 8·4096 = ~65K params instead of ~16.8M — a **~256× reduction** on that matrix.

> **Rank** — in linear algebra, the number of linearly independent rows/columns of a matrix; informally, its "true dimensionality." A low-rank update is one expressible with few independent directions.

Why LoRA is a big deal operationally:

- The trained **adapter** is tiny (often 10–200 MB), not a full model copy.
- You can keep **many adapters** for one base model and swap them at serving time ("LoRA hot-swapping"). One 70B base, dozens of task adapters.
- Training memory drops dramatically because you only store optimizer state for the adapter params.
- At inference you can **merge** the adapter into the base (`W += BA`) for zero added latency, or keep it separate to swap.

> **Adapter** — the small set of extra weights (here, the LoRA `A`/`B` matrices) that, combined with the frozen base, specialize the model.

##### QLoRA (Quantized LoRA)

QLoRA makes LoRA fit on a *single consumer GPU* by quantizing the frozen base model to 4-bit while training the LoRA adapter in higher precision.

> **Quantization** — storing weights in fewer bits (e.g. 4-bit instead of 16-bit) to cut memory ~4×, accepting a small accuracy loss. **NF4 (4-bit NormalFloat)** is the quantization format QLoRA introduced, designed for normally-distributed weights.

QLoRA's three tricks:
1. **4-bit NF4** quantization of the frozen base weights (huge memory save).
2. **Double quantization** — even the quantization constants are quantized, saving a bit more.
3. **Paged optimizers** — spill optimizer state to CPU RAM when GPU memory spikes (uses NVIDIA unified memory), preventing out-of-memory crashes.

Net effect: you can fine-tune a **65B/70B model on a single 48 GB GPU**, or a 7B on a 16 GB consumer card. QLoRA is the default for budget-constrained fine-tuning. It is slightly slower per step than LoRA (dequantization overhead) but vastly cheaper to run.

Quick comparison:

| Method | Trains | Train memory (7B, rough) | Output artifact | Speed | When |
|---|---|---|---|---|---|
| Full FT | All params | ~100–120 GB | Full model copy (~14 GB) | Fastest/step | Max behavior change, big budget |
| LoRA | <1% (adapters) | ~16–24 GB | Adapter (~10–200 MB) | Fast | Default for most teams |
| QLoRA | <1% (adapters), 4-bit base | ~6–12 GB | Adapter (~10–200 MB) | ~Slightly slower | Single-GPU / budget |

(Memory numbers are order-of-magnitude and depend on sequence length, batch size, rank, and library; treat as guidance, not guarantees.)

### 2.5 What fine-tuning is GOOD at

- **Style and tone** — make every response sound like your brand, a persona, a legal register.
- **Output format** — always emit valid JSON of a specific schema, a particular markdown layout, a function-call signature, SQL in your dialect.
- **Narrow tasks / classification** — sentiment, intent routing, triage labels, extraction. A small fine-tuned model can match a giant general model on a narrow task at a fraction of the cost/latency.
- **Following implicit conventions** — domain shorthand, idioms, a coding style guide, redaction rules.
- **Latency/cost reduction** — bake instructions into weights so you can drop a long system prompt and few-shot examples, shrinking each request's token count. Often you can also **distill** a big model's behavior into a smaller, cheaper one.
  > **Distillation** — train a small "student" model on outputs from a large "teacher" model, transferring behavior cheaply.
- **Tool / API call reliability** — teach the exact tool-calling grammar so the model stops malforming calls.

### 2.6 What fine-tuning is BAD at

- **Injecting fresh or frequently-changing facts.** Knowledge baked into weights is a snapshot. To update it you must retrain. This is the #1 misuse.
- **Reliable factual recall in general.** Fine-tuning on facts tends to teach the *style* of those facts more than the facts themselves, and can *increase hallucination* if you train it to confidently state things it only half-learned (research on "fine-tuning on new knowledge" shows it can make the model more prone to hallucinate). This is a critical, counterintuitive point: **teaching a model new facts via fine-tuning can make it *worse* at honesty**, because it learns the pattern "answer confidently" without truly internalizing the fact.
- **Auditability / citations.** Weights can't cite a source. RAG can.
- **Per-user / per-tenant data.** You can't (sanely) fine-tune one model per customer in a multi-tenant SaaS; RAG with per-tenant filters is the right tool.
- **Anything requiring guaranteed up-to-the-minute correctness** (prices, inventory, account balances).

### 2.7 RAG from first principles

**RAG = Retrieval-Augmented Generation.** Instead of asking the model to answer from memory, you (1) **retrieve** relevant documents from a knowledge store and (2) **augment** the prompt with them, so the model answers grounded in the supplied text.

The canonical pipeline:

```
1. INGEST (offline):
   raw docs → chunk → embed each chunk → store vectors + text in a vector DB

2. QUERY (online):
   user question → embed → similarity search in vector DB → top-k chunks
   → build prompt: [system] + [retrieved chunks] + [question] → LLM → answer (+ citations)
```

> **Chunk / chunking** — splitting a document into smaller pieces (e.g. 200–1000 tokens) so each retrievable unit is focused and fits in context. Chunking strategy massively affects RAG quality.
> **Vector database** — a datastore optimized for *nearest-neighbor search* over embedding vectors (e.g. find the 5 chunks whose embeddings are closest to the query embedding). Examples: pgvector, Pinecone, Weaviate, Milvus, Qdrant, Elasticsearch/OpenSearch kNN.
> **k / top-k** — how many chunks you retrieve. Typical k = 3–20. Too few misses context; too many adds noise and cost.
> **Embedding model** — a separate model that turns text into vectors. Examples: OpenAI `text-embedding-3-large`, Cohere embed, open-source BGE/E5. Often different from the generation model.

RAG's superpowers, point-for-point against fine-tuning's weaknesses:
- Knowledge is **fresh** — update the index, not the model.
- Knowledge is **auditable** — you can show the exact chunks and cite them.
- Knowledge is **per-tenant** — filter the vector search by `tenant_id`.
- **No GPU training** required — it's a data/infra problem, not an ML-training problem.

RAG's costs:
- You must **build and operate retrieval infra** (embedding, vector DB, chunking, reranking).
- Quality is **bounded by retrieval quality** — "garbage retrieved in, garbage out." If the right chunk isn't fetched, the model can't use it.
- Every request **pays for the injected tokens** (cost + latency + context-window pressure).

### 2.8 Prompt engineering and long-context, briefly

- **Prompt engineering** — crafting the instruction, role, constraints, and **few-shot examples** in the prompt itself.
  > **Few-shot** — including a handful of input→output examples in the prompt to demonstrate the task. **Zero-shot** = no examples, just instructions.
  > **In-context learning** — the model's ability to "learn" a task from examples in the prompt *without any weight change*. This is what makes few-shot work and is the cheapest lever of all.

- **Long-context** — modern models have large context windows (e.g. 128K, 200K, even 1M tokens). You can paste an entire document set into the prompt. This is "RAG without retrieval" for bounded data, but it has limits: cost scales with tokens, latency grows, and models suffer **"lost in the middle"** — accuracy drops for facts buried in the middle of a very long context.
  > **Lost in the middle** — the empirical finding that LLMs recall information best at the *start* and *end* of a long context and worst in the *middle*. Implication: don't rely on a huge context to surface a single buried fact; retrieval + reranking that puts the key chunk near the edges does better.

---

## 3. How it works internally

This is the heart of the chapter. We trace both pipelines step by step.

### 3.1 Fine-tuning: the internal lifecycle (LoRA/QLoRA, the common case)

**Phase 0 — Decide and scope.** Confirm the problem is a *behavior/format/skill* problem, not a *fresh-facts* problem (else use RAG). Define a measurable success metric *before* touching GPUs.

**Phase 1 — Data preparation (where 80% of the value and the risk lives).**
1. **Collect** examples of the desired behavior: `(prompt, ideal_completion)` pairs for SFT, or `(prompt, chosen, rejected)` triples for DPO.
2. **Format** to the model's chat template. *This is a common silent bug:* every instruct model expects a specific template (special tokens like `<|im_start|>`, `[INST]`, `<|begin_of_text|>`). Using the wrong template degrades or breaks training.
   > **Chat template** — the exact string format (roles, special tokens) the model was instruct-tuned with. You must match it. Libraries expose `tokenizer.apply_chat_template(...)`.
3. **Clean & deduplicate** — remove near-duplicates, fix label noise, balance classes. Label noise is the single biggest quality killer.
4. **Mask the prompt tokens** so loss is computed *only on the completion* (you don't want to train the model to generate the user's question). This is **completion-only / instruction masking**.
5. **Split** into train / validation / (held-out) test. The validation set drives early stopping; the test set is the honest final judge.

**Phase 2 — Configure training.**
- Choose base model and method (LoRA vs QLoRA vs full).
- Set **hyperparameters**: rank `r`, alpha `α`, learning rate, epochs (1–4 typical), batch size, sequence length, LoRA `target_modules` (which matrices get adapters — often the attention projections `q_proj,k_proj,v_proj,o_proj` and sometimes MLP `gate/up/down_proj`).
  > **Hyperparameter** — a setting you choose *before* training (vs a weight learned *during* training). Examples: learning rate, epochs, rank.

**Phase 3 — The training loop (what the GPU does, per step).**
1. **Forward pass** — run a batch through the model with adapters active (`W + BA`), producing token probabilities.
2. **Loss** — compute cross-entropy between predictions and the (masked) target tokens.
3. **Backward pass** — backpropagation computes gradients, but **only for the LoRA `A`/`B` params** (the frozen base contributes gradients flowing *through* it but its weights aren't updated).
   > **Backpropagation** — the algorithm that computes the gradient of the loss w.r.t. each trainable parameter by applying the chain rule backward through the network.
4. **Optimizer step** — AdamW updates `A`/`B`. In QLoRA, the base stays 4-bit; computations dequantize on the fly.
5. **Repeat** for all batches × epochs. Periodically evaluate on the validation set; **early-stop** when validation loss stops improving (to avoid overfitting).
   > **Overfitting** — the model memorizes the training set (train loss keeps dropping) but generalizes worse (validation loss rises). Early stopping and regularization fight it.
   > **Gradient accumulation** — simulate a large batch by summing gradients over several small "micro-batches" before stepping the optimizer; lets you train big effective batches on small GPUs.
   > **Gradient checkpointing** — trade compute for memory by recomputing activations during the backward pass instead of storing them; common in QLoRA to fit long sequences.

**Phase 4 — The catastrophic-forgetting risk.**
> **Catastrophic forgetting** — when fine-tuning on a narrow task erodes the model's *general* abilities it had before. The classic example: fine-tune on JSON extraction, and suddenly the model is worse at open-ended chat or reasoning, or forgets instruction-following niceties.

Why it happens: gradient descent on a narrow distribution pulls weights toward that distribution, overwriting the broad knowledge encoded earlier. Full fine-tuning is most prone; LoRA is *less* prone because the base is frozen and the update is low-rank/small. Mitigations:
- **LoRA/PEFT** instead of full FT (frozen base preserves general ability).
- **Lower learning rate, fewer epochs.**
- **Mix in general/"replay" data** (a slice of broad instruction data) so the model doesn't see only your narrow task.
- **Evaluate on general benchmarks** before/after to *measure* the regression, not just hope.

**Phase 5 — Evaluation (covered in depth in §6.5).** Run held-out test set; compare to the base model + RAG + prompt-only baselines on the *real* metric.

**Phase 6 — Packaging & serving.**
- Save the adapter. Optionally **merge** into base for deployment (`merge_and_unload()`), or keep separate for **multi-adapter serving** (e.g. vLLM/LoRAX can serve many adapters on one base).
  > **vLLM** — a high-throughput LLM serving engine. **PagedAttention** is its key trick: it manages the KV cache like OS virtual memory pages, reducing waste and boosting throughput.
  > **KV cache** — during generation, the model caches the key/value attention vectors for already-generated tokens so it doesn't recompute them; this is the main memory consumer at inference and grows with context length.
- Version the adapter alongside the base model hash and the training data hash for reproducibility.

#### Fine-tuning state machine (compact)

```
DATA_PREP → CONFIGURED → TRAINING ⇄ VALIDATING → (early stop?) → DONE
   │                         │
   └── bad data → STOP       └── overfit/forget detected → adjust hp → re-TRAIN
DONE → PACKAGE(merge|adapter) → EVAL_vs_BASELINES → DEPLOY → MONITOR(drift) → RETRAIN
```

### 3.2 RAG: the internal lifecycle

**Ingestion (offline / batch):**
1. **Load** documents from sources (S3, Confluence, DB, PDFs).
2. **Parse / clean** — extract text, strip boilerplate, preserve structure (headings, tables).
3. **Chunk** — split into retrievable units. Strategies: fixed-size with overlap, recursive by structure, semantic (split on topic shifts), or "parent-child" (retrieve small, return large).
   > **Chunk overlap** — repeating a few tokens between adjacent chunks so a fact spanning a boundary isn't lost. Common overlap 10–20%.
4. **Embed** each chunk with the embedding model → vector.
5. **Index** — store `(vector, text, metadata)` in the vector DB. Build the **ANN index**.
   > **ANN (Approximate Nearest Neighbor)** — algorithms (e.g. **HNSW**, IVF) that find *almost* the closest vectors very fast, trading a little accuracy for big speed. Exact search is O(N) per query; ANN is sub-linear.
   > **HNSW (Hierarchical Navigable Small World)** — the most common ANN index: a layered graph you greedily navigate to find near neighbors. Tunable via `M` (graph degree) and `ef` (search breadth).

**Query (online / per request):**
1. **(Optional) Query transformation** — rewrite/expand the user query, generate multiple sub-queries (**multi-query**), or **HyDE** (generate a hypothetical answer and embed *that* to retrieve).
   > **HyDE (Hypothetical Document Embeddings)** — generate a fake answer with the LLM, embed it, and search with that; often retrieves better than embedding the raw question.
2. **Embed** the query.
3. **Retrieve** top-k by vector similarity, optionally combined with **keyword/BM25** search = **hybrid search**, optionally filtered by metadata (`tenant_id`, `date`, `acl`).
   > **BM25** — a classic lexical (keyword) ranking function. Great at exact terms/IDs where embeddings struggle. Hybrid (BM25 + vector) usually beats either alone.
   > **Cosine similarity / dot product** — the metric for "closeness" of two vectors. Cosine ignores magnitude; dot product doesn't. Pick the one your embedding model was trained for.
4. **Rerank** — a more expensive **cross-encoder** reorders the candidates for precision; keep the top few.
   > **Cross-encoder reranker** — a model that takes `(query, chunk)` *together* and scores relevance directly; slower but far more accurate than embedding similarity. Retrieve k=50 cheaply, rerank to top-5.
5. **Assemble prompt** — put system instructions + retrieved chunks (with source IDs) + the question. Often place the most relevant chunks at the **edges** (mitigate "lost in the middle").
6. **Generate** — the LLM answers, instructed to use only the provided context and to cite sources, and to say "I don't know" if the context is insufficient.
7. **Post-process** — attach citations, run a groundedness/faithfulness check, optionally a guardrail.

#### RAG control/data flow (one query)

```
question ──embed──► [vector search top-50] ─┐
question ──BM25───► [keyword top-50]        ├─► fuse ─► rerank(cross-encoder) ─► top-5
                                            ┘
top-5 ─► build prompt(system + chunks + Q) ─► LLM ─► answer + citations ─► faithfulness check
```

### 3.3 The two pipelines side by side (where they touch)

The crucial realization: these pipelines are *orthogonal* and often *stacked*. A production system frequently does:

```
[Fine-tuned model: knows the format, tone, tool-call grammar, domain skill]
        ▲
        │ prompt = system + retrieved context + question
        │
[RAG: supplies fresh, private, citable facts at query time]
```

Example: a customer-support assistant fine-tuned to always answer in the company's voice, follow the escalation policy, and emit a structured resolution object — *and* using RAG to pull the customer's actual order, the relevant policy doc, and the latest product info. Fine-tuning fixes *how*; RAG fixes *what*.

---

## 4. The complete toolkit

### 4.1 Fine-tuning libraries & APIs

| Tool | Layer | What it does | Key knobs / defaults |
|---|---|---|---|
| **Hugging Face `transformers`** | Core | Load models/tokenizers, `Trainer` loop | `model`, `tokenizer`, `TrainingArguments` |
| **`peft`** (HF) | PEFT | LoRA/QLoRA/adapters | `LoraConfig(r, lora_alpha, target_modules, lora_dropout, bias)` |
| **`trl`** (HF) | Trainer | `SFTTrainer`, `DPOTrainer`, `RewardTrainer` | `SFTConfig(...)`, packing, completion-only masking |
| **`bitsandbytes`** | Quantization | 4-bit/8-bit base for QLoRA | `BitsAndBytesConfig(load_in_4bit, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype, bnb_4bit_use_double_quant)` |
| **`accelerate`** | Distributed | Multi-GPU, mixed precision, FSDP/DeepSpeed glue | `accelerate config`, `accelerate launch` |
| **DeepSpeed / FSDP** | Scaling | Shard optimizer/params across GPUs (ZeRO) | ZeRO stage 1/2/3; offload to CPU/NVMe |
| **Unsloth** | Optimized | Faster/cheaper LoRA/QLoRA kernels | drop-in, ~2× speed, less memory |
| **Axolotl / LLaMA-Factory** | Config-driven | YAML-configured fine-tuning pipelines | declarative datasets/hp |
| **OpenAI fine-tuning API** | Managed | Upload JSONL, get a fine-tuned model id | `n_epochs`, `learning_rate_multiplier`, `batch_size` (auto by default) |
| **Together / Fireworks / Anyscale / Vertex / Bedrock** | Managed | Hosted LoRA/full FT for OSS & proprietary models | vendor-specific |

> **FSDP (Fully Sharded Data Parallel)** — PyTorch's way to split a model's parameters, gradients, and optimizer state across GPUs so a model bigger than one GPU still trains. **ZeRO** is DeepSpeed's equivalent (stages 1/2/3 shard progressively more).

**`LoraConfig` key parameters (the ones that matter):**

| Param | Meaning | Typical |
|---|---|---|
| `r` | rank of the update | 8, 16, 32, 64 |
| `lora_alpha` | scaling (`α/r` applied) | often `2*r` |
| `target_modules` | which matrices get adapters | attn projections; add MLP for more capacity |
| `lora_dropout` | dropout on adapter for regularization | 0.0–0.1 |
| `bias` | whether to train biases | `"none"` |
| `task_type` | e.g. `CAUSAL_LM` | `CAUSAL_LM` |

**OpenAI fine-tuning JSONL format (chat):**
```json
{"messages":[{"role":"system","content":"..."},{"role":"user","content":"..."},{"role":"assistant","content":"..."}]}
```

### 4.2 RAG libraries, stores & components

| Component | Tool examples | Notes |
|---|---|---|
| **Orchestration** | LangChain, LlamaIndex, Hareway/Haystack, semantic-kernel, Spring AI (Java) | Glue: load→chunk→embed→retrieve→prompt |
| **Embedding models** | OpenAI `text-embedding-3-small/large`, Cohere `embed-v3`, BGE, E5, GTE | Dim sizes vary (384–3072); cost per token |
| **Vector DB** | pgvector (Postgres), Pinecone, Weaviate, Milvus, Qdrant, Chroma, OpenSearch/Elasticsearch kNN, Redis | pgvector great if you're already on Postgres |
| **Rerankers** | Cohere Rerank, BGE-reranker, Jina, cross-encoders (MS MARCO) | Big precision win |
| **Keyword/lexical** | BM25 (Elasticsearch/OpenSearch, Tantivy), Postgres FTS | For hybrid search |
| **Eval** | RAGAS, TruLens, DeepEval, Arize Phoenix | Faithfulness, context precision/recall |

**Java-ecosystem note (reader profile):** **Spring AI** and **LangChain4j** are the idiomatic JVM choices. Spring AI offers `VectorStore`, `EmbeddingModel`, `ChatClient`, and an `Advisor` API (`QuestionAnswerAdvisor`) for RAG; LangChain4j offers `EmbeddingStore`, `ContentRetriever`, `AiServices`. Fine-tuning itself is overwhelmingly Python (PyTorch ecosystem); from the JVM you typically *consume* a fine-tuned model via an HTTP/OpenAI-compatible endpoint (e.g. served by vLLM/TGI) rather than train in-process.

> **TGI (Text Generation Inference)** — Hugging Face's production LLM server; like vLLM, exposes an OpenAI-compatible API and supports LoRA adapters.

### 4.3 Vector index tuning knobs (HNSW)

| Knob | Effect | Tradeoff |
|---|---|---|
| `M` (graph degree) | More edges per node | Higher recall, more memory/build time |
| `ef_construction` | Search breadth at build | Better index, slower build |
| `ef_search` | Search breadth at query | Higher recall, higher latency |
| distance metric | cosine / dot / L2 | Must match embedding model |

---

## 5. Code examples by use case

These span genuinely different scenarios. Python for the ML/training parts (the real ecosystem) and Java where you'd integrate from a backend.

### 5.1 QLoRA fine-tune of a 7B model for a structured-extraction task (Python)

Use case: you need the model to reliably extract `{name, amount, date}` JSON from messy invoices — a *format/skill* problem, ideal for fine-tuning.

```python
# pip install transformers peft trl bitsandbytes accelerate datasets
import torch
from datasets import load_dataset
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from peft import LoraConfig
from trl import SFTTrainer, SFTConfig

BASE = "mistralai/Mistral-7B-Instruct-v0.3"

# 4-bit quantization config = the "Q" in QLoRA. Frozen base lives in 4-bit NF4.
bnb = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_quant_type="nf4",            # NormalFloat4, tuned for ~normal weight dist
    bnb_4bit_compute_dtype=torch.bfloat16, # math done in bf16 for stability
    bnb_4bit_use_double_quant=True,        # quantize the quant-constants too
)

tok = AutoTokenizer.from_pretrained(BASE)
tok.pad_token = tok.eos_token              # many base models lack a pad token

model = AutoModelForCausalLM.from_pretrained(
    BASE, quantization_config=bnb, device_map="auto", torch_dtype=torch.bfloat16
)

# LoRA: train tiny low-rank adapters on the attention + MLP projections.
peft_cfg = LoraConfig(
    r=16, lora_alpha=32, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
    target_modules=["q_proj","k_proj","v_proj","o_proj","gate_proj","up_proj","down_proj"],
)

# Dataset of {"messages":[...]} chat examples. apply_chat_template handles the format.
ds = load_dataset("json", data_files="invoices_sft.jsonl", split="train")
def fmt(ex):
    return {"text": tok.apply_chat_template(ex["messages"], tokenize=False)}
ds = ds.map(fmt)

trainer = SFTTrainer(
    model=model,
    train_dataset=ds,
    peft_config=peft_cfg,
    args=SFTConfig(
        output_dir="out",
        num_train_epochs=2,                 # 1–3 is usually enough; watch val loss
        per_device_train_batch_size=2,
        gradient_accumulation_steps=8,      # effective batch = 16
        learning_rate=2e-4,                 # higher than full-FT; typical for LoRA
        bf16=True,
        gradient_checkpointing=True,        # save memory at small compute cost
        logging_steps=10,
        max_seq_length=2048,
        packing=True,                        # pack short samples to fill sequences
        dataset_text_field="text",
    ),
)
trainer.train()
trainer.save_model("invoice-extractor-lora")   # saves the ~tens-of-MB adapter only
```

Why this is the right tool: extraction format is *behavior*, not *fresh facts*; a small fine-tuned model will be cheaper and faster than prompting a giant model with examples on every call.

### 5.2 Merge an adapter and serve it (Python)

```python
from peft import PeftModel
from transformers import AutoModelForCausalLM

base = AutoModelForCausalLM.from_pretrained("mistralai/Mistral-7B-Instruct-v0.3",
                                            torch_dtype="auto", device_map="auto")
merged = PeftModel.from_pretrained(base, "invoice-extractor-lora").merge_and_unload()
merged.save_pretrained("invoice-extractor-merged")  # standalone model for vLLM/TGI
```

Then serve with vLLM (OpenAI-compatible) so any backend can call it:
```bash
vllm serve invoice-extractor-merged --port 8000
# Alternatively keep adapters separate and hot-swap:
# vllm serve mistralai/Mistral-7B-Instruct-v0.3 --enable-lora \
#     --lora-modules invoice=invoice-extractor-lora
```

### 5.3 Managed fine-tuning via the OpenAI API (Python)

Use case: you want a fine-tuned `gpt-4o-mini` that always replies in your support tone and JSON schema, no GPUs to manage.

```python
from openai import OpenAI
client = OpenAI()

# 1) Upload JSONL of {"messages":[...]} examples (>= ~50, ideally hundreds).
f = client.files.create(file=open("support_sft.jsonl","rb"), purpose="fine-tune")

# 2) Kick off the job (epochs/lr auto-selected unless overridden).
job = client.fine_tuning.jobs.create(training_file=f.id, model="gpt-4o-mini-2024-07-18")

# 3) Poll, then use the resulting model id.
# resp = client.chat.completions.create(model="ft:gpt-4o-mini:org::abc123", messages=[...])
```

### 5.4 A complete RAG pipeline in Python (pgvector + reranking)

Use case: answer questions over an internal wiki with citations and per-tenant isolation — a *fresh/private facts* problem, ideal for RAG.

```python
# Ingestion (offline)
from openai import OpenAI
import psycopg2
client = OpenAI()

def embed(texts):                       # 3072-dim for text-embedding-3-large
    r = client.embeddings.create(model="text-embedding-3-large", input=texts)
    return [d.embedding for d in r.data]

def chunk(text, size=800, overlap=120): # naive token-ish chunker
    words = text.split()
    step = size - overlap
    return [" ".join(words[i:i+size]) for i in range(0, len(words), step)]

conn = psycopg2.connect("dbname=kb")
cur = conn.cursor()
# Table: id, tenant_id, doc_id, content text, embedding vector(3072)
def ingest(tenant_id, doc_id, text):
    chunks = chunk(text)
    for c, e in zip(chunks, embed(chunks)):
        cur.execute(
          "INSERT INTO kb(tenant_id,doc_id,content,embedding) VALUES (%s,%s,%s,%s)",
          (tenant_id, doc_id, c, e))
    conn.commit()

# Query (online)
def answer(tenant_id, question, k=20, top=5):
    qe = embed([question])[0]
    # Vector search filtered by tenant. <=> is pgvector cosine distance operator.
    cur.execute(
      "SELECT content, doc_id FROM kb WHERE tenant_id=%s "
      "ORDER BY embedding <=> %s::vector LIMIT %s", (tenant_id, qe, k))
    cands = cur.fetchall()
    # Rerank with a cross-encoder for precision (pseudo; use Cohere/BGE in practice).
    reranked = rerank(question, cands)[:top]
    context = "\n\n".join(f"[{i}] {c}" for i,(c,_) in enumerate(reranked))
    prompt = (f"Answer using ONLY the context. Cite sources like [0]. "
              f"If the context is insufficient, say you don't know.\n\n"
              f"Context:\n{context}\n\nQuestion: {question}")
    resp = client.chat.completions.create(
        model="gpt-4o", messages=[{"role":"user","content":prompt}])
    return resp.choices[0].message.content, [d for _,d in reranked]
```

Key correctness features: tenant filter (security), citation instruction (auditability), "say you don't know" (reduce hallucination), rerank (precision).

### 5.5 RAG from a JVM backend with Spring AI (Java)

Use case: the reader's world — a Spring Boot service doing RAG against a fine-tuned model served at an OpenAI-compatible endpoint.

```java
// build.gradle: org.springframework.ai:spring-ai-openai-spring-boot-starter,
//               org.springframework.ai:spring-ai-pgvector-store-spring-boot-starter
@RestController
class SupportController {
    private final ChatClient chat;
    private final VectorStore vectors;

    SupportController(ChatClient.Builder builder, VectorStore vectors) {
        this.vectors = vectors;
        this.chat = builder.build();
    }

    @PostMapping("/ask")
    String ask(@RequestParam String tenantId, @RequestBody String question) {
        // Retrieve top-k chunks for this tenant only (metadata filter).
        var docs = vectors.similaritySearch(
            SearchRequest.query(question).withTopK(5)
                .withFilterExpression("tenantId == '" + tenantId + "'"));

        String context = docs.stream()
            .map(Document::getContent)
            .collect(Collectors.joining("\n\n"));

        // The model behind ChatClient may be a fine-tuned, OpenAI-compatible endpoint.
        return chat.prompt()
            .system("Answer using only the provided context and cite sources. "
                  + "If insufficient, say you don't know.")
            .user(u -> u.text("Context:\n{ctx}\n\nQuestion: {q}")
                        .param("ctx", context).param("q", question))
            .call()
            .content();
    }
}
```

Spring AI also offers `QuestionAnswerAdvisor` to do retrieval+augmentation declaratively:
```java
String answer = chat.prompt()
    .advisors(new QuestionAnswerAdvisor(vectors, SearchRequest.defaults().withTopK(5)))
    .user(question)
    .call().content();
```

### 5.6 DPO preference fine-tuning to fix a behavior (Python)

Use case: the model is too verbose / occasionally rude. You have `(prompt, chosen, rejected)` pairs and want to *steer behavior* without new facts.

```python
from trl import DPOTrainer, DPOConfig
# dataset rows: {"prompt": ..., "chosen": ..., "rejected": ...}
trainer = DPOTrainer(
    model=model, ref_model=None,            # ref_model=None => uses frozen copy
    args=DPOConfig(beta=0.1, learning_rate=5e-6, num_train_epochs=1,
                   per_device_train_batch_size=2, gradient_accumulation_steps=8),
    train_dataset=pref_ds, processing_class=tok, peft_config=peft_cfg,
)
trainer.train()
# beta controls how much to trust preferences vs stay near the reference policy.
```

### 5.7 The combined system: RAG + fine-tuned model + structured output (Python)

Use case: financial-report assistant. Fine-tuned for the bank's tone + a strict JSON schema and refusal policy; RAG supplies the latest filings.

```python
def answer(question, tenant):
    chunks = retrieve(question, tenant)          # RAG: fresh, private, citable facts
    context = "\n\n".join(f"[{i}] {c}" for i,c in enumerate(chunks))
    # The model is a FINE-TUNED model: it already "knows" the tone, refusal rules,
    # and the exact JSON schema, so the prompt can be short (latency/cost win).
    resp = client.chat.completions.create(
        model="ft:our-bank-assistant",
        messages=[{"role":"system","content":"Use only context; cite; output schema."},
                  {"role":"user","content":f"{context}\n\nQ: {question}"}],
        response_format={"type":"json_object"})
    return resp.choices[0].message.content
```

This is the canonical "both, plus a good prompt" architecture.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency

- **Fine-tuning wins latency/cost at inference** by shrinking prompts: instructions/few-shots are baked in, so each request sends fewer tokens. It also enables **distillation** to a smaller, faster model.
- **RAG adds latency**: embedding the query (~10–50 ms), vector search (single-digit to tens of ms), reranking (50–300 ms for a cross-encoder), and *more input tokens* for the LLM (the dominant cost). Budget for it.
- **Token economics**: LLM cost/latency scale with input+output tokens. RAG inflates input tokens; fine-tuning deflates them. Long-context is the most token-hungry. Do the arithmetic for your QPS.
  > **QPS** — queries per second; your throughput target.
- **Caching**: cache embeddings, cache retrieval results for hot queries, and use **prompt caching** (provider feature) for stable prefixes.

### 6.2 Correctness & concurrency

- **RAG correctness is bounded by retrieval.** Measure **context recall** (did we fetch the chunk that contains the answer?) and **context precision** (is the retrieved set free of noise?). If recall is low, no prompt fixes it.
- **Fine-tuning correctness** hinges on **data quality** (label noise, leakage) and **avoiding overfitting/forgetting**. Always keep a held-out test set the model never saw.
- **Concurrency**: training is usually a batch job (no concurrency concern in your app). Serving (both RAG and FT) is concurrent — size the inference server (vLLM/TGI) for KV-cache memory and max concurrent sequences; the **KV cache** is your concurrency bottleneck.

### 6.3 Memory & cost

- **Training memory**: full FT ≈ 16 bytes/param (Adam); LoRA slashes optimizer state; QLoRA additionally 4-bits the base. Use gradient checkpointing/accumulation to fit.
- **Serving memory**: model weights + KV cache. Quantize for serving (AWQ/GPTQ/4-bit) to fit bigger models.
- **Cost reality check**: a small LoRA/QLoRA job on a single rented GPU can cost **single-digit to low-tens of dollars**; managed fine-tuning is priced per training token. RAG has *ongoing* per-query cost (embeddings + larger prompts + vector DB hosting) but *no* training cost. Fine-tuning has *upfront* cost + *re-training* cost whenever data changes — which is exactly why you don't fine-tune for changing facts.

### 6.4 Security

- **RAG**: enforce **tenant/ACL filters at the vector-search layer** (never rely on the LLM to "not reveal" other tenants' data). Sanitize retrieved content — RAG is an injection surface (**indirect prompt injection**: a malicious document instructs the model). Strip/escape, and instruct the model that context is data, not commands.
  > **Prompt injection** — attacker text that hijacks the model's instructions. *Indirect* injection hides such text in retrieved documents or web pages.
- **Fine-tuning**: training data can **leak** into outputs (memorization). Don't fine-tune on secrets you can't afford to have regurgitated. Scrub PII. Be aware of **data poisoning** if your training data is user-contributed.
- **Auditability**: regulated domains often *require* citations → favors RAG over baking facts into weights.

### 6.5 Evaluation (do this rigorously — it's where teams cut corners)

Always evaluate **fine-tuned vs base vs RAG vs prompt-only** on the *same* held-out test set, on the *real* metric, before shipping.

- **Fine-tuning metrics**: task accuracy/F1 (classification), exact-match/JSON-valid rate (extraction/format), and **general-capability regression** (run a general benchmark before/after to detect catastrophic forgetting). Use **LLM-as-judge** for open-ended quality, but calibrate the judge against human ratings.
  > **F1** — harmonic mean of precision and recall; a standard classification metric.
  > **LLM-as-judge** — using a strong LLM to score outputs against a rubric. Cheap and scalable but biased; validate against humans.
- **RAG metrics** (RAGAS-style):
  - **Faithfulness/groundedness** — is the answer supported by the retrieved context (no hallucination)?
  - **Answer relevance** — does it actually address the question?
  - **Context precision** & **context recall** — retrieval quality.
- **Golden set**: maintain a curated `(question, ideal answer, must-cite source)` set; run it in CI on every prompt/model/index change. Track regressions like you track test failures.
- **A/B test in production** with guardrails before full rollout.

### 6.6 Observability

- Log retrieval traces (query, retrieved chunk IDs, scores), the assembled prompt, token counts, latency per stage, and the final answer + citations.
- For fine-tuned models, track output schema-validity rate, refusal rate, and drift over time. **Concept drift** (the world changes) is the signal to re-train or, better, move that knowledge into RAG.
  > **Drift** — the input/world distribution shifts away from training, degrading model quality over time.

### 6.7 Testability & production hardening

- **RAG**: unit-test chunking; integration-test retrieval recall on the golden set; contract-test the citation format; chaos-test "no relevant docs" → must say "I don't know."
- **Fine-tuning**: pin base-model hash, data hash, hyperparameters, and library versions for reproducibility. Treat the adapter as a versioned artifact in your registry.
- **Guardrails**: input/output filters, schema validators (reject and retry on invalid JSON), max-token caps, refusal policies, and a fallback to the base model if the adapter misbehaves.

### 6.8 Anti-patterns (memorize these — they're common interview traps)

1. **Fine-tuning to add a knowledge base.** Use RAG. Fine-tuning facts is expensive, stale-prone, and can *increase* hallucination.
2. **Skipping prompt engineering** and jumping to fine-tuning. Always exhaust prompting/few-shot first; it's free and reversible.
3. **No held-out eval / no baseline.** "It looks better" is not evidence. Compare against base + RAG + prompt-only.
4. **Training on dirty/leaky data.** Label noise and train/test leakage produce inflated metrics and bad models.
5. **Fine-tuning per customer** in multi-tenant SaaS. Use one model + RAG with tenant filters.
6. **Ignoring catastrophic forgetting** — shipping a model that's great at the narrow task but worse at everything else.
7. **RAG with no reranking / no hybrid search** and then blaming the LLM for "hallucinating" when retrieval simply missed the chunk.
8. **Letting the LLM enforce tenant isolation** instead of filtering at the DB. Security must be deterministic.
9. **Dumping everything into long-context** and ignoring "lost in the middle" + per-token cost.
10. **Re-fine-tuning every time a fact changes.** That's a RAG problem wearing a fine-tuning costume.

---

## 7. Advanced topics & deep internals

### 7.1 LoRA internals & variants

- **Where LoRA helps most**: targeting all linear layers (attention *and* MLP) gives more capacity than attention-only; the cost is more adapter params. Rank is the main capacity dial.
- **rsLoRA (rank-stabilized LoRA)**: scales by `α/√r` instead of `α/r`, stabilizing high ranks.
- **DoRA (Weight-Decomposed LoRA)**: decomposes weights into magnitude + direction, updating them separately; often beats vanilla LoRA at equal params.
- **LoRA+**: uses different learning rates for `A` and `B` matrices for faster convergence.
- **Merging multiple LoRAs**: you can linearly combine adapters (with care) for **model merging**; conflicts can degrade quality.
- **Serving many adapters**: **S-LoRA / LoRAX / vLLM multi-LoRA** keep one base in GPU and swap thousands of small adapters per request — economically transformative for per-customer style adapters (note: *style*, not *facts*).

### 7.2 QLoRA internals

- **NF4** is information-theoretically optimal for zero-mean normal data (which weights approximate). **Double quantization** quantizes the per-block scale factors, saving ~0.4 bits/param. **Paged optimizers** use CUDA unified memory to page optimizer state to CPU during spikes, avoiding OOM at the cost of occasional slowdown.
- **Accuracy**: QLoRA was shown to nearly match 16-bit fine-tuning quality on many tasks — the 4-bit base is *frozen* and only used in forward/backward math, while the trainable adapter stays in higher precision.

### 7.3 Catastrophic forgetting — deeper

- **Mechanism**: narrow-task gradients overwrite the broad manifold learned in pretraining/instruct-tuning. Worse with high LR, many epochs, full FT, and homogeneous data.
- **Mitigations beyond LoRA**: **replay/rehearsal** (mix general data in), **EWC (Elastic Weight Consolidation)** (penalize changing weights important to old tasks), **L2-SP** (regularize toward the original weights), low LR, and **early stopping** on a *general* validation set, not just the task one.
  > **EWC** — adds a penalty proportional to how important each weight was to previous tasks, so important weights move less. Reduces forgetting.

### 7.4 Advanced RAG

- **Hybrid + reranking** is table stakes for quality. Beyond that:
  - **Parent-document / small-to-big**: embed small chunks for precise matching but return the larger parent for context.
  - **Multi-query / RAG-fusion**: generate several query variants, retrieve for each, fuse with **Reciprocal Rank Fusion (RRF)**.
    > **RRF** — combine multiple ranked lists by summing `1/(k+rank)`; robust fusion without score calibration.
  - **HyDE**: embed a hypothetical answer for better recall on under-specified queries.
  - **GraphRAG**: build a knowledge graph from the corpus; answer multi-hop/global questions by traversing it instead of pure similarity.
    > **Multi-hop** — a question needing facts from multiple documents chained together; pure top-k similarity often fails these.
  - **Agentic / iterative RAG**: the model decides to retrieve again, refine the query, or use tools mid-reasoning.
  - **Contextual retrieval**: prepend a short LLM-generated context blurb to each chunk before embedding, improving recall (a recent, effective technique).
  - **Self-RAG / corrective RAG (CRAG)**: the model critiques retrieved context and re-retrieves or abstains if it's irrelevant.

### 7.5 The "fine-tune the embedding/retriever" lever

A frequently-missed expert move: instead of (or in addition to) fine-tuning the *generator*, **fine-tune the embedding model or train a domain reranker** on your `(query, relevant_chunk)` pairs. This directly raises retrieval recall — often a bigger win than touching the generator, and it composes with RAG.

### 7.6 Long-context vs RAG, with numbers

- Attention is ~O(n²) in sequence length; a 200K-token prompt is enormously more expensive than a 4K-token RAG prompt for the same answer.
- **Lost-in-the-middle** means a 1M-token context is not a substitute for good retrieval; recall of a single buried fact degrades. RAG that surfaces the right 5 chunks and places them at the edges typically beats brute-force long-context on both cost and accuracy.
- Long-context *does* shine when relationships span the *whole* document (e.g. "summarize this 300-page contract's obligations") where chunking would fragment the reasoning.

### 7.7 Continued pretraining vs SFT

For deep domain *fluency* (e.g. legal/medical jargon, a new programming language), **continued pretraining** (more next-token training on raw domain text, unlabeled) can help — but it's expensive and still doesn't make facts reliably *retrievable*; pair with RAG. SFT then teaches the *task* on top.

---

## 8. Tradeoffs & decision frameworks

### 8.1 The master comparison

| Dimension | Prompt eng. | Long-context | RAG | Fine-tuning |
|---|---|---|---|---|
| Changes model weights | No | No | No | **Yes** |
| Best for | Eliciting latent ability | Bounded reference blob | Fresh/private/large facts | Style, format, narrow skill, latency/cost |
| Fresh facts | No | Yes (paste) | **Yes (best)** | **No (stale)** |
| Private/per-tenant data | Limited | Possible (paste) | **Yes (filter)** | Risky/expensive |
| Citations/audit | No | Weak | **Yes** | No |
| Upfront cost | ~0 | ~0 | Medium (infra) | Medium–high (GPUs/data) |
| Per-query cost | Low | **High (tokens)** | Medium (tokens+infra) | **Low (short prompt)** |
| Latency | Low | High | Medium | **Low** |
| Update cadence | Instant | Instant | **Instant (re-index)** | Slow (re-train) |
| Skill/behavior change | Some | No | No | **Yes (best)** |
| Infra/ops burden | None | None | Vector DB + pipeline | Training + serving MLOps |
| Risk | Brittle prompts | Lost-in-middle, cost | Retrieval misses, injection | Forgetting, overfit, hallucination on facts |

### 8.2 Decision rules (use when / avoid when)

**Use prompt engineering when:** the model can already do it with better instructions/few-shot. *Always try first.* Avoid relying on it alone when behavior must be *guaranteed* consistent or prompts grow huge.

**Use long-context when:** you have a bounded blob (a few docs/a contract) that fits, you need whole-document reasoning, and you don't want retrieval infra. Avoid when the corpus is large, costs matter at scale, or a single buried fact must be found reliably.

**Use RAG when:** the model needs knowledge it lacks — private, fresh, large, or citation-required; data changes often; multi-tenant. Avoid when the problem is *behavior/format* (RAG won't change how the model writes) or when retrieval can't be made reliable for your data.

**Use fine-tuning when:** you need consistent *style/format/tone*, a *narrow skill/classification*, reliable *tool-call grammar*, or *lower latency/cost* by shrinking prompts/distilling. Avoid for fresh/changing facts, per-tenant knowledge, or when you lack a held-out eval and quality data.

**Combine RAG + fine-tuning when:** you need *both* a specific behavior/format *and* fresh/private facts — e.g. a branded, schema-strict assistant over a live knowledge base. This is the most common production answer for serious products.

### 8.3 A practical decision ladder

1. Define the metric and a golden test set.
2. Try **prompt engineering + few-shot**. Measure.
3. If it needs *knowledge it lacks* → add **RAG** (hybrid + rerank). Measure.
4. If output *format/style/skill* is still inconsistent, or prompts are too long/slow/expensive → **fine-tune** (start LoRA/QLoRA). Measure against baselines + check for forgetting.
5. For serious products, you'll likely end at **RAG + fine-tuned model + a tight prompt**. Iterate retrieval quality (often the highest-leverage knob).

### 8.4 Worked decision examples

| Scenario | Right lever(s) | Why |
|---|---|---|
| "Answer questions over our 50k-page internal wiki, with citations" | **RAG** | Fresh, large, private, audit → retrieval, not weights |
| "Always reply in our brand voice and a fixed JSON schema" | **Fine-tune** | Pure behavior/format; bake it in, shrink prompt |
| "Classify 1M support tickets/day into 20 intents cheaply" | **Fine-tune a small model** | Narrow task; latency/cost; distill from a big model |
| "Summarize this single 300-page contract's obligations" | **Long-context** (or hierarchical) | Whole-doc reasoning, bounded, no infra |
| "Branded support bot over live order data and policies" | **RAG + fine-tune** | Behavior (FT) + fresh private facts (RAG) |
| "Add yesterday's product launch to the bot's knowledge" | **RAG (re-index)** | Changing fact → index, never re-train |
| "Model malforms our tool-call syntax 10% of the time" | **Fine-tune** | Teach the grammar reliably |
| "Improve retrieval on legal jargon queries" | **Fine-tune the embedding/reranker** | Fix retrieval, the real bottleneck |
| "Make answers less verbose and never rude" | **DPO fine-tune** | Preference/behavior steering |
| "MVP, tiny budget, a dozen reference docs" | **Prompt + long-context** | Cheapest; defer infra |

---

## 9. Failure modes & debugging

### 9.1 Fine-tuning failure modes

| Symptom | Likely cause | Diagnose | Fix |
|---|---|---|---|
| Model great on task, worse at everything else | **Catastrophic forgetting** | Run general benchmark before/after | LoRA, lower LR, fewer epochs, replay data |
| Train loss ↓ but val/test worse | **Overfitting** | Watch val loss curve; it rises | Early stop, more/cleaner data, dropout |
| Model confidently wrong on new facts | **Fine-tuned facts → hallucination** | Probe held-out facts | Move facts to **RAG**, not weights |
| Garbled outputs / no learning | **Wrong chat template / no prompt masking** | Print a formatted training sample | Use `apply_chat_template`, completion-only loss |
| Inflated eval numbers | **Train/test leakage** | Check overlap/hashes | De-dup, proper split |
| OOM during training | Memory (optimizer/activations) | Watch GPU mem | QLoRA, grad checkpointing/accumulation, shorter seq |
| Unstable loss / NaNs | LR too high / precision | Loss spikes | Lower LR, bf16, warmup |
| Adapter does nothing at serving | Adapter not loaded/merged or wrong base | Diff outputs vs base | Verify base hash, merge or load adapter |

### 9.2 RAG failure modes

| Symptom | Likely cause | Diagnose | Fix |
|---|---|---|---|
| "It hallucinated!" but context was wrong | **Retrieval miss** (low recall) | Log retrieved chunk IDs vs the truth | Hybrid search, rerank, better chunking, fine-tune embeddings |
| Right doc retrieved but answer ignores it | Prompt/placement; **lost-in-middle** | Inspect assembled prompt | Reorder (key chunks at edges), stronger "use only context" instruction |
| Answers leak other tenants' data | **No/weak tenant filter** | Audit query filters | Enforce filter at DB layer, not LLM |
| Exact IDs/codes not found | Embeddings poor at exact tokens | Test with BM25 | Add **hybrid (BM25)** search |
| Slow queries | k too high, no ANN tuning, big rerank | Latency per stage | Tune HNSW `ef`, reduce k, cache |
| Stale answers | Index not refreshed | Check ingest pipeline freshness | Automate re-indexing, TTLs |
| Injected instructions executed | **Indirect prompt injection** in docs | Inspect malicious chunk | Treat context as data, sanitize, guardrails |
| High cost | Too many/long chunks | Token accounting | Smaller chunks, fewer k, summarize, prompt cache |

### 9.3 Debugging toolkit

- **RAG**: log `(query, retrieved_ids, scores, reranked_ids, final_prompt, answer, citations)`; replay against the golden set; eval with RAGAS (faithfulness, context recall/precision); use Phoenix/TruLens for traces.
- **Fine-tuning**: TensorBoard/W&B loss curves (train vs val); print formatted samples to verify templating/masking; run held-out test + general benchmark; compare against base + RAG + prompt-only.

### 9.4 Real-world incident patterns

- **"We fine-tuned our docs into the model and it got worse."** Classic: tried to inject knowledge via fine-tuning; the model learned to assert facts confidently it didn't fully internalize → more hallucination, and it forgot some general skills. Remedy: revert; put docs in RAG; reserve fine-tuning for format/tone.
- **"RAG hallucinates."** Almost always **retrieval failure**, not generation. The right chunk wasn't in the top-k (no hybrid/rerank, bad chunking). Fix retrieval first; the LLM was working with the wrong page.
- **"Our per-customer fine-tunes don't scale."** A SaaS tried one fine-tune per tenant; ops/cost exploded and updates lagged. Remedy: one base model + RAG with tenant filters (and at most a few *style* LoRA adapters served via multi-LoRA).
- **"Costs blew up at scale."** Brute-force long-context for every query. Remedy: switch to RAG (top-k), and fine-tune to shorten the system prompt.

---

## 10. Interview drill

**Q1. What's the core difference between fine-tuning and RAG?**
*Model answer:* Fine-tuning changes the model's *weights* to alter *how* it behaves (style, format, skills); RAG leaves weights frozen and supplies *what* the model knows at query time by retrieving and injecting relevant context. Fine-tuning = closed-book studying; RAG = open-book lookup. They're complementary.
- *Probe: Which would you use to add today's news?* RAG — facts change; re-index, don't re-train.
- *Probe: Which lowers per-request latency/cost?* Fine-tuning (shorter prompts); RAG usually adds tokens/latency.
- *Probe: Can fine-tuning add facts at all?* Technically yes but unreliably and it can increase hallucination; prefer RAG for facts.

**Q2. Explain LoRA and why it's efficient.**
*Model answer:* LoRA freezes the base weights and learns a low-rank update `ΔW = BA` (ranks like 8–64) added to chosen matrices. Because the fine-tuning update is approximately low-rank, you train <1% of params, slashing optimizer memory and producing a tiny swappable adapter.
- *Probe: Role of `r` and `alpha`?* `r` = capacity (more params), `alpha/r` = scaling of the adapter's effect.
- *Probe: How does QLoRA differ?* 4-bit NF4 frozen base + double quant + paged optimizers → fits big models on one GPU.
- *Probe: Inference cost of LoRA?* Zero if merged (`W+=BA`); near-zero if served via multi-LoRA.

**Q3. Why is fine-tuning a bad way to inject a knowledge base?**
*Model answer:* Knowledge baked into weights is a frozen snapshot — stale the moment data changes — and re-training is slow/costly. Worse, fine-tuning on new facts can *increase* hallucination (the model learns to answer confidently without truly internalizing the fact) and offers no citations/auditability. RAG is fresh, citable, per-tenant, and needs no GPU training.
- *Probe: When is some fact-fine-tuning OK?* Stable domain vocabulary/fluency via continued pretraining, still paired with RAG for retrievable facts.
- *Probe: Multi-tenant facts?* Never per-tenant fine-tunes; RAG with tenant filters.

**Q4. What is catastrophic forgetting and how do you prevent it?** *(senior signal)*
*Model answer:* Fine-tuning on a narrow distribution overwrites broad pretrained abilities. Prevent with PEFT/LoRA (frozen base), low LR, few epochs, replay/rehearsal of general data, regularization (EWC/L2-SP), and *measuring* general-capability regression on a benchmark before/after — not just the task metric.
- *Probe: Why is LoRA less prone?* Base is frozen; the update is small and low-rank.
- *Probe: How do you detect it?* Run a general benchmark pre/post and watch for drops.

**Q5. Design a production assistant over a live internal knowledge base, in the company voice, returning strict JSON. What's your architecture?** *(senior signal)*
*Model answer:* RAG for fresh/private/citable facts (hybrid search + cross-encoder rerank, tenant filters at the DB), plus a fine-tuned model (LoRA) for the company voice, refusal policy, and JSON schema so the prompt stays short (latency/cost). Tight system prompt ties them together; golden-set eval in CI; faithfulness checks and guardrails at runtime. "Both, plus a good prompt."
- *Probe: Where do citations come from?* RAG context with source IDs; instruct the model to cite.
- *Probe: A fact changed — what do you do?* Re-index (RAG), never re-train.
- *Probe: Output JSON invalid sometimes?* Schema-validate + retry; the FT raises valid-rate.

**Q6. When would you choose long-context over RAG, and vice versa?** *(senior signal)*
*Model answer:* Long-context for a bounded blob needing whole-document reasoning with no retrieval infra; RAG for large/changing/private corpora, citations, and cost control. Long-context is O(n²) expensive and suffers lost-in-the-middle; RAG surfaces the right few chunks cheaply but is bounded by retrieval quality.
- *Probe: Lost in the middle?* LLMs recall edges better than the middle of long contexts; place key chunks at the edges.
- *Probe: Cost at scale?* Long-context per-query token cost dominates; RAG is cheaper at scale.

**Q7. How do you evaluate a fine-tuned model and a RAG system?**
*Model answer:* Fine-tuning: held-out task accuracy/F1, JSON-valid rate, plus a general benchmark to catch forgetting, compared against base/RAG/prompt-only. RAG: faithfulness/groundedness, answer relevance, context precision and recall (RAGAS). Maintain a golden set in CI; A/B in production.
- *Probe: LLM-as-judge pitfalls?* Bias/position effects; calibrate against humans.
- *Probe: Retrieval recall low — what fixes generation?* Nothing; fix retrieval first.

**Q8. Walk through a QLoRA training run end-to-end.**
*Model answer:* Prepare/clean `(prompt, completion)` data → apply chat template, mask prompt tokens → load base in 4-bit NF4 (`bitsandbytes`) → attach LoRA via `peft` → train 1–3 epochs with low LR, grad accumulation/checkpointing, validation early-stop → save adapter → eval vs baselines + forgetting check → merge or serve via multi-LoRA.
- *Probe: Why mask prompt tokens?* Don't train the model to generate the user's question; loss only on the completion.
- *Probe: Why grad checkpointing?* Trade compute to fit longer sequences in memory.

**Q9. RAG "hallucinates." How do you debug it?**
*Model answer:* Assume retrieval failure first. Log retrieved chunk IDs vs the known-correct chunk on a golden set: measure context recall. If the right chunk isn't retrieved, fix retrieval (hybrid BM25+vector, rerank, better chunking, maybe fine-tune embeddings). If it *is* retrieved but ignored, fix prompt/placement and add a strict "use only context / say I don't know" instruction.
- *Probe: Exact IDs not matching?* Embeddings weak on exact tokens → add BM25 hybrid.
- *Probe: Prevent fabrication structurally?* Faithfulness check + force citations + abstain when context insufficient.

**Q10. How would you cut inference cost/latency for a high-QPS LLM feature?**
*Model answer:* Fine-tune (or distill to a smaller model) to bake in instructions and shrink prompts; cap output tokens; use prompt caching for stable prefixes; for RAG, reduce k, smaller chunks, tune HNSW `ef`, cache hot retrievals and embeddings; serve on vLLM/TGI with good KV-cache utilization.
- *Probe: Distillation?* Train a small student on a big teacher's outputs.
- *Probe: KV cache role?* Main inference memory/concurrency limiter; size the server for it.

**Q11. Your model malforms tool/function calls intermittently. Fix it without breaking general ability.**
*Model answer:* Fine-tune (LoRA) on many examples of correct tool-call grammar with completion-only loss, mixing in some general data to avoid forgetting; validate on held-out calls and a general benchmark. Add a runtime schema validator + retry as a safety net.
- *Probe: Why LoRA not full FT?* Preserves general ability, cheap, swappable.
- *Probe: Still occasional errors?* Validate-and-retry guardrail; constrained decoding/grammar.

**Q12. Argue for "both" when a stakeholder says "just fine-tune it on our docs."** *(senior signal)*
*Model answer:* Fine-tuning docs makes knowledge stale, costly to update, un-citable, and can increase hallucination and forgetting. Put the *docs* in RAG (fresh, citable, per-tenant) and reserve fine-tuning for *behavior* (voice, format, skills) and *cost* (shorter prompts). The robust architecture is RAG + a fine-tuned model + a tight prompt; quantify it on a golden set against baselines.
- *Probe: One thing to do first?* Prompt engineering + RAG; only then fine-tune if behavior/cost still lacking.
- *Probe: Biggest hidden cost of fine-tuning facts?* Re-training treadmill + hallucination risk + audit gaps.

---

## 11. Glossary

- **Adapter** — small trainable weights (e.g. LoRA `A`/`B`) added to a frozen base to specialize it.
- **Adam / AdamW** — standard optimizer keeping two moment statistics per parameter; AdamW adds correct weight decay.
- **ANN (Approximate Nearest Neighbor)** — fast, slightly inexact vector similarity search (e.g. HNSW).
- **Attention / self-attention** — mechanism letting each token gather info from all others; ~O(n²) in length.
- **Autoregressive** — generating one token at a time, each conditioned on prior tokens.
- **Backpropagation** — chain-rule algorithm computing gradients of the loss w.r.t. trainable params.
- **BM25** — classic lexical/keyword ranking; strong on exact terms; used in hybrid search.
- **Catastrophic forgetting** — loss of general ability when fine-tuning on a narrow task.
- **Chat template** — the exact role/special-token format an instruct model expects.
- **Chunk / chunking / overlap** — splitting docs into retrievable units, with repeated boundary tokens.
- **Context window** — max tokens (prompt + output) the model can process at once.
- **Continued pretraining** — more unlabeled next-token training to gain domain fluency.
- **Cosine similarity / dot product** — vector closeness metrics for retrieval.
- **Cross-encoder reranker** — scores `(query, chunk)` jointly; slow but accurate reordering.
- **Cross-entropy loss** — measures prediction surprise; the LLM training objective.
- **Distillation** — train a small student on a large teacher's outputs.
- **DoRA / LoRA+ / rsLoRA** — LoRA variants improving quality/stability/convergence.
- **DPO** — direct preference optimization; RL-free training on chosen/rejected pairs.
- **Drift** — degradation as the real-world distribution diverges from training.
- **Embedding** — numeric vector capturing meaning; similar text → nearby vectors.
- **Epoch** — one full pass over the training data.
- **EWC** — elastic weight consolidation; penalizes changing important weights to fight forgetting.
- **F1** — harmonic mean of precision and recall.
- **Faithfulness/groundedness** — whether an answer is supported by retrieved context.
- **Few-shot / zero-shot** — including / not including examples in the prompt.
- **Fine-tuning** — continuing training on your data to change model behavior.
- **FSDP / ZeRO** — sharding params/grads/optimizer state across GPUs to fit big models.
- **GraphRAG** — RAG over a knowledge graph for multi-hop/global questions.
- **Gradient / gradient descent** — direction to nudge weights / the iterative training algorithm.
- **Gradient accumulation / checkpointing** — simulate big batches / recompute activations to save memory.
- **GPU** — parallel hardware doing the matrix math for training and inference.
- **HNSW** — layered-graph ANN index; tuned via `M`, `ef_construction`, `ef_search`.
- **HyDE** — embed a hypothetical answer to improve retrieval recall.
- **Hybrid search** — combine vector + keyword (BM25) retrieval.
- **Hyperparameter** — a pre-chosen training setting (LR, epochs, rank).
- **In-context learning** — learning a task from prompt examples with no weight change.
- **KV cache** — cached attention keys/values for generated tokens; main inference memory cost.
- **Learning rate / warmup** — step size for updates / ramping it up at the start.
- **LLM-as-judge** — using a strong LLM to grade outputs against a rubric.
- **LoRA** — low-rank adaptation; trains `ΔW=BA` on a frozen base.
- **Loss** — scalar error the optimizer minimizes.
- **Lost in the middle** — long-context recall is best at edges, worst in the middle.
- **MLP / feed-forward** — dense matrices in each transformer layer; store much knowledge.
- **Multi-hop** — a question needing facts chained across documents.
- **NF4 / quantization / double quantization** — 4-bit format / fewer-bit storage / quantizing the scales.
- **Optimizer state** — per-parameter bookkeeping (Adam moments); dominates training memory.
- **Overfitting** — memorizing train data; worse generalization.
- **PagedAttention** — vLLM's OS-paging-style KV-cache management.
- **Paged optimizers** — page optimizer state to CPU to avoid OOM (QLoRA).
- **Parameter / weight** — a learnable number in the network.
- **PEFT** — parameter-efficient fine-tuning (e.g. LoRA).
- **Pretraining** — initial large-scale next-token training; source of world knowledge.
- **Prompt engineering** — crafting instructions/examples to elicit behavior.
- **Prompt injection (indirect)** — attacker text (in docs/web) hijacking model instructions.
- **QLoRA** — LoRA on a 4-bit-quantized frozen base; fits big models on one GPU.
- **QPS** — queries per second.
- **RAG** — retrieval-augmented generation; retrieve then inject context at query time.
- **RAGAS** — RAG eval metrics (faithfulness, context precision/recall, answer relevance).
- **Rank (`r`)** — true dimensionality of a matrix / LoRA's capacity dial.
- **Reranking** — reordering retrieved candidates for precision (cross-encoder).
- **RRF** — reciprocal rank fusion; robustly merge ranked lists.
- **RLHF** — reinforcement learning from human feedback for alignment.
- **SFT** — supervised fine-tuning on `(instruction, response)` pairs.
- **TGI / vLLM** — production LLM serving engines with OpenAI-compatible APIs and LoRA support.
- **Token** — atomic text unit (~¾ word); the unit of context and billing.
- **Transformer** — the LLM architecture built on attention + MLP layers.
- **Vector database** — store optimized for nearest-neighbor vector search.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **Fine-tuning changes *how* (behavior); RAG changes *what* (knowledge). They're complementary.**
- **Order of levers:** prompt → RAG (for knowledge) → fine-tune (for behavior/cost) → combine.
- **Fine-tune for:** style, format, narrow skill, tool grammar, latency/cost (shorter prompts, distillation).
- **Don't fine-tune for:** fresh/changing facts, per-tenant knowledge, citations → use **RAG**.
- **Fine-tuning facts can *increase* hallucination + cause **catastrophic forgetting**.** Prefer RAG; if you must FT, use LoRA, low LR, few epochs, replay data, and measure general regression.
- **PEFT:** LoRA learns `ΔW=BA` (rank 8–64, frozen base, tiny adapter). **QLoRA** = 4-bit NF4 base + double-quant + paged optimizers → big models on one GPU. Merge for zero-latency or multi-LoRA to swap.
- **Train memory:** full FT ≈ 16 B/param (Adam); LoRA/QLoRA far less. Use grad accumulation/checkpointing.
- **RAG pipeline:** ingest (chunk→embed→index) ; query (embed→ANN top-k + BM25 hybrid→rerank→prompt→generate→cite). **Quality is bounded by retrieval.**
- **RAG quality knobs:** chunking, hybrid search, cross-encoder rerank, HNSW `ef`, query rewriting/HyDE, contextual retrieval, fine-tune the embedding/reranker.
- **Long-context:** O(n²), lost-in-the-middle, pricey; good for whole-doc reasoning, weak as a "find one buried fact" tool.
- **Evaluate against baselines** (base/RAG/prompt-only) on a golden set in CI; FT: task F1 + JSON-valid + forgetting check; RAG: faithfulness + context recall/precision.
- **Security:** tenant filters at the DB (not the LLM); sanitize retrieved docs (indirect prompt injection); don't FT on secrets (memorization leakage).
- **The production default for serious products:** **RAG + a fine-tuned model + a tight prompt.**

### Self-test (no answers — recall actively)

1. A stakeholder says "fine-tune the model on our constantly-updated product catalog." What's wrong with that, and what do you propose instead — and why?
2. Explain LoRA's `ΔW = BA` precisely: what `r` and `alpha` control, how big the artifact is, and what QLoRA adds on top.
3. Your RAG bot "hallucinates." Outline your debugging procedure end-to-end and the single thing you check first.
4. Design the full architecture for a branded, citation-required, multi-tenant assistant over a live knowledge base. Specify every lever and where security is enforced.
5. Define catastrophic forgetting, give the mechanism, list four mitigations, and say how you would *detect* it before shipping.
6. Compare RAG vs long-context for (a) summarizing one 300-page contract and (b) answering questions over 50k changing documents. Justify each choice with cost and accuracy reasoning.
7. You must cut a high-QPS feature's inference cost in half. List the fine-tuning-side and the RAG-side levers you'd pull and the tradeoffs of each.

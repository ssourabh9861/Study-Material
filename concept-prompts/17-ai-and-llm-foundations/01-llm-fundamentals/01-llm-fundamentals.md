# LLM Fundamentals

> An engineering handbook chapter for senior backend developers (Java/JVM focus) who want to fully master Large Language Models — from first principles to deep internals — well enough to design with them, operate and debug them in production, teach them, and answer any interview question on them.

---

## 1. Overview & where it fits

### 1.1 What an LLM is, in one sentence

A **Large Language Model (LLM)** is a very large neural network trained to predict the **next token** in a sequence of text, given all the tokens that came before it. Everything else — chat, code generation, summarization, "reasoning" — is an emergent consequence of doing that one job extremely well over a model with hundreds of billions of parameters trained on trillions of tokens.

If you remember nothing else, remember this: **an LLM is a conditional probability machine.** Given an input sequence of tokens, it outputs a probability distribution over the entire vocabulary for "what token comes next." A program (the *decoder*, also called the *sampling loop*) repeatedly picks a token from that distribution, appends it to the sequence, and asks the model again. That loop, run thousands of times, produces a paragraph.

- **Token:** a chunk of text — roughly a word-piece. "tokenization" splits text into the model's atomic units (more in §2). One token is ~0.75 English words on average, or ~4 characters.
- **Parameter:** a single learned floating-point number (a "weight") inside the network. A "70B model" has 70 billion of them. They are fixed at inference time — they do not change as you chat.
- **Inference:** running the trained model to produce output. Distinct from **training**, where the parameters are learned.

### 1.2 The problem it solves

Before LLMs, almost every natural-language task needed a *bespoke* system: a sentiment classifier, a named-entity extractor, a machine-translation pipeline, a question-answering index, each separately built, trained, and maintained. LLMs collapse a huge swath of these into **one general-purpose model you steer with text instructions** (a *prompt*). You no longer train a model per task; you *describe* the task in natural language and optionally show a few examples.

The engineering value proposition:

- **Generality.** One API endpoint handles classification, extraction, rewriting, translation, code, drafting, and Q&A.
- **Zero/few-shot capability.** Many tasks need no fine-tuning at all — just a good prompt.
- **Natural-language interface.** Non-ML engineers can build sophisticated language features by writing prompts and wiring an HTTP call.

The cost: LLMs are **probabilistic, stateless, non-deterministic by default, factually unreliable (hallucination), latency- and cost-heavy, and bounded by a context window.** Most of this chapter is about understanding and engineering around those properties.

### 1.3 When you reach for an LLM (and when you do not)

**Reach for an LLM when:**
- The task involves understanding or generating unstructured natural language or code.
- The input space is open-ended and you cannot enumerate rules (e.g., "summarize this support ticket," "extract the shipping address from this email").
- An approximate, fluent answer with human review is acceptable, or the failure cost is low.
- You need to ship a language feature fast without standing up an ML training pipeline.

**Do NOT reach for an LLM when:**
- You need a **deterministic, auditable, exact** answer (tax calculations, balance transfers, primary-key lookups). Use code/SQL.
- The task is a simple, high-volume, well-defined classification you can do with a cheap rule or a small fine-tuned classifier at 1/1000th the cost.
- Correctness is safety-critical and unverifiable (e.g., raw medical dosing with no human in the loop).
- You need sub-10ms latency per call. LLM calls are hundreds of ms to many seconds.

### 1.4 The one-paragraph mental model (memorize this)

> An LLM is a **pure function** `f(token_sequence) -> probability_distribution_over_next_token`. It is **stateless**: it remembers nothing between calls; the only "memory" is the text you resend each time (the *context*). You repeatedly call it, sample a token, append it, and call again — this loop is **decoding**. The model has a fixed **context window** (max tokens it can attend to) and a fixed **knowledge cutoff** (the date its training data ends). It does not browse the web, run code, or check facts unless *you* wire those tools in. Its output is *plausible-sounding text*, optimized for fluency, not truth — so it will confidently produce false statements (*hallucinations*). You control its behavior with the **prompt** (what you say) and **decoding parameters** (temperature, top-p, etc., which control how randomly it samples).

### 1.5 Where it fits in a system

A typical LLM-backed backend service looks like:

```
[User] -> [Your API / orchestration layer (Java)] -> [Prompt assembly]
        -> [LLM provider HTTP API or self-hosted inference server]
        -> [Post-processing / validation / parsing]
        -> [Tools / DB / RAG retrieval as needed] -> [Response]
```

The LLM is one node — a stateless, networked, probabilistic dependency. You treat it much like a flaky third-party API: with timeouts, retries, circuit breakers, caching, fallbacks, and rigorous input/output validation. The bulk of "LLM engineering" is the orchestration *around* the model, not the model itself.

---

## 2. Foundations from first principles

We build the entire stack from zero. Each new term is defined the moment it appears.

### 2.1 Text → tokens → numbers

Computers cannot operate on text directly; neural networks operate on numbers. The pipeline:

1. **Tokenization.** Raw text is split into **tokens** by a **tokenizer**. Modern LLMs use **subword tokenization**, most commonly **Byte-Pair Encoding (BPE)** or its variants (e.g., OpenAI's `tiktoken`, SentencePiece used by Llama/Mistral). BPE starts from individual bytes/characters and greedily merges the most frequent adjacent pairs into a fixed **vocabulary** (often 32k–256k tokens). Common words become a single token; rare words split into several.
   - *Why subword?* A pure word vocabulary cannot represent unseen words; a pure character vocabulary makes sequences enormously long. Subword is the compromise: frequent words are one token, novel words decompose into known pieces.
   - *Concrete:* `"tokenization"` might be `["token", "ization"]`. `"flibbertigibbet"` might split into many pieces. Numbers, whitespace, and punctuation each cost tokens. Non-English text typically costs *more* tokens per word (often 1.5–3×), which matters for cost.
2. **Token IDs.** Each token maps to an integer ID (its index in the vocabulary). `"Hello"` → `15339` (example). The text is now a list of integers.
3. **Embedding.** Each token ID is looked up in an **embedding matrix** — a table with one learned vector (e.g., 4096 floats) per vocabulary entry. So a sequence of N tokens becomes an N × d matrix of floats (d = the model's *hidden dimension*). This is the model's actual input.
   - **Embedding:** a dense vector of real numbers representing a token's meaning in a high-dimensional space, where semantically similar tokens sit near each other.

> **Engineer's takeaway:** every billing, latency, and context-window limit is measured in **tokens, not characters or words.** Always think in tokens. Use the provider's tokenizer (e.g., `tiktoken` for OpenAI, `transformers` tokenizer for HF models) to count exactly; never estimate billing off character counts in production.

### 2.2 What "next-token prediction" means concretely

The model's entire learned skill is: *given tokens t₁…tₙ, output a probability for every possible token tₙ₊₁.*

- The output is a vector of size = vocabulary (e.g., 100,000 numbers), called the **logits** — raw, unnormalized scores.
- **Logits** are converted to probabilities by the **softmax** function, which exponentiates each logit and normalizes so they sum to 1. Result: a probability distribution over the whole vocabulary.
  - **Softmax:** `p_i = exp(logit_i) / Σ exp(logit_j)`. It turns arbitrary real scores into a valid probability distribution; larger logits get exponentially larger probabilities.
- A **decoding strategy** then selects an actual token from this distribution (§2.6, §3.4).

Training optimizes the parameters so that, across trillions of tokens of real text, the model assigns high probability to the token that actually came next. The objective is **cross-entropy loss** (a.k.a. **negative log-likelihood**): penalize the model in proportion to `-log(probability it assigned to the true next token)`. Minimizing it = "be less surprised by real text."
- **Cross-entropy / loss:** a number measuring prediction error; lower is better. **Perplexity** = `exp(loss)`; it is the loss expressed as an "effective branching factor" — roughly, how many tokens the model is, on average, choosing between. Lower perplexity = a better language model.

That is the whole game. Chat, reasoning, and code are all "what text plausibly comes next," learned implicitly because the training data contained conversations, reasoning, and code.

### 2.3 The neural network: the Transformer (working-level, no math overload)

Nearly all modern LLMs use the **Transformer** architecture (Vaswani et al., 2017, *"Attention Is All You Need"*). You do not need the matrix calculus, but you must understand the moving parts conceptually.

The Transformer processes the input token-embedding matrix through a stack of identical **layers** (often 32–120 of them). Each layer has two main sub-blocks:

1. **Self-attention** — lets each token "look at" other tokens and pull in relevant information.
2. **Feed-forward network (FFN / MLP)** — a per-token nonlinear transformation that does most of the "computation" and stores much of the model's factual knowledge.

Plus supporting machinery: **residual connections** (each sub-block adds its output back to its input, so signal/gradients flow through deep stacks), and **layer normalization** (rescales activations to keep them numerically stable).
- **Residual connection (skip connection):** `output = input + sublayer(input)`. It lets very deep networks train without the signal vanishing.
- **Layer normalization:** normalizes a vector to zero mean / unit variance (then rescales with learned parameters) to stabilize training and inference.

#### 2.3.1 Attention, explained at a working level

Attention answers: *"For the token I'm currently producing, which earlier tokens matter, and how much?"*

For every token, the model computes three vectors via learned projections:
- **Query (Q):** "what am I looking for?"
- **Key (K):** "what do I offer / what am I about?"
- **Value (V):** "the actual information I'll contribute if attended to."

Mechanically: each token's **Query** is compared (dot product) against **every** token's **Key** to produce a **score** of relevance. Scores are softmaxed into **attention weights** (sum to 1). The output for a token is the weighted sum of all **Values**, weighted by those attention weights. Intuition: a token *attends* most strongly to the earlier tokens most relevant to it, and copies a blend of their information into itself.

- Example: in "The trophy didn't fit in the suitcase because **it** was too big," attention lets "it" attend to "trophy" rather than "suitcase," resolving the reference.

**Multi-head attention:** the model does this several times in parallel with different learned Q/K/V projections (the "heads," e.g., 32–128 of them). Each head can specialize — one tracks syntax, another long-range dependencies, another coreference. Their outputs are concatenated. This is why it's "multi-head."

**Causal (masked) attention:** in a generative LLM, a token may only attend to tokens *at or before* its position — never to future tokens. This **causal mask** is what makes the model a left-to-right predictor and lets it generate text one token at a time. (Encoder models like BERT use *bidirectional* attention and are not generative in the same way.)

**Positional information.** Attention as described is order-agnostic ("permutation invariant") — it sees a *set* of tokens. To know word order, the model injects **positional encodings**. Early Transformers used fixed sinusoidal encodings; modern LLMs (Llama, Mistral, GPT-NeoX-style) use **Rotary Position Embeddings (RoPE)**, which rotate Q/K vectors by an angle proportional to position, encoding *relative* position and enabling some context-length extrapolation.
- **RoPE:** a way of encoding token position by rotating attention vectors; it generalizes better to longer sequences and is the de facto standard in open models.

#### 2.3.2 The feed-forward / MLP block

After attention mixes information across tokens, each token's vector is independently pushed through a **feed-forward network** — typically two linear layers with a nonlinearity (ReLU/GeLU/SwiGLU) in between, expanding to ~4× the hidden dimension and back. This is where a large fraction of parameters and "knowledge" live. Research suggests FFN layers behave like a giant **key-value memory** of learned facts and patterns.
- **Nonlinearity / activation function:** a function (ReLU, GeLU, SwiGLU) applied element-wise that lets the network model non-linear relationships; without it, stacked linear layers collapse to one linear layer.

#### 2.3.3 The full forward pass (conceptually)

```
tokens
  -> embedding lookup (N x d matrix)
  -> [ + positional info ]
  -> Layer 1: attention -> add&norm -> FFN -> add&norm
  -> Layer 2: ...
  -> ... (32-120 layers)
  -> Layer L
  -> final layer norm
  -> "unembedding" / output projection -> logits (size = vocab)
  -> softmax -> probability of next token
```

The deeper layers build progressively more abstract representations; the final projection (often the transposed embedding matrix, "weight tying") maps back to vocabulary logits.

### 2.4 How models are trained: the three (or more) stages

A production chat LLM is not trained once; it goes through stages. Understanding them explains *why models behave as they do*.

#### Stage 1 — Pretraining (the expensive part)

- **What:** train the raw Transformer on a massive, mostly-unfiltered corpus (web pages, books, code, Wikipedia, etc. — often trillions of tokens) with the single objective of next-token prediction.
- **Result:** a **base model** (a.k.a. *foundation model*). It is a brilliant autocomplete: it knows grammar, facts, code, and world structure, but it does *not* reliably follow instructions. Ask a base model "What is the capital of France?" and it might continue with more quiz questions instead of answering — because that's a plausible continuation of text that looks like a quiz.
- **Cost/scale:** this is where the millions of dollars and thousands of GPUs go. **Compute scales roughly with parameters × tokens.** The **Chinchilla** finding (DeepMind, 2022) showed many models were undertrained: for a fixed compute budget, you want roughly **~20 tokens of training data per parameter** for compute-optimal training. (Inference-cost considerations now push teams to train *smaller* models on *far more* tokens than Chinchilla-optimal, because a smaller model is cheaper to serve forever.)
- **Knowledge cutoff** is set here: the model only "knows" what was in its pretraining (plus later) data. Events after the cutoff are unknown unless supplied at inference.

#### Stage 2 — Instruction tuning (a.k.a. Supervised Fine-Tuning, SFT)

- **What:** continue training the base model on a curated dataset of **(instruction, ideal response)** pairs, often tens of thousands to millions of examples, frequently human-written or carefully filtered.
- **Result:** an **instruct/chat model** that actually answers questions, follows formats, and adopts a helpful assistant persona. This is the step that converts "autocomplete" into "assistant."
- It also teaches the **chat format / special tokens** (system/user/assistant role delimiters — see §6).

#### Stage 3 — Preference tuning / alignment (RLHF and friends)

- **RLHF (Reinforcement Learning from Human Feedback):** the model generates multiple candidate answers; humans (or a model trained on human preferences) **rank** them; a **reward model** is trained to predict those preferences; then the LLM is optimized (classically with **PPO**, Proximal Policy Optimization) to produce outputs the reward model scores highly, while a penalty keeps it from drifting too far from the SFT model.
  - **Reward model:** a separate model that, given a prompt and a response, outputs a scalar "how good is this" score learned from human rankings.
  - **PPO:** a reinforcement-learning algorithm that updates a policy in small, clipped steps to keep training stable.
- **What it achieves:** makes outputs more helpful, harmless, and honest *as judged by humans* — better formatting, refusals of unsafe requests, less rambling, preferred tone.
- **Newer/cheaper alternatives:** **DPO (Direct Preference Optimization)** skips the separate reward model and RL loop, optimizing directly on preference pairs with a simple classification-style loss; **RLAIF** uses AI feedback instead of human; **Constitutional AI** (Anthropic) has the model critique/revise its own outputs against a set of written principles ("a constitution").

> **Why this matters to you:** the *same base model* can produce a terse, safety-tuned chat assistant or a verbose completion engine depending on stages 2–3. "Alignment tax" is real: heavily aligned models sometimes refuse benign requests or hedge. The behavioral quirks you fight in prompts (over-apologizing, refusing, verbose preambles) are artifacts of these stages.

#### Stage 4 (newer) — Reasoning / RL on verifiable tasks

The latest "reasoning models" (OpenAI o-series, DeepSeek-R1, Claude with extended thinking, Gemini "thinking") add a stage of **reinforcement learning on tasks with checkable answers** (math, code, logic), training the model to produce long internal **chain-of-thought** before answering, and to spend more **inference-time compute** ("think longer") on hard problems. See §7.4.

### 2.5 Base vs Instruct vs Reasoning — behavioral cheat sheet

| Model type | Trained how | Behaves like | Use for |
|---|---|---|---|
| **Base / foundation** | Pretraining only | Raw autocomplete; doesn't follow instructions | Fine-tuning starting point; raw text completion; research |
| **Instruct / Chat** | + SFT (+ RLHF/DPO) | Helpful assistant; follows instructions & chat format | 95% of product use cases |
| **Reasoning** | + RL on verifiable tasks; long CoT | "Thinks" before answering; strong at math/code/logic; slower, pricier | Hard reasoning, planning, agentic, math/coding |

### 2.6 Decoding: turning probabilities into text (first principles)

After the forward pass gives a probability distribution over the next token, **decoding** chooses one. The strategy hugely affects output quality and determinism.

- **Greedy decoding:** always pick the single highest-probability token. Deterministic, but produces repetitive, bland, often degenerate text ("the the the").
- **Sampling:** draw a token randomly according to the probability distribution. Introduces variety and creativity but also errors.
- **Temperature (T):** divides the logits before softmax. `T < 1` sharpens the distribution (more confident, more deterministic); `T > 1` flattens it (more random, more "creative"); `T = 0` ≈ greedy/argmax (most providers special-case it). Default is often `1.0`.
- **Top-k sampling:** restrict sampling to only the *k* highest-probability tokens, renormalize, then sample. Cuts off the long tail of nonsense.
- **Top-p / nucleus sampling:** restrict to the smallest set of tokens whose cumulative probability ≥ *p* (e.g., 0.9), then sample. Adaptive: a confident step keeps few tokens, an uncertain step keeps many. Generally preferred over top-k.

We cover the full parameter set with numbers and defaults in §4. The key first-principles point: **the model is the same; decoding parameters change how you sample from it.** "Make it deterministic" and "make it creative" are decoding decisions, not different models.

### 2.7 The autoregressive generation loop

Generation is **autoregressive** — each new token is fed back in to predict the next.

```
context = encode(prompt)                  # list of token IDs
while True:
    logits   = model.forward(context)     # full forward pass, O(context^2) attention
    logits   = logits[-1]                  # only the LAST position predicts the next token
    probs    = softmax(logits / temperature)
    probs    = apply_top_p_top_k(probs)
    next_tok = sample(probs)               # or argmax if greedy
    if next_tok == EOS or len == max_tokens or next_tok in stop_sequences:
        break
    context.append(next_tok)               # autoregression: feed output back as input
emit(decode(generated_tokens))
```

Two costs fall out of this immediately:
- **Prefill (prompt processing):** the first forward pass over the whole prompt. Done once, highly parallel, compute-bound. Determines **time-to-first-token (TTFT)**.
- **Decode (generation):** one forward pass *per output token*, sequential, memory-bandwidth-bound. Determines **tokens-per-second** throughput and total latency. This is why **output length dominates latency**, and why naïvely it's `O(context²)` per step — mitigated by the **KV cache** (§3.3, §7.1).

---

## 3. How it works internally (the heart of the document)

This section traces the full lifecycle from your HTTP request to streamed tokens, going deep on the inference engine internals.

### 3.1 End-to-end request lifecycle (provider API view)

Step by step, what happens when your Java service calls `POST /v1/chat/completions`:

1. **Request assembly (your side).** You build a JSON body: the `messages` array (system/user/assistant turns), `model`, and decoding params (`temperature`, `top_p`, `max_tokens`, `stop`, etc.). You include **the entire conversation history** every time, because the API is **stateless** (§3.6).
2. **Transport.** HTTPS POST to the provider. TLS handshake (reused via keep-alive/HTTP-2 connection pools in production), auth via bearer token / API key, routing to a regional endpoint.
3. **Gateway & rate limiting.** The provider authenticates, checks your **rate limits** (RPM = requests/min, TPM = tokens/min), and may enqueue the request. On overflow you get `429 Too Many Requests`.
4. **Tokenization.** The server tokenizes your messages (after rendering the **chat template** that inserts role delimiters / special tokens). It validates that `input_tokens + max_tokens ≤ context_window`; otherwise it errors (e.g., "context length exceeded").
5. **Scheduling / batching.** The inference server places your request into a **batch** with other users' requests (**continuous batching**, §7.2) so the GPU stays saturated. You are time-sharing GPUs with everyone else; this is invisible to you but explains latency variance.
6. **Prefill.** The model runs one big forward pass over all prompt tokens, populating the **KV cache** (§3.3). Time here ≈ TTFT and scales with prompt length.
7. **Decode loop.** The server generates tokens one at a time (§2.7), each step appending to the KV cache. If you requested **streaming**, each token (or small chunk) is pushed to you as a **Server-Sent Event (SSE)** as it's produced.
   - **SSE (Server-Sent Events):** a one-way HTTP streaming protocol where the server sends a sequence of `data:` events over a long-lived response; how LLM token streaming is delivered.
8. **Stop condition.** Generation halts on an **end-of-sequence (EOS)** token, a user **stop sequence**, or `max_tokens`. The reason is reported as `finish_reason` (`stop`, `length`, `content_filter`, `tool_calls`, etc.).
9. **Post-processing & response.** The server assembles the final message, runs safety/content filters, computes **usage** (prompt/completion/total tokens for billing), and returns JSON (or closes the SSE stream with a terminal event).
10. **Your side.** You parse, validate, possibly re-prompt or repair, and use the result.

### 3.2 What "stateless" really means (and the context illusion)

The model holds **no memory** of prior requests. The *appearance* of memory in a chatbot comes entirely from **you resending the conversation** in the `messages` array. The server may *cache* computation (prompt caching, §7.1) for efficiency, but semantically each call is a pure function of the tokens you send. Consequences:

- Conversation state is *your* responsibility (store it; trim it; summarize it when it grows).
- Every turn re-pays for the *entire* history in input tokens (cost grows with conversation length).
- "The model forgot what I said earlier" almost always means *you* truncated or omitted the history, or it fell out of the context window.

### 3.3 The KV cache — the single most important inference internal

Naïvely, generating token N requires attention over all N−1 prior tokens, and you'd recompute everything each step — `O(N²)` work to produce N tokens, plus recomputing identical Keys/Values repeatedly.

The **KV cache** fixes this. Recall attention needs each prior token's **Key** and **Value** vectors. These never change once computed (causal models only look backward). So the server **caches the K and V tensors for every token, in every layer, in every head.** Each new step:
- Computes Q/K/V only for the *one* new token.
- Reuses cached K/V for all prior tokens.
- Appends the new token's K/V to the cache.

This turns per-step cost from `O(N²)` to `O(N)` and is why generation is feasible. But the KV cache is **huge** and is the dominant memory consumer at inference:

```
KV cache bytes ≈ 2 (K and V) × num_layers × num_kv_heads × head_dim
                 × sequence_length × batch_size × bytes_per_element
```

For a 70B-class model this can be **hundreds of KB to MB per token**, so a long context × many concurrent users can blow past GPU memory faster than the weights themselves. This drives several advanced techniques:
- **Multi-Query Attention (MQA)** / **Grouped-Query Attention (GQA):** share Keys/Values across many or groups of attention heads, shrinking the KV cache (and bandwidth) dramatically with minimal quality loss. GQA is now standard (Llama 2/3, Mistral).
- **PagedAttention (vLLM):** manage the KV cache like OS virtual memory — non-contiguous "pages" — eliminating fragmentation and enabling much higher concurrency. (§7.2)

> **Mental model:** weights are fixed and shared across all requests; the **KV cache is per-request, grows with context length, and is what actually limits how many users and how long a context you can serve.**

### 3.4 The decoding loop internals (with sampling order)

Per generated token, in order:
1. Forward pass → **logits** for the last position (a vector of size `vocab`).
2. Apply **logit processors** in a defined order (provider-specific, but typically):
   - **Repetition / frequency / presence penalties** subtract from logits of already-seen tokens to reduce loops.
   - **Logit bias** (user-supplied) nudges specific tokens up/down (e.g., ban a token by `-100`).
   - **Temperature** scales logits (`logits / T`).
3. Convert to probabilities via **softmax**.
4. **Truncate** the distribution: **top-k** then **top-p (nucleus)** (or min-p) filtering; renormalize.
5. **Sample** one token (or `argmax` if `T=0`/greedy). Optionally **beam search** keeps multiple hypotheses (rare in chat APIs; common in translation).
6. Check **stop conditions** (EOS, stop sequences, max tokens, structured-output grammar completion).
7. Append token's K/V to the cache; emit token (if streaming).

Knowing this order explains interactions: temperature and top-p compose; setting `T=0` makes top-p/top-k irrelevant; penalties act *before* temperature, etc. (Exact order is engine-specific; vLLM and Hugging Face `transformers` document theirs.)

### 3.5 Constrained / structured decoding (how "JSON mode" works)

To force valid JSON or a schema, the engine constrains step 4/5: at each token, it **masks out** any token that would make the output invalid per a **grammar** (often a regex or context-free grammar / JSON schema compiled to a finite-state machine). The model still chooses among *allowed* tokens by probability, so you get fluent *and* structurally valid output.
- **Tools:** `outlines`, `guidance`, `lm-format-enforcer`, vLLM/TGI "guided decoding," OpenAI **Structured Outputs** (`response_format: json_schema` with `strict: true`), Anthropic tool-use schemas. This is far more reliable than "please respond in JSON" prompting because invalid tokens are *physically impossible*.

### 3.6 State machine of a chat session (from your orchestration's view)

```
[New session]
   -> build messages = [system]
[User turn]
   -> append {role: user, content}
   -> (optional) retrieve context (RAG), inject as system/user message
   -> trim/summarize if tokens near window limit   <-- YOUR job
   -> call LLM (stateless)
   -> stream/collect assistant tokens
[Assistant turn]
   -> append {role: assistant, content}
   -> (if tool_call) execute tool -> append tool result -> call LLM again
   -> persist session state (DB/cache)
[Loop to User turn]
```

The model contributes only the "call LLM" box. Everything else — history management, retrieval, trimming, tool execution, persistence — is your code. **This is the core insight for backend engineers: an LLM app is mostly an orchestration and state-management problem around a stateless probabilistic function.**

### 3.7 Data flow summary

- **Control flow:** orchestrator → prompt builder → provider gateway → scheduler → prefill → decode loop → filters → response → orchestrator.
- **Data flow:** text → tokens → embeddings → L Transformer layers (attention reads/writes KV cache) → logits → sampler → token → (back into context) → text.
- **State:** model weights (immutable, shared) + KV cache (per request, ephemeral) + conversation history (yours, persisted).

---

## 4. The complete toolkit

This section enumerates the knobs, APIs, and units you actually use. Numbers are real but **flag** version/vendor specificity where it exists.

### 4.1 Decoding / sampling parameters

| Parameter | What it does | Typical range | Common default | Notes |
|---|---|---|---|---|
| `temperature` | Scales logits before softmax; higher = more random | `0.0`–`2.0` | `1.0` (OpenAI), `1.0` (Anthropic) | `0` ≈ greedy/most-deterministic. Use low (0–0.3) for extraction/classification, high (0.7–1.0) for creative. |
| `top_p` (nucleus) | Sample from smallest token set with cumulative prob ≥ p | `0.0`–`1.0` | `1.0` | Prefer tuning **either** temperature **or** top_p, not both aggressively. |
| `top_k` | Sample only from top-k tokens | `1`–`100+` | varies / disabled on some APIs | Common in open-source serving (vLLM, TGI), Gemini, not exposed by OpenAI chat API. |
| `min_p` | Keep tokens with prob ≥ `min_p × max_prob` | `0.0`–`0.2` | n/a | Newer; in vLLM/llama.cpp; robust alt to top-p. |
| `max_tokens` / `max_completion_tokens` | Hard cap on **output** tokens | task-dependent | model-dependent | Caps cost/latency. Does **not** include input tokens. For reasoning models, includes hidden "thinking" tokens. |
| `stop` / `stop_sequences` | Strings that halt generation when produced | up to ~4 sequences | none | The stop string itself is typically not included in output. |
| `frequency_penalty` | Penalize tokens by how often they've appeared | `-2.0`–`2.0` | `0` | Reduces verbatim repetition. (OpenAI) |
| `presence_penalty` | Penalize tokens that appeared at all (encourages new topics) | `-2.0`–`2.0` | `0` | (OpenAI) |
| `repetition_penalty` | Multiplicative penalty on seen tokens | `1.0`–`1.3` | `1.0` | (HF/vLLM/llama.cpp; different formulation than freq/presence.) |
| `logit_bias` | Per-token additive bias to logits | `-100`–`+100` | `{}` | `-100` effectively bans a token; `+100` forces it. Keyed by token ID. |
| `seed` | Pseudo-random seed for reproducibility | int | none | **Best-effort** determinism only (see §4.5). |
| `n` | Number of completions to generate | `1`+ | `1` | Costs n× output tokens. |
| `response_format` | Force text / JSON / JSON-schema | — | text | `json_schema` + `strict:true` for guaranteed-valid JSON (OpenAI). |
| `stream` | Stream tokens via SSE | bool | `false` | Lowers perceived latency (TTFT). |
| `reasoning_effort` / `thinking` budget | Controls reasoning-token spend | low/med/high or token budget | model-dependent | Reasoning models only (o-series, Claude extended thinking, Gemini thinking). |

> **Vendor flags:** OpenAI Chat Completions exposes `temperature`, `top_p`, `frequency_penalty`, `presence_penalty`, `max_completion_tokens`, `stop`, `logit_bias`, `seed`, `n`, `response_format`. It does **not** expose `top_k`. Anthropic exposes `temperature`, `top_p`, `top_k`, `max_tokens`, `stop_sequences`. Open-source servers (vLLM, TGI, llama.cpp) expose the union, including `repetition_penalty`, `min_p`, `mirostat`, etc.

### 4.2 The Chat Messages API mental model

The dominant API shape is **chat completions**: a list of role-tagged messages.

| Role | Purpose |
|---|---|
| `system` (or `developer` in newer OpenAI) | High-priority instructions: persona, rules, format, constraints. Sets behavior for the whole conversation. |
| `user` | The human's input/turn. |
| `assistant` | The model's prior responses (you replay them to give context). |
| `tool` / `function` | Results returned from a tool/function the model asked to call. |

A request = `{model, messages: [...], ...decoding params}`. The response = an `assistant` message + `usage` (token counts) + `finish_reason`. **You own the messages array.**

### 4.3 Core API objects / fields (provider-agnostic)

| Field | Meaning |
|---|---|
| `model` | Which model/version to use (e.g., `gpt-4o-2024-08-06`). Pin versions in prod. |
| `messages` | Conversation history (your responsibility to assemble). |
| `usage.prompt_tokens` / `input_tokens` | Billed input tokens. |
| `usage.completion_tokens` / `output_tokens` | Billed output tokens (output usually 2–5× pricier than input). |
| `usage.cached_tokens` | Tokens served from prompt cache (cheaper). |
| `finish_reason` / `stop_reason` | Why generation stopped: `stop`, `length`, `tool_calls`, `content_filter`. |
| `choices[]` | The completion(s); length = `n`. |
| `tool_calls` / `function_call` | Structured request from the model to invoke a tool. |
| `id`, `created`, `system_fingerprint` | Metadata; `system_fingerprint` helps detect backend changes affecting determinism. |

### 4.4 Tooling ecosystem (what you'll actually use)

| Category | Tools | Purpose |
|---|---|---|
| **Tokenizers** | `tiktoken` (OpenAI), HF `transformers` tokenizers, SentencePiece | Exact token counting for cost/limits. |
| **Provider SDKs** | OpenAI Java SDK, Anthropic SDK; or raw HTTP | API calls. |
| **JVM frameworks** | **LangChain4j**, **Spring AI** | Idiomatic Java abstractions: chat models, memory, RAG, tools. |
| **Inference servers (self-host)** | **vLLM**, **TGI** (Text Generation Inference), **llama.cpp**/`llama-server`, **Ollama**, **SGLang**, **TensorRT-LLM** | Serve open-weight models with batching, KV cache, OpenAI-compatible APIs. |
| **Orchestration** | LangChain, LlamaIndex, Spring AI, custom | Prompt templates, chains, agents, RAG. |
| **Structured output** | OpenAI Structured Outputs, `outlines`, `guidance`, `lm-format-enforcer` | Force valid JSON/schema. |
| **Observability** | LangSmith, Langfuse, Helicone, OpenTelemetry GenAI semantic conventions | Trace prompts, tokens, latency, cost. |
| **Eval** | `promptfoo`, OpenAI Evals, Ragas (RAG), custom golden sets | Regression-test prompts/models. |
| **Gateways** | LiteLLM, OpenRouter, Portkey | One API across providers; fallback/routing/caching. |

### 4.5 Determinism controls (and why they're best-effort)

To make outputs as repeatable as possible:
- `temperature = 0` (greedy/argmax).
- Set `seed` and pin `model` version; check `system_fingerprint` for backend changes.

Even then, **bit-exact determinism is not guaranteed** on hosted APIs because: floating-point reductions are **non-associative** and depend on GPU **batch composition** (you share batches with other users), kernel/library versions, hardware, and **Mixture-of-Experts routing** under load. Treat determinism as "low-variance," not "guaranteed identical." (Self-hosting with fixed hardware, `T=0`, and batch-invariant kernels gets closer.)

---

## 5. Code examples by use case

Default language is **Java**. Examples are idiomatic and copy-adaptable; non-obvious lines are commented. Replace the model names/keys as needed. (APIs evolve — pin versions and consult current docs; the *shapes* below are stable.)

### 5.1 Minimal raw HTTP call (no SDK) — see exactly what's on the wire

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class RawCompletion {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY"); // never hardcode secrets
        // The body is plain JSON. Note: messages carry ALL state (stateless API).
        String body = """
            {
              "model": "gpt-4o-mini",
              "messages": [
                {"role": "system", "content": "You are a terse assistant."},
                {"role": "user",   "content": "Name 3 JVM garbage collectors."}
              ],
              "temperature": 0.2,
              "max_tokens": 200
            }
            """;

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)) // fail fast on connect
            .build();

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))        // total request timeout; tune to expected output length
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println(resp.statusCode()); // 200 ok; 429 rate-limited; 400 bad request
        System.out.println(resp.body());       // contains choices[0].message.content + usage{...}
    }
}
```

Key takeaways: the entire conversation is in `messages`; `usage` in the response tells you billed tokens; HTTP status codes are your error contract (`429` → back off).

### 5.2 Streaming tokens via SSE (responsive UX)

```java
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.stream.Stream;

public class StreamingCompletion {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String body = """
            {
              "model": "gpt-4o-mini",
              "stream": true,                                  // ask for SSE token stream
              "messages": [{"role":"user","content":"Explain the KV cache in 3 sentences."}]
            }
            """;
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        // Stream the body line-by-line; each SSE event is "data: {...}" then "data: [DONE]"
        HttpResponse<Stream<String>> resp =
            client.send(req, HttpResponse.BodyHandlers.ofLines());
        resp.body()
            .filter(line -> line.startsWith("data: "))
            .map(line -> line.substring(6))                    // strip "data: "
            .takeWhile(json -> !json.equals("[DONE]"))         // terminal sentinel
            .forEach(json -> {
                // Each chunk has choices[0].delta.content with a token fragment.
                // Parse with Jackson in real code; here we just print raw chunks.
                System.out.print(extractDelta(json));
                System.out.flush();
            });
    }
    static String extractDelta(String json) { /* parse choices[0].delta.content */ return ""; }
}
```

Streaming cuts **perceived** latency: you show tokens as they arrive (TTFT in ~hundreds of ms) instead of waiting for the full response.

### 5.3 LangChain4j: chat with managed memory + production-grade client

```java
// build.gradle: implementation 'dev.langchain4j:langchain4j-open-ai:<version>'
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import java.time.Duration;

public class ChatService {

    interface Assistant {
        @SystemMessage("You are a concise senior Java mentor. Prefer code over prose.")
        String chat(String userMessage);
    }

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .temperature(0.3)             // low: we want stable, focused answers
            .maxTokens(500)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(2)                // built-in retry on transient errors
            .build();

        Assistant assistant = AiServices.builder(Assistant.class)
            .chatLanguageModel(model)
            // Memory automatically appends history each call and trims to last N messages,
            // protecting the context window WITHOUT you managing the messages array by hand.
            .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
            .build();

        System.out.println(assistant.chat("What's the difference between G1 and ZGC?"));
        System.out.println(assistant.chat("Which would you pick for a 200GB heap?")); // remembers prior turn
    }
}
```

`MessageWindowChatMemory` embodies §3.2: it owns the history and trims it so the context window doesn't overflow. `TokenWindowChatMemory` trims by token count instead of message count (more precise for cost/limits).

### 5.4 Deterministic structured extraction (JSON schema, low temperature)

```java
// Goal: extract structured fields from messy text, reliably, for downstream code.
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

public class Extraction {

    // The model is asked to populate this POJO; LangChain4j builds a JSON schema from it
    // and uses structured output so parsing is reliable.
    record ShippingInfo(String fullName, String streetAddress, String city,
                        String postalCode, String country) {}

    interface AddressExtractor {
        @UserMessage("Extract the shipping address from this text:\n\n{{it}}")
        ShippingInfo extract(String text);
    }

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-2024-08-06") // pin version: structured outputs supported
            .temperature(0.0)               // extraction = deterministic, no creativity
            .strictJsonSchema(true)         // forces schema-valid output (constrained decoding)
            .build();

        AddressExtractor ex = AiServices.create(AddressExtractor.class, model);
        ShippingInfo info = ex.extract(
            "Pls ship to Jane Roe, 12 Baker St, flat 4, London EC1A 1BB, United Kingdom. Thx!");
        System.out.println(info); // typed object, ready for your domain logic
    }
}
```

Notes: `temperature=0` + `strictJsonSchema` makes this robust enough to feed a typed pipeline. This is the *right* way to get structured data out of an LLM — not regex on free text.

### 5.5 RAG (Retrieval-Augmented Generation) — answer over your private data

```java
// Pattern: retrieve relevant chunks from a vector store, inject them as context,
// instruct the model to answer ONLY from that context. Mitigates hallucination + stale knowledge.
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;

public class RagAnswer {
    // Assume an embeddingModel and an embeddingStore populated at ingestion time.
    static String answer(String question,
                         EmbeddingModel embeddingModel,
                         EmbeddingStore<TextSegment> store,
                         dev.langchain4j.model.chat.ChatLanguageModel chat) {

        // 1. Embed the question into a vector.
        var qVec = embeddingModel.embed(question).content();

        // 2. Retrieve top-k most similar chunks (cosine similarity in vector space).
        var matches = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(qVec)
                .maxResults(5)            // k: balance recall vs context cost
                .minScore(0.6)            // drop weak matches
                .build()).matches();

        // 3. Build a grounded prompt. Explicitly forbid answering beyond the context.
        StringBuilder ctx = new StringBuilder();
        matches.forEach(m -> ctx.append("- ").append(m.embedded().text()).append("\n"));
        String prompt = """
            Answer the question using ONLY the context below.
            If the answer is not in the context, say "I don't know."

            Context:
            %s
            Question: %s
            """.formatted(ctx, question);

        // 4. Low temperature for faithful, grounded answers.
        return chat.generate(prompt);
    }
}
```

RAG addresses two core LLM limits at once: **no real-time/private knowledge** (you supply it) and **hallucination** (you ground answers and instruct "say I don't know").

### 5.6 Tool / function calling (let the model act)

```java
// The model decides to call a tool; you execute it and feed the result back. Two LLM calls.
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

public class ToolCalling {

    static class WeatherTools {
        @Tool("Get the current temperature in Celsius for a city")
        int currentTempCelsius(String city) {
            // Real impl: call a weather API. The LLM cannot do I/O itself.
            return switch (city.toLowerCase()) {
                case "london" -> 14;
                case "bengaluru" -> 28;
                default -> 20;
            };
        }
    }

    interface Agent { String chat(String msg); }

    public static void main(String[] args) {
        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .temperature(0.0)            // tool-use should be deterministic & precise
            .build();

        Agent agent = AiServices.builder(Agent.class)
            .chatLanguageModel(model)
            .tools(new WeatherTools())   // schema auto-generated from @Tool annotations
            .build();

        // Internally: 1) LLM emits a tool_call -> 2) framework runs currentTempCelsius("Bengaluru")
        //             3) result fed back -> 4) LLM produces the final natural-language answer.
        System.out.println(agent.chat("Should I take a jacket in Bengaluru today?"));
    }
}
```

This shows the model's true role: it *plans and phrases*; your code *acts*. The model cannot fetch live data, do math reliably, or hit a DB — tools bridge that gap.

### 5.7 Production-hardened client (timeout, retry with backoff, circuit breaker)

```java
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Supplier;

public class HardenedLlmClient {

    private final Retry retry = Retry.of("llm", RetryConfig.custom()
        .maxAttempts(3)
        .intervalFunction(io.github.resilience4j.core.IntervalFunction
            .ofExponentialRandomBackoff(Duration.ofMillis(500), 2.0)) // jittered backoff
        .retryExceptions(java.io.IOException.class)                    // retry transient only
        .build());

    private final CircuitBreaker breaker = CircuitBreaker.ofDefaults("llm");

    public String callWithGuards(Supplier<String> rawLlmCall) {
        // Compose: circuit breaker wraps retry wraps the actual call.
        Supplier<String> guarded =
            CircuitBreaker.decorateSupplier(breaker,
                Retry.decorateSupplier(retry, rawLlmCall));
        try {
            return guarded.get();
        } catch (Exception e) {
            // Fallback: degrade gracefully rather than failing the whole request.
            return "Service is busy; please retry shortly.";
        }
    }
}
```

Treat the LLM like any flaky network dependency: **timeouts, jittered exponential backoff on `429`/`5xx`/timeouts, circuit breaker, and a graceful fallback.** Never retry on `400` (bad request) — that's a bug in your payload, not a transient fault.

### 5.8 Exact token counting & cost estimation (avoid surprises)

```java
// Use the real tokenizer; do not estimate from characters in production billing paths.
// jtokkit is a JVM port of tiktoken.
// build.gradle: implementation 'com.knuddels:jtokkit:<version>'
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.*;

public class TokenCost {
    public static void main(String[] args) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        Encoding enc = registry.getEncoding(EncodingType.CL100K_BASE); // GPT-4-family encoding

        String prompt = "Summarize the quarterly report in two bullet points.";
        int inputTokens = enc.countTokens(prompt);
        int estOutputTokens = 100; // your budget cap (max_tokens)

        // Example pricing ($ per 1M tokens) — REPLACE with current numbers; these are illustrative.
        double inUsdPerM = 0.15, outUsdPerM = 0.60;
        double cost = (inputTokens * inUsdPerM + estOutputTokens * outUsdPerM) / 1_000_000.0;

        System.out.printf("input=%d tokens, est cost=$%.6f%n", inputTokens, cost);
    }
}
```

This makes the cost model concrete: **you pay per token, output usually costs several times more than input, and long histories silently inflate input cost every turn.**

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency

- **Latency is dominated by output tokens.** TTFT depends on prompt size + queueing; total time ≈ TTFT + (output_tokens / tokens_per_second). To go faster: **stream**, **cap `max_tokens`**, **shorten prompts**, pick a **smaller/faster model**, and use **reasoning models only when needed** (their hidden thinking tokens add large latency/cost).
- **Prompt caching.** If your prompt has a large stable prefix (system prompt, few-shot examples, retrieved docs), use provider **prompt caching** to reuse the prefill computation — big latency and cost wins (cached input tokens are much cheaper). Put the *stable* content first, the *variable* content last.
- **Batching.** On self-hosted serving, **continuous batching** (vLLM) and a healthy batch size maximize throughput. There's a throughput-vs-latency tradeoff: bigger batches = higher tokens/sec aggregate but higher per-request latency.
- **Quantization.** Serving weights in 8-bit/4-bit (INT8, FP8, GPTQ, AWQ) cuts memory and increases speed with modest quality loss — relevant if self-hosting.
- **Connection reuse.** Use HTTP/2 keep-alive connection pools; the TLS handshake per call is non-trivial at scale.
- **Parallelism.** LLM calls are I/O-bound (network). Use async/non-blocking (CompletableFuture, reactive, virtual threads in Java 21+) to fan out concurrent calls instead of blocking threads.

### 6.2 Correctness, concurrency & determinism

- **Non-determinism is the default.** For testable/repeatable behavior use `temperature=0`, pin model versions, set `seed` — but accept it's best-effort (§4.5). Write tests that assert *properties* (valid JSON, contains required fields, passes a checker) not exact strings.
- **Validate every output.** Never trust LLM output as structurally or semantically correct. Parse and validate; on failure, **repair** (re-prompt with the error) or fall back.
- **Idempotency & concurrency.** Each call is independent; there's no shared mutable model state to race on. Your *session store* is the concurrency surface — guard it (e.g., per-conversation locking, optimistic concurrency) so two turns don't interleave history corruptly.
- **Hallucination control.** Ground with RAG; instruct "say you don't know"; lower temperature; require citations; verify factual claims with tools/code; keep humans in the loop for high-stakes outputs.

### 6.3 Security

- **Prompt injection** is the #1 LLM-specific vulnerability: untrusted text (user input, a fetched web page, an email, a retrieved document) contains instructions that hijack the model ("ignore previous instructions and..."). Because the model cannot reliably distinguish "data" from "instructions," **treat all model-reachable content as untrusted.**
  - Mitigations: never give the model more authority/tools than necessary (least privilege); keep system instructions separate and reinforce them; sanitize/escape retrieved content; require human approval for dangerous tool actions; validate and constrain tool inputs/outputs; sandbox tool execution.
- **Data exfiltration / leakage.** Don't put secrets, PII, or proprietary data in prompts to third-party APIs unless contractually allowed; scrub PII; understand the provider's data-retention/training policy; use enterprise/zero-retention tiers where required.
- **Output handling = injection sink.** LLM output rendered as HTML can XSS; as SQL can inject; as a shell command can RCE. **Never** pass LLM output directly into `eval`, SQL, shell, or `innerHTML` without the same escaping/parameterization you'd apply to user input.
- **API key management.** Keys in env/secret manager, never in code or client-side; rotate; scope; monitor spend (a leaked key = unbounded bill).
- **Excessive agency.** Tool-using agents can take harmful real-world actions; gate destructive operations behind confirmation and authorization.

(These map to the **OWASP Top 10 for LLM Applications** — worth reading in full.)

### 6.4 Cost

- **Cost = input_tokens × in_rate + output_tokens × out_rate**, per call. Output is typically 2–5× the input rate. Reasoning tokens are billed (and large).
- Long conversations re-bill the whole history every turn → **trim/summarize aggressively**.
- **Route by difficulty:** cheap small model for easy/most traffic; escalate to a large/reasoning model only when needed ("model cascade").
- Use **prompt caching**, **shorter prompts**, **lower `max_tokens`**, **batch APIs** (50% discounts for async), and **cache identical requests** at your edge.
- Track cost per request/feature in observability; set spend alerts and per-key budgets.

### 6.5 Observability

- Log (with care for PII): model+version, prompt template id, input/output token counts, latency (TTFT + total), `finish_reason`, cost, retry/error rates, cache-hit rate, and a trace id.
- Use **OpenTelemetry GenAI semantic conventions** or tools like Langfuse/LangSmith/Helicone for prompt-level tracing.
- Capture **golden examples** of failures for regression evals.

### 6.6 Testing & evaluation

- **Unit-test prompts** with `temperature=0` and property assertions; LLM output isn't bit-stable, so assert structure/contains/passes-validator, not equality.
- **Eval harness:** maintain a labeled dataset; run candidate prompts/models; score with exact match, regex, JSON-schema validity, embedding similarity, or **LLM-as-judge** (use a strong model to grade outputs against a rubric — cheap but biased, validate against human labels).
- **Regression-gate** prompt and model upgrades: a "minor" model version bump can silently change formatting/behavior. Pin versions; re-run evals before upgrading.

### 6.7 Production hardening checklist

- Timeouts (connect + total), jittered exponential backoff on `429/5xx`, circuit breaker, graceful fallback.
- Idempotency keys for retried requests where the provider supports them.
- Pin model versions; gate upgrades behind evals; monitor `system_fingerprint`.
- Context-window guardrails: count tokens *before* sending; trim/summarize; reject oversized inputs early.
- Output validation + repair loop; constrained decoding for structured output.
- Rate-limit and budget per tenant/key; spend alerts.
- PII scrubbing; secret management; data-retention compliance.
- Caching: prompt cache (provider) + response cache (yours).
- Streaming for UX; cancel streams on client disconnect to stop billing.

### 6.8 Anti-patterns (avoid)

- **Trusting output blindly** (no validation) — leads to crashes, injections, and silent wrong answers.
- **"Please respond only in JSON" without constrained decoding** — fragile; the model adds prose, code fences, or trailing commas. Use structured outputs.
- **Stuffing the entire history forever** — cost explosion + context overflow + "lost in the middle" quality drop. Trim/summarize.
- **Using a reasoning/huge model for trivial tasks** — 10–100× cost/latency for no gain.
- **Treating temperature=0 as fully deterministic** — it isn't on hosted APIs.
- **One giant mega-prompt doing five jobs** — split into focused calls/steps; easier to test and debug.
- **No version pinning** — silent behavior drift on provider updates.
- **Putting secrets/PII in prompts to third-party APIs** without compliance review.
- **Synchronous blocking calls in a request thread pool** — exhausts threads; use async/virtual threads.
- **Estimating tokens from character counts for billing/limits** — use the real tokenizer.

---

## 7. Advanced topics & deep internals

### 7.1 Prompt caching internals

Providers cache the **KV tensors of a prompt prefix** keyed by the exact token prefix. On a cache hit, prefill for that prefix is skipped — the server resumes decoding from the cached state. Rules that fall out:
- Caching is **prefix-based**: only a common *leading* prefix is reused. Put stable content (system prompt, examples, retrieved docs) first; variable content (the user's latest turn) last.
- Even a one-token change near the start invalidates the cache from that point.
- Cached input tokens are billed at a steep discount (provider-specific, often ~10–50% of normal input price). TTFT drops sharply on hits.

### 7.2 Continuous batching & PagedAttention (serving internals)

- **Static batching** waits to fill a batch, then runs it to completion — wasteful because requests finish at different times.
- **Continuous (in-flight) batching** (vLLM, TGI) adds/removes requests from the running batch *every decode step*, keeping the GPU saturated; this multiplies throughput several-fold.
- **PagedAttention** stores the KV cache in fixed-size **blocks ("pages")** that need not be contiguous, like OS virtual memory paging. This eliminates memory fragmentation, allows near-100% KV-cache utilization, and enables **prefix sharing** (multiple requests with the same system prompt share KV blocks). It's the key innovation behind vLLM's throughput.
  - **Paging (OS analogy):** the operating system splits memory into fixed-size pages and maps them flexibly to physical frames, avoiding the need for one big contiguous block; PagedAttention applies the same idea to the KV cache.

### 7.3 Long context: how models reach 128K–1M+ tokens, and the catch

- Native attention is `O(n²)` in sequence length, so naïve long context is expensive. Techniques: **RoPE scaling / position interpolation** (extend the rotary embeddings to longer positions), **YaRN/NTK-aware scaling**, **sparse/sliding-window attention** (Mistral's sliding window attends to a fixed local window), and **FlashAttention** (an IO-aware exact-attention kernel that avoids materializing the huge attention matrix in slow GPU memory — speeds up and reduces memory, enabling longer contexts).
- **The catch — "lost in the middle":** retrieval/recall accuracy degrades for information in the *middle* of a long context; models attend best to the beginning and end. So a 1M-token window does not mean reliable use of all 1M tokens. Put the most important instructions/data at the start or end; don't assume the model "read" everything equally.
- **Cost/latency scale with context.** A long prompt is expensive prefill + large KV cache + slower attention. Long context is not free RAG; often *targeted retrieval* of the right 5K tokens beats dumping 500K.

### 7.4 Reasoning models & inference-time compute

Reasoning models (o-series, DeepSeek-R1, Claude extended thinking, Gemini thinking) are trained (often via RL on math/code with checkable answers) to emit a long internal **chain-of-thought** before the final answer, and to scale **test-time compute**: harder problem → more thinking tokens → better accuracy. Implications:
- They are **slower and pricier** (you pay for thinking tokens, which can dwarf the visible answer). `max_tokens`/budget must account for hidden reasoning.
- Best for genuine multi-step reasoning (math, complex code, planning, agentic). For simple tasks they waste money and time.
- **Don't hand-write "think step by step" chain-of-thought prompts for reasoning models** — they do it internally; explicit CoT prompting can hurt. (For *non-reasoning* models, CoT prompting still helps.)
- Their thinking is not guaranteed faithful — it's a useful artifact, not a verified proof.

### 7.5 Mixture-of-Experts (MoE)

Many frontier models are **MoE**: the FFN block is split into many "expert" sub-networks; a **router** selects a few (e.g., 2 of 64) experts per token. This gives a huge **total** parameter count (capacity/knowledge) while only **activating** a fraction per token (compute cost). E.g., a model might have 600B total but activate ~37B per token.
- Implications: cheaper inference per quality than a dense model of equal total size; but **routing varies with batch composition**, adding another source of non-determinism; load-balancing across experts is an operational concern when self-hosting.

### 7.6 Embeddings as a sibling capability

The same Transformer machinery yields **embedding models**: instead of next-token logits, they output a single vector representing the meaning of a text. Used for **semantic search, RAG retrieval, clustering, classification, deduplication.** Similarity = cosine distance between vectors. Embedding calls are far cheaper/faster than generation. Know that "embeddings" and "chat completion" are different endpoints/models even though both are Transformers.

### 7.7 Fine-tuning vs prompting vs RAG (when to customize the model)

- **Prompting/few-shot:** cheapest, fastest to iterate; first resort.
- **RAG:** for injecting fresh/private *knowledge*; doesn't change the model's *behavior/style*.
- **Fine-tuning (full or PEFT/LoRA):** for changing *behavior, format, style, or specialized skill*, or to compress a long prompt into weights. **LoRA (Low-Rank Adaptation)** trains small adapter matrices instead of all weights — cheap, fast, swappable; **QLoRA** does it on a quantized base for low memory. Fine-tuning is *not* the way to add facts (use RAG); it's for *how* the model responds.
- Rule: **prompt first → RAG for knowledge → fine-tune for behavior.**

### 7.8 Lesser-known behaviors

- **Tokenizer artifacts:** the model can't reliably count letters or reverse strings ("how many r's in strawberry") because it sees tokens, not characters; arithmetic is unreliable for the same reason — use tools.
- **Position/recency bias:** later instructions can override earlier ones; conflicting instructions resolve unpredictably.
- **Sensitivity to formatting:** delimiters, examples, and ordering materially change output; few-shot example order matters.
- **System-prompt priority:** system/developer messages are weighted higher than user messages but are **not** a hard security boundary (prompt injection still works).
- **Sampling degeneracies:** greedy decoding loops; very high temperature produces incoherence; repetition penalties can over-suppress legitimate repeats (e.g., in code/tables).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Model selection

| Dimension | Small/cheap model | Large model | Reasoning model |
|---|---|---|---|
| Cost | $ | $$$ | $$$$ (thinking tokens) |
| Latency | Low | Medium | High |
| Simple tasks (classify, extract, rewrite) | Best fit | Overkill | Overkill |
| Broad knowledge / nuanced writing | Limited | Best fit | Good |
| Hard math/code/multi-step logic | Weak | OK | Best fit |
| When to use | High volume, easy tasks, latency-sensitive | General product quality | Genuine reasoning, agentic, verifiable hard problems |

**Decision rule:** start with the cheapest model that passes your eval; escalate only on measured failure. Implement a **cascade** (cheap → expensive on low confidence) for high-volume features.

### 8.2 Determinism vs creativity (temperature)

| Goal | temperature | top_p | Example |
|---|---|---|---|
| Deterministic/extraction/classification | `0.0`–`0.2` | `1.0` | Parsing, routing, structured output |
| Balanced assistant | `0.3`–`0.7` | `0.9`–`1.0` | Q&A, summarization |
| Creative/diverse | `0.8`–`1.2` | `0.9`–`1.0` | Brainstorming, marketing copy |

Tune *either* temperature *or* top_p; don't crank both. For repeatable tests, `temperature=0` + `seed` + pinned model.

### 8.3 Add knowledge / change behavior

| Need | Prompting/few-shot | RAG | Fine-tuning (LoRA) |
|---|---|---|---|
| Fresh/private facts | Weak | **Best** | No |
| Specific output format/style | OK | Weak | **Best** |
| New skill/domain behavior | Limited | No | **Best** |
| Lowest cost to start | **Best** | Medium | High (training) |
| Iteration speed | **Fastest** | Fast | Slow |

### 8.4 Build vs buy (hosted API vs self-host)

| | Hosted API (OpenAI/Anthropic/Google) | Self-hosted open-weight (vLLM/TGI) |
|---|---|---|
| Time to ship | Fast | Slow (infra, GPUs) |
| Quality (frontier) | Highest | High (gap narrowing) |
| Cost at low volume | Low | High (idle GPUs) |
| Cost at huge volume | Can be high | Can be lower (amortized) |
| Data control / on-prem | Limited (check policy) | Full |
| Ops burden | None | High (GPU ops, scaling, KV mgmt) |
| Customization (fine-tune, decoding) | Limited | Full |

**Use hosted when:** shipping fast, frontier quality matters, volume modest, no strict data-residency rules. **Self-host when:** strict data control, very high steady volume, need full control/customization, or open-weight quality suffices.

### 8.5 Streaming vs non-streaming

| | Streaming | Non-streaming |
|---|---|---|
| Perceived latency | Low (tokens appear immediately) | High (wait for full output) |
| Implementation | More complex (SSE, partial parsing) | Simple |
| Structured output / validation | Harder (incremental) | Easier (validate whole) |
| Best for | Chat UIs, long outputs | Batch jobs, parse-then-use |

---

## 9. Failure modes & debugging

### 9.1 Catalog of production failures

| Symptom | Likely cause | Diagnosis | Fix |
|---|---|---|---|
| Confidently wrong facts | Hallucination; knowledge gap/stale | Compare to ground truth; check if fact post-dates cutoff | RAG grounding; "say I don't know"; lower temperature; tools |
| Output truncated mid-sentence | Hit `max_tokens` | `finish_reason == "length"` | Raise `max_tokens`; shorten prompt; continue-prompt |
| "Context length exceeded" error | input+output > window | Count tokens before send | Trim/summarize history; chunk; bigger-context model |
| Invalid/partial JSON | Free-form JSON prompting | Inspect raw output (prose, code fences) | Constrained decoding/structured outputs; repair loop |
| Repetitive loops ("the the the") | Greedy/low temp; degeneration | Inspect text; check sampling params | Add temperature/top_p; repetition penalty |
| Latency spikes | Long output; provider load; cold cache; reasoning tokens | TTFT vs total split; token counts | Stream; cap max_tokens; cache; smaller model |
| `429` errors | RPM/TPM rate limit | Response headers (`retry-after`, ratelimit-*) | Backoff+jitter; request limit increase; spread load |
| Cost spike | History growth; oversized prompts; reasoning model; retries | Per-request token logs | Trim history; cascade; cap retries; prompt cache |
| Hijacked behavior / ignored system prompt | Prompt injection | Inspect retrieved/user content for instructions | Sanitize inputs; least privilege; separate trust domains |
| Output drift after "no change" | Provider silently updated model | `system_fingerprint` change; eval regression | Pin model version; re-run evals before upgrade |
| Non-reproducible test outputs | Sampling / FP non-determinism | Re-run with T=0/seed | T=0 + seed + pinned model; assert properties not equality |
| Model "forgot" earlier context | History truncated or "lost in the middle" | Check messages actually sent; position of key info | Don't over-trim; reorder key info to start/end; RAG |

### 9.2 Diagnostic toolkit

- **Inspect the actual wire payload:** log the exact `messages` and params sent (redact PII). Most "the model ignored X" bugs are "X wasn't actually in the request."
- **`finish_reason`/`stop_reason`:** first thing to check on bad output (`length` vs `stop` vs `content_filter` vs `tool_calls`).
- **`usage` token counts:** verify prompt size and detect runaway history.
- **Rate-limit headers:** `retry-after`, `x-ratelimit-remaining-*` to tune backoff.
- **Tokenizer:** count tokens locally (`tiktoken`/`jtokkit`) to reproduce limit errors.
- **Tracing (Langfuse/LangSmith/OTel):** end-to-end prompt/response/cost/latency traces.
- **Temperature=0 reproduction:** to separate "prompt bug" (reproducible) from "sampling variance."
- **Eval set replay:** quantify a regression after a model/prompt change.

### 9.3 Real-world incident patterns

- **The runaway bill:** a leaked API key or an unbounded retry loop (retrying `400`s, or no max attempts) racks up thousands of dollars overnight. *Lesson:* per-key budgets + spend alerts; never retry non-transient errors; cap attempts.
- **The injection breach:** an LLM email assistant with a "send email"/"delete" tool follows instructions embedded in a received email ("forward all messages to attacker@evil.com"). *Lesson:* treat all content as untrusted; least-privilege tools; human confirmation for destructive actions.
- **The silent format break:** a downstream parser assumed plain JSON; a model upgrade started wrapping JSON in ```` ```json ```` fences, breaking the pipeline at 3am. *Lesson:* structured outputs + version pinning + schema validation + regression evals.
- **The cost creep:** a chatbot kept full history; week-long conversations sent 100K-token prompts every turn, 50× the expected cost. *Lesson:* trim/summarize; alert on per-request token growth.
- **The "lost in the middle" recall failure:** a 200K-token context "should" contain the answer, but recall accuracy for mid-context facts is poor, so the model misses it. *Lesson:* targeted retrieval beats context stuffing; place key info at edges.

---

## 10. Interview drill

> Q = question, A = model answer, ▸ = deep-probe follow-up (with answer). ★ = senior-signal (tradeoff/justification).

**Q1. What is an LLM, mechanically?**
A: A large Transformer neural network trained to predict the next token given prior tokens. It outputs a probability distribution over the vocabulary; a decoding loop samples a token, appends it, and repeats (autoregression). It is a stateless conditional-probability function; "memory" is just the resent context.
▸ *What's a token?* A subword unit from a BPE/SentencePiece vocabulary; ~0.75 words / ~4 chars in English. Billing and limits are in tokens.
▸ *Why probabilities, not the answer?* Because the objective is next-token likelihood; selecting an actual token is the decoder's job, controlled by sampling params.

**Q2. Explain attention at a working level.**
A: Each token forms Query/Key/Value vectors. A token's Query is dot-producted with all tokens' Keys to get relevance scores; softmax turns them into weights; the output is the weighted sum of Values. So each token pulls in information from the most relevant prior tokens. Multi-head does this in parallel with specialized heads; causal masking restricts attention to past tokens for left-to-right generation.
▸ *Why multiple heads?* Different heads specialize (syntax, coreference, long-range) and their outputs concatenate, increasing representational richness.
▸ *Why causal mask?* So a token can't see the future, making the model a valid autoregressive next-token predictor.

**Q3. Pretraining vs instruction tuning vs RLHF — what does each add?**
A: Pretraining on a huge corpus yields a base model that's a great autocomplete but doesn't follow instructions. SFT on (instruction, response) pairs turns it into an assistant that follows directions and the chat format. RLHF (or DPO) optimizes toward human preferences via a reward model + RL, improving helpfulness, formatting, safety, and tone.
▸ *DPO vs RLHF?* DPO optimizes directly on preference pairs with a simple loss, skipping the separate reward model and PPO loop — simpler and cheaper, often comparable quality.
▸ *Why does an aligned model sometimes refuse benign requests?* "Alignment tax": safety tuning can over-trigger refusals/hedging; a behavioral artifact of preference tuning.

**Q4. Why is the API stateless, and what follows from that?**
A: The model holds no memory between calls; each call is a pure function of the tokens sent. So you must resend the full conversation each turn (managing/trimming it yourself), you re-pay input cost for history every turn, and overflowing the context window or truncating history is *your* bug, not the model's.
▸ *Then how do chatbots remember?* Your app replays prior messages in the `messages` array; optionally caches/summarizes.
▸ *What's prompt caching then?* A server-side performance optimization caching the prefill KV for a token prefix — semantics stay stateless, but a repeated prefix is cheaper/faster.

**Q5. Walk through temperature, top-p, top-k. How do they interact?**
A: Temperature scales logits before softmax: lower = sharper/deterministic, higher = flatter/random; 0 ≈ greedy. Top-k restricts sampling to the k highest-prob tokens; top-p (nucleus) restricts to the smallest set with cumulative prob ≥ p (adaptive). They compose: penalties → temperature → top-k → top-p → sample. At T=0, top-p/top-k are irrelevant (argmax).
▸ *Tune which for creativity?* Either temperature or top_p — not both aggressively; convention is to fix one at default.
▸ *How to get reproducible output?* T=0 + seed + pinned model, accepting it's best-effort on hosted APIs.

**Q6. Why isn't temperature=0 fully deterministic on a hosted API?**
A: Floating-point reductions are non-associative and depend on batch composition (you share GPU batches with other users), plus kernel/library/hardware versions and MoE routing. So argmax can flip on near-ties. It's low-variance, not bit-exact.
▸ *How to get closer?* Self-host with fixed hardware, T=0, batch-invariant kernels.
▸ *How should tests cope?* Assert properties (valid JSON, contains fields, passes a checker), not exact strings.

**Q7. What can't LLMs do, and how do you compensate?**
A: No real-time/post-cutoff knowledge (→ RAG/tools); they hallucinate (→ grounding, "say I don't know," verification, low temperature); they can't reliably do exact math, count characters, or perform actions (→ tools/code); base reasoning is shallow (→ reasoning models or CoT prompting); they can't be trusted as structurally/semantically correct (→ validate + repair).
▸ *Why can't they count letters in "strawberry"?* They see tokens, not characters; letter-level operations are obscured by tokenization.
▸ *Reasoning models vs CoT prompting?* Reasoning models are RL-trained to think internally and scale test-time compute; for them, explicit "think step by step" can hurt; for non-reasoning models, CoT prompting helps.

**Q8. ★ You must ship a high-volume support-ticket classifier. Pick and justify a model + decoding setup.**
A: Start with the cheapest small instruct model that passes an eval on labeled tickets; classification is easy and latency/cost-sensitive at volume. Use `temperature=0` (deterministic), constrained/structured output to force one of the allowed labels (logit_bias or JSON schema), short prompt, low `max_tokens`. Add a confidence-based cascade: escalate ambiguous cases to a larger model. Pin the version; gate upgrades behind the eval. Justification: matches task difficulty to cost, guarantees valid labels, controls spend, and stays reproducible/testable.
▸ *Why not fine-tune immediately?* Prompt+few-shot is faster/cheaper to iterate; fine-tune only if eval shows prompting plateaus below target accuracy or to cut prompt cost.
▸ *Why constrained output over "respond with one label"?* Free-form prompting leaks prose/variants; constrained decoding makes invalid labels impossible.

**Q9. ★ When would you choose RAG over fine-tuning, and vice versa?**
A: RAG to inject fresh/private/changing *knowledge* without retraining and with citations/auditability; fine-tuning to change *behavior, style, or format* or to compress a long static prompt into weights. They're complementary: RAG for "what it knows," fine-tuning for "how it responds." Don't fine-tune to add facts (brittle, costly, stale); don't RAG to fix tone/format.
▸ *Cost/iteration angle?* RAG: no training, fast iteration, per-call retrieval cost; fine-tuning: upfront training cost, slow iteration, cheaper/shorter prompts after.
▸ *Combine both?* Yes — fine-tune for format/behavior, RAG for current facts; common in production.

**Q10. ★ Design the production wrapper around an LLM provider call.**
A: Treat it as a flaky network dependency: connect+total timeouts; jittered exponential backoff on 429/5xx/timeouts only (never on 400); circuit breaker; graceful fallback; idempotency keys. Pre-send token counting + history trimming to respect the window; structured output + validation + repair loop. Pin model version; gate upgrades behind evals; monitor `system_fingerprint`. Observability: tokens, latency (TTFT+total), cost, finish_reason, error/retry/cache-hit rates, trace ids. Cost controls: per-key budgets, prompt caching, model cascade, response cache. Security: secret management, PII scrubbing, prompt-injection mitigations, careful output handling. Async/virtual threads for I/O-bound concurrency.
▸ *Why not retry 400s?* They're payload bugs, not transient — retrying wastes money and never succeeds.
▸ *Biggest LLM-specific security risk?* Prompt injection — untrusted content as instructions; mitigate with least privilege, sanitization, human gates on dangerous actions, and treating output as untrusted input to sinks.

**Q11. Explain the KV cache and why it dominates serving capacity.**
A: Attention needs each prior token's Key/Value; in causal models these don't change, so the server caches K/V per token/layer/head and only computes Q/K/V for the new token each step — turning O(N²) into O(N). But the cache is per-request and grows with sequence length × batch, often consuming more memory than weights, so it limits concurrency and max context. GQA/MQA shrink it; PagedAttention manages it as non-contiguous pages for high utilization and prefix sharing.
▸ *What is GQA?* Grouped-Query Attention shares K/V across groups of heads, cutting KV-cache size/bandwidth with minimal quality loss; standard in Llama 3/Mistral.
▸ *Why does long context cost so much?* Bigger prefill, larger KV cache, slower O(n²) attention — plus "lost in the middle" recall degradation.

**Q12. What's the cost model, and how do you control spend?**
A: Pay per token: input × in_rate + output × out_rate, output typically 2–5× input; reasoning tokens billed and large; long histories re-bill every turn. Control via: cheapest-model-that-passes + cascade; trim/summarize history; prompt caching; cap max_tokens; structured short prompts; batch API discounts; response caching; per-key budgets + alerts; cap retries.
▸ *Why is output pricier than input?* Output is generated sequentially (memory-bandwidth-bound, one forward pass per token) while input prefill is parallel; output dominates latency too.
▸ *Hidden cost in reasoning models?* Thinking tokens you pay for but may not see; budget `max_tokens` accordingly.

---

## 11. Glossary

- **Alignment:** tuning a model to be helpful, harmless, and honest per human preferences (via RLHF/DPO/Constitutional AI).
- **Attention:** mechanism letting each token weigh and pull information from other tokens via Query/Key/Value.
- **Autoregressive:** generating output one token at a time, feeding each output back as input.
- **Base model / foundation model:** a pretrained-only model (good autocomplete, doesn't follow instructions).
- **Batch / continuous batching:** grouping requests to saturate the GPU; continuous batching adds/removes requests every step.
- **BPE (Byte-Pair Encoding):** subword tokenization that merges frequent character/byte pairs into a fixed vocabulary.
- **Causal mask:** restriction so tokens attend only to earlier tokens, enabling left-to-right generation.
- **Chain-of-thought (CoT):** intermediate reasoning steps a model produces before an answer.
- **Chinchilla:** finding that compute-optimal training uses ~20 training tokens per parameter.
- **Constrained / structured decoding:** masking invalid tokens to force schema/grammar-valid output.
- **Context window:** max tokens (input + output) the model can attend to at once.
- **Cross-entropy loss:** training objective penalizing low probability assigned to the true next token.
- **Decoding / sampling:** choosing the next token from the output distribution (greedy, top-k, top-p, etc.).
- **Determinism:** producing identical output for identical input; best-effort on hosted LLMs.
- **DPO (Direct Preference Optimization):** preference tuning directly on ranked pairs, no reward model/RL loop.
- **Embedding:** dense vector representing a token's/text's meaning; also a model type for semantic search.
- **EOS (end-of-sequence) token:** special token signaling generation should stop.
- **Feed-forward network (FFN/MLP):** per-token nonlinear block holding much of the model's knowledge.
- **Few-shot:** including example input/output pairs in the prompt to steer behavior.
- **Fine-tuning:** further training a model on task/style data (full or PEFT/LoRA).
- **FlashAttention:** IO-aware exact-attention kernel that speeds up attention and reduces memory.
- **finish_reason / stop_reason:** why generation stopped (stop, length, content_filter, tool_calls).
- **GQA (Grouped-Query Attention):** sharing K/V across head groups to shrink the KV cache.
- **Greedy decoding:** always pick the highest-probability token.
- **Hallucination:** confident generation of false or fabricated content.
- **Hidden dimension (d):** size of each token's internal vector.
- **Inference:** running a trained model to produce output (vs training).
- **Instruction tuning (SFT):** supervised fine-tuning on (instruction, response) pairs to make an assistant.
- **Knowledge cutoff:** date after which the model has no training knowledge.
- **KV cache:** cached Key/Value tensors for prior tokens to avoid recomputation; dominates serving memory.
- **LoRA / QLoRA:** parameter-efficient fine-tuning via small adapters (QLoRA on a quantized base).
- **Logits:** raw unnormalized scores over the vocabulary before softmax.
- **Logit bias:** user-supplied additive bias to specific tokens' logits.
- **"Lost in the middle":** degraded recall for information in the middle of a long context.
- **Mixture-of-Experts (MoE):** model with many expert FFNs; a router activates a few per token.
- **Multi-head attention:** running attention in parallel with multiple specialized heads.
- **Nucleus sampling (top-p):** sample from the smallest token set with cumulative probability ≥ p.
- **Parameter / weight:** a single learned number in the network; fixed at inference.
- **PagedAttention:** managing the KV cache in non-contiguous pages (vLLM) for high utilization/prefix sharing.
- **Perplexity:** exp(loss); effective branching factor; lower = better language model.
- **PPO (Proximal Policy Optimization):** stable RL algorithm used in classic RLHF.
- **Prefill:** the initial forward pass over the whole prompt; sets time-to-first-token.
- **Prompt:** the text instruction/context you send to steer the model.
- **Prompt caching:** server-side reuse of prefill KV for a repeated token prefix.
- **Prompt injection:** attack where untrusted content contains instructions that hijack the model.
- **Quantization:** serving weights at lower precision (INT8/FP8/4-bit) to save memory/speed.
- **RAG (Retrieval-Augmented Generation):** retrieving relevant documents and injecting them as context.
- **Reasoning model:** model RL-trained to produce long internal CoT and scale test-time compute.
- **Reward model:** model predicting human preference scores, used in RLHF.
- **RLHF:** Reinforcement Learning from Human Feedback (reward model + RL).
- **RoPE (Rotary Position Embeddings):** encoding token position by rotating Q/K vectors; supports length extrapolation.
- **Residual connection:** adding a sublayer's input to its output for deep-network trainability.
- **Sampling parameters:** temperature, top-p, top-k, penalties, etc., controlling token selection.
- **Softmax:** function turning logits into a probability distribution.
- **SSE (Server-Sent Events):** one-way HTTP streaming used to deliver tokens incrementally.
- **Stateless:** the model retains no memory across calls; context is resent each time.
- **Stop sequence:** string that halts generation when produced.
- **Subword tokenization:** splitting text into word-pieces (between word and character granularity).
- **System / developer message:** high-priority instructions setting persona/rules/format.
- **Temperature:** scales logits before softmax to control randomness.
- **Test-time / inference-time compute:** extra computation at generation time (e.g., reasoning tokens) to boost quality.
- **Token:** the model's atomic text unit; unit of billing and limits.
- **Tokenizer:** component mapping text ↔ tokens/IDs.
- **Tool / function calling:** the model emitting a structured request for your code to execute an action.
- **Top-k sampling:** sample only from the k highest-probability tokens.
- **Transformer:** the attention-based architecture underlying modern LLMs.
- **TTFT (time-to-first-token):** latency until the first output token (≈ prefill + queueing).
- **Usage:** token counts (prompt/completion/total) returned for billing.
- **Vocabulary:** the fixed set of tokens a model can read/produce.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Mental model:** LLM = stateless pure function `f(tokens) → P(next token)`. Loop: forward pass → sample → append → repeat. You own context, history, validation, tools.

**Key numbers / facts:**
- 1 token ≈ 0.75 word ≈ 4 English chars; non-English costs more tokens.
- Chinchilla: ~20 training tokens per parameter (compute-optimal).
- Output tokens typically 2–5× the input price; output dominates latency.
- Attention is O(n²) in length; KV cache makes generation O(n) per step but is per-request and grows with context — it limits concurrency/max context.
- "Lost in the middle": long-context recall is best at the start/end.

**Training stages:** Pretraining (base, autocomplete) → SFT/instruction tuning (assistant) → RLHF/DPO (preference/safety) → [RL on verifiable tasks → reasoning models].

**Decoding defaults & ranges:** temperature 0–2 (default ~1; 0≈greedy); top_p 0–1 (default 1); top_k engine-dependent; freq/presence penalty −2…2 (default 0); set max_tokens, stop, seed.

**Decision rules:**
- Cheapest model that passes eval; cascade up on failure.
- Deterministic/extraction: T=0 + structured output. Creative: T 0.7–1.0.
- Fresh/private facts → RAG. Behavior/format/style → fine-tune (LoRA). Try prompting first.
- Reasoning model only for genuine multi-step reasoning; don't add manual CoT to it.
- Hosted for speed/quality/low volume; self-host for data control/very high volume.

**Can't do (compensate):** real-time/post-cutoff knowledge (RAG/tools), reliable math/char-counting/actions (tools/code), guaranteed truth (ground+verify), guaranteed structure (constrained decoding + validate).

**Production must-haves:** timeouts, jittered backoff on 429/5xx (never 400), circuit breaker, fallback; pin model version + eval-gated upgrades; token counting + history trimming; output validate+repair; observability (tokens/latency/cost/finish_reason); secret mgmt + PII scrub; prompt-injection mitigations + careful output handling; per-key budgets; prompt + response caching; streaming for UX; async/virtual threads.

**Top failure checks:** `finish_reason` (length vs stop), `usage` tokens (history bloat), rate-limit headers (429), `system_fingerprint` (silent drift), reproduce with T=0.

**#1 security risk:** prompt injection — treat all model-reachable content as untrusted; least privilege; human gate on dangerous tool actions; treat output as untrusted input to any sink (SQL/HTML/shell).

### 12.2 Self-test (no answers — active recall)

1. Explain, end to end, what happens from your `POST /chat/completions` call to the first streamed token — name prefill, batching, the KV cache, and TTFT, and say why output length (not input length) usually dominates total latency.
2. You set `temperature=0` and `seed`, pin the model, and still get two different outputs for the same prompt. Give three distinct mechanistic reasons and how you'd make your *tests* robust to this.
3. A teammate proposes fine-tuning to make the bot "know" your latest product catalog. Argue why this is the wrong tool, what you'd use instead, and exactly what fine-tuning *is* the right tool for.
4. Design the decoding + output strategy for a service that must return one of 12 fixed labels per request at high volume and lowest cost — specify model class, temperature, output-constraint mechanism, escalation policy, and how you'd version and test it.
5. An LLM email agent with a "send" tool starts forwarding internal mail to an external address after processing an incoming message. Diagnose the vulnerability class, explain why the model can't reliably defend itself, and list four concrete mitigations.
6. Your LLM feature's monthly bill grew 40× with no traffic change. Walk through your diagnosis using `usage` token counts and conversation handling, and name the two most likely root causes and their fixes.
7. Explain attention (Q/K/V, multi-head, causal mask) well enough to teach a new hire, then explain what the KV cache caches and why it — not the weights — limits how many concurrent users and how long a context you can serve.

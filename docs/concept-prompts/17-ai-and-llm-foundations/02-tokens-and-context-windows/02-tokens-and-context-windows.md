# Tokens & Context Windows

> An exhaustive engineering-handbook chapter for senior backend developers (Java/JVM-centric) who want to fully master how Large Language Models (LLMs) ingest, count, bill, and limit text — from first principles down to the byte-pair-encoding internals, attention math, caching mechanics, and production failure modes.

---

## 1. Overview & where it fits

### 1.1 What this is

When you call an LLM API — OpenAI's `gpt-4o`, Anthropic's `claude-sonnet`, Google's `gemini`, or a self-hosted Llama/Mistral model — you do **not** send "text" and you are **not** billed by characters or words. You send **tokens**, the model thinks in **tokens**, the model is billed in **tokens**, and the model has a hard ceiling on how many tokens it can consider at once. That ceiling is the **context window**.

- A **token** is a subword unit: a chunk of text (often 3–4 characters of English, sometimes a whole word, sometimes a single character or byte) produced by a **tokenizer** that maps raw bytes ↔ integer IDs from a fixed vocabulary.
- The **context window** is the maximum number of tokens the model can attend to in a single forward pass — and critically, **the prompt (input) and the generated completion (output) share the same budget**.

Everything practical about working with LLMs — cost, latency, what you can fit in a prompt, why a model "forgets" the start of a long conversation, why your RAG (Retrieval-Augmented Generation) system retrieves chunks instead of dumping whole documents — flows from these two concepts.

> **LLM (Large Language Model):** a neural network (almost always a Transformer) trained on enormous text corpora to predict the next token given preceding tokens. "Large" = billions of parameters (the learned weights). GPT-4-class models are on the order of hundreds of billions of parameters; small open models are 1–8 billion.

> **Transformer:** the neural-network architecture (Vaswani et al., 2017, "Attention Is All You Need") underlying essentially all modern LLMs. Its defining mechanism is **self-attention**, which lets every token "look at" every other token in the context. We will return to this because attention is *why* context windows are finite and expensive.

### 1.2 The problem it solves / why it exists

Computers cannot do arithmetic on raw text. A neural network operates on vectors of floating-point numbers. So we need a reproducible scheme to turn the string `"Hello, world"` into a sequence of integers, then into vectors. Tokenization is that scheme.

Two competing pressures shape it:

1. **Vocabulary size must be bounded.** The model has an embedding table — one learned vector per vocabulary entry. If every distinct word were a token, the vocabulary would be millions of entries (think of all word forms, typos, code identifiers, URLs, emoji, CJK characters). Embedding tables would be enormous, and rare words would never get trained well.
2. **Sequences must be reasonably short.** If every byte were a token, `"internationalization"` would be 20 tokens, and the self-attention cost (which grows with the *square* of the sequence length) would explode.

**Subword tokenization** — the dominant approach, usually **Byte-Pair Encoding (BPE)** — threads this needle: common words become single tokens, rare words split into a few subword pieces, and *anything* (any byte sequence, any language, any emoji) can always be represented because the vocabulary bottoms out at the byte level.

The **context window** exists because self-attention is **O(n²)** in sequence length `n` for both compute and memory. You cannot let `n` grow unbounded. Even with engineering tricks, there is always a maximum the model was trained and configured to handle.

### 1.3 When you reach for this knowledge

You need to understand tokens and context windows whenever you:

- **Estimate or control cost.** Billing is per-token. Misjudging token counts is the #1 cause of surprise LLM bills.
- **Hit a `context_length_exceeded` error.** You must know how to count, truncate, summarize, or retrieve.
- **Design a RAG pipeline.** Chunk sizes, retrieval budgets, and the "lost in the middle" effect are all token-window concerns.
- **Optimize latency.** Output tokens are generated one at a time (autoregressively) and dominate latency; input tokens are processed in parallel (the "prefill") and dominate cost. Prompt caching changes both.
- **Build agents / long conversations.** Conversation history grows unbounded; you must manage the window or the agent degrades and costs spiral.
- **Answer interview questions** about LLM economics and architecture.

### 1.4 One-paragraph mental model

> Think of the context window as a **fixed-size buffer measured in tokens**, like a TCP receive window or a fixed-capacity ring buffer. Text is **encoded** into integer tokens by a deterministic tokenizer (≈4 English characters per token, but highly variable). Everything you put in — system prompt, conversation history, retrieved documents, the user's question — **plus** everything the model writes back must fit inside that single buffer. You pay rent per token, at different rates for tokens going in (cheap, processed in parallel) versus tokens coming out (expensive, generated serially). The model attends to every token against every other token (cost ∝ n²), so a bigger buffer is quadratically more expensive and, paradoxically, can make the model *worse* at using the information buried in the middle. Your engineering job is to decide what earns a seat in that buffer.

---

## 2. Foundations from first principles

We build up from raw bytes to a model's input, defining every term.

### 2.1 Characters, code points, bytes, and why "text" is slippery

Before tokens, recall what a string *is*:

- A **character** (informally) is a thing a human sees: `A`, `é`, `中`, `😀`.
- A **Unicode code point** is an integer assigned to each abstract character, e.g. `A` = U+0041, `é` = U+00E9, `😀` = U+1F600. There are ~1.1 million possible code points.
- **UTF-8** is the dominant *encoding* of code points into **bytes** (8-bit values 0–255). ASCII characters take 1 byte; `é` takes 2 bytes; most CJK characters take 3 bytes; emoji take 4 bytes.

> **Why this matters for tokens:** modern tokenizers operate on **UTF-8 bytes**, not on characters. This is what makes them "byte-level" — they can represent *any* input, including invalid Unicode, binary-ish data, and languages they never saw, by falling back to individual bytes. This is also why **a single emoji can cost several tokens** and why **non-English text is more token-expensive**.

In Java, a `String` is UTF-16 internally, and `String.length()` returns the number of UTF-16 code *units* (so `"😀".length() == 2` because it's a surrogate pair). None of these — characters, code points, bytes, UTF-16 units — equals tokens. **Token count is its own thing**, computable only by running the specific model's tokenizer.

### 2.2 What a tokenizer is

A **tokenizer** is a deterministic, reversible function with two operations:

- **encode**: `String → List<Integer>` (token IDs)
- **decode**: `List<Integer> → String`

It carries a fixed **vocabulary**: a bidirectional map between token IDs (e.g. `15339`) and token byte-strings (e.g. `"Hello"`). The vocabulary size is a fixed number, typically:

| Model family | Tokenizer | Vocab size (approx) |
|---|---|---|
| GPT-3.5 / GPT-4 (legacy) | `cl100k_base` (tiktoken) | ~100,277 |
| GPT-4o / GPT-4.1 / o-series | `o200k_base` (tiktoken) | ~200,019 |
| Llama 2 | SentencePiece BPE | 32,000 |
| Llama 3 / 3.1 | tiktoken-style BPE | 128,256 |
| Mistral (v1) | SentencePiece BPE | 32,000 |
| Anthropic Claude | proprietary (not publicly released) | not published |
| Google Gemini | SentencePiece-based | not fully published |

> **Note (vendor-specific):** Anthropic does **not** publish its tokenizer. You count Claude tokens via Anthropic's API `count_tokens` endpoint, not a local library. Treat the "≈4 chars/token" rule as an estimate for Claude, and use the API for anything billing-critical. OpenAI *does* publish its tokenizers via the open-source `tiktoken` library, so you can count locally and exactly.

### 2.3 Byte-Pair Encoding (BPE) — the core algorithm

BPE is the most common subword algorithm. Originally a 1994 data-compression technique (Gage), it was adapted to NLP (Sennrich et al., 2016). Here is the algorithm from first principles.

**Training the tokenizer (done once, by the model vendor):**

1. Start with a base vocabulary = every individual byte (or character). So 256 byte tokens (for byte-level BPE) are always present. This guarantees *any* input is representable.
2. Take a huge training corpus. Pre-split it into words (or word-like chunks) using a **pre-tokenization** regex (more on this below).
3. Represent each word as a sequence of base tokens.
4. **Count all adjacent token pairs** across the corpus. Find the most frequent pair, e.g. (`t`, `h`).
5. **Merge** that pair into a new token `th`, add it to the vocabulary, and record the **merge rule** `(t, h) → th`.
6. Repeat steps 4–5 until the vocabulary reaches the target size (e.g. 100k). The result is an ordered list of merge rules.

**Encoding at inference time (what happens on every API call):**

1. **Pre-tokenize** the input with the regex into chunks (so merges never cross, say, a word boundary into the next word in ways the regex forbids).
2. Within each chunk, start from bytes/characters.
3. Apply the learned merge rules **in priority order** (most-frequent merges first), greedily merging adjacent pairs, until no more merges apply.
4. Emit the resulting token IDs.

**Worked micro-example.** Suppose merge rules learned (in order): `(l,o)→lo`, `(lo,w)→low`, `(e,r)→er`. Encoding `"lower"`:

- Start: `l o w e r`
- Apply `(l,o)→lo`: `lo w e r`
- Apply `(lo,w)→low`: `low e r`
- Apply `(e,r)→er`: `low er`
- Result: 2 tokens: `low`, `er`.

Because common words got merged into single tokens during training, frequent English words are usually 1 token; rare/long/compound words split. `"the"` → 1 token. `"tokenization"` → often 2 tokens (`token`, `ization`). A random string like `"xq7zk"` → several tokens, sometimes one per byte.

> **WordPiece** (used by BERT) and **Unigram LM** (used by SentencePiece/T5) are alternatives. WordPiece is BPE-like but chooses merges by likelihood gain rather than raw frequency. Unigram starts with a large vocabulary and *prunes* down, scoring tokenizations probabilistically. For generative LLMs, BPE (or byte-level BPE) dominates. **SentencePiece** is a *library/wrapper* that can run either BPE or Unigram and famously treats the input as a raw stream (encoding spaces as a visible marker `▁`), making it language-agnostic and reversible without language-specific pre-tokenization.

### 2.4 Pre-tokenization, spaces, and the `Ġ` / `▁` mystery

If you ever dump GPT tokens, you'll see weird leading characters like `Ġworld` or `Ċ`. These come from **byte-level BPE's encoding of whitespace**:

- A space before a word is part of the token. `"hello"` and `" hello"` are **different tokens**. GPT tokenizers represent the leading space as `Ġ` (a display artifact for byte 0x20 in their byte-to-unicode mapping); newline shows as `Ċ`.
- SentencePiece uses `▁` (U+2581, "lower one eighth block") to mark a space.

**Practical consequence:** `"hello world"` is often 2 tokens (`hello`, `Ġworld`), but `"hello"` followed separately by `"world"` (no space) tokenizes differently. This is why **concatenating strings and re-tokenizing can change the token count** versus tokenizing the parts separately, and why token counts are not additive in general.

### 2.5 The ≈4-characters-per-token rule (and when it lies)

A widely cited heuristic for English prose: **1 token ≈ 4 characters ≈ 0.75 words**. Equivalently, ~**1.33 tokens per word**, or ~**750 words per 1,000 tokens**. OpenAI popularized "100 tokens ≈ 75 words."

This is a *rough average for English prose with the cl100k/o200k tokenizers*. It breaks down badly in many cases:

| Content type | Token density vs English prose | Why |
|---|---|---|
| English prose | baseline (~4 chars/token) | tokenizer trained on it |
| Code (JSON, Java, Python) | **more tokens** (~2.5–3.5 chars/token) | punctuation, indentation, `camelCase`/`snake_case` split, braces |
| Numbers / IDs / UUIDs | **many more** | digits often tokenize individually or in small groups |
| Chinese / Japanese / Korean | **far more tokens per character** | each CJK char may be 1+ tokens; older tokenizers split into bytes (3 bytes → up to 3 tokens) |
| Other non-Latin scripts (Arabic, Hindi, Thai) | **2–4× more** | under-represented in vocab → byte fallback |
| Whitespace-heavy / formatted text | variable | runs of spaces sometimes merge into single tokens (cl100k added merges for indentation) |
| Repeated characters (`"aaaa..."`) | fewer than expected | merges collapse repeats |

> **Real number to remember:** the "tax" on non-English languages is real and economic. The same *information* in Hindi or Thai can cost 2–4× the tokens of English, meaning 2–4× the price and faster context-window exhaustion. o200k_base improved CJK/multilingual efficiency over cl100k_base, but the disparity remains.

### 2.6 Embeddings — what tokens become inside the model (brief, for grounding)

After tokenization you have integers. The model's **embedding layer** is a lookup table of shape `[vocab_size, d_model]`: each token ID indexes a learned vector of dimension `d_model` (e.g. 4096). To these the model adds **positional information** (so it knows token order — via learned positional embeddings, or **RoPE**, Rotary Positional Embeddings, in modern models). The stack of Transformer layers then runs **self-attention** and feed-forward networks over this sequence of vectors.

> **RoPE (Rotary Position Embedding):** a way of encoding token position by rotating the query/key vectors as a function of position. It matters here because **long-context extension techniques** (NTK scaling, YaRN, "position interpolation") manipulate RoPE to let a model trained at, say, 8k tokens generalize to 128k+. This is the machinery behind ever-larger advertised context windows.

### 2.7 The context window, precisely

The **context window** (a.k.a. context length, `max_context`, `n_ctx`, sequence length limit) is the maximum number of tokens the model processes in one forward pass.

Two non-negotiable facts:

1. **Input + output share it.** If a model has a 128k window and your prompt is 120k tokens, the model can generate at most ~8k tokens before it physically runs out of positions. Many APIs let you set `max_tokens` (the output cap); if `prompt_tokens + max_tokens > context_window`, you get an error *before generation even starts*.
2. **It's a hard constraint, not a soft suggestion.** The positional encodings, the KV-cache buffers, and the trained behavior all assume `n ≤ context_window`. Exceed it and you get a `400` error like `context_length_exceeded` / `prompt is too long`.

A subtlety: some providers separately advertise a **maximum output length** that is *smaller* than the context window (e.g. a 200k-token context but a 64k or 8k max output). Always check both numbers.

> **Common context windows (as of mid-2020s; version-specific — verify against current docs):**
> - GPT-4 (original `8k` and `32k` variants), GPT-4 Turbo / GPT-4o: **128k** tokens.
> - GPT-4.1 family: up to **~1,000,000** tokens (input).
> - Anthropic Claude 3/3.5/3.7/4 Sonnet/Opus: **200k** tokens standard; some configurations offer **1M** (beta/tiered).
> - Google Gemini 1.5/2.x Pro: **1,000,000–2,000,000** tokens.
> - Llama 3.1: **128k**. Mistral Large: **128k**. Many open 7B models: **8k–32k**.
> These numbers change frequently. Treat them as "order of magnitude" and confirm in the provider's current model card.

---

## 3. How it works internally

This is the heart of the chapter. We trace, step by step, what happens to your text from API call to billed response, and why each step has the cost/latency/limit characteristics it does.

### 3.1 End-to-end lifecycle of a single request

```
Your bytes
   │  (1) Tokenize
   ▼
[token IDs]  ── (2) Assemble full prompt (system + history + RAG + user) ──┐
   │                                                                        │
   │  (3) Validate against context window                                   │
   ▼                                                                        │
[prefill]  (4) Parallel forward pass over ALL input tokens → build KV cache │
   │                                                                        │
   ▼                                                                        │
[decode]   (5) Generate output tokens ONE AT A TIME (autoregressive),       │
   │           each new token attends to KV cache of all prior tokens       │
   ▼                                                                        │
[stop]     (6) Stop on EOS token, max_tokens, or stop sequence              │
   │                                                                        │
   ▼                                                                        │
[detokenize] (7) Decode output token IDs → text                             │
   │                                                                        │
   ▼                                                                        │
[billing]   (8) Bill input_tokens at input rate, output_tokens at output rate
```

Let's expand the load-bearing steps.

### 3.2 Step 2 — Prompt assembly and chat templates

You rarely send raw text. Chat models expect a **structured prompt** with roles (`system`, `user`, `assistant`) wrapped in special control tokens defined by the model's **chat template**. For example, Llama 3 uses tokens like `<|begin_of_text|>`, `<|start_header_id|>`, `<|end_header_id|>`, `<|eot_id|>`. These **special tokens count against the context window** even though you never typed them.

So your effective token count = system prompt + role-formatting tokens + each message's content + each message's wrapper tokens + (for tool use) tool/function schemas + the assistant's reply. **Tool/function definitions are tokens too** — a large JSON schema for tools can silently consume thousands of tokens on every single call.

> **Implication:** "My user message is 50 tokens" is rarely the billed input. The system prompt, conversation history, tool schemas, and formatting tokens are usually the bulk. Always measure the *assembled* prompt.

### 3.3 Step 4 — Prefill (processing the input)

The **prefill** phase runs the input tokens through the network. Critically, **all input tokens are processed in parallel** (one big matrix multiply per layer), because the model already knows every input token — there's no need to generate them.

During prefill the model computes, for every token and every layer, two tensors per attention head: the **Key (K)** and **Value (V)** vectors. These are stored in the **KV cache**.

> **KV cache:** a per-request memory buffer holding the Key and Value vectors for every token seen so far, at every layer and head. Its purpose is to avoid recomputing attention over old tokens during generation. Its size grows **linearly** with the number of tokens in context: roughly `2 (K and V) × n_layers × n_heads × head_dim × n_tokens × bytes_per_value`. For a large model with a long context, the KV cache can be **gigabytes per request**, which is the real memory limiter for long context and high concurrency on a serving box.

**Cost/latency characteristics of prefill:**
- Compute is high (it's the n² attention plus big matmuls) but **parallelized**, so wall-clock prefill latency is dominated by the largest sequence and the hardware, not by token order.
- This is why **input tokens are cheaper per token than output tokens** in pricing: they're processed in one parallel sweep.
- Prefill latency is what you experience as **Time To First Token (TTFT)**. A huge prompt → long TTFT even before any output appears.

### 3.4 Step 5 — Decode (autoregressive generation)

Generation is **autoregressive**: the model produces one token, appends it to the context, and feeds the whole thing back to produce the next token. Each step:

1. Compute the next-token probability distribution (a softmax over the entire vocabulary — for o200k that's a ~200k-way distribution).
2. **Sample** a token (greedy = argmax; or temperature/top-p sampling for diversity).
3. Append the new token's K/V to the KV cache.
4. Repeat.

Because each output token requires a **full forward pass** (though attention reuses the KV cache, so each step is "cheap" relative to recomputing everything), output is **serial and slow**. This is why:
- **Output tokens cost more** (you pay for serial GPU time, and they're the scarce throughput resource).
- **Latency scales with output length**, roughly linearly. A 2,000-token answer takes ~10× longer to stream than a 200-token answer.
- Throughput is often quoted as **tokens/second** (output).

> **Speculative decoding** (deep-internals teaser): a small "draft" model proposes several tokens, the big model verifies them in one parallel pass, accepting the prefix that matches. This speeds up decode without changing outputs. It doesn't change token *counts* or billing but changes latency.

### 3.5 Step 6 — Stopping

Generation stops when:
- The model emits the **End-Of-Sequence (EOS)** / end-of-turn token (e.g. `<|eot_id|>`), meaning "I'm done."
- It hits the caller-specified **`max_tokens`** (output cap). The response is truncated; `finish_reason: "length"`.
- It hits a caller-specified **stop sequence** (e.g. stop at `"\n\n"` or `"</answer>"`).

> **Cost trap:** if you set `max_tokens` very high and the model rambles, you pay for every output token. If you set it too low, you get truncated JSON/answers. Set it to the smallest value that comfortably fits your expected output.

### 3.6 Why attention forces O(n²) — the core internal reason for the window

In self-attention, each of the `n` tokens computes an attention score against each of the `n` tokens (the QKᵀ matrix is `n × n`). So:
- **Compute** for the attention scores is **O(n²·d)**.
- **Memory** for the attention matrix during compute is **O(n²)** (mitigated by FlashAttention's tiling, but the fundamental scaling stands), and the **KV cache** is **O(n)**.

Double your context and you roughly **quadruple** the attention compute. This is *the* reason context windows are finite, why long context is expensive, and why "just make the window bigger" is not free.

> **FlashAttention:** a GPU kernel that computes exact attention without materializing the full n² matrix in slow memory, by tiling and recomputing on the fly. It dramatically reduces memory and speeds things up, enabling longer contexts — but it does **not** change the O(n²) compute; it changes the constant factors and memory profile.

> **Sparse / linear attention, sliding-window attention (SWA):** architectural alternatives that restrict which tokens attend to which (e.g. Mistral's sliding window only lets each token attend to the last W tokens), trading exactness for sub-quadratic scaling. These enable longer contexts cheaply but can hurt long-range recall.

### 3.7 The state machine of a conversation

For multi-turn chat, your application maintains conversation state. The token-relevant state transitions:

```
        ┌─────────────────────────────────────────────┐
        │  ACCUMULATING (history grows each turn)       │
        └─────────────────────────────────────────────┘
            │ token_count approaching window limit
            ▼
        ┌─────────────────────────────────────────────┐
        │  MANAGE  (truncate / summarize / evict)       │
        └─────────────────────────────────────────────┘
            │ after management, token_count < limit
            ▼
        back to ACCUMULATING
```

Every turn you must: assemble prompt → count tokens → if over budget, run a management strategy (§3.8) → send. The naive implementation (append forever) inevitably hits `context_length_exceeded` and, before that, becomes expensive and slow because **you re-send the entire history every turn** (LLM APIs are stateless; there's no server-side memory unless the provider offers a stateful API).

### 3.8 Context management strategies (the internal workflows)

When history exceeds budget, you choose among:

1. **Truncation (drop oldest):** keep the system prompt + the most recent N messages (or most recent N tokens). Simple, fast, lossy — the model forgets early context. Often combined with always-pinning the system prompt and the first user message.
2. **Sliding window:** a moving window of the last W tokens/messages. Same as truncation but framed as a fixed-size window; common in agents.
3. **Summarization (compaction):** when history grows large, call the LLM (or a cheaper model) to **summarize** older turns into a compact note, replace those turns with the summary, and continue. Preserves gist at the cost of detail and an extra LLM call. This is what "auto-compact" features do.
4. **Retrieval (RAG):** don't keep everything in-context. Store history/documents in a vector database; at each turn, **retrieve only the most relevant chunks** for the current query and inject those. Keeps the window small regardless of total corpus size.
5. **Hierarchical / recursive summarization:** summaries of summaries — a tree that lets you keep a tiny rolling summary plus the ability to drill back into detail via retrieval.

> **Vector database / embeddings (for RAG):** text is converted to **embedding vectors** (dense float vectors capturing meaning) by an embedding model; a **vector database** (e.g. pgvector, Pinecone, Milvus, FAISS) indexes them for **nearest-neighbor search**, so "find chunks similar to this query" is fast. This is how RAG decides what tokens earn a seat in the window. (Embeddings here are a *separate* model from the chat model and are billed separately, per token, but typically far cheaper.)

### 3.9 Prompt caching — internal mechanics

Because prefill recomputes the KV cache for the *entire* prompt every call, and because many prompts share a large **common prefix** (a big system prompt, a fixed tool schema, a long document you ask many questions about), providers offer **prompt caching**: the provider stores the computed KV cache for a prefix and reuses it on subsequent calls with the same prefix.

Internally:
- The provider hashes the **exact prefix tokens**. On a cache hit, it **skips prefill** for those tokens, loading the stored KV cache instead.
- This cuts **TTFT/latency** (no recompute) and **cost** (cached input tokens are billed at a steep discount or free).
- The cache is **prefix-based**: it only helps the longest matching prefix. Anything after the first differing token must be recomputed. So **put stable content first** (system prompt, tools, long documents) and variable content (the user's latest question) last.

> **Vendor specifics (verify current docs):**
> - **Anthropic** uses *explicit* caching: you mark cache breakpoints with `cache_control` on content blocks. Cache writes cost a premium (~1.25× input rate for 5-min TTL, more for 1-hour); cache reads are deeply discounted (~0.1× input rate, i.e. ~90% off). Default TTL ~5 minutes, refreshed on use; a longer 1-hour TTL is available. Minimum cacheable prefix is ~1,024 tokens (model-dependent).
> - **OpenAI** does *automatic* prefix caching for prompts over ~1,024 tokens, in 128-token increments, with cached input tokens discounted (commonly ~50% off, model-dependent). No code change required; just keep the prefix stable and put dynamic content last.
> - **Google Gemini** offers both implicit and explicit ("context caching") with its own pricing and minimum-token thresholds.
> These discounts and thresholds are version- and vendor-specific and change often.

---

## 4. The complete toolkit

This section enumerates the concrete APIs, libraries, parameters, and commands you actually use. Defaults noted where stable; flagged when version-specific.

### 4.1 Token-counting libraries and tools

| Tool | Ecosystem | What it does | Notes |
|---|---|---|---|
| `tiktoken` | Python (OpenAI, open-source) | Exact BPE tokenizer for OpenAI models (`cl100k_base`, `o200k_base`, etc.) | The canonical local counter for OpenAI. Fast (Rust core). |
| `tiktoken` ports | Java: `jtokkit`; JS: `js-tiktoken`/`gpt-tokenizer`; Rust: `tiktoken-rs` | Same vocabularies, other languages | **`jtokkit`** is the standard JVM choice. |
| Anthropic `count_tokens` API | HTTP / SDK | Exact Claude token count for a given messages payload | No local lib; must call the API. Returns `input_tokens`. |
| `transformers` `AutoTokenizer` | Python (HuggingFace) | Loads any open model's tokenizer by name | Use `apply_chat_template` to count *with* role/formatting tokens. |
| SentencePiece | C++/Python/Java | Tokenizer engine for Llama2/Mistral/T5-style models | Loads a `.model` file. |
| OpenAI Tokenizer (web) | Browser | Visual token inspector | Great for intuition; not for automation. |
| API response `usage` | All providers | Returns *actual* `prompt_tokens`/`input_tokens`, `completion_tokens`/`output_tokens`, totals, and cache hit counts | **Ground truth for billing.** Always log this. |

### 4.2 Key tokenizer/encoding identifiers (OpenAI / tiktoken)

| Encoding | Used by | Vocab |
|---|---|---|
| `o200k_base` | GPT-4o, GPT-4.1, o1/o3/o4 families | ~200k |
| `cl100k_base` | GPT-4 (legacy), GPT-3.5-turbo, `text-embedding-3-*` | ~100k |
| `p50k_base` | older Codex / `text-davinci-002/003` | ~50k |
| `r50k_base` (`gpt2`) | GPT-2 / older GPT-3 | ~50k |

In `tiktoken`, prefer `tiktoken.encoding_for_model("gpt-4o")` so you always get the right encoding for a model name (it maps model → encoding for you).

### 4.3 Request parameters that affect tokens / window / cost

| Parameter (typical name) | Purpose | Default / notes |
|---|---|---|
| `max_tokens` / `max_completion_tokens` / `max_output_tokens` | Hard cap on **output** tokens | Provider-specific defaults; **always set it deliberately**. If `prompt + max_tokens > window` → error. |
| `stop` / `stop_sequences` | Strings that halt generation | Helps avoid paying for runaway output. |
| `temperature`, `top_p`, `top_k` | Sampling controls | Don't change token *count* directly but affect output length/verbosity. |
| `stream` | Stream tokens as generated | Improves perceived latency; doesn't change counts. |
| `n` | Number of completions | Multiplies **output** token cost by `n`. |
| `cache_control` (Anthropic) | Marks cacheable prefix breakpoints | Enables explicit prompt caching. |
| `tools` / `functions` | Tool schemas | **Counts as input tokens** every call; can be large. |
| `response_format` (JSON mode / structured outputs) | Constrains output format | Can slightly change token usage; structured outputs may add overhead. |
| `logit_bias` | Bias specific token IDs | Operates at the token level; used to ban/encourage tokens. |
| `reasoning_effort` / reasoning tokens (o-series, "thinking" models) | Internal chain-of-thought | **Reasoning tokens are billed as output** and consume window even though hidden. Major cost factor. |

### 4.4 Pricing model (how billing works)

Billing is **per token**, quoted per **million tokens (MTok)** or per **1K tokens**, with **separate input and output rates** (output is typically 3–5× the input rate). Additional line items:

| Billing line item | Typical relationship | Notes |
|---|---|---|
| Input (prompt) tokens | base rate | Includes system prompt, history, tool schemas, RAG context, formatting tokens. |
| Output (completion) tokens | ~3–5× input rate | Serial generation; the expensive part per token. |
| **Cached input (read)** | deep discount (~0.1–0.5× input) | Only on cache hits with stable prefix. |
| **Cache write** (Anthropic) | premium (~1.25× input, more for long TTL) | One-time per cached segment until it expires. |
| Reasoning/thinking tokens | billed as **output** | Hidden but charged; can dwarf visible output. |
| Embeddings | separate, cheap per-token rate | Input-only (no output). |
| Batch API | ~50% discount | For async, non-latency-sensitive jobs (provider-specific). |
| Image/audio inputs | converted to "tokens" by a formula | E.g. images priced by tile count → equivalent tokens; vendor-specific. |

> **Worked cost formula:**
> `cost = (input_tokens − cached_read_tokens)·R_in + cached_read_tokens·R_cache_read + cache_write_tokens·R_cache_write + output_tokens·R_out`
> Always compute against the **actual `usage` block**, not your estimate.

### 4.5 CLI / inspection commands

```bash
# Count OpenAI tokens locally (Python, exact)
python -c "import tiktoken; e=tiktoken.encoding_for_model('gpt-4o'); print(len(e.encode(open('prompt.txt').read())))"

# HuggingFace tokenizer count (open models), WITH chat template
python - <<'PY'
from transformers import AutoTokenizer
tok = AutoTokenizer.from_pretrained("meta-llama/Llama-3.1-8B-Instruct")
msgs=[{"role":"system","content":"You are helpful."},
      {"role":"user","content":"Explain BPE."}]
ids = tok.apply_chat_template(msgs, add_generation_prompt=True)
print(len(ids))   # includes special/role tokens — the real input count
PY

# Anthropic exact count (no local lib) — count_tokens endpoint
curl https://api.anthropic.com/v1/messages/count_tokens \
  -H "x-api-key: $ANTHROPIC_API_KEY" -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{"model":"claude-sonnet-4","messages":[{"role":"user","content":"Hello"}]}'
```

---

## 5. Code examples by use case

These are deliberately across **different real scenarios**, Java-first (the reader's ecosystem), with Python where the tooling is Python-only. Comments explain the non-obvious lines.

### 5.1 Use case: count tokens exactly on the JVM with `jtokkit`

```java
// Maven: com.knuddels:jtokkit (a Java port of tiktoken with OpenAI vocabularies)
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.*;

public class TokenCounter {
    // Build the registry once; it's thread-safe and cheap to reuse.
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    public static int countForModel(String text, ModelType model) {
        // getEncodingForModel maps a model (e.g. GPT_4O) to its encoding (o200k_base).
        Encoding enc = REGISTRY.getEncodingForModel(model)
                .orElseThrow(() -> new IllegalArgumentException("Unknown model"));
        return enc.countTokens(text); // exact for OpenAI models
    }

    public static void main(String[] args) {
        String s = "Tokenization turns text into integer tokens.";
        // GPT-4o uses o200k_base; GPT-3.5/legacy GPT-4 use cl100k_base.
        System.out.println(countForModel(s, ModelType.GPT_4O));
    }
}
```

Why this matters: lets you **pre-flight** prompts (reject or trim before sending), estimate cost, and size RAG chunks — all without a network call. (Exact only for OpenAI models; for Claude use the API.)

### 5.2 Use case: guard against `context_length_exceeded` before calling the API

```java
public final class ContextGuard {
    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();

    /** Returns true if the request fits, leaving room for the desired output. */
    public boolean fits(String assembledPrompt, ModelType model,
                        int contextWindow, int desiredMaxOutput) {
        int inputTokens = registry.getEncodingForModel(model)
                .orElseThrow().countTokens(assembledPrompt);
        // Reserve output budget: input + output must fit the single shared window.
        // Add a safety margin for special/role tokens jtokkit may not model exactly.
        int safetyMargin = 64;
        return inputTokens + desiredMaxOutput + safetyMargin <= contextWindow;
    }
}
// Usage: if (!guard.fits(prompt, GPT_4O, 128_000, 4_000)) { trimOrSummarize(); }
```

This converts an unpredictable runtime `400` into a controlled, testable branch.

### 5.3 Use case: sliding-window truncation that always keeps the system prompt

```java
import java.util.*;

record Msg(String role, String content) {}

class SlidingWindowTrimmer {
    private final EncodingRegistry reg = Encodings.newDefaultEncodingRegistry();
    private final Encoding enc;
    SlidingWindowTrimmer(ModelType model){ enc = reg.getEncodingForModel(model).orElseThrow(); }

    private int tokens(Msg m){
        // ~4 tokens per message overhead for role/formatting wrappers (cl100k heuristic).
        return enc.countTokens(m.content()) + 4;
    }

    /** Keep the (mandatory) system message + most recent messages within budget. */
    List<Msg> trim(List<Msg> history, int inputBudget){
        Msg system = history.get(0);          // assume index 0 is the system prompt
        int used = tokens(system);
        Deque<Msg> kept = new ArrayDeque<>();
        // Walk from newest to oldest, keeping as much recent context as fits.
        for (int i = history.size() - 1; i >= 1; i--) {
            int t = tokens(history.get(i));
            if (used + t > inputBudget) break; // stop once we'd overflow
            kept.addFirst(history.get(i));
            used += t;
        }
        List<Msg> out = new ArrayList<>();
        out.add(system);
        out.addAll(kept);
        return out;
    }
}
```

Trade-off baked in: the model **forgets the middle/oldest** turns. Acceptable for stateless Q&A; bad for tasks needing early context (use summarization or RAG instead).

### 5.4 Use case: summarization-based compaction (pseudo-flow)

```java
// When history tokens exceed a threshold, compress the OLDER half into a summary.
class Compactor {
    final LlmClient llm; final SlidingWindowTrimmer trimmer; /* ... */

    List<Msg> compactIfNeeded(List<Msg> history, int threshold, int targetBudget) {
        int total = history.stream().mapToInt(this::approxTokens).sum();
        if (total <= threshold) return history;

        int split = history.size() / 2;
        List<Msg> older = history.subList(1, split);          // skip system prompt
        // Cheap model + tight max_tokens keeps the summary itself small.
        String summary = llm.complete(
            "Summarize the following conversation faithfully and compactly, "
          + "preserving facts, decisions, names, and open questions:\n" + render(older),
            /*maxTokens*/ 400, /*model*/ "cheap-fast-model");

        List<Msg> compacted = new ArrayList<>();
        compacted.add(history.get(0));                         // keep system prompt
        compacted.add(new Msg("system", "Summary of earlier conversation:\n" + summary));
        compacted.addAll(history.subList(split, history.size())); // keep recent turns verbatim
        return compacted; // now well under budget; detail is lossy but gist preserved
    }
    int approxTokens(Msg m){ /* jtokkit count + overhead */ return 0; }
    String render(List<Msg> ms){ /* role: content lines */ return ""; }
}
```

### 5.5 Use case: RAG — retrieve only what fits, ordered to beat "lost in the middle"

```java
// Pseudocode: embed query, fetch top-K chunks, pack into a token budget,
// then ORDER so the most important chunk is NOT buried in the middle.
class RagPacker {
    final EmbeddingClient embedder; final VectorStore store; final Encoding enc;

    String buildContext(String query, int contextTokenBudget) {
        float[] qv = embedder.embed(query);                 // query → vector
        List<Chunk> hits = store.search(qv, /*topK*/ 20);   // nearest neighbors, ranked

        List<Chunk> packed = new ArrayList<>();
        int used = 0;
        for (Chunk c : hits) {                              // greedily pack by relevance
            int t = enc.countTokens(c.text());
            if (used + t > contextTokenBudget) continue;    // skip oversize, keep filling
            packed.add(c);
            used += t;
        }
        // Beat "lost in the middle": place strongest chunks at the START and END,
        // weakest in the MIDDLE (where models attend least). 'packed' is rank-ordered.
        List<Chunk> ordered = new ArrayList<>();
        Deque<Chunk> dq = new ArrayDeque<>(packed);
        boolean front = true;
        // Interleave from the ends inward so #1 and #2 land at the edges.
        while (!dq.isEmpty()) {
            if (front) ordered.add(0, dq.pollFirst()); else ordered.add(dq.pollFirst());
            front = !front;
        }
        StringBuilder sb = new StringBuilder();
        for (Chunk c : ordered) sb.append("[Source ").append(c.id()).append("]\n")
                                  .append(c.text()).append("\n\n");
        return sb.toString();
    }
}
record Chunk(String id, String text) {}
```

### 5.6 Use case: prompt caching with Anthropic (cut cost/latency on a fixed prefix)

```bash
# A large, STABLE system prompt / document is marked cacheable with cache_control.
# Subsequent calls reusing the same prefix get cache READ pricing (~90% off input).
curl https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{
    "model": "claude-sonnet-4",
    "max_tokens": 512,
    "system": [
      {
        "type": "text",
        "text": "<<a very long, fixed style guide / knowledge base of 50k tokens>>",
        "cache_control": { "type": "ephemeral" }   /* mark prefix as cacheable */
      }
    ],
    "messages": [
      { "role": "user", "content": "Question that VARIES every call goes LAST." }
    ]
  }'
```

Design rule encoded here: **stable content first (and cached), variable content last.** With OpenAI you get this automatically (no `cache_control`) by simply keeping the prefix byte-identical and putting the dynamic part last.

### 5.7 Use case: read the ground-truth `usage` to compute real cost (Java, OpenAI-style JSON)

```java
// After any call, the response carries the AUTHORITATIVE token counts.
record Usage(int prompt_tokens, int completion_tokens, int total_tokens,
             PromptDetails prompt_tokens_details) {}
record PromptDetails(int cached_tokens) {}   // cache hits, when present

double dollars(Usage u, double rInPerM, double rOutPerM, double rCachedPerM) {
    int cached = u.prompt_tokens_details() == null ? 0
                 : u.prompt_tokens_details().cached_tokens();
    int freshInput = u.prompt_tokens() - cached;     // non-cached input billed at full rate
    return  freshInput        / 1_000_000.0 * rInPerM
          + cached            / 1_000_000.0 * rCachedPerM
          + u.completion_tokens() / 1_000_000.0 * rOutPerM;
}
// Log this per request. Aggregate to catch cost regressions before the invoice does.
```

### 5.8 Use case: exact local counting for OpenAI chat messages (Python, including role overhead)

```python
import tiktoken

def count_chat_tokens(messages, model="gpt-4o"):
    enc = tiktoken.encoding_for_model(model)
    # Per-message and per-name overheads approximate the chat template's special tokens.
    # These constants are an OpenAI-documented approximation and can shift by model.
    tokens_per_message, tokens_per_name = 3, 1
    total = 0
    for m in messages:
        total += tokens_per_message
        for key, value in m.items():
            total += len(enc.encode(value))
            if key == "name":
                total += tokens_per_name
    total += 3  # priming tokens for the assistant reply
    return total

msgs = [{"role": "system", "content": "You are concise."},
        {"role": "user", "content": "Define a context window."}]
print(count_chat_tokens(msgs))  # approximate but close to billed prompt_tokens
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency

- **Separate the two latencies.** TTFT is dominated by **prefill** (prompt size). Total time is TTFT + (output_tokens / tokens-per-second). To cut TTFT: shrink the prompt and use **prompt caching**. To cut total time: shrink the output (lower `max_tokens`, ask for concise answers), and **stream**.
- **Output length is the latency lever you control most cheaply.** "Answer in ≤3 sentences" or strict JSON schemas reduce both latency and cost.
- **Batch** non-interactive work (provider Batch APIs, ~50% cheaper, higher latency).
- **Avoid re-tokenizing huge prompts repeatedly** in your own code; cache local token counts keyed by content hash.

### 6.2 Correctness & concurrency

- **Token counts are not additive.** `count(a) + count(b) ≠ count(a + b)` because of the leading-space/merge behavior (§2.4). Always count the **assembled** string.
- **Special/role tokens are easy to under-count.** Use the model's chat template (`apply_chat_template`) or add a per-message overhead constant; validate against `usage`.
- **Truncation must never cut a message mid-token** in your *display*, and must preserve required structure (don't drop the system prompt, don't orphan a tool call without its result).
- **Concurrency:** tokenizers (`jtokkit`, `tiktoken`) are thread-safe to use after construction; build the encoding once and share it. KV-cache memory on the *server* side limits concurrent long-context requests — relevant if you self-host.

### 6.3 Memory (self-hosting)

- **KV cache dominates inference memory** for long contexts. Budget `≈ 2 × layers × heads × head_dim × seq_len × bytes` per request. Quantizing the KV cache (e.g. to 8-bit) and using **PagedAttention** (vLLM) drastically improves concurrency.
- **Context length × batch size** is the real capacity constraint, not model weights alone.

### 6.4 Security

- **Prompt injection** rides in on tokens you pasted from untrusted sources (retrieved web pages, user files). Treat all in-context text as potentially adversarial; never let retrieved content silently override the system prompt.
- **Data exfiltration / leakage:** long context means you may stuff sensitive data into prompts. Redact PII before it becomes tokens; remember provider logging/retention policies.
- **Token-boundary smuggling:** attackers can craft inputs that tokenize unexpectedly (homoglyphs, zero-width chars). Normalize/validate untrusted input.
- **Cache poisoning / cross-tenant leakage:** prompt caches are scoped per account/org by providers; if you build your *own* caching layer, key it carefully to avoid serving one user's cached prefix to another.

### 6.5 Cost & observability

- **Log the `usage` block on every call** (input, output, cached, total) plus model and request id. This is your only ground truth.
- **Attribute cost** by feature/tenant via metadata so you can find the prompt that's quietly 30k tokens because someone pasted a PDF into the system prompt.
- **Alert on token regressions** (e.g. average prompt_tokens jumps after a deploy that grew the tool schema).
- **Watch reasoning tokens** on "thinking" models — they're billed as output and invisible in the text response.

### 6.6 Testability

- **Pre-flight token checks** are unit-testable (no network). Test the trimmer/compactor with fixtures at/over the boundary.
- **Golden tests on token counts** catch when a prompt-template change unexpectedly inflates tokens.
- **Mock the `usage` block** to test your cost accounting.

### 6.7 Production hardening checklist

- Always set `max_tokens` deliberately; never leave it unbounded.
- Reserve output budget when validating input fit (input + max_tokens ≤ window).
- Pin and cache stable prefixes; put variable content last.
- Have a degradation path for `context_length_exceeded` (trim → summarize → reject with a clear message).
- Cap retrieval (top-K and token budget) so RAG can't explode the prompt.
- Set `stop` sequences and timeouts to bound runaway generation.

### 6.8 Anti-patterns

- **"Just send the whole document/history."** Quadratic cost, slow, and triggers lost-in-the-middle. Retrieve or summarize.
- **Estimating cost by character/word count.** Off by 25%+ and wildly off for code/CJK. Count tokens.
- **Putting variable data first.** Destroys prompt caching.
- **Ignoring tool-schema tokens.** A 4k-token tool schema on every call is a silent tax.
- **Maxing out the window "just in case."** Bigger context isn't free or even necessarily better (attention dilution).
- **Trusting `length()`/`split(" ")`.** Neither equals tokens.

---

## 7. Advanced topics & deep internals

### 7.1 How context windows got bigger: RoPE scaling, position interpolation, YaRN

Models trained at, say, 8k tokens can be *extended* to 128k+ without full retraining by manipulating positional encodings:
- **Position Interpolation (PI):** linearly rescale position indices so the model "sees" longer sequences as if compressed into its trained range.
- **NTK-aware scaling / YaRN:** smarter, frequency-dependent rescaling of RoPE that preserves high-frequency (local) detail while stretching low-frequency (long-range) components, with light fine-tuning. This is *why* a model can advertise 1M tokens — the architecture didn't change, the positional math and fine-tuning did. The catch: **effective** use of the far context often lags the advertised maximum.

### 7.2 The "lost in the middle" effect (and the prompt-positioning lever)

Empirically (Liu et al., 2023, "Lost in the Middle"), models retrieve information best when it's at the **beginning or end** of the context and **worst when it's in the middle** — performance often follows a **U-shaped curve**. Causes include positional biases, attention sinks, and training-data ordering effects.

**Engineering responses:**
- Put the **most important instructions and the key evidence at the start and end** of the prompt.
- In RAG, order retrieved chunks so top-ranked ones land at the **edges** (see §5.5), or **rerank** so only a few highly-relevant chunks are included rather than many mediocre ones.
- Prefer **fewer, more relevant** chunks over "stuff the window" — more context can *dilute* attention and lower accuracy.

> **Attention sink:** models tend to dump excess attention onto the very first token(s) (often the BOS token) regardless of content. Streaming-LLM techniques exploit this by always keeping the first few tokens in the window when sliding.

### 7.3 Attention dilution & the needle-in-a-haystack benchmark

- **Needle in a Haystack (NIAH):** a test that hides a fact ("the needle") at varying depths in a long context and asks the model to recall it. Modern long-context models score near-perfect on simple single-needle tests but degrade on **multi-needle**, **reasoning-over-many-facts**, and **distractor-heavy** variants. A high "1M context" number does not guarantee high *reasoning* quality across that span.
- **Attention dilution:** with more tokens, attention mass spreads thinner; signal can be drowned by distractors. This is why concise, relevant prompts often outperform maximal ones — a counterintuitive but well-documented effect.

### 7.4 Tokenizer pathologies

- **Glitch / "unspeakable" tokens:** rare tokens (e.g. `" SolidGoldMagikarp"`) that appeared in tokenizer-training data but barely in model-training data; the model behaves erratically when they appear. A reminder that tokenizer and model training are separate.
- **Digit tokenization** historically hurt arithmetic; some tokenizers now force single-digit tokens to improve math. This changes token counts for numbers.
- **Whitespace/indentation:** cl100k added merges for runs of spaces (good for code); different tokenizers count the same code very differently.
- **Trailing-space sensitivity:** a prompt ending in a space vs not can change the first generated token and even quality.

### 7.5 Reasoning tokens / "thinking" budgets

o-series and Claude "extended thinking" models generate hidden **reasoning tokens** before the visible answer. These:
- **Count against the context window** and are **billed as output**.
- Are controlled by knobs like `reasoning_effort` or a thinking-token budget.
- Can be the dominant cost on hard problems (thousands of hidden tokens for a short answer). **Budget and monitor them explicitly.**

### 7.6 Multimodal "tokens"

Images, audio, and video are converted into token-equivalents:
- **Images** are split into patches/tiles; cost is computed by a tile-count formula → a token count (vendor-specific; e.g. base tokens + per-tile tokens at a given resolution).
- **Audio** is chunked into frames → tokens.
This means **a single high-res image can cost hundreds to thousands of tokens** and consume real window space. Always check the provider's image-token formula.

### 7.7 Prefix-cache internals & gotchas

- Caching is **prefix-exact**: a single differing byte early in the prompt invalidates the whole downstream cache.
- **Ordering content by stability** (system → tools → long docs → few-shot examples → dynamic user input) maximizes cache hits.
- **TTL and eviction**: caches expire (Anthropic ~5 min default, refreshed on hit; OpenAI minutes-scale). Bursty traffic benefits; sparse traffic may keep missing.
- **Cache-write premium** (Anthropic) means caching a prefix you only use once is a *loss*; it pays off with reuse.

### 7.8 Beyond quadratic: long-context architectures

- **Sliding-window attention (SWA)** (Mistral): each token attends to the last W tokens; stacking layers gives an effective receptive field of `W × layers`. Sub-quadratic, but weaker exact long-range recall.
- **Mixture-of-Experts (MoE):** orthogonal to context length (it cuts *active* parameters per token), but relevant to cost/throughput at long contexts.
- **State-space models (Mamba) / linear attention:** O(n) alternatives gaining traction for very long sequences, trading some recall fidelity.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Context-management strategy comparison

| Strategy | Preserves detail | Cost per turn | Latency | Implementation complexity | Best when |
|---|---|---|---|---|---|
| Truncation / sliding window | Low (drops old) | Low | Low | Trivial | Short-memory chat; recent context is all that matters |
| Summarization / compaction | Medium (gist kept) | Extra LLM call when compacting | Spikes at compaction | Medium | Long conversations needing the gist of history |
| RAG (retrieval) | High (for retrievable facts) | Embed + search + small context | Low-medium | High (vector DB, chunking) | Large/unbounded knowledge bases; many docs |
| Full context (no management) | Highest (until limit) | Highest (quadratic) | Highest TTFT | Trivial | Short total content well under window |
| Hierarchical summary + RAG | High | Medium | Medium | Highest | Long-running agents with deep history |

### 8.2 Bigger context window — use when / avoid when

**Use a large window when:**
- The task genuinely needs cross-document synthesis you can't pre-retrieve (e.g. "compare these 30 contracts").
- You have a stable large prefix you can **cache** (turns big cost into small).
- Latency is not critical and answers depend on dispersed details.

**Avoid / prefer RAG when:**
- The relevant info is a small fraction of a large corpus (retrieve it).
- Cost/latency matter (quadratic prefill).
- The "lost in the middle"/dilution effect is hurting accuracy.
- The corpus changes often (re-stuffing a huge prompt vs. updating a vector index).

### 8.3 Counting approach: local vs. API

| Approach | Exact? | Network? | Use when |
|---|---|---|---|
| `tiktoken` / `jtokkit` | Exact for OpenAI | No | Pre-flight, cost estimation, OpenAI models |
| HF `apply_chat_template` | Exact for that open model | No | Self-hosted/open models |
| Provider `count_tokens` API | Exact | Yes | Claude (no local lib); billing-critical checks |
| `usage` from response | Exact (ground truth) | N/A (post-hoc) | Accounting, monitoring |
| ≈4 chars/token heuristic | No (±25%+) | No | Quick sanity checks only |

### 8.4 `max_tokens` sizing rule

Set `max_tokens` to roughly **p95 of expected output length + small buffer**, never the model max "to be safe." Too high wastes the window and invites rambling; too low truncates. Validate `input + max_tokens ≤ window` before sending.

---

## 9. Failure modes & debugging

### 9.1 `context_length_exceeded` / "prompt is too long"

- **Symptom:** HTTP 400 before any output; message states prompt + max_tokens exceed the window.
- **Diagnose:** log the **assembled** prompt token count (jtokkit/tiktoken) and `max_tokens`; compare to the model's window. The culprit is usually unbounded history, a giant pasted document, or a bloated tool schema.
- **Fix:** trim/summarize history, lower `max_tokens`, chunk via RAG, or move to a larger-window model. Add the §5.2 guard so this becomes a handled branch, not a crash.

### 9.2 Truncated / cut-off output (`finish_reason: "length"`)

- **Symptom:** JSON missing its closing brace; answer stops mid-sentence.
- **Diagnose:** check `finish_reason`. It means you hit `max_tokens`.
- **Fix:** raise `max_tokens` (within window), ask for more concise output, or implement continuation ("continue from where you stopped"). For structured output, validate and re-request.

### 9.3 Cost blowups

- **Symptom:** invoice 5–10× expectation.
- **Diagnose:** aggregate logged `usage` by feature/model; look for (a) huge prompt_tokens (someone stuffed a document/system prompt), (b) high output (no `max_tokens`, verbose model), (c) reasoning tokens on thinking models, (d) `n>1`, (e) cache misses on what should be cached.
- **Fix:** the corresponding lever — trim prompt, cap output, control reasoning budget, fix cache prefix stability.

### 9.4 "It forgot what I told it earlier"

- **Symptom:** in a long chat/agent, early instructions or facts are ignored.
- **Diagnose:** likely truncation dropped them, or "lost in the middle" buried them.
- **Fix:** pin critical instructions in the (cached) system prompt; reposition key facts to the start/end; switch to summarization/RAG; reduce distractor context.

### 9.5 Long context, low quality (NIAH-style misses)

- **Symptom:** model can't find/use a fact you know is in the 100k-token context.
- **Diagnose:** run a needle-in-haystack probe at various depths; check whether the fact is mid-context.
- **Fix:** rerank/retrieve to fewer, edge-positioned chunks; don't rely on the advertised max for hard reasoning over the full span.

### 9.6 Token-count mismatch (estimate vs. billed)

- **Symptom:** your estimate disagrees with `usage`.
- **Diagnose:** you forgot role/special tokens, tool schemas, or counted parts separately (non-additivity), or used the wrong encoding (cl100k vs o200k).
- **Fix:** count the assembled prompt with the correct encoding/chat template; reconcile against `usage` in tests.

### 9.7 Multilingual/code prompts overflow unexpectedly

- **Symptom:** a prompt that "looks short" overflows or costs more (CJK, code, lots of numbers).
- **Diagnose:** measure token density; CJK and code are far denser than the 4-chars/token rule.
- **Fix:** size budgets per content type; prefer o200k-class models for multilingual efficiency; chunk smaller for dense content.

### 9.8 Prompt cache "not working"

- **Symptom:** no cost reduction despite caching.
- **Diagnose:** check `usage` for cached-token counts; verify the prefix is byte-identical and ≥ minimum size; verify dynamic content is **last**; check TTL (sparse traffic expires the cache); ensure breakpoints (Anthropic `cache_control`) are set on the right blocks.
- **Fix:** stabilize and reorder the prompt; raise reuse frequency; use longer TTL where offered.

### 9.9 Real-world incident patterns

- **The exploding system prompt:** a team appends a growing few-shot library to the system prompt; months later every call carries 20k tokens. Fix: move examples to retrieval; cache the stable core.
- **The pasted PDF:** a support tool lets users paste tickets; someone pastes a 200-page manual; prompts overflow and costs spike. Fix: token guard + truncation/RAG + size limits on user input.
- **The chatty agent loop:** an agent re-sends full history every step; by step 30 each call is 100k tokens and slow. Fix: sliding window + periodic compaction + tool-result trimming.
- **The reasoning-token surprise:** switching to a "thinking" model 5× the bill with no visible output change. Fix: set reasoning budgets; monitor reasoning tokens.

---

## 10. Interview drill

**Q1. What is a token, and roughly how does text map to tokens?**
*Model answer:* A token is a subword unit from a fixed vocabulary, produced by a (usually byte-level BPE) tokenizer that maps text↔integer IDs. English prose averages ≈4 characters/token (~0.75 words/token), but it varies a lot: code and numbers are denser (more tokens), and non-English/CJK can be 2–4× denser. Token count is its own metric — not characters, words, or bytes.
- *Follow-up: Why subwords instead of words or characters?* Words → unbounded vocabulary and poor rare-word handling; characters → very long sequences and quadratic attention blowup. Subwords balance vocabulary size against sequence length, and byte-level fallback guarantees any input is representable.
- *Follow-up: Why isn't count(a)+count(b)=count(a+b)?* Leading spaces are part of tokens and merges happen across the boundary; concatenation can merge or re-split, so counts aren't additive. Always count the assembled string.
- *Follow-up: How do you count exactly?* `tiktoken`/`jtokkit` for OpenAI; HF `apply_chat_template` for open models; Anthropic's `count_tokens` API for Claude; and the response `usage` block is ground truth.

**Q2. What is a context window and why is it a hard constraint?**
*Model answer:* It's the max tokens the model can attend to in one forward pass. It's hard because positional encodings, KV-cache buffers, and trained behavior assume `n ≤ window`; exceed it and you get a 400. Crucially, **input and output share** the window.
- *Follow-up: Why finite at all?* Self-attention is O(n²) in compute and the KV cache is O(n) in memory; unbounded `n` is infeasible.
- *Follow-up: What happens if prompt + max_tokens > window?* Error before generation; you must reserve output budget when validating.
- *Follow-up: Is max output always equal to the window?* No — some models cap output well below the context window; check both numbers.

**Q3. Explain BPE step by step.**
*Model answer:* Training: start from base bytes/chars, count adjacent pairs over a corpus, repeatedly merge the most frequent pair into a new token (recording ordered merge rules) until the target vocab size. Inference: pre-tokenize with a regex, then within each chunk greedily apply merge rules in priority order until none apply.
- *Follow-up: BPE vs WordPiece vs Unigram?* WordPiece chooses merges by likelihood gain; Unigram starts large and prunes probabilistically; BPE uses frequency. Generative LLMs mostly use (byte-level) BPE.
- *Follow-up: What guarantees any input is representable?* The base vocabulary includes all 256 bytes, so byte fallback covers anything.

**Q4. How does LLM pricing work, and why is output more expensive than input?**
*Model answer:* Per token, with separate input and output rates (output ~3–5× input). Input is processed in a parallel **prefill**, so it's cheap per token; output is generated **autoregressively** (one token per forward pass), consuming serial GPU time, so it's pricier and slower. Cached input is deeply discounted.
- *Follow-up: What else counts as input tokens?* System prompt, full history, tool/function schemas, RAG context, and special/role formatting tokens.
- *Follow-up: What are reasoning tokens?* Hidden chain-of-thought on "thinking" models, billed as output and counting against the window — often the dominant cost.

**Q5. (Senior signal) When would you choose a 1M-token context vs. RAG?**
*Model answer:* Use the big window when the task needs synthesis across many documents you can't pre-select, or when you have a stable large prefix you can cache (turning huge cost into small). Prefer RAG when the relevant info is a small slice of a large/changing corpus, when cost/latency matter (quadratic prefill), or when lost-in-the-middle/dilution hurts accuracy. Often the answer is *both*: RAG to select, modest window to reason.
- *Follow-up: Why can a bigger window reduce quality?* Attention dilution and the U-shaped lost-in-the-middle effect; more distractors spread attention thinner.
- *Follow-up: How do you make the big-window choice economical?* Prompt caching of the stable prefix, fewer/edge-positioned chunks, batch APIs for async work.

**Q6. (Senior signal) Design context management for a long-running agent.**
*Model answer:* Pin a stable, cached system prompt (instructions + tool schemas). Keep a sliding window of recent turns verbatim. Periodically compact older turns into a summary (cheap model, tight max_tokens). Offload durable facts to a vector store and retrieve per step. Trim tool outputs aggressively. Always pre-flight token counts, reserve output budget, and have a degradation path (trim→summarize→reject).
- *Follow-up: How prevent the agent from forgetting key constraints?* Keep them in the pinned system prompt and re-state critical ones near the end of the prompt (edges beat the middle).
- *Follow-up: How control cost as the session grows?* Compaction cadence, retrieval instead of full history, capping reasoning tokens, monitoring `usage` per step.

**Q7. (Senior signal) Prompt caching: how it works and how to design prompts for it.**
*Model answer:* Providers store the KV cache for a stable **prefix**; on a hit they skip prefill, cutting latency and billing cached tokens at a deep discount. Design: order content by stability — system prompt → tool schemas → long documents → few-shot → dynamic user input last — so the cacheable prefix is maximal and byte-identical across calls. Anthropic is explicit (`cache_control`, write premium, ~5-min TTL); OpenAI is automatic for prompts ≥~1k tokens.
- *Follow-up: When does caching lose money?* When you pay the write premium but rarely reuse the prefix (especially Anthropic's cache-write cost).
- *Follow-up: What invalidates a cache?* Any change before the matching prefix ends, or TTL expiry.

**Q8. Explain "lost in the middle" and how you mitigate it.**
*Model answer:* Models recall information best at the start/end of context and worst in the middle (U-shaped curve). Mitigate by positioning key instructions/evidence at the edges, reranking RAG to include fewer highly-relevant chunks, and avoiding window-stuffing.
- *Follow-up: Why does it happen?* Positional biases, attention sinks on early tokens, and training-data ordering.
- *Follow-up: How do you measure it?* Needle-in-a-haystack probes at varying depths; multi-needle and distractor variants for realism.

**Q9. Why do non-English text and code cost more tokens?**
*Model answer:* The tokenizer's vocabulary is trained mostly on English; under-represented scripts fall back to bytes (CJK chars can be 3 UTF-8 bytes → multiple tokens), and code has dense punctuation/indentation and split identifiers. Same information, more tokens → more cost and faster window exhaustion. o200k improved multilingual efficiency over cl100k but disparities remain.
- *Follow-up: How would you budget for a multilingual product?* Per-language token-density factors; measure with the real tokenizer; choose multilingual-efficient models.

**Q10. What is the KV cache and why does it matter for long context?**
*Model answer:* Per-request storage of Key/Value vectors for all prior tokens at every layer/head, so generation doesn't recompute attention over old tokens. It grows linearly with context length and is the dominant *memory* limiter for long-context, high-concurrency serving. PagedAttention and KV quantization mitigate it.
- *Follow-up: How does it relate to prompt caching?* Prompt caching persists/reuses the prefix's KV cache across requests.
- *Follow-up: Compute vs. memory scaling?* Attention compute is O(n²); KV-cache memory is O(n).

**Q11. How do models advertise 128k–1M context if trained shorter?**
*Model answer:* Positional-encoding extension — position interpolation and NTK/YaRN RoPE scaling, with light fine-tuning — lets a model generalize beyond its training length. But effective reasoning over the full span often lags the advertised max (verify with NIAH-style tests).
- *Follow-up: Tradeoff?* Possible quality degradation at the extremes; cost/latency still quadratic in prefill.

**Q12. Walk through what happens, token-wise, on one API call.**
*Model answer:* Assemble structured prompt (roles + special tokens + tools) → tokenize → validate against window (reserving output) → prefill all input tokens in parallel, building the KV cache (→ TTFT) → autoregressively decode output tokens one at a time, each appended to the KV cache → stop on EOS/max_tokens/stop sequence → detokenize → bill input and output tokens (and any cache reads/writes/reasoning tokens), reported in `usage`.
- *Follow-up: Which phase drives latency vs. cost?* Prefill drives TTFT; decode (output length) drives total latency and per-token cost.

---

## 11. Glossary

- **Attention dilution:** loss of effectiveness as more tokens spread attention mass thinner, drowning signal in distractors.
- **Attention sink:** the tendency to dump excess attention on the first token(s) regardless of content.
- **Autoregressive generation:** producing output one token at a time, feeding each back to predict the next.
- **BOS / EOS:** Beginning/End-Of-Sequence special tokens that frame or terminate generation.
- **BPE (Byte-Pair Encoding):** subword tokenization that iteratively merges the most frequent adjacent token pairs.
- **Byte-level BPE:** BPE whose base vocabulary is the 256 bytes, guaranteeing any input is representable.
- **Chat template:** the model-specific format wrapping roles (system/user/assistant) in special tokens.
- **`cl100k_base` / `o200k_base`:** OpenAI tiktoken encodings (~100k / ~200k vocab) for GPT-3.5/legacy GPT-4 and GPT-4o/4.1 respectively.
- **Context window (context length):** max tokens processable in one forward pass; shared by input and output.
- **`context_length_exceeded`:** error when prompt + requested output exceed the window.
- **Decode:** the autoregressive output-generation phase.
- **Embedding:** a learned dense vector representing a token (in the model) or text (for retrieval).
- **`finish_reason`:** why generation stopped (`stop` = EOS, `length` = hit max_tokens, etc.).
- **FlashAttention:** a memory-efficient exact-attention GPU kernel (changes constants, not O(n²)).
- **KV cache:** per-request store of Key/Value vectors for all prior tokens; O(n) memory.
- **`max_tokens`:** caller-set cap on output tokens.
- **MoE (Mixture of Experts):** architecture activating only some parameters per token (cost/throughput, not context length).
- **Needle in a Haystack (NIAH):** benchmark testing recall of a fact placed at varying depths in long context.
- **Lost in the middle:** the U-shaped tendency to use start/end context better than the middle.
- **Prefill:** the parallel processing of all input tokens, building the KV cache; drives TTFT.
- **Pre-tokenization:** regex splitting of text into chunks before BPE merges run.
- **Prompt caching:** reusing the stored KV cache for a stable prompt prefix to cut latency/cost.
- **RAG (Retrieval-Augmented Generation):** retrieving relevant chunks from a store and injecting them into the prompt instead of holding everything in context.
- **Reasoning tokens:** hidden chain-of-thought tokens on "thinking" models; billed as output, count against the window.
- **RoPE (Rotary Position Embedding):** positional encoding via rotation; basis for long-context extension (PI, NTK, YaRN).
- **Self-attention:** mechanism letting each token attend to all others; O(n²) cost.
- **SentencePiece:** a tokenizer library (BPE or Unigram) treating input as a raw stream with `▁` for spaces.
- **Sliding-window attention (SWA):** each token attends only to the last W tokens; sub-quadratic.
- **Special tokens:** non-text control tokens (roles, BOS/EOS) that still consume window space.
- **Speculative decoding:** speeding decode by drafting with a small model and verifying with the big one.
- **Subword unit:** a token between a character and a word in granularity.
- **tiktoken / jtokkit:** OpenAI's tokenizer library (Python) and its JVM port.
- **Token:** a vocabulary unit; the atomic thing models count, bill, and limit on.
- **Tokenizer:** the deterministic encode/decode function mapping text↔token IDs.
- **TTFT (Time To First Token):** latency until the first output token; dominated by prefill.
- **`usage` block:** API response field with authoritative token counts (and cache hits).
- **UTF-8:** byte encoding of Unicode; basis for byte-level tokenization.
- **Unigram LM:** probabilistic subword tokenization (prunes from a large vocab); used by SentencePiece/T5.
- **Vector database:** index for nearest-neighbor search over embeddings (RAG retrieval).
- **Vocabulary:** the fixed set of tokens a tokenizer/model knows.
- **WordPiece:** BERT's subword algorithm choosing merges by likelihood gain.
- **YaRN / NTK scaling / Position Interpolation:** RoPE-rescaling methods to extend context length.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Numbers to memorize**
- English: **1 token ≈ 4 chars ≈ 0.75 words**; **100 tokens ≈ 75 words**; **~1.33 tokens/word**.
- Code/numbers: denser (~2.5–3.5 chars/token). CJK/non-Latin: **2–4× more tokens**.
- Output rate ≈ **3–5×** input rate. Cached input read ≈ **0.1–0.5×** input (vendor-specific).
- Attention compute **O(n²)**; KV cache memory **O(n)**.
- Common windows: GPT-4o/4.1 **128k–1M**; Claude **200k (–1M)**; Gemini **1M–2M**; Llama 3.1 **128k**. *(Verify; version-specific.)*

**Hard rules**
- Input + output **share** the window. `prompt + max_tokens ≤ window` or you error.
- Bill is **per token**, input vs output separate; tool schemas, system prompt, history, special tokens **all count**.
- Token counts are **not additive**; count the **assembled** prompt with the **correct encoding**.
- Put **stable content first** (cache it), **variable content last**.
- Position key info at **start/end** (beat lost-in-the-middle).
- **Always set `max_tokens`**; log the **`usage`** block.

**Decision rules**
- Small total content → just send it.
- Recent-only memory → sliding window.
- Need gist of long history → summarization/compaction.
- Small slice of big/changing corpus → **RAG**.
- Long-running agent → pinned cached system prompt + sliding window + periodic compaction + retrieval.

**Counting toolkit**
- OpenAI: `tiktoken` / `jtokkit` (exact). Open models: HF `apply_chat_template`. Claude: `count_tokens` API. Ground truth: response `usage`.

### 12.2 Self-test (no answers — for active recall)

1. Encode `"lower"` by hand given merge rules `(l,o)→lo, (lo,w)→low, (e,r)→er`. How many tokens, and why is `" lower"` (with a leading space) potentially different?
2. A model has a 128k window. Your assembled prompt is 124k tokens and you request `max_tokens=8000`. What happens, and how would you have caught it before the API did?
3. Explain, with the underlying compute/memory scaling, why output tokens cost more than input tokens and why doubling context roughly quadruples attention cost.
4. You're building a 1M-token document-QA feature. Justify whether to use the full window or RAG, and describe exactly how you'd order the prompt to maximize prompt-cache hits and minimize the lost-in-the-middle effect.
5. Your monthly LLM bill jumped 6× after a deploy with no traffic change. List the five most likely token-level causes and the single metric you'd check first to localize it.
6. Why might the same paragraph cost ~3× more tokens in Thai than in English, and how would you budget context windows for a multilingual product?
7. Describe the full token-level lifecycle of one chat API call, naming which phase drives TTFT versus total latency.

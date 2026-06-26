# Memory & State in Agentic AI & Agents

> An exhaustive engineering reference for senior backend developers. Reads from first principles through deep internals, with a Java/JVM bias where the topic is language-relevant.

---

## 1. Overview & where it fits

### What it is

An **agent** in the LLM sense is a software system that uses a large language model (LLM) as a reasoning engine inside a loop: it observes some input, decides on an action (call a tool, ask a question, write to a file), executes it, observes the result, and repeats until a goal is reached. The classic loop is **sense → plan → act → observe → repeat**.

The LLM at the center of this loop is, by construction, **stateless**. A model like GPT-4o, Claude, or Llama 3 does not "remember" anything between calls. Each HTTP request to the model API is independent: you send a sequence of tokens (the prompt), the model produces a sequence of output tokens, and then the model forgets everything. The only thing the model "knows" on the next call is whatever you choose to send it again.

**Memory & State** is the entire engineering discipline of deciding *what to send the model again, when, in what form, and from where it is stored*. It is the layer that turns a stateless next-token predictor into something that behaves as if it remembers your name, your past tasks, the contents of a 400-page document, and where it was when it got interrupted three days ago.

> **Term: token.** A token is the atomic unit an LLM reads and writes. It is a chunk of text — roughly ¾ of an English word on average (e.g., "tokenization" might be 2–3 tokens). Models have fixed vocabularies (e.g., ~100k–200k distinct tokens). Everything — your prompt, the conversation history, retrieved documents, the model's reply — is counted in tokens, and you pay per token and are limited by a per-request token budget.

> **Term: context window.** The context window is the maximum number of tokens a model can attend to in a single forward pass — input plus output combined (vendors vary on whether output counts against the same budget). It is a hard physical limit imposed by the model architecture and the GPU memory used during inference. Typical sizes as of 2024–2025: 8k, 32k, 128k, 200k, 1M tokens depending on the model. Anything outside the window simply does not exist to the model on that call.

### The problem it solves

Without a memory/state layer you get a goldfish. Concretely, three problems appear immediately:

1. **Conversational continuity.** If a user says "My name is Pavan" then later asks "What's my name?", the model can only answer if you resend the earlier turn. Multi-turn chat *is* a memory problem solved by re-sending history.
2. **Knowledge beyond the window.** A 90-minute support transcript, a 500-page contract, or three years of a user's purchase history will not fit in any context window. You need to store it externally and fetch only the relevant slices on demand.
3. **Long-running and resumable work.** A research agent that runs for 40 minutes, makes 200 tool calls, and might crash, get rate-limited, or need human approval in the middle must be able to **pause and resume** without losing its place. That requires persisting the agent's *execution state*, not just its chat.

### When you reach for it

- Any chatbot that must remember within a session → short-term memory (resend history).
- Any assistant that must remember a user across sessions ("you told me last week you're vegetarian") → long-term memory (persisted store).
- Any agent working over documents/data larger than the window → retrieval (RAG-style memory).
- Any agent that runs long, can be interrupted, needs human-in-the-loop approval, or must survive a process restart → checkpointed execution state.
- Any agent that should *get better at recurring tasks* (learn a workflow, remember a user's preferences for formatting) → procedural/semantic long-term memory.

### One-paragraph mental model

Think of the LLM as a **CPU with no RAM and no disk** — it can only operate on what is currently in its registers (the context window), and those registers are wiped after every instruction. Memory & State engineering is everything around that CPU: the **registers/L1 cache** are the short-term working memory you fit into the prompt; **RAM** is your fast external store (a session object, a Redis cache) you read/write each turn; **disk** is long-term memory (a database, a vector store) you query selectively; and a **process snapshot / core dump** is the checkpoint that lets a paused agent resume exactly where it stopped. The art is the *memory hierarchy*: keep hot, relevant state in the small expensive window; page everything else out to cheaper, larger stores and page it back in only when needed.

---

## 2. Foundations from first principles

### 2.1 Why a stateless model needs an external memory system

Start from the API contract. A chat completion call looks like this (OpenAI-style; nearly all vendors mirror it):

```jsonc
POST /v1/chat/completions
{
  "model": "gpt-4o",
  "messages": [
    { "role": "system",    "content": "You are a helpful assistant." },
    { "role": "user",       "content": "My name is Pavan." },
    { "role": "assistant",  "content": "Nice to meet you, Pavan." },
    { "role": "user",       "content": "What's my name?" }
  ]
}
```

The crucial fact: **the entire `messages` array is the model's only memory.** There is no server-side session on the model's side that remembers turn 2 when you make turn 4. *You* maintain the array and resend it. The model answers "Pavan" because you re-sent the turn where it was told. Delete that turn and the model cannot answer.

> **Term: role.** Messages are tagged with a role — `system` (instructions/persona, highest authority), `user` (the human), `assistant` (the model's prior outputs), and `tool`/`function` (results returned from tool calls). Roles let the model distinguish "what I was told to do" from "what the user said" from "what I previously said." Modern chat models are fine-tuned to respect this structure.

So "memory" in agents is fundamentally a question of **prompt construction**: every turn, you assemble a prompt from (a) durable instructions, (b) some slice of recent conversation, (c) some retrieved facts/documents, and (d) the new input — then send it. The sophistication lives in *how you choose* (b) and (c) when they cannot all fit.

### 2.2 The two axes of agent memory

There are two orthogonal classifications. People conflate them, so pin them down separately.

**Axis A — Duration / scope (the systems view):**

- **Short-term / working memory:** lives for the duration of a single task or conversation. Usually held in the prompt itself or in a per-session object. Volatile; lost when the session ends. Examples: the conversation transcript, a scratchpad of intermediate reasoning, the current tool-call results.
- **Long-term memory:** persists across sessions and survives process restarts. Stored in an external durable system (relational DB, document store, vector store, key-value store). Examples: "this user prefers metric units," summaries of past conversations, a knowledge base.

**Axis B — Content type (the cognitive-science view, borrowed and applied loosely):**

- **Episodic memory:** specific past events/experiences. "On 2026-06-01 the user asked me to book a flight to BLR and I did." Time-stamped, particular.
- **Semantic memory:** general facts and knowledge, divorced from when they were learned. "The user's home airport is BLR." "Kubernetes pods are scheduled by the kube-scheduler."
- **Procedural memory:** how to do things — learned skills, workflows, reusable instructions. "When the user asks for a status report, query Jira, group by epic, and format as a table." Often stored as updated system prompts, tool descriptions, or few-shot examples.

These two axes cross-cut: episodic/semantic/procedural memories can each be short-term or long-term. A scratchpad is short-term procedural-ish; a vector store of past chats is long-term episodic; a user-profile row is long-term semantic.

> **Term: scratchpad.** A scratchpad (a.k.a. working memory or agent state) is a region of the prompt or an in-memory structure where the agent writes intermediate results — its chain of thought, a running plan, the outputs of tools it has called so far. It is the agent's "thinking out loud" space and is the canonical short-term working memory.

### 2.3 The context window as a budget

Everything bottoms out in the token budget. Suppose a 128k-token window. A long-running agent accumulates:

- system prompt + tool schemas: ~2k–8k tokens (tool/function JSON schemas are surprisingly heavy)
- conversation so far: grows unbounded
- retrieved documents: 0–50k tokens depending on RAG settings
- the model's reasoning + output: 1k–16k tokens

Two failure surfaces:

1. **Hard overflow:** you exceed the window → the API rejects the call (HTTP 400, `context_length_exceeded`) or silently truncates (worse — silent data loss).
2. **Soft degradation:** even within the window, models degrade as context grows. The well-documented **"lost in the middle"** effect (Liu et al., 2023) shows retrieval accuracy is highest for content at the *start* and *end* of a long context and sags in the middle. Long contexts also cost more (you pay per input token) and are slower (latency scales with context length).

> **Term: "lost in the middle."** An empirical finding that LLMs attend most reliably to information at the beginning and end of their context window, and least reliably to information buried in the middle. Practical consequence: placing the most important facts at the top (system prompt) or bottom (most recent turns) of the prompt improves reliability; dumping a 100k-token document and hoping the model finds the one relevant sentence is risky.

So even with million-token windows, **you should not just stuff everything in.** Cost, latency, and accuracy all argue for active memory management: keep the prompt small, relevant, and well-ordered.

### 2.4 Short-term memory strategies (build-up)

Given a growing conversation, how do you keep it inside budget? Four canonical strategies, from simplest to most sophisticated:

1. **Full history (no management).** Resend everything. Works until you overflow. Fine for short chats.
2. **Buffer window / sliding window.** Keep only the last *N* messages (or last *N* tokens). Simple, bounded, but the agent "forgets" anything older than the window.
3. **Summarization / compaction.** Periodically replace old turns with a compressed summary produced by the LLM itself. Preserves the gist of old context at a fraction of the tokens. This is the workhorse for long conversations.
4. **Retrieval over history.** Store every turn in a searchable store (often a vector store). Each new turn, retrieve only the *k* most relevant past turns and inject them. Scales to effectively unlimited history; only relevant bits enter the prompt.

Real systems combine these: e.g., keep the last 10 turns verbatim (window), a rolling summary of everything older (summarization), and a vector store of all turns for on-demand recall (retrieval).

### 2.5 Long-term memory and the role of vector stores

Long-term memory needs (a) durable storage and (b) a way to find the *relevant* memory among potentially millions of entries. For exact-key lookups (user ID → profile) a relational/KV store suffices. For "find me the past conversations *similar in meaning* to the current question," you need **semantic search**, which is what vector stores provide.

> **Term: embedding.** An embedding is a fixed-length vector of floating-point numbers (e.g., 768, 1024, 1536, 3072 dimensions) produced by an embedding model from a piece of text. Texts with similar *meaning* produce vectors that are close together in this high-dimensional space (by cosine similarity or dot product). Embeddings turn "semantic similarity" into "geometric proximity," which computers can search efficiently.

> **Term: vector store / vector database.** A database specialized for storing embeddings and answering "nearest neighbor" queries: given a query vector, return the *k* stored vectors closest to it. Examples: Pinecone, Weaviate, Milvus, Qdrant, Chroma, pgvector (a Postgres extension), Elasticsearch/OpenSearch kNN, Redis vector search. It uses an **ANN (Approximate Nearest Neighbor)** index because exact search over millions of high-dim vectors is too slow.

> **Term: ANN / HNSW / IVF.** Approximate Nearest Neighbor search trades a tiny bit of recall for huge speed. **HNSW (Hierarchical Navigable Small World)** builds a layered graph you can traverse greedily to find near neighbors in logarithmic-ish time; it is the most common index. **IVF (Inverted File)** clusters vectors and only searches the nearest clusters. Both have tuning knobs (e.g., HNSW's `M` and `efSearch`) that trade recall vs latency/memory.

The pattern that uses long-term memory at inference time is **RAG**.

> **Term: RAG (Retrieval-Augmented Generation).** A pattern where, before calling the LLM, you (1) embed the user's query, (2) search a vector store for the most relevant chunks of text, and (3) inject those chunks into the prompt as context. The model then answers grounded in retrieved facts rather than only its training data. Agent long-term memory is essentially RAG over the agent's own accumulated experiences/facts.

### 2.6 State persistence and resumability (build-up)

A conversation is one kind of state. An *agent's execution* is a richer kind: which step of a plan it's on, the partial results of tools it has called, the loop counter, whether it's waiting on a human. To **pause and resume** an agent — across a crash, a deploy, a rate-limit backoff, or a human approval gate — you must serialize that execution state to durable storage and reload it later. This is **checkpointing**.

> **Term: checkpoint.** A saved snapshot of an agent's complete execution state at a point in time, written to durable storage. Resuming from a checkpoint restores the agent to exactly that state and continues. Conceptually identical to a database WAL checkpoint, a VM snapshot, or a game save. Frameworks like **LangGraph** make checkpointing first-class.

> **Term: LangGraph.** An orchestration library (from the LangChain team) for building agents as **state graphs**: nodes are functions, edges define control flow, and a shared mutable **state object** flows through. Its key memory feature is the **checkpointer** — after each node executes, the entire state is persisted (to memory, SQLite, Postgres, Redis, etc.), keyed by a thread ID, so runs can be paused, resumed, inspected, time-traveled, and made human-in-the-loop. We use it as the running example because it makes the abstractions concrete.

> **Term: idempotency.** An operation is idempotent if performing it multiple times has the same effect as performing it once. Critical for resumable agents: if an agent crashes after sending an email but before recording that it sent it, naive resume would send the email twice. Idempotency keys and checkpoint-after-effect ordering prevent duplicate side effects.

With these foundations — stateless model, token budget, the two memory axes, short-term strategies, vector-store-backed long-term memory, and checkpointed execution state — we can now go under the hood.

---

## 3. How it works internally

This is the heart. We trace the full lifecycle of an agent turn end-to-end, then drill into each memory subsystem's internal workflow.

### 3.1 The agent turn lifecycle (control + data flow)

Here is what happens, step by step, on a single user turn for a memory-equipped agent:

1. **Receive input.** User message arrives with a session/thread ID. The orchestrator loads the *short-term state* for that thread (conversation buffer, scratchpad, loop counter) from wherever it lives (in-process map, Redis, checkpointer).
2. **Pre-retrieval (long-term read).** The orchestrator decides whether to fetch long-term memory. Two policies: (a) *always retrieve* (embed the query, search the vector store, get top-*k*), or (b) *let the LLM decide* by exposing a `search_memory` tool the model can call. It also loads structured memory (user profile row) by key.
3. **Context assembly (the prompt builder).** The orchestrator constructs the `messages` array under the token budget, in priority order:
   - system prompt + persona + dynamic instructions (procedural memory)
   - tool schemas
   - long-term semantic facts (user profile, retrieved knowledge)
   - rolling summary of old conversation (compacted episodic)
   - last *N* verbatim turns (recent working memory)
   - retrieved relevant past turns (episodic recall)
   - the new user message
   - It counts tokens (e.g., via `tiktoken`/`jtokkit`) and applies the **eviction/compaction policy** if over budget.
4. **LLM call.** Send the assembled prompt. Receive either a final answer or a **tool call** request.
5. **Act (if tool call).** Execute the tool, capture its output, append it to the scratchpad as a `tool` message. Loop back to step 3 (re-assemble, re-call) until the model returns a final answer. This inner loop is the **agent loop / ReAct loop**.
6. **Post-processing (long-term write).** Decide what to persist. The **write policy** (Section 3.5) extracts durable facts from this turn (e.g., "user said they're vegetarian") and writes them to long-term memory; optionally embeds and stores the turn for future retrieval; optionally updates the rolling summary.
7. **Checkpoint.** Persist the updated short-term state (and possibly a checkpoint of the whole execution graph) keyed by thread ID, so the next turn — or a resume after crash — starts from the right place.
8. **Respond.** Return the answer to the user.

> **Term: ReAct.** "Reasoning + Acting" — a prompting/agent pattern (Yao et al., 2022) where the model interleaves *thought* ("I should look up the user's location"), *action* (a tool call), and *observation* (the tool result), looping until done. The accumulating thought/action/observation trace is the scratchpad/working memory of the loop.

### 3.2 Short-term memory internals: the buffer

The simplest working memory is an in-memory list of messages plus a trimming policy. State machine for a **token-bounded sliding window**:

- **State:** `messages: List<Message>`, `tokenBudget`, `currentTokens`.
- **On append(message):** add message; `currentTokens += count(message)`.
- **On overflow** (`currentTokens > tokenBudget`): evict from the *front* (oldest non-system messages first) until under budget. Always preserve the system message. Optionally preserve message pairs (don't orphan a tool result from its tool call).

Subtlety: **never split a tool-call/tool-result pair.** Chat APIs require that every `assistant` message containing a tool call be followed by the corresponding `tool` result message; evicting one but not the other yields an API validation error. So eviction works on logical groups, not raw messages.

### 3.3 Summarization / compaction internals

When the window approaches full, you compact. The internal workflow:

1. **Trigger.** Either token-threshold (e.g., when buffer > 70% of budget) or turn-count (e.g., every 6 turns) or model-signaled. (LangGraph and Claude's "context compaction" use a threshold trigger.)
2. **Select the chunk to compact.** Take the oldest *M* turns that are outside the "keep verbatim" window.
3. **Summarize via an LLM call.** Prompt a (often cheaper/smaller) model: "Summarize the following conversation, preserving names, decisions, open tasks, and user preferences. Output ≤ 300 tokens." This is a *recursive summary* if a previous summary exists: feed the old summary + new turns → produce an updated summary.
4. **Replace.** Swap the *M* raw turns for the single summary message (typically a `system` or `assistant` message tagged `[conversation summary]`). Recompute token count.
5. **Persist** the summary so it survives restarts.

Tradeoff baked in: summarization is **lossy**. Specific quotes, exact numbers, and nuance can be lost. Mitigations: keep the last *N* turns verbatim (recent precision), and also store full turns in a vector store so exact recall is still possible via retrieval (Section 3.4). This combination — verbatim recent + summary middle + retrievable full archive — is the production-grade pattern.

> **Term: compaction.** A general term (also used in LSM-tree databases and log systems) for compressing accumulated state into a smaller equivalent form. In agents, it means summarizing old conversation/tool-output into fewer tokens. Anthropic's tooling and LangGraph both ship compaction utilities.

### 3.4 Long-term memory internals: write path and read path

**Write path (indexing) — how a memory gets stored:**

1. **Extract.** Decide what to store. Could be the raw turn, or an LLM-extracted fact ("memory extraction": prompt a model with the turn and ask "what durable facts about the user/task should be remembered?").
2. **Chunk** (for documents). Split long text into chunks of e.g. 256–1024 tokens with overlap (e.g., 10–20%), so each chunk is independently meaningful and embeddable. Overlap prevents cutting a fact across a boundary.
3. **Embed.** Call the embedding model on each chunk → a vector.
4. **Store.** Upsert into the vector store: `{ id, vector, text, metadata }`. Metadata is crucial: `user_id`, `timestamp`, `source`, `type` (episodic/semantic), `ttl`. Also store structured facts in a relational/KV store for exact lookup and updates.
5. **Index.** The vector store inserts the vector into its ANN index (HNSW graph link insertion, etc.).

**Read path (retrieval) — how a memory comes back:**

1. **Form the query.** Often the raw user message; better, a *rewritten* query (LLM rewrites "what about the other one?" into a self-contained query using recent context).
2. **Embed** the query with the *same* embedding model used for writes (mismatched models = garbage results).
3. **ANN search** in the vector store for top-*k* (e.g., k=4–10), filtered by metadata (`user_id = X`, `timestamp > Y`).
4. **(Optional) Re-rank.** Take top ~50 candidates and re-score them with a cross-encoder/reranker model for higher precision, keep top-*k*. (Initial ANN by embedding similarity is cheap but coarse; rerankers are accurate but slow.)
5. **Inject.** Place retrieved texts in the prompt (usually labeled, e.g., "Relevant past context: …"), ordered with the most relevant near the bottom (recency-of-attention) or top (lost-in-the-middle mitigation).

> **Term: re-ranker / cross-encoder.** A model that takes a (query, document) *pair* together and outputs a relevance score. Unlike embedding similarity (which encodes query and doc separately — a "bi-encoder"), a cross-encoder sees both at once and is far more accurate, but you can't pre-index it, so you run it only on a small candidate set retrieved by the cheap embedding search. Two-stage retrieval (embed → rerank) is standard for quality-sensitive memory.

### 3.5 Memory read/write policies and the lifecycle of a fact

A *policy* decides when to write, what to write, what to overwrite, and when memory is stale.

**Write policies:**
- **Write-on-every-turn (eager):** store every turn. Simple, complete, but noisy and expensive; the store fills with low-value chatter.
- **Write-on-extraction (curated):** an LLM/heuristic decides what's worth keeping (preferences, decisions, entities). Higher quality, costs an extra LLM call, can miss things.
- **Reflexion / reflection writes:** after a task, the agent writes a *reflection* ("what worked, what to do differently") into procedural memory to improve next time.
- **Update vs append (the dedup/conflict problem):** if the user says "actually I moved to Bangalore," do you *append* a new fact (now you have two conflicting locations) or *update/overwrite* the existing one? Update requires resolving identity ("this is the same fact, superseded"). Naive append leads to contradictory memories and is a top source of agent confusion.

**Read policies:**
- **Always-on retrieval:** every turn does a vector search. Robust, but adds latency/cost and can inject irrelevant context.
- **Tool-gated retrieval:** expose `search_memory(query)` as a tool; the model retrieves only when it judges it needs to. Cheaper, but depends on the model deciding correctly.

> **Term: staleness.** A memory is stale when it no longer reflects reality (the user moved, the price changed, the policy was updated) but is still stored and being retrieved. Staleness corrupts answers. Defenses: timestamps + recency weighting (prefer newer memories), **TTLs** (expire memories after a duration), explicit invalidation/update on contradicting input, and source-of-truth precedence (a live DB query beats a cached memory).

> **Term: TTL (time-to-live).** A duration after which a stored item is automatically expired/deleted. Used to bound staleness and storage growth for ephemeral memories.

**The lifecycle of a single fact:** *observed* (in a turn) → *extracted* (write policy decides it's durable) → *embedded + stored* (with timestamp/metadata) → *retrieved* (when relevant) → *used* (injected into a prompt) → *updated/superseded* (on new contradicting info) → *expired/forgotten* (TTL or pruning). Designing each transition is the core of memory engineering.

### 3.6 State persistence & checkpointing internals (LangGraph as concrete example)

LangGraph models an agent as a graph with a typed **state** object. Internals of a checkpointed run:

1. **State definition.** You define a state schema (e.g., a dict/TypedDict with `messages`, `plan`, `step`). Reducers define how each node's output merges into state (e.g., `messages` uses an "append" reducer; `plan` uses "replace").
2. **Thread identity.** Every run is keyed by a `thread_id` (the conversation/session). The checkpointer namespaces all saved state by it.
3. **Superstep execution.** The graph executes in **supersteps** (one or more nodes run, then state is reduced). After *each* superstep, the checkpointer writes the *entire* state plus metadata (which node ran, the timestamp, a monotonically increasing checkpoint ID, parent checkpoint ID) to durable storage.
4. **Persistence backend.** Pluggable `BaseCheckpointSaver`: `MemorySaver` (in-process, for dev), `SqliteSaver`, `PostgresSaver`, Redis, etc. Each checkpoint is a row keyed by `(thread_id, checkpoint_id)` with the serialized state blob and the channel values.
5. **Resume.** To continue a thread, the orchestrator loads the *latest* checkpoint for the `thread_id` and re-enters the graph at the saved position. Because every superstep is persisted, a crash loses at most the last partial superstep.
6. **Interrupts (human-in-the-loop).** A node can `interrupt()`, which checkpoints and *halts*, returning control to the caller (e.g., to get human approval). Later you resume by supplying the human's input; the graph continues from the interrupt point. This is checkpointing used as a coroutine/yield mechanism.
7. **Time travel.** Because every checkpoint is retained with parent pointers, you can list a thread's checkpoint history, fork from an *earlier* checkpoint, edit the state, and re-run a different branch — invaluable for debugging and "what-if" replays.

> **Term: superstep.** Borrowed from the Pregel/Bulk-Synchronous-Parallel model of graph computation: a discrete step in which a batch of nodes execute and then synchronize/merge their results before the next batch. LangGraph persists state at superstep boundaries, giving clean, consistent checkpoints.

> **Term: human-in-the-loop (HITL).** A design where the agent pauses at sensitive steps (spending money, sending external messages, deleting data) and waits for a human to approve, edit, or reject before proceeding. Implemented via interrupt-and-resume on top of checkpointing.

The same primitives appear under different names across stacks: OpenAI's Assistants API persists **threads** and **runs** server-side; Temporal (a durable-execution engine) persists workflow state via an event-sourced history so a workflow function can resume after any crash; your own implementation might just `INSERT` the state into Postgres after each step. The principle is universal: **serialize execution state at safe points, key it by an identity, reload to resume.**

> **Term: Temporal / durable execution.** Temporal is a workflow engine that makes ordinary code crash-proof by recording every step (activity) to an event log and *replaying* the log to reconstruct state after a failure, so a long-running function resumes exactly where it left off. Increasingly used to host long-running agents because it solves resumability and idempotency at the infrastructure layer.

---

## 4. The complete toolkit

Below: the methods, classes, APIs, stores, and config you actually use. Defaults are flagged with versions/vendors where they vary; if a default is genuinely uncertain it is marked *(verify)*.

### 4.1 Short-term memory constructs

| Construct | What it does | Key params | Typical default |
|---|---|---|---|
| Message buffer (full) | Resend entire history | — | unbounded |
| Sliding window (by count) | Keep last *N* messages | `N` | app-defined (e.g., 10–20) |
| Sliding window (by tokens) | Keep last *T* tokens | `maxTokens` | ~50–75% of window |
| Rolling summary | LLM-compressed old turns | summarizer model, trigger threshold, max summary tokens | trigger ~70% budget *(verify per tool)* |
| Scratchpad / state object | Holds intermediate reasoning & tool outputs | schema, reducers | framework-specific |

LangChain memory classes (note: LangChain v0.3+ deprecates the old `Memory` classes in favor of LangGraph persistence; listed for recognition):

| Class | Behavior |
|---|---|
| `ConversationBufferMemory` | Stores all turns verbatim |
| `ConversationBufferWindowMemory` | Last *k* turns only (`k` param) |
| `ConversationTokenBufferMemory` | Last turns under `max_token_limit` |
| `ConversationSummaryMemory` | Replaces all history with a rolling summary |
| `ConversationSummaryBufferMemory` | Verbatim recent + summary of older (`max_token_limit`) |
| `VectorStoreRetrieverMemory` | Retrieves relevant past turns from a vector store |

### 4.2 Token counting (you must measure, not guess)

| Tool | Ecosystem | Notes |
|---|---|---|
| `tiktoken` | Python / OpenAI | BPE tokenizer matching OpenAI models; `encoding_for_model("gpt-4o")` |
| `jtokkit` | Java | JVM port of tiktoken; `Encodings.newDefaultEncodingRegistry()` |
| Anthropic count-tokens endpoint | Claude | `POST /v1/messages/count_tokens` returns exact input token count |
| `transformers` `AutoTokenizer` | HF / open models | Per-model tokenizer for Llama, Mistral, etc. |

Counting matters because cost and the hard limit are both in tokens, and tokenization differs per model family — a string is *not* the same token count across GPT, Claude, and Llama.

### 4.3 Vector stores & embeddings (long-term semantic memory)

| Vector store | Type | Index | Notes |
|---|---|---|---|
| pgvector | Postgres extension | IVFFlat, HNSW | Reuse existing Postgres; transactional; good default for JVM shops |
| Qdrant | Standalone | HNSW | Rust; rich metadata filtering; payload indexes |
| Weaviate | Standalone | HNSW | Built-in vectorizers, hybrid search |
| Milvus | Standalone | IVF/HNSW/DiskANN | Scales to billions; heavier ops |
| Pinecone | Managed SaaS | proprietary | No ops; per-vector pricing |
| Chroma | Embedded/local | HNSW | Great for dev/prototyping |
| Redis (RediSearch) | In-memory | HNSW/FLAT | Low latency; doubles as cache |
| OpenSearch/Elasticsearch | Search engine | HNSW | Hybrid (BM25 + vector) |

> **Term: hybrid search.** Combining lexical search (**BM25** — a classic keyword-relevance ranking) with vector/semantic search and fusing the rankings (e.g., **Reciprocal Rank Fusion**). Catches both exact-keyword matches (IDs, names, error codes that embeddings handle poorly) and semantic matches. Often materially better than vector-only.

| Embedding model | Dim | Notes |
|---|---|---|
| OpenAI `text-embedding-3-small` | 1536 (truncatable) | Cheap, strong baseline |
| OpenAI `text-embedding-3-large` | 3072 | Higher quality |
| Cohere `embed-v3` | 1024 | Strong reranking ecosystem |
| `all-MiniLM-L6-v2` (sentence-transformers) | 384 | Tiny, fast, local, free |
| BGE / E5 families | 768–1024 | Strong open models |

Key vector-search params: `k` (results returned, 3–10 typical), `efSearch`/`ef` (HNSW search breadth — higher = better recall, slower), `M` (HNSW graph connectivity — higher = better recall, more memory), distance metric (`cosine` is default for normalized text embeddings; `dot`/`l2` alternatives).

### 4.4 Managed agent-memory APIs & frameworks

| Tool | What it provides |
|---|---|
| LangGraph checkpointers (`MemorySaver`, `SqliteSaver`, `PostgresSaver`, Redis) | Persistent execution state, threads, HITL interrupts, time travel |
| LangGraph `Store` (long-term) | Cross-thread key-value + semantic memory namespaced by user |
| OpenAI Assistants API | Server-side persistent `threads`, `runs`, `messages`, file search |
| LangMem / `mem0` | Higher-level memory extraction, dedup, update policies |
| Letta (formerly MemGPT) | "OS for LLMs": tiered memory with self-managed paging |
| Temporal | Durable execution for long-running, crash-proof agent workflows |
| Spring AI `ChatMemory` / advisors | JVM-native conversation memory + RAG advisors |
| LlamaIndex memory + index abstractions | Document indexing, retrieval, chat memory buffers |

> **Term: MemGPT / Letta.** A research system (and product, Letta) that treats the context window like RAM and external storage like disk, and gives the LLM *tools to page memory in and out itself* — deciding what to keep in-context and what to archive — emulating an operating system's virtual memory for an "infinite" effective context.

### 4.5 LangGraph checkpointer API surface (concrete)

| Method / object | Purpose |
|---|---|
| `graph.compile(checkpointer=...)` | Attach persistence to a graph |
| `config = {"configurable": {"thread_id": "abc"}}` | Identify the conversation/run |
| `graph.invoke(input, config)` | Run; auto-checkpoints each superstep |
| `graph.get_state(config)` | Read current persisted state |
| `graph.get_state_history(config)` | List all checkpoints (for time travel) |
| `graph.update_state(config, values)` | Manually edit persisted state |
| `interrupt(value)` / `Command(resume=...)` | Pause for HITL, then resume |
| `Store.put/get/search(namespace, key, value)` | Long-term cross-thread memory (with optional embedding-based `search`) |

---

## 5. Code examples by use case

Six distinct scenarios. Java is used where the topic is language-relevant (the JVM reader profile); Python is used for LangGraph/where the ecosystem is Python-first. Non-obvious lines are commented.

### 5.1 Bare-metal short-term memory in Java (no framework)

Shows the fundamental truth: memory = you maintaining and resending the message list, with a token-bounded sliding window.

```java
import com.knuddels.jtokkit.api.*;          // jtokkit: JVM token counter
import com.knuddels.jtokkit.Encodings;
import java.util.*;

public class ChatSession {
    record Msg(String role, String content) {}

    private final Deque<Msg> buffer = new ArrayDeque<>();
    private final Msg system = new Msg("system", "You are a helpful assistant.");
    private final int maxTokens;
    private final Encoding enc =
        Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public ChatSession(int maxTokens) { this.maxTokens = maxTokens; }

    private int tokens(Msg m) {
        // ~4 tokens of per-message overhead in OpenAI chat format; approximate.
        return enc.countTokens(m.content()) + 4;
    }

    public void add(String role, String content) {
        buffer.addLast(new Msg(role, content));
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        int total = tokens(system) + buffer.stream().mapToInt(this::tokens).sum();
        // Evict OLDEST first, but never the system prompt (it isn't in the buffer).
        while (total > maxTokens && buffer.size() > 1) {
            Msg dropped = buffer.removeFirst();   // FIFO eviction
            total -= tokens(dropped);
            // NOTE: in a real impl, drop tool-call/tool-result PAIRS together.
        }
    }

    /** The prompt = system + whatever survives in the window. This IS the memory. */
    public List<Msg> buildPrompt(String userInput) {
        add("user", userInput);
        List<Msg> prompt = new ArrayList<>();
        prompt.add(system);
        prompt.addAll(buffer);
        return prompt; // send this to the model API
    }
}
```

Lesson: nothing magic — the "memory" is a list you trim and resend. Every framework is sugar over this.

### 5.2 Rolling-summary compaction (Java, pseudo-LLM)

Keeps the last *N* turns verbatim and compresses everything older into a single summary message.

```java
public class SummarizingMemory {
    private final List<Msg> recent = new ArrayList<>();   // verbatim recent turns
    private String runningSummary = "";                   // compacted older turns
    private final int keepVerbatim = 8;                   // last 8 turns kept raw
    private final LlmClient llm;                           // your model client

    public SummarizingMemory(LlmClient llm) { this.llm = llm; }

    public void add(Msg m) {
        recent.add(m);
        if (recent.size() > keepVerbatim) {
            // Move the oldest overflow turns into the summary (recursive summarization).
            List<Msg> toCompact = new ArrayList<>(recent.subList(0, recent.size() - keepVerbatim));
            recent.subList(0, recent.size() - keepVerbatim).clear();
            runningSummary = compact(runningSummary, toCompact);
        }
    }

    private String compact(String prior, List<Msg> turns) {
        String prompt = """
            You are maintaining a running summary of a conversation.
            Preserve: names, decisions, open tasks, user preferences, numbers.
            Be concise (<= 250 tokens). Update the prior summary with the new turns.

            PRIOR SUMMARY:
            %s

            NEW TURNS:
            %s
            """.formatted(prior.isBlank() ? "(none)" : prior, render(turns));
        // Use a cheaper/faster model for summarization to control cost.
        return llm.complete("gpt-4o-mini", prompt);
    }

    public List<Msg> buildPrompt() {
        List<Msg> p = new ArrayList<>();
        p.add(new Msg("system", "You are a helpful assistant."));
        if (!runningSummary.isBlank())
            p.add(new Msg("system", "[Conversation summary so far] " + runningSummary));
        p.addAll(recent);   // recent turns kept precise
        return p;
    }
    private String render(List<Msg> ms){ /* join role: content */ return ""; }
}
```

Lesson: precision-where-it-matters (recent verbatim) + cheap recall-of-gist (summary), summarized by a cheaper model.

### 5.3 Long-term semantic memory with pgvector (Java + Spring JDBC)

Cross-session memory: store user facts as embeddings, retrieve the relevant ones each session.

```sql
-- Schema (Postgres + pgvector extension)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE agent_memory (
  id          BIGSERIAL PRIMARY KEY,
  user_id     TEXT NOT NULL,
  content     TEXT NOT NULL,
  embedding   VECTOR(1536) NOT NULL,         -- matches text-embedding-3-small
  kind        TEXT NOT NULL,                 -- 'episodic' | 'semantic' | 'procedural'
  created_at  TIMESTAMPTZ DEFAULT now(),
  expires_at  TIMESTAMPTZ                    -- TTL for staleness control
);
-- ANN index (HNSW) with cosine distance:
CREATE INDEX ON agent_memory USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON agent_memory (user_id);
```

```java
@Repository
public class MemoryStore {
    private final JdbcTemplate jdbc;
    private final EmbeddingClient embed;   // wraps your embedding model
    public MemoryStore(JdbcTemplate jdbc, EmbeddingClient embed){ this.jdbc=jdbc; this.embed=embed; }

    public void write(String userId, String content, String kind) {
        float[] v = embed.embed(content);                 // SAME model as read path
        jdbc.update(
          "INSERT INTO agent_memory(user_id, content, embedding, kind) VALUES (?,?,?::vector,?)",
          userId, content, toVectorLiteral(v), kind);
    }

    /** Top-k relevant, non-expired memories for this user, by cosine distance. */
    public List<String> retrieve(String userId, String query, int k) {
        float[] qv = embed.embed(query);
        return jdbc.query(
          """
          SELECT content FROM agent_memory
          WHERE user_id = ? AND (expires_at IS NULL OR expires_at > now())
          ORDER BY embedding <=> ?::vector     -- <=> is cosine distance in pgvector
          LIMIT ?
          """,
          (rs, i) -> rs.getString("content"),
          userId, toVectorLiteral(qv), k);
    }

    private String toVectorLiteral(float[] v){ /* "[0.1,0.2,...]" */ return Arrays.toString(v); }
}
```

Lesson: long-term memory is a CRUD store + ANN search; TTL and `user_id` filtering keep it scoped and fresh.

### 5.4 Memory write policy with extraction & dedup (Java, pseudo-LLM)

The curated write policy: don't store chatter; extract durable facts and update rather than blindly append.

```java
public class MemoryWriter {
    private final MemoryStore store;
    private final LlmClient llm;
    public MemoryWriter(MemoryStore store, LlmClient llm){ this.store=store; this.llm=llm; }

    /** Extract durable facts from a turn and upsert them, resolving contradictions. */
    public void maybeWrite(String userId, String userTurn) {
        // 1) Extraction: ask the model what is worth remembering. May return [].
        List<String> facts = llm.extractFacts(
            "Extract durable facts about the user (preferences, identity, goals). " +
            "Return [] if nothing durable. Turn: " + userTurn);

        for (String fact : facts) {
            // 2) Dedup/conflict: find similar existing memory.
            List<String> similar = store.retrieve(userId, fact, 1);
            if (!similar.isEmpty() && llm.contradicts(similar.get(0), fact)) {
                // 3) Supersede: invalidate stale, write new (update-not-append).
                store.expire(userId, similar.get(0));     // set expires_at = now()
            }
            store.write(userId, fact, "semantic");
        }
    }
}
```

Lesson: the hard part of long-term memory is not storage; it is *deciding what to keep* and *handling contradictions* (the staleness/dedup problem).

### 5.5 Persistent, resumable agent with LangGraph checkpointing (Python)

Execution-state persistence: the agent survives process restarts and supports human approval.

```python
from langgraph.graph import StateGraph, END
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.types import interrupt, Command
from typing import TypedDict, Annotated
import operator

class State(TypedDict):
    messages: Annotated[list, operator.add]   # reducer: append new messages
    plan: str                                  # replaced each time

def planner(state: State):
    return {"plan": "1) gather data 2) email summary"}

def approval(state: State):
    # Pause here; checkpoint is written, control returns to caller (HITL gate).
    decision = interrupt({"plan": state["plan"], "ask": "Approve sending email?"})
    return {"messages": [f"human said: {decision}"]}

def executor(state: State):
    return {"messages": ["email sent"]}        # side effect AFTER approval

g = StateGraph(State)
g.add_node("planner", planner); g.add_node("approval", approval); g.add_node("executor", executor)
g.set_entry_point("planner")
g.add_edge("planner", "approval"); g.add_edge("approval", "executor"); g.add_edge("executor", END)

# Postgres-backed checkpointer => durable across process restarts.
with PostgresSaver.from_conn_string("postgresql://localhost/agents") as cp:
    cp.setup()
    app = g.compile(checkpointer=cp)
    cfg = {"configurable": {"thread_id": "user-42-task-7"}}

    # First run: stops at the interrupt, state is persisted.
    app.invoke({"messages": [], "plan": ""}, cfg)

    # ... process could crash / restart here; state is safe in Postgres ...

    # Later: resume the SAME thread with the human's decision.
    app.invoke(Command(resume="approved"), cfg)   # continues from approval -> executor
```

Lesson: checkpointing turns a fragile in-memory loop into a durable, pausable, human-gated workflow keyed by `thread_id`.

### 5.6 Spring AI: conversation memory + RAG long-term memory (Java)

JVM-native combination of short-term chat memory and long-term retrieval.

```java
@Configuration
class AgentConfig {
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore,
                          ChatMemory chatMemory) {
        return builder
            .defaultAdvisors(
                // Short-term: replays recent conversation per conversationId.
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                // Long-term: retrieves relevant docs from the vector store (RAG).
                QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder().topK(5).build())
                    .build())
            .build();
    }
}

@Service
class Assistant {
    private final ChatClient chat;
    Assistant(ChatClient chat){ this.chat = chat; }

    String ask(String conversationId, String userText) {
        return chat.prompt()
            .user(userText)
            // conversationId scopes the short-term memory to this session/thread.
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call().content();
    }
}
```

Lesson: in the JVM world, Spring AI's *advisors* compose short-term (`ChatMemory`) and long-term (`VectorStore` + `QuestionAnswerAdvisor`) memory declaratively, the same two layers as the bare-metal example.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency

- **Every memory operation adds latency:** an embedding call (~20–100ms), an ANN query (~5–50ms), a reranker (~50–300ms), a summarization LLM call (~hundreds of ms to seconds). Budget these; do retrieval/summarization in parallel with other prep where possible.
- **Prompt length dominates LLM latency and cost.** Larger prompts mean slower **TTFT (time-to-first-token)** and higher per-call cost. Keep prompts lean; retrieve precisely (small *k*, reranked) rather than dumping.
- **Cache aggressively.** **Prompt caching** (offered by Anthropic, OpenAI, Google) lets you mark a stable prefix (system prompt, tool schemas, long static context) as cached so repeated calls reuse it at a large discount (e.g., ~90% cheaper, lower latency on cache hits). Structure prompts so the *stable* parts come first (system + tools + long-term context) and the *volatile* parts (latest turn) come last — this maximizes cache hits.

> **Term: prompt caching / KV cache.** During inference the model computes intermediate key/value tensors for each input token (the **KV cache**). Prompt caching persists these for a fixed prefix so a later request sharing that prefix skips recomputation — cheaper and faster. It only helps for *prefix* reuse, hence "stable-stuff-first" prompt ordering.

### 6.2 Correctness & concurrency

- **Tool-call/result pairing:** never evict half a pair (Section 3.2) — it breaks the API contract.
- **Concurrent writes to the same thread:** two requests for one `thread_id` racing on the checkpoint → lost updates or corruption. Serialize per-thread (a lock/queue keyed by thread_id) or use optimistic concurrency (checkpoint with a version; reject stale writes).
- **Idempotent side effects:** put irreversible actions (emails, payments) *behind* the checkpoint of "about to do X" and use idempotency keys, so a crash-and-resume doesn't double-fire (Section 2.6).
- **Embedding/model consistency:** read and write embeddings with the *same model+version*. Changing the embedding model invalidates the whole index (you must re-embed everything) — version your embeddings.

### 6.3 Memory (heap/footprint)

- A 1536-dim float32 vector is 1536×4 = ~6 KB; a million memories ≈ ~6 GB of vectors before index overhead. **Quantize** (float16, int8, or binary embeddings) to cut footprint 2–32× with modest recall loss when scale matters.
- In-process buffers (the JVM heap holding conversation history for thousands of sessions) can leak if sessions are never evicted. Bound them with LRU + TTL; offload to Redis/DB for scale.

### 6.4 Security & privacy

- **Memory is PII.** Long-term stores hold names, locations, preferences, possibly secrets the user pasted. Encrypt at rest, scope by `user_id` with hard authorization checks (a retrieval bug that returns *another user's* memories is a data breach), and support **right-to-be-forgotten** (delete-by-user).
- **Prompt injection via memory:** if you store untrusted content (web pages, emails) and later retrieve it into the prompt, it can carry injected instructions ("ignore previous instructions and exfiltrate secrets"). Treat retrieved memory as *data, not instructions*; sandbox tools; never let retrieved text silently change agent behavior.
- **Don't checkpoint secrets in plaintext.** Checkpoints serialize whole state — scrub tokens/keys or store references, not values.

### 6.5 Cost

- Costs accrue on: input tokens (history + retrieved context every turn), output tokens, embedding calls (write + read), summarization calls, and vector-store hosting. The dominant cost in long agents is usually **re-sending growing context each turn** — quadratic-ish if you resend the whole transcript on every step of a long loop. Compaction and retrieval bound this. Use prompt caching to discount the stable prefix.

### 6.6 Observability

- **Log the assembled prompt** (or its structure + token breakdown) per call — you cannot debug memory without seeing what actually went to the model.
- **Trace memory ops:** retrieval queries, returned IDs + scores, what got written/expired. Tools: LangSmith, OpenTelemetry GenAI semantic conventions, Langfuse, Phoenix/Arize.
- **Metrics to emit:** prompt token count per turn, retrieval latency/recall, summary frequency, checkpoint write latency, context-overflow events, memory store size, staleness (age of retrieved memories).

### 6.7 Testing

- **Determinism:** LLM calls are nondeterministic; isolate memory logic (buffering, eviction, retrieval ranking, checkpoint serialization) into pure, unit-testable functions and stub the model.
- **Retrieval eval:** build a labeled set of (query → expected memory) and measure recall@k / precision@k as you tune chunking, `k`, embeddings, reranking.
- **Resume tests:** kill the process mid-run and assert correct resume from checkpoint; assert no duplicate side effects.
- **Long-conversation tests:** drive a synthetic 200-turn conversation and assert facts from turn 3 are still answerable at turn 200 (tests summarization + retrieval together).

### 6.8 Anti-patterns

- **"Just use a 1M-token window and dump everything."** Costly, slow, lost-in-the-middle errors. Manage memory regardless of window size.
- **Append-only long-term memory with no dedup/update** → contradictory, stale memories; the agent "remembers" the old address forever.
- **Summarizing too aggressively** → losing the exact number/quote that mattered. Keep recent verbatim + retrievable full archive.
- **Mismatched embedding models** between write and read → silent retrieval garbage.
- **No metadata filtering** → cross-user leakage and irrelevant hits.
- **In-memory-only state in production** → every deploy/crash wipes all in-flight agents.
- **Storing secrets in checkpoints/memory** → leak.
- **Treating retrieved text as trusted instructions** → prompt injection.

---

## 7. Advanced topics & deep internals

### 7.1 Context compaction vs truncation vs offloading (the real choices)

Three distinct ways to fit history into the budget, with different loss profiles:

- **Truncation (windowing):** drop old turns. Zero compute, total loss of dropped content.
- **Compaction (summarization):** compress old turns. LLM compute cost, lossy-but-gist-preserving.
- **Offloading + retrieval:** move full content to external store, pull back relevant slices. Storage + retrieval cost, lossless archive but recall depends on retrieval quality.

Production systems layer all three: window the most recent (truncate the live buffer at *N*), compact the middle (rolling summary), offload everything (vector store). This is the standard "memory hierarchy" of a mature agent.

### 7.2 Hierarchical / tiered memory (MemGPT-style self-paging)

Letta/MemGPT exposes memory-management *tools to the model itself*: `core_memory_append`, `core_memory_replace`, `archival_memory_insert`, `archival_memory_search`, plus a recall of conversation history. The model decides what to keep in its limited "main context" (like RAM) and what to push to "archival memory" (like disk), then pages it back in on demand. The framework injects a memory-pressure warning when context fills, triggering the model to summarize/evict. This emulates virtual memory and an OS pager — the model is its own memory manager. Tradeoff: more LLM calls (the paging decisions cost tokens) for effectively unbounded recall.

### 7.3 Episodic, semantic, procedural — engineered separately

Mature agents store the three memory types in *different* substrates with *different* policies:

- **Episodic:** append-mostly, timestamped event log; retrieved by recency + similarity; good for "what did we do last Tuesday." Often a vector store with strong time metadata.
- **Semantic:** the curated fact/profile store; *updated in place* (dedup, supersede); retrieved by key or similarity; the source of "what is true about this user/world now."
- **Procedural:** learned workflows/skills, often materialized as edits to the system prompt, new few-shot examples, or stored tool macros; updated via *reflection* after tasks ("next time, query Jira before summarizing"). This is how agents *improve* at recurring tasks.

> **Term: reflection / Reflexion.** A technique (Shinn et al., 2023) where after a task the agent critiques its own performance and writes lessons into memory, which are injected on the next attempt, improving success rate without retraining weights. It is procedural/episodic memory used as a self-improvement loop.

### 7.4 Chunking strategy depth

Retrieval quality is dominated by chunking. Knobs: chunk size (small = precise but fragmented; large = coherent but dilutes relevance), overlap (prevents boundary cuts), and *semantic* chunking (split on topic/heading boundaries rather than fixed token counts). Advanced: **parent-document retrieval** (embed small chunks for precise matching but return the larger parent for context), and **contextual retrieval** (Anthropic, 2024 — prepend an LLM-generated context blurb to each chunk before embedding, sharply improving recall on ambiguous chunks).

### 7.5 Forgetting, decay, and consolidation

Biologically inspired and practically necessary: memories should *decay* (recency-weighted scoring: `score = similarity × e^(-λ·age)`), *consolidate* (merge many episodic memories into one semantic fact — "user has booked BLR flights 5 times" → "user frequently flies BLR"), and *prune* (TTL, capacity caps, importance scoring). Without forgetting, stores grow unbounded, retrieval precision drops (more candidates, more noise), and staleness accumulates. Some systems run a periodic offline "consolidation" job (like sleep) to summarize/merge/prune.

### 7.6 Checkpoint internals: serialization, schema evolution, replay vs snapshot

- **Serialization format** matters: LangGraph uses a pluggable serializer (msgpack/pickle-ish); custom impls use JSON/protobuf. Pickle is brittle across code versions — prefer schema'd formats.
- **Schema evolution:** if you add a field to the state schema, old checkpoints must still deserialize. Version your state schema and write migrations, exactly like DB migrations.
- **Snapshot vs event-sourcing:** LangGraph snapshots full state per superstep (simple, but big states are expensive to write each step). Temporal uses **event sourcing** — store the *sequence of events* and replay to reconstruct state (compact writes, but replay must be deterministic; nondeterministic LLM calls must be recorded, not re-executed, on replay). Choose snapshot for small state/simple ops; event-sourcing for long, complex, high-throughput workflows.

> **Term: event sourcing.** Persisting state as an append-only log of events rather than the current state; current state is derived by replaying events. Gives a full audit/history and compact writes, at the cost of replay complexity and a determinism requirement.

### 7.7 Multi-agent shared memory

When several agents collaborate, memory becomes a *shared* concern: a blackboard (shared state all agents read/write), message passing (each agent has private memory, communicates via messages), or a shared long-term store with per-agent namespaces. Concurrency control (who can write what), consistency (do all agents see the same world state), and isolation (one agent's scratchpad shouldn't pollute another's) become distributed-systems problems.

### 7.8 Context windows aren't free even when huge

Even with 1M-token models: cost scales with tokens, latency scales with prefill length, and accuracy degrades (lost-in-the-middle, and benchmarks show effective recall well below nominal window size for "needle in a haystack" with multiple needles or reasoning over scattered facts). The advanced practitioner uses big windows as *headroom*, not as an excuse to stop managing memory.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Short-term memory strategy selection

| Strategy | Recall of old info | Cost | Latency | Complexity | Use when… | Avoid when… |
|---|---|---|---|---|---|---|
| Full buffer | Perfect (until overflow) | Grows | Grows | Trivial | Short chats; cheap | Long sessions; cost-sensitive |
| Sliding window | None beyond window | Bounded, low | Low | Trivial | Old turns irrelevant | Need long-range recall |
| Summarization | Gist only (lossy) | + summary calls | + summary latency | Medium | Long chats, gist suffices | Need exact old quotes/numbers |
| Retrieval over history | Relevant slices | + embed/search | + retrieval | High | Very long history; selective recall | Tiny chats (overkill) |
| Hybrid (window+summary+retrieval) | Best overall | Highest | Higher | Highest | Production long-running agents | Prototypes; simple bots |

### 8.2 Long-term store selection

| Store | Best for | Pros | Cons |
|---|---|---|---|
| Relational/KV (Postgres, Redis) | Exact-key facts (profiles) | Transactional, simple, fast key lookup | No semantic search |
| pgvector | Semantic memory in existing PG | One DB, transactional, cheap | Scales to ~millions, not billions |
| Dedicated vector DB (Qdrant/Pinecone/Milvus) | Large-scale semantic memory | Scale, filtering, managed options | Extra system to run/pay |
| Hybrid (vector + BM25 / OpenSearch) | Mixed keyword+semantic recall | Best recall | More complex |

### 8.3 Execution-state persistence selection

| Approach | Resumability | HITL | Ops burden | Use when… |
|---|---|---|---|---|
| In-memory only | None (lost on restart) | Hard | Lowest | Stateless single-shot calls; dev |
| DB rows per step (DIY) | Good | Manual | Medium | Custom control, existing DB |
| LangGraph checkpointer | Excellent | First-class | Low–Medium | Graph-structured agents, time travel |
| OpenAI Assistants threads | Good (vendor-managed) | Limited | Lowest (managed) | OpenAI-only, want managed |
| Temporal (durable execution) | Excellent (crash-proof) | Yes | Higher (run Temporal) | Long, mission-critical, high-throughput workflows |

### 8.4 Big window vs retrieval (recurring debate)

| | Big context window | RAG / retrieval |
|---|---|---|
| Setup | Trivial (just paste) | Indexing pipeline |
| Cost at scale | High (pay per token every call) | Low (retrieve small slices) |
| Freshness | Whatever you pasted | Live (re-query store) |
| Scale ceiling | Window size | Effectively unbounded |
| Accuracy on scattered facts | Degrades (lost in middle) | Good (precise injection) |
| Use when | Small/medium one-off context, simplicity | Large/growing/shared/fresh knowledge |

Rule of thumb: **paste when it's small and one-off; retrieve when it's large, growing, shared across sessions, or must stay fresh.**

---

## 9. Failure modes & debugging

### 9.1 Context overflow / silent truncation

- **Symptom:** API 400 `context_length_exceeded`, or worse, the agent "forgets" the system prompt / earliest instructions with no error (silent client-side truncation).
- **Diagnose:** log per-call token counts; alert when prompt > X% of window. Check whether your client truncates silently.
- **Fix:** compaction/window/retrieval; raise budget; prune tool schemas (they're heavy).

### 9.2 "Forgot what I told it" (recall failure)

- **Symptom:** user gave a fact early; agent doesn't use it later.
- **Diagnose:** Was the turn evicted by the window? Summarized away? In the store but not retrieved (retrieval recall miss)? Inspect the assembled prompt and the retrieval results+scores for that turn.
- **Fix:** keep more verbatim; improve summary prompt to preserve facts; tune retrieval (`k`, reranking, query rewriting, chunking); store facts as curated semantic memory, not just buried in transcript.

### 9.3 Stale / contradictory memory

- **Symptom:** agent insists on outdated info (old address, old price).
- **Diagnose:** check stored memories' timestamps; look for multiple conflicting entries for the same fact (append-without-update smell).
- **Fix:** update-not-append policy; recency-weighted scoring; TTLs; prefer live source-of-truth over cached memory for volatile facts.

### 9.4 Cross-user / cross-tenant leakage

- **Symptom:** agent reveals another user's data.
- **Diagnose:** check whether retrieval filters on `user_id`/tenant; audit a retrieval that returned foreign data.
- **Fix:** mandatory metadata filtering enforced server-side; per-tenant namespaces/indexes; authorization on the memory API. This is a security incident, not just a bug.

### 9.5 Resume failures & duplicate side effects

- **Symptom:** after a crash/restart, agent restarts from scratch, or re-sends an email/charges a card twice.
- **Diagnose:** is the checkpointer actually durable (not `MemorySaver` in prod)? Is the side effect ordered after its checkpoint? Are idempotency keys used? Are concurrent runs racing on one `thread_id`?
- **Fix:** durable checkpointer (Postgres/Redis); checkpoint-then-act ordering; idempotency keys; per-thread serialization/locking.

### 9.6 Retrieval returns irrelevant junk

- **Symptom:** injected context is off-topic, answers get worse.
- **Diagnose:** inspect retrieved chunks + similarity scores; check embedding model mismatch, bad chunking, missing rerank, or a non-self-contained query ("the other one").
- **Fix:** rerank; query rewriting; better chunking/contextual retrieval; hybrid search; raise similarity threshold (drop low-score hits).

### 9.7 Cost/latency blowup on long agents

- **Symptom:** a 30-step agent costs/latency explodes mid-run.
- **Diagnose:** is the full transcript re-sent every step (quadratic growth)? Are tool outputs huge (e.g., a 50k-token API dump) sitting in context every subsequent step?
- **Fix:** compact tool outputs (store full result externally, keep a summary/handle in context); offload + retrieve; prompt caching for the stable prefix.

### 9.8 Real-world incident shapes (illustrative)

- **The doubled refund:** an agent crashed after issuing a refund but before checkpointing the "done" state; on resume it issued it again. Root cause: act-before-checkpoint, no idempotency key. Fix: idempotency keys + checkpoint ordering.
- **The poisoned memory:** an agent stored a scraped web page into long-term memory; the page contained injected instructions; weeks later that memory was retrieved and the agent followed the injected instruction. Fix: treat retrieved content as untrusted data; sanitize; don't auto-execute instructions from memory.
- **The 1M-token bill:** a "just paste the whole codebase" agent re-sent 800k tokens on every one of 60 steps; the run cost orders of magnitude more than expected. Fix: retrieval over the codebase + prompt caching.

---

## 10. Interview drill

**Q1. Why does an LLM-based agent need a separate memory system at all?**
*Model answer:* The LLM is stateless — each API call only "knows" the tokens you send it; it retains nothing between calls. Any continuity (within or across sessions), any knowledge larger than the context window, and any ability to pause/resume long work must be provided by an external layer that decides what to re-send, store, retrieve, and persist.
- *Follow-up: So with a 1M-token window, is memory still needed?* Yes — cost scales with tokens, latency scales with prefill, and accuracy degrades (lost-in-the-middle); cross-session persistence and resumability still require external storage regardless of window size.
- *Follow-up: Where is the "memory" physically?* In the `messages` array you construct (short-term) and in external stores — DB/KV/vector store (long-term) and checkpoint storage (execution state).

**Q2. Contrast short-term vs long-term, and episodic vs semantic vs procedural memory.**
*Model answer:* Short-term = task/session-scoped, usually in the prompt or a per-session object, volatile. Long-term = persisted across sessions in external durable storage. Orthogonally: episodic = specific timestamped events; semantic = general facts/profile; procedural = learned how-to/workflows. The two axes cross-cut (e.g., a vector store of past chats is long-term episodic; a user-profile row is long-term semantic; an updated system prompt is procedural).
- *Follow-up: How would you store each of the three content types?* Episodic → timestamped vector store; semantic → curated, updated-in-place fact/profile store; procedural → system-prompt edits / few-shot examples / tool macros via reflection.
- *Follow-up: Which improves an agent at recurring tasks?* Procedural, via reflection after tasks.

**Q3. How do you keep a long conversation inside the context window?**
*Model answer:* Layer three techniques: a verbatim window of recent turns (precision), a rolling LLM summary of older turns (gist at low token cost), and an offloaded full archive in a vector store for on-demand retrieval (lossless recall of specifics). Trigger compaction on a token threshold; keep tool-call/result pairs intact when evicting.
- *Follow-up: What's lost with summarization and how do you mitigate it?* Exact quotes/numbers; mitigate by keeping recent verbatim and retrievable full archive.
- *Follow-up: Where do you place retrieved context in the prompt and why?* Toward top or bottom to dodge lost-in-the-middle; stable prefix first to maximize prompt-cache hits.

**Q4. Walk me through the read and write paths of long-term semantic memory.**
*Model answer:* Write: extract durable fact → (chunk if doc) → embed → upsert `{id, vector, text, metadata}` → index in ANN. Read: form/rewrite query → embed with the *same* model → ANN top-k with metadata filters → optional rerank → inject into prompt. Key correctness rule: same embedding model on both paths; version it.
- *Follow-up: Why a vector store and not just SQL `LIKE`?* Semantic similarity ≠ keyword match; embeddings + ANN find meaning-similar memories efficiently; hybrid adds keyword recall.
- *Follow-up: What is reranking and when do you use it?* A cross-encoder rescoring of top candidates for precision; use it when retrieval quality matters more than the extra latency.

**Q5. (Senior signal) When would you choose retrieval over a large context window, and vice versa?**
*Model answer:* Paste into the window when context is small, one-off, and simplicity matters. Retrieve when the corpus is large, growing, shared across sessions, or must stay fresh — because re-sending everything every turn is costly (often near-quadratic in long loops), slow, and accuracy-degrading. Big windows are headroom, not a substitute for memory management.
- *Follow-up: How does prompt caching change the math?* It discounts a stable prefix (~90% on hits), making "paste the stable context once" cheaper — so order prompts stable-first; but it doesn't help volatile/growing content.
- *Follow-up: Cost shape of a 60-step agent that re-sends full transcript?* Roughly quadratic token growth; fix via compaction/offload + caching.

**Q6. Explain checkpointing and how you'd make an interrupted agent resumable.**
*Model answer:* Serialize the agent's full execution state to durable storage at safe points (e.g., after each step/superstep), keyed by a thread/run ID; to resume, load the latest checkpoint and re-enter at that position. Use a durable backend (Postgres/Redis, not in-memory) in prod. LangGraph does this per superstep with pluggable checkpointers and supports interrupt/resume for HITL and time travel.
- *Follow-up: How do you avoid duplicate side effects on resume?* Order side effects after their checkpoint and use idempotency keys; consider event-sourcing recording results rather than re-executing on replay.
- *Follow-up: Two requests hit the same thread_id concurrently — what happens?* Risk of lost updates/corruption; serialize per-thread (lock/queue) or use optimistic concurrency with versioned checkpoints.

**Q7. (Senior signal) Design memory for a multi-tenant customer-support agent serving millions of users.**
*Model answer:* Per-tenant/user namespacing with server-enforced metadata filtering (security boundary). Short-term: windowed buffer + rolling summary per conversation, persisted (Redis/DB) keyed by conversation ID. Long-term: semantic profile store (updated-in-place, deduped) + episodic vector store of past tickets (timestamped, TTL'd). Retrieval: hybrid search + rerank, recency-weighted. Execution: durable checkpointer for resumable/HITL flows. Hardening: encryption at rest, right-to-be-forgotten deletes, prompt-injection defenses on stored external content, prompt caching for the stable prefix, observability on tokens/retrieval/staleness.
- *Follow-up: How do you prevent cross-tenant leakage?* Mandatory server-side tenant filter on every retrieval, per-tenant namespaces/indexes, authz on the memory API, tests that assert isolation.
- *Follow-up: How do you control unbounded store growth?* TTLs, importance scoring, consolidation jobs (merge episodic→semantic), capacity caps with decay-based eviction.

**Q8. What is the staleness problem and how do you handle conflicting memories?**
*Model answer:* Stored memory drifts from reality (user moved, price changed). Handle via update-not-append (detect contradiction, supersede/expire the old fact), timestamps + recency-weighted retrieval, TTLs, and preferring a live source-of-truth for volatile facts over cached memory.
- *Follow-up: How do you detect a contradiction at write time?* Retrieve the nearest existing memory and have the model/heuristic judge if the new fact supersedes it; if so, expire the old.
- *Follow-up: Append-only seems simpler — why not?* It accumulates contradictory entries, retrieval returns both, and the agent gets confused/uses stale info.

**Q9. (Senior signal) You're told to "just use a million-token window and skip RAG." Argue your position.**
*Model answer:* For small, one-off context it's fine and simpler. But as a general policy it's a mistake: you pay per token on every call (huge at scale and in long loops), latency rises with prefill, and accuracy degrades on scattered facts (lost-in-the-middle, weak multi-needle recall). It also doesn't address cross-session persistence, freshness, or shared memory. The pragmatic answer: use the big window as headroom and prompt caching for stable prefixes, but still manage memory (compaction + retrieval) for large/growing/shared/fresh knowledge.
- *Follow-up: Any case where big-window genuinely wins over RAG?* Tight reasoning over a medium document where chunking would fragment cross-references, or when retrieval recall is the bottleneck — paste it (cached) instead.
- *Follow-up: How do you decide empirically?* Measure recall@k of your retrieval vs accuracy of full-context on a labeled eval set, plus cost/latency; pick per workload.

**Q10. How do you make memory logic testable given nondeterministic LLMs?**
*Model answer:* Isolate deterministic parts — buffering, eviction (incl. pair-preservation), token counting, retrieval ranking, checkpoint serialization — into pure functions and unit-test them with stubbed models. For retrieval quality, use labeled recall@k/precision@k evals. For resumability, kill-and-resume integration tests asserting state restoration and no duplicate side effects.
- *Follow-up: How do you test summarization quality?* Synthetic long conversations with planted facts; assert the planted facts survive into later answers (LLM-as-judge or exact-match probes).
- *Follow-up: How do you regression-test embeddings?* Pin model+version; snapshot expected top-k for a fixed query set; alert on drift; re-embed on intentional model upgrades.

---

## 11. Glossary

- **Agent:** an LLM-driven loop that observes, decides, acts via tools, observes results, and repeats toward a goal.
- **ANN (Approximate Nearest Neighbor):** fast, slightly-inexact nearest-neighbor search over vectors.
- **BM25:** classic keyword-based relevance ranking used in lexical/hybrid search.
- **Bi-encoder:** encodes query and document separately into embeddings (enables pre-indexing); contrast cross-encoder.
- **Checkpoint:** durable snapshot of an agent's execution state for resume/HITL/time-travel.
- **Chunking:** splitting long text into embeddable pieces (with overlap) for retrieval.
- **Compaction:** compressing accumulated context (e.g., via summarization) into fewer tokens.
- **Context window:** max tokens a model can attend to in one call (input + output budget).
- **Contextual retrieval:** prepending an LLM-generated context blurb to each chunk before embedding to improve recall.
- **Cross-encoder / re-ranker:** scores (query, doc) pairs jointly for high-precision reranking.
- **Durable execution:** infrastructure (e.g., Temporal) that makes long-running code crash-proof via event-logged replay.
- **Embedding:** fixed-length vector capturing the meaning of text; similar meaning → nearby vectors.
- **Episodic memory:** specific, timestamped past events/experiences.
- **Event sourcing:** persisting state as an append-only event log, deriving current state by replay.
- **HNSW:** a graph-based ANN index; tuned via `M` and `efSearch`.
- **Human-in-the-loop (HITL):** pausing the agent for human approval/edit at sensitive steps.
- **Hybrid search:** fusing lexical (BM25) and semantic (vector) retrieval rankings.
- **Idempotency:** an operation safe to repeat without extra effect; vital for resume safety.
- **IVF (Inverted File):** cluster-based ANN index; searches only nearest clusters.
- **KV cache / prompt caching:** persisting the model's key/value tensors for a stable prefix to skip recomputation (cheaper/faster).
- **LangGraph:** library modeling agents as checkpointed state graphs; first-class persistence/HITL/time-travel.
- **Lost in the middle:** LLMs attend best to context start/end, worst to the middle.
- **MemGPT / Letta:** tiered "OS-for-LLMs" memory where the model self-pages between core and archival memory.
- **Procedural memory:** learned how-to/workflows/skills (often system-prompt or few-shot edits).
- **RAG (Retrieval-Augmented Generation):** retrieve relevant text and inject it into the prompt before generation.
- **ReAct:** interleaving reasoning, actions (tool calls), and observations in the agent loop.
- **Reflection / Reflexion:** the agent critiques itself post-task and writes lessons to memory to improve next time.
- **Role:** message tag — system/user/assistant/tool — structuring the prompt.
- **Scratchpad / working memory:** the agent's in-prompt space for intermediate reasoning and tool outputs.
- **Semantic memory:** general facts/knowledge independent of when learned.
- **Staleness:** stored memory no longer matching reality.
- **Superstep:** a discrete batch-execute-then-merge step in graph computation; LangGraph checkpoints at superstep boundaries.
- **Temporal:** a durable-execution workflow engine used to host crash-proof long-running agents.
- **Token:** the atomic text unit an LLM reads/writes; the unit of cost and context limits.
- **TTL (time-to-live):** expiry duration bounding staleness and storage growth.
- **Vector store / vector database:** stores embeddings and answers nearest-neighbor queries via ANN.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **Core truth:** the LLM is stateless. "Memory" = what *you* assemble into the prompt + what you persist outside. Mental model: LLM = CPU with no RAM/disk; window = registers; external stores = RAM/disk; checkpoint = process snapshot.
- **Two axes:** duration (short-term vs long-term) × content (episodic / semantic / procedural). They cross-cut.
- **Short-term strategies (escalating):** full buffer → sliding window → summarization/compaction → retrieval over history → **hybrid (window + summary + retrieval)** for production.
- **Long-term = RAG over your own data:** write path = extract → chunk → embed → upsert → index; read path = (rewrite) query → embed (same model!) → ANN top-k + metadata filter → rerank → inject.
- **Vector basics:** embedding dims 384–3072; float32 = 4 B/dim (1536-d ≈ 6 KB); index HNSW (`M`, `efSearch`); metric cosine; `k`≈4–10; quantize at scale.
- **Policies:** write eager vs curated(+extraction)+dedup/supersede; read always-on vs tool-gated. Fight **staleness** with timestamps, recency weighting, TTLs, update-not-append, live source-of-truth.
- **Execution state:** checkpoint per step keyed by thread_id; durable backend in prod (not in-memory). LangGraph: `compile(checkpointer=...)`, `thread_id`, `get_state`/`get_state_history`/`update_state`, `interrupt`/`Command(resume=...)`. Idempotency + checkpoint-then-act prevents double side effects.
- **Cost/latency:** prompt length dominates; re-sending full transcript per step ≈ quadratic; **prompt caching** discounts the stable prefix → put stable stuff first, volatile last.
- **Pitfalls:** lost-in-the-middle, embedding model mismatch, append-only contradictions, cross-tenant leakage, in-memory-only prod state, secrets in checkpoints, prompt injection via stored content.
- **Decision rule:** paste when small/one-off; retrieve when large/growing/shared/fresh. Big window = headroom, not an excuse to skip memory management.

### Self-test (no answers — active recall)

1. A 50-step agent re-sends its full transcript every step and the bill is 30× your estimate. Explain the cost shape and give two concrete fixes that don't change the agent's behavior.
2. Design the read and write paths for a per-user long-term memory store, and name three correctness/security rules you must enforce on each path.
3. Your agent "forgot" a fact the user gave at turn 4 when answering at turn 80. Enumerate every layer where that fact could have been lost and how you'd diagnose each.
4. Explain why an append-only long-term memory leads to wrong answers over time, and describe the write policy you'd implement instead, including how you detect a contradiction at write time.
5. An agent crashed right after charging a customer but before recording success, and on resume it charged them again. Identify the two design flaws and the fix for each, then explain how LangGraph's checkpoint/interrupt model would have prevented it.
6. (Senior signal) You're handed a 1M-token-window model and told to delete the RAG pipeline. Make the case for what you keep, what you remove, and how you'd measure whether the decision was right.

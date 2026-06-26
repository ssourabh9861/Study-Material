# Reranking & Evaluation

> A definitive engineering-handbook chapter on the two-stage retrieve-then-rerank pattern, reranker model families, and how to evaluate Retrieval-Augmented Generation (RAG) pipelines rigorously — offline and online. Written for a senior JVM-backend engineer who wants to design, operate, debug, and teach this material.

---

## 1. Overview & where it fits

### 1.1 The one-paragraph mental model

When you build a system that answers questions over your own documents — a support bot, an internal knowledge search, a code assistant — you almost always use **retrieval** to find candidate text and then a **Large Language Model (LLM)** to read those candidates and produce an answer. The quality of the final answer is bounded by the quality of what you retrieve: if the right passage never reaches the LLM, no amount of prompt engineering fixes it. *Reranking* is the act of taking a cheap, high-recall first pass that returns (say) 100 candidate passages and applying a more expensive, more accurate model to **reorder** them so the truly relevant passages float to the top — you then keep only the top 3–10 to feed the LLM. *Evaluation* is the discipline of measuring whether your retrieval, your reranking, and your generation are actually good — using metrics (precision/recall, faithfulness, answer relevance), test sets, and increasingly an LLM acting as a "judge." Together, reranking and evaluation are the two levers that move a RAG demo from "works on the three queries I tried" to "works in production at p95 latency on real traffic."

### 1.2 What problem does reranking solve?

A **vector search** (also called *semantic search* or *dense retrieval*) embeds your query and your documents into numeric vectors and finds the documents whose vectors are closest to the query's vector. It is fast and scalable but **lossy**: the entire meaning of a 500-token passage is squashed into a single fixed-size vector (commonly 384, 768, or 1536 numbers), and the same is done independently for the query. Two pieces of text that *should* match can land far apart in vector space, and two that *shouldn't* match can land close. The result: first-stage retrieval has good **recall** (the right answer is usually *somewhere* in the top 100) but mediocre **precision at the top** (the right answer is often not in the top 3).

A **reranker** fixes the ordering. Instead of comparing two independent vectors, it looks at the query and a candidate passage **together**, in the same model forward pass, and outputs a single relevance score. Because it can attend to the actual words of both at once, it is far more accurate at judging "does this passage really answer this query?" The cost is that you must run the model once per (query, passage) pair, so you can only afford it on a shortlist — hence the two-stage design.

> **Recall vs precision (define now):** *Recall* = of all the truly relevant documents, what fraction did you retrieve? *Precision* = of the documents you retrieved, what fraction are truly relevant? First-stage retrieval optimizes recall (cast a wide net); reranking optimizes precision-at-the-top (tighten the net). You generally cannot have both cheaply, which is exactly why the pattern is two-stage.

### 1.3 When do you reach for it?

- **Your RAG answers are "almost right" but cite the wrong passage,** or miss a passage you *know* is in the index. The relevant chunk is in the top 50 but not the top 5. Reranking is the highest-leverage fix.
- **You feed too many chunks to the LLM** to compensate for poor ordering, blowing up token cost and latency and triggering the "lost-in-the-middle" effect (LLMs attend poorly to context buried in the middle of a long prompt). A reranker lets you feed *fewer, better* chunks.
- **You combine multiple retrievers** (dense + keyword/BM25, or multiple indices) and need one principled way to merge and order their outputs.
- **You need to *prove* a change is an improvement.** That is the evaluation half: without an eval harness, "I added a reranker" is a vibe, not an engineering claim.

### 1.4 Where it sits in the pipeline

```
                 ┌─────────────────────────────────────────────────────┐
                 │                  Ingestion (offline)                  │
                 │  documents → chunk → embed → upsert into vector store │
                 └─────────────────────────────────────────────────────┘

  query
    │
    ▼
┌───────────┐   top-k (k≈50–200)   ┌────────────┐   top-n (n≈3–10)   ┌──────────┐
│ Stage 1:  │ ───────────────────▶ │ Stage 2:   │ ─────────────────▶ │  LLM     │ ──▶ answer
│ Retrieval │   high recall,       │ Reranker   │   high precision   │ generate │
│ (cheap)   │   coarse order       │ (expensive)│   tight order      │          │
└───────────┘                      └────────────┘                    └──────────┘
       ▲                                                                   │
       │                              ┌─────────────────────────────┐      │
       └──────────────────────────────│  Evaluation harness (RAGAS, │◀─────┘
                                       │  LLM-as-judge, offline+online)│
                                       └─────────────────────────────┘
```

Reranking is the **second stage of retrieval**; evaluation wraps the **whole pipeline** (and each stage individually).

---

## 2. Foundations from first principles

This section assumes you know what an LLM and an API call are, but builds everything else from zero.

### 2.1 Embeddings and vector search (the first stage)

An **embedding** is a function that maps a piece of text to a fixed-length vector of floating-point numbers such that *semantically similar* texts map to *nearby* vectors. "Nearby" is measured by a **similarity metric**:

- **Cosine similarity** — the cosine of the angle between two vectors; ranges from −1 (opposite) to 1 (identical direction). Ignores magnitude, cares only about direction. The most common metric for text embeddings.
- **Dot product (inner product)** — like cosine but sensitive to magnitude; used when embeddings are *not* normalized to unit length.
- **Euclidean (L2) distance** — straight-line distance; smaller is more similar. Equivalent to cosine when vectors are unit-normalized.

A **vector database / vector store** (Pinecone, Weaviate, Qdrant, Milvus, pgvector, Elasticsearch/OpenSearch dense vectors, Redis) stores millions of these vectors and answers the query "give me the k nearest vectors to this query vector" using an **Approximate Nearest Neighbor (ANN)** index.

> **ANN (define now):** Computing the *exact* nearest neighbors means comparing the query to every stored vector — O(N) per query, too slow at scale. ANN algorithms (most commonly **HNSW** — Hierarchical Navigable Small World graphs, and **IVF** — Inverted File index) trade a tiny amount of accuracy for a huge speedup, returning *approximately* the nearest neighbors in roughly O(log N). HNSW builds a layered graph you "walk" toward the query; IVF clusters vectors and only searches the nearest clusters. The key tuning knob is *how hard you search* (e.g. HNSW's `ef_search`): higher = more accurate, slower.

### 2.2 Bi-encoders vs cross-encoders (the central distinction)

This is the most important concept in the chapter. Both are neural models (usually **Transformers** — the architecture behind BERT and GPT), but they differ in *when* they combine the query and the document.

**Bi-encoder (dual encoder, two-tower model).** Encodes the query and the document **separately** into two vectors, then compares them with a cheap similarity (cosine/dot). Because the document encoding does not depend on the query, you can **precompute and index all document vectors offline**. At query time you only embed the query once and do fast vector math. This is what powers first-stage retrieval. Examples: OpenAI `text-embedding-3`, `sentence-transformers` models (`all-MiniLM-L6-v2`, `all-mpnet-base-v2`), Cohere `embed`, BGE embeddings (`bge-base-en`, `bge-large-en`).

```
  Bi-encoder:
     query ─▶ [Encoder] ─▶ q_vec ┐
                                  ├─▶ cosine(q_vec, d_vec) ─▶ score
  document ─▶ [Encoder] ─▶ d_vec ┘   (d_vec precomputed offline)
```

**Cross-encoder.** Concatenates the query and the document into a single input (`[CLS] query [SEP] document [SEP]`) and runs them through **one** Transformer, letting every query token attend to every document token. Outputs a single relevance score. Far more accurate because it models the *interaction* between query and document — but you **cannot precompute** anything, because the document's representation depends on the specific query. You must run a full forward pass per (query, document) pair at query time. This is what powers reranking. Examples: `cross-encoder/ms-marco-MiniLM-L-6-v2`, Cohere Rerank, BGE reranker (`bge-reranker-base`, `bge-reranker-large`, `bge-reranker-v2-m3`), Jina Reranker, Voyage `rerank`, Mixedbread `mxbai-rerank`.

```
  Cross-encoder:
     [CLS] query [SEP] document [SEP] ─▶ [Single Transformer w/ full cross-attention] ─▶ score
```

The trade-off in one line:

| Property | Bi-encoder | Cross-encoder |
|---|---|---|
| Combines query & doc | Late (after encoding) | Early (full attention) |
| Doc vectors precomputable? | **Yes** (index offline) | **No** (per-query) |
| Cost at query time | Embed query once + ANN search | One forward pass **per candidate** |
| Accuracy (ranking quality) | Good recall, coarse order | Excellent precision |
| Scales to millions of docs? | Yes (that's its job) | No — only to a shortlist (tens to low hundreds) |
| Role in pipeline | Stage 1 retrieval | Stage 2 reranking |

> **Why "two towers"?** A bi-encoder is sometimes drawn as two parallel encoder "towers" (one for query, one for document) that never touch until the final dot product. A cross-encoder is "one tower" the joined input flows through.

### 2.3 A third option: late-interaction models (ColBERT)

Between the two extremes sits **late interaction**, exemplified by **ColBERT**. Instead of one vector per document, ColBERT stores **one vector per token**. At query time it computes, for each query token, the maximum similarity against all document token vectors, then sums (the **MaxSim** operation). This captures much of the cross-encoder's fine-grained matching while keeping document representations **precomputable** (you still index per-token vectors). The cost is storage (many vectors per doc) and a more complex index. ColBERT is often used as a *better first stage* or a *cheaper reranker*. We will return to it in §7.

### 2.4 The two-stage retrieve-then-rerank pattern, formally

1. **Stage 1 — Retrieve (recall-oriented):** embed the query, run ANN search over the vector store (and/or BM25 keyword search), return top-`k` candidates where `k` is large (commonly 50–200). Goal: the right passage is *somewhere* in this set.
2. **Stage 2 — Rerank (precision-oriented):** for each of the `k` candidates, run the cross-encoder on (query, candidate) to get a relevance score; sort descending; keep the top-`n` (commonly 3–10). Goal: the right passage is *at the top* and the noise is gone.
3. **Generate:** feed the top-`n` passages plus the query into the LLM to produce the grounded answer.

> **Why not just rerank everything?** Because reranking is O(k) forward passes. Reranking 1,000,000 documents per query is absurd; reranking 100 is a few hundred milliseconds. The first stage exists precisely to shrink the candidate set to a rerankable size.

### 2.5 BM25 and hybrid search (the keyword half)

**BM25** (Best Match 25) is the classic **lexical/keyword** ranking function used by Lucene/Elasticsearch/OpenSearch. It scores a document by term frequency (how often query words appear) and inverse document frequency (rarer words count more), with length normalization. It has *no* notion of semantics — "automobile" and "car" are unrelated to BM25 — but it is exact, cheap, and unbeatable for rare tokens, codes, IDs, and exact-phrase matches.

**Hybrid search** runs both dense (vector) and sparse (BM25) retrieval and fuses the results, most commonly with **Reciprocal Rank Fusion (RRF)**:

```
RRF_score(d) = Σ over retrievers r of  1 / (k_rrf + rank_r(d))
```

where `rank_r(d)` is the document's rank in retriever `r`'s list and `k_rrf` is a constant (default **60** in many implementations). RRF only needs ranks, not comparable scores, so it fuses heterogeneous retrievers cleanly. **A reranker is the natural top of a hybrid stack:** dense + BM25 → fuse → rerank → top-n.

### 2.6 RAG, recapped

> **RAG (Retrieval-Augmented Generation):** an LLM answering pattern where, instead of relying on the model's parametric memory, you *retrieve* relevant text at query time and put it in the prompt as context. Reduces hallucination, lets you answer over private/fresh data, and makes answers citable. RAG quality = retrieval quality × generation quality. Reranking improves the retrieval factor; evaluation measures both.

---

## 3. How it works internally — step by step

This is the heart of the chapter. We trace control and data flow for (a) a cross-encoder reranker forward pass, (b) the full two-stage request lifecycle, and (c) the evaluation harness.

### 3.1 Inside a cross-encoder reranker forward pass

Take `cross-encoder/ms-marco-MiniLM-L-6-v2` (a 6-layer distilled BERT) reranking one (query, passage) pair.

1. **Tokenization.** The pair is tokenized into subword tokens (WordPiece/BPE) and assembled as `[CLS] q_1 q_2 … [SEP] d_1 d_2 … [SEP]`. A **token-type / segment id** marks which side each token belongs to (0 for query, 1 for document). The sequence is truncated to the model's max length (commonly **512 tokens**); overlong documents are cut, which matters — see §9.
   > **`[CLS]`/`[SEP]` (define):** special tokens. `[CLS]` ("classification") is a slot whose final hidden state summarizes the whole input; `[SEP]` separates segments. The relevance head reads the `[CLS]` representation.
2. **Embedding lookup + positional encoding.** Each token id becomes a vector via an embedding table; positional encodings are added so the model knows token order.
3. **Self-attention across all layers.** Every token attends to every other token — crucially, **query tokens attend to document tokens and vice versa**. This *cross-attention* is what a bi-encoder fundamentally cannot do, and is the source of the accuracy gain. Each layer = multi-head self-attention + feed-forward network + residual connections + layer normalization.
4. **Pooling / head.** The final hidden state of `[CLS]` is fed to a small linear head producing a **single logit** (a raw score). Some models apply a sigmoid to map it to [0,1]; many (including ms-marco cross-encoders) emit an **unbounded logit** where only the *relative ordering* is meaningful — do not interpret a raw `-7.2` as "0% relevant."
5. **Score returned.** Repeat for all `k` candidates (batched on GPU/CPU), then sort by score.

**Computational cost.** Roughly O(L²·H) per pair where L is sequence length and H is hidden size (the L² is the attention matrix). Doubling sequence length quadruples attention cost. This is why rerankers cap candidates and truncate long passages, and why latency is dominated by `k` × (passage length).

### 3.2 The full two-stage request lifecycle

```
T0  Request arrives: { query, top_n=5 }
T1  Embed query (bi-encoder)                      ~5–30 ms (or network call to embedding API)
T2  ANN search vector store, k=100                ~5–50 ms (HNSW)
T2' (optional) BM25 search, k=100                 ~5–30 ms   ─┐ run in parallel with T1–T2
T3  (optional) RRF-fuse dense + sparse → 100–150 candidates  ─┘ ~1 ms
T4  Dedupe / collapse near-duplicate chunks       ~1 ms
T5  Rerank 100 candidates with cross-encoder      ~50–500 ms  ◀── usually the latency hot spot
T6  Take top_n=5, optionally apply score threshold
T7  Assemble prompt (query + 5 passages + instructions)
T8  LLM generation (streamed)                     ~500 ms–several s (dominates wall clock)
T9  Post-process: attach citations, log scores, emit telemetry
```

Two things to internalize: **(1)** the reranker is the slowest *retrieval* step but generation usually dwarfs everything; **(2)** every step should emit telemetry (candidate ids, scores, latencies) because that telemetry *is* your online evaluation data (§6).

### 3.3 State and where it lives

- **Stage 1 is stateful offline:** the vector index and BM25 index are built during ingestion and persisted. At query time they're read-only.
- **Stage 2 is stateless:** the reranker holds no per-request state; it's a pure function (query, candidates) → ordered candidates. This makes it trivially horizontally scalable — spin up N replicas behind a load balancer.
- **Evaluation is stateful over time:** your eval set (the golden questions/answers/relevant-docs) is versioned data; your eval *runs* produce metric time series you compare across pipeline versions.

### 3.4 The evaluation harness, internally

An offline RAG evaluation run is a batch pipeline:

1. **Load the eval set** — a list of records, each with at least a `question`, and depending on the metric: a `ground_truth` answer, and/or a set of `relevant_contexts` (the chunk ids/text a human marked as relevant).
2. **Run the pipeline under test** on each question, capturing the *full trace*: retrieved chunks, reranked order, the contexts actually sent to the LLM, and the generated `answer`.
3. **Compute retrieval metrics** from (relevant_contexts, retrieved order): Recall@k, Precision@k, MRR, NDCG, Hit Rate (defined in §3.5).
4. **Compute generation/RAG metrics** — RAGAS-style faithfulness, answer relevance, context precision/recall — many of which call an LLM internally to judge (§5).
5. **Aggregate** to dataset-level means (and percentiles), store the run with a version tag, and **diff against the previous run** (regression gate).

### 3.5 Ranking metrics — the math, plainly

These measure the *ordering* a retriever/reranker produces. Let the system return a ranked list; mark each item relevant (1) or not (0) per the ground truth.

- **Hit Rate @k / Recall@k:** did *any* relevant doc appear in the top k? (Hit Rate is the binary "at least one"; Recall@k is the fraction of all relevant docs found in top k.) Range [0,1], higher better.
- **Precision@k:** of the top k, what fraction are relevant.
- **MRR (Mean Reciprocal Rank):** `mean(1 / rank_of_first_relevant)`. If the first relevant doc is at rank 1, contributes 1.0; rank 2 → 0.5; rank 5 → 0.2. Rewards getting *one* right answer to the very top. Great single number for "did the best chunk reach position 1?"
- **NDCG@k (Normalized Discounted Cumulative Gain):** the gold-standard graded-relevance metric. **DCG** sums each item's relevance discounted by a log of its position (`rel_i / log2(i+1)`), so relevant items lower down count less; **NDCG** divides DCG by the ideal DCG (the best possible ordering) so it's normalized to [0,1]. Use when relevance is graded (e.g. 0/1/2/3), not just binary.

> **Why reranking shows up most in MRR and NDCG.** First-stage retrieval already gets Recall@100 high; what the reranker improves is *where in the list* the relevant docs land. MRR and NDCG are position-sensitive, so they're the metrics that *light up* when a reranker works. Recall@k often barely moves (the docs were already retrieved); precision@small-k, MRR, and NDCG move a lot.

---

## 4. The complete toolkit

### 4.1 Reranker models and services

| Model / Service | Type | Hosting | Notable specs | Notes |
|---|---|---|---|---|
| **Cohere Rerank** (`rerank-english-v3.0`, `rerank-multilingual-v3.0`, and newer v3.5) | Cross-encoder (proprietary) | API (managed); also via AWS Bedrock, Azure | Handles long docs via internal chunking; multilingual variant | Strong default for managed reranking; pay per 1k searches. Verify exact model names/limits against current Cohere docs — they version frequently. |
| **BGE reranker** (`BAAI/bge-reranker-base`, `bge-reranker-large`, `bge-reranker-v2-m3`, `bge-reranker-v2-gemma`) | Cross-encoder (open weights) | Self-host (HF Transformers / FlagEmbedding) | base ≈ small/fast, large ≈ more accurate; v2-m3 multilingual; v2-gemma LLM-based | Best open-source option; self-host on GPU for cost control. Apache-friendly licensing (check current license per model). |
| **`cross-encoder/ms-marco-MiniLM-L-6-v2`** (and L-12) | Cross-encoder (open) | Self-host (sentence-transformers) | 6 or 12 layers, max 512 tokens, English | Classic lightweight baseline; CPU-runnable; emits unbounded logits. |
| **Jina Reranker** (`jina-reranker-v2-base-multilingual`) | Cross-encoder | API + open weights | Multilingual, long context, fast | Competitive; strong on code/function-calling variants. |
| **Voyage `rerank`** (`rerank-2`, `rerank-2-lite`) | Cross-encoder | API | Long context | Often paired with Voyage embeddings. |
| **Mixedbread `mxbai-rerank`** (`mxbai-rerank-large-v1`, etc.) | Cross-encoder (open) | Self-host + API | English | Strong open option. |
| **ColBERT / RAGatouille** (`colbert-ir/colbertv2.0`) | Late interaction | Self-host | Per-token vectors, MaxSim | Cheaper-than-cross-encoder reranking; can also be a first stage. |

> All specifics (exact model names, max token limits, prices, latencies) are **version/vendor-specific and change often** — treat the table as a map, confirm numbers against current provider docs before committing.

### 4.2 Key reranker API parameters (provider-agnostic)

| Parameter | Purpose | Typical default / range |
|---|---|---|
| `query` | The search query string | required |
| `documents` | The candidate passages to score | required; keep to tens–low hundreds |
| `top_n` / `top_k` | How many ranked results to return | commonly 3–10 returned; you *send* 50–200 |
| `model` | Which reranker | required (e.g. `rerank-english-v3.0`) |
| `max_chunks_per_doc` / truncation | How long documents are split/truncated internally | provider-specific; long docs get chunked |
| `return_documents` | Echo back the doc text with scores | often `false` to save bandwidth |

### 4.3 Self-hosted inference knobs (HF Transformers / sentence-transformers)

| Knob | Effect | Default-ish |
|---|---|---|
| `batch_size` | Pairs per forward pass; bigger = better GPU utilization, more memory | tune to GPU (e.g. 16–64) |
| `max_length` | Token cap per pair; lower = faster, may truncate context | 512 |
| precision (`fp16`/`bf16`/`int8`) | Lower precision = faster, less memory, tiny accuracy loss | fp32 default; use fp16/bf16 on GPU |
| device (`cpu`/`cuda`/`mps`) | Where it runs | cpu |
| ONNX / TensorRT / OpenVINO | Compiled runtime for lower latency | optional |

### 4.4 RAG evaluation frameworks

| Framework | Language | What it gives you | Notes |
|---|---|---|---|
| **RAGAS** | Python | Faithfulness, answer relevance, context precision/recall, and more; LLM-as-judge based | The de-facto RAG metric library; needs an LLM + embeddings configured. |
| **TruLens** | Python | "RAG triad" (context relevance, groundedness, answer relevance); feedback functions; tracing | Strong for instrumentation + dashboards. |
| **DeepEval** | Python | Pytest-style LLM eval assertions; many metrics incl. G-Eval | Fits unit-test/CI workflows. |
| **Phoenix / Arize** | Python | Tracing + eval + drift monitoring | Production observability bent. |
| **promptfoo** | Node/CLI | Declarative test matrices for prompts/models; assertions | Great for prompt regression in CI; language-agnostic via CLI. |
| **LangSmith / LangFuse** | SDKs (multi-lang) | Tracing, datasets, online eval, annotation queues | Hosted/self-host observability + eval platforms. |
| **BEIR / MTEB** | Python | Standard *retrieval* benchmarks (NDCG, recall) | For benchmarking embedding/reranker models, not your app. |

### 4.5 The RAGAS core metrics, defined

> **RAGAS = Retrieval-Augmented Generation Assessment.** A library that scores a RAG output using an LLM-as-judge plus embeddings. Its four flagship metrics:

| Metric | Question it answers | Inputs needed | Uses LLM judge? | Needs ground truth? |
|---|---|---|---|---|
| **Faithfulness** | Is every claim in the answer supported by the retrieved context? (anti-hallucination) | answer, contexts | Yes (decomposes answer into claims, verifies each against context) | No |
| **Answer Relevance** | Does the answer actually address the question (not evasive/off-topic)? | question, answer | Yes (generates synthetic questions from the answer, compares to the real question via embeddings) | No |
| **Context Precision** | Are the *relevant* contexts ranked at the top? (rewards good reranking) | question, contexts, (ground truth) | Yes | Often |
| **Context Recall** | Do the retrieved contexts contain all the info needed to produce the ground-truth answer? | contexts, ground_truth | Yes | **Yes** |

Two of these are *retrieval* metrics (context precision/recall), two are *generation* metrics (faithfulness, answer relevance). **Context precision is the metric your reranker should move the most**, because it directly measures whether relevant chunks are ranked at the top.

---

## 5. Code examples by use case

Default language is Java/JVM where relevant; reranker model hosting and RAGAS evaluation are Python-native, so those examples are Python (and called from the JVM via a service boundary, shown below). Comments flag the non-obvious lines.

### 5.1 Use case A — Java: call a hosted reranker (Cohere-style HTTP) after vector search

```java
// Two-stage retrieve-then-rerank in plain Java (java.net.http + Jackson).
// Stage 1 (vector search) is abstracted behind VectorStore; we focus on Stage 2.

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;

public class Reranker {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))   // fail fast on connect
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey = System.getenv("COHERE_API_KEY");

    /** Returns candidate indices reordered by relevance, best first. */
    public List<RankedDoc> rerank(String query, List<String> docs, int topN) throws Exception {
        var body = Map.of(
            "model", "rerank-english-v3.0",   // version-specific; confirm current name
            "query", query,
            "documents", docs,
            "top_n", topN,
            "return_documents", false          // we already hold the text locally
        );
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.cohere.com/v2/rerank"))
            .timeout(Duration.ofSeconds(5))    // bound tail latency; rerank is the hot path
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("Rerank failed " + resp.statusCode() + ": " + resp.body());

        // Response shape: { "results": [ { "index": int, "relevance_score": double }, ... ] }
        JsonNode results = json.readTree(resp.body()).get("results");
        List<RankedDoc> out = new ArrayList<>();
        for (JsonNode r : results) {
            int idx = r.get("index").asInt();              // index into the ORIGINAL docs list
            double score = r.get("relevance_score").asDouble();
            out.add(new RankedDoc(idx, docs.get(idx), score));
        }
        return out;  // already sorted best-first by the API
    }

    public record RankedDoc(int originalIndex, String text, double score) {}

    // ---- wiring it into a two-stage flow ----
    public List<RankedDoc> retrieveThenRerank(String query, VectorStore vs) throws Exception {
        List<String> candidates = vs.search(query, /*k=*/100);  // Stage 1: high recall
        return rerank(query, candidates, /*topN=*/5);           // Stage 2: precise order
    }

    interface VectorStore { List<String> search(String query, int k); }
}
```

**What matters:** send ~100 candidates, ask for top 5; the response gives indices into your *original* list (re-map carefully); set an explicit timeout because the reranker is on the critical path; don't echo documents back over the wire if you already hold them.

### 5.2 Use case B — Java + LangChain4j: reranking as a `ContentAggregator`

LangChain4j (the JVM port of LangChain) has first-class reranking via `ScoringModel` + `ReRankingContentAggregator`.

```java
// build.gradle: implementation 'dev.langchain4j:langchain4j-cohere:<version>'
import dev.langchain4j.model.cohere.CohereScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;

ScoringModel scoringModel = CohereScoringModel.builder()
        .apiKey(System.getenv("COHERE_API_KEY"))
        .modelName("rerank-english-v3.0")
        .build();

// Aggregator reranks the union of retrieved content and keeps the best ones.
ReRankingContentAggregator aggregator = ReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .minScore(0.5)     // drop weak matches below this relevance score
        .maxResults(5)     // keep top 5 after reranking
        .build();

RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
        .queryRouter(new DefaultQueryRouter(/* your EmbeddingStoreContentRetriever with k=100 */))
        .contentAggregator(aggregator)   // <-- the reranking stage drops in here
        .build();
// Attach `augmentor` to your AiService; retrieval→rerank→prompt now happens automatically.
```

**What matters:** the reranker plugs in as the **content aggregator** between retrieval and prompt assembly; `minScore` is your noise gate; `maxResults` is your `top_n`. This is the idiomatic JVM way — you don't hand-roll the HTTP call.

### 5.3 Use case C — Python: self-hosted cross-encoder reranker (no API, GPU)

```python
# pip install sentence-transformers torch
from sentence_transformers import CrossEncoder

# 6-layer distilled BERT cross-encoder; ~80MB, CPU-runnable, GPU-faster.
model = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2", max_length=512)

def rerank(query: str, candidates: list[str], top_n: int = 5):
    # Build (query, candidate) pairs — the cross-encoder scores each pair jointly.
    pairs = [(query, c) for c in candidates]
    scores = model.predict(pairs, batch_size=32)  # batch for GPU throughput
    # scores are UNBOUNDED logits; only the ordering is meaningful.
    ranked = sorted(zip(candidates, scores), key=lambda x: x[1], reverse=True)
    return ranked[:top_n]

# Stage 1 would supply `candidates` from your vector store (k≈100).
for doc, score in rerank("How do I rotate AWS access keys?", candidates):
    print(f"{score:7.3f}  {doc[:80]}")
```

**What matters:** `max_length=512` truncates long passages — chunk before reranking; scores are logits, so threshold *empirically* per model, never assume "0.5 = relevant"; batch for throughput.

### 5.4 Use case D — Python: hybrid (dense + BM25) → RRF fuse → rerank

```python
def reciprocal_rank_fusion(rank_lists: list[list[str]], k_rrf: int = 60):
    """Fuse multiple ranked lists of doc-ids by reciprocal rank. k_rrf=60 is the common default."""
    scores = {}
    for ranking in rank_lists:
        for rank, doc_id in enumerate(ranking):          # rank is 0-based
            scores[doc_id] = scores.get(doc_id, 0.0) + 1.0 / (k_rrf + rank + 1)
    return [d for d, _ in sorted(scores.items(), key=lambda x: x[1], reverse=True)]

dense_ids  = vector_store.search(query, k=100)   # semantic recall
sparse_ids = bm25_index.search(query, k=100)     # exact/keyword recall (IDs, codes, rare terms)

fused_ids = reciprocal_rank_fusion([dense_ids, sparse_ids])[:150]  # union, deduped by RRF
candidates = [doc_text[i] for i in fused_ids]
final = rerank(query, candidates, top_n=5)        # cross-encoder makes the final precise order
```

**What matters:** RRF needs only ranks (not comparable scores), so it fuses heterogeneous retrievers; the reranker is the single source of truth for the final order on top of the fused set.

### 5.5 Use case E — Python: RAGAS offline evaluation of a RAG pipeline

```python
# pip install ragas datasets
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import (faithfulness, answer_relevancy,
                           context_precision, context_recall)

# One row per eval question. `contexts` = chunks your pipeline actually sent the LLM.
data = {
    "question":     ["How do I rotate AWS access keys?"],
    "answer":       ["Run `aws iam create-access-key`, update apps, then delete the old key."],  # pipeline output
    "contexts":     [["To rotate keys, create a new key with create-access-key, "
                      "deploy it, verify, then delete the old key with delete-access-key."]],
    "ground_truth": ["Create a new access key, switch apps to it, verify, delete the old one."],  # human-written
}
ds = Dataset.from_dict(data)

result = evaluate(ds, metrics=[
    faithfulness,        # answer claims supported by contexts? (no ground_truth needed)
    answer_relevancy,    # answer addresses the question? (no ground_truth needed)
    context_precision,   # relevant contexts ranked first? <-- your RERANKER moves this
    context_recall,      # contexts contain everything ground_truth needs? (needs ground_truth)
])
print(result)  # {'faithfulness': 0.93, 'answer_relevancy': 0.91, 'context_precision': 0.88, ...}
```

**What matters:** RAGAS calls an LLM internally to judge — set your judge model/key; `context_precision` is the metric a reranker should lift; `context_recall` requires `ground_truth`. Run this on your whole eval set, not one row.

### 5.6 Use case F — measuring reranker *lift* (the A/B you actually report)

```python
# Compare top-5 retrieval quality WITH vs WITHOUT the reranker, on a labeled eval set.
import numpy as np

def mrr_at_k(ranked_ids, relevant_ids, k=5):
    for rank, doc_id in enumerate(ranked_ids[:k], start=1):
        if doc_id in relevant_ids:
            return 1.0 / rank          # reciprocal rank of first relevant hit
    return 0.0

def ndcg_at_k(ranked_ids, relevant_ids, k=5):
    dcg = sum(1.0 / np.log2(i + 2) for i, d in enumerate(ranked_ids[:k]) if d in relevant_ids)
    ideal_hits = min(len(relevant_ids), k)
    idcg = sum(1.0 / np.log2(i + 2) for i in range(ideal_hits))
    return dcg / idcg if idcg > 0 else 0.0

baseline_mrr, reranked_mrr = [], []
baseline_ndcg, reranked_ndcg = [], []
for q in eval_set:                                   # each q: {query, relevant_ids}
    cand = vector_store.search(q["query"], k=100)    # Stage-1 ids
    baseline_mrr.append(mrr_at_k(cand, q["relevant_ids"]))
    baseline_ndcg.append(ndcg_at_k(cand, q["relevant_ids"]))
    rr = [d.original_index for d in rerank(q["query"], cand_texts, top_n=5)]
    reranked_mrr.append(mrr_at_k(rr, q["relevant_ids"]))
    reranked_ndcg.append(ndcg_at_k(rr, q["relevant_ids"]))

print(f"MRR@5:  baseline {np.mean(baseline_mrr):.3f} -> reranked {np.mean(reranked_mrr):.3f}")
print(f"NDCG@5: baseline {np.mean(baseline_ndcg):.3f} -> reranked {np.mean(reranked_ndcg):.3f}")
# A real reranker commonly lifts MRR@5/NDCG@5 by ~0.05–0.20 absolute on messy corpora.
# ALWAYS report a confidence interval / significance, not a single point estimate (see §6.4).
```

**What matters:** measure the metrics that *move* (MRR, NDCG, context precision) on a *labeled* set; report **lift** (delta), not absolute numbers in isolation; check significance.

### 5.7 Use case G — Java: regression-gate the pipeline in CI (JUnit)

```java
// A regression test that fails the build if reranked retrieval quality drops below a baseline.
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class RerankRegressionTest {

    static final double MRR_FLOOR = 0.72;  // baseline locked from last good run; bump only on review

    @Test
    void rerankedRetrievalMeetsBaseline() throws Exception {
        List<EvalCase> set = EvalSet.load("eval/golden_v3.jsonl"); // versioned golden set
        double total = 0;
        for (EvalCase c : set) {
            List<String> cand = vectorStore.search(c.query(), 100);
            var ranked = reranker.rerank(c.query(), cand, 5);
            List<Integer> ids = ranked.stream().map(Reranker.RankedDoc::originalIndex).toList();
            total += reciprocalRank(ids, c.relevantIndices(), cand);
        }
        double mrr = total / set.size();
        assertTrue(mrr >= MRR_FLOOR,
            () -> "MRR@5 regressed to %.3f (floor %.3f) on golden_v3".formatted(mrr, MRR_FLOOR));
    }
    // reciprocalRank(...) maps ranked candidate indices back to relevance and returns 1/rank.
}
```

**What matters:** pin a baseline floor, version the golden set, fail the build on regression. This is how reranking/prompt changes stop silently degrading prod.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Latency budget.** Reranking adds the dominant *retrieval* latency. Self-hosted cross-encoder on CPU: roughly tens of ms per pair → reranking 100 candidates can be hundreds of ms to seconds on CPU; on GPU with batching, often <100 ms for 100 candidates. Hosted APIs add network round-trip (tens of ms) but offload compute. **Measure on your hardware** — these numbers vary wildly by model size, sequence length, and batch size.
- **Tune `k` (candidates in) and `n` (results out).** Bigger `k` = better recall but linearly more reranker cost. Common sweet spot: `k`=50–100, `n`=3–8. Diminishing returns past `k`≈100 for most corpora — verify with §5.6.
- **Batch and use accelerators.** GPU + `fp16`/`bf16` + large `batch_size` is the single biggest self-hosted speedup. Compile with ONNX Runtime / TensorRT for further gains.
- **Truncate intelligently.** A 512-token cap means very long chunks get cut. Chunk to ~200–400 tokens *before* reranking so the relevant text isn't truncated away.
- **Cache.** Cache (query, candidate)→score for hot queries; cache whole reranked lists keyed by a normalized query hash with a short TTL.
- **Parallelize stage 1.** Run dense and BM25 retrieval concurrently; their latencies overlap.

### 6.2 Correctness & concurrency

- **Index re-mapping bugs.** The #1 reranker bug: the API returns indices into your *input* list, but you sorted/deduped the list after building it. Keep candidate id ↔ text mapping immutable end-to-end.
- **Score semantics.** Cross-encoder logits are unbounded and *not* comparable across models or even versions. Never hardcode a universal threshold; recalibrate per model.
- **Statelessness.** The reranker is a pure function — safe to call concurrently and to scale horizontally. Stage-1 indices are read-only at query time; concurrency issues live in *ingestion*, not query.
- **Determinism.** GPU non-determinism and floating-point summation order can produce tiny score jitter; tie-break by stable doc id so ordering is reproducible in tests.

### 6.3 Security

- **Prompt injection via retrieved content.** Reranking does not sanitize content — a malicious document that ranks high can carry injection ("ignore previous instructions…") straight into the LLM prompt. Treat retrieved text as untrusted; sandbox tool use; consider an instruction-defense system prompt and content filtering.
- **Data exfiltration / multi-tenancy.** Apply tenant/ACL filters in **stage 1** (metadata filtering on the vector store) so a reranker never even sees another tenant's docs. Never rely on the reranker for access control.
- **PII in eval/judge calls.** Sending contexts to a hosted reranker or LLM-judge ships your data to a third party. Check data-residency/retention; prefer self-hosted models for sensitive corpora.

### 6.4 Observability & statistical rigor

- **Log the trace per request:** query, candidate ids + stage-1 scores, reranker scores, final top-n, latencies per stage. This is both your debugging tool and your **online eval** data source.
- **Report uncertainty.** With small eval sets (50–200 questions), a 0.02 metric delta may be noise. Use **bootstrap confidence intervals** or a paired significance test (e.g. paired t-test / Wilcoxon on per-query scores). Never ship a reranker change on a single point estimate.
- **Dashboards & alerts:** track p50/p95/p99 reranker latency, error rate, score distribution drift, and online proxy metrics (CTR, thumbs-up rate, deflection rate).

### 6.5 Cost

- **Hosted reranker** bills per search/document — at high QPS this adds up; model it against self-hosting a GPU.
- **LLM-as-judge eval is expensive** — each RAGAS metric may make multiple LLM calls per question; evaluating a 1,000-question set across 4 metrics can be thousands of LLM calls. Sample, cache judge outputs, and use a cheaper judge model for routine CI runs (reserve the strong judge for releases).
- **Token cost from fewer chunks:** a good reranker *saves* money downstream by letting you send 3–5 great chunks instead of 15 mediocre ones to the (expensive) generator.

### 6.6 Testing & production hardening

- **Graceful degradation:** if the reranker times out or errors, fall back to stage-1 order (don't fail the whole request). Wrap in a circuit breaker.
- **Timeouts & retries:** bound the reranker call tightly; retry once on transient 5xx with jittered backoff; never retry on the critical path more than the latency budget allows.
- **Golden-set regression gate in CI** (§5.7) plus prompt/pipeline regression via promptfoo/DeepEval.
- **Canary online eval** (§6.7) before full rollout.

### 6.7 Offline vs online evaluation

> **Offline eval:** run your pipeline against a fixed, labeled **eval set** in CI/batch. Fast, repeatable, cheap-ish, no real users at risk. Measures retrieval/RAG metrics. Used to gate changes.
>
> **Online eval:** measure on **live traffic** — A/B test or canary the new reranker against the old, and compare *business/proxy* metrics (click-through rate, thumbs-up rate, task completion, deflection, escalation rate) plus sampled LLM-judge scores on real queries. Slower, needs traffic and statistical care, but it's the only measure of real impact. Offline tells you "did the metric improve?"; online tells you "did users get helped?" **Ship on offline gates, prove value on online tests.**

### 6.8 Anti-patterns

- **No eval set at all** — shipping reranker/prompt changes on vibes.
- **Evaluating on your training/dev examples** — leakage inflates scores.
- **Only reporting absolute metrics** — report *lift* vs a baseline with significance.
- **Reranking the entire corpus** — defeats the point; rerank a shortlist.
- **Trusting LLM-judge blindly** (§5/§7.3) without spot-checking against human labels.
- **One universal score threshold** across models/versions.
- **Truncating away the answer** by reranking oversized chunks.
- **Ignoring the generator** — perfect retrieval with a bad prompt still fails; eval end-to-end.

---

## 7. Advanced topics & deep internals

### 7.1 Late interaction (ColBERT) deep dive

ColBERT stores a vector per token. At query time, for each query token `q_i` it computes `max_j sim(q_i, d_j)` over all document tokens `d_j` (MaxSim), then sums over query tokens. This approximates cross-encoder fidelity (token-level matching) while keeping document encodings precomputable and indexable (via a specialized index, e.g. PLAID). Trade-off: storage explodes (N tokens × dim per doc) and the index is complex. Use ColBERT when you want better-than-bi-encoder quality at better-than-cross-encoder latency, or as a strong stage-1.

### 7.2 Reranker tuning knobs (lesser-known)

- **Candidate diversity / MMR.** Before reranking (or after), apply **Maximal Marginal Relevance** to avoid five near-duplicate chunks crowding the top-n. MMR trades relevance against novelty: `MMR = λ·rel(d) − (1−λ)·max_sim(d, already_selected)`. Improves answer coverage.
- **Sliding-window reranking for long docs.** If a doc exceeds 512 tokens, score multiple overlapping windows and take the max (or mean) — avoids truncating the relevant span.
- **Listwise / LLM rerankers.** Instead of scoring pairs independently (pointwise), prompt an LLM to *order* a list of passages (e.g. RankGPT, or `bge-reranker-v2-gemma`). More context-aware, far slower/costlier; useful for the very top few.
- **Distillation.** Distill a large cross-encoder's judgments into a small one (or into the embedding model) to push quality earlier in the pipeline for less query-time cost.
- **Fine-tuning the reranker** on your domain's (query, relevant, irrelevant) triplets often beats swapping to a bigger off-the-shelf model. Mine hard negatives from stage-1 false positives.

### 7.3 LLM-as-judge: mechanics, biases, and mitigations

> **LLM-as-judge:** using a (usually strong) LLM to *score* another model's output — e.g. "rate this answer's faithfulness 1–5 given this context," or "which of answer A/B is better?" Powers RAGAS, G-Eval, and most automated RAG eval at scale because human labeling doesn't scale.

**Documented biases (know these cold):**

| Bias | What it is | Mitigation |
|---|---|---|
| **Position bias** | Judge favors the first (or a fixed) option in pairwise comparisons | Randomize order; run both orders and average; report ties |
| **Verbosity / length bias** | Longer answers scored higher regardless of quality | Control for length; instruct judge to ignore length; penalize padding |
| **Self-enhancement bias** | A model rates its own family's outputs higher | Use a *different* model family as judge than the one generating |
| **Sycophancy / leniency** | Judges drift toward high scores; poor calibration | Use rubrics + few-shot anchors; reference-based scoring; binary > Likert when possible |
| **Format/style bias** | Prefers confident, well-formatted prose over correct-but-plain | Explicit rubric focused on correctness/grounding |
| **Prompt sensitivity** | Scores swing with tiny prompt wording changes | Freeze the judge prompt; version it; chain-of-thought before the score |

**Best practices:** (1) **calibrate against human labels** — periodically have humans label a sample and measure judge–human agreement (e.g. Cohen's κ); a judge you haven't validated is a random number generator with good grammar. (2) Use **reference-based** judging (give the ground-truth answer) when available — far more reliable than reference-free. (3) Prefer **pairwise/binary** verdicts over fine-grained 1–10 scores (LLMs calibrate poorly on fine scales). (4) Ask for **reasoning before the verdict** (improves consistency). (5) **Ensemble/aggregate** multiple judge calls. (6) Treat judge scores as *noisy proxies*, not ground truth.

### 7.4 Building eval sets

A good eval set is the most valuable asset in your RAG system. Sources:

1. **Production logs** — sample real queries (anonymized), label which retrieved chunks were relevant and write/verify gold answers. Most realistic distribution.
2. **Synthetic generation** — use an LLM to generate (question, answer, relevant-chunk) triples *from your documents* (RAGAS has a test-set generator). Fast to bootstrap; **must be human-reviewed** to remove leakage/trivial questions.
3. **Stratify by query type** — keyword/exact-match, multi-hop, comparative, "no answer exists" (negatives!), long-tail, adversarial. A set that's all easy questions tells you nothing.
4. **Include negatives** — questions whose answer is *not* in the corpus, to test that the system abstains rather than hallucinates.
5. **Version and freeze.** `golden_v3.jsonl`. Bump versions deliberately; never silently edit (it breaks regression comparisons). Hold out a slice the team never inspects to detect overfitting to the eval set (Goodhart's law: when a metric becomes a target, it stops being a good metric).
6. **Size.** 50 questions to start (better than nothing); 200–1,000 for stable, low-variance metrics. Bigger sets shrink confidence intervals.

### 7.5 Worked end-to-end: adding a reranker and measuring lift (the narrative)

1. **Establish baseline.** With no reranker, run §5.6 on `golden_v2`: say MRR@5 = 0.61, NDCG@5 = 0.66, RAGAS context_precision = 0.70, faithfulness = 0.88.
2. **Add reranker.** Insert Cohere/BGE reranking on top of `k=100` stage-1 results, keep top 5.
3. **Re-run offline eval.** MRR@5 → 0.74, NDCG@5 → 0.79, context_precision → 0.86. Faithfulness barely moves (0.88→0.89) — expected, because the *generator* didn't change, only the *inputs* improved. **Bootstrap a 95% CI** on the per-query MRR deltas; if the CI excludes 0, the lift is real.
4. **Check latency cost.** p95 retrieval latency went from 45 ms → 230 ms; total p95 (incl. generation) 2.1 s → 2.3 s — acceptable.
5. **Canary online.** Route 5% of traffic to the reranked pipeline; compare thumbs-up rate and answer-edit rate over a week with proper significance. If positive and latency within SLO, ramp to 100%.
6. **Lock the new baseline** in the CI regression gate (§5.7) so future changes can't silently undo the gain.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Bi-encoder vs cross-encoder vs late-interaction

| Dimension | Bi-encoder (retrieve) | Late interaction (ColBERT) | Cross-encoder (rerank) | LLM reranker (listwise) |
|---|---|---|---|---|
| Accuracy | Baseline | High | Very high | Highest (often) |
| Query-time cost | Lowest | Medium | High (per candidate) | Highest |
| Doc precompute | Full | Per-token | None | None |
| Scales to millions | Yes | Yes (big index) | No | No |
| Storage | 1 vec/doc | N vecs/doc | none | none |
| Typical use | Stage 1 | Stage 1 or cheap rerank | Stage 2 rerank | Final top-few |

### 8.2 Hosted vs self-hosted reranker

| Factor | Hosted (Cohere/Jina/Voyage) | Self-hosted (BGE/cross-encoder) |
|---|---|---|
| Time-to-ship | Minutes | Days (GPU infra) |
| Cost at scale | Per-call $$ | Fixed GPU $; cheaper at high QPS |
| Latency | + network RTT | No RTT; depends on your hardware |
| Data residency | Data leaves your network | Stays in-house |
| Ops burden | None | You own it |
| Best when | Low/medium QPS, fast iteration, non-sensitive data | High QPS, sensitive data, cost control, custom fine-tune |

### 8.3 Use-when / avoid-when

**Add a reranker when:** answers cite wrong/missing-but-indexed passages; you use hybrid retrieval; you want to send fewer chunks to the LLM; precision@top matters more than raw recall.

**Skip/defer a reranker when:** stage-1 already nails precision@5 on your eval set (measure first!); your latency SLO can't absorb it and you can't self-host on GPU; your corpus is tiny (you can feed everything to the LLM); you have no eval set to prove it helps (build the eval set *first*).

**Choose offline eval when:** gating CI changes, comparing models, fast iteration. **Choose online eval when:** validating real user impact, comparing UX-affecting changes, deciding rollout. You need **both**.

---

## 9. Failure modes & debugging

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Reranker "does nothing" / order unchanged | Index re-mapping bug; sending text where ids expected | Log candidate id ↔ text ↔ score mapping; assert input order preserved | Keep immutable id↔text map end-to-end |
| Relevant chunk truncated, low score | Chunk > model max_length (512), answer cut off | Inspect tokenized length of the relevant chunk | Smaller chunks; sliding-window rerank |
| Lift on offline but none online | Eval set unrepresentative / leakage / Goodhart | Compare eval query distribution to prod logs; held-out slice | Rebuild eval from prod samples; add negatives |
| RAGAS scores all ~1.0 or all ~0.0 | Judge model/key misconfigured; metric needs ground_truth you didn't supply | Inspect a single row's intermediate judge prompts | Configure judge + embeddings; supply required fields |
| p99 latency spikes | No timeout/batching; `k` too large; cold model | Per-stage latency histograms; trace one slow request | Cap `k`; batch; warm the model; GPU; circuit-breaker fallback to stage-1 |
| Hallucinated answers despite good retrieval | Generator ignores context; lost-in-the-middle; too many chunks | Faithfulness metric drop; inspect prompt | Fewer/better chunks (reranker!); reorder best-first; tighten prompt |
| Score threshold drops everything | Hardcoded threshold from a different model/version | Print score distribution on eval set | Recalibrate threshold empirically per model |
| Duplicate chunks crowd top-n | No dedup/MMR | Eyeball top-n on sample queries | Dedup by content hash; apply MMR |

**Real-world incident patterns:** (1) A reranker upgrade silently regressed a niche query class (code identifiers) because the new model was tuned for prose — caught only because a *stratified* eval set had a code-query slice. Lesson: stratify. (2) An LLM-judge "improved" scores after a prompt tweak that just made the judge more lenient — caught by periodic human calibration showing judge–human agreement *dropped*. Lesson: validate the judge. (3) Reranker timeouts during a provider incident took down the whole search until a circuit-breaker fallback to stage-1 order was added. Lesson: degrade gracefully.

---

## 10. Interview drill

**Q1. Why two stages? Why not rerank everything with the cross-encoder?**
*Model answer:* A cross-encoder must run a full forward pass per (query, doc) pair — O(k) cost — so it can't scan millions of docs per query. A bi-encoder precomputes doc vectors offline and does fast ANN search for high recall but coarse ordering. Stage 1 (bi-encoder) cheaply shrinks the corpus to a rerankable shortlist (k≈100); stage 2 (cross-encoder) precisely reorders it. You get recall *and* precision affordably.
- *Probe: What exactly does the cross-encoder do that the bi-encoder can't?* It feeds query+doc through one model with full cross-attention, modeling token-level interaction; the bi-encoder encodes them independently and only compares two pooled vectors, losing interaction signal.
- *Probe: How do you pick k and n?* Empirically on a labeled eval set — increase k until Recall@k plateaus (often ≈100), pick n by precision/latency trade-off (often 3–8).
- *Probe: Where does ColBERT fit?* Late interaction: per-token vectors + MaxSim — between the two on the accuracy/cost curve; usable as a strong stage-1 or cheap reranker.

**Q2. Cross-encoder vs bi-encoder — explain to a new hire.**
*Model answer:* Bi-encoder = two towers, encode query and doc separately, compare vectors; fast, indexable, used for retrieval. Cross-encoder = one tower, encode query+doc together with cross-attention, output one score; accurate, not precomputable, used for reranking a shortlist.
- *Probe: Can you precompute cross-encoder scores?* No — the doc's representation depends on the specific query, so nothing can be cached offline (only per-(query,doc) caches at runtime).
- *Probe: Are cross-encoder scores probabilities?* Usually unbounded logits; only ordering is meaningful unless the model documents a calibrated sigmoid output.

**Q3. Which metric should move when a reranker works, and why?**
*Model answer:* MRR and NDCG@small-k and RAGAS context precision — they're position-sensitive. Recall@large-k often barely moves because the relevant doc was already retrieved; the reranker changes *where* it sits, which only position-aware metrics capture.
- *Probe: Why might faithfulness not move?* Because the generator is unchanged; the reranker improves the *inputs*, which helps when it surfaces the answer, but faithfulness mostly reflects the generator's grounding behavior.
- *Probe: When is NDCG preferred over MRR?* When relevance is graded (0/1/2/3), not binary, and you care about the whole top-k order, not just the first hit.

**Q4. What is RAGAS measuring, metric by metric?**
*Model answer:* Faithfulness = are the answer's claims supported by the contexts (anti-hallucination, no ground truth needed). Answer relevance = does the answer address the question. Context precision = are relevant contexts ranked first (reranker target). Context recall = do contexts contain everything the ground-truth answer needs (requires ground truth).
- *Probe: Which need ground truth?* Context recall always; context precision often; faithfulness and answer relevance can run reference-free.
- *Probe: How does faithfulness work internally?* It decomposes the answer into atomic claims and uses an LLM to check each against the contexts; the score is the supported fraction.

**Q5 (senior signal). When would you NOT add a reranker?**
*Model answer:* When measurement shows stage-1 already nails precision@n on a representative eval set; when the latency SLO can't absorb it and self-hosting on GPU isn't viable; when the corpus is small enough to feed entirely to the LLM; or before you have an eval set to prove value — building the eval set is prerequisite work. Adding a reranker without evidence is cargo-culting.
- *Probe: How do you prove it's not helping?* Run with/without on the eval set, compare MRR/NDCG/context-precision with bootstrap CIs; if the CI includes 0, no significant lift.
- *Probe: Cheaper alternatives to try first?* Better chunking, hybrid (BM25+dense) retrieval, query rewriting/expansion, metadata filtering, MMR for diversity.

**Q6 (senior signal). Walk me through evaluating a reranker change end-to-end before shipping.**
*Model answer:* Establish an offline baseline on a versioned, stratified golden set (MRR/NDCG/RAGAS). Add the reranker, re-run, report *lift* with confidence intervals and a paired significance test. Verify latency p95/p99 within SLO. Add a CI regression gate at the new floor. Canary 5% online, compare proxy metrics (thumbs-up, edit rate, deflection) with significance over a week, then ramp.
- *Probe: How do you avoid overfitting to the eval set?* Hold out a slice nobody inspects; rebuild periodically from fresh prod logs; watch for Goodhart drift.
- *Probe: How big should the eval set be?* 50 to start, 200–1,000 for low-variance metrics; size to the confidence interval you need.

**Q7 (senior signal). LLM-as-judge — what are the failure modes and how do you trust it?**
*Model answer:* Position bias, verbosity bias, self-enhancement bias, leniency/sycophancy, format bias, prompt sensitivity. Trust requires *calibration against human labels* (measure judge–human agreement like Cohen's κ on a sample), reference-based judging when possible, randomizing option order, pairwise/binary over fine Likert scales, reasoning-before-verdict, a frozen versioned judge prompt, and treating scores as noisy proxies.
- *Probe: Why not use the generator model as its own judge?* Self-enhancement bias inflates scores; use a different, ideally stronger, model family.
- *Probe: Pairwise vs pointwise judging?* Pairwise (A vs B) is more reliable because relative comparison is easier for LLMs than absolute calibration; pointwise scales better and gives absolute scores but is noisier.

**Q8. Offline vs online evaluation — when each, and why both?**
*Model answer:* Offline runs against a fixed labeled set in CI — fast, repeatable, safe, gates changes, measures retrieval/RAG metrics. Online runs on live traffic (A/B/canary) — measures real user/business impact (CTR, thumbs-up, deflection) but is slower and needs traffic + statistics. Offline answers "did the metric improve?"; online answers "did users benefit?" Ship on offline gates, prove value online.
- *Probe: A change improves offline but not online — causes?* Unrepresentative/leaky eval set; metric–outcome mismatch; Goodhart; latency regressions hurting UX that offline ignored.
- *Probe: How do you make online comparison valid?* Randomized assignment, sufficient sample, fixed metric chosen in advance, significance testing, guardrail metrics (latency, error rate).

**Q9. How does hybrid retrieval + RRF interact with reranking?**
*Model answer:* Dense + BM25 retrieve in parallel; RRF fuses by reciprocal rank (`1/(60+rank)` summed) needing only ranks; the fused union (deduped) becomes the reranker's candidate set. The reranker is the single authority on final order atop the fused set.
- *Probe: Why RRF instead of summing scores?* Dense and BM25 scores aren't comparable in scale; RRF uses only ranks, so it fuses heterogeneous retrievers robustly.
- *Probe: Why include BM25 at all if you have semantic search?* BM25 nails exact tokens, IDs, codes, rare terms, and quoted phrases that embeddings often miss.

**Q10. A relevant document is in the index but never reaches the LLM. How do you debug?**
*Model answer:* Check each stage: is it in stage-1 top-k? (recall problem → embeddings/chunking/k). If yes, what's its reranker score and rank? (precision problem → truncation, model fit, threshold). Is a metadata/ACL filter excluding it? Is it deduped/merged away? Inspect the logged trace per stage.
- *Probe: Stage-1 misses it — fixes?* Better embedding model, smaller/overlapping chunks, larger k, hybrid retrieval, query expansion.
- *Probe: It's retrieved but reranked low — fixes?* Check truncation (chunk too long), recalibrate threshold, try a domain-fine-tuned reranker, sliding-window scoring.

---

## 11. Glossary

- **ANN (Approximate Nearest Neighbor):** fast, slightly-inexact nearest-vector search (HNSW, IVF) trading tiny accuracy for huge speed at scale.
- **Answer relevance (RAGAS):** does the answer address the question; measured reference-free via synthetic-question similarity.
- **Bi-encoder (dual/two-tower):** encodes query and doc separately into vectors; fast, indexable; powers retrieval.
- **BM25:** classic lexical ranking by term frequency + inverse document frequency with length normalization; no semantics.
- **`[CLS]`/`[SEP]`:** special Transformer tokens; `[CLS]` summarizes the input (read by the score head), `[SEP]` separates segments.
- **ColBERT / late interaction:** stores per-token vectors and scores via MaxSim; between bi- and cross-encoder on accuracy/cost.
- **Context precision (RAGAS):** are relevant contexts ranked at the top; the metric a reranker most directly improves.
- **Context recall (RAGAS):** do retrieved contexts contain everything the ground-truth answer needs; requires ground truth.
- **Cosine similarity:** cosine of the angle between two vectors; direction-only similarity in [−1,1].
- **Cross-attention:** attention where query tokens attend to doc tokens (and vice versa); the cross-encoder's accuracy source.
- **Cross-encoder:** scores query+doc jointly in one model pass; accurate, not precomputable; powers reranking.
- **DCG/NDCG:** Discounted Cumulative Gain (position-discounted relevance) normalized to [0,1]; the graded-relevance ranking metric.
- **Embedding:** text → fixed-length vector where similar texts are nearby.
- **Faithfulness (RAGAS):** fraction of answer claims supported by the contexts; anti-hallucination metric.
- **G-Eval:** LLM-as-judge method using chain-of-thought + a rubric to score outputs.
- **Golden set / eval set:** versioned, labeled questions (+ relevant chunks/answers) used for offline evaluation.
- **Goodhart's law:** when a metric becomes a target it stops being a good metric (overfitting the eval set).
- **HNSW:** Hierarchical Navigable Small World — a graph-based ANN index; key knob `ef_search`.
- **Hit Rate@k:** did *any* relevant doc appear in top k (binary).
- **Hybrid search:** combine dense (vector) + sparse (BM25) retrieval, usually fused with RRF.
- **IVF (Inverted File):** cluster-based ANN index; searches only the nearest clusters.
- **Lift:** the metric delta a change produces vs a baseline; always report with significance.
- **LLM-as-judge:** using an LLM to score/compare model outputs at scale; biased, must be calibrated.
- **Lost-in-the-middle:** LLMs attend poorly to context buried in the middle of a long prompt; argues for fewer, best-first chunks.
- **MMR (Maximal Marginal Relevance):** re-ranks trading relevance vs novelty to reduce near-duplicate results.
- **MRR (Mean Reciprocal Rank):** mean of 1/rank of the first relevant hit; rewards getting one answer to the top.
- **Offline evaluation:** batch eval against a fixed labeled set; fast, safe, gates CI.
- **Online evaluation:** eval on live traffic (A/B/canary) measuring real user/business metrics.
- **Position bias:** LLM judge favoring a fixed option position in comparisons.
- **Precision@k:** fraction of top-k that are relevant.
- **RAG (Retrieval-Augmented Generation):** LLM answering using retrieved context in the prompt.
- **RAGAS:** Python framework scoring RAG via LLM-as-judge (faithfulness, answer relevance, context precision/recall).
- **Recall@k:** fraction of all relevant docs found in top k.
- **Reranking:** reordering stage-1 candidates with a more accurate (cross-encoder) model.
- **RRF (Reciprocal Rank Fusion):** fuse ranked lists by summing `1/(k_rrf+rank)`; default `k_rrf=60`.
- **Self-enhancement bias:** a judge rating its own model family higher.
- **Two-stage retrieve-then-rerank:** cheap high-recall retrieval then expensive precise reranking of a shortlist.
- **Vector store:** database of embeddings with ANN search (Pinecone, Qdrant, Weaviate, pgvector, etc.).
- **Verbosity bias:** judge favoring longer answers regardless of quality.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Pattern:** retrieve (bi-encoder, k≈50–100, high recall) → rerank (cross-encoder, top-n≈3–8, high precision) → generate.
- **Bi-encoder:** separate encodings, indexable, fast, retrieval. **Cross-encoder:** joint encoding + cross-attention, not precomputable, accurate, reranking. **ColBERT:** per-token + MaxSim, in between.
- **Scores:** cross-encoder logits are unbounded; only order is meaningful; calibrate thresholds per model.
- **Reranker moves:** MRR@k, NDCG@k, RAGAS **context precision**. Recall@large-k and faithfulness often don't move.
- **RAGAS four:** faithfulness (claims supported), answer relevance (addresses question), context precision (relevant ranked first), context recall (contexts cover ground truth — needs ground truth).
- **RRF default:** `k_rrf = 60`. **Cross-encoder max length:** commonly **512 tokens** — chunk smaller.
- **Hybrid:** dense + BM25 → RRF → rerank.
- **LLM-judge biases:** position, verbosity, self-enhancement, leniency, format, prompt-sensitivity → calibrate vs humans, randomize order, reference-based, pairwise/binary, frozen prompt.
- **Eval split:** offline (CI gate, labeled set, MRR/NDCG/RAGAS) + online (A/B canary, CTR/thumbs-up/deflection). Ship offline, prove online.
- **Eval set:** versioned, stratified (incl. negatives), 200–1,000 for stable metrics, hold out a hidden slice (Goodhart).
- **Report lift with confidence intervals / significance**, never a single point estimate.
- **Hardening:** timeout + circuit-breaker fallback to stage-1 order; dedup/MMR; CI regression gate at a baseline floor.
- **Decide:** add a reranker when wrong/missing-but-indexed citations and precision@top matters; skip when stage-1 already wins, SLO can't absorb it, or you have no eval set yet.

### 12.2 Self-test (no answers)

1. Explain, to someone who knows what an embedding is, why a cross-encoder cannot precompute document representations and what that costs you at query time.
2. You add a reranker and Recall@100 is unchanged but MRR@5 jumps from 0.6 to 0.75. What happened, and which RAGAS metric should also move?
3. Design an eval set for a customer-support RAG bot: list the query strata you'd include and explain why negatives matter.
4. Your LLM-judge faithfulness scores rose 0.05 after a judge-prompt tweak, but real answer quality didn't change. Name two biases that could cause this and how you'd detect the artifact.
5. Sketch the full request lifecycle from query to answer for a hybrid + rerank pipeline, naming each stage, its latency order-of-magnitude, and what you log at each step.
6. Give three concrete situations where you would *not* add a reranker, and the cheaper fix you'd try first in each.
7. Write the RRF formula, state the common default constant, and explain why RRF is preferred over summing raw dense and BM25 scores.

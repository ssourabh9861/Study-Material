# Embeddings & Vector Search

> An engineering-handbook chapter for senior backend developers (Java/JVM-centric) who want to master embeddings and vector search from first principles to production internals.

---

## 1. Overview & where it fits

### What it is

An **embedding** is a fixed-length list of floating-point numbers — a **vector** — that represents a piece of content (a word, a sentence, a document, an image, a row of structured data) in a way that captures its **meaning** rather than its literal characters. Two pieces of content that *mean* similar things end up with vectors that are *close together* in the high-dimensional space; two unrelated pieces end up far apart. "Close" is measured by a **similarity metric** (cosine similarity, dot product, or Euclidean distance — defined in detail later).

**Vector search** (also called **semantic search**, **similarity search**, or **nearest-neighbor search**) is the operation of: given a query vector, find the stored vectors that are closest to it. A **vector database** (or a vector *index* embedded in another database) is the system that stores these vectors and answers nearest-neighbor queries fast, even when you have millions or billions of them.

### The problem it solves

Classic search is **lexical**: it matches tokens. A query for "car" does not match a document that only says "automobile," and a query for "how do I cancel my subscription" does not match "steps to end your membership," because the *words* differ even though the *intent* is identical. Lexical engines (inverted indexes, BM25, the kind of thing Lucene/Elasticsearch do by default) are excellent at exact and fuzzy token matching but blind to meaning.

> **Inverted index (term for beginners):** a data structure mapping each term (word) to the list of documents containing it. It is what makes keyword search fast. **BM25** ("Best Match 25") is the standard relevance-scoring formula on top of an inverted index — it ranks documents by how often query terms appear, dampened by term frequency saturation and document length. It is lexical: it scores on word overlap, not meaning.

Embeddings solve the **semantic gap**. Because "car" and "automobile" map to nearby vectors, a vector search returns the right document regardless of vocabulary. This is the foundation of:

- **Semantic search** over documents, products, code, support tickets.
- **Retrieval-Augmented Generation (RAG)** — the dominant pattern for grounding Large Language Models (LLMs) in your private data: you embed your documents, store them, and at query time retrieve the most relevant chunks to stuff into the LLM's prompt so it answers from facts instead of hallucinating.

> **LLM (term for beginners):** a Large Language Model is a neural network trained to predict the next token of text; modern ones (GPT-4, Claude, Llama, Gemini) can answer questions, write code, and summarize. They only "know" what was in their training data and what you put in the prompt. **RAG** = Retrieval-Augmented Generation: fetch relevant context from a knowledge base (via vector search) and prepend it to the prompt so the model answers from your data.

- **Recommendations** ("users who liked X also liked…"), **deduplication**, **clustering**, **classification**, **anomaly detection**, **multimodal search** (text query → image results).

### When you reach for it

Reach for embeddings + vector search when **meaning matters more than exact wording**, when you need **fuzzy "find me things like this"** over unstructured data, or when you are building **RAG**. Do **not** reach for it when a `WHERE status = 'OPEN'` SQL query or a keyword filter already answers the question precisely — vectors are slower, fuzzier, and more expensive than exact predicates. In practice the best systems are **hybrid**: lexical filters for exact constraints, vector similarity for semantic ranking.

### One-paragraph mental model

Think of the embedding model as a **function that maps any input to a point in a high-dimensional space** (typically 384–3072 dimensions) such that semantic similarity becomes geometric proximity. The vector database is a **specialized spatial index over those points** — like a B-tree gives you fast range lookups in one dimension, an **Approximate Nearest Neighbor (ANN)** index gives you fast "what's near this point" lookups in hundreds of dimensions, trading a tiny bit of accuracy (recall) for orders-of-magnitude speedups. Your job as an engineer is to choose the embedding model, chunk your data sensibly, pick the index and its tuning knobs, and combine vector similarity with metadata filters.

---

## 2. Foundations from first principles

### 2.1 From symbols to vectors

A computer cannot reason about the *meaning* of the string `"dog"`. Historically, NLP (Natural Language Processing) represented words as **one-hot vectors**: a vector as long as the vocabulary, all zeros except a single `1` at the word's index. With a 50,000-word vocabulary, "dog" is a 50,000-dimensional vector with one `1`. Problems: (1) gigantic and sparse, (2) every pair of distinct words is equidistant — "dog" is exactly as far from "cat" as from "refrigerator." There is no notion of similarity.

> **Sparse vs dense vector (term for beginners):** a *sparse* vector is mostly zeros (like one-hot, or like a bag-of-words count vector). A *dense* vector has a meaningful nonzero value in (almost) every position. Embeddings are dense, low-dimensional, and learned.

### 2.2 The distributional hypothesis

The breakthrough idea (Firth, 1957: "You shall know a word by the company it keeps") is the **distributional hypothesis**: words appearing in similar contexts have similar meanings. If "dog" and "cat" both frequently appear near "pet," "vet," "feed," and "leash," a model can learn they are similar. **Word2Vec** (Mikolov et al., 2013) operationalized this: train a shallow neural network to predict a word from its neighbors (CBOW) or neighbors from a word (Skip-gram); the network's hidden-layer weights become the word's dense vector, typically 100–300 dimensions. Suddenly `vec("king") - vec("man") + vec("woman") ≈ vec("queen")` — meaning had become arithmetic.

> **CBOW / Skip-gram (terms for beginners):** two training objectives in Word2Vec. *CBOW* (Continuous Bag of Words) predicts the center word from surrounding context words. *Skip-gram* does the reverse — predicts surrounding words from the center word. Skip-gram is better for rare words; CBOW trains faster.

### 2.3 From words to context: contextual embeddings

Word2Vec gives one vector per word regardless of context — "bank" (river) and "bank" (money) collapse to the same vector. **Contextual embedding models** (ELMo, then **BERT** and the entire Transformer family) produce a *different* vector for "bank" depending on the surrounding sentence. Modern embedding models are usually Transformer encoders fine-tuned so that a *whole sentence or passage* maps to a single vector that captures its meaning in context.

> **Transformer (term for beginners):** the neural-network architecture (Vaswani et al., 2017, "Attention Is All You Need") behind virtually all modern LLMs and embedding models. Its core mechanism, **self-attention**, lets every token "look at" every other token in the input and weight their influence, so the representation of a word depends on its full context. **BERT** (Bidirectional Encoder Representations from Transformers, Google 2018) is a Transformer *encoder* trained to understand text bidirectionally; it (and successors like the Sentence-BERT / `sentence-transformers` family) is a common backbone for embedding models.

### 2.4 How a sentence becomes one vector (pooling)

A Transformer outputs one vector **per token**. To get a single vector for the whole input you **pool**:

- **CLS pooling:** use the vector of the special `[CLS]` token that BERT prepends to every input. Works only if the model was trained for it.
- **Mean pooling:** average all token vectors (often the most robust; used by `sentence-transformers`).
- **Last-token pooling:** use the final token's vector (common for decoder-style embedding models like some OpenAI/E5-style models).

> **Token / tokenization (terms for beginners):** models don't see characters or whole words; they see **tokens** — sub-word units produced by a *tokenizer* (e.g., Byte-Pair Encoding / WordPiece). "tokenization" might split into `token` + `ization`. The token count, not the character count, drives model cost and context limits.

### 2.5 The geometry: what "similar" means

After pooling you have a vector `v ∈ ℝ^d` (d = dimensionality, e.g., 768). The model is trained (usually with **contrastive learning** — pull semantically related pairs together, push unrelated pairs apart) so that the **angle** between related vectors is small. The three metrics:

- **Cosine similarity** — the cosine of the angle between two vectors: `cos(a,b) = (a·b) / (‖a‖‖b‖)`. Range −1 to 1; 1 = identical direction, 0 = orthogonal/unrelated, −1 = opposite. **Ignores magnitude**, cares only about direction. This is the default for text embeddings because vector *length* often encodes things like word frequency or document length that you don't want to dominate similarity.
- **Dot product (inner product)** — `a·b = Σ aᵢbᵢ`. Same as cosine *if the vectors are normalized to unit length* (then `‖a‖=‖b‖=1`). If not normalized, magnitude matters — useful when length carries signal (e.g., some recommendation embeddings deliberately encode popularity in magnitude).
- **Euclidean (L2) distance** — straight-line distance: `‖a−b‖ = √(Σ(aᵢ−bᵢ)²)`. A *distance* (smaller = more similar), not a similarity. For unit-normalized vectors, L2 distance and cosine similarity are monotonically related: `‖a−b‖² = 2 − 2·cos(a,b)`, so ranking by one equals ranking by the other.

> **Normalization / unit vector (terms for beginners):** *normalizing* a vector means dividing it by its length (L2 norm) so it has length 1. After normalization, dot product == cosine similarity, and many indexes run faster. Many embedding APIs (e.g., OpenAI) return already-normalized vectors.

**Practical rule:** for text semantic search, **normalize and use cosine (or equivalently dot product / L2 on normalized vectors)**. Pick the specific metric your embedding model was *trained* for — using the wrong one degrades recall.

### 2.6 The curse of dimensionality (why ANN exists)

In high dimensions, distances between random points concentrate: nearly everything is roughly equidistant, and exact nearest-neighbor search degenerates toward a brute-force scan. Worse, classic spatial indexes (KD-trees, R-trees) that work in 2D/3D **fail above ~10–20 dimensions** — they end up examining almost all points. This is the **curse of dimensionality**. The escape hatch is **Approximate** Nearest Neighbor (ANN): give up the guarantee of finding the *exact* top-k and instead find *almost certainly the right ones* far faster. The quality measure is **recall@k** — of the true top-k nearest neighbors, what fraction did the approximate index return.

> **KD-tree / R-tree (terms for beginners):** classic data structures for spatial queries in low dimensions. A *KD-tree* recursively splits space along axes; an *R-tree* groups points into bounding boxes. Both are great for maps/GIS (2–3D) but collapse in the hundreds of dimensions embeddings live in.

### 2.7 Vocabulary you'll keep meeting

- **k (top-k):** how many neighbors you want returned.
- **recall@k:** correctness of an ANN index (1.0 = perfect, matches brute force).
- **QPS:** queries per second (throughput).
- **latency (p50/p95/p99):** response time percentiles.
- **dimensionality (d):** length of each vector.
- **index build time** vs **query time** — many ANN structures are expensive to build, cheap to query.

---

## 3. How it works internally

This is the heart of the chapter. We trace, in order: (A) how an embedding is produced, (B) how vectors are stored and indexed, (C) the two dominant ANN families (HNSW and IVF/PQ) step by step, (D) how filtering interacts with ANN, and (E) the end-to-end query lifecycle.

### 3.1 Producing an embedding (control + data flow)

1. **Input** raw content (e.g., a 2-page support article).
2. **Chunking** (if too long): split into passages that fit the model's context window and that represent coherent units of meaning (covered in §3.6). Each chunk is embedded separately.
3. **Tokenization:** the model's tokenizer converts text → token IDs (integers). Truncation happens here if the chunk exceeds the model's max sequence length (commonly 256–8192 tokens).
4. **Forward pass:** token IDs go through the Transformer; out comes one hidden vector per token.
5. **Pooling:** token vectors → one vector of size `d` (mean/CLS/last-token).
6. **(Optional) normalization** to unit length.
7. **Output:** a float vector `[0.013, -0.245, …]` of length `d`. This is deterministic for a given model+input (barring nondeterministic GPU kernels), but **not portable across models** — vectors from model A are meaningless to model B. *Never mix embeddings from different models or even different model versions in one index.*

### 3.2 Storage: what a vector store actually holds

A vector record is conceptually:

```
{
  id:        "doc_4213#chunk_2",     // primary key
  vector:    float[d],               // the embedding
  metadata:  { source, url, lang,    // arbitrary filterable fields
               created_at, tenant_id, ... },
  payload:   "the original chunk text" // often stored too, for RAG
}
```

The store keeps three things: the **raw vectors** (or a compressed form), the **ANN index** built over them, and the **metadata/payload** (often in a columnar or key-value side store, sometimes with its own inverted index for filtering). Memory math matters: `N vectors × d dims × 4 bytes (float32)`. **1M × 768 × 4 B ≈ 3 GB** just for raw vectors; **10M × 1536 × 4 B ≈ 61 GB**. The HNSW *graph* adds roughly `N × M × (4–8 B)` more. This is why **quantization** (compressing vectors) and **disk-based indexes** exist.

> **float32 / float16 / int8 (terms for beginners):** how many bytes per number. *float32* = 4 bytes (default, full precision). *float16* = 2 bytes (half precision, ~half the RAM, tiny accuracy loss). *int8* = 1 byte (8-bit integer, ~quarter RAM, more accuracy loss). Storing vectors in lower precision is the cheapest "quantization."

### 3.3 The brute-force baseline (flat index)

The simplest "index" is **no index** — a **flat** index. Store all vectors; for each query, compute the metric against **every** vector and keep the top-k. This is **exact (recall = 1.0)** and trivially correct. Cost is `O(N·d)` per query. For 1M × 768 that's ~768M multiply-adds per query — fine for thousands of vectors, far too slow for millions at interactive latency. Flat is the correctness oracle you measure ANN recall against, and it's genuinely the right choice below ~50k–100k vectors (where ANN's build cost and recall loss aren't worth it). Vector engines call this `Flat` (FAISS), `FLAT` (Milvus), or just "exact search."

> **FAISS (term for beginners):** Facebook AI Similarity Search — Meta's open-source C++/Python library of ANN indexes (Flat, IVF, PQ, HNSW). It is the reference implementation many vector databases build on or benchmark against.

### 3.4 HNSW — Hierarchical Navigable Small World (graph-based ANN)

HNSW (Malkov & Yashunin, 2016) is the most popular ANN index in production (used by Pinecone, Weaviate, Milvus, Qdrant, Lucene/Elasticsearch/OpenSearch, pgvector's `hnsw`). It is a **graph** you greedily walk.

**The idea, built up:**

1. **Navigable Small World (NSW) graph:** put every vector as a node; connect each node to its approximate nearest neighbors. To search, start at some node and **greedily hop to whichever neighbor is closer to the query**, repeating until no neighbor is closer (a local minimum). This works because the graph has "small-world" shortcuts that let you traverse it in roughly logarithmic hops. The flaw: a single flat graph can get stuck in poor local minima for far-apart starts.
2. **Hierarchy (the "H"):** build **multiple layers** like a skip list. The top layer has very few nodes with long-range links (coarse, fast jumps across the space); each lower layer has more nodes and shorter links; the bottom layer (layer 0) contains **all** nodes. Search starts at the top, greedily descends to the nearest node, drops a layer, repeats — zooming in coarse-to-fine. This yields ~`O(log N)` search.

> **Skip list (term for beginners):** a layered linked list where upper layers skip over many elements for fast search and lower layers are dense for precision. HNSW applies the same coarse-to-fine layering idea to a proximity graph.

**Insertion (build) — step by step for one new vector:**
1. Randomly assign the node a **maximum layer** `L` (drawn from an exponential distribution; most nodes only exist on layer 0, few reach high layers).
2. From the top layer down to `L+1`, greedily find the closest node (just navigation, no linking).
3. From layer `L` down to 0, run a search to collect `efConstruction` candidate neighbors, then connect the new node to the best `M` of them (and add reciprocal links), pruning each affected node back to at most `M` (or `Mmax0 ≈ 2M` on layer 0) connections using a heuristic that keeps links *diverse*, not just nearest.

**Search — step by step:**
1. Enter at the single top-layer entry point.
2. Greedily walk to the closest node on each layer, descending one layer at a time, until layer 0.
3. On layer 0, run a **best-first search** maintaining a dynamic candidate list of size **`efSearch`** (`ef`): repeatedly expand the closest unexpanded candidate, add its neighbors, keep the `ef` closest seen. Return the top-k from that list.

**The three knobs:**

| Knob | What it controls | Effect of increasing | Typical default |
|---|---|---|---|
| `M` | max links per node (graph degree) | higher recall, more memory, slower build | 16 (range 8–64) |
| `efConstruction` | candidate breadth during build | higher recall, slower/heavier build | 100–200 |
| `efSearch` / `ef` | candidate breadth during query | higher recall, higher latency | 40–200 (tunable per query) |

`efSearch` is the **runtime recall/latency dial** — you raise it for accuracy, lower it for speed, *without rebuilding the index*. `M` and `efConstruction` are fixed at build time. HNSW is **in-memory-hungry** and **slow to build/insert** but gives **excellent recall at low latency** and supports incremental inserts (though deletes are awkward — usually a "soft delete" tombstone, with real removal on compaction).

### 3.5 IVF and PQ — partition-based ANN and compression

**IVF — Inverted File index (partitioning):**
1. **Train:** run k-means clustering on a sample of the vectors to find **`nlist`** centroids (e.g., 1024–65536 cluster centers). Each centroid owns a "cell" (Voronoi region).
2. **Index:** assign every vector to its nearest centroid's cell (an inverted list per cell).
3. **Query:** find the **`nprobe`** centroids nearest the query, then brute-force search only the vectors in those cells. You skip the other `nlist − nprobe` cells entirely.

> **k-means / centroid / Voronoi cell (terms for beginners):** *k-means* is a clustering algorithm that partitions points into k groups, each represented by its mean point (the *centroid*). The region of space closest to a given centroid is its *Voronoi cell*. IVF buckets vectors by which cell they fall in.

The recall/latency dial here is **`nprobe`**: probe more cells → higher recall, slower. `nprobe = nlist` degenerates to brute force (recall 1.0). The risk: a true neighbor sitting just across a cell boundary in an unprobed cell gets missed — so IVF recall is lumpier than HNSW's. IVF needs a **training step** and works best when you have many vectors (≳100k–1M) so cells are well-populated.

**PQ — Product Quantization (compression):** PQ shrinks each vector so millions fit in RAM.
1. **Split** each `d`-dim vector into `m` contiguous **sub-vectors** (e.g., d=768, m=96 → each sub-vector is 8-dim).
2. For **each** of the `m` sub-spaces, run k-means with `k=256` to learn a small codebook of 256 centroids.
3. **Encode** a vector by replacing each sub-vector with the **1-byte ID (0–255)** of its nearest sub-centroid. A 768-dim float32 vector (3072 bytes) becomes `m` bytes (e.g., **96 bytes** — a 32× compression).
4. **Query (Asymmetric Distance Computation, ADC):** precompute, for the query, the distance from each query sub-vector to all 256 sub-centroids (an `m × 256` lookup table); then a stored vector's approximate distance is just `m` table lookups summed — extremely fast and cache-friendly.

> **Quantization (term for beginners):** mapping continuous values to a small finite set of representatives (codes). PQ quantizes *pieces* of the vector independently, hence "product" quantization — the full codebook is the Cartesian *product* of the per-subspace codebooks (256^m possible codes from only m×256 stored centroids).

PQ trades **memory for accuracy**: it's lossy, so distances are approximate even before ANN. It's almost always **combined with IVF** as **`IVF,PQ`** (FAISS notation `IVF4096,PQ96`): IVF narrows which cells to scan, PQ makes scanning those cells cheap and memory-light. Variants: **OPQ** (Optimized PQ — rotates the space first so subspaces are more balanced), **SQ** (Scalar Quantization — simpler per-dimension int8), and **PQ with re-ranking** (find candidates with PQ, then re-score the top few with full-precision vectors for accuracy — "refine"). HNSW can also be combined: **HNSWPQ** / **HNSWSQ** compress the graph's stored vectors.

### 3.6 Chunking and its effect on embeddings

Embedding models have a **max input length** and produce **one vector per input**. Feeding a 50-page PDF as a single input either truncates it (losing most content) or, if the model accepts it, averages so much meaning that the vector becomes mush ("semantic dilution"). So you **chunk**:

- **Fixed-size chunking:** N tokens with overlap (e.g., 512 tokens, 50-token overlap). Simple, robust, ignores structure.
- **Recursive/structural chunking:** split on natural boundaries (headings → paragraphs → sentences) and pack up to a size budget. Preserves coherence.
- **Semantic chunking:** detect topic shifts (e.g., embed sentences, cut where adjacent-sentence similarity drops). Best coherence, more compute.
- **Sentence-window / parent-document:** embed small units for precision but return the surrounding larger context for the LLM.

**Why chunking dominates retrieval quality:** the chunk *is* the unit of retrieval. Too **large** → diluted vectors, you retrieve a 2000-token chunk to answer one sentence (wastes the LLM context, lowers precision). Too **small** → fragments lack context, the embedding is ambiguous, and answers get split across chunks the retriever can't reassemble. **Overlap** prevents an answer from being cut across a boundary. Tradeoff numbers in the wild: chunks of **~200–500 tokens with 10–20% overlap** are a common sweet spot for prose RAG; code and tables often need structure-aware splitting. **Always embed the query with the same model and same preprocessing as the documents.**

### 3.7 End-to-end query lifecycle (control flow)

1. **Receive query** text (and optional filters: `tenant_id = 42`, `lang = "en"`).
2. **Embed** the query with the *same model* used for documents → query vector `q`.
3. **(Optional) normalize** `q`.
4. **Filter resolution:** decide whether filters are applied **pre**, **post**, or **inline** with the ANN search (§6 covers the tradeoffs).
5. **ANN search:** walk the HNSW graph / probe IVF cells with the chosen `ef`/`nprobe` → candidate set (often `k × overfetch`, e.g., fetch 50 to return 10).
6. **(Optional) re-rank:** re-score candidates with full-precision vectors, a cross-encoder, or fuse with BM25 (hybrid).
7. **Hydrate:** join candidate IDs back to payload/metadata.
8. **Return** top-k with scores; in RAG, format into the LLM prompt.

> **Cross-encoder / re-ranker (term for beginners):** a model that takes the query *and* a candidate together and outputs a precise relevance score. It's far more accurate than comparing independent embeddings (a "bi-encoder") but too slow to run over the whole corpus — so you use cheap vector search to fetch ~50 candidates, then a cross-encoder to re-rank the final 10.

---

## 4. The complete toolkit

### 4.1 Embedding models (representative; check current versions)

| Model | Provider | Dim(s) | Max tokens | Notes |
|---|---|---|---|---|
| `text-embedding-3-small` | OpenAI | up to 1536 (configurable) | 8191 | Cheap, strong; supports **Matryoshka** dimension shortening |
| `text-embedding-3-large` | OpenAI | up to 3072 (configurable) | 8191 | Higher quality, pricier |
| `text-embedding-004` / Gemini embeddings | Google | 768 (configurable) | ~2048 | Task-type prompts |
| `voyage-3` family | Voyage AI | 1024+ | 32k (some) | High retrieval quality, long context |
| Cohere `embed-v3` | Cohere | 1024 | 512 | Has `input_type` (search_doc vs search_query) |
| `all-MiniLM-L6-v2` | sentence-transformers (open) | 384 | 256 | Tiny, fast, runs locally/CPU, very popular baseline |
| `bge-large-en-v1.5` | BAAI (open) | 1024 | 512 | Strong open model; needs query instruction prefix |
| `e5-large-v2` | Microsoft (open) | 1024 | 512 | Prefix `query:` / `passage:` |
| `nomic-embed-text-v1.5` | Nomic (open) | 768 (Matryoshka) | 8192 | Long context, open |

> **Matryoshka embeddings (term for beginners):** models trained so the *first* k dimensions are themselves a usable (lower-quality) embedding. You can truncate a 3072-dim vector to 512 dims to save space/speed with graceful quality loss — like nesting dolls.

Key API params you'll set: the **model name**, **dimensions** (if configurable), **input type / task** (`search_document` vs `search_query` for asymmetric models — using the wrong one *hurts recall*), and **batch size** (embed many texts per call for throughput).

### 4.2 Index types (FAISS-style names, broadly portable)

| Index | Family | Recall | Latency | Memory | Build | Best when |
|---|---|---|---|---|---|---|
| `Flat` | exact | 1.0 | high (O(N)) | high (raw) | none | <100k vectors; correctness oracle |
| `IVF,Flat` | partition | high w/ nprobe | medium | high (raw) | needs training | 100k–10M, RAM OK |
| `IVF,PQ` | partition+compress | medium-high | low | very low | training | 10M–1B, RAM constrained |
| `HNSW` (`HNSWFlat`) | graph | very high | very low | high | slow | best recall@latency, RAM available |
| `HNSWPQ`/`HNSWSQ` | graph+compress | high | low | medium | slow | large + RAM constrained |
| `IVF,SQ8` | partition+scalar quant | high | low | ~1/4 raw | training | cheap memory win, mild loss |
| DiskANN / Vamana | graph on disk | high | medium | low RAM (SSD) | slow | billions, SSD-backed |

> **DiskANN / Vamana (term for beginners):** a graph ANN (Microsoft) designed to keep most of the index on SSD with a small in-RAM cache, so you can serve billions of vectors without billions of bytes of RAM. Used by Milvus (DiskANN) and others.

### 4.3 Tuning knobs by index

| Knob | Index | Meaning | Direction |
|---|---|---|---|
| `M` | HNSW | graph degree | ↑ recall, ↑ memory, ↓ build speed |
| `efConstruction` | HNSW | build candidate breadth | ↑ recall, ↓ build speed |
| `efSearch`/`ef` | HNSW | query candidate breadth | ↑ recall, ↑ latency (runtime) |
| `nlist` | IVF | number of clusters | ↑ → finer cells; rule: ~`√N`–`4√N` |
| `nprobe` | IVF | cells probed per query | ↑ recall, ↑ latency (runtime) |
| `m` (PQ) | PQ | sub-vectors | ↑ → better accuracy, less compression |
| `nbits` (PQ) | PQ | bits per code (usually 8) | ↑ accuracy, ↑ size |
| `metric` | all | cosine/IP/L2 | match the model |

### 4.4 Vector databases / engines (what each adds over a flat index)

| System | Type | Indexes | Filtering | Notes |
|---|---|---|---|---|
| **pgvector** | Postgres extension | IVFFlat, HNSW | full SQL `WHERE` | Vectors live *with* relational data & transactions; great when you already run Postgres; HNSW added in 0.5.0 |
| **Pinecone** | Managed SaaS | proprietary (graph-based) | metadata filters | Fully managed, serverless option, namespaces for multitenancy; no infra to run |
| **Weaviate** | OSS + cloud | HNSW (+flat, +compression) | structured + hybrid (BM25+vector) | GraphQL/REST, built-in modules, hybrid search first-class |
| **Milvus / Zilliz** | OSS + cloud | HNSW, IVF*, DiskANN, GPU (CAGRA) | scalar filtering | Distributed, separates storage/compute, billion-scale, GPU indexes |
| **Qdrant** | OSS + cloud (Rust) | HNSW | rich payload filtering, **filterable HNSW** | Strong filtering integration, quantization built in |
| **Elasticsearch / OpenSearch** | search engine | HNSW (Lucene `knn`) | full DSL + BM25 | **Best when you want lexical + vector + filters in one**; mature ops |
| **Redis (Search/Vector)** | in-memory KV | HNSW, FLAT | tag/numeric filters | Ultra-low latency, RAM-bound |
| **FAISS** | library (not a DB) | all of the above | none (DIY) | No persistence/metadata/concurrency by itself; you build the service |
| **Chroma / LanceDB** | embedded/OSS | HNSW / IVF (Lance) | metadata | Lightweight, dev-friendly, local-first |

**What a vector DB adds over a bare flat index / FAISS:** persistence and crash recovery; **metadata storage + filtering**; CRUD with concurrent reads/writes; horizontal scaling and sharding; replication/HA; **hybrid (lexical+vector) search**; **multitenancy** (namespaces/collections); quantization management; monitoring; access control; and an API/SDK. FAISS gives you the algorithm; a vector DB gives you a *production system*.

### 4.5 JVM-side libraries

- **Lucene** (`org.apache.lucene.codecs.lucene*.Lucene99HnswVectorsFormat`) — HNSW vector search **natively in the JVM**; the engine under Elasticsearch/OpenSearch/Solr. `KnnFloatVectorField`, `KnnFloatVectorQuery`.
- **Spring AI** — abstraction over embedding models (`EmbeddingModel`) and `VectorStore` implementations (pgvector, Pinecone, Weaviate, Milvus, Redis, Qdrant, Elasticsearch…).
- **LangChain4j** — `EmbeddingModel`, `EmbeddingStore`, `EmbeddingStoreIngestor`, document splitters/chunkers for Java.
- **Official Java SDKs:** OpenAI Java, Pinecone Java, Qdrant Java, Milvus Java, Weaviate Java, Elasticsearch Java client (`knnSearch`).
- **JLama / DJL / ONNX Runtime** — run embedding models *in-process on the JVM* (no Python service).

---

## 5. Code examples by use case

> Examples default to Java where the topic is language-relevant; SQL/CLI where appropriate. Comments mark the non-obvious lines.

### 5.1 In-process embeddings + in-memory store (LangChain4j) — zero infra prototype

```java
// build.gradle: implementation 'dev.langchain4j:langchain4j:0.36.x'
//               implementation 'dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:0.36.x'
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.util.List;

public class SemanticSearchDemo {
  public static void main(String[] args) {
    // Runs the 384-dim MiniLM model fully in-JVM via ONNX — no API key, no network.
    EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
    EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>(); // brute-force/flat under the hood

    List<String> docs = List.of(
        "How to cancel your subscription",
        "Resetting your account password",
        "Steps to end your membership plan",     // semantically == doc 0
        "Configuring two-factor authentication");

    for (String d : docs) {
      TextSegment seg = TextSegment.from(d);
      Embedding e = model.embed(seg).content();   // text -> 384-d vector
      store.add(e, seg);                           // store vector + payload together
    }

    // Query uses the SAME model — never mix models across index & query.
    Embedding q = model.embed("I want to stop paying for my plan").content();
    EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
        .queryEmbedding(q).maxResults(3).minScore(0.5) // minScore filters weak matches
        .build();
    EmbeddingSearchResult<TextSegment> res = store.search(req);
    res.matches().forEach(m ->
        System.out.printf("%.3f  %s%n", m.score(), m.embedded().text()));
    // Expect "Steps to end your membership plan" and "How to cancel..." to rank top,
    // even though they share almost no keywords with the query.
  }
}
```

### 5.2 Production embeddings via OpenAI + cosine, with normalization (Java)

```java
// implementation 'com.openai:openai-java:<version>'
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.*;
import java.util.*;

public class OpenAiEmbeddings {
  static final OpenAIClient client = OpenAIOkHttpClient.fromEnv(); // reads OPENAI_API_KEY

  // Batch many inputs per request for throughput & cost; one network round-trip.
  static List<float[]> embed(List<String> texts) {
    EmbeddingCreateParams params = EmbeddingCreateParams.builder()
        .model("text-embedding-3-small")
        .dimensions(512L)               // Matryoshka: shorten 1536 -> 512 to save storage
        .inputOfArrayOfStrings(texts)
        .build();
    CreateEmbeddingResponse resp = client.embeddings().create(params);
    List<float[]> out = new ArrayList<>();
    for (Embedding e : resp.data()) {
      List<Float> v = e.embedding();
      float[] arr = new float[v.size()];
      for (int i = 0; i < arr.length; i++) arr[i] = v.get(i);
      out.add(normalize(arr));          // make unit-length so dot == cosine
    }
    return out;
  }

  static float[] normalize(float[] v) {
    double sum = 0; for (float x : v) sum += (double) x * x;
    float inv = (float) (1.0 / Math.max(Math.sqrt(sum), 1e-12)); // guard div-by-0
    float[] o = new float[v.length];
    for (int i = 0; i < v.length; i++) o[i] = v[i] * inv;
    return o;
  }

  // For unit vectors, cosine similarity == dot product.
  static float dot(float[] a, float[] b) {
    float s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s;
  }
}
```

### 5.3 pgvector — store with relational data, HNSW index, hybrid metadata filter (SQL)

```sql
-- 1. Enable the extension (Postgres 13+; pgvector 0.5.0+ for HNSW)
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. A table mixing structured columns and a vector column
CREATE TABLE docs (
  id         BIGSERIAL PRIMARY KEY,
  tenant_id  INT NOT NULL,
  lang       TEXT NOT NULL,
  body       TEXT NOT NULL,
  embedding  vector(512)            -- must match your model's output dim
);

-- 3. Build an HNSW index for cosine distance (vector_cosine_ops).
--    Use the operator class matching your metric: _cosine_ops / _ip_ops / _l2_ops.
CREATE INDEX ON docs USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 200);

-- 4. Query: nearest neighbors WITH a SQL filter (true hybrid: exact predicate + vector rank).
--    <=> is cosine distance (smaller = closer). Set ef_search per session for recall/latency.
SET hnsw.ef_search = 100;
SELECT id, body, 1 - (embedding <=> :query_vec) AS cosine_similarity
FROM docs
WHERE tenant_id = 42 AND lang = 'en'        -- metadata filter applied with the ANN scan
ORDER BY embedding <=> :query_vec           -- order by distance => nearest first
LIMIT 10;
```

```text
Operators:  <-> L2 distance | <#> negative inner product | <=> cosine distance
Gotcha: pgvector pre-0.8 could under-fill HNSW results when a restrictive WHERE filtered
out most of the ef_search candidates — raise ef_search, or use iterative scan (0.8+),
or a partial/filtered index per tenant.
```

### 5.4 Elasticsearch — hybrid lexical (BM25) + vector (kNN) search (Java client)

```java
// co.elastic.clients:elasticsearch-java
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import java.util.List;

SearchResponse<Doc> resp = esClient.search(s -> s
    .index("docs")
    // Lexical leg: BM25 on the text field — catches exact terms, codes, names.
    .query(q -> q.match(m -> m.field("body").query("cancel subscription")))
    // Vector leg: approximate kNN over the embedding field (Lucene HNSW).
    .knn(KnnSearch.of(k -> k
        .field("embedding")
        .queryVector(toFloatList(queryVec))
        .k(10)                       // neighbors to return
        .numCandidates(100)          // == efSearch overfetch: bigger -> higher recall
        .filter(f -> f.term(t -> t   // metadata filter fused into the ANN scan
            .field("tenant_id").value(42)))))
    .size(10),
    Doc.class);
// ES combines BM25 score and kNN score (configurable / RRF). Hybrid beats either alone
// on most real corpora: BM25 nails exact tokens, vectors nail paraphrases.
```

> **RRF — Reciprocal Rank Fusion (term for beginners):** a simple, robust way to merge two ranked lists (e.g., BM25 and vector) without tuning weights: each item's fused score = Σ 1/(k + rank_in_list). Items ranked high in *either* list bubble up.

### 5.5 RAG ingestion pipeline with chunking (LangChain4j)

```java
import dev.langchain4j.data.document.*;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

Document doc = FileSystemDocumentLoader.loadDocument(path);

EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
    .embeddingModel(model)
    .embeddingStore(store)
    // 300-token chunks, 30-token overlap: overlap stops answers being split at a boundary.
    .documentSplitter(DocumentSplitters.recursive(300, 30))
    .build();

ingestor.ingest(doc);   // load -> split -> embed each chunk -> store with source metadata

// Retrieval at query time: fetch top-k chunks, then feed them to the LLM prompt.
```

### 5.6 FAISS — choosing & training an IVF,PQ index at scale (Python, for the algorithm)

```python
import faiss, numpy as np
d = 768                          # embedding dimensionality
nlist = 4096                     # IVF clusters (~ a few * sqrt(N))
m, nbits = 96, 8                 # PQ: 96 sub-vectors x 8 bits -> 96 bytes/vector (32x smaller)

quantizer = faiss.IndexFlatIP(d) # inner-product coarse quantizer (use normalized vectors)
index = faiss.IndexIVFPQ(quantizer, d, nlist, m, nbits, faiss.METRIC_INNER_PRODUCT)

index.train(train_vectors)       # REQUIRED: learns centroids + PQ codebooks (use a sample)
index.add(all_vectors)           # encode + bucket every vector

index.nprobe = 32                # query knob: probe 32/4096 cells -> recall/latency dial
D, I = index.search(query_vectors, k=10)   # D=scores, I=ids of nearest neighbors
# Memory: 96 bytes/vec * N (vs 3072 bytes raw) lets ~30M vectors fit where 1M raw would.
```

### 5.7 Re-ranking with a cross-encoder after vector retrieval (pseudocode flow)

```text
candidates = vectorStore.search(queryVec, k=50)        // cheap, high-recall overfetch
scored = crossEncoder.score(query, [c.text for c in candidates])  // accurate, slow, only 50
return top10(scored)                                   // precision boost on the final list
```

### 5.8 Deduplication via similarity threshold

```java
// Treat any pair with cosine >= 0.95 as duplicates; cluster greedily.
// Useful for dedup of support tickets, near-duplicate docs, product listings.
for (Record r : incoming) {
  var hit = store.searchTopK(r.vector(), 1);
  if (!hit.isEmpty() && hit.get(0).score() >= 0.95f) {
    markDuplicate(r, hit.get(0).id());   // route to existing cluster
  } else {
    store.add(r.vector(), r);            // new unique item
  }
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Batch embedding calls.** One request of 100 texts beats 100 requests. Respect provider rate limits; add retry with backoff.
- **Pick `efSearch`/`nprobe` from a recall target,** not vibes: build a labeled query set, sweep the knob, plot recall@k vs p99 latency, pick the knee.
- **Overfetch then trim:** fetch `k×3–5` and re-rank/filter to `k` to recover recall lost to filtering or PQ.
- **Quantize when RAM-bound:** SQ8 (~4× smaller, small recall loss) before PQ (bigger loss); add full-precision **re-ranking** of the top candidates to claw accuracy back.
- **Normalize once at write time** if using cosine, so queries are pure dot products.
- **Keep `d` as small as the task tolerates.** 384–768 dims often match 1536 on retrieval quality for many corpora and are far cheaper to store and search; Matryoshka models make truncation principled.

### 6.2 Correctness & concurrency

- **Model/version pinning is a correctness invariant.** Re-embedding with a new model version invalidates the whole index — you must **re-index everything**, ideally with **blue/green** (build new index, switch atomically). Store the model name+version as metadata.
- **Deletes in HNSW** are typically tombstones; reclaim via compaction. Heavy churn degrades the graph — schedule rebuilds.
- **Idempotent upserts** keyed by a stable `id` (e.g., `docId#chunkIndex#contentHash`) so re-ingestion doesn't duplicate.
- **Consistency:** managed/distributed stores are often **eventually consistent** — a just-written vector may not be queryable for a moment. Don't assume read-your-writes unless documented.

### 6.3 Filtering strategy (a frequent footgun)

| Strategy | How | Pro | Con |
|---|---|---|---|
| **Post-filter** | ANN top-N, then drop non-matching | simple | low-selectivity filters can leave <k results (the "post-filter starvation" bug) |
| **Pre-filter** | filter first, brute force the subset | exact, full recall | slow if subset is large; can't use the ANN graph |
| **Inline / filtered ANN** | filter checked *during* graph walk (Qdrant filterable HNSW, ES, Weaviate) | best of both | engine-specific; very selective filters can disconnect the graph → recall drop |

Rule: **overfetch and raise `efSearch`/`nprobe` when filters are selective**; for extremely selective filters (e.g., one tenant out of millions) consider **per-tenant indexes/namespaces** or a partial index instead of relying on filtered ANN.

### 6.4 Memory & cost

- Budget RAM with the formula in §3.2 *plus* graph overhead (`N·M·~8 B` for HNSW). Cost grows with `N`, `d`, and replicas.
- **Embedding API cost** is per-token; chunking choices directly drive your bill. Cache embeddings keyed by content hash to avoid re-embedding unchanged text.
- Disk-based indexes (DiskANN) cut RAM cost at the price of SSD IOPS and latency — choose by your $$/latency tradeoff.

### 6.5 Security

- **Tenant isolation:** never let a missing filter leak cross-tenant vectors. Prefer namespaces/collections per tenant over a single shared index with a `tenant_id` filter when the threat model demands hard isolation.
- **Embedding inversion:** embeddings are *not* anonymized — research shows text can be partially reconstructed from embeddings. Treat them as **sensitive data**: encrypt at rest, restrict access, don't ship raw embeddings to untrusted clients.
- **PII:** redact/avoid embedding secrets and personal data you wouldn't store in plaintext.
- **Prompt injection via retrieved content (RAG):** retrieved chunks become LLM input; malicious documents can carry injection payloads. Sanitize and constrain.

### 6.6 Observability

- Track **recall@k** continuously against a golden set (offline eval), plus **p50/p95/p99 latency**, **QPS**, **index size/RAM**, **filter selectivity distribution**, and **empty-result rate** (a spike often means a filtering or model-mismatch bug).
- Log the **model version** and **index version** on every query for forensic traceability.

### 6.7 Testing

- **Recall regression tests:** a fixed query set with known relevant IDs; fail CI if recall drops below threshold after a config/model change.
- **Brute-force oracle:** compare ANN results to a `Flat` index on a sample to quantify recall.
- **Golden-set retrieval eval** (NDCG/MRR/recall) for end-to-end RAG quality, separate from the LLM's answer quality.

> **NDCG / MRR (terms for beginners):** ranking metrics. *MRR* (Mean Reciprocal Rank) = average of 1/(rank of first relevant result). *NDCG* (Normalized Discounted Cumulative Gain) rewards putting more-relevant items higher, discounted by position. Both judge *ranking quality*, not just hit/miss.

### 6.8 Anti-patterns

- Mixing embeddings from different models/versions in one index.
- Using the wrong metric for the model (e.g., L2 on a cosine-trained model).
- Forgetting `input_type`/instruction prefixes on asymmetric models (query vs document).
- Single giant chunks (semantic dilution) or single sentences (lost context).
- Relying on vector search where an exact filter is correct and cheaper.
- Not normalizing when the metric assumes it.
- Treating ANN recall as 1.0; never validating against brute force.
- No re-index strategy → stuck on a stale model forever.

---

## 7. Advanced topics & deep internals

### 7.1 HNSW internals worth knowing

- **Entry-point dynamics:** the single global entry point on the top layer is a potential hotspot; some implementations randomize or maintain multiple. A poorly chosen entry point hurts tail latency.
- **Neighbor selection heuristic:** HNSW doesn't just keep the M nearest neighbors; it uses a **diversity heuristic** (`SELECT-NEIGHBORS-HEURISTIC`) that prefers neighbors spread around the node so the graph stays navigable and avoids redundant links toward one cluster. This is why HNSW recall beats naive kNN graphs.
- **Layer 0 degree** is usually `Mmax0 = 2·M` because the densest layer needs more connectivity.
- **Deletes & graph decay:** repeated insert/delete churn fragments the graph and lowers recall; periodic full rebuild restores it. Some engines (Lucene) rebuild HNSW per-segment and merge.
- **Concurrency:** HNSW inserts mutate shared structure; engines use per-node locks or build immutable segments and merge (Lucene's approach) to allow concurrent reads.

### 7.2 Quantization deep dive

- **SQ (Scalar Quantization):** map each dimension's float to an int8 using per-dimension min/max — simple, ~4× smaller, modest recall loss; great default.
- **PQ math:** with `m` subspaces and `nbits=8`, you store `m` bytes/vector and represent `256^m` possible codes from only `m·256` learned centroids — that's the "product" trick. ADC makes query distance `m` table lookups.
- **OPQ:** learns a rotation `R` so variance is balanced across subspaces before PQ — meaningful recall gains for skewed data.
- **Binary quantization (1 bit/dim):** extreme compression (32× vs float32) where you compare with Hamming distance; surprisingly effective for some high-dim models, especially with a re-ranking pass. **Hamming distance** = count of differing bits.
- **Re-ranking ("refine"):** ANN/PQ finds candidates; you then recompute exact distance on full-precision vectors for the top few. Recovers most of the lost recall for tiny extra cost.

### 7.3 GPU and SIMD acceleration

- FAISS GPU and Milvus **CAGRA** (NVIDIA RAFT graph index) run ANN on GPUs for huge build/query throughput.
- On CPU, distance kernels exploit **SIMD** (AVX2/AVX-512, ARM NEON); the JVM since recent versions has the **Vector API** (`jdk.incubator.vector`) and Lucene uses SIMD/Panama for fast dot products. *SIMD* = Single Instruction, Multiple Data — one instruction processes many floats at once.

### 7.4 Multi-vector & late interaction (ColBERT)

Instead of one vector per document, **ColBERT** stores **one vector per token** and scores via **MaxSim** (for each query token, take its best match across document tokens, then sum). Much higher precision than single-vector ("late interaction"), at large storage cost. Supported by some engines (e.g., Vespa, Qdrant multi-vectors) and worth knowing for high-recall retrieval.

### 7.5 Sparse, dense, and learned-sparse hybrids

- **Dense** = the embeddings we've discussed.
- **Sparse learned** = **SPLADE** and similar produce sparse vectors (term weights over the vocabulary) that you can index in an inverted index but that capture some semantics — bridging lexical and dense.
- **Hybrid dense+sparse** with **RRF** or weighted fusion is state of the art on many benchmarks.

### 7.6 Matryoshka & adaptive dimensions

Train so prefixes are valid embeddings → serve a coarse 256-dim index for the first pass, then re-rank with full 1536-dim vectors. Cuts memory and latency dramatically with controllable quality.

### 7.7 Domain adaptation

Off-the-shelf models underperform on specialized domains (legal, biomedical, code). Options: pick a domain model (e.g., code embeddings for code), **fine-tune** with contrastive pairs from your data, or add a **re-ranker** trained on your relevance labels. Measure with your own golden set, not public leaderboards alone.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Metric choice

| Metric | Use when | Avoid when |
|---|---|---|
| Cosine | text semantic search; model trained for it; magnitude is noise | model trained for IP and you skip normalization |
| Dot/IP | model trained for it; magnitude carries signal (e.g., popularity) | vectors not normalized but you wanted cosine semantics |
| L2 | model trained for L2; image/embedding spaces where geometry matters | magnitude differences are artifacts you want ignored (then normalize → cosine) |

### 8.2 Index choice

- **Use Flat when:** N < ~100k, or you need exact recall, or it's a re-rank pass.
- **Use HNSW when:** you want the best recall@latency, have RAM, moderate write rate. *Avoid when:* extremely high churn, or vectors don't fit RAM (use HNSW+PQ or DiskANN).
- **Use IVF,Flat when:** millions of vectors, RAM available, you accept training and slightly lumpier recall.
- **Use IVF,PQ / DiskANN when:** 10M–1B vectors and RAM/cost constrained; add re-ranking.

### 8.3 Build vs buy (engine selection)

| You have / want | Lean toward |
|---|---|
| Already run Postgres; modest scale; transactions with vectors | **pgvector** |
| Already run Elasticsearch/OpenSearch; need lexical+vector+filters | **ES/OpenSearch** |
| Want zero ops, fast time-to-market | **Pinecone** (managed) |
| OSS, billion-scale, distributed, GPU | **Milvus/Zilliz** |
| Rich filtering, Rust perf, OSS | **Qdrant** |
| Hybrid + built-in modules, OSS | **Weaviate** |
| Sub-ms latency, RAM-rich | **Redis** |
| Library-level control, building your own service | **FAISS / Lucene** |

### 8.4 Lexical vs vector vs hybrid

| Need | Choice |
|---|---|
| Exact tokens, codes, names, IDs | Lexical/BM25 |
| Paraphrase, intent, cross-vocabulary | Vector |
| Real-world mixed queries | **Hybrid (RRF)** — almost always best |

---

## 9. Failure modes & debugging

### 9.1 "Search returns irrelevant / empty results"
- **Cause: model mismatch** — query embedded with a different model/version than docs. *Diagnose:* log model version on write and query; embed a doc and its exact text as a query — similarity should be ~1.0. *Fix:* pin and re-index.
- **Cause: wrong metric** (L2 vs cosine, or unnormalized dot). *Diagnose:* recompute scores by hand on a few pairs. *Fix:* use the model's intended metric; normalize.
- **Cause: missing `input_type`/prefix** on asymmetric models. *Fix:* set `query`/`passage` prefixes or `input_type`.

### 9.2 "Recall is bad / inconsistent"
- **Cause: knob too low** (`efSearch`/`nprobe`). *Diagnose:* sweep against a Flat oracle. *Fix:* raise the knob; accept latency.
- **Cause: PQ too aggressive.** *Fix:* increase `m`, switch to SQ, or add re-ranking.
- **Cause: selective filter starves ANN** (post-filter). *Diagnose:* result count < k with a tight filter. *Fix:* overfetch, raise `ef`, use inline filtering or per-tenant index. (pgvector's classic under-fill bug; mitigated by iterative scan in 0.8+.)

### 9.3 "OOM / index won't fit / slow build"
- **Cause: float32 × N × d too big.** *Fix:* quantize (SQ8/PQ), reduce `d` (Matryoshka), shard, or go DiskANN.
- **Cause: HNSW build is single-threaded or `efConstruction` huge.** *Fix:* parallelize build, lower `efConstruction`, build offline.

### 9.4 "Latency spikes / tail p99"
- **Cause: cold cache, disk-backed index page faults, GC pauses (JVM), entry-point hotspot.** *Diagnose:* flame graphs, JFR (Java Flight Recorder), engine metrics. *Fix:* warm caches, pin memory, tune GC (ZGC/Shenandoah for low pause), add replicas.

### 9.5 "Quality degrades over time"
- **Cause: index decay from insert/delete churn (HNSW tombstones), or data drift.** *Fix:* schedule rebuilds/compaction; re-embed on model upgrades; monitor recall against the golden set.

### 9.6 Real-world incident patterns
- **The silent re-index trap:** team upgrades embedding model in the API but only re-embeds *new* docs; old docs now live in a different semantic space → retrieval quietly rots. Always re-embed the whole corpus on model change.
- **The filter-leak:** a code path skips the `tenant_id` filter → cross-tenant data surfaces. Enforce filters at the storage layer or use namespaces.
- **The chunk-too-big regression:** someone bumps chunk size to "include more context"; recall@k drops because vectors are diluted and the LLM context fills with noise. Re-run retrieval eval on chunking changes.

> **JFR (term for beginners):** Java Flight Recorder — a low-overhead JVM profiler that records allocation, GC, locks, and method samples; pair with **flame graphs** (stacked CPU-time visualizations) to find hotspots. **ZGC/Shenandoah** are low-pause JVM garbage collectors useful for latency-sensitive vector services.

---

## 10. Interview drill

**Q1. What is an embedding and why does similarity become geometry?**
*Model answer:* A dense, fixed-length vector mapping content into a space where semantic similarity ≈ geometric proximity, produced by a model trained (often contrastively) so related inputs get small angular distance. Similarity is measured via cosine/dot/L2.
- *Follow-up: Why dense not one-hot?* One-hot is huge, sparse, and equidistant — no notion of similarity. Dense vectors are compact and place similar meanings nearby.
- *Follow-up: Are embeddings portable across models?* No — each model defines its own space; never mix.
- *Follow-up: What does "contrastive training" mean?* Pull positive pairs together, push negatives apart, shaping the geometry.

**Q2. Cosine vs dot vs Euclidean — when each?**
*Model answer:* Cosine = direction only (default for text). Dot = cosine when normalized; otherwise magnitude matters. L2 = geometric distance; equivalent ranking to cosine for unit vectors. Choose the metric the model was trained for.
- *Follow-up: Relationship for unit vectors?* `‖a−b‖² = 2 − 2cos`, so L2 and cosine rank identically.
- *Follow-up: When does magnitude help?* When length encodes signal (popularity, confidence) — then unnormalized dot.

**Q3. Why ANN instead of exact NN? What's recall@k?**
*Model answer:* Exact NN is O(N·d) and classic spatial indexes fail in high dims (curse of dimensionality). ANN trades a little accuracy for huge speedups. recall@k = fraction of the true top-k an ANN returns.
- *Follow-up: How measure recall?* Compare to a Flat brute-force oracle on a sample.
- *Follow-up: Why do KD-trees fail?* Above ~10–20 dims they degenerate to scanning nearly all points.

**Q4. Explain HNSW.**
*Model answer:* A multi-layer navigable small-world graph; search descends layers coarse-to-fine, greedily hopping to closer neighbors, doing best-first search of breadth `efSearch` on layer 0. Knobs: `M`, `efConstruction` (build), `efSearch` (query).
- *Follow-up: Which knob is runtime?* `efSearch`.
- *Follow-up: Why hierarchy?* Long-range top-layer links avoid local minima; ~O(log N) search.
- *Follow-up: How are deletes handled?* Tombstones + compaction/rebuild.

**Q5. Explain IVF and PQ; how do they combine?**
*Model answer:* IVF clusters vectors (k-means → `nlist` cells); query probes the `nprobe` nearest cells. PQ splits vectors into sub-vectors and quantizes each to a 1-byte code (massive compression) with ADC distance lookups. `IVF,PQ` narrows the search (IVF) and makes it memory-light/fast (PQ).
- *Follow-up: IVF runtime knob?* `nprobe`.
- *Follow-up: PQ downside?* Lossy → lower recall; mitigate with re-ranking/OPQ.
- *Follow-up: Why "product"?* Codebook is the product of per-subspace codebooks → 256^m codes cheaply.

**Q6. (Senior signal) Choose an index for 200M vectors, p99 < 50ms, limited RAM, recall ≥ 0.9. Justify.**
*Model answer:* Raw float32 is ~600GB+ at d=768 — won't fit cheap RAM. Use **IVF,PQ** (or DiskANN) for compression + partitioning, tune `nprobe` to the recall target, and add a **full-precision re-ranking** pass on top candidates to recover recall. Shard across nodes for QPS and to bound per-shard latency. Validate recall against a Flat oracle. Trade: PQ loss vs RAM; re-ranking buys accuracy cheaply.
- *Follow-up: Why not pure HNSW?* RAM blows up at 200M × d float32 + graph overhead.
- *Follow-up: How pick `nprobe`/`nlist`?* `nlist ≈ a few·√N`; sweep `nprobe` on a labeled set to the recall knee.

**Q7. (Senior signal) Lexical vs vector vs hybrid — defend a choice for a support-doc search.**
*Model answer:* Real queries mix exact terms (error codes, product names → BM25 wins) and paraphrases (intent → vectors win). Use **hybrid with RRF**: it dominates either alone, needs no weight tuning, and degrades gracefully. Add a cross-encoder re-rank if precision@k matters.
- *Follow-up: What is RRF?* Rank-based fusion: Σ 1/(k+rank); robust without score normalization.
- *Follow-up: When skip vectors entirely?* If queries are exact identifiers/filters, lexical/SQL is cheaper and exact.

**Q8. (Senior signal) You upgraded the embedding model. What's your rollout plan?**
*Model answer:* Embeddings are model-specific, so a new model = new space → **full re-index**. Use **blue/green**: build a parallel index with the new model, run recall/NDCG eval against the golden set, shadow-test traffic, then switch atomically; keep the old index for rollback. Pin model+version in metadata. Never partially re-embed.
- *Follow-up: Why not incremental?* Old and new vectors are incomparable; mixed spaces wreck recall.
- *Follow-up: Cost control?* Cache by content hash; only re-embed unchanged content once.

**Q9. How does chunking affect retrieval quality?**
*Model answer:* The chunk is the retrieval unit. Too big → semantic dilution + wasted context; too small → lost context + fragmented answers. Use ~200–500 tokens with 10–20% overlap; structure/semantic-aware splitting for code/tables; sentence-window for precision-with-context. Always embed query and docs the same way.
- *Follow-up: Why overlap?* Prevents answers being split across a boundary.
- *Follow-up: Parent-document pattern?* Embed small chunks for matching, return larger parent for context.

**Q10. How do filters interact with ANN, and what's the common bug?**
*Model answer:* Pre-filter (exact but maybe slow), post-filter (simple but can starve below k), inline/filtered ANN (best, engine-specific). Common bug: selective post-filter leaves <k results; fix with overfetch, higher `ef`, inline filtering, or per-tenant indexes.
- *Follow-up: Hard multitenancy?* Namespaces/collections per tenant, not a shared `tenant_id` filter.
- *Follow-up: Very selective filter on inline ANN?* Can disconnect the graph → recall drop; consider pre-filter/partial index.

**Q11. (Senior signal) Are embeddings safe to store/share? Security concerns?**
*Model answer:* Treat embeddings as **sensitive** — inversion attacks can partially reconstruct source text. Encrypt at rest, restrict access, don't expose raw vectors to clients, redact PII before embedding, and guard RAG against prompt injection from retrieved content.
- *Follow-up: Tenant isolation?* Namespaces + enforced filters at storage layer.
- *Follow-up: RAG-specific risk?* Retrieved chunks are LLM input → injection; sanitize/constrain.

**Q12. What does a vector DB add over FAISS/a flat index?**
*Model answer:* Persistence, CRUD with concurrency, metadata storage + filtering, hybrid search, sharding/replication/HA, multitenancy, quantization management, monitoring, security, and an SDK. FAISS is the algorithm; a vector DB is the production system.
- *Follow-up: When is FAISS enough?* When you build your own service layer and control everything.
- *Follow-up: pgvector tradeoff?* Transactions + relational data together, at the cost of Postgres scaling limits at very large N.

---

## 11. Glossary

- **ADC (Asymmetric Distance Computation):** PQ query method using a query-to-centroid lookup table for fast approximate distances.
- **ANN (Approximate Nearest Neighbor):** fast neighbor search that trades exactness for speed.
- **BERT:** bidirectional Transformer encoder; common embedding backbone.
- **BM25:** lexical relevance ranking over an inverted index.
- **Binary quantization:** 1-bit-per-dimension compression; compared via Hamming distance.
- **CAGRA:** NVIDIA GPU graph ANN index (in Milvus/RAFT).
- **CBOW / Skip-gram:** Word2Vec training objectives.
- **Centroid:** the mean point representing a cluster.
- **ColBERT / late interaction:** multi-vector retrieval scoring with MaxSim.
- **Contextual embedding:** vector that depends on surrounding context (vs static Word2Vec).
- **Contrastive learning:** training that pulls positives together, pushes negatives apart.
- **Cosine similarity:** similarity = cosine of the angle between vectors.
- **Cross-encoder:** model scoring query+candidate jointly; accurate re-ranker.
- **Curse of dimensionality:** distance concentration making high-dim NN hard.
- **DiskANN / Vamana:** SSD-backed graph ANN for billion-scale, low RAM.
- **Dimensionality (d):** number of components in a vector.
- **Dense vector:** vector with meaningful values in (almost) all positions.
- **Dot product / inner product:** Σ aᵢbᵢ; equals cosine for unit vectors.
- **efConstruction / efSearch (ef):** HNSW build/query candidate breadth knobs.
- **Embedding:** dense vector capturing semantic meaning of content.
- **Euclidean (L2) distance:** straight-line distance between vectors.
- **FAISS:** Meta's ANN library (Flat/IVF/PQ/HNSW).
- **Flat index:** brute-force exact search; recall = 1.0.
- **float32/16, int8:** numeric precisions (4/2/1 bytes per value).
- **Hamming distance:** count of differing bits between binary codes.
- **HNSW:** Hierarchical Navigable Small World graph ANN.
- **Hybrid search:** combine lexical (BM25) and vector results.
- **Inverted index:** term→documents map enabling fast keyword search.
- **IVF (Inverted File):** partition vectors into k-means cells; probe `nprobe`.
- **JFR / flame graph:** JVM profiler / CPU-time visualization.
- **k-means:** clustering into k centroids.
- **LLM:** Large Language Model (next-token predictor neural net).
- **M:** HNSW max links per node (graph degree).
- **Matryoshka embeddings:** prefixes of the vector are valid lower-dim embeddings.
- **Mean/CLS/last-token pooling:** ways to turn per-token vectors into one vector.
- **Metric:** the function (cosine/dot/L2) defining "close."
- **MRR / NDCG:** ranking quality metrics.
- **nlist / nprobe:** IVF cell count / cells probed at query.
- **Normalization:** scaling a vector to unit length.
- **One-hot vector:** sparse indicator vector with a single 1.
- **OPQ:** Optimized Product Quantization (rotate before PQ).
- **Overfetch:** retrieve more than k to recover recall after filter/PQ loss.
- **pgvector:** Postgres extension for vectors (IVFFlat, HNSW).
- **Pooling:** aggregating token vectors into a single vector.
- **PQ (Product Quantization):** subspace codebook compression of vectors.
- **QPS:** queries per second.
- **RAG:** Retrieval-Augmented Generation.
- **recall@k:** fraction of true top-k an ANN returns.
- **Re-ranking / refine:** re-score candidates with exact/cross-encoder scoring.
- **RRF (Reciprocal Rank Fusion):** rank-based fusion of multiple result lists.
- **Self-attention / Transformer:** architecture where tokens attend to each other.
- **SIMD:** Single Instruction, Multiple Data (vectorized CPU ops).
- **Skip list:** layered structure inspiring HNSW's hierarchy.
- **Sparse vector:** mostly-zero vector.
- **SPLADE:** learned-sparse retrieval model.
- **SQ (Scalar Quantization):** per-dimension int8 compression.
- **Tokenization / token:** splitting input into sub-word units the model processes.
- **Tombstone:** soft-delete marker reclaimed during compaction.
- **Vector database:** system storing vectors + metadata and serving ANN queries.
- **Voronoi cell:** region of space closest to a given centroid.
- **Word2Vec:** early static word-embedding model.
- **ZGC / Shenandoah:** low-pause JVM garbage collectors.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

```
EMBEDDING  = dense float vector; meaning -> geometry. Never mix models/versions.
DIMS       = 384 (MiniLM) .. 768 .. 1024 .. 1536/3072 (OpenAI). Smaller = cheaper.
METRIC     cosine (text default; dir only) | dot (=cosine if normalized; mag matters)
           | L2 (distance; == cosine ranking for unit vectors). Use model's metric.
MEMORY     N * d * 4B (float32). 1M*768*4B ≈ 3GB. + HNSW graph ≈ N*M*~8B.
COMPRESS   SQ8 ~4x (small loss) < PQ up to 32x (more loss) -> add re-ranking.

INDEXES
  Flat    exact, O(N), <100k or oracle.
  HNSW    graph, best recall@latency, RAM-heavy. Knobs: M(16), efConstruction(200) build;
          efSearch(40-200) runtime recall/latency dial.
  IVF     k-means cells. Knobs: nlist(~few*√N) build; nprobe runtime dial.
  IVF,PQ  partition + compress, 10M-1B, low RAM, lossy.
  DiskANN SSD-backed, billions, low RAM.

FILTERS   pre (exact/slow) | post (simple/can starve <k) | inline (best, engine-specific).
          Selective filter -> overfetch + raise ef/nprobe, or per-tenant index/namespace.
CHUNK     ~200-500 tokens, 10-20% overlap. Too big=dilution; too small=lost context.
HYBRID    BM25 (exact tokens) + vector (paraphrase) fused via RRF = usually best.
QUERY     embed(same model) -> normalize -> filter -> ANN(overfetch) -> rerank -> hydrate.
ENGINES   pgvector (have PG) | ES/OpenSearch (lexical+vector) | Pinecone (managed) |
          Milvus (billion/GPU) | Qdrant (filtering) | Weaviate (hybrid) | FAISS (lib).
EVAL      recall@k vs Flat oracle; NDCG/MRR; p50/p95/p99; QPS. Pin model+index versions.
SECURITY  embeddings = sensitive (inversion). Encrypt, isolate tenants, guard RAG injection.
ROLLOUT   model change => full re-index, blue/green, cache by content hash.
```

### Self-test (no answers — active recall)

1. You have 50M vectors at d=1536, a 64GB-RAM box, and a target recall@10 ≥ 0.92 at p99 < 40ms. Which index family and knobs do you choose, and how do you recover recall lost to compression? Sketch the memory math.

2. A teammate reports that after "upgrading the embedding model," search quality slowly degraded over two weeks rather than immediately. What almost certainly happened, and how would you confirm and fix it?

3. Explain precisely why, for unit-normalized vectors, ranking by cosine similarity, by dot product, and by Euclidean distance all produce the *same* order — and give the identity that proves it.

4. A query with the filter `tenant_id = 7 AND region = 'eu-west'` returns only 3 results when the caller asked for 10, even though the tenant has thousands of matching documents. Diagnose the likely cause across the filtering strategies and give two concrete fixes.

5. Walk through, step by step, what HNSW does on a single query from entry point to returned top-k, and state which knob you'd change (and in which direction) to raise recall at the cost of latency without rebuilding the index.

6. Compare HNSW and IVF,PQ across recall, latency, memory, build cost, and write/update friendliness, and give one workload where each clearly wins.

7. Your RAG answers are factually wrong even though the right document exists in the corpus. List four distinct retrieval-side root causes (not LLM-side) and how you'd test each.
```
```

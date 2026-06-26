# Chunking & Retrieval

> An exhaustive engineering-handbook chapter on how documents are split, indexed, and retrieved to feed Large Language Models — the "R" in Retrieval-Augmented Generation (RAG). Written for a senior JVM/Java backend engineer who wants to master the subject end to end: design, operate, debug, teach, and interview.

---

## 1. Overview & where it fits

### 1.1 What this is

**Chunking** is the process of cutting a body of source text (documents, web pages, code, transcripts, tickets) into smaller pieces ("chunks") that can each be embedded, indexed, and retrieved independently. **Retrieval** is the process of, given a user query, finding the most relevant chunks from that index and handing them to a downstream consumer — almost always a **Large Language Model (LLM)** that will read them and answer.

Together they form the front half of **Retrieval-Augmented Generation (RAG)**. RAG is the architectural pattern where, instead of relying solely on what an LLM memorized during training, you *retrieve* relevant external knowledge at query time and *augment* the model's prompt with it, then *generate* an answer grounded in that retrieved text.

> **LLM (Large Language Model):** a neural network (typically a Transformer) trained on enormous text corpora to predict the next token. It has a fixed **context window** — the maximum number of **tokens** (sub-word units; roughly ¾ of a word in English) it can read at once. Everything you retrieve must fit, alongside the question and the model's answer, inside that window.

### 1.2 The problem it solves

LLMs have three structural limitations that retrieval addresses:

1. **Knowledge cutoff & staleness.** A model only knows what was in its training data, frozen at some date. Your internal wiki, last night's incident report, and today's prices are not in there.
2. **Hallucination.** When a model lacks grounded facts, it confidently invents plausible-sounding ones. Supplying authoritative source text and instructing the model to answer *only from it* sharply reduces this.
3. **Finite context & cost.** You cannot paste a 10,000-page manual into every prompt. Even with a million-token window, doing so is slow, expensive, and degrades accuracy (the "lost in the middle" effect — see §7). Retrieval narrows 10,000 pages down to the 5–20 passages that actually matter.

Chunking is the lever that determines *whether retrieval can succeed at all*. If your chunks are wrong — too big, too small, split mid-sentence, missing context — then no amount of clever retrieval or prompting recovers the lost signal. **Garbage chunks in, garbage answers out.** This is why a chapter that pairs "chunking" with "retrieval" is the right unit: they are co-designed.

### 1.3 When you reach for it

Reach for chunking + retrieval (RAG) when:

- You need answers grounded in a **specific corpus** the model wasn't trained on (internal docs, product catalog, legal contracts, codebase, customer tickets).
- The corpus is **larger than the context window**, or large enough that stuffing it all in is too slow/expensive.
- The corpus **changes** and you need fresh answers without retraining.
- You need **citations / provenance** — "the model said X *because* of passage Y in document Z."

Do **not** reach for it (or reach for something else) when:

- The whole relevant corpus fits comfortably in the context window and is static — just stuff it (this is "long-context" or "context-stuffing"), or use prompt caching.
- The task is reasoning over data already in the prompt, not knowledge lookup.
- You actually need the model to *learn new behavior/style*, not new facts — that is fine-tuning territory, not retrieval.

### 1.4 The one-paragraph mental model

Think of it as a library with a smart librarian. **Chunking** is deciding how to cut your books into index cards — one paragraph per card? one chapter? — and what to write at the top of each card (title, author, date = **metadata**). **Indexing** is filing those cards by meaning (a **vector / embedding** — a list of numbers capturing semantics) and/or by keyword (an inverted index like **BM25**). **Retrieval** is the librarian, given your question, pulling the **top-k** most relevant cards — by meaning, by keyword, or both (**hybrid**), then re-sorting them (**fusion / re-ranking**). The cards (chunks) plus your question go to the LLM, which reads only those and answers. Every quality problem in this pipeline traces back to one of: bad cuts (chunking), bad filing (embedding/index), or bad pulling (retrieval).

---

## 2. Foundations from first principles

We build the vocabulary bottom-up. Each term is defined the first time it appears.

### 2.1 Tokens, embeddings, and vector space

- **Token:** the unit an LLM and most embedding models operate on. A **tokenizer** (e.g. OpenAI's `tiktoken` `cl100k_base`/`o200k_base`, or a SentencePiece/BPE tokenizer) maps text to integer IDs. Rule of thumb for English: **1 token ≈ 4 characters ≈ 0.75 words**, so ~750 words ≈ 1,000 tokens. This matters because chunk sizes and context budgets are measured in tokens, not characters or words.

- **Embedding (vector):** a fixed-length array of floating-point numbers (e.g. 384, 768, 1024, 1536, or 3072 dimensions) produced by an **embedding model** that maps text to a point in a high-dimensional space such that *semantically similar texts land near each other*. "How do I reset my password?" and "I forgot my login credentials" produce nearby vectors even though they share few words. This is the foundation of **dense retrieval**.

  > **Embedding model:** a neural network (often a smaller Transformer encoder like a BERT derivative, or a dedicated model such as OpenAI `text-embedding-3-small/large`, Cohere `embed-v3`, `bge-large`, `e5`, `gte`, `nomic-embed`) trained with **contrastive learning** — pulling matching query/passage pairs together and pushing mismatches apart.

- **Similarity metric:** how "nearness" is measured.
  - **Cosine similarity:** the cosine of the angle between two vectors; range −1..1, higher = more similar. Insensitive to vector length; the most common default.
  - **Dot product (inner product):** sums element-wise products; sensitive to magnitude. If vectors are **L2-normalized** (scaled to length 1), dot product equals cosine similarity. Many models expect normalized vectors.
  - **Euclidean (L2) distance:** straight-line distance; smaller = more similar. Equivalent to cosine ranking when vectors are normalized.

### 2.2 What a "chunk" actually is

A chunk is a record with (at minimum):

```
{
  id:        unique identifier,
  text:      the chunk content (what gets embedded and shown to the LLM),
  vector:    the embedding of `text` (for dense retrieval),
  metadata:  { source_doc_id, title, section, page, url, author, created_at,
               token_count, chunk_index, parent_id, ... }
}
```

The **text** is what the LLM reads. The **vector** is how dense retrieval finds it. The **metadata** is how you filter ("only docs from 2024", "only the `payments` service"), how you cite, and how you reconstruct context. Designing the chunk record well is half the battle.

### 2.3 Sparse vs dense representations

- **Sparse representation:** represents a chunk as a (mostly-zero) vector over the vocabulary — i.e., which words appear and how important they are. The classic is the **inverted index** (term → list of documents containing it) scored by **TF-IDF** or **BM25** (§2.4). Sparse retrieval is **lexical**: it matches exact words/stems. Strengths: exact terms, rare jargon, product codes, names, acronyms. Weakness: zero understanding of synonyms or paraphrase ("car" ≠ "automobile").

- **Dense representation:** the embedding vector (§2.1). Dense retrieval is **semantic**: it matches meaning. Strengths: paraphrase, synonyms, fuzzy intent. Weakness: can miss exact rare tokens, and quality depends entirely on the embedding model's training domain.

- **Hybrid:** combine both, because their failure modes are complementary (§6.4). This is now the production default for serious systems.

### 2.4 TF-IDF and BM25 (the workhorse sparse scorers)

- **TF-IDF (Term Frequency × Inverse Document Frequency):** a term's weight rises with how often it appears in a document (TF) and falls with how common it is across the whole corpus (IDF). "The" is everywhere → low IDF → ignored; "kerberos" is rare → high IDF → strong signal.

- **BM25 (Best Matching 25):** the dominant practical refinement of TF-IDF, used by Lucene/Elasticsearch/OpenSearch/Solr. It adds **term-frequency saturation** (the 10th occurrence of a word adds little beyond the 3rd, controlled by parameter `k1`, default ~1.2) and **length normalization** (a hit in a short doc counts more than in a long one, controlled by `b`, default ~0.75). BM25 is shockingly strong, cheap, interpretable, and needs no GPU. Never dismiss it.

  > **Inverted index:** a data structure mapping each term to a **posting list** — the set of documents (and positions) containing it. It is what makes keyword search over billions of docs fast. Lucene is the canonical implementation; Elasticsearch/OpenSearch/Solr wrap it.

### 2.5 Approximate Nearest Neighbor (ANN) search

Dense retrieval needs to find, among millions of vectors, the ones closest to the query vector. Exact search (compare to every vector — **brute-force / flat**) is O(N·d) and too slow at scale. **ANN** trades a little recall for massive speed.

- **HNSW (Hierarchical Navigable Small World):** a graph-based ANN index; the default in most vector DBs (FAISS, Qdrant, Weaviate, pgvector, Lucene/Elasticsearch kNN, Milvus). You navigate a multi-layer graph of vectors, greedily hopping toward the query. Key knobs: `M` (graph connectivity, e.g. 16), `ef_construction` (build-time search width, e.g. 100–200), `ef_search` (query-time search width — bigger = more accurate, slower). Fast, high recall, but memory-hungry (the whole graph is typically in RAM) and slower to build/update.
- **IVF (Inverted File):** partition vectors into clusters; at query time search only the nearest few clusters (`nprobe`). Less memory than HNSW, lower recall unless `nprobe` is high.
- **PQ (Product Quantization):** compress vectors into codes to save memory at a recall cost; often combined as IVF-PQ for billion-scale corpora.

> **Recall (in ANN):** fraction of the true top-k nearest neighbors that the approximate search actually returned. ANN recall@k of 0.95 means it found 95% of the truly-closest vectors. Distinct from *retrieval* recall (§6.10), which is about whether the *answer-bearing* chunk was returned.

### 2.6 The retrieval pipeline at a glance

```
INGEST (offline):
  raw docs → clean/parse → chunk → enrich with metadata
           → embed each chunk → upsert into vector index (+ keyword index)

QUERY (online):
  user query → (optional) rewrite/expand → embed query
            → dense search (top-k1) ┐
            → sparse search (top-k2) ┼→ fuse/merge → (optional) re-rank
                                     ┘    → select final top-n
            → (optional) expand to parent/neighbor chunks
            → assemble prompt (chunks + question) → LLM → answer (+ citations)
```

Everything in §3 is an expansion of these two flows.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle, in order, calling out the data and control flow, the state transitions, and the decisions made at each step.

### 3.1 Ingestion lifecycle (offline / batch)

**Step 1 — Acquire & parse.** Pull raw bytes from the source (PDF, HTML, DOCX, Markdown, Confluence, S3, a DB). Convert to text. This step is *underrated and high-impact*:

- PDFs are the worst: multi-column layouts, headers/footers, tables, ligatures, and reading order get mangled. Tools: Apache PDFBox / Tika (JVM), `unstructured`, `pdfplumber`, `PyMuPDF`, LlamaParse, AWS Textract, Azure Document Intelligence. A table flattened into one run-on line will *never* chunk or retrieve well.
- Strip boilerplate (nav bars, cookie banners, repeated footers) — they pollute embeddings and waste tokens.
- Preserve **structure** signals you'll need for chunking: headings, list boundaries, table cells, code fences, page numbers.

> **Apache Tika:** a JVM library that detects content type and extracts text + metadata from ~1000 file formats. The standard first stop for JVM ingestion pipelines.

**Step 2 — Normalize.** Fix encoding, unify whitespace, optionally lowercase for the sparse index (but *keep original case* for the text shown to the LLM and usually for dense embedding). De-hyphenate line-break splits ("inter-\nnational" → "international"). De-duplicate near-identical documents (e.g. via MinHash/SimHash) so retrieval doesn't return five copies of the same passage.

**Step 3 — Chunk.** Apply a chunking strategy (§3.4 and §6.1) to split text into chunks, each within a target token budget, ideally respecting semantic/structural boundaries. Attach `chunk_index`, `parent_id`, char/token offsets.

**Step 4 — Enrich metadata.** Attach filterable/citable fields and, increasingly, **contextual prefixes** (§7.3) — a short LLM- or template-generated description situating the chunk in its document. Optionally generate **synthetic questions** the chunk answers (improves query-to-chunk matching).

**Step 5 — Embed.** Send each chunk's text through the embedding model, in batches, to get a vector. Watch the model's own input token limit (e.g. 512 or 8192 tokens) — chunks longer than that get silently truncated, losing their tail. **Critical invariant:** the *same* embedding model and the *same* preprocessing must be used for chunks at ingest and for queries at search; mixing models makes vectors incomparable.

**Step 6 — Upsert.** Write each `{id, vector, text, metadata}` into the vector index (and the keyword index). Build/refresh the ANN structure (HNSW graph, IVF clusters). Decide on **idempotency** (deterministic IDs so re-ingest updates rather than duplicates) and **versioning** (so you can roll back a bad re-chunk).

State transitions of a chunk: `parsed → normalized → chunked → enriched → embedded → indexed → (queried) → (re-embedded on model change) → (deleted/superseded)`.

### 3.2 Query lifecycle (online / per-request)

**Step 1 — Query understanding (optional but high-leverage).**
- **Query rewriting/expansion:** an LLM rewrites a terse or conversational query into a standalone search query, expands acronyms, or generates multiple sub-queries (**multi-query**). Example: in a chat, "and the second one?" → "What are the side effects of the second medication, ibuprofen?"
- **HyDE (Hypothetical Document Embeddings):** ask an LLM to draft a *hypothetical answer* to the query, then embed *that* and search with it — answers look more like target passages than questions do, often improving dense recall.

**Step 2 — Embed the query** with the *same* embedding model used at ingest. Note that some models use **asymmetric** encoding: a `query:` prefix vs a `passage:`/`document:` prefix (e.g. E5, BGE, Nomic). Forgetting the prefix silently tanks recall.

**Step 3 — Search.**
- **Dense:** ANN search returns the top-k₁ chunks by vector similarity, optionally with a metadata pre-filter ("only `tenant_id = 42`").
- **Sparse:** BM25 search returns top-k₂ by lexical score.

**Step 4 — Fuse.** Merge the two ranked lists into one. The standard, robust method is **Reciprocal Rank Fusion (RRF)** (§6.4): each doc's fused score is Σ 1/(k + rank_in_list), with `k` typically 60. RRF uses only *ranks*, not raw scores, so it sidesteps the problem that cosine and BM25 scores aren't on the same scale.

**Step 5 — Re-rank (optional, high-precision).** Take the fused top-N (say 50) and re-score each (query, chunk) pair with a **cross-encoder re-ranker** (§7.2) — a model that reads the query and chunk *together* and outputs a relevance score. Far more accurate than the bi-encoder embedding similarity, but far more expensive, so it's applied only to a shortlist. Examples: Cohere Rerank, `bge-reranker`, Jina reranker, cross-encoders from sentence-transformers.

**Step 6 — Context expansion (optional).** Replace or augment each retrieved chunk with surrounding context:
- **Parent-document retrieval:** you retrieved a small precise child chunk; you return its larger parent (section/page) so the LLM has full context (§7.1).
- **Sentence-window / neighbor expansion:** return the matched sentence plus N neighbors on each side.

**Step 7 — Assemble & generate.** Select the final top-n that fit the token budget, order them (best near the start *and* end to dodge "lost in the middle", §7.5), format with source markers, and build the prompt:

```
System: Answer ONLY using the context below. If the answer isn't there, say you don't know. Cite sources by [n].
Context:
[1] (title, date) <chunk text>
[2] ...
Question: <user query>
```

Send to the LLM; return its answer with citations mapped back to chunk metadata.

### 3.3 Data flow summary (who holds what)

| Stage | Input | Output | Stored where |
|---|---|---|---|
| Parse | raw bytes | clean text + structure | scratch / object store |
| Chunk | text | list of chunk texts + offsets | scratch |
| Enrich | chunk + doc | chunk + metadata + context prefix | scratch |
| Embed | chunk text | vector | scratch |
| Upsert | chunk record | (none) | vector DB + keyword index |
| Query | user query | query vector + terms | request memory |
| Search | query vector/terms | ranked chunk IDs | from index |
| Fuse/rerank | ranked lists | final ordered chunks | request memory |
| Generate | chunks + query | answer + citations | response |

### 3.4 Chunking strategies — how each actually splits text

(Tradeoffs are tabulated in §6.1; here we describe the *mechanism*.)

1. **Fixed-size (by tokens or characters).** Slide a window of N tokens, emit a chunk, advance by N. Simplest, fastest, deterministic. Mechanism is index arithmetic. Risk: cuts mid-sentence, mid-word, mid-table.

2. **Fixed-size with overlap.** Advance by N − O (overlap O tokens), so consecutive chunks share O tokens at their boundary. Overlap preserves cross-boundary context (a fact split across the seam appears whole in at least one chunk). Typical O = 10–20% of chunk size.

3. **Recursive / structure-aware splitting.** Try to split on the *largest* natural separator first, recursing to smaller ones only if a piece is still too big. LangChain's `RecursiveCharacterTextSplitter` default separator order is `["\n\n", "\n", " ", ""]` (paragraphs → lines → words → chars). For Markdown/HTML/code there are structure-aware variants splitting on headings, list items, or function boundaries. This keeps most chunks aligned to natural units.

4. **Sentence / paragraph splitting.** Split on sentence boundaries (spaCy, NLTK, or a sentence tokenizer), then **pack** sentences greedily into chunks up to the token budget without crossing the budget mid-sentence. Clean boundaries; variable chunk sizes.

5. **Semantic chunking.** Embed each sentence; walk through the document and start a new chunk when the embedding **distance** between consecutive sentences exceeds a threshold (a topic shift). Produces topically coherent chunks of variable size; costs embeddings at ingest and is sensitive to the threshold/percentile chosen.

6. **Per-document-type ("layout-aware" / element-based).** Use the document's own structure: one chunk per slide, per table, per code function/class, per log entry, per Q&A pair, per markdown section. `unstructured` partitions a doc into typed elements (Title, NarrativeText, Table, ListItem, Code) and you chunk by element. Best fidelity, most engineering.

7. **Proposition / atomic-fact chunking.** An LLM rewrites the text into standalone factual statements ("propositions"), each a chunk. Maximizes retrieval precision and self-containment; expensive and can drift from source wording (a faithfulness risk).

8. **Hierarchical / multi-granularity.** Index chunks at several sizes simultaneously (sentence, paragraph, section) and let retrieval pick the granularity — closely related to parent-document and **RAPTOR** (recursive clustering + summarization into a tree, §7.7).

### 3.5 The control-flow decisions that define behavior

At each junction the system makes a choice that you must own:

- **Chunk size & overlap** → recall vs precision vs cost (§6.2).
- **Embedding model** → semantic quality, dimensionality, cost, multilingual support, input limit.
- **Index type & ANN params** → latency vs recall vs memory.
- **k₁/k₂ (per-retriever top-k)** and **final n** → recall vs prompt cost vs noise.
- **Fusion vs single retriever** → robustness.
- **Re-rank or not** → precision vs latency/cost.
- **Expand to parent or not** → context completeness vs token cost.
- **Filter pre vs post** → correctness of multi-tenant isolation, latency.

---

## 4. The complete toolkit

### 4.1 Chunking APIs / splitters

| Tool / class | Ecosystem | What it does | Key params (defaults) |
|---|---|---|---|
| `RecursiveCharacterTextSplitter` | LangChain (Py/JS) | Recursive split on separator hierarchy | `chunk_size` (no universal default; you set ~500–1000), `chunk_overlap` (e.g. 50–200), `separators` (`["\n\n","\n"," ",""]`), `length_function` (default `len`; use a token counter) |
| `CharacterTextSplitter` | LangChain | Split on a single separator | `separator` (`"\n\n"`), `chunk_size`, `chunk_overlap` |
| `TokenTextSplitter` | LangChain | Split by token count via tiktoken | `chunk_size`, `chunk_overlap`, `encoding_name` |
| `MarkdownHeaderTextSplitter` / `HTMLHeaderTextSplitter` | LangChain | Split by heading levels, keep heading metadata | `headers_to_split_on` |
| `SemanticChunker` | LangChain experimental | Embedding-distance topic-shift splitting | `breakpoint_threshold_type` (`percentile`/`standard_deviation`/`interquartile`/`gradient`), `breakpoint_threshold_amount` |
| `SentenceSplitter` | LlamaIndex | Sentence-aware fixed-size packing | `chunk_size` (default 1024 tokens), `chunk_overlap` (default 200), `paragraph_separator` |
| `SentenceWindowNodeParser` | LlamaIndex | Single-sentence chunks + window metadata | `window_size` (default 3) |
| `SemanticSplitterNodeParser` | LlamaIndex | Semantic chunking | `buffer_size` (1), `breakpoint_percentile_threshold` (95) |
| `HierarchicalNodeParser` | LlamaIndex | Multi-level chunks for auto-merging | `chunk_sizes` (e.g. `[2048,512,128]`) |
| `chunk_by_title` / `partition` | `unstructured` | Layout/element-aware partition + chunk | `max_characters`, `new_after_n_chars`, `combine_text_under_n_chars`, `multipage_sections` |

> There is **no universal default chunk size.** Common starting points: **256–512 tokens** for precise QA, **~1000 tokens** for richer context, with **10–20% overlap**. Always measure (§6.10) rather than trust a default.

### 4.2 Embedding models (representative, version-specific — verify before use)

| Model | Dim | Notes |
|---|---|---|
| OpenAI `text-embedding-3-small` | 1536 (truncatable via Matryoshka) | Cheap, strong general-purpose |
| OpenAI `text-embedding-3-large` | 3072 (truncatable) | Higher quality, higher cost |
| Cohere `embed-english-v3.0` / `embed-multilingual-v3.0` | 1024 | Supports `input_type` (`search_query` vs `search_document`) |
| `BAAI/bge-large-en-v1.5` | 1024 | Open-weights; use `query:`-style instruction |
| `intfloat/e5-large-v2` | 1024 | Open-weights; requires `query:`/`passage:` prefixes |
| `nomic-embed-text-v1.5` | 768 (Matryoshka) | Open-weights, long context, task prefixes |
| `jina-embeddings-v3` | up to 1024 | Long context, task LoRAs |
| `voyage-3` family | varies | Strong retrieval-tuned commercial |

> **Matryoshka embeddings:** trained so that the *first* k dimensions of the vector are themselves a usable (lower-quality) embedding. Lets you truncate 3072→512 to save storage/latency with graceful degradation.

### 4.3 Vector stores / search engines

| System | Type | ANN | Hybrid? | Notes |
|---|---|---|---|---|
| FAISS | library (in-proc) | Flat/IVF/HNSW/PQ | no (DIY) | Meta's C++/Python lib; great for experiments, not a server |
| pgvector | Postgres extension | HNSW/IVFFlat | yes (+ Postgres FTS) | SQL, transactions, joins; operationally familiar to backend teams |
| Elasticsearch / OpenSearch | search engine | HNSW (Lucene kNN) | **native hybrid + RRF** | Mature BM25 + vectors + filters + RRF in one query |
| Qdrant | vector DB | HNSW | yes (sparse+dense) | Rich payload filtering, quantization |
| Weaviate | vector DB | HNSW | yes | Built-in modules, GraphQL |
| Milvus / Zilliz | vector DB | IVF/HNSW/DiskANN | yes | Scales to billions |
| Pinecone | managed vector DB | proprietary | yes (sparse-dense) | Fully managed |
| Vespa | search engine | HNSW | yes | Strong for late-interaction/ColBERT, ranking expressions |
| MongoDB Atlas / Redis / Cassandra | DB + vector | HNSW | partial | Vector add-ons to existing stores |

### 4.4 Re-rankers

| Re-ranker | Type | Notes |
|---|---|---|
| Cohere Rerank (`rerank-english-v3.0`) | cross-encoder API | Strong, simple `rerank(query, docs, top_n)` |
| `BAAI/bge-reranker-large` / `-v2-m3` | open cross-encoder | Self-host; multilingual `-m3` |
| `jina-reranker-v2` | cross-encoder API | Long context |
| sentence-transformers `CrossEncoder` | open | `ms-marco-MiniLM` models; cheap baseline |
| ColBERT / `RAGatouille` | late-interaction | Token-level scoring (§7.6) |

### 4.5 Frameworks & evaluation

| Tool | Purpose |
|---|---|
| LangChain / LangChain4j | Orchestration (Python/JS; **LangChain4j** is the idiomatic JVM port) |
| LlamaIndex | Ingestion/indexing/retrieval framework, rich chunkers |
| Haystack | Pipeline framework (retrievers, rankers) |
| Spring AI | Spring-native RAG (`DocumentReader`, `TextSplitter`, `VectorStore`, `EmbeddingModel`) |
| Ragas | RAG eval: context precision/recall, faithfulness, answer relevancy |
| TREC `trec_eval`, `pytrec_eval`, BEIR | IR metrics (recall@k, MRR, nDCG, MAP) and benchmark suite |
| `ir_measures` | Convenient IR metric computation |

> **LangChain4j / Spring AI** are the two JVM-native RAG stacks. Both expose `EmbeddingModel`, a `VectorStore`/`EmbeddingStore`, and document splitters, mirroring the Python concepts.

### 4.6 Key knobs cheat-list (with typical defaults)

| Knob | Where | Typical default / range |
|---|---|---|
| chunk_size | splitter | 256–1024 tokens |
| chunk_overlap | splitter | 0–20% of chunk_size |
| top_k (dense) | retriever | 5–50 (more before re-rank) |
| top_k (sparse) | retriever | 5–50 |
| final_n (to LLM) | assembler | 3–10 |
| RRF k | fusion | 60 |
| HNSW M | index | 16 |
| HNSW ef_construction | index | 100–200 |
| HNSW ef_search | query | 64–256 (↑ = recall↑, latency↑) |
| BM25 k1 | sparse | 1.2 |
| BM25 b | sparse | 0.75 |
| rerank top_n | re-ranker | 3–10 out of 25–100 |

---

## 5. Code examples by use case

The reader is a JVM engineer, so Java/JVM examples lead; Python and config appear where they're the lingua franca of a tool.

### 5.1 Token-aware recursive chunking with overlap (Java, LangChain4j)

```java
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiTokenizer;

import java.util.List;

public class Chunking {
    public static List<TextSegment> chunk(String text) {
        Document doc = Document.from(text);

        // Recursive splitter: paragraph -> line -> sentence -> word,
        // sized in TOKENS (not chars) using the model's tokenizer so we
        // never overflow the embedding model's input limit.
        DocumentSplitter splitter = DocumentSplitters.recursive(
                512,                              // max tokens per chunk
                64,                               // overlap tokens (~12%)
                new OpenAiTokenizer("gpt-4o-mini") // accurate token counting
        );

        List<TextSegment> segments = splitter.split(doc);
        // Each TextSegment carries text + metadata (index, source) we can enrich.
        return segments;
    }
}
```

Why it matters: sizing in **tokens** (not characters) and respecting the **separator hierarchy** are the two decisions that prevent the most common chunking bugs (truncated embeddings, mid-sentence cuts).

### 5.2 Metadata enrichment + contextual prefix (Java, conceptual)

```java
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

TextSegment enrich(TextSegment seg, DocInfo doc, int idx, String contextPrefix) {
    Metadata md = seg.metadata();
    md.put("source_doc_id", doc.id());
    md.put("title",        doc.title());
    md.put("url",          doc.url());
    md.put("section",      doc.currentHeading());   // for parent-doc expansion
    md.put("created_at",   doc.createdAt().toString());
    md.put("chunk_index",  String.valueOf(idx));
    md.put("tenant_id",    doc.tenantId());          // for hard multi-tenant filter

    // Contextual Retrieval (Anthropic): prepend a 1-2 sentence situating blurb
    // so an out-of-context chunk is still self-explanatory when retrieved.
    String situated = contextPrefix + "\n\n" + seg.text();
    return TextSegment.from(situated, md);
}
```

The `tenant_id` field is load-bearing for security (§6.5): retrieval **must** filter on it or one tenant sees another's data.

### 5.3 Hybrid retrieval with RRF in Elasticsearch/OpenSearch (config + query)

```json
// A single hybrid query: BM25 + kNN dense, fused with Reciprocal Rank Fusion.
{
  "retriever": {
    "rrf": {
      "rank_constant": 60,
      "rank_window_size": 50,
      "retrievers": [
        { "standard": {                              // sparse / BM25
            "query": { "match": { "text": "how to rotate kms keys" } } } },
        { "knn": {                                   // dense / vector
            "field": "vector",
            "query_vector": [/* 1024 floats from the embedding model */],
            "k": 50, "num_candidates": 200,
            "filter": { "term": { "tenant_id": "42" } } } }  // hard isolation
      ]
    }
  }
}
```

Why it matters: BM25 nails the exact token "kms", dense catches "rotate keys"≈"key rotation". RRF (`rank_constant`=60) fuses without needing the two score scales to agree. The `filter` enforces tenant isolation **inside** the ANN search (pre-filter), not after.

### 5.4 pgvector hybrid retrieval (SQL — familiar to backend teams)

```sql
-- Schema
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE chunks (
  id          bigserial PRIMARY KEY,
  doc_id      text,
  tenant_id   text,
  text        text,
  tsv         tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
  embedding   vector(1024)
);
CREATE INDEX ON chunks USING hnsw (embedding vector_cosine_ops);  -- dense ANN
CREATE INDEX ON chunks USING gin (tsv);                           -- sparse FTS

-- Hybrid query with RRF, fused in SQL
WITH dense AS (
  SELECT id, row_number() OVER (ORDER BY embedding <=> :qvec) AS r
  FROM chunks WHERE tenant_id = :tenant
  ORDER BY embedding <=> :qvec LIMIT 50           -- <=> = cosine distance
),
sparse AS (
  SELECT id, row_number() OVER (
           ORDER BY ts_rank_cd(tsv, plainto_tsquery('english', :q)) DESC) AS r
  FROM chunks
  WHERE tenant_id = :tenant AND tsv @@ plainto_tsquery('english', :q)
  LIMIT 50
)
SELECT c.id, c.text,
       COALESCE(1.0/(60+d.r),0) + COALESCE(1.0/(60+s.r),0) AS rrf_score  -- RRF, k=60
FROM chunks c
LEFT JOIN dense d  ON d.id = c.id
LEFT JOIN sparse s ON s.id = c.id
WHERE d.id IS NOT NULL OR s.id IS NOT NULL
ORDER BY rrf_score DESC
LIMIT 10;
```

Why it matters: you get hybrid RAG inside the database you already operate, with transactions, joins, and backups for free — no separate vector service to run.

### 5.5 Parent-document retrieval (LangChain, Python — pattern is language-agnostic)

```python
from langchain.retrievers import ParentDocumentRetriever
from langchain.storage import InMemoryStore
from langchain_text_splitters import RecursiveCharacterTextSplitter

# Retrieve on SMALL precise children, but RETURN their LARGE parents.
child_splitter  = RecursiveCharacterTextSplitter(chunk_size=256)   # embedded/indexed
parent_splitter = RecursiveCharacterTextSplitter(chunk_size=2000)  # returned to LLM

retriever = ParentDocumentRetriever(
    vectorstore=vector_store,    # holds child vectors
    docstore=InMemoryStore(),    # holds parents by id (use Redis/DB in prod)
    child_splitter=child_splitter,
    parent_splitter=parent_splitter,
)
retriever.add_documents(docs)    # splits parents, then children, links them
results = retriever.invoke("What is the refund window for damaged goods?")
# Matches a tight child sentence, but hands the LLM the whole surrounding section.
```

Why it matters: solves the **lost-context problem** (§7.1) — precise matching *and* complete context, the best of both chunk sizes.

### 5.6 Cross-encoder re-ranking a shortlist (Python)

```python
from sentence_transformers import CrossEncoder
reranker = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")

def rerank(query, candidates, top_n=5):
    pairs  = [(query, c["text"]) for c in candidates]   # query+chunk read together
    scores = reranker.predict(pairs)                    # true relevance, not cosine
    ranked = sorted(zip(candidates, scores), key=lambda x: x[1], reverse=True)
    return [c for c, _ in ranked[:top_n]]

# Retrieve 50 cheaply (hybrid), then precisely re-rank to the best 5 for the prompt.
shortlist = hybrid_search(query, k=50)
final     = rerank(query, shortlist, top_n=5)
```

Why it matters: a bi-encoder (embedding) compresses each text to one vector *before* seeing the query; a cross-encoder attends to query and chunk jointly, catching nuance the vector lost. Apply only to a shortlist for cost control.

### 5.7 Code-aware chunking (split a Java codebase by structure)

```python
from langchain_text_splitters import RecursiveCharacterTextSplitter, Language

# Language-aware separators: split on class/method boundaries, not arbitrary lines.
java_splitter = RecursiveCharacterTextSplitter.from_language(
    language=Language.JAVA, chunk_size=1200, chunk_overlap=150
)
chunks = java_splitter.split_text(java_source)
# Keeps methods/classes intact so a retrieved chunk is a coherent unit of code.
```

Why it matters: chunking source on `\n\n` shreds methods. Structure-aware splitting keeps a method (signature + body) together, so retrieval returns something the LLM can actually reason about.

### 5.8 Semantic chunking (Python)

```python
from langchain_experimental.text_splitter import SemanticChunker
from langchain_openai import OpenAIEmbeddings

chunker = SemanticChunker(
    OpenAIEmbeddings(),
    breakpoint_threshold_type="percentile",   # split where sentence-to-sentence
    breakpoint_threshold_amount=95,           # distance jumps into top 5%
)
chunks = chunker.create_documents([long_text])
# Chunks align to topic shifts; size varies. Costs embeddings at ingest time.
```

### 5.9 Evaluating retrieval offline (Python)

```python
def recall_at_k(retrieved_ids, relevant_ids, k):
    top = retrieved_ids[:k]
    return len(set(top) & set(relevant_ids)) / max(1, len(relevant_ids))

def reciprocal_rank(retrieved_ids, relevant_ids):
    for i, rid in enumerate(retrieved_ids, start=1):
        if rid in relevant_ids:
            return 1.0 / i      # MRR contribution: rank of FIRST relevant hit
    return 0.0

# Build a gold set: {query -> set(relevant_chunk_ids)} from real Q&A or labeling.
# Sweep chunk_size, k, hybrid vs dense, rerank on/off; pick by recall@k + nDCG.
```

Why it matters: you cannot tune chunking/retrieval by vibes. A small labeled gold set turns every decision (§3.5) into a measured A/B.

---

## 6. Implementation concerns & best practices

### 6.1 Chunking strategy tradeoffs

| Strategy | Boundary quality | Cost (ingest) | Determinism | Best for | Watch out for |
|---|---|---|---|---|---|
| Fixed-size | poor | trivial | high | quick baselines, uniform text | mid-sentence cuts |
| Fixed + overlap | poor-ish | trivial | high | baselines that must not lose seam facts | duplication, token bloat |
| Recursive/structure | good | low | high | general prose, Markdown, code | needs good separators |
| Sentence/paragraph | good | low | high | clean QA over articles | variable sizes |
| Semantic | very good (topical) | high (embeddings) | low (threshold-sensitive) | dense, topic-shifting docs | tuning, cost |
| Per-doc-type/element | best (fidelity) | medium-high | medium | PDFs, slides, tables, code | most engineering |
| Proposition/atomic | best (precision) | very high (LLM) | low | high-precision QA, fact retrieval | faithfulness drift, cost |
| Hierarchical/RAPTOR | excellent (multi-grain) | high | medium | mixed broad+narrow queries | complexity, storage |

**Default recommendation:** start with **recursive/structure-aware, token-sized 256–512 with ~15% overlap**, measure, and only escalate to semantic/element/parent-doc where the eval says it helps.

### 6.2 Chunk size vs precision vs context cost

- **Small chunks (e.g. 128–256 tokens):** high **precision** (the chunk is mostly about one thing, so its embedding is "pure"), higher **recall** of the *specific* fact, but each chunk lacks surrounding context — the LLM may misread it. More chunks → bigger index, more vectors.
- **Large chunks (e.g. 1000–2000 tokens):** rich context, fewer vectors, but **diluted embeddings** (one vector averaging many topics → weaker matching, "topic blur"), and each retrieved chunk eats more of the token budget, so fewer can fit.
- **Resolution:** decouple *retrieval granularity* from *generation granularity* via **parent-document / sentence-window** retrieval (§7.1): embed small, return large.

Concrete budgeting: if your LLM context for retrieved content is ~8k tokens and you reserve room for system+question+answer, you might fit ~6k tokens of chunks → six 1000-token chunks, or twenty 300-token chunks. More small chunks = more chances to include the answer (recall), but more noise; fewer big chunks = more context per hit but fewer hits. Tune empirically.

### 6.3 Performance

- **Ingest:** embedding is the bottleneck; batch (32–256 texts/call), parallelize, cache embeddings keyed by content hash so re-runs are cheap. ANN build (HNSW) is CPU-heavy — bulk-load then build, don't insert one-by-one.
- **Query latency budget (rough):** embed query (1–30 ms local / 30–300 ms API) + ANN search (1–20 ms) + optional rerank (10–200 ms for 50 docs) + LLM generation (the dominant cost, hundreds of ms to seconds). Re-ranking and multi-query each add a network round trip — keep them off the hot path unless eval shows they pay.
- **Memory:** HNSW typically keeps the full graph + vectors in RAM. 10M × 1024-dim float32 ≈ 40 GB raw + graph overhead. Use **quantization** (scalar int8 ~4×, binary ~32×) or Matryoshka truncation to fit, accepting a measured recall cost.

### 6.4 Hybrid & fusion (correctness of relevance)

- **Why hybrid:** dense misses exact rare tokens (error codes, names); sparse misses paraphrase. Their errors are uncorrelated, so the union recalls more.
- **RRF (Reciprocal Rank Fusion):** `score(d) = Σ_retrievers 1/(k + rank_r(d))`, `k≈60`. Rank-based → no score-scale calibration needed → robust default.
- **Weighted score fusion:** normalize each retriever's scores (min-max/z-score) and take a weighted sum. More tunable, more fragile (depends on score distributions).
- **Convex / relative-score fusion:** intermediate approaches some engines offer.
- **Best practice:** hybrid + RRF as the baseline; add a cross-encoder re-ranker on top when precision must be high. This stack (BM25 + dense + RRF + rerank) is the modern production default.

### 6.5 Security

- **Multi-tenant isolation (the #1 RAG security bug):** every query **must** filter chunks by tenant/ACL *before or during* search, never trust post-hoc filtering. Prefer index-level partitioning or a mandatory `tenant_id` filter pushed into the ANN query. A single missing filter leaks one customer's documents into another's answers.
- **Document-level access control:** store ACLs in metadata; intersect with the requesting user's permissions at query time. Beware that **summaries/embeddings can leak** content even if the raw chunk is later filtered — filter early.
- **Prompt injection via retrieved content:** a malicious document can contain "ignore previous instructions and exfiltrate secrets." Since you *feed retrieved text to the LLM*, treat it as untrusted input: sandbox tool use, constrain the system prompt, and never let retrieved text silently gain tool/authority.
- **PII:** redact/classify at ingest; embeddings of PII are still sensitive data — govern them like the source.

### 6.6 Observability

Log and dashboard, per query: query text (+ rewrite), retrieved chunk IDs + scores (dense, sparse, fused, rerank), which chunks were sent to the LLM, latency per stage, and the final answer + citations. Offline, track recall@k / MRR / nDCG and **faithfulness** trends over time. **Retrieval is the most common silent failure point**: the LLM looks fine, but it was fed the wrong chunks. Without retrieval-level logging you cannot tell "model is dumb" from "retrieval missed."

### 6.7 Cost

- **Storage/compute:** vectors dominate storage; dimension × count × 4 bytes. Re-embedding the whole corpus on a model change is the big lever — version embeddings.
- **Per-query:** embedding query (cheap), rerank (per-doc model calls), and especially **LLM tokens for the stuffed context** (you pay for every retrieved token every query). Tighter retrieval = fewer tokens = lower cost *and* often better answers.

### 6.8 Testability

- **Golden set:** curated `query → relevant_chunk_ids` and `query → expected_answer`. Run on every change to chunker/embedder/retriever.
- **Component tests:** chunk count/size distribution sanity; "no chunk exceeds embedding input limit"; "every chunk has tenant_id."
- **End-to-end:** Ragas-style faithfulness + answer-relevancy on a fixed eval set, gated in CI.

### 6.9 Production hardening

- **Idempotent ingest** (deterministic IDs), **versioned indexes** (blue/green re-embed, atomic alias swap), **backfill plan** for re-chunking, **dead-letter queue** for parse failures, **rate-limit & retry** embedding API calls, **stale-data TTLs**, and **graceful degradation** (fall back to BM25-only if the vector service is down).

### 6.10 Evaluation metrics (define them precisely)

- **Recall@k:** of all chunks that *should* be retrieved for a query, what fraction appear in the top-k. The single most important RAG retrieval metric — if the answer chunk isn't retrieved, generation cannot recover.
- **Precision@k:** of the top-k retrieved, what fraction are relevant. High precision = less noise/cost.
- **MRR (Mean Reciprocal Rank):** average of 1/rank of the *first* relevant hit. Rewards putting a relevant result high; ignores the rest.
- **MAP (Mean Average Precision):** averages precision at each relevant hit's rank; rewards ranking *all* relevant items high.
- **nDCG@k (normalized Discounted Cumulative Gain):** sums graded relevance discounted by log(rank), normalized to [0,1]. The best single ranking metric when relevance is graded (not just binary).
- **Context precision / context recall (Ragas):** RAG-specific — did retrieved context contain the ground-truth answer (recall) and was it un-padded with junk (precision).
- **Faithfulness / answer relevancy:** generation-side — is the answer supported by the context, and does it address the question.

> **DCG intuition:** a relevant doc at rank 1 is worth its full relevance; at rank 5 it's discounted by log₂(6). nDCG divides by the best-possible ordering so 1.0 = perfect ranking.

### 6.11 Anti-patterns to avoid

- Splitting by fixed characters with no overlap on prose → mid-sentence cuts, lost seam facts.
- One giant chunk per document → diluted embeddings, no precision.
- Different embedding model (or missing query/passage prefix) at query vs ingest → silent recall collapse.
- Dense-only because it's trendy → missing exact codes/names BM25 would have nailed.
- Stuffing top-50 chunks "to be safe" → cost, latency, and "lost in the middle" accuracy loss.
- No metadata / no citations → unverifiable answers, no tenant isolation.
- Tuning by vibes with no golden set → unrepeatable, unfalsifiable.
- Re-embedding only new docs after switching models, leaving a mixed index → incomparable vectors.

---

## 7. Advanced topics & deep internals

### 7.1 The lost-context problem & parent-document retrieval

Small chunks retrieve precisely but read ambiguously ("It costs $40" — what's "it"?). Large chunks read clearly but retrieve poorly (diluted vectors). **Parent-document retrieval** decouples the two: **embed and search on small child chunks**, but **return their larger parent** (section/page/document) to the LLM. Implementations: LangChain `ParentDocumentRetriever`, LlamaIndex `SentenceWindowNodeParser` (retrieve a sentence, expand to a window), and **auto-merging retrieval** (if many sibling leaf chunks under a parent are retrieved, merge them up to the parent automatically via `HierarchicalNodeParser` + `AutoMergingRetriever`).

### 7.2 Two-stage retrieval: bi-encoder then cross-encoder

- **Bi-encoder** (the embedding model): encodes query and chunk *independently* into vectors; similarity = cheap dot product. Scales to billions (precompute chunk vectors) but loses query-chunk interaction.
- **Cross-encoder** (the re-ranker): feeds `[query [SEP] chunk]` through a Transformer that **attends across both**, outputting a single relevance score. Far more accurate, but O(N) model forward passes per query → only feasible on a shortlist. The canonical pattern: **bi-encoder recalls 50–200, cross-encoder re-ranks to 3–10.**

### 7.3 Contextual Retrieval (Anthropic, 2024)

Before embedding each chunk, prepend a 1–2 sentence, LLM-generated description situating the chunk within its whole document ("This chunk is from the Q2 2023 10-K, Risk Factors section, discussing supply-chain exposure…"). This **Contextual Embeddings** + a parallel **Contextual BM25** index, fused with RRF and re-ranked, was reported to cut retrieval failure substantially versus naive chunking. Prompt caching makes generating the per-chunk context affordable. It directly attacks the lost-context problem at *index* time rather than *query* time.

### 7.4 Query transformations

- **HyDE:** embed a hypothetical answer instead of the question (closes the query↔passage style gap).
- **Multi-query / RAG-Fusion:** generate several query variants, retrieve for each, RRF the union — boosts recall on ambiguous queries.
- **Step-back prompting:** ask a more general question first to retrieve background, then the specific one.
- **Decomposition:** break a multi-hop question into sub-questions, retrieve per sub-question.

### 7.5 "Lost in the middle"

LLMs attend most strongly to the **start and end** of a long context and can miss facts buried in the **middle**. Mitigations: retrieve *fewer, better* chunks (re-rank); place the most relevant chunks at the **edges** of the context; keep total context tight even when the window is huge.

### 7.6 Multi-vector & late interaction (ColBERT)

- **Single-vector (bi-encoder):** one vector per chunk — fast, but a lossy summary.
- **Multi-vector / late interaction (ColBERT):** store **one vector per token** of the chunk. At query time, for each *query* token, take the **MaxSim** (max similarity to any chunk token), then sum — "late interaction" because the fine-grained matching happens at query time, not collapsed at index time. Far better recall on exact and compositional matches, at large storage cost (many vectors/chunk). Tooling: ColBERTv2, PLAID index, RAGatouille, Vespa. **ColPali** extends this to documents-as-images for visually rich PDFs.
- **Sparse-learned vectors (SPLADE):** a model predicts a sparse, expanded bag-of-weighted-terms (including related terms the text didn't literally contain) — "learned sparse retrieval," indexable in an inverted index, combining BM25's efficiency with neural semantics.

### 7.7 RAPTOR (hierarchical summary tree)

Recursively **cluster** chunks and **summarize** each cluster with an LLM, building a tree from raw leaf chunks up to high-level summaries. Retrieval can match at any level: leaves for specifics, summaries for broad "what is this document about" questions. Strong for queries needing synthesis across a long document.

### 7.8 Tuning knobs & lesser-known behavior

- **ANN recall ≠ retrieval recall:** raising `ef_search`/`nprobe` improves the chance ANN finds the true nearest vectors, but if your *chunking* dropped the answer, no ANN setting helps.
- **Normalization:** many open models require L2-normalized vectors and specific instruction prefixes; cosine vs dot must match how the model was trained.
- **Embedding input truncation is silent:** exceed the model's token limit and the tail is dropped without error — a stealthy cause of "the answer is in the doc but never retrieved."
- **Distance vs similarity sign:** pgvector `<=>` is *distance* (smaller better); many APIs return *similarity* (larger better) — mixing them inverts ranking.
- **Drift:** corpus topics, query patterns, and even the embedding model version drift; schedule periodic re-eval and re-embed.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Retrieval method selection

| Method | Recall (semantic) | Recall (exact terms) | Cost | Latency | Use when… | Avoid when… |
|---|---|---|---|---|---|---|
| BM25 (sparse) | low | high | very low | very low | jargon/codes/names dominate; no GPU; explainability needed | paraphrase-heavy queries |
| Dense (bi-encoder) | high | medium | medium | low | natural-language/paraphrase queries | exact rare tokens matter most |
| Hybrid + RRF | high | high | medium | low-medium | **default production choice** | extreme latency/cost limits |
| + Cross-encoder rerank | high (precision↑) | high | high | medium | precision-critical, small shortlist OK | tight latency budgets |
| ColBERT/late-interaction | very high | very high | high (storage) | medium | top-tier recall, storage available | storage-constrained |

### 8.2 Chunking strategy selection

Use **recursive/structure-aware** by default. Switch to **semantic** when documents shift topics within sections and eval shows gains. Use **element/layout-aware** for PDFs/slides/tables/code. Add **parent-document/sentence-window** whenever you see "right topic, missing context" failures. Reach for **proposition/RAPTOR** only for high-precision or synthesis-heavy workloads where the cost is justified by measured improvement.

### 8.3 RAG vs alternatives

| Approach | Adds new *facts*? | Adds new *behavior/style*? | Fresh data? | Cost model | Use when |
|---|---|---|---|---|---|
| RAG (chunk+retrieve) | yes | no | yes (re-index) | per-query tokens + index | corpus-grounded, changing knowledge, citations |
| Long-context stuffing | yes (if it fits) | no | yes | high per-query tokens | small/static corpus, simplicity > cost |
| Fine-tuning | weakly | **yes** | no (retrain) | training + serving | change style/format/skill, not facts |
| Prompt caching | n/a | no | n/a | amortized prefix | repeated large static prefixes |

Often combined: fine-tune for behavior + RAG for facts.

### 8.4 Index/store selection

- **pgvector** if you already run Postgres and want SQL/transactions/joins and moderate scale.
- **Elasticsearch/OpenSearch** if you need mature BM25 + native hybrid/RRF + filters in one engine.
- **Qdrant/Weaviate/Milvus/Pinecone** for vector-first scale, rich filtering, managed ops.
- **Vespa** for late-interaction/ColBERT and complex ranking expressions.
- **FAISS** for research/prototyping, not as a production server.

---

## 9. Failure modes & debugging

### 9.1 "The answer is in the docs but the bot says it doesn't know" (retrieval miss)

- **Diagnose:** log retrieved chunk IDs+scores. Manually check: is the answer chunk in the index at all? In the top-k? Embed the gold answer chunk and the query and compare cosine directly.
- **Common causes & fixes:** chunk too big (answer diluted) → smaller chunks / parent-doc; missing query/passage prefix → fix preprocessing; exact term lost → add BM25/hybrid; embedding truncation → reduce chunk size below model limit; wrong/changed embedding model between ingest and query → re-embed consistently; ANN recall too low → raise `ef_search`/`nprobe`.

### 9.2 "It retrieves irrelevant chunks" (low precision)

- **Diagnose:** inspect fused scores; are sparse hits flooding with stopword matches? Is dense matching surface similarity?
- **Fixes:** add a cross-encoder re-ranker; lower final `n`; raise BM25 specificity; add metadata filters; improve chunk boundaries so embeddings are purer.

### 9.3 "Right info retrieved, wrong answer" (generation, not retrieval)

- **Diagnose:** confirm the answer chunk was actually in the prompt (log assembled context). If yes, it's a generation/prompt problem.
- **Fixes:** "lost in the middle" → fewer chunks, reorder best to edges; weak instruction → strengthen "answer only from context, cite" prompt; conflicting chunks → de-duplicate, prefer freshest via metadata.

### 9.4 "Cross-tenant leak" (security incident)

- **Diagnose:** a user saw another tenant's content. Check whether the `tenant_id` filter was applied *inside* the ANN/BM25 query for that code path.
- **Fix:** make the tenant filter mandatory and unbypassable (enforced in a shared retrieval layer, not per-call); add a test that a tenant-A query can never return a tenant-B chunk; consider physical index partitioning.

### 9.5 "Latency spikes / OOM"

- **Diagnose:** which stage? `ef_search` too high, re-ranker batch too large, embedding API throttling, or HNSW graph exceeding RAM.
- **Fixes:** quantize/truncate vectors, cap `ef_search`, batch+cache embeddings, move re-rank off hot path or shrink shortlist, add disk-backed ANN (DiskANN) for huge corpora.

### 9.6 "Quality silently degraded after a deploy"

- **Diagnose:** did the embedding model version, tokenizer, chunker config, or normalization change? A mixed-version index is the classic culprit.
- **Fix:** version everything; gate deploys on the golden-set eval (recall@k/nDCG must not regress); blue/green re-index with atomic swap.

### 9.7 Real-world failure patterns (illustrative, not vendor-attributed)

- A PDF pipeline chunked two-column papers in left-then-right *visual* order, interleaving unrelated sentences → embeddings meaningless → recall near zero until a layout-aware parser fixed reading order.
- A team switched embedding models but only re-embedded *new* documents; old vectors were from the prior model → silent recall collapse on legacy docs, invisible until a golden-set eval caught it.
- A chatbot returned other customers' contract clauses because a new "search all docs" code path forgot the tenant filter that the original path enforced.
- Dense-only retrieval couldn't find error code `ORA-00942` (an exact, rare token); adding BM25 hybrid fixed it instantly.

---

## 10. Interview drill

**Q1. Why does chunking determine RAG quality more than the LLM choice?**
Model answer: Retrieval is the gate — if the answer-bearing text isn't in the retrieved context, no model can produce a grounded answer (it will abstain or hallucinate). Chunking sets what *can* be retrieved: bad boundaries dilute embeddings (low precision) or split facts (low recall). So the cheap, early decisions (chunk size, overlap, boundaries) cap the achievable quality.
- *Probe: how would you prove this empirically?* Build a golden `query→relevant_chunk_id` set; measure recall@k across chunking configs; show answer quality tracks retrieval recall, not model size.
- *Probe: when is the LLM the bottleneck instead?* When recall@k is high but the model still answers wrong — usually "lost in the middle" or weak grounding instructions.
- *Probe: a counterexample where chunking doesn't matter?* Tiny static corpus that fits the window — just stuff it; no chunking needed.

**Q2. Compare dense, sparse, and hybrid retrieval. When does each win?**
Model answer: Sparse (BM25) matches exact terms/stems — great for codes, names, jargon; blind to synonyms. Dense (embeddings) matches meaning — great for paraphrase; can miss exact rare tokens and depends on the embedding model's domain. Hybrid fuses both (RRF) because their errors are complementary; it's the production default.
- *Probe: why RRF over weighted score sum?* RRF uses only ranks, so it needs no calibration between incompatible score scales (cosine vs BM25); robust out of the box.
- *Probe: a query where dense loses?* Exact identifier like `ORA-00942` or a part number — BM25 nails it, dense may not.
- *Probe: a query where sparse loses?* "How do I make my app start faster?" vs a doc titled "Reducing JVM cold-start latency" — no shared keywords, dense wins.

**Q3. Walk through the ingestion and query lifecycles.**
Model answer: Ingest: parse → normalize → chunk → enrich metadata → embed → upsert/index. Query: (rewrite) → embed query → dense + sparse search → fuse (RRF) → (rerank) → (expand to parent) → assemble prompt → generate with citations. Key invariant: same embedding model/preprocessing at ingest and query.
- *Probe: where do most silent failures hide?* Parsing (PDF reading order, tables) and embedding truncation/model mismatch.
- *Probe: pre-filter vs post-filter for tenancy?* Pre-filter inside the ANN/BM25 query for correctness and latency; post-filter risks leaks and wastes recall slots.

**Q4. Explain the lost-context problem and how parent-document retrieval solves it.**
Model answer: Small chunks retrieve precisely but read ambiguously; large chunks read well but retrieve poorly (diluted vectors). Parent-document retrieval embeds/searches small children but returns their larger parent to the LLM — precise matching plus full context.
- *Probe: alternatives?* Sentence-window (retrieve sentence, expand neighbors); auto-merging (merge sibling leaves to parent); contextual retrieval (situate at index time).
- *Probe: cost of parent expansion?* More tokens per hit → fewer hits fit, higher cost; tune parent size and final n.

**Q5. What is a cross-encoder re-ranker and why not just use it for everything?**
Model answer: It reads (query, chunk) together with full cross-attention, scoring relevance far more accurately than independent bi-encoder vectors. But it requires one model forward pass per candidate, so it's O(N) per query — infeasible over millions. Pattern: bi-encoder recalls a shortlist (50–200), cross-encoder re-ranks to 3–10.
- *Probe: latency mitigation?* Smaller reranker, smaller shortlist, batch on GPU, cache.
- *Probe: how to know if it's worth it?* Eval: does nDCG@k / answer faithfulness improve enough to justify the latency/cost?

**Q6. How do you choose chunk size and overlap?**
Model answer: There's no universal default; start 256–512 tokens, 10–20% overlap, sized in *tokens* with the right tokenizer, then sweep on a golden set measuring recall@k and nDCG, plus context cost. Decouple retrieval vs generation granularity with parent-doc if "right topic, missing context" appears.
- *Probe: why size in tokens not characters?* To respect the embedding model's input limit and the LLM's budget precisely; char counts mislead across languages/code.
- *Probe: downside of large overlap?* Storage/token bloat and duplicate hits crowding top-k.

**Q7 (senior-signal). You have 50M documents, p95 latency budget 300 ms for retrieval, and a tight infra cost ceiling. Design the retrieval stack and justify every tradeoff.**
Model answer: Hybrid BM25 + dense with HNSW (quantized int8 or Matryoshka-truncated vectors to fit RAM/cost), RRF fusion, mandatory tenant pre-filter. Skip cross-encoder on the hot path (latency); instead invest in better chunking + contextual embeddings to lift first-stage recall. Cap `ef_search` to hit p95; pre-compute and cache query embeddings for common queries. Re-ranking offered only for a "precise mode" or async flows. Justify: re-ranking's per-doc cost and latency violate the budget at this scale; quantization trades a measured small recall loss for fitting memory/cost; hybrid maximizes recall so the (cheaper) first stage suffices.
- *Probe: when would you add the re-ranker anyway?* If golden-set nDCG is too low and the latency budget can absorb a small reranker on a 25-doc shortlist (batched on GPU).
- *Probe: how to fit 50M×1024 vectors cost-effectively?* int8 scalar quantization (~4×) or binary (~32×) with a re-score pass, or DiskANN for disk-backed ANN.

**Q8 (senior-signal). Your RAG answers regressed after a model upgrade though nothing in the prompt changed. Diagnose.**
Model answer: Most likely an embedding model/tokenizer/preprocessing change creating a *mixed-version index*, or a chunker config change. Reproduce on the golden set; bisect by component (re-embed a sample with old vs new model; compare recall@k). Fix by full blue/green re-embed with atomic alias swap and a CI gate that blocks deploys regressing recall@k/nDCG.
- *Probe: how to prevent recurrence?* Version embeddings + chunker config in the index metadata; eval gate in CI; never partial re-embed across model versions.
- *Probe: what if only some tenants regressed?* Their docs were on the old model (partial backfill) — finish the backfill.

**Q9 (senior-signal). Justify hybrid + RRF vs investing the same effort in a bigger embedding model.**
Model answer: A bigger embedding model improves semantic recall but still structurally cannot match arbitrary exact rare tokens as reliably as an inverted index, and it costs more storage/latency. Hybrid + RRF adds lexical recall *cheaply* and is robust without score calibration. Effort-for-effort, hybrid usually yields more recall per dollar than a dimensionality/model bump — and they're not exclusive; do hybrid first, then upgrade the embedder if eval still demands it.
- *Probe: a case where the bigger model wins?* Heavily multilingual or domain-shifted corpora where the small model's semantics are weak; or when lexical overlap is already high.
- *Probe: how to decide?* Ablate on the golden set: hybrid-with-small vs dense-with-large; pick by recall@k/nDCG per cost.

**Q10. Which metrics do you track for retrieval, and what does each tell you?**
Model answer: Recall@k (did we get the answer chunk at all — the gate), Precision@k (noise/cost), MRR (is the first relevant hit near the top), nDCG@k (overall graded ranking quality), plus Ragas context precision/recall and faithfulness for the RAG-specific and generation sides.
- *Probe: which single metric if you had one?* Recall@k for retrieval — without it generation can't succeed; nDCG if ranking quality is the concern.
- *Probe: how to build the gold set cheaply?* Mine real Q&A/support tickets, or LLM-generate questions per chunk then human-spot-check.

**Q11. How do you secure a multi-tenant RAG system?**
Model answer: Mandatory tenant/ACL pre-filter pushed into the search query (never post-hoc), enforced in a shared retrieval layer; treat retrieved text as untrusted (prompt-injection); redact PII at ingest; test that tenant-A queries can never surface tenant-B chunks.
- *Probe: physical vs logical isolation?* Physical (per-tenant index/namespace) is safest but costlier; logical (filter) scales better but must be bulletproof.
- *Probe: prompt injection mitigation?* Constrain system prompt authority, sandbox tools, don't let retrieved content trigger privileged actions.

**Q12. Explain ColBERT/late interaction vs single-vector embeddings at a high level.**
Model answer: Single-vector compresses a chunk to one vector before seeing the query — lossy. ColBERT stores one vector per token and, at query time, for each query token takes the max similarity over chunk tokens and sums (MaxSim) — fine-grained "late" interaction that recovers exact/compositional matches, at high storage cost.
- *Probe: why not always use it?* Storage (many vectors/chunk) and specialized indexing (PLAID/Vespa); overkill if hybrid+rerank already meets the bar.
- *Probe: relation to cross-encoders?* Both add interaction; ColBERT is cheaper than a full cross-encoder per doc because token vectors are precomputed.

---

## 11. Glossary

- **ANN (Approximate Nearest Neighbor):** fast, slightly-inexact nearest-vector search (HNSW, IVF, PQ) trading a little recall for big speed.
- **Auto-merging retrieval:** merge many retrieved sibling leaf chunks up to their shared parent automatically.
- **BEIR:** a benchmark suite of diverse IR datasets for zero-shot retrieval evaluation.
- **Bi-encoder:** encodes query and document independently into vectors; the standard embedding retriever.
- **BM25:** the dominant sparse lexical scoring function (TF saturation `k1`, length norm `b`).
- **BPE / SentencePiece:** subword tokenization algorithms.
- **Chunk:** an independently embedded/retrieved unit of text plus metadata.
- **ColBERT:** late-interaction retriever storing per-token vectors, scoring via MaxSim.
- **Context window:** max tokens an LLM can read at once.
- **Contextual Retrieval:** prepend an LLM-generated situating blurb to each chunk before indexing.
- **Cosine similarity:** angle-based vector similarity, magnitude-insensitive.
- **Cross-encoder:** scores a (query, doc) pair jointly with attention; accurate, expensive; used for re-ranking.
- **Dense retrieval:** retrieval by embedding-vector similarity (semantic).
- **DCG / nDCG:** ranking metrics discounting relevance by position; normalized to [0,1].
- **Embedding:** a vector encoding text meaning.
- **Embedding model:** the network that produces embeddings.
- **ef_search / ef_construction / M:** HNSW knobs (query width / build width / connectivity).
- **FAISS:** Meta's ANN library.
- **Fusion (RRF):** combine multiple ranked lists; RRF = Σ 1/(k+rank).
- **HNSW:** graph-based ANN index.
- **HyDE:** embed a hypothetical answer to improve dense recall.
- **Hybrid retrieval:** combine dense + sparse.
- **Inverted index:** term → posting list; powers BM25/keyword search.
- **IVF / PQ:** clustering-based ANN / vector compression.
- **L2 distance / normalization:** Euclidean distance / scaling vectors to unit length.
- **Late interaction:** query-time token-level matching (ColBERT).
- **LangChain / LangChain4j / LlamaIndex / Haystack / Spring AI:** RAG orchestration frameworks (LangChain4j and Spring AI are JVM-native).
- **LLM:** large language model with a finite context window.
- **Lost in the middle:** LLMs under-attend to content in the middle of long contexts.
- **MAP / MRR:** mean average precision / mean reciprocal rank (ranking metrics).
- **Matryoshka embeddings:** truncatable embeddings whose prefix is itself usable.
- **Metadata:** structured fields on a chunk (source, title, date, tenant, ACL) for filtering/citation.
- **Multi-query / RAG-Fusion:** generate several query variants and fuse their results.
- **nprobe:** IVF query-time clusters-to-search knob.
- **Overlap:** shared tokens between adjacent chunks to preserve seam context.
- **Parent-document retrieval:** search small children, return larger parents.
- **pgvector:** Postgres vector extension.
- **Precision@k / Recall@k:** fraction of top-k relevant / fraction of relevant retrieved in top-k.
- **Proposition chunking:** split text into atomic factual statements.
- **Quantization (scalar/binary/PQ):** compress vectors to save memory at a recall cost.
- **RAG (Retrieval-Augmented Generation):** retrieve relevant text and condition the LLM on it.
- **RAPTOR:** recursive cluster-and-summarize tree over chunks for multi-granularity retrieval.
- **Re-ranker:** second-stage model reordering a shortlist (usually a cross-encoder).
- **Recursive splitting:** split on a hierarchy of separators, recursing only when too big.
- **RRF (Reciprocal Rank Fusion):** rank-based list fusion, `k≈60`.
- **Semantic chunking:** split where consecutive-sentence embedding distance spikes (topic shift).
- **Sentence-window:** retrieve a sentence, return it plus neighboring sentences.
- **SPLADE:** learned sparse retrieval (neural term expansion in an inverted index).
- **Sparse retrieval:** lexical/keyword retrieval (BM25/TF-IDF).
- **Tika (Apache):** JVM content extraction across ~1000 formats.
- **TF-IDF:** term frequency × inverse document frequency weighting.
- **Token / tokenizer:** subword unit / the mapper from text to token IDs (~4 chars/token English).
- **Top-k:** number of results a retriever returns.
- **`unstructured`:** library that partitions documents into typed layout elements.
- **Vector store / vector DB:** system that indexes and ANN-searches embeddings (pgvector, Qdrant, Weaviate, Milvus, Pinecone, OpenSearch, Vespa).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

```
PIPELINE:  parse → normalize → CHUNK → enrich metadata → EMBED → index
           query → (rewrite/HyDE) → embed → [dense kNN + BM25] → RRF
                 → (cross-encoder rerank) → (parent/window expand) → prompt → LLM

CHUNKING:  start recursive/structure-aware, 256–512 tokens, 10–20% overlap,
           sized in TOKENS. Escalate to semantic/element/parent-doc per EVAL.
           Big chunk = rich context, diluted vector. Small = precise, no context.
           Fix lost-context: embed small, RETURN parent (or sentence-window).

RETRIEVAL: dense=meaning, sparse(BM25)=exact terms. HYBRID + RRF (k=60) = default.
           Two-stage: bi-encoder recall 50–200 → cross-encoder rerank to 3–10.
           ColBERT/late-interaction = per-token vectors, top recall, big storage.

KEY KNOBS: chunk_size 256–1024 | overlap 0–20% | top_k 5–50 | final_n 3–10
           RRF k=60 | HNSW M=16, ef_construction 100–200, ef_search 64–256
           BM25 k1=1.2 b=0.75 | rerank top_n 3–10 of 25–100

METRICS:   Recall@k (THE gate) · Precision@k · MRR · MAP · nDCG@k
           Ragas: context precision/recall · faithfulness · answer relevancy

INVARIANTS: same embedding model+preprocessing at ingest & query (or recall dies)
            never exceed embedding input token limit (silent truncation)
            mandatory tenant/ACL PRE-filter inside the search (no leaks)
            version embeddings + chunker config; gate deploys on golden-set eval

NUMBERS:   1 token ≈ 4 chars ≈ 0.75 word; 750 words ≈ 1000 tokens
           10M × 1024-dim float32 ≈ 40 GB raw (quantize/truncate to fit)

FAILURE TRIAGE: not retrieved? → chunking/embedding/ANN-recall/model-mismatch
                irrelevant?     → add rerank, lower n, filters
                retrieved-but-wrong-answer? → lost-in-middle / weak grounding prompt
                leaked tenant?  → missing pre-filter
```

### 12.2 Self-test (no answers — recall actively)

1. You retrieve the right *topic* but the LLM keeps answering with missing context (ambiguous pronouns). Which two techniques fix this, and what exactly do they change about *what is embedded* vs *what is returned*?
2. A query for the error code `KMS-1042` returns nothing useful from a dense-only system. Explain mechanistically why, and how hybrid + RRF fixes it without any score-scale calibration.
3. You must fit 50M × 3072-dim vectors under a strict RAM budget. List three independent levers and the recall cost of each.
4. Derive, step by step, why using `text-embedding-3-large` for ingest but `bge-large` for queries produces near-random retrieval — and what the symptom looks like in your recall@k dashboard.
5. Given an 8k-token context budget with ~2k reserved for system+question+answer, you must choose between six 1000-token chunks and twenty 300-token chunks. Argue both sides in terms of recall, precision, noise, cost, and "lost in the middle," then state how you'd decide empirically.
6. Explain why nDCG@10 can improve while recall@10 stays flat, and what that tells you about which part of the stack you changed.
7. Write the RRF formula and explain, in one sentence each, why it uses ranks (not raw scores) and what the constant `k=60` controls.
```

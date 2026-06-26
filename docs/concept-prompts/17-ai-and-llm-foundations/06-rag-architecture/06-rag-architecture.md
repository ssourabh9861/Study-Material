# RAG Architecture — A Definitive Engineering Handbook Chapter

> Reader profile: a senior Java/JVM backend developer who wants to fully master Retrieval-Augmented Generation (RAG) — from first principles to deep internals — well enough to design, operate, debug, teach, and pass any interview on it.

---

## 1. Overview & where it fits

### What it is

**Retrieval-Augmented Generation (RAG)** is an architecture that makes a **Large Language Model (LLM)** answer questions using *external knowledge fetched at query time* instead of relying only on what the model memorized during training. In one sentence: **RAG = search + prompt-stuffing + generation.** You retrieve relevant documents from a knowledge source, paste them into the model's prompt as context, and ask the model to answer *using that context*.

Before we go further, three terms a newcomer needs:

- **LLM (Large Language Model):** a neural network (e.g., GPT-4, Claude, Llama, Mistral) trained to predict the next token of text. It has "parametric knowledge" — facts baked into its billions of weights during training. It does not "look anything up" on its own; it generates plausible continuations.
- **Token:** the unit an LLM reads and writes. Roughly ~4 characters or ~0.75 words of English per token. "Tokenization" is splitting text into these units. Models have a maximum **context window** (the total tokens of input + output they can handle in one call), e.g., 8K, 128K, or 1M tokens.
- **Hallucination:** when an LLM produces fluent, confident text that is factually wrong or fabricated (a fake citation, a made-up API method, a nonexistent date). It happens because the model optimizes for plausibility, not truth.

### The problem it solves

An LLM by itself has four hard limitations:

1. **Stale knowledge.** Its parametric memory is frozen at its **training cutoff** (the date after which it saw no data). Ask it about an event last week and it cannot know.
2. **No private/proprietary knowledge.** It never saw your company's wiki, your codebase, your customer's contract, your internal runbooks.
3. **Hallucination.** With no grounding, it invents answers when uncertain.
4. **No provenance.** It cannot tell you *where* an answer came from, so you cannot verify or cite it.

RAG fixes all four **without retraining the model.** You keep the model fixed and change *what you put in its context window* at inference time. The knowledge lives in an external store you control and update freely.

### When you reach for it

Reach for RAG when **the answer depends on a specific, possibly-changing, possibly-private corpus** that is too large to fit in a prompt and too dynamic to bake into model weights. Canonical use cases: enterprise document Q&A, customer-support assistants over a knowledge base, "chat with your codebase/PDFs," internal search, legal/medical/financial assistants that must cite sources, and any chatbot that must answer about *today's* data.

Avoid RAG (or combine it with other tools) when the task is reasoning-heavy with little factual lookup (e.g., "write a poem", "refactor this code"), when the knowledge is small enough to just paste into the prompt, or when you need a *behavioral* change (tone, format, a new skill) rather than new facts — that is a fine-tuning job.

### The one-paragraph mental model

Think of the LLM as a brilliant but amnesiac expert locked in a room with no internet. **RAG is the open-book exam:** before asking your question, a fast librarian (the retriever) runs into a vast archive, grabs the few pages most relevant to your question, and slides them under the door. The expert reads those pages and answers *based on them*, quoting page numbers. The expert's reasoning skill is unchanged; you have only changed what is on the desk. Everything in RAG engineering is about making that librarian fast, accurate, and trustworthy — because **if the wrong pages arrive, even a genius gives a wrong answer.**

---

## 2. Foundations from first principles

We build the whole machine from zero. Every term is defined as it appears.

### 2.1 Why "just prompt the model" isn't enough

The naive approach is to paste all your documents into the prompt. Two walls stop you:

- **The context window is finite.** Even a 1M-token window (~750k words, ~1,500 pages) cannot hold a 10-million-document corpus.
- **Cost and latency scale with input tokens.** You pay per input token and the model gets slower (and often *less accurate* — see "lost in the middle" in §7) as you stuff more in. Sending 1M tokens on every query is wasteful when only 2 KB is relevant.

So you need to **select** the relevant slice first. Selection by keyword search is brittle ("car" won't match a doc that says "automobile"). The breakthrough enabling modern RAG is **semantic search via embeddings.**

### 2.2 Embeddings — the core primitive

An **embedding** is a fixed-length vector of floating-point numbers (e.g., 384, 768, 1536, or 3072 dimensions) that represents the *meaning* of a piece of text. An **embedding model** (a neural network like OpenAI `text-embedding-3-small`, Cohere `embed-v3`, or open-source BGE/E5/`all-MiniLM`) maps text → vector such that **texts with similar meaning land close together in vector space**, even if they share no words.

- "How do I reset my password?" and "I forgot my login credentials" produce nearby vectors.
- "How do I reset my password?" and "What is the capital of France?" produce far-apart vectors.

**Closeness is measured by a distance/similarity metric:**

- **Cosine similarity:** the cosine of the angle between two vectors; ranges −1 to 1; 1 means identical direction. Most common for text because it ignores magnitude and focuses on direction (meaning).
- **Dot product (inner product):** sums element-wise products; if vectors are normalized to unit length, it equals cosine similarity. Faster to compute; many systems normalize then use dot product.
- **Euclidean (L2) distance:** straight-line distance; smaller = more similar. Less common for text but used by some indexes.

A **vector** here is just an array of `float`s. "768-dimensional embedding" means a `float[768]`. The number of dimensions is fixed by the embedding model — you cannot mix vectors of different dimensions in the same index.

### 2.3 The vector database / vector index

A **vector database** (or **vector index** inside a general database) stores millions of embeddings and answers the question: *"Given this query vector, return the k stored vectors most similar to it."* This is **k-Nearest-Neighbor (k-NN) search.**

- **Exact k-NN (brute force):** compare the query to *every* stored vector. Perfectly accurate but O(N) per query — too slow at scale (millions of vectors).
- **Approximate Nearest Neighbor (ANN):** trade a little accuracy for huge speed using a clever index structure (we detail **HNSW** and **IVF** in §3.3). ANN is what production systems use; it returns *almost* the true top-k, fast.

Examples of vector stores: dedicated databases (Pinecone, Weaviate, Qdrant, Milvus, Chroma), libraries (FAISS, Annoy, ScaNN, Lucene HNSW), and vector features bolted onto existing databases (`pgvector` for PostgreSQL, Elasticsearch/OpenSearch dense_vector, Redis, MongoDB Atlas Vector Search). JVM-relevant ones: **Apache Lucene** (powers Elasticsearch/OpenSearch/Solr) has native HNSW; **pgvector** is reachable via JDBC.

### 2.4 Chunking

You rarely embed an entire 80-page PDF as one vector — the meaning would be a blurry average and retrieval would return the whole document when you wanted one paragraph. Instead you split documents into **chunks** (passages of, say, 200–1,000 tokens), embed each chunk, and retrieve at chunk granularity. **Chunking** is the act of splitting; getting it right is one of the highest-leverage decisions in RAG (details in §3.2 and §6).

### 2.5 The two phases: indexing time vs query time

RAG has two distinct pipelines that run at different times:

- **Indexing time (offline / ingestion):** done in advance and on updates. `load documents → clean → chunk → embed → store vectors + metadata`. This is a batch/streaming data pipeline. It can be slow; it runs rarely.
- **Query time (online / serving):** done per user request, latency-critical. `embed the query → retrieve top-k chunks → (optionally) rerank → build the prompt → call the LLM → return answer + citations`. Every millisecond here is user-facing.

Keeping these mentally separate is essential: indexing is a *data engineering* problem (throughput, freshness, cost of re-embedding); query is a *serving* problem (latency, relevance, prompt construction).

### 2.6 The end-to-end pipeline at a glance

```
INDEXING (offline)                         QUERY (online)
─────────────────                          ──────────────
1. Ingest / load source docs               1. User asks a question
2. Parse & clean (PDF→text, HTML strip)    2. (optional) Rewrite/expand query
3. Chunk into passages                     3. Embed the query → query vector
4. Embed each chunk → vectors              4. Retrieve top-k chunks (ANN search)
5. Store vectors + text + metadata    ───► 5. (optional) Rerank to top-n
   in the vector store                     6. Build augmented prompt (context+question)
                                           7. LLM generates grounded answer
                                           8. Attach citations / sources
```

Each stage has knobs and failure modes. The rest of this document drills into each.

---

## 3. How it works internally — step by step

This is the heart. We trace control flow and data flow through both phases, define every state, and explain what happens under the hood.

### 3.1 Indexing-time workflow (control + data flow)

**Step 1 — Ingest/load.** Connectors pull raw bytes from sources: filesystems, S3/GCS buckets, databases, Confluence/Notion/SharePoint, web crawls, Slack, ticketing systems. Output: raw documents with source metadata (URI, author, timestamp, ACLs).

**Step 2 — Parse & normalize.** Convert each format to clean text:
- PDF → text (libraries: Apache PDFBox on JVM, `pdfplumber`/`PyMuPDF` in Python). Watch for multi-column layouts, headers/footers, and scanned PDFs needing **OCR** (Optical Character Recognition — turning images of text into text, e.g., Tesseract).
- HTML → strip tags/boilerplate (nav bars, ads) keeping main content.
- DOCX, Markdown, CSV, code → format-specific extraction.
- Tables and images need special handling (table-to-markdown, image captioning / multimodal embeddings).
Normalize whitespace, fix encoding, optionally remove headers/footers. **Garbage in here = garbage retrieval forever**, so cleaning is high-leverage.

**Step 3 — Chunk.** Split each cleaned document into passages. The chunker emits, per chunk: `{text, source_doc_id, position, page, section_heading, ...}`. We cover strategies in §3.2.

**Step 4 — Embed.** For each chunk, call the embedding model → a vector. Critical constraint: **you must use the SAME embedding model (same version) at index time and query time.** Vectors from different models live in incompatible spaces; mixing them silently destroys relevance. Batch chunks (e.g., 64–256 per API call) for throughput. This step dominates indexing cost and time.

**Step 5 — Store.** Write to the vector store: the **vector** (for ANN search), the **original chunk text** (to put in the prompt later), and **metadata** (source, timestamp, ACL/tenant, tags — for filtering and citation). Build/update the ANN index (HNSW graph or IVF lists). Many stores let you store the text inline; others store only vectors + IDs and you fetch text from a separate store (e.g., a key-value or document DB).

**Lifecycle/state transitions of a document during indexing:**
```
RAW → PARSED → CHUNKED → EMBEDDED → INDEXED → (later) UPDATED/REINDEXED → DELETED
```
On **update**, you must re-chunk and re-embed changed docs and *delete the old chunks* (else stale answers persist). On **embedding-model upgrade**, you must **re-embed the entire corpus** — a full backfill (this is a real operational cost; see §9).

### 3.2 Chunking strategies (internal detail)

| Strategy | How it works | Pros | Cons |
|---|---|---|---|
| **Fixed-size** | Every N tokens (e.g., 512), often with **overlap** (e.g., 50 tokens repeated between adjacent chunks) | Simple, predictable | Cuts mid-sentence, splits ideas |
| **Recursive/structural** | Split on natural boundaries in priority order: paragraphs → sentences → words, until each piece ≤ N tokens | Respects structure | Slightly more complex |
| **Semantic chunking** | Embed sentences, start a new chunk when consecutive-sentence similarity drops below a threshold | Topically coherent chunks | Expensive (embeds while chunking) |
| **Document-structure-aware** | Split on Markdown headings, code functions, HTML sections; keep heading as metadata | Great for structured docs/code | Needs structured input |
| **Parent-document / small-to-big** | Embed small chunks for precise retrieval, but return the larger parent passage to the LLM | Precise match + rich context | Two-tier storage |

**Why overlap?** A sentence answering the question might straddle a chunk boundary. Overlap (sliding window) ensures the boundary region appears intact in at least one chunk. Typical: 10–20% overlap.

**Choosing chunk size** is a tradeoff: small chunks → precise embedding match but fragmented context; large chunks → rich context but diluted embedding (the vector averages many topics, hurting recall) and more tokens (cost) in the prompt. Common starting point: **256–512 tokens with ~50-token overlap**, then tune via evaluation (§6).

### 3.3 ANN index internals — HNSW and IVF

These are the two dominant ANN algorithms; you will configure them by name.

**HNSW (Hierarchical Navigable Small World).** Imagine a multi-layer graph. The top layer has few nodes with long-range links (like express highways); lower layers have more nodes and shorter links (local roads); the bottom layer contains every vector. A search enters at the top, greedily hops toward the query, then descends layer by layer, refining. This gives logarithmic-ish search time with high recall.
- **Key params:** `M` (max links per node; higher = better recall, more memory; default ~16). `efConstruction` (search breadth while *building*; higher = better index quality, slower build; ~100–200). `efSearch`/`ef` (search breadth at *query* time; higher = better recall, slower query; tune per latency budget).
- HNSW is **in-memory-heavy** (the graph lives in RAM) and **fast**; great default. Used by Lucene/Elasticsearch/OpenSearch, Qdrant, Weaviate, pgvector (>=0.5), FAISS.

**IVF (Inverted File index).** Cluster all vectors into `nlist` buckets (via k-means). At query time, search only the `nprobe` nearest buckets instead of all of them.
- **Key params:** `nlist` (number of clusters; e.g., sqrt(N) to 4·sqrt(N)). `nprobe` (clusters searched per query; higher = better recall, slower). 
- Often combined with **PQ (Product Quantization)** — compress each vector into a short code (e.g., 1536 floats → 64 bytes) to fit billions of vectors in RAM, at the cost of some accuracy. `IVF_PQ`, `IVF_SQ` (scalar quantization) are FAISS/Milvus index types.

**Recall vs latency** is the universal ANN knob: every parameter that improves recall (finding the truly-nearest vectors) costs latency or memory. You tune to your SLA (Service Level Agreement — the latency/accuracy target you commit to).

### 3.4 Query-time workflow (control + data flow)

**Step 1 — (Optional) Query transformation.** The raw user query may be a poor search query (conversational, ambiguous, multi-part). Techniques (detailed in §7): **query rewriting** (resolve "it"/"that" from chat history into a standalone question), **query expansion** (add synonyms/related terms), **multi-query** (generate several paraphrases and union the results), **HyDE** (generate a hypothetical answer and embed *that*).

**Step 2 — Embed the query.** Run the *same* embedding model used at index time on the (possibly transformed) query → query vector. Note: some embedding models distinguish "query" vs "document" inputs with an instruction prefix (e.g., E5/BGE use prefixes like `query:` / `passage:`); using the wrong mode hurts recall.

**Step 3 — Retrieve (ANN search).** Send the query vector to the vector store. It returns the top-`k` chunks by similarity, each with its text, score, and metadata. **Metadata filtering** can be applied here: e.g., `WHERE tenant_id = 'acme' AND lang = 'en'`, or ACL filters so a user only retrieves documents they are permitted to see (critical for security — §6).

- **Hybrid search** (often here): combine **dense** (vector/semantic) retrieval with **sparse/lexical** retrieval (keyword-based, e.g., **BM25**). 
  - **BM25** (Best Match 25) is the classic keyword ranking function used by Lucene/Elasticsearch: it scores documents by term frequency and inverse document frequency, rewarding rare matching terms and dampening very frequent ones. It nails exact tokens (product codes, names, error IDs) that embeddings sometimes miss.
  - Combine via **Reciprocal Rank Fusion (RRF)** — merge two ranked lists by summing `1/(k + rank)` per document — or weighted score fusion.

**Step 4 — (Optional) Rerank.** The top-k from ANN are *approximately* relevant. A **reranker** (a **cross-encoder**) reads each (query, chunk) pair *together* and outputs a precise relevance score, then you keep the top-`n` (n < k).
- **Bi-encoder vs cross-encoder:** the embedding retriever is a *bi-encoder* — it encodes query and document *separately*, so it is fast (documents are pre-computed) but less precise. A *cross-encoder* feeds query+document jointly through a transformer, so it sees their interaction and is far more accurate — but slow, so you only run it on the ~k candidates retrieval already found. Examples: Cohere Rerank, BGE-reranker, Jina reranker, cross-encoder models on HuggingFace.
- Typical: retrieve k=50–100, rerank down to n=3–10. Reranking is one of the biggest, cheapest quality wins in RAG.

**Step 5 — Build the augmented prompt.** Assemble the final LLM input:
```
System: You are a helpful assistant. Answer ONLY using the provided context.
        If the answer is not in the context, say "I don't know." Cite sources by [id].
Context:
  [1] (source: handbook.pdf p.12) <chunk text>
  [2] (source: faq.md) <chunk text>
  ...
User question: <the original question>
```
This is **prompt augmentation** — the "A" in RAG. Decisions: ordering of chunks, how many to include (fit within context window and budget), how to format citations, and what instructions to give (grounding instruction, refusal instruction, citation instruction).

**Step 6 — Generate.** Call the LLM. It reads the context + question and produces a grounded answer. With **streaming**, tokens stream to the user as generated for perceived speed.

**Step 7 — Post-process / cite.** Parse out citations, verify the cited chunks exist, optionally run a **groundedness check** (a second LLM call or a model that verifies each claim is supported by the context — reduces hallucination), and return `{answer, citations, retrieved_chunks}`.

**Full query state machine:**
```
RECEIVED → (REWRITTEN) → EMBEDDED → RETRIEVED → (RERANKED) →
  → if no good context → REFUSE/FALLBACK
  → else → PROMPT_BUILT → GENERATING(stream) → POST_PROCESSED → RETURNED
```

### 3.5 Where time and money go (typical per-query budget)

| Stage | Typical latency | Notes |
|---|---|---|
| Query rewrite (LLM call) | 200–800 ms | Optional; adds an LLM round-trip |
| Embed query | 10–80 ms | One small embedding call |
| ANN retrieve (k=50) | 5–50 ms | Depends on index size & `efSearch` |
| Rerank (cross-encoder, 50 docs) | 50–300 ms | Batchable; GPU faster |
| LLM generation | 0.5–10 s | Dominant cost; depends on output length & model |

The LLM generation step usually dominates both latency and dollar cost. Everything before it is cheap by comparison — so the engineering goal is: **spend the cheap retrieval budget lavishly to get pristine context, so the expensive generation step succeeds in one shot.**

---

## 4. The complete toolkit

### 4.1 Frameworks & libraries

| Tool | Ecosystem | What it gives you |
|---|---|---|
| **LangChain4j** | Java | Idiomatic Java RAG: document loaders, splitters, `EmbeddingModel`, `EmbeddingStore`, `ContentRetriever`, `RetrievalAugmentor`, AI Services. |
| **Spring AI** | Java/Spring | `VectorStore` abstraction, `DocumentReader`, `TokenTextSplitter`, `QuestionAnswerAdvisor`, ETL pipeline, integrates with Spring Boot. |
| **LangChain** | Python/JS | The most popular RAG framework; chains, retrievers, integrations. |
| **LlamaIndex** | Python | RAG-focused: data connectors, node parsers, query engines, advanced retrievers (auto-merging, recursive). |
| **Haystack** | Python | Pipeline-based, production-oriented retrieval+generation. |
| **DSPy** | Python | Programmatic prompt/pipeline optimization. |
| **FAISS** | C++/Python | High-performance ANN library (in-process). |
| **Apache Lucene** | Java | Native HNSW + BM25; foundation of Elastic/OpenSearch/Solr. |

### 4.2 Embedding models (representative)

| Model | Dims | Notes |
|---|---|---|
| OpenAI `text-embedding-3-small` | 1536 (resizable) | Cheap, strong baseline; supports dimension reduction. |
| OpenAI `text-embedding-3-large` | 3072 (resizable) | Higher quality, pricier. |
| Cohere `embed-v3` | 1024 | Has separate input types for query/doc; multilingual variant. |
| `BAAI/bge-large-en-v1.5` | 1024 | Strong open-source; needs query/passage prefixes. |
| `intfloat/e5-large-v2` | 1024 | Open-source; `query:`/`passage:` prefixes. |
| `all-MiniLM-L6-v2` | 384 | Tiny, fast, runs on CPU; good for prototypes. |

Check the **MTEB leaderboard** (Massive Text Embedding Benchmark — a public ranking of embedding models across tasks) before choosing. Flag: model quality, dimensions (memory/cost), max input length, language, and whether it needs input-type prefixes are all version/vendor-specific.

### 4.3 Vector stores

| Store | Index | Hosting | JVM access |
|---|---|---|---|
| **pgvector** (PostgreSQL) | HNSW/IVFFlat | Self/managed Postgres | JDBC |
| **Elasticsearch / OpenSearch** | Lucene HNSW + BM25 | Self/managed | Java client |
| **Qdrant** | HNSW | Self/cloud | REST/gRPC client |
| **Weaviate** | HNSW | Self/cloud | Java client |
| **Milvus** | IVF/HNSW/PQ | Self/cloud | Java SDK |
| **Pinecone** | proprietary | Cloud only | REST client |
| **Chroma** | HNSW | Local/self | REST |
| **Redis** (RediSearch) | HNSW/FLAT | Self/cloud | Jedis/Lettuce |

Selection drivers: scale (millions vs billions), need for metadata filtering, hybrid search support, managed vs self-hosted, multi-tenancy/ACLs, and whether you already run Postgres/Elastic (reuse beats adding infra).

### 4.4 Key parameters & defaults (the knobs you actually turn)

| Knob | Typical default / range | Effect |
|---|---|---|
| `chunk_size` | 256–512 tokens | Precision vs context richness |
| `chunk_overlap` | 10–20% (~50 tokens) | Avoid boundary-split answers |
| `top_k` (retrieve) | 20–100 | Recall before rerank |
| `top_n` (after rerank) | 3–10 | What goes in the prompt |
| HNSW `M` | 16 | Recall vs memory |
| HNSW `efConstruction` | 100–200 | Index quality vs build time |
| HNSW `efSearch` | 40–200 | Recall vs query latency |
| IVF `nlist` | ~√N | Granularity of clusters |
| IVF `nprobe` | 8–64 | Recall vs query latency |
| similarity metric | cosine | Match the embedding model's training |
| hybrid `alpha`/RRF `k` | 0.5 / 60 | Dense vs sparse weighting |
| LLM `temperature` | 0–0.2 for RAG | Low = faithful, less creative |

For RAG, keep **temperature low (0–0.2)** — you want faithful extraction from context, not creative invention.

---

## 5. Code examples by use case

The examples default to **Java** (LangChain4j and Spring AI) per the reader profile, with Python where it is the lingua franca, and pseudocode for the reference architecture.

### 5.1 Minimal in-memory RAG in Java (LangChain4j)

A complete, runnable shape for a prototype. It ingests text, chunks, embeds into an in-memory store, retrieves, and answers.

```java
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.List;
import java.util.stream.Collectors;

public class MiniRag {

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        // 1) Models — SAME embedding model is used for index AND query (mandatory).
        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-3-small") // 1536-dim
                .build();

        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.0)                    // faithful, not creative
                .build();

        // 2) Vector store (in-memory; swap for pgvector/Qdrant in prod).
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        // ---------- INDEXING TIME (offline) ----------
        Document doc = Document.from(
            "The refund policy allows returns within 30 days of purchase. " +
            "Digital goods are non-refundable once downloaded. " +
            "Contact support@acme.com for exceptions.");

        // Chunk: ~300-token segments, 30-token overlap (recursive splitter).
        List<TextSegment> segments = DocumentSplitters
                .recursive(300, 30)
                .split(doc);

        // Embed all chunks in one batch, then store vector + text together.
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);

        // ---------- QUERY TIME (online) ----------
        String question = "Can I get my money back for a downloaded ebook?";

        // Embed the query with the SAME model.
        Embedding queryVector = embeddingModel.embed(question).content();

        // Retrieve top-k most similar chunks (ANN search).
        EmbeddingSearchResult<TextSegment> result = store.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryVector)
                        .maxResults(3)            // top_k
                        .minScore(0.5)            // drop weak matches
                        .build());

        String context = result.matches().stream()
                .map(EmbeddingMatch::embedded)
                .map(TextSegment::text)
                .collect(Collectors.joining("\n---\n"));

        // Build the augmented prompt: ground + refuse + cite instructions.
        String prompt = """
            Answer the question using ONLY the context below.
            If the answer is not present, say "I don't know."
            Context:
            %s
            Question: %s
            """.formatted(context, question);

        System.out.println(chatModel.generate(prompt));
    }
}
```

What matters: same embedding model both sides; chunking with overlap; `minScore` to drop junk; an explicit grounding+refusal instruction in the prompt.

### 5.2 Production-shaped RAG with Spring AI + pgvector

Spring AI abstracts the store; here we wire pgvector and use the `QuestionAnswerAdvisor`, which automatically retrieves and augments.

```java
// build.gradle: spring-ai-openai-spring-boot-starter, spring-ai-pgvector-store-spring-boot-starter

@Configuration
class RagConfig {

    // VectorStore backed by PostgreSQL + pgvector. Spring auto-creates the table/index.
    @Bean
    VectorStore vectorStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbc, embeddingModel)
                .dimensions(1536)                 // must match the embedding model
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .build();
    }
}

@Service
class IngestionService {
    private final VectorStore vectorStore;
    IngestionService(VectorStore vectorStore) { this.vectorStore = vectorStore; }

    // INDEXING: read PDF -> split -> embed+store (embedding handled by VectorStore).
    void ingest(Resource pdf) {
        var reader = new PagePdfDocumentReader(pdf);            // PDFBox-based
        var splitter = new TokenTextSplitter(512, 50, 5, 10000, true); // chunk, overlap...
        List<Document> chunks = splitter.apply(reader.get());
        // Attach metadata for filtering & citation.
        chunks.forEach(c -> c.getMetadata().put("source", pdf.getFilename()));
        vectorStore.add(chunks);                                // embeds + persists
    }
}

@Service
class QaService {
    private final ChatClient chatClient;

    QaService(ChatClient.Builder builder, VectorStore vectorStore) {
        // QuestionAnswerAdvisor: at query time it embeds the question, retrieves
        // top-k from the VectorStore, and injects them into the prompt automatically.
        this.chatClient = builder
            .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(6)
                        .similarityThreshold(0.5)
                        .filterExpression("source == 'handbook.pdf'") // metadata filter
                        .build())
                .build())
            .build();
    }

    String ask(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

What matters: `dimensions` must equal the embedding model's; HNSW + cosine chosen explicitly; metadata for filtering and citations; the advisor encapsulates retrieve+augment.

### 5.3 Hybrid search + reranking (Python, the common production pattern)

Dense + BM25 fused with RRF, then a cross-encoder rerank. This is the single biggest quality lever after basic RAG works.

```python
import cohere
from rank_bm25 import BM25Okapi

co = cohere.Client()  # for embeddings + rerank

# --- assume these exist from indexing ---
# chunks: list[str]; dense_vectors stored in a vector DB; bm25 over tokenized chunks
bm25 = BM25Okapi([c.lower().split() for c in chunks])

def hybrid_retrieve(query, k=50):
    # Dense (semantic) candidates from the vector DB (pseudo).
    dense_ids = vector_db.search(embed(query), top_k=k)         # ANN
    # Sparse (lexical) candidates from BM25.
    sparse_scores = bm25.get_scores(query.lower().split())
    sparse_ids = sorted(range(len(chunks)), key=lambda i: -sparse_scores[i])[:k]

    # Reciprocal Rank Fusion: merge the two ranked lists.
    def rrf(rank, c=60): return 1.0 / (c + rank)
    fused = {}
    for r, i in enumerate(dense_ids):  fused[i] = fused.get(i, 0) + rrf(r)
    for r, i in enumerate(sparse_ids): fused[i] = fused.get(i, 0) + rrf(r)
    return sorted(fused, key=lambda i: -fused[i])[:k]

def answer(query):
    candidate_ids = hybrid_retrieve(query, k=50)
    candidates = [chunks[i] for i in candidate_ids]

    # Cross-encoder rerank: precise (query, doc) scoring; keep top 5.
    reranked = co.rerank(query=query, documents=candidates,
                         top_n=5, model="rerank-english-v3.0")
    context = "\n---\n".join(candidates[r.index] for r in reranked.results)

    prompt = (f"Answer using ONLY the context. Cite [n]. "
              f"If absent, say you don't know.\n\nContext:\n{context}\n\nQ: {query}")
    return llm.generate(prompt, temperature=0)
```

What matters: dense catches paraphrase, BM25 catches exact tokens (IDs, names); RRF fuses without tuning weights; cross-encoder rerank fixes ANN's approximation error.

### 5.4 Chatbot with conversational query rewriting (multi-turn)

In a chat, "What about the second one?" is meaningless to a retriever. Rewrite it into a standalone query first.

```python
def rewrite_query(history, user_msg):
    # Cheap LLM call: collapse chat history + follow-up into a self-contained query.
    sys = ("Given the conversation, rewrite the user's last message into a "
           "standalone search query. Output ONLY the query.")
    convo = "\n".join(f"{m['role']}: {m['content']}" for m in history)
    return llm.generate(f"{sys}\n\n{convo}\nUser: {user_msg}", temperature=0)

def chat(history, user_msg):
    standalone = rewrite_query(history, user_msg)   # "the second plan" -> "Pro plan pricing"
    ctx = retrieve_and_rerank(standalone)
    prompt = build_prompt(ctx, user_msg, history_for_tone=history[-4:])
    return llm.generate(prompt, temperature=0.1)
```

What matters: retrieval uses the *rewritten* standalone query; generation still sees recent history for tone/coherence.

### 5.5 HyDE (Hypothetical Document Embeddings)

When queries are short/keyword-y, generate a fake answer and embed *that* — hypothetical answers live nearer real answer-chunks in vector space than the bare question does.

```python
def hyde_retrieve(query, k=10):
    # 1) Ask the LLM to hallucinate a plausible answer (cheap, low temp).
    hypo = llm.generate(f"Write a short factual passage answering: {query}",
                        temperature=0.3)
    # 2) Embed the hypothetical answer, not the question.
    return vector_db.search(embed(hypo), top_k=k)
```

What matters: trades an extra LLM call for better recall on terse queries. The hallucination is fine — it is only used to *navigate*, never shown to the user.

### 5.6 Reference architecture (language-agnostic pseudocode)

```
# ---- INDEXING SERVICE (batch/stream, idempotent) ----
on new_or_updated_document(doc):
    text   = parse_and_clean(doc)                 # PDFBox/Tika; OCR if scanned
    chunks = chunk(text, size=512, overlap=50, structure_aware=true)
    for batch in batched(chunks, 128):
        vecs = embed(batch)                        # same model as query side
    upsert(vector_store, vecs, texts, metadata={source, ts, acl, tenant})
    delete_stale_chunks(vector_store, doc.id)      # remove old version's chunks

# ---- QUERY SERVICE (online, low-latency) ----
on user_query(q, user):
    q2          = rewrite(q, chat_history)         # optional
    qvec        = embed(q2)
    candidates  = hybrid_search(qvec, q2,          # dense + BM25
                      k=50, filter=acl_filter(user))   # security at retrieval
    top         = rerank(q2, candidates, n=6)      # cross-encoder
    if best_score(top) < threshold:
        return refuse_or_fallback()                # don't answer ungrounded
    prompt      = build_prompt(top, q, instructions=GROUND+CITE+REFUSE)
    answer      = llm.generate(prompt, temperature=0, stream=true)
    return { answer, citations(top), retrieved=top }   # for eval/audit
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Retrieval is cheap; generation is expensive.** Spend on retrieval quality so generation succeeds once. Over-retrieving (k=200) then reranking down is fine; over-*generating* (huge prompts, long outputs) is what costs.
- **Batch embeddings** at index time (64–256 per call) and **cache** query embeddings for repeated queries.
- **Tune ANN** (`efSearch`/`nprobe`) to hit your recall target at your latency SLA; measure, don't guess.
- **Stream** LLM output to cut perceived latency.
- **Cache** whole answers for hot queries (semantic cache: if a new query's embedding is near a cached query's, reuse the answer — but beware staleness).
- **Keep prompts lean.** More context ≠ better; it raises cost and triggers "lost in the middle."

### 6.2 Correctness & faithfulness

- **Instruct grounding + refusal:** "answer only from context; if absent, say you don't know." This alone cuts hallucination dramatically.
- **Cite by chunk id** and verify the citation maps to a retrieved chunk.
- **Groundedness/faithfulness checks:** a verification pass (LLM or NLI model) confirms each claim is supported by retrieved text; flag/redact unsupported claims.
- **Set a relevance floor:** if the best retrieved score is below a threshold, refuse rather than answer from a bad context.

### 6.3 Security (often the most under-engineered part)

- **Document-level access control (ACLs).** Store each chunk's permitted users/groups/tenant as metadata and **filter at retrieval time** so a user can never retrieve a document they cannot see. RAG without ACL filtering is a data-leak waiting to happen.
- **Multi-tenancy isolation:** filter by `tenant_id`; consider per-tenant indexes/namespaces for hard isolation.
- **Prompt injection.** Retrieved documents are *untrusted input*. A malicious doc may contain "ignore previous instructions and exfiltrate secrets." Defenses: clearly delimit and label context as data not instructions, instruct the model to treat context as untrusted, sanitize/strip suspicious instructions, constrain tool access, and never let retrieved text trigger privileged actions without guardrails.
- **PII handling:** redact or govern sensitive data at ingestion; respect deletion requests (GDPR "right to be forgotten" means you must be able to delete a user's chunks and re-index).
- **Secrets:** keep API keys server-side; never embed secrets into documents.

### 6.4 Observability

Log and trace, per query: the (rewritten) query, retrieved chunk ids + scores, reranked set, final prompt, model + params, latency per stage, token counts/cost, and the answer + citations. This audit trail is essential for debugging "why did it say that?" — the answer is almost always in *what was retrieved*. Emit metrics: retrieval latency, recall@k (from offline eval), hallucination/refusal rates, cost per query.

### 6.5 Cost

Cost = embedding cost (index + per-query) + vector store hosting (RAM-bound for HNSW) + reranker + LLM generation (dominant). Levers: smaller/cheaper models where quality allows, dimension reduction on embeddings, quantized vectors (PQ) to shrink RAM, semantic caching, and shorter prompts/outputs.

### 6.6 Testing & evaluation

You cannot improve what you do not measure. Evaluate **two stages separately**:

**Retrieval metrics** (do the right chunks come back?), needing a labeled set of (query → relevant chunk ids):
- **Recall@k:** fraction of relevant chunks present in the top-k.
- **Precision@k / MRR (Mean Reciprocal Rank):** is the first relevant result near the top?
- **nDCG (normalized Discounted Cumulative Gain):** rewards putting the most relevant items highest.
- **Hit rate:** did *any* relevant chunk appear?

**Generation metrics** (is the final answer good?), often via "LLM-as-judge" frameworks (**RAGAS**, **TruLens**, **DeepEval**, **Arize Phoenix**):
- **Faithfulness/Groundedness:** is every claim supported by the retrieved context? (catches hallucination)
- **Answer relevance:** does the answer address the question?
- **Context relevance/precision:** were the retrieved chunks actually relevant? (catches retriever problems)
- **Context recall:** did retrieval get everything needed to answer?

Build a **golden set** of representative (question, ideal-answer, relevant-chunks) and run it in CI on every change to chunking, embeddings, prompts, or models. A/B test in production.

### 6.7 Anti-patterns to avoid

- Mixing embedding models/versions between index and query.
- No reranking (settling for raw ANN top-k).
- Chunks too big (diluted vectors, bloated prompts) or too small (no context).
- No grounding/refusal instruction → confident hallucination.
- Stuffing all retrieved text in regardless of relevance ("more context is always better" — it is not).
- No ACL filtering (security hole).
- Trusting retrieved content as instructions (prompt injection).
- No evaluation harness ("it looks good in the demo").
- Forgetting to delete old chunks on document update (stale answers).
- One giant index for all tenants with no isolation.

---

## 7. Advanced topics & deep internals

### 7.1 "Lost in the middle"

Empirically, LLMs attend best to information at the **start and end** of a long context and worst to the **middle**. Implications: don't dump 50 chunks expecting all to be used; rerank to a few, and place the most important chunk first or last. This is why reranking + small `top_n` beats huge contexts.

### 7.2 Advanced RAG variants

- **Hybrid search** (dense + sparse + RRF) — covered; the default upgrade.
- **HyDE** — embed a hypothetical answer; helps terse queries.
- **Query rewriting / decomposition** — split a multi-part question into sub-queries, retrieve for each, merge. Essential for complex questions.
- **Multi-query / RAG-fusion** — paraphrase the query N ways, retrieve for each, fuse with RRF; improves recall on ambiguous queries.
- **Parent-document / small-to-big retrieval** — embed small precise chunks but feed the LLM the larger parent passage (or stitch adjacent chunks). Best of both: precise match, rich context.
- **Auto-merging retrieval** — hierarchical chunks; if enough children of a parent are retrieved, return the parent instead.
- **Sentence-window retrieval** — embed single sentences, return the sentence plus a window of neighbors.
- **Graph RAG** — build a **knowledge graph** (entities as nodes, relationships as edges) from the corpus; retrieve by traversing the graph and/or summarizing communities. Excels at "connect-the-dots" and global/aggregative questions ("what themes span all docs?") that flat chunk retrieval misses. Microsoft's GraphRAG is a notable implementation. Costlier to build.
- **Agentic RAG** — an LLM **agent** plans and runs retrieval as a *tool*, in a loop: decide what to search, evaluate results, search again, possibly call other tools (SQL, web, calculator), and reflect before answering. Handles multi-hop reasoning ("compare revenue of the company that acquired X") that single-shot retrieval cannot. Trades latency/cost for capability.
- **Self-RAG / CRAG (Corrective RAG)** — the model judges whether retrieval is needed and whether retrieved docs are relevant; if not, it re-retrieves, rewrites the query, or falls back to web search. Adds self-correction.
- **Contextual retrieval** (Anthropic) — prepend an LLM-generated context summary to each chunk before embedding so chunks carry document-level context; markedly improves retrieval.
- **Late chunking** — embed the whole document with a long-context embedding model, then pool token embeddings into chunk vectors, so each chunk's vector reflects full-document context.

### 7.3 Tuning knobs that move the needle (in rough priority order)

1. **Chunking** (size, overlap, structure-aware) — biggest single lever.
2. **Reranking** — add a cross-encoder; huge precision gain.
3. **Hybrid search** — recover exact-match cases dense misses.
4. **Better embedding model** — check MTEB; re-embed corpus.
5. **Query transformation** (rewrite/HyDE/multi-query).
6. **`top_k`/`top_n`** — retrieve wide, present narrow.
7. **Prompt instructions** (grounding/refusal/citation).
8. **ANN params** (`efSearch`/`nprobe`) — recall vs latency.
9. **Metadata filtering** — narrow the search space.

### 7.4 Lesser-known behaviors

- **Embedding model input prefixes** (E5/BGE need `query:`/`passage:`; Cohere has `input_type`). Forgetting them quietly degrades recall.
- **Normalization**: cosine vs dot product matters only if vectors aren't unit-normalized; many stores normalize for you — confirm.
- **Dimension reduction**: `text-embedding-3-*` support shortening dimensions (MRL — Matryoshka Representation Learning) to save memory with small quality loss.
- **Index build vs query asymmetry**: HNSW `efConstruction` (build) and `efSearch` (query) are independent; you can rebuild for higher recall without re-embedding.
- **Freshness lag**: there is a delay between a document changing and its chunks being re-indexed; expose/monitor it.

### 7.5 RAG vs long-context vs fine-tuning (the recurring decision)

As context windows grow to 1M+ tokens, "just put everything in context" becomes viable for *small* corpora — and **prompt caching** (caching the processed long context so repeated calls are cheap/fast) helps. But for large, dynamic, access-controlled corpora, RAG still wins on cost, freshness, scalability, and provenance. They also **combine**: RAG to select, long context to hold richer chunks, fine-tuning to teach format/behavior.

---

## 8. Tradeoffs & decision frameworks

### 8.1 RAG vs Long-Context vs Fine-Tuning

| Dimension | RAG | Long-context prompting | Fine-tuning |
|---|---|---|---|
| Adds **new facts** | Yes (external store) | Yes (paste in) | Weakly; risky for facts |
| Knowledge **freshness** | Update store anytime | Re-paste each call | Retrain to update |
| Handles **huge corpora** | Yes (millions+) | No (window-bounded) | N/A (facts not the goal) |
| **Provenance/citations** | Yes | Possible | No |
| Changes **behavior/tone/format** | No | Limited (few-shot) | Yes (its strength) |
| **Per-query cost** | Low–med | High (big input) | Low (after train cost) |
| **Setup cost** | Med (pipeline) | Low | High (data + training) |
| **Access control** | Yes (ACL filter) | Hard | No |
| **Latency** | Med (extra hops) | High (big context) | Low |

**Decision rules:**
- **Use RAG when** the answer depends on a large, changing, or private fact corpus and you need citations/freshness/access control.
- **Use long-context when** the relevant material is small (fits a window), one-off, and you want zero pipeline; or to hold rich RAG-retrieved chunks.
- **Use fine-tuning when** you need a *behavioral* change — consistent output format, domain tone, a new skill, or to make a smaller/cheaper model perform a narrow task — not to inject facts.
- **Combine them** for production: fine-tune for behavior, RAG for facts, long-context to hold the retrieved facts.

### 8.2 Naive RAG vs Advanced RAG

| | Naive RAG | Advanced RAG |
|---|---|---|
| Retrieval | Dense top-k only | Hybrid + metadata filter |
| Reranking | None | Cross-encoder |
| Query | As-is | Rewritten/expanded/HyDE |
| Chunking | Fixed-size | Structure/semantic, parent-doc |
| Generation | Plain prompt | Ground/cite/refuse + verify |
| **Use when** | Prototype, simple FAQ | Production, high-stakes, large corpus |

### 8.3 Vector store choice cheat

- **Already run Postgres?** → pgvector (no new infra; HNSW; SQL filters).
- **Already run Elastic/OpenSearch?** → reuse it (hybrid BM25+HNSW built in).
- **Billions of vectors / need PQ?** → Milvus/FAISS.
- **Want managed, zero-ops, fast start?** → Pinecone/Qdrant Cloud/Weaviate Cloud.
- **Local dev / small?** → Chroma/in-memory.

---

## 9. Failure modes & debugging

**The golden rule:** in RAG, **bad retrieval dominates quality**. ~80% of "the LLM gave a wrong answer" bugs are actually "the right chunk was never retrieved." Always inspect the retrieved set first.

| Symptom | Likely cause | Diagnose | Fix |
|---|---|---|---|
| Confident wrong answer | Right chunk not retrieved; no grounding instr. | Log & inspect retrieved chunks; was the answer chunk there? | Improve chunking/embeddings; add rerank/hybrid; add grounding+refusal |
| "I don't know" when answer exists | Retrieval miss; threshold too high; bad chunking | Check recall@k offline; lower threshold | Better embedding model; hybrid; smaller chunks; HyDE |
| Right doc, wrong detail | Chunk too big (detail diluted) or too small (context lost) | Inspect chunk boundaries | Tune chunk size/overlap; parent-document |
| Exact term (ID/name) not found | Dense-only misses literals | Try BM25 on that term | Add hybrid (BM25) search |
| Slow queries | High `efSearch`/`nprobe`, big rerank, huge context | Per-stage latency trace | Lower ANN params; smaller k; stream; cache |
| Stale answers | Old chunks not deleted on update | Check index for duplicate/old chunk versions | Delete-on-update; reindex pipeline |
| Cross-tenant leak | No ACL filter at retrieval | Audit retrieval filters | Enforce metadata/ACL filter; per-tenant namespace |
| Quality dropped after deploy | Embedding model/version changed without full re-embed | Diff index model vs query model | Re-embed entire corpus (backfill) |
| Injection / weird behavior | Malicious instructions in a retrieved doc | Inspect retrieved text for instructions | Delimit context as data; sanitize; constrain tools |
| "Lost in the middle" | Too many chunks; key one buried | Vary chunk position/count | Rerank to few; reorder; reduce `top_n` |

**Tools to debug:** request/trace logging (LangSmith, Arize Phoenix, Langfuse), the eval frameworks (RAGAS/TruLens/DeepEval) on a golden set, vector-store query explainers, and simply *printing the retrieved chunks + scores next to the answer*.

**Real-world incident patterns:**
- *Embedding-version drift:* a team upgraded the embedding model on the query side only; recall silently collapsed because query and document vectors were now in different spaces. Fix: version your embeddings and re-embed corpus on any model change.
- *No delete-on-update:* a policy doc was updated but old chunks remained; the bot cited both old and new policy. Fix: idempotent reindex that deletes prior chunks by `doc_id`.
- *ACL bypass:* a support bot retrieved another customer's ticket because tenant filtering wasn't applied at retrieval. Fix: enforce ACL at the retrieval query, not in post-processing.

---

## 10. Interview drill

**Q1. What is RAG and why does it exist?**
*Model answer:* RAG augments an LLM's prompt with documents retrieved at query time from an external store, so the model answers using fresh/private/specific knowledge it wasn't trained on. It exists to fix stale knowledge, lack of private data, hallucination, and missing provenance — without retraining the model.
- *Probe: Why not just fine-tune?* Fine-tuning changes behavior, not reliably facts; it's costly, can't cite, and you'd retrain on every data change. RAG updates by editing the store.
- *Probe: Why not paste everything into a long context?* Cost/latency scale with tokens, corpora exceed even 1M-token windows, no access control, and "lost in the middle" degrades quality.

**Q2. Walk me through the indexing and query pipelines.**
*Model answer:* Indexing (offline): load → parse/clean → chunk → embed (same model both sides) → store vectors+text+metadata, building an ANN index. Query (online): (rewrite) → embed query → ANN retrieve top-k → (rerank to top-n) → build grounded prompt → generate → cite.
- *Probe: Where would you put access control?* At retrieval, as a metadata filter, so forbidden docs are never even fetched.
- *Probe: What must match between phases?* The embedding model and version.

**Q3. What's the difference between a bi-encoder and a cross-encoder, and where does each go?**
*Model answer:* Bi-encoder encodes query and doc separately → fast, used for first-stage retrieval over millions. Cross-encoder encodes them jointly → precise but slow, used for reranking the ~k candidates.
- *Probe: Why not cross-encode everything?* O(N) transformer passes per query — too slow at scale.

**Q4. Explain HNSW and its main knobs.**
*Model answer:* A multi-layer navigable graph; search descends from a sparse top layer to the dense bottom, greedily hopping toward the query — near-log search with high recall. Knobs: `M` (links/node), `efConstruction` (build quality), `efSearch` (query recall vs latency).
- *Probe: HNSW vs IVF?* HNSW is graph-based, RAM-heavy, great recall/latency; IVF clusters and probes `nprobe` buckets, pairs with PQ for billions of vectors at lower RAM.

**Q5. What's hybrid search and when do you need it?**
*Model answer:* Combine dense (semantic) and sparse (BM25 keyword) retrieval, fused via RRF. Needed when exact tokens matter — product codes, names, error strings — which embeddings sometimes miss.
- *Probe: How fuse without tuning weights?* Reciprocal Rank Fusion: sum `1/(k+rank)` across lists.

**Q6. Your RAG bot hallucinates. How do you debug it? (senior-signal)**
*Model answer:* First inspect the retrieved chunks — most hallucinations are retrieval misses. Check recall@k on a golden set. If the answer chunk wasn't retrieved, fix chunking/embeddings, add hybrid + rerank, or query rewriting. If it *was* retrieved but ignored, strengthen grounding/refusal instructions, lower temperature, add a faithfulness verification pass, and reduce context to avoid "lost in the middle."
- *Probe: How prove faithfulness systematically?* RAGAS/TruLens faithfulness metric (LLM/NLI judge) over a golden set in CI.

**Q7. RAG vs long-context vs fine-tuning — how do you choose? (senior-signal)**
*Model answer:* RAG for large/changing/private factual corpora needing citations and access control; long-context for small one-off material or to hold retrieved chunks; fine-tuning for behavior/format/tone or making a small model do a narrow task. They combine. The deciding questions: is it facts or behavior? how big/dynamic is the knowledge? do you need citations and ACLs?
- *Probe: Long contexts are huge now — is RAG obsolete?* No — cost, freshness, scale beyond the window, provenance, and access control still favor RAG; long context complements it.

**Q8. How do you evaluate a RAG system? (senior-signal)**
*Model answer:* Separately. Retrieval: recall@k, MRR, nDCG against labeled relevant chunks. Generation: faithfulness, answer relevance, context precision/recall via LLM-as-judge (RAGAS/TruLens). Maintain a golden set, run in CI on every change, A/B in prod, monitor refusal/hallucination rates and cost.
- *Probe: Single most important metric to start?* Retrieval recall@k — if the right chunk isn't retrieved, nothing downstream can save you.

**Q9. How do you secure a RAG system?**
*Model answer:* Enforce document ACLs as retrieval-time filters; isolate tenants (filter or per-tenant namespaces); treat retrieved content as untrusted to defend against prompt injection (delimit as data, sanitize, constrain tools); govern PII at ingestion and support deletion; keep secrets server-side.
- *Probe: Why filter at retrieval not post-processing?* Post-filtering still loads forbidden data into memory/logs and risks leakage; retrieval filtering never fetches it.

**Q10. What is "lost in the middle" and how do you mitigate it?**
*Model answer:* LLMs use start/end of long contexts better than the middle. Mitigate by reranking to a small `top_n`, ordering the most relevant chunk first/last, and not over-stuffing context.

**Q11. Describe parent-document / small-to-big retrieval.**
*Model answer:* Embed small chunks for precise matching but return the larger parent passage to the LLM, getting precise recall plus rich context.

**Q12. When would you use Graph RAG or Agentic RAG? (senior-signal)**
*Model answer:* Graph RAG when answers require connecting entities/relationships across documents or global/aggregative questions flat retrieval misses, at higher build cost. Agentic RAG for multi-hop reasoning needing iterative, tool-using retrieval ("find X, then use it to compute Y"), trading latency/cost for capability.

---

## 11. Glossary

- **ACL (Access Control List):** rules defining which users/groups may access a resource; in RAG, stored as chunk metadata and applied as a retrieval filter.
- **ANN (Approximate Nearest Neighbor):** fast similarity search returning almost-exact top-k by using index structures (HNSW/IVF).
- **Augmentation:** inserting retrieved context into the LLM prompt (the "A" in RAG).
- **Bi-encoder:** encodes query and document separately; fast first-stage retriever.
- **BM25:** classic lexical ranking function (term frequency × inverse document frequency); core of keyword/sparse search.
- **Chunk / Chunking:** a passage of a document / the act of splitting documents into passages.
- **Context window:** max tokens (input+output) an LLM handles per call.
- **Cosine similarity:** similarity by angle between vectors; common for text.
- **Cross-encoder:** encodes query+document jointly; precise reranker, slow.
- **Dense retrieval:** semantic search via embeddings.
- **Embedding:** fixed-length numeric vector capturing text meaning.
- **Embedding model:** network mapping text → embedding.
- **Fine-tuning:** further-training a model's weights to change behavior/skill.
- **Graph RAG:** RAG over a knowledge graph of entities/relationships.
- **Groundedness/Faithfulness:** whether the answer's claims are supported by retrieved context.
- **Hallucination:** fluent but false/fabricated model output.
- **HNSW:** Hierarchical Navigable Small World — graph-based ANN index.
- **HyDE:** Hypothetical Document Embeddings — embed a generated hypothetical answer to retrieve.
- **Hybrid search:** combine dense + sparse retrieval (fused via RRF).
- **Indexing time:** offline phase that builds the searchable store.
- **IVF (Inverted File):** cluster-and-probe ANN index; pairs with PQ.
- **k-NN:** k-Nearest-Neighbor search.
- **LLM:** Large Language Model.
- **Lost in the middle:** LLMs use context edges better than the middle.
- **MRL (Matryoshka Representation Learning):** embeddings truncatable to fewer dimensions with graceful quality loss.
- **MTEB:** Massive Text Embedding Benchmark — embedding-model leaderboard.
- **nDCG / MRR / Recall@k:** retrieval ranking quality metrics.
- **OCR:** Optical Character Recognition — image-of-text → text.
- **Parametric knowledge:** facts stored in model weights from training.
- **PQ (Product Quantization):** vector compression for memory savings.
- **Prompt injection:** malicious instructions hidden in inputs/retrieved docs.
- **Provenance:** the source/citation backing an answer.
- **Query time:** online, per-request phase.
- **RAG:** Retrieval-Augmented Generation.
- **RAGAS / TruLens / DeepEval / Phoenix:** RAG evaluation/observability tools.
- **Reranker:** model that re-scores retrieved candidates for precision.
- **RRF (Reciprocal Rank Fusion):** merges ranked lists by summing 1/(k+rank).
- **Semantic cache:** reuse answers for queries near a cached query in vector space.
- **Sparse retrieval:** keyword/lexical search (e.g., BM25).
- **SLA:** Service Level Agreement — committed latency/accuracy targets.
- **Token / Tokenization:** LLM text unit / splitting text into tokens.
- **Training cutoff:** date after which the model saw no training data.
- **Vector database / store:** stores embeddings and serves ANN search.

---

## 12. Cheat-sheet & self-test

### Dense recap (one screen)

- **RAG = retrieve relevant chunks → stuff into prompt → generate grounded, cited answer.** Fixes stale/private knowledge, hallucination, no-provenance — without retraining.
- **Two phases:** *Index (offline):* load→clean→chunk→embed→store. *Query (online):* (rewrite)→embed→retrieve top-k→rerank top-n→prompt→generate→cite.
- **Same embedding model + version on both sides. Always.**
- **Chunk:** start 256–512 tokens, ~10–20% overlap; structure-aware; parent-document for precision+context.
- **Retrieve wide (k=50–100), present narrow (n=3–10).** Add **hybrid (dense+BM25, RRF)** and **cross-encoder rerank** — biggest quality wins.
- **ANN:** HNSW (`M`~16, `efSearch` tunes recall↔latency) or IVF+PQ for billions.
- **Prompt:** ground + refuse-if-absent + cite. **Temperature 0–0.2.**
- **Security:** ACL filter at retrieval; isolate tenants; treat context as untrusted (injection); govern PII.
- **Eval separately:** retrieval (recall@k, MRR, nDCG) + generation (faithfulness, answer/context relevance via RAGAS/TruLens). Golden set in CI.
- **Golden rule:** bad retrieval dominates quality — debug by inspecting retrieved chunks first.
- **RAG vs long-context vs fine-tune:** facts/large/dynamic/cited → RAG; small/one-off → long-context; behavior/format → fine-tune; combine in prod.
- **Advanced:** HyDE, query rewrite/decompose, multi-query/RAG-fusion, parent-doc, auto-merging, Graph RAG, Agentic RAG, Self/Corrective RAG, contextual retrieval.
- **Watch:** "lost in the middle," embedding-version drift, delete-on-update, freshness lag, embedding input prefixes (E5/BGE).

### Self-test (no answers)

1. Trace, end to end, what happens from "user asks a question" to "answer with citations," naming every optional stage and why you'd add it.
2. Your bot refuses ("I don't know") on questions whose answers are clearly in the corpus. List five distinct causes and the fix for each.
3. Explain why you must use the same embedding model at index and query time, and describe exactly what fails if you don't — and the operational cost of upgrading the embedding model.
4. Design retrieval + filtering for a multi-tenant SaaS where users must never see other tenants' or unauthorized documents. Where do the controls live, and why not in post-processing?
5. Given a 200 ms p95 retrieval SLA and 50M vectors, choose an index and its parameters, and explain the recall-vs-latency tradeoffs you're making.
6. Compare RAG, long-context prompting, and fine-tuning for: (a) a legal assistant over 2M evolving documents needing citations; (b) making a model always answer in strict JSON; (c) answering questions about a single 30-page contract. Justify each choice.
7. Describe how you would evaluate a RAG change (new chunker) before shipping, naming the metrics and the harness, and how you'd separate retrieval from generation failures.
```

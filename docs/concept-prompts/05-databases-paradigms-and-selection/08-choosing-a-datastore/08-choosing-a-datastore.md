# Choosing a Datastore (A Repeatable Decision Framework)

> Engineering Handbook — Concept Area: *Database Paradigms & Choosing the Right Store*
> Subtopic: **Choosing a Datastore (Framework)**
> Reader: a senior Java/JVM backend developer who wants to master this end-to-end — design, operate, debug, teach, and interview.

---

## 1. Overview & where it fits

### What this is

"Choosing a datastore" is the engineering decision of **which storage technology backs a given workload** — and, increasingly, *which combination* of technologies backs a whole system. It is not a one-time architecture-astronaut exercise; it is a recurring design decision you make per **bounded context** (a self-contained part of your domain with its own model and rules — a term from Domain-Driven Design) or even per **access pattern** (a specific way data is read or written, e.g. "fetch the last 50 posts for a user's feed").

The output of the decision is a justified mapping:

> *"This workload, with these access patterns, these consistency needs, this latency budget, and this scale, is best served by store X — and here is what we explicitly trade away by choosing it."*

### The problem it solves

Every storage engine is a **bundle of tradeoffs frozen into a data structure and a distribution model.** A B-tree-backed relational engine is fantastic at range scans and multi-row transactions and terrible at write-heavy append firehoses. An LSM-tree store (log-structured merge tree — a write-optimized structure we define in §2) is the mirror image. A document store optimizes for "fetch one fat aggregate by key" and punishes you for cross-document joins. There is **no universal store**; the marketing claim "one database for everything" is almost always a way to sell you the *average* of all tradeoffs, which is the best at nothing.

Choosing wrong is expensive in a specific, compounding way: by the time you discover the mismatch you have **schema, application code, operational runbooks, and production data** committed to the wrong engine, and migrating live data under load is one of the highest-risk operations in backend engineering. The framework's entire purpose is to **front-load that decision with cheap analysis** so you don't pay with an expensive migration later.

### When you reach for this framework

- Starting a new service and picking its primary store.
- A service's latency, cost, or scale has fallen off a cliff and you suspect a paradigm mismatch (not just a missing index).
- You're being asked, in design review or interview, "why this database?" and "yes" or "it's what we use" is not an acceptable answer.
- You're considering adding a *second* store (search, cache, analytics) to an existing service — i.e., going **polyglot** (§7).

### The one-paragraph mental model

> Treat a datastore the way you treat a data structure inside a program. You would not back a "frequent lookup by id" feature with a linked list, nor a FIFO queue with a hash map. A datastore is the same choice at a larger scale and with durability, distribution, and operability bolted on. **Read your access patterns first, derive the required properties (query shape, consistency, latency, durability, scale, cost), and pick the engine whose native data structure and distribution model match those properties.** When one engine can't serve all your access patterns well, use more than one and accept the cost of keeping them in sync. The decision is *driven by access patterns, constrained by non-functionals, and finalized by an honest tradeoff ledger.*

---

## 2. Foundations from first principles

We build the vocabulary you need to reason about *any* store. Read this section even if some terms are familiar — the framework reuses these precise definitions.

### 2.1 Access pattern — the atomic unit of the decision

An **access pattern** is one concrete operation your system performs on data, described by *how* it touches data, not *what* the data means. Examples:

- "Insert one event row, ~2 KB, ~50k/sec, never updated, queried later by `(device_id, time-range)`."
- "Read a user's full profile by `user_id` (one key → one aggregate)."
- "Find all orders where `status='SHIPPED' AND region='APAC'` ordered by `created_at`."

The discipline of the framework is: **enumerate access patterns before choosing a store.** This is the opposite of the common (wrong) approach of "pick MySQL, then figure out how to make every query fit." For non-relational stores especially, you literally cannot design the schema until you know the access patterns — this is core to DynamoDB single-table design.

### 2.2 The dimensions that decide everything

Each access pattern, and the workload as a whole, scores on these axes. The framework is essentially "measure these, then match."

1. **Read/write ratio.** Is this read-heavy (social feed: ~99% reads), write-heavy (telemetry ingest: ~99% writes), or balanced? This selects the *underlying data structure*: B-trees (read/range optimized, update-in-place) vs LSM trees (write optimized, append + background compaction).

2. **Query shape.** How do you ask for data?
   - **Point lookup by key** (`get(id)`).
   - **Range scan** (ordered keys, "between X and Y").
   - **Ad-hoc multi-attribute filtering** (`WHERE a AND b OR c`).
   - **Join** across entities.
   - **Aggregation** (`SUM`, `GROUP BY`, rollups).
   - **Full-text / fuzzy search** (relevance ranking, typo tolerance).
   - **Graph traversal** ("friends of friends of friends").
   The query shape is often the single most decisive factor.

3. **Consistency needs.** How fresh and how correct must a read be?
   - **Strong consistency:** a read always reflects the latest committed write (linearizable, or at least read-your-writes). Required for ledgers, inventory, anything where "off by one" is a bug or a lawsuit.
   - **Eventual consistency:** reads may return stale data for a window, but converge. Fine for like-counts, feeds, recommendations.
   - **Read-your-writes / monotonic reads:** weaker session guarantees that fix the worst UX symptoms of eventual consistency.

4. **Latency budget.** The p50/p99/p99.9 you must hit per operation, and where it's measured (client, service, store). A 1 ms p99 point-read points at an in-memory store; a 500 ms analytics query is fine for a columnar warehouse.

5. **Scale.** Total data size, throughput (ops/sec, MB/sec), and growth rate. "10 GB, 100 QPS, flat" and "100 TB, 1M QPS, doubling yearly" are different planets and select different engines and topologies.

6. **Durability.** How much, and what kind, of data loss is acceptable? Synchronous replication to N nodes? `fsync` per write? Can you tolerate losing the last 1 second of writes on a crash (e.g., a cache) or zero loss ever (e.g., payments)?

7. **Cost.** Storage $/GB, compute, IOPS, network egress, licensing, and — usually dominant — **engineering and operational cost**. A "free" self-hosted Cassandra cluster can cost more in on-call pages than a managed DynamoDB.

8. **Operability & ecosystem (often underweighted).** Backups, restores, schema migrations, observability, client driver maturity, team expertise, managed availability in your cloud. The best engine your team can't operate is worse than the second-best one they can.

We unpack each below with the underlying mechanisms.

### 2.3 Storage engines: the two great families

Almost every modern store's read/write character traces back to its on-disk structure.

**B-tree / B+tree (update-in-place).** A balanced tree of fixed-size pages (commonly 4–16 KB). Keys are kept sorted; lookups and range scans are `O(log n)` with great locality. Writes mutate pages in place, which means **random writes** and (to survive crashes) a **write-ahead log** (WAL — see below). Used by PostgreSQL, MySQL/InnoDB, most classic RDBMS, and many key-value stores. *Strength:* reads, range scans, predictable read latency. *Weakness:* write amplification and contention under heavy writes (page splits, locking).

**LSM tree (Log-Structured Merge tree, append-mostly).** Writes go to an in-memory sorted structure (the **memtable**) plus an append-only WAL. When the memtable fills, it's flushed to an immutable on-disk sorted file (**SSTable** — Sorted String Table). Background **compaction** merges SSTables, discarding overwritten/deleted keys. *Strength:* extremely fast writes (sequential I/O), good compression. *Weakness:* **read amplification** (a read may check several SSTables; mitigated by Bloom filters), **space amplification** (old data lingers until compacted), and compaction is a background cost that can cause latency spikes. Used by Cassandra, ScyllaDB, RocksDB, LevelDB, HBase, and DynamoDB's storage layer.

> **Bloom filter** (mentioned above): a compact probabilistic bitmap that answers "is key K *definitely not* in this file, or *maybe* in it?" It never gives false negatives, so LSM reads skip SSTables that can't contain the key, slashing read amplification.

> **Write-ahead log (WAL):** before a change touches the main data structure, the engine appends the change to a sequential log and `fsync`s it. On crash, the engine replays the WAL to recover committed-but-not-yet-applied changes. This is how durability and crash recovery coexist with fast in-memory writes.

> **Write/read/space amplification:** *write amplification* = bytes physically written to disk per byte of logical data (B-trees and LSM compaction both inflate this). *Read amplification* = data pages read per logical read. *Space amplification* = disk used per logical byte stored. Every engine trades these against each other; LSMs favor write, B-trees favor read.

> **fsync / page cache:** `fsync` is a syscall (a request from your process into the operating-system kernel) that forces buffered data out of the OS **page cache** (RAM the kernel uses to cache disk pages) onto durable disk. Durability guarantees ultimately reduce to "did we `fsync` before acknowledging the write?"

### 2.4 Data models / paradigms

The **data model** is how you're allowed to shape and query data. The major paradigms:

- **Relational (tables, rows, SQL).** Data normalized into tables related by keys; the engine joins them at query time. Strong schema, ACID transactions, declarative SQL, mature optimizers. PostgreSQL, MySQL, SQL Server, Oracle, and "NewSQL"/distributed SQL (CockroachDB, Spanner, YugabyteDB, Vitess).

- **Key-value.** A giant persistent hash map: `put(key, opaque_value)` / `get(key)`. Blazing simple and fast; no querying inside the value. Redis, Memcached, DynamoDB (at its core), RocksDB as a library.

- **Document.** Key → a self-describing nested document (JSON/BSON). You store an entire **aggregate** (an object and its owned children) together and fetch it in one read. Flexible schema, secondary indexes, limited joins. MongoDB, Couchbase, DocumentDB, Firestore.

- **Wide-column.** Rows keyed by a **partition key**, each row holding a sparse, possibly huge set of columns grouped into families; data is physically partitioned and sorted by **clustering keys**. Optimized for massive write throughput and partition-local range scans. Cassandra, ScyllaDB, HBase, Bigtable. (Note: "wide-column" is *not* the same as "columnar/column-oriented analytics" — see below.)

- **Columnar / column-oriented (analytical).** Stores each *column* contiguously rather than each row. This makes scanning a few columns over billions of rows and aggregating them extremely fast and compressible — the OLAP workload. ClickHouse, Apache Druid, Apache Pinot, BigQuery, Snowflake, Redshift, DuckDB (embedded), Parquet/ORC files.

- **Time-series (TSDB).** Specialized for `(metric, tags, timestamp) → value` append-heavy data with time-window queries, downsampling, and retention/TTL. InfluxDB, TimescaleDB (Postgres extension), Prometheus, VictoriaMetrics, QuestDB.

- **Search engine.** Inverted-index store for full-text, relevance ranking, faceting, fuzzy/typo-tolerant matching, geo. Elasticsearch/OpenSearch, Apache Solr, Typesense, Meilisearch, Vespa.

- **Graph.** First-class nodes and edges; optimized for traversals and pattern matching (`MATCH (a)-[:FOLLOWS]->(b)`). Neo4j, JanusGraph, Amazon Neptune, TigerGraph, Dgraph.

- **Vector.** Stores high-dimensional embeddings and does approximate nearest-neighbor (ANN) search for semantic/AI retrieval. pgvector (Postgres), Pinecone, Milvus, Weaviate, Qdrant; also bolt-ons in Elasticsearch/OpenSearch and Redis.

> **OLTP vs OLAP** (you'll use these constantly): **OLTP** (Online Transaction Processing) = many small, low-latency reads/writes of individual entities (the app's live database). **OLAP** (Online Analytical Processing) = few large scans/aggregations over huge datasets for reporting/analytics (the warehouse). Row stores favor OLTP; columnar stores favor OLAP. **HTAP** (Hybrid Transactional/Analytical Processing) tries to do both in one engine.

> **Aggregate** (DDD term, used above): a cluster of objects treated as one unit for data changes — e.g., an `Order` plus its `LineItems`. Document and KV stores reward designing around aggregates because the aggregate is what you read and write atomically.

### 2.5 CAP, PACELC, and consistency models

> **CAP theorem:** in a distributed store, when a **network partition** (P — some nodes can't talk to others) happens, you must choose between **Consistency** (C — every read sees the latest write, i.e., the system behaves like a single up-to-date copy) and **Availability** (A — every request gets a non-error response). You cannot have both *during a partition*. When there's no partition you can have both. CAP is often over-cited; its practical value is forcing the question "during a network split, do I reject writes (CP) or serve possibly-stale data (AP)?"

> **PACELC:** a more useful refinement: *if Partition, choose A or C; Else (normal operation), choose Latency or Consistency.* Even with no partition, stronger consistency (e.g., quorum reads/writes, synchronous replication) costs latency. PACELC makes you reason about the common case, not just the rare partition.

> **Linearizability vs serializability:** *Linearizability* is a consistency guarantee on single objects — operations appear to happen instantaneously in real-time order. *Serializability* is an isolation guarantee on transactions — concurrent transactions produce a result equivalent to *some* serial order. **Strict serializability** = both at once (Spanner's claim). You rarely need full strict serializability; knowing which you need avoids overpaying in latency.

> **ACID:** **Atomicity** (all-or-nothing), **Consistency** (transactions move the DB between valid states per constraints), **Isolation** (concurrent transactions don't corrupt each other — tuned by isolation level), **Durability** (committed = survives crash). Classic relational guarantee; many NoSQL stores now offer ACID at least within a partition/document.

> **BASE:** the NoSQL counter-philosophy — **B**asically **A**vailable, **S**oft state, **E**ventually consistent. Trades immediate consistency for availability and scale.

> **Isolation levels (SQL):** from weakest to strongest — *Read Uncommitted, Read Committed* (Postgres/Oracle default; no dirty reads), *Repeatable Read* (MySQL/InnoDB default; no non-repeatable reads), *Serializable*. Anomalies they prevent: *dirty read* (reading uncommitted data), *non-repeatable read* (same row read twice differs), *phantom read* (a range query gains/loses rows), *write skew* (two transactions each read-then-write disjoint rows, violating a cross-row invariant — only Serializable/SSI prevents it).

> **MVCC (Multi-Version Concurrency Control):** instead of locking rows for reads, the engine keeps multiple versions of each row, each tagged with a transaction timestamp; readers see a consistent snapshot without blocking writers. Powers Postgres, Oracle, MySQL/InnoDB, CockroachDB. Cost: version bloat needing cleanup (Postgres `VACUUM`).

> **Quorum (R + W > N):** in a replicated store with N copies, requiring W replicas to ack a write and R replicas to answer a read, with R + W > N, guarantees a read overlaps with the latest write set → strong-ish consistency. Tunable in Cassandra/DynamoDB. Lower R/W → lower latency, weaker consistency.

### 2.6 Replication, partitioning, and topology

> **Replication:** keeping copies of data on multiple nodes for durability and availability. *Synchronous* (ack only after replicas confirm — safe, slower) vs *asynchronous* (ack immediately, replicas catch up — fast, can lose recent writes on failover). *Single-leader* (one node takes writes, replicas serve reads — Postgres/MySQL default), *multi-leader* (several accept writes, conflicts must be resolved), *leaderless* (any node, quorum-based — Cassandra/Dynamo).

> **Partitioning / sharding:** splitting data across nodes so no single node holds it all. *Hash sharding* (key → hash → shard; even spread, no range scans) vs *range sharding* (contiguous key ranges per shard; range scans work, risk of hotspots). The **partition key** choice determines load distribution and is the #1 source of "hot partition" pain in NoSQL.

> **Consistent hashing:** a hashing scheme where adding/removing a node remaps only a small fraction of keys (not all of them). Used by Dynamo-style and many caches to scale the cluster without massive reshuffling.

> **Hot partition / hotspot:** a single partition receiving a disproportionate share of traffic (e.g., partitioning by `country` when 60% of users are in one country), saturating one node while others idle. The framework's "scale" axis is largely about avoiding this.

### 2.7 Durability & retention mechanics

- **Replication factor (RF):** number of copies (e.g., RF=3 means 3 nodes hold each row).
- **WAL / journaling:** as defined above; the durability backbone.
- **Snapshots & PITR:** *snapshot* = point-in-time full copy; *PITR* (Point-In-Time Recovery) = WAL/binlog archiving so you can restore to any second, critical for "someone ran `DELETE` without `WHERE`."
- **TTL (Time To Live):** auto-expiry of rows after a duration — native in Redis, DynamoDB, Cassandra, TSDBs; central to session and time-series stores.

With this vocabulary, the framework in later sections is just disciplined application of these axes.

---

## 3. How it works internally (the decision process as a workflow)

Choosing a datastore *is itself a process with a control flow, inputs, intermediate state, and outputs.* Treat it as an algorithm. Below is the step-by-step internal workflow.

### 3.1 The end-to-end decision pipeline

```
[1] Enumerate access patterns        ── inputs: domain model, API spec, expected usage
        │
        ▼
[2] Quantify each pattern             ── R/W ratio, QPS, payload size, query shape, latency SLO
        │
        ▼
[3] Derive required properties       ── consistency class, durability class, scale class, query class
        │
        ▼
[4] Filter the candidate set         ── eliminate paradigms that can't serve the query shapes
        │
        ▼
[5] Score survivors on non-functionals ── cost, ops, ecosystem, team skill, cloud fit
        │
        ▼
[6] Decide: single store vs polyglot ── can one engine serve all patterns "well enough"?
        │
        ▼
[7] Write the tradeoff ledger        ── what you gain, what you give up, what could go wrong
        │
        ▼
[8] Prototype + load-test the top 1–2 ── validate p99 at target scale BEFORE committing
        │
        ▼
[9] Define exit strategy             ── how would we migrate off if we're wrong? (abstraction, dual-write plan)
```

### 3.2 Step 1 — Enumerate access patterns

Produce a table, one row per pattern. Don't skip rare-but-critical patterns (e.g., "monthly compliance export" or "delete all of a user's data for GDPR").

| Pattern ID | Operation | Trigger | Selectivity |
|---|---|---|---|
| AP-1 | Get user profile by `user_id` | profile page load | 1 row |
| AP-2 | Append order event | checkout | 1 write |
| AP-3 | List user's orders, newest first, paginated | orders page | range scan, key-prefixed |
| AP-4 | Search products by free text + filters | search bar | inverted index |
| AP-5 | Daily revenue by region | BI dashboard | full scan + aggregate |

The selectivity column ("how much data does this touch?") foreshadows the engine: 1-row patterns love KV/document; full-scan aggregates love columnar.

### 3.3 Step 2 — Quantify

For each pattern attach numbers (estimate, then validate). The minimal set:

- **QPS** now and at 12–24 month projection (growth matters more than the snapshot).
- **Read/write split.**
- **Payload size** (bytes per item; affects network, cache, cost).
- **Latency SLO** (p50/p99) and **freshness tolerance** (how stale is OK?).
- **Cardinality** (distinct keys; affects index size and partitioning).
- **Working set size** (hot data that should fit in RAM/cache vs cold archive).

Rule of thumb scale bands (informal, to anchor intuition — validate for your case):

| Band | Data size | Throughput | Typical answer |
|---|---|---|---|
| Small | < ~100 GB | < ~1k QPS | One well-indexed relational DB handles it. Don't over-engineer. |
| Medium | ~100 GB–few TB | ~1k–50k QPS | Relational + read replicas + cache; or a purpose store per hot pattern. |
| Large | tens of TB–PB | 50k–1M+ QPS | Horizontally partitioned NoSQL / columnar / distributed SQL; polyglot likely. |

### 3.4 Step 3 — Derive required properties

Translate quantified patterns into property *classes*:

- **Consistency class:** `STRONG` (money, inventory, auth) / `READ-YOUR-WRITES` (user editing their own data) / `EVENTUAL` (counters, feeds, search).
- **Durability class:** `ZERO-LOSS` (ledger) / `BOUNDED-LOSS` (analytics — losing last few seconds is fine) / `EPHEMERAL` (cache/session that can be rebuilt).
- **Query class:** the dominant shape(s) from §2.2.
- **Scale class:** from the bands above.

This is the crux: **properties, not products.** You now have a spec a store must satisfy, independent of brand names.

### 3.5 Step 4 — Filter candidates by query shape (the hard gate)

Eliminate paradigms that *cannot natively* serve the dominant query shapes. This is a hard gate because "you can technically do X in store Y" is usually a trap that costs 100× the latency or forces full scans.

| Dominant query shape | Native fit | Poor / anti-fit |
|---|---|---|
| Point lookup by key | KV, document, wide-column | columnar (overkill), search |
| Ordered range scan by key prefix | wide-column, relational (index), document | pure KV (no ordering) |
| Ad-hoc multi-attribute filter | relational, document w/ indexes, search | KV, wide-column (must predefine) |
| Joins across entities | relational, distributed SQL | KV, document, wide-column |
| Aggregation over huge data | columnar/OLAP, TSDB | OLTP relational at scale |
| Full-text / fuzzy | search engine | relational `LIKE '%x%'` (no index use) |
| Graph traversal (multi-hop) | graph | relational (recursive joins blow up), document |
| Vector similarity | vector / pgvector | everything else |
| High-rate time-window metrics | TSDB, columnar | OLTP relational |

### 3.6 Step 5 — Score survivors on non-functionals

For the 2–4 survivors, score (e.g., 1–5) on: **cost at target scale, operational burden, managed availability in our cloud, driver/ecosystem maturity, team expertise, backup/restore story, observability.** Weight by what hurts most for *your* org. A startup with three engineers should weight "managed + low ops" heavily; a large infra team can absorb self-hosted Cassandra.

### 3.7 Step 6 — Single vs polyglot decision

Ask: **can one survivor serve *all* patterns at "good enough" quality?** If yes, prefer it — one store is dramatically cheaper to operate, reason about, and keep consistent. If no — e.g., you have both "transactional orders" *and* "full-text product search" *and* "real-time revenue dashboards" — go polyglot, choosing the best store per pattern cluster and accepting synchronization cost (§7).

> **The 80/20 default:** most products start best with **one relational database** (Postgres/MySQL) plus **one cache** (Redis). Relational covers point lookups, ranges, ad-hoc filters, joins, transactions, and even decent JSON and basic full-text/vector via extensions, up to surprising scale. Reach for a specialized store only when a *specific* access pattern provably outgrows the relational engine. Premature polyglot is a top cause of operational misery.

### 3.8 Step 7 — Tradeoff ledger

Write it down explicitly. Format: *"By choosing X we gain ___. We give up ___. The biggest risk is ___, which we'll mitigate by ___."* This is exactly what a strong interviewer or staff design reviewer wants — not the choice, but the *reasoning and the things you knowingly sacrificed.*

### 3.9 Step 8 — Prototype & load-test

Never commit on a spec sheet. Spin up the top 1–2 candidates, load the realistic data distribution (not uniform synthetic — real data has skew), and **measure p99 at target QPS**. Most "it'll scale" assumptions die here cheaply, which is the point. Use representative payload sizes and cardinality; test the *worst* access pattern, not the average.

### 3.10 Step 9 — Exit strategy

Assume you might be wrong. Reduce future migration pain *now*: put a thin repository/port interface in front of the store (don't leak store-specific query DSLs all over the codebase), keep the canonical source of truth separable from derived stores (search/analytics are derived and re-buildable), and know the dual-write/backfill plan. Migration pain (§8) is the entire reason this framework exists.

---

## 4. The complete toolkit

This section enumerates the concrete artifacts you'll use to *execute* the framework: the store categories with their key knobs/defaults, the decision criteria mapped to tools, and the operational instruments for validation.

### 4.1 Store categories — capability & default reference

> Versions/defaults below are accurate as of widely-deployed recent versions but **verify against your exact version**; defaults change between major releases.

| Category | Representative engines | Data structure | Native consistency | Scaling model | Best query shapes | Key tuning knobs (examples) |
|---|---|---|---|---|---|---|
| Relational (OLTP) | PostgreSQL, MySQL/InnoDB, SQL Server | B+tree + MVCC + WAL | ACID, strong | vertical + read replicas; partitioning; Vitess/Citus for horizontal | point, range, filter, join, txn | `shared_buffers`, `work_mem`, `max_connections`, isolation level, `fsync`/`synchronous_commit` |
| Distributed SQL / NewSQL | CockroachDB, Spanner, YugabyteDB, TiDB | LSM/B-tree + Raft/Paxos | strong (serializable) | horizontal, auto-sharded | point, range, filter, join, txn at scale | replication factor, ranges/regions, follower reads |
| Key-value | Redis, Memcached, DynamoDB, RocksDB | hash / LSM | per-engine (DDB tunable) | sharded / consistent hashing | point lookup, TTL | eviction policy, TTL, RCU/WCU (DDB), `maxmemory` (Redis) |
| Document | MongoDB, Couchbase, Firestore | B-tree/LSM over BSON | tunable; ACID txns since MongoDB 4.x | sharded by shard key | aggregate fetch, filter, secondary index | shard key, write concern `w`, read concern, indexes |
| Wide-column | Cassandra, ScyllaDB, HBase, Bigtable | LSM | tunable quorum (AP) | leaderless, consistent hashing | partition-keyed point + clustering-key range | RF, consistency level (`ONE`/`QUORUM`/`ALL`), compaction strategy |
| Columnar / OLAP | ClickHouse, Druid, Pinot, BigQuery, Snowflake | columnar (Parquet/ORC) | typically eventual/batch | MPP, separated storage/compute | scan + aggregate over billions of rows | sort/order keys, partitioning, materialized views, compression codec |
| Time-series | TimescaleDB, InfluxDB, Prometheus, VictoriaMetrics | hypertables / TSM / columnar | varies | sharded by time + series | time-window scan, downsample, last-value | chunk interval, retention/TTL, continuous aggregates, downsampling |
| Search | Elasticsearch/OpenSearch, Solr, Typesense | inverted index | eventual (near-real-time) | sharded + replicated | full-text, fuzzy, facet, geo | analyzers, shards/replicas, refresh interval, relevance (BM25) tuning |
| Graph | Neo4j, Neptune, JanusGraph, TigerGraph | native adjacency / index-free | varies | varies (sharding is hard) | multi-hop traversal, pattern match | cache config, index-free adjacency, query planner hints |
| Vector | pgvector, Pinecone, Milvus, Qdrant, Weaviate | HNSW / IVF / PQ indexes | varies | sharded | approximate nearest neighbor | index type, `ef_construction`/`ef_search`, `M`, distance metric |
| Object/blob | S3, GCS, Azure Blob, MinIO | object store | strong (per-object) | effectively infinite | put/get big blobs by key | storage class, lifecycle/TTL, versioning |

> **HNSW, IVF, PQ** (vector index types): *HNSW* (Hierarchical Navigable Small World) — a layered proximity graph giving fast, accurate ANN at higher memory cost. *IVF* (Inverted File) — clusters vectors, searches only nearby clusters. *PQ* (Product Quantization) — compresses vectors to save memory at some accuracy cost. *ef_search/ef_construction/M* trade recall vs speed/memory.

> **BM25:** the default relevance scoring function in modern search engines — ranks documents by term frequency and inverse document frequency with length normalization. You tune it to control which results rank first.

### 4.2 Decision criteria → "which tool answers it"

| Criterion (from §2.2) | How to measure it | Tool/metric |
|---|---|---|
| Read/write ratio | count ops in logs/APM by type | APM (Datadog, New Relic), DB metrics |
| Query shape | inspect actual queries | `pg_stat_statements`, slow query log, EXPLAIN plans |
| Latency budget | p50/p99/p99.9 per endpoint | APM, load test (k6, JMeter, Gatling) |
| Scale/throughput | QPS, data growth | metrics + capacity model (spreadsheet) |
| Consistency need | domain analysis | design review, threat model |
| Durability need | RPO/RTO targets | DR plan, replication config |
| Cost | model $/op, $/GB | cloud pricing calc, FinOps dashboard |

> **RPO/RTO:** *Recovery Point Objective* = max acceptable data loss measured in time ("we can lose at most 5 minutes of writes"). *Recovery Time Objective* = max acceptable downtime to restore service. These directly set replication mode and backup cadence.

> **`EXPLAIN` / query plan:** every SQL engine offers `EXPLAIN [ANALYZE]` to show how it will execute a query (which indexes, seq scan vs index scan, join algorithm, estimated vs actual rows). It is the primary tool for proving whether a query shape is well-served — *use it before blaming the engine.*

### 4.3 Validation & operational instruments

| Tool | Purpose | Notes |
|---|---|---|
| **k6 / Gatling / JMeter / `wrk`** | load test candidate at target QPS | model realistic skew, payload size |
| **`pgbench` / `sysbench` / `tlp-stress`** | engine-native benchmarks (Postgres/MySQL/Cassandra) | quick apples-to-apples |
| **`EXPLAIN ANALYZE`** | prove query is indexed, not scanning | per-query |
| **`pg_stat_statements`, slow log** | find the real hot/slow patterns | feeds Step 1–2 |
| **Testcontainers (Java)** | spin real engines in integration tests | avoid H2-fakes-Postgres bugs |
| **Flyway / Liquibase** | versioned schema migrations | part of exit strategy |
| **Cloud pricing calculators** | model cost at scale | DynamoDB on-demand vs provisioned, Aurora I/O, etc. |
| **Debezium / CDC** | replicate to derived stores (search/analytics) | the glue of polyglot persistence |

> **CDC (Change Data Capture):** reading a database's commit log (Postgres logical replication / MySQL binlog) and streaming each change as an event. Debezium is the standard open-source tool. CDC is *the* mechanism for keeping a search index or analytics store in sync with the source of truth without dual-writes in application code (§7).

> **Testcontainers:** a Java/JVM library that boots real Docker containers (a real Postgres, real Redis) for tests, so you test against the actual engine's behavior instead of an in-memory fake (like H2 pretending to be Postgres, which silently diverges on SQL dialect, locking, and JSON behavior).

---

## 5. Code examples by use case

These are worked, copy-adaptable examples in **Java (JVM ecosystem)** plus the relevant SQL/CLI/config, each illustrating a *different* workload-to-store mapping and *why* it fits. The point is to see the framework produce concrete, idiomatic choices.

### 5.1 Use case A — Financial ledger (STRONG consistency, ZERO loss): relational with serializable transactions

Access pattern: debit one account, credit another, atomically; never lose or double-apply; auditable. This is the textbook case for ACID relational transactions.

```sql
-- Schema: append-only ledger is safer than mutable balances.
-- The balance is a DERIVED view; truth is the immutable entries.
CREATE TABLE ledger_entry (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT      NOT NULL,
    amount      NUMERIC(20,4) NOT NULL,  -- NEVER float for money: NUMERIC/DECIMAL only
    txn_id      UUID        NOT NULL,    -- groups the two legs of a transfer
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Idempotency: a client retry must not double-post the same transfer.
CREATE UNIQUE INDEX uq_txn_leg ON ledger_entry (txn_id, account_id);
CREATE INDEX ix_account_time ON ledger_entry (account_id, created_at);
```

```java
// Spring + JDBC; SERIALIZABLE isolation prevents write-skew on balance checks.
@Service
public class LedgerService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txnTemplate;

    public LedgerService(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.txnTemplate = new TransactionTemplate(txm);
        // SERIALIZABLE: the only level that prevents write-skew, where two
        // concurrent transfers each read an "ok" balance and both overdraw.
        this.txnTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    public void transfer(long from, long to, BigDecimal amount, UUID txnId) {
        // Retry loop: SERIALIZABLE may abort with a serialization failure
        // (Postgres SQLSTATE 40001). Aborts are EXPECTED; retry is correct.
        int attempts = 0;
        while (true) {
            try {
                txnTemplate.executeWithoutResult(status -> {
                    BigDecimal bal = balanceOf(from);
                    if (bal.compareTo(amount) < 0)
                        throw new InsufficientFundsException(from);
                    // Two legs share txnId; the unique index makes retries idempotent.
                    insertEntry(from, amount.negate(), txnId);
                    insertEntry(to,   amount,          txnId);
                });
                return; // committed
            } catch (DuplicateKeyException dup) {
                return; // this txnId already applied → idempotent success
            } catch (CannotSerializeTransactionException retryable) {
                if (++attempts > 5) throw retryable; // bounded retry
                backoff(attempts);
            }
        }
    }

    private BigDecimal balanceOf(long account) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM ledger_entry WHERE account_id=?",
            BigDecimal.class, account);
    }
    private void insertEntry(long acct, BigDecimal amt, UUID txnId) {
        jdbc.update("INSERT INTO ledger_entry(account_id,amount,txn_id) VALUES(?,?,?)",
                    acct, amt, txnId);
    }
    private void backoff(int n) { try { Thread.sleep(10L * n); } catch (InterruptedException e){ Thread.currentThread().interrupt(); } }
}
```

**Why relational here:** atomic multi-row mutation, serializable isolation to enforce the cross-row invariant (no overdraft), durability via WAL+`fsync`, mature audit/backup tooling. A KV or document store would force you to hand-roll transactions and idempotency — reinventing the database's hardest features, badly. **What we give up:** horizontal write scale beyond one primary; mitigated by partitioning accounts (e.g., Citus/Vitess) only when we actually outgrow a single node.

### 5.2 Use case B — Social feed / timeline (read-heavy, EVENTUAL ok, low latency): wide-column + cache

Access pattern: append posts; read "latest N posts for a user," paginated; 99% reads; staleness of a few seconds is acceptable; must scale to huge fan-out. Cassandra's partition-key + clustering-key model is purpose-built.

```sql
-- Cassandra CQL. Model around the READ pattern (query-first design).
-- Partition = user; clustered by time DESC so "latest N" is a contiguous read.
CREATE TABLE user_timeline (
    user_id   uuid,
    post_id   timeuuid,             -- embeds time → natural ordering
    author_id uuid,
    body      text,
    PRIMARY KEY ((user_id), post_id)   -- (partition key), clustering key
) WITH CLUSTERING ORDER BY (post_id DESC)
  AND default_time_to_live = 7776000;  -- 90-day TTL auto-expires old rows
```

```java
// DataStax Java driver. QUORUM read/write balances consistency vs latency.
public class TimelineRepository {
    private final CqlSession session;
    private final PreparedStatement insert, page;

    public TimelineRepository(CqlSession session) {
        this.session = session;
        this.insert = session.prepare(
            "INSERT INTO user_timeline(user_id,post_id,author_id,body) VALUES(?,now(),?,?)");
        this.page = session.prepare(
            "SELECT post_id,author_id,body FROM user_timeline " +
            "WHERE user_id=? AND post_id < ? LIMIT ?");  // keyset pagination
    }

    public void addPost(UUID user, UUID author, String body) {
        session.execute(insert.bind(user, author, body)
            .setConsistencyLevel(ConsistencyLevel.QUORUM)); // R+W>N → strong-ish
    }

    // Keyset (cursor) pagination: pass the last seen post_id; far cheaper than OFFSET.
    public List<Post> page(UUID user, UUID before, int limit) {
        ResultSet rs = session.execute(page.bind(user, before, limit)
            .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)); // stay in-region for latency
        // ... map rows to Post ...
        return map(rs);
    }
}
```

**Why wide-column here:** writes are cheap (LSM append), the read is a single-partition contiguous slice (no cross-node scatter), TTL handles retention for free, and leaderless replication gives high availability across regions. **What we give up:** ad-hoc queries and joins (impossible here — you must predefine access patterns; want a different query? you maintain another denormalized table). Eventual consistency is acceptable for a feed. We add Redis in front for the hottest users' first page.

### 5.3 Use case C — Session / rate-limit / cache (EPHEMERAL, sub-millisecond): Redis

Access pattern: store session by token, expire after inactivity; also do per-user rate limiting; data is rebuildable; needs <1 ms p99.

```java
// Lettuce (async Redis client). Sessions with TTL; sliding-window rate limit via Lua.
public class SessionAndRateLimit {
    private final RedisCommands<String,String> redis;

    public void putSession(String token, String json, Duration ttl) {
        // SET with expiry: ephemeral by design — Redis evicts on TTL, no cleanup job.
        redis.setex("sess:" + token, ttl.toSeconds(), json);
    }
    public Optional<String> getSession(String token) {
        return Optional.ofNullable(redis.get("sess:" + token));
    }

    // Atomic fixed-window rate limit. INCR + EXPIRE in one round trip is racy across
    // failures; a Lua script makes it atomic on the server.
    private static final String RATE_LUA =
        "local c = redis.call('INCR', KEYS[1]) " +
        "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
        "return c";

    public boolean allow(String userId, int limit, int windowSec) {
        Long count = (Long) redis.eval(RATE_LUA, ScriptOutputType.INTEGER,
            new String[]{"rl:" + userId}, String.valueOf(windowSec));
        return count <= limit;
    }
}
```

```
# redis.conf knobs that matter for this workload
maxmemory 4gb
maxmemory-policy allkeys-lru   # evict least-recently-used when full (cache semantics)
appendonly no                  # sessions are rebuildable → skip AOF fsync cost
# If you needed durability you'd set: appendonly yes / appendfsync everysec
```

**Why Redis here:** in-memory hash → sub-ms; native TTL → zero cleanup code; single-threaded atomic commands and Lua → correct counters; `allkeys-lru` matches cache semantics. **What we give up:** durability (chosen deliberately — sessions are EPHEMERAL). If we needed strict durability we'd enable AOF or pick a different store.

### 5.4 Use case D — Product search (full-text + fuzzy + facets): Elasticsearch/OpenSearch, fed by CDC

Access pattern: free-text search with typo tolerance, filter by facets (brand, price range, in-stock), relevance ranking. Relational `LIKE '%shoe%'` cannot use an index and won't rank.

```json
// Index mapping: analyzers enable tokenization, stemming, typo tolerance.
PUT /products
{
  "mappings": {
    "properties": {
      "name":     { "type": "text", "analyzer": "english" },
      "brand":    { "type": "keyword" },          // exact-match facet
      "price":    { "type": "scaled_float", "scaling_factor": 100 },
      "in_stock": { "type": "boolean" }
    }
  }
}
```

```java
// Elasticsearch Java client: full-text + fuzziness + facet filter, ranked by BM25.
SearchResponse<Product> res = esClient.search(s -> s
    .index("products")
    .query(q -> q.bool(b -> b
        .must(m -> m.match(t -> t.field("name").query(userText)
            .fuzziness("AUTO")))          // typo tolerance: "addidas" → "adidas"
        .filter(f -> f.term(t -> t.field("in_stock").value(true)))
        .filter(f -> f.range(r -> r.field("price").lte(JsonData.of(maxPrice))))))
    .aggregations("by_brand", a -> a.terms(t -> t.field("brand"))) // facet counts
    , Product.class);
```

**Critical design point:** Elasticsearch is a **derived store**, not the source of truth. The catalog lives in Postgres; **Debezium CDC** streams changes into the index. Never make your search engine the authoritative store — it's eventually consistent and optimized for query, not durability. **Why search here:** inverted index + analyzers + BM25 give relevance, typo tolerance, and facet aggregations no OLTP store can match. **What we give up:** strong consistency (search lags the source by seconds) and we run a second system kept in sync — accepted because the query shape demands it.

### 5.5 Use case E — Real-time analytics dashboard (scan + aggregate over billions of rows): columnar (ClickHouse)

Access pattern: "revenue by region by day for the last 90 days," ad-hoc slice-and-dice, sub-second over billions of event rows. An OLTP row store would scan and choke.

```sql
-- ClickHouse: columnar storage; only the columns touched are read from disk.
CREATE TABLE events (
    event_time DateTime,
    region     LowCardinality(String),  -- dictionary-encoded → tiny + fast group-by
    user_id    UInt64,
    revenue    Decimal(18,4)
) ENGINE = MergeTree
ORDER BY (region, event_time)          -- sort key: enables range pruning + locality
PARTITION BY toYYYYMM(event_time);     -- prune whole months not in the query window

-- Pre-aggregate hot rollup with a materialized view → dashboard reads are instant.
CREATE MATERIALIZED VIEW revenue_daily
ENGINE = SummingMergeTree ORDER BY (region, day) AS
SELECT region, toDate(event_time) AS day, sum(revenue) AS revenue
FROM events GROUP BY region, day;
```

```sql
-- The dashboard query: scans a column store + a tiny pre-aggregated MV → ms latency.
SELECT region, sum(revenue)
FROM revenue_daily
WHERE day >= today() - 90
GROUP BY region ORDER BY 2 DESC;
```

**Why columnar here:** reading 3 columns out of 50 over a billion rows touches ~6% of the data; columnar compression (`LowCardinality`, codecs) shrinks it further; sort key + partition pruning skip irrelevant data; materialized views pre-compute the hot rollup. **What we give up:** point updates/deletes are awkward (columnar stores are append-oriented; ClickHouse mutations are heavy) and it's a *separate* analytical system fed from the OLTP store — never run BI queries against your production OLTP DB at scale.

### 5.6 Use case F — IoT / metrics time-series (high write rate, time-window queries, retention): TimescaleDB

Access pattern: 100k sensor readings/sec; query "avg temperature per device per hour, last 7 days"; auto-drop data older than 1 year. TimescaleDB (a Postgres extension) keeps SQL/joins while adding time-series mechanics.

```sql
-- Hypertable: a regular table auto-partitioned ("chunked") by time under the hood.
CREATE TABLE readings (
    ts        TIMESTAMPTZ NOT NULL,
    device_id BIGINT      NOT NULL,
    temp_c    DOUBLE PRECISION
);
SELECT create_hypertable('readings', 'ts', chunk_time_interval => INTERVAL '1 day');

-- Continuous aggregate: incrementally maintained rollup (like a TSDB-aware MV).
CREATE MATERIALIZED VIEW hourly_temp
WITH (timescaledb.continuous) AS
SELECT device_id, time_bucket('1 hour', ts) AS hour, avg(temp_c) AS avg_temp
FROM readings GROUP BY device_id, hour;

-- Retention: auto-drop chunks older than a year (cheap whole-chunk drop, not row deletes).
SELECT add_retention_policy('readings', INTERVAL '1 year');
-- Compress old chunks → big storage savings on cold data.
SELECT add_compression_policy('readings', INTERVAL '7 days');
```

**Why TSDB here:** time-chunked storage makes inserts append-friendly and lets retention drop whole chunks instantly (vs slow `DELETE` row-by-row); continuous aggregates serve dashboard queries pre-computed; you keep full SQL and can `JOIN` to device metadata in the same Postgres. **What we give up:** raw single-node write ceiling vs a distributed TSDB; mitigated by Timescale multi-node or by VictoriaMetrics if we outgrow it.

### 5.7 Use case G — Social graph / recommendations (multi-hop traversal): Neo4j

Access pattern: "people followed by people I follow, whom I don't yet follow" (2-hop), "shortest path between two users." In SQL this is recursive self-joins that explode combinatorially.

```cypher
// Cypher (Neo4j). Index-free adjacency makes hops O(degree), not O(table size).
MATCH (me:User {id: $myId})-[:FOLLOWS]->(f:User)-[:FOLLOWS]->(suggested:User)
WHERE NOT (me)-[:FOLLOWS]->(suggested) AND suggested <> me
RETURN suggested.id, count(*) AS mutuals
ORDER BY mutuals DESC
LIMIT 20;
```

```java
// Neo4j Java driver.
try (Session s = driver.session()) {
    var recs = s.run(
        "MATCH (me:User {id:$id})-[:FOLLOWS]->()-[:FOLLOWS]->(sug) " +
        "WHERE NOT (me)-[:FOLLOWS]->(sug) AND sug<>me " +
        "RETURN sug.id AS id, count(*) AS mutuals ORDER BY mutuals DESC LIMIT 20",
        Map.of("id", myId));
    recs.forEachRemaining(r -> System.out.println(r.get("id") + " " + r.get("mutuals")));
}
```

**Why graph here:** **index-free adjacency** means each node directly references its neighbors, so a hop costs `O(neighbors)` regardless of total graph size; multi-hop traversals and shortest-path are first-class. **What we give up:** graphs are hard to shard horizontally (traversals cross shards), and they're weaker for bulk aggregations; we keep the graph for traversal patterns only and the rest of the data elsewhere.

### 5.8 Use case H — Semantic search / RAG (vector similarity): pgvector (stay in Postgres if you can)

Access pattern: "find the 10 documents most semantically similar to this query embedding." Often you already have Postgres — `pgvector` avoids adding a whole new system.

```sql
CREATE EXTENSION vector;
ALTER TABLE docs ADD COLUMN embedding vector(1536);   -- e.g., OpenAI embedding dim
-- HNSW index: fast approximate nearest-neighbor; cosine distance here.
CREATE INDEX ON docs USING hnsw (embedding vector_cosine_ops);
```

```java
// JDBC: <=> is pgvector's cosine-distance operator; ORDER BY distance LIMIT k = ANN top-k.
String sql = "SELECT id, body FROM docs ORDER BY embedding <=> ?::vector LIMIT 10";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setString(1, toPgVector(queryEmbedding)); // "[0.01,-0.2,...]"
    ResultSet rs = ps.executeQuery();
    // ... read top-10 semantically nearest docs ...
}
```

**Why pgvector here:** it keeps vectors *next to* your relational data so you can filter (`WHERE tenant_id=? ORDER BY embedding <=> ?`) and join in one query, and you add zero new infrastructure. **When to graduate to a dedicated vector DB (Pinecone/Milvus/Qdrant):** when vector count reaches the high tens/hundreds of millions, recall/latency under load degrades, or you need advanced index management — i.e., the *specific access pattern* outgrows Postgres. This is the framework's "graduate only when proven" rule applied to vectors.

> **RAG (Retrieval-Augmented Generation):** an AI pattern where you retrieve relevant documents (via vector similarity) and feed them to an LLM as context. The vector store is the retrieval engine.

---

## 6. Implementation concerns & best practices

Choosing the store is half the job; using it correctly is the other half. These concerns cut across all engines.

### 6.1 Performance

- **Index for your access patterns, not speculatively.** Each index speeds matching reads but slows writes and costs storage. Add indexes from the *enumerated patterns*, then prove with `EXPLAIN ANALYZE`. Drop unused indexes (`pg_stat_user_indexes` shows zero-scan indexes).
- **Composite index column order = leftmost-prefix rule.** An index on `(a, b, c)` serves queries filtering `a`, `a,b`, `a,b,c` — *not* `b` alone. Order by selectivity and by your WHERE/ORDER BY shape.
- **Keyset (cursor) pagination over `OFFSET`.** `OFFSET 100000` scans and discards 100k rows; `WHERE id < :lastSeen ORDER BY id DESC LIMIT n` jumps straight to the page. Mandatory at scale.
- **Avoid N+1 queries.** Fetching a list then one query per element is the classic ORM trap; batch with `IN`, joins, or projections. (See §9.)
- **Connection pooling is mandatory.** Each Postgres connection is a backend process costing memory; pool with HikariCP (JVM) and a server-side pooler (PgBouncer) for high concurrency. Default `max_connections` (~100 in Postgres) is small — overshooting it causes failures, not slowness.
- **Cache the right layer.** Cache derived/expensive reads, not everything. Beware **cache stampede** (many requests recompute the same expired key at once — mitigate with request coalescing / `EXPIRE` jitter) and **stale-cache correctness** (invalidate on write, or accept bounded staleness consciously).

### 6.2 Correctness & concurrency

- **Pick the isolation level deliberately.** Default Read Committed allows write-skew; if you have a cross-row invariant (balance, inventory), you need Serializable (or explicit `SELECT ... FOR UPDATE` locking) and a retry loop (§5.1).
- **Idempotency keys for writes.** Networks retry; without an idempotency key (unique constraint on a client-supplied token), retries double-post. Bake it into the schema.
- **Money is `DECIMAL`/`NUMERIC`, never `float`/`double`.** Binary floats can't represent `0.10` exactly; you *will* mis-bill.
- **Beware dual-write inconsistency.** Writing to two stores in app code ("save to DB, then index in ES") is not atomic — a crash between them desyncs you. Use the **transactional outbox + CDC** pattern instead (§7).
- **Understand your store's consistency default.** DynamoDB reads are *eventually consistent by default* (you opt into strong reads at 2× cost); Cassandra `ONE` reads can be stale; MongoDB read concern matters. The default is often weaker than you assume.

### 6.3 Memory

- **Working set should fit in cache/RAM.** Random-access stores degrade sharply once the hot set exceeds the buffer cache and every read hits disk. Size `shared_buffers` (Postgres, ~25% of RAM is a common start) / `maxmemory` (Redis) / heap (Elasticsearch — and **never** above ~31 GB JVM heap to keep compressed object pointers; leave the rest to the OS page cache).
- **MVCC bloat.** Postgres keeps dead row versions until `VACUUM`/autovacuum reclaims them; under heavy update/delete, tune autovacuum or tables bloat and slow down.

### 6.4 Security

- **Encryption in transit (TLS) and at rest** — non-negotiable for regulated data.
- **Least-privilege DB roles**; the app should not connect as superuser. Separate read-only roles for analytics.
- **Parameterized queries always** — string-concatenated SQL is SQL injection. (Use `PreparedStatement`/JPA parameters, never `+ userInput`.)
- **PII and the right-to-be-forgotten (GDPR).** If a deletion/erasure access pattern exists, the chosen store must support efficient targeted delete (append-only/immutable designs and some columnar stores make this painful — surface it during Step 1).
- **Network isolation:** databases in private subnets, no public exposure, secrets in a vault not in config.

### 6.5 Cost

- **Model cost per access pattern at projected scale**, not list price. DynamoDB on-demand vs provisioned, Aurora's per-I/O charge, BigQuery's per-TB-scanned (a single bad `SELECT *` can cost real money), egress fees for cross-region replication.
- **Storage tiering / TTL.** Move cold data to cheap object storage; expire ephemeral data. The cheapest byte is the one you don't store.
- **The dominant cost is usually people.** A managed store at 2× the infra price but 1/5 the ops time is frequently cheaper overall. Count on-call load in the decision.

### 6.6 Observability

- **Per-query latency histograms (p50/p99/p99.9), not averages.** Averages hide the tail that defines user experience.
- **Track the store's own internals:** Postgres `pg_stat_statements`, replication lag, cache hit ratio, lock waits, autovacuum activity; Cassandra compaction/GC pauses; Elasticsearch JVM heap and refresh times; Redis evictions/hit ratio.
- **Slow query log + APM traces** to attribute latency to specific access patterns.
- **Alert on RPO indicators:** replication lag, backup success, disk-full trajectory.

### 6.7 Testing

- **Test against the real engine** (Testcontainers), not an in-memory fake — dialect/locking/JSON differences cause "works in tests, fails in prod."
- **Load-test the worst access pattern at target scale before launch** (Step 8). Use realistic data skew.
- **Chaos/failover tests:** kill the primary and verify failover, RPO, and that the app reconnects.
- **Migration tests:** run forward and rollback migrations in CI (Flyway/Liquibase).

### 6.8 Production hardening checklist

- Automated backups + *tested* restores (an untested backup is Schrödinger's backup).
- PITR enabled for source-of-truth stores.
- Multi-AZ replication; documented, drilled failover.
- Connection pool sized below `max_connections`; circuit breakers/timeouts on the client.
- Schema migrations are backward-compatible and online (expand-contract pattern).
- Capacity headroom alert (e.g., page at 70% disk, not 95%).

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it hurts |
|---|---|
| Choosing the store before the access patterns | You'll bend every query to fit; mismatch is baked in. |
| "We'll just use Mongo/Cassandra for everything" (resume-driven) | Pays the NoSQL tax (no joins, manual consistency) without earning the scale benefit. |
| Premature polyglot / microservice-per-store | Massive ops & consistency overhead before you have the scale to justify it. |
| Search engine or cache as source of truth | They're eventually consistent and lossy by design. |
| Running OLAP/BI queries on the OLTP primary | Long scans starve transactional traffic. |
| Dual-writes in app code | Non-atomic → silent drift between stores. |
| Floats for money | Rounding bugs and mis-billing. |
| `OFFSET` pagination at scale | O(offset) cost; tail latency explodes. |
| Ignoring the migration/exit cost | The cheap wrong choice becomes the expensive trap. |
| Uniform synthetic load tests | Real skew creates hot partitions your test never saw. |

---

## 7. Advanced topics & deep internals

### 7.1 Polyglot persistence — the discipline, not the buzzword

**Polyglot persistence** = using multiple specialized stores in one system, each for the access pattern it serves best (Postgres as source of truth, Elasticsearch for search, ClickHouse for analytics, Redis for cache, S3 for blobs). It's powerful and dangerous; the danger is **keeping them consistent.**

**The canonical safe pattern: source of truth + derived stores via CDC.**

```
[Postgres  (SOURCE OF TRUTH, ACID)]
        │  logical replication / binlog
        ▼
[Debezium CDC ─→ Kafka topics]
        ├──► consumer → Elasticsearch (search index)
        ├──► consumer → ClickHouse   (analytics)
        └──► consumer → Redis        (cache warm)
```

Why this beats dual-writes: the source-of-truth write is one atomic transaction; the change is captured *from the durable commit log*, so derived stores can't silently diverge — they only lag (eventual consistency you've consciously accepted). If a consumer dies, it resumes from its Kafka offset and catches up. Each derived store is **rebuildable** by replaying CDC from a snapshot — which is also your exit strategy.

> **Transactional outbox:** a variant where the app, *in the same DB transaction* as the business write, inserts an "event" row into an `outbox` table; a relay (or CDC on the outbox) publishes it. This guarantees "event published iff business data committed" without distributed transactions (which are slow and operationally brittle — avoid 2-phase commit / XA across heterogeneous stores).

### 7.2 Single source of truth vs system of record vs system of derivation

- **System of record (SoR):** the authoritative store for a piece of data; the place you'd trust in a dispute (the ledger, the user table).
- **System of derivation/engagement:** a store built *from* the SoR for a specific query (search index, cache, OLAP, read model). Lossy/eventual by design, fully rebuildable.

Designing this split explicitly is what lets you go polyglot safely and migrate without fear: you can blow away and rebuild any derived store; you protect only the SoR with the strongest durability/consistency.

### 7.3 CQRS and read models

> **CQRS (Command Query Responsibility Segregation):** separate the write model (commands, normalized, transactional) from one or more read models (denormalized, query-optimized, possibly in different stores) kept in sync via events. This *is* the access-pattern framework taken to its logical end: each read pattern gets a store/model shaped exactly for it. Cost: more moving parts and eventual consistency between command and query sides. Use when read and write shapes diverge sharply (e.g., write a normalized order, read a denormalized dashboard).

### 7.4 Tunable consistency internals

- **DynamoDB:** eventually consistent reads (default, cheaper) vs strongly consistent reads (`ConsistentRead=true`, ~2× cost, single-region only); transactions via `TransactWriteItems` (ACID across up to 100 items). Capacity in RCU/WCU (read/write capacity units) or on-demand.
- **Cassandra:** per-query consistency level — `ONE` (fast, can be stale), `QUORUM`/`LOCAL_QUORUM` (R+W>N strong-ish), `ALL` (strongest, lowest availability). `LOCAL_QUORUM` keeps you in one datacenter for latency while staying consistent there. **Read repair** and **hinted handoff** are the background mechanisms that converge replicas; **anti-entropy repair** (`nodetool repair`) must be run periodically or deletes can "resurrect" (the **tombstone/zombie** problem).
- **MongoDB:** write concern `w:1` (primary only) vs `w:majority` (durable across majority); read concern `local`/`majority`/`linearizable`; causal consistency sessions for read-your-writes.

> **Tombstone:** in LSM/Dynamo-style stores, a delete is written as a marker (tombstone), not an immediate removal; compaction later physically removes it. If repairs don't run before tombstones expire (`gc_grace_seconds`, default 10 days in Cassandra), a stale replica that missed the delete can resurrect the data. This is a subtle, real production hazard.

### 7.5 Compaction & write-path tuning (LSM stores)

- **Compaction strategy:** *Size-Tiered (STCS)* — write-optimized, higher space amplification, good for write-heavy; *Leveled (LCS)* — read-optimized, predictable read amp, more write amp; *Time-Window (TWCS)* — ideal for time-series with TTL (drops whole expired windows). Choosing the wrong strategy for the workload causes either disk bloat or compaction-induced latency spikes.
- **Bloom filter false-positive rate**, memtable size, and flush thresholds all trade memory vs read/write amplification.

### 7.6 Distributed SQL internals (the "have your cake" option)

NewSQL/distributed SQL (CockroachDB, Spanner, YugabyteDB, TiDB) offers SQL + ACID + horizontal scale by sharding the keyspace into ranges, replicating each range via a consensus protocol, and coordinating cross-range transactions.

> **Raft / Paxos (consensus):** protocols by which a group of nodes agree on an ordered log of operations despite failures, electing a leader and committing an entry once a majority acks. This gives each shard strong consistency and automatic failover. Spanner adds **TrueTime** (GPS+atomic-clock bounded clock uncertainty) to achieve external consistency globally; others approximate with hybrid logical clocks.

The tradeoff: cross-range/cross-region transactions pay consensus latency (PACELC's "Else Latency or Consistency" — they choose Consistency, so you pay latency). Great when you genuinely need relational semantics *and* horizontal scale; overkill (and pricier/more complex) when a single Postgres suffices.

### 7.7 HTAP and the OLTP/OLAP convergence

Some systems (TiDB with TiFlash, SingleStore, Snowflake Unistore, Postgres + Citus columnar) attempt **HTAP**: serve transactions and analytics from one system, often by maintaining both a row store and a columnar replica internally. Tempting (no ETL, no second system), but the row and column engines have opposing optima, so HTAP is usually a *compromise* on both — evaluate whether a clean SoR + CDC-fed columnar store is simpler and faster for your actual scale.

### 7.8 Lesser-known behaviors that bite

- **DynamoDB hot partition / adaptive capacity:** a poor partition key concentrates traffic; adaptive capacity helps but doesn't eliminate the need for a high-cardinality, evenly-distributed key. A single partition has a hard throughput ceiling (~3000 RCU/1000 WCU).
- **Postgres `TOAST`:** large field values (>~2 KB) are transparently moved out-of-line (compressed/chunked), affecting performance of wide rows.
- **Elasticsearch refresh interval (default 1s):** docs aren't searchable until a refresh; bulk indexing throughput improves dramatically by raising it temporarily.
- **MongoDB shard key is immutable (historically) and decides everything** — a bad shard key (low cardinality or monotonically increasing) creates hot shards and is painful to change.
- **Redis is single-threaded for commands:** one O(N) command (`KEYS *`, big `SMEMBERS`) blocks *everything*. Use `SCAN`, avoid big-O surprises.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Master mapping: workload → store

| Workload | Dominant access pattern | Consistency | Recommended primary | Strong alternatives | Avoid |
|---|---|---|---|---|---|
| **Ledger / payments** | atomic multi-row txn, audit | STRONG / serializable | Postgres/MySQL (+ append-only entries) | distributed SQL (CockroachDB/Spanner) if global scale | KV/document (no real txns), eventual stores |
| **User accounts / core OLTP** | point + filter + join, txn | strong / read-your-writes | Postgres/MySQL | distributed SQL at scale | wide-column (no joins) |
| **Social feed / timeline** | append + key-prefixed range, read-heavy | eventual ok | Cassandra/ScyllaDB (+ Redis) | DynamoDB | relational at huge fan-out |
| **Session / cache / rate-limit** | point by key, TTL, sub-ms | ephemeral | Redis | Memcached, DynamoDB+TTL | relational (too slow, no TTL) |
| **Product / content search** | full-text, fuzzy, facets | eventual (derived) | Elasticsearch/OpenSearch | Typesense, Vespa, Postgres FTS (small) | relational `LIKE '%x%'` |
| **Analytics / BI** | scan + aggregate, ad-hoc | eventual (batch/near-real-time) | ClickHouse / BigQuery / Snowflake | Druid, Pinot (real-time) | OLTP primary |
| **Metrics / IoT / time-series** | time-window scan, downsample, TTL | bounded-loss ok | TimescaleDB / VictoriaMetrics / Prometheus | InfluxDB, QuestDB | OLTP relational |
| **Social graph / fraud rings** | multi-hop traversal | varies | Neo4j / Neptune | JanusGraph, TigerGraph | relational recursive joins |
| **Semantic / RAG search** | vector ANN top-k | eventual | pgvector (start) | Pinecone/Milvus/Qdrant (scale) | brute-force scans |
| **Files / media / backups** | put/get large blobs | strong per-object | S3/GCS/Azure Blob | MinIO (self-host) | storing blobs in RDBMS |

### 8.2 Paradigm comparison on the core axes

| Paradigm | Write throughput | Read flexibility | Consistency ceiling | Horizontal scale | Operational simplicity |
|---|---|---|---|---|---|
| Relational (single node) | medium | very high (joins, ad-hoc) | strong (ACID, serializable) | limited (vertical + replicas) | high (mature) |
| Distributed SQL | high | high | strong | high | medium |
| Key-value | very high | very low (key only) | tunable | very high | high |
| Document | high | medium (indexes, weak joins) | tunable/ACID-per-doc | high (shard key) | medium |
| Wide-column | very high | low (predefined patterns) | tunable (AP) | very high | medium-low |
| Columnar/OLAP | high (batch) | high for aggregates, low for points | eventual/batch | very high | medium |
| Search | medium | very high (text/fuzzy/facet) | eventual | high | medium |
| Graph | medium | very high (traversal) | varies | low (hard) | medium |
| Time-series | very high | medium (time-windowed) | varies | high | medium |

### 8.3 "Use when / avoid when" rules

- **Relational (Postgres/MySQL):** *Use when* you need transactions, joins, ad-hoc queries, strong consistency, or you're simply not sure (it's the safest default up to large scale). *Avoid when* a single proven access pattern needs scale or query shapes (full-text, billions-row aggregates, multi-hop graph) it can't serve.
- **Key-value (Redis):** *Use when* you need sub-ms point access, TTL, counters, caches, ephemeral state. *Avoid when* you need to query inside values or need durability you haven't configured.
- **Document (MongoDB):** *Use when* data is naturally a self-contained aggregate read/written whole, schema flexes, and joins are rare. *Avoid when* you have many cross-entity relationships/joins or need multi-document strong consistency frequently.
- **Wide-column (Cassandra):** *Use when* write throughput is enormous, access patterns are few and known up front, and multi-region high availability matters more than ad-hoc querying. *Avoid when* you need joins, ad-hoc queries, or have a small team (operational cost is real).
- **Columnar (ClickHouse/BigQuery):** *Use when* the workload is scan-and-aggregate over huge datasets. *Avoid when* you need point updates/deletes or low-latency single-row OLTP.
- **Search (Elasticsearch):** *Use when* full-text relevance, fuzzy matching, faceting. *Avoid as a source of truth* — always derive it.
- **Time-series (Timescale):** *Use when* time-stamped, append-heavy, time-windowed queries with retention. *Avoid when* it's really just OLTP with a timestamp column.
- **Graph (Neo4j):** *Use when* relationships and multi-hop traversal are the core query. *Avoid when* it's tabular data you're forcing into nodes/edges, or you need massive horizontal scale.
- **Distributed SQL (Cockroach/Spanner):** *Use when* you need both relational semantics and horizontal/global scale. *Avoid when* one Postgres suffices (don't pay the latency/complexity prematurely).

### 8.4 The cost of choosing wrong & migration pain

Why this framework is worth the up-front effort: migrating a live store is among the riskiest backend operations.

- **What gets locked in:** schema, query/DSL code throughout the app, ORM mappings, operational runbooks, monitoring, *and the data itself* (often TB scale, growing during the migration).
- **Why it's hard:** you must migrate **without downtime** while the system keeps writing. The standard playbook is the **dual-write + backfill + verify + cutover** pattern:
  1. **Expand:** introduce the new store behind a feature flag; write to both old and new (dual-write) for new data.
  2. **Backfill:** copy historical data to the new store in batches, throttled to not overload either.
  3. **Verify:** continuously compare reads from both stores (shadow reads) and reconcile discrepancies.
  4. **Cutover:** flip reads to the new store; keep dual-writing as a safety net.
  5. **Contract:** once confident, stop writing to the old store and decommission.
- **The traps:** data drift during dual-write (use CDC to avoid app-level dual-write where possible), differing consistency semantics changing app behavior, hidden access patterns discovered only mid-migration, and the migration itself taking *months* of engineering time.
- **The mitigation, applied early (Step 9):** keep store access behind a repository/port abstraction; keep derived stores rebuildable; choose the SoR conservatively (relational) since *that's* the store that's painful to move. You can swap a search engine or cache far more easily than your system of record.

> **Real-world flavor of "wrong choice" cost:** teams that picked a NoSQL store "for scale" they never reached commonly spend the next year reimplementing joins, transactions, and consistency in application code — paying the NoSQL tax with none of the scale benefit — then migrate back to Postgres. Conversely, teams that rode a single Postgres too far without sharding or a read model hit a write/scan wall and faced an emergency, high-pressure migration. The framework's job is to keep you in the "right store, chosen on purpose, with an exit plan" zone.

---

## 9. Failure modes & debugging

What actually breaks in production, the symptom, and the tools/commands to diagnose it.

### 9.1 Mismatch symptoms (the store is wrong for the pattern)

- **Symptom:** a query that's fine at 10k rows is 5s at 10M rows. **Diagnose:** `EXPLAIN ANALYZE` — look for `Seq Scan` where you expected `Index Scan`, huge `rows removed by filter`, or a nested-loop join over big inputs. **Fix:** add the right index, fix column order, or (if the shape is wrong for the engine, e.g., `LIKE '%x%'` full-text) move that pattern to the right store.
- **Symptom:** write latency spikes periodically on an LSM store. **Diagnose:** compaction/GC. Cassandra: `nodetool compactionstats`, GC logs; check compaction strategy vs workload. **Fix:** tune/change compaction strategy (TWCS for time-series), add hardware, or rethink the data model.
- **Symptom:** one node/partition hot, others idle. **Diagnose:** partition-key cardinality and skew (DynamoDB CloudWatch `ThrottledRequests`, Cassandra `nodetool tablehistograms`). **Fix:** redesign partition key for even distribution (add a high-cardinality component / salting).

### 9.2 The N+1 query explosion

- **Symptom:** an endpoint issues hundreds of tiny queries; latency scales with result size. **Diagnose:** APM trace showing repeated identical queries; `pg_stat_statements` showing a query with huge `calls`. **Fix:** batch with `IN`, `JOIN FETCH`/`@EntityGraph` in JPA, or projections.

### 9.3 Connection pool exhaustion

- **Symptom:** sudden errors "too many connections" / pool timeouts under load spike. **Diagnose:** Hikari metrics (active/pending), Postgres `SELECT count(*) FROM pg_stat_activity`. **Fix:** size pool below `max_connections`, add PgBouncer, set query/connection timeouts, kill long-idle-in-transaction sessions (these also block VACUUM).

### 9.4 Replication lag / stale reads

- **Symptom:** user updates data, refreshes, sees old data (read-from-replica lag). **Diagnose:** Postgres `pg_stat_replication` (`replay_lag`), Cassandra/Mongo lag metrics. **Fix:** route read-your-writes to primary, use causal/session consistency, or accept and design UI around eventual consistency.

### 9.5 Lock contention / deadlocks

- **Symptom:** spikes of latency, `deadlock detected` errors. **Diagnose:** Postgres `pg_locks` joined to `pg_stat_activity`, `log_lock_waits=on`; MySQL `SHOW ENGINE INNODB STATUS`. **Fix:** shorten transactions, consistent lock ordering, lower isolation where safe, use idempotency to allow retries.

### 9.6 MVCC bloat / autovacuum falling behind

- **Symptom:** Postgres table/index growing, queries slowing despite stable row count. **Diagnose:** `pg_stat_user_tables` (`n_dead_tup`), `pgstattuple`. **Fix:** tune autovacuum (more aggressive thresholds/workers), fix long-running transactions holding back the "xmin horizon."

### 9.7 Cache failures

- **Symptom:** latency cliff + DB overload when cache restarts (**cache stampede**) or cache and DB disagree (**stale cache**). **Diagnose:** Redis hit ratio drop, DB QPS spike correlating with cache events. **Fix:** request coalescing/single-flight, TTL jitter, write-through or proper invalidation, and make the DB survive a cold cache (capacity headroom).

### 9.8 Search/analytics drift (polyglot desync)

- **Symptom:** product exists in DB but not in search results, or counts disagree. **Diagnose:** CDC consumer lag (Kafka consumer offset vs end offset), error logs in the indexer. **Fix:** because derived stores are rebuildable, replay CDC from snapshot to fully reconcile; alert on consumer lag.

### 9.9 The "successful backup, failed restore" disaster

- **Symptom:** outage, then discover backups don't restore (wrong format, missing WAL, untested). **Diagnose/Prevent:** *test restores regularly* into a scratch environment; verify RPO/RTO with drills. Never trust a backup you haven't restored.

### 9.10 Diagnostic command quick-reference

| Engine | Command / tool | Reveals |
|---|---|---|
| Postgres | `EXPLAIN (ANALYZE, BUFFERS)` | plan, actual rows, I/O |
| Postgres | `pg_stat_statements` | hottest/slowest queries |
| Postgres | `pg_stat_activity`, `pg_locks` | live sessions, locks, deadlocks |
| Postgres | `pg_stat_replication` | replica lag |
| MySQL | `EXPLAIN`, `SHOW ENGINE INNODB STATUS`, slow log | plan, locks, slow queries |
| Cassandra | `nodetool status/compactionstats/tablehistograms/tpstats` | cluster, compaction, latency, dropped msgs |
| Redis | `INFO`, `SLOWLOG`, `MONITOR` (careful), `--latency` | memory, evictions, slow commands |
| Elasticsearch | `_cat/indices`, `_cat/shards`, `_nodes/stats`, `_cluster/health` | shard health, heap, refresh |
| DynamoDB | CloudWatch `ThrottledRequests`, `ConsumedCapacity`, Contributor Insights | throttling, hot keys |
| Kafka/CDC | `kafka-consumer-groups --describe` | consumer lag (polyglot drift) |

---

## 10. Interview drill

Each question: a crisp model answer, plus deep-probe follow-ups with answers. "Senior-signal" questions (tradeoff/justification) are marked **[S]**.

**Q1. Walk me through how you'd choose a datastore for a new service.**
*Model:* Enumerate and quantify access patterns first (R/W ratio, query shapes, QPS, payload, latency SLO). Derive required properties (consistency, durability, scale, query class). Filter paradigms by query shape (hard gate). Score survivors on cost/ops/team/ecosystem. Decide single vs polyglot — prefer single if it serves all patterns acceptably. Write a tradeoff ledger, prototype and load-test the top candidate at target scale, and define an exit/migration strategy. Default bias: one relational DB + Redis until a specific pattern provably outgrows it.
- *Probe: Why patterns before product?* Because the schema and even the store's viability depend on access patterns — NoSQL especially is query-first; picking the product first forces every query to bend to it.
- *Probe: When do you NOT load-test?* Almost never for a primary store; you might skip only for trivial small-scale internal tools. The cost of being wrong far exceeds the test.

**Q2. Relational vs NoSQL — how do you decide?**
*Model:* It's not relational-vs-NoSQL; it's query-shape-vs-engine. Need joins, ad-hoc queries, multi-row transactions, strong consistency → relational. Need extreme write throughput with few known patterns, multi-region AP availability → wide-column. Need flexible self-contained aggregates → document. Need a specific query shape (full-text, graph, vector, time-series, OLAP) → the specialized store for it. NoSQL trades query flexibility and built-in consistency for scale/throughput; only take that trade when you'll actually use the scale.
- *Probe: "NoSQL scales better" — true?* For specific access patterns and write throughput, yes (horizontal by design). But you pay the "NoSQL tax": no joins, manual consistency, predefined patterns. Modern Postgres scales much further than people assume with replicas, partitioning, and Citus/Vitess.

**Q3. [S] Your team wants Cassandra "because it scales." The app needs joins, ad-hoc filters, and 5k QPS. What do you say?**
*Model:* Push back with the framework. 5k QPS and join/ad-hoc shapes are squarely in single-Postgres territory; Cassandra would force denormalization into one table per query, manual consistency, and heavy ops — paying the NoSQL tax with no scale payoff (we're nowhere near needing it). I'd choose Postgres (+ read replicas + Redis), and document the migration trigger (e.g., write throughput or data size thresholds) at which we'd revisit. This is a "premature scaling / resume-driven" anti-pattern.
- *Probe: What would change your mind?* Evidence of a real future write firehose with few, known access patterns and a multi-region availability requirement — i.e., the actual properties Cassandra is good at.

**Q4. Design the store(s) for an e-commerce platform.**
*Model:* Polyglot, with a clear source of truth. Postgres = SoR for orders/users/inventory (ACID, joins, transactions). Redis = sessions, carts, rate-limits, hot caches (sub-ms, TTL). Elasticsearch = product search (full-text, facets), **derived** from Postgres via Debezium CDC. ClickHouse/BigQuery = analytics/BI, fed from CDC, never querying the OLTP primary. S3 = product images/invoices. pgvector or a vector DB = recommendation/semantic search. Each store maps to a distinct, justified access pattern; derived stores are rebuildable.
- *Probe: How do you keep search in sync?* CDC (Debezium → Kafka → indexer), not app-level dual-writes; on drift, replay from snapshot since search is a derived, rebuildable store.
- *Probe: Where do strong consistency requirements live?* Inventory and payments in Postgres with serializable/locking + idempotency keys; everything else can be eventual.

**Q5. [S] When is polyglot persistence the wrong call?**
*Model:* When you adopt it before scale justifies it. Each extra store multiplies ops (backups, monitoring, on-call, expertise) and introduces cross-store consistency problems. Early-stage, prefer one relational DB doing many jobs (it does JSON, full-text, even vectors via extensions) plus Redis. Go polyglot only when a *specific* access pattern provably can't be served acceptably by the primary, and you can afford the operational surface. Premature polyglot is a classic over-engineering failure.
- *Probe: Cheapest way to defer polyglot?* Use Postgres' extensions (FTS, pgvector, partitioning, JSONB) and Timescale for time-series-in-Postgres — one operational surface, several paradigms.

**Q6. Explain consistency models and how they affect store choice.**
*Model:* Strong/linearizable: reads see latest write (ledgers, inventory) — costs latency/availability (PACELC). Eventual: converges, may be stale (feeds, counters, search) — cheap and available. Read-your-writes/monotonic: session guarantees fixing the worst UX of eventual. The access pattern's tolerance for staleness sets the consistency class, which filters stores (and, within tunable stores, the read/write level: Cassandra QUORUM, DynamoDB strong reads, Mongo majority).
- *Probe: CAP in one sentence?* During a network partition you must choose between serving consistent data or staying available; PACELC adds that even without partitions, consistency costs latency.
- *Probe: Give an eventual-consistency bug you'd accept and one you wouldn't.* Accept: a like-count off by one for a few seconds. Reject: an account balance that lets a double-spend (needs serializable).

**Q7. How do you migrate from one store to another with zero downtime?**
*Model:* Expand-migrate-contract: dual-write new data to both stores behind a flag, backfill historical data in throttled batches, run shadow reads to verify and reconcile, cut over reads to the new store while still dual-writing, then contract (stop old writes, decommission). Prefer CDC over app dual-writes to avoid drift. Keep store access behind a repository abstraction so app code barely changes.
- *Probe: Biggest risk?* Data drift during dual-write and discovering hidden access patterns mid-migration; mitigate with continuous verification and CDC-based sync.

**Q8. Why not store money as a float? And what isolation level for a transfer?**
*Model:* Binary floats can't exactly represent decimal fractions → rounding/mis-billing; use `NUMERIC`/`DECIMAL`. For a transfer with a balance invariant, Serializable (or `SELECT ... FOR UPDATE` locking) is required to prevent write-skew where two concurrent transfers both pass the balance check; pair it with a retry loop on serialization failures and an idempotency key.
- *Probe: Why a retry loop?* Serializable transactions can abort with a serialization failure by design; the correct behavior is bounded retry, not surfacing an error.

**Q9. [S] Single Postgres is at 70% disk and write latency is rising. Walk me through options without jumping to "shard."**
*Model:* First, confirm it's not a fixable inefficiency: missing indexes, bloat (autovacuum behind), N+1, OLAP queries on the primary, or oversized rows. Then cheap scaling: read replicas for read load, connection pooling, partitioning hot tables, archiving cold data to object storage, moving derived workloads (search/analytics) off via CDC. Only after exhausting these and confirming a genuine write-throughput ceiling do I consider horizontal sharding (Citus/Vitess) or distributed SQL — because sharding adds permanent complexity. The senior signal is exhausting the cheap, reversible options before the expensive, irreversible one.

**Q10. How do you decide between adding an index vs a different store for a slow query?**
*Model:* Run `EXPLAIN ANALYZE`. If the query shape is natively servable (point/range/filter/join) but doing a seq scan, it's an indexing/modeling fix — cheap, stay put. If the shape is fundamentally wrong for the engine (full-text relevance, multi-hop traversal, billion-row aggregate, vector ANN), no index saves you — that pattern belongs in a specialized derived store. The test: "is the engine doing the wrong *kind* of work, or just unindexed work?"

**Q11. [S] Justify choosing DynamoDB over Postgres for a service.**
*Model:* I'd choose DynamoDB when the access patterns are few, known up front, key-based (point + partition-keyed range), throughput is very high and spiky, single-digit-ms latency at any scale matters, and I want a fully managed, near-zero-ops store with seamless horizontal scale — and I can live without joins/ad-hoc queries. I'd design single-table with carefully chosen partition keys to avoid hot partitions, use on-demand capacity for spiky load, and accept eventual-consistent reads by default. I'd reject it if I needed ad-hoc querying, joins, or my patterns weren't stable — Postgres' flexibility would win.
- *Probe: Hot partition mitigation?* High-cardinality partition key, key salting/sharding for hot items, and adaptive capacity; never partition by a low-cardinality or monotonic attribute.

**Q12. What's a system of record vs a derived store, and why does the distinction matter?**
*Model:* The system of record is the authoritative, durable, strongly-consistent source of truth (Postgres for orders). Derived stores (search, cache, OLAP, read models) are built from it for specific query shapes, are eventually consistent, lossy, and fully rebuildable. It matters because it tells you where to spend your durability/consistency budget (only the SoR), lets you go polyglot safely (rebuild any derived store from the SoR via CDC), and makes migration low-risk for everything except the SoR itself.

---

## 11. Glossary

- **ACID:** Atomicity, Consistency, Isolation, Durability — the relational transaction guarantees.
- **Access pattern:** one concrete way data is read/written, described by mechanics (key, range, filter, join, aggregate), not meaning.
- **Aggregate (DDD):** a cluster of objects changed as one unit (e.g., Order + LineItems).
- **ANN (Approximate Nearest Neighbor):** fast, slightly inexact similarity search over vectors.
- **APM:** Application Performance Monitoring (Datadog, New Relic) — traces and latency metrics.
- **BASE:** Basically Available, Soft state, Eventually consistent — the NoSQL availability-first philosophy.
- **B-tree / B+tree:** balanced, sorted, page-based structure; read/range optimized, update-in-place.
- **Bloom filter:** probabilistic "definitely not / maybe present" membership test; speeds LSM reads.
- **BM25:** standard full-text relevance ranking function.
- **Bounded context (DDD):** a self-contained part of the domain with its own model.
- **CAP theorem:** during a partition, choose Consistency or Availability.
- **CDC (Change Data Capture):** streaming each DB change from its commit log (e.g., Debezium) to other systems.
- **Clustering key:** secondary part of a wide-column primary key that orders rows within a partition.
- **Columnar / column-oriented:** stores each column contiguously; optimized for scan-and-aggregate (OLAP).
- **Compaction:** background merging of LSM SSTables, discarding overwritten/deleted data.
- **Connection pool:** reused set of DB connections (HikariCP, PgBouncer) to avoid per-request connection cost.
- **Consensus (Raft/Paxos):** protocol for nodes to agree on an ordered operation log despite failures.
- **Consistent hashing:** key→node mapping where adding/removing a node moves few keys.
- **CQRS:** Command Query Responsibility Segregation — separate write and read models.
- **Document store:** key → nested JSON/BSON aggregate; flexible schema.
- **Durability:** committed data survives crashes (via WAL + fsync + replication).
- **Eventual consistency:** replicas converge over time; reads may be temporarily stale.
- **EXPLAIN / query plan:** how the engine will execute a query; the primary indexing/perf diagnostic.
- **fsync:** syscall forcing buffered writes to durable disk.
- **Graph store:** first-class nodes/edges with index-free adjacency for traversals.
- **Hot partition / hotspot:** one partition/node getting disproportionate traffic.
- **HNSW / IVF / PQ:** vector index types trading recall, speed, and memory.
- **HTAP:** Hybrid Transactional/Analytical Processing — OLTP + OLAP in one engine.
- **Idempotency key:** client-supplied unique token making retried writes safe (no double-apply).
- **Index-free adjacency:** graph nodes directly reference neighbors → O(degree) hops.
- **Inverted index:** term → list of documents containing it; the basis of search engines.
- **Isolation level:** how much concurrent transactions can interfere (Read Committed → Serializable).
- **Key-value store:** persistent map of key → opaque value; fast point access.
- **Keyset (cursor) pagination:** paginate via `WHERE key < lastSeen` instead of `OFFSET`.
- **Linearizability:** single-object operations appear instantaneous in real-time order.
- **LSM tree (Log-Structured Merge):** write-optimized structure (memtable + WAL + SSTables + compaction).
- **Materialized view:** stored, precomputed query result, refreshed/maintained for fast reads.
- **Memtable:** in-memory sorted buffer of recent writes in an LSM store.
- **MVCC (Multi-Version Concurrency Control):** keep row versions so readers don't block writers.
- **N+1 query:** fetching a list then one query per element; a major latency anti-pattern.
- **NewSQL / distributed SQL:** SQL + ACID + horizontal scale via consensus-replicated shards.
- **OLTP / OLAP:** transactional (many small ops) vs analytical (few big scans).
- **PACELC:** if Partition→A/C, Else→Latency/Consistency; refinement of CAP for the normal case.
- **Page cache:** OS RAM cache of disk pages.
- **Partition key:** attribute determining which shard/node holds a row; decides load distribution.
- **PITR (Point-In-Time Recovery):** restore to any past moment via archived WAL/binlog.
- **Polyglot persistence:** using multiple specialized stores, each for its best access pattern.
- **Quorum (R+W>N):** read/write replica counts ensuring overlap → strong-ish consistency.
- **RAG:** Retrieval-Augmented Generation — retrieve docs (often via vectors) to ground an LLM.
- **Read/write/space amplification:** extra data read/written/stored per logical unit.
- **Replication factor (RF):** number of copies of each piece of data.
- **RPO / RTO:** max acceptable data loss / downtime, in time.
- **Serializability:** transactions appear to run in some serial order.
- **Sharding / partitioning:** splitting data across nodes (hash or range).
- **Source of truth / system of record:** the authoritative store for data.
- **SSTable (Sorted String Table):** immutable on-disk sorted file in LSM stores.
- **Strong consistency:** reads always reflect the latest committed write.
- **Tombstone:** delete marker in LSM/Dynamo stores; removed later by compaction.
- **Transactional outbox:** write business data + an event row in one transaction; relay publishes the event.
- **TTL (Time To Live):** automatic expiry of data after a duration.
- **Time-series DB (TSDB):** specialized for timestamped append-heavy data with time-window queries and retention.
- **Vector store:** stores embeddings for ANN similarity search.
- **WAL (Write-Ahead Log):** sequential durability log replayed on crash recovery.
- **Wide-column store:** partition-keyed rows with sparse column families; massive write throughput.
- **Working set:** the hot subset of data that should fit in RAM/cache.
- **Write skew:** anomaly where two transactions each read-then-write disjoint rows, breaking a cross-row invariant; only Serializable prevents it.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**The framework (memorize this loop):**
1. Enumerate access patterns → 2. Quantify (R/W, QPS, payload, latency, freshness) → 3. Derive properties (consistency/durability/scale/query class) → 4. Filter paradigms by query shape (hard gate) → 5. Score on cost/ops/team → 6. Single vs polyglot → 7. Tradeoff ledger → 8. Prototype + load-test p99 at scale → 9. Exit strategy.

**The 8 decision axes:** read/write ratio · query shape · consistency · latency budget · scale · durability · cost · operability.

**Storage structures:** B-tree = read/range optimized, update-in-place; LSM = write optimized, append + compaction (uses memtable, WAL, SSTables, Bloom filters).

**Default bias:** start with **one Postgres + Redis.** Add a specialized store only when a *specific* access pattern provably outgrows it. Premature polyglot and resume-driven NoSQL are the top anti-patterns.

**Workload → store (quick):** ledger→relational (serializable) · core OLTP→relational · feed→wide-column+cache · session/cache→Redis · search→Elasticsearch (derived via CDC) · analytics→ClickHouse/BigQuery · time-series→Timescale · graph→Neo4j · vectors→pgvector→dedicated VDB at scale · blobs→S3.

**Consistency classes:** STRONG (money/inventory) · READ-YOUR-WRITES (self-edits) · EVENTUAL (counters/feeds/search). PACELC: even without partitions, consistency costs latency.

**Polyglot done safely:** one **source of truth** (ACID relational) + **derived, rebuildable** stores fed by **CDC** (not app-level dual-writes). Use the **transactional outbox** to publish events atomically.

**Numbers to anchor (verify per version):** Postgres `max_connections` ~100 (pool below it); ES JVM heap ≤ ~31 GB; DynamoDB single-partition ceiling ~3000 RCU / 1000 WCU; Cassandra `gc_grace_seconds` default 10 days; ES default refresh interval 1s. Money = `DECIMAL`, never float. Use keyset pagination, not `OFFSET`.

**Migration playbook:** Expand (dual-write) → Backfill → Verify (shadow reads) → Cutover → Contract. Keep store access behind a repository abstraction; the SoR is the only truly painful store to move.

**Always before committing:** `EXPLAIN ANALYZE` to prove the query is served, and a load test at target p99 with realistic skew.

### Self-test (no answers — recall actively)

1. List the 9 steps of the decision pipeline in order, and name the "hard gate" step and why it's hard.
2. A workload is 99% writes, 2 KB rows, 80k writes/sec, queried later only by `(device_id, time-range)`, eventual consistency fine, 1-year retention. Which storage structure and which store(s) fit, and why? What partition/clustering key and retention mechanism?
3. Explain why writing a financial transfer with `Read Committed` isolation and `float` columns is doubly wrong, and give the correct design.
4. You have one Postgres at write-throughput limits. Name five things to try *before* sharding, in order of cost/risk.
5. Describe the CDC-based polyglot pattern and explain precisely why it's safer than app-level dual-writes. What makes a derived store "rebuildable"?
6. Give one access pattern each where you'd choose: a graph DB, a columnar store, a search engine, and pgvector — and state the query shape that justifies each.
7. State CAP and PACELC in one sentence each, then give a concrete bug you'd accept under eventual consistency and one you would not.

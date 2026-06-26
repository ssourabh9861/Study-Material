# Document & Graph Databases

> A definitive handbook chapter for senior JVM/backend engineers. Goal: master the document model (MongoDB) and the property-graph model (Neo4j) from first principles to deep internals — enough to design with them, operate and debug them in production, teach them, and answer any interview question.

---

## 1. Overview & where it fits

### 1.1 The landscape in one paragraph

A **database paradigm** is the data model plus the operations a store optimizes for. The three you must hold in your head simultaneously are:

- **Relational (RDBMS)** — data is split into *normalized* tables (rows and columns), relationships are reconstructed at query time with **joins**, and the store enforces a fixed **schema** and **ACID** transactions. Examples: PostgreSQL, MySQL, Oracle.
- **Document** — data is stored as self-contained, hierarchical **documents** (JSON-like trees), grouped into **collections**. Each document carries its own structure (schema-on-read / flexible schema). The model optimizes for fetching one *aggregate* (a parent and its nested children) in a single read. Example: MongoDB.
- **Graph** — data is stored as **nodes** (entities) and **edges/relationships** (connections), with properties on both. The model optimizes for *traversal*: hopping along relationships, especially deep, variable-length paths. Example: Neo4j.

Terms used above, defined inline:

- **Normalized**: a relational design principle where each fact lives in exactly one place (no duplication). You normalize to avoid update anomalies; you pay for it with joins at read time.
- **Join**: an operation that combines rows from two tables by matching a key (e.g., `orders.customer_id = customers.id`). Joins are how relational DBs reconstruct relationships that normalization split apart.
- **ACID**: Atomicity (all-or-nothing transactions), Consistency (constraints always hold), Isolation (concurrent transactions don't corrupt each other), Durability (committed data survives a crash). The classic correctness contract of relational DBs.
- **Aggregate**: a cluster of objects treated as a single unit for data changes (term from Domain-Driven Design). An order + its line items is a natural aggregate. Document DBs are essentially "aggregate-oriented" stores.

### 1.2 The problem each model solves

| Problem | Best-fit model | Why |
|---|---|---|
| You read/write a whole object (user profile, order, product) as a unit | Document | The aggregate is one document → one disk read, no joins |
| The *connections* between entities are the point — and you traverse them deeply | Graph | Edges are first-class; traversal cost is independent of total data size |
| You query the same data many different ways, need strong constraints, ad-hoc joins | Relational | Normalization + a mature query planner + decades of tooling |
| You need flexible/evolving schema and horizontal scale-out for simple access patterns | Document | Schema-on-read + built-in sharding |

### 1.3 When you reach for each (the mental model)

- **Reach for a document DB** when your access pattern is "load this aggregate by id, mutate it, save it" and the aggregate boundaries are stable. Think: a product catalog, a user's settings blob, a CMS article with embedded comments, an event/log record, an IoT reading.
- **Reach for a graph DB** when the *relationships are the data* and your queries are "from this node, find everything N hops away matching some pattern." Think: fraud rings, social graphs ("friends of friends who liked X"), recommendations, network/dependency topologies, identity resolution, knowledge graphs.
- **Reach for relational** (the default for most OLTP) when you need ad-hoc querying across many dimensions, strong multi-row constraints, and you don't have a dominant single access pattern. **OLTP** = Online Transaction Processing: many small, fast, concurrent read/write transactions (the workload of typical business apps), as opposed to **OLAP** (Online Analytical Processing): few large scans/aggregations for analytics.

> **One-paragraph mental model.** A document database is a giant, indexed `Map<Id, JsonTree>` that lets you embed children inside parents so the common read is a single fetch; it trades cross-document consistency and join power for read locality and schema flexibility. A graph database is a giant in-memory-friendly set of nodes connected by physical pointers (edges), so "walk the relationships" is a pointer-chase whose cost depends on how much you touch, not on how big the database is; it trades the rigid table structure for first-class, index-free traversal of relationships.

---

## 2. Foundations from first principles

### 2.1 Document databases from zero

#### 2.1.1 What is a "document"?

A **document** is a single record stored as a tree of key/value pairs, where values can be scalars (number, string, boolean, null), arrays, or nested sub-documents. It is conceptually JSON. MongoDB stores it physically as **BSON** (Binary JSON) — a length-prefixed binary encoding that adds types JSON lacks: `ObjectId`, `Date`, 32-bit/64-bit integers, `Decimal128`, binary blobs, and a few more. BSON is designed to be *traversable* (you can skip to a field using length prefixes without parsing everything) and *appendable*.

Example document (this is one record):

```json
{
  "_id": ObjectId("66a3f0c2e13b4a0012ab34cd"),
  "sku": "BOOK-1984",
  "title": "Nineteen Eighty-Four",
  "price": { "amount": 499, "currency": "INR" },   // nested sub-document
  "tags": ["dystopia", "classic"],                  // array of scalars
  "reviews": [                                       // array of sub-documents (embedding)
    { "user": "ana", "stars": 5, "text": "Chilling." },
    { "user": "ben", "stars": 4 }
  ],
  "createdAt": ISODate("2026-01-10T08:00:00Z")
}
```

Key terms:

- **Collection**: the document analogue of a table — a named group of documents. Unlike a table, a collection does *not* require all documents to share a schema (though good practice and MongoDB's optional **schema validation** push you toward consistency).
- **`_id`**: the mandatory primary key of every document. If you don't supply one, MongoDB generates an **ObjectId**: a 12-byte value = 4-byte Unix timestamp (seconds) + 5-byte random per-process value + 3-byte incrementing counter. ObjectIds are roughly time-ordered, globally unique without coordination, and embed creation time (you can extract it). They are *not* sequential integers — don't assume monotonic ordering at sub-second resolution.
- **Schema-on-read vs schema-on-write**: relational DBs validate structure at write time (schema-on-write). Document DBs traditionally validate at read time — your application code interprets the shape (schema-on-read). MongoDB now also supports optional **schema validation** at write time via JSON Schema, giving you a dial between the two.

#### 2.1.2 The defining design choice: embedding vs referencing

This is the single most important skill in document modeling.

- **Embedding** (denormalization): nest the related data *inside* the parent document (e.g., `reviews` inside the book above). One read returns everything. Trade-off: duplication and document growth.
- **Referencing** (normalization): store a child's `_id` (or a foreign-key-like field) in the parent and keep the child in another collection; "join" them in the application or with `$lookup`. Trade-off: an extra read/lookup, but no duplication and unbounded children are fine.

We devote §2.1.4 and §3 to the rules. First, why this matters at all: document DBs have **no enforced foreign keys and (historically) no multi-document transactions**, so the cheapest, safest unit of consistency is *a single document*. Embedding makes your consistency boundary match your read boundary. That's the whole philosophy.

#### 2.1.3 What you give up vs relational

- **Joins are second-class.** MongoDB has `$lookup` (a left-outer join in the aggregation pipeline), but it is not the optimized, statistics-driven join of a mature relational planner. You design to *avoid* joins by embedding.
- **No referential integrity by default.** Delete a referenced document and dangling references just... dangle. Your app must maintain integrity.
- **Cross-document atomicity is opt-in and costlier.** A single-document write is always atomic. Multi-document atomicity requires explicit transactions (added in v4.0, extended to sharded clusters in v4.2), which cost more.

#### 2.1.4 The modeling rules of thumb (memorize these)

1. **Data that is read together should be stored together** → embed.
2. **Embed one-to-few** (a handful of children, bounded). **Reference one-to-many/many-to-many** or when the "many" side is large or unbounded.
3. **The 16 MB document limit is a hard ceiling.** A document cannot exceed 16 MB in BSON. Any embedded array that can grow without bound (comments on a viral post, sensor readings, audit log) is a time bomb — reference instead, or use the **bucket pattern** (§7).
4. **Avoid unbounded arrays even below 16 MB.** Large arrays hurt because (a) updates may rewrite/move the document, (b) indexing array elements (multikey indexes) gets expensive, (c) you often don't need all elements.
5. **Optimize for your dominant query.** If 95% of reads need the parent + the last 5 children, embed the last 5 and reference the rest (the **subset pattern**, §7).
6. **Duplication is acceptable** when the duplicated data rarely changes (e.g., copy `productName` into an order line so the order is a faithful historical snapshot even if the product is renamed). This is *intentional* denormalization.

### 2.2 Graph databases from zero

#### 2.2.1 The property graph model

A **property graph** (the model Neo4j uses) has exactly four primitives:

- **Node** (vertex): an entity — a person, account, product, server.
- **Relationship** (edge): a *directed, typed* connection between two nodes — `(:Person)-[:FOLLOWS]->(:Person)`. Relationships always have a direction (stored), but you can traverse them in either direction at query time, usually at the same cost.
- **Label**: a tag grouping nodes into sets/roles — `:Person`, `:Account`. A node can have zero or more labels. Labels are how you scope queries and attach indexes.
- **Property**: a key/value pair stored on a node *or* a relationship — `name: "Ana"`, `since: 2019`, `amount: 250.0`. Properties on relationships are a superpower: you can put weights, timestamps, and confidence scores *on the edge itself*.

Contrast with the other major graph model, **RDF/triple stores** (Resource Description Framework): data is millions of `(subject, predicate, object)` triples queried with **SPARQL**. RDF is great for open, federated knowledge graphs and standards-based interchange; property graphs are usually more ergonomic and faster for operational app workloads. This chapter focuses on property graphs (Neo4j/Cypher) because that's what JVM app teams reach for.

#### 2.2.2 The killer feature: index-free adjacency

**Index-free adjacency** is the architectural property that makes graph databases special. Each node stores *direct references (pointers) to its adjacent relationships and nodes* — physically, on disk and in memory — rather than requiring an index lookup to find neighbors.

Why this matters: in a relational DB, "find the friends of person 42" is an index lookup into the `friendships` table (`O(log N)` in the *total* number of rows). "Friends of friends" is a self-join (another `O(log N)` lookup per result), and depth `d` means `d` self-joins whose cost compounds with table size. In a graph DB, finding a node's neighbors is "follow the pointers from this node" — `O(1)` in the *degree* of the node (how many edges it has), **independent of the total graph size**. A 6-hop traversal touches only the nodes/edges along the way, not the whole database.

This is the single sentence to remember for interviews: **graph traversal cost scales with the size of the result you touch, not the size of the data you store; relational join cost scales with table size at each hop.**

Term: **degree** = number of relationships attached to a node. **Out-degree** / **in-degree** split it by direction. Nodes with extreme degree are **supernodes** (e.g., a celebrity followed by 50M people, or a "country = USA" node linked to billions of records). Supernodes are the graph world's hot-spot problem (§7, §9).

#### 2.2.3 When relationships are first-class

You should feel the pull toward a graph DB when your problem statement contains words like *path, network, connection, reachable, shortest, ring, recommend, similar-to-people-who, depends-on, lineage, hierarchy of arbitrary depth*. Canonical domains:

- **Fraud detection**: find rings — accounts that share a device, phone, or address with known-bad accounts within 2–4 hops. The pattern *is* a subgraph.
- **Social networks**: friends-of-friends, mutual connections, influence propagation.
- **Recommendations**: "people who bought what you bought also bought…" is a 2-hop traversal (`you -> products -> other people -> their products`).
- **Network & IT ops / dependency graphs**: impact analysis ("if this switch fails, what goes down?"), software dependency lineage, data lineage.
- **Identity resolution / master data**: merge entities connected by shared attributes.
- **Knowledge graphs**: entities + typed relationships powering search and reasoning.

#### 2.2.4 Cypher in 90 seconds

**Cypher** is Neo4j's declarative query language. Its genius is **ASCII-art pattern matching**: you draw the pattern you want.

- Nodes are parentheses: `(p:Person {name:'Ana'})` — a `Person` node with property `name='Ana'`, bound to variable `p`.
- Relationships are arrows with bracketed types: `-[:FOLLOWS]->`.
- A path is a chain: `(a:Person)-[:FOLLOWS]->(b:Person)-[:FOLLOWS]->(c:Person)`.

```cypher
// "Find people Ana follows, who follow someone Ana doesn't follow" (friend recommendation)
MATCH (ana:Person {name:'Ana'})-[:FOLLOWS]->(friend)-[:FOLLOWS]->(suggestion)
WHERE NOT (ana)-[:FOLLOWS]->(suggestion) AND suggestion <> ana
RETURN suggestion.name, count(*) AS mutualCount
ORDER BY mutualCount DESC
LIMIT 10;
```

Cypher is now standardized as **GQL** (ISO/IEC 39075:2024 — the first new ISO database language standard since SQL), and Neo4j's dialect is converging on it. Flag this as version-relevant: GQL conformance is being phased into Neo4j 5.x+.

---

## 3. How it works internally

This is the heart of the chapter. We go deep on both engines.

### 3.1 MongoDB internals

#### 3.1.1 Storage engine: WiredTiger

Since MongoDB 3.2, the default storage engine is **WiredTiger** (the older MMAPv1 is gone as of 4.2). WiredTiger is a B-tree / LSM-capable, document-level-concurrency storage engine. What you must know:

- **Document-level concurrency**: WiredTiger uses **MVCC** (Multi-Version Concurrency Control). MVCC means each transaction sees a consistent *snapshot* of data as of its start; writers create new versions instead of blocking readers. Concurrency is controlled at the *document* level, so two writes to different documents in the same collection don't block each other. (Pre-WiredTiger, MMAPv1 used collection- or database-level locks — a major historical scaling pain.)
- **The WiredTiger cache**: an in-process cache of uncompressed data and index pages. Default size = `max(50% of (RAM − 1 GB), 256 MB)`. This is the single most important memory knob (§4.4, §6.1). The OS filesystem cache *additionally* holds compressed data, so MongoDB effectively uses two cache layers.
- **Compression**: collections use **Snappy** by default (fast, ~moderate ratio); indexes use **prefix compression**. You can switch collection compression to **zlib** or **zstd** (better ratio, more CPU). Compression applies on disk and in the OS cache, not in the WiredTiger cache.
- **Checkpoints**: WiredTiger flushes a consistent snapshot to disk every 60 seconds (or every 2 GB of journal, whichever first). Between checkpoints, durability is provided by the **journal** (write-ahead log). The journal is flushed to disk every 100 ms by default (and on `j:true` writes). A crash recovers to the last checkpoint, then replays the journal.

Inline definitions:
- **MVCC**: keep multiple versions of a record so readers and writers don't block each other; each sees a snapshot.
- **WAL / journal (write-ahead log)**: before applying a change to the main data files, write it to a sequential log first. If you crash, replay the log to recover. This is how databases get durability without `fsync`-ing the whole dataset on every write.
- **Checkpoint**: a point-in-time consistent flush of all dirty data to the main files, so recovery only needs to replay the journal *after* the checkpoint.

#### 3.1.2 The path of a write (single document)

1. Client sends an `insert`/`update`/`delete` to a `mongod` (the server process).
2. The query layer parses it, applies schema validation if configured.
3. WiredTiger acquires the necessary document-level lock and creates a new MVCC version in the cache (mark the page **dirty**).
4. The change is appended to the **journal** in memory; durability depends on **write concern** (§3.1.7).
5. The operation is recorded in the **oplog** (operations log — a capped collection that drives replication, §3.1.6) if this is a replica set.
6. Acknowledgment returns per the write concern.
7. Later, the journal is flushed (≤100 ms) and the dirty page is checkpointed (≤60 s).

#### 3.1.3 The path of a read / query planning

1. Client sends a `find`/`aggregate`.
2. The **query planner** generates candidate plans (different index choices, or a collection scan = **COLLSCAN**).
3. For a new query shape, MongoDB runs candidates in parallel for a short trial and **caches the winning plan** keyed by query shape (the **plan cache**). Subsequent identical-shape queries reuse it until it's evicted or a better candidate appears (plans are periodically re-evaluated).
4. The chosen plan executes through a pipeline of stages (`IXSCAN` → `FETCH` → `SORT` → `PROJECTION`, etc.). You inspect this with `explain()` (§9).
5. Results stream back via a **cursor** in batches (default first batch 101 docs or 16 MB, subsequent batches 16 MB).

Definitions:
- **COLLSCAN**: collection scan — reads every document; the thing you usually want to avoid.
- **IXSCAN**: index scan — walks an index B-tree, then `FETCH`es the matching documents.
- **Covered query**: a query answerable entirely from an index without fetching documents (all needed fields are in the index). The fastest kind.

#### 3.1.4 Indexes (the B-tree story)

MongoDB indexes are **B-trees** (specifically B+-tree-like structures in WiredTiger). Index types:

- **Single-field**, **compound** (multiple fields, order matters — see the ESR rule in §4.3), **multikey** (automatically created when you index an array field — one index entry per array element), **text** (tokenized full-text), **geospatial** (`2d`, `2dsphere`), **hashed** (for hashed sharding), **wildcard** (`$**` — index all/unknown fields), **TTL** (auto-expire documents), **partial** (index only documents matching a filter), **sparse** (skip documents missing the field), and **unique**.
- **Index intersection** exists but is weak; in practice you design *compound* indexes to serve queries rather than relying on the planner to intersect two single-field indexes.

#### 3.1.5 The aggregation pipeline

The **aggregation pipeline** is MongoDB's data-processing framework: an ordered array of **stages**, each transforming the stream of documents and passing it to the next (like Unix pipes). It's how you do GROUP BY, JOINs, reshaping, and analytics.

Control/data flow internals worth knowing:
- The optimizer **reorders and merges stages**: e.g., it pushes `$match` and `$project` as early as possible (predicate/projection pushdown) so an index can be used and less data flows downstream. It can coalesce `$sort`+`$limit` into a top-k that doesn't materialize the full sort.
- A pipeline can use an index **only for leading stages** until the first stage that transforms documents in a way the index can't follow (typically the first `$group`, `$unwind`, or a `$project` that renames the indexed field). After that point, it's in-memory streaming.
- Each stage has a **100 MB memory limit** by default; exceeding it errors unless you set `allowDiskUse:true`, which spills to temp files on disk.

Key stages (full table in §4):
- `$match` (filter), `$project` (reshape/select), `$group` (aggregate), `$sort`, `$limit`/`$skip`, `$unwind` (explode an array into one doc per element), `$lookup` (left-outer join to another collection), `$facet` (run multiple sub-pipelines on the same input), `$bucket`/`$bucketAuto` (histogram), `$graphLookup` (recursive lookup — poor-man's graph traversal), `$merge`/`$out` (write results to a collection).

#### 3.1.6 Replication (replica sets)

A **replica set** is a group of `mongod` processes holding the same data for high availability:
- One **primary** (accepts writes) and multiple **secondaries** (replicate from the primary's **oplog** asynchronously).
- The **oplog** is a special **capped collection** (fixed-size, ring-buffer-like) of idempotent operations. Secondaries tail it and apply ops in order. Idempotent means applying an op twice yields the same result — essential for safe re-application.
- **Elections (Raft-like)**: if the primary becomes unreachable, the remaining members run an election to pick a new primary. **Raft** is a consensus algorithm where a majority of nodes vote for a leader; MongoDB's protocol (PV1) is Raft-inspired. You need a **majority** (more than half) of voting members up to elect and to commit `majority` writes — which is why an **arbiter** (a vote-only member with no data) or an odd member count is used to break ties.
- **Read preference** lets clients read from primary (default, strongest) or secondaries (eventual consistency, possible stale reads). **Read concern** (`local`, `majority`, `linearizable`, `snapshot`) controls the consistency/recency guarantee.

#### 3.1.7 Write concern & read concern (the consistency dials)

- **Write concern `w`**: how many members must acknowledge a write. `w:1` = primary only (fast, can lose data on failover). `w:"majority"` = a majority have it (durable across failover — the safe default for important data). `j:true` = also require the journal flushed to disk. `wtimeout` caps how long to wait.
- **Read concern**: `local` (whatever the node has, may roll back), `majority` (only data acknowledged by a majority — won't be rolled back), `linearizable` (reflects all majority-acked writes that completed before the read began — strongest, slowest, primary-only), `snapshot` (a consistent snapshot across a transaction).
- This is MongoDB's tunable position on **CAP**. **CAP theorem**: in the presence of a network **P**artition you must choose between **C**onsistency (every read sees the latest write) and **A**vailability (every request gets a non-error response). MongoDB defaults toward CP for writes (majority) but lets you trade toward AP with relaxed concerns and secondary reads.

#### 3.1.8 Sharding (horizontal scale-out)

**Sharding** = partitioning a collection's documents across multiple machines (**shards**) so the dataset and throughput exceed a single server. Components:

- **Shard**: a replica set holding a subset of the data.
- **`mongos`**: a stateless router clients connect to; it routes queries to the right shard(s) using the shard key.
- **Config servers**: a replica set storing cluster metadata (which key ranges live on which shard).
- **Shard key**: the field(s) MongoDB uses to partition data. *This is the most consequential and irreversible design decision in a sharded cluster* (though MongoDB 5.0+ allows **resharding**, it's expensive).
  - **Ranged sharding**: contiguous key ranges per shard. Good for range queries; risky for monotonically increasing keys (all new writes hit one shard — a **hot shard**).
  - **Hashed sharding**: hash the key to spread writes evenly. Great for write distribution; destroys range-query locality.
- **Chunks & the balancer**: data is split into **chunks** (logical key ranges); the **balancer** migrates chunks between shards to keep them even. (MongoDB 6.0+ rebalances by *data size* rather than chunk count.)
- **Targeted vs scatter-gather queries**: queries that include the shard key are **targeted** to one shard (fast). Queries without it become **scatter-gather** (broadcast to all shards, results merged by `mongos`) — slow and unscalable. So: choose a shard key that appears in your most common queries *and* has high cardinality *and* spreads writes.

### 3.2 Neo4j internals

#### 3.2.1 Native graph storage

Neo4j is a **native graph database** — it stores graphs as graphs, not as tables underneath. The on-disk layout uses **fixed-size record store files**:

- `neostore.nodestore.db` — node records.
- `neostore.relationshipstore.db` — relationship records.
- `neostore.propertystore.db` — property records (with separate stores for strings/arrays).
- Plus relationship-type, label, and schema stores.

Because records are **fixed-size**, Neo4j computes a record's file offset as `recordId × recordSize` — an `O(1)` direct lookup, no index needed. This is the mechanical basis of index-free adjacency.

A **node record** (historically ~15 bytes) stores: in-use flag, a pointer to its *first relationship*, a pointer to its *first property*, and a pointer to its labels. A **relationship record** (~34 bytes) is the clever part: it's a node in a **doubly linked list** of relationships. It stores: the two end-node ids, the relationship type, a pointer to the first property, and **four pointers**: previous/next relationship in the *source* node's relationship chain, and previous/next in the *target* node's chain.

So to enumerate a node's relationships, Neo4j reads the node record → follows `firstRel` → walks the linked list via the prev/next pointers. No global index lookup — just pointer chasing. This is `O(degree)`.

> Version note: Neo4j 5.x introduced a new "block" storage format and relationship group/sparse-vs-dense handling that changes exact byte sizes; the *principle* (fixed-size records, linked relationship chains, index-free adjacency) is unchanged.

#### 3.2.2 Dense nodes and relationship groups

A naive single linked list per node breaks down for **dense nodes** (high degree). Neo4j detects when a node crosses a density threshold (default ~50 relationships) and switches it to use **relationship group records**: instead of one mixed chain, relationships are grouped *by type and direction*, with a group record pointing to each chain. This means "get this dense node's `:FOLLOWS` out-relationships" doesn't have to scan unrelated edges. This is a critical supernode mitigation (§7, §9).

#### 3.2.3 The page cache

Neo4j keeps the store files in its own **page cache** (off-heap by default in modern versions). For best performance you want the working set — ideally the whole graph's node+relationship stores — to fit in the page cache, so traversals are pure memory pointer-chasing with no disk I/O. Sizing the page cache (`server.memory.pagecache.size`) is the #1 Neo4j performance knob, mirroring WiredTiger cache in MongoDB.

#### 3.2.4 How a Cypher query executes (control/data flow)

1. **Parse & semantic check**: Cypher text → AST.
2. **Plan**: the **cost-based optimizer (CBO)** uses **statistics** (counts of labels, relationship types, index selectivity) to pick a plan: which node to start from (the **anchor**), which index to use to find it, and the traversal order/direction.
3. **Anchor selection** is crucial: the planner picks the most *selective* starting point (e.g., the indexed `:Person {email:'x'}` node rather than the millions of `:Person` nodes) and **expands** from there.
4. **Execute via operators**: `NodeIndexSeek`, `Expand(All)` (follow relationships), `Filter`, `ProjectEndpoints`, `VarLengthExpand` (for variable-length `[:R*1..3]` patterns), etc. Inspect with `EXPLAIN` (plan only) and `PROFILE` (plan + actual rows + `db hits`, §9).
5. **db hits**: Neo4j's unit of work — each store access (read a node, a relationship, a property) is a db hit. Minimizing db hits is how you tune Cypher.

Definition: **selectivity** = the fraction of rows a predicate keeps. A highly selective predicate (returns few rows) is a great anchor; a low-selectivity one (returns most rows) is a bad starting point.

#### 3.2.5 Indexes in Neo4j

Indexes in a graph DB are *not* used for traversal (that's index-free adjacency). They're used to **find the starting node(s)** quickly. Types:
- **Range/B-tree indexes** (the default secondary index on `(:Label {property})`) for equality and range lookups.
- **Text indexes** (substring/`CONTAINS`/`ENDS WITH`).
- **Point indexes** (spatial).
- **Full-text indexes** (Lucene-backed) for tokenized search.
- **Vector indexes** (Neo4j 5.13+) for embedding similarity / GraphRAG.
- **Constraints**: `UNIQUE`, `NODE KEY` (composite unique + existence), and existence constraints (existence/key constraints are an Enterprise feature) — these also create backing indexes.

#### 3.2.6 Transactions & consistency

Neo4j is **fully ACID** (unlike many distributed graph stores). A single-instance Neo4j gives strong serializable-ish guarantees with write locks on touched nodes/relationships. In **Neo4j clustering** (Enterprise), the **Raft consensus** protocol replicates the transaction log to a majority of core servers before commit; **read replicas** scale out reads with eventual consistency. (Raft, again: a leader is elected by majority vote; writes commit once a majority persists the log entry — the same family of algorithm MongoDB's election protocol belongs to.)

---

## 4. The complete toolkit

### 4.1 MongoDB — CRUD & core operations

| Operation | Method (shell / Java driver) | Purpose | Key params / defaults |
|---|---|---|---|
| Insert one | `insertOne(doc)` | Add a document | Generates `_id` if absent |
| Insert many | `insertMany(docs)` | Bulk insert | `ordered:true` default (stop on first error); set `false` to continue |
| Find | `find(filter, projection)` | Query | Returns a cursor; `limit/skip/sort` chainable |
| Find one | `findOne(filter)` | First match | — |
| Update one | `updateOne(filter, update, opts)` | Modify first match | `upsert:false` default; update operators below |
| Update many | `updateMany(...)` | Modify all matches | — |
| Replace | `replaceOne(filter, doc)` | Swap whole document | Keeps `_id` |
| Find & modify | `findOneAndUpdate/Replace/Delete` | Atomic read-modify-write, returns doc | `returnDocument: BEFORE/AFTER` |
| Delete | `deleteOne/deleteMany(filter)` | Remove | — |
| Bulk | `bulkWrite(ops)` | Mixed batch | `ordered` toggle |
| Count | `countDocuments(filter)` | Accurate count | `estimatedDocumentCount()` is fast but approximate |
| Distinct | `distinct(field, filter)` | Unique values | — |

**Update operators**: `$set`, `$unset`, `$inc`, `$mul`, `$min`/`$max`, `$rename`, `$push`/`$pull`/`$addToSet`/`$pop` (arrays), `$each`/`$slice`/`$sort` (array modifiers), `$currentDate`, `$setOnInsert` (upsert-only). **Query operators**: `$eq/$ne/$gt/$gte/$lt/$lte`, `$in/$nin`, `$and/$or/$nor/$not`, `$exists`, `$type`, `$regex`, `$elemMatch` (match array element against multiple conditions), `$all`, `$size`, `$text`, geo (`$near`, `$geoWithin`).

### 4.2 MongoDB — aggregation stages (most-used)

| Stage | Purpose | Notes |
|---|---|---|
| `$match` | Filter | Place first; can use indexes |
| `$project` / `$set`/`$addFields`/`$unset` | Reshape | `$set` adds/keeps; `$project` selects |
| `$group` | Aggregate by `_id` expr | Accumulators: `$sum,$avg,$min,$max,$push,$addToSet,$first,$last,$count,$mergeObjects` |
| `$sort` | Order | Uses index if early; else in-memory (100 MB cap) |
| `$limit` / `$skip` | Paginate | Prefer range-based pagination over big `$skip` |
| `$unwind` | Array → one doc per element | `preserveNullAndEmptyArrays` |
| `$lookup` | Left-outer join | `localField/foreignField` or sub-pipeline with `let` |
| `$graphLookup` | Recursive/self join (traversal-lite) | `maxDepth`, `connectFromField/connectToField` |
| `$facet` | Multiple pipelines on same input | Great for dashboards (count + page in one round trip) |
| `$bucket` / `$bucketAuto` | Histograms | — |
| `$merge` / `$out` | Materialize results to a collection | `$merge` upserts (materialized views); `$out` replaces |
| `$setWindowFields` | Window functions (5.0+) | Running totals, rank, moving avg |

### 4.3 MongoDB — the ESR index rule

For compound indexes, order fields **Equality → Sort → Range** (ESR):
1. Fields matched with **equality** first (`status: "active"`).
2. Then fields used for **sort**.
3. Then **range** fields (`price: {$gt: 100}`) last.

This lets one index satisfy filter + sort + range without an in-memory sort. Getting ESR wrong is the #1 cause of "I have an index but it's still slow / doing a SORT stage."

### 4.4 MongoDB — key configuration & tools

| Item | What it does | Default |
|---|---|---|
| `storage.wiredTiger.engineConfig.cacheSizeGB` | WiredTiger cache size | `max(50% of (RAM−1GB), 256MB)` |
| `--wiredTigerCollectionBlockCompressor` | Collection compression | `snappy` (alt: `zstd`, `zlib`, `none`) |
| Write concern `w` / `j` / `wtimeout` | Durability/ack policy | `w:"majority"` for acked writes (driver default modern) |
| Read concern / read preference | Consistency / source | `local` / `primary` |
| `mongosh` | The shell | — |
| `mongodump` / `mongorestore` | Logical backup/restore (BSON) | — |
| `mongoexport` / `mongoimport` | JSON/CSV export/import | — |
| `explain("executionStats")` | Query plan + actual stats | — |
| `db.currentOp()` / `db.killOp()` | Inspect/kill running ops | — |
| `mongostat` / `mongotop` | Live throughput / per-collection time | — |
| Database Profiler (`db.setProfilingLevel`) | Log slow ops to `system.profile` | level 0 (off); level 1 logs slow (>100 ms) |
| Atlas / Ops Manager / Compass | Hosted service / monitoring / GUI | — |

### 4.5 Neo4j / Cypher — clauses

| Clause | Purpose |
|---|---|
| `MATCH` | Find a pattern (read) |
| `OPTIONAL MATCH` | Like SQL left outer join — nulls if no match |
| `WHERE` | Filter (attaches to MATCH/WITH) |
| `RETURN` | Project results |
| `WITH` | Pipe results between query parts; aggregate then continue |
| `CREATE` | Create nodes/relationships |
| `MERGE` | Get-or-create (match if exists else create) — needs uniqueness or you'll duplicate |
| `SET` / `REMOVE` | Modify properties/labels |
| `DELETE` / `DETACH DELETE` | Delete node (DETACH also deletes its relationships) |
| `UNWIND` | List → rows (the inverse of `collect()`) |
| `CALL { ... }` | Subquery (incl. `CALL { } IN TRANSACTIONS` for batched writes) |
| `CALL db.*/dbms.*/apoc.*` | Procedures (introspection, APOC library) |
| `FOREACH` | Mutate per list element |

**Patterns**: `[:TYPE]`, multiple types `[:A|B]`, variable length `[:KNOWS*1..3]` (1 to 3 hops), unbounded `[*]` (dangerous), shortest path `shortestPath((a)-[*]-(b))` / `allShortestPaths`. **Aggregations**: `count, sum, avg, min, max, collect, percentileCont, stDev`. **Path functions**: `length(p), nodes(p), relationships(p)`.

### 4.6 Neo4j — operational toolkit

| Tool | Purpose |
|---|---|
| `cypher-shell` | CLI query client |
| Neo4j Browser / Bloom | Web UI / visualization & exploration |
| `EXPLAIN` / `PROFILE` | Plan only / plan + actual db hits |
| `neo4j-admin database dump/load` | Backup/restore (offline) |
| `neo4j-admin database backup/restore` | Online backup (Enterprise) |
| `LOAD CSV` | Bulk import via Cypher |
| `neo4j-admin database import full` | Fast bulk import (offline, millions/sec) |
| GDS (Graph Data Science library) | PageRank, community detection, centrality, node embeddings, shortest paths at scale |
| APOC | "Awesome Procedures on Cypher" — utility procedures (import/export, refactoring, dynamic Cypher) |
| `dbms.listQueries` / `dbms.killQuery` | Inspect/kill running queries |
| `server.memory.pagecache.size`, `server.memory.heap.max_size` | Memory tuning |

---

## 5. Code examples by use case

These use the modern **MongoDB Java Driver (4.x/5.x)** and **Neo4j Java Driver (5.x)**, the idioms a JVM team would actually ship.

### 5.1 MongoDB (Java) — model an e-commerce order as an aggregate (embedding)

```java
// Maven: org.mongodb:mongodb-driver-sync:5.x
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.List;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
    MongoDatabase db = client.getDatabase("shop");
    MongoCollection<Document> orders = db.getCollection("orders");

    // An order is a natural AGGREGATE: header + line items embedded.
    // We deliberately COPY product name & price into the line item so the
    // order is a faithful historical snapshot (intentional denormalization).
    Document order = new Document("_id", "ORD-1001")
        .append("customerId", "CUST-42")
        .append("status", "PLACED")
        .append("placedAt", new java.util.Date())
        .append("items", List.of(
            new Document("sku", "BOOK-1984").append("name", "1984")
                .append("unitPrice", 499).append("qty", 2),
            new Document("sku", "PEN-BLK").append("name", "Black Pen")
                .append("unitPrice", 20).append("qty", 5)))
        .append("total", 1098);

    orders.insertOne(order); // single-document write = atomic, no transaction needed

    // Reading the whole aggregate is ONE indexed lookup by _id, zero joins:
    Document fetched = orders.find(eq("_id", "ORD-1001")).first();

    // Atomic field update on the embedded doc — still single-document atomic:
    orders.updateOne(eq("_id", "ORD-1001"),
        combine(set("status", "SHIPPED"),
                currentDate("shippedAt")));

    // Atomically push a new line item AND adjust total in one write:
    orders.updateOne(eq("_id", "ORD-1001"),
        combine(push("items",
                   new Document("sku","NOTE").append("name","Notebook")
                     .append("unitPrice",60).append("qty",1)),
                inc("total", 60)));
}
```

Why embedding here: an order and its items are always read and written together, the item count is bounded (few-to-dozens), and the consistency boundary (one order) equals the read boundary.

### 5.2 MongoDB (Java) — referencing + `$lookup` for an unbounded relationship

When the "many" side is large/unbounded (a product's reviews can hit millions), **reference** and join on demand.

```java
MongoCollection<Document> products = db.getCollection("products");
MongoCollection<Document> reviews  = db.getCollection("reviews");

// Reviews live in their own collection, referencing the product:
reviews.insertOne(new Document("productId","BOOK-1984")
    .append("user","ana").append("stars",5).append("text","Chilling"));

// Index the foreign key so the join/lookup is fast:
reviews.createIndex(Indexes.ascending("productId"));

// Aggregation: product + its 5 latest reviews (subset via sub-pipeline):
List<Document> pipeline = List.of(
    Aggregates.match(eq("_id","BOOK-1984")),
    Aggregates.lookup("reviews", List.of(
            new Variable<>("pid", "$_id")),
        List.of(
            Aggregates.match(expr(new Document("$eq", List.of("$productId","$$pid")))),
            Aggregates.sort(Sorts.descending("createdAt")),
            Aggregates.limit(5)),
        "latestReviews"));

products.aggregate(pipeline).forEach(System.out::println);
```

### 5.3 MongoDB (Java) — analytics with the aggregation pipeline

Top 5 customers by spend in 2026 with average order value — a `$match → $group → $sort → $limit` pipeline that the optimizer will index-accelerate at the `$match`.

```java
import java.util.Date;
import java.time.*;

Date start = Date.from(LocalDate.of(2026,1,1).atStartOfDay(ZoneOffset.UTC).toInstant());
Date end   = Date.from(LocalDate.of(2027,1,1).atStartOfDay(ZoneOffset.UTC).toInstant());

List<Document> top = orders.aggregate(List.of(
    Aggregates.match(and(gte("placedAt", start), lt("placedAt", end),
                         eq("status","SHIPPED"))),
    Aggregates.group("$customerId",
        Accumulators.sum("spend", "$total"),
        Accumulators.avg("avgOrder", "$total"),
        Accumulators.sum("orders", 1)),
    Aggregates.sort(Sorts.descending("spend")),
    Aggregates.limit(5)
)).into(new java.util.ArrayList<>());
// Index to support the $match anchor: { status:1, placedAt:1 } (ESR: equality then range)
```

### 5.4 MongoDB (Java) — a multi-document transaction (when you truly need it)

Money transfer across two accounts — the textbook case where single-document atomicity isn't enough.

```java
import com.mongodb.client.*;
import com.mongodb.*;

MongoCollection<Document> accounts = db.getCollection("accounts");

try (ClientSession session = client.startSession()) {
    TransactionOptions txnOpts = TransactionOptions.builder()
        .writeConcern(WriteConcern.MAJORITY)        // durable across failover
        .readConcern(ReadConcern.SNAPSHOT)          // consistent snapshot
        .build();

    session.withTransaction(() -> {
        // Conditional debit: only succeeds if balance >= 100 (prevents overdraft)
        var debited = accounts.updateOne(session,
            and(eq("_id","A"), gte("balance",100)), inc("balance",-100));
        if (debited.getModifiedCount() == 0)
            throw new IllegalStateException("Insufficient funds"); // aborts txn
        accounts.updateOne(session, eq("_id","B"), inc("balance",100));
        return null;
    }, txnOpts);
}
// NOTE: transactions add latency & lock contention. Prefer redesigning so the
// invariant lives in ONE document when you can. Reserve txns for genuine
// cross-document invariants.
```

### 5.5 MongoDB — TTL index for ephemeral data (sessions/cache)

```javascript
// mongosh: auto-delete session docs 30 minutes after lastActive
db.sessions.createIndex({ lastActive: 1 }, { expireAfterSeconds: 1800 });
// A background thread sweeps expired docs ~every 60s (not exact-time deletion).
```

### 5.6 Neo4j (Cypher) — fraud ring detection

Find accounts that share an identifier (device/phone/address) with a flagged account within 2 hops — the canonical "the pattern is a subgraph" case.

```cypher
// Data model: (:Account)-[:USED]->(:Device|:Phone|:Address)
// Flagged accounts already marked with :Fraud
MATCH (bad:Account:Fraud)-[:USED]->(shared)<-[:USED]-(suspect:Account)
WHERE NOT suspect:Fraud
RETURN suspect.id AS suspectAccount,
       collect(DISTINCT shared.value) AS sharedIdentifiers,
       count(DISTINCT bad) AS linkedBadAccounts
ORDER BY linkedBadAccounts DESC;
```

This is `O(edges touched)`, not `O(total accounts)`. In SQL this is a multi-self-join over a junction table whose cost grows with the junction table's size.

### 5.7 Neo4j (Cypher) — recommendations (collaborative filtering, 2 hops)

```cypher
// "People who bought what you bought also bought…"
MATCH (me:Customer {id:$customerId})-[:BOUGHT]->(p:Product)
      <-[:BOUGHT]-(other:Customer)-[:BOUGHT]->(reco:Product)
WHERE NOT (me)-[:BOUGHT]->(reco) AND me <> other
RETURN reco.name AS recommendation,
       count(DISTINCT other) AS supportingCustomers
ORDER BY supportingCustomers DESC
LIMIT 10;
```

### 5.8 Neo4j (Cypher) — shortest path / "how are these two connected?"

```cypher
MATCH (a:Person {name:$from}), (b:Person {name:$to}),
      p = shortestPath((a)-[:KNOWS*..6]-(b))   // cap depth; never use [*] unbounded
RETURN [n IN nodes(p) | n.name] AS chain, length(p) AS degreesOfSeparation;
```

### 5.9 Neo4j (Java driver) — parameterized writes and reads

```java
// Maven: org.neo4j.driver:neo4j-java-driver:5.x
import org.neo4j.driver.*;
import static org.neo4j.driver.Values.parameters;

try (Driver driver = GraphDatabase.driver(
        "neo4j://localhost:7687", AuthTokens.basic("neo4j","password"))) {

    // Write in an auto-committing managed transaction (retries on transient errors)
    try (var session = driver.session()) {
        session.executeWrite(tx -> {
            tx.run("""
                MERGE (a:Customer {id:$cid})
                MERGE (p:Product  {sku:$sku})
                MERGE (a)-[:BOUGHT {at:datetime()}]->(p)
                """, parameters("cid","CUST-42","sku","BOOK-1984"));
            return null;
        });
    }

    // Read
    try (var session = driver.session()) {
        var recos = session.executeRead(tx ->
            tx.run("""
                MATCH (me:Customer {id:$cid})-[:BOUGHT]->(:Product)
                      <-[:BOUGHT]-(:Customer)-[:BOUGHT]->(reco:Product)
                WHERE NOT (me)-[:BOUGHT]->(reco)
                RETURN reco.sku AS sku, count(*) AS score
                ORDER BY score DESC LIMIT 5
                """, parameters("cid","CUST-42"))
              .list(r -> r.get("sku").asString()));
        System.out.println(recos);
    }
}
// ALWAYS use $parameters, never string concatenation — it prevents Cypher
// injection AND lets Neo4j cache the query plan by query template.
```

### 5.10 Neo4j — batched bulk write to avoid giant transactions

```cypher
// Import 10M rows without one monster transaction (each batch commits separately)
LOAD CSV WITH HEADERS FROM 'file:///purchases.csv' AS row
CALL {
  WITH row
  MERGE (c:Customer {id: row.customerId})
  MERGE (p:Product  {sku: row.sku})
  MERGE (c)-[:BOUGHT]->(p)
} IN TRANSACTIONS OF 10000 ROWS;
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

**MongoDB**
- **Working set must fit in RAM (WiredTiger cache + OS cache).** If indexes + hot documents don't fit, you page from disk and latency collapses. Monitor cache eviction and page faults.
- **Index for your queries (ESR).** Verify with `explain("executionStats")`: want `IXSCAN`, `totalDocsExamined ≈ nReturned`, no in-memory `SORT`. A red flag: `COLLSCAN` or `totalDocsExamined >> nReturned`.
- **Covered queries** (all fields from index, project out `_id` if not needed) avoid document fetches.
- **Avoid large `$skip` pagination** — it scans+discards. Use range-based ("seek") pagination on an indexed sort key (`_id > lastSeen`).
- **Beware unbounded array growth** — `$push` without `$slice` can move documents and blow the 16 MB cap.
- **Batch writes** with `bulkWrite`/`insertMany`; round trips dominate at scale.

**Neo4j**
- **Page cache should hold the node+relationship stores** (ideally the whole graph). Properties can spill to disk more tolerably than topology.
- **Anchor on an indexed, selective start node.** Create indexes on the properties you `MATCH` by; verify the planner uses `NodeIndexSeek` (not `NodeByLabelScan` / `AllNodesScan`) via `PROFILE`.
- **Minimize db hits.** Return only needed properties; avoid fetching properties you discard.
- **Cap variable-length paths** (`[*..n]`); never use unbounded `[*]` on a connected graph — it can explode combinatorially.
- **Mitigate supernodes**: model around them (e.g., introduce intermediate nodes), rely on relationship-type grouping for dense nodes, or filter by relationship type/direction early.

### 6.2 Correctness & concurrency

- **MongoDB**: single-document writes are atomic — design invariants to live in one document. Use `findOneAndUpdate` for atomic read-modify-write. For optimistic concurrency, keep a `version` field and condition updates on it. Multi-document transactions exist but have a 60-second default lifetime limit and can abort on **write conflicts** (two transactions touch the same doc) — your code must retry.
- **Neo4j**: ACID with write locks; long transactions hold locks and risk **deadlocks** (two txns lock the same nodes in opposite order). Keep transactions small; use `executeWrite` managed transactions which auto-retry transient/deadlock errors.

### 6.3 Security

- **Auth & RBAC**: enable authentication (it is *not* on by default in some self-hosted setups — a classic breach cause). MongoDB has role-based access control with built-in and custom roles; Neo4j Enterprise has fine-grained role/property/graph-level security.
- **Never expose the DB to the public internet unauthenticated.** The "Mongo ransom" incidents (2017+) wiped tens of thousands of internet-exposed, auth-disabled instances. Bind to private networks; use TLS.
- **Injection**: MongoDB is vulnerable to **NoSQL injection** when you build query objects from unsanitized input (`{$where: userInput}`, or `{username: req.body.username}` where the body is `{"$ne": null}`). Use typed query builders and never pass user-controlled operators. In Cypher, always use **parameters** — never string-concatenate user input into queries.
- **Encryption**: at rest (WiredTiger encryption is Enterprise; or use disk/volume encryption) and in transit (TLS). MongoDB also offers **Client-Side Field-Level Encryption** and **Queryable Encryption** for sensitive fields.

### 6.4 Observability

- **MongoDB**: the **database profiler** (`db.setProfilingLevel(1, {slowms:100})`) logs slow ops to `system.profile`; `mongostat`/`mongotop`; `serverStatus` (cache, connections, oplog window); Atlas / Ops Manager dashboards; `db.currentOp()`. Watch: replication lag, oplog window, cache dirty %, page faults, scanned/returned ratio.
- **Neo4j**: `PROFILE` (db hits), `dbms.listQueries`, query log (`db.logs.query.*`), JMX/metrics endpoints (Prometheus), page cache hit ratio, transaction/lock stats. Watch: page cache hit ratio, heap GC pauses, long-running queries, store size vs page cache.

### 6.5 Cost

- Both are RAM-hungry — your bill is dominated by fitting the working set in memory. Graph DBs especially reward keeping topology resident.
- Neo4j's most powerful clustering/security/online-backup features are **Enterprise (commercial)**; the Community edition lacks clustering and some security. MongoDB's Community is more capable standalone, with Atlas (managed) and Enterprise tiers adding ops/security features.

### 6.6 Testing

- Use **Testcontainers** (`MongoDBContainer`, `Neo4jContainer`) to spin real instances in integration tests — far more faithful than in-memory fakes (which often diverge in query semantics).
- For MongoDB transactions, you need a **replica set** (transactions don't work on a standalone) — Testcontainers can start a single-node replica set.

### 6.7 Production hardening

- **MongoDB**: deploy 3-member replica sets (or P-S-A with an arbiter only if you must), use `w:"majority"` + `readConcern majority` for critical data, enable journaling, set sensible cache size, monitor oplog window vs secondary lag, plan the shard key *before* you need to shard.
- **Neo4j**: 3+ core servers (Raft) for HA, read replicas for read scale, regular backups, size page cache to the store, cap query depth/complexity, and put a query timeout in place.

### 6.8 Anti-patterns (memorize)

- **MongoDB**: unbounded embedded arrays; using MongoDB as a relational DB with lots of `$lookup` joins (you probably want Postgres); choosing a monotonically increasing shard key (hot shard); massive fan-out reads via `$skip`; one giant collection with wildly heterogeneous documents and no validation; treating `_id` as a sequential int.
- **Neo4j**: unbounded `[*]` traversals; ignoring supernodes; not indexing the anchor property (forcing `AllNodesScan`); string-concatenating queries (injection + plan-cache thrash); modeling tabular data with no real relationship traversal in a graph DB "because graphs are cool."

---

## 7. Advanced topics & deep internals

### 7.1 MongoDB schema design patterns (the canonical catalog)

- **Bucket pattern**: instead of one document per event (1B tiny docs) or one giant array, group N events into a "bucket" document (e.g., one doc per sensor per hour holding up to 200 readings). Balances doc count vs array size; ideal for time-series/IoT. (MongoDB 5.0+ also has native **time-series collections** that do this for you under the hood with columnar storage.)
- **Subset pattern**: embed the *hot subset* (latest 10 reviews) in the parent for the common read; keep the full set referenced in another collection. Cuts read size and working set.
- **Computed pattern**: precompute and store aggregates (e.g., `reviewCount`, `avgStars`) on the parent, updated on write, so reads don't aggregate.
- **Extended reference**: copy the few fields you need from a referenced doc into the referrer (e.g., copy `customerName` into the order) to avoid a `$lookup` on the hot path — accept controlled duplication.
- **Outlier pattern**: handle the rare giant document specially (the celebrity with 10M followers) so the common case stays small — store overflow in linked docs and flag the outlier.
- **Schema versioning**: add a `schemaVersion` field; migrate lazily on read or in background; lets you evolve shape without big-bang migrations.
- **Polymorphic pattern**: store related-but-different shapes in one collection with a `type` discriminator (e.g., different product categories).
- **Attribute pattern**: for documents with many sparse, queryable, similar fields, store them as an array of `{k, v}` so a single index `{ "attrs.k":1, "attrs.v":1 }` covers all of them.

### 7.2 MongoDB time-series & change streams

- **Time-series collections** (5.0+): purpose-built for measurements; internally bucketed + columnar, with automatic `_id`/time clustering and better compression.
- **Change streams**: a tailing API over the oplog that gives your app a real-time, resumable feed of inserts/updates/deletes (`watch()`), enabling event-driven architectures without polling. They emit `majority`-committed changes and carry a **resume token** so you can restart from where you left off.

### 7.3 MongoDB tuning knobs & lesser-known behavior

- **Plan cache**: query *shape* (filter structure + sort + projection) keys the cache. Wildly varying ad-hoc shapes can thrash it. You can pin plans with **index filters** / `planCacheSetFilter`.
- **`hint()`**: force a specific index when the planner picks poorly.
- **Read on secondaries** with `readPreference: secondaryPreferred` can offload reads but yields possibly stale data (eventual consistency).
- **`majority` read concern + causal consistency** (causally consistent sessions) give "read your own writes" across primary/secondary without full linearizability.
- **`$merge`** enables **on-demand materialized views** refreshed by a scheduled aggregation.
- **Collation**: locale-aware string comparison/sorting; set per-collection or per-operation; affects index usability.

### 7.4 Neo4j deep internals & tuning

- **Relationship store as doubly linked list** (re-stated, because it explains performance): traversal direction is symmetric because each relationship record sits in *both* the source and target node's chains. So `(a)-[:R]->(b)` can be walked from `a` or `b` cheaply.
- **Dense node threshold** (~50 rels) flips a node to **relationship group records** grouped by type+direction — the key reason a well-typed model survives moderate supernodes.
- **Cost-based optimizer & statistics**: stale stats → bad plans. After big writes, ensure indexes/statistics are current; use `PROFILE` to confirm `NodeIndexSeek` and reasonable estimated vs actual rows.
- **Eager operators**: certain Cypher operations (some aggregations, `DISTINCT`, parts of write queries) force an **Eager** operator that materializes all rows before continuing — a common cause of memory blowups in big writes. `PROFILE` shows `Eager`; restructure (e.g., batch with `CALL { } IN TRANSACTIONS`) to avoid it.
- **Graph Data Science (GDS)**: for *global* graph algorithms (PageRank, Louvain community detection, betweenness centrality, node2vec embeddings, weighted shortest paths at scale), GDS projects the graph into an **in-memory columnar graph** and runs parallel algorithms — different beast from transactional Cypher traversal. Use GDS for analytics, Cypher for OLTP-style pattern matching.
- **Vector indexes + GraphRAG** (5.13+): store embeddings on nodes, do approximate nearest-neighbor search, then traverse relationships for context — pairing semantic similarity with explicit structure for retrieval-augmented generation.

### 7.5 `$graphLookup` vs a real graph DB

MongoDB's `$graphLookup` does recursive traversal within one collection (`maxDepth`, `restrictSearchWithMatch`). It's fine for **shallow** hierarchies (org charts, category trees, ≤ a few hops). It is *not* index-free adjacency — it's repeated index lookups, so it degrades with depth/breadth far faster than Neo4j. Use it for occasional, bounded recursion; reach for a graph DB when traversal is the core, deep, frequent workload.

---

## 8. Tradeoffs & decision frameworks

### 8.1 The big three compared

| Dimension | Relational (Postgres) | Document (MongoDB) | Graph (Neo4j) |
|---|---|---|---|
| Core unit | Normalized rows in tables | Self-contained JSON aggregate | Nodes + typed relationships |
| Relationships | Foreign keys, joins at read | Embed or `$lookup` | First-class, stored as pointers |
| Deep-join / traversal cost | Grows with table size per hop | Worse (lookups per hop) | `O(touched)`, ~independent of size |
| Schema | Rigid, schema-on-write | Flexible, schema-on-read (+ optional validation) | Flexible labels/properties |
| Transactions | Strong ACID, multi-row default | Single-doc atomic; multi-doc opt-in | Full ACID |
| Ad-hoc querying across dimensions | Excellent (SQL + planner) | Good but join-limited | Pattern-matching strong; tabular analytics weaker |
| Horizontal scale | Harder (sharding bolt-ons) | Built-in sharding | Read replicas; write scaling harder |
| Best at | General OLTP, reporting, constraints | Aggregate read/write, flexible/evolving schema | Connected data, deep traversal |

### 8.2 Document vs relational — use when / avoid when

- **Use document when**: a dominant access pattern loads/saves one aggregate by id; schema evolves fast; you want built-in horizontal scale for simple access; data is naturally hierarchical (catalogs, profiles, CMS, events).
- **Avoid document when**: you need many ad-hoc joins across entities; strong multi-entity transactional invariants are central; reporting/BI over many dimensions dominates; relationships are deep and traversal-heavy (use graph).

### 8.3 Graph vs relational — use when / avoid when

- **Use graph when**: relationships are the query (paths, rings, recommendations, networks); traversal depth is variable/deep; the same data is explored along its connections repeatedly.
- **Avoid graph when**: your workload is bulk tabular scans/aggregations (OLAP), or simple key lookups with no traversal; you need massive write throughput with simple access (a document/relational/KV store fits better). Graphs are not the best at "sum revenue by region for last quarter."

### 8.4 Worked decision examples

| Scenario | Pick | Why |
|---|---|---|
| Product catalog with nested variants, fast evolving | Document | Aggregate read; flexible schema |
| Bank ledger with strict multi-account invariants & reporting | Relational | ACID, constraints, ad-hoc SQL |
| Fraud ring / shared-attribute detection | Graph | Subgraph patterns, deep traversal |
| Social feed friends-of-friends | Graph | 2–3 hop traversal at scale |
| IoT sensor readings, high write volume, time queries | Document (time-series) or a TSDB | Bucketing/columnar; write scale |
| Recommendation engine | Graph (often + GDS) | Collaborative filtering = traversal |
| User session/cache with TTL | Document or KV | Simple aggregate + expiry |
| Reporting warehouse / BI | Relational/columnar (OLAP) | Scans & aggregations |
| Org chart / category tree (shallow) | Relational w/ recursive CTE or document `$graphLookup` | Bounded recursion |
| Deep dependency/impact analysis | Graph | Unbounded-depth reachability |

> **Polyglot persistence** is normal: use the right store per workload (e.g., Postgres for ledger, Mongo for the catalog, Neo4j for recommendations), and sync via change streams/CDC. The cost is operational complexity and consistency across stores.

---

## 9. Failure modes & debugging

### 9.1 MongoDB failures and how to diagnose

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Sudden latency cliff | Working set no longer fits cache; page faults | `serverStatus.wiredTiger.cache`, OS page faults, `mongostat` | Add RAM, shrink working set, index better, archive cold data |
| Query slow despite "having an index" | Wrong index order (ESR), in-memory SORT, COLLSCAN | `explain("executionStats")` (look for `SORT`, `COLLSCAN`, `totalDocsExamined`) | Build ESR-correct compound index; `hint()` |
| Writes failing/blocking | Write conflicts in transactions; lock contention | Driver `WriteConflict` errors; `db.currentOp()` | Retry txns; shorten txns; move invariant into one doc |
| Replication lag / stale secondary reads | Secondary can't keep up; small oplog window | `rs.printSecondaryReplicationInfo()`, oplog window | Bigger oplog, faster secondaries, reduce write load |
| Failover data loss | `w:1` writes lost on primary crash | Post-incident analysis | Use `w:"majority"` |
| Hot shard / uneven load | Bad shard key (monotonic/low cardinality) | `sh.status()`, per-shard `mongostat` | Reshard (5.0+) with a better/hashed key |
| Scatter-gather everywhere | Queries omit shard key | `explain` on `mongos` (SHARD_MERGE) | Include shard key; redesign key |
| Document too large | 16 MB cap from unbounded array | `BSONObjectTooLarge` error | Bucket/subset pattern; reference |
| `$skip` pagination slowing with depth | Skip scans+discards | `explain` shows high docsExamined | Range/seek pagination |

Real-world flavor: the 2017 wave of **MongoDB ransom attacks** hit tens of thousands of internet-exposed instances with authentication disabled — attackers deleted data and demanded payment. Lesson: never run unauthenticated, never expose to the public internet. Separately, teams routinely get burned by choosing a **monotonically increasing shard key** (timestamp or ObjectId), funneling all new writes to the last shard (a hot shard) — the fix is a hashed or compound high-cardinality key chosen *before* sharding.

### 9.2 Neo4j failures and how to diagnose

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Query never returns / OOM | Unbounded `[*]` or huge variable-length expansion | `PROFILE` (rows/db hits explode), query log | Cap depth `[*..n]`; add filters; pick selective anchor |
| Slow query | `AllNodesScan`/`NodeByLabelScan` instead of index seek | `PROFILE`/`EXPLAIN` (look for `NodeIndexSeek`) | Create index on anchor property |
| Memory spike on writes | `Eager` operator materializing all rows | `PROFILE` shows `Eager` | Restructure; batch with `CALL { } IN TRANSACTIONS` |
| Supernode hot spot | Dense node with millions of edges | Degree checks; slow expands through it | Model around it; rely on type/direction filtering; intermediate nodes |
| Page cache thrash, disk-bound traversal | Store > page cache | Page cache hit ratio metric | Increase `server.memory.pagecache.size`; add RAM |
| Deadlocks on concurrent writes | Txns lock nodes in opposite order | Deadlock exceptions in logs | Use managed `executeWrite` retries; consistent lock ordering |
| Plan-cache misses / slow compile | String-concatenated (non-parameterized) queries | Query log shows many distinct queries | Use parameters |
| Stale plan after big load | Outdated statistics | `PROFILE` estimated vs actual rows diverge | Refresh stats / rebuild indexes |

### 9.3 The universal debugging loop

1. Reproduce with the exact query/shape.
2. `explain("executionStats")` (Mongo) / `PROFILE` (Neo4j) — read the *actual* work done, not your assumptions.
3. Identify the expensive operator (COLLSCAN/SORT/SHARD_MERGE; AllNodesScan/VarLengthExpand/Eager).
4. Fix the structural cause (index, model, shard key, depth cap), not the symptom.
5. Re-profile; confirm `docsExamined ≈ returned` / minimal db hits.

---

## 10. Interview drill

**Q1. What is index-free adjacency and why does it make graph traversal fast?**
Model answer: Each node stores direct physical pointers to its adjacent relationships (and they to their nodes), so finding neighbors is pointer-chasing — `O(degree)` — instead of an index lookup whose cost grows with total data size. A deep traversal therefore scales with how much you touch, not how big the database is.
- Follow-up: *How does Neo4j implement it on disk?* Fixed-size record stores; offset = `id × recordSize` (O(1)); relationship records are doubly linked lists threaded through both endpoints' chains.
- Follow-up: *Where does it break down?* Supernodes/dense nodes; mitigated by relationship group records grouping by type+direction and by modeling around hubs.
- Follow-up: *Why is this worse than relational only at depth?* At one hop both are cheap; relational pays a `log N` index lookup per hop that compounds with table size, while graph stays `O(touched)`.

**Q2. Embedding vs referencing in MongoDB — how do you decide?**
Model answer: Embed data read together and bounded (one-to-few); reference unbounded/large or many-to-many relationships, or when the child is queried independently. Respect the 16 MB document cap and avoid unbounded array growth.
- Follow-up: *Give a case for intentional duplication.* Copy product name/price into an order line so it's a historical snapshot (extended reference pattern).
- Follow-up: *How do you serve "parent + latest 5 children" cheaply?* Subset pattern: embed the hot 5, reference the rest.

**Q3. Walk a write through MongoDB with `w:"majority"`.**
Model answer: Parse/validate → WiredTiger creates an MVCC version (doc-level lock) → append to journal → record in oplog → wait until a majority of replica-set members have applied it → ack. Journal flushes ≤100 ms; checkpoints ≤60 s; crash recovery = last checkpoint + journal replay.
- Follow-up: *What does `w:1` risk?* Data loss if the primary crashes before replicating.
- Follow-up: *What's `readConcern: majority` for?* Read only data that won't be rolled back.

**Q4. Explain MongoDB sharding and how to pick a shard key.**
Model answer: Partition a collection across shards (each a replica set) via a shard key; `mongos` routes; config servers hold metadata; the balancer evens chunks. Pick a key that's high-cardinality, spreads writes (avoid monotonic), and appears in common queries (for targeted, not scatter-gather, routing).
- Follow-up: *Ranged vs hashed?* Ranged keeps range-query locality but risks hot shards on monotonic keys; hashed spreads writes but kills range locality.
- Follow-up: *Can you change it?* Resharding exists (5.0+) but is expensive; design it up front.

**Q5. (Senior-signal) You're designing a recommendation engine. Document, graph, or relational — justify.**
Model answer: Collaborative filtering is fundamentally traversal (`user → items → other users → their items`), variable-depth and relationship-centric → graph (often Neo4j + GDS for global algorithms). Relational self-joins explode at depth; document `$lookup` per hop is worse. But if recommendations are precomputed offline and only served by id, a document/KV store for the precomputed result is fine — so the answer depends on whether traversal is online or batch.
- Follow-up: *When would relational still win?* If "recommendations" reduce to a single GROUP BY over a fact table (1-hop), Postgres is simpler and cheaper.
- Follow-up: *How do you scale graph reads?* Read replicas; keep topology in page cache; precompute heavy global algorithms with GDS.

**Q6. (Senior-signal) When is MongoDB the wrong choice and you should use Postgres?**
Model answer: When you need many ad-hoc joins across normalized entities, strong multi-entity transactional invariants, rich constraints, or heavy multi-dimensional reporting — Postgres's planner, joins, constraints, and SQL ecosystem win. MongoDB shines when there's a dominant aggregate access pattern and flexible/evolving schema; using it as a relational DB with pervasive `$lookup` is an anti-pattern.
- Follow-up: *Doesn't Postgres also do JSONB?* Yes — for mostly-relational data with some flexible fields, Postgres JSONB often beats adopting a second datastore.
- Follow-up: *What about scale?* MongoDB's built-in sharding is easier than scaling Postgres horizontally, so write/data volume can tip the choice.

**Q7. How do MongoDB and Neo4j handle ACID, and what changes when you cluster them?**
Model answer: MongoDB: single-doc writes always atomic; multi-doc ACID transactions added in 4.0 (4.2 sharded), tuned by write/read concern; clustering is a replica set with majority commit (Raft-inspired elections). Neo4j: full ACID single-instance with write locks; clustering uses Raft to replicate the tx log to a majority before commit, with eventually-consistent read replicas.
- Follow-up: *What's the cost of MongoDB multi-doc transactions?* Latency, lock contention, 60 s default lifetime, write-conflict aborts requiring retry.
- Follow-up: *Read-your-own-writes without full linearizability?* Causally consistent sessions in MongoDB.

**Q8. What is the aggregation pipeline and how does the optimizer help?**
Model answer: An ordered set of stages transforming a document stream. The optimizer pushes `$match`/`$project` early (predicate/projection pushdown) to use indexes and shrink the stream, coalesces `$sort`+`$limit`, and uses an index only for leading stages until the first transforming stage (`$group`/`$unwind`). Each stage caps at 100 MB unless `allowDiskUse`.
- Follow-up: *Index-using `$sort`?* Only if the sort field leads or follows an equality match per ESR before any transforming stage.
- Follow-up: *How to join?* `$lookup` (left-outer); but design to avoid it on hot paths.

**Q9. (Senior-signal) Justify embedding an unbounded comment list vs not, with the consequences.**
Model answer: Don't embed unbounded comments: risks the 16 MB cap, document moves/rewrites on growth, and forces loading data you usually don't need. Reference comments in their own collection (or bucket them), embed only a hot subset, and store a computed `commentCount`. The tradeoff is an extra read for full comments vs predictable document size and working set.
- Follow-up: *What if 99% of reads need only the latest 3?* Subset pattern + computed count.
- Follow-up: *How keep count consistent?* Atomic `$inc` on the parent in the same write that adds a comment, or reconcile via change streams.

**Q10. Diagnose: "I added an index but my query is still slow."**
Model answer: Run `explain("executionStats")`. Common findings: it's still a `COLLSCAN` (filter not index-aligned), or `IXSCAN` followed by an in-memory `SORT` (compound index doesn't satisfy ESR), or `totalDocsExamined >> nReturned` (low selectivity / not covered). Fix by building an ESR-ordered compound index, making the query covered, or `hint()`-ing.
- Follow-up: *Why might the planner ignore your index?* Plan cache picked another plan, low selectivity, or the index isn't usable due to collation/multikey constraints.
- Follow-up: *Covered query requirements?* All queried + returned fields in the index, and you exclude `_id` if it's not in the index.

**Q11. Why are unbounded `[*]` Cypher traversals dangerous, and how do you bound them?**
Model answer: On a connected graph, unbounded variable-length expansion can visit an exponential number of paths and exhaust memory/time. Bound depth (`[*..n]`), pin a relationship type/direction, anchor on a selective indexed node, and use `shortestPath`/GDS for path problems.
- Follow-up: *How do you see the blow-up?* `PROFILE` — rows and db hits explode at the `VarLengthExpand` operator.
- Follow-up: *Alternative for global path/centrality?* GDS algorithms on a projected in-memory graph.

**Q12. (Senior-signal) Design polyglot persistence for an e-commerce platform.**
Model answer: Catalog & user profiles in MongoDB (aggregate reads, flexible schema); orders/payments/ledger in Postgres (ACID, constraints, reporting); recommendations/fraud in Neo4j (traversal). Sync via change streams/CDC into the graph; keep each store's source-of-truth clear; accept eventual consistency across stores and the operational overhead. Justify each by its dominant access pattern.
- Follow-up: *How keep the graph fresh?* MongoDB change streams / Postgres logical decoding → stream into Neo4j.
- Follow-up: *Biggest risk?* Cross-store consistency and operational complexity; mitigate with idempotent consumers and clear ownership.

---

## 11. Glossary

- **ACID**: Atomicity, Consistency, Isolation, Durability — the transactional correctness contract.
- **Aggregate**: a parent + its nested children treated as one unit of change (DDD term); the natural document.
- **Aggregation pipeline**: MongoDB's staged data-processing framework (filter/group/join/reshape).
- **Anchor (Cypher)**: the starting node(s) a query expands from, chosen for selectivity.
- **Arbiter**: a vote-only MongoDB replica-set member with no data, used to break election ties.
- **BSON**: Binary JSON — MongoDB's typed, traversable binary document encoding.
- **Bucket pattern**: grouping many small records into one document to balance count vs array size.
- **CAP theorem**: under a network partition, choose Consistency or Availability.
- **Capped collection**: fixed-size, insertion-ordered collection (e.g., the oplog).
- **Change stream**: resumable real-time feed of MongoDB data changes (over the oplog).
- **Checkpoint**: a consistent on-disk flush; recovery replays the journal after it.
- **Chunk**: a contiguous shard-key range; the unit the balancer migrates.
- **COLLSCAN**: full collection scan (no index used).
- **Compound index**: index on multiple fields; order matters (ESR).
- **Covered query**: answered entirely from an index, no document fetch.
- **Cursor**: a server-side pointer streaming query results in batches.
- **CBO (cost-based optimizer)**: chooses query plans using statistics.
- **Cypher**: Neo4j's declarative graph query language (now standardized as GQL).
- **db hits**: Neo4j's unit of store access work; minimized for performance.
- **Degree**: number of relationships on a node; in/out-degree by direction.
- **Dense node / supernode**: a very high-degree node; a hot-spot risk.
- **Denormalization**: deliberately duplicating data (e.g., embedding) for read locality.
- **Document**: a self-contained JSON/BSON record; the unit of a document DB.
- **Eager operator**: a Cypher operator that materializes all rows before continuing (memory risk).
- **Edge / relationship**: a directed, typed connection between two nodes.
- **Embedding**: nesting related data inside a parent document.
- **ESR rule**: order compound-index fields Equality → Sort → Range.
- **GDS**: Neo4j Graph Data Science library (global graph algorithms on a projected graph).
- **GQL**: ISO standard graph query language (2024); Cypher converges on it.
- **`$graphLookup`**: MongoDB recursive lookup within a collection (bounded traversal).
- **Index-free adjacency**: nodes store direct pointers to neighbors; neighbor lookup is O(degree).
- **IXSCAN**: index scan.
- **Join**: combining rows/data by matching keys.
- **Journal (WAL)**: write-ahead log for durability before checkpointing.
- **Label (Neo4j)**: a tag grouping nodes (e.g., `:Person`).
- **`$lookup`**: MongoDB left-outer join stage.
- **Multikey index**: index over array elements (one entry per element).
- **MVCC**: multi-version concurrency control; readers see snapshots without blocking writers.
- **Node / vertex**: an entity in a graph.
- **Normalization**: storing each fact once (relational design principle).
- **OLTP / OLAP**: transactional (many small ops) vs analytical (few large scans) workloads.
- **Oplog**: MongoDB's capped operations log driving replication.
- **Polyglot persistence**: using multiple datastores, each for its best-fit workload.
- **Page cache (Neo4j)**: off-heap cache of store files; sized to hold the working set.
- **Plan cache**: cached query plan keyed by query shape.
- **Property**: a key/value on a node or relationship.
- **Property graph**: graph model of labeled nodes + typed relationships, both with properties.
- **Raft**: majority-vote consensus algorithm for leader election + log replication.
- **Read concern / write concern**: MongoDB consistency/durability dials.
- **Replica set**: MongoDB HA group (primary + secondaries) sharing data.
- **Resharding**: changing a shard key (expensive; 5.0+).
- **Schema-on-read / -on-write**: validate structure at read vs write time.
- **Selectivity**: fraction of rows a predicate keeps; high = good anchor.
- **Shard / sharding**: a data partition / partitioning a collection across servers.
- **Shard key**: the field(s) used to partition sharded data.
- **Subset pattern**: embed a hot subset, reference the rest.
- **Supernode**: see dense node.
- **TTL index**: auto-expires documents after a time.
- **WiredTiger**: MongoDB's default MVCC storage engine.
- **16 MB limit**: max BSON document size in MongoDB.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Document (MongoDB)**
- Unit: BSON document (≤ **16 MB**); collection ≈ table. PK = `_id` (ObjectId: 12 bytes, time-prefixed).
- Model rule: **read together → store together**; embed one-to-few, reference one-to-many/unbounded; duplication OK if rarely changes.
- Engine: **WiredTiger**, MVCC, doc-level concurrency. Cache default = `max(50% (RAM−1GB), 256MB)`. Journal ≤100 ms, checkpoint ≤60 s. Snappy compression.
- Indexes: ESR rule (**Equality → Sort → Range**). Want `IXSCAN`, `docsExamined ≈ returned`, no in-memory SORT.
- Consistency: `w:"majority"` + `readConcern majority` for safe data; transactions are opt-in (4.0/4.2), costly, retry on conflict.
- Scale: sharding via shard key (high cardinality, spreads writes, in common queries; avoid monotonic → hot shard). Targeted vs scatter-gather.
- Aggregation stage cap **100 MB** (`allowDiskUse`).

**Graph (Neo4j)**
- Property graph: nodes + labels + typed, directed relationships + properties on both.
- Superpower: **index-free adjacency** → neighbor lookup `O(degree)`, traversal `O(touched)`, independent of total size. Relationship records are doubly linked lists; fixed-size records; dense nodes (~50 rels) → relationship groups.
- Indexes find the **anchor**, not the path. Want `NodeIndexSeek`, not `AllNodesScan`. Minimize **db hits**.
- ACID; clustering via **Raft**; read replicas eventually consistent. Page cache = top perf knob.
- Cypher: ASCII-art patterns; always parameterize; **cap variable-length** `[*..n]`, never `[*]`. GDS for global algorithms.

**Decision rule**: dominant aggregate access + flexible schema → **document**; relationships-are-the-query + deep traversal → **graph**; ad-hoc joins + strong constraints + reporting → **relational**. Polyglot is normal.

### 12.2 Self-test (no answers)

1. Sketch the on-disk relationship record layout in Neo4j and explain how it yields O(degree) neighbor enumeration and symmetric traversal direction.
2. You have a `posts` collection where viral posts accumulate millions of comments. Design the schema (collections, indexes, patterns) and justify against the 16 MB limit and working-set size.
3. Given a query that filters on `status` (equality), sorts by `createdAt`, and ranges on `price`, write the optimal compound index and explain why each field is in that position.
4. Compare the cost of a 4-hop "friends-of-friends-of-friends-of-friends" query in Postgres vs Neo4j as the dataset grows from 1M to 1B rows/nodes, and explain the asymptotics.
5. You must pick a shard key for a high-write event collection that's usually queried by `tenantId` and time range. Propose a key, explain hot-shard and scatter-gather implications, and what you'd lose.
6. Walk through diagnosing a Neo4j query that runs for minutes and OOMs — which command, which operators you'd look for, and three structural fixes.
7. Justify a polyglot architecture for a fraud-detection + transactions + catalog system, naming the store per workload and how you'd keep them in sync.

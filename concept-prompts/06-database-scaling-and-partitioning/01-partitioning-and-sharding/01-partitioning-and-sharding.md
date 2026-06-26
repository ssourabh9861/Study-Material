# Partitioning & Sharding

> A definitive engineering-handbook chapter on splitting data across boundaries — within a single database (partitioning) and across many independent databases (sharding). Written for a senior JVM/Java backend developer who wants to master this from first principles to deep internals.

---

## 1. Overview & where it fits

### 1.1 What it is

**Partitioning** is the general act of splitting one logical dataset into multiple physical pieces. **Sharding** is the specific case of partitioning where those pieces live on *separate database servers* (separate machines / processes / storage), so that no single node holds the whole dataset.

Two words you must keep straight from the very beginning:

- **Partition** (also called a *shard* when distributed, or a *region/tablet/vnode* in specific systems): one physical chunk of the data.
- **Partition key** / **shard key**: the column (or set of columns) whose value decides which partition a row lands in. This is the single most consequential design decision in the entire topic.

A useful mental distinction:

| Term | Scope | Lives on | Example |
|---|---|---|---|
| **Partitioning** | One database server | Same machine, often same instance | PostgreSQL declarative partitioning of a `orders` table by month |
| **Sharding** | Many database servers | Different machines | User data split: users A–M on `db1`, N–Z on `db2` |

> **Beginner note — "logical vs physical":** A *logical* table is the table as your application thinks about it ("the `orders` table"). The *physical* representation is how it is actually stored on disk and across machines. Partitioning/sharding is the art of making one logical thing be many physical things while preserving (most of) the illusion that it is one thing.

### 1.2 The problem it solves

A single database server has hard ceilings:

1. **Storage**: a single disk/volume is finite (a few TB to ~64 TB on cloud block storage). A 50 TB table simply will not fit.
2. **Write throughput**: a single primary node can only commit so many writes per second because all writes funnel through one write-ahead log (WAL) and one set of locks. Vertical scaling (bigger machine) hits diminishing returns and a price cliff.
3. **Working-set memory**: queries are fast when the *hot* data fits in RAM (the buffer pool/page cache). When the working set exceeds RAM, you fall to disk and latency spikes.
4. **Index size**: a B-tree index on a billion rows is deep and large; maintaining it on writes becomes expensive.
5. **Maintenance operations**: `VACUUM`, backups, schema migrations, and index rebuilds all scale with table size and eventually take days.
6. **Blast radius**: one big database is one big single point of failure.

> **Beginner note — "WAL / write-ahead log":** Before a database changes a data page, it first appends a record of the change to a sequential log on disk. This makes writes durable and crash-recoverable. Because it is a single sequential structure, it is also a throughput bottleneck on a single node.
>
> **Beginner note — "buffer pool / page cache":** The region of RAM where the database keeps recently used data pages so it doesn't have to read disk every time. Hit-the-cache = microseconds; miss-and-read-disk = milliseconds.

Partitioning addresses (3), (4), (5) within one server. Sharding additionally addresses (1), (2), and (6) by spreading load across N machines, giving roughly N× the storage, write throughput, and memory.

### 1.3 When you reach for it

Reach for **partitioning (single node)** when:
- A table is large enough that maintenance/queries hurt, but the *total* load still fits one machine.
- You have a natural "time" dimension and want cheap data lifecycle management (drop old partitions instead of `DELETE` millions of rows).
- You want **partition pruning** — the planner skips partitions irrelevant to a query.

Reach for **sharding (multi node)** when:
- Write throughput or dataset size genuinely exceeds what one (reasonably-sized) primary can do.
- Read replicas + caching + vertical scaling are already exhausted.
- You can tolerate the large complexity tax sharding imposes (cross-shard queries, distributed transactions, rebalancing, operational surface area).

> **The order of escalation matters.** Sharding is the *last* lever, not the first. The usual ladder is: optimize queries/indexes → add caching → add read replicas → vertical scale → functional decomposition (split tables to different DBs) → **shard**. Sharding too early is one of the most common and expensive engineering mistakes.

### 1.4 One-paragraph mental model

> Picture your dataset as a giant deck of cards you can no longer hold in one hand. **Partitioning** is dealing the deck into labeled piles by some rule (suit, rank, date). If all the piles stay on your one table, that's local partitioning — handy for organization and for throwing away whole piles. **Sharding** is handing each pile to a *different person at a different table*; now you have N× the hands, but to answer "how many red cards total?" you must ask everyone and add up the answers, and if one person's pile grows huge you have a "hot shard." The rule you choose for dealing — the **shard key** — determines everything: how evenly work spreads, which questions are cheap (single pile) versus expensive (ask everyone), and how painful it is to re-deal later.

---

## 2. Foundations from first principles

### 2.1 The two axes of splitting: vertical vs horizontal

There are exactly two directions you can cut a table:

**Vertical partitioning** — split by *columns*. You take a wide table and put some columns in one place, other columns in another, joined by a shared key.

```
Original:  users(id, name, email, bio_text, avatar_blob, last_login, settings_json)

Vertically split:
  users_core(id, name, email, last_login)        -- hot, small, queried constantly
  users_profile(id, bio_text, avatar_blob, settings_json)  -- cold, large, rarely read
```

Why: keep the hot, frequently-scanned columns small so more rows fit per page and in cache; isolate large BLOB/TEXT columns that would otherwise bloat every scan.

**Horizontal partitioning** — split by *rows*. Every partition has the *same* columns (same schema) but a disjoint subset of rows.

```
Original:  orders(id, user_id, created_at, amount, ...)   -- 2 billion rows

Horizontally split by created_at:
  orders_2024_01(...)   -- Jan rows
  orders_2024_02(...)   -- Feb rows
  ...
```

> **Key insight:** **Sharding is always horizontal partitioning** (same schema, disjoint rows) *distributed across servers*. When people say "sharding" they essentially never mean vertical. Vertical splitting across servers is usually called **functional decomposition** (see §2.3).

| | Vertical | Horizontal |
|---|---|---|
| Split by | Columns | Rows |
| Each piece has | Subset of columns, all rows | All columns, subset of rows |
| Joined back via | Shared primary key | (No join needed; union the rows) |
| Typical motive | Isolate hot/cold columns, large blobs | Scale row count / write volume |
| Becomes "sharding" when | (rarely distributed) | distributed across servers |

### 2.2 Foundational terms (define-as-you-go)

- **Shard / partition**: one physical subset.
- **Shard key / partition key**: the value used to assign a row to a shard.
- **Partition function**: the deterministic function `f(key) → shard_id`. Could be a range lookup, a hash modulo, or a directory lookup.
- **Cardinality**: how many *distinct* values a column has. High cardinality (e.g., `user_id` with millions of values) is generally good for a shard key; low cardinality (e.g., `country` with ~200 values, or `status` with 3) is bad — it limits how finely you can split.
- **Skew**: uneven distribution of data or load across shards. The enemy.
- **Fan-out / scatter-gather**: a query that must touch many/all shards, collect partial results, and merge them. Expensive and latency-bound by the slowest shard.
- **Routing**: the logic that, given a query, figures out which shard(s) to send it to.
- **Rebalancing / resharding**: moving data between shards to fix skew or add capacity.
- **Replication** (adjacent, not the same!): keeping *copies* of the *same* data on multiple nodes for availability/read-scaling. **Sharding splits data; replication copies it.** Real systems do both: each shard is itself replicated.

> **Beginner note — sharding vs replication, the one-liner:** Replication = "everyone has the whole book" (redundancy, read scaling). Sharding = "each person has different chapters" (capacity, write scaling). Production = "each chapter has 3 copies held by 3 people" (both).

### 2.3 Functional decomposition vs sharding

**Functional decomposition** (a.k.a. functional/service partitioning) splits data by *feature/domain* onto different databases:

```
users_db        -> the Users service
orders_db       -> the Orders service
inventory_db    -> the Inventory service
analytics_db    -> the Analytics service
```

This is the natural database boundary of a microservices architecture. It scales because each domain's load goes to its own machine. But it does **not** help when *one single domain* (say, `orders`) outgrows a machine — you still have one `orders_db`. At that point you shard the `orders_db` itself.

| | Functional decomposition | Sharding |
|---|---|---|
| Split by | Business domain / feature | Value of a key within one dataset |
| Each DB holds | Different *tables* | Same table, different *rows* |
| Scales | Number of features | Size of a single dataset |
| Cross-cut cost | Cross-service joins/transactions | Cross-shard joins/transactions |
| Usually done | First (cheap, aligns with services) | Later (when one domain is too big) |

The progression is almost always: **monolith DB → functional decomposition → shard the biggest decomposed piece.**

### 2.4 What "one logical table, many physical shards" actually requires

To present N shards as one table, *something* must do four jobs:

1. **Route**: given a query, decide which shard(s).
2. **Execute**: send the (possibly rewritten) query to those shards.
3. **Merge**: combine results (union, re-sort, re-aggregate, re-limit).
4. **Maintain the map**: track which key ranges/hashes live on which shard, and update it during rebalancing.

That "something" can be: your application code, a middleware proxy (Vitess, ProxySQL), or a natively-sharded database (Cassandra, CockroachDB, MongoDB, Spanner). §2.5 and §7 dig into where this logic lives.

### 2.5 The three places sharding logic can live

1. **Application-level sharding** — your Java code computes the shard, picks the right DataSource, and runs the query. Maximum control, maximum burden. Cross-shard logic is yours to write.
2. **Middleware/proxy sharding** — a process between app and DBs that speaks the DB wire protocol and transparently routes/merges. Examples: **Vitess** (MySQL), **Citus** (PostgreSQL extension, partly proxy-like), **ProxySQL**, **ShardingSphere**. Your app thinks it's talking to one DB.
3. **Native/built-in sharding** — the database itself is distributed and shards automatically: **Cassandra**, **MongoDB** (sharded clusters), **CockroachDB**, **YugabyteDB**, **Google Spanner**, **DynamoDB**, **Vitess** (arguably native for MySQL).

These are covered in depth in §7.5.

---

## 3. How it works internally

This is the heart of the document. We trace, step by step, what happens under the hood for each strategy, for the read/write path, and for rebalancing.

### 3.1 The partition function — the core machinery

Every sharded system reduces to: `shard_id = f(shard_key)`. The three families of `f` are **range**, **hash**, and **directory/lookup**. Geo is a specialization (usually directory- or range-based on a location attribute).

#### 3.1.1 Range partitioning

`f` maps contiguous key ranges to shards:

```
key in [-inf, 1000)      -> shard A
key in [1000, 2000)      -> shard B
key in [2000, +inf)      -> shard C
```

Internally the router holds a sorted list of *split points* (boundaries) and does a binary search on the key to find the owning shard.

- **Read path (point lookup):** binary-search boundaries → one shard → execute. O(log S) routing where S = number of shards.
- **Read path (range query `key BETWEEN x AND y`):** find the shard(s) covering [x, y]. If the range is narrow, it's one or few shards — **this is range partitioning's superpower.** Range scans are cheap and stay local.
- **Write path:** binary-search → route → write. New keys at the high end (e.g., monotonically increasing `id` or `created_at`) all land on the *last* shard → **hot tail / append hotspot** (a major failure mode, §9).
- **State:** boundaries are stored in a metadata service (Vitess topology, CockroachDB's range descriptors, HBase's META table, MongoDB's config servers).

#### 3.1.2 Hash partitioning

`f` hashes the key and reduces it:

```
shard_id = hash(key) % S          -- naive "modulo" sharding
```

The hash (e.g., MurmurHash, CRC32, xxHash, or a cryptographic hash truncated) scatters keys uniformly, so data and load spread evenly even for sequential keys.

- **Read path (point lookup):** `hash(key) % S` → one shard. O(1) routing. Excellent.
- **Read path (range query):** keys adjacent in value are scattered to *random* shards → a range query becomes a **scatter-gather across all shards**. This is hash partitioning's fatal weakness for range/sorted access.
- **Write path:** uniform spread, no append hotspot for sequential keys. 
- **The modulo trap:** `% S` means changing S (adding a shard) remaps *almost every key* → massive data movement. The fix is **consistent hashing** (§3.1.4).

> **Beginner note — "hash function":** A function that turns any input into a fixed-size pseudo-random-looking number, deterministically (same input → same output) and with good *avalanche* (small input change → big output change). It does **not** preserve order, which is exactly why hash sharding kills range queries.

#### 3.1.3 Directory / lookup partitioning

`f` is a literal lookup table (often itself in a database or coordination service):

```
lookup_table:
   shard_key       -> shard_id
   "user_42"       -> shard C
   "user_99"       -> shard A
   ...
```

Or, more scalably, a lookup keyed by *buckets*: hash the key into one of K buckets (K ≫ S), then a small directory maps bucket → shard.

- **Read/write path:** one extra hop to the directory (often cached), then to the shard.
- **Superpower:** total flexibility. You can move any key/bucket to any shard by editing the directory — no rehashing, arbitrary rebalancing, even per-tenant placement ("this whale customer gets its own shard").
- **Weakness:** the directory is an extra component, a potential bottleneck, and (if not replicated) a single point of failure. It must be highly available and consistent.

#### 3.1.4 Consistent hashing (the fix for the modulo trap)

Instead of `hash(key) % S`, map both keys *and* shards onto a circular hash space `[0, 2^k)`. A key is owned by the next shard clockwise from `hash(key)`.

```
Hash ring (positions 0..2^32):
   shard positions:   ...A........B..............C......(wrap)
   key K hashes to position just before B  -> owned by B
```

- **Adding/removing a shard** only re-homes the keys between the new/removed node and its predecessor — roughly **1/S of the keys move**, not all of them.
- **Virtual nodes (vnodes):** each physical node is placed at *many* positions on the ring (e.g., 256 vnodes per node in Cassandra). This (a) smooths distribution (averages out clumping) and (b) makes rebalancing spread the moved data across many sources/targets in parallel.

> **Beginner note — why vnodes:** With one position per node, random placement leaves big and small arcs → skew. With 256 positions per node, the law of large numbers evens out the arc sizes, so each node owns ≈ equal share, and when a node joins it takes a little from *every* other node rather than dumping the whole burden on one neighbor.

Cassandra, DynamoDB, Riak, and ScyllaDB use consistent hashing with vnodes. CockroachDB and Spanner use *range* sharding with automatic splitting instead.

### 3.2 End-to-end write path (sharded MySQL via Vitess, as a concrete trace)

> **Beginner note — "Vitess":** An open-source sharding middleware for MySQL, originally built at YouTube to scale MySQL. It sits in front of many MySQL instances and makes them look like one. Core pieces: `vtgate` (the smart proxy/router clients connect to), `vttablet` (a sidecar in front of each MySQL), and a **topology service** (metadata store, e.g., etcd/ZooKeeper/Consul).
>
> **Beginner note — "etcd / ZooKeeper / Consul":** Highly-available key-value stores used for *coordination* and *metadata*. They store cluster configuration, the shard map, leader-election results, etc., with strong consistency (usually via the Raft or Zab consensus protocol). If you've heard "the topology service," it's one of these.

Step-by-step for an `INSERT INTO orders (...) VALUES (...)`:

1. **Client → vtgate.** The Java app (via the MySQL JDBC driver or Vitess's gRPC driver) sends the SQL to `vtgate` as if it were a normal MySQL server.
2. **Parse & plan.** `vtgate` parses the SQL, identifies the table `orders`, and consults the **VSchema** (the Vitess schema metadata) to learn that `orders` is sharded by, say, a hash (`hash` vindex) on `customer_id`.

   > **Beginner note — "vindex":** Vitess's name for a *Vitess index* — the function that maps a column value to a shard ("keyspace ID"). A `hash` vindex hashes the column; a `lookup` vindex stores a key→shard mapping in a side table (Vitess's built-in directory sharding).
3. **Compute keyspace ID.** `vtgate` applies the vindex: `keyspace_id = hash(customer_id)`. It then consults the **shard map** (which keyspace-ID ranges belong to which shard) to pick the target shard, e.g., shard `-80` (keyspace IDs `0x00..0x80`).
4. **Route to vttablet.** `vtgate` opens/uses a connection to the primary `vttablet` of shard `-80`.
5. **vttablet → MySQL.** The `vttablet` applies connection pooling, query rewriting, and protections (e.g., row-count limits), then runs the `INSERT` against its local MySQL primary.
6. **MySQL commits.** MySQL writes to its InnoDB redo log (WAL), updates the buffer pool, and on commit flushes/groups the log. The shard's own **replicas** receive the change via MySQL binlog replication.

   > **Beginner note — "binlog":** MySQL's binary log of all data changes. Replicas tail it to stay in sync; tools (and Vitess's resharding) also read it to stream changes.
7. **Acknowledge up the chain.** MySQL → vttablet → vtgate → client. The client sees a normal MySQL OK packet.

If the `INSERT` had *no* shard key in scope (e.g., a multi-row insert spanning shards), vtgate would split it per shard and run a **distributed transaction** (Vitess supports best-effort 2PC; see §3.6 and §7).

### 3.3 End-to-end read path

**Single-shard (targeted) read** — `SELECT * FROM orders WHERE customer_id = 42`:
1. vtgate computes `hash(42)` → shard.
2. Routes to that shard's serving tablet (primary or replica, per the requested consistency).
3. Returns rows directly. **Cheap.** This is the case you want to design for.

**Scatter-gather (cross-shard) read** — `SELECT * FROM orders WHERE status = 'PENDING' ORDER BY created_at LIMIT 100`:
1. `status` is not the shard key → vtgate cannot prune → sends to **all** shards.
2. Each shard runs `... WHERE status='PENDING' ORDER BY created_at LIMIT 100` locally (note: each must return up to 100 so the merge is correct).
3. vtgate **merge-sorts** the N×100 rows and applies the final `LIMIT 100`.
4. Latency = slowest shard + merge cost. The "tail latency" of the slowest shard dominates (tail amplification).

**Aggregations** — `SELECT COUNT(*) FROM orders`:
1. vtgate rewrites to `SELECT COUNT(*) ...` per shard.
2. Sums the partial counts. (For `AVG`, it must fetch `SUM` and `COUNT` per shard and recombine — a naive average of averages is wrong.)

> **Internal subtlety — pushdown:** Good sharding layers *push down* as much computation as possible to each shard (filters, partial aggregates, partial sorts) and do minimal work at the merge stage. `GROUP BY`, `DISTINCT`, `ORDER BY ... LIMIT`, and `HAVING` all require careful per-shard execution + merge logic.

### 3.4 Metadata & the shard map lifecycle

Every distributed system needs a source of truth for "which key lives where":

| System | Metadata store | What it holds |
|---|---|---|
| Vitess | etcd/ZooKeeper/Consul (topology) + VSchema | shard ranges, vindexes, tablet types |
| MongoDB sharded cluster | **config servers** (a replica set) | chunk ranges → shard mapping |
| CockroachDB | gossiped **range descriptors** + system ranges | range [start,end) → replicas/leaseholder |
| Cassandra | gossip + token ranges | token ring ownership per node |
| HBase | `hbase:meta` table in ZooKeeper-coordinated cluster | region [startKey,endKey) → RegionServer |

The map's lifecycle: **created** at cluster init → **read** on every routed query (heavily cached client-side) → **mutated** during splits/merges/rebalancing → **propagated** to all routers (via watch/gossip/version bump). Stale maps cause misroutes; systems handle this with versioning + retry ("you sent this to the wrong shard; here's the new owner, retry").

### 3.5 Rebalancing / resharding — the hard part, step by step

Rebalancing moves data to fix skew or add/remove capacity. Done wrong, it causes downtime or data loss. The modern approach (Vitess "Reshard", MongoDB chunk migration, Cassandra/CockroachDB streaming) is **online** and roughly:

**Vitess online resharding (split shard `-80` into `-40` and `40-80`):**
1. **Create** the new target shards (`-40`, `40-80`) with empty tables and their own replicas.
2. **Copy** the existing data: vttablet streams a consistent snapshot of source rows into the targets, filtered by keyspace-ID range (`VReplication` copy phase).

   > **Beginner note — "VReplication":** Vitess's engine for streaming rows/changes from one set of shards to another, used for resharding and migrations. It reads the source's binlog and replays matching rows to the target.
3. **Catch up**: after the snapshot, VReplication tails the source binlog and applies ongoing changes to the targets, so they stay current with live writes.
4. **Reach near-zero lag** between source and targets.
5. **SwitchReads**: flip read traffic for the affected key ranges to the new shards (reversible).
6. **SwitchWrites**: briefly stop writes to the source range (a short, coordinated cutover), ensure targets fully caught up, then point writes at the new shards. Update the topology shard map.
7. **Cleanup**: drop the old source shard once verified.

The genius is steps 2–4 happen *online* with the source still serving; only step 6 is a brief, controlled cutover (milliseconds to seconds of write unavailability for that key range).

**Cassandra/Dynamo-style (consistent hashing):** adding a node inserts new vnode tokens on the ring; the node *streams* the key ranges it now owns from its predecessors. Reads/writes use *quorum* across replicas during the move so no data is lost. No global pause.

**CockroachDB:** ranges auto-split when they exceed a size threshold (default ~512 MiB) and the **rebalancer** continuously moves *replicas* between nodes to equalize load/space using Raft snapshots + log replay.

> **Beginner note — "Raft":** A consensus algorithm that keeps multiple replicas of the same data in agreement by electing a leader and replicating an ordered log of operations. A majority (quorum) must acknowledge each entry. It's how CockroachDB, etcd, TiKV, and others keep each shard's replicas consistent.
>
> **Beginner note — "quorum":** A majority of replicas (e.g., 2 of 3). Requiring writes to reach a quorum and reads to consult a quorum guarantees they overlap, so a read always sees the latest committed write. Underpins both consensus and Dynamo-style tunable consistency.

### 3.6 Cross-shard transactions internally — Two-Phase Commit (2PC)

When a write must atomically touch rows on multiple shards, you need a distributed transaction. The classic protocol is **2PC**:

> **Beginner note — "2PC / two-phase commit":** A protocol where a *coordinator* asks all participating shards "can you commit?" (PREPARE / phase 1). If *all* vote yes, it tells everyone "COMMIT" (phase 2); if any votes no, "ABORT." Each participant durably persists its prepared state so it can survive a crash and finish either way.

Phase 1 (PREPARE): coordinator → each shard: "prepare." Each shard does the work, writes a *prepare record* to its WAL, holds locks, and replies "ready" (or "no").
Phase 2 (COMMIT/ABORT): if all ready → "commit"; each shard commits and releases locks. If any said no / timed out → "abort."

**The fatal flaw — blocking:** if the coordinator crashes *after* participants prepared but *before* sending the decision, participants are stuck holding locks indefinitely ("in-doubt"). Variants like **3PC** and consensus-based commit (Spanner, CockroachDB use Paxos/Raft for the commit record to avoid a single fragile coordinator) mitigate this. This is why **avoiding cross-shard transactions by design** (co-locating related data via the shard key) is the preferred strategy.

> **Beginner note — "Paxos":** Another consensus algorithm (like Raft) for getting a group of nodes to agree on a value despite failures. Spanner uses Paxos groups per shard.

---

## 4. The complete toolkit

This section enumerates the concrete APIs, commands, config, and parameters across the major ecosystems. Defaults are flagged with versions where they matter; where I'm unsure of an exact default I say so.

### 4.1 PostgreSQL — declarative partitioning (single-node)

PostgreSQL 10+ has built-in **declarative partitioning**.

| Construct | Purpose | Notes / params |
|---|---|---|
| `PARTITION BY RANGE (col)` | Range partitioning | Most common; great for time-series |
| `PARTITION BY LIST (col)` | Discrete-value partitioning | e.g., by `region` |
| `PARTITION BY HASH (col)` | Even distribution by hash | params: `MODULUS m, REMAINDER r` |
| `CREATE TABLE ... PARTITION OF parent FOR VALUES ...` | Create a child partition | `FROM (..) TO (..)` for range |
| `ATTACH PARTITION` / `DETACH PARTITION` | Add/remove a partition online | `DETACH ... CONCURRENTLY` (PG 14+) avoids long locks |
| `enable_partition_pruning` | Planner skips irrelevant partitions | default `on` |
| `enable_partitionwise_join` | Join partitions pairwise | default **`off`** (costs planning time) |
| `enable_partitionwise_aggregate` | Aggregate per partition then combine | default **`off`** |
| `pg_partman` (extension) | Automates partition creation/retention | not core; widely used |

> Sharding across PostgreSQL servers is **not** core PostgreSQL — you use **Citus** (extension), **foreign data wrappers** (`postgres_fdw` + `partition` = manual sharding), or external tooling.

### 4.2 MySQL — partitioning (single-node) + Vitess (sharding)

| Construct | Purpose | Notes |
|---|---|---|
| `PARTITION BY RANGE/LIST/HASH/KEY (...)` | Single-node partitioning | `KEY` uses MySQL's internal hash; `HASH` uses your expression |
| `PARTITIONS n` | Number of hash/key partitions | |
| Limitation | Every **unique key (incl. PK)** must include the partition column | hard constraint; shapes your PK design |

**Vitess CLI / config (sharding):**

| Tool / command | Purpose |
|---|---|
| `vtctldclient` | Admin CLI for Vitess topology/operations |
| `Reshard` | Split/merge shards online via VReplication |
| `MoveTables` | Move tables between keyspaces |
| `ApplyVSchema` | Define vindexes/sharding scheme |
| VSchema `vindex` types | `hash`, `lookup`, `lookup_unique`, `numeric`, `unicode_loose_md5`, `consistent_lookup`, etc. |
| `--max_result_size` / row limits | Guard against runaway scatter queries |

### 4.3 MongoDB — sharded clusters

| Command / concept | Purpose | Defaults / params |
|---|---|---|
| `sh.enableSharding("db")` | Enable sharding on a database | |
| `sh.shardCollection("db.coll", {key: 1})` | Range shard on key | |
| `sh.shardCollection("db.coll", {key: "hashed"})` | Hash shard | |
| Compound shard key | Multi-field key | supported; enables some range locality |
| **chunk** | Unit of data migration | default size **128 MB** (was 64 MB pre-6.0) |
| **balancer** | Background chunk mover | on by default; window configurable |
| `config servers` | Hold the chunk map | a replica set (CSRS) |
| `mongos` | Query router (the proxy) | stateless; clients connect here |
| **zones** (`sh.addShardToZone`, `sh.updateZoneKeyRange`) | Pin key ranges to shards | used for geo-sharding |

### 4.4 Cassandra / ScyllaDB — native partitioning + replication

| Concept | Purpose | Defaults |
|---|---|---|
| **partition key** (first part of PRIMARY KEY) | Determines node via token | required |
| **clustering columns** | Sort rows *within* a partition | |
| `partitioner` | Hash function for tokens | `Murmur3Partitioner` (default) |
| `num_tokens` | vnodes per node | historically 256; modern guidance 16 (or fewer with allocation algorithm) |
| `replication_factor` (per keyspace) | Copies per partition | you set it (commonly 3) |
| Consistency levels | `ONE`, `QUORUM`, `LOCAL_QUORUM`, `ALL`, etc. | per-query tunable |

### 4.5 CockroachDB / YugabyteDB / Spanner — NewSQL native

| Concept | Purpose | Notes |
|---|---|---|
| **range** (CockroachDB) | Auto-split shard | default split target ~**512 MiB** |
| `SPLIT AT` / `UNSPLIT AT` | Manual range hints | |
| `PARTITION BY` (geo-partitioning) | Pin rows to localities | enterprise/geo feature |
| Raft groups | Per-range consensus | replication_factor default 3 |
| `ALTER TABLE ... CONFIGURE ZONE` | Set replication/placement | |
| Spanner `INTERLEAVE IN PARENT` | Co-locate child rows with parent | avoids cross-shard joins for hierarchies |

### 4.6 Java-side application sharding toolkit

| Tool / API | Purpose |
|---|---|
| Multiple `DataSource` beans + a routing `DataSource` | Spring's `AbstractRoutingDataSource` picks a DataSource per thread/key |
| **Apache ShardingSphere-JDBC** | A JDBC driver that shards transparently below your DAO |
| HikariCP (one pool per shard) | Connection pooling per physical DB |
| Consistent-hashing libs (Guava `Hashing.consistentHash`, or custom ring) | Compute shard from key |
| Snowflake-style ID generators | Globally unique IDs without a central sequence (critical when you can't use AUTO_INCREMENT across shards) |

> **Beginner note — "Snowflake ID":** A 64-bit ID composed of a timestamp + a machine/shard id + a per-ms sequence. It's unique across all shards without coordination, sortable by time, and avoids the cross-shard collision problem of per-shard `AUTO_INCREMENT`.

---

## 5. Code examples by use case

### 5.1 PostgreSQL range partitioning by month (time-series, single node)

```sql
-- Parent table: declares the partitioning scheme but stores no rows itself.
CREATE TABLE events (
    id          bigint        GENERATED ALWAYS AS IDENTITY,
    occurred_at timestamptz   NOT NULL,
    user_id     bigint        NOT NULL,
    payload     jsonb         NOT NULL,
    -- PK MUST include the partition column in PG declarative partitioning:
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Monthly child partitions. Each is a real table on disk.
CREATE TABLE events_2026_06 PARTITION OF events
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE events_2026_07 PARTITION OF events
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

-- Partition pruning in action: planner only scans events_2026_06.
EXPLAIN SELECT count(*) FROM events
 WHERE occurred_at >= '2026-06-10' AND occurred_at < '2026-06-15';

-- Data lifecycle: drop last quarter in O(1), no giant DELETE/VACUUM.
DROP TABLE events_2026_03;          -- instant; reclaims space immediately
-- Or detach to archive without locking the live table (PG14+):
ALTER TABLE events DETACH PARTITION events_2026_03 CONCURRENTLY;
```

Why it matters: `DROP TABLE old_partition` replaces an hours-long `DELETE FROM events WHERE occurred_at < ...` (which also bloats the table and demands `VACUUM`). Pruning means time-bounded queries touch one partition.

### 5.2 PostgreSQL hash partitioning for even write spread

```sql
CREATE TABLE sessions (
    session_id  uuid NOT NULL,
    user_id     bigint NOT NULL,
    data        jsonb,
    PRIMARY KEY (session_id)
) PARTITION BY HASH (session_id);

-- 4 hash partitions, evenly spread by modulus/remainder.
CREATE TABLE sessions_p0 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE sessions_p1 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE sessions_p2 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE sessions_p3 PARTITION OF sessions FOR VALUES WITH (MODULUS 4, REMAINDER 3);
```

UUID session ids would otherwise create no useful ranges; hashing spreads them evenly. Note the trade-off: you cannot do an efficient range scan over `session_id` (they're meaningless ranges anyway).

### 5.3 Application-level sharding in Java with Spring routing DataSource

```java
// 1) Hold the current shard for this thread of execution.
public final class ShardContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static void set(String shard) { CURRENT.set(shard); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}

// 2) Spring picks the physical DataSource based on the thread-local key.
public class ShardRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return ShardContext.get();        // e.g. "shard-0" .. "shard-7"
    }
}

// 3) Compute the shard from the shard key (consistent hashing via Guava).
public class ShardRouter {
    private final int shardCount;                       // e.g. 8
    public ShardRouter(int shardCount) { this.shardCount = shardCount; }

    public String shardFor(long customerId) {
        // Guava consistentHash minimizes remapping when shardCount changes.
        int bucket = com.google.common.hash.Hashing.consistentHash(customerId, shardCount);
        return "shard-" + bucket;
    }
}

// 4) Use it: route, then run the query on the right shard.
@Service
public class OrderService {
    private final ShardRouter router;
    private final OrderRepository repo;   // a normal Spring Data repo

    public Order placeOrder(long customerId, Order order) {
        try {
            ShardContext.set(router.shardFor(customerId));  // pick shard
            return repo.save(order);                        // routed automatically
        } finally {
            ShardContext.clear();                            // never leak the binding
        }
    }
}
```

Key lessons embedded above: bind the shard *per unit of work* and **always clear** the thread-local (a leaked binding sends the next request to the wrong shard — a classic, nasty bug). One `HikariCP` pool per shard sits behind each target DataSource.

### 5.4 Cross-shard scatter-gather aggregation in Java

```java
// COUNT across all shards: run per-shard COUNT in parallel, then sum.
public long totalOrders(List<DataSource> shards) {
    return shards.parallelStream()                          // fan out
        .mapToLong(ds -> {
            try (var c = ds.getConnection();
                 var st = c.prepareStatement("SELECT COUNT(*) FROM orders");
                 var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);                        // partial count
            } catch (SQLException e) { throw new RuntimeException(e); }
        })
        .sum();                                              // gather/merge
}

// Top-N across shards with correct merge semantics.
public List<Order> topNByAmount(List<DataSource> shards, int n) {
    return shards.parallelStream()
        .flatMap(ds -> fetchTopN(ds, n).stream())            // each shard returns its top N
        .sorted(Comparator.comparingLong(Order::amount).reversed())
        .limit(n)                                            // final global top N
        .toList();
    // NOTE: must fetch N from EACH shard, not N/shards, or you can miss true top rows.
}
```

The comment on `topNByAmount` captures the single most common correctness bug in scatter-gather: under-fetching per shard.

### 5.5 MongoDB sharded collection with hashed key + zones for geo

```javascript
sh.enableSharding("shop")

// Hashed shard key: even write distribution; user_id is high-cardinality.
sh.shardCollection("shop.orders", { user_id: "hashed" })

// Geo-sharding via zones: pin EU users' data to EU-resident shards
// (data residency / latency). Requires a range-able key, here a compound key.
sh.addShardToZone("shardEU", "EU")
sh.addShardToZone("shardUS", "US")
sh.updateZoneKeyRange("shop.users",
    { region: "EU", user_id: MinKey }, { region: "EU", user_id: MaxKey }, "EU")
sh.updateZoneKeyRange("shop.users",
    { region: "US", user_id: MinKey }, { region: "US", user_id: MaxKey }, "US")
```

### 5.6 Cassandra: choosing a partition key to avoid hot partitions

```sql
-- BAD: all sensor readings for a device go to ONE partition -> unbounded growth, hot.
CREATE TABLE readings_bad (
    device_id text,
    ts        timestamp,
    value     double,
    PRIMARY KEY (device_id, ts)
);

-- GOOD: "bucket" the partition by day to bound partition size and spread load.
CREATE TABLE readings (
    device_id text,
    day       date,                       -- bucketing dimension
    ts        timestamp,
    value     double,
    PRIMARY KEY ((device_id, day), ts)    -- composite partition key
) WITH CLUSTERING ORDER BY (ts DESC);

-- Query one device for one day = one partition, sorted by time. Fast & bounded.
SELECT * FROM readings WHERE device_id = 'sensor-7' AND day = '2026-06-24';
```

This is the canonical **bucketing** pattern: append a coarse time/space dimension to the partition key so no single partition grows without bound and writes spread over time.

### 5.7 Vitess VSchema: hash vindex + lookup vindex for secondary access

```json
{
  "sharded": true,
  "vindexes": {
    "hash": { "type": "hash" },
    "orders_by_email": {
      "type": "lookup_unique",
      "params": {
        "table": "orders_email_idx",   // a side table mapping email -> keyspace_id
        "from": "email",
        "to": "keyspace_id"
      },
      "owner": "orders"
    }
  },
  "tables": {
    "orders": {
      "column_vindexes": [
        { "column": "customer_id", "name": "hash" },         // primary sharding
        { "column": "email",       "name": "orders_by_email" } // lets WHERE email=? route to one shard
      ]
    }
  }
}
```

A **lookup vindex** is directory sharding bolted onto a hash-sharded table: `WHERE customer_id = ?` is a single-shard query via the hash, and `WHERE email = ?` becomes a single-shard query too (after one lookup hop) instead of a full scatter.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Design for single-shard queries.** The shard key should align with your dominant query's `WHERE` clause. If 90% of reads are `WHERE customer_id = ?`, shard by `customer_id`. Scatter-gather should be the rare path.
- **Tail-latency amplification.** A scatter query's latency = the slowest shard's. With 50 shards, even a 99th-percentile-slow shard becomes likely on every query. Mitigate with timeouts, hedged requests, and minimizing fan-out width.
- **Connection pool math.** Each app instance × each shard × pool size = total connections to the DB tier. 100 app pods × 50 shards × 10 connections = 50,000 connections — easily exhausts MySQL's `max_connections`. Use a proxy (vtgate/ProxySQL) to multiplex, or small pools.
- **Avoid `SELECT ... ORDER BY non_shard_key LIMIT` at scale** — it forces per-shard over-fetch + merge.

### 6.2 Correctness & concurrency

- **No cross-shard foreign keys.** The DB cannot enforce FKs across shards. Enforce in app or co-locate.
- **Globally unique IDs.** `AUTO_INCREMENT` per shard collides. Use Snowflake/UUIDv7/database sequences-with-offset (`shard0: 0,N,2N…`, `shard1: 1,N+1…`).
- **Cross-shard transactions are not free or fully atomic by default.** Prefer co-location; if unavoidable, use 2PC (slow, blocking risk) or a **saga** (a sequence of local transactions with compensating actions).

  > **Beginner note — "saga":** Instead of one big distributed transaction, you do a chain of local transactions, each publishing an event; if a later step fails, you run *compensating* transactions to undo prior steps. Eventual consistency, no global locks.
- **Read-your-writes across shard + replica.** A write to a shard's primary may not yet be on its replica; route follow-up reads to the primary or use bounded staleness.

### 6.3 Security

- **Multi-tenant isolation.** Directory/zone sharding can pin a tenant to a shard for isolation and **data residency** (GDPR: EU data stays in EU). Verify the router cannot misroute across tenants.
- **Larger attack/credential surface.** N database endpoints = N sets of credentials, network rules, and audit logs. Centralize via a proxy and a secrets manager.

### 6.4 Observability

- **Per-shard metrics**: QPS, latency, disk, connection count, replication lag — *per shard*, not aggregated (aggregates hide a single hot shard).
- **Heatmaps** of key/shard distribution to catch skew early.
- **Scatter-query rate**: track what fraction of queries fan out; a rising trend signals a shard-key mismatch.
- **Slowest-shard tracking** for tail-latency root cause.

### 6.5 Cost

- Sharding multiplies fixed overhead (N primaries + replicas, N backups, N monitoring targets). It is rarely cheaper than one big box until you genuinely exceed one box; choose it for *capability*, not cost.

### 6.6 Testing

- Test routing with **boundary keys** (the exact split points), **hot keys**, and **missing keys**.
- Test **resharding** in staging with production-like data volume; many bugs only appear at scale (lock contention, binlog lag).
- **Chaos test** a single shard's failure: does the app degrade gracefully (partial results) or hard-fail the whole request?

### 6.7 Production hardening

- **Idempotent writes** so retries after misroute/timeout don't double-apply.
- **Backpressure & per-shard circuit breakers** so one sick shard doesn't take down the app.
- **Pre-split** before known load spikes (Black Friday) to avoid hot-shard formation mid-event.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it bites |
|---|---|
| Sharding too early | Massive complexity for load one box could handle |
| Low-cardinality shard key (e.g., `country`, `status`) | Can't split finely; guarantees skew |
| Monotonic shard key with range sharding (`auto-increment id`, `created_at`) | All new writes hit the last shard (append hotspot) |
| Naive `hash % N` | Adding a shard remaps nearly all data |
| Designing for scatter-gather as the norm | Every query is as slow as the slowest shard |
| Cross-shard transactions everywhere | Distributed-commit latency + blocking risk |
| No global ID strategy | PK collisions across shards |
| Shard key you can't change | You will want to change it; build a re-key path |

---

## 7. Advanced topics & deep internals

### 7.1 The celebrity / hot-shard problem and mitigations

Even with a high-cardinality, uniformly-hashed key, **one value** can be hot: a celebrity user with 10M followers, a viral product, a single device emitting at 100× rate. Hashing puts that key on *one* shard, which then melts.

Mitigations:
1. **Split the hot key** by appending a sub-key: `key = celebrityId + ":" + bucket(0..K)`. Reads must fan out over the K sub-buckets, but writes spread. (a.k.a. *salting*.)
2. **Cache the hot entity** aggressively (its writes still hurt, but reads offload).
3. **Dedicated shard** for known whales (directory sharding lets you pin them).
4. **Application-level write coalescing/batching** for the hot key.
5. **Approximate counters** (e.g., sharded counters summed periodically) instead of one row everyone increments.

### 7.2 Choosing a shard key — the full checklist

A good shard key maximizes four properties simultaneously:

1. **High cardinality** — enough distinct values to split as fine as you'll ever need.
2. **Even distribution** — values (after hashing) spread uniformly; no value dominates.
3. **Query alignment** — the dominant queries filter on it, so they stay single-shard.
4. **Monotonicity-aware** — if using range sharding, avoid ever-increasing keys (or accept/mitigate the append hotspot).
5. **Immutability** — the key value shouldn't change for a row (changing it = moving the row to another shard = expensive and transactionally hard).

These conflict. `customer_id` gives query alignment + cardinality but a celebrity breaks even distribution. A **compound key** (`customer_id, order_id`) or **bucketing** often balances them.

### 7.3 Cross-shard joins — strategies

You cannot `JOIN` rows that live on different shards in a single local query. Options:

- **Co-location (best):** shard both tables by the *same* key so joinable rows share a shard. Spanner's `INTERLEAVE IN PARENT` and Vitess's shared vindex do exactly this — child rows live with their parent.
- **Reference / broadcast tables:** small, slowly-changing tables (e.g., `currencies`, `countries`) replicated to *every* shard, so any join finds them locally. Citus calls these *reference tables*; Vitess does *broadcast*.
- **Application-side join:** fetch from shard A, then fetch matching rows from shard B, join in app memory. Simple, but N+1 network hops.
- **Denormalization:** copy needed columns into the row so no join is required (pay with write-time duplication and consistency work).

### 7.4 Tunable consistency (Dynamo-style) and how it interacts with sharding

Cassandra/DynamoDB shard via consistent hashing *and* replicate each partition RF times. Per query you pick consistency:
- Write with `W` acks, read from `R` replicas. If `R + W > RF`, reads see the latest write (strong-ish). If not, you may read stale (eventual consistency) but with lower latency/higher availability.
- `LOCAL_QUORUM` keeps quorum within one datacenter (low latency, survives DC partition for that DC).

> **Beginner note — "CAP theorem":** Under a network partition (nodes can't all talk), a distributed system must choose between **C**onsistency (every read sees the latest write) and **A**vailability (every request gets an answer). Sharded+replicated systems expose this as a tuning knob. (Adjacent: **PACELC** adds that even without a partition you trade **L**atency vs **C**onsistency.)

### 7.5 Application-level vs middleware vs native sharding — deep comparison

| Dimension | Application-level | Middleware (Vitess/Citus/ProxySQL) | Native (Cassandra/Cockroach/Mongo/Spanner) |
|---|---|---|---|
| Where routing lives | Your code | A proxy process | The database engine |
| Transparency to app | Low (app knows shards) | High (looks like one DB) | High |
| Cross-shard joins/txn | You build them | Engine provides (some) | Built in |
| Resharding | You build (very hard) | Online tooling (Reshard) | Automatic / built in |
| Operational burden | On your team | On your team (run the proxy) | On the DB (but it's complex) |
| Flexibility | Total | High | Constrained to the engine's model |
| Maturity risk | Many edge cases to reinvent | Battle-tested (YouTube/Slack/etc.) | Battle-tested per vendor |
| Migration from existing RDBMS | Hard | Moderate (keeps MySQL/PG) | Often a rewrite |
| When to pick | Simple, few shards, full control | Large MySQL/PG estate you must keep | Greenfield needing scale-out by default |

### 7.6 Lesser-known behaviors & tuning knobs

- **MongoDB jumbo chunks:** a chunk that can't split (because the shard-key value range is indivisible — low cardinality!) becomes a `jumbo` chunk the balancer won't move → permanent skew. Always pick high-cardinality keys.
- **CockroachDB hotspot mitigation:** for sequential PKs, use `UUID`/hash-prefixed keys or `ALTER TABLE ... SPLIT AT` to pre-split, avoiding a single-range write hotspot.
- **Cassandra `tombstones`:** deletes write tombstone markers; partitions with many deletes accumulate tombstones and slow reads (the `tombstone_failure_threshold`, default 100,000, can abort a query). A sharding-adjacent gotcha for delete-heavy partitions.
- **Vitess `consistent_lookup` vindex:** keeps the lookup table consistent with the main table within the same transaction, avoiding the dangling-lookup problem of plain `lookup`.
- **Partition pruning vs. prepared statements:** in some DBs, parameterized queries can defeat *static* pruning if the planner can't see the literal; watch for "runtime pruning" support.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Strategy selection matrix

| Strategy | Point lookups | Range scans | Write spread | Rebalance cost | Hotspot risk | Best for |
|---|---|---|---|---|---|---|
| **Range** | Good | **Excellent** | Poor for monotonic keys | Low–medium (split a range) | High (append tail) | Time-series, ordered access |
| **Hash** | **Excellent** | **Bad** (scatter) | **Excellent** | High if naive `%N`; low with consistent hashing | Low (except single hot key) | Even load, key-equality access |
| **Consistent hash** | Excellent | Bad | Excellent | **Low** (~1/N moves) | Low | Elastic clusters (Cassandra/Dynamo) |
| **Directory/lookup** | Good (+1 hop) | Depends | Controllable | **Lowest** (edit map) | Controllable (pin whales) | Multi-tenant, flexible placement |
| **Geo** | Good | Good within region | Per-region | Medium | Per-region | Data residency, latency |

### 8.2 "Use when / avoid when"

**Range sharding** — *use when* queries are range/ordered (time-series, leaderboards), and you can avoid or mitigate monotonic keys. *Avoid when* your key is monotonically increasing and you can't tolerate an append hotspot.

**Hash sharding** — *use when* access is by exact key equality and you need even write spread. *Avoid when* you need efficient range scans or sorted reads on the shard key.

**Directory sharding** — *use when* you need per-tenant placement, easy rebalancing, or to isolate whales. *Avoid when* you can't operate a highly-available directory or the extra hop's latency is unacceptable.

**Geo sharding** — *use when* you have data-residency law (GDPR) or strong locality (users mostly query their own region). *Avoid when* data is global and access is uniform (you'd just add cross-region latency).

**Sharding at all** — *use when* you've exhausted vertical scaling, caching, read replicas, and functional decomposition, and one dataset still won't fit. *Avoid when* a bigger box or better indexes would do — the complexity tax is enormous.

### 8.3 Decision flow (text)

1. Does a single table hurt only on maintenance/queries but fit one box? → **Local partitioning** (range by time usually). Stop.
2. Are different *domains* the problem? → **Functional decomposition.** 
3. Is one domain too big for a box even after replicas/cache/vertical? → **Shard it.**
4. Choose key by §7.2 checklist. Range vs hash by §8.1. 
5. Keep existing MySQL/PG? → **Vitess/Citus** middleware. Greenfield, want auto-scale? → **native** (Cockroach/Cassandra/Mongo/Spanner).

---

## 9. Failure modes & debugging

### 9.1 Hot shard / append hotspot

**Symptom:** one shard at 100% CPU/IO, others idle; p99 latency on writes spikes.
**Cause:** monotonic range key (everything hits last shard) or a celebrity key.
**Diagnose:** per-shard QPS/CPU dashboards (the hot one stands out); MongoDB `sh.status()` shows chunk distribution; Cassandra `nodetool tablehistograms`/`nodetool toppartitions` finds hot partitions; CockroachDB's **hot ranges** page in the UI.
**Fix:** switch to hash key, salt/bucket the hot key, pre-split, or pin to a dedicated shard.

**Real-world flavor:** Instagram and others famously had to avoid auto-increment hotspots; the standard answer is a Snowflake-like ID or hashing.

### 9.2 Skewed distribution from low-cardinality key

**Symptom:** a few shards far larger than others; balancer can't fix it.
**Cause:** shard key with too few distinct values; in MongoDB this manifests as **jumbo chunks** that won't split.
**Diagnose:** `sh.status()` chunk counts; histogram of rows per shard.
**Fix:** re-shard on a higher-cardinality (often compound) key. Painful — hence picking right the first time.

### 9.3 Scatter-gather latency blowup

**Symptom:** dashboards/list endpoints slow as shards grow; latency tracks shard count.
**Cause:** dominant queries don't filter on the shard key → fan out to all.
**Diagnose:** Vitess `vtgate` exposes scatter-query counts; slow-query logs show identical queries across all shards.
**Fix:** add a **lookup vindex**/secondary index alignment, denormalize, or maintain a separate read model (CQRS) keyed for that access pattern.

> **Beginner note — "CQRS":** Command Query Responsibility Segregation — keep a separate, denormalized *read* model optimized for queries, fed asynchronously from the *write* model. Lets you shard the write side by one key and the read side by another.

### 9.4 In-doubt distributed transactions

**Symptom:** rows locked, transactions hung after a coordinator crash.
**Cause:** 2PC coordinator died between PREPARE and COMMIT.
**Diagnose:** look for prepared/in-doubt transactions (`XA RECOVER` in MySQL; `pg_prepared_xacts` in PostgreSQL).
**Fix:** manual resolution (`XA COMMIT`/`XA ROLLBACK`) or rely on a consensus-based commit (Spanner/Cockroach avoid the single-coordinator blocking). Strategically: avoid cross-shard txns.

### 9.5 Stale shard map / misroute

**Symptom:** "key not found here" errors or wrong results during/after resharding.
**Cause:** a router cached an old map version.
**Diagnose:** check topology version vs router's cached version; correlate with a recent reshard.
**Fix:** systems version the map and force retry on the new owner; ensure routers `watch` the metadata store rather than poll slowly.

### 9.6 Resharding lag / cutover stall

**Symptom:** `Reshard` never reaches low lag; `SwitchWrites` won't proceed.
**Cause:** write rate exceeds VReplication apply rate; long-running source transactions block binlog progress.
**Diagnose:** Vitess VReplication lag metrics; MySQL `SHOW REPLICA STATUS` / binlog position.
**Fix:** throttle source writes, scale target tablets, kill long transactions, copy during low-traffic windows.

### 9.7 Connection exhaustion

**Symptom:** `Too many connections` errors as you add shards/app instances.
**Cause:** pool-per-shard × instances overruns `max_connections`.
**Diagnose:** sum of `processlist`/`pg_stat_activity` across shards.
**Fix:** put a multiplexing proxy (vtgate, ProxySQL, PgBouncer) in front; shrink per-shard pools.

---

## 10. Interview drill

**Q1. Partitioning vs sharding — what's the difference?**
*Model answer:* Partitioning is splitting one dataset into pieces; sharding is partitioning where the pieces live on separate servers. All sharding is horizontal partitioning (same schema, disjoint rows) distributed across machines. Partitioning can be purely local (one server) for maintenance/pruning benefits without distribution.
- *Probe: Is vertical partitioning ever "sharding"?* — Practically no; "sharding" means horizontal across servers. Vertical-across-servers is usually called functional decomposition.
- *Probe: Why partition locally if it doesn't add capacity?* — Partition pruning (faster queries), cheap data lifecycle (drop old partitions), smaller indexes, faster maintenance.

**Q2. How do you choose a shard key?**
*Model answer:* Maximize high cardinality, even distribution (post-hash), query alignment (dominant `WHERE` filters on it), avoid monotonicity for range sharding, and prefer immutable keys. These conflict, so compromise with compound keys or bucketing.
- *Probe: customer_id seems perfect — what breaks it?* — A celebrity customer concentrates load on one shard (hot-shard).
- *Probe: Why does immutability matter?* — Changing the key value means physically moving the row to another shard, which is expensive and hard to do transactionally.

**Q3. Range vs hash sharding — tradeoffs?**
*Model answer:* Range gives excellent range scans and ordered access but causes append hotspots with monotonic keys. Hash gives even distribution and great point lookups but turns range queries into scatter-gather. Pick by access pattern.
- *Probe: Fix range's hotspot?* — Hash-prefix or salt the key, pre-split, or bucket.
- *Probe: Fix hash's range-scan weakness?* — Maintain a secondary read model/index, or use a compound key that preserves locality within a parent.

**Q4. What is the celebrity/hot-shard problem and how do you mitigate it?**
*Model answer:* One key (a viral user/product) is far hotter than others; hashing still sends it to one shard, overloading it. Mitigate by salting the key into sub-buckets, caching, dedicating a shard (directory sharding), write coalescing, or sharded counters.
- *Probe: Cost of salting?* — Reads must fan out across the sub-buckets.
- *Probe: Why not just add more shards?* — More shards don't help a single hot *value*; it still hashes to one place.

**Q5. How do cross-shard queries and joins work, and how do you avoid them?**
*Model answer:* Cross-shard queries fan out (scatter-gather), execute per shard, and merge; joins can't span shards in one local query. Avoid via co-location (same shard key), reference/broadcast tables for small dimensions, app-side joins, or denormalization.
- *Probe: How is a global ORDER BY ... LIMIT N done correctly?* — Fetch N from each shard, merge-sort, take global N (under-fetching is the classic bug).
- *Probe: How does AVG work across shards?* — Fetch SUM and COUNT per shard; AVG = ΣSUM/ΣCOUNT, never average-of-averages.

**Q6. Explain online resharding.**
*Model answer:* Create empty targets, copy a consistent snapshot filtered by the new key ranges, tail the binlog to catch up to live writes, switch reads, then briefly stop writes for a clean cutover, update the map, drop the source. Only the cutover is a short pause.
- *Probe: What can stall it?* — Write rate exceeding apply rate; long source transactions blocking binlog.
- *Probe: Why version the shard map?* — So routers detect staleness and retry on the correct new owner.

**Q7. Why is `hash(key) % N` dangerous, and what's the fix?**
*Model answer:* Changing N remaps nearly every key, forcing a near-total data reshuffle. Consistent hashing (a hash ring with virtual nodes) moves only ~1/N of keys when adding/removing a node.
- *Probe: What do vnodes add?* — Smoother distribution and parallelized, less-lumpy rebalancing.
- *Probe: Which systems use consistent hashing vs ranges?* — Cassandra/Dynamo/Riak use consistent hashing; CockroachDB/Spanner/HBase use ranges with auto-split.

**Q8 (senior-signal). When would you *not* shard, and what would you do instead?**
*Model answer:* If load fits a bigger box or can be relieved by indexing, caching, read replicas, or functional decomposition, do those first — sharding's complexity tax (cross-shard txns, rebalancing, ops surface, ID management) rarely pays off below a real single-node ceiling. Shard only for genuine capability limits, not premature optimization.
- *Probe: What signals it's truly time?* — Write throughput or dataset size exceeding a maxed-out primary after exhausting the cheaper levers; sustained, not spiky.
- *Probe: Cheapest lever people skip?* — A separate read model/CQRS and aggressive caching, plus functional decomposition.

**Q9 (senior-signal). Application-level vs Vitess vs native sharding — justify a choice.**
*Model answer:* Keep a large existing MySQL estate you can't rewrite → Vitess (transparent routing, online reshard, proven at scale). Greenfield needing horizontal scale by default → native NewSQL (CockroachDB/Spanner) or Cassandra/Mongo by consistency/model needs. Application-level only for simple, few-shard cases where you want total control and accept reinventing cross-shard logic.
- *Probe: Hidden cost of app-level?* — You re-implement routing, merging, resharding, and distributed transactions — all error-prone.
- *Probe: Why might native still lose?* — Migration cost/rewrite, operational unfamiliarity, or losing rich SQL/ecosystem features.

**Q10 (senior-signal). Design the data model for a multi-tenant SaaS with a few "whale" tenants and many small ones.**
*Model answer:* Shard by `tenant_id` for isolation and single-tenant query locality. Use **directory sharding** so small tenants pack onto shared shards while whales get dedicated shards (or salted sub-keys) to prevent hot shards. Reference/config tables broadcast to all shards. Enforce data residency via geo-zones if required. This balances even distribution, isolation, and cheap rebalancing (edit the directory).
- *Probe: Why directory over hash here?* — Hash can't pin whales to dedicated capacity or isolate tenants; directory gives per-tenant placement and easy moves.
- *Probe: How handle a whale growing past one shard?* — Salt its key into sub-buckets within its dedicated shard set, accepting fan-out for that tenant's reads.

**Q11. How are globally unique IDs handled in a sharded system?**
*Model answer:* Per-shard `AUTO_INCREMENT` collides. Use Snowflake IDs (timestamp + shard id + sequence), UUIDv7 (time-ordered), or sequences with per-shard offsets/step. Snowflake gives uniqueness, rough time-ordering, and no central coordinator.
- *Probe: Downside of random UUIDs as PK?* — Poor index locality / write amplification (random insert points in the B-tree); UUIDv7/Snowflake fix this by being time-sortable.

**Q12. What's a distributed transaction across shards, and why avoid it?**
*Model answer:* An atomic write spanning shards, typically via 2PC (prepare/commit). It's slow (extra round trips, held locks) and can block if the coordinator crashes mid-protocol. Prefer co-locating related data so the transaction is single-shard, or use sagas with compensations for eventual consistency.
- *Probe: How do Spanner/Cockroach reduce 2PC's blocking risk?* — They make the commit decision itself consensus-replicated (Paxos/Raft), so no single fragile coordinator.

---

## 11. Glossary

- **2PC (Two-Phase Commit):** Distributed-transaction protocol: prepare (vote) then commit/abort; can block if the coordinator fails mid-way.
- **3PC:** A non-blocking variant of 2PC adding an extra phase; rarely used in practice.
- **Append hotspot / hot tail:** All new writes hitting the last range-shard because the key is monotonically increasing.
- **AUTO_INCREMENT:** A per-table counter for IDs; collides across shards.
- **Binlog:** MySQL's binary change log used for replication and change streaming.
- **Bucketing:** Adding a coarse dimension (day, region) to the partition key to bound partition size and spread load.
- **Buffer pool / page cache:** RAM cache of data pages; hits are fast, misses go to disk.
- **CAP theorem:** Under a partition, choose consistency or availability.
- **Cardinality:** Number of distinct values a column has.
- **Celebrity problem:** A single key value far hotter than others, overloading its shard.
- **Chunk (MongoDB):** Unit of data migration between shards (default 128 MB).
- **Citus:** PostgreSQL extension that shards Postgres.
- **Co-location:** Placing related rows on the same shard (same shard key) so joins/txns stay local.
- **Config servers (MongoDB):** Replica set storing the chunk-to-shard map.
- **Consistent hashing:** Mapping keys and nodes onto a ring so adding/removing a node moves ~1/N keys.
- **CQRS:** Separate optimized read model fed from the write model.
- **Directory/lookup sharding:** A map (table/service) from key/bucket to shard; maximally flexible.
- **etcd / ZooKeeper / Consul:** Strongly-consistent key-value stores for coordination/metadata.
- **Fan-out / scatter-gather:** Querying many/all shards and merging results.
- **Functional decomposition:** Splitting data by business domain onto different databases.
- **Geo sharding:** Placing data by geographic region (residency/latency).
- **Hash function:** Deterministic mapping of input to a pseudo-random fixed-size value; does not preserve order.
- **Horizontal partitioning:** Splitting by rows (same schema, disjoint rows).
- **Hot shard:** A shard receiving disproportionate load.
- **In-doubt transaction:** A prepared distributed transaction stuck because the decision never arrived.
- **Jumbo chunk:** A MongoDB chunk too large to split (low-cardinality key) and thus unmovable.
- **Keyspace ID (Vitess):** The hashed value space used to map rows to shards.
- **Leaseholder (CockroachDB):** The replica that serves reads/coordinates writes for a range.
- **Logical vs physical:** How the app thinks of data vs how it's stored.
- **Lookup vindex (Vitess):** Directory sharding via a side table mapping a secondary column to shard.
- **Middleware/proxy sharding:** A process between app and DBs that routes/merges transparently (Vitess, ProxySQL, ShardingSphere).
- **mongos:** MongoDB's stateless query router.
- **Murmur3 / xxHash / CRC32:** Fast non-cryptographic hash functions.
- **MVCC (Multi-Version Concurrency Control):** Keeping multiple row versions so readers don't block writers (adjacent concept).
- **PACELC:** Extends CAP: even without partitions, trade latency vs consistency.
- **Paxos:** A consensus algorithm for agreement among replicas (used by Spanner).
- **Partition:** One physical chunk of data.
- **Partition function:** `f(key) → shard`.
- **Partition key / shard key:** The value deciding a row's shard.
- **Partition pruning:** Planner skipping partitions irrelevant to a query.
- **Quorum:** A majority of replicas; overlapping read/write quorums ensure latest-write visibility.
- **Raft:** A consensus algorithm (leader + replicated log + quorum) keeping replicas consistent.
- **Range partitioning:** Contiguous key ranges per shard; great for range scans.
- **Rebalancing / resharding:** Moving data between shards to fix skew or change capacity.
- **Reference / broadcast table:** Small table copied to every shard for local joins.
- **Replication:** Keeping copies of the same data for availability/read scaling (distinct from sharding).
- **Routing:** Deciding which shard(s) a query targets.
- **Saga:** A chain of local transactions with compensating actions instead of one distributed transaction.
- **Salting:** Appending a sub-key to spread a hot key across buckets.
- **Scatter-gather:** See fan-out.
- **Shard:** A distributed partition (on its own server).
- **Skew:** Uneven data/load distribution.
- **Snowflake ID:** 64-bit ID = timestamp + machine/shard + sequence; unique, time-sortable, coordination-free.
- **Tail-latency amplification:** A fan-out query's latency dominated by the slowest shard.
- **Tombstone (Cassandra):** A delete marker that can slow reads if accumulated.
- **Topology service (Vitess):** The metadata store (etcd/ZooKeeper/Consul) holding shard layout.
- **vnode (virtual node):** Multiple ring positions per physical node to smooth distribution and rebalancing.
- **VReplication (Vitess):** Engine that streams rows/changes for resharding and migration.
- **vtgate / vttablet (Vitess):** The router proxy / the per-MySQL sidecar.
- **Vertical partitioning:** Splitting by columns.
- **Vindex (Vitess):** The function mapping a column value to a shard.
- **WAL (write-ahead log):** Sequential durability log written before data pages change.
- **Zone (MongoDB):** A label pinning key ranges to specific shards (used for geo).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Definitions:** Partitioning = split a dataset. Sharding = partitioning across servers (always horizontal). Vertical = by columns; Horizontal = by rows. Functional decomposition = split by domain.

**Strategies:** Range (great range scans, append-hotspot risk) · Hash (even spread, kills range scans) · Consistent hash (adds/removes node ⇒ ~1/N moves; use vnodes) · Directory (flexible, pin whales, +1 hop) · Geo (residency/latency).

**Shard-key rules:** high cardinality · even distribution · query alignment · avoid monotonic for range · immutable.

**Key numbers/defaults:** MongoDB chunk 128 MB (6.0+); CockroachDB range split ~512 MiB; Cassandra `num_tokens` 256 historically / 16 modern; PG `enable_partition_pruning` on, `enable_partitionwise_join/aggregate` off; Cassandra `tombstone_failure_threshold` 100,000.

**Hot-shard fixes:** salt/bucket the key · cache · dedicated shard · sharded counters · pre-split.

**Cross-shard fixes:** co-locate by shard key · reference/broadcast tables · app-side join · denormalize · CQRS read model.

**Don'ts:** shard too early · low-cardinality key · monotonic key + range · `hash % N` · scatter-gather as the norm · per-shard AUTO_INCREMENT · cross-shard txns everywhere.

**Where logic lives:** app-level (control, burden) · middleware Vitess/Citus (transparent, keep MySQL/PG) · native Cassandra/Cockroach/Mongo/Spanner (built-in, complex engine).

**Resharding (online):** create targets → copy snapshot by range → tail binlog catch-up → switch reads → brief write cutover → drop source.

**IDs:** Snowflake / UUIDv7 (time-sortable, coordination-free), not per-shard AUTO_INCREMENT.

### 12.2 Self-test (no answers — recall practice)

1. Your `orders` table is sharded by hash on `customer_id`. Product wants a "recent orders across all customers, newest first, page size 50" feed. Walk through exactly what the router must do, where the latency comes from, and how you'd redesign to make it cheap.
2. You picked `country` as a shard key and now three of your eight shards are 90% empty while two are full. Explain precisely why, what MongoDB would call the unmovable artifact, and your remediation path.
3. Derive why `hash(key) % N` forces a near-total reshuffle when N changes, and show with a small example how consistent hashing reduces moved keys to ~1/N. What do virtual nodes add on top?
4. Describe online resharding step by step for splitting one shard into two with zero (or near-zero) downtime. Identify the single step that requires a brief write pause and why.
5. A single celebrity user is melting one shard despite a well-hashed `user_id`. Enumerate four distinct mitigations and state the cost of each.
6. Justify, for a system you've worked on, when sharding is the wrong answer and which cheaper levers you'd pull first and in what order.
7. Explain how to compute a correct global `AVG(amount)` and a correct global `ORDER BY created_at LIMIT 100` across shards, and name the classic correctness bug in each.
8. Compare application-level, Vitess-style middleware, and native sharding across transparency, resharding effort, and cross-shard transaction support; give one scenario where each is the right pick.

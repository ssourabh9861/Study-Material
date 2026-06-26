# Key-Value Stores

> A definitive engineering-handbook chapter for a senior Java/JVM backend developer who wants to master key-value (KV) stores end to end: design with them, operate and debug them in production, teach them, and answer any interview question on them.

---

## 1. Overview & where it fits

### What a key-value store is

A **key-value store** is the simplest database paradigm: it persists an opaque association from a **key** (a unique identifier, usually a string or byte array) to a **value** (an arbitrary blob — bytes, JSON, a serialized object, a number, a list). Logically it is a giant, durable, distributed `Map<K, V>` (a hash map / dictionary). You `PUT(key, value)`, you `GET(key)`, you `DELETE(key)`. That is the core contract.

The defining trait is that the store treats the value as **opaque** in the basic model: it does not parse the value, does not index its internals, and does not let you query *by* the contents of the value. You query *by the key*. This is what makes KV stores fast and horizontally scalable — and also what makes them awkward for ad-hoc analytical queries.

> **Opaque value:** the database stores and returns the bytes you gave it without understanding their structure. Contrast a relational DB, which knows a row has typed columns it can filter and index on, or a document DB, which parses JSON and can index nested fields. "Opaque" KV is the pure model; many real KV stores (Redis, DynamoDB) relax this with secondary indexes and rich value types — covered later.

### The problem it solves

Most application read/write traffic is **point access by a known identifier**: "get the user with id `u_1042`," "load the cart for session `sess_abc`," "fetch the feature flags for tenant `t_77`." For these, you don't need joins, you don't need SQL's relational algebra, and you don't want the coordination cost of a single primary node. You need:

- **Predictable single-digit-millisecond latency** at the 99th percentile.
- **Horizontal scalability** — add nodes, get more throughput and storage linearly.
- **High availability** — survive node loss without downtime.

A KV store delivers exactly this because the data model makes the system's job trivial to **partition** (split data across machines) and to look up (hash the key, find the node, find the value).

> **Partition / shard:** splitting a dataset across multiple machines so each holds a fraction. The mechanism that lets a database scale beyond one machine's RAM/disk/CPU. KV stores partition naturally because the key alone determines where the data lives — no global secondary structure needs consulting.

> **p99 latency (99th percentile):** if you sort all request latencies, p99 is the value 99% fall under. Tail latency, not the average, is what users and SLAs feel — one slow request in a fan-out of 100 dominates the page load. KV stores are prized for tight tails.

### When you reach for it

Reach for a KV store when **all** of these hold:

- Your access pattern is overwhelmingly **lookup/update by primary key**.
- You need **scale** (throughput or data size) beyond a single relational node, or **very low, predictable latency**.
- You can **denormalize** — store data shaped for how you read it, accepting redundancy.
- You do **not** need rich server-side querying (ad-hoc filters, joins, aggregations) on the hot path.

Classic fits: **caches**, **session stores**, **user/profile stores**, **shopping carts**, **feature flags / config**, **rate limiters & counters**, **leaderboards**, **device/IoT state**, **metadata for object stores**, and as the **storage engine inside** other databases (RocksDB underneath many systems).

### The one-paragraph mental model

> Think of a KV store as a **sharded, replicated, durable hash map**. A key is run through a hash (or compared in a sorted tree) to decide which machine and which on-disk structure holds it; that machine returns the value with no parsing and no joins. You trade SQL's query flexibility for partitionability, predictable latency, and linear scale. The engineering art is **designing the key and the value so that every query you need becomes a key lookup or a tight, single-partition scan** — because the moment you need to "find all X where Y," the pure KV model fights you, and you must add a secondary index, a second copy of the data, or a different database.

### Where it fits among paradigms

| Paradigm | Primary access | Server understands value? | Joins / ad-hoc queries | Scale-out story | Typical latency target |
|---|---|---|---|---|---|
| **Key-Value** | by key | No (opaque) — or limited types | No (or bolt-on secondary index) | Excellent (hash partition) | sub-ms to low-ms |
| **Wide-column** (Cassandra, Bigtable) | by partition key + clustering key | Partially (typed columns) | Limited (within partition) | Excellent | low-ms |
| **Document** (MongoDB) | by id or indexed field | Yes (parses JSON/BSON) | Some (per-collection, limited joins) | Good | low-ms |
| **Relational** (Postgres, MySQL) | by any indexed column | Yes (typed columns) | Full (SQL, joins, aggregates) | Harder (sharding is work) | low-ms to tens-ms |
| **Graph** (Neo4j) | by node/edge traversal | Yes | Traversals | Harder | varies |

Wide-column stores are often described as "KV stores with a two-level key (partition key + sort key) and typed columns." DynamoDB sits right at that boundary — it is marketed as KV but really is a KV/wide-column hybrid, which is why this chapter treats it in depth.

---

## 2. Foundations from first principles

We build the model up from nothing. If you already know hashing and B-trees, skim — but the partitioning and consistency primitives here are load-bearing for everything later.

### 2.1 The abstract data type

The KV abstraction is an **associative array** (a.k.a. map, dictionary):

```
put(key, value)        // insert or overwrite
get(key) -> value|nil  // point read
delete(key)            // remove
```

Real systems add:

- **Conditional writes** — `put if not exists`, `put if value == expected` (compare-and-set). Essential for correctness without locks.
- **Atomic numeric ops** — `incr(key, n)`, `decr`. The store mutates a counter atomically server-side, avoiding read-modify-write races.
- **TTL (time-to-live)** — a key auto-expires after N seconds. Foundational for caches and sessions.
- **Batch / multi-key ops** — `mget([k1,k2,...])`, `batchWrite`.
- **Range / prefix scans** — only in **ordered** KV stores (see 2.4): "give me all keys between A and B."

> **Compare-and-set (CAS) / conditional write:** an atomic "write only if the current value still matches what I last read" operation. It's the lock-free primitive for optimistic concurrency: read value + version, compute new value, write *only if version unchanged*. If someone else wrote in between, the CAS fails and you retry. Hardware exposes `CAS` on CPU words; databases expose it on rows/items.

> **TTL (time-to-live):** an expiry timer on a key. After the TTL elapses, the key is logically gone (and physically reclaimed later). Lets a cache evict stale data and a session store forget idle users without a cleanup job.

### 2.2 Keys

A key must be **unique** within its keyspace and is typically a string or byte array. Good key design is *the* skill of KV modeling. Conventions:

- **Namespacing with separators:** `user:1042:profile`, `cart:sess_abc`. The `:` (Redis convention) or `#`/`|` makes keys self-describing and enables prefix scans in ordered stores.
- **Composite keys** encode multiple dimensions: `order:2024-06-24:u_1042` sorts by date then user (in an ordered store).
- **Avoid hot keys:** a single key hammered by all traffic (e.g. a global counter) becomes a bottleneck because it lives on one partition. Spread with sharding suffixes (`counter:shard:0..N`) and sum on read.

> **Keyspace:** the set of all valid keys in a database (or a logical namespace within it). In Redis a "keyspace" maps to a numbered database (0–15 by default); in DynamoDB it's a table.

### 2.3 Values

Values range from raw bytes to rich structures:

- **Pure blob** (RocksDB, plain Memcached): you serialize; the store is agnostic.
- **Typed values / data structures** (Redis): strings, hashes, lists, sets, sorted sets, streams, bitmaps, HyperLogLog, geo — each with O(1)/O(log n) server-side operations. This blurs the "opaque" line: Redis *does* understand its value types and gives you commands on them.
- **Structured items** (DynamoDB): an item is a set of typed attributes (like a row), addressed by a key; you can project and filter attributes server-side.

The size of values matters enormously for performance and cost (large values blow caches, increase network bytes, and in DynamoDB drive capacity cost). Keep values small; store big blobs in object storage (S3) and keep only a pointer in KV.

### 2.4 Two storage-engine families: hash vs. ordered

This is the single most important structural distinction.

**Unordered (hash-partitioned) KV.** Keys are hashed; the store offers no global ordering. You get O(1) point ops but **no range scans**. Examples: Memcached, DynamoDB partition-key-only access, Redis (unordered keyspace; within a sorted set you do get order). Best when you only ever touch one key at a time.

**Ordered KV.** Keys are kept in sorted order, usually in a **B-tree** or an **LSM-tree** (below). You get point ops **and** range/prefix scans: "all keys with prefix `user:1042:`," "all keys between two timestamps." Examples: RocksDB, LevelDB, FoundationDB, etcd, TiKV, and the *sort key* dimension within a DynamoDB partition. Ordered scans are how you implement "list" and "range" queries on a KV substrate.

> **B-tree / B+ tree:** a balanced, on-disk search tree with high fan-out (each node holds many keys), so lookups touch few disk blocks (typically 3–4 for billions of keys). Keeps keys sorted, supports range scans, and updates **in place**. The workhorse of relational engines (InnoDB, Postgres). Read-optimized; random in-place writes can be slower than append-only designs.

> **LSM-tree (Log-Structured Merge-tree):** a write-optimized structure. Writes go to an in-memory sorted buffer (**memtable**) plus an append-only **write-ahead log**; when the memtable fills it is flushed to an immutable sorted file on disk (**SSTable**). Background **compaction** merges SSTables, discarding overwritten/deleted keys. Reads may check several files (mitigated by **Bloom filters**). LSM trades read amplification for very high, sequential write throughput. Powers RocksDB, Cassandra, ScyllaDB, and DynamoDB's storage layer.

> **SSTable (Sorted String Table):** an immutable file of key-value pairs sorted by key, with a sparse index and often a Bloom filter. Immutable means simple, crash-safe writes and easy replication; the cost is that updates create new versions that compaction must later reconcile.

> **Bloom filter:** a compact probabilistic set membership structure. Asks "is key K possibly in this SSTable?" — answers "definitely no" or "probably yes" (with a tunable false-positive rate). Lets an LSM skip files that can't contain the key, slashing read amplification. Never has false negatives.

> **Memtable:** the in-memory, mutable, sorted write buffer of an LSM-tree (often a skip list or red-black tree). Recent writes live here and are served from RAM until flushed to an SSTable.

> **Write-ahead log (WAL) / commit log:** an append-only on-disk log written *before* the in-memory state is updated, so a crash can be recovered by replaying it. Gives durability to in-memory structures (the memtable) without waiting for a full flush.

> **Compaction:** the LSM background process that merges several SSTables into fewer/larger ones, dropping superseded values and tombstones. Reclaims space and bounds read amplification, at the cost of background CPU/IO ("write amplification").

> **Read / write / space amplification:** the three LSM costs. *Write amplification* = bytes actually written to disk ÷ bytes the app wrote (compaction rewrites data). *Read amplification* = files/lookups per logical read. *Space amplification* = disk used ÷ live data size (old versions awaiting compaction). Tuning an LSM is balancing these three.

> **Tombstone:** a marker recording that a key was deleted. In append-only/replicated systems you can't erase in place, so a delete writes a tombstone that later compaction (or replication reconciliation) honors and eventually purges. Accumulating tombstones (e.g. range-deleting in Cassandra) is a classic performance bug.

### 2.5 Partitioning (sharding)

To scale past one machine you split the keyspace. Two dominant schemes:

**Hash partitioning.** `partition = hash(key) mod N` (or, better, **consistent hashing**). Even load distribution; no range scans across partitions.

> **Consistent hashing:** keys and nodes are mapped onto a hash ring; a key belongs to the next node clockwise. Adding/removing a node only remaps the keys in one arc, not the whole dataset — crucial for elastic clusters. **Virtual nodes** (each physical node owns many ring positions) smooth the distribution and make rebalancing finer-grained. Used by DynamoDB (its design lineage), Cassandra, Riak.

> **Virtual node (vnode):** a physical node pretends to be many small nodes on the hash ring. More, smaller ranges → more even load and faster, less disruptive rebalancing when nodes join/leave.

**Range partitioning.** Contiguous key ranges are assigned to partitions (`A–F` on node 1, `G–M` on node 2, …). Enables cross-partition range scans but risks **hot ranges** (e.g. monotonically increasing timestamps all hit the newest partition). Used by HBase, Bigtable, TiKV, CockroachDB, FoundationDB.

> **Rebalancing:** moving partitions between nodes to even out load or to accommodate added/removed capacity. The hard part of any distributed store: it must happen without dropping requests, ideally without overloading the network. DynamoDB does this automatically and invisibly; Cassandra/Redis Cluster require operator awareness.

### 2.6 Replication

To survive node failure, each partition is copied to **R** replicas.

- **Leader-based (single-leader):** one replica is the leader; writes go there and replicate to followers. Simple consistency, but the leader is a write bottleneck and a failover point. (Redis primary/replica, DynamoDB internally per partition uses a leader.)
- **Multi-leader / leaderless (Dynamo-style):** any replica accepts writes; conflicts are resolved later (last-write-wins, vector clocks, CRDTs). Highly available, but you must handle conflicts. (Classic Amazon Dynamo paper, Riak, Cassandra.)

> **Quorum (R/W/N):** in leaderless replication, with **N** replicas, a read consults **R** of them and a write must ack from **W**. If **W + R > N**, every read overlaps at least one replica that saw the latest write → strong-ish consistency. Tuning R/W trades latency/availability against consistency. (Dynamo, Cassandra `QUORUM`.)

> **Replication lag:** the delay between a write committing on the leader and reaching a follower. Read your write from a lagging follower and you may see stale data — the root of "I updated my profile but it shows the old name" bugs.

### 2.7 Consistency models

> **Strong consistency (linearizability):** every read returns the most recent committed write, as if there were a single copy of the data and operations happened in a single global order. Easiest to reason about; costs latency and availability (you may have to wait for a quorum or a leader).

> **Eventual consistency:** if writes stop, all replicas *eventually* converge, but a read right after a write may return stale data. Cheap and highly available; demands the app tolerate staleness or use read-your-writes tricks.

> **Read-your-writes (read-after-write) consistency:** a weaker-than-strong guarantee that a client always sees its *own* prior writes (even if others' writes lag). Often the practical sweet spot for user-facing apps.

> **CAP theorem:** during a **network partition** (P) — when nodes can't all talk — a distributed store must choose **C**onsistency *or* **A**vailability; it cannot have both. There's no partition-free option in the real world, so the real choice is CP (refuse some requests to stay consistent) vs AP (serve possibly-stale data to stay up). KV stores famously span the spectrum: DynamoDB lets you pick per-request (eventual = AP-ish, strong = CP-ish); Redis is CP-leaning by default.

> **PACELC:** an extension of CAP: if Partitioned, choose A or C (PAC); **E**lse (normal operation), choose **L**atency or **C**onsistency (ELC). It captures that even with no partition you still trade latency for consistency (a quorum read is slower than a local read). DynamoDB: PA/EL by default (available + low-latency, eventually consistent), with an opt-in for C.

### 2.8 Durability & memory tiers

- **In-memory** (Memcached, Redis default): data in RAM, fast, volatile unless persisted.
- **In-memory with persistence** (Redis with RDB snapshots / AOF log): RAM-speed reads, disk for recovery.
- **Disk/SSD-backed with cache** (DynamoDB, Aerospike hybrid, RocksDB): data primarily on SSD, hot data cached in RAM. Scales storage beyond RAM cheaply.

> **Persistence (RDB vs AOF in Redis):** **RDB** = periodic point-in-time binary snapshots of the whole dataset (compact, fast restart, can lose the seconds since the last snapshot). **AOF (Append-Only File)** = log every write command; replay on restart (more durable, larger files, slower restart). Many run both. Covered in the Redis toolkit.

With the primitives defined, we go inside the engines.

---

## 3. How it works internally

We trace four real systems' internals because "KV store" hides wildly different machinery: **RocksDB** (an embedded LSM engine — the substrate of many others), **Redis** (in-memory data-structure server), **DynamoDB** (managed, partitioned, Dynamo-lineage), and **Aerospike** (hybrid RAM-index + SSD). Understanding RocksDB first pays off because DynamoDB, several others, and many homegrown stores are LSM under the hood.

### 3.1 RocksDB: the LSM write & read path, step by step

RocksDB is an **embedded** library (you link it into your process; there is no server) created at Facebook, forked from Google's LevelDB. It is the canonical modern LSM KV engine. The JVM uses it via JNI bindings (`rocksdbjni`); it underlies Kafka Streams state stores, Flink, CockroachDB (historically), TiKV, and countless internal systems.

#### Write path (`Put(key, value)`)

1. **Acquire sequence number.** RocksDB assigns a monotonically increasing **sequence number** to the write — its global ordering and the basis for snapshots/MVCC.

   > **Sequence number:** a per-database monotonically increasing integer stamped on every write. It lets reads at a given snapshot ignore newer writes (MVCC) and lets compaction know which version is newer.

2. **Append to the WAL.** The write is serialized and appended to the on-disk write-ahead log (sequential IO — fast). If `sync=true` (or WAL fsync configured), it's fsync'd here, guaranteeing durability before returning. By default many setups don't fsync per write (they batch), trading a small durability window for throughput.

3. **Insert into the active memtable.** The key-value goes into the in-memory sorted structure (default: a skip list). Reads can now see it.

   > **Skip list:** a probabilistic ordered structure with O(log n) search/insert, built from layered linked lists. RocksDB's default memtable. Lock-friendly for concurrent inserts.

4. **Return success.** The data is durable (WAL) and visible (memtable). No disk seek for the value yet.

5. **Memtable fills → becomes immutable.** When the active memtable hits `write_buffer_size` (default **64 MB**), it's frozen (immutable memtable) and a new active memtable is created. Writes continue uninterrupted.

6. **Flush.** A background thread writes the immutable memtable to a new **L0 SSTable** on disk and drops the corresponding WAL segment. L0 SSTables may have overlapping key ranges (they're just flushed memtables).

7. **Compaction.** Background threads merge SSTables down a level hierarchy (L0 → L1 → … → L6 in **leveled compaction**, the default). Each lower level is ~10× larger and holds **non-overlapping** sorted runs. Compaction merges overlapping inputs, keeps the newest version of each key, drops tombstoned/expired keys, and writes new SSTables.

#### Read path (`Get(key)`)

1. **Check the active memtable**, then **immutable memtables** (newest data first).
2. **Check L0 SSTables** (newest to oldest; they may overlap, so possibly several).
3. **Descend levels L1…L6.** Within each leveled tier the runs are non-overlapping, so at most one SSTable per level can contain the key — found via the level's index. Each candidate SSTable is **Bloom-filter-tested first**: if the filter says "no," skip it without touching data blocks.
4. **Block cache.** Data and index blocks are cached in the **block cache** (RAM, default LRU). A hot read is served from RAM; a miss reads an SSTable block from SSD (and caches it).

   > **Block cache:** RocksDB's in-RAM cache of uncompressed (or compressed) SSTable blocks. The main read-performance lever. Sizing it well is the difference between RAM-speed and SSD-speed reads.

5. **Return the newest version** (highest sequence number ≤ the read snapshot) or "not found" / honor a tombstone.

#### Why this shape

Writes are append-only and sequential (WAL + memtable flush), so write throughput is enormous and SSD-friendly (no random in-place updates that wear flash and seek). The price is **read amplification** (a read may probe memtable + multiple SSTables) and **write/space amplification** (compaction rewrites data). Bloom filters and the block cache claw back read performance; compaction strategy tuning bounds the amplifications.

#### Column families & MVCC

RocksDB supports **column families** — independent keyspaces within one DB sharing a WAL but with separate memtables/SSTables and configs (e.g. one CF for indexes, one for data). Snapshots provide a consistent read view at a sequence number (MVCC), so readers never block writers.

> **MVCC (Multi-Version Concurrency Control):** keep multiple versions of a value (tagged by sequence/timestamp) so readers see a consistent snapshot without locking out writers, and vice versa. The reason LSM and many SQL engines deliver high concurrency. Old versions are reclaimed by compaction/vacuum once no reader needs them.

### 3.2 Redis: single-threaded event loop, data structures, persistence

Redis is an **in-memory data-structure server**. Internals worth knowing:

#### Execution model

Classic Redis executes **commands on a single thread** via an event loop (`epoll`/`kqueue`). Each command runs to completion atomically — *no two commands interleave* — which is why Redis has no read locks and why `INCR`, `LPUSH`, `SETNX` are atomic for free. The tradeoff: one slow command (e.g. `KEYS *`, a big `SORT`, a Lua script in a loop) blocks **everything**.

> **Event loop / epoll:** a single thread that asks the kernel "which of these thousands of sockets have data?" (`epoll` on Linux, `kqueue` on BSD/macOS) and processes them one at a time. Avoids thread-per-connection overhead; achieves huge connection counts with minimal CPU — but serializes work, so each handler must be fast.

Redis 6+ adds **I/O threads** for *reading/parsing requests and writing replies* (network), but command *execution* of the core data path remains single-threaded. (Redis 7 / Redis 8 and forks like KeyDB/Valkey push further into multithreading; flag the version when it matters.)

#### Data structures (the "values")

Redis stores typed values, each a tuned in-memory structure with adaptive encodings:

- **String** — bytes/int; backing for `SET`, `INCR`, bitmaps, `SETRANGE`.
- **Hash** — field→value map (e.g. a user object); small hashes use a compact **listpack/ziplist** encoding, large ones a hash table.
- **List** — `quicklist` (linked list of listpacks); deque ops.
- **Set** — `intset` (all-integer, sorted) or hash table.
- **Sorted Set (ZSet)** — member→score, kept ordered by score in a **skip list + hash table** combo → O(log n) rank/range queries. The basis of **leaderboards**, priority queues, time-series windows.
- **Stream** — append-only log with consumer groups (Kafka-lite).
- **HyperLogLog / Bitmap / Geo** — probabilistic cardinality, bit ops, geospatial.

> **Listpack / ziplist:** a compact, cache-friendly serialization of small collections into a single contiguous blob, saving memory and pointer-chasing. Redis auto-upgrades to a real hash table/skip list past size thresholds (`hash-max-listpack-entries`, etc.).

#### Expiration

TTL'd keys are reclaimed two ways: **lazy** (on access, if expired, delete and return nil) and **active** (a background cycle samples keys with TTLs and evicts expired ones, ~10×/sec, sampling to bound CPU). So an expired key may linger in RAM briefly until sampled — relevant for memory accounting.

#### Persistence (durability)

- **RDB:** `fork()` a child process that writes a point-in-time snapshot using copy-on-write memory (parent keeps serving). Compact, fast restart; you can lose writes since the last snapshot.
- **AOF:** append each write command to a log; `fsync` policy is `everysec` (default — at most ~1s loss), `always` (slowest, safest), or `no` (OS-decides). AOF is periodically **rewritten** (compacted) to bound size.
- **Hybrid (default in modern Redis):** RDB preamble + AOF tail — fast restart and small data-loss window.

> **fork() + copy-on-write (COW):** the OS clones a process cheaply by sharing memory pages; a page is physically copied only when one side writes it. Redis uses this so the snapshot child sees a frozen view while the parent keeps mutating. Caveat: heavy writes during a snapshot cause many page copies → a memory spike (can OOM if `maxmemory` is set tight).

#### Clustering & replication

- **Replication:** primary streams its command/replication backlog to replicas (async by default → replicas can lag; reading from a replica is eventually consistent).
- **Redis Cluster:** the keyspace is split into **16384 hash slots**; `slot = CRC16(key) mod 16384`. Each primary owns a slot range. Clients are cluster-aware (cache the slot→node map; follow `MOVED`/`ASK` redirects). Multi-key ops require keys in the **same slot**, achieved via **hash tags** `{...}` (only the substring in braces is hashed): `user:{1042}:profile` and `user:{1042}:cart` co-locate.

> **Hash slot:** Redis Cluster's fixed 16384 buckets; keys map to a slot, slots map to nodes. Decouples key→slot (stable) from slot→node (movable), so rebalancing moves slots, not rehashes keys.

> **Hash tag `{}`:** force multiple keys onto the same slot by hashing only the braced substring — required for multi-key commands/transactions in a cluster.

> **Redis Sentinel:** a separate HA mechanism (not Cluster) that monitors a primary + replicas, performs automatic failover (promote a replica) and tells clients the new primary. Used for HA without sharding.

### 3.3 DynamoDB: managed partitioned KV/wide-column

DynamoDB is AWS's fully managed NoSQL store, lineage from the 2007 Amazon **Dynamo** paper but a distinct, hosted product. You never see nodes; AWS manages partitioning, replication, and scaling. Internals as exposed to you:

#### Data & key model

- A **table** holds **items** (rows). Each item is a set of typed **attributes**.
- The **primary key** is either:
  - **Partition key only** (a.k.a. hash key) — `HASH`. Determines the partition.
  - **Partition key + sort key** (a.k.a. composite/range key) — `HASH` + `RANGE`. The partition key picks the partition; the **sort key orders items within that partition**, enabling range queries inside a partition.

> **Partition key (hash key):** the attribute DynamoDB hashes to choose which physical partition stores the item. All items with the same partition key live together (and share that partition's throughput).

> **Sort key (range key):** the second key attribute; within a partition, items are stored **sorted by sort key**, so you can `Query` ranges (`begins_with`, `between`, `<`, `>`) cheaply. This is what turns DynamoDB from pure KV into a one-to-many, query-able store.

#### Storage & replication internals

- DynamoDB stores data on SSD and replicates each partition across **3 Availability Zones** synchronously to a quorum for durability. There's a leader per partition for strongly-consistent reads/writes.

> **Availability Zone (AZ):** an isolated datacenter within an AWS Region (independent power/cooling/network). Replicating across AZs survives a datacenter failure. A Region (e.g. `us-east-1`) contains multiple AZs.

- **Partitions** are the unit of capacity and storage. A single physical partition holds up to ~**10 GB** and serves up to ~**3000 read capacity units (RCU)** and **1000 write capacity units (WCU)**. Exceed either and DynamoDB **splits** the partition (by key range or by throughput) automatically.

#### Capacity & request lifecycle

Every request consumes **capacity units**:

> **RCU (Read Capacity Unit):** 1 RCU = one **strongly consistent** read of up to **4 KB/sec**, or **two eventually consistent** reads of 4 KB/sec, or ½ a transactional read. Larger items consume proportionally more (rounded up to 4 KB).

> **WCU (Write Capacity Unit):** 1 WCU = one write of up to **1 KB/sec**. Larger items consume more (rounded up to 1 KB). Transactional writes cost 2×.

Lifecycle of a `GetItem`:
1. SDK signs the request (SigV4) and sends HTTPS to the regional endpoint.
2. The request router hashes the partition key → locates the partition's nodes.
3. For an **eventually consistent** read, any replica answers (lower latency, possibly stale). For a **strongly consistent** read, the leader answers.
4. Capacity is metered; if you're over your provisioned/burst capacity, the request is **throttled** (`ProvisionedThroughputExceededException`) and the SDK retries with backoff.

> **Throttling:** the store rejecting requests that exceed allotted capacity, to protect itself. The client must back off and retry. In DynamoDB, throttling is the #1 operational surprise and usually signals a hot partition or under-provisioning.

#### Consistency options (per request)

- **Eventually consistent reads** (default): may not reflect a just-completed write; cheapest; lowest latency.
- **Strongly consistent reads** (`ConsistentRead=true`): reflect all prior writes; cost 2× the RCU; only within a Region (not on GSIs or global tables' remote regions).
- **Transactions** (`TransactWriteItems`/`TransactGetItems`): ACID across up to 100 items, 2× cost.

#### Secondary indexes

- **LSI (Local Secondary Index):** same partition key, **different sort key**. Created **at table creation only**; shares the table's partition (and counts against the 10 GB-per-partition-key limit). Supports strongly consistent reads.
- **GSI (Global Secondary Index):** **different partition key and/or sort key** — effectively a separate, asynchronously-maintained copy of the table reorganized by a new key. Eventually consistent only; has its **own** capacity (RCU/WCU). Create/delete anytime.

> **Eventual maintenance of a GSI:** writes to the base table propagate to GSIs asynchronously, so a GSI read can lag the base table by a short window. A throttled GSI can also back up base-table writes — covered in failure modes.

#### Capacity modes

- **Provisioned:** you set RCU/WCU (optionally with **auto-scaling** that adjusts within bounds). Cheapest for steady, predictable load. Has **burst capacity** (a small bank of unused capacity) and **adaptive capacity** (automatically shifts capacity toward hot partitions).
- **On-Demand (pay-per-request):** no capacity planning; you pay per request; instantly absorbs spikes. Best for spiky/unknown load; ~6–7× the per-unit price of provisioned for sustained load.

> **Adaptive capacity:** DynamoDB's automatic redistribution of throughput toward partitions receiving disproportionate traffic, and **isolation** (splitting a hot key's partition). It mitigates — but does not eliminate — hot-partition problems; a single hot **key** still caps at one partition's limits.

#### Streams & TTL

- **DynamoDB Streams:** an ordered change-data-capture log of item modifications (24 h retention), consumable by Lambda for event-driven pipelines, replication, search indexing.
- **TTL:** mark an attribute as the expiry epoch; DynamoDB deletes expired items in a background sweep (within ~48 h, not instantly — design around the lag).

> **CDC (Change Data Capture):** a stream of inserts/updates/deletes emitted by a database so downstream systems (search index, cache, analytics, another region) stay in sync without polling. DynamoDB Streams and Redis keyspace notifications are CDC mechanisms.

### 3.4 Aerospike: hybrid index-in-RAM, data-on-SSD

Aerospike targets extreme throughput at low latency and cost by keeping the **primary index in RAM** (64 bytes/record) and **data on SSD**, with a log-structured layout that talks to raw flash devices (bypassing the filesystem) for predictable IO.

- **Namespace** ≈ a database/storage policy unit; contains **sets** (≈ tables) of **records** addressed by key.
- **Smart partitioning:** keys hash (RIPEMD-160) into **4096 partitions** distributed across nodes; the **Smart Client** computes the partition and contacts the owning node directly (no proxy hop).
- **Replication:** synchronous to replica nodes; **Strong Consistency mode** (opt-in) gives linearizable single-record ops via a roster + master-handoff protocol; default mode is AP-leaning.
- **Hybrid Memory:** index in RAM for O(1) lookup → data read in a single SSD IO. This is why Aerospike sustains millions of ops/sec/node at sub-millisecond latency on modest hardware.

With internals in hand, the next sections enumerate the concrete toolkit and code.

---

## 4. The complete toolkit

Below: the operations, classes, APIs, CLI commands, and configuration that a working engineer reaches for. Tables list purpose, key parameters, and defaults. Version/vendor specifics are flagged.

### 4.1 Generic KV operations (conceptual)

| Operation | Semantics | Notes / gotchas |
|---|---|---|
| `GET key` | point read | nil if absent; may be stale under eventual consistency |
| `PUT/SET key value` | upsert | overwrites silently — use conditional form to avoid clobbering |
| `DELETE key` | remove | idempotent; may leave a tombstone in LSM systems |
| `SETNX` / put-if-absent | write only if key absent | distributed-lock and "claim" primitive |
| `CAS` / put-if-match-version | write only if version matches | optimistic concurrency |
| `INCR/DECR key [n]` | atomic counter | avoids read-modify-write races |
| `MGET/MSET` / batch | multi-key in one round-trip | in clusters, keys may span nodes → driver fans out |
| `EXPIRE key ttl` / TTL on put | auto-expiry | active vs lazy reclamation differs per engine |
| `SCAN`/range query | iterate keys, optionally by prefix/range | only meaningful in ordered stores; use cursor, never `KEYS *` in prod |

### 4.2 Redis command toolkit (most-used)

| Command | Purpose | Key params / defaults |
|---|---|---|
| `SET k v [EX s\|PX ms] [NX\|XX] [GET] [KEEPTTL]` | set string + optional TTL/conditions | `NX`=only if absent, `XX`=only if present; `EX` seconds |
| `GET k` / `MGET k...` | read one / many | — |
| `INCR k` / `INCRBY k n` / `INCRBYFLOAT` | atomic counters | overflow on 64-bit int |
| `EXPIRE k s` / `PEXPIRE` / `TTL k` / `PERSIST k` | manage TTL | `TTL` returns -1 (no expiry) / -2 (no key) |
| `HSET k f v` / `HGET` / `HGETALL` / `HMGET` | hash (object) fields | small hashes use listpack encoding |
| `LPUSH/RPUSH/LPOP/RPOP/LRANGE/BLPOP` | list / queue | `BLPOP` blocks → simple work queue |
| `SADD/SREM/SISMEMBER/SINTER/SUNION` | sets | `SINTERSTORE` for materialized intersections |
| `ZADD k score m` / `ZRANGE`/`ZRANGEBYSCORE`/`ZRANK`/`ZINCRBY` | sorted set (leaderboard, time index) | `ZRANGE ... REV` for descending |
| `SETBIT/GETBIT/BITCOUNT` | bitmaps | space-efficient flags/presence |
| `PFADD/PFCOUNT` | HyperLogLog cardinality | ~0.81% error, 12 KB/HLL |
| `XADD/XREADGROUP/XACK` | streams + consumer groups | at-least-once processing |
| `EXPIRE`+`SET NX` / `SET k v NX EX 30` | distributed lock | prefer Redlock/`SET NX PX` + fencing token |
| `EVAL script numkeys k... arg...` | Lua script (atomic, server-side) | runs atomically; keep it fast |
| `MULTI/EXEC/WATCH` | transaction + optimistic lock | `WATCH` = CAS on keys; no rollback semantics |
| `SCAN cursor [MATCH p] [COUNT n]` | cursor iteration | **never** `KEYS *` in prod (blocks) |
| `INFO` / `MEMORY USAGE k` / `SLOWLOG GET` | introspection | ops & debugging |
| `OBJECT ENCODING k` | inspect internal encoding | confirms listpack vs hashtable |
| `CLIENT NO-EVICT` / `WAIT n ms` | client/replication controls | `WAIT` blocks until N replicas ack |

Key Redis config (`redis.conf` / `CONFIG SET`):

| Config | Purpose | Default (typical) |
|---|---|---|
| `maxmemory` | RAM cap | 0 (unlimited — dangerous) |
| `maxmemory-policy` | eviction when full | `noeviction` (writes error!); use `allkeys-lru`/`allkeys-lfu`/`volatile-ttl` for caches |
| `appendonly` | enable AOF | `no` |
| `appendfsync` | AOF fsync policy | `everysec` |
| `save` | RDB snapshot triggers | `3600 1 300 100 60 10000` |
| `hash-max-listpack-entries`/`-value` | hash encoding threshold | 128 / 64 |
| `timeout` | idle client close (s) | 0 (never) |
| `tcp-keepalive` | TCP keepalive | 300 |
| `io-threads` | network I/O threads (Redis 6+) | 1 |

> **Eviction policy:** what Redis does when `maxmemory` is hit. `noeviction` *rejects writes* (a classic outage cause when people forget to set a cache policy). `allkeys-lru`/`-lfu` evict least-recently/frequently-used keys; `volatile-*` only evict keys with a TTL. **Choose one explicitly for caches.**

### 4.3 DynamoDB API toolkit

| API | Purpose | Key params |
|---|---|---|
| `PutItem` | insert/overwrite item | `ConditionExpression` for conditional writes |
| `GetItem` | point read by full key | `ConsistentRead` (default false), `ProjectionExpression` |
| `UpdateItem` | mutate attributes atomically | `UpdateExpression` (`SET/ADD/REMOVE`), `ConditionExpression`, atomic counters via `ADD` |
| `DeleteItem` | delete | `ConditionExpression` |
| `Query` | items by partition key (+ sort key range) | `KeyConditionExpression`, `FilterExpression`, `ScanIndexForward`, `Limit`, `ExclusiveStartKey` |
| `Scan` | read entire table/index | expensive; use `Segment/TotalSegments` for parallel scan; avoid on hot path |
| `BatchGetItem` / `BatchWriteItem` | up to 100 gets / 25 writes | partial failures → retry `UnprocessedKeys/Items` |
| `TransactWriteItems` / `TransactGetItems` | ACID across ≤100 items | 2× capacity; all-or-nothing |
| `DescribeTable` / `UpdateTable` | schema, capacity, indexes | change capacity mode, add GSIs |

> **Expressions:** DynamoDB uses string mini-languages. `KeyConditionExpression` filters on the key (uses the index — efficient). `FilterExpression` filters **after** reading (you still pay capacity for the read!). `ProjectionExpression` returns a subset of attributes. `ConditionExpression` gates writes (optimistic concurrency: `attribute_not_exists(pk)` or `version = :v`). Placeholders `#name`/`:value` dodge reserved words and inject values.

> **Pagination cursor (`ExclusiveStartKey`/`LastEvaluatedKey`):** `Query`/`Scan` return at most 1 MB; a `LastEvaluatedKey` tells you to call again with it as `ExclusiveStartKey` to continue. Forgetting to paginate = silently missing data.

Key DynamoDB knobs:

| Knob | Purpose | Default/notes |
|---|---|---|
| Capacity mode | Provisioned vs On-Demand | choose per workload shape |
| `ConsistentRead` | strong vs eventual | false (eventual) |
| Auto-scaling target utilization | provisioned scaling | ~70% |
| TTL attribute | auto-expire items | off; deletes within ~48 h |
| Streams view type | CDC payload | `KEYS_ONLY`/`NEW_IMAGE`/`OLD_IMAGE`/`NEW_AND_OLD_IMAGES` |
| PITR | point-in-time recovery | off; 35-day continuous backup |
| Global Tables | multi-region active-active | last-writer-wins conflict resolution |

### 4.4 RocksDB (Java) toolkit

| Class / method | Purpose |
|---|---|
| `RocksDB.open(options, path)` | open DB (load native lib via `RocksDB.loadLibrary()` first) |
| `put(key, value)` / `get(key)` / `delete(key)` | core ops (byte arrays) |
| `WriteBatch` + `write(writeOptions, batch)` | atomic multi-key batch |
| `RocksIterator` (`seek`, `seekToFirst`, `next`) | ordered range scans |
| `ColumnFamilyHandle` | independent keyspaces |
| `Snapshot` + `ReadOptions.setSnapshot` | consistent point-in-time reads (MVCC) |
| `Options.setWriteBufferSize` | memtable size (default 64 MB) |
| `BlockBasedTableConfig.setBlockCache` / `setFilterPolicy(BloomFilter)` | read perf: cache + Bloom |
| `Options.setCompressionType` | e.g. `LZ4` / `ZSTD` |
| `Options.setMaxBackgroundJobs` | compaction/flush parallelism |
| `Options.setCompactionStyle` | `LEVEL` (default) / `UNIVERSAL` / `FIFO` |

### 4.5 Java client libraries (cheat list)

| Store | Java client | Notes |
|---|---|---|
| Redis | **Lettuce** (async/reactive, Netty), **Jedis** (simple, pooled), **Redisson** (high-level: distributed locks, maps, collections) | Lettuce default for reactive/Spring; Redisson for Java distributed objects |
| DynamoDB | **AWS SDK v2** (`DynamoDbClient`, `DynamoDbEnhancedClient` for POJO mapping) | Enhanced client gives annotation-based mapping |
| RocksDB | `org.rocksdb:rocksdbjni` | JNI; manage native resources (close handles) |
| Aerospike | `aerospike-client` (Java) | Smart Client computes partitions |
| Memcached | **SpyMemcached** / **XMemcached** | simple cache only |

---

## 5. Code examples by use case

Idiomatic, runnable/adaptable Java unless a CLI/config is clearer. Non-obvious lines are commented.

### 5.1 Cache-aside with Redis (Lettuce) — the workhorse pattern

```java
// Maven: io.lettuce:lettuce-core
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.SetArgs;

public class UserCache {
    private final RedisCommands<String, String> redis;
    private final UserRepository db;            // your source of truth (SQL/Dynamo)
    private final ObjectMapper json = new ObjectMapper();

    public UserCache(RedisClient client, UserRepository db) {
        StatefulRedisConnection<String, String> conn = client.connect();
        this.redis = conn.sync();               // synchronous API; use .async()/.reactive() for non-blocking
        this.db = db;
    }

    public User getUser(String id) throws Exception {
        String key = "user:" + id;              // namespaced key
        String cached = redis.get(key);
        if (cached != null) {                   // CACHE HIT
            return json.readValue(cached, User.class);
        }
        // CACHE MISS -> load from system of record
        User user = db.findById(id);
        if (user == null) {
            // Negative caching: remember "absent" briefly to stop a hot miss hammering the DB
            redis.set(key, "", SetArgs.Builder.nx().ex(30));
            return null;
        }
        // Populate cache with a TTL so stale data self-heals; jitter avoids synchronized expiry (thundering herd)
        long ttl = 300 + ThreadLocalRandom.current().nextInt(60);   // 5min +/- jitter
        redis.set(key, json.writeValueAsString(user), SetArgs.Builder.ex(ttl));
        return user;
    }

    public void updateUser(User user) throws Exception {
        db.save(user);                          // write-through to source of truth first
        // Then INVALIDATE rather than update the cache, to avoid race with concurrent reads
        redis.del("user:" + user.getId());      // next read repopulates fresh
    }
}
```

Key lessons: **cache-aside** (app manages the cache, source of truth elsewhere), **TTL with jitter** (avoid synchronized expiry → thundering herd), **negative caching** (cache misses too), and **invalidate-on-write** (deleting is safer than updating under concurrency).

> **Cache stampede / thundering herd:** when a popular key expires, thousands of concurrent requests miss simultaneously and all hit the database at once. Mitigations: TTL jitter, a per-key lock so only one caller recomputes (`SET NX` "lease"), or serving stale-while-revalidate.

### 5.2 Atomic rate limiter (fixed window) with a Lua script

Doing `GET`+`INCR`+`EXPIRE` as separate commands races. A Lua script runs atomically on the single Redis thread.

```java
// Lua: increment a per-user-per-window counter; set TTL on first hit; return current count.
private static final String RATE_LIMIT_LUA =
    "local c = redis.call('INCR', KEYS[1]) " +
    "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " + // set window TTL only on first increment
    "return c";

public boolean allow(String userId, int limit, long windowMs) {
    long bucket = System.currentTimeMillis() / windowMs;       // fixed-window bucket id
    String key = "rl:" + userId + ":" + bucket;                // key rotates each window
    Long count = redis.eval(RATE_LIMIT_LUA, ScriptOutputType.INTEGER,
                            new String[]{ key }, String.valueOf(windowMs));
    return count <= limit;                                      // reject when over limit
}
```

For smoother limiting use a **sliding-window** or **token-bucket** variant (also expressible in one Lua script). The point: **push read-modify-write atomicity to the server**.

### 5.3 Distributed lock with fencing (Redisson) — and the caveat

```java
// Maven: org.redisson:redisson
RLock lock = redisson.getLock("lock:invoice:" + invoiceId);
// waitTime=5s to acquire, leaseTime=10s auto-release (prevents deadlock if holder dies)
if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
    try {
        processInvoice(invoiceId);  // critical section
    } finally {
        lock.unlock();              // only unlocks if WE hold it (Redisson tracks owner)
    }
}
```

> **Fencing token:** a monotonically increasing number handed out with a lock. Even if a paused holder wakes up after its lease expired and another client grabbed the lock, the storage layer rejects the stale holder's write because its token is lower than the latest. **Redis-based locks are not safe under GC pauses/clock skew without fencing** (see Martin Kleppmann's critique of Redlock). For correctness-critical mutual exclusion, prefer a consensus system (ZooKeeper/etcd) or a DB conditional write with a version.

> **ZooKeeper / etcd:** strongly-consistent coordination services (built on consensus protocols — ZAB / Raft) used for distributed locks, leader election, and config. They prioritize correctness (CP) over raw throughput, so they're the right tool when a lock *must* be exclusive.

> **Raft / consensus:** an algorithm letting a cluster agree on a single ordered log of operations despite failures, by electing a leader and requiring a majority to commit each entry. Underpins etcd, Consul, CockroachDB, and many CP KV stores. Guarantees linearizability at the cost of needing a majority alive.

### 5.4 Leaderboard with a Redis sorted set

```java
// Add/update a player's score (ZADD is idempotent upsert by member)
redis.zadd("leaderboard:weekly", 1500.0, "player:42");
redis.zincrby("leaderboard:weekly", 50.0, "player:42"); // atomic increment

// Top 10, highest first
List<ScoredValue<String>> top = redis.zrevrangeWithScores("leaderboard:weekly", 0, 9);

// A player's rank (0-based, descending) and score
Long rank = redis.zrevrank("leaderboard:weekly", "player:42");
Double score = redis.zscore("leaderboard:weekly", "player:42");
```

A sorted set gives O(log n) rank queries — something painful and slow in a relational DB at scale, trivial here. This is a textbook case where a KV/data-structure store crushes SQL.

### 5.5 DynamoDB: conditional write (optimistic locking) + atomic counter

```java
// AWS SDK v2
DynamoDbClient ddb = DynamoDbClient.create();

// Optimistic locking: only write if the version we read is still current; bump version
UpdateItemRequest req = UpdateItemRequest.builder()
    .tableName("Orders")
    .key(Map.of("pk", AttributeValue.fromS("ORDER#1001")))
    .updateExpression("SET #st = :new, version = version + :one")
    .conditionExpression("version = :expected")               // CAS: fails if someone else wrote
    .expressionAttributeNames(Map.of("#st", "status"))        // 'status' is reserved -> alias
    .expressionAttributeValues(Map.of(
        ":new", AttributeValue.fromS("SHIPPED"),
        ":one", AttributeValue.fromN("1"),
        ":expected", AttributeValue.fromN("3")))
    .build();
try {
    ddb.updateItem(req);
} catch (ConditionalCheckFailedException e) {
    // Lost the race: re-read, recompute, retry
}

// Atomic counter (no read needed): ADD is atomic server-side
ddb.updateItem(b -> b.tableName("Stats")
    .key(Map.of("pk", AttributeValue.fromS("page#home")))
    .updateExpression("ADD views :one")
    .expressionAttributeValues(Map.of(":one", AttributeValue.fromN("1"))));
```

### 5.6 DynamoDB single-table design: model + access patterns

Single-table design packs multiple entity types into one table, using overloaded `pk`/`sk` so related items co-locate and one `Query` returns a whole object graph. Suppose: users, their orders, and order line items.

| Entity | pk | sk | other attrs |
|---|---|---|---|
| User | `USER#u1042` | `PROFILE` | name, email |
| Order | `USER#u1042` | `ORDER#2024-06-24#o500` | total, status |
| LineItem | `ORDER#o500` | `ITEM#sku123` | qty, price |

Access patterns served:

```java
// 1) Get a user's profile + all their orders in ONE query (same partition)
QueryRequest q = QueryRequest.builder()
    .tableName("App")
    .keyConditionExpression("pk = :u AND begins_with(sk, :prefix)")
    .expressionAttributeValues(Map.of(
        ":u", AttributeValue.fromS("USER#u1042"),
        ":prefix", AttributeValue.fromS("ORDER#")))           // sort-key prefix scan
    .scanIndexForward(false)                                  // newest orders first (sk encodes date)
    .build();
// 2) Get all line items for an order: pk = ORDER#o500, begins_with(sk, 'ITEM#')
// 3) Get just the profile: pk = USER#u1042, sk = PROFILE  (GetItem)
```

A **GSI** adds an orthogonal access path, e.g. "all orders with status SHIPPED":

```
GSI1:  GSI1PK = "STATUS#SHIPPED"   GSI1SK = "2024-06-24#o500"
       -> Query GSI1 by GSI1PK to list shipped orders chronologically
```

> **Single-table design:** the DynamoDB idiom of storing many entity types in one table with generic key names (`pk`, `sk`, `GSI1PK`…), so each application access pattern maps to a single efficient `Query`. It trades schema readability for performance (fewer round-trips, no joins) and is justified *because DynamoDB has no joins* — you pre-join by co-locating. Controversial; multi-table is fine when access patterns are simple.

### 5.7 RocksDB embedded store with range scan (Java)

```java
import org.rocksdb.*;

RocksDB.loadLibrary();                                     // load native JNI lib once
try (Options opts = new Options().setCreateIfMissing(true)
         .setWriteBufferSize(64 * 1024 * 1024)) {         // 64MB memtable
    BlockBasedTableConfig table = new BlockBasedTableConfig()
        .setBlockCache(new LRUCache(256 * 1024 * 1024))   // 256MB read cache
        .setFilterPolicy(new BloomFilter(10));            // ~1% FP, fewer SSTable probes
    opts.setTableFormatConfig(table);

    try (RocksDB db = RocksDB.open(opts, "/data/events")) {
        // Keys are time-ordered for range scans: "evt#<epochMillis>#<id>"
        db.put(("evt#" + 1719230000000L + "#a").getBytes(), "payloadA".getBytes());
        db.put(("evt#" + 1719230005000L + "#b").getBytes(), "payloadB".getBytes());

        // Range scan: all events in a time window via ordered iterator
        try (RocksIterator it = db.newIterator()) {
            for (it.seek("evt#1719230000000".getBytes());   // seek to range start
                 it.isValid() && new String(it.key()).compareTo("evt#1719230006000") < 0;
                 it.next()) {
                System.out.println(new String(it.key()) + " => " + new String(it.value()));
            }
        }
    }
}
```

This is exactly how Kafka Streams keeps local state and how you'd build a low-latency embedded time-series index — ordered keys turn a KV store into a range-queryable log.

### 5.8 Session store with TTL + read-your-writes (Redis)

```java
// On login: create a session that auto-expires after 30 min of inactivity
String sid = UUID.randomUUID().toString();
redis.hset("sess:" + sid, Map.of("uid", userId, "csrf", csrfToken));
redis.expire("sess:" + sid, 1800);                  // sliding window handled by re-EXPIRE on each request

// On each request: validate + slide the window atomically-ish
Map<String,String> sess = redis.hgetall("sess:" + sid);
if (sess.isEmpty()) throw new UnauthorizedException();
redis.expire("sess:" + sid, 1800);                  // refresh TTL = sliding expiry
```

Sessions are the canonical KV use case: keyed by an opaque id, small value, TTL-driven lifecycle, no query needed.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Minimize round-trips.** Batch (`MGET`, `BatchGetItem`, `WriteBatch`), pipeline (Redis pipelining sends many commands without waiting for each reply), and co-locate multi-key ops (same Redis slot via hash tags; same DynamoDB partition).
- **Keep values small.** Network bytes, serialization cost, and (DynamoDB) capacity scale with size. Offload large blobs to S3; store a pointer.
- **Avoid hot keys/partitions.** A single hammered key caps at one partition's throughput regardless of cluster size. Shard counters (`counter:{shard%N}`) and sum on read; add entropy to DynamoDB partition keys (write-sharding) for monotonic patterns.
- **Right storage engine for the access pattern.** Hash-only for pure point access; ordered (LSM/B-tree) when you need range scans. Choose LSM tuning (leveled vs universal compaction) by read/write ratio.
- **Connection pooling.** Jedis needs a pool; Lettuce multiplexes on a single connection (thread-safe). For DynamoDB SDK v2 reuse the client (it's thread-safe; creating it per request is a latency tax).
- **Use server-side atomics** (`INCR`, `ADD`, conditional writes) instead of read-modify-write to cut both latency and race risk.

> **Pipelining:** sending a batch of commands to the server back-to-back without waiting for each response, then reading all responses. Amortizes network round-trip time (RTT) across many ops — often a 5–10× throughput win on a high-RTT link. Distinct from a transaction (no atomicity guarantee unless wrapped in MULTI).

### 6.2 Correctness & concurrency

- **Concurrency primitive = conditional write / CAS,** not locks. Use versioned items (DynamoDB `version` attribute) or `WATCH`/Lua (Redis).
- **Idempotency.** Network retries mean a write may execute twice. Make writes idempotent (use `PutItem` with a deterministic key, conditional `attribute_not_exists`, or an idempotency key) so retries are safe.
- **Beware "eventually consistent reads after a write."** A GSI read or a DynamoDB eventual read right after a write can be stale; use `ConsistentRead` on the base table or read-your-writes patterns when needed.
- **Transactions are limited.** DynamoDB transactions: ≤100 items, 2× cost, no long-running logic. Redis `MULTI/EXEC` has no rollback (it queues then runs; a command failing mid-batch doesn't undo prior ones) and no isolation across the queue beyond atomic execution.

### 6.3 Memory

- **Redis: always set `maxmemory` + an eviction policy.** The default `noeviction` turns a full cache into a write-outage. Watch fragmentation (`mem_fragmentation_ratio`); a value >1.5 suggests the allocator (jemalloc) is holding freed memory — consider `activedefrag`.
- **RocksDB: budget the block cache, write buffers, and OS page cache together;** uncontrolled memtables/compaction can OOM. Cap with `WriteBufferManager` and bound background jobs.
- **Watch the COW spike** during Redis RDB snapshots under heavy writes (can transiently double memory).

### 6.4 Security

- **Auth & encryption.** Redis: require `requirepass`/ACLs (Redis 6 ACLs give per-command/per-key permissions), enable TLS, never expose to the public internet (the default historically had no auth → mass compromises). DynamoDB: IAM policies (least privilege, condition keys like `dynamodb:LeadingKeys` to scope a user to their own partition), encryption at rest (KMS) on by default, TLS in transit.
- **Injection.** Use parameterized expressions in DynamoDB (`:value` placeholders), never string-concatenate user input into Lua/queries.
- **Multi-tenancy isolation.** Scope keys/partitions per tenant and enforce with ACLs/IAM, not just app code.

### 6.5 Observability

- **Redis:** `INFO` (memory, hit rate `keyspace_hits/misses`, connected clients, replication lag `master_repl_offset` vs replica), `SLOWLOG` (commands over a threshold), `LATENCY DOCTOR`, `MONITOR` (debug only — it's expensive). Track **hit ratio**, **evicted_keys**, **p99 command latency**, **blocked_clients**.
- **DynamoDB:** CloudWatch metrics — `ConsumedRead/WriteCapacityUnits`, `ThrottledRequests`, `UserErrors`, `SuccessfulRequestLatency`, `ReturnedItemCount`. Alarm on throttles and on GSI throttling.
- **RocksDB:** statistics (`Options.setStatistics`), `GetProperty("rocksdb.stats")`, compaction stats, block-cache hit rate, pending compaction bytes.

> **Cache hit ratio:** hits ÷ (hits + misses). The single most important cache health metric. A dropping hit ratio means your working set outgrew memory, TTLs are too short, or invalidation is too aggressive — and the backing store is about to feel it.

### 6.6 Cost

- **Redis (self-managed/ElastiCache):** cost ≈ RAM. Right-size with `maxmemory`; consider data-tiering (Redis Enterprise/ElastiCache tiering keeps cold data on SSD). Compression and small values cut RAM.
- **DynamoDB:** pay for capacity + storage + (optionally) streams/backups/GSIs. **On-Demand is convenient but 6–7× provisioned per unit** for steady load — switch steady workloads to provisioned + auto-scaling. Each GSI doubles relevant write cost (base + index write). Strong reads cost 2× eventual. TTL deletes are free; large items cost more capacity.

### 6.7 Testing

- **Local emulators:** **DynamoDB Local** (jar/Docker) for tests; **Testcontainers** for Redis/RocksDB integration tests in JVM CI. RocksDB is embedded — just use a temp dir.
- **Test the access patterns, not the schema.** For single-table design, write a test per documented access pattern.
- **Inject faults:** simulate throttling, replica lag, and node loss (chaos testing) — these are the production realities.

### 6.8 Production hardening

- Enable persistence/backups (Redis AOF+RDB or managed snapshots; DynamoDB PITR).
- Set client timeouts and retries with exponential backoff + jitter; cap retries.
- Use circuit breakers around the store so a slow cache degrades gracefully (fall back to source of truth) rather than cascading.
- Capacity-plan for the **tail** and for failover (a replica taking over inherits the load).

### 6.9 Anti-patterns

- `KEYS *` / unbounded `Scan` on the hot path (blocking / full-table cost).
- Using a KV store as a relational DB: many `Scan`+`FilterExpression` calls that read everything then discard most → paying full capacity for a filtered result.
- Hot keys/partitions (global counter, single celebrity user, monotonic timestamp partition key).
- `noeviction` on a cache; no TTL on session/cache keys (memory leak).
- Treating Redis as a durable system of record without persistence + replication understanding.
- Big values / fat items (unbounded list growth, huge JSON blobs).
- Relying on a Redis lock for hard correctness without fencing.
- Per-request client/connection creation.

---

## 7. Advanced topics & deep internals

### 7.1 LSM compaction strategies (RocksDB)

- **Leveled (default):** L0 overlapping; L1+ non-overlapping, each ~10× the prior. Low **read** & **space** amplification, higher **write** amplification. Best for read-heavy.
- **Universal (tiered):** merge similarly-sized runs; lower write amplification, higher space/read amplification. Best for write-heavy ingestion.
- **FIFO:** drop oldest SSTables past a size — for pure TTL/time-series caches.

Tuning knobs: `level0_file_num_compaction_trigger` (when to start L0→L1), `max_bytes_for_level_base`, `target_file_size_base`, `min_write_buffer_number_to_merge`, `bloom_locality`. **Write stalls** occur when L0 files pile up faster than compaction drains them (`level0_slowdown_writes_trigger`, `level0_stop_writes_trigger`) — a key production symptom; fix with more compaction threads or larger memtables.

> **Write stall / write amplification cliff:** when an LSM can't compact as fast as you write, it deliberately slows or stops writes to avoid unbounded read amplification. Manifests as latency spikes. Diagnose via pending-compaction-bytes and L0 file count.

### 7.2 DynamoDB deep cut

- **Partition math & hot keys.** Throughput is shared per partition (~3000 RCU / 1000 WCU). With adaptive capacity, DynamoDB shifts capacity to hot partitions and can isolate a hot partition, but a single hot **item/key** still caps at one partition's limits. **Write-sharding** (suffix the partition key with `0..N`, fan reads across shards) spreads load for unavoidable hot keys.
- **Item-collection size limit.** With an LSI, all items sharing a partition key must fit in **10 GB** (because LSI data lives in the same partition). GSIs have no such constraint (separate storage).
- **GSI back-pressure.** If a GSI is under-provisioned and throttles, it can **throttle base-table writes** (writes must propagate). Provision GSIs generously or use On-Demand.
- **Eventually consistent GSI.** Always eventual; never strongly consistent. Don't read-your-write off a GSI.
- **Adaptive capacity & burst.** Burst capacity banks up to 5 minutes of unused capacity; adaptive capacity reallocates within ~minutes — neither saves you from a sustained hotspot.
- **Single-table vs multi-table.** Single-table minimizes round-trips and is "join-free join," but is harder to evolve and reason about. Use it when access patterns are well known and numerous; multi-table when they're simple or evolving fast.
- **Transactions internals.** `TransactWriteItems` uses a two-phase protocol across partitions; conflicting concurrent transactions cause `TransactionConflictException` (retry).
- **Global Tables** replicate across regions active-active with **last-writer-wins** (by timestamp) conflict resolution — accept that concurrent multi-region writes to the same item can silently drop one.

### 7.3 Redis advanced

- **Cluster resharding** moves slots live; clients handle `MOVED` (permanent) and `ASK` (in-flight migration) redirects. CROSSSLOT errors when a multi-key op spans slots — fix with hash tags.
- **Keyspace notifications** (pub/sub on key events) = lightweight CDC; enable with `notify-keyspace-events`.
- **Client-side caching (RESP3 tracking):** the server invalidates client-cached keys via push messages — a near-cache without staleness. Redis 6+.
- **Lua/Functions atomicity** lets you ship complex atomic ops; but a long script blocks the single thread → keep O(small).
- **`WAIT numreplicas timeout`** blocks until N replicas ack a write — turns async replication into a tunable durability barrier (not full synchronous replication, but close).
- **Threaded forks (KeyDB, Valkey):** multi-threaded Redis-compatible forks raise throughput on multi-core; Valkey is the Linux Foundation fork after Redis's license change (2024) — flag licensing when choosing.

> **Redis license change (2024):** Redis Inc. moved Redis to a non-OSI source-available license (RSALv2/SSPL); the community forked the last BSD version as **Valkey** (Linux Foundation). Operationally compatible today, but verify license terms for commercial/managed use.

### 7.4 Aerospike & RocksDB advanced

- **Aerospike Strong Consistency mode** gives linearizable single-record ops and avoids lost writes during partitions, at some availability cost; default AP mode trades consistency for uptime.
- **Aerospike all-flash** keeps even the index on SSD for petabyte datasets (vs index-in-RAM default).
- **RocksDB BlobDB / key-value separation (WiscKey-style):** store large values in separate blob files, keeping the LSM keyspace small → much less write amplification for large values.
- **RocksDB merge operators:** define a custom server-side merge (e.g. counter add, append) so read-modify-write becomes an append RocksDB resolves at read/compaction — avoids read-before-write.

> **Key-value separation (WiscKey):** an LSM optimization that stores large values outside the LSM (in a value log) and keeps only key+pointer in the tree. Since compaction then moves far fewer bytes, write amplification drops dramatically for large values.

---

## 8. Tradeoffs & decision frameworks

### 8.1 KV store comparison

| Dimension | Redis | DynamoDB | Aerospike | RocksDB |
|---|---|---|---|---|
| Deployment | self/managed (ElastiCache, Redis Cloud) | fully managed (AWS) | self/managed | embedded library |
| Storage | RAM (+ optional persist/tiering) | SSD (+ in-mem cache) | RAM index + SSD data | SSD/disk (LSM) |
| Data model | rich data structures | items w/ attributes, pk+sk | records w/ bins | opaque bytes, ordered |
| Range scans | within sorted sets/streams | within partition (sort key) | secondary index | yes (ordered) |
| Secondary index | RediSearch module | GSI/LSI | yes | none (build yourself) |
| Consistency | strong on primary; async replicas | per-request eventual/strong; txns | AP default / SC opt-in | local (no distribution) |
| Scale model | Cluster (16384 slots) | automatic, invisible | auto-sharding (4096 parts) | single node (you shard) |
| Latency | sub-ms (RAM) | low single-digit ms | sub-ms | sub-ms local |
| Best for | cache, counters, leaderboards, queues, sessions | massive scale point access, serverless | huge-throughput low-cost RAM/SSD | embedded state, engine substrate |
| Watch out | RAM cost, single-thread hot cmds, eviction policy | hot partitions, GSI cost, no joins | ops complexity | you build distribution |

### 8.2 KV vs relational — use when / avoid when

**Use KV when:**
- Access is dominantly point lookup/update by a known key.
- You need horizontal scale or strict low-latency tails.
- Schema is simple/denormalizable; access patterns are known up front.
- You want managed elasticity (DynamoDB) or RAM-speed structures (Redis).

**Avoid KV (prefer relational) when:**
- You need ad-hoc queries, joins, aggregations, reporting.
- Access patterns are unknown/evolving (relational's flexible querying wins).
- Strong multi-row ACID transactions and complex integrity constraints are central.
- Data is highly relational and you'd otherwise duplicate it everywhere.

> **Denormalization:** deliberately storing redundant copies of data shaped per query, instead of normalizing into joinable tables. KV/NoSQL embraces it (you pre-join by co-locating) because there are no server-side joins; the cost is keeping duplicates in sync on write.

### 8.3 Choosing a storage engine within KV

| Need | Pick |
|---|---|
| Pure point access, lowest latency, small data | in-memory hash (Redis/Memcached) |
| Point + range/prefix scans | ordered store (RocksDB, DynamoDB sort key) |
| Write-heavy ingestion | LSM with universal compaction |
| Read-heavy, range scans | B-tree, or LSM leveled compaction |
| Embedded local state in a JVM app | RocksDB |
| Massive managed scale, serverless | DynamoDB |
| Rich server-side structures (leaderboards, queues) | Redis |

### 8.4 Capacity mode decision (DynamoDB)

- **On-Demand:** unknown/spiky traffic, new apps, dev/test, traffic that can 10× without warning.
- **Provisioned + auto-scaling:** steady, predictable, cost-sensitive sustained load.
- **Reserved capacity:** committed steady baseline → further discount on provisioned.

---

## 9. Failure modes & debugging

### 9.1 DynamoDB hot partition / throttling

- **Symptom:** `ProvisionedThroughputExceededException` / CloudWatch `ThrottledRequests` spiking while total provisioned capacity looks fine. Elevated p99.
- **Cause:** traffic skewed to one partition key (hot key) or monotonic key concentrating writes on the newest partition; or a GSI throttling and back-pressuring base writes.
- **Diagnose:** CloudWatch Contributor Insights for DynamoDB (top partition keys by traffic), `ConsumedWriteCapacityUnits` per partition, check GSI metrics separately.
- **Fix:** write-sharding the partition key (add `#0..N` suffix, scatter-gather reads), switch to On-Demand to absorb spikes, redesign the key, or provision the GSI higher. Adaptive capacity helps automatically but won't beat a single hot key.

### 9.2 Redis latency spikes / blocking

- **Symptom:** periodic p99 spikes, timeouts, `blocked_clients` rising.
- **Causes:** a `O(n)` command on a big key (`KEYS *`, `HGETALL` on a huge hash, `SMEMBERS` on a giant set, `DEL` of a huge key), a slow Lua script, an RDB fork/COW pause, AOF rewrite IO, or swapping (Redis must never swap).
- **Diagnose:** `SLOWLOG GET`, `LATENCY DOCTOR`, `LATENCY HISTORY`, `INFO` (`latest_fork_usec`, `mem_fragmentation_ratio`, `evicted_keys`), `--bigkeys`/`MEMORY USAGE` to find fat keys.
- **Fix:** replace blocking commands with `SCAN`/`HSCAN`/`UNLINK` (async delete), shard big keys, tune persistence (disable RDB on the primary, persist on a replica), pin to a core, disable transparent huge pages, ensure no swap.

### 9.3 Cache stampede after mass expiry

- **Symptom:** backing DB CPU spikes at TTL boundaries; latency cliff.
- **Fix:** TTL jitter, per-key recompute lease (`SET NX`), stale-while-revalidate, request coalescing.

### 9.4 Eviction-induced misses / wrong policy

- **Symptom:** hit ratio drops, `evicted_keys` climbs, or writes suddenly error.
- **Cause:** working set exceeds `maxmemory`; or `noeviction` policy → writes rejected (`OOM command not allowed`).
- **Fix:** scale RAM/cluster, shorten TTLs, set an LRU/LFU policy for caches.

### 9.5 Replication lag / stale reads

- **Symptom:** "I updated it but still see the old value" when reading a replica or a DynamoDB eventual read / GSI.
- **Diagnose:** Redis `INFO replication` offsets; DynamoDB nature of eventual reads.
- **Fix:** read from primary / use `ConsistentRead`, or design for read-your-writes (route a user's reads to where their writes landed for a window).

### 9.6 RocksDB write stalls / space blowup

- **Symptom:** write latency spikes; disk usage far exceeds live data.
- **Cause:** compaction can't keep up (L0 backlog) or too many obsolete versions/tombstones.
- **Diagnose:** `GetProperty("rocksdb.stats")`, pending-compaction-bytes, L0 file count, num-live-versions.
- **Fix:** more `max_background_jobs`, larger/more memtables, switch compaction style, ensure snapshots/iterators are closed (open snapshots pin old versions, bloating space).

### 9.7 Real-world incident patterns

- **Redis publicly exposed, no auth → cryptojacking / data wipe.** Classic: never bind Redis to a public IP without auth+TLS+firewall.
- **DynamoDB hot partition on a "celebrity" key** (one viral item) throttling an otherwise healthy table — solved by write-sharding + caching the hot item in front (Redis).
- **GSI throttle cascading to base writes**, stalling the whole write path — solved by over-provisioning the GSI or On-Demand.
- **Redis Cluster CROSSSLOT outage** when a deploy added a multi-key op spanning slots — fixed with hash tags.
- **Tombstone storms** (Cassandra-family, same LSM family lesson): range-deleting in a partition then scanning it → reads wade through millions of tombstones; redesign to avoid mass deletes or use TTLs.

---

## 10. Interview drill

**Q1. What is a key-value store and when would you choose it over a relational database?**
Model answer: A KV store maps unique keys to opaque values, optimized for point access by key; it partitions trivially (hash/range on the key), giving horizontal scale and predictable low-latency tails, at the cost of no server-side joins/ad-hoc queries. Choose it when access is dominantly lookup/update by a known key, you need scale or tight p99, and the schema is denormalizable with known access patterns (caches, sessions, carts, counters). Prefer relational when you need joins, aggregations, ad-hoc/evolving queries, or rich multi-row ACID.
- *Probe: How does the KV model enable scaling that relational struggles with?* The key alone determines placement, so partitioning needs no global secondary structure; relational sharding must handle cross-shard joins/transactions, which is much harder.
- *Probe: What do you give up?* Server-side querying flexibility — you must model data per access pattern and often duplicate it.
- *Probe: Where do KV stores sit on CAP?* They span it: DynamoDB lets you pick per request (eventual≈AP, strong≈CP); Redis primary is CP-leaning; classic Dynamo/Cassandra are AP with tunable quorums.

**Q2. Explain how an LSM-tree services a write and a read, and the amplifications involved.**
Model answer: Write = WAL append + insert into in-memory memtable → return; memtable flushes to an immutable sorted SSTable; compaction merges SSTables, dropping old versions/tombstones. Read = check memtable(s), then SSTables newest-to-oldest, using Bloom filters to skip files and a block cache for RAM-speed hits. Amplifications: write (compaction rewrites data), read (multiple files per lookup), space (old versions awaiting compaction). Tuning balances the three (leveled = low read/space, high write; universal = the reverse).
- *Probe: Why LSM over B-tree for write-heavy?* Append-only sequential writes vs random in-place updates → far higher write throughput and SSD-friendliness.
- *Probe: What's a write stall?* When compaction lags ingestion, the LSM throttles/stops writes to bound read amplification → latency spikes; fix with more compaction threads/bigger memtables.
- *Probe: Role of Bloom filters?* Skip SSTables that can't contain the key (no false negatives), slashing read amplification.

**Q3. DynamoDB: partition key vs sort key vs GSI vs LSI — when do you use each?**
Model answer: Partition key picks the partition (and shares its throughput). Sort key orders items within a partition, enabling range queries (`begins_with`, `between`). LSI = same partition key, alternate sort key, created at table creation, shares the 10 GB item-collection limit, supports strong reads. GSI = different partition/sort key, a separate async-maintained copy, eventual-only, own capacity, create anytime. Use sort keys for one-to-many within an entity; GSIs for orthogonal access paths; LSIs rarely (only when you need strong reads on an alternate sort within the same partition).
- *Probe: Why can a GSI throttle your base table?* Base writes must propagate to the GSI; if the GSI is under-provisioned it back-pressures base writes.
- *Probe: Strong consistency on a GSI?* Not possible — GSIs are always eventually consistent.
- *Probe: LSI's hidden constraint?* All items per partition key (base + LSI projections) must fit in 10 GB.

**Q4. What is a hot partition / hot key and how do you fix it?** *(senior signal)*
Model answer: Traffic skewed to one partition key concentrates load on a single partition, which caps at ~3000 RCU/1000 WCU regardless of total provisioned capacity, causing throttling. Causes: a celebrity item, a monotonic key (timestamp) hitting the newest partition. Fixes: write-sharding (suffix the key with `#0..N`, scatter-gather on read), add entropy to monotonic keys, cache the hot item in Redis in front, or switch to On-Demand. Adaptive capacity helps but can't beat a single hot key.
- *Probe: How would you diagnose it?* CloudWatch Contributor Insights (top keys), throttle metrics, per-partition consumed capacity.
- *Probe: Tradeoff of write-sharding?* Reads become scatter-gather (N queries merged) → more capacity and code complexity.

**Q5. Why is Redis fast, and what's the danger of its execution model?**
Model answer: Data in RAM plus a single-threaded event loop means each command runs atomically with no locking overhead, giving sub-ms latency and free atomicity for `INCR`/`SETNX`. The danger: one slow command (`KEYS *`, big `HGETALL`, slow Lua, fork/COW pause) blocks all clients. Mitigate with `SCAN` family, sharding big keys, `UNLINK`, careful persistence, and keeping scripts O(small).
- *Probe: How do persistence options affect latency?* RDB forks (COW spike), AOF rewrite causes IO; run persistence on a replica to protect the primary.
- *Probe: Redis 6 I/O threads — do they make execution multi-threaded?* No — they parallelize network read/write; core command execution stays single-threaded.

**Q6. How do you achieve concurrency-safe updates without locks in a KV store?**
Model answer: Optimistic concurrency via conditional writes/CAS: read value+version, write only if version unchanged (DynamoDB `ConditionExpression: version = :v`, Redis `WATCH`/`MULTI` or Lua), retry on conflict. Plus server-side atomics (`INCR`, DynamoDB `ADD`) for counters to avoid read-modify-write entirely. Make writes idempotent so retries are safe.
- *Probe: Why prefer CAS over a distributed lock?* Locks add a failure mode (holder dies/pauses) and need fencing tokens for safety; CAS is lock-free and naturally retried.
- *Probe: Is a Redis lock safe for hard correctness?* Not without fencing tokens — GC pauses/clock skew can let a stale holder act; use ZooKeeper/etcd or a DB conditional write for must-be-exclusive cases.

**Q7. Design the data model for an e-commerce app on DynamoDB (users, orders, items). Justify single- vs multi-table.** *(senior signal)*
Model answer: List access patterns first (get profile; list a user's orders newest-first; get an order's line items; list shipped orders). Single-table: overload `pk`/`sk` — `USER#id`/`PROFILE`, `USER#id`/`ORDER#date#oid`, `ORDER#oid`/`ITEM#sku` — so a user's profile+orders come from one `Query` (sort-key prefix), and a GSI (`STATUS#x`) serves "shipped orders." Justify: DynamoDB has no joins, so co-location pre-joins and cuts round-trips; single-table fits when access patterns are numerous and known. Multi-table is fine when patterns are simple/evolving and readability matters more than round-trips.
- *Probe: How does sort-key design enable newest-first?* Encode date in the sort key and `ScanIndexForward=false`.
- *Probe: When would single-table hurt you?* When access patterns change a lot — re-modeling overloaded keys/GSIs is painful; you may need to backfill.

**Q8. Compare eventual vs strong consistency in DynamoDB. What are the costs?**
Model answer: Eventual (default) may not reflect a just-completed write, costs 1 RCU per 4 KB, lowest latency, highest availability. Strong (`ConsistentRead=true`) reflects all prior writes, costs 2× RCU, only within a Region and not on GSIs. Use strong when read-your-writes correctness matters (e.g. reading back a just-written balance); eventual for high-throughput reads tolerant of slight staleness.
- *Probe: Why can't GSIs be strongly consistent?* They're maintained asynchronously — a separate eventually-updated copy.
- *Probe: PACELC classification?* PA/EL by default (available + low-latency, eventual), with opt-in C.

**Q9. What metrics tell you a cache is unhealthy, and what would you do?**
Model answer: Falling **hit ratio**, rising **evicted_keys**, growing **memory** toward `maxmemory`, climbing p99 command latency, rising `blocked_clients`, and (DynamoDB front) increased misses → backing-store load. Actions: scale RAM/cluster, tune TTLs, set proper eviction policy, find/shard big keys, add jitter to prevent stampedes, add a near-cache.
- *Probe: Hit ratio dropped but memory not full — why?* TTLs too short or invalidation too aggressive, or working set shifted.
- *Probe: Sudden write errors on a cache?* Likely `noeviction` policy hitting `maxmemory`.

**Q10. When would you NOT use a KV store?** *(senior signal)*
Model answer: When you need ad-hoc queries/joins/aggregations/reporting, when access patterns are unknown or rapidly evolving (relational's flexibility wins), when complex multi-row ACID and referential integrity are central, or when data is deeply relational and KV would force pervasive duplication that's expensive to keep consistent. Also avoid pushing analytical/range-heavy workloads onto a hash-only KV store.
- *Probe: Could you bolt querying onto KV?* Yes — secondary indexes (GSI, RediSearch), CDC into a search engine (Elasticsearch) or warehouse — but that's added complexity/consistency burden; if querying is core, start relational/search.
- *Probe: Hybrid approach?* Common: KV/Dynamo as system of record for point access + CDC (Streams) into OpenSearch/warehouse for queries — best of both.

**Q11. Explain TTL/expiry implementation and its pitfalls.**
Model answer: Redis uses lazy (delete on access if expired) + active (sampled background sweep ~10×/s) expiration, so expired keys linger briefly in RAM. DynamoDB TTL marks an epoch attribute; a background sweep deletes within ~48 h (not instant) — and TTL deletes flow through Streams. Pitfall: relying on TTL for *immediate* removal (both lag); for security-sensitive expiry, filter at read time too.
- *Probe: Does an expired Redis key count against memory?* Yes, until lazily/actively reclaimed.
- *Probe: Does DynamoDB charge for TTL deletes?* No write capacity consumed by TTL deletions.

**Q12. How do you partition and rebalance a KV cluster without downtime?**
Model answer: Use consistent hashing with virtual nodes so adding/removing a node remaps only a fraction of keys; rebalancing moves vnodes/slots gradually while serving requests (Redis Cluster moves 16384 slots, redirecting via `MOVED`/`ASK`; DynamoDB splits partitions automatically and invisibly). Range partitioning enables scans but risks hot ranges from monotonic keys.
- *Probe: Why virtual nodes?* Smoother load distribution and finer, less disruptive rebalancing.
- *Probe: Hash vs range partitioning tradeoff?* Hash = even load, no cross-partition scans; range = scans possible but hot-range risk.

---

## 11. Glossary

- **Adaptive capacity (DynamoDB):** automatic reallocation of throughput toward hot partitions, plus splitting hot partitions; mitigates but can't fix a single hot key.
- **AOF (Append-Only File):** Redis persistence logging every write command for replay on restart.
- **Associative array / map / dictionary:** the abstract key→value data type a KV store implements.
- **Availability Zone (AZ):** isolated datacenter within an AWS Region; replicating across AZs survives a datacenter failure.
- **B-tree / B+ tree:** balanced, high-fan-out on-disk search tree; sorted, range-scannable, updated in place; read-optimized.
- **Block cache (RocksDB):** in-RAM cache of SSTable blocks; the main read-perf lever.
- **Bloom filter:** probabilistic set-membership test ("definitely no"/"probably yes"); lets LSM skip files; no false negatives.
- **Burst capacity (DynamoDB):** banked unused capacity (~5 min) to absorb short spikes.
- **CAP theorem:** under a network partition, choose Consistency or Availability, not both.
- **Cache-aside:** app reads cache, on miss loads from source of truth and populates cache.
- **Cache hit ratio:** hits ÷ (hits+misses); top cache health metric.
- **Cache stampede / thundering herd:** simultaneous misses on an expired hot key flooding the backing store.
- **CAS / conditional write:** atomic "write only if unchanged"; the lock-free concurrency primitive.
- **CDC (Change Data Capture):** stream of DB changes for downstream sync (DynamoDB Streams, Redis keyspace notifications).
- **Column family (RocksDB):** independent keyspace within one DB, shared WAL, separate SSTables/config.
- **Compaction:** LSM background merge of SSTables that drops old versions/tombstones; bounds read/space amplification.
- **Consistent hashing:** keys/nodes on a ring; adding/removing a node remaps only one arc.
- **COW (copy-on-write):** share memory pages until written; Redis uses it for snapshot forks.
- **Denormalization:** storing redundant data shaped per query; the NoSQL norm (no joins).
- **Durability:** surviving crashes; provided by WAL/AOF/snapshots/replication.
- **Eventual consistency:** replicas converge eventually; a read after a write may be stale.
- **Eviction policy (Redis):** behavior at `maxmemory` (LRU/LFU/volatile/noeviction).
- **Event loop / epoll:** single thread multiplexing many sockets; Redis's execution model.
- **Fencing token:** monotonic number with a lock; storage rejects writes bearing a stale token.
- **fork():** OS process clone (with COW); Redis snapshotting.
- **GSI (Global Secondary Index):** DynamoDB index with a different key; async, eventual, own capacity.
- **Hash partitioning / slot:** placement by hashing the key; Redis Cluster uses 16384 slots.
- **Hash tag `{}` (Redis):** force keys onto one slot by hashing only the braced substring.
- **Hot key / hot partition:** disproportionate traffic to one key/partition, capping at one partition's throughput.
- **Idempotency:** an operation safe to repeat; essential under retries.
- **LSI (Local Secondary Index):** DynamoDB index with same partition key, alternate sort key; created at table creation; strong reads.
- **Linearizability:** strongest single-object consistency: reads see the latest committed write in a single global order.
- **Listpack / ziplist:** compact serialization of small Redis collections.
- **LSM-tree:** write-optimized engine (memtable + WAL + SSTables + compaction).
- **maxmemory (Redis):** RAM cap; with eviction policy governs full-cache behavior.
- **Memtable:** in-memory sorted write buffer of an LSM.
- **MVCC:** keep multiple versions so readers don't block writers (and vice versa).
- **On-Demand vs Provisioned (DynamoDB):** pay-per-request vs preset capacity (+auto-scaling).
- **Optimistic concurrency:** assume no conflict; verify with CAS at write; retry on failure.
- **Partition key (hash key):** attribute hashed to pick a DynamoDB partition.
- **PACELC:** if Partitioned A-or-C; Else Latency-or-Consistency.
- **Pipelining:** batching commands without waiting for each reply; amortizes RTT.
- **Quorum (R/W/N):** read R of N, write W of N; W+R>N gives overlap → consistency.
- **Raft / consensus:** algorithm for agreeing on an ordered log despite failures (etcd, Consul).
- **RCU/WCU:** DynamoDB read/write capacity units (1 RCU = strong 4 KB read/s; 1 WCU = 1 KB write/s).
- **RDB:** Redis point-in-time binary snapshot persistence.
- **Read/write/space amplification:** the three LSM cost dimensions.
- **Read-your-writes:** a client always sees its own prior writes.
- **Rebalancing:** moving partitions/slots between nodes to even load.
- **Replication lag:** delay before a write reaches a follower; source of stale reads.
- **Sequence number (RocksDB):** monotonic per-write stamp; ordering + MVCC basis.
- **Single-table design:** packing many entity types in one DynamoDB table to make each access pattern one Query.
- **Skip list:** probabilistic ordered structure; Redis sorted sets, RocksDB memtable.
- **Sort key (range key):** orders items within a DynamoDB partition; enables range queries.
- **SSTable:** immutable sorted on-disk KV file with sparse index + Bloom filter.
- **Strong consistency:** reads reflect all prior committed writes.
- **Throttling:** rejecting over-capacity requests; client backs off/retries.
- **Tombstone:** deletion marker honored until compaction purges it.
- **TTL (time-to-live):** auto-expiry timer on a key/item.
- **Virtual node (vnode):** physical node owning many ring positions for smoother load/rebalance.
- **WAL / commit log:** append-only durability log written before in-memory state changes.
- **Write stall:** LSM throttling writes when compaction can't keep up.
- **Write-sharding:** suffixing a partition key to spread an unavoidable hot key across partitions.
- **ZooKeeper / etcd:** strongly-consistent coordination services (locks, leader election, config).

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Mental model:** sharded, replicated, durable hash map; design key+value so every query is a key lookup or tight single-partition scan.

**Engine families:** hash (point only) vs ordered/B-tree/LSM (point + range). LSM = memtable + WAL + SSTables + compaction; Bloom filters + block cache for reads; three amplifications (read/write/space).

**Key numbers:**
- RocksDB default memtable `write_buffer_size` = **64 MB**.
- Redis Cluster = **16384** hash slots; `slot = CRC16(key) mod 16384`.
- DynamoDB partition ≈ **10 GB**, ~**3000 RCU** / **1000 WCU** per partition before split.
- DynamoDB: **1 RCU** = 1 strong 4 KB read/s (or 2 eventual); **1 WCU** = 1 KB write/s; strong read = 2× RCU; transaction = 2×.
- DynamoDB `Query`/`Scan` page = **1 MB**; batch = 100 gets / 25 writes; transaction ≤ 100 items.
- DynamoDB TTL deletes within ~**48 h**; On-Demand ≈ **6–7×** provisioned per unit for steady load.
- Redis AOF default `appendfsync everysec` (≤~1 s loss); LSI item collection ≤ **10 GB**.

**Decision rules:**
- Point access by key + scale/low-latency + denormalizable → **KV**. Joins/ad-hoc/aggregations → **relational**.
- Range scans needed → **ordered** store (RocksDB / DynamoDB sort key). Pure point + RAM speed → **Redis/Memcached**.
- Spiky/unknown DynamoDB load → **On-Demand**; steady → **Provisioned + auto-scaling**.
- Orthogonal DynamoDB access path → **GSI** (eventual, own capacity); alternate sort with strong reads → **LSI** (at creation).
- Concurrency → **CAS/conditional write + server-side atomics + idempotency**, not locks. Hard exclusion → **etcd/ZooKeeper or DB conditional write with fencing**.

**Anti-patterns:** `KEYS *`/unbounded `Scan`; `Scan`+`FilterExpression` as a query engine; hot keys/partitions; `noeviction` cache; missing TTLs; fat keys/items; per-request clients; Redis lock for hard correctness without fencing.

**Debug first moves:** Redis → `SLOWLOG`, `LATENCY DOCTOR`, `INFO`, `--bigkeys`. DynamoDB → CloudWatch `ThrottledRequests` + Contributor Insights. RocksDB → `rocksdb.stats`, L0 file count, pending-compaction-bytes.

### Self-test (no answers — recall actively)

1. Trace a RocksDB `Put` and a `Get` end to end, naming every structure touched and why each amplification arises.
2. You have a celebrity item being read 50k times/sec from one DynamoDB partition key and getting throttled. Walk through three independent mitigations and their tradeoffs.
3. Why is `W + R > N` sufficient for read-after-write consistency under leaderless replication, and what does it cost?
4. Design DynamoDB keys (pk/sk + any GSIs) for: "get a customer," "list a customer's invoices newest-first," "find all overdue invoices across customers." Justify single- vs multi-table.
5. Redis p99 spikes every few minutes in lockstep with snapshots. Explain the mechanism and give two fixes that don't disable durability.
6. When would you put Redis in front of DynamoDB, and what new consistency/invalidation problems does that introduce?
7. Explain why a Redis distributed lock can violate mutual exclusion, and what a fencing token does about it.

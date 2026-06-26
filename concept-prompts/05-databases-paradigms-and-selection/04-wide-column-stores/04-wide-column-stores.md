# Wide-Column Stores

> A definitive engineering-handbook chapter on the wide-column / BigTable data model, with deep internals on HBase and Cassandra, LSM-tree storage, and production design. Written for a senior JVM-backend developer who wants to master the topic end to end.

---

## 1. Overview & where it fits

### 1.1 What a wide-column store is

A **wide-column store** (also called a *column-family store*, a *BigTable-style store*, or sometimes loosely an "extensible record store") is a NoSQL database that organizes data into a **multidimensional sorted map**. Conceptually, a single logical value is addressed by a tuple of keys:

```
(row key, column family, column qualifier, timestamp) -> value (bytes)
```

That is the entire model. Everything else — HBase, Cassandra, Google Cloud Bigtable, ScyllaDB, Accumulo — is an implementation of variations on this map.

Let me define each piece immediately, because the names are the foundation of everything that follows:

- **Row key**: the primary identifier of a row. It is an opaque byte array. Rows are **physically stored sorted by row key** (in BigTable/HBase/Accumulo) or **distributed by a hash of the partition key** (in Cassandra). The row key determines *where* the data lives and *how fast* you can find it.
- **Column family** (CF): a named, statically-declared grouping of columns. All columns within a family are **stored together on disk** (co-located in the same files) and share storage/tuning settings (compression, TTL, version count). A table has a small, fixed number of column families (usually 1–3).
- **Column qualifier** (the "column name"): an arbitrary byte array *within* a column family. Unlike a relational column, qualifiers are **not declared in a schema** — you can have millions of distinct qualifiers, and every row can have a *different* set of them. This is what "wide" means: a row can be extremely wide (millions of columns) and **sparse** (most rows have only a few columns present).
- **Timestamp / version**: each cell can retain multiple versions, identified by a 64-bit timestamp (usually milliseconds since epoch, but you can supply your own). The store keeps the N most recent versions per cell (configurable).
- **Cell**: the atomic unit — the value at `(row, family, qualifier, timestamp)`. It is an uninterpreted byte array; the database does not know or care whether it is an int, a UTF-8 string, or a protobuf.

> **"Sparse" defined for a beginner.** In a relational table, every row has a value (or NULL) for *every* column. If you add a column, every row gains a slot. In a wide-column store, a column only "exists" for a row if you actually wrote a value to it. A row with three columns and a row with three million columns can sit side by side in the same table, costing storage proportional only to what was written. There is no NULL and no reserved empty slot.

### 1.2 The problem it solves

Wide-column stores were born to answer one question: **how do you store and serve petabytes of structured-ish data, across thousands of commodity machines, with high write throughput and predictable low-latency point/range reads, while surviving constant hardware failure?**

This is the problem Google had in 2004–2006 indexing the web (the Google **Bigtable** paper, OSDI 2006) and the problem Facebook had with inbox search in 2008 (the **Cassandra** paper, which fused Bigtable's data model with Amazon **Dynamo's** distribution and replication design). The common thread:

- **Write-heavy workloads** at massive scale (clickstreams, sensor telemetry, messaging, time-series, audit logs).
- **Tables too big for one machine**, requiring **horizontal scaling** (sharding) as a first-class feature, not an afterthought.
- **Schema flexibility** — data whose shape varies per row or evolves rapidly, where a rigid relational schema and `ALTER TABLE` migrations across billions of rows are infeasible.
- **Linear scalability and fault tolerance** on cheap, failure-prone hardware.

> **"Horizontal scaling / sharding" defined.** *Vertical scaling* means buying a bigger machine (more CPU/RAM). *Horizontal scaling* means adding more machines and splitting the data across them. A *shard* (HBase calls it a **region**, Cassandra calls it a range of **token** values) is a contiguous slice of the keyspace that lives on one node. Wide-column stores shard automatically based on the row/partition key.

### 1.3 When you reach for it (and when you don't)

**Reach for a wide-column store when:**

- Your write rate dwarfs your read rate, or both are huge (hundreds of thousands to millions of ops/sec).
- Your data is naturally keyed by an entity and queried by that key or by a key-range (e.g., "all events for user X between time T1 and T2").
- You have **time-series** or **append-mostly** data.
- Rows are **wide and sparse** (e.g., a user profile with thousands of optional attributes; a feature store).
- You need **linear horizontal scalability** and tolerance of node failure without operator intervention.
- You can express your reads as **point lookups or contiguous range scans on the key** — and you are willing to **design your tables around your queries**, not the other way around.

**Avoid it when:**

- You need **ad-hoc queries**, **arbitrary joins**, **multi-row ACID transactions**, or **secondary-index-heavy** access across many dimensions. (These are weak or absent.)
- Your dataset fits comfortably on one node and your query patterns are unpredictable — a relational database (PostgreSQL/MySQL) is simpler and far more flexible.
- You need strong relational integrity (foreign keys, constraints) or complex aggregations/analytics — use an RDBMS or an analytics engine (a columnar OLAP store like ClickHouse/BigQuery, which despite the name is a *different* thing — see §8).

### 1.4 The one-paragraph mental model

Picture a giant, distributed `SortedMap<byte[], SortedMap<byte[], SortedMap<Long, byte[]>>>` — outer key is the row, then the column, then the timestamp, ending in a value of bytes. The map is split into contiguous key-range shards spread over a cluster. Writes never overwrite in place: they append to an in-memory sorted buffer (the **memtable**) that is periodically flushed to immutable on-disk files (**SSTables**), and a background process (**compaction**) merges those files and discards obsolete versions and deletes. Reads merge the memtable with the relevant SSTables. Deletes are themselves writes (**tombstones**). The shape of your row key decides everything: which node a row lands on, whether your scans are fast or full-table, and whether your write load is evenly spread or piled onto one unlucky machine (a **hotspot**).

---

## 2. Foundations from first principles

We build the model up from zero. By the end of this section you should be able to draw the data layout on a whiteboard.

### 2.1 From key-value to multidimensional map

Start with the simplest NoSQL store: a **key-value (KV) store**. It maps `key -> value`. `GET(k)`, `PUT(k, v)`, `DELETE(k)`. That is Redis, Memcached, the values in DynamoDB's simplest form.

A wide-column store extends this in three orthogonal directions:

1. **Add structure to the value.** Instead of an opaque blob, the value is itself a map of *columns*. So `row -> {col1: v1, col2: v2, ...}`. Now one key addresses a whole structured record, and you can read/write *individual columns* without rewriting the whole row.

2. **Group columns into families.** Columns are partitioned into **column families** that are stored separately on disk. This means a scan that only touches family `A` never reads bytes from family `B`. You get a form of vertical partitioning for free.

3. **Add a version axis.** Each cell keeps a stack of timestamped versions, so the store is also a tiny time-machine per cell.

Putting it together, the addressing tuple is `(row, family, qualifier, timestamp) -> bytes`, and the whole thing is kept **sorted** so range scans are cheap.

### 2.2 The "wide" and "sparse" properties, concretely

Imagine a table `webtable` (the canonical Bigtable example) keyed by the **reversed domain** of a web page:

```
Row key: "com.cnn.www/index.html"
  Column family "contents:" 
      contents:           -> <raw HTML at t6>, <raw HTML at t3> (two versions)
  Column family "anchor:"
      anchor:cnnsi.com    -> "CNN"
      anchor:my.look.ca   -> "CNN.com"
```

Key observations:

- The row `com.cnn.www/...` has **two anchor columns**; another row might have **zero or fifty thousand** anchors. The set of qualifiers under `anchor:` is open-ended and per-row. That is **wide + sparse**.
- Reversing the domain (`com.cnn.www`) makes pages from the same site **sort adjacently**, so you can scan an entire domain with one range scan. This is your first taste of **key design driving query performance**.
- `contents:` keeps multiple timestamped versions of the page — the version axis in action.

### 2.3 Sorted storage and why it matters

In BigTable/HBase/Accumulo, **rows are stored in lexicographic byte order of the row key**, globally. This single fact gives you:

- **Efficient range scans**: "give me all rows from `userA#2024-01` to `userA#2024-12`" is a sequential disk read, not a scatter-gather.
- **Locality**: related rows (by key prefix) sit together.
- **A curse**: monotonically increasing keys (timestamps, sequence numbers) all land at the *end* of the keyspace, hammering one shard — the classic **hotspot** (§2.8, §6, §9).

Cassandra is different: it **hashes the partition key** to place partitions around a ring, so partitions are *not* globally ordered (you cannot do a global range scan by partition key). But *within* a partition, rows are stored **sorted by clustering columns**, so range scans inside a partition are fast. This distinction (global order vs. hash-distributed) is one of the deepest differences between the two systems (§3, §8).

### 2.4 The LSM-tree: the storage engine underneath

Every mainstream wide-column store uses a **Log-Structured Merge-tree (LSM-tree)** as its on-disk storage engine. Understanding the LSM-tree *is* understanding why these systems behave the way they do. We will go deep in §3, but here is the first-principles version.

> **Why not a B-tree?** A traditional relational database uses a **B-tree** (or B+tree): an on-disk balanced tree where writes update pages **in place**. In-place updates mean random disk I/O and read-modify-write of pages — fine for spinning disks at moderate write rates, painful at very high write rates because each write may touch a random disk location. LSM-trees trade this for **sequential writes**.

The LSM-tree's core idea:

1. **All writes go to an in-memory sorted structure** called the **memtable** (typically a concurrent skip-list or balanced tree), plus an append to a **write-ahead log** (WAL) on disk for durability.
2. When the memtable fills up, it is **flushed** to disk as an **immutable, sorted file** called an **SSTable** (Sorted String Table). Flushing is a big sequential write — fast.
3. Over time you accumulate many SSTables. A background process called **compaction** merges them, keeping the newest version of each cell and physically removing deleted/expired data.
4. **Reads** consult the memtable and then the SSTables (newest first), merging results. To avoid touching every SSTable, each SSTable has a **Bloom filter** (a probabilistic "is key X possibly here?" structure) and a sparse index.

> **"Write-ahead log (WAL)" defined.** Before a write is acknowledged, it is appended to a sequential on-disk log. If the node crashes before the in-memory memtable is flushed, the WAL is **replayed** on restart to recover those writes. HBase calls it the **WAL** (formerly HLog); Cassandra calls it the **commit log**. The WAL is the durability backbone of an LSM system.

> **"Bloom filter" defined.** A Bloom filter is a compact bit-array structure that answers "is element X in the set?" with either **definitely no** or **probably yes** (a tunable false-positive rate, never a false negative). Each SSTable has one so a read can skip SSTables that definitely do not contain the key, saving disk I/O. Cost: a few bits per key (e.g., ~10 bits/key for ~1% false positives).

> **"SSTable (Sorted String Table)" defined.** An immutable on-disk file holding key→value entries **sorted by key**, with an embedded index and Bloom filter. "Immutable" is crucial: once written, an SSTable is never modified — it is only ever read or, eventually, deleted by compaction after its data has been merged into a newer SSTable. Immutability is what makes LSM concurrency, backups, and caching simple.

### 2.5 Reads, writes, and the three amplifications

LSM-trees are defined by the tradeoff between three "amplifications," a vocabulary you must internalize:

- **Write amplification**: the ratio of bytes physically written to disk to bytes logically written by the user. Compaction rewrites data multiple times as it merges levels, so one logical write may be physically written 10–30×. High write amp wears out SSDs and consumes I/O bandwidth.
- **Read amplification**: the number of disk reads (SSTables consulted) to satisfy one logical read. If a key's versions and the relevant tombstones are scattered across many un-compacted SSTables, a single read may touch many files. Lots of tombstones make this dramatically worse (§2.7, §6, §9).
- **Space amplification**: the ratio of disk space used to the size of the live (logical) data. Obsolete versions and not-yet-removed deletes consume extra space until compaction reclaims it.

Compaction strategies (§3.6, §7) exist precisely to choose a point in this **write/read/space amplification trade-space**.

### 2.6 Versions and TTL

Each cell can hold up to `N` versions (HBase `VERSIONS`, default historically `1` in modern HBase, `3` in older versions; Cassandra doesn't keep multiple versions of a cell — it keeps the latest write per column based on timestamp). You can:

- Read the latest version (default).
- Read a specific version by timestamp, or the latest version **as of** a given time.
- Set a **TTL (time-to-live)** so cells expire automatically. Expired cells become eligible for removal during compaction. TTL is enormously useful for time-series/log data ("keep 30 days").

> **"TTL" defined.** Time-to-live: a per-cell or per-table expiry duration. After it elapses, the cell is treated as deleted (and physically removed at the next compaction). Critically, TTL expiry creates the same read-cost problem as tombstones until compaction cleans up (§9).

### 2.7 Deletes are writes: tombstones

You cannot delete in place in an immutable-file world. So a delete is implemented as a special **write** called a **tombstone**: a marker that says "this cell (or row, or range) is deleted as of timestamp T." Tombstones:

- **Shadow** older data during reads (a read that finds a tombstone newer than a value treats the value as gone).
- Are themselves removed only during compaction, and only after a **grace period** (Cassandra's `gc_grace_seconds`, default **864000s = 10 days**) that guarantees the delete has propagated to all replicas before the tombstone is purged. Purging too early could **resurrect** deleted data.
- Cause **read amplification**: scanning over a range littered with tombstones forces the engine to read and discard them. This is the single most common Cassandra production pathology (§9).

> **"Resurrection of deleted data" defined.** If a tombstone is purged before a lagging/recovered replica has seen the delete, that replica still holds the old value; a later read-repair or hint could re-propagate it, making the "deleted" data come back. `gc_grace_seconds` and anti-entropy repair exist to prevent this (§7).

### 2.8 Partitioning, replication, and consistency — the distributed layer

So far we described a single node's storage. Now distribute it.

- **Partitioning (sharding)**: split the keyspace across nodes. HBase uses **range partitioning** (contiguous key ranges = **regions**, with auto-splitting). Cassandra uses **consistent hashing**: a hash function maps each partition key to a **token** on a ring; the node owning that token range stores it.

> **"Consistent hashing" defined.** A hashing scheme arranging nodes and keys on a logical ring (0 to 2⁶⁴-1, say). A key is placed on the first node clockwise from its hash. Adding/removing a node only re-homes a small fraction of keys (those between the changed node and its neighbor), instead of reshuffling everything as plain `hash mod N` would. Cassandra refines this with **virtual nodes (vnodes)**: each physical node owns many small token ranges, smoothing data distribution and rebalancing.

- **Replication**: each shard is copied to `RF` (replication factor) nodes for fault tolerance. HBase relies on **HDFS** (the Hadoop Distributed File System) underneath, which replicates blocks (default 3×); HBase itself stores one logical copy per region (the durability/replication comes from HDFS). Cassandra replicates at the database layer: each partition is stored on `RF` nodes chosen by walking the ring.

- **Consistency model**: this is where HBase and Cassandra fundamentally diverge, and it maps onto the **CAP theorem**.

> **"CAP theorem" defined.** In the presence of a network **P**artition (some nodes can't talk to others), a distributed system must choose between **C**onsistency (every read sees the latest write) and **A**vailability (every request gets a non-error response). You cannot have both *during a partition*. HBase chooses **CP**: during a partition, the unreachable region's data becomes unavailable rather than serving stale/divergent data. Cassandra chooses **AP**: it stays available, accepting writes/reads on reachable replicas and reconciling later, so you may briefly read stale data (**eventual consistency**), tunable per query.

> **"Eventual consistency" and "tunable consistency" defined.** *Eventual consistency*: if writes stop, all replicas eventually converge to the same value. *Tunable consistency* (Cassandra): you pick, per operation, how many replicas must acknowledge — e.g., `ONE`, `QUORUM`, `ALL`. If read-replicas + write-replicas > RF (i.e., `R + W > RF`), you get **strong consistency** for that data; otherwise you trade consistency for latency/availability.

We now have all the primitives: sorted/hashed keys, families, versions, LSM storage (memtable/WAL/SSTable/compaction/Bloom), tombstones/TTL, partitioning, replication, and a consistency model. Sections 3 onward make these mechanical and concrete.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle of writes, reads, compaction, and cluster operations for **HBase** and **Cassandra**, calling out where they differ. Read this section slowly; everything in §6–§9 is a consequence of it.

### 3.1 Cluster topology

**HBase (CP, master/region-server, layered on HDFS):**

```
            +------------------+
            |   ZooKeeper      |  (coordination, master election, liveness)
            +------------------+
                    |
        +-----------+------------+
        | HMaster (active+backup)|  (region assignment, schema, balancing)
        +-----------+------------+
                    |
   +--------+--------+--------+--------+
   | Region | Region | Region | Region |   RegionServers (serve reads/writes)
   | Server | Server | Server | Server |
   +--------+--------+--------+--------+
                    |
            +------------------+
            |       HDFS       |   (durable, replicated block storage)
            | DataNodes (3x)   |
            +------------------+
```

> **"ZooKeeper" defined.** A distributed coordination service that provides a small, strongly-consistent, hierarchical key-value namespace (znodes) with watches and ephemeral nodes. HBase uses it for **master election**, tracking which RegionServers are alive (ephemeral znodes that vanish when a server's session dies), and storing the location of the `hbase:meta` table. It is a separate cluster you must run and operate. (Newer HBase 3.x is reducing the ZooKeeper dependency, but assume it for 2.x.)

> **"HDFS" defined.** The Hadoop Distributed File System: a distributed file system that stores large files as 128 MB (default) blocks replicated across DataNodes (3× default). HBase writes its WAL and SSTables (called **HFiles**) onto HDFS, delegating durability and replication to it. This layering is why HBase is **CP**: HDFS gives strongly consistent, replicated storage, and HBase serves each region from exactly one RegionServer at a time (no divergent replicas to reconcile).

> **"RegionServer" and "region" defined.** A **region** is a contiguous row-key range of a table (a shard). A **RegionServer** is a process that hosts many regions and serves their reads/writes. Each region is owned by exactly one RegionServer at a time. When a region grows past a threshold (default ~10 GB in modern HBase, controlled by `hbase.hregion.max.filesize`), it **splits** into two.

> **"HMaster" defined.** The HBase master process: assigns regions to RegionServers, handles splits/merges and load balancing, manages schema (create/alter table), and coordinates failover. It is not on the read/write data path — clients talk directly to RegionServers — so a brief master outage doesn't stop data access, only administrative operations and recovery.

**Cassandra (AP, masterless/peer-to-peer, gossip):**

```
        Ring of peer nodes (no master)
              N1 ---- N2
             /          \
           N6            N3
             \          /
              N5 ---- N4
   Each node: owns token ranges, gossips state,
   serves any request as "coordinator".
```

> **"Masterless / peer-to-peer" defined.** Every Cassandra node is identical and equal; there is no master and no single point of failure. Any node can receive any request and act as the **coordinator** for it, forwarding to the replicas that own the data and assembling the response. This is the Dynamo heritage and the reason Cassandra is highly available.

> **"Gossip" defined.** A decentralized protocol where each node periodically (every ~1 second) exchanges state ("I'm alive, here's what I know about other nodes' liveness, load, schema version") with a few random peers. Information spreads epidemically. After a few rounds, the whole cluster converges on a consistent view of membership and node health — without any central registry. Cassandra uses gossip plus a **phi-accrual failure detector** (a statistical model that outputs a *suspicion level* rather than a hard up/down, adapting to network jitter) to decide a node is down.

### 3.2 The write path (single node, common LSM mechanics)

Both systems share the LSM write skeleton; the distributed wrapper differs.

**Step by step (write path):**

1. **Client sends a mutation** (`PUT`/`INSERT`) to the responsible server.
   - HBase: client looks up the region for the row key via the `hbase:meta` table (cached locally; bootstrapped from ZooKeeper), then sends directly to the owning RegionServer.
   - Cassandra: client connects to any node = **coordinator**; coordinator hashes the partition key to find the `RF` replica nodes and forwards the write to them.

2. **Append to the durability log first.**
   - HBase: appends to the **WAL** on HDFS (one WAL per RegionServer, shared by its regions). Optionally deferred/async (`Durability` setting). 
   - Cassandra: appends to the **commit log** on local disk. By default `commitlog_sync: periodic` (fsync every `commitlog_sync_period`, default **10000 ms** in recent versions — older default was different), meaning a small window of writes could be lost on power failure; `batch` mode fsyncs before ack (safer, slower).

3. **Apply to the memtable** (in-memory sorted structure).
   - HBase: the region's **MemStore** (one per column family per region), a skip-list (`ConcurrentSkipListMap`) keyed by the full cell coordinates.
   - Cassandra: the **memtable** (a concurrent map/skip-list per table), keyed by partition + clustering + column.

4. **Acknowledge the client.**
   - HBase: ack after WAL + MemStore (durable + visible).
   - Cassandra: ack after enough replicas respond to satisfy the **write consistency level** (CL). E.g., `CL=QUORUM` with `RF=3` means 2 of 3 replicas must ack. Replicas that are down get a **hint** stored by the coordinator (**hinted handoff**, §3.8).

5. **Flush when the memtable is full.** When the memtable crosses a size/heap threshold, it is made immutable, a new memtable takes new writes, and the old one is **flushed** to disk as a new immutable SSTable (HFile in HBase). The corresponding WAL/commit-log segments become eligible for deletion once their data is safely on disk.

> **Key property: writes are sequential and never do random in-place updates.** That is why these systems achieve such high write throughput: the hot path is "append to log + insert into in-memory map," and the disk-heavy work (flush, compaction) is sequential and batched.

### 3.3 The read path (single node)

A read must reconstruct the current value from potentially many sources, because data for one key can be scattered across the memtable and multiple SSTables.

**Step by step (read path):**

1. **Locate the server/replicas** (same routing as writes).
   - Cassandra coordinator picks replicas to read from based on **read CL** (e.g., `QUORUM` reads from 2 of 3) and uses a **snitch** + dynamic snitch to prefer the fastest/closest replica.

> **"Snitch" defined.** Cassandra's component that tells the cluster about the topology — which datacenter and rack each node is in — so replicas are placed across failure domains and the coordinator can prefer nearby/low-latency replicas. The **dynamic snitch** layers on real-time latency measurements to route reads to the fastest replica.

2. **Check the row cache / block cache (if enabled).**
   - HBase: the **BlockCache** (LRU or bucket cache) caches HFile data blocks in memory; the **MemStore** holds recent writes.
   - Cassandra: optional **row cache** and a **key cache** (caches the position of a partition within an SSTable, saving an index lookup); the OS **page cache** is the main read accelerator.

3. **Read the memtable(s)** for any recent versions of the key.

4. **For each candidate SSTable, consult its Bloom filter.** If the filter says "definitely not here," skip it. Otherwise, use the SSTable's **partition/sparse index** to seek to the right block and read it.

5. **Merge all sources** (memtable + matching SSTables), keeping the newest version per cell, applying tombstones (a delete shadows older values), and respecting requested versions/timestamps/TTL.

6. **Cassandra only: reconcile across replicas.** If the read CL requires multiple replicas, the coordinator compares their responses (via lightweight digest reads). On mismatch, it performs **read repair**: it returns the newest value to the client and asynchronously (or before responding, depending on settings) writes the reconciled value back to the stale replica.

> **Why reads can be slow: read amplification.** If a key's data lives in the memtable plus, say, 8 SSTables, and there are many tombstones to skip, one logical read becomes many block reads + a merge. Compaction (next) is what keeps the number of SSTables-per-key small.

### 3.4 Compaction: the engine that keeps LSM healthy

Compaction merges multiple SSTables into fewer (ideally one per key-range), producing sorted output, **dropping superseded versions, expired (TTL) cells, and purgeable tombstones**. It is the price you pay for cheap writes, and it is the source of most operational pain.

**Step by step (a compaction run):**

1. A trigger fires (too many SSTables in a level/bucket, time-based, or manual).
2. The compactor selects a set of input SSTables.
3. It performs a **merge-sort** over them (they're already individually sorted), streaming output to a new SSTable.
4. During the merge it **resolves conflicts** (newest timestamp wins), **drops** versions beyond the retained count, **drops TTL-expired cells**, and **drops tombstones** that are older than the grace period *and* whose shadowed data is also being compacted away.
5. The new SSTable is published; the old input SSTables are deleted; Bloom filters/indexes are rebuilt for the output.

> **Minor vs. major compaction (HBase terms).** A **minor compaction** merges a few small recent HFiles (cheap, frequent). A **major compaction** merges *all* HFiles of a region/store into one and is the only thing that fully removes deleted/expired cells — it is I/O-heavy and is usually scheduled off-peak (default automatic major compaction interval `hbase.hregion.majorcompaction` = **7 days**, often disabled and run manually).

**Cassandra compaction strategies** (set per table) — these are major tuning knobs (§7):

| Strategy | Best for | How it merges | Write amp | Space amp | Read amp |
|---|---|---|---|---|---|
| **STCS** (SizeTieredCompactionStrategy) — default | Write-heavy, general | Merges SSTables of similar size into bigger ones | Low–med | High (can need ~2× free disk during major) | Higher (key may be in several tiers) |
| **LCS** (LeveledCompactionStrategy) | Read-heavy, update-heavy | Organizes into levels of fixed-size SSTables with non-overlapping ranges per level | High (more rewrites) | Low | Low (≤ ~L+1 SSTables per read) |
| **TWCS** (TimeWindowCompactionStrategy) | Time-series with TTL | Buckets SSTables by time window; never mixes windows | Low | Low for TTL data | Low for time-range reads |
| **UCS** (UnifiedCompactionStrategy) | Modern Cassandra 5.0+ | Configurable to behave like STCS or LCS along a continuum | Tunable | Tunable | Tunable |

> **"Leveled compaction" intuition.** Data is organized into levels L0, L1, L2…; each level is ~10× bigger than the one above and, except L0, contains SSTables with **non-overlapping** key ranges. A read therefore needs at most one SSTable per level (plus L0), bounding read amplification — at the cost of more compaction work (higher write amp). This is RocksDB's default and Cassandra's LCS.

### 3.5 HBase region lifecycle and splitting

1. A new table starts with one (or a few **pre-split**) regions.
2. As writes flow, the region's MemStore flushes create HFiles; minor compactions merge them.
3. When the region's largest store exceeds `hbase.hregion.max.filesize`, the RegionServer **splits** the region at the midpoint key into two daughter regions (a fast metadata operation — the HFiles are *referenced*, not rewritten, then cleaned up by later compaction).
4. The HMaster may **rebalance** regions across RegionServers for even load.
5. On RegionServer crash, ZooKeeper detects the dead session, the HMaster reassigns the dead server's regions to live servers, and each new owner **replays the dead server's WAL** to recover unflushed writes before serving. During this window those regions are unavailable (the **CP** tradeoff).

### 3.6 Cassandra cluster lifecycle: ring, vnodes, bootstrap, repair

1. **Token assignment.** Each node owns token ranges. With **vnodes** (default `num_tokens`, historically 256, **16** in newer versions for better operability), each physical node owns many small ranges spread around the ring.
2. **Bootstrap (adding a node).** The new node picks tokens, gossips its arrival, and **streams** the data it now owns from existing replicas; once streaming completes it begins serving. No downtime.
3. **Decommission (removing a node).** The leaving node streams its data to the nodes that will take over its ranges, then exits.
4. **Anti-entropy repair.** Because replicas diverge under AP, you must periodically run `nodetool repair`, which uses **Merkle trees** (hash trees summarizing data ranges) to find and reconcile differences between replicas. Repair must complete within `gc_grace_seconds` to avoid data resurrection (§2.7, §9).

> **"Merkle tree" defined.** A tree of hashes where leaves hash data blocks and parents hash their children. Two replicas can compare trees top-down and only exchange the sub-ranges whose hashes differ, making divergence detection efficient (you don't compare every row).

### 3.7 Consistency mechanics in Cassandra (R + W > RF)

For a given query you choose a **consistency level (CL)**. With `RF=3`:

- `W=QUORUM (2)` and `R=QUORUM (2)`: `2+2=4 > 3` ⇒ at least one read replica overlaps the latest write ⇒ **strong consistency**, still tolerating one node down.
- `W=ONE`, `R=ONE`: fast, highly available, but you may read stale data.
- `W=ALL` or `R=ALL`: strongest but no fault tolerance for that op (any down replica fails it).
- **`LOCAL_QUORUM`**: quorum within the local datacenter — the standard choice for multi-DC deployments to avoid cross-DC latency.

For genuinely linearizable single-row operations, Cassandra offers **lightweight transactions (LWT)** using a **Paxos** consensus round (`IF NOT EXISTS` / `IF col = ?`), at significant latency cost.

> **"Paxos / linearizable" defined.** *Linearizable* = behaves as if there's a single, real-time-ordered copy of the data. *Paxos* is a consensus algorithm letting a set of nodes agree on one value despite failures. Cassandra's LWT runs a Paxos round per operation to implement compare-and-set safely; it is 4 round-trips and an order of magnitude slower than a normal write — use sparingly.

### 3.8 Hinted handoff and read repair (Cassandra's self-healing)

- **Hinted handoff**: if a replica is down at write time, the coordinator stores a **hint** (the missed write) for up to `max_hint_window_in_ms` (default **3 hours**) and replays it when the replica returns. Beyond that window, only repair can fix the gap.
- **Read repair**: detected divergence on reads triggers background reconciliation (see §3.3).

These plus `nodetool repair` are Cassandra's three anti-entropy mechanisms keeping eventual consistency from drifting forever.

### 3.9 End-to-end state machine of a cell (mental model)

```
WRITE -> [WAL/commitlog] -> [memtable] --flush--> [SSTable v1]
                                   |                  |
   another WRITE (same cell) ------+----flush-------> [SSTable v2 (newer ts)]
                                                      |
   DELETE -> tombstone --flush---------------------> [SSTable v3 (tombstone)]
                                                      |
   READ: merge memtable + SSTables (newest wins, tombstone shadows) -> value or "not found"
                                                      |
   COMPACTION: merge v1+v2+v3 -> single SSTable; if tombstone older than
               gc_grace AND data co-compacted -> drop value AND tombstone
```

---

## 4. The complete toolkit

This section enumerates the practical APIs, CLI tools, and config knobs. It is reference material — skim now, return when building.

### 4.1 HBase Java client API (the core classes)

The modern API lives under `org.apache.hadoop.hbase.client`. Workflow: build a `Connection` (heavyweight, share it), get a lightweight `Table`/`Admin` from it, issue operations.

| Class / method | Purpose | Key params / notes |
|---|---|---|
| `ConnectionFactory.createConnection(conf)` | Create a thread-safe, long-lived connection | `conf` from `HBaseConfiguration.create()`; share one per process |
| `Connection.getTable(TableName)` | Lightweight per-thread table handle | Not thread-safe; cheap to create |
| `Connection.getAdmin()` | DDL/admin handle | create/disable/delete table, splits, compactions |
| `Put(byte[] rowkey)` | A write of one or more cells in one row | `.addColumn(family, qualifier, [ts], value)`; atomic per row |
| `Get(byte[] rowkey)` | Read one row | `.addColumn/.addFamily`, `.setMaxVersions(n)`, `.setTimeRange(min,max)`, `.setFilter(...)` |
| `Result` | Read result | `.getValue(f,q)`, `.getColumnCells(f,q)`, `.rawCells()` |
| `Delete(byte[] rowkey)` | Tombstone(s) | `.addColumns` (all versions), `.addColumn` (latest), `.addFamily` |
| `Scan()` | Range scan | `.withStartRow`, `.withStopRow`, `.setCaching(n)`, `.setBatch(n)`, `.setFilter`, `.setReversed(true)` |
| `Table.getScanner(Scan)` | Returns `ResultScanner` (iterable) | Remember to close it |
| `Increment(rowkey)` / `Append(rowkey)` | Atomic counter / append | server-side atomic, no read-modify-write race |
| `checkAndMutate(...)` | Conditional (CAS) write | atomic compare-and-set on one row |
| Filters (`SingleColumnValueFilter`, `PrefixFilter`, `ColumnPrefixFilter`, `KeyOnlyFilter`, `PageFilter`, `FilterList`) | Server-side filtering to reduce data shipped | Push down predicates; combine with `FilterList` |

Common config flags (in `hbase-site.xml`):

| Flag | Meaning | Typical default |
|---|---|---|
| `hbase.hregion.max.filesize` | Region split threshold | ~10 GB |
| `hbase.hregion.memstore.flush.size` | MemStore flush size | 128 MB |
| `hbase.hstore.compaction.min` / `.max` | Files to trigger/limit minor compaction | 3 / 10 |
| `hbase.hregion.majorcompaction` | Auto major-compaction interval | 7 days (often set to 0 = off) |
| `hbase.regionserver.global.memstore.size` | Fraction of heap for all MemStores | 0.4 |
| `hfile.block.cache.size` | Fraction of heap for BlockCache | 0.4 |
| `hbase.client.scanner.caching` | Rows fetched per RPC during scan | 100 (varies) |

### 4.2 HBase shell (CLI) — the everyday commands

```bash
hbase shell
```
```ruby
# DDL
create 'events', {NAME => 'd', VERSIONS => 1, COMPRESSION => 'SNAPPY', TTL => 2592000}, {SPLITS => ['1','2','3']}
list
describe 'events'
disable 'events'; alter 'events', {NAME => 'd', TTL => 7776000}; enable 'events'
drop 'events'   # must disable first

# DML
put 'events', 'user42#1718000000', 'd:type', 'click'
get 'events', 'user42#1718000000', {COLUMN => 'd:type', VERSIONS => 3}
scan 'events', {STARTROW => 'user42#', STOPROW => 'user42$', LIMIT => 10}
delete 'events', 'user42#1718000000', 'd:type'
count 'events', INTERVAL => 100000

# Ops
flush 'events'
major_compact 'events'
split 'events', 'user5'
balancer
status 'detailed'
```

### 4.3 Cassandra CQL (the query language) and schema

> **"CQL (Cassandra Query Language)" defined.** A SQL-*looking* language for Cassandra. The syntax is familiar (`SELECT`, `INSERT`, `CREATE TABLE`) but the semantics are restricted to what the storage engine can do efficiently: you query by partition key (and optionally clustering columns), there are **no joins**, and `WHERE` on non-key columns is rejected unless you add an index or `ALLOW FILTERING` (a performance footgun). It hides the underlying column-family map behind a tabular facade.

```sql
-- Keyspace = top-level namespace with replication settings
CREATE KEYSPACE app WITH replication =
  {'class':'NetworkTopologyStrategy', 'dc1':3};   -- RF=3 in dc1

-- Table: PRIMARY KEY = (partition key, clustering columns...)
CREATE TABLE app.events_by_user (
  user_id   uuid,
  event_ts  timestamp,
  event_id  timeuuid,
  type      text,
  payload   text,
  PRIMARY KEY ((user_id), event_ts, event_id)   -- partition=(user_id); cluster by ts,id
) WITH CLUSTERING ORDER BY (event_ts DESC)
  AND compaction = {'class':'TimeWindowCompactionStrategy',
                    'compaction_window_unit':'DAYS',
                    'compaction_window_size':1}
  AND default_time_to_live = 2592000;   -- 30 days

INSERT INTO app.events_by_user (user_id,event_ts,event_id,type,payload)
  VALUES (?, ?, now(), 'click', '{...}') USING TTL 2592000;

SELECT * FROM app.events_by_user
  WHERE user_id = ? AND event_ts >= ? AND event_ts < ?;  -- partition + clustering range

-- Lightweight transaction (Paxos) -- use sparingly
INSERT INTO app.users (id, email) VALUES (?, ?) IF NOT EXISTS;
```

> **"Partition key vs. clustering columns" defined.** The PRIMARY KEY's first component (or the parenthesized group) is the **partition key** — it is hashed to choose the node, and **all rows with the same partition key live together, sorted by the clustering columns**. The clustering columns define the on-disk sort order within the partition and are what you can do range scans on. Choosing these is *the* central design decision (§5, §6).

### 4.4 Cassandra `nodetool` (the operator's CLI)

| Command | Purpose |
|---|---|
| `nodetool status` | Ring membership, UN/DN state, load, ownership % |
| `nodetool info` | Heap, cache hit rates, uptime for the local node |
| `nodetool tablestats <ks.table>` (a.k.a. `cfstats`) | Per-table SSTable count, read/write latency, tombstones |
| `nodetool tablehistograms` | Latency / partition-size / cell-count percentiles |
| `nodetool compactionstats` / `compactionhistory` | In-flight and past compactions |
| `nodetool repair [-pr] <ks>` | Anti-entropy repair (`-pr` = primary range only, run on every node) |
| `nodetool flush` / `nodetool drain` | Flush memtables; drain before shutdown |
| `nodetool cleanup` | Remove data a node no longer owns after topology change |
| `nodetool decommission` / `removenode` | Graceful / forced node removal |
| `nodetool setcompactionthroughput <MB/s>` | Throttle compaction I/O |
| `nodetool tpstats` | Thread-pool stats (dropped messages, pending tasks) |
| `nodetool getendpoints <ks> <tbl> <key>` | Which nodes hold a given key (debugging placement) |

### 4.5 Other relevant tools

- **HBase**: `hbck2` (consistency repair tool), `RowCounter`/`CellCounter` MapReduce jobs, the REST gateway (`hbase rest`), the Thrift gateway (for non-JVM clients), `Phoenix` (a SQL layer + secondary indexes on top of HBase), bulk-load via `HFileOutputFormat`/`LoadIncrementalHFiles`.
- **Cassandra**: `cqlsh` (interactive shell), `sstableloader` (bulk stream SSTables into a cluster), `sstabledump`/`sstablemetadata` (inspect SSTables, tombstone ratios), `cassandra-stress` (load/benchmark tool), the **DataStax Java Driver** (the standard async client), Spark-Cassandra connector for analytics.
- **Cross-cutting**: metrics export via JMX (both are JVM apps) → Prometheus/Grafana; `jstack`/`jmap`/async-profiler for JVM GC and stalls (GC pauses are a top operational concern for both — §6, §9).

---

## 5. Code examples by use case

Different real scenarios, not variations of one. Java-first for the HBase client and the DataStax driver; CQL/shell where appropriate.

### 5.1 Use case A — High-throughput time-series ingestion (Cassandra, DataStax driver)

Scenario: ingest IoT sensor readings, query "last 24h for a sensor." Partition by sensor + day **bucket** to bound partition size; cluster by time descending.

```sql
CREATE TABLE iot.readings (
  sensor_id   text,
  day_bucket  text,         -- 'yyyy-MM-dd' to cap partition width (one partition per sensor-day)
  reading_ts  timestamp,
  value       double,
  PRIMARY KEY ((sensor_id, day_bucket), reading_ts)
) WITH CLUSTERING ORDER BY (reading_ts DESC)
  AND compaction = {'class':'TimeWindowCompactionStrategy',
                    'compaction_window_unit':'HOURS','compaction_window_size':6}
  AND default_time_to_live = 7776000;   -- 90 days; TWCS drops whole expired SSTables cheaply
```

```java
// DataStax Java Driver 4.x — connection is a heavyweight singleton; share it.
try (CqlSession session = CqlSession.builder()
        .addContactPoint(new InetSocketAddress("cass1", 9042))
        .withLocalDatacenter("dc1")            // required in driver 4.x
        .withKeyspace("iot")
        .build()) {

    // Prepare once, reuse — prepared statements avoid re-parsing and enable token-aware routing.
    PreparedStatement insert = session.prepare(
        "INSERT INTO readings (sensor_id, day_bucket, reading_ts, value) VALUES (?,?,?,?)");

    DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    // Fire-and-forget async writes for throughput; bound in-flight with a semaphore (backpressure).
    Semaphore inflight = new Semaphore(1024);
    for (Reading r : stream) {
        inflight.acquire();
        Instant ts = r.timestamp();
        BoundStatement bs = insert.bind(r.sensorId(), dayFmt.format(ts), ts, r.value())
            .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE); // cheap, available
        session.executeAsync(bs).whenComplete((rs, err) -> {
            inflight.release();
            if (err != null) log.warn("write failed", err); // retry policy handles transients
        });
    }

    // Range read: last 24h for a sensor (assemble the day buckets you need)
    PreparedStatement q = session.prepare(
        "SELECT reading_ts, value FROM readings " +
        "WHERE sensor_id=? AND day_bucket=? AND reading_ts >= ? AND reading_ts < ?");
    // ... bind today's and yesterday's buckets as needed, with LOCAL_QUORUM for read accuracy.
}
```

Why these choices: TWCS keeps each time window in its own SSTable so expiry is a whole-file drop (no tombstone storm); the `day_bucket` prevents unbounded partitions (Cassandra warns past 100 MB / 100k rows per partition); `LOCAL_ONE` writes maximize ingest rate while `LOCAL_QUORUM` reads give accuracy when you need it.

### 5.2 Use case B — HBase point and range reads with server-side filters (Java)

Scenario: an event store keyed `userId#reverseTimestamp` so a user's newest events sort first; fetch a page of a user's events and filter by type server-side.

```java
Configuration conf = HBaseConfiguration.create();
try (Connection conn = ConnectionFactory.createConnection(conf);   // share this per process
     Table table = conn.getTable(TableName.valueOf("events"))) {

    // ----- Write -----
    long reverseTs = Long.MAX_VALUE - System.currentTimeMillis();   // newest sorts first
    byte[] rowKey = Bytes.toBytes("user42#" + String.format("%020d", reverseTs));
    Put put = new Put(rowKey)
        .addColumn(Bytes.toBytes("d"), Bytes.toBytes("type"), Bytes.toBytes("click"))
        .addColumn(Bytes.toBytes("d"), Bytes.toBytes("url"),  Bytes.toBytes("/home"));
    table.put(put);   // atomic for this single row

    // ----- Point read of latest version -----
    Get get = new Get(rowKey).addFamily(Bytes.toBytes("d"));
    Result res = table.get(get);
    byte[] type = res.getValue(Bytes.toBytes("d"), Bytes.toBytes("type"));

    // ----- Range scan: a user's newest N events, filtered to type=click, server-side -----
    Scan scan = new Scan()
        .withStartRow(Bytes.toBytes("user42#"))          // inclusive prefix start
        .withStopRow(Bytes.toBytes("user42$"))           // '$' = '#'+1, exclusive prefix end
        .setCaching(100);                                // rows per RPC: latency vs memory
    SingleColumnValueFilter typeFilter = new SingleColumnValueFilter(
        Bytes.toBytes("d"), Bytes.toBytes("type"),
        CompareOperator.EQUAL, Bytes.toBytes("click"));
    typeFilter.setFilterIfMissing(true);                 // drop rows lacking the column
    scan.setFilter(new FilterList(typeFilter, new PageFilter(20))); // cap to 20 matches
    try (ResultScanner scanner = table.getScanner(scan)) {
        for (Result r : scanner) { /* ... */ }
    }
}
```

The `#`/`$` trick exploits ASCII ordering (`$`=0x24 is `#`=0x23 + 1) to bound a prefix scan. Filters run on the RegionServer, cutting network transfer, but they still **read** the rows (filtering reduces bytes shipped, not bytes scanned).

### 5.3 Use case C — Atomic counters (HBase `Increment` vs. Cassandra counters)

Scenario: page-view counters under concurrency, without read-modify-write races.

HBase (server-side atomic increment):
```java
Increment inc = new Increment(Bytes.toBytes("page#/home"))
    .addColumn(Bytes.toBytes("c"), Bytes.toBytes("views"), 1L);
long newVal = table.increment(inc)
    .getValue(Bytes.toBytes("c"), Bytes.toBytes("views"))
    != null ? Bytes.toLong(/*...*/) : 0; // returns the post-increment value
```

Cassandra (special counter columns; note their caveats):
```sql
CREATE TABLE stats.page_views (page text PRIMARY KEY, views counter);
UPDATE stats.page_views SET views = views + 1 WHERE page = '/home';
```
Cassandra counters are **not idempotent** on retry (a timed-out write that actually succeeded then retried double-counts) and are more expensive than normal writes — acceptable for approximate metrics, dangerous for billing.

### 5.4 Use case D — Query-first modeling with denormalization (Cassandra)

Scenario: you need "messages by conversation (newest first)" *and* "messages by user." There are **no joins**, so you **store the data twice**, one table per query (the **query-first** discipline).

```sql
CREATE TABLE chat.by_conversation (
  conv_id  uuid, msg_ts timestamp, msg_id timeuuid, sender uuid, body text,
  PRIMARY KEY ((conv_id), msg_ts, msg_id)
) WITH CLUSTERING ORDER BY (msg_ts DESC);

CREATE TABLE chat.by_user (
  user_id  uuid, msg_ts timestamp, msg_id timeuuid, conv_id uuid, body text,
  PRIMARY KEY ((user_id), msg_ts, msg_id)
) WITH CLUSTERING ORDER BY (msg_ts DESC);
```
On send, write to **both** tables (ideally via a `BATCH` only when they share the partition key; cross-partition batches are expensive — see §6 anti-patterns). Denormalization trades storage and write fan-out for fast, single-partition reads.

### 5.5 Use case E — Conditional create (compare-and-set) in both systems

HBase `checkAndMutate` (single-row CAS, no extra round-trips):
```java
boolean created = table.checkAndMutate(Bytes.toBytes("user:alice"),
        Bytes.toBytes("d"))
    .qualifier(Bytes.toBytes("email"))
    .ifNotExists()                                  // only if column absent
    .thenPut(new Put(Bytes.toBytes("user:alice"))
        .addColumn(Bytes.toBytes("d"), Bytes.toBytes("email"),
                   Bytes.toBytes("alice@x.com")));
```
Cassandra LWT (Paxos, much slower — use only when truly needed):
```sql
INSERT INTO app.users (id, email) VALUES (?, ?) IF NOT EXISTS;  -- returns [applied]=true/false
```

### 5.6 Use case F — Bulk loading (avoid the write path entirely)

For initial loads of billions of rows, bypass the online write path by generating SSTables/HFiles offline and streaming them in:

- **HBase**: a MapReduce/Spark job with `HFileOutputFormat2` writes pre-sorted HFiles, then `LoadIncrementalHFiles` (the `completebulkload` tool) atomically moves them into the table — no WAL, no flush, no compaction storm.
- **Cassandra**: `CQLSSTableWriter` produces SSTables offline; `sstableloader` streams them to the right replicas.

This is the single biggest throughput lever for migrations and is the idiomatic way to load wide-column stores at scale.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Design the key for your access pattern, and to spread load.** A monotonically increasing key (timestamp, auto-increment id) sends all writes to the last region/partition = a **hotspot**. Mitigations: **salting** (prefix a hash bucket), **field promotion/composite keys** (lead with a high-cardinality field like `userId`), **reversed timestamps** (newest-first within a key), or **hashing**. In HBase, **pre-split** new tables so writes start spread across regions instead of one.
- **Bound partition/row size.** Cassandra partitions should stay under ~100 MB / 100k rows (it logs warnings past compaction-configurable thresholds). HBase rows should not be enormous (a row lives in one region; multi-GB rows defeat splitting and blow up memory). Use **time bucketing** to cap partition width for time-series.
- **Keep SSTable count low** (compaction tuned, not falling behind) to bound read amplification. Watch `compactionstats` / pending compactions.
- **Use prepared statements + token-aware routing** (Cassandra) so the driver sends each query straight to a replica that owns the data, skipping a coordinator hop.
- **Right-size batches and caching.** Large scan `caching` reduces RPCs but raises memory and tail latency; tune per workload. In Cassandra, use `BATCH` only for same-partition atomicity, never as a bulk-insert optimization (it overloads the coordinator).
- **Compression and block size.** Snappy/LZ4 (fast) is the usual default; ZSTD for better ratio at more CPU. Smaller HFile/SSTable block size helps point reads; larger helps scans.

### 6.2 Correctness & concurrency

- **Atomicity is per-row (HBase) / per-partition (Cassandra).** There are **no multi-row/multi-partition ACID transactions** (Cassandra LWT and HBase `checkAndMutate` give single-key CAS only). Do not design workflows that need cross-key atomicity.
- **Clock skew matters in Cassandra.** Last-write-wins is decided by the write timestamp; skewed clocks can cause a logically-older write to win. Use NTP/`chrony` everywhere; prefer server-generated timestamps; for ordering, use `timeuuid`.
- **Tombstone correctness.** Run `nodetool repair` on every node within `gc_grace_seconds` or risk **data resurrection**. Don't lower `gc_grace_seconds` unless you understand the repair cadence.
- **Idempotency.** Make writes idempotent (deterministic keys/timestamps) so retries after timeouts are safe — especially because a "timeout" in an AP system may mean the write *did* succeed. Avoid counters where exactness matters.

### 6.3 Memory & the JVM

Both are JVM systems, so **GC pauses are a leading cause of latency spikes and false failure detection** (a stop-the-world GC can make a node look dead to gossip/ZooKeeper, triggering needless failover).

- Use G1GC (or ZGC/Shenandoah on modern JDKs for low pause) with carefully sized heaps. Very large heaps worsen pauses; Cassandra historically caps heap (e.g., 8–16 GB) and leans on the OS page cache and **off-heap** memtables/Bloom filters/key caches.
- HBase MemStore (`global.memstore.size`) and BlockCache (`hfile.block.cache.size`) together must leave headroom; their sum is bounded (e.g., ≤ 0.8 of heap).
- Monitor GC pause time, promotion failures, and "JVM pause detected" log lines.

### 6.4 Security

- **Authentication & authorization**: both support pluggable auth. HBase integrates with **Kerberos** (the standard Hadoop auth) and cell/column-level ACLs (and Apache Ranger for centralized policy). Cassandra has `PasswordAuthenticator` + role-based `GRANT`/`REVOKE`, and integrates with LDAP/Kerberos via plugins.

> **"Kerberos" defined.** A network authentication protocol using time-limited tickets issued by a trusted Key Distribution Center, so services authenticate clients without sending passwords over the wire. It is the default strong-auth mechanism in the Hadoop/HBase ecosystem.

- **Encryption**: TLS for client↔node and node↔node traffic; encryption-at-rest (transparent disk/SSTable encryption, or rely on encrypted volumes).
- **Network**: never expose ports (HBase 16000/16020, ZooKeeper 2181, Cassandra 9042/7000/7001) to untrusted networks; default installs are notoriously unauthenticated.

### 6.5 Observability

- **Metrics**: export JMX → Prometheus/Grafana. Watch: p99 read/write latency, SSTable count per table, pending compactions, tombstone counts/ratios, dropped messages (`tpstats`), GC pause time, MemStore/cache hit rates, region/partition size distribution, hinted-handoff backlog.
- **Logs**: compaction durations, "tombstone threshold exceeded" warnings (Cassandra logs at `tombstone_warn_threshold`=1000 and aborts queries at `tombstone_failure_threshold`=100000 tombstones scanned), "slow query" / "large partition" warnings.
- **Tracing**: Cassandra `TRACING ON` in `cqlsh` shows the per-replica timeline of a query — invaluable for diagnosing a slow read.

### 6.6 Cost

- Storage cost is dominated by **replication factor × space amplification × raw data**. RF=3 plus uncompacted bloat can mean 4–6× raw. Compression and timely compaction directly cut cost.
- Compute/IO cost is dominated by **compaction**; under-provisioned disks fall behind on compaction, spiraling read amplification. Use fast SSD/NVMe; throttle compaction (`setcompactionthroughput`) to balance against query I/O.

### 6.7 Testing

- Use **embedded/test clusters**: HBase `HBaseTestingUtility` (mini-cluster) and Cassandra via `cassandra-unit` or Testcontainers. Test against a real (small) cluster, not a mock — the LSM and consistency behaviors are the point.
- **Load-test with the real key distribution** (`cassandra-stress`, custom harness) to surface hotspots before production.
- Test failure: kill nodes mid-write, verify CL behavior, verify repair convergence.

### 6.8 Common anti-patterns (memorize these)

| Anti-pattern | Why it hurts | Fix |
|---|---|---|
| Monotonic row/partition key (timestamp, sequence) | Single-shard write hotspot | Salt/hash/lead with high-cardinality field; pre-split |
| Unbounded partition (e.g., partition by `sensor_id` only, forever) | Huge partitions → slow reads, GC, repair pain | Time-bucket the partition key |
| Frequent deletes / queue-like pattern in Cassandra | Tombstone buildup → read amp, query aborts | Use TTL + TWCS; avoid Cassandra for queues |
| `ALLOW FILTERING` in production | Full-cluster scan, unpredictable latency | Model a new table for the query (query-first) |
| Big multi-partition `BATCH` for bulk insert | Coordinator overload, latency spikes | Async single-statement writes, or bulk-load |
| Treating CL=ONE as "good enough" then expecting consistency | Stale reads | Use R+W>RF (e.g., LOCAL_QUORUM both) |
| Skipping `nodetool repair` | Data resurrection, divergence | Schedule repair within gc_grace |
| One giant column family / mixing hot+cold columns (HBase) | Reads/flushes touch unrelated data | Split into families by access pattern |

---

## 7. Advanced topics & deep internals

### 7.1 Compaction tuning in depth

- **STCS** can require up to ~2× the table's size in free disk to compact the largest tier (it merges similarly-sized SSTables into one), and a single huge SSTable can become a "compaction black hole." 
- **LCS** bounds read amplification to roughly (#levels) but multiplies write amplification; great for read-heavy/update-in-place workloads, bad if your disk can't keep up with the extra rewrites.
- **TWCS** is purpose-built for append-only TTL time-series: each window's SSTables never compact with other windows, so when a window fully expires the whole SSTable is dropped — *zero* tombstone churn for the expired data. The trap: **out-of-order/late-arriving writes** and **explicit deletes** break TWCS's assumptions and reintroduce cross-window data.
- **UCS** (Cassandra 5.0) unifies these under scaling parameters so you can dial between size-tiered and leveled behavior without switching strategies.
- HBase exposes a pluggable compaction policy (`ExploringCompactionPolicy` default) and **stripe compaction** / **date-tiered compaction** (DTCP, analogous to TWCS) for time-series.

### 7.2 Bloom filters, indexes, and read short-circuits

- Bloom filter false-positive rate is tunable (`bloom_filter_fp_chance` in Cassandra, default 0.01 for STCS / 0.1 for LCS). Lower FP = fewer wasted SSTable reads but more memory. 
- **Row-level vs. row+column Bloom filters** (HBase `BLOOMFILTER => ROW | ROWCOL`): ROWCOL helps when you read specific columns of sparse rows.
- Cassandra's **partition summary** + **partition index** + **key cache** turn a partition lookup into (ideally) one seek. The **chunk/compression offset** structures map logical positions to compressed on-disk blocks.

### 7.3 Read-repair, speculative retry, and tail-latency control

- **Speculative retry** (Cassandra): if a replica is slow (e.g., past the 99th percentile), the coordinator preemptively asks another replica, cutting tail latency at the cost of extra load. Configurable per table (`speculative_retry = 99p`).
- **Read repair chance** has evolved: modern Cassandra does **blocking read repair** when a digest mismatch is detected at quorum reads, ensuring monotonic reads for that key.

### 7.4 Consistency edge cases

- **Read-your-writes** is not guaranteed at `CL=ONE`/`ONE`. Use `LOCAL_QUORUM`+`LOCAL_QUORUM` or session-pinned consistency.
- **Lightweight transactions** use Paxos with a separate `serial_consistency` (`SERIAL` or `LOCAL_SERIAL`); mixing LWT and non-LWT writes to the same partition can interleave surprisingly — keep partitions LWT-only if you rely on them.
- HBase provides **strong consistency for single-row reads/writes** out of the box (one region owner), and **MVCC** internally to give readers a consistent snapshot during concurrent writes.

> **"MVCC (Multi-Version Concurrency Control)" defined.** A technique where readers see a consistent snapshot (a particular version) of data while writers create new versions, so reads never block writes and vice versa. HBase uses a read-point/write-point sequence so a scan sees a stable view even as MemStore mutates.

### 7.5 HBase ↔ HDFS interactions and data locality

- HFiles live in HDFS; a RegionServer ideally runs **co-located** with the DataNode holding its region's blocks, so reads are local (HDFS short-circuit reads bypass the network). After a region moves or HDFS rebalances, **locality degrades** until the next major compaction rewrites blocks locally. Low locality is a classic cause of latency regression.
- HBase **replication** (cluster-to-cluster, async via WAL shipping) enables cross-DC DR; Cassandra achieves multi-DC natively via `NetworkTopologyStrategy` and per-DC replication factors.

### 7.6 Cassandra storage-engine evolution

- Pre-3.0 storage was a thinner column map; **3.0 introduced a new storage engine** with a richer row/cell structure and big space savings. 
- **4.0** added incremental/zero-copy streaming (faster bootstrap/repair) and audit logging.
- **5.0** added **Storage-Attached Indexes (SAI)** — far better secondary indexes than the old `2i`/SASI — plus vector search, UCS, and trie-based memtables/indexes. Flag SAI as version-specific (5.0+).

> **"Secondary index" caveats defined.** A secondary index lets you query by a non-partition-key column. In Cassandra, the legacy local **2i** index stores an index *per node* for that node's data, so a query without the partition key must **scatter-gather across all nodes** — fine for low-cardinality, high-replication lookups, terrible for high-cardinality. The query-first denormalization pattern (or SAI in 5.0+) is usually preferred. HBase has no native global secondary index; **Apache Phoenix** adds them on top.

### 7.7 ScyllaDB and Google Cloud Bigtable (the cousins)

- **ScyllaDB**: a C++ reimplementation of Cassandra (CQL-compatible) using a shard-per-core, shared-nothing thread-per-core architecture and its own I/O scheduler — eliminates JVM GC pauses and often delivers far higher per-node throughput. Same data model, different runtime.
- **Google Cloud Bigtable**: the managed, original BigTable — single-CF-family-per-table-ish, range-partitioned, no compaction/ops for you, HBase-compatible client. Strong single-row consistency; no multi-row transactions. A good "wide-column without operating it" option.

---

## 8. Tradeoffs & decision frameworks

### 8.1 HBase vs. Cassandra at a glance

| Dimension | HBase | Cassandra |
|---|---|---|
| CAP stance | **CP** (consistency over availability during partition) | **AP**, tunable toward CP per query |
| Topology | Master + RegionServers, on **HDFS**, **ZooKeeper** | Masterless peer-to-peer, **gossip** |
| Partitioning | **Range** (regions, auto-split) → global key order, range scans across keyspace | **Hash** (token ring, vnodes) → no global order; range scans only within a partition |
| Replication | Delegated to HDFS (3× blocks); one region owner | DB-layer, RF per keyspace/DC; all replicas serve |
| Consistency | Strong single-row by default; MVCC | Eventual; tunable CL (`R+W>RF` for strong) |
| Writes | High; WAL on HDFS | Very high; commit log local |
| Reads | Strong; can do global range scans | Fast within partition; QUORUM for accuracy |
| Multi-DC | Async cluster replication (WAL shipping) | Native, first-class (`NetworkTopologyStrategy`) |
| Ops burden | Higher (HDFS + ZK + HBase) | Self-contained, but repair/compaction discipline |
| Sweet spot | Strong-consistency big-data on Hadoop stack; full-table range scans; Phoenix SQL | Always-on, multi-DC, write-heavy, time-series |
| Query language | Java API / shell / Phoenix SQL | CQL |

### 8.2 Wide-column vs. other paradigms

| Need | Best paradigm | Why not wide-column |
|---|---|---|
| Ad-hoc queries, joins, multi-row ACID | Relational (PostgreSQL/MySQL) | WC has no joins, single-key atomicity only |
| Flexible nested documents, per-doc queries | Document (MongoDB) | WC is flat key→column map, weak secondary queries |
| Simple cache/session, lowest latency KV | Key-value (Redis/DynamoDB) | WC adds storage/ops you don't need |
| Heavy analytical aggregation/scans of columns | Columnar OLAP (ClickHouse/BigQuery/Parquet) | WC optimizes point/range OLTP, not column aggregation |
| Graph traversal | Graph (Neo4j) | WC has no traversal primitives |
| Massive write-throughput OLTP by key, time-series, wide-sparse | **Wide-column** | — |

> **Crucial distinction: "wide-column store" ≠ "columnar (column-oriented) database."** A *columnar OLAP* store (ClickHouse, Parquet, BigQuery) stores each **column's values contiguously** to make analytical scans/aggregations over a few columns blazing fast. A *wide-column store* stores each **row's cells together per column family** and is optimized for OLTP point/range access by key. They share the word "column" and almost nothing else. Confusing them is a classic interview trap.

### 8.3 Decision rules

**Use a wide-column store when:** writes ≥ reads at scale; access is by entity key or key-range; data is time-series / append-mostly / wide-sparse; you need linear scale-out and node-failure tolerance; you can design tables around known queries.

**Use HBase specifically when:** you already run Hadoop/HDFS; you need strong single-row consistency and *global* range scans; you want SQL via Phoenix; one strongly-consistent DC is fine.

**Use Cassandra specifically when:** you need always-on availability across multiple datacenters; you have very high, geographically distributed write volume; eventual/tunable consistency is acceptable; you want no master and minimal external dependencies.

**Avoid wide-column entirely when:** queries are ad-hoc/relational; you need cross-row transactions; the dataset fits one node; you need rich aggregation/analytics (use OLAP); strong secondary-index querying is central.

---

## 9. Failure modes & debugging

Real production pathologies, their symptoms, and the exact tools to diagnose them.

### 9.1 Hotspotting (the #1 design failure)

**Symptom:** one RegionServer/node is pegged at high CPU/IO and high latency while others idle; uneven `nodetool status` ownership/load; HBase region with runaway write rate.
**Cause:** monotonic key (timestamp/sequence) or low-cardinality partition key.
**Diagnose:** HBase Web UI per-region request counts; `nodetool tablehistograms` (skewed partition sizes); per-node metrics in Grafana.
**Fix:** redesign the key (salt/hash/composite/reversed-ts); pre-split HBase tables; bucket time-series partitions.

### 9.2 Tombstone overload (the #1 Cassandra runtime failure)

**Symptom:** read latency spikes; `cqlsh` queries fail with `TombstoneOverwhelmingException`; logs show "Read N live rows and M tombstone cells" warnings; query aborts at `tombstone_failure_threshold` (100000).
**Cause:** heavy deletes, TTL on wide rows, queue-like usage, or collection overwrites (which delete+rewrite).
**Diagnose:** `sstablemetadata <file>` shows the estimated droppable tombstone ratio; enable `TRACING ON` to see tombstones scanned per query; watch `nodetool tablestats` tombstone metrics.
**Fix:** switch to TWCS + TTL; avoid range deletes; ensure `nodetool repair` runs so tombstones can be purged after `gc_grace_seconds`; sometimes run a major compaction (carefully) to reclaim.

### 9.3 Read amplification / too many SSTables

**Symptom:** rising read latency, high SSTables-per-read.
**Cause:** compaction falling behind (slow disk, throttled too low, STCS with huge tier).
**Diagnose:** `nodetool compactionstats` (large pending count), `nodetool tablestats` SSTable count, HBase storefile count per region.
**Fix:** raise compaction throughput; add faster disks; reconsider strategy (LCS for read-heavy); trigger compaction.

### 9.4 GC pauses / "node flapping"

**Symptom:** intermittent timeouts; nodes marked DOWN then UP in gossip; "JVM pause detected" or long GC logs; in HBase, RegionServers abort because their ZooKeeper session expired during a pause.
**Cause:** oversized/poorly tuned heap, allocation pressure from large reads/batches.
**Diagnose:** GC logs, `jstat -gcutil`, async-profiler; correlate pause times with latency spikes.
**Fix:** tune GC (G1/ZGC), right-size heap, push memtables/caches off-heap, reduce batch/scan sizes, raise ZooKeeper session timeout modestly.

### 9.5 Large partition / wide row

**Symptom:** Cassandra logs "Writing large partition" / compacting a multi-GB partition stalls; HBase region won't split because one row is huge.
**Diagnose:** `nodetool tablehistograms` partition-size percentiles; `sstablemetadata`.
**Fix:** re-bucket the partition key; cap collection sizes; split wide rows by adding a bucket to the key.

### 9.6 Repair pain / data resurrection

**Symptom:** deleted data reappears; repairs take forever or stream huge amounts.
**Cause:** repairs not run within `gc_grace_seconds`; heavy entropy from `CL=ONE` writes; oversized vnode count amplifying repair ranges.
**Diagnose:** `nodetool repair` logs, validation-compaction load; check last successful repair time per node.
**Fix:** schedule incremental/primary-range repair on a cadence < gc_grace; reduce `num_tokens` (newer clusters use 16); consider tools like Cassandra Reaper to orchestrate repair.

### 9.7 HBase split/assignment storms & low data locality

**Symptom:** latency jumps after region moves; many regions in transition; `hbck2` reports inconsistencies.
**Cause:** balancer churn, RegionServer crashes, HDFS rebalancing reducing locality.
**Diagnose:** HBase Master UI "regions in transition"; per-region locality metric; `hbck2`.
**Fix:** run a major compaction to restore locality; tune balancer; investigate the crashing RegionServer (often GC).

### 9.8 Real-world incident patterns (illustrative)

- **The Friday-night queue.** A team used a Cassandra table as a work queue (insert job, read, delete). Tombstones from deletes accumulated faster than `gc_grace` allowed purging; weekend read latency exploded and queries began aborting. Lesson: never build a queue on a tombstone-based store; use TTL + TWCS or a purpose-built queue.
- **The timestamp row key.** An HBase event table keyed purely by event time funneled all ingest to the last region; one RegionServer saturated while the cluster sat 90% idle. Pre-splitting and salting fixed throughput overnight.
- **The GC-induced cascade.** A long G1 pause on a heavily-loaded Cassandra node exceeded gossip/failure-detector timeouts; the node was marked down, its load shifted to peers, which then GC'd under the extra load — a flapping cascade. Heap right-sizing and speculative retry stabilized it.

---

## 10. Interview drill

Each question: a crisp model answer, then deep-probe follow-ups with answers. Senior-signal questions marked **[S]**.

**Q1. Explain the wide-column data model from scratch.**
*Model answer:* It's a sparse, distributed, persistent multidimensional sorted map: `(row key, column family, column qualifier, timestamp) -> value bytes`. Rows are addressed by an opaque key; columns are grouped into a few statically-declared families stored together on disk; qualifiers within a family are arbitrary and per-row (so rows are wide and sparse); cells keep timestamped versions. Storage is sorted by key (range-partitioned in HBase) or hash-distributed by partition key (Cassandra).
- *Probe: Why "sparse"?* Columns exist only where written; no NULLs, no reserved slots — storage is proportional to data present.
- *Probe: Why column families?* On-disk co-location and shared tuning (compression/TTL/versions); a scan of one family ignores the others.

**Q2. Walk through the LSM write and read paths.**
*Model answer:* Write: append to WAL/commit log, insert into in-memory memtable, ack; when full, flush memtable to an immutable sorted SSTable. Read: merge memtable + relevant SSTables (newest version wins, tombstones shadow), using Bloom filters and sparse indexes to skip/seek; compaction periodically merges SSTables and drops obsolete versions/tombstones.
- *Probe: Why is write throughput so high?* Hot path is sequential log append + in-memory insert; expensive I/O is batched/sequential in flush and compaction.
- *Probe: What causes a slow read?* Read amplification — many SSTables and tombstones to merge; mitigated by compaction and Bloom filters.

**Q3. What is a tombstone and why does it matter?**
*Model answer:* A delete marker written like any other cell, shadowing older data; physically removed only at compaction after a grace period (`gc_grace_seconds`, default 10 days) ensuring the delete propagated to all replicas. Excess tombstones cause read amplification and can abort queries.
- *Probe: What is data resurrection?* If a tombstone is purged before a lagging replica saw the delete, the old value can re-propagate; repair within gc_grace prevents it.
- *Probe: How avoid tombstone storms for TTL time-series?* TWCS so whole expired SSTables drop without per-cell tombstones.

**Q4. [S] You must store 1M writes/sec of clickstream and query "last hour for a user." Design the table(s) and key. Justify.**
*Model answer:* Wide-column fits (write-heavy, key+range access). Cassandra table `events_by_user` with `PRIMARY KEY ((user_id, hour_bucket), event_ts)` clustering `event_ts DESC`; TWCS + TTL. `user_id` gives high-cardinality spread (no hotspot); `hour_bucket` caps partition size; clustering by time gives an efficient range scan; LOCAL_ONE writes for ingest, LOCAL_QUORUM reads for accuracy. Bulk-load for backfills.
- *Probe: Why bucket the partition?* Unbounded `user_id`-only partitions grow forever → huge partitions, GC, repair pain.
- *Probe: HBase alternative?* Key `userId#reverseTs`, pre-split, salt if a few whale users dominate; gains global range scans and strong consistency at higher ops cost.

**Q5. [S] HBase vs. Cassandra — when do you pick which, and what's the deepest reason?**
*Model answer:* HBase is CP on HDFS+ZooKeeper with range partitioning and strong single-row consistency and global range scans; Cassandra is AP, masterless, gossip-based, hash-partitioned with tunable consistency and native multi-DC. The deepest reason is the CAP stance interacting with partitioning: HBase's single-region-owner + HDFS gives strong consistency but unavailability of a region during failover; Cassandra's multi-replica-serves + gossip gives availability but eventual consistency. Pick HBase for strong-consistency Hadoop-stack workloads and global scans; Cassandra for always-on, multi-DC, write-heavy.
- *Probe: Can Cassandra be strongly consistent?* Yes per-operation when `R+W>RF` (e.g., LOCAL_QUORUM both), or linearizably via Paxos LWT (slow).
- *Probe: Why can't you global-range-scan in Cassandra?* Partition keys are hashed onto the ring, so they aren't stored in key order — only clustering columns within a partition are ordered.

**Q6. Explain compaction and its strategies.**
*Model answer:* Compaction merge-sorts immutable SSTables into fewer, dropping superseded versions, expired cells, and purgeable tombstones — the price of cheap writes, paid in write amplification. STCS (size-tiered, write-friendly, higher read/space amp), LCS (leveled, read-friendly, higher write amp), TWCS (time-windowed for TTL time-series), UCS (5.0 unified). HBase: minor vs. major; only major fully purges deletes.
- *Probe: Why does LCS bound read amp?* Non-overlapping ranges per level ⇒ at most one SSTable per level per key.
- *Probe: TWCS trap?* Late/out-of-order writes or explicit deletes mix windows and break the whole-SSTable-drop optimization.

**Q7. What guarantees of a relational DB do you lose, and how do you cope?**
*Model answer:* No joins, no multi-row/partition ACID transactions, weak ad-hoc/secondary-index queries, no foreign keys. Cope via query-first modeling and denormalization (a table per query), single-key CAS (checkAndMutate/LWT) where you truly need atomicity, application-side joins, and SAI/Phoenix for limited indexing.
- *Probe: Cost of denormalization?* More storage and write fan-out; risk of inconsistency between copies (mitigate with idempotent writes/repair).
- *Probe: When is `ALLOW FILTERING` ok?* Rarely — only tiny/bounded data or admin tooling; never on the hot path.

**Q8. How do consistency levels work in Cassandra; what's `R+W>RF`?**
*Model answer:* Per-operation you choose how many replicas must respond. If read replicas + write replicas exceed RF, a read is guaranteed to intersect the latest write ⇒ strong consistency, while still tolerating failures (e.g., QUORUM both with RF=3 survives one down node). LOCAL_QUORUM keeps quorums within a DC for multi-DC latency.
- *Probe: What is hinted handoff?* Coordinator stores missed writes for a down replica (default 3h window) and replays on return.
- *Probe: What is read repair?* On digest mismatch at read time, reconcile and write back the newest value to stale replicas.

**Q9. Why are these systems prone to GC-related instability, and how do you mitigate?**
*Model answer:* They're JVM apps; a stop-the-world GC pause can exceed gossip/ZooKeeper timeouts, marking a healthy node down and triggering failover/load-shift cascades. Mitigate with G1/ZGC, right-sized (not huge) heaps, off-heap memtables/caches/Bloom filters, bounded batch/scan sizes, and pause monitoring.
- *Probe: How does ScyllaDB sidestep this?* It's C++, thread-per-core, no JVM GC.
- *Probe: Symptom to watch?* "JVM pause detected"/long GC logs correlated with latency spikes and node flapping.

**Q10. Design a key to avoid hotspots for monotonically increasing data.**
*Model answer:* Don't lead with the monotonic field. Options: salt with a hash bucket prefix (`bucket#timestamp`) and scatter-gather on read; lead with a high-cardinality field (`userId#...`); hash the key; in HBase pre-split into the expected key space. Trade scan-locality vs. spread depending on whether you need range scans.
- *Probe: Downside of salting?* A range scan must hit every salt bucket (fan-out).
- *Probe: HBase-specific lever?* Pre-splitting + a custom region split policy.

**Q11. [S] Your Cassandra read p99 jumped 10× overnight with no traffic change. Diagnose.**
*Model answer:* Hypotheses in order: tombstone buildup (check logs for tombstone warnings, `sstablemetadata` droppable ratio, `TRACING`), compaction falling behind (`nodetool compactionstats` pending, SSTables-per-read up), GC pauses (GC logs), a large partition (`tablehistograms`), or a hot node (`nodetool status` skew). Fix per root cause: change to TWCS/repair for tombstones, raise compaction throughput/faster disk, tune GC, re-bucket partitions.
- *Probe: Which single command first?* `nodetool tablestats`/`tablehistograms` for the table — surfaces SSTable count, tombstones, partition size in one place.
- *Probe: How confirm tombstones are the cause?* `TRACING ON` shows "tombstone cells read" per query.

**Q12. What's the difference between a wide-column store and a columnar (column-oriented) analytics database?**
*Model answer:* Wide-column (BigTable/HBase/Cassandra) is OLTP — stores a row's cells together per column family, optimized for point/range access by key, high write throughput. Columnar OLAP (ClickHouse/Parquet/BigQuery) stores each column's values contiguously to make analytical aggregations/scans over few columns fast. Same word, different goals; don't conflate.
- *Probe: Could you do analytics on Cassandra?* Possible but suboptimal; pair with Spark or export to an OLAP store.

---

## 11. Glossary

- **AP / CP**: CAP outcomes — Availability vs. Consistency under a network partition. Cassandra is AP; HBase is CP.
- **Anti-entropy**: mechanisms that converge divergent replicas (read repair, hinted handoff, `nodetool repair`).
- **BigTable**: Google's 2006 wide-column store; the model's origin.
- **BlockCache (HBase)**: in-memory cache of HFile data blocks.
- **Bloom filter**: probabilistic set membership test ("definitely no" / "probably yes") used to skip SSTables.
- **Bootstrap (Cassandra)**: a new node joining and streaming its owned data.
- **CAP theorem**: under a partition you must choose Consistency or Availability.
- **Cell**: the value at `(row, family, qualifier, timestamp)`.
- **Clustering column (Cassandra)**: PRIMARY KEY component(s) after the partition key; define on-disk sort order within a partition.
- **Column family**: statically-declared group of columns stored together on disk.
- **Column qualifier**: the (arbitrary, per-row) column name within a family.
- **Commit log (Cassandra)**: the write-ahead durability log.
- **Compaction**: background merge of SSTables that purges obsolete versions/tombstones.
- **Consistency level (CL)**: per-operation replica acknowledgment requirement in Cassandra (ONE/QUORUM/ALL/LOCAL_QUORUM…).
- **Consistent hashing**: ring-based key placement minimizing reshuffling on membership change.
- **Coordinator (Cassandra)**: the node handling a client request and forwarding to replicas.
- **CQL**: Cassandra Query Language — SQL-like but join-less and key-centric.
- **Denormalization**: storing data redundantly, one table per query (query-first modeling).
- **Dynamo**: Amazon's 2007 AP key-value design that influenced Cassandra's distribution.
- **Eventual consistency**: replicas converge over time absent new writes.
- **Gossip**: decentralized epidemic protocol spreading cluster state.
- **gc_grace_seconds**: grace period before a tombstone may be purged (default 864000s/10 days).
- **HDFS**: Hadoop Distributed File System; HBase's durable, replicated storage layer.
- **HFile**: HBase's SSTable format on HDFS.
- **Hinted handoff**: coordinator stores writes for a down replica and replays them later.
- **HMaster / RegionServer / Region**: HBase master, data-serving process, and a key-range shard.
- **Hotspot**: a single shard overloaded due to a skewed/monotonic key.
- **Kerberos**: ticket-based network authentication (Hadoop/HBase default).
- **LCS / STCS / TWCS / UCS / DTCP**: compaction strategies (leveled / size-tiered / time-window / unified / date-tiered).
- **Linearizable**: behaves as a single real-time-ordered copy (Cassandra LWT via Paxos).
- **LSM-tree**: Log-Structured Merge-tree — the memtable/SSTable/compaction storage engine.
- **LWT (lightweight transaction)**: Cassandra compare-and-set via Paxos (`IF` clauses).
- **Memtable / MemStore**: in-memory sorted write buffer (Cassandra / HBase).
- **Merkle tree**: hash tree used by repair to find divergent ranges efficiently.
- **MVCC**: multi-version concurrency control giving readers consistent snapshots.
- **NetworkTopologyStrategy**: Cassandra replication placement aware of DCs/racks.
- **Partition key**: hashed PRIMARY KEY component selecting the node (Cassandra).
- **Paxos**: consensus algorithm underlying Cassandra LWT.
- **Phoenix (Apache)**: SQL + secondary indexes layer over HBase.
- **Pre-splitting (HBase)**: creating a table with multiple regions up front to spread load.
- **Read/Write/Space amplification**: extra reads/writes/space LSM incurs vs. logical data.
- **Read repair**: reconciling stale replicas detected during a read.
- **Replication factor (RF)**: number of replicas per partition.
- **Resurrection**: deleted data reappearing because a tombstone was purged too early.
- **Row key**: the primary, sorted (HBase) row identifier.
- **Salting**: prefixing a key with a hash bucket to spread writes.
- **SAI (Storage-Attached Index)**: Cassandra 5.0 improved secondary index.
- **Secondary index (2i/SASI)**: index on non-key columns; scatter-gather in Cassandra.
- **Snitch**: Cassandra topology/latency awareness for replica placement and routing.
- **Speculative retry**: preemptively querying another replica to cut tail latency.
- **SSTable**: immutable, sorted on-disk file (with index + Bloom filter).
- **Tombstone**: delete marker; removed at compaction after the grace period.
- **TTL**: time-to-live; auto-expiry of cells.
- **Token / token ring / vnodes**: Cassandra's hashed key space, the ring, and per-node sub-ranges.
- **WAL (write-ahead log)**: HBase's durability log (replayed on crash recovery).
- **ZooKeeper**: coordination service for HBase (election, liveness, meta location).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Model**: `(row, family, qualifier, timestamp) -> bytes`; wide + sparse; few families, unlimited per-row qualifiers; multiple versions; TTL.
- **Storage = LSM**: write → WAL + memtable → flush → immutable SSTable; read → merge memtable + SSTables (Bloom + index, newest wins, tombstones shadow); compaction merges & purges. Three amps: write / read / space.
- **Deletes = tombstones**; purged at compaction after `gc_grace_seconds` (default 10 days); repair within that window or risk resurrection.
- **HBase = CP**: master + RegionServers on HDFS + ZooKeeper; range partitioning (regions, auto-split, pre-split); strong single-row consistency; global range scans; Phoenix for SQL.
- **Cassandra = AP**: masterless, gossip, token ring + vnodes; hash partitioning (no global order); tunable CL; `R+W>RF` ⇒ strong; LOCAL_QUORUM for multi-DC; LWT (Paxos) for CAS; TWCS+TTL for time-series.
- **Key design rules**: never lead with a monotonic field (hotspot); bound partition/row size (time-bucketing; Cassandra <100 MB/100k rows); query-first denormalization (no joins); avoid `ALLOW FILTERING`; avoid queue patterns/heavy deletes.
- **Compaction**: STCS (write-friendly), LCS (read-friendly), TWCS (TTL time-series), UCS (5.0).
- **Ops watch-list**: SSTable count, pending compactions, tombstone ratio, GC pauses, partition-size skew, hinted-handoff backlog, dropped messages, data locality (HBase).
- **Tools**: HBase `hbase shell`, `Admin`/`Table` API, `hbck2`, Phoenix; Cassandra `cqlsh`, `nodetool` (status/tablestats/tablehistograms/compactionstats/repair), `sstablemetadata`, `cassandra-stress`, DataStax driver.
- **Not the same as** columnar OLAP (ClickHouse/Parquet) — that's analytics; wide-column is OLTP by key.
- **Defaults to remember**: `gc_grace_seconds`=864000s; tombstone warn/fail = 1000/100000; HBase region split ~10 GB; MemStore flush 128 MB; HDFS block 128 MB / 3× replication; tombstone-free TTL drops via TWCS.

### 12.2 Self-test (no answers — recall actively)

1. Trace, step by step, what happens from the moment a client issues a write until it is durable and visible, in *both* HBase and Cassandra — and name where they diverge.
2. You see Cassandra read p99 climbing and logs warning about tombstones. Give three distinct root causes and the exact `nodetool`/SSTable command you'd use to confirm each.
3. Design row/partition keys and tables for a system that must answer both "newest 50 posts by a user" and "newest 50 posts in a topic," at high write rate, with no hotspots. Justify every key choice.
4. Explain `R + W > RF`, give a concrete RF=5 example that tolerates two node failures while staying strongly consistent, and state what you lose vs. `CL=ONE`.
5. Why does an LSM-tree make writes cheap but potentially make reads and disk usage expensive? Name the three amplifications and the knob (compaction strategy) you'd change for a read-heavy vs. a TTL-time-series workload.
6. A teammate proposes using Cassandra as a job queue (insert, claim, delete). Explain precisely why this fails over time and what storage mechanism is responsible.
7. Distinguish a wide-column store from a columnar OLAP database in terms of on-disk layout and the workload each optimizes — then name one system in each category.

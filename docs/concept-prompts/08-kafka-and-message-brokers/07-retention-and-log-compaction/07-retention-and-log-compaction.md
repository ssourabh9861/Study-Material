# Retention & Log Compaction in Apache Kafka

> A definitive engineering-handbook chapter for senior JVM/backend developers who want to master Kafka retention and log compaction from first principles through deep internals — enough to design with it, operate it, debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What this is

Apache Kafka stores every message a producer sends in an **append-only log** on disk. A *log* here is not an application logfile — it is an ordered, immutable sequence of records, each at a fixed numeric position called an **offset** (0, 1, 2, …). Kafka never edits a record in place; it only ever appends to the end. That design is the source of Kafka's throughput, but it raises an obvious operational question: **a log that only grows will eventually fill every disk.** So Kafka needs a strategy for *removing old data*. That strategy is **retention**, and Kafka offers two fundamentally different flavors of it:

1. **Delete retention** (the default): throw away records that are *too old* (by time) or once the log grows *too large* (by size). This is a time/space window — old data simply ages out.
2. **Log compaction**: instead of deleting by age, keep *the most recent value for every distinct key* forever, and garbage-collect only the superseded older values for those keys. This turns a topic into a durable, replayable **changelog** or **snapshot** of the latest state per key.

You choose between (or combine) these with one config knob, **`cleanup.policy`**, whose values are `delete` (the default), `compact`, or `compact,delete` (both).

### 1.2 The problem it solves

- **Delete retention** solves *bounded storage for streaming/event data* whose value decays with time. A topic of click events, metrics, or transient commands does not need to live forever; you keep, say, 7 days and discard the rest.
- **Compaction** solves a different problem: *materializing the latest state of a keyed dataset* inside Kafka itself, so that a consumer can reconstruct the entire current state by replaying the topic from the beginning, without that replay growing unbounded over time. It is the mechanism behind:
  - **Kafka Streams / ksqlDB state stores** (their fault-tolerance changelogs are compacted topics).
  - **Kafka Connect offset/config/status topics**.
  - **Kafka's own `__consumer_offsets` topic** (it stores the latest committed offset per consumer-group/partition — compacted).
  - **Change Data Capture (CDC)** pipelines (Debezium et al.) where the topic holds the latest row image per primary key.
  - **Event-carried state transfer** between microservices, where one service publishes "here is the current state of entity X" and others build a local cache by reading the compacted topic.

### 1.3 When you reach for each

| You want… | Use |
|---|---|
| A stream of events that expire (logs, metrics, telemetry, transient commands) | `delete` (time- and/or size-based) |
| The current value per key, replayable from the start, bounded by *key cardinality* not by time | `compact` |
| The current value per key, **and** a hard floor under which even tombstones/old keys age out (e.g. GDPR delete, bounded changelog) | `compact,delete` |

### 1.4 One-paragraph mental model

> Think of a Kafka partition as an infinite ledger you can only append to. **Delete retention** is a janitor who, every few minutes, walks to the *oldest pages* and shreds any page group whose newest entry is older than your retention window (or once the ledger exceeds your size cap). **Log compaction** is a different janitor: it reads the whole ledger, and for each *account number* (key) keeps only the most recent balance entry, tearing out the stale duplicate entries in between — so the ledger stays proportional to the number of distinct accounts, not the number of transactions. A special "delete this account" entry is a **tombstone** (a record with a null value); the compaction janitor keeps it around just long enough (`delete.retention.ms`, default 24h) for every reader to learn the account is gone, then removes it too. Crucially, this janitor is **asynchronous and best-effort** — at any instant the *tail* of the log (the active, recently-written part) still contains duplicates, so compaction guarantees the latest value *survives*, never that duplicates are *immediately gone*.

---

## 2. Foundations from first principles

Before retention makes sense, you must understand how a Kafka log is physically laid out. Almost every behavior in this chapter is a consequence of that layout.

### 2.1 Topics, partitions, replicas

- **Topic**: a named stream of records (e.g. `orders`). Purely logical.
- **Partition**: a topic is split into N partitions, each an independent ordered log. Partition is the unit of parallelism *and* the unit of ordering — Kafka only guarantees order *within* a partition. Records are routed to a partition by hashing the record **key** (or round-robin if the key is null). **This is the first reason keys matter for compaction:** compaction works per-key *within a partition*, and the default partitioner guarantees that all records with the same key land in the same partition. (If you use a custom partitioner that breaks this, compaction's "latest value per key" semantics break across partitions.)
- **Replica**: each partition is replicated to `replication.factor` brokers for fault tolerance. One replica is the **leader** (handles reads/writes), the others are **followers** that copy the leader's log. Retention and compaction run *independently on each replica* but, because followers replicate the leader's byte stream, they converge to the same logical state.
- **Broker**: a Kafka server process. A cluster is a set of brokers.

> **Adjacent term — ZooKeeper / KRaft.** Historically Kafka used **ZooKeeper** (a separate distributed coordination service that stores cluster metadata: which broker is controller, topic configs, partition leaders). Modern Kafka (3.3+, GA in 3.5; ZooKeeper removed entirely in Kafka 4.0) uses **KRaft** ("Kafka Raft"), where metadata lives in an internal Kafka log managed by the **Raft** consensus protocol. *Raft* is an algorithm for getting a set of machines to agree on an ordered log of changes even when some fail. None of this changes retention/compaction semantics, but it's where topic configs are stored and propagated.

### 2.2 The on-disk log: segments, offsets, indexes

A partition's log is **not** one giant file. It is a directory (e.g. `/var/lib/kafka/data/orders-3/` for partition 3 of topic `orders`) split into **segments**:

- Each segment is a pair (really a trio+) of files named by the **base offset** — the offset of the first record in that segment — zero-padded to 20 digits:
  - `00000000000000000000.log` — the actual records.
  - `00000000000000000000.index` — a sparse **offset index** mapping offset → byte position in the `.log` file (so a consumer asking for offset 12345 can seek near it without scanning).
  - `00000000000000000000.timeindex` — a sparse **time index** mapping timestamp → offset (so time-based lookups and time-based retention can find boundaries).
  - Optionally `.snapshot` (producer state for the idempotent/transactional producer) and `.txnindex` (aborted transaction index).
- Exactly one segment is the **active segment** — the newest one, the only segment currently being appended to. **All other segments are immutable.** This distinction is everything for retention:
  - **Delete retention never deletes the active segment** — only closed, "old" segments.
  - **Compaction never compacts the active segment** — it only cleans closed segments. The active segment, plus anything not yet cleaned, forms the **dirty/active portion** of the log where duplicates can still exist.

> **Why segments at all?** Because deletion and compaction operate at *segment granularity* (or by rewriting groups of segments). Deleting a whole file is O(1) and instant; deleting individual records from the middle of a file would be brutal. Segments make "drop old data" a `rm` of a few files.

### 2.3 The two pointers: log start offset and high watermark

- **Log Start Offset (LSO, sometimes "log begin offset")**: the offset of the *earliest still-retained* record. Retention/compaction advance this forward; consumers that try to read below it get an `OFFSET_OUT_OF_RANGE` error (and must reset via `auto.offset.reset`).
- **Log End Offset (LEO)**: the offset that will be assigned to the *next* record (one past the last record).
- **High Watermark (HW)**: the highest offset that has been replicated to all in-sync replicas and is therefore safe to consume.

Retention moves the *floor* (LSO) up. Producers move the *ceiling* (LEO) up. The retained data lives between them.

### 2.4 Record anatomy (and what a "tombstone" is)

A Kafka record carries: a **key** (bytes, may be null), a **value** (bytes, may be null), a **timestamp**, and **headers**. For compaction, two fields are decisive:

- The **key** is the identity used for "latest value per key."
- A **null value** is a **tombstone** — a deletion marker. In a compacted topic, writing a record with key `K` and value `null` tells compaction: "the latest state of `K` is *deleted*." After consumers have had a chance to see it (`delete.retention.ms`), compaction removes both the tombstone and any prior records for `K`, so `K` vanishes from the log entirely.

> A record **must have a non-null key to be eligible for compaction**. Null-keyed records cannot be compacted (there's no identity to dedupe on) and — depending on version — will either be retained or cause issues; producing null-keyed records to a compacted topic is an anti-pattern. (More precisely: the log cleaner will refuse to compact records without keys and will log warnings / skip them; in practice you should never send null-keyed records to a compacted topic.)

### 2.5 Timestamps: which clock drives time retention?

Each record has a timestamp whose meaning depends on the topic config **`message.timestamp.type`**:
- **`CreateTime`** (default): the timestamp the *producer* set (defaults to producer wall-clock at send time).
- **`LogAppendTime`**: the broker overwrites it with the *broker's* wall-clock at append time.

Time-based retention uses the **largest timestamp in a segment** to decide whether the segment is expired (historically Kafka used file mtime; modern Kafka uses the max record timestamp via the time index). This matters: with `CreateTime`, a producer that backfills old data (old timestamps) can make a segment appear instantly expired; with reprocessing, you can accidentally delete data faster than expected. Flag this whenever you rely on time retention.

---

## 3. How it works internally

This is the heart of the chapter. We'll trace both cleanup policies under the hood.

### 3.1 The append path (shared by both policies)

1. Producer sends a batch to the partition **leader**.
2. Leader validates, assigns offsets, and **appends to the active segment's `.log` file** (writing through the OS page cache; Kafka does *not* fsync each write by default — it relies on replication + OS flush for durability, governed by `log.flush.interval.messages` / `.ms`, which default to "rely on the OS," i.e. effectively never force-flush per message).
3. Sparse index entries are added to `.index`/`.timeindex` every `log.index.interval.bytes` (default **4096** bytes) of appended data.
4. When the active segment hits a roll trigger, it is **closed** (becomes immutable) and a **new active segment** is created. Roll triggers:
   - **`segment.bytes`** (default **1 GiB**, i.e. `1073741824`): roll when the segment reaches this size.
   - **`segment.ms`** (default **7 days**, `604800000`): roll when the segment has been open this long, even if not full.
   - `segment.index.bytes` (default 10 MiB): roll if an index file fills.
   - There's also `segment.jitter.ms` (default 0) to randomize time-based rolls so all segments don't roll simultaneously across partitions.
5. Followers fetch and replicate the appended bytes; once replicated to all in-sync replicas, the high watermark advances.

**Key fact:** retention and compaction only ever act on **closed** segments. The active segment is sacrosanct until it rolls. So if your segments are huge or roll rarely, retention/compaction effectively *can't act on recent data* — a frequent source of "why isn't my data being deleted/compacted?" surprises.

### 3.2 Delete retention internals (cleanup.policy=delete)

A background thread pool (the **log retention / cleanup scheduler**, running on an interval controlled by **`log.retention.check.interval.ms`**, default **300000 ms = 5 minutes**) periodically evaluates each partition log:

**Time-based deletion:**
1. For each *closed* segment, compute the segment's largest record timestamp.
2. If `now - maxTimestamp(segment) > retention.ms`, the segment is **eligible for deletion**.
3. `retention.ms` default is **604800000 (7 days)**. There are also `retention.minutes` and `retention.hours` (broker-level `log.retention.*`), with the finer-grained one winning if multiple are set. Setting `retention.ms = -1` means **infinite time retention** (never delete by time).

**Size-based deletion:**
1. Sum the sizes of all segments in the partition.
2. While `totalSize > retention.bytes`, delete the **oldest** segment(s) until under the cap.
3. `retention.bytes` default is **-1** (no size limit) at the topic level; the broker-wide default `log.retention.bytes` is also **-1**. Note: `retention.bytes` is **per partition**, not per topic — a topic with 12 partitions and `retention.bytes=1GB` can use up to ~12 GB on a broker hosting all of them.

**Both can be active simultaneously**: a segment is removed if it violates *either* the time or the size constraint. Size acts as a hard cap; time acts as the normal policy.

**The actual deletion is two-phase (asynchronous):**
1. The segment is first **renamed** with a `.deleted` suffix (e.g. `00000000000000000000.log.deleted`) and removed from the in-memory segment list immediately, so it stops serving reads and the **log start offset advances**.
2. After **`file.delete.delay.ms`** (default **60000 ms = 60 s**), a scheduled task actually `unlink`s the file. The delay gives in-flight readers/mmaps a grace period before the bytes disappear.

So even "delete" is not instantaneous at the filesystem level; there's the 5-minute check interval plus a 60-second file-delete delay, plus the requirement that the segment be closed.

### 3.3 Log compaction internals (cleanup.policy=compact)

Compaction is performed by a dedicated component: the **Log Cleaner**, a pool of **`log.cleaner.threads`** (default **1**) `LogCleaner`/`CleanerThread` threads, **separate** from the delete-retention scheduler. It only runs if **`log.cleaner.enable=true`** — which is the default and has been since Kafka 0.9.0. (If someone disabled it, compacted topics silently never compact — a classic incident.)

#### 3.3.1 The clean point and the dirty ratio

The cleaner conceptually splits each compacted log into two regions:
- **Clean section**: the portion already compacted (offsets below the **clean point** / "first dirty offset"). Within the clean section, each key appears at most once (its latest value as of the last clean).
- **Dirty section**: everything from the clean point up to (but not including) the **active segment** — newly appended records the cleaner hasn't processed yet. Here, duplicate keys and stale values exist.

> **This is why compaction is "best-effort": at any moment the dirty section can contain many records for the same key. Compaction guarantees the *most recent* value per key is never removed; it never guarantees that, right now, there's only one record per key.**

The cleaner picks which partition to clean next by **dirtiest-first**: it computes a **dirty ratio** = `dirtyBytes / (cleanBytes + dirtyBytes)` for each compacted log and chooses the highest. It only bothers if the ratio exceeds **`min.cleanable.dirty.ratio`** (default **0.5**, i.e. 50% of the log must be "dirty" before a clean is triggered). Lower → cleans more aggressively (more CPU/IO, less duplicate retention); higher → lazier (more duplicates linger, less overhead).

Two more knobs gate *when* a record in the dirty section becomes eligible:
- **`min.cleanable.dirty.ratio`** (above) gates whether the *log* gets cleaned.
- **`min.compaction.lag.ms`** (default **0**): a record stays uncleaned for at least this long after being written — guarantees a minimum time a given message is *guaranteed present* before it can be compacted away. Useful when consumers need a minimum window to see every update.
- **`max.compaction.lag.ms`** (default **Long.MAX_VALUE**, effectively ∞): an upper bound — a dirty record will be cleaned within this long even if the dirty-ratio threshold isn't met. This is what lets you guarantee a tombstone *eventually* gets processed (needed for GDPR-style deletes) without waiting for organic dirtiness. Setting this also forces segment rolls so the active segment doesn't pin old dirty data forever.

#### 3.3.2 The compaction algorithm, step by step

When the cleaner selects a log to clean, it does **two passes**:

**Pass 1 — build the offset map.** It scans the **dirty section** and builds an in-memory hash map: **key → highest offset of that key seen in the dirty section** (the `OffsetMap`, a compact open-addressing hash of key-hash → offset). This map has a fixed memory budget:
- **`log.cleaner.dedupe.buffer.size`** (default **134217728 = 128 MiB**) total across all cleaner threads.
- **`log.cleaner.io.buffer.load.factor`** (default **0.9**): how full the dedupe buffer can get.
- The number of keys the map can hold is roughly `dedupe.buffer.size * load.factor / 24` (each entry is a 16-byte MD5 key hash + 8-byte offset). With defaults that's ~5 million keys per cleaner thread's slice. **If a partition's dirty section has more distinct keys than the map can hold, the cleaner can only clean part of the dirty range in one pass** (it processes as much as fits, advancing the clean point partway) — slower convergence, but still correct.

**Pass 2 — recopy survivors.** It then reads the segments from the *old clean point* forward and copies each record into **new segment files**, keeping a record **only if**:
- It is the **latest** occurrence of its key (per the offset map for the dirty part, and per "nothing later exists in clean part"), **and**
- It is **not a tombstone past its retention** (see tombstones below), **and**
- It satisfies `min.compaction.lag.ms` (too-recent records are retained even if duplicated).

Records that fail (older duplicates of a key) are **dropped**. Survivors are written into newly created segments, then the old segments are atomically swapped out (renamed `.deleted`, then `unlink`ed after `file.delete.delay.ms`, just like delete retention). **Offsets are preserved** — compaction never renumbers records; a compacted log has *gaps* in its offset sequence (e.g. offsets 5, 9, 12, 13 might survive while 6,7,8,10,11 were dropped). Consumers handle gaps transparently; this is normal and expected for compacted topics.

**Segment grouping.** To avoid producing tiny segments, the cleaner *groups* adjacent old segments and recopies them into a combined output segment, bounded by `segment.bytes` and by index-size limits and by the constraint that the offset range fits in an int (segments span ≤ `Int.MAX` offsets). This is why after compaction you may see fewer, fuller segment files.

#### 3.3.3 Tombstones and `delete.retention.ms`

A tombstone (key `K`, value `null`) signals deletion. The cleaner's handling:
1. On a cleaning pass, the tombstone *replaces/supersedes* all earlier records for `K` (they're dropped) — good.
2. But the **tombstone itself must linger** so that *every consumer* (including slow or down ones, and especially **followers/replicas** catching up) has a chance to observe the deletion. If the tombstone vanished too quickly, a consumer that rebuilt state from an older offset would never learn `K` was deleted and would keep stale state forever.
3. So a tombstone is retained for **`delete.retention.ms`** (default **86400000 = 24 hours**) *after the cleaning pass that would otherwise have removed it*. Only on a subsequent pass, once that window has elapsed, is the tombstone finally dropped and `K` fully purged.

> **Subtle but important:** `delete.retention.ms` clocks from the point the cleaner *processes* the tombstone into the clean section, not from when it was produced. The practical guarantee is: a consumer that is no more than `delete.retention.ms` behind the head is guaranteed to see the tombstone. Consumers further behind than that may miss it. This is why Kafka Streams sets long-ish values and why CDC consumers should not lag beyond this window.

#### 3.3.4 Header-based "delete markers" and value-bearing deletes

Note that *only a null value* is a tombstone to the cleaner. A record with a non-null value and a header like `__deleted: true` is **not** a tombstone — the cleaner keeps it as the latest value. CDC tools (Debezium) can emit either style; if you want Kafka to actually purge the key, you need a real null-value tombstone (Debezium's `tombstones.on.delete=true`, default true, and/or the `ExtractNewRecordState` SMT which can rewrite deletes into tombstones).

### 3.4 compact,delete combined

With `cleanup.policy=compact,delete`, **both** mechanisms run:
- The **log cleaner** compacts (latest-value-per-key) as above.
- The **delete retention** scheduler *also* deletes whole old segments by `retention.ms` / `retention.bytes`.

Effect: you get a compacted "latest state per key" topic that *additionally* has a hard time/size floor — even keys that are never updated again will eventually age out once their segment passes `retention.ms`. This is the right choice when:
- You need a changelog but also a **bounded** one (cap total retention so a never-updated key doesn't live forever).
- You have **GDPR/compliance** requirements that data older than N days must be physically gone regardless of compaction.

> A common config: `cleanup.policy=compact,delete`, `retention.ms=<your floor>`, `delete.retention.ms` ≤ `retention.ms`. Kafka Streams changelogs for *windowed* stores actually use exactly this — `compact,delete` with retention sized to the window length — so old windows are deleted, not kept forever.

### 3.5 Full lifecycle of a record in a compacted topic (worked trace)

Imagine a compacted topic `accounts` (1 partition), `min.cleanable.dirty.ratio=0.5`, `delete.retention.ms=24h`, `segment.bytes` small for illustration.

1. **t0**: produce `(alice, $100)` → offset 0, lands in active segment S2.
2. **t1**: produce `(bob, $50)` → offset 1.
3. **t2**: produce `(alice, $150)` → offset 2. Now two records for `alice` exist (offsets 0 and 2).
4. **t3**: active segment rolls (hits `segment.bytes`); S2 closes, S3 becomes active. Offsets 0–2 are now in a *closed* segment → eligible for cleaning.
5. **t4**: more writes push the dirty ratio over 0.5. Cleaner runs. It builds offset map `{alice→2, bob→1}`, recopies survivors: **offset 0 (`alice,$100`) is dropped; offsets 1 and 2 survive.** Log now logically holds `bob→$50`, `alice→$150` with offset gaps.
6. **t5**: produce `(bob, null)` → offset N (tombstone). Bob is logically deleted, but the tombstone is in the dirty section; older `bob` record at offset 1 still physically present until next clean.
7. **t6**: cleaner runs again. It drops offset 1 (`bob,$50`) but **keeps the tombstone** (it's within `delete.retention.ms`). Consumers reading now still see the tombstone and learn bob is deleted.
8. **t6 + 24h**: on a later clean pass, the tombstone's `delete.retention.ms` has elapsed → the tombstone is dropped. `bob` is now completely gone from the log.

A new consumer reading from offset 0 at any point after t8 sees only `alice→$150` (plus whatever else accumulated) — a perfect snapshot of current state.

---

## 4. The complete toolkit

### 4.1 Topic-level configs (the ones you set per topic)

These are set with `--config key=value` on `kafka-topics.sh`/`kafka-configs.sh`. Defaults shown are Kafka's documented defaults (current as of Kafka 3.x; flag where they differ historically).

| Config | Default | Applies to | What it does |
|---|---|---|---|
| `cleanup.policy` | `delete` | both | `delete`, `compact`, or `compact,delete`. Chooses the cleanup strategy. |
| `retention.ms` | `604800000` (7 d) | delete | Delete segments older than this. `-1` = infinite. |
| `retention.bytes` | `-1` (unlimited) | delete | Max **per-partition** log size before oldest segments are deleted. |
| `segment.bytes` | `1073741824` (1 GiB) | both | Roll a new segment at this size. Smaller = finer retention/compaction granularity, more files. |
| `segment.ms` | `604800000` (7 d) | both | Roll a new segment after this much time even if not full. |
| `segment.index.bytes` | `10485760` (10 MiB) | both | Max size of the offset index per segment. |
| `segment.jitter.ms` | `0` | both | Random jitter added to `segment.ms` to desynchronize rolls. |
| `min.cleanable.dirty.ratio` | `0.5` | compact | Fraction of log that must be dirty before the cleaner cleans it. |
| `min.compaction.lag.ms` | `0` | compact | Minimum age a record is guaranteed to remain uncompacted. |
| `max.compaction.lag.ms` | `Long.MAX_VALUE` (∞) | compact | Upper bound before a dirty record *must* be compacted (forces rolls). |
| `delete.retention.ms` | `86400000` (24 h) | compact | How long tombstones (and delete markers) are retained after they would otherwise be cleaned, so consumers can observe deletions. |
| `min.compaction.lag.ms` / `max.compaction.lag.ms` | see above | compact | (listed for completeness) |
| `file.delete.delay.ms` | `60000` (60 s) | both | Delay between marking a segment `.deleted` and unlinking it. |
| `message.timestamp.type` | `CreateTime` | both | `CreateTime` (producer time) vs `LogAppendTime` (broker time) — drives time retention. |
| `max.message.bytes` | `1048588` (~1 MiB) | both | Max record/batch size; relevant because the cleaner must fit records in buffers. |
| `unclean.leader.election.enable` | `false` | both | Not retention per se, but affects whether a lagging replica can become leader and resurrect/lose data. |

### 4.2 Broker-level configs (cluster defaults; topic configs override these)

Broker configs use the `log.` prefix and act as the default for topics that don't override.

| Broker config | Default | Topic-level equivalent | Notes |
|---|---|---|---|
| `log.cleanup.policy` | `delete` | `cleanup.policy` | Cluster-wide default policy. |
| `log.retention.ms` / `.minutes` / `.hours` | 7 days (`168` hours) | `retention.ms` | Finer unit wins if multiple set. |
| `log.retention.bytes` | `-1` | `retention.bytes` | Per-partition. |
| `log.retention.check.interval.ms` | `300000` (5 min) | (none) | How often the delete scheduler scans for expired segments. |
| `log.segment.bytes` | `1073741824` | `segment.bytes` | |
| `log.roll.ms` / `.hours` | 7 days | `segment.ms` | |
| `log.cleaner.enable` | `true` | (none) | **Must be true for any compaction to happen.** |
| `log.cleaner.threads` | `1` | (none) | Number of cleaner threads. Increase for many/large compacted partitions. |
| `log.cleaner.dedupe.buffer.size` | `134217728` (128 MiB) | (none) | Total offset-map memory across cleaner threads. |
| `log.cleaner.io.buffer.size` | `524288` (512 KiB) | (none) | Per-thread read/write buffer. |
| `log.cleaner.io.buffer.load.factor` | `0.9` | (none) | Max fill of the dedupe buffer. |
| `log.cleaner.io.max.bytes.per.second` | `Double.MAX_VALUE` (unthrottled) | (none) | Throttle cleaner disk IO to protect produce/consume throughput. |
| `log.cleaner.min.cleanable.ratio` | `0.5` | `min.cleanable.dirty.ratio` | |
| `log.cleaner.min.compaction.lag.ms` | `0` | `min.compaction.lag.ms` | |
| `log.cleaner.max.compaction.lag.ms` | `Long.MAX_VALUE` | `max.compaction.lag.ms` | |
| `log.cleaner.delete.retention.ms` | `86400000` (24 h) | `delete.retention.ms` | |
| `log.cleaner.backoff.ms` | `15000` (15 s) | (none) | Sleep when there's nothing to clean. |
| `log.index.interval.bytes` | `4096` | `index.interval.bytes` | Sparseness of the offset index. |
| `log.flush.interval.messages` / `.ms` | `Long.MAX_VALUE` / `null` | `flush.messages`/`flush.ms` | Force fsync cadence (default: rely on OS). |

### 4.3 CLI commands

```bash
# Create a compacted topic
kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic accounts --partitions 6 --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.1 \
  --config delete.retention.ms=86400000 \
  --config segment.ms=600000              # roll every 10 min so cleaning starts promptly

# Inspect a topic's effective configs
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name accounts --describe

# Change retention/compaction live (dynamic; no restart)
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name accounts \
  --alter --add-config cleanup.policy=compact,delete,retention.ms=604800000

# Switch a topic from delete to compact
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name orders \
  --alter --add-config cleanup.policy=compact

# Force-delete data below an offset (manually advance log start offset)
kafka-delete-records.sh --bootstrap-server localhost:9092 \
  --offset-json-file delete.json
# delete.json: {"partitions":[{"topic":"orders","partition":0,"offset":1000}],"version":1}

# Inspect raw segment files (records, keys, tombstones, offsets)
kafka-dump-log.sh --files /var/lib/kafka/data/accounts-0/00000000000000000000.log \
  --print-data-log --deep-iteration
# Look for `keysize: -1`/`payload: <null>`? Actually a tombstone shows valuesize -1 / value null.

# Set broker cleaner throttle dynamically (cluster-wide)
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type brokers --entity-default \
  --alter --add-config log.cleaner.io.max.bytes.per.second=5242880
```

### 4.4 Java AdminClient API

```java
// Create a compacted topic programmatically
try (Admin admin = Admin.create(props)) {
    NewTopic topic = new NewTopic("accounts", 6, (short) 3)
        .configs(Map.of(
            TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT, // "compact"
            TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1",
            TopicConfig.DELETE_RETENTION_MS_CONFIG, "86400000",
            TopicConfig.SEGMENT_MS_CONFIG, "600000"));
    admin.createTopics(List.of(topic)).all().get();

    // Alter config live
    ConfigResource res = new ConfigResource(ConfigResource.Type.TOPIC, "accounts");
    AlterConfigOp op = new AlterConfigOp(
        new ConfigEntry(TopicConfig.CLEANUP_POLICY_CONFIG, "compact,delete"),
        AlterConfigOp.OpType.SET);
    admin.incrementalAlterConfigs(Map.of(res, List.of(op))).all().get();
}
```

Relevant constants live in `org.apache.kafka.common.config.TopicConfig`: `CLEANUP_POLICY_CONFIG`, `CLEANUP_POLICY_DELETE`, `CLEANUP_POLICY_COMPACT`, `RETENTION_MS_CONFIG`, `RETENTION_BYTES_CONFIG`, `SEGMENT_BYTES_CONFIG`, `SEGMENT_MS_CONFIG`, `DELETE_RETENTION_MS_CONFIG`, `MIN_CLEANABLE_DIRTY_RATIO_CONFIG`, `MIN_COMPACTION_LAG_MS_CONFIG`, `MAX_COMPACTION_LAG_MS_CONFIG`, `FILE_DELETE_DELAY_MS_CONFIG`.

---

## 5. Code examples by use case

### 5.1 Producing tombstones to delete a key (Java)

```java
Properties p = new Properties();
p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
// For changelog correctness, prefer idempotence + acks=all so retries don't reorder/dup
p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
p.put(ProducerConfig.ACKS_CONFIG, "all");

try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
    // Upsert the latest state for key "user-42"
    producer.send(new ProducerRecord<>("accounts", "user-42", "{\"balance\":150}"));

    // Delete key "user-99": value MUST be null to be a tombstone.
    producer.send(new ProducerRecord<>("accounts", "user-99", null));  // tombstone
    producer.flush();
}
```
Why it matters: the **key must be non-null** (compaction identity) and the **value must be exactly null** (not empty string `""`, not `"null"`). `enable.idempotence=true` + `acks=all` keep the changelog from getting duplicate/reordered entries on retry, which would otherwise muddy "latest value per key."

### 5.2 Building a local cache from a compacted topic (event-carried state transfer)

```java
// Read a compacted "products" topic from the beginning to materialize current state.
Properties c = new Properties();
c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
c.put(ConsumerConfig.GROUP_ID_CONFIG, "product-cache-" + UUID.randomUUID()); // unique: read whole log
c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");   // start at log start offset
c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

Map<String, String> cache = new ConcurrentHashMap<>();
try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
    consumer.subscribe(List.of("products"));
    while (true) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
            if (r.value() == null) cache.remove(r.key());   // tombstone => delete from cache
            else cache.put(r.key(), r.value());             // upsert latest value
        }
    }
}
```
This is the canonical *event-carried state transfer* / GlobalKTable-by-hand pattern: because the topic is compacted, replaying from `earliest` reconstructs the full current state and stays bounded by key count, not event count. **You must handle `value()==null` as a removal**, or your cache will retain deleted keys.

### 5.3 Kafka Streams: state stores backed by compacted changelogs (automatic)

```java
StreamsBuilder builder = new StreamsBuilder();

// A KTable is materialized into a local RocksDB store, backed by a COMPACTED changelog topic
// that Streams creates automatically: <application.id>-<store>-changelog
KTable<String, Long> counts = builder
    .stream("clicks", Consumed.with(Serdes.String(), Serdes.String()))
    .groupByKey()
    .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("click-counts")
        // You can tune the changelog topic's configs here:
        .withLoggingEnabled(Map.of(
            TopicConfig.CLEANUP_POLICY_CONFIG, "compact",
            TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.3",
            TopicConfig.SEGMENT_BYTES_CONFIG, String.valueOf(64 * 1024 * 1024))));

// WINDOWED stores use cleanup.policy=compact,delete with retention = window size + grace,
// so old windows are physically deleted rather than compacted forever. Streams sets this for you.
```
Key insight: Kafka Streams fault tolerance *is* compacted changelog topics. On failure/rebalance, a new instance **restores** its local store by replaying the compacted changelog from the beginning — which is fast precisely because compaction keeps it bounded to one entry per key. If you ever manually set those changelog topics to `delete`, you can permanently lose state during restore.

### 5.4 CDC with Debezium → compacted topic per table (snapshot of latest row)

```properties
# Debezium connector (Connect worker config snippet)
name=inventory-connector
connector.class=io.debezium.connector.mysql.MySqlConnector
database.hostname=mysql
database.server.id=184054
topic.prefix=inv
table.include.list=inventory.customers
# Emit a tombstone when a row is deleted, so the compacted topic actually purges the key
tombstones.on.delete=true
# Flatten the envelope so the message value is just the row (and deletes become true tombstones)
transforms=unwrap
transforms.unwrap.type=io.debezium.transforms.ExtractNewRecordState
transforms.unwrap.delete.handling.mode=none      # 'none' lets the null-value tombstone through
transforms.unwrap.drop.tombstones=false
```
The destination topic (e.g. `inv.inventory.customers`) is configured `cleanup.policy=compact`. The message **key is the table's primary key**; the **value is the latest row image**; a **DELETE produces a null-value tombstone**. Consumers (search indexers, caches, other services) replay it to hold the current table state. Pitfall: if `drop.tombstones=true` or you use a value-bearing delete flag, Kafka will never purge the deleted PKs — your compacted topic grows with ghost keys.

### 5.5 `compact,delete` for a bounded changelog (GDPR floor)

```bash
kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name customer-profiles \
  --alter --add-config "cleanup.policy=compact,delete,\
retention.ms=2592000000,\
delete.retention.ms=604800000,\
max.compaction.lag.ms=86400000,\
min.cleanable.dirty.ratio=0.1"
```
- `compact` keeps latest profile per customer.
- `delete` + `retention.ms=30d` guarantees any segment older than 30 days is physically deleted — a hard floor for "right to be forgotten" and bounded storage even for never-updated keys.
- `max.compaction.lag.ms=1d` guarantees a tombstone produced after a delete request is *processed* (key purged) within a day, not "whenever the dirty ratio happens to cross 0.5."
- `min.cleanable.dirty.ratio=0.1` cleans aggressively so deletes propagate quickly.

### 5.6 `__consumer_offsets`: Kafka eating its own dog food

You don't configure this topic, but it's instructive: it's an internal **compacted** topic, key = `(group, topic, partition)`, value = latest committed offset + metadata. Replaying it gives the current offset of every consumer group — exactly the "latest value per key" pattern. Its compaction is what keeps offset storage bounded. (Defaults: 50 partitions, `cleanup.policy=compact`, `segment.bytes=104857600`.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Compaction is CPU + IO heavy** (it rereads and rewrites segments). On clusters with many large compacted partitions and only `log.cleaner.threads=1`, the cleaner can fall behind, letting dirty data and disk usage balloon. Scale `log.cleaner.threads` (a common production value is 2–8 depending on partition count and core count).
- **Throttle to protect the hot path.** `log.cleaner.io.max.bytes.per.second` (default unthrottled) lets you cap cleaner disk bandwidth so it doesn't starve produce/consume. Set it when you see produce latency spikes correlated with cleaning.
- **Page cache & zero-copy.** Kafka serves consumer reads via `sendfile` (zero-copy from page cache to socket). Compaction's rewrites churn the page cache; on memory-constrained brokers this can evict hot data. Size brokers with enough RAM for page cache.
- **Segment size vs latency to clean.** Large `segment.bytes`/`segment.ms` mean recent data sits in the active (uncleanable) segment for a long time → slow compaction of recent keys, slower tombstone propagation. For low-latency changelogs, *reduce* `segment.ms` (e.g. 10 min) so segments roll and become cleanable promptly. Trade-off: more, smaller files (more open file handles, more index files).

### 6.2 Correctness & concurrency

- **Keys must be stable and deterministic.** "Latest value per key" only dedupes if the same logical entity always serializes to the same key bytes. Beware: non-canonical JSON keys, locale-dependent serialization, or changing key schemas → the *same entity under two different key encodings* won't dedupe.
- **Ordering matters.** Compaction keeps the record with the **highest offset** per key, which equals the most recently *appended*, not the most recently *created*. With producer retries that reorder, or multiple producers racing on the same key, "latest" may not be what you mean. Use `enable.idempotence=true`, `max.in.flight.requests.per.connection ≤ 5` (idempotence guarantees ordering up to 5), and ideally a single writer per key.
- **Tombstone for empty vs null.** A null value deletes; an empty-but-non-null value is a real value. Don't conflate them.
- **Null-keyed records to a compacted topic** are invalid — never produce them.

### 6.3 Memory

- The cleaner's **dedupe buffer** (`log.cleaner.dedupe.buffer.size`, 128 MiB default) bounds how many distinct keys can be deduped in one pass. **High-cardinality keyspaces** (hundreds of millions of keys per partition) may exceed it, forcing multi-pass cleaning and slow convergence. Mitigation: increase the buffer, increase threads, or reduce per-partition key cardinality (more partitions).

### 6.4 Security & compliance

- **Deletes are not immediate.** A tombstone purges a key only after a clean pass *and* `delete.retention.ms`. For GDPR "right to be forgotten," you must (a) produce a tombstone, (b) ensure `cleanup.policy` includes `compact` (so the key gets purged) usually with `delete` too, and (c) set `max.compaction.lag.ms` to bound when purge happens. Even then, the data sits on disk (and in backups, replicas) until cleaned. Document the actual purge SLA.
- Replicas, mirrored clusters (MirrorMaker 2), and backups each retain their own copies; a delete must propagate to all of them.

### 6.5 Observability

Monitor these JMX metrics / signals:

| Metric | What it tells you |
|---|---|
| `kafka.log:type=LogCleanerManager,name=max-dirty-percent` | Worst dirty ratio across partitions — high & rising = cleaner falling behind. |
| `kafka.log:type=LogCleaner,name=cleaner-recopy-percent` | Fraction of bytes recopied — cleaning efficiency. |
| `kafka.log:type=LogCleaner,name=max-clean-time-secs` | Slowest clean — long = cleaner saturated. |
| `kafka.log:type=LogCleanerManager,name=time-since-last-run-ms` | If this climbs unbounded, the cleaner thread may be dead (check broker logs for `LogCleaner` errors). |
| `kafka.log:type=Log,name=Size,topic=...,partition=...` | Per-partition log size — unexpected growth on a compacted topic = compaction not keeping up. |
| `kafka.server:type=BrokerTopicMetrics,name=...` | Produce/consume rates to correlate. |
| Disk free % | Ultimate backstop. |

Also: `log-cleaner.log` on the broker (a dedicated log file) records cleaner activity and **fatal cleaner errors** — a single uncaught exception (historically, e.g., a record larger than buffers, or a corrupt record) can **kill the cleaner thread**, after which *no compaction happens cluster-wide* and disks fill silently. Alert on cleaner-thread death.

### 6.6 Testing

- **Embedded broker / Testcontainers**: spin up a real Kafka, create a compacted topic with tiny `segment.bytes` and low `min.cleanable.dirty.ratio`, produce duplicate keys + tombstones, then *trigger a roll* (produce enough to roll the active segment) and assert post-compaction state via `kafka-dump-log` or by consuming from earliest.
- Remember compaction won't touch the active segment, so tests that produce a handful of records and immediately read will *see all duplicates* — that's correct behavior, not a bug.
- Use `min.cleanable.dirty.ratio=0` and small segments in tests to force prompt cleaning.

### 6.7 Production hardening checklist

- Confirm `log.cleaner.enable=true` (don't assume).
- Set `log.cleaner.threads` proportional to compacted-partition count.
- Alert on cleaner-thread death and on `max-dirty-percent`.
- For changelogs, set `segment.ms` low enough that recent keys get cleaned promptly.
- For deletes that must happen, set `max.compaction.lag.ms`.
- Don't put `delete.retention.ms` *shorter* than your slowest consumer's max lag, or consumers will miss tombstones.
- Use idempotent producers + single-writer-per-key for changelog topics.

### 6.8 Anti-patterns

- Producing **null-keyed** records to a compacted topic.
- Using **empty string** as a "delete" instead of a real null tombstone.
- **Huge segments** on a low-latency changelog (recent state never gets cleaned).
- Treating compaction as **dedup-on-read**: it is async/best-effort; consumers must still tolerate seeing multiple records per key.
- `delete.retention.ms` **too short** → consumers/replicas miss tombstones → stale state forever.
- Switching a Streams changelog from `compact` to `delete` → state loss on restore.
- Relying on `retention.ms` for compliance deletes (it only ages out whole old segments; a recently-updated key never expires).

---

## 7. Advanced topics & deep internals

### 7.1 Offset gaps are normal — and consumers must cope

After compaction, surviving offsets are non-contiguous. The consumer's `position()` and `seek()` work fine (offsets still monotonically increase), but code that assumes "offset N+1 follows offset N" is wrong on compacted topics. The high watermark and `endOffsets()` still reflect the LEO (one past last appended), which may be far above the highest *surviving* offset.

### 7.2 The "first dirty offset" / clean checkpoint file

Each broker maintains a `cleaner-offset-checkpoint` file in each log directory recording the **first dirty offset** per partition — the boundary between clean and dirty. On restart, the cleaner resumes from this checkpoint instead of recompacting the whole log. If this file is corrupted/lost, the cleaner re-cleans from the log start (correct but expensive). There's a sibling `log-start-offset-checkpoint` and `recovery-point-offset-checkpoint`.

### 7.3 Why the active segment is never cleaned (and the max.compaction.lag.ms interaction)

The cleaner cannot clean records in the active segment because they're still being appended (and offsets/keys may yet be superseded). Normally that's fine, but it means a *low-traffic* compacted partition whose active segment never rolls can pin a tombstone indefinitely. `max.compaction.lag.ms` fixes this: when set, Kafka will **force the active segment to roll** so the cleaner can process dirty records within the bound. Without it, on a quiet topic a delete can effectively never happen.

### 7.4 Interaction of compact + delete in detail

When both run, the delete-retention path can delete a whole old segment that the cleaner *would* have kept the latest value from. Net effect: a key that hasn't been updated within `retention.ms` and lives only in a now-expired segment gets deleted even though it was the latest value. That's the *intended* "bounded changelog" behavior — but it means `compact,delete` is **not** a pure changelog: keys can disappear due to age, not just due to a newer value or tombstone. Choose deliberately.

### 7.5 Compaction and transactions / control records

With the transactional/idempotent producer, the log contains **control records** (commit/abort markers) and **producer state snapshots** (`.snapshot` files). The cleaner is aware of these: it preserves transaction markers as needed for correct read-committed behavior and manages producer state so that idempotence/transactional guarantees survive compaction. Aborted-transaction records are filtered by read-committed consumers regardless. You generally don't tune this, but be aware compaction does *not* break EOS (exactly-once semantics).

### 7.6 Compaction does not guarantee a single copy across consumers' view

Even after extensive compaction, a consumer that starts mid-log may see different "first value per key" than one starting at the head, because the dirty section differs over time. The only guarantee: *if you consume to the head*, the **last value you see per key is the true latest** (or absence/tombstone for deleted keys, subject to `delete.retention.ms`).

### 7.7 Changing cleanup.policy on an existing topic

- **delete → compact**: existing duplicates remain until segments roll and the cleaner runs; you may want to lower `min.cleanable.dirty.ratio` temporarily to force a cleanup. **Caution:** if the topic has null-keyed records, those become problematic under compaction.
- **compact → delete**: the cleaner stops; data now ages out by time/size. State-store changelogs must never do this.
- Changes are dynamic (no restart) but act lazily (next scheduler/cleaner cycle).

### 7.8 Tiered storage (Kafka 3.6+ KIP-405)

With **tiered storage**, older closed segments are offloaded to object storage (S3/GCS) while recent data stays local. `retention.ms`/`retention.bytes` then have **local** counterparts (`local.retention.ms`, `local.retention.bytes`) governing what stays on the broker's disk vs the remote tier. **Note (version-specific):** as of its introduction, tiered storage supports `cleanup.policy=delete`; compacted topics with remote tiering have had restrictions — verify your exact Kafka/vendor version before assuming compaction + tiering works together.

### 7.9 The 24-byte offset-map entry math

Each dedupe-map entry = 16-byte (MD5) key hash + 8-byte offset = 24 bytes. Usable map size = `dedupe.buffer.size × load.factor`. With defaults: `134217728 × 0.9 / 24 ≈ 5.03 million` distinct keys handleable per cleaning pass per thread. Cross this and the cleaner makes partial progress per pass. This is the number to compute when sizing for high-cardinality compacted topics.

### 7.10 Hashing collisions

Because the map stores *hashes* of keys (MD5), two distinct keys with colliding hashes could in principle be treated as one. Kafka uses MD5 (128-bit) precisely because collision probability across realistic key counts is negligible; this is why entries are 16 bytes. You can't tune the hash, but it's good to know the dedupe is hash-based, not full-key.

---

## 8. Tradeoffs & decision frameworks

### 8.1 delete vs compact vs compact,delete

| Dimension | `delete` | `compact` | `compact,delete` |
|---|---|---|---|
| Retains | Records within time/size window | Latest value per key, forever | Latest value per key, but only within time/size window |
| Bounded by | Time / size | Number of distinct keys | min(distinct keys, time/size window) |
| Requires keys | No | Yes (non-null) | Yes |
| Tombstones meaningful | No (just data) | Yes (purge key) | Yes |
| Typical use | Event/telemetry streams | Changelogs, snapshots, caches, CDC | Bounded changelogs, GDPR floors, windowed stores |
| Replay reconstructs | A time window of events | Full current state | Recent current state |
| Storage growth | Capped by window | Grows with key cardinality | Capped |

**Use compaction when…** you need the *current state per key* and want consumers to rebuild it by replay; key cardinality is bounded enough to fit on disk; ordering per key is well-defined.

**Avoid compaction when…** records are keyless or keys are non-stable; you need *every event* (audit/event-sourcing where history matters — use `delete` with long retention or no retention, or a separate event store); cardinality is effectively unbounded and unbounded growth is unacceptable (then use `compact,delete`).

### 8.2 Compacted topic vs external KV store (Redis/RocksDB/DB) for state

| | Compacted Kafka topic | External KV store |
|---|---|---|
| Source of truth + transport | Same system (one less moving part) | Separate; needs a pipeline |
| Replayability | Built in (replay from earliest) | Need snapshots/backups |
| Read latency | Must materialize locally first | Direct point reads |
| Multi-consumer fan-out | Native (every consumer builds own view) | Shared store contention |
| Bounded by | Key cardinality | Store capacity |
| Best for | Event-carried state transfer, Streams changelogs, CDC | Low-latency random point lookups |

### 8.3 min.cleanable.dirty.ratio tuning

| Value | Effect |
|---|---|
| `0.0` | Always clean (max freshness, max CPU/IO) — good for tests, low-latency deletes |
| `0.1–0.3` | Aggressive; deletes/updates propagate fast; higher overhead |
| `0.5` (default) | Balanced |
| `0.9` | Lazy; tolerate lots of duplicates; minimal overhead; slow purge |

---

## 9. Failure modes & debugging

### 9.1 "Disk is filling up on a compacted topic"

Likely causes & checks:
1. **Cleaner thread is dead.** Check `log-cleaner.log` for stack traces; check JMX `time-since-last-run-ms` climbing and `max-dirty-percent` near 100. Fix: address the root error (often an oversized record historically, or corruption), then restart the broker to revive the cleaner.
2. **`log.cleaner.enable=false`.** Someone disabled it. Set true, restart.
3. **High dirty-ratio threshold + low traffic.** `min.cleanable.dirty.ratio=0.5` plus a topic that rarely crosses 50% dirty → never cleans. Lower the ratio and/or set `max.compaction.lag.ms`.
4. **Active segment never rolls.** Low-traffic topic, large `segment.ms`. Set smaller `segment.ms` or `max.compaction.lag.ms`.
5. **High key cardinality exceeding dedupe buffer.** Multi-pass slow cleaning. Increase buffer/threads or partitions.

### 9.2 "Consumers see stale state for deleted keys"

- `delete.retention.ms` too short and a consumer lagged past it → it never saw the tombstone. Increase `delete.retention.ms` and ensure consumers stay within it; reset/rebuild lagging consumers.
- The "delete" was a value-bearing flag, not a real null tombstone → key never purged. Fix producer/CDC to emit nulls.

### 9.3 "Duplicate values per key showing up downstream"

Expected if reading the dirty section. If downstream logic assumed dedup-on-read, fix the logic to keep latest-by-offset. Confirm with `kafka-dump-log.sh --print-data-log` which offsets survived.

### 9.4 "Wrong value won the compaction race"

Highest-offset (latest appended) wins, not latest-by-business-time. Caused by producer retries reordering or multiple writers. Enable idempotence, restrict in-flight requests, enforce single-writer-per-key.

### 9.5 "Time retention deleting data too early/late"

- Too early: `CreateTime` with backfilled old timestamps → segments look expired. Switch to `LogAppendTime` if you want broker-time semantics.
- Too late: large segments don't roll, so old data sits in an unexpired (because it shares a segment with newer records) file. Reduce `segment.ms`/`segment.bytes`.

### 9.6 Diagnostic commands recap

```bash
# Effective config
kafka-configs.sh --bootstrap-server b:9092 --entity-type topics --entity-name t --describe
# Per-partition log layout (run on the broker)
ls -la /var/lib/kafka/data/t-0/
# Decode a segment, see keys/tombstones/offsets
kafka-dump-log.sh --files /var/lib/kafka/data/t-0/000...0.log --print-data-log --deep-iteration
# Watch cleaner metrics via JMX (jconsole / kafka JMX exporter)
# tail the dedicated cleaner log
tail -f /var/log/kafka/log-cleaner.log
```

### 9.7 Real-world incident patterns

- **Silent cleaner death → full disks → broker down.** A poison record or a bug caused an uncaught exception in the single cleaner thread; compaction stopped cluster-wide; `__consumer_offsets` and Streams changelogs ballooned; brokers ran out of disk days later. Lesson: alert on cleaner liveness, not just disk.
- **GDPR delete didn't take.** Team produced tombstones but the topic was `compact` only with default `min.cleanable.dirty.ratio=0.5` on a low-traffic topic; tombstones sat for weeks. Adding `max.compaction.lag.ms` and lowering the ratio fixed the purge SLA.
- **Streams state loss.** An operator "cleaned up" by switching changelog topics to `cleanup.policy=delete` with 1-day retention; next rebalance, restoring instances replayed an incomplete log and lost older keys' state.

---

## 10. Interview drill

**Q1. What are Kafka's cleanup policies and how do they differ?**
*Model answer:* `delete` removes whole old segments by time (`retention.ms`, default 7d) or size (`retention.bytes`, default unlimited). `compact` keeps only the latest value per key forever, purging superseded values and (after `delete.retention.ms`) tombstoned keys. `compact,delete` does both: latest-per-key but also age/size-bounded.
- *Probe: When would you choose compact,delete?* Bounded changelogs, GDPR floors, windowed Streams stores — when you want latest-per-key but also a hard time/size cap.
- *Probe: Why per-partition for retention.bytes?* Because logs are per-partition; a topic's total = sum across its partitions on a broker.
- *Probe: Does delete ever touch the active segment?* No — only closed segments.

**Q2. Explain a tombstone and `delete.retention.ms`.**
*Model answer:* A tombstone is a record with a non-null key and **null value** marking a key as deleted in a compacted topic. The cleaner drops prior values for that key, but retains the tombstone for `delete.retention.ms` (default 24h) so all consumers/replicas can observe the deletion before it too is removed.
- *Probe: What if a consumer lags beyond that window?* It may never see the tombstone and will keep stale state.
- *Probe: Is an empty value a tombstone?* No — must be exactly null.
- *Probe: From when does the 24h clock start?* From when the cleaner processes the tombstone into the clean section, not from produce time.

**Q3. Walk me through the log cleaner algorithm.**
*Model answer:* Pick the dirtiest log (dirty ratio > `min.cleanable.dirty.ratio`, 0.5). Pass 1: scan the dirty section, build a key→highest-offset offset map (bounded by `log.cleaner.dedupe.buffer.size`, 128MiB ≈ 5M keys). Pass 2: recopy from the old clean point, keeping only the latest record per key (and tombstones within `delete.retention.ms`), into new grouped segments; swap out old ones. Offsets preserved (gaps appear). Active segment never touched.
- *Probe: What if there are more keys than the buffer holds?* Partial multi-pass cleaning — correct but slower.
- *Probe: Why are offsets non-contiguous after?* Dropped records leave gaps; Kafka never renumbers.

**Q4. Why is compaction described as "async / best-effort"?**
*Model answer:* The cleaner runs in the background on closed segments only; the dirty section + active segment always contain un-deduped duplicates. The only guarantee is that the latest value per key survives; there's no guarantee that at any instant only one record per key exists.
- *Probe: What does that mean for consumers?* They must tolerate multiple records per key and apply latest-by-offset.

**Q5. How do Kafka Streams state stores use compaction?** (senior-signal)
*Model answer:* Each materialized store has a compacted **changelog** topic (`<app-id>-<store>-changelog`). Writes to the local RocksDB store are mirrored to the changelog; on failure/rebalance, a new instance restores the store by replaying the changelog — fast because compaction bounds it to one entry per key. Windowed stores use `compact,delete` with retention = window+grace so old windows are deleted.
- *Probe: What happens if you set the changelog to delete?* State loss on restore.
- *Probe: Why not just back state with an external DB?* Replayability, no extra moving parts, native per-instance materialization.

**Q6. You have a compacted topic that isn't shrinking. How do you debug?** (senior-signal)
*Model answer:* Check cleaner liveness (`log-cleaner.log`, JMX `time-since-last-run-ms`, `max-dirty-percent`); confirm `log.cleaner.enable=true`; check `min.cleanable.dirty.ratio` vs traffic; check whether the active segment ever rolls (`segment.ms`); check key cardinality vs dedupe buffer; verify keys aren't null and "deletes" are real tombstones.
- *Probe: A single bad record killed the cleaner — impact?* Compaction halts cluster-wide; disks fill silently.
- *Probe: Fix for low-traffic never-cleaning?* `max.compaction.lag.ms` + lower dirty ratio + smaller `segment.ms`.

**Q7. Design retention for an event-sourcing audit log vs a current-state cache.** (senior-signal)
*Model answer:* Audit/event-sourcing needs *every* event → `delete` with very long or infinite retention (or tiered storage), never compact (you'd lose history). Current-state cache needs *latest per key* → `compact` (or `compact,delete` if bounded). They're often two topics: an immutable event log + a compacted state view derived from it.
- *Probe: Can you do both in one topic?* Not well — compaction destroys history. Keep them separate.

**Q8. How does compaction interact with transactions/EOS?**
*Model answer:* The cleaner preserves transaction control markers and producer-state snapshots needed for read-committed and idempotence, so compaction doesn't break exactly-once. Aborted records are filtered by read-committed consumers regardless.
- *Probe: Does compaction renumber offsets?* No.

**Q9. What roll triggers exist and why do they matter for cleanup?**
*Model answer:* `segment.bytes` (1GiB), `segment.ms` (7d), index-size limits. They matter because retention and compaction only act on *closed* segments; if segments don't roll, recent data is never cleaned/deleted.
- *Probe: Tuning for a low-latency changelog?* Lower `segment.ms`.

**Q10. Sizing the cleaner's dedupe buffer.** (senior-signal)
*Model answer:* Entries are 24 bytes (16-byte MD5 hash + 8-byte offset); usable = `dedupe.buffer.size × load.factor`. Default ≈ 5M keys/thread. For higher cardinality, raise `log.cleaner.dedupe.buffer.size`, raise `log.cleaner.threads`, or add partitions to reduce per-partition cardinality.
- *Probe: What happens if exceeded?* Partial multi-pass cleaning; correct but slow.

**Q11. How would you guarantee a GDPR delete is physically purged within 24h?**
*Model answer:* Produce a null-value tombstone for the key; topic `cleanup.policy=compact` (usually `compact,delete`); set `max.compaction.lag.ms ≤ 24h` (forces rolls + cleaning), `min.cleanable.dirty.ratio` low, `delete.retention.ms` short enough but ≥ slowest consumer lag; and propagate to all replicas/mirrors/backups.
- *Probe: Why isn't retention.ms enough?* It only ages out whole old segments; a recently-updated key never expires by time.

**Q12. CreateTime vs LogAppendTime — effect on retention?**
*Model answer:* Time retention uses the max record timestamp in a segment. `CreateTime` (producer time) means backfilled old data can look instantly expired; `LogAppendTime` (broker time) ties retention to ingestion. Choose `LogAppendTime` if you want age-since-ingest semantics.

---

## 11. Glossary

- **Active segment** — the newest, currently-appended segment; never deleted by retention nor cleaned by compaction until it rolls.
- **AdminClient** — Kafka's Java API for administrative ops (create topics, alter configs).
- **Append-only log** — an ordered, immutable record sequence written only at the end.
- **Base offset** — the offset of the first record in a segment; used to name segment files.
- **CDC (Change Data Capture)** — capturing row-level DB changes and streaming them (e.g. Debezium), often into compacted topics keyed by primary key.
- **Clean point / first dirty offset** — boundary between already-compacted (clean) and not-yet-compacted (dirty) regions of a log.
- **Cleaner (Log Cleaner)** — the background thread pool that performs compaction.
- **`cleanup.policy`** — topic config selecting `delete`, `compact`, or `compact,delete`.
- **Control records** — transaction commit/abort markers in the log; preserved by compaction.
- **Dedupe buffer / OffsetMap** — in-memory key-hash→offset map the cleaner builds to find latest offsets.
- **`delete.retention.ms`** — how long tombstones survive after they'd otherwise be cleaned (default 24h).
- **Dirty ratio** — `dirtyBytes / totalBytes`; the cleaner cleans logs above `min.cleanable.dirty.ratio`.
- **EOS (Exactly-Once Semantics)** — Kafka's transactional/idempotent guarantees; preserved across compaction.
- **Event-carried state transfer** — publishing an entity's current state as events so consumers build local caches.
- **High watermark (HW)** — highest offset replicated to all in-sync replicas and safe to consume.
- **Idempotent producer** — producer mode preventing duplicate/reordered appends on retry.
- **In-sync replica (ISR)** — replicas caught up enough to the leader to be eligible for HW/leadership.
- **KRaft** — Kafka's ZooKeeper-free metadata mode using the Raft consensus protocol.
- **KTable / GlobalKTable** — Kafka Streams abstractions representing a changelog as a keyed table.
- **Leader / Follower** — the replica handling I/O for a partition vs replicas copying it.
- **Log End Offset (LEO)** — offset to be assigned to the next record.
- **Log Start Offset (LSO)** — offset of the earliest retained record.
- **`min.cleanable.dirty.ratio`** — dirtiness threshold to trigger compaction (default 0.5).
- **`min.compaction.lag.ms` / `max.compaction.lag.ms`** — lower/upper bounds on how long a record stays before being compactable.
- **Offset** — a record's monotonic position within a partition.
- **Page cache** — OS in-memory file cache; Kafka reads/writes through it and uses `sendfile` zero-copy.
- **Partition** — an independent ordered log; the unit of parallelism and ordering.
- **Raft** — consensus algorithm for agreeing on an ordered log of changes.
- **Replica / replication.factor** — copies of a partition for fault tolerance.
- **Retention** — the policy for removing old data: by time (`retention.ms`), size (`retention.bytes`), or compaction.
- **`sendfile` (zero-copy)** — kernel syscall to send file bytes to a socket without copying to user space.
- **Segment** — a chunk of a partition's log stored as `.log` + `.index` + `.timeindex` files.
- **`segment.bytes` / `segment.ms`** — segment roll triggers (1 GiB / 7 days).
- **Tiered storage (KIP-405)** — offloading old segments to object storage; introduces `local.retention.*`.
- **Tombstone** — a record with non-null key and **null value** marking a key for deletion in a compacted topic.
- **Topic** — a named logical stream split into partitions.
- **ZooKeeper** — legacy external coordination service for Kafka metadata (removed in Kafka 4.0).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Policies:** `delete` (age/size out) · `compact` (latest per key forever) · `compact,delete` (both).
**Key defaults:** `retention.ms`=7d · `retention.bytes`=-1 (unlimited, **per partition**) · `segment.bytes`=1GiB · `segment.ms`=7d · `min.cleanable.dirty.ratio`=0.5 · `delete.retention.ms`=24h · `min.compaction.lag.ms`=0 · `max.compaction.lag.ms`=∞ · `file.delete.delay.ms`=60s · `log.retention.check.interval.ms`=5min · `log.cleaner.enable`=true · `log.cleaner.threads`=1 · `log.cleaner.dedupe.buffer.size`=128MiB (~5M keys).
**Tombstone:** non-null key + **null** value. Survives `delete.retention.ms`. Key must be non-null to compact.
**Iron rules:** retention/compaction act on **closed segments only**; compaction is **async/best-effort** (dirty section keeps dups); compaction **preserves offsets** (gaps appear); compaction keeps **highest-offset** value per key.
**Decision:** events that expire → `delete`; latest-state-per-key replayable → `compact`; bounded changelog / GDPR floor → `compact,delete`; event-sourced history → `delete` with long/∞ retention, never compact.
**Debug compacted topic not shrinking:** cleaner alive? `enable=true`? dirty ratio vs traffic? active segment rolling (`segment.ms`)? key cardinality vs dedupe buffer? real null tombstones? Set `max.compaction.lag.ms` + lower ratio + smaller `segment.ms` to force progress.
**Don'ts:** null keys to compacted topics · empty-string "deletes" · giant segments on low-latency changelogs · `delete.retention.ms` < slowest consumer lag · switching Streams changelogs to `delete`.

### 12.2 Self-test (no answers)

1. A compacted, low-traffic topic produces a tombstone for a key. With all-default configs, what is the *worst-case* time before that key's bytes are physically gone, and which two configs would you change to bound it tightly?
2. Your `__consumer_offsets` topic and several Streams changelogs all started growing without bound at the same time last week. What single root cause should you check first, and what metric confirms it?
3. Explain why a consumer that starts at `earliest` on a compacted topic might see two records for the same key, and why that is not a bug.
4. You set `cleanup.policy=compact,delete`, `retention.ms=7d` on a changelog. A customer record hasn't been updated in 10 days. Is it still in the topic? Why, and is that the behavior you want for a "current state" view?
5. A compacted partition has 40 million distinct keys. With default cleaner settings, what happens during a cleaning pass, and what three levers change the outcome?
6. Two producers write the same key concurrently with `acks=all` but `enable.idempotence=false` and `max.in.flight.requests=10`. Why might compaction keep the "wrong" value, and how do you prevent it?
7. Why does Kafka never delete or compact the active segment, and what config makes a quiet topic still purge deletes promptly despite this rule?

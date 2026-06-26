# Kafka & Message Brokers — Architecture & The Commit Log

> An exhaustive engineering-handbook chapter for senior JVM/backend developers. It builds from first principles up to deep internals, operations, and interview-grade depth. Wherever a newcomer term appears, it is explained inline.

---

## 1. Overview & where it fits

### 1.1 What Kafka is, in one sentence

Apache Kafka is a **distributed, partitioned, replicated commit log** dressed up as a publish/subscribe messaging system. The single most important idea — the one everything else hangs off of — is that Kafka does not store "messages in a queue." It stores an **append-only, ordered sequence of records on disk** (a *log*), and lets many independent readers move through that sequence at their own pace.

If you internalize *only one thing* from this chapter, make it this: **Kafka is a log, not a queue.** A traditional queue is destructive on read — once a consumer takes a message, it is gone. A Kafka log is non-destructive on read — consuming a record does not delete it; the consumer merely advances a *cursor* (an offset). Many consumers can read the same record. Records are deleted later, by time or size policy, not by being consumed.

### 1.2 The problem it solves

Before Kafka (open-sourced by LinkedIn in 2011), large organizations had a tangle of point-to-point data pipes: the database fed the search index, which fed the analytics warehouse, which fed monitoring, which fed the recommendation system. Every new system meant another bespoke integration. This is the classic **O(N²) integration problem** — N systems each talking to up to N others.

Kafka's pitch: insert a single, durable, high-throughput **central log** in the middle. Every producer writes once to Kafka; every consumer reads from Kafka. The topology becomes **O(N)** — each system has exactly one connection (to Kafka). Kafka becomes the organization's *central nervous system* for streaming data — the durable, replayable source of truth for events in motion.

Concretely, you reach for Kafka when you need some combination of:

- **High write throughput** — hundreds of thousands to millions of records per second per cluster, sustained.
- **Durable buffering / decoupling** — producers and consumers run at different speeds and uptimes; Kafka absorbs the mismatch.
- **Replay** — the ability for a new or recovering consumer to re-read history from any point (e.g., to rebuild a cache, backfill a new microservice, or reprocess after a bug fix).
- **Multiple independent consumers** of the same stream (the database CDC stream feeds search, analytics, and audit simultaneously).
- **Ordering within a key** — all events for `user-42` processed in the order they happened.
- **Stream processing** — a substrate for Kafka Streams, ksqlDB, Flink, Spark Structured Streaming, etc.

You do *not* reach for Kafka when you need: per-message acknowledgement and redelivery semantics with arbitrary out-of-order completion (classic task-queue workloads — RabbitMQ/SQS fit better); priority queues; very low fan-in with complex routing; or a request/response RPC pattern (use gRPC/HTTP). More on this in §8.

### 1.3 The one-paragraph mental model

Picture a giant append-only notebook. Writers (producers) only ever add lines to the **end** of the notebook; they never erase or insert in the middle. Each line gets a monotonically increasing line number (an **offset**). To scale, the notebook is split into many independent notebooks called **partitions**, each with its own independent line numbering. A logical stream (a **topic**) is just the set of its partitions. The notebooks are photocopied onto several machines (**replicas**) so that losing a machine loses no data. Readers (consumers) each remember "I have read up to line 5,000 in partition 3" — that remembered position is the **committed offset**, and it lives in Kafka itself. Reading does not erase lines; old lines are torn out only when they age out (retention). Because writes are pure appends to the end of a file, the disk head (or SSD controller) does almost nothing but **sequential I/O**, which is why a "slow" spinning disk can keep up with a fast network — and why Kafka is fast.

### 1.4 Where it sits in a system diagram

```
          ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
Producers │  app A      │     │  app B      │     │  DB (CDC)   │
          └──────┬──────┘     └──────┬──────┘     └──────┬──────┘
                 │ produce            │ produce           │ produce
                 ▼                    ▼                   ▼
        ╔══════════════════════════════════════════════════════════╗
        ║                    KAFKA CLUSTER                           ║
        ║   broker 1        broker 2        broker 3                 ║
        ║  ┌────────┐      ┌────────┐      ┌────────┐                ║
        ║  │topic T │      │topic T │      │topic T │   (partitions  ║
        ║  │ P0(L)  │      │ P1(L)  │      │ P2(L)  │    spread &     ║
        ║  │ P1(F)  │      │ P2(F)  │      │ P0(F)  │    replicated)  ║
        ║  └────────┘      └────────┘      └────────┘                ║
        ╚══════════════════════════════════════════════════════════╝
                 │ fetch              │ fetch             │ fetch
                 ▼                    ▼                   ▼
          ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
Consumers │ search idx  │     │ analytics   │     │ audit log   │
          └─────────────┘     └─────────────┘     └─────────────┘
    (L) = leader replica   (F) = follower replica
```

---

## 2. Foundations from first principles

We now build the model term by term. Every term gets defined the first time it appears.

### 2.1 Record (message / event)

The atomic unit Kafka stores is a **record** (older docs say "message"; the streaming world says "event"). A record has:

- **Key** (optional, bytes) — used for partition routing and for log compaction. Think "the entity this event is about" (`user-42`, `order-9981`).
- **Value** (the payload, bytes) — the actual data (often JSON, Avro, Protobuf, JSON-Schema-encoded).
- **Timestamp** — either the time the producer created it (`CreateTime`, default) or the time the broker appended it (`LogAppendTime`), configurable per topic.
- **Headers** — optional key/value metadata (since Kafka 0.11), used for tracing IDs, schema hints, content-type, etc.
- **Offset** — assigned by the broker on append; the record's position in its partition. The producer does not choose it.
- **Partition** — which partition the record landed in.

Kafka treats key/value as **opaque byte arrays**. It does not parse them. Serialization/deserialization happens in the client (the producer's `Serializer`, the consumer's `Deserializer`). The broker neither knows nor cares whether your value is JSON or a JPEG.

> **Beginner note — "bytes / byte array":** On the JVM, `byte[]` is just a raw block of memory with no inherent meaning. Kafka stores and transmits these raw bytes. Turning a Java object (say a `Order`) into bytes is *serialization*; turning bytes back into an object is *deserialization*.

### 2.2 Topic

A **topic** is a named logical stream — the unit you publish to and subscribe from, e.g. `orders`, `clicks`, `payments.events`. A topic is purely a logical grouping. Physically it is implemented as one or more **partitions**. Topics are multi-subscriber: any number of consumer groups can read the same topic independently.

### 2.3 Partition — the real unit of everything

A **partition** is the actual append-only log. This is the heart of Kafka. Key facts:

- A topic with `P` partitions is `P` independent logs.
- Each partition is an **ordered, immutable sequence of records**. Order is guaranteed **within a partition** and **only** within a partition. Across partitions there is *no* global order. (This per-partition-only ordering is the single most consequential guarantee in Kafka — see §2.10 and §7.)
- Each record in a partition has a unique, monotonically increasing 64-bit integer called the **offset**: 0, 1, 2, 3, … Offsets are *per partition* (partition 0 and partition 1 both have an offset 5; they are unrelated records).
- A partition lives entirely on one broker as its **leader** (plus copies on follower brokers). A single partition is never split across brokers — so a single partition's throughput is bounded by a single broker/disk. This is *the* scaling unit: to go faster, add partitions.

Why partitions at all? Because a single append-only file on one machine can only go so fast and only fit so much. Partitions are how Kafka achieves **horizontal scalability** (spread partitions across many brokers) and **parallelism** (each partition can be consumed by a different consumer thread in parallel).

> **Beginner note — "horizontal scalability":** Scaling *up* (vertical) means buying a bigger machine; scaling *out* (horizontal) means adding more machines and dividing the work among them. Kafka scales out by adding brokers and spreading partitions.

### 2.4 Offset

An **offset** is the integer position of a record within a partition. It is the *primary key* of a record within that partition and never changes. Three offset-ish positions matter constantly:

- **Log-start offset** — the oldest offset still retained (older ones were deleted by retention or compaction).
- **Log-end offset (LEO)** — one past the last record written; i.e., the offset that will be assigned to the *next* record.
- **High-watermark (HW)** — the highest offset that has been replicated to all in-sync replicas and is therefore safe to expose to consumers. Consumers can never read past the high-watermark. (Defined fully in §3.6.)

A **consumer's position** is the offset it will fetch next. A **committed offset** is the offset a consumer has durably recorded as "processed up to here," stored in the internal `__consumer_offsets` topic. These are different: position is in-memory and advances as you fetch; committed is what survives a restart.

### 2.5 Producer

A **producer** is a client that appends records to topics. The producer:

1. Serializes the key and value to bytes.
2. Chooses a partition (by key hash, round-robin/sticky, or an explicit partition number, or a custom partitioner).
3. Buffers and **batches** records in memory, grouped by partition.
4. Sends batches to the **leader broker** of each partition.
5. Waits for the configured acknowledgement (`acks`).

Producers are designed for high throughput via batching and compression. They are thread-safe and meant to be shared across application threads.

### 2.6 Consumer & consumer group

A **consumer** reads records from partitions by issuing **fetch** requests. Consumers track their own progress (offsets) and explicitly or automatically **commit** offsets so they can resume after a restart.

A **consumer group** is a set of consumer instances sharing a `group.id`. Kafka divides the partitions of the subscribed topics among the members so that **each partition is consumed by exactly one member of the group**. This gives you scaling: add consumers (up to the partition count) to process faster. Add a second group with a different `group.id` and it gets *its own independent copy* of the stream from offset 0 (or wherever it starts) — this is how the same `orders` topic feeds both billing and analytics without interference.

A crucial consequence: **the number of partitions caps the parallelism of a single group.** A topic with 6 partitions can usefully employ at most 6 consumers in one group; a 7th sits idle.

> **Beginner note — "publish/subscribe (pub/sub)":** A messaging pattern where publishers send to a named channel without knowing who reads, and any number of subscribers receive. Kafka topics are pub/sub channels; consumer groups decide whether the subscribers *share* the load (one group) or each get *all* the data (separate groups).

### 2.7 Broker

A **broker** is a single Kafka server process (a JVM). It:

- Stores partition data on local disk.
- Serves produce requests (appends) and fetch requests (reads).
- Acts as **leader** for some partitions and **follower** for others.
- Participates in cluster metadata and replication.

A **cluster** is a set of brokers. One broker is the **controller** (see §2.9). Each broker has a unique integer `broker.id`.

### 2.8 Replication: leaders, followers, ISR

To survive machine failure, each partition is **replicated** with a **replication factor** (RF), commonly 3. Of the RF copies:

- One is the **leader**: all reads and writes for that partition go through the leader (by default; follower fetching for reads is a later, opt-in feature — §7.9).
- The rest are **followers**: they continuously fetch from the leader to stay caught up. They do not serve clients (by default).

The **in-sync replica set (ISR)** is the subset of replicas (including the leader) that are "caught up" with the leader — within `replica.lag.time.max.ms` (default 30,000 ms / 30 s). A follower that falls behind for longer than that is removed from the ISR until it catches up. Durability guarantees are defined relative to the ISR, not all replicas (§3.6).

> **Beginner note — "replica":** A copy of the data on a different machine. If the machine holding the leader dies, a follower in the ISR is promoted to leader and serving continues with no data loss (given proper config). Replication is how Kafka tolerates broker failures.

### 2.9 The controller, ZooKeeper, and KRaft

Something must coordinate the cluster: assign partition leaders, react to broker failures, manage topic metadata. Historically this was split between a **controller** broker and an external **ZooKeeper** ensemble. Modern Kafka replaces ZooKeeper with **KRaft**.

- **ZooKeeper (legacy, ≤ Kafka 3.x; removed in 4.0):** A separate distributed coordination service. Kafka used it to store cluster metadata (which brokers are alive, topic configs, partition assignments, ACLs) and to elect the controller. It was an operational burden (a second cluster to run and tune) and a scalability bottleneck (metadata changes had to round-trip through ZooKeeper).

  > **Beginner note — "ZooKeeper":** A small, strongly-consistent key-value store / coordination service used by many distributed systems for leader election and configuration. Think "a reliable shared notepad with locks that all the brokers agree on."

- **KRaft (Kafka Raft, GA since 3.3; the only mode in Kafka 4.0+):** Kafka now stores its own metadata in an internal Kafka-style log managed by the **Raft consensus protocol**, run by a quorum of **controller** nodes. No external ZooKeeper. Metadata is itself a replicated log — Kafka eating its own dog food. Benefits: simpler ops (one system), faster controller failover (seconds instead of tens of seconds), and support for *millions* of partitions instead of tens of thousands.

  > **Beginner note — "Raft":** A consensus algorithm: a way for a group of machines to agree on an ordered sequence of decisions (a log) even if some fail. One node is the leader; it replicates entries to followers; an entry is "committed" once a majority acknowledges it. KRaft uses Raft to agree on cluster metadata changes.

  > **Beginner note — "consensus / quorum":** Consensus is getting multiple machines to agree on a value despite failures. A quorum is a majority (e.g., 2 of 3, 3 of 5). Requiring a majority to agree guarantees any two quorums overlap in at least one node, which prevents split decisions.

### 2.10 Ordering — the guarantee and its boundary

Kafka guarantees **total order within a single partition**, and **no order across partitions**. Consequences you must design around:

- To preserve order for a logical entity, route all its records to the **same partition** — do this by giving them the **same key** (Kafka hashes the key to pick a partition). E.g., key by `accountId` so all events for an account are ordered.
- If you key by nothing and let records spread across partitions, you get higher parallelism but lose cross-record order.
- More partitions = more parallelism but smaller ordering scope. This tension is permanent and central to topic design.

### 2.11 Retention vs. compaction (deletion policy)

Records are not deleted on consumption. They are removed by a **cleanup policy**:

- **`delete` (time/size retention):** Records older than `retention.ms` (default 7 days) or beyond `retention.bytes` are deleted, *segment by segment* (§3.2). This is the default; good for event streams.
- **`compact` (log compaction):** Kafka retains *at least the latest value for each key*, deleting older values for the same key. Turns the log into a "latest snapshot per key" — ideal for changelogs/state (the `__consumer_offsets` topic uses compaction). Detailed in §7.4.
- **`compact,delete`:** Both — compact, and also drop segments past retention.

### 2.12 Putting it together — the layered model

```
topic "orders"  (logical)
   ├── partition 0 ──> log: [r0][r1][r2]...[rN]      offsets 0..N
   ├── partition 1 ──> log: [r0][r1]...[rM]
   └── partition 2 ──> log: [r0][r1]...[rK]
        each partition: leader on one broker + RF-1 followers
        each partition log: split into segments on disk
            segment = .log (records) + .index (offset→pos) + .timeindex (time→offset)
```

---

## 3. How it works internally

This is the heart of the chapter. We go down to bytes on disk and packets on the wire.

### 3.1 The partition as files on disk

A partition is a directory on a broker, named `<topic>-<partition>`, e.g. `orders-3`, under one of the `log.dirs` directories. Inside, the log is split into **segments**. Each segment is a set of files sharing a **base offset** (the offset of the first record in that segment), zero-padded to 20 digits:

```
/var/lib/kafka/data/orders-3/
  00000000000000000000.log          # records, byte 0 = offset 0
  00000000000000000000.index        # sparse offset index for this segment
  00000000000000000000.timeindex    # sparse timestamp index
  00000000000000170431.log          # next segment, first record = offset 170431
  00000000000000170431.index
  00000000000000170431.timeindex
  00000000000000170431.snapshot     # producer-state snapshot (idempotence/txn)
  leader-epoch-checkpoint           # leader epoch history (truncation safety)
  partition.metadata                # topic id etc.
```

- **`.log`** — the actual record bytes, in append order. This is where data lives.
- **`.index`** — a **sparse** mapping from *relative offset* (offset minus base offset) → **byte position** in the `.log`. Sparse means it has an entry roughly every `index.interval.bytes` (default 4096 bytes / 4 KB), not every record. To find offset X, binary-search the index for the largest indexed offset ≤ X, jump to that byte position in `.log`, then scan forward. Sparse keeps the index small enough to memory-map.
- **`.timeindex`** — a sparse mapping from timestamp → offset, so the broker can answer "give me the first record at or after time T" (used by `seekToTimestamp`, retention-by-time, and consumers starting from a wall-clock time).
- **`.snapshot`** — snapshots of producer state (sequence numbers, txn state) for idempotent/transactional producers, so recovery doesn't have to scan the whole log.
- **`leader-epoch-checkpoint`** — records `(leader_epoch, start_offset)` pairs. Critical for correct truncation after leader changes (§7.6).

> **Beginner note — "memory-mapped file (mmap)":** The OS can map a file directly into a process's virtual address space, so reading the file is just reading memory; the kernel pages data in on demand and shares it via the page cache. Kafka memory-maps the small index files for fast lookups.

### 3.2 Segments and the active segment

Each partition's log is a chain of segments. Exactly one is the **active segment** — the one currently being appended to. The active segment is **never deleted or compacted** while active. A new segment is **rolled** (started) when any of these triggers fires:

- The active segment reaches `segment.bytes` (default 1 GiB, i.e. 1,073,741,824 bytes).
- The active segment's age reaches `segment.ms` (default 7 days / 604,800,000 ms).
- The index file fills up to `segment.index.bytes` (default 10 MiB).

Why segment at all? Because retention and compaction operate at **segment granularity**. To delete old data, Kafka just deletes whole closed segment files — an O(1) `unlink`, no rewriting. To compact, it works segment by segment. Segmentation makes deletion cheap and bounds index sizes.

### 3.3 The append path (write) step by step

What happens when a producer sends a batch to a partition leader:

1. **Producer side — accumulation.** The producer's `send()` does *not* immediately go to the network. The record is serialized, the partition chosen, and the record placed into a per-partition **batch** in the **RecordAccumulator** (an in-memory buffer of size `buffer.memory`, default 32 MiB). A background **Sender** thread groups batches and sends them.
2. **Producer side — batching window.** A batch is sent when it fills to `batch.size` (default 16,384 bytes / 16 KB) *or* `linger.ms` (default 0 ms) elapses, whichever first. `linger.ms > 0` trades a little latency for much bigger batches and higher throughput. Optional compression (`compression.type`: `none`/`gzip`/`snappy`/`lz4`/`zstd`) is applied to the whole batch.
3. **Wire format.** Multiple records are wrapped in a **record batch** (the v2 message format, since 0.11) — a single framed structure with a header (base offset placeholder, producer id, epoch, base sequence, CRC, compression flags) followed by the compressed records. Compression is **batch-level**, which is far more effective than per-record.
4. **Leader receives.** The leader broker's network thread reads the request; an I/O (request-handler) thread validates it (CRC check, schema-agnostic but format checks), assigns **offsets** to the records (the leader is the sole authority on offset assignment — this is why offsets are gap-free per partition), and stamps the **log-append timestamp** if the topic is set to `LogAppendTime`.
5. **Append to active segment.** The batch bytes are **appended** to the active `.log` file. Crucially this is a write into the OS **page cache** (memory), *not* necessarily an `fsync` to the physical disk. Kafka deliberately relies on the page cache and OS flushing, not synchronous fsync per write (see §3.4). The leader updates its in-memory log-end offset and, if a sparse-index threshold is crossed, adds entries to `.index`/`.timeindex`.
6. **Replication.** Followers in the ISR are continuously issuing **fetch** requests to the leader (followers are just special consumers). They pull the new batch and append it to their own logs. When a follower's fetch indicates it has reached offset X, the leader knows that follower has replicated up to X.
7. **High-watermark advance.** Once *all* replicas in the ISR have replicated up to some offset, the leader advances the **high-watermark (HW)** to that offset. Only records at or below the HW are visible to consumers.
8. **Acknowledge.** Depending on `acks`:
   - `acks=0`: producer doesn't wait at all (fire-and-forget; data loss on failure).
   - `acks=1`: leader replies as soon as *it* has written to its log (page cache). If the leader dies before a follower replicates, that record is lost.
   - `acks=all` (a.k.a. `acks=-1`): leader replies only after all ISR members have replicated. Combined with `min.insync.replicas`, this gives the strongest durability.
9. **Producer callback.** The producer receives the `RecordMetadata` (topic, partition, offset, timestamp) via the returned `Future` or the async callback.

> **Beginner note — "page cache":** The OS keeps recently used file data in RAM. Reads hit RAM if cached; writes go to RAM first and the kernel flushes to disk later (or on `fsync`). Kafka leans hard on the page cache: it does almost no caching inside the JVM, deferring to the OS, which keeps JVM heap small and lets all of free RAM act as a giant cache shared across produce and consume.

> **Beginner note — "fsync":** A system call forcing the OS to write buffered data all the way to physical disk and confirm. Expensive. Kafka by default does *not* fsync per record — it trusts replication across machines for durability instead of trusting one disk. You *can* force fsync via `flush.messages`/`flush.ms`, but it's rarely needed because RF=3 + `acks=all` protects against single-machine loss without per-write fsync.

> **Beginner note — "CRC (cyclic redundancy check)":** A checksum stored with each batch to detect corruption. Kafka verifies the CRC on produce and on read, catching bit-rot and truncated writes.

### 3.4 Why Kafka is fast: sequential I/O, page cache, zero-copy

These three mechanics explain Kafka's throughput. Understand all three.

**(a) Sequential I/O.** Because the log is append-only, writes go to the *end* of one file. There is no random seeking. On spinning disks the difference between sequential and random access is enormous (sequential can be ~100s of MB/s; random can be < 1 MB/s for tiny ops due to seek latency). Jeff Dean's famous numbers and Kafka's own design docs note sequential disk throughput can rival or exceed random *memory* access for streaming workloads. SSDs narrow the gap but sequential still wins (better wear, fewer write-amplification surprises, better controller pipelining). Reads are also largely sequential: consumers stream forward through the log, which the OS readahead loves.

**(b) Page cache as the cache.** Kafka stores essentially nothing in JVM heap for record data. Writes land in the page cache; recently written data is read back from page cache (hot consumers reading near the tail almost never touch disk). This avoids: (1) duplicating data in JVM heap + page cache, (2) GC pressure from a large object cache, (3) cache loss on broker restart (the page cache is in the OS, so a JVM restart keeps the cache warm). It does mean you should give the OS lots of free RAM and keep the JVM heap modest (commonly 4–8 GiB even on large brokers).

> **Beginner note — "GC (garbage collection)":** The JVM automatically reclaims unused memory. Large heaps full of cached objects cause long, latency-spiking GC pauses. Kafka sidesteps this by keeping record data *out* of the heap and *in* the OS page cache.

**(c) Zero-copy via `sendfile`.** When a consumer fetches data, the broker must move bytes from a file to a socket. The naive path copies data four times and crosses the kernel/user boundary twice:

```
disk -> kernel page cache -> JVM/user buffer -> kernel socket buffer -> NIC
   (read)                  (copy to user)    (write to socket)
```

The **`sendfile(2)`** syscall lets the kernel copy file data **directly** from the page cache to the socket buffer (and on modern NICs, even DMA straight to the network card), skipping the trip through user space entirely:

```
disk -> kernel page cache -> NIC        (sendfile: no user-space copy, no extra CPU copies)
```

The JVM exposes this via `FileChannel.transferTo()`, which Kafka uses on the consumer fetch path. The payoff: serving a consumer is almost CPU-free; a broker can saturate the NIC with little CPU. **Caveat:** `sendfile` zero-copy works only when the broker sends bytes *as-is*. If the broker must re-encrypt for TLS, or re-compress/convert message format (e.g., serving an old-format client), it must touch the bytes in user space and **zero-copy is lost**. So mismatched client versions or TLS reduce this benefit (TLS in particular forces a copy for in-kernel encryption unless kernel TLS / kTLS is in play).

> **Beginner note — "DMA (direct memory access)":** Hardware (like a NIC or disk controller) moving data to/from RAM without involving the CPU for each byte. Zero-copy chains DMA transfers so the CPU mostly just sets things up.

> **Beginner note — "syscall (system call)":** A request from a user program into the OS kernel (e.g., to read a file or send on a socket). Crossing this boundary costs CPU; minimizing crossings (as zero-copy does) speeds things up.

### 3.5 The read (fetch) path step by step

1. **Consumer issues a fetch.** A consumer sends a **FetchRequest** to the leader of each partition it owns, specifying for each partition the **fetch offset** (where to start), `fetch.max.bytes` / `max.partition.fetch.bytes` (size caps), and `fetch.min.bytes`/`fetch.max.wait.ms` (how long the broker may wait to accumulate data — long polling).
2. **Broker locates the offset.** For the requested offset, the broker finds the right segment (segments are named by base offset; binary search the segment list), then binary-searches that segment's `.index` for the nearest indexed offset ≤ target, seeks to that byte position in `.log`, and scans forward to the exact offset.
3. **Broker bounds the response at the high-watermark.** The broker will not return records past the **HW** (so consumers never see un-replicated, possibly-to-be-lost data under default read-uncommitted... actually they never see beyond HW regardless). For transactional reads (`read_committed`), it also won't return past the **last stable offset (LSO)** (§7.5).
4. **Zero-copy send.** The broker uses `sendfile`/`transferTo` to stream the bytes from page cache to the socket — no decompression, no deserialization on the broker. The *consumer* decompresses and deserializes.
5. **Consumer processes & advances.** The consumer's `poll()` returns the records; the application processes them; the consumer's in-memory **position** advances to the next offset.
6. **Commit.** Periodically (auto-commit, default every `auto.commit.interval.ms` = 5000 ms) or explicitly, the consumer writes its committed offset to the internal `__consumer_offsets` topic (a compacted topic keyed by `(group, topic, partition)`). On restart/rebalance, the consumer resumes from the committed offset.

> **Beginner note — "long polling":** Instead of the consumer hammering the broker and getting empty replies, the broker holds the fetch open up to `fetch.max.wait.ms` until at least `fetch.min.bytes` of data is available, then replies. Fewer round-trips, lower CPU.

### 3.6 High-watermark, ISR, and the durability state machine

The **high-watermark (HW)** is the offset up to which data is replicated to *all* current ISR members and is therefore "committed" and consumer-visible. Mechanics:

- Leader's HW = min(log-end offsets of all ISR members).
- A produce with `acks=all` is acknowledged once HW ≥ the produced offset (i.e., all ISR have it).
- Consumers can only read up to HW. This prevents a consumer from reading a record that later vanishes if the leader fails before replication completed.

**`min.insync.replicas` (default 1, but you almost always set 2 with RF=3):** the minimum number of replicas that must be in the ISR for an `acks=all` produce to succeed. If the ISR shrinks below this (too many followers lagging/dead), the leader **rejects** `acks=all` writes with `NotEnoughReplicasException` rather than accept data that isn't sufficiently durable. The classic safe combo:

```
replication.factor = 3
min.insync.replicas = 2
acks = all
```

This tolerates the loss of **one** broker with **no data loss and no write outage** (2 of 3 still in sync ≥ min 2). Lose two brokers and writes pause (correctly — you'd rather pause than lose data).

> **Beginner note — "in-sync replica (ISR) again, precisely":** A replica is *in-sync* if it has fully replicated up to the leader's log-end offset within `replica.lag.time.max.ms`. The ISR is dynamic: replicas drop out when they lag and rejoin when caught up. The ISR — not the full replica set — defines committed/durable.

### 3.7 Leader election, unclean leader election, and the controller

When a broker dies, every partition it led needs a new leader. The **controller** picks a new leader from the partition's ISR (preferring the next live ISR member). Because the new leader was in the ISR, it had all committed (≤ HW) data — **no committed data is lost**.

**Unclean leader election** (`unclean.leader.election.enable`, default **false** in modern Kafka): if *no* ISR member is available (all in-sync replicas dead), should Kafka promote an out-of-sync follower (which is missing recent committed records)? If `true`, you regain availability but **lose data** (the gap between that follower and the dead leader). If `false`, the partition stays **offline** until an ISR member returns — choosing consistency over availability. This is a direct **CAP** lever.

> **Beginner note — "CAP theorem":** In a distributed system, during a network **P**artition you must choose between **C**onsistency (every read sees the latest write or errors) and **A**vailability (every request gets a non-error response, possibly stale). Kafka with `unclean.leader.election=false` chooses C over A for that partition; `true` chooses A over C.

### 3.8 Producer idempotence and exactly-once internals

By default a producer retry can create **duplicates**: producer sends batch, broker writes it, ack is lost on the network, producer retries, broker writes it again. **Idempotent producer** (`enable.idempotence=true`, the default since Kafka 3.0) fixes this:

- Each producer gets a **Producer ID (PID)** and an **epoch** from the broker.
- Each record batch carries a per-partition **sequence number**.
- The broker tracks the last sequence per (PID, partition). A retried batch with a sequence it already saw is **deduplicated** (acknowledged but not re-appended). A gap in sequence is rejected (`OutOfOrderSequenceException`).
- This guarantees **exactly-once *append*** for a single producer session, *per partition*, and also preserves ordering even with retries (it forces in-order acks). Requires `acks=all`, `retries>0`, and `max.in.flight.requests.per.connection ≤ 5`.

**Transactions** extend this across multiple partitions/topics atomically and across the consume-process-produce loop (exactly-once stream processing). Covered in §7.5.

### 3.9 End-to-end data flow, fully traced

Tracing one record from app to app:

```
1. App calls producer.send(new ProducerRecord("orders", "acct-42", orderBytes))
2. Serializer turns key/value to bytes; partitioner hashes "acct-42" -> partition 3
3. Record appended to RecordAccumulator batch for orders-3
4. linger.ms/batch.size triggers Sender; batch compressed (lz4), framed (v2)
5. Sender sends ProduceRequest to leader of orders-3 (say broker 2)
6. Broker 2 validates CRC, assigns offsets (say 170431..170450), appends to active .log (page cache)
7. ISR followers (brokers 1,3) fetch the new batch, append to their logs
8. All ISR replicated up to 170450 -> HW advances to 170451
9. With acks=all, broker 2 acks the ProduceRequest -> producer Future completes with offset 170431
10. Consumer in group "search-indexer" owns orders-3; its position is 170431
11. Consumer sends FetchRequest(orders-3, offset=170431)
12. Broker 2 finds segment, indexes to byte pos, sendfile()s bytes (<= HW) to socket
13. Consumer decompresses, deserializes, processes records
14. Consumer commits offset 170451 to __consumer_offsets (keyed by group/topic/partition)
15. On restart, consumer resumes fetch at 170451
```

---

## 4. The complete toolkit

### 4.1 Core broker / topic configs

| Config | Scope | Default | Purpose |
|---|---|---|---|
| `num.partitions` | broker (new-topic default) | 1 | Default partition count for auto/created topics. |
| `default.replication.factor` | broker | 1 | Default RF for new topics. Set ≥3 in prod. |
| `log.retention.ms` / `.minutes` / `.hours` | topic (`retention.ms`) | 604800000 (7 d) | Time-based deletion threshold. |
| `log.retention.bytes` (`retention.bytes`) | topic | -1 (unlimited) | Size cap per **partition** before deletion. |
| `log.segment.bytes` (`segment.bytes`) | topic | 1073741824 (1 GiB) | Max segment size before rolling. |
| `log.roll.ms`/`.hours` (`segment.ms`) | topic | 604800000 (7 d) | Max segment age before rolling. |
| `log.index.interval.bytes` (`index.interval.bytes`) | topic | 4096 (4 KB) | Bytes between sparse index entries. |
| `log.index.size.max.bytes` (`segment.index.bytes`) | topic | 10485760 (10 MiB) | Max index file size. |
| `cleanup.policy` | topic | `delete` | `delete` / `compact` / `compact,delete`. |
| `min.insync.replicas` | topic/broker | 1 | Min ISR for `acks=all` to succeed. Set 2 with RF=3. |
| `unclean.leader.election.enable` | topic/broker | false | Allow promoting out-of-sync replica (lose data for availability). |
| `replica.lag.time.max.ms` | broker | 30000 (30 s) | How long a follower can lag before leaving ISR. |
| `message.max.bytes` | broker/topic (`max.message.bytes`) | 1048588 (~1 MiB) | Max record-batch size accepted. |
| `log.dirs` | broker | /tmp/kafka-logs | Disk directories for partition data (JBOD = list). |
| `num.io.threads` | broker | 8 | Request-handler threads. |
| `num.network.threads` | broker | 3 | Socket-handling threads. |
| `log.flush.interval.messages` / `.ms` | topic | very large / null | Force fsync every N messages / ms (rarely set). |
| `compression.type` | topic | `producer` | Broker re-compression policy; `producer` keeps producer's. |
| `min.compaction.lag.ms` / `max.compaction.lag.ms` | topic | 0 / ∞ | Bounds before/by-when a record may be compacted. |
| `delete.retention.ms` | topic | 86400000 (1 d) | How long tombstones (null-value compaction markers) are kept. |
| `segment.index.bytes` | topic | 10485760 | (see above). |

> **Beginner note — "JBOD (just a bunch of disks)":** Listing multiple directories on multiple physical disks in `log.dirs`; Kafka spreads partitions across them for more I/O bandwidth, without RAID. A disk failure takes down only the partitions on it (handled by replication).

> **Beginner note — "tombstone":** In a compacted topic, a record with a non-null key and **null value** signals "this key is deleted." Compaction keeps it long enough (`delete.retention.ms`) for consumers to observe the delete, then removes it.

### 4.2 Producer configs

| Config | Default | Purpose |
|---|---|---|
| `bootstrap.servers` | — | Initial broker list to discover the cluster. |
| `key.serializer` / `value.serializer` | — | Turn objects into bytes. |
| `acks` | `all` (since 3.0) | Durability: `0`/`1`/`all`. |
| `enable.idempotence` | true (since 3.0) | Dedup retries; exactly-once append per session. |
| `retries` | 2147483647 | Retry count on retriable errors. |
| `delivery.timeout.ms` | 120000 (2 min) | Total time `send()` may take incl. retries. The real upper bound. |
| `max.in.flight.requests.per.connection` | 5 | Unacked requests per connection; ≤5 to keep idempotent ordering. |
| `batch.size` | 16384 (16 KB) | Per-partition batch size target. |
| `linger.ms` | 0 | Wait this long to fill batches (throughput vs latency). |
| `buffer.memory` | 33554432 (32 MiB) | Total producer buffer; blocks/throws when full. |
| `max.block.ms` | 60000 | How long `send()` blocks when buffer full / metadata missing. |
| `compression.type` | none | `none`/`gzip`/`snappy`/`lz4`/`zstd`. |
| `partitioner.class` | sticky (default) | Partition assignment strategy. |
| `transactional.id` | null | Enables transactions; must be stable per logical producer. |
| `request.timeout.ms` | 30000 | Per-request timeout. |

### 4.3 Consumer configs

| Config | Default | Purpose |
|---|---|---|
| `bootstrap.servers` | — | Cluster discovery. |
| `group.id` | — | Consumer group membership. |
| `key.deserializer` / `value.deserializer` | — | Bytes → objects. |
| `enable.auto.commit` | true | Auto-commit offsets periodically. Often set false for control. |
| `auto.commit.interval.ms` | 5000 | Auto-commit period. |
| `auto.offset.reset` | latest | `earliest`/`latest`/`none` — where to start with no committed offset. |
| `fetch.min.bytes` | 1 | Min data before broker replies (long-poll). |
| `fetch.max.wait.ms` | 500 | Max wait when `fetch.min.bytes` not met. |
| `fetch.max.bytes` | 52428800 (50 MiB) | Max data per fetch across partitions. |
| `max.partition.fetch.bytes` | 1048576 (1 MiB) | Max per partition per fetch. |
| `max.poll.records` | 500 | Max records returned by one `poll()`. |
| `max.poll.interval.ms` | 300000 (5 min) | Max time between polls before member considered dead. |
| `session.timeout.ms` | 45000 | Heartbeat timeout for group membership. |
| `heartbeat.interval.ms` | 3000 | Heartbeat frequency. |
| `partition.assignment.strategy` | `[CooperativeSticky]` (3.0+) | How partitions are assigned in the group. |
| `isolation.level` | read_uncommitted | `read_committed` to honor transactions (read only ≤ LSO). |
| `client.rack` | null | Enables rack-aware (follower) fetching. |

### 4.4 CLI / admin tools

| Tool | What it does | Common usage |
|---|---|---|
| `kafka-topics.sh` | Create/list/describe/alter/delete topics. | `--create --topic orders --partitions 6 --replication-factor 3` ; `--describe --topic orders` |
| `kafka-console-producer.sh` | Produce records from stdin. | `--topic orders --property parse.key=true --property key.separator=:` |
| `kafka-console-consumer.sh` | Read records to stdout. | `--topic orders --from-beginning --property print.key=true` |
| `kafka-consumer-groups.sh` | Inspect/reset group offsets, show **lag**. | `--describe --group g1` ; `--reset-offsets --to-earliest --execute` |
| `kafka-configs.sh` | Get/set dynamic configs (topic/broker). | `--alter --entity-type topics --entity-name orders --add-config retention.ms=3600000` |
| `kafka-reassign-partitions.sh` | Move partitions/replicas across brokers. | generate → execute → verify |
| `kafka-leader-election.sh` | Trigger preferred/unclean leader election. | `--election-type PREFERRED --all-topic-partitions` |
| `kafka-log-dirs.sh` | Inspect on-disk sizes per broker/log dir. | `--describe --broker-list 1,2,3` |
| `kafka-dump-log.sh` | Decode raw segment files (offsets, batches, indices). | `--files 00000000000000000000.log --print-data-log` |
| `kafka-get-offsets.sh` | Query log-start / log-end offsets by time. | `--topic orders --time -1` (latest), `-2` (earliest) |
| `kafka-storage.sh` | Format storage / bootstrap KRaft metadata. | `format --cluster-id <id> --config server.properties` |
| `kafka-metadata-quorum.sh` | Inspect KRaft controller quorum status. | `describe --status` |
| `kafka-acls.sh` | Manage authorization ACLs. | `--add --allow-principal User:app --operation Write --topic orders` |
| `kafka-producer-perf-test.sh` / `kafka-consumer-perf-test.sh` | Benchmark throughput/latency. | `--num-records 1000000 --record-size 1000 --throughput -1` |

### 4.5 Key client classes / methods (Java)

| Class / method | Purpose |
|---|---|
| `KafkaProducer<K,V>` | Thread-safe producer; `send(record)`, `send(record, callback)`, `flush()`, `close()`. |
| `ProducerRecord<K,V>` | topic, [partition], [timestamp], key, value, [headers]. |
| `RecordMetadata` | Returned on ack: topic, partition, offset, timestamp. |
| `KafkaConsumer<K,V>` | **Not** thread-safe; `subscribe()`, `assign()`, `poll(Duration)`, `commitSync()`, `commitAsync()`, `seek()`, `seekToBeginning()`, `seekToEnd()`, `position()`, `committed()`, `pause()`, `resume()`. |
| `ConsumerRecords<K,V>` / `ConsumerRecord<K,V>` | Batch returned by `poll`; per-record topic/partition/offset/key/value/headers/timestamp. |
| `AdminClient` | Programmatic admin: `createTopics`, `describeTopics`, `listOffsets`, `alterConfigs`, `describeConsumerGroups`, etc. |
| `Partitioner` (interface) | Custom partition routing. |
| `ConsumerRebalanceListener` | Hook `onPartitionsRevoked` / `onPartitionsAssigned` for commit-on-revoke. |

---

## 5. Code examples by use case

All examples use the official `org.apache.kafka:kafka-clients` (3.x/4.x) Java API. Comments explain the non-obvious lines.

### 5.1 Durable producer with idempotence and explicit acks

```java
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import java.util.Properties;
import java.util.concurrent.Future;

public class DurableProducer {
  public static void main(String[] args) {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092");
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

    // Strongest single-producer durability: wait for all ISR replicas.
    p.put(ProducerConfig.ACKS_CONFIG, "all");
    // Idempotence: dedup retries, preserve per-partition order even on retry.
    p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    // Throughput tuning: linger briefly to build bigger batches; compress.
    p.put(ProducerConfig.LINGER_MS_CONFIG, 20);              // wait up to 20ms
    p.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);      // 64KB batches
    p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");    // fast, good ratio
    // Hard upper bound on send incl. retries.
    p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      for (int i = 0; i < 1000; i++) {
        // Keying by accountId ensures per-account ordering (same partition).
        String key = "acct-" + (i % 50);
        ProducerRecord<String, String> rec =
            new ProducerRecord<>("orders", key, "{\"orderId\":" + i + "}");

        // Async send with callback; never block per-record for throughput.
        producer.send(rec, (RecordMetadata md, Exception ex) -> {
          if (ex != null) {
            System.err.println("Send failed: " + ex.getMessage()); // log/alert/DLQ
          } else {
            // md tells you exactly where it landed.
            System.out.printf("ok p=%d off=%d%n", md.partition(), md.offset());
          }
        });
      }
      // flush() blocks until all buffered records are sent & acked.
      producer.flush();
    } // close() also flushes and releases the network thread.
  }
}
```

### 5.2 Consumer group with manual offset commit (at-least-once, correct)

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.*;

public class ManualCommitConsumer {
  public static void main(String[] args) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092");
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "search-indexer");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    // Turn OFF auto-commit so we commit only after successful processing.
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    // With no committed offset, start at the oldest retained record.
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
      consumer.subscribe(List.of("orders"));
      while (true) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> r : records) {
          process(r); // your business logic; must be idempotent (see below)
        }
        if (!records.isEmpty()) {
          // Commit AFTER processing => at-least-once. A crash before commit
          // reprocesses the batch (hence processing must be idempotent).
          consumer.commitSync();
        }
      }
    }
  }
  static void process(ConsumerRecord<String, String> r) {
    System.out.printf("p=%d off=%d key=%s%n", r.partition(), r.offset(), r.key());
  }
}
```

### 5.3 Precise per-partition offset commit (finest control)

```java
// Commit exact next-offsets per partition instead of one blanket commit.
// Useful when processing is partition-parallel or partially complete.
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
// ... inside the poll loop:
Map<TopicPartition, OffsetAndMetadata> toCommit = new HashMap<>();
for (TopicPartition tp : records.partitions()) {
  List<ConsumerRecord<String, String>> part = records.records(tp);
  for (ConsumerRecord<String, String> r : part) process(r);
  long lastOffset = part.get(part.size() - 1).offset();
  // Commit lastOffset + 1: the NEXT offset to read on resume.
  toCommit.put(tp, new OffsetAndMetadata(lastOffset + 1));
}
consumer.commitSync(toCommit);
```

### 5.4 Replaying / time-travel: seek to a timestamp or to the beginning

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import java.util.*;

// Reprocess everything since a wall-clock time (e.g., re-run after a bug fix).
KafkaConsumer<String,String> c = /* configured, group.id = "reprocess-job" */ null;
c.subscribe(List.of("orders"), new ConsumerRebalanceListener() {
  public void onPartitionsRevoked(Collection<TopicPartition> tps) {}
  public void onPartitionsAssigned(Collection<TopicPartition> tps) {
    long sinceMillis = System.currentTimeMillis() - Duration.ofHours(6).toMillis();
    Map<TopicPartition, Long> query = new HashMap<>();
    for (TopicPartition tp : tps) query.put(tp, sinceMillis);
    // Ask the broker (uses .timeindex) for the first offset >= that timestamp.
    Map<TopicPartition, OffsetAndTimestamp> found = c.offsetsForTimes(query);
    for (var e : found.entrySet()) {
      if (e.getValue() != null) c.seek(e.getKey(), e.getValue().offset());
      else c.seekToEnd(List.of(e.getKey())); // no record after T -> jump to tail
    }
  }
});
// Replay from the start instead:  c.seekToBeginning(c.assignment());
```

### 5.5 Custom partitioner (route by tenant to control ordering scope)

```java
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.*;
import java.util.Map;

public class TenantPartitioner implements Partitioner {
  @Override public int partition(String topic, Object key, byte[] keyBytes,
                                 Object value, byte[] valueBytes, Cluster cluster) {
    int numPartitions = cluster.partitionCountForTopic(topic);
    if (keyBytes == null) return 0; // force keyless records to a known partition
    // Reserve partition 0 for a VIP tenant to isolate its ordering & load.
    String k = key.toString();
    if (k.startsWith("vip-")) return 0;
    // Everyone else hashed across partitions 1..N-1.
    int h = Utils.toPositive(Utils.murmur2(keyBytes));
    return 1 + (h % (numPartitions - 1));
  }
  @Override public void close() {}
  @Override public void configure(Map<String, ?> configs) {}
}
// Register:  props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, TenantPartitioner.class.getName());
```

### 5.6 Transactional, exactly-once consume-process-produce

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;
import java.util.*;

public class ExactlyOnce {
  public static void main(String[] args) {
    // Producer with a STABLE transactional.id (one per logical instance).
    Properties pp = new Properties();
    pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092");
    pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
    pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
    pp.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "txn-orders-enricher-1");
    KafkaProducer<String,String> producer = new KafkaProducer<>(pp);
    producer.initTransactions(); // fences out zombie instances with same txn.id

    Properties cp = new Properties();
    cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092");
    cp.put(ConsumerConfig.GROUP_ID_CONFIG, "enricher");
    cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
    cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
    cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // offsets committed in the txn
    cp.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    KafkaConsumer<String,String> consumer = new KafkaConsumer<>(cp);
    consumer.subscribe(List.of("orders"));

    while (true) {
      ConsumerRecords<String,String> recs = consumer.poll(Duration.ofMillis(500));
      if (recs.isEmpty()) continue;
      producer.beginTransaction();
      try {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String,String> r : recs) {
          producer.send(new ProducerRecord<>("orders.enriched", r.key(), enrich(r.value())));
          offsets.put(new TopicPartition(r.topic(), r.partition()),
                      new OffsetAndMetadata(r.offset() + 1));
        }
        // Atomically: the produced records AND the consumed offsets commit together.
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
        producer.commitTransaction();
      } catch (Exception e) {
        producer.abortTransaction(); // nothing becomes visible to read_committed readers
      }
    }
  }
  static String enrich(String v) { return v + ",enriched=true"; }
}
```

### 5.7 AdminClient: create a topic and read lag programmatically

```java
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.*;
import java.util.*;

try (Admin admin = Admin.create(Map.of("bootstrap.servers", "broker1:9092"))) {
  // Create a 6-partition, RF=3 topic with min.insync.replicas=2 and 3-day retention.
  NewTopic t = new NewTopic("payments", 6, (short) 3)
      .configs(Map.of("min.insync.replicas", "2", "retention.ms", "259200000"));
  admin.createTopics(List.of(t)).all().get();

  // Compute consumer lag = log-end-offset - committed-offset, per partition.
  String group = "billing";
  Map<TopicPartition, OffsetAndMetadata> committed =
      admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
  Map<TopicPartition, OffsetSpec> latestSpec = new HashMap<>();
  committed.keySet().forEach(tp -> latestSpec.put(tp, OffsetSpec.latest()));
  Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
      admin.listOffsets(latestSpec).all().get();
  committed.forEach((tp, off) -> {
    long lag = ends.get(tp).offset() - off.offset();
    System.out.printf("%s lag=%d%n", tp, lag);
  });
}
```

### 5.8 Inspecting raw segment files on a broker

```bash
# Decode an on-disk segment to see offsets, batches, compression, producer IDs.
kafka-dump-log.sh \
  --files /var/lib/kafka/data/orders-3/00000000000000170431.log \
  --print-data-log
# Output shows: baseOffset, lastOffset, producerId, producerEpoch,
# baseSequence, compressType, position, CRC, and (with --print-data-log) payloads.

# Inspect the sparse offset index (offset -> byte position):
kafka-dump-log.sh --files /var/lib/kafka/data/orders-3/00000000000000170431.index

# Inspect the time index (timestamp -> offset):
kafka-dump-log.sh --files /var/lib/kafka/data/orders-3/00000000000000170431.timeindex
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Batch and linger.** The single biggest producer throughput lever: set `linger.ms` to 5–50 ms and `batch.size` to 32–256 KB. Bigger batches amortize network and compression overhead and produce better compression ratios.
- **Compress.** `lz4` or `zstd` are the modern choices — `zstd` for best ratio (great for text/JSON), `lz4` for lowest CPU. Compression is batch-level, so it works best with large batches.
- **Right-size partitions.** Throughput scales with partition count *up to* the cluster's disk/network limits. Too few partitions = parallelism ceiling; too many = more open files, more replication overhead, slower controller failover, larger metadata. A common heuristic: target a few thousand partitions per broker max; size partitions so each handles a manageable MB/s. Don't over-partition "just in case" — you can add partitions later (but it disrupts keyed ordering, see anti-patterns).
- **Keep the JVM heap small, leave RAM for page cache.** Brokers often run 4–8 GiB heap with the rest of RAM as page cache. Use G1GC (default) or ZGC for very large heaps; watch for long GC pauses causing ISR shrink.
- **Don't co-locate other heavy I/O on Kafka disks.** Kafka's sequential-I/O advantage evaporates if another process makes the disk seek randomly.
- **Avoid format conversion.** Keep clients on a recent protocol/version so the broker can use zero-copy (`sendfile`) instead of decoding/re-encoding old message formats per fetch.

### 6.2 Correctness & delivery semantics

- **At-most-once:** commit before processing (or `acks=0`). Lose records on failure. Rare.
- **At-least-once (default, most common):** commit after processing; crashes cause reprocessing → **make processing idempotent** (dedupe by record key/offset, use upserts, idempotent downstream APIs).
- **Exactly-once (within Kafka):** transactions + `read_committed` (§5.6) for consume-process-produce. End-to-end exactly-once *to an external system* still needs idempotent writes or a transactional sink — Kafka's EOS doesn't magically extend to your database unless that write is part of the same atomic unit (e.g., Kafka Connect with idempotent sinks, or outbox pattern).
- **Ordering:** preserve with keying + (idempotence to keep order under retries). Note: setting `max.in.flight.requests.per.connection > 1` without idempotence can reorder on retry.
- **Rebalance hazards:** on `max.poll.interval.ms` overrun (your processing took too long), the consumer is kicked from the group, triggering a rebalance and **possible duplicate processing**. Keep poll loops tight; offload slow work; tune `max.poll.records`.

### 6.3 Security

- **Encryption in transit:** TLS (`security.protocol=SSL` or `SASL_SSL`). Note TLS defeats `sendfile` zero-copy (bytes must be encrypted in user space unless kernel-TLS is configured), costing CPU.
- **Authentication:** SASL mechanisms — `PLAIN`, `SCRAM-SHA-256/512`, `GSSAPI` (Kerberos), `OAUTHBEARER`. Use SCRAM or mTLS in prod, never plaintext.
- **Authorization:** ACLs via the authorizer (`kafka-acls.sh`) grant read/write/describe per principal per resource (topic, group, cluster).
- **Encryption at rest:** Kafka has no built-in disk encryption; use OS/volume-level (LUKS, cloud KMS-backed disks).
- **Quotas:** throttle rogue clients by bytes/sec and request rate (`client.quota`), preventing one tenant from starving others.

### 6.4 Observability

- **Lag is the #1 health metric.** Consumer lag = log-end-offset − committed-offset per partition. Rising lag = consumers falling behind. Track via `kafka-consumer-groups.sh --describe` or JMX `records-lag-max` (consumer-side) or Burrow / Cruise Control / your APM.
- **Broker JMX metrics:** `UnderReplicatedPartitions` (should be 0; >0 means ISR shrinking — a follower or broker is unhealthy), `OfflinePartitionsCount` (should be 0; >0 means data unavailable), `ActiveControllerCount` (exactly 1 across the cluster), `RequestHandlerAvgIdlePercent` (low = saturated), `BytesInPerSec`/`BytesOutPerSec`, `ISRShrinksPerSec`/`ISRExpandsPerSec`.
- **Disk:** monitor free space per `log.dirs`; a full disk takes a broker (and its leaderships) down hard.
- **End-to-end latency:** producer `record-queue-time`, broker `RequestQueueTimeMs`/`LocalTimeMs`/`RemoteTimeMs`, consumer fetch latency.

### 6.5 Cost

- **Storage dominates** with long retention. Tier hot/cold: enable **tiered storage** (KIP-405, GA in 3.6+ for some, broadly in 3.9/Confluent) to offload old segments to object storage (S3/GCS) cheaply while keeping recent data on local disk.
- **Network egress** can dominate in cloud, especially cross-AZ replication. Rack-aware placement and follower fetching reduce cross-AZ reads.
- **Over-provisioning partitions** raises memory, file-handle, and metadata cost. Right-size.

### 6.6 Testing

- **Unit:** `MockProducer` / `MockConsumer` for client logic without a broker.
- **Integration:** **Testcontainers** (`KafkaContainer`) or **embedded Kafka** (Spring Kafka `EmbeddedKafkaBroker`) spin a real broker in-process/in-Docker for CI.
- **Contract/schema:** test serialization compatibility against the **Schema Registry** (Avro/Protobuf/JSON Schema) to catch breaking schema changes before deploy.
- **Chaos:** kill brokers, induce network partitions, fill disks; verify `min.insync.replicas`/`acks=all` behavior matches expectations.

### 6.7 Production hardening checklist

- RF ≥ 3, `min.insync.replicas=2`, `acks=all`, `unclean.leader.election.enable=false`.
- Spread replicas across racks/AZs (`broker.rack`) so one AZ outage keeps a quorum.
- Idempotent producers on (default); transactions where EOS is needed.
- Tight consumer poll loops; alert on lag and on `UnderReplicatedPartitions > 0`.
- Capacity headroom on disk (alert at 70–80%); retention sized to disk.
- Separate listeners for internal replication vs client traffic; TLS + SASL on client listeners.
- Use **Cruise Control** for automated partition balancing; rebalance disk/leader skew.

### 6.8 Common anti-patterns

- **Using Kafka as a database / request-response store.** It's a log, not a random-access KV store; point lookups by key are not its job (use compaction + a state store / external DB).
- **One giant partition.** Caps throughput at one disk and serializes all consumers. Partition deliberately.
- **Tiny `max.message.bytes` with huge payloads,** or worse, storing large blobs (images/video) in Kafka — store the blob in object storage and the reference in Kafka (the "claim check" pattern).
- **Relying on cross-partition ordering.** It doesn't exist.
- **Adding partitions to a keyed topic in production** — it changes the key→partition mapping for *new* records (old records stay put), breaking per-key ordering across the change. Plan partition count up front for keyed topics.
- **Auto-commit + slow processing** → committing offsets for records you haven't finished, losing them on crash. Use manual commit for at-least-once correctness.
- **Treating consumer lag as fine because "it catches up."** Sustained lag means you're under-provisioned; bursts that never drain are a ticking outage.

---

## 7. Advanced topics & deep internals

### 7.1 The v2 record batch format (on the wire and on disk)

Since 0.11, records travel and rest in a **RecordBatch** (a.k.a. message set v2). One batch holds many records and a shared header:

- `baseOffset` (8 bytes), `batchLength`, `partitionLeaderEpoch`, `magic` (2 = v2), `crc` (CRC-32C over the batch), `attributes` (compression codec, timestamp type, isTransactional, isControl), `lastOffsetDelta`, `firstTimestamp`, `maxTimestamp`, `producerId`, `producerEpoch`, `baseSequence`, then `recordsCount` and the (optionally compressed) records.
- Individual records store **deltas**: `offsetDelta` and `timestampDelta` relative to the batch base — compact varint-encoded. This is why a million records compress and frame so efficiently.
- **Control batches** (isControl) carry transaction markers (COMMIT/ABORT), not user data; consumers skip them.

This format is why offsets are gap-free *logically* but the physical file is a sequence of variable-length batches, and why the index maps offsets to *batch* byte positions, then the broker scans within the batch.

### 7.2 The sparse index and offset lookup, precisely

- `.index` entries are 8 bytes: 4-byte **relative offset** + 4-byte **physical position**. Relative-to-base keeps it 4 bytes (good to ~2 GiB segments).
- Lookup: binary search `.index` → largest entry ≤ target → seek to that file position → linearly read batches forward until the batch containing the target offset. Average scan is bounded by `index.interval.bytes` (4 KB) of log between index points — tiny.
- `.timeindex` entries are 12 bytes: 8-byte timestamp + 4-byte relative offset; used for `offsetsForTimes`, time-based retention, and the time-based seek in §5.4.

### 7.3 Log cleaner / compaction mechanics

For `cleanup.policy=compact`:

- A pool of **log cleaner threads** (`log.cleaner.threads`, default 1) processes the "dirty" portion of the log (everything past the last clean point).
- The cleaner builds an in-memory **offset map**: key → highest offset seen. Memory for this is bounded by `log.cleaner.dedupe.buffer.size` (default 128 MiB) split across threads; if a partition's keyspace exceeds the map, cleaning slows.
- It then rewrites segments keeping, for each key, **only the record at its highest offset**; older same-key records are dropped. **Tombstones** (null value) are kept for `delete.retention.ms` then removed, so consumers can observe deletes.
- `min.compaction.lag.ms` keeps recent records uncompacted (so consumers near the tail see every update); `max.compaction.lag.ms` forces eventual compaction even if a segment isn't full.
- The **active segment is never compacted**; only closed segments are.
- Result: a compacted topic always retains the latest value per key — a perfect **changelog / state** representation. This is how Kafka Streams stores state and how `__consumer_offsets` works.

### 7.4 The `__consumer_offsets` (and `__transaction_state`) internal topics

- `__consumer_offsets`: a **compacted** topic (default 50 partitions) keyed by `(group, topic, partition)`; value is the committed offset + metadata. The group's coordinator broker (determined by hashing `group.id` to a partition) owns the group's state. Compaction keeps the latest committed offset per key.
- `__transaction_state`: tracks transaction status per `transactional.id` for EOS, owned by a **transaction coordinator**.

### 7.5 Transactions, LSO, and read_committed

- A **transaction coordinator** (a broker) manages each `transactional.id`, assigning a PID/epoch and writing state to `__transaction_state`.
- During a transaction, the producer writes records to partitions and registers those partitions with the coordinator. On `commitTransaction`, the coordinator writes **commit markers** (control batches) into each involved partition; on abort, **abort markers**.
- **Last Stable Offset (LSO):** the offset before the earliest still-open (uncommitted) transaction. A `read_committed` consumer can only read up to the **LSO**, never into an in-flight transaction. It filters out aborted records using the abort markers. `read_uncommitted` reads up to HW regardless. This is how Kafka delivers atomic, all-or-nothing visibility of multi-partition writes.
- **Fencing:** `initTransactions()` bumps the producer epoch, so a "zombie" old instance with the same `transactional.id` is rejected — preventing duplicate output from a hung-then-resumed process.

### 7.6 Leader epochs and truncation correctness

Before leader epochs, a follower that had extra (uncommitted) data after an unclean failover could end up with a divergent log. The **leader-epoch-checkpoint** maps each `(epoch, startOffset)`. After a leader change, a follower asks the new leader for the **end offset of its last common epoch** and truncates anything beyond it, guaranteeing logs converge to a consistent prefix. This fixed subtle data-divergence and log-truncation bugs present in older versions (pre-0.11/0.10.x).

### 7.7 Rebalancing protocols (eager vs cooperative)

- **Eager (legacy):** on any membership change, *all* consumers drop *all* partitions ("stop the world"), then reassign. Causes a processing pause proportional to the largest member.
- **Cooperative sticky (default 3.0+):** only the partitions that actually need to move are revoked; the rest keep processing. **Incremental** rebalancing → far less disruption. Use `CooperativeStickyAssignor`.
- **Static membership (`group.instance.id`):** gives each consumer a stable identity so a brief restart (within `session.timeout.ms`) does **not** trigger a rebalance — great for rolling restarts/k8s.

### 7.8 KRaft internals

- Controllers form a **Raft quorum** (typically 3 or 5). The active controller is the Raft leader; metadata changes are appended to the metadata log and replicated; brokers **replicate** the metadata log and apply it to a local cache.
- Metadata is itself a compacted Kafka log (`__cluster_metadata`), so the same log mechanics apply to cluster state.
- Failover is fast because a standby controller already has the full metadata log in memory — no scanning ZooKeeper. Supports vastly more partitions.

### 7.9 Rack-aware / follower fetching (KIP-392)

By default consumers read from the **leader**, which may be cross-AZ (costly). With `client.rack` set and broker `replica.selector.class=RackAwareReplicaSelector`, a consumer can fetch from an **in-sync follower in its own rack/AZ**, cutting cross-AZ network cost. Followers still only serve data ≤ HW, so consistency is preserved.

### 7.10 Tiered storage (KIP-405)

Offloads old, closed segments to remote object storage (S3/GCS/Azure Blob), keeping only recent segments on local disk. Lets you set very long (or infinite) retention cheaply and shrinks local disk needs and rebalance times (less local data to move). Recent data served at full speed from local disk + page cache; historical reads transparently fetched from remote. GA timing varies by feature/version — flag as version-specific.

### 7.11 Tuning knobs worth knowing

- `replica.fetch.max.bytes`, `replica.fetch.response.max.bytes` — follower fetch sizing; raise for high-throughput partitions.
- `num.replica.fetchers` — parallelism of replication per broker; raise on high-RF, high-throughput clusters.
- `queued.max.requests`, `socket.request.max.bytes` — request queue depth and max request size.
- `log.cleaner.io.max.bytes.per.second` — throttle compaction I/O so it doesn't starve serving.
- `compression.type=producer` on the broker avoids broker-side recompression (preserves zero-copy).
- `message.timestamp.type` = `CreateTime` vs `LogAppendTime` — affects retention and time-based seeks.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Log (Kafka) vs traditional queue (RabbitMQ/ActiveMQ) vs cloud queue (SQS)

| Dimension | Kafka (log) | RabbitMQ (broker queue) | AWS SQS |
|---|---|---|---|
| Storage model | Append-only log, retained | Queue, deleted on ack | Queue, deleted on ack |
| Replay | Yes — re-read from any offset | No (once acked, gone) | No (limited visibility window) |
| Multiple independent consumers of same data | Yes (consumer groups) | Via fanout exchanges (copies) | Via SNS fanout |
| Ordering | Per-partition total order | Per-queue (no parallel order) | FIFO queues per message-group |
| Throughput | Very high (100k–millions/s) | High but lower; per-msg overhead | Managed, scales but per-msg pricing |
| Per-message ack / redelivery / DLQ | Coarse (offset-based) | Fine-grained, native | Native (visibility timeout, DLQ) |
| Routing complexity | Minimal (topic/partition) | Rich (exchanges, bindings, routing keys) | Minimal |
| Priorities | No | Yes | No (limited) |
| Best for | Event streaming, log/CDC, replay, analytics | Task queues, RPC, complex routing | Simple decoupling, serverless, no-ops |

### 8.2 Kafka vs Pulsar vs Kinesis vs Pub/Sub

| | Kafka | Apache Pulsar | AWS Kinesis | GCP Pub/Sub |
|---|---|---|---|---|
| Model | Partitioned log | Segmented log (BookKeeper) | Sharded stream | Topic/subscription |
| Storage/compute | Coupled (broker = storage) | Decoupled (broker + BookKeeper) | Managed | Managed |
| Ordering | Per-partition | Per-partition (+ key-shared) | Per-shard | Per-key (ordered keys) |
| Replay | Yes | Yes | 24h–365d | 7-day retention (seek) |
| Multi-tenancy | Add-on (quotas/ACLs) | First-class (tenants/namespaces) | Account-level | Project-level |
| Ops | Self-manage or managed (MSK/Confluent) | More moving parts | Fully managed | Fully managed |

### 8.3 Decision rules

**Use Kafka when:**
- You need durable, replayable event streams consumed by many independent systems.
- You need very high throughput with horizontal scaling.
- You need per-key ordering and stream processing (Streams/Flink).
- You're doing CDC, event sourcing, log aggregation, metrics pipelines.

**Avoid / reconsider Kafka when:**
- You need per-message ack with arbitrary redelivery and complex routing (→ RabbitMQ).
- You need priority queues or delayed/scheduled messages natively (→ RabbitMQ/SQS + features).
- You have tiny scale and want zero ops (→ SQS/Pub/Sub).
- You need request/response (→ RPC, not a broker).
- You need random key lookups / a primary datastore (→ a database; pair Kafka with it).

**Durability tier choice:**

| Need | Config |
|---|---|
| Max throughput, lossy OK | `acks=0`, RF can be lower |
| Balanced | `acks=1`, RF=3 |
| No data loss on 1-broker failure | `acks=all`, RF=3, `min.insync.replicas=2`, `unclean.leader.election=false` |
| Exactly-once stream processing | above + transactions + `read_committed` |

---

## 9. Failure modes & debugging

### 9.1 Consumer lag climbing

- **Symptom:** `kafka-consumer-groups.sh --describe --group g` shows growing `LAG`.
- **Causes:** consumers too few (≤ partitions), slow processing, frequent rebalances, downstream backpressure, a hot partition (skewed key).
- **Diagnose:** check per-partition lag (is it all one partition? → key skew). Check `max.poll.interval.ms` overruns (rebalance logs). Check downstream latency.
- **Fix:** add consumers (up to partition count), increase partitions (mind keyed ordering), speed up processing, fix key distribution, raise `max.poll.records`/fetch sizes.

### 9.2 Under-replicated / offline partitions

- **Symptom:** JMX `UnderReplicatedPartitions > 0` or `OfflinePartitionsCount > 0`; `kafka-topics.sh --describe` shows ISR < replicas.
- **Causes:** a broker down/slow, disk full, network issues, GC pauses shrinking ISR, follower can't keep up.
- **Diagnose:** `kafka-topics.sh --describe --under-replicated-partitions`; check broker logs, disk space (`kafka-log-dirs.sh`), GC logs, `ISRShrinksPerSec`.
- **Fix:** restore/replace the broker, free disk, tune `num.replica.fetchers`, fix GC. If a leader is offline and ISR empty, decide on unclean election (data-loss tradeoff).

### 9.3 "NotEnoughReplicas" / producers blocked

- **Symptom:** producers get `NotEnoughReplicasException` / `NOT_ENOUGH_REPLICAS_AFTER_APPEND` with `acks=all`.
- **Cause:** ISR fell below `min.insync.replicas`. This is by design — Kafka refuses to accept writes that aren't durable enough.
- **Fix:** restore the lagging/dead replica so ISR recovers; don't "fix" it by lowering `min.insync.replicas` (that trades away durability).

### 9.4 Duplicate or lost messages

- **Duplicates:** at-least-once + non-idempotent processing, or producer retries without idempotence, or rebalance-induced reprocessing. → enable idempotence, make processing idempotent, commit after processing.
- **Loss:** `acks=0/1` with broker failure, or unclean leader election, or committing offsets before processing. → `acks=all` + RF3 + min.isr=2, disable unclean election, commit after success.

### 9.5 Rebalance storms

- **Symptom:** group constantly rebalancing; throughput craters.
- **Causes:** `max.poll.interval.ms` exceeded (slow processing), `session.timeout.ms` too low vs GC/network jitter, consumers restarting (no static membership), scaling churn.
- **Fix:** use `CooperativeStickyAssignor` + `group.instance.id` (static membership), shrink work per poll, raise `max.poll.interval.ms`, fix flapping consumers.

### 9.6 Disk full

- **Symptom:** broker crashes/IOExceptions; partitions go offline.
- **Cause:** retention too long for disk; sudden traffic spike; a stuck compaction.
- **Fix:** add disk, lower `retention.ms`/`retention.bytes` (takes effect at next segment roll/retention check), enable tiered storage. Prevent with disk alerts at 70–80%.

### 9.7 Hot partition / key skew

- **Symptom:** one partition's lag/throughput dwarfs others.
- **Cause:** a dominant key (e.g., one huge tenant) all hashing to one partition.
- **Fix:** composite keys (tenant+bucket), custom partitioner isolating hot tenants, or rethink keying. Trade ordering scope for balance.

### 9.8 Real-world incident patterns

- **The "page cache eviction" stall:** running a non-Kafka batch job on broker hosts evicts hot Kafka data from page cache; suddenly consumers hit disk and latency spikes. Lesson: isolate brokers.
- **The "TLS killed our CPU" surprise:** enabling TLS lost `sendfile` zero-copy; broker CPU doubled. Lesson: budget CPU for TLS or use kTLS.
- **The "added partitions, broke ordering" outage:** scaling a keyed topic remapped keys to new partitions; per-key ordering broke mid-stream for downstream stateful consumers. Lesson: pre-size keyed topics; if you must grow, plan a migration.
- **The "min.isr misconfig" data loss:** RF=3 but `min.insync.replicas=1` and `acks=1`; a leader crashed right after ack before replication → silent loss. Lesson: `acks=all` + `min.insync.replicas=2`.

### 9.9 Debug toolkit quick reference

```bash
# Lag and group state
kafka-consumer-groups.sh --bootstrap-server b:9092 --describe --group g1
# Under-replicated partitions
kafka-topics.sh --bootstrap-server b:9092 --describe --under-replicated-partitions
# Earliest/latest offsets (is data even there? did it expire?)
kafka-get-offsets.sh --bootstrap-server b:9092 --topic t --time -2   # earliest
kafka-get-offsets.sh --bootstrap-server b:9092 --topic t --time -1   # latest
# On-disk sizes per broker
kafka-log-dirs.sh --bootstrap-server b:9092 --describe --broker-list 1,2,3
# Decode a segment to verify contents / find corruption
kafka-dump-log.sh --files /data/t-0/00000000000000000000.log --print-data-log
# KRaft quorum health
kafka-metadata-quorum.sh --bootstrap-server b:9092 describe --status
# Reset a group to reprocess (CAUTION)
kafka-consumer-groups.sh --bootstrap-server b:9092 --group g1 \
  --reset-offsets --to-earliest --topic t --execute
```

---

## 10. Interview drill

**Q1. What is the fundamental difference between a Kafka topic and a traditional message queue?**
*Model answer:* A queue is destructive on read — a consumer removes a message, and ordering/parallel-consumption tradeoffs are tight. A Kafka topic is a **partitioned, append-only commit log**; consuming is non-destructive (a consumer just advances an offset cursor), so the same data can be replayed and read independently by many consumer groups. Retention is policy-based (time/size/compaction), not consumption-based.
- *Probe: Why does this enable replay?* Because records persist until retention; a consumer can `seek` to any offset/timestamp and re-read. Offsets are stable identifiers per partition.
- *Probe: What's the cost?* No per-message ack/redelivery semantics, no priorities, coarser failure handling — you trade queue-style fine control for throughput + replay.

**Q2. Walk me through what happens on disk when a producer sends a record with `acks=all`.**
*Model answer:* Producer serializes, picks partition, batches; the Sender sends a ProduceRequest to the partition **leader**; leader validates CRC, **assigns offsets**, appends the batch to the active segment's `.log` (into page cache, no fsync), updates sparse indexes; **ISR followers fetch** and append; once all ISR replicate up to the offset, the **high-watermark** advances; the leader then **acks**. Consumers can read only up to the HW.
- *Probe: Why no fsync per write?* Durability comes from replication across machines (RF + ISR), not from one disk's fsync; this keeps writes fast. fsync is the OS's job on its own schedule.
- *Probe: What advances the high-watermark exactly?* min of ISR members' log-end offsets.

**Q3. Explain zero-copy and why TLS can negate it.**
*Model answer:* On fetch, the broker uses `sendfile`/`FileChannel.transferTo` to move bytes straight from page cache to the socket, skipping user-space copies and CPU work. TLS requires encrypting bytes in user space (unless kernel-TLS), so the broker must pull data into user space, breaking the zero-copy path and raising CPU.
- *Probe: What else breaks zero-copy?* Message-format conversion for old clients, and broker-side recompression — anything that makes the broker touch the bytes.
- *Probe: Why is page cache central here?* `sendfile` serves from page cache; if data isn't cached, it's read from disk first (still sequential, but slower than RAM).

**Q4. What ordering guarantees does Kafka provide, and how do you preserve order for an entity?**
*Model answer:* Total order **within a partition only**; **no order across partitions**. To order all events for an entity, route them to one partition by using a consistent **key** (Kafka hashes the key). Also enable idempotence so retries don't reorder.
- *Probe: What breaks ordering?* `max.in.flight > 1` without idempotence on retry; adding partitions (remaps keys); spreading a key across partitions.
- *Probe: Cost of strict ordering?* It serializes that key's processing to one partition/one consumer — limits parallelism.

**Q5. How do partitions, consumers, and consumer groups interact for scaling?**
*Model answer:* Within one group, each partition is consumed by exactly one member, so parallelism is capped at the partition count; extra consumers idle. Separate groups each get the full stream independently. Add partitions (and consumers) to scale a single group's throughput.
- *Probe: What happens when a consumer dies?* A rebalance reassigns its partitions to surviving members; with cooperative-sticky, only affected partitions move.
- *Probe: How to scale beyond partition count?* Increase partitions (mind keyed ordering) — you can't exceed it within one group otherwise.

**Q6. Explain segments, indexes, and how the broker finds offset N.**
*Model answer:* A partition log is split into **segments** (`.log` + sparse `.index` + `.timeindex`), named by base offset. To find N: binary-search segments by base offset, binary-search that segment's `.index` for the nearest indexed offset ≤ N, seek to that byte position, scan forward to N. Sparse indexing (~every 4 KB) keeps indexes small and mmap-able.
- *Probe: Why segment at all?* Retention/compaction operate per-segment; deleting old data is an O(1) file unlink.
- *Probe: What's the active segment?* The one being appended; never deleted/compacted while active.

**Q7. How does Kafka achieve durability without losing availability, and what's the key config combo?**
*Model answer:* RF=3, `min.insync.replicas=2`, `acks=all`, `unclean.leader.election=false`. This tolerates one broker loss with zero data loss and no write stall (2 of 3 ISR ≥ min 2). Lose two and writes correctly pause rather than risk loss.
- *Probe: What does min.insync.replicas actually do?* Rejects `acks=all` writes if ISR < that number — refuses insufficiently-durable writes.
- *Probe: What's unclean leader election?* Promoting an out-of-sync replica when no ISR member is left — regains availability but loses data; off by default (chooses consistency).

**Q8. (Senior signal) When would you NOT use Kafka, and what would you use instead — justify.**
*Model answer:* For fine-grained task queues with per-message ack/redelivery, complex routing, priorities, or delayed delivery → RabbitMQ. For tiny scale with zero ops → SQS/Pub/Sub. For request/response → RPC. For random key lookups / source of truth → a database. Kafka's strengths (replay, throughput, fan-out, ordering-by-key, stream processing) don't help these and its weaknesses (coarse acks, no priorities, ops weight) hurt.
- *Probe: Could you bolt task-queue semantics onto Kafka?* You can approximate (per-key partitions, DLQ topics, retry topics) but you fight the model; native brokers do it better.
- *Probe: When is "Kafka as a database" defensible?* Compacted topics as changelogs/state for stream processors — but pair with a real query store for lookups.

**Q9. (Senior signal) You see rising consumer lag in production. Walk me through your triage and the tradeoffs at each fork.**
*Model answer:* First, is it one partition or all? One → key skew (fix keying/partitioner, trading ordering scope for balance). All → under-provisioned consumers or slow processing. Check `max.poll.interval.ms` overruns and rebalance frequency (cooperative + static membership to reduce churn). Check downstream backpressure. Levers: add consumers (capped by partitions), add partitions (breaks keyed ordering — migration cost), raise fetch sizes/`max.poll.records` (more memory, longer poll cycles), optimize processing (best but slowest to ship). Each lever trades effort/risk vs immediacy.
- *Probe: Why not just always add partitions?* Cost: more files/memory/metadata, slower failover, broken keyed ordering, irreversible (can't easily shrink).
- *Probe: How do you bound it operationally?* Alert on lag *rate* and absolute lag vs SLA; autoscale consumers to partition count.

**Q10. (Senior signal) Design the topic/partitioning/durability for a payments event pipeline that must never lose or reorder a customer's events and must be replayable for audit for 1 year.**
*Model answer:* Key by `customerId` (or accountId) for per-customer ordering. Partition count sized to peak throughput with headroom but fixed up front (adding later breaks keyed ordering) — e.g., enough partitions that each handles a safe MB/s, spread across ≥3 brokers/AZs. RF=3, `min.insync.replicas=2`, `acks=all`, `unclean.leader.election=false`, idempotent producer, transactions if multi-topic atomicity is needed. Retention: 1 year — use **tiered storage** to offload old segments to object storage cheaply; or `compact` if you only need latest-per-key, but audit usually needs full history so `delete` with 1y retention (or tiered). `read_committed` consumers. Monitor `UnderReplicatedPartitions`, lag, disk. Schema Registry with backward-compatible Avro/Protobuf for safe evolution.
- *Probe: Compaction or retention for audit?* Audit needs every event → time retention (tiered for cost), not compaction (which drops history).
- *Probe: How guarantee no reorder under retries?* Idempotent producer (`enable.idempotence=true`, `max.in.flight ≤ 5`) preserves per-partition order even on retry.

**Q11. What is the high-watermark, and why can't consumers read past it?**
*Model answer:* The HW is the highest offset replicated to all ISR members; it's the "committed" frontier. Consumers can't read past it because those records aren't yet durably replicated — if the leader failed, they could vanish, and Kafka must never expose data that might disappear.
- *Probe: Difference from log-end offset?* LEO is the next offset to be written (leader's tail); HW ≤ LEO; the gap is un-replicated data.
- *Probe: What's the LSO?* Last Stable Offset — for `read_committed`, the bound below which there are no open transactions; even stricter than HW.

**Q12. How did Kafka remove its ZooKeeper dependency, and why does it matter?**
*Model answer:* KRaft mode stores cluster metadata in an internal Raft-replicated Kafka log managed by a quorum of controller nodes — Kafka manages its own consensus instead of using external ZooKeeper. Benefits: one system to operate, faster controller failover (in-memory metadata), and support for far more partitions.
- *Probe: What is Raft doing here?* Getting the controller quorum to agree on an ordered metadata log despite failures (leader + majority-ack commits).
- *Probe: Migration concern?* ZK→KRaft migration is a staged process; ZK removed entirely in Kafka 4.0.

---

## 11. Glossary

- **Acks** — Producer durability setting: `0` (no wait), `1` (leader only), `all`/`-1` (all ISR).
- **Active segment** — The segment currently being appended; never deleted/compacted while active.
- **At-least-once / at-most-once / exactly-once** — Delivery semantics: possible duplicates / possible loss / neither.
- **Batch (record batch, v2)** — Framed group of records sharing a header; the unit of compression and transfer.
- **Broker** — A single Kafka server process storing partitions and serving requests.
- **CAP theorem** — Under a network partition, choose Consistency or Availability.
- **CDC (change data capture)** — Streaming a database's row changes as events (often into Kafka).
- **Cluster** — A set of brokers working together.
- **Compaction** — Cleanup policy retaining the latest value per key (changelog semantics).
- **Consensus / quorum** — Agreement among machines; a majority vote.
- **Consumer** — Client reading records and tracking offsets.
- **Consumer group** — Consumers sharing a `group.id`; partitions split among members.
- **Controller** — The broker (KRaft: quorum) coordinating metadata and leader elections.
- **CRC** — Checksum detecting corruption in batches.
- **DMA** — Hardware moving data to/from RAM without per-byte CPU work.
- **fsync** — Syscall forcing buffered data to physical disk.
- **GC (garbage collection)** — JVM memory reclamation; large heaps cause pauses.
- **High-watermark (HW)** — Highest offset replicated to all ISR; the consumer-visible frontier.
- **Idempotent producer** — Dedupes retries via PID/epoch/sequence; exactly-once append per session.
- **In-sync replica (ISR)** — Replicas caught up with the leader within `replica.lag.time.max.ms`.
- **JBOD** — Multiple independent disks listed in `log.dirs`.
- **KRaft** — Kafka's built-in Raft-based metadata management (replaces ZooKeeper).
- **Key** — Optional record field used for partition routing and compaction.
- **Leader / follower** — The replica serving clients vs replicas that only replicate.
- **Leader epoch** — Versioned leadership term enabling correct follower truncation.
- **Log-end offset (LEO)** — Next offset to be written (leader's tail).
- **Log-start offset** — Oldest retained offset.
- **Long polling** — Broker holds a fetch open until enough data or a timeout.
- **LSO (last stable offset)** — Read frontier for `read_committed` (before open transactions).
- **Memory-mapped file (mmap)** — File mapped into address space; reads/writes are memory ops.
- **min.insync.replicas** — Minimum ISR for an `acks=all` write to succeed.
- **Offset** — A record's monotonic position within a partition.
- **Page cache** — OS RAM cache of file data; Kafka's primary cache.
- **Partition** — The append-only log; the unit of ordering, parallelism, and scaling.
- **Partitioner** — Logic mapping a record to a partition.
- **Producer** — Client appending records to topics.
- **Publish/subscribe** — Pattern where publishers send to channels and subscribers receive.
- **Raft** — Consensus algorithm replicating an ordered log via a leader + majority acks.
- **Rebalance** — Reassignment of partitions among group members on membership change.
- **Replica** — A copy of a partition on another broker.
- **Replication factor (RF)** — Number of copies of each partition.
- **Retention** — Time/size policy deleting old segments.
- **Segment** — A chunk of a partition log on disk (`.log` + indexes).
- **sendfile / zero-copy** — Kernel path moving file bytes to a socket without user-space copies.
- **Sequential I/O** — Reading/writing contiguous bytes; far faster than random access.
- **Sparse index** — Index with entries every N bytes, not per record.
- **Syscall** — A call into the OS kernel.
- **Tiered storage** — Offloading old segments to remote object storage.
- **Timeindex** — Sparse timestamp→offset index per segment.
- **Tombstone** — Null-value record marking a key deleted in a compacted topic.
- **Topic** — A named logical stream; a set of partitions.
- **Transaction / transactional.id** — Atomic multi-partition writes + offset commits for EOS.
- **Unclean leader election** — Promoting an out-of-sync replica (availability over consistency).
- **ZooKeeper** — Legacy external coordination service (removed in Kafka 4.0).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

```
MODEL:  Kafka = distributed, partitioned, replicated, append-only COMMIT LOG (not a queue).
         topic -> partitions (each an ordered log) -> records (offset 0,1,2...) on disk segments.
ORDER:  Total order PER PARTITION ONLY. Same key -> same partition -> ordered. No global order.
SCALE:  Within a group, 1 partition -> 1 consumer. Parallelism capped by #partitions.
         Separate groups = independent full copies of the stream.
DURABILITY GOLDEN COMBO:  RF=3, min.insync.replicas=2, acks=all, unclean.leader.election=false
         -> survive 1 broker loss, zero data loss, no write stall.
SPEED:  (1) sequential I/O  (2) page cache as the cache (small JVM heap)  (3) zero-copy sendfile.
         TLS / format conversion / recompression DEFEAT zero-copy.
WRITE PATH: serialize -> partition -> batch(linger.ms/batch.size) -> leader append (page cache,
         no fsync) -> ISR replicate -> HW advances -> ack.
READ PATH: fetch(offset) -> binary-search segment+.index -> sendfile bytes <= HW -> client
         decompresses/deserializes -> commit offset to __consumer_offsets.
KEY DEFAULTS: segment.bytes=1GiB, segment.ms=7d, retention.ms=7d, index.interval.bytes=4KB,
         batch.size=16KB, linger.ms=0, acks=all(3.0+), enable.idempotence=true(3.0+),
         max.in.flight=5, replica.lag.time.max.ms=30s, auto.commit.interval.ms=5s,
         max.poll.records=500, max.poll.interval.ms=5min, fetch.max.bytes=50MiB.
RETENTION: delete (time/size) | compact (latest per key) | compact,delete.
INTERNAL TOPICS: __consumer_offsets (compacted), __transaction_state, __cluster_metadata (KRaft).
COORDINATION: KRaft (Raft quorum of controllers) replaces ZooKeeper (gone in 4.0).
EOS: idempotent producer + transactions + read_committed (reads up to LSO).
TOP METRICS: consumer LAG, UnderReplicatedPartitions(=0), OfflinePartitionsCount(=0),
         ActiveControllerCount(=1), ISRShrinksPerSec, disk free.
DON'T:  cross-partition ordering, add partitions to keyed topics, store blobs, auto-commit+slow
         processing, treat as a database, one giant partition, min.isr=1 with acks=1.
CLI:  kafka-topics / -consumer-groups (lag) / -configs / -dump-log / -get-offsets / -log-dirs /
      -reassign-partitions / -leader-election / -metadata-quorum.
```

### 12.2 Self-test (no answers — recall actively)

1. Trace the full lifecycle of a single record from `producer.send()` to a consumer committing its offset, naming every component it passes through and exactly when the high-watermark advances.
2. Your team adds partitions to a topic keyed by `customerId` to handle load. A downstream stateful consumer starts producing wrong results. Explain precisely why, and how you'd have avoided it.
3. Enabling TLS doubled broker CPU at the same throughput. Explain the mechanism and name two other things that cause the same effect.
4. Given RF=3, write the four config values for "survive one broker failure with zero data loss and no write outage," and explain what happens if two brokers fail.
5. Explain the difference between log-end offset, high-watermark, and last stable offset, and which one each of `read_uncommitted` and `read_committed` consumers respect.
6. Describe how the broker locates the byte position of offset 1,234,567 in a partition, including the role of the sparse index and the active segment.
7. When would you choose log compaction over time retention, and what does a tombstone do? Give a concrete topic where compaction is the right choice.
8. Explain how an idempotent producer prevents duplicates *and* preserves ordering under retries, naming the three pieces of state involved.

---

*End of chapter: Kafka & Message Brokers — Architecture & The Commit Log.*

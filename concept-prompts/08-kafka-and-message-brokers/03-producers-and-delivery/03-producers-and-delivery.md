# Kafka Producers & Delivery

> An exhaustive engineering-handbook chapter on the Kafka producer: the produce path internals, delivery guarantees, idempotence and transactions, batching and compression, ordering, partitioning, retries, tuning, and many worked Java examples.

---

## 1. Overview & where it fits

### What it is

A **Kafka producer** is the client-side component that publishes records (messages) to a **Kafka cluster**. In the Java/JVM ecosystem this is the `org.apache.kafka.clients.producer.KafkaProducer` class shipped in the `kafka-clients` artifact. You hand it a `ProducerRecord` (topic, optional partition, optional key, value, optional headers, optional timestamp), and it takes responsibility for getting that record durably stored on one or more **brokers**.

A few terms before we go further, because a newcomer needs them right away:

- **Broker:** a single Kafka server process. A **cluster** is a set of brokers that cooperate. Each broker stores some of the data and serves client requests.
- **Topic:** a named stream of records, like a table name or a logical channel. Topics are split into **partitions**.
- **Partition:** an ordered, append-only log. A topic with 12 partitions is 12 independent ordered logs. Ordering in Kafka is *per partition*, never across a whole topic. Partitions are the unit of parallelism and the unit of ordering.
- **Record / message:** a key-value pair plus metadata (headers, timestamp, offset). The **key** is optional and is mainly used to decide which partition a record lands in. The **value** is the payload.
- **Offset:** the position of a record within a partition. The first record is offset 0, the next is 1, and so on. Offsets are per partition and monotonically increasing.
- **Leader / follower replica:** each partition has one **leader** replica and zero or more **follower** replicas on other brokers. All produce and consume traffic for a partition goes to its leader. Followers copy (replicate) the leader's log so the data survives broker failure.

So the producer's job, stated precisely: take an application-level object, turn it into bytes, decide which partition it belongs to, batch it efficiently with other records, ship it to the right broker (the partition leader), and confirm — according to the **durability guarantee you configured** — that it was stored.

### The problem it solves

Naively, "send a message to a server" sounds trivial: open a socket, write bytes, done. Kafka producers exist because doing that *correctly and at high throughput* is hard:

1. **Throughput.** Sending one record per network round-trip is catastrophically slow. A round-trip to a broker might be 0.5–2 ms; at one record per RTT you cap out around a few thousand records/second. Real systems need hundreds of thousands to millions per second. The producer solves this by **batching** many records into one request.
2. **Durability vs. latency tradeoff.** Sometimes you want "fire and forget" (logs, metrics) and sometimes you want "do not lose this under any single failure" (payments). The producer exposes this as the `acks` setting.
3. **Partitioning / routing.** The producer must know the cluster's **metadata** (which broker leads which partition) and route each record to the correct broker, refreshing that map as leadership moves.
4. **Reliability under failure.** Brokers fail, leaders move, networks blip. The producer must retry transparently without (a) dropping records, (b) duplicating records, or (c) reordering them — and it must do all three at once if you ask for it (the **idempotent producer**).
5. **Backpressure / memory safety.** If the cluster is slower than the application, the producer must not OOM. It bounds memory with a buffer and blocks or fails when full.

### When you reach for it

You use a Kafka producer any time your service is the *source* of events into Kafka: emitting domain events (OrderPlaced, PaymentCaptured), shipping logs/metrics, change-data-capture, command/request streams, materializing data into compacted topics, or as the write side of an event-sourced or CQRS system. If your code calls `send()`, you are using a producer.

### One-paragraph mental model

> A Kafka producer is a **two-stage pipeline running inside your JVM**. Stage one (your application thread, inside `send()`) serializes the record, picks a partition, and appends the record's bytes to an in-memory batch in the **RecordAccumulator** — then returns a `Future` immediately. Stage two is a single background **Sender (I/O) thread** that drains ready batches, groups them per broker into produce requests, sends them over the network respecting `max.in.flight.requests.per.connection`, waits for acknowledgements according to `acks`, retries on retriable errors, and finally completes the futures and fires your callbacks. Everything else — idempotence, compression, ordering guarantees, durability — is a knob on one of those two stages.

Hold onto that two-stage picture; the entire rest of this document is just zooming into the two stages.

---

## 2. Foundations from first principles

We build the model from zero. If you already know Kafka, skim — but the precise definitions here are reused everywhere below.

### 2.1 The log, the partition, the offset

A Kafka partition is an **append-only commit log**: an ordered sequence of records where you only ever add to the end. Think of a notebook you only write into, never erase. Each entry gets the next line number — that line number is the **offset**. Because you only append, the log is cheap to write (sequential disk I/O is fast, often 100s of MB/s even on spinning disks) and trivially ordered.

The producer always appends to the **end (tail)** of a partition log. It never updates in place. "Deleting" old data happens via **retention** (time- or size-based deletion of old segments) or **log compaction** (keeping only the latest value per key) — both broker-side concerns, not the producer's.

### 2.2 Replication, leaders, and the ISR

Each partition is replicated `replication.factor` times (commonly 3). Of those replicas, one is the **leader** and the rest are **followers**.

- The leader handles all reads and writes for that partition.
- Followers continuously fetch from the leader to stay caught up.

The set of replicas that are "sufficiently caught up" is the **ISR — In-Sync Replica set**. A follower is in the ISR if it has fetched up to (roughly) the leader's latest offset within `replica.lag.time.max.ms` (default 30000 ms = 30 s). If a follower falls behind or dies, the leader removes it from the ISR. The ISR matters enormously for the producer because of the next concept.

### 2.3 The high watermark and "committed"

A record is **committed** once it has been replicated to all replicas currently in the ISR. The **high watermark (HW)** is the offset up to which all ISR members have the data. Consumers can only read up to the high watermark — they never see uncommitted records. This is the foundation of Kafka's durability story: "committed" means "survives as long as one ISR replica survives."

This is why `acks=all` (more on it soon) is meaningful: it ties the producer's success acknowledgement to the record being *committed*, i.e., present on the full ISR — not just on the leader's disk.

### 2.4 What a record actually is

A `ProducerRecord<K,V>` carries:

| Field | Required? | Purpose |
|---|---|---|
| `topic` | Yes | Destination topic name. |
| `partition` | No | If set, overrides the partitioner — record goes exactly there. |
| `key` | No | Used by the default partitioner to pick a partition (hash of key). Also used for log compaction. |
| `value` | No (can be null) | The payload. A `null` value with a key is a **tombstone** in compacted topics (signals deletion). |
| `timestamp` | No | Event time; if null, the producer stamps "now" (depends on broker `message.timestamp.type`). |
| `headers` | No | Arbitrary `byte[]` key/value metadata (tracing IDs, schema IDs, content-type). |

### 2.5 Serialization

Kafka brokers store and move **bytes**. They do not understand your Java objects. So the producer must convert key and value to `byte[]` using a **Serializer**.

- A `Serializer<T>` has the method `byte[] serialize(String topic, T data)`.
- You configure `key.serializer` and `value.serializer` (both mandatory; the producer will not start without them).
- Built-in serializers: `StringSerializer`, `ByteArraySerializer`, `IntegerSerializer`, `LongSerializer`, `DoubleSerializer`, `ByteBufferSerializer`, `UUIDSerializer`, `VoidSerializer`.
- For structured data you typically use Avro/Protobuf/JSON Schema serializers from Confluent's Schema Registry, or a hand-rolled JSON serializer (e.g., wrapping Jackson).

Serialization is the first thing that happens inside `send()` and it happens **on your application thread**, not the Sender thread. A slow or throwing serializer directly slows or fails your `send()` call.

### 2.6 The acknowledgement contract: `acks`

`acks` controls *when the leader replies "got it"* to the producer. It is the single most important durability knob.

- **`acks=0`** — fire and forget. The producer does not wait for any acknowledgement; it considers the record "sent" the moment it writes the bytes to the socket. Highest throughput, lowest latency, **no durability guarantee**. If the leader is down or the request is lost, the record vanishes silently. Retries are effectively meaningless (there's no error to react to in the normal path).
- **`acks=1`** — leader acknowledgement. The leader writes the record to its own log (page cache, not necessarily fsync'd to disk) and replies. **Risk:** if the leader crashes *after* acking but *before* a follower replicated the record, and a follower becomes the new leader, the record is lost. Good middle ground for many workloads.
- **`acks=all`** (alias `acks=-1`) — full ISR acknowledgement. The leader waits until all in-sync replicas have replicated the record (it is *committed*) before replying. Strongest durability. Combined with `min.insync.replicas`, this is the configuration for "don't lose my data."

> **Critical nuance:** `acks=all` alone is not enough. If the ISR has shrunk to just the leader (all followers died), then "all ISR" = "just the leader," and you're effectively back to `acks=1` durability. The broker-side topic config **`min.insync.replicas`** (default 1, but you should set it to 2 for RF=3) fixes this: if fewer than `min.insync.replicas` replicas are in sync, the leader **rejects the produce** with `NotEnoughReplicasException` rather than accepting data it can't safely commit. The durable trio is: **`acks=all` + `replication.factor=3` + `min.insync.replicas=2`.** That tolerates one broker failure with zero data loss and still accepts writes.

### 2.7 Delivery semantics: at-most-once, at-least-once, exactly-once

These three terms describe what can happen to a record under failure and retry:

- **At-most-once:** each record is delivered zero or one times — never duplicated, but may be lost. You get this with `acks=0` or by disabling retries.
- **At-least-once:** each record is delivered one or more times — never lost (given proper acks), but may be duplicated. This is the default for a retrying producer with `acks≥1` and *non-idempotent* behavior. Duplicates arise when the producer sends a batch, the broker writes it, the ack is lost on the way back, and the producer retries the same batch.
- **Exactly-once:** each record is delivered exactly one time — no loss, no duplication. The **idempotent producer** gives you exactly-once *for the produce path* (no broker-side duplicates from retries). The **transactional producer** extends this to atomic writes across partitions and to the consume-process-produce loop.

### 2.8 The two threads (the most important foundation)

The single most clarifying fact about the producer:

1. **Your application thread(s)** call `send()`. Inside `send()`, the record is serialized, a partition is chosen, and the record's bytes are appended to an in-memory batch buffer (the **RecordAccumulator**). Then `send()` returns a `Future<RecordMetadata>` **immediately** — it does *not* wait for the network. (Exception: it can block if the buffer is full; see §3.)
2. **One background thread** — the **Sender thread**, sometimes called the I/O thread, named `kafka-producer-network-thread | <client.id>` — owns all network I/O. It pulls ready batches out of the accumulator, builds produce requests, manages connections via Java NIO, awaits acks, retries, and completes the futures / invokes callbacks.

So `send()` is asynchronous and fast; the actual delivery is the Sender thread's job. This is why callbacks fire on the Sender thread (never block them) and why `flush()` exists (to wait for the Sender to drain everything).

---

## 3. How it works internally

This is the heart of the chapter. We trace one record from `producer.send(record, callback)` all the way to a completed callback, naming every component.

### 3.1 The components

```
 Application thread(s)                         Sender (I/O) thread
 ┌───────────────────────┐                     ┌──────────────────────────┐
 │ send()                │                     │  run() loop               │
 │  1. wait for metadata │                     │   - drain ready batches   │
 │  2. serialize K,V     │   RecordAccumulator │   - build ProduceRequests │
 │  3. partition         │  ┌────────────────┐ │   - poll NetworkClient    │
 │  4. append to batch ──┼─▶│ per-TopicPartn │ │   - handle responses      │
 │  5. return Future     │  │  Deque<Batch>  │◀┼── drain                   │
 └───────────────────────┘  └────────────────┘ │   - retry / complete      │
                              BufferPool         └──────────────────────────┘
                              (bounded memory)            │
                                                          ▼
                                                   NetworkClient ──▶ brokers
```

Key objects:

- **`Metadata`** — the producer's cached view of the cluster: topics, partitions, which broker leads each partition, broker addresses. Refreshed lazily and periodically (`metadata.max.age.ms`, default 300000 ms = 5 min) and on errors.
- **`Serializer`** — converts key/value to bytes (on the app thread).
- **`Partitioner`** — chooses the partition (on the app thread).
- **`RecordAccumulator`** — the in-memory staging area. For each `TopicPartition` it holds a `Deque<ProducerBatch>` (a double-ended queue of batches). New records append to the last batch in the deque (or create a new one).
- **`ProducerBatch`** — a `MemoryRecords` buffer (default up to `batch.size` bytes) holding many serialized records destined for the same partition. The unit of network transfer and of retry.
- **`BufferPool`** — manages the total memory (`buffer.memory`, default 33554432 = 32 MiB). Hands out `batch.size`-sized buffers and reclaims them. Blocks `send()` when memory is exhausted.
- **`Sender`** — the background thread's logic (a `Runnable`).
- **`NetworkClient`** — non-blocking network layer (Java NIO `Selector`) managing connections to brokers and in-flight requests.
- **`TransactionManager`** — present when idempotence or transactions are enabled; tracks the Producer ID (PID), epoch, and per-partition sequence numbers.

### 3.2 Step-by-step: the app-thread side of `send()`

When you call `producer.send(record, callback)`:

1. **Wait for metadata.** The producer needs to know the partitions of the target topic and where their leaders are. `waitOnMetadata(topic, partition, maxBlockTimeMs)` blocks up to `max.block.ms` (default 60000 ms = 60 s) until metadata for the topic is available, triggering a metadata fetch if needed. If it can't get metadata in time → `TimeoutException`. (This is one of two places `send()` can block.)
2. **Serialize the key.** `keySerializer.serialize(topic, headers, key)`. Runs on your thread. Exceptions propagate out of `send()` synchronously (they are *not* delivered to the callback) — e.g., a `SerializationException`.
3. **Serialize the value.** Same, with `value.serializer`.
4. **Compute the partition.** If `record.partition()` is set, use it. Otherwise call the **partitioner** (see §3.4). For key-based records this is `hash(key) % numPartitions`.
5. **Estimate serialized size** and validate against `max.request.size` (default 1048576 = 1 MiB). Oversize → `RecordTooLargeException` synchronously.
6. **Append to the accumulator.** `accumulator.append(...)`:
   - Find/create the `Deque<ProducerBatch>` for this `TopicPartition`.
   - Try to append the serialized record to the **last** batch in the deque if it has room.
   - If no batch or no room, **allocate** a new buffer from the `BufferPool` (size = `max(batch.size, record size)`), create a new `ProducerBatch`, append the record, and enqueue it.
   - If the pool has no free memory, **block up to `max.block.ms`** waiting for memory to free up (the second place `send()` can block). Timeout → `TimeoutException` ("Failed to allocate memory within ... ms").
7. **Wake the Sender if the batch is ready.** If appending filled a batch or created a new one, signal the Sender thread (so it doesn't wait the full `linger.ms`).
8. **Return the `Future<RecordMetadata>`.** The append returns a `FutureRecordMetadata` tied to that record's slot in the batch. `send()` returns immediately.

So by the time `send()` returns, **nothing has been sent over the network**. The record is sitting in memory in a batch.

### 3.3 The RecordAccumulator and batching lifecycle

A `ProducerBatch` is "ready" to be sent when **any** of these is true:

- It is **full** (reached `batch.size`, default 16384 = 16 KiB).
- It has waited at least **`linger.ms`** (default 0) since creation. With `linger.ms=0`, a batch is sent as soon as the Sender can get to it (which still batches whatever accumulated while the previous request was in flight).
- The producer is **flushing** (`flush()` called) or **closing**.
- There is **memory pressure** or the deque has more than one batch waiting.
- A retry backoff has elapsed for a batch that needs retrying.

`linger.ms` is the key throughput/latency knob: it deliberately *waits* a few milliseconds to let more records accumulate into a batch, trading a little latency for much higher throughput and better compression. `batch.size` caps how big a single batch buffer can grow.

> **Subtlety:** `batch.size` is a *per-partition* cap, and `buffer.memory` is the *total* cap. If you produce to many partitions, you may exhaust `buffer.memory` long before any single batch fills. Tune both together.

### 3.4 The partitioner

The partitioner decides which partition a keyless or keyed record goes to. The behavior has changed across versions — flag this carefully:

- **Keyed records:** partition = `murmur2(serializedKey) % numPartitions`. Same key → same partition → ordering preserved for that key. This is stable across all versions.
- **Keyless records, modern (Kafka 2.4+):** the **sticky partitioner**. Instead of round-robining every record (which produces tiny batches scattered across all partitions), the producer "sticks" to one partition until the current batch for it is full or `linger.ms` elapses, then switches to another partition. This dramatically improves batching and throughput while keeping load roughly even over time. Implemented via `DefaultPartitioner` (2.4–3.2) / built-in sticky logic.
- **Keyless records, Kafka 3.3+:** `partitioner.class` defaults to `null`, which enables the **built-in uniform sticky partitioner** that *also* prefers partitions on less-loaded brokers (it considers `partitioner.availability.timeout.ms`). The old explicit `DefaultPartitioner`/`UniformStickyPartitioner` classes were deprecated (KIP-794).
- **Legacy / pre-2.4:** plain round-robin for keyless records.

You can also write a **custom partitioner** implementing `org.apache.kafka.clients.producer.Partitioner` (e.g., to route by tenant ID, to keep "hot" keys spread out, or to implement geo-affinity).

### 3.5 Step-by-step: the Sender thread loop

The Sender thread runs `run()` → `runOnce()` repeatedly until shutdown. Each iteration:

1. **Compute ready nodes.** `accumulator.ready(metadata, now)` returns the set of broker nodes that have at least one ready batch, plus the earliest next-ready time (used as the poll timeout).
2. **Ensure connectivity / metadata.** If a leader is unknown for some ready partition, request a metadata update. Skip nodes that aren't connected/ready.
3. **Drain batches per node.** `accumulator.drain(metadata, readyNodes, maxRequestSize, now)` pulls batches out, grouped by destination broker, up to `max.request.size` per node. Crucially, when idempotence/ordering constraints apply, drain respects `max.in.flight.requests.per.connection` and per-partition in-flight limits so it doesn't send out-of-order.
4. **Build ProduceRequests.** One request per broker, containing batches for all that broker's partitions. The request carries `acks` and `request.timeout.ms`.
5. **Compress** (if `compression.type != none`). Each batch's records are compressed together as a unit — this is why batching improves compression ratio. Compression actually happens earlier, when records are appended/the batch is closed, but conceptually the compressed batch is what's shipped.
6. **Send via NetworkClient.** Add to in-flight requests for that connection. Respects `max.in.flight.requests.per.connection` (default 5).
7. **Poll.** `client.poll(timeout, now)` does the actual NIO read/write: sends queued requests, reads responses, fires response handlers.
8. **Handle responses.** For each batch in a response:
   - **Success:** complete each record's future with `RecordMetadata` (partition, offset, timestamp), invoke callbacks, free the batch's buffer back to the pool, advance idempotent sequence bookkeeping.
   - **Retriable error** (e.g., `NotLeaderForPartitionException`, `LeaderNotAvailableException`, `NotEnoughReplicasException`, network disconnect, request timeout): if retries remain and `delivery.timeout.ms` not exceeded, **re-enqueue** the batch (front of the deque to preserve order) after `retry.backoff.ms`, and refresh metadata if it was a leadership error.
   - **Fatal/non-retriable error** (e.g., `RecordTooLargeException`, `SerializationException` would've failed earlier, `TopicAuthorizationException`, `InvalidProducerEpochException` in some cases): fail the batch's futures with the exception; invoke callbacks with that exception.
9. **Handle delivery timeout.** Independently, any batch whose total time since creation exceeds **`delivery.timeout.ms`** (default 120000 ms = 2 min) is expired and failed with `TimeoutException`, regardless of retries.

### 3.6 The timeout cascade (often misunderstood)

There are several timeouts; know how they nest:

| Config | Default | Scope | Meaning |
|---|---|---|---|
| `max.block.ms` | 60000 ms | `send()` and `partitionsFor()` | Max time `send()` blocks waiting for metadata or buffer memory. |
| `request.timeout.ms` | 30000 ms | per network request | Max time to wait for a broker response to a single produce request before considering it failed (and retriable). |
| `delivery.timeout.ms` | 120000 ms | per record, end-to-end | Upper bound on time from `send()` returning to success/failure, **covering all retries**. Must be ≥ `linger.ms + request.timeout.ms`. |
| `retry.backoff.ms` | 100 ms | between retries | Wait before retrying a failed batch. |
| `retry.backoff.max.ms` | 1000 ms | retry cap (3.x) | Exponential backoff cap for retries. |
| `retries` | Integer.MAX_VALUE | retry count | Max retry attempts. With `delivery.timeout.ms` governing the real bound, leaving this at max is recommended. |
| `linger.ms` | 0 ms | batching | How long a batch waits to accumulate more records. |

> **Modern guidance:** Since Kafka 2.1, you generally **leave `retries` at its huge default and control retry duration via `delivery.timeout.ms`.** A record will keep being retried until either it succeeds, hits a non-retriable error, or `delivery.timeout.ms` elapses. This makes reasoning about "how long until I give up" a single number.

### 3.7 The idempotent producer internals (PID + sequence numbers)

Enable with `enable.idempotence=true` (the **default since Kafka 3.0** when configs are compatible). It prevents the broker from writing **duplicate** records caused by producer retries, and it preserves ordering — *within a single producer session, per partition*.

How it works under the hood:

1. **PID assignment.** On first use, the producer sends an `InitProducerId` request and the cluster assigns a **Producer ID (PID)** — a unique 64-bit identifier — plus an **epoch** (a generation counter). For a plain idempotent producer (no `transactional.id`), the PID is ephemeral and tied to this producer instance's lifetime.
2. **Sequence numbers.** For each `(PID, partition)` pair the producer maintains a monotonically increasing **sequence number** starting at 0. Every record batch carries the PID, epoch, and the sequence number of its first record (and the count).
3. **Broker-side dedup.** Each partition leader remembers, per `(PID, epoch)`, the last sequence number it has written for that partition (the last 5 batches, matching `max.in.flight=5`). When a batch arrives:
   - If its first sequence = lastWritten + 1 → accept, advance.
   - If it's a **duplicate** (sequence ≤ what it already has) → the broker silently acknowledges success **without writing again**. This is the dedup: a retried batch whose ack was lost simply gets acked again, no duplicate stored.
   - If there's a **gap** (sequence > lastWritten + 1) → `OutOfOrderSequenceException`. This means a batch was lost/reordered; it's a signal something went wrong (and with `max.in.flight≤5` and proper retry handling, it shouldn't happen in normal operation).
4. **Ordering preserved with up to 5 in-flight.** Pre-idempotence, guaranteeing order required `max.in.flight.requests.per.connection=1` (otherwise a retried batch could land after a later batch). With idempotence, the broker uses sequence numbers to reject out-of-order batches and the producer re-sends in order, so you keep **ordering even with `max.in.flight.requests.per.connection` up to 5**. This is why idempotence is nearly free for throughput.

**Constraints idempotence imposes** (the producer enforces these, or refuses to start):

- `acks=all` (required; idempotence is meaningless without full durability).
- `retries > 0` (must be able to retry).
- `max.in.flight.requests.per.connection ≤ 5`.

If you explicitly set conflicting values, the producer throws a `ConfigException` at construction.

**What idempotence does NOT give you:**

- It is **per producer session**. If your producer crashes and a new one starts, it gets a new PID — the broker can't dedup across the boundary. End-to-end exactly-once across restarts needs **transactions** with a stable `transactional.id`.
- It does not dedup *application-level* duplicates (you calling `send()` twice for the same logical event). It only dedups *transport-level* retries.
- It is per partition; it says nothing about cross-partition atomicity (that's transactions).

### 3.8 The transactional producer (brief, since it's adjacent)

Transactions build on idempotence to give **atomic multi-partition writes** and **exactly-once in the consume-process-produce loop** (the core of Kafka Streams' EOS).

- Set a stable **`transactional.id`** (a user-chosen string that survives restarts). This makes the PID stable: on restart, `initTransactions()` recovers the PID and **bumps the epoch**, fencing the old (zombie) producer so its in-flight writes get rejected (`ProducerFencedException`).
- API: `initTransactions()` once at startup; then per transaction `beginTransaction()` → `send(...)` (and optionally `sendOffsetsToTransaction(...)` to commit consumer offsets atomically) → `commitTransaction()` or `abortTransaction()`.
- Brokers write transaction markers (commit/abort) into the log. Consumers with `isolation.level=read_committed` skip aborted records.
- A **transaction coordinator** (a broker role) and the internal `__transaction_state` topic track transaction state.

We'll show a code example in §5; full transaction internals are a chapter of their own, but you must know they exist and that they're the "exactly-once across the whole pipeline" answer.

---

## 4. The complete toolkit

### 4.1 Core classes and interfaces

| Type | Package | Purpose |
|---|---|---|
| `KafkaProducer<K,V>` | `org.apache.kafka.clients.producer` | The concrete producer. Thread-safe; share one instance across threads. |
| `Producer<K,V>` | same | Interface implemented by `KafkaProducer` and `MockProducer`. |
| `MockProducer<K,V>` | same | In-memory test double; records `send()`s, lets you complete futures manually. |
| `ProducerRecord<K,V>` | same | The record you send. |
| `RecordMetadata` | same | Returned on success: topic, partition, offset, timestamp, serialized sizes. |
| `Callback` | same | `onCompletion(RecordMetadata, Exception)` invoked on the Sender thread. |
| `Partitioner` | same | SPI for custom partitioning. |
| `ProducerInterceptor<K,V>` | same | SPI to intercept/modify records before send and on ack. |
| `Serializer<T>` | `org.apache.kafka.common.serialization` | Converts T → byte[]. |
| `ProducerConfig` | `org.apache.kafka.clients.producer` | Constants for all config keys. |

### 4.2 The `KafkaProducer` API surface

| Method | Returns | What it does |
|---|---|---|
| `send(ProducerRecord)` | `Future<RecordMetadata>` | Async send; future completes when acked/failed. |
| `send(ProducerRecord, Callback)` | `Future<RecordMetadata>` | Async send with callback (preferred; non-blocking). |
| `flush()` | `void` | Block until all buffered records are sent (succeeded or failed). Does not close. |
| `partitionsFor(topic)` | `List<PartitionInfo>` | Fetch partition metadata for a topic (can block up to `max.block.ms`). |
| `metrics()` | `Map<MetricName,Metric>` | Live producer metrics (also exposed via JMX). |
| `close()` | `void` | Flush then close, releasing the Sender thread, connections, buffers. |
| `close(Duration)` | `void` | Close with a timeout; force-fails leftover records after it. |
| `initTransactions()` | `void` | One-time transactional setup (gets PID, bumps epoch). |
| `beginTransaction()` | `void` | Start a transaction. |
| `send(...)` (within txn) | `Future` | Sends become part of the current transaction. |
| `sendOffsetsToTransaction(offsets, groupMetadata)` | `void` | Atomically commit consumer offsets within the txn (EOS loop). |
| `commitTransaction()` | `void` | Commit; flushes and writes commit markers. |
| `abortTransaction()` | `void` | Abort; discards the txn's records. |

> **Getting the result synchronously:** call `producer.send(record).get()`. This blocks the calling thread until ack — turning the async producer into a synchronous one. Only do this for low-throughput, must-confirm-each writes; it destroys batching/throughput otherwise.

### 4.3 Configuration reference (the ones that matter)

**Connection & identity**

| Config | Default | Notes |
|---|---|---|
| `bootstrap.servers` | (required) | `host:port` list to discover the cluster. |
| `client.id` | "" | Logical name; shows in broker logs, metrics, quotas. Set it. |
| `key.serializer` | (required) | Class for key serialization. |
| `value.serializer` | (required) | Class for value serialization. |

**Durability & ordering**

| Config | Default | Notes |
|---|---|---|
| `acks` | `all` (since 3.0; was `1`) | `0` / `1` / `all`. |
| `enable.idempotence` | `true` (since 3.0) | Requires `acks=all`, `retries>0`, `max.in.flight≤5`. |
| `max.in.flight.requests.per.connection` | 5 | Concurrent unacked requests per broker connection. >5 disables idempotence's ordering guarantee. |
| `retries` | 2147483647 | Leave high; bound by `delivery.timeout.ms`. |
| `delivery.timeout.ms` | 120000 | End-to-end per-record deadline. |
| `transactional.id` | null | Set to enable transactions (implies idempotence). |
| `transaction.timeout.ms` | 60000 | Max txn duration before broker aborts it. |

**Batching, buffering, compression**

| Config | Default | Notes |
|---|---|---|
| `batch.size` | 16384 (16 KiB) | Max bytes per partition batch buffer. |
| `linger.ms` | 0 | Wait time to fill batches. Set 5–100 for throughput. |
| `buffer.memory` | 33554432 (32 MiB) | Total producer buffer. |
| `max.block.ms` | 60000 | Max block in `send()` for metadata/memory. |
| `max.request.size` | 1048576 (1 MiB) | Max single request size; also caps single record. Must align with broker `message.max.bytes`. |
| `compression.type` | `none` | `none`/`gzip`/`snappy`/`lz4`/`zstd`. |
| `partitioner.class` | null (built-in sticky, 3.3+) | Custom partitioning. |

**Timeouts & retries**

| Config | Default | Notes |
|---|---|---|
| `request.timeout.ms` | 30000 | Per-request response wait. |
| `retry.backoff.ms` | 100 | Backoff between retries. |
| `retry.backoff.max.ms` | 1000 | Exponential backoff cap (3.x). |
| `metadata.max.age.ms` | 300000 | Force metadata refresh interval. |
| `reconnect.backoff.ms` / `reconnect.backoff.max.ms` | 50 / 1000 | Connection retry backoff. |
| `connections.max.idle.ms` | 540000 | Idle connection close. |

**Security (when not PLAINTEXT)**

| Config | Typical value | Notes |
|---|---|---|
| `security.protocol` | `SSL` / `SASL_SSL` | Transport + auth scheme. |
| `ssl.truststore.location` / `...password` | path / secret | TLS trust. |
| `ssl.keystore.location` / `...password` | path / secret | mTLS client cert. |
| `sasl.mechanism` | `PLAIN` / `SCRAM-SHA-512` / `OAUTHBEARER` / `GSSAPI` | SASL method. |
| `sasl.jaas.config` | inline JAAS | Credentials. |

### 4.4 Compression options compared

| Codec | Ratio | CPU (compress) | Speed | When to use |
|---|---|---|---|---|
| `none` | 1.0× | none | fastest | Already-compressed payloads (images), ultra-low-latency. |
| `gzip` | best ratio | high | slow | Bandwidth-constrained, CPU-rich, batch/archival. |
| `snappy` | moderate | low | fast | General throughput; legacy default choice. |
| `lz4` | moderate | low | very fast | **Best general-purpose** balance; common default. |
| `zstd` | very good ratio | tunable | fast | **Best modern choice** (Kafka 2.1+); great ratio at good speed; broker must support it. |

Compression is per-batch, so bigger batches (higher `linger.ms`/`batch.size`) compress better. Broker can be configured to keep producer compression or recompress (`compression.type` at broker/topic level).

### 4.5 CLI tools (producing & inspecting)

| Tool | Purpose | Example |
|---|---|---|
| `kafka-console-producer.sh` | Hand-produce from stdin | `kafka-console-producer.sh --bootstrap-server b:9092 --topic t --property parse.key=true --property key.separator=:` |
| `kafka-producer-perf-test.sh` | Throughput/latency benchmark | `kafka-producer-perf-test.sh --topic t --num-records 1000000 --record-size 1000 --throughput -1 --producer-props bootstrap.servers=b:9092 acks=all linger.ms=10 batch.size=65536` |
| `kafka-verifiable-producer.sh` | Produce with verifiable IDs (test ordering/dups) | `kafka-verifiable-producer.sh --topic t --max-messages 100000 --broker-list b:9092` |
| `kafka-topics.sh` | Inspect partitions/ISR | `kafka-topics.sh --bootstrap-server b:9092 --describe --topic t` |
| `kafka-get-offsets.sh` | Check end offsets per partition | `kafka-get-offsets.sh --bootstrap-server b:9092 --topic t` |
| `kafka-configs.sh` | Set `min.insync.replicas` etc. | `kafka-configs.sh --bootstrap-server b:9092 --entity-type topics --entity-name t --alter --add-config min.insync.replicas=2` |

### 4.6 Key metrics (JMX, via `producer.metrics()` or JMX MBeans)

| Metric | Meaning | Watch for |
|---|---|---|
| `record-send-rate` | records/sec sent | Throughput. |
| `record-error-rate` | failed records/sec | Should be ~0. |
| `request-latency-avg` / `-max` | broker round-trip | Spikes = broker/network issues. |
| `batch-size-avg` | avg batch bytes | Low = poor batching (raise `linger.ms`). |
| `records-per-request-avg` | records per produce req | Same. |
| `compression-rate-avg` | compressed/uncompressed | Effectiveness of compression. |
| `buffer-available-bytes` | free buffer | Near 0 = backpressure / blocking sends. |
| `bufferpool-wait-ratio` | fraction of time waiting for buffer | >0 = `send()` is blocking on memory. |
| `record-queue-time-avg` | time records sit in accumulator | High = Sender can't keep up. |
| `requests-in-flight` | unacked requests | Near `max.in.flight` × nodes = saturated. |
| `produce-throttle-time-avg` | broker quota throttling | >0 = you're being rate-limited by quotas. |

---

## 5. Code examples by use case

All examples use `kafka-clients` (3.x). Adjust versions/serializers as needed. Imports omitted for brevity except where instructive.

### 5.1 Baseline: a correctly-configured durable async producer

The everyday "I want my events to not be lost" producer.

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092,broker3:9092");
props.put(ProducerConfig.CLIENT_ID_CONFIG, "order-service-producer");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

// Durability: the durable trio (broker side: replication.factor=3, min.insync.replicas=2)
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // default in 3.x, explicit for clarity
// retries left at default (Integer.MAX_VALUE); bound by delivery.timeout.ms
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

// Throughput-friendly batching without much latency cost
props.put(ProducerConfig.LINGER_MS_CONFIG, 10);          // wait up to 10ms to fill batches
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);  // 64 KiB batches
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

try (Producer<String, String> producer = new KafkaProducer<>(props)) {
    for (Order order : orders) {
        ProducerRecord<String, String> record =
            new ProducerRecord<>("orders", order.customerId(), order.toJson());
        // Asynchronous send with a callback. The callback runs on the Sender thread —
        // keep it fast and NON-BLOCKING.
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                // Final failure after all retries / delivery.timeout — handle/alert/DLQ.
                log.error("Failed to send order {}", order.id(), exception);
            } else {
                log.debug("Sent order {} to {}-{}@{}",
                    order.id(), metadata.topic(), metadata.partition(), metadata.offset());
            }
        });
    }
    // try-with-resources close() flushes remaining records before returning.
}
```

Why each part: `acks=all` + idempotence gives no-loss, no-duplicate, ordered delivery (with the broker-side `min.insync.replicas=2`). `linger.ms=10` and the bigger `batch.size` plus `lz4` give strong throughput. The callback handles the *terminal* outcome only — retries are invisible here.

### 5.2 Synchronous, must-confirm send (low volume, high importance)

For a control command where you must know it landed before proceeding.

```java
ProducerRecord<String, String> record =
    new ProducerRecord<>("provisioning-commands", tenantId, command.toJson());
try {
    // .get() blocks this thread until the broker acks (or it ultimately fails).
    RecordMetadata md = producer.send(record).get(); // throws on failure
    log.info("Command committed at {}-{}@{}", md.topic(), md.partition(), md.offset());
} catch (ExecutionException e) {
    // e.getCause() is the real Kafka exception (e.g., TimeoutException)
    throw new ProvisioningException("Command not durably stored", e.getCause());
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new ProvisioningException("Interrupted while producing", e);
}
```

Throughput note: this caps at ~1 record per RTT — fine for a handful of commands, terrible for a firehose.

### 5.3 Maximum throughput firehose (logs/metrics, loss-tolerant)

When you ship gigabytes of telemetry and a few lost records are acceptable.

```java
props.put(ProducerConfig.ACKS_CONFIG, "1");                 // or "0" if truly loss-tolerant
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false); // not needed; allows higher in-flight
props.put(ProducerConfig.LINGER_MS_CONFIG, 100);            // big linger -> big batches
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 256 * 1024);    // 256 KiB
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 256L * 1024 * 1024); // 256 MiB buffer
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");  // great ratio for text/JSON logs
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 16); // more pipelining
props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 4 * 1024 * 1024);  // align with broker

// Fire-and-forget; no callback needed for acks=1/0 telemetry, but still good to count errors.
for (LogLine line : lines) {
    producer.send(new ProducerRecord<>("app-logs", line.host(), line.bytes()));
}
```

Tradeoff explicit: disabling idempotence and raising `max.in.flight` past 5 means **possible reordering on retry** and **possible duplicates** — acceptable for logs, never for payments.

### 5.4 Strict per-key ordering with a custom partitioner

Route by tenant and guarantee per-tenant ordering even across rebalances.

```java
public class TenantPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        int numPartitions = cluster.partitionCountForTopic(topic);
        if (keyBytes == null) {
            // Spread keyless records; fall back to a hash of value or random.
            return ThreadLocalRandom.current().nextInt(numPartitions);
        }
        // Stable, version-independent hashing (don't rely on String.hashCode across JVMs).
        int hash = Utils.murmur2(keyBytes) & 0x7fffffff;
        return hash % numPartitions;
    }
    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}
```

```java
props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, TenantPartitioner.class.getName());
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // keeps order with in-flight up to 5
```

Because every record for a tenant uses the tenant ID as key, all of that tenant's events land in one partition and are strictly ordered. Idempotence preserves order through retries without forcing `max.in.flight=1`.

### 5.5 JSON value serializer with Jackson (custom serializer)

```java
public class JsonSerializer<T> implements Serializer<T> {
    private final ObjectMapper mapper = new ObjectMapper();
    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) return null;                 // null -> tombstone for compacted topics
        try {
            return mapper.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            // Thrown on the APP thread out of send(); record never enters the accumulator.
            throw new SerializationException("Failed to serialize for topic " + topic, e);
        }
    }
}
```

```java
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class.getName());
Producer<String, OrderEvent> producer = new KafkaProducer<>(props);
producer.send(new ProducerRecord<>("orders", order.id(), orderEvent));
```

### 5.6 Avro + Schema Registry (the production-grade structured approach)

```java
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
          io.confluent.kafka.serializers.KafkaAvroSerializer.class.getName());
props.put("schema.registry.url", "http://schema-registry:8081");
props.put("auto.register.schemas", false);   // production: register via CI, don't auto-create
props.put("use.latest.version", true);

Producer<String, GenericRecord> producer = new KafkaProducer<>(props);
GenericRecord value = new GenericData.Record(orderSchema);
value.put("orderId", order.id());
value.put("amountCents", order.amountCents());
producer.send(new ProducerRecord<>("orders-avro", order.id(), value));
```

The serializer registers/looks up the schema, prefixes the bytes with a magic byte + 4-byte schema ID, and the consumer uses that ID to fetch the writer schema. This gives **schema evolution** with compatibility checks — far safer than ad-hoc JSON at scale.

### 5.7 Transactional producer: atomic multi-topic write

Write to two topics atomically; either both land or neither does.

```java
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "order-tx-" + instanceId); // STABLE across restarts
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // implied, but be explicit
Producer<String, String> producer = new KafkaProducer<>(props);

producer.initTransactions(); // once at startup: gets PID, fences zombies by bumping epoch

try {
    producer.beginTransaction();
    producer.send(new ProducerRecord<>("orders", order.id(), order.toJson()));
    producer.send(new ProducerRecord<>("inventory-reservations", order.sku(), reservation.toJson()));
    producer.commitTransaction(); // atomic: both records become visible together to read_committed consumers
} catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
    // Fatal: another producer with the same transactional.id fenced us, or unrecoverable.
    producer.close(); // must not continue; create a new producer
} catch (KafkaException e) {
    producer.abortTransaction(); // recoverable: roll back this transaction and retry the unit of work
}
```

### 5.8 Exactly-once consume-process-produce (the EOS loop)

The canonical pattern Kafka Streams uses under the hood.

```java
producer.initTransactions();
while (running) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
    if (records.isEmpty()) continue;
    producer.beginTransaction();
    try {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String, String> in : records) {
            String out = transform(in.value());
            producer.send(new ProducerRecord<>("output", in.key(), out));
            offsets.put(new TopicPartition(in.topic(), in.partition()),
                        new OffsetAndMetadata(in.offset() + 1));
        }
        // Commit the SOURCE offsets inside the SAME transaction as the output records.
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
        producer.commitTransaction(); // outputs + offsets commit atomically -> exactly-once
    } catch (KafkaException e) {
        producer.abortTransaction(); // reprocess from last committed offset
    }
}
// Consumer must use isolation.level=read_committed; do NOT auto-commit offsets.
```

### 5.9 Bounded blocking + backpressure-aware producing

When you must not silently drop and want to fail fast under overload.

```java
props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);        // fail send() fast if buffer full
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64L * 1024 * 1024);

try {
    producer.send(record, callback);
} catch (BufferExhaustedException | TimeoutException e) {
    // Buffer full for > max.block.ms: apply application backpressure (slow the source,
    // shed load, or push to a local spool) rather than blocking forever.
    metrics.increment("producer.backpressure");
    backpressureController.signalSlowDown();
}
```

### 5.10 Producer interceptor (cross-cutting tracing/metrics)

```java
public class TracingInterceptor implements ProducerInterceptor<String, String> {
    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        record.headers().add("trace-id", currentTraceId().getBytes(StandardCharsets.UTF_8));
        return record; // runs on the APP thread, before partitioning/serialization
    }
    @Override
    public void onAcknowledgement(RecordMetadata md, Exception e) {
        // runs on the Sender thread on ack/failure
        if (e != null) errorCounter.increment();
    }
    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}
```

```java
props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, TracingInterceptor.class.getName());
```

### 5.11 Unit testing with `MockProducer`

```java
MockProducer<String, String> mock = new MockProducer<>(true, // autoComplete
        new StringSerializer(), new StringSerializer());
OrderPublisher publisher = new OrderPublisher(mock);

publisher.publish(new Order("o1", "c1"));

assertEquals(1, mock.history().size());
ProducerRecord<String, String> sent = mock.history().get(0);
assertEquals("orders", sent.topic());
assertEquals("c1", sent.key());

// To test failure handling, use autoComplete=false and complete manually:
MockProducer<String, String> manual = new MockProducer<>(false,
        new StringSerializer(), new StringSerializer());
Future<RecordMetadata> f = manual.send(record, cb);
manual.errorNext(new TimeoutException("simulated")); // fire failure into the callback/future
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Share one producer instance.** `KafkaProducer` is thread-safe and designed to be shared across all your application threads. Creating a producer per request is a serious anti-pattern: each has its own buffer, Sender thread, and connections, and you lose all batching. One (or a small pool) per process.
- **Tune the throughput trio:** raise `linger.ms` (5–100), raise `batch.size` (64–256 KiB), enable compression (`lz4`/`zstd`). Bigger batches = fewer requests = higher throughput and better compression ratios.
- **`buffer.memory`** must be large enough to absorb bursts and cover all active partitions; watch `bufferpool-wait-ratio` and `buffer-available-bytes`.
- **`max.in.flight=5`** is fine and keeps ordering with idempotence; raising it only helps when idempotence is off and you accept reorder risk.
- **Avoid `send().get()` per record** — it serializes the pipeline. Use callbacks; call `flush()` at boundaries if you need a barrier.
- **Partition count** is the parallelism ceiling on the consume side and affects producer batching distribution. Too many partitions fragment batches and exhaust `buffer.memory`.

### 6.2 Correctness & concurrency

- **Callbacks run on the single Sender thread.** Never block in a callback (no DB calls, no `send().get()`, no locks held long) — you stall *all* delivery. Hand work off to another executor if needed.
- **Serializer exceptions throw synchronously** out of `send()` (not via callback). Wrap `send()` if you can't tolerate that.
- **Ordering** is per partition only. If you need global order, you need one partition (and you lose parallelism). Usually you want per-key order via keying.
- **Idempotence on by default (3.x).** Don't disable it without reason. If you set `acks=1` *and* `enable.idempotence=true` explicitly, you'll get a `ConfigException` — pick consistent values.

### 6.3 Memory

- Total producer heap pressure ≈ `buffer.memory` plus per-batch overhead plus compression buffers. With many producers or large buffers, account for this in JVM sizing.
- `max.request.size` and broker `message.max.bytes` / topic `max.message.bytes` must be consistent, or large records get rejected broker-side with confusing errors. For genuinely large payloads, prefer **claim-check** (store the blob in object storage, send a reference).

### 6.4 Security

- Use `SASL_SSL` (encryption + auth) in production. Don't ship `PLAINTEXT` over untrusted networks.
- Keep credentials out of code: use `sasl.jaas.config` from a secrets manager, or `ssl.keystore.location` pointing at mounted secrets.
- Producer needs **WRITE** ACL on the topic; transactional producers also need **WRITE** on the `transactional.id` resource and access to `__transaction_state`. Missing ACLs surface as `TopicAuthorizationException` / `TransactionalIdAuthorizationException`.

### 6.5 Observability

- Export producer JMX metrics (§4.6) to Prometheus/Grafana. Alert on `record-error-rate > 0`, rising `record-queue-time-avg`, `bufferpool-wait-ratio > 0`, and `produce-throttle-time-avg > 0`.
- Always attach a callback (or check the future) so terminal failures are logged/alerted/DLQ'd — fire-and-forget without error handling hides data loss.
- Add trace headers via an interceptor for end-to-end tracing across producer → topic → consumer.

### 6.6 Cost

- Compression reduces network egress and broker storage (often the biggest Kafka cost). `zstd` typically wins.
- Over-provisioned `acks=all` + RF=3 triples storage and inter-broker traffic vs. RF=1 — justified for important data, wasteful for ephemeral telemetry.
- Quotas (`produce-throttle-time-avg`) protect shared clusters; design producers to respect throttling rather than hammering.

### 6.7 Testing

- `MockProducer` for unit tests; `EmbeddedKafkaCluster` / Testcontainers Kafka for integration tests.
- `kafka-producer-perf-test.sh` for load/latency characterization before production.
- `kafka-verifiable-producer.sh` + `kafka-verifiable-consumer.sh` to assert no loss/dups/reordering under induced failures.

### 6.8 Production hardening checklist

- `acks=all`, `replication.factor=3`, `min.insync.replicas=2`, `enable.idempotence=true`.
- Set `client.id`; export metrics; alert on error rate and backpressure.
- Bound `max.block.ms` so the app fails fast under buffer exhaustion instead of hanging.
- Every `send()` has terminal error handling (callback → DLQ/alert).
- Graceful shutdown: `close(Duration)` so buffered records flush before exit.
- For EOS: stable `transactional.id` per logical instance; consumers `read_committed`.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Producer per request/thread | No batching; resource churn | One shared producer. |
| Ignoring the future/callback | Silent data loss | Always handle terminal errors. |
| Blocking inside a callback | Stalls the Sender thread → all delivery stops | Offload to executor. |
| `acks=1` for critical data | Loss on leader failover | `acks=all` + `min.insync.replicas=2`. |
| `min.insync.replicas=1` with RF=3 | `acks=all` gives false security | Set to 2. |
| `max.in.flight>1` without idempotence, needing order | Reordering on retry | Idempotence (≤5) or `max.in.flight=1`. |
| Huge records over Kafka | Rejections, memory pressure | Claim-check pattern. |
| Relying on cross-partition ordering | Doesn't exist | Key for per-key order. |
| Not calling `close()` | Lost buffered records on exit | try-with-resources / `close(Duration)`. |

---

## 7. Advanced topics & deep internals

### 7.1 Why `max.in.flight=5` is the magic number for idempotence

The broker tracks the last **5** batches' sequence metadata per `(PID, partition)`. That's why idempotence caps `max.in.flight` at 5: with up to 5 unacked batches in flight, the broker can still detect duplicates and out-of-order arrivals among them and the producer can re-sort retries correctly. Beyond 5, the broker can't reliably dedup/reorder, so the producer disallows idempotence. This is a deliberate memory/correctness tradeoff baked into the protocol.

### 7.2 `OutOfOrderSequenceException` — what it really means

With idempotence on, if the broker sees a batch whose sequence number is *higher* than expected (a gap), it means an earlier batch was lost (not written) — a serious anomaly (e.g., a non-retriable failure mid-stream, or a broker-side log truncation). Pre-Kafka-2.5 this could be a **fatal** producer error requiring recreation. Modern clients handle many cases by re-requesting a new PID and reassigning sequences (KIP-360, Kafka 2.5+), making the idempotent producer far more resilient to transient out-of-order/UNKNOWN_PRODUCER_ID situations.

### 7.3 `UNKNOWN_PRODUCER_ID` and PID expiration

Brokers expire PID metadata after a partition's records age out (retention) or after `transactional.id.expiration.ms`. A long-idle idempotent producer could find its PID forgotten and get `UNKNOWN_PRODUCER_ID`. KIP-360 lets the producer recover by obtaining a fresh PID and continuing, instead of failing fatally.

### 7.4 The sticky partitioner's batch-completion behavior (KIP-480 / KIP-794)

The original sticky partitioner (KIP-480, 2.4) switched partitions when a batch completed *or* `linger.ms` elapsed. KIP-794 (3.3) refined it to switch based on a configurable amount of bytes (`partitioner.batch.size`-like behavior) and to weight by broker load and availability (`partitioner.availability.timeout.ms`, default 0 = off). This keeps batches large *and* avoids hammering a slow broker. The legacy `DefaultPartitioner`/`UniformStickyPartitioner` classes are deprecated in favor of the built-in (set `partitioner.class=null`).

### 7.5 Record format and overhead

Since Kafka 0.11 the **record batch format v2** is used: a batch header (CRC, attributes, baseOffset, producerId, producerEpoch, baseSequence, etc.) followed by varint-encoded records with delta offsets/timestamps. This format is what enables idempotence and transactions (the header carries PID/epoch/sequence). Per-record overhead is small because keys/timestamps are delta-encoded within the batch — another reason batching helps.

### 7.6 Compression and the broker's choices

If a topic sets `compression.type=producer` (default), the broker stores the batch in the producer's codec untouched (cheapest). If a topic forces a codec different from the producer's, the broker must **decompress and recompress** every batch — costly CPU. Keep producer and topic codecs aligned to avoid silent broker CPU burn.

### 7.7 Leader epoch and avoiding stale-leader data loss

Brokers tag data with a **leader epoch** (incremented each leadership change). On failover, followers use leader epochs to truncate divergent suffixes correctly, preventing the classic "lost messages on unclean leadership change" bug. This is broker-side but it's *why* `acks=all` durability actually holds under failover (combined with `unclean.leader.election.enable=false`, which you should keep false).

### 7.8 `flush()` semantics

`flush()` blocks until **every** record buffered at the time of the call has been acked or failed. It does *not* prevent new sends from other threads, but those started after the call aren't waited on. Useful as a barrier (e.g., before committing a checkpoint). It does not close the producer.

### 7.9 Delivery timeout vs. retries interplay (the modern model)

Before Kafka 2.1, `retries` directly bounded attempts and reasoning about total time was painful. KIP-91 introduced `delivery.timeout.ms` as the single end-to-end bound. Now: a record is retried until success, a non-retriable error, *or* `delivery.timeout.ms` elapses — whichever first. Keep `retries` high and tune `delivery.timeout.ms`.

### 7.10 Zombie fencing in transactions

When a transactional producer restarts (same `transactional.id`), `initTransactions()` bumps the epoch. Any older instance still alive ("zombie") that tries to write gets `ProducerFencedException` because its epoch is now stale. This guarantees that even with crash-restart, only one logical producer is active per `transactional.id`, which is essential for exactly-once across restarts.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing `acks`

| Goal | acks | Idempotence | Notes |
|---|---|---|---|
| Max throughput, loss OK | 0 | off | Metrics firehose; no ack, no retry value. |
| Balanced, rare loss OK | 1 | off | Loss only on leader-crash-before-replication. |
| No loss, no dup, ordered | all | on | + `min.insync.replicas=2`, RF=3. The default for important data. |
| No loss + atomic multi-partition / EOS | all | on (txn) | Transactions with stable `transactional.id`. |

### 8.2 Throughput vs. latency tuning

| Knob | Toward latency | Toward throughput |
|---|---|---|
| `linger.ms` | 0 | 20–100 |
| `batch.size` | 16 KiB | 128–512 KiB |
| `compression.type` | none/lz4 | zstd |
| `buffer.memory` | default | larger (128–512 MiB) |
| `max.in.flight` | 5 | 5 (keep idempotence) or higher if loss/reorder OK |
| `acks` | 1 | 1 (durability-permitting) |

### 8.3 Idempotent vs. transactional vs. neither

| Property | Plain (non-idempotent) | Idempotent | Transactional |
|---|---|---|---|
| No loss (with acks=all) | yes | yes | yes |
| No transport duplicates | no | yes (per session) | yes |
| Ordering with in-flight>1 | no | yes (≤5) | yes |
| Atomic multi-partition | no | no | yes |
| EOS across restarts | no | no | yes (stable txn.id) |
| Overhead | lowest | tiny | higher (markers, coordinator, latency) |
| Use when | logs/metrics | most event pipelines | EOS, atomic writes, Kafka Streams |

### 8.4 Use-when / avoid-when rules

- **Use `acks=all` + idempotence** for any business event you can't afford to lose or duplicate. **Avoid** for high-volume best-effort telemetry where the cost outweighs the value.
- **Use transactions** when multiple sends (or sends + offset commits) must be all-or-nothing, or for EOS stream processing. **Avoid** for simple single-topic publishing — the coordinator round-trips and markers add latency and complexity you don't need.
- **Use high `linger.ms`** for throughput-bound batch/streaming. **Avoid** for interactive/low-latency request paths.
- **Use a custom partitioner** for affinity/tenancy routing. **Avoid** unless you have a concrete need — the built-in sticky partitioner is well-tuned.

### 8.5 Kafka producer vs. alternatives

| Need | Kafka producer | Alternative |
|---|---|---|
| High-throughput durable event log | Kafka | Pulsar producer (similar), Kinesis PutRecords. |
| Simple work queue with per-message ack/redelivery | Kafka (coarser) | RabbitMQ / SQS (per-message ack, easier requeue). |
| Request/reply RPC | Not ideal | gRPC/HTTP. |
| Exactly-once stream processing | Kafka transactions / Streams | Flink with its own checkpointing. |

---

## 9. Failure modes & debugging

### 9.1 Common production failures and how to diagnose

| Symptom | Likely cause | Diagnose | Fix |
|---|---|---|---|
| `TimeoutException: ... expiring N record(s)` | Broker/network slow; ISR can't satisfy acks; delivery.timeout too low | Check `request-latency-max`, broker logs, `kafka-topics --describe` for under-replicated partitions | Raise `delivery.timeout.ms`; fix broker/ISR; check network. |
| `NotEnoughReplicasException` | ISR < `min.insync.replicas` | `kafka-topics --describe` shows Isr smaller than min | Restore failed brokers; verify RF/min.isr config. |
| `RecordTooLargeException` | Record > `max.request.size` or broker `message.max.bytes` | Compare configs and record size | Raise limits consistently or use claim-check. |
| `BufferExhaustedException` / blocking sends | Producer outpacing brokers; small `buffer.memory` | `bufferpool-wait-ratio`, `buffer-available-bytes` near 0 | Raise `buffer.memory`/throughput tuning or apply backpressure. |
| `TopicAuthorizationException` | Missing WRITE ACL | Broker authorizer logs | Grant ACL. |
| `ProducerFencedException` | Another producer with same `transactional.id` (or restart) | Logs; epoch bump | Ensure unique txn.id per instance; close fenced producer. |
| `OutOfOrderSequenceException` | Lost batch / truncation with idempotence | Broker logs; correlate failover | Usually transient (modern clients recover); investigate broker health/unclean elections. |
| Reordering observed | `max.in.flight>1` without idempotence + retries | Config audit | Enable idempotence or set `max.in.flight=1`. |
| Duplicates observed | Retries without idempotence; or app-level double-send | Inspect dedup keys | Enable idempotence; idempotent app design. |
| High latency, low throughput | Tiny batches; `send().get()` per record; small `linger.ms` | `batch-size-avg`, `records-per-request-avg` low | Raise `linger.ms`/`batch.size`; use async callbacks. |
| `produce-throttle-time-avg > 0` | Broker quota | Quota metrics | Reduce rate or raise quota. |

### 9.2 Diagnostic toolkit

- **Producer JMX metrics** (§4.6) — first stop. `record-error-rate`, `request-latency`, `record-queue-time`, `bufferpool-wait-ratio`.
- **`kafka-topics.sh --describe --topic t`** — check ISR, under-replicated partitions, leadership.
- **`kafka-get-offsets.sh`** — confirm records actually landed (end offsets advancing).
- **Broker logs** (`server.log`) — authorization, ISR shrink/expand, leadership changes.
- **`kafka-producer-perf-test.sh`** — reproduce throughput/latency in isolation.
- **Enable producer DEBUG logging** (`org.apache.kafka.clients.producer`) to see batch drains, retries, metadata refreshes.
- **`kafka-dump-log.sh`** — inspect on-disk segments, including PID/epoch/sequence and transaction markers, to confirm idempotence/transaction behavior.

### 9.3 Real-world incident patterns

- **"We set `acks=all` but still lost messages."** Almost always `min.insync.replicas=1` (or `unclean.leader.election.enable=true`). With ISR shrunk to the leader, `acks=all` ≡ `acks=1`; a subsequent unclean election picks a replica missing the data. Fix: `min.insync.replicas=2`, RF=3, unclean election disabled. Tradeoff: writes get rejected (`NotEnoughReplicasException`) if you lose two brokers — that's the *correct* behavior (fail closed, don't risk loss).
- **"Throughput cratered after a refactor."** Someone switched to `producer.send(record).get()` (synchronous) "to handle errors," serializing the pipeline to 1 record/RTT. Fix: async + callback.
- **"Duplicate downstream effects."** Producer retried after a lost ack with idempotence disabled → at-least-once duplicates. Fix: enable idempotence and/or make consumers idempotent on a business key.
- **"Random reordering of a key's events."** `max.in.flight=5` (or higher) with idempotence accidentally disabled and retries on. A retried batch landed after a later one. Fix: keep idempotence on.
- **"Producer hung at startup."** `send()` blocked `max.block.ms` because bootstrap servers were unreachable / DNS wrong / firewall — metadata never arrived. Fix: connectivity; bound `max.block.ms`; fail fast.

---

## 10. Interview drill

**Q1. Walk me through what happens between `producer.send(record)` and the record being durably stored.**
*Model answer:* On the app thread, `send()` waits for metadata (≤`max.block.ms`), serializes key and value, picks a partition via the partitioner, and appends the serialized record to a per-partition batch in the RecordAccumulator (allocating buffer from the BufferPool, possibly blocking if full), then returns a `Future` immediately. The background Sender thread drains ready batches (when full or `linger.ms` elapsed), groups them per broker into ProduceRequests respecting `max.in.flight`, sends via NIO, awaits acks per `acks`, retries retriable errors (bounded by `delivery.timeout.ms`), then completes futures and fires callbacks. Durability is realized when, with `acks=all`, the record is replicated to all ISR replicas (committed) under the high watermark.
- *Probe: Where can `send()` block?* Waiting for metadata, and waiting for buffer memory — both bounded by `max.block.ms`.
- *Probe: Which thread runs my callback?* The single Sender thread — never block it.
- *Probe: When is the record "committed"?* When all current ISR replicas have it (high watermark advances), which `acks=all` waits for.

**Q2. Explain `acks` and why `acks=all` alone isn't enough for no-loss.**
*Model answer:* `acks=0` doesn't wait; `acks=1` waits for leader's log write; `acks=all` waits for all ISR. But if the ISR has shrunk to just the leader, `acks=all` provides only leader durability. You need `min.insync.replicas=2` (with RF=3) so the broker rejects writes when it can't safely commit, plus `unclean.leader.election.enable=false`. That trio tolerates one broker loss with zero data loss.
- *Probe: What error if min.isr can't be met?* `NotEnoughReplicasException` — a retriable produce failure.
- *Probe: What does min.isr=2, RF=3 cost you?* Writes fail if two brokers in the ISR are down — fail-closed for safety.

**Q3. How does the idempotent producer prevent duplicates while preserving ordering?**
*Model answer:* On init the producer gets a PID and epoch. Each `(PID, partition)` has a monotonically increasing sequence number; each batch carries PID/epoch/first-sequence. The broker remembers the last sequence per `(PID, partition)` (for the last 5 batches). A retried duplicate (seq ≤ stored) is acked without rewriting; a gap (seq too high) yields `OutOfOrderSequenceException`. Because the broker enforces order via sequences, you keep ordering with `max.in.flight` up to 5 instead of being forced to 1.
- *Probe: Why cap at 5?* The broker tracks the last 5 batches' sequence state per partition.
- *Probe: Does it dedup across producer restarts?* No — new PID each session; that needs transactions with a stable `transactional.id`.
- *Probe: Required configs?* `acks=all`, `retries>0`, `max.in.flight≤5` — else `ConfigException`.

**Q4. What's the difference between idempotent and transactional producers?**
*Model answer:* Idempotence prevents transport-level duplicates and preserves order per partition within one session. Transactions add atomic writes across multiple partitions/topics and atomic offset commits (the consume-process-produce EOS loop), survive restarts via a stable `transactional.id` (with epoch-based zombie fencing), and require `read_committed` consumers to hide aborted records.
- *Probe: What fences a zombie?* `initTransactions()` bumps the epoch; stale-epoch writes get `ProducerFencedException`.
- *Probe: What does a `read_committed` consumer do differently?* Skips aborted records and only reads up to the last stable offset.

**Q5. How do `batch.size`, `linger.ms`, and compression interact, and how do you tune for throughput vs latency?**
*Model answer:* `batch.size` caps a per-partition batch; `linger.ms` is how long a batch waits to accumulate before sending. Higher both → bigger batches → fewer requests → higher throughput and better compression ratios (compression is per-batch), at the cost of added latency. For throughput: `linger.ms` 20–100, `batch.size` 128–512 KiB, `zstd`. For latency: `linger.ms` 0, smaller batches, `lz4`/none.
- *Probe: Does `linger.ms=0` mean no batching?* No — it batches whatever accumulates while the prior request is in flight; it just doesn't deliberately wait.
- *Probe: Why does compression like bigger batches?* More redundancy across records in one block → better ratio.

**Q6 (senior-signal). You're designing the producer for a payments event stream. Justify every config choice.**
*Model answer:* `acks=all` + `enable.idempotence=true` + broker `replication.factor=3`, `min.insync.replicas=2`, `unclean.leader.election.enable=false` → no loss, no transport duplicates, ordered per key. Key by account/transaction ID for per-entity ordering. `max.in.flight=5` (kept ordered by idempotence). `delivery.timeout.ms` sized to business SLA (e.g., 2 min). If downstream effects must be atomic with offset commits (consume-process-produce), use transactions with a stable `transactional.id` and `read_committed` consumers. Modest `linger.ms` (5–10) for some batching without hurting latency; `lz4`/`zstd` compression. Bound `max.block.ms` and route terminal failures to a DLQ with alerting. The justification thread is: payments demand exactly-once-effect and ordering, so durability and idempotence dominate, and we accept fail-closed writes (NotEnoughReplicas) over any loss risk.
- *Probe: Why not `acks=0`/`1`?* Both can lose committed-looking data on failover; unacceptable for payments.
- *Probe: When would you add transactions vs. just idempotence?* When you must atomically write multiple records and/or commit offsets together (cross-partition atomicity / EOS), not for single-topic publish.

**Q7 (senior-signal). A teammate raises `max.in.flight.requests.per.connection` to 32 to boost throughput. What do you say?**
*Model answer:* That disables the idempotent producer's ordering guarantee (cap is 5) — and if idempotence is off, retries can reorder and duplicate. For a throughput firehose where order/dups don't matter (logs), it's acceptable along with `acks=1` and disabled idempotence. For ordered/critical streams, it's a correctness regression; instead get throughput from `linger.ms`/`batch.size`/compression and more partitions. The real question is the data's correctness requirements, not raw throughput.
- *Probe: Will the producer even let you?* Only if idempotence is disabled; otherwise `ConfigException`.
- *Probe: Better levers for throughput while keeping order?* Bigger batches, compression, more partitions, more producer instances/threads.

**Q8 (senior-signal). Your producer's `record-error-rate` is near zero but consumers see duplicates. Diagnose.**
*Model answer:* Producer-side errors near zero means the transport isn't failing visibly. Duplicates with no producer errors typically come from (a) idempotence disabled + retries after lost acks (at-least-once), or (b) application-level double-sends (e.g., retried business operation calling `send` twice), or (c) a consume-process-produce loop without transactions reprocessing after a crash. Fix: enable idempotence to kill transport dups; for app/loop dups, use transactions (EOS) or make consumers idempotent on a business key. Idempotence only dedups transport retries within a session — it won't fix application duplicates.
- *Probe: How to confirm transport vs app dups?* Check whether duplicate records share offsets/sequence (transport, shouldn't happen with idempotence) vs distinct offsets with same business key (app/loop).
- *Probe: Cheapest robust fix if you can't change producers?* Consumer-side idempotency keyed on a business idempotency key.

**Q9. What is `delivery.timeout.ms` and how does it relate to `retries` and `request.timeout.ms`?**
*Model answer:* `delivery.timeout.ms` (default 120s) is the end-to-end deadline for a record from `send()` returning to terminal success/failure, covering all retries and backoffs; it must be ≥ `linger.ms + request.timeout.ms`. Modern practice leaves `retries` at max and controls the real bound via this single timeout. `request.timeout.ms` (30s) bounds one network request.
- *Probe: What happens on expiry?* Record fails with `TimeoutException` regardless of remaining retries.

**Q10. How does the default partitioner behave for keyed vs keyless records across versions?**
*Model answer:* Keyed: `murmur2(key) % partitions` (stable, same key → same partition). Keyless: pre-2.4 round-robin; 2.4+ sticky partitioner (stick to a partition until the batch fills/linger, then switch) for better batching; 3.3+ built-in uniform sticky that also weights by broker load/availability, with the old classes deprecated.
- *Probe: Why sticky over round-robin?* Round-robin scatters records into many tiny batches; sticky fills batches → bigger requests, better throughput/compression, with even load over time.

**Q11. Why must you never block inside a producer callback?**
*Model answer:* Callbacks execute on the single Sender (I/O) thread that drives all network I/O for the producer. Blocking it (DB call, lock, `send().get()`) stalls draining batches and processing responses for *every* partition — global throughput collapse and possible delivery-timeout cascades. Offload heavy work to a separate executor.
- *Probe: What's safe in a callback?* Fast, non-blocking work: metrics, logging, lightweight enqueue.

**Q12. Explain the buffer-full / backpressure path.**
*Model answer:* `send()` allocates from `BufferPool` (size `buffer.memory`). If exhausted, `send()` blocks up to `max.block.ms` waiting for memory; on timeout it throws `TimeoutException`/`BufferExhaustedException`. This is the producer's backpressure mechanism — it bounds memory and signals overload rather than OOMing. Watch `bufferpool-wait-ratio` and `buffer-available-bytes`; respond with real backpressure (slow the source) rather than blocking forever.
- *Probe: How to fail fast instead of hanging?* Lower `max.block.ms` and handle the exception as a backpressure signal.

---

## 11. Glossary

- **acks:** Producer config controlling when a leader acknowledges a write: `0` (none), `1` (leader), `all` (full ISR).
- **At-least-once / at-most-once / exactly-once:** Delivery semantics — duplicates-but-no-loss / no-duplicates-but-possible-loss / neither.
- **Batch (ProducerBatch):** A group of serialized records for one partition, the unit of network transfer, retry, and compression.
- **batch.size:** Max bytes for a per-partition batch buffer (default 16 KiB).
- **Broker:** A single Kafka server process; a cluster is many brokers.
- **BufferPool:** Producer-side memory manager bounded by `buffer.memory`.
- **buffer.memory:** Total producer buffer for unsent records (default 32 MiB).
- **Callback:** `onCompletion(metadata, exception)` run on the Sender thread when a send terminally succeeds/fails.
- **Claim-check pattern:** Storing large payloads externally and sending only a reference through Kafka.
- **Committed / high watermark:** A record is committed when replicated to all ISR; the high watermark is the max offset all ISR have. Consumers read only up to it.
- **Compression (none/gzip/snappy/lz4/zstd):** Per-batch payload compression to save bandwidth/storage.
- **delivery.timeout.ms:** End-to-end per-record deadline covering all retries (default 120s).
- **Epoch:** Generation counter paired with a PID/leader to detect stale (zombie/old) writers.
- **EOS (Exactly-Once Semantics):** No loss and no duplicates end-to-end, via transactions.
- **fsync:** Forcing OS page-cache writes to physical disk. Kafka relies on replication more than per-write fsync for durability.
- **High watermark (HW):** See committed.
- **Idempotent producer:** Producer that prevents broker-side duplicates from retries via PID + per-partition sequence numbers; default on in 3.x.
- **In-flight requests:** Sent-but-unacked requests per connection; `max.in.flight.requests.per.connection` caps them (default 5).
- **ISR (In-Sync Replicas):** Replicas caught up to the leader within `replica.lag.time.max.ms`.
- **JMX:** Java Management Extensions — how producer metrics are exposed.
- **Key:** Optional record field used for partitioning (hash) and log compaction.
- **Leader / follower:** The replica handling reads/writes for a partition / the replicas copying it.
- **Leader epoch:** Counter incremented on each leadership change; used for correct log truncation on failover.
- **linger.ms:** Time a batch waits to accumulate records before sending (default 0).
- **Log (commit log):** Append-only ordered record sequence = a partition.
- **Log compaction:** Retention mode keeping only the latest value per key.
- **max.block.ms:** Max time `send()` blocks for metadata/buffer (default 60s).
- **max.in.flight.requests.per.connection:** Concurrent unacked requests per connection (default 5; idempotence caps at 5).
- **max.request.size:** Max single produce request / single record size (default 1 MiB).
- **Metadata:** Producer's cached cluster topology (topics, partitions, leaders, brokers).
- **min.insync.replicas:** Broker/topic config; minimum ISR for `acks=all` to succeed (set to 2 with RF=3).
- **murmur2:** The hash function the default partitioner uses on keys.
- **NIO (Java New I/O):** Non-blocking I/O the NetworkClient uses for socket multiplexing.
- **Offset:** Position of a record within a partition (per-partition, monotonically increasing).
- **Partition:** An ordered append-only log within a topic; unit of ordering and parallelism.
- **Partitioner:** Component choosing a record's partition (key hash, sticky, or custom).
- **PID (Producer ID):** Cluster-assigned 64-bit ID enabling idempotence/transactions.
- **ProducerRecord:** The object you send (topic, optional partition/key/timestamp/headers, value).
- **RecordAccumulator:** In-memory staging area holding per-partition deques of batches.
- **RecordMetadata:** Success result: topic, partition, offset, timestamp, sizes.
- **read_committed / read_uncommitted:** Consumer isolation levels; the former hides aborted-transaction records.
- **Replication factor (RF):** Number of replicas per partition (commonly 3).
- **request.timeout.ms:** Per-request response wait (default 30s).
- **retries / retry.backoff.ms:** Retry count (default max) / wait between retries (default 100ms).
- **Sender (I/O) thread:** The single background thread doing all network I/O, retries, callbacks.
- **Sequence number:** Per-`(PID, partition)` monotonic counter used for dedup/ordering.
- **Serializer:** Converts key/value to `byte[]` (runs on the app thread).
- **Sticky partitioner:** Keyless partitioning that sticks to a partition until the batch fills, improving batching.
- **Tombstone:** A record with a null value signaling deletion in a compacted topic.
- **Transaction coordinator / `__transaction_state`:** Broker role / internal topic tracking transaction state.
- **transactional.id:** Stable producer identity enabling transactions and zombie fencing.
- **Unclean leader election:** Electing an out-of-sync replica as leader (risks data loss); keep disabled.
- **Zombie fencing:** Rejecting writes from an older epoch producer after a restart bumps the epoch.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Two stages:** app thread (`send()`: metadata → serialize → partition → append to accumulator → return Future) | Sender thread (drain → build ProduceRequests → NIO send → acks → retry → callbacks).

**Durable trio:** `acks=all` + `replication.factor=3` + `min.insync.replicas=2` + `unclean.leader.election.enable=false`. Tolerates 1 broker loss, zero data loss.

**Idempotence (default 3.x):** PID + epoch + per-`(PID,partition)` sequence; broker dedups retries, preserves order with `max.in.flight≤5`. Requires `acks=all`, `retries>0`.

**Key defaults:** `acks=all` (3.x), `enable.idempotence=true`, `batch.size=16 KiB`, `linger.ms=0`, `buffer.memory=32 MiB`, `max.in.flight=5`, `max.block.ms=60s`, `request.timeout.ms=30s`, `delivery.timeout.ms=120s`, `retry.backoff.ms=100ms`, `max.request.size=1 MiB`, `compression.type=none`, `metadata.max.age.ms=5min`.

**Throughput levers:** ↑`linger.ms` (20–100), ↑`batch.size` (128–512 KiB), compression (`zstd`/`lz4`), ↑`buffer.memory`, more partitions, async + callbacks (never `get()` per record).

**Ordering:** per partition only; key for per-key order; keep idempotence on to allow `max.in.flight=5` with order.

**Delivery semantics:** acks=0 → at-most-once; acks≥1 non-idempotent → at-least-once; idempotent → no transport dups; transactional → EOS (atomic multi-partition + offsets).

**Never:** producer-per-request; block in callbacks; ignore the future/callback; `acks=1` for critical data; `min.insync.replicas=1` with RF=3; raise `max.in.flight>5` when you need order; huge records (use claim-check); skip `close()`.

**Top metrics:** `record-error-rate` (≈0), `request-latency-max`, `batch-size-avg`, `record-queue-time-avg`, `bufferpool-wait-ratio` (0), `produce-throttle-time-avg` (0).

**Transactions:** stable `transactional.id` → `initTransactions()` (bumps epoch, fences zombies) → `beginTransaction()` → `send`/`sendOffsetsToTransaction` → `commit`/`abortTransaction`; consumers `read_committed`.

### Self-test (no answers — active recall)

1. Trace a record from `send()` to a fired callback, naming every component it passes through and stating which thread each step runs on. Where exactly can `send()` block, and for how long?
2. Your service uses `acks=all` and still lost a committed-looking message during a broker failover. Enumerate every config that could be responsible and explain the mechanism of loss for each.
3. Explain precisely how the idempotent producer can preserve ordering with five requests in flight, when a non-idempotent producer cannot. What broker-side state makes this possible, and why is the cap exactly five?
4. You must atomically (a) emit two events to different topics and (b) commit the source consumer offsets, with exactly-once effect across producer restarts. Specify the full producer + consumer configuration and the exact API call sequence, and explain what fences a restarted zombie.
5. Throughput is 10× too low and latency is fine. List, in priority order, the configuration changes you'd make and the producer metrics you'd watch to confirm each helped — and name one change that would *look* like a throughput fix but break ordering for keyed data.
6. Distinguish, with a concrete diagnostic procedure, between transport-level duplicates and application-level duplicates seen by a consumer, and give the correct fix for each.

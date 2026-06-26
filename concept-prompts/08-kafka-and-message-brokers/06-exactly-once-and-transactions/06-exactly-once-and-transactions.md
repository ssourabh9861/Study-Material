# Exactly-Once & Transactions in Apache Kafka

> An engineering-handbook chapter for senior JVM backend developers. From first principles to deep internals: idempotent producers, transactions, the transaction coordinator, read-committed consumers, the consume-process-produce loop, and the hard truth about what "exactly once" actually buys you.

---

## 1. Overview & where it fits

### What it is

**Exactly-Once Semantics (EOS)** in Kafka is a set of cooperating mechanisms that guarantee a record is processed and its effects are reflected **exactly one time** within the Kafka system — even in the presence of producer retries, broker crashes, network partitions, and consumer rebalances. It is built from three layers stacked on top of one another:

1. **The idempotent producer** — deduplicates retried writes to a single partition so a retry never creates a duplicate on the log.
2. **Transactions** — make writes to *multiple* partitions (and the consumer-offset commit) **atomic**: all of them become visible together, or none do.
3. **The `read_committed` consumer** — refuses to deliver records that belong to aborted or still-open transactions, so downstream readers only ever see committed data.

### The problem it solves

A distributed message system has to choose how it behaves when something goes wrong mid-delivery. There are three classic **delivery guarantees**:

| Guarantee | What it means | Failure behavior |
|---|---|---|
| **At-most-once** | Each record delivered 0 or 1 times | On failure, may *lose* records (never retried) |
| **At-least-once** | Each record delivered 1 or more times | On failure, may *duplicate* records (retried) |
| **Exactly-once** | Each record's effect applied exactly 1 time | Neither loses nor duplicates (within the boundary) |

The default Kafka producer, when it retries a send after a network hiccup (a temporary failure in the connection between two machines), can write the *same record twice* — the first write actually succeeded but the acknowledgement got lost, so the producer resends. That is at-least-once, and it produces duplicates. EOS removes those duplicates.

The single most important caveat, stated up front so it colors everything below: **Kafka's exactly-once guarantee is exactly-once *within Kafka's own boundary*.** It covers Kafka-to-Kafka data flow (read from topic, process, write to topic, commit offsets). It does **not** magically make a write to an external Postgres database or a REST call idempotent. That boundary is the subject of §6 and §8 and is where most engineers get burned.

### When you reach for it

- **Stream processing pipelines** (Kafka Streams, Flink-on-Kafka, ksqlDB) where you read from input topics, transform, and write to output topics, and a duplicate would corrupt aggregates (double-counting clicks, balances, inventory).
- **Multi-topic atomic writes** — e.g., an order event must land in `orders` *and* `audit` together or not at all.
- **The consume-process-produce loop** where the offset commit must be atomic with the output write (the classic source of duplicates).

When you reach for it inappropriately:

- A simple producer that just appends events and tolerates the occasional duplicate (use **idempotent producer** alone — it's nearly free).
- A pipeline whose terminal sink is an external system that already has natural idempotency (an upsert keyed by a business ID). There, **at-least-once + idempotency keys** is simpler and often cheaper (see §6).

### One-paragraph mental model

Think of a Kafka transaction as a **two-phase commit (2PC)** scoped to one producer. The producer announces "I'm starting a transaction," writes a bunch of records across partitions (each tagged as part of this transaction), and then says "commit." A dedicated broker component — the **transaction coordinator** — durably records the transaction's state in an internal log and stamps a special **control record (a commit or abort marker)** onto every partition the transaction touched. Consumers running in `read_committed` mode read up to the **Last Stable Offset (LSO)** — the highest offset below which there are no still-open transactions — and skip over any records belonging to aborted transactions. Idempotency (sequence numbers per producer) handles single-partition retries underneath; transactions handle multi-partition atomicity on top.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Skip ahead if a term is already familiar.

### 2.1 Kafka in one minute (the substrate)

**Apache Kafka** is a distributed, append-only **commit log**. Producers append records to **topics**; topics are split into **partitions**; each partition is an ordered, immutable sequence of records. Every record in a partition has a monotonically increasing integer position called its **offset** (0, 1, 2, …). Consumers read partitions sequentially and track "how far I've read" by committing an offset.

- **Broker** — a single Kafka server process. A **cluster** is a set of brokers.
- **Partition leader** — for each partition, one broker is the leader and handles all reads/writes; **followers** replicate it. The set of in-sync replicas is the **ISR (In-Sync Replica set)**.
- **`acks`** — a producer setting controlling how many replicas must acknowledge a write before it's considered done. `acks=0` (fire and forget), `acks=1` (leader only), `acks=all` (all in-sync replicas). EOS requires `acks=all`.
- **Consumer group** — a set of consumers that cooperatively divide a topic's partitions; each partition is consumed by exactly one member of the group at a time.
- **`__consumer_offsets`** — an internal Kafka topic where consumer groups store their committed offsets. (Remember this; transactions write here too.)

### 2.2 Why duplicates and gaps happen — the retry problem

Consider a producer sending record R to partition P with `acks=all`:

1. Producer sends R.
2. Leader appends R, replicates to followers, all ack.
3. Leader sends acknowledgement back to producer.
4. **The acknowledgement is lost** (network blip, leader crash right after step 2).
5. Producer times out, **retries**: sends R again.
6. Leader appends R *again* — now there are two copies. **Duplicate.**

If instead the producer is configured *not* to retry, then a genuinely-failed send is dropped → **lost record** (at-most-once). The fundamental tension: you cannot tell "my write failed" apart from "my write succeeded but the ack was lost" without extra machinery. That machinery is **idempotency** (a per-record identity the broker can dedupe on).

### 2.3 Idempotency — the foundational term

**Idempotent** means an operation can be applied multiple times without changing the result beyond the first application. `SET x = 5` is idempotent; `x = x + 1` is not. Kafka's **idempotent producer** makes the *append* operation idempotent at the broker by giving every record a unique identity the broker remembers, so a retried append with an identity it has already seen is silently discarded (and still acked, so the producer is happy).

### 2.4 Atomicity — the second foundational term

**Atomic** (from database **ACID**: Atomicity, Consistency, Isolation, Durability) means "all or nothing." A bank transfer debits one account and credits another; either both happen or neither does. A Kafka **transaction** gives you atomicity across multiple partition-writes *and* the offset commit, so a stream processor can't end up in the state "I wrote the output but forgot to record that I consumed the input" (which on restart would reprocess and duplicate).

### 2.5 Two-phase commit (2PC) — the pattern transactions imitate

**Two-phase commit** is a classic distributed-transaction protocol with a coordinator and participants:

- **Phase 1 (prepare):** coordinator asks every participant "can you commit?"; each votes yes (and durably promises it can) or no.
- **Phase 2 (commit/abort):** if all voted yes, coordinator tells everyone "commit"; otherwise "abort."

Kafka's transaction protocol is a *specialized* 2PC where the participants are the partition leaders, the coordinator is a broker-side component, and the "durable promise" is the writing of records plus a final control marker. (Kafka does not use generic XA/2PC across heterogeneous systems — it's an internal, optimized variant.)

### 2.6 The cast of EOS-specific terms

- **PID (Producer ID)** — a 64-bit integer the broker assigns to a producer when it initializes. The broker uses `(PID, partition, sequence number)` to dedupe.
- **Sequence number** — a per-`(PID, partition)` monotonically increasing integer the producer stamps on each record. The broker tracks the last-seen sequence per `(PID, partition)` and rejects out-of-order or duplicate sequences.
- **`transactional.id`** — a *user-chosen*, stable string identifying a logical producer across restarts. It's the key that enables transactions and **producer fencing** (below).
- **Producer epoch** — a monotonically increasing integer bumped each time a new producer instance claims a given `transactional.id`. The latest epoch "fences" (locks out) older zombie instances.
- **Transaction coordinator** — a broker-side module (the leader of a partition of the internal `__transaction_state` topic) that drives the transaction state machine and writes control markers.
- **Control record / control batch** — a special record (not visible to applications) written to a partition's log to mark a transaction's **commit** or **abort** on that partition.
- **LSO (Last Stable Offset)** — the offset up to which all transactions are resolved (committed or aborted). A `read_committed` consumer never reads past the LSO.
- **`read_committed` / `read_uncommitted`** — consumer isolation levels. `read_uncommitted` (default) returns everything; `read_committed` returns only committed transactional data (and all non-transactional data).
- **Zombie** — a producer instance everyone thought was dead (so a replacement was started) but which is actually still alive and might issue writes. Fencing exists to neutralize zombies.

With this vocabulary, the rest of the document is "merely" assembling these pieces.

---

## 3. How it works internally

This is the heart of the chapter. We go layer by layer, tracing control flow and data flow, then assemble the full picture.

### 3.1 Layer 1 — the idempotent producer

#### Enabling it

Set `enable.idempotence=true`. Since **Kafka 3.0**, this is the **default** (provided the other required settings are compatible). Enabling idempotence forces:

- `acks=all` (a write isn't deduped reliably unless durably replicated),
- `retries > 0` (idempotence is pointless without retries — its whole job is making retries safe),
- `max.in.flight.requests.per.connection <= 5` (the broker can only dedupe/reorder a bounded window).

If you explicitly set conflicting values (e.g., `acks=1`) the client throws a `ConfigException` at startup.

#### The PID handshake

When an idempotent producer starts, it sends an **`InitProducerId`** request to a broker. For a *non-transactional* idempotent producer (no `transactional.id`), the broker simply allocates a fresh **PID** and returns it with epoch 0. The PID is ephemeral — it lives only as long as this producer process; a restart gets a new PID.

#### Sequence numbers and broker-side dedup

Each producer maintains, per destination partition, a sequence counter starting at 0. Each record (technically each **record batch** — Kafka batches records) carries the PID, the producer epoch, and a base sequence number. The **partition leader** keeps, in memory (and recoverable from the log), the **last 5 acked sequence numbers per PID per partition** (5 because `max.in.flight` can be up to 5).

On receiving a batch with base sequence `S` from `(PID, epoch)`:

- If `S == lastSeq + 1` → accept, append, advance `lastSeq`.
- If `S <= lastSeq` (already seen) → **duplicate**: discard the append but **return success** with the original offset. The producer is none the wiser; no duplicate hits the log.
- If `S > lastSeq + 1` → **out-of-order**: reject with `OUT_OF_ORDER_SEQUENCE_NUMBER`. This means a batch in between was lost. The producer must resend the gap.

This is why `max.in.flight.requests.per.connection <= 5` matters: the broker tracks a sliding window of the last 5 batches and can detect/reorder within it. Beyond 5, it can't guarantee ordering+dedup. (Pre-2.0 Kafka required `max.in.flight=1` for ordering with retries; the 5-deep tracking landed in KIP-185/KAFKA era around 1.0–2.0.)

**Scope and limits of Layer 1:**
- Dedup is **per partition** — it cannot dedupe a record written to two different partitions.
- Dedup is **per producer session** — a producer restart yields a new PID, so the broker no longer recognizes prior sequences. Idempotent-producer-alone does **not** survive restarts. (Transactions, via `transactional.id`, do.)
- It guarantees **no duplicates and in-order delivery** to a single partition for the life of one producer instance. That's it. Powerful, cheap, and the right default for most producers.

### 3.2 Layer 2 — transactions

Transactions add **cross-partition atomicity** and **cross-session identity**. They are opt-in via a `transactional.id`.

#### The transactional.id and producer fencing

You set `transactional.id` to a stable, unique string per logical producer (e.g., `order-processor-3` for shard 3 of your service). On `initTransactions()`:

1. The producer sends `InitProducerId` **with** the `transactional.id`.
2. The **transaction coordinator** (the broker leading the relevant partition of `__transaction_state`) looks up any existing PID/epoch for that `transactional.id`.
   - If none, allocate a PID, epoch 0.
   - If one exists, **bump the epoch** (epoch+1) and return the same PID with the new epoch.
3. Crucially, the coordinator also **aborts any in-flight transaction** left over from a previous, now-fenced instance, then records the new epoch durably.

**Producer fencing:** because the coordinator bumps the epoch, any *older* producer instance still holding the old epoch will, on its next transactional request, get a **`ProducerFenced`** (or `InvalidProducerEpochException`) error and must shut down. This neutralizes zombies: if your processor for shard 3 hangs and a new one starts with the same `transactional.id`, the new one fences the old one, guaranteeing only one writer with that identity is ever live. **This is the linchpin of EOS across restarts and rebalances.**

> Beginner note — "fencing": in distributed systems, *fencing* means cutting off a process that should no longer act (often after it was presumed dead), so its stale operations can't corrupt shared state. The classic illustration is a STONITH ("shoot the other node in the head") in clustering. Kafka's epoch is a *fencing token*.

#### `__transaction_state` and the coordinator

`__transaction_state` is an internal, compacted Kafka topic (default **50 partitions**, replication factor governed by `transaction.state.log.replication.factor`, default **3** in production configs). A `transactional.id` is hashed to one of those partitions; the **leader broker of that partition is the transaction coordinator for that id.** The coordinator stores each transaction's metadata: PID, epoch, state, the set of partitions enrolled in the current transaction, and a timeout. This log is the durable source of truth — if the coordinator broker dies, a new leader replays the log and resumes.

#### The transaction state machine

A transaction (tracked by the coordinator) moves through these states:

```
Empty → Ongoing → PrepareCommit → CompleteCommit → (back to) Empty
                ↘ PrepareAbort  → CompleteAbort  ↗
Dead (transactional.id expired/removed)
```

- **Empty** — no active transaction for this id.
- **Ongoing** — `beginTransaction()` called; records are being written; partitions get *added* to the transaction as they're first written.
- **PrepareCommit / PrepareAbort** — the coordinator has durably logged the intent to commit/abort (phase-1-equivalent: the decision is now fixed and recoverable).
- **CompleteCommit / CompleteAbort** — control markers have been written to all enrolled partitions; the transaction is done.

#### Step-by-step trace of one transaction (control + data flow)

Suppose a producer with `transactional.id=tx-A` does: begin → send to topic `out1` partition 0 → send to topic `out2` partition 3 → commit.

1. **`initTransactions()`** (once, at startup): handshake with coordinator; get PID, epoch; any prior open txn for `tx-A` is aborted. State: `Empty`.
2. **`beginTransaction()`** (client-local): the producer marks itself "in transaction." No broker round-trip yet.
3. **First `send()` to out1-p0:** before the produce request, the producer sends an **`AddPartitionsToTxn`** request to the coordinator, enrolling `out1-0`. Coordinator logs it; state → `Ongoing`. Then the actual records are produced to the leader of `out1-0`, tagged with PID+epoch+sequence and a **transactional bit** so the broker knows these are part of an open txn.
4. **First `send()` to out2-p3:** same — `AddPartitionsToTxn` for `out2-3`, then produce.
5. **`commitTransaction()`:**
   a. Producer sends **`EndTxn(commit=true)`** to the coordinator.
   b. Coordinator writes a **PrepareCommit** entry to `__transaction_state` (durable decision). *From this instant the transaction* **will** *commit even if everyone crashes.*
   c. Coordinator sends **`WriteTxnMarkers`** requests to the leaders of every enrolled partition (`out1-0`, `out2-3`), instructing them to append a **commit control record** carrying this PID+epoch.
   d. Each partition leader appends the commit marker. Now the data records in those partitions are "committed" and become eligible to advance the LSO.
   e. Once all markers are acked, the coordinator writes **CompleteCommit** to `__transaction_state`. State → `Empty`. Done.

For an abort, replace `commit=true` with `false`, write **abort control records**, and consumers will skip the data records.

> Note the elegant trick: the *data* records were already written to the partitions during the transaction (step 3–4), *before* the commit decision. The control marker added at commit time is what flips them from "pending" to "committed/aborted." This is why an aborted transaction still leaves its data records physically on the log — they're just shadowed by an abort marker and skipped by `read_committed` consumers (and later removed by compaction/retention). This has real disk and consumer-throughput implications (see §6, §7).

#### Transaction timeout & the coordinator's watchdog

A transaction can't stay `Ongoing` forever. The producer declares `transaction.timeout.ms` (default **60000 ms = 60 s**); it must be `<= transaction.max.timeout.ms` on the broker (default **900000 ms = 15 min**). If the producer doesn't commit/abort within the timeout, the **coordinator proactively aborts** the transaction (writing abort markers), bumping the epoch to fence the stuck producer. This prevents one stuck producer from blocking the LSO of a partition forever (which would stall all `read_committed` consumers of that partition).

### 3.3 Layer 3 — the read_committed consumer and the LSO

#### What the consumer does

A consumer with `isolation.level=read_committed`:

- Only returns records up to the **LSO** of each partition. The LSO is the offset of the **first still-open (unresolved) transaction's first record** — i.e., everything below it is fully decided.
- **Buffers transactional records** until it sees the corresponding commit/abort marker. On commit → deliver them; on abort → discard them.
- **Skips abort markers and control records** entirely (they're invisible to your `poll()` loop) but accounts for their offsets — which is why *offsets in a `read_committed` topic can have gaps* (a consumer may jump from offset 7 to offset 10 because 8–9 were aborted data and a control marker). **Your code must never assume offsets are contiguous.**

#### LSO vs. HW (High Watermark)

- **High Watermark (HW)** — the highest offset that has been replicated to all in-sync replicas; the boundary of "durably committed to the cluster." A `read_uncommitted` consumer reads up to the HW.
- **LSO** — `min(HW, firstUnstableOffset)`. The `firstUnstableOffset` is the earliest offset belonging to an open transaction. So LSO ≤ HW. A `read_committed` consumer reads up to LSO.

If a long-running transaction stays open, the LSO is pinned just before it, and `read_committed` consumers **stall** even though new committed data may sit physically beyond it. This is the most common EOS production pain point (§9).

### 3.4 The consume-process-produce loop (the whole point)

The canonical EOS use case is a stream processor: read input → transform → write output, where the **consumed offset commit must be atomic with the produced output.** Naively:

```
records = consumer.poll()
output = process(records)
producer.send(output)
consumer.commitSync()   // <-- if we crash here, output written but offset not committed → reprocess → duplicate
```

The fix: commit the consumed offsets **as part of the producer's transaction**, using `producer.sendOffsetsToTransaction(offsets, groupMetadata)`. This writes the offsets to `__consumer_offsets` **inside** the same transaction as the output records, so the commit marker atomically commits *both* the output and the offset advance.

Internal flow:

1. `producer.beginTransaction()`.
2. For each consumed record, `producer.send(transformed)` to output topic(s). Partitions get enrolled via `AddPartitionsToTxn` as before.
3. `producer.sendOffsetsToTransaction(offsetsMap, consumer.groupMetadata())`. The producer sends an **`AddOffsetsToTxn`** to the coordinator (enrolling the `__consumer_offsets` partition for this group) and then a **`TxnOffsetCommit`**. The offsets are now pending inside the transaction.
4. `producer.commitTransaction()`. Coordinator writes commit markers to the output partitions **and** to the `__consumer_offsets` partition. Output + offset advance become visible atomically.

Because `groupMetadata` (since Kafka 2.5, KIP-447) carries the consumer group's generation, the coordinator can **fence by group generation** too — so a consumer that was rebalanced out can't sneak in a stale offset commit. KIP-447 is also what let a *single* `transactional.id` be reused across rebalances without exploding into one txn-id per partition; before it, you needed a `transactional.id` per input partition to stay correct under rebalances.

> Key consequence: in the consume-process-produce loop, **the consumer must NOT auto-commit and must NOT call `commitSync()` itself** — `enable.auto.commit=false`, and offsets flow only through `sendOffsetsToTransaction`. Mixing them reintroduces duplicates.

### 3.5 Putting the three layers together (the full data path)

```
            ┌─────────────────────────────────────────────────────────┐
            │ Producer (transactional.id=tx-A, PID=42, epoch=7)         │
            │  beginTransaction()                                       │
            │   send → out1-p0  (PID42,ep7,seq0..)  [transactional bit] │  Layer 1: idempotent
            │   send → out2-p3  (PID42,ep7,seq0..)                      │  per-partition dedup
            │   sendOffsetsToTransaction(in-topic offsets, group)       │  Layer 2: atomic
            │  commitTransaction()                                      │  across partitions
            └───────────────┬─────────────────────────────────────────┘  + offsets
                            │ EndTxn(commit)
                            ▼
            ┌─────────────────────────────────────────────────────────┐
            │ Transaction Coordinator (leader of __transaction_state P) │
            │  PrepareCommit → WriteTxnMarkers → CompleteCommit         │  2PC-style
            └───────────────┬─────────────────────────────────────────┘
              commit markers │ to out1-p0, out2-p3, __consumer_offsets-pX
                            ▼
            ┌─────────────────────────────────────────────────────────┐
            │ read_committed consumer: reads ≤ LSO, skips aborted data, │  Layer 3
            │ delivers only committed records (offsets may have gaps)   │
            └─────────────────────────────────────────────────────────┘
```

---

## 4. The complete toolkit

### 4.1 Producer configs

| Config | Purpose | Default | Notes |
|---|---|---|---|
| `enable.idempotence` | Turn on idempotent producer (Layer 1) | `true` (since 3.0) | Forces `acks=all`, `retries>0`, `max.in.flight<=5` |
| `transactional.id` | Stable id enabling transactions + fencing | `null` (no txns) | Setting it implies `enable.idempotence=true` |
| `transaction.timeout.ms` | Max duration of one txn before coordinator aborts | `60000` (60s) | Must be ≤ broker `transaction.max.timeout.ms` |
| `acks` | Replicas that must ack a write | `all` (when idempotent) | EOS requires `all` |
| `retries` | Retry count on retriable errors | `Integer.MAX_VALUE` (modern) | Bounded in practice by `delivery.timeout.ms` |
| `max.in.flight.requests.per.connection` | Unacked requests per connection | `5` | Must be ≤5 for idempotence to preserve order |
| `delivery.timeout.ms` | Total time a send may take incl. retries | `120000` (120s) | The real cap on retrying |
| `enable.metrics.push` | (3.7+) client metrics to broker | `true` | Observability, not EOS-specific |

### 4.2 Consumer configs

| Config | Purpose | Default | Notes |
|---|---|---|---|
| `isolation.level` | `read_committed` or `read_uncommitted` | `read_uncommitted` | Set `read_committed` to honor txns |
| `enable.auto.commit` | Auto-commit offsets periodically | `true` | **Set `false`** in consume-process-produce |
| `auto.offset.reset` | Where to start with no committed offset | `latest` | Orthogonal to EOS but relevant |

### 4.3 Broker configs

| Config | Purpose | Default |
|---|---|---|
| `transaction.state.log.replication.factor` | RF of `__transaction_state` | `3` (often `1` in single-broker dev) |
| `transaction.state.log.min.isr` | Min ISR for txn state log | `2` |
| `transaction.state.log.num.partitions` | Partitions of `__transaction_state` | `50` |
| `transaction.max.timeout.ms` | Upper bound on producer txn timeout | `900000` (15 min) |
| `transactional.id.expiration.ms` | Idle time before a `transactional.id`'s PID is expired | `604800000` (7 days) |
| `transaction.abort.timed.out.transaction.cleanup.interval.ms` | How often coordinator scans for timed-out txns | `10000` (10s) |
| `offsets.topic.replication.factor` | RF of `__consumer_offsets` | `3` |

> Dev gotcha: on a single-broker cluster, the defaults `transaction.state.log.replication.factor=3` and `offsets.topic.replication.factor=3` will fail because you can't satisfy RF=3 with one broker. Set both to `1` for local development.

### 4.4 Producer Java API (the EOS-relevant methods)

| Method | Purpose |
|---|---|
| `producer.initTransactions()` | One-time: register `transactional.id`, get PID/epoch, fence predecessors, abort their open txns |
| `producer.beginTransaction()` | Start a new transaction (client-local marker) |
| `producer.send(record [, callback])` | Produce a record; enrolls its partition in the txn on first write |
| `producer.sendOffsetsToTransaction(offsets, consumerGroupMetadata)` | Atomically include consumer-offset commit in the txn (KIP-447 signature) |
| `producer.commitTransaction()` | Commit: triggers PrepareCommit → markers → CompleteCommit |
| `producer.abortTransaction()` | Abort: triggers abort markers; data records are shadowed |
| `producer.flush()` | Block until buffered sends complete (commit also flushes) |

### 4.5 Kafka Streams (it does EOS for you)

| Config | Purpose | Notes |
|---|---|---|
| `processing.guarantee` | `at_least_once` or `exactly_once_v2` | Set `exactly_once_v2` (was `exactly_once`, deprecated; `_v2` = KIP-447, fewer producers, better scaling) |
| `commit.interval.ms` | How often Streams commits (and thus closes a txn) | Defaults to `100` ms under EOS (vs `30000` otherwise) — frequent small txns for low latency |

Kafka Streams manages `transactional.id`s, producers, and the consume-process-produce loop internally. With `exactly_once_v2` you get EOS for the whole topology essentially for free — this is the recommended path unless you have a reason to hand-roll.

### 4.6 CLI / admin tools

| Command | Purpose |
|---|---|
| `kafka-transactions.sh --list` | List active transactions (Kafka 3.0+) |
| `kafka-transactions.sh --describe --transactional-id <id>` | Inspect a txn's state, PID, epoch, partitions |
| `kafka-transactions.sh --find-hanging --topic <t>` | Find hanging transactions pinning the LSO |
| `kafka-transactions.sh --abort --topic <t> --partition <p> --start-offset <o>` | Manually abort a hanging txn (last resort) |
| `kafka-consumer-groups.sh --describe --group <g>` | Lag per partition (lag under read_committed is relative to LSO) |
| `kafka-dump-log.sh --files <segment>.log --print-data-log` | Inspect raw log incl. control records, PID/epoch/seq |

`kafka-dump-log.sh` is invaluable: it shows the `endTxnMarker`, `controlType: COMMIT/ABORT`, `producerId`, `producerEpoch`, and `baseSequence` of each batch — your ground truth when debugging.

---

## 5. Code examples by use case

All examples use the Java `org.apache.kafka:kafka-clients` library. Assume `bootstrap.servers` is set appropriately.

### 5.1 Idempotent producer only (the common, cheap case)

Use when you write events to Kafka and want no duplicates from retries, but don't need cross-partition atomicity.

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
// Since Kafka 3.0 this is the default, but be explicit for clarity and older clients:
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
// Idempotence implies these; setting them wrong throws ConfigException:
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
    for (int i = 0; i < 1000; i++) {
        // Even if a send is retried due to a transient error, the broker
        // dedupes via (PID, partition, sequence) — no duplicate on the log.
        producer.send(new ProducerRecord<>("events", "key-" + i, "payload-" + i));
    }
    producer.flush();
}
// NOTE: this does NOT survive a producer restart (new PID) and does NOT
// give cross-partition atomicity. For that, you need transactions (below).
```

### 5.2 Multi-topic atomic write (transactions, no consumer)

Use when one logical event must land atomically in several topics — e.g., write an order and its audit entry together.

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
// A STABLE, UNIQUE id per logical producer instance. Stable across restarts!
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "order-writer-1");
// (transactional.id implies enable.idempotence=true and acks=all)

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();   // handshake: PID+epoch, fence predecessors, abort their open txns

try {
    producer.beginTransaction();
    producer.send(new ProducerRecord<>("orders", "order-42", "{...order...}"));
    producer.send(new ProducerRecord<>("audit",  "order-42", "created order-42"));
    // Both records become visible together — or neither (on abort).
    producer.commitTransaction();
} catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
    // These are FATAL — the producer is poisoned; close and recreate it.
    producer.close();
} catch (KafkaException e) {
    // Retriable / abortable: abort this transaction and (optionally) retry the unit of work.
    producer.abortTransaction();
} finally {
    producer.close();
}
```

The exception handling is not boilerplate — it's correctness. `ProducerFencedException`, `OutOfOrderSequenceException`, and `AuthorizationException` are **fatal**: another instance fenced you, or the log integrity is broken, and you must abandon this producer. A generic `KafkaException` is **abortable**: call `abortTransaction()` and decide whether to retry.

### 5.3 Full consume-process-produce loop with EOS (the canonical example)

Use when you read from input topic(s), transform, write to output topic(s), and must not duplicate on crash/rebalance.

```java
// ---- Consumer (read_committed, NO auto-commit) ----
Properties cprops = new Properties();
cprops.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
cprops.put(ConsumerConfig.GROUP_ID_CONFIG, "txn-processor");
cprops.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
cprops.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
cprops.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");   // honor transactions
cprops.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);          // offsets flow ONLY via the txn

// ---- Transactional producer ----
Properties pprops = new Properties();
pprops.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
pprops.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
pprops.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
pprops.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "txn-processor-tx-1"); // stable, unique per instance

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cprops);
KafkaProducer<String, String> producer = new KafkaProducer<>(pprops);

consumer.subscribe(Collections.singletonList("input-topic"));
producer.initTransactions();

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
    if (records.isEmpty()) continue;

    try {
        producer.beginTransaction();

        // 1) Process each input record and produce output(s)
        for (ConsumerRecord<String, String> rec : records) {
            String out = transform(rec.value());                  // your business logic
            producer.send(new ProducerRecord<>("output-topic", rec.key(), out));
        }

        // 2) Build the offsets-to-commit map: NEXT offset to read per partition
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<String, String>> partRecs = records.records(tp);
            long lastOffset = partRecs.get(partRecs.size() - 1).offset();
            offsets.put(tp, new OffsetAndMetadata(lastOffset + 1));   // +1: next to consume
        }

        // 3) Commit consumed offsets ATOMICALLY inside the same transaction.
        //    groupMetadata() (KIP-447) lets the coordinator fence by group generation.
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

        // 4) Commit: output records + offset advance become visible together.
        producer.commitTransaction();

    } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException fatal) {
        // Another instance fenced us, or log integrity broke — stop.
        producer.close();
        consumer.close();
        throw fatal;
    } catch (KafkaException e) {
        // Abortable: roll back the transaction. On the next poll the SAME
        // input records are re-read (offsets weren't committed) and retried.
        producer.abortTransaction();
        // Rewind the consumer to the last committed position so we re-poll the same batch:
        for (TopicPartition tp : records.partitions()) {
            OffsetAndMetadata committed = consumer.committed(Collections.singleton(tp)).get(tp);
            consumer.seek(tp, committed == null ? 0 : committed.offset());
        }
    }
}
```

The three EOS-critical choices, restated: `enable.auto.commit=false`, `isolation.level=read_committed`, and offsets committed **only** via `sendOffsetsToTransaction` with `groupMetadata()`. On abort, we `seek` back so the rolled-back batch is re-read from the last *committed* position — otherwise we'd skip those records.

### 5.4 Kafka Streams with EOS (recommended for stream apps)

Use when your workload is a stream topology; let the framework own transactions.

```java
Properties props = new Properties();
props.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-eos");
props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
// THE one line that turns on exactly-once for the entire topology:
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

StreamsBuilder builder = new StreamsBuilder();
KStream<String, String> source = builder.stream("input-topic");
source.flatMapValues(v -> Arrays.asList(v.toLowerCase().split("\\W+")))
      .groupBy((k, word) -> word)
      .count()                      // stateful aggregate — duplicates here would corrupt counts
      .toStream()
      .to("counts-output", Produced.with(Serdes.String(), Serdes.Long()));

KafkaStreams streams = new KafkaStreams(builder.build(), props);
streams.start();
Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
// Streams atomically commits input offsets, changelog (state) writes, and output
// records in one transaction per commit.interval.ms (default 100ms under EOS).
```

### 5.5 Abort-and-retry with a poison message (defensive pattern)

Use when a record might fail processing and you don't want to wedge the partition forever.

```java
producer.beginTransaction();
try {
    for (ConsumerRecord<String, String> rec : records) {
        try {
            producer.send(new ProducerRecord<>("output-topic", rec.key(), riskyTransform(rec.value())));
        } catch (PoisonMessageException pme) {
            // Route bad records to a dead-letter topic WITHIN the same txn,
            // so the DLQ write and the offset advance are atomic.
            producer.send(new ProducerRecord<>("dead-letter", rec.key(), rec.value()));
        }
    }
    producer.sendOffsetsToTransaction(buildOffsets(records), consumer.groupMetadata());
    producer.commitTransaction();
} catch (KafkaException e) {
    producer.abortTransaction();   // entire batch rolls back; re-poll and retry
}
```

Note the DLQ write lives *inside* the transaction, so you never advance past a poison message without recording it somewhere — and you never double-write the DLQ entry.

---

## 6. Implementation concerns & best practices

### 6.1 Performance — what EOS costs

EOS is not free. The overheads:

- **Per-transaction round-trips.** Each transaction adds `AddPartitionsToTxn` (per new partition), `AddOffsetsToTxn`, `EndTxn`, and `WriteTxnMarkers` traffic to the coordinator and partition leaders. The fixed cost is amortized over the records in the transaction, so **bigger transactions amortize better** but increase latency and the window where the LSO can stall.
- **Latency floor.** A record isn't visible to `read_committed` consumers until its transaction commits and the commit marker is written. So end-to-end latency ≥ transaction duration. Kafka Streams under EOS defaults `commit.interval.ms` to **100 ms** to keep this low; hand-rolled loops trade latency for throughput by batching more per txn.
- **Consumer-side buffering.** `read_committed` consumers buffer open-transaction records in memory until resolution; very large open transactions can pressure consumer memory.
- **Disk amplification.** Aborted transactions' data records still occupy log space until retention/compaction reclaims them. Control markers add a small per-transaction overhead.
- **Rule of thumb (not a guarantee):** the original Confluent benchmark for idempotent + transactional producer reported roughly a **3–20% throughput overhead** depending on record size and transaction size — small records and tiny transactions hurt most because the fixed per-txn cost dominates. **Idempotence alone is nearly free** (a few % at most); transactions add the bulk of the cost. Treat these as ballpark; measure your workload.

Practical levers: commit fewer, larger transactions when latency permits; keep transactions short to avoid pinning the LSO; co-locate output partitions to reduce the number of `WriteTxnMarkers` recipients (marginal).

### 6.2 Correctness & concurrency

- **One live producer per `transactional.id`.** This is invariant #1. Two live producers sharing a `transactional.id` will continuously fence each other and make no progress, or worse, corrupt your atomicity assumptions. Map `transactional.id` to a stable logical shard (e.g., per input-partition assignment under the old model, or per task under KIP-447).
- **`transactional.id` must be stable across restarts** for fencing and recovery to work. A random UUID per boot defeats the purpose (new id → no predecessor to fence → zombie possible).
- **Don't mix manual offset commits with transactional offset commits.** Pick `sendOffsetsToTransaction` and set `enable.auto.commit=false`.
- **Handle the fatal exceptions as fatal.** Catching `ProducerFencedException` and retrying on the same producer is a bug.

### 6.3 The external-DB boundary (the big one)

**EOS guarantees exactly-once only within Kafka.** The instant your processing has a side effect outside Kafka — an INSERT into Postgres, a charge to Stripe, an email, a call to a downstream microservice — that side effect is **not** in the Kafka transaction. If you crash after the external write but before `commitTransaction()`, the transaction aborts and reprocesses, and the external write happens **again**. Kafka's commit marker can't un-charge a credit card.

Strategies for the boundary:

| Approach | How it achieves effective exactly-once | Tradeoff |
|---|---|---|
| **Idempotent sink** | External write is an upsert keyed by a business id; replays overwrite harmlessly | Requires a natural unique key; only works for idempotent operations |
| **Dedup table / processed-offset table** | Store `(topic, partition, offset)` you've handled in the same DB transaction as the side effect; skip if already present | Adds a DB round-trip; the DB transaction, not Kafka's, is the unit of atomicity |
| **Transactional outbox** | Write the side-effect intent to an *outbox table* in the DB transaction, then a separate relay publishes it to Kafka (or vice versa, CDC) | More moving parts; eventual not instant |
| **Two-phase commit / XA** | True distributed transaction across Kafka and DB | Rarely worth it; poor performance, operational pain, Kafka doesn't natively join XA |

The honest summary: **for Kafka→external-system flows, real-world "exactly once" is achieved by making the external write idempotent, not by Kafka transactions.** Use Kafka EOS for the Kafka-internal hops and an idempotency key at the external boundary.

### 6.4 Security

- Transactions require the producer to have **`Write`/`IdempotentWrite`** ACLs and **`TransactionalId`-scoped** ACLs (a `Write` ACL on the `TransactionalId` resource). Without them, `initTransactions()` fails with `TransactionalIdAuthorizationException`. Lock `transactional.id` ACLs down so a rogue client can't impersonate and fence your processor.
- The `__transaction_state` and `__consumer_offsets` topics are sensitive internal state — protect with broker ACLs; don't let arbitrary clients read/write them.

### 6.5 Observability

- **Producer metrics:** `txn-init-time-ns`, `txn-begin-time-ns`, `txn-send-offsets-time-ns`, `txn-commit-time-ns`, `txn-abort-time-ns` (timing of each phase); `record-error-rate`.
- **Broker metrics:** transaction coordinator metrics on `__transaction_state`; `LastStableOffsetLag` (how far LSO trails HW — a rising value means a long/hanging transaction); active-transaction counts.
- **Consumer lag under `read_committed`** is measured to the LSO, not the HW — so a hanging transaction shows up as growing lag even if the broker has newer data. Alert on `LastStableOffsetLag` and on growing read_committed lag with flat production rate.
- Use `kafka-transactions.sh --list/--describe/--find-hanging` for live state.

### 6.6 Testing

- **Integration-test with `EmbeddedKafkaCluster`** (in `kafka-streams-test-utils` / `kafka-clients` test jars) or **Testcontainers Kafka** to exercise real transaction semantics — mocks won't reproduce fencing or LSO behavior.
- **Inject failures:** kill the producer mid-transaction and assert no duplicates downstream; rebalance the consumer group and assert offset atomicity.
- **`TopologyTestDriver`** for Kafka Streams gives deterministic EOS-topology testing without a broker.
- Assert that `read_committed` consumers never see aborted data and that offsets can be non-contiguous.

### 6.7 Production hardening

- Set `transaction.state.log.replication.factor=3` and `min.isr=2` in production (not the dev `1`).
- Keep `transaction.timeout.ms` tight enough that a stuck producer is aborted before it pins the LSO for too long, but long enough to cover your worst-case processing time.
- Size `transaction.state.log.num.partitions` (default 50) for your number of distinct `transactional.id`s; many ids → coordinator load spreads across these partitions.
- Plan `transactional.id` allocation for elasticity: with KIP-447 (`exactly_once_v2`), Streams uses one producer per *thread/task* and reuses ids across rebalances, which scales far better than the old "one txn-id per input partition."

### 6.8 Anti-patterns

- Random/ephemeral `transactional.id` per restart → no fencing.
- Sharing one `transactional.id` across concurrent instances → mutual fencing / no progress.
- Forgetting `isolation.level=read_committed` on the consumer → you read uncommitted/aborted data and EOS is pointless downstream.
- Auto-commit left on in a transactional loop → duplicates.
- Assuming offsets are contiguous in a read_committed topic → off-by-N bugs.
- Treating Kafka EOS as covering an external DB write → silent duplicate side effects.
- Huge, long-running transactions → LSO stalls all read_committed consumers of those partitions.

---

## 7. Advanced topics & deep internals

### 7.1 KIP-98, KIP-129, KIP-447 — the lineage

- **KIP-98 (Kafka 0.11, June 2017)** introduced idempotent producer + transactions + control records + the `__transaction_state` coordinator. This is the foundation of everything above.
- **KIP-129** brought EOS to Kafka Streams (`exactly_once`).
- **KIP-447 (Kafka 2.5/2.6)** redesigned the consumer-offset side: `sendOffsetsToTransaction` now takes **`ConsumerGroupMetadata`**, enabling fencing by group generation and letting a single producer serve many partitions across rebalances. Kafka Streams `exactly_once_v2` uses this; it dramatically reduced the number of producers/transactional-ids needed (the old model needed one per input partition, exploding under scale). `exactly_once` (v1) is deprecated; **use `exactly_once_v2`.**

### 7.2 Why offsets have gaps — control records in detail

A commit/abort marker is a **control batch** occupying a real offset in the partition. Aborted *data* records also occupy real offsets. A `read_committed` consumer's `position()` advances over all of these but `poll()` only returns the visible, committed *data* records. So a consumer might receive offsets `…, 5, 6, 12, 13, …` where 7–11 were an aborted batch plus markers. The broker tells the consumer which offsets to skip via the **aborted-transactions list** in the fetch response (it includes, per fetched range, the list of aborted PIDs and their first offsets so the client can filter). This is also why **log compaction interacts subtly** with transactions: markers are retained until the transaction is fully out of the active window.

### 7.3 firstUnstableOffset and LSO computation

The broker maintains, per partition, the **`firstUnstableOffset`** — the offset of the first record belonging to a still-open transaction (the oldest `Ongoing` transaction touching this partition). `LSO = min(highWatermark, firstUnstableOffset)`. When that oldest transaction commits or aborts, `firstUnstableOffset` advances to the next open transaction (or to the HW if none), and the LSO jumps forward — at which point buffered records suddenly become deliverable. A single old open transaction therefore holds back the LSO regardless of how much newer committed data exists.

### 7.4 Coordinator failover

If the broker hosting a `__transaction_state` partition (the coordinator for some ids) dies, a follower becomes leader and **replays the `__transaction_state` log** to reconstruct each transaction's state. Transactions in `PrepareCommit`/`PrepareAbort` are completed (markers re-sent — idempotently, since markers carry PID/epoch); `Ongoing` transactions continue or eventually time out. This is why the decision is logged *before* markers are written: the prepare entry makes the outcome recoverable.

### 7.5 Hanging transactions

A **hanging transaction** is one whose data records and `firstUnstableOffset` are stuck on a partition but the coordinator no longer has matching state (historically caused by bugs around producer-id reuse, partition reassignment, or epoch races; several were fixed across 2.x–3.x). The symptom: LSO pinned, `read_committed` consumers stalled, `LastStableOffsetLag` climbing, but no producer is actually active. Detect with `kafka-transactions.sh --find-hanging --topic <t>`; resolve, as a last resort, with `kafka-transactions.sh --abort`. KIP-664 added these tooling commands precisely because hanging transactions used to require painful manual intervention.

### 7.6 Tuning knobs summary

- `transaction.timeout.ms` (producer) vs `transaction.max.timeout.ms` (broker) — bound how long the LSO can be pinned.
- `commit.interval.ms` (Streams) — latency/throughput trade under EOS (default 100 ms).
- `transactional.id.expiration.ms` (broker, default 7 days) — how long an idle id's PID is remembered; affects whether a long-dormant producer can resume vs. gets a fresh PID.
- `max.in.flight.requests.per.connection` ≤ 5 — required for in-order idempotent delivery.
- `linger.ms` / `batch.size` — affect how many records pack into each transactional batch (amortization).

### 7.7 Lesser-known behaviors

- **A transaction with zero produced records still costs a round-trip** if you call begin/commit; avoid empty transactions in tight loops.
- **`sendOffsetsToTransaction` enrolls a `__consumer_offsets` partition** in the transaction — that partition's LSO can be affected too, so a hanging txn can stall offset reads for that group.
- **Idempotence is per-connection-window:** if a connection drops and reconnects, the in-flight window is re-established; sequence tracking is per `(PID, partition)`, not per connection, so reconnection is safe.
- **`exactly_once_v2` requires broker ≥ 2.5;** brokers and clients have a compatibility matrix — mixing very old brokers with EOS clients can silently degrade or fail.

---

## 8. Tradeoffs & decision frameworks

### 8.1 The three guarantees compared

| Dimension | At-most-once | At-least-once | Exactly-once (EOS) |
|---|---|---|---|
| Duplicates | No | **Yes** | No |
| Loss | **Yes** | No | No |
| Producer config | `acks=0/1`, `retries=0` | `acks=all`, retries on | `transactional.id`, idempotence |
| Consumer | any | commit after process | `read_committed`, txn offsets |
| Throughput | highest | high | ~3–20% lower than at-least-once |
| Latency | lowest | low | higher (commit barrier) |
| Complexity | trivial | low | moderate–high |
| Covers external side effects | n/a | n/a | **No** |

### 8.2 Idempotent-only vs. full transactions

| | Idempotent producer only | Transactions |
|---|---|---|
| Dedup on retry (single partition) | ✅ | ✅ |
| Survives producer restart | ❌ (new PID) | ✅ (stable `transactional.id`) |
| Cross-partition atomicity | ❌ | ✅ |
| Atomic offset+output | ❌ | ✅ (`sendOffsetsToTransaction`) |
| Cost | negligible | meaningful |
| Reach for it when | plain event producing, dup-intolerant | stream processing, multi-topic atomicity |

### 8.3 EOS vs. at-least-once + idempotency keys

**Use EOS when:**
- The flow is Kafka→Kafka (consume-process-produce), especially stateful (aggregations, joins).
- A duplicate corrupts correctness (counts, balances) and there's no natural dedup key downstream.
- You're using Kafka Streams (just set `exactly_once_v2` — it's basically free to adopt).

**Prefer at-least-once + idempotency keys when:**
- The terminal sink is an **external system** (DB, payment API) — EOS doesn't cover it anyway, so add an idempotency key (business id / dedup table) and skip the Kafka transactional overhead on that hop.
- You need maximum throughput / lowest latency and can tolerate transient duplicates that the sink dedupes.
- The processing is naturally idempotent (upserts).

**Avoid EOS when:**
- You have one simple producer with no atomicity need → idempotent-only.
- Your bottleneck is latency and transactions push you over budget.
- Operational simplicity matters more than the last increment of correctness and you have a downstream dedup key.

### 8.4 Decision flow

```
Do you produce to Kafka only (no external side effect)?
  ├─ No → EOS won't cover the external write. Use at-least-once + idempotency key at the sink.
  └─ Yes →
       Do you need cross-partition atomicity OR atomic consume-process-produce?
         ├─ No → Idempotent producer only (default since 3.0). Done.
         └─ Yes →
              Are you using Kafka Streams?
                ├─ Yes → processing.guarantee = exactly_once_v2. Done.
                └─ No  → Hand-rolled transactions: stable transactional.id,
                          read_committed, sendOffsetsToTransaction, no auto-commit.
```

---

## 9. Failure modes & debugging

### 9.1 Hanging transaction pins the LSO

**Symptom:** `read_committed` consumers stop advancing; `kafka-consumer-groups.sh --describe` shows growing lag; broker `LastStableOffsetLag` climbs; production rate is normal.
**Diagnose:** `kafka-transactions.sh --find-hanging --topic <t>` lists hanging txns with PID/partition/start-offset. Cross-check with `kafka-transactions.sh --describe --transactional-id <id>` and `kafka-dump-log.sh` to see the open batch with no matching marker.
**Fix:** ensure no stuck producer (kill/restart it cleanly so the coordinator times the txn out and writes an abort marker). As a last resort, `kafka-transactions.sh --abort --topic <t> --partition <p> --start-offset <o>` writes an abort marker manually. Then the LSO advances and consumers resume.

### 9.2 ProducerFencedException storms

**Symptom:** producer repeatedly throws `ProducerFencedException` / `InvalidProducerEpochException`.
**Cause:** two processes share a `transactional.id` (e.g., a bad deploy ran the old and new pod concurrently, or two consumer-group members both assigned the same `transactional.id`). They bump each other's epochs forever.
**Fix:** enforce a single live instance per `transactional.id`; under KIP-447/Streams, let the framework manage ids. Confirm your id-assignment scheme maps one id ↔ one live owner.

### 9.3 Duplicates still appearing despite "EOS"

**Symptom:** downstream sees duplicates.
**Common causes & fixes:**
- Consumer isn't `read_committed` → set it.
- Auto-commit left on alongside transactional offsets → set `enable.auto.commit=false`.
- The duplicate is at an **external sink** not covered by EOS → add an idempotency key / dedup table.
- The producer uses a fresh `transactional.id` each run, so post-crash reprocessing isn't fenced → stabilize the id.
- Offsets committed *outside* the transaction (manual `commitSync`) → route through `sendOffsetsToTransaction`.

### 9.4 `OutOfOrderSequenceException`

**Symptom:** producer throws `OutOfOrderSequenceException` (fatal).
**Cause:** the broker saw a sequence gap — usually a lost batch, an unclean leader election that truncated the log, or `max.in.flight` > 5 with reordering. Can also indicate data loss on the partition.
**Fix:** investigate broker health / unclean leader elections (`unclean.leader.election.enable` should be `false` for EOS); keep `max.in.flight ≤ 5`; the producer must be recreated (`initTransactions` again) — the old instance is poisoned.

### 9.5 `TimeoutException` on `commitTransaction`

**Symptom:** commit times out.
**Cause:** coordinator unreachable, `__transaction_state` under-replicated (ISR shrunk below `min.isr`), or slow broker.
**Fix:** check `__transaction_state` ISR and broker health; ensure RF/min.isr are healthy; the transaction may still complete or be aborted by timeout — handle idempotently and re-derive state from committed offsets.

### 9.6 Coordinator unavailable / `COORDINATOR_NOT_AVAILABLE`

**Symptom:** `initTransactions` or sends fail finding the coordinator.
**Cause:** the `__transaction_state` partition leader is unavailable (broker down, election in progress).
**Fix:** transient — the client retries and rediscovers the new coordinator after leader election; ensure `__transaction_state` is replicated (RF ≥ 3) so a failover is possible.

### 9.7 Real-world incident shapes

- **The double-counting aggregate:** a Streams app on `at_least_once` rebalanced during a deploy, reprocessed a window, and inflated a billing metric ~2x for the overlap. Fix was `exactly_once_v2`. Lesson: stateful aggregates are exactly where EOS earns its cost.
- **The single-broker dev failure:** transactions fail with replication errors in local dev because `transaction.state.log.replication.factor` defaults to 3 on a one-broker cluster. Fix: set RF/offsets-RF to 1 locally.
- **The phantom duplicate charge:** a team believed Kafka EOS covered their Stripe call; a crash between the external charge and `commitTransaction` re-charged customers. Fix: idempotency key on the Stripe request. Lesson: the external boundary is not covered.

---

## 10. Interview drill

**Q1. What does "exactly once" actually guarantee in Kafka, and where does it stop?**
Model answer: It guarantees each record's effect is reflected exactly once *within Kafka's boundary* — Kafka-to-Kafka consume-process-produce, including the offset commit — even across retries, broker failures, and rebalances. It does **not** cover side effects to external systems; a DB write or API call inside your processing is outside the Kafka transaction and can be duplicated on reprocess.
- Probe: *So how do you get effective exactly-once to a database?* → Idempotency key / upsert, or a processed-offsets dedup table written in the DB's own transaction, or transactional outbox.
- Probe: *Within Kafka, what are the three mechanisms?* → Idempotent producer (per-partition dedup), transactions (cross-partition + offset atomicity), `read_committed` consumer (LSO).
- Probe: *Does idempotence alone survive a producer restart?* → No; restart gives a new PID. Only a stable `transactional.id` survives.

**Q2. How does the idempotent producer prevent duplicates?**
Model answer: The broker assigns a PID; the producer stamps each batch with `(PID, epoch, base sequence)` per partition. The leader tracks the last accepted sequence per `(PID, partition)`; a retried batch with an already-seen sequence is discarded but acked, so no duplicate hits the log, and an out-of-order sequence is rejected.
- Probe: *Why `max.in.flight ≤ 5`?* → The broker tracks a sliding window of 5 batches to dedupe and preserve order; beyond that it can't guarantee both.
- Probe: *Why does it force `acks=all`?* → Dedup state must be on a durably-replicated record; otherwise a leader failover could lose the sequence state.

**Q3. Walk through what `commitTransaction()` does internally.**
Model answer: Producer sends `EndTxn(commit)` to the transaction coordinator; coordinator durably logs `PrepareCommit` to `__transaction_state` (decision now recoverable); coordinator sends `WriteTxnMarkers` to every enrolled partition leader, which append commit control records; once acked, coordinator logs `CompleteCommit`. Data records (written earlier) now flip to committed and can advance the LSO.
- Probe: *Why log the decision before writing markers?* → For recovery: if the coordinator fails, replaying the log replays the decision and re-sends markers idempotently.
- Probe: *What advances the LSO?* → The commit/abort marker resolves the partition's oldest open transaction, moving `firstUnstableOffset` forward.

**Q4. What is the LSO and why does a `read_committed` consumer care?**
Model answer: The Last Stable Offset is `min(HW, firstUnstableOffset)` — the highest offset below which all transactions are resolved. A `read_committed` consumer only reads up to the LSO and buffers/filters open or aborted records, so it never sees uncommitted data.
- Probe: *What happens to consumers if a transaction hangs open?* → The LSO is pinned just before it; consumers stall, lag grows, `LastStableOffsetLag` climbs even though newer committed data may exist physically.
- Probe: *Why might offsets have gaps?* → Aborted data records and control markers occupy real offsets but aren't delivered.

**Q5. Explain `transactional.id`, producer epochs, and fencing.**
Model answer: `transactional.id` is a stable, user-chosen identity per logical producer. On `initTransactions`, the coordinator bumps the epoch for that id and aborts any prior open transaction. An older instance still holding the prior epoch gets `ProducerFenced` on its next transactional request and must die — guaranteeing a single live writer per id (zombie fencing).
- Probe: *Why must the id be stable across restarts?* → So the restarted/replacement instance fences the old one and recovers state; a random id can't fence a zombie.
- Probe: *What goes wrong if two instances share an id?* → Continuous mutual fencing — no progress.

**Q6. How do you make the consume-process-produce loop exactly-once?**
Model answer: Use a transactional producer; `enable.auto.commit=false`; `isolation.level=read_committed`; inside one transaction, produce outputs and call `sendOffsetsToTransaction(offsets, consumer.groupMetadata())`, then `commitTransaction()`. The offset advance and output become atomic.
- Probe: *What does `groupMetadata()` add (KIP-447)?* → Group-generation fencing so a rebalanced-out consumer can't commit stale offsets, and it lets one producer serve many partitions across rebalances.
- Probe: *On abort, what must you do with the consumer?* → `seek` back to the last committed offset so the rolled-back batch is re-read.

**Q7 (senior-signal). When would you choose at-least-once + idempotency keys over Kafka EOS, and why?**
Model answer: When the terminal sink is an external system EOS can't cover anyway (so I need an idempotency key regardless), or when latency/throughput budgets can't absorb the transactional overhead, or when the operation is naturally idempotent (upsert). EOS adds round-trips, a commit-latency floor, LSO-stall risk, and operational complexity (txn-id management, hanging-txn handling). If a business-key upsert downstream already neutralizes duplicates, EOS buys little. I reserve EOS for Kafka-internal, stateful pipelines where duplicates corrupt correctness and there's no downstream dedup key.
- Probe: *Quantify the EOS cost.* → Idempotence ~negligible; transactions ~3–20% throughput depending on record/txn size, plus a latency floor of the commit interval; measure your workload.
- Probe: *How does Streams change the calculus?* → With `exactly_once_v2` it's one config line and the framework owns the hard parts, so the adoption cost is low for Streams topologies.

**Q8 (senior-signal). You see `read_committed` consumer lag climbing while producers look healthy. Diagnose.**
Model answer: Likely a long-running or hanging transaction pinning the LSO. Check broker `LastStableOffsetLag`; run `kafka-transactions.sh --find-hanging --topic <t>`; inspect with `--describe --transactional-id` and `kafka-dump-log.sh` for an open batch with no commit/abort marker. If a producer is stuck, restart it so the coordinator times the txn out; otherwise manually `--abort` the hanging txn as a last resort. Prevent by tightening `transaction.timeout.ms` and avoiding huge long transactions.
- Probe: *Why doesn't more data being produced help?* → New committed data sits beyond the pinned LSO and stays invisible until the oldest open txn resolves.
- Probe: *What metric do you alert on?* → `LastStableOffsetLag` rising and read_committed lag rising with flat production.

**Q9 (senior-signal). Design EOS for a service that consumes orders, updates a Postgres balance, and emits a confirmation to Kafka.**
Model answer: Kafka EOS does not cover the Postgres write, so I won't rely on it for the DB. Pattern: make the DB update idempotent — e.g., a processed-offsets table (or business-id dedup) updated in the *same Postgres transaction* as the balance change; on replay, skip if the offset/key is already recorded. The Kafka confirmation emit + input-offset commit can use a Kafka transaction for the Kafka hop, but the source of truth for "did I apply this order" is the DB transaction. Optionally use a transactional outbox so the confirmation is emitted exactly once relative to the DB commit. I'd avoid XA/2PC across Kafka and Postgres due to operational cost.
- Probe: *Where's the dedup boundary?* → The DB transaction (offset/business-id table) — that's what neutralizes duplicate processing on replay.
- Probe: *What if the Kafka emit succeeds but the DB commit fails?* → Use outbox/CDC so the emit is derived from the committed DB state, not done independently.

**Q10. What's the difference between `exactly_once` and `exactly_once_v2` in Kafka Streams?**
Model answer: `exactly_once` (KIP-129) used one producer per input partition (a `transactional.id` per partition), which scaled poorly. `exactly_once_v2` (KIP-447) uses `sendOffsetsToTransaction` with consumer group metadata to fence by group generation, so a single producer serves many partitions and ids are reused across rebalances — far fewer producers, better scaling. `exactly_once` is deprecated; use `_v2`.
- Probe: *Broker requirement for v2?* → Brokers ≥ 2.5.
- Probe: *What broke under v1 at scale?* → Producer/txn-id explosion and rebalance overhead.

**Q11. What are the fatal vs. abortable producer exceptions in a transaction, and how do you handle each?**
Model answer: Fatal: `ProducerFencedException`, `OutOfOrderSequenceException`, `AuthorizationException` (incl. `TransactionalIdAuthorizationException`) — the producer is poisoned; close it (and recreate for a fresh `initTransactions`). Abortable: generic `KafkaException` (and many retriable errors surfaced during the txn) — call `abortTransaction()` and retry the unit of work. Catching fatal ones and continuing is a bug.
- Probe: *Why is fencing fatal?* → Another instance owns the id; you must yield.
- Probe: *After abort, how do you avoid skipping records?* → `seek` the consumer back to the last committed offset.

**Q12. Why can an aborted transaction still occupy disk space?**
Model answer: Data records are physically appended to the partition log during the transaction, *before* the commit/abort decision. An abort writes an abort marker; the data records remain on disk (shadowed, skipped by `read_committed`) until normal retention/compaction reclaims them. So aborts cost disk and consumer-filter work, not just a logical no-op.
- Probe: *Then how do consumers skip them?* → The fetch response carries an aborted-transactions list; the client filters those offsets.
- Probe: *Implication for sizing?* → Frequent aborts inflate log size temporarily and add consumer-side filtering overhead.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability; the classic database transaction guarantees.
- **acks** — Producer setting for how many replicas must acknowledge a write (`0`, `1`, `all`).
- **At-least-once** — Delivery guarantee that never loses but may duplicate records.
- **At-most-once** — Delivery guarantee that never duplicates but may lose records.
- **Abort marker** — Control record marking a transaction as aborted on a partition.
- **AddPartitionsToTxn / AddOffsetsToTxn** — Requests enrolling a partition (or the offsets-topic partition) into the current transaction.
- **Broker** — A single Kafka server process.
- **Commit marker** — Control record marking a transaction as committed on a partition.
- **Consumer group** — A set of consumers sharing a `group.id` that divide a topic's partitions.
- **Control record / control batch** — A non-application record (commit/abort marker) written to the log; invisible to `poll()` but occupies an offset.
- **Coordinator (transaction)** — The broker (leader of a `__transaction_state` partition) that drives a transaction's state machine.
- **Epoch (producer)** — Monotonically increasing integer per `transactional.id`; the latest fences older instances.
- **EOS** — Exactly-Once Semantics.
- **Fencing** — Cutting off a stale/zombie process so its operations can't corrupt shared state.
- **firstUnstableOffset** — Earliest offset belonging to an open transaction on a partition.
- **High Watermark (HW)** — Highest offset replicated to all in-sync replicas; boundary of durable commit; read by `read_uncommitted` consumers.
- **Idempotent** — Repeatable without changing the result beyond the first application.
- **In-sync replica (ISR)** — Replicas currently caught up enough to be considered in sync.
- **Isolation level** — Consumer setting `read_committed` or `read_uncommitted`.
- **Kafka Streams** — Kafka's stream-processing library; provides EOS via `processing.guarantee`.
- **KIP** — Kafka Improvement Proposal (design doc). KIP-98 (txns), KIP-129 (Streams EOS), KIP-447 (consumer-group-metadata EOS), KIP-664 (txn tooling).
- **Last Stable Offset (LSO)** — `min(HW, firstUnstableOffset)`; the read boundary for `read_committed` consumers.
- **Offset** — A record's integer position within a partition.
- **Partition** — An ordered, append-only shard of a topic.
- **PID (Producer ID)** — Broker-assigned 64-bit id used with sequence numbers for dedup.
- **Producer fencing** — Using epochs to lock out an older producer sharing a `transactional.id`.
- **read_committed / read_uncommitted** — Consumer isolation levels (committed-only vs. everything).
- **Sequence number** — Per-`(PID, partition)` counter on each batch enabling broker dedup.
- **Topic** — A named stream of records, split into partitions.
- **Transaction** — An atomic unit of multi-partition writes (and offset commits) in Kafka.
- **transactional.id** — User-chosen stable id enabling transactions and fencing.
- **transaction.timeout.ms / transaction.max.timeout.ms** — Producer/broker bounds on transaction duration.
- **`__transaction_state`** — Internal compacted topic storing transaction metadata; its partition leaders are coordinators.
- **`__consumer_offsets`** — Internal topic storing committed consumer offsets; written transactionally by `sendOffsetsToTransaction`.
- **Two-phase commit (2PC)** — Prepare-then-commit distributed-transaction protocol; Kafka uses a specialized internal variant.
- **WriteTxnMarkers** — Coordinator request instructing partition leaders to append commit/abort control records.
- **Zombie** — A presumed-dead process still alive and capable of issuing stale writes.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Three layers:** (1) idempotent producer = per-partition dedup via `(PID, epoch, seq)`; (2) transactions = cross-partition + offset atomicity via coordinator + control markers; (3) `read_committed` consumer = reads ≤ LSO, skips aborted data.

**Boundary:** EOS = exactly-once *inside Kafka only*. External DB/API writes are NOT covered → use idempotency keys.

**Key configs:**
- Producer: `enable.idempotence=true` (default ≥3.0), `transactional.id=<stable>`, `transaction.timeout.ms=60000`, `acks=all`, `max.in.flight≤5`.
- Consumer: `isolation.level=read_committed`, `enable.auto.commit=false`.
- Broker: `transaction.state.log.replication.factor=3`, `min.isr=2`, `num.partitions=50`, `transaction.max.timeout.ms=900000`, `transactional.id.expiration.ms=7d`.
- Streams: `processing.guarantee=exactly_once_v2`, `commit.interval.ms=100` (EOS default).

**Loop recipe:** beginTransaction → send outputs → `sendOffsetsToTransaction(offsets, consumer.groupMetadata())` → commitTransaction. On abort → `abortTransaction()` + `seek` back.

**Commit internals:** EndTxn → PrepareCommit (logged) → WriteTxnMarkers → CompleteCommit.

**LSO** = `min(HW, firstUnstableOffset)`. Hanging txn → LSO pinned → consumers stall → watch `LastStableOffsetLag`.

**Fatal exceptions:** `ProducerFenced`, `OutOfOrderSequence`, `Authorization` → close producer. **Abortable:** `KafkaException` → abort + retry.

**Cost:** idempotence ≈ free; transactions ≈ 3–20% throughput + commit-latency floor.

**Tooling:** `kafka-transactions.sh --list/--describe/--find-hanging/--abort`; `kafka-dump-log.sh`; `kafka-consumer-groups.sh --describe`.

**Numbers to memorize:** PID = 64-bit; `max.in.flight ≤ 5`; `__transaction_state` 50 partitions, RF 3; txn timeout 60s (max 15min); txn-id expiration 7 days; Streams EOS commit 100ms; idempotence default since 3.0; transactions since 0.11; KIP-447 since 2.5.

### Self-test (no answers — recall actively)

1. Trace, step by step, what happens from `commitTransaction()` to a `read_committed` consumer seeing the data — naming every broker request and log entry involved.
2. Two pods of your service briefly run with the same `transactional.id` during a rolling deploy. Exactly what happens to each, and why is the system still safe?
3. Your `read_committed` consumer lag is rising but `bytes-in-per-sec` shows producers are healthy and committing. List your diagnostic steps and the most likely root cause.
4. Explain precisely why Kafka EOS does not make a write to an external Postgres exactly-once, and give two concrete patterns that do.
5. Why does the idempotent producer require `acks=all` and `max.in.flight.requests.per.connection ≤ 5`? What breaks if you violate each?
6. Describe how the LSO is computed and why a single long-open transaction can stall consumers of a partition holding gigabytes of newer committed data.
7. Compare `exactly_once` vs `exactly_once_v2` in Kafka Streams: what changed, which KIP, and what problem at scale did it solve?

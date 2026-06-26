# Delivery Semantics

> **Concept area:** Distributed Transactions & Consistency Patterns
> **Subtopic:** Delivery Semantics (at-most-once, at-least-once, exactly-once / effectively-once)
> **Audience:** Senior Java/JVM backend engineers who want to master this end-to-end — design, operate, debug, teach, and interview.

---

## 1. Overview & where it fits

### What "delivery semantics" means

When one process sends a message to another over an unreliable medium (a network, a queue, a log), **delivery semantics** is the *contract* about how many times the receiver will end up acting on that message in the presence of failures: crashes, timeouts, retries, network partitions, and restarts.

There are three classical guarantees:

- **At-most-once:** every message is delivered **zero or one** times. Duplicates never happen; **loss is possible**.
- **At-least-once:** every message is delivered **one or more** times. Loss never happens (assuming the message was durably accepted); **duplicates are possible**.
- **Exactly-once:** every message is delivered **exactly one** time. No loss, no duplicates.

The trap, and the single most important idea in this whole chapter:

> **True end-to-end "exactly-once *delivery*" over an asynchronous network with crashes is impossible.** What is achievable, and what real systems actually give you, is **"exactly-once *processing*"** — also called **"effectively-once."** You get there by combining *at-least-once delivery* with **deduplication and/or idempotency** so that duplicates have no *observable effect*.

Keep that distinction sharp: **delivery** is about how many physical copies arrive; **processing/effect** is about how many times the *state of the world* changes. You cannot prevent the duplicate from arriving, but you can make the second arrival a no-op.

### The problem it solves

Distributed systems fail partially. A sender transmits a message, then the network drops the acknowledgment (ack). The sender doesn't know whether the receiver got it. It has exactly two safe choices:

1. **Retry** the send → if the receiver *did* get the first copy, you now have a **duplicate**. (This is the at-least-once path.)
2. **Don't retry** → if the receiver *didn't* get it, the message is **lost**. (This is the at-most-once path.)

There is no third option that magically knows the truth, because **the only way to learn the outcome is another message, which can also be lost.** This is the heart of why exactly-once delivery is impossible — we prove it rigorously in §7. Delivery semantics is the framework for *choosing which failure you can tolerate* and *engineering around it*.

> **Beginner aside — "ack" (acknowledgment):** a small reply message the receiver sends back saying "I got it (and optionally, I durably stored / processed it)." Acks are how the sender learns whether to stop retrying. Almost every delivery guarantee is really a story about *when* the ack is sent and *what* it promises.

> **Beginner aside — "idempotent":** an operation is idempotent if applying it many times has the same effect as applying it once. `SET balance = 100` is idempotent; `balance = balance + 100` (an increment) is **not**. Idempotency is the workhorse that turns at-least-once delivery into effectively-once processing.

### When you reach for each

| You care most about… | Pick | Classic example |
|---|---|---|
| Lowest latency, loss is acceptable | At-most-once | Metrics samples, live video frames, dashboard pings, cache invalidation hints |
| Never losing data, can dedupe later | At-least-once | Order events, financial postings, audit logs, anything that mutates important state |
| No loss **and** no observable duplicate | Effectively-once (at-least-once + idempotency/transactions) | Payments, inventory decrement, "send exactly one email," stream aggregation |

### One-paragraph mental model

Picture a sender holding a message and a stopwatch, and a receiver holding a ledger. The sender writes "please apply this" and waits for the receiver's ack. **Three knobs decide everything:** (1) *when does the sender give up retrying* (never = at-least-once; immediately = at-most-once); (2) *does the receiver remember what it has already applied* (a dedup table / offset / sequence number); (3) *is the receiver's action idempotent or wrapped in a transaction that also records "I did this."* If you retry forever **and** the receiver remembers (or its action is idempotent), the world changes exactly once even though the message may physically arrive many times. That is effectively-once, and it is the target for almost all important systems.

---

## 2. Foundations from first principles

### 2.1 The actors and the medium

- **Producer / sender / publisher:** the component that originates a message.
- **Broker / queue / log:** an intermediary that stores messages durably and hands them out (Kafka, RabbitMQ, Amazon SQS, ActiveMQ, NATS). Not all pipelines have one (RPC is producer→consumer directly), but most async ones do.
- **Consumer / receiver / subscriber:** the component that reads and acts on the message.
- **The medium:** an **asynchronous network** — messages can be delayed arbitrarily, reordered, duplicated, or dropped. We do **not** assume bounded delay. This assumption matters enormously (see FLP, §7).

> **Beginner aside — "broker":** a server that sits between producers and consumers, accepts messages, stores them (often replicated to disk on multiple machines), and delivers them. It decouples producer and consumer in time (consumer can be offline), space (they need not know each other), and rate (buffering absorbs bursts).

### 2.2 The fundamental uncertainty: the "two generals" of acks

Two events the sender cares about:
1. Did the message **arrive**?
2. Did the receiver **process/persist** it?

The sender learns both only via a return message (the ack). If the ack is lost, the sender is stuck in ambiguity. This is the **Two Generals Problem**: two generals must coordinate an attack by messengers crossing enemy territory; no finite exchange of messengers can make both *certain* the other will attack, because the last messenger might be the one that's captured. The formal consequence: **no protocol over an unreliable channel can guarantee both parties agree with certainty using a bounded number of messages.**

> **Beginner aside — "Two Generals Problem":** the canonical impossibility result for reliable communication over a lossy channel. Its practical lesson: you can drive the *probability* of disagreement arbitrarily low with retries, but you can never reach *certainty*. Hence: design for "the ack might be lost," which forces you to choose retry-and-dedupe (at-least-once + idempotency) or give up (at-most-once).

### 2.3 Defining the three semantics precisely

Let `apply(m)` be the effect of message `m` on the receiver's state (write a row, increment a counter, send an email). Let `N(m)` be the number of times `apply(m)` actually executes across all retries and crashes.

- **At-most-once:** `N(m) ∈ {0, 1}`. The producer/consumer does not retry past the first attempt (or the consumer acks *before* processing). Implementation: **fire-and-forget**, or **ack-then-process**.
- **At-least-once:** `N(m) ∈ {1, 2, 3, …}`. The producer retries until it gets an ack; the consumer acks *after* processing. Implementation: **process-then-ack with retries**.
- **Exactly-once (processing / effectively-once):** the *observable* effect equals `N(m) = 1` even if the physical message arrives multiple times, because duplicates are detected and suppressed (dedup) or the operation is idempotent or the whole thing is in a transaction. The physical `apply` may be *attempted* many times, but committed once.

The crucial reframing: **exactly-once is not a fourth delivery channel; it is at-least-once delivery plus an effect-suppression mechanism.**

### 2.4 Where the ack is placed determines the semantic

This is the single most useful first-principles lens. Consider a consumer with three steps: **receive → process → ack**.

| Ordering | Crash window | Result |
|---|---|---|
| receive → **ack** → process | crash after ack, before process | message lost → **at-most-once** |
| receive → process → **ack** | crash after process, before ack | redelivery → reprocess → **at-least-once** |
| receive → process(idempotent / in txn with offset) → ack | crash anywhere | duplicate suppressed → **effectively-once** |

Memorize: **"ack before work = at-most-once; ack after work = at-least-once; ack after idempotent work = effectively-once."**

### 2.5 Duplicates, retries, and acks — the trinity

- **Retry:** re-sending a message because no ack arrived in time. The *source* of every duplicate.
- **Duplicate:** a redundant copy of a message. Arises from producer retries, broker redelivery after consumer crash, or rebalancing.
- **Ack/Nack:** positive vs negative acknowledgment. A **nack** says "I couldn't process it, redeliver (or dead-letter) it."
- **Timeout / visibility timeout / ack deadline:** how long the broker waits for an ack before assuming the consumer died and redelivering. Too short → spurious duplicates; too long → slow recovery.

> **Beginner aside — "redelivery":** when a broker hands the same message to a consumer again because the previous attempt wasn't acked in time (or was nacked). Redelivery is the broker-side cause of duplicates; producer retries are the producer-side cause.

### 2.6 Idempotency keys, dedup, and sequence numbers

Three mechanisms turn at-least-once into effectively-once:

1. **Idempotency key (dedup ID):** a unique ID attached to each *logical* operation. The receiver records processed IDs and skips repeats. (`PUT /payments` with `Idempotency-Key: abc123`.)
2. **Sequence numbers / offsets:** monotonic counters per producer or per partition. The receiver knows "I've processed up to N" and discards anything ≤ N. (Kafka's idempotent producer, TCP, log offsets.)
3. **Idempotent operation design:** the operation is naturally repeat-safe (`UPSERT`, `SET`, `DELETE`, conditional writes).

### 2.7 End-to-end principle

The **end-to-end argument** (Saltzer, Reed, Clark, 1984): a correctness property is only truly guaranteed if it's enforced at the *endpoints*, because every intermediate hop can fail independently. Practically: even if Kafka gives you exactly-once *inside* Kafka, the moment your data leaves Kafka for a database or an external API, **you** are responsible for the dedup/idempotency there. There is no global exactly-once you can buy; you assemble it segment by segment with the weakest segment defining the whole.

---

## 3. How it works internally

This is the heart of the chapter. We trace, step by step, how each semantic is produced under the hood across the three layers: **producer → broker → consumer**, and then how brokers implement transactional exactly-once.

### 3.1 At-most-once internals

**Producer side (fire-and-forget):**
1. Producer serializes message, writes to socket buffer.
2. Producer returns immediately; does **not** wait for broker ack (`acks=0` in Kafka).
3. If the TCP segment is lost, or the broker leader crashes before persisting, the message is gone. No retry.

**Consumer side (ack-then-process / auto-commit-early):**
1. Consumer fetches message.
2. Consumer **commits the offset / acks** immediately.
3. Consumer processes. If it crashes mid-process, the offset already advanced → message never reprocessed → lost.

**State machine:**
```
SENT ──(network ok)──> RECEIVED ──> PROCESSED
   └──(network lost)──> ⊗ (lost, no retry)
```
No retry edges. Minimal latency, minimal bookkeeping, lossy.

### 3.2 At-least-once internals

**Producer side (retry until acked):**
1. Producer sends message, starts a timer.
2. Broker persists message to its log (and replicas), sends ack.
3. If ack not received before timeout → producer **resends**. (Kafka `acks=all`, `retries>0`, `delivery.timeout.ms`.)
4. If the broker had *already* persisted the first copy but the ack was lost, the resend creates a **duplicate in the log** — unless an idempotent producer is enabled (§3.4).

**Consumer side (process-then-ack):**
1. Consumer fetches message; broker marks it "in-flight / invisible" and starts a visibility/ack timer.
2. Consumer processes (writes DB, calls API).
3. Consumer **acks/commits offset** only after processing succeeds.
4. If the consumer crashes between step 2 and 3, the visibility timer expires and the broker **redelivers** → reprocessing → duplicate effect.

**State machine:**
```
SENT ──> RECEIVED ──> PROCESSING ──ack──> DONE
                          │
                          └──crash/timeout──> REDELIVERED ──> PROCESSING (again)
```
The redelivery loop is the source of duplicates. Loss is impossible (the message stays until acked), duplicates are guaranteed-possible.

### 3.3 Effectively-once internals (consumer-side dedup / idempotency)

You start from at-least-once and add a suppression step. Two implementations:

**(A) Dedup table (idempotency key):**
1. Each message carries a unique key `k`.
2. Consumer, in a **single transaction**: `INSERT INTO processed(k) VALUES (k)` with a unique constraint, then perform the business write, then commit.
3. On redelivery, the `INSERT` violates the unique constraint → transaction aborts/skips business write → effect applied once.
4. Ack the broker. If crash before ack, redelivery hits the dedup row → no-op → safe.

**(B) Idempotent write (no separate table):**
1. The business operation itself is conditional: `UPDATE accounts SET balance=100 WHERE id=7` (absolute set) or `INSERT … ON CONFLICT DO NOTHING`.
2. Redelivery re-runs the same write; the state is already correct → no observable change.

**Key subtlety — atomicity of "do work + record done":** the dedup record and the business effect **must be in the same transaction** (or the business effect must itself be idempotent). If you write the business effect, crash, then never record the dedup key, the redelivery reprocesses. If you record the dedup key, crash, then never do the business effect, you lose the effect. The transaction binds them.

### 3.4 Kafka idempotent producer (broker-side dedup)

Kafka stops *producer-retry* duplicates from entering the log:
1. Enable `enable.idempotence=true` (default `true` since Kafka 3.0).
2. On first connect, the producer is assigned a **Producer ID (PID)** by the broker.
3. Each message carries `(PID, partition, sequence number)`. Sequence numbers are monotonically increasing per partition.
4. The broker tracks the last sequence number per `(PID, partition)`. A resend with a sequence number ≤ last-seen is **discarded** (duplicate). A gap (sequence too high) is rejected (`OutOfOrderSequenceException`).
5. Result: **no duplicates from producer retries within a producer session.** (This is per-session; a producer restart gets a new PID unless transactions with a stable `transactional.id` are used.)

> **Beginner aside — "PID (Producer ID)":** an internal Kafka identifier the broker assigns to a producer so it can recognize that producer's messages and detect re-sends. It's how Kafka deduplicates retries without your code doing anything.

### 3.5 Kafka transactions & exactly-once semantics (EOS)

Kafka offers **exactly-once *within* a read-process-write Kafka topology** (consume from topic A, produce to topic B, all in Kafka):

1. Producer configured with a stable `transactional.id` (e.g., `payments-processor-1`). On init, the broker **fences** any older producer using the same `transactional.id` (assigns a higher epoch; the old zombie's writes are rejected).
2. `producer.initTransactions()` → `beginTransaction()`.
3. Producer writes output records to topic B **and** writes the *consumer offsets* for topic A via `sendOffsetsToTransaction(...)` — both inside the transaction.
4. `commitTransaction()` writes a **transaction marker (commit marker)** to all involved partitions atomically via the **transaction coordinator** (a broker component) using a 2-phase-commit-like protocol logged in an internal `__transaction_state` topic.
5. Consumers reading topic B with `isolation.level=read_committed` only see records once the commit marker is present; aborted-transaction records are skipped.
6. Because the *offset commit* and the *output produce* are atomic, a crash-restart never reprocesses-and-republishes: either both happened or neither.

> **Beginner aside — "2-phase commit (2PC)":** a protocol for committing a transaction across multiple participants. A coordinator first asks everyone to *prepare* (phase 1: "can you commit?"), and only if all say yes does it tell everyone to *commit* (phase 2). It guarantees all-or-nothing across participants but blocks if the coordinator dies mid-protocol. Kafka's transaction commit is 2PC-like but durable (the coordinator's state is in a replicated log, so it recovers).

> **Beginner aside — "fencing / epoch":** to prevent a "zombie" old instance (one everyone thought was dead) from corrupting state, the system assigns each new incarnation a higher number (epoch). Writes carrying an old epoch are rejected ("fenced off"). Kafka fences zombie producers by `transactional.id` epoch.

**Critical scope limit:** Kafka EOS is exactly-once **only for the Kafka-to-Kafka path** (and offset commit). The instant you write to an external DB or call an external API, Kafka's transaction can't cover it — you're back to needing idempotency at that sink (end-to-end principle).

### 3.6 SQS internals

Amazon SQS comes in two flavors:

- **Standard queue:** **at-least-once** delivery, best-effort ordering. Duplicates are possible (and normal). High throughput.
- **FIFO queue:** **exactly-once *processing within the dedup window*** and strict ordering per message group. SQS deduplicates using either a `MessageDeduplicationId` you supply or a content hash, within a **5-minute** dedup window. Throughput is limited (300 msg/s, 3000 with batching, per the documented baseline; high-throughput mode raises this).

**SQS visibility timeout mechanism (both flavors):**
1. Consumer calls `ReceiveMessage`; SQS returns the message and makes it **invisible** for the *visibility timeout* (default **30s**, max **12h**).
2. Consumer processes, then calls `DeleteMessage` (the ack).
3. If the timeout expires before delete, the message becomes visible again → redelivery → duplicate. So SQS Standard is fundamentally at-least-once and you must dedupe downstream.

> **Beginner aside — "visibility timeout":** the period after a consumer receives an SQS message during which SQS hides it from other consumers. It's SQS's version of the ack deadline. Set it longer than your worst-case processing time, or you'll get duplicate processing.

### 3.7 RabbitMQ internals

- **Default (autoack / no publisher confirms):** can lose messages → at-most-once-ish.
- **At-least-once:** use **publisher confirms** (broker acks the publish) + **consumer manual acks** (`basicAck` after processing) + **durable queues** + **persistent messages**. On consumer crash before ack, RabbitMQ **requeues** the unacked message → redelivery → duplicates (marked `redelivered=true`).
- **Exactly-once:** RabbitMQ historically does **not** provide native exactly-once delivery; you achieve effectively-once with consumer-side dedup/idempotency. (RabbitMQ Streams and some plugins help, but the classic answer is "dedupe yourself.") AMQP transactions (`tx.select`) exist but are slow and don't give cross-system exactly-once.

> **Beginner aside — "publisher confirm":** RabbitMQ's broker-to-producer ack confirming the message was accepted (and, for persistent messages, written to disk). Without it, a publish is fire-and-forget and can be silently lost.

### 3.8 End-to-end pipeline state flow

Consider: **Producer → Kafka → Stream processor → Database → downstream API.** Each arrow needs its own guarantee. The end-to-end semantic is the *minimum* across all hops:

```
Producer→Kafka:    idempotent producer (no dup in log)        [effectively-once into log]
Kafka→Processor:   read_committed + EOS                        [effectively-once within Kafka]
Processor→DB:      idempotent upsert keyed by event id         [effectively-once at DB]
DB→External API:   idempotency key on the API call             [effectively-once at API]
```
If any single hop is "at-least-once with no dedup," the whole pipeline is at-least-once. This is why "exactly-once" is an *architecture*, not a config flag.

---

## 4. The complete toolkit

### 4.1 Kafka producer configs (delivery-relevant)

| Config | Purpose | Key values / default |
|---|---|---|
| `acks` | How many replicas must persist before ack | `0` (at-most-once-ish, no ack), `1` (leader only), `all`/`-1` (all in-sync replicas). Default `all` (since 3.0). |
| `enable.idempotence` | Dedup producer retries via PID+seq | Default `true` (3.0+). Requires `acks=all`, `retries>0`, `max.in.flight<=5`. |
| `retries` | Max resend attempts | Default `Integer.MAX_VALUE` (bounded by `delivery.timeout.ms`). |
| `delivery.timeout.ms` | Total time to deliver incl. retries | Default `120000` (2 min). |
| `max.in.flight.requests.per.connection` | Concurrent unacked requests | Default `5`; must be ≤5 for idempotence to preserve ordering. |
| `transactional.id` | Enables transactions + zombie fencing | No default; set to a stable unique string to use EOS. |
| `transaction.timeout.ms` | Max open transaction time | Default `60000`. |

### 4.2 Kafka consumer configs

| Config | Purpose | Default |
|---|---|---|
| `enable.auto.commit` | Auto-commit offsets periodically (risk: commit-before-process → at-most-once on the commit; or commit-after-fetch-before-process → loss) | `true` |
| `auto.commit.interval.ms` | Auto-commit cadence | `5000` |
| `isolation.level` | `read_committed` hides aborted/uncommitted txn records (needed for EOS reads) | `read_uncommitted` |
| `max.poll.records` | Batch size per poll | `500` |
| `max.poll.interval.ms` | Max time between polls before consumer is considered dead → rebalance → redelivery | `300000` (5 min) |

**Offset commit APIs (Java):** `commitSync()`, `commitAsync()`, `commitSync(Map<TopicPartition,OffsetAndMetadata>)`. For at-least-once: **disable auto-commit, commit after processing.**

### 4.3 Kafka transaction API (Java)

| Method | Purpose |
|---|---|
| `initTransactions()` | One-time init; fences zombies, gets epoch. |
| `beginTransaction()` | Start a transaction. |
| `send(record)` | Buffer output records (transactional). |
| `sendOffsetsToTransaction(offsets, groupMetadata)` | Atomically commit consumer offsets within the txn. |
| `commitTransaction()` | Write commit markers atomically. |
| `abortTransaction()` | Discard; consumers with `read_committed` never see these records. |

### 4.4 SQS APIs / configs

| API / Config | Purpose | Default |
|---|---|---|
| `SendMessage` / `SendMessageBatch` | Enqueue | — |
| `MessageDeduplicationId` (FIFO) | Dedup key within 5-min window | content-hash if `ContentBasedDeduplication=true` |
| `MessageGroupId` (FIFO) | Ordering scope | — |
| `ReceiveMessage` | Dequeue (makes invisible) | up to 10 messages/call |
| `VisibilityTimeout` | Invisibility window | `30s` (max 12h) |
| `DeleteMessage` | Ack | — |
| `ChangeMessageVisibility` | Extend processing time (heartbeat) | — |
| `RedrivePolicy` / DLQ + `maxReceiveCount` | Move poison messages to dead-letter queue after N redeliveries | — |
| Long polling `WaitTimeSeconds` | Reduce empty receives | `0` (short poll); set up to `20` |

### 4.5 RabbitMQ knobs

| Feature | Purpose |
|---|---|
| Publisher confirms (`confirmSelect`) | Producer learns the broker durably accepted the message. |
| `durable` queue + `deliveryMode=2` (persistent) | Survive broker restart. |
| Manual ack (`basicAck`, `autoAck=false`) | Ack after processing → at-least-once. |
| `basicNack` / `basicReject` (`requeue` flag) | Negative ack; requeue or dead-letter. |
| `prefetch` (`basicQos`) | Limit unacked in-flight messages per consumer. |
| `x-dead-letter-exchange` | Route rejected/expired messages to a DLQ. |
| Quorum queues | Replicated, safer at-least-once (replaces classic mirrored queues). |

### 4.6 HTTP / RPC level

| Tool | Purpose |
|---|---|
| `Idempotency-Key` header (Stripe-style) | Client supplies a UUID; server dedupes the request and replays the stored response. |
| Conditional requests (`If-Match` / ETag, `If-None-Match`) | Optimistic-concurrency idempotency. |
| HTTP method idempotency (RFC 7231) | `GET`, `PUT`, `DELETE` idempotent; `POST` not. |
| gRPC retries with `RetryPolicy` | Auto-retry; pair with server-side dedup. |

### 4.7 Stream-processing frameworks

| Framework | Exactly-once mechanism |
|---|---|
| Kafka Streams | `processing.guarantee=exactly_once_v2` — uses Kafka transactions + changelog topics for state. |
| Apache Flink | **Checkpointing + 2PC sinks** (`TwoPhaseCommitSinkFunction`); barriers snapshot state for exactly-once. |
| Spark Structured Streaming | Idempotent sinks + offset checkpointing (write-ahead log). |

> **Beginner aside — "checkpoint" (Flink):** a periodic, consistent snapshot of all operator state plus the input positions, taken by injecting "barriers" into the stream. On failure, Flink restores the last checkpoint and replays from the saved positions, giving exactly-once *state* when paired with transactional sinks.

---

## 5. Code examples by use case

### 5.1 At-most-once producer (Kafka, fire-and-forget metrics)

```java
// Use case: high-volume metrics where losing a sample is fine but latency must be minimal.
Properties p = new Properties();
p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
p.put(ProducerConfig.ACKS_CONFIG, "0");          // don't wait for broker ack -> may lose
p.put(ProducerConfig.RETRIES_CONFIG, "0");        // never retry -> no duplicates
p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false"); // idempotence forces acks=all

try (KafkaProducer<String,String> prod = new KafkaProducer<>(p)) {
    // fire-and-forget: no callback wait; if the network drops it, it's gone.
    prod.send(new ProducerRecord<>("metrics", "cpu", "0.83"));
}
```

### 5.2 At-least-once consumer (Kafka, process-then-commit)

```java
// Use case: order events. We must never lose an order; duplicates handled downstream.
Properties p = new Properties();
p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
p.put(ConsumerConfig.GROUP_ID_CONFIG, "order-workers");
p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // CRITICAL: manual commit after work
p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

try (KafkaConsumer<String,String> c = new KafkaConsumer<>(p)) {
    c.subscribe(List.of("orders"));
    while (true) {
        ConsumerRecords<String,String> records = c.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String,String> r : records) {
            processOrder(r.value());   // do the work FIRST
        }
        c.commitSync();                // ack AFTER work -> at-least-once
        // Crash between processOrder and commitSync => redelivery => duplicate processing.
    }
}
```

### 5.3 Effectively-once consumer via dedup table (Kafka + Postgres, one transaction)

```java
// Use case: payment postings. At-least-once delivery + DB-side dedup = effectively-once.
void handle(ConsumerRecord<String,String> r, Connection db) throws SQLException {
    String eventId = extractEventId(r); // stable business key, NOT the kafka offset
    db.setAutoCommit(false);
    try {
        // 1) Try to claim this event. Unique constraint on event_id makes redelivery a no-op.
        try (PreparedStatement ins = db.prepareStatement(
                "INSERT INTO processed_events(event_id) VALUES (?)")) {
            ins.setString(1, eventId);
            ins.executeUpdate();        // throws on duplicate -> we skip the business write
        }
        // 2) Business effect, in the SAME transaction.
        try (PreparedStatement up = db.prepareStatement(
                "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
            up.setLong(1, amountOf(r));
            up.setLong(2, accountOf(r));
            up.executeUpdate();
        }
        db.commit();                    // dedup row + business effect commit together
    } catch (SQLException dup) {
        db.rollback();                  // duplicate event -> rollback, treat as success
        if (!isUniqueViolation(dup)) throw dup;
        // else: already processed, safe to ack the broker
    }
}
```
The atomic INSERT-then-UPDATE is the whole trick: the increment (a *non-idempotent* op) becomes safe because the dedup row gates it within one transaction.

### 5.4 Effectively-once via naturally idempotent write (no dedup table)

```java
// Use case: materialize "latest known price". Absolute SET is inherently idempotent.
try (PreparedStatement up = db.prepareStatement(
        "INSERT INTO prices(symbol, price, ts) VALUES (?,?,?) " +
        "ON CONFLICT (symbol) DO UPDATE SET price = EXCLUDED.price, ts = EXCLUDED.ts " +
        "WHERE prices.ts < EXCLUDED.ts")) {  // also guards against out-of-order/duplicate
    up.setString(1, symbol);
    up.setBigDecimal(2, price);
    up.setLong(3, eventTs);
    up.executeUpdate();   // reprocessing the same event -> same final state -> no-op
}
```

### 5.5 Kafka exactly-once read-process-write (transactional)

```java
// Use case: enrich events from topic-A to topic-B with exactly-once *within Kafka*.
Properties pp = new Properties();
pp.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "enricher-1"); // stable -> zombie fencing
pp.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
KafkaProducer<String,String> producer = new KafkaProducer<>(pp /*+serializers*/);
producer.initTransactions();

Properties cp = new Properties();
cp.put(ConsumerConfig.GROUP_ID_CONFIG, "enricher");
cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
cp.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); // don't read aborted txns
KafkaConsumer<String,String> consumer = new KafkaConsumer<>(cp /*+deserializers*/);
consumer.subscribe(List.of("topic-A"));

while (true) {
    ConsumerRecords<String,String> recs = consumer.poll(Duration.ofMillis(200));
    if (recs.isEmpty()) continue;
    producer.beginTransaction();
    try {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String,String> r : recs) {
            producer.send(new ProducerRecord<>("topic-B", enrich(r.value())));
            offsets.put(new TopicPartition(r.topic(), r.partition()),
                        new OffsetAndMetadata(r.offset() + 1));
        }
        // Atomically commit output AND input offsets in one transaction:
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
        producer.commitTransaction();   // all-or-nothing
    } catch (KafkaException e) {
        producer.abortTransaction();    // consumers never see these outputs
    }
}
```

### 5.6 SQS Standard at-least-once with downstream dedup (Java SDK v2)

```java
// Use case: SQS Standard is always at-least-once. Dedupe via a unique key in DynamoDB.
ReceiveMessageResponse resp = sqs.receiveMessage(b -> b
        .queueUrl(QUEUE_URL).maxNumberOfMessages(10).waitTimeSeconds(20)); // long poll
for (Message m : resp.messages()) {
    String key = m.messageAttributes().get("eventId").stringValue();
    try {
        // Conditional put: only succeeds if not already processed (idempotency gate).
        ddb.putItem(PutItemRequest.builder()
            .tableName("processed")
            .item(Map.of("eventId", AttributeValue.fromS(key)))
            .conditionExpression("attribute_not_exists(eventId)")
            .build());
        doBusinessWork(m);            // only runs the first time
    } catch (ConditionalCheckFailedException dup) {
        // already processed -> skip, still delete below
    }
    sqs.deleteMessage(b -> b.queueUrl(QUEUE_URL).receiptHandle(m.receiptHandle())); // ack
}
```

### 5.7 RabbitMQ at-least-once consumer with manual ack + DLQ

```java
// Use case: durable work queue; ack only after success; poison messages go to a DLQ.
channel.queueDeclare("work", true, false, false, Map.of(
        "x-dead-letter-exchange", "dlx"));     // failures route here
channel.basicQos(20);                          // prefetch: max 20 unacked in flight
boolean autoAck = false;                        // manual ack -> at-least-once
channel.basicConsume("work", autoAck, (tag, delivery) -> {
    try {
        process(delivery.getBody());            // work first
        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false); // then ack
    } catch (TransientException te) {
        channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);  // requeue
    } catch (PoisonException pe) {
        channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false); // -> DLQ
    }
}, tag -> {});
```

### 5.8 HTTP idempotency key (server side, Stripe-style)

```java
// Use case: payment API. Client retries POST with the same Idempotency-Key -> one charge.
@PostMapping("/charges")
ResponseEntity<Charge> charge(@RequestHeader("Idempotency-Key") String key,
                              @RequestBody ChargeReq req) {
    // Atomic claim of the key; returns the existing record if already present.
    Optional<StoredResponse> prior = idempotencyStore.tryClaim(key, req.fingerprint());
    if (prior.isPresent()) {
        return ResponseEntity.status(prior.get().status())  // replay stored response
                             .body(prior.get().body());
    }
    Charge c = paymentGateway.charge(req);    // executed exactly once for this key
    idempotencyStore.complete(key, 200, c);   // persist response for future replays
    return ResponseEntity.ok(c);
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Acks cost latency.** `acks=all` waits for replication (adds milliseconds and is bounded by the slowest in-sync replica). `acks=0/1` is faster but weaker. Tune to the data's value.
- **Dedup tables add a read/write per message.** Keep the dedup key indexed; expire old keys (TTL) so the table doesn't grow unbounded. SQS FIFO's window is 5 min; pick a TTL ≥ your max redelivery horizon.
- **Transactions reduce throughput.** Kafka EOS adds transaction markers and coordinator round-trips; batch many records per transaction to amortize. Flink 2PC sinks commit on checkpoint interval — longer interval = higher throughput but higher end-to-end latency and larger replay on failure.
- **`max.in.flight` and ordering:** with idempotence, keep ≤5 to preserve order; without idempotence, >1 can reorder on retry.

### 6.2 Correctness / concurrency
- **Atomicity of effect + dedup-record is non-negotiable.** Two separate transactions (write business effect; then write dedup row) reintroduce a crash window. Use one transaction, or make the effect itself idempotent.
- **The dedup key must be the *business* identity, not the transport identity.** Using the Kafka offset or SQS messageId as the dedup key fails when the *same logical event* is produced twice (e.g., upstream retry created two messages). Use an order ID / event UUID minted at the true source.
- **Beware non-idempotent side effects:** sending an email, charging a card, calling a non-idempotent external API. Wrap these with an idempotency key the external system honors, or record "I sent it" atomically.
- **Out-of-order delivery** breaks naive "last write wins" — guard with event timestamps/version numbers (`WHERE ts < new_ts`).

### 6.3 Security
- **Idempotency keys can be a side channel / DoS vector** — validate they belong to the requester; rate-limit; don't let an attacker probe the store. Tie the key to the authenticated principal and a request fingerprint so a replayed key with a *different* body is rejected (Stripe returns a 409/422 on mismatch).
- **Dead-letter queues may hold sensitive payloads** — apply the same encryption/retention/access controls as the live queue.

### 6.4 Observability
- **Track redelivery/duplicate rates.** Kafka: consumer lag, `records-lag`, rebalance frequency. SQS: `ApproximateNumberOfMessagesNotVisible`, `NumberOfMessagesReceived` vs `Deleted`, DLQ depth. RabbitMQ: `redelivered` flag count, unacked count.
- **Emit a metric when a dedup gate *fires*** (a duplicate was suppressed). A sudden spike means upstream retries or a too-short visibility timeout.
- **Log the idempotency key / event id on every effect** so you can trace "was this applied?" during incidents.

### 6.5 Cost
- At-least-once + dedup means **extra storage and extra writes**. Effectively-once via Kafka transactions costs throughput and broker CPU. Exactly-once architectures are *more expensive*; only pay for it where the business needs it (payments yes, view-count metrics no).

### 6.6 Testing
- **Inject duplicates deliberately** in tests: feed every message twice and assert the final state is identical. This is the single best test for effectively-once.
- **Kill consumers mid-process** (chaos) and assert no loss (at-least-once) and no double effect (effectively-once).
- **Test the dedup-window expiry** boundary for SQS FIFO (a duplicate arriving after 5 min *will* be reprocessed).

### 6.7 Production hardening
- Always configure a **DLQ + max-retries** so poison messages don't loop forever (a redelivery storm can saturate a system).
- Set **visibility timeout / ack deadline > p99 processing time**, and use heartbeats (`ChangeMessageVisibility`) for long jobs.
- For at-least-once producers, ensure **durability** (`acks=all`, replication factor ≥3, `min.insync.replicas=2`) — otherwise a leader crash can still lose data despite "at-least-once" intent.

### 6.8 Anti-patterns
- **"We enabled Kafka exactly-once, so our database writes are exactly-once."** No — EOS stops at Kafka's boundary. Classic, costly mistake.
- **Acking before processing** "to be fast" → silent data loss.
- **Auto-commit + slow processing** → offsets advance past unprocessed records on rebalance → loss; or reprocessing → duplicates. Disable auto-commit for important consumers.
- **Dedup by transport ID instead of business ID.**
- **Two-transaction dedup** (effect and dedup row committed separately).
- **Infinite requeue with no DLQ** → poison-message death spiral.
- **Assuming ordering you don't have** (Kafka orders *within a partition* only; SQS Standard doesn't order at all).

---

## 7. Advanced topics & deep internals

### 7.1 Why exactly-once *delivery* is provably impossible

Two independent results:

**(a) Two Generals / unreliable channel:** As shown in §2.2, no finite protocol over a lossy channel makes both parties *certain*. The sender can never *know* the receiver got it; it can only *believe* with increasing probability. So it must either risk loss (stop retrying = at-most-once) or risk duplication (retry = at-least-once). There is no exactly-once delivery primitive.

**(b) FLP impossibility (Fischer, Lynch, Paterson, 1985):** In an *asynchronous* system (no bound on message delay) with even *one* faulty process, there is **no deterministic protocol that guarantees consensus**. Delivery agreement is a consensus problem. Because we can't bound delay, we can't distinguish "slow" from "dead," so we can't deterministically agree "delivered exactly once."

> **Beginner aside — "FLP impossibility":** a landmark theorem: in a fully asynchronous network where a single node may crash, no algorithm can *guarantee* the nodes reach agreement in bounded time. Real systems sidestep it with timeouts and randomness (giving *probabilistic* or *eventually*-correct agreement), which is exactly why we settle for effectively-once rather than provably exactly-once.

**The resolution:** we *route around* impossibility. We accept at-least-once delivery (duplicates possible) and make duplicates *harmless* via idempotency/dedup. The world changes once even though the network can't promise the message arrives once. That's effectively-once / exactly-once-*processing*.

### 7.2 Kafka idempotent producer edge cases
- **PID expiry / producer restart:** a plain idempotent producer's PID is session-scoped. After a crash-restart it gets a *new* PID, so it can't dedupe messages it had buffered before the crash — those are only deduped if you use **transactions with a stable `transactional.id`** (which fences the old epoch and recovers transaction state).
- **`OutOfOrderSequenceException`:** indicates the broker saw a sequence gap (lost messages it can't reconcile) — usually a sign of `acks` mis-config or broker data loss; the producer must be recreated.
- **Idempotence is per `(PID, partition)`**, so re-partitioning (changing the key/partitioner) breaks the dedup lineage.

### 7.3 Kafka EOS internals: the transaction coordinator & markers
- A dedicated broker acts as the **transaction coordinator** for each `transactional.id` (chosen by hashing into the `__transaction_state` topic partitions).
- Commit is durable 2PC: coordinator appends `PREPARE_COMMIT` to `__transaction_state`, then writes **commit markers** to every data partition the txn touched, then `COMPLETE_COMMIT`. If the coordinator crashes mid-commit, on recovery it reads `__transaction_state` and finishes the commit — no blocking forever (unlike classic 2PC).
- `read_committed` consumers maintain a **Last Stable Offset (LSO)**: they won't deliver records beyond the first still-open transaction, preserving the "only see committed" guarantee. This means a long-open transaction can *stall* read_committed consumers — hence `transaction.timeout.ms`.

### 7.4 Flink exactly-once with `TwoPhaseCommitSinkFunction`
- On each **checkpoint barrier**, the sink *pre-commits* (writes to a staging area / opens an external transaction) and persists the transaction handle in the checkpoint.
- When the checkpoint is *globally complete*, Flink calls `notifyCheckpointComplete`, and the sink *commits* the external transaction.
- On recovery, Flink either commits (if the checkpoint was complete) or aborts pending transactions. This bridges Flink state to an external store with exactly-once — but **the external sink must support transactions or idempotent commits** (e.g., Kafka, JDBC with txn, S3 with rename-on-commit).

### 7.5 The "effectively-once = at-least-once + idempotency" identity, formalized
- Let `D` = delivery guarantee, `E` = effect-suppression.
- `at-most-once + anything = at-most-once` (you can't recover lost messages).
- `at-least-once + idempotent effect = effectively-once`.
- `at-least-once + dedup-store(business-key) = effectively-once`.
- `exactly-once-delivery` is unreachable, so every "exactly-once" product is internally one of the bottom two rows.

### 7.6 Ordering vs delivery — orthogonal but entangled
Delivery semantics and ordering are *separate* properties, but exactly-once systems often need ordering too:
- Kafka: ordering only within a partition; idempotent producer preserves it on retry.
- SQS FIFO: ordering per `MessageGroupId`, at the cost of throughput.
- Out-of-order + at-least-once is the *hardest* combo for state — you need version vectors / event-time watermarks to apply idempotently.

### 7.7 Outbox pattern (the standard cross-system effectively-once technique)
To get an event published *exactly as often as* a DB row changed, you cannot do "write DB then publish to Kafka" (two systems, no atomicity → dual-write problem). Instead:
1. In the *same DB transaction* as the business write, insert a row into an `outbox` table.
2. A separate **relay / CDC** (e.g., Debezium reading the WAL) reads the outbox and publishes to Kafka **at-least-once**.
3. Consumers dedupe by the outbox row's unique id.
This converts an unsolvable dual-write into a solvable single-write + at-least-once relay + downstream dedup.

> **Beginner aside — "dual-write problem":** when you must update two systems (a DB and a message broker) and there's no shared transaction, a crash between the two writes leaves them inconsistent. The outbox pattern + CDC solves it by writing only to the DB atomically and deriving the broker event from the DB log.

> **Beginner aside — "CDC (Change Data Capture)":** reading a database's commit log (e.g., Postgres WAL, MySQL binlog) to stream every change as events. Debezium is the common tool. It guarantees you see every committed change at least once.

### 7.8 Tuning knobs summary
- Visibility/ack timeout vs p99 processing latency (avoid spurious redelivery).
- `delivery.timeout.ms` and `retries` (total at-least-once effort).
- Transaction/checkpoint interval (throughput vs latency vs replay size).
- Dedup TTL (storage vs duplicate window).
- `min.insync.replicas` + replication factor (durability backing the "at-least-once" promise).

---

## 8. Tradeoffs & decision frameworks

### 8.1 The three semantics compared

| Dimension | At-most-once | At-least-once | Effectively-once |
|---|---|---|---|
| Loss | Possible | No | No |
| Duplicates (effect) | No | Yes | No (suppressed) |
| Latency | Lowest | Medium | Highest |
| Throughput | Highest | High | Lower |
| Complexity | Trivial | Low | High (dedup/txn) |
| Cost | Lowest | Medium | Highest |
| Typical use | Telemetry, logs you can drop | Most business events | Payments, billing, inventory |

### 8.2 Broker capability matrix

| System | Native default | Native exactly-once? | How to reach effectively-once |
|---|---|---|---|
| Kafka | at-least-once | EOS *within Kafka* only | idempotent producer + transactions; dedup at external sinks |
| SQS Standard | at-least-once | No | downstream dedup (DynamoDB conditional put, etc.) |
| SQS FIFO | exactly-once *in 5-min window* | Yes (windowed) | rely on dedup id; still dedupe for >5-min gaps |
| RabbitMQ (classic/quorum) | at-most-once unless configured | No | confirms + manual ack + consumer-side dedup |
| Google Pub/Sub | at-least-once | Exactly-once *feature* (subscription opt-in, within ack window) | enable exactly-once delivery; still idempotent sinks |
| Flink/Kafka Streams | per config | Yes (state + txn sinks) | enable EOS / checkpointing + transactional sinks |

### 8.3 Decision rules
- **Use at-most-once when:** the data is high-volume, low-value, and a dropped item is invisible (metrics, sensor samples, cache pokes). **Avoid when:** any business decision depends on the message.
- **Use at-least-once when:** loss is unacceptable and you can either tolerate duplicates or dedupe downstream — i.e., **the default for most systems.** Pair with idempotent consumers.
- **Use effectively-once (txn/dedup) when:** a duplicate has real-world consequences (double charge, double shipment, double email) and you can afford the complexity/cost.
- **Avoid chasing "exactly-once delivery" as a feature:** it doesn't exist; design idempotent endpoints instead.

### 8.4 Idempotency vs dedup-store vs transactions

| Technique | Best when | Cost |
|---|---|---|
| Naturally idempotent op (SET/UPSERT/DELETE) | Effect is a state you can re-assert | Cheapest; sometimes impossible (counters, emails) |
| Dedup store keyed by business id | Non-idempotent effects; need exactly-one execution | Extra store + write; TTL management |
| Distributed transaction (Kafka EOS / XA) | Multiple participants must commit atomically | Throughput hit; coordinator complexity |
| Outbox + CDC | Crossing DB↔broker boundary | Relay infra (Debezium), eventual latency |

---

## 9. Failure modes & debugging

### 9.1 Duplicate storm (effect applied many times)
**Symptoms:** double charges, inflated counters, duplicate rows. **Causes:** consumer crashing before ack repeatedly; visibility timeout shorter than processing time; rebalances; missing dedup. **Diagnose:** Kafka — check rebalance logs, `max.poll.interval.ms` exceeded, consumer lag oscillating; SQS — `ApproximateNumberOfMessagesNotVisible` high, receive>>delete counts; metric on dedup-gate firings spiking. **Fix:** raise visibility/poll timeouts, add heartbeats, add dedup keyed by business id.

### 9.2 Silent data loss
**Symptoms:** events missing, totals too low, no errors. **Causes:** `acks=0/1` + leader crash; auto-commit advancing offsets before processing; acking before work; non-durable RabbitMQ queue. **Diagnose:** compare produced count vs consumed/persisted count; audit `acks`, `min.insync.replicas`, auto-commit settings; check broker leader-election events around the gap. **Fix:** `acks=all`, RF≥3, `min.insync.replicas=2`, manual commit after processing, durable+persistent queues + publisher confirms.

### 9.3 Poison-message loop
**Symptoms:** one message redelivered forever, consumer CPU pegged, lag stuck. **Diagnose:** look for the same messageId/offset repeatedly in logs; DLQ empty despite failures (no DLQ configured). **Fix:** configure DLQ + `maxReceiveCount` / `x-dead-letter-exchange`; make the consumer fail fast and route poison to DLQ.

### 9.4 Kafka transaction stalls read_committed consumers
**Symptoms:** consumers with `read_committed` stop advancing; LSO frozen. **Cause:** a producer left a transaction open (crashed without commit/abort) — consumers can't pass the open transaction. **Diagnose:** check `__transaction_state`, look for long-open transactions, hanging `transactional.id`. **Fix:** `transaction.timeout.ms` will eventually abort it; ensure producers always commit/abort; tune timeout.

### 9.5 Dedup window expiry (SQS FIFO)
**Symptom:** rare duplicates despite FIFO. **Cause:** a duplicate arrived >5 min after the original (outside the dedup window). **Fix:** add a durable downstream dedup store for long horizons; don't trust the broker window for permanent idempotency.

### 9.6 Real-world incident patterns
- **Double-charge after deploy:** a rolling restart caused rebalances; consumers reprocessed in-flight orders that lacked a dedup key → customers charged twice. Postmortem fix: idempotency key on the payment gateway call.
- **Lost metrics during broker failover** (acks=1): acceptable by design, flagged as known tradeoff.
- **Outbox saves the day:** teams that moved from "write DB then publish" to outbox+Debezium eliminated a class of "DB updated but event never published" bugs after crashes between the two writes.

### 9.7 Debugging toolkit
- **Kafka:** `kafka-consumer-groups.sh --describe` (lag), `kafka-console-consumer --isolation-level read_committed`, `kafka-transactions.sh` (3.x) to inspect/abort hung transactions.
- **SQS:** CloudWatch metrics (NotVisible, ReceiveCount, DLQ depth), `aws sqs receive-message` with attributes to see `ApproximateReceiveCount`.
- **RabbitMQ:** management UI (unacked, redelivered), `rabbitmqctl list_queues messages_unacknowledged`.
- **General:** structured logs keyed by business id; a "duplicate suppressed" counter; chaos tests that kill mid-process.

---

## 10. Interview drill

**Q1. Define at-most-once, at-least-once, and exactly-once.**
*Model:* At-most-once = 0 or 1 deliveries, loss possible, no duplicates. At-least-once = 1+ deliveries, no loss, duplicates possible. Exactly-once = exactly 1 *effect*; in practice it means effectively-once (at-least-once delivery + dedup/idempotency), because true exactly-once *delivery* is impossible.
*Probes:* (a) *Where does the ack placement come in?* Ack-before-work → at-most-once; ack-after-work → at-least-once; ack-after-idempotent-work → effectively-once. (b) *Why "effect" not "delivery"?* Because the network can't promise one physical arrival; we only control how many times state changes.

**Q2. Why is exactly-once delivery impossible?**
*Model:* Two Generals + FLP. Over a lossy async channel you can't be certain the ack arrived, so you must either risk loss (no retry) or duplication (retry). FLP says no deterministic consensus in async systems with a crash. So we settle for effectively-once.
*Probes:* (a) *How do real systems sidestep FLP?* Timeouts + randomness for probabilistic/eventual agreement. (b) *Is "exactly-once" marketing then a lie?* It's exactly-once *processing within a bounded scope*; honest products say so.

**Q3. How do you turn at-least-once into effectively-once?**
*Model:* Idempotent operations (UPSERT/SET) or a dedup store keyed by a *business* id, with the dedup record and the business effect committed in the *same transaction*.
*Probes:* (a) *Why business id, not message id?* Because the same logical event can produce two messages upstream. (b) *Why one transaction?* Otherwise a crash between effect and dedup-record reopens the duplicate/loss window. (c) *What about non-idempotent side effects like emails?* Gate them with a dedup row recording "sent," or use the external system's idempotency key.

**Q4. Explain Kafka's exactly-once semantics and its limits.** *(senior-signal)*
*Model:* Idempotent producer (PID+seq dedup of retries) + transactions (`transactional.id`, atomically commit output records and consumer offsets, commit markers, `read_committed`). Limit: only exactly-once *within Kafka*; external sinks need their own idempotency (end-to-end principle).
*Probes:* (a) *What is the transaction coordinator/`__transaction_state`?* Durable 2PC state so commits recover after a coordinator crash. (b) *What stalls read_committed consumers?* An open transaction freezes the LSO. (c) *Does EOS make my Postgres writes exactly-once?* No.

**Q5. SQS Standard vs FIFO delivery guarantees.**
*Model:* Standard = at-least-once, best-effort order, high throughput. FIFO = exactly-once within a 5-min dedup window + strict per-group order, lower throughput.
*Probes:* (a) *What enforces FIFO dedup?* MessageDeduplicationId/content hash within 5 min. (b) *Why might FIFO still duplicate?* A copy after the 5-min window. (c) *Role of visibility timeout?* Ack deadline; too short → spurious redelivery.

**Q6. Walk me through achieving effectively-once across DB and a broker (the dual-write problem).** *(senior-signal)*
*Model:* Don't write DB then publish (no atomicity). Use the **outbox pattern**: write business row + outbox row in one DB transaction; a CDC relay (Debezium) publishes the outbox at-least-once; consumers dedupe by outbox id.
*Probes:* (a) *Why not 2PC/XA across DB and Kafka?* Operationally fragile, blocking, poor throughput. (b) *What guarantee does CDC give?* At-least-once for every committed change. (c) *Where's the dedup?* Downstream consumer keyed by event id.

**Q7. Your consumer double-processed and double-charged a customer. Diagnose.**
*Model:* Likely at-least-once redelivery (crash/rebalance/visibility timeout) hitting a non-idempotent charge with no dedup key. Check rebalance logs, processing time vs visibility timeout, presence of an idempotency key on the gateway call.
*Probes:* (a) *Immediate mitigation?* Idempotency key at the payment gateway; raise visibility timeout/heartbeats. (b) *How to test the fix?* Replay every message twice; assert one charge.

**Q8. When would you deliberately choose at-most-once?** *(senior-signal)*
*Model:* High-volume, low-value, drop-tolerant data where latency and cost dominate — metrics, sensor telemetry, cache hints — and where adding acks/dedup would cost more than the lost data is worth.
*Probes:* (a) *Risk?* Silent gaps; ensure no business logic depends on completeness. (b) *Could sampling beat at-most-once?* Often yes — sample explicitly rather than lose randomly.

**Q9. Explain idempotency keys in an HTTP API.**
*Model:* Client sends a unique `Idempotency-Key`; server atomically claims it, executes once, stores the response, and replays the stored response on retries — turning a non-idempotent POST into a safe-to-retry operation.
*Probes:* (a) *Key reused with a different body?* Reject (409/422) to prevent accidental reuse. (b) *How long to keep keys?* A TTL covering the client's retry horizon (e.g., 24h). (c) *Concurrency?* The claim must be atomic (unique constraint / conditional put).

**Q10. What's the difference between ordering and delivery guarantees?**
*Model:* Orthogonal. Delivery = how many times; ordering = in what sequence. Kafka orders within a partition only; SQS Standard doesn't order; FIFO orders per group. Exactly-once doesn't imply ordering and vice versa.
*Probes:* (a) *Why is out-of-order + at-least-once hard?* Naive last-write-wins corrupts state; need version/timestamp guards. (b) *How does idempotent producer preserve order?* Sequence numbers + `max.in.flight ≤ 5`.

**Q11. How do Flink/Kafka Streams achieve exactly-once and what does the sink need?** *(senior-signal)*
*Model:* Kafka Streams uses Kafka transactions + changelog topics (`exactly_once_v2`). Flink uses checkpoint barriers to snapshot state + `TwoPhaseCommitSinkFunction` (pre-commit on checkpoint, commit on completion). The external sink must support transactions or idempotent commits.
*Probes:* (a) *Tradeoff of checkpoint interval?* Longer = more throughput but larger replay and higher latency. (b) *Non-transactional sink?* Must be idempotent or you lose exactly-once at that edge.

**Q12. Design an effectively-once pipeline: producer → Kafka → enrich → Postgres.**
*Model:* Idempotent producer into Kafka; consumer disables auto-commit; in one Postgres transaction insert a dedup row keyed by event id (unique) + the business write + commit; then commit Kafka offset. Add DLQ, monitor dedup-fire metric, set visibility/poll timeouts > p99.
*Probes:* (a) *Why not Kafka EOS to Postgres?* EOS stops at Kafka. (b) *Crash points handled?* Redelivery hits the dedup row; offset not yet committed → safe reprocess no-op. (c) *Out-of-order updates?* Guard with event timestamp/version.

---

## 11. Glossary

- **Ack (acknowledgment):** receiver's reply confirming receipt/persistence/processing.
- **At-least-once:** delivered 1+ times; no loss, duplicates possible.
- **At-most-once:** delivered 0–1 times; no duplicates, loss possible.
- **Broker:** server storing and routing messages between producers and consumers.
- **CDC (Change Data Capture):** streaming a DB's committed changes from its log (Debezium, WAL, binlog).
- **Checkpoint (Flink):** consistent snapshot of operator state + input positions for recovery.
- **Commit marker (Kafka):** record signaling a transaction's records are now visible to read_committed consumers.
- **Consumer/Subscriber:** component that reads and acts on messages.
- **Dead-letter queue (DLQ):** destination for messages that repeatedly fail or expire.
- **Dedup store / idempotency store:** persistent record of processed ids used to suppress duplicates.
- **Delivery semantics:** the contract on how many times a message's effect occurs under failure.
- **Dual-write problem:** inconsistency when updating two systems without a shared transaction.
- **Effectively-once / exactly-once processing:** at-least-once delivery + dedup/idempotency so the effect happens once.
- **End-to-end principle:** correctness must be enforced at the endpoints; intermediate hops can't guarantee it.
- **Epoch / fencing:** version number that rejects writes from stale (zombie) incarnations.
- **Exactly-once delivery:** one physical delivery — provably impossible over async lossy channels.
- **FLP impossibility:** no deterministic consensus in async systems with one crash.
- **Idempotent:** repeating the operation yields the same effect as doing it once.
- **Idempotency key:** unique id per logical operation used for dedup at the receiver.
- **In-flight / invisible:** state of a message being processed but not yet acked.
- **Isolation level (Kafka):** `read_committed` hides aborted/uncommitted transactional records.
- **Last Stable Offset (LSO):** highest offset read_committed consumers may read (before any open txn).
- **Nack:** negative acknowledgment; redeliver or dead-letter.
- **Offset:** position in a Kafka partition; committing it = acking.
- **Outbox pattern:** write business row + event row in one DB txn; relay publishes the event.
- **PID (Producer ID):** Kafka's internal id used to dedupe a producer's retries.
- **Producer/Publisher:** component originating messages.
- **Publisher confirm (RabbitMQ):** broker ack that a publish was accepted/persisted.
- **Redelivery:** broker re-handing an unacked message → duplicate source.
- **Retry:** resending after no ack → duplicate source.
- **Sequence number:** monotonic counter for dedup/ordering.
- **Transaction coordinator (Kafka):** broker component running durable 2PC for transactions.
- **Two Generals Problem:** impossibility of certain agreement over a lossy channel.
- **Two-phase commit (2PC):** prepare-then-commit protocol for atomic multi-participant commit.
- **Visibility timeout (SQS):** time a received message is hidden before redelivery.
- **WAL (Write-Ahead Log):** DB log of changes; basis for durability and CDC.
- **Zombie:** an old instance presumed dead that resurfaces; fenced via epoch.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

- **Three semantics:** at-most-once (lossy, no dups) · at-least-once (no loss, dups) · effectively-once (no loss, no dup-effect = at-least-once + idempotency/dedup).
- **Impossibility:** exactly-once *delivery* is impossible (Two Generals + FLP). Aim for exactly-once *processing*.
- **Ack rule:** ack before work = at-most-once · ack after work = at-least-once · ack after idempotent work = effectively-once.
- **Make it effectively-once:** idempotent op (UPSERT/SET) OR dedup row keyed by **business id**, both in **one transaction**; or Kafka transactions (within Kafka only); or outbox+CDC across DB↔broker.
- **Kafka:** `acks=all`, `enable.idempotence=true` (default), `transactional.id` for EOS, `read_committed`; durability `RF≥3`, `min.insync.replicas=2`; disable auto-commit, commit after processing.
- **SQS:** Standard = at-least-once; FIFO = exactly-once in **5-min** window; visibility timeout default **30s** (max 12h); add DLQ + maxReceiveCount.
- **RabbitMQ:** confirms + durable + persistent + manual ack = at-least-once; dedupe yourself for exactly-once.
- **End-to-end principle:** exactly-once is an architecture, defined by the weakest hop; EOS stops at Kafka's edge.
- **Ops:** set timeouts > p99 processing; DLQ for poison; monitor redelivery/dedup-fire rates; test by replaying every message twice.
- **Choose:** at-most-once for cheap drop-tolerant telemetry; at-least-once as the default; effectively-once where a duplicate has real cost (payments, inventory, emails).

### Self-test (no answers)

1. A consumer commits its Kafka offset *before* calling an external payment API, then crashes after the commit but before the API call. What semantic did you just build, and what is the business consequence?
2. Explain precisely why adding `enable.idempotence=true` to your Kafka producer does **not** make your downstream Postgres writes idempotent.
3. You must dedupe an event that may be redelivered up to 30 minutes after its first arrival. Why is SQS FIFO's native dedup insufficient, and what do you add?
4. Design the single transaction that makes a *non-idempotent* `balance = balance + amount` update effectively-once. What exactly goes in the transaction and in what order?
5. Walk through the Two Generals argument and connect it to the concrete choice your producer's `retries` setting forces on you.
6. In a producer→Kafka→Flink→S3 pipeline, name the mechanism at each hop required for end-to-end effectively-once, and identify which single misconfigured hop would downgrade the whole pipeline to at-least-once.
7. Your read_committed consumers stop advancing while throughput looks normal. List the likely cause and the exact tools/commands you'd use to confirm and resolve it.

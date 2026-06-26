# Broker Comparison: Kafka vs RabbitMQ vs SQS/SNS vs Pulsar

> A definitive engineering-handbook chapter for senior JVM backend developers. From first principles to deep internals, with a bias toward concrete numbers, real defaults, and production reality.

---

## 1. Overview & where it fits

### What this chapter is about

A **message broker** is a piece of infrastructure that sits between *producers* (services that emit data) and *consumers* (services that process it), so the two sides do not have to talk directly, at the same time, at the same speed, or even know each other exist. This is the foundation of **asynchronous, decoupled architectures**: instead of Service A calling Service B's API and waiting (a *synchronous* request/response), Service A hands a **message** to a broker and moves on; Service B reads it whenever it's ready.

This chapter compares the four systems you will actually meet in production:

- **Apache Kafka** — a distributed, partitioned, replicated **commit log**.
- **RabbitMQ** — a classic **message queue / broker** implementing the AMQP protocol (with exchanges, bindings, and routing).
- **Amazon SQS/SNS** — AWS's **fully managed** queue (SQS) and pub/sub fan-out (SNS) services.
- **Apache Pulsar** — a distributed messaging-and-streaming platform with a **compute/storage-separated** architecture (brokers + Apache BookKeeper).

The goal is not "which is best" (a meaningless question) but **which model fits which problem**, and *why* — down to the mechanics that make each one behave the way it does.

### The problem they solve

Direct synchronous calls between services create a web of problems:

- **Tight coupling** — both services must be up simultaneously; if B is down, A fails.
- **Backpressure / load spikes** — if A produces faster than B can handle, B falls over. A buffer (queue) absorbs the spike.
- **Fan-out** — one event ("order placed") often needs to trigger *many* independent reactions (charge card, update inventory, send email, update analytics). Hard-coding all of those into the producer is brittle.
- **Reliability** — if the consumer crashes mid-processing, you want the message redelivered, not lost.
- **Throughput & scale** — you want to add more consumer instances and have work spread across them automatically.
- **Replay / audit** — sometimes you need to reprocess history (a bug corrupted downstream state; a new service needs to backfill).

Different brokers solve different *subsets* of these well. The single biggest dividing line is **log vs queue** (explained next), which determines almost everything else: whether you can replay, how ordering works, how consumers scale, and how delivery is tracked.

### When you reach for each (one-line mental model)

| Broker | One-line mental model | Reach for it when… |
|---|---|---|
| **Kafka** | A durable, replayable, append-only **log** that many consumers read independently at their own offset. | High-throughput event streaming, event sourcing, log/metrics pipelines, replay, stream processing. |
| **RabbitMQ** | A smart **router + work queue**: messages are dispatched to consumers and deleted once acked. | Complex routing, task/job queues, RPC-style request/reply, per-message TTL/priority, low-latency dispatch. |
| **SQS/SNS** | **Managed** AWS queue (SQS) + managed fan-out (SNS); zero ops, pay-per-use. | You're on AWS, want no servers to run, simple decoupling/buffering, and don't need replay. |
| **Pulsar** | A **log + queue hybrid** with storage separated from compute; multi-tenant, geo-replicated. | You need both streaming *and* queueing semantics, multi-tenancy, tiered/infinite storage, or geo-replication out of the box. |

### One-paragraph mental model (the unifying idea)

Every one of these is a **store-and-forward intermediary**. The deepest difference is what happens to a message after a consumer reads it. In a **queue** (RabbitMQ, SQS), the message is *consumed and destroyed* — once acknowledged, it's gone, and there is one logical copy of the work that gets handed to exactly one consumer in a competing-consumers group. In a **log** (Kafka, Pulsar's core), the message is *retained* on disk for a configured time/size regardless of who read it; each consumer tracks its own **offset** (position) into the log, so many independent consumers can read the same data, and any of them can rewind and replay. SNS and Pulsar's pub/sub layer add **fan-out** (one message delivered to many subscribers). Hold onto this: **destroy-on-ack vs retain-and-offset** is the axis everything else rotates around.

---

## 2. Foundations from first principles

This section defines, from zero, every concept you need before the per-broker deep dives. Read it even if some terms feel familiar — the precise definitions matter later.

### 2.1 Producer, consumer, message, topic/queue

- **Producer (publisher):** code that sends a message into the broker.
- **Consumer (subscriber):** code that reads messages out of the broker.
- **Message:** a unit of data. Typically a **payload** (the bytes — JSON, Avro, Protobuf, plain text) plus **metadata** (headers, a key, a timestamp). A message is **immutable** once published.
- **Topic:** a named category/feed of messages. Producers write to a topic; consumers read from it. (Kafka, Pulsar, SNS use "topic".)
- **Queue:** a named buffer that holds messages until a consumer takes them. (RabbitMQ, SQS use "queue".) In RabbitMQ producers actually publish to an *exchange*, which routes to queues — more on that later.

### 2.2 Queue vs log (the central distinction)

**Queue model (RabbitMQ, SQS):**
- A message lives in the queue until a consumer **acknowledges** it, then it is **deleted**.
- With multiple consumers on one queue (**competing consumers**), each message goes to *exactly one* of them. This is how you scale work horizontally: 10 consumers ⇒ ~10× throughput, and the queue load-balances.
- There is no built-in "read it again later." Once acked and deleted, it's gone (unless you copied it to a dead-letter queue or another queue).
- Natural fit for **task/work distribution**.

**Log model (Kafka, Pulsar core):**
- Messages are **appended** to an ordered, immutable sequence on disk and assigned a monotonically increasing **offset** (Kafka) / message ID (Pulsar). Think of a numbered list that only grows.
- Messages are **retained** based on a **retention policy** (e.g., 7 days, or until the log hits 1 TB), *independent of consumption*. A consumer reading a message does **not** delete it.
- Each consumer (or consumer group) stores its own **offset** — "I've read up to position 4,581." To replay, you just reset your offset backward.
- Many independent consumer groups can read the same log at the same time without interfering.
- Natural fit for **event streaming, event sourcing, multiple independent readers, and replay**.

> **Why this matters:** "Can I replay last Tuesday's events?" → trivial in a log, generally impossible in a plain queue. "Does adding consumers increase total throughput on the same data?" → yes in a queue (work splits), but in a log you must add *partitions* (explained below), because parallelism within a consumer group is capped by partition count.

### 2.3 Partition / shard (parallelism unit in logs)

A single ordered log on one machine can't scale infinitely (one disk, one CPU, ordering forces serial append). So logs are split into **partitions** (Kafka) / managed via **segments** spread across nodes (Pulsar).

- A **partition** is an independent ordered log. A topic with 12 partitions is 12 independent ordered sequences.
- **Ordering guarantee:** order is preserved **within a partition**, not across the topic. If global ordering matters, you need one partition (and lose parallelism), or you order by **key**.
- **Keyed routing:** producers can attach a **key** (e.g., `customerId`). The broker hashes the key to pick a partition, so all messages with the same key land in the same partition and stay ordered relative to each other. This gives you *per-key ordering with parallelism across keys* — a hugely important pattern.
- **Consumer parallelism in a group is bounded by partition count.** A consumer group with 20 consumers on a 12-partition topic leaves 8 consumers idle. Plan partition counts for your target parallelism.

### 2.4 Offset, acknowledgement, commit

- **Offset (log):** the integer position of a message within a partition. A consumer's **committed offset** is where it has durably recorded "I've processed up to here." On restart it resumes from there.
- **Acknowledgement / ack (queue):** the consumer telling the broker "I successfully processed this message; delete it." If the consumer crashes before acking, the broker **redelivers** the message (to the same or another consumer).
- **Commit cadence matters for delivery semantics.** Commit/ack *before* you finish work → risk of *losing* messages (you said done, then crashed). Commit/ack *after* → risk of *duplicate* processing (you finished, crashed before committing, message redelivered). This tradeoff is universal.

### 2.5 Delivery semantics: at-most-once / at-least-once / exactly-once

- **At-most-once:** each message delivered zero or one time. Fast, may lose messages. (Ack/commit before processing.)
- **At-least-once:** each message delivered one or more times; never lost, but **duplicates possible**. The most common default. Requires consumers to be **idempotent** (see below). (Ack/commit after processing.)
- **Exactly-once:** each message has its effect applied exactly once. The hardest. Achieved either via end-to-end **transactions + idempotence** (Kafka EOS within Kafka) or by combining at-least-once delivery with **idempotent consumers** and **deduplication**.
- **Idempotent operation:** an operation that, applied multiple times, has the same effect as applying it once (e.g., `SET balance = 100` is idempotent; `balance = balance + 10` is not). Idempotency is how you survive at-least-once delivery safely.

### 2.6 Pub/sub vs point-to-point

- **Point-to-point (work queue):** one message → exactly one consumer. (SQS, RabbitMQ default queue.)
- **Publish/subscribe (fan-out):** one message → every interested subscriber gets its own copy. (SNS, Kafka with multiple consumer groups, Pulsar with multiple subscriptions.)
- **Fan-out / fan-in:** fan-out = one source to many sinks; fan-in = many sources to one sink.

### 2.7 Durability, replication, persistence

- **Persistence:** writing the message to disk so it survives a process restart. (RAM-only = fast but lost on crash.)
- **Replication:** keeping copies of the data on multiple nodes so a single node failure doesn't lose data. The number of copies is the **replication factor**. A common safe value is **3** (tolerate loss of any one node, often any two depending on settings).
- **Quorum / ISR:** a **quorum** is a majority of replicas that must agree before an operation is considered done. Kafka uses the **ISR (in-sync replica) set** — the replicas currently caught up with the leader. A write is durable once enough ISR members have it. (Detailed later.)
- **fsync / flush:** `fsync` is the OS syscall that forces buffered file data from page cache to the physical disk. Until fsync, data lives in RAM and a power loss can lose it. Brokers trade off "fsync every message" (safe, slow) vs "fsync periodically / rely on replication" (fast, relies on multiple nodes not dying simultaneously). Kafka by default relies on **replication** rather than per-message fsync.

### 2.8 Ordering, backpressure, DLQ, TTL, visibility timeout

- **Ordering:** the guarantee that messages come out in the order they went in. Strong only within a partition/queue, almost never globally in a distributed system.
- **Backpressure:** the mechanism by which a slow consumer signals "slow down." In log systems consumers simply lag (read pointer falls behind); in queues the queue depth grows and producers may be throttled or rejected.
- **Dead-letter queue (DLQ):** a separate queue/topic where messages that repeatedly fail processing are routed, so they don't block the main flow and can be inspected later.
- **TTL (time-to-live):** how long a message is allowed to live before it's discarded (or DLQ'd). RabbitMQ supports per-message and per-queue TTL; SQS has message retention.
- **Visibility timeout (SQS-specific):** when a consumer receives an SQS message, it becomes **invisible** to other consumers for a configured window. If the consumer deletes it within that window, it's gone; if not (crash/timeout), it becomes visible again and can be redelivered. This is SQS's way of doing at-least-once without holding a connection open.

### 2.9 Protocols

- **AMQP (Advanced Message Queuing Protocol):** an open, binary, wire-level protocol for message-oriented middleware. RabbitMQ's native protocol (0-9-1 is the common version; 1.0 also supported). Defines exchanges, queues, bindings, acks, etc.
- **Kafka protocol:** a custom binary TCP protocol, not AMQP. Highly optimized for batched, high-throughput log access.
- **MQTT, STOMP:** lightweight protocols (MQTT for IoT, STOMP simple text). RabbitMQ supports them via plugins; Pulsar supports MQTT/AMQP/Kafka protocols via protocol handlers.
- **HTTP/REST APIs:** SQS/SNS are accessed over HTTPS APIs (no long-lived broker connection); RabbitMQ/Kafka/Pulsar have HTTP management/admin APIs in addition to their native protocols.

### 2.10 Coordination services (ZooKeeper, Raft, KRaft, BookKeeper)

You'll see these names constantly, so define them now:

- **Apache ZooKeeper:** a separate distributed coordination service that stores small amounts of critical metadata (who's the leader, cluster membership, config) with strong consistency. Historically Kafka and Pulsar both depended on it. It's an *extra system to run and operate*.
- **Consensus / Raft / ZAB:** **consensus** is the problem of getting multiple nodes to agree on a value despite failures. **Raft** is a popular, understandable consensus algorithm (leader election + replicated log). **ZAB** is ZooKeeper's own consensus protocol. You need consensus for things like "who is the leader of this partition."
- **KRaft (Kafka Raft):** Kafka's built-in Raft-based metadata layer that **replaces ZooKeeper** (GA since Kafka 3.3; ZooKeeper removed in Kafka 4.0, 2025). Now Kafka runs without ZooKeeper.
- **Apache BookKeeper / bookie:** a distributed, replicated **log storage** service. A **bookie** is a single BookKeeper storage node. Pulsar stores its actual message data in BookKeeper (each storage node is a bookie), which is what lets Pulsar separate storage from the broker (compute) tier.
- **CAP theorem:** in a distributed system you can't simultaneously guarantee **C**onsistency, **A**vailability, and **P**artition tolerance; under a network partition you must trade C vs A. Brokers make explicit choices here (e.g., Kafka favors consistency with `acks=all`).

With the vocabulary in place, we go deep on internals.

---

## 3. How it works internally

This is the heart of the chapter. We trace each broker's internal workflow step by step.

### 3.1 Kafka internals

#### 3.1.1 Physical layout

A Kafka cluster is a set of **brokers** (server processes). A **topic** is split into **partitions**; each partition is **replicated** across brokers (replication factor N). For each partition, one replica is the **leader** (handles all reads/writes) and the others are **followers** (replicate from the leader).

On disk, a partition is a directory containing **segment files**. The active segment is appended to; when it reaches `segment.bytes` (default **1 GiB**) or `segment.ms` (default **7 days**), it's rolled and a new segment starts. Each segment has:
- a **log file** (`.log`) — the actual records,
- an **offset index** (`.index`) — maps offset → byte position,
- a **time index** (`.timeindex`) — maps timestamp → offset (powers "seek to time").

#### 3.1.2 The produce path (step by step)

1. **Producer batches.** The Java client buffers records per partition in a `RecordAccumulator`, grouping them into **batches** (controlled by `batch.size`, default 16 KB, and `linger.ms`, default 0). Batching is the secret to Kafka's throughput.
2. **Partition selection.** If the record has a key, partition = `hash(key) % numPartitions` (sticky behavior aside). If no key, the **sticky partitioner** fills one partition's batch then moves on, improving batching.
3. **Send to leader.** The batch goes to the partition **leader** broker over Kafka's binary protocol.
4. **Leader appends.** Leader appends to its active segment (page cache → eventually fsync'd by the OS; Kafka relies on replication, not per-record fsync, by default).
5. **Replication.** **Follower** brokers continuously fetch from the leader and append the same records. A follower that's caught up is **in-sync** (part of the **ISR**).
6. **Acknowledgement to producer** depends on `acks`:
   - `acks=0` — fire-and-forget; no wait (at-most-once-ish, can lose data).
   - `acks=1` — wait for leader write only; data can be lost if leader dies before a follower copies it.
   - `acks=all` (a.k.a. `-1`) — wait until all **in-sync replicas** have it. Combined with `min.insync.replicas` (e.g., 2) and RF=3, this tolerates one broker loss with no data loss. **This is the durable setting.**
7. **Offset assigned.** The leader assigns the record its offset within the partition. Offsets are per-partition and monotonic.

**Idempotent producer & transactions:**
- `enable.idempotence=true` (default since Kafka 3.0) makes the producer attach a **producer ID (PID)** and **sequence numbers**, so the broker can dedupe retries → no duplicate records from producer retries within a partition.
- **Transactions** (`transactional.id` + `initTransactions/beginTransaction/commitTransaction`) let a producer atomically write to multiple partitions *and* commit consumer offsets, enabling **exactly-once semantics (EOS)** for read-process-write loops (the basis of Kafka Streams EOS). The **transaction coordinator** (a broker role) and a special `__transaction_state` topic manage this; consumers read with `isolation.level=read_committed` to skip aborted records.

#### 3.1.3 The consume path (step by step)

1. **Subscribe.** A consumer joins a **consumer group** (`group.id`). 
2. **Group coordination & rebalance.** A broker acting as the **group coordinator** assigns partitions to group members. When members join/leave, a **rebalance** redistributes partitions (assignors: Range, RoundRobin, Sticky, **CooperativeSticky** — the modern incremental one that avoids stop-the-world). 
3. **Fetch.** Each consumer **pulls** (Kafka is pull-based) batches from its assigned partition leaders, starting at its committed offset. `fetch.min.bytes`/`fetch.max.wait.ms` control batching; `max.poll.records` caps records per `poll()`.
4. **Process.** Application code handles the records.
5. **Commit offset.** The consumer commits its position to the internal `__consumer_offsets` topic — either **auto** (`enable.auto.commit=true`, every `auto.commit.interval.ms`=5s, at-least-once and easy to get duplicates/loss) or **manual** (`commitSync`/`commitAsync`, for precise control).
6. **Replay** = call `seek()` / reset offsets (`--reset-offsets` via `kafka-consumer-groups.sh`) backward.

**Zero-copy:** Kafka uses the `sendfile` syscall to transfer bytes from page cache straight to the network socket without copying into user space — a major reason for its throughput.

**Log retention & compaction:**
- **Time/size retention** (`retention.ms` default 7 days; `retention.bytes` default -1 = unlimited) deletes whole old segments.
- **Log compaction** (`cleanup.policy=compact`) keeps only the **latest value per key**, turning a topic into a changelog/snapshot (used by `__consumer_offsets`, Kafka Streams state, CDC). Compaction is per-key, not time-based.

**KRaft (metadata):** instead of ZooKeeper, a set of **controller** nodes run a Raft-replicated metadata log (`__cluster_metadata`). One controller is the active leader; it manages topic/partition/leader assignments. This removed Kafka's external ZooKeeper dependency (GA 3.3, ZK removed in 4.0).

#### 3.1.4 Kafka state machine (partition leadership)

- Each partition has a leader and followers. If the leader broker dies, the controller elects a new leader from the ISR.
- `unclean.leader.election.enable` (default **false**) controls whether an out-of-sync replica can become leader to restore availability **at the cost of data loss**. Leaving it false favors consistency (CAP: C over A).

### 3.2 RabbitMQ internals

#### 3.2.1 The AMQP model: exchanges, bindings, queues

This is RabbitMQ's defining feature. Producers **do not publish directly to queues.** They publish to an **exchange**, which routes the message to zero or more **queues** based on **bindings** and a **routing key**.

- **Exchange:** a routing element. Types:
  - **direct** — routes to queues whose **binding key == routing key** exactly. Use for simple routing by a fixed key.
  - **fanout** — ignores routing key; copies to **every** bound queue. Use for broadcast/pub-sub.
  - **topic** — routes by **pattern matching** on a dotted routing key, with wildcards `*` (one word) and `#` (zero+ words). E.g., binding `logs.*.error` matches `logs.auth.error`. Use for flexible, hierarchical routing.
  - **headers** — routes on message **headers** instead of routing key (rarely used).
- **Binding:** a rule linking an exchange to a queue (with a binding key/pattern).
- **Routing key:** a string the producer sets on the message; the exchange uses it to decide where to route.
- **Queue:** holds messages until consumed and acked. A queue can be **durable** (survives broker restart) and messages **persistent** (`delivery_mode=2`, written to disk).
- **Default exchange:** a nameless direct exchange to which every queue is automatically bound by its name — so publishing with routing key = queue name "just works" for simple cases.

**Mental flow:** `Producer → Exchange --(binding/routing key)--> Queue(s) → Consumer(s)`.

#### 3.2.2 The dispatch path (step by step)

1. Producer opens a **connection** (TCP) and a **channel** (a lightweight virtual connection multiplexed over the TCP connection — you use many channels per connection).
2. Producer **publishes** to an exchange with a routing key. **Publisher confirms** (an async ack from broker) tell the producer the broker accepted/persisted the message.
3. Exchange evaluates bindings → enqueues a copy into each matching queue.
4. **Competing consumers:** consumers subscribe to a queue; the broker **pushes** messages round-robin (Kafka pulls; RabbitMQ pushes). **Prefetch** (`basic.qos`, `prefetch_count`) limits how many unacked messages a consumer may hold — the key knob for fair dispatch and backpressure. Default prefetch in many clients is effectively unlimited unless set, which can starve other consumers; setting it (e.g., 10–100) is best practice.
5. Consumer processes and sends **basic.ack** → broker deletes the message from the queue. **basic.nack/reject** with `requeue=false` can route to a DLX.
6. If a consumer dies without acking, the broker **requeues** the message to another consumer (at-least-once).

#### 3.2.3 Advanced RabbitMQ features

- **Dead-letter exchange (DLX):** a queue can be configured so rejected/expired/over-length messages are republished to a designated exchange → DLQ pattern.
- **TTL:** per-message (`expiration`) or per-queue (`x-message-ttl`). Combined with DLX, this builds **delayed/retry** queues.
- **Priority queues:** `x-max-priority` lets higher-priority messages jump ahead.
- **Quorum queues:** the modern replicated queue type, backed by a **Raft** consensus implementation (replaces the older "mirrored/classic HA queues"). They provide data safety across nodes and predictable failover. Use quorum queues for HA today; classic mirrored queues are deprecated.
- **Streams (RabbitMQ 3.9+):** an append-only, replayable **log** abstraction inside RabbitMQ (Kafka-like), letting RabbitMQ do non-destructive, offset-based reads. This narrows the gap with Kafka for some use cases.
- **Lazy queues / message paging:** keep messages on disk to handle very deep queues without exhausting RAM (largely default behavior in newer versions).

#### 3.2.4 RabbitMQ clustering

Nodes form a cluster sharing metadata. Queues live on specific nodes (or are replicated via quorum queues). RabbitMQ historically used **Mnesia** (an Erlang distributed DB) for metadata; newer versions add **Khepri** (a Raft-based metadata store) to fix split-brain issues. Erlang's runtime gives RabbitMQ its lightweight concurrency.

### 3.3 SQS/SNS internals (managed)

You don't run servers; AWS does. But you must understand the model.

#### 3.3.1 SQS (queue)

- **Standard queues:** nearly unlimited throughput, **at-least-once** delivery, **best-effort ordering** (no strict ordering). Highly available, massively scalable.
- **FIFO queues:** **exactly-once processing** (via dedup) and **strict ordering** within a **message group ID**, but throughput-limited (historically 300 msg/s, 3,000 with batching per group; "high throughput FIFO" raised this substantially). Use FIFO only when you truly need ordering/dedup.
- **Message group ID (FIFO):** like a partition key — ordering is preserved within a group; different groups process in parallel.
- **Deduplication (FIFO):** a `MessageDeduplicationId` (or content-based hash) suppresses duplicates within a **5-minute** dedup window.

**The visibility-timeout lifecycle (step by step):**
1. Consumer calls `ReceiveMessage` (long-poll up to 20s). It gets a message and a **receipt handle**.
2. The message becomes **invisible** to other consumers for the **visibility timeout** (default **30s**, max 12h).
3. Consumer processes, then calls `DeleteMessage` with the receipt handle → message gone.
4. If the consumer crashes or the timeout elapses before deletion, the message reappears and may be redelivered (at-least-once). Long jobs should call `ChangeMessageVisibility` to extend the timeout (heartbeat).
5. **Redrive / DLQ:** a **redrive policy** with `maxReceiveCount` moves a message to a **dead-letter queue** after N failed receives.

**Other SQS facts:** max message size **256 KB** (larger via the S3 extended-client pointer pattern), retention default **4 days** (max **14 days**), no replay (once deleted, gone), polling-based (no push), no consumer-group concept (you just run more pollers — competing consumers).

#### 3.3.2 SNS (pub/sub fan-out)

- SNS is **topic-based fan-out**. Publishers send to an SNS **topic**; SNS pushes a copy to every **subscription**.
- Subscriptions can be: **SQS queues**, **Lambda functions**, **HTTP/HTTPS endpoints**, **email/SMS**, **Kinesis Firehose**, and **mobile push**.
- **Fan-out pattern (the canonical AWS design):** SNS topic → multiple SQS queues, each feeding an independent consumer. SNS does the fan-out (one publish → N copies); each SQS queue gives one consumer durable, retryable, buffered delivery. This is AWS's answer to Kafka's "multiple consumer groups."
- **Message filtering:** subscription **filter policies** let a subscriber receive only messages whose attributes match, avoiding the need to fan out everything.
- **FIFO SNS topics** exist and pair with FIFO SQS for ordered fan-out.

### 3.4 Pulsar internals (the hybrid)

#### 3.4.1 Compute/storage separation

Pulsar's signature design: **brokers are stateless serving nodes**; the actual data lives in **Apache BookKeeper** (storage nodes called **bookies**). This separation lets you scale serving and storage independently and makes broker failover fast (no data to move — a new broker just starts serving the topic by reading from BookKeeper).

- **Broker:** receives publishes, serves reads, handles subscriptions, but stores **no** message data locally.
- **BookKeeper / bookie:** stores the data as **ledgers** (append-only segments). A topic's backlog is a sequence of ledgers spread across many bookies — this is **segment-centric** storage (vs Kafka's partition lives wholly on its brokers). Because segments are distributed, no single node holds a whole partition, easing rebalancing and capacity.
- **Metadata:** historically ZooKeeper (newer Pulsar can use other metadata stores / etcd; the move off ZK is in progress).

#### 3.4.2 Subscription types (Pulsar's killer flexibility)

A **subscription** is a named cursor (offset tracker) on a topic. *The subscription type determines the delivery model* — this is where Pulsar unifies queue and log semantics:

| Subscription type | Behavior | Analogous to |
|---|---|---|
| **Exclusive** | Only one consumer may attach; gets all messages in order. | Single-reader log. |
| **Failover** | Multiple consumers attach, but only one (the master) is active; others stand by and take over on failure. Ordered. | Active/standby. |
| **Shared (round-robin)** | Messages distributed across all consumers round-robin; no ordering guarantee. Scales out work. | RabbitMQ/SQS competing consumers. |
| **Key_Shared** | Like Shared, but all messages with the same **key** go to the same consumer → per-key ordering with parallelism. | Kafka keyed partitions, but dynamic. |

Crucially, **multiple subscriptions on one topic each get the full stream** (pub/sub fan-out), and **within a subscription** you choose queue-like (Shared) or log-like (Exclusive/Failover) semantics. One system, both models.

#### 3.4.3 Other Pulsar internals

- **Cursors:** subscription positions are stored in BookKeeper as durable **cursors** (the broker tracks acked positions; supports **individual ack** of out-of-order messages, unlike Kafka's single offset).
- **Tiered storage:** older ledgers can be **offloaded** to cheap object storage (S3, GCS) automatically, while remaining transparently readable — enabling **infinite/long retention** at low cost. Kafka added similar tiered storage (KIP-405) more recently.
- **Multi-tenancy:** Pulsar is natively multi-tenant via the hierarchy **tenant → namespace → topic**, with per-namespace quotas, auth, and isolation. Built for running many teams/apps on one cluster.
- **Geo-replication:** built-in **async (and sync) cross-region replication** at the namespace level — configure clusters and Pulsar replicates topics between regions automatically. (Kafka needs MirrorMaker 2 / external tooling; RabbitMQ uses federation/shovel.)
- **Pulsar Functions:** a lightweight built-in stream-processing layer (run functions on topic data without a separate Flink/Streams cluster).

---

## 4. The complete toolkit

### 4.1 Kafka toolkit

**Key client configs (producer):**

| Config | Purpose | Default |
|---|---|---|
| `acks` | Durability of writes | `all` (since 3.0) |
| `enable.idempotence` | Dedup producer retries | `true` (since 3.0) |
| `batch.size` | Bytes per partition batch | 16384 (16 KB) |
| `linger.ms` | Wait to fill batches | 0 |
| `compression.type` | none/gzip/snappy/lz4/zstd | none |
| `max.in.flight.requests.per.connection` | In-flight batches (≤5 for ordering w/ idempotence) | 5 |
| `transactional.id` | Enables transactions/EOS | null |
| `buffer.memory` | Producer buffer | 33554432 (32 MB) |

**Key client configs (consumer):**

| Config | Purpose | Default |
|---|---|---|
| `group.id` | Consumer group | (required) |
| `enable.auto.commit` | Auto-commit offsets | true |
| `auto.commit.interval.ms` | Auto-commit cadence | 5000 |
| `auto.offset.reset` | earliest/latest/none on no offset | latest |
| `max.poll.records` | Records per poll | 500 |
| `max.poll.interval.ms` | Max time between polls before eviction | 300000 (5 min) |
| `isolation.level` | read_uncommitted/read_committed | read_uncommitted |
| `partition.assignment.strategy` | Assignor | RangeAssignor,CooperativeStickyAssignor |

**Key topic/broker configs:** `replication.factor` (typ. 3), `min.insync.replicas` (typ. 2), `retention.ms` (604800000 = 7d), `retention.bytes` (-1), `segment.bytes` (1 GiB), `cleanup.policy` (delete/compact), `unclean.leader.election.enable` (false).

**CLI tools (in `bin/`):**

| Command | Does |
|---|---|
| `kafka-topics.sh` | Create/list/describe/alter topics & partitions |
| `kafka-console-producer.sh` / `kafka-console-consumer.sh` | Manual produce/consume for testing |
| `kafka-consumer-groups.sh` | Inspect group offsets/lag; **reset offsets** (replay) |
| `kafka-configs.sh` | Get/set dynamic configs |
| `kafka-reassign-partitions.sh` | Move/rebalance partitions across brokers |
| `kafka-acls.sh` | Manage ACLs (security) |
| `kafka-dump-log.sh` | Inspect segment files |

Plus the ecosystem: **Kafka Connect** (source/sink connectors), **Kafka Streams** (JVM stream-processing library), **Schema Registry** (Avro/Protobuf schema management), **ksqlDB**.

### 4.2 RabbitMQ toolkit

**Core operations (AMQP 0-9-1 methods):** `exchange.declare`, `queue.declare`, `queue.bind`, `basic.publish`, `basic.consume`, `basic.ack`/`basic.nack`/`basic.reject`, `basic.qos` (prefetch), `confirm.select` (publisher confirms).

**Key arguments / policies:**

| Setting | Purpose | Note |
|---|---|---|
| `durable` (queue/exchange) | Survive restart | pair with persistent msgs |
| `delivery_mode=2` | Persistent message | written to disk |
| `prefetch_count` | Unacked msgs per consumer | set it (e.g., 10–100) |
| `x-message-ttl` | Per-queue TTL | builds delay queues |
| `x-dead-letter-exchange` | DLX target | DLQ pattern |
| `x-max-priority` | Priority queue | 1–255 |
| `x-queue-type=quorum` | Replicated (Raft) queue | use for HA |

**CLI/admin:** `rabbitmqctl` (cluster/user/queue management), `rabbitmq-plugins` (enable plugins like management UI, MQTT, shovel, federation), the **Management UI/HTTP API** (port 15672), `rabbitmq-diagnostics`.

### 4.3 SQS/SNS toolkit

**SQS API actions:** `CreateQueue`, `SendMessage`/`SendMessageBatch`, `ReceiveMessage` (with `WaitTimeSeconds` for long polling, `MaxNumberOfMessages` ≤10), `DeleteMessage`/`DeleteMessageBatch`, `ChangeMessageVisibility`, `SetQueueAttributes` (visibility timeout, retention, redrive policy).

**Key SQS attributes:**

| Attribute | Purpose | Default / limit |
|---|---|---|
| `VisibilityTimeout` | Invisibility window | 30s (max 12h) |
| `MessageRetentionPeriod` | How long unconsumed msgs live | 4 days (max 14) |
| `ReceiveMessageWaitTimeSeconds` | Long-poll wait | 0 (set to 20 to reduce empty polls/cost) |
| `RedrivePolicy` (`maxReceiveCount`, DLQ ARN) | DLQ routing | none |
| Max message size | — | 256 KB |
| `FifoQueue`, `ContentBasedDeduplication` | FIFO settings | — |

**SNS API actions:** `CreateTopic`, `Subscribe` (protocol = sqs/lambda/http/email/...), `Publish`, `SetSubscriptionAttributes` (filter policy, raw message delivery).

**Tooling:** AWS SDK (Java v2: `SqsClient`, `SnsClient`), AWS CLI (`aws sqs`, `aws sns`), CloudWatch metrics (`ApproximateNumberOfMessagesVisible`, `ApproximateAgeOfOldestMessage`), IAM policies for access control, KMS for encryption.

### 4.4 Pulsar toolkit

**Client (Java) building blocks:** `PulsarClient`, `producer()`, `consumer()` with `.subscriptionType(SubscriptionType.Shared/Exclusive/Failover/Key_Shared)`, `Reader` (non-subscription log reads), `acknowledge()` / `acknowledgeCumulative()`.

**CLI/admin:** `pulsar-admin` (tenants, namespaces, topics, subscriptions, offload, geo-replication clusters), `pulsar-client` (produce/consume from CLI), `pulsar` (broker/bookie startup).

**Key concepts/configs:** subscription types (above), **retention & TTL policies** per namespace, **tiered storage / offload** thresholds, **backlog quotas**, **deduplication** (`brokerDeduplicationEnabled`), **geo-replication** cluster config, **multi-tenancy** (tenant/namespace ACLs).

---

## 5. Code examples by use case

> Java/JVM-first, as requested. Examples are trimmed to the load-bearing parts; add dependency setup and error handling for production.

### 5.1 Kafka — durable producer with idempotence (use case: order events)

```java
Properties p = new Properties();
p.put("bootstrap.servers", "broker1:9092,broker2:9092");
p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
p.put("acks", "all");                 // wait for all in-sync replicas -> no data loss
p.put("enable.idempotence", "true");  // dedup producer retries (default true in 3.x)
p.put("compression.type", "lz4");     // cheap, fast compression -> more throughput
p.put("linger.ms", "10");             // wait up to 10ms to batch -> better throughput

try (KafkaProducer<String,String> producer = new KafkaProducer<>(p)) {
    // Key = customerId so all of a customer's events land in one partition (per-key order)
    ProducerRecord<String,String> rec =
        new ProducerRecord<>("orders", order.customerId(), order.toJson());
    producer.send(rec, (md, ex) -> {
        if (ex != null) log.error("send failed", ex);
        else log.info("sent to {}-{}@{}", md.topic(), md.partition(), md.offset());
    });
}
```

### 5.2 Kafka — manual-commit consumer (use case: at-least-once with control)

```java
Properties c = new Properties();
c.put("bootstrap.servers", "broker1:9092");
c.put("group.id", "billing-service");
c.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
c.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
c.put("enable.auto.commit", "false");   // we commit only after successful processing
c.put("auto.offset.reset", "earliest"); // first run: read from the beginning

try (KafkaConsumer<String,String> consumer = new KafkaConsumer<>(c)) {
    consumer.subscribe(List.of("orders"));
    while (running) {
        ConsumerRecords<String,String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String,String> r : records) {
            process(r.value());         // MUST be idempotent (at-least-once => possible dup)
        }
        consumer.commitSync();          // commit AFTER processing the batch
    }
}
```

### 5.3 Kafka — replay (use case: reprocess after a downstream bug)

```bash
# Reset the consumer group "billing-service" for topic "orders" back to a timestamp,
# so it reprocesses everything since then. Group must have no active members.
kafka-consumer-groups.sh --bootstrap-server broker1:9092 \
  --group billing-service --topic orders \
  --reset-offsets --to-datetime 2026-06-20T00:00:00.000 --execute
```

### 5.4 RabbitMQ — topic exchange routing (use case: log distribution)

```java
ConnectionFactory factory = new ConnectionFactory();
factory.setHost("rabbit-host");
try (Connection conn = factory.newConnection(); Channel ch = conn.createChannel()) {
    // Topic exchange: route by dotted, wildcarded routing keys
    ch.exchangeDeclare("logs", "topic", true);  // durable

    // A consumer that only wants errors from any service:
    String q = ch.queueDeclare().getQueue();
    ch.queueBind(q, "logs", "*.error");          // * = exactly one word
    ch.basicQos(20);                             // prefetch: at most 20 unacked at a time
    ch.basicConsume(q, false, (tag, msg) -> {    // autoAck=false -> manual ack
        handle(new String(msg.getBody()));
        ch.basicAck(msg.getEnvelope().getDeliveryTag(), false);
    }, tag -> {});

    // Producer side:
    ch.basicPublish("logs", "auth.error", null, "login failed".getBytes());  // matches *.error
    ch.basicPublish("logs", "auth.info",  null, "login ok".getBytes());      // does NOT match
}
```

### 5.5 RabbitMQ — work queue with DLX retry (use case: resilient task processing)

```java
// Main queue dead-letters to a retry exchange after reject; retry queue TTLs back to main.
Map<String,Object> mainArgs = Map.of(
    "x-dead-letter-exchange", "tasks.dlx");
ch.queueDeclare("tasks", true, false, false, mainArgs);

Map<String,Object> retryArgs = Map.of(
    "x-message-ttl", 30000,                 // wait 30s
    "x-dead-letter-exchange", "");          // back to default exchange -> requeue to "tasks"
ch.queueDeclare("tasks.retry", true, false, false, retryArgs);
ch.exchangeDeclare("tasks.dlx", "direct", true);
ch.queueBind("tasks.retry", "tasks.dlx", "tasks");

// On failure: nack with requeue=false -> goes to DLX -> retry queue -> back after 30s
ch.basicNack(deliveryTag, false, false);
```

### 5.6 SQS — long-polling consumer with heartbeat (use case: long-running jobs)

```java
SqsClient sqs = SqsClient.create();
String url = "https://sqs.us-east-1.amazonaws.com/123/jobs";

ReceiveMessageResponse resp = sqs.receiveMessage(r -> r
    .queueUrl(url)
    .maxNumberOfMessages(10)
    .waitTimeSeconds(20));            // long polling: fewer empty receives -> lower cost

for (Message m : resp.messages()) {
    try {
        // For a long job, extend visibility so SQS doesn't redeliver mid-processing:
        sqs.changeMessageVisibility(v -> v.queueUrl(url)
            .receiptHandle(m.receiptHandle()).visibilityTimeout(120));
        doLongJob(m.body());          // must be idempotent (at-least-once)
        sqs.deleteMessage(d -> d.queueUrl(url).receiptHandle(m.receiptHandle()));
    } catch (Exception e) {
        // do nothing -> after visibility timeout, message redelivered;
        // after maxReceiveCount, redrive policy sends it to the DLQ.
    }
}
```

### 5.7 SNS → SQS fan-out (use case: one event, many independent consumers)

```bash
# SNS topic "order-events" fans out to two SQS queues, each with its own consumer.
aws sns create-topic --name order-events
aws sqs create-queue --queue-name email-svc
aws sqs create-queue --queue-name analytics-svc
# (grant SNS permission to send to each queue, then subscribe each queue ARN)
aws sns subscribe --topic-arn $TOPIC --protocol sqs --notification-endpoint $EMAIL_Q_ARN \
  --attributes RawMessageDelivery=true
aws sns subscribe --topic-arn $TOPIC --protocol sqs --notification-endpoint $ANALYTICS_Q_ARN \
  --attributes '{"FilterPolicy":"{\"region\":[\"us\"]}"}'   # only US events to analytics
aws sns publish --topic-arn $TOPIC --message '{"orderId":"42","region":"us"}'
```

### 5.8 Pulsar — Key_Shared subscription (use case: per-key ordering + scale-out)

```java
PulsarClient client = PulsarClient.builder()
    .serviceUrl("pulsar://broker:6650").build();

Consumer<byte[]> consumer = client.newConsumer()
    .topic("persistent://acme/payments/transactions")  // tenant/namespace/topic
    .subscriptionName("settlement")
    .subscriptionType(SubscriptionType.Key_Shared)      // same key -> same consumer, ordered
    .subscribe();

while (true) {
    Message<byte[]> msg = consumer.receive();
    try {
        settle(new String(msg.getData()));
        consumer.acknowledge(msg);          // individual ack (can ack out of order)
    } catch (Exception e) {
        consumer.negativeAcknowledge(msg);  // redeliver later
    }
}
// Run many instances of this consumer; Pulsar partitions keys across them dynamically.
```

### 5.9 Pulsar — Reader for replay (use case: read a topic like a log from a point)

```java
Reader<byte[]> reader = client.newReader()
    .topic("persistent://acme/payments/transactions")
    .startMessageId(MessageId.earliest)    // or a specific message id / timestamp
    .create();
while (reader.hasMessageAvailable()) {
    Message<byte[]> m = reader.readNext();
    reprocess(m.getData());                // no subscription/ack state -> pure replay
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Kafka:** throughput king — sequential disk writes, batching, zero-copy `sendfile`, compression. Tune `batch.size`/`linger.ms`/`compression.type`; size partitions for parallelism; avoid tiny messages without batching. Real-world single clusters push **millions of messages/sec**.
- **RabbitMQ:** optimized for **low-latency** dispatch and complex routing, not raw streaming throughput. Throughput per queue is bounded; scale via more queues/sharding. Set **prefetch** to balance latency vs fairness. Persistent messages + confirms cost throughput.
- **SQS:** standard queues scale automatically to very high throughput; **batch** sends/receives (up to 10) and **long-poll** (20s) to cut API calls and cost. FIFO is throughput-limited unless high-throughput mode.
- **Pulsar:** high throughput like Kafka, plus the storage tier scales independently. Watch BookKeeper bookie disk/IO; the extra hop (broker→bookie) adds a small latency vs Kafka's local disk.

### 6.2 Correctness & concurrency

- **Default to at-least-once + idempotent consumers** everywhere. Build dedup using a natural idempotency key (orderId) and a store (DB unique constraint, Redis set with TTL).
- **Ordering:** only guaranteed within a partition (Kafka/Pulsar), a queue/group (RabbitMQ/SQS-FIFO), or a key (keyed routing / Key_Shared / FIFO message group). Don't assume global order.
- **Kafka EOS** is real but constrained to read-process-write *within Kafka*; the moment you touch an external system, you're back to idempotency.
- **Rebalances** (Kafka) and **redeliveries** (everywhere) can cause duplicate processing — design for it.

### 6.3 Memory & resource

- **Kafka** relies on OS **page cache** — give the host RAM, keep JVM heap modest (e.g., 6 GB), let the OS cache. Disk is the bottleneck/asset.
- **RabbitMQ** holds queue state in RAM (Erlang); deep queues risk **memory alarms** (flow control kicks in, blocking publishers). Use **quorum/lazy** queues and bound queue length.
- **SQS** — no memory concerns (managed); watch your *consumer* fleet sizing.
- **Pulsar** — broker is light (stateless); memory/disk pressure is on bookies.

### 6.4 Security

- **Kafka:** TLS for transport, SASL (PLAIN/SCRAM/GSSAPI/OAUTHBEARER) for auth, **ACLs** per topic/group; encryption at rest is via disk/volume encryption.
- **RabbitMQ:** TLS, users/permissions per vhost, plugins for LDAP/OAuth.
- **SQS/SNS:** IAM policies (fine-grained), KMS encryption at rest, VPC endpoints; security is AWS-native and strong by default.
- **Pulsar:** TLS, token/JWT/OAuth2/mTLS auth, per-tenant/namespace authorization — multi-tenancy makes authz first-class.

### 6.5 Observability

- **Kafka:** watch **consumer lag** (the #1 health metric: how far behind a group is), under-replicated partitions, ISR shrink, request latency. Tools: JMX, `kafka-consumer-groups.sh`, Burrow, Cruise Control, Prometheus exporters.
- **RabbitMQ:** queue depth, unacked count, consumer count, memory/disk alarms, message rates (Management UI / Prometheus plugin).
- **SQS:** CloudWatch `ApproximateNumberOfMessagesVisible` (depth), `ApproximateAgeOfOldestMessage` (lag), `NumberOfMessagesSent/Received`, DLQ depth alarms.
- **Pulsar:** backlog size per subscription, storage size, bookie health, via Prometheus/Grafana.

### 6.6 Cost

- **Kafka/RabbitMQ/Pulsar (self-managed):** cost = servers + storage + ops headcount. Kafka/Pulsar need beefy disks; Pulsar's tiered storage cuts long-retention cost.
- **Managed:** MSK/Confluent (Kafka), Amazon MQ (RabbitMQ), StreamNative (Pulsar) trade money for ops.
- **SQS/SNS:** per-request pricing (per million requests) + data. Cheapest at low/spiky volume; can get pricey at very high constant throughput vs a self-run log. Long polling and batching materially reduce SQS cost.

### 6.7 Testing

- Use **Testcontainers** (`KafkaContainer`, `RabbitMQContainer`, `LocalStackContainer` for SQS/SNS, `PulsarContainer`) for real-broker integration tests.
- Test consumer **idempotency** and **redelivery** explicitly (force duplicates).
- Test **rebalance** behavior and **poison-message → DLQ** flows.

### 6.8 Production hardening checklist

- Kafka: RF≥3, `min.insync.replicas`=2, `acks=all`, `unclean.leader.election=false`, monitor lag, capacity-plan partitions.
- RabbitMQ: quorum queues, bounded queue lengths + DLX, prefetch set, publisher confirms, memory/disk alarms tuned.
- SQS: DLQ + `maxReceiveCount`, long polling, visibility timeout > p99 processing time, idempotent consumers.
- Pulsar: replication (BookKeeper ensemble/write/ack quorums e.g. 3/3/2), retention/backlog quotas, tiered offload configured.

### 6.9 Anti-patterns

- Using Kafka as a database / RPC channel (it's a log, not a request/reply system).
- Using RabbitMQ for high-volume event streaming with replay needs (use a log).
- Ignoring **consumer lag** until it's a 6-hour backlog.
- Too few partitions in Kafka → can't scale consumers later (repartitioning is painful).
- Not setting prefetch in RabbitMQ → one greedy consumer hoards all messages.
- Visibility timeout shorter than processing time in SQS → constant redelivery storms.
- Treating at-least-once as exactly-once (no idempotency) → duplicate charges/emails.
- Unbounded queues → memory exhaustion / broker meltdown.

---

## 7. Advanced topics & deep internals

### 7.1 Kafka deep dives

- **ISR shrink/expand:** a slow follower is removed from the ISR (`replica.lag.time.max.ms`, default 30s). With `acks=all` + `min.insync.replicas=2`, if ISR shrinks below 2, **producers get errors** (write availability sacrificed for durability — a deliberate CAP choice).
- **Sticky partitioner:** without keys, the producer "sticks" to one partition per batch to improve batching, then rotates — improving throughput vs naive round-robin.
- **Cooperative rebalancing** (`CooperativeStickyAssignor`): incremental rebalances avoid the old "stop-the-world" pause where all consumers gave up all partitions. Static membership (`group.instance.id`) avoids rebalances on quick restarts.
- **Log compaction internals:** a cleaner thread reads the dirty portion of the log, builds an offset map of latest-per-key, and rewrites segments keeping only the latest value (and tombstones — null-value records that signal deletion — kept for `delete.retention.ms`).
- **Tiered storage (KIP-405):** offloads older log segments to remote object storage, keeping local disk small while preserving long retention; reads transparently fetch from remote. GA in newer Kafka.
- **Transactions/EOS internals:** transaction coordinator, `__transaction_state` topic, transaction markers written into partitions, and `read_committed` consumers filtering aborted batches.

### 7.2 RabbitMQ deep dives

- **Quorum queues (Raft):** each quorum queue is a Raft cluster of replicas; writes are committed once a majority acks. They store on disk, tolerate node loss, and replace the flaky old mirrored queues. Cost: more disk I/O and per-message overhead.
- **Streams:** an append-only log with a binary protocol and offset-based consumption — RabbitMQ's Kafka-like primitive (high throughput, replay), distinct from classic queues.
- **Flow control & credit:** RabbitMQ applies internal credit-based flow control between connections/channels and triggers **memory/disk alarms** that *block publishers* to protect the broker.
- **Khepri:** the new Raft-based metadata store replacing Mnesia to eliminate split-brain/network-partition metadata hazards.

### 7.3 SQS/SNS deep dives

- **Visibility timeout is per-receive, not per-message:** extending it (`ChangeMessageVisibility`) is the heartbeat mechanism; set it just above your p99 processing time, not arbitrarily large (large timeouts delay redelivery of genuinely stuck messages).
- **FIFO dedup window** is **5 minutes**; identical `MessageDeduplicationId` within it is dropped. High-throughput FIFO partitions by message group for parallelism.
- **SNS filter policies** evaluate message attributes server-side, reducing wasteful fan-out and consumer filtering.
- **SQS has no replay** — for replayable history on AWS you use **Kinesis** or Kafka (MSK), not SQS.

### 7.4 Pulsar deep dives

- **Ledger lifecycle:** a topic's data is a chain of ledgers; an open ledger is appended to until rolled (size/time/leadership change), then sealed (immutable) and a new one opened. Sealed ledgers are candidates for tiered offload.
- **Ensemble / write quorum / ack quorum (E/Qw/Qa):** BookKeeper writes each entry to `Qw` of `E` bookies and waits for `Qa` acks. E.g., 3/3/2 = stripe across 3 bookies, write 3 copies, wait for 2 — tunable durability vs latency.
- **Individual acks & negative acks:** unlike Kafka's single advancing offset, Pulsar tracks acked message IDs individually, enabling selective redelivery — closer to a queue's per-message semantics.
- **Geo-replication:** namespace-level config replicates topics across named clusters asynchronously (and synchronously for some setups); each region runs its own brokers+bookies.
- **Multi-tenancy isolation:** namespaces carry quotas, auth, retention, and can be **isolated** to specific broker/bookie groups for noisy-neighbor protection.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Master comparison table

| Dimension | **Kafka** | **RabbitMQ** | **SQS / SNS** | **Pulsar** |
|---|---|---|---|---|
| **Core model** | Distributed log | Queue + smart router (AMQP) | Managed queue (SQS) + fan-out (SNS) | Log + queue hybrid (broker + BookKeeper) |
| **Ordering** | Per partition (per key) | Per queue (no ordering across competing consumers w/o care) | Standard: best-effort; FIFO: per message group | Per partition; Key_Shared = per key |
| **Throughput** | Very high (millions/s) | Moderate (latency-optimized) | High (standard); FIFO limited | Very high |
| **Latency** | Low-ms (batched) | Lowest for dispatch | Tens of ms (HTTP) | Low-ms (extra hop) |
| **Replay** | Yes (offsets, retention) | Limited (Streams type only) | No | Yes (cursors/Reader, tiered) |
| **Delivery** | At-least-once; EOS within Kafka | At-least-once | At-least-once; FIFO exactly-once-ish | At-least-once; effectively-once via dedup |
| **Fan-out** | Multiple consumer groups | Fanout/topic exchanges | SNS → many SQS/Lambda | Multiple subscriptions |
| **Routing** | Key/partition only | Rich (direct/topic/fanout/headers) | Basic (SNS filter policies) | Topic + subscription type |
| **Retention** | Time/size (+ tiered) | Until consumed (+ Streams) | ≤14 days | Time/size + infinite via tiered |
| **Ops burden** | High (self) / med (managed) | Medium | **None (fully managed)** | High (broker + BookKeeper + metadata) |
| **Multi-tenancy** | Limited (quotas/ACLs) | vhosts | AWS accounts/IAM | **Native (tenant/namespace)** |
| **Geo-replication** | MirrorMaker 2 (external) | Federation/Shovel | Cross-region via setup | **Built-in** |
| **Protocol** | Kafka binary | AMQP (+MQTT/STOMP) | HTTPS API | Pulsar binary (+ Kafka/AMQP/MQTT handlers) |
| **Coordination** | KRaft (was ZooKeeper) | Mnesia/Khepri (Raft) | AWS-managed | ZooKeeper/etcd + BookKeeper |
| **Best for** | Streaming, event sourcing, pipelines, replay | Task queues, complex routing, RPC, low latency | AWS-native decoupling, zero-ops | Unified streaming+queueing, multi-tenant, geo, infinite retention |

### 8.2 Use-when / avoid-when

**Kafka — use when:** high-throughput event streaming, event sourcing/CDC, multiple independent consumers, replay/reprocessing, stream processing. **Avoid when:** you need complex per-message routing, RPC request/reply, tiny ops budget, or true exactly-once across external systems without idempotency work.

**RabbitMQ — use when:** complex routing, task/job distribution, priority/TTL/delay, RPC over messaging, lowest dispatch latency, moderate volume. **Avoid when:** you need massive streaming throughput with long retention and replay (use a log), or huge multi-consumer fan-out of the same stream.

**SQS/SNS — use when:** you're on AWS, want zero ops, simple decoupling/buffering, fan-out via SNS→SQS, spiky traffic. **Avoid when:** you need replay, strict global ordering at high throughput, or you're multi-cloud / on-prem.

**Pulsar — use when:** you want both queue and stream semantics in one system, native multi-tenancy, built-in geo-replication, or infinite retention via tiered storage. **Avoid when:** you want the simplest possible ops (it's three subsystems: brokers, bookies, metadata) or the largest ecosystem/community (Kafka's is bigger).

### 8.3 A quick decision path

1. **On AWS, want zero ops, no replay?** → SQS (+SNS for fan-out).
2. **Need rich routing / priorities / RPC / lowest latency, moderate scale?** → RabbitMQ.
3. **Need high-throughput streaming, replay, many readers, event sourcing?** → Kafka.
4. **Need streaming + queueing in one, multi-tenant, geo-replicated, infinite retention?** → Pulsar.

---

## 9. Failure modes & debugging

### 9.1 Kafka

- **Consumer lag growing unbounded:** consumers too slow / too few / stuck. Diagnose: `kafka-consumer-groups.sh --describe` (LAG column), CloudWatch/Prometheus. Fix: scale consumers (≤ partition count), speed up processing, add partitions.
- **Under-replicated / offline partitions:** broker down or disk full. `kafka-topics.sh --describe --under-replicated-partitions`. Fix the broker; check ISR.
- **Frequent rebalances / `max.poll.interval.ms` evictions:** processing a poll batch takes too long → consumer kicked → rebalance loop. Fix: lower `max.poll.records`, raise `max.poll.interval.ms`, or use static membership.
- **`NOT_ENOUGH_REPLICAS` produce errors:** ISR < `min.insync.replicas`. A broker is down; producers blocked by design (durability over availability).
- **Real incident archetype:** a single hot key floods one partition (skew) while others idle — fix the keying or repartition.

### 9.2 RabbitMQ

- **Queue depth exploding / publishers blocked:** consumers down or too slow; memory/disk alarm triggers flow control. Diagnose via Management UI (queue depth, memory alarm). Fix: scale consumers, set prefetch, bound queue length + DLX, add RAM.
- **Unacked messages piling up:** consumer holding many messages without acking (prefetch too high or stuck consumer). 
- **Poison message loops:** message repeatedly fails and requeues forever → set DLX with a retry/dead-letter policy.
- **Split-brain (older versions):** network partition + Mnesia → conflicting state; mitigated by `pause_minority` partition handling and Khepri/quorum queues.

### 9.3 SQS/SNS

- **Messages reappearing / processed twice:** visibility timeout < processing time → message redelivered mid-flight. Fix: increase timeout or heartbeat with `ChangeMessageVisibility`.
- **Messages silently vanishing:** retention expired (default 4 days) before consumption, or deleted by a buggy consumer. Check `ApproximateAgeOfOldestMessage`.
- **DLQ filling:** consumer keeps failing; inspect DLQ messages, fix the bug, redrive.
- **SNS messages not arriving:** subscription not confirmed, filter policy excluding them, or permissions missing on the target queue.
- **Empty-receive cost spikes:** short polling. Fix: long polling (`WaitTimeSeconds=20`).

### 9.4 Pulsar

- **Backlog growing:** subscription consumers lagging; check backlog metrics per subscription; scale consumers (Shared/Key_Shared).
- **Bookie failures / write timeouts:** insufficient healthy bookies for the write quorum → writes fail. Check ensemble/quorum settings and bookie disk health.
- **Topic ownership churn:** brokers reassigning topic ownership (load balancing) causes brief unavailability; usually self-heals.
- **Metadata (ZooKeeper) issues:** ZK quorum loss stalls cluster operations — monitor ZK/etcd health.

### 9.5 General debugging toolkit

- Reproduce with **console producers/consumers** / `pulsar-client` / `aws sqs receive-message` to isolate producer vs consumer vs broker.
- Always check the **three pillars**: depth/backlog, lag/age, and error/redelivery counts.
- Capture a **poison message** to a DLQ and inspect it offline rather than blocking the pipeline.

---

## 10. Interview drill

**Q1. What's the fundamental difference between Kafka and RabbitMQ?**
*Model answer:* Kafka is a **distributed log** — messages are appended, retained by policy, and each consumer reads at its own offset, enabling replay and many independent readers. RabbitMQ is a **queue/router** — messages are routed via exchanges/bindings to queues and **deleted on ack**, with competing consumers splitting work. Log vs destroy-on-ack queue is the core difference.
- *Probe: When would you still pick RabbitMQ over Kafka?* Complex routing (topic/headers), priorities, TTL/delay, RPC, lowest dispatch latency, moderate volume.
- *Probe: How does each scale consumers?* Kafka: parallelism capped by partition count within a group. RabbitMQ: add competing consumers to a queue, work load-balances (prefetch controls fairness).
- *Probe: Can RabbitMQ replay?* Only with the newer **Streams** type; classic queues can't.

**Q2. Explain at-least-once vs exactly-once. How do you get exactly-once in practice?**
*Model answer:* At-least-once never loses but can duplicate (ack/commit after processing). Exactly-once means the effect is applied once. In practice you use **at-least-once delivery + idempotent consumers** (dedup on a natural key). Kafka offers true EOS for read-process-write **within Kafka** via transactions + idempotent producer; crossing to external systems still needs idempotency.
- *Probe: How does Kafka's idempotent producer work?* PID + per-partition sequence numbers let the broker drop duplicate retries.
- *Probe: How would you dedup in a consumer?* Idempotency key + a store with a unique constraint / Redis SETNX with TTL.
- *Probe: Why is exactly-once hard across systems?* The commit of the side effect and the offset commit aren't a single atomic transaction (two-phase commit is costly/fragile).

**Q3. How does Kafka guarantee durability and ordering?**
*Model answer:* Durability via **replication** (RF≥3) + `acks=all` + `min.insync.replicas=2` + `unclean.leader.election=false`. Ordering is guaranteed **per partition**; use a key to keep related messages ordered while parallelizing across keys.
- *Probe: What happens if ISR drops below min.insync.replicas?* Producers get `NOT_ENOUGH_REPLICAS` errors — writes blocked to preserve durability (CAP: C over A).
- *Probe: What does unclean leader election trade off?* Availability vs data loss — letting an out-of-sync replica lead restores writes but can lose data.

**Q4. Walk through SQS visibility timeout.**
*Model answer:* On `ReceiveMessage`, a message becomes invisible for the visibility timeout (default 30s). If `DeleteMessage` is called within it, it's gone; otherwise it reappears and may be redelivered (at-least-once). Long jobs heartbeat with `ChangeMessageVisibility`. After `maxReceiveCount`, the redrive policy sends it to a DLQ.
- *Probe: Timeout too short?* Redelivery storms / duplicate processing.
- *Probe: Standard vs FIFO?* Standard = best-effort order, near-unlimited throughput; FIFO = strict per-group order + dedup, throughput-limited.

**Q5. Describe SNS→SQS fan-out and why you'd use it.**
*Model answer:* SNS publishes one message to many subscriptions; subscribing multiple SQS queues gives each downstream service its own durable, buffered, retryable copy. It decouples a single event from many independent consumers — AWS's analog to Kafka's multiple consumer groups, with per-subscription filter policies.
- *Probe: Why not subscribe Lambdas directly?* You can, but SQS adds buffering, retries, DLQ, and decoupling from Lambda concurrency limits.

**Q6. What makes Pulsar's architecture different, and why does it matter?**
*Model answer:* Pulsar **separates compute (brokers) from storage (BookKeeper bookies)**. Brokers are stateless, so failover is instant (no data to move) and you scale serving and storage independently. Data lives as distributed **ledgers/segments**, easing rebalancing, and tiered storage enables infinite retention.
- *Probe: What are subscription types?* Exclusive, Failover, Shared, Key_Shared — letting one system do both queue and log semantics.
- *Probe: Downside?* Operational complexity (three subsystems) and a smaller ecosystem than Kafka.

**Q7. How do RabbitMQ exchanges route messages?**
*Model answer:* Producers publish to an exchange with a routing key. **Direct** = exact key match; **fanout** = broadcast to all bound queues; **topic** = wildcard pattern match (`*` one word, `#` many); **headers** = match on headers. Bindings link exchanges to queues.
- *Probe: How to broadcast to all consumers?* Fanout exchange, or separate queues each bound — each consumer gets its own copy.
- *Probe: How to build a delay queue?* Per-message/queue TTL + dead-letter exchange routing back to the work queue after the delay.

**Q8 (senior signal). You must choose a broker for a new high-volume event-sourcing platform across two AWS regions, run by a small team. Justify.**
*Model answer:* Event sourcing needs **replay + long retention + ordered keyed streams + many readers** → a **log**. Two regions + small team pushes toward **managed**. Options: **MSK (managed Kafka)** — mature, huge ecosystem, replay, but cross-region needs MirrorMaker; **Pulsar (e.g., StreamNative)** — native geo-replication and infinite tiered retention, but smaller team must learn three subsystems. With a small team and AWS-native preference, I'd lean **MSK** for ecosystem/operability and add MirrorMaker 2 for cross-region, unless built-in geo-replication and multi-tenancy are decisive — then Pulsar. SQS is out (no replay); RabbitMQ is out (not a streaming log at this scale).

**Q9 (senior signal). At-least-once duplicates are causing double charges. Walk me through your fix without switching brokers.**
*Model answer:* Make the charge **idempotent**: derive an idempotency key (e.g., `orderId+attempt`), and in the payment write enforce a **unique constraint** (DB) or `SETNX` (Redis) so a replay is a no-op. Ensure ack/commit happens **after** the side effect is durably recorded. Add a DLQ for poison messages, and monitor duplicate rates. This converts at-least-once delivery into effectively-once *effects*.
- *Probe: Why not just commit before processing?* That converts duplicates into **lost** charges (at-most-once) — worse for money.

**Q10 (senior signal). Your Kafka cluster's p99 produce latency spikes during incidents but throughput is fine. What's likely, and what's the tradeoff in your fix?**
*Model answer:* Likely an **ISR shrink** (a slow/failing follower) combined with `acks=all` — the producer waits for the slow replica, or `min.insync.replicas` is barely met. Fixes: remove the unhealthy broker, tune `replica.lag.time.max.ms`, ensure adequate replicas. Tradeoff: loosening durability (`acks=1` or lower `min.insync.replicas`) cuts latency but risks data loss — usually not worth it for important data. Address the root-cause broker instead.
- *Probe: Would `linger.ms` help?* It improves throughput via batching but can *add* latency — wrong lever here.

**Q11. Compare ordering guarantees across all four.**
*Model answer:* Kafka & Pulsar: ordered per partition (per key via hashing / Key_Shared). RabbitMQ: ordered within a queue, but competing consumers + requeues can reorder; single consumer preserves order. SQS standard: best-effort (no guarantee); SQS FIFO: strict order per message group. None guarantee total global order across partitions/groups at scale.

**Q12. When is a managed service (SQS) the wrong choice despite zero ops?**
*Model answer:* When you need **replay** (SQS has none — once deleted, gone), **long retention** (max 14 days), high-throughput **strict ordering** (FIFO is limited), or you're **multi-cloud/on-prem**. Then a log (Kafka/Pulsar) or Kinesis is required.

---

## 11. Glossary

- **AMQP:** open binary protocol for messaging; RabbitMQ's native protocol (exchanges, queues, bindings, acks).
- **Ack (acknowledgement):** consumer signal that a message was processed; in queues triggers deletion, in logs commits an offset.
- **At-least-once / at-most-once / exactly-once:** delivery semantics — duplicates-possible-no-loss / no-duplicates-loss-possible / effect-applied-once.
- **Backpressure:** mechanism to slow producers when consumers lag.
- **Binding (RabbitMQ):** rule linking an exchange to a queue with a key/pattern.
- **BookKeeper / bookie:** Pulsar's distributed replicated log storage; a bookie is one storage node.
- **CAP theorem:** under a partition you must trade consistency vs availability.
- **Commit (offset):** durably recording a consumer's read position.
- **Compaction (log):** retaining only the latest value per key.
- **Competing consumers:** multiple consumers on one queue, each message to one of them.
- **Consensus:** agreement among nodes despite failures (Raft, ZAB).
- **Consumer group (Kafka):** set of consumers sharing partitions of a topic.
- **Cursor (Pulsar):** durable subscription position in BookKeeper.
- **Dead-letter queue (DLQ) / exchange (DLX):** destination for repeatedly failing/expired messages.
- **Durability:** data survives crashes (via persistence + replication).
- **Exchange (RabbitMQ):** routing element (direct/fanout/topic/headers).
- **Fan-out / fan-in:** one→many / many→one message flow.
- **FIFO (SQS):** ordered, deduplicated, throughput-limited queue type.
- **fsync:** syscall forcing data from page cache to physical disk.
- **Idempotency:** repeating an operation yields the same result as doing it once.
- **ISR (in-sync replicas):** Kafka replicas currently caught up with the leader.
- **KRaft:** Kafka's built-in Raft metadata layer replacing ZooKeeper.
- **Ledger (Pulsar/BookKeeper):** append-only storage segment.
- **Log (commit log):** append-only, offset-addressed, retained sequence of messages.
- **min.insync.replicas:** minimum ISR members required for an `acks=all` write to succeed.
- **MVCC:** multi-version concurrency control (DB technique; mentioned only for context — not central here).
- **Offset:** integer position of a message in a partition.
- **Page cache:** OS RAM cache of disk pages; Kafka leans on it for speed.
- **Partition:** an independent ordered sub-log of a topic; the unit of parallelism/order.
- **Prefetch (RabbitMQ `basic.qos`):** max unacked messages a consumer may hold.
- **Producer / consumer:** sender / receiver of messages.
- **Publisher confirms (RabbitMQ):** async broker acks that a publish was accepted.
- **Pub/sub:** one message delivered to many subscribers.
- **Quorum:** a majority of replicas that must agree.
- **Quorum queue (RabbitMQ):** Raft-replicated durable queue type (modern HA).
- **Raft / ZAB:** consensus algorithms (leader election + replicated log).
- **Rebalance (Kafka):** reassigning partitions among consumer group members.
- **Replication factor:** number of copies of data across nodes.
- **Retention:** how long/much data is kept regardless of consumption.
- **Routing key (RabbitMQ):** producer-set string used by exchanges to route.
- **Segment:** physical file/unit a partition/ledger is split into.
- **Subscription type (Pulsar):** Exclusive/Failover/Shared/Key_Shared — delivery model selector.
- **Tiered storage:** offloading old segments to cheap object storage while keeping them readable.
- **Tombstone:** null-value record signaling key deletion in a compacted log.
- **TTL:** time-to-live before a message expires.
- **Visibility timeout (SQS):** window during which a received message is hidden from other consumers.
- **Zero-copy (`sendfile`):** transferring file bytes to the socket without user-space copy.
- **ZooKeeper:** external strongly-consistent coordination service (legacy Kafka/Pulsar metadata).

---

## 12. Cheat-sheet & self-test

### One-screen recap

**The axis:** destroy-on-ack **queue** (RabbitMQ, SQS) vs retain-and-offset **log** (Kafka, Pulsar core).

**Pick by need:**
- Zero-ops on AWS, no replay → **SQS** (+ **SNS** for fan-out).
- Rich routing / priorities / RPC / lowest latency → **RabbitMQ**.
- High-throughput streaming + replay + many readers → **Kafka**.
- Stream+queue in one, multi-tenant, geo, infinite retention → **Pulsar**.

**Key numbers/defaults:**
- Kafka: `acks=all` + RF 3 + `min.insync.replicas=2`; retention 7d; segment 1 GiB; `max.poll.records` 500; `enable.idempotence=true` (3.x).
- RabbitMQ: persistent = `delivery_mode=2`; **set prefetch** (e.g., 10–100); quorum queues for HA; DLX+TTL for retry.
- SQS: visibility timeout 30s (max 12h); retention 4d (max 14d); long-poll 20s; msg ≤256 KB; FIFO dedup window 5 min.
- Pulsar: subscription types Exclusive/Failover/Shared/Key_Shared; BookKeeper E/Qw/Qa e.g. 3/3/2; tiered offload for infinite retention.

**Ordering:** per partition (Kafka/Pulsar) / per queue or FIFO group (RabbitMQ/SQS) — never free global order.

**Delivery default:** at-least-once everywhere → **make consumers idempotent**.

**Top health metrics:** Kafka consumer **lag**; RabbitMQ queue **depth**/unacked; SQS **age of oldest message**; Pulsar subscription **backlog**.

**Coordination:** Kafka = **KRaft** (was ZooKeeper); RabbitMQ = Khepri/Mnesia (Raft); Pulsar = BookKeeper + ZK/etcd.

### Self-test (no answers — recall practice)

1. Explain why a Kafka topic can be read by 5 independent services simultaneously while a plain SQS queue cannot serve the same message to 5 services, and what AWS construct closes that gap.
2. You set `acks=all`, RF=3, `min.insync.replicas=2`. One broker dies. What happens to producers, and why is this the *intended* behavior?
3. Design a RabbitMQ topology that delivers `payment.*` events to a fraud service and *all* events to an audit service, with a 1-minute retry-on-failure delay. Name the exchanges, bindings, and queue arguments.
4. Your SQS consumers occasionally double-charge customers. Walk through the root cause and a fix that doesn't change the broker.
5. Compare how Kafka and Pulsar achieve per-key ordering while still scaling consumers, and name the specific mechanism each uses.
6. When would you choose Pulsar over Kafka despite Kafka's larger ecosystem? Give at least two decisive factors.
7. What is consumer lag, how do you measure it in Kafka, and what are three distinct causes of it growing unbounded?

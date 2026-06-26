# Kafka Consumers & Consumer Groups

> An exhaustive engineering-handbook chapter for senior JVM backend developers who want to *fully master* how Kafka consumers work — from first principles to deep internals, operations, tuning, and interview-grade depth.

---

## 1. Overview & where it fits

### 1.1 What a consumer is

A **Kafka consumer** is a client application that *reads* records (messages) from one or more **topics** in a Kafka cluster. If a **producer** is the writer that appends records to the log, the **consumer** is the reader that pulls records back out and does something useful with them — index them, transform them, load them into a database, trigger side effects, recompute aggregates, and so on.

A few terms before we go further, each explained inline as promised:

- **Topic:** a named, append-only stream of records. Think of it as a category or feed name, e.g. `orders`, `clickstream`, `payments`. Topics are the unit of publish/subscribe.
- **Partition:** a topic is physically split into one or more **partitions**, each an ordered, immutable, append-only sequence of records stored on disk. Partitions are *the* unit of parallelism and ordering in Kafka. Within a single partition, records have a strict total order; across partitions there is no global order.
- **Offset:** every record in a partition has a monotonically increasing 64-bit integer called its **offset** — its position in that partition's log. Offset 0 is the first record ever written to the partition; offsets never repeat and never go backwards within a partition.
- **Broker:** a single Kafka server process. A **cluster** is a set of brokers. Each partition is hosted by a **leader** broker (and replicated to **follower** brokers for durability). Consumers read from the leader (and, since Kafka 2.4, optionally from in-sync followers via "follower fetching").
- **Record / message:** the unit of data — a key (optional), a value (the payload), a timestamp, and headers (optional metadata). I use "record" and "message" interchangeably; the modern API class is `ConsumerRecord`.

### 1.2 The problem consumers solve

Producers fire-and-forget records into topics. You need a way to:

1. **Read those records reliably**, even as brokers fail, partitions move, and your own consumer processes crash and restart.
2. **Scale out horizontally** — when one consumer can't keep up with the write rate, add more consumers and split the work automatically.
3. **Track progress durably** — remember "I've processed up to offset N in partition P" so that after a restart you resume exactly where you left off (not from the beginning, not skipping data).
4. **Decouple producers from consumers** in time and rate. Producers write at their pace; consumers read at theirs; the log buffers the difference (Kafka retains data for hours or days regardless of whether anyone has read it yet).

The **consumer group** abstraction is the mechanism that delivers scaling (#2) and fault tolerance (#1) automatically. **Offset management** delivers durable progress tracking (#3). Together they're the core of this chapter.

### 1.3 When you reach for it

You write a consumer whenever you need to *react to* or *process* a stream of events:

- Stream processing (enrichment, filtering, joining, windowed aggregation).
- ETL / ingestion into a warehouse, search index, or cache.
- Event-driven microservices (a service reacts to `OrderPlaced`, emits `PaymentRequested`).
- Change-data-capture (CDC) sinks reading a database's change log.
- Materialized views, audit logs, metrics pipelines.

You typically use the bare `KafkaConsumer` API for fine-grained control, **Kafka Streams** for stateful stream processing (it's built on top of consumers), **Kafka Connect** for no-code source/sink integration (also built on consumers), or **Spring Kafka** in Spring apps (a thin, ergonomic wrapper around `KafkaConsumer`).

### 1.4 The one-paragraph mental model

> A Kafka topic is split into N partitions. A **consumer group** is a team of consumer instances that share a `group.id`; Kafka guarantees that **each partition is assigned to exactly one consumer in the group at a time**, so the group collectively reads every record exactly once *as a group*, and the maximum useful parallelism equals the number of partitions. Each consumer runs a **poll loop**: it fetches batches of records, processes them, and periodically **commits offsets** (its read position) to a special internal topic `__consumer_offsets`. If a consumer dies, its partitions are **rebalanced** onto the survivors, who resume from the last committed offset — giving **at-least-once** delivery by default. The art of running consumers well is the art of tuning the poll loop, choosing the right commit strategy, picking a good partition-assignment strategy, and keeping **consumer lag** (how far behind the log's tail you are) under control.

---

## 2. Foundations from first principles

### 2.1 The log, restated

Each partition is a **commit log**: an ordered file (actually a set of segment files) you can only append to. A record's identity is `(topic, partition, offset)`. Because the log is immutable and offsets are stable, reading is just "give me records starting at offset X." This is fundamentally different from a traditional message queue:

- In a classic queue (e.g. RabbitMQ, ActiveMQ in queue mode), a consumed message is *removed* from the queue. The broker tracks per-message acknowledgement state, and once acked the message is gone.
- In Kafka, **consumption does not delete anything**. The record stays in the log until it ages out by **retention** (time- or size-based) regardless of who has read it. The *consumer* tracks its own position (offset). This means many independent consumers can read the same topic at different speeds and positions, and you can "rewind" by resetting your offset.

This pull-based, offset-tracking design is the source of Kafka's replayability and multi-subscriber power.

### 2.2 Pull, not push

Kafka consumers **pull** (poll) data from brokers; brokers do not push to consumers. Why pull?

- **Backpressure for free:** a slow consumer simply polls less often / fetches less; it can never be overwhelmed by the broker. A push system must implement flow control to avoid drowning slow consumers.
- **Batching:** the consumer can fetch large batches in one round trip, amortizing network and syscall overhead.
- **Consumer-controlled position:** the consumer decides where to read from and can replay.

The tradeoff: with pure pull, an idle consumer could busy-loop. Kafka solves this with **long polling** — a fetch request can wait up to `fetch.max.wait.ms` (default 500 ms) on the broker for enough data (`fetch.min.bytes`, default 1 byte) to accumulate, so you don't spin uselessly.

### 2.3 Subscribe vs assign

There are two ways a consumer gets partitions:

1. **`subscribe(topics)` — group management (the common path).** You tell Kafka *which topics* you care about; Kafka's group coordinator decides *which partitions* this instance gets, coordinating with the other members of the same `group.id`. Assignment is dynamic and rebalances as members join/leave.

2. **`assign(partitions)` — manual assignment (the advanced path).** You tell Kafka *exactly which partitions* this instance should read. No group coordination, no rebalancing, no automatic failover. You own the partition→instance mapping. Used for stateful, pinned workloads, or when you implement your own coordination.

You cannot mix them on one consumer. `subscribe` enables the consumer-group machinery; `assign` bypasses it.

### 2.4 The consumer group, from zero

A **consumer group** is identified by a string `group.id`. Every consumer instance configured with the same `group.id` is a *member* of that group. The rules:

- **Each partition of a subscribed topic is assigned to exactly one member of the group.** (One partition → one consumer, at any instant.)
- A single member can be assigned **many** partitions.
- Therefore the **maximum parallelism of a group = the number of partitions** in the subscribed topic(s). If a topic has 12 partitions, at most 12 consumers in one group do useful work; a 13th member sits idle with zero partitions.
- **Different groups are independent.** Group `analytics` and group `billing` both read the *full* topic, each maintaining its own offsets. This is how you get publish/subscribe (many groups) layered on top of queue-like load balancing (within a group).

This single invariant — *one partition, one consumer per group* — is the foundation of both ordering (per-partition order is preserved because only one consumer reads it) and scaling (add members up to the partition count).

#### Worked intuition

Topic `orders` has 6 partitions (P0–P5).

| Members in group | Assignment example | Idle members |
|---|---|---|
| 1 | C1: P0,P1,P2,P3,P4,P5 | 0 |
| 2 | C1: P0,P1,P2 — C2: P3,P4,P5 | 0 |
| 3 | C1: P0,P1 — C2: P2,P3 — C3: P4,P5 | 0 |
| 6 | C1..C6: one partition each | 0 |
| 7 | C1..C6: one each — C7: none | 1 |

**Implication for capacity planning:** choose your partition count for the *maximum future consumer parallelism* you'll ever want, because increasing partitions later is disruptive (it changes key→partition mapping and breaks per-key ordering for in-flight keys). Over-partition modestly; don't massively over-partition (each partition has fixed overhead on brokers and adds rebalance and metadata cost).

### 2.5 Offsets: position, committed, and the gap

Three offset concepts you must keep straight:

- **Position (a.k.a. current/next offset):** the offset of the *next* record this consumer will return from `poll()`. It advances in memory as you consume. Purely client-side.
- **Committed offset:** the offset persisted (to `__consumer_offsets`) as "I have processed up to here." On restart/rebalance, consumption resumes from the committed offset, **not** from the in-memory position. By convention the committed offset is the offset of the *next* record to read (i.e. last-processed + 1).
- **Log-end offset (LEO) / high watermark:** the offset just past the last record in the partition — the "tail." The difference between the tail and your position is your **lag**.

> Mental rule: **position** is where you *are*, **committed** is where you'd *resume from* after a crash, **lag** is how far behind the *tail* you are.

### 2.6 Delivery semantics from first principles

The interplay of *when you commit* and *when you process* determines delivery guarantees:

- **At-least-once (the default):** process the record, then commit. If you crash after processing but before committing, on restart you re-read and re-process those records → possible duplicates, but no loss. This is what most consumers get out of the box.
- **At-most-once:** commit first, then process. If you crash after committing but before processing, those records are skipped → possible loss, but no duplicates.
- **Exactly-once (EOS):** no duplicates *and* no loss, achieved within Kafka via **transactions** (read-process-write where consume + produce + offset-commit are one atomic transaction) or, when writing to an external system, via **idempotent writes** / a transactional sink. Pure exactly-once *delivery* to an arbitrary external side effect is impossible in general (the classic distributed-systems result); what you get is exactly-once *processing* of Kafka-to-Kafka pipelines, or effectively-once via idempotency.

We unpack EOS in §7.

### 2.7 The cast of internal characters

To understand the internals you need these actors:

- **Group Coordinator:** a broker that manages a given group — it's the broker that leads the partition of `__consumer_offsets` to which the group hashes. It tracks membership, drives rebalances, and stores committed offsets.
- **Consumer Group Leader:** one of the *consumer instances* (not a broker) that the coordinator designates to actually *compute* the partition assignment using the configured assignor. (Assignment logic runs client-side; the coordinator just shuttles metadata.)
- **`__consumer_offsets`:** an internal, compacted Kafka topic (default 50 partitions) where committed offsets and group metadata are stored as records. We dissect it in §3.6.
- **Heartbeat thread:** a background thread in each consumer that sends periodic heartbeats to the coordinator to say "I'm alive and in the group."
- **(Legacy) ZooKeeper / (modern) KRaft:** ZooKeeper was the external coordination service Kafka historically used for cluster metadata (controller election, broker registry). Modern Kafka (3.x+, and exclusively in 4.0) uses **KRaft** (Kafka Raft) — an internal Raft-based consensus quorum that replaces ZooKeeper. *Note:* consumer **offset** storage moved off ZooKeeper into `__consumer_offsets` way back in 0.9 (2015), so neither ZK nor KRaft is in the hot path of normal offset commits today. **Raft** here is a consensus algorithm that keeps a replicated log consistent across nodes by electing a leader and requiring a majority to acknowledge writes.

---

## 3. How it works internally — the heart of the doc

### 3.1 The poll loop, conceptually

A consumer's life is a loop:

```java
while (running) {
    ConsumerRecords<K, V> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<K, V> record : records) {
        process(record);              // your business logic
    }
    consumer.commitSync();            // persist progress (one strategy)
}
```

But `poll()` does *far* more than fetch records. It is the single method that drives essentially every piece of consumer machinery. Understanding what `poll()` does is understanding the consumer.

### 3.2 What `poll(timeout)` actually does, step by step

On each call to `poll()`, the consumer (on the application thread) performs roughly this sequence:

1. **Ensure coordinator known.** If the group coordinator isn't known yet, send a `FindCoordinator` request to any broker to learn which broker coordinates this `group.id` (the broker leading the `__consumer_offsets` partition that `group.id` hashes to). Cache it.

2. **Ensure active group membership (join/sync if needed).** If this is the first poll, or a rebalance is pending, run the **JoinGroup / SyncGroup** handshake (detailed in §3.4) to obtain the set of partitions assigned to this member. This step *blocks within poll* until assignment completes (subject to the poll timeout).

3. **Fetch committed offsets / reset position.** For newly assigned partitions with no in-memory position, fetch the committed offset from the coordinator (an `OffsetFetch` request). If there's no committed offset (brand-new group, or offsets expired), apply `auto.offset.reset` (`latest` by default → start at the tail; `earliest` → start at offset 0; `none` → throw).

4. **Send fetch requests.** Issue `Fetch` requests to the leader brokers of the assigned partitions, asking for records starting at the current position, governed by `fetch.min.bytes`, `fetch.max.bytes`, `max.partition.fetch.bytes`, and `fetch.max.wait.ms`.

5. **Trigger auto-commit (if enabled).** If `enable.auto.commit=true` and `auto.commit.interval.ms` has elapsed since the last commit, asynchronously commit the current positions *before* returning new records. (Important nuance: auto-commit commits the position reached as of the *previous* poll's returned records — see §3.7.)

6. **Return up to `max.poll.records` records** (default 500) from the in-memory fetch buffer to the caller. The consumer prefetches more than it returns and buffers the rest for subsequent polls (so not every poll hits the network).

7. **Advance position** for the returned records so the next poll continues after them.

The **liveness contract**: the application thread *must* call `poll()` again within `max.poll.interval.ms` (default 5 minutes). The time between two `poll()` calls is when your processing happens. If processing a batch takes longer than `max.poll.interval.ms`, the consumer is considered stuck and is *proactively ejected* from the group (it sends a LeaveGroup), triggering a rebalance. This is the #1 cause of mysterious rebalances (see §9).

### 3.3 Two clocks: heartbeats vs poll interval

Kafka tracks consumer liveness with **two independent mechanisms**, and conflating them causes endless confusion:

| Mechanism | Thread | Config | Default | Detects |
|---|---|---|---|---|
| **Heartbeat** | Background heartbeat thread | `session.timeout.ms`, `heartbeat.interval.ms` | 45,000 ms / 3,000 ms | Process *death*, GC pauses, network partition |
| **Poll interval** | Application (poll) thread | `max.poll.interval.ms` | 300,000 ms | *Live but stuck* consumer (slow processing) |

- The **heartbeat thread** runs independently of your processing. As long as the process is alive and not paused, it heartbeats every `heartbeat.interval.ms`. If the coordinator sees no heartbeat for `session.timeout.ms`, it declares the member dead and rebalances. So `session.timeout.ms` catches *crashes and freezes*.
- The **poll interval** catches the case where the process is alive (heartbeats fine) but the application thread is wedged in a long `process()` and not calling `poll()`. If `poll()` isn't called within `max.poll.interval.ms`, the consumer voluntarily leaves the group.

This separation (introduced in Kafka 0.10.1, KIP-62) is crucial: it lets you set a *short* session timeout for fast crash detection while still allowing *long* processing times between polls.

> **Rule of thumb:** `heartbeat.interval.ms` should be ≤ 1/3 of `session.timeout.ms` (so you miss a couple heartbeats before being declared dead). Set `max.poll.interval.ms` comfortably above your worst-case batch processing time.

### 3.4 The rebalance protocol, step by step

A **rebalance** is the process of (re)distributing partitions among the current members of a group. It happens when: a member joins, a member leaves/dies, the subscribed topic's partition count changes, or a member is removed for missing the poll interval.

#### Eager (stop-the-world) rebalance protocol

This is the original protocol (assignors: `RangeAssignor`, `RoundRobinAssignor`, `StickyAssignor`):

1. **Trigger.** Coordinator detects a membership change and sets a *rebalance-in-progress* flag. It tells members (via the heartbeat/poll response error code `REBALANCE_IN_PROGRESS`) to rejoin.
2. **Revoke (stop the world).** *Every* member revokes *all* its partitions — invoking `ConsumerRebalanceListener.onPartitionsRevoked()` — and stops fetching. The whole group pauses processing. This is why it's called "stop-the-world."
3. **JoinGroup.** All members send `JoinGroup` to the coordinator with their subscriptions and supported assignors. The coordinator picks one member as the **group leader** and one common assignor (by member vote), and replies to the leader with the full member list + metadata; replies to others with just their member ID.
4. **Assignment computed (client-side).** The leader runs the chosen assignor's `assign()` to map partitions → members.
5. **SyncGroup.** The leader sends the computed assignment to the coordinator via `SyncGroup`; other members send empty `SyncGroup` requests and *receive* their assignment in the response.
6. **Resume.** Each member invokes `onPartitionsAssigned()`, fetches committed offsets for its new partitions, and resumes fetching/processing.

The cost: **every partition stops being consumed during the rebalance**, even partitions that didn't move. For large groups or frequent membership changes, this is a serious availability hit.

#### Incremental cooperative rebalance protocol

Introduced in KIP-429 (Kafka 2.4) and the default path for **Kafka Streams** and increasingly recommended for plain consumers via `CooperativeStickyAssignor`. The key idea: **only revoke partitions that actually need to move**, and do it over (up to) two rebalances rather than one global stop:

1. **First rebalance — compute the desired assignment** but members keep consuming partitions they currently own that they'll *retain*. They only revoke partitions that the new assignment says must move to another member.
2. **Revoked partitions are released**, then a **second rebalance** assigns those freed partitions to their new owners.

Net effect: partitions that don't move are *never* stopped. Rebalances become cheaper and less disruptive, especially in autoscaling / rolling-restart scenarios. (Cooperative rebalancing requires *all* members to use a cooperative assignor; mixing eager and cooperative assignors in one group is unsupported and you must roll the upgrade carefully — see §7.4.)

#### The next generation: KIP-848 (the new consumer group protocol)

Kafka 3.7 introduced (early access) and later versions stabilized **KIP-848**, a redesigned group protocol that moves assignment computation **from the client leader to the broker coordinator** and makes rebalances fully incremental and "reconciliation-based" (members and coordinator converge to a target assignment via per-member heartbeat exchanges, with no global synchronization barrier). Benefits: no group-wide stop, faster and more predictable rebalances, removal of the client-side leader as a bottleneck/SPOF, and new configs like `group.protocol=consumer` (vs the classic `group.protocol=classic`). *Version-specific:* availability and stability depend on your broker and client versions; treat the new protocol as the future default but verify support in your deployment. With KIP-848, several classic configs (e.g. `session.timeout.ms`, `heartbeat.interval.ms`, partition.assignment.strategy) move to broker-side group configuration.

### 3.5 Member lifecycle / state machine

A member's group-membership state, conceptually:

```
        (start)
           │  subscribe() + first poll()
           ▼
     ┌───────────┐  FindCoordinator
     │ Unjoined  │──────────────────┐
     └───────────┘                  ▼
           ▲                  ┌─────────────┐
           │                  │   Joining   │  JoinGroup
   LeaveGroup / death         └─────────────┘
           │                        │ leader chosen, assignor run
           │                        ▼
     ┌───────────┐  SyncGroup ┌─────────────┐
     │  Stable   │◀───────────│  Syncing    │
     │ (Fetching)│            └─────────────┘
     └───────────┘
           │  membership change detected
           ▼
   REBALANCE_IN_PROGRESS → revoke → back to Joining
```

The group itself (server-side) has states: **Empty** (no members), **PreparingRebalance** (waiting for members to join), **CompletingRebalance** (waiting for SyncGroup), **Stable** (assignment done, consuming), **Dead** (group removed). You can see these in `kafka-consumer-groups.sh --describe --state`.

### 3.6 `__consumer_offsets` dissected

When you commit an offset, the consumer sends an `OffsetCommit` request to the group coordinator, which writes a record to `__consumer_offsets`:

- **Topic:** `__consumer_offsets`, internal, created automatically.
- **Partitions:** `offsets.topic.num.partitions`, default **50**. A group's data lives entirely in one partition: `partition = abs(murmur2(group.id)) % 50`. This is why the coordinator for a group is the leader of that specific partition — and why all members of a group talk to the same coordinator broker.
- **Replication:** `offsets.topic.replication.factor`, default **3** (capped to broker count on small clusters; on a 1-broker dev cluster it's effectively 1 — a classic dev-vs-prod gotcha).
- **Cleanup policy:** `compact`. Log compaction keeps only the *latest* value per key, so the topic stores the most recent committed offset per `(group, topic, partition)` rather than growing forever.
- **Key:** `(group.id, topic, partition)`. **Value:** committed offset + optional metadata + leader epoch + commit timestamp.
- Group metadata (membership, generation, assignment for the classic protocol) is also stored here under group-metadata keys.

**Offset expiration:** committed offsets for a group are retained for `offsets.retention.minutes` (default **10080** = 7 days) after the group becomes empty / inactive. If a group is down longer than that, its offsets are deleted and on restart it falls back to `auto.offset.reset` — a nasty surprise (you reprocess from earliest or skip to latest). Historically this was 24 hours pre-1.0; raised to 7 days in KIP-186. *Version-specific — verify in your cluster.*

### 3.7 Auto-commit internals (and its sharp edge)

With `enable.auto.commit=true` (the default) and `auto.commit.interval.ms=5000`:

- Auto-commit happens **inside `poll()`** (and on `close()`), not on a timer thread. On a poll, if ≥ 5 s elapsed since last commit, it commits the *position* — i.e., the offsets of records returned by the **previous** poll, which the application is assumed to have processed by now.
- **The hazard:** auto-commit assumes "returned from poll == fully processed." If you crash mid-batch *after* an auto-commit fired but *before* you finished processing the just-returned batch, you can **lose** records (they're committed but not processed) — an at-most-once leak hiding inside a system you thought was at-least-once. Conversely, if you crash before the periodic commit, you reprocess — duplicates. Auto-commit gives you *neither clean guarantee*; it's "approximately at-least-once with a loss window."

For correctness-sensitive workloads, **disable auto-commit and commit manually after processing** (§3.8).

### 3.8 Manual commit: sync vs async

With `enable.auto.commit=false`, you commit explicitly. Two APIs:

- **`commitSync()` / `commitSync(offsets)`** — blocks until the broker acknowledges (or the commit fails after retries). Reliable; you know it succeeded. Adds latency (a network round trip per commit) and reduces throughput if done per-record. Retries automatically on retriable errors up to `default.api.timeout.ms`.
- **`commitAsync()` / `commitAsync(offsets, callback)`** — fires the commit and returns immediately; result delivered via callback. Higher throughput (no blocking), but **does not retry** on failure (because a later commit may already have superseded it — retrying a stale offset could *move the committed offset backwards*). Use a callback to log/handle failures.

**Idiomatic pattern:** `commitAsync()` in the steady-state loop (fast), and `commitSync()` once in `finally`/on shutdown and during rebalance-revoke (to guarantee the final position is durable):

```java
try {
    while (running) {
        var records = consumer.poll(Duration.ofMillis(100));
        process(records);
        consumer.commitAsync();                 // fast path, best-effort
    }
} catch (WakeupException ignored) {             // thrown by consumer.wakeup() to break poll
} finally {
    try {
        consumer.commitSync();                  // durable final commit
    } finally {
        consumer.close();                       // also commits & leaves group cleanly
    }
}
```

**What to commit:** the convention is **last-processed offset + 1** (the offset of the *next* record to read). When committing explicitly with a map, you must do this arithmetic yourself:

```java
consumer.commitSync(Map.of(
    new TopicPartition(record.topic(), record.partition()),
    new OffsetAndMetadata(record.offset() + 1)   // +1 is mandatory and a classic bug source
));
```

Forgetting the `+1` reprocesses one record per commit on restart.

### 3.9 Where processing happens relative to commit (the four orderings)

| Order | Result | Use when |
|---|---|---|
| process → commitSync | At-least-once, durable | Default safe choice |
| process → commitAsync | At-least-once, best-effort durability | High throughput, periodic commitSync backstop |
| commit → process | At-most-once | Loss tolerable, dupes intolerable (rare) |
| transactional read-process-write | Exactly-once | Kafka→Kafka pipelines (§7.1) |

---

## 4. The complete toolkit

### 4.1 Core consumer API (`org.apache.kafka.clients.consumer.KafkaConsumer<K,V>`)

`KafkaConsumer` is **not thread-safe** (except `wakeup()`). One consumer per thread.

| Method | Purpose | Notes |
|---|---|---|
| `subscribe(Collection<String> topics)` | Join group, get dynamic assignment | Triggers group management |
| `subscribe(Pattern)` | Subscribe to topics matching a regex | Re-evaluated on metadata refresh |
| `subscribe(topics, ConsumerRebalanceListener)` | As above + rebalance hooks | Commit on revoke here |
| `assign(Collection<TopicPartition>)` | Manual partition assignment | No group mgmt, no rebalance |
| `poll(Duration)` | Fetch records, drive all machinery | The engine (§3.2) |
| `commitSync()` / `commitSync(offsets)` / `commitSync(offsets, Duration)` | Blocking commit | Reliable, retries |
| `commitAsync()` / `commitAsync(cb)` / `commitAsync(offsets, cb)` | Non-blocking commit | No auto-retry |
| `seek(TopicPartition, long)` | Set position for a partition | Replay / skip |
| `seek(TopicPartition, OffsetAndMetadata)` | Seek with leader epoch | |
| `seekToBeginning(Collection)` / `seekToEnd(Collection)` | Jump to earliest/latest | Empty collection = all assigned |
| `position(TopicPartition)` | Current in-memory position | |
| `committed(Set<TopicPartition>)` | Fetch committed offsets from broker | |
| `pause(Collection)` / `resume(Collection)` | Stop/start fetching specific partitions | Backpressure, in-order retry |
| `paused()` | Which partitions are paused | |
| `assignment()` | Currently assigned partitions | |
| `subscription()` | Currently subscribed topics | |
| `partitionsFor(topic)` | Partition metadata for a topic | |
| `listTopics()` | All topics + partitions | |
| `beginningOffsets(Collection)` / `endOffsets(Collection)` | Earliest/latest offsets (for lag calc) | Network call |
| `offsetsForTimes(Map<TP,Long>)` | Find offset at/after a timestamp | Time-based seek/replay |
| `currentLag(TopicPartition)` | Client-side lag estimate (3.0+) | No network call |
| `wakeup()` | Interrupt a blocking `poll()` | Only thread-safe method; throws `WakeupException` |
| `enforceRebalance(reason)` | Force a rebalance | Testing/ops |
| `close()` / `close(Duration)` | Commit (if auto), leave group, free resources | Always call |
| `groupMetadata()` | Group id, member id, generation | For transactional producers |

### 4.2 Key configuration reference

Defaults are for recent Apache Kafka (3.x). *Always verify against your client version.*

#### Identity & connection

| Config | Default | Meaning |
|---|---|---|
| `bootstrap.servers` | (required) | Initial broker list for discovery |
| `group.id` | (none) | Consumer group name; required for `subscribe`/commits |
| `group.instance.id` | (none) | Enables **static membership** (§7.3) |
| `client.id` | "" | Logical client name for logging/metrics/quotas |
| `key.deserializer` / `value.deserializer` | (required) | Bytes → objects |

#### Group & liveness

| Config | Default | Meaning |
|---|---|---|
| `heartbeat.interval.ms` | 3000 | How often background thread heartbeats |
| `session.timeout.ms` | 45000 | No heartbeat for this long → declared dead (must be within broker's `group.min/max.session.timeout.ms`, default 6000–1800000) |
| `max.poll.interval.ms` | 300000 | Max gap between polls before voluntary leave |
| `max.poll.records` | 500 | Max records returned per `poll()` |
| `partition.assignment.strategy` | `[RangeAssignor, CooperativeStickyAssignor]` (3.x) | Ordered list of assignors |
| `group.protocol` | `classic` | `classic` or `consumer` (KIP-848) |

> Historical note: `session.timeout.ms` default was **10000** before Kafka 2.x raised it to **45000** (KIP-735) to reduce spurious rebalances on transient hiccups. The assignment-strategy default changed over versions too. *Version-specific.*

#### Offsets & delivery

| Config | Default | Meaning |
|---|---|---|
| `enable.auto.commit` | true | Periodic auto-commit inside poll |
| `auto.commit.interval.ms` | 5000 | Auto-commit cadence |
| `auto.offset.reset` | latest | When no committed offset: `earliest` / `latest` / `none` |
| `isolation.level` | read_uncommitted | `read_committed` to skip aborted txn records (EOS) |
| `default.api.timeout.ms` | 60000 | Timeout for blocking calls (commitSync, etc.) |

#### Fetching & throughput

| Config | Default | Meaning |
|---|---|---|
| `fetch.min.bytes` | 1 | Broker waits for this much data before responding |
| `fetch.max.wait.ms` | 500 | Max wait if `fetch.min.bytes` not met |
| `fetch.max.bytes` | 52428800 (50 MB) | Max data per fetch response |
| `max.partition.fetch.bytes` | 1048576 (1 MB) | Max data per partition per fetch |
| `receive.buffer.bytes` | 65536 (64 KB) | TCP receive buffer |
| `check.crcs` | true | Validate record CRCs (slight CPU cost) |

#### Resilience / advanced

| Config | Default | Meaning |
|---|---|---|
| `client.rack` | (none) | Enables follower fetching from same rack/AZ (KIP-392) |
| `allow.auto.create.topics` | true | Auto-create subscribed topic if missing (often set false) |
| `metadata.max.age.ms` | 300000 | Force metadata refresh interval |
| `reconnect.backoff.ms` / `.max.ms` | 50 / 1000 | Reconnect backoff |
| `retry.backoff.ms` | 100 | Backoff between retries |

### 4.3 Assignors (partition.assignment.strategy)

| Assignor | Class | Behavior | Balance | Sticky? | Cooperative? |
|---|---|---|---|---|---|
| Range | `RangeAssignor` | Per-topic, lay partitions out and slice ranges per consumer | Can skew with many topics; co-locates same-index partitions across topics (good for joins) | No | No |
| RoundRobin | `RoundRobinAssignor` | Round-robin all topic-partitions across all consumers | Even | No | No |
| Sticky | `StickyAssignor` | Even like round-robin but minimizes movement on rebalance | Even | Yes | No (eager) |
| CooperativeSticky | `CooperativeStickyAssignor` | Sticky + incremental cooperative rebalancing | Even | Yes | **Yes** |

Detailed in §7.4.

### 4.4 CLI tools

| Command | Purpose |
|---|---|
| `kafka-consumer-groups.sh --bootstrap-server <b> --list` | List groups |
| `... --describe --group <g>` | Per-partition: current-offset, log-end-offset, **LAG**, consumer-id, host, client-id |
| `... --describe --group <g> --members --verbose` | Membership + assignment |
| `... --describe --group <g> --state` | Group state, coordinator, assignor |
| `... --reset-offsets --group <g> --topic <t> --to-earliest --execute` | Reset to earliest |
| `... --reset-offsets --group <g> --topic <t> --to-latest --execute` | Skip to latest |
| `... --reset-offsets --group <g> --topic <t> --to-offset <n> --execute` | Reset to specific offset |
| `... --reset-offsets --group <g> --topic <t> --to-datetime <iso8601> --execute` | Time-based reset |
| `... --reset-offsets --group <g> --topic <t> --shift-by <±n> --execute` | Relative shift |
| `... --reset-offsets ... --dry-run` | Preview without applying (default if no `--execute`) |
| `... --delete --group <g>` | Delete a (empty) group |
| `... --delete-offsets --group <g> --topic <t>` | Delete specific committed offsets |
| `kafka-console-consumer.sh --bootstrap-server <b> --topic <t> --from-beginning` | Ad-hoc read |
| `kafka-console-consumer.sh ... --group <g> --property print.offset=true` | Read with offsets shown |

> **Critical operational rule:** `--reset-offsets` only works when the group has **no active members** (the group must be empty/inactive). Stop your consumers first, or you'll get an error. And always `--dry-run` first.

### 4.5 Ecosystem layers built on consumers

| Tool | What it is | Consumer relevance |
|---|---|---|
| **Spring Kafka** | Spring wrapper; `@KafkaListener`, containers, `AckMode` | Manages poll loop, commit, error handlers, retry/DLT for you |
| **Kafka Streams** | Stream-processing library | Uses consumers + cooperative rebalancing internally; `application.id` ≈ `group.id` |
| **Kafka Connect** | Pluggable source/sink framework | Sink connectors are consumers; manages offsets in its own topics |
| **librdkafka** | C client (basis for Python/Go/.NET/etc.) | Same group protocol; config names sometimes differ (e.g. `enable.auto.offset.store`) |

---

## 5. Code examples by use case

All examples use Apache Kafka's Java client (`org.apache.kafka:kafka-clients`). They are deliberately complete enough to adapt.

### 5.1 Baseline at-least-once consumer (auto-commit, simplest)

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.*;

public class SimpleConsumer {
    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "demo-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // new group starts at 0
        // enable.auto.commit defaults to true; auto.commit.interval.ms defaults to 5000

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("orders"));
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> r : records) {
                    System.out.printf("p=%d off=%d key=%s val=%s%n",
                            r.partition(), r.offset(), r.key(), r.value());
                }
                // auto-commit handles offset persistence; see §3.7 for its loss window
            }
        }
    }
}
```

Use for: dashboards, non-critical metrics, where occasional dupes/losses are fine and simplicity wins.

### 5.2 Correctness-first: manual commit after processing (sync + async hybrid)

```java
public class ReliableConsumer {
    private final KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;

    public ReliableConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "billing");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // we commit ourselves
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        this.consumer = new KafkaConsumer<>(props);
    }

    public void run() {
        consumer.subscribe(List.of("payments"));
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> r : records) {
                    handlePayment(r);          // do the real work FIRST
                }
                if (!records.isEmpty()) {
                    consumer.commitAsync((offsets, ex) -> {  // fast, best-effort
                        if (ex != null) System.err.println("async commit failed: " + ex);
                    });
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException ignored) {
            // expected on shutdown()
        } finally {
            try {
                consumer.commitSync();   // durable final commit (blocks, retries)
            } finally {
                consumer.close();        // leaves group, frees connections
            }
        }
    }

    public void shutdown() {   // call from a shutdown hook / another thread
        running = false;
        consumer.wakeup();     // the ONLY thread-safe method: breaks the blocking poll
    }

    private void handlePayment(ConsumerRecord<String, String> r) { /* ... */ }
}
```

Use for: anything where reprocessing-on-restart is acceptable but loss is not (the common backend default).

### 5.3 Per-record / per-partition fine-grained commit with a rebalance listener

Commit on revoke so a partition handed to another consumer resumes accurately; track offsets per partition.

```java
public class GranularConsumer {
    private final KafkaConsumer<String, String> consumer;
    private final Map<org.apache.kafka.common.TopicPartition, OffsetAndMetadata> pending = new HashMap<>();

    class CommitOnRevoke implements ConsumerRebalanceListener {
        @Override public void onPartitionsRevoked(Collection<org.apache.kafka.common.TopicPartition> tps) {
            consumer.commitSync(pending);   // flush what we've processed before losing the partitions
        }
        @Override public void onPartitionsAssigned(Collection<org.apache.kafka.common.TopicPartition> tps) {
            // optionally seek() to a custom store here for exactly-once-into-external-DB
        }
    }

    public void run() {
        consumer.subscribe(List.of("events"), new CommitOnRevoke());
        while (true) {
            var records = consumer.poll(Duration.ofMillis(100));
            for (var r : records) {
                process(r);
                pending.put(new org.apache.kafka.common.TopicPartition(r.topic(), r.partition()),
                            new OffsetAndMetadata(r.offset() + 1));  // +1 == next to read
            }
            consumer.commitAsync(pending, null);
        }
    }
    private void process(ConsumerRecord<String,String> r) {}
}
```

Use for: long-running per-partition processing where you want minimal reprocessing after a rebalance.

### 5.4 Manual partition assignment (no group), with explicit seek

```java
public class AssignAndSeek {
    public static void main(String[] args) {
        Properties props = baseProps();          // no group management needed
        try (KafkaConsumer<String,String> consumer = new KafkaConsumer<>(props)) {
            var p0 = new org.apache.kafka.common.TopicPartition("audit", 0);
            var p1 = new org.apache.kafka.common.TopicPartition("audit", 1);
            consumer.assign(List.of(p0, p1));    // pin exactly these partitions
            consumer.seekToBeginning(List.of(p0)); // replay p0 from start
            consumer.seek(p1, 1_000L);             // p1 from offset 1000
            while (true) {
                var records = consumer.poll(Duration.ofMillis(200));
                records.forEach(r -> System.out.println(r.offset() + " " + r.value()));
            }
        }
    }
    static Properties baseProps() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return p;
    }
}
```

Use for: stateful consumers with externally managed partition pinning, debugging, replay tools, or where you can't tolerate rebalances.

### 5.5 Time-travel / replay by timestamp

```java
// Reprocess everything from the last 2 hours across all assigned partitions.
long since = System.currentTimeMillis() - Duration.ofHours(2).toMillis();
Set<TopicPartition> assigned = consumer.assignment();
Map<TopicPartition, Long> query = assigned.stream()
        .collect(java.util.stream.Collectors.toMap(tp -> tp, tp -> since));
Map<TopicPartition, OffsetAndTimestamp> found = consumer.offsetsForTimes(query);
found.forEach((tp, ot) -> {
    if (ot != null) consumer.seek(tp, ot.offset());   // offset of first record at/after 'since'
    else consumer.seekToEnd(List.of(tp));             // no record that recent → go to tail
});
```

Use for: incident recovery ("replay the last N hours into a fixed sink"), backfills, debugging.

### 5.6 Backpressure with pause/resume (bounded in-flight external calls)

```java
// If a downstream system is slow, stop pulling from the partitions whose buffer is full.
var records = consumer.poll(Duration.ofMillis(100));
for (var r : records) {
    boolean accepted = downstreamQueue.offer(r);   // bounded queue
    if (!accepted) {
        var tp = new TopicPartition(r.topic(), r.partition());
        consumer.pause(List.of(tp));               // stop fetching this partition
        // NOTE: keep calling poll() (it still heartbeats & returns 0 records for paused tps)
    }
}
// elsewhere, when capacity frees up:
consumer.resume(consumer.paused());
```

> Critical: even when paused, you **must keep calling `poll()`** to stay in the group (it returns no records for paused partitions but still heartbeats and maintains membership). Stopping `poll()` entirely triggers the `max.poll.interval.ms` eviction.

Use for: rate-limiting to a slow DB/API, in-order retry of a stuck record without blocking other partitions.

### 5.7 Exactly-once read-process-write (Kafka → Kafka, transactional)

```java
// Atomically: consume from "input", produce to "output", and commit input offsets.
producerProps.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "txn-app-1"); // stable, unique
producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
KafkaProducer<String,String> producer = new KafkaProducer<>(producerProps);
producer.initTransactions();

consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); // skip aborted txns
KafkaConsumer<String,String> consumer = new KafkaConsumer<>(consumerProps);
consumer.subscribe(List.of("input"));

while (true) {
    var records = consumer.poll(Duration.ofMillis(100));
    if (records.isEmpty()) continue;
    producer.beginTransaction();
    try {
        for (var r : records) {
            producer.send(new ProducerRecord<>("output", r.key(), transform(r.value())));
        }
        // offsets travel INSIDE the transaction -> atomic with the produced records
        Map<TopicPartition, OffsetAndMetadata> offsets = currentOffsets(records);
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
        producer.commitTransaction();   // all-or-nothing
    } catch (Exception e) {
        producer.abortTransaction();    // nothing leaks; reprocess input
    }
}
```

Use for: stream pipelines where duplicate output records are unacceptable. (Kafka Streams does this for you with `processing.guarantee=exactly_once_v2`.)

### 5.8 Spring Kafka equivalent (declarative)

```java
@KafkaListener(topics = "orders", groupId = "fulfilment",
               concurrency = "3")              // 3 consumer threads → up to 3 partitions in parallel
public void onOrder(ConsumerRecord<String, String> record, Acknowledgment ack) {
    fulfilmentService.handle(record.value());
    ack.acknowledge();                          // manual ack (AckMode.MANUAL)
}
// container factory: factory.getContainerProperties().setAckMode(AckMode.MANUAL);
// Spring handles the poll loop, error handling, retries, and dead-letter topics for you.
```

Use for: Spring apps; you get retry/backoff (`DefaultErrorHandler`), dead-letter publishing, and observability with little code.

### 5.9 Static membership (avoid rebalances on rolling restart)

```java
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "consumer-az1-3"); // stable per instance
props.put(ConsumerConfig.SESSION_TIMEOUT_CONFIG, 60000);             // long enough to survive a restart
// On a quick restart within session.timeout, the coordinator recognizes the same instance id
// and re-grants the SAME partitions WITHOUT a rebalance. See §7.3.
```

Use for: stateful consumers (Kafka Streams, large local caches) and K8s deployments doing rolling restarts.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & throughput

- **Batch bigger, commit less.** Raise `fetch.min.bytes` (e.g. 64 KB–1 MB) and `fetch.max.wait.ms` to fetch fewer, larger batches; raise `max.poll.records` if processing is cheap. Don't `commitSync()` per record — commit per batch (or async per batch + sync backstop).
- **Parallelism = partitions.** To go faster, add partitions *and* consumers together. Adding consumers beyond partition count does nothing.
- **Decouple fetch from processing** for CPU-heavy work: poll on one thread, hand records to a worker pool — but then *you* own offset ordering (don't commit an offset until all lower offsets in that partition are done). The pause/resume pattern (§5.6) keeps this safe. Beware: naive "thread per record" breaks per-partition ordering and offset semantics.
- **Compression & deserialization** dominate CPU for high-volume topics; profile deserializers (Avro/Protobuf schema lookups can be a hidden cost — cache the schema registry client).
- **Follower fetching (KIP-392):** set `client.rack` to read from a same-AZ replica and cut cross-AZ network cost/latency. *Requires broker support and `replica.selector.class` configured.*

### 6.2 Correctness & concurrency

- `KafkaConsumer` is **single-threaded**: never share one instance across threads (except `wakeup()`). Sharing causes `ConcurrentModificationException` or corruption.
- Always commit **last-processed + 1**.
- For at-least-once, **commit only after processing succeeds**, and make `process()` **idempotent** (so reprocessing duplicates is harmless — e.g. upsert by key, dedupe on a unique event id). Idempotency is the practical foundation of "effectively-once" against external systems.
- On rebalance, **flush/commit in `onPartitionsRevoked`** so the next owner resumes correctly.
- With cooperative rebalancing, `onPartitionsRevoked` may receive only the *moved* partitions (not all) — write listener logic that handles partial sets.

### 6.3 Memory

- Memory ≈ `max.partition.fetch.bytes` × (assigned partitions) for in-flight fetch buffers, plus `fetch.max.bytes`. With many partitions and large per-partition limits, fetch buffers can balloon — size them deliberately.
- Large `max.poll.records` × large record size = large heap spikes per poll. Tune together.

### 6.4 Security

- **Transport:** `security.protocol=SSL` or `SASL_SSL` for encryption in transit.
- **AuthN:** SASL mechanisms — `PLAIN`, `SCRAM-SHA-256/512`, `GSSAPI` (Kerberos), `OAUTHBEARER`.
- **AuthZ (ACLs):** a consumer needs `READ` on the **Topic** *and* `READ` on the **Group** resource. Forgetting the Group ACL is a classic "I can't join but the topic ACL is fine" failure.
- Reading `__consumer_offsets` directly requires cluster-level permission; don't expose it.

### 6.5 Observability — the metrics that matter

Consumer client JMX metrics (under `kafka.consumer:type=consumer-fetch-manager-metrics` etc.):

| Metric | Why it matters |
|---|---|
| `records-lag-max` | Max lag across assigned partitions — the headline health number |
| `records-lag` (per-partition) | Locate the hot/stuck partition |
| `records-consumed-rate`, `bytes-consumed-rate` | Throughput |
| `fetch-latency-avg/max` | Broker/network health |
| `commit-latency-avg/max`, `commit-rate` | Commit cost/cadence |
| `rebalance-rate-per-hour`, `rebalance-latency-avg` | Rebalance churn (should be near zero in steady state) |
| `last-poll-seconds-ago` / `time-between-poll-avg/max` | Detect slow-processing toward `max.poll.interval.ms` |
| `heartbeat-rate`, `last-heartbeat-seconds-ago` | Liveness |
| `assigned-partitions` | Did this instance get any work? |

**Server-side lag** is best monitored externally with **Burrow** (LinkedIn) or **Kafka Lag Exporter** → Prometheus → Grafana/alerts, because client-side lag metrics vanish when the consumer is down (exactly when you most want to know). Burrow evaluates lag *trends* ("is lag growing?") rather than a raw threshold.

### 6.6 Cost

- Cross-AZ traffic is a major cloud bill line; follower fetching (`client.rack`) and co-locating consumers with leaders reduce it.
- Over-partitioning increases broker metadata, file handles, and rebalance time — partitions aren't free.
- Frequent `commitSync` adds request load to the coordinator broker; batch commits.

### 6.7 Testing

- **`MockConsumer`** (`org.apache.kafka.clients.consumer.MockConsumer`) — in-memory consumer for unit tests; you `addRecord()`, `rebalance()`, `updateBeginningOffsets()` etc.
- **Testcontainers Kafka** / **EmbeddedKafka** (Spring) for integration tests against a real broker.
- Test rebalance behavior explicitly: simulate a member leaving and assert offsets resume correctly.

### 6.8 Production hardening checklist

- `enable.auto.commit=false` for anything correctness-sensitive; commit after processing.
- Make processing **idempotent**.
- Set `max.poll.interval.ms` > worst-case batch time; lower `max.poll.records` if batches are slow.
- Use **static membership** + reasonable `session.timeout.ms` to survive rolling restarts.
- Use **CooperativeStickyAssignor** to minimize rebalance disruption (roll the upgrade carefully).
- Implement a clean shutdown (`wakeup()` + final `commitSync()` + `close()`).
- Handle deserialization failures (poison pills) — use `ErrorHandlingDeserializer` (Spring) or catch in a custom deserializer; otherwise one bad record wedges the partition forever.
- Set `allow.auto.create.topics=false` in prod to avoid accidental topic creation.
- Alert on **lag trend** and **rebalance rate**, not just instantaneous lag.

### 6.9 Anti-patterns

- **Long processing inside the poll loop without raising `max.poll.interval.ms`** → endless rebalances.
- **Sharing one consumer across threads.**
- **`commitAsync` only, no `commitSync` backstop** → lost final commit on crash → reprocessing.
- **Forgetting `+1` on manual commits.**
- **Committing before processing** thinking it's "safer" → silent data loss.
- **Subscribing to a regex that matches `__consumer_offsets` or other internal topics.**
- **One giant partition for a high-volume key** → that partition's single consumer is the bottleneck (hot-partition skew).
- **Letting a group go idle past `offsets.retention.minutes`** → offsets expire → mass reprocess/skip.
- **Stopping `poll()` during backpressure instead of `pause()`** → eviction.

---

## 7. Advanced topics & deep internals

### 7.1 Exactly-once semantics (EOS) deep dive

Kafka's EOS rests on three pillars:

1. **Idempotent producer** (`enable.idempotence=true`, default true since 3.0): the broker dedupes producer retries using a producer id + sequence number per partition, so a retried send doesn't create a duplicate.
2. **Transactions:** a producer with a `transactional.id` can atomically write to multiple partitions *and* commit consumer offsets (`sendOffsetsToTransaction`) as one unit. The **transaction coordinator** (a broker) writes transaction markers (commit/abort) into the partitions.
3. **`isolation.level=read_committed`** on the consumer: it only returns records from *committed* transactions, skipping aborted ones, by respecting the **Last Stable Offset (LSO)** — the consumer won't read past the LSO into records belonging to still-open transactions.

`exactly_once_v2` (Kafka 2.5+, KIP-447) made this scale: a single `transactional.id` per Streams *instance* (not per input partition), drastically reducing the number of producers. *Version-specific:* `exactly_once` (v1) is deprecated; use `exactly_once_v2`.

**Limits:** EOS is exactly-once *within Kafka*. Side effects to external systems are only effectively-once if those writes are idempotent or themselves transactional. Transactions add latency (marker writes, two-phase commit) — typically fine but not free.

### 7.2 Consumer lag, precisely

**Lag(partition) = log-end-offset(partition) − committed-offset(group, partition).** It's the count of records produced but not yet *committed* as consumed by this group. Notes:

- Lag is **per (group, partition)**; group lag is usually reported as sum and max.
- Lag can be high transiently (a burst) and that's fine; **persistent or growing lag** means consumers can't keep up — add partitions+consumers, speed up processing, or shed load.
- Lag in *time* (how old is the oldest unconsumed record) often matters more than count; derive it via `offsetsForTimes` or tools that expose "time lag."
- A consumer that's *down* shows lag growing without bound; that's why external lag monitoring (Burrow) is essential.
- `consumer.currentLag(tp)` (3.0+) gives a no-network client-side estimate using the last fetch's high-watermark.

### 7.3 Static membership (KIP-345) deep dive

Normally each `subscribe` consumer gets an ephemeral member id from the coordinator, so a restart looks like "old member left, new member joined" → two rebalances. **Static membership** assigns a stable `group.instance.id`:

- On graceful restart *within* `session.timeout.ms`, the coordinator sees the same `group.instance.id` rejoin and **re-grants the identical assignment with no rebalance**.
- This eliminates the two rebalances per rolling-restart of a member — huge for stateful consumers (Kafka Streams restoring large state stores) and K8s rollouts.
- Tradeoff: set `session.timeout.ms` long enough to cover a restart but short enough to detect real crashes. A statically-membered instance that truly dies still costs you `session.timeout.ms` of unavailability for its partitions.
- Two live instances with the *same* `group.instance.id` → the coordinator **fences** the older one (`FencedInstanceIdException`) — guards against split-brain.

### 7.4 Assignors in depth

- **RangeAssignor (default, first in list):** for each subscribed topic independently, sort partitions and consumers, divide partitions into contiguous ranges per consumer. With T topics each having P partitions and C consumers, consumer i gets a *range* of each topic. **Co-locates same-numbered partitions of different topics on the same consumer** — useful for joining two topics partitioned the same way. **Downside:** with many topics and uneven partition counts, lower-indexed consumers accumulate the remainder partitions → skew.
- **RoundRobinAssignor:** flatten all subscribed topic-partitions into one list, deal them round-robin across consumers. **Most even** when all consumers subscribe to the same topics; can shuffle a lot on every rebalance (not sticky).
- **StickyAssignor:** as even as round-robin **but** preserves as much of the previous assignment as possible on rebalance, minimizing partition movement (and thus state-store reload / cache warm-up). Still **eager** (stop-the-world revoke-all then reassign).
- **CooperativeStickyAssignor (recommended):** sticky + the **incremental cooperative** protocol (§3.4) so unmoved partitions never stop. This is the modern default choice for plain consumers.

**Upgrading an eager group to cooperative** requires a two-phase rolling restart: first deploy with the strategy list `[StickyAssignor, CooperativeStickyAssignor]` (so the group picks Sticky while members are mixed), then once all members support cooperative, deploy `[CooperativeStickyAssignor]`. Skipping this and mixing eager+cooperative in one group causes errors/incorrect assignment. *Version- and procedure-specific.*

### 7.5 The new protocol (KIP-848) implications

Under `group.protocol=consumer`: assignment moves server-side; the broker becomes the single source of truth for target assignment; members reconcile incrementally via heartbeats; there's no client group-leader. Configs like `session.timeout.ms`, `heartbeat.interval.ms`, and assignment strategy become **group-level broker configs** (set via `kafka-configs.sh` / admin API), not client configs. Operationally this means smoother autoscaling and fewer "thundering herd" rebalances. *Confirm broker+client support before adopting.*

### 7.6 Offset reset & leader epoch

`auto.offset.reset` only fires when there is **no valid committed offset** (new group, expired offsets, or `seek` to an out-of-range offset). Modern clients also track **leader epoch** with offsets to detect and recover from **log truncation** after an unclean leader election (a follower that was behind becomes leader and the log "shrinks"); the consumer uses `OffsetForLeaderEpoch` to find a safe resume point rather than silently reading stale/duplicate data. (Unclean leader election = promoting an out-of-sync replica to leader to restore availability at the cost of possibly losing the most recent records; controlled by `unclean.leader.election.enable`, default false.)

### 7.7 Cooperative fetch internals & prefetching

The consumer prefetches: it issues fetches for partitions whose buffered data is below a threshold, even while you process the current batch, so the next `poll()` often returns instantly from buffer. `poll()` returns at most `max.poll.records` regardless of how much is buffered. This is why throughput is smooth and why a single huge `fetch.max.bytes` doesn't translate into one giant `poll()` return.

### 7.8 `__consumer_offsets` compaction & coordinator failover

Because `__consumer_offsets` is compacted and replicated (RF 3), if the coordinator broker fails, another replica of that offsets partition becomes leader and the new coordinator reconstructs group state from the compacted log + transaction state. Consumers transparently re-discover the coordinator (`FindCoordinator`) and continue. Committed offsets are never lost as long as the offsets partition survives (RF ≥ majority).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Commit strategy

| Strategy | Latency impact | Throughput | Guarantee | Use when |
|---|---|---|---|---|
| Auto-commit | Low | High | ~at-least-once, with loss window | Non-critical, simple |
| commitSync per batch | Medium | Medium | At-least-once, durable | Default safe choice |
| commitAsync + sync backstop | Low | High | At-least-once | High-throughput pipelines |
| commit before process | Low | High | At-most-once | Loss OK, dupes not (rare) |
| Transactional EOS | Higher | Medium | Exactly-once (Kafka↔Kafka) | No-duplicate pipelines |

**Use auto-commit when** you can tolerate occasional dupes and small losses and want minimal code. **Avoid auto-commit when** correctness matters — its loss window is silent.

### 8.2 Assignor

| Goal | Choose |
|---|---|
| Minimal rebalance disruption, autoscaling | **CooperativeStickyAssignor** |
| Even spread, simple, single-topic | RoundRobin or Range |
| Join two co-partitioned topics on same consumer | Range |
| Minimize movement but stuck on old (eager) protocol | Sticky |

**Use CooperativeSticky** unless you have a specific reason; **avoid mixing** assignor families within a group.

### 8.3 Membership

| Need | Choose |
|---|---|
| Stateless, elastic scaling | Dynamic membership (subscribe) |
| Stateful, frequent rolling restarts | **Static membership** (`group.instance.id`) |
| Pinned partitions / custom coordination | `assign()` (no group) |

### 8.4 subscribe vs assign

**Use `subscribe`** for automatic scaling and failover (99% of cases). **Use `assign`** when you need deterministic, pinned partition ownership and will handle failover yourself, or for one-off tooling/replay.

### 8.5 Kafka vs traditional broker (consumer perspective)

| Dimension | Kafka consumer | RabbitMQ/JMS queue consumer |
|---|---|---|
| Model | Pull, offset-based, log retained | Push, ack-based, message removed |
| Replay | Yes (seek to any offset) | No (once acked, gone) |
| Multi-subscriber | Many independent groups read same data | Needs fanout exchange/topics |
| Ordering | Per-partition total order | Per-queue; lost with competing consumers |
| Parallelism cap | = partitions | = prefetch/consumers, finer-grained |
| Per-message ack | No (offset-range) | Yes (per message) |

**Use Kafka** for high-throughput streams, replay, and many consumers. **Use a classic queue** when you need fine-grained per-message ack/redelivery, priority queues, or low-volume task distribution.

---

## 9. Failure modes & debugging

### 9.1 Constant rebalancing ("rebalance storm")

**Symptoms:** `rebalance-rate-per-hour` high; logs full of "Revoking previously assigned partitions" / "Attempt to heartbeat failed since group is rebalancing"; lag oscillates; throughput tanks.

**Common causes & fixes:**
- **Processing exceeds `max.poll.interval.ms`.** The consumer is alive but slow; gets evicted each batch. → Increase `max.poll.interval.ms`, lower `max.poll.records`, or move processing off the poll thread. Check `time-between-poll-max`.
- **Long GC pauses / process freezes** exceed `session.timeout.ms`. → Tune GC, increase `session.timeout.ms`, or use static membership.
- **Members flapping** (autoscaler scaling up/down). → Static membership + cooperative assignor.
- **Network blips** to the coordinator. → Increase `session.timeout.ms`.

**Diagnose:** `kafka-consumer-groups.sh --describe --group g --state` (look for `PreparingRebalance` flicker), client metric `last-poll-seconds-ago`, broker logs on the coordinator.

### 9.2 Growing lag

**Symptoms:** `records-lag-max` climbing; downstream data stale; Burrow shows "lag increasing."

**Causes & fixes:** consumer too slow (add partitions+consumers, optimize `process()`, batch downstream writes), a single **hot partition** (re-key the producer, increase partitions), a stuck consumer holding a partition (find it via `--describe`'s CONSUMER-ID/HOST column), or downstream backpressure (pause/resume, scale the sink).

**Diagnose:** `kafka-consumer-groups.sh --describe --group g` → the LAG column per partition shows exactly which partition is behind and which consumer owns it.

### 9.3 Stuck consumer / no progress, lag not moving

- **Poison pill (deserialization error):** a record fails to deserialize, the same poll keeps throwing, the partition never advances. → Use `ErrorHandlingDeserializer` (Spring) or a defensive deserializer that routes bad records to a DLT; or `seek` past the offending offset.
- **Blocking call in `process()`** (a hung HTTP call) wedges the loop. → Add timeouts; pause/resume.
- **No assignment:** `assigned-partitions=0` because there are more consumers than partitions, or the consumer never joined (ACL missing on the Group resource). → Check partition count vs members; check Group READ ACL.

### 9.4 Duplicate processing

Expected under at-least-once after any crash/rebalance. If excessive: you're committing too rarely, or `process()` isn't idempotent, or you `commitAsync` without a sync backstop and lost a commit. → Commit more often (per batch), make processing idempotent, add the `finally`-block `commitSync`.

### 9.5 Data loss / skipped records

- **Auto-commit loss window** (§3.7). → Disable auto-commit, commit after processing.
- **Offsets expired** (group idle > `offsets.retention.minutes`) and `auto.offset.reset=latest` → on restart it skips everything between the lost offset and the tail. → Raise retention, monitor for idle groups, or set `earliest` if reprocessing is safer than skipping.
- **Unclean leader election** truncated the log. → Keep `unclean.leader.election.enable=false`; rely on leader-epoch recovery.

### 9.6 `CommitFailedException`

Thrown by `commitSync` when the consumer was kicked out of the group (rebalance happened, e.g. due to slow processing) and is no longer the owner of the partition it's trying to commit. → It's a symptom of §9.1; fix the rebalance cause. Don't blindly retry; the partition may now belong to someone else.

### 9.7 Real-world incident patterns

- **The "5-minute mystery":** a service that calls a slow external API per record processes a 500-record batch in > 5 min, gets evicted at `max.poll.interval.ms`, rebalances, reprocesses, and never catches up — a self-perpetuating storm. Fix: lower `max.poll.records` to e.g. 50, raise `max.poll.interval.ms`, or async the API calls with pause/resume.
- **The "weekend offset wipe":** a low-traffic group is stopped Friday for maintenance; offsets expire over a long weekend (old 24h retention); Monday it restarts with `latest` and silently skips a weekend of data. Fix: raise `offsets.retention.minutes`, alert on idle groups.
- **The "dev RF=1 surprise":** offsets topic created with RF 1 on a single-broker dev cluster; promoting that config or a broker loss wipes group offsets. Fix: ensure RF 3 in prod (`offsets.topic.replication.factor`).
- **The "rolling restart churn":** every deploy triggers two rebalances per pod; with 50 pods the group is rebalancing for minutes. Fix: static membership + cooperative assignor.

### 9.8 Debugging toolbelt recap

`kafka-consumer-groups.sh --describe` (lag, ownership, state) · client JMX metrics (`records-lag-max`, `time-between-poll-max`, `rebalance-rate`) · Burrow / Kafka Lag Exporter for trend-based alerting · coordinator broker logs · `--reset-offsets --dry-run` for recovery planning · `MockConsumer` to reproduce rebalance logic in tests.

---

## 10. Interview drill

**Q1. How does a consumer group achieve scaling and what limits parallelism?**
Each partition is assigned to exactly one consumer in the group; a consumer can hold many partitions. So work spreads across members, but the max useful parallelism equals the partition count — extra members sit idle.
- *Follow-up: Why can't two consumers in a group read the same partition?* Because per-partition ordering and single-owner offset tracking require exactly one reader; two readers would race on offsets and break ordering.
- *Follow-up: How do you increase parallelism beyond current partitions?* Add partitions (carefully — it changes key→partition mapping and breaks per-key ordering for existing keys) and add consumers together.
- *Follow-up: Two different groups on the same topic?* They're independent; each reads the full topic with its own offsets (pub/sub layered over queue-like balancing).

**Q2. Walk me through what `poll()` does internally.**
Find coordinator → join/sync group if needed (get assignment) → fetch/reset committed offsets for new partitions → send fetch requests → maybe auto-commit prior positions → return up to `max.poll.records` from buffer → advance position. It also implicitly enforces the poll-interval liveness contract.
- *Follow-up: Where do heartbeats happen?* On a separate background thread, not in `poll()`.
- *Follow-up: What if processing a batch takes longer than `max.poll.interval.ms`?* The consumer voluntarily leaves the group, triggering a rebalance; the next `commitSync` may throw `CommitFailedException`.

**Q3. Difference between `session.timeout.ms` and `max.poll.interval.ms`?**
`session.timeout.ms` (with the heartbeat thread) detects a *dead/frozen* process; `max.poll.interval.ms` detects a *live but stuck* consumer that isn't calling `poll()`. They guard different failure modes on different threads.
- *Follow-up: Recommended heartbeat relationship?* `heartbeat.interval.ms` ≤ session.timeout/3.
- *Follow-up: Why were they separated (KIP-62)?* So you can detect crashes quickly while still allowing long processing between polls.

**Q4. Explain offset management and the default delivery semantics.**
Committed offsets (last-processed+1) are stored in `__consumer_offsets`. Default is auto-commit + process-after-poll → at-least-once (reprocess on crash, no loss). Switching to commit-before-process gives at-most-once; transactions give exactly-once Kafka-to-Kafka.
- *Follow-up: Why is the committed value last-processed+1?* So resume reads the next unprocessed record, not the last processed one again.
- *Follow-up: Hidden danger of auto-commit?* It commits inside poll on a timer, assuming returned==processed; a crash mid-batch can both lose and duplicate — neither clean guarantee.

**Q5. `commitSync` vs `commitAsync` — when each?**
`commitSync` blocks and retries (reliable, slower); `commitAsync` is non-blocking and does *not* retry (so a stale retry can't move offsets backward). Idiomatic: async in the loop, sync in `finally`/on revoke.
- *Follow-up: Why doesn't async retry?* A later commit may supersede it; retrying an old offset could regress the committed position.
- *Follow-up: Where must you commit during a rebalance?* In `onPartitionsRevoked`, synchronously, before losing the partition.

**Q6. Compare the partition assignors.**
Range (per-topic ranges, co-locates same-index partitions, can skew), RoundRobin (even, not sticky), Sticky (even + minimal movement, eager), CooperativeSticky (sticky + incremental cooperative rebalancing — unmoved partitions never stop). Prefer CooperativeSticky.
- *Follow-up: What does "cooperative" buy you?* No stop-the-world; only partitions that actually move are revoked, over up to two rebalances.
- *Follow-up: How do you migrate eager→cooperative safely?* Two-phase rolling restart with `[Sticky, CooperativeSticky]` then `[CooperativeSticky]`.

**Q7. What is static membership and when do you use it?**
A stable `group.instance.id` so a quick restart within `session.timeout.ms` re-grants the same partitions with no rebalance — ideal for stateful consumers and rolling restarts.
- *Follow-up: Risk if an instance truly dies?* Its partitions are unavailable for up to `session.timeout.ms`.
- *Follow-up: Two instances, same instance id?* The newer fences the older (`FencedInstanceIdException`).

**Q8. Define consumer lag and how you monitor it.**
Lag = log-end-offset − committed-offset per (group, partition). Monitor via `kafka-consumer-groups.sh --describe`, client metric `records-lag-max`, and externally with Burrow/Kafka Lag Exporter (trend-based, survives consumer downtime).
- *Follow-up: Why external monitoring?* Client metrics disappear when the consumer is down — exactly when lag is worst.
- *Follow-up: Count lag vs time lag?* Time lag (age of oldest unconsumed record) often reflects business SLA better; derive via `offsetsForTimes`.

**Q9 (senior-signal). You see constant rebalancing in production. How do you root-cause and fix it without guessing?**
Check whether it's heartbeat-driven or poll-driven: inspect `time-between-poll-max` / `last-poll-seconds-ago` (poll-interval issue) vs heartbeat metrics and GC logs (session issue). If processing time approaches `max.poll.interval.ms`, lower `max.poll.records` and/or raise the interval and async the work; if it's GC/network, raise `session.timeout.ms` or use static membership; if it's autoscaler flapping, add static membership + cooperative assignor. Justify the chosen lever with the metric that pointed to it.
- *Follow-up: Why not just crank every timeout up?* Long timeouts delay real crash detection, hurting availability; tune the specific failing dimension.
- *Follow-up: How does CooperativeSticky help here?* It removes the stop-the-world cost so the residual rebalances are far cheaper while you fix the root cause.

**Q10 (senior-signal). How do you choose a commit/delivery strategy for a payments service, and how do you defend it?**
Disable auto-commit; commit after processing (at-least-once); make processing idempotent (dedupe by payment id / upsert) so reprocessing is harmless; use commitAsync in-loop with a commitSync backstop for throughput+durability; consider transactional EOS only if it's a pure Kafka→Kafka step. Defend it: payments can't lose data (rules out at-most-once and auto-commit's loss window) and can't double-charge (so idempotency, not just at-least-once, is the real guarantee against external side effects).
- *Follow-up: Why not full EOS everywhere?* It only covers Kafka↔Kafka; the actual charge is an external side effect, so idempotency is required regardless, and EOS adds latency/complexity.
- *Follow-up: How do you bound reprocessing after a crash?* Commit per small batch and commit on revoke, so the replay window is at most one batch.

**Q11 (senior-signal). How would you size partitions and consumers for a topic projected to grow 10× in throughput?**
Partition count caps consumer parallelism and is disruptive to raise (rekeying breaks per-key ordering), so provision partitions for the *future* peak parallelism with modest headroom, while accounting for per-partition broker overhead (file handles, metadata, rebalance time). Match consumer instances to partitions, scale them elastically with cooperative assignor + static membership, and watch lag trend as the leading indicator for when to add capacity.
- *Follow-up: Downside of massive over-partitioning "just in case"?* More metadata, slower rebalances, more open files, higher end-to-end latency, and no benefit beyond your real parallelism.
- *Follow-up: What breaks if you increase partitions later?* Hash-based key→partition mapping changes, so a given key may move partitions, breaking per-key ordering for in-flight keys and any state keyed by partition.

**Q12. What's stored in `__consumer_offsets` and what happens if the coordinator broker dies?**
Committed offsets keyed by (group, topic, partition) plus group metadata, in a compacted, RF-3 topic with 50 partitions; a group maps to one partition (its coordinator's). If the coordinator dies, a replica of that offsets partition becomes leader/new coordinator and rebuilds group state from the compacted log; consumers re-discover it via `FindCoordinator` and continue with no offset loss.
- *Follow-up: How does a group find its coordinator?* `partition = murmur2(group.id) % 50`; the leader of that partition is the coordinator.
- *Follow-up: Why is the topic compacted?* To retain only the latest offset per key so it doesn't grow unbounded.

---

## 11. Glossary

- **At-least-once:** delivery guarantee where no record is lost but duplicates are possible (process-then-commit). Kafka's default.
- **At-most-once:** no duplicates but possible loss (commit-then-process).
- **Assignor / partition.assignment.strategy:** client-side logic that maps partitions to group members (Range, RoundRobin, Sticky, CooperativeSticky).
- **Auto-commit:** periodic automatic offset commit inside `poll()` (`enable.auto.commit`, every `auto.commit.interval.ms`).
- **Backpressure:** mechanism by which a slow consumer slows intake (pull model, pause/resume) rather than being overwhelmed.
- **Broker:** a single Kafka server; a cluster is many brokers.
- **Burrow:** LinkedIn's external consumer-lag monitoring tool that evaluates lag trends.
- **Cluster:** a set of cooperating brokers.
- **Commit (offset commit):** persisting the consumer's progress (next offset to read) to `__consumer_offsets`.
- **`commitSync` / `commitAsync`:** blocking-and-retrying vs non-blocking-no-retry offset commit APIs.
- **Compaction (log compaction):** retention policy keeping only the latest value per key; used by `__consumer_offsets`.
- **Consumer:** client that reads records from topics.
- **Consumer group:** set of consumers sharing a `group.id` that collectively consume topics, each partition owned by one member.
- **`__consumer_offsets`:** internal compacted topic storing committed offsets and group metadata (default 50 partitions, RF 3).
- **Coordinator (group coordinator):** broker managing a group's membership, rebalances, and offset storage.
- **Cooperative rebalancing:** incremental rebalance protocol that revokes only moving partitions (KIP-429).
- **CDC (change data capture):** capturing database changes as an event stream, often consumed from Kafka.
- **Deserializer:** converts record bytes back into key/value objects.
- **DLT (dead-letter topic):** a topic where unprocessable ("poison") records are routed.
- **EOS (exactly-once semantics):** no loss and no duplicates, via idempotent producer + transactions + `read_committed` (Kafka↔Kafka).
- **Fencing:** rejecting a stale/duplicate member (e.g. `FencedInstanceIdException`) to prevent split-brain.
- **Fetch:** consumer request to a leader broker for records starting at a position.
- **Group leader (consumer):** the member designated by the coordinator to compute the assignment (classic protocol).
- **`group.id`:** the string identifying a consumer group.
- **`group.instance.id`:** stable id enabling static membership.
- **Heartbeat:** periodic liveness signal from the consumer's background thread to the coordinator.
- **High watermark / log-end offset (LEO):** the offset just past the last record (the tail); the boundary of consumable data.
- **Idempotent processing:** processing where reapplying the same record has no extra effect; the practical basis of "effectively once."
- **Idempotent producer:** producer that dedupes its own retries via producer id + sequence numbers.
- **Isolation level:** `read_uncommitted` (default) vs `read_committed` (skip aborted-transaction records).
- **JoinGroup / SyncGroup:** the two RPCs of the classic rebalance handshake.
- **KIP:** Kafka Improvement Proposal (e.g. KIP-62, -345, -429, -447, -848).
- **KRaft:** Kafka's Raft-based internal consensus replacing ZooKeeper for cluster metadata.
- **Lag (consumer lag):** log-end-offset − committed-offset per (group, partition); how far behind the tail you are.
- **Last Stable Offset (LSO):** highest offset a `read_committed` consumer may read past safely (no open transactions before it).
- **Leader (partition leader):** broker that handles reads/writes for a partition; followers replicate it.
- **Leader epoch:** version counter on partition leadership used to detect/recover from log truncation.
- **`max.poll.interval.ms`:** max allowed gap between `poll()` calls before voluntary group leave (default 300000).
- **`max.poll.records`:** max records returned per `poll()` (default 500).
- **MockConsumer:** in-memory consumer for unit testing.
- **Offset:** monotonically increasing position of a record within a partition.
- **`offsets.retention.minutes`:** how long committed offsets survive after a group goes idle (default 7 days).
- **Partition:** ordered, append-only subdivision of a topic; the unit of parallelism and ordering.
- **Poll loop:** the application loop calling `poll()` and processing returned records.
- **Position:** the in-memory offset of the next record `poll()` will return.
- **Pull model:** consumers fetch from brokers (vs brokers pushing).
- **Raft:** a consensus algorithm (leader + majority quorum) underpinning KRaft.
- **Rebalance:** redistribution of partitions among group members on membership/metadata change.
- **Record / message:** the data unit (key, value, timestamp, headers).
- **Replication factor (RF):** number of copies of a partition across brokers.
- **Retention:** how long records stay in a topic regardless of consumption (time/size based).
- **`seek` / `seekToBeginning` / `seekToEnd`:** APIs to set the consumer's position for replay/skip.
- **`session.timeout.ms`:** time without heartbeats before a member is declared dead (default 45000).
- **Static membership:** stable membership via `group.instance.id` to avoid rebalances on restart (KIP-345).
- **Sticky assignment:** assignor minimizing partition movement across rebalances.
- **Subscribe vs assign:** dynamic group-managed assignment vs manual fixed assignment.
- **Topic:** named append-only stream split into partitions.
- **Transaction (Kafka):** atomic unit grouping produces + offset commits across partitions for EOS.
- **Transaction coordinator:** broker managing transaction state and commit/abort markers.
- **Unclean leader election:** promoting an out-of-sync replica to leader (availability over durability; default off).
- **`wakeup()`:** the only thread-safe `KafkaConsumer` method; interrupts a blocking `poll()` for clean shutdown.
- **ZooKeeper:** legacy external coordination service for Kafka metadata, replaced by KRaft.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Invariant:** one partition → one consumer per group. **Max parallelism = partition count.**

**Two clocks:** `session.timeout.ms` (45000, heartbeat thread, catches death/freeze) vs `max.poll.interval.ms` (300000, poll thread, catches slow processing). `heartbeat.interval.ms` (3000) ≤ session/3.

**Key defaults:** `max.poll.records` 500 · `enable.auto.commit` true · `auto.commit.interval.ms` 5000 · `auto.offset.reset` latest · `fetch.min.bytes` 1 · `fetch.max.wait.ms` 500 · `max.partition.fetch.bytes` 1 MB · `fetch.max.bytes` 50 MB · `offsets.topic.num.partitions` 50 · `offsets.topic.replication.factor` 3 · `offsets.retention.minutes` 10080 (7d) · `isolation.level` read_uncommitted.

**Offsets:** stored in `__consumer_offsets` (compacted, key=(group,topic,partition)); commit value = last-processed **+1**; group→partition = `murmur2(group.id) % 50`.

**Delivery:** process→commit = at-least-once (default); commit→process = at-most-once; transactional read-process-write = exactly-once (Kafka↔Kafka). Make processing idempotent regardless.

**Commit:** `commitSync` blocks+retries; `commitAsync` non-blocking, no retry. Pattern: async in loop, sync in `finally`/on revoke. Don't forget `+1`.

**Assignors:** Range (co-locate same-index, can skew) · RoundRobin (even) · Sticky (even+minimal move, eager) · **CooperativeSticky** (sticky+incremental, recommended). Migrate eager→cooperative in two phases.

**Static membership:** `group.instance.id` → no rebalance on quick restart; size `session.timeout.ms` accordingly.

**Lag:** LEO − committed per (group,partition). Monitor `records-lag-max` + Burrow/Lag Exporter (trend). Diagnose with `kafka-consumer-groups.sh --describe`.

**Decision rules:** subscribe for auto scaling/failover; assign for pinning. CooperativeSticky unless a reason not to. Disable auto-commit for correctness. Static membership for stateful + rolling restarts. Provision partitions for future peak parallelism (raising later breaks per-key ordering).

**Top failure → fix:** rebalance storm → check poll-interval vs heartbeat metrics, lower `max.poll.records`/raise interval or static+cooperative. Growing lag → add partitions+consumers / fix hot partition. Stuck → poison pill (ErrorHandlingDeserializer/DLT). Loss → disable auto-commit, raise offset retention. `CommitFailedException` → you were evicted (fix the rebalance cause).

**Shutdown:** `wakeup()` → break poll → `commitSync()` in finally → `close()`.

### 12.2 Self-test (no answers — recall actively)

1. A topic has 8 partitions and a group has 12 members. How many members do useful work, and what determines whether you can speed things up by adding more? What must you change first?
2. Your `process()` averages 4 minutes per 500-record batch. Which timeout will evict you, what symptom appears, and which two configs would you change and why?
3. Explain precisely what value you commit for a record at offset 4096 and what goes wrong on restart if you commit the wrong value.
4. Auto-commit is on. Trace a crash scenario that causes (a) duplicates and (b) data loss, and explain why auto-commit gives neither clean guarantee.
5. Walk through the eager rebalance protocol step by step, then contrast each step with cooperative rebalancing. Why does cooperative reduce downtime?
6. You must guarantee no double-charge in a payments consumer that calls an external charge API. Lay out your full strategy (commit mode, idempotency, EOS or not) and justify each choice against the alternatives.
7. A low-traffic group is offline for 9 days, then restarts and silently skips a week of data. Name the two configs responsible and how you'd prevent it.
8. Given only `kafka-consumer-groups.sh --describe` output, how do you locate a single hot/stuck partition and identify which consumer instance owns it?

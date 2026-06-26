# Kafka Consumer Group Rebalancing — A Definitive Engineering Reference

> **Scope:** Everything a senior JVM backend engineer needs to *master* Kafka consumer-group rebalancing: from "what is a consumer group" up to the cooperative-sticky assignor internals, static membership, rebalance storms, and production debugging. Java-first, with concrete defaults and version flags throughout.

---

## 1. Overview & where it fits

### 1.1 What rebalancing is

A **Kafka consumer group** is a set of consumer processes that cooperate to read a set of topic-partitions exactly once *as a group*: every partition the group subscribes to is assigned to exactly one member at a time, and the group's read position advances as a unit. **Rebalancing** is the protocol by which the group **re-divides the partitions among its current live members** whenever the membership or the subscription changes.

Think of it as a **distributed assignment problem solved repeatedly at runtime**. The "work" is the set of partitions. The "workers" are the consumer instances. Rebalancing is the recurring negotiation — "who owns what now?" — triggered every time a worker joins, leaves, dies, or the work itself changes shape.

> **Term — partition (beginner):** A Kafka topic is split into ordered, append-only logs called *partitions*. Partitions are the unit of parallelism and ordering. A topic with 12 partitions can be consumed by up to 12 group members in parallel; ordering is guaranteed *within* a partition, not across partitions.

> **Term — offset (beginner):** Each record in a partition has a monotonically increasing integer position called its *offset*. A consumer tracks "the next offset I will read" per partition. *Committing* an offset persists "I have processed up to here" so that after a crash or reassignment, processing resumes from that point and not from the beginning.

### 1.2 The problem it solves

You want **horizontal scalability and fault tolerance** for consuming a topic, without manually assigning partitions to machines. Rebalancing gives you:

- **Elasticity:** add a consumer instance → it automatically picks up a share of partitions. Remove one → its partitions are redistributed.
- **Fault tolerance:** a consumer crashes → the group detects it and reassigns its partitions to survivors, so consumption continues.
- **No single coordinator you have to run:** the cluster brokers themselves coordinate the group.

The cost: during a rebalance, the group's progress can **pause** (partially or fully, depending on protocol), and if rebalances happen too often the group can spend more time rebalancing than consuming. Most of this document is about controlling that cost.

### 1.3 When you reach for it

You essentially always use rebalancing if you use the **high-level consumer with `subscribe()`** (group management). You *avoid* it only if you use **manual partition assignment** with `assign()` — which trades automatic fault tolerance and elasticity for full control (covered in §8).

### 1.4 One-paragraph mental model

A broker elected as the **group coordinator** holds the group's state. Each consumer sends periodic **heartbeats** to it. When membership or subscription changes, the coordinator declares a new **generation** and runs a two-phase handshake: **JoinGroup** (everyone re-announces themselves; one member is elected *leader*) and **SyncGroup** (the leader computes the partition→member assignment using a pluggable **assignor** and the coordinator distributes it). Members then own their assigned partitions until the next generation. The two competing styles are **eager** (everyone drops everything, then reclaims — "stop-the-world") and **incremental cooperative** (only the partitions that actually need to move are revoked — minimal disruption).

---

## 2. Foundations from first principles

### 2.1 The actors

| Actor | What it is | Where it runs |
|---|---|---|
| **Consumer instance** | A `KafkaConsumer` object in your JVM, member of a group | Your application process |
| **Consumer group** | Logical set of consumers sharing a `group.id` | Logical; state lives on a broker |
| **Group coordinator** | A broker that owns one group's membership/offset state | A specific broker, chosen by hashing `group.id` |
| **Group leader** | One *consumer* (not a broker) elected to compute assignments | A consumer instance |
| **`__consumer_offsets`** | Internal compacted topic storing committed offsets and group metadata | Spread across brokers |

> **Term — broker (beginner):** A Kafka *broker* is a single server in the Kafka cluster. Brokers store partition data and serve produce/fetch requests. One cluster has many brokers.

> **Term — coordinator vs leader (critical distinction):** The **coordinator is a broker**; the **leader is a consumer**. The coordinator manages the protocol and persistence; the leader does the *math* of who-gets-what. This split keeps the assignment logic client-side and pluggable while keeping authoritative state server-side.

### 2.2 How the coordinator is chosen

The `__consumer_offsets` topic has (by default) **50 partitions** (`offsets.topic.num.partitions`, default `50`). For a given group, its coordinator is the **leader broker of the `__consumer_offsets` partition** computed as:

```
partition = abs(murmur2(group.id.getBytes("UTF-8"))) % offsets.topic.num.partitions
```

The broker hosting the leader replica of that partition is the group coordinator. Every member discovers it via a `FindCoordinator` request at startup.

> **Term — compacted topic (beginner):** A *log-compacted* topic keeps only the **latest value per key**, garbage-collecting older values. `__consumer_offsets` is keyed by `(group, topic, partition)` for offset records, so it retains the most recent committed offset per partition rather than a full history.

### 2.3 The membership lifecycle in one breath

1. Consumer starts → `FindCoordinator` → connects to coordinator.
2. `JoinGroup` → receives a `member.id` and a `generation.id`; coordinator picks a leader.
3. Leader computes assignment; everyone does `SyncGroup`; coordinator stores and hands each member its partitions.
4. Members **poll** records and **heartbeat** continuously.
5. On any trigger (§2.4), the cycle repeats with a new generation.

> **Term — generation / epoch (beginner):** The **generation id** is a monotonically increasing integer the coordinator stamps on the group each time it rebalances. It's a *fencing token*: requests carrying a stale generation are rejected, which prevents a slow/zombie member from acting on an outdated assignment. (This is the same idea as an *epoch* in Raft or a *term* — a number that strictly increases so old leaders/members can be detected and ignored.)

### 2.4 What triggers a rebalance

A rebalance is triggered when the coordinator observes that the group must be re-divided:

1. **A new member joins** (sends `JoinGroup` with empty/known member id).
2. **A member leaves gracefully** — calls `consumer.close()`, which sends a `LeaveGroup` request.
3. **A member is presumed dead** — it stops heartbeating within `session.timeout.ms`, OR it fails to call `poll()` within `max.poll.interval.ms` (the consumer proactively leaves in that case).
4. **Subscription changes** — a member's set of subscribed topics changes, or a pattern subscription (`subscribe(Pattern)`) starts matching new topics.
5. **Partition count changes** — a subscribed topic gains partitions (topics never lose partitions in Kafka). The metadata refresh (`metadata.max.age.ms`, default `300000` = 5 min) detects this and triggers a rebalance.
6. **Coordinator change/failover** — if the coordinator broker fails, members find the new coordinator and the group re-forms.

> **Heartbeats vs polling (key beginner point):** There are **two independent liveness mechanisms.**
> - **Heartbeats** run on a *background thread* (`heartbeat.interval.ms`, default `3000`) and must arrive within `session.timeout.ms` (default `45000` in modern clients). This detects a *dead process / network partition*.
> - **`poll()` cadence** is on *your application thread* and must recur within `max.poll.interval.ms` (default `300000` = 5 min). This detects a *live process that is stuck or too slow processing a batch* — heartbeats alone wouldn't catch that because the background thread is still alive.

### 2.5 What "owning a partition" means operationally

When a member is assigned partition P, it:
- Fetches records from P starting at its last committed (or reset) position.
- Is the **only** member allowed to commit offsets for P in this generation.
- Must, on revocation, stop fetching P and (usually) commit its progress before another member resumes it — otherwise records get reprocessed (at-least-once) or skipped.

This is why the **`ConsumerRebalanceListener` callbacks** (§3.6, §5.2) exist: they are your hook to commit offsets and flush state at exactly the revocation/assignment boundary.

---

## 3. How it works internally — the rebalance protocol

This section is the heart of the document. We trace the wire protocol, the state machine on both sides, and then the two rebalance *styles*.

### 3.1 The protocol primitives (request types)

| Request | Sender → Receiver | Purpose |
|---|---|---|
| `FindCoordinator` | consumer → any broker | Locate the group coordinator for `group.id` |
| `JoinGroup` | consumer → coordinator | Announce membership; receive `member.id`, generation, and (for leader) all members' subscriptions |
| `SyncGroup` | consumer → coordinator | Leader uploads the full assignment; each member downloads *its* slice |
| `Heartbeat` | consumer → coordinator | Liveness; coordinator replies `REBALANCE_IN_PROGRESS` to summon members into a rebalance |
| `OffsetCommit` | consumer → coordinator | Persist committed offsets to `__consumer_offsets` |
| `OffsetFetch` | consumer → coordinator | Read committed offsets (e.g., on startup) |
| `LeaveGroup` | consumer → coordinator | Graceful departure |

### 3.2 The coordinator-side group state machine

The coordinator models each group as a finite state machine:

```
        (group created / member joins)
Empty ───────────────► PreparingRebalance
  ▲                          │  all members re-joined OR rejoin timeout
  │                          ▼
  │                  CompletingRebalance   (a.k.a. AwaitingSync)
  │                          │  leader's SyncGroup received, assignment stored
  │                          ▼
  └──────────────────────  Stable
        (all members gone)     │
                               │ trigger (§2.4)
                               ▼
                       PreparingRebalance
   (special: Dead — group metadata removed, e.g. after expiry)
```

- **Empty:** group exists but has no active members (offsets may still be retained).
- **PreparingRebalance:** the coordinator has decided a rebalance is needed and is waiting for all known members to (re)send `JoinGroup`. It waits up to the **rebalance timeout** (= `max.poll.interval.ms`) for stragglers; members that don't show are evicted.
- **CompletingRebalance / AwaitingSync:** all join requests collected; coordinator returned the member list to the leader and is now waiting for the leader's `SyncGroup` carrying the computed assignment.
- **Stable:** assignment distributed; members are consuming and heartbeating.
- **Dead:** terminal; group metadata is being removed.

### 3.3 Phase 1 — JoinGroup (the "who's here" phase)

Step by step:

1. The coordinator enters **PreparingRebalance** and, on each member's next `Heartbeat`, replies with error `REBALANCE_IN_PROGRESS`. This is how members *learn* a rebalance is happening even if they didn't cause it.
2. Each member responds by sending `JoinGroup`, which includes its **subscribed topics** and its **assignor-specific metadata** (e.g., its currently-owned partitions, used by sticky assignors).
3. The coordinator collects all `JoinGroup`s until either every member has re-joined or the **rebalance timeout** expires.
4. The coordinator **elects a leader** — typically the first member to join the new generation — and increments the **generation id**.
5. The coordinator replies to every member with the new `generation.id` and their `member.id`. Crucially, **only the leader** receives the *full list of all members and their subscriptions*; followers get an empty member list.

> **Why the leader does the assignment:** It keeps the assignment policy on the client side, so you can plug in a custom `ConsumerPartitionAssignor` without changing brokers. The broker stays policy-agnostic.

### 3.4 Phase 2 — SyncGroup (the "here's your share" phase)

1. The leader runs the configured **assignor**'s `assign(clusterMetadata, subscriptions)` to produce a `Map<memberId, List<TopicPartition>>`.
2. The leader sends `SyncGroup` containing the *entire* assignment map.
3. Followers also send `SyncGroup` but with an **empty** assignment (they're just asking, "what's mine?").
4. The coordinator **persists the assignment** (in group metadata) and replies to each member with **only its own** partition list.
5. Each member now knows its partitions, transitions to fetching, and the group becomes **Stable**.

### 3.5 The protocol of protocols: `partition.assignment.strategy`

The two rebalance *styles* are not separate protocols — they're encoded as a chosen **assignor**. The assignor declares which **rebalance protocol** it supports: `EAGER` or `COOPERATIVE`. This is negotiated during `JoinGroup`; if members disagree, the group falls back to the highest commonly-supported protocol (in practice: any `EAGER`-only member forces the whole group to `EAGER`).

| Assignor class | Strategy name | Protocol | Sticky? | Notes |
|---|---|---|---|---|
| `RangeAssignor` | `range` | EAGER | No | Default historically; co-localizes same-index partitions of co-subscribed topics (good for joins) |
| `RoundRobinAssignor` | `roundrobin` | EAGER | No | Even spread across all partitions |
| `StickyAssignor` | `sticky` | EAGER | Yes (best-effort) | Eager but tries to keep prior assignments |
| `CooperativeStickyAssignor` | `cooperative-sticky` | **COOPERATIVE** | Yes | The modern default-recommended; incremental |
| `ConsumerPartitionAssignor` (interface) | — | either | — | Implement your own |

> **Default note (version-specific):** In the classic (ZooKeeper-era and early KRaft) clients, the default `partition.assignment.strategy` is `[RangeAssignor, CooperativeStickyAssignor]` for the consumer (Range listed first). The list means: prefer Range, but Cooperative-Sticky is available for negotiation. To get cooperative behavior you typically set it explicitly to `CooperativeStickyAssignor`. The new **KIP-848** "consumer group protocol" (next-gen, server-side assignment) changes this picture entirely — see §7.6.

### 3.6 The two styles, traced step by step

#### 3.6.1 Eager (stop-the-world) rebalancing

Used by `RangeAssignor`, `RoundRobinAssignor`, `StickyAssignor`.

**Invariant:** before a new assignment can be computed, *every member must give up all of its partitions*. The assignor assumes nobody owns anything when it computes the new map.

Trace (member B joins a group that had only A owning P0–P5):

1. Coordinator → PreparingRebalance; A's heartbeat returns `REBALANCE_IN_PROGRESS`.
2. **A revokes ALL partitions P0–P5.** It invokes `onPartitionsRevoked(P0..P5)` — your callback commits offsets here. A stops fetching everything. **This is the stop-the-world pause: A consumes nothing during the whole rebalance.**
3. A and B both `JoinGroup`. Leader elected.
4. Leader computes a fresh assignment from scratch, e.g. A←P0,P1,P2 ; B←P3,P4,P5.
5. `SyncGroup`; coordinator distributes.
6. A invokes `onPartitionsAssigned(P0,P1,P2)`; B invokes `onPartitionsAssigned(P3,P4,P5)`. Fetching resumes.

The damage: **A surrendered P0,P1,P2 only to get them right back**, but during steps 2–6 nothing was consumed. With large groups, this pause can be hundreds of milliseconds to seconds, repeated on every membership change.

#### 3.6.2 Incremental cooperative-sticky rebalancing (KIP-429)

Used by `CooperativeStickyAssignor`. The core idea: **only the partitions that must change hands are revoked, and the rest keep flowing.** A rebalance may take **two** rounds.

> **KIP (beginner):** A *KIP* is a "Kafka Improvement Proposal" — the design-doc/RFC process for evolving Kafka. KIP-429 introduced incremental cooperative rebalancing (Kafka 2.4, 2019).

Trace (same scenario: B joins, A owns P0–P5):

**Round 1 (revocation round):**
1. Coordinator → PreparingRebalance.
2. A and B `JoinGroup`. A reports it currently owns P0–P5; B reports it owns nothing.
3. Leader computes the *target* assignment: A←P0,P1,P2 ; B←P3,P4,P5. It compares to current ownership and sees P3,P4,P5 must move from A to B. **In the cooperative protocol, a partition that needs to change owners is first removed from the current owner and NOT immediately given to the new owner.** So the assignment returned in round 1 is: A←P0,P1,P2 ; B←(nothing yet).
4. `SyncGroup`. A sees its assignment shrank by P3,P4,P5 → invokes `onPartitionsRevoked(P3,P4,P5)` and commits those, **but keeps consuming P0,P1,P2 the entire time.** B sees it got nothing.
5. Because the assignment isn't yet "settled" (P3,P4,P5 are now unowned), the assignor triggers a **second rebalance** immediately (a member that detects newly-revoked partitions rejoins).

**Round 2 (assignment round):**
6. A and B `JoinGroup` again. Now A owns P0,P1,P2; B owns nothing; P3,P4,P5 are free.
7. Leader assigns the free partitions: B←P3,P4,P5. A unchanged.
8. `SyncGroup`. B invokes `onPartitionsAssigned(P3,P4,P5)` and starts fetching. A is untouched.

**Net effect:** A *never stopped consuming P0,P1,P2*. Only P3,P4,P5 paused, and only briefly. The price is one extra rebalance round and a slightly more complex callback contract (see `onPartitionsLost`, §3.7).

> **"Sticky" (beginner):** A *sticky* assignor minimizes movement — it prefers to leave each partition with its current owner and only moves the minimum needed to balance. This preserves warm caches, local state (e.g., RocksDB stores in Kafka Streams), and avoids re-reading from committed offsets.

### 3.7 The callback contract (eager vs cooperative)

`ConsumerRebalanceListener` has three methods:

```java
void onPartitionsRevoked(Collection<TopicPartition> revoked);   // about to lose these — commit now
void onPartitionsAssigned(Collection<TopicPartition> assigned); // just gained these — init state
void onPartitionsLost(Collection<TopicPartition> lost);         // default delegates to Revoked
```

| Behavior | Eager | Cooperative |
|---|---|---|
| `onPartitionsRevoked` receives | **all** currently owned partitions | **only** the partitions actually being moved away (often empty) |
| `onPartitionsAssigned` receives | the **full** new assignment | **only** the newly added partitions (incremental) |
| Can you still commit in `onPartitionsRevoked`? | Yes — you still own them at that instant | Yes — you still own them at that instant |
| `onPartitionsLost` | rarely distinct | called when partitions were lost **without** a clean revocation (e.g., you fell out of the group); **do not commit** here — you no longer own them, committing risks duplicate/conflict |

> **Why `onPartitionsLost` matters (KIP-429 detail):** In cooperative mode, if a member is fenced (stale generation) or times out, its partitions may already have been reassigned to someone else. Committing for them would be wrong. `onPartitionsLost` (added in 2.4) lets you *drop* state without committing. The default implementation calls `onPartitionsRevoked`, which is the safe-but-conservative behavior; override it when you need the distinction.

### 3.8 Where offsets fit: the commit/revoke ordering rule

The single most important correctness rule:

> **Commit the offsets for a partition *before* you stop owning it, inside `onPartitionsRevoked`, and make that commit synchronous.**

Sequence for safe handoff:
1. `onPartitionsRevoked(P)` fires.
2. You call `consumer.commitSync(offsetsForP)` — blocking, so you *know* it landed.
3. The rebalance proceeds; the new owner does `OffsetFetch(P)` and reads exactly where you stopped.

If you commit asynchronously or skip it, the new owner may resume from a stale offset → **reprocessing** (at-least-once) or, worse with auto-commit timing, **skipping** unprocessed records.

---

## 4. The complete toolkit

### 4.1 Consumer configs that govern rebalancing

| Config | Default | What it controls | Tuning guidance |
|---|---|---|---|
| `group.id` | none (required for groups) | The group identity; determines the coordinator | — |
| `partition.assignment.strategy` | `[RangeAssignor, CooperativeStickyAssignor]` (varies by version) | The assignor(s) / rebalance protocol | Set to `CooperativeStickyAssignor` for incremental rebalances |
| `session.timeout.ms` | `45000` (modern; was `10000` pre-3.0) | Max time between heartbeats before member is declared dead | Must be within broker bounds `[group.min.session.timeout.ms=6000, group.max.session.timeout.ms=1800000]` |
| `heartbeat.interval.ms` | `3000` | Background heartbeat cadence | Keep ≈ ⅓ of `session.timeout.ms` |
| `max.poll.interval.ms` | `300000` (5 min) | Max time between `poll()` calls before member self-evicts | Raise for slow processing; or reduce `max.poll.records` |
| `max.poll.records` | `500` | Max records returned per `poll()` | Lower it if per-record processing is slow, to keep within `max.poll.interval.ms` |
| `group.instance.id` | null | Enables **static membership** (KIP-345) | Set a stable, unique id per instance |
| `enable.auto.commit` | `true` | Auto-commit offsets every `auto.commit.interval.ms` | Set `false` for precise commit control around rebalances |
| `auto.commit.interval.ms` | `5000` | Auto-commit cadence | — |
| `auto.offset.reset` | `latest` | What to do when no committed offset exists / offset out of range | `earliest`/`latest`/`none` |
| `metadata.max.age.ms` | `300000` | How often to refresh cluster metadata (detects partition count changes) | Lower to detect new partitions faster |
| `internal.leave.group.on.close` | `true` (internal) | Whether `close()` sends `LeaveGroup` | Controlled indirectly; affects restart behavior |

### 4.2 Broker configs that bound the group protocol

| Broker config | Default | Purpose |
|---|---|---|
| `group.min.session.timeout.ms` | `6000` | Lower bound a consumer may request for `session.timeout.ms` |
| `group.max.session.timeout.ms` | `1800000` (30 min) | Upper bound for `session.timeout.ms` |
| `group.initial.rebalance.delay.ms` | `3000` | Delay before the **first** rebalance of an empty group, to let multiple members join together (avoids N sequential rebalances on cold start) |
| `offsets.topic.num.partitions` | `50` | Partitions of `__consumer_offsets`; affects coordinator distribution |
| `offsets.retention.minutes` | `10080` (7 days) | How long committed offsets survive after the group goes empty |
| `group.max.size` | `Integer.MAX_VALUE` (effectively unbounded) | Max members per group; protects coordinator |

### 4.3 Key Java APIs

| API | Purpose |
|---|---|
| `KafkaConsumer.subscribe(topics, ConsumerRebalanceListener)` | Join group with listener for revoke/assign hooks |
| `KafkaConsumer.subscribe(Pattern, listener)` | Pattern subscription (triggers rebalance when matching topics appear) |
| `KafkaConsumer.assign(partitions)` | **Manual** assignment — *no* group management, *no* rebalances |
| `KafkaConsumer.poll(Duration)` | Fetch records AND drive the rebalance/heartbeat machinery |
| `KafkaConsumer.commitSync()/commitAsync()` | Persist offsets |
| `KafkaConsumer.committed(partitions)` | Read committed offsets |
| `KafkaConsumer.position(partition)` | Current read position |
| `KafkaConsumer.seek/seekToBeginning/seekToEnd` | Reposition (often in `onPartitionsAssigned`) |
| `KafkaConsumer.enforceRebalance(String reason)` | **Force** a rebalance on next poll (added in 2.6; reason string added later) |
| `KafkaConsumer.groupMetadata()` | Returns `ConsumerGroupMetadata` (generation, memberId) — used by transactional producers (`sendOffsetsToTransaction`) |
| `ConsumerRebalanceListener` | The three callbacks (§3.7) |

### 4.4 CLI / admin tooling

| Command | Purpose |
|---|---|
| `kafka-consumer-groups.sh --describe --group G` | Show per-partition current offset, log-end offset, **lag**, owning member, client-id, host |
| `kafka-consumer-groups.sh --describe --group G --state` | Show group **state** (Stable / PreparingRebalance / Empty) and coordinator |
| `kafka-consumer-groups.sh --describe --group G --members --verbose` | Members and their assigned partitions |
| `kafka-consumer-groups.sh --list` | All groups |
| `kafka-consumer-groups.sh --reset-offsets ...` | Move offsets (to-earliest/to-latest/by-duration/to-datetime) — group must be inactive |
| `kafka-consumer-groups.sh --delete --group G` | Delete an empty group |
| `kafka-consumer-groups.sh --delete-offsets --group G --topic T` | Delete committed offsets for specific partitions |

> Example: `kafka-consumer-groups.sh --bootstrap-server b:9092 --describe --group payments --state` quickly tells you if a group is stuck in `PreparingRebalance` — the #1 symptom of a rebalance storm.

---

## 5. Code examples by use case

### 5.1 Baseline: manual-commit consumer with a rebalance listener (at-least-once, safe handoff)

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;
import java.util.*;

public class SafeAtLeastOnceConsumer {
    public static void main(String[] args) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "orders-processor");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringDeserializer");
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
              "org.apache.kafka.common.serialization.StringDeserializer");
        // Use the modern incremental protocol:
        p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
              "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        // We commit ourselves, precisely around rebalances:
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        final KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p);
        // Tracks the next offset to commit per partition (lastProcessed + 1).
        final Map<TopicPartition, OffsetAndMetadata> pending = new HashMap<>();

        ConsumerRebalanceListener listener = new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> revoked) {
                // We still own these right now -> commit synchronously so the
                // next owner resumes exactly where we stopped. Blocking is intentional.
                Map<TopicPartition, OffsetAndMetadata> toCommit = new HashMap<>();
                for (TopicPartition tp : revoked) {
                    if (pending.containsKey(tp)) toCommit.put(tp, pending.remove(tp));
                }
                if (!toCommit.isEmpty()) consumer.commitSync(toCommit);
            }
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> assigned) {
                // Newly added partitions (incremental in cooperative mode).
                // Initialize any per-partition state/caches here if needed.
            }
            @Override
            public void onPartitionsLost(Collection<TopicPartition> lost) {
                // We no longer own these (fenced/timed out). DO NOT commit.
                for (TopicPartition tp : lost) pending.remove(tp);
            }
        };

        consumer.subscribe(Collections.singletonList("orders"), listener);

        try {
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> r : records) {
                    process(r); // your business logic
                    // Record "I've processed up to r.offset" -> commit r.offset + 1.
                    pending.put(new TopicPartition(r.topic(), r.partition()),
                                new OffsetAndMetadata(r.offset() + 1));
                }
                // Periodic async commit for throughput; the sync commit in
                // onPartitionsRevoked handles correctness at the boundary.
                if (!pending.isEmpty()) consumer.commitAsync(pending, null);
            }
        } finally {
            try { consumer.commitSync(pending); } finally { consumer.close(); } // close() sends LeaveGroup
        }
    }
    static void process(ConsumerRecord<String, String> r) { /* ... */ }
}
```

Key points: commit happens **synchronously in `onPartitionsRevoked`** (correct handoff), `onPartitionsLost` deliberately **does not commit**, and `close()` triggers a graceful `LeaveGroup`.

### 5.2 Static membership to survive rolling restarts (KIP-345)

```java
Properties p = new Properties();
// ... bootstrap, group.id, deserializers as above ...

// The magic line: a STABLE identity that persists across process restarts.
// Each instance gets a unique, stable id (e.g., from the pod ordinal / hostname).
p.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, System.getenv("POD_NAME")); // e.g. "consumer-2"

// With static membership, raise session.timeout.ms above your restart time so a
// quick bounce doesn't expire the member and trigger a rebalance.
p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "120000"); // 2 minutes, must be <= broker max
```

What changes: a *static* member does **not** send `LeaveGroup` on `close()`, and when it restarts with the same `group.instance.id` within `session.timeout.ms`, the coordinator **re-attaches it to its previous assignment without a rebalance**. This is the standard cure for "every deploy triggers N rebalances." In Kubernetes, set `group.instance.id` from the StatefulSet pod ordinal so it's stable per replica.

> **Caveat:** With static membership, a *truly dead* instance is only detected after `session.timeout.ms`. Choose it long enough to ride out restarts but short enough that real failures are detected acceptably fast.

### 5.3 Pattern subscription that rebalances as topics appear

```java
import java.util.regex.Pattern;
// Subscribe to all topics matching the regex; a rebalance fires whenever a NEW
// matching topic is created (detected on metadata refresh, metadata.max.age.ms).
consumer.subscribe(Pattern.compile("events\\..*"), listener);
```

Use case: multi-tenant ingestion where new per-tenant topics appear at runtime. Be aware each new matching topic causes a rebalance; with eager assignors that's a stop-the-world pause, so prefer cooperative-sticky here.

### 5.4 Exactly-once: committing offsets *inside* a transaction (no manual rebalance commit)

```java
// Producer is transactional; consumer offsets are committed as part of the txn.
producer.beginTransaction();
for (ConsumerRecord<String,String> r : records) {
    producer.send(transform(r));
}
// Bind the commit to the consumer's CURRENT generation so a stale member is fenced.
producer.sendOffsetsToTransaction(currentOffsets(records), consumer.groupMetadata());
producer.commitTransaction(); // offsets + outputs commit atomically
```

> **Why `groupMetadata()` here:** Passing `ConsumerGroupMetadata` (which carries the generation id + member id) lets the broker **fence** the offset commit if this consumer has been kicked out of the group by a rebalance. Without it, a zombie consumer could commit offsets for partitions it no longer owns. This is the read-process-write exactly-once pattern; you typically do **not** also commit in `onPartitionsRevoked` because the transaction owns offset commits.

### 5.5 Forcing a rebalance deliberately

```java
// e.g., your custom assignor depends on external state that just changed and you
// want the group to recompute the assignment immediately rather than waiting.
consumer.enforceRebalance("external assignment input changed");
// Takes effect on the next poll().
```

### 5.6 Pausing partitions to stay alive during slow processing (avoid max.poll.interval eviction)

```java
ConsumerRecords<String,String> records = consumer.poll(Duration.ofMillis(500));
if (!records.isEmpty()) {
    // Hand the batch to a worker pool; pause so poll() keeps heartbeating
    // WITHOUT returning new records, preventing max.poll.interval.ms eviction.
    consumer.pause(consumer.assignment());
    submitToWorkers(records);
}
// When workers finish:
if (workersIdle()) {
    consumer.resume(consumer.assignment());
}
// Keep calling poll() on a timer even while paused — poll() with no new records
// still drives heartbeats and rebalance participation.
```

This is the canonical pattern for decoupling slow processing from the poll loop without falling out of the group.

---

## 6. Implementation concerns & best practices

### 6.1 Correctness

- **Always commit in `onPartitionsRevoked` with `commitSync`** for at-least-once handoff (§3.8). Async commits can race the rebalance.
- **Never commit in `onPartitionsLost`** — you don't own the partitions; the new owner may have already committed past you.
- **Idempotent processing** is the only real defense against the duplicates that at-least-once + rebalance inevitably produce. Design downstream writes to be idempotent (upserts keyed by a business id, or dedup by `(partition, offset)`).
- **Don't share a `KafkaConsumer` across threads.** It is not thread-safe (except `wakeup()`). Rebalance callbacks run on the poll thread.

### 6.2 Performance

- **Prefer `CooperativeStickyAssignor`** to eliminate stop-the-world pauses. Migrating from eager requires a two-step rolling upgrade (§7.3).
- **Right-size `max.poll.records`** so a batch finishes well within `max.poll.interval.ms`. If 500 records take 6 minutes, you'll be evicted (default 5 min) → rebalance storm.
- **Keep `heartbeat.interval.ms` ≈ session/3.** Too low wastes coordinator cycles; too high delays failure detection.

### 6.3 Stability — avoiding rebalance storms

A **rebalance storm** is a feedback loop where rebalances trigger more rebalances and the group never reaches `Stable`. Top causes and cures:

| Cause | Cure |
|---|---|
| Processing exceeds `max.poll.interval.ms` → self-eviction → rejoin → repeat | Lower `max.poll.records`, raise `max.poll.interval.ms`, or offload to a worker pool with `pause()/resume()` (§5.6) |
| Long GC pauses / CPU starvation stall heartbeats → `session.timeout.ms` expiry | Tune JVM GC; raise `session.timeout.ms`; fix resource contention |
| Rolling deploys: every restart = leave + join | **Static membership** (§5.2) + adequate `session.timeout.ms` |
| Eager protocol amplifies any blip into full stop-the-world | Switch to cooperative-sticky |
| Misconfigured liveness (heartbeat ≥ session) | Set heartbeat ≈ session/3 |
| Frequent new topics matching a `Pattern` subscription | Pre-create topics or accept cooperative-sticky's lower cost |

### 6.4 Observability

Metrics to watch (consumer client JMX, `kafka.consumer:type=consumer-coordinator-metrics`):

| Metric | Meaning |
|---|---|
| `rebalance-rate-per-hour` | How often the group rebalances — should be near zero in steady state |
| `rebalance-latency-avg` / `-max` | How long rebalances take |
| `last-rebalance-seconds-ago` | Time since last rebalance |
| `failed-rebalance-rate-per-hour` | Rebalances that didn't complete |
| `assigned-partitions` | Current ownership count |
| `commit-latency-avg` | Offset commit cost (affects revoke time) |
| `heartbeat-rate` / `heartbeat-response-time-max` | Heartbeat health |
| `records-lag-max` (consumer-fetch metrics) | Consumer lag — spikes during rebalances |

Server-side: monitor group `state` and consumer **lag** via `kafka-consumer-groups.sh --describe --state`.

### 6.5 Testing

- Use a `MockConsumer` (in `kafka-clients`) to unit-test callback logic; you can call `rebalance(partitions)` to simulate assignment changes.
- Integration-test with `EmbeddedKafka` (Spring Kafka) or Testcontainers; kill a member mid-consumption and assert no records are lost/skipped.
- Chaos-test: introduce GC pauses / network partitions and verify your group recovers without data loss and that idempotency holds.

### 6.6 Production hardening checklist

- `CooperativeStickyAssignor` set explicitly.
- Static membership for stable deployments.
- `enable.auto.commit=false` with commit-on-revoke for precise control (unless using EOS transactions).
- Alert on `rebalance-rate-per-hour > threshold` and on group stuck in `PreparingRebalance`.
- `max.poll.interval.ms` comfortably above worst-case batch time; `max.poll.records` tuned accordingly.
- Idempotent downstream writes.

### 6.7 Anti-patterns

- Doing heavy/blocking work synchronously in the poll loop (causes eviction).
- Committing asynchronously in `onPartitionsRevoked`.
- Catching and swallowing `WakeupException`/`InterruptException` incorrectly (breaks clean shutdown / `LeaveGroup`).
- Mixing `assign()` and `subscribe()` on the same consumer.
- Setting `session.timeout.ms` outside broker bounds (consumer fails to join).
- Using one giant group with thousands of members on one coordinator without raising `group.max.size` awareness (coordinator load).

---

## 7. Advanced topics & deep internals

### 7.1 Generation fencing in depth

Every `JoinGroup`/`SyncGroup`/`Heartbeat`/`OffsetCommit` carries the `generation.id`. After a rebalance, a slow member that wakes up with the old generation gets `ILLEGAL_GENERATION` and must rejoin. This prevents a **split-brain** where two members believe they own the same partition. Combined with EOS's `sendOffsetsToTransaction(groupMetadata)`, it provides end-to-end fencing of zombie consumers.

### 7.2 The "second rebalance" cost of cooperative mode

KIP-429 incremental rebalancing can take **two rebalance rounds** when partitions move (§3.6.2). The benefit is that *consumption keeps flowing* for unaffected partitions during both rounds — so total *throughput* impact is far lower than eager's single stop-the-world round, despite the extra round. The extra round is cheap because no partition is paused for the partitions that aren't moving.

### 7.3 Migrating from eager to cooperative (the two-rolling-restart upgrade)

You **cannot** flip the strategy in one deploy — a group must not simultaneously contain `EAGER`-only and `COOPERATIVE`-only members negotiating incompatibly. The supported path (KIP-429 upgrade notes):

1. **First rolling restart:** set `partition.assignment.strategy=[CooperativeStickyAssignor, RangeAssignor]` (or whatever eager you used) — i.e., list cooperative *first* but keep the old eager strategy as a fallback. The group still operates in EAGER because at least one member can only do eager until all are upgraded; the negotiated protocol stays eager.
2. After all instances run the new config, the group can negotiate COOPERATIVE.
3. **Second rolling restart:** remove the eager fallback, leaving only `CooperativeStickyAssignor`.

Skipping the two-step process risks a member being assigned partitions it hasn't revoked under the wrong protocol assumption.

### 7.4 Static membership internals (KIP-345)

A static member is identified by `(group.id, group.instance.id)` rather than the ephemeral broker-assigned `member.id`. Key behaviors:

- On `close()`, a static member **does not** send `LeaveGroup` (so a planned restart doesn't trigger a rebalance).
- On restart, `JoinGroup` with the same `group.instance.id` **replaces** the prior member id and **reuses the prior assignment** — no rebalance if it happens within `session.timeout.ms`.
- Duplicate `group.instance.id` (two live processes with the same id) is rejected via `FENCED_INSTANCE_ID` — the older one is fenced. This protects against accidental double-deploys.
- The tradeoff: failure detection latency = `session.timeout.ms` (since no proactive leave). Pick it deliberately.

### 7.5 Custom assignors

Implement `org.apache.kafka.clients.consumer.ConsumerPartitionAssignor`:

- `name()` — strategy name on the wire.
- `subscriptionUserData(topics)` — opaque bytes each member ships in `JoinGroup` (e.g., rack id, processing capacity, prior ownership).
- `assign(metadata, groupSubscription)` — the actual assignment computation, run on the leader.
- `supportedProtocols()` — declare EAGER and/or COOPERATIVE.
- `onAssignment(assignment, metadata)` — callback after each member gets its slice.

Use cases: rack-aware assignment (co-locate consumer and partition leader replica to cut cross-AZ traffic — see `RackAwareAssignor` ideas / KIP-881 for follow-the-leader rack awareness), weighted assignment for heterogeneous hardware, or co-partitioning two topics for stream-stream joins.

> **Term — rack awareness (beginner):** A "rack" is a failure/locality domain (e.g., an AWS Availability Zone). Rack-aware assignment tries to assign a consumer partitions whose replicas live in the same rack, reducing cross-AZ network cost and latency.

### 7.6 The next-generation protocol: KIP-848 (consumer rebalance protocol)

A major redesign (GA around Kafka 4.0, configurable via `group.protocol=consumer`). It moves assignment computation **from the client leader to the broker (coordinator)** and makes rebalancing **fully incremental and asynchronous** via a reconciliation loop driven by heartbeats — eliminating the global JoinGroup/SyncGroup synchronization barrier and most stop-the-world behavior. Key implications:

- No more client-side leader election for assignment; the coordinator runs a server-side assignor.
- `partition.assignment.strategy` is replaced by server-side assignor selection (`group.remote.assignor`).
- Heartbeats carry incremental reconciliation state; members converge to the target assignment partition-by-partition.
- Dramatically reduces rebalance storms and large-group rebalance latency.

Flag this as **version-specific**: KIP-848 is the new "consumer protocol"; the classic protocol described in §3 remains the default in 3.x and is what most production systems run today. Verify your broker and client versions before relying on KIP-848 behavior.

### 7.7 `group.initial.rebalance.delay.ms` — cold-start damping

When an empty group first forms, the coordinator waits `group.initial.rebalance.delay.ms` (default `3000`) before the first assignment, so that if you start 10 consumers at once they (mostly) join within the window and get **one** rebalance instead of up to 10 sequential ones. Tune up for very large groups that start slowly.

### 7.8 Offset retention and "phantom resets"

If a group goes **empty** (all members leave) and stays empty past `offsets.retention.minutes` (default 7 days), the committed offsets are deleted. When the group restarts, `auto.offset.reset` kicks in — `earliest` reprocesses everything, `latest` skips the backlog. A common production surprise after a long outage. (Historically retention was tied to `__consumer_offsets` topic retention; KIP-211 changed it so retention starts when the group becomes empty, not at each commit.)

---

## 8. Tradeoffs & decision frameworks

### 8.1 Assignor selection

| Assignor | Balance quality | Movement on change | Pause behavior | Use when | Avoid when |
|---|---|---|---|---|---|
| `RangeAssignor` | Can be uneven across topics; co-locates same-index partitions | High (eager full reassign) | Stop-the-world | You need co-partitioning for joins and don't mind eager | Large groups / frequent changes |
| `RoundRobinAssignor` | Even | High (eager) | Stop-the-world | Even spread matters, few rebalances | Stateful/sticky needs |
| `StickyAssignor` | Even + sticky | Low | Stop-the-world (still eager!) | You want stickiness but stay on eager | You want zero pause |
| `CooperativeStickyAssignor` | Even + sticky | Minimal | Incremental, near-zero pause | **Default recommendation** | Mixed-version group mid-migration |
| Custom | Whatever you code | Whatever you code | Either | Rack-awareness, weighting, co-partitioning | Simple cases (overkill) |

### 8.2 `subscribe()` (group mgmt) vs `assign()` (manual)

| Aspect | `subscribe()` | `assign()` |
|---|---|---|
| Rebalancing | Yes (automatic) | No |
| Fault tolerance | Automatic reassignment | You must handle it |
| Elasticity | Add/remove members freely | Manual repartitioning |
| Offset commits | Group-tracked | Still possible per-partition |
| Use when | General consumption, scaling | Strict partition pinning, custom orchestrators (e.g., Kafka Streams uses its own assignor; some CDC tools pin partitions) |

### 8.3 Static vs dynamic membership

| | Dynamic | Static (KIP-345) |
|---|---|---|
| Rebalance on restart | Yes | No (if within session timeout) |
| Failure detection | Fast (heartbeat/session) | Slower (waits full `session.timeout.ms`) |
| Identity | Ephemeral `member.id` | Stable `group.instance.id` |
| Best for | Autoscaling, ephemeral workers | Stable, frequently-deployed services (StatefulSets) |

### 8.4 Liveness tuning rule of thumb

```
heartbeat.interval.ms ≈ session.timeout.ms / 3
session.timeout.ms ≥ worst-case GC/stall you tolerate (and within broker bounds 6s..30min)
max.poll.interval.ms ≥ worst-case time to process one max.poll.records batch (with margin)
```

---

## 9. Failure modes & debugging

### 9.1 Symptom: group permanently in `PreparingRebalance`

**Diagnose:** `kafka-consumer-groups.sh --describe --group G --state`. If it never reaches `Stable`, a member keeps leaving/rejoining.

**Likely causes:** processing exceeds `max.poll.interval.ms` (storm), or one bad instance with mismatched `partition.assignment.strategy`, or GC pauses tripping `session.timeout.ms`.

**Fix:** check client logs for `Member ... sending LeaveGroup request ... due to consumer poll timeout has expired` (the max.poll signature) or `failed to send heartbeat ... session timeout` (the heartbeat signature). Address per §6.3.

### 9.2 Symptom: rising lag with periodic resets

Lag climbs, then drops, repeatedly. Classic rebalance storm — the group rebalances faster than it processes. Confirm with `rebalance-rate-per-hour` JMX metric. Cure: cooperative-sticky + static membership + right-sized batches.

### 9.3 Symptom: duplicate processing after deploys

Every deploy causes reprocessing. Cause: eager rebalance + at-least-once + non-idempotent writes. Cure: idempotent sinks, static membership, commit-on-revoke, and `CooperativeStickyAssignor` to limit which partitions move.

### 9.4 Symptom: `FENCED_INSTANCE_ID`

Two processes share the same `group.instance.id`. Cause: bad templating (e.g., all pods got the same env var) or an old pod not terminated before the new one started. Cure: derive `group.instance.id` from a guaranteed-unique stable source (StatefulSet ordinal).

### 9.5 Symptom: `CommitFailedException` in the poll loop

Message: *"Commit cannot be completed since the group has already rebalanced and assigned the partitions to another member."* Cause: your `commitSync()` ran after you were evicted (poll loop too slow → rebalance happened). Cure: reduce processing time per poll; commit in `onPartitionsRevoked`; handle the exception by not retrying the now-invalid offsets.

### 9.6 Symptom: brand-new consumer reprocesses everything

After a long outage, group offsets were purged (`offsets.retention.minutes`) and `auto.offset.reset=earliest` replayed the topic. Cure: be aware of retention; consider `latest` if replay is unacceptable; keep at least one member alive or commit periodically to refresh retention (offsets retention resets when the group is active again under KIP-211 semantics).

### 9.7 Real-world failure patterns (composite, vendor-neutral)

- **The "thundering herd deploy":** A 60-instance group on eager `RangeAssignor` is rolling-deployed; each of 60 restarts triggers a full stop-the-world rebalance, lag spikes for minutes. Fix that's repeatedly reported as effective: static membership + cooperative-sticky reduces deploy-time disruption to near zero.
- **The "slow record":** One downstream API call occasionally takes 6 minutes; default `max.poll.interval.ms=5min` evicts the consumer mid-batch, the batch is reprocessed, the slow call happens again → permanent storm. Fix: offload with `pause()/resume()` + worker pool, or raise the interval.
- **The "GC cliff":** A memory leak grows heap, full GC pauses hit 10s, heartbeats stall, members drop and rejoin in waves. Fix: GC tuning + raised `session.timeout.ms` buys headroom while the leak is fixed.

### 9.8 Debugging toolkit recap

| Tool | Use |
|---|---|
| `kafka-consumer-groups.sh --describe --state` | Is the group Stable or stuck? Who's the coordinator? |
| `--describe --members --verbose` | Who owns what right now |
| Client JMX `consumer-coordinator-metrics` | Rebalance rate/latency, heartbeat health |
| Client logs (DEBUG on `org.apache.kafka.clients.consumer.internals`) | See JoinGroup/SyncGroup, leave reasons |
| `kafka-consumer-groups.sh --reset-offsets` | Recover from bad resets/replays |

---

## 10. Interview drill

**Q1. What triggers a consumer-group rebalance?**
Model answer: membership change (a member joins; leaves gracefully via `close()`/`LeaveGroup`; or is presumed dead via `session.timeout.ms` heartbeat expiry or `max.poll.interval.ms` poll-gap eviction), a subscription change (topics added, or a `Pattern` subscription matching a new topic), a partition-count increase on a subscribed topic, or a coordinator failover.
- *Follow-up: difference between session timeout and poll interval eviction?* Session timeout is missed **heartbeats** (background thread) → detects dead process. Poll interval is the gap between **`poll()`** calls (app thread) → detects a live-but-stuck consumer. They're independent.
- *Follow-up: does adding partitions to a topic rebalance immediately?* No — it's detected on the next metadata refresh (`metadata.max.age.ms`, default 5 min) or sooner if metadata is fetched for another reason.

**Q2. Walk through the rebalance protocol on the wire.**
Model answer: `FindCoordinator` → coordinator. On trigger, coordinator → PreparingRebalance, signals members via `REBALANCE_IN_PROGRESS` on heartbeat. Members send `JoinGroup` (with subscriptions/userdata); coordinator elects a leader, bumps generation, returns the member list to the leader only. Leader computes assignment; everyone sends `SyncGroup`; coordinator persists and returns each member its slice; group → Stable.
- *Follow-up: coordinator vs leader?* Coordinator is a **broker** managing protocol/state; leader is a **consumer** computing the assignment.
- *Follow-up: why client-side assignment?* Keeps assignment policy pluggable without broker changes.

**Q3. Explain eager vs cooperative-sticky rebalancing.**
Model answer: Eager (Range/RoundRobin/Sticky) requires *every* member to revoke *all* partitions before reassignment — stop-the-world. Cooperative-sticky (KIP-429) revokes *only* the partitions that must change owners; everything else keeps flowing. Cooperative can take two rounds (revoke round, then assign round) but causes far less throughput loss.
- *Follow-up: why two rounds?* A partition that changes owners is first revoked from the old owner (round 1) and only assigned to the new owner once it's free (round 2), to never have two owners.
- *Follow-up: migration path eager→cooperative?* Two rolling restarts: first add `CooperativeStickyAssignor` ahead of the eager one (still runs eager until all upgraded), then remove the eager fallback.

**Q4. How do you avoid rebalances on rolling restarts?**
Model answer: **Static membership (KIP-345)** — set a stable `group.instance.id` per instance and a `session.timeout.ms` longer than the restart window. Static members don't send `LeaveGroup` on close and re-attach to their prior assignment on restart, so no rebalance.
- *Follow-up: downside?* Real failures take up to `session.timeout.ms` to detect.
- *Follow-up: what if two pods share an instance id?* `FENCED_INSTANCE_ID` — one is fenced. Derive the id from a unique stable source.

**Q5. What is a rebalance storm and how do you fix it?** *(senior-signal)*
Model answer: a feedback loop where the group never reaches Stable because rebalances keep firing. Common roots: processing exceeding `max.poll.interval.ms`, GC pauses tripping `session.timeout.ms`, every deploy triggering leave/join, or eager protocol amplifying blips. Fixes: cooperative-sticky, static membership, right-size `max.poll.records`/`max.poll.interval.ms`, offload slow work with `pause()/resume()`, and tune liveness (`heartbeat ≈ session/3`). I'd confirm with `rebalance-rate-per-hour` JMX and the group `--state`.

**Q6. How do you commit offsets safely across a rebalance?**
Model answer: in `onPartitionsRevoked`, `commitSync` the offsets for the revoked partitions *before* releasing them, so the next owner resumes exactly there. Don't commit in `onPartitionsLost` (you no longer own them). With EOS, commit offsets inside the transaction via `sendOffsetsToTransaction(offsets, consumer.groupMetadata())` and skip the revoke commit.
- *Follow-up: why `commitSync` not async?* You must know the commit landed before handoff; async could race the rebalance.
- *Follow-up: what does `groupMetadata()` add?* The generation id, which fences zombie commits from evicted members.

**Q7. What's the relationship between `max.poll.interval.ms` and rebalances?**
Model answer: if your code doesn't call `poll()` within `max.poll.interval.ms`, the consumer proactively leaves the group (sends `LeaveGroup` with a poll-timeout reason), triggering a rebalance — even though heartbeats were fine. It exists to detect a live-but-stuck consumer.
- *Follow-up: how to handle genuinely long processing?* Raise the interval, lower `max.poll.records`, or `pause()` and process off-thread while still calling `poll()`.

**Q8. Why split heartbeat (background thread) from poll cadence (app thread)?** *(senior-signal)*
Model answer: they detect different failures. Heartbeats catch a dead JVM/network partition quickly without depending on application progress; poll cadence catches an application that's alive but wedged in processing — which heartbeats alone would miss because the background thread keeps beating. Decoupling lets you tune fast failure detection (short session timeout) independently from tolerating slow batches (long poll interval).

**Q9. When would you choose `RangeAssignor` over `CooperativeStickyAssignor`?** *(senior-signal)*
Model answer: rarely for raw throughput, but Range co-locates the same partition index across co-subscribed topics, which is useful when you join two topics and want the same member to own `topicA-3` and `topicB-3`. If I needed that co-partitioning and the group was stable enough that stop-the-world pauses were acceptable, Range is defensible. Otherwise cooperative-sticky wins on operational smoothness.

**Q10. How does the coordinator get chosen, and what happens if it fails?**
Model answer: `partition = murmur2(group.id) % offsets.topic.num.partitions`; the leader broker of that `__consumer_offsets` partition is the coordinator. On its failure, the partition's new leader becomes the coordinator; members detect the lost connection, re-`FindCoordinator`, and the group re-forms (a rebalance).
- *Follow-up: where is group/offset state stored?* In `__consumer_offsets`, a compacted internal topic.

**Q11. What changes with KIP-848?** *(senior-signal)*
Model answer: it moves assignment to the broker and makes rebalancing fully incremental/asynchronous via a heartbeat-driven reconciliation loop, removing the JoinGroup/SyncGroup synchronization barrier and most stop-the-world behavior. It replaces client `partition.assignment.strategy` with server-side assignors. It's the new `group.protocol=consumer` (GA ~Kafka 4.0); classic protocol remains the 3.x default. Worth flagging version-sensitivity before relying on it.

**Q12. A consumer logs `CommitFailedException`. What happened and how do you fix it?**
Model answer: the group rebalanced and reassigned the partitions before your `commitSync` completed — usually because the poll loop was too slow and `max.poll.interval.ms` evicted you. Fix by shortening per-poll processing, committing in `onPartitionsRevoked`, and not blindly retrying the stale commit.

---

## 11. Glossary

- **At-least-once:** delivery semantics where a record may be processed more than once but never lost; the default with offset-after-processing commits.
- **Assignor (`ConsumerPartitionAssignor`):** pluggable strategy run by the group leader to map partitions to members; declares EAGER/COOPERATIVE support.
- **Broker:** a single Kafka server holding partition data.
- **Compacted topic:** a topic that retains only the latest value per key (e.g., `__consumer_offsets`).
- **Cooperative rebalancing (KIP-429):** incremental protocol revoking only partitions that must move; minimal disruption.
- **Coordinator (group coordinator):** the broker that owns a group's membership and offset state.
- **Eager rebalancing:** protocol where all members revoke all partitions before reassignment ("stop-the-world").
- **Epoch / generation id:** monotonically increasing fencing token stamped on the group each rebalance; rejects stale members.
- **Exactly-once (EOS):** semantics where each record affects state exactly once, via transactions + `sendOffsetsToTransaction`.
- **Fencing:** rejecting actions from stale/zombie members using generation ids or instance-id checks (`FENCED_INSTANCE_ID`, `ILLEGAL_GENERATION`).
- **`group.id`:** identifier shared by members of a consumer group.
- **`group.instance.id`:** stable per-instance id enabling static membership.
- **Heartbeat:** periodic liveness signal sent on a background thread to the coordinator.
- **JoinGroup / SyncGroup:** the two phases of the classic rebalance handshake.
- **KIP (Kafka Improvement Proposal):** the RFC process for Kafka changes.
- **KIP-345:** static membership.
- **KIP-429:** incremental cooperative rebalancing.
- **KIP-848:** next-gen broker-driven consumer rebalance protocol.
- **Lag:** difference between a partition's log-end offset and the consumer's committed/current offset.
- **Leader (group leader):** the consumer elected to compute the assignment.
- **`max.poll.interval.ms`:** max gap between `poll()` calls before self-eviction.
- **`member.id`:** ephemeral coordinator-assigned member identifier.
- **murmur2:** the non-cryptographic hash Kafka uses to map keys to partitions (including `group.id` → coordinator partition).
- **Offset:** a record's integer position within a partition.
- **Partition:** an ordered, append-only log; the unit of parallelism and ordering.
- **`pause()/resume()`:** consumer methods to stop/start fetching specific partitions while still calling `poll()`.
- **Rack awareness:** assigning partitions to reduce cross-availability-zone traffic.
- **Rebalance:** the process of re-dividing partitions among current group members.
- **Rebalance storm:** a loop of repeated rebalances preventing the group from reaching Stable.
- **`session.timeout.ms`:** max time without a heartbeat before a member is declared dead.
- **Static membership:** keeping a stable identity across restarts to avoid rebalances (KIP-345).
- **Sticky assignor:** one that minimizes partition movement across rebalances.
- **Stop-the-world:** the full consumption pause during an eager rebalance.
- **`__consumer_offsets`:** internal compacted topic storing committed offsets and group metadata.
- **`ConsumerRebalanceListener`:** the callback interface (`onPartitionsRevoked/Assigned/Lost`).
- **`onPartitionsLost`:** callback when partitions are lost without clean revocation; do not commit there.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Triggers:** join / leave(`close()`→`LeaveGroup`) / dead(`session.timeout.ms` or `max.poll.interval.ms`) / subscription change / +partitions / coordinator failover.
- **Two liveness clocks:** heartbeat (bg thread, `heartbeat.interval.ms=3000`, must hit within `session.timeout.ms=45000`) vs poll cadence (`max.poll.interval.ms=300000`).
- **Protocol:** `FindCoordinator` → `JoinGroup`(elect leader, bump generation) → `SyncGroup`(leader assigns) → Stable. Coordinator = broker; leader = consumer.
- **Coordinator:** `murmur2(group.id) % 50` partition of `__consumer_offsets`.
- **Eager** = revoke all, stop-the-world (Range/RoundRobin/Sticky). **Cooperative-sticky (KIP-429)** = revoke only movers, 2 rounds, near-zero pause. **Use cooperative-sticky.**
- **Static membership (KIP-345):** set `group.instance.id`; no rebalance on restart within `session.timeout.ms`; failures detected only after that timeout; duplicate id → `FENCED_INSTANCE_ID`.
- **Safe commits:** `commitSync` in `onPartitionsRevoked`; never commit in `onPartitionsLost`; EOS uses `sendOffsetsToTransaction(offsets, groupMetadata())`.
- **Storm cures:** cooperative-sticky + static membership + right-size `max.poll.records`/`max.poll.interval.ms` + `pause()/resume()` + `heartbeat ≈ session/3`.
- **Key defaults:** `heartbeat=3000`, `session.timeout=45000`, `max.poll.interval=300000`, `max.poll.records=500`, `auto.commit=true/5000ms`, `offsets.retention=7d`, `group.initial.rebalance.delay=3000`, `__consumer_offsets` partitions=50.
- **Debug:** `kafka-consumer-groups.sh --describe --state`; JMX `rebalance-rate-per-hour`, `rebalance-latency-max`.
- **KIP-848:** broker-driven, incremental, no JoinGroup barrier; `group.protocol=consumer` (~Kafka 4.0). Classic remains 3.x default.

### 12.2 Self-test (no answers)

1. Two consumers in a group, one topic of 4 partitions, using `CooperativeStickyAssignor`. A third consumer joins. Describe exactly which partitions get revoked, in how many rounds, and which partitions keep consuming throughout.
2. You set `session.timeout.ms=4000` and the broker rejects the member. Why, and what's the valid range?
3. Your processing of a 500-record batch occasionally takes 7 minutes. Default configs. What fails, what's the log signature, and give two distinct fixes with their tradeoffs.
4. Explain why committing offsets in `onPartitionsLost` is dangerous in cooperative mode, and what the safe action is there instead.
5. Design the full two-step rolling upgrade from `RangeAssignor` to `CooperativeStickyAssignor` and explain why it cannot be done in one deploy.
6. A group reprocesses its entire topic after a 10-day outage. No code changed. Identify the two configs responsible and how to prevent it.
7. Contrast how KIP-848 changes the roles of "coordinator" and "leader" versus the classic protocol, and what client config it deprecates.

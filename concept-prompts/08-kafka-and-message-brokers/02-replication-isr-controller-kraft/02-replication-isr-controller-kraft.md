# Replication, ISR, Controller & KRaft

> An exhaustive engineering-handbook chapter on how Apache Kafka keeps data durable and available: leader/follower replication, the in-sync-replica (ISR) set, the high-watermark, the `acks`/`min.insync.replicas` durability contract, the cluster controller, the ZooKeeper era, and the modern KRaft (Raft-based) metadata quorum.

---

## 1. Overview & where it fits

### 1.1 What this chapter is about

Apache Kafka is a **distributed, append-only log system**. Producers write records to **topics**, which are split into **partitions**, and each partition is an ordered, immutable sequence of records identified by a monotonically increasing integer called the **offset**. A single broker holds many partitions, and a cluster holds many brokers.

The moment you put data on more than one machine, two hard questions appear:

1. **Durability / fault tolerance** — if the machine holding partition `P` dies, do we lose the data, or is there a copy somewhere else? This is the job of **replication**.
2. **Coordination** — *who* decides which broker owns which partition, who is allowed to accept writes, and how the cluster agrees on that decision even when machines crash mid-decision? This is the job of the **controller** plus a **consensus/metadata layer** (historically ZooKeeper, now KRaft).

This chapter covers both halves and the contracts that tie them together (`acks`, `min.insync.replicas`, the high-watermark, ISR membership). Master this and you understand *why* Kafka is durable, *when* it is not, and *how* to operate it so it stays durable.

### 1.2 The problem it solves

A naive message queue stores each message on one disk. That gives you a single point of failure: lose the disk, lose the data. Kafka instead keeps **N copies (replicas)** of every partition on N different brokers. One replica is the **leader** (handles all reads and writes for that partition); the rest are **followers** that copy the leader's log. If the leader broker dies, one of the followers is promoted to leader, and clients keep working. The art is in doing this **without losing committed data** and **without two brokers both thinking they're the leader** (split-brain).

### 1.3 When you reach for these mechanisms

You don't "turn on" replication for a special case — it is the always-on backbone of any production Kafka cluster. You *tune* it. You reach for the knobs in this chapter when you must answer questions like:

- "How many machine failures can we survive without data loss?" → replication factor, `min.insync.replicas`, `acks`.
- "Can we ever lose acknowledged data?" → ISR semantics, high-watermark, unclean leader election.
- "Why did the cluster stall when one broker got slow?" → ISR shrink/expand, `acks=all` blocking.
- "Why does failover take 30 seconds?" → controller behavior, session timeouts, ZooKeeper vs. KRaft.

### 1.4 One-paragraph mental model

Each partition is a small replicated state machine: one **leader** owns the truth, a set of **followers** continuously pull the leader's log to stay current, and the subset of replicas that are sufficiently caught up forms the **ISR (in-sync replica set)**. A record is **committed** only once every member of the ISR has it; the boundary between committed and not-yet-committed is the **high-watermark**, and consumers can only read up to it. A cluster-wide **controller** keeps the map of "which broker leads which partition" and reassigns leadership when brokers come and go. That controller, and all cluster metadata, was historically stored in **ZooKeeper** (a separate consensus service) and is now stored in Kafka's own **KRaft** metadata log, which uses the **Raft** consensus algorithm. Durability is a *contract* you opt into with three settings working together: replication factor (how many copies exist), `min.insync.replicas` (how many must confirm), and `acks` (how many the producer waits for).

---

## 2. Foundations from first principles

We build the vocabulary from zero. Skip ahead if a term is already second nature.

### 2.1 Topic, partition, offset, log

- **Topic** — a named stream of records, e.g. `orders`. Purely logical.
- **Partition** — the unit of parallelism and replication. A topic with 12 partitions is 12 independent ordered logs. Records with the same key go to the same partition (by default `hash(key) % numPartitions`), which preserves per-key ordering. Ordering is guaranteed **within** a partition, never across partitions.
- **Offset** — a 64-bit integer; the position of a record within its partition. Offsets are per-partition, start at 0, and never reused. "The consumer is at offset 4,182,003" means it has consumed everything before that index in that one partition.
- **Log** — the on-disk representation of a partition: a directory of **segment** files (`.log`) plus index files (`.index`, `.timeindex`). New records append to the active segment. This append-only design is why Kafka writes are fast — sequential disk I/O, mostly served from the OS page cache.

A first principle worth internalizing: **replication operates per-partition, not per-topic and not per-broker.** Every statement in this chapter about "leader" and "follower" is scoped to a single partition. One broker is simultaneously leader for some partitions and follower for others.

### 2.2 Replica, leader, follower

- **Replica** — one physical copy of a partition's log, living on one broker. If a partition has **replication factor 3**, there are 3 replicas on 3 different brokers.
- **Replication factor (RF)** — how many replicas exist. RF=3 is the production standard: survive 2 broker failures (in storage terms) while keeping a copy.
- **Leader replica** — exactly one replica per partition is the leader. **All** produces and (by default) all consumes go to the leader. The leader is the source of truth.
- **Follower replica** — the other replicas. A follower does **no** client-facing work in the classic model; its only job is to **fetch** records from the leader and write them to its own log, staying as caught-up as possible. (Since Kafka 2.4, consumers *can* read from followers via "follower fetching" / `client.rack` for locality — covered in §7.)

Why route everything through the leader? Because a single writer per partition makes ordering and consistency trivial: the leader assigns offsets, and there is never a conflict to resolve. Followers are passive copiers, not co-writers. This is **primary-backup (leader-based) replication**, not quorum-write replication like Dynamo. (Kafka *does* use a quorum-like idea for the metadata log under KRaft — see §6 — but partition data uses leader-based replication with an ISR twist.)

### 2.3 How a follower copies the leader: the fetch loop

A follower runs a **ReplicaFetcherThread** that issues `Fetch` requests to the leader, exactly like a consumer does, but with replica privileges. The follower says "give me everything from offset X onward." The leader responds with records, the follower appends them to its local log, and advances X. Crucially:

- The follower tells the leader, in each fetch request, **how far it has gotten** (its `fetchOffset` / log-end offset). This is how the leader knows how caught-up each follower is.
- A follower that is keeping up will have fetched up to (or very near) the leader's latest offset.

This continuous reporting is the raw signal behind ISR membership.

### 2.4 Log-end offset (LEO) and high-watermark (HW)

Two offsets per replica that you must never confuse:

- **Log-End Offset (LEO)** — the offset of the *next* record to be written; i.e., one past the last record this replica has in its log. Every replica (leader and each follower) has its own LEO. The leader's LEO is the most advanced.
- **High-Watermark (HW)** — the offset up to which records are **committed**, meaning replicated to all members of the ISR. The HW = the **minimum LEO across all in-sync replicas**. The leader computes and propagates it.

Records **below** the HW are committed and visible to consumers. Records **at or above** the HW exist on the leader (and maybe some followers) but are **not yet committed** and are **invisible to consumers**. If the leader dies before those records reach the HW, they may be lost — and that's *correct*, because they were never acknowledged as committed.

> Mental picture: the leader's log runs ahead; the HW is a line drawn at "the slowest in-sync follower has caught up to here." Consumers read only up to that line. Anything past the line is "in flight."

### 2.5 The In-Sync Replica set (ISR)

The **ISR** is the set of replicas (always including the leader) that are **sufficiently caught up** to the leader. "Sufficiently caught up" is defined by time, not byte count, via the broker config:

- **`replica.lag.time.max.ms`** (default **30000** ms = 30s in modern Kafka). A follower stays in the ISR as long as it has either fully caught up to the leader's LEO, or sent a fetch request that consumed up to the leader's LEO, within the last `replica.lag.time.max.ms`. If a follower falls behind and hasn't caught up within that window, the leader **removes it from the ISR** ("ISR shrink"). When it catches back up, the leader **adds it back** ("ISR expand").

> Historical note: very old Kafka (pre-0.9) also used `replica.lag.max.messages` (a count of records). That message-count metric was removed because a sudden burst could eject a perfectly healthy follower. Modern Kafka uses **time-based lag only**. Flag this if you see old docs.

The ISR is the linchpin. Commitment (HW advancement) is defined relative to the ISR — **not** relative to all replicas. So a slow or dead follower that has been kicked out of the ISR does *not* hold up commitment for everyone else. This is what gives Kafka its blend of strong durability and good availability.

### 2.6 Committed messages and the durability contract

A message is **committed** when it has been written to the logs of **all replicas currently in the ISR**. Only committed messages are:

- handed to consumers (consumers never see uncommitted data), and
- guaranteed not to be lost as long as **at least one ISR member survives**.

Three producer/broker settings define exactly how strong this guarantee is. They must be understood together:

| Setting | Lives on | What it controls |
|---|---|---|
| **`acks`** (producer) | producer | How many acknowledgements the producer waits for before considering a send successful: `0`, `1`, or `all` (`-1`). |
| **`min.insync.replicas`** (broker/topic) | broker or topic | The minimum size the ISR must have for an `acks=all` write to be accepted. If the ISR is smaller, the leader **rejects** the write with `NotEnoughReplicas`. |
| **replication factor** (topic) | topic | Total number of replicas. Upper bound on ISR size and on how many failures you can tolerate. |

- **`acks=0`** — fire and forget. Producer doesn't wait at all. Highest throughput, weakest durability — you can lose data on any failure, even a TCP hiccup.
- **`acks=1`** — producer waits for the **leader** to write to its log (not necessarily replicated). If the leader dies after acking but before a follower copies the record, **that record is lost**. Medium durability.
- **`acks=all`** (a.k.a. `acks=-1`) — producer waits until the record is committed, i.e., replicated to **all members of the ISR**. Strongest durability. But "all in the ISR" is only meaningful if the ISR is reasonably large — which is exactly why you pair it with `min.insync.replicas`.

**The canonical durable configuration:** `RF=3`, `min.insync.replicas=2`, `acks=all`. This means: keep 3 copies; require at least 2 in-sync; the producer waits for those 2+. You can lose **1** broker and keep writing (ISR=2 still ≥ 2). Lose a 2nd broker and writes start failing (`NotEnoughReplicas`) rather than silently risking data — the cluster chooses *consistency over availability* for writes. No acknowledged write is ever lost as long as one ISR replica survives.

> Common pitfall: `acks=all` with `min.insync.replicas=1` is a trap. If the ISR shrinks to just the leader (all followers fell behind or died), then "all in the ISR" = "the leader alone," so `acks=all` degrades to `acks=1`. You think you're durable; you're not. Always set `min.insync.replicas ≥ 2` for important data.

### 2.7 The controller (one-sentence version, expanded in §3.4)

Among all brokers, exactly one is elected the **controller**. It is the cluster's brain for metadata: it watches which brokers are alive, decides leadership for each partition, drives leader elections on failure, and propagates the resulting metadata to all brokers. There is one controller per cluster; if it dies, another broker takes over.

### 2.8 ZooKeeper, explained from scratch

Before Kafka could elect a controller or store "who leads partition X," it needed somewhere to keep that metadata that *all brokers agree on* and *survives crashes*. That somewhere was **Apache ZooKeeper**.

**What ZooKeeper is:** a small, separate, replicated **distributed coordination service**. Think of it as a tiny, highly-consistent, hierarchical key-value store (it looks like a filesystem) with primitives designed for coordination: leader election, configuration storage, distributed locks, group membership, and notifications. It is its own cluster (an "ensemble") of typically 3 or 5 servers, running alongside — but separate from — Kafka.

**Core concepts:**

- **znode** — a node in ZooKeeper's tree-shaped namespace (paths look like `/brokers/ids/1`). A znode holds a small blob of data (KB-scale, not MB) and can have children. Kafka stored, for example, `/controller` (who the controller is), `/brokers/ids/*` (live brokers), and `/brokers/topics/*` (topic metadata) as znodes.
- **Ephemeral znode** — a znode tied to a client's **session**. When the client (a broker) disconnects or its session times out, the ephemeral znode **disappears automatically**. Kafka used this for liveness: each broker creates an ephemeral znode under `/brokers/ids/`. If the broker dies, the znode vanishes, and the controller learns the broker is gone. The `/controller` znode itself was ephemeral — whoever creates it first is the controller; if that broker dies, the znode disappears and others race to recreate it (that race *is* the controller election).
- **Watch** — a one-shot subscription. A client can set a watch on a znode and gets a **single notification** when it changes (data change, child added/removed, or deletion). The controller set watches on `/brokers/ids` to be told when brokers join/leave. "One-shot" means after firing, you must re-register the watch — a frequent source of bugs.
- **Session** — a client's connection lifetime, kept alive by heartbeats. If heartbeats stop for longer than the session timeout, the session expires and all the client's ephemeral znodes are deleted. This is the timing mechanism behind failure detection.
- **Consensus / ZAB** — ZooKeeper internally uses an atomic-broadcast protocol called **ZAB (ZooKeeper Atomic Broadcast)** to keep its ensemble consistent. A write must be acknowledged by a **majority (quorum)** of ZooKeeper servers before it's committed. With 3 servers, 2 must agree; with 5, 3 must agree. This is why ZooKeeper ensembles are odd-sized: a quorum of 3 (out of 5) tolerates 2 failures; an even size wastes a node. Reads can be served by any single server (and may be slightly stale unless you issue a `sync`).
- **Linearizable writes, sequential consistency** — ZooKeeper gives strong ordering guarantees: all clients see updates in the same order; writes are atomic and ordered.

**Why Kafka used ZooKeeper:** building correct distributed consensus is extraordinarily hard. Rather than implement it, early Kafka delegated all the "agree on cluster state, detect failures, elect a controller" work to a battle-tested external system. ZooKeeper provided exactly the primitives needed: ephemeral znodes for membership/liveness, watches for change notification, and a consistent store for metadata.

### 2.9 Why Kafka moved away from ZooKeeper → KRaft (intro)

ZooKeeper worked, but it had costs:

1. **Two systems to run, secure, monitor, and tune.** ZooKeeper has its own JVM, its own config, its own failure modes, its own version compatibility matrix.
2. **A scaling ceiling on metadata.** All metadata changes funneled through the controller talking to ZooKeeper. Storing per-partition state in znodes and propagating it didn't scale to **millions of partitions**; controller failover time grew with partition count because the new controller had to **load the entire metadata state from ZooKeeper** before it could act — sometimes tens of seconds.
3. **Split state / consistency seams.** The controller cached metadata; ZooKeeper held the truth; brokers had their own copies. Keeping these in sync was a known source of subtle bugs.

The fix was **KRaft** ("**K**afka **Raft**"), introduced as KIP-500. KRaft removes ZooKeeper entirely and stores all cluster metadata in a **dedicated internal Kafka topic** (`__cluster_metadata`) that is itself a **replicated log managed by the Raft consensus algorithm**. A small set of brokers act as **controllers** forming a **metadata quorum**. We explain Raft and KRaft fully in §6.

---

## 3. How it works internally

This is the heart of the chapter. We trace the actual control and data flow, step by step.

### 3.1 The life of a produce request (acks=all, RF=3, min.insync.replicas=2)

Assume topic `orders`, partition 7, RF=3, replicas on brokers B1 (leader), B2, B3 (followers), ISR = {B1, B2, B3}.

1. **Producer partitions the record.** The producer computes the target partition (by key hash, round-robin, or a custom partitioner) → partition 7.
2. **Producer finds the leader.** Using cached cluster metadata (fetched via a `Metadata` request), the producer knows B1 leads partition 7. If its metadata is stale, it gets a `NotLeaderForPartition` error and refreshes.
3. **Producer batches and sends.** Records accumulate in an in-memory buffer (`RecordAccumulator`), grouped into batches per partition (`batch.size`, `linger.ms`). A batch is sent in a `Produce` request to B1.
4. **Leader appends to its log.** B1 validates the batch (CRC, idempotence sequence numbers if enabled), assigns offsets, and **appends to its local active segment**. B1's LEO advances. The data is now in B1's page cache (not necessarily fsync'd to disk yet — see §3.6).
5. **Leader does NOT ack yet** (because `acks=all`). It registers the request in a structure called **Purgatory** (a delayed-operation holding area) waiting for the HW to reach the request's offset.
6. **Followers fetch.** B2 and B3's ReplicaFetcherThreads are continuously sending `Fetch` requests to B1. They pull the new records, append to their own logs, advancing their LEOs. Each fetch request reports the follower's new LEO back to B1.
7. **Leader advances the HW.** B1 recomputes HW = min(LEO across ISR). Once **both** B2 and B3 (the ISR) have fetched up to or past the record's offset, the HW crosses that offset. (Technically, the HW advances as soon as the ISR minimum LEO does; with `min.insync.replicas=2`, even 2 of the 3 suffice, but here all 3 are in ISR.)
8. **Leader satisfies the delayed produce.** Purgatory sees HW ≥ the request offset and completes it; B1 sends the ack to the producer. The record is now **committed**.
9. **Consumers can now read it.** A consumer fetching partition 7 is served records only up to the HW, so it now sees this record.

If at step 6 only **one** follower had been in the ISR (say B3 had been ejected for lag), the ISR would be {B1, B2}. With `min.insync.replicas=2`, that's still acceptable — the write commits once B2 catches up. If the ISR had shrunk to {B1} alone, B1 would **reject the produce** with `NotEnoughReplicas` (because the ISR size 1 < `min.insync.replicas` 2), and the producer would retry or fail. This is the durability contract enforcing itself.

### 3.2 The life of a fetch (consumer read) request

1. Consumer knows the leader for the partition (from metadata).
2. Consumer sends a `Fetch` request with the offset it wants and `fetch.max.bytes`/`max.partition.fetch.bytes` limits.
3. Leader serves records **only up to the HW** (committed data). Records between HW and LEO are withheld.
4. If no new committed data is available, the request waits up to `fetch.max.wait.ms` (long-poll) before returning empty, to avoid busy-looping.

This HW-bounded read is *why* consumers never see data that could later be lost: they only ever see committed records.

### 3.3 ISR shrink and expand, step by step

**Shrink (a follower falls behind or dies):**

1. Follower B3 stops fetching (crash, GC pause, slow disk, network partition) or fetches but can't keep up.
2. B1 (leader) tracks, per follower, the last time it was caught up. When B3 hasn't caught up to B1's LEO within `replica.lag.time.max.ms` (30s), B1 marks B3 as out-of-sync.
3. B1 **shrinks the ISR** from {B1,B2,B3} to {B1,B2}. Under ZooKeeper, the leader proposed this change and it was persisted (controller + ZK); under KRaft, the leader sends an `AlterPartition` (formerly `AlterIsr`) request to the controller, which records the new ISR in the metadata log.
4. The HW now depends only on {B1,B2}. If B3 was the laggard holding back the HW, the HW can now advance — *committing* records that were stuck.
5. Metric `UnderReplicatedPartitions` increments (ISR size < RF), and operators get alerted.

**Expand (the follower catches up):**

1. B3 recovers and resumes fetching.
2. B3 catches up to B1's LEO (or at least to the current HW and keeps pace).
3. B1 **adds B3 back into the ISR** via `AlterPartition` to the controller.
4. HW now requires {B1,B2,B3} again; `UnderReplicatedPartitions` decrements.

A subtle, important rule: a follower rejoining the ISR must first **truncate its log to the leader's HW** if it had uncommitted records that the new leader doesn't have, to avoid log divergence. This is governed by the **leader epoch** mechanism (§3.7).

### 3.4 The controller: responsibilities and internal flow

The controller is a broker with extra duties. Its responsibilities:

- **Track broker liveness** — knows which brokers are up (via ZK ephemeral znodes in the old world; via the metadata quorum / broker heartbeats in KRaft).
- **Maintain the leader/ISR map** — for every partition, who is leader, who are the replicas, and the current ISR.
- **Drive leader elections** — when a leader broker dies, pick a new leader (normally the first in-sync replica in the partition's "preferred replica/assignment" order) and notify the relevant brokers.
- **Handle topic creation/deletion, partition reassignment, and config changes.**
- **Propagate metadata** — push `LeaderAndIsr`, `UpdateMetadata`, and `StopReplica` requests to brokers (ZK era) or publish records to the metadata log that brokers replay (KRaft era).

**ZK-era controller failure & election (internal flow):**

1. Each broker tries to create the ephemeral `/controller` znode at startup. The one that succeeds is the controller; others set a **watch** on `/controller`.
2. If the controller dies, its session expires, ZooKeeper deletes the ephemeral `/controller` znode, and the watch fires on all other brokers.
3. Brokers race to recreate `/controller`; the winner becomes the new controller.
4. The new controller must **bootstrap**: read all topic/partition/ISR/broker state from ZooKeeper into memory, increment the **controller epoch** (a fencing counter, so stale messages from a deposed controller are ignored), and send `UpdateMetadata` to every broker. With many partitions, this load phase was the dominant cost of failover — sometimes 10–30+ seconds.

**Controller epoch (fencing):** every controller action carries an epoch number that strictly increases at each election. Brokers reject requests stamped with an epoch lower than the highest they've seen. This prevents a "zombie" old controller (e.g., one that had a long GC pause and thinks it's still in charge) from corrupting state — a classic distributed-systems fencing technique.

### 3.5 Leader election for a partition

When a partition's leader broker fails, the controller picks a new leader. The default policy is **"preferred replica" / first eligible in the ISR**:

1. The controller knows the partition's replica list (its **assignment**, an ordered list like `[B1, B2, B3]`) and current ISR.
2. It selects the **first replica in the assignment that is still in the ISR**. If `[B1,B2,B3]` and B1 died but B2,B3 are in ISR, the new leader is B2.
3. The controller updates the leader/ISR state and sends `LeaderAndIsr` requests so B2 becomes leader and remaining followers re-point their fetchers to B2.

**Preferred leader:** the first replica in the assignment list (`B1` above) is the *preferred* leader. Over time, after failovers, leaders drift away from the preferred broker, concentrating load. **Preferred leader election** (auto via `auto.leader.rebalance.enable=true`, default true, run every `leader.imbalance.check.interval.seconds`, default 300s; or manual via `kafka-leader-election.sh`) restores balance by moving leadership back to preferred replicas when they're in the ISR.

**Unclean leader election (the dangerous case):** what if the leader dies and **no replica is in the ISR** (e.g., the leader was the only ISR member and it died)? Two choices:

- **`unclean.leader.election.enable=false`** (the safe default in modern Kafka): the partition goes **offline** — no leader, no reads, no writes — until an ISR replica comes back. Prioritizes **consistency/durability** over availability.
- **`unclean.leader.election.enable=true`**: the controller elects a **non-ISR** replica (one that was behind) as leader. This restores availability **but discards** any committed records the dead leader had that the new leader lacks. **This causes data loss and can even cause already-consumed offsets to "rewind."** Use only when availability trumps correctness (rare for important data).

### 3.6 Disk durability vs. replication durability (`flush` vs. replicate)

A frequent misunderstanding: when the leader "appends to its log," it writes to the OS **page cache**, not necessarily to disk. Kafka deliberately **relies on replication for durability, not on fsync**. The relevant configs:

- **`log.flush.interval.messages`** / **`log.flush.interval.ms`** — control how often Kafka forces an `fsync`. Defaults are effectively "let the OS decide" (very large / disabled), because forcing fsync on every record kills throughput.
- The reasoning: with RF=3 across 3 machines (ideally in 3 failure domains), the probability of *all three* losing un-fsynced page-cache data simultaneously (e.g., 3 simultaneous power losses) is far lower than the throughput cost of synchronous fsync. So Kafka treats **"replicated to ISR"** as the durability boundary, not **"on disk."**

Caveat to flag: a correlated failure (whole-rack power loss, all replicas in one AZ) *can* lose un-fsynced data. Spreading replicas across racks/AZs (rack awareness, §7) and, for extreme requirements, tuning flush, are the mitigations.

### 3.7 Leader epoch and log truncation (preventing divergence)

Old Kafka used the HW for truncation, which had a known data-loss/divergence edge case during rapid leader changes. Modern Kafka (since KIP-101) uses **leader epochs**:

- A **leader epoch** is a number incremented every time a partition gets a new leader. Each record range in the log is tagged with the epoch of the leader that wrote it, stored in a **leader-epoch checkpoint file**.
- When a follower needs to truncate (after becoming follower to a new leader), it asks the leader "what's the end offset of my last epoch?" via `OffsetsForLeaderEpoch`. It truncates to that point rather than blindly to the HW. This precisely removes only diverged records and avoids both data loss and log divergence that the old HW-based scheme could cause.

You rarely touch this directly, but it's why modern Kafka is correct under churny leadership changes — a common senior-interview deep-probe.

### 3.8 KRaft internal flow (high-level here, deep in §6)

Under KRaft, there is no ZooKeeper. Instead:

1. A small set of nodes run in **controller** role (e.g., 3 controllers). They form a **Raft quorum** over the `__cluster_metadata` log.
2. One controller is the **active controller (Raft leader)**; the others are hot standbys that have a full, up-to-date copy of the metadata log.
3. All metadata changes (topic create, ISR change, leader election) are **appended as records to the metadata log** and committed by Raft majority.
4. Brokers are **observers** of the metadata log: they continuously **replay** committed metadata records to build their local view. No more `UpdateMetadata` push storms; brokers pull the log.
5. **Failover is fast**: standby controllers already have the full log in memory, so when the active controller dies, a standby becomes leader almost instantly (no multi-second reload from an external store). Failover drops from tens of seconds to sub-second / low hundreds of ms.

---

## 4. The complete toolkit

### 4.1 Producer configs that govern durability

| Config | Default | Purpose / notes |
|---|---|---|
| `acks` | `all` (since Kafka 3.0; was `1` before) | `0`/`1`/`all`. The single most important durability knob on the producer side. |
| `enable.idempotence` | `true` (since 3.0) | Exactly-once-per-partition producing: dedups retries via producer ID + sequence numbers. Requires `acks=all`, `retries>0`, `max.in.flight.requests.per.connection ≤ 5`. |
| `retries` | `2147483647` (effectively ∞, bounded by `delivery.timeout.ms`) | How many times to retry a failed send. |
| `delivery.timeout.ms` | `120000` (2 min) | Upper bound on total time for a send incl. retries. |
| `max.in.flight.requests.per.connection` | `5` | Unacked requests per connection. With idempotence on, ≤5 preserves ordering; without idempotence, >1 can reorder on retry. |
| `retry.backoff.ms` | `100` | Wait between retries. |
| `request.timeout.ms` | `30000` | Per-request timeout. |
| `batch.size` | `16384` (16 KB) | Max batch bytes per partition. |
| `linger.ms` | `0` | Wait this long to fill a batch (throughput vs. latency). |
| `compression.type` | `none` | `gzip`/`snappy`/`lz4`/`zstd`. Reduces replication bandwidth too. |

### 4.2 Topic / broker configs that govern replication & durability

| Config | Scope | Default | Purpose |
|---|---|---|---|
| `replication.factor` / `--replication-factor` | topic | (set at create) | Number of replicas. RF=3 standard. |
| `min.insync.replicas` | topic & broker | `1` | Minimum ISR size for `acks=all` writes; below it, leader rejects with `NotEnoughReplicas`. Set to `2` with RF=3. |
| `default.replication.factor` | broker | `1` | RF used when a topic is auto-created. Set to 3 in prod. |
| `unclean.leader.election.enable` | topic & broker | `false` | Allow electing an out-of-sync replica (data loss) to restore availability. |
| `replica.lag.time.max.ms` | broker | `30000` | Max time a follower can be behind before being dropped from ISR. |
| `num.replica.fetchers` | broker | `1` | Fetcher threads per broker for follower replication. Increase for high-throughput/many-partition brokers. |
| `replica.fetch.max.bytes` | broker | `1048576` (1 MB) | Max bytes per partition per follower fetch. |
| `replica.fetch.wait.max.ms` | broker | `500` | Follower fetch long-poll wait. |
| `replica.socket.timeout.ms` | broker | `30000` | Socket timeout for replication. |
| `auto.leader.rebalance.enable` | broker | `true` | Periodically move leadership back to preferred replicas. |
| `leader.imbalance.check.interval.seconds` | broker | `300` | How often to check leader imbalance. |
| `leader.imbalance.per.broker.percentage` | broker | `10` | Imbalance threshold (%) that triggers rebalance. |
| `log.flush.interval.messages` / `.ms` | broker/topic | very large / null | Force fsync cadence (usually left to OS). |
| `controlled.shutdown.enable` | broker | `true` | On shutdown, migrate leadership off the broker first (graceful). |

### 4.3 KRaft-specific configs

| Config | Default | Purpose |
|---|---|---|
| `process.roles` | (none → ZK mode) | `broker`, `controller`, or `broker,controller` (combined). Presence of this enables KRaft. |
| `node.id` | — | Unique node id (replaces `broker.id`). |
| `controller.quorum.voters` | — | `id@host:port` list of controller voters, e.g. `1@c1:9093,2@c2:9093,3@c3:9093`. |
| `controller.quorum.bootstrap.servers` | — | (Newer) bootstrap form for dynamic quorum. |
| `controller.listener.names` | — | Listener(s) controllers use (e.g., `CONTROLLER`). |
| `metadata.log.dir` | (falls back to `log.dirs`) | Where the `__cluster_metadata` log lives. |
| `metadata.max.idle.interval.ms` | `500` | How often to append a no-op to advance the metadata log. |

### 4.4 CLI tools

| Command | What it does |
|---|---|
| `kafka-topics.sh --describe --topic T` | Shows partitions, **Leader**, **Replicas**, **Isr** per partition. Your #1 replication-health tool. |
| `kafka-topics.sh --describe --under-replicated-partitions` | Lists only under-replicated partitions cluster-wide. |
| `kafka-topics.sh --describe --unavailable-partitions` | Partitions with no leader (offline). |
| `kafka-topics.sh --create --replication-factor 3 --partitions N` | Create with RF. |
| `kafka-reassign-partitions.sh` | Move replicas between brokers; change RF; rebalance. Uses a JSON reassignment plan; supports `--throttle`. |
| `kafka-leader-election.sh --election-type PREFERRED\|UNCLEAN` | Manually trigger leader election. |
| `kafka-configs.sh --alter --add-config min.insync.replicas=2` | Change topic/broker dynamic configs. |
| `kafka-metadata-quorum.sh describe --status` | (KRaft) Shows quorum leader, voters, observers, log end offsets, lag. |
| `kafka-metadata-shell.sh` | (KRaft) Inspect the `__cluster_metadata` log contents. |
| `kafka-storage.sh format --cluster-id <id> -c server.properties` | (KRaft) Format storage with a cluster id before first start. |
| `zookeeper-shell.sh host:2181 get /controller` | (ZK) Inspect controller/broker znodes. |
| `kafka-dump-log.sh` | Inspect log segments, leader-epoch checkpoints, offsets. |

### 4.5 Key JMX metrics

| Metric (MBean fragment) | Meaning / alert |
|---|---|
| `kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions` | Partitions with ISR < RF. **Alert if > 0 for a sustained period.** |
| `kafka.server:type=ReplicaManager,name=UnderMinIsrPartitionCount` | Partitions with ISR < `min.insync.replicas`. **These reject `acks=all` writes — alert immediately.** |
| `kafka.controller:type=KafkaController,name=OfflinePartitionsCount` | Partitions with no leader. **Any >0 = outage.** |
| `kafka.controller:type=KafkaController,name=ActiveControllerCount` | Should be exactly **1** across the cluster. 0 = no controller; 2 = split-brain. |
| `kafka.server:type=ReplicaManager,name=IsrShrinksPerSec` / `IsrExpandsPerSec` | High churn signals flapping followers (GC, slow disk, network). |
| `kafka.server:type=ReplicaFetcherManager,name=MaxLag` | Max follower lag in messages. |
| `kafka.controller:type=ControllerStats,name=LeaderElectionRateAndTimeMs` | Election frequency/latency. |

---

## 5. Code examples by use case

### 5.1 Use case A — Maximum-durability producer (financial events)

```java
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import java.util.Properties;

public class DurableProducer {
    public static void main(String[] args) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092,b2:9092,b3:9092");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // --- The durability contract (producer half) ---
        p.put(ProducerConfig.ACKS_CONFIG, "all");                 // wait for full ISR commit
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);    // no duplicates on retry
        p.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);  // retry hard...
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000); // ...but bounded to 2 min total
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5); // safe ordering w/ idempotence

        // Topic side MUST be: replication.factor=3, min.insync.replicas=2
        try (Producer<String, String> producer = new KafkaProducer<>(p)) {
            ProducerRecord<String, String> rec =
                new ProducerRecord<>("payments", "acct-42", "{\"amount\":100.00}");
            // Synchronous send: block until COMMITTED (or exception)
            RecordMetadata md = producer.send(rec).get();
            System.out.printf("committed partition=%d offset=%d%n", md.partition(), md.offset());
        } catch (Exception e) {
            // e.g. org.apache.kafka.common.errors.NotEnoughReplicasException
            //      when the ISR < min.insync.replicas — do NOT swallow this.
            System.err.println("Durable send failed, must not drop: " + e.getMessage());
        }
    }
}
```

Why each line matters: `acks=all` + topic `min.insync.replicas=2` is the *whole* durability guarantee — neither alone is enough. `enable.idempotence` prevents the retries from creating duplicates. Catching `NotEnoughReplicasException` and **not** dropping the record is the difference between "durable system" and "system that silently loses money during a broker outage."

### 5.2 Use case B — Throughput-first producer (telemetry/logs, loss-tolerant)

```java
Properties p = new Properties();
p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092,b2:9092");
p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

p.put(ProducerConfig.ACKS_CONFIG, "1");          // wait only for leader (faster)
p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
p.put(ProducerConfig.LINGER_MS_CONFIG, 20);      // batch up to 20ms for throughput
p.put(ProducerConfig.BATCH_SIZE_CONFIG, 64 * 1024);
p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4"); // cuts network + disk + replication BW

try (Producer<String,String> producer = new KafkaProducer<>(p)) {
    for (int i = 0; i < 100_000; i++) {
        // fire-and-forget with callback; do not block per record
        producer.send(new ProducerRecord<>("metrics", "host-"+(i%500), "cpu=0.7"),
            (md, ex) -> { if (ex != null) log.warn("drop ok for telemetry", ex); });
    }
}
```

This deliberately trades durability for throughput: `acks=1` means a leader crash can lose the most-recent records, which is acceptable for metrics but never for payments.

### 5.3 Use case C — Inspecting and reacting to ISR health programmatically (AdminClient)

```java
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.*;
import java.util.*;

public class IsrHealth {
    public static void main(String[] args) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092"))) {

            String topic = "payments";
            TopicDescription desc = admin.describeTopics(List.of(topic))
                    .allTopicNames().get().get(topic);

            for (TopicPartitionInfo tp : desc.partitions()) {
                int rf = tp.replicas().size();
                int isr = tp.isr().size();
                boolean underReplicated = isr < rf;
                boolean leaderless = tp.leader() == null || tp.leader().id() < 0;
                System.out.printf("p=%d leader=%s replicas=%d isr=%d %s%s%n",
                        tp.partition(),
                        leaderless ? "NONE(offline)" : tp.leader().id(),
                        rf, isr,
                        underReplicated ? "[UNDER-REPLICATED] " : "",
                        leaderless ? "[OFFLINE]" : "");
            }
        }
    }
}
```

Use this in a health probe: if `isr < min.insync.replicas`, `acks=all` writes are already failing; if `leaderless`, the partition is offline.

### 5.4 Use case D — Increasing replication factor of an existing topic (CLI)

You cannot raise RF with `kafka-topics.sh --alter`; you must use a reassignment plan.

```bash
# 1. Write a plan that lists, for each partition, the desired (larger) replica set.
cat > increase-rf.json <<'JSON'
{
  "version": 1,
  "partitions": [
    {"topic": "orders", "partition": 0, "replicas": [1,2,3]},
    {"topic": "orders", "partition": 1, "replicas": [2,3,1]},
    {"topic": "orders", "partition": 2, "replicas": [3,1,2]}
  ]
}
JSON

# 2. Execute, throttling replication so it doesn't saturate the network.
kafka-reassign-partitions.sh --bootstrap-server b1:9092 \
  --reassignment-json-file increase-rf.json \
  --execute --throttle 50000000     # 50 MB/s cap on replication traffic

# 3. Verify until "completed"; this also removes the throttle.
kafka-reassign-partitions.sh --bootstrap-server b1:9092 \
  --reassignment-json-file increase-rf.json --verify
```

### 5.5 Use case E — Safe rolling broker restart (preserve durability)

```bash
# Graceful: controlled.shutdown.enable=true (default) migrates leadership off the broker.
# Restart ONE broker at a time and WAIT for full re-sync before the next.

# (A) Before restarting broker 2, confirm zero under-replicated partitions:
kafka-topics.sh --bootstrap-server b1:9092 --describe --under-replicated-partitions
# (empty output == healthy)

# (B) Stop broker 2 (SIGTERM triggers controlled shutdown), restart it.
systemctl restart kafka   # on broker 2

# (C) Poll until URP returns to empty (broker 2's replicas back in ISR), THEN proceed to b3.
while kafka-topics.sh --bootstrap-server b1:9092 \
        --describe --under-replicated-partitions | grep -q .; do
  echo "still catching up..."; sleep 10
done
```

Restarting a second broker before the first re-syncs can drop the ISR below `min.insync.replicas` and halt `acks=all` writes — a self-inflicted outage. This loop prevents it.

### 5.6 Use case F — Manually triggering preferred leader election

```bash
# After several failovers, leadership is lopsided. Rebalance to preferred replicas:
kafka-leader-election.sh --bootstrap-server b1:9092 \
  --election-type PREFERRED --all-topic-partitions

# Emergency only (DATA LOSS): force a leader when all ISR replicas are gone:
kafka-leader-election.sh --bootstrap-server b1:9092 \
  --election-type UNCLEAN --topic orders --partition 7
```

### 5.7 Use case G — Formatting and starting a KRaft cluster

```bash
# Combined broker+controller node (dev). server.properties excerpt:
#   process.roles=broker,controller
#   node.id=1
#   controller.quorum.voters=1@host1:9093,2@host2:9093,3@host3:9093
#   listeners=PLAINTEXT://:9092,CONTROLLER://:9093
#   controller.listener.names=CONTROLLER
#   log.dirs=/var/lib/kafka/data

# 1. Generate a cluster id ONCE for the whole cluster:
KAFKA_CLUSTER_ID="$(kafka-storage.sh random-uuid)"

# 2. Format storage on EACH node with that id:
kafka-storage.sh format -t "$KAFKA_CLUSTER_ID" -c server.properties

# 3. Start each node:
kafka-server-start.sh server.properties

# 4. Inspect the metadata quorum:
kafka-metadata-quorum.sh --bootstrap-server host1:9092 describe --status
# Shows: LeaderId, LeaderEpoch, HighWatermark, voters with their LogEndOffset & lag.
```

---

## 6. Raft and KRaft, in depth

### 6.1 Raft, explained briefly from scratch

**Raft** is a **consensus algorithm**: a protocol that lets a group of servers agree on an ordered sequence of values (a **replicated log**) even when some servers crash, as long as a **majority (quorum)** are alive. It was designed to be more understandable than its predecessor **Paxos**. Key ideas:

- **Roles:** each server is a **leader**, **follower**, or **candidate**. At most one leader at a time.
- **Term:** a logical clock — a monotonically increasing integer. Each election starts a new term. Terms are Raft's fencing mechanism (analogous to Kafka's controller epoch): messages from an old term are rejected.
- **Leader election:** if followers stop hearing heartbeats from the leader within an election timeout, a follower becomes a **candidate**, increments the term, and requests votes. A server grants one vote per term. A candidate that gets votes from a **majority** becomes leader. Randomized timeouts prevent perpetual split votes.
- **Log replication:** the leader appends client commands to its log and sends `AppendEntries` to followers. An entry is **committed** once it's stored on a **majority** of servers; committed entries are then applied to the state machine. This is a **quorum write**: majority-acknowledged, not all-acknowledged.
- **Safety:** Raft guarantees that committed entries are durable and that all servers apply the same log in the same order. A new leader must contain all committed entries (enforced by the voting rule: you only vote for a candidate whose log is at least as up-to-date as yours).

With 3 voters, a majority is 2 → tolerate 1 failure. With 5 voters, majority is 3 → tolerate 2 failures. **Odd sizes** are used because an even size adds a server without raising fault tolerance.

> **Raft (quorum) vs. Kafka partition ISR (leader-based):** Raft commits on a *majority*. Kafka partition data commits on *all in the ISR* (which can be fewer than a majority of all replicas, since dead ones are evicted). They are different replication models. KRaft uses Raft *only for the metadata log*; ordinary topic data still uses the ISR model. (KRaft's Raft is a tailored variant — sometimes called "KRaft Raft" — that, like Kafka, pulls via fetch rather than pushing.)

### 6.2 What KRaft actually is

KRaft replaces ZooKeeper with an internal, self-managed metadata system:

- **Metadata is a Kafka log.** All cluster metadata (topics, partitions, configs, ACLs, leader/ISR assignments, broker registrations) lives in a single-partition internal topic, **`__cluster_metadata`**.
- **Controllers form a Raft quorum** over that log. You run an odd number (3 or 5) of controller-role nodes. One is the **active controller** (Raft leader); the rest are standbys (Raft followers) holding a full, current copy.
- **Brokers are observers.** Each broker **fetches** the metadata log and **replays** it to maintain an in-memory image of cluster state. Brokers never write metadata directly; they send requests (e.g., `AlterPartition`) to the active controller, which appends a record to the log.
- **Snapshots.** Because the metadata log would grow forever, controllers periodically write **snapshots** of the materialized state, so a new controller or a freshly started broker can load a snapshot + tail of the log instead of the entire history.

### 6.3 Deployment modes

- **Combined mode:** a node runs `process.roles=broker,controller`. Convenient for small clusters / dev; not recommended for large production because controller and broker workloads contend.
- **Isolated/dedicated mode:** controller nodes run `process.roles=controller` only (typically 3 dedicated controllers), brokers run `process.roles=broker`. Recommended for production.

### 6.4 Why KRaft is better (concrete wins)

| Dimension | ZooKeeper era | KRaft |
|---|---|---|
| Number of systems | Kafka **+** ZooKeeper ensemble | Kafka only |
| Metadata store | External znodes | Internal `__cluster_metadata` log |
| Controller failover | New controller reloads all state from ZK (seconds → tens of seconds with many partitions) | Standby already has full log in RAM → **near-instant** |
| Partition scalability | Practical ceiling ~200k partitions/cluster | Designed for **millions** |
| Metadata propagation | Controller pushes `UpdateMetadata` to every broker | Brokers pull/replay the log (incremental) |
| Operational surface | Two security/ACL/monitoring stacks | One |

### 6.5 KRaft timeline & version notes (flag version-specificity)

- **KIP-500** proposed removing ZooKeeper.
- KRaft shipped as **early access in Kafka 2.8** (2021), **production-ready in 3.3** (late 2022).
- **Migration tooling** (ZK→KRaft) arrived around **3.4–3.6**.
- **Kafka 3.5** deprecated ZooKeeper mode.
- **Kafka 4.0** (2025) **removed ZooKeeper entirely** — KRaft is the only mode.
- Always check the exact version: pre-3.3 KRaft lacked many features (ACLs, some reassignment paths) that landed later.

---

## 7. Advanced topics & deep internals

### 7.1 Rack awareness

`broker.rack` tags each broker with a rack/AZ. Kafka's replica placement then spreads a partition's replicas across **different racks**, so a rack/AZ failure doesn't take out all copies. Combined with `min.insync.replicas`, this makes the durability story robust against correlated (whole-rack) power/network loss — the main weakness of relying on page cache over fsync (§3.6).

### 7.2 Follower fetching (read from nearest replica)

Since Kafka 2.4 (KIP-392), consumers can read from a **follower** in their own rack to cut cross-AZ network cost/latency. The consumer sets `client.rack`; the leader, via a configurable `replica.selector.class` (e.g., `RackAwareReplicaSelector`), tells the consumer which replica to read from. Important nuance: a follower only serves data up to **its own** view of the HW, which can lag the leader's slightly — so follower reads can be marginally staler.

### 7.3 The HW propagation lag and the "second fetch" subtlety

The HW is computed by the leader, but **followers learn the updated HW on their *next* fetch response**, not instantaneously. So there's always a small window where a follower's locally-known HW trails the leader's. This matters for follower fetching and for understanding why a freshly-promoted leader might briefly expose a slightly older HW. Leader-epoch-based truncation (§3.7) ensures correctness despite this lag.

### 7.4 Idempotent and transactional producers vs. replication

- **Idempotence** (`enable.idempotence=true`) gives exactly-once *per partition per producer session*: a producer ID + per-partition sequence numbers let the leader dedup retried batches. It requires `acks=all`. This interacts with replication because the dedup state must survive leader failover (it's part of the partition state replicated to followers).
- **Transactions** (`transactional.id`, `initTransactions`, `beginTransaction`/`commitTransaction`) give atomic multi-partition writes and exactly-once stream processing. Transaction state lives in an internal topic `__transaction_state`, itself replicated. The **transaction coordinator** (a broker role) and **consumer `isolation.level=read_committed`** complete the picture. Replication underpins all of it: the txn log and offsets topic must be durable.

### 7.5 Tuning replication throughput

- **`num.replica.fetchers`** — default 1. On brokers leading/following many partitions with high write rates, a single fetcher thread can bottleneck replication; raise to e.g. 4–8.
- **`replica.fetch.max.bytes` / `replica.fetch.response.max.bytes`** — bound per-partition and total fetch sizes; raise for large messages.
- **Replication throttling** during reassignment (`--throttle`, or `leader.replication.throttled.rate` / `follower.replication.throttled.rate`) prevents a rebalance from starving live traffic.

### 7.6 `min.insync.replicas` placement gotcha

`min.insync.replicas` can be set at the **broker** level (default for new topics) **and** overridden per **topic**. The effective value is the topic-level one if set. A frequent incident: cluster-wide default is `1` and someone forgets to override the important topic to `2`, so `acks=all` quietly degrades. Always set it explicitly on critical topics and audit it.

### 7.7 What exactly happens to uncommitted data on failover

When a new leader is elected from the ISR, its log may be *behind* the old leader's LEO (the old leader had records past the HW that no follower copied). Those above-HW records are **gone** — but they were **never committed**, so no acknowledged write is lost (with `acks=all`). With `acks=1`, those records *were* acked to the producer yet are lost — this is the precise data-loss window of `acks=1`. With **unclean** election, even committed records can vanish.

### 7.8 Combined-mode controller contention & the metadata partition

In combined mode, the controller's metadata Raft replication shares CPU/disk with broker data traffic. Under heavy load the metadata log can lag, slowing all metadata operations (topic creation, reassignment). Dedicated controllers avoid this. Also note the metadata log is a **single partition** — its throughput is bounded but metadata change rate is normally tiny.

### 7.9 Observer/voter dynamics and quorum reconfiguration

Newer KRaft supports **dynamic quorum** changes (add/remove voters) via `kafka-metadata-quorum.sh add-controller/remove-controller`, avoiding the older static `controller.quorum.voters` rigidity. Flag this as version-specific (matured in 3.9/4.x).

### 7.10 Lesser-known: ISR can include replicas that are technically behind the HW

Because ISR membership is **time-based**, a follower that fetched up to the leader's LEO "recently enough" stays in the ISR even if, at this instant, it's a few records behind. The HW is the **min LEO over the ISR**, so the slowest ISR member sets the HW. This is by design — it tolerates normal fetch jitter without flapping the ISR.

---

## 8. Tradeoffs & decision frameworks

### 8.1 `acks` decision table

| `acks` | Durability | Latency | Throughput | Use when… | Avoid when… |
|---|---|---|---|---|---|
| `0` | Lowest (can lose anything) | Lowest | Highest | High-volume metrics where loss is fine | Any data you can't lose |
| `1` | Leader-only (loses on leader crash before replication) | Low | High | Logs, clickstream, loss-tolerant | Financial/order data |
| `all` | Full ISR commit; no loss while one ISR survives | Higher | Lower | Payments, orders, anything important | Pure best-effort firehoses |

### 8.2 RF / `min.insync.replicas` / `acks` combination guide

| RF | `min.insync.replicas` | `acks` | Failures tolerated (no loss) | Writes survive | Notes |
|---|---|---|---|---|---|
| 1 | 1 | any | 0 | broker up | No redundancy; dev only |
| 2 | 1 | all | 0 (degrades to acks=1 if ISR=1) | 1 broker down | Weak; not recommended |
| 2 | 2 | all | 1 | **0** (any loss → writes block) | Durable but fragile availability |
| **3** | **2** | **all** | **1** | **1 broker down** | **Recommended default** |
| 3 | 3 | all | 2 | 0 (any 1 down → writes block) | Max durability, poor write availability |
| 5 | 3 | all | 2 | 2 brokers down | High durability + availability (costly) |

The fundamental tradeoff: higher `min.insync.replicas` (relative to RF) = more durability but less write availability. RF=3/min.ISR=2 is the sweet spot: survive one failure for *both* durability and availability.

### 8.3 ZooKeeper vs. KRaft decision

| Question | Answer |
|---|---|
| New cluster on Kafka ≥ 3.3? | **KRaft** (ZK removed in 4.0). |
| Existing ZK cluster, can't upgrade past 3.x yet? | Stay on ZK until you can migrate; ZK is deprecated, not magic-broken. |
| Need millions of partitions / fast failover? | KRaft (ZK can't). |
| Tiny footprint, fewest moving parts? | KRaft (one system). |

### 8.4 `unclean.leader.election` decision

| Scenario | Setting | Why |
|---|---|---|
| Financial/critical data | `false` (default) | Never silently lose committed data; accept partition offline. |
| Best-effort availability > correctness (rare) | `true` | Restore a leader even if it's behind, accepting loss. |

---

## 9. Failure modes & debugging

### 9.1 Symptom: producers failing with `NotEnoughReplicasException` / `NotEnoughReplicasAfterAppendException`

**Cause:** the ISR for the target partition has shrunk below `min.insync.replicas` (a follower/broker is down or lagging), so `acks=all` writes are rejected. This is the cluster *correctly* refusing to risk durability.

**Diagnose:**
```bash
kafka-topics.sh --bootstrap-server b1:9092 --describe --under-min-isr-partitions
kafka-topics.sh --bootstrap-server b1:9092 --describe --topic <t>   # check Isr vs Replicas
```
Check JMX `UnderMinIsrPartitionCount`. Then find *why* the ISR shrank: a dead broker, a slow disk, long GC pauses, or network issues on the follower. Look at `IsrShrinksPerSec` and broker logs (`Shrinking ISR ... to ...`).

**Fix:** bring the missing replica back / fix the lagging broker. Do **not** "fix" it by lowering `min.insync.replicas` to 1 — that just hides the durability loss.

### 9.2 Symptom: `UnderReplicatedPartitions > 0` persistently

**Cause:** one or more followers can't keep up or a broker is down. Common roots: slow/failing disk on a follower, network saturation, `num.replica.fetchers` too low for the load, an unbalanced cluster, or a broker stuck in long GC.

**Diagnose:** `kafka-topics.sh --describe --under-replicated-partitions`; per-broker `ReplicaFetcherManager,name=MaxLag`; disk I/O (`iostat`), GC logs, network (`iftop`). If only one broker's partitions are URP, that broker is the problem.

### 9.3 Symptom: `OfflinePartitionsCount > 0` (partitions with no leader)

**Cause:** all replicas of a partition are unavailable, or no in-sync replica exists to promote and unclean election is disabled. Reads and writes for those partitions fail.

**Diagnose:** `kafka-topics.sh --describe --unavailable-partitions`. Identify which brokers hold those replicas and why they're down.

**Fix:** restore a broker that holds an in-sync replica. As a **last resort** (accepting data loss): enable/trigger unclean leader election.

### 9.4 Symptom: `ActiveControllerCount != 1`

- **0 across the cluster:** no controller — metadata operations stall, no failovers happen. In ZK mode, check ZooKeeper health (the `/controller` znode); in KRaft, check the controller quorum (`kafka-metadata-quorum.sh describe --status`) — likely lost quorum (≥ majority of controllers down).
- **2 (sum across brokers) = split-brain risk:** usually transient during election; if persistent, suspect a network partition or a stale controller not being fenced. Controller/term epoch fencing should prevent real damage, but investigate.

### 9.5 Symptom: high `IsrShrinksPerSec`/`IsrExpandsPerSec` (ISR flapping)

**Cause:** followers repeatedly cross the `replica.lag.time.max.ms` boundary — usually GC pauses, undersized `num.replica.fetchers`, bursty load, or a marginal network link.

**Fix:** tune JVM/GC, raise `num.replica.fetchers`, investigate the flapping broker's I/O. Don't just raise `replica.lag.time.max.ms` blindly — that masks lag and lengthens the window in which a "in-sync" follower is actually behind.

### 9.6 KRaft-specific: metadata quorum lost / controller can't elect

**Cause:** majority of controller voters are down (e.g., 2 of 3). Without a quorum, no metadata changes commit; the cluster freezes for metadata ops (existing data keeps flowing while leaders survive, but no failovers/reassignments).

**Diagnose:** `kafka-metadata-quorum.sh --bootstrap-server ... describe --status` — look at `CurrentVoters`, leader, and per-voter `LogEndOffset`/lag. Restore controllers to regain majority.

### 9.7 Real-world incident patterns

- **The "min.insync.replicas not set on the important topic" outage:** cluster default `min.insync.replicas=1`; a broker died; `acks=all` writes that operators *thought* were durable had actually been committing to a single replica; the dead broker's recent data was lost. Lesson: explicitly set and audit per-topic `min.insync.replicas=2`.
- **The "rolling restart too fast" self-outage:** an operator restarted brokers without waiting for URP to clear; the ISR for many partitions dropped to 1 < `min.insync.replicas`; `acks=all` producers globally stalled. Lesson: one broker at a time, wait for URP=0 (§5.5).
- **The "unclean election surprise":** `unclean.leader.election.enable=true` was left on; a brief network partition let a stale replica win leadership; consumers saw offsets rewind and reprocessed/duplicated data. Lesson: keep unclean election off for important data.
- **The "controller failover storm" (ZK era):** a cluster with hundreds of thousands of partitions lost its controller; the new controller took 30+ seconds to reload state from ZooKeeper, during which partition leadership couldn't move and producers timed out. This class of incident is precisely what KRaft was built to eliminate.

---

## 10. Interview drill

**Q1. What is the ISR, and when does a replica leave it?**
*Model answer:* The In-Sync Replica set is the replicas (including the leader) sufficiently caught up to the leader. A follower stays in if it has caught up to the leader's LEO within `replica.lag.time.max.ms` (default 30s); otherwise the leader shrinks the ISR to exclude it, and re-adds it once it catches back up.
- *Probe: Is lag measured in messages or time?* Time (modern Kafka). The old message-count metric (`replica.lag.max.messages`) was removed because bursts unfairly ejected healthy followers.
- *Probe: Does the leader count toward the ISR?* Yes, always.
- *Probe: Why is the ISR central to durability?* Commitment (HW advancement) is defined over the ISR, not all replicas, so dead followers don't block progress yet committed data survives any single ISR survivor.

**Q2. Walk me through `acks=all`, `min.insync.replicas`, and replication factor together. Why all three?**
*Model answer:* RF sets how many copies exist; `acks=all` makes the producer wait for the record to be committed to all current ISR members; `min.insync.replicas` sets the minimum ISR size for an `acks=all` write to be accepted. Without `min.insync.replicas≥2`, an ISR that shrinks to just the leader makes `acks=all` silently degrade to `acks=1`. RF=3/min.ISR=2/acks=all survives one failure for both durability and write availability.
- *Probe: What happens when ISR < min.insync.replicas?* Leader rejects writes with `NotEnoughReplicas`; reads continue.
- *Probe: Does `min.insync.replicas` affect `acks=1`?* No — it only gates `acks=all`.

**Q3. What is the high-watermark and why can't consumers read past it?**
*Model answer:* The HW is the offset up to which records are committed (replicated to all ISR members); it equals the minimum LEO across the ISR. Consumers read only up to the HW so they never see records that could be lost if the leader fails before those records are committed.
- *Probe: Where does the HW live and how do followers learn it?* The leader computes it; followers learn the updated HW on their next fetch response, so it lags slightly.
- *Probe: LEO vs HW?* LEO is one-past-the-last record on a given replica; HW is the committed boundary (min LEO over ISR).

**Q4. Explain ZooKeeper's role in old Kafka — znodes, ephemeral nodes, watches.**
*Model answer:* ZooKeeper is a separate, replicated coordination service storing cluster metadata in a tree of znodes. Kafka used ephemeral znodes (e.g., `/brokers/ids/*`, `/controller`) so that a broker's crash auto-deletes its node, and watches to be notified of changes. Controller election was "create the ephemeral `/controller` znode first."
- *Probe: What consensus does ZK use?* ZAB, majority-quorum atomic broadcast; odd ensemble sizes.
- *Probe: What's a watch's gotcha?* One-shot — must re-register after it fires.

**Q5. What is KRaft and why did Kafka move to it? (senior-signal)**
*Model answer:* KRaft stores all metadata in an internal Raft-replicated log (`__cluster_metadata`) managed by a controller quorum, eliminating ZooKeeper. It removes a second system, scales to millions of partitions, and makes controller failover near-instant because standby controllers already hold the full metadata log in memory (vs. reloading from ZK).
- *Probe: How does a broker get metadata in KRaft?* It's an observer that fetches and replays the metadata log.
- *Probe: Does topic data use Raft now?* No — only the metadata log uses Raft; partition data still uses leader/ISR replication.
- *Probe: When was ZK removed?* Deprecated in 3.5, removed in 4.0.

**Q6. Explain Raft in two minutes. How does it differ from Kafka's ISR replication? (senior-signal)**
*Model answer:* Raft elects a leader per term; the leader appends entries and commits them once a majority of voters store them; terms fence stale leaders; new leaders must contain all committed entries. It's a **quorum** model. Kafka partition data commits on **all current ISR members** (which excludes dead/slow replicas), so it isn't a fixed majority. KRaft applies Raft only to metadata.
- *Probe: Why odd quorum sizes?* An even node adds cost without raising fault tolerance (majority of 4 is 3, same as majority of 3... actually 3-of-4 vs 2-of-3: 4 tolerates 1, 3 tolerates 1 — even wastes a node).
- *Probe: What's Raft's analog to Kafka's controller epoch?* The term.

**Q7. What is unclean leader election and when would you enable it? (senior-signal)**
*Model answer:* It's electing an out-of-sync replica as leader when no ISR replica is available, trading data loss for availability. Default is off (consistency-first). Enable only when availability strictly outweighs correctness — rarely for important data; the cost is losing committed records and possibly rewinding offsets.
- *Probe: What's the alternative when ISR is empty and it's off?* The partition goes offline until an ISR replica returns.
- *Probe: Can it cause consumers to reprocess?* Yes — offsets can effectively rewind.

**Q8. Why does Kafka not fsync every record yet still claim durability?**
*Model answer:* Kafka treats "replicated to the ISR across multiple machines" as the durability boundary rather than "fsync'd to local disk," because the probability of all RF replicas (ideally in different racks) losing un-fsynced page-cache data simultaneously is far lower than the throughput cost of synchronous fsync.
- *Probe: When is that assumption wrong?* Correlated failures (whole-rack/AZ power loss) — mitigate with rack awareness and, if extreme, flush tuning.

**Q9. How does Kafka avoid log divergence during leader changes?**
*Model answer:* Leader epochs (KIP-101). Each record range is tagged with the epoch of the leader that wrote it; a follower truncates using `OffsetsForLeaderEpoch` rather than blindly to the HW, removing exactly the diverged records and avoiding the old HW-based truncation's loss/divergence edge cases.

**Q10. Your producers suddenly fail with NotEnoughReplicas during a deploy. Diagnose. (senior-signal)**
*Model answer:* The ISR dropped below `min.insync.replicas`, almost certainly because a rolling restart took a broker down before the previous one re-synced, pushing ISR to 1. Check `UnderMinIsrPartitionCount`, `--under-min-isr-partitions`, and broker logs. Fix by restoring the replica and, going forward, restart one broker at a time waiting for URP=0. Never paper over it by lowering `min.insync.replicas`.

**Q11. What is the controller and what happens when it fails?**
*Model answer:* One broker is the controller; it tracks broker liveness, owns the leader/ISR map, drives leader elections, and propagates metadata. In ZK mode, failure means another broker recreates the `/controller` znode and reloads state from ZK (slow with many partitions). In KRaft, a standby controller in the quorum takes over near-instantly.
- *Probe: How is a stale ex-controller prevented from causing harm?* Controller epoch (ZK) / Raft term (KRaft) fencing — brokers reject lower-epoch requests.

**Q12. Difference between preferred leader election and a failover election?**
*Model answer:* A failover election happens when a leader dies — the controller promotes the first in-ISR replica. Preferred leader election is a rebalancing operation that moves leadership back to the first replica in each partition's assignment (the preferred leader) to even out load; it runs automatically (`auto.leader.rebalance.enable`) or via `kafka-leader-election.sh --election-type PREFERRED`.

---

## 11. Glossary

- **acks** — Producer setting (`0`/`1`/`all`) for how many acknowledgements to await.
- **AlterPartition (AlterIsr)** — Request a leader sends the controller to change a partition's ISR.
- **Assignment (replica list)** — Ordered list of brokers holding a partition's replicas; first is the preferred leader.
- **Committed message** — A record replicated to all current ISR members; visible to consumers, durable while one ISR replica survives.
- **Consensus** — Protocol by which servers agree on a value/log despite failures (e.g., Raft, Paxos, ZAB).
- **Controller** — The broker responsible for cluster metadata, broker-liveness tracking, and leader elections.
- **Controller epoch** — Monotonic counter incremented per controller election; fences stale controllers.
- **Ephemeral znode** — A ZooKeeper node tied to a client session; auto-deleted when the session ends.
- **Fetch request** — Request to read records (consumers and follower replicas both use it).
- **fsync** — Syscall forcing buffered file data from page cache to physical disk.
- **High-watermark (HW)** — Committed-offset boundary; min LEO across the ISR; consumers read up to it.
- **Idempotent producer** — Producer that dedups retried records via producer ID + sequence numbers (`enable.idempotence`).
- **ISR (In-Sync Replicas)** — Replicas sufficiently caught up to the leader (within `replica.lag.time.max.ms`).
- **KRaft** — Kafka's ZooKeeper-free metadata system using a Raft-replicated internal metadata log.
- **Leader (partition)** — The single replica that handles produces and reads for a partition.
- **Leader epoch** — Per-partition counter incremented on each new leader; used for correct log truncation.
- **LEO (Log-End Offset)** — Offset one past the last record in a replica's log.
- **Linearizable / sequential consistency** — Strong ordering guarantees (ZooKeeper provides these).
- **min.insync.replicas** — Minimum ISR size for an `acks=all` write to be accepted.
- **Offset** — Per-partition position index of a record.
- **Page cache** — OS in-memory cache of file data; Kafka relies on it heavily for speed.
- **Partition** — An ordered, replicated log; the unit of parallelism and replication.
- **Preferred leader** — First replica in a partition's assignment; target of preferred-leader rebalancing.
- **Purgatory** — Broker structure holding delayed operations (e.g., `acks=all` produces awaiting HW).
- **Quorum** — A majority of voters required to commit in Raft/ZAB.
- **Raft** — An understandable consensus algorithm (leader, term, majority-committed log).
- **Replica** — One physical copy of a partition's log on a broker.
- **Replication factor (RF)** — Number of replicas per partition.
- **replica.lag.time.max.ms** — Time-based threshold for ISR membership (default 30000).
- **Segment** — A file chunk of a partition's on-disk log.
- **Snapshot (KRaft)** — Periodic materialized state of the metadata log to bound its size.
- **Split-brain** — Two nodes believing they're leader/controller simultaneously; prevented by epochs/quorum.
- **Term (Raft)** — Logical clock incremented per election; fences stale leaders.
- **Unclean leader election** — Electing an out-of-sync replica as leader, risking data loss.
- **Under-replicated partition (URP)** — A partition whose ISR size < replication factor.
- **Watch (ZooKeeper)** — One-shot subscription to a znode change.
- **ZAB** — ZooKeeper Atomic Broadcast, ZooKeeper's consensus protocol.
- **znode** — A node in ZooKeeper's hierarchical namespace holding small data.
- **ZooKeeper** — External distributed coordination service Kafka used pre-KRaft.
- **`__cluster_metadata`** — KRaft's internal metadata log topic.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Durability contract:** `RF=3` + `min.insync.replicas=2` + `acks=all` → survive 1 broker loss, no committed-data loss, writes keep flowing. Drop a 2nd broker → writes block (`NotEnoughReplicas`), reads continue.

**Offsets:** LEO = next write position (per replica). HW = committed boundary = min LEO across ISR. Consumers read ≤ HW only.

**ISR:** time-based (`replica.lag.time.max.ms`, default **30000 ms**). Falls behind → shrink; catches up → expand. Includes the leader.

**acks:** `0` = none (fastest, lossy) · `1` = leader only (loses on leader crash) · `all` = full ISR commit (durable).

**Trap:** `acks=all` + `min.insync.replicas=1` silently degrades to `acks=1` when ISR shrinks to the leader.

**Controller:** one per cluster; owns leader/ISR map + elections. ZK era: ephemeral `/controller` znode + state reload (slow failover). KRaft: Raft quorum over `__cluster_metadata`, standby takes over instantly.

**Unclean election:** OFF by default (consistency); ON = availability at the cost of data loss.

**Failure metrics:** `UnderReplicatedPartitions` (ISR<RF), `UnderMinIsrPartitionCount` (writes failing), `OfflinePartitionsCount` (no leader = outage), `ActiveControllerCount` (must be 1).

**ZK→KRaft:** EA in 2.8, GA 3.3, ZK deprecated 3.5, **removed 4.0**.

**Raft:** leader + term + majority-committed log; tolerates ⌊(n-1)/2⌋ failures; odd quorum sizes. KRaft = Raft for metadata only; partition data still uses ISR.

**Key CLI:** `kafka-topics.sh --describe --under-replicated-partitions` · `kafka-reassign-partitions.sh` (change RF) · `kafka-leader-election.sh` · `kafka-metadata-quorum.sh describe --status`.

### 12.2 Self-test (no answers — recall actively)

1. Partition has RF=3, ISR={leader, F1}, `min.insync.replicas=2`, `acks=all`. F1 then drops out of the ISR. What happens to new produce requests, and to consumers, and why?
2. Explain precisely why `acks=all` with `min.insync.replicas=1` does not give you the durability you might assume. What concrete failure loses data?
3. A follower has LEO=1000; the leader has LEO=1050; the other ISR follower has LEO=1020. What is the high-watermark, and which records can a consumer see?
4. Describe, step by step, what the ZK-era controller does between the moment a leader broker dies and the moment producers can write again — and contrast each step with KRaft.
5. Raft commits on a majority; Kafka partition data commits on "all in the ISR." Give a scenario where these two rules accept a write with a different number of replicas confirming, and explain why both are still correct.
6. Your `UnderMinIsrPartitionCount` JMX metric is non-zero during a deploy. Name the most likely root cause, the exact CLI command you'd run to confirm, and the operational change that prevents recurrence.
7. Why does a freshly promoted leader use leader epochs rather than the high-watermark to decide how much a rejoining follower should truncate? What bug does this prevent?

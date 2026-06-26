# Kafka & Message Brokers — Operations & Troubleshooting

> A definitive, exhaustive engineering handbook chapter for senior backend developers (Java/JVM ecosystem) who want to design with, operate, debug, teach, and interview on the operational side of Apache Kafka.

---

## 1. Overview & where it fits

**What this chapter is about.** Once a Kafka cluster is built and applications are written, the hard part begins: keeping it healthy, fast, and correct under real-world load, hardware failures, bad data, and ever-changing schemas. "Operations & Troubleshooting" is the discipline of *observing* a Kafka deployment (metrics, logs), *diagnosing* what's wrong when symptoms appear (consumer lag growing, partitions under-replicated, brokers falling over), *fixing* it (rebalancing, reassigning partitions, tuning configs), and *planning ahead* (capacity, partition counts, retention) so the problems don't recur.

**The problem it solves.** Kafka is a distributed, replicated, append-only commit log. Distributed systems fail in partial, confusing ways: one disk fills up, one network link gets slow, one consumer in a group dies and triggers a cascade of rebalances, one producer ships a malformed record that jams a consumer forever. Operations is the toolkit and mental model that turns "the pipeline is broken and I don't know why" into "ISR shrank on broker 3 because its disk hit 95% and log flush latency spiked — here's the fix."

**When you reach for this material.** You're on call for a Kafka cluster. You see an alert: "consumer lag on `orders-consumer` is 4 million and climbing." Or "under-replicated partitions = 27." Or "a schema registry change broke all our consumers." Or a capacity-planning meeting asks "can the cluster handle Black Friday?" Every one of those is in scope here.

**One-paragraph mental model.** Think of Kafka as a *warehouse of conveyor belts* (partitions). Producers drop boxes (records) onto the right belt; the box's position on the belt is its **offset** (a monotonically increasing sequence number per partition). Each belt is duplicated onto several machines (**replicas**) so a machine failure doesn't lose boxes; one copy is the **leader** (handles reads/writes) and the rest are **followers** that copy from it. The set of replicas currently caught-up-enough to be trusted is the **ISR** (in-sync replica set). Consumers walk along belts picking up boxes, remembering how far they've gotten (**committed offset**); the gap between the newest box and where a consumer has reached is **lag**. Operations is the job of watching every belt, every machine, every consumer — and reacting when a belt backs up, a machine drops out of the trusted set, or a consumer gets stuck on a jammed box.

**Where it sits relative to the rest of Kafka knowledge.** Producers/consumers and topic design are the *build* phase; replication and the controller are the *internals*; this chapter is the *run* phase that depends on all of them. You cannot troubleshoot what you don't understand internally, so this chapter re-explains the relevant internals as it goes.

---

## 2. Foundations from first principles

This section builds the vocabulary you need before any troubleshooting can make sense. Every term a newcomer might not know is defined inline the first time it appears.

### 2.1 The core objects

**Broker.** A single Kafka server process (a JVM, the Java Virtual Machine — the runtime that executes Java bytecode). A **cluster** is a set of brokers cooperating. Each broker has a unique integer `broker.id`. Brokers store partition data on local disk and serve produce/fetch requests over TCP.

**Topic.** A named stream of records, e.g. `orders`. Topics are split into **partitions** for parallelism and scale.

**Partition.** An ordered, immutable, append-only sequence of records. Ordering is guaranteed *only within a partition*, never across partitions. A partition is the unit of parallelism, replication, and storage. Physically it's a directory of **segment** files on disk.

**Offset.** A 64-bit integer that is the position of a record within its partition. Offsets start at 0 and increase by 1 per record. They are *per-partition*, not global.

**Segment.** A partition is stored as a series of files called segments. The active segment is the one currently being appended to. Each segment has a `.log` (the records), a `.index` (offset → byte position), and a `.timeindex` (timestamp → offset) file. Segments are the unit of retention deletion and compaction.

**Record (message).** A key/value pair plus a timestamp, headers, and metadata. The **key** determines which partition a record goes to (by default `hash(key) % numPartitions`), which is how Kafka guarantees that all records with the same key land in the same partition and thus stay ordered relative to each other.

**Producer.** A client that appends records to topic partitions.

**Consumer.** A client that reads records from partitions, tracking its position.

**Consumer group.** A set of consumers that cooperate to consume a topic. Kafka assigns each partition to exactly one consumer in the group (the **group membership / assignment**), so the group as a whole reads every partition once. Adding consumers up to the partition count increases throughput; beyond that, extra consumers sit idle.

### 2.2 Replication terms (the heart of operational health)

**Replica.** A copy of a partition on a broker. The **replication factor (RF)** is how many copies exist (commonly 3 in production). RF=3 means the partition survives the loss of up to 2 brokers without data loss (given the right configs).

**Leader.** The single replica that handles all produce and consume requests for a partition at a given time. Clients always talk to the leader.

**Follower.** A non-leader replica. Followers do nothing but continuously fetch records from the leader to stay caught up. They are *passive*; they don't serve client reads (except optionally via "follower fetching" / rack-aware reads in newer versions — version-specific, see §7).

**ISR — In-Sync Replica set.** The subset of replicas (including the leader) that are "caught up" with the leader. "Caught up" means a follower has fetched up to within `replica.lag.time.max.ms` (default **30000 ms = 30s** in modern versions) of the leader. A replica that falls behind that window is *removed* from the ISR (an **ISR shrink**); when it catches back up it's re-added (an **ISR expand**). The ISR is the trusted set: a write is considered "committed" once all members of the ISR have it.

**High watermark (HW).** The offset up to which all ISR members have replicated. Consumers can only read up to the HW — never beyond it — because anything past it isn't yet guaranteed durable. This is why a slow follower (shrinking ISR) can stall consumers indirectly: the HW can't advance past records the ISR hasn't all stored.

**Log End Offset (LEO).** The offset of the next record to be written to a replica — i.e., one past the last record it has. The leader's LEO is the newest data; the HW is the min LEO across the ISR.

**Under-replicated partition (URP).** A partition whose current ISR size is *less* than its configured replication factor. This is the single most important cluster-health metric: URP > 0 means at least one replica is lagging or its broker is down, so you have reduced fault tolerance right now.

**min.insync.replicas (min.isr).** A topic/broker config: the minimum number of ISR members required for a produce request with `acks=all` to succeed. With RF=3 and `min.insync.replicas=2`, a producer using `acks=all` is rejected (with `NotEnoughReplicasException`) if fewer than 2 replicas are in sync — trading availability for durability. This is *the* knob for the durability/availability tradeoff.

**acks (producer).** How many acknowledgments the producer waits for: `acks=0` (fire and forget, no durability), `acks=1` (leader only — can lose data if leader dies before followers copy), `acks=all` / `acks=-1` (wait for the full ISR — strongest durability). Default in modern clients is **acks=all** (it changed from `1` to `all` in Kafka 3.0).

### 2.3 The control plane

**Controller.** One broker in the cluster is elected the **controller**. It manages cluster metadata: which broker leads which partition, ISR membership changes, partition reassignments, broker join/leave events, and triggering **leader elections** when a leader broker dies. There is exactly one active controller at a time.

**ZooKeeper (ZK).** Historically (pre-Kafka 2.8, and the default through Kafka 3.x in many deployments), Kafka used **ZooKeeper** — a separate distributed coordination service that stores cluster metadata and helps elect the controller. ZooKeeper is itself a replicated key-value store using a consensus protocol (ZAB, the ZooKeeper Atomic Broadcast protocol — a way for a set of nodes to agree on an ordered log of changes despite failures). Operating ZooKeeper (its own ensemble of 3 or 5 nodes, its own GC and disk concerns) is part of classic Kafka ops.

**KRaft (Kafka Raft).** Starting in Kafka 2.8 (early access) and production-ready from 3.3+, Kafka can run *without* ZooKeeper using **KRaft**, where the metadata itself lives in an internal Kafka log managed by a set of **controller** nodes running the **Raft** consensus algorithm. **Raft** is a consensus protocol (like ZAB/Paxos) where nodes elect a leader and replicate an ordered log of operations; a value is committed once a majority (quorum) has it. KRaft removes ZooKeeper entirely, simplifying ops and dramatically speeding up metadata operations (millions of partitions, faster failover). ZooKeeper mode is **deprecated in 3.x and removed in Kafka 4.0** — so new clusters should be KRaft. Operationally, KRaft changes *where* you look for controller/metadata health but not the core partition concepts.

### 2.4 Consumer position terms

**Committed offset.** The offset a consumer group has durably recorded as "processed" for a partition, stored in the internal compacted topic `__consumer_offsets`. On restart or rebalance, consumers resume from the committed offset.

**Current/position offset.** Where the consumer is actually reading right now (may be ahead of the committed offset if it hasn't committed recently).

**Consumer lag.** `log-end-offset (LEO of partition) − committed offset` for a partition; summed per consumer group, it's the backlog of unprocessed records. The most-watched application-level metric. Growing lag = consumers can't keep up (or are stuck).

**Rebalance.** The process by which a consumer group redistributes partition assignments among its members — triggered when a member joins, leaves, crashes, or times out, or when partitions are added. During a (stop-the-world) rebalance, consumers stop processing. Frequent or cascading rebalances ("rebalance storms") are a top operational pain.

### 2.5 The two latencies you must distinguish

**Produce/fetch request latency.** How long the broker takes to handle a single produce or fetch request (queue time + local processing + remote replication wait + response). Measured per request type via JMX.

**End-to-end latency.** Time from a producer sending a record to a consumer reading it — includes produce latency, replication, the consumer's poll interval, and processing. Different metric, different causes.

With this vocabulary, every alert and incident below becomes legible.

---

## 3. How it works internally — the operational lifecycles

This is the heart of the chapter. To troubleshoot, you must know the exact sequence of events under the hood.

### 3.1 The write path (and why it determines durability)

Step by step, when a producer sends a record with `acks=all`:

1. **Producer batches.** The producer buffers the record in an in-memory accumulator keyed by partition. It waits up to `linger.ms` (default **0**, but commonly tuned to 5–100ms) or until a batch reaches `batch.size` (default **16384** bytes = 16 KB), then sends.
2. **Partition selection.** If the record has a key, partition = `murmur2(key) % numPartitions` (sticky default partitioner for keyless records batches to one partition at a time for efficiency).
3. **Network send.** The batch goes to the **leader** broker for that partition over a single TCP connection (the producer multiplexes; `max.in.flight.requests.per.connection` default **5** controls how many unacknowledged requests can be outstanding — relevant to ordering, see below).
4. **Leader appends to its log.** The leader writes the batch to the active segment of the partition. Crucially, this write goes to the OS **page cache** (the operating system's in-memory cache of disk pages), not necessarily to physical disk immediately. Kafka relies on the OS to flush page cache to disk lazily — this is why Kafka is fast and why durability comes from *replication*, not from fsync (forcing data to physical disk) by default.
5. **Followers fetch.** Follower replicas continuously send fetch requests to the leader and pull the new records, appending to their own logs (also into page cache).
6. **ISR acknowledgment.** Once *all* current ISR members have fetched up to the record's offset, the leader advances the **high watermark** and acknowledges the producer (because `acks=all`). If the ISR has fewer members than `min.insync.replicas`, the produce is rejected before it even waits.
7. **Producer receives ack** (or a retriable error → retries up to `retries`, default effectively `Integer.MAX_VALUE` with `delivery.timeout.ms` default **120000 ms = 2 min** bounding total time).

**Operational consequences of this path:**
- **Log flush** (`log.flush.interval.messages` / `log.flush.interval.ms`) is *off by default* — Kafka doesn't fsync per message. The "log flush latency" metric (`LogFlushRateAndTimeMs`) spikes when the OS flushes large amounts of dirty page cache to a busy/slow disk; high flush latency is an early disk-saturation signal.
- **acks + min.insync.replicas + RF** together define your durability. RF=3, min.isr=2, acks=all is the standard "no data loss on single broker failure" recipe.
- **Ordering** can break if `max.in.flight.requests.per.connection > 1` *and* `enable.idempotence=false` *and* retries happen (a later batch could land before a retried earlier one). Idempotent producer (`enable.idempotence=true`, **default true** since 3.0) preserves ordering even with in-flight=5 by sequence-numbering batches.

### 3.2 The read path and consumer lifecycle

1. **Consumer subscribes** to topics; on first join it triggers a rebalance to get partition assignments.
2. **Fetch loop.** `poll(Duration)` is called repeatedly. Each poll fetches batches (bounded by `fetch.min.bytes` default **1**, `fetch.max.bytes` default **52428800** = 50 MB, `max.partition.fetch.bytes` default **1048576** = 1 MB, `fetch.max.wait.ms` default **500**).
3. **Process.** Application processes the records returned by `poll()`.
4. **Commit.** The consumer commits offsets — either automatically (`enable.auto.commit=true`, default true; `auto.commit.interval.ms` default **5000 ms**) or manually (`commitSync`/`commitAsync`). Auto-commit commits *on poll*, meaning it commits the previous batch's offsets when the next poll happens — a subtle source of duplicate or lost processing if you crash mid-batch.
5. **Heartbeat.** A background thread sends heartbeats every `heartbeat.interval.ms` (default **3000**) to the **group coordinator** (a broker that manages the group). If no heartbeat arrives within `session.timeout.ms` (default **45000 ms** = 45s since 3.0; was 10s earlier), the coordinator declares the member dead and triggers a rebalance.
6. **The poll-interval contract.** Even with heartbeats healthy, if the application doesn't call `poll()` within `max.poll.interval.ms` (default **300000 ms = 5 min**), the consumer is considered stuck (it's processing too slowly), it leaves the group, and a rebalance happens. **This is the #1 cause of rebalance storms**: slow processing of `max.poll.records` (default **500**) records exceeds 5 minutes → kicked out → rejoins → kicked out again.

### 3.3 Rebalance protocol (eager vs. cooperative)

A rebalance reassigns partitions. Two protocols exist:

- **Eager rebalancing (RangeAssignor/RoundRobinAssignor, the classic default).** *Stop-the-world*: all members revoke *all* their partitions, then the group re-forms and everyone gets new assignments. Even adding one consumer pauses the entire group. Painful at scale.
- **Cooperative rebalancing (CooperativeStickyAssignor).** Incremental: only the partitions that actually need to move are revoked; the rest keep processing. Introduced via KIP-429 (Kafka 2.4). Dramatically reduces rebalance impact. **Recommended default** for most workloads: `partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor`.
- **Static membership (KIP-345, Kafka 2.3+).** Each consumer gets a stable `group.instance.id`. On a *transient* restart (e.g., a rolling deploy within `session.timeout.ms`), the coordinator recognizes the returning member and skips the rebalance entirely. Combine with cooperative rebalancing to make deploys nearly rebalance-free.

**Rebalance lifecycle (eager):** member joins → all members get `onPartitionsRevoked` callback (commit offsets here!) → JoinGroup request → coordinator picks a leader (one of the consumers) → leader computes assignment → SyncGroup distributes it → `onPartitionsAssigned` callback → resume. Every step is a window where processing is paused.

### 3.4 Leader election and ISR management (controller's job)

When a broker dies:
1. The controller detects it (via ZK session expiry, or in KRaft via the metadata log heartbeat).
2. For every partition that broker *led*, the controller picks a new leader **from the ISR** (preferring the head of the replica list — the "preferred leader").
3. ISR shrinks for partitions whose followers were on the dead broker (those partitions become **under-replicated**).
4. The controller propagates new leader/ISR metadata to all brokers via the metadata channel; clients refresh metadata and redirect to new leaders.

**Unclean leader election.** If *all* ISR replicas for a partition are unavailable, Kafka faces a choice: stay offline (no leader, partition unavailable) or elect a *non-ISR* replica that's missing some data (data loss). `unclean.leader.election.enable` (default **false**) controls this. Default false = prioritize correctness (partition goes offline until an ISR member returns). Setting it true risks silent data loss — generally avoid except for non-critical, availability-over-correctness topics.

**Preferred leader / leader imbalance.** Each partition has a "preferred" leader (first in its replica list). After failures, leadership can drift so one broker leads disproportionately many partitions (**leader skew / imbalance**), creating a hot broker. `auto.leader.rebalance.enable` (default **true**) periodically (`leader.imbalance.check.interval.seconds`, default **300**) moves leadership back to preferred replicas when imbalance exceeds `leader.imbalance.per.broker.percentage` (default **10**). You can force it with `kafka-leader-election.sh`.

### 3.5 Retention, compaction, and disk lifecycle

- **Time/size retention (`cleanup.policy=delete`).** Segments older than `retention.ms` (default **604800000** = 7 days) or beyond `retention.bytes` (default **-1** = unlimited) are deleted. Deletion happens at the *segment* level — a segment is only deleted when its *entire* contents are past retention. `segment.ms` (default 7 days) / `segment.bytes` (default 1 GB) control segment roll frequency; small segments → finer-grained deletion but more files/open handles.
- **Log compaction (`cleanup.policy=compact`).** Keeps the *latest* value per key (used by `__consumer_offsets`, changelog topics, CDC). A background **log cleaner** thread rewrites segments keeping only the newest record per key. `min.cleanable.dirty.ratio` (default **0.5**) controls how aggressively. Tombstones (null-value records) mark deletions and are retained for `delete.retention.ms` (default 24h) before removal.
- **Disk lifecycle and pressure.** As topics grow, disk fills. When a disk fills completely, the broker's log directory goes **offline**, partitions on it lose a replica (URP), and if it's a leader, leadership moves. A fully-stuck disk can crash the broker. Monitoring free disk %, log retention sizing, and `log.retention.bytes` per topic is core capacity ops.

---

## 4. The complete toolkit

### 4.1 The operational metrics that matter (JMX)

Kafka exposes metrics via **JMX** (Java Management Extensions — the JVM's standard for exposing runtime metrics/management beans, queried by tools like Prometheus JMX exporter, Jolokia, or `jconsole`). The critical ones:

| Metric (MBean) | What it measures | Healthy value | Alarm when |
|---|---|---|---|
| `kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions` | Count of partitions where ISR < RF | **0** | > 0 sustained (lost redundancy) |
| `kafka.server:type=ReplicaManager,name=UnderMinIsrPartitionCount` | Partitions below `min.insync.replicas` | **0** | > 0 (acks=all produces failing) |
| `kafka.server:type=ReplicaManager,name=OfflineReplicaCount` | Replicas with no leader | **0** | > 0 (data unavailable) |
| `kafka.controller:type=KafkaController,name=ActiveControllerCount` | Should be exactly 1 across the cluster | **1** (sum) | 0 (no controller) or >1 (split brain) |
| `kafka.controller:type=KafkaController,name=OfflinePartitionsCount` | Partitions with no leader | **0** | > 0 (producers/consumers blocked) |
| `kafka.server:type=ReplicaManager,name=IsrShrinksPerSec` / `IsrExpandsPerSec` | Rate of ISR membership churn | ~0 | sustained nonzero (flapping followers) |
| `kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce` (and `=Fetch`) | Request latency percentiles | low, stable | p99 spiking |
| `kafka.network:type=RequestMetrics,name=RequestQueueTimeMs` | Time requests wait in queue | low | rising = broker saturated |
| `kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs` | Disk flush latency | low | spiking = disk saturation |
| `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec` / `BytesOutPerSec` | Throughput | baseline | sudden change |
| `kafka.server:type=BrokerTopicMetrics,name=FailedProduceRequestsPerSec` / `FailedFetch...` | Client-facing errors | ~0 | nonzero |
| `kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent` | Network thread idle % | > 0.3 | < 0.3 (network threads saturated) |
| `kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent` | I/O thread idle % | > 0.3 | < 0.3 (request handlers saturated) |
| `kafka.server:type=ReplicaFetcherManager,name=MaxLag,clientId=Replica` | Max replica fetch lag | low | high = followers behind |
| `kafka.consumer:type=consumer-fetch-manager-metrics,name=records-lag-max` (client side) | Consumer lag (per consumer) | low | growing |

**JVM/host metrics** (just as important): GC pause time (long GC pauses can cause ZK/heartbeat timeouts → false-positive ISR shrink and rebalances), heap usage, file descriptor count (Kafka opens many — `ulimit -n` must be high, e.g. 100000+), disk free %, disk I/O utilization (`%util` from `iostat`), network saturation, page cache hit ratio.

### 4.2 CLI tools

| Tool | Purpose | Key examples |
|---|---|---|
| `kafka-topics.sh` | Create/describe/alter/delete topics, inspect partitions/replicas/ISR | `--describe`, `--create`, `--alter --partitions N` |
| `kafka-consumer-groups.sh` | Inspect lag, reset offsets, delete groups, list members | `--describe`, `--reset-offsets`, `--list` |
| `kafka-reassign-partitions.sh` | Move replicas between brokers, change RF, rebalance data | `--generate`, `--execute`, `--verify` |
| `kafka-leader-election.sh` | Trigger preferred or unclean leader election | `--election-type PREFERRED` |
| `kafka-configs.sh` | Get/set dynamic topic/broker/client configs and quotas | `--alter --add-config`, `--describe` |
| `kafka-log-dirs.sh` | Report per-broker, per-log-dir disk usage | `--describe --json` |
| `kafka-dump-log.sh` | Inspect raw segment files (offsets, keys, batches) | `--files x.log --print-data-log` |
| `kafka-get-offsets.sh` (a.k.a. `GetOffsetShell`) | Get earliest/latest offsets per partition | `--time -1` (latest) / `-2` (earliest) |
| `kafka-producer-perf-test.sh` / `kafka-consumer-perf-test.sh` | Load/benchmark | capacity planning |
| `kafka-broker-api-versions.sh` | Check broker/client API compatibility | upgrade checks |
| `kafka-delete-records.sh` | Trim records below a given offset | data deletion/GDPR |
| `kafka-acls.sh` | Manage ACLs (authorization) | security ops |
| `kafka-metadata-quorum.sh` (KRaft) | Inspect KRaft controller quorum health | `--status` |
| `kafka-cluster.sh` | Cluster ID/metadata utilities | KRaft ops |

#### `kafka-consumer-groups.sh` — the lag tool, in depth

```bash
# Describe a group: shows per-partition CURRENT-OFFSET, LOG-END-OFFSET, LAG, the
# owning consumer (CONSUMER-ID), its HOST, and CLIENT-ID. The LAG column is gold.
kafka-consumer-groups.sh --bootstrap-server b1:9092 \
  --describe --group orders-consumer

# List all groups
kafka-consumer-groups.sh --bootstrap-server b1:9092 --list

# Reset offsets to earliest (reprocess everything) — DRY RUN first (no --execute):
kafka-consumer-groups.sh --bootstrap-server b1:9092 \
  --group orders-consumer --topic orders \
  --reset-offsets --to-earliest --dry-run

# Reset to a specific datetime (reprocess from a point in time):
kafka-consumer-groups.sh --bootstrap-server b1:9092 \
  --group orders-consumer --topic orders \
  --reset-offsets --to-datetime 2026-06-24T00:00:00.000 --execute

# Skip a poison message: shift the offset forward by 1 on a stuck partition
kafka-consumer-groups.sh --bootstrap-server b1:9092 \
  --group orders-consumer --topic orders:7 \
  --reset-offsets --shift-by 1 --execute

# IMPORTANT: the group must have NO ACTIVE MEMBERS to reset offsets, or you get
# "Assignments can only be reset if the group is inactive". Stop consumers first.
```

Reset variants: `--to-earliest`, `--to-latest`, `--to-offset N`, `--shift-by N` (can be negative), `--to-datetime`, `--by-duration PT1H`, `--from-file` (CSV).

#### `kafka-topics.sh` — topic/partition inspection, in depth

```bash
# Describe one topic: Partition, Leader, Replicas, Isr columns reveal URPs at a glance.
kafka-topics.sh --bootstrap-server b1:9092 --describe --topic orders
# Topic: orders  PartitionCount: 12  ReplicationFactor: 3  Configs: min.insync.replicas=2
#   Partition: 0  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3      <- healthy
#   Partition: 1  Leader: 2  Replicas: 2,3,1  Isr: 2,3        <- UNDER-REPLICATED (missing 1)

# Find ALL under-replicated partitions cluster-wide:
kafka-topics.sh --bootstrap-server b1:9092 --describe --under-replicated-partitions

# Find partitions with no leader (offline):
kafka-topics.sh --bootstrap-server b1:9092 --describe --unavailable-partitions

# Find partitions below min.insync.replicas:
kafka-topics.sh --bootstrap-server b1:9092 --describe --at-min-isr-partitions
kafka-topics.sh --bootstrap-server b1:9092 --describe --under-min-isr-partitions

# Create a topic with explicit ops-relevant configs:
kafka-topics.sh --bootstrap-server b1:9092 --create --topic orders \
  --partitions 12 --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=604800000 \
  --config cleanup.policy=delete

# Add partitions (CAUTION: breaks key→partition mapping; see §7.3):
kafka-topics.sh --bootstrap-server b1:9092 --alter --topic orders --partitions 24
```

#### `kafka-reassign-partitions.sh` — moving data and changing RF

```bash
# 1. GENERATE a plan: which brokers should hold which replicas.
# topics.json lists the topics to move:  {"topics":[{"topic":"orders"}],"version":1}
kafka-reassign-partitions.sh --bootstrap-server b1:9092 \
  --topics-to-move-json-file topics.json \
  --broker-list "1,2,3,4" --generate > plan.json
# Edit plan.json (the "Proposed" reassignment) — this is also how you CHANGE RF
# by adding/removing broker ids per partition's replica list.

# 2. EXECUTE with a throttle so the data move doesn't saturate the network:
kafka-reassign-partitions.sh --bootstrap-server b1:9092 \
  --reassignment-json-file plan.json --execute \
  --throttle 50000000   # 50 MB/s replication cap during the move

# 3. VERIFY completion (also removes the throttle when done):
kafka-reassign-partitions.sh --bootstrap-server b1:9092 \
  --reassignment-json-file plan.json --verify
```

#### `kafka-configs.sh` — dynamic configuration & quotas

```bash
# View effective topic config:
kafka-configs.sh --bootstrap-server b1:9092 --entity-type topics \
  --entity-name orders --describe

# Change retention dynamically (no restart):
kafka-configs.sh --bootstrap-server b1:9092 --entity-type topics \
  --entity-name orders --alter --add-config retention.ms=259200000  # 3 days

# Throttle a runaway producer (client quota: bytes/sec):
kafka-configs.sh --bootstrap-server b1:9092 --entity-type clients \
  --entity-name bad-producer --alter \
  --add-config 'producer_byte_rate=10485760'   # 10 MB/s

# Dynamic broker config (e.g., raise replication throttle):
kafka-configs.sh --bootstrap-server b1:9092 --entity-type brokers \
  --entity-name 1 --alter \
  --add-config 'leader.replication.throttled.rate=104857600'
```

### 4.3 Key topic configs (the levers you actually pull)

| Config | Default | What it controls | Ops impact |
|---|---|---|---|
| `min.insync.replicas` | 1 (set 2 in prod) | Min ISR for acks=all success | Durability vs availability |
| `retention.ms` | 604800000 (7d) | Time-based retention | Disk usage, replay window |
| `retention.bytes` | -1 (unlimited) | Size-based retention per partition | Disk cap |
| `segment.bytes` | 1073741824 (1GB) | Segment roll size | Deletion granularity, file count |
| `segment.ms` | 604800000 (7d) | Segment roll time | Same |
| `cleanup.policy` | delete | delete / compact / both | Topic semantics |
| `max.message.bytes` | 1048588 (~1MB) | Max record/batch size | Big-message handling |
| `unclean.leader.election.enable` | false | Allow lossy leader election | Availability vs correctness |
| `message.timestamp.type` | CreateTime | Producer vs broker timestamp | Time-based retention/seek correctness |
| `compression.type` | producer | Broker-side compression policy | CPU/disk/network |

### 4.4 Schema Registry & compatibility (a major ops surface)

**Schema Registry** (Confluent's, the de-facto standard; alternatives: AWS Glue Schema Registry, Apicurio) is a separate service that stores versioned schemas (Avro, Protobuf, or JSON Schema) and assigns each a global **schema ID**. Producers register/lookup the schema, then serialize the *schema ID* (a few bytes) into each record instead of the full schema; consumers fetch the schema by ID to deserialize. This keeps records small and enables **schema evolution** — changing the data shape over time without breaking producers/consumers.

**Compatibility modes** govern what schema changes are allowed:

| Mode | Allowed change | Who can upgrade first | Mental rule |
|---|---|---|---|
| `BACKWARD` (default) | New schema can read data written with the **previous** schema. Can delete fields & add **optional** fields (with defaults). | **Consumers first**, then producers | "New code reads old data" |
| `BACKWARD_TRANSITIVE` | Same, but vs **all** previous versions | Consumers first | Stronger |
| `FORWARD` | **Previous** schema can read data written with the **new** schema. Can add fields & delete **optional** fields. | **Producers first**, then consumers | "Old code reads new data" |
| `FORWARD_TRANSITIVE` | Same vs all previous versions | Producers first | Stronger |
| `FULL` | Both BACKWARD and FORWARD vs previous version | Either order | "Old↔new both ways" |
| `FULL_TRANSITIVE` | Both, vs all versions | Either | Strongest |
| `NONE` | No checks | — | Dangerous |

**Why this is an ops concern:** a deploy that registers an incompatible schema is *rejected at registration* (good) — but if compatibility is set to `NONE` or someone force-registers, consumers start throwing `SerializationException`/`Incompatible schema` at runtime and lag explodes. Knowing the mode and the *upgrade order* it implies prevents outages. Operationally you manage modes via the Schema Registry REST API:

```bash
# Get the global compatibility level
curl -s http://schema-registry:8081/config

# Set per-subject (subject = usually "<topic>-value") compatibility to FULL
curl -s -X PUT http://schema-registry:8081/config/orders-value \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d '{"compatibility": "FULL"}'

# Test a candidate schema against the registry BEFORE deploying:
curl -s -X POST http://schema-registry:8081/compatibility/subjects/orders-value/versions/latest \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d @candidate-schema.json
# => {"is_compatible": true|false}
```

---

## 5. Code examples by use case

These are idiomatic Java examples spanning *different* operational scenarios.

### 5.1 Poison-message handling: retry topic + Dead Letter Queue (DLQ)

A **poison message** is a record that always fails processing (bad data, deserialization error, a downstream that will never accept it). If you simply retry in place, the consumer is stuck forever — lag explodes and the whole partition stalls. The pattern: try N times with backoff via **retry topics**, then route to a **DLQ** (a dedicated topic for unprocessable records) for out-of-band inspection, so the main consumer moves on.

```java
// A robust consumer that never gets permanently stuck on a poison message.
public class ResilientConsumer {
    private final KafkaConsumer<String, byte[]> consumer;
    private final KafkaProducer<String, byte[]> producer; // for retry/DLQ routing
    private static final String DLQ_TOPIC = "orders.DLQ";
    private static final int MAX_ATTEMPTS = 3;

    public void run() {
        consumer.subscribe(List.of("orders"));
        while (true) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> rec : records) {
                int attempt = attemptCount(rec); // read "x-attempt" header, default 0
                try {
                    process(rec); // your business logic; may throw
                } catch (RetriableException e) {
                    if (attempt + 1 < MAX_ATTEMPTS) {
                        // Re-publish to the SAME (or a delayed retry) topic with incremented attempt.
                        // Routing to a separate "orders.retry" topic lets you add backoff delay.
                        forward(rec, "orders.retry", attempt + 1);
                    } else {
                        forwardToDlq(rec, e); // give up -> DLQ
                    }
                } catch (NonRetriableException e) {
                    // e.g. deserialization / schema failure: never retry, straight to DLQ.
                    forwardToDlq(rec, e);
                }
            }
            consumer.commitSync(); // commit AFTER routing, so we never lose the record
        }
    }

    private void forwardToDlq(ConsumerRecord<String, byte[]> rec, Exception cause) {
        ProducerRecord<String, byte[]> dlq = new ProducerRecord<>(DLQ_TOPIC, rec.key(), rec.value());
        // Preserve provenance for debugging: original topic/partition/offset + error.
        dlq.headers().add("x-original-topic", rec.topic().getBytes(UTF_8));
        dlq.headers().add("x-original-partition", String.valueOf(rec.partition()).getBytes(UTF_8));
        dlq.headers().add("x-original-offset", String.valueOf(rec.offset()).getBytes(UTF_8));
        dlq.headers().add("x-error", cause.toString().getBytes(UTF_8));
        producer.send(dlq);
    }
}
```

**Spring Kafka** makes this declarative with `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
    // Send to "<topic>.DLT" after retries are exhausted.
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    // 3 retries with exponential backoff (1s, 2s, 4s), then DLT.
    ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setMaxElapsedTime(7000L);
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    // Deserialization errors should NOT be retried — they'll never succeed.
    handler.addNotRetryableExceptions(DeserializationException.class);
    return handler;
}
```

### 5.2 Programmatic lag monitoring (export to your own dashboard)

```java
// Compute consumer-group lag with AdminClient — useful for custom alerting that
// doesn't depend on running the CLI from a cron job.
try (AdminClient admin = AdminClient.create(props)) {
    String group = "orders-consumer";

    // 1. Committed offsets for the group.
    Map<TopicPartition, OffsetAndMetadata> committed =
        admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();

    // 2. Latest (end) offsets for those partitions.
    Map<TopicPartition, OffsetSpec> latestSpec = committed.keySet().stream()
        .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
    Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
        admin.listOffsets(latestSpec).all().get();

    // 3. Lag = end - committed, per partition.
    long totalLag = 0;
    for (var entry : committed.entrySet()) {
        TopicPartition tp = entry.getKey();
        long committedOff = entry.getValue().offset();
        long endOff = latest.get(tp).offset();
        long lag = endOff - committedOff;
        totalLag += lag;
        System.out.printf("%s lag=%d%n", tp, lag);
    }
    // Emit totalLag to Prometheus/StatsD; alert when it crosses a threshold.
}
```

### 5.3 Tuning a consumer to STOP rebalance storms

```java
Properties p = new Properties();
p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092");
p.put(ConsumerConfig.GROUP_ID_CONFIG, "orders-consumer");

// 1. Use COOPERATIVE rebalancing: only moves the partitions that must move.
p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
      CooperativeStickyAssignor.class.getName());

// 2. STATIC MEMBERSHIP: survives transient restarts (rolling deploys) without rebalancing.
p.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "orders-consumer-pod-3");

// 3. Make the poll loop fit within max.poll.interval. If each record takes 2s and we
//    process 500 per poll, that's 1000s >> 300s default -> guaranteed eviction.
//    Fix by lowering batch size and/or raising the interval.
p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);              // smaller batches
p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);      // 10 min headroom

// 4. Decouple liveness (heartbeat) from progress (poll). Heartbeats keep us in the
//    group while we process; poll interval guards against truly-stuck processing.
p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);
p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);

p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // commit manually after work
```

### 5.4 Detecting and mitigating a hot partition

A **hot partition** is one partition receiving far more traffic than its siblings — usually because a single key (or a small set) dominates (e.g., a "whale" customer ID), so `hash(key)%N` funnels everything to one partition, one leader broker, one consumer. Symptoms: lag on one partition while others are idle; one broker hotter than the rest.

```java
// Custom partitioner that spreads a known hot key across partitions using a
// composite key, while keeping normal keys ordered. (Trade ordering of the hot key
// for throughput — only acceptable if the hot key doesn't need strict per-key order.)
public class HotKeyAwarePartitioner implements Partitioner {
    private static final Set<String> HOT_KEYS = Set.of("whale-customer-42");
    private final Random rnd = new Random();

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        int n = cluster.partitionCountForTopic(topic);
        String k = (String) key;
        if (HOT_KEYS.contains(k)) {
            // Salt the hot key so it fans out across all partitions.
            return rnd.nextInt(n);
        }
        return Utils.toPositive(Utils.murmur2(keyBytes)) % n; // default behavior
    }
}
```

Operational alternatives when you *can't* change the key: increase partition count and reassign so the hot partition's leader is alone on a beefy broker; use `kafka-reassign-partitions.sh` to isolate it; or apply a client quota to the noisy producer.

### 5.5 Transactional, exactly-once processing (avoids duplicate-on-rebalance)

```java
// Read-process-write with exactly-once semantics (EOS), so a rebalance/crash mid-batch
// neither loses nor duplicates output. Requires transactional producer + read_committed consumer.
Properties pp = new Properties();
pp.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "orders-enricher-1"); // stable per instance
pp.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // implied by transactional.id
KafkaProducer<String, byte[]> producer = new KafkaProducer<>(pp);
producer.initTransactions();

consumer.subscribe(List.of("orders"));
while (true) {
    ConsumerRecords<String, byte[]> recs = consumer.poll(Duration.ofMillis(200));
    if (recs.isEmpty()) continue;
    producer.beginTransaction();
    try {
        for (ConsumerRecord<String, byte[]> r : recs) {
            producer.send(new ProducerRecord<>("orders.enriched", r.key(), enrich(r.value())));
        }
        // Commit consumer offsets INSIDE the transaction -> atomic with the output.
        producer.sendOffsetsToTransaction(offsetsFor(recs), consumer.groupMetadata());
        producer.commitTransaction();
    } catch (Exception e) {
        producer.abortTransaction(); // nothing committed; safe to reprocess
    }
}
```

### 5.6 Capacity-planning load test

```bash
# Producer throughput test: 10M records, 1KB each, acks=all, measure throughput & p99.
kafka-producer-perf-test.sh \
  --topic loadtest --num-records 10000000 --record-size 1024 \
  --throughput -1 \
  --producer-props bootstrap.servers=b1:9092 acks=all batch.size=65536 linger.ms=10

# Consumer throughput test:
kafka-consumer-perf-test.sh --bootstrap-server b1:9092 \
  --topic loadtest --messages 10000000 --threads 1
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Page cache is your friend.** Kafka's speed comes from sequential disk I/O + OS page cache + zero-copy (`sendfile` syscall — copies file data straight from page cache to the network socket without passing through user space, saving CPU and memory copies). Don't oversize the JVM heap (commonly **6 GB** is plenty for a broker); leave the rest of RAM for page cache. A 64 GB box might run a 6 GB heap and let ~50+ GB be page cache.
- **Disks:** prefer many spindles/SSDs; use multiple `log.dirs` (one per disk) for parallelism. Avoid RAID5 (write penalty); RAID10 or JBOD with RF=3 is typical. NVMe SSDs for low-latency workloads.
- **Network threads (`num.network.threads`, default 3) and I/O threads (`num.io.threads`, default 8):** raise if `*AvgIdlePercent` is low. Replica fetchers (`num.replica.fetchers`, default 1) — raise to speed up follower catch-up on high-throughput clusters.
- **Compression:** `lz4` or `zstd` reduce network/disk at CPU cost. Compress at the producer (`compression.type=lz4`); the broker stores compressed batches.
- **Batching:** `linger.ms` 5–100 + larger `batch.size` dramatically improves throughput at small latency cost.

### 6.2 Correctness & concurrency

- **Durability recipe:** RF=3, `min.insync.replicas=2`, producer `acks=all`, `enable.idempotence=true`. This survives one broker loss with no data loss and no duplicates.
- **`unclean.leader.election.enable=false`** (default) — never silently lose data unless a topic explicitly prefers availability.
- **Ordering:** with idempotence on, ordering holds even with `max.in.flight=5`. Without it, set in-flight to 1 if strict ordering matters during retries.
- **Auto-commit pitfalls:** auto-commit + crash mid-batch = either reprocessing (at-least-once) or, if you commit before processing, loss. Use manual commit after successful processing for at-least-once; transactions for exactly-once.

### 6.3 Security

- **Encryption in transit:** TLS (`security.protocol=SSL` or `SASL_SSL`). Adds CPU; breaks zero-copy (data must be encrypted in user space), so plaintext is faster — security/performance tradeoff.
- **Authentication:** SASL mechanisms — `PLAIN`, `SCRAM-SHA-256/512`, `GSSAPI` (Kerberos), `OAUTHBEARER`.
- **Authorization:** ACLs via `kafka-acls.sh` (who can produce/consume/admin which topic/group).
- **Quotas:** rate-limit per client/user to prevent a noisy tenant from starving others.

### 6.4 Observability

- **Metrics pipeline:** JMX → Prometheus JMX exporter / Confluent metrics → Grafana dashboards. Standard dashboards: cluster (URP, controller count, offline partitions), broker (throughput, request latency, threads), topic, and consumer-group lag (Burrow, Kafka Lag Exporter, or `kafka-consumer-groups.sh` scraped).
- **Lag monitoring tools:** **Burrow** (LinkedIn — evaluates lag *trend/status*, not just absolute number, so a high-but-shrinking lag isn't a false alarm), **Kafka Lag Exporter**, Confluent Control Center, CMAK (Kafka Manager), Conduktor, Kafdrop, AKHQ.
- **Logs:** broker `server.log` (state changes, ISR shrink/expand, controller events), `controller.log`, `state-change.log`. `grep` for `ISR shrink`, `Shrinking ISR`, `Expanding ISR`.

### 6.5 Cost

- Storage dominates cost. Tune `retention.ms`/`retention.bytes` per topic to actual need. Consider **tiered storage** (KIP-405, GA in Kafka 3.6+): offload old segments to object storage (S3/GCS) so brokers keep only hot data on local disk — cuts storage cost and speeds rebalance/failover (less local data to move).
- Right-size RF: RF=3 triples storage and network; RF=2 is cheaper but loses tolerance.

### 6.6 Testing & production hardening

- **Test harnesses:** `EmbeddedKafka` (Spring), Testcontainers Kafka, `kafka-streams-test-utils` (`TopologyTestDriver`).
- **Chaos:** kill brokers, fill disks, inject network latency, force rebalances — validate alerts fire and recovery works.
- **Rolling restarts:** restart one broker at a time, wait for URP to return to 0 before the next (use `kafka-topics.sh --under-replicated-partitions` as the gate). Enable `controlled.shutdown.enable` (default true) so a broker gracefully moves leadership before stopping.
- **Rack awareness:** set `broker.rack`; Kafka spreads replicas across racks/AZs so a rack/AZ failure doesn't take out all replicas of a partition.

### 6.7 Anti-patterns to avoid

| Anti-pattern | Why it hurts | Do instead |
|---|---|---|
| `min.insync.replicas=1` with `acks=all` | No real durability — leader-only ack | min.isr=2, RF=3 |
| Huge `max.poll.records` + slow processing | Exceeds `max.poll.interval.ms` → rebalance storm | Smaller batches, async processing |
| Retrying poison messages in place forever | Partition stalls, lag explodes | Retry topic + DLQ |
| Over-partitioning "just in case" (e.g., 1000s) | More open files, longer rebalances/failover, controller load | Plan to target throughput |
| Adding partitions to a keyed topic casually | Breaks key→partition ordering guarantee | Plan partition count up front |
| Giant JVM heap on brokers | Starves page cache, long GC pauses | ~6 GB heap, rest to page cache |
| `unclean.leader.election.enable=true` everywhere | Silent data loss | Leave false except non-critical topics |
| Ignoring `__consumer_offsets` health | Group coordination breaks | Monitor it like any topic |

---

## 7. Advanced topics & deep internals

### 7.1 ISR shrink/expand mechanics and false positives

A follower is dropped from ISR when its last successful fetch is older than `replica.lag.time.max.ms` (30s). Common *false-positive* causes — the follower is fine but *looks* slow:
- **Long GC pause** on the follower (or leader) blocks fetch processing > 30s → ISR shrinks, then expands when GC finishes ("ISR flapping"). Fix GC (G1, smaller heap) before assuming a real problem.
- **Network blip / saturation** between leader and follower.
- **Disk saturation** on the follower — it can't write fetched data fast enough (watch `iostat %util` and `LogFlushRateAndTimeMs`).
Sustained `IsrShrinksPerSec`/`IsrExpandsPerSec` is the fingerprint. Correlate with GC logs and `iostat`.

### 7.2 The controller and metadata propagation (ZK vs KRaft)

- **ZooKeeper mode:** controller writes leader/ISR state to ZK; on controller failure, brokers race to create the `/controller` znode (ephemeral node), winner becomes controller. Controller failover at huge partition counts can take minutes (it must reload all metadata) — a real scaling pain.
- **KRaft mode:** metadata is a replicated Raft log on dedicated controller nodes. Controller failover is near-instant; supports millions of partitions. Inspect with `kafka-metadata-quorum.sh --bootstrap-server ... describe --status` (shows `LeaderId`, `HighWatermark`, follower lag of voters). The "metadata lag" of a broker behind the controller log is a new health signal.

### 7.3 Partition count planning and the pain of changing it

**Why partition count matters:**
- **Parallelism ceiling:** a consumer group can have at most `numPartitions` active consumers; more partitions = more consumer scaling headroom.
- **Throughput rule of thumb:** estimate target throughput / per-partition throughput. A common heuristic: `partitions = max(target_throughput / producer_per_partition_throughput, target_throughput / consumer_per_partition_throughput)`. Per-partition you might assume ~10 MB/s producer, ~5–20 MB/s consumer depending on processing — **benchmark on your hardware** rather than trusting generic numbers.
- **Costs of too many partitions:** more file handles and memory per broker; longer leader election and controller failover (more partitions to move); larger rebalances; more end-to-end latency (each partition adds replication overhead). LinkedIn-era guidance suggested keeping partitions per broker in the low thousands; KRaft raises the ceiling dramatically.

**The pain of changing it:**
- **You can only ADD partitions, never remove** (removing would lose data and reorder). Adding is `kafka-topics.sh --alter --partitions N`.
- **Adding partitions BREAKS the key→partition mapping.** Since partition = `hash(key) % N`, increasing N sends a key to a *different* partition than before. This destroys per-key ordering across the change boundary and can break stateful consumers/compacted-topic semantics. New records for a key may now appear before older records for that key are consumed. **Conclusion: plan partition count up front; if you must repartition, do it deliberately (new topic + migration) for keyed topics.**
- Adding partitions also doesn't rebalance existing data; only new records use the new partitions until retention ages out old data.

### 7.4 Tiered storage (KIP-405)

Offloads closed (non-active) segments to remote object storage. Local disk keeps only recent data + indexes. Benefits: cheaper long retention, faster broker recovery and reassignment (less local data). Caveats: remote reads are slower (cold consumers fetching old data hit object storage latency); adds operational dependency on the object store. GA in 3.6+ (still maturing; check version).

### 7.5 Lesser-known behaviors

- **`__consumer_offsets`** is a 50-partition (default `offsets.topic.num.partitions`) compacted internal topic; group coordinator for a group is the broker leading `hash(groupId) % 50`. If that broker is overloaded, *that* group's commits slow down.
- **Producer `delivery.timeout.ms`** (default 2 min) bounds total time including retries; if exceeded you get a timeout even though `retries` is "infinite."
- **Rebalance "leader" is a consumer, not a broker:** the group coordinator (broker) picks one consumer to compute the assignment, which is why a buggy custom assignor can wedge a whole group.
- **`replica.fetch.max.bytes`** and `message.max.bytes` must be consistent across producer/broker/consumer or large messages get stuck (producer accepted, replica can't fetch, consumer can't read).
- **Quota throttling** is *silent* from the app's view — it just slows down; check `*-throttle-time` metrics if a client is mysteriously slow.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Durability vs availability vs latency

| Setting combo | Durability | Availability | Latency | Use when |
|---|---|---|---|---|
| acks=0 | none | highest | lowest | metrics/logs you can lose |
| acks=1, RF=3 | leader-only (can lose) | high | low | non-critical, throughput-first |
| acks=all, RF=3, min.isr=2 | strong (1-broker tolerant) | medium | higher | payments, orders, anything important |
| acks=all, RF=3, min.isr=3 | strongest | lower (any broker down blocks writes) | highest | rare; max safety, accept write stalls |

### 8.2 Rebalance strategy

| Strategy | Pause behavior | Best for |
|---|---|---|
| Eager (Range/RoundRobin) | Stop-the-world | small/stable groups |
| CooperativeSticky | Incremental, minimal pause | most production groups |
| Static membership (+cooperative) | Skips rebalance on transient restart | rolling deploys, K8s |

### 8.3 Delivery semantics

| Semantic | Mechanism | Cost | Use when |
|---|---|---|---|
| At-most-once | commit before processing | possible loss | lossy-tolerant |
| At-least-once | commit after processing | possible duplicates | most pipelines (make consumers idempotent) |
| Exactly-once (EOS) | transactions + read_committed | throughput/latency overhead | financial, dedup-critical |

### 8.4 Schema compatibility choice

- Choose **BACKWARD** (default) when you control consumers and can deploy them first (most common).
- Choose **FORWARD** when producers must upgrade first (e.g., many independent consumer teams).
- Choose **FULL** when upgrade order can't be coordinated.
- Use **`_TRANSITIVE`** variants when consumers may read very old data (replay/reprocessing).

### 8.5 ZooKeeper vs KRaft

| Dimension | ZooKeeper | KRaft |
|---|---|---|
| Extra system to run | Yes (ZK ensemble) | No |
| Max partitions | ~200k practical | millions |
| Controller failover | seconds–minutes | sub-second |
| Status | deprecated (gone in 4.0) | the future |

**Rule:** new clusters → KRaft. Existing ZK clusters → plan migration before Kafka 4.0.

---

## 9. Failure modes & debugging

Each entry: symptom → likely cause → diagnosis (actual commands/metrics) → fix.

### 9.1 Consumer lag growing unbounded
- **Symptom:** `LAG` column climbing; `records-lag-max` rising.
- **Causes:** consumers too slow / too few; a stuck consumer; a poison message; downstream (DB/API) slow; a rebalance storm preventing progress; partition skew (one partition's lag dominates).
- **Diagnose:**
  ```bash
  kafka-consumer-groups.sh --bootstrap-server b1:9092 --describe --group g
  # Look: is lag concentrated on ONE partition (hot/poison) or spread (under-provisioned)?
  # Is CONSUMER-ID null on some partitions (no owner -> mid-rebalance / fewer consumers than partitions)?
  ```
  Check app logs for repeated processing errors; check `max.poll.interval` evictions in client logs.
- **Fix:** add consumers (up to partition count); speed up processing / parallelize; route poison messages to DLQ; fix the slow downstream; resolve rebalance cause.

### 9.2 Rebalance storm (group constantly rebalancing)
- **Symptom:** repeated "Revoking partitions" / "Attempt to heartbeat failed" in consumer logs; lag oscillating; throughput collapses.
- **Causes:** processing exceeds `max.poll.interval.ms`; `session.timeout.ms` too low vs GC/network jitter; consumers crashing/OOMing; non-cooperative assignor amplifying every join/leave; flapping pods (K8s) without static membership.
- **Diagnose:** grep consumer logs for `max.poll.interval.ms` and `Member ... rejoining`; check pod restart counts; measure per-batch processing time vs `max.poll.records`.
- **Fix:** lower `max.poll.records` / raise `max.poll.interval.ms`; switch to `CooperativeStickyAssignor`; add `group.instance.id` (static membership); fix the crash; tune heartbeat/session timeouts.

### 9.3 Under-replicated partitions (URP > 0)
- **Symptom:** `UnderReplicatedPartitions` metric > 0; `kafka-topics.sh --under-replicated-partitions` lists partitions.
- **Causes:** a broker down; a follower can't keep up (disk/network/GC saturation); a recent reassignment in flight; throttle too low so replication can't catch up.
- **Diagnose:**
  ```bash
  kafka-topics.sh --bootstrap-server b1:9092 --describe --under-replicated-partitions
  # Cross-check which broker(s) are missing from Isr lines.
  ```
  Check that broker's `server.log` for "Shrinking ISR", GC logs for long pauses, `iostat -x 1` for disk `%util`, network for saturation.
- **Fix:** bring the broker back; relieve disk pressure (delete data / extend retention down / add disk); fix GC; raise `num.replica.fetchers` / replication throttle; if a reassignment is in flight, just wait (URP is expected during moves).

### 9.4 Under-min-ISR (produces failing with acks=all)
- **Symptom:** producers get `NotEnoughReplicasException` / `NotEnoughReplicasAfterAppendException`; `UnderMinIsrPartitionCount > 0`.
- **Cause:** ISR dropped below `min.insync.replicas` (e.g., RF=3, min.isr=2, and 2 brokers down/lagging).
- **Diagnose:** `kafka-topics.sh --describe --under-min-isr-partitions`.
- **Fix:** restore replicas (the durability/availability tradeoff is *working as designed* — it's refusing to accept writes it can't make durable). Temporarily, you *can* lower `min.insync.replicas` to keep accepting writes, accepting reduced durability — do this consciously.

### 9.5 Disk pressure / log dir offline
- **Symptom:** broker log: "Disk error" / "log directory ... offline"; `OfflineReplicaCount > 0`; URP spikes; broker may crash.
- **Diagnose:** `df -h`, `kafka-log-dirs.sh --describe`, broker `server.log` for `KafkaStorageException`.
- **Fix:** free space fast — lower `retention.ms`/`retention.bytes` dynamically via `kafka-configs.sh` (takes effect on next log-retention check), or `kafka-delete-records.sh` to trim. Then add capacity. Long-term: alert on disk % at 70/85, enable tiered storage.

### 9.6 Poison message stalling a partition
- **Symptom:** one partition's lag rises while others are fine; consumer logs show the *same* offset failing repeatedly.
- **Diagnose:** `kafka-consumer-groups.sh --describe` shows the stuck partition; `kafka-console-consumer.sh --partition P --offset O --max-messages 1` to inspect the record; `kafka-dump-log.sh` to inspect raw bytes.
- **Fix:** implement retry-topic+DLQ pattern; as an emergency, skip the offset:
  ```bash
  kafka-consumer-groups.sh --bootstrap-server b1:9092 \
    --group g --topic t:P --reset-offsets --shift-by 1 --execute   # group must be stopped
  ```

### 9.7 Hot partition / broker
- **Symptom:** one broker much hotter (CPU/network/disk) than peers; one partition's lag/throughput dominates.
- **Diagnose:** per-broker `BytesInPerSec`/`BytesOutPerSec`; per-partition lag in `--describe`; identify the dominant key (sample records).
- **Fix:** salt/repartition the hot key (§5.4); reassign the hot partition's leader to a dedicated broker; rebalance leadership (`kafka-leader-election.sh --election-type PREFERRED`); quota the noisy producer.

### 9.8 Schema incompatibility outage
- **Symptom:** consumers throw `SerializationException` / "Schema being registered is incompatible"; lag spikes after a deploy.
- **Diagnose:** check Schema Registry compatibility mode and recent versions:
  ```bash
  curl -s http://schema-registry:8081/config/orders-value
  curl -s http://schema-registry:8081/subjects/orders-value/versions
  ```
- **Fix:** revert the incompatible producer; set correct compatibility mode; test schemas pre-deploy via the `/compatibility` endpoint; deploy in the order the mode requires (BACKWARD → consumers first; FORWARD → producers first).

### 9.9 No active controller / multiple controllers
- **Symptom:** `ActiveControllerCount` (summed) is 0 or >1; metadata operations hang; leader elections don't happen.
- **Diagnose (ZK):** controller logs, ZK `/controller` znode. **(KRaft):** `kafka-metadata-quorum.sh --describe --status`.
- **Fix (ZK):** usually a controller broker GC/ZK-session issue — restart the misbehaving controller broker to force re-election; ensure exactly one wins. **(KRaft):** check controller quorum voters' health and metadata lag.

### 9.10 Real-world incident patterns (composites of common postmortems)
- **"The GC-induced rebalance cascade."** A heap-pressured consumer pauses for a 40s GC → coordinator evicts it → eager rebalance pauses the whole group → backlog grows → other consumers now process more, GC harder, get evicted too → storm. **Lesson:** tune GC, use cooperative+static membership, right-size `max.poll.records`.
- **"The silent schema break."** Compatibility was set to `NONE`; a producer added a required field without default → downstream consumers across 6 teams started failing → lag everywhere. **Lesson:** enforce `BACKWARD`/`FULL`, test in CI against the registry.
- **"The disk that filled at 3am."** Retention was time-based only; a traffic spike filled disks before time retention kicked in → log dir offline → URP → cascading leader moves overloaded survivors. **Lesson:** set `retention.bytes` too, alert on disk %, capacity headroom.
- **"The repartition that scrambled order."** Someone bumped a keyed topic from 12→24 partitions to "fix lag" → per-key ordering broke → a stateful consumer double-applied updates. **Lesson:** never casually add partitions to keyed topics.
- **"The unclean election data loss."** `unclean.leader.election.enable=true` on a critical topic; all ISR died briefly, a stale replica became leader → committed records vanished. **Lesson:** keep it false on critical topics.

---

## 10. Interview drill

**Q1. What is the ISR and why does it matter operationally?**
*Model answer:* The in-sync replica set is the leader plus followers caught up within `replica.lag.time.max.ms` (30s). A write with `acks=all` is committed only when all ISR members have it, and the high watermark — the read ceiling for consumers — is the min offset across the ISR. Operationally, ISR size vs RF defines under-replication (lost redundancy), and ISR size vs `min.insync.replicas` defines whether `acks=all` writes are accepted.
- *Follow-up: What causes an ISR to shrink without an actual broker failure?* Long GC pauses, network saturation, or disk saturation on a follower (or leader) make fetches lag past 30s. Look for ISR flapping (`IsrShrinks/ExpandsPerSec`) correlated with GC logs and `iostat`.
- *Follow-up: How does the high watermark relate to consumer reads?* Consumers can only read up to the HW; a lagging ISR member can stall HW advance, indirectly increasing end-to-end latency even though produces succeed.
- *Follow-up: RF=3, min.isr=2, acks=all — how many brokers can you lose?* One with no impact; a second drops you to URP and, if it takes ISR below 2, produces fail (correct behavior).

**Q2. Walk me through diagnosing growing consumer lag.**
*Model answer:* Run `kafka-consumer-groups.sh --describe`. Determine if lag is concentrated on one partition (hot key or poison message) or spread (under-provisioned/slow). Check for null `CONSUMER-ID` (mid-rebalance or fewer consumers than partitions). Check app logs for repeated errors and `max.poll.interval` evictions, and the downstream's latency. Fix accordingly: add consumers up to partition count, parallelize processing, DLQ the poison message, or fix the slow dependency.
- *Follow-up: Lag is high but the trend is flat/declining — is that an incident?* Often not; that's why Burrow evaluates lag *status/trend*, not just the number. A consumer catching up after a deploy shows high-but-shrinking lag.
- *Follow-up: All consumers idle yet lag grows — why?* They may be stuck rebalancing repeatedly, blocked on a downstream, or evicted for exceeding `max.poll.interval.ms`.

**Q3. What is a rebalance storm and how do you stop one? (senior-signal)**
*Model answer:* Repeated, often cascading rebalances where the group spends more time reassigning than processing. Root causes: processing exceeding `max.poll.interval.ms`, GC/heartbeat timeouts, crashing pods, eager assignor amplifying churn. Fixes, in order of leverage: switch to `CooperativeStickyAssignor` (incremental), add static membership (`group.instance.id`) to skip transient-restart rebalances, right-size `max.poll.records`/`max.poll.interval.ms`, and fix the underlying crash/GC. The senior signal is sequencing cheap config fixes before architecture changes and explaining *why* cooperative+static together neutralize deploy churn.

**Q4. Why can you add partitions but adding them is dangerous? (senior-signal)**
*Model answer:* Partition for a keyed record is `hash(key) % N`. Increasing N changes the target partition for existing keys, breaking per-key ordering across the change and corrupting stateful/compacted semantics. You also can't reduce partitions. So partition count must be planned for target throughput up front; repartitioning a keyed topic should be a deliberate migration to a new topic, not an `--alter`.
- *Follow-up: How do you choose partition count?* Estimate target throughput / per-partition throughput (benchmarked on your hardware), also bounded by desired consumer parallelism, while avoiding over-partitioning costs (file handles, longer failover/rebalance, controller load).
- *Follow-up: Costs of too many partitions?* More open files and memory, longer leader election/controller failover, bigger rebalances, more replication overhead/latency.

**Q5. Explain the durability knobs and how they interact. (senior-signal)**
*Model answer:* `acks` (producer wait), RF (replica count), `min.insync.replicas` (ISR floor for acks=all), and `unclean.leader.election.enable` (whether to elect a stale replica). The standard safe recipe is RF=3, min.isr=2, acks=all, idempotence on, unclean=false — survives one broker loss with no loss/duplicates, refuses writes it can't make durable. The senior signal is articulating the *tradeoff*: min.isr=2 with acks=all blocks writes when only one replica is in sync (availability cost) to guarantee durability, and that this is correct, not a bug.

**Q6. How does poison-message handling work, and why not just retry in place?**
*Model answer:* Retrying in place stalls the partition forever — lag explodes and that partition stops. Instead: classify errors (retriable vs not), retry retriable ones a bounded number of times (often via retry topics for backoff), then route exhausted/non-retriable records to a DLQ with provenance headers (original topic/partition/offset/error). The main consumer commits and moves on. Spring Kafka's `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` does this declaratively, with non-retryable exceptions (e.g., deserialization) going straight to the DLT.

**Q7. Schema Registry compatibility modes — what do BACKWARD/FORWARD/FULL mean and how do they affect deploy order?**
*Model answer:* BACKWARD (default): new schema reads old data → upgrade *consumers first*; allows deleting fields and adding optional ones. FORWARD: old schema reads new data → upgrade *producers first*; allows adding fields and removing optional ones. FULL: both → either order. `_TRANSITIVE` variants check against all prior versions, needed when replaying old data. Operationally, choosing the wrong mode or `NONE` lets an incompatible schema reach runtime and break consumers.
- *Follow-up: Which to default to?* BACKWARD for most teams that control and deploy consumers; FULL when upgrade order can't be coordinated.

**Q8. ZooKeeper vs KRaft — operational differences? (senior-signal)**
*Model answer:* ZK is a separate ensemble storing metadata; controller failover and metadata ops slow down at high partition counts (reloading all state), capping practical partitions around the low hundreds of thousands. KRaft puts metadata in an internal Raft log on dedicated controllers — sub-second failover, millions of partitions, one fewer system to operate. ZK is deprecated and removed in Kafka 4.0, so new clusters use KRaft and existing ones must migrate. You inspect KRaft health with `kafka-metadata-quorum.sh`.

**Q9. A broker's disk hit 100% at night. Walk me through it.**
*Model answer:* The log dir goes offline (`KafkaStorageException`), its replicas become offline → URP rises, leadership moves to other brokers (which now carry more load, risking cascade). Immediate fix: free space by dynamically lowering `retention.ms`/`retention.bytes` via `kafka-configs.sh` or trimming with `kafka-delete-records.sh`, then add capacity. Prevent recurrence: set size-based retention too, alert on disk % at 70/85, keep headroom, consider tiered storage.

**Q10. What metrics would you alert on for cluster health, and why?**
*Model answer:* `UnderReplicatedPartitions` (>0 = lost redundancy), `UnderMinIsrPartitionCount` (>0 = acks=all failing), `OfflinePartitionsCount` (>0 = unavailable data), `ActiveControllerCount` (must sum to exactly 1), request latency p99 and request-queue time (saturation), `IsrShrinks/ExpandsPerSec` (flapping), consumer-group lag (app health), plus host signals: disk %, GC pause time, file descriptors, network/disk `%util`. Each maps to a concrete failure class.

**Q11. How do you do a safe rolling restart of a Kafka cluster?**
*Model answer:* Restart one broker at a time with controlled shutdown enabled (it moves leadership off first). After each restart, wait until `UnderReplicatedPartitions` returns to 0 (use `kafka-topics.sh --under-replicated-partitions` as the gate) before touching the next broker, so you never drop below durability guarantees. Restart the controller broker last (or accept one controller failover). Combine with cooperative+static consumer config so client groups don't storm during the broker churn.

**Q12. Producer is mysteriously slow but no errors — what do you check?**
*Model answer:* Likely quota throttling (silent) — check `*-throttle-time` client metrics and any configured `producer_byte_rate`. Also check `acks=all` waiting on a lagging ISR, `linger.ms`/`batch.size` config, network latency, broker request-queue time, and whether the target partition's leader broker is saturated/hot. Use `kafka-configs.sh --entity-type clients --describe` to see quotas.

---

## 11. Glossary

- **acks:** Producer setting for how many acknowledgments to wait for (0 / 1 / all). Default `all` (3.0+).
- **AdminClient:** Java API for administrative operations (topics, configs, offsets, groups).
- **At-least-once / at-most-once / exactly-once:** Delivery semantics — possible duplicates / possible loss / neither.
- **Broker:** A single Kafka server (JVM process).
- **Cluster:** A set of cooperating brokers.
- **Committed offset:** Durably recorded consumer position, stored in `__consumer_offsets`.
- **Compaction:** Retention policy keeping the latest value per key.
- **Consumer group:** Set of consumers sharing partitions of a topic.
- **Controller:** The single broker managing cluster metadata and leader elections.
- **Cooperative rebalancing:** Incremental rebalance that moves only necessary partitions (`CooperativeStickyAssignor`).
- **DLQ / DLT (Dead Letter Queue/Topic):** Topic for records that can't be processed, for out-of-band handling.
- **Eager rebalancing:** Stop-the-world rebalance revoking all partitions.
- **End-to-end latency:** Producer-send to consumer-read time.
- **Follower:** Passive replica that fetches from the leader.
- **fsync:** Syscall forcing OS buffers to physical disk.
- **GC (Garbage Collection):** JVM memory reclamation; long pauses can cause timeouts/ISR flaps.
- **Group coordinator:** Broker managing a consumer group's membership and offsets.
- **High watermark (HW):** Offset up to which all ISR members have replicated; the consumer read ceiling.
- **Hot partition:** A partition receiving disproportionate traffic (key skew).
- **Idempotent producer:** Dedups retries via sequence numbers; preserves ordering. Default on (3.0+).
- **ISR (In-Sync Replica set):** Replicas caught up within `replica.lag.time.max.ms`.
- **JMX (Java Management Extensions):** JVM standard for exposing metrics/management beans.
- **JVM (Java Virtual Machine):** Runtime executing Java bytecode.
- **KRaft (Kafka Raft):** ZooKeeper-free metadata mode using the Raft consensus protocol.
- **Lag:** Backlog = log-end-offset − committed offset.
- **Leader:** The replica serving reads/writes for a partition.
- **LEO (Log End Offset):** Offset one past the last record in a replica.
- **Log cleaner:** Background thread performing compaction.
- **Log flush:** OS/Kafka flush of page-cache data to disk; `LogFlushRateAndTimeMs` tracks latency.
- **min.insync.replicas:** Minimum ISR for `acks=all` produces to succeed.
- **Offset:** Per-partition position of a record.
- **Page cache:** OS in-memory cache of disk pages; key to Kafka performance.
- **Partition:** Ordered, append-only subset of a topic; unit of parallelism/replication.
- **Poison message:** A record that always fails processing.
- **Preferred leader:** First replica in a partition's replica list; the rebalance target for leadership.
- **Quota:** Per-client/user rate limit (silent throttling).
- **Raft / ZAB / Paxos:** Consensus protocols for agreeing on an ordered log across nodes.
- **Rebalance:** Reassignment of partitions among consumers in a group.
- **Rebalance storm:** Repeated/cascading rebalances that stall a group.
- **Replica / Replication factor (RF):** A partition copy / number of copies.
- **Retention:** How long/much data is kept (`retention.ms` / `retention.bytes`).
- **Schema Registry:** Service storing versioned schemas and enforcing compatibility.
- **Segment:** File-level chunk of a partition; unit of deletion/compaction.
- **sendfile / zero-copy:** Syscall sending file data from page cache directly to a socket.
- **Static membership:** Stable `group.instance.id` so transient restarts skip rebalancing.
- **Tiered storage:** Offloading old segments to object storage (KIP-405).
- **Tombstone:** Null-value record marking a key for deletion in compaction.
- **Unclean leader election:** Electing a non-ISR (stale) replica as leader — risks data loss.
- **Under-replicated partition (URP):** Partition with ISR < RF.
- **ZooKeeper:** Legacy external coordination service (deprecated; removed in Kafka 4.0).

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Critical metrics (alert on):** `UnderReplicatedPartitions`=0 · `UnderMinIsrPartitionCount`=0 · `OfflinePartitionsCount`=0 · `ActiveControllerCount`=1 (sum) · low/stable Produce/Fetch `TotalTimeMs` p99 · `RequestQueueTimeMs` low · `IsrShrinks/ExpandsPerSec`≈0 · consumer lag flat/low · disk % < 85 · GC pause low · fds under `ulimit`.

**Key defaults:** `replica.lag.time.max.ms`=30000 · `session.timeout.ms`=45000 · `heartbeat.interval.ms`=3000 · `max.poll.interval.ms`=300000 · `max.poll.records`=500 · `auto.commit.interval.ms`=5000 · `retention.ms`=604800000 (7d) · `segment.bytes`=1GB · `acks`=all (3.0+) · `enable.idempotence`=true (3.0+) · `unclean.leader.election.enable`=false · `min.insync.replicas`=1 (set 2) · `__consumer_offsets` partitions=50 · `delivery.timeout.ms`=120000 · `max.in.flight`=5.

**Durability recipe:** RF=3 + `min.insync.replicas=2` + `acks=all` + idempotence on + unclean=false → survives 1 broker loss, no loss/dupes.

**Decision rules:**
- Lag growing → describe group; concentrated (hot/poison) vs spread (under-provisioned); fix accordingly.
- Rebalance storm → CooperativeSticky + static membership + smaller `max.poll.records` / bigger `max.poll.interval.ms` + fix crash/GC.
- URP>0 → which broker missing from ISR; check GC/disk/network; raise replica fetchers/throttle.
- Under-min-ISR → produces failing *by design*; restore replicas (or consciously lower min.isr).
- Disk full → dynamically drop retention / delete-records, then add capacity; set `retention.bytes`.
- Poison message → retry topic + DLQ; emergency `--reset-offsets --shift-by 1` (group stopped).
- Adding partitions to keyed topic → don't (breaks key→partition ordering); plan up front.
- Schema deploy → BACKWARD=consumers first, FORWARD=producers first, FULL=either; test against registry in CI.

**Core CLI:** `kafka-topics.sh --describe --under-replicated-partitions` · `kafka-consumer-groups.sh --describe --group g` · `kafka-reassign-partitions.sh --generate/--execute/--verify --throttle` · `kafka-configs.sh --alter --add-config` · `kafka-log-dirs.sh --describe` · `kafka-leader-election.sh --election-type PREFERRED` · `kafka-metadata-quorum.sh --status` (KRaft).

### Self-test (no answers — active recall)

1. RF=3, `min.insync.replicas=2`, `acks=all`: exactly what happens to producers when the 1st broker dies, and what happens when the 2nd also dies? Which metric flags each stage?
2. A consumer group shows healthy heartbeats but is still being kicked out and rebalancing. What single config is the most likely culprit, why, and what two changes would you make?
3. You bumped a keyed topic from 16 to 32 partitions to reduce lag and now a stateful consumer is producing wrong results. Explain the mechanism precisely and what you should have done instead.
4. Walk through, in order, what the controller does when a leader broker dies, and what `unclean.leader.election.enable` changes about that sequence.
5. Your team uses Schema Registry in BACKWARD mode and is adding a new field. In what order must you deploy producers and consumers, and what property must the new field have to pass compatibility? How would FORWARD mode change the answer?
6. Distinguish log flush latency from request latency from end-to-end latency: what does each measure, which metric exposes it, and which one does a saturated follower disk most directly affect?
7. Design a poison-message strategy for a topic where some failures are transient (downstream timeout) and some are permanent (bad schema). Specify retry count, where retries and DLQ live, and the commit point.

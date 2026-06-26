# Multi-Region & Geo-Partitioning

> A definitive engineering-handbook chapter for senior backend developers (Java/JVM-centric, but the concepts are universal). We start from first principles and climb to deep internals, operations, and interview-grade mastery.

---

## 1. Overview & where it fits

### 1.1 What it is

**Multi-region** means running your database (and usually the rest of your stack) across two or more **geographic regions** — physically separate datacenter clusters, often thousands of kilometers apart, connected only over the wide-area network (WAN). A *region* in cloud terms (AWS `us-east-1`, GCP `europe-west1`, Azure `westeurope`) is a cluster of nearby datacenters; inside a region there are **Availability Zones (AZs)** — isolated datacenters with independent power, cooling, and networking but low-latency links (sub-millisecond to ~2 ms) between them. The jump from "multi-AZ" to "multi-region" is a jump in *physics*: intra-region links are short and fast; inter-region links are long (light takes ~5 ms to cross the continental US one-way, ~40 ms across the Atlantic, ~80 ms US-to-Asia) and lossy.

**Geo-partitioning** (also called *geo-sharding*, *partition pinning*, or *data domiciling*) is the practice of deciding, **per row or per partition, which region physically stores and owns that data**. A European user's rows live on disks in Frankfurt; a US user's rows live in Virginia. It is *partitioning* (Chapter 6's core idea — splitting one logical table into many physical pieces) where the partition key, or a derived locality attribute, dictates *geography* rather than just *which machine*.

### 1.2 The problem it solves

Three forces push you toward multi-region, and you usually feel them in this order:

1. **Latency.** The speed of light is a hard floor. A user in Sydney talking to a database in Virginia pays ~160 ms round-trip just in fiber, before any processing. Put the data near the user and that drops to single-digit milliseconds. For chatty workloads (many sequential queries per request), latency compounds and becomes the dominant cost.

2. **Availability / disaster tolerance.** A single region *will* fail — fire, flood, fiber cut, control-plane bug, bad config push, an entire cloud region brownout. (Real example: the AWS `us-east-1` outages of Dec 2021 and June 2023 took down huge swathes of the internet because so many systems are single-region in `us-east-1`.) Surviving a whole-region loss requires data in another region.

3. **Data residency / compliance.** Laws increasingly require that certain data *physically stays* within a jurisdiction: the EU's **GDPR** (General Data Protection Regulation), India's **DPDP Act**, China's **PIPL/CSL**, Russia's data-localization law, and sector rules (healthcare, finance). You may be *legally forbidden* from copying a German citizen's PII to a US server. Geo-partitioning is often the only architecture that satisfies this.

> **Beginner aside — PII:** *Personally Identifiable Information* — names, emails, government IDs, location, anything that can identify a real person. Most data-residency law targets PII specifically.

### 1.3 When you reach for it

- Your p99 latency is dominated by cross-ocean round-trips and you've already optimized everything else.
- You have a hard RTO/RPO requirement that a single region cannot meet (defined in §1.5).
- Legal/compliance mandates that data for region X must live in region X.
- You are serving a genuinely global user base where "follow-the-sun" traffic patterns mean different regions are hot at different hours.

**When you should NOT:** if you have one region's worth of users, no residency requirement, and your availability target is met by multi-AZ. Multi-region multiplies cost, latency-vs-consistency pain, and operational complexity by a large constant. It is one of the most expensive architectural commitments you can make. Do not adopt it for résumé reasons.

### 1.4 One-paragraph mental model

Think of your data as having a **home** (a region that owns the authoritative copy and accepts writes for it) and zero or more **copies** (replicas in other regions for reading or failover). Multi-region design is the art of choosing, for each piece of data, *where home is*, *who gets copies*, *how fresh those copies must be*, and *what happens when home becomes unreachable*. Every hard decision in this chapter reduces to a tension between two unmovable facts: (a) the network between regions is slow and unreliable, and (b) keeping copies in sync requires talking across that network. You cannot have both instant cross-region consistency and tolerance of cross-region partitions — that is the **CAP theorem** made physical.

### 1.5 Two metrics you must internalize early

- **RPO — Recovery Point Objective:** how much data (measured in *time*) you can afford to lose in a disaster. RPO = 0 means "lose nothing"; RPO = 5 minutes means "losing the last 5 minutes of writes is acceptable." Asynchronous replication implies RPO > 0.
- **RTO — Recovery Time Objective:** how long you can be down before recovery. RTO = 30 s means failover must complete within 30 seconds.

These two numbers drive almost every multi-region decision. Write them on the wall before you design anything.

---

## 2. Foundations from first principles

### 2.1 Replication: the atom of multi-region

**Replication** = keeping copies of the same data on multiple machines. There are two axes that matter enormously:

**Axis A — synchronous vs asynchronous.**
- **Synchronous replication:** the write is not acknowledged to the client until it is durably stored on the replica(s). Pro: no data loss if the primary dies (RPO ≈ 0). Con: the client pays the round-trip latency to the replica on *every write*. Across regions, that is +tens-to-hundreds of milliseconds per write.
- **Asynchronous replication:** the primary acknowledges the write immediately and ships it to replicas in the background. Pro: writes are fast (local latency). Con: if the primary dies before shipping, those writes are lost (RPO > 0), and replicas serve stale data (**replication lag**).

> **Beginner aside — replication lag:** the time gap between a write landing on the primary and that same write appearing on a replica. Across regions under async replication this is typically tens to hundreds of milliseconds but can spike to seconds or more under load or network stress.

**Axis B — single-leader vs multi-leader vs leaderless.**
- **Single-leader (a.k.a. primary/replica, master/slave):** one node accepts all writes; others are read-only replicas. Simple, no write conflicts, but the leader is a single write bottleneck and a single point of failure for writes.
- **Multi-leader (a.k.a. active-active):** multiple nodes (often one per region) accept writes. Great for local-write latency, but introduces **write conflicts** (two regions edit the same row concurrently) that must be resolved.
- **Leaderless (Dynamo-style):** any replica accepts reads and writes; consistency is tuned with **quorums** (defined below). DynamoDB, Cassandra, Riak.

### 2.2 Quorums (the leaderless tuning knob)

In a leaderless system with **N** replicas, you require **W** replicas to acknowledge a write and **R** replicas to answer a read. If **W + R > N**, every read overlaps at least one replica that saw the latest write — this gives **strong-ish consistency** for that key. Classic Dynamo defaults: N=3, W=2, R=2 (2+2 > 3). Tuning W and R trades latency against consistency. In multi-region, the placement of those N replicas across regions decides whether a quorum can be formed *locally* (fast) or must span the ocean (slow).

> **Beginner aside — quorum:** a majority (or any agreed threshold) of nodes that must agree before an action counts. "Quorum of 2 out of 3" means at least 2 of the 3 must acknowledge.

### 2.3 Consistency models (the vocabulary everyone misuses)

From strongest to weakest, the ones that matter here:

- **Linearizability (a.k.a. "strong consistency" / atomic consistency):** the system behaves as if there is a single copy of the data and every operation takes effect at a single instant between its start and end. Once a write completes, *every* subsequent read (from anywhere) sees it or something newer. This is the gold standard and the most expensive across regions.
- **Sequential / serializable:** about transaction ordering (for multi-object transactions). **Serializability** is the strongest *transaction isolation* level — the result is as if transactions ran one at a time. (Distinct from linearizability, which is about single-object recency. Spanner gives both and calls the combination **external consistency**.)
- **Causal consistency:** operations that are causally related (A happened-before B) are seen in that order everywhere; unrelated operations may be seen in different orders. Strong enough to avoid most "WTF" anomalies, cheap enough to survive partitions. Often the sweet spot.
- **Read-your-writes (a.k.a. read-after-write):** a client always sees its own prior writes (even if others see stale data). The minimum users usually expect.
- **Monotonic reads:** you never see time go backwards — once you've seen a value, you won't later see an older one.
- **Eventual consistency:** if writes stop, all replicas eventually converge to the same value. Says nothing about *when* or about ordering in the meantime.

> **Beginner aside — happened-before:** Leslie Lamport's notion. Event A "happened-before" B if A could have causally influenced B (same thread in sequence, or a message sent then received). Used to define causal order without synchronized clocks.

### 2.4 The CAP theorem, made concrete

**CAP** (Brewer): in the presence of a network **P**artition, a distributed system must choose between **C**onsistency (linearizable reads) and **A**vailability (every request gets a non-error response). You cannot have all three *during a partition*.

> **Beginner aside — network partition:** the network splits so that group of nodes A cannot talk to group B, though each group may be healthy internally. Across regions this happens regularly: the WAN link degrades or drops.

The crucial nuance for multi-region: partitions between regions are *common, not exotic*. So your choice is real and frequent:
- **CP systems** (Spanner, CockroachDB, etcd, ZooKeeper, HBase): during a partition, the minority side stops accepting writes (becomes unavailable) to preserve consistency.
- **AP systems** (DynamoDB default, Cassandra, Riak): keep serving on both sides, accept divergence, reconcile later.

**PACELC** extends CAP and is more useful for multi-region: *if Partition, choose A or C; **Else** (normal operation), choose **L**atency or **C**onsistency.* Spanner is **PC/EC** (consistent always, pays latency). DynamoDB global tables are **PA/EL** (available, low latency, eventual). This Else-clause is the day-to-day reality — even when the network is fine, strong consistency across regions costs latency.

### 2.5 Clocks and ordering across regions

To order events across regions you need a notion of time, and physical clocks on different machines disagree (**clock skew**). Three approaches:

- **Logical clocks (Lamport timestamps):** a counter per node, bumped on each event and on message receipt. Gives a total order consistent with happened-before but doesn't relate to wall-clock time.
- **Vector clocks:** a vector of counters, one per node. Lets you *detect* concurrent (conflicting) updates, not just order them. Used by Dynamo/Riak for conflict detection.
- **Hybrid Logical Clocks (HLC):** combine physical wall-clock time with a logical counter so timestamps are close to real time *and* respect causality. Used by CockroachDB and YugabyteDB.
- **TrueTime (Google Spanner):** a clock API backed by GPS + atomic clocks in every datacenter that returns a time *interval* `[earliest, latest]` with a bounded uncertainty ε (epsilon), historically ~1–7 ms. Spanner waits out this uncertainty to guarantee global ordering (see §3.5).

> **Beginner aside — clock skew:** two computers' clocks drift apart because crystal oscillators aren't perfect and NTP sync isn't instant. Skew of a few milliseconds to tens of milliseconds is normal; you must never assume two machines' clocks agree exactly.

### 2.6 Consensus (how a group of nodes agrees on one value)

**Consensus protocols** let a set of nodes agree on a sequence of values (a replicated log) even if some nodes fail. The two you must know:

- **Paxos / Multi-Paxos:** the original (Lamport). Notoriously hard to understand and implement. Used inside Spanner and Chubby.
- **Raft:** designed for understandability. A **leader** is elected; it appends entries to a log and replicates them to **followers**; an entry is **committed** once a majority (quorum) has it. If the leader dies, a new election picks a follower with the most up-to-date log. CockroachDB, etcd, TiKV, YugabyteDB all use Raft.

> **Beginner aside — replicated log / state machine replication:** if every replica applies the *same operations in the same order*, they end in the same state. Consensus's job is to agree on that order. The ordered list of operations is the "replicated log."

Why this matters for multi-region: a Raft/Paxos **commit requires a quorum**, and if your replicas span regions, that quorum spans the WAN. Placement of replicas across regions directly sets your write latency floor (see §3.4 and §7.2).

### 2.7 Sharding vs partitioning vs geo-partitioning

- **Partition / shard:** a horizontal slice of a table (a subset of rows), so the whole dataset doesn't have to live on one machine. Partitioned by **hash** (even spread, no range scans) or by **range** (ordered, supports range scans, risks hotspots).
- **Geo-partitioning:** partitioning where the partition's *physical location* is chosen by a locality attribute (the user's region/country). The partition key effectively encodes "where on Earth this data lives."

The mechanism you'll use in modern SQL databases is a **`REGIONAL BY ROW`** table (CockroachDB) or a **partitioned table with a leader-region constraint** (Spanner / YugabyteDB tablespaces). Conceptually: add a hidden or explicit `region`/`crdb_region` column, partition by it, and pin each partition's leader replica to that region's nodes.

---

## 3. How it works internally

This is the heart of the chapter. We'll trace the actual control and data flow for the major patterns, then dig into how three production systems implement them.

### 3.1 Topology vocabulary

- **Active-passive (a.k.a. primary-DR, primary-standby):** one region is *active* (takes all reads+writes); one or more are *passive standbys* receiving replication, doing nothing user-facing until a failover promotes one. Simple, no conflicts, but the standby's capacity is idle and failover is a discrete, risky event.
- **Active-active:** two or more regions take live traffic simultaneously. Either (a) each region owns a disjoint set of data (geo-partitioned active-active — no conflicts, the clean version) or (b) all regions can write all data (true multi-leader — conflicts possible, the hard version).

### 3.2 Internal workflow: single-leader async cross-region replication (the classic DR setup)

This is what you get with vanilla PostgreSQL/MySQL streaming replication or RDS cross-region read replicas. Step by step:

1. **Write arrives** at the leader in region A. The leader appends it to its **write-ahead log (WAL)** — an on-disk, append-only record of every change written *before* the change touches the data pages, so a crash can be replayed.
2. **Local commit.** Once the WAL record is `fsync`'d to local disk (and, intra-region, to a synchronous standby if configured), the leader acknowledges the client. The client sees low (intra-region) latency.

   > **Beginner aside — `fsync`:** a syscall that forces the OS to flush buffered file data from RAM to physical disk so it survives a power loss. Durability hinges on it.
3. **Async ship to region B.** A background **WAL sender** process streams WAL records over the WAN to a **WAL receiver** on the standby in region B.
4. **Apply on standby.** Region B replays the WAL into its own data pages. The standby is now behind by the **replication lag** (network RTT + apply time + any backlog).
5. **Reads in region B** hit the standby and may be stale by the lag amount. Good for read-local of non-critical data; dangerous if a user expects read-your-writes after a write that went to region A.
6. **Failover (region A dies):** an operator or automated controller **promotes** the standby in B to leader. Any WAL not yet shipped at the moment of death is **lost** — that's your RPO. DNS/connection strings/global load balancer must now point writes at B. This switch is the RTO.

**Failure subtlety — split brain:** if A didn't actually die but was just partitioned, and you promote B, you now have two leaders. When the partition heals, they have divergent histories. Preventing this requires **fencing** (STONITH — "shoot the other node in the head," i.e., forcibly isolate the old leader) or a consensus-based controller that ensures only one leader at a time.

> **Beginner aside — split brain:** two nodes both believing they are the sole leader, each accepting writes, producing two conflicting versions of reality that must later be reconciled (often by hand).

### 3.3 Internal workflow: multi-leader active-active replication

Each region has a leader that accepts local writes and replicates to the other regions' leaders. Step by step:

1. Region A leader accepts write `X.balance += 10`, applies locally, acks client (fast, local).
2. Region B *simultaneously* accepts `X.balance += 5`, applies locally, acks client.
3. Each ships its change to the other asynchronously.
4. **Conflict:** both regions now have a different value for `X`, and neither saw the other's write before its own. The system must **detect** and **resolve** this (see §3.6).
5. After resolution, both converge to one agreed value — but possibly one of the two intended updates is *silently lost* unless the merge is semantically aware (e.g., it knows "+=10 and +=5 → +=15").

Multi-leader is the only topology that gives every region fast local writes for *the same data*, but it buys that with conflict resolution complexity. This is why **geo-partitioned active-active** (each region owns disjoint data, §3.7) is preferred when possible — it sidesteps conflicts entirely for the common case.

### 3.4 Internal workflow: consensus-replicated CP database (CockroachDB-style)

Modern "NewSQL" databases (CockroachDB, YugabyteDB, TiDB, Spanner) don't pick one leader per *region*; they pick one Raft/Paxos leader per **range/tablet** (a small contiguous slice of the key space, ~64–512 MB), and they place that range's replicas across regions/AZs per a placement policy. Trace a write:

1. **Routing.** The SQL gateway node receiving the query finds which **range** owns the target key (via a distributed range metadata index) and which node is that range's **leaseholder** (the replica authorized to serve reads and coordinate writes — an optimization on top of the Raft leader).
2. **Propose.** The leaseholder proposes the write to the range's Raft group.
3. **Quorum commit.** A *majority* of the range's replicas must durably append the entry. If the range has 3 replicas in 3 regions, the leader needs **1 other region** to ack → write latency = RTT to the *nearest* other region. If replicas are 3-in-1-region (+ async copies elsewhere), commit is local and fast but you can lose the region.
4. **Apply & ack.** Once committed, all replicas apply it; the client is acked.
5. **Reads.** The leaseholder can serve linearizable reads locally *if* its lease is valid (no quorum round-trip needed for reads — a key optimization). Follower replicas can serve **stale/bounded-staleness** reads locally for read-local patterns.

The single most important internal lever here is **where the range's leaseholder/leader lives and where the quorum-forming replicas live.** That's exactly what geo-partitioning controls. `REGIONAL BY ROW` makes the leaseholder follow the data's home region, so local writes commit against a *local* quorum (across that region's AZs, ~2 ms) instead of a cross-ocean quorum.

### 3.5 Deep dive: how Google Spanner does globally-consistent multi-region

Spanner is the canonical CP/EC system. Its trick is **TrueTime** plus Paxos.

1. **Data is split into splits (ranges)**, each replicated by a **Paxos group** across replicas in multiple zones/regions per the instance's replication config.
2. Every transaction gets a **commit timestamp** from TrueTime. TrueTime returns `[earliest, latest]` with uncertainty ε.
3. **Commit-wait:** to guarantee that the commit timestamp is in the past for *every* observer everywhere, the coordinator picks a timestamp `s` and then *deliberately waits until TrueTime says `s` is definitely past* (until `now.earliest > s`) before releasing locks and acking. This wait is roughly **2ε** (historically a handful of milliseconds). This is the price of **external consistency** (a global real-time order): if transaction T2 starts after T1 commits, T2's timestamp is guaranteed greater.

   > **Beginner aside — external consistency:** the strongest guarantee — the order the database assigns to transactions matches the real-world wall-clock order in which they happened, globally. Spanner is the famous example.
4. **Multi-region writes** that span Paxos groups use **two-phase commit (2PC)** layered over Paxos, with one group acting as coordinator. A cross-region write therefore costs WAN round-trips for Paxos quorum *and* the commit-wait — which is why Spanner write latency in a multi-region config is tens of milliseconds, not single digits.
5. **Leader placement:** Spanner lets you configure **leader regions**; the Paxos leader (which coordinates writes) lives there, so writes are fastest for clients near the leader region. Reads can be served from any replica at a chosen timestamp (strong, bounded-staleness, or exact-staleness).

The lesson: Spanner buys global linearizability by *spending latency* (commit-wait + quorum RTT), and it shrinks clock uncertainty with expensive hardware so that "latency" stays small. It never gives up consistency, even during partitions — the minority side just can't make progress.

### 3.6 Deep dive: how DynamoDB Global Tables do multi-region

DynamoDB Global Tables are the canonical AP/EL system.

1. **Multi-active (multi-leader):** every replica region accepts both reads and writes locally — local single-digit-ms latency everywhere. There is no single write region.
2. **Async replication:** a write in region A is applied locally and acked immediately, then propagated to other regions, typically within ~1 second (not guaranteed; SLA is "eventually," p99 often sub-second).
3. **Conflict resolution = Last-Writer-Wins (LWW) by timestamp.** If the same item is written concurrently in two regions, the write with the highest timestamp wins; the other is silently discarded. There is no merge, no vector clock surfaced to you. (DynamoDB uses an internal wall-clock-based comparison.)
4. **Consequence:** Global Tables give you availability and low latency everywhere, but **no cross-region atomicity and no conflict merging** — if your app does read-modify-write on the same key from two regions, you can lose updates. You design around it: partition writes by region (geo-partition), or make operations idempotent/commutative, or route all writes for a key to one region.

> **Beginner aside — Last-Writer-Wins (LWW):** a conflict-resolution rule where, of two conflicting versions, the one with the later timestamp is kept and the earlier is thrown away. Simple and convergent, but *loses data* by design when both writes were intended.

### 3.7 Internal workflow: geo-partitioned (home-region) active-active — the clean pattern

This is the architecture most production multi-region systems actually want. Each row has a **home region**; that region owns it (leaseholder/leader lives there); other regions hold replicas for failover and possibly local stale reads.

1. **Tag data with locality.** Add a `region` column (or derive it from a country/`tenant` field). E.g., user `alice@de` → `region = 'eu-central'`.
2. **Partition by region.** The table is partitioned on `region`; partition `eu-central` is pinned so its leaseholders live on EU nodes.
3. **Write path:** a write to Alice's row is routed to the EU leaseholder, commits against an EU-local quorum (across EU AZs, ~2 ms), and is acked fast. **No cross-region conflict** because no other region is a leader for Alice's row.
4. **Local read path:** EU users read Alice from EU (fast, strong). A US user reading Alice either pays a cross-region read (slow but strong) or reads a US follower replica with bounded staleness (fast, slightly stale).
5. **Compliance bonus:** because Alice's home region is EU and you can constrain replicas to EU-only regions, her PII never leaves the EU → GDPR satisfied at the storage layer.
6. **Failover:** if EU goes down, the surviving replicas in other *EU* zones/regions elect a new leaseholder; if you've allowed only-EU placement for residency, you need ≥3 EU AZs/regions for quorum survival.

This is exactly what CockroachDB's `REGIONAL BY ROW`, Spanner's leader-placement + partitioning, and YugabyteDB's tablespaces implement.

### 3.8 State machine: the lifecycle of a multi-region cluster under failure

```
        ┌─────────────┐  region link degrades   ┌──────────────────┐
        │  HEALTHY     │ ───────────────────────▶│ PARTITIONED       │
        │ all regions  │                          │ (CP: minority RO; │
        │ read+write   │◀─────────────────────────│  AP: both write,  │
        └─────────────┘   link heals + reconcile  │  diverging)       │
              │                                    └──────────────────┘
              │ region A hard-fails                          │
              ▼                                               │ heal
        ┌─────────────┐  promote/elect new leader   ┌─────────▼────────┐
        │ DEGRADED     │ ──────────────────────────▶│ RECONCILING       │
        │ (A down)     │                             │ (apply backlog,   │
        └─────────────┘                             │  resolve conflicts)│
              │ A returns                            └──────────────────┘
              ▼                                               │
        ┌─────────────┐                                       │ done
        │ REJOIN/      │◀──────────────────────────────────────┘
        │ CATCH-UP     │  A streams missed log, rejoins quorum
        └─────────────┘
              │ caught up
              ▼  back to HEALTHY
```

Key transitions and what governs them:
- **HEALTHY → PARTITIONED:** WAN link loss. CP systems make the minority unavailable for writes; AP systems keep writing on both sides (divergence begins).
- **PARTITIONED → RECONCILING (heal):** reconnect; exchange logs; resolve conflicts (LWW / CRDT merge / manual).
- **HEALTHY → DEGRADED:** hard region loss. Triggers election/promotion. RPO = unshipped writes; RTO = detection + election + traffic switch.
- **DEGRADED → REJOIN:** the dead region returns, must catch up its log before re-entering the quorum, and may need to *roll back* writes it accepted before death that the new leader never saw.

---

## 4. The complete toolkit

### 4.1 Concepts & knobs (cross-vendor)

| Knob / concept | What it controls | Typical values / defaults | Notes |
|---|---|---|---|
| Replication mode | sync vs async | vendor-specific | sync ⇒ RPO 0, high write latency cross-region; async ⇒ RPO>0, low latency |
| Replication factor (N) | number of copies | 3 (most CP systems), 3 per region | quorum needs majority of N |
| Write quorum (W) / Read quorum (R) | leaderless consistency | Dynamo classic N3/W2/R2 | W+R>N ⇒ overlap |
| Survivability goal | what failure to survive | "zone failure" / "region failure" | region survival needs ≥3 regions |
| Locality / home region | which region owns a row | per-row or per-partition | basis of geo-partitioning |
| Leader/leaseholder placement | where writes coordinate | follow data home | sets write-latency floor |
| Staleness bound | how old a follower read may be | e.g. 4.8 s (CRDB default follower-read), tunable | trades freshness for local reads |
| Consistency level (per query) | strong / bounded / eventual | per-statement in CRDB/Spanner/Cassandra | |
| Conflict resolution | LWW / CRDT / app-merge / single-writer | vendor & schema dependent | |

### 4.2 CockroachDB (Java-relevant; speaks the PostgreSQL wire protocol)

| Command / feature | Purpose | Key params / defaults |
|---|---|---|
| `ALTER DATABASE db SET PRIMARY REGION 'us-east1'` | declare a region as primary | first region added |
| `ALTER DATABASE db ADD REGION 'europe-west1'` | register a region for placement | — |
| `CREATE TABLE ... LOCALITY REGIONAL BY ROW` | per-row home region | adds hidden `crdb_region` column |
| `CREATE TABLE ... LOCALITY REGIONAL BY TABLE IN 'eu'` | whole table homed in one region | fast writes for that region |
| `CREATE TABLE ... LOCALITY GLOBAL` | read-everywhere, write-rare (e.g. reference data) | fast local strong reads everywhere, slower writes |
| `SET enable_durable_locking_for_serializable=on` etc. | isolation/locking tuning | — |
| `SELECT ... AS OF SYSTEM TIME follower_read_timestamp()` | bounded-staleness **follower read** (local, fast) | default staleness ~4.8 s |
| `SURVIVE REGION FAILURE` / `SURVIVE ZONE FAILURE` | survivability goal per DB | zone is default; region needs ≥3 regions |
| `SHOW RANGES`, `SHOW REGIONS` | inspect placement | observability |

> **Beginner aside — follower read:** reading from a non-leader replica at a slightly-in-the-past timestamp so the replica is guaranteed to already have that data, avoiding a cross-region hop to the leader. Trades a few seconds of freshness for local-latency reads.

### 4.3 Google Cloud Spanner

| Feature | Purpose | Notes / defaults |
|---|---|---|
| Instance config (e.g. `nam3`, `eur6`, `nam-eur-asia1`) | choose multi-region replication topology | each defines leader region + read replicas + witnesses |
| Leader region | where Paxos leaders live (write latency anchor) | part of the chosen config |
| Read-only replicas | serve reads in extra regions | no Paxos vote |
| Witness replicas | vote in Paxos for quorum but store no data | cheaper quorum member |
| `staleness` read options: strong / bounded / exact | per-read consistency vs latency | strong = default |
| TrueTime / commit-wait | global ordering | ε historically ~1–7 ms; cost ≈ 2ε per commit |
| Interleaved tables / partitioning | colocate child rows with parent; geo-partition | basis for locality |

### 4.4 Amazon DynamoDB Global Tables

| Feature | Purpose | Notes / defaults |
|---|---|---|
| Global Table (v2, 2019.11.21) | multi-active multi-region table | add/remove replica regions |
| Conflict resolution | Last-Writer-Wins by timestamp | not configurable; design around it |
| DynamoDB Streams | change feed powering replication & CDC | required for global tables |
| Replication latency | typically ~1 s p99 (not SLA-guaranteed) | "eventually consistent" cross-region |
| Strongly-consistent reads | only *within* the region you write to | cross-region reads are eventual |
| Global Secondary Index (GSI) | query by non-key attribute | replicated per region |

### 4.5 PostgreSQL / MySQL (DIY multi-region)

| Tool | Purpose | Notes |
|---|---|---|
| PostgreSQL streaming replication / `synchronous_standby_names` | sync or async standbys | cross-region sync = slow writes |
| Logical replication / `pglogical` | row-level, selective, cross-version | enables multi-leader (with BDR) |
| `pg_failover_slots`, Patroni, repmgr | automated failover + leader election | Patroni uses etcd/Consul/ZK for consensus |
| MySQL Group Replication / InnoDB Cluster | multi-primary or single-primary w/ consensus | Group Replication uses a Paxos variant |
| Vitess | sharding + cross-cell (region) topology for MySQL | used at YouTube scale |

> **Beginner aside — Patroni & etcd/Consul/ZooKeeper:** Patroni is a controller that manages PostgreSQL failover. It stores "who is leader" in a strongly-consistent key-value store (etcd, Consul, or **ZooKeeper** — distributed coordination services that use consensus to agree on small bits of critical metadata). This external consensus prevents split-brain.

### 4.6 Apache Cassandra / ScyllaDB (leaderless, multi-DC)

| Feature | Purpose | Notes |
|---|---|---|
| `NetworkTopologyStrategy` with per-DC replication factor | place replicas per region/DC | e.g. `{us: 3, eu: 3}` |
| Consistency levels: `LOCAL_QUORUM`, `EACH_QUORUM`, `QUORUM`, `ONE`, `ALL` | per-query consistency vs latency | `LOCAL_QUORUM` = quorum within local DC only (fast, common) |
| `LOCAL_QUORUM` | majority within local DC, no cross-region wait | the workhorse for multi-region Cassandra |
| `EACH_QUORUM` | quorum in *every* DC (strong, slow) | for critical writes |
| Hinted handoff / read repair / anti-entropy (Merkle trees) | converge replicas | background reconciliation |
| LWW + per-cell timestamps | conflict resolution | column-level LWW |

---

## 5. Code examples by use case

The examples are Java-first (the reader's ecosystem) plus SQL/CLI where the database is the actor.

### 5.1 Geo-partitioned table in CockroachDB (`REGIONAL BY ROW`)

```sql
-- 1. Declare the database's regions. The first becomes PRIMARY.
ALTER DATABASE shop PRIMARY REGION "us-east1";
ALTER DATABASE shop ADD REGION "europe-west1";
ALTER DATABASE shop ADD REGION "asia-southeast1";

-- 2. Survive an entire region failure (requires >= 3 regions).
ALTER DATABASE shop SURVIVE REGION FAILURE;

-- 3. A users table homed per-row. The hidden crdb_region column
--    decides where each user's leaseholder (write coordinator) lives.
CREATE TABLE users (
    id        UUID NOT NULL DEFAULT gen_random_uuid(),
    email     STRING NOT NULL,
    country   STRING NOT NULL,
    profile   JSONB,
    -- compute the home region from country so EU users live in EU, etc.
    crdb_region crdb_internal_region AS (
        CASE
            WHEN country IN ('DE','FR','IT','ES') THEN 'europe-west1'
            WHEN country IN ('SG','JP','IN')       THEN 'asia-southeast1'
            ELSE 'us-east1'
        END
    ) STORED,
    PRIMARY KEY (crdb_region, id)         -- region is the leading PK column
) LOCALITY REGIONAL BY ROW;               -- per-row homing -> local writes
```

What matters: an EU user's write now commits against an EU-local quorum (across EU AZs, ~2 ms) instead of a transatlantic quorum (~80–160 ms). The `STORED` computed `crdb_region` enforces residency at the schema level.

Java side (CockroachDB speaks PostgreSQL wire protocol, so use plain JDBC):

```java
// Standard PostgreSQL JDBC against CockroachDB. No special driver needed.
String url = "jdbc:postgresql://gateway.eu.shop.example:26257/shop?sslmode=verify-full";
try (Connection c = DriverManager.getConnection(url, props)) {
    c.setAutoCommit(false);
    // Insert: crdb_region is computed, so we don't set it. The row is
    // homed by country, and the write goes to the local EU quorum.
    try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO users (email, country, profile) VALUES (?, ?, ?::jsonb)")) {
        ps.setString(1, "alice@example.de");
        ps.setString(2, "DE");
        ps.setString(3, "{\"tier\":\"gold\"}");
        ps.executeUpdate();
    }
    c.commit();
    // IMPORTANT: connect to a gateway in the region nearest the user so
    // that local reads/writes don't take an extra cross-region hop to the
    // gateway itself. Use a geo-aware load balancer or per-region URL.
}
```

### 5.2 Fast local stale read (follower read) for a read-heavy dashboard

```java
// A US analytics dashboard reading EU-homed rows. We don't need
// up-to-the-millisecond freshness, so we use a follower read served
// from a LOCAL replica instead of hopping to the EU leaseholder.
String sql =
    "SELECT id, email, profile " +
    "FROM users AS OF SYSTEM TIME follower_read_timestamp() " +  // ~4.8s stale, but LOCAL & fast
    "WHERE country = 'DE' LIMIT 100";
try (Connection c = DriverManager.getConnection(usGatewayUrl, props);
     Statement st = c.createStatement();
     ResultSet rs = st.executeQuery(sql)) {
    while (rs.next()) { /* ... */ }
}
// Tradeoff: this read may be up to ~4.8s behind the EU leaseholder, but it
// never crosses the ocean. Never use follower reads for read-your-writes.
```

### 5.3 DynamoDB Global Table with region-pinned writes to avoid LWW data loss (Java SDK v2)

```java
// Global Tables resolve conflicts with Last-Writer-Wins, which silently
// drops one of two concurrent writes to the same item. To avoid losing
// updates, we (a) write each user's data only in that user's HOME region,
// and (b) make balance changes idempotent + use conditional writes.

DynamoDbClient eu = DynamoDbClient.builder()
        .region(Region.EU_CENTRAL_1)   // route by user's home region
        .build();

// Idempotent, conditional update: only apply if this requestId hasn't been
// seen, preventing double-apply during retries/replication races.
UpdateItemRequest req = UpdateItemRequest.builder()
    .tableName("wallets")
    .key(Map.of("userId", AttributeValue.fromS("alice@de")))
    .updateExpression("SET balance = balance + :amt, lastReq = :rid")
    .conditionExpression("attribute_not_exists(lastReq) OR lastReq <> :rid")
    .expressionAttributeValues(Map.of(
        ":amt", AttributeValue.fromN("10"),
        ":rid", AttributeValue.fromS("req-7f3a")))   // unique per logical op
    .build();
try {
    eu.updateItem(req);
} catch (ConditionalCheckFailedException dup) {
    // Replay of the same logical write; safe to ignore (idempotency).
}
// Reads within eu-central-1 can be strongly consistent; cross-region reads
// from us-east-1 would be eventually consistent (replication lag ~1s).
```

### 5.4 Cassandra multi-DC with `LOCAL_QUORUM` (Java driver 4.x)

```java
// Keyspace with replicas in two regions; writes/reads use LOCAL_QUORUM so
// they only wait for a quorum within the LOCAL datacenter (no WAN wait),
// while still being durable to a majority locally and replicated to EU.
//
// CREATE KEYSPACE app WITH replication =
//   {'class':'NetworkTopologyStrategy','us-east':3,'eu-central':3};

CqlSession session = CqlSession.builder()
    .withLocalDatacenter("us-east")                 // pins driver to local DC
    .withConfigLoader(DriverConfigLoader.programmaticBuilder()
        .withString(DefaultDriverOption.REQUEST_CONSISTENCY, "LOCAL_QUORUM")
        .build())
    .build();

PreparedStatement put = session.prepare(
    "INSERT INTO app.orders (id, payload, ts) VALUES (?, ?, ?)");
session.execute(put.bind(orderId, payload, Instant.now())
    .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM));
// Write acks after 2/3 local replicas ack (~2ms). Async replication carries
// it to eu-central. Cross-DC consistency is eventual; use EACH_QUORUM only
// for the rare write that must be durable in BOTH regions before ack.
```

### 5.5 Read-your-writes routing in an application layer (region-aware session pinning)

```java
// Pattern: route a user's requests to their HOME region so read-your-writes
// holds, and fall back to another region only on failure. Sticky by user.

enum Region { US_EAST, EU_CENTRAL, ASIA_SE }

class RegionRouter {
    Region homeRegionFor(String country) {
        return switch (country) {
            case "DE","FR","IT","ES" -> Region.EU_CENTRAL;
            case "SG","JP","IN"      -> Region.ASIA_SE;
            default                   -> Region.US_EAST;
        };
    }

    // Connection pool per region. We always serve a user's read+write from
    // their home so they read their own writes with strong consistency.
    DataSource dsFor(User u) {
        Region home = homeRegionFor(u.country());
        DataSource ds = pools.get(home);
        if (!healthChecker.isHealthy(home)) {
            // Failover: pick the next-nearest HEALTHY region in the same
            // residency zone (e.g., another EU region for an EU user) to
            // avoid violating GDPR by spilling into the US.
            ds = pools.get(failoverWithinResidency(home));
        }
        return ds;
    }
}
```

### 5.6 Active-passive failover with a consensus-backed controller (Patroni + PostgreSQL, config)

```yaml
# patroni.yml (per node). Patroni stores leader state in etcd (consensus),
# preventing split-brain: only the node holding the etcd leader key is primary.
scope: shop-cluster
namespace: /db/
restapi:
  listen: 0.0.0.0:8008
etcd3:
  hosts: etcd-1:2379,etcd-2:2379,etcd-3:2379   # 3 nodes -> survives 1 loss
bootstrap:
  dcs:
    ttl: 30                 # leader key TTL; if not renewed, election starts
    loop_wait: 10
    retry_timeout: 10
    maximum_lag_on_failover: 1048576   # bytes; refuse to promote a standby
                                       # too far behind (caps RPO)
    synchronous_mode: true             # primary waits for >=1 sync standby
                                       # -> RPO 0 for committed writes
postgresql:
  parameters:
    synchronous_commit: "on"
    wal_level: replica
```

The `synchronous_mode: true` + a sync standby gives RPO 0 within the sync set; `maximum_lag_on_failover` caps how stale a promoted standby may be (bounding data loss if you fail over to an async standby). The etcd cluster is the consensus oracle that ensures exactly one primary.

### 5.7 Spanner: choosing a multi-region config and a bounded-staleness read (Java)

```java
// Spanner instance created with config "nam-eur-asia1" (a 3-continent
// config) or "eur6" (EU-only, for residency). The config decides leader
// region + read replicas. Strong reads always see latest but pay quorum +
// commit-wait; bounded-staleness reads serve locally and fast.

try (Spanner spanner = SpannerOptions.newBuilder().build().getService()) {
    DatabaseClient client = spanner.getDatabaseClient(
        DatabaseId.of("my-proj", "eu-instance", "shop"));

    // Bounded-staleness read: "anything no older than 10 seconds" -> can be
    // served by a nearby replica without contacting the leader.
    try (ResultSet rs = client
            .singleUse(TimestampBound.ofMaxStaleness(10, TimeUnit.SECONDS))
            .executeQuery(Statement.of("SELECT id, email FROM Users LIMIT 50"))) {
        while (rs.next()) { /* ... */ }
    }

    // Strong read-write txn: globally externally-consistent, pays the price.
    client.readWriteTransaction().run(txn -> {
        txn.buffer(Mutation.newUpdateBuilder("Users")
            .set("id").to("alice")
            .set("tier").to("gold").build());
        return null;
    });
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **The latency floor is physics.** A cross-region quorum write cannot be faster than the one-way light-speed RTT to the nearest quorum member. Memorize approximate RTTs: same-region ~1–2 ms; US-coast-to-coast ~60–70 ms; transatlantic ~80–90 ms; trans-Pacific ~120–160 ms. *Design so the common path stays local.*
- **Geo-partition to keep quorums local.** The whole point of `REGIONAL BY ROW` / leader placement is that 95% of writes touch local-home data and never cross the ocean.
- **Use follower / bounded-staleness reads** for anything that tolerates a few seconds of lag; reserve strong cross-region reads for the cases that truly need them.
- **Chatty request patterns are the silent killer.** N sequential cross-region queries = N×RTT. Batch, denormalize, or co-locate to collapse round-trips. One cross-region call is fine; fifty in a loop is a disaster.
- **Connection placement:** connect app servers to a *local* DB gateway. A US app server talking to an EU gateway adds a needless ocean hop before any DB work.

### 6.2 Correctness & concurrency

- **Decide your consistency model per data class, not globally.** Money and inventory may need strong/serializable; user "last seen at" can be eventual.
- **Beware read-your-writes violations** when reads come from stale followers/replicas after a write that went to a different region. Pin a user to their home region, or route their post-write reads to the leader.
- **In multi-leader/AP, assume conflicts will happen** and pick a deliberate resolution: single-writer-per-key (best), commutative/idempotent operations (good), CRDTs (good for specific types), or LWW (lossy — only when loss is acceptable).
- **Two-phase commit across regions is expensive and can stall** if the coordinator or a participant is partitioned. Prefer designs where a transaction touches one region's data only.

> **Beginner aside — CRDT (Conflict-free Replicated Data Type):** a data structure (counters, sets, registers, maps) mathematically designed so that concurrent updates from different regions *merge automatically* into the same correct result regardless of order — no coordination needed. Used by Riak, Redis Enterprise CRDTs, Azure Cosmos DB (some modes), and collaborative editors. The catch: only specific data shapes have CRDT formulations.

### 6.3 Security

- **Encrypt cross-region traffic** (TLS/mTLS) — it traverses the public-ish internet or shared backbones.
- **Encrypt at rest per region** with region-scoped keys (KMS) so a compromised region can't decrypt another's data, and residency keys never leave their jurisdiction.
- **Scope IAM/credentials per region**; blast radius of a leaked credential should be one region.
- **Residency is a security/compliance control too:** ensure replicas, backups, *and logs* (which often contain PII) honor the same residency rules. Logs are the most common residency leak.

### 6.4 Cost

- Multi-region roughly multiplies storage and compute by the number of regions, **plus** cross-region data-transfer (egress) fees, which are nontrivial (cloud egress is often the surprise line item). Replication is continuous egress.
- Spanner multi-region and DynamoDB Global Tables cost materially more than single-region; Global Tables bill replicated write capacity in every region.
- **Witness/quorum-only replicas** (Spanner witnesses, CRDB non-voting replicas) reduce cost by participating in quorum without storing full data.

### 6.5 Observability

- **Track replication lag per region pair** as a first-class SLI; alert when it exceeds your RPO budget.
- **Track per-region write/read latency and quorum composition** (which regions are forming quorums). A sudden latency jump often means a leaseholder moved or a region dropped from quorum.
- **Track conflict/LWW-loss rates** in AP systems (instrument it; the database won't tell you data was silently dropped).
- **Detect partitions explicitly** (region-to-region heartbeats), and surface failover events with timestamps to compute actual RTO/RPO post-incident.
- Tools: vendor consoles (Spanner/Dynamo CloudWatch, CRDB DB Console `SHOW RANGES`/jobs), Prometheus exporters, distributed tracing with region tags on spans.

### 6.6 Testing

- **Game-day / chaos drills:** regularly *kill a region* in staging (and ideally prod, carefully) to validate RTO/RPO and that residency failover stays within jurisdiction.
- **Inject WAN latency and partitions** (`tc netem`, toxiproxy) to test how the app behaves under lag and split.
- **Test the reconciliation path**, not just the happy failover — induce divergence, heal, and verify the merge result is what you intend.
- **Verify residency in tests:** assert that EU-homed rows never have replicas/backups outside allowed regions.

> **Beginner aside — `tc netem` / toxiproxy:** Linux `tc` (traffic control) with the `netem` module can add artificial delay, jitter, and packet loss to a network interface; toxiproxy is a TCP proxy that injects faults. Both let you simulate slow/broken WAN links in tests.

### 6.7 Production hardening

- **Quorum survivability math:** to survive a *region* loss you need the quorum to remain formable without that region — practically ≥3 regions (or 3 AZs if only surviving zone failure). Two regions cannot maintain a majority if one dies (1 of 2 is not a majority).
- **Bound failover RPO** (`maximum_lag_on_failover`, sync standbys) so you never promote a wildly stale replica.
- **Fencing/STONITH** to prevent split-brain in DIY setups; rely on a consensus controller (Patroni+etcd, or the DB's own Raft).
- **Backups per residency zone**, and test restores cross-region.
- **Capacity for failover:** the surviving region must absorb the failed region's traffic; don't run every region at 90% or failover will overload the survivor.

### 6.8 Anti-patterns to avoid

- **Synchronous cross-region replication on the hot write path** "for safety" — you've turned every write into an ocean round-trip.
- **Two regions and calling it HA** — you can't form a majority after losing one; you'll get split-brain or unavailability.
- **Multi-leader writes to the same key from multiple regions without a conflict strategy** — silent data loss via LWW.
- **Ignoring read-your-writes** — users write in region A, immediately read a stale replica, "lose" their change, and rage.
- **Letting logs/backups/analytics pipelines violate residency** while the primary table is compliant.
- **Treating multi-region as a switch you flip** rather than a property of each table/data class.
- **Chatty cross-region ORMs** — lazy-loading N+1 across the ocean.

---

## 7. Advanced topics & deep internals

### 7.1 Leaseholder vs Raft leader (CockroachDB nuance)

The **Raft leader** coordinates the log; the **leaseholder** is the single replica allowed to serve reads and sequence writes for a range without a quorum round-trip on reads. CRDB co-locates them when possible. Geo-partitioning works by moving the *leaseholder* to the data's home region; a misplaced leaseholder (e.g., after a node failure causing a lease transfer to a far region) silently doubles write latency until rebalanced. Watch `SHOW RANGES` for leaseholder drift.

### 7.2 Where the quorum forms = your write-latency SLA

For an N=3 range:
- **3 replicas in 3 regions:** survives a region loss, but every write waits for the *2nd-nearest* region (the median RTT). Strong, durable, but slow writes.
- **3 replicas in 1 region (3 AZs) + non-voting copies elsewhere:** writes commit on local AZ quorum (~2 ms), but losing that region loses write availability for those ranges (the non-voters can be promoted, but it's a recovery event, not seamless).
- **5 replicas across 3 regions (2+2+1):** survives a region loss while keeping quorum closer; more storage cost.

This is the central tuning tradeoff: **survivability vs write latency vs cost.** Geo-partitioning lets you choose differently *per table*: keep `eu_users` as `REGIONAL BY ROW SURVIVE ZONE FAILURE` (fast EU writes, survives an AZ) but make `global_config` a `GLOBAL` table (fast strong reads everywhere, rare slow writes).

### 7.3 Commit-wait and the value of tight clocks (Spanner)

Spanner's commit-wait ≈ 2ε. With ε ≈ 4 ms, that's ~8 ms added to *every* commit, on top of Paxos quorum RTT. Google invests in GPS/atomic clocks precisely to keep ε small; on commodity hardware (no special clocks), ε would be tens to hundreds of ms (NTP-level), making commit-wait prohibitive — which is *why* CockroachDB/YugabyteDB use HLC + uncertainty intervals + read restarts instead of relying on tight bounded clocks. CRDB's `max_offset` (default 500 ms) is the assumed clock-skew bound; exceed it and a node *self-terminates* to avoid consistency violations.

### 7.4 Uncertainty restarts (HLC systems)

Without TrueTime's tight bound, CRDB/YugabyteDB use a **maximum clock offset** and, when a read encounters a value whose timestamp falls within the uncertainty window, it performs a **read restart** at a higher timestamp to ensure linearizability. Under high contention across regions, restart rates can spike and tank throughput — a lesser-known failure mode you diagnose via restart metrics.

### 7.5 Conflict resolution spectrum (deep)

| Strategy | Convergence | Data loss | When to use |
|---|---|---|---|
| Single-writer per key (geo-partition / home region) | trivial (no conflict) | none | the default goal; route writes to one owner |
| Idempotent + commutative ops (e.g., set-if-absent, increment via CRDT counter) | yes | none | counters, append-only, dedup by request id |
| CRDTs (G-Counter, PN-Counter, OR-Set, LWW-Register, RGA) | yes (by math) | none for the type's semantics | collaborative editing, presence, carts, counters |
| Application-defined merge (custom 3-way) | yes (if total) | none if merge is correct | rich domain objects where you know the semantics |
| Last-Writer-Wins by timestamp | yes | **yes, silent** | only when losing one concurrent write is acceptable |
| Manual / operator reconciliation | depends | possible | rare, high-value conflicts; surface to humans |

> **Beginner aside — G-Counter / PN-Counter / OR-Set:** specific CRDT designs. A **G-Counter** is a grow-only counter (each replica increments its own slot; merge = element-wise max, total = sum). A **PN-Counter** supports decrements (two G-Counters: plus and minus). An **OR-Set** (Observed-Remove Set) lets concurrent add/remove of the same element resolve sanely by tagging each add with a unique id.

### 7.6 Read-local / write-global vs read-global / write-local

- **Read-local, write-global:** all writes funnel to one region (a single global write leader), reads served locally everywhere from replicas. Good when writes are rare and reads dominate and must be local; bad write latency for far regions. (CRDB `GLOBAL` tables are this for the strong-read-everywhere case.)
- **Read-global, write-local (the geo-partitioned default):** each region writes its own data locally and fast; reading *another region's* data is the global/slow case. Optimizes for the common "users mostly touch their own data" workload.

Most consumer apps fit **read-global, write-local** because a user overwhelmingly reads and writes their own data, which lives in their home region.

### 7.7 Follow-the-sun

A pattern where the *active* responsibility (or the leaseholder, or on-call) **migrates with the time zone** so the busy region is always the local one. At the data layer this can mean dynamically moving leaseholders to the currently-hot region, or routing batch/maintenance work to the region that is in its off-peak night. Spanner/CRDB can rebalance leaseholders toward where load is; you can also schedule it. Caution: moving leaders has a cost (lease transfer) and can disrupt in-flight transactions, so don't thrash.

### 7.8 Bootstrapping and schema changes across regions

Online schema changes in multi-region NewSQL (CRDB/Spanner) use a **multi-version, staged protocol** (à la F1's online schema change): the schema transitions through intermediate states (`DELETE_ONLY` → `WRITE_ONLY` → `PUBLIC`) so that nodes in different regions, possibly seeing the change at different times, never corrupt data. This is why a seemingly trivial `ADD COLUMN` can take noticeable time and proceed in phases across regions.

### 7.9 Backfills, region adds, and rebalancing

Adding a region triggers massive background **rebalancing** (copying replicas to the new region) — large egress and load. Schedule it, throttle it (CRDB `kv.snapshot_rebalance.max_rate`), and watch lag. Removing a region requires first relocating any leaseholders/replicas off it.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Topology decision

| Requirement | Recommended topology |
|---|---|
| One region of users, no residency, multi-AZ HA suffices | **Single region, multi-AZ** (don't go multi-region) |
| Survive region loss, writes from one region, simple | **Active-passive** (primary + cross-region standby) |
| Global users, each mostly touches own data, want local writes | **Geo-partitioned active-active** (home region per row) |
| Same data written from many regions, need local writes everywhere | **Multi-leader + conflict strategy** (CRDT/LWW), accept complexity |
| Strong global consistency mandatory, can pay latency | **Spanner / CockroachDB (CP)** with cross-region quorum |
| Max availability + lowest latency, can tolerate eventual + LWW loss | **DynamoDB Global Tables / Cassandra (AP)** |
| Hard data-residency per jurisdiction | **Geo-partitioned with residency-constrained replica placement** |

### 8.2 System comparison

| | Spanner | CockroachDB | YugabyteDB | DynamoDB Global Tables | Cassandra/Scylla |
|---|---|---|---|---|---|
| Model | CP / EC | CP / EC | CP / EC | AP / EL | AP (tunable) |
| Consistency | external (linearizable+serializable) | serializable | serializable | eventual (LWW) | tunable (LOCAL_QUORUM…) |
| Replication | Paxos + TrueTime | Raft + HLC | Raft + HLC | async multi-active | async, leaderless |
| Clocks | GPS/atomic (TrueTime) | NTP + max_offset (HLC) | HLC | wall-clock LWW | wall-clock LWW |
| Geo-partition | leader placement + partitioning | `REGIONAL BY ROW` | tablespaces / preferred zones | partition by region key (app) | `NetworkTopologyStrategy` |
| Conflict handling | none (no concurrent leaders) | none (single leaseholder) | none | **LWW (lossy)** | LWW per cell |
| Cross-region write latency | quorum RTT + commit-wait | quorum RTT (if cross-region) | quorum RTT | local (eventual) | local (`LOCAL_QUORUM`) |
| Lock-in | GCP only | self/multi-cloud | self/multi-cloud | AWS only | self/multi-cloud |
| Wire protocol | Spanner SQL/gRPC | PostgreSQL | PostgreSQL + Cassandra | DynamoDB API | CQL |

### 8.3 Consistency-per-read decision

| Read need | Choose |
|---|---|
| Must see own/latest write, can pay latency | strong / leaseholder read |
| Can tolerate seconds of staleness, want local speed | bounded-staleness / follower read |
| Analytics over slightly old data | exact-staleness / async replica |
| Don't care about freshness | eventual / any replica |

### 8.4 Use-when / avoid-when rules

**Use multi-region when:** legal residency requires it; a single region cannot meet RTO/RPO; cross-ocean latency is your top user-facing cost; you have genuinely global, region-localizable traffic.

**Avoid (or defer) multi-region when:** you can meet HA with multi-AZ; you have no residency need; your team can't yet operate one region well (multi-region multiplies operational load); the data is inherently global-write-shared with no natural home (you'll drown in conflicts).

---

## 9. Failure modes & debugging

### 9.1 Catalog of failures

| Failure | Symptom | Root cause | Diagnosis | Mitigation |
|---|---|---|---|---|
| WAN partition (CP) | minority region writes hang/error | link loss; minority can't form quorum | region heartbeat metrics; quorum-loss alerts; `SHOW RANGES` unavailable | route writes to majority side; restore link; ≥3 regions |
| WAN partition (AP) | both sides write, divergence | link loss; both accept writes | conflict/LWW-loss metrics post-heal | choose AP knowingly; idempotent ops; reconcile |
| Split-brain (DIY) | two primaries, divergent data | promotion without fencing | duplicate primary in topology store | fencing/STONITH; consensus controller (Patroni+etcd) |
| Replication lag spike | stale reads, RPO at risk | network congestion; big txn; backfill | per-pair lag SLI; WAL/stream backlog | throttle backfills; scale link; alert on lag>RPO |
| Leaseholder drift | sudden write-latency jump | lease moved to far region after node loss | `SHOW RANGES` leaseholder column; latency by region | rebalance; pin via locality; lease-preference settings |
| Uncertainty/read restarts | throughput collapse under contention | HLC clock-skew restarts | restart-rate metric | reduce cross-region contention; geo-partition hot keys |
| Failover to stale standby | data loss beyond RPO | promoted an async laggard | post-incident RPO measurement | `maximum_lag_on_failover`; sync standby |
| Survivor overload | cascading failure after failover | survivor lacked spare capacity | region CPU/latency post-failover | capacity headroom; load shedding |
| Residency leak | PII in wrong region | logs/backups/analytics not constrained | data-flow audit; egress logs | constrain all replicas/backups/logs; key scoping |
| Clock skew breach | node self-terminates (CRDB) | NTP failure, VM pause | DB logs "clock offset exceeds max" | reliable NTP/PTP; monitor offset |

### 9.2 Debugging playbook

1. **Is it a partition or a region loss?** Check region-to-region heartbeats and the topology/consensus store (etcd keys, `SHOW REGIONS`). Partition → both sides may be alive; loss → one side dark.
2. **Where is the quorum/leaseholder?** `SHOW RANGES`, Spanner instance config / replica info, Cassandra `nodetool status`. Latency jumps usually trace to a leader/leaseholder in the wrong region.
3. **What's the lag?** Compare leader commit timestamp vs replica apply timestamp per region pair; CRDB DB Console, Dynamo CloudWatch `ReplicationLatency`, Postgres `pg_stat_replication` (`replay_lag`).
4. **Did we lose writes?** Compare RPO budget to measured gap; in AP, scan for LWW conflicts in the change feed/streams.
5. **Reproduce with fault injection** (`tc netem`, toxiproxy) in staging to confirm the trigger and validate the fix.

### 9.3 Real-world incidents (instructive)

- **AWS `us-east-1` outages (Dec 2021, June 2023):** single-region dependencies (control planes, defaults landing in us-east-1) caused cascading global impact — the textbook argument for not concentrating critical state in one region.
- **GitHub October 2018 (24h+ degraded):** a 43-second network partition between US East and West triggered an automated Orchestrator failover; when the partition healed, the two coasts had divergent MySQL state, forcing slow, careful reconciliation. Canonical multi-region split-brain/reconciliation story.
- **Cloudflare / others, configuration-push regional cascades:** a bad global config can take down all regions at once — multi-region protects against *infrastructure* failure, not against *correlated logical* failures (bad deploys/configs). Stagger and canary changes per region.

The throughline: **multi-region defends against independent regional failures, not correlated ones (bad config, schema bug, poisoned data) — those replicate everywhere instantly.** Pair geo-redundancy with progressive/canary rollouts.

---

## 10. Interview drill

**Q1. Why go multi-region, and what does it cost you?**
*Model answer:* Three drivers — latency (put data near users; light-speed RTT is the floor), availability/disaster tolerance (survive whole-region loss for RTO/RPO targets a single region can't meet), and data residency/compliance (GDPR-style laws requiring data stay in-jurisdiction). Costs: multiplied storage/compute, cross-region egress fees, and — most fundamentally — the latency-vs-consistency wall: any data kept strongly consistent across regions pays WAN round-trips on writes.
- *Probe: which comes first?* Usually availability + residency are hard requirements; latency is an optimization. Adopt for hard requirements, not vibes.
- *Probe: when NOT to?* If multi-AZ meets HA and there's no residency need — multi-region multiplies operational complexity.
- *Probe: does it protect against bad deploys?* No — correlated logical failures replicate everywhere; you still need canary/staggered rollouts.

**Q2. Explain CAP and PACELC in the context of two regions with a WAN partition.**
*Model answer:* During a partition you must choose Consistency (minority becomes unavailable to preserve linearizability — CP) or Availability (both sides serve, diverge, reconcile later — AP). PACELC adds: *Else* (no partition), choose Latency or Consistency — even healthy, strong cross-region consistency costs WAN latency. Spanner is PC/EC; DynamoDB Global Tables are PA/EL.
- *Probe: are partitions rare?* Across regions, no — WAN degradation is routine, so the choice is frequent and real.
- *Probe: where does Cassandra LOCAL_QUORUM sit?* AP-leaning; it gets fast local-DC quorum and eventual cross-DC, sacrificing cross-region strong consistency for latency/availability.

**Q3. How does geo-partitioning reduce write latency, concretely?**
*Model answer:* Give each row a home region and pin that partition's leader/leaseholder there. Writes to local-home data form a quorum *within* the home region's AZs (~2 ms) instead of across the ocean (~80–160 ms). In CRDB it's `REGIONAL BY ROW` adding a `crdb_region` column; in Spanner it's leader placement + partitioning.
- *Probe: what about reading another region's data?* Either pay a cross-region strong read or use a local follower/bounded-staleness read (a few seconds stale, but local-fast).
- *Probe: residency tie-in?* Constrain that partition's replicas to in-jurisdiction regions → PII never leaves; compliance enforced at storage.

**Q4. Active-active vs active-passive at the data layer — tradeoffs?**
*Model answer:* Active-passive: one region serves all traffic, others are standbys; simple, no conflicts, but idle capacity and a discrete risky failover (with RPO if async). Active-active: multiple live regions; if geo-partitioned (disjoint data per region) it's conflict-free and gives local writes; if true multi-leader (same data writable everywhere) you inherit write conflicts needing resolution.
- *Probe: how avoid conflicts in active-active?* Single-writer-per-key via geo-partitioning — the clean version.
- *Probe: capacity caveat?* Survivor must absorb failed region's load; don't run regions hot.

**Q5. How does Spanner give external consistency across regions?**
*Model answer:* TrueTime exposes a bounded-uncertainty clock interval `[earliest, latest]` (ε via GPS/atomic clocks). Each commit gets a timestamp, and the coordinator does **commit-wait** (waits until the timestamp is definitely past, ~2ε) before releasing locks, guaranteeing global real-time order. Replication is Paxos; cross-group txns use 2PC over Paxos. The price is latency (quorum RTT + commit-wait).
- *Probe: why GPS/atomic clocks?* To keep ε tiny (~ms) so commit-wait stays small; on commodity NTP clocks ε would be too large.
- *Probe: how do CRDB/Yugabyte avoid that hardware?* HLC + max clock-offset assumption + uncertainty read-restarts instead of tight bounds.

**Q6. How do DynamoDB Global Tables resolve conflicts, and what's the trap?**
*Model answer:* Multi-active: every region writes locally and acks fast; async replicate (~1 s). Conflicts resolved by **Last-Writer-Wins** on timestamp — the loser is silently dropped. Trap: concurrent read-modify-write on the same item from two regions can lose updates. Mitigate by partitioning writes by region, using idempotent/commutative ops, conditional writes, or routing a key's writes to one region.
- *Probe: are cross-region reads strong?* No — only reads in the region you wrote to can be strong; cross-region is eventual.
- *Probe: how detect lost writes?* Instrument it — the DB won't tell you; inspect Streams/conflict metrics.

**Q7. You must serve EU users with GDPR residency and global low-latency reads. Design it.** *(senior-signal)*
*Model answer:* Geo-partition by user country; home EU users in an EU-only multi-region config (e.g., Spanner `eur6` or CRDB EU regions) with replicas, backups, **and logs** constrained to the EU. Writes for EU users commit on an EU-local quorum (fast, compliant). For non-EU users reading EU data (rare), accept cross-region strong reads or use bounded-staleness local reads where freshness allows. Encrypt with EU-scoped KMS keys. Validate residency in CI (assert no replica/backup outside allowed regions) and in game-days (kill EU AZ, confirm failover stays in EU — needs ≥3 EU AZs/regions for quorum survival).
- *Probe: two EU regions enough?* No for region-failure survival — can't keep a majority after losing one; need ≥3.
- *Probe: biggest residency footgun?* Logs/analytics pipelines carrying PII out of jurisdiction.

**Q8. A user writes in region A then immediately reads stale data and complains. Diagnose and fix.** *(senior-signal)*
*Model answer:* Read-your-writes violation: the read hit a replica/follower that hadn't received the write yet (async lag). Fixes: pin the user to their home region for both read and write (sticky session); route post-write reads to the leaseholder/strong path; or use a write-token/timestamp so the read waits for the replica to reach that timestamp (read-after-write barrier). Don't use follower/bounded-staleness reads for a user's own just-written data.
- *Probe: cost of always-strong reads?* Cross-region latency on every read — overkill; only the user's own recent data needs it.
- *Probe: how implement the barrier?* Capture commit timestamp on write; on read, request "as of >= that timestamp," forcing the replica to catch up or redirect.

**Q9. Choose between Spanner, CockroachDB, and DynamoDB Global Tables for a global fintech ledger.** *(senior-signal)*
*Model answer:* A ledger needs strong/serializable consistency and no silent loss → rules out DynamoDB Global Tables (LWW lossy). Between Spanner (GCP-locked, external consistency, mature, tight clocks) and CockroachDB (multi/any-cloud, serializable, PostgreSQL-compatible, HLC). Pick Spanner if all-in on GCP and want the strongest guarantee with least clock worry; pick CRDB for cloud portability and PostgreSQL ecosystem. Either way, geo-partition by account home region for local writes, keep ≥3 regions for survivability, and reserve cross-region strong reads for the cases that need them.
- *Probe: why not Dynamo with app-side conflict handling?* You'd be reimplementing transactions/conflict resolution the others give natively; for money, don't.
- *Probe: latency expectation?* Cross-region strong writes are tens of ms (quorum RTT [+ commit-wait for Spanner]); design so most writes are local-home.

**Q10. What's the minimum region count to survive a region failure while staying consistent, and why?**
*Model answer:* Three. Consensus needs a majority; with 2 regions, losing 1 leaves 1 — not a majority — so the survivor can't safely commit (CP) or you risk split-brain (naive). With 3 (or odd-numbered) you keep a majority after one loss. Witness/quorum-only replicas can supply the third vote cheaply without full data storage.
- *Probe: can a 3rd "witness" region hold no data?* Yes — Spanner witnesses / CRDB non-voting setups vote for quorum without storing the dataset, cutting cost and (sometimes) easing residency.
- *Probe: AZs vs regions?* Same logic at AZ granularity if you only need to survive zone failure.

**Q11. Explain replication lag's relationship to RPO and how you bound it.**
*Model answer:* Under async replication, RPO ≈ the replication lag at the moment of failure (writes not yet shipped are lost). Bound it with sync replication (RPO 0 within the sync set, at write-latency cost), `maximum_lag_on_failover` (refuse to promote a too-stale standby), and lag SLIs/alerts tied to your RPO budget.
- *Probe: sync across regions tradeoff?* RPO 0 but every write pays WAN RTT — usually only acceptable intra-region or for the most critical data.

**Q12. How do CRDTs help multi-region, and what are their limits?**
*Model answer:* CRDTs are data types whose concurrent updates merge to the same correct value regardless of order (G/PN-Counters, OR-Sets, LWW-Registers, sequence CRDTs), enabling conflict-free multi-leader replication without coordination. Limit: only specific data shapes have CRDT formulations; arbitrary business invariants (e.g., "balance ≥ 0") generally can't be expressed as a CRDT, so they still need coordination/single-writer.
- *Probe: example where CRDT fits?* Shopping cart (OR-Set), like-counter (PN-Counter), presence.
- *Probe: where it doesn't?* Enforcing non-negative balance or unique constraints across regions.

---

## 11. Glossary

- **Active-active:** two+ regions serving live traffic simultaneously.
- **Active-passive:** one active region; others are standbys awaiting failover.
- **Anti-entropy:** background process that compares replicas (e.g., via Merkle trees) and repairs divergence.
- **Availability Zone (AZ):** an isolated datacenter within a region; low-latency links to sibling AZs.
- **Bounded staleness:** a read guaranteed no older than a configured time, servable from a nearby replica.
- **CAP theorem:** under a partition, choose Consistency or Availability, not both.
- **Causal consistency:** causally related ops seen in order everywhere; concurrent ops may differ.
- **Clock skew:** difference between two machines' clocks.
- **Commit-wait:** Spanner's deliberate wait (~2ε) to guarantee global timestamp ordering.
- **Consensus:** protocol (Paxos/Raft) by which nodes agree on an ordered log despite failures.
- **CP / AP:** CAP categories — consistency-preferring vs availability-preferring under partition.
- **CRDT:** Conflict-free Replicated Data Type; concurrent updates merge automatically.
- **Eventual consistency:** replicas converge if writes stop; no timing/ordering guarantee meanwhile.
- **External consistency:** Spanner's guarantee that commit order matches real-world time globally.
- **Fencing / STONITH:** forcibly isolating a node so a deposed leader can't keep writing (prevents split-brain).
- **`fsync`:** syscall forcing buffered data to physical disk for durability.
- **Follower read:** reading a non-leader replica at a slightly past timestamp for local speed.
- **Follow-the-sun:** shifting active responsibility/leadership to the currently busy time zone.
- **GDPR:** EU data-protection law; drives data-residency requirements.
- **Geo-partitioning:** partitioning where a row's physical location is chosen by a locality attribute.
- **G-Counter / PN-Counter / OR-Set:** specific CRDT designs (grow-only counter / increment-decrement counter / observed-remove set).
- **Happened-before:** Lamport's causal-precedence relation between events.
- **HLC (Hybrid Logical Clock):** clock combining physical time with a logical counter.
- **Leaderless:** any replica accepts reads/writes; consistency tuned by quorums (Dynamo-style).
- **Leaseholder:** (CRDB) the replica allowed to serve reads/sequence writes for a range without a read quorum.
- **Linearizability:** strongest single-object recency guarantee; behaves as one copy, instant effect.
- **Logical clock (Lamport timestamp):** counter giving an order consistent with happened-before.
- **LWW (Last-Writer-Wins):** keep the higher-timestamp write, discard the other (lossy).
- **Multi-leader:** multiple nodes accept writes; conflicts possible.
- **NTP / PTP:** Network/Precision Time Protocols for clock synchronization.
- **PACELC:** if Partition, A or C; Else, Latency or Consistency.
- **Paxos:** classic consensus protocol (used in Spanner).
- **Partition (data):** a horizontal slice of a table; also a *network* partition (a split).
- **Quorum:** the threshold (often majority) of nodes that must agree.
- **Raft:** an understandable consensus protocol with leader election + replicated log.
- **Region:** a cluster of nearby datacenters; multiple AZs; far from other regions.
- **Replication lag:** delay between a write on the leader and its appearance on a replica.
- **Replicated log / state-machine replication:** apply identical ordered ops on every replica for identical state.
- **RPO (Recovery Point Objective):** max acceptable data loss, in time.
- **RTO (Recovery Time Objective):** max acceptable downtime before recovery.
- **Serializability:** strongest transaction isolation — result as if txns ran one at a time.
- **Split brain:** two nodes both believing they're sole leader, diverging.
- **Synchronous vs asynchronous replication:** ack after replica durable (RPO 0, slow) vs ack first, ship later (RPO>0, fast).
- **TrueTime:** Google's bounded-uncertainty clock API (GPS + atomic clocks).
- **Two-phase commit (2PC):** prepare-then-commit protocol for atomic multi-participant transactions.
- **Vector clock:** per-node counter vector enabling detection of concurrent updates.
- **WAL (Write-Ahead Log):** append-only log written before data pages for crash recovery and replication.
- **Witness replica:** a quorum-voting replica that stores no (or minimal) data, for cheap survivability.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Why multi-region:** latency · availability/DR · residency (GDPR). **Costs:** ×N storage/compute + egress + latency-vs-consistency wall.

**RTT memory pegs:** same-region ~1–2 ms · US coast-to-coast ~60–70 ms · transatlantic ~80–90 ms · trans-Pacific ~120–160 ms.

**CAP under partition:** CP (minority unavailable) vs AP (diverge, reconcile). **PACELC Else:** Latency vs Consistency even when healthy.

**Survive a region:** need **≥3 regions** (majority after losing one). Two regions = no majority = split-brain/unavailability.

**Geo-partition:** give each row a **home region**, pin leader/leaseholder there → local-quorum writes (~2 ms) instead of ocean RTT. CRDB `REGIONAL BY ROW`; Spanner leader placement; Yugabyte tablespaces; Cassandra `NetworkTopologyStrategy` + `LOCAL_QUORUM`.

**Consistency per read:** strong (latest, slow cross-region) · bounded-staleness/follower (local, seconds stale) · eventual (any replica). **Never** serve a user's own just-written data from a stale follower (read-your-writes).

**Conflict resolution ladder:** single-writer (best) → idempotent/commutative → CRDT → app-merge → LWW (lossy) → manual.

**RPO ≈ replication lag at failure** (async). Bound with sync set, `maximum_lag_on_failover`, lag alerts.

**Systems:** Spanner = CP/EC, Paxos+TrueTime+commit-wait, GCP. CRDB/Yugabyte = CP/EC, Raft+HLC, multi-cloud, PostgreSQL-wire. DynamoDB Global Tables = AP/EL, multi-active, **LWW (silent loss)**, AWS. Cassandra = AP tunable, `LOCAL_QUORUM`.

**Top anti-patterns:** sync cross-region on hot path · 2 regions = HA · multi-leader same-key w/o conflict plan · ignoring read-your-writes · residency leaks in logs/backups · chatty cross-region N+1.

**Remember:** multi-region defends against *independent* regional failure, not *correlated* logical failure (bad config/deploy/schema) — pair with canary/staggered rollouts.

### 12.2 Self-test (no answers — active recall)

1. Your app must survive a full region outage with RPO 0 for committed orders and p99 write latency under 10 ms for the common case. Sketch a topology and explain why two regions is insufficient — and exactly what changes at three.
2. Walk through, step by step, what happens to in-flight writes and reads when the WAN link between your two active regions degrades for 60 seconds, in (a) a CP system and (b) an AP system, then what the heal/reconcile path looks like for each.
3. Explain why Spanner needs GPS/atomic clocks while CockroachDB does not, and what each pays for that choice. What metric would tell you CRDB is suffering from clock-related restarts?
4. You discover EU user PII is appearing in application logs shipped to a US logging cluster, even though the database is correctly EU-homed. Explain why this is a residency violation, where else this class of leak hides, and how you'd prevent it systematically.
5. A user reports that after saving their profile they sometimes see the old version on refresh. Give three distinct root causes related to multi-region reads and a concrete fix for each.
6. Design the conflict-resolution strategy for a globally-writable shopping cart vs a globally-writable bank balance. Justify why one can be conflict-free and the other generally cannot.
7. You add a new region and write latency in *existing* regions briefly spikes. Explain the likely cause(s) and the knobs you'd reach for to control it.

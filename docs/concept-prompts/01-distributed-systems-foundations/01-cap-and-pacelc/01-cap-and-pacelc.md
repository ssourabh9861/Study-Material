# CAP & PACELC — A Definitive Engineering Handbook Chapter

> **Reader profile:** A senior backend developer in the Java/JVM ecosystem who wants to *fully master* distributed-systems consistency tradeoffs — enough to design systems, operate and debug them in production, teach them, and answer any interview question.

---

## 1. Overview & where it fits

### What it is

**CAP** and **PACELC** are *theorems and design frameworks* that describe the unavoidable tradeoffs a distributed data store must make. They do not tell you how to build a system; they tell you what you are *not allowed* to have simultaneously, so that you choose your compromises deliberately instead of discovering them in an outage at 3 a.m.

- **CAP theorem** (Brewer's conjecture, 2000; formalized by Gilbert & Lynch, 2002): In the presence of a **network partition**, a distributed system must choose between **consistency** and **availability**. It cannot have both.
- **PACELC** (Daniel Abadi, 2010/2012): An extension that says CAP is incomplete because it only talks about the (rare) partition case. PACELC adds: *even when the system is running normally (no partition)*, you still trade **latency** against **consistency**. The acronym reads: **P**artition → **A** or **C**; **E**lse (no partition) → **L** or **C**.

### The problem it solves (for *you*, the engineer)

You are choosing or building a datastore for a feature. Someone says "make it consistent and always up." CAP/PACELC give you the vocabulary and the proof that this request, taken literally, is impossible across machines connected by an unreliable network. They force the conversation that actually matters: *for this specific data, under failure, do I prefer to return a possibly-stale-or-wrong answer, or to refuse to answer?* And *in the normal case, am I willing to pay extra latency to be more correct?*

This maps directly onto real decisions:
- Money/ledger data → usually favor **consistency** (refuse rather than double-spend).
- Shopping cart, social feed, view counts, sessions → usually favor **availability/latency** (serve something, reconcile later).

### When you reach for it

- Choosing a database (Cassandra vs. Spanner vs. MongoDB vs. ZooKeeper vs. DynamoDB vs. Postgres).
- Designing a replication or quorum scheme.
- Setting client-side consistency knobs (e.g., Cassandra's `ONE` vs `QUORUM` vs `ALL`; MongoDB's `writeConcern`/`readConcern`; DynamoDB's strongly-vs-eventually consistent reads).
- Writing an incident postmortem that explains *why* the system returned stale data or rejected writes.
- Interviews: this is the canonical distributed-systems screening topic.

### The one-paragraph mental model

> Picture two replicas of your data on two machines, connected by a network cable. Cut the cable (a **partition**). A write lands on machine A. A client now reads from machine B. You have exactly two honest choices: (1) machine B refuses or blocks the read until it can talk to A again — that's **CP**, you sacrificed *availability* to stay *consistent*; or (2) machine B answers with its old value — that's **AP**, you stayed *available* but served *inconsistent* (stale) data. CAP is just this picture. **PACELC** adds: even with the cable intact, every time you want B to reflect A's write *before* answering, you must wait for A and B to coordinate — and that coordination costs **latency**. So consistency is never free: under partition it costs availability, and the rest of the time it costs latency.

---

## 2. Foundations from first principles

We build up every term from zero. If you already know a term, skim — but the precise definitions below matter, because most CAP confusion comes from sloppy definitions.

### 2.1 What is a distributed system (for this discussion)?

A **distributed system** is a set of computers (**nodes**) that coordinate over a network to present themselves, ideally, as a single coherent service. For CAP we care specifically about a **replicated data store**: the same logical piece of data lives on more than one node so the system can survive node failures and serve more traffic. The instant data is **replicated**, the copies can disagree, and CAP becomes relevant.

- **Node / replica:** one machine (or process) holding a copy of the data.
- **Replication:** keeping multiple copies of the same data on different nodes. Done for *durability* (survive disk/machine loss), *availability* (survive node failure), and *read scaling* (spread reads).
- **Client:** the program (often your Java service) issuing reads and writes to the store.

### 2.2 What is a network partition?

A **network partition** is a failure in which the network splits the nodes into two or more groups that **cannot communicate** with each other, even though each group may still be alive and reachable by *some* clients. Messages between the groups are dropped or delayed indefinitely.

Crucially, a partition is **indistinguishable, from inside a node, from a slow node or a crashed node.** Node A sends a message to node B and gets no reply. A cannot tell whether: (a) B crashed, (b) B is slow/GC-paused, or (c) the link to B is down. This ambiguity is the root of the entire CAP problem. This is sometimes called the **"two generals" / FLP-flavored** difficulty (see §2.9 and Glossary).

Partitions are not exotic. They include: a switch reboot, a misconfigured firewall rule, a flaky NIC, a cross-AZ link saturating, a long **GC pause** (a JVM stop-the-world garbage collection that freezes the process for seconds — to peers it looks exactly like a partition), a `STONITH`/fencing event, BGP route flaps, and "gray failures" where the link is up but 30% of packets drop. Treat partitions as a *when*, not an *if*.

### 2.3 What does "Consistency" mean in CAP? (and what it does NOT mean)

This is the single most misunderstood term in the field, because the word "consistency" is overloaded across at least three communities.

**CAP's "C" = linearizability** (a.k.a. *atomic consistency* or *strong consistency* in the single-object sense). Linearizability means:

> Every operation appears to take effect **instantaneously at some single point in time** between its invocation and its response, and once a write completes, **all subsequent reads (by wall-clock time) see that write or a later one.** The system behaves as if there is exactly **one copy** of the data and operations on it are totally ordered consistent with real time.

Practical consequence: if a write `W` finishes at 12:00:00.000, any read that *starts* at 12:00:00.001 — from *any* client, against *any* replica — must return `W` (or something newer). No read can see the old value after `W` returned.

It is NOT the same as:
- **The "C" in ACID** (transaction consistency). ACID-C means a transaction moves the database from one *valid state to another* with respect to declared invariants/constraints (foreign keys, check constraints, the "balance ≥ 0" rule). That is the application's correctness, enforced by the DB. CAP-C is about *replica agreement and real-time ordering*, not constraints. These are different concepts that share a letter. (This collision is a frequent interview trap.)
- **Serializability** (a multi-object transaction isolation property). Linearizability is about *single objects* and *real-time order*; serializability is about *transactions* (groups of operations) being equivalent to *some* serial order, with no real-time guarantee. The strongest practical guarantee, **strict serializability**, is roughly *serializability + linearizability* (real-time order across transactions). Spanner provides this; it calls it **external consistency**.

> **Mental hook:** CAP-C = "behaves like a single, up-to-date copy with respect to a global wall clock." ACID-C = "never violates my business rules." Serializability = "transactions look like they ran one at a time."

### 2.4 The consistency spectrum (from strongest to weakest)

You will design within this spectrum constantly. Define each rung:

| Model | Guarantee | Cost / note |
|---|---|---|
| **Strict serializability** | Transactions appear in a single serial order consistent with real time. | Strongest. Spanner ("external consistency"), FaunaDB, CockroachDB (serializable + near-strict). |
| **Linearizability** (CAP-C) | Single-object ops appear instantaneous & real-time ordered; one logical copy. | Strong, single-object. Requires consensus or synchronous quorum. |
| **Sequential consistency** | All clients see operations in the *same order*, consistent with each client's program order — but **not** necessarily real-time order. | A read may return a slightly old value as long as ordering is globally agreed. |
| **Causal consistency** | Operations that are *causally related* (one could have influenced the other) are seen in order by everyone; concurrent ops may be seen in different orders. | The strongest model achievable while remaining **available** under partition (per the CALM/Bailis "Highly Available Transactions" line of work). |
| **Read-your-writes / Monotonic reads / Monotonic writes / Writes-follow-reads** | Per-client "session" guarantees: you see your own writes; you never see time go backward; etc. | Cheap, hugely improves UX; often layered on eventual consistency via sticky routing or version tracking. |
| **Eventual consistency** | If writes stop, all replicas *eventually* converge to the same value. No bound on *when*, no ordering guarantee in the meantime. | Weakest useful model. Dynamo-style stores. Needs conflict resolution (LWW, vector clocks, CRDTs). |

> **Beginner note — "eventually" is doing heavy lifting.** Eventual consistency promises convergence *if writes stop*. In a live system writes never stop, so what you actually observe is a "**replication lag** window" — usually milliseconds, but can balloon to seconds/minutes under load or partition. Stale-read probability ≈ lag duration relative to read rate.

### 2.5 What does "Availability" mean in CAP?

CAP **Availability** has a strict formal definition: **every request received by a non-failing node must result in a (non-error) response.** Not "the system is mostly up." Not "99.99% uptime." It means *every* node that is up will *always* answer (with data, not an error or timeout), regardless of partition.

Consequence: a node that *refuses* a request, returns an error, or *blocks indefinitely* waiting for a peer is, by CAP's definition, **not available** for that request. This is stricter than the operational SLA sense of "availability" and is another classic source of confusion.

- **CAP availability (formal):** every live node answers every request, always.
- **Operational availability (SLA):** fraction of time the *service as a whole* is usable, e.g. "four nines" = 99.99% ≈ 52.6 min downtime/year. A CP system can have excellent *operational* availability (it's only unavailable during the rare partition) while being "CP" (not CAP-available) by the formal definition.

> **Don't conflate them.** "CP" doesn't mean "frequently down." Spanner is CP and offers a 99.999% SLA. CP means: *during a partition*, the minority side stops serving rather than serve possibly-wrong data.

### 2.6 What does "Partition tolerance" mean?

**Partition tolerance** means the system **continues to operate** (in *some* defined way) despite an arbitrary number of messages being dropped or delayed between nodes. It is the system's ability to *keep functioning through* a partition, choosing C or A as its behavior.

This is the term most often misread. "Partition tolerance" is **not** an optional feature you can decline. (See §2.8.)

### 2.7 The CAP theorem, stated precisely

**Gilbert & Lynch (2002) formalization:** It is impossible for a distributed data store to *simultaneously* provide all three of:
1. **Consistency** (linearizability),
2. **Availability** (every non-failing node responds to every request), and
3. **Partition tolerance** (the system tolerates arbitrary message loss between nodes),

The precise, defensible statement is: **In an asynchronous network model where messages can be lost, no read/write register can be both *available* and *linearizable* in the presence of partitions.** (Gilbert & Lynch prove this for the asynchronous model and a weaker result for the partially-synchronous model.)

> **The honest one-liner:** "When a partition happens, you must give up C or A." Outside a partition the theorem says *nothing* — which is exactly why PACELC was needed.

### 2.8 Why "pick 2 of 3" is wrong, and why P is not optional

The popular "**pick any 2 of CAP**" slogan is misleading and, taken literally, wrong. Here's why:

- **You do not get to choose whether partitions happen.** The network *will* partition. P is a property of the *environment*, not a feature you toggle. So "CA" — a system that has Consistency and Availability but *not* Partition tolerance — only exists if you assume partitions never occur. On a real multi-node network, that assumption is false.
- A single-node database (one Postgres instance, no replicas) is the only honest "CA" system: there is no network *between replicas* to partition, so the tradeoff never arises. But it also isn't a *distributed* system, and a network partition between it and its *clients* simply makes it unreachable (which is just "down").
- Therefore the *real* choice for any genuinely distributed store is between **CP** and **AP**: *given that partitions are inevitable, when one occurs, do you sacrifice C (stay available) or sacrifice A (stay consistent)?*

> **Restate it correctly:** CAP is not "choose 2 of 3." It is: "**P is forced; choose C or A for the moments P is active.**"

Even subtler (Brewer's own 2012 "CAP Twelve Years Later"): the choice is **not global or permanent.** A system can be CP for some operations and AP for others, and can vary the choice per partition, per data type, even per request (e.g., Cassandra's per-query consistency level). The tradeoff is *fine-grained*.

### 2.9 Adjacent foundational concepts (explained inline)

- **Consensus:** the problem of getting a set of nodes to *agree* on a single value (e.g., "who is the leader" or "what is the next entry in the log"), even when some nodes fail. Algorithms: **Paxos**, **Raft**, **Zab** (ZooKeeper), **Viewstamped Replication**. Consensus is how CP systems stay consistent: they require a **majority (quorum)** to agree before committing, so the minority side of a partition cannot commit (preserving C by sacrificing A on the minority).
- **Quorum:** a minimum number of nodes that must participate in an operation for it to count. With `N` replicas, a common rule is `W + R > N` (write quorum + read quorum exceed N), which guarantees a read overlaps with the latest write → strong consistency. Majority quorum = `⌊N/2⌋ + 1`.
- **Raft (consensus algorithm):** elects a single **leader**; all writes go through the leader, which replicates the log to followers and commits an entry once a majority acknowledges. A partition that isolates the leader's minority forces a new election on the majority side; the old leader steps down → only the majority side serves writes (CP). *Beginner gist: "one boss, majority must agree, minority shuts up."*
- **Paxos / Multi-Paxos:** the original consensus protocol (Lamport). Harder to understand than Raft but equivalent in power. Spanner uses Paxos per shard.
- **Zab (ZooKeeper Atomic Broadcast):** ZooKeeper's consensus/atomic-broadcast protocol; like Raft, leader-based, majority quorum.
- **FLP impossibility (Fischer-Lynch-Paterson, 1985):** in a fully *asynchronous* network, **no consensus algorithm can guarantee termination** if even one node can fail (you can't distinguish slow from dead). Real systems sidestep FLP by adding *timeouts* (partial synchrony) and accepting that they may be temporarily unavailable rather than incorrect. FLP is the deeper "why" behind CAP's CP unavailability.
- **MVCC (Multi-Version Concurrency Control):** keep multiple versions of a row so readers see a consistent snapshot without blocking writers. Used by Postgres, Spanner, CockroachDB. Relevant because it's how strongly-consistent systems offer *snapshot reads* cheaply.
- **Vector clock:** a per-replica counter vector used to detect whether two versions are causally ordered or *concurrent* (conflicting). Dynamo/Riak use them to surface conflicts. *Gist: "version stamps that reveal who-saw-what."*
- **CRDT (Conflict-free Replicated Data Type):** data structures (counters, sets, maps) mathematically designed so concurrent updates *always merge deterministically* without conflict — enabling AP systems to converge automatically. Used by Riak, Redis (enterprise), Azure Cosmos DB.
- **LWW (Last-Write-Wins):** the simplest conflict resolution — keep the version with the highest timestamp, discard the rest. Cheap but **silently loses data** under concurrency and clock skew. Cassandra's default.
- **Hinted handoff / read repair / anti-entropy:** Dynamo-style mechanisms to push toward eventual consistency. *Hinted handoff*: a node temporarily stores writes destined for an unreachable node and replays them later. *Read repair*: on a read, if replicas disagree, push the newest value to the stale ones. *Anti-entropy*: background process (often Merkle-tree-based) that compares and reconciles replicas.
- **TrueTime:** Google Spanner's API that exposes time as an *interval* `[earliest, latest]` with a bounded uncertainty `ε` (epsilon, typically a few ms), backed by GPS + atomic clocks. By *waiting out* the uncertainty (`commit-wait`), Spanner assigns globally meaningful timestamps and achieves external consistency. We'll revisit in §3 and §7.

---

## 3. How it works internally

CAP/PACELC are *frameworks*, so "how it works internally" means: **how a replicated store actually implements a C-vs-A or C-vs-L choice under the hood.** We trace the real machinery: the partition lifecycle, quorum math, leader-based commit, and the precise control/data flow that produces CP or AP behavior.

### 3.1 The replicated-write data path (the core picture)

Setup: `N = 3` replicas of key `k` on nodes A, B, C. A client wants to write `k = v2` (previous value `v1`).

**Synchronous-quorum (CP-leaning) write flow:**
1. Client sends `PUT k=v2` to a coordinator (could be the leader or any node).
2. Coordinator forwards to all `N=3` replicas (or proposes via consensus).
3. Coordinator waits for `W` acknowledgments (the **write quorum**).
4. Once `W` acks arrive, the write is *committed*; coordinator replies success to client.
5. A subsequent read contacts `R` replicas (the **read quorum**) and returns the newest value among them.

**The quorum invariant for strong consistency:** `W + R > N`. With `N=3`, choosing `W=2, R=2` guarantees any read set and any write set **overlap in at least one node**, so the read sees the latest committed write. (Proof sketch: two sets of sizes summing to > N over N elements must intersect — pigeonhole.)

- `W=1` (write to any one) + `R=1` (read from any one) → `1+1 = 2 ≤ 3` → **no overlap guaranteed** → eventual consistency (AP-style, fast).
- `W=N, R=1` → writes are slow & fragile (all must ack) but reads are fast & consistent.
- `W=⌈(N+1)/2⌉, R=⌈(N+1)/2⌉` → majority quorum, balanced, strongly consistent.

### 3.2 What happens during a partition — step by step (CP system)

Scenario: Raft/Paxos-style leader-based store, `N=5`, partition splits into **{A, B}** (minority, contains the old leader A) and **{C, D, E}** (majority).

1. **Steady state:** A is leader; all 5 in one log; writes commit when ≥3 ack.
2. **Partition occurs:** A can no longer reach C, D, E.
3. **Old leader (A) tries to commit:** it needs 3 acks but can only reach B → only 2 → **cannot commit any write.** Writes on the {A,B} side **block or fail** → minority is *unavailable for writes* (CP: sacrificed A to keep C).
4. **Majority side detects missing leader:** C/D/E's election timers expire (no heartbeats from A). They start a **leader election** (new **term**). One of them (say C) wins with ≥3 votes.
5. **Majority side resumes:** C is the new leader, can commit (3 acks: C,D,E). Clients reaching {C,D,E} get full service.
6. **Reads:** linearizable reads must also be served by the majority/leader (a "read index" or lease confirms the leader is still leader). The minority {A,B} must *not* serve stale linearizable reads — so it rejects/blocks them too (else it'd violate C).
7. **Partition heals:** A reconnects, sees a higher term, **steps down** to follower, truncates any uncommitted entries, and catches up from C's log. No split-brain; no divergent committed history. Consistency preserved throughout.

**Net effect:** the minority side was *unavailable*; the majority stayed *consistent and available*. That asymmetry — "majority lives, minority freezes" — is the signature of CP systems.

### 3.3 What happens during a partition — step by step (AP system)

Scenario: Dynamo-style store (Cassandra/DynamoDB-eventual/Riak), `N=3`, `W=1, R=1`. Partition splits into {A} and {B, C}. Clients can reach both sides.

1. **Steady state:** writes go to whichever replicas are reachable; gossip/replication keeps copies roughly in sync.
2. **Partition occurs.**
3. **Writes on side {A}:** client writes `k=v_left` → A accepts (W=1 satisfied locally) → **success**. A stores a **hint** for B and C (hinted handoff) to replay later.
4. **Writes on side {B,C}:** another client writes `k=v_right` → succeeds.
5. **Now both sides are available** (CAP-A preserved) but the value of `k` has **diverged** (`v_left` vs `v_right`) → **consistency sacrificed.**
6. **Reads:** each side returns its local view (possibly stale relative to the other side).
7. **Partition heals:** replication resumes. The two versions **conflict.** Resolution strategy decides the outcome:
   - **LWW:** higher timestamp wins; the other write is **silently lost** (data loss risk).
   - **Vector clocks → app-level merge:** system surfaces both versions ("siblings"); client must merge (e.g., union the shopping carts).
   - **CRDT:** the data type merges deterministically (e.g., a `G-Counter` sums both increments; an `OR-Set` unions adds/removes). No loss, no app code.
8. **Read repair / anti-entropy** propagate the resolved value to all replicas → eventual convergence.

**Net effect:** both sides stayed available; the data diverged and had to be reconciled. That "everyone answers, reconcile later" is the signature of AP systems.

### 3.4 PACELC's "Else" path — the latency machinery (no partition)

PACELC's contribution is the **EL/EC** axis: *with the network healthy*, how much do you wait to be consistent?

Trace a strongly-consistent (EC) read in a geo-replicated store:
1. Client in `us-east` issues a linearizable read.
2. To guarantee it sees the latest write (which may have originated in `eu-west`), the system must **confirm with a quorum** that spans regions, or **route to the leader** (possibly in another region), or **wait out clock uncertainty** (Spanner's commit-wait, ~2×ε).
3. Each of these adds **wide-area round-trip latency** — often 50–150 ms cross-region — even though *nothing is broken.*

Versus a latency-optimized (EL) read:
1. Client reads from the **nearest local replica**.
2. Returns immediately (single-digit ms) — but the local replica may be a few ms/seconds behind → possibly stale.

> **PACELC's deep point:** consistency's cost doesn't disappear when the network is healthy — it just *changes currency* from *availability* to *latency*. Quorum coordination, leader hops, and commit-wait are pure latency taxes you pay in the common case to keep things consistent. Most real workloads spend ~99.9% of their time in the "Else" branch, which is exactly why PACELC is the more *operationally relevant* model day to day.

### 3.5 State machine view

A replicated CP node moves through states like:
`FOLLOWER → (election timeout) → CANDIDATE → (wins majority) → LEADER → (sees higher term / loses majority) → FOLLOWER`.
Availability of *writes* exists only while a `LEADER` with majority contact exists. During elections (typically 150 ms–10 s depending on timeouts), the system is briefly write-unavailable — a deliberate CP behavior.

An AP node is far simpler: `UP and accepting` essentially always; the complexity moves to the *background* reconciliation state machine (hint replay, read repair, anti-entropy rounds).

---

## 4. The complete toolkit

This section enumerates the concrete knobs across the major systems and the math that underlies them. **Versions noted where behavior is version-specific.**

### 4.1 Quorum / tunable-consistency knobs (Dynamo-style: Cassandra, ScyllaDB, Riak, DynamoDB)

| Knob | What it does | Typical values / defaults |
|---|---|---|
| `N` (replication factor, RF) | Number of replicas per key. | Often `3` (per datacenter). |
| `W` (write consistency level) | How many replicas must ack a write. | Cassandra: `ANY, ONE, TWO, QUORUM, LOCAL_QUORUM, EACH_QUORUM, ALL`. Default varies by driver; commonly `LOCAL_QUORUM`. |
| `R` (read consistency level) | How many replicas must respond to a read. | Same enum as `W`. |
| `W + R > N` | Strong consistency invariant. | e.g., `QUORUM`+`QUORUM` with RF=3 → `2+2>3`. |
| Read repair chance | Probability a read triggers background repair of stale replicas. | Cassandra `read_repair_chance`/`dclocal_read_repair_chance` (deprecated/removed in 4.0; replaced by blocking read repair on QUORUM+ reads). |
| Hinted handoff window | How long a node stores hints for an unreachable peer. | Cassandra `max_hint_window_in_ms`, default **3 hours** (10800000 ms). |
| Speculative retry | Reissue a read to another replica if one is slow (tail-latency mitigation). | Cassandra `speculative_retry`, default `99PERCENTILE`. |

**Cassandra consistency-level cheat:**
- `ONE` / `LOCAL_ONE`: fastest, weakest. (AP / EL.)
- `QUORUM`: majority across all DCs. Strong but cross-DC latency.
- `LOCAL_QUORUM`: majority *within the local DC* — the workhorse for multi-DC: strong-ish locally, low latency, but **not** globally linearizable.
- `EACH_QUORUM`: quorum in *every* DC (writes only) — expensive, strong.
- `ALL`: every replica — strongest, but a *single* down replica makes it unavailable (most CP-like; least available).
- **LWT (Lightweight Transactions)** via `SERIAL`/`LOCAL_SERIAL`: Cassandra's Paxos-based compare-and-set for *linearizable* single-partition operations (`IF NOT EXISTS`, `IF col = x`). Turns specific ops CP at high latency cost (4 round trips).

### 4.2 MongoDB knobs

| Knob | What it does | Defaults / notes |
|---|---|---|
| `writeConcern: { w: <n / "majority">, j: <bool>, wtimeout: <ms> }` | How many replica-set members must ack; `j` = wait for journal (durability). | **`w: "majority"`** is default since MongoDB **5.0**. `j` defaults follow `w`. |
| `readConcern: { level: ... }` | `"local"`, `"available"`, `"majority"`, `"linearizable"`, `"snapshot"`. | Default `"local"`. `"linearizable"` only for single-doc reads on primary, adds latency. `"snapshot"` for multi-doc txns. |
| `readPreference` | `primary`, `primaryPreferred`, `secondary`, `secondaryPreferred`, `nearest`. | Default `primary`. Reading from secondaries = lower latency, possible staleness (EL). |
| `maxStalenessSeconds` | Cap how stale a secondary read may be. | Bound on EL staleness. |
| Causal consistency sessions | `startSession({causalConsistency:true})` → read-your-writes & monotonic guarantees across primary/secondary. | Opt-in per session. |

MongoDB is **CP** (primary-based; minority can't elect a primary → no writes). With `w:majority` + `readConcern:majority` it gives strong guarantees; relaxing read prefs/concerns moves it toward EL.

### 4.3 DynamoDB knobs

| Knob | Effect |
|---|---|
| `ConsistentRead: false` (default) | **Eventually consistent** read from any replica — ~half the cost, lower latency. (EL.) |
| `ConsistentRead: true` | **Strongly consistent** read — reflects all prior successful writes, higher latency, costs 2× RCU, *not available* during certain failures. (EC / CP-leaning.) |
| Transactions (`TransactWriteItems`/`TransactGetItems`) | ACID across items, serializable; higher cost. |
| Global Tables | Multi-region, **eventually consistent** (AP across regions; LWW conflict resolution). |

### 4.4 ZooKeeper / etcd / Consul (consensus stores)

| Tool | Consistency model | Notes |
|---|---|---|
| **ZooKeeper** | Linearizable **writes**; **sequentially consistent reads** by default (a read may be slightly stale). Use `sync()` then read for linearizable read. CP. | Zab protocol; majority quorum; minority unavailable for writes. |
| **etcd** | Linearizable reads & writes (Raft). CP. | `--consistency=l` (linearizable) vs `s` (serializable/local, faster). Default linearizable. |
| **Consul** | Raft for catalog; configurable read consistency: `default` (leader-leased), `consistent` (linearizable, extra round trip), `stale` (any server, fast/stale). | CP for writes; reads tunable along EL/EC. |

### 4.5 Spanner / CockroachDB / YugabyteDB (NewSQL, strongly consistent)

| System | Model | Mechanism |
|---|---|---|
| **Google Spanner** | External consistency (strict serializability). PC/EC (CP, and consistency-favoring in Else too). | Paxos per shard + **TrueTime** commit-wait. SLA up to 99.999%. |
| **CockroachDB** | Serializable isolation; near-linearizable. CP. | Raft per range + hybrid-logical clocks (HLC). |
| **YugabyteDB** | Strong (single-key linearizable, serializable txns). CP. | Raft per tablet + HLC. |

### 4.6 The PACELC classification table (the canonical reference)

| System | PACELC class | Reading |
|---|---|---|
| **DynamoDB (classic), Cassandra, Riak (default), ScyllaDB** | **PA/EL** | Under partition → Available; Else → Latency. (Tunable toward C.) |
| **Spanner, BigTable (single-cluster), HBase, etcd, ZooKeeper, MongoDB** | **PC/EC** | Under partition → Consistent; Else → Consistent (pays latency). |
| **MySQL/Postgres async replication** | **PA/EL**-ish (the replica side) | Replica can serve stale during partition; favors latency normally. |
| **PNUTS (Yahoo)** | **PC/EL** | Consistent under partition, but latency-favoring normally — the model PACELC was partly designed to capture. |
| **MongoDB (default w:majority)** | **PC/EC** | Often cited PC/EC; with relaxed read prefs behaves PC/EL. |
| **Cassandra tuned to QUORUM+QUORUM** | **PC/EC** (per-op) | Demonstrates the per-operation nature of the choice. |

> The PC/EL quadrant (consistent under partition, fast/stale otherwise) is the strongest evidence that CAP alone is insufficient — such systems are "CP" in CAP but behave very differently in normal operation than Spanner.

---

## 5. Code examples by use case

These are idiomatic, copy-adaptable Java-centric examples spanning **distinct** scenarios. Non-obvious lines are commented.

### 5.1 Cassandra — choosing CP vs AP *per query* (the per-operation point made concrete)

```java
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;

public class CassandraConsistencyDemo {
    public static void main(String[] args) {
        try (CqlSession session = CqlSession.builder().build()) {

            // --- AP / EL path: a view counter. We do NOT care about strict correctness.
            // ONE = ack from a single replica → lowest latency, stays available under partition.
            PreparedStatement incr = session.prepare(
                "UPDATE metrics.page_views SET views = views + 1 WHERE page_id = ?");
            session.execute(incr.bind("home")
                // counters are CRDT-like; ONE is fine, convergence is automatic
                .setConsistencyLevel(ConsistencyLevel.ONE));

            // --- CP / EC path: read an account balance. We DO care about correctness.
            // QUORUM on both read+write with RF=3 gives W+R>N → strong consistency.
            PreparedStatement readBal = session.prepare(
                "SELECT balance FROM bank.accounts WHERE acct_id = ?");
            ResultSet rs = session.execute(readBal.bind("acct-42")
                .setConsistencyLevel(ConsistencyLevel.QUORUM)); // majority must agree
            long balance = rs.one().getLong("balance");

            // --- Linearizable compare-and-set via Lightweight Transaction (Paxos).
            // "IF balance = ?" makes this a single-partition linearizable CAS.
            // Cost: ~4 round trips (Paxos prepare/propose). Use sparingly.
            PreparedStatement debit = session.prepare(
                "UPDATE bank.accounts SET balance = ? WHERE acct_id = ? IF balance = ?");
            ResultSet lwt = session.execute(debit.bind(balance - 100, "acct-42", balance)
                .setConsistencyLevel(ConsistencyLevel.QUORUM)
                .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)); // Paxos ballot
            boolean applied = lwt.one().getBoolean("[applied]"); // false ⇒ someone raced us
            if (!applied) {
                // retry/refetch: the CAS precondition failed → concurrent modification
            }
        }
    }
}
```

**Lesson:** the *same database* serves AP/EL for the counter and CP/EC for the money, chosen per statement. This is CAP-as-a-spectrum in one file.

### 5.2 MongoDB — money write that *refuses* under insufficient replication (CP on purpose)

```java
import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;

public class MongoMajorityWrite {
    public static void main(String[] args) {
        try (MongoClient client = MongoClients.create("mongodb://rs0/host1,host2,host3")) {
            MongoDatabase db = client.getDatabase("bank")
                // w:"majority" → write only acknowledged after a MAJORITY of the replica set
                // persists it. During a partition, the MINORITY side cannot reach majority,
                // so the write BLOCKS then fails → CP: we refuse rather than risk divergence.
                .withWriteConcern(WriteConcern.MAJORITY.withWTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
                // readConcern majority → never read data that could be rolled back
                .withReadConcern(ReadConcern.MAJORITY);

            MongoCollection<Document> accts = db.getCollection("accounts");
            try {
                accts.updateOne(new Document("_id", "acct-42"),
                                new Document("$inc", new Document("balance", -100)));
            } catch (MongoWriteConcernException e) {
                // We hit wtimeout: majority not reachable (likely a partition or election).
                // CORRECT behavior for money: surface the failure, do NOT assume success.
                System.err.println("Debit not durably committed; treat as FAILED, retry safely.");
            }
        }
    }
}
```

### 5.3 DynamoDB — toggling consistency at read time (EL vs EC) for cost vs correctness

```java
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Map;

public class DynamoConsistency {
    static final DynamoDbClient ddb = DynamoDbClient.create();

    // Catalog browse: stale-by-a-few-ms is fine, cheaper + faster → eventually consistent.
    static GetItemResponse browse(String sku) {
        return ddb.getItem(GetItemRequest.builder()
            .tableName("catalog")
            .key(Map.of("sku", AttributeValue.fromS(sku)))
            .consistentRead(false)      // EL: any replica, ~half the RCU cost
            .build());
    }

    // Inventory decrement check at checkout: must reflect latest writes → strongly consistent.
    static GetItemResponse checkoutRead(String sku) {
        return ddb.getItem(GetItemRequest.builder()
            .tableName("inventory")
            .key(Map.of("sku", AttributeValue.fromS(sku)))
            .consistentRead(true)       // EC: reflects all prior successful writes, 2× cost
            .build());
    }
}
```

### 5.4 etcd (via jetcd) — linearizable read for leader/lock coordination (CP)

```java
import io.etcd.jetcd.*;
import io.etcd.jetcd.options.GetOption;
import java.nio.charset.StandardCharsets;

public class EtcdLinearizable {
    public static void main(String[] args) throws Exception {
        try (Client client = Client.builder().endpoints("http://etcd:2379").build()) {
            KV kv = client.getKVClient();
            ByteSequence key = ByteSequence.from("/service/leader", StandardCharsets.UTF_8);

            // Default etcd reads are LINEARIZABLE: the read goes through the Raft leader
            // and confirms it still holds majority before returning → never stale.
            // During a partition, the minority etcd nodes will REFUSE this read (CP).
            var resp = kv.get(key).get();

            // For a faster, possibly-stale read (serializable / local) — explicit opt-in:
            var staleResp = kv.get(key, GetOption.builder().withSerializable(true).build()).get();
            //                                          ^ trades linearizability for latency (EL)
        }
    }
}
```

### 5.5 Application-level read-your-writes over an eventually-consistent store (session guarantee)

```java
/**
 * Pattern: you run an AP store (fast, eventually consistent) but UX requires that a user
 * always sees their OWN latest write (read-your-writes), without paying for global C.
 * Technique: pin the user's reads to the replica that absorbed their write for a short window,
 * OR track a version token and read until the replica's version >= the token.
 */
public class ReadYourWrites {
    // Returned by writes; opaque "you are at least this fresh" token (e.g., commit timestamp/LSN).
    record VersionToken(long lsn, String replicaHint) {}

    VersionToken write(String key, String value) {
        long lsn = store.put(key, value);            // returns log sequence number of this write
        return new VersionToken(lsn, store.lastReplica());
    }

    String readYourWrites(String key, VersionToken token) {
        // Prefer the replica that took the write; verify it has caught up to our LSN.
        // If not yet caught up, either wait briefly or fall back to a quorum read.
        for (int attempt = 0; attempt < 3; attempt++) {
            Replica r = store.replicaByHint(token.replicaHint());
            if (r != null && r.appliedLsn() >= token.lsn()) {
                return r.get(key);                   // guaranteed to include our own write
            }
            sleepMillis(20L << attempt);             // small backoff for replication to catch up
        }
        return store.quorumRead(key);                // last resort: strong read (pays latency)
    }
    // store/Replica/sleepMillis elided for brevity
    Store store; void sleepMillis(long m){try{Thread.sleep(m);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    interface Store { long put(String k,String v); String quorumRead(String k); String lastReplica(); Replica replicaByHint(String h);}
    interface Replica { long appliedLsn(); String get(String k);}
}
```

### 5.6 CRDT counter — conflict-free convergence under partition (AP done *correctly*)

```java
/**
 * A grow-only counter (G-Counter): each node increments only its own slot; the value is the
 * SUM across slots. Two partitioned sides can both increment with NO conflict; on merge we take
 * the element-wise MAX of slots. This is how AP systems avoid the LWW "lost update" trap.
 */
import java.util.*;
public final class GCounter {
    private final String nodeId;
    private final Map<String, Long> slots = new HashMap<>();
    public GCounter(String nodeId) { this.nodeId = nodeId; }

    public void increment(long by) {                       // local-only mutation → always available
        slots.merge(nodeId, by, Long::sum);
    }
    public long value() {                                  // observed value = sum of all slots
        return slots.values().stream().mapToLong(Long::longValue).sum();
    }
    public void merge(GCounter other) {                    // deterministic, commutative, idempotent
        other.slots.forEach((node, v) -> slots.merge(node, v, Long::max)); // element-wise MAX
    }
    // Increment on A and B during a partition, then merge() either direction → same result. No loss.
}
```

### 5.7 Resilience knob (Java/Resilience4j) — making a CP dependency’s unavailability *graceful*

```java
// When you depend on a CP store, partitions = it returns errors. Don't cascade the failure;
// degrade. Circuit breaker + fallback turns "store unavailable" into "serve cached/stale" where allowed.
import io.github.resilience4j.circuitbreaker.*;
import java.util.function.Supplier;

public class GracefulDegradation {
    final CircuitBreaker cb = CircuitBreaker.of("balance-svc",
        CircuitBreakerConfig.custom().failureRateThreshold(50)
            .waitDurationInOpenState(java.time.Duration.ofSeconds(5)).build());

    long balanceOrError(Supplier<Long> strongRead, Supplier<Long> cachedRead) {
        try {
            return cb.executeSupplier(strongRead::get); // CP store: may throw during partition
        } catch (Exception e) {
            // For MONEY we must NOT fabricate a number → rethrow / show "temporarily unavailable".
            throw new RuntimeException("Balance temporarily unavailable (consistency preserved)", e);
            // For NON-MONEY (e.g., profile name) you might instead: return cachedRead.get();
        }
    }
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Correctness / concurrency
- **Choose C-vs-A per *data class*, not per system.** Money/ledger/inventory-reservation → CP. Feeds/carts/sessions/counters/recommendations → AP. (See §8.)
- **Never use LWW for data you can't afford to lose.** LWW + clock skew = silent lost updates. Prefer CRDTs or app-merge for AP money-adjacent data, or just go CP.
- **Beware `w:1`/`ONE` "success" lies.** A success ack from one replica is not durable across failures. For anything important, require majority/journal.
- **Linearizability ≠ serializability.** If you need multi-row invariants under concurrency, you need *transactions* (serializable/strict-serializable), not just a linearizable single-key store.

### 6.2 Performance & latency (the PACELC/EL side)
- **Strong reads cost round trips.** Quorum/leader reads add 1+ RTT; cross-region they add 50–150 ms. Use `LOCAL_QUORUM`/local leases where global linearizability isn't required.
- **Tail latency:** slow replicas inflate quorum waits. Use **speculative retry / hedged requests** (Cassandra `speculative_retry`, gRPC hedging) to cut p99.
- **GC pauses masquerade as partitions.** A 3 s JVM stop-the-world pause can trigger leader elections and false failovers. Tune GC (G1/ZGC), size heaps, and set failure-detector timeouts *above* worst-case pause times.

### 6.3 Observability (what to measure)
- **Replication lag** (per replica, per region) — the EL staleness window. Alert when it exceeds your read-staleness budget.
- **Quorum failure rate / write-concern timeouts** — early partition signal.
- **Leader-election frequency / term churn** — flapping = network or GC trouble.
- **Stale-read rate** — instrument app-level reads against a known-fresh oracle where possible.
- **Hint queue depth / anti-entropy backlog** (Cassandra `nodetool tpstats`, hint metrics) — growing backlog = nodes falling behind = convergence at risk.

### 6.4 Testing & production hardening
- **Inject partitions in tests.** Tools: **Jepsen** (the gold standard for testing consistency claims — it partitions, crashes, and checks linearizability with a checker like Knossos/Elle), `tc`/`netem` (Linux traffic control to add loss/latency), **Toxiproxy**, **Chaos Mesh**, AWS FIS, Pumba (Docker).
- **Don't trust vendor C-claims; verify.** Jepsen has repeatedly found that systems claiming strong consistency violated it under partition (MongoDB, early Cassandra LWT, etcd, Redis Sentinel, Kafka, Elasticsearch — many have since fixed issues).
- **Set explicit timeouts and `wtimeout`s.** A CP write that blocks forever is an outage; bound it and handle the failure.
- **Idempotency keys for retries.** Under partition you often can't tell if a write succeeded; safe retry requires idempotency.
- **Fence with leases/tokens (fencing tokens).** A stale leader that "comes back" can corrupt data; use monotonic fencing tokens so storage rejects writes from a deposed leader.

### 6.5 Security & cost
- **Security:** quorum/consensus traffic crosses your network — encrypt inter-node (mTLS); a partition that's actually a compromised link shouldn't leak or accept forged messages.
- **Cost:** strong/consistent reads cost more (DynamoDB 2× RCU; cross-region quorum = egress + latency). Multi-region strong consistency (Spanner) is premium-priced. Eventual reads are the cost-saver — use them where staleness is acceptable.

### 6.6 Anti-patterns
- **"CA database" thinking** — believing you've escaped the tradeoff. You haven't; you've just disabled handling for the partition that will eventually happen.
- **Treating CAP as a one-time, system-wide switch.** It's per-operation and per-data.
- **Equating CP with "slow/unreliable."** CP systems can have excellent SLAs.
- **Using global strong consistency everywhere "to be safe"** — needless latency and cost; degrades UX and burns budget.
- **Ignoring the failure detector / timeout tuning** — the most common cause of *unnecessary* unavailability in CP systems.

---

## 7. Advanced topics & deep internals

### 7.1 Spanner and the "is it CP or CA?" debate (TrueTime deep dive)
Spanner is often marketed as "beating CAP." It does not. Spanner is **CP** (PC/EC in PACELC). Its trick is making partitions *rare and short* via Google's private network, and using **TrueTime** to assign globally consistent timestamps. TrueTime returns an interval `[earliest, latest]` with uncertainty `ε` (a few ms). On commit, Spanner does **commit-wait**: it waits until `now.earliest > commit_timestamp` (≈ `2ε`) so the timestamp is unambiguously in the past everywhere → **external consistency** (strict serializability). The latency cost (commit-wait + Paxos quorum) is the EC tax. During an actual partition, the minority side of each Paxos group becomes unavailable — pure CP. Brewer's own paper concedes Spanner is "effectively CA" only in the sense that partitions are so rare its *operational* availability stays at 5 nines; *formally* it is CP.

### 7.2 Harvest and Yield (Fox & Brewer, 1999)
A more nuanced framing than binary C/A:
- **Yield** = fraction of requests answered (probability of a non-error response). ≈ availability, but continuous.
- **Harvest** = fraction of the *complete* data reflected in a given answer (e.g., a search that returns 90% of the corpus because one shard is partitioned away).
Search engines famously trade harvest for yield: return partial results rather than fail. This decomposes CAP into tunable, per-request degradation — a powerful design lens beyond "C or A."

### 7.3 Why partition tolerance can't be traded away (formal nuance)
Gilbert & Lynch's proof constructs an *execution* where, under message loss, an available+linearizable register would have to return a stale value, contradicting linearizability. The asynchronous-model result is absolute; the *partially synchronous* model (with clocks/timeouts) admits a register that is linearizable and available *when there is no partition* but must sacrifice availability during one — i.e., real systems. This is precisely why "the choice only bites during partitions" and PACELC's Else branch governs the rest.

### 7.4 PACELC's PC/EL quadrant — why it exists
A system can refuse to diverge under partition (PC) yet, in normal operation, prefer fast local reads that may be slightly stale (EL). Yahoo's **PNUTS** is the canonical example, offering **timeline (per-record) consistency**: a record always moves forward through its write history (no rollbacks), reads may be stale but never out-of-order. This is *strong-ish* yet *low-latency*, occupying a quadrant CAP literally cannot express — the original motivation for PACELC.

### 7.5 Bounded staleness & monotonic guarantees as a middle path
Azure Cosmos DB exposes **five** named consistency levels — *Strong, Bounded Staleness, Session, Consistent Prefix, Eventual* — letting you dial the EL/EC tradeoff per request with explicit staleness bounds (e.g., "at most `K` versions or `T` seconds behind"). This productizes the spectrum from §2.4. *Session* consistency (read-your-writes within a session) is the default and the sweet spot for most apps.

### 7.6 Sticky availability vs. CAP availability
PACELC's "A" (and CAP's) is strict. Many systems offer **"sticky available"**: a client that stays connected to the *same* replica gets a consistent (causal) view and never errors, even under partition — but a client that *switches* replicas may not. Causal consistency is the strongest model that can be *totally available* under partition (per "Highly Available Transactions," Bailis et al.) — a key result: you don't have to drop all the way to eventual to keep availability.

### 7.7 Clock skew and the danger of LWW
LWW resolves conflicts by timestamp. If clocks skew (NTP drift of tens of ms, VM pauses, leap seconds), a *logically newer* write can carry an *older* timestamp and be discarded → silent data loss with no error. Mitigations: NTP discipline, **hybrid logical clocks (HLC)** (combine physical time with a logical counter so causality is respected even under skew — used by CockroachDB/Yugabyte), or avoid LWW entirely.

### 7.8 Read-after-write inside CP systems isn't automatic for *replica reads*
Even in a CP system, if you allow reads from *followers* (for scale), a follower may lag the leader. ZooKeeper reads are *sequentially* consistent, not linearizable, for this reason — you must call `sync()` first for a linearizable read. Always check whether your "strongly consistent DB" gives linearizable *reads* or only writes.

---

## 8. Tradeoffs & decision frameworks

### 8.1 CP vs AP — the core decision table

| Dimension | Choose **CP** (sacrifice availability under partition) | Choose **AP** (sacrifice consistency under partition) |
|---|---|---|
| Data type | Money, ledgers, inventory reservations, unique IDs, locks, config/leader election, auth tokens | Carts, feeds, view counts, likes, sessions, recommendations, telemetry, caches |
| Failure behavior | Refuse/block on minority side | Always answer, reconcile later |
| Risk if wrong | Double-spend, oversell, split-brain corruption | Stale read, temporary disagreement (acceptable) |
| Latency (normal) | Higher (quorum/leader/commit-wait) | Lower (local replica) |
| Examples | Spanner, etcd, ZooKeeper, MongoDB(majority), CockroachDB | Cassandra, DynamoDB(eventual), Riak, Cosmos(eventual) |

### 8.2 Money vs non-money rule of thumb
- **Money / correctness-critical:** It is far better to **fail loudly** (CP) than to serve a wrong balance and allow a double-spend. Pattern: CP store + idempotency + retries + "temporarily unavailable" UX. *Refusing is recoverable; corruption often isn't.*
- **Non-money / engagement data:** It is far better to **stay up** (AP) and show slightly stale likes/feed than to error. Pattern: AP store + CRDT/merge + read-your-writes for the author's own actions.
- **The hybrid reality:** most products use *both* — a CP system of record (Postgres/Spanner) for orders/payments, and AP stores (Cassandra/Redis/DynamoDB) for everything around it. This is the dominant production architecture.

### 8.3 "Use when / avoid when"
- **Use CP when:** correctness > availability; you can tolerate brief minority-side unavailability; you have an odd replica count for clean majorities; partitions are rare (good network).
- **Avoid CP when:** you need every write to succeed always (offline-first, IoT at the edge, high write availability across regions), and the data tolerates eventual convergence.
- **Use AP when:** availability/latency > immediate consistency; data is mergeable (CRDT) or loss-tolerant; geo-distributed writes are common.
- **Avoid AP when:** invariants must never be violated (no oversell, no negative balance, no duplicate unique key) — unless you add coordination back (LWT/transactions) for those specific operations.

### 8.4 Alternatives / escape hatches (not "more CAP," but ways around the bind)
- **Partition per-operation:** AP by default, CP only for the critical op (Cassandra LWT, DynamoDB transactions).
- **CRDTs:** get availability *and* convergence for the data types they cover — no manual conflict resolution.
- **Make partitions rarer:** invest in network (Spanner's approach) so the CP tax is paid almost never.
- **Geo-partition the data, not the consistency:** keep each user's data primarily in one region (home-region pattern) so most ops are local-strong; only cross-region ops pay the tax.

---

## 9. Failure modes & debugging

### 9.1 Common production failure modes
1. **Split-brain (AP misconfig or bad CP fencing):** two leaders both accept writes → divergent data. *Diagnosis:* two nodes both reporting "I am primary"; conflicting versions on merge. *Fix:* proper quorum (odd counts), fencing tokens, STONITH.
2. **Minority-side write outage (expected CP behavior, surprising to ops):** half your nodes can't write during a partition; pages fire. *Diagnosis:* write-concern timeouts only on a subset; election logs. *This is correct* — the fix is capacity/region planning and clear runbooks, not "make it AP."
3. **Stale reads breaking invariants (AP):** read of an old value lets a duplicate/oversell through. *Diagnosis:* business invariant violated without an error; replication-lag spike correlated. *Fix:* route that op to CP, add LWT/transaction, or use a version check.
4. **GC-pause false failover:** a long JVM pause makes a healthy leader look dead → election storm, latency spikes, dropped throughput. *Diagnosis:* GC logs show multi-second pauses aligned with election/term churn. *Fix:* ZGC/Shenandoah, heap tuning, raise failure-detector thresholds.
5. **LWW silent data loss:** an update vanishes with no error. *Diagnosis:* audit log shows a write that never appears; timestamps reveal skew. *Fix:* HLC, CRDT, or CP for that data.
6. **Quorum loss from losing 2 of 3 in one AZ:** an AZ outage kills majority → whole shard unavailable. *Diagnosis:* RF placement on a single AZ. *Fix:* spread replicas across ≥3 AZs/racks (rack-aware/NetworkTopologyStrategy).

### 9.2 Tools & commands
- **Cassandra:** `nodetool status` (up/down, ownership), `nodetool tpstats` (dropped messages, hint backlog), `nodetool gossipinfo`, `nodetool repair` (anti-entropy), tracing (`TRACING ON` in cqlsh) to see which replicas answered and at what CL.
- **MongoDB:** `rs.status()` (member states, election info), `db.serverStatus().repl`, `rs.printSecondaryReplicationInfo()` (lag), `db.adminCommand({replSetGetStatus:1})`.
- **etcd:** `etcdctl endpoint status --cluster -w table` (leader, raft index per node), `etcdctl endpoint health`, `etcdctl member list`.
- **ZooKeeper:** four-letter words `mntr`, `stat`, `srvr` (leader/follower, latency, outstanding requests); check `zk_followers`/`zk_synced_followers`.
- **OS/network:** `ping`/`mtr` (loss/latency), `ss -s`, `tc qdisc show` (is netem injecting loss?), `dmesg` (NIC resets), cloud VPC flow logs.
- **JVM:** GC logs (`-Xlog:gc*`), `jstat -gcutil`, async-profiler — to catch pause-induced false partitions.

### 9.3 Real-world incidents (illustrative, well-documented patterns)
- **GitHub 2018 (24h+ degradation):** a 43-second cross-coast network partition triggered an Orchestrator/MySQL failover that promoted a leader on the wrong side; conflicting writes accumulated and reconciliation took a day. Textbook CP-system partition handling gone wrong + the cost of split histories.
- **Jepsen findings (MongoDB, Cassandra LWT, etcd, Redis-Sentinel, Kafka, Elasticsearch, etc.):** numerous documented cases where systems lost acknowledged writes or violated their advertised consistency under partition — most subsequently fixed. The lesson: *verify consistency claims empirically.*
- **Roblox 2021 (73-hour outage):** a Consul (CP, Raft) cluster under a new streaming feature + contention experienced cascading failure; the CP store's coordination layer became the single point of unavailability — a reminder that your coordination substrate's CAP behavior governs everything built on it.
- **Generic AP horror story:** an e-commerce "inventory = 1, oversold to 50 buyers" because availability-favoring reads served stale stock during a flash sale partition — the classic case for putting *inventory reservation* on CP.

### 9.4 Debugging playbook (quick)
1. Confirm a partition exists (network tools, not just app errors).
2. Identify majority vs minority side (which side has quorum?).
3. For CP: expect minority unavailability — verify it's *only* the minority and that no split-brain occurred (fencing intact?).
4. For AP: expect divergence — capture conflicting versions *before* auto-resolution discards them.
5. Check GC/pauses to rule out a *false* partition.
6. After heal: verify convergence (anti-entropy/repair completed, lag back to baseline) and audit for lost writes.

---

## 10. Interview drill

**Q1. State the CAP theorem precisely. What does each letter mean?**
*Model answer:* In the presence of a network partition, a distributed data store cannot be both linearizable (C) and available (A); it tolerates partitions (P) by choosing one. C = linearizability (behaves like one up-to-date copy, real-time ordered). A = every non-failing node answers every request (no errors/timeouts). P = continues operating despite arbitrary message loss between nodes.
- *Probe: Why is "pick 2 of 3" wrong?* Because P isn't optional — partitions happen whether you like it or not — so the real choice is CP vs AP for the duration of the partition.
- *Probe: Is the choice global?* No. It's per-operation and per-data; a system can be CP for some ops, AP for others (e.g., Cassandra per-query CL).
- *Probe: What model is CAP's C?* Linearizability — not ACID-C and not serializability.

**Q2. What's the difference between CAP-consistency, ACID-consistency, and serializability?**
*Model:* CAP-C = linearizability (replica agreement + real-time order on single objects). ACID-C = transactions preserve declared invariants/constraints. Serializability = transactions equivalent to *some* serial order (no real-time guarantee); strict serializability adds real-time order.
- *Probe: Can a system be linearizable but not serializable?* Yes — linearizable single-key store with no multi-key transactions.
- *Probe: What does Spanner give?* Strict serializability (external consistency) via TrueTime.

**Q3. Why is partition tolerance not optional?**
*Model:* A partition is a property of the unreliable network, not a feature; you can't disable packet loss. A "CA" system only exists by *assuming* partitions never occur, which is false for real distributed systems. So genuinely distributed stores are CP or AP.
- *Probe: When is "CA" honest?* A single-node DB — no inter-replica network to partition.

**Q4. Explain PACELC and why CAP alone is insufficient.**
*Model:* PACELC: if Partition → choose A or C; Else (normal) → choose L (latency) or C (consistency). CAP says nothing about the common, no-partition case, where consistency still costs latency (quorum/leader/commit-wait). PACELC captures that, plus the PC/EL quadrant (PNUTS) that CAP can't express.
- *Probe: Give a PC/EL system.* PNUTS / timeline consistency: consistent under partition, fast-but-stale normally.
- *Probe: Which branch dominates operationally?* The Else branch — partitions are rare; you live in EL/EC ~99.9% of the time.

**Q5. Classify Cassandra, Spanner, ZooKeeper, MongoDB in CAP and PACELC.**
*Model:* Cassandra = AP / PA-EL (tunable to PC-EC via QUORUM/QUORUM, LWT). Spanner = CP / PC-EC (TrueTime). ZooKeeper = CP / PC-EC (Zab; reads sequentially consistent unless `sync()`). MongoDB = CP / PC-EC by default (w:majority), PC-EL with secondary reads.
- *Probe: Is Spanner "CA"?* No — it's CP; partitions are just rare enough that operational availability is 5 nines. Marketing ≠ formal model.
- *Probe: How does Cassandra change quadrant per query?* Consistency level per statement (ONE vs QUORUM vs SERIAL/LWT).

**Q6. How does a quorum system guarantee strong consistency?**
*Model:* With N replicas, require W + R > N so every read set intersects every write set (pigeonhole) → a read always sees the latest committed write. Majority quorum (⌊N/2⌋+1) for both is the common balanced choice.
- *Probe: What does W=1,R=1 give?* No overlap → eventual consistency, lowest latency.
- *Probe: Why odd N?* Clean majorities and tolerate ⌊N/2⌋ failures without ties.

**Q7. Walk through what a CP system does during a partition.**
*Model:* Minority side can't reach quorum → blocks/refuses writes (and linearizable reads) → unavailable. Majority side elects/keeps a leader, commits with quorum, serves clients. On heal, the old leader steps down, truncates uncommitted entries, catches up — no divergence.
- *Probe: What prevents split-brain?* Majority quorum + fencing tokens; a deposed leader's writes are rejected.
- *Probe: What's the cost?* Minority unavailability + election window (write pause).

**Q8. (Senior-signal) You're storing account balances and processing debits. CP or AP, and how do you handle a partition?**
*Model:* CP. Correctness dominates — a stale read enabling a double-spend is unacceptable; refusing is recoverable, corruption often isn't. Use a strongly consistent store (Spanner/CockroachDB/Postgres-primary or Mongo w:majority), wrap debits in transactions/LWT with idempotency keys, bound timeouts, and on partition surface "temporarily unavailable" rather than guess. Reconcile via the system of record.
- *Probe: How do you keep latency acceptable?* Home-region the account so most ops are local-strong; only cross-region ops pay the WAN tax.
- *Probe: Idempotency?* Under partition you can't always tell if a write committed; idempotency keys make retries safe.

**Q9. (Senior-signal) Design the data layer for a social feed with likes and a shopping cart. Justify your CAP choices per component.**
*Model:* Feed & likes → AP (Cassandra/DynamoDB), CRDT counters for likes (no lost increments), read-your-writes so authors see their own posts/likes; staleness for others is fine. Cart → AP with CRDT-set merge (union items on conflict — Dynamo's original use case) for max availability, but *checkout/payment/inventory reservation* → CP with transactions to prevent oversell/double-charge. Justification: engagement data favors availability/latency; money/inventory favors correctness. This split is the standard hybrid architecture.
- *Probe: Why CRDT for cart not LWW?* LWW would drop concurrently-added items; CRDT union preserves them.
- *Probe: Where's the consistency boundary?* At checkout — the handoff from AP browsing to CP transaction.

**Q10. (Senior-signal) A teammate says "let's use a strongly consistent global DB for everything to avoid bugs." Critique.**
*Model:* Over-applying CP imposes WAN latency and higher cost on every operation, hurts UX (slower, and unavailable on minority during partitions), and over-provisions correctness where stale data is harmless (feeds, counters). Better: CP only for invariant-critical data; AP/EL elsewhere; consider bounded-staleness/session consistency as a middle path. Match the consistency model to each data class's actual requirement; don't pay for guarantees you don't need.
- *Probe: What middle grounds exist?* Causal/session consistency, bounded staleness (Cosmos), read-your-writes — strong enough for UX, cheap enough for scale.
- *Probe: Cost angle?* Strong reads = 2× cost (DynamoDB) / cross-region egress; multi-region strong = premium.

**Q11. How do GC pauses interact with CAP?**
*Model:* A long stop-the-world GC freezes a node; to peers it's indistinguishable from a partition/crash, triggering failovers and elections — *unnecessary* CP unavailability and latency spikes. Mitigate with low-pause collectors (ZGC/Shenandoah), heap tuning, and failure-detector timeouts set above worst-case pause time.
- *Probe: Why can't peers tell pause from partition?* The asynchronous-network ambiguity at CAP's root — no reply ≠ known cause.

**Q12. How do you *verify* a database's consistency claims?**
*Model:* Empirically — with Jepsen: inject partitions/crashes/clock skew while issuing operations, record a history, and check it against a consistency model (linearizability via Knossos/Elle). Don't trust marketing; many systems have failed Jepsen and been fixed.
- *Probe: What's Elle?* A Jepsen checker that infers transactional anomalies (cycles) efficiently from observed histories.

---

## 11. Glossary

- **ACID:** Atomicity, Consistency, Isolation, Durability — transaction guarantees. ACID-C (constraints valid) differs from CAP-C (linearizability).
- **Anti-entropy:** background reconciliation comparing replicas (often Merkle trees) to converge them.
- **AP:** Availability + Partition tolerance; sacrifices consistency under partition.
- **Availability (CAP):** every non-failing node answers every request, always.
- **Bounded staleness:** a consistency level guaranteeing reads are at most K versions / T seconds behind.
- **Causal consistency:** causally related ops seen in order by all; strongest model that stays available under partition.
- **Commit-wait:** Spanner's waiting out TrueTime uncertainty before committing, ensuring external consistency.
- **Consensus:** agreement on a single value among nodes despite failures (Paxos/Raft/Zab).
- **Consistency (CAP):** linearizability — behaves like one up-to-date copy, real-time ordered.
- **CP:** Consistency + Partition tolerance; sacrifices availability under partition.
- **CRDT:** Conflict-free Replicated Data Type; merges concurrent updates deterministically.
- **Eventual consistency:** replicas converge if writes stop; no timing/ordering guarantee meanwhile.
- **External consistency:** Spanner's term for strict serializability (real-time order across transactions).
- **Failure detector:** the timeout-based mechanism that decides a peer is "down."
- **Fencing token:** a monotonically increasing token storage uses to reject writes from a deposed leader (prevents split-brain corruption).
- **FLP impossibility:** in a fully async network, no consensus can guarantee termination if a node may fail.
- **Gossip protocol:** epidemic-style peer-to-peer state dissemination (used by Cassandra/Dynamo).
- **Harvest / Yield:** fraction of data reflected / fraction of requests answered — a continuous refinement of C/A.
- **HLC (Hybrid Logical Clock):** physical time + logical counter; preserves causality under clock skew.
- **Hinted handoff:** temporarily storing writes for an unreachable replica to replay later.
- **Linearizability:** single-object real-time-ordered atomic consistency = CAP-C.
- **LWT (Lightweight Transaction):** Cassandra's Paxos-based linearizable compare-and-set.
- **LWW (Last-Write-Wins):** keep highest-timestamp version; can silently lose data.
- **MVCC:** multiple row versions enabling snapshot reads without blocking writers.
- **Network partition:** nodes split into groups that can't communicate.
- **Node / replica:** a machine/process holding a copy of data.
- **PACELC:** Partition → A/C; Else → L/C — extends CAP to the no-partition case.
- **Paxos:** classic consensus protocol (Lamport).
- **Quorum:** minimum participating nodes; majority = ⌊N/2⌋+1; strong-consistency rule W+R>N.
- **Raft:** leader-based consensus algorithm (understandable Paxos alternative).
- **Read repair:** fixing stale replicas during a read when versions disagree.
- **Read-your-writes:** session guarantee that you see your own latest write.
- **Replication lag:** how far behind a replica is from the latest write (the EL staleness window).
- **Sequential consistency:** all clients agree on one order consistent with program order, but not real time.
- **Serializability:** transactions equivalent to some serial order; strict serializability adds real-time order.
- **Split-brain:** two leaders both accepting writes → divergence/corruption.
- **Strict serializability:** serializability + linearizability (real-time order across transactions).
- **TrueTime:** Spanner's bounded-uncertainty global clock (GPS + atomic clocks).
- **Vector clock:** version-stamp vector revealing causal vs concurrent updates.
- **W / R / N:** write quorum / read quorum / replication factor.
- **Write concern (`w`):** how many replicas must ack a write (MongoDB).
- **Zab:** ZooKeeper Atomic Broadcast — ZooKeeper's leader-based consensus.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one-screen recap)
- **CAP:** under a partition, choose **C (linearizability)** or **A (every node answers)**. P is *forced* (network partitions happen). Real choice = **CP vs AP**, *per operation*, not a one-time switch.
- **CAP-C ≠ ACID-C ≠ serializability.** CAP-C = linearizability (one up-to-date copy, real-time order).
- **PACELC:** **P**→A|C; **E**lse→**L**|C. Consistency costs *availability* under partition and *latency* otherwise. You live in the Else branch ~99.9% of the time.
- **Quorum:** strong iff **W + R > N**; majority = ⌊N/2⌋+1. W=1,R=1 → eventual.
- **Classifications:** Cassandra/DynamoDB/Riak = **PA/EL** (tunable). Spanner/etcd/ZooKeeper/MongoDB(majority)/Cockroach = **PC/EC**. PNUTS = **PC/EL**.
- **CP signature:** minority freezes, majority serves. **AP signature:** everyone answers, reconcile later (CRDT > app-merge > LWW).
- **Money → CP** (fail loud, idempotent retries). **Engagement → AP** (CRDT, read-your-writes). Most systems = **hybrid**.
- **Gotchas:** GC pause looks like a partition; LWW silently loses data; ZK reads are sequential not linearizable (use `sync()`); "CA" is a myth for distributed stores; verify consistency with **Jepsen**.
- **Numbers:** cross-region RTT ~50–150 ms (EC tax); DynamoDB strong read = 2× cost; TrueTime ε ~few ms; Cassandra hint window default 3 h; Mongo default `w:"majority"` (5.0+).
- **Tools:** Jepsen/Elle, netem/Toxiproxy/Chaos Mesh; `nodetool status/tpstats`, `rs.status()`, `etcdctl endpoint status`, ZK `mntr`.

### Self-test (no answers — active recall)
1. Precisely state CAP and explain why "pick any 2 of 3" is misleading. What is the *correct* framing?
2. A colleague says their two-node, no-partition-handling cluster is "CA." Why is that not a real distributed-systems option, and what will actually happen on a partition?
3. Distinguish CAP-consistency, ACID-consistency, and serializability with one concrete example each. Which one does Spanner provide, and via what mechanism?
4. You have N=5 replicas. Give a (W, R) that is strongly consistent and one that is eventually consistent, and justify each using the quorum invariant. What happens to write availability if 3 of the 5 are partitioned away?
5. Classify Cassandra and ZooKeeper in both CAP and PACELC, and explain how each could be moved into a different PACELC quadrant.
6. You're designing checkout for an e-commerce site: cart, inventory reservation, payment, and order confirmation. Assign CP or AP to each and justify, including how you handle a partition during a flash sale.
7. Explain how a 4-second JVM GC pause could cause an *unnecessary* outage in a CP system, and list two mitigations.
8. Your AP store uses LWW and a user reports a saved setting "disappeared." Walk through the most likely root cause and how you'd fix the data model so it can't recur.

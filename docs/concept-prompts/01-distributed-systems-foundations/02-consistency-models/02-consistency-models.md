# Consistency Models

> **Concept area:** Distributed Systems Foundations
> **Subtopic:** Consistency Models
> **Reader profile:** A senior Java/JVM backend engineer who wants to fully master consistency models — from first principles to deep internals — well enough to design with them, operate and debug them in production, teach them, and answer any interview question on them.

---

## 1. Overview & where it fits

### What a consistency model *is*

A **consistency model** is a *contract* between a data store and the programs that use it. It specifies precisely **which results a read is allowed to return**, given the writes that have happened (or are happening concurrently). Equivalently, it constrains the set of *legal orderings* of operations that the system may pretend occurred.

That is the entire idea in one sentence: **a consistency model defines the set of permitted outcomes for concurrent reads and writes over replicated or cached data.** Everything else — linearizability, causal, eventual, bounded staleness — is just a *named point* on the spectrum from "behaves exactly like one copy with no concurrency" to "behaves like a loose collection of copies that eventually agree."

A few framing notes before we go deep:

- The model is a **guarantee**, not an implementation. Two completely different databases (one using Raft, one using quorum reads) can both offer *linearizability*. The model is what the client can *rely on*; the mechanism is how it's achieved.
- The model is about **observable behavior**. You cannot "see" replication internally; you can only observe it through the answers your reads return. A consistency model is a statement about those answers.
- Stronger models are **easier to program against** but **more expensive** (more coordination, higher latency, lower availability under partition). Weaker models are **cheaper and more available** but **push complexity onto the application**. This tension is the whole subject.

### The problem it solves

The moment your data lives in **more than one place** — replicas for fault tolerance, caches for speed, geographically distributed copies for latency, or multiple cores reading shared memory — you have a problem: **the copies can disagree.** A write lands on one copy; a read hits another that hasn't seen it yet.

> **Replica** — a copy of a piece of data (or a whole dataset) kept on another node, disk, region, or memory location, maintained to survive failures, serve reads closer to clients, or scale throughput.

Without a consistency model, the behavior of "read after write" is *undefined* in a distributed setting. A consistency model nails down what the system promises. It answers questions like:

- If I write `x=5` and then immediately read `x`, am I guaranteed to see `5`? (Read-your-writes.)
- If two clients write concurrently, do all observers agree on who "won"? (Total order / linearizability.)
- If I see effect B, am I guaranteed to also see its cause A? (Causal consistency.)
- Can a reader ever go *backward in time* — see a newer value, then an older one? (Monotonic reads forbid this.)

### When you reach for it

You "reach for" a consistency model implicitly every time you choose a database mode, a cache strategy, a replication setting, or a memory ordering primitive. Concretely:

- **Choosing a database / configuration:** DynamoDB strong vs. eventual reads; Cassandra `ONE` vs `QUORUM` vs `ALL`; MongoDB `readConcern`/`writeConcern`; Cosmos DB's five named levels; Spanner's external consistency. These are *consistency model knobs*.
- **Designing a feature:** A bank ledger needs linearizable transfers; a "like" counter tolerates eventual consistency; a user editing their own profile needs at least read-your-writes.
- **Caching:** Putting Redis or a CDN in front of a database silently downgrades your consistency unless you reason about invalidation.
- **Concurrent JVM programming:** The **Java Memory Model (JMM)** is literally a consistency model for shared memory; `volatile`, `synchronized`, and `java.util.concurrent.atomic` are how you select stronger guarantees.

### One-paragraph mental model

Imagine every replica keeps its own timeline of events. A consistency model is the set of rules that say *which combined "story" of what happened is allowed to be told to clients.* The strongest model (**linearizability**) forces everyone to agree on one global timeline that respects real wall-clock order — as if there were a single copy. Weaker models relax this: maybe everyone agrees on an order but it need not match real time (**sequential**); maybe only cause-and-effect order is preserved (**causal**); maybe each individual client just sees a sane, non-contradictory view of its *own* actions (**client-centric** guarantees like read-your-writes); and at the weakest end, the only promise is that *if writes stop, all replicas eventually converge to the same value* (**eventual**). The art is picking the weakest model your application can tolerate, because weaker is cheaper, faster, and more available.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Every term a newcomer might not know is defined inline the first time it appears.

### 2.1 The objects of study: operations, histories, and replicas

A **register** is the simplest shared object: a single variable holding a value, supporting `write(x, v)` and `read(x) → v`. Most consistency-model theory is stated over registers because they're the minimal interesting case; the results generalize to richer objects (queues, sets, key-value stores).

An **operation** has a *start time* (when the client issues it) and an *end time* (when the client receives the response). Crucially, in a distributed system **operations take time** — they are *intervals*, not instants. This is the source of almost all subtlety: two operations can *overlap* in real time.

> **Concurrent operations** — two operations are *concurrent* if their time intervals overlap; neither finished before the other started. When operations are concurrent, the consistency model gets to choose (within its rules) how to order them. When they are *not* concurrent (one fully finished before the other began), strong models require respecting that real-time order.

A **history** is the recorded sequence of operation invocations and responses across all clients. Reasoning about consistency = reasoning about which histories a model permits. We often draw histories as horizontal timelines, one per client/process.

> **Process / client / thread** — an independent agent issuing operations. In this document "process," "client," and "thread" are used interchangeably to mean *one sequential stream of operations*. A consistency model treats each process's own operations as ordered (it issues them one at a time).

### 2.2 Two foundational papers' worth of intuition

Two ideas from the literature anchor everything:

1. **Lamport's "happens-before" (1978)** gives us **causality** without clocks (Section 2.6).
2. **Herlihy & Wing's linearizability (1990)** gives us the gold standard of "behaves like a single copy" (Section 2.3).

### 2.3 The strongest single-object model: linearizability (a.k.a. atomic consistency, strict-ish)

**Linearizability** says: each operation *appears to take effect instantaneously at some single point in time between its start and its end*, and the resulting single-threaded order of those points is consistent with a sequential specification of the object. Informally: **the system behaves as if there is exactly one copy of the data, and every operation happens atomically at one moment.**

Three properties define it:
- **Real-time ordering:** if operation A finishes before operation B starts (in real wall-clock time), then A must appear before B in the chosen total order.
- **Single total order:** there is *one* order all clients agree on.
- **Each read returns the value of the most recent write** in that order.

Example history (✓ linearizable):

```
Client 1:  write(x,1)|----------|
Client 2:         |--- read(x)→1 ---|
```
Here `write(x,1)` and `read(x)` overlap, and the read sees `1`. We can place the write's instant before the read's instant. Legal.

Example history (✗ NOT linearizable):

```
Client 1:  write(x,1)|--|
Client 2:                 |read(x)→0|   <- starts AFTER write finished, sees old value
```
The read started strictly after the write completed, yet returned the stale value `0`. No single timeline explains this. **Illegal under linearizability.**

> **"Strict consistency"** is a theoretical extreme: a read *instantly* reflects the most recent write across the entire system using a global clock. It's unimplementable in a real distributed system (no instantaneous global clock exists) and is essentially the single-CPU mental model. Linearizability is the *achievable* approximation — the practically strongest model — and in everyday usage people often say "strong consistency" to mean linearizability.

**Key fact:** linearizability is a **single-object** (per-key) property and it is **composable / local**: if every individual object is linearizable, the whole system is linearizable. This composability is what makes it so valuable — you can reason object by object.

### 2.4 Adding multi-object ordering: sequential consistency

**Sequential consistency** (Lamport, 1979) relaxes the *real-time* requirement. It says: there exists *some* single total order of all operations such that (a) every process's operations appear in that order **in the order that process issued them** (program order is preserved per process), and (b) reads return the last write in that order. But the global order **need not match real time** across processes.

Difference from linearizability in one line: **linearizability = sequential consistency + respect real-time order between non-overlapping operations.**

Example: P1 writes `x=1` then `x=2`; P2 reads. Under sequential consistency, all processes must agree P1 did `1` before `2` (program order), but P2 might "lag" and observe `1` even slightly after P1's `2` completed in real time — as long as everyone's view is mutually consistent. This is the classic model many textbook multiprocessors and the *idealized* shared-memory model use.

> **Program order** — the order in which a single process issues its operations. Both sequential and causal consistency promise to preserve each process's own program order in the global story; they differ on what *else* they preserve.

Sequential consistency is **not composable**: combining two individually sequentially-consistent objects need not yield a sequentially-consistent whole. (Linearizability fixes exactly this.)

### 2.5 The two halves: data-centric vs client-centric models

The literature splits consistency models into two families. Knowing this split prevents a huge amount of confusion.

- **Data-centric models** describe the guarantee *across all clients simultaneously* — a global statement about the data store. Linearizability, sequential, and causal consistency are data-centric: they constrain the single shared story everyone sees.
- **Client-centric (session) models** describe guarantees *for a single client's session*, even when the underlying store is only eventually consistent globally. They are weaker promises that are far cheaper to provide and are often exactly what users *perceive*. The four canonical ones (from Tanenbaum & van Steen) are: **read-your-writes**, **monotonic reads**, **monotonic writes**, and **writes-follow-reads**.

> **Session** — a logical scope of interaction for one client (often one login, one connection, or a token the client carries). Client-centric guarantees hold *within* a session and may be lost if the client starts a fresh session on a different replica without carrying its context.

### 2.6 Causality and causal consistency

**Causality** captures "this happened because of that." Lamport's **happens-before** relation (written `→`) is defined by three rules:

1. If `a` and `b` are in the same process and `a` comes first, then `a → b`.
2. If `a` is the send of a message and `b` is its receipt, then `a → b`.
3. Transitivity: if `a → b` and `b → c`, then `a → c`.

If neither `a → b` nor `b → a`, the events are **concurrent** (causally independent).

> **Vector clock** — a data structure (an array/map of per-process counters) that *tracks* the happens-before relation precisely. Each process keeps a counter for every process; on each event it bumps its own counter and attaches the whole vector to outgoing messages; on receipt it takes the element-wise max. Comparing two vectors tells you whether one happened-before the other or they're concurrent. Vector clocks are how systems *implement* causal consistency and *detect* conflicts.

> **Lamport timestamp** — a single scalar counter (cheaper than a vector clock) that produces a total order consistent with happens-before, but **cannot** distinguish concurrent from ordered events. Good for ordering; insufficient for detecting concurrency.

**Causal consistency** guarantees: **operations that are causally related are seen by every process in the same (causal) order; concurrent operations may be seen in different orders by different processes.** It's the strongest model that can be provided while remaining *available* under network partitions (a deep result, Section 7).

Causal example:
```
P1: write(post, "I lost my keys")          (event A)
P1: write(comment, "found them!")           (event B, A→B because same process & B references A)
P2: must see A before B (never B without A)
P3: a concurrent write to an unrelated key may be seen in any order relative to A
```
Causal consistency is what prevents the dreaded "you see the reply but not the original message" anomaly.

### 2.7 The client-centric quartet (precise definitions)

Within a session, with values written by writes `W` and observed by reads `R`:

| Model | Plain definition | Anomaly it prevents |
|---|---|---|
| **Read-your-writes (RYW)** | After a client writes a value, every subsequent read *by that client* returns that value or a newer one — never an older one. | You update your profile photo, refresh, and see the *old* photo. |
| **Monotonic reads (MR)** | If a client reads a value, any later read by that client returns that value or a newer one — reads never go backward in time. | You see message #5, refresh, and message #5 has "disappeared." |
| **Monotonic writes (MW)** | A client's writes are applied *in the order the client issued them* on every replica. | Your "set name=A" then "set name=B" get reordered so name ends up A. |
| **Writes-follow-reads (WFR)** (a.k.a. session causality) | If a client reads value V and then writes W, the write W is ordered *after* the write that produced V on all replicas. | You reply to a comment; your reply propagates to a replica that doesn't yet have the comment, so the reply appears orphaned. |

These four are **composable into useful bundles**. Many systems offer a "session consistency" or "causal consistency" mode that is exactly *MR + RYW + MW + WFR*. (Azure Cosmos DB's "Session" and "Consistent Prefix" levels are productized versions of these.)

### 2.8 Bounded staleness and eventual consistency

> **Staleness** — how far *behind* a read can be relative to the latest write, measured either in *time* (e.g., "at most 5 seconds old") or in *versions/operations* (e.g., "at most 100 writes behind").

**Bounded staleness** guarantees reads are *consistent up to a bounded lag*: you may read stale data, but never more than `K` versions or `T` seconds out of date. It's a tunable middle ground. (Cosmos DB exposes this directly as a level with `MaxStalenessPrefix` and `MaxIntervalInSeconds`.)

**Eventual consistency** is the weakest commonly-named model: **if no new writes are made, all replicas will *eventually* converge to the same value.** It says nothing about *when*, nothing about *what intermediate reads return*, and nothing about ordering. It's cheap, highly available, and the default for AP systems (see CAP, Section 2.9). On its own it permits jarring anomalies (reads going backward, seeing replies before messages); that's why it's usually *augmented* with session guarantees or CRDTs.

> **Convergence** — the property that replicas, given the same set of writes, end up at the same final state. *How* they converge requires a conflict-resolution rule: **last-writer-wins (LWW)** (pick the write with the highest timestamp — simple but silently drops data), or a **merge function** that combines concurrent writes losslessly (the CRDT approach, Section 2.10).

### 2.9 CAP, PACELC, and where models live

> **CAP theorem** (Brewer; proven by Gilbert & Lynch, 2002) — during a **network partition** (P: the network drops/delays messages between nodes so they can't all communicate), a distributed system must choose between **Consistency** (C, here meaning *linearizability*) and **Availability** (A, every request to a non-failed node gets a non-error response). You cannot have both *while partitioned*. You can have all three when there's no partition.

Crucial clarifications newcomers always get wrong:
- The "C" in CAP is **specifically linearizability**, *not* the broad word "consistency" and *not* the "C" (correctness) in ACID. These are three different "C"s.
- CAP is **only about the partitioned moment**. It does not say "pick 2 of 3 forever."
- "CA systems" don't really exist meaningfully in a network that can partition; the real choice is **CP** (sacrifice availability to stay consistent) vs **AP** (stay available, allow stale/divergent reads).

> **PACELC** (Abadi, 2012) extends CAP: **if Partition (P) then choose A or C; Else (E), in normal operation, choose between Latency (L) and Consistency (C).** This is more useful because most of the time there's *no* partition, and the real daily tradeoff is *latency vs consistency*. Spanner is "PC/EC" (consistent in both cases, paying latency); Cassandra default is "PA/EL" (available + low latency, weaker consistency); DynamoDB is tunable.

### 2.10 CRDTs: getting convergence without coordination

> **CRDT (Conflict-free / Convergent Replicated Data Type)** — a data structure designed so that concurrent updates on different replicas can be merged **automatically and deterministically** into the same final value, with **no coordination and no conflicts**. The merge is mathematically guaranteed to converge.

Two flavors:
- **State-based (CvRDT, convergent):** each replica holds full state; periodically a replica ships its whole state; the receiver applies a **merge** function that must be a *join* on a *semilattice* — i.e., **commutative, associative, and idempotent** (order, grouping, and duplicates don't matter). Because merge is a least-upper-bound, replicas monotonically climb to the same value.
- **Operation-based (CmRDT, commutative):** replicas ship *operations*; concurrent operations must **commute** (applying in either order gives the same result), and the delivery layer must guarantee causal, exactly-once-ish delivery.

> **Semilattice** — a set with a binary "join" operation that is commutative, associative, and idempotent, yielding a well-defined least upper bound. The mathematical backbone that makes CRDT convergence automatic.

Common CRDTs: **G-Counter** (grow-only counter: per-replica counts, merge = element-wise max, value = sum); **PN-Counter** (two G-Counters for increments/decrements); **G-Set** (grow-only set, merge = union); **2P-Set** (add + tombstone-remove); **OR-Set** (observed-remove set, tags each add with a unique id so concurrent add/remove resolves predictably); **LWW-Register**; **RGA / WOOT / Logoot** (sequence CRDTs used in collaborative text editors); **MV-Register** (multi-value, keeps concurrent values for the app to resolve).

CRDTs give you **Strong Eventual Consistency (SEC):** any two replicas that have received the same *set* of updates are in the *same state* (not just "eventually" — *immediately upon* having the same updates), with no rollback. They power Riak, Redis (Active-Active via Redis Enterprise), Azure Cosmos DB's conflict resolution, Automerge/Yjs (collaborative editing), and Akka Distributed Data.

### 2.11 Consistency (replication) vs isolation (transactions) — a critical distinction

This trips up nearly everyone. **They are orthogonal axes:**

- **Consistency models** (this document) concern **single-object** behavior across **replicas**: "what value does a read of *key x* return given concurrent writes to *x*?" The canonical strong point is **linearizability**.
- **Isolation levels** (the "I" in ACID) concern **multi-object transactions** on (often) a single logical copy: "what can a transaction *see* of other concurrent transactions' uncommitted/committed changes across *many* keys?" The canonical strong point is **serializability**.

> **Serializability** — the strongest isolation level: the result of executing concurrent transactions is equivalent to *some* serial (one-at-a-time) execution of them. Note: like sequential consistency, it does **not** constrain real-time order.

> **Strict serializability** = serializability **+** linearizability's real-time guarantee. It's the gold standard for transactional distributed systems (what Spanner calls "external consistency"). It combines *both* axes: transactions appear to run one at a time, *and* that order respects wall-clock order.

> **Isolation levels** (weaker → stronger, ANSI/extended): **Read Uncommitted → Read Committed → Snapshot Isolation → Repeatable Read → Serializable.** These prevent anomalies like **dirty read** (reading another txn's uncommitted data), **non-repeatable read** (re-reading a row gives a different value), **phantom** (a re-run query returns new rows), and **write skew** (two transactions each read an overlapping set, then write disjointly, violating an invariant — *not* prevented by snapshot isolation).

> **MVCC (Multi-Version Concurrency Control)** — a technique where each write creates a *new version* of a row stamped with a transaction id/timestamp, and readers see a *consistent snapshot* as of their start time without blocking writers. Powers PostgreSQL, Oracle, MySQL/InnoDB, and most snapshot-isolation databases. Relevant here because *snapshot reads* are a per-transaction consistency guarantee that interacts with replication.

> **Snapshot Isolation (SI)** — every transaction reads from a consistent snapshot taken at its start; writes commit only if no concurrent committed transaction wrote the same rows (first-committer-wins). Prevents dirty/non-repeatable reads but **allows write skew**, so it is *weaker than serializable*.

**The cross product matters:** a system can be linearizable per-key but offer only read-committed transactions; or serializable but only *eventually* consistent across replicas (rare). The phrase "strong consistency" is ambiguous — always ask *"linearizable (replication) or serializable (transactions) or strict-serializable (both)?"*

---

## 3. How it works internally

This is the heart of the document. We trace, step by step, *how* each major model is actually achieved, including control flow, data flow, lifecycle, and the underlying state machines.

### 3.1 The substrate: replication topologies

Before any model, understand how writes get to replicas.

> **Leader-based (primary-backup / single-master) replication** — one replica is the **leader**; all writes go to it; it streams a **replication log** of changes to **followers**. Reads may go to the leader (fresh) or followers (possibly stale). This is PostgreSQL streaming replication, MySQL replication, Kafka partitions, MongoDB replica sets, and the basis for Raft/Paxos systems.

> **Multi-leader replication** — multiple nodes accept writes (e.g., one per region) and replicate to each other. Improves write availability/latency but introduces **write conflicts** that need resolution (LWW or CRDT or app-level).

> **Leaderless (Dynamo-style) replication** — clients (or a coordinator) write to *many* replicas directly and read from *many*, using **quorums** to overlap. Cassandra, Riak, DynamoDB use this.

> **Quorum** — require acknowledgments from a *subset* of replicas. With `N` replicas, `W` = write acks required, `R` = read acks required. If `W + R > N`, every read overlaps at least one replica that saw the latest write — the basis for *tunable* consistency. (Strong reads also generally require `W + R > N` **and** care about concurrent writes / read-repair; quorum alone gives "strong-ish" but not full linearizability without extra mechanisms.)

### 3.2 How linearizability is achieved — consensus & the Raft trace

The most common way to get linearizable single-object semantics is a **consensus protocol** with a single leader.

> **Consensus** — the problem of getting a set of nodes to agree on a single value (or a single *ordered log* of values) despite failures. Solved by **Paxos** (Lamport) and **Raft** (Ongaro & Ousterhout, 2014, designed to be understandable). **ZooKeeper** uses **Zab** (a Paxos-like protocol). These produce a **replicated state machine**: all nodes apply the same commands in the same order, so they stay identical.

> **Replicated State Machine (RSM)** — if you start identical state machines and feed them the *same sequence* of deterministic commands, they stay identical. Consensus's job is to agree on that sequence (the log). This is the engine under most CP databases.

**Step-by-step Raft write (linearizable):**

1. **Client → Leader.** A client sends `write(x,5)`. (If it contacted a follower, the follower redirects it to the leader.)
2. **Append to leader log.** The leader appends the command to its local replication log at the next index, in its current **term** (a monotonically increasing election epoch number).
3. **Replicate (AppendEntries RPC).** The leader sends the new log entry to all followers in parallel.
4. **Followers persist & ack.** Each follower appends the entry to its own log (after a consistency check that its log matches the leader's up to that point) and acknowledges.
5. **Commit on majority.** Once a **majority** (quorum) of nodes have the entry, the leader marks it **committed**. Majority of an odd cluster (e.g., 3 of 5) tolerates `floor((N-1)/2)` failures and guarantees any future majority overlaps this one.
6. **Apply to state machine.** The leader applies the committed command to its state machine (sets `x=5`) and returns success to the client.
7. **Followers apply.** On subsequent heartbeats the leader tells followers the new commit index; they apply the entry to their state machines in log order.

**How reads stay linearizable** (the subtle part — naive leader reads can be stale if the leader was deposed):
- **Read index / leader lease:** before answering a read, the leader confirms it is *still* the leader by exchanging heartbeats with a quorum (the "ReadIndex" optimization) **or** holds a time-bounded **lease** during which no new leader can be elected. Only then does it serve the read. This prevents a *stale deposed leader* from answering with old data.
- Reads from followers are **not** linearizable unless they go through the leader's commit index (some systems offer "follower reads" with a known bounded staleness instead).

**State machine of the model:** the *cluster* moves through `Follower → Candidate → Leader` per node; the *log* moves entries through `Appended → Replicated-to-majority → Committed → Applied`. Linearizability emerges because there is exactly one committed log order and reads observe the applied prefix.

> **Term / epoch** — a logical clock for leadership. Each election increments it. Messages carry the term; a node seeing a higher term steps down. This prevents two leaders from both committing (split brain) because the old leader's writes won't get a fresh quorum.

### 3.3 How sequential consistency is achieved

Sequential consistency drops the real-time cross-process requirement, so it can be cheaper. A common implementation: a **single total-order broadcast** (atomic broadcast) of writes that all replicas apply in the same order, *plus* each client tagging its own writes so its program order is preserved — but **without** forcing reads to reflect the absolute latest committed write in real time. Multiprocessor hardware historically targeted sequential consistency; modern CPUs actually provide *weaker* models (TSO, etc.) and use *memory fences* to recover stronger ordering on demand.

### 3.4 How causal consistency is achieved — step by step

Causal consistency requires tracking dependencies and **only delivering** an update to a replica once that replica has seen all the update's causal predecessors.

**Data flow with vector clocks (e.g., COPS-style / causal+ systems):**

1. **Client context.** Each client maintains a *context* = the set (or vector clock) of versions it has observed (its causal past).
2. **Write tagging.** When a client writes, the new version is stamped with **dependencies**: the versions in the client's context that the write causally depends on.
3. **Replication with dependency check.** When the write propagates to another replica, that replica **buffers** it until all its dependencies have been applied locally. Only then does it become visible ("dependency satisfaction").
4. **Read.** Reads return a version, and the client merges that version's metadata into its context, so future writes correctly depend on it (this gives writes-follow-reads).
5. **Convergence of concurrent writes.** Two causally-concurrent writes to the same key are *conflicts*; the system resolves them via LWW, MV-register (keep both), or a CRDT merge.

The cost: metadata overhead (dependency lists or vector clocks grow with the number of writers) and the buffering/visibility delay. The benefit: **no cross-replica coordination on the write path** — causal consistency is *available* under partition.

### 3.5 How quorum (tunable) consistency works — step by step

**Leaderless write path (Dynamo/Cassandra):**

1. **Coordinator.** The client contacts any node, which becomes the **coordinator** for this request.
2. **Fan-out write.** The coordinator sends the write to all `N` replicas responsible for the key (determined by **consistent hashing**).
3. **Wait for W.** The coordinator returns success once `W` replicas ack. The other replicas get the write asynchronously (or via hinted handoff if down).
4. **Versioning.** Each write carries a timestamp (Cassandra) or vector clock (classic Dynamo/Riak) so replicas can later reconcile.

**Leaderless read path:**

1. **Fan-out read** to `N` replicas; wait for `R` responses.
2. **Reconcile.** Compare versions among the `R` responses; return the newest (LWW) or all concurrent versions (Riak siblings) to the client.
3. **Read repair.** If replicas disagree, the coordinator *writes back* the newest value to the stale ones (synchronous or background).

> **Consistent hashing** — a way to map keys to nodes on a ring so that adding/removing a node only remaps a small fraction of keys, not the whole space. The basis for partitioning in Dynamo-style and many distributed caches. (Newcomer note: it solves "where does this key live, and how do we rebalance without reshuffling everything?")

> **Hinted handoff** — if a target replica is down during a write, another node temporarily stores a "hint" (the write + intended destination) and replays it when the downed node returns, improving durability/availability at the cost of temporary inconsistency.

> **Anti-entropy / Merkle trees** — background process where replicas compare hash trees (**Merkle trees** = trees of hashes letting two nodes find *which* ranges differ in O(log n) comparisons) to detect and repair divergence, driving eventual convergence.

**Why `W+R>N` is not full linearizability:** quorum overlap guarantees a read *sees at least one replica with the latest completed write*, but concurrent writes, failed-but-partial writes, sloppy quorums, and clock-skew-based LWW can still produce non-linearizable histories. To get true linearizability, Dynamo-style systems add per-key consensus or lightweight transactions (Cassandra's **LWT** using Paxos — Section 4).

### 3.6 The JVM internals: the Java Memory Model as a consistency model

The **JMM** is the consistency model for *shared memory between threads*. Without it, the compiler/CPU could reorder reads and writes such that one thread never sees another's updates.

- **Per-thread program order** is what each thread sees of *itself*.
- **Happens-before** (same relation as Lamport's, applied to memory) is the JMM's core: if action A happens-before B, A's memory effects are visible to B.
- Happens-before edges are created by: program order within a thread; `volatile` write → subsequent `volatile` read of the same field; unlock of a monitor → subsequent lock of the same monitor (`synchronized`); `Thread.start()` → the started thread's first action; a thread's actions → another thread's return from `join()`; constructor field writes → reads via a properly published `final` field.
- **`volatile`** gives *sequential-consistency-like* visibility and ordering for a single variable (and, since Java 5, prevents reordering of surrounding accesses), but **not atomicity of compound actions** (`x++` is still a race).
- **`synchronized` / `j.u.c.locks`** give mutual exclusion *and* happens-before.
- **`java.util.concurrent.atomic`** (e.g., `AtomicLong`, `AtomicReference`) and **`VarHandle`** (Java 9+) expose CAS and explicit memory-ordering modes (`plain`, `opaque`, `acquire/release`, `volatile`) — a fine-grained per-access consistency knob analogous to choosing a consistency level in a database.

> **CAS (Compare-And-Swap)** — an atomic CPU instruction: "set this memory location to V *only if* it currently equals expected E." The building block of lock-free algorithms and of linearizable single-variable updates in the JVM. The distributed analog is a *conditional write* / compare-and-set in a database (e.g., DynamoDB conditional writes, Cassandra LWT).

The parallel is exact: choosing `volatile` vs plain field is choosing a consistency model for memory, just as choosing `QUORUM` vs `ONE` is choosing one for a database.

---

## 4. The complete toolkit

This section enumerates the concrete knobs, APIs, and commands across the major systems a Java backend engineer will touch.

### 4.1 The model spectrum at a glance

| Model | Real-time order? | Single total order? | Preserves causality? | Reads can go backward? | Available under partition? | Composable? |
|---|---|---|---|---|---|---|
| Strict | Yes (instant) | Yes | Yes | No | No | Yes |
| **Linearizable** | Yes | Yes | Yes | No | **No (CP)** | **Yes** |
| Sequential | No | Yes | Yes | No (per process) | No | No |
| **Causal** | No | No | **Yes** | No (per process) | **Yes (AP)** | — |
| Bounded staleness | Within bound | No | Typically prefix | Within bound | Partial | — |
| Read-your-writes | — | No | — | No (own writes) | Yes | — |
| Monotonic reads | — | No | — | **No** | Yes | — |
| Eventual | No | No | No | **Yes** | **Yes (AP)** | — |

### 4.2 Cassandra (leaderless, tunable) — consistency levels

Set per-statement via `CONSISTENCY <LEVEL>;` (cqlsh) or in the driver per-`Statement`. `N` = replication factor.

| Level | Meaning | Use when |
|---|---|---|
| `ANY` | Write acknowledged even if only a hint is stored (no replica yet). Highest availability, weakest. | Fire-and-forget telemetry. |
| `ONE` / `TWO` / `THREE` | Wait for that many replica acks/reads. | Low latency, tolerate staleness. |
| `QUORUM` | Majority of all replicas (`floor(N/2)+1`). | Balanced strong-ish reads. |
| `LOCAL_QUORUM` | Quorum within the *local datacenter* only (no cross-DC latency). | Multi-DC, most common production default. |
| `EACH_QUORUM` | Quorum in *every* DC (writes only). | Strong multi-DC writes. |
| `ALL` | Every replica. Strongest, lowest availability. | Rare; correctness-critical, low write volume. |
| `LOCAL_ONE` | One replica in local DC. | Latency-sensitive local reads. |
| `SERIAL` / `LOCAL_SERIAL` | Used with **LWT** (Paxos) for linearizable compare-and-set. | `IF NOT EXISTS` / `IF col=val` conditional updates. |

Rule: **`W (write CL) + R (read CL) > N` ⇒ strong (read-after-write) consistency** (e.g., `QUORUM`+`QUORUM`). **LWT** (`INSERT ... IF NOT EXISTS`, `UPDATE ... IF ...`) provides *linearizable* compare-and-set via Paxos at the cost of ~4 round trips.

### 4.3 DynamoDB

| Knob | Values / API | Default | Notes |
|---|---|---|---|
| Read consistency | `ConsistentRead = false` (eventual) or `true` (strong) | `false` (eventual) | Strong reads cost 2× RCU, higher latency, not allowed on GSIs. |
| Conditional writes | `ConditionExpression` | — | Atomic compare-and-set (linearizable per item). |
| Transactions | `TransactWriteItems` / `TransactGetItems` | — | ACID across ≤100 items, serializable-ish, 2× cost. |
| Global Tables | multi-region, **LWW** conflict resolution | eventual cross-region | Last writer wins by timestamp. |

### 4.4 MongoDB

Two independent knobs:

| `writeConcern` | Meaning |
|---|---|
| `w: 1` | Ack from primary only (default-ish). |
| `w: "majority"` | Ack from majority of replica set (durable, survives primary failover). |
| `j: true` | Wait for on-disk journal. |
| `wtimeout` | Timeout for the write concern. |

| `readConcern` | Meaning |
|---|---|
| `local` | Returns data from the queried node, possibly not majority-committed (can be rolled back). |
| `available` | Like local; for sharded reads, lowest latency. |
| `majority` | Only majority-committed data (won't be rolled back). |
| `linearizable` | Linearizable reads (primary only, with `majority` write concern); adds latency. |
| `snapshot` | Consistent snapshot across a multi-document transaction. |

`readPreference` (`primary`, `primaryPreferred`, `secondary`, `secondaryPreferred`, `nearest`) chooses *where* to read — secondary reads are stale.

### 4.5 Azure Cosmos DB — five named levels (a clean productized spectrum)

| Level | Guarantee |
|---|---|
| **Strong** | Linearizable. Reads see latest committed write. (Limited to single write region or within bound.) |
| **Bounded staleness** | Lag bounded by `MaxStalenessPrefix` (versions) and `MaxIntervalInSeconds` (time). Consistent prefix within the bound. |
| **Session** (default) | Per-session: RYW, MR, MW, WFR — i.e., client-centric causal within a session token. |
| **Consistent prefix** | Reads never see writes out of order (no gaps), but may be stale. |
| **Eventual** | Weakest; no ordering guarantee. |

### 4.6 Spanner / CockroachDB / YugabyteDB

| System | Default model | Mechanism |
|---|---|---|
| Google **Spanner** | **External consistency** (= strict serializability) | **TrueTime** (GPS+atomic-clock API giving bounded-uncertainty timestamps; "commit wait" of a few ms covers clock uncertainty) + Paxos groups. |
| **CockroachDB** | Serializable isolation; *not* linearizable by default (uses HLC + uncertainty intervals; offers "linearizable"/"causal reverse" controls) | Raft per range + Hybrid Logical Clocks. |
| **YugabyteDB** | Snapshot/Serializable; strongly consistent on writes | Raft per tablet + HLC. |

> **TrueTime** — Spanner's clock API returning an interval `[earliest, latest]` guaranteed to contain true time. By waiting out the uncertainty (`commit wait`), Spanner assigns globally meaningful timestamps, enabling externally-consistent transactions across the planet.

> **Hybrid Logical Clock (HLC)** — combines a physical timestamp with a logical counter, giving timestamps that track causality *and* stay close to wall-clock, without needing special hardware. Used by CockroachDB, YugabyteDB, MongoDB (clusterTime).

### 4.7 Kafka (log ordering ≈ a consistency model for streams)

| Knob | Effect |
|---|---|
| `acks=0/1/all` | Producer durability/consistency: `all` waits for all in-sync replicas (ISR). |
| `min.insync.replicas` | With `acks=all`, minimum replicas that must ack — the quorum knob. |
| `enable.idempotence=true` | Exactly-once *producer* (dedup by producer id + sequence). |
| transactional API (`transactional.id`) | Atomic multi-partition writes (read-process-write). |
| Per-partition ordering | Kafka guarantees order *within a partition* only. |

### 4.8 etcd / ZooKeeper / Consul (coordination stores)

| System | Model | Notes |
|---|---|---|
| **etcd** | Linearizable reads/writes (Raft); serializable reads available for lower latency. | Used by Kubernetes. |
| **ZooKeeper** | Linearizable *writes*; **sequentially consistent** reads (can be stale) unless `sync()` first; "consistent prefix." | Zab protocol. |
| **Consul** | `consistent` / `default` (leader-lease) / `stale` read modes. | Tunable per query. |

### 4.9 Redis

| Mode | Model |
|---|---|
| Single instance | Linearizable-ish (single-threaded command execution). |
| Async replication (default) | Eventual; replica reads stale; failover can lose acknowledged writes. |
| `WAIT numreplicas timeout` | Blocks until N replicas ack — *best-effort* stronger durability, not full linearizability. |
| Redis Enterprise Active-Active | **CRDTs** for multi-region convergence. |

### 4.10 JVM concurrency primitives (memory consistency)

| Tool | Guarantee | Notes |
|---|---|---|
| plain field | none (data race possible) | reads may see stale/torn (long/double) values |
| `volatile` | visibility + ordering (no reordering), per-variable; SC-ish | not atomic for `x++` |
| `synchronized` / `ReentrantLock` | mutual exclusion + happens-before | |
| `j.u.c.atomic.*` (CAS) | atomic RMW + ordering | lock-free |
| `VarHandle` modes (`getAcquire`/`setRelease`/`getOpaque`/`getVolatile`) | fine-grained memory ordering | Java 9+ |
| `final` fields | safe publication after construction | |

---

## 5. Code examples by use case

Idiomatic, explained, copy-adaptable. Java where language-relevant.

### 5.1 Linearizable single-variable update in the JVM (CAS loop)

```java
// Use case: a lock-free, linearizable counter shared by many threads.
// AtomicLong's operations are linearizable: each appears to take effect atomically.
import java.util.concurrent.atomic.AtomicLong;

public final class Metrics {
    private final AtomicLong requests = new AtomicLong();

    public void onRequest() {
        requests.incrementAndGet(); // atomic read-modify-write; no torn values, fully ordered
    }

    // Conditional update: only succeed if value hasn't changed (CAS) — the JVM analog
    // of a database "conditional write". Retries on contention.
    public boolean capAt(long max) {
        long cur;
        do {
            cur = requests.get();
            if (cur >= max) return false;          // give up: already at cap
        } while (!requests.compareAndSet(cur, cur + 1)); // succeeds only if unchanged since read
        return true;
    }
}
```
Why it matters: `compareAndSet` is the exact same idea as a distributed compare-and-set — it lets you implement linearizable conditional logic without a lock.

### 5.2 Read-your-writes across an eventually-consistent store (session token)

```java
// Use case: user updates their profile, then immediately views it. We must not show
// the OLD profile. The store replicates asynchronously (eventual). We enforce RYW by
// pinning the client to a replica that has caught up to the version it just wrote.

public class ProfileService {
    private final ReplicaRouter router;     // picks a replica, can pin by version
    private final KeyValueStore store;

    // Returns a "version stamp" the client carries in its session.
    public long updateProfile(String userId, Profile p, Session session) {
        long version = store.write("profile:" + userId, p); // returns the write's version
        session.observe("profile:" + userId, version);      // remember our causal context
        return version;
    }

    public Profile readProfile(String userId, Session session) {
        long minVersion = session.observedVersion("profile:" + userId); // our own latest write
        // Route to a replica whose applied-version >= minVersion (read-your-writes).
        Replica r = router.replicaWithAtLeast("profile:" + userId, minVersion);
        return store.readFrom(r, "profile:" + userId);
    }
}
```
The session carries the *causal context* (versions observed). Routing to a sufficiently-fresh replica turns an eventually-consistent store into a read-your-writes store *for that client*.

### 5.3 Cassandra: strong read-after-write with quorum, plus linearizable LWT

```java
// Use case: claim a unique username (must be globally atomic) AND read it back consistently.
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;

try (CqlSession session = CqlSession.builder().build()) {

    // 1) Linearizable claim via Lightweight Transaction (Paxos). IF NOT EXISTS makes it
    //    a compare-and-set: only one concurrent claimer wins. SERIAL = linearizable.
    SimpleStatement claim = SimpleStatement.builder(
            "INSERT INTO usernames (name, user_id) VALUES (?, ?) IF NOT EXISTS")
        .addPositionalValues("neo", "u-123")
        .setConsistencyLevel(ConsistencyLevel.QUORUM)        // commit CL
        .setSerialConsistencyLevel(ConsistencyLevel.SERIAL)  // Paxos CL
        .build();
    ResultSet rs = session.execute(claim);
    boolean applied = rs.wasApplied();  // false => someone already took it

    // 2) Strong read-after-write: QUORUM write + QUORUM read => W+R>N.
    session.execute(SimpleStatement.builder(
            "UPDATE profiles SET bio=? WHERE user_id=?")
        .addPositionalValues("hello", "u-123")
        .setConsistencyLevel(ConsistencyLevel.QUORUM).build());

    Row row = session.execute(SimpleStatement.builder(
            "SELECT bio FROM profiles WHERE user_id=?")
        .addPositionalValues("u-123")
        .setConsistencyLevel(ConsistencyLevel.QUORUM).build()) // QUORUM read overlaps the write
        .one();
}
```
Note: LWT (`IF NOT EXISTS`) is ~4 round-trips and *much* slower than a normal write — use it only when you truly need linearizable conditional logic.

### 5.4 DynamoDB: eventual vs strong read, and a conditional write

```java
// Use case: a counter you can read eventually (cheap) but increment atomically.
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Map;

DynamoDbClient db = DynamoDbClient.create();

// Strong read: ConsistentRead=true (2x cost, higher latency, guarantees latest committed).
GetItemResponse strong = db.getItem(GetItemRequest.builder()
    .tableName("accounts")
    .key(Map.of("id", AttributeValue.fromS("a-1")))
    .consistentRead(true)               // <-- the consistency knob
    .build());

// Conditional (linearizable) write: only debit if balance stays >= 0.
db.updateItem(UpdateItemRequest.builder()
    .tableName("accounts")
    .key(Map.of("id", AttributeValue.fromS("a-1")))
    .updateExpression("SET balance = balance - :amt")
    .conditionExpression("balance >= :amt")   // atomic compare-and-set; fails -> ConditionalCheckFailedException
    .expressionAttributeValues(Map.of(":amt", AttributeValue.fromN("100")))
    .build());
```

### 5.5 MongoDB: majority write + linearizable read

```java
// Use case: financial record that must never be rolled back and must be read linearizably.
import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;

MongoClient client = MongoClients.create();
MongoCollection<Document> coll = client.getDatabase("bank")
    .getCollection("ledger")
    .withWriteConcern(WriteConcern.MAJORITY)            // survives primary failover, no rollback
    .withReadConcern(ReadConcern.LINEARIZABLE);         // linearizable read (primary, adds latency)

coll.insertOne(new Document("txn", "t-9").append("amount", 500));
Document d = coll.find(new Document("txn", "t-9")).first(); // sees the majority-committed write
```

### 5.6 A G-Counter CRDT in Java (strong eventual consistency, no coordination)

```java
// Use case: a globally distributed "page views" counter where each replica increments
// independently and they converge with NO coordination. Merge = element-wise max; value = sum.
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class GCounter {
    private final String replicaId;
    // per-replica monotonically increasing counts
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    public GCounter(String replicaId) { this.replicaId = replicaId; }

    public void increment(long by) {
        counts.merge(replicaId, by, Long::sum);  // only THIS replica bumps its own slot
    }

    public long value() {
        return counts.values().stream().mapToLong(Long::longValue).sum(); // total across replicas
    }

    // Commutative, associative, idempotent merge: take max per replica slot.
    public void merge(GCounter other) {
        other.counts.forEach((k, v) -> counts.merge(k, v, Math::max));
    }
}
```
Because `max` is commutative/associative/idempotent, replicas can gossip states in any order, any number of times, and still converge to the same `value()`. That is *strong eventual consistency*.

### 5.7 Enforcing monotonic reads with a client high-water mark

```java
// Use case: a feed where the UI must never appear to go "backward". We track the highest
// version we've shown and refuse to render anything older, re-fetching from a fresher replica.
public class MonotonicFeedReader {
    private long highWaterVersion = Long.MIN_VALUE; // highest version observed so far

    public Feed read(FeedStore store) {
        VersionedFeed vf = store.read();              // may hit a stale replica
        if (vf.version() < highWaterVersion) {
            vf = store.readFrom(store.freshestReplica()); // recover monotonicity
        }
        highWaterVersion = Math.max(highWaterVersion, vf.version());
        return vf.feed();
    }
}
```

### 5.8 etcd linearizable compare-and-swap (distributed lock primitive)

```java
// Use case: leader election / lock using etcd's linearizable transactions (jetcd).
import io.etcd.jetcd.*;
import io.etcd.jetcd.op.*;
import io.etcd.jetcd.kv.TxnResponse;
import static java.nio.charset.StandardCharsets.UTF_8;

Client client = Client.builder().endpoints("http://localhost:2379").build();
KV kv = client.getKVClient();
ByteSequence key = ByteSequence.from("/lock/leader", UTF_8);
ByteSequence me  = ByteSequence.from("node-A", UTF_8);

// Atomic CAS: set the key to "node-A" ONLY IF it does not already exist (createRevision == 0).
TxnResponse txn = kv.txn()
    .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.createRevision(0)))
    .Then(Op.put(key, me, PutOption.DEFAULT))
    .Else(Op.get(key, GetOption.DEFAULT))
    .commit().get();

boolean iAmLeader = txn.isSucceeded(); // linearizable: at most one node wins
```

### 5.9 Kafka exactly-once read-process-write (ordering as consistency)

```java
// Use case: consume, transform, produce atomically so downstream never sees partial/duplicate
// results — Kafka's transactional guarantee is its strong-consistency story for streams.
producer.initTransactions();
producer.beginTransaction();
try {
    for (ConsumerRecord<String,String> r : records) {
        producer.send(new ProducerRecord<>("out", transform(r.value())));
    }
    // commit the consumer offsets WITHIN the same transaction -> exactly-once
    producer.sendOffsetsToTransaction(currentOffsets(), consumer.groupMetadata());
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction(); // nothing becomes visible to read_committed consumers
}
// Consumers must set isolation.level=read_committed to honor the boundary.
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency

- **Stronger = slower.** Linearizable writes need a quorum round trip (often cross-AZ ~1–2 ms, cross-region 50–150+ ms). Spanner pays a few ms of *commit wait* for TrueTime. Plan latency budgets around the *weakest acceptable* model.
- **Strong reads cost more:** DynamoDB strong reads = 2× RCU; MongoDB `linearizable` reads add a round trip; Cassandra `QUORUM` waits for the slower of a majority. Use **tail-latency** (p99/p999), not average, when reasoning — the slowest quorum member sets your latency.
- **Read locality:** `LOCAL_QUORUM` (Cassandra) / read-from-secondary (Mongo) / follower reads trade staleness for avoiding cross-region hops. Use them deliberately.
- **Batch & avoid LWT/transactions on hot paths:** Cassandra LWT and DynamoDB transactions are expensive (extra round trips, contention). Reserve for truly contended invariants.

### 6.2 Correctness & concurrency

- **Don't confuse the two axes.** "We use serializable transactions" does *not* imply linearizable replica reads, and vice versa. Specify both.
- **`W+R>N` is necessary, not sufficient** for linearizability; concurrent writes and LWW clock skew still bite. For real linearizable conditional updates use consensus-backed primitives (LWT, conditional writes, etcd txns).
- **Beware read-modify-write races** on eventually consistent stores — they silently lose updates. Use CAS/conditional writes or CRDTs.
- **LWW silently discards data** under concurrent writes; if losing a concurrent update is unacceptable, use MV-registers or CRDTs.

### 6.3 Memory (JVM-specific)

- Unsynchronized shared mutable state = data race = *undefined* visibility. Always create a happens-before edge (`volatile`, lock, atomic, or safe publication via `final`).
- `volatile` does not make `count++` atomic; use `AtomicLong`/`LongAdder` (the latter scales better under contention by sharding the counter).
- Prefer immutable objects + safe publication; it sidesteps most JMM pitfalls.

### 6.4 Security

- Stale reads can leak revoked permissions: if you cache/replicate auth decisions eventually, a user might retain access after revocation until convergence. Auth-critical reads often need *at least* read-your-writes (revoker sees their own change) and bounded staleness for everyone else.
- Conditional writes prevent **TOCTOU** (time-of-check-to-time-of-use) races that an attacker could exploit (e.g., double-spend). Make the check-and-act atomic.

### 6.5 Cost

- Strong reads/writes consume more resources (DynamoDB capacity units, extra network, CPU for consensus). At scale this is real money; classify data by *required* model and pay only where needed.
- Cross-region strong consistency (EACH_QUORUM, multi-region strong) multiplies write latency and inter-region bandwidth cost.

### 6.6 Observability

- **Measure replication lag** (Mongo `rs.printSecondaryReplicationInfo()`, Postgres `pg_stat_replication`, Kafka consumer lag, Cassandra `nodetool netstats`/`tablestats`). Bounded staleness is only meaningful if you *monitor* the bound.
- Track **read repair rate / hinted handoff backlog** (Cassandra) and **conflict/sibling counts** (Riak) — rising values signal divergence.
- Add **client-side causal context logging** to debug "I don't see my own write" reports.

### 6.7 Testing

- **Jepsen** (Kyle Kingsbury) is the canonical tool: it injects partitions/clock skew and checks recorded histories for linearizability/serializability violations using a checker (**Knossos**/**Elle**). Many vendors' consistency claims were debunked by Jepsen.
- **Linearizability checking** is NP-hard in general but tractable for recorded histories with good heuristics (Wing-Gong / Knossos).
- In unit tests, use deterministic clocks and fault injection; for the JVM, **jcstress** stress-tests memory-model behavior of concurrent code.

### 6.8 Production hardening & anti-patterns

Anti-patterns to avoid:
- **"We'll just use eventual consistency everywhere"** without session guarantees → users see their own writes vanish, feeds flicker. At least add RYW + monotonic reads.
- **Reading from a secondary/follower for read-after-write flows** → stale UX. Pin to leader or carry version.
- **Relying on wall-clock timestamps for ordering** across machines → clock skew reorders writes (LWW data loss). Use logical/hybrid clocks or consensus.
- **Mixing consistency levels inconsistently** for the same data (e.g., `QUORUM` write, `ONE` read) when you needed read-after-write → violates `W+R>N`.
- **Distributed locks for correctness without fencing tokens** → a paused (GC/STW) lock holder can act after its lease expired. Use **fencing tokens** (monotonic numbers the resource checks).
- **Assuming "strong" means the same thing across vendors** — always map the vendor word to a formal model.

> **Fencing token** — a monotonically increasing number issued with a lock; the protected resource rejects any operation carrying a token lower than the highest it has seen, preventing a stale lock holder (e.g., one that paused for a long GC) from corrupting state. Essential for correct distributed locking.

---

## 7. Advanced topics & deep internals

### 7.1 The CAP boundary, precisely: causal is the strongest "always-available" model

A landmark result (**Mahajan, Alvisi, Dahlin**, and related work by **Attiya/Ellen/Morrison**) shows that **real-time causal consistency (causal+ / observable causal consistency) is the strongest consistency model that can be provided in an always-available, one-way-convergent system tolerant to partitions.** In other words: you can have causality *and* availability under partition, but you cannot have anything stronger (no sequential, no linearizable) without sacrificing availability. This is *the* theoretical reason causal consistency is the sweet spot for AP systems (COPS, Eiger, Bayou's lineage).

### 7.2 "Consistency" in CAP vs "C" in ACID vs sequential consistency vs cache coherence

Disambiguation table — keep this mental model crisp:

| The word "consistency" in… | Actually means |
|---|---|
| CAP theorem | **Linearizability** (single-object, real-time) |
| ACID | **The DB stays valid** (invariants/constraints hold) — an application-level correctness notion |
| Consistency *models* (this doc) | The whole spectrum of allowable read/write orderings across replicas |
| Cache coherence (CPU) | All cores see a single, consistent value per cache line (≈ per-location sequential/linearizable) |
| Eventual consistency | Convergence given quiescence |

### 7.3 Convergent conflict resolution internals

- **LWW** needs a total order on writes → uses timestamps; vulnerable to skew; ties broken by node id. Cassandra and DynamoDB Global Tables use LWW.
- **Vector-clock sibling detection (Riak):** concurrent writes produce *siblings*; the app must merge them on read (or configure a CRDT type so the DB merges automatically).
- **OR-Set internals:** each `add(e)` attaches a unique tag; `remove(e)` removes only the tags it *observed*. A concurrent add (new tag) survives a remove — resolving the classic add/remove race without coordination. This is why OR-Set is the default "set" CRDT.
- **Sequence CRDTs (RGA/Logoot/Treedoc):** assign dense, totally-ordered position identifiers between existing elements so concurrent inserts at the "same spot" get distinct stable positions — the magic behind Google-Docs-style collaborative editing (Yjs, Automerge).
- **Delta-state CRDTs:** ship only *deltas* of state instead of full state, drastically cutting bandwidth while preserving convergence — the practical evolution of CvRDTs.

### 7.4 Tuning knobs and lesser-known behavior

- **Cassandra `read_repair_chance` / blocking read repair / speculative retry:** control background vs foreground convergence and tail latency.
- **`SERIAL` vs `LOCAL_SERIAL`:** LWT Paxos can be confined to one DC for latency at the cost of cross-DC linearizability.
- **MongoDB `afterClusterTime` / causal consistency sessions** (`startSession(causalConsistency=true)`): the driver carries cluster time so reads see prior writes — a productized causal session.
- **Spanner stale reads** (`read_timestamp` / `max_staleness` / bounded staleness reads): explicitly trade freshness for the ability to serve from any nearby replica with no leader round trip — a huge latency win for read-heavy workloads.
- **CockroachDB `AS OF SYSTEM TIME`:** historical/stale reads that avoid contention; "follower reads" serve from the nearest replica at a slightly stale timestamp.
- **JVM `VarHandle` opaque/acquire-release modes:** lets experts pick *exactly* the memory ordering needed, avoiding the cost of full `volatile` where weaker ordering suffices.

### 7.5 The hidden cost of metadata

Causal systems carry dependency metadata; vector clocks grow O(number of writers). Production systems bound this with: **dependency compression**, **version vectors with pruning**, **dotted version vectors** (Riak — accurately track per-actor causality with bounded size), and **HLC** (constant-size, causality-tracking). Always ask "what's the metadata growth story?" when evaluating a causal store.

### 7.6 Read-only and write-only transactions, and the COPS line

COPS/Eiger demonstrate **causally-consistent read-only transactions** (a *set* of reads that together reflect a consistent causal snapshot) — important because reading several keys one-by-one under mere causal consistency can still expose an inconsistent *combination*. Multi-key consistency needs explicit transactional reads even in causal systems.

### 7.7 Geo-distribution patterns

- **Single write region + read replicas** (simplest strong story; writes pay latency to the region).
- **Multi-region active-active with CRDTs** (Redis Enterprise, Riak) — available + low latency, eventual/strong-eventual, app must accept merge semantics.
- **Spanner-style global strict serializability** — pay commit-wait; best when correctness across regions is paramount and you can afford the latency floor.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Strength vs cost/availability (the master tradeoff)

| Need | Pick | Pay |
|---|---|---|
| Money, inventory, uniqueness, locks | Linearizable / strict-serializable | Latency, availability under partition |
| "See my own changes" UX | Read-your-writes (session) | Routing/version tracking |
| Social feeds, comments, causality | Causal consistency | Metadata, visibility delay |
| Counters, sets, collaborative editing | CRDT (strong eventual) | Merge semantics, metadata |
| Telemetry, caches, analytics | Eventual | App tolerance to staleness |
| Bounded freshness SLA | Bounded staleness | Monitoring the bound |

### 8.2 Use when / avoid when

**Linearizability — use when** correctness depends on a single global truth (balances, unique IDs, leader election, locks); **avoid when** you need high availability under partition or low cross-region latency on the write path.

**Causal — use when** you need cause-before-effect ordering (messaging, comments) but want partition availability; **avoid when** you need multi-key invariants enforced atomically (that's transactions) or a single global order.

**Eventual — use when** staleness is harmless and availability/latency dominate (likes, view counts, caches); **avoid when** users observe their own writes immediately, or order matters.

**Bounded staleness — use when** you can tolerate *some* lag but need a guarantee on its size (dashboards, near-real-time); **avoid when** any staleness is unacceptable.

**CRDTs — use when** offline/multi-master writes must merge automatically without conflicts (collaborative apps, multi-region counters); **avoid when** business logic needs a strict global decision (CRDTs can't enforce "max one winner" without coordination).

### 8.3 Model comparison vs isolation (the orthogonal axis)

| | Single-object (replication) | Multi-object (transactions) |
|---|---|---|
| Strongest | Linearizability | Serializability |
| Strongest + real-time | (linearizability *is* real-time) | Strict serializability |
| Weak | Eventual | Read uncommitted |
| Middle | Causal / bounded staleness | Snapshot isolation / read committed |

### 8.4 Decision flow (text)

1. Does correctness require a single global truth right now? → **Linearizable** (or strict-serializable if multi-key).
2. Else, do users need to see their own writes / not go backward? → **Session guarantees (RYW + MR)** on top of eventual.
3. Else, does cause-before-effect ordering matter across users? → **Causal**.
4. Else, do concurrent writes need lossless auto-merge? → **CRDT**.
5. Else, is a freshness bound required? → **Bounded staleness**.
6. Else → **Eventual** (cheapest, most available).

---

## 9. Failure modes & debugging

### 9.1 Common production failures

- **Stale read after write (RYW violation):** user updates, sees old value. Cause: read hit a lagging follower / `W+R≤N` / read from secondary. **Diagnose:** check read routing and CL; reproduce with a forced follower read; inspect replication lag. **Fix:** route read-after-write to leader, carry a version/causal token, or raise read CL.
- **Lost update:** two concurrent read-modify-writes; one overwrites the other. Cause: non-atomic RMW on eventually consistent store / LWW. **Diagnose:** compare expected vs actual via audit log; look for concurrent writers. **Fix:** conditional write/CAS, CRDT, or transaction.
- **Non-monotonic reads (feed flicker):** value appears then disappears. Cause: alternating reads across replicas with different lag. **Fix:** sticky routing + client high-water mark (Section 5.7).
- **Causality violation (orphaned reply):** reply visible before original. Cause: eventual store without WFR. **Fix:** causal store / session causality / carry dependencies.
- **Split brain (two leaders):** two nodes both accept writes during partition. Cause: misconfigured quorum / no fencing. **Diagnose:** divergent logs, conflicting writes. **Fix:** correct majority quorums, fencing tokens, proper leader leases.
- **Phantom rollback (MongoDB):** writes acked with `w:1` lost on failover. **Fix:** `w:"majority"`.
- **Clock-skew data loss (LWW):** future-dated write from a skewed clock permanently masks later real writes. **Diagnose:** look for writes with timestamps far in the future. **Fix:** NTP discipline, reject far-future timestamps, prefer logical clocks.

### 9.2 Tools & commands

| Symptom | Tool / command |
|---|---|
| Replication lag (Mongo) | `rs.status()`, `rs.printSecondaryReplicationInfo()` |
| Replication lag (Postgres) | `SELECT * FROM pg_stat_replication;` (compare LSNs) |
| Cassandra divergence | `nodetool netstats`, `nodetool tablestats`, hinted-handoff metrics, `nodetool repair` |
| Kafka consumer lag | `kafka-consumer-groups.sh --describe` |
| etcd linearizability | `etcdctl endpoint status --cluster`, raft index comparison |
| Verify claims | **Jepsen** (Knossos/Elle checkers) |
| JVM memory bugs | **jcstress**, ThreadSanitizer-style review, `-XX` flags + careful happens-before analysis |

### 9.3 Real-world incidents (publicly documented patterns)

- **Jepsen findings:** numerous databases (early MongoDB, etcd/Consul at points, Cassandra LWT edge cases, Redis Sentinel/Redlock, Elasticsearch, RethinkDB, etc.) were shown to violate their advertised consistency under partition/clock-skew. Lesson: *advertised ≠ verified*; test with fault injection.
- **Redlock debate (Kleppmann vs antirez):** Martin Kleppmann argued Redis's Redlock distributed lock is unsafe for correctness because GC pauses/clock issues can let a stale holder proceed; the fix is **fencing tokens**. A canonical lesson in "locks for efficiency vs locks for correctness."
- **GitHub 2018 outage:** a network partition between regions plus automated failover caused MySQL clusters to diverge; reconciling the split required manual intervention — illustrating the CP/AP tradeoff and the cost of divergence.
- **Generic LWW data loss:** systems using wall-clock LWW (early Cassandra/DynamoDB cross-region) silently dropped concurrent updates; teams discovered missing data only via audits.

---

## 10. Interview drill

Each question: crisp model answer, then deep-probe follow-ups with answers. (★ = senior-signal.)

**Q1. Define linearizability and contrast it with sequential consistency.**
*Answer:* Linearizability: every operation appears to take effect atomically at a single instant between its invocation and response, there is one total order, and that order respects real-time (if A finishes before B starts, A precedes B). Sequential consistency drops the real-time requirement: there's still one total order respecting each process's program order, but it need not match wall-clock across processes. So *linearizability = sequential + real-time*.
- *Probe: Is linearizability composable? Why does it matter?* Yes — if each object is linearizable the whole system is. It lets you reason object-by-object; sequential consistency lacks this.
- *Probe: Give a history that's sequential but not linearizable.* P1 writes x=1 and finishes; later (real time) P2 reads x=0. A single total order P2.read < P1.write satisfies sequential (program orders intact) but violates real-time, so not linearizable.
- *Probe: How do you implement linearizable reads in Raft?* ReadIndex (confirm leadership via heartbeat quorum) or a leader lease, then read the applied state — so a deposed leader can't serve stale data.

**Q2. What is the C in CAP, and why is "CA" misleading?**
*Answer:* C = linearizability. CAP says during a partition you must choose C or A. "CA" is misleading because real networks can partition, so you must handle P; the genuine choice is CP vs AP. CAP only constrains the partitioned moment.
- *Probe: How does PACELC refine this?* It adds the no-partition case: Else, trade Latency vs Consistency — capturing the everyday cost of strong consistency.
- *Probe: Classify Spanner and Cassandra in PACELC.* Spanner PC/EC; Cassandra (default) PA/EL.

**Q3. Explain the four client-centric (session) guarantees.**
*Answer:* RYW (see your own writes or newer), Monotonic Reads (never go backward), Monotonic Writes (your writes applied in issue order), Writes-Follow-Reads (a write you make is ordered after writes you read). Together ≈ session causality, cheaply implementable atop eventual stores.
- *Probe: Which prevents "reply visible before message"?* Writes-follow-reads (across users) / causal consistency.
- *Probe: How implement RYW over an eventual store?* Carry the version you wrote in the session and route reads to a replica that has applied at least that version.

**Q4. ★ When would you deliberately choose eventual over strong consistency, and how do you make it safe?**
*Answer:* When availability and low latency dominate and staleness is harmless (likes, view counts, caches, telemetry). Make it safe by layering session guarantees (RYW, monotonic reads) for UX, using CRDTs or conditional writes to avoid lost updates, bounding staleness for SLAs, and reserving linearizable primitives only for true invariants. The senior move is to classify data by *required* model and pay only where needed.
- *Probe: Risk of eventual for auth?* Revoked permissions linger until convergence — security hole; auth-critical paths need at least RYW + bounded staleness.
- *Probe: How avoid lost updates under eventual?* CAS/conditional writes, CRDTs, or transactions — never naive read-modify-write.

**Q5. Consistency models vs isolation levels — how are they different?**
*Answer:* Consistency models are about *single-object* reads across *replicas* (strong point = linearizability). Isolation levels are about *multi-object transactions* (strong point = serializability). Orthogonal axes; combine to strict serializability (Spanner's external consistency).
- *Probe: Define write skew and which level allows it.* Two txns read overlapping data, then write disjoint rows, breaking an invariant; *snapshot isolation* allows it, *serializable* forbids it.
- *Probe: Can a system be serializable but not linearizable?* Yes — serializability doesn't constrain real-time order; you can observe a "stale" but serial result.

**Q6. ★ Justify a consistency choice for a multi-region "user wallet balance" feature.**
*Answer:* Money requires no lost updates and a single truth → strict serializability / linearizable conditional writes for debits. I'd use a single write region (or Spanner) with conditional `balance >= amount` writes, accept higher write latency, and serve *displayed* balance with bounded-staleness reads from nearby replicas for snappy UX, clearly labeling it "as of." The senior signal: separating the *authoritative* write path (strong) from the *display* read path (bounded stale).
- *Probe: Why not eventual + CRDT counter for balance?* A PN-counter can't enforce "never below zero" — that invariant needs coordination; CRDTs converge but can't reject a concurrent overdraft.
- *Probe: How prevent double-spend under retries?* Idempotency keys + conditional writes so a replayed debit is a no-op.

**Q7. What are CRDTs and what do they guarantee?**
*Answer:* Data types whose concurrent updates merge deterministically with no coordination, giving Strong Eventual Consistency: replicas with the same update set have identical state. State-based merge must be commutative/associative/idempotent (a semilattice join); op-based ops must commute under causal delivery.
- *Probe: Walk through G-Counter merge.* Per-replica counts; merge = element-wise max; value = sum. Max is CAI, so convergence is automatic.
- *Probe: Why OR-Set over 2P-Set?* 2P-Set can't re-add a removed element; OR-Set tags adds with unique ids so concurrent add survives remove and re-adds work.

**Q8. ★ Critique using a distributed lock (e.g., Redlock) for correctness.**
*Answer:* Locks-for-correctness are unsafe without fencing: a holder can pause (GC/STW) past its lease, the lock gets reassigned, and the paused holder resumes and corrupts shared state. The fix is a monotonic *fencing token* the protected resource validates, rejecting stale holders. Redlock specifically also relies on bounded clock drift, which is fragile. Use locks for *efficiency*, but back correctness with fencing or a linearizable store (etcd/ZooKeeper) + tokens.
- *Probe: Where does the fencing token get checked?* At the resource (e.g., storage), which tracks the highest token seen and rejects lower ones.
- *Probe: Alternative to locking entirely?* Make the operation idempotent / conditional (CAS), removing the need for mutual exclusion.

**Q9. How does quorum tuning (N/W/R) relate to consistency?**
*Answer:* With `W+R>N`, every read quorum overlaps the latest write quorum, giving read-after-write (strong-ish) consistency; `W+R≤N` gives eventual. `W=N` maximizes read freshness; `R=1,W=N` favors reads; `W=1,R=N` favors writes.
- *Probe: Why isn't `W+R>N` truly linearizable?* Concurrent writes, partial/failed writes, sloppy quorums, and clock-skew LWW can produce non-linearizable histories; need consensus/LWT for true linearizability.
- *Probe: What's sloppy quorum + hinted handoff?* Under failure, writes go to *any* N healthy nodes (not the canonical owners), storing hints to replay later — boosts availability but weakens the overlap guarantee.

**Q10. Explain causal consistency and how it's implemented.**
*Answer:* Causally related operations are seen in the same order by everyone; concurrent ones may differ. Implemented by tracking happens-before (vector clocks / dependency metadata): a write carries its causal dependencies, and a replica makes it visible only after applying all dependencies.
- *Probe: Cost?* Metadata growth (vector clocks ~O(writers)); mitigated by dotted version vectors, HLC, compression.
- *Probe: Why is causal special wrt CAP?* It's the strongest model achievable while staying always-available under partition.

**Q11. ★ Your team says "we'll just turn on strong consistency everywhere." Push back.**
*Answer:* Strong consistency everywhere multiplies latency (quorum/cross-region round trips), reduces availability under partition (CP), and costs more (2× reads in DynamoDB, commit-wait in Spanner, contention from LWT/transactions). Most data doesn't need it. The right approach is per-feature classification: linearizable for invariants, session guarantees for UX, eventual/CRDT for the rest — paying for strength only where correctness demands it. Blanket strong consistency is over-engineering that hurts p99 and uptime.
- *Probe: Concrete latency number?* Cross-region quorum can add 50–150 ms per write; on a hot path that's catastrophic for UX.
- *Probe: How decide per feature?* Apply the decision flow (Section 8.4): need a single global truth now? invariants? cause-before-effect? auto-merge? freshness bound? — pick the weakest sufficient model.

**Q12. What is the Java Memory Model and how does it relate to distributed consistency?**
*Answer:* The JMM is a consistency model for shared memory: it defines via happens-before which writes are visible to which reads across threads. `volatile`/`synchronized`/atomics create happens-before edges, exactly analogous to choosing replication consistency levels. CAS is the JVM analog of a distributed conditional write.
- *Probe: Why isn't `volatile x; x++` safe?* `volatile` gives visibility/ordering but not atomic read-modify-write; the increment can interleave. Use `AtomicLong`/`LongAdder`.
- *Probe: What edges does `synchronized` create?* Unlock-of-monitor happens-before subsequent lock-of-same-monitor, publishing all prior writes.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability; transaction properties. The "C" means invariants hold, *not* linearizability.
- **Anti-entropy** — background reconciliation comparing replicas (often via Merkle trees) to repair divergence.
- **Atomic broadcast (total-order broadcast)** — delivering messages to all nodes in the same order; equivalent to consensus.
- **Availability (CAP)** — every request to a non-failed node returns a non-error response.
- **Bounded staleness** — reads lag the latest write by at most K versions or T seconds.
- **CAP theorem** — under partition, choose Consistency (linearizability) or Availability.
- **CAS (compare-and-swap)** — atomic conditional update; succeeds only if the value is unchanged.
- **Causal consistency** — causally related ops seen in the same order everywhere; concurrent ops may reorder.
- **Causal+ / convergent causal** — causal consistency plus convergence of concurrent writes.
- **Composable / local property** — a per-object property that, if held by all objects, holds for the whole system (linearizability is; sequential isn't).
- **Concurrent operations** — operations whose real-time intervals overlap.
- **Conflict-free Replicated Data Type (CRDT)** — type whose concurrent updates merge deterministically without coordination.
- **Consensus** — agreeing on one value/ordered log despite failures (Paxos/Raft/Zab).
- **Consistent hashing** — key→node mapping minimizing remapping on membership change.
- **Convergence** — replicas reaching the same state given the same updates.
- **Coordinator** — the node handling a client request in leaderless replication.
- **Dirty read** — reading another transaction's uncommitted data.
- **Eventual consistency** — replicas converge if writes stop; no ordering/freshness guarantee.
- **External consistency** — Spanner's term for strict serializability.
- **Fencing token** — monotonic number validating a lock holder's freshness to a resource.
- **Follower / secondary / replica** — non-leader copy receiving the replication stream.
- **G-Counter / PN-Counter / OR-Set / LWW-Register** — common CRDTs (grow-only counter; +/- counter; observed-remove set; last-writer-wins register).
- **Happens-before (→)** — Lamport's causal ordering relation (also the JMM's core).
- **Hinted handoff** — temporarily storing a write for a down replica to replay later.
- **HLC (Hybrid Logical Clock)** — physical time + logical counter; tracks causality, near wall-clock, constant size.
- **Isolation level** — how transactions see each other's effects (read uncommitted → serializable).
- **Java Memory Model (JMM)** — the JVM's shared-memory consistency model.
- **Jepsen** — fault-injection framework that verifies consistency claims.
- **Lamport timestamp** — scalar logical clock; total order consistent with happens-before, can't detect concurrency.
- **Leader / primary** — the replica that accepts writes in leader-based replication.
- **Leaderless (Dynamo-style)** — clients write/read many replicas with quorums.
- **Linearizability** — strongest single-object model: atomic effect at one instant, single real-time-respecting order.
- **Lost update** — concurrent RMWs where one overwrites the other.
- **LWT (Lightweight Transaction)** — Cassandra's Paxos-based linearizable compare-and-set.
- **LWW (last-writer-wins)** — conflict resolution by highest timestamp; can drop data.
- **Merkle tree** — tree of hashes enabling efficient detection of differing data ranges.
- **Monotonic reads (MR)** — a client's reads never return older data than before.
- **Monotonic writes (MW)** — a client's writes apply in issue order everywhere.
- **Multi-leader replication** — multiple write-accepting nodes; needs conflict resolution.
- **MVCC** — multiple row versions enabling non-blocking consistent snapshots.
- **Non-repeatable read** — re-reading a row yields a different value within a transaction.
- **N/W/R** — replica count, write-ack quorum, read-ack quorum.
- **PACELC** — if Partition then A/C, Else Latency/Consistency.
- **Partition (network)** — nodes can't all communicate due to network failure.
- **Phantom** — a re-run query returns new rows.
- **Program order** — the order a single process issues its operations.
- **Quorum** — required subset of replicas to ack a read/write.
- **Raft / Paxos / Zab** — consensus protocols producing replicated logs.
- **Read repair** — fixing stale replicas using the freshest value seen on a read.
- **Read-your-writes (RYW)** — a client always sees its own prior writes (or newer).
- **Register** — a single-value shared object supporting read/write.
- **Replica** — a copy of data on another node/region/memory location.
- **Replicated State Machine (RSM)** — identical state machines fed the same command order stay identical.
- **Semilattice** — set with a commutative/associative/idempotent join (CRDT math foundation).
- **Sequential consistency** — single total order respecting program order, not real time.
- **Serializability** — transactions equivalent to some serial execution.
- **Session** — scope within which client-centric guarantees hold.
- **Sloppy quorum** — quorum from any healthy nodes (not canonical owners) during failure.
- **Snapshot Isolation (SI)** — read from a consistent snapshot; allows write skew.
- **Split brain** — two nodes both believe they're leader and accept writes.
- **Staleness** — how far behind the latest write a read may be.
- **Strict consistency** — instantaneous global visibility (theoretical, unimplementable).
- **Strict serializability** — serializability + linearizable real-time order.
- **Strong consistency** — colloquial for linearizable (always disambiguate!).
- **Strong eventual consistency (SEC)** — same updates ⇒ same state, no rollback (CRDT property).
- **Term / epoch** — leadership logical clock in consensus protocols.
- **TrueTime** — Spanner's bounded-uncertainty clock API.
- **Vector clock** — per-process counter vector precisely tracking happens-before/concurrency.
- **Volatile (Java)** — field modifier giving cross-thread visibility and ordering, not compound atomicity.
- **Write skew** — two txns read overlapping data, write disjointly, breaking an invariant (allowed by SI).
- **Writes-follow-reads (WFR)** — a write is ordered after writes the client previously read.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Spectrum (strong → weak):** Strict ▸ **Linearizable** ▸ Sequential ▸ **Causal** ▸ Bounded-staleness ▸ {RYW, MR, MW, WFR session} ▸ **Eventual**.

**Two axes:** *Consistency* (single-object, replicas) — strong point **linearizability**. *Isolation* (multi-object, transactions) — strong point **serializability**. Both ⇒ **strict serializability** (= Spanner external consistency).

**Key equalities:**
- Linearizable = Sequential + real-time order.
- Strict-serializable = Serializable + linearizable real-time.
- Causal = strongest always-available-under-partition model.
- CRDT ⇒ Strong Eventual Consistency (merge = commutative+associative+idempotent).

**CAP:** under partition choose C(linearizable) or A. **PACELC:** else choose L or C. CP = Spanner/etcd/ZK-writes; AP = Cassandra/Dynamo(eventual)/Riak.

**Quorum rule:** `W + R > N` ⇒ read-after-write; necessary but *not sufficient* for full linearizability.

**Numbers/defaults to remember:** DynamoDB default read = eventual; strong = 2× RCU. Mongo default `readConcern local`, use `majority`/`linearizable` for safety, `w:"majority"` for durability. Cosmos default = Session. Cassandra production default ≈ `LOCAL_QUORUM`. Spanner pays a few ms commit-wait. Cross-region quorum ≈ 50–150 ms/write.

**Decision rule:** pick the *weakest model your correctness allows*; layer session guarantees for UX; reserve linearizable/strict-serializable for true invariants; use CRDTs for lossless multi-master merge; bound staleness when you need a freshness SLA.

**Anti-patterns:** eventual-everywhere without RYW/MR; read-after-write from a follower; wall-clock LWW across machines; locks-for-correctness without fencing tokens; assuming "strong" means the same across vendors.

**JVM mapping:** plain=race; `volatile`=visibility/order (not atomic RMW); `synchronized`/atomics=happens-before+atomicity; CAS=conditional write analog; JMM happens-before = Lamport happens-before for memory.

### 12.2 Self-test (no answers — active recall)

1. Construct a 3-process history that is sequentially consistent but **not** linearizable, then explain precisely which property fails.
2. Your service shows users their own profile edits instantly but other users' edits are sometimes a few seconds stale, and reads never go backward. Name the exact combination of consistency guarantees you've implemented and the cheapest mechanism to provide each.
3. A PN-Counter CRDT is proposed for an account balance that must never go negative. Explain whether this works and, if not, what minimal coordination you must add and why CRDT math cannot supply it.
4. You configure Cassandra with `N=3`, write `CL=QUORUM`, read `CL=ONE`. What consistency do you actually get, what anomaly can a user observe, and what is the minimal change to fix it without going to `ALL`?
5. Explain why `W + R > N` does not guarantee linearizability, giving a concrete interleaving of two concurrent writes that produces a non-linearizable read history.
6. Map each of these to CP or AP and to a PACELC class, justifying each: Spanner, default Cassandra, etcd, DynamoDB eventual reads, Redis async replication.
7. A distributed lock built on Redis is used to guard a critical section that writes to S3. Describe the exact failure sequence (including a JVM-specific cause) that corrupts S3, and the precise mechanism that prevents it.

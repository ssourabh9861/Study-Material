# ACID vs BASE

> A definitive engineering-handbook chapter on transactional guarantees, consistency models, and how to choose a data store. Written for a senior JVM/Java backend developer who wants to master this from first principles to deep internals.

---

## 1. Overview & where it fits

When you write data to a store, you implicitly trust a bundle of promises: that your write won't be half-applied, that other people won't see a corrupt in-between state, that what you read back is correct, and that once the store says "done" the data survives a crash. **ACID** and **BASE** are two named *families* of such promises. They sit at opposite ends of a spectrum of how strongly a data store guarantees correctness versus how aggressively it optimizes for availability, latency, and horizontal scale.

- **ACID** (Atomicity, Consistency, Isolation, Durability) is the contract of classical transactional databases — PostgreSQL, MySQL/InnoDB, Oracle, SQL Server, and the transactional cores of many newer systems. It says: *transactions are all-or-nothing, leave the database valid, behave as if run one at a time, and survive crashes once committed.* You reach for ACID when correctness is non-negotiable — money, inventory, bookings, identity, anything where a wrong answer is a real-world incident.

- **BASE** (Basically Available, Soft state, Eventual consistency) is a deliberately loosened contract popularized by large-scale distributed systems (Amazon Dynamo, Cassandra, Riak, DynamoDB in eventually-consistent mode). It says: *the system stays available even under partitions and failures, the data may be temporarily inconsistent across replicas, and it converges to a consistent state given enough time and no new writes.* You reach for BASE when availability and scale matter more than reading the absolute latest value at every instant — feeds, view counters, product catalogs, session caches, telemetry.

**The problem each solves.** ACID solves the problem of *concurrent, fault-prone access to shared mutable state without corrupting it*. Before ACID disciplines existed, applications hand-rolled locking and crash recovery and got it wrong constantly. BASE solves a *different* problem: keeping a system *up and fast* across many machines and across network partitions, where insisting on strong consistency would force you to reject requests or stall.

**Where it fits in the larger map.** ACID vs BASE is one axis of "choosing the right store." It interacts tightly with the **CAP theorem** (consistency vs availability under partition), the **PACELC** extension (and the latency-vs-consistency tradeoff even when there's no partition), the **data model** (relational vs document vs key-value vs wide-column vs graph), and the **scaling model** (single-node vs sharded vs globally replicated). ACID was historically tied to single-node SQL; BASE to distributed NoSQL. That mapping is now obsolete: **NewSQL** systems (Google Spanner, CockroachDB, YugabyteDB, TiDB) deliver ACID transactions *across* a distributed cluster, and many "NoSQL" systems have bolted on stronger guarantees. So ACID vs BASE is best understood not as two boxes but as a **dial**.

**One-paragraph mental model.** Picture a dial from "strict" to "loose." At the strict end, every read sees the latest committed write, transactions are isolated and crash-safe, and the system may refuse service or add latency to keep that promise. At the loose end, the system always answers quickly and stays up, but a read might return a slightly stale value and a write might take a moment to propagate everywhere. ACID is the strict end; BASE is the loose end. Real systems pick a point on the dial — often *per operation*. The engineering skill is knowing which point each piece of your data needs, and why.

---

## 2. Foundations from first principles

Let's build the vocabulary from zero. Read this section even if some terms feel familiar — the precise definitions matter and people routinely confuse them (especially the two different meanings of "consistency").

### 2.1 What is a transaction?

A **transaction** is a unit of work — one or more reads and writes — that the database treats as a single logical operation. The canonical example: transfer $100 from account A to B. That's two writes (debit A, credit B). A transaction lets you say "do both or neither." The two markers are **BEGIN** (start) and **COMMIT** (make it permanent) or **ROLLBACK/ABORT** (undo everything since BEGIN).

```sql
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 'A';
UPDATE accounts SET balance = balance + 100 WHERE id = 'B';
COMMIT;   -- both updates become durable atomically; or ROLLBACK to undo both
```

### 2.2 What does a database protect against?

Two adversaries:

1. **Crashes / power loss / process kills** — the machine dies mid-write. The database must come back to a sane state.
2. **Concurrency** — many transactions run at the same time, interleaving their reads and writes. Without discipline, they corrupt each other's view of the data.

ACID is the set of guarantees that defeat both adversaries. Let's define each letter precisely.

### 2.3 Atomicity — "all or nothing"

**Atomicity** means a transaction's effects are *indivisible*: either every write in it takes effect, or none does. There is no partial application visible after the fact. If the transfer above crashes after debiting A but before crediting B, atomicity guarantees that on recovery the debit is *undone* — money is not destroyed.

> Note: "atomic" here is about *transaction boundaries*, not about CPU-level atomic instructions. Different layer, same English word.

**How databases provide atomicity (the mechanism):** primarily via a **write-ahead log (WAL)** plus an **undo** capability.
- A **write-ahead log (WAL)** is an append-only file where the database records *what it is about to change* **before** it changes the actual data pages. ("Write-ahead" = log first, data later.) This is the single most important durability/recovery mechanism in databases; we'll return to it repeatedly.
- To roll back, the engine either keeps **undo information** (old values, so it can reverse changes) or simply never makes uncommitted changes durable. On recovery, the engine reads the WAL and *undoes* any transaction that didn't reach COMMIT.

### 2.4 Consistency — and its two meanings (this trips everyone up)

There are **two completely different "C"s** that share the word "consistency." Conflating them is the #1 conceptual error in this area.

**(a) The ACID "C" — consistency as integrity constraints.** This means a transaction moves the database from one *valid* state to another *valid* state, where "valid" is defined by **integrity constraints**: things like primary keys (no duplicate IDs), foreign keys (no orphan references), CHECK constraints (`balance >= 0`), uniqueness, NOT NULL, and triggers. If a transaction would violate a constraint, it's rejected. Critically, the database only enforces the rules *you declared*; it cannot know that "debit + credit must net to zero" unless you encode that. So the ACID C is partly the application's responsibility — it's the weakest, least "database-y" of the four letters, and many authors argue it doesn't really belong with the other three.

**(b) The CAP/distributed "C" — consistency as a recency guarantee.** In distributed-systems literature (CAP theorem, consistency models), "consistency" means something about *what value a read returns* across replicas. The strongest form is **linearizability**: the system behaves as if there is a single copy of the data and every operation takes effect at a single instant between its start and end; once a write completes, every later read (by wall-clock time) sees it. This is a *recency/ordering* guarantee, totally unrelated to integrity constraints.

When someone says "eventual consistency" (the E in BASE), they mean the *CAP* kind. When ACID lists "Consistency," it means the *integrity-constraint* kind. **Keep these separate in your head.** Throughout this doc, I'll write **C-integrity** vs **C-recency** when ambiguity is possible.

### 2.5 Isolation — "as if alone"

**Isolation** means concurrent transactions don't step on each other: the end result is *as if* transactions ran one after another (serially), even though they actually ran interleaved for performance. The gold standard is **serializability** — there exists *some* serial order of the transactions producing the same result as the actual concurrent execution.

Perfect isolation is expensive, so SQL defines weaker **isolation levels** that permit certain **anomalies** in exchange for more concurrency. The classic anomalies:

- **Dirty read** — transaction T1 reads data written by T2 that T2 hasn't committed (and might roll back). You read a value that never officially existed.
- **Non-repeatable read** — T1 reads a row, T2 updates and commits it, T1 reads the same row again and gets a *different* value within the same transaction.
- **Phantom read** — T1 runs a query (`WHERE age > 30`), T2 inserts a new row matching that predicate and commits, T1 reruns the query and sees a new ("phantom") row.
- **Lost update** — T1 and T2 both read a value, both compute a new value from it, both write; one write silently overwrites the other.
- **Write skew** — two transactions read overlapping data, make disjoint writes based on what they read, and the combination violates an invariant that each alone would have preserved (the textbook case: two on-call doctors each check "at least one other doctor is on duty" and both go off-shift simultaneously).

The SQL-standard isolation levels and which anomalies they (by the standard) allow:

| Isolation level | Dirty read | Non-repeatable read | Phantom read |
|---|---|---|---|
| READ UNCOMMITTED | Possible | Possible | Possible |
| READ COMMITTED | Prevented | Possible | Possible |
| REPEATABLE READ | Prevented | Prevented | Possible (standard) |
| SERIALIZABLE | Prevented | Prevented | Prevented |

We'll see in §3 and §7 that real engines deviate from the standard (e.g., PostgreSQL's REPEATABLE READ prevents phantoms; "SERIALIZABLE" means different things across engines).

**How databases provide isolation (the mechanism):** two broad strategies, often combined.
- **Locking (pessimistic):** transactions acquire **locks** (shared/read locks, exclusive/write locks) on rows/ranges/tables; conflicting access blocks until the lock is released. **Two-phase locking (2PL)** — acquire all locks before releasing any — yields serializability. Downsides: blocking and **deadlocks** (two transactions each waiting for a lock the other holds).
- **MVCC (Multi-Version Concurrency Control, optimistic-ish):** instead of overwriting data in place, the database keeps **multiple versions** of each row tagged with the transaction that created them. Readers get a consistent **snapshot** (the set of versions visible as of their start) and *never block writers*; writers *never block readers*. Conflicts between writers are detected and one transaction aborts. MVCC is the dominant modern approach (PostgreSQL, Oracle, MySQL/InnoDB, SQL Server's snapshot isolation, most NewSQL). We'll dissect it in §3.

### 2.6 Durability — "once committed, it survives"

**Durability** means once the database acknowledges COMMIT, the data survives crashes — process kill, OS panic, power loss. The data must be on **non-volatile storage** (disk/SSD), not just in RAM (volatile, lost on power loss).

**How databases provide durability (the mechanism):**
- **WAL again:** at COMMIT, the engine ensures the transaction's log records are physically on stable storage *before* returning success. The actual data pages can be flushed lazily later; the log is enough to reconstruct everything.
- **fsync():** a system call (a "syscall" = a request from a program to the operating-system kernel) that forces buffered file writes from OS page cache and disk-controller caches down to the physical medium. Without fsync, a "written" file may still be sitting in volatile caches and vanish on power loss. Durability hinges on a correct fsync (and on hardware honoring it — see §9).
- **Replication:** copying committed data to other machines so a single node's permanent failure doesn't lose data. Durability "to disk on one node" and durability "replicated to N nodes" are different strengths (see §7.4).

### 2.7 Now, BASE

BASE is a backronym coined as a deliberate chemistry pun (acids vs bases) to describe the philosophy of highly available distributed systems, articulated in Eric Brewer's work and Pat Helland / eBay-era writing.

- **Basically Available** — the system *always responds* to requests (it doesn't refuse service), though the response might be stale, a failure for that key, or a default. Availability is prioritized.
- **Soft state** — the state of the system may change over time *even without new input*, because of background convergence: replicas reconcile, anti-entropy repairs run, hinted writes get delivered. The system doesn't guarantee a single fixed truth at every instant.
- **Eventual consistency** — if no new updates are made to a given item, *eventually* all replicas will converge to the same (last) value. There's no bound (by default) on how long "eventually" takes.

BASE is the natural consequence of choosing **Availability** over **Consistency** in the face of network **Partitions** (the CAP theorem, §2.8). Where ACID says "be correct, even if you must wait or refuse," BASE says "always answer fast, even if sometimes slightly wrong, and fix it up later."

### 2.8 CAP and PACELC (the bridge concepts)

**CAP theorem** (Brewer; formalized by Gilbert & Lynch). In a distributed data store, during a **network partition** (a "partition" = the network splits so some nodes can't talk to others), you can guarantee **Consistency** (every read sees the latest write — i.e., linearizability) *or* **Availability** (every request gets a non-error response) but **not both**. You must drop one *while the partition lasts*. Note: CAP's "C" is **C-recency** (linearizability), and the theorem only forces the choice *during a partition* — it's not "pick 2 of 3 always," a common misreading. When there's no partition you can have both C and A.

- **CP system** (consistency under partition): refuses or blocks requests on the minority side to avoid returning stale/conflicting data. Examples: ZooKeeper, etcd, HBase, Spanner, MongoDB (with majority concerns), classic single-leader RDBMS replication when it requires quorum.
  - *(ZooKeeper / etcd:* small, strongly-consistent coordination stores used to hold configuration, leader-election state, and locks for larger systems. They use consensus protocols — ZAB and Raft respectively — to keep a replicated log identical across nodes.)*
- **AP system** (availability under partition): keeps serving on both sides, accepting that they may diverge and reconcile later. Examples: Cassandra, Riak, DynamoDB (eventually-consistent reads), Dynamo-style stores. These are the BASE systems.

**PACELC** extends CAP to cover the no-partition case: *if Partition, choose Availability or Consistency; Else (normal operation), choose Latency or Consistency.* Even with a healthy network, a system that wants strong consistency must coordinate replicas, which costs **latency**. So Spanner is "PC/EC" (consistent in both cases, paying latency); Cassandra is "PA/EL" (available under partition, low-latency otherwise, both at the cost of consistency); DynamoDB is tunable. PACELC is the more honest framework because the latency-vs-consistency tradeoff is the one you pay *every single request*, partitions or not.

### 2.9 Consensus, Raft, Paxos (you'll see these in NewSQL)

To keep replicas strongly consistent, distributed databases run a **consensus protocol** — an algorithm letting a group of nodes agree on a single ordered sequence of operations (a **replicated log**) despite crashes and message loss.

- **Paxos** — the original (Lamport) consensus algorithm; correct but famously hard to understand and implement. Google uses Paxos variants (e.g., in Spanner and Chubby).
- **Raft** — a consensus algorithm designed to be understandable; it elects a **leader**, the leader appends entries to its log and replicates them to **followers**, and an entry is **committed** once a **majority (quorum)** has stored it. (CockroachDB, TiDB, etcd, YugabyteDB use Raft.)
- **Quorum** — a majority subset of replicas. With *N* replicas, a quorum is ⌊N/2⌋+1. Requiring a write to reach a quorum and a read to consult a quorum guarantees the read sees the latest write, because any two majorities overlap in at least one node.

These are how NewSQL provides ACID across machines: the replicated log gives a single agreed order (helping isolation/atomicity across nodes), and quorum gives durability and recency.

### 2.10 MVCC vs locks, snapshot, version (terms used heavily later)

- **Snapshot** — a consistent point-in-time view of the whole database (all the row versions visible as of some logical instant). MVCC readers operate against a snapshot.
- **Tuple/row version** — a physical copy of a row tagged with the transaction IDs that created and (eventually) deleted it. The engine decides which version is visible to which snapshot.
- **Vacuum / compaction / garbage collection** — background processes that remove old versions no longer visible to any live transaction, reclaiming space (PostgreSQL's `VACUUM`, InnoDB's purge, LSM compaction).

With the vocabulary in hand, let's open the hood.

---

## 3. How it works internally

This is the heart of the chapter. We'll trace, step by step, what actually happens inside engines to provide each ACID property, then how BASE systems behave internally, then how NewSQL bridges them.

### 3.1 The write path and the WAL (atomicity + durability together)

ACID's A and D are delivered by the same machinery: the **write-ahead log**. Here's the canonical lifecycle of a committing transaction in a WAL-based engine (PostgreSQL/InnoDB-style). Terms are defined inline.

**Components:**
- **Buffer pool / page cache** — an in-RAM cache of disk pages the engine reads and modifies. Modified-but-not-yet-written pages are **dirty pages**.
- **WAL buffer** — an in-RAM staging area for log records before they're flushed to the WAL file.
- **WAL file (a.k.a. redo log / transaction log)** — the append-only on-disk log.
- **Checkpoint** — a periodic operation that flushes dirty pages to their real locations and records "everything up to log position X is safely in the data files," so recovery doesn't have to replay the entire log from the beginning of time.

**Step-by-step COMMIT (control + data flow):**

1. **BEGIN.** The engine assigns a **transaction ID (XID/txid)** — a monotonically increasing number identifying this transaction for visibility and locking.
2. **Modify in memory.** Each `UPDATE/INSERT/DELETE` changes pages **in the buffer pool** (RAM), marking them dirty. The data file on disk is *not* touched yet.
3. **Generate WAL records.** For every change, the engine appends a **redo** record (how to reproduce the change) — and, for rollback, **undo** information (how to reverse it; in PostgreSQL the old version stays as a separate tuple, in InnoDB undo lives in the undo log/tablespace) — into the **WAL buffer**.
4. **COMMIT issued.** The engine writes a **commit record** to the WAL buffer.
5. **Flush WAL (the critical step).** The engine writes the WAL buffer to the WAL file and calls **fsync()** to force it to stable storage. *Only after this fsync returns does the engine return "committed" to the client.* This is the durability guarantee: the log is on disk before the client is told "done."
6. **Acknowledge.** Client gets COMMIT success.
7. **Lazily flush data pages.** Later, at a checkpoint (or under memory pressure), dirty data pages are written to the actual data files and fsync'd. Their content is already safe in the WAL, so a crash before this is fine.

**Recovery after a crash (the payoff):** on restart the engine performs **ARIES-style recovery** (ARIES = the classic recovery algorithm: *Algorithm for Recovery and Isolation Exploiting Semantics*):
1. **Analysis** — scan the WAL from the last checkpoint to find which transactions were in-flight and which dirty pages existed.
2. **Redo** — replay *all* logged changes (even uncommitted ones) to bring data files up to the crash point. This restores work that was committed but whose data pages hadn't been flushed.
3. **Undo** — roll back transactions that were in-flight (never committed) using undo info, giving **atomicity** (no partial transactions survive).

This is why "log first" works: the WAL is the source of truth at recovery, the data files are a lazily-updated materialization. Atomicity = undo of losers; durability = redo of winners.

**Why not just write data pages directly and skip the log?** Because page writes aren't atomic (a page can be torn mid-write across a power loss), aren't ordered, and force random I/O. The WAL turns many small random data-page writes into one sequential append + fsync, which is dramatically faster and crash-safe. (PostgreSQL additionally protects against **torn pages** with *full-page writes*: the first modification of a page after a checkpoint logs the entire page image.)

### 3.2 Group commit (durability throughput optimization)

fsync is slow (a real disk fsync is hundreds of microseconds to several milliseconds). If every committing transaction did its own fsync, throughput would be capped at ~(1 / fsync latency) commits/sec — maybe a few hundred to a few thousand. **Group commit** batches the WAL flushes of many concurrent transactions into a *single* fsync: transactions arriving in a small window all wait, then one fsync makes all of them durable at once. This amortizes the fsync cost across the batch, raising throughput by 10–100×.

- PostgreSQL: governed by `commit_delay` (microseconds to wait to accumulate a batch; default `0`) and `commit_siblings` (minimum concurrent transactions before delaying; default `5`). Even with defaults, PostgreSQL does implicit group commit under load via the WAL writer.
- MySQL/InnoDB: binary-log and redo-log group commit, with `binlog_group_commit_sync_delay` etc.

Tradeoff: group commit trades a tiny latency increase (the wait window) for large throughput gains. It does *not* weaken durability — every transaction in the batch is fully fsync'd before ack.

### 3.3 Isolation via MVCC — step by step

Let's trace MVCC, the dominant isolation mechanism. We'll use PostgreSQL's model (InnoDB and Oracle differ in storage details but share the core idea).

**Data layout.** Every row version (**tuple**) carries hidden system columns: `xmin` (the XID that created/inserted this version) and `xmax` (the XID that deleted/updated-away this version; 0 if still live). An UPDATE doesn't overwrite — it marks the old tuple's `xmax` and inserts a new tuple with a fresh `xmin`.

**Snapshot.** When a transaction (or, at READ COMMITTED, each statement) starts, it takes a **snapshot**: essentially the set of XIDs that had committed at that instant (and which are still in flight). A tuple version is **visible** to a snapshot if its `xmin` is committed-as-of-the-snapshot and its `xmax` is *not* committed-as-of-the-snapshot (i.e., it was created by a visible transaction and not yet deleted by one).

**Step-by-step read under MVCC (READ COMMITTED):**
1. Statement begins, takes a fresh snapshot.
2. For each candidate tuple, the engine checks visibility using `xmin`/`xmax` against the snapshot's commit info.
3. Returns the version visible to *this* snapshot. Concurrent uncommitted changes by others are invisible (no dirty reads); committed changes from before the statement are visible.
4. Readers acquire *no row locks* and *block no one* — the snapshot already isolates them.

**Step-by-step write under MVCC:**
1. Writer takes an **exclusive row lock** (or uses optimistic conflict detection) on the row it's modifying — to serialize concurrent writers to the *same* row and prevent lost updates.
2. It marks the current version's `xmax = my_xid` and inserts a new version with `xmin = my_xid`.
3. On COMMIT, the engine records that `my_xid` committed (in PostgreSQL, in the **commit log / clog** — a bitmap of transaction outcomes). Visibility flips atomically: every later snapshot now sees the new version.
4. On ROLLBACK, the new version is simply never made visible (its `xmin` belongs to an aborted XID), and the old version's `xmax` is ignored. Atomicity falls out for free.

**Snapshot isolation (SI) vs serializable.** Plain MVCC gives **snapshot isolation**: each transaction sees a frozen snapshot from its start. SI prevents dirty/non-repeatable reads and (in PostgreSQL) phantoms, but **allows write skew** (§2.5) because two transactions read the same snapshot and write disjoint rows without conflicting. To get true serializability, PostgreSQL adds **Serializable Snapshot Isolation (SSI)**: it tracks read/write dependencies between concurrent transactions and aborts one if a dangerous cycle (which could produce a non-serializable outcome) is detected. SSI keeps MVCC's non-blocking reads but adds **serialization-failure** aborts the application must retry.

**Garbage: VACUUM.** Because old versions accumulate, MVCC engines need cleanup. PostgreSQL's **VACUUM** (and **autovacuum** background process) removes dead tuples no longer visible to any snapshot and prevents **transaction-ID wraparound** (XIDs are 32-bit and must be frozen before they wrap — a famous operational hazard). InnoDB's **purge threads** drop old undo-log versions. LSM stores compact. If cleanup falls behind, you get **table bloat**, slow scans, and (in extreme PostgreSQL cases) a forced read-only shutdown to prevent wraparound corruption.

### 3.4 Isolation via locking — step by step (2PL)

The alternative to MVCC is explicit locking. **Strict two-phase locking (S2PL):**
1. **Growing phase:** as the transaction reads/writes, it acquires **shared locks** (for reads — multiple readers OK) and **exclusive locks** (for writes — sole access).
2. It holds all locks; it never releases until commit/abort (strict variant), preventing others from seeing or overwriting its data.
3. **Shrinking phase:** at COMMIT/ABORT, all locks release at once.

This yields serializability but introduces **blocking** (waiters stall) and **deadlocks** (cyclic waits). Databases run a **deadlock detector** (e.g., a wait-for-graph cycle finder) that periodically aborts a victim transaction to break the cycle, returning a deadlock error the app must retry. To stop phantoms, locking engines use **predicate locks** or **next-key locks** (InnoDB locks the gap between index entries so no one can insert a matching phantom row).

### 3.5 How BASE systems work internally (Dynamo-style)

Now the loose end of the dial. Consider a Dynamo-style AP store (Cassandra/Riak/DynamoDB). The internal mechanisms that produce "basically available, soft state, eventually consistent":

**Partitioning (sharding) via consistent hashing.** Keys are hashed onto a ring; each key maps to a position, and the next *N* nodes clockwise own its **replicas** (replication factor RF = N). **Consistent hashing** means adding/removing a node only remaps a small slice of keys, not everything — crucial for elastic scaling.

**Tunable quorum reads/writes (the heart of eventual vs strong).** Each operation specifies how many replicas must respond:
- **W** = replicas that must ack a write before it's considered successful.
- **R** = replicas that must respond to a read.
- **N** = replication factor.
- If **R + W > N**, the read and write quorums overlap, so a read is guaranteed to see the latest acked write → **strong-ish (read-your-writes/quorum) consistency**.
- If **R + W ≤ N** (e.g., W=1, R=1, N=3), reads and writes may not overlap → reads can be **stale** → eventual consistency, but lowest latency and highest availability (a write succeeds if *any one* replica is up).

This single knob *is* the ACID↔BASE dial inside one system. Cassandra exposes it as **consistency levels** (`ONE`, `QUORUM`, `LOCAL_QUORUM`, `ALL`, `EACH_QUORUM`, etc.). DynamoDB exposes "eventually consistent" (default, cheaper, R effectively <quorum) vs "strongly consistent" reads (R = quorum).

**Step-by-step write in an AP store (W < N):**
1. Client sends write to a **coordinator** node (any node can coordinate).
2. Coordinator forwards to all N replicas for the key.
3. As soon as **W** replicas ack, coordinator returns success to client — *without* waiting for the rest.
4. Replicas that were down or slow miss the write *for now* (soft state). The system is now temporarily inconsistent.

**Step-by-step read (R < quorum):**
1. Coordinator queries R replicas.
2. If they disagree (different versions), it resolves the conflict (last-write-wins by timestamp, or returns siblings, or vector-clock reconciliation) and may trigger **read repair** (write the winning value back to stale replicas).

**Convergence mechanisms (how "eventually" actually happens):**
- **Hinted handoff** — if a replica is down during a write, the coordinator stores a "hint" and delivers the write when the node returns.
- **Read repair** — on a read that detects divergence, push the latest value to lagging replicas.
- **Anti-entropy / Merkle-tree repair** — a background process compares replicas' data (using Merkle trees, which let two nodes find differing ranges with minimal data exchange) and reconciles them. (Cassandra `nodetool repair`.)

**Conflict resolution (when two writes race):**
- **Last-Write-Wins (LWW)** — keep the value with the highest timestamp. Simple but *loses data* if clocks are skewed or two writes truly conflict (Cassandra default).
- **Vector clocks** — metadata tracking causal history so the system can tell "B is a descendant of A" (keep B) vs "A and B are concurrent" (conflict → return both as siblings for the app to merge). (Riak/Dynamo.)
- **CRDTs (Conflict-free Replicated Data Types)** — data structures (counters, sets, registers) designed so concurrent updates *merge deterministically* without conflict (e.g., a G-Counter sums per-replica increments). Used by Riak data types, Redis CRDT, Azure Cosmos DB. CRDTs are how you get useful, mergeable eventual consistency for things like counters and shopping carts.

The famous **Amazon shopping-cart** story: Dynamo used vector clocks; a network partition could resurrect a previously deleted item because "add to cart" merges (union) won over "remove," yielding the *available, occasionally-wrong-but-never-lost-a-sale* behavior that was a deliberate business choice. That's BASE in one anecdote: prefer "always add to cart" over "sometimes reject the add."

### 3.6 How NewSQL bridges (ACID across a distributed cluster)

NewSQL = distributed SQL databases that provide **ACID transactions and strong consistency across many nodes**, killing the old "ACID = single node, scale = give up ACID" tradeoff. The two landmark designs:

**Google Spanner.** Globally distributed, externally-consistent (linearizable) transactions. Two key mechanisms:
1. **Paxos per shard** — data is split into shards ("splits"); each shard is a Paxos group replicated across zones/regions. Writes go through Paxos (quorum), giving durability + agreed order.
2. **TrueTime** — Spanner's secret sauce. **TrueTime** is an API backed by GPS receivers and atomic clocks in every datacenter that returns a *bounded-uncertainty* timestamp interval `[earliest, latest]` (uncertainty ε, typically a few milliseconds). To commit a transaction at timestamp T, Spanner **waits out the uncertainty** ("commit wait" — sleep until T is definitely in the past everywhere) before releasing locks. This makes timestamps globally meaningful, giving **external consistency / linearizability** across the planet. The cost is added commit latency (a few ms) — the PACELC "EC" price.

**CockroachDB / YugabyteDB / TiDB.** Open-source Spanner-inspired systems that *don't* assume specialized clocks:
- Data is range-sharded; each range is a **Raft group** (quorum-replicated). Raft gives the agreed order + durability per range.
- Distributed transactions use a **transaction coordinator**, write **intents** (provisional, MVCC-style uncommitted versions), and commit via a **two-phase commit (2PC)** across the ranges' Raft leaders. (**2PC** = a protocol where a coordinator asks all participants to "prepare" (promise they can commit), and only if *all* vote yes does it tell them to "commit"; otherwise "abort." It's how a transaction spanning multiple shards stays atomic.)
- They use **hybrid logical clocks (HLC)** — a clock combining physical time with a logical counter — plus bounded clock skew assumptions and (in CockroachDB) *uncertainty intervals* + transaction restarts, to approximate Spanner's external consistency without atomic clocks. CockroachDB defaults to **serializable** isolation; YugabyteDB and TiDB offer snapshot/serializable.

So NewSQL achieves ACID at scale by combining: MVCC for isolation, Raft/Paxos for replicated durability and ordering, 2PC for cross-shard atomicity, and clock discipline (TrueTime/HLC) for global recency. The price is per-transaction coordination latency — you pay milliseconds of cross-node round-trips that a single-node ACID DB doesn't.

### 3.7 The full spectrum (the dial, concretely)

Strongest → weakest *consistency/recency* guarantees, with examples:

| Model | Guarantee | Example systems |
|---|---|---|
| Strict serializable / external consistency | Serializable **and** linearizable (real-time order respected) | Spanner; CockroachDB (serializable) |
| Serializable | Some serial order, but not necessarily real-time | PostgreSQL SERIALIZABLE (SSI) |
| Linearizable (single object) | Real-time recency per key, no multi-object isolation | etcd, ZooKeeper reads w/ sync, single-key quorum |
| Snapshot isolation | Consistent snapshot; allows write skew | Oracle, PostgreSQL REPEATABLE READ, MySQL RR |
| Read committed | No dirty reads; non-repeatable reads allowed | PostgreSQL default, Oracle default |
| Causal consistency | Causally-related ops seen in order; concurrent ops may reorder | MongoDB causal sessions, Cosmos DB |
| Read-your-writes / monotonic reads | Session guarantees only | Many tunable stores at QUORUM-ish |
| Eventual consistency | Converges eventually; stale reads allowed | Cassandra/DynamoDB at low R/W, AP mode |

Most real architectures use *several* of these simultaneously — ACID for the orders table, eventual for the recommendation cache.

---

## 4. The complete toolkit

This section enumerates the concrete knobs, APIs, and commands across the JVM ecosystem and the major engines. Defaults flagged; version/vendor specifics called out.

### 4.1 SQL / JDBC transaction control (Java-centric)

| API / statement | Purpose | Key params / values | Default |
|---|---|---|---|
| `SET TRANSACTION ISOLATION LEVEL ...` (SQL) | Set isolation for a transaction | `READ UNCOMMITTED`, `READ COMMITTED`, `REPEATABLE READ`, `SERIALIZABLE` | Engine-specific (PG/Oracle: READ COMMITTED; MySQL InnoDB: REPEATABLE READ) |
| `Connection.setAutoCommit(boolean)` (JDBC) | Turn implicit per-statement commit on/off | `false` to begin an explicit transaction | `true` (autocommit on) |
| `Connection.setTransactionIsolation(int)` | Set isolation in Java | `TRANSACTION_READ_UNCOMMITTED/READ_COMMITTED/REPEATABLE_READ/SERIALIZABLE` | Driver/DB default |
| `Connection.commit()` / `rollback()` | End a transaction | — | — |
| `Connection.setSavepoint()` / `rollback(Savepoint)` | Partial rollback within a transaction | named savepoints | — |
| `SELECT ... FOR UPDATE` | Pessimistic row lock (read intending to write) | `FOR UPDATE`, `FOR SHARE`, `NOWAIT`, `SKIP LOCKED` | — |
| `@Transactional` (Spring) | Declarative transaction boundary | `isolation=`, `propagation=`, `readOnly=`, `timeout=`, `rollbackFor=` | propagation REQUIRED, isolation DEFAULT |
| JPA optimistic lock (`@Version`) | Detect lost updates without DB locks | a version column; throws `OptimisticLockException` | — |
| `LockModeType` (JPA) | Per-query locking | `OPTIMISTIC`, `PESSIMISTIC_READ`, `PESSIMISTIC_WRITE`, `PESSIMISTIC_FORCE_INCREMENT` | none |

Spring `Propagation` values worth knowing: `REQUIRED` (join or start), `REQUIRES_NEW` (suspend outer, start fresh), `NESTED` (savepoint-based subtransaction), `SUPPORTS`, `MANDATORY`, `NEVER`, `NOT_SUPPORTED`.

### 4.2 PostgreSQL durability & WAL config

| Setting | Purpose | Notable values | Default |
|---|---|---|---|
| `synchronous_commit` | Whether COMMIT waits for WAL fsync (and/or replicas) | `on`, `off`, `local`, `remote_write`, `remote_apply` | `on` |
| `fsync` | Master switch to issue fsync at all | `on` / `off` (off = **no crash durability**, never in prod) | `on` |
| `wal_level` | How much WAL detail (for replication) | `minimal`, `replica`, `logical` | `replica` |
| `commit_delay` / `commit_siblings` | Group-commit tuning | microseconds / count | `0` / `5` |
| `wal_sync_method` | Which sync syscall | `fdatasync`, `fsync`, `open_datasync`, etc. | platform-specific |
| `full_page_writes` | Torn-page protection | `on`/`off` | `on` |
| `checkpoint_timeout` / `max_wal_size` | Checkpoint frequency | time / size | `5min` / `1GB` |
| `synchronous_standby_names` | Which replicas must ack for synchronous replication | list / `ANY n (...)` | empty |

`synchronous_commit=off` is a powerful, *safe-for-isolation* knob: it keeps atomicity/isolation but allows losing the *last few hundred ms* of committed transactions on crash (the data is still consistent, just slightly behind). Great for bulk loads and non-critical writes.

### 4.3 MySQL/InnoDB durability config

| Setting | Purpose | Values | Default |
|---|---|---|---|
| `innodb_flush_log_at_trx_commit` | When redo log is flushed/fsync'd | `1` (fsync every commit — durable), `2` (write to OS each commit, fsync ~1/s), `0` (flush ~1/s) | `1` |
| `sync_binlog` | fsync the binary log every N commits | `1` (every commit — safest), `0`/`N` | `1` |
| `innodb_doublewrite` | Torn-page protection | `ON`/`OFF` | `ON` |
| `transaction_isolation` | Isolation level | as SQL standard | `REPEATABLE-READ` |

The pair `innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` = full ACID + replication-safe (default, slowest). Setting trx_commit to `2` trades crash durability of the last ~1s for big throughput — common, deliberate.

### 4.4 Cassandra (BASE) consistency & repair toolkit

| Knob / command | Purpose | Values / params | Default |
|---|---|---|---|
| Consistency level (per query) | Choose R/W quorum strength | `ONE`, `TWO`, `QUORUM`, `LOCAL_QUORUM`, `EACH_QUORUM`, `ALL`, `LOCAL_ONE`, `ANY` | `LOCAL_ONE` (driver-dependent) |
| Replication factor (per keyspace) | N copies | integer per datacenter | set at keyspace creation |
| `nodetool repair` | Anti-entropy reconciliation | full/incremental, `-pr` | manual/scheduled |
| Lightweight transactions (LWT) | Compare-and-set via Paxos | `IF NOT EXISTS`, `IF col=val` | off (opt-in, slow) |
| `read_repair_chance` / read repair | Background convergence on reads | probability | tunable |
| Hinted handoff | Deferred delivery to down nodes | `max_hint_window` | on |

Cassandra is normally BASE, but **LWT** gives per-row *linearizable compare-and-set* via Paxos — an ACID-like island for the rare "must not double-allocate this username" case, at ~4× the latency.

### 4.5 DynamoDB (tunable) toolkit

| Feature | Purpose | Params |
|---|---|---|
| `ConsistentRead` | Strong vs eventual read | `true` (strong, quorum, ~2× cost) / `false` (eventual, default) |
| `TransactWriteItems` / `TransactGetItems` | ACID transactions across up to 100 items | all-or-nothing, serializable, single region |
| Conditional writes | Optimistic concurrency / CAS | `ConditionExpression`, `ReturnValuesOnConditionCheckFailure` |
| Global Tables | Multi-region replication | **eventually consistent** across regions (LWW) |

DynamoDB transactions are real ACID within a region; cross-region (global tables) is eventual with last-writer-wins.

### 4.6 MongoDB knobs

| Knob | Purpose | Values | Default |
|---|---|---|---|
| Write concern `w` | How many nodes ack a write | `1`, `majority`, integer, `0` (fire-and-forget) | `majority` (modern) |
| `j` (journal) | Wait for on-disk journal (WAL) fsync | `true`/`false` | per config |
| Read concern | Recency/consistency of reads | `local`, `available`, `majority`, `linearizable`, `snapshot` | `local` |
| Read preference | Which replica to read | `primary`, `secondary`, `nearest`, ... | `primary` |
| Multi-doc transactions | ACID across docs/collections | `session.startTransaction()` | opt-in |

MongoDB spans the dial: `w:majority` + `readConcern:majority` + multi-doc transactions ≈ ACID-ish; `w:0`/`readConcern:available` ≈ BASE.

### 4.7 OS / hardware durability primitives

| Primitive | Purpose | Notes |
|---|---|---|
| `fsync(fd)` | Force file data + metadata to stable storage | The durability linchpin; can be lied to by caches/firmware |
| `fdatasync(fd)` | Like fsync but may skip non-essential metadata | Slightly faster; DBs often prefer it |
| `O_DIRECT` | Bypass OS page cache | DBs may use to control caching themselves |
| Battery-backed/flash-backed write cache (BBU/FBWC) | Make controller cache effectively non-volatile | Lets fsync return fast yet safely |
| Write barriers / FUA | Ordering/force-unit-access at block layer | Filesystem must honor for durability |

---

## 5. Code examples by use case

Idiomatic, copy-adaptable examples across genuinely different scenarios. Java-first where relevant.

### 5.1 Money transfer — ACID transaction with serializable isolation + retry (JDBC)

The classic ACID use case. Note the **retry loop** — at SERIALIZABLE, the DB may abort your transaction with a serialization failure, and the application *must* retry. This is the single most-forgotten production detail.

```java
// Transfer `amount` from `fromId` to `toId`, fully ACID, retrying on serialization failures.
public void transfer(DataSource ds, String fromId, String toId, BigDecimal amount) throws SQLException {
    final int MAX_RETRIES = 5;
    for (int attempt = 1; ; attempt++) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);                                  // begin explicit transaction
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); // strongest isolation
            try {
                // Read+check+write are all inside one atomic, isolated transaction.
                BigDecimal bal = currentBalance(c, fromId);
                if (bal.compareTo(amount) < 0)
                    throw new IllegalStateException("Insufficient funds"); // app-level integrity (the ACID "C")
                debit(c, fromId, amount);
                credit(c, toId, amount);
                c.commit();                                          // durable only after WAL fsync returns
                return;                                              // success
            } catch (SQLException e) {
                c.rollback();                                        // atomicity: undo partial work
                // 40001 = serialization_failure; 40P01 = deadlock_detected (PostgreSQL SQLSTATEs)
                if (isRetryable(e) && attempt < MAX_RETRIES) {
                    sleepWithJitter(attempt);                        // backoff to reduce contention
                    continue;                                        // retry the WHOLE transaction
                }
                throw e;
            }
        }
    }
}

private boolean isRetryable(SQLException e) {
    String s = e.getSQLState();
    return "40001".equals(s) || "40P01".equals(s);
}
```

Why this is correct: the read-check-write is inside one transaction so no one can change the balance between check and debit (no lost update / write skew); SERIALIZABLE + retry guarantees the outcome equals some serial order; COMMIT's fsync gives durability; ROLLBACK gives atomicity.

### 5.2 Inventory decrement without over-selling — pessimistic vs optimistic

**Pessimistic (`SELECT ... FOR UPDATE`)** — lock the row, guaranteeing no concurrent decrement:

```sql
BEGIN;
SELECT stock FROM products WHERE id = 42 FOR UPDATE;  -- exclusive row lock; others block here
-- application checks stock >= qty
UPDATE products SET stock = stock - 1 WHERE id = 42;
COMMIT;                                                -- lock released, durable
```

**Optimistic (JPA `@Version`)** — no lock; detect conflict at commit and retry:

```java
@Entity
class Product {
    @Id Long id;
    int stock;
    @Version long version;   // Hibernate auto-increments; UPDATE ... WHERE id=? AND version=?
}

// In service, with retry on OptimisticLockException
@Retryable(value = OptimisticLockException.class, maxAttempts = 5)
@Transactional
public void buyOne(Long id) {
    Product p = em.find(Product.class, id);
    if (p.stock <= 0) throw new OutOfStockException();
    p.stock--;                  // on flush: UPDATE ... SET stock=?, version=version+1 WHERE id=? AND version=?
}                               // if version mismatched (someone else committed), 0 rows → OptimisticLockException
```

Pick pessimistic for high-contention single hot rows (one viral SKU); optimistic for low-contention spread-out rows (less locking overhead).

### 5.3 The dangerous pattern that needs SERIALIZABLE — write skew

Two transactions each ensure "at least one doctor stays on call," both succeed at REPEATABLE READ/SI, both go off → zero doctors. SI does **not** catch this; only SERIALIZABLE does.

```sql
-- Run each in its own concurrent transaction at READ COMMITTED or REPEATABLE READ → BUG
BEGIN;
SELECT count(*) FROM doctors WHERE on_call = true AND shift = 'night'; -- both see 2
-- both decide "fine, 2 > 1, I can leave"
UPDATE doctors SET on_call = false WHERE name = 'Alice' AND shift = 'night';
COMMIT;  -- and concurrently the same for 'Bob' → now zero on call, invariant violated
```

Fix: `SET TRANSACTION ISOLATION LEVEL SERIALIZABLE` (PostgreSQL SSI aborts one with 40001; retry), or take explicit locks covering the read set (`SELECT ... FOR UPDATE` / a predicate lock).

### 5.4 BASE write/read with tunable consistency — Cassandra (Java driver)

```java
// Eventual consistency for a high-volume feed write: fast, available, possibly stale reads elsewhere.
session.execute(
    SimpleStatement.builder("INSERT INTO feed (user_id, post_id, body) VALUES (?,?,?)")
        .addPositionalValues(userId, postId, body)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_ONE)  // W=1: ack from one local replica → BASE
        .build());

// Read at QUORUM when this particular read must be fresh (R+W>N for that op):
ResultSet rs = session.execute(
    SimpleStatement.builder("SELECT body FROM feed WHERE user_id=? AND post_id=?")
        .addPositionalValues(userId, postId)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)  // stronger read when needed
        .build());
```

You're literally turning the ACID↔BASE dial *per request* with `ConsistencyLevel`.

### 5.5 Cassandra LWT — an ACID island (linearizable compare-and-set) inside a BASE store

```sql
-- Ensure a username is claimed at most once, despite concurrency, in an AP store.
INSERT INTO users (username, user_id) VALUES ('pavan', 123)
IF NOT EXISTS;   -- Paxos-backed compare-and-set: linearizable, ~4x latency of a normal write
-- Returns [applied]=true only for the winner; everyone else sees [applied]=false
```

Use LWT *sparingly* — it's slow and partition-sensitive — but it's the right tool for uniqueness/no-double-spend in Cassandra.

### 5.6 DynamoDB ACID transaction (within a region)

```java
dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
    .transactItems(
        // Debit, with a condition that prevents going negative (optimistic + atomic)
        TransactWriteItem.builder().update(Update.builder()
            .tableName("accounts").key(key("A"))
            .updateExpression("SET balance = balance - :amt")
            .conditionExpression("balance >= :amt")
            .expressionAttributeValues(Map.of(":amt", n("100"))).build()).build(),
        TransactWriteItem.builder().update(Update.builder()
            .tableName("accounts").key(key("B"))
            .updateExpression("SET balance = balance + :amt")
            .expressionAttributeValues(Map.of(":amt", n("100"))).build()).build())
    .build());
// All-or-nothing across both items; serializable; a TransactionCanceledException means a condition failed → retry/handle
```

### 5.7 Saga — keeping a *business* transaction atomic across services when 2PC isn't available

In microservices you usually *can't* run one ACID transaction across services. A **Saga** is a sequence of local ACID transactions, each with a **compensating** transaction to undo it if a later step fails — giving *eventual* business-level atomicity (a BASE-flavored pattern).

```java
// Orchestrated saga: order -> payment -> shipping, with compensations.
try {
    var orderId   = orderSvc.create(cmd);         // local ACID tx in order DB
    var paymentId = paymentSvc.charge(orderId);   // local ACID tx in payment DB
    shippingSvc.schedule(orderId);                // local ACID tx in shipping DB
} catch (PaymentFailed e) {
    orderSvc.cancel(orderId);                      // compensate step 1
    throw e;
} catch (ShippingFailed e) {
    paymentSvc.refund(paymentId);                 // compensate step 2
    orderSvc.cancel(orderId);                      // compensate step 1
    throw e;
}
```

Sagas trade strict isolation (intermediate states *are* visible) for availability and service autonomy — pair with the **outbox pattern** (write the event in the same local transaction as the data, publish from the outbox) to avoid the dual-write problem.

### 5.8 Tuning durability for a bulk load (PostgreSQL)

```sql
-- For a restartable bulk import where losing the last few ms on crash is fine:
SET synchronous_commit = off;   -- keep atomicity/isolation, drop per-commit fsync wait → much faster
-- ... do millions of INSERTs ...
RESET synchronous_commit;       -- back to durable
CHECKPOINT;                     -- force everything to disk at the end
```

This is the *safe* way to go fast: you keep consistency and isolation; you only relax the durability *window*. Never `fsync=off` in production.

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **fsync is the bottleneck** for write-heavy ACID workloads. Use group commit, batch transactions, fast storage (NVMe), and a battery/flash-backed controller cache so fsync returns quickly yet safely. On cloud, prefer disks with high IOPS and consider `synchronous_commit=off`/`innodb_flush_log_at_trx_commit=2` for non-critical writes.
- **Keep transactions short.** Long transactions hold locks and pin MVCC snapshots, causing lock waits and **bloat** (old versions can't be vacuumed while a long-running snapshot needs them). Never do network calls or user-think-time inside a transaction.
- **Right-size isolation.** SERIALIZABLE adds aborts/retries and overhead; use it only where correctness demands. Many apps run READ COMMITTED and add `SELECT ... FOR UPDATE` or `@Version` precisely where needed.
- **In BASE stores**, lower R/W = lower latency + higher availability; `QUORUM`/`ALL` and LWT cost dearly. Use `LOCAL_QUORUM` (within one datacenter) instead of cross-DC quorums to avoid WAN latency.

### 6.2 Correctness & concurrency
- **Always retry on serialization failures (40001) and deadlocks (40P01).** Code that doesn't is silently broken under load.
- **Beware read-modify-write outside a transaction** — that's the lost-update bug. Use `UPDATE ... SET x = x + 1` (atomic in DB), `FOR UPDATE`, or `@Version`.
- **Know your engine's actual isolation semantics** (the standard lies; see §7). MySQL RR ≠ PostgreSQL RR ≠ Oracle "serializable" (which is really SI).
- **In BASE**, design for **idempotency** (retries/replays must be safe), use **CRDTs** or **vector clocks** for mergeable data, and pick conflict resolution deliberately — LWW silently drops data under clock skew.

### 6.3 Memory
- MVCC engines accumulate dead tuples → bloat → memory/disk pressure and slow scans. Tune **autovacuum** aggressively on high-churn tables; monitor dead-tuple ratios.
- Large WAL buffers and buffer pools improve throughput but must fit RAM; right-size `shared_buffers`/`innodb_buffer_pool_size` (commonly 25–70% of RAM).

### 6.4 Security
- Transactions don't provide isolation *between tenants*; that's authorization. Don't conflate.
- Audit logs benefit from durable WAL; ensure WAL and backups are encrypted at rest. fsync'd-but-unencrypted WAL is a leak surface.

### 6.5 Cost
- Strong consistency costs money: DynamoDB strongly-consistent reads cost ~2× eventually-consistent; transactions cost ~2× normal writes. Cross-region/quorum coordination costs latency *and* compute. Spend it only where business correctness requires.

### 6.6 Observability
- Monitor: commit latency, fsync latency, WAL/redo generation rate, checkpoint frequency, **replication lag** (how far behind replicas are — directly bounds staleness), lock waits, deadlock count, serialization-failure/abort rate, autovacuum/compaction progress, dead-tuple counts.
- In BASE: monitor **hint queue size**, **repair backlog**, replica divergence, and read-repair rates — these reveal how "eventual" your eventual consistency currently is.

### 6.7 Testing
- **Jepsen** is the gold-standard tool: it hammers a distributed DB with concurrent ops under injected network partitions and checks the history against a consistency model (linearizability, etc.). Many vendors' real consistency bugs were found by Jepsen. If a vendor "passed Jepsen," that's meaningful; if they avoid it, be wary.
- Unit-test retry logic by injecting serialization failures; chaos-test partitions; property-test CRDT merges for commutativity/idempotency.

### 6.8 Production hardening
- Verify your storage *actually honors fsync* (cheap SSDs/firmware have lied historically — see §9). Test with power-pull experiments or tools like `diskchecker`.
- Use synchronous replication for the data you cannot lose (`synchronous_commit=remote_apply`, `w:majority j:true`), accepting the latency. Have a documented RPO (Recovery Point Objective — how much data you can afford to lose) and RTO (Recovery Time Objective).
- Set transaction `timeout` and statement timeouts to bound runaway transactions and protect against XID-wraparound/bloat.

### 6.9 Anti-patterns
- **Dual writes** (write to DB and Kafka separately) — not atomic; use the outbox pattern.
- **Distributed 2PC across microservice databases** as a default — fragile, blocking, poor availability; prefer sagas.
- **Treating eventual consistency as strong** — "I just wrote it, why isn't it there?" Provide read-your-writes via sticky sessions/causal tokens where users expect it.
- **LWW for data that must merge** (carts, inventory) — silently loses writes; use CRDTs.
- **fsync=off / w:0 in production** to "go fast" — you've traded durability you'll regret.
- **Long transactions / open transactions during user think-time** — locks, bloat, timeouts.

---

## 7. Advanced topics & deep internals

### 7.1 Engine-specific isolation reality (the standard is a poor guide)
- **PostgreSQL:** READ COMMITTED (default) and REPEATABLE READ are MVCC snapshot-based; PG's REPEATABLE READ **prevents phantoms** (stronger than the standard) and is true snapshot isolation. SERIALIZABLE uses **SSI** (predicate-dependency tracking) and can abort with 40001. There is *no* READ UNCOMMITTED (it behaves as READ COMMITTED) because MVCC never exposes dirty data.
- **MySQL/InnoDB:** default REPEATABLE READ; uses **consistent reads** (snapshot) plus **next-key locking** to block phantoms for locking reads; but plain SELECT vs `SELECT ... FOR UPDATE` can see different snapshots — a subtle gotcha. SERIALIZABLE makes plain reads take shared locks.
- **Oracle:** offers READ COMMITTED (default) and "SERIALIZABLE" which is actually **snapshot isolation** (allows write skew). No true serializability historically.
- **SQL Server:** locking by default; offers optimistic SNAPSHOT and READ_COMMITTED_SNAPSHOT (MVCC) when enabled.

Takeaway: "isolation level X" is not portable. Know each engine.

### 7.2 Durability is not binary — the fsync spectrum
- `innodb_flush_log_at_trx_commit=1`: fsync every commit → lose nothing on crash.
- `=2`: write to OS cache each commit, fsync ~once/sec → survive *process* crash, lose ≤~1s on *OS/power* crash.
- `=0`: even the write is ~1/sec → lose ≤~1s on process crash too.
- PostgreSQL `synchronous_commit=off`: commit returns before WAL fsync; a background flush happens within `wal_writer_delay` (default 200ms) → lose ≤~3×that on crash, but the DB stays **consistent** (no torn/atomic violations), just behind. This is the key insight: you can relax the durability *window* without sacrificing A/C/I.

### 7.3 Replication and durability
- **Asynchronous replication:** primary acks commit before replicas have it → on primary failure, recent committed transactions can be *lost* (data loss window = replication lag). Low latency, weaker durability.
- **Synchronous replication:** primary waits for ≥1 (or quorum of) replicas to persist before ack → no data loss on single-node failure, higher latency. PostgreSQL `synchronous_standby_names` + `synchronous_commit=remote_write`/`remote_apply`; MySQL semi-sync / Group Replication; MongoDB `w:majority`.
- **Quorum durability (NewSQL):** Raft/Paxos commit = persisted on a majority → tolerates minority failure with zero loss; this is *stronger* than single-node fsync because it survives whole-machine death. The price: cross-node round-trip latency per commit.

### 7.4 The "C" you didn't know you needed: external consistency / linearizability across keys
Single-key linearizability (etcd, quorum reads) is easy; making *multi-key transactions* linearizable across shards is hard. Spanner's **TrueTime + commit-wait** and CockroachDB's **uncertainty intervals + transaction restarts** exist precisely to make cross-shard transactions appear to happen at a single global instant. The lesser-known cost: CockroachDB may **restart** a transaction transparently when a read falls inside another transaction's uncertainty window (clock skew zone) — visible as retries/latency spikes; you reduce it with tight NTP/PTP clock sync (lower `--max-offset`).

### 7.5 Isolation anomalies beyond the textbook
- **Write skew** (SI allows) and **read skew** (reading two related rows across an intervening commit) — handled by SERIALIZABLE/SSI.
- **Phantom via predicate** — needs predicate/range locks (SSI tracks read predicates; InnoDB uses next-key locks).
- **The "lost update" at READ COMMITTED** — extremely common in naive ORMs; `@Version` or atomic SQL fixes it.
- **G2/anti-dependency cycles** — the formal cycles SSI detects; useful to know the term for interviews.

### 7.6 Clocks: HLC, TrueTime, NTP and why they matter
- **NTP** (Network Time Protocol) syncs clocks to ~1–10ms typically — *not* tight enough to order events safely without extra logic, hence uncertainty intervals.
- **PTP** (Precision Time Protocol) reaches sub-microsecond on good hardware.
- **HLC** (Hybrid Logical Clock) = physical time + logical counter; gives a timestamp that respects causality even with skew, used by Cockroach/Yugabyte/Mongo.
- **TrueTime** = bounded-uncertainty time from GPS+atomic clocks; Spanner *waits out* the uncertainty to make timestamps globally meaningful. This is the deepest "secret" of distributed ACID: you can't have global order for free; you either buy precise clocks (Spanner) or pay with restarts/uncertainty waits (Cockroach).

### 7.7 LSM vs B-tree and how it touches durability/MVCC
- **B-tree** engines (PostgreSQL, InnoDB) update pages in place (with WAL); good for reads, write amplification from page updates.
- **LSM-tree** engines (Cassandra, RocksDB, used inside CockroachDB/TiDB) append to an in-memory **memtable** + WAL (the **commit log**), flush to immutable **SSTables**, and **compact** in background. Writes are sequential (fast, durable via the commit log fsync); reads may touch multiple SSTables (use **bloom filters** to skip files). MVCC versions and tombstones (deletion markers) live across SSTables until compaction. Knowing this explains Cassandra's write speed and its "tombstone" and "compaction backlog" operational pain.

---

## 8. Tradeoffs & decision frameworks

### 8.1 ACID vs BASE head-to-head

| Dimension | ACID | BASE |
|---|---|---|
| Consistency | Strong (latest, isolated) | Eventual (may be stale) |
| Availability under partition | May refuse/block (CP-leaning) | Stays up (AP) |
| Latency | Higher (coordination, fsync) | Lower (local, async) |
| Horizontal scale | Historically hard (NewSQL fixes it, at latency cost) | Native, elastic |
| Conflict handling | Prevented (locks/MVCC/abort) | Reconciled later (LWW/CRDT/vector) |
| Typical data | Money, inventory, identity, bookings | Feeds, counters, catalogs, sessions, telemetry |
| Failure cost of wrong read | High (real-world incident) | Low (slightly stale, self-heals) |
| Developer model | Transactions, rollback | Idempotency, convergence, compensations |

### 8.2 When eventual consistency is acceptable vs not

**Acceptable (use BASE / eventual):**
- Read-mostly, staleness-tolerant: social feeds, timelines, "likes"/view counts, product catalogs, recommendation caches, search indexes (lag by seconds is fine), analytics/telemetry, session stores.
- Anything where convergence + idempotency + occasional retry covers correctness, and availability/latency/scale dominate.

**Unacceptable (require ACID / strong):**
- **Money:** balances, ledgers, payments, double-spend prevention. A stale or lost write here is fraud/loss.
- **Inventory / seat / ticket allocation:** overselling the last unit is a real-world failure.
- **Identity/auth state, permissions, unique constraints** (username, idempotency keys).
- **Anything with an invariant that must never be transiently violated and observed** (write-skew-prone rules).

Rule of thumb: *if a human or downstream system makes an irreversible decision based on the read, you probably need strong consistency for that read.* "Money vs feeds" is the canonical dividing line.

### 8.3 Decision rules
- **Use ACID single-node (PostgreSQL/InnoDB) when** your dataset fits one (well-replicated) primary, you need rich transactions, and latency from a local fsync is acceptable. Default choice for OLTP until proven insufficient.
- **Use NewSQL (Spanner/CockroachDB/Yugabyte/TiDB) when** you need ACID *and* horizontal scale / multi-region / high availability, and can tolerate a few ms of cross-node commit latency and a more complex operational story.
- **Use BASE (Cassandra/DynamoDB/Riak) when** you need massive write throughput, multi-region availability, and your data tolerates staleness — and you'll engineer idempotency + convergence.
- **Mix per data class**: ACID store for the system of record, BASE store/cache for derived/read-heavy views, with async pipelines (CDC/outbox) between them. This is the most common real architecture.
- **Tune per operation** within a tunable store: strong reads where it matters, eventual elsewhere.

### 8.4 Alternatives & adjacent choices
- **Single-leader async replication** (Postgres + read replicas): ACID on primary, eventual on replicas — a pragmatic middle ground; route critical reads to primary.
- **Event sourcing / CQRS**: ACID append of events, eventually-consistent read models.
- **Coordination services** (ZooKeeper/etcd): tiny strongly-consistent stores for the *control plane* even when the data plane is BASE.

---

## 9. Failure modes & debugging

### 9.1 Lost data despite "committed" — the fsync lie
**Symptom:** after power loss, recently-committed transactions are gone though the app got COMMIT success.
**Causes:** `fsync=off`/`innodb_flush_log_at_trx_commit=2`/`w:0`/async replication losing the lag window; **or** storage/firmware that ignores fsync (consumer SSDs, virtualized disks with volatile caches, misconfigured RAID controller without BBU).
**Diagnose:** check durability settings; run a power-pull test or `diskchecker.pl`/`fio` with sync; verify controller cache is battery-backed and write-through unless protected; check replication mode and lag at the time of failure.
**Fix:** set durable config for critical data; use protected hardware; use synchronous/quorum replication for zero-RPO needs.

### 9.2 Deadlocks and serialization failures storm
**Symptom:** spikes of `deadlock detected` (40P01) / `could not serialize access` (40001); rising error rate under load.
**Diagnose:** PostgreSQL: `log_lock_waits=on`, inspect `pg_locks`, `pg_stat_activity` (find blocking PIDs), deadlock messages name the two statements. MySQL: `SHOW ENGINE INNODB STATUS` "LATEST DETECTED DEADLOCK".
**Fix:** ensure consistent lock ordering across transactions; shorten transactions; add the missing retry loop; lower isolation where safe; reduce hot-row contention (sharding counters, queueing).

### 9.3 Lock waits / blocked transactions / "idle in transaction"
**Symptom:** queries hang; throughput collapses; one long transaction blocks many.
**Diagnose:** `pg_stat_activity` rows in state `idle in transaction` holding locks; `pg_blocking_pids()`. MySQL `information_schema.innodb_trx`/`data_locks`.
**Fix:** set `idle_in_transaction_session_timeout`; never hold transactions over network/user waits; add connection-pool transaction timeouts.

### 9.4 Table bloat / VACUUM falling behind / XID wraparound
**Symptom:** disk grows, scans slow, autovacuum can't keep up; worst case PostgreSQL warns about wraparound and can stop accepting writes.
**Diagnose:** monitor dead-tuple counts (`pg_stat_user_tables`), `age(datfrozenxid)`, autovacuum logs; long-running transactions pinning snapshots.
**Fix:** tune autovacuum (`autovacuum_vacuum_scale_factor` lower on hot tables), kill long transactions, schedule manual `VACUUM`, avoid keeping ancient open snapshots.

### 9.5 Eventual-consistency surprises
**Symptom:** "I saved it but the next read shows the old value"; resurrected deletes; lost increments.
**Diagnose:** check R/W vs N (is R+W>N?), replication/hint/repair backlog, clock skew (LWW), whether reads hit a lagging replica.
**Fix:** raise read consistency for that op (`QUORUM`/strong read), route read-your-writes to primary or use causal tokens, use CRDTs for counters/sets, fix NTP, schedule `nodetool repair`.

### 9.6 NewSQL clock-skew restarts / hot-range contention
**Symptom (CockroachDB):** elevated transaction restarts/retries, latency spikes; "ReadWithinUncertaintyInterval".
**Diagnose:** check `--max-offset` vs measured clock offset, NTP/PTP health, hot ranges in the UI.
**Fix:** tighten clock sync, split hot ranges, use explicit retry handling, design keys to spread load (avoid monotonically-increasing primary keys creating a single hot range).

### 9.7 Real-world incidents (illustrative, well-documented patterns)
- **Amazon Dynamo cart resurrection:** chosen BASE behavior — adds beat deletes during partitions; deliberate tradeoff favoring availability/sales over strict correctness.
- **Jepsen findings:** numerous distributed DBs (early MongoDB, Cassandra LWT edge cases, various "SERIALIZABLE" claims) were shown under Jepsen to violate their advertised consistency under partition — a recurring lesson that *claimed* guarantees ≠ *tested* guarantees.
- **GitLab 2017 outage:** a replication/backup gap (durability/recovery process failure) caused data loss — a reminder that durability is an end-to-end property (config + replication + tested backups), not just `fsync`.
- **The classic "we set innodb_flush_log_at_trx_commit=2 for speed" postmortem:** great throughput, then a datacenter power event loses the last second of orders — a deliberate-but-forgotten durability tradeoff biting in production.

---

## 10. Interview drill

**Q1. Define ACID precisely.**
*Model:* Atomicity = all-or-nothing transaction (WAL + undo/MVCC). Consistency = transactions preserve declared integrity constraints (PK/FK/CHECK), partly the app's job. Isolation = concurrent transactions appear serial (via MVCC and/or locking); weaker levels permit named anomalies. Durability = once committed, survives crashes (WAL fsync, replication).
- *Probe: Which letter is least "database-provided"?* Consistency — the DB only enforces rules you declare; many invariants are app logic.
- *Probe: How are A and D both delivered?* The WAL: redo replays committed work (durability), undo reverses in-flight work on recovery (atomicity).
- *Probe: What's the difference between ACID's C and CAP's C?* ACID C = integrity constraints; CAP C = linearizability/recency. Unrelated.

**Q2. What is BASE and why does it exist?**
*Model:* Basically Available, Soft state, Eventual consistency — the philosophy of AP systems that favor availability/latency/scale over strong consistency, converging replicas over time via hinted handoff/read repair/anti-entropy. It exists because, per CAP, you must drop C or A during partitions, and many workloads prefer staying up.
- *Probe: What makes state "soft"?* It can change without new input as background convergence runs.
- *Probe: Give a BASE-appropriate and a BASE-inappropriate use case.* Feed/counter = fine; bank balance = not.

**Q3. Explain MVCC and how it differs from locking.**
*Model:* MVCC keeps multiple row versions; readers see a snapshot and don't block writers, writers don't block readers; writer conflicts abort. Locking blocks conflicting access (2PL) and risks deadlocks. MVCC gives better read concurrency; both can implement serializability (SSI vs S2PL).
- *Probe: What anomaly does plain snapshot isolation still allow?* Write skew.
- *Probe: How does PostgreSQL get true serializability with MVCC?* SSI — tracks read/write dependencies, aborts dangerous cycles.
- *Probe: What maintenance does MVCC require?* VACUUM/purge to remove dead versions and prevent bloat/XID wraparound.

**Q4. Walk me through what happens at COMMIT.**
*Model:* Changes are made in the buffer pool, WAL records (redo + undo info) are generated, a commit record is appended, the WAL is fsync'd to stable storage, *then* the client is acked; data pages flush lazily at checkpoints; recovery replays WAL (redo) and undoes in-flight transactions.
- *Probe: Why log before data?* Page writes aren't atomic/ordered; sequential WAL + fsync is fast and crash-safe; recovery rebuilds from the log.
- *Probe: What's group commit?* Batching many transactions' WAL flush into one fsync to amortize cost.

**Q5 (senior signal). You need to scale an OLTP system 50× and go multi-region. Walk me through ACID vs BASE vs NewSQL and justify a choice.**
*Model:* Identify per-data-class needs. System-of-record (money/orders) needs ACID → choose NewSQL (Cockroach/Spanner) for ACID + scale + multi-region, accepting a few ms commit latency and operational complexity; or single-region ACID + async cross-region read replicas if writes are regional. Derived/read-heavy data (feeds, catalog, recommendations) → BASE store/cache fed by CDC/outbox, eventual consistency, `LOCAL_QUORUM`/eventual reads. Justify with PACELC: you pay latency for consistency on the critical path only. Mention RPO/RTO, idempotency, and read-your-writes handling.
- *Probe: Why not 2PC across microservices?* Blocking, poor availability, fragile; prefer sagas + outbox for business-level atomicity.
- *Probe: What's the latency cost of NewSQL strong consistency?* Cross-node quorum round-trips per commit; Spanner adds commit-wait (~ε ms) for external consistency.

**Q6 (senior signal). When is eventual consistency acceptable, and how do you bound it?**
*Model:* Acceptable when staleness causes no irreversible wrong decision (feeds, counters, catalogs) and availability/latency dominate. Bound it by monitoring/limiting replication lag, raising R/W for critical ops (R+W>N), providing read-your-writes via primary routing or causal tokens, using CRDTs for mergeable data, and scheduling repair. Unacceptable for money/inventory/identity invariants.
- *Probe: How would you give a user read-your-writes on an eventually-consistent backend?* Sticky-route their reads to the node/region that took the write, or pass a causal/consistency token, or read at quorum/primary for their own data.
- *Probe: What breaks LWW?* Clock skew and true concurrent conflicting writes silently drop data; use vector clocks/CRDTs.

**Q7. Explain CAP and PACELC and place Spanner, Cassandra, PostgreSQL.**
*Model:* CAP: during a partition pick C or A. PACELC: also, Else (no partition) pick Latency or Consistency. Spanner = PC/EC (consistent always, pays latency). Cassandra = PA/EL (available + low latency, eventual). Single-node PostgreSQL isn't distributed, but with quorum sync replication it's CP-leaning.
- *Probe: Common CAP misconception?* "Pick 2 of 3 always" — wrong; the choice only forces during a partition.
- *Probe: Which C is CAP's?* Linearizability (recency), not integrity constraints.

**Q8. How do databases guarantee durability, and what can undermine it?**
*Model:* WAL + fsync at commit, optionally + synchronous/quorum replication. Undermined by `fsync=off`, OS/controller volatile caches, firmware that lies about fsync, async replication's lag window, and untested backups. Strengthen with protected hardware (BBU/FBWC), quorum replication, defined RPO/RTO, tested restores.
- *Probe: Difference between durable-on-one-node and durable-replicated?* Single-node fsync survives crash but not machine loss; quorum survives minority machine loss.
- *Probe: innodb_flush_log_at_trx_commit values?* 1 = fsync/commit (full); 2 = OS write/commit, fsync ~1/s (lose ≤1s on power loss); 0 = ~1/s for both.

**Q9. What is write skew and why doesn't snapshot isolation prevent it?**
*Model:* Two transactions read an overlapping set, make disjoint writes each valid against their snapshot, and the combination violates an invariant. SI gives each a stale-but-consistent snapshot and they don't write the same row, so no write-write conflict is detected. SERIALIZABLE/SSI detects the read/write dependency and aborts one.
- *Probe: Fix without SERIALIZABLE?* Materialize the conflict by locking the read set (`FOR UPDATE`) or writing a sentinel row both touch.

**Q10. How does NewSQL provide ACID across machines?**
*Model:* MVCC for isolation; Raft/Paxos per shard for replicated, ordered, quorum-durable commits; 2PC for cross-shard atomicity; clock discipline (TrueTime commit-wait / HLC + uncertainty restarts) for global recency/external consistency. Cost: per-transaction cross-node latency.
- *Probe: What is TrueTime and why commit-wait?* Bounded-uncertainty global clock; wait out the uncertainty so a commit timestamp is unambiguously in the past everywhere → external consistency.
- *Probe: How does CockroachDB cope without atomic clocks?* Uncertainty intervals + transaction restarts under tight NTP; serializable by default.

**Q11 (senior signal). Your team set a DB to async replication for latency; later a region failed and you lost 12 seconds of orders. Walk through the tradeoff and what you'd change.**
*Model:* Async replication acks before replicas persist, so the loss window = replication lag (here ~12s) — a durability/RPO decision traded for latency. For orders (money), RPO should be ~0: switch the orders path to synchronous/quorum replication (`synchronous_commit=remote_apply` / `w:majority`) accepting added commit latency, possibly using NewSQL quorum commit; keep async for non-critical data. Add lag monitoring/alerts and document RPO/RTO. The lesson: durability is a per-data-class business decision, not a global switch.
- *Probe: How much latency will sync add?* Roughly one extra cross-node/region round-trip per commit (single-digit ms intra-region, tens of ms cross-region).
- *Probe: Could you keep low latency and zero loss?* Quorum within a single low-latency region/AZ set gives near-zero loss with modest latency; cross-region zero-loss inherently costs WAN latency.

**Q12. Compare optimistic and pessimistic concurrency control; when each?**
*Model:* Pessimistic locks upfront (`FOR UPDATE`, 2PL): good for high contention/hot rows; cost = blocking/deadlocks. Optimistic (versioning/`@Version`, MVCC abort-retry): assume no conflict, detect at commit, retry: good for low contention; cost = wasted work + retries under contention.
- *Probe: Which does MVCC lean toward for readers?* Optimistic — readers never block; conflicts only between writers.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency (integrity), Isolation, Durability; strong transactional guarantees.
- **Anti-entropy** — background process reconciling divergent replicas (e.g., Merkle-tree repair).
- **ARIES** — classic WAL-based recovery algorithm (Analysis, Redo, Undo).
- **Atomicity** — transaction is all-or-nothing.
- **Availability (CAP)** — every request gets a non-error response.
- **BASE** — Basically Available, Soft state, Eventual consistency; loosened guarantees for availability/scale.
- **Buffer pool / page cache** — in-RAM cache of disk pages; modified ones are "dirty."
- **CAP theorem** — under partition, choose Consistency or Availability.
- **Causal consistency** — causally-related operations observed in order.
- **Checkpoint** — flush dirty pages and mark a recovery starting point.
- **clog / commit log** — record of transaction commit/abort outcomes (PostgreSQL); also Cassandra's WAL.
- **Commit wait** — Spanner's delay to wait out clock uncertainty for external consistency.
- **Consensus** — agreeing on an ordered log across nodes (Paxos/Raft).
- **Consistency (ACID)** — preserves declared integrity constraints.
- **Consistency (CAP)** — linearizability/recency.
- **Consistency level** — Cassandra's per-op R/W strength (ONE, QUORUM, ...).
- **Consistent hashing** — key→node mapping that minimizes remap on membership change.
- **CRDT** — Conflict-free Replicated Data Type; merges concurrent updates deterministically.
- **Deadlock** — cyclic lock wait; detector aborts a victim.
- **Dirty read** — reading uncommitted data.
- **Durability** — committed data survives crashes.
- **Eventual consistency** — replicas converge given no new writes.
- **External consistency** — serializable + linearizable (real-time order) across the system (Spanner).
- **fsync()** — syscall forcing buffered writes to stable storage.
- **Group commit** — batching many transactions' WAL flush into one fsync.
- **HLC** — Hybrid Logical Clock (physical time + logical counter).
- **Hinted handoff** — store-and-forward a write for a down replica.
- **Idempotency** — repeating an operation has the same effect as doing it once.
- **Integrity constraint** — PK/FK/CHECK/UNIQUE/NOT NULL rule defining valid state.
- **Isolation** — concurrent transactions appear serial.
- **Isolation level** — READ UNCOMMITTED/COMMITTED, REPEATABLE READ, SERIALIZABLE.
- **Jepsen** — testing framework that injects partitions and checks consistency claims.
- **Linearizability** — single-copy real-time recency guarantee.
- **Lost update** — one write silently overwrites another's based on stale read.
- **LSM-tree** — log-structured merge tree; memtable + WAL + SSTables + compaction.
- **LWT** — Cassandra Lightweight Transaction; Paxos compare-and-set.
- **LWW** — Last-Write-Wins conflict resolution.
- **Merkle tree** — hash tree enabling efficient replica difference detection.
- **MVCC** — Multi-Version Concurrency Control; multiple row versions + snapshots.
- **NewSQL** — distributed SQL with ACID at scale (Spanner, CockroachDB, Yugabyte, TiDB).
- **Non-repeatable read** — same row read twice yields different values.
- **NTP / PTP** — clock-sync protocols (ms / sub-µs).
- **Outbox pattern** — write events in the same local transaction, publish from the outbox (avoids dual writes).
- **PACELC** — if Partition: A or C; Else: Latency or Consistency.
- **Paxos / Raft** — consensus protocols.
- **Partition (network)** — network split isolating node groups.
- **Phantom read** — a re-run query sees newly-inserted matching rows.
- **Quorum** — majority subset of replicas (⌊N/2⌋+1).
- **R / W / N** — read-quorum / write-quorum / replication factor.
- **Read repair** — fix stale replicas during a read.
- **Replication lag** — how far behind a replica is; bounds staleness/RPO.
- **RPO / RTO** — Recovery Point/Time Objective (tolerable data loss / downtime).
- **Saga** — chain of local transactions with compensations for cross-service atomicity.
- **Serializability** — equivalent to some serial execution order.
- **Snapshot isolation (SI)** — each transaction sees a frozen snapshot; allows write skew.
- **Soft state** — state may change without new input (via convergence).
- **SSI** — Serializable Snapshot Isolation (PostgreSQL); SI + dependency tracking.
- **SSTable** — immutable sorted file in LSM engines.
- **Tombstone** — deletion marker in LSM/eventually-consistent stores.
- **TrueTime** — Spanner's bounded-uncertainty global clock (GPS + atomic).
- **Two-phase commit (2PC)** — prepare/commit protocol for multi-participant atomicity.
- **Two-phase locking (2PL)** — acquire-then-release locking yielding serializability.
- **Vacuum / purge / compaction** — remove dead MVCC versions/reclaim space.
- **Vector clock** — causal-history metadata to detect concurrent vs causal updates.
- **WAL** — Write-Ahead Log; log changes before data, basis of atomicity+durability+recovery.
- **Write skew** — disjoint writes from overlapping reads violating an invariant; SI allows it.
- **XID / txid** — transaction identifier; basis of MVCC visibility; risk of wraparound.
- **ZooKeeper / etcd** — strongly-consistent coordination stores (ZAB / Raft).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**ACID** = Atomicity (all-or-nothing, WAL undo) · Consistency (integrity constraints, partly your job) · Isolation (appear serial; MVCC/locks) · Durability (survive crash; WAL fsync + replication).
**BASE** = Basically Available · Soft state · Eventual consistency. Born from CAP (under partition pick A over C).
**Two C's:** ACID-C = constraints; CAP-C = linearizability. Don't conflate.
**Durability path:** modify in buffer pool → WAL records → commit record → **fsync WAL** → ack → lazy page flush → recovery = redo winners + undo losers.
**Group commit:** batch fsyncs → 10–100× write throughput.
**Isolation levels:** RU < RC < RR < SERIALIZABLE; anomalies dirty/non-repeatable/phantom/write-skew. SI allows write skew; SSI/SERIALIZABLE fixes it (retry on 40001!).
**MVCC:** versions + snapshots; readers don't block writers; needs VACUUM; watch XID wraparound.
**Quorum dial:** R+W>N ⇒ fresh reads; R+W≤N ⇒ eventual/fast. Cassandra `LOCAL_QUORUM` vs `ONE`.
**Durability knobs:** PG `synchronous_commit` (on/off/remote_apply); MySQL `innodb_flush_log_at_trx_commit` (1/2/0) + `sync_binlog`. `=2` loses ≤~1s on power loss.
**NewSQL:** MVCC + Raft/Paxos + 2PC + TrueTime/HLC ⇒ ACID at scale, costs ms latency.
**Replication:** async = low latency, data-loss window; sync/quorum = zero-loss, higher latency.
**Decision line:** money/inventory/identity ⇒ ACID; feeds/counters/catalog/cache ⇒ BASE. Mix per data class; tune per op.
**Anti-patterns:** dual writes (use outbox), default 2PC across services (use sagas), LWW for mergeable data (use CRDTs), missing retry loops, fsync=off in prod, long/open transactions.
**Numbers to remember:** quorum = ⌊N/2⌋+1; fsync ~0.1–several ms; NTP ~1–10ms (hence uncertainty); Spanner ε ~few ms; strong DynamoDB read ~2× cost.
**Debug tools:** `pg_stat_activity`, `pg_locks`, `pg_blocking_pids()`, `SHOW ENGINE INNODB STATUS`, `nodetool repair`, Jepsen.

### 12.2 Self-test (no answers — recall actively)

1. Explain, in order, every step from BEGIN to a crash-safe durable COMMIT, naming the structure responsible for atomicity vs durability.
2. A teammate says "we use SERIALIZABLE so we don't need retry logic." What's wrong, and what specifically must the application handle?
3. Your eventually-consistent store keeps "resurrecting" deleted shopping-cart items after partitions. Diagnose the mechanism and give two fixes with their tradeoffs.
4. Given N=5 replicas, choose R and W for (a) lowest-latency available writes and (b) guaranteed fresh reads; state the rule you used and the availability consequence of each.
5. Distinguish the two meanings of "consistency" and place each in ACID vs CAP; then say which one "eventual consistency" refers to.
6. You set `innodb_flush_log_at_trx_commit=2` for throughput. Describe the exact failure scenario where you lose data, how much, and what you'd change for an orders table.
7. Explain how Spanner achieves external consistency across continents and what it costs per commit; contrast with how CockroachDB approximates it without atomic clocks.
8. Show a write-skew scenario that snapshot isolation permits, then two different ways to prevent it.
```

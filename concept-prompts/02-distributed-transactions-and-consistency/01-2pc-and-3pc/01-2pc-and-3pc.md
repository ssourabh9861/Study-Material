# Two-Phase & Three-Phase Commit (2PC / 3PC)

> A definitive engineering-handbook chapter on atomic commitment protocols for distributed transactions — from first principles to deep internals, with Java/JTA/XA specifics, production failure stories, and an interview drill.

---

## 1. Overview & where it fits

### What it is

**Two-Phase Commit (2PC)** is a distributed *atomic commitment protocol*. Its single job is to make a group of independent processes (databases, message queues, services) agree, all-or-nothing, on whether to **commit** or **abort** a transaction that touched all of them. Either *every* participant durably applies the changes, or *every* participant durably discards them. There is no in-between where some commit and some abort.

**Three-Phase Commit (3PC)** is a refinement of 2PC that inserts an extra round of messaging to remove one specific weakness of 2PC — the *blocking* problem — under a restricted failure model. It is theoretically interesting and almost never used in practice (we explain exactly why later).

Let us define the terms a newcomer needs immediately:

- **Distributed transaction:** a transaction whose work spans more than one independent *resource manager* — e.g., writing to a MySQL database *and* an Oracle database *and* posting to a message broker, all as one logical unit. Contrast with a *local transaction*, which lives entirely inside one database.
- **Resource manager (RM):** the software that owns a piece of durable state and can commit/rollback its own local transaction — typically a database engine, a JMS broker, or a transactional cache. In 2PC vocabulary these are the **participants** (a.k.a. *cohorts*).
- **Coordinator (a.k.a. transaction manager, TM):** the single process that drives the protocol — it asks every participant to prepare, collects their votes, and then tells everyone the final outcome.
- **Atomicity:** the "A" in ACID. A transaction's effects are all-or-nothing. 2PC is fundamentally a mechanism to extend atomicity across process and machine boundaries.
- **ACID:** Atomicity, Consistency, Isolation, Durability — the four classic guarantees of a database transaction. 2PC is the cross-system enforcer of **A** (and indirectly **D**, durability, because votes and decisions are logged).

### The problem it solves: the *atomic commit* problem

Suppose a money-transfer must debit account A in Bank-DB-1 and credit account B in Bank-DB-2. Each database can commit its *own* change atomically, but nothing coordinates the two. Naively:

```
DB1: debit A   -> commit
DB2: credit B  -> CRASH before commit
```

Now money has vanished. We need a protocol such that *both* commits happen or *neither* does, even though the two databases never talk to each other and either can crash at any instant, and the network can drop or delay messages.

This is the **atomic commitment problem**: get N autonomous participants to reach a unanimous, durable, irreversible decision (commit or abort) where:

- **AC1 (uniform agreement):** all participants that decide, decide the same value.
- **AC2 (no spurious commit):** a participant can only decide *commit* if **all** participants voted *yes*.
- **AC3 (validity):** if there are no failures and all vote *yes*, the decision is *commit*.
- **AC4 (non-triviality / stability):** once a participant decides, it cannot change its mind.
- **AC5 (termination / liveness):** every non-faulty participant eventually decides.

2PC satisfies AC1–AC4 always, but **sacrifices AC5 under certain failures** (it can block). This single tradeoff is the crux of the entire topic.

### When you reach for it

- You have **two or more transactional resources** that must change atomically and you cannot redesign them to live in one database.
- You control (or can deploy) a **transaction manager**, and the resources speak a 2PC-capable protocol (in Java, that means **XA**).
- The participants are **trusted, low-latency, and administratively close** — classically inside one company's data center (a database, a JMS queue, a mainframe resource).
- You can tolerate **reduced availability** (blocking) in exchange for **strong atomicity**, or you have a recovery process to unblock.

You **avoid** it when participants are loosely coupled microservices over slow/unreliable networks, when you need high availability over strict atomicity, or when one party is a third-party API with no XA support. There, you reach for **sagas**, **outbox + idempotent consumers**, or **eventual consistency** instead (covered in §8).

### The one-paragraph mental model

> 2PC is a wedding officiant. In **Phase 1 (voting/prepare)**, the officiant asks each party "do you take this transaction?" — each party must answer *I do* (yes) or *I object* (no), and crucially, after saying *I do* it is **bound**: it has written a durable record promising it *can and will* commit if told to, and it gives up the right to unilaterally abort. In **Phase 2 (decision/commit)**, if and only if *everyone* said *I do*, the officiant declares them married (commit); a single objection or no-show means the ceremony is abandoned (abort). The fatal flaw: if the officiant faints right after the last *I do* but before declaring the outcome, the bound parties must stand frozen at the altar — unable to leave (abort) because they promised, unable to proceed (commit) because no one told them to. That frozen state is **blocking**.

---

## 2. Foundations from first principles

### 2.1 Why local atomicity is not enough

A single database achieves atomicity with a **write-ahead log (WAL)** and locks. The WAL is an append-only on-disk log: before changing a data page, the engine writes a log record describing the change. On commit it writes a *commit record* and forces (flushes) the log to durable storage (`fsync`). On crash recovery, the engine **redoes** committed transactions and **undoes** uncommitted ones by replaying/reverting log records.

- **fsync / force:** a system call that flushes the OS file buffers to physical disk so data survives a power loss. "Forcing the log" means the commit is durable before the application is told "committed." This costs a disk-sync (~0.1–10 ms depending on storage) and is the dominant latency in committing.
- **Redo / Undo:** redo re-applies committed changes lost from the buffer pool; undo reverts changes of transactions that never committed.

This works because *one* engine controls *all* the relevant state and *one* commit record is the single source of truth. The moment two engines are involved, there is no single commit record — that is exactly the gap 2PC fills by adding a **distributed** commit decision on top of each engine's **local** commit machinery.

### 2.2 The naive (broken) approaches and why they fail

1. **Just commit them in sequence.** Crash between commits → partial commit. Violates atomicity.
2. **Commit one, then commit the other, rollback the first if the second fails.** Once a database commits, it has released locks and made the change visible; you cannot reliably "un-commit" — compensation is possible but is *not* atomicity (other transactions may have already read the committed value). This is the saga approach, not atomic commit.
3. **Single round: "everyone commit now."** A participant might fail *during* its commit after others succeeded. No way to back out.

The insight 2PC adds: **separate the decision to be *able* to commit from the decision to *actually* commit.** Phase 1 makes everyone *prepared* (able, and bound); Phase 2 makes the global decision once everyone is provably able.

### 2.3 The "prepared" state — the keystone concept

The genius of 2PC is the **prepared** (a.k.a. *in-doubt* or *ready*) state of a participant. When a participant votes *yes*:

- It has done all the work, acquired and **held** all locks, and written all changes to its log.
- It has **force-written a `prepare` log record** to durable storage. This record means: *"I promise I can commit this transaction even if I crash and restart right now; I will not unilaterally abort it; I await the coordinator's decision."*
- It has **relinquished its autonomy** — it can no longer decide on its own. It must obey the coordinator's final order.

A transaction sitting in the prepared state across a crash is called an **in-doubt transaction**. It still holds its locks. This is both the source of 2PC's correctness *and* its blocking problem.

### 2.4 The failure model (assumptions matter enormously)

Atomic-commit protocols are analyzed under explicit assumptions. The standard ones:

- **Fail-stop / crash-recovery model:** processes fail by stopping (crashing), and may later recover and read their durable log. They do **not** behave maliciously (no Byzantine faults). *Byzantine* means arbitrary/adversarial behavior — lying, sending conflicting messages; tolerating it requires different protocols (BFT) and is out of scope here.
- **Stable storage:** each process has durable storage (the log) that survives crashes. This is what lets a recovered process figure out what it had promised.
- **Network:** messages may be lost, delayed, duplicated, or reordered, but not corrupted undetectably (TCP/checksums handle that). The network can **partition** (split into groups that cannot talk).
- **Synchrony assumptions:** whether you have bounded message delay and bounded clock drift. **Synchronous** = known upper bounds (you can use timeouts to *reliably* detect failure). **Asynchronous** = no bounds (a slow node is indistinguishable from a dead one). The real internet is *partially synchronous*. This distinction is the whole reason 3PC is fragile (see §7).

A foundational result hangs over all of this: the **FLP impossibility** (Fischer–Lynch–Paterson, 1985) proves that in a *purely asynchronous* system with even one crash failure, no deterministic protocol can guarantee both safety and liveness for consensus. Atomic commit is consensus-flavored, so we cannot have a protocol that is *always* both correct and non-blocking under fully asynchronous failures. 2PC chooses safety (never wrong) and gives up liveness (can block). Paxos/Raft-based commit choose to remain available as long as a majority survives. There is no free lunch.

### 2.5 Roles, messages, and logs

- **Coordinator (TM):** initiates, sends `PREPARE` (a.k.a. *can-commit?* / vote-request), tallies votes, makes the global decision, sends `COMMIT`/`ABORT`, collects acknowledgements, forgets the transaction.
- **Participant (RM):** does the work, votes, obeys.
- **Messages:** `PREPARE`/`VOTE-REQUEST` → `VOTE-YES`/`VOTE-NO` → `GLOBAL-COMMIT`/`GLOBAL-ABORT` → `ACK`.
- **Log records (these are what make it survive crashes):**
  - Coordinator writes: `begin/collecting`, then **`commit` decision record (force-written before sending COMMIT)**, then `end/done` after all ACKs.
  - Participant writes: `prepare` record (force-written before voting YES), then `commit`/`abort` record (force-written before ACK).

The phrase "**force-write**" (synchronous, `fsync`-backed log write) is non-negotiable at the points above; if a process logged lazily and crashed, it could forget a promise or a decision and violate atomicity.

---

## 3. How it works internally

This section is the heart of the document. We trace the protocol message-by-message and log-record-by-log-record, then walk every failure-recovery path.

### 3.1 The happy path (everyone votes yes), step by step

Assume coordinator **C** and participants **P1, P2, P3**.

**Phase 1 — Voting / Prepare:**

1. C decides to commit transaction T. C **force-writes** a log record marking T as `PREPARING` / lists the participants. (Some implementations defer this; the critical force-write is the *commit* record later.)
2. C sends `PREPARE(T)` to P1, P2, P3 and starts a **timeout timer**.
3. Each Pi receives `PREPARE`. It validates it can commit (constraints OK, no deadlock, disk space, etc.). If yes:
   - Pi does all remaining work, ensures **all undo/redo log records for T are force-written**.
   - Pi **force-writes a `prepared` (ready) record** to its log.
   - Pi enters the **prepared / in-doubt** state, **keeps all locks held**, and replies `VOTE-YES`.
   - From now on Pi may **not** unilaterally abort; it is bound.
   - If Pi cannot commit (error/constraint), it force-writes an `abort` record, releases locks, replies `VOTE-NO`, and may forget T (it can abort unilaterally *before* voting yes).

**Phase 2 — Decision / Commit:**

4. C collects votes. If **all** are `VOTE-YES`:
   - C **force-writes a `COMMIT` decision record** to its log. **This is the commit point** — the instant T is officially committed in the universe. Even if C crashes right after this write, on recovery it will re-drive commit.
   - C sends `GLOBAL-COMMIT(T)` to all participants and starts a timer for ACKs.
5. Each Pi receives `GLOBAL-COMMIT`:
   - Pi **force-writes a `commit` record**, makes the changes durable, **releases all locks**, and sends `ACK(T)` to C.
6. C collects all ACKs. Once all received, C **writes an `end`/`forget` record** and forgets T (garbage-collects its log entry). The transaction is complete.

If **any** vote is `VOTE-NO` or a timeout fires before all votes arrive:

4'. C **force-writes an `ABORT` decision record**, sends `GLOBAL-ABORT(T)` to all participants that voted (or all). Each Pi force-writes `abort`, releases locks, ACKs. C forgets T.

#### Message/round complexity

- Messages: `2N` (prepare + vote) + `2N` (commit/abort + ack) = **4N messages** for N participants, in **2 round-trips**.
- Forced log writes: coordinator **1** (the decision), each participant **2** (prepare, commit). So `2N + 1` forced disk syncs — these dominate latency.

#### ASCII timeline (happy path)

```
   Coordinator C                P1            P2            P3
        |  --- PREPARE ------->  |             |             |
        |  --- PREPARE -------------------->   |             |
        |  --- PREPARE ----------------------------------->  |
        |                       [force prepared rec, hold locks]
        |  <---- VOTE-YES ------ |             |             |
        |  <---- VOTE-YES ------------------- |             |
        |  <---- VOTE-YES --------------------------------- |
   [force COMMIT decision record  <-- COMMIT POINT]
        |  --- GLOBAL-COMMIT --> |             |             |
        |       ...              [force commit rec, release locks]
        |  <------ ACK --------- |  (x3)
   [write end/forget record]
```

### 3.2 The state machines

**Coordinator state machine:**

```
INIT --(send PREPARE)--> WAIT
WAIT --(all VOTE-YES)--> [force COMMIT] --> COMMITTED --(all ACK)--> DONE/forget
WAIT --(any VOTE-NO / timeout)--> [force ABORT] --> ABORTED --(all ACK)--> DONE/forget
```

**Participant state machine:**

```
INIT --(recv PREPARE, can commit)--> [force prepared] --> READY (in-doubt, locks held)
INIT --(recv PREPARE, cannot)--> [force abort] --> ABORTED  (vote NO)
INIT --(timeout waiting for PREPARE)--> ABORTED  (unilateral abort allowed here)
READY --(recv GLOBAL-COMMIT)--> [force commit] --> COMMITTED
READY --(recv GLOBAL-ABORT)--> [force abort] --> ABORTED
READY --(timeout waiting for decision)--> *** BLOCKED *** (must run termination protocol)
```

The **READY/BLOCKED** transition is the entire problem. In `READY`, a participant has lost the right to decide for itself and is at the mercy of the coordinator.

### 3.3 Failure scenarios and recovery — exhaustively

The behavior on failure is the most-tested, least-understood part. We go case by case. Two tools matter:

- **Timeouts:** how a process reacts to silence.
- **Recovery from the log:** what a restarted process does after reading its durable records.

#### Case A — Participant times out waiting for PREPARE (still in INIT)

It never voted, never promised. It can **unilaterally ABORT** safely. (Coordinator will likewise abort when its vote times out.) No blocking. This is the only state where a participant has freedom.

#### Case B — Coordinator times out waiting for votes (in WAIT)

A participant is slow/dead. Coordinator decides **ABORT**, force-writes abort, broadcasts `GLOBAL-ABORT`. Safe: no participant has been told to commit. No blocking. (Coordinator can always abort while in WAIT.)

#### Case C — Participant crashes *before* writing `prepared` (in INIT)

On restart it finds no `prepared` record for T → it never voted yes → it **aborts** T locally. If the coordinator later sends `GLOBAL-COMMIT` it would be wrong, but it cannot: a participant that hasn't sent `VOTE-YES` means the coordinator could not have committed (AC2). So coordinator either also timed out (Case B → abort) or is still waiting. Consistent: abort.

#### Case D — Participant crashes *after* `prepared`, *before* receiving decision (in READY)

On restart it finds a `prepared` record but no `commit`/`abort` record → T is **in-doubt**. It cannot decide alone. It must **ask the coordinator** "what was the outcome of T?" (a *recovery query*). The coordinator (if alive) replies COMMIT or ABORT from its log. If the coordinator has *forgotten* T (because it had committed/aborted and all others ACKed long ago), the convention is **`presumed abort`** (see §3.4). The locks for T remain held until resolved.

#### Case E — Coordinator crashes (the dangerous one)

Sub-cases by when it crashed, recoverable from its log:

- **Before force-writing the decision (in WAIT):** on restart, no `commit` record → coordinator **aborts** T, notifies participants. Participants in READY were blocked meanwhile but get released on recovery. Safe.
- **After force-writing `COMMIT`, before/while broadcasting:** on restart, finds `COMMIT` record (no `end`) → **re-broadcasts `GLOBAL-COMMIT`** to participants that haven't ACKed. Safe; T commits everywhere. Idempotency required (a participant may receive COMMIT twice).
- **After force-writing `ABORT`:** symmetric → re-broadcasts ABORT.
- **After `end`/forget:** nothing to do; T fully resolved.

The window of pain: a participant in **READY** while the **coordinator is down and unreachable**. The participant is **blocked** — holding locks, unable to terminate — until the coordinator recovers or an operator intervenes. *During this window, the locked rows are unavailable to everyone.* This is the **blocking problem**.

#### Case F — The cooperative termination protocol (mitigation, not cure)

A blocked participant in READY can ask its **peers** instead of only the coordinator:

- If any peer has already received `GLOBAL-COMMIT`/`GLOBAL-ABORT`, it shares the decision → blocked node follows it. 
- If any peer is still in INIT (never voted) or already ABORTED, it implies abort is safe.
- **But** if *all* surviving peers are *also* in READY and the coordinator is down, **nobody knows the decision** — the coordinator may have force-written COMMIT and crashed before telling anyone. The whole cohort blocks. This is why **2PC is a *blocking* protocol**: there exists a failure pattern (coordinator + the one informed participant fail) where survivors cannot safely terminate.

### 3.4 Presumed-abort and presumed-commit optimizations

Logging and ACK overhead is reduced by *presumption* conventions about forgotten transactions.

- **Presumed Abort (PA) — the default in XA/JTA and most databases.** If a recovering participant queries the coordinator about T and the coordinator has **no record** of T, the answer is "abort." This means:
  - The coordinator need **not** force-write *before* an abort decision and need **not** log abort completion — absence of record ⇒ abort.
  - Read-only participants and aborts cost less; the common abort path is cheaper.
- **Presumed Commit (PC).** Symmetric: no record ⇒ commit. Requires the coordinator to force-write a record *before* starting the protocol (so it doesn't accidentally presume-commit a transaction it never decided). Rarely the default because it penalizes the (more common) prepare/abort path.

Most real systems (Oracle, the X/Open standard, JTA TMs) use **presumed abort**.

### 3.5 The read-only optimization

If a participant did **no writes** in T, it can vote **`VOTE-READ-ONLY`** instead of yes. It then releases locks immediately, writes nothing, and is **dropped from Phase 2** entirely — the coordinator never sends it a decision. This saves a log force and a round of messages per read-only participant, which is significant since many participants in a distributed transaction touch data read-only.

### 3.6 One-phase commit (1PC) / last-resource optimization

If there is **exactly one** participant (after read-only ones are dropped), the coordinator can skip Phase 1 and just issue a **local commit** — there is nothing to coordinate. JTA implementations call this the **one-phase optimization**. The **Last Resource Commit (LRCO)** variant lets you enlist *one* non-XA (single-phase-only) resource in an otherwise-XA transaction: all XA resources prepare first, then the single non-XA resource commits last; if it succeeds the XA resources commit, else they roll back. It widens the window for inconsistency (if the TM crashes between the single resource's commit and the XA commits) and must be used carefully.

### 3.7 Three-Phase Commit (3PC) internals

3PC's goal: make the protocol **non-blocking** under the *crash-only, synchronous, no-partition* failure model by ensuring no participant can commit while another could still abort without all of them knowing it's safe.

It inserts a **pre-commit** phase between voting and committing:

**Phase 1 — CanCommit?** Coordinator asks `CAN-COMMIT?`; participants reply Yes/No. (Like 2PC prepare, but participants do **not** yet enter an unbreakable bound state — they have **not** force-written a hard prepare.)

**Phase 2 — PreCommit.** If all Yes, coordinator force-logs and sends `PRE-COMMIT`. Each participant acknowledges and moves to a **prepared/precommit** state — now it knows *everyone* voted yes (the decision will be commit), but it has **not** committed yet.

**Phase 3 — DoCommit.** Coordinator sends `DO-COMMIT`; participants commit and ACK.

Why this removes blocking *under its model*: there is now a clean separation between "everyone can commit" (after PreCommit, all know the outcome will be commit) and "everyone has committed." If the coordinator dies:

- If a recovering/timing-out participant is in the **PreCommit** state, it knows all voted yes → safe to **commit** (and elect a new coordinator that finishes commit).
- If it is still only in the **CanCommit/Yes** state and times out, no one could have reached DoCommit, so it is safe to **abort**.

A participant can use **timeouts to act unilaterally** without risking inconsistency, because the protocol guarantees that adjacent states differ by at most one "step" and there is no state where one node can commit while another must abort without that being detectable.

**3PC state machine (participant):**

```
INIT --(CAN-COMMIT? yes)--> WAIT(uncertain)
WAIT --(timeout / coord dead)--> ABORT     (safe: nobody can have committed)
WAIT --(PRE-COMMIT)--> PRECOMMIT(prepared)
PRECOMMIT --(timeout / coord dead)--> COMMIT (safe: everyone voted yes)
PRECOMMIT --(DO-COMMIT)--> COMMITTED
```

### 3.8 Why 3PC fails in practice — network partitions and asynchrony

3PC's non-blocking guarantee **assumes a synchronous network with reliable failure detection and no partitions**. The real world has all three problems. Under a **network partition**:

- Suppose the cohort splits into Group X (in PreCommit) and Group Y (still in WAIT/uncertain) with the coordinator on one side. Group X times out and **commits** (it's in PreCommit). Group Y times out and **aborts** (it's in WAIT). When the partition heals → **split-brain / inconsistency**: some committed, some aborted. 3PC violates atomicity under partition.

So 3PC trades 2PC's *blocking* for *inconsistency under partition* — and inconsistency (a safety violation) is far worse than blocking (a liveness/availability problem). Add to that:

- An extra message round and extra forced log writes → **higher latency**, worse normal-case performance.
- It still has a single coordinator (single point of decision); it just changed the failure behavior.

This is why **no mainstream production system uses textbook 3PC.** The industry instead solved the blocking problem differently: replace the single coordinator with a **fault-tolerant consensus group** (Paxos/Raft), giving **Paxos Commit** — non-blocking *and* partition-safe (as long as a majority survives), at the cost of more nodes and messages. Google **Spanner** does exactly this (see §7.4).

---

## 4. The complete toolkit

In the Java/JVM world, distributed transactions are standardized through **JTA** (the API your code uses) sitting on top of **XA** (the wire/native protocol resources implement). Let us define and enumerate.

### 4.1 The standards and what each is

- **X/Open DTP (Distributed Transaction Processing) model:** the 1991 industry reference model with three roles — **Application Program (AP)**, **Transaction Manager (TM)**, **Resource Manager (RM)** — connected by the **XA interface** (TM↔RM) and the **TX interface** (AP↔TM).
- **XA:** the C-level interface specification between a TM and an RM. The TM calls functions like `xa_start`, `xa_end`, `xa_prepare`, `xa_commit`, `xa_rollback`, `xa_recover`. Databases (Oracle, PostgreSQL, MySQL/InnoDB, Db2, SQL Server) and brokers (IBM MQ, ActiveMQ) implement the RM side. **XA *is* 2PC** — `xa_prepare` is Phase 1, `xa_commit`/`xa_rollback` is Phase 2.
- **JTA (Jakarta/Java Transaction API):** the Java mapping of the X/Open model. Key types: `UserTransaction`, `TransactionManager`, `Transaction`, `XAResource`, `Xid`, `Synchronization`. JTA is the *API*; an implementation is a **JTA transaction manager**.
- **JTS (Java Transaction Service):** the (largely historical) CORBA OTS-based standard for TM interoperability across vendors. Rarely used now.
- **JCA / XAConnectionFactory / XADataSource:** the JDBC/JMS hooks that expose a resource's `XAResource` so the TM can enlist it.

### 4.2 Core JTA / XA API surface

| Type / method | Role | Purpose | Key params / notes |
|---|---|---|---|
| `javax.transaction.UserTransaction` | AP-facing | App boundary control | `begin()`, `commit()`, `rollback()`, `setRollbackOnly()`, `getStatus()`, `setTransactionTimeout(int sec)` |
| `javax.transaction.TransactionManager` | Container/TM | Full lifecycle + thread association | `begin/commit/rollback/suspend()/resume(tx)/getTransaction()` |
| `javax.transaction.Transaction` | per-tx object | Enlist resources, register sync | `enlistResource(XAResource)`, `delistResource(xar,flag)`, `registerSynchronization(Synchronization)` |
| `javax.transaction.xa.XAResource` | RM-facing | The 2PC verbs | `start(Xid,flags)`, `end(Xid,flags)`, `prepare(Xid)`, `commit(Xid, boolean onePhase)`, `rollback(Xid)`, `recover(int flag)`, `forget(Xid)`, `setTransactionTimeout`, `isSameRM` |
| `javax.transaction.xa.Xid` | identity | Global transaction id | `getFormatId()`, `getGlobalTransactionId()`, `getBranchQualifier()` — a tx may have multiple **branches** (one per RM) under one global id |
| `javax.transaction.Synchronization` | callback | Pre/post commit hooks | `beforeCompletion()` (still in tx, can flush/veto), `afterCompletion(int status)` |
| `javax.sql.XADataSource` / `XAConnection` | JDBC | Yields an `XAResource` for a DB | use the vendor's `*XADataSource` class, not the plain one |
| `javax.jms.XAConnectionFactory` | JMS | Yields `XAResource` for a broker | enlist a JMS session in the global tx |

#### `XAResource.prepare` return codes

| Return | Meaning |
|---|---|
| `XA_OK` | Voted yes; in-doubt; awaiting decision |
| `XA_RDONLY` | Read-only; dropped from phase 2 (the read-only optimization) |
| (throws `XAException` with code `XA_RBxxx`) | Rollback; voted no |

#### `XAResource.commit/rollback/recover` flags & exception codes (selected)

| Constant | Where | Meaning |
|---|---|---|
| `TMSUCCESS`, `TMFAIL`, `TMSUSPEND`, `TMRESUME`, `TMJOIN`, `TMNOFLAGS` | `start/end` flags | how the branch is associated/disassociated with the thread |
| `TMSTARTRSCAN`, `TMENDRSCAN`, `TMNOFLAGS` | `recover` flags | scan in-doubt Xids in the RM during recovery |
| `XA_HEURCOM`, `XA_HEURRB`, `XA_HEURMIX`, `XA_HEURHAZ` | `XAException` | **heuristic** outcomes — an RM unilaterally resolved an in-doubt tx (committed/rolled back/mixed/hazard) against the protocol, usually after a long block. **`XA_HEURMIX` is the nightmare**: part committed, part rolled back. |
| `XA_RETRY` | return | TM should retry later |
| `XAER_RMFAIL`, `XAER_RMERR`, `XAER_NOTA`, `XAER_PROTO` | errors | RM unavailable, RM error, no such Xid, protocol error |

### 4.3 Java JTA transaction managers (implementations)

| TM | Context | Notes |
|---|---|---|
| **Narayana** (JBoss/WildFly, ex-JBossTS/Arjuna) | The de-facto standalone JTA TM; used by Quarkus, Spring (via `narayana-jta`) | Mature recovery manager, object store for logs, presumed abort, LRCO, supports JTS for interop |
| **Atomikos** (TransactionsEssentials) | Popular standalone in Spring Boot apps | `UserTransactionManager`, log dir config; commercial ExtremeTransactions edition |
| **Bitronix (BTM)** | Lightweight standalone, now community-maintained | Simple config; less active |
| **GlassFish/Payara, WebLogic, WebSphere, JBoss built-in** | App-server-managed | Container provides JTA; you just use `@Transactional`/UserTransaction |
| **Spring `JtaTransactionManager`** | Adapter, not a TM itself | Delegates to one of the above; Spring Boot auto-configures Atomikos/Narayana when present |

### 4.4 Database/RM side — enabling XA (vendor-specific, real flags)

| RM | How to enable / observe in-doubt txns |
|---|---|
| **Oracle** | `OracleXADataSource`; in-doubt txns visible in `DBA_2PC_PENDING` and `DBA_2PC_NEIGHBORS`; manually resolve with `COMMIT FORCE 'local_tran_id'` / `ROLLBACK FORCE`; `DISTRIBUTED_TRANSACTIONS` init param; `RECO` background process auto-recovers |
| **PostgreSQL** | Must set `max_prepared_transactions > 0` (default **0 = disabled**); `PREPARE TRANSACTION 'gid'`, `COMMIT PREPARED`, `ROLLBACK PREPARED`; in-doubt visible in `pg_prepared_xacts`. **Prepared txns hold locks and block VACUUM** — orphaned ones are dangerous |
| **MySQL/InnoDB** | `XA START/END/PREPARE/COMMIT/ROLLBACK`; `XA RECOVER` lists in-doubt; historically buggy across crashes pre-5.7; binlog/XA interplay |
| **SQL Server** | MS DTC (Distributed Transaction Coordinator) acts as TM; `XACT_ABORT` |
| **Db2** | Native XA; `LIST INDOUBT TRANSACTIONS` |
| **IBM MQ / ActiveMQ Artemis** | `XAConnectionFactory`; broker stores prepared transactions |

### 4.5 CLI / operational commands cheat list

```sql
-- PostgreSQL: see and resolve orphaned prepared transactions
SHOW max_prepared_transactions;            -- must be > 0 for XA
SELECT * FROM pg_prepared_xacts;           -- list in-doubt
ROLLBACK PREPARED 'gid_string';            -- forcibly abort an orphan
COMMIT PREPARED 'gid_string';              -- forcibly commit

-- Oracle: see and resolve in-doubt
SELECT * FROM DBA_2PC_PENDING;
COMMIT FORCE '1.23.456';                    -- local transaction id
ROLLBACK FORCE '1.23.456';

-- MySQL
XA RECOVER;                                 -- list prepared (in-doubt)
XA COMMIT 'xid'; XA ROLLBACK 'xid';
```

---

## 5. Code examples by use case

These are Java/JTA examples spanning genuinely different scenarios. Comments mark the non-obvious lines.

### 5.1 Programmatic JTA: DB + JMS atomic commit (Atomikos, standalone)

Scenario: consume an order from a queue *and* insert it into a DB as one atomic unit. Both must commit or both roll back.

```java
// Atomikos provides the TransactionManager + UserTransaction.
import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.atomikos.jms.AtomikosConnectionFactoryBean;

UserTransactionManager utm = new UserTransactionManager();
utm.init(); // starts the recovery manager that scans for in-doubt txns on startup

// XA DataSource — note the *XA* datasource class, not the plain pooled one.
AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
ds.setUniqueResourceName("ordersDB");                  // MUST be stable & unique:
ds.setXaDataSourceClassName("org.postgresql.xa.PGXADataSource"); // used to match in-doubt branches on recovery
Properties p = new Properties();
p.setProperty("user", "app"); p.setProperty("password", "secret");
p.setProperty("serverName", "db-host"); p.setProperty("databaseName", "orders");
ds.setXaProperties(p);

AtomikosConnectionFactoryBean cf = new AtomikosConnectionFactoryBean();
cf.setUniqueResourceName("ordersBroker");
cf.setXaConnectionFactory(brokerXaConnFactory);        // vendor XAConnectionFactory

utm.begin();                                           // PHASE 0: start global tx, set on thread
utm.setTransactionTimeout(30);                         // abort if not done in 30s (prevents eternal blocking)
try {
    // Each getConnection() auto-enlists its XAResource into the current global tx.
    try (java.sql.Connection c = ds.getConnection();
         javax.jms.Connection jc = cf.createConnection()) {
        // ... read message from jc's session, INSERT into DB via c ...
    }
    utm.commit();   // Drives 2PC: prepare(DB), prepare(JMS); if both XA_OK -> commit both.
} catch (Exception e) {
    utm.rollback(); // GLOBAL-ABORT to all enlisted resources
    throw e;
}
```

Key points: the **`uniqueResourceName` must be stable across restarts** — the recovery manager uses it to match logged in-doubt branches to the right RM after a crash. Changing it orphans in-doubt transactions.

### 5.2 Declarative JTA in Spring Boot (the idiomatic way)

Scenario: a service method that writes to two different databases atomically.

```java
// build.gradle: implementation 'org.springframework.boot:spring-boot-starter-jta-atomikos'
// (or narayana). Spring Boot auto-configures the JtaTransactionManager.

@Service
public class TransferService {

    private final JdbcTemplate bankA;   // backed by an XADataSource (datasource A)
    private final JdbcTemplate bankB;   // backed by an XADataSource (datasource B)

    @Transactional                      // JTA global transaction across BOTH datasources
    public void transfer(String from, String to, BigDecimal amt) {
        bankA.update("UPDATE acct SET bal = bal - ? WHERE id = ?", amt, from);
        bankB.update("UPDATE acct SET bal = bal + ? WHERE id = ?", amt, to);
        // On normal return: Spring calls TM.commit() -> 2PC prepare both, commit both.
        // On any RuntimeException: TM.rollback() -> both roll back. Atomic.
    }
}
```

Both datasources must be **XA datasources** (e.g., `org.postgresql.xa.PGXADataSource`) for this to be a true 2PC. If they are plain datasources, Spring cannot span them atomically and will fail or silently degrade.

### 5.3 Raw `XAResource` orchestration (what the TM does under the hood)

Scenario: educational — manually drive 2PC across two XA resources so you can *see* every phase. You almost never write this by hand, but it demystifies the TM.

```java
import javax.transaction.xa.*;

XAResource rm1 = xaConn1.getXAResource();
XAResource rm2 = xaConn2.getXAResource();

// One global transaction id, two BRANCH qualifiers (one per RM).
Xid xid1 = new MyXid(100, gtrid, new byte[]{0x01});
Xid xid2 = new MyXid(100, gtrid, new byte[]{0x02});

try {
    // ---- associate work with each branch ----
    rm1.start(xid1, XAResource.TMNOFLAGS);
    // ... do JDBC work on conn1 ...
    rm1.end(xid1, XAResource.TMSUCCESS);

    rm2.start(xid2, XAResource.TMNOFLAGS);
    // ... do JDBC work on conn2 ...
    rm2.end(xid2, XAResource.TMSUCCESS);

    // ---- PHASE 1: PREPARE (the vote) ----
    int v1 = rm1.prepare(xid1);    // XA_OK = yes, XA_RDONLY = read-only (drop it)
    int v2 = rm2.prepare(xid2);

    // ---- PHASE 2: DECISION ----
    if (v1 == XAResource.XA_OK && v2 == XAResource.XA_OK) {
        rm1.commit(xid1, false);   // false = NOT one-phase; honor the prepared decision
        rm2.commit(xid2, false);
    } else {
        if (v1 == XAResource.XA_OK) rm1.rollback(xid1);
        if (v2 == XAResource.XA_OK) rm2.rollback(xid2);
    }
} catch (XAException xae) {
    // Heuristic codes (XA_HEURMIX etc.) surface here. Must be logged & alerted.
    rm1.rollback(xid1); rm2.rollback(xid2);
}
```

### 5.4 Recovery: resolving in-doubt transactions after a crash

Scenario: the TM restarted and must find branches that are prepared-but-undecided in each RM.

```java
// Called by the TM's recovery manager periodically and at startup.
Xid[] inDoubt = rm1.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
for (Xid x : inDoubt) {
    Decision d = transactionLog.lookup(x);   // consult the TM's durable decision log
    if (d == Decision.COMMIT)      rm1.commit(x, false);
    else if (d == Decision.ABORT)  rm1.rollback(x);
    else /* no record */           rm1.rollback(x); // PRESUMED ABORT default
}
```

This loop is exactly why the TM keeps a durable **object store / transaction log**: to answer "what did I decide?" for in-doubt branches after a crash.

### 5.5 The microservices alternative: a Saga (because 2PC is the wrong tool here)

Scenario: order across Order, Payment, Inventory microservices, each with its *own* DB and *no* shared TM. We use a **saga** — a sequence of local transactions with **compensating actions** — instead of 2PC.

```java
// Orchestration-based saga (a central orchestrator issues commands + compensations).
public OrderResult placeOrder(Order o) {
    var payment = paymentService.charge(o.customer(), o.total());  // local tx, committed
    try {
        inventoryService.reserve(o.items());                       // local tx, committed
    } catch (OutOfStock e) {
        paymentService.refund(payment.id());   // COMPENSATION: undo the prior step
        throw new OrderFailed(e);
    }
    try {
        shippingService.schedule(o);
    } catch (Exception e) {
        inventoryService.release(o.items());   // compensate step 2
        paymentService.refund(payment.id());   // compensate step 1
        throw new OrderFailed(e);
    }
    return OrderResult.confirmed(o.id());
}
```

This is **not** atomic (there are windows where payment is charged but order not yet confirmed) — it provides **eventual consistency** with **semantic rollback** (refund ≠ "uncommit"). It trades 2PC's strict atomicity and blocking for availability and loose coupling. See §8.

### 5.6 Transactional Outbox + idempotent consumer (the dominant microservice pattern)

Scenario: update a DB and publish an event atomically *without* XA across DB and broker.

```java
@Transactional   // ONE local DB transaction — no distributed commit at all
public void createOrder(Order o) {
    orderRepo.save(o);
    // Write the event to an "outbox" table in the SAME local transaction.
    outboxRepo.save(new OutboxEvent("OrderCreated", o.id(), toJson(o)));
}
// A separate relay (CDC like Debezium, or a poller) reads the outbox and publishes
// to Kafka, marking rows sent. Consumers must be IDEMPOTENT (dedupe by event id),
// because the relay guarantees AT-LEAST-ONCE delivery, not exactly-once.
```

The DB write and the "intent to publish" commit atomically in a single *local* transaction; the broker publish happens later, reliably, with at-least-once semantics. This sidesteps 2PC entirely and is the modern default.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Latency:** 2PC adds **two synchronous round-trips** and **2N+1 forced `fsync`s**. Commit latency is roughly `2 × network_RTT + (slowest participant's 2 log forces) + coordinator log force`. On a LAN this is single-digit ms; cross-region it is tens-to-hundreds of ms.
- **Lock duration:** participants **hold locks from prepare until the decision arrives**. This window is `1 RTT + coordinator processing` in the happy path, but **unbounded** if the coordinator stalls. Held locks reduce concurrency and increase deadlock/contention. This is often the real performance killer, not raw latency.
- **Throughput:** the coordinator and its log are a serialization point. Group-commit (batching log forces) and parallel prepare help.
- **Optimizations to apply:** read-only optimization, one-phase optimization when a single resource remains, presumed abort, last-resource commit (carefully), and minimizing the number of enlisted resources.

### 6.2 Correctness & concurrency

- **Force-write ordering is sacred:** prepare record before voting yes; commit decision before sending COMMIT; commit record before ACK. Any reordering can violate atomicity across a crash.
- **Idempotency:** participants may receive COMMIT/ABORT/PREPARE more than once (retries, coordinator recovery). All handlers must be idempotent.
- **Isolation interaction:** the prepared state must keep the locks/MVCC versions that enforce the transaction's isolation level; releasing them early breaks isolation.

### 6.3 Heuristics — the dangerous escape hatch

When a participant is blocked too long, an operator (or the RM automatically after a configured timeout) may make a **heuristic decision** — commit or roll back the in-doubt branch *without* the coordinator's order. If the heuristic guess disagrees with the coordinator's actual decision, you get a **heuristic mixed** (`XA_HEURMIX`) or **heuristic hazard** (`XA_HEURHAZ`) outcome: **atomicity is silently broken**. These must be logged loudly, alerted on, and reconciled manually. Treat any heuristic outcome as a data-integrity incident.

### 6.4 Security

- The 2PC channel can carry the power to commit/abort financial transactions — secure it with **TLS/mutual auth**; an attacker who can inject `GLOBAL-COMMIT`/`ABORT` can corrupt data.
- XA Xids are not secrets but should not be guessable in ways that let an attacker resolve someone else's in-doubt branch.
- Protect the TM's transaction log (it contains commit decisions) and recovery endpoints.

### 6.5 Observability

- Monitor and alert on: **count and age of in-doubt/prepared transactions** (`pg_prepared_xacts`, `DBA_2PC_PENDING`, `XA RECOVER`), **heuristic outcomes**, **recovery-manager activity**, **transaction-timeout/rollback rate**, **lock-wait/contention on prepared rows**.
- An aging in-doubt transaction is a ticking bomb: it holds locks and (in Postgres) blocks `VACUUM`/freezing → can lead to transaction-ID wraparound emergencies.
- Trace IDs should span the whole global transaction so you can correlate branches.

### 6.6 Cost

- Cross-region 2PC multiplies cost: more round-trips on metered links, more `fsync`s on premium storage, reduced throughput → more nodes. The availability cost (blocking) is often the largest hidden cost.

### 6.7 Testing & production hardening

- **Inject coordinator crashes** between every protocol step (especially right after the commit force-write) and assert recovery resolves all branches consistently.
- **Inject participant crashes** in the prepared state; assert recovery queries the coordinator and resolves.
- **Partition tests:** verify the system *blocks* (correctly) rather than diverges.
- Set sane **transaction timeouts** (e.g., 30–120s) so stuck transactions abort instead of holding locks forever.
- Always set a **stable, unique resource name** per RM (recovery depends on it).
- Run the **recovery manager** continuously, not just at startup.
- Keep a **runbook** for resolving orphaned prepared transactions per RM.

### 6.8 Anti-patterns

- **2PC across microservices over the WAN / to third-party APIs.** Slow, fragile, blocking, often impossible (no XA). Use sagas/outbox.
- **2PC with a queue/broker that doesn't truly support XA prepare** (some "XA" support is faux). Verify.
- **Long-lived prepared transactions** (forgot to set timeout, orphaned by a dead TM). Causes lock pileups and VACUUM stalls.
- **Heuristic completion as routine.** It is a last resort, not a tuning knob.
- **Mixing one-phase and XA carelessly** (LRCO) without understanding the inconsistency window.
- **Treating 3PC as a drop-in fix for blocking.** It introduces partition inconsistency; reach for consensus-based commit instead.

---

## 7. Advanced topics & deep internals

### 7.1 Why 2PC is "blocking": the precise argument

A protocol is **non-blocking** if every non-faulty process can reach a decision despite the failure of others. 2PC is provably blocking: consider the cohort all in READY; the coordinator force-writes COMMIT, sends it to P1 only, then *both coordinator and P1 crash*. Surviving participants are all in READY; they cannot distinguish "coordinator decided COMMIT and crashed" from "coordinator decided ABORT and crashed" — both are consistent with their local state. To stay safe (never violate atomicity) they must **block** until the coordinator or P1 recovers. There is **no** timeout action they can take that is safe in all cases. This is the formal sense in which 2PC sacrifices liveness (AC5).

### 7.2 The independent-recovery impossibility (Skeen)

Dale Skeen (1981–82) proved: **no atomic commit protocol exists that is both non-blocking *and* allows independent recovery of all single-site failures, when communication failures (partitions) are possible.** 3PC achieves non-blocking only by *assuming no partitions and a synchronous network*. Once you admit partitions, you cannot have non-blocking + safety with a fixed-membership protocol — you need a majority-based (quorum) scheme. This theorem is the reason the industry moved to consensus.

### 7.3 Paxos/Raft Commit — the modern non-blocking 2PC

**Paxos / Raft** are consensus algorithms: a group of nodes (an odd number, e.g., 3 or 5) agrees on a value such that a **majority (quorum)** is enough to make progress, surviving a minority of failures and partitions safely. **Leader election** picks one node to propose; a value is chosen when a majority accepts it.

**Paxos Commit** (Gray & Lamport, 2006) replaces 2PC's single fault-prone coordinator with a fault-tolerant one: instead of the coordinator logging the decision to its single disk, **each participant's vote is logged into a Paxos instance** (a replicated, majority-acknowledged log). The commit decision is now a consensus decision that survives any minority failure or partition — **non-blocking as long as a majority is up**. Cost: more messages and more replicas, but no blocking and no partition inconsistency. This is the theoretically and practically correct answer to 2PC's weakness.

### 7.4 Where 2PC still lives — and how the survivors fixed blocking

- **Google Spanner:** uses **2PC for cross-shard transactions**, but each shard (participant *and* coordinator) is itself a **Paxos group** of replicas. So the coordinator is not a single machine that can vanish — it is a replicated, leader-elected group; if the leader dies, a new leader is elected from the Paxos group and *finishes* the 2PC. This makes Spanner's distributed commit **non-blocking** in practice. Spanner also uses **TrueTime** (GPS+atomic-clock-bounded clock uncertainty) to provide external consistency/linearizable timestamps, and holds locks during the 2PC window. Net: 2PC's *structure*, consensus's *fault tolerance*.
- **CockroachDB / YugabyteDB / TiDB:** distributed SQL databases that run 2PC-style commit over Raft groups; CockroachDB uses a "transaction record" + write intents and **parallel commits** to cut a round-trip, with Raft providing fault tolerance for the decision.
- **FoundationDB:** a different (optimistic, versioned) commit but conceptually a coordinated atomic commit over replicated logs.
- **Classic XA in the enterprise:** Java EE app servers, JTA TMs (Narayana, Atomikos) coordinating an RDBMS + JMS broker inside one data center — still common in banking/insurance/ERP. Here the single coordinator's blocking risk is accepted and managed with recovery + heuristics + monitoring.
- **MS DTC / mainframe (CICS, IMS):** decades of production 2PC.

### 7.5 Tuning knobs and lesser-known behavior

- **Transaction timeout** (`setTransactionTimeout`): bounds the prepared window before forced abort.
- **Recovery scan interval / orphan detection:** how often the TM scans RMs for in-doubt branches.
- **Heuristic timeout** on RMs: how long an RM waits before it may make a heuristic decision.
- **Group commit / log batching** on coordinator and RMs to amortize `fsync`.
- **Read-only & one-phase optimizations** must be enabled/verified.
- **Presumed abort vs commit:** almost always leave it at presumed abort.
- **Last Resource Commit** to include one non-XA resource — know its risk window.
- **PostgreSQL `max_prepared_transactions`:** default **0** disables XA prepare entirely — a classic "why doesn't my XA transaction work" gotcha.

### 7.6 Interaction with replication and isolation

- 2PC commits the *logical* decision; physical durability still depends on each RM's replication (sync vs async). A "committed" 2PC transaction can still be lost if an RM acknowledged a non-durable (async-replicated) commit and then lost its primary.
- During the prepared window, isolation is preserved by held locks; serializable isolation + 2PC can substantially increase abort/retry rates under contention.

---

## 8. Tradeoffs & decision frameworks

### 8.1 2PC vs 3PC vs Paxos/Consensus Commit

| Property | 2PC | 3PC | Paxos/Raft Commit |
|---|---|---|---|
| Round trips (normal) | 2 | 3 | ~2 (1 with optimizations) |
| Forced log writes | 2N+1 | more (extra phase) | majority-replicated per vote |
| Blocking on coordinator failure | **Yes** (can block) | No (under its model) | **No** (majority survives) |
| Safe under network partition | Yes (blocks, never diverges) | **No** (can diverge → inconsistent) | Yes (majority side proceeds) |
| Failure model assumed | crash-recovery, async OK | **synchronous, no partition** | partial synchrony, partitions OK |
| Latency / overhead | Low | Higher | Moderate (replication) |
| Used in practice | **Yes** (XA, Spanner-as-base) | **Essentially never** | **Yes** (Spanner, Cockroach, TiDB) |
| SPOF | Coordinator | Coordinator | None (quorum) |

### 8.2 Atomic commit (2PC/XA) vs Saga vs Outbox/Eventual

| Dimension | 2PC / XA | Saga | Outbox + idempotent consumer |
|---|---|---|---|
| Consistency | Strong atomicity (ACID) | Eventual, semantic | Eventual |
| Isolation | Yes (locks across prepare) | **No** (intermediate states visible) | No |
| Coupling | Tight (shared TM, XA) | Loose | Loose |
| Availability | Lower (blocking) | High | High |
| Latency | Higher (2 RTT, fsyncs) | Per-step local | Local + async relay |
| Rollback | True undo | **Compensation** (not undo) | N/A (forward recovery) |
| Works with 3rd-party API | No (needs XA) | Yes | Yes |
| Failure complexity | Recovery, heuristics, in-doubt | Compensation logic, partial states | Dedup, at-least-once |
| Best for | Few trusted RMs in a DC needing atomicity | Long-running cross-service business flows | DB-write-then-publish reliably |

### 8.3 Use-when / avoid-when rules

**Use 2PC/XA when:**
- 2–N transactional resources inside one data center must be atomic *and* you cannot consolidate them.
- All resources support real XA (DB, JMS broker, mainframe).
- You can tolerate occasional blocking and have recovery + monitoring.

**Avoid 2PC/XA when:**
- Participants are microservices over the WAN, or any is a non-XA third-party API.
- You need high availability and can accept eventual consistency.
- The contention/lock window would crater throughput.
- → Reach for **saga**, **outbox + CDC**, or a **distributed SQL DB** that hides the commit problem behind consensus.

**Use 3PC:** essentially never in production. If you think you want 3PC, you want **consensus-based commit** instead.

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → diagnosis → fix

| Symptom | Likely cause | How to diagnose | Fix |
|---|---|---|---|
| Queries hang on certain rows | Orphaned **prepared** transaction holding locks | `pg_prepared_xacts` / `DBA_2PC_PENDING` / `XA RECOVER`; check lock waits | Let TM recovery resolve; if TM gone, `ROLLBACK/COMMIT PREPARED` per coordinator log |
| `XA_HEURMIX` / `XA_HEURRB` in logs | RM made a **heuristic** decision; possible split | Search TM + RM logs for `HEUR*`; compare to coordinator decision | Manual reconciliation; data-integrity incident; `forget(Xid)` after resolving |
| Postgres XA "transaction not prepared" / nothing prepares | `max_prepared_transactions = 0` | `SHOW max_prepared_transactions` | Set > 0, restart |
| VACUUM not advancing, wraparound warnings (PG) | Long-lived prepared txn pins xmin | `pg_prepared_xacts` age | Resolve/rollback the orphan |
| Distributed commit blocks after a node restart | In-doubt branch can't reach coordinator | Recovery scan; coordinator reachability | Restart/repair TM; ensure stable `uniqueResourceName` |
| In-doubt branches multiply after deploy | Changed resource name / TM log location | Compare config to logged Xids | Restore stable name + log dir |
| Commit succeeds but data missing later | RM async replication lost a "committed" txn | Replication lag/failover logs | Use synchronous replication for XA RMs |

### 9.2 Concrete tooling

- **PostgreSQL:** `SELECT gid, prepared, owner, database, age(transactionid) FROM pg_prepared_xacts;` then `ROLLBACK PREPARED 'gid';`
- **Oracle:** `SELECT local_tran_id, state, mixed FROM dba_2pc_pending;` then `COMMIT FORCE 'id';` / `ROLLBACK FORCE 'id';`
- **MySQL:** `XA RECOVER;` → `XA ROLLBACK 'xid';`
- **JTA TM (Narayana):** object-store / recovery-manager logs; recovery dump tools; JMX beans exposing in-doubt counts.
- **App side:** alert on `pg_prepared_xacts` count/age, on `HEUR*` log lines, on transaction-timeout-rollback rate.

### 9.3 Real-world failure stories / patterns

- **The orphaned prepared transaction:** a JTA TM (e.g., an old Atomikos/Bitronix or a misconfigured app) crashes or is redeployed with a *different* resource name; prepared branches in Postgres/Oracle are never resolved, hold locks, and (in Postgres) stall VACUUM until autovacuum can't freeze and the cluster approaches **XID wraparound** — an emergency. Fix is to manually `ROLLBACK PREPARED` after confirming the global decision. This is the single most common real 2PC incident.
- **Heuristic mixed in a payment system:** a participant blocked too long, RM heuristic-committed while the coordinator had decided abort → money moved on one side only → manual reconciliation and a postmortem. Demonstrates why heuristics are dangerous.
- **2PC across services over a flaky WAN:** teams that bolted XA across microservices saw cascading blocking and timeouts during minor network blips; migrating to sagas/outbox restored availability. The classic "don't do distributed transactions across service boundaries" lesson.
- **Spanner-style success:** because the coordinator is a Paxos group, a coordinator-leader crash mid-commit just triggers a new leader that finishes the 2PC — no operator action, no blocking. This is the proof that the *protocol* is fine; the *single coordinator* was the problem.

---

## 10. Interview drill

**Q1. Walk me through 2PC step by step, including what gets logged and when.**
Model answer: Phase 1 — coordinator sends PREPARE; each participant does its work, force-writes a `prepared` record, holds locks, votes YES (or force-writes abort and votes NO). Phase 2 — if all YES, coordinator **force-writes the COMMIT decision (the commit point)** then sends GLOBAL-COMMIT; participants force-write commit, release locks, ACK; coordinator writes `end` and forgets. Any NO/timeout → ABORT. Forced writes: participant before voting yes and before ACK; coordinator before sending the decision.
- *Probe: What's the "commit point"?* The coordinator's forced COMMIT log write — after it, the transaction is committed regardless of crashes; recovery re-drives commit.
- *Probe: Why force-write before voting yes?* So a crashed participant remembers its promise and stays in-doubt rather than unilaterally aborting a transaction that may commit.
- *Probe: How many messages/forced writes?* 4N messages, 2 round trips, 2N+1 forced syncs.

**Q2. What is the blocking problem and exactly when does it occur?**
Model answer: A participant in the *prepared* (in-doubt) state has given up the right to abort and must await the coordinator's decision. If the coordinator (and the one participant it told) fail while others are still prepared, survivors cannot distinguish commit-decided from abort-decided and must block — holding locks — until recovery. It's a liveness/availability failure, not a safety failure.
- *Probe: Can the cooperative termination protocol fix it?* It helps if any reachable peer already knows the decision, but if all survivors are prepared and the coordinator is gone, they still block.
- *Probe: What does blocking cost operationally?* Locks held → contention, query hangs, in Postgres VACUUM stalls.

**Q3. How does a participant recover after crashing in the prepared state?**
Model answer: On restart it scans its log, finds a `prepared` record with no decision → in-doubt. It queries the coordinator (`recover`/recovery query). Coordinator answers from its decision log; if it has forgotten T → **presumed abort**. Locks stay held until resolved.
- *Probe: What's presumed abort and why is it the default?* No coordinator record ⇒ abort; it makes the common abort/read-only paths cheaper by avoiding forced writes.
- *Probe: What's a heuristic decision?* The RM resolves an in-doubt branch unilaterally after a long block; risks `XA_HEURMIX` (atomicity violation).

**Q4. Explain 3PC and why nobody uses it.** (senior-signal)
Model answer: 3PC adds a PreCommit phase so that no participant commits before all know the outcome will be commit, making it non-blocking *under a synchronous, no-partition crash model*: a participant in PreCommit can safely commit on timeout, one still in WAIT can safely abort. But under a real **network partition** it can diverge — one side reaches PreCommit and commits, the other aborts — violating atomicity, which is worse than blocking. It also adds latency. The right fix for blocking is consensus-based commit, not 3PC.
- *Probe: Synchronous vs asynchronous network?* Synchronous has bounded delays so timeouts reliably detect failure; async (real internet) doesn't, breaking 3PC's safety.
- *Probe: What replaces it?* Paxos/Raft Commit — replicate the decision across a quorum; non-blocking as long as a majority survives, partition-safe.

**Q5. What are XA and JTA, and how do they relate to 2PC?**
Model answer: XA is the X/Open TM↔RM interface — `xa_prepare`/`xa_commit`/`xa_rollback`/`xa_recover` are literally 2PC. JTA is the Java API (`UserTransaction`, `TransactionManager`, `XAResource`, `Xid`) mapping the X/Open DTP model; an implementation (Narayana, Atomikos) is the transaction manager that drives XA across enlisted resources.
- *Probe: What is an Xid and a branch?* A global transaction id with per-RM branch qualifiers; each RM gets its own branch under one global id.
- *Probe: What does `prepare` returning `XA_RDONLY` enable?* Read-only optimization — that resource is dropped from phase 2.

**Q6. Why do microservices avoid 2PC, and what do they use instead?** (senior-signal)
Model answer: 2PC needs a shared TM, XA-capable resources, tight coupling, and tolerates blocking — all hostile to autonomous services over unreliable WANs, and impossible with non-XA third-party APIs. Microservices instead use **sagas** (local transactions + compensations, eventual consistency) and the **transactional outbox** (commit a DB write + an outbox event in one local transaction; relay via CDC to a broker with at-least-once delivery + idempotent consumers). They trade atomicity/isolation for availability and loose coupling.
- *Probe: Saga vs outbox — when each?* Saga for multi-service business workflows needing semantic rollback; outbox for "update DB then publish event" reliability.
- *Probe: What guarantee does outbox give?* At-least-once delivery; consumers must dedupe → effectively-once processing.

**Q7. Where does 2PC still live, and how do those systems avoid blocking?**
Model answer: XA in enterprise Java (DB+JMS in a DC), MS DTC, mainframes — they accept/manage blocking. Spanner/CockroachDB/TiDB use 2PC *over* Raft/Paxos groups so the coordinator is replicated; a leader crash triggers re-election that finishes the commit → non-blocking. Spanner adds TrueTime for external consistency.
- *Probe: What makes Spanner's 2PC non-blocking?* The coordinator and participants are Paxos groups, not single nodes.
- *Probe: Is the decision still durable if the coordinator leader dies post-decision?* Yes — it's replicated in the Paxos log to a majority.

**Q8. A Postgres cluster's queries are hanging and VACUUM isn't advancing. Walk me through diagnosis.** (senior-signal)
Model answer: Suspect an orphaned prepared (in-doubt) transaction. `SELECT * FROM pg_prepared_xacts;` to list them and their age; check lock waits. Confirm the owning TM is dead/misconfigured (changed resource name/log dir). Determine the global decision from the TM's log if available; then `COMMIT PREPARED`/`ROLLBACK PREPARED` to release. Prevent recurrence: stable `uniqueResourceName`, running recovery manager, transaction timeouts, monitoring on prepared-txn age.
- *Probe: Why does a prepared txn block VACUUM?* It pins the oldest xmin, so dead tuples can't be cleaned → wraparound risk.
- *Probe: How avoid orphans?* Stable resource names, persistent TM log, active recovery, timeouts, alerting.

**Q9. Prove that 2PC cannot be made non-blocking under partitions.** (senior-signal)
Model answer: Cite Skeen's result — no protocol is simultaneously non-blocking and independently recoverable when communication failures (partitions) are possible. Intuition: a partitioned subset of prepared participants cannot tell whether the other side (with the coordinator) committed or aborted; acting unilaterally risks divergence (unsafe) so they must block. Only a quorum/consensus scheme breaks this, by letting the majority side decide while the minority defers.
- *Probe: How does FLP relate?* FLP says async consensus can't guarantee both safety and liveness with one failure; atomic commit is consensus-like, so the same tension applies.
- *Probe: How does Paxos sidestep it?* It chooses liveness only when a majority is reachable, preserving safety always.

**Q10. What is the read-only optimization and the one-phase optimization?**
Model answer: Read-only: a participant that did no writes votes `XA_RDONLY`, releases locks immediately, and is excluded from phase 2 (saves a log force + a round). One-phase: if only one writable participant remains, skip prepare and do a direct local commit (`commit(xid, true)`).
- *Probe: Risk of Last Resource Commit?* It enlists one non-XA resource that commits last; a TM crash between its commit and the XA commits can cause inconsistency.

---

## 11. Glossary

- **ACID:** Atomicity, Consistency, Isolation, Durability — core transaction guarantees.
- **Atomic commitment problem:** getting N participants to unanimously, durably, irreversibly decide commit/abort.
- **Atomicity:** all-or-nothing effect of a transaction.
- **At-least-once / exactly-once:** delivery semantics; exactly-once is usually achieved as at-least-once + idempotent dedup.
- **Blocking:** a participant cannot make progress (decide) and holds locks while waiting; a liveness failure.
- **Branch (transaction branch):** the per-RM portion of a global transaction, identified by a branch qualifier in the Xid.
- **Byzantine fault:** arbitrary/malicious behavior; out of scope for 2PC's crash model.
- **CAP:** under a network Partition you must choose Consistency or Availability; relevant to why 2PC blocks.
- **CDC (Change Data Capture):** reading a DB's log/changes (e.g., Debezium) to relay events; used by the outbox pattern.
- **Cohort / participant:** a resource manager taking part in the transaction.
- **Commit point:** the coordinator's forced commit-decision log write; the instant the transaction is officially committed.
- **Compensation:** a business-level "undo" action in a saga (e.g., refund); not a true rollback.
- **Consensus:** getting a group to agree on a value despite failures (Paxos/Raft).
- **Coordinator / Transaction Manager (TM):** drives the protocol and makes the global decision.
- **Cooperative termination protocol:** blocked participants query peers to try to learn the decision.
- **Distributed transaction:** a transaction spanning multiple resource managers.
- **DTC (MS Distributed Transaction Coordinator):** Microsoft's TM.
- **Durability:** committed effects survive crashes (via forced log writes / fsync).
- **Eventual consistency:** replicas/services converge over time, not instantly.
- **FLP impossibility:** no deterministic async consensus tolerating one crash can guarantee both safety and liveness.
- **Force-write / fsync:** synchronously flushing a log record to durable storage before proceeding.
- **Heuristic decision:** an RM unilaterally resolving an in-doubt branch; risks `XA_HEURMIX`/`HEURHAZ` (atomicity violation).
- **In-doubt / prepared / ready:** a participant has voted yes, holds locks, awaits the decision.
- **Idempotency:** applying an operation more than once has the same effect as once.
- **Isolation:** concurrent transactions don't see each other's incomplete work.
- **JTA / JTS:** Jakarta Transaction API (the Java API) / Java Transaction Service (CORBA-based interop).
- **Last Resource Commit (LRCO):** including one non-XA resource by committing it last among prepared XA resources.
- **Leader election:** consensus mechanism to pick the proposer/coordinator.
- **MVCC (Multi-Version Concurrency Control):** isolation via versioned rows; interacts with held versions during prepare.
- **Network partition:** the network splits into groups that cannot communicate.
- **One-phase optimization (1PC):** skip prepare when a single writable resource remains.
- **Outbox pattern:** write data + an event to one local DB transaction; relay the event asynchronously.
- **Paxos / Raft:** consensus algorithms providing fault-tolerant agreement via majority quorums.
- **Paxos Commit:** replacing 2PC's single coordinator with a consensus group → non-blocking commit.
- **Presumed abort / presumed commit:** conventions for the outcome of a forgotten transaction (PA is the XA default).
- **Prepare / vote phase:** Phase 1 of 2PC.
- **Quorum / majority:** the minimum set (e.g., (N/2)+1) needed for consensus progress.
- **Read-only optimization:** a no-write participant votes `XA_RDONLY` and is dropped from phase 2.
- **Resource Manager (RM):** owns durable state and commits/rolls back its local branch (DB, broker).
- **Saga:** a sequence of local transactions with compensating actions; eventual consistency.
- **Skeen's theorem:** no protocol is both non-blocking and independently recoverable under partitions.
- **Spanner / CockroachDB / TiDB:** distributed SQL DBs that run 2PC over consensus groups.
- **Synchronous vs asynchronous network:** bounded vs unbounded message delays; determines if timeouts reliably detect failure.
- **TrueTime:** Spanner's clock with bounded uncertainty (GPS + atomic clocks) for external consistency.
- **WAL (Write-Ahead Log):** append-only log written before data changes; basis of local atomicity/durability.
- **X/Open DTP:** the AP/TM/RM reference model with XA and TX interfaces.
- **XA:** the X/Open TM↔RM interface; the standard 2PC implementation.
- **Xid:** global transaction identifier (format id + global txn id + branch qualifier).

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **2PC = atomic commit across N resources.** Phase 1 PREPARE/vote → Phase 2 COMMIT/ABORT.
- **Force-writes:** participant before voting YES and before ACK; coordinator before the decision (= **commit point**).
- **Costs:** 4N messages, 2 round trips, **2N+1 fsyncs**; locks held from prepare to decision.
- **Prepared/in-doubt state:** voted yes, holds locks, gave up the right to abort → source of correctness *and* blocking.
- **Blocking:** coordinator (+ the one informed participant) crash while others are prepared → survivors block, holding locks. Safety preserved, liveness lost.
- **Recovery:** participant scans log; prepared-with-no-decision → query coordinator; no record → **presumed abort** (XA default).
- **Heuristics:** unilateral resolution after long block; `XA_HEURMIX` = atomicity broken = incident.
- **3PC:** adds PreCommit → non-blocking *only* if synchronous + no partitions; **diverges under partition** → effectively unused.
- **Modern fix:** **Paxos/Raft Commit** — replicate the decision across a quorum; non-blocking + partition-safe. Spanner = 2PC over Paxos groups.
- **Java:** **XA = 2PC wire/native** (`xa_prepare/commit/rollback/recover`); **JTA = the API** (`UserTransaction`, `TransactionManager`, `XAResource`, `Xid`); TMs = **Narayana, Atomikos, Bitronix**.
- **Optimizations:** read-only (`XA_RDONLY`), one-phase (single resource), presumed abort, group commit, LRCO (careful).
- **Microservices:** avoid 2PC → **saga** (compensation) or **outbox + CDC** (at-least-once + idempotent).
- **Ops gotchas:** Postgres `max_prepared_transactions` default **0**; orphaned prepared txns hold locks & stall VACUUM → wraparound risk; keep a **stable `uniqueResourceName`** + running recovery manager + transaction timeouts + monitoring on in-doubt count/age and `HEUR*`.
- **Decision rule:** few trusted XA resources in a DC needing atomicity → 2PC/XA; cross-service/WAN/3rd-party or need HA → saga/outbox; want non-blocking strong consistency at scale → distributed SQL DB (2PC over consensus).

### Self-test (no answers — for active recall)

1. Trace 2PC's forced log writes in order and explain why each is mandatory; identify the exact commit point.
2. Construct a failure interleaving that forces 2PC to block, and explain why no timeout action by the survivors is safe.
3. Explain precisely how 3PC achieves non-blocking under its model and exactly how a network partition breaks it (give the divergent interleaving).
4. Map each XA call (`xa_start/end/prepare/commit/rollback/recover/forget`) to its place in the 2PC protocol and to a JTA `XAResource` method.
5. You find 50 rows of in Postgres unqueryable and `pg_prepared_xacts` shows aging entries from a redeployed app — diagnose and remediate step by step, then list four preventions.
6. Compare 2PC, saga, and outbox across consistency, isolation, availability, and rollback semantics, and state which you'd pick for (a) bank DB + JMS in one DC, (b) order across three microservices, (c) "save order then publish event."
7. Explain how Spanner uses both 2PC and Paxos and why that combination is non-blocking; contrast with classic single-coordinator XA.

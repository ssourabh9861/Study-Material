# Isolation Levels & MVCC

> A definitive engineering-handbook chapter for senior Java/JVM backend developers. From first principles to deep internals: the four SQL isolation levels and the anomalies they prevent, two-phase locking vs. MVCC, version chains and snapshots, vacuum/GC, snapshot isolation vs. serializable (SSI), Postgres vs. MySQL InnoDB, choosing a level, and `SELECT ... FOR UPDATE` with optimistic vs. pessimistic locking.

---

## 1. Overview & where it fits

### What this is

When two or more database transactions run at the same time and touch the same data, the database has to decide **how much they are allowed to see of each other's in-progress work**. That decision is governed by the transaction's **isolation level**. Isolation is the **"I" in ACID** — the four properties a transactional database promises:

- **Atomicity** — a transaction is all-or-nothing; either every change commits or none does.
- **Consistency** — a transaction moves the database from one valid state to another, respecting constraints (uniqueness, foreign keys, checks).
- **Isolation** — concurrent transactions do not corrupt each other; the *result* should be as if they ran in some serial order (at the strongest level).
- **Durability** — once committed, the data survives crashes (it's on stable storage / in the write-ahead log).

**Isolation** is the property that is most often *weakened on purpose*. Perfect isolation (every transaction behaves as if it ran completely alone, one after another) is called **serializability**, and it is expensive. So databases offer a **menu of weaker isolation levels** that trade away some correctness guarantees for more concurrency and throughput. Understanding that menu — what each level actually permits, and how the engine enforces it — is the subject of this chapter.

**MVCC (Multi-Version Concurrency Control)** is the dominant *mechanism* modern databases use to provide isolation efficiently. Instead of making readers wait for writers (and vice versa) by taking locks on rows, MVCC keeps **multiple versions of each row** and shows each transaction a **consistent snapshot** of the data as of a point in time. The slogan: **"readers don't block writers, and writers don't block readers."**

### The problem it solves

Imagine a naive database that just lets all transactions read and write a shared set of rows with no coordination. You immediately get anomalies:

- A transaction reads a value another transaction wrote but hasn't committed — then that other transaction rolls back. The first transaction acted on data that **never existed** (a **dirty read**).
- Two transactions read an account balance of \$100, each adds \$50, each writes \$150. One update is silently destroyed; the balance should be \$200 (a **lost update**).
- A report sums up a table while another transaction is moving money between rows, and the report double-counts or misses money (an **inconsistent read / read skew**).

Isolation levels are the **contract** that tells you exactly which of these anomalies can and cannot happen. MVCC and locking are the **implementation** that enforces the contract.

### When you reach for it

You don't "reach for" isolation — every transaction has one (your driver/pool sets a default). But you make **deliberate isolation decisions** when:

- You have **money, inventory, or counters** that must not be lost or double-spent.
- You run **long analytical reads** that must see a consistent snapshot while OLTP writes continue.
- You hit **deadlocks, serialization failures, or lock-wait timeouts** in production and need to understand why.
- You're choosing between **optimistic** (version-column, retry on conflict) and **pessimistic** (`SELECT ... FOR UPDATE`) concurrency control for a hot row.
- You're picking or tuning a database and need to know what `READ COMMITTED` vs. `REPEATABLE READ` vs. `SERIALIZABLE` actually means **on that engine** (the names are standardized; the behavior is not).

### One-paragraph mental model

> Think of the database as a shared whiteboard that many people write on at once. **Isolation level** is the rule about what each person is allowed to see of others' half-finished writing. **Locking** enforces it by making people wait their turn at the marker. **MVCC** enforces it instead by photographing the whiteboard the moment you start (your **snapshot**), letting everyone else keep writing on the real board while you read your photo — and keeping old photos around (the **version chain**) until no one needs them, at which point a janitor (**vacuum / GC**) erases the stale ones. The strongest rule, **serializable**, additionally guarantees that the final board looks as if everyone had taken turns one at a time, even though they didn't.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Every term you'll need later is defined here on first use.

### 2.1 Transaction

A **transaction** is a unit of work bracketed by `BEGIN` and `COMMIT` (or `ROLLBACK`). Everything inside it is treated as a single logical operation for the purposes of atomicity and isolation. In SQL:

```sql
BEGIN;                       -- start a transaction
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;                      -- make it permanent and visible to others
```

In JDBC (Java), a transaction is delimited by turning off **auto-commit**:

```java
conn.setAutoCommit(false);   // begin: subsequent statements are one transaction
// ... statements ...
conn.commit();               // or conn.rollback();
```

> **Auto-commit** (newcomer note): by default JDBC connections commit after *every single statement* — each statement is its own one-statement transaction. To group statements you must explicitly disable auto-commit. Forgetting this is the #1 cause of "my rollback did nothing."

### 2.2 Concurrency and the need for isolation

**Concurrency** means multiple transactions are *in flight* (started, not yet finished) at overlapping times. They may be physically parallel (different CPU cores) or interleaved by the scheduler. Either way, their individual reads and writes can interleave in many orders. Isolation defines which interleavings are *allowed to be observable*.

A **schedule** is a particular interleaving of the operations of several transactions. A schedule is **serializable** if its outcome is identical to *some* serial schedule (T1 fully then T2, or T2 fully then T1). Serializability is the gold standard of correctness: if every transaction is individually correct, and they execute serializably, the whole system is correct.

### 2.3 The read/write anomalies (the "phenomena")

The SQL standard (SQL-92 and later) defines isolation levels by which **phenomena** (anomalies) they *prevent*. You must know these cold.

**a) Dirty write** — T1 modifies a row, then T2 modifies the same row *before T1 commits or rolls back*. Now if T1 rolls back, T2's write may be lost or partially applied. **Every real isolation level prevents dirty writes** (they are never allowed), so the standard doesn't even list this among the optional phenomena — but it matters conceptually because preventing it requires write locks.

**b) Dirty read** — T1 writes a row but hasn't committed; T2 *reads* that uncommitted value. If T1 then rolls back, T2 saw data that never officially existed. Example:

```
T1: UPDATE accounts SET balance = 0 WHERE id = 1;   -- not committed
T2: SELECT balance FROM accounts WHERE id = 1;       -- reads 0  (DIRTY)
T1: ROLLBACK;                                         -- balance was never 0
```

**c) Non-repeatable read** (a.k.a. **read skew** in the narrow sense, or **fuzzy read**) — T1 reads a row, T2 modifies *and commits* that row, T1 reads it again **within the same transaction** and gets a different value. The same query returned two different answers.

```
T1: SELECT balance FROM accounts WHERE id = 1;   -- reads 100
T2: UPDATE accounts SET balance = 200 WHERE id = 1; COMMIT;
T1: SELECT balance FROM accounts WHERE id = 1;   -- reads 200  (NON-REPEATABLE)
```

**d) Phantom read** — T1 runs a query with a search condition (e.g. `WHERE age > 30`) and gets a set of rows. T2 *inserts or deletes* a row that matches the condition and commits. T1 re-runs the same query and the **set of rows changed** — a "phantom" row appeared or vanished. The difference from non-repeatable read: non-repeatable read is about an *existing row changing value*; phantom is about the *membership of a result set changing* due to inserts/deletes.

```
T1: SELECT count(*) FROM users WHERE age > 30;   -- 10 rows
T2: INSERT INTO users(age) VALUES (40); COMMIT;
T1: SELECT count(*) FROM users WHERE age > 30;   -- 11 rows  (PHANTOM)
```

**e) Lost update** — Two transactions read the same row, both compute a new value based on what they read, both write. The second write overwrites the first without incorporating it. The classic read-modify-write race.

```
T1: SELECT balance FROM accounts WHERE id=1;     -- reads 100
T2: SELECT balance FROM accounts WHERE id=1;     -- reads 100
T1: UPDATE accounts SET balance = 100+50;  COMMIT;  -- 150
T2: UPDATE accounts SET balance = 100+50;  COMMIT;  -- 150  (T1's +50 LOST; should be 200)
```

Lost update is **not in the original SQL-92 phenomena list** but is central in practice and is explicitly addressed by snapshot-isolation literature.

**f) Write skew** — The subtle one, and the canonical reason snapshot isolation is *not* serializable. Two transactions read an **overlapping set** of rows, each makes a decision based on that read, then each writes to a **different** row. Neither sees the other's write (they're on separate snapshots), and a constraint that holds for each transaction individually is violated globally.

The textbook example: a hospital requires **at least one doctor on call**. Two doctors, Alice and Bob, are both on call. Each independently decides to go off call. Each transaction checks "is there still at least one *other* doctor on call?" — at the moment of the read, yes (the other one). So each commits its own change. Result: **zero doctors on call**, violating the invariant, even though each transaction individually preserved it.

```
-- invariant: count(on_call = true) >= 1
T1 (Alice): SELECT count(*) FROM doctors WHERE on_call;  -- sees 2, OK to leave
T2 (Bob):   SELECT count(*) FROM doctors WHERE on_call;  -- sees 2, OK to leave
T1: UPDATE doctors SET on_call=false WHERE name='Alice'; COMMIT;
T2: UPDATE doctors SET on_call=false WHERE name='Bob';   COMMIT;
-- now 0 on call -> invariant violated (WRITE SKEW)
```

Write skew is allowed under **snapshot isolation** but prevented under **serializable**.

**g) Read-only transaction anomaly** — An esoteric case (Fekete et al.) where even a *read-only* transaction can observe a state inconsistent with any serial order under snapshot isolation. Rare, but it's why SI ≠ serializable even for readers.

### 2.4 The four standard SQL isolation levels

The SQL standard defines four levels, each *permitting* a shrinking set of phenomena. The standard defines them by what they **forbid**, leaving the implementation free.

| Level | Dirty read | Non-repeatable read | Phantom read |
|---|---|---|---|
| **READ UNCOMMITTED** | Possible | Possible | Possible |
| **READ COMMITTED** | Prevented | Possible | Possible |
| **REPEATABLE READ** | Prevented | Prevented | Possible (per standard) |
| **SERIALIZABLE** | Prevented | Prevented | Prevented |

Crucial caveats you must internalize:

1. The standard specifies the **minimum** guarantee. An engine may give you *more* than the level requires. (Postgres `READ UNCOMMITTED` actually behaves like `READ COMMITTED` — it never does dirty reads.)
2. The standard says nothing about **lost update** or **write skew**. Those depend on the engine and on whether the level is implemented with locking or MVCC.
3. **The same level name means different things on different engines.** This is the single most important practical fact in this chapter. Postgres `REPEATABLE READ` = snapshot isolation (no phantoms!). MySQL InnoDB `REPEATABLE READ` = snapshot reads + next-key locks for some statements (mostly no phantoms for locking reads, possible for some plain reads). Always test on *your* engine.

### 2.5 Two ways to enforce isolation

There are two families of mechanisms. Modern engines mix them.

**Pessimistic / lock-based concurrency control.** Assume conflicts will happen; prevent them by taking **locks**. A **lock** is a marker on a data item that grants exclusive or shared access. The canonical protocol is **Two-Phase Locking (2PL)** (§3.6). Readers and writers block each other. Strong correctness, lower concurrency.

**Optimistic / multi-version concurrency control (MVCC).** Assume conflicts are rare; let everyone proceed on snapshots, and detect conflicts at commit (for the strongest level) or simply tolerate weaker isolation. Readers and writers don't block. Higher concurrency; some anomalies possible at weaker levels; serializable MVCC needs a validation phase.

> **Optimistic vs. pessimistic** at the *application* level (§5.6) is a related but distinct choice: do you guard a row with `SELECT ... FOR UPDATE` (pessimistic) or with a version column + retry (optimistic)? Don't confuse the engine's internal mechanism with your app's strategy — though they rhyme.

---

## 3. How it works internally

This is the heart of the chapter. We go deep on MVCC, then on locking, then on how isolation levels are actually realized on top of them.

### 3.1 The core idea of MVCC

Under MVCC, **a row is not a single mutable cell. It is a chain of immutable versions.** Each version is stamped with metadata saying *when* it became valid and *when* it stopped being valid, expressed in terms of the transactions that created and deleted it.

- An **UPDATE** does not overwrite a row in place (logically). It creates a **new version** and marks the old version as expired.
- A **DELETE** marks the current version as expired (no new version).
- An **INSERT** creates a first version with no expiry.

Every transaction has a **snapshot**: a definition of "which versions are visible to me." A version is visible if it was committed by a transaction that, from this snapshot's point of view, had already finished, and it has not been superseded by a newer committed version visible to this snapshot. Reading is then *snapshot reading*: walk the version chain and pick the version your snapshot can see. No locks needed for reads.

### 3.2 PostgreSQL MVCC internals (the canonical, most transparent design)

Postgres is the best teaching example because its MVCC is exposed directly in the table heap.

**Transaction IDs (XIDs).** Every transaction gets a monotonically increasing 32-bit **transaction ID** (`xid`). (More precisely, a transaction that writes gets a real XID; read-only transactions may use a "virtual" XID to avoid burning the counter.) XIDs are the timestamps of MVCC.

**Per-row hidden columns.** Every row version (Postgres calls a row version a **tuple**) carries hidden system columns:

| System column | Meaning |
|---|---|
| `xmin` | XID of the transaction that **created** (inserted/updated-into) this tuple. |
| `xmax` | XID of the transaction that **deleted or updated-away** this tuple (0 if still live). |
| `cmin` / `cmax` | Command IDs within a transaction (so a transaction sees its own earlier statements correctly). |
| `ctid` | Physical location `(page, offset)`; an updated tuple's old version points forward to the new version via the **t_ctid** field, forming the **version chain (HOT chain)**. |
| `t_infomask` bits | Hint bits: `HEAP_XMIN_COMMITTED`, `HEAP_XMAX_COMMITTED`, etc. — caches of commit status to avoid re-checking the commit log. |

You can see these:

```sql
SELECT xmin, xmax, cmin, cmax, ctid, * FROM accounts WHERE id = 1;
```

**The commit log (CLOG / pg_xact).** Postgres keeps a separate structure, the **CLOG** (a.k.a. `pg_xact`), mapping each XID → status: `IN_PROGRESS`, `COMMITTED`, `ABORTED`, or `SUB_COMMITTED`. To decide if a tuple's creator/deleter "counts," the engine checks the CLOG (cached via the hint bits above).

**The snapshot.** When a statement (READ COMMITTED) or transaction (REPEATABLE READ) takes a snapshot, Postgres records:

- `xmin` of the snapshot — the lowest XID still active; everything below is definitely finished.
- `xmax` of the snapshot — one past the highest assigned XID; everything ≥ this hasn't started from our view.
- `xip_list` — the list of XIDs that were **in progress** when the snapshot was taken.

The internal representation is `SnapshotData`. You can inspect the textual form:

```sql
SELECT txid_current();          -- my transaction id (64-bit, epoch-extended)
SELECT pg_current_snapshot();   -- e.g. 100:104:100,102  -> xmin:xmax:xip_list
```

**Visibility rule (HeapTupleSatisfiesMVCC), step by step.** Given a tuple and a snapshot, is the tuple visible?

1. Look at `xmin` (creator):
   - If `xmin` is **my own** transaction and the command that created it ran before the current command → visible (subject to step 2). (This is how you see your own writes.)
   - If `xmin` is **in progress** per the snapshot's `xip_list`, or `xmin >= snapshot.xmax` (started after my snapshot) → **not yet committed from my view → tuple invisible.**
   - If `xmin` is **committed and below my snapshot** → creator counts; proceed.
   - If `xmin` is **aborted** → tuple never existed → invisible.
2. Look at `xmax` (deleter):
   - If `xmax` is 0 / invalid → not deleted → **visible.**
   - If `xmax` is in progress (and not mine), or aborted, or ≥ my snapshot's xmax → the deletion doesn't count from my view → **visible.**
   - If `xmax` is committed and below my snapshot → the row was deleted before my snapshot → **invisible.**

This rule, applied per tuple, gives each transaction a consistent view *without locking the rows it reads*. The same physical tuple can be visible to one transaction and invisible to another simultaneously — that's the whole trick.

**Update as delete+insert.** `UPDATE accounts SET balance=200 WHERE id=1` does:
1. Find the live tuple v1 (`xmin=50, xmax=0`).
2. Set v1's `xmax = my_xid` (mark it expired by me).
3. Insert a new tuple v2 (`xmin=my_xid, xmax=0`) with the new value.
4. Link v1.t_ctid → v2's location (the version chain).
5. On commit, both become permanent; on rollback, v1's xmax is ignored (because my XID aborted) and v2 is dead.

Concurrent readers with an older snapshot keep seeing v1; readers with a newer snapshot see v2. **Bloat** is the cost: the old version sticks around physically until vacuum reclaims it.

**HOT (Heap-Only Tuples).** Optimization: if an UPDATE doesn't change any indexed column and the new version fits on the same page, Postgres creates a **HOT chain** entirely within the heap page and does *not* add new index entries — the index points at the chain head and the engine follows `t_ctid` to the live version. This drastically cuts index bloat. It's why "update only what you must" and "leave fillfactor headroom" matter for write-heavy tables.

### 3.3 PostgreSQL: VACUUM and the garbage-collection lifecycle

Because old versions are never overwritten in place, they accumulate as **dead tuples** (also called **bloat**). Reclaiming them is **VACUUM**.

**What VACUUM does:**
- Scans tables, finds tuples that are **dead to all transactions** (their `xmax` is committed and below the oldest snapshot any backend could need — the global **xmin horizon**).
- Marks that space as reusable (plain `VACUUM`) — it does **not** return space to the OS, just makes it available for future inserts/updates in the same table.
- Updates the **free space map (FSM)** and **visibility map (VM)**.
- Removes dead index entries pointing at reclaimed tuples.
- Freezes old XIDs (see anti-wraparound below).

**`VACUUM FULL`** rewrites the entire table compactly into a new file and *does* return space to the OS — but it takes an **ACCESS EXCLUSIVE lock** (blocks everything) and needs disk for a full copy. Use sparingly; prefer tools like `pg_repack` for online compaction.

**Autovacuum.** A background daemon (`autovacuum` launcher + workers) that triggers VACUUM and ANALYZE automatically based on the fraction of changed rows. Key knobs (defaults as of PG 13–16):

| Parameter | Default | Meaning |
|---|---|---|
| `autovacuum` | `on` | Master switch. |
| `autovacuum_vacuum_threshold` | 50 | Base number of dead tuples before vacuuming. |
| `autovacuum_vacuum_scale_factor` | 0.2 | Plus 20% of the table's row count. (Threshold = base + scale × n_rows.) |
| `autovacuum_vacuum_insert_scale_factor` | 0.2 | (PG 13+) trigger vacuum after inserts too, to set visibility map. |
| `autovacuum_max_workers` | 3 | Concurrent autovacuum workers. |
| `autovacuum_vacuum_cost_limit` | 200 (`-1` → uses `vacuum_cost_limit`) | I/O throttling budget. |
| `autovacuum_naptime` | 60s | How often the launcher checks. |

For a 100M-row table, `0.2` scale factor means autovacuum waits for ~20M dead tuples — far too lax. Production practice is to **lower the scale factor** (e.g. `0.02` or set per-table `autovacuum_vacuum_threshold` to a fixed large number) on big, churny tables.

**Transaction ID wraparound — the existential MVCC hazard.** XIDs are 32-bit (~4.2 billion values) and **wrap around**. Postgres compares XIDs *modulo 2³¹*, so "old" is whatever is more than ~2 billion XIDs in the past. If a tuple's `xmin` is never **frozen** (rewritten to the special `FrozenTransactionId`, which is always considered "in the infinite past / committed"), then after wraparound that ancient row could suddenly look like it's *in the future* and become invisible — **silent data loss**.

To prevent this, VACUUM **freezes** old tuples. Relevant knobs:

| Parameter | Default | Meaning |
|---|---|---|
| `vacuum_freeze_min_age` | 50,000,000 | XIDs old before a tuple is frozen during a normal vacuum. |
| `autovacuum_freeze_max_age` | 200,000,000 | When a table's oldest XID is this old, **anti-wraparound autovacuum** is forced, even if `autovacuum = off`. |
| `vacuum_failsafe_age` | 1,600,000,000 | Emergency: skip cost-based delays to vacuum fast. |

If anti-wraparound vacuum can't keep up (often because a long-running transaction or stuck replication slot holds the xmin horizon back), Postgres warns, then **refuses new writes** at ~3M XIDs remaining to avoid corruption. Monitor with:

```sql
SELECT datname, age(datfrozenxid) FROM pg_database ORDER BY 2 DESC;
-- age approaching 2,000,000,000 is an emergency.
```

This is one of the most famous Postgres production incidents (Sentry, Mailchimp, and others have publicly hit XID wraparound emergencies).

### 3.4 MySQL InnoDB MVCC internals

InnoDB's MVCC is conceptually similar but architecturally different: **old versions live in the undo log, not in the table.**

**Hidden columns per row:**

| Hidden column | Meaning |
|---|---|
| `DB_TRX_ID` (6 bytes) | Last transaction that inserted/updated the row. |
| `DB_ROLL_PTR` (7 bytes) | **Roll pointer** to the undo log record needed to reconstruct the *previous* version. |
| `DB_ROW_ID` (6 bytes) | Internal row id (only if no user-defined primary key). |

**Undo logs and the version chain.** The clustered-index row holds the *latest* version. When you UPDATE, InnoDB writes the **before-image** to an **undo log record** and points `DB_ROLL_PTR` at it. To read an older version, the engine follows the roll pointer chain, applying undo records to "rewind" the row to the state a given snapshot needs. There are two undo segments: **insert undo** (discarded after commit) and **update undo** (kept until no MVCC snapshot needs it — purged later).

**Read view (the snapshot).** InnoDB's snapshot is a **Read View** containing:
- `m_low_limit_id` — XIDs ≥ this are not visible (started after the view).
- `m_up_limit_id` — XIDs < this are all visible (committed before the view).
- `m_ids` — the set of transaction IDs **active** when the view was created.
- `m_creator_trx_id` — the viewing transaction's own ID.

Visibility check for a row's `DB_TRX_ID`: if it's the creator → visible; if `< m_up_limit_id` → visible; if `>= m_low_limit_id` → not visible (follow roll pointer to older version); if in `m_ids` → not visible (active when view created, follow roll pointer); else visible. This is the same idea as Postgres, just undo-driven.

**Purge.** InnoDB's analog of VACUUM is the **purge thread(s)** (`innodb_purge_threads`, default 4). They physically remove update-undo log records and delete-marked rows once **no active Read View** can need them. The amount of history that must be retained is the **history list length** — visible via:

```sql
SHOW ENGINE INNODB STATUS\G   -- look for "History list length"
SELECT name, count FROM information_schema.innodb_metrics
  WHERE name = 'trx_rseg_history_len';
```

A growing history list length means a long-running transaction is **pinning** old versions (the equivalent of Postgres's xmin horizon being held back), bloating the undo tablespace and slowing reads (longer version chains to walk). This is a classic InnoDB production smell. Relevant knobs: `innodb_purge_threads`, `innodb_max_purge_lag`, `innodb_max_purge_lag_delay`, and `innodb_undo_log_truncate` (online truncation of undo tablespaces).

**Key difference from Postgres:** because old versions live in the undo log and the latest version lives in-place in the clustered index, InnoDB tables don't bloat the same way Postgres tables do — but a stuck purge bloats the **undo tablespace** and lengthens version chains, which is a different but equally serious failure mode. Also, InnoDB has **no XID wraparound problem** of the Postgres kind (its transaction IDs are large and managed differently).

### 3.5 How each isolation level is realized on MVCC

The **timing of when the snapshot is taken** is the lever that produces the different levels.

- **READ UNCOMMITTED**: read the latest version regardless of commit status (dirty reads). Postgres doesn't actually implement this (it maps to READ COMMITTED). InnoDB does, by reading without a Read View.
- **READ COMMITTED**: **take a fresh snapshot at the start of each statement.** So every statement sees everything committed *up to that statement's start*. Non-repeatable reads and phantoms are possible because successive statements use different snapshots. This is the default in Postgres and Oracle.
- **REPEATABLE READ / SNAPSHOT ISOLATION**: **take one snapshot at the first read of the transaction** and reuse it for the whole transaction. Every read sees the same consistent point-in-time. Non-repeatable reads and (in Postgres) phantoms vanish. This is the default in MySQL InnoDB.
- **SERIALIZABLE**: snapshot isolation **plus a conflict-detection mechanism** (SSI in Postgres) or **range locking** (InnoDB) so that the result is equivalent to a serial schedule (prevents write skew and the read-only anomaly).

### 3.6 Two-Phase Locking (2PL) — the lock-based alternative

Even MVCC engines use locks for *writes* and for serializable in some engines, so you must understand 2PL.

**Lock modes:** **Shared (S)** locks for reads, **Exclusive (X)** locks for writes. S/S compatible; anything-with-X conflicts.

**The 2PL protocol** has two phases:
1. **Growing phase** — the transaction acquires locks (never releases).
2. **Shrinking phase** — the transaction releases locks (never acquires).

The boundary is the moment of the first unlock. 2PL guarantees **conflict-serializability**. **Strict 2PL (S2PL)** — the practical variant — holds *all* locks until commit/abort, which also guarantees recoverability (no cascading aborts). Almost every lock-based engine uses strict 2PL.

**Predicate / range locks and next-key locks.** Plain row locks can't prevent **phantoms** (you can't lock a row that doesn't exist yet). To prevent phantoms under locking, you need to lock the *predicate* (the gap where matching rows could appear). InnoDB implements this with **next-key locks**: a combination of a record lock on an index row plus a **gap lock** on the gap before it. This is why InnoDB `REPEATABLE READ` prevents phantoms for locking reads — it gap-locks the range so no one can insert into it.

> **Gap lock** (newcomer note): a lock on the *interval between two index values* (not on any actual row), preventing inserts into that interval. **Record lock**: a lock on an actual index record. **Next-key lock** = record lock + the gap before it. Gap locks exist only in `REPEATABLE READ` and `SERIALIZABLE`; `READ COMMITTED` disables most of them, which is why RC has fewer deadlocks but allows phantoms.

**Deadlocks.** Two transactions each hold a lock the other wants → cyclic wait. Engines run a **deadlock detector** (a wait-for graph cycle check) and abort one victim (the cheaper-to-rollback one). You must be prepared to **retry** the aborted transaction. Lock-wait timeouts (`innodb_lock_wait_timeout`, default 50s) are the fallback when detection is off or for cross-engine waits.

### 3.7 Serializable via MVCC: SSI (Serializable Snapshot Isolation)

Postgres `SERIALIZABLE` (since 9.1) uses **SSI**, an optimistic technique (Cahill/Fekete/Röhm) that keeps MVCC's "readers don't block writers" performance while guaranteeing true serializability.

**The insight:** every non-serializable execution under snapshot isolation contains a structure called a **dangerous structure** — two consecutive **rw-dependency** (read-write conflict) edges forming a specific pattern in the serialization graph, with a pivot transaction. SSI doesn't lock; it **tracks read/write dependencies** at runtime using lightweight **SIReadLocks** (predicate locks that don't block, they just record "T read this range"). When it detects a dangerous structure that could yield a non-serializable outcome, it **aborts one of the transactions** with a serialization failure (`could not serialize access due to read/write dependencies`, SQLSTATE **40001**).

**Consequences you must design for:**
- SSI never blocks reads or causes deadlocks for serialization, but it **produces serialization failures** that the application **must catch and retry**.
- SSI uses memory to track predicate locks (`max_pred_locks_per_transaction`, `max_pred_locks_per_relation`, `max_pred_locks_per_page`). Under memory pressure it **escalates** to coarser-grained (page/relation) predicate locks, causing **more false-positive aborts**.
- It's the cleanest way to get correctness without rewriting your concurrency logic: write straightforward transactions, set `SERIALIZABLE`, and add a retry loop.

**InnoDB `SERIALIZABLE`** does *not* use SSI. It implements serializable the lock-based way: it implicitly converts plain `SELECT` into `SELECT ... LOCK IN SHARE MODE` (i.e., takes shared next-key locks on everything it reads). This blocks writers and can deadlock, but it prevents write skew by locking the predicate. Different mechanism, same goal.

---

## 4. The complete toolkit

### 4.1 SQL statements & clauses

| Statement / clause | Purpose | Notes / key params |
|---|---|---|
| `SET TRANSACTION ISOLATION LEVEL <level>` | Set isolation for the *next* transaction (or current, per dialect). | Levels: `READ UNCOMMITTED`, `READ COMMITTED`, `REPEATABLE READ`, `SERIALIZABLE`. |
| `BEGIN [TRANSACTION] [ISOLATION LEVEL ...]` | Start a transaction, optionally with level. | Postgres: `BEGIN ISOLATION LEVEL SERIALIZABLE;` |
| `SET TRANSACTION ... READ ONLY` | Mark a transaction read-only. | Lets the engine optimize (e.g., Postgres deferrable serializable read-only txns can avoid aborts). |
| `SELECT ... FOR UPDATE` | Pessimistic exclusive row lock for the read rows. | Blocks other writers/FOR UPDATE readers. Add `NOWAIT` (fail immediately if locked) or `SKIP LOCKED` (skip locked rows — great for queues). |
| `SELECT ... FOR NO KEY UPDATE` | (PG) Weaker than FOR UPDATE; allows FK references. | Used by FK machinery. |
| `SELECT ... FOR SHARE` (PG) / `LOCK IN SHARE MODE` (MySQL 5.x) / `FOR SHARE` (MySQL 8) | Shared lock; others can read but not write the rows. | Prevents the rows from being changed under you. |
| `SAVEPOINT name` / `ROLLBACK TO SAVEPOINT name` | Partial rollback within a transaction. | Useful for retry-inner-operation patterns. |
| `SET TRANSACTION ... DEFERRABLE` (PG) | For `SERIALIZABLE READ ONLY DEFERRABLE` txns. | Waits for a safe snapshot, then never aborts with 40001. Great for long reports. |

### 4.2 Per-engine defaults & level mapping

| Engine | Default level | RU behavior | RR behavior | SERIALIZABLE mechanism |
|---|---|---|---|---|
| **PostgreSQL** | READ COMMITTED | Mapped to READ COMMITTED (no dirty reads ever) | Snapshot isolation (no phantoms; allows write skew) | **SSI** (optimistic, 40001 retries) |
| **MySQL InnoDB** | REPEATABLE READ | True dirty reads possible | Snapshot reads + next-key locks (no phantoms for locking reads) | Lock-based (implicit shared next-key locks on reads) |
| **Oracle** | READ COMMITTED | Not supported (no dirty reads) | Not supported (use SERIALIZABLE) | Snapshot-based "SERIALIZABLE" (actually SI; can throw ORA-08177) |
| **SQL Server** | READ COMMITTED (lock-based by default) | Supported | Lock-based RR | Lock-based; optional `SNAPSHOT` isolation via row-versioning (`READ_COMMITTED_SNAPSHOT`) |

> Note Oracle's "SERIALIZABLE" is really snapshot isolation, so it permits write skew despite the name — another reason names mislead.

### 4.3 JDBC API surface (Java)

| API | Purpose |
|---|---|
| `Connection.setTransactionIsolation(int)` | Set level. Constants: `TRANSACTION_READ_UNCOMMITTED` (1), `TRANSACTION_READ_COMMITTED` (2), `TRANSACTION_REPEATABLE_READ` (4), `TRANSACTION_SERIALIZABLE` (8), `TRANSACTION_NONE` (0). |
| `Connection.getTransactionIsolation()` | Read current level. |
| `Connection.setAutoCommit(false)` | Begin a multi-statement transaction. |
| `Connection.commit()` / `rollback()` | End it. |
| `Connection.setSavepoint(name)` / `rollback(Savepoint)` | Savepoints. |
| `SQLException.getSQLState()` | Read SQLSTATE — `40001` (serialization failure) and `40P01` (Postgres deadlock) are the retryable ones. |
| `DataSource` / HikariCP `setTransactionIsolation` | Set pool-wide default (e.g., Hikari `transactionIsolation=TRANSACTION_REPEATABLE_READ`). |

JPA/Hibernate, Spring:

| API | Purpose |
|---|---|
| `@Transactional(isolation = Isolation.REPEATABLE_READ)` | Spring declarative isolation. |
| `@Version` (JPA) | Optimistic locking version column; Hibernate auto-increments and checks it. |
| `LockModeType.PESSIMISTIC_WRITE` / `OPTIMISTIC` / `OPTIMISTIC_FORCE_INCREMENT` | JPA lock modes; `PESSIMISTIC_WRITE` ⇒ `SELECT ... FOR UPDATE`. |
| `em.find(Entity.class, id, LockModeType.PESSIMISTIC_WRITE)` | Lock on read. |
| `OptimisticLockException` | Thrown when `@Version` check fails — catch and retry. |

### 4.4 Postgres configuration knobs (isolation/MVCC-relevant)

| Parameter | Default | Purpose |
|---|---|---|
| `default_transaction_isolation` | `read committed` | Cluster default level. |
| `default_transaction_read_only` | `off` | Default to read-only. |
| `idle_in_transaction_session_timeout` | `0` (off) | Kill sessions idle inside a transaction — **critical** to bound the xmin horizon. Set it (e.g., 5min). |
| `statement_timeout` | `0` | Abort long statements. |
| `max_pred_locks_per_transaction` | 64 | SSI predicate-lock budget per txn. |
| `vacuum_cost_delay` / `vacuum_cost_limit` | varies | VACUUM I/O throttling. |
| (autovacuum & freeze knobs) | see §3.3 | GC & wraparound protection. |

### 4.5 MySQL InnoDB configuration knobs

| Parameter | Default | Purpose |
|---|---|---|
| `transaction_isolation` | `REPEATABLE-READ` | Default level. |
| `innodb_lock_wait_timeout` | 50 (sec) | How long to wait for a row lock before erroring (1205). |
| `innodb_deadlock_detect` | `ON` | Toggle deadlock detection (off ⇒ rely on timeout; helps very high concurrency). |
| `innodb_print_all_deadlocks` | `OFF` | Log every deadlock to the error log. |
| `innodb_purge_threads` | 4 | MVCC garbage collection threads. |
| `innodb_max_purge_lag` | 0 | Throttle DML when purge falls behind. |
| `innodb_undo_log_truncate` | `ON` (8.0) | Reclaim undo tablespace space. |

### 4.6 Diagnostic tools & commands

| Tool / view | Engine | Use |
|---|---|---|
| `pg_stat_activity` | PG | See active queries, `state` (`idle in transaction`!), `backend_xmin`, `xact_start`. |
| `pg_locks` | PG | Current locks, including SIReadLocks. |
| `pg_blocking_pids(pid)` | PG | Who is blocking whom. |
| `pg_stat_user_tables` (`n_dead_tup`, `last_autovacuum`) | PG | Bloat & vacuum health. |
| `age(datfrozenxid)` | PG | Wraparound proximity. |
| `SHOW ENGINE INNODB STATUS` | MySQL | LATEST DETECTED DEADLOCK, history list length, active transactions. |
| `information_schema.innodb_trx` / `innodb_lock_waits` (8.0: `performance_schema.data_lock_waits`) | MySQL | Running transactions & lock waits. |
| `performance_schema.data_locks` | MySQL 8 | Held locks (record/gap/next-key). |

---

## 5. Code examples by use case

All Java examples assume PostgreSQL via JDBC unless noted, and target real, distinct scenarios.

### 5.1 Lost update — and three correct fixes

**The bug (read-modify-write in app code):**

```java
// WRONG: classic lost update under READ COMMITTED
conn.setAutoCommit(false);
int balance;
try (PreparedStatement ps = conn.prepareStatement(
        "SELECT balance FROM accounts WHERE id = ?")) {
    ps.setLong(1, accountId);
    try (ResultSet rs = ps.executeQuery()) { rs.next(); balance = rs.getInt(1); }
}
int newBalance = balance + amount;                 // gap: another txn can run here
try (PreparedStatement up = conn.prepareStatement(
        "UPDATE accounts SET balance = ? WHERE id = ?")) {
    up.setInt(1, newBalance); up.setLong(2, accountId); up.executeUpdate();
}
conn.commit();   // two concurrent runs each read 100, each write 150 -> one +50 LOST
```

**Fix A — atomic write (best when possible).** Push the arithmetic into SQL so the read and write are one atomic statement; the engine takes the row's write lock for the duration:

```java
// CORRECT: no read-modify-write gap; the DB does the math atomically
try (PreparedStatement up = conn.prepareStatement(
        "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
    up.setInt(1, amount); up.setLong(2, accountId);
    up.executeUpdate();
}
conn.commit();
```

**Fix B — pessimistic lock (`SELECT ... FOR UPDATE`).** When you need to read, branch on the value, then write:

```java
conn.setAutoCommit(false);
int balance;
try (PreparedStatement ps = conn.prepareStatement(
        "SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {  // X-lock the row
    ps.setLong(1, accountId);
    try (ResultSet rs = ps.executeQuery()) { rs.next(); balance = rs.getInt(1); }
}
if (balance + amount < 0) { conn.rollback(); throw new InsufficientFundsException(); }
try (PreparedStatement up = conn.prepareStatement(
        "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
    up.setInt(1, amount); up.setLong(2, accountId); up.executeUpdate();
}
conn.commit();   // other txns block on FOR UPDATE until we commit -> no lost update
```

**Fix C — optimistic lock (version column + retry).** No locks held across the think-time; detect the conflict at write:

```java
// Schema: accounts(id, balance, version BIGINT)
boolean done = false;
for (int attempt = 0; attempt < MAX_RETRIES && !done; attempt++) {
    conn.setAutoCommit(false);
    int balance; long version;
    try (PreparedStatement ps = conn.prepareStatement(
            "SELECT balance, version FROM accounts WHERE id = ?")) {
        ps.setLong(1, accountId);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next(); balance = rs.getInt(1); version = rs.getLong(2);
        }
    }
    int newBalance = balance + amount;
    try (PreparedStatement up = conn.prepareStatement(
            "UPDATE accounts SET balance = ?, version = version + 1 " +
            "WHERE id = ? AND version = ?")) {   // CAS on version
        up.setInt(1, newBalance); up.setLong(2, accountId); up.setLong(3, version);
        int rows = up.executeUpdate();
        if (rows == 1) { conn.commit(); done = true; }   // we won
        else { conn.rollback(); /* someone else bumped version -> retry */ }
    }
}
if (!done) throw new ConcurrencyException("Too many retries");
```

### 5.2 Write skew — and fixing it with SERIALIZABLE + retry

The on-call doctors invariant from §2.3. Under Postgres `REPEATABLE READ` (snapshot isolation), both transactions commit and the invariant breaks. Fix with `SERIALIZABLE` and a retry loop:

```java
// Robust serializable executor with retry on 40001 / deadlock
public <T> T runSerializable(DataSource ds, Callable<T> work) throws Exception {
    int attempts = 0;
    while (true) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try {
                T result = work.call();    // do the reads + writes inside
                c.commit();
                return result;
            } catch (SQLException e) {
                c.rollback();
                String s = e.getSQLState();
                if (("40001".equals(s) || "40P01".equals(s)) && ++attempts < 8) {
                    // serialization failure or deadlock: back off and retry
                    Thread.sleep((long) (Math.pow(2, attempts) + Math.random() * 10));
                    continue;
                }
                throw e;
            }
        }
    }
}
```

Inside `work`, the doctor leaves on call:

```sql
-- runs under SERIALIZABLE; SSI will abort one of two concurrent attempts
BEGIN;
SELECT count(*) AS n FROM doctors WHERE on_call AND shift_id = 1234;
-- application checks n >= 2 before proceeding
UPDATE doctors SET on_call = false WHERE name = 'Alice' AND shift_id = 1234;
COMMIT;   -- one of the two concurrent commits raises 40001 -> retried -> sees 1 -> rejected
```

### 5.3 A reliable job queue with `SKIP LOCKED`

A real, high-value pattern: many workers pull jobs from a table without stepping on each other and without long lock waits.

```sql
-- Each worker grabs one available job atomically:
BEGIN;
SELECT id, payload
FROM jobs
WHERE status = 'ready'
ORDER BY created_at
FOR UPDATE SKIP LOCKED        -- skip rows other workers already locked
LIMIT 1;
-- ... worker marks it taken:
UPDATE jobs SET status = 'processing', locked_by = 'worker-7' WHERE id = :id;
COMMIT;
```

```java
// Java worker loop using SKIP LOCKED
try (Connection c = ds.getConnection()) {
    c.setAutoCommit(false);
    Long jobId = null; String payload = null;
    try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, payload FROM jobs WHERE status='ready' " +
            "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1")) {
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) { jobId = rs.getLong(1); payload = rs.getString(2); }
        }
    }
    if (jobId == null) { c.rollback(); return; }  // queue empty
    try (PreparedStatement up = c.prepareStatement(
            "UPDATE jobs SET status='processing' WHERE id=?")) {
        up.setLong(1, jobId); up.executeUpdate();
    }
    c.commit();
    process(payload);   // do work after commit so the row isn't locked during processing
}
```

`SKIP LOCKED` (Postgres 9.5+, MySQL 8.0+) is the idiomatic way to build SQL queues; `NOWAIT` is its sibling when you'd rather fail fast than skip.

### 5.4 Consistent report under load (snapshot isolation / deferrable)

A long analytical query that must see a single consistent point-in-time while OLTP writes continue:

```sql
-- Postgres: a long read that never aborts and never blocks writers
BEGIN ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE;
-- engine waits for a "safe snapshot", then guarantees serializable WITHOUT 40001 aborts
SELECT region, sum(amount) FROM ledger GROUP BY region;   -- consistent totals
SELECT count(*) FROM ledger;                              -- consistent with the above
COMMIT;
```

Or simpler, when serializable correctness isn't needed for the report, just snapshot:

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;   -- one snapshot for the whole report
-- all SELECTs see the same instant; money in flight is counted exactly once
COMMIT;
```

### 5.5 Demonstrating each level's behavior (two psql sessions)

```sql
-- Session A                              -- Session B
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT bal FROM acct WHERE id=1;  -- 100
                                          BEGIN;
                                          UPDATE acct SET bal=200 WHERE id=1;
                                          COMMIT;
SELECT bal FROM acct WHERE id=1;  -- STILL 100 under RR (no non-repeatable read)
COMMIT;
SELECT bal FROM acct WHERE id=1;  -- now 200
```

Switch Session A to `READ COMMITTED` and the second `SELECT` returns `200` mid-transaction — that's the non-repeatable read, allowed at RC.

### 5.6 Spring/JPA optimistic & pessimistic locking

```java
@Entity
class Account {
    @Id Long id;
    int balance;
    @Version long version;   // JPA optimistic lock column
}

// Optimistic: Hibernate appends "AND version=?" and bumps it; throws on mismatch
@Transactional
public void deposit(Long id, int amt) {
    Account a = em.find(Account.class, id);
    a.setBalance(a.getBalance() + amt);   // flush -> UPDATE ... WHERE id=? AND version=?
}   // OptimisticLockException if someone else committed first -> caller retries

// Pessimistic: emits SELECT ... FOR UPDATE
@Transactional
public void withdraw(Long id, int amt) {
    Account a = em.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (a.getBalance() < amt) throw new InsufficientFundsException();
    a.setBalance(a.getBalance() - amt);
}
```

Wrap optimistic methods with Spring Retry on `OptimisticLockException` / `ObjectOptimisticLockingFailureException`, or `CannotSerializeTransactionException` (Spring's wrapper for SQLSTATE 40001) for serializable transactions.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **MVCC read cost = version-chain walking.** Long-running readers (or stuck purge/vacuum) force the engine to keep and traverse long chains. Symptom: reads slow down over time; fix the long transactions and let GC catch up.
- **Bloat (Postgres) / undo growth (InnoDB).** Update-heavy tables bloat. Mitigate: `fillfactor` < 100 (leave page room for HOT updates), avoid updating indexed columns unnecessarily, tune autovacuum aggressively per-table, and never leave transactions idle-in-transaction (set `idle_in_transaction_session_timeout`).
- **Hot rows.** A single counter row updated by everyone serializes throughput regardless of isolation. Solutions: sharded counters (N rows summed), `INSERT` then periodic aggregation, or move counters out of the OLTP path.
- **Lock contention vs. abort/retry.** Pessimistic (`FOR UPDATE`) trades throughput for predictability under high contention; optimistic/SSI trades retries (wasted work) for non-blocking reads under low contention. Match the strategy to the conflict rate.
- **Keep transactions short.** Long transactions hold the xmin horizon (Postgres) / history list (InnoDB), bloat storage, increase deadlock windows, and (Postgres) push you toward XID wraparound. **Do not** open a transaction, call an external service, then commit — never hold a DB transaction across a network call you don't control.

### 6.2 Correctness / concurrency

- **Know your engine's real semantics, not the SQL-standard names.** Test the actual behavior (use the two-session demo).
- **Read-modify-write must be protected** — atomic SQL, `FOR UPDATE`, version column, or `SERIALIZABLE`. Choosing none of these is choosing lost updates.
- **`SERIALIZABLE` requires retry loops.** Code that uses serializable but doesn't catch 40001/40P01 is broken under load.
- **Constraints are your friend.** A unique constraint prevents the double-insert phantom problem far more cheaply than serializable isolation. Push invariants into the schema where you can.
- **Beware write skew at snapshot isolation** for any "check a condition over rows, then write a different row" logic (booking last seat, on-call, balance-across-accounts). Either use SERIALIZABLE, take explicit `FOR UPDATE`/`FOR SHARE` locks on the rows you read for the decision, or materialize the conflict (e.g., a row you update so the writes collide).

### 6.3 Security

- Don't expose raw SQLSTATE/lock details to clients (information leak about contention/schema). Map to generic 409 Conflict / retry-after.
- Lock-based DoS: an attacker (or buggy client) holding `FOR UPDATE` / leaving idle-in-transaction can stall others. Bound it with timeouts (`statement_timeout`, `idle_in_transaction_session_timeout`, `innodb_lock_wait_timeout`).

### 6.4 Observability

- Track and alert on: **idle-in-transaction** sessions, oldest transaction age, `n_dead_tup` / autovacuum lag, `age(datfrozenxid)` (Postgres); **history list length**, deadlocks/sec, lock-wait timeouts (InnoDB).
- Count **40001/40P01** and **OptimisticLockException** retries as a first-class metric — a spike means rising contention.
- Use `pg_stat_activity` / `innodb_trx` dashboards; log all deadlocks (`innodb_print_all_deadlocks=ON`, Postgres `log_lock_waits=on`).

### 6.5 Cost

- Bloat and undo growth = real disk \$ and slower I/O. Storage is cheap until it isn't (full disk = outage). VACUUM/purge are not free; budget I/O for them.
- Retries waste CPU and latency; excessive serialization failures may cost more than just using pessimistic locks on the hot path.

### 6.6 Testing

- **Deterministic concurrency tests** with barriers: spin up two threads, use a `CyclicBarrier` to force the exact interleaving, assert the anomaly is/isn't present at each level.
- Test your **retry loop** by injecting 40001 (e.g., run two serializable transactions deliberately).
- Use **Jepsen**-style or `pg_isolation_tester`-style spec tests for critical invariants.
- Load-test at production concurrency; many isolation bugs only appear under contention.

### 6.7 Production hardening checklist

- Set sane defaults: `idle_in_transaction_session_timeout`, `statement_timeout`, `lock_timeout` (PG) / `innodb_lock_wait_timeout` (MySQL).
- Per-table autovacuum tuning for big churny tables; monitor wraparound.
- Connection pool sets the right isolation level explicitly (don't rely on per-call `setTransactionIsolation`, which may leak across pooled connections if not reset).
- A central, tested **transaction template** with retry on 40001/40P01/deadlock for every write path that needs serializability.

### 6.8 Anti-patterns

- Read-modify-write with no protection (lost updates).
- `SERIALIZABLE` without a retry loop.
- Long/idle transactions; transactions spanning external calls.
- Updating indexed columns on hot tables (kills HOT, bloats indexes).
- Assuming the SQL-standard name = behavior on your engine.
- Using `SELECT ... FOR UPDATE` then doing heavy work *while holding the lock* (lock the row, do work, then commit — minimize lock-hold time; commit before slow work where possible).
- Catch-and-ignore on `OptimisticLockException` (silently drops the user's change).
- Disabling deadlock detection without understanding the timeout consequences.

---

## 7. Advanced topics & deep internals

### 7.1 The serialization (dependency) graph and SSI's dangerous structure

Serializability theory models each transaction as a node and draws edges for conflicts: **ww** (write-write), **wr** (write-read), **rw** (read-write / "anti-dependency"). A schedule is serializable iff this graph is **acyclic**. SSI's theorem (Fekete et al.): every cycle that can occur *under snapshot isolation* must contain two consecutive **rw** edges meeting at a **pivot** transaction. SSI tracks rw edges with SIReadLocks and aborts to break potential cycles — it may abort even when no actual cycle would form (**false positives**), the price of being conservative and fast.

### 7.2 First-committer-wins / first-updater-wins

Under snapshot isolation, when two transactions update the **same row**, the engine must reject one to prevent a lost update. Two implementations:
- **First-committer-wins** (Oracle-style): both proceed; whoever commits second is aborted (ORA-08177).
- **First-updater-wins** (Postgres RR): the second updater **blocks** on the row's write lock; when the first commits, the second gets `could not serialize access due to concurrent update` (40001) and must retry. (Under READ COMMITTED, Postgres instead **re-reads** the row with the new value and retries the update internally — the "EPQ"/`HeapTupleUpdated` re-check, which is why RC silently does the right thing for single-row updates but can still lose updates across the read-then-write app pattern.)

### 7.3 READ COMMITTED's statement-level re-evaluation (Postgres EvalPlanQual)

Under READ COMMITTED, if an `UPDATE`/`DELETE`/`SELECT FOR UPDATE` finds a target row that was modified by a concurrent transaction that *committed* after the statement's snapshot, Postgres doesn't fail — it **walks the version chain to the latest committed version and re-checks the WHERE clause** (EvalPlanQual). If the row still matches, it locks and updates the new version; if not, it skips. This is why RC behaves intuitively for simple updates but can produce surprising results for complex `UPDATE ... WHERE` predicates over moving data. MySQL InnoDB has analogous "semi-consistent read" behavior under RC.

### 7.4 InnoDB locking nuances

- **Locking reads vs. consistent (non-locking) reads.** Plain `SELECT` under RR uses the consistent snapshot (no locks). `SELECT ... FOR UPDATE/SHARE`, and all `UPDATE/DELETE`, take **locks** and read the **latest committed** version (not the snapshot!) — this can surprise you: a locking read inside an RR transaction can see newer data than a plain read in the same transaction.
- **Gap locks only at RR/SERIALIZABLE.** RC disables gap locks (except for FK/unique checks), giving fewer deadlocks but allowing phantoms.
- **Insert intention locks**, **next-key locks** on the supremum pseudo-record, and **auto-inc locks** are all part of InnoDB's machinery for preventing phantoms and serializing inserts.

### 7.5 Postgres tuning knobs that bite

- `synchronous_commit` (durability vs. latency), separate from isolation but often confused.
- SSI predicate-lock escalation thresholds (`max_pred_locks_per_*`) — raise on serializable-heavy workloads to reduce false aborts.
- `vacuum_freeze_table_age` / aggressive vacuum behavior; `parallel vacuum` (PG 13+) speeds large-table vacuum.
- Replication slots and `hot_standby_feedback=on` can hold back the xmin horizon on the primary — a sneaky cause of bloat and wraparound risk.

### 7.6 Distributed isolation (beyond a single node)

In distributed/replicated systems, snapshot isolation and serializability get harder. Google Spanner uses **TrueTime** (globally synchronized clocks with bounded uncertainty) to provide **external consistency** (linearizable + serializable). CockroachDB and YugabyteDB use **hybrid logical clocks** and provide serializable by default. Many systems offer **causal consistency** or **read-your-writes** as cheaper cross-node guarantees. The single-node phenomena still apply, plus replication-lag anomalies (a read replica may serve a stale snapshot — "read your writes" violations). Flag: replica reads in MySQL/Postgres are typically *not* in the same MVCC snapshot as the primary.

### 7.7 The read-only anomaly (concrete)

Even SI's read-only transactions can see impossible states. Fekete's example with three transactions (two read-write, one read-only) shows the read-only txn observing a state inconsistent with both serial orders. This is why true serializability sometimes requires aborting/deferring read-only transactions — and why Postgres offers `READ ONLY DEFERRABLE`.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Anomalies prevented, by level & engine (the table to memorize)

| Anomaly | RU | RC | RR (PG = SI) | RR (InnoDB) | SERIALIZABLE (PG/SSI) | SERIALIZABLE (InnoDB) |
|---|---|---|---|---|---|---|
| Dirty write | No | No | No | No | No | No |
| Dirty read | Allowed* | No | No | No | No | No |
| Non-repeatable read | Allowed | Allowed | No | No | No | No |
| Phantom read | Allowed | Allowed | **No** | Mostly No† | No | No |
| Lost update | Allowed | Allowed‡ | Prevented (abort) | Prevented (locks) | Prevented | Prevented |
| Write skew | Allowed | Allowed | **Allowed** | Allowed | **Prevented** | Prevented |

\* Postgres RU = RC (never dirty reads). † InnoDB RR uses next-key locks to prevent phantoms for locking reads; some plain-read phantom edge cases exist. ‡ RC prevents lost update only for single-statement atomic updates; the read-then-write app pattern still loses updates.

### 8.2 Choosing an isolation level

| Use when… | Level |
|---|---|
| High-throughput OLTP, you handle read-modify-write explicitly (atomic SQL / FOR UPDATE / version col) | **READ COMMITTED** (default; lowest overhead) |
| You need consistent multi-statement reads / reports; updates are guarded against lost update | **REPEATABLE READ** (snapshot isolation) |
| Correctness-critical invariants spanning multiple rows (booking, on-call, balances), and write skew would be catastrophic | **SERIALIZABLE** (with retry loop) |
| You truly don't care about dirty reads (rare; approximate dashboards) | **READ UNCOMMITTED** (InnoDB only; PG won't give it) |

> Default stance: **READ COMMITTED + explicit locking/atomic-writes for the few hot invariants** is the most common production choice (lowest overhead, fewest aborts). Escalate specific transactions to SERIALIZABLE only where write skew matters.

### 8.3 Optimistic vs. pessimistic concurrency control (application level)

| Dimension | Optimistic (`@Version` / SSI / version-column CAS) | Pessimistic (`SELECT ... FOR UPDATE`) |
|---|---|---|
| Best for | Low contention, short critical sections, web/REST stateless flows | High contention on specific rows; long decision logic |
| Reads block writers? | No | No (but FOR UPDATE blocks other writers/FOR UPDATE readers) |
| Failure mode | Conflict → exception/40001 → **retry** | Wait/block → possible deadlock or lock timeout |
| Throughput under high contention | Drops (retry storms / wasted work) | Steadier (serialized but no wasted work) |
| Risk | Livelock if retries thrash | Deadlock; lock convoy; long lock holds |
| Complexity | Retry loop + idempotency | Careful lock ordering; timeouts |
| Cross-request (think-time) safety | Yes (version stored client-side; detect on submit) | No (can't hold a DB lock across a user think-time) |

Rule of thumb: **optimistic for HTTP-style stateless workflows with think-time; pessimistic for short, server-side, high-contention critical sections.**

### 8.4 Locking vs. MVCC as an engine mechanism

| | Pure 2PL (lock-based) | MVCC |
|---|---|---|
| Readers block writers | Yes | No |
| Writers block readers | Yes | No |
| Storage overhead | Low | Version history (bloat/undo) |
| GC needed | No | Yes (vacuum/purge) |
| Serializable cost | Built-in (range locks) | Needs SSI or read locks |
| Examples | older SQL Server default | Postgres, InnoDB, Oracle |

---

## 9. Failure modes & debugging

### 9.1 Deadlock

**Symptom:** `deadlock detected` (PG, SQLSTATE 40P01) / `Deadlock found when trying to get lock; try restarting transaction` (MySQL 1213).
**Cause:** transactions acquire locks in different orders (T1 locks A then B; T2 locks B then A).
**Diagnose:**
- MySQL: `SHOW ENGINE INNODB STATUS\G` → `LATEST DETECTED DEADLOCK` section shows both transactions and the locks.
- Postgres: server log (`log_lock_waits=on`), `pg_locks` joined with `pg_stat_activity`, `pg_blocking_pids()`.
**Fix:** consistent lock ordering (always lock rows by ascending id), shorter transactions, retry on the deadlock SQLSTATE, lower isolation where gap locks aren't needed, batch in deterministic order.

### 9.2 Lock-wait timeout

**Symptom:** MySQL 1205 `Lock wait timeout exceeded`; PG: query hangs then `canceling statement due to lock timeout` (if `lock_timeout` set).
**Cause:** a row you need is locked by another (often idle-in-transaction) session.
**Diagnose:** `information_schema.innodb_lock_waits` / `performance_schema.data_lock_waits` (MySQL); `pg_blocking_pids` (PG). Look for the blocking session's `state = 'idle in transaction'`.
**Fix:** kill/limit idle transactions, shorten lock holds, set `lock_timeout`, fix the app that forgot to commit.

### 9.3 Serialization failures (40001)

**Symptom:** PG `could not serialize access due to read/write dependencies among transactions` (SSI) or `... due to concurrent update`.
**Cause:** SERIALIZABLE conflict (or RR first-updater-wins).
**Fix:** retry loop with exponential backoff (this is *expected*, not a bug). If rates are high, reduce contention (shorter txns, narrower predicates, more granular rows) or raise `max_pred_locks_*` to cut false positives.

### 9.4 Bloat / vacuum not keeping up (Postgres)

**Symptom:** table/index size grows far beyond row count; queries slow; disk filling.
**Diagnose:** `pg_stat_user_tables.n_dead_tup`, `last_autovacuum`; check for `idle in transaction` and old `xact_start` in `pg_stat_activity`; check replication slots (`pg_replication_slots`).
**Fix:** kill long/idle transactions, tune autovacuum per-table (lower scale factor), `pg_repack`/`VACUUM FULL` to reclaim, drop orphaned replication slots.

### 9.5 XID wraparound emergency (Postgres)

**Symptom:** `WARNING: database "x" must be vacuumed within N transactions`, eventually the DB stops accepting writes.
**Diagnose:** `SELECT datname, age(datfrozenxid) FROM pg_database ORDER BY 2 DESC;` near 2 billion.
**Fix:** find and kill the transaction/slot holding back the xmin horizon, run aggressive `VACUUM (FREEZE)` on the oldest tables, increase autovacuum workers/cost limit. **Real incidents:** Sentry (2015) and Mailchimp publicly documented multi-hour outages from XID wraparound — the canonical cautionary tale for monitoring `age(datfrozenxid)`.

### 9.6 History list length growth (InnoDB)

**Symptom:** `SHOW ENGINE INNODB STATUS` shows large/growing "History list length"; reads slow; undo tablespace grows.
**Cause:** a long-running transaction (often a forgotten `BEGIN` in a reporting tool, or an uncommitted RR transaction) pins old versions so purge can't run.
**Fix:** find the offending transaction in `information_schema.innodb_trx` (`trx_started` long ago), commit/kill it; ensure `innodb_purge_threads` adequate; enable `innodb_undo_log_truncate`.

### 9.7 "My READ COMMITTED report double-counted money"

**Cause:** statement-level snapshots — a multi-statement report saw committed changes between statements (read skew). **Fix:** run the report in `REPEATABLE READ`/`SERIALIZABLE READ ONLY DEFERRABLE` so all statements share one snapshot.

### 9.8 Phantom under InnoDB RR with a plain read then write

**Cause:** InnoDB RR uses snapshot for plain reads but the latest version for locking reads; mixing them can surface a "phantom"-like inconsistency. **Fix:** be consistent — use locking reads (`FOR UPDATE`) for the rows you'll write, or use SERIALIZABLE.

---

## 10. Interview drill

**Q1. Name the four SQL isolation levels and the anomaly each one newly prevents.**
*Model answer:* READ UNCOMMITTED (prevents nothing extra; allows dirty reads). READ COMMITTED (prevents dirty reads). REPEATABLE READ (also prevents non-repeatable reads). SERIALIZABLE (also prevents phantoms and guarantees serializability). The standard defines them by phenomena prevented: dirty read, non-repeatable read, phantom.
- *Probe: Where do lost update and write skew fit?* Not in the original phenomena list; lost update is prevented at RR via first-updater-wins/locks; write skew is allowed under snapshot isolation (so under Postgres RR) and only prevented at SERIALIZABLE.
- *Probe: Does the standard mandate an implementation?* No — only the guarantees. Engines can exceed them and use locking or MVCC.

**Q2. Explain MVCC in one minute, then explain how the same physical row is visible to one transaction and not another.**
*Model answer:* MVCC keeps multiple immutable versions of each row, each stamped with creating/deleting transaction IDs. A transaction reads against a snapshot (a definition of which transactions had committed at a point in time) and a visibility rule picks the version it's allowed to see. Two transactions with different snapshots apply the same rule to the same version chain and select different versions — so the same physical tuple is visible to one and not the other without any locks on reads.
- *Probe: Postgres xmin/xmax?* xmin = creator XID, xmax = deleter XID; visible iff xmin committed-and-before-snapshot and xmax not-committed-or-after-snapshot.
- *Probe: Where do old versions live in InnoDB vs Postgres?* InnoDB: undo log (DB_ROLL_PTR chain). Postgres: in the heap as dead tuples, reclaimed by VACUUM.

**Q3. What is write skew? Give an example and say which isolation level prevents it.**
*Model answer:* Two transactions read an overlapping set, each makes a decision, each writes a different row; neither sees the other's write (separate snapshots), and a cross-row invariant breaks. Example: two on-call doctors each leave because each sees the other still on call → zero on call. Snapshot isolation allows it; SERIALIZABLE prevents it.
- *Probe: How does Postgres prevent it at SERIALIZABLE?* SSI tracks rw-dependencies and aborts one transaction with 40001.
- *Probe: How without raising the level?* Take explicit `FOR UPDATE`/`FOR SHARE` locks on the rows read for the decision, or materialize the conflict.

**Q4. Postgres REPEATABLE READ vs MySQL InnoDB REPEATABLE READ — same behavior?**
*Model answer:* No. Both are snapshot-based and prevent non-repeatable reads. Postgres RR is true snapshot isolation and prevents phantoms but allows write skew. InnoDB RR adds next-key (gap) locks for locking reads to prevent phantoms, and InnoDB's locking reads see the latest committed version (not the snapshot), causing subtle differences. The name is identical; the semantics differ — always test on your engine.
- *Probe: Why do gap locks matter?* They lock the predicate range so no insert can create a phantom; they exist only at RR/SERIALIZABLE.
- *Probe: Default level each?* Postgres RC; InnoDB RR.

**Q5. How is SERIALIZABLE implemented in Postgres vs InnoDB?**
*Model answer:* Postgres uses **SSI** — optimistic, tracks read/write dependencies with non-blocking SIReadLocks, aborts (40001) when a dangerous structure (two consecutive rw edges) appears. InnoDB uses **locking** — it turns plain SELECTs into shared next-key locks, blocking conflicting writers. Postgres: more aborts, no extra blocking; InnoDB: more blocking/deadlocks, fewer aborts.
- *Probe: What must the app do for Postgres SERIALIZABLE?* Retry on 40001/40P01.
- *Probe: Cost of SSI?* Memory for predicate locks; escalation causes false-positive aborts.

**Q6. Optimistic vs pessimistic locking — when do you pick each? (senior signal)**
*Model answer:* Optimistic (version column / CAS / SSI) when contention is low and especially across HTTP think-time, where you can't hold a DB lock — store the version client-side, detect conflict on submit, retry. Pessimistic (`FOR UPDATE`) for short, server-side, high-contention critical sections where retry storms would waste more than blocking. Optimistic shifts cost to retries (wasted work); pessimistic shifts cost to blocking (and deadlock risk). Measure conflict rate to decide.
- *Probe: Failure of each?* Optimistic: livelock/retry storm; pessimistic: deadlock/lock convoy/long holds.
- *Probe: Can you mix?* Yes — optimistic across requests, pessimistic within a single short server transaction.

**Q7. Why and how do you keep transactions short? (senior signal)**
*Model answer:* Long transactions hold the xmin horizon (PG) / history list (InnoDB), blocking GC → bloat/undo growth → slower reads and disk pressure; they widen deadlock windows; in Postgres they push toward XID wraparound. Practices: never call external services inside a DB transaction; set `idle_in_transaction_session_timeout` and `statement_timeout`; do slow work outside the lock; commit before processing in queue patterns.
- *Probe: How detect the culprit?* `pg_stat_activity` oldest `xact_start` / `idle in transaction`; `innodb_trx.trx_started`.
- *Probe: Replication's role?* Replication slots / `hot_standby_feedback` can hold back the horizon on the primary.

**Q8. Walk through what an UPDATE does under MVCC, including GC.**
*Model answer:* It marks the current version expired (set xmax = my xid / write before-image to undo) and creates a new version (xmin = my xid), linking the chain. Concurrent older snapshots still see the old version. On commit both are durable. Later, when no snapshot needs the old version, VACUUM (PG) / purge (InnoDB) reclaims it. Postgres also freezes very old XIDs to avoid wraparound.
- *Probe: HOT?* If no indexed column changed and it fits the page, Postgres keeps the chain in-heap and skips index updates.
- *Probe: First-updater-wins?* The second concurrent updater blocks then gets 40001 at RR.

**Q9. SELECT ... FOR UPDATE — what does it do, and what are NOWAIT/SKIP LOCKED?**
*Model answer:* Takes exclusive row locks on the selected rows, blocking other writers and FOR UPDATE readers until commit — pessimistic concurrency. `NOWAIT` errors immediately if a row is locked; `SKIP LOCKED` silently skips locked rows (ideal for work queues so workers don't contend).
- *Probe: Does it block plain readers?* No — plain MVCC reads still see their snapshot.
- *Probe: Risk?* Holding it across slow work serializes throughput and risks deadlock; lock only what you need, briefly.

**Q10. Your service shows rising 40001 errors in production. Diagnose and respond. (senior signal)**
*Model answer:* 40001 = serialization failures, expected under SERIALIZABLE/RR contention. First confirm there's a retry loop (if not, that's the bug). Then reduce contention: shorten transactions, narrow predicates (SSI predicate locks cover ranges), split hot rows, move some transactions to RC + explicit locks where write skew isn't a risk, and consider raising `max_pred_locks_*` to cut false-positive escalation. Add metrics on retry counts and tail latency to validate.
- *Probe: When prefer pessimistic instead?* When the conflict rate is so high that retries waste more than blocking would.
- *Probe: Could it be a single hot row?* Yes — sharded counters or atomic SQL on the server fix it.

**Q11. Explain dirty read vs non-repeatable read vs phantom precisely.**
*Model answer:* Dirty read = reading another transaction's *uncommitted* write. Non-repeatable read = the same row's value changes between two reads in your transaction because another committed an UPDATE. Phantom = the *set* of rows matching a predicate changes between two reads because another committed an INSERT/DELETE.
- *Probe: Which level first removes each?* RC removes dirty; RR removes non-repeatable; SERIALIZABLE (and PG RR, InnoDB RR via gap locks) removes phantom.

**Q12. What is vacuum / purge and why is it existential?**
*Model answer:* They reclaim row versions no transaction can see anymore. Without them, MVCC storage grows unbounded (Postgres heap bloat; InnoDB undo growth), reads slow (longer chains), and in Postgres unfrozen ancient XIDs risk wraparound and data loss. They're the price of MVCC's non-blocking reads.
- *Probe: VACUUM vs VACUUM FULL?* Plain marks space reusable in-table (no exclusive lock); FULL rewrites the table, returns space to OS, takes ACCESS EXCLUSIVE lock.
- *Probe: What stalls them?* Long/idle transactions, replication slots holding the horizon.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability: the guarantees of a transactional database.
- **Anomaly / phenomenon** — an incorrect observable behavior under concurrency (dirty read, etc.).
- **Anti-dependency (rw-conflict)** — T1 reads a value, T2 later writes it; an edge in the serialization graph central to SSI.
- **Autovacuum** — Postgres background process that runs VACUUM/ANALYZE automatically.
- **Bloat** — dead row versions/index entries occupying space until reclaimed.
- **CLOG / pg_xact** — Postgres commit log mapping XIDs to commit/abort status.
- **Consistent (non-locking) read** — InnoDB read served from the MVCC snapshot without locks.
- **CAS (compare-and-set)** — update only if a value (e.g., version) matches expectation; the optimistic-locking primitive.
- **Deadlock** — cyclic lock wait; resolved by the engine aborting a victim.
- **Dirty read / write** — reading/overwriting uncommitted data.
- **EvalPlanQual (EPQ)** — Postgres RC mechanism to re-check an updated row's WHERE against the latest version.
- **First-committer/updater-wins** — SI rules for resolving concurrent writes to the same row.
- **Gap lock / next-key lock / record lock** — InnoDB locks on a range / range+record / single record, used to prevent phantoms.
- **HOT (Heap-Only Tuple)** — Postgres optimization keeping a version chain in-page without new index entries.
- **History list length** — InnoDB metric of undo history retained for MVCC; growth signals stuck purge.
- **Isolation level** — the contract for which anomalies concurrent transactions may observe.
- **Lost update** — one transaction's update overwritten by another's in a read-modify-write race.
- **MVCC** — Multi-Version Concurrency Control: isolation via multiple row versions + snapshots.
- **Non-repeatable read** — a row's value changes between two reads in one transaction.
- **Optimistic concurrency control** — proceed without locks, detect conflicts at commit, retry.
- **Phantom read** — the set of rows matching a predicate changes between reads.
- **Pessimistic concurrency control** — take locks up front to prevent conflicts.
- **Predicate lock** — a lock on a search condition / range, not a physical row (used by SSI and gap locks).
- **Purge** — InnoDB's GC of undo records and delete-marked rows.
- **Read view** — InnoDB's snapshot structure.
- **Roll pointer (DB_ROLL_PTR)** — InnoDB pointer from a row to its previous version in the undo log.
- **Serializable / serializability** — execution equivalent to some serial order; the strongest isolation.
- **Serialization graph** — nodes=transactions, edges=conflicts; acyclic ⇔ serializable.
- **Snapshot** — the point-in-time view of committed data a transaction reads against.
- **Snapshot isolation (SI)** — each transaction reads a consistent snapshot; allows write skew (≠ serializable).
- **SSI (Serializable Snapshot Isolation)** — Postgres's optimistic serializable: SI + rw-dependency tracking + aborts.
- **SQLSTATE 40001 / 40P01** — serialization failure / deadlock; the retryable error codes.
- **Two-Phase Locking (2PL) / Strict 2PL** — lock-based protocol: grow then shrink; strict holds all locks to commit.
- **Undo log** — InnoDB store of before-images used to reconstruct old versions and to roll back.
- **VACUUM / VACUUM FULL** — Postgres GC: reclaim space (in-table) / rewrite table (returns space, exclusive lock).
- **Version chain** — the linked sequence of a row's versions.
- **Visibility rule** — the per-tuple test deciding if a version is visible to a snapshot.
- **Write skew** — concurrent transactions read overlapping data, write disjoint rows, break a cross-row invariant.
- **XID (transaction ID)** — Postgres 32-bit transaction identifier; basis of MVCC timestamps; subject to wraparound.
- **XID wraparound** — XID counter overflow risk causing old rows to look future-dated; prevented by freezing.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Levels → anomalies allowed (standard):**
- RU: dirty, non-repeatable, phantom
- RC: non-repeatable, phantom (no dirty)
- RR: phantom (no dirty/non-repeatable)
- SERIALIZABLE: none

**Engine reality:**
- **Postgres** default RC; RR = snapshot isolation (no phantoms, allows write skew); SERIALIZABLE = SSI → **retry 40001/40P01**. RU = RC. XID wraparound is real → monitor `age(datfrozenxid)`; VACUUM reclaims heap bloat.
- **InnoDB** default RR; RR uses next-key locks (no phantoms for locking reads); SERIALIZABLE = shared next-key locks (blocking). Old versions in **undo log**; watch **history list length**. RU does dirty reads.

**Anomalies one-liner:** dirty=uncommitted read; non-repeatable=value changed; phantom=row set changed; lost update=RMW race; write skew=disjoint writes break cross-row invariant (SI allows, SERIALIZABLE prevents).

**Fix lost update:** atomic SQL (`SET x = x + ?`) > `FOR UPDATE` > `@Version`/CAS retry.
**Fix write skew:** SERIALIZABLE + retry, or `FOR UPDATE`/`FOR SHARE` on decision rows, or materialize conflict.
**Queues:** `FOR UPDATE SKIP LOCKED`.
**Consistent report:** RR or `SERIALIZABLE READ ONLY DEFERRABLE`.

**Retryable SQLSTATEs:** `40001` (serialization), `40P01` (PG deadlock); MySQL 1213 deadlock, 1205 lock-wait timeout.

**Key defaults:** PG `default_transaction_isolation=read committed`; autovacuum scale 0.2; `autovacuum_freeze_max_age=200M`; XID limit ~2.1B. InnoDB `transaction_isolation=REPEATABLE-READ`; `innodb_lock_wait_timeout=50s`; `innodb_purge_threads=4`.

**Optimistic vs pessimistic:** optimistic = low contention / across think-time / retry; pessimistic = short high-contention server section / block.

**Golden rules:** keep transactions short; never hold a DB transaction across an external call; protect every read-modify-write; SERIALIZABLE requires retry loops; bound idle-in-transaction; know your engine's real semantics.

### Self-test (no answers — recall actively)

1. A transaction reads a row, another commits a change to a *different* row, and your transaction then violates a global invariant though each transaction was individually correct. Name the anomaly, the lowest isolation level that prevents it on Postgres, and two ways to prevent it without raising the level.
2. On Postgres, walk through `UPDATE accounts SET balance=balance+100 WHERE id=1` at the tuple level (xmin/xmax, version chain, what a concurrent older-snapshot reader sees, and when the old version is reclaimed).
3. Your MySQL job shows "History list length" climbing into the millions and reads are slowing. What is happening, how do you find the cause, and how do you fix it?
4. Contrast how Postgres and InnoDB each implement SERIALIZABLE, and state the one thing the application must do differently for Postgres.
5. You have an HTTP endpoint where a user edits a record over several seconds, then submits. Which concurrency strategy do you use and exactly how do you implement it, including the failure response? Why not the other strategy?
6. Explain why `SELECT ... FOR UPDATE SKIP LOCKED` is preferable to plain `SELECT ... FOR UPDATE` for a multi-worker queue, and what `NOWAIT` would do instead.
7. What is XID wraparound, what symptom precedes it, what query detects proximity, and what operational mistakes make it more likely?

---

*End of chapter.*

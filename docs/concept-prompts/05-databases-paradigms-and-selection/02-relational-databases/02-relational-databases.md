# Relational Databases

> A definitive engineering-handbook chapter for senior backend developers (Java/JVM ecosystem). From the relational model and SQL essentials, through B-tree internals, query planning, transactions, locking (Postgres / MySQL InnoDB), normalization, connection pooling, partitioning, and production debugging.

---

## 1. Overview & where it fits

A **relational database management system (RDBMS)** stores data as *relations* — what most people call **tables**: a set of rows (tuples), each with the same named, typed columns (attributes). On top of this it gives you three things that, taken together, almost nothing else does as well:

1. **A declarative query language (SQL)** where you describe *what* you want, and a **query optimizer** figures out *how* to get it efficiently.
2. **ACID transactions** — guarantees that groups of changes are Atomic, Consistent, Isolated, and Durable, so you can reason about concurrent access without hand-rolling locking.
3. **Declarative integrity constraints** — primary keys, foreign keys, unique constraints, check constraints, `NOT NULL` — so the database enforces correctness for you instead of trusting every application that touches it.

**The problem it solves.** Before relational databases (the model was introduced by Edgar F. Codd at IBM in 1970), applications navigated data through hand-coded pointers and hierarchies (the *network* and *hierarchical* models). Every query was a bespoke traversal program; changing the storage layout broke the application. Codd's insight was to separate the **logical model** (tables and relationships) from the **physical storage** (files, indexes, pages), and to let a *set-oriented algebra* (relational algebra) define queries. You ask for "all orders over $100 joined to their customers"; the engine decides whether to scan, use an index, or build a hash table.

**When you reach for it (the senior's default).** Relational is the correct **default** for almost any system with:
- Structured data with clear relationships (users, orders, payments, inventory).
- A need for **transactional correctness** — money, bookings, inventory, anything where a lost or double-applied write is a bug that costs real money.
- **Ad-hoc query flexibility** — you don't know all access patterns in advance, and you want to answer new questions without re-modeling.
- Moderate-to-large but not internet-planet-scale data (a single Postgres/MySQL node comfortably handles tens of thousands of TPS and terabytes; with read replicas and partitioning, much more).

You reach for *something else* only when you have a specific, validated reason: extreme write throughput across many nodes, schemaless rapidly-evolving documents, graph traversal as the dominant pattern, full-text/vector search at scale, or time-series ingestion. Even then, a relational DB is often part of the system as the source of truth.

**One-paragraph mental model.** Think of an RDBMS as a *fact store with a planner and a referee*. Facts are rows in tables. The **planner** (query optimizer) reads your declarative SQL and your **statistics** about the data, then chooses an *execution plan* — which indexes to use, what join order and join algorithm, whether to sort or hash. The **referee** (the transaction & locking subsystem) lets many clients read and write concurrently while preserving the illusion that each transaction runs alone (isolation) and survives crashes (durability via a write-ahead log). Indexes (almost always **B+-trees**) are the data structures that let the planner find rows in `O(log n)` instead of scanning everything. Master those three actors — planner, referee, B-tree — and you understand 90% of what makes relational databases fast or slow.

---

## 2. Foundations from first principles

### 2.1 The relational model, defined term by term

- **Relation / table.** A set of tuples sharing a *schema*. "Set" is theoretical (no duplicates, no order); real SQL tables are *multisets* (duplicates allowed unless constrained) and have no inherent row order — **never rely on insertion order without `ORDER BY`.**
- **Tuple / row.** One record: a fixed set of attribute values, e.g. `(id=42, name='Ada', email='ada@x.com')`.
- **Attribute / column.** A named, typed slot. Each column has a **data type** (`INTEGER`, `VARCHAR(255)`, `TIMESTAMP`, `NUMERIC(12,2)`, `BOOLEAN`, `JSONB`, etc.).
- **Domain.** The set of legal values for a column (the type plus any `CHECK` constraints). E.g. `age INT CHECK (age >= 0)`.
- **Degree.** Number of columns. **Cardinality.** Number of rows.
- **NULL.** A special marker meaning "unknown / not applicable." Critically, `NULL` is *not* a value: `NULL = NULL` is **not true, it is `UNKNOWN`** (three-valued logic). This trips up everyone. `WHERE x = NULL` matches nothing; you must write `WHERE x IS NULL`. Aggregates like `COUNT(col)` skip NULLs; `COUNT(*)` does not.

### 2.2 Keys

- **Candidate key.** A minimal set of columns that uniquely identifies every row.
- **Primary key (PK).** The chosen candidate key. Implies `UNIQUE` + `NOT NULL`. In InnoDB it also determines physical row order (see clustered index, §3).
- **Surrogate key.** A synthetic PK with no business meaning — an auto-increment integer or a UUID. Preferred over **natural keys** (e.g. email, SSN) because business identifiers change and leak into foreign keys everywhere.
- **Foreign key (FK).** A column (or set) in table A that references the PK of table B, enforcing **referential integrity**: you cannot insert an order for a customer that doesn't exist, and (depending on `ON DELETE` rules) deleting a customer can cascade or be rejected.
- **Composite key.** A key spanning multiple columns, e.g. `(order_id, line_no)`.

### 2.3 Relational algebra → SQL

SQL is a (mostly) declarative skin over **relational algebra**, the formal set of operations:

| Algebra operation | Meaning | SQL |
|---|---|---|
| Selection (σ) | Filter rows | `WHERE` |
| Projection (π) | Pick columns | `SELECT col1, col2` |
| Cartesian product (×) | Every row of A paired with every row of B | `FROM a, b` (no join condition) |
| Join (⋈) | Product + filter on a predicate | `JOIN ... ON` |
| Union / Intersection / Difference | Set operations | `UNION` / `INTERSECT` / `EXCEPT` |
| Rename (ρ) | Alias | `AS` |
| Grouping/aggregation | Collapse rows into summaries | `GROUP BY` + `SUM/COUNT/...` |

**Logical evaluation order of a `SELECT`** (this is the order to *reason* about, not necessarily the physical order the engine runs):
`FROM` (and `JOIN`) → `WHERE` → `GROUP BY` → `HAVING` → `SELECT` (compute expressions, window functions) → `DISTINCT` → `ORDER BY` → `LIMIT/OFFSET`.

This explains common confusions: you can't use a `SELECT` alias in `WHERE` (WHERE runs first), but you *can* in `ORDER BY`; `HAVING` filters groups (after aggregation), `WHERE` filters rows (before).

### 2.4 Joins — the core of relational querying

Given `customers(id, name)` and `orders(id, customer_id, total)`:

- **INNER JOIN** — rows where the predicate matches in both. Customers with at least one order.
- **LEFT (OUTER) JOIN** — all left rows; right columns are `NULL` when no match. All customers, with their orders if any.
- **RIGHT JOIN** — symmetric; rarely used (flip the tables instead).
- **FULL OUTER JOIN** — all rows from both, NULLs where no match.
- **CROSS JOIN** — Cartesian product, every pair. Use deliberately (e.g. generating a calendar grid); accidental cross joins are a classic performance disaster.
- **SELF JOIN** — a table joined to itself (e.g. employees to their managers).
- **SEMI JOIN / ANTI JOIN** — "rows in A that have / don't have a match in B," expressed via `EXISTS` / `NOT EXISTS` or `IN` / `NOT IN`. The optimizer recognizes these and need not materialize the right side.

```sql
-- Customers and their order count, including customers with zero orders.
SELECT c.id, c.name, COUNT(o.id) AS order_count
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id   -- LEFT keeps zero-order customers
GROUP BY c.id, c.name;
-- NOTE: COUNT(o.id) not COUNT(*) — COUNT(*) would count the NULL-filled row as 1.
```

### 2.5 Indexes — the first principle of performance

An **index** is an auxiliary data structure that maps column value(s) → row location(s), kept sorted (B-tree) so lookups, range scans, and ordered reads are fast. Without an index, finding rows means a **full table scan** (read every row). With one, the engine descends a tree in `O(log n)`.

- The trade: indexes speed up reads but **slow down writes** (every `INSERT`/`UPDATE`/`DELETE` must maintain every affected index) and consume storage and memory (buffer cache).
- **Selectivity** is what makes an index worth using: an index on a column where each value matches few rows (high selectivity, e.g. email) is great; an index on `gender` (two values) is usually useless because the engine would still read ~half the table — a scan is cheaper.

We go deep on B-trees in §3.

### 2.6 Transactions & ACID

A **transaction** is a unit of work that either fully happens or not at all.

- **Atomicity.** All-or-nothing. If any statement fails (or you `ROLLBACK`), every change in the transaction is undone. Implemented via the **undo log / rollback segment**.
- **Consistency.** The transaction moves the DB from one valid state to another, respecting all constraints (PK, FK, CHECK). This is partly the DB's job (enforcing constraints) and partly yours (writing correct logic).
- **Isolation.** Concurrent transactions don't see each other's incomplete work; the result is *as if* they ran in some serial order (depending on isolation level — see §3.6).
- **Durability.** Once `COMMIT` returns, the change survives a crash/power loss. Implemented via the **write-ahead log (WAL)** flushed to durable storage before commit acknowledges.

```sql
BEGIN;                                  -- start transaction
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
-- if either fails, neither change persists
COMMIT;                                 -- durably persists both, atomically
```

### 2.7 Write-Ahead Logging (WAL) — the durability mechanism, explained simply

Writing each change directly to the data files in random places would be slow and crash-unsafe (a crash mid-write corrupts a page). Instead, RDBMSs use **WAL**: *before* a change is applied to the actual data pages, a compact, sequential record of the change is appended to a log file and **fsync**'d (forced to disk). "fsync" is a syscall that tells the OS to flush its write buffers to physical storage; it's the expensive operation that makes durability real.

Because the log is sequential (fast) and the data-page writes can happen lazily later (the "checkpoint" flushes dirty pages in the background), you get both speed and durability. On crash recovery, the engine **replays** the WAL forward (redo) and rolls back uncommitted transactions (undo). Postgres calls this WAL; MySQL/InnoDB calls it the **redo log** (plus a separate **undo log** for MVCC/rollback).

### 2.8 The buffer pool / page cache — why RAM matters

Databases don't read single rows from disk; they read fixed-size **pages** (Postgres: 8 KB; InnoDB: 16 KB by default). Recently used pages live in an in-memory **buffer pool** (InnoDB: `innodb_buffer_pool_size`) or **shared buffers** (Postgres: `shared_buffers`). A query that finds its pages in memory is orders of magnitude faster than one that must read from disk. Sizing this cache correctly is one of the highest-leverage tuning decisions (§7).

---

## 3. How it works internally — the heart of the doc

We trace, step by step: (a) the lifecycle of a query, (b) the B+-tree index, (c) how the planner uses indexes, (d) MVCC, (e) the write/commit path, (f) isolation and locking.

### 3.1 Lifecycle of a query (end to end)

When your application sends `SELECT ... FROM orders WHERE customer_id = 42 AND status = 'PAID'`:

1. **Parse.** The SQL text is tokenized and parsed into an abstract syntax tree. Syntax errors are caught here.
2. **Bind / analyze (semantic analysis).** Names are resolved: does `orders` exist? Does `customer_id` exist and what's its type? Permissions checked. The AST becomes a **logical query tree** (relational-algebra operators).
3. **Rewrite.** Views are expanded inline, some subqueries are flattened, constant expressions folded, predicates pushed down. (Postgres has an explicit rewrite phase; it also applies rules and expands `CHECK`/RLS policies.)
4. **Plan / optimize.** The **cost-based optimizer** enumerates candidate **physical plans**: for each table, sequential scan vs which index scan; for joins, the **order** of joins and the **algorithm** (nested loop / hash / merge). Each candidate is assigned an estimated **cost** computed from **table statistics** (row counts, value distribution histograms, number of distinct values, correlation). The cheapest plan wins. This is where most performance lives.
5. **Execute.** The chosen plan is a tree of **operators** (iterators). Execution typically uses the **Volcano/iterator model**: each operator exposes `open()/next()/close()`, pulling rows from its children one at a time (or in vectorized batches in newer engines). Rows flow up the tree; the buffer pool serves pages, reading from disk on miss.
6. **Return.** Result rows are streamed back to the client (often in batches; the JDBC **fetch size** controls how many rows are pulled per round trip — see §4 and §6).

**EXPLAIN** shows step 4's chosen plan; **EXPLAIN ANALYZE** actually runs it and shows estimated vs **actual** rows and timing — the single most important debugging tool (§9).

### 3.2 The B+-tree index in detail

Almost every relational index is a **B+-tree** (a balanced, high-fanout search tree variant). Why this structure and not a binary tree or hash?

- **Disk-oriented.** Each tree **node = one page** (8–16 KB). A node holds *many* keys (high *fanout*, often hundreds), so the tree is **shallow** — a billion rows is only ~3–4 levels deep. Each level is one page read (cache miss). Thus point lookups touch a handful of pages.
- **Balanced.** All leaves are at the same depth; the tree self-balances on insert/delete (splitting/merging nodes), guaranteeing `O(log n)`.
- **Range-friendly.** In a **B+-tree**, all actual keys live in the **leaf level**, and **leaves are linked in a doubly-linked list** in sorted order. So a range scan (`WHERE x BETWEEN 10 AND 50`) descends once to the start, then walks leaves sideways — no re-descending. Internal nodes hold only separator keys for routing.

**Anatomy:**
- **Root node** (one page, cached almost always) → **internal nodes** (routing) → **leaf nodes** (the data or pointers).
- A **leaf entry** holds the indexed key + a pointer to the row. What the pointer *is* differs by engine and index type — this is the crucial clustered vs secondary distinction below.

**Operations and cost:**
- **Point lookup** (`= value`): descend root→leaf, `O(log n)` ≈ 3–4 page reads.
- **Range scan** (`BETWEEN`, `>`, `<`, `LIKE 'abc%'`): descend to first match, scan leaves. Cost ∝ rows returned.
- **Ordered retrieval** (`ORDER BY indexed_col`): read leaves in order — **free sort**, no separate sort step.
- **Insert/delete:** find leaf, insert/remove; if a node overflows it **splits** (propagating up, possibly growing the tree); if it underflows it may **merge**. Splits are why random-key inserts (e.g. random UUIDv4 PKs) cause **page fragmentation** and write amplification, whereas monotonic keys (auto-increment, UUIDv7/ULID) append to the rightmost leaf — far cheaper.

#### Clustered vs secondary indexes — know this cold

- **Clustered index (InnoDB).** In MySQL/InnoDB, the **table *is* its primary-key B+-tree**: the leaf nodes contain the **full rows**, physically ordered by PK. There is exactly one clustered index per table (the PK; if none declared, InnoDB picks a unique `NOT NULL` key, else generates a hidden 6-byte row id). Consequence: **PK lookups are maximally fast** (you reach the row in the leaf), and PK range scans are sequential.
- **Secondary index (InnoDB).** A secondary index's leaf holds the indexed columns **+ the PK value** (not a physical pointer). To fetch other columns it must do a **second lookup into the clustered index** by PK — a **bookmark lookup** (a.k.a. "lookup back to the heap" conceptually). This is why a **fat PK is expensive in InnoDB**: every secondary index embeds the PK, bloating all of them. Prefer a small integer PK.
- **Heap + index (Postgres).** Postgres stores rows in an unordered **heap**; *all* indexes (including the PK) are **secondary** and their leaves point to a physical **TID** (tuple id = page number + offset). There is no clustered index by default (the `CLUSTER` command reorders a heap by an index once, but it doesn't stay clustered). Trade-off: Postgres PK lookups do one extra heap fetch vs InnoDB, but Postgres avoids InnoDB's PK-bloat-in-every-secondary-index problem.

#### Covering indexes & index-only scans

If an index contains *all* columns a query needs (in the key or as **included** columns), the engine answers from the index alone — a **covering index / index-only scan** — skipping the table/heap fetch entirely. Huge win for hot queries.

```sql
-- Postgres: include extra columns so this index "covers" the query.
CREATE INDEX idx_orders_cust_covering
  ON orders (customer_id) INCLUDE (status, total);  -- INCLUDE = non-key payload columns

-- Now this query can be index-only (no heap access):
SELECT status, total FROM orders WHERE customer_id = 42;
```
(Postgres caveat: an index-only scan still needs the row's **visibility** to be known; it checks the **visibility map** and may fall back to heap fetches if pages aren't all-visible — keep tables `VACUUM`'d.)

#### Composite (multi-column) indexes and the leftmost-prefix rule

An index on `(a, b, c)` is sorted by `a`, then `b`, then `c`. It can serve:
- `WHERE a = ?`
- `WHERE a = ? AND b = ?`
- `WHERE a = ? AND b = ? AND c = ?`
- `WHERE a = ? AND b = ? AND c > ?` (range on the last used column)
- `WHERE a = ? ORDER BY b` (ordered, no sort)

It generally **cannot** efficiently serve `WHERE b = ?` alone (skips the leftmost column) — though MySQL 8+ and modern Postgres can sometimes do an **index skip scan** when `a` has few distinct values. **Column order matters enormously:** put **equality predicates first, then the range/sort column last**. Put the most selective equality columns early.

### 3.3 How the planner chooses (cost-based optimization)

The optimizer's job: pick the cheapest physical plan. It needs to **estimate** how many rows each operation produces (**cardinality estimation**) and how expensive each access path is (**cost model**).

- **Statistics** drive everything. Collected by `ANALYZE` (Postgres) / via `innodb_stats_persistent` sampling (MySQL). They include per-column **histograms** (value distribution), **n_distinct** (number of distinct values), **null fraction**, and **correlation** (how physically ordered the column is relative to the heap). Postgres samples ~`default_statistics_target` (default **100**) × 300 rows.
- **Selectivity estimation.** For `status = 'PAID'`, the planner consults the histogram to estimate the fraction of rows matching, multiplies by table cardinality → estimated rows. Multiple predicates are combined (assuming independence unless **extended/multivariate statistics** are defined).
- **Access-path costing.** Sequential scan cost ≈ (pages × `seq_page_cost`) + (rows × `cpu_tuple_cost`). Index scan cost ≈ tree descent + matching leaf reads + (for non-covering) random heap fetches × `random_page_cost`. The default `random_page_cost`/`seq_page_cost` ratio in Postgres is **4.0 / 1.0**, modeling spinning disks; on SSDs people lower `random_page_cost` to ~**1.1**, which makes the planner favor index scans more.
- **Join algorithm selection:**
  - **Nested loop join.** For each row of the outer table, probe the inner (ideally via an index). Best when the outer side is small and the inner has a good index. `O(outer × inner_lookup_cost)`.
  - **Hash join.** Build an in-memory hash table on the smaller input's join key, then probe it with the larger input. Best for large, unindexed equijoins. Needs memory (`work_mem` in Postgres); spills to disk if it doesn't fit.
  - **Merge join.** Sort both inputs by the join key (or read them already-sorted from indexes), then merge. Best for large inputs already ordered or when output ordering is reusable.
- **Join order.** With N tables there are exponentially many orders; the planner uses dynamic programming up to a threshold (Postgres `geqo_threshold`, default **12** tables, beyond which it uses a genetic algorithm to avoid combinatorial blowup).

**Key takeaway:** bad plans almost always trace back to **bad estimates** (stale/missing statistics, correlated columns the planner assumes are independent, or skewed data). Fix the statistics before fighting the planner with hints.

### 3.4 MVCC — Multi-Version Concurrency Control

The central trick that lets readers and writers not block each other: instead of overwriting a row in place, an update creates a **new version** of the row, and old versions are kept around as long as some transaction might still need to see them. "MVCC" = each transaction sees a **snapshot** — a consistent view of the database as of a point in time — so reads never wait for writes and writes never wait for reads.

- **Postgres MVCC.** Every row version (a "tuple") carries hidden system columns `xmin` (the transaction id that created it) and `xmax` (the txn that deleted/superseded it). A transaction's **snapshot** is the set of txn ids visible to it. Visibility rule (simplified): a tuple is visible if `xmin` committed and is in the snapshot, and `xmax` is either empty or not committed/visible. An `UPDATE` writes a brand-new tuple and marks the old one's `xmax`. Dead tuples accumulate and must be reclaimed by **VACUUM** (see §3.7). This is why Postgres `UPDATE`s are effectively delete+insert and can bloat tables/indexes.
- **InnoDB MVCC.** Rows are updated in place in the clustered index, but each row carries a hidden `DB_TRX_ID` and `DB_ROLL_PTR`; **old versions are reconstructed from the undo log** on demand for a transaction's **read view**. Old undo is purged by background **purge threads** once no read view needs it. Effect: less table bloat than Postgres, but long-running transactions hold back purge and inflate the **undo/history list length**.

Consequence both engines share: **long-running transactions are toxic.** They pin old row versions (Postgres: prevent VACUUM from removing dead tuples → bloat; InnoDB: prevent undo purge → undo log growth, slower reads). A forgotten `BEGIN` in an idle connection can degrade the whole cluster.

### 3.5 The write & commit path (durability in motion)

For `UPDATE accounts SET balance = balance - 100 WHERE id = 1; COMMIT;`:

1. **Locate & lock the row.** The engine finds the row via the PK index and acquires a **row lock** (an exclusive lock for a write).
2. **Modify the in-memory page.** The page in the buffer pool is changed (now a **dirty page**). The change is *not* yet on disk in the data files.
3. **Write the WAL/redo record.** A log record describing the change is appended to the in-memory log buffer.
4. **On COMMIT, flush WAL.** The log up to this commit is **fsync**'d to durable storage. *This* is the durability point — once fsync returns, the commit is safe even if the server crashes before the data pages are written. (Group commit batches many transactions' fsyncs together for throughput.)
5. **Acknowledge commit** to the client.
6. **Background flush (checkpoint).** Later, a background writer/checkpointer flushes dirty data pages to the data files and advances the **checkpoint**, after which old WAL can be recycled.
7. **Crash recovery** (if it happens): on restart, replay WAL from the last checkpoint (**redo**) to re-apply committed changes that hadn't reached data files, and **undo** any uncommitted ones.

Tunables that trade durability for speed: Postgres `synchronous_commit` (off = ack before fsync, risking the last few ms of commits on crash but never corruption); MySQL `innodb_flush_log_at_trx_commit` (1 = fsync every commit, the durable default; 2 = flush to OS but fsync once/sec; 0 = even looser).

### 3.6 Isolation levels & the anomalies they prevent

The SQL standard defines four isolation levels by which **read anomalies** they forbid:

| Anomaly | What it is |
|---|---|
| **Dirty read** | Reading another transaction's *uncommitted* change. |
| **Non-repeatable read** | Re-reading a row in the same txn yields a different value (someone committed an update in between). |
| **Phantom read** | Re-running a range query yields new rows (someone committed an insert matching your predicate). |
| **Lost update** | Two txns read-modify-write the same row; one overwrites the other's change. |
| **Write skew** | Two txns read overlapping data, make disjoint writes that are each fine alone but together violate an invariant (only fully prevented by Serializable). |

| Level | Dirty read | Non-repeatable | Phantom | Notes |
|---|---|---|---|---|
| READ UNCOMMITTED | possible* | possible | possible | Postgres treats it as READ COMMITTED (never dirty-reads). |
| READ COMMITTED | no | possible | possible | **Postgres default.** Each *statement* sees a fresh snapshot. |
| REPEATABLE READ | no | no | no (PG)/no (InnoDB) | **MySQL/InnoDB default.** Snapshot fixed at txn start. Postgres RR = "snapshot isolation," prevents phantoms; InnoDB uses next-key locks to prevent them. |
| SERIALIZABLE | no | no | no | Behaves as if txns ran one at a time. |

Engine specifics worth knowing:
- **Postgres READ COMMITTED** (default): every statement gets a new snapshot, so within one transaction two `SELECT`s can see different data.
- **Postgres REPEATABLE READ** = true **snapshot isolation**: the whole transaction sees one snapshot taken at first query. Prevents non-repeatable and phantom reads, but **not write skew**. On a write conflict it raises `could not serialize access` (`40001`) — your app must **retry**.
- **Postgres SERIALIZABLE** = **SSI (Serializable Snapshot Isolation)**: snapshot isolation plus detection of dangerous read/write dependency cycles; aborts one txn with `40001` to guarantee true serializability. Cheap reads, but you must handle retries.
- **InnoDB REPEATABLE READ** (default) uses a **consistent read view** for plain `SELECT`s plus **next-key locking** (gap + record locks) for locking reads to prevent phantoms. Note InnoDB RR has a famous quirk: a "locking read" (`SELECT ... FOR UPDATE`) sees the *latest committed* row, not the snapshot — mixing snapshot and locking reads can surprise you.
- **`SELECT ... FOR UPDATE` / `FOR SHARE`** take explicit row locks to implement read-modify-write safely (pessimistic locking).

### 3.7 Locking, deadlocks, and lock granularity

- **Lock granularity:** row locks (most writes), page locks (some engines), table locks (DDL like `ALTER TABLE`, `TRUNCATE`), and metadata/advisory locks.
- **Lock modes:** **shared (S)** for reads-that-lock, **exclusive (X)** for writes. S/S compatible; anything with X conflicts.
- **InnoDB next-key locks:** a record lock + a **gap lock** on the range before it, used under REPEATABLE READ to prevent phantoms. Gap locks can cause surprising contention/deadlocks on range writes; they vanish under READ COMMITTED (a reason some shops run InnoDB at RC).
- **Deadlock:** txn A holds lock 1 and wants 2; txn B holds 2 and wants 1 — neither proceeds. The engine runs a **deadlock detector** (a wait-for graph cycle check) and aborts one transaction (the "victim," chosen as the cheaper-to-roll-back). Your app must **catch and retry** (`SQLState 40001`/`40P01`; MySQL error 1213). **Prevention:** always acquire locks in a consistent order; keep transactions short; lower isolation if safe.
- **Postgres VACUUM (the MVCC janitor):** reclaims space from dead tuples, updates the **visibility map** and **free space map**, and — critically — performs **transaction id wraparound prevention** by "freezing" old tuples. **Autovacuum** runs this automatically; if it falls behind (high write churn, long transactions blocking it), you get **table/index bloat** and, in the worst case, the dreaded **xid wraparound** shutdown ("database is not accepting commands to avoid wraparound data loss"). Tune `autovacuum_vacuum_scale_factor` (default **0.2** = vacuum after 20% of rows change) down for hot tables.

---

## 4. The complete toolkit

### 4.1 DDL — defining structure

| Statement | Purpose | Key options / notes |
|---|---|---|
| `CREATE TABLE` | Define a table | column types, `PRIMARY KEY`, `UNIQUE`, `CHECK`, `DEFAULT`, `NOT NULL`, `REFERENCES` (FK), `GENERATED ... AS IDENTITY` (PG) / `AUTO_INCREMENT` (MySQL) |
| `ALTER TABLE` | Change schema | `ADD/DROP COLUMN`, `ADD CONSTRAINT`, `ALTER COLUMN TYPE`. **Beware locks** — some forms rewrite the whole table and take exclusive locks. |
| `CREATE INDEX` | Add an index | `UNIQUE`, partial (`WHERE`), expression, multi-column, `INCLUDE` (PG), `USING btree/hash/gin/gist/brin` (PG), `USING BTREE/HASH` (MySQL) |
| `DROP INDEX` / `DROP TABLE` | Remove | — |
| `CREATE VIEW` / `MATERIALIZED VIEW` | Saved query / cached query result | Materialized must be `REFRESH`ed (PG). |
| `TRUNCATE` | Empty a table fast | Skips per-row delete & triggers; takes a strong lock; not MVCC-friendly to long readers. |

```sql
CREATE TABLE orders (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- surrogate PK, monotonic
  customer_id  BIGINT NOT NULL REFERENCES customers(id),         -- FK + referential integrity
  status       TEXT   NOT NULL CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED')),
  total        NUMERIC(12,2) NOT NULL CHECK (total >= 0),        -- exact decimal, never FLOAT for money
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_customer ON orders (customer_id);         -- speed up the FK join/lookup
CREATE INDEX idx_orders_status_created
  ON orders (status, created_at)                                  -- equality col first, range/sort last
  WHERE status <> 'CANCELLED';                                    -- PARTIAL index: skip dead rows
```

### 4.2 DML & query clauses

| Clause | Purpose | Gotchas |
|---|---|---|
| `INSERT ... ON CONFLICT` (PG) / `INSERT ... ON DUPLICATE KEY UPDATE` (MySQL) | Upsert | Requires a unique constraint to detect conflict. |
| `UPDATE ... FROM` / `UPDATE ... JOIN` | Update with a join | Syntax differs per engine. |
| `DELETE` | Remove rows | Large deletes = long txns + bloat; batch them. |
| `WHERE` | Row filter | Wrapping a column in a function (`WHERE lower(email)=...`) defeats a plain index unless you build an **expression index**. |
| `GROUP BY` / `HAVING` | Aggregate / filter groups | `HAVING` filters after aggregation. |
| Window functions (`OVER`, `PARTITION BY`, `ROW_NUMBER`, `RANK`, `LAG/LEAD`, running sums) | Per-row computation over a window without collapsing rows | Powerful for top-N-per-group, dedup, time-series. |
| CTEs (`WITH`) / recursive CTEs | Named subqueries / hierarchy traversal | Pre-PG12 CTEs were optimization fences; PG12+ inlines them unless `MATERIALIZED`. |
| `LIMIT ... OFFSET` | Pagination | **OFFSET is O(offset)** — slow deep pages; use **keyset/seek pagination** instead. |

```sql
-- Idempotent upsert (Postgres):
INSERT INTO inventory (sku, qty) VALUES ('A1', 10)
ON CONFLICT (sku) DO UPDATE SET qty = inventory.qty + EXCLUDED.qty;  -- atomic increment-or-create

-- Keyset pagination (fast even at page 10,000) instead of OFFSET:
SELECT id, created_at FROM orders
WHERE (created_at, id) < (:last_created_at, :last_id)   -- "seek" past the last seen row
ORDER BY created_at DESC, id DESC
LIMIT 50;
```

### 4.3 Transaction control

| Command | Purpose |
|---|---|
| `BEGIN` / `START TRANSACTION` | Start a transaction |
| `COMMIT` | Durably persist |
| `ROLLBACK` | Undo |
| `SAVEPOINT name` / `ROLLBACK TO name` | Nested partial rollback |
| `SET TRANSACTION ISOLATION LEVEL ...` | Choose isolation |
| `SELECT ... FOR UPDATE [NOWAIT|SKIP LOCKED]` | Pessimistic row lock; `SKIP LOCKED` powers queue/worker patterns |

### 4.4 Inspection & maintenance toolkit

| Tool / command | Engine | Purpose |
|---|---|---|
| `EXPLAIN` / `EXPLAIN (ANALYZE, BUFFERS, VERBOSE)` | Both | Show/measure the plan. `BUFFERS` shows cache hits/disk reads. |
| `ANALYZE` | PG | Refresh planner statistics. |
| `VACUUM (ANALYZE)` / `VACUUM FULL` | PG | Reclaim dead tuples; `FULL` rewrites & locks. |
| `pg_stat_statements` | PG | Aggregated query stats (top queries by total time) — install it. |
| `pg_stat_activity` | PG | Currently running queries, wait events, idle-in-transaction. |
| `pg_locks` | PG | Current locks (find blockers). |
| `auto_explain` | PG | Log plans of slow queries automatically. |
| `SHOW ENGINE INNODB STATUS` | MySQL | Last deadlock, buffer pool, transactions, semaphores. |
| `performance_schema` / `sys` schema | MySQL | Wait events, statement digests, IO. |
| `slow query log` | MySQL | Log queries over `long_query_time`. |
| `pt-query-digest` (Percona Toolkit) | MySQL | Analyze slow log / tcpdump for top queries. |
| `EXPLAIN ANALYZE FORMAT=TREE` / `EXPLAIN FORMAT=JSON` | MySQL 8 | Detailed plans with actuals. |

### 4.5 Java/JDBC & pooling toolkit

| API / setting | Purpose / default |
|---|---|
| `java.sql.Connection`, `PreparedStatement`, `ResultSet` | Core JDBC. **Always use `PreparedStatement`** (server-side parameter binding) to prevent SQL injection and enable plan reuse. |
| `Connection.setAutoCommit(false)` | Group statements into a transaction; default is autocommit=true (each statement its own txn). |
| `Statement.setFetchSize(n)` | Rows fetched per round trip. Default differs; Postgres JDBC defaults to fetching **all rows** unless autocommit is off AND fetchSize is set — a classic OOM on huge result sets. |
| `Connection.setTransactionIsolation(...)` | Per-connection isolation. |
| **HikariCP** (`maximumPoolSize`, `minimumIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime`, `leakDetectionThreshold`) | The de facto JVM connection pool. Defaults: `maximumPoolSize=10`, `connectionTimeout=30s`, `idleTimeout=600s`, `maxLifetime=1800s`. |
| JPA/Hibernate (`@Transactional`, fetch strategies, `@BatchSize`, `JOIN FETCH`, `hibernate.jdbc.batch_size`) | ORM; primary source of N+1 problems if misused (§6). |

---

## 5. Code examples by use case

### 5.1 Safe money transfer (transaction + pessimistic lock + retry), Java/JDBC

```java
// Transfer money atomically between two accounts, deadlock-safe.
void transfer(DataSource ds, long from, long to, BigDecimal amount) throws SQLException {
    int attempts = 0;
    while (true) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);                                   // begin transaction
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            // Lock rows in a CONSISTENT ORDER (lowest id first) to avoid deadlocks:
            long first = Math.min(from, to), second = Math.max(from, to);
            lockRow(c, first);
            lockRow(c, second);

            BigDecimal bal = balanceOf(c, from);
            if (bal.compareTo(amount) < 0) { c.rollback(); throw new IllegalStateException("insufficient"); }

            update(c, from, amount.negate());                         // debit
            update(c, to,   amount);                                  // credit
            c.commit();                                               // durable, atomic
            return;
        } catch (SQLException e) {
            // 40001 = serialization failure, 40P01 = deadlock (Postgres). Retry with backoff.
            if (("40001".equals(e.getSQLState()) || "40P01".equals(e.getSQLState())) && attempts++ < 3) {
                sleepBackoff(attempts);
                continue;
            }
            throw e;
        }
    }
}
private void lockRow(Connection c, long id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM accounts WHERE id = ? FOR UPDATE")) {     // pessimistic X lock
        ps.setLong(1, id); ps.executeQuery();
    }
}
```

### 5.2 Optimistic locking (no row locks; version column) — high-contention web update

```sql
-- Schema has a version column:
-- ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE products
SET price = :newPrice, version = version + 1
WHERE id = :id AND version = :expectedVersion;   -- succeeds only if nobody changed it since we read
-- If row count == 0 -> someone else won; reload and retry (or surface a conflict to the user).
```
This is what JPA `@Version` does under the hood — it appends `AND version = ?` to updates and throws `OptimisticLockException` when zero rows are affected. Prefer optimistic locking for web edit forms (low real conflict rate); prefer pessimistic (`FOR UPDATE`) for hot-row contention like seat/inventory reservation.

### 5.3 A reliable job queue using `SKIP LOCKED` (avoid the classic "two workers grab the same job")

```sql
-- Each worker atomically claims up to N unprocessed jobs without blocking other workers:
UPDATE jobs
SET status = 'PROCESSING', locked_by = :workerId, locked_at = now()
WHERE id IN (
    SELECT id FROM jobs
    WHERE status = 'PENDING'
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED        -- skip rows other workers already locked
    LIMIT 10
)
RETURNING id, payload;            -- PG: return the claimed work in one round trip
```
`FOR UPDATE SKIP LOCKED` (Postgres 9.5+, MySQL 8+) turns a relational table into a competent work queue without a separate broker — workers never block each other and never double-process.

### 5.4 Diagnosing and fixing a slow query with EXPLAIN ANALYZE

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.id, o.total
FROM orders o
WHERE o.customer_id = 42 AND o.status = 'PAID'
ORDER BY o.created_at DESC
LIMIT 20;
```
Reading the output, you look for:
- **`Seq Scan` on a large table** where you expected an index → missing/unused index or stale stats.
- **Estimated rows vs actual rows wildly off** (e.g. `rows=1` estimated, `rows=50000` actual) → bad statistics; run `ANALYZE`, consider extended statistics.
- A **`Sort`** node with `external merge Disk: ...kB` → sort spilled to disk; either add an index matching the `ORDER BY` or raise `work_mem`.
- **`Rows Removed by Filter: 999000`** → the index isn't selective enough or a predicate isn't index-backed.

Fix here: a composite index lets the planner satisfy filter + order + limit cheaply:
```sql
CREATE INDEX idx_orders_cust_status_created
  ON orders (customer_id, status, created_at DESC);   -- equality, equality, then sort column
```

### 5.5 Killing the N+1 problem in Hibernate/JPA

```java
// ANTI-PATTERN: triggers 1 query for orders + N queries for each order's customer.
List<Order> orders = em.createQuery("SELECT o FROM Order o", Order.class).getResultList();
for (Order o : orders) o.getCustomer().getName();   // lazy load -> N extra SELECTs

// FIX 1: JOIN FETCH — one query loads orders + customers together.
List<Order> orders = em.createQuery(
    "SELECT o FROM Order o JOIN FETCH o.customer", Order.class).getResultList();

// FIX 2 (collections): use @BatchSize or a separate "in-clause" query to avoid Cartesian blow-up
//   when fetching multiple collections; JOIN FETCH on >1 collection multiplies rows.
```
Detect N+1 by counting SQL statements per request (enable `hibernate.show_sql` in dev, or use a query counter / datasource-proxy in tests and **fail the test if a request issues > K queries**).

### 5.6 Range partitioning a large time-series table (Postgres declarative partitioning)

```sql
CREATE TABLE events (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB,
    PRIMARY KEY (id, occurred_at)            -- partition key MUST be in the PK/unique constraint
) PARTITION BY RANGE (occurred_at);

CREATE TABLE events_2026_06 PARTITION OF events
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE events_2026_07 PARTITION OF events
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

-- Queries with a predicate on occurred_at get "partition pruning": only relevant partitions scanned.
-- Dropping old data is instant: DROP TABLE events_2026_06 (vs a slow, bloat-causing DELETE).
```

### 5.7 Correct HikariCP + transactional service (Spring)

```java
@Bean
public HikariDataSource dataSource() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl("jdbc:postgresql://db:5432/app");
    cfg.setMaximumPoolSize(20);            // ~= cores*2..4 + effective_spindles; START SMALL, measure
    cfg.setMinimumIdle(20);                // keep pool warm to avoid latency spikes
    cfg.setConnectionTimeout(3_000);       // fail fast if pool exhausted (don't hang requests)
    cfg.setMaxLifetime(1_800_000);         // < DB/network idle timeout to avoid stale conns
    cfg.setLeakDetectionThreshold(10_000); // log connections held > 10s (find leaks)
    return new HikariDataSource(cfg);
}

@Service
class TransferService {
    @Transactional                          // one connection, one txn for the whole method
    public void place(Order o) {
        orders.save(o);                     // do NOT call slow remote APIs inside @Transactional —
        inventory.decrement(o.sku(), o.qty()); // it holds the connection & locks open
    }
}
```

---

## 6. Implementation concerns & best practices

**Performance**
- **Index the columns you filter, join, and sort on** — but only those; every index taxes writes. Drop unused indexes (find them via `pg_stat_user_indexes` `idx_scan = 0`, or MySQL `sys.schema_unused_indexes`).
- **Composite index order:** equality predicates first, range/sort column last; most selective equality first.
- **Avoid SELECT \*** in hot paths — it defeats covering indexes and ships unneeded bytes.
- **Avoid functions on indexed columns** in `WHERE` (`WHERE date(created_at)=...`); use range predicates or an **expression index**.
- **Batch writes** (`hibernate.jdbc.batch_size`, JDBC `addBatch`/`executeBatch`, multi-row `INSERT`) — round trips dominate at scale.
- **Keyset pagination over OFFSET** for deep pages.
- **Keep result sets streamable** — set fetch size + autoCommit(false) on Postgres JDBC for large reads, or you'll buffer everything in heap.

**Correctness / concurrency**
- Pick the isolation level deliberately; under READ COMMITTED, **read-modify-write needs `FOR UPDATE` or optimistic versioning** or you get lost updates.
- **Always retry on `40001`/deadlock** — these are normal, expected outcomes, not bugs.
- Use **FK constraints, CHECK constraints, and `NOT NULL`** — let the DB be the last line of defense; application validation alone leaks bad data via every code path you forgot.
- Use **`NUMERIC`/`DECIMAL` for money**, never `FLOAT/DOUBLE` (binary float can't represent 0.10 exactly).
- Mind **NULL semantics** in `NOT IN (subquery)` (a single NULL in the subquery makes the whole thing return no rows) — prefer `NOT EXISTS`.

**Memory** — size `shared_buffers`/`innodb_buffer_pool_size` to hold the working set; `work_mem` (PG, **per sort/hash node per query**, default 4 MB — easy to multiply into OOM under concurrency); InnoDB buffer pool typically **50–75% of RAM** on a dedicated DB host.

**Security** — parameterized queries always (no string concatenation → no SQL injection); least-privilege DB roles; encrypt in transit (TLS) and at rest; avoid putting secrets in tables in plaintext; restrict superuser; use row-level security (PG RLS) for multi-tenant isolation when appropriate.

**Cost** — over-indexing inflates storage and write IO; idle/leaked connections inflate memory (each PG backend is a process costing several MB); oversized instances waste money — right-size by the working-set-in-RAM target.

**Observability** — enable `pg_stat_statements` / slow query log on day one; track p99 query latency, lock wait time, buffer cache hit ratio, replication lag, transaction age (`xact_start`), connections-in-use vs pool size, and "idle in transaction" count.

**Testability** — test against the **same engine** you run in prod (use Testcontainers to spin a real Postgres/MySQL), not H2 (its SQL dialect diverges and hides bugs). Assert on query counts to catch N+1 regressions.

**Production hardening** — connection pooling with sane limits + `connectionTimeout`; statement timeouts (`statement_timeout`, `idle_in_transaction_session_timeout`) so a runaway query can't pin resources forever; read replicas for read scaling; automated, *tested* backups + PITR (point-in-time recovery via WAL archiving); schema migrations applied with a tool (Flyway/Liquibase) and reviewed for locking impact.

**Common anti-patterns** — N+1 queries; missing indexes on FK/filter columns; over-indexing; long/idle-in-transaction transactions; `OFFSET` pagination; storing money as float; EAV (entity-attribute-value) schemas; SELECT \* everywhere; unbounded `IN (...)` lists; doing slow external I/O inside a DB transaction; ignoring `ANALYZE`/stale stats; one giant connection pool per app instance multiplied across 50 pods overwhelming the DB.

---

## 7. Advanced topics & deep internals

**Tuning the planner.** `random_page_cost` (lower to ~1.1 on SSD to favor index scans), `effective_cache_size` (tell the planner how much OS+DB cache exists — purely an estimate input, doesn't allocate), `default_statistics_target` (raise for skewed columns to get finer histograms), **extended/multivariate statistics** (`CREATE STATISTICS` for correlated columns like `(city, state)` so the planner stops assuming independence). `cpu_tuple_cost`, `cpu_index_tuple_cost` fine-tune CPU vs IO.

**Bitmap index scans (Postgres).** When a single index isn't selective enough but several together are, Postgres can scan multiple indexes, build **bitmaps** of matching page locations, combine them (AND/OR), then fetch heap pages in physical order — efficient for multi-predicate queries.

**HOT updates (Heap-Only Tuples, Postgres).** If an `UPDATE` doesn't touch any indexed column *and* there's room on the same page, Postgres can update without writing new index entries (a "HOT" update), drastically reducing index bloat. Leave `fillfactor` headroom on hot-updated tables to enable it.

**InnoDB change buffer.** For secondary-index maintenance on non-unique indexes when the target page isn't in the buffer pool, InnoDB buffers the change and merges it later — speeds up bulk writes but can cause a recovery/merge spike.

**InnoDB adaptive hash index.** InnoDB builds an in-memory hash index over frequently accessed B-tree pages automatically, turning some B-tree lookups into O(1); occasionally a contention hotspot under certain workloads (can be disabled).

**Index types beyond B-tree (Postgres):** **GIN** (inverted index for arrays, JSONB, full-text), **GiST** (geometric/range/nearest-neighbor), **SP-GiST**, **BRIN** (block range index — tiny index for naturally ordered huge tables like append-only time series; stores min/max per block range), **Hash** (equality only). MySQL: B-tree everywhere, plus **fulltext** and **spatial (R-tree)** indexes; memory engine supports hash.

**Partitioning depth.** Range (time), list (region/tenant), hash (even spread). Benefits: **partition pruning** (skip irrelevant partitions), instant old-data drop, smaller per-partition indexes, parallelism. Costs: the **partition key must be in every unique/PK constraint**; cross-partition queries and global uniqueness are harder; too many partitions hurts planning time. Postgres declarative partitioning matured in PG11–14 (partition-wise joins/aggregates, hash partitioning, default partition). MySQL has native partitioning but with notable limitations (no FKs on partitioned tables).

**Replication & read scaling.** **Streaming/physical replication** (ship WAL/binlog to replicas) gives read replicas and HA; replicas can serve reads but with **replication lag** (a read after write may not see your write — beware "read your writes" anomalies; route critical reads to primary or use `synchronous_commit = remote_apply`/semi-sync). **Logical replication** (row-level, selective tables) enables zero-downtime upgrades and selective sync.

**MVCC bloat math (Postgres).** A table with heavy updates accumulates dead tuples between vacuums; if autovacuum can't keep up (blocked by a long transaction, or scale factor too high), bloat grows unbounded, queries scan dead space, and indexes balloon. Monitor with `pg_stat_user_tables.n_dead_tup` and bloat-estimation queries; remediate with more aggressive autovacuum settings or `pg_repack` (rebuild online, unlike `VACUUM FULL` which takes an exclusive lock).

**Transaction ID wraparound (Postgres).** XIDs are 32-bit; after ~2 billion the counter wraps and old data could appear "in the future." Autovacuum "freezes" old tuples to prevent this; if it falls catastrophically behind, Postgres enters a protective read-only-ish mode. Monitor `age(datfrozenxid)`. This is a genuine, famous production-killer (multiple public outages).

**Prepared statement plan caching & generic plans (Postgres).** After 5 executions of a prepared statement, Postgres may switch from a **custom plan** (re-planned each time with actual parameter values) to a **generic plan** (planned once, parameter-agnostic). On skewed data a generic plan can be terrible; control with `plan_cache_mode = force_custom_plan`.

---

## 8. Tradeoffs & decision frameworks

### Relational vs alternatives

| Need | Relational (PG/MySQL) | Document (Mongo) | Key-Value (Redis/Dynamo) | Wide-column (Cassandra) | Graph (Neo4j) |
|---|---|---|---|---|---|
| Multi-row ACID transactions | **Excellent** | Limited (improving) | Weak/none | Per-partition only | Good |
| Ad-hoc/flexible queries & joins | **Excellent** | Moderate | Poor | Poor (query by partition key) | Excellent (traversal) |
| Schema flexibility | Moderate (use JSONB) | **Excellent** | High | High | Moderate |
| Horizontal write scaling | Hard (shard/partition) | Good | **Excellent** | **Excellent** | Moderate |
| Deep relationship traversal | Moderate (recursive CTE) | Poor | Poor | Poor | **Excellent** |
| Strong consistency default | **Yes** | Tunable | Often eventual | Tunable (often eventual) | Yes |

**Use relational when…** you need transactional correctness, you have relationships and want joins, your access patterns aren't fully known, your data fits a single node (or shards cleanly by tenant/time), and you want mature tooling/operability. This is **most OLTP applications** — make it the default.

**Avoid / supplement relational when…** you need massive multi-region write throughput with eventual consistency tolerance (→ Cassandra/Dynamo), your data is genuinely schemaless and document-shaped with no cross-doc transactions (→ document store), graph traversal is the dominant query (→ graph DB), or you need specialized search/vector/time-series at scale (→ Elasticsearch/dedicated TSDB) — often as a satellite to a relational source of truth.

### Postgres vs MySQL/InnoDB (senior cheat comparison)

| Dimension | PostgreSQL | MySQL (InnoDB) |
|---|---|---|
| Default isolation | READ COMMITTED | REPEATABLE READ |
| Storage model | Heap + all-secondary indexes; MVCC via dead tuples + VACUUM | Clustered index (table = PK B+-tree); MVCC via undo log + purge |
| Secondary index leaf points to | Heap TID | PK value (extra clustered lookup) |
| Phantom prevention at RR | Snapshot isolation | Next-key (gap) locks |
| Index types | B-tree, GIN, GiST, BRIN, SP-GiST, Hash | B-tree, fulltext, spatial; (hash in MEMORY) |
| JSON | JSONB (indexable, rich) | JSON (functional indexes) |
| Notable footgun | XID wraparound / VACUUM bloat | Gap-lock deadlocks; PK bloat in secondaries; RR locking-read quirk |

### Normalization vs denormalization

- **Normalize** (3NF: no redundant data, every non-key attribute depends on the key, the whole key, and nothing but the key) to eliminate **update anomalies** and keep one source of truth. Default for OLTP.
- **Denormalize** *deliberately* for read-heavy paths: precompute aggregates, duplicate a hot column to skip a join, use materialized views. The cost is keeping copies consistent (triggers, app logic, or scheduled refresh). Rule: normalize first, denormalize with evidence (a measured hot query), and document why.

### Pessimistic vs optimistic locking

| | Pessimistic (`FOR UPDATE`) | Optimistic (`@Version`) |
|---|---|---|
| Best for | High real contention on a hot row | Low conflict rate (web edit forms) |
| Cost | Holds locks → reduces concurrency, deadlock risk | Retries on conflict; wasted work if conflicts are common |
| Visibility to user | Blocks/serializes | Surfaces "someone else changed this" |

---

## 9. Failure modes & debugging

**1. Mysteriously slow query / sudden plan regression.**
*Symptom:* a query that was fast now takes seconds. *Cause:* stale statistics after a bulk load, data skew, or generic-plan flip. *Diagnose:* `EXPLAIN (ANALYZE, BUFFERS)` — compare estimated vs actual rows; a huge gap means bad stats. *Fix:* `ANALYZE table;`, add/extend statistics, add the right composite index, or pin a custom plan.

**2. Connection pool exhaustion / "too many clients."**
*Symptom:* requests time out waiting for a connection; Postgres logs `FATAL: sorry, too many clients`. *Cause:* pool too large × many pods exceeding `max_connections` (PG default ~**100**), or connection leaks (not closing), or slow queries holding connections. *Diagnose:* `pg_stat_activity` (count by state; look for `idle in transaction`), Hikari `leakDetectionThreshold` logs. *Fix:* right-size pools, set `connectionTimeout` to fail fast, add `idle_in_transaction_session_timeout`, introduce a server-side pooler (**PgBouncer** in transaction mode) to multiplex many app connections onto few DB connections.

**3. Lock contention / deadlocks.**
*Symptom:* throughput collapses; errors `deadlock detected` (PG `40P01`) / MySQL 1213. *Diagnose:* PG — `pg_locks` joined to `pg_stat_activity` to find blocker→blocked chains; `pg_blocking_pids()`. MySQL — `SHOW ENGINE INNODB STATUS` shows the latest deadlock with both transactions' held/wanted locks. *Fix:* consistent lock ordering, shorter transactions, lower isolation (RC to drop gap locks), index the predicate to lock fewer rows, retry logic.

**4. Idle-in-transaction bloat / VACUUM can't keep up (Postgres).**
*Symptom:* table/index size grows, queries slow, `n_dead_tup` high. *Cause:* a long-running or leaked open transaction pins the oldest snapshot, preventing dead-tuple removal. *Diagnose:* `SELECT now()-xact_start, * FROM pg_stat_activity ORDER BY xact_start;` find the ancient transaction; check `n_dead_tup`. *Fix:* kill the offending session (`pg_terminate_backend`), set `idle_in_transaction_session_timeout`, tune autovacuum more aggressively, `pg_repack` to reclaim space online.

**5. The N+1 explosion.**
*Symptom:* one user request issues hundreds of tiny SELECTs; DB CPU and latency spike under load. *Diagnose:* SQL log / `pg_stat_statements` shows a huge `calls` count for a tiny single-row query. *Fix:* `JOIN FETCH`/batch fetching/`IN`-clause loading; add a per-request query-count assertion in tests.

**6. Sort/hash spilling to disk.**
*Symptom:* `EXPLAIN ANALYZE` shows `external merge Disk` or hash batches; query slow under concurrency. *Fix:* index to provide order (avoid the sort), reduce returned columns, raise `work_mem` cautiously (it's per-node-per-query).

**7. Replication lag causing stale reads.**
*Symptom:* user updates a record, immediately reloads, sees old data (read routed to a lagging replica). *Diagnose:* monitor `pg_stat_replication` lag / MySQL `Seconds_Behind_Master`. *Fix:* route read-after-write to primary, use synchronous replication for critical paths, or add app-level "sticky to primary for N seconds."

**8. XID wraparound emergency (Postgres).**
*Symptom:* `database is not accepting commands to avoid wraparound data loss`. *Cause:* autovacuum freezing fell far behind (often due to a stuck long transaction or disabled autovacuum). *Fix:* in single-user mode, run aggressive `VACUUM`; resolve whatever blocked autovacuum; monitor `age(datfrozenxid)` proactively. Real outages (publicly documented at several large companies) have stemmed from this.

**Real-world pattern:** the most common production incident is not exotic — it's **a missing index plus stale statistics under a sudden traffic increase**, which flips the planner to a sequential scan, which saturates IO, which makes every query slow, which exhausts the connection pool, which takes the service down. The fix chain — index, `ANALYZE`, pool limits, statement timeout — prevents most of these.

---

## 10. Interview drill

**Q1. Walk me through what happens when I run a `SELECT` with a `WHERE` and a `JOIN`.**
*Model answer:* Parse → bind/analyze (resolve names, types, permissions) → rewrite (expand views, flatten subqueries) → cost-based optimize (choose access paths per table, join order and algorithm using statistics) → execute (iterator/Volcano model pulling rows up the operator tree, served by the buffer pool) → stream results.
*Probes:* (a) *What's the iterator model?* Each operator implements open/next/close and pulls from children one row/batch at a time. (b) *Where do statistics come from?* `ANALYZE` samples rows into histograms + n_distinct; the planner estimates cardinalities. (c) *Why might the planner pick a seq scan over an index?* When estimated matching rows are a large fraction of the table, random index+heap fetches cost more than a sequential scan.

**Q2. Explain B+-tree indexes and why databases use them over hash or binary trees.**
*Model answer:* High-fanout balanced tree; node=page so the tree is shallow (few disk reads); leaves linked in sorted order so range scans and ordered reads are efficient; supports `=`, ranges, prefix, and `ORDER BY`. Hash indexes only do equality and don't support ranges/ordering; binary trees are too deep for disk.
*Probes:* (a) *Clustered vs secondary?* InnoDB clustered = table is the PK tree (leaf holds full row); secondary leaf holds PK → extra lookup. Postgres is heap + all-secondary. (b) *Covering index?* Index contains all needed columns → index-only scan, no table fetch. (c) *Why is column order in a composite index critical?* Leftmost-prefix rule; equality first, range/sort last.

**Q3. What are the ACID properties and how does the DB implement each?**
*Model answer:* Atomicity (undo log/rollback), Consistency (constraints + correct logic), Isolation (MVCC + locking + isolation levels), Durability (WAL/redo fsync'd before commit ack).
*Probes:* (a) *What's WAL and why?* Sequential log of changes fsync'd before data pages; fast + crash-recoverable via redo/undo. (b) *Does COMMIT mean data pages are on disk?* No — only the WAL is durably flushed; data pages flush later at checkpoint. (c) *How can you trade durability for speed?* `synchronous_commit=off` / `innodb_flush_log_at_trx_commit=2`.

**Q4. Explain isolation levels and the anomalies each prevents. What are the defaults?**
*Model answer:* RU/RC/RR/SER preventing progressively more of dirty/non-repeatable/phantom reads. Postgres default RC; MySQL/InnoDB default RR. Postgres RR = snapshot isolation (no phantoms, but write skew possible); SERIALIZABLE = SSI.
*Probes:* (a) *What's write skew and which level stops it?* Two txns read overlapping data, write disjointly, jointly violating an invariant; only SERIALIZABLE. (b) *How does InnoDB prevent phantoms at RR?* Next-key (gap) locks. (c) *What must your app do under Postgres SERIALIZABLE/RR?* Catch `40001` and retry.

**Q5. What is MVCC and what's its main operational cost?**
*Model answer:* Multiple row versions + per-transaction snapshots so readers don't block writers. Postgres keeps dead tuples (cleaned by VACUUM); InnoDB reconstructs old versions from the undo log (cleaned by purge). Cost: long transactions prevent cleanup → bloat / undo growth.
*Probes:* (a) *Postgres dead tuple lifecycle?* UPDATE = new tuple + old marked; VACUUM reclaims when no snapshot needs it. (b) *XID wraparound?* 32-bit XIDs; freezing prevents wrap; falling behind = protective shutdown. (c) *Why are long transactions toxic cluster-wide?* They pin the oldest snapshot for everyone.

**Q6. How do you find and fix a slow query?** *(senior-signal)*
*Model answer:* Reproduce with `EXPLAIN (ANALYZE, BUFFERS)`; compare estimated vs actual rows (gap → stats issue), look for seq scans on big tables, disk-spilling sorts, high "rows removed by filter." Fix in order: refresh/extend statistics, add/adjust the right (composite, possibly covering) index, rewrite the query (avoid functions on indexed cols, keyset pagination), and only then touch memory/cost params. Verify with the same EXPLAIN.
*Probes:* (a) *When is adding an index the wrong fix?* Low selectivity, write-heavy table, or the real problem is stale stats. (b) *How do you decide composite index column order?* Equality columns first (most selective), range/sort last. (c) *What if estimates are off due to correlated columns?* `CREATE STATISTICS` (extended/multivariate).

**Q7. Your service intermittently throws "too many connections." Diagnose and design a fix.** *(senior-signal)*
*Model answer:* Map it: pool size × pod count vs DB `max_connections`; check `pg_stat_activity` for `idle in transaction` (leaks / slow external calls inside txns) and long queries. Fix: right-size pools (small! the DB has limited cores), `connectionTimeout` to fail fast, `idle_in_transaction_session_timeout`, remove I/O from transactions, and introduce **PgBouncer** (transaction pooling) so thousands of app connections multiplex onto ~tens of DB connections.
*Probes:* (a) *Why is a bigger pool often worse?* Connections beyond the DB's parallel capacity just queue inside the DB and add context-switch overhead; throughput is bounded by cores/IO. (b) *Transaction vs session pooling in PgBouncer?* Transaction pooling reuses a server conn per transaction (max efficiency) but breaks session-scoped features (prepared statements, advisory locks, `SET`). (c) *Hikari settings that matter?* maximumPoolSize, connectionTimeout, maxLifetime (< server idle timeout), leakDetectionThreshold.

**Q8. When would you NOT choose a relational database, and why is it still your default?** *(senior-signal)*
*Model answer:* Default because of ACID, joins/ad-hoc queries, integrity constraints, and operational maturity — fits most OLTP. Move away when validated: planet-scale multi-region writes with eventual-consistency tolerance (Cassandra/Dynamo), schemaless document workloads without cross-doc transactions (Mongo), graph-traversal-dominant queries (Neo4j), or specialized search/time-series at scale — often keeping relational as the system of record.
*Probes:* (a) *Can't you scale relational horizontally?* Yes via read replicas (reads), partitioning, and sharding by tenant/time — but cross-shard transactions/joins get hard; it's a deliberate trade. (b) *JSONB blurs the line — when use it?* For sparse/variable attributes within an otherwise relational model; index with GIN; don't abandon schema for everything. (c) *What's the risk of premature polyglot persistence?* Operational complexity, dual-write consistency bugs, more failure modes — earn each datastore with evidence.

**Q9. Explain the N+1 problem and three ways to prevent it.**
*Model answer:* One query loads N parents, then N more queries load each child (lazy loading). Fixes: `JOIN FETCH`/eager join, batch fetching (`@BatchSize`/`IN (...)`), or a projection/DTO query. Detect via per-request query counting.
*Probes:* (a) *Why not always eager fetch?* Cartesian explosion with multiple collections, and over-fetching. (b) *How do you catch regressions?* Assert query count in integration tests (Testcontainers + datasource-proxy). (c) *Caching's role?* Second-level/query cache reduces it but adds invalidation complexity.

**Q10. Normalization vs denormalization — how do you decide?**
*Model answer:* Default to 3NF for OLTP (one source of truth, no update anomalies). Denormalize deliberately for a measured read-hot path (precomputed aggregates, materialized views, duplicated hot columns), accepting the consistency-maintenance cost. Document the reason.
*Probes:* (a) *What's an update anomaly?* Redundant data getting out of sync on partial updates. (b) *Materialized view vs trigger-maintained column?* MV is simpler but stale until refresh; trigger is live but adds write cost/complexity. (c) *Is JSONB denormalization?* It can embed related data; fine for read-mostly nested data, risky if that data is independently updated/queried.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability: the transactional guarantees.
- **Anti-join** — rows in A with no match in B (`NOT EXISTS`).
- **Autovacuum** — Postgres background process that runs VACUUM/ANALYZE automatically.
- **B+-tree** — balanced, high-fanout search tree; keys in linked leaves; the standard index structure.
- **Bitmap scan** — Postgres technique combining multiple index results into page bitmaps before heap fetch.
- **BRIN** — Block Range INdex; tiny index storing per-block min/max, for naturally ordered huge tables.
- **Buffer pool / shared buffers** — in-memory cache of data pages.
- **Cardinality** — number of rows (or, in estimation, the predicted row count of an operation).
- **Checkpoint** — point at which dirty pages are flushed so older WAL can be recycled.
- **Clustered index** — index whose leaves contain the full rows (InnoDB PK); the table physically ordered by it.
- **Covering index / index-only scan** — index containing all columns a query needs, avoiding table access.
- **CTE (Common Table Expression)** — a named subquery (`WITH`); recursive form traverses hierarchies.
- **Deadlock** — circular lock wait; the engine aborts one transaction (the victim).
- **Denormalization** — deliberately introducing redundancy for read performance.
- **Dirty read** — reading uncommitted data from another transaction.
- **EXPLAIN / EXPLAIN ANALYZE** — show the planned / actually-executed query plan.
- **fsync** — syscall forcing OS write buffers to physical storage; the durability primitive.
- **Foreign key (FK)** — column referencing another table's PK, enforcing referential integrity.
- **Gap lock / next-key lock** — InnoDB locks on ranges to prevent phantoms under REPEATABLE READ.
- **GIN / GiST** — Postgres index types for arrays/JSON/full-text (GIN) and geometric/range/NN (GiST).
- **Hash join** — join via an in-memory hash table on the smaller input.
- **Heap** — Postgres unordered row storage; indexes point to heap TIDs.
- **HOT update** — Postgres update that avoids new index entries when no indexed column changes.
- **Index** — auxiliary sorted structure mapping values → row locations.
- **Isolation level** — degree to which concurrent transactions are shielded from each other.
- **JDBC** — Java Database Connectivity; the standard Java DB API.
- **Keyset/seek pagination** — paginating with a `WHERE (col) < lastSeen` predicate instead of OFFSET.
- **Lost update** — two transactions' writes clobber each other.
- **Materialized view** — a stored, refreshable cache of a query's result.
- **MVCC** — Multi-Version Concurrency Control; per-transaction snapshots over multiple row versions.
- **Nested loop join** — for each outer row, probe the inner (usually via index).
- **Normalization (3NF)** — structuring data to remove redundancy/update anomalies.
- **N+1 problem** — one query for parents plus one per child; an ORM performance anti-pattern.
- **NULL** — unknown/inapplicable marker; participates in three-valued logic.
- **Optimistic locking** — conflict detection via a version column at write time.
- **Partition pruning** — skipping partitions that can't match a query's predicate.
- **Pessimistic locking** — taking row locks up front (`FOR UPDATE`).
- **PgBouncer** — lightweight Postgres connection pooler/multiplexer.
- **Phantom read** — a re-run range query returns new rows.
- **Planner / optimizer** — chooses the physical execution plan from cost estimates.
- **Prepared statement** — pre-parsed, parameterized query; prevents injection, enables plan reuse.
- **Primary key (PK)** — unique, non-null row identifier.
- **Purge (InnoDB)** — background reclamation of old undo/row versions.
- **Selectivity** — fraction of rows a predicate matches (lower = more selective = better for indexing).
- **Serializable / SSI** — strongest isolation; Postgres uses Serializable Snapshot Isolation.
- **Statistics** — sampled metadata (histograms, n_distinct) the planner uses to estimate cardinalities.
- **Surrogate key** — synthetic PK with no business meaning.
- **TID** — Postgres tuple id (page + offset) that indexes point to.
- **Transaction** — atomic unit of work.
- **Undo log** — record enabling rollback and (InnoDB) MVCC version reconstruction.
- **VACUUM** — Postgres reclamation of dead tuples and freezing of old XIDs.
- **Volcano/iterator model** — execution where operators pull rows via open/next/close.
- **WAL / redo log** — write-ahead log providing durability and crash recovery.
- **work_mem** — Postgres per-operation memory budget for sorts/hashes.
- **Write skew** — disjoint writes on overlapping reads that jointly break an invariant.
- **XID wraparound** — Postgres 32-bit transaction id exhaustion hazard, prevented by freezing.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Three actors:** Planner (cost-based, needs statistics) · Referee (transactions + locking + MVCC) · B+-tree (the index).

**Key numbers/defaults:**
- Page size: Postgres 8 KB, InnoDB 16 KB.
- Default isolation: **Postgres READ COMMITTED**, **MySQL/InnoDB REPEATABLE READ**.
- Postgres `random_page_cost` 4.0 (lower to ~1.1 on SSD); `seq_page_cost` 1.0; `work_mem` 4 MB (per node!); `default_statistics_target` 100; `autovacuum_vacuum_scale_factor` 0.2; `max_connections` ~100.
- InnoDB buffer pool ≈ 50–75% RAM; `innodb_flush_log_at_trx_commit` 1 = durable.
- HikariCP defaults: pool 10, connectionTimeout 30 s, maxLifetime 30 min.
- B+-tree depth for ~1B rows ≈ 3–4 levels.

**Decision rules:**
- Relational is the **default** for OLTP; switch only with validated need.
- Composite index: **equality columns first, range/sort last; most selective first.**
- Money → `NUMERIC`, never float. PK → small surrogate, monotonic.
- Read-modify-write under RC → `FOR UPDATE` or `@Version`; **always retry 40001/deadlock.**
- Long/idle transactions are toxic — keep them short, no external I/O inside.
- Deep pages → **keyset pagination**, not OFFSET.
- First debugging move: **`EXPLAIN (ANALYZE, BUFFERS)`** + check estimated vs actual rows.
- Pool small; multiplex with PgBouncer; set `connectionTimeout` + statement/idle timeouts.

**Anti-pattern hit-list:** N+1 · missing FK/filter index · over-indexing · long txns · OFFSET paging · float money · SELECT * · slow I/O in txn · stale stats · giant pools.

### Self-test (no answers — recall actively)

1. Explain, in order, every stage a `SELECT` passes through from text to result rows, and name what `EXPLAIN ANALYZE` reveals about stage 4.
2. Why does InnoDB make a fat primary key expensive across *all* secondary indexes, and how does Postgres's storage model differ?
3. Given `WHERE a = ? AND b > ? ORDER BY c`, design the best composite index and justify the column order. When would a skip scan help?
4. Contrast how Postgres and InnoDB implement MVCC, and explain precisely why a single forgotten open transaction can degrade the entire cluster in each.
5. Your write-heavy table's queries slowed overnight after a bulk load. List your diagnostic steps in order and the most likely root cause.
6. When does the planner correctly prefer a sequential scan over an available index, and how do `random_page_cost`/statistics influence that choice?
7. Describe two correct ways to implement a safe read-modify-write under READ COMMITTED, and the failure mode if you do neither.
8. You must drain a `jobs` table with 20 parallel workers and never double-process. Write the claim query and explain each clause.

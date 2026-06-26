# Replication & Read Replicas

> **Concept area:** Database Scaling & Partitioning
> **Subtopic:** Replication & Read Replicas
> **Reader profile:** A senior Java/JVM backend developer who wants to fully master replication — design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

**Replication** is the practice of keeping a copy of the same data on more than one machine, connected by a network, so that the copies stay (eventually or immediately) in agreement. Each machine holding a copy is called a **replica** (or **node**). If all replicas hold the *entire* dataset, you have *replication*. If each machine holds only a *subset* of the data, that is **partitioning** (a.k.a. **sharding**) — a different scaling axis covered in its own chapter. The two are orthogonal and routinely combined: you partition to fit data that is too big for one machine, and you replicate each partition for availability and read scale.

**The problem it solves.** A single database server is a single point of failure and a single point of throughput. Replication attacks four distinct problems at once, and it is important to keep them separate in your head because they pull in different design directions:

1. **High availability (HA)** — keep serving even if one machine (or a whole datacenter) goes down.
2. **Read scalability** — spread read traffic across many copies so no single node is the read bottleneck.
3. **Latency / locality** — put a copy geographically close to users so reads are fast (a copy in Mumbai for Mumbai users, a copy in Virginia for US users).
4. **Disaster recovery (DR)** & **operational flexibility** — keep an offsite copy, run heavy analytics on a replica without hurting the primary, do zero-downtime upgrades.

**When you reach for it.** You reach for replication almost always once you are past a toy deployment — even a two-node primary/standby pair is replication, and it is the baseline for any production OLTP system. You reach for *read replicas* specifically when your **read:write ratio is high** (say 90:10 or more, which is typical for web apps) and your reads are saturating the primary. You reach for **multi-region replication** when users are globally distributed or you need to survive a regional outage.

**One-paragraph mental model.** Think of replication as **one writer dictating a log of changes, and one or more listeners replaying that log to reproduce the same state.** The single source of truth is an *ordered* stream of writes — the replication log. The leader appends to it; followers consume it in order and apply it. Everything hard about replication — staleness, failover, conflicts, durability — comes down to *how far behind the listeners are allowed to be*, *what happens when the dictator dies*, and *what happens if two dictators speak at once*. Hold that image: an ordered change-log being shipped and replayed.

---

## 2. Foundations from first principles

Let us build the vocabulary from zero. A newcomer should be able to read this section linearly and never hit an unexplained term.

### 2.1 The core actors

- **Leader (a.k.a. primary, master, source).** The one node that accepts **writes** (INSERT/UPDATE/DELETE/DDL). It is the authority. The word "master" is being phased out in favor of "primary" or "leader" in most modern docs (MySQL 8.0.26+, PostgreSQL, etc.); we use **leader** and **primary** interchangeably.
- **Follower (a.k.a. replica, standby, secondary, slave, read replica).** A node that receives a stream of changes from the leader and applies them to stay in sync. It typically serves **reads only**. "Slave" is the legacy term; "replica" or "follower" is preferred.
- **Replication log / change stream.** The ordered record of writes the leader produces and followers consume. Its concrete form differs by engine (binlog, WAL, oplog — defined below).

This single-leader arrangement is called **leader-follower replication** (or **primary-replica**, or **master-slave**). It is by far the most common topology because it sidesteps write conflicts: there is exactly one place writes happen, so writes are totally ordered by definition.

### 2.2 What "a write" travels as — three log formats

When the leader does a write, *what exactly does it ship to followers?* There are three classic approaches, and understanding them is essential because each has sharp correctness gotchas.

#### (a) Statement-based replication (SBR)
The leader ships the **literal SQL statement** it executed, e.g. `UPDATE accounts SET balance = balance - 100 WHERE id = 42;`. The follower re-runs the same SQL.

- **Pro:** compact (a 30-byte statement can affect a million rows).
- **Con — nondeterminism:** any statement whose result depends on something other than its inputs will diverge on the replica. Classic offenders:
  - `NOW()`, `RAND()`, `UUID()`, `CURRENT_TIMESTAMP` — time/random differ per machine.
  - Auto-increment columns combined with concurrent transactions.
  - Triggers and user-defined functions with side effects.
  - Statements with nondeterministic ordering (`UPDATE ... LIMIT n` without `ORDER BY`).
- MySQL had real, painful production bugs from this; it now detects "unsafe" statements and logs warnings or falls back to row-based.

#### (b) Row-based replication (RBR)
The leader ships the **actual changed rows** (the before-image and after-image for each affected row), not the SQL. E.g. "row with PK 42 changed from balance=500 to balance=400."

- **Pro:** deterministic — the replica just stamps the new bytes in. No nondeterminism problem.
- **Con:** can be much larger (a `DELETE` of a million rows ships a million row entries). Mitigated by only logging changed columns (`binlog_row_image=MINIMAL`) and binary log compression.
- This is the **modern default** in MySQL 8.0 (`binlog_format=ROW`) and the only safe choice with many features (e.g. `READ-COMMITTED` isolation + replication).

#### (c) Write-Ahead Log (WAL) shipping / physical replication
This is a level deeper. Most storage engines already maintain a **Write-Ahead Log (WAL)** for crash recovery: *before* modifying a data page (a fixed-size block, typically 8 KB in PostgreSQL, 16 KB InnoDB page) on disk, the engine first appends a record describing the change to a sequential log on durable storage. This guarantees that after a crash, the engine can **replay** the WAL to recover committed-but-not-yet-flushed changes. (This pattern — log first, then mutate — is called *write-ahead logging*; it converts random page writes into a fast sequential append plus a deferred flush.)

**WAL shipping** repurposes this exact log for replication: ship the WAL records to a follower and have it replay them. Because WAL records operate at the level of "byte range X on page Y becomes Z," this is called **physical replication** — the replica is a *byte-for-byte* copy at the storage level.

- **Pro:** extremely faithful and efficient; no SQL re-parsing.
- **Con:** the replica must run the **exact same major version and (often) the same CPU page layout** because the WAL describes physical pages. You cannot use physical WAL replication between, say, PostgreSQL 14 and PostgreSQL 16. This is why physical replication is great for HA standbys but bad for major-version upgrades, and why **logical replication** (below) exists.
- This is PostgreSQL's primary replication mechanism (called *streaming replication*).

#### (d) Logical (row/change) replication — the in-between
**Logical replication** decodes the WAL back into logical change events (insert/update/delete of rows in a named table) and ships *those*. It is version-independent and lets you replicate a *subset* of tables, or replicate across major versions. PostgreSQL added built-in logical replication in v10 via *logical decoding* and *publications/subscriptions*. MySQL's row-based binlog is, in spirit, logical replication.

> **Mental shortcut:** Physical = "copy my disk pages." Logical/row = "copy my row changes." Statement = "re-run my SQL." Going down that list trades compactness/portability for determinism.

### 2.3 Synchronous vs asynchronous vs semi-synchronous — the durability dial

When the leader commits a write, *how long does it wait for followers before telling the client "done"?* This is the single most important tuning decision in replication. Define **commit acknowledgement** as the moment the leader returns success to the client.

- **Asynchronous replication.** The leader commits locally and returns success **immediately**, then ships the change to followers whenever it can. Followers may lag behind by milliseconds to minutes.
  - **Pro:** lowest write latency; followers can't slow down the leader; one slow/dead follower doesn't block writes.
  - **Con:** **durability hole.** If the leader crashes after acking a write but before the change reached any follower, that write is **lost** when a follower is promoted. This is *not theoretical* — it is the default and most common data-loss source in MySQL/PostgreSQL setups.
- **Synchronous replication.** The leader does **not** return success until at least one (or a quorum of) follower(s) confirm they have **received and durably stored** the change.
  - **Pro:** guaranteed that a confirmed write survives leader failure (no data loss within the sync set).
  - **Con:** write latency now includes a network round-trip to the follower; and if the synchronous follower is down or slow, **writes block** (the leader can't get its ack). This couples your write availability to the follower's health.
  - Pure fully-synchronous replication to *all* replicas is rare in OLTP because it makes the system as slow and fragile as its slowest replica.
- **Semi-synchronous replication.** A pragmatic middle ground: the leader waits for **one** (configurable N) follower to acknowledge **receipt** of the change (often just to its relay log / WAL receive buffer, not full apply), then returns. The rest replicate asynchronously.
  - This bounds data loss to "at most what one acked follower also lost," dramatically better than async, while only paying one round-trip.
  - MySQL's `rpl_semi_sync` plugin and PostgreSQL's `synchronous_standby_names` with `synchronous_commit` levels implement variants of this.
  - **Subtlety:** "acknowledge receipt" vs "acknowledge durable persistence" vs "acknowledge applied (visible to readers)" are three different guarantees. MySQL semi-sync (after 5.7) waits for the replica to write the event to its **relay log** (durably), *not* to apply it — so a read on that replica right after commit could still be stale even though durability is protected.

> **The fundamental tradeoff in one sentence:** Asynchronous = fast but can lose acked writes; synchronous = no loss but slow and fragile; semi-synchronous = bounded loss for one round-trip's cost. There is no free lunch — this is a direct consequence of the CAP/PACELC theorems (defined in §7).

### 2.4 Replication lag — the defining property of followers

**Replication lag** is how far behind a follower is, measured either in **time** ("this replica is 800 ms behind the leader") or in **bytes/log position** ("the replica is 4 MB of WAL behind"). Because followers apply changes after the leader, asynchronous followers are *always* at least a little stale. Lag is normally tiny (sub-millisecond to a few ms) but spikes under load, during big writes (a bulk `DELETE`), during long-running queries on the replica that block apply, or when the network hiccups.

Lag is the root cause of the **read-your-own-writes** problem: a user posts a comment (write goes to leader), the page reloads and reads from a lagging replica that hasn't gotten the comment yet, and the comment "disappears." Solving this requires **consistency mechanisms** discussed in §3.6 and §6.

### 2.5 Read replicas — what they are and what they are *not*

A **read replica** is simply a follower that you *deliberately route read queries to*, to offload the leader. Conceptually trivial; operationally subtle:

- They scale **reads**, not writes. Every write still goes to the single leader and is *also* applied on every replica, so each replica does the *same write work* as the leader plus serves reads. **Replication does not reduce write load — it multiplies it.** Ten replicas means each write is applied eleven times total.
- They serve **stale** data (asynchronous). Suitable for reads that tolerate staleness (analytics, search, feeds), risky for reads that must reflect the latest write.
- They are **not a backup.** A replica faithfully replicates your mistakes: `DROP TABLE users;` or a buggy `UPDATE` with no `WHERE` is replicated to every follower within milliseconds. Backups (point-in-time, immutable) protect against logical corruption and human error; replicas do not. (Expanded in §6 and §8.)

### 2.6 Topologies — the shapes replication can take

- **Single-leader (primary/standby):** one leader, N followers. Simple, no write conflicts. The workhorse.
- **Cascading / chained replication:** follower A replicates from the leader; follower B replicates from A (not the leader). This *fans out* without overloading the leader's outbound bandwidth/connection count. The cost is *additive lag* — B's lag = (A's lag from leader) + (B's lag from A).
- **Multi-leader (multi-master):** two or more nodes accept writes and replicate to each other. Needed for multi-datacenter low-latency writes or offline-capable clients, but introduces **write conflicts** (the same row edited on two leaders) that must be resolved (§7).
- **Leaderless (Dynamo-style):** no designated leader; clients (or a coordinator) write to several replicas and read from several, using quorums to get consistency. Used by Cassandra, DynamoDB, Riak. Different mental model entirely; touched on in §7.

We will spend most of our depth on **single-leader** because it dominates the JVM/relational world (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, and the relational layer most Java backends sit on).

---

## 3. How it works internally

This is the heart of the document. We trace the full lifecycle for the two dominant relational engines — **MySQL/MariaDB** (binlog-based) and **PostgreSQL** (WAL-based streaming) — then generalize.

### 3.1 MySQL leader-follower: the binary log pipeline, step by step

MySQL's replication is built on the **binary log (binlog)** — a set of files on the leader recording every data-changing event in commit order. (The binlog is *separate* from InnoDB's redo log, which is the crash-recovery WAL; do not confuse them. The binlog is the *replication* log and is engine-agnostic at the server layer.)

**Components and threads:**

- On the **leader**: a **binlog dump thread** per connected replica. It reads the binlog and streams events over the network.
- On the **follower**: an **I/O thread** that connects to the leader, requests events starting from a position, and writes received events into a local file called the **relay log**. Separately, a **SQL thread** (or, with parallel replication, a coordinator + multiple **worker/applier threads**) reads the relay log and *applies* the events to the follower's data.

**Step-by-step control & data flow for one write:**

1. Client sends `UPDATE accounts SET balance = balance - 100 WHERE id = 42;` to the leader.
2. The leader's storage engine (InnoDB) modifies the row in the buffer pool, writes a redo-log record (its internal WAL), and at **COMMIT** writes the change as one or more events into the **binlog** (in `ROW` format: a `Table_map` event + a `Update_rows` event with before/after images). Binlog write + InnoDB commit are coordinated by an internal **two-phase commit** so they can never disagree after a crash (controlled by `sync_binlog` and `innodb_flush_log_at_trx_commit`).
3. The leader returns success to the client. **In async mode this happens now**, regardless of replicas. In semi-sync mode, the leader's commit *waits* at this point until at least `rpl_semi_sync_master_wait_for_slave_count` replicas ack receipt (after `AFTER_SYNC` was introduced in 5.7, the ack is awaited *before* the engine commit is made visible, closing a phantom-read window).
4. The leader's **dump thread** notices new binlog data and pushes the events to each connected replica's I/O thread.
5. The replica's **I/O thread** receives the events and appends them to its **relay log**, then fsyncs (depending on `sync_relay_log`).
6. The replica's **SQL/applier thread(s)** read the relay log and execute the `Update_rows` event, stamping balance=400 onto row 42. With **multi-threaded replication (MTR)** (`replica_parallel_workers > 0`), independent transactions are applied concurrently by multiple worker threads; ordering within dependent transactions is preserved by the **logical clock** / **WRITESET** scheme (`binlog_transaction_dependency_tracking`).
7. The replica records how far it has progressed. With **GTIDs** (Global Transaction Identifiers — a globally unique `source_uuid:transaction_id` stamped on every transaction), the replica tracks the *set* of transactions it has applied rather than a fragile file+offset position. GTIDs make failover and re-pointing replicas vastly safer because any node can be told "give me everything I'm missing" by set difference.

**Crash-safety detail:** the replica's applied position must be stored *transactionally with the data*. Modern MySQL stores replication metadata in InnoDB tables (`relay_log_info_repository=TABLE`, default since 8.0) so that "I applied transaction X" and "X's row changes" commit atomically — otherwise a crash could replay or skip a transaction.

### 3.2 PostgreSQL streaming replication: the WAL pipeline, step by step

PostgreSQL uses **physical streaming replication** built on the WAL.

**Components:**

- WAL is a sequence of **WAL records** grouped into 16 MB **WAL segment files**, addressed by a monotonically increasing **LSN (Log Sequence Number)** — a 64-bit byte offset into the logical WAL stream. (Remember LSN; it is the unit of "how far along" everywhere in PG replication.)
- On the **primary**: a **WAL sender** process per connected standby streams WAL over the **replication protocol** (a special mode of the normal libpq wire protocol).
- On the **standby**: a **WAL receiver** process receives WAL and writes it locally; the **startup/recovery process** continuously **replays (redoes)** WAL records to keep the standby's data files in lockstep with the primary.

**Step-by-step:**

1. Client commits a transaction on the primary. The backend writes WAL records describing every page change and flushes them to disk at `pg_wal/` (formerly `pg_xlog/`).
2. `synchronous_commit` decides what "committed" means:
   - `off` — return before WAL is even flushed locally (fastest, can lose recent local commits on crash).
   - `local` — wait for local WAL flush only (async replication).
   - `remote_write` — wait until a sync standby has *received and written* (not fsynced) the WAL.
   - `on` (default) — wait for local flush *and*, if `synchronous_standby_names` is set, for the standby to **flush** WAL durably.
   - `remote_apply` — wait until the standby has **applied** the WAL so the change is visible to readers there (gives read-your-writes on that standby, at higher latency).
3. The **WAL sender** streams the new WAL bytes to each standby's **WAL receiver**.
4. The standby's recovery process replays the WAL records, mutating its data pages to match the primary byte-for-byte.
5. The standby periodically sends back its **flush LSN** and **apply LSN** so the primary knows how far it has progressed (used for sync replication and for `pg_stat_replication` monitoring).

**Replication slots** (PG 9.4+): a named server-side object that records "this standby has consumed WAL up to LSN X." The primary will then **refuse to recycle/delete WAL** older than X, guaranteeing a temporarily-disconnected standby can always catch up. The danger: a dead/forgotten slot causes WAL to accumulate indefinitely and **fill the primary's disk** — a famous PG outage cause. (Mitigated by `max_slot_wal_keep_size` in PG 13+.)

**Hot standby:** by default a streaming standby can serve **read-only queries** while replaying (`hot_standby=on`). The tension: WAL replay may need to remove rows a long-running standby query still needs to see (MVCC — Multi-Version Concurrency Control, where old row versions are kept until no transaction needs them). PostgreSQL resolves this by either **canceling the standby query** (the dreaded `ERROR: canceling statement due to conflict with recovery`) or **pausing replay** if `hot_standby_feedback=on` (the standby tells the primary "don't vacuum away rows I'm reading," at the cost of bloat on the primary). This is a classic real-world tuning fight.

### 3.3 MongoDB replica sets (for contrast — document DB)

MongoDB uses a **replica set**: one primary, several secondaries, replicating via the **oplog** (operations log — a capped collection of idempotent, logical write operations). Secondaries tail the oplog and apply ops. Crucially, MongoDB has **built-in automatic failover via Raft-like election** (see §3.5) and **tunable consistency via write/read concerns** (`w:"majority"`, `readConcern:"majority"`/`"linearizable"`, `readPreference:"primary"/"secondary"/"nearest"`). It bakes in much of what relational engines bolt on with external tools. The oplog is idempotent so re-applying an op is safe — a key design choice for crash recovery.

### 3.4 Initial sync / bootstrapping a new replica (often forgotten)

Before a replica can *stream* changes, it must first obtain a **consistent base snapshot** of the entire dataset, then start streaming from the exact log position that snapshot corresponds to. Getting this seam right is where many setups go wrong.

- **MySQL:** historically `mysqldump --single-transaction --master-data` (records the binlog position inside the dump) or, much faster for large datasets, **physical backup** via Percona XtraBackup / MySQL Enterprise Backup, then `CHANGE REPLICATION SOURCE TO ... ` with the recorded GTID/position. The newer **CLONE plugin** (8.0.17+) clones a donor instance directly.
- **PostgreSQL:** `pg_basebackup` takes a physical base backup *and* sets up the streaming connection/replication slot in one shot — the standard, reliable way to build a standby.
- The invariant: **snapshot position + first streamed change must be contiguous, with no gap and no overlap-that-isn't-idempotent.** GTIDs/LSNs/replication slots exist precisely to make this seam exact.

### 3.5 Failover & leader election — the dangerous part

When the leader dies, someone must **promote** a follower to be the new leader and **redirect** clients and remaining followers to it. This is **failover**. Doing it correctly is genuinely hard; doing it automatically is harder.

**The steps of a (good) automatic failover:**

1. **Detect** the leader is down — not just slow, not a network blip. Requires multiple probes, timeouts, and ideally agreement among several observers to avoid false positives.
2. **Choose** the new leader — typically the follower with the **most up-to-date log** (highest GTID set / LSN) to minimize data loss. Some systems let you weight by location/priority.
3. **Reconfigure** — promote the chosen follower (stop it being read-only, let it accept writes), re-point the other followers at it, and stop the old leader from accepting writes.
4. **Redirect clients** — update the connection endpoint (DNS, a virtual IP, a proxy, or service discovery) so writes go to the new leader.

**Why it's dangerous — the canonical failure list:**

- **Lost writes (async).** The promoted follower was behind; acked writes the old leader had are gone. Mitigated by semi-sync/quorum.
- **Split-brain.** The old leader didn't actually die (it was just network-partitioned) and still thinks it's leader, while a new leader was elected. **Two leaders accept writes simultaneously** → divergent data, the worst outcome. Prevented by **fencing / STONITH** ("Shoot The Other Node In The Head" — forcibly kill/isolate the old leader) and by **quorum** (a leader must hold a majority of votes to remain leader; a minority-partition leader steps down).
- **Quorum & the need for an odd number.** To avoid split-brain you need a **majority** to agree who is leader. With 2 nodes there is no majority on partition; you need **3+ nodes** (or 2 + a tiebreaker **witness/arbiter**). This is why HA clusters are 3 or 5 nodes, not 2 or 4.
- **Cascading failure.** Promoting a follower that can't handle the full write+read load melts it too.

**Consensus algorithms** make automatic failover safe by formalizing leader election and log replication so that there is *provably* at most one leader per term:

- **Raft** — a consensus algorithm where nodes elect a leader for a numbered **term**; a candidate needs votes from a **majority** to win; the leader replicates a log to followers and a log entry is **committed** once a majority has it. Used by etcd, Consul, CockroachDB, TiKV, MongoDB's election protocol (a Raft variant), and Patroni's logic via etcd/Consul. Its whole point is **no two leaders in the same term** and **no committed entry is ever lost**.
- **Paxos** — the older, harder-to-implement consensus family with the same guarantees; used (in variants like Multi-Paxos) by Google Spanner, Chubby, etc.
- **ZooKeeper / etcd / Consul** — these are **distributed coordination services**: small, strongly-consistent key-value stores (built on ZAB/Raft) used as the **source of truth for "who is leader"** and for distributed locks. Failover tools store the cluster's leadership state here so all participants agree. (ZooKeeper uses **ZAB**, the ZooKeeper Atomic Broadcast protocol; etcd/Consul use Raft.)

**The tools that orchestrate failover for relational DBs:**

- **Orchestrator** (MySQL, originally from GitHub/Outbrain): discovers and visualizes the replication topology, detects primary failure using the *whole topology* as evidence (it checks whether replicas also lost their primary, reducing false positives), and performs automated promotion and topology repair. Pairs with a proxy (ProxySQL/HAProxy) or consul-template/DNS to redirect traffic.
- **Patroni** (PostgreSQL): a Python supervisor that runs alongside each Postgres node and uses a **DCS (Distributed Configuration Store)** — etcd, Consul, or ZooKeeper — to hold a **leader lock** with a TTL (time-to-live). Whichever node holds the lock is primary; it renews the lock via heartbeats. If it can't renew (it died/partitioned), the lock expires and a healthy node grabs it and promotes. This *delegates the hard consensus to etcd/Raft*, which is the right design. Patroni also manages the local Postgres config and `pg_rewind`.
- **repmgr** (PostgreSQL): older, lighter failover/management tool; needs an external witness for safe automatic failover and is less split-brain-proof than Patroni+DCS.
- **MHA (Master High Availability)** — older MySQL tool, largely superseded by Orchestrator + GTID.
- **Cloud-managed:** Amazon RDS/Aurora, Google Cloud SQL, Azure Database all do failover for you (Aurora uses a shared-storage design so failover is a fast pointer flip rather than a data copy). You give up control for operability.

### 3.6 Read routing — sending writes to the leader, reads to replicas

Once you have replicas, the application must route each query to the right node. This logic lives in one of three places:

1. **In the application / driver.** Maintain two connection pools — one to the leader (writes + read-your-writes), one (load-balanced) across replicas (tolerant reads). Most explicit and flexible; ties routing knowledge into your code. In Spring you can use `AbstractRoutingDataSource` keyed by a thread-local "read vs write" flag, often driven by `@Transactional(readOnly = true)`.
2. **In a proxy / middleware.** A SQL-aware proxy inspects each statement and routes `SELECT` to replicas and writes to the leader, often with read-after-write stickiness. Examples: **ProxySQL** and **MaxScale** (MySQL/MariaDB), **PgBouncer** (connection pooling; not query-routing by itself), **Pgpool-II** (can do load-balancing/routing for PG), **AWS RDS Proxy** / **Aurora reader endpoint**.
3. **In the connection string / endpoint.** Some systems expose a single "reader endpoint" that DNS-round-robins across replicas (Aurora), or JDBC URLs with `readOnly`/load-balancing options (`jdbc:mysql:replication://`, `jdbc:postgresql://host1,host2/?targetServerType=preferSecondary`).

**The hazard:** naive `SELECT`→replica routing breaks **read-your-own-writes** and **monotonic reads**. You must add a consistency strategy (sticky-to-leader for a window after a write, or LSN/GTID-aware routing that waits until the replica has caught up to your write's position — see §6.2).

### 3.7 The state machine of a replica (PostgreSQL flavor)

A standby moves through states you will see in logs/`pg_stat_replication`:
`startup` → `catchup` (replaying archived/streamed WAL to catch up to the primary) → `streaming` (live, low lag) → possibly `backup`/`stopping`. On the primary, `pg_stat_replication.state` shows each standby as `startup | catchup | streaming | backup`. During failover a standby goes through *promotion*: recovery ends, a **timeline** increments (PG tracks "timelines" so a promoted node's WAL history doesn't collide with the old one), and it begins generating its own WAL.

---

## 4. The complete toolkit

Below are the knobs, commands, and APIs you actually use. Defaults are flagged with version where they matter. **Always verify against your exact version/vendor** — defaults change between releases.

### 4.1 MySQL / MariaDB replication settings (server variables)

| Variable | Purpose | Typical / default |
|---|---|---|
| `server_id` | Unique ID per node; replication refuses to run without distinct IDs | must be set, unique |
| `log_bin` | Enable the binary log (required on leader) | ON by default in 8.0 |
| `binlog_format` | `ROW` / `STATEMENT` / `MIXED` | `ROW` (8.0 default) |
| `binlog_row_image` | `FULL` / `MINIMAL` / `NOBLOB` — how much of each row to log | `FULL` |
| `gtid_mode` + `enforce_gtid_consistency` | Enable Global Transaction IDs | `OFF` historically; turn `ON` for sane failover |
| `sync_binlog` | fsync binlog every N commits; `1` = safest (per-commit) | `1` (8.0) |
| `innodb_flush_log_at_trx_commit` | InnoDB redo durability; `1` = ACID-safe | `1` |
| `rpl_semi_sync_master_enabled` / `..._slave_enabled` | Enable semi-sync plugin | OFF |
| `rpl_semi_sync_master_wait_for_slave_count` | How many replicas must ack | 1 |
| `rpl_semi_sync_master_timeout` | ms to wait before falling back to async | 10000 (10 s) |
| `rpl_semi_sync_master_wait_point` | `AFTER_SYNC` (safe) vs `AFTER_COMMIT` | `AFTER_SYNC` (5.7+) |
| `replica_parallel_workers` (was `slave_parallel_workers`) | Threads applying replication in parallel | 0 (single-threaded) — set to >0 |
| `replica_parallel_type` | `LOGICAL_CLOCK` (recommended) vs `DATABASE` | `LOGICAL_CLOCK` (8.0) |
| `replica_preserve_commit_order` | Keep commit order on replica with parallel apply | ON (8.0) |
| `read_only` / `super_read_only` | Make a node reject writes (replicas) | OFF on leader, ON on replicas |
| `expire_logs_days` / `binlog_expire_logs_seconds` | Auto-purge old binlogs | 30 days (8.0 default `binlog_expire_logs_seconds=2592000`) |

**Key MySQL commands:**

| Command | Purpose |
|---|---|
| `CHANGE REPLICATION SOURCE TO SOURCE_HOST=..., SOURCE_AUTO_POSITION=1` | Point a replica at a leader (GTID auto-position). (Was `CHANGE MASTER TO`.) |
| `START REPLICA` / `STOP REPLICA` | Start/stop the I/O+SQL threads (was `START SLAVE`) |
| `SHOW REPLICA STATUS\G` | The single most important diagnostic — shows `Seconds_Behind_Source`, `Replica_IO_Running`, `Replica_SQL_Running`, `Last_Error`, GTID sets |
| `SHOW BINARY LOGS`, `SHOW BINLOG EVENTS` | Inspect the binlog |
| `mysqlbinlog` | CLI to decode binlog files into readable SQL/events |
| `RESET REPLICA ALL` | Wipe replica config |
| `SHOW REPLICAS` (was `SHOW SLAVE HOSTS`) | List connected replicas on the leader |

### 4.2 PostgreSQL replication settings (`postgresql.conf`)

| Setting | Purpose | Typical / default |
|---|---|---|
| `wal_level` | `replica` (physical) or `logical` | `replica` (10+ default) |
| `max_wal_senders` | Max concurrent WAL sender processes | 10 (recent) |
| `max_replication_slots` | Max replication slots | 10 |
| `synchronous_commit` | `off`/`local`/`remote_write`/`on`/`remote_apply` | `on` |
| `synchronous_standby_names` | Which standbys are synchronous; supports `ANY 2 (s1,s2,s3)` quorum syntax | empty (async) |
| `hot_standby` | Allow read queries on standby | on |
| `hot_standby_feedback` | Standby tells primary to delay vacuum to avoid query cancels | off |
| `max_standby_streaming_delay` | How long replay waits before canceling a conflicting standby query | 30 s |
| `wal_keep_size` (was `wal_keep_segments`) | Extra WAL to retain for lagging standbys | 0 |
| `max_slot_wal_keep_size` | Cap WAL retained for slots (prevents disk-fill) | -1 (unlimited) — **set this!** |
| `primary_conninfo` | Standby's connection to primary (in `postgresql.auto.conf`) | — |
| `recovery_target_timeline` | Which timeline to follow after promotion (`latest`) | — |
| `restore_command` / `archive_command` | WAL archiving for PITR & catch-up | — |

**Key PostgreSQL commands / functions:**

| Command | Purpose |
|---|---|
| `pg_basebackup -h primary -D /data -R --slot=s1 -C` | Bootstrap a standby + create slot + write `primary_conninfo` |
| `SELECT * FROM pg_stat_replication;` | On primary: each standby's `state`, `sent_lsn`, `write_lsn`, `flush_lsn`, `replay_lsn`, `sync_state` |
| `SELECT * FROM pg_stat_wal_receiver;` | On standby: receiver status |
| `SELECT pg_is_in_recovery();` | Is this node a standby? (true) or primary? (false) |
| `SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) ...` | Compute lag in bytes |
| `SELECT pg_last_wal_replay_lsn(), now() - pg_last_xact_replay_timestamp();` | Lag on standby (LSN + time) |
| `pg_ctl promote` / `SELECT pg_promote();` | Promote a standby to primary |
| `pg_rewind` | Re-synchronize an old primary as a standby after failover without full rebuild |
| `CREATE PUBLICATION` / `CREATE SUBSCRIPTION` | Logical replication setup (subset of tables, cross-version) |
| `SELECT pg_create_physical_replication_slot('s1');` | Create a slot manually |

### 4.3 Failover/orchestration tooling

| Tool | DB | What it does |
|---|---|---|
| **Orchestrator** | MySQL | Topology discovery/visualization, failure detection using whole-topology evidence, automated promotion & re-pointing |
| **Patroni** | PostgreSQL | Per-node supervisor; leader lock in etcd/Consul/ZK; automatic, split-brain-safe failover; manages config + `pg_rewind` |
| **repmgr** | PostgreSQL | Lighter management/failover; needs witness for safe auto-failover |
| **ProxySQL** | MySQL | SQL-aware proxy: read/write split, query routing/rewriting, connection pooling, can integrate with Orchestrator |
| **MaxScale** | MariaDB/MySQL | SQL-aware proxy with automatic failover (monitors) and read/write split |
| **HAProxy** | any (TCP) | L4 load balancer; with health checks routes to current primary/replicas |
| **PgBouncer** | PostgreSQL | Connection pooler (transaction/session pooling); reduces connection overhead; not query-routing |
| **Pgpool-II** | PostgreSQL | Pooling + load-balancing + (legacy) failover |
| **etcd / Consul / ZooKeeper** | any | Distributed consensus store holding "who is leader" |
| **Vitess** | MySQL | Sharding + replication + routing platform (powers YouTube/Slack); managed leader/replica & resharding |

### 4.4 Java/Spring routing toolkit

- **JDBC URLs:** `jdbc:mysql:replication://primary,replica1,replica2/db` (Connector/J auto read/write split via `Connection.setReadOnly(true)`); PostgreSQL `jdbc:postgresql://h1:5432,h2:5432/db?targetServerType=primary` and `targetServerType=preferSecondary` with `loadBalanceHosts=true`.
- **Spring:** `AbstractRoutingDataSource` (choose datasource by a key); `@Transactional(readOnly = true)` sets the read flag; `LazyConnectionDataSourceProxy` to defer connection acquisition until the read/write intent is known.
- **HikariCP:** run two pools (one per role) sized appropriately; never share a single pool across roles.

---

## 5. Code examples by use case

These span genuinely different scenarios. Adapt freely.

### 5.1 Bootstrap a PostgreSQL streaming standby (operations)

```bash
# On the PRIMARY: ensure replication is allowed.
# postgresql.conf:
#   wal_level = replica
#   max_wal_senders = 10
#   max_replication_slots = 10
#   max_slot_wal_keep_size = 50GB   # CRITICAL: cap WAL so a dead slot can't fill the disk
# pg_hba.conf (allow the standby host to connect for replication):
#   host  replication  replicator  10.0.0.0/24  scram-sha-256
psql -c "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 's3cret';"

# On the STANDBY host: take a base backup, create a slot, and write connection info.
# -R writes primary_conninfo into postgresql.auto.conf and creates standby.signal
# -C --slot creates a physical replication slot so the primary retains WAL for us.
pg_basebackup \
  -h 10.0.0.10 -U replicator -D /var/lib/postgresql/16/main \
  -R -C --slot=standby1 -X stream -P

# standby.signal exists -> Postgres starts in standby mode and streams.
systemctl start postgresql

# Verify on the PRIMARY:
#   SELECT client_addr, state, sync_state,
#          pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS replay_lag_bytes
#   FROM pg_stat_replication;
```

Key points: `-R` automates the standby config; the **slot** guarantees catch-up but *must* be capped with `max_slot_wal_keep_size`; `sync_state` will be `async` until you list it in `synchronous_standby_names`.

### 5.2 Turn on synchronous (quorum) commit in PostgreSQL (durability)

```sql
-- On the primary. Require ANY 1 of the two named standbys to durably flush
-- before a commit returns. This bounds data loss to zero within the sync set,
-- while surviving the loss of one standby (the other satisfies the quorum).
ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (standby1, standby2)';
ALTER SYSTEM SET synchronous_commit = 'on';   -- wait for standby FLUSH
SELECT pg_reload_conf();

-- Trade-off: if BOTH standbys are down, writes BLOCK (no quorum). That is the
-- price of guaranteed durability. Choose 'ANY 1 (a,b)' over 'FIRST 1 (a,b)'
-- so either standby can satisfy the quorum (FIRST is ordered/preferential).
```

### 5.3 MySQL: build a GTID-based replica and enable semi-sync

```sql
-- LEADER my.cnf (excerpt):
--   server_id=1
--   log_bin=mysql-bin
--   binlog_format=ROW
--   gtid_mode=ON
--   enforce_gtid_consistency=ON
--   sync_binlog=1
--   innodb_flush_log_at_trx_commit=1
INSTALL PLUGIN rpl_semi_sync_source SONAME 'semisync_source.so';   -- 8.0 names
SET GLOBAL rpl_semi_sync_source_enabled = 1;
SET GLOBAL rpl_semi_sync_source_timeout = 1000;  -- ms: fall back to async if no ack in 1s
CREATE USER 'repl'@'%' IDENTIFIED BY 's3cret';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';

-- REPLICA my.cnf: server_id=2, gtid_mode=ON, enforce_gtid_consistency=ON,
--                 super_read_only=ON, replica_parallel_workers=4
INSTALL PLUGIN rpl_semi_sync_replica SONAME 'semisync_replica.so';
SET GLOBAL rpl_semi_sync_replica_enabled = 1;
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='10.0.0.10', SOURCE_USER='repl', SOURCE_PASSWORD='s3cret',
  SOURCE_AUTO_POSITION=1;     -- GTID auto-position: no fragile file+offset
START REPLICA;
SHOW REPLICA STATUS\G          -- check Replica_IO_Running=Yes, Replica_SQL_Running=Yes,
                               -- Seconds_Behind_Source, Retrieved/Executed_Gtid_Set
```

### 5.4 Spring Boot: route reads to replicas, writes to the leader (application)

```java
// 1) A key that tells the routing datasource which physical DB to use.
public enum DbRole { WRITE, READ }

public final class DbContext {
    private static final ThreadLocal<DbRole> ROLE = ThreadLocal.withInitial(() -> DbRole.WRITE);
    public static void set(DbRole r) { ROLE.set(r); }
    public static DbRole get() { return ROLE.get(); }
    public static void clear() { ROLE.remove(); }
}

// 2) AbstractRoutingDataSource picks the target pool from the ThreadLocal.
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override protected Object determineCurrentLookupKey() { return DbContext.get(); }
}

@Configuration
class DataSourceConfig {
    @Bean DataSource writeDataSource() { /* HikariCP -> leader */ return hikari("jdbc:postgresql://primary:5432/app"); }
    @Bean DataSource readDataSource()  { /* HikariCP -> replicas (LB) */ return hikari("jdbc:postgresql://r1:5432,r2:5432/app?targetServerType=preferSecondary&loadBalanceHosts=true"); }

    @Bean
    DataSource routingDataSource(DataSource writeDataSource, DataSource readDataSource) {
        RoutingDataSource rds = new RoutingDataSource();
        rds.setTargetDataSources(Map.of(DbRole.WRITE, writeDataSource, DbRole.READ, readDataSource));
        rds.setDefaultTargetDataSource(writeDataSource);   // default to leader = safe
        // Defer real connection acquisition until after the read/write intent is set,
        // so @Transactional(readOnly=true) can flip the key before a connection is grabbed.
        return new LazyConnectionDataSourceProxy(rds);
    }
}

// 3) An aspect that flips the key based on @Transactional(readOnly=true).
@Aspect @Component
class ReadOnlyRoutingAspect {
    @Around("@annotation(tx)")
    public Object route(ProceedingJoinPoint pjp, Transactional tx) throws Throwable {
        if (tx.readOnly()) DbContext.set(DbRole.READ);
        try { return pjp.proceed(); }
        finally { DbContext.clear(); }   // always reset the ThreadLocal
    }
}
```
**Gotcha baked in:** the default key is `WRITE` (the leader), so any un-annotated query is safe-by-default. `LazyConnectionDataSourceProxy` is essential — without it Spring grabs a connection *before* the read-only flag is known.

### 5.5 Read-your-own-writes via LSN-aware routing (consistency)

```java
// After a write, capture the primary's commit LSN; on the next read, only use a
// replica that has replayed at least that LSN, else fall back to the leader.
public class ConsistentReadRouter {

    long captureWriteLsn(Connection primary) throws SQLException {
        try (var st = primary.createStatement();
             var rs = st.executeQuery("SELECT pg_current_wal_lsn()::text")) {
            rs.next();
            return lsnToLong(rs.getString(1));     // parse 'X/Y' hex into a long
        }
    }

    Connection pickReadConn(long requiredLsn, List<DataSource> replicas, DataSource leader) throws SQLException {
        for (DataSource ds : replicas) {
            Connection c = ds.getConnection();
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT pg_last_wal_replay_lsn()::text")) {
                rs.next();
                if (lsnToLong(rs.getString(1)) >= requiredLsn) return c;  // caught up
            }
            c.close();
        }
        return leader.getConnection();   // no replica is current enough -> read the leader
    }
    // lsnToLong: split on '/', parse both halves as base-16, combine: (hi << 32) | lo
}
```
This is the principled fix for staleness. The cheaper fix is **sticky-to-leader for N seconds after a user writes** (store last-write timestamp per session; route that user's reads to the leader for, say, 5 s).

### 5.6 PostgreSQL logical replication: replicate a subset of tables to a different version (data movement)

```sql
-- SOURCE (publisher), e.g. PG 13.  wal_level = logical
CREATE PUBLICATION analytics_pub FOR TABLE orders, order_items;

-- TARGET (subscriber), e.g. PG 16 (cross-version is fine with logical replication).
CREATE SUBSCRIPTION analytics_sub
  CONNECTION 'host=src dbname=app user=repl password=s3cret'
  PUBLICATION analytics_pub;
-- Now only orders/order_items stream into the target; great for blue/green major
-- upgrades and feeding a separate analytics/reporting database.
-- Caveat: logical replication does NOT replicate DDL (schema changes) or sequences'
-- current values automatically; you handle those out of band.
```

### 5.7 A health/lag check you can wire into monitoring (observability)

```bash
# PostgreSQL: alert if replay lag exceeds 10s OR no standby is connected.
psql -At -c "
  SELECT application_name,
         EXTRACT(epoch FROM (now() - replay_lsn_ts)) AS lag_s
  FROM (
    SELECT application_name, state,
           pg_last_xact_replay_timestamp() OVER () AS replay_lsn_ts
    FROM pg_stat_replication
  ) s;"

# MySQL: Seconds_Behind_Source (note: NULL when SQL thread stopped or broken!)
mysql -e "SHOW REPLICA STATUS\G" | egrep \
  'Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source|Last_Error'
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Replicas don't reduce write throughput** — they replicate every write. If writes are your bottleneck, you need **partitioning/sharding**, not more replicas.
- **Single-threaded apply is the classic replica bottleneck.** A leader applies writes with many concurrent client threads; an old-style replica applied them with **one** SQL thread, so under heavy write load the replica falls behind permanently. Fix: **parallel replication** (`replica_parallel_workers` in MySQL with `LOGICAL_CLOCK`; PostgreSQL replays WAL single-threaded but the work is cheaper). Tune `binlog_transaction_dependency_tracking=WRITESET` on the leader to expose more parallelism.
- **Big single transactions** (a `DELETE` of 50M rows) serialize on the replica and spike lag; chunk large DML.
- **Network bandwidth/latency** between regions bounds sync replication and cross-region lag. Compress the stream (binlog compression; `wal_compression=on`).
- **Replica reads compete with apply.** Long analytical queries on a hot standby can block WAL replay (PG) or compete for IO — isolate analytics to a dedicated replica.

### 6.2 Correctness & consistency (the part interviews love)
Establish the consistency guarantee you need and engineer to it:
- **Read-your-own-writes (read-after-write):** a user always sees their own prior writes. Achieve via sticky-to-leader window, or LSN/GTID-aware routing (§5.5), or session-level `readPreference: primary` for that user's reads.
- **Monotonic reads:** a user never sees time go *backwards* (read fresh value, then stale value from a more-lagging replica). Achieve by pinning a user/session to a **single** replica (consistent hashing) rather than round-robining each request.
- **Consistent prefix reads:** if writes are causally ordered, reads see them in that order — a concern mainly with partitioned/multi-leader systems where each partition replicates independently.
- **Avoid stale reads in critical paths:** payment confirmation, auth, inventory decrement → read from the leader or require `remote_apply`/`readConcern: linearizable`.

### 6.3 Memory & resources
- WAL/binlog retention consumes disk; **replication slots can fill the primary disk** if a consumer dies — cap with `max_slot_wal_keep_size` (PG13+) / `binlog_expire_logs_seconds` (MySQL). This is one of the most common self-inflicted outages.
- Each connected replica costs the leader a sender/dump thread and network egress; **cascading replication** (§7) reduces leader fan-out.
- `hot_standby_feedback=on` prevents standby query cancels but causes **table bloat** on the primary (vacuum is held back) — monitor bloat.

### 6.4 Security
- Replication traffic carries *all* your data; **encrypt it in transit (TLS)** — `require_secure_transport` (MySQL), `sslmode=require`/`verify-full` in `primary_conninfo` (PG).
- Use a **least-privilege replication user** (`REPLICATION SLAVE`/`REPLICATION` role only).
- Lock down `pg_hba.conf` / firewall the replication port; replicas can expose data to anyone who can connect.
- Treat replicas as **production data** for compliance (GDPR, PCI): a replica in another region may change your data-residency posture.

### 6.5 Observability (must-haves)
- **Replication lag** (time *and* bytes) per replica, alerting on thresholds. (Time-based lag is intuitive but goes to zero misleadingly when the replica is idle; track bytes too.)
- **Replication state**: IO/SQL threads running (MySQL), `pg_stat_replication.state = streaming`, slot existence/`wal_status`.
- **`Seconds_Behind_Source` is NULL when broken** — alert on NULL, not just on high values.
- **Slot WAL retention** size on the primary (disk-fill early warning).
- **Failover events** and **timeline** changes; **last applied GTID/LSN**.
- Standby **query cancellations** (`conflict with recovery`) count.

### 6.6 Testing
- **Chaos-test failover regularly** (kill the primary in staging; measure RTO/RPO — Recovery Time/Point Objective). Untested failover *is* broken failover.
- Verify **data loss bounds** match your config (async loses recent writes; semi-sync/quorum doesn't, within the set).
- Test **read routing** under lag (inject artificial replica delay; confirm read-your-writes holds).
- Test **rebuild** of a replica from backup/basebackup; time it (it's your recovery path).

### 6.7 Production hardening checklist
- Odd number of voting members (3/5) for any auto-failover cluster; use a **witness/arbiter** if you only have 2 data nodes.
- **Fencing/STONITH** so a recovered old leader cannot accept writes (split-brain prevention).
- `super_read_only=ON` on replicas (MySQL) / `default_transaction_read_only` so apps can't accidentally write to a replica.
- Cap WAL/binlog retention; monitor disk.
- Separate **backups** from **replicas** — keep immutable, point-in-time backups (PITR) regardless of how many replicas you have.
- Use **GTIDs** (MySQL) so re-pointing replicas after failover is safe and automatic.
- Keep replica hardware ≈ leader; an underpowered replica lags and is a poor failover target.

### 6.8 Anti-patterns
- **Treating a replica as a backup.** It replicates `DROP TABLE`. Backups protect against logical/human errors; replicas don't.
- **Two-node auto-failover with no witness** → split-brain or no quorum.
- **Round-robin reads** without monotonic-read handling → users see data flicker backwards.
- **Routing all reads to replicas including read-after-write** → "my comment disappeared" bugs.
- **Forgetting to cap replication slots** → primary disk fills, hard outage.
- **Promoting the most-behind replica** during failover → maximal data loss; always promote the most-caught-up node.
- **Synchronous replication to a single standby with no fallback** → that standby's downtime = your write downtime.

---

## 7. Advanced topics & deep internals

### 7.1 CAP and PACELC — the theory that explains the tradeoffs
- **CAP theorem:** during a **network Partition (P)**, a distributed system must choose between **Consistency (C)** (every read sees the latest write) and **Availability (A)** (every request gets a non-error response). You cannot have both *while partitioned*. Single-leader sync replication is **CP**-leaning (a minority partition stops accepting writes to stay consistent); some multi-leader/leaderless systems are **AP**-leaning (accept writes everywhere, reconcile later).
- **PACELC** extends it: **if Partitioned, choose A or C; Else (normal operation), choose Latency (L) or Consistency (C).** This captures the everyday async-vs-sync choice: async = low latency, weaker consistency; sync = strong consistency, higher latency. Most relational replication is **PC/EL** (consistent under partition via single leader, latency-favoring normally via async).

### 7.2 Multi-leader (multi-master) and conflict resolution
Multiple nodes accept writes → the same row can be edited concurrently on two leaders → a **write conflict** that single-leader systems never have. Resolution strategies:
- **Last-Write-Wins (LWW):** keep the write with the highest timestamp. Simple but **silently drops** the loser; clock skew makes it unsafe. Cassandra uses LWW.
- **Application/merge logic:** custom conflict handlers (e.g., merge two shopping carts by union).
- **CRDTs (Conflict-free Replicated Data Types):** data structures (counters, sets, sequences) mathematically designed so concurrent updates **always merge deterministically** without coordination. Used by Riak, Redis (active-active), collaborative editors.
- **Avoid conflicts by topology:** route each record's writes to a "home" leader (e.g., shard by user → that user always writes to one region).
- Tools: MySQL **Group Replication** (built on a Paxos-variant protocol called **XCom**, certifies transactions before commit — can run multi-primary), **Galera Cluster** (synchronous multi-master via certification-based replication; writes go everywhere, conflicts cause one transaction to fail at certify time), **BDR** for Postgres (commercial multi-master).

### 7.3 Synchronous replication is not the same as a single transaction across nodes
Sync replication guarantees the *log* reached the standby; it does **not** by itself give you cross-node distributed transactions or **linearizability** unless you go further (quorum reads + writes, or consensus on every operation as in Spanner/CockroachDB). Be precise: "synchronous replication" ≠ "strong consistency for reads on the replica" unless reads also wait for apply (`remote_apply`/`readConcern: linearizable`).

### 7.4 Group/quorum replication & the leader's hidden role
In **MySQL Group Replication** and PG **quorum sync** (`ANY k (...)`), a write needs acknowledgement from a **majority/quorum** of members; this tolerates `f` failures with `2f+1` members. The reason quorum systems use **odd member counts**: with 5 nodes you tolerate 2 failures (quorum 3); a 6th node raises the cost (quorum 4) without raising fault tolerance. Quorum reads + quorum writes overlapping (`R + W > N`) is the leaderless route to consistency (Dynamo/Cassandra tunable consistency: `QUORUM`, `LOCAL_QUORUM`, `ONE`, `ALL`).

### 7.5 Delayed replicas (an underused safety tool)
A **delayed replica** intentionally lags by a fixed interval (e.g., 1 hour): `CHANGE REPLICATION SOURCE TO SOURCE_DELAY=3600;` (MySQL) or `recovery_min_apply_delay = '1h'` (PG). If someone runs a catastrophic `DELETE`, you have an hour to stop the delayed replica before it applies the disaster and recover from it — a fast partial alternative to full PITR restore.

### 7.6 `pg_rewind` and timelines (avoiding full rebuilds)
After failover, the **old primary** has diverged (it may have local WAL never sent to the new primary). Rather than rebuild it from scratch (slow for TB-scale), **`pg_rewind`** rewinds only the changed blocks so the old primary becomes a standby of the new one. It relies on PostgreSQL **timelines**: each promotion bumps the timeline ID so histories don't collide; `recovery_target_timeline='latest'` tells a standby to follow promotions.

### 7.7 MySQL GTID internals & `gtid_executed`
A GTID is `source_uuid:N`. Each server tracks `gtid_executed` (the set it has applied) and `gtid_purged` (binlogs gone). Re-pointing a replica with `SOURCE_AUTO_POSITION=1` computes the *missing* GTIDs by set subtraction and requests exactly those — this is why GTID failover is robust where file+offset is fragile. Pitfall: if a needed GTID has been **purged** from the new leader's binlog, the replica errors (`ER_MASTER_HAS_PURGED_REQUIRED_GTIDS`) and needs a fresh snapshot.

### 7.8 Aurora-style shared storage (vendor-specific)
AWS Aurora decouples compute from a distributed, multi-AZ **shared storage** layer; "replicas" share the same storage volume, so they don't replay a logical/physical log the same way — they read from shared storage and only apply in-memory cache updates from a redo stream. Result: very low replica lag (often <100 ms, no full WAL replay) and fast failover (pointer flip). This breaks the usual "each replica re-does all writes" model — explicitly vendor-specific.

### 7.9 Logical decoding & change-data-capture (CDC)
Logical replication's underlying **logical decoding** is the foundation of **CDC**: tools like **Debezium** tail the binlog/WAL and emit row-change events to Kafka, powering search indexes, caches, data lakes, and event-driven microservices *without* dual-writes. This is replication repurposed as an integration backbone — a major modern pattern for Java/Kafka shops.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Log format

| | Statement (SBR) | Row (RBR) | WAL/physical |
|---|---|---|---|
| Size on wire | Smallest | Larger | Medium |
| Determinism | Risky (NOW/RAND/etc.) | Safe | Safe |
| Cross-version | Yes | Yes | **No** (same major version) |
| Subset of tables | Yes | Yes | No (whole cluster) |
| Use when | rarely | **default OLTP** (MySQL) | HA standby/PITR (PG) |

### 8.2 Durability mode

| | Async | Semi-sync | Sync (quorum) |
|---|---|---|---|
| Write latency | Lowest | +1 RTT | + RTT to quorum |
| Data loss on leader crash | Possible (acked writes) | Bounded (≤ one node's loss) | None (within set) |
| Write availability if replica down | Unaffected | Falls back to async after timeout | **Blocks** (unless quorum still met) |
| Use when | most reads-heavy apps tolerant of tiny loss | want low loss cheaply | financial/critical, can afford latency |

### 8.3 Topology

| Topology | Write conflicts? | Min nodes for safe auto-failover | Use when | Avoid when |
|---|---|---|---|---|
| Single-leader | No | 3 (or 2+witness) | Default; most apps | Need local writes in many regions |
| Cascading | No | — | Many replicas, save leader fan-out | Lag-sensitive (additive lag) |
| Multi-leader | **Yes** | — | Multi-region writes, offline clients | You can't tolerate conflict logic |
| Leaderless (Dynamo) | Resolved by quorum/LWW | — | Massive scale, AP needs | You need strong relational consistency |

### 8.4 Replicas vs backups vs partitioning

| Need | Use |
|---|---|
| Survive node/AZ failure (HA) | Replicas + failover |
| Scale **reads** | Read replicas |
| Scale **writes** / dataset too big for one box | **Partitioning/sharding** (not replicas) |
| Recover from human error / corruption / ransomware | **Backups (PITR, immutable)** — replicas don't help |
| Low latency for global users | Geo-distributed replicas (read) or multi-leader (write) |
| Offload analytics | Dedicated replica or logical-replication target |

### 8.5 Decision rules (use when / avoid when)
- **Use read replicas when** read:write is high and reads tolerate small staleness. **Avoid** for read-after-write critical paths unless you add LSN/sticky routing.
- **Use synchronous/quorum when** losing an acked write is unacceptable and you can spend a round-trip; have ≥2 sync candidates so one can be down. **Avoid** single sync standby with no fallback.
- **Use automated failover (Patroni/Orchestrator + DCS) when** you can run ≥3 nodes and need low RTO; **avoid** hand-rolled 2-node auto-failover (split-brain).
- **Use multi-leader when** you truly need multi-region writes/offline; **avoid** if you can shard writes to a home region instead — it sidesteps conflicts.
- **Always keep backups** regardless of replica count.

---

## 9. Failure modes & debugging

### 9.1 Replica falling behind (growing lag)
- **Symptoms:** `Seconds_Behind_Source` climbing (MySQL); `pg_stat_replication.replay_lsn` diverging from `pg_current_wal_lsn` (PG); stale reads.
- **Causes:** single-threaded apply under heavy write load; a giant transaction; long-running query on the replica blocking replay (PG); slow replica IO/CPU; network saturation.
- **Diagnose:** `SHOW REPLICA STATUS\G`; `SELECT * FROM pg_stat_replication;`; on PG check `pg_stat_activity` for `wait_event = 'RecoveryConflict'` or replay being blocked; check replica disk IO (`iostat`), CPU.
- **Fix:** enable/tune parallel apply (`replica_parallel_workers`, `WRITESET`); chunk big DML; isolate analytics queries; upgrade replica IO; for PG query cancels tune `max_standby_streaming_delay` / `hot_standby_feedback`.

### 9.2 Replica broken / stopped applying
- **Symptoms:** `Replica_SQL_Running=No`, `Last_Error` set; `Seconds_Behind_Source = NULL`.
- **Causes:** duplicate-key or missing-row error (data drift between leader/replica), schema mismatch, a statement the replica can't execute.
- **Diagnose:** read `Last_Error` / PG `startup` log; identify the offending GTID/LSN with `mysqlbinlog`/`pg_waldump`.
- **Fix:** repair the data drift; never blindly `SET GLOBAL SQL_SLAVE_SKIP_COUNTER`/skip GTID without understanding — skipping hides drift and compounds it. Often the correct fix is to **rebuild the replica** from a fresh snapshot. Use **pt-table-checksum / pt-table-sync** (Percona Toolkit) to detect and repair drift safely.

### 9.3 Replication slot filling the primary disk (PostgreSQL)
- **Symptom:** primary disk usage climbing toward 100%; `pg_replication_slots.wal_status = 'extended' | 'unreserved' | 'lost'`.
- **Cause:** a standby disconnected/dead but its slot still pins WAL.
- **Diagnose:** `SELECT slot_name, active, wal_status, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) FROM pg_replication_slots;`
- **Fix:** revive the standby, or `SELECT pg_drop_replication_slot('dead_slot');`. Prevent with `max_slot_wal_keep_size`.

### 9.4 Split-brain after failover
- **Symptom:** two nodes accepting writes; diverging row values; clients hitting different "primaries."
- **Cause:** old primary was partitioned, not dead; no fencing/quorum; 2-node cluster.
- **Diagnose:** check who holds the leader lock in etcd/Consul (Patroni: `patronictl list`); check `pg_is_in_recovery()` on each node (should be exactly one `false`).
- **Fix/Prevent:** fencing/STONITH; quorum (3+ nodes); a single source of leadership truth (DCS). Recovery after split-brain means choosing a survivor and reconciling/discarding the divergent writes — painful and lossy; prevention is everything.

### 9.5 Data loss after async failover
- **Symptom:** writes the app saw as committed are gone after promotion.
- **Cause:** promoted a replica that hadn't received those writes (async).
- **Fix/Prevent:** semi-sync/quorum; promote the most-caught-up node; measure RPO and decide if it's acceptable.

### 9.6 Read-your-own-writes bug
- **Symptom:** user submits data, immediately doesn't see it; intermittent and load-dependent.
- **Cause:** read routed to a lagging replica.
- **Fix:** sticky-to-leader window or LSN/GTID-aware routing (§5.5); per-session pin.

### 9.7 Diagnostic command cheat-set
- MySQL: `SHOW REPLICA STATUS\G`, `SHOW REPLICAS`, `SHOW PROCESSLIST`, `mysqlbinlog`, `pt-table-checksum`, `pt-heartbeat` (accurate lag measurement).
- PostgreSQL: `pg_stat_replication`, `pg_stat_wal_receiver`, `pg_replication_slots`, `pg_is_in_recovery()`, `pg_last_xact_replay_timestamp()`, `pg_waldump`, `pg_rewind`, `patronictl list`.

### 9.8 Real-world incident patterns (well-documented categories)
- **GitHub 2018 outage:** a 43-second network partition between US East/West triggered Orchestrator-driven failover; the cross-region topology and write routing led to ~24 hours of degraded service while data was reconciled — a textbook lesson in cross-region failover complexity and the cost of partitions. (Public post-mortem.)
- **PostgreSQL disk-fill from orphaned slots:** a recurring class of outage where a removed/disconnected standby's slot pinned WAL until the primary's disk filled and writes stopped. Prevention is `max_slot_wal_keep_size`.
- **Async-failover data loss:** broadly reported across MySQL shops before semi-sync/GTID adoption; the standard cautionary tale behind moving to semi-sync + Orchestrator.

---

## 10. Interview drill

**Q1. Explain leader-follower replication and why writes go to a single node.**
*Model answer:* One leader accepts all writes and produces an ordered change log; followers replay that log to stay in sync. A single writer gives a total order on writes, eliminating write conflicts — the simplest correct model. Followers serve reads and act as failover targets.
- *Probe: What if you need writes in two regions?* → Multi-leader or sharded-by-home-region; both introduce conflicts or complexity, so prefer a single home leader per record.
- *Probe: How do followers know where to resume after disconnect?* → Log position (file+offset) or, better, GTID set / LSN, which lets them request exactly the missing changes.
- *Probe: Does adding followers scale writes?* → No — each follower re-applies every write; replicas scale reads, sharding scales writes.

**Q2. Statement vs row vs WAL-based replication — tradeoffs?**
*Model answer:* Statement ships SQL (compact, but nondeterministic functions like NOW()/RAND() diverge); row ships actual row before/after images (deterministic, larger, MySQL's default); WAL/physical ships byte-level page changes (faithful and efficient but tied to one major version). PG uses physical streaming; MySQL uses row binlog.
- *Probe: Give a concrete SBR bug.* → `UPDATE t SET ts=NOW()` produces different timestamps on replica.
- *Probe: Why can't you do major-version upgrades with physical replication?* → WAL describes physical page layout, which changes across versions; use logical replication instead.

**Q3. Async vs sync vs semi-sync — when do you lose data?**
*Model answer:* Async: leader acks before replicas receive → crash loses acked writes. Sync: leader waits for durable ack from a (quorum of) standby → no loss within the set, but writes block if the standby is down. Semi-sync: wait for one standby's receipt → bounded loss for one round-trip.
- *Probe: What does MySQL semi-sync actually wait for?* → Replica writing the event to its relay log (with AFTER_SYNC, before the commit is visible) — receipt/durability, not apply.
- *Probe (senior): You need zero data loss but can't tolerate write stalls when a standby reboots. Design it.* → Quorum sync with `ANY 1 (a,b)` over **two** sync candidates so one can be down; or N+1 sync standbys. Single sync standby is the anti-pattern.

**Q4. How does automatic failover avoid split-brain?**
*Model answer:* Use a quorum/consensus source of truth (etcd/Consul via Patroni; Orchestrator with whole-topology detection) so only a majority-backed node is leader, plus **fencing/STONITH** to forcibly stop a recovered old leader. Need 3+ nodes (or a witness) so a partition has a clear majority.
- *Probe: Why odd node counts?* → A majority requires it; even counts add cost without added fault tolerance and can deadlock on tie.
- *Probe: What is Raft's role?* → It guarantees at most one leader per term and that committed log entries survive — the formal backbone of safe election.
- *Probe (senior): Your 2-node primary+standby auto-failover split-brained. What changed?* → Add a third voting member/witness and fencing; move leadership truth into a DCS; 2 nodes cannot form a safe majority.

**Q5. A user complains their just-saved post disappears on refresh. Diagnose.**
*Model answer:* Read routed to a lagging async replica that hasn't applied the write → read-your-own-writes violation. Fix: sticky-to-leader for a window after a user write, or LSN/GTID-aware routing that only uses a replica caught up past the write's position, or pin that user's reads to primary.
- *Probe: Difference between read-your-writes and monotonic reads?* → RYW = see your own writes; monotonic = never see time go backwards (achieve by pinning a session to one replica).

**Q6. What is replication lag, how do you measure it, and what causes spikes?**
*Model answer:* The delay between a write on the leader and its apply on a follower; measure in time and bytes/LSN. Spikes come from single-threaded apply under load, giant transactions, long queries blocking replay (PG), slow IO, network saturation. Track both time and bytes (time lag misleads when the replica is idle).
- *Probe: Why is `Seconds_Behind_Source` unreliable?* → It's NULL when the SQL thread is stopped/broken and can read 0 during idle gaps; use `pt-heartbeat` for accurate measurement.

**Q7. Are replicas a backup? Justify.**
*Model answer (senior):* No. Replicas faithfully replicate destructive operations (`DROP TABLE`, bad `UPDATE`) within milliseconds, so they don't protect against logical corruption, bugs, or human/malicious error. Backups (immutable, point-in-time) do. Keep both; a delayed replica is a partial middle ground.
- *Probe: What's a delayed replica good for?* → A time buffer (e.g., 1h) to halt apply before a disaster propagates and recover faster than full PITR.

**Q8. Walk me through bootstrapping a new replica without downtime.**
*Model answer:* Take a consistent base snapshot recording its exact log position (PG `pg_basebackup` with a slot; MySQL physical backup/XtraBackup/CLONE with GTID), then start streaming from that position. The snapshot position and first streamed change must be contiguous; GTIDs/LSNs/slots make the seam exact and guarantee no WAL is purged before the replica connects.
- *Probe: What if the leader purged needed WAL/binlog first?* → Replica errors and needs a fresh snapshot; that's why slots / sufficient retention exist.

**Q9 (senior).** *You run a read-heavy global app. Design the replication + routing strategy and justify each tradeoff.*
*Model answer:* Single-leader in the primary region (write simplicity), async read replicas in each user region for low-latency local reads, semi-sync or quorum within the primary region for durability. Route writes to the leader; route tolerant reads to the nearest replica; route read-after-write and critical reads to the leader (or LSN-gate them). Automate failover with Patroni/Orchestrator + DCS (3+ voting members) and fencing. Keep PITR backups. Avoid multi-leader unless you genuinely need local *writes*, because conflict resolution is costly; if you do, shard writes by home region to minimize conflicts.
- *Probe: Where does this break under a region partition?* → CAP forces a choice: the cut-off region's replicas serve stale reads (AP) or you stop serving there (CP). State the choice explicitly per read path.

**Q10 (senior).** *Justify choosing semi-sync over full sync for a payments system.*
*Model answer:* Full sync to all replicas makes the system as available and slow as its worst replica and blocks writes on any sync member's downtime. Semi-sync (or quorum `ANY k`) over multiple candidates bounds data loss to effectively zero (an acked write is on ≥1 durable standby) while tolerating one standby's failure and paying only one RTT. The justification is RPO≈0 *and* maintained write availability — the actual business requirement — rather than maximal-but-fragile durability.
- *Probe: What's your RPO and RTO target and how does the config meet it?* → RPO≈0 from quorum-durable writes; RTO from automated failover time (detection + promotion + redirect), typically seconds to low tens of seconds with Patroni/Aurora.

**Q11. Multi-leader conflicts — how do you resolve them?**
*Model answer:* Concurrent edits to the same row on different leaders. Strategies: LWW (simple, lossy, clock-skew-sensitive), application merge logic, CRDTs (mathematically merge-safe types), or avoid via home-region routing. Galera/Group Replication use certification (a transaction that conflicts at certify time is aborted).
- *Probe: Why is LWW dangerous?* → It silently discards one update and depends on synchronized clocks.

**Q12. What's the difference between physical and logical replication in PostgreSQL, and when use each?**
*Model answer:* Physical (streaming/WAL) copies byte-level pages — same major version, whole cluster, ideal HA standby. Logical decodes WAL into row-change events — cross-version, subset of tables, ideal for major-version upgrades, CDC, and feeding analytics. Logical doesn't replicate DDL or sequence values automatically.
- *Probe: How would you do a zero-downtime PG major upgrade?* → Logical replication from old to new major version, cut over once caught up.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability; the transactional guarantees relational DBs provide.
- **Arbiter / Witness** — a voting member that holds no data, used to break ties and form a majority cheaply.
- **Asynchronous replication** — leader acks before followers receive changes; can lose acked writes on crash.
- **Binlog (binary log)** — MySQL's ordered log of data-changing events, used for replication and PITR; distinct from InnoDB's redo log.
- **CAP theorem** — under a network partition you must choose Consistency or Availability.
- **Cascading replication** — a replica replicates from another replica, not the leader; reduces leader fan-out at the cost of additive lag.
- **CDC (Change Data Capture)** — emitting row-change events (often from the replication log) to downstream systems (e.g., Debezium → Kafka).
- **Consensus** — a protocol (Raft, Paxos) by which a majority of nodes agree on a value/leader/log; guarantees at most one leader per term.
- **Consistent prefix reads** — reads observe causally ordered writes in order.
- **CRDT (Conflict-free Replicated Data Type)** — data types whose concurrent updates always merge deterministically.
- **DCS (Distributed Configuration Store)** — etcd/Consul/ZooKeeper; holds the cluster's leadership truth.
- **Delayed replica** — a follower intentionally lagging by a fixed interval as a disaster buffer.
- **etcd / Consul / ZooKeeper** — strongly consistent coordination stores used for leader election and locks.
- **Failover** — promoting a follower to leader after the leader fails and redirecting traffic.
- **Fencing / STONITH** — forcibly isolating/killing a failed leader so it can't accept writes (split-brain prevention).
- **Follower / Replica / Standby / Secondary** — a node replaying the leader's log; serves reads/failover.
- **GTID (Global Transaction Identifier)** — MySQL's globally unique transaction stamp (`uuid:N`) enabling robust positioning and failover.
- **Hot standby** — a PostgreSQL standby that serves read queries while replaying WAL.
- **Leader / Primary / Master / Source** — the single node that accepts writes.
- **Leaderless replication** — no fixed leader; clients use quorums (Dynamo/Cassandra style).
- **Linearizability** — the strongest single-object consistency: reads always see the most recent committed write, as if there were one copy.
- **Logical replication** — replicating decoded row-change events; cross-version, table-subset capable.
- **LSN (Log Sequence Number)** — PostgreSQL's 64-bit position in the WAL stream.
- **LWW (Last-Write-Wins)** — conflict resolution keeping the highest-timestamp write.
- **Monotonic reads** — a session never sees data go backwards in time.
- **MVCC (Multi-Version Concurrency Control)** — keeping multiple row versions so readers don't block writers; old versions are reclaimed by vacuum/purge once unneeded.
- **Multi-leader (multi-master)** — multiple nodes accept writes; introduces conflicts.
- **Oplog** — MongoDB's idempotent operations log used by secondaries.
- **Orchestrator** — MySQL topology management and automated failover tool.
- **PACELC** — extends CAP: if Partition then A or C; Else Latency or Consistency.
- **Partitioning / Sharding** — splitting the dataset across nodes (scales writes/size); orthogonal to replication.
- **Patroni** — PostgreSQL HA supervisor using a DCS for leader election.
- **Physical replication** — byte/page-level WAL shipping; same major version.
- **PITR (Point-In-Time Recovery)** — restoring a backup + replaying logs to an exact time; the real protection against human/logical error.
- **Quorum** — a majority of nodes; needed to agree safely and avoid split-brain.
- **Raft** — a consensus algorithm with elected leaders per term and majority-committed logs.
- **Read replica** — a follower deliberately used to serve read traffic.
- **Read-your-own-writes (read-after-write)** — a user always sees their own prior writes.
- **Relay log** — MySQL replica's local copy of received binlog events before they're applied.
- **Replication lag** — how far behind a follower is (time and/or bytes).
- **Replication slot** — PostgreSQL object ensuring WAL is retained until a consumer has it (can fill disk if abandoned).
- **RPO / RTO** — Recovery Point Objective (max acceptable data loss) / Recovery Time Objective (max acceptable downtime).
- **Row-based replication (RBR)** — shipping actual changed-row images; deterministic; MySQL default.
- **Semi-synchronous replication** — wait for one (N) follower's receipt before acking; bounded data loss.
- **Split-brain** — two nodes believe they are leader and both accept writes; causes divergence.
- **Statement-based replication (SBR)** — shipping the SQL statement; compact but nondeterministic.
- **Streaming replication** — PostgreSQL's continuous WAL streaming to standbys.
- **Synchronous replication** — wait for durable follower ack before acking; no loss within set, but can block.
- **Timeline (PostgreSQL)** — an ID bumped on promotion so divergent WAL histories don't collide.
- **WAL (Write-Ahead Log)** — log written before mutating data pages; basis for crash recovery and physical replication.
- **WAL sender / receiver** — PostgreSQL processes that stream/receive WAL.
- **Writeset** — a transaction's modified-row set used to compute parallel-apply dependencies (MySQL).
- **ZAB (ZooKeeper Atomic Broadcast)** — ZooKeeper's consensus/ordering protocol.

---

## 12. Cheat-sheet & self-test

### One-screen recap
- **Model:** one leader writes an ordered change log; followers replay it. Replicas scale **reads**, not writes. Sharding scales writes.
- **Log formats:** Statement (compact, nondeterministic) | Row (deterministic, MySQL default) | WAL/physical (faithful, same-major-version, PG). Logical = cross-version/subset.
- **Durability dial:** Async (fast, can lose acked writes) | Semi-sync (bounded loss, +1 RTT) | Sync/quorum (no loss in set, can block). PACELC: partition→A/C, else→L/C.
- **Consistency to engineer:** read-your-writes (sticky/LSN-gate), monotonic reads (pin session to one replica), consistent prefix.
- **Failover safety:** quorum (3/5 nodes, odd) + consensus (Raft/etcd) + fencing/STONITH. Promote the **most caught-up** node. Tools: Patroni (PG, etcd lock), Orchestrator (MySQL, topology evidence).
- **Routing:** writes→leader; tolerant reads→nearest replica; critical/read-after-write→leader or LSN-gated. ProxySQL/MaxScale/Pgpool or app-side `AbstractRoutingDataSource`.
- **Hard numbers/defaults:** MySQL `binlog_format=ROW`, `sync_binlog=1`, `innodb_flush_log_at_trx_commit=1`, semi-sync timeout 10000 ms. PG WAL segment 16 MB, page 8 KB, `synchronous_commit=on`, `max_standby_streaming_delay=30s`, `max_wal_senders=10`. Use GTIDs; cap slots with `max_slot_wal_keep_size`.
- **Anti-patterns:** replica-as-backup; 2-node auto-failover; round-robin reads breaking monotonicity; uncapped slots filling disk; promoting the laggiest node; single sync standby with no fallback.
- **Top diagnostics:** `SHOW REPLICA STATUS\G` (watch NULL `Seconds_Behind_Source`); `SELECT * FROM pg_stat_replication;`; `pg_replication_slots`; `patronictl list`; `pt-heartbeat`.
- **Always keep PITR backups** regardless of replica count.

### Self-test (no answers — recall practice)
1. Why does adding ten read replicas not increase write throughput, and what *does*?
2. Give a concrete statement-based replication bug and explain why row-based fixes it.
3. Exactly what does MySQL semi-sync wait for, and why doesn't that guarantee a fresh read on that replica?
4. You have a 2-node primary/standby with automatic failover. Describe the split-brain scenario and the two mechanisms that prevent it.
5. A replica's `Seconds_Behind_Source` reads NULL. What does that mean and what do you check next?
6. Design read routing that guarantees read-your-own-writes without sending all reads to the leader.
7. Why must a PostgreSQL replication slot be capped, and what happens if it isn't?
8. When would you choose multi-leader replication, and what new class of problem does it create — name two resolution strategies.
9. Walk through bootstrapping a new replica so there is neither a gap nor a non-idempotent overlap at the snapshot/stream seam.
10. State PACELC and map async vs sync replication onto its two clauses.

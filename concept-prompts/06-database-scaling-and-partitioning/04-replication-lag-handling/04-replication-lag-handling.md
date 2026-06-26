# Handling Replication Lag

> **Concept area:** Database Scaling & Partitioning
> **Subtopic:** Handling Replication Lag
> **Reader profile:** A senior backend engineer (Java/JVM-centric) who wants to master this subtopic from first principles to deep internals — enough to design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### What it is

**Replication** is the practice of keeping copies of the same data on more than one machine. One copy (or a small set) accepts writes — these are the **leader(s)** (also called *primary*, *master*, or *source*). The other copies — the **followers** (also called *replicas*, *secondaries*, *standbys*, or *read replicas*) — receive a stream of changes from the leader and apply them to their own storage so that, eventually, they hold the same data.

**Replication lag** is the time delay between the moment a write is durably committed on the leader and the moment that same write becomes visible on a given follower. If you write a row at time `T` on the leader, and a follower only reflects that row at `T + 250ms`, the replication lag of that follower (for that write) is 250 milliseconds. Lag is not a single global number — it is per-follower, and it fluctuates moment to moment.

> **Beginner aside — "durably committed":** A write is *durable* once the database guarantees it will survive a crash (typically because it has been written to a transaction log on disk and flushed/`fsync`ed). "Committed" means the transaction is finalized and visible to other transactions on that node. We measure lag from the commit point on the leader, not from when the client first sent the request.

### The problem it solves vs. the problem it creates

Replication exists to solve real problems:

- **Read scaling.** A single leader can only do so much I/O and CPU. By sending reads to many followers, you multiply read throughput. This is the dominant motivation in read-heavy systems (most web apps read far more than they write — ratios of 10:1 to 1000:1 are common).
- **High availability.** If the leader dies, a follower can be promoted to take over (**failover**), so the system survives machine loss.
- **Geographic locality.** Place followers near users (e.g., a replica in `eu-west` for European users) so reads have low latency.
- **Analytics/backup isolation.** Run heavy reporting queries or take backups on a follower so they don't disturb the leader's latency-sensitive workload.

But the moment you serve reads from a follower that might be behind, you introduce **replication lag** and the **read anomalies** it causes. The reader who "just posted a comment but can't see it" is the canonical symptom. This document is about understanding, mitigating, measuring, and reasoning about that lag.

### When you reach for this knowledge

You need this material whenever:

- You add read replicas to scale reads (almost every growing system does this).
- You use a managed primary/replica setup: **Amazon RDS / Aurora read replicas**, **Google Cloud SQL replicas**, **Azure Database replicas**, **PostgreSQL streaming replication**, **MySQL replication**, **MongoDB replica sets**, **Cassandra** (different model — eventual consistency by default), **Redis** primary/replica, **Kafka** (different again — partition replicas with ISR).
- You operate across regions (cross-region lag is much larger — tens to hundreds of milliseconds even in steady state).
- You debug a "ghost data" bug: data that exists but intermittently appears missing.

### One-paragraph mental model

> Think of the leader as a person writing entries into a master ledger, and each follower as a clerk copying those entries into their own ledger by reading a feed of changes. The feed is ordered and reliable, but the clerks are not instantaneous — they fall behind when the writer is fast, when a clerk is busy with a big single entry, or when the feed is slow. If you ask a *random clerk* a question right after the writer made an entry, the clerk may not have copied it yet and will answer "no such entry" — even though it definitely exists in the master ledger. **Replication lag handling is the discipline of deciding which clerk to ask, and how to compensate when the answer might be stale, so the application never observes an impossible or confusing version of reality.**

---

## 2. Foundations from first principles

### 2.1 The single-node baseline

Start with one database node. Every read and every write goes to it. There is no lag and no anomaly — every read reflects every prior committed write. This is **strong consistency** in its simplest form: the system behaves as if there is exactly one copy of the data and operations happen one at a time in a total order. The price is that this one node is a bottleneck for throughput and a single point of failure.

### 2.2 Adding replicas: the leader/follower model

The most common topology is **single-leader (a.k.a. leader-based, master-slave) replication**:

```
                  writes
client ───────────────────────────▶  ┌─────────┐
                                      │ LEADER  │
                                      └────┬────┘
                                           │ replication stream (ordered changes)
                          ┌────────────────┼────────────────┐
                          ▼                ▼                ▼
                    ┌──────────┐    ┌──────────┐     ┌──────────┐
                    │FOLLOWER 1│    │FOLLOWER 2│ ... │FOLLOWER N│
                    └──────────┘    └──────────┘     └──────────┘
                          ▲                ▲                ▲
                          └──────── reads (load-balanced) ──┘
```

- **All writes go to the leader.** The leader serializes writes into a single ordered log.
- **Reads can go to the leader or to any follower.** Sending reads to followers is what scales reads — and what introduces lag.

> **Beginner aside — other replication topologies (so the terms aren't a surprise later):**
> - **Multi-leader (master-master):** more than one node accepts writes; used across data centers. Introduces *write conflicts* on top of lag.
> - **Leaderless (Dynamo-style, e.g., Cassandra, Riak, DynamoDB):** clients write to several replicas directly and read from several; consistency is tuned with **quorums** (read R + write W replicas such that R + W > N gives overlap). "Lag" here manifests as replicas disagreeing until repair.
> This chapter focuses on single-leader, because that is where "replication lag" is most precisely defined and most commonly handled. Leaderless gets its own treatment in §7.

### 2.3 What "the replication stream" actually contains

The leader must tell followers what changed. The three classic strategies:

| Method | What is shipped | Pros | Cons | Real examples |
|---|---|---|---|---|
| **Statement-based** | The SQL statement itself (`UPDATE … WHERE …`) | Compact | Nondeterministic functions (`NOW()`, `RAND()`, auto-increment, triggers) replicate wrong; order-dependence | Old MySQL default (pre-5.1.12) |
| **Write-ahead-log (WAL) shipping / physical** | The exact byte-level changes to the storage pages/log | Deterministic, exact | Tightly coupled to storage format & version (can't replicate across versions easily) | PostgreSQL streaming replication (WAL) |
| **Logical / row-based** | The actual rows that changed (before/after images) | Deterministic, version-portable, decodable by external consumers | Larger than statement-based | MySQL row-based binlog (`binlog_format=ROW`), PostgreSQL logical replication (`pgoutput`) |

> **Beginner aside — WAL (Write-Ahead Log):** Before a database modifies its data files, it first appends a record describing the change to an append-only log on disk. This guarantees durability (you can replay the log after a crash) and gives a natural, ordered stream to ship to followers. PostgreSQL's WAL, MySQL's *binlog*, and SQL Server's transaction log all play this role. The key property for us: **the log is totally ordered**, so followers apply changes in the same order the leader committed them.

### 2.4 Synchronous vs. asynchronous vs. semi-synchronous replication

This single choice determines whether lag can even exist on the path you care about.

- **Asynchronous replication (the default almost everywhere):** The leader commits and acknowledges the client *immediately*, then ships changes to followers in the background. **Followers can lag arbitrarily.** If the leader crashes before a follower received a write, that write can be **lost** on failover. Fast writes, weakest guarantee.
- **Synchronous replication:** The leader waits for one (or more) followers to confirm they durably received/applied the write *before* acknowledging the client. **Zero lag to the synchronous follower(s)** for committed data, but writes are slower and *block entirely* if the synchronous follower is down or slow (availability hit).
- **Semi-synchronous:** A pragmatic middle ground — the leader waits for *at least one* follower to acknowledge receipt (not necessarily apply), so you get durability without waiting on all replicas. If the chosen follower stalls, the system often *degrades to async* automatically after a timeout (MySQL `rpl_semi_sync_master_timeout`, default 10000 ms = 10s).

> **Why this matters for lag:** Even with synchronous replication, vendors usually only make *one or a few* followers synchronous. The *other* followers you read from are still asynchronous and still lag. So "we have a synchronous replica" rarely means "all my read replicas are caught up."

> **Subtle point — "received" vs. "applied":** A follower can have *received* a WAL/binlog record (it's in the follower's own log) but not yet *applied* it (the change isn't visible to queries on the follower yet). PostgreSQL exposes both: `write_lag`, `flush_lag`, and `replay_lag`. **Read anomalies are governed by *replay* lag**, because reads see applied state. A follower can be "synchronous" at the *flush* level (durable) yet still lag at the *replay* level (visible).

### 2.5 Consistency models — the vocabulary you must internalize

Replication lag is best understood through the lens of **consistency guarantees**. From strongest to weakest, the ones that matter here:

1. **Linearizability (a.k.a. strong consistency, atomic consistency):** The system behaves as if there is a single copy and every operation takes effect at a single instant between its start and end. Once a write completes, *every* subsequent read (by anyone) sees it. Lag-induced anomalies are impossible. Expensive; usually requires reading from the leader or a consensus quorum.

2. **Sequential consistency:** All clients see operations in *some* single total order, consistent with each client's own program order — but not necessarily real-time order.

3. **Causal consistency:** Operations that are causally related (B "happens after" A because B read A's effect or the same client did A then B) are seen by everyone in that order; concurrent operations may be seen in different orders. Strong enough to eliminate most user-visible weirdness, far cheaper than linearizability.

4. **Read-your-writes (a.k.a. read-after-write) consistency:** A *session*-scoped guarantee: a client that wrote a value will always see *at least* that value (or newer) on subsequent reads in the same session. It says nothing about what *other* clients see. This is the single most important guarantee for fixing the "I posted but can't see it" bug.

5. **Monotonic reads:** Within a session, reads never go *backwards in time*. If you saw version `v5`, a later read won't show `v3`. (Without this, hitting different followers can make data appear to "un-happen.")

6. **Consistent prefix reads:** If a sequence of writes happens in a certain order, anyone reading them sees them in that order (you never see an answer before its question). Important for causally ordered streams.

7. **Bounded staleness:** Reads may be stale, but never by more than a bounded amount — e.g., "at most 5 seconds old" or "at most 100 versions behind." A tunable middle ground.

8. **Eventual consistency:** The weakest useful guarantee: if writes stop, all replicas *eventually* converge to the same value. No bound on *when*. This is the default for many followers and for leaderless stores.

> **Beginner aside — "session":** A session here is a logical scope tied to one user or one client connection/sequence of requests — e.g., everything one logged-in user does in their browser tab. Session-scoped guarantees (read-your-writes, monotonic reads) only promise consistency *for that user's own view*, which is exactly what end users notice.

> **The crucial reframing:** "Eventual consistency" and "read-your-writes consistency" are *not the same level of weak*. Eventual consistency makes no promise about *your own* writes. Read-your-writes is a much stronger, session-scoped promise that fixes the most jarring user-facing bug while still letting you use lagging replicas. **Most "replication lag handling" is really "buying back read-your-writes (and sometimes monotonic reads) on top of an eventually-consistent fleet of replicas."**

### 2.6 The three canonical anomalies (define them precisely)

Replication lag does not corrupt data — the leader's data is correct and followers converge. It corrupts the *observed view*. Three named anomalies:

**(A) Read-your-writes violation (stale read of your own write).**
You write, then read, and don't see your own write because your read hit a lagging follower.
*Symptom:* "I updated my profile / posted a comment / placed an order, then the page reloaded and it's gone." It usually "fixes itself" on refresh (because lag closed, or a refresh hit a caught-up replica), which makes it maddening to reproduce and report.

**(B) Monotonic-read violation (time going backwards).**
Two successive reads in the same session hit two *different* followers with different lag. The first follower was caught up (showed `v5`); the second was further behind (showed `v3`). Data appears to *regress*.
*Symptom:* "The comment count was 42, I refreshed and it's 40," or a list item appears then disappears then reappears. Caused by **load balancing across replicas with uneven lag**.

**(C) Stale read (generic) / consistent-prefix violation.**
A read returns data older than some other observer's, or returns a causally inconsistent slice (you see Mr. Poons's *answer* before his *question* because the two writes were on different partitions replicated at different speeds).
*Symptom:* dashboards that disagree, "the total doesn't match the line items," cross-entity reads that violate an invariant the application assumed.

> **The unifying insight:** All three come from the *same* root cause — **a read was served from a node that had not yet applied a write the reader's correctness depends on.** Every mitigation below is some way of *choosing the read node* or *waiting until it's caught up enough*.

---

## 3. How it works internally

This section traces the full lifecycle so you can reason about *where* lag comes from and *where* you can intervene. We use PostgreSQL streaming replication and MySQL replication as the two canonical concrete examples, then generalize.

### 3.1 The write path on the leader (PostgreSQL example)

1. **Client sends `COMMIT`** for a transaction that modified rows.
2. **Leader generates WAL records** describing every change (heap tuple inserts/updates, index changes, etc.) and appends them to the in-memory WAL buffer, then to the WAL on disk.
3. **Leader flushes (`fsync`) the WAL** up to this transaction's commit record (the **LSN**, *Log Sequence Number* — a monotonically increasing byte offset into the WAL).
4. **Commit visibility:** the transaction is now durable and visible to other transactions on the leader.
5. **Acknowledge the client** (for async replication, this happens now, before any follower has the data). For synchronous replication, step 5 is *delayed* until step 7 (below) reports the required followers have confirmed.

> **Beginner aside — LSN (Log Sequence Number):** A 64-bit position in the WAL stream, printed like `16/B374D848`. It's the universal "how far along are we" cursor. Every replica tracks the LSN it has *received*, *flushed*, and *replayed*. Comparing the leader's current LSN to a follower's replayed LSN is *the* way to measure lag in bytes; converting that to time gives lag in seconds. **Memorize: LSN is the clock of replication.**

### 3.2 The replication transport

6. **The WAL sender process** on the leader streams WAL records to a **WAL receiver process** on each follower over a TCP connection (the *replication connection*). This is a continuous push (with the follower also able to request a starting LSN on reconnect).
7. **The follower writes the received WAL to its own disk** (`write` → then `flush`/`fsync`) and reports back its `write_lsn` and `flush_lsn`. *If* this follower is a synchronous standby and configured for `remote_write`/`on`, the leader now releases the client (step 5).

### 3.3 The apply (replay) path on the follower — where read visibility happens

8. **The follower's startup/recovery process replays the WAL records**, applying the changes to the follower's own data pages. After replaying past a commit record, that committed data becomes **visible to read queries on the follower**.
9. **The follower reports its `replay_lsn`** back to the leader.

**This step 8 is the bottleneck that produces *visible* lag.** Even if WAL arrived instantly (step 7), the follower may be slow to *apply* it.

> **MySQL parallel:** The leader writes the **binlog**. Each follower runs an **I/O thread** that copies the binlog into a local **relay log**, and one or more **SQL (applier) threads** that replay the relay log into the follower's tables. `Seconds_Behind_Master` (now `Seconds_Behind_Source`) in `SHOW REPLICA STATUS` measures applier lag. Historically MySQL replication was *single-threaded on the applier*, which is the #1 historical cause of MySQL replica lag; modern MySQL supports **multi-threaded replication (MTR)** via `replica_parallel_workers` / `replica_parallel_type=LOGICAL_CLOCK`.

### 3.4 The state machine of a single write's visibility

For one write, from a given follower's perspective:

```
  [committed on leader]               (leader ACKs client if async)
         │  LSN = L
         ▼
  [WAL streamed to follower]          (network transit time)
         │  follower.write_lsn  → L
         ▼
  [WAL flushed on follower]           (follower fsync; durability achieved)
         │  follower.flush_lsn  → L
         ▼
  [WAL replayed on follower]          (data now VISIBLE to reads here)
         │  follower.replay_lsn → L
         ▼
  [visible to reads on this follower]
```

**Replication lag (the kind that causes anomalies) = wall-clock time from "committed on leader" to "replayed on follower."**

### 3.5 The seven concrete causes of lag (control/data-flow view)

Knowing *why* lag spikes tells you which mitigation and which metric to reach for:

1. **Write burst / throughput overload.** The leader commits faster than the follower can replay. The follower falls behind and the backlog grows; lag climbs roughly linearly until the burst ends. *Most common cause.*
2. **Single-threaded apply.** If the follower applies serially (classic MySQL) while the leader had many parallel writers, the follower simply can't keep up. *Cause of chronic MySQL lag.*
3. **Long-running queries on the follower blocking replay.** On PostgreSQL hot standbys, a long analytics query holding a snapshot can conflict with WAL replay (e.g., the WAL wants to vacuum/remove rows the query still needs). Replay pauses (or the query is cancelled), depending on `max_standby_streaming_delay`/`hot_standby_feedback`. *Cause of lag spikes during reporting.*
4. **Network latency/bandwidth.** Cross-region transport adds steady-state lag (physics: ~5 ms per 1000 km one-way minimum); congestion adds variance. *Cause of baseline cross-region lag.*
5. **Disk I/O saturation on the follower.** Replay is write-heavy; if the follower's disk is slow or busy (e.g., also taking a backup), replay slows. *Cause of correlated lag + I/O metrics.*
6. **A single large transaction.** A `DELETE` of 50M rows, a schema migration, or a bulk import is one big chunk that must be applied as a unit; the follower can stall for the whole duration. *Cause of sudden cliff-edge lag.*
7. **Lock/contention or replication conflicts on the follower.** Conflicts between replay and local activity stall apply. *Cause of intermittent lag.*

### 3.6 Why lag is *unbounded* in the worst case

Asynchronous replication offers **no upper bound** on lag. If a follower is offline for an hour and comes back, it must replay an hour of WAL — during which it is an hour behind (and you must *not* read from it as if it were current). During failover or a sustained write spike, lag of *minutes* on busy systems is routine; multi-hour catch-up after an outage is common. **This is why every mitigation must treat lag as potentially large and changing, never as "a few milliseconds, ignore it."**

---

## 4. The complete toolkit

This section enumerates (a) the *mitigation techniques* with their parameters, (b) the *database-level knobs* per engine, and (c) the *measurement/observability* surface.

### 4.1 Mitigation techniques (the application-level patterns)

| Technique | Guarantee it buys | Where it lives | Key parameters / inputs | Cost / downside |
|---|---|---|---|---|
| **Read-from-leader-after-write** | Read-your-writes | Routing layer | A time/condition window after a write during which reads go to leader | Loads the leader; window must be tuned |
| **Sticky / session routing to one follower** | Monotonic reads (per session) | Load balancer / router | Hash key (user/session id) → fixed replica | Hot replicas; rebalancing on topology change |
| **Write-timestamp / LSN token (read-after-write via token)** | Read-your-writes, monotonic reads | App + router | The LSN/GTID/timestamp returned on write; carried by client | Need to surface and propagate the token |
| **Wait-for-replica (block until caught up to token)** | Read-your-writes (precise) | Router/DB | Target LSN/GTID + timeout | Adds latency to the read; needs DB support |
| **Bounded-staleness reads** | Bounded staleness | Router | `max_staleness` (seconds or versions) | Rejects/redirects reads when no replica is fresh enough |
| **Causal-consistency tokens (e.g., MongoDB cluster time)** | Causal consistency | Driver/session | `afterClusterTime` / causal-consistent session | Driver support required; small overhead |
| **Always-read-leader for critical paths** | Strong (linearizable-ish) | Routing policy | List of "must be fresh" operations | No read scaling for those paths |
| **Quorum reads (leaderless)** | Tunable (R+W>N) | Client/coordinator | R, W, N | Higher read latency; not single-leader |
| **Read-then-verify-then-retry** | Best-effort RYW | App | Retry count, backoff | Latency on miss; not a guarantee |

### 4.2 The "where do I send this read?" decision is the core API

In practice you build (or configure) a **router** that, per query, decides:

- **Leader** (always fresh, costs leader capacity), or
- **A specific follower** (sticky), or
- **Any follower that is fresh enough** (bounded staleness / token check), or
- **Any follower** (don't care, eventual is fine).

Everything in §4.1 is a policy feeding that one decision.

### 4.3 PostgreSQL: replication & lag knobs

| Parameter / function | Where | Purpose | Default (flag if version-specific) |
|---|---|---|---|
| `wal_level` | server | Level of WAL detail (`replica`, `logical`) | `replica` (PG10+) |
| `synchronous_commit` | server/session | `on`, `remote_apply`, `remote_write`, `local`, `off` — controls how much follower confirmation a commit waits for | `on` |
| `synchronous_standby_names` | server | Which standbys are synchronous (and quorum like `ANY 2 (s1,s2,s3)`) | empty (all async) |
| `hot_standby` | standby | Allow read queries on a standby | `on` (PG10+) |
| `hot_standby_feedback` | standby | Standby tells leader about its oldest snapshot to prevent it from vacuuming rows the standby still needs (reduces query-cancel, can bloat leader) | `off` |
| `max_standby_streaming_delay` | standby | Max time replay waits before cancelling a conflicting query | `30s` |
| `recovery_min_apply_delay` | standby | *Intentionally* delay apply (for a time-delayed standby) | `0` |
| `pg_stat_replication` | view (on leader) | Per-follower `sent_lsn`, `write_lsn`, `flush_lsn`, `replay_lsn`, and `write_lag`/`flush_lag`/`replay_lag` | — |
| `pg_last_wal_replay_lsn()` | function (on standby) | The LSN this standby has replayed | — |
| `pg_current_wal_lsn()` | function (on leader) | Leader's current WAL position | — |
| `pg_wal_lsn_diff(a, b)` | function | Bytes between two LSNs (turn LSN gap into a number) | — |
| `pg_last_xact_replay_timestamp()` | function (standby) | Commit timestamp of last replayed transaction (→ time lag) | — |

**Compute lag on a PostgreSQL standby (time-based):**

```sql
-- On the STANDBY. If it is actively replaying, lag ≈ now() - last replayed commit ts.
-- If no new transactions are coming, this can read high even when caught up,
-- so also check that WAL is actually still being received.
SELECT
  CASE
    WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
    ELSE EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))
  END AS replay_lag_seconds;
```

**Compute lag on the leader (byte-based, per follower):**

```sql
-- On the LEADER.
SELECT
  application_name,
  client_addr,
  state,
  pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS replay_lag_bytes,
  replay_lag         -- interval, available PG10+
FROM pg_stat_replication;
```

### 4.4 MySQL: replication & lag knobs

| Parameter / command | Purpose | Default (flag version) |
|---|---|---|
| `binlog_format` | `ROW` / `STATEMENT` / `MIXED` | `ROW` (MySQL 8.0+) |
| `rpl_semi_sync_master_enabled` / `rpl_semi_sync_source_enabled` | Enable semi-sync on source | `OFF` |
| `rpl_semi_sync_master_timeout` | Fall back to async after this many ms | `10000` (10s) |
| `replica_parallel_workers` (`slave_parallel_workers`) | Parallel applier threads | `0`→`4` (varies by version; 8.0.27+ defaults to 4) |
| `replica_parallel_type` | `LOGICAL_CLOCK` for safe parallel apply | `LOGICAL_CLOCK` (8.0+) |
| `replica_preserve_commit_order` | Keep commit order with parallel apply | `ON` (8.0+) |
| `SHOW REPLICA STATUS` (`SHOW SLAVE STATUS`) | Includes `Seconds_Behind_Source` | — |
| `gtid_mode` | Use Global Transaction IDs for tracking/failover | `OFF` (often turned `ON`) |
| `WAIT_FOR_EXECUTED_GTID_SET(gtid_set, timeout)` | Block until the replica has executed a GTID set (this is *the* read-your-writes primitive) | — |
| `MASTER_POS_WAIT()/SOURCE_POS_WAIT()` | Block until replica reaches a binlog position | — |

> **Beginner aside — GTID (Global Transaction ID):** A globally unique identifier MySQL assigns to each committed transaction (e.g., `3E11FA47-...:23`). Because it's unique cluster-wide and ordered, a client can take the GTID of its own write and later ask any replica "have you executed up to this GTID yet?" — the foundation of token-based read-your-writes in MySQL.

> **Caveat on `Seconds_Behind_Source`:** It is famously imperfect. It's computed from the timestamp of the event being applied vs. the replica's clock; it shows `NULL` when replication is broken, can read `0` when the replica is idle but not actually caught up, and can spike misleadingly during large transactions or relay-log download. Prefer GTID-based "has it executed my transaction?" checks for correctness, and use `Seconds_Behind_Source` only as a coarse health gauge.

### 4.5 MongoDB: replica sets & causal consistency

| Mechanism | Purpose |
|---|---|
| **Read preference** (`primary`, `primaryPreferred`, `secondary`, `secondaryPreferred`, `nearest`) | Where reads go |
| **Read concern** (`local`, `available`, `majority`, `linearizable`, `snapshot`) | What consistency the read demands |
| **Write concern** (`w: 1`, `w: "majority"`, `j: true`, `wtimeout`) | How many nodes must ack a write |
| **Causally consistent sessions** (`startSession({ causalConsistency: true })`) | Driver tracks `operationTime`/cluster time so reads in the session see your prior writes even on secondaries |
| **`maxStalenessSeconds`** (on read preference) | Bounded staleness: only route to secondaries within N seconds of primary (min 90s) | 
| **`db.printSecondaryReplicationInfo()` / `rs.printSecondaryReplicationInfo()`** | Per-secondary lag | 
| **`rs.status()`** → `optimeDate` per member | Compute lag = primary.optimeDate − secondary.optimeDate |

### 4.6 Managed clouds

- **Amazon RDS / Aurora:** CloudWatch metric `ReplicaLag` (RDS, in seconds) and `AuroraReplicaLag` (Aurora, in **milliseconds** — Aurora uses a shared storage layer so lag is typically single-digit to low double-digit ms). Aurora replicas read from the same storage volume, so their lag model differs from classic streaming replication. **RDS Proxy** can help with connection management but does not by itself give read-your-writes.
- **GCP Cloud SQL / Azure:** expose replica lag metrics; same async semantics.

---

## 5. Code examples by use case

Examples default to **Java/JVM** with Spring where the topic is framework-relevant, plus SQL/driver snippets. Each addresses a *different* scenario.

### 5.1 Use case: "Read-your-writes via leader-pinning for a short window" (the 90% solution)

After a user mutation, route *their* reads to the leader for a short window (e.g., a few seconds — comfortably above your p99 replication lag). Simple, robust, no DB-level token plumbing.

```java
/**
 * A per-user "read freshness" gate. After a write, we mark the user as "must read
 * from leader until T". Reads check the gate to decide leader vs. replica.
 *
 * Window should be > p99 replication lag (measure it!). Too short → RYW violations.
 * Too long → unnecessary leader load.
 */
public class ReadAfterWriteGate {

    // userId -> epoch millis until which this user must read from the leader.
    // In a multi-instance deployment this MUST be shared (e.g., Redis), not a local map,
    // because the user's next request may hit a different app server.
    private final StringRedisTemplate redis;
    private final long windowMillis;

    public ReadAfterWriteGate(StringRedisTemplate redis, long windowMillis) {
        this.redis = redis;
        this.windowMillis = windowMillis; // e.g., 3000 ms; tune from measured lag
    }

    /** Call right after a successful write for this user. */
    public void markWrote(String userId) {
        // SET key value PX windowMillis  → auto-expires; no cleanup needed.
        redis.opsForValue().set(key(userId), "1", java.time.Duration.ofMillis(windowMillis));
    }

    /** Returns true if this user's reads must currently go to the leader. */
    public boolean mustReadFromLeader(String userId) {
        return Boolean.TRUE.equals(redis.hasKey(key(userId)));
    }

    private String key(String userId) { return "raw:" + userId; }
}
```

Wiring it into Spring's routing datasource:

```java
/**
 * Spring AbstractRoutingDataSource picks a target DataSource per "lookup key".
 * We set the key in a ThreadLocal before the query runs.
 */
public class RoutingDataSource extends org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource {
    public enum Route { LEADER, REPLICA }
    private static final ThreadLocal<Route> CURRENT = ThreadLocal.withInitial(() -> Route.REPLICA);

    public static void use(Route r) { CURRENT.set(r); }
    public static void clear()       { CURRENT.remove(); }

    @Override protected Object determineCurrentLookupKey() { return CURRENT.get(); }
}
```

```java
@Service
public class ProfileService {
    private final ReadAfterWriteGate gate;
    private final ProfileRepository repo;

    public ProfileService(ReadAfterWriteGate gate, ProfileRepository repo) {
        this.gate = gate; this.repo = repo;
    }

    @Transactional // writes always hit the leader by definition
    public void updateBio(String userId, String bio) {
        RoutingDataSource.use(RoutingDataSource.Route.LEADER);
        try {
            repo.updateBio(userId, bio);
            gate.markWrote(userId);          // open the leader-read window for this user
        } finally {
            RoutingDataSource.clear();
        }
    }

    public Profile getProfile(String userId) {
        // If the user wrote recently, read from leader to guarantee read-your-writes.
        RoutingDataSource.use(gate.mustReadFromLeader(userId)
                ? RoutingDataSource.Route.LEADER
                : RoutingDataSource.Route.REPLICA);
        try {
            return repo.findByUserId(userId);
        } finally {
            RoutingDataSource.clear();
        }
    }
}
```

**Why this works:** the only reader who *needs* freshness for a given write is the user who made it; we pay leader cost only for them, only briefly. **Pitfall:** the gate must be *shared across app instances* (hence Redis), or a load-balanced next request will bypass it.

### 5.2 Use case: "Token-based read-your-writes with PostgreSQL LSN (precise, no fixed window)"

Instead of a time window, capture the *exact* LSN of the write and only read from a replica that has replayed at least that LSN; otherwise fall back to the leader.

```java
/** Capture the leader's commit LSN immediately after a write, in the same transaction. */
public class LsnAwareWriteDao {
    private final JdbcTemplate leaderJdbc;

    public LsnAwareWriteDao(JdbcTemplate leaderJdbc) { this.leaderJdbc = leaderJdbc; }

    @Transactional
    public String insertCommentAndGetLsn(long postId, String body) {
        leaderJdbc.update("INSERT INTO comments(post_id, body) VALUES (?, ?)", postId, body);
        // pg_current_wal_lsn() returns the current write position; after our insert+flush
        // it is >= our commit LSN, which is sufficient as a "read at least this far" token.
        return leaderJdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
    }
}
```

```java
/** Choose a replica only if it has replayed past the token LSN; else use the leader. */
public class FreshnessRouter {
    private final List<JdbcTemplate> replicas;   // one JdbcTemplate per replica
    private final JdbcTemplate leader;

    public FreshnessRouter(JdbcTemplate leader, List<JdbcTemplate> replicas) {
        this.leader = leader; this.replicas = replicas;
    }

    public JdbcTemplate pickFor(String requiredLsn) {
        if (requiredLsn == null) return anyReplicaOrLeader();
        for (JdbcTemplate r : replicas) {
            String replayed = r.queryForObject("SELECT pg_last_wal_replay_lsn()::text", String.class);
            // pg_wal_lsn_diff(replayed, required) >= 0  means replica is caught up to token.
            Long diff = r.queryForObject(
                "SELECT pg_wal_lsn_diff(?::pg_lsn, ?::pg_lsn)", Long.class, replayed, requiredLsn);
            if (diff != null && diff >= 0) return r;     // fresh enough
        }
        return leader; // no replica caught up yet → leader guarantees freshness
    }

    private JdbcTemplate anyReplicaOrLeader() {
        return replicas.isEmpty() ? leader : replicas.get(0);
    }
}
```

**Why this is better than a fixed window:** it self-tunes. When lag is low it uses replicas immediately; when lag is high it falls back to the leader exactly as long as needed — no guessing the window. **Cost:** one extra round-trip to check the replica's LSN (mitigate by caching each replica's replayed LSN, refreshed every ~100 ms by a background poller instead of per-request).

### 5.3 Use case: "MySQL GTID-based wait-for-replica"

```java
/** After writing on the leader, fetch the GTID set the leader has executed. */
public class GtidWriteDao {
    private final JdbcTemplate leader;
    public GtidWriteDao(JdbcTemplate leader) { this.leader = leader; }

    @Transactional
    public String placeOrderAndGetGtid(long userId, long itemId) {
        leader.update("INSERT INTO orders(user_id, item_id) VALUES (?, ?)", userId, itemId);
        // @@gtid_executed = the set of all transactions the leader has committed.
        return leader.queryForObject("SELECT @@gtid_executed", String.class);
    }
}
```

```java
public class GtidFreshnessRouter {
    private final JdbcTemplate replica;
    private final JdbcTemplate leader;
    public GtidFreshnessRouter(JdbcTemplate leader, JdbcTemplate replica) {
        this.leader = leader; this.replica = replica;
    }

    public JdbcTemplate pick(String requiredGtidSet, int timeoutSeconds) {
        if (requiredGtidSet == null) return replica;
        // Returns 0 if the set was already/became executed within timeout, NULL/-1 on timeout.
        Integer result = replica.queryForObject(
            "SELECT WAIT_FOR_EXECUTED_GTID_SET(?, ?)", Integer.class,
            requiredGtidSet, timeoutSeconds);
        return (result != null && result == 0) ? replica : leader;
    }
}
```

`WAIT_FOR_EXECUTED_GTID_SET` *blocks* until the replica catches up (or times out), so this both *checks* and *waits* — at the cost of read latency during lag. Set a small timeout (e.g., 1–2 s) and fall back to the leader.

### 5.4 Use case: "Monotonic reads via sticky routing (consistent hashing)"

Keep a given user pinned to the same replica so their reads never go backwards in time.

```java
/** Hash userId to a stable replica index so the same user always hits the same replica. */
public class StickyReplicaRouter {
    private final List<JdbcTemplate> replicas;
    public StickyReplicaRouter(List<JdbcTemplate> replicas) { this.replicas = replicas; }

    public JdbcTemplate forUser(String userId) {
        // Stable hash → stable replica. Using a strong hash avoids skew vs. String.hashCode().
        int idx = Math.floorMod(
            com.google.common.hash.Hashing.murmur3_32_fixed().hashUnencodedChars(userId).asInt(),
            replicas.size());
        return replicas.get(idx);
    }
}
```

**Why it gives monotonic reads:** since all of a user's reads hit one replica, and a single replica's replayed state only moves forward, the user never sees data regress. **Pitfalls:** (1) a *hot* user can overload one replica; (2) when the replica set changes (add/remove), the mapping shifts and the guarantee briefly breaks — use **consistent hashing** to minimize remapping; (3) if that replica falls badly behind, the user gets consistently stale (monotonic but stale) data — combine with bounded staleness.

### 5.5 Use case: "MongoDB causally consistent session" (driver does the token-plumbing for you)

```java
import com.mongodb.client.*;
import com.mongodb.*;

try (MongoClient client = MongoClients.create("mongodb://.../?replicaSet=rs0")) {
    // A causally consistent session tracks cluster time; reads see this session's prior writes
    // even when reading from secondaries.
    ClientSessionOptions opts = ClientSessionOptions.builder().causallyConsistent(true).build();
    try (ClientSession session = client.startSession(opts)) {
        MongoCollection<org.bson.Document> coll =
            client.getDatabase("app").getCollection("posts")
                  // majority write so it's durable + part of the causal chain
                  .withWriteConcern(WriteConcern.MAJORITY)
                  // read from a secondary but with majority read concern for causal guarantee
                  .withReadPreference(ReadPreference.secondaryPreferred())
                  .withReadConcern(ReadConcern.MAJORITY);

        coll.insertOne(session, new org.bson.Document("user", "pavan").append("body", "hi"));
        // This read, in the same causal session, is guaranteed to observe the insert above,
        // even from a secondary — the driver waits via afterClusterTime under the hood.
        long count = coll.countDocuments(session, new org.bson.Document("user", "pavan"));
    }
}
```

This is the cleanest model: the application says "this work is causally related" and the driver enforces read-your-writes/monotonic reads via cluster-time tokens. Note it requires `readConcern: majority` + a causal session to hold.

### 5.6 Use case: "Bounded staleness gate" — reject/redirect when no replica is fresh enough

```java
/** Only serve a read from a replica whose lag is within maxStaleness; else use leader. */
public class BoundedStalenessRouter {
    private final ReplicaLagProbe probe;     // background-updated per-replica lag (seconds)
    private final Map<String, JdbcTemplate> replicas;
    private final JdbcTemplate leader;
    private final double maxStalenessSeconds;

    public BoundedStalenessRouter(JdbcTemplate leader, Map<String, JdbcTemplate> replicas,
                                  ReplicaLagProbe probe, double maxStalenessSeconds) {
        this.leader = leader; this.replicas = replicas;
        this.probe = probe; this.maxStalenessSeconds = maxStalenessSeconds;
    }

    public JdbcTemplate pick() {
        return replicas.entrySet().stream()
            .filter(e -> probe.lagSeconds(e.getKey()) <= maxStalenessSeconds)
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(leader);   // no replica fresh enough → leader
    }
}
```

`ReplicaLagProbe` is a scheduled poller (e.g., `@Scheduled(fixedDelay=200)`) that runs the lag query from §4.3/§4.4 against each replica and caches the result, so the hot path never pays for a lag check.

### 5.7 Use case: "Don't break the UI" — optimistic local echo (front-end mitigation)

Even with perfect routing, sometimes the cheapest fix is to not depend on the read at all: after a successful write, the client *renders the new state from the response* rather than re-fetching. This sidesteps replica lag for the user's own action entirely. Pair with eventual reconciliation on the next natural refresh.

```javascript
// Pseudo front-end: render from the write's own response; don't re-read a replica immediately.
const res = await api.postComment(postId, body);     // server returns the created comment
addCommentToDom(res.comment);                          // optimistic/local echo — no replica read
// Later background refresh reconciles with server truth.
```

This is not a *database* technique, but in interviews and real systems it's frequently the correct, cheapest answer to "I posted but can't see it."

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Leader-read windows cost leader capacity.** If 100% of users are in their post-write window simultaneously (e.g., a flash event), you've effectively disabled read scaling. Keep windows tight; prefer token-based checks that release as soon as a replica catches up.
- **Per-request lag checks add round-trips.** Never run `pg_last_wal_replay_lsn()`/`SHOW REPLICA STATUS` *per read*. Poll in the background (every 100–500 ms) and cache.
- **Sticky routing causes hotspots.** Monitor per-replica QPS; rebalance if one replica carries disproportionate load.
- **`hot_standby_feedback=on` trades replica query stability for leader bloat.** It prevents the leader from vacuuming rows a standby query needs (fewer query cancellations) but lets dead tuples accumulate on the leader. Use deliberately.

### 6.2 Correctness & concurrency

- **Decide per operation whether stale is acceptable** (see §8). Don't apply one global policy.
- **Read-your-writes ≠ global consistency.** Pinning *the writer* to the leader does nothing for *other users* who must see the write — if that matters (e.g., inventory), you need stronger routing for those readers too.
- **Beware cross-entity invariants on replicas.** If order and order-lines replicate at different rates (different tables/partitions/shards), a replica read can momentarily violate "lines sum to order total." Read both from the same consistent source (leader, or a snapshot/consistent-prefix read) when an invariant spans entities.
- **Failover resets your assumptions.** After a leader fails over, a former follower (possibly behind) becomes leader; un-replicated writes may be lost (async). Token-based clients must handle "the token's LSN/GTID no longer exists" gracefully.

### 6.3 Memory & resource

- The background lag-probe poller and the shared RYW gate (Redis) add small, bounded overhead. Size the Redis keyspace: with PX expiry, keys self-clean; estimate peak = (writes/sec) × (window seconds).

### 6.4 Security

- Replication connections carry your entire dataset over the network — **encrypt them** (TLS on the replication channel; PostgreSQL `sslmode`, MySQL `REQUIRE SSL` on the replication user). A read replica is a full copy of production data: apply the same access controls, encryption-at-rest, and audit as the leader.
- Don't expose `pg_stat_replication`/`SHOW REPLICA STATUS` (which reveal topology and host addresses) to untrusted roles.

### 6.5 Observability (non-negotiable for this topic)

Track, per replica:
- **Replication lag in seconds AND in bytes/LSN** (bytes is leading; seconds is human-readable). Alert on both an absolute threshold (e.g., > 10s) and on *growth rate* (lag increasing for N minutes → it will not self-recover).
- **Replication state** (streaming/catchup/broken). `NULL`/`stopped` is worse than "high lag."
- **Apply throughput vs. leader write throughput** — if apply < write sustainably, lag grows without bound.
- **RYW fallback rate** (how often your router fell back to the leader) — a spike means lag rose; a chronic high rate means your window/threshold is mistuned or replicas are undersized.
- **Per-replica QPS** (catch sticky-routing hotspots).

Emit a synthetic canary: write a heartbeat row on the leader every second with a timestamp; read it on each replica; lag = `now − heartbeat_ts`. This is more reliable than engine-reported lag (which can read 0 when idle).

```sql
-- Heartbeat approach (engine-agnostic, robust). Writer (every 1s, on leader):
INSERT INTO repl_heartbeat(id, ts) VALUES (1, now())
  ON CONFLICT (id) DO UPDATE SET ts = excluded.ts;
-- Reader (on each replica): lag_seconds = now() - ts
SELECT EXTRACT(EPOCH FROM (now() - ts)) AS lag_seconds FROM repl_heartbeat WHERE id = 1;
```

### 6.6 Testing

- **Inject lag in staging.** PostgreSQL `recovery_min_apply_delay = '5s'` makes a standby *deliberately* lag — perfect for testing RYW logic. MySQL has `CHANGE REPLICATION SOURCE TO ... SOURCE_DELAY = 5`. Or use `tc`/`netem` to add network delay/loss on the replication link.
- **Test the failover path** (former-follower-as-leader, lost writes, token invalidation).
- **Test the "all replicas behind" path** — your router must fall back to the leader and stay correct, just slower.

### 6.7 Cost

- Each replica is a full-sized machine + storage + cross-AZ/region data transfer (cross-region replication traffic is billed). Adding replicas to fix lag has direct $ cost; sometimes the cheaper fix is reducing write volume, batching, or upgrading replica I/O.

### 6.8 Anti-patterns (memorize these)

1. **Assuming lag is "a few ms, ignore it."** It's unbounded; design for seconds-to-minutes.
2. **Using `Seconds_Behind_Source`/`ReplicaLag` as a *correctness* gate.** They're health gauges, not exactness primitives. Use LSN/GTID/cluster-time for correctness.
3. **Reading your own write from a random replica with no compensation.** Guaranteed RYW violations.
4. **Round-robin across replicas with different lag, no stickiness.** Guaranteed monotonic-read violations (data appears to flicker).
5. **A local (per-instance) RYW gate behind a load balancer.** The next request hits another instance and bypasses it.
6. **One global "read from leader for X seconds after any write."** Either too aggressive (kills scaling) or too lax (misses RYW). Scope to the specific user/key/operation.
7. **Treating Aurora/leaderless like single-leader streaming.** Their lag models differ (§7); copying knobs blindly misleads.
8. **Forgetting that schema migrations / bulk DML are giant single transactions** that spike lag — run them in batches.

---

## 7. Advanced topics & deep internals

### 7.1 PostgreSQL: the three lags and conflict handling

PostgreSQL distinguishes **`write_lag`** (time to durably write WAL on standby), **`flush_lag`** (time to flush), and **`replay_lag`** (time to make visible). **Only `replay_lag` governs read anomalies.** You can have synchronous *flush* (durable, no data loss on failover) while *replay* still lags — a standby can be a trustworthy failover target yet a stale read source simultaneously.

**Standby query conflicts:** WAL replay sometimes needs to remove rows (vacuum) or take locks that conflict with a long-running query on the standby. PostgreSQL resolves this by either *pausing replay* (increasing lag) or *cancelling the query* (`ERROR: canceling statement due to conflict with recovery`). `max_standby_streaming_delay` (default 30s) is how long replay will wait before cancelling. `hot_standby_feedback=on` pushes the standby's snapshot horizon to the leader so it won't vacuum those rows — fewer cancellations, but leader bloat. This is the deep reason "lag spikes during reporting queries."

### 7.2 MySQL: parallel replication internals

Classic MySQL applied the relay log with a single SQL thread → chronic lag under parallel write load. Modern MySQL uses **`LOGICAL_CLOCK`** parallelization: transactions that committed in the *same binary-log group commit* on the source are known to be non-conflicting and can be applied in parallel on the replica. `replica_preserve_commit_order=ON` keeps the externally visible commit order intact (important for consistent-prefix reads). Tuning `binlog_group_commit_sync_delay` on the *source* can *increase* parallelism opportunities on replicas by grouping more commits — a counterintuitive "add a little latency on the leader to reduce lag on the replica" knob.

### 7.3 Aurora's shared-storage model (why its lag is sub-10ms)

Amazon Aurora does **not** ship WAL to per-replica local storage. The leader writes log records to a distributed, multi-AZ **shared storage volume**; replicas read pages from that same volume and only need to apply in-memory cache updates from a redo stream. There's no full per-replica replay of all data changes, so `AuroraReplicaLag` is typically **single-digit to low-double-digit milliseconds** (vs. classic streaming replication's tens-to-hundreds of ms or more under load). The tradeoff: it's vendor-specific, and Aurora replicas still aren't *linearizable* with the writer — there's still a small window. Aurora also offers **read-after-write consistency within a session via the writer endpoint / global database session consistency features** (version-specific; verify current docs).

### 7.4 Leaderless (Dynamo-style) — "lag" as replica disagreement

In Cassandra/DynamoDB/Riak there's no single leader; clients write to N replicas and read from some. Instead of "lag" you reason with **quorums**: write to W replicas, read from R; if **R + W > N**, the read set and write set overlap, so a read sees the latest write (with caveats around concurrent writes and clock skew). Stale reads happen when R + W ≤ N or during the window before **anti-entropy/read-repair/hinted handoff** reconciles divergent replicas.

> **Beginner asides:**
> - **Read repair:** during a read, if replicas disagree, the coordinator writes the newest value back to the stale ones.
> - **Hinted handoff:** if a replica is down during a write, another node stores a "hint" and replays it when the replica returns — bounded backlog, like a mini replication queue.
> - **Anti-entropy (Merkle-tree repair):** a background process compares replicas and fixes divergence.
> Cassandra also offers **`LOCAL_QUORUM`/`EACH_QUORUM`** consistency levels and lightweight transactions (Paxos) for linearizable ops.

### 7.5 Causal consistency tokens in depth

The general pattern (MongoDB cluster time, Spanner's TrueTime, FaunaDB, CockroachDB's HLCs) is: every operation is stamped with a logical/physical timestamp; a client carries the max timestamp it has *observed* (`afterClusterTime`/`read_timestamp`); a replica serving a read *waits* until its applied timestamp ≥ that token. This gives causal+monotonic guarantees without pinning to a node and without reading the leader — the elegant general solution token-based RYW (§5.2/§5.3) approximates.

> **Beginner aside — HLC / TrueTime:** A **Hybrid Logical Clock** combines a physical wall-clock with a logical counter so events get globally meaningful, ordered timestamps even with imperfect clocks. Google **Spanner's TrueTime** goes further: it exposes clock *uncertainty bounds* (using GPS+atomic clocks) and *waits out* the uncertainty to guarantee external (linearizable) consistency across the globe. These make "wait until your replica is past timestamp T" rigorous.

### 7.6 Time-delayed replicas (intentional lag as a feature)

A replica configured with `recovery_min_apply_delay`/`SOURCE_DELAY` *deliberately* stays N minutes behind. Purpose: if someone runs a catastrophic `DELETE FROM users` on the leader, you have an N-minute window to stop the delayed replica before it applies the disaster and use it for point-in-time recovery. This is lag harnessed on purpose.

### 7.7 The "consistent prefix" subtlety across shards/partitions

Read-your-writes and monotonic reads are *single-key/session* properties. When data is **partitioned/sharded**, two related writes (question on shard A, answer on shard B) can replicate at different speeds, so a multi-shard read can show the answer before the question — a **causality/consistent-prefix violation** that single-key tokens don't fix. Solutions: keep causally related data in the same partition, use a causal-consistency layer, or read such cross-shard invariants from the leader.

---

## 8. Tradeoffs & decision frameworks

### 8.1 When stale reads are FINE vs. when they CORRUPT logic

| Read is… | Stale read acceptable? | Why |
|---|---|---|
| Analytics dashboards, trend charts, counts | **Yes** | A few seconds old is invisible to the consumer |
| Public timelines/feeds of *other* people's content | **Usually yes** | Eventual visibility is expected |
| Search indexes, recommendations | **Yes** | Already approximate/async by nature |
| Cached, regenerable content (article body) | **Yes** | Low stakes; refresh fixes it |
| **Your own** just-submitted content/profile | **No** (RYW needed) | "I posted but can't see it" — user-trust bug |
| Reads that *gate a decision*: "do I have enough balance?", "is this seat free?", "did the coupon already get used?" | **No** (often need leader/strong) | Stale read → double-spend, oversell, fraud |
| Authentication/permission checks after a change (revoke a token, change a password) | **No** | Stale read → security hole (revoked token still works) |
| Idempotency/dedup checks ("did we already process order #X?") | **No** | Stale read → duplicate processing |
| Anything feeding a write that asserts an invariant | **No** | Read-modify-write on stale data corrupts state |

**Rule of thumb:** *If the read result is only ever shown to a human as informational, stale is usually fine. If the read result is used to make a decision that changes data or grants access, treat staleness as a correctness bug and route to the leader (or use a strong/token-checked read).*

### 8.2 Choosing a mitigation

| You need… | Use | Avoid when |
|---|---|---|
| Fix "I posted but can't see it," cheaply | Leader-read window for the writer (§5.1) | Window is hard to tune; prefer tokens if you have LSN/GTID |
| Precise RYW with self-tuning | LSN/GTID token + freshness check (§5.2/§5.3) | Driver/DB lacks token support |
| Monotonic reads per user | Sticky routing (§5.4) | Hot users; frequent topology change (use consistent hashing) |
| Causal consistency app-wide | Causal session tokens (§5.5, §7.5) | DB lacks support |
| Cap on staleness | Bounded-staleness router (§5.6) | You actually need exact RYW (bounded ≠ RYW) |
| Strong everywhere | Read from leader / linearizable read concern | Read scaling matters (this won't scale) |
| Avoid the read entirely | Optimistic local echo (§5.7) | The data must reflect *others'* concurrent writes |

### 8.3 Sync vs. async vs. semi-sync decision

| Goal | Choice |
|---|---|
| Max write throughput, can tolerate some lag & rare lost writes on failover | **Async** (default) |
| No data loss on failover, accept slower writes | **Semi-sync** or **sync to ≥1 standby** |
| Zero-lag reads from a specific node | **`synchronous_commit = remote_apply`** to that standby (still slows writes; only that standby is fresh) |

### 8.4 Replication vs. alternatives for the underlying scaling need

- If you need **read scaling** → replicas (this chapter) or caching.
- If you need **write scaling** → replicas don't help (all writes still hit one leader); you need **partitioning/sharding** (a different chapter) or multi-leader/leaderless.
- If you need **freshness + scale** → caching with explicit invalidation, materialized views, or a strongly-consistent distributed DB (Spanner/CockroachDB) instead of leader+async-replicas.

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → diagnosis table

| Production symptom | Likely cause | First diagnostic |
|---|---|---|
| Users report "saved but it's gone, comes back on refresh" | RYW violation (read hit lagging replica) | Check replica lag at the report time; check whether the read path bypassed the RYW gate (e.g., per-instance gate behind LB) |
| Counts/lists flicker (go up then down) | Monotonic-read violation (round-robin over uneven replicas) | Check whether reads are sticky; compare lag across replicas |
| Lag steadily climbing, never recovers | Apply throughput < write throughput (overload, single-thread apply) | Compare leader write rate vs. replica apply rate; check `replica_parallel_workers` (MySQL) |
| Lag spikes during business-hours reporting | Standby query conflicts pausing replay (PG) | `pg_stat_database_conflicts`; check `max_standby_streaming_delay`, `hot_standby_feedback` |
| Sudden cliff: lag jumps to minutes | One huge transaction (migration, bulk delete) | Find the long transaction in the binlog/WAL; check for DDL/bulk DML |
| `Seconds_Behind_Source` = NULL | Replication broken/stopped | `SHOW REPLICA STATUS` `Last_Error`; `Replica_IO_Running`/`Replica_SQL_Running` |
| After failover, recent writes missing | Async failover lost un-replicated writes | Check replication mode; check promoted node's GTID vs. old leader |
| Replica CPU/IO pegged, lag rising | Disk/IO saturation, or no parallel apply | OS metrics + apply thread count |

### 9.2 The actual commands

**PostgreSQL:**
```sql
-- On leader: per-follower lag (bytes + time)
SELECT application_name, state,
       pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS lag_bytes,
       replay_lag
FROM pg_stat_replication;

-- On standby: is it even receiving? is it caught up?
SELECT pg_is_in_recovery(),                 -- true on a standby
       pg_last_wal_receive_lsn(),
       pg_last_wal_replay_lsn(),
       now() - pg_last_xact_replay_timestamp() AS time_lag;

-- Conflicts causing replay pauses / query cancels:
SELECT * FROM pg_stat_database_conflicts;
```

**MySQL:**
```sql
SHOW REPLICA STATUS\G   -- look at: Seconds_Behind_Source, Replica_IO_Running,
                        -- Replica_SQL_Running, Last_Error, Retrieved_Gtid_Set, Executed_Gtid_Set
SELECT @@gtid_executed;                       -- what this node has applied
-- Did this node apply my write's GTID? (0 = yes/within timeout)
SELECT WAIT_FOR_EXECUTED_GTID_SET('<gtid>', 1);
```

**MongoDB:**
```javascript
rs.printSecondaryReplicationInfo();   // per-secondary lag vs primary
rs.status();                          // members[].optimeDate → compute lag
```

**Managed:** watch CloudWatch `ReplicaLag`/`AuroraReplicaLag`, GCP/Azure equivalents; alert on absolute value AND positive slope.

### 9.3 Real-world incident patterns (composite, representative)

- **The bulk-delete cliff:** An engineer runs `DELETE FROM events WHERE created < '...'` removing 80M rows in one transaction. On the replica it applies as one giant unit; for 12 minutes the replica is up to 12 minutes behind, every replica read is wildly stale, and RYW windows tuned for "3 seconds" all fail. *Fix:* batch large DML (`DELETE ... LIMIT 10000` loops); alert on lag slope, not just threshold.
- **The reporting deadlock:** A nightly BI job runs a 40-minute query on a PG hot standby. WAL replay needs to vacuum rows the report is reading; `max_standby_streaming_delay` (30s) is exceeded; either the report gets cancelled (BI fails) or `hot_standby_feedback` was on and the leader bloated. *Fix:* dedicate a separate replica for reporting with `hot_standby_feedback=on` and accept bloat there only, or run reports off a snapshot.
- **The load-balancer flicker:** Reads were round-robined across three replicas with 0.1s/2s/8s lag. A user's feed count jumped between values on every refresh. *Fix:* sticky routing by user; add monotonic-read tokens.
- **The lost-write failover:** Async replication; leader hardware failed; a follower 4 seconds behind was promoted; 4 seconds of orders vanished. *Fix:* semi-sync or sync-to-one-standby for the durability-critical path; reconcile from upstream event log.
- **The per-instance gate bug:** RYW gate stored in a local `ConcurrentHashMap`; behind a round-robin LB, the user's read landed on a different app instance with an empty map, bypassed the gate, hit a stale replica. *Fix:* shared gate (Redis), or token carried by the client.

---

## 10. Interview drill

**Q1. What is replication lag and what causes it?**
*Model answer:* It's the time between a write committing on the leader and becoming visible on a follower, in single-leader async replication. Causes: write bursts overwhelming apply throughput, single-threaded apply, long queries on the standby blocking replay, network latency/bandwidth (esp. cross-region), disk I/O saturation, and giant single transactions. It's per-follower and unbounded in the worst case.
- *Probe: Why is it unbounded?* Because async commits ack the client before followers receive the data; a follower that's offline or overloaded just keeps falling behind with no ceiling, and must replay the whole backlog to catch up.
- *Probe: Sync vs. flush vs. replay lag?* Sync/flush concern durability (data won't be lost on failover); replay concerns visibility (when reads see it). Read anomalies come from *replay* lag — a node can be synchronous for durability yet still lag for reads.

**Q2. A user updates their profile and on reload sees the old value. Explain and fix.**
*Model answer:* Classic read-your-writes violation — the read hit a follower that hadn't replayed the update yet. Fix by guaranteeing read-your-writes for the writer: route the writer's reads to the leader for a short window after the write, or capture the write's LSN/GTID and only read from a replica that has replayed past it (else fall back to leader). Crucially, the gate must be shared across app instances.
- *Probe: Window-based vs token-based?* Window is simple but you must guess > p99 lag and it wastes leader capacity; token-based self-tunes and releases the moment a replica catches up.
- *Probe: Does this help other users see the write?* No — RYW is session-scoped. Other users still see eventual consistency unless you route their reads more strongly too.

**Q3. Count flickers up and down across refreshes. What's wrong?**
*Model answer:* Monotonic-read violation: reads are load-balanced across replicas with different lag, so successive reads see different points in time. Fix with sticky routing (hash user→one replica) so a session's reads only move forward, or carry a monotonic token (last-seen LSN/timestamp) and require replicas to be at least that fresh.
- *Probe: Downside of sticky routing?* Hotspots if one user/replica is hot; broken guarantee on topology change (mitigate with consistent hashing); a stuck-behind replica gives consistently stale data.

**Q4. When is a stale read acceptable, and when is it a correctness bug? (senior-signal)**
*Model answer:* Stale is fine for informational reads shown to humans (dashboards, other users' feeds, search). It's a correctness bug when the read *gates a decision that changes data or grants access*: balance checks (double-spend), seat/inventory availability (oversell), permission/revocation checks (security hole), idempotency checks (duplicate processing), or any read-modify-write asserting an invariant. The deciding question: is the result merely displayed, or does it drive a mutation/authorization?

**Q5. How do you measure replication lag reliably, and why not just trust `Seconds_Behind_Source`?**
*Model answer:* Measure both byte lag (LSN/GTID gap — leading indicator) and time lag. Don't rely on `Seconds_Behind_Source`/`ReplicaLag` for correctness: they can read 0 when idle-but-not-caught-up, NULL when broken, and spike misleadingly during big transactions. For correctness use exact primitives (`WAIT_FOR_EXECUTED_GTID_SET`, `pg_last_wal_replay_lsn` vs token). For monitoring, a heartbeat row (write every second, read everywhere, lag = now − ts) is the most robust.
- *Probe: Alert on value or slope?* Both — absolute threshold catches acute staleness; positive slope over minutes predicts a backlog that won't self-recover.

**Q6. Explain synchronous vs. asynchronous vs. semi-synchronous and the tradeoffs. (senior-signal)**
*Model answer:* Async acks the client before followers have the data — fastest writes, but lag is unbounded and failover can lose writes. Sync waits for follower confirmation — no lost data to that follower, zero lag there, but writes block if it's slow/down (availability hit), and usually only one/few followers are sync. Semi-sync waits for ≥1 follower to *receive* (not apply), with timeout fallback to async — durability without full sync cost. Choose by whether you can tolerate lost writes on failover vs. write-latency/availability cost.

**Q7. Walk through what happens internally from COMMIT on the leader to a read seeing it on a follower (PostgreSQL).**
*Model answer:* COMMIT → leader writes+flushes WAL up to the commit LSN → (async) acks client → WAL sender streams to follower's WAL receiver over TCP → follower writes+flushes WAL (reports write/flush LSN) → startup process *replays* WAL into data pages → data becomes visible to reads on the follower (reports replay LSN). Read anomalies are governed by the replay step; lag = wall-clock from commit to replay.

**Q8. Design read-your-writes for a globally distributed app with regional read replicas. (senior-signal)**
*Model answer:* Cross-region async lag is tens-to-hundreds of ms steady-state and spiky. Options: (a) token-based — on write capture a timestamp/LSN, propagate it with the user's session (cookie/header), and have the regional replica wait until its applied timestamp ≥ token (causal-consistency style, e.g., HLC), falling back to the writer region if not caught up; (b) pin the writer's reads to the write region briefly; (c) optimistic local echo to avoid the read. Discuss the tradeoff: stronger guarantees cost cross-region round-trips; bound it with a timeout and degrade gracefully. Mention that consistent-prefix violations across shards need causally-related data co-located.

**Q9. Lag is steadily climbing on one replica and never recovers. Diagnose.**
*Model answer:* Apply throughput < write throughput. Check leader write rate vs. replica apply rate; on MySQL check `replica_parallel_workers`/`replica_parallel_type` (single-thread apply is the classic culprit); check replica disk I/O/CPU saturation; check for a giant transaction or DDL stuck applying; on PG check standby query conflicts pausing replay. Mitigations: enable/parallelize apply, scale up replica I/O, batch big DML, offload reporting, or reduce write volume.
- *Probe: Why might more replicas not help?* They don't reduce per-replica apply cost; if each replica can't keep up with the write stream, adding more just gives you more lagging replicas. You need faster apply or fewer/smaller writes.

**Q10. What's the difference between eventual consistency and read-your-writes consistency? (senior-signal)**
*Model answer:* Eventual consistency only promises replicas converge *eventually* with no bound and no session guarantees — your own write may be invisible to you for a while. Read-your-writes is a session-scoped guarantee that *you* always see at least your own writes. Most "lag handling" is buying back read-your-writes (and monotonic reads) on top of an eventually-consistent replica fleet — it's a much stronger, user-facing-relevant guarantee than plain eventual consistency, achievable without making the whole system linearizable.

**Q11. How does Aurora achieve millisecond replica lag, and why can't classic streaming replication match it easily?**
*Model answer:* Aurora replicas read from the same distributed shared-storage volume the writer logs to, applying only in-memory cache updates from a redo stream rather than replaying all data changes into their own local storage. So `AuroraReplicaLag` is single-digit ms. Classic streaming replication ships and *replays* the full change stream into each replica's independent storage, so apply throughput and I/O bound the lag. Aurora's approach is vendor-specific and still not linearizable with the writer.

**Q12. Your RYW fix works in dev but fails intermittently in production behind a load balancer. Why?**
*Model answer:* Almost certainly the freshness gate is stored per-instance (local map), so a request load-balanced to a different app instance bypasses it. Fix: store the gate in a shared store (Redis) keyed by user, or carry the freshness token (LSN/GTID/timestamp) in the client request itself so any instance can enforce it. Also verify the window exceeds p99 lag and that you're not re-reading before the replica catches up.

---

## 11. Glossary

- **Anti-entropy:** Background process that compares replicas (often via Merkle trees) and repairs divergence; used in leaderless systems.
- **Asynchronous replication:** Leader acks the client before followers receive the data; fast writes, unbounded lag, possible lost writes on failover.
- **Binlog (binary log):** MySQL's ordered log of data changes, shipped to replicas; also used for point-in-time recovery and CDC.
- **Bounded staleness:** Consistency model where reads may be stale but never by more than a configured bound (time or versions).
- **Causal consistency:** Guarantee that causally related operations are observed in order by everyone; concurrent ops may be ordered differently.
- **CDC (Change Data Capture):** Streaming row-level changes out of a DB (often by reading the WAL/binlog) to other systems.
- **Consistent prefix reads:** Guarantee that writes are observed in the order they occurred (no answer before its question).
- **Eventual consistency:** Replicas converge eventually if writes stop; no time bound, no session guarantee.
- **Failover:** Promoting a follower to leader when the leader fails.
- **Follower / replica / secondary / standby:** A node that receives and applies the replication stream; can serve reads.
- **`fsync`:** A syscall forcing buffered file data to durable storage; used to make WAL/log durable.
- **GTID (Global Transaction ID):** MySQL's cluster-unique, ordered transaction identifier; basis for token-based RYW and failover.
- **Heartbeat (replication heartbeat):** A periodically updated row used to measure lag robustly as `now − last_seen_timestamp`.
- **HLC (Hybrid Logical Clock):** A clock combining physical time and a logical counter to give globally meaningful ordered timestamps.
- **Hinted handoff:** In leaderless systems, storing a write meant for a down replica and replaying it when the replica returns.
- **Hot standby:** A PostgreSQL standby that can serve read queries while replaying WAL.
- **Leader / primary / master / source:** The node that accepts writes and produces the replication stream.
- **Linearizability (strong consistency):** Behaves as a single copy with a single instantaneous effect per op; once a write completes, all later reads see it.
- **LSN (Log Sequence Number):** PostgreSQL's monotonic byte position in the WAL; the universal replication progress cursor.
- **Logical replication:** Replicating decoded row changes (portable across versions) rather than physical bytes.
- **Monotonic reads:** Session guarantee that reads never go backwards in time.
- **MTR (Multi-Threaded Replication):** MySQL applying the relay log with multiple parallel applier threads.
- **MVCC (Multi-Version Concurrency Control):** Keeping multiple row versions so readers don't block writers; why standby vacuum can conflict with long queries.
- **Quorum (R, W, N):** In leaderless systems, reading from R and writing to W of N replicas; R + W > N gives read/write overlap.
- **Raft / Paxos:** Consensus algorithms for agreeing on an ordered log across nodes; underpin strongly-consistent replication.
- **Read concern (MongoDB):** What consistency a read demands (`local`, `majority`, `linearizable`, `snapshot`).
- **Read preference (MongoDB):** Where a read goes (`primary`, `secondary`, `nearest`, etc.).
- **Read repair:** During a read, writing the newest value back to replicas found stale.
- **Read-your-writes (read-after-write) consistency:** Session guarantee that you always see at least your own writes.
- **Relay log:** MySQL replica's local copy of the source's binlog, written by the I/O thread and applied by the SQL thread.
- **Replication lag:** Time between a write committing on the leader and becoming visible on a follower.
- **Semi-synchronous replication:** Leader waits for ≥1 follower to receive (not apply) before acking; falls back to async on timeout.
- **`Seconds_Behind_Source`:** MySQL's coarse, imperfect lag estimate; a health gauge, not a correctness primitive.
- **Sharding / partitioning:** Splitting data across nodes to scale writes/storage (distinct from replication, which copies data).
- **Sticky routing:** Pinning a session/user to a fixed replica to preserve monotonic reads.
- **Synchronous replication:** Leader waits for follower confirmation before acking; no lag/loss to that follower, slower/less available.
- **TrueTime:** Google Spanner's clock API exposing uncertainty bounds (GPS+atomic clocks) to enable global external consistency.
- **WAL (Write-Ahead Log):** Append-only log of changes written before data files; provides durability and an ordered replication stream.
- **WAL sender / WAL receiver:** PostgreSQL processes that stream WAL from leader to follower.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one-screen recap)

**Definition:** Lag = time from commit on leader → visible (replayed) on follower. Per-follower, fluctuating, **unbounded** under async.

**Three anomalies:**
- **Read-your-writes violation** — can't see your own write ("posted but gone").
- **Monotonic-read violation** — data goes backwards (count flickers) from hitting uneven replicas.
- **Stale / consistent-prefix violation** — old data, or answer-before-question across shards.

**Root cause (all three):** read served from a node that hasn't applied a write the read depends on.

**Lag causes:** write burst > apply throughput · single-thread apply (classic MySQL) · long standby queries pausing replay (PG) · network (cross-region) · disk I/O saturation · one huge transaction · contention.

**Mitigations:** leader-read window (writer) · token-based check (LSN/GTID/cluster-time) + wait-or-fallback · sticky routing (monotonic) · bounded staleness · causal sessions · optimistic local echo · always-leader for critical paths.

**Stale OK?** Informational/displayed → yes. Gates a mutation or authorization (balance, inventory, permissions, idempotency) → **no, treat as correctness bug.**

**Measure:** byte/LSN lag (leading) + time lag; heartbeat row is most robust; alert on **value AND slope**. Don't trust `Seconds_Behind_Source` for correctness.

**Key primitives:** PG `pg_current_wal_lsn()` / `pg_last_wal_replay_lsn()` / `pg_wal_lsn_diff` / `pg_stat_replication.replay_lag`; MySQL `@@gtid_executed` / `WAIT_FOR_EXECUTED_GTID_SET`; MongoDB causal session + `readConcern: majority`.

**Sync modes:** async (fast, lossy on failover) · semi-sync (durable, timeout-fallback) · sync (no loss, slow/less available, usually only 1 node).

**Anti-patterns:** assuming lag is tiny · `Seconds_Behind` as correctness gate · random replica for your own write · round-robin uneven replicas · per-instance RYW gate behind LB · one global leader-window · bulk DML as one transaction.

**Numbers to anchor:** PG `max_standby_streaming_delay` 30s · MySQL semi-sync timeout 10s · MySQL 8 `replica_parallel_workers` ~4 · Aurora replica lag ~single-digit ms · MongoDB `maxStalenessSeconds` min 90s · network ~5 ms/1000 km one-way.

### Self-test (no answers — active recall)

1. Trace, step by step, what happens from `COMMIT` on a PostgreSQL leader until a read on a follower can see that write — and identify exactly which step's delay causes read anomalies.
2. You're asked to fix "I posted a comment but can't see it" with the least leader load while keeping read scaling. Design the mechanism, name the failure mode that breaks it behind a load balancer, and give the precise consistency model you're providing.
3. Explain why adding more read replicas can *fail* to fix a steadily-climbing lag, and what you'd change instead.
4. Give three reads where a stale value is a correctness bug (not just cosmetic) and explain the concrete damage in each.
5. Compare window-based vs. token-based read-your-writes and bounded-staleness routing: what guarantee each provides, what it costs, and when you'd pick each.
6. Why is `Seconds_Behind_Source` unsafe as a correctness gate, and what would you use instead for "did this replica apply my specific write?"
7. Explain how a monotonic-read violation differs from a read-your-writes violation, including the routing decision that causes each and the fix for each.

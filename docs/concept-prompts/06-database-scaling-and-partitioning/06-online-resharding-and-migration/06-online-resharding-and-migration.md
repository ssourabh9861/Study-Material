# Online Resharding & Migration

> **Concept area:** Database Scaling & Partitioning
> **Subtopic:** Online Resharding & Migration
> **Reader profile:** A senior Java/JVM backend engineer who wants to fully master this subtopic — from first principles to deep internals — to design, operate, debug, teach, and interview on it.

---

## 1. Overview & where it fits

### 1.1 What it is

**Online resharding and migration** is the discipline of *moving live data from one physical layout to another while the system keeps serving production traffic*, with **zero (or near-zero) downtime**, **no data loss**, and **no correctness regressions**. "Online" is the load-bearing word: the database is being read from and written to *while you migrate it*. Nobody gets a maintenance window of eight hours to stop the world, dump, reload, and restart.

The two words pull in slightly different directions, so let's pin them down:

- **Resharding** means changing how a dataset is *partitioned* across nodes. A **shard** (or *partition*) is a horizontal slice of a table — a disjoint subset of the rows — that lives on its own node or storage unit. **Sharding** is splitting one logical table into many such slices so the data and load fit across many machines instead of one. *Resharding* is changing that split after the fact: going from 4 shards to 16, rebalancing hot shards, or — the hardest case — changing the **shard key** (the column[s] whose value decides which shard a row lands on).
- **Migration** is the broader act of moving data from one *store* to another: MySQL → a new MySQL cluster, MySQL → Cassandra, a self-hosted Postgres → Amazon Aurora, a monolithic table → a sharded fleet, one cloud region → another. Resharding is a special case of migration where source and target are the same engine but a different topology.

Both share the same fundamental engineering problem and almost the same playbook, which is why they're taught together.

### 1.2 The problem it solves

Databases outgrow their original design. Concretely, you reach for online resharding/migration when:

- **A single node is saturated** — CPU, IOPS (I/O operations per second), RAM for the working set, disk capacity, or connection count — and you must spread load across more nodes.
- **A shard is hot** — your partitioning scheme sends disproportionate traffic to one shard (e.g., a single "whale" tenant), and you need to split or rebalance.
- **The shard key was wrong** — you sharded by `user_id` but your access pattern is by `order_id`, causing scatter-gather queries. Changing the shard key requires physically relocating essentially every row.
- **You're changing engines** — moving off an end-of-life system, adopting a managed service, or switching to a store whose data model fits better (e.g., wide-column for time-series).
- **You're consolidating or splitting** — merging two databases after a company acquisition, or carving a monolith's tables into per-service databases for a microservices migration.

### 1.3 When you reach for it (and when you don't)

Reach for the full online machinery when **downtime is unacceptable** and the dataset is **large enough that a stop-the-world dump/restore would take too long** (more than your tolerable window). For a 10 GB database with a permissible 20-minute maintenance window at 3 a.m., the right answer is often the boring one: stop writes, `mysqldump`/`pg_dump`, restore, switch connection string, done. Don't build a CDC pipeline (defined below) for that.

You reach for the elaborate **dual-write / backfill / shadow-read / cutover** playbook when the data is large (hundreds of GB to many TB), the write rate is high, and the business cannot tolerate a window.

### 1.4 The one-paragraph mental model

> Think of online migration as **changing the wheels on a moving car**. You can't stop the car (downtime), so you must bolt on the new wheel while the old one still turns, get them spinning in perfect sync (dual-write keeps new data consistent), copy the tread pattern of every old wheel onto the new one without missing a groove (backfill copies historical data), quietly test that the new wheel can bear load before you trust it (shadow-read), then transfer weight from old to new in one careful, reversible motion (cutover), and only after you're sure the car still drives straight do you unbolt the old wheel (decommission). At every step you keep a way to shift weight back (rollback). The entire craft is about **two systems holding the same truth simultaneously**, long enough to swap which one is authoritative without anyone noticing.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Skip ahead if these are second nature, but every later section assumes these definitions.

### 2.1 Partitioning, sharding, and the shard key

- **Partitioning** = dividing a dataset into pieces. *Vertical partitioning* splits by columns (put rarely-used columns in another table); *horizontal partitioning* splits by rows. Sharding is horizontal partitioning across separate database servers.
- **Shard** = one horizontal slice living on its own node. If `users` has 100M rows across 10 shards, each shard holds ~10M rows.
- **Shard key (partition key)** = the column(s) whose value decides a row's shard. Choosing it well is the single most consequential schema decision in a distributed database, because it determines data distribution, query routing, and how painful resharding will be.
- **Routing** = the logic that, given a shard key value, returns *which shard* to talk to. Lives in application code, a proxy (e.g., Vitess, ProxySQL), or the database itself (e.g., Cassandra, MongoDB).

### 2.2 Two ways to map keys to shards: range vs hash

**Range partitioning** assigns contiguous key ranges to shards: shard A = users 1–1M, shard B = 1M–2M, etc. Great for range scans (`WHERE id BETWEEN ...`), terrible for hotspots — if IDs are monotonically increasing, all new writes hit the last shard ("hot tail"). Splitting a range shard is conceptually easy: pick a midpoint and split.

**Hash partitioning** runs the key through a hash function and uses the result (often `hash(key) mod N`) to pick a shard. Distributes load evenly and kills hotspots, but destroys range-scan locality and — critically — `mod N` makes resharding catastrophic: change `N` and *almost every key remaps to a different shard*, forcing a near-total data shuffle. This single fact is *why naive hash sharding is so hard to reshard* and motivates the next concept.

### 2.3 Consistent hashing and virtual nodes

**Consistent hashing** maps both keys and nodes onto the same circular keyspace (a "ring", e.g., 0 to 2^64−1). A key is owned by the first node encountered moving clockwise from the key's hash. The payoff: **adding or removing one node only moves the keys between that node and its neighbor — roughly K/N keys, not all K.** This is the foundation of Dynamo-style systems (Cassandra, Riak, DynamoDB internals).

**Virtual nodes (vnodes)** refine this: each physical node owns *many* small ranges scattered around the ring rather than one big arc. This smooths distribution (avoids one node owning a huge arc by bad luck) and makes rebalancing finer-grained — when a node joins, it picks up many small slivers from many peers in parallel, speeding up the move. Cassandra defaults to **256 vnodes per node** in older versions; newer versions favor **16** (`num_tokens`) for better repair/streaming behavior. (Version-specific — verify for your Cassandra release.)

### 2.4 Logical vs physical shards (the pre-split trick)

A powerful design pattern: create **many logical shards up front** (e.g., 4096) and pack them onto few **physical nodes** (e.g., 4 nodes × 1024 logical shards each). Routing maps key → logical shard via a *stable* function (e.g., `hash(key) mod 4096` — note 4096 never changes), and a separate, mutable map says which physical node currently hosts each logical shard.

Resharding then becomes: **move whole logical shards between physical nodes** — a metadata change plus a file copy — *without* rehashing any keys, because the key→logical-shard function is fixed. This is how you "reshard without resharding the keys." Slack, Notion, Figma, and Vitess all lean on variants of this. The cost is choosing a large-enough logical shard count on day one (you can split logical shards later, but it's more work than just moving them).

### 2.5 Replication, the binlog, and CDC

- **Replication** = keeping copies of data on multiple nodes. The **primary** (leader/master) takes writes; **replicas** (followers/slaves) copy them. *Synchronous* replication waits for replicas before acking a write (safe, slow); *asynchronous* acks immediately and ships changes after (fast, can lose recent writes on failover).
- **Binlog / WAL** — the **binary log** (MySQL) or **write-ahead log** (WAL; Postgres) is the ordered, append-only record of every change the database made. The DB writes the change to this log *before* applying it to data files, which is how it survives crashes and how replicas catch up. **This log is the beating heart of online migration** — it's the source of truth for "what changed and in what order."
- **CDC (Change Data Capture)** = reading that log as a *stream* and shipping each change (insert/update/delete) to consumers. Instead of polling tables ("what's new since I last looked?"), you tail the binlog and get every change in commit order, in near real-time. Tools: **Debezium** (open-source, Kafka-based), **Maxwell**, AWS **DMS** (Database Migration Service), Vitess **VReplication**, Striim. CDC is how you keep a new store continuously in sync with an old one while you migrate.

### 2.6 Consistency models you'll meet

- **Strong consistency** — every read returns the most recent write. Easy to reason about, harder to scale.
- **Eventual consistency** — replicas converge "eventually"; a read may return stale data briefly. Common in Dynamo-style stores and in any async-replicated topology.
- **Read-after-write (read-your-writes)** — a weaker-than-strong guarantee that *your own* writes are visible to *your* subsequent reads. Migrations frequently break this temporarily (you wrote to old store, read from new store before sync caught up), so you must design around it.
- **Idempotency** — an operation that produces the same result whether applied once or many times (e.g., `SET balance = 100`, but not `balance = balance + 10`). Migration pipelines re-deliver events on retry, so writes to the target must be idempotent or you'll double-apply.

### 2.7 The CAP and PACELC framing (just enough)

**CAP theorem**: in the presence of a network **P**artition (nodes can't talk), a distributed system must choose between **C**onsistency (refuse to serve stale/conflicting data) and **A**vailability (keep serving). **PACELC** extends it: *else* (no partition), you still trade **L**atency vs **C**onsistency. Why it matters here: during migration you run a *temporarily distributed* system spanning old and new stores. Dual-writing across both means a partition between them forces a choice — fail the write (consistency) or accept divergence (availability) and reconcile later. Know which you're choosing on every write path.

### 2.8 Distributed transactions and 2PC (and why you usually avoid them)

A **distributed transaction** atomically updates two systems (old store *and* new store) so both commit or both abort. The classic protocol is **two-phase commit (2PC)**: a coordinator asks all participants "can you commit?" (prepare), and if all say yes, tells them "commit." 2PC is **blocking** — if the coordinator dies after prepare, participants hold locks indefinitely — and it cripples latency and availability. In practice, migrations **avoid 2PC** and instead accept temporary inconsistency, then **reconcile** (compare and repair) asynchronously. Remember this: *migrations trade strong cross-store atomicity for eventual reconciliation.*

### 2.9 ZooKeeper / etcd (the coordination layer)

Several migration tools need a place to store *shared, consistent metadata*: which shard is where, which phase the migration is in, which node holds a lock. **ZooKeeper** and **etcd** are distributed coordination services that provide a small, strongly-consistent, replicated key-value store with watches and leader election, built on consensus protocols (**ZAB** for ZooKeeper, **Raft** for etcd). **Raft** is a consensus algorithm where nodes elect a leader and replicate a log; a write is committed once a majority (quorum) acknowledges it, guaranteeing agreement despite failures. Vitess uses a "topology service" (etcd/ZooKeeper/Consul) to store its shard map; Kafka historically used ZooKeeper.

---

## 3. How it works internally — the canonical playbook, step by step

This section is the heart of the document. The industry-standard recipe for online migration has five phases. We'll trace control flow, data flow, and state transitions for each, then describe the variant pipelines (logical shard move, CDC-based engine swap, shard-key change).

### 3.1 The five-phase playbook at a glance

```
Phase 0  PREPARE      Provision target; define mapping; build routing layer; add feature flags.
Phase 1  DUAL-WRITE   Writes go to OLD (authoritative) AND NEW. New rows now stay in sync.
Phase 2  BACKFILL     Copy all historical rows from OLD to NEW (the data that predates dual-write).
Phase 3  SHADOW-READ  Read from NEW in parallel, compare to OLD, log mismatches. Don't serve NEW yet.
Phase 4  CUTOVER      Flip reads to NEW; make NEW authoritative; keep dual-write briefly for rollback.
Phase 5  DECOMMISSION Stop writing OLD; verify; tear down OLD.
```

The invariant that makes this safe: **at every moment there is exactly one authoritative store, and the other is kept consistent or known-stale.** You only flip authority once you've *proven* the new store matches.

### 3.2 Phase 0 — Prepare

**Goal:** stand up the target and build the machinery to route, flag, and observe — *before touching production data flow.*

Steps under the hood:

1. **Provision the target** with correct schema, indexes, capacity, and the new topology (e.g., 16 shards). Mirror the source schema initially even if you plan to evolve it; schema *and* topology changes at once multiply risk.
2. **Build the routing/abstraction layer.** Introduce a repository/DAO interface so the rest of the app calls `userRepo.findById(id)` and *that layer* decides old vs new. Without this seam, you'll sprinkle migration logic everywhere.
3. **Introduce feature flags / kill switches** for: dual-write on/off, backfill throttle, shadow-read on/off, read-source (OLD|NEW|BOTH), write-source. These must be **runtime-configurable without deploy** (e.g., LaunchDarkly, a config service, or a DB-backed flag table) so you can react in seconds.
4. **Establish observability**: metrics for write success per store, mismatch counts, replication lag, backfill progress, p50/p99 latency per path. You cannot operate a migration you can't see.

**State after Phase 0:** target exists and is empty; all flags default to "old only"; production behavior unchanged.

### 3.3 Phase 1 — Dual-write

**Goal:** every *new* write lands in both stores so they stop diverging going forward. Historical data is handled in Phase 2.

Control flow on a write (e.g., `updateUser`):

```
1. App calls repository.save(user)
2. Repo writes to OLD store  → this is the AUTHORITATIVE write; its success/failure determines the user-visible result
3. Repo writes to NEW store  → best-effort (or strict, see below)
4. Repo returns based on OLD's result
```

The crucial design decisions:

- **Ordering & authority.** Write OLD first and treat it as the source of truth during this phase. NEW is the understudy. If NEW write fails, you typically **log the failure and continue** (so a flaky new store doesn't break production) — the failure is acceptable *because backfill + reconciliation will repair it*. The alternative (fail the whole request if NEW fails) is "strict dual-write": safer for consistency, worse for availability. Choose per CAP analysis.
- **Atomicity gap.** Because you're *not* using 2PC, there's a window where OLD committed but NEW didn't (crash between steps 2 and 3, or NEW write fails). This is *expected*. Reconciliation closes the gap. Do **not** try to make dual-write atomic — that path leads to 2PC pain.
- **Idempotency.** Make the NEW-store write idempotent (upsert by primary key) so retries and the later backfill can overwrite without duplicating.
- **Capturing deletes.** Dual-write must handle deletes and updates, not just inserts — a forgotten delete leaves a zombie row in NEW.

A more robust alternative to in-process dual-write is **transactional outbox + CDC**: in the *same* DB transaction as the business write, insert a row into an `outbox` table; a CDC consumer reads the outbox and applies to NEW. This makes the "did we capture this change?" question atomic with the original write (no lost events if the app crashes between writes), at the cost of more infrastructure.

**State after Phase 1:** all writes from now on are in both stores; rows that existed *before* Phase 1 are still only in OLD.

### 3.4 Phase 2 — Backfill

**Goal:** copy every historical row from OLD to NEW, idempotently, without crushing production.

Internal workflow:

1. **Iterate the source in stable, indexed chunks.** Never `SELECT * FROM users` into memory. Use **keyset pagination** (a.k.a. seek method): `WHERE id > :lastSeenId ORDER BY id LIMIT 1000`. This is O(1) per page regardless of depth, unlike `OFFSET` which scans and discards skipped rows (O(n) drift) and can skip/duplicate rows when concurrent writes shift offsets.
2. **Upsert each chunk into NEW.** Idempotent upsert (`INSERT ... ON DUPLICATE KEY UPDATE` / `INSERT ... ON CONFLICT DO UPDATE`) so a row that dual-write already wrote, or a retried chunk, just overwrites harmlessly.
3. **Handle the race with dual-write.** A row can change *while you backfill it*: you read v1 from OLD, then dual-write updates it to v2 in both stores, then your backfill writes the stale v1 into NEW, clobbering v2. **Fixes:** (a) make backfill writes *conditional* — only write if NEW's version/timestamp is older than the source row's (compare-and-set on `updated_at`); or (b) run backfill, then a reconciliation pass catches stragglers; or (c) use CDC-based backfill where the tool guarantees ordering (Vitess VReplication, Debezium snapshot+stream).
4. **Throttle.** Backfill is a bulk scan that competes with production for IOPS and replica bandwidth. Throttle by rows/sec, watch replica lag, and *back off automatically* when lag or latency rises (adaptive throttling). A common pattern: read from a *replica*, not the primary, to spare the primary's read capacity (accepting that replica data is slightly stale, which reconciliation handles).

**State after Phase 2:** NEW should contain *all* data — historical (from backfill) plus ongoing (from dual-write). It is now a *candidate* authoritative copy, but unverified.

### 3.5 Phase 3 — Shadow-read (dark reads) & reconciliation

**Goal:** prove NEW returns the same answers as OLD *under real traffic* before trusting it.

Control flow on a read during shadow phase:

```
1. App calls repository.findById(id)
2. Repo reads from OLD  → returns this to the user (OLD still authoritative)
3. Repo ALSO reads from NEW (async, off the hot path)
4. Compare the two results:
     - equal      → increment match counter
     - different  → log {id, oldValue, newValue, diff}, increment mismatch counter
5. Return OLD's result
```

Key properties:

- **Shadow reads are non-authoritative.** The user always gets OLD's answer; NEW's answer is for measurement only. A bug in NEW cannot hurt users yet.
- **Tolerate benign diffs.** Expect transient mismatches from replication lag and the dual-write race. Distinguish *transient* (resolves on retry/recheck after lag window) from *persistent* (real bug). Re-read mismatches after a short delay before counting them as real.
- **Reconciliation** is the offline cousin: a batch job that scans both stores and compares. Two flavors: **full scan** (compare every row — expensive, thorough) and **checksum/Merkle** comparison (hash ranges of rows; only drill into ranges whose hashes differ — far cheaper, used by Cassandra repair and by tools like `gh-ost`'s and Vitess's verification). When a mismatch is confirmed, the reconciler **repairs** by re-copying the source row to NEW.
- **Exit criterion.** You proceed to cutover only when the *persistent* mismatch rate is effectively zero over a sustained window under representative load (including peak). Define this threshold up front (e.g., "0 persistent mismatches over 24h including peak traffic").

**State after Phase 3:** NEW is proven consistent with OLD under live traffic; you have data-backed confidence to flip authority.

### 3.6 Phase 4 — Cutover

**Goal:** make NEW authoritative for reads and writes, reversibly.

This is the riskiest moment; do it gradually:

1. **Ramp reads** from OLD→NEW behind the flag: 1% → 5% → 25% → 50% → 100%, watching error rate, latency, and mismatch metrics at each step. (Some teams cut reads over per-tenant or per-shard rather than by percentage, to contain blast radius.)
2. **Flip write authority.** Now NEW is the source of truth. *Keep dual-writing to OLD* for a safety window so rollback remains a flag flip, not a re-migration. Reverse the authority: NEW write must succeed; OLD write becomes best-effort.
3. **Watch the read-after-write hazard.** During the flip, a write may land in NEW while a near-simultaneous read still routes to OLD (or vice versa), violating read-your-writes. Mitigations: cut a *whole tenant/shard* atomically; or route a session consistently to one store for a sticky window; or briefly read from BOTH and prefer the authoritative store.
4. **Hold.** Stay in this dual-running state hours to days until confident.

**State after Phase 4:** NEW serves all reads and is authoritative for writes; OLD is a warm standby still receiving writes.

### 3.7 Phase 5 — Decommission

1. **Stop writing OLD** (flip dual-write off). Now rollback is no longer a flag flip — past this point, reverting requires re-syncing OLD from NEW, so don't cross it until truly confident.
2. **Final reconciliation** snapshot for the record.
3. **Snapshot/backup OLD**, then leave it read-only/idle for a grace period (days/weeks) as ultimate insurance.
4. **Tear down OLD**; remove migration code paths, flags, dual-write logic, and dead routing branches. *Cleaning up the scaffolding is part of the job* — leftover dual-write code is a common source of future bugs and confusion.

**State after Phase 5:** migration complete; single store; clean code.

### 3.8 The reverse-direction guarantee (rollback wiring)

Throughout phases 1–4, build the pipeline so it can run *in reverse*. If you dual-write OLD+NEW and later cut authority to NEW, you should *also* dual-write NEW→OLD (or CDC-replicate NEW→OLD) so OLD never falls behind. Then rollback = flip authority back to OLD, and OLD is already current. Without reverse sync, "rollback" after cutover means OLD is now stale by however long you ran on NEW — effectively a second migration. The discipline: **rollback must be a flag flip, not a project**, for as long as you maintain bidirectional sync.

### 3.9 Variant pipeline A — moving logical shards (expand-in-place)

When you've used the logical-shard design (§2.4), resharding is much gentler:

```
1. Pick logical shards to move (e.g., the busiest 256 of node A's 1024).
2. SNAPSHOT those logical shards' data on node A (consistent point-in-time copy).
3. STREAM the snapshot to node B (new node).
4. While streaming, TAIL the binlog/changes for those shards and apply to B (catch-up).
5. When B has caught up to within a tiny lag, briefly FREEZE writes for just those shards (sub-second).
6. UPDATE the routing map: logical shards 769–1024 now live on node B.
7. UNFREEZE; routing now sends those keys to B.
8. DELETE the moved data from A.
```

This is exactly how **Vitess** `MoveTables`/`Reshard` works (snapshot + VReplication stream + cutover with a brief query-serving switch). Notion documented moving from 32 to 480 logical shards across physical hosts this way. The key win: keys never rehash; you move whole slices and only flip a pointer.

### 3.10 Variant pipeline B — CDC-based engine migration (e.g., MySQL → new store)

When source and target are *different engines*, in-process dual-write is awkward (your app would need two different DAOs and translation logic on the hot path). CDC moves that work off the hot path:

```
1. SNAPSHOT the source table (consistent read; Debezium does this as its initial snapshot).
2. STREAM the snapshot rows into the target (this is the backfill).
3. SWITCH to STREAMING binlog/WAL changes via CDC: every insert/update/delete since the snapshot LSN/GTID flows to the target.
   - LSN = Log Sequence Number (Postgres WAL position). GTID = Global Transaction ID (MySQL’s globally-unique transaction marker). These let the consumer resume exactly where it left off.
4. The target now trails the source by the CDC lag (typically ms–seconds).
5. VERIFY with row counts + checksums/Merkle comparison; repair diffs.
6. SHADOW-READ from target.
7. CUTOVER reads, then writes; optionally reverse the CDC stream (target→source) for rollback.
8. DECOMMISSION source.
```

Tools that implement this: **Debezium** (binlog→Kafka→sink), **AWS DMS** (managed source→target with full-load + ongoing CDC), Vitess **VReplication**, **Maxwell**. Debezium's snapshot-then-stream model elegantly solves the backfill/dual-write race because the *same* connector does the snapshot and then continues exactly from the snapshot's log position — no gap, no overlap.

### 3.11 Variant pipeline C — changing the shard key

The hardest case, because a new shard key means **every row may move to a different shard** — there's no "expand in place." You effectively migrate to a freshly-built cluster sharded by the new key:

```
1. Build a NEW cluster sharded by the NEW key.
2. Dual-write: on each write, compute the row’s NEW-key shard and write there too.
3. Backfill: re-route every historical row to its NEW-key shard (a full re-distribution).
4. Beware: queries that filtered by the OLD key now scatter-gather across the NEW cluster
   until callers are updated to query by the new key. Plan caller migration alongside.
5. Shadow-read by querying the NEW cluster with NEW-key-aware queries; compare.
6. Cutover; decommission OLD.
```

The extra hazard versus a topology-only reshard: **query patterns change.** A read that was a single-shard lookup (`WHERE old_key = x`) may become a fan-out across all new shards (`WHERE old_key = x` when sharded by `new_key`). You must migrate read paths to use the new key (or maintain a secondary index / lookup table from old key → new key) or your latency and load explode after cutover.

### 3.12 State machine summary

```
        ┌─────────┐   enable        ┌────────────┐  start        ┌──────────┐
        │ PREPARED │ ──dual-write──▶ │ DUAL-WRITE │ ──backfill──▶ │ BACKFILL │
        └─────────┘                  └────────────┘               └──────────┘
                                                                       │ backfill done
                                                                       ▼
   ┌────────────┐   reads verified   ┌──────────────┐  enable     ┌──────────────┐
   │  CUTOVER   │ ◀──(ramp reads)──  │ SHADOW-READ  │ ◀─shadow──   │ (verified?)  │
   └────────────┘                    └──────────────┘             └──────────────┘
        │ writes flipped, hold window
        ▼
   ┌──────────────┐   confident    ┌────────────────┐
   │ NEW-AUTH     │ ──stop OLD──▶  │ DECOMMISSIONED │
   │ (dual-write) │               └────────────────┘
   └──────────────┘
        │ any phase up to NEW-AUTH: rollback = flip flag back (reverse sync keeps OLD current)
        ▼ ROLLBACK → previous stable state
```

---

## 4. The complete toolkit

### 4.1 Categories of tooling

| Category | Examples | What it does |
|---|---|---|
| Sharding middleware | Vitess, Citus (Postgres), ProxySQL, ShardingSphere | Routing, resharding orchestration, query rewriting |
| Online schema/data migration | gh-ost, pt-online-schema-change (Percona), pg_repack | Alter/copy tables online without locking |
| CDC / replication | Debezium, Maxwell, AWS DMS, Vitess VReplication, GoldenGate | Stream changes old→new in commit order |
| Coordination | ZooKeeper, etcd, Consul | Store shard map, leader election, locks |
| Verification | Vitess VDiff, Percona pt-table-checksum/pt-table-sync, custom Merkle jobs | Compare source vs target, repair diffs |
| Feature flagging | LaunchDarkly, Unleash, DB-backed flags | Runtime control of dual-write/read-source |
| Streaming backbone | Kafka, Kinesis, Pulsar | Durable transport for CDC events |

### 4.2 Vitess (MySQL sharding) — key operations

| Command / concept | Purpose | Key params / notes |
|---|---|---|
| `MoveTables` | Move tables between keyspaces (e.g., un-sharded → sharded) | `--source`, `--tables`; uses VReplication |
| `Reshard` | Split/merge shards within a keyspace | `--source_shards`, `--target_shards` |
| VReplication | Engine doing snapshot + binlog stream + apply | Handles backfill *and* catch-up |
| `VDiff` | Verify source vs target row-by-row | Run before `SwitchTraffic`; reports diffs |
| `SwitchTraffic` | Cut read/write traffic to target shards | Can switch `--tablet_types` (reads) then writes; reversible with `ReverseTraffic` |
| `ReverseTraffic` | Roll back a SwitchTraffic | Relies on reverse VReplication stream Vitess sets up automatically |
| VTGate / VTTablet | Query router / per-tablet agent | VTGate routes by shard key; VTTablet manages MySQL |
| Topology service | etcd/ZooKeeper/Consul storing shard map | Source of truth for routing |

Vitess's automatic **reverse VReplication stream** is the canonical implementation of §3.8's rollback guarantee: after `SwitchTraffic`, it streams target→source so `ReverseTraffic` is instant.

### 4.3 Debezium (CDC) — key configuration

| Config | Purpose | Default / notes |
|---|---|---|
| `snapshot.mode` | Whether/how to do initial backfill | `initial` (snapshot then stream); `never`, `schema_only`, `when_needed` |
| `database.server.id` | MySQL replica identity for binlog | Must be unique in the cluster |
| `table.include.list` | Which tables to capture | Comma-separated `db.table` |
| `tombstones.on.delete` | Emit a null-value record after delete (for Kafka compaction) | `true` by default |
| `snapshot.locking.mode` | Lock behavior during snapshot | `minimal` (brief global lock), `none`, `extended` |
| `decimal.handling.mode` | How DECIMAL maps to event types | `precise` (BigDecimal), `double`, `string` |
| GTID / binlog position | Resume point | Stored in offsets topic; enables exactly-resume |

### 4.4 gh-ost (GitHub online schema/table migration) — relevant flags

Though built for online `ALTER`, gh-ost's mechanism (copy to ghost table + binlog apply + atomic cut-over) is a textbook online-migration engine and worth knowing.

| Flag | Purpose | Default / notes |
|---|---|---|
| `--max-load` | Throttle if metrics exceed thresholds (e.g., `Threads_running=25`) | Adaptive backoff |
| `--chunk-size` | Rows per copy iteration | 1000; larger = faster but heavier |
| `--max-lag-millis` | Pause copy if replica lag exceeds | Protects replicas |
| `--cut-over` | Cut-over strategy | `atomic` (default), `two-step` |
| `--postpone-cut-over-flag-file` | Hold cut-over until a file is removed | Lets humans control the final swap |
| `--test-on-replica` / `--migrate-on-replica` | Run safely against a replica first | Dry-run safety |

`pt-online-schema-change` (Percona) is the trigger-based alternative: it creates triggers to keep a shadow table in sync while copying, then swaps. gh-ost is trigger-*less* (reads the binlog instead), which avoids the write-amplification and lock contention of triggers.

### 4.5 AWS DMS — key concepts

| Concept | Purpose | Notes |
|---|---|---|
| Replication instance | Compute that runs the migration | Size for full-load + CDC throughput |
| Endpoints | Source & target connection defs | Many engines supported, incl. heterogeneous |
| Migration type | `full-load`, `cdc`, `full-load-and-cdc` | The last = backfill then ongoing replication |
| Table mappings | Select/transform tables & columns | JSON rules |
| Validation | `EnableValidation` row-level compare | Reports mismatches per table |
| `cdc-start-position` | Resume CDC from a log position | For restarts |

### 4.6 Verification tools

| Tool | Method | Notes |
|---|---|---|
| `pt-table-checksum` | Chunked CRC checksums on source vs replica | Detects drift; runs on live traffic |
| `pt-table-sync` | Repairs differences found by checksum | Use cautiously; can write to source |
| Vitess `VDiff` | Row-by-row source/target diff | Built into reshard flow |
| Custom Merkle job | Hash key ranges, drill into mismatches | Scales to huge tables; minimizes data scanned |
| DMS validation | Built-in row compare | Managed |

### 4.7 Application-layer building blocks (Java)

| Building block | Purpose |
|---|---|
| Repository/DAO seam | Single place to choose OLD vs NEW |
| Feature-flag client | Runtime control of dual-write/read-source |
| Idempotent upsert | Safe re-apply for backfill + retries |
| Keyset pagination | O(1) chunked source scan |
| Async executor for shadow reads | Keep comparison off the hot path |
| Metrics (Micrometer) | match/mismatch counters, lag gauges, per-path timers |

---

## 5. Code examples by use case

All examples are Java unless noted, kept idiomatic and adaptable. Comments flag the non-obvious lines.

### 5.1 Use case: a dual-writing repository behind a routing seam

```java
// Routing seam: the rest of the app talks to this interface only.
public interface UserRepository {
    Optional<User> findById(long id);
    void save(User user);
    void delete(long id);
}

// Decorator that dual-writes OLD + NEW, controlled at runtime by flags.
public class DualWriteUserRepository implements UserRepository {

    private final UserRepository oldStore;   // authoritative during migration
    private final UserRepository newStore;    // understudy
    private final MigrationFlags flags;       // runtime-configurable, no deploy needed
    private final MeterRegistry metrics;      // Micrometer

    public DualWriteUserRepository(UserRepository oldStore, UserRepository newStore,
                                   MigrationFlags flags, MeterRegistry metrics) {
        this.oldStore = oldStore;
        this.newStore = newStore;
        this.flags = flags;
        this.metrics = metrics;
    }

    @Override
    public void save(User user) {
        // 1) Authoritative write decides the user-visible result.
        UserRepository primary = flags.newIsAuthoritative() ? newStore : oldStore;
        UserRepository secondary = flags.newIsAuthoritative() ? oldStore : newStore;

        primary.save(user); // if this throws, the request fails — correct behavior.

        // 2) Secondary write is best-effort during migration: log, don't fail the request.
        if (flags.dualWriteEnabled()) {
            try {
                secondary.save(user); // MUST be an idempotent upsert (see 5.3)
                metrics.counter("dualwrite.secondary.ok").increment();
            } catch (Exception e) {
                // Reconciliation will repair this row later; do NOT break production.
                metrics.counter("dualwrite.secondary.fail").increment();
                log.warn("secondary save failed for user {}", user.id(), e);
            }
        }
    }

    @Override
    public void delete(long id) {
        UserRepository primary = flags.newIsAuthoritative() ? newStore : oldStore;
        UserRepository secondary = flags.newIsAuthoritative() ? oldStore : newStore;
        primary.delete(id);
        if (flags.dualWriteEnabled()) {
            try { secondary.delete(id); }            // deletes MUST be dual-written too,
            catch (Exception e) {                    // or zombie rows survive in NEW.
                metrics.counter("dualwrite.secondary.fail").increment();
                log.warn("secondary delete failed for user {}", id, e);
            }
        }
    }

    @Override
    public Optional<User> findById(long id) {
        // Reads handled by ShadowReadRepository (5.2). Here, just serve authoritative.
        return flags.newIsAuthoritative() ? newStore.findById(id) : oldStore.findById(id);
    }
}
```

Why this shape: the *seam* (interface) lets you wrap behavior without touching call sites; flags make every behavior runtime-switchable; the secondary write is best-effort so a flaky new store can't take down production.

### 5.2 Use case: shadow reads with async comparison and transient-diff suppression

```java
public class ShadowReadUserRepository implements UserRepository {

    private final UserRepository authoritative;  // serves the user
    private final UserRepository shadow;          // measured only
    private final MigrationFlags flags;
    private final MeterRegistry metrics;
    private final ExecutorService pool;           // keep comparison OFF the hot path

    @Override
    public Optional<User> findById(long id) {
        Optional<User> result = authoritative.findById(id); // user gets THIS

        if (flags.shadowReadEnabled()) {
            // Fire-and-forget: never let the shadow path add latency or throw to the caller.
            pool.submit(() -> compare(id, result));
        }
        return result;
    }

    private void compare(long id, Optional<User> authoritativeValue) {
        try {
            Optional<User> shadowValue = shadow.findById(id);
            if (Objects.equals(authoritativeValue, shadowValue)) {
                metrics.counter("shadow.match").increment();
                return;
            }
            // Mismatch could be transient (replication lag / dual-write race).
            // Re-check after a short delay before counting it as a real defect.
            Thread.sleep(500); // crude; use a delayed-recheck queue in production
            Optional<User> recheck = shadow.findById(id);
            if (Objects.equals(authoritativeValue, recheck)) {
                metrics.counter("shadow.transient").increment(); // benign lag
            } else {
                metrics.counter("shadow.mismatch.persistent").increment(); // REAL defect
                log.error("PERSISTENT mismatch id={} auth={} shadow={}",
                          id, authoritativeValue, recheck);
            }
        } catch (Exception e) {
            metrics.counter("shadow.error").increment();
        }
    }
    // save/delete delegate to the wrapped authoritative repo...
}
```

The persistent-mismatch counter is your **cutover gate**: it must sit at zero over a sustained window before you ramp reads.

### 5.3 Use case: idempotent backfill with keyset pagination and version-guarded upsert

```java
public class Backfiller {

    private final JdbcTemplate oldDb;
    private final JdbcTemplate newDb;
    private final RateLimiter limiter;     // Guava RateLimiter, e.g., 5000 rows/sec
    private final LagMonitor lagMonitor;   // checks replica lag to back off adaptively

    public void run() {
        long lastId = 0;
        final int chunk = 1000;
        while (true) {
            // Keyset (seek) pagination: O(1) per page, unlike OFFSET which scans+discards.
            List<User> rows = oldDb.query(
                "SELECT id, name, email, updated_at FROM users " +
                "WHERE id > ? ORDER BY id LIMIT ?",
                userMapper, lastId, chunk);
            if (rows.isEmpty()) break;

            for (User u : rows) {
                limiter.acquire();                 // throttle to protect production IOPS
                upsertIfNewer(u);                  // version-guarded, race-safe
            }
            lastId = rows.get(rows.size() - 1).id();

            if (lagMonitor.replicaLagSeconds() > 5) {
                sleepSeconds(10);                  // adaptive backoff: don't drown replicas
            }
        }
    }

    // Version-guarded upsert: only overwrite NEW if the source row is newer,
    // so a slow backfill chunk cannot clobber a fresher dual-write. (Race fix from §3.4)
    private void upsertIfNewer(User u) {
        newDb.update(
            "INSERT INTO users (id, name, email, updated_at) VALUES (?,?,?,?) " +
            "ON DUPLICATE KEY UPDATE " +
            "  name = IF(VALUES(updated_at) > updated_at, VALUES(name), name), " +
            "  email = IF(VALUES(updated_at) > updated_at, VALUES(email), email), " +
            "  updated_at = IF(VALUES(updated_at) > updated_at, VALUES(updated_at), updated_at)",
            u.id(), u.name(), u.email(), Timestamp.from(u.updatedAt()));
    }
}
```

Note: reading source from a *replica* (point `oldDb` at a read replica) spares the primary; the slight staleness is fine because reconciliation and dual-write converge it.

### 5.4 Use case: Debezium CDC connector for MySQL → Kafka → new store (config + sink)

```json
// Debezium MySQL source connector: snapshot then stream changes.
{
  "name": "users-cdc",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "old-mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "${file:/secrets:dbz}",   // never inline secrets
    "database.server.id": "184054",                 // unique replica id in the cluster
    "topic.prefix": "olddb",
    "table.include.list": "appdb.users",
    "snapshot.mode": "initial",                     // backfill (snapshot) THEN stream
    "tombstones.on.delete": "true",                 // emit delete markers for compaction
    "decimal.handling.mode": "precise"              // exact money values as BigDecimal
  }
}
```

```java
// A minimal idempotent Kafka sink consumer applying CDC events to the NEW store.
void onChangeEvent(ChangeEvent<String, String> event) {
    Envelope env = parse(event.value());     // Debezium envelope: op, before, after, source
    switch (env.op()) {                       // c=create, u=update, d=delete, r=snapshot-read
        case "c", "u", "r" -> newStore.upsert(env.after());   // idempotent by PK
        case "d"           -> newStore.deleteByPk(env.before().pk());
    }
    // Commit the Kafka offset only AFTER the target write succeeds → at-least-once.
    // Because writes are idempotent, at-least-once delivery is safe (re-apply is a no-op).
}
```

The pairing of **idempotent upserts** + **commit-after-apply** gives effective exactly-once *outcomes* on top of at-least-once delivery — the standard, pragmatic alternative to true exactly-once.

### 5.5 Use case: Merkle-tree-style reconciliation (compare cheaply, repair precisely)

```java
// Compare source and target over key ranges using checksums; only drill where they differ.
public class RangeReconciler {

    private final JdbcTemplate src, dst;
    private final int rangeSize = 100_000;   // ids per range

    public void reconcile(long minId, long maxId) {
        for (long lo = minId; lo < maxId; lo += rangeSize) {
            long hi = Math.min(lo + rangeSize, maxId);
            String srcSum = checksum(src, lo, hi);
            String dstSum = checksum(dst, lo, hi);
            if (!srcSum.equals(dstSum)) {
                // Hashes differ → this range has drift. Drill in and repair just this range.
                repairRange(lo, hi);
            }
            // Matching ranges are skipped entirely — that's the cost saving vs full scan.
        }
    }

    private String checksum(JdbcTemplate db, long lo, long hi) {
        // Order-independent rollup: XOR/sum of per-row CRCs so it doesn't matter
        // what order rows come back in.
        return db.queryForObject(
            "SELECT COALESCE(BIT_XOR(CRC32(CONCAT_WS('|', id, name, email, updated_at))), 0) " +
            "FROM users WHERE id >= ? AND id < ?", String.class, lo, hi);
    }

    private void repairRange(long lo, long hi) {
        List<User> truth = src.query(
            "SELECT id,name,email,updated_at FROM users WHERE id>=? AND id<?",
            userMapper, lo, hi);
        truth.forEach(this::upsertIntoTarget); // re-copy source-of-truth rows
        log.warn("Repaired range [{},{})", lo, hi);
    }
}
```

This mirrors how production verification scales: full row-by-row compare on a 10B-row table is infeasible nightly, but hashing 100k-row ranges and only drilling into the (usually few) mismatched ranges is.

### 5.6 Use case: changing the shard key with a lookup table (avoid scatter-gather)

```java
// New cluster is sharded by tenant_id. Old code queried by user_id (single-shard on OLD).
// To keep user_id lookups single-shard on NEW, maintain a lookup index user_id -> tenant_id.

public Optional<User> findByUserId(long userId) {
    // 1) Resolve the new shard key from a small, separately-sharded lookup table.
    Long tenantId = lookupRepo.tenantForUser(userId);   // sharded by user_id; cheap point read
    if (tenantId == null) return Optional.empty();
    // 2) Now do a single-shard read on the NEW cluster using the NEW shard key.
    return newUserRepo.findByUserAndTenant(tenantId, userId);  // routed to ONE shard
}
```

Without the lookup table, `findByUserId` on a cluster sharded by `tenant_id` becomes a fan-out across all shards — exactly the scatter-gather (§3.11) you must avoid. The lookup table (kept in sync via dual-write/CDC like everything else) converts a fan-out into two point reads.

### 5.7 Use case: feature-flagged percentage read ramp during cutover

```java
public UserRepository chooseReadSource(long id) {
    int pct = flags.newReadPercent();         // 0..100, runtime-tunable
    // Deterministic per-id bucketing so a given row reads from a STABLE source
    // (avoids flapping a single user between stores on consecutive reads → read-your-writes).
    int bucket = Math.floorMod(Long.hashCode(id), 100);
    return bucket < pct ? newStore : oldStore;
}
```

Deterministic bucketing matters: random selection per request would flip the same user between stores, re-introducing the read-after-write hazard. Bucketing by id (or tenant) keeps each entity's reads stable as you ramp.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Throttle the backfill adaptively.** A bulk scan competes with production for IOPS, buffer pool, and replica bandwidth. Drive throttling off live signals (`Threads_running`, replica lag, p99 latency), not a fixed sleep. gh-ost's `--max-load`/`--max-lag-millis` are the reference model.
- **Read source from replicas** for backfill and reconciliation to protect the primary; accept slight staleness (converged later).
- **Keyset pagination, never OFFSET.** `OFFSET 1_000_000` scans and discards a million rows per page; keyset (`WHERE id > ?`) is O(1).
- **Keep shadow reads off the hot path** (async, fire-and-forget) so verification never adds user-facing latency.
- **Batch writes to the target.** Single-row upserts at backfill scale are network-bound; batch (multi-row INSERT, JDBC `addBatch`) within transaction-size limits.

### 6.2 Correctness & concurrency

- **The backfill↔dual-write race is the #1 correctness bug.** Use version/timestamp-guarded upserts (write only if source is newer) plus a reconciliation pass. (§3.4, §5.3)
- **Idempotency everywhere.** Every target write — backfill, CDC apply, dual-write secondary, repair — must be a safe re-apply (upsert by PK). Pipelines retry; non-idempotent writes double-apply.
- **Capture deletes and updates**, not just inserts. CDC handles this for free; in-process dual-write must explicitly mirror deletes.
- **Order matters under CDC.** Apply events in commit order per key. Kafka preserves order *within a partition*; partition by primary key so all changes to a row land in one partition and stay ordered.
- **Read-your-writes during cutover.** Cut whole entities/tenants atomically or use deterministic, sticky read-source selection (§5.7) to avoid a user reading a store that doesn't yet have their just-written value.

### 6.3 Security

- **Secrets:** never inline DB credentials in connector configs or code; use a secrets manager / file provider (note the `${file:...}` in §5.4).
- **Least privilege:** the migration user needs only what it needs — CDC needs replication privileges (e.g., MySQL `REPLICATION SLAVE`, `REPLICATION CLIENT`); the backfill writer needs only upsert on target tables.
- **Encryption in transit:** TLS between source, pipeline, and target — you're streaming the entire dataset across the network.
- **PII handling:** if regulations forbid certain data in the new region/store, mask or filter in the pipeline (DMS table-mapping transforms, Debezium SMTs — Single Message Transforms).
- **Audit:** log who flipped which flag and when; cutover decisions are high-stakes.

### 6.4 Observability (you cannot operate what you can't see)

Instrument and dashboard, at minimum:

- Write success/fail **per store** and per phase.
- **Persistent mismatch rate** (the cutover gate) and transient mismatch rate (lag indicator).
- **Replication / CDC lag** (seconds behind), with alerts.
- **Backfill progress** (rows done / total, ETA) and throttle state.
- p50/p99 latency and error rate **per read/write path** (old vs new).
- Flag state changes as annotated events on the dashboards.

### 6.5 Cost

- **Double-write capacity:** during dual-write you're writing to two clusters — budget for the extra IOPS/storage of running both in parallel for the whole migration window (often weeks).
- **CDC infrastructure:** Kafka/connect clusters, DMS replication instances — non-trivial and easy to under-provision (then CDC lag grows).
- **Egress / cross-region transfer** for region migrations can be a large, surprising line item.
- **Time:** a careful migration of a large dataset is *weeks to months* of calendar time; staff accordingly.

### 6.6 Testing

- **Test the rollback path before you need it** — flip back in staging and confirm OLD is current. Untested rollback is not rollback.
- **Dry-run on a replica** (gh-ost `--test-on-replica`) to validate mechanics without touching production writes.
- **Game-day / chaos:** kill the CDC pipeline mid-stream and confirm it resumes from the saved log position with no gap; inject a target-write failure and confirm reconciliation repairs it.
- **Verify with checksums**, not vibes. Row counts alone miss content drift; use checksum/Merkle comparison (§5.5, pt-table-checksum, VDiff).
- **Load-test the new topology at peak** before cutover — shadow reads at 100% of peak traffic prove capacity, not just correctness.

### 6.7 Production hardening

- **Kill switches** for every behavior, runtime-togglable in seconds.
- **Idempotent, resumable** every phase: a crashed backfill resumes from `lastId`; a crashed CDC consumer resumes from the committed offset/GTID.
- **Gradual everything:** ramp reads by percentage/tenant; never flip 0→100.
- **Hold windows:** stay dual-running for days after cutover before decommissioning.
- **Runbook + on-call:** written runbook with exact commands, owners, decision thresholds, and the rollback procedure.

### 6.8 Anti-patterns to avoid

| Anti-pattern | Why it bites | Do instead |
|---|---|---|
| Big-bang cutover | No rollback; full blast radius | Phased ramp + dual-run hold |
| 2PC across old/new on the hot path | Blocking, kills latency/availability | Best-effort dual-write + reconcile |
| Backfill with `OFFSET` | O(n) drift; skips/dupes under concurrency | Keyset pagination |
| Non-idempotent target writes | Double-apply on retry | Upsert by PK |
| Ignoring the backfill/dual-write race | Stale data clobbers fresh | Version-guarded upsert + reconcile |
| Forgetting deletes/updates in dual-write | Zombie rows in NEW | Mirror all op types or use CDC |
| Shadow reads on the hot path | Adds user-facing latency | Async fire-and-forget |
| "Verify" by row counts only | Misses content drift | Checksum/Merkle compare |
| Schema change *and* topology change at once | Compounded risk | One change at a time |
| Leaving migration scaffolding in code | Future bugs, confusion | Clean up dual-write/flags after |
| No tested rollback | Discover it fails when you need it most | Test rollback in staging first |
| Changing shard key without fixing read paths | Scatter-gather latency explosion | Migrate read paths / add lookup index |

---

## 7. Advanced topics & deep internals

### 7.1 Snapshot consistency: how a "consistent read" is taken without long locks

A naive snapshot (`SELECT *` while writes continue) yields a smeared, inconsistent picture. Engines provide point-in-time consistent reads via **MVCC (Multi-Version Concurrency Control)**: the DB keeps multiple versions of rows, and a transaction reads the version "as of" its start, so concurrent writes don't tear the snapshot. Debezium's MySQL snapshot grabs a brief global read lock to capture the binlog position, then reads in a **REPEATABLE READ** transaction (an MVCC isolation level where the whole transaction sees a single consistent snapshot) so it can release the lock and read consistently while writes continue. The captured **GTID/binlog position** is the exact seam where snapshot ends and streaming begins — no gap, no overlap.

### 7.2 Exactly-once semantics in the pipeline (and why "effectively-once" is the real answer)

True exactly-once delivery across systems is famously hard (it reduces to distributed consensus on every message). The practical recipe:

- **At-least-once delivery** (the pipeline may redeliver on failure) +
- **Idempotent application** (re-applying a change is a no-op) =
- **Effectively-once outcomes.**

Commit the consumer offset *after* the target write succeeds (commit-after-apply). On crash, you reprocess the last few events, but idempotent upserts make that harmless. Kafka's transactional producer + idempotent consumer can give stronger guarantees within Kafka, but the *sink to an external DB* still relies on idempotency.

### 7.3 Ordering guarantees and the per-key partition rule

Cross-table or cross-key ordering is generally *not* preserved by CDC; *per-key* ordering is what you need and can get. **Partition the change stream by primary key** so all changes to one row traverse the same Kafka partition and are applied in commit order. If you partition by something else, two updates to the same row can land in different partitions and be applied out of order, producing wrong final state. For relationships requiring cross-entity ordering (e.g., parent insert before child), either co-partition related keys or tolerate transient FK violations and retry.

### 7.4 Schema evolution during migration

If the target schema differs (renamed columns, split tables, new types), the pipeline must **transform** events. Options: Debezium SMTs, DMS transformation rules, or a translation layer in your sink consumer. Rule of thumb: **migrate topology first with an identical schema, get stable, then evolve schema as a separate change.** Doing both at once means a mismatch can't be cleanly attributed to either change.

### 7.5 Handling very large rows, BLOBs, and hot keys

- **Large rows/BLOBs** strain CDC event size limits (Kafka `max.message.bytes`) and snapshot memory. Consider streaming BLOBs separately (object store) and migrating only references, or raising message-size limits deliberately.
- **Hot keys** (a single shard key with extreme write volume) cause one CDC partition to lag the rest. Mitigations: split the hot entity (sub-sharding), or accept and isolate that one stream.

### 7.6 Tuning knobs and lesser-known behaviors

- **Vitess `num_tokens`/vnode count** (Cassandra) trades distribution smoothness against repair/streaming cost — lower vnode counts (16) speed up streaming/repair at some distribution cost.
- **gh-ost `--cut-over=atomic`** uses a clever lock-and-rename dance; the `--postpone-cut-over-flag-file` lets a human gate the final, irreversible swap — invaluable for doing the risky bit during business hours with eyes on dashboards.
- **Replica reads for backfill** can hit *stale* data; if your dual-write hasn't propagated to the replica yet, you may copy a pre-dual-write version — reconciliation must run *after* backfill, and the version-guard prevents clobbering.
- **CDC initial-snapshot duration** on huge tables can take hours/days; `snapshot.mode=when_needed` and incremental snapshots (Debezium's "signal table" / watermark-based incremental snapshot) let you snapshot without one giant locking pass.
- **Backpressure:** if the target can't keep up, the CDC consumer must slow the source read (pause polling) rather than buffer unboundedly into OOM.

### 7.7 Multi-region and active-active complications

Migrating across regions adds **latency** (dual-writing across an ocean adds 100+ ms) and **conflict** risk if both regions accept writes (active-active). Conflict resolution strategies: **last-write-wins** (simple, can lose data), **CRDTs** (Conflict-free Replicated Data Types — data structures that mathematically merge without conflict, e.g., counters, OR-sets), or application-level merge. For active-active, you often migrate one region at a time and tolerate a window of single-region writes.

### 7.8 The "online schema change" engines as migration primitives

`gh-ost` and `pt-osc` are, mechanically, mini online migrations of a *single table to itself with a new schema*: build a ghost/shadow table, copy rows in chunks, keep it synced (binlog tail for gh-ost; triggers for pt-osc), then atomically swap names. Understanding them deeply teaches the whole pattern in miniature — same backfill, same catch-up, same atomic cut-over, same throttling.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Stop-the-world vs online migration

| Dimension | Stop-the-world (dump/restore) | Online (dual-write/CDC) |
|---|---|---|
| Downtime | Minutes to hours | ~Zero |
| Complexity | Low | High |
| Engineering time | Days | Weeks–months |
| Risk surface | Small, contained | Large, but reversible |
| Data size ceiling | Small/medium (fits the window) | Any size |
| When to use | Small DB, tolerable window | Large DB, no window allowed |

> **Use stop-the-world when** the dataset is small enough to copy within an acceptable maintenance window and the business permits one. **Use online when** downtime is unacceptable or the dataset is too big to copy in the window.

### 8.2 Expand-in-place vs migrate-to-new-cluster

| Dimension | Expand-in-place (move logical shards / add nodes) | Migrate-to-new-cluster |
|---|---|---|
| Prerequisite | Logical-shard design or consistent hashing | None |
| Data movement | Only the moved slices (K/N) | Potentially all rows |
| Shard key change | Not possible (same key) | Possible |
| Engine change | No | Yes |
| Risk | Lower (metadata + slice copy) | Higher (full re-distribution) |
| Tooling | Vitess Reshard, Cassandra add-node | Dual-write/CDC playbook |

> **Expand-in-place when** you only need more capacity/rebalancing and your design supports moving slices without rehashing. **Migrate to a new cluster when** you're changing the shard key, the engine, or the fundamental topology.

### 8.3 In-process dual-write vs CDC-based migration

| Dimension | In-process dual-write | CDC-based |
|---|---|---|
| Hot-path impact | Adds a write to every request | None (off hot path) |
| Heterogeneous engines | Awkward (two DAOs, translation) | Natural (translate in pipeline) |
| Capture completeness | Easy to miss a code path | Captures *everything* at the log |
| Infra needed | Minimal (just app code) | Kafka/Connect/DMS |
| Atomicity of capture | Risk of lost events on crash | Log is authoritative; no loss |
| Best for | Same engine, code-controlled writes | Engine change, many write paths, completeness |

> **Dual-write when** source and target are the same engine and all writes flow through a controllable code seam. **Use CDC when** engines differ, writes enter through many paths (incl. out-of-band), or you need guaranteed completeness. **Transactional outbox** is the hybrid: capture atomically in-process, apply via CDC.

### 8.4 Verification: full scan vs checksum/Merkle

| Method | Cost | Thoroughness | When |
|---|---|---|---|
| Row-count compare | Cheap | Weak (misses content drift) | Quick sanity only |
| Full row-by-row | Expensive | Total | Small tables / final gate |
| Checksum per range | Moderate | High | Large tables, periodic |
| Merkle tree | Cheap to localize | High | Huge tables, repeated runs |

### 8.5 Range vs hash vs consistent-hash partitioning (resharding lens)

| Scheme | Resharding ease | Hotspot risk | Range scans | Used by |
|---|---|---|---|---|
| Range | Easy (split midpoint) | High (hot tail) | Excellent | HBase, Spanner, Vitess (range) |
| Hash mod N | Catastrophic (rehash all) | Low | Poor | Naive sharding (avoid for growth) |
| Consistent hash + vnodes | Easy (move K/N keys) | Low | Poor | Cassandra, DynamoDB, Riak |
| Logical shards (fixed N) | Easy (move slices) | Low | Depends | Vitess, Slack, Notion, Figma |

---

## 9. Failure modes & debugging

### 9.1 Replication / CDC lag blows up

**Symptom:** target trails source by growing seconds/minutes; shadow mismatches spike (all transient).
**Causes:** backfill saturating the source/replica; target can't absorb writes; hot-key partition skew; under-provisioned CDC compute.
**Diagnose:** MySQL `SHOW SLAVE STATUS` → `Seconds_Behind_Master`; Debezium connector lag metrics; Kafka consumer lag (`kafka-consumer-groups --describe`); per-partition lag to spot a hot key.
**Fix:** throttle backfill; scale target write capacity; scale CDC consumers; repartition hot key.

### 9.2 The backfill/dual-write race (stale clobber)

**Symptom:** a row in NEW is older than OLD; persistent mismatch on specific rows that *were* recently updated.
**Cause:** backfill wrote a stale version after dual-write wrote the fresh one.
**Diagnose:** compare `updated_at` of mismatched rows in OLD vs NEW; correlate with backfill timing.
**Fix:** version-guarded upsert (§5.3) and a reconciliation pass after backfill completes.

### 9.3 Lost deletes (zombie rows)

**Symptom:** NEW has rows that no longer exist in OLD; counts diverge upward in NEW.
**Cause:** dual-write or CDC didn't propagate deletes.
**Diagnose:** anti-join (`rows in NEW not in OLD`); check CDC `tombstones.on.delete` / delete handling.
**Fix:** ensure delete events are captured/applied; run reconciliation to remove zombies.

### 9.4 Out-of-order application (wrong final state)

**Symptom:** a row's value in NEW reflects an older write than its latest.
**Cause:** events for one key spread across Kafka partitions and applied out of order.
**Diagnose:** trace the event sequence (offsets, source LSN/GTID) for the bad key.
**Fix:** partition the stream by primary key (§7.3); reprocess the affected key.

### 9.5 Read-after-write violation during cutover

**Symptom:** user writes, then immediately reads stale/missing data.
**Cause:** write landed in one store, read routed to the other before sync.
**Diagnose:** correlate the user's request trace across stores and the flag/bucket they hit.
**Fix:** deterministic sticky read-source by entity (§5.7); cut whole tenants atomically; brief read-from-both-prefer-authoritative.

### 9.6 Cutover ramp shows error/latency spike

**Symptom:** at, say, 25% reads on NEW, p99 or error rate jumps.
**Cause:** NEW under-provisioned, missing index, cold cache, or a query that's single-shard on OLD but scatter-gather on NEW.
**Diagnose:** per-path latency dashboards; slow-query log on NEW; `EXPLAIN` the hot queries against NEW's topology.
**Fix:** roll the ramp back (flag), add indexes/capacity, fix query routing (lookup table for shard-key change), retry.

### 9.7 Pipeline crash / resume gap

**Symptom:** after a CDC consumer restart, some changes missing.
**Cause:** offset committed *before* the write succeeded (commit-before-apply) → those events skipped on resume.
**Diagnose:** compare committed offset/GTID against last successfully applied row.
**Fix:** commit-after-apply (§5.4); replay from the last *safely applied* position; reconcile the gap.

### 9.8 Real-world war stories (publicly documented patterns)

- **Stripe — Online migrations at scale.** Stripe's engineering writing on moving millions of objects between databases is the canonical articulation of the **dual-write → backfill → dual-read/compare → cutover** pattern, including reading from both stores and comparing during the transition before flipping. (Widely cited as the template for this playbook.)
- **GitHub — gh-ost.** GitHub built gh-ost to do **triggerless** online schema migrations after trigger-based tools caused load/lock problems; it reads the binlog, copies in throttled chunks, and does an atomic cut-over — the online-migration pattern distilled into one tool.
- **Notion — sharding Postgres.** Notion publicly described sharding their Postgres (and later re-sharding to far more logical shards across more physical hosts) using a double-write + backfill + verify + switch approach with careful auditing — a textbook large-scale reshard.
- **Slack / Figma / Vitess users** — moving to **logical shards** so growth becomes "move a slice, flip a pointer" rather than rehash-the-world; Vitess's `Reshard`/`VReplication`/`VDiff`/`SwitchTraffic`/`ReverseTraffic` operationalize the entire playbook including built-in reverse replication for instant rollback.

> Where exact numbers (object counts, durations, error rates) matter for your decisions, treat the above as *patterns* and verify the specifics against the primary engineering posts and your own measurements — these are illustrative of the approach, not precise benchmarks.

---

## 10. Interview drill

**Q1. Walk me through migrating a 5 TB MySQL table to a new sharded cluster with zero downtime.**
*Model answer:* Stand up the target with matching schema and the new topology behind a routing seam and feature flags (Phase 0). Enable dual-write so new writes hit both stores, old store authoritative (Phase 1). Backfill historical rows with keyset pagination and version-guarded idempotent upserts, throttled off live load, reading from a replica (Phase 2). Turn on shadow reads to compare NEW vs OLD under live traffic, plus a checksum/Merkle reconciliation job; gate on zero persistent mismatches (Phase 3). Ramp reads 1→100% and then flip write authority while keeping reverse dual-write for instant rollback (Phase 4). Hold, then decommission and clean up scaffolding (Phase 5).
- *Probe: why not 2PC across the two stores?* It's blocking, kills latency/availability, and holds locks if the coordinator dies; migrations accept temporary inconsistency and reconcile instead.
- *Probe: how do you handle a row updated mid-backfill?* Version/timestamp-guarded upsert (only overwrite if source is newer) plus a reconciliation pass after backfill.
- *Probe: how do you make rollback a flag flip?* Maintain reverse sync (dual-write or reverse CDC) target→source so OLD stays current; rollback just re-points authority.

**Q2. Why is `hash(key) mod N` sharding so painful to reshard, and what fixes it?**
*Model answer:* Changing N remaps almost every key to a different shard, forcing a near-total data shuffle. Fixes: **consistent hashing** (only ~K/N keys move when a node joins/leaves) or **fixed large logical-shard count** (key→logical-shard is stable; you move whole logical shards between nodes without rehashing).
- *Probe: how do vnodes help?* They scatter many small ranges per node, smoothing distribution and parallelizing the move during rebalancing.
- *Probe: downside of consistent hashing?* Loses range-scan locality; uneven distribution without vnodes.

**Q3. Explain dual-write and its failure window. How do you keep production safe if the new store flaps?**
*Model answer:* Every write goes to OLD (authoritative) and NEW (understudy). There's a non-atomic window where OLD commits but NEW fails — that's expected; make NEW best-effort (log and continue) so a flaky NEW can't break production, and let reconciliation/backfill repair the gap. NEW writes must be idempotent upserts.
- *Probe: what if you need stronger guarantees?* Use a transactional outbox so capture is atomic with the business write, then apply via CDC.
- *Probe: what about deletes?* Must be dual-written too, or you get zombie rows.

**Q4. How does CDC-based migration avoid the backfill/streaming gap?**
*Model answer:* The connector takes a consistent snapshot at a recorded log position (GTID/LSN) using MVCC, then begins streaming changes *from exactly that position* — the snapshot's end is the stream's start, so no events are missed or double-counted. Idempotent application makes any redelivery harmless.
- *Probe: how is the snapshot consistent without long locks?* Brief lock to grab the binlog position, then REPEATABLE READ MVCC transaction reads while writes continue.
- *Probe: how do you keep per-row ordering?* Partition the change stream by primary key so all changes to a row stay ordered in one partition.

**Q5. (Senior signal) When would you NOT do an online migration?**
*Model answer:* When the dataset fits within an acceptable maintenance window and the business permits one — then stop-the-world dump/restore is far cheaper and lower-risk. Online machinery (CDC, dual-write, weeks of dual-running) is justified only when downtime is unacceptable or data is too large for the window. Choosing the elaborate path for a small DB is over-engineering.
- *Probe: how do you decide the threshold?* Estimate dump+restore time vs tolerable window; include re-index and warm-up time; add margin.
- *Probe: hybrid options?* Logical replication to a follower then a brief switch can shrink the window without the full dual-write rig.

**Q6. (Senior signal) You changed the shard key and latency exploded after cutover. What happened and how do you prevent it?**
*Model answer:* Queries that were single-shard on the old key became scatter-gather fan-outs on the new key. Prevent it by migrating read paths to use the new key, or maintaining a secondary lookup index (old key → new key) so old-key lookups become a cheap point read plus a single-shard read. Plan caller migration *alongside* the data migration, not after.
- *Probe: cost of the lookup table?* It's another dataset to keep in sync (dual-write/CDC) and adds a read hop; usually worth it to avoid fan-out.
- *Probe: how detect this pre-cutover?* Shadow-read at peak with new-key-aware queries and watch per-path latency.

**Q7. (Senior signal) Justify in-process dual-write vs CDC for a specific scenario.**
*Model answer:* If both stores are MySQL and all writes go through one service's DAO, in-process dual-write is simplest and needs no extra infra. If the target is a different engine, or writes enter through many services / out-of-band scripts, CDC is better: it captures everything at the log level (completeness) and keeps translation off the hot path. The deciding factors are engine homogeneity, write-path controllability, and completeness requirements.
- *Probe: what does the outbox pattern buy you?* Atomic capture with the business transaction (no lost events on crash) while still applying off the hot path.
- *Probe: CDC's operational cost?* A streaming backbone (Kafka/Connect/DMS) to provision, monitor, and keep within lag SLOs.

**Q8. How do you verify the new store actually matches before cutover, at scale?**
*Model answer:* Don't rely on row counts (they miss content drift). Use checksum-per-range or Merkle-tree comparison: hash key ranges on both sides, drill only into ranges whose hashes differ, repair by re-copying source-of-truth rows. Combine with shadow reads that compare live results and a persistent-mismatch metric as the cutover gate.
- *Probe: how handle transient mismatches from lag?* Re-check after a delay; only count persistent diffs.
- *Probe: tools?* pt-table-checksum, Vitess VDiff, DMS validation, or a custom Merkle job.

**Q9. The CDC consumer crashed and some changes are missing after restart. Root cause?**
*Model answer:* Offsets were committed before the target write succeeded (commit-before-apply), so skipped events were never applied on resume. Fix: commit-after-apply for at-least-once + idempotent writes for effectively-once; replay from the last safely applied position and reconcile the gap.
- *Probe: why is exactly-once hard?* It reduces to distributed consensus per message; effectively-once via idempotency is the pragmatic answer.
- *Probe: how prevent unbounded buffering if target is slow?* Backpressure — pause source polling rather than buffer into OOM.

**Q10. (Senior signal) Your migration is "done" — what's left, and what's the risk of skipping it?**
*Model answer:* Stop dual-writing OLD, run a final reconciliation, snapshot OLD and keep it read-only for a grace period as insurance, then remove dual-write code, flags, and dead routing branches. Skipping cleanup leaves confusing dual paths that cause future bugs; decommissioning too early removes your last rollback insurance.
- *Probe: when is rollback no longer a flag flip?* Once you stop dual-writing OLD, OLD goes stale; reverting then requires re-syncing OLD from NEW — effectively a new migration.
- *Probe: how long to keep OLD?* Days to weeks, sized to your confidence and the cost of keeping it.

---

## 11. Glossary

- **Active-active:** topology where multiple regions/nodes accept writes simultaneously; requires conflict resolution.
- **Asynchronous replication:** replicas receive changes after the primary acks; fast, can lose recent writes on failover.
- **At-least-once delivery:** messages may be delivered more than once; safe only with idempotent consumers.
- **Backfill:** copying historical rows (those predating dual-write) from source to target.
- **Binlog (binary log):** MySQL's ordered record of all data changes; the source for replication and CDC.
- **CAP theorem:** under a partition, choose Consistency or Availability.
- **CDC (Change Data Capture):** streaming a DB's change log to consumers in commit order.
- **Checksum comparison:** hashing row ranges to detect drift cheaply.
- **Consistent hashing:** ring-based key→node mapping where adding/removing a node moves only ~K/N keys.
- **CRDT:** Conflict-free Replicated Data Type; data structure that merges concurrent updates without conflict.
- **Cutover:** flipping read/write authority from old to new store.
- **Dual-write:** writing each change to both old and new stores during migration.
- **Effectively-once:** at-least-once delivery + idempotent application = each change's effect applied once.
- **Eventual consistency:** replicas converge over time; reads may be briefly stale.
- **Feature flag / kill switch:** runtime-toggleable control over behavior, no deploy.
- **GTID (Global Transaction ID):** MySQL's globally-unique transaction identifier; a resumable stream position.
- **Hash partitioning:** assign shards via `hash(key) mod N`; even spread, hard to reshard.
- **Hot key / hot shard:** a key or shard receiving disproportionate load.
- **Idempotency:** an operation safe to apply repeatedly with the same result.
- **Keyset (seek) pagination:** paging via `WHERE id > :last`; O(1) per page.
- **Logical shard:** a stable, fine-grained partition mapped onto physical nodes via a mutable map.
- **LSN (Log Sequence Number):** Postgres WAL position; a resumable stream position.
- **Merkle tree:** hierarchical hashing that localizes differences to small ranges.
- **MVCC (Multi-Version Concurrency Control):** keeping multiple row versions so readers see a consistent snapshot without blocking writers.
- **Outbox pattern:** writing change-capture rows in the same transaction as the business write, applied later via CDC.
- **PACELC:** extends CAP — under partition choose C/A; else choose Latency/Consistency.
- **Partition (Kafka):** an ordered subdivision of a topic; ordering is guaranteed within, not across, partitions.
- **Quorum:** a majority of nodes whose agreement commits a write (in consensus protocols).
- **Raft / ZAB:** consensus algorithms behind etcd / ZooKeeper.
- **Range partitioning:** assign contiguous key ranges to shards; great for scans, prone to hot tail.
- **Read-after-write (read-your-writes):** guarantee that your own writes are visible to your subsequent reads.
- **Reconciliation:** comparing source and target and repairing differences.
- **Replica / primary:** copy node / write-accepting node.
- **Resharding:** changing how data is partitioned across nodes.
- **Scatter-gather:** a query that must fan out to many shards and merge results.
- **Shadow read (dark read):** reading from the new store for comparison only, not serving it to users.
- **Shard / sharding:** a horizontal slice of a table / splitting a table across nodes.
- **Shard key (partition key):** the column(s) determining a row's shard.
- **SMT (Single Message Transform):** lightweight per-event transformation in Kafka Connect/Debezium.
- **Snapshot:** a consistent point-in-time copy of data.
- **Strong consistency:** every read returns the latest write.
- **Synchronous replication:** primary waits for replicas before acking; safe, slower.
- **Tombstone:** a record marking a deletion (used for log compaction).
- **Two-phase commit (2PC):** blocking protocol for atomic multi-system commit; avoided in migrations.
- **Vitess:** MySQL sharding middleware with built-in resharding/migration tooling.
- **Vnode (virtual node):** many small token ranges per physical node for smoother distribution.
- **WAL (Write-Ahead Log):** Postgres's ordered change log; written before data files.
- **ZooKeeper / etcd:** distributed coordination services storing consistent shared metadata.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The playbook (memorize):** Prepare → **Dual-write** → **Backfill** → **Shadow-read** → **Cutover** → Decommission. Invariant: *exactly one authoritative store at all times; flip authority only after proving a match.*

**Key rules:**
- Dual-write: OLD authoritative, NEW best-effort + idempotent; mirror deletes.
- Backfill: keyset pagination (never OFFSET), version-guarded upsert, throttle off live load, read replicas.
- Shadow-read: async/off hot path; re-check mismatches; gate cutover on **0 persistent mismatches**.
- Cutover: ramp reads 1→100%; flip writes; keep reverse sync so **rollback = flag flip**.
- Verify with **checksum/Merkle**, not row counts.
- Avoid **2PC** on the hot path; accept temporary inconsistency + reconcile.

**Numbers/defaults to know (verify per version):** gh-ost `--chunk-size` 1000; Cassandra `num_tokens` 256 (old) → 16 (new); Kafka order guaranteed per-partition → partition by PK; CDC lag target ms–seconds.

**Reshard ease by scheme:** range = easy split, hot tail; hash mod N = catastrophic; consistent hash + vnodes = move K/N; logical shards = move slices (best).

**Pick:** stop-the-world if it fits the window; online if not. Expand-in-place if design supports slice moves; new cluster for shard-key/engine change. Dual-write for same-engine code-controlled writes; CDC for engine change / many write paths.

**Failure quick-map:** lag spike → throttle/scale/repartition; stale clobber → version-guard + reconcile; zombie rows → fix delete capture; out-of-order → partition by PK; read-after-write → sticky/atomic cutover; resume gap → commit-after-apply.

### 12.2 Self-test (no answers — recall actively)

1. Explain, end to end, how you'd migrate a 3 TB single-instance MySQL table to a 16-shard cluster with zero downtime — and at which exact step rollback stops being a flag flip, and why.
2. A row is updated by a user *while* your backfill is mid-flight over that row. Describe every way this can corrupt the target and the precise mechanism that prevents it.
3. Why does `hash(key) mod N` make resharding catastrophic, and contrast two distinct designs that fix it (with their tradeoffs)?
4. You're migrating MySQL → a different engine with writes arriving through five different services and a nightly batch job. Argue for CDC over in-process dual-write, and explain how the snapshot-to-stream handoff avoids missing or double-applying changes.
5. After flipping the shard key and cutting over, p99 latency triples. Diagnose the most likely cause and give two concrete remedies.
6. Design the verification strategy for a 10-billion-row table you must reconcile nightly without scanning every row each time.
7. Your CDC consumer restarts and three updates to one row are now applied out of order in the target. Name the root cause and the configuration that prevents it.

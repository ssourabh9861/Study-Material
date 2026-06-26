# Replication & Quorums

> **Concept area:** Distributed Systems Foundations
> **Subtopic:** Replication & Quorums
> **Reader profile:** A senior Java/JVM backend developer who wants to fully master this subtopic — from first principles to deep internals — well enough to design with it, operate it in production, debug it, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### What it is

**Replication** is the practice of keeping copies of the same data on multiple machines (called *replicas* or *nodes*), connected over a network. **Quorums** are a voting rule layered on top of replication: you require some minimum number of replicas to acknowledge an operation before you consider it successful. Together they answer two of the hardest questions in distributed systems:

1. *How do we not lose data when a machine dies?* (durability / availability)
2. *How do we make sure readers see correct, recent data even though there are many copies that can disagree?* (consistency)

### The problem it solves

A single database server is a *single point of failure*. If it crashes, the data is unreachable (an availability problem) and possibly lost (a durability problem). It is also a single point of *capacity*: one machine can only serve so many reads per second and store so many bytes. Replication addresses all three:

- **Availability / fault tolerance** — if one replica is down, others can serve the data.
- **Read scalability** — spread read traffic across many replicas.
- **Latency / locality** — put a replica near the user (e.g., a copy in Mumbai for Indian users, a copy in Virginia for US users) so reads don't cross an ocean.
- **Durability** — more independent copies means lower probability that all are lost simultaneously.

The cost you pay: the moment you have more than one copy, those copies can *diverge*. A write hits replica A but hasn't yet reached replica B. A reader hitting B sees stale data. Two writers update two different replicas concurrently and now you have a *conflict*. Almost everything in this chapter is about managing that divergence.

### When you reach for it

You reach for replication essentially **always** in a serious production datastore — it is table stakes for durability and availability. You reach for **quorum-tuned, leaderless replication** specifically when you need high write availability across failures and multiple data centers and are willing to handle eventual consistency (think Cassandra, DynamoDB). You reach for **single-leader replication** (the default in PostgreSQL, MySQL, MongoDB) when you want strong consistency and simple semantics and can tolerate a brief failover window.

### The one-paragraph mental model

> Picture **N identical copies** of your data. Every write must be *propagated* to all of them eventually, and every read must *choose* which copies to consult. A **leader-based** system funnels all writes through one copy that then streams changes to the rest — simple and consistent, but the leader is a bottleneck and a failure point. A **leaderless** system lets the client (or a coordinator) write to several copies at once and read from several at once; if it writes to **W** copies and reads from **R** copies and arranges **R + W > N**, then any read set and any write set must *overlap in at least one copy*, guaranteeing the reader sees at least one up-to-date value. The whole field is the study of how to keep N copies agreeing *enough*, *fast enough*, while machines and networks fail in every imaginable way.

---

## 2. Foundations from first principles

Let's build the vocabulary from zero. Every term a newcomer might not know is defined inline the first time it appears.

### 2.1 Replica, node, and the data unit

- **Node** — a single machine (physical or virtual) participating in the system. Often used interchangeably with **server** or **instance**.
- **Replica** — a node that holds a copy of (some of) the data. "We have 3 replicas of the orders table" means 3 nodes each hold the orders data.
- **Replication factor (RF or N)** — how many copies of each piece of data exist. RF=3 is the industry-standard sweet spot (explained later).
- **Partition / shard** — a horizontal slice of the dataset. Big datasets are split into partitions, and *each partition is independently replicated*. Replication (copies of the same data) and partitioning (splitting different data across machines) are **orthogonal** concerns that are almost always combined. This chapter is about replication; just remember that in a real cluster, "the replicas of partition P" is the relevant set, not "all nodes."

### 2.2 The fundamental tension: consistency vs. availability

Two foundational results frame everything:

**CAP theorem.** *CAP* stands for **Consistency, Availability, Partition tolerance.** A **network partition** is when the network splits so that some nodes cannot talk to others (messages are dropped or arbitrarily delayed). The theorem states: when a partition happens, a distributed system must choose between:

- **C (linearizability / strong consistency)** — every read sees the most recent write, as if there were a single copy. (Note: CAP's "C" is *linearizability* specifically, a strong guarantee — not the looser "C" of ACID.)
- **A (availability)** — every request to a non-failing node gets a non-error response, even if it might be stale.

You cannot have both *during a partition*. You can have both when the network is healthy. So CAP is really: **"when partitioned, pick CP or AP."** A **CP** system (e.g., a system using a strict quorum or a consensus protocol) refuses some requests to stay consistent; an **AP** system (e.g., classic Dynamo-style) keeps serving but may return stale or conflicting data.

> **Beginner note — linearizability.** Linearizability means the system *behaves as if there is exactly one copy of the data and all operations happen one at a time in some order consistent with real time.* If write W finishes before read R begins (in wall-clock time), R must see W's effect (or something newer). It is the strongest single-object consistency model.

**PACELC.** An extension of CAP: *if there is a Partition (P), choose Availability or Consistency (A/C); Else (E), in normal operation, choose Latency or Consistency (L/C).* PACELC captures the *everyday* tradeoff that CAP ignores: even with no partition, waiting for more replicas to acknowledge gives you more consistency but more latency. Cassandra is "PA/EL" (favors availability and latency); a strictly-consistent system is "PC/EC."

### 2.3 Synchronous, asynchronous, and semi-synchronous replication

When a leader (or coordinator) receives a write, it must propagate it. *When does it tell the client "done"?*

- **Synchronous replication** — the leader waits for the replica(s) to confirm they have *durably stored* the write before acking the client. **Pro:** the replica is guaranteed up-to-date, so no data is lost if the leader dies. **Con:** if the replica is slow or down, the write *blocks* — you've coupled your write latency and availability to the replica.
- **Asynchronous replication** — the leader acks the client immediately and ships the change to replicas in the background. **Pro:** fast, and a down replica doesn't block writes. **Con:** if the leader dies before the change is shipped, that write is **lost** (durability gap), and replicas lag behind (the *replication lag* problem).
- **Semi-synchronous replication** — a pragmatic middle ground. Typically **one** replica is synchronous (so there's always at least one up-to-date copy) and the rest are asynchronous. If the sync replica falls behind or dies, the system often *promotes* another async replica to be the sync one. PostgreSQL and MySQL both support this.

> **Beginner note — durable / fsync.** "Durably stored" usually means the data has been written to disk and flushed (an **fsync** system call that forces the OS to push buffers to physical storage), so it survives a power loss. A replica that has merely *received* a change in memory but not fsynced it can still lose it on a crash. Different systems define "ack" at different points (received vs. written to WAL vs. fsynced vs. applied) — this matters enormously for durability guarantees.

### 2.4 The three replication topologies

This is the central taxonomy. There are exactly three ways to decide *who is allowed to accept writes.*

#### (a) Single-leader (a.k.a. master-slave, primary-replica, active-passive)

One designated node — the **leader** (primary/master) — accepts all writes. It records each change and streams it to **followers** (replicas/secondaries/standbys), which apply the changes to stay in sync. Reads can go to the leader (always fresh) or to followers (possibly stale, but scalable).

- **Pros:** simple, no write conflicts (one writer), easy to reason about, strong consistency available.
- **Cons:** the leader is a write bottleneck and a single point of failure; failover (promoting a follower when the leader dies) is tricky and risky.
- **Used by:** PostgreSQL, MySQL, MongoDB (replica sets), Kafka (per-partition leader), most relational DBs by default.

#### (b) Multi-leader (a.k.a. master-master, active-active)

Multiple nodes accept writes, each acting as a leader and also as a follower of the others. Commonly used to have **one leader per data center** so writes are local and fast in each region.

- **Pros:** writes can happen locally in each region (low latency), tolerates whole-datacenter failures, works offline (e.g., calendar apps on multiple devices).
- **Cons:** the killer problem — **write conflicts.** Two leaders can accept conflicting writes to the same row concurrently, and you must *resolve* the conflict. This is genuinely hard and a frequent source of subtle bugs.
- **Used by:** multi-region MySQL/Postgres setups (e.g., BDR, Tungsten), CouchDB, and collaborative-editing infrastructure.

#### (c) Leaderless (a.k.a. Dynamo-style)

No node is special. The client (or a stateless coordinator on its behalf) sends each write to **several** replicas directly and reads from **several** replicas directly. Quorum math (next section) ensures correctness. Conflicts are detected and resolved at read time or by background processes.

- **Pros:** excellent availability and write throughput, no failover (any node can serve), tunable consistency, great for multi-DC.
- **Cons:** eventual consistency by default, conflict resolution required, quorum math is subtle, and "reading your own writes" needs care.
- **Used by:** Amazon Dynamo (the 2007 paper), DynamoDB, Apache Cassandra, ScyllaDB, Riak, Voldemort.

### 2.5 Quorum math — the heart of leaderless correctness

This is the most important piece of arithmetic in the chapter.

Let:
- **N** = replication factor (number of replicas per piece of data).
- **W** = the number of replicas that must acknowledge a **write** before it's considered successful.
- **R** = the number of replicas that must respond to a **read** before it's considered successful.

The **quorum condition** is:

> **R + W > N**

Why does this work? If a write succeeds on W replicas and a read consults R replicas, and `R + W > N`, then by the **pigeonhole principle** the read set and the write set *must share at least one replica.* That overlapping replica has the latest write, so the read is guaranteed to *see* it (you then pick the value with the newest version/timestamp).

> **Beginner note — pigeonhole principle.** If you place more than N items into N boxes, at least one box has two items. Here: W "written" replicas + R "read" replicas = more than N total slots, but there are only N replicas, so at least one replica is in *both* the write set and the read set.

Common configurations with **N=3**:

| W | R | R+W | Meaning | Property |
|---|---|-----|---------|----------|
| 2 | 2 | 4 > 3 | Quorum write & read | Strong-ish consistency, tolerates 1 node down for both reads and writes |
| 3 | 1 | 4 > 3 | Write all, read one | Fast reads, slow/fragile writes (any node down blocks writes) |
| 1 | 3 | 4 > 3 | Write one, read all | Fast writes, slow/fragile reads |
| 1 | 1 | 2 < 3 | **Not** a quorum | Fastest, lowest consistency — reads may be stale |

The most common production choice is **N=3, W=2, R=2** ("quorum"). It tolerates **one** node failure while preserving the overlap guarantee, and it balances read and write latency.

**Crucial caveat:** `R + W > N` guarantees overlap, but it is *not* a guarantee of linearizability in the strict sense. Edge cases break it:

- If a write fails on some replicas but succeeds on fewer than W, different reads may or may not see it (an in-doubt write).
- **Sloppy quorums** (below) write to *substitute* nodes, breaking the overlap with the "home" replicas.
- Concurrent writes need conflict resolution; the quorum doesn't tell you *which* concurrent value wins.
- Without proper read-repair/anti-entropy, a stale replica that's in the read set can still cause you to see an old value if version comparison isn't done correctly.

So quorums give *strong eventual* behavior and *probabilistic* freshness — for true linearizability you need consensus protocols (Paxos/Raft) or "lightweight transactions" (Cassandra LWT) layered on top.

### 2.6 Sloppy quorums and hinted handoff

In a strict quorum, the W and R replicas must be among the **N "home" nodes** that *own* that key (determined by the partitioning scheme, usually consistent hashing). But what if a partition cuts you off from those home nodes?

- **Sloppy quorum** — when some home nodes are unreachable, the coordinator accepts the write on *any* W available nodes in the cluster, even ones outside the home set. This keeps writes available during partitions (the AP choice). The downside: a read from the home nodes won't find the value, because it landed elsewhere.
- **Hinted handoff** — the substitute node that accepted the write stores it with a **hint**: "this really belongs to node X, deliver it when X comes back." When the home node recovers, the substitute *hands off* the data to it and deletes the hint. This is how the data eventually reaches its proper home.

> **Beginner note — consistent hashing.** A technique to map keys to nodes such that adding/removing a node only reshuffles a small fraction of keys. Nodes and keys are hashed onto a ring (0 … 2^m); a key is owned by the next N nodes clockwise from its hash position. Dynamo, Cassandra, and Riak all use this (Cassandra historically with *vnodes* — many small token ranges per physical node — for smoother balancing).

### 2.7 Anti-entropy: read-repair and Merkle trees

Asynchronous propagation and sloppy quorums leave replicas inconsistent. Two background mechanisms heal them:

- **Read-repair** — when a read consults R replicas and notices one returned a stale value, the coordinator writes the fresh value back to the stale replica *as part of serving the read.* Cheap, opportunistic, but only repairs data that is actually read.
- **Anti-entropy (Merkle tree sync)** — a background process that compares two replicas' entire datasets and copies over differences. Comparing every key is too expensive, so each replica builds a **Merkle tree**: a tree of hashes where each leaf hashes a range of keys and each parent hashes its children. Two replicas compare root hashes; if equal, they're identical and stop. If different, they descend only into the subtrees whose hashes differ, exchanging *O(log n)* hashes to localize the divergent ranges, then copy just those. Cassandra's `nodetool repair` does exactly this.

> **Beginner note — hash / Merkle tree.** A *hash function* maps data to a fixed-size fingerprint; identical data yields identical hashes, and any change yields a wildly different hash. A *Merkle tree* (a.k.a. hash tree) stacks hashes so you can verify and diff large datasets by comparing small fingerprints, only drilling into the parts that differ — the same idea Git and BitTorrent use.

### 2.8 Replication lag and the anomalies it creates

With asynchronous replication, followers are behind the leader by some **replication lag** (milliseconds normally, but seconds or minutes under load or failure). Reading from a lagging follower causes user-visible anomalies. The three classic ones, and their fixes:

1. **Read-your-own-writes (read-after-write) violation** — you post a comment, the write goes to the leader, then your page reload reads from a lagging follower that doesn't have your comment yet — it *disappears.* **Fix:** *read-your-writes consistency* — route reads for data the user might have modified to the leader (or to a follower known to be caught up), e.g., for ~1 second after their write, or track the write's position and wait.

2. **Monotonic reads violation** — you refresh twice; the first read hits an up-to-date follower (you see the comment), the second hits a lagging follower (the comment vanishes) — time appears to **move backward.** **Fix:** *monotonic reads* — ensure each user always reads from the *same* follower (e.g., hash the user ID to a replica), so they never see older data than they've already seen.

3. **Consistent-prefix-reads violation** — observer sees an answer before the question it answers (causality violated) because different partitions replicate at different speeds. **Fix:** *consistent prefix reads* — ensure causally related writes are read in order, typically by keeping causally-related data in the same partition or tracking causal dependencies.

These three (read-your-writes, monotonic reads, consistent prefix) are the practical "session guarantees" that make eventual consistency tolerable for users.

### 2.9 Conflict resolution

When two writes to the same key happen "concurrently" (neither is aware of the other — formally, neither *happened-before* the other), you have a **conflict** and must decide the winner. Strategies:

- **Last-Writer-Wins (LWW)** — attach a timestamp to each write; the highest timestamp wins. Simple, used by Cassandra. **Danger:** it silently *discards* the loser's write (lost update), and clock skew between nodes can make a "later" write lose to an "earlier" one. Acceptable only when lost writes are tolerable.
- **Version vectors / vector clocks** — track causality so you can *detect* whether two writes are concurrent or one supersedes the other; concurrent writes are surfaced as **siblings** for the application (or a CRDT) to merge. Used by Dynamo and Riak.
- **CRDTs (Conflict-free Replicated Data Types)** — data structures (counters, sets, maps, registers) mathematically designed so that concurrent updates *always merge deterministically* without conflicts, regardless of order. Used by Riak data types, Redis (Active-Active), Azure Cosmos DB, and collaborative editors (via the related OT/CRDT families).
- **Application-defined merge** — hand the siblings to application code (e.g., union two shopping carts, the famous Dynamo example).

> **Beginner note — happened-before / concurrency.** Lamport's *happened-before* relation (→): event A happened-before B if A could have *caused* B (same node in order, or a message sent then received). If neither A→B nor B→A, they are **concurrent** — and that's exactly when you have a conflict to resolve.

> **Beginner note — vector clock.** An array of counters, one per node. Each node increments its own slot on each event and includes the whole vector in messages; on receipt, you take the element-wise max. By comparing two vectors you can tell if one *dominates* the other (descended from it) or if they're concurrent (each has some slot larger). It's how you detect causality without synchronized clocks.

---

## 3. How it works internally

This is the heart of the document. We trace the actual control and data flow for each topology, step by step.

### 3.1 Single-leader replication: end-to-end internal workflow

**Setup.** One leader, two followers (N=3). Followers connect to the leader and request a stream of changes.

**The replication log.** The leader doesn't ship raw SQL or full pages by default; it ships a **replication log** — an ordered record of every change. Four log formats exist:

| Format | What it ships | Pros | Cons | Example |
|--------|---------------|------|------|---------|
| **Statement-based** | The literal SQL statement | Compact | Non-deterministic functions (`NOW()`, `RAND()`), triggers, auto-increment can diverge | MySQL `STATEMENT` binlog (legacy) |
| **Write-ahead log (WAL) shipping** | The byte-level changes to storage (same WAL used for crash recovery) | Exact, simple | Tightly coupled to storage engine & version (can't replicate across versions) | PostgreSQL physical/streaming replication |
| **Logical (row-based) log** | Logical description of changed rows (insert/update/delete with values) | Decoupled from storage, cross-version, selective | More verbose | MySQL `ROW` binlog; PostgreSQL logical replication (`pgoutput`) |
| **Trigger-based** | App-level capture via triggers | Flexible, selective | Higher overhead, error-prone | Bucardo, Londiste |

**Write path, step by step (PostgreSQL streaming replication as the concrete model):**

1. Client sends `UPDATE accounts SET balance = balance - 100 WHERE id = 42;` to the **leader (primary)**.
2. The leader's executor modifies the in-memory page (in the **shared buffers**) and appends a record describing the change to the **WAL (write-ahead log)** in memory and disk. *WAL-before-data* is the cardinal rule: the log entry is durable before the change is considered committed.

   > **Beginner note — WAL (write-ahead log).** A sequential, append-only log of every change, written *before* the change is applied to the main data files. On crash, the DB replays the WAL to recover. It's the source of truth for both durability and replication.

3. On `COMMIT`, the leader fsyncs the WAL up to this commit's position (the **LSN**, *Log Sequence Number* — a monotonically increasing byte offset into the WAL).
4. **WAL sender** processes on the leader stream new WAL records to each follower's **WAL receiver** over a TCP connection.
5. Each follower writes the received WAL to its own disk and **replays (applies)** it, advancing through three reported positions: `write_lsn` (received & written), `flush_lsn` (fsynced), `replay_lsn` (applied & visible to queries).
6. **Acknowledgement / synchrony decision:**
   - *Asynchronous:* the leader already acked the client at step 3 — it doesn't wait for followers.
   - *Synchronous:* the leader's COMMIT *blocks* at step 3 until at least one (or a quorum of) synchronous followers report reaching the required position (`synchronous_commit` setting controls the exact point: `remote_write`, `remote_apply`, etc.).
7. Followers serve read-only queries from their replayed state (with lag = leader LSN − follower `replay_lsn`).

**Failover — the dangerous part, step by step:**

1. **Detect** the leader is dead — usually a timeout (heartbeats missed for N seconds). *Problem: you can't distinguish "dead" from "slow/partitioned."*
2. **Choose a new leader** — pick the follower with the most recent data (highest replay LSN) to minimize loss. Done by an external orchestrator (Patroni, repmgr, Orchestrator, MongoDB's built-in Raft-based election).
3. **Reconfigure** — point clients and other followers at the new leader.

**Failover failure modes (memorize these):**

- **Lost writes:** with async replication, writes the old leader acked but didn't ship are gone when a behind follower is promoted. (GitHub's 2012 outage: a promoted MySQL replica lacked recent writes.)
- **Split brain:** the old leader comes back (it was only partitioned, not dead) and *both* nodes think they're leader, accepting conflicting writes. Mitigated by **fencing / STONITH** ("Shoot The Other Node In The Head" — forcibly kill or isolate the old leader).
- **Cascade / herd:** auto-failover triggered by a transient blip causes a worse outage; conservative timeouts trade faster recovery for fewer false failovers.

### 3.2 Multi-leader replication: internal workflow

**Topology.** Each leader accepts writes locally and asynchronously replicates to the other leaders. Common topologies: **all-to-all** (every leader to every other — robust but more messages), **circular/ring** (each forwards to the next — fewer connections but a single node failure breaks the ring), and **star** (one central node).

**Write & propagation flow:**

1. Client writes to its local leader (e.g., the EU data center).
2. The local leader commits and tags the change with origin metadata and a version (timestamp or vector clock).
3. The change is asynchronously shipped to the other leaders, which apply it.
4. **Loop prevention:** each write carries the set of nodes it has passed through, so a node won't re-forward a change it already saw (critical in ring/all-to-all topologies).

**Conflict detection & resolution flow:** Two leaders update the same row before they exchange updates → on exchange, each detects a conflict (versions are concurrent) → resolution runs (LWW, merge function, or surface siblings). The conflict can be detected *synchronously* (rare, reduces the multi-leader benefit) or *asynchronously* at apply time (common).

### 3.3 Leaderless (Dynamo-style) replication: internal workflow

This is the richest internal model. Concrete reference: **Cassandra / DynamoDB**.

**Placement.** A key is hashed (consistent hashing / token ring) to find its N replicas (the "home" replica set).

**Write path, step by step:**

1. Client sends the write to any node, which becomes the **coordinator** for this request. (Token-aware drivers pick a coordinator that is itself a replica to save a hop.)
2. The coordinator looks up the N replicas for the key and sends the write to **all reachable replicas** (it sends to all of them, but only *waits* for W acks — sending to more than W gives a head start for repair and durability).
3. Each replica writes the data **locally**: append to its **commit log** (for durability), then update the in-memory **memtable**; later memtables flush to immutable **SSTables** on disk (this is the **LSM-tree** storage model).

   > **Beginner note — LSM-tree, memtable, SSTable, commit log.** An *LSM-tree* (Log-Structured Merge tree) optimizes writes by buffering them in memory (the *memtable*) and a sequential on-disk *commit log* (for crash recovery), then periodically flushing the sorted memtable to an immutable on-disk file (*SSTable* = Sorted String Table). Background *compaction* merges SSTables. Cassandra, RocksDB, LevelDB, and ScyllaDB all use LSM-trees. Reads may have to check several SSTables (mitigated by *Bloom filters*).

4. The write carries a **timestamp** (Cassandra) or **version vector** (Dynamo/Riak) for later conflict resolution.
5. When **W** replicas ack, the coordinator returns success to the client. (If the home replicas aren't reachable and *sloppy quorum* is enabled — DynamoDB; Cassandra does this within a datacenter via hinted handoff — the write lands on substitutes with **hints**.)
6. Replicas that *didn't* ack in time (slow/down) either get the write asynchronously, via **hinted handoff** when they recover, or via later **read-repair / anti-entropy**.

**Read path, step by step:**

1. Client sends the read to a coordinator.
2. Coordinator sends read requests to enough of the N replicas. Cassandra optimization: it sends a **full data read** to the fastest/closest replica and **digest reads** (just a hash of the value) to the others, to save bandwidth. It waits for **R** responses.
3. Coordinator compares versions/timestamps across the R responses:
   - If they agree → return the value.
   - If they disagree → pick the newest (LWW) or surface siblings (vector clocks), return it, and trigger **read-repair**: write the freshest value back to the stale replicas.
4. **Background anti-entropy** independently runs Merkle-tree repair to fix replicas that are never read.

**Tunable consistency levels (Cassandra) — the actual knobs:** `ONE`, `TWO`, `THREE`, `QUORUM` (majority of N), `LOCAL_QUORUM` (majority within the local datacenter — the multi-DC workhorse), `EACH_QUORUM` (quorum in *every* DC), `ALL`, `ANY` (write only; satisfied even by a hint), `LOCAL_ONE`, `SERIAL`/`LOCAL_SERIAL` (for lightweight transactions). You set these *per query*, which is the defining flexibility of leaderless systems.

**Lightweight transactions (LWT) — when you need linearizability:** Cassandra layers a **Paxos** consensus round over the replicas for `IF NOT EXISTS` / compare-and-set operations. This gives true linearizable semantics at the cost of ~4 round trips. DynamoDB offers conditional writes and transactions for the same purpose.

> **Beginner note — Paxos / Raft (consensus).** *Consensus protocols* let a group of nodes agree on a single value even with failures, providing linearizable operations and safe leader election. *Paxos* (Lamport) is the foundational one; *Raft* is a more understandable, widely-implemented alternative (used by etcd, Consul, CockroachDB, MongoDB elections, TiKV). Quorums are the building block (a majority must agree), but consensus adds *ordering and agreement*, which plain read/write quorums don't provide.

### 3.4 State machine: a replica's lifecycle (leaderless)

```
                 join cluster
   [NEW] ─────────────────────────► [BOOTSTRAPPING]
                                          │ stream data for its token ranges
                                          ▼
   [UP/NORMAL] ◄──── caught up ──── [JOINING/STREAMING]
      │  │                                 ▲
      │  │ miss heartbeats                 │ replay hints + repair
      │  ▼                                 │
      │ [DOWN] ──── recover ──────────────►┘   (gossip marks UP)
      │
      │ operator decommission
      ▼
   [LEAVING] ── stream data away ──► [REMOVED]
```

Cassandra tracks this via **gossip** — a peer-to-peer protocol where nodes periodically exchange state ("I think node X is UP, here's its load and version") with a few random peers, so cluster membership and health converge without a central registry.

> **Beginner note — gossip protocol.** An epidemic-style protocol: each node periodically picks a few random peers and exchanges what it knows; information spreads exponentially fast (like a rumor) and the cluster reaches a consistent view of membership without any coordinator. Used by Cassandra, Dynamo, Consul (Serf), Riak.

---

## 4. The complete toolkit

### 4.1 PostgreSQL replication toolkit

**Key configuration parameters (`postgresql.conf`):**

| Parameter | Purpose | Typical / default |
|-----------|---------|-------------------|
| `wal_level` | Detail in WAL; `replica` for physical, `logical` for logical replication | `replica` (default since PG10) |
| `max_wal_senders` | Max concurrent WAL sender processes (one per standby + base backups) | `10` (default since PG10) |
| `wal_keep_size` | WAL retained for standbys that fall behind (replaces old `wal_keep_segments`) | `0` (use replication slots instead) |
| `synchronous_standby_names` | Which standbys are synchronous; supports `ANY 2 (s1,s2,s3)` quorum syntax | empty (= all async) |
| `synchronous_commit` | When COMMIT returns: `off`, `local`, `remote_write`, `on`, `remote_apply` | `on` |
| `hot_standby` | Allow read queries on standby | `on` |
| `max_standby_streaming_delay` | How long standby queries can delay WAL replay before being canceled | `30s` |
| `primary_conninfo` | (standby) how to connect to primary | — |
| `recovery_min_apply_delay` | Intentionally delay replay (delayed standby for protection against bad writes) | `0` |

**Replication slots** — a server-side bookmark (`pg_create_physical_replication_slot`, `pg_create_logical_replication_slot`) ensuring the primary retains WAL a standby still needs, so a temporarily-offline standby can catch up. **Caution:** a slot for a dead standby retains WAL forever and can fill the disk — monitor `pg_replication_slots`.

**Key views & tools:**

| Tool / view | Purpose |
|-------------|---------|
| `pg_stat_replication` (on primary) | Per-standby state, `sent_lsn`, `write_lsn`, `flush_lsn`, `replay_lsn`, lag columns |
| `pg_stat_wal_receiver` (on standby) | Standby's receiver status |
| `pg_replication_slots` | Slot state and retained WAL |
| `pg_current_wal_lsn()`, `pg_last_wal_replay_lsn()` | Compute lag in bytes/time |
| `pg_basebackup` | Take a base backup to seed a new standby |
| `pg_rewind` | Resync a former primary after failover without a full rebuild |
| **Patroni** | HA template using etcd/Consul/ZooKeeper for automated failover (CP via DCS) |
| **repmgr**, **pgpool-II**, **PgBouncer** | Failover management, pooling, routing |

### 4.2 Cassandra / leaderless toolkit

| Knob / command | Purpose | Default / note |
|----------------|---------|----------------|
| `replication_factor` (per keyspace) | N | choose 3 commonly; `NetworkTopologyStrategy` sets RF per DC |
| Consistency Level (per query) | R/W requirement | `LOCAL_QUORUM` is the common production choice |
| `nodetool repair` | Merkle-tree anti-entropy repair | run regularly within `gc_grace_seconds` |
| `gc_grace_seconds` | How long **tombstones** (deletion markers) live before purge | `864000` (10 days) — repair must complete within this window or deletes can resurrect |
| `hinted_handoff_enabled`, `max_hint_window_in_ms` | Hinted handoff and how long hints are kept | enabled; `10800000` (3h) default |
| `read_repair_chance` / table `read_repair` | Probability/scope of read-repair | modern Cassandra uses `BLOCKING`/`NONE` table option |
| `nodetool status`, `gossipinfo`, `tpstats`, `netstats` | Cluster/health/thread-pool/streaming diagnostics | — |
| `speculative_retry` | Send extra read to another replica if one is slow (tail-latency control) | `99PERCENTILE` |
| `nodetool setstreamthroughput`, `compactionthroughput` | Throttle repair/compaction I/O | — |

> **Beginner note — tombstone.** In an LSM/append-only store you can't delete in place, so a delete writes a *tombstone* marker that shadows the old value until compaction removes both. Tombstones must outlive the repair cycle (`gc_grace_seconds`) or a stale replica could resurrect deleted data.

### 4.3 DynamoDB (managed, AP) toolkit

| Feature | Purpose |
|---------|---------|
| `ConsistentRead=true` | Strongly consistent read (reads from leader replica) vs. default eventually-consistent read |
| **Global Tables** | Multi-region, multi-leader (active-active) replication with LWW conflict resolution |
| Conditional writes / `ConditionExpression` | Compare-and-set for safe concurrent updates |
| `TransactWriteItems` / `TransactGetItems` | ACID transactions across items |
| Streams + `versionAttribute` (optimistic locking in SDK mapper) | CDC and optimistic concurrency |

### 4.4 MySQL replication toolkit

| Item | Purpose |
|------|---------|
| `binlog_format` = `ROW`/`STATEMENT`/`MIXED` | Logical vs. statement replication; `ROW` recommended |
| **GTID** (Global Transaction ID) | Uniquely identify transactions cluster-wide; simplifies failover |
| `rpl_semi_sync_master_enabled` | Enable semi-synchronous replication |
| **Group Replication / InnoDB Cluster** | Paxos-based (Group Communication System) multi-primary or single-primary with automatic failover |
| Orchestrator | Topology management & automated failover |

### 4.5 Kafka (a replicated log — different model worth knowing)

Per-partition single leader; followers are **ISR** (*In-Sync Replicas*). `acks=all` + `min.insync.replicas=2` gives a quorum-like durability guarantee. `replication.factor=3` is standard. The controller (Raft-based **KRaft** in modern Kafka, replacing ZooKeeper) handles leader election.

> **Beginner note — ZooKeeper / KRaft.** *ZooKeeper* is a separate, consensus-backed (ZAB protocol) coordination service historically used to store cluster metadata and elect leaders for systems like Kafka and HBase. Kafka has since moved this to an internal Raft implementation called **KRaft**, removing the ZooKeeper dependency.

---

## 5. Code examples by use case

### 5.1 Read-your-writes routing (Java service in front of a primary + replicas)

This solves the read-after-write anomaly by routing reads to the primary briefly after a user writes.

```java
// A DataSource router that sends reads to a replica unless the current user
// recently wrote, in which case it routes to the primary to guarantee
// read-your-own-writes consistency.
public class ReadYourWritesRouter {

    private final DataSource primary;          // single leader
    private final List<DataSource> replicas;   // async followers
    // Tracks, per user, the wall-clock time until which we must read from primary.
    private final Cache<String, Long> stickyUntil =
        Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.SECONDS).build();

    private static final long STICKY_WINDOW_MS = 1_000; // > observed replication lag

    /** Call after every write on behalf of a user. */
    public void recordWrite(String userId) {
        stickyUntil.put(userId, System.currentTimeMillis() + STICKY_WINDOW_MS);
    }

    /** Pick the connection for a READ. */
    public DataSource readDataSource(String userId) {
        Long until = stickyUntil.getIfPresent(userId);
        if (until != null && System.currentTimeMillis() < until) {
            return primary;                    // must see own recent write
        }
        // Monotonic reads: hash the user to the SAME replica each time so the
        // user never observes time going backwards across replicas.
        int idx = Math.floorMod(userId.hashCode(), replicas.size());
        return replicas.get(idx);
    }
}
```

The two non-obvious points: the **sticky window** must exceed your *observed p99 replication lag* (measure it — don't guess), and the **hash-to-replica** gives monotonic reads cheaply.

### 5.2 LSN-aware "wait for replica to catch up" (PostgreSQL, precise read-your-writes)

The time-window approach above is heuristic. A precise version captures the write's LSN and waits for the chosen replica to replay past it.

```java
public class LsnConsistentReader {

    /** After a write on the primary, capture the commit LSN. */
    public String currentPrimaryLsn(Connection primary) throws SQLException {
        try (var st = primary.createStatement();
             var rs = st.executeQuery("SELECT pg_current_wal_lsn()::text")) {
            rs.next();
            return rs.getString(1);             // e.g. "0/3A1F2C0"
        }
    }

    /** Before reading on a replica, ensure it has replayed past the write's LSN. */
    public boolean replicaCaughtUp(Connection replica, String targetLsn)
            throws SQLException {
        try (var ps = replica.prepareStatement(
                // pg_lsn comparison: replayed >= target  ?
                "SELECT pg_last_wal_replay_lsn() >= ?::pg_lsn")) {
            ps.setString(1, targetLsn);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
    // Caller pattern: capture LSN on write; on the user's next read, poll
    // replicaCaughtUp() with a short timeout, else fall back to the primary.
}
```

### 5.3 Cassandra: per-query tunable consistency in Java (DataStax driver v4)

```java
// Demonstrates choosing consistency per operation: strong-ish for money,
// fast for analytics, and an LWT (Paxos) for a uniqueness guarantee.
import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.*;
import static com.datastax.oss.driver.api.core.ConsistencyLevel.*;

public class TunableConsistencyDemo {

    void run(CqlSession session) {
        // 1) Critical write: LOCAL_QUORUM => majority of replicas in local DC.
        //    With N=3 per DC, that's 2 acks. R+W>N within the DC.
        SimpleStatement deposit = SimpleStatement.builder(
                "UPDATE ledger SET balance = balance + 100 WHERE acct = ?")
            .addPositionalValue("acct-42")
            .setConsistencyLevel(LOCAL_QUORUM)
            .build();
        session.execute(deposit);

        // 2) Critical read with the matching LOCAL_QUORUM => overlap guarantee.
        SimpleStatement balance = SimpleStatement.builder(
                "SELECT balance FROM ledger WHERE acct = ?")
            .addPositionalValue("acct-42")
            .setConsistencyLevel(LOCAL_QUORUM)
            .build();
        long bal = session.execute(balance).one().getLong("balance");

        // 3) Cheap, stale-tolerant analytics read: ONE replica only.
        session.execute(SimpleStatement.builder("SELECT count(*) FROM events")
            .setConsistencyLevel(LOCAL_ONE).build());

        // 4) Linearizable insert-if-absent via Lightweight Transaction (Paxos).
        //    SERIAL consistency is implied; this is much slower (~4 round trips).
        ResultSet rs = session.execute(SimpleStatement.builder(
                "INSERT INTO users (email, id) VALUES (?, ?) IF NOT EXISTS")
            .addPositionalValues("a@b.com", java.util.UUID.randomUUID())
            .setConsistencyLevel(LOCAL_QUORUM)        // for the read part
            .setSerialConsistencyLevel(LOCAL_SERIAL)  // for the Paxos part
            .build());
        boolean applied = rs.one().getBoolean("[applied]"); // false if email taken
    }
}
```

### 5.4 Optimistic concurrency (version/CAS) to avoid lost updates under replication

LWW silently loses concurrent writes; optimistic locking makes the conflict explicit.

```java
// DynamoDB optimistic locking with a version attribute. The write only
// succeeds if the version we read still matches => detects concurrent update.
import software.amazon.awssdk.services.dynamodb.*;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Map;

public class OptimisticUpdate {
    void incrementCounter(DynamoDbClient db, String id, long expectedVersion) {
        try {
            db.updateItem(UpdateItemRequest.builder()
                .tableName("Counters")
                .key(Map.of("id", AttributeValue.fromS(id)))
                .updateExpression("SET cnt = cnt + :one, version = :next")
                // Guard: apply ONLY if stored version equals what we read.
                .conditionExpression("version = :expected")
                .expressionAttributeValues(Map.of(
                    ":one",      AttributeValue.fromN("1"),
                    ":next",     AttributeValue.fromN(Long.toString(expectedVersion + 1)),
                    ":expected", AttributeValue.fromN(Long.toString(expectedVersion))))
                .build());
        } catch (ConditionalCheckFailedException e) {
            // Someone else wrote concurrently: re-read and retry (CAS loop).
            throw new ConcurrentModificationException("version conflict, retry");
        }
    }
}
```

### 5.5 A grow-only counter CRDT (conflict-free merge by construction)

CRDTs sidestep conflict resolution: merge is associative, commutative, idempotent.

```java
// A G-Counter (grow-only counter) CRDT. Each node owns one slot; the value
// is the sum of all slots; merge takes the element-wise MAX. Concurrent
// increments on different replicas NEVER conflict and always converge.
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class GCounter {
    private final String nodeId;
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    public GCounter(String nodeId) { this.nodeId = nodeId; }

    /** Local increment: only ever touch your own slot. */
    public void increment() {
        counts.merge(nodeId, 1L, Long::sum);
    }

    /** The counter's value is the sum across all nodes' slots. */
    public long value() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Merge another replica's state. Element-wise max => no double counting,
     *  order-independent, idempotent. This is what makes it conflict-free. */
    public void merge(GCounter other) {
        other.counts.forEach((node, v) -> counts.merge(node, v, Math::max));
    }
}
```

Why max-merge is correct: each node's slot only grows, and it is the *authority* for its own slot; taking the max picks the most recent value per slot regardless of message order or duplication — giving **strong eventual consistency** with no manual conflict handling.

### 5.6 Vector clock comparison (detect concurrent vs. causal)

```java
// Compares two vector clocks to classify the relationship between two writes.
import java.util.*;

public final class VectorClock {
    final Map<String, Long> v = new HashMap<>();

    void tick(String node) { v.merge(node, 1L, Long::sum); }

    enum Rel { BEFORE, AFTER, EQUAL, CONCURRENT }

    /** Classify this clock relative to other. CONCURRENT => true conflict. */
    Rel compare(VectorClock other) {
        boolean less = false, greater = false;
        Set<String> nodes = new HashSet<>(v.keySet());
        nodes.addAll(other.v.keySet());
        for (String n : nodes) {
            long a = v.getOrDefault(n, 0L);
            long b = other.v.getOrDefault(n, 0L);
            if (a < b) less = true;
            if (a > b) greater = true;
        }
        if (!less && !greater) return Rel.EQUAL;
        if (less && !greater)  return Rel.BEFORE;   // this happened-before other
        if (greater && !less)  return Rel.AFTER;    // other happened-before this
        return Rel.CONCURRENT;                       // neither dominates => merge!
    }
}
```

### 5.7 Measuring and exporting replication lag (operational)

```java
// Periodically measure replica lag in bytes & seconds and expose it as a
// metric so dashboards/alerts can catch lag spikes before users notice.
public class ReplicaLagMonitor {
    void sample(Connection primary, Connection replica, MeterRegistry metrics)
            throws SQLException {
        try (var p = primary.createStatement();
             var rs = p.executeQuery("SELECT pg_current_wal_lsn()")) {
            rs.next();
            String primaryLsn = rs.getString(1);
            try (var ps = replica.prepareStatement(
                    // bytes the replica is behind the primary
                    "SELECT pg_wal_lsn_diff(?::pg_lsn, pg_last_wal_replay_lsn())")) {
                ps.setString(1, primaryLsn);
                try (var r2 = ps.executeQuery()) {
                    r2.next();
                    long lagBytes = r2.getLong(1);
                    metrics.gauge("pg.replica.lag.bytes", lagBytes);
                }
            }
        }
    }
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Choose N=3.** It's the standard: tolerates one failure with a quorum (W=R=2), bounded cost. N=5 tolerates two failures (W=R=3) at higher latency/cost; use for the most critical data. Even N (e.g., 2, 4) wastes a node for fault tolerance (a "majority" of 4 is 3, same as 5) and risks split votes — prefer odd N for consensus.
- **Synchronous replication couples your latency to the slowest replica.** A single sync replica in another region adds the cross-region RTT to *every* write. Use `LOCAL_QUORUM` / one local sync standby instead of cross-region sync.
- **Tail latency:** use **speculative retries / hedged requests** — if a replica is slow past the p99, fire a duplicate to another replica and take the first response. Cassandra `speculative_retry`, DynamoDB adaptive retries.
- **Read offloading** to replicas scales reads but *not* writes (every replica applies every write). If writes are the bottleneck, you need **partitioning**, not more replicas.

### 6.2 Correctness & concurrency

- **`R + W > N` is necessary but not sufficient** for linearizability (see §2.5). For true linearizable single-key ops, use consensus/LWT; expect ~4× the round trips.
- **Beware LWW + clock skew.** "Last writer" is decided by timestamps; if node clocks differ by more than the time between writes, the *wrong* write wins and you silently lose data. Use NTP discipline, and prefer logical clocks / version vectors / CAS where correctness matters. (See the famous Cassandra/clock-skew lost-write reports.)
- **Quorum reads can still be stale** if a previous quorum *write* didn't fully complete (some replicas missed it). Dynamo-style systems don't roll back partial writes; read-repair eventually heals them, but a read between the partial write and the repair may flip-flop.

### 6.3 Memory & storage

- **LSM stores (Cassandra) amplify writes** via compaction (write amplification) and can have **read amplification** if many SSTables must be checked — tune compaction strategy (`SizeTieredCompactionStrategy` for write-heavy, `LeveledCompactionStrategy` for read-heavy, `TimeWindowCompactionStrategy` for time-series).
- **Replication slots / unacked WAL pin disk** on the primary if a standby is down (PostgreSQL). Always cap and monitor.
- **Tombstones** (Cassandra deletes) consume space and slow reads until compaction; "tombstone overwhelm" is a classic outage — avoid queue-like delete-heavy patterns and large `IN`/range scans over tombstoned data.

### 6.4 Security

- **Encrypt replication traffic in transit** (TLS between primary/standby, inter-node TLS in Cassandra). Replication streams carry your entire dataset.
- **Authenticate replicas** — an attacker who can register as a standby exfiltrates everything. Use replication-specific credentials/certs, restrict by network/firewall.
- **Cross-region replication crosses trust/jurisdiction boundaries** — consider data-residency/regulatory constraints (GDPR data localization) before replicating PII to another region.

### 6.5 Observability (what to monitor)

- **Replication lag** (bytes and seconds) per replica — the single most important metric. Alert on sustained lag.
- **Quorum failures / unavailable exceptions** (Cassandra `UnavailableException`, `WriteTimeoutException`).
- **Hint queue size / hinted-handoff backlog** — large backlog = replicas were down a long time.
- **Pending repairs / anti-entropy progress**, dropped mutations (`nodetool tpstats`), pending compactions.
- **Failover events**, leader election counts, and split-brain detection.

### 6.6 Testing & production hardening

- **Inject partitions and replica failures** in staging (chaos engineering — e.g., Jepsen-style tests, which famously found consistency bugs in many systems). Verify read-your-writes, monotonic reads under lag.
- **Test failover regularly** (game days). An untested failover *will* fail when you need it.
- **Use fencing/STONITH** to prevent split-brain; require a witness/quorum for failover decisions (Patroni + etcd).
- **Backups are not replication.** Replication propagates your mistakes (a bad `DELETE` replicates instantly). Keep independent point-in-time backups; consider a *delayed* standby (`recovery_min_apply_delay`) as a fast undo buffer.

### 6.7 Anti-patterns

- **Reading from async replicas for read-your-writes-sensitive flows** (the disappearing-comment bug).
- **Multi-leader with LWW for data you can't afford to lose** (silent lost updates).
- **N=2 thinking you have "redundancy"** — you can't form a strict quorum that tolerates a failure (majority of 2 is 2; one node down blocks quorum).
- **Cross-region synchronous replication on the hot write path** (latency disaster).
- **Forgetting to run `nodetool repair` within `gc_grace_seconds`** → deleted data resurrects.
- **Auto-failover with aggressive timeouts** → flapping/false failovers cause more downtime than they prevent.

---

## 7. Advanced topics & deep internals

### 7.1 Why even N is bad and the "fast quorum" idea

A majority of N=4 is 3 — identical fault tolerance to N=3 (tolerates 1) but with 33% more cost; N=4 only tolerates 1 failure for *strict* majority while paying for 4 copies. **Flexible Paxos** insight: the quorums for the two phases of Paxos need only intersect *pairwise*, not be majorities — leader-election quorum (Q1) and replication quorum (Q2) just need `Q1 + Q2 > N`. This lets you shrink the steady-state replication quorum (faster commits) at the cost of larger election quorums (rarer event). Used to optimize Raft/Paxos variants.

### 7.2 Read repair subtleties

- **Blocking vs. background read repair.** Blocking read-repair (Cassandra default behavior at `QUORUM`) repairs *before* returning, ensuring monotonicity for that read; background/async read-repair returns first and repairs after (faster, weaker).
- **Read repair can't fix what isn't read.** Cold data needs scheduled anti-entropy repair. Hence `nodetool repair` is mandatory operationally, not optional.

### 7.3 Anti-entropy cost and incremental repair

Full repair rebuilds Merkle trees over the entire dataset — expensive (CPU + I/O + network) and historically a top operational pain. **Incremental repair** marks already-repaired SSTables so subsequent repairs only process new data. Tradeoff: incremental repair has had correctness edge cases across Cassandra versions — **version-specific**, verify for your version (it was notably problematic in some 3.x releases). Tools like Cassandra **Reaper** orchestrate repairs safely (subrange, scheduled).

### 7.4 Hinted handoff limits

Hints are kept only for `max_hint_window_in_ms` (default 3h). If a node is down *longer*, hints are dropped and the only recovery is repair. So a long outage **requires** a repair afterward, or that node will serve stale data until read-repaired. Hint storage can also overload a coordinator if many replicas are down at once (hint storms).

### 7.5 Sloppy quorum's broken guarantee

With sloppy quorum, `R + W > N` no longer guarantees overlap because writes may land on *substitute* nodes outside the home set, while reads target the home set. You get higher availability (the AP knob) but lose the read-overlap guarantee until hinted handoff completes. DynamoDB uses this; understand that "quorum" availability ≠ "quorum" consistency under partitions.

### 7.6 Causality and version vectors vs. vector clocks

- A **version vector** tracks per-*replica* update counts for a single object (used in Dynamo/Riak to detect concurrent object updates).
- A **vector clock** tracks per-*process* event counts to order events generally.
- **Dotted version vectors (DVVs)** fix a sibling-explosion bug in naïve version vectors where slow clients could create unbounded siblings; Riak adopted DVVs. This is a real, subtle correctness improvement worth knowing for senior interviews.

### 7.7 Consistency models lattice (where quorums sit)

From strongest to weakest: **Linearizable** ⊃ **Sequential** ⊃ **Causal** ⊃ **Read-your-writes / Monotonic (session guarantees)** ⊃ **Eventual.** Strict quorums *approximate* strong consistency for single keys but don't give it across keys or under all failure interleavings. **Causal consistency** (e.g., COPS, MongoDB causal sessions, Cassandra with care) is often the sweet spot: it preserves causality (the consistent-prefix property) without the cost of linearizability, and is provably the *strongest* model achievable in an always-available (AP) system.

### 7.8 Tunable knobs cheat list (Cassandra-centric)

`replication_factor`, per-query consistency level, `speculative_retry`, `read_repair`, `gc_grace_seconds`, `hinted_handoff_*`, `max_hint_window_in_ms`, compaction strategy, `nodetool setstreamthroughput`, `concurrent_reads/writes`, `commitlog_sync` (`periodic` vs `batch` — durability vs. latency), `memtable_flush_writers`.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Topology comparison

| Dimension | Single-leader | Multi-leader | Leaderless |
|-----------|---------------|--------------|------------|
| Write conflicts | None | **Yes (hard)** | Yes (resolved at read/merge) |
| Write availability under failure | Drops during failover | High (local leader) | **Very high** |
| Consistency (default) | Strong | Eventual + conflicts | Eventual (tunable) |
| Write latency | One node + (sync) replicas | Local | W replicas |
| Operational complexity | Failover orchestration | Conflict resolution | Quorum/repair tuning |
| Best for | OLTP, financial, default | Multi-region active-active, offline | High-availability, multi-DC, large scale |
| Examples | PostgreSQL, MySQL, MongoDB | CouchDB, multi-region MySQL | Cassandra, DynamoDB, Riak |

### 8.2 Synchrony comparison

| | Sync | Semi-sync | Async |
|--|------|-----------|-------|
| Data loss on leader crash | None (on sync replica) | None on the sync replica | Possible |
| Write latency | High (replica RTT) | Medium | Low |
| Availability if replica down | **Blocks** | Degrades to async | Unaffected |
| Use when | Zero data loss required | Balanced default | Throughput/latency priority, some loss tolerable |

### 8.3 Conflict resolution comparison

| Strategy | Detects concurrency? | Loses data? | Complexity | Use when |
|----------|---------------------|-------------|------------|----------|
| LWW | No (just picks newest ts) | **Yes, silently** | Low | Loss-tolerant, clock-disciplined |
| Version vectors + siblings | Yes | No (app merges) | Medium | Need correctness, can merge |
| CRDTs | N/A (always merges) | No | Medium (must model as CRDT) | Counters, sets, carts, collaborative |
| App-defined merge | Yes | No | High | Domain-specific merge logic |
| CAS / optimistic lock | Yes (rejects) | No (retries) | Low–medium | Single-key invariants |
| Consensus/LWT | N/A (serializes) | No | High (latency) | Linearizability required |

### 8.4 Explicit use-when / avoid-when

- **Use single-leader when:** you need strong consistency and simple semantics, writes fit one node, brief failover is acceptable (most OLTP apps). **Avoid when:** you need multi-region low-latency writes or write throughput beyond one node.
- **Use multi-leader when:** local writes per region are essential or you support offline/multi-device. **Avoid when:** you can't define a sound conflict-resolution policy (most relational schemas with invariants).
- **Use leaderless when:** availability and write throughput dominate, eventual consistency is acceptable, multi-DC. **Avoid when:** you need cross-key transactions, strong consistency by default, or can't run repair operationally.
- **Use synchronous/sync-quorum when:** data loss is unacceptable (ledgers). **Avoid when:** the latency cost (esp. cross-region) is prohibitive.

---

## 9. Failure modes & debugging

### 9.1 Replication lag spike (followers fall behind)

**Symptoms:** stale reads, growing `pg_stat_replication` lag, user "my change disappeared" reports.
**Causes:** write burst, long-running query blocking replay on standby, network saturation, slow disk on standby, single-threaded apply bottleneck.
**Diagnose (PostgreSQL):** `SELECT *, pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS lag_bytes FROM pg_stat_replication;` and check `replay_lag`. On standby, check for `max_standby_streaming_delay` query cancellations.
**Diagnose (Cassandra):** `nodetool netstats` (streaming), `tpstats` (dropped mutations / pending), gossip.
**Fix:** throttle writes, add capacity, route read-your-writes traffic to primary, ensure standby isn't I/O starved.

### 9.2 Split brain after failover

**Symptoms:** two nodes accept writes; data diverges; reconciliation pain.
**Diagnose:** multiple nodes report `is_leader=true`; conflicting writes; orchestrator logs show double promotion.
**Fix/Prevent:** fencing/STONITH, a quorum-based DCS (etcd via Patroni) so only a node holding the lock can be leader, generous failure-detection timeouts. After the fact: pick a survivor, replay/merge the divergent writes manually.

### 9.3 Lost writes on failover (async)

**Symptoms:** acked writes vanish after promotion of a behind replica. (GitHub 2012 MySQL incident is the canonical real-world case.)
**Fix:** use semi-sync (at least one sync replica), GTID/`pg_rewind` to reconcile the demoted old primary, promote the most-caught-up replica.

### 9.4 Tombstone overwhelm / data resurrection (Cassandra)

**Symptoms:** read timeouts/`TombstoneOverwhelmingException`; deleted rows reappear.
**Causes:** queue-like delete patterns; **failure to run `nodetool repair` within `gc_grace_seconds`** lets a node that missed the delete resurrect data after tombstones are purged.
**Fix:** schedule repairs (Reaper), redesign delete-heavy schemas, tune `gc_grace_seconds` carefully.

### 9.5 Quorum unavailable under partition

**Symptoms:** `UnavailableException` / `WriteTimeoutException` when too few replicas are reachable for the chosen consistency level.
**Diagnose:** `nodetool status` (DOWN nodes), datacenter reachability.
**Fix:** use `LOCAL_QUORUM` to survive cross-DC partitions; right-size RF per DC; consider lowering CL for non-critical paths (explicit tradeoff).

### 9.6 Clock-skew lost updates (LWW)

**Symptoms:** a write "disappears" though it succeeded; later-but-lower-timestamp write wins.
**Diagnose:** compare node clocks, check NTP health, inspect cell write timestamps (`WRITETIME()` in CQL).
**Fix:** discipline clocks (chrony/NTP, ideally bounded like AWS Time Sync), avoid LWW for critical mutations, use CAS/LWT or CRDTs.

### 9.7 Replication slot fills disk (PostgreSQL)

**Symptoms:** primary disk fills; WAL not recycled.
**Diagnose:** `pg_replication_slots` shows an inactive slot with growing `wal_status`/retained bytes.
**Fix:** drop orphaned slots, set `max_slot_wal_keep_size` (PG13+) to bound retention, monitor slot activity.

---

## 10. Interview drill

**Q1. Explain the quorum condition R + W > N and why it works.**
*Model answer:* It guarantees that any read set of R replicas and any write set of W replicas overlap in at least one replica (pigeonhole: R + W slots > N replicas), so a read sees at least one replica with the latest write; you then pick the newest version. The standard N=3, W=2, R=2 tolerates one node failure.
- *Follow-up: Does it guarantee linearizability?* No — partial writes, sloppy quorums, and concurrent writes break strictness; you need consensus/LWT for true linearizability.
- *Follow-up: What does W=N, R=1 give you?* Fast reads, but writes block/fail if any replica is down — fragile writes.
- *Follow-up: Why odd N?* Majorities are well-defined and even N wastes a node without improving fault tolerance.

**Q2. Single-leader vs. multi-leader vs. leaderless — when each?** *(senior-signal)*
*Model answer:* Single-leader for strong consistency/simple OLTP (Postgres/MySQL); multi-leader for multi-region local writes/offline at the cost of conflict resolution; leaderless for high availability and tunable consistency at scale (Cassandra/Dynamo). The deciding axes are write-conflict tolerance, availability-under-partition needs, and consistency requirements.
- *Follow-up: Why is multi-leader so hard?* Concurrent conflicting writes require resolution (LWW loses data; merge/CRDTs add complexity).
- *Follow-up: Can leaderless be strongly consistent?* For single keys with R+W>N plus consensus (LWT), yes, at higher latency.

**Q3. What is replication lag and what anomalies does it cause? How do you fix each?**
*Model answer:* Lag is how far async followers trail the leader. It causes read-your-writes violations (own write disappears), monotonic-reads violations (time goes backward across replicas), and consistent-prefix violations (effect seen before cause). Fixes: route own-data reads to the leader/caught-up replica; pin a user to one replica; keep causal data co-located.
- *Follow-up: Precise read-your-writes implementation?* Capture the write LSN, ensure the chosen replica's replay LSN ≥ it before reading (else read leader).

**Q4. Walk through Cassandra's write and read paths.**
*Model answer:* Write → coordinator → all N replicas (wait for W) → each appends to commit log + memtable (LSM), later flush to SSTable; hinted handoff for down replicas. Read → coordinator → one full + (R−1) digest reads → compare versions → return newest + read-repair stale replicas; background anti-entropy via Merkle trees (`nodetool repair`).
- *Follow-up: What's a digest read?* A hash-only read to save bandwidth while still detecting divergence.
- *Follow-up: LOCAL_QUORUM vs QUORUM?* LOCAL_QUORUM = majority within the local DC (survives cross-DC partition, lower latency); QUORUM spans all DCs.

**Q5. How does sync vs. async vs. semi-sync replication affect durability and availability?**
*Model answer:* Sync = no loss but write blocks if replica is slow/down; async = fast and resilient to replica failure but can lose acked writes on leader crash; semi-sync (one sync replica) balances — guarantees one up-to-date copy without coupling to all replicas.
- *Follow-up: Where exactly is the "ack" in Postgres?* `synchronous_commit` controls it: `remote_write` (received), `on`/`remote_flush` (fsynced on standby), `remote_apply` (visible to reads on standby).

**Q6. LWW vs. vector clocks vs. CRDTs for conflict resolution.** *(senior-signal)*
*Model answer:* LWW is simple but silently loses concurrent writes and is clock-skew-sensitive. Vector clocks detect concurrency so the app can merge (no loss) at the cost of complexity and siblings. CRDTs guarantee deterministic conflict-free merge for specific data types. Choose by whether silent loss is acceptable and whether your data can be modeled as a CRDT.
- *Follow-up: Why is LWW dangerous with clock skew?* A genuinely-later write can carry a lower timestamp and lose.
- *Follow-up: G-Counter merge rule and why correct?* Per-node slots, element-wise max; each node authoritative for its slot, slots monotonic → order/duplication-independent convergence.

**Q7. What is read-repair and anti-entropy with Merkle trees?**
*Model answer:* Read-repair fixes stale replicas opportunistically during reads. Anti-entropy is a background full sync; Merkle trees let two replicas compare root hashes and recursively descend only into differing subtrees, exchanging O(log n) hashes to localize and copy just the divergent ranges efficiently.
- *Follow-up: Why both?* Read-repair only fixes read data; anti-entropy fixes cold data and is mandatory operationally.

**Q8. Describe split-brain and how to prevent it.**
*Model answer:* Two nodes both believe they're leader after a partition/failover and accept conflicting writes. Prevent with fencing/STONITH, a consensus-backed lock (etcd/ZooKeeper via Patroni) so only the lock holder leads, and quorum-based failure detection.
- *Follow-up: Why can naive timeout-based failover cause it?* A slow/partitioned (not dead) leader gets a new leader elected while it's still writing.

**Q9. Why might `R + W > N` still return stale data?** *(senior-signal)*
*Model answer:* A prior quorum write that partially failed (fewer than W) is in-doubt — not rolled back; reads may flip-flop until repair. Sloppy quorums place writes on substitutes outside the home set, breaking overlap. Concurrent writes mean overlap shows you a value but not *which* is authoritative without proper versioning. Hence quorums give strong-eventual, not linearizable, semantics absent consensus.

**Q10. How do Dynamo, Cassandra, and Postgres replication differ at a high level?**
*Model answer:* Postgres = single-leader WAL streaming (sync/async/semi-sync), strong consistency, external failover (Patroni). Dynamo = leaderless, sloppy quorums + hinted handoff, version vectors + app merge, AP. Cassandra = Dynamo-derived leaderless with consistent hashing/vnodes, tunable per-query consistency, LWW with timestamps (or LWT/Paxos for linearizable ops), Merkle-tree repair.

**Q11. You must guarantee zero data loss across two regions but can't pay cross-region sync latency on every write. Design it.** *(senior-signal)*
*Model answer:* Use semi-sync within the primary region (one local sync standby → no loss for local failures at low latency) plus async cross-region replication for DR, accepting a small RPO for whole-region loss; or use a quorum spanning region with `LOCAL_QUORUM` for latency and `EACH_QUORUM` only for the rare must-be-durable-everywhere writes. State the explicit RPO/RTO and latency tradeoffs.

**Q12. When would you choose causal consistency over linearizability?**
*Model answer:* When you need high availability (AP) but must preserve cause/effect ordering (no consistent-prefix anomalies) — causal is the strongest model achievable while staying always-available, far cheaper than linearizable, and sufficient for most user-facing semantics (comments, feeds, sessions).

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability; transaction guarantees (distinct from CAP's "C").
- **Anti-entropy** — background process that reconciles divergent replicas (e.g., Merkle-tree repair).
- **AP / CP system** — under partition, favors Availability vs. Consistency (CAP).
- **Asynchronous replication** — leader acks before replicas confirm; fast, can lose writes.
- **Bloom filter** — probabilistic structure to skip SSTables that can't contain a key (no false negatives).
- **CAP theorem** — under partition, choose consistency or availability.
- **Causal consistency** — preserves happened-before ordering; strongest AP-compatible model.
- **Commit log** — sequential durability log (Cassandra) / WAL analog.
- **Compaction** — merging SSTables in an LSM store.
- **Consensus** — protocol for agreement among nodes (Paxos, Raft); enables linearizable ops/elections.
- **Consistent hashing** — key→node mapping minimizing reshuffles on membership change.
- **Consistent prefix reads** — reads never see effects before their causes.
- **Coordinator** — node handling a client request on its behalf in leaderless systems.
- **CRDT** — Conflict-free Replicated Data Type; merges concurrent updates deterministically.
- **Digest read** — hash-only read for divergence detection (Cassandra).
- **Dotted version vector (DVV)** — version-vector refinement bounding sibling growth (Riak).
- **Eventual consistency** — replicas converge if writes stop.
- **Failover** — promoting a replica to leader after leader failure.
- **Fencing / STONITH** — isolating/killing a deposed leader to prevent split-brain.
- **fsync** — syscall forcing buffered data to durable storage.
- **G-Counter** — grow-only counter CRDT.
- **Gossip protocol** — epidemic membership/health dissemination.
- **GTID** — Global Transaction ID (MySQL) for cluster-wide transaction identity.
- **Happened-before (→)** — Lamport's causal-ordering relation; absence both ways = concurrent.
- **Hinted handoff** — substitute node stores a write destined for an unreachable home node and delivers it later.
- **ISR** — In-Sync Replicas (Kafka).
- **Leaderless replication** — Dynamo-style; clients write/read multiple replicas with quorums.
- **Linearizability** — strongest single-object consistency; appears as one copy in real-time order.
- **LSM-tree** — Log-Structured Merge tree; write-optimized storage (memtable + SSTables).
- **LSN** — Log Sequence Number; WAL byte position (PostgreSQL).
- **LWW** — Last-Writer-Wins conflict resolution by timestamp.
- **LWT** — Lightweight Transaction (Cassandra), Paxos-based compare-and-set.
- **Memtable** — in-memory write buffer in an LSM store.
- **Merkle tree** — hash tree enabling efficient diff of large datasets.
- **Monotonic reads** — a client never sees data older than it already saw.
- **Multi-leader** — multiple write-accepting leaders; needs conflict resolution.
- **N / RF** — replication factor (copies per datum).
- **Network partition** — network split preventing some nodes from communicating.
- **PACELC** — CAP extension adding the latency/consistency tradeoff in normal operation.
- **Paxos / Raft** — consensus algorithms.
- **Quorum** — minimum replicas required to ack an operation.
- **R / W** — read/write quorum sizes.
- **Read-repair** — fixing stale replicas during a read.
- **Read-your-writes (read-after-write)** — a client always sees its own prior writes.
- **Replication lag** — how far a follower trails the leader.
- **Replication slot** — PostgreSQL bookmark retaining WAL a standby needs.
- **Semi-synchronous replication** — at least one sync replica, rest async.
- **Single-leader** — one write-accepting node; followers replicate.
- **Sloppy quorum** — writing to substitute nodes when home replicas are unreachable.
- **Split brain** — two leaders accept conflicting writes.
- **SSTable** — immutable sorted on-disk file in an LSM store.
- **Synchronous replication** — leader waits for replica confirmation before acking.
- **Tombstone** — deletion marker in an append-only/LSM store.
- **Vector clock** — per-process counters detecting causal vs. concurrent events.
- **Version vector** — per-replica counters detecting concurrent object updates.
- **vnode** — virtual node; many token ranges per physical node (Cassandra) for balance.
- **WAL** — Write-Ahead Log; log written before data changes for durability and replication.
- **ZooKeeper / KRaft** — coordination/consensus services for metadata and leader election.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **Three topologies:** single-leader (simple, strong, failover risk) · multi-leader (local writes, conflict hell) · leaderless (HA, tunable, repair needed).
- **Synchrony:** sync = no loss/blocks · async = fast/can lose · semi-sync = one sync replica (balanced).
- **Quorum:** `R + W > N` ⇒ read/write sets overlap. Standard **N=3, W=2, R=2** (tolerates 1 failure). Necessary, *not sufficient* for linearizability.
- **Availability extras:** sloppy quorum + hinted handoff (write to substitutes, deliver later) → AP under partition.
- **Repair:** read-repair (on reads) + anti-entropy via **Merkle trees** (`nodetool repair`, within `gc_grace_seconds`).
- **Lag anomalies → fixes:** read-your-writes (route to leader/LSN-wait) · monotonic reads (pin to one replica) · consistent prefix (co-locate causal data).
- **Conflicts:** LWW (simple, silent loss, clock-skew risk) · vector/version vectors (detect, siblings) · CRDTs (auto-merge) · CAS/LWT (reject/serialize).
- **Real systems:** PostgreSQL = WAL streaming single-leader (Patroni HA). Dynamo = leaderless, version vectors, sloppy quorum. Cassandra = leaderless, consistent hashing/vnodes, per-query CL, LWW + LWT/Paxos, Merkle repair. Kafka = per-partition leader, ISR, `acks=all`+`min.insync.replicas`.
- **Numbers:** RF=3 standard; `gc_grace_seconds`=10 days; hint window=3h default; prefer odd N; `LOCAL_QUORUM` is the multi-DC workhorse.
- **Don'ts:** N=2 "redundancy", cross-region sync on hot path, LWW for must-keep data, skipping repair, aggressive auto-failover.

### Self-test (no answers — recall practice)

1. With N=5, list every (W, R) pair that satisfies the quorum condition and state how many node failures each tolerates for reads and for writes independently.
2. A user reports their just-posted profile change "flickers" on and off across page reloads. Name the two anomalies likely involved and the precise fix for each, including how you'd implement read-your-writes deterministically (not by a fixed time window).
3. Explain exactly why a sloppy quorum breaks the `R + W > N` overlap guarantee, and what mechanism eventually restores correctness.
4. Two replicas of a shopping-cart object diverge. Walk through how a version vector lets you decide whether to overwrite or to surface siblings, and how you'd merge a cart as a CRDT instead.
5. Your Cassandra cluster starts resurrecting deleted rows. Give the root cause involving `gc_grace_seconds` and repair, and the step-by-step fix and prevention.
6. Design replication for a payment ledger that must never lose an acknowledged write but serves users on two continents — specify topology, synchrony, quorum/consistency levels, and the explicit RPO/RTO and latency tradeoffs you're accepting.

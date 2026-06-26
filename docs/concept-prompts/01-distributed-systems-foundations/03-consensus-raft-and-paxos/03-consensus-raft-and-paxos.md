# Consensus — Raft & Paxos

> A definitive engineering-handbook chapter for senior backend developers (Java/JVM-centric, but language-agnostic in the theory). Built from first principles up to production internals.

---

## 1. Overview & where it fits

### 1.1 What consensus is

**Distributed consensus** is the problem of getting a group of independent computers (called **nodes**, **processes**, or **replicas**) — each with its own memory, clock, and failure behavior, talking over an unreliable network — to **agree on a single value** (or a single ordered sequence of values), such that:

- **Agreement (safety):** no two correct nodes ever decide on *different* values.
- **Validity (non-triviality):** the value decided must have been *proposed* by some node (you can't just always decide `42`).
- **Termination (liveness):** every correct node eventually decides *something* (the protocol doesn't hang forever under reasonable conditions).

That sounds almost trivial when you say it in one sentence. It is, in fact, one of the deepest and most consequential problems in computer science, because **the network can drop, delay, duplicate, and reorder messages, and the nodes themselves can crash and restart at the worst possible moment.** Achieving agreement *despite* those faults is what makes consensus hard.

> **Mental model (the one paragraph):** Consensus is how a cluster of machines that individually can crash and that cannot trust the clock or the network nonetheless behaves like a *single, reliable, strongly-consistent state machine*. You feed the cluster a stream of commands; consensus guarantees that every surviving replica applies the *same commands in the same order*, so they all end up in the same state. Everything else — leader election, log replication, terms, quorums — is machinery in service of that one guarantee: **one agreed-upon log of commands, replicated and durable, that survives a minority of failures.**

### 1.2 The problem it solves

Almost every strongly-consistent distributed system has, buried inside it, a consensus module. Concretely, consensus is the foundation for:

- **Replicated state machines (RSM):** the canonical use. You have a deterministic state machine (a key-value store, a config database, a lock service). You replicate it N times. To keep replicas identical, you funnel every state-changing command through consensus so that all replicas apply the same ordered log. This is how **etcd**, **Consul**, **ZooKeeper**, and **Spanner** work internally.
- **Leader election:** electing exactly one coordinator at a time (a master, a primary, a lock holder), and ensuring two nodes never *both* think they're leader in a way that causes data corruption ("split brain").
- **Distributed locks & leases:** "only one worker may hold this lock" — a safety property that requires agreement.
- **Atomic configuration changes / membership:** changing the set of nodes in the cluster without losing safety.
- **Metadata & coordination:** which shard lives on which node, the current schema version, feature-flag truth, the head of a replication chain.

### 1.3 When you reach for it

You reach for consensus when you need **linearizable** (strongly consistent) behavior on a small but critical piece of state, and you cannot tolerate divergence even for a moment. Typical triggers:

- You need a **single source of truth** for cluster metadata that survives node failures.
- You need a **fault-tolerant leader** and must guarantee there is at most one at a time.
- You need a **durable, ordered, replicated log** that no single machine's disk can lose.

> **Linearizable / linearizability** — a consistency model where the system behaves as if every operation happened *atomically at a single instant* between its invocation and its response, and that instant respects real-time order. In practice: once a write is acknowledged, every subsequent read (from anyone) sees that write or a later one. It is the strongest single-object consistency guarantee and is exactly what consensus systems give you.

### 1.4 When you do NOT reach for it (preview)

Consensus is expensive: every committed operation costs at least one network round trip to a **majority (quorum)** of nodes plus a durable disk write (`fsync`). For high-throughput, latency-sensitive, or geo-distributed paths, you often **avoid consensus** by using:

- **Quorum replication without a leader log** (e.g., Dynamo-style `R + W > N`),
- **CRDTs** (Conflict-free Replicated Data Types) for data that can merge commutatively,
- **Eventually consistent** replication where you simply don't need agreement on order.

Section 8 covers this decision in depth. The rule of thumb: **use consensus for the small, critical control plane; avoid it on the high-volume data plane.**

### 1.5 Where it sits in a real architecture

```
            Clients
              │  (linearizable reads/writes of metadata, locks, config)
              ▼
   ┌──────────────────────────────┐
   │  Consensus group (3 or 5)     │   <-- the "control plane"
   │  leader ── follower ── follower│       small, critical, strongly consistent
   └──────────────────────────────┘
              │  hands out: leadership, config, shard maps, leases
              ▼
   ┌──────────────────────────────┐
   │  Data plane (100s of nodes)   │   <-- bulk storage / compute
   │  sharded, often eventually    │       high throughput, scaled out
   │  consistent or quorum-based   │
   └──────────────────────────────┘
```

A 1000-node database does **not** run one giant 1000-node consensus group (that would be unbearably slow). It runs many small consensus groups (often one per shard, 3 or 5 replicas each), or one small consensus group that *coordinates* the larger fleet.

---

## 2. Foundations from first principles

This section builds up the vocabulary and the impossibility results you must internalize before Raft and Paxos make sense.

### 2.1 The system model: what we assume about the world

Every consensus result depends on precise assumptions. The two axes that matter most:

**(a) Timing model — what we assume about clocks and delays:**

- **Synchronous model:** there are known upper bounds on message delay and on relative clock speeds. If a message doesn't arrive within `Δ`, you *know* it was lost or the sender crashed. Consensus is *easy* here. **Real distributed systems are not synchronous.**
- **Asynchronous model:** *no* bounds. A message can take arbitrarily long; a slow node is indistinguishable from a crashed one. This is the model in which the famous impossibility (FLP, below) holds.
- **Partially synchronous model:** the network is asynchronous *most of the time* but is *eventually* synchronous (after some unknown "global stabilization time," delays become bounded). **This is the model real algorithms (Raft, Paxos, ZAB) actually target.** It's realistic: networks misbehave during incidents but eventually calm down.

**(b) Failure model — how nodes can fail:**

- **Crash-stop (fail-stop):** a node either works correctly or halts. It never lies. *Most consensus systems (Raft, Paxos, etcd, ZooKeeper) assume this.*
- **Crash-recovery:** nodes can crash and come back (with persistent state on disk surviving). Raft and Paxos handle this; it's why they `fsync` state.
- **Byzantine:** nodes can behave *arbitrarily* — lie, send conflicting messages, be malicious or corrupted. Tolerating this requires **Byzantine Fault Tolerant (BFT)** consensus (PBFT, Tendermint, blockchains). It needs **3f+1** nodes to tolerate `f` Byzantine faults, vs **2f+1** for crash faults. *Out of scope for most internal infra; relevant to blockchains.*

> **Throughout this chapter, unless stated otherwise, we assume: partial synchrony + crash-recovery faults + non-Byzantine.** That is the world Raft, Paxos, etcd, ZooKeeper, and Spanner live in.

### 2.2 Quorums — the central trick

A **quorum** is any subset of nodes large enough that **any two quorums overlap in at least one node.** For a cluster of `N` nodes, a **majority quorum** is `⌊N/2⌋ + 1` nodes.

| N (cluster size) | Majority quorum | Failures tolerated (f) |
|---|---|---|
| 1 | 1 | 0 |
| 2 | 2 | 0 |
| 3 | 2 | 1 |
| 4 | 3 | 1 |
| 5 | 3 | 2 |
| 6 | 4 | 2 |
| 7 | 4 | 3 |

Two facts fall out of this table that you must burn into memory:

1. **You need `2f+1` nodes to tolerate `f` failures.** (5 nodes tolerate 2; 3 nodes tolerate 1.)
2. **Even cluster sizes are pointless** for fault tolerance: a 4-node cluster tolerates the same 1 failure as a 3-node cluster but needs a *larger* quorum (3 vs 2), so it's *slower and no more resilient*. **Always use odd numbers** (3, 5, 7).

**Why overlap matters:** if every decision requires a majority to agree, and any two majorities share at least one node, then that shared node "remembers" the previous decision and prevents a conflicting one. This single property — **quorum intersection** — is the engine behind all of Paxos and Raft.

> **Split brain** — the failure where a network partition causes two sub-groups to *both* believe they are in charge and both accept writes, leading to divergent, conflicting state. Quorums prevent it: at most one side of a partition can contain a majority, so only one side can make progress. The minority side cannot form a quorum and must stall.

### 2.3 The FLP impossibility result (explained)

In 1985, Fischer, Lynch, and Paterson proved the **FLP impossibility result**:

> **In a purely asynchronous system, no deterministic consensus protocol can guarantee both safety and termination if even a single node may crash.**

Read that carefully. It does **not** say consensus is impossible. It says you cannot have *all three* of: (1) always safe, (2) always terminates, (3) tolerates one crash, *in a fully asynchronous network with a deterministic algorithm.*

**The intuition:** in an asynchronous network you cannot distinguish a *crashed* node from a *very slow* node or a *delayed message*. There's no timeout you can trust, because there's no bound on delay. The proof constructs an infinite execution in which the protocol is forever kept in a "bivalent" (undecided, could-go-either-way) state by an adversarial scheduler that delays exactly the right message at exactly the right moment. The system never *violates* safety, but it can be prevented from *ever deciding* — it loses liveness forever.

**Why it doesn't kill real systems:** real consensus algorithms *sidestep* FLP rather than violate it. They do this by:

- **Relaxing the timing model to partial synchrony** — assuming the network is *eventually* well-behaved. Once it is, timeouts become meaningful and progress is made. (Raft, Paxos, ZAB.)
- **Using randomization** — randomized timeouts break the adversarial symmetry the FLP proof relies on. Raft's *randomized election timeout* is a direct, practical FLP workaround: it makes the pathological tie-breaking scenario vanishingly unlikely.
- **Sacrificing guaranteed liveness, keeping safety.** This is the crucial engineering takeaway: **real consensus always preserves safety (never two leaders committing conflicting entries), but liveness is only guaranteed under good-enough network conditions.** During a bad partition the cluster may simply stop making progress (become unavailable) rather than do something wrong. **That is the CAP "CP" choice in action** (see below).

> **Bivalent / univalent** — terms from the FLP proof. A protocol state is *bivalent* if both decision outcomes (say "0" and "1") are still reachable from it; *univalent* if the outcome is already inevitable. The proof shows you can keep a system bivalent forever.

### 2.4 CAP and PACELC — placing consensus on the consistency map

> **CAP theorem** — informally: when a network **P**artition happens, a distributed system must choose between **C**onsistency (every read sees the latest write) and **A**vailability (every request gets a non-error response). You can't have both *during a partition*. (When there's no partition, you can have both.)

Consensus systems are **CP**: during a partition, the minority side becomes **unavailable** (it refuses reads/writes because it can't reach a quorum) to preserve **consistency**. etcd, ZooKeeper, Spanner, Consul are all CP.

> **PACELC** — a refinement: **if P**artition, choose **A**vailability or **C**onsistency; **E**lse (normal operation), choose **L**atency or **C**onsistency. Consensus systems are typically **PC/EC**: consistent during partitions, and consistent (paying latency cost) even normally. The cost: every write pays a round-trip-to-quorum latency you can't avoid.

### 2.5 Replicated State Machine (RSM) — the unifying abstraction

The dominant way consensus is *used* is the **Replicated State Machine** approach:

1. Model your service as a **deterministic state machine**: same start state + same sequence of commands ⇒ same end state, always. (Deterministic = no `random()`, no wall-clock reads, no map-iteration-order dependence inside the command application.)
2. Put a **replicated log** in front of it. Consensus's *real* job is to make all replicas agree on **the same log: the same commands in the same positions (indexes).**
3. Each replica **applies** committed log entries to its local state machine **in log order**. Because the log is identical and application is deterministic, all replicas reach identical state.

```
   command  ──►  [ Consensus: agree on log index & order ]  ──►  replicated log
                                                                       │
                          ┌────────────────────┬───────────────────────┤
                          ▼                    ▼                       ▼
                    state machine        state machine           state machine
                     (replica A)          (replica B)             (replica C)
                  apply in log order   apply in log order      apply in log order
                          │                    │                       │
                          ▼                    ▼                       ▼
                     identical state    identical state         identical state
```

**Everything Raft and Paxos do is in service of building that one agreed-upon log.** Keep this picture in mind for the rest of the chapter.

> **Log (in this context)** — an append-only, ordered sequence of entries. Each entry has an **index** (its position, 1, 2, 3, …) and contains a **command** to apply to the state machine. "Committing" an entry means consensus has guaranteed it is permanent and will appear at that index on every replica forever.

---

## 3. How it works internally

This is the heart of the chapter. We'll go *deep* on **Raft** (because it's the design you'll actually read, implement, and operate), then cover **Paxos / Multi-Paxos** at a working level and explain *why Raft was created*.

### 3.1 Raft: design philosophy

Raft (Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm," 2014) was explicitly designed for **understandability**. It is *equivalent in power* to Multi-Paxos but decomposes the problem into three relatively independent sub-problems:

1. **Leader election** — pick one leader.
2. **Log replication** — the leader pushes its log to followers.
3. **Safety** — constrain elections and commits so the system never loses a committed entry.

Raft enforces a **strong leader**: all client writes go through the leader, and **log entries only ever flow from leader to followers** (never the other way). This asymmetry is what makes Raft simpler than Paxos.

### 3.2 Server states and the core data

Every Raft node is in exactly one of three states at any time:

- **Follower** — passive. Responds to RPCs from leaders and candidates. Resets its election timer whenever it hears from a valid leader. (All nodes start here.)
- **Candidate** — a follower that timed out without hearing from a leader and is now trying to *become* leader by soliciting votes.
- **Leader** — handles all client requests, replicates entries, sends periodic heartbeats. There is **at most one leader per term.**

**Persistent state on every server (must survive crash — `fsync` before responding to RPCs):**

| Field | Meaning |
|---|---|
| `currentTerm` | Latest term this server has seen (monotonic counter, starts at 0). |
| `votedFor` | `candidateId` this server voted for in `currentTerm` (or null). Prevents double-voting. |
| `log[]` | Log entries; each holds a `command` and the `term` when the leader created it. |

**Volatile state on every server:**

| Field | Meaning |
|---|---|
| `commitIndex` | Highest log index known to be committed. |
| `lastApplied` | Highest log index applied to the state machine. |

**Volatile state on the leader only (reinitialized after each election):**

| Field | Meaning |
|---|---|
| `nextIndex[]` | For each follower, the next log index the leader will send. |
| `matchIndex[]` | For each follower, the highest log index known to be replicated on it. |

> **Term** — Raft divides time into consecutive numbered **terms**, each beginning with an election. A term is a *logical clock*: it lets nodes detect stale leaders. Each term has **at most one leader**. Terms are monotonically increasing integers, persisted to disk. Any message carries the sender's term; **a node that sees a higher term immediately steps down to follower and updates its `currentTerm`.** A node that receives a message with a *stale* (lower) term rejects it. This single rule — "higher term wins, lower term is rejected" — is the backbone of Raft's safety.

### 3.3 Leader election — step by step

**Trigger:** a follower's **election timeout** expires without receiving an `AppendEntries` (heartbeat) from a current leader or granting a vote to a candidate. The election timeout is **randomized** (commonly 150–300 ms in the paper; production systems use larger values, e.g., etcd default election timeout is 1000 ms with 100 ms heartbeat). Randomization is the FLP workaround — it makes simultaneous timeouts (which cause split votes) rare.

**The election workflow:**

1. Follower increments `currentTerm` (say from 4 → 5), transitions to **Candidate**, and **votes for itself** (`votedFor = self`). Persist both.
2. It resets its election timer (so it'll retry if this election fails).
3. It sends `RequestVote` RPCs **in parallel** to all other servers, including its own `lastLogIndex` and `lastLogTerm` (its log's "freshness").
4. Each recipient grants its vote **if and only if**:
   - The candidate's term ≥ the recipient's `currentTerm` (and if greater, recipient steps down/updates term), **and**
   - The recipient hasn't already voted in this term (`votedFor` is null or already this candidate), **and**
   - The candidate's log is **at least as up-to-date** as the recipient's log (the *election restriction*, §3.6).
5. **Three possible outcomes:**
   - **(a) Wins:** receives votes from a **majority** (including itself). Becomes **Leader**, immediately starts sending heartbeats to assert authority and suppress new elections.
   - **(b) Another node wins:** receives an `AppendEntries` from a node claiming to be leader with term ≥ its own. It accepts that node as leader and **reverts to Follower**.
   - **(c) Split vote / timeout:** no one gets a majority (e.g., two candidates split the votes). The election timer expires again, term increments, and a **new election** starts. Randomized timeouts ensure that next time, one candidate likely fires first and wins cleanly.

```
         times out, starts election
Follower ───────────────────────────► Candidate
   ▲                                      │  wins majority of votes
   │ discovers leader OR higher term      ▼
   │◄──────────────────────────────── Leader
   │                                      │ discovers server with higher term
   └──────────────────────────────────────┘
```

> **Heartbeat** — an empty `AppendEntries` RPC the leader sends periodically (the *heartbeat interval*, much shorter than the election timeout — typically 10x shorter) to tell followers "I'm alive, don't start an election." If followers stop hearing heartbeats, they start an election. The rule **`electionTimeout >> heartbeatInterval >> networkRTT`** is mandatory for stability.

### 3.4 Log replication — step by step

Once a leader is elected, normal operation looks like this:

1. **Client sends a command** to the leader (e.g., `SET x = 3`). If a client contacts a follower, the follower redirects it to the leader.
2. Leader **appends** the command to *its own* log as a new entry `{ index, term, command }` (not yet committed).
3. Leader sends `AppendEntries` RPCs **in parallel** to all followers, carrying the new entry plus `prevLogIndex` and `prevLogTerm` (the index/term of the entry *immediately preceding* the new ones — this is the **consistency check**).
4. Each follower:
   - Rejects the RPC if its term is stale or if it has no entry at `prevLogIndex` matching `prevLogTerm` (a **log inconsistency**). On rejection, the leader decrements `nextIndex` for that follower and retries — walking backward until they find the last point of agreement, then overwriting the follower's divergent suffix. (Optimizations let the follower report the conflicting term/index so the leader can skip back faster.)
   - If the consistency check passes, the follower **appends** the new entries (truncating any conflicting tail), `fsync`s, and replies success.
5. Once the leader sees that an entry is stored on a **majority** of servers (it tracks this via `matchIndex[]`), the entry is **committed**. The leader advances `commitIndex`.
6. Leader **applies** committed entries to its state machine and **returns the result to the client.**
7. Leader piggybacks the updated `commitIndex` on subsequent `AppendEntries`; followers then learn the entry is committed and **apply** it to *their* state machines in order.

**The Log Matching Property** (an invariant Raft maintains): *if two logs contain an entry with the same index and same term, then (a) they store the same command, and (b) all preceding entries are identical.* The `prevLogIndex`/`prevLogTerm` consistency check inductively preserves this. It means logs can only diverge in a *suffix*, and that suffix is always overwritten by the leader's version.

> **Commit index** — `commitIndex` is the highest log index the leader has confirmed is safely replicated to a majority and is therefore *durable forever*. Crucially, **a leader can only directly commit entries from its own current term** — it commits older-term entries only *indirectly* by committing a current-term entry on top of them (this prevents a subtle safety bug; see §7.1).

### 3.5 Applying vs committing — keep them distinct

- **Committed** = consensus guarantees the entry is permanent (replicated to a majority, will survive any minority of failures).
- **Applied** = the entry's command has actually been executed against the local state machine.

`lastApplied` chases `commitIndex`. A background loop on each node does: *while `commitIndex > lastApplied`: increment `lastApplied`; apply `log[lastApplied]` to the state machine.* The reply to the client only goes out **after the entry the client cared about is applied on the leader.**

### 3.6 Safety — the rules that make it correct

Raft's safety boils down to a few enforced rules:

1. **Election Safety:** at most one leader per term (enforced by majority voting + one-vote-per-term).
2. **Leader Append-Only:** a leader never overwrites or deletes its own log entries; it only appends.
3. **Log Matching:** (above) identical (index, term) ⇒ identical prefix.
4. **Leader Completeness (the key one):** if an entry is committed in some term, it is present in the logs of all leaders of *higher* terms. Enforced by the **election restriction**: a voter refuses its vote to any candidate whose log is *less up-to-date* than its own. "More up-to-date" is defined as: **higher `lastLogTerm` wins; if equal terms, longer log wins.** Because a committed entry is on a majority, any winning candidate must have gotten a vote from at least one server that holds that committed entry — and that server only votes for candidates at least as up-to-date — so the new leader necessarily has the committed entry. **This is how Raft guarantees a newly elected leader never lacks a committed entry, so committed data is never lost.**
5. **State Machine Safety:** if a server has applied an entry at a given index, no other server ever applies a *different* entry at that index.

### 3.7 Cluster membership changes (reconfiguration)

Changing the set of servers is dangerous: if you naively switch from old config `C_old` to new config `C_new`, there's a window where two *different majorities* (one in `C_old`, one in `C_new`) could each elect a leader → split brain. Raft offers two safe approaches:

- **Joint consensus (the original paper):** a transitional configuration `C_old,new` that requires **agreement from majorities of *both* `C_old` and `C_new`** for elections and commits. The cluster moves `C_old → C_old,new → C_new`, and at no point can two disjoint majorities form. Configuration changes are themselves *log entries*, applied as soon as they're seen (not when committed).
- **Single-server changes (the common, simpler approach used by etcd):** add or remove **one** server at a time. Because adding/removing a single node can't create two disjoint majorities, this is safe without joint consensus and is much easier to implement. To grow from 3→5, you add nodes one at a time (3→4→5). New nodes typically join as **non-voting learners** first to catch up their log before counting toward quorum.

> **Learner / non-voting member** — a node that *receives* the replicated log (to catch up) but does **not** vote in elections and does **not** count toward the commit quorum. Used when adding a fresh node so it doesn't temporarily enlarge the quorum (and risk availability) before it's caught up. etcd and many Raft libraries support learners.

### 3.8 Log compaction & snapshotting

The log grows forever; you can't keep it all. **Snapshotting** is the fix:

1. Each server independently takes a **snapshot** of its state machine up to some index `lastIncludedIndex` (and records `lastIncludedTerm`).
2. It then **discards all log entries up to and including** `lastIncludedIndex`. The snapshot replaces them.
3. If a follower lags so far behind that the leader has already discarded the entries it needs, the leader sends an **`InstallSnapshot` RPC** — shipping the whole snapshot instead of individual entries. The follower installs it and continues from there.

Snapshots must be taken carefully (often via copy-on-write or a fork) so the live state machine isn't blocked. Snapshot frequency is a tuning knob: too frequent wastes I/O; too rare lets logs grow and slows recovery.

### 3.9 Read consistency in Raft (subtle but critical)

Naively, a leader could just answer reads from its local state. **But a stale leader (one that was partitioned out and doesn't yet know it lost leadership) could serve stale data** — violating linearizability. Three standard solutions:

1. **Log reads through the log:** treat the read as a no-op log entry, commit it via quorum, then answer. Correct but expensive (full round trip + disk).
2. **ReadIndex:** the leader records its current `commitIndex` as the `readIndex`, confirms it's *still* the leader by exchanging heartbeats with a quorum (no disk write), waits until its state machine has applied up to `readIndex`, then serves the read. Linearizable, cheaper than #1. (Used by etcd, TiKV.)
3. **Lease reads:** the leader holds a time-based **leader lease**; as long as the lease is valid (and clock drift is bounded), it can serve reads locally with no network round trip. Fastest, but relies on bounded clock drift assumptions — riskier. (Used as an optimization in TiKV, CockroachDB.)

> **Stale read** — returning data that was correct at some point but is now out of date (a newer committed write exists that the reader didn't see). The ReadIndex/lease machinery exists precisely to prevent stale reads from a deposed leader.

### 3.10 Paxos & Multi-Paxos — a working understanding

**Paxos** (Lamport, 1998, "The Part-Time Parliament"; clarified in "Paxos Made Simple," 2001) solves consensus on a *single value*. It defines three roles (a node can play multiple):

- **Proposer:** proposes values.
- **Acceptor:** votes on proposals; a majority of acceptors decides.
- **Learner:** learns the chosen value.

**Single-decree (basic) Paxos** runs in two phases, using monotonically increasing **proposal numbers** (globally unique, e.g., `(counter, nodeId)`):

**Phase 1 — Prepare / Promise:**
1. A proposer picks a proposal number `n` (higher than any it has used) and sends `Prepare(n)` to a majority of acceptors.
2. An acceptor that receives `Prepare(n)`:
   - If `n` > the highest prepare it has promised, it **promises** not to accept any proposal numbered < `n`, and **returns the highest-numbered proposal it has already accepted** (if any).
   - Otherwise it ignores/rejects.

**Phase 2 — Accept / Accepted:**
3. If the proposer gets promises from a majority, it sends `Accept(n, v)` where `v` is: **the value of the highest-numbered already-accepted proposal returned in the promises** (if any acceptor had accepted something — it must reuse that value, this is the crux of safety), otherwise its own value.
4. An acceptor receiving `Accept(n, v)` accepts it **unless** it has already promised a higher number. When a majority accept, the value is **chosen**.

**Why it's safe:** the "reuse the highest already-accepted value" rule + majority overlap guarantees that once a value is chosen, every higher-numbered proposal will also propose that same value. So all proposals that succeed agree.

**Multi-Paxos:** running full two-phase Paxos for *every* log entry is wasteful (two round trips each). **Multi-Paxos** elects a *stable leader* (the **distinguished proposer**) which runs **Phase 1 once** for a range of future log slots, then for each new command only needs **Phase 2** (one round trip). This makes it as efficient as Raft in steady state. But the paper's description is famously terse and leaves many practical details (leader election mechanics, log management, membership changes, recovery) *unspecified* — implementers had to invent them.

> **Distinguished proposer / leader** — in Multi-Paxos, a single proposer that "owns" Phase 1 for a window of slots, so individual commands skip Phase 1. Functionally the same role as Raft's leader, but Paxos doesn't *prescribe* how to elect or maintain it — that's left to the implementer, which is a big reason Multi-Paxos is hard to get right.

### 3.11 Why Raft was created — Paxos's practical problems

The Raft authors' explicit motivation: **Paxos is hard to understand and hard to implement correctly.** Specific issues:

- **Single-decree Paxos is unintuitive**, and Multi-Paxos (what you actually need) is described only sketchily; "there is no widely agreed-upon algorithm for Multi-Paxos."
- Paxos's architecture (peer-to-peer proposers, values flowing in any direction) is poor for building real systems; **most real implementations end up adding a leader and looking more like Raft anyway** ("Paxos used in practice is closer to Multi-Paxos with a leader").
- Reconfiguration, log management, and recovery are under-specified, so every implementation diverges.

Raft's contributions: a **strong-leader** design (log flows one way only), **terms as a logical clock**, **randomized election timeouts** (simple, robust leader election), and **explicit, fully-specified handling** of log replication, safety, membership changes, and snapshotting. The result is *the same fault tolerance* with far less implementation ambiguity. That's why nearly every consensus system built since ~2014 (etcd, Consul, TiKV, CockroachDB, Kafka KRaft, MongoDB's replication protocol) uses Raft or a Raft-derivative, while the older generation (Chubby, ZooKeeper, Spanner) used Paxos-family protocols.

### 3.12 ZAB (ZooKeeper Atomic Broadcast) — Raft's cousin

> **ZAB** — the protocol inside **ZooKeeper** (a widely used coordination service). ZAB is *not* Paxos and predates Raft; it's an **atomic broadcast** protocol with a single leader that totally-orders all writes (called *transactions* / *zxids*). It has phases (discovery, synchronization, broadcast) analogous to Raft's election + log replication. Conceptually ZAB and Raft are very similar: strong leader, majority quorum, ordered log, leader election on failure. The main historical difference is ZAB's emphasis on **primary-order** delivery guarantees for its specific use case (coordination). ZooKeeper exposes a hierarchical namespace of "znodes" on top of ZAB.

---

## 4. The complete toolkit

Consensus is an *algorithm*, so the "toolkit" comes from the **libraries, systems, RPCs, and config knobs** you actually use. We cover (a) the abstract Raft RPC surface, (b) major Java/JVM libraries, (c) major systems and their CLIs/configs.

### 4.1 The Raft RPC surface (the protocol "API")

| RPC | Sent by | Purpose | Key arguments | Returns |
|---|---|---|---|---|
| `RequestVote` | Candidate | Solicit a vote during election | `term`, `candidateId`, `lastLogIndex`, `lastLogTerm` | `term`, `voteGranted` |
| `AppendEntries` | Leader | Replicate log entries; also heartbeat (empty entries) | `term`, `leaderId`, `prevLogIndex`, `prevLogTerm`, `entries[]`, `leaderCommit` | `term`, `success`, (opt.) conflict hints |
| `InstallSnapshot` | Leader | Ship a snapshot to a far-behind follower | `term`, `leaderId`, `lastIncludedIndex`, `lastIncludedTerm`, `offset`, `data`, `done` | `term` |
| `TimeoutNow` (extension) | Leader | Tell a follower to start an election *now* (used for graceful leadership transfer) | `term`, leader log info | — |

### 4.2 Java/JVM consensus libraries

| Library | What it is | Notes / defaults |
|---|---|---|
| **Apache Ratis** | A pure-Java, production Raft library (used by Apache Ozone, Apache IoTDB). | You implement a `StateMachine` interface; Ratis handles election, replication, snapshots. Pluggable RPC (gRPC, Netty), pluggable log/storage. |
| **JRaft (SOFAJRaft)** | Ant Group's mature Java Raft library, derived from Baidu's braft. | Production-grade: snapshots, linearizable reads (ReadIndex/lease), learners, multi-raft groups, batching, pipelining. Widely used in Chinese fintech infra. |
| **Atomix / Copycat** | Java framework with a Raft implementation + higher-level primitives (maps, locks, leader election). | Now somewhat dormant but historically popular for JVM apps. |
| **MicroRaft** | A lightweight, well-documented Java Raft library focused on the core algorithm. | Good for learning and embedding; transport-agnostic. |
| **Apache ZooKeeper (client + ensemble)** | Not a library you embed, but *the* JVM coordination service (ZAB-based). | You use the `ZooKeeper` client class, or **Apache Curator** (a higher-level client with recipes: locks, leader election, queues). |
| **Apache Kafka (KRaft)** | Kafka's built-in Raft (replaces ZooKeeper for metadata) — JVM, embedded in brokers/controllers. | Config via `controller.quorum.voters`, etc. (see §4.4). |

**Apache Curator recipes (JVM) — the practical leader-election/lock toolkit on ZooKeeper:**

| Recipe | Class | Purpose |
|---|---|---|
| Leader election | `LeaderLatch`, `LeaderSelector` | Elect one leader among JVM processes. |
| Distributed lock | `InterProcessMutex` | Mutually exclusive lock. |
| Shared/read-write lock | `InterProcessReadWriteLock` | RW semantics. |
| Counter | `DistributedAtomicLong` | Atomic counter. |
| Node cache / watches | `NodeCache`, `PathChildrenCache` | React to znode changes. |

### 4.3 etcd — the canonical Raft system (CLI & config)

> **etcd** — a distributed, strongly consistent key-value store written in Go, using Raft. It's the **backing store for Kubernetes** (all cluster state lives in etcd). The reference Raft library `go.etcd.io/raft` is reused by CockroachDB, TiKV, and others.

**Key `etcdctl` commands:**

| Command | Purpose |
|---|---|
| `etcdctl put <k> <v>` | Linearizable write. |
| `etcdctl get <k> [--consistency=l\|s]` | Read; `l`=linearizable (default), `s`=serializable (local, may be stale, faster). |
| `etcdctl member list/add/remove/promote` | Membership changes; `promote` turns a *learner* into a voter. |
| `etcdctl endpoint status --cluster -w table` | Show leader, term, raft index, DB size per node. |
| `etcdctl endpoint health` | Health of each endpoint. |
| `etcdctl snapshot save <file>` | Take a point-in-time snapshot (for backup/restore). |
| `etcdctl snapshot restore <file>` | Rebuild a cluster from a snapshot (disaster recovery). |
| `etcdctl defrag` | Reclaim space after compaction. |
| `etcdctl compaction <rev>` | Discard history before a revision (controls DB growth). |
| `etcdctl move-leader <id>` | Graceful leadership transfer. |
| `etcdctl alarm list/disarm` | Inspect/clear alarms (e.g., the dreaded `NOSPACE`). |

**Key etcd config flags (with real defaults):**

| Flag | Default | Meaning |
|---|---|---|
| `--heartbeat-interval` | **100 ms** | Leader→follower heartbeat period. |
| `--election-timeout` | **1000 ms** | Time without a heartbeat before a follower starts an election. **Rule: should be ~10× heartbeat, and well above network RTT.** |
| `--snapshot-count` | 100000 (historically 10000) | Number of committed entries before triggering a snapshot. |
| `--quota-backend-bytes` | **~2 GiB** default | Storage quota; exceeding it raises a `NOSPACE` alarm and makes the cluster **read-only**. |
| `--max-request-bytes` | ~1.5 MiB | Max size of a single request. |
| `--auto-compaction-mode` / `--auto-compaction-retention` | off by default | Periodic/revision-based history compaction. |
| `--initial-cluster` / `--initial-cluster-state` | — | Bootstrap config (`new` or `existing`). |

### 4.4 Kafka KRaft — Raft for Kafka metadata

> **KRaft (Kafka Raft)** — Kafka's self-managed metadata quorum that **replaces ZooKeeper** (ZooKeeper removal completed in Kafka 4.0). A set of **controller** nodes runs Raft over a special metadata log (`__cluster_metadata`); the elected **active controller** is the metadata leader. Brokers consume the metadata log as observers. This removed Kafka's longstanding external ZooKeeper dependency and improved metadata scalability and failover time.

| KRaft config | Meaning |
|---|---|
| `process.roles` | `broker`, `controller`, or `broker,controller` (combined mode). |
| `controller.quorum.voters` | `id@host:port` list of the controller voters (the Raft group). |
| `node.id` | Unique node id. |
| `controller.quorum.election.timeout.ms` / `...fetch.timeout.ms` | Raft election/fetch timeouts for the metadata quorum. |

Note: KRaft uses a **pull-based** variant (followers *fetch* from the leader, mirroring Kafka's existing replication style) rather than the push-based `AppendEntries` of classic Raft — an interesting design divergence.

### 4.5 ZooKeeper — config & CLI

| Config (`zoo.cfg`) | Default | Meaning |
|---|---|---|
| `tickTime` | 2000 ms | Basic time unit; heartbeats and timeouts are multiples of it. |
| `initLimit` | 10 (ticks) | Time for followers to connect & sync with leader at startup. |
| `syncLimit` | 5 (ticks) | Max lag (in ticks) before a follower is dropped. |
| `server.N=host:peerPort:leaderPort` | — | Ensemble membership; `peerPort` (2888) for follower↔leader, `leaderPort` (3888) for elections. |

**ZooKeeper CLI (`zkCli.sh`):** `create /path data`, `get /path`, `set /path data`, `ls /path`, `delete /path`, `stat /path`, plus the **four-letter words** (over the admin port) like `ruok` (are you OK? → `imok`), `stat`, `mntr` (metrics), `srvr`, `cons`.

### 4.6 Spanner — Paxos in a planet-scale database

> **Google Spanner** — a globally distributed, externally-consistent SQL database. Data is sharded; **each shard ("split") is replicated via a Paxos group** across multiple datacenters. Spanner achieves *external consistency* (a stronger-than-linearizable guarantee) using **TrueTime**, an API backed by GPS + atomic clocks that returns a *bounded* time interval `[earliest, latest]` rather than a single timestamp. By waiting out the uncertainty interval ("commit wait"), Spanner assigns globally meaningful commit timestamps. Consensus (Paxos) handles per-shard replication; TrueTime handles cross-shard global ordering. **CockroachDB** and **YugabyteDB** are open-source systems inspired by Spanner but they use **Raft per shard** and **hybrid logical clocks (HLC)** instead of TrueTime hardware.

---

## 5. Code examples by use case

These span different real scenarios. Java is used where language-relevant; CLI/config elsewhere.

### 5.1 Leader election across JVM processes (Curator `LeaderLatch` on ZooKeeper)

A classic use of consensus *without* writing your own: elect one active instance among many app servers (e.g., to run a singleton scheduler).

```java
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class SingletonScheduler {
    public static void main(String[] args) throws Exception {
        // Connect to a ZooKeeper ensemble (3 or 5 nodes). Retry with backoff
        // because the ensemble may briefly be unavailable during elections.
        CuratorFramework client = CuratorFrameworkFactory.newClient(
                "zk1:2181,zk2:2181,zk3:2181",
                new ExponentialBackoffRetry(1000, 3)); // 1s base, 3 retries
        client.start();

        // All instances contend on the same path; ZooKeeper (via ZAB consensus)
        // guarantees exactly one latch holder ("leader") at a time.
        String myId = java.net.InetAddress.getLocalHost().getHostName();
        LeaderLatch latch = new LeaderLatch(client, "/scheduler/leader", myId);

        latch.addListener(new LeaderLatchListener() {
            @Override public void isLeader() {
                // We are now THE leader. Safe to run the singleton job.
                // IMPORTANT: keep checking latch.hasLeadership() — we can lose
                // leadership at any time (e.g., a GC pause causes a session loss).
                System.out.println("I am leader, starting scheduler loop");
            }
            @Override public void notLeader() {
                // Lost leadership (session expiry/partition). STOP doing leader work
                // immediately to avoid two active schedulers (split brain at app level).
                System.out.println("Lost leadership, stopping scheduler loop");
            }
        });

        latch.start();          // join the election
        Thread.currentThread().join();  // keep process alive
    }
}
```

**Key teaching points:** the consensus system guarantees at most one leader, but **your code must react to `notLeader()` fast** — a leader that pauses (long GC) can lose its session while still believing it's leader. Never assume leadership is permanent; re-check `hasLeadership()` before any critical action (fencing — see §6).

### 5.2 A linearizable distributed lock with a fencing token (etcd)

Locks alone are unsafe under pauses; you need a **fencing token**. etcd gives you this naturally via the lock key's *revision*.

```bash
# Acquire a lease (TTL 10s). If the holder dies, the lease expires and the lock frees.
LEASE=$(etcdctl lease grant 10 | awk '{print $2}')

# Acquire the lock; this blocks until acquired. The command runs while held,
# then the lock is auto-released. The mod_revision acts as the fencing token.
etcdctl lock my-lock --ttl 10 -- /bin/sh -c '
    echo "Got lock at revision $ETCDCTL_LOCK_REV"
    # Pass $ETCDCTL_LOCK_REV to downstream storage as a fencing token:
    # storage rejects any write whose token < the latest token it has seen.
    do-critical-work --fencing-token=$ETCDCTL_LOCK_REV
'
```

**Why fencing matters:** suppose client A acquires the lock, then stalls (GC). The lease expires; client B acquires the lock and proceeds. Now A wakes up, still thinking it holds the lock, and issues a write. Without fencing, both writes hit storage → corruption. **With a monotonic fencing token, storage rejects A's now-stale token.** This is the canonical "consensus gives you a lock, but you still need fencing" lesson.

### 5.3 Implementing the Raft `AppendEntries` follower handler (the core of replication)

This is the heart of a Raft implementation. It shows the consistency check, conflict resolution, and commit-index advancement.

```java
// Follower's handler for an AppendEntries RPC. Returns an AppendEntriesReply.
synchronized AppendEntriesReply handleAppendEntries(AppendEntriesArgs a) {
    AppendEntriesReply reply = new AppendEntriesReply();
    reply.term = currentTerm;

    // 1. Reject stale leaders (term in the past). This is how a deposed
    //    leader learns it has been replaced.
    if (a.term < currentTerm) {
        reply.success = false;
        return reply;
    }

    // 2. Valid leader for this/newer term: adopt its term, become FOLLOWER,
    //    and reset the election timer (we just heard from the leader).
    if (a.term > currentTerm) {
        currentTerm = a.term;
        votedFor = null;
        persist();              // currentTerm/votedFor must be durable
    }
    state = FOLLOWER;
    resetElectionTimer();       // suppress our own election

    // 3. Log consistency check: do we have an entry at prevLogIndex whose
    //    term == prevLogTerm? If not, our logs diverge here -> reject so the
    //    leader can back up nextIndex and re-send earlier entries.
    if (a.prevLogIndex > lastIndex() ||
        (a.prevLogIndex > 0 && termAt(a.prevLogIndex) != a.prevLogTerm)) {
        reply.success = false;
        // Optimization: report our conflicting term/first-index so the leader
        // can skip back by a whole term instead of one entry at a time.
        reply.conflictIndex = firstIndexOfTermAt(a.prevLogIndex);
        return reply;
    }

    // 4. Append entries, truncating any conflicting suffix. The Log Matching
    //    Property lets us trust everything up to prevLogIndex.
    int i = a.prevLogIndex + 1;
    for (LogEntry e : a.entries) {
        if (i <= lastIndex() && termAt(i) != e.term) {
            truncateFrom(i);    // delete the divergent tail
        }
        if (i > lastIndex()) {
            append(e);
        }
        i++;
    }
    persist();                  // fsync the log before acknowledging

    // 5. Advance commit index: never beyond the last entry we actually have.
    if (a.leaderCommit > commitIndex) {
        commitIndex = Math.min(a.leaderCommit, lastIndex());
        signalApplyLoop();      // a background loop applies up to commitIndex
    }

    reply.success = true;
    return reply;
}
```

**Teaching points:** (1) the term rules at the top are the safety backbone; (2) the consistency check + truncation is how divergent follower logs get repaired; (3) `persist()`/`fsync` *before* replying is mandatory — otherwise a crash could "un-commit" acknowledged data; (4) the leader's `matchIndex[]` (updated from `success` replies) is what lets it decide an entry is on a majority and commit it.

### 5.4 Leader commit logic (advancing `commitIndex` only for the current term)

```java
// Runs on the leader after collecting matchIndex[] from successful replies.
void maybeAdvanceCommitIndex() {
    // Find the highest index N replicated on a majority.
    int[] sorted = sortedCopy(matchIndex);          // include leader's own lastIndex
    int majorityIndex = sorted[sorted.length / 2];  // median = highest majority index

    // SAFETY: only commit an entry from the CURRENT term directly. Committing an
    // older-term entry just because it's on a majority is UNSAFE (Raft §5.4.2):
    // a future leader could still overwrite it. We commit old entries only
    // indirectly, once a current-term entry above them reaches a majority.
    if (majorityIndex > commitIndex && termAt(majorityIndex) == currentTerm) {
        commitIndex = majorityIndex;
        signalApplyLoop();
    }
}
```

This guards against the famous **Figure 8 anomaly** in the Raft paper (§7.1).

### 5.5 Bootstrapping a 3-node etcd cluster (config-as-code)

```bash
# Run on node1 (repeat on node2/node3 with their own --name and --listen addrs).
etcd \
  --name node1 \
  --initial-advertise-peer-urls http://10.0.0.1:2380 \
  --listen-peer-urls http://10.0.0.1:2380 \
  --listen-client-urls http://10.0.0.1:2379,http://127.0.0.1:2379 \
  --advertise-client-urls http://10.0.0.1:2379 \
  --initial-cluster node1=http://10.0.0.1:2380,node2=http://10.0.0.2:2380,node3=http://10.0.0.3:2380 \
  --initial-cluster-state new \
  --initial-cluster-token my-etcd-cluster-1 \
  --heartbeat-interval 100 \
  --election-timeout 1000 \
  --quota-backend-bytes 8589934592   # 8 GiB; raise from the ~2GiB default for K8s
# Verify leadership and raft state:
#   etcdctl --endpoints=10.0.0.1:2379,10.0.0.2:2379,10.0.0.3:2379 \
#           endpoint status --cluster -w table
```

### 5.6 Safely adding a node via the learner pattern (etcd)

```bash
# 1. Add the new node as a LEARNER first. Learners receive the log but do NOT
#    vote and do NOT count toward quorum — so adding them can't hurt availability.
etcdctl member add node4 --peer-urls=http://10.0.0.4:2380 --learner

# 2. Start node4 with --initial-cluster-state existing pointing at the cluster.
#    Watch it catch up:
etcdctl endpoint status --cluster -w table   # node4's RAFT INDEX climbs toward leader's

# 3. Once caught up (RAFT INDEX ~= leader's), PROMOTE it to a full voter.
#    etcd refuses to promote a learner that is still too far behind — a built-in
#    guard against shrinking your effective fault tolerance.
etcdctl member promote <node4-member-id>
```

### 5.7 KRaft controller quorum (config) and a metadata read

```properties
# controller.properties — a dedicated 3-node KRaft controller quorum.
process.roles=controller
node.id=1
controller.quorum.voters=1@ctrl1:9093,2@ctrl2:9093,3@ctrl3:9093
listeners=CONTROLLER://:9093
controller.listener.names=CONTROLLER
log.dirs=/var/lib/kafka/metadata
```

```bash
# Initialize the cluster's metadata storage with a shared cluster UUID.
KAFKA_CLUSTER_ID=$(bin/kafka-storage.sh random-uuid)
bin/kafka-storage.sh format -t $KAFKA_CLUSTER_ID -c controller.properties

# Inspect the Raft metadata quorum (who is the active controller / leader):
bin/kafka-metadata-quorum.sh --bootstrap-controller ctrl1:9093 describe --status
```

### 5.8 A CRDT alternative — when you *don't* need consensus (avoiding the cost)

For a shopping cart or a hit counter, you can replicate without agreeing on order, using a CRDT. Here's a **G-Counter** (grow-only counter) — each node increments only its own slot; merge takes the max per slot. No leader, no quorum, no consensus round trips.

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Grow-only counter CRDT: increments commute, replicas converge without consensus. */
public class GCounter {
    private final String nodeId;
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    public GCounter(String nodeId) { this.nodeId = nodeId; }

    /** A node only ever increments ITS OWN entry — no coordination needed. */
    public void increment(long delta) {
        counts.merge(nodeId, delta, Long::sum);
    }

    /** Current value = sum of all nodes' contributions. */
    public long value() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Merge another replica's state. Commutative, associative, idempotent:
     *  apply in any order, any number of times — replicas always converge. */
    public void merge(GCounter other) {
        other.counts.forEach((node, c) -> counts.merge(node, c, Math::max));
    }
}
```

**Teaching point:** because increments commute and merge is `max`-per-node, replicas can sync **asynchronously, in any order, even after partitions**, and always converge to the same total — **no consensus, no leader, no quorum round trip, fully available during partitions (AP).** The cost: you give up *order* and *linearizable reads*; you can't express "decrement only if balance ≥ amount" (that needs consensus or a CRDT with more structure like a PN-Counter plus an external invariant).

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Every committed write costs ≥ 1 round-trip to a quorum + ≥ 1 `fsync`.** Latency floor ≈ (slowest-of-the-fastest-majority RTT) + disk sync time. For a 3-node cluster in one AZ, that's often **single-digit milliseconds**; across regions it can be **tens to >100 ms** (e.g., US↔EU RTT ~80–100 ms means cross-region consensus writes are inherently slow).
- **Batching:** group many client commands into one `AppendEntries` and one `fsync`. This is the single biggest throughput lever; it amortizes the fixed per-round-trip cost. (etcd, JRaft, and TiKV all batch aggressively.)
- **Pipelining:** the leader sends the next `AppendEntries` without waiting for the previous one's ack, keeping the network full. Combined with batching, this is how Raft reaches tens to hundreds of thousands of ops/sec.
- **Group commit / parallel fsync:** `fsync` the log for many entries at once.
- **Read scaling:** use **ReadIndex** or **lease reads** (§3.9) to serve linearizable reads without a log write. Use **serializable (local) reads** only where staleness is acceptable.
- **Separate the WAL disk:** put the Raft write-ahead log on its own fast disk (NVMe). etcd is *extremely* sensitive to disk `fsync` latency; a slow disk causes leader election storms (see §9).
- **Don't over-size the cluster.** Going from 3→5 nodes *increases* write latency (bigger quorum to wait for) while only raising fault tolerance from 1→2. Use 5 only when you genuinely need to survive 2 simultaneous failures; otherwise 3.

### 6.2 Correctness & concurrency

- **`fsync` persistent state (`currentTerm`, `votedFor`, log) before replying** to any RPC that depends on it. Skipping this is the most common way homegrown Raft implementations become *unsafe* (they can lose committed data or elect two leaders after a crash).
- **The state machine must be deterministic.** No wall-clock, no `random`, no nondeterministic iteration, no host-specific behavior inside `apply()`. If you need randomness or time, *put the value into the log entry* (decided by the leader) so all replicas apply the same value.
- **Idempotency / exactly-once-effect:** Raft guarantees an entry is applied once per replica, but a *client* may retry after a timeout and cause the same command to be logged twice. Attach a **client id + sequence number** to commands and have the state machine dedup, so retries are idempotent.
- **Never commit a prior-term entry directly** (§5.4 / §7.1).

### 6.3 Security

- **mTLS for peer and client traffic.** etcd, ZooKeeper, and Kafka all support TLS for both the consensus (peer) channel and the client channel — use it; the consensus log carries your most sensitive control-plane data.
- **Authentication & authorization:** etcd has RBAC; ZooKeeper has ACLs per znode + SASL/Kerberos; Kafka has ACLs. The control plane is a high-value target — locking it down prevents an attacker from rewriting cluster state.
- **Consensus assumes non-Byzantine nodes.** A compromised member can disrupt the cluster. If you need to tolerate *malicious* members, you need BFT (out of scope for typical infra).
- **Encryption at rest** for snapshots/WAL if they contain secrets.

### 6.4 Observability

Watch these signals (names vary by system):

| Signal | Why it matters | etcd metric (example) |
|---|---|---|
| **Leader changes / elections per minute** | Frequent elections = instability (slow disk, network, GC). | `etcd_server_leader_changes_seen_total` |
| **Has a leader?** | No leader = no writes. | `etcd_server_has_leader` |
| **Commit/`fsync` latency (p99)** | The single best predictor of consensus health. | `etcd_disk_wal_fsync_duration_seconds`, `etcd_disk_backend_commit_duration_seconds` |
| **Proposal failures / pending** | Backpressure / overload. | `etcd_server_proposals_failed_total`, `..._pending` |
| **Raft index / apply lag** | Followers falling behind. | compare `etcd_server_*` raft index across members |
| **DB size vs quota** | Approaching `NOSPACE` → read-only cluster. | `etcd_mvcc_db_total_size_in_bytes` |
| **Network peer RTT** | Slow peers slow commits. | `etcd_network_peer_round_trip_time_seconds` |

### 6.5 Cost

- **Operational cost:** consensus clusters are stateful, require careful capacity planning, backups, and careful upgrades (rolling, one node at a time, respecting quorum).
- **Latency cost:** the unavoidable quorum + fsync tax on every write.
- **Don't put bulk data in the consensus store.** etcd/ZooKeeper are for *small, critical metadata* (KB-scale values, limited total size). Storing large blobs or high-churn data there is an anti-pattern that blows past the DB quota and overwhelms the log.

### 6.6 Testing

- **Jepsen** — the industry-standard tool (by Kyle Kingsbury) for testing distributed systems under partitions, clock skew, and crashes, checking *linearizability* with the **Knossos/Elle** checkers. Many consensus systems (etcd, ZooKeeper, Kafka, CockroachDB, MongoDB) have published Jepsen reports — read them; they reveal real bugs and real guarantees.
- **Deterministic simulation testing (DST):** run the whole cluster in a single process with a simulated network/clock/disk and inject faults reproducibly. FoundationDB pioneered this; TigerBeetle and others use it. It catches consensus bugs that are nearly impossible to reproduce otherwise.
- **Model checking:** Raft's safety was specified and checked in **TLA+** (a formal specification language); the TLA+ spec is published. For homegrown implementations, a TLA+ model is the gold standard for confidence.
- **Chaos testing in staging:** kill the leader, partition a minority, slow a disk, induce a GC pause — verify the cluster stays *safe* and recovers liveness.

### 6.7 Production hardening checklist

- Use **odd** cluster sizes (3 or 5). Spread across **failure domains** (AZs/racks), but beware cross-region latency.
- **Dedicated fast disk** for the WAL; monitor `fsync` p99 like a hawk.
- Set **`election-timeout` ≈ 10× `heartbeat-interval`**, both comfortably above worst-case network RTT *and* worst-case GC pause. Too-tight timeouts cause election storms.
- Configure **auto-compaction** and watch the **DB quota**; have a runbook for the `NOSPACE` alarm.
- **Automate backups** (`etcd snapshot save`) and *test restores* regularly.
- **Rolling upgrades one node at a time**, never lose quorum.
- Add nodes as **learners** first; remove nodes cleanly via membership API (don't just `kill`).
- Tune **JVM GC** for ZooKeeper/Kafka — long stop-the-world pauses look like crashes and trigger elections.

### 6.8 Common anti-patterns

| Anti-pattern | Why it's bad |
|---|---|
| Even-sized clusters (2, 4) | Worse latency, no extra fault tolerance. |
| Treating a lock as safe without **fencing** | Pauses/partitions ⇒ two holders ⇒ corruption. |
| Storing bulk/high-churn data in etcd/ZooKeeper | Blows quota, floods the log, kills latency. |
| Election timeout too short | Election storms; cluster never stabilizes. |
| Nondeterministic state machine | Replicas diverge ⇒ silent data corruption. |
| Skipping `fsync` for "speed" | Loses committed data after a crash — unsafe. |
| One giant consensus group for everything | Doesn't scale; use sharded/multi-group. |
| Assuming a leader stays leader | A paused/partitioned leader can be deposed; re-check before acting. |
| Reading from a leader without ReadIndex/lease | Stale reads from a deposed leader; not linearizable. |
| Cross-region single Raft group on the write path | 80–150 ms write latency; usually the wrong design. |

---

## 7. Advanced topics & deep internals

### 7.1 The Figure 8 problem — why old entries aren't committed by count alone

The Raft paper's **Figure 8** shows a subtle scenario: an entry replicated to a majority can *still be overwritten* by a future leader **if** it was from a *previous* term and the current leader hasn't yet replicated any of *its own* term's entries. The fix (Raft §5.4.2): **a leader only marks an entry committed once it has replicated an entry from its *current* term to a majority.** Older entries then ride along (committed indirectly). This is why the leader-commit code (§5.4) checks `termAt(majorityIndex) == currentTerm`. Get this wrong and you have a silent safety violation that only manifests under specific crash/partition timing — the worst kind of bug.

### 7.2 Pre-Vote — preventing disruptive rejoins

**Problem:** a node partitioned away keeps timing out and incrementing its term (say to 50 while the cluster is at term 7). When it rejoins, its high term forces the healthy leader to step down — even though that node has a stale log and can't win. Result: a needless, disruptive election.

**Pre-Vote optimization:** before incrementing its term and starting a real election, a candidate first asks "*would* you vote for me?" without bumping terms. It only proceeds if a majority *would* grant the vote (i.e., it's up-to-date and could actually win). This prevents a partitioned, stale node from disrupting a healthy cluster. (Implemented in etcd, TiKV, JRaft.)

### 7.3 CheckQuorum & leader leases

**CheckQuorum:** a leader periodically verifies it can still reach a quorum; if not, it *steps down* proactively rather than continuing to act as a (possibly stale) leader. Combined with Pre-Vote, it greatly improves stability under partitions.

**Leader lease:** for fast local reads, a leader holds a lease valid for `electionTimeout` (minus a safety margin for clock drift); during it, no other node can have been elected (because election requires that timeout to elapse). The leader can then serve reads locally. **Caveat:** relies on bounded clock drift between nodes; a node whose clock jumps could break the assumption, so this is used cautiously.

### 7.4 Leadership transfer (graceful failover)

For zero-downtime maintenance, a leader can *transfer* leadership: it stops accepting new entries, brings a chosen follower fully up to date, then sends `TimeoutNow` to make that follower start an election immediately (it'll win because it's caught up and others are still in their timeout). This avoids the dead-air of waiting for an election timeout. (`etcdctl move-leader`.)

### 7.5 Flexible quorums (Paxos insight)

A deep result (Howard et al., "Flexible Paxos"): Paxos/Raft don't strictly need *majority* quorums — they need that **Phase-1 (leader-election) quorums and Phase-2 (replication) quorums intersect.** You can make replication quorums *smaller* (faster commits) at the cost of *larger* election quorums (slower, rarer elections). E.g., with 5 nodes, you could commit to any 2 nodes if elections require 4. Useful when writes are frequent and elections rare. Few production systems expose this, but it shows the *real* requirement is **quorum intersection**, not "majority."

### 7.6 Witness / arbiter replicas & weighted votes

To get the fault tolerance of an odd cluster without paying for a full third data replica, some systems add a **witness** (a lightweight voting-only member that stores metadata/votes but not full data). MongoDB's *arbiter* and various Raft systems' *witness* roles work this way. This helps tie-break elections cheaply but can reduce durability if misused (a witness can't supply lost data).

### 7.7 Joint consensus internals (the careful dance)

During joint consensus, the cluster operates under `C_old,new`. The subtle rules: configuration entries take effect **when appended** (not when committed); a leader that proposed `C_new` but crashed before committing it might be replaced by a leader from `C_old` — the protocol must handle a leader that isn't part of the *new* configuration (it steps down after committing `C_new`). These corner cases are exactly why single-server membership changes (§3.7) became the popular simplification.

### 7.8 Disk and storage internals

- **WAL (write-ahead log):** the Raft log is persisted as an append-only WAL; entries are `fsync`ed before acknowledgment. WAL files are segmented and recycled.
- **Snapshots & log truncation:** after a snapshot at index `i`, the WAL before `i` is truncated. etcd separately maintains its **MVCC backend** (a B+tree in BoltDB) for the key-value data, with its own compaction and `defrag`.
- **`fsync` is the bottleneck.** Modern systems batch and use group commit; some use `fdatasync`. SSD/NVMe write latency directly bounds consensus throughput.

> **MVCC (Multi-Version Concurrency Control)** — storing multiple versions of each key keyed by a revision number, so reads can see a consistent past snapshot without blocking writes. etcd uses MVCC so you can do consistent range reads and watch for changes since a revision. Relevant here because etcd's *log* (Raft) and its *store* (MVCC) are separate subsystems with separate compaction.

### 7.9 EPaxos and leaderless variants (lesser-known)

> **EPaxos (Egalitarian Paxos)** — a leaderless consensus protocol where any replica can commit commands; non-conflicting commands commit in **one round trip** with no leader bottleneck, and only *conflicting* commands need extra coordination. Benefit: no single leader hotspot, lower latency in geo-distributed setups (a client talks to its nearest replica). Cost: much more complex, harder to reason about; rarely used in mainstream infra but influential in research and in systems optimizing geo-latency. **Raft/Multi-Paxos trade this away for understandability.**

### 7.10 Hybrid Logical Clocks (HLC) — ordering without TrueTime hardware

> **HLC (Hybrid Logical Clock)** — combines a physical clock with a logical (Lamport) counter to produce timestamps that are close to wall-clock time but also strictly capture causal order, *without* needing GPS/atomic-clock hardware. CockroachDB and YugabyteDB use HLC (plus per-shard Raft) to approximate what Spanner gets from TrueTime, accepting a configurable max clock-offset assumption instead of specialized hardware.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Raft vs Multi-Paxos vs ZAB

| Dimension | Raft | Multi-Paxos | ZAB (ZooKeeper) |
|---|---|---|---|
| Designed for | Understandability | Generality (single-value proof) | Primary-backup atomic broadcast |
| Leadership | Strong leader, log flows leader→follower only | Distinguished proposer (leader) but log can flow any direction in theory | Strong leader |
| Election | Randomized timeouts, terms | Unspecified by paper; implementer's choice | Fast Leader Election, zxid-based |
| Spec completeness | Fully specified (incl. membership, snapshots) | Single-decree precise; Multi-Paxos sketchy | Specified for ZooKeeper's needs |
| Reconfiguration | Joint consensus or single-server | Implementer-defined | Dynamic reconfig (added later) |
| Where used | etcd, Consul, TiKV, CockroachDB, MongoDB, KRaft | Chubby, Spanner, Megastore | ZooKeeper |
| Reputation | "Same power as Paxos, far easier to implement right" | Powerful but error-prone in practice | Battle-tested, ZK-specific |

### 8.2 Consensus vs alternatives — what guarantee do you actually need?

| You need… | Use | Why |
|---|---|---|
| Linearizable updates to small critical state; one leader; ordered durable log | **Consensus (Raft/Paxos)** | Only consensus gives total order + linearizability + fault tolerance. |
| High availability, can tolerate temporary inconsistency, data merges naturally | **CRDTs** | Available during partitions (AP), no coordination, eventual convergence. |
| Tunable consistency, no single leader, high write throughput | **Dynamo-style quorums (`R+W>N`)** | Cheaper than consensus; sloppy quorums + hinted handoff for availability. But beware: quorum *alone* gives **last-write-wins / read-repair**, not linearizable order. |
| Just durability + ordering within one shard, with a primary | **Primary-backup / chain replication** | Simpler; but needs an external consensus service to elect the primary safely (else split brain). |
| Tolerate *malicious* nodes | **BFT consensus (PBFT, Tendermint)** | Needs 3f+1 nodes; for blockchains/untrusted participants. |
| Mutual exclusion across processes | **Lease/lock on a consensus service + fencing token** | Consensus gives the safe lock; fencing guards against pauses. |

> **Dynamo-style quorum (`R + W > N`)** — a *leaderless* replication scheme (Amazon Dynamo, Cassandra, Riak). Write to `W` of `N` replicas, read from `R`, with `R + W > N` so read and write sets overlap, guaranteeing a read sees at least one copy of the latest acknowledged write. This is a *read-write quorum* for a *single object*, **not** consensus: it does not establish a total order across operations and typically resolves conflicts by last-write-wins or vector clocks. Cheaper and more available than consensus, but weaker guarantees.

### 8.3 When you do NOT need consensus

- **Embarrassingly partitionable / commutative data:** counters, sets, registers that can use CRDTs.
- **Read-mostly, staleness-tolerant data:** caches, analytics, recommendations — eventual consistency is fine.
- **Single-object, no cross-object invariant:** a quorum store suffices; you don't need ordered multi-key transactions.
- **You can push the hard part elsewhere:** e.g., use an *existing* consensus service (etcd, ZooKeeper) for the tiny critical bit (leader election) and run everything else without consensus. **This is the most common pragmatic answer:** don't build consensus; use a battle-tested one for the 1% that needs it.

### 8.4 Cluster sizing decision rule

- **3 nodes:** default. Tolerates 1 failure. Lowest write latency. Use for most control planes.
- **5 nodes:** tolerate 2 failures *or* survive losing a node *during* a rolling upgrade. Higher write latency, more resilient. Use for critical, high-availability clusters (e.g., large Kubernetes etcd).
- **7+:** rarely worth it; latency and election cost grow. Consider sharding into multiple groups instead.
- **Never even numbers.** Never 1 (no fault tolerance). Never 2 (worst of both worlds).

### 8.5 Single region vs multi-region

| Topology | Write latency | Fault tolerance | Notes |
|---|---|---|---|
| 3 nodes, 1 AZ | lowest (sub-ms to few ms) | survives 1 node | AZ failure kills it. |
| 3 nodes, 3 AZs (1 region) | low-moderate (intra-region RTT) | survives 1 AZ | Common K8s etcd layout. |
| 5 nodes across regions | high (cross-region RTT in every commit) | survives 1 region | Slow writes; consider per-region groups + async replication, or Spanner-style design. |

---

## 9. Failure modes & debugging

### 9.1 Leader election storms (the classic etcd outage)

**Symptom:** frequent leader changes, write latency spikes, clients timing out. `etcd_server_leader_changes_seen_total` climbing.

**Common causes & diagnosis:**
- **Slow disk `fsync`.** Check `etcd_disk_wal_fsync_duration_seconds` p99. If it's high (e.g., >100 ms), the leader can't persist heartbeats/log fast enough, followers time out and start elections. **The #1 real-world etcd failure.** Fix: faster/dedicated NVMe disk, isolate from noisy neighbors.
- **Network latency/loss between peers.** Check `etcd_network_peer_round_trip_time_seconds`. Fix: co-locate, raise timeouts.
- **Election timeout too tight relative to RTT/GC.** Fix: increase `--election-timeout`.
- **CPU starvation / GC pauses** (esp. ZooKeeper/Kafka JVM): a stop-the-world pause looks like a crash. Fix: GC tuning, more CPU, isolate.

### 9.2 No leader / cluster unavailable

**Symptom:** all writes fail; `etcd_server_has_leader == 0`.

**Causes:** lost quorum (≥ majority nodes down or partitioned). With 3 nodes, losing 2 = no quorum = no writes (by design — CP). Diagnose with `etcdctl endpoint status --cluster` / `endpoint health`. **Recovery:** restore the down nodes, or in a true disaster (majority permanently lost) **rebuild from a snapshot** (`etcdctl snapshot restore`) — this *forges a new cluster* and is a last resort; any writes not in the snapshot are lost.

### 9.3 Split brain / "two leaders"

True Raft/Paxos **cannot** have two leaders committing in the same term (quorum + one-vote-per-term prevent it). But you *can* have a **stale leader** (deposed, doesn't know it) briefly serving stale reads — prevented by ReadIndex/lease. *Application-level* split brain happens when code treats leadership as permanent and keeps acting after losing it (the §5.1 / §6.8 anti-pattern). **Fix:** fencing tokens + re-check leadership before every critical action.

### 9.4 The `NOSPACE` alarm (etcd)

**Symptom:** cluster goes **read-only**; writes rejected with `mvcc: database space exceeded`. **Cause:** DB exceeded `--quota-backend-bytes` (default ~2 GiB) because history wasn't compacted. **Fix:** `etcdctl compaction <rev>`, then `etcdctl defrag` (per member), then `etcdctl alarm disarm`. **Prevent:** enable auto-compaction; monitor `etcd_mvcc_db_total_size_in_bytes`; don't store bulk data.

### 9.5 Slow followers / apply lag

**Symptom:** one follower's raft/applied index lags; reads from it (serializable mode) return stale data; it can't be safely promoted. **Causes:** slow disk/CPU on that node, network. **Fix:** investigate that node's resources; if hopelessly behind, the leader will send an `InstallSnapshot`; if a learner, etcd refuses promotion until caught up.

### 9.6 The "lost committed write" bug in homegrown implementations

**Symptom:** an acknowledged write disappears after a crash. **Cause:** the implementation acked before `fsync`, or committed a prior-term entry by count (Figure 8), or mishandled log truncation. **Diagnosis:** this is exactly what **Jepsen/Elle** catches — run them. **Fix:** strict `fsync`-before-ack, current-term-commit rule, careful truncation; ideally validate against a TLA+ model.

### 9.7 Real-world incidents to learn from

- **Kubernetes/etcd disk-latency outages:** numerous postmortems trace cluster-wide K8s outages to etcd `fsync` latency on shared/slow disks. Lesson: etcd disk performance is a first-class SLO.
- **Cloudflare 2020 etcd-related outage** and others: control-plane consensus stores are single points of *coordination*; their slowdown cascades widely. Lesson: protect, monitor, and isolate the consensus tier.
- **Jepsen reports** on ZooKeeper, etcd, Kafka, CockroachDB, MongoDB: each surfaced concrete consistency bugs that were subsequently fixed. Lesson: even mature systems have had consensus-adjacent bugs; rigorous testing matters.

### 9.8 A debugging playbook (etcd, but generalizable)

```bash
# 1. Who's leader? Are all members healthy and at the same raft index?
etcdctl --endpoints=$ALL endpoint status --cluster -w table
etcdctl --endpoints=$ALL endpoint health

# 2. Is there a leader at all, and is leadership flapping?
#    Scrape Prometheus: etcd_server_has_leader, etcd_server_leader_changes_seen_total

# 3. Disk health (the usual culprit):
#    p99 of etcd_disk_wal_fsync_duration_seconds and
#    etcd_disk_backend_commit_duration_seconds — should be << election timeout.

# 4. Are proposals failing / piling up?
#    etcd_server_proposals_failed_total, etcd_server_proposals_pending

# 5. Space alarms?
etcdctl alarm list
# 6. Peer network RTT between members:
#    etcd_network_peer_round_trip_time_seconds
```

---

## 10. Interview drill

### Q1. What is distributed consensus, and what are its three core guarantees?
**Answer:** Getting a set of unreliable, independently-failing nodes communicating over an unreliable network to agree on a value or an ordered sequence of values. The guarantees: **agreement** (no two correct nodes decide differently), **validity** (the decided value was proposed), and **termination** (every correct node eventually decides). In practice it underpins replicated state machines: all replicas apply the same commands in the same order.
- *Probe: why is agreement on a single value enough to build a database?* Because you chain it: agree on an *ordered log* of commands (each slot is a consensus instance / Multi-Paxos or a Raft log entry), then deterministically apply the log — that replicates any state machine.
- *Probe: which guarantee do real systems sacrifice, and when?* Termination/liveness — under bad network conditions (partition) a CP system stops making progress to preserve safety. Safety is never sacrificed.
- *Probe: difference between consensus and atomic broadcast?* They're equivalent in power — total-order broadcast = consensus on the order of each message; ZAB is framed as atomic broadcast, Raft as a replicated log; you can build one from the other.

### Q2. Explain the FLP impossibility result. Does it mean consensus is impossible?
**Answer:** FLP (1985) proves that in a *purely asynchronous* system (no bounds on message delay or clock speed), no deterministic protocol can guarantee both safety and termination if even one node may crash — because you can't distinguish a crashed node from a slow one, and an adversarial scheduler can keep the system undecided forever. It does **not** make consensus impossible; real algorithms sidestep it by assuming **partial synchrony** (eventual timing bounds) and using **randomization** (e.g., Raft's randomized election timeouts), preserving safety always and liveness when the network behaves.
- *Probe: how exactly does Raft's randomized timeout relate to FLP?* The FLP-style failure needs adversarial symmetry (perfectly timed split votes); randomized timeouts make repeated ties exponentially unlikely, restoring practical liveness.
- *Probe: what's partial synchrony?* Asynchronous most of the time, but after some unknown "global stabilization time," delays are bounded — realistic for real networks that misbehave then recover.
- *Probe: how does CAP relate?* CAP's "can't have C and A during a partition" is a sibling result; consensus systems choose C (CP), becoming unavailable on the minority side during partitions.

### Q3. Walk me through Raft leader election.
**Answer:** Followers expect periodic heartbeats. If a follower's *randomized* election timeout elapses with no heartbeat, it becomes a **candidate**, increments `currentTerm`, votes for itself, and sends `RequestVote` (with its last log index/term) to all peers in parallel. A peer grants its vote if the candidate's term is current/newer, it hasn't voted this term, and the candidate's log is at least as up-to-date as its own. A candidate that gets a **majority** becomes leader and sends heartbeats; if it sees a valid leader/higher term it reverts to follower; a split vote triggers a new randomized-timeout election.
- *Probe: why randomized timeouts?* To avoid perpetual split votes — they desynchronize candidates so one fires first and wins.
- *Probe: what stops two leaders in the same term?* Majority quorum + one-vote-per-term: two majorities overlap, and the shared node won't vote twice, so two candidates can't both get majorities in one term.
- *Probe: what's the election restriction and why?* Voters refuse candidates with a less-up-to-date log (higher lastLogTerm wins; ties broken by longer log). It guarantees the new leader holds all committed entries (Leader Completeness), so committed data is never lost.

### Q4. How does Raft replicate the log and decide an entry is committed?
**Answer:** The leader appends a client command to its log, then sends `AppendEntries` with `prevLogIndex`/`prevLogTerm` (a consistency check) to followers. Followers reject if their log doesn't match at `prevLogIndex` (leader then backs up `nextIndex` and retries, repairing the divergent suffix); otherwise they append, `fsync`, and ack. When the entry is stored on a **majority** (tracked via `matchIndex[]`) **and is from the leader's current term**, the leader marks it committed, advances `commitIndex`, applies it, and replies to the client. Followers learn `commitIndex` via subsequent `AppendEntries` and apply in order.
- *Probe: why only commit current-term entries directly?* Figure 8: an old-term entry on a majority can still be overwritten by a future leader; committing it by count alone is unsafe. Committing a current-term entry on top makes the old ones safe (committed indirectly).
- *Probe: what's the Log Matching Property?* Same (index, term) ⇒ same command and identical preceding log; maintained inductively by the consistency check.
- *Probe: difference between committed and applied?* Committed = guaranteed durable on a majority; applied = executed against the local state machine. `lastApplied` chases `commitIndex`.

### Q5. How are linearizable reads served without giving stale data?
**Answer:** A naive local read from the leader can be stale if the leader was silently deposed. Solutions: (1) **log the read** as a no-op and commit it (correct, slow); (2) **ReadIndex** — record current `commitIndex`, confirm leadership via a heartbeat round to a quorum (no disk write), wait until applied up to that index, then read (linearizable, cheaper); (3) **lease reads** — serve locally while a time-bounded leader lease is valid, relying on bounded clock drift (fastest, riskier).
- *Probe: what can break lease reads?* Clock skew/jumps violating the bounded-drift assumption.
- *Probe: serializable vs linearizable reads in etcd?* Serializable reads are local (may be stale, fast); linearizable reads use ReadIndex.

### Q6. How does Raft change cluster membership safely?
**Answer:** Naive config switches can create two disjoint majorities (split brain). Raft uses either **joint consensus** (a transitional `C_old,new` requiring majorities of *both* old and new configs, so no two disjoint majorities exist) or, more commonly, **single-server changes** (add/remove one node at a time, which provably can't create disjoint majorities). New nodes typically join as **non-voting learners** to catch up before counting toward quorum.
- *Probe: why learners?* So adding a fresh, behind node doesn't enlarge the quorum and hurt availability before it's caught up.
- *Probe: why is single-server change simpler than joint consensus?* Adding/removing one node can't split the cluster into two independent majorities, so you skip the transitional config entirely.

### Q7. What is snapshotting and why is it needed?
**Answer:** The log grows unbounded; snapshotting captures the state machine up to `lastIncludedIndex`/`Term` and discards the log before it, bounding storage and speeding restart/recovery. A leader uses `InstallSnapshot` to bring a far-behind follower (one whose needed entries were discarded) up to date by shipping the snapshot instead of entries.
- *Probe: how to snapshot without blocking?* Copy-on-write / fork the state so the live machine keeps serving.
- *Probe: tuning tradeoff?* Too frequent = wasted I/O; too rare = huge logs, slow recovery.

### Q8 (senior signal). Why was Raft created when Paxos already existed? Is one "more correct"?
**Answer:** Both are equally powerful and equally *safe*. Raft exists for **understandability and implementability**: Multi-Paxos (what you actually need) is under-specified — leader election, log management, reconfiguration, and recovery are left to implementers, so real Paxos systems diverge and are error-prone, and they usually end up adding a leader anyway (looking like Raft). Raft prescribes a **strong single leader** (log flows one way), **terms** as a logical clock, **randomized election**, and **fully specified** replication/membership/snapshotting. So neither is "more correct," but Raft is far easier to implement *correctly*, which is why post-2014 systems overwhelmingly chose it.
- *Probe: where is Paxos still used?* Chubby, Spanner, Megastore (Google) — older, battle-tested, with deep in-house expertise.
- *Probe: what does Paxos's flexibility buy you?* Theoretical generality (leaderless variants like EPaxos, flexible quorums) at the cost of complexity; rarely worth it for typical infra.

### Q9 (senior signal). When would you deliberately NOT use consensus, and what would you use instead?
**Answer:** When you don't need a total order or linearizability — e.g., commutative/mergeable data (use **CRDTs**, which stay available during partitions and converge), staleness-tolerant read-mostly data (eventual consistency), or single-object tunable consistency (**Dynamo-style `R+W>N` quorums**). Consensus costs a quorum round trip + `fsync` on every write and becomes unavailable during partitions (CP). The pragmatic pattern: use an *existing* consensus service for the tiny critical bit (leader election, config) and keep the high-volume data plane consensus-free.
- *Probe: difference between a read/write quorum and consensus?* `R+W>N` guarantees a read overlaps a write for a *single object* (last-write-wins/vector clocks), but gives **no total order** across operations — not linearizable across the system; consensus does.
- *Probe: a case where CRDTs fail?* Invariants that aren't naturally commutative — e.g., "balance must stay ≥ 0" needs coordination/consensus, not a plain CRDT.

### Q10 (senior signal). How do you size and lay out a consensus cluster for a critical control plane, and what are the latency/fault-tolerance tradeoffs?
**Answer:** Use **odd** sizes: 3 (tolerates 1, lowest latency — default) or 5 (tolerates 2, or survives a node loss *during* a rolling upgrade, at higher write latency). Never even (no extra tolerance, larger quorum) or 1/2. Spread across failure domains (AZs) but mind that *every commit pays the slowest-of-the-fastest-majority RTT* — cross-region groups add 80–150 ms per write, so prefer per-region groups + async replication or a Spanner-style design for global data. Put the WAL on dedicated fast NVMe (fsync latency is the throughput ceiling and the top cause of election storms), set `election-timeout ≈ 10× heartbeat` and above worst-case RTT/GC, and don't store bulk data in it.
- *Probe: why does 5 nodes have higher write latency than 3?* Each commit must reach a *larger* majority (3 of 5 vs 2 of 3), so it waits on more/slower acks.
- *Probe: how do you scale consensus to thousands of nodes?* You don't run one big group — you shard into many small (3/5-node) consensus groups (multi-raft) or use a small group to coordinate a larger fleet.

### Q11. Why is a fencing token needed even when you have a consensus-backed lock?
**Answer:** A consensus lock guarantees at most one holder *at the protocol level*, but a holder can stall (GC pause / partition) past its lease; another client then legitimately acquires the lock, and the stalled client may wake up and act, believing it still holds it. A **monotonically increasing fencing token** issued with the lock, checked by downstream storage (reject any token older than the latest seen), prevents the stale holder's writes from taking effect.
- *Probe: where does the token come from in etcd?* The lock key's mod_revision (monotonic).
- *Probe: why isn't a lease TTL alone enough?* Clock skew and pauses make TTLs unreliable for safety; fencing makes it deterministic at the resource.

### Q12. What's the difference between Raft and ZAB, and where does each run?
**Answer:** Both are strong-leader, majority-quorum, ordered-log protocols and are conceptually very similar. ZAB (in **ZooKeeper**) is framed as atomic broadcast with primary-order guarantees and zxid-based ordering, predating Raft. Raft (etcd, Consul, TiKV, CockroachDB, KRaft, MongoDB) emphasizes a fully specified, understandable design. Practically you choose them via the *system* you adopt rather than implementing from scratch.
- *Probe: is ZAB Paxos?* No — it's a distinct atomic-broadcast protocol, though it shares the leader+quorum+log shape.
- *Probe: what replaced ZooKeeper in Kafka and why?* KRaft (Kafka Raft) — to remove the external ZooKeeper dependency and improve metadata scalability/failover.

---

## 11. Glossary

- **Acceptor** — Paxos role; votes on proposals. A majority of acceptors choosing a value makes it chosen.
- **Agreement** — safety guarantee: no two correct nodes decide different values.
- **AppendEntries** — Raft RPC the leader uses to replicate log entries and send heartbeats.
- **Apply / applied** — executing a committed log entry against the local state machine; `lastApplied` tracks how far.
- **Asynchronous model** — timing model with no bounds on delays/clock speed; the model in which FLP holds.
- **Atomic broadcast** — totally-ordered, reliable message delivery to all nodes; equivalent in power to consensus (ZAB).
- **Availability (CAP)** — every request gets a non-error response.
- **BFT (Byzantine Fault Tolerant)** — tolerates arbitrary/malicious node behavior; needs 3f+1 nodes for f faults.
- **Bivalent / univalent** — FLP terms; a state where both/one outcomes are still reachable.
- **CAP theorem** — during a partition, choose Consistency or Availability, not both.
- **Candidate** — Raft state of a node soliciting votes to become leader.
- **CheckQuorum** — leader steps down if it can't reach a quorum (stability optimization).
- **Chubby** — Google's Paxos-based lock service (inspired ZooKeeper).
- **Commit / commitIndex** — an entry is committed when replicated to a majority (and from the leader's current term); `commitIndex` is the highest such index.
- **Consensus** — agreeing on a value/ordered sequence despite faults.
- **Consistency (CAP)** — every read sees the latest write.
- **CRDT (Conflict-free Replicated Data Type)** — data type whose replicas merge commutatively and converge without coordination (no consensus).
- **CockroachDB / YugabyteDB** — Spanner-inspired SQL DBs using per-shard Raft + HLC.
- **Crash-recovery / crash-stop** — failure models where nodes halt (and may restart with durable state) but never lie.
- **Distinguished proposer** — Multi-Paxos's stable leader that owns Phase 1 for a slot range.
- **Determinism (state machine)** — same inputs ⇒ same outputs/state on every replica; required for RSM.
- **Dynamo-style quorum (`R+W>N`)** — leaderless single-object read/write quorum; not a total order, not consensus.
- **EPaxos (Egalitarian Paxos)** — leaderless consensus; non-conflicting commands commit in one round trip.
- **etcd** — Go, Raft-based KV store; Kubernetes' backing store; reference Raft library.
- **External consistency** — Spanner's guarantee (stronger than linearizable, spans the whole DB) via TrueTime.
- **Failure domain** — a unit (rack/AZ/region) that can fail together; spread replicas across them.
- **Fencing token** — monotonic number issued with a lock; downstream resources reject stale tokens to prevent split-brain writes.
- **Figure 8 problem** — Raft scenario showing why prior-term entries can't be committed by replica count alone.
- **Flexible quorums** — Paxos result: election and replication quorums need only intersect, not both be majorities.
- **FLP impossibility** — no deterministic async protocol guarantees safety + termination with one crash possible.
- **fsync** — force buffered writes to durable storage; mandatory before acking persisted consensus state; usually the throughput bottleneck.
- **G-Counter / PN-Counter** — grow-only / increment-decrement CRDT counters.
- **Heartbeat** — empty AppendEntries the leader sends to assert liveness and suppress elections.
- **HLC (Hybrid Logical Clock)** — physical+logical clock capturing causal order without special hardware.
- **InstallSnapshot** — Raft RPC to ship a snapshot to a far-behind follower.
- **Jepsen / Elle / Knossos** — tools for testing distributed systems and checking linearizability under faults.
- **Joint consensus** — Raft transitional config requiring majorities of both old and new configs during reconfiguration.
- **KRaft (Kafka Raft)** — Kafka's built-in Raft metadata quorum replacing ZooKeeper.
- **Leader** — Raft state handling all client writes and replication; at most one per term.
- **Leader Completeness** — committed entries appear in all future leaders' logs (via the election restriction).
- **Learner / non-voting member** — receives the log to catch up but doesn't vote or count toward quorum.
- **Lease read / leader lease** — serving reads locally while a time-bounded leadership lease is valid.
- **Linearizability** — strongest single-object consistency: operations appear atomic and respect real-time order.
- **Log / log entry / index** — append-only ordered sequence; each entry has an index, term, and command.
- **Log Matching Property** — same (index, term) ⇒ identical command and identical prefix.
- **Majority quorum** — `⌊N/2⌋+1` nodes; any two majorities intersect.
- **matchIndex / nextIndex** — leader's per-follower replication progress trackers.
- **MVCC** — multi-version concurrency control; keeps versioned data keyed by revision (etcd's store).
- **Multi-Paxos** — Paxos with a stable leader so commands need only Phase 2 (one round trip) in steady state.
- **Partial synchrony** — async most of the time, eventually bounded; the realistic model algorithms target.
- **Paxos** — Lamport's consensus protocol (Prepare/Promise, Accept/Accepted) using proposal numbers + majorities.
- **Pre-Vote** — a candidate checks whether it *could* win before bumping its term, preventing disruptive elections.
- **Proposal number** — globally unique, monotonic identifier ordering Paxos proposals.
- **Proposer / Learner** — Paxos roles: proposes values / learns the chosen value.
- **Quorum** — a subset large enough that any two such subsets overlap; usually a majority.
- **Raft** — understandable, strong-leader consensus algorithm (election, log replication, safety).
- **ReadIndex** — linearizable read technique: confirm leadership via heartbeat quorum, then read at the recorded commit index.
- **Reconfiguration / membership change** — safely changing the cluster's node set.
- **Replicated state machine (RSM)** — replicas applying the same ordered log to deterministic state machines.
- **Serializable read** — local, possibly stale read (etcd `--consistency=s`).
- **Snapshot** — compacted state-machine image replacing old log entries.
- **Spanner** — Google's globally distributed SQL DB; per-shard Paxos + TrueTime for external consistency.
- **Split brain** — two sub-groups both acting as authority, causing divergence; quorums prevent the protocol-level case.
- **Stale read** — returning out-of-date data; prevented by ReadIndex/lease.
- **Synchronous model** — known bounds on delays/clocks; consensus is easy but unrealistic.
- **Term** — Raft's monotonic logical clock; each term has at most one leader; higher term wins, lower is rejected.
- **TimeoutNow** — RPC telling a follower to start an election immediately (graceful leadership transfer).
- **TLA+** — formal specification language used to model-check Raft's safety.
- **TrueTime** — Google's GPS/atomic-clock API returning a bounded time interval; basis of Spanner's commit-wait.
- **Validity** — safety guarantee: the decided value was actually proposed.
- **WAL (write-ahead log)** — durable append-only log persisted (and fsynced) before acknowledgment.
- **Witness / arbiter** — lightweight voting-only member used to cheaply tie-break elections.
- **ZAB (ZooKeeper Atomic Broadcast)** — ZooKeeper's strong-leader atomic-broadcast protocol.
- **ZooKeeper / znode / Curator** — coordination service / its hierarchical data nodes / a high-level JVM client with recipes.
- **zxid** — ZooKeeper transaction id encoding epoch + counter, totally ordering writes.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Core idea:** consensus = a fault-tolerant, linearizable, ordered, durable **log**; replicas apply it deterministically (RSM).

**Quorum math:** `N` nodes → majority = `⌊N/2⌋+1`; tolerate `f` failures with `2f+1` nodes. **Always odd.** 3 → tol 1; 5 → tol 2.

**FLP:** async + 1 crash ⇒ can't guarantee safety *and* termination. Real systems use **partial synchrony + randomization**; keep **safety always**, **liveness when network is healthy**. Consensus is **CP** (PACELC PC/EC).

**Raft in one breath:** randomized election timeout → candidate bumps **term**, RequestVote → majority wins → **leader** heartbeats. Writes: leader appends → AppendEntries (prevLogIndex consistency check) → majority + **current term** ⇒ commit → apply → reply. Higher term wins; election restriction keeps committed data; commit old entries only indirectly (Figure 8).

**Reads:** local = stale risk; **ReadIndex** (heartbeat-quorum, no disk) or **lease** (clock-dependent) for linearizable.

**Membership:** single-server changes (common) or joint consensus; new nodes as **learners** first.

**Compaction:** snapshot + truncate log; `InstallSnapshot` for far-behind followers.

**Paxos:** Phase1 Prepare/Promise + Phase2 Accept/Accepted, proposal numbers, majority; **Multi-Paxos** = stable leader skips Phase 1. **Raft created for understandability/implementability** (Paxos under-specified).

**Where used:** etcd (K8s), ZooKeeper (ZAB), Kafka **KRaft**, Spanner/Chubby (Paxos), Consul/TiKV/CockroachDB/MongoDB (Raft).

**Don't need consensus:** CRDTs (commutative/mergeable, AP), Dynamo `R+W>N` quorums (single-object, no total order), eventual consistency (staleness OK).

**Cost:** every write = quorum RTT + `fsync`. Intra-AZ: ms; cross-region: 80–150 ms. **fsync latency = #1 cause of election storms.** Batch + pipeline for throughput. Don't store bulk data; mind the DB quota (etcd ~2 GiB default → NOSPACE → read-only).

**Key etcd knobs:** `--heartbeat-interval 100ms`, `--election-timeout 1000ms` (≈10×), `--quota-backend-bytes ~2GiB`. **Rule:** `electionTimeout >> heartbeat >> RTT`, and above worst-case GC.

**Locks need fencing tokens.** Leadership is not permanent — re-check before acting.

### 12.2 Self-test (no answers — recall actively)

1. A 6-node cluster: what's the quorum, how many failures does it tolerate, and why is 6 a poor choice compared to 5 *and* 7?
2. Precisely why can a Raft leader **not** mark an entry from a *previous* term committed just because it's stored on a majority? Sketch the scenario that goes wrong.
3. Your etcd cluster shows `leader_changes_seen_total` climbing every few seconds and write p99 spiking to seconds. List the top three causes in order of likelihood and the exact metric you'd check for each.
4. Design a distributed lock for "only one worker writes to file `X`" that stays correct even if a worker GC-pauses for 30 seconds. Name every mechanism you use and what failure each one defends against.
5. You need a globally available "likes" counter that must never reject a write, even during a regional partition, and slight staleness is fine. Would you use consensus? If not, what exactly, and which guarantees are you giving up?
6. Explain how Raft's randomized election timeout is a practical workaround for the FLP impossibility result. What property of the FLP adversary does it defeat?
7. Compare ReadIndex vs lease reads vs log-the-read for serving a linearizable read: cost, correctness assumptions, and when you'd pick each.
8. Why did Kafka replace ZooKeeper with KRaft, and what changed architecturally (including the push-vs-pull replication detail)?

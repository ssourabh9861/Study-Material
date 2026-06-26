# Distributed Locks

*An exhaustive engineering-handbook chapter for senior JVM/backend developers.*

---

## 1. Overview & where it fits

### 1.1 What a distributed lock is

A **lock** is a coordination primitive that guarantees **mutual exclusion**: at most one party holds the lock at a time, so the protected operation (the **critical section**) is not executed concurrently by two parties. On a single machine, the JVM gives you `synchronized`, `ReentrantLock`, semaphores, and the like — these work because all threads share the same memory and the same scheduler, and the operating system can atomically arbitrate who wins.

A **distributed lock** extends mutual exclusion *across process and machine boundaries*. The "parties" are now separate processes — possibly on different hosts, in different data centers, written in different languages — that share no memory and no common scheduler. They coordinate through an **external, shared, authoritative store** (Redis, ZooKeeper, etcd, a relational database, Consul, etc.) that everyone can reach over the network. The store is the single source of truth for "who holds the lock."

> **Mutual exclusion (mutex):** the property that a resource is used by only one actor at a time. The classic motivating example is two threads incrementing a shared counter: without mutual exclusion the increments interleave and updates are lost.
>
> **Critical section:** the span of code or the operation that must not run concurrently — e.g., "read balance, subtract, write balance," or "generate the next invoice number," or "run this nightly batch job exactly once."

### 1.2 The problem it solves

You reach for a distributed lock when **multiple independent processes might try to do the same exclusive thing at the same time**, and doing it concurrently is wrong. Canonical cases:

- **Singleton work in a horizontally scaled service.** You run 10 replicas of a worker, but a particular cron-like job ("send the daily digest," "rebuild the cache," "reconcile ledgers") must run on exactly one replica per tick.
- **Leader election.** A cluster of identical nodes must pick one *leader* to coordinate (assign partitions, drive a state machine, be the writer). A distributed lock is the lowest-level building block for leader election: "the node holding the lock is the leader."
- **Serializing access to an external resource that has no locking of its own.** A legacy device, a third-party API with no idempotency, a file in object storage, a printer.
- **Preventing duplicate effectful work.** Two consumers pick up the same message; you want only one to perform the side effect.

### 1.3 When you reach for it — and when you should not

A distributed lock is a **last-resort coordination tool**, not a default. The single most important framing in this entire chapter:

> **A distributed lock is correct for *efficiency*, but dangerous for *correctness*.**

Martin Kleppmann's well-known distinction (we cover his Redlock critique in depth in §3.6 and §7):

- **Efficiency use:** "I'd *prefer* not to do this work twice (it's expensive), but if it happens twice by accident, nothing is corrupted — just wasted." Here a lock that is *mostly* exclusive is fine. Occasional double-execution is a cost problem, not a correctness problem. Almost any lock works.
- **Correctness use:** "If two processes do this concurrently, data is corrupted / money is double-spent / an invariant is violated." Here you need *true* exclusion — and **a bare distributed lock cannot give it to you** because of GC pauses, clock skew, and network delays (see §3.5–§3.6). For correctness you must combine the lock with **fencing tokens** enforced *at the resource*, or push the mutual exclusion *into the resource itself* (e.g., a conditional write / compare-and-set in the database).

**Prefer alternatives to a distributed lock when you can:**

| Instead of a lock… | …use |
|---|---|
| Serializing writes to a row | A DB transaction with the right isolation level, or an atomic conditional update (`UPDATE … WHERE version = ?`) |
| "Run this once" | An idempotency key + dedup table, or a single-partition queue consumer |
| "Pick a coordinator" | A purpose-built leader-election library on ZooKeeper/etcd (Curator, etcd `concurrency`) |
| Rate limiting / throttling | A token bucket in Redis (atomic, no lock needed) |
| Counters / sequences | A DB sequence, Redis `INCR`, or sharded counters |

The rule of thumb: **if the resource you're protecting can enforce the invariant itself (conditional write, unique constraint, transaction), do that instead of bolting a lock in front of it.** Locks are most defensible when the resource is *dumb* (no native concurrency control) and the cost of a rare double-run is bounded and acceptable.

### 1.4 One-paragraph mental model

A distributed lock is a **lease on a key in a shared, fault-tolerant store**. A client asks the store, "give me exclusive ownership of key K for up to T seconds," using an atomic *set-if-absent* operation so two clients can't both win. The store grants it with an expiry (a **lease/TTL**) so a crashed holder doesn't block everyone forever. The holder does its work, then releases the lock (atomically, verifying it still owns it). The hard truths are: (1) because the holder can pause (GC) or the network can delay, the holder may *believe* it still owns the lock after the lease has already expired and been granted to someone else; (2) therefore the lock alone is not enough for correctness — you must hand the protected resource a monotonically increasing **fencing token** and have the resource reject stale tokens. Everything else in this chapter is detail on top of these two facts.

---

## 2. Foundations from first principles

We build up from zero. If you already know `synchronized`, skim — but the failure-model parts at the end are where distributed locks diverge sharply from local locks.

### 2.1 Local mutual exclusion: the baseline

On one JVM, mutual exclusion is cheap and *safe* because the hardware and OS cooperate:

```java
private final Object monitor = new Object();
private long counter = 0;

void increment() {
    synchronized (monitor) {   // only one thread inside this block at a time
        counter++;             // read-modify-write is now atomic w.r.t. other threads
    }
}
```

Why is this *safe*? Three guarantees the JVM/CPU give you locally:

1. **Atomic test-and-set at the hardware level.** Acquiring a monitor ultimately uses an atomic CPU instruction (e.g., `CMPXCHG` / `LOCK XADD`) — an instruction the hardware guarantees no other core can interleave with. There is a single, authoritative arbiter (the cache-coherent memory system).
2. **The holder cannot "disappear" without releasing.** If a thread holding a monitor dies, the JVM process dies too — there is no scenario where the lock is "held by a thread that no longer exists but the lock is still marked held forever," within a healthy JVM.
3. **No clocks involved.** Local locks don't rely on wall-clock time to decide ownership.

> **Test-and-set / compare-and-swap (CAS):** an atomic operation "if the value is X, set it to Y, and tell me whether you succeeded." It's the bedrock of all lock-free and lock-based coordination. `java.util.concurrent.atomic.AtomicLong.compareAndSet` exposes it directly.

### 2.2 What breaks when you cross the network

Distributed locks lose every one of those three guarantees:

1. **No shared hardware arbiter.** The "atomic test-and-set" must now be performed by a *remote service* and the result must travel back over a network that can drop, delay, duplicate, and reorder messages.
2. **The holder can vanish silently.** A process holding a lock can crash, get partitioned away, or freeze (GC pause, VM migration, container throttling) — and the lock service has *no reliable way to know* whether the holder is dead or merely slow. This is the fundamental ambiguity: **a non-response is indistinguishable from a slow response.**
3. **Clocks and timeouts become load-bearing.** Because a holder might be dead-or-slow, the lock must *expire on its own* (a **lease**) — and now wall-clock time and timeouts decide ownership, exposing you to **clock skew** and **pause-induced lease expiry**.

> **Network partition:** a condition where some nodes can't communicate with others, even though all are alive. The system splits into groups that each think the others are down.
>
> **Lease / TTL (time-to-live):** ownership granted for a bounded duration. If not renewed before it expires, ownership is automatically revoked. Leases trade *safety* for *liveness*: without a lease, a crashed holder blocks everyone forever (a liveness failure); with a lease, a slow holder might lose the lock while still acting (a safety risk).

### 2.3 Safety vs liveness — the central tension

Two properties every locking scheme is judged on:

- **Safety:** "nothing bad ever happens." For a lock: *at most one* client ever believes it holds the lock *and acts on it* at any instant. (More precisely for correctness: at most one client's writes to the protected resource are ever accepted at a time.)
- **Liveness:** "something good eventually happens." For a lock: if the holder dies, the lock is *eventually* released so others can proceed; clients don't deadlock forever.

These pull against each other:

- **No expiry → perfect-ish safety, broken liveness.** If the holder crashes, the lock is held forever; the system stalls.
- **Aggressive expiry → good liveness, broken safety.** Short leases reclaim locks quickly from dead holders — but also from *slow* holders that are actually still working, so two clients can act at once.

**A pure lock, by itself, cannot give you both.** The resolution for correctness-critical cases is to *stop relying on the lock for safety* and instead enforce safety at the resource using **fencing tokens** (§3.5). The lock then becomes an *optimization* (reduces contention) while the fencing token provides the *correctness*.

> **Deadlock:** a state where parties wait on each other forever and none can proceed. Leases are the primary defense against a distributed-lock deadlock caused by a crashed holder.
>
> **Liveness vs safety, intuition:** "safety = never two cooks in the kitchen; liveness = the kitchen never stays empty just because one cook fainted and no one noticed."

### 2.4 The four building blocks every distributed lock needs

Any usable distributed-lock implementation must provide:

1. **Atomic acquire (set-if-absent).** A single operation that either creates the lock key *and returns success to exactly one caller*, or fails if it already exists. This is the distributed analog of CAS. Examples: Redis `SET key val NX PX ttl`; ZooKeeper *create ephemeral sequential node*; etcd transaction `if create_revision == 0 then put`.
2. **Owner identity (a fencing/ownership token).** The value stored under the key must uniquely identify the holder (a random UUID per acquire), so release and renewal can verify "I'm still the owner I think I am." Without this, client A can accidentally release a lock that client B now holds.
3. **A lease / expiry + (usually) renewal.** A TTL so a dead holder is reclaimed; optionally a background "keepalive"/"heartbeat" that extends the lease while work continues.
4. **Atomic release (delete-if-owner).** Releasing must check ownership and delete in one atomic step — otherwise a stale holder deletes someone else's lock.

We'll see each store implement these four differently in §3.

### 2.5 The relevant slice of CAP / consistency theory

> **CAP theorem:** during a network **P**artition, a distributed system can preserve either **C**onsistency (every read sees the latest write / a single agreed-upon state) or **A**vailability (every request gets a non-error response), not both. Outside partitions you can have both.

For a *correct* lock you need the lock store to behave like a **CP** system: under partition it must refuse to hand out conflicting answers, even at the cost of availability. This is why **consensus-backed** stores (ZooKeeper, etcd, Consul) are the safe substrate for locks, and why an **AP** / asynchronously-replicated store (a single Redis primary with async replicas, or multi-master Redis) is fundamentally risky for correctness locks (§3.6).

> **Consensus:** a protocol by which a set of nodes agree on a single value/order of operations even if some nodes fail. **Raft** and **Paxos** are the two famous algorithms. ZooKeeper uses **ZAB** (ZooKeeper Atomic Broadcast, Paxos-like); etcd and Consul use **Raft**. A consensus-backed store gives you **linearizable** writes — exactly the property a lock needs.
>
> **Linearizability:** the strongest single-object consistency model. Every operation appears to take effect instantaneously at some point between its invocation and its response, and all clients see operations in one consistent real-time order. A linearizable "set-if-absent" is precisely a correct distributed lock acquire.
>
> **Quorum:** a majority of nodes (e.g., 2 of 3, 3 of 5). Consensus systems require a quorum to acknowledge a write before it's committed, which is why they survive a minority of failures but stall if a majority is lost.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle for each major implementation, step by step, then unify them around fencing.

### 3.1 The universal lock lifecycle (state machine)

Regardless of store, a lock client moves through these states:

```
            acquire() success                renew() ok
   ┌──────┐ ───────────────► ┌──────────┐ ──────────────┐
   │ FREE │                  │  HELD    │ ◄─────────────┘
   └──────┘ ◄─────────────── └──────────┘
       ▲   release()/expiry       │
       │                          │ lease expires while paused / lost
       │                          ▼
       │                    ┌──────────────┐
       └────────────────────│ LOST (think  │
        (reacquire needed)   │  we hold it, │
                             │  but don't)  │
                             └──────────────┘
```

- **FREE → HELD:** atomic acquire succeeds.
- **HELD → HELD:** lease renewal (heartbeat) succeeds in time.
- **HELD → FREE:** explicit release (delete-if-owner) or natural lease expiry after a crash.
- **HELD → LOST:** the dangerous transition. The lease expired (because we paused, or our renewal packets were lost) but *we don't know it*. Another client may already be in HELD. This is where unsafe behavior originates and where fencing tokens save you.

The whole game is **minimizing time spent in, and damage done from, the LOST state.**

### 3.2 Redis single-instance lock (`SET … NX PX`)

> **Redis:** an in-memory key-value data store, single-threaded for command execution, extremely fast, commonly used as a cache and for lightweight coordination. By default a Redis primary replicates to replicas **asynchronously** (the primary acks the client *before* replicas have the data).

#### Acquire — the right way

The correct one-liner (do **not** use the old `SETNX` + separate `EXPIRE`, which isn't atomic and leaves a window where a crash strands a key with no TTL):

```bash
SET lock:resource <random-uuid> NX PX 30000
# NX  = only set if Not eXists (set-if-absent → mutual exclusion)
# PX  = expiry in milliseconds (the lease)
# value = a random UUID unique to THIS acquisition (ownership token)
```

`SET … NX` is atomic and single-instance-linearizable: Redis is single-threaded, so exactly one concurrent caller gets `OK`; everyone else gets `nil`.

Step by step under the hood (single instance):
1. Client sends `SET … NX PX`. 
2. Redis executes it atomically on its single command thread.
3. If the key was absent, it's created with the value and TTL; reply `OK`. Else reply `nil`.
4. The TTL countdown begins immediately on the server.

#### Release — must be delete-if-owner (Lua, atomic)

You **must not** do `GET` then `DEL`: between them, your lease could expire and another client could acquire — your `DEL` would then delete *their* lock. Use a Lua script (Redis runs scripts atomically):

```lua
-- KEYS[1] = lock key, ARGV[1] = my uuid
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])   -- delete only if I'm still the owner
else
    return 0                            -- someone else owns it now; do nothing
end
```

#### Renewal (keepalive) — same pattern, `PEXPIRE` if owner

```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("pexpire", KEYS[1], ARGV[2])  -- extend my lease
else
    return 0
end
```

A background thread (the "watchdog") runs this every `ttl/3` while work continues.

#### Why the single instance is not safe enough for correctness

If the Redis primary crashes after acking your `SET` but *before* replicating it to the replica that gets promoted, the new primary has **no record of your lock** — so another client acquires it and now two clients hold "the same" lock. This is the asynchronous-replication hole that motivated Redlock.

### 3.3 Redlock (multi-instance Redis) and its critique

> **Redlock:** an algorithm proposed by Redis's author to make Redis locks safe without a single point of failure, by using **N independent Redis masters** (typically 5, no replication between them).

Algorithm (client-side):
1. Record start time `t0` (local clock).
2. Try to `SET … NX PX ttl` on **all N** instances sequentially, each with a short per-request timeout.
3. Count successes. The lock is considered **acquired iff** (a) a *majority* (⌊N/2⌋+1, i.e., 3 of 5) acked, **and** (b) total elapsed time `< ttl` (so the lock is still valid).
4. **Effective validity** = `ttl − (elapsed) − clock-drift-margin`. The client may only act within this window.
5. To release, send delete-if-owner to **all** instances (even ones you think failed).

The intuition: a majority of independent masters must agree, so losing a minority doesn't lose the lock.

#### Martin Kleppmann's critique (you *must* be able to articulate this in interviews)

Kleppmann's 2016 article "How to do distributed locking" argued Redlock is **neither necessary nor sufficient**:

1. **It relies on bounded clocks and bounded pauses, which distributed systems don't guarantee.** Redlock's safety depends on the assumption that the per-instance clocks advance at roughly the same rate and that processes don't pause arbitrarily. But:
   - **Clock jumps** (NTP step, leap-second handling, VM time travel) can make a node think a still-valid lease has expired, or vice versa, breaking the majority/timeout reasoning.
   - **GC pauses / scheduler preemption / page faults** can freeze a client for *seconds* between "I checked I hold the lock" and "I write." During that pause the lease can expire and another client can legitimately acquire. When the paused client wakes, it writes anyway — two writers. **No amount of quorum prevents this**, because the danger is on the *client* side after acquisition, not in the acquisition protocol.
2. **For correctness you need a fencing token, and Redlock doesn't produce a monotonic one.** Quorum acquisition gives you a lock but not a *strictly increasing token* you can use to fence stale writers at the resource (see §3.5). Kleppmann's punchline: *if you have a way to fence (a monotonic token enforced at the resource), the elaborate Redlock protocol is unnecessary; if you don't fence, no lock — Redlock included — is safe for correctness.*
3. **Auto-expiry + async failover** is the core unsafety; quorum across masters doesn't address the pause problem at all.

Salvatore Sanfilippo (Redis author) rebutted that Redlock is intended for environments with reasonably bounded clock drift and that fencing can be layered on. The pragmatic, widely-accepted conclusion:

> **Use Redlock (or single-Redis locks) only for *efficiency*. For *correctness*, use a consensus store (ZooKeeper/etcd) that gives you a monotonic fencing token, and/or enforce the invariant at the resource. Either way, fence.**

### 3.4 ZooKeeper ephemeral-sequential lock recipe

> **ZooKeeper (ZK):** a CP coordination service. Data is a tree of **znodes** (like a filesystem). Clients keep a **session** (a TCP connection + heartbeats). ZK uses the **ZAB** consensus protocol so all writes are linearizable across a quorum of ZK servers (an "ensemble," typically 3 or 5).

Two znode features make ZK ideal for locks:

> **Ephemeral znode:** a node that exists only while the creating client's *session* is alive. When the session ends — graceful close *or* heartbeat timeout — ZK automatically deletes it. This is a *much better* liveness mechanism than a wall-clock TTL: the lock is tied to a live session managed by the cluster, not to client/server clock agreement.
>
> **Sequential znode:** when you create a node with the `SEQUENTIAL` flag, ZK appends a monotonically increasing, zero-padded counter (e.g., `lock-0000000001`). This counter is *global and monotonic per parent* — exactly a **fencing token** for free.
>
> **Watch:** a one-shot subscription; ZK notifies the client when a specific znode changes/deletes. Used to wait without polling.

#### The canonical lock recipe (as in Apache Curator's `InterProcessMutex`)

1. **Create** an ephemeral-sequential child under `/locks/resource/`: e.g., `/locks/resource/lock-0000000007`.
2. **List** all children of `/locks/resource/` and sort by sequence number.
3. **If your node has the lowest sequence number, you hold the lock.** (You're at the head of the queue.)
4. **Otherwise**, find the child with the *next-lower* sequence number than yours, set a **watch** on *only that one node*, and wait. (Watching only your predecessor avoids the "herd effect" — a thundering stampede of notifications if you watched the whole directory.)
5. When the watch fires (predecessor deleted), go back to step 2.
6. **Release** = delete your znode (or just close your session; the ephemeral node disappears).

Why this is safe and live:
- **Safety:** only one node is the lowest; ZK's linearizable, consensus-backed writes mean everyone agrees on the order.
- **Liveness:** if a holder crashes, its session times out, ZK deletes its ephemeral node, the next-in-line's watch fires, and the queue advances. No client-set TTL guesswork.
- **Fencing:** the sequence number is your monotonic fencing token (see §3.5).

> **Session expiry subtlety:** ZK liveness depends on *session timeout*, not a per-lock TTL. If a client GC-pauses longer than the session timeout, ZK kills the session and deletes the ephemeral node — and another client acquires. The paused client, on waking, is *not automatically notified instantly* and may still believe it holds the lock until it next talks to ZK. **So even ZK does not eliminate the LOST state; it just narrows it and gives you a fencing token to defend against it.**

### 3.5 Fencing tokens — the actual fix for correctness

A **fencing token** is a number that **strictly increases each time the lock is granted**. The lock service hands you the token on acquire; you pass it along with *every write* to the protected resource; **the resource remembers the highest token it has seen and rejects any write carrying a lower-or-equal token.**

Why this defeats the LOST-state problem:

```
Client A acquires lock, gets token = 33
A starts a long GC pause...
A's lease expires; Client B acquires, gets token = 34
B writes to storage with token 34 → accepted; storage now remembers max=34
A wakes up (still thinks it holds the lock!) and writes with token 33
Storage sees 33 <= 34 → REJECTS A's write.  Safety preserved.
```

Crucial properties:
- The token must be **monotonic** (strictly increasing) and **issued by the lock authority**, not the client.
- The **resource must enforce it.** A fencing token the storage layer ignores is useless. This is the part teams forget. If your object store / DB / API cannot reject stale tokens, you do not have correctness — you have hope.
- ZooKeeper's sequential counter, etcd's `mod_revision`/key revision, and a DB sequence are natural monotonic token sources. **Redlock does not natively provide one.**

> Some resources can fence "for free": a relational DB can enforce `UPDATE … SET data=?, fence=? WHERE fence < ?`. Object stores increasingly support conditional writes (e.g., `If-Match` ETags, S3 conditional puts) which can serve as a fencing mechanism.

### 3.6 etcd lease lock and leader election

> **etcd:** a CP, Raft-backed key-value store (the backing store for Kubernetes). It offers **leases**, **transactions (compare-and-swap)**, **watches**, and a built-in **`concurrency`** package implementing locks and elections.

> **Raft:** a consensus algorithm with an elected leader that orders all writes into a replicated log; a write commits once a quorum has it. This gives etcd linearizable writes — the right substrate for locks.

etcd lock mechanics:
1. **Create a lease** with a TTL: `LeaseGrant(ttl=15s)` → returns a `leaseID`.
2. **Keep the lease alive** with a periodic `LeaseKeepAlive` stream (heartbeats); etcd extends the TTL on each keepalive.
3. **Put the lock key attached to the lease**, *conditionally*, in a transaction: "if key's `create_revision == 0` (i.e., absent) then put key with this lease." Only one client's txn succeeds.
4. The key's **revision number** is a global monotonic counter → **use it as the fencing token.**
5. **Auto-release:** if the client stops keepalives (crash/partition), the lease expires and etcd deletes all keys attached to it — releasing the lock.
6. The `concurrency.Mutex` / `concurrency.Election` helpers wrap this, including queueing (each waiter creates a key; lowest revision wins, like ZK's sequential recipe).

Same caveat as ZK: a long client pause can let the lease lapse → LOST state → fence with the revision number.

### 3.7 Database-backed locks

Two flavors:

**(a) Advisory locks (purpose-built, e.g., PostgreSQL `pg_advisory_lock`).**
> **Advisory lock:** a lock the *application* agrees to honor; the database doesn't tie it to any row/table — it's a named lock you take and release. `pg_advisory_lock(key)` blocks until acquired; `pg_try_advisory_lock(key)` is non-blocking; **session-level** advisory locks auto-release when the DB session ends (good liveness, like ZK's ephemeral nodes); **transaction-level** (`pg_advisory_xact_lock`) auto-release at commit/rollback.

```sql
-- non-blocking try; returns true/false
SELECT pg_try_advisory_lock(hashtext('invoice-run-2026-06-24'));
-- ... do work ...
SELECT pg_advisory_unlock(hashtext('invoice-run-2026-06-24'));
```

Pros: strong consistency (the DB is CP-ish for this), auto-release on session loss, no extra infra. Cons: ties up a DB connection for the lock's duration; the DB becomes a coordination bottleneck/SPOF; no built-in fencing token (you can mint one with a sequence).

**(b) Row/record locks via a lock table.**

```sql
-- A row per logical lock; uniqueness gives mutual exclusion.
CREATE TABLE locks (
  name        VARCHAR PRIMARY KEY,
  owner       VARCHAR NOT NULL,
  fence       BIGINT  NOT NULL,     -- monotonic fencing token
  expires_at  TIMESTAMPTZ NOT NULL
);

-- acquire (atomic via INSERT … ON CONFLICT, single round trip)
INSERT INTO locks(name, owner, fence, expires_at)
VALUES ('resource', :me, nextval('lock_fence_seq'), now() + interval '30 seconds')
ON CONFLICT (name) DO UPDATE
  SET owner = EXCLUDED.owner, fence = EXCLUDED.fence, expires_at = EXCLUDED.expires_at
  WHERE locks.expires_at < now();   -- only steal if the current lease is expired
-- check rows-affected / RETURNING to know if YOU got it
```

`SELECT … FOR UPDATE` (pessimistic row lock) is another option for serializing access to an *existing* row, but it holds a transaction open and can cause lock queues/deadlocks under load.

> **Pessimistic vs optimistic locking:** *Pessimistic* takes the lock up front and holds it (`SELECT … FOR UPDATE`), assuming conflict is likely. *Optimistic* takes no lock; it reads a version, does work, then does a conditional update `… WHERE version = :read_version` and retries on failure, assuming conflict is rare. Optimistic concurrency (a version column / CAS) is frequently the *better answer than a distributed lock* for correctness — it pushes the invariant into the resource and needs no separate lock service.

### 3.8 Leader election as a lock (and vice versa)

Leader election and distributed locking are **the same primitive viewed two ways**:

- "Hold the lock" ≡ "be the leader."
- The difference is mostly *intent and duration*: a lock is usually short and per-operation; leadership is long-lived, renewed continuously, and the loser nodes typically watch to take over.

Implementations:
- **ZooKeeper:** Curator `LeaderLatch` (just be present and win) or `LeaderSelector` (take leadership, do work, relinquish) — both built on the ephemeral-sequential recipe.
- **etcd:** `concurrency.Election.Campaign()` / `Observe()`; or Kubernetes' `client-go` **leader election** which uses an etcd-backed `Lease` object with `LeaseDurationSeconds`, `RenewDeadline`, `RetryPeriod`.
- **Kubernetes lease object:** controllers (kube-controller-manager, schedulers, operators) elect a leader by repeatedly updating a `Lease` resource; whoever can renew within `RenewDeadline` stays leader.

The fencing caveat is even more important for leaders: a former leader that GC-paused past its lease can wake up thinking it's still leader and issue commands. Leader-driven systems must also fence (e.g., include a leader epoch/term number on every command, and have followers reject stale terms — exactly how Raft itself uses **terms**).

> **Term / epoch:** a monotonically increasing number identifying a leadership reign. Raft increments the term each election; messages from an older term are rejected. This *is* a fencing token for leadership.

---

## 4. The complete toolkit

### 4.1 Redis commands & client APIs

| Command / API | Purpose | Key params | Notes / defaults |
|---|---|---|---|
| `SET key val NX PX ms` | Atomic acquire with lease | `NX` set-if-absent; `PX` ms TTL (`EX` for seconds) | Single round-trip, atomic. Use a random UUID for `val`. |
| `SETNX` (legacy) | Set-if-absent (no TTL) | — | **Avoid for locks** — no atomic TTL; deprecated pattern. |
| `EVAL`/`EVALSHA` (Lua) | Atomic release / renew | script + `KEYS`/`ARGV` | Scripts run atomically on the single-threaded server. |
| `PEXPIRE key ms` | Extend lease (inside owner-check Lua) | ms | Used by watchdog. |
| `GET` + `DEL` | **Anti-pattern** for release | — | Race window; never use separately. |
| Redisson `RLock` (Java) | High-level lock w/ watchdog | `lockWatchdogTimeout` (default 30000 ms), `leaseTime` | Auto-renews while held if `leaseTime` not set; reentrant; pub/sub wakeups. |
| Redisson `RedissonRedLock` | Redlock across N clients | list of `RLock`s | Implements multi-instance Redlock. |
| Redisson `RFencedLock` | Lock that issues a fencing token | — | Returns a monotonic token on lock; the *correct* choice when you need fencing on Redis. |

> **Redisson:** a popular Java Redis client providing distributed objects including `RLock`, `RReadWriteLock`, `RSemaphore`, `RFencedLock`. Its **watchdog** auto-extends a held lock every `lockWatchdogTimeout/3` (~10s by default) so long jobs don't lose the lock — *unless* you pass an explicit `leaseTime`, which disables the watchdog.

### 4.2 ZooKeeper / Apache Curator

| API | Purpose | Key params | Notes |
|---|---|---|---|
| `create … EPHEMERAL_SEQUENTIAL` | Create ordered ephemeral lock node | path, mode | Building block of the recipe; sequence = fencing token. |
| `getChildren` + `exists`(watch) | Find predecessor & wait | watch flag | Watch only predecessor to avoid herd effect. |
| Curator `InterProcessMutex` | Reentrant distributed mutex | `acquire(time, unit)` | Implements the recipe correctly; reentrant per thread. |
| Curator `InterProcessSemaphoreMutex` | Non-reentrant mutex | — | Use when reentrancy is undesirable. |
| Curator `InterProcessReadWriteLock` | Shared/exclusive | — | Multiple readers, single writer. |
| Curator `LeaderLatch` / `LeaderSelector` | Leader election | — | Built on the same recipe. |
| Curator `InterProcessSemaphore` | N permits | leases count | Distributed counting semaphore. |

> **Session timeout (ZK):** negotiated between client and server, bounded by ensemble `minSessionTimeout`/`maxSessionTimeout` (defaults commonly 2× and 20× `tickTime`, where `tickTime` defaults to 2000 ms). Determines how fast a dead holder's ephemeral lock is reclaimed — and how long a paused holder might linger in the LOST state.

### 4.3 etcd

| API | Purpose | Key params | Notes |
|---|---|---|---|
| `LeaseGrant(ttl)` | Create a lease | TTL seconds | TTL is the lock lease. |
| `LeaseKeepAlive` | Heartbeat lease | stream | Keeps lock alive while working. |
| `Txn` (`If/Then/Else`) | Atomic compare-and-set acquire | `create_revision == 0` | Linearizable acquire; revision = fence token. |
| `clientv3/concurrency.NewMutex` | High-level mutex | `Lock(ctx)` / `Unlock` | Queue via revisions, like ZK recipe. |
| `concurrency.NewElection` | Leader election | `Campaign`/`Resign`/`Observe` | Built on lease + txn. |
| `etcdctl lock <name> [cmd]` | CLI lock | — | Holds lock for duration of `cmd`. |
| `etcdctl elect <name> <proposal>` | CLI election | — | Becomes leader; prints key. |

### 4.4 Relational DB

| Mechanism | Purpose | Key params | Notes |
|---|---|---|---|
| `pg_advisory_lock(key)` / `_try_` / `_xact_` | Session/txn advisory lock | 64-bit key | Auto-release on session/txn end. |
| `SELECT … FOR UPDATE` / `FOR UPDATE SKIP LOCKED` | Pessimistic row lock | — | `SKIP LOCKED` great for queue workers. |
| `INSERT … ON CONFLICT … WHERE expires_at < now()` | Lock table acquire | TTL column | Atomic steal of expired lock. |
| `GET_LOCK(name, timeout)` (MySQL) | Named advisory lock | timeout secs | Auto-release on connection close. |
| Sequences | Mint fencing tokens | — | `nextval` is monotonic. |

### 4.5 ShedLock (Java scheduler dedup)

> **ShedLock:** a Java library that ensures a `@Scheduled` task runs on only *one* node at a time, across Redis, JDBC, MongoDB, ZooKeeper, etc. It is explicitly an **efficiency** tool (it says so in its docs): "it does NOT guarantee the task runs at most once if a node holds the lock and pauses." Use it for "run the cron on one node," not for correctness-critical exclusivity.

---

## 5. Code examples by use case

### 5.1 Use case A — Redis single-instance lock with auto-release, in raw Java (Jedis)

A minimal, *correct-for-efficiency* lock showing all four building blocks (atomic acquire, owner token, lease, delete-if-owner).

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import java.util.UUID;
import java.util.List;

public final class RedisLock {
    private static final String UNLOCK_LUA =
        "if redis.call('get', KEYS[1]) == ARGV[1] " +
        "then return redis.call('del', KEYS[1]) else return 0 end";

    private final Jedis jedis;
    private final String key;
    private final String token = UUID.randomUUID().toString(); // unique owner id

    RedisLock(Jedis jedis, String resource) {
        this.jedis = jedis;
        this.key = "lock:" + resource;
    }

    /** Atomic acquire: SET key token NX PX ttl. Returns true iff we won. */
    boolean tryAcquire(long ttlMillis) {
        String res = jedis.set(key, token, new SetParams().nx().px(ttlMillis));
        return "OK".equals(res); // null when key already existed
    }

    /** Atomic release: delete ONLY if we still own it (avoids deleting another's lock). */
    void release() {
        jedis.eval(UNLOCK_LUA, List.of(key), List.of(token));
    }

    public static void main(String[] args) {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            RedisLock lock = new RedisLock(jedis, "daily-report");
            if (lock.tryAcquire(30_000)) {
                try {
                    // CRITICAL SECTION — keep it shorter than the TTL!
                    generateDailyReport();
                } finally {
                    lock.release(); // always release in finally
                }
            } else {
                System.out.println("Another node holds the lock; skipping.");
            }
        }
    }
    static void generateDailyReport() { /* ... */ }
}
```

Things that matter: the token is per-acquisition; release is Lua (atomic owner-check + delete); the work must finish well within the 30 s lease, or another node will steal it (use Redisson's watchdog, §5.2, for long jobs).

### 5.2 Use case B — Redisson `RFencedLock`: correctness with a fencing token

When you actually need to fence stale writers (correctness use), use Redisson's fenced lock and pass the token to the resource.

```java
import org.redisson.Redisson;
import org.redisson.api.RFencedLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import java.util.concurrent.TimeUnit;

public class FencedExample {
    public static void main(String[] args) throws InterruptedException {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient redisson = Redisson.create(cfg);

        RFencedLock lock = redisson.getFencedLock("storage-writer");

        // Acquire; wait up to 5s; lease 30s (watchdog disabled because leaseTime is set)
        Long token = lock.tryLockAndGetToken(5, 30, TimeUnit.SECONDS);
        if (token != null) {                 // token is MONOTONIC across acquisitions
            try {
                // Pass the fencing token to the resource on EVERY write.
                writeToStorage(payload(), token);   // storage rejects token <= maxSeen
            } finally {
                lock.unlock();
            }
        } else {
            System.out.println("Could not acquire fenced lock");
        }
        redisson.shutdown();
    }

    // The resource MUST enforce monotonicity — this is where correctness lives.
    static void writeToStorage(byte[] data, long fence) {
        // e.g., UPDATE storage SET data=?, fence=? WHERE fence < ? ;
        // if 0 rows updated -> reject (stale writer).
    }
    static byte[] payload() { return new byte[0]; }
}
```

### 5.3 Use case C — ZooKeeper leader election with Apache Curator

"Exactly one active coordinator," with automatic failover.

```java
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class CoordinatorElection {
    public static void main(String[] args) throws Exception {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            "zk1:2181,zk2:2181,zk3:2181",
            new ExponentialBackoffRetry(1000, 3));  // base 1s, up to 3 retries
        client.start();

        LeaderSelector selector = new LeaderSelector(client, "/election/coordinator",
            new LeaderSelectorListenerAdapter() {
                @Override public void takeLeadership(CuratorFramework c) throws Exception {
                    // Called when WE become leader. Block here while leading.
                    System.out.println("I am the leader; coordinating...");
                    try {
                        runCoordinatorLoop();      // returns only when we should step down
                    } finally {
                        System.out.println("Releasing leadership.");
                    }
                    // When this method returns, leadership is relinquished.
                }
            });
        selector.autoRequeue();   // re-enter the election after losing/relinquishing
        selector.start();
        Thread.currentThread().join();
    }

    static void runCoordinatorLoop() throws InterruptedException {
        // IMPORTANT: also fence downstream commands with a leader epoch/term,
        // because a GC pause can make us a "zombie leader" after ZK demoted us.
        while (!Thread.currentThread().isInterrupted()) { Thread.sleep(1000); }
    }
}
```

Note the comment: even with ZK, defend against the zombie-leader (LOST) state by tagging commands with a term and having followers reject stale terms.

### 5.4 Use case D — PostgreSQL queue worker with `FOR UPDATE SKIP LOCKED` (no separate lock service)

This is the "push exclusion into the resource" pattern — often *better* than a distributed lock.

```sql
-- Each worker atomically claims one un-taken job; SKIP LOCKED means workers
-- never block each other — they just grab different rows.
BEGIN;
SELECT id, payload
FROM jobs
WHERE status = 'pending'
ORDER BY created_at
FOR UPDATE SKIP LOCKED      -- lock the row I picked; skip rows others locked
LIMIT 1;
-- ... in app code: process the job ...
UPDATE jobs SET status = 'done' WHERE id = :id;
COMMIT;                     -- releases the row lock
```

No Redis, no ZK, no fencing token needed: the DB transaction *is* the mutual exclusion, and the `status` column makes it idempotent across retries.

### 5.5 Use case E — ShedLock to run a Spring `@Scheduled` job on one node

```java
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

@Scheduled(cron = "0 0 2 * * *")               // 02:00 daily, on every replica
@SchedulerLock(name = "nightlyReconciliation",
               lockAtLeastFor = "PT1M",         // hold ≥1m even if work is fast (anti-flap)
               lockAtMostFor  = "PT30M")        // safety: release after 30m if node dies
public void reconcile() {
    // Runs on exactly one replica per tick — EFFICIENCY guarantee, not correctness.
    // If this node pauses > lockAtMostFor while working, another node may also run.
}
```

`lockAtMostFor` is the lease; pick it comfortably larger than the worst-case runtime. `lockAtLeastFor` prevents two nodes from both running when clocks are slightly off and the job is very fast.

### 5.6 Use case F — etcd mutex with revision as fence (Go, since etcd's client is Go-first)

```go
import (
    "context"
    clientv3 "go.etcd.io/etcd/client/v3"
    "go.etcd.io/etcd/client/v3/concurrency"
)

func withEtcdLock(cli *clientv3.Client) error {
    // Lease-backed session: lock auto-releases if this process dies.
    sess, err := concurrency.NewSession(cli, concurrency.WithTTL(15)) // 15s lease
    if err != nil { return err }
    defer sess.Close()

    mu := concurrency.NewMutex(sess, "/locks/billing")
    if err := mu.Lock(context.TODO()); err != nil { return err } // blocks until acquired
    defer mu.Unlock(context.TODO())

    // mu.Header().Revision is a monotonic fence token usable downstream.
    doExclusiveWork()
    return nil
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Correctness / concurrency

- **Always use the four building blocks** (atomic acquire, owner token, lease, delete-if-owner). The most common production bug is `GET`+`DEL` release or `SETNX`+`EXPIRE` acquire.
- **Fence at the resource for any correctness use.** A lock without enforced fencing is an efficiency tool masquerading as a safety tool. If the resource can't fence, you can't claim correctness — be honest about it.
- **Reentrancy:** raw Redis locks are *not reentrant* (a thread that already holds it will deadlock against itself). Redisson `RLock` and Curator `InterProcessMutex` *are* reentrant per owner/thread. Know which you have.
- **Keep critical sections short** and shorter than the lease. The longer the section, the more likely a pause exceeds the lease → LOST state.

### 6.2 Performance & contention

- **Lock granularity:** prefer fine-grained, per-key locks (`lock:order:123`) over one global lock. A single hot lock serializes your whole system.
- **Watch only your predecessor (ZK)** to avoid the **herd effect** (thundering herd of watch notifications when one lock releases and N waiters all wake).
- **Lock services are coordination hot spots.** Every acquire/release/heartbeat is a network round trip to a quorum; under high churn this is a real load on ZK/etcd. Don't use a distributed lock at per-request rates — cache the leadership/ownership decision.
- **Heartbeat cost:** each held lock with renewal generates periodic writes (heartbeats) to a consensus store. Thousands of held locks = thousands of writes/interval. Size the cluster accordingly.

### 6.3 Liveness & lease tuning

- **Lease too short** → false expirations (lost locks under normal GC/load) → safety violations and churn.
- **Lease too long** → slow recovery after a real crash (resource blocked for the whole lease).
- **Rule of thumb:** lease ≈ a few × your p99 stop-the-world pause + network round-trip margin, and renew at lease/3. For JVMs, *measure your GC pauses* (`-Xlog:gc*` / GC logs); if you see multi-second pauses, your lease must exceed them or you'll constantly lose locks.
- **Prefer session-bound liveness** (ZK ephemeral nodes, etcd leases, DB session locks) over client-set wall-clock TTLs when possible — they reclaim on actual disconnect rather than guessed timeouts.

### 6.4 Clocks

- **Never trust client wall-clock for ownership decisions.** Use monotonic clocks (`System.nanoTime()`) for measuring elapsed time, never `System.currentTimeMillis()` (which can jump backward via NTP).
- **Be aware of NTP steps and leap seconds**; they have caused real lock outages. Consensus stores avoid relying on synchronized clocks for ordering (they order by log index/revision), which is another reason they're safer.

### 6.5 Security

- **Authenticate and authorize access to the lock store.** Anyone who can write to your ZK/etcd/Redis can steal or forge locks. Use ZK ACLs / etcd RBAC / Redis ACLs + TLS.
- **Don't expose the lock store on the public network.** A compromised Redis is a coordination catastrophe.
- **Validate owner tokens** server-side where possible; never let a client delete a key it doesn't own (the Lua delete-if-owner enforces this for Redis).

### 6.6 Observability

Instrument and alert on:
- **Acquire success/failure/latency**, **wait time**, **lock hold time** (histogram), **renewal failures**, **lease expirations while held** (this last one is your LOST-state alarm — it should be ~0).
- **Fence-token rejections at the resource** — every rejection is a caught safety violation; a spike means your leases are too short or pauses too long.
- **Lock store health:** quorum status, leader changes, request latency, ZK session expirations, etcd lease churn.
- Emit the **owner identity** and **fence token** in logs around the critical section for forensic tracing.

### 6.7 Testing

- **Inject pauses:** simulate GC/STW by `Thread.sleep` between acquire and write, with a lease shorter than the sleep, and assert the resource *rejects* the stale write. This is the test that proves your fencing works.
- **Inject partitions / kill the lock store mid-hold** and assert no two clients act (use a shared counter / fence-rejection assertion).
- **Jepsen-style testing** for the lock store itself if you depend on its linearizability claims.
- **Chaos: kill the holder** and verify the lock is reclaimed within the expected lease/session timeout and that a successor acquires exactly once.

> **Jepsen:** a framework by Kyle Kingsbury for testing distributed systems' consistency claims under partitions and faults; it has repeatedly found that systems violate the guarantees they advertise.

### 6.8 Cost

- A dedicated ZK/etcd ensemble (3–5 nodes) is real operational cost. Don't stand one up for a single "run cron once" need — use ShedLock on your existing DB/Redis.
- Conversely, don't bolt correctness locks onto a cache (Redis) you also use for other things and then carry that risk; the "free" Redis lock can be the most expensive choice when it silently double-executes a financial operation.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's wrong | Fix |
|---|---|---|
| `SETNX` then `EXPIRE` (two commands) | Crash between them leaves a TTL-less lock forever | `SET … NX PX` in one command |
| `GET` then `DEL` release | Deletes someone else's lock after your lease expired | Lua delete-if-owner |
| No owner token (shared value) | Any client can release any lock | Per-acquisition UUID |
| Distributed lock for correctness *without* fencing | GC pause/clock skew → two writers, data corruption | Fence at the resource, or use DB conditional write |
| Single-Redis (or async-replicated) lock for money/correctness | Failover loses the lock → double write | Consensus store + fence, or DB transaction |
| Lease shorter than worst-case pause | Constant lost locks, safety violations | Measure pauses; size lease; add watchdog renewal |
| Long critical section under a fixed lease, no renewal | Lock expires mid-work | Watchdog/keepalive renewal |
| One global coarse lock | Serializes the whole system | Per-key fine-grained locks |
| Using a lock when a DB transaction/unique constraint suffices | Extra infra, extra failure mode, weaker guarantee | Push exclusion into the resource |
| Treating leader election as fence-free | Zombie leader after pause issues stale commands | Leader epoch/term on every command |

---

## 7. Advanced topics & deep internals

### 7.1 The Redlock debate, in full

The Kleppmann↔Sanfilippo exchange is the canonical advanced topic. The deep points:

- **Asynchronous model vs partially-synchronous assumptions.** Redlock's correctness argument implicitly assumes *bounded* message delay and *bounded* clock drift. Real systems are better modeled as *asynchronous* (unbounded delays, pauses). In an asynchronous model, **no algorithm can guarantee mutual exclusion using leases without fencing** — this is fundamentally why fencing is required.
- **The pause is post-acquisition.** Quorum (Redlock) hardens the *acquisition* against losing a minority of masters. But the dangerous window — pause between "I hold it" and "I write" — is entirely on the client side and untouched by quorum. This is why Kleppmann says quorum is solving the wrong problem for correctness.
- **What Redlock *is* good at:** surviving the loss of a minority of independent Redis nodes for *efficiency* locks, without a single point of failure and without ZK/etcd. That's a legitimate, narrower value.
- **Sanfilippo's counter:** with reasonable clock bounds and short leases plus fencing, Redlock is usable. Most practitioners land on: *for correctness, use a consensus store and fence; for efficiency, a single Redis lock is usually enough and Redlock is overkill.*

### 7.2 Reentrancy and lock counting internals

Redisson implements reentrancy by storing a **hash** at the lock key: field = `clientId:threadId`, value = reentry count. Acquire by the same owner increments the count (Lua `HINCRBY`); release decrements and only `DEL`s the key when the count hits 0. This is how it gives you `ReentrantLock` semantics across the network.

### 7.3 The watchdog (auto-renewal) internals

Redisson's watchdog: on lock acquire *without* an explicit `leaseTime`, it schedules a timer task every `lockWatchdogTimeout/3` (default 30000/3 = 10 s) that runs the owner-check `PEXPIRE` Lua to reset the lease to the full watchdog timeout. It stops when you `unlock()` or the client shuts down. **Gotcha:** if your JVM pauses longer than `lockWatchdogTimeout`, the watchdog can't renew in time and you enter the LOST state anyway. The watchdog reduces, not eliminates, the risk.

### 7.4 Read-write and semaphore variants

- **Read-write locks** (`RReadWriteLock`, Curator `InterProcessReadWriteLock`): many readers OR one writer. Internals: separate read/write znodes/keys; writers wait for all readers; readers wait for the writer. Watch for **writer starvation** under heavy read load.
- **Counting semaphores** (`RSemaphore`, Curator `InterProcessSemaphore`): up to N holders. Used for connection-pool-like limits across the cluster.
- **Fair vs unfair:** Curator's recipe is FIFO-fair (sequence order). Redis-based locks are typically *unfair* unless you build a queue.

### 7.5 ZooKeeper session-vs-connection nuance

A ZK client distinguishes **connection loss** (recoverable; reconnect to another ensemble member, session preserved) from **session expiration** (terminal; ephemeral nodes deleted, all locks lost). Curator surfaces these via `ConnectionStateListener` (`SUSPENDED` → `LOST`/`RECONNECTED`). **You must handle `SUSPENDED`/`LOST` by *assuming you no longer hold the lock* and ceasing critical-section work** — failing to do so is a classic zombie-leader bug.

### 7.6 etcd lease & revision internals

etcd keys carry `create_revision`, `mod_revision`, and `version`. A lock acquire txn keys on `create_revision == 0`. The global `revision` (a cluster-wide monotonic counter incremented on every write) is the ideal fence. etcd leases are managed by the Raft leader; `LeaseKeepAlive` is a streaming RPC, and lease expiry triggers deletion of attached keys *through the Raft log* (so it's linearizable, not a local timer race).

### 7.7 Generic vs specific failure of timeouts

A subtle expert point: **even consensus stores can't make a *client* respect a revoked lock instantly.** The store can revoke (delete the ephemeral node / expire the lease) linearizably, but the *client's belief* is stale until its next round trip. The only thing that converts "store revoked it" into "client's writes are safely rejected" is **fencing at the resource.** This is why fencing is not optional for correctness, *regardless of how good your lock store is.*

### 7.8 Multi-region / geo-distributed locks

Cross-region locks are usually a mistake: consensus across regions means every acquire pays inter-region RTT (tens to hundreds of ms) and a regional partition stalls the lock. Patterns: keep the lock store regional and partition the work by region; or use a single global writer per shard (leader per partition) rather than a global lock. Spanner/TrueTime-style systems use tightly bounded clocks (uncertainty intervals) to make cross-region ordering safe — but you generally won't be building a lock on that.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Backing-store comparison

| Property | Single Redis | Redlock (N Redis) | ZooKeeper | etcd | RDBMS (advisory/row) |
|---|---|---|---|---|---|
| Consistency model | AP-ish (async repl) | AP-ish, quorum | CP (ZAB) | CP (Raft) | CP-ish (single DB) |
| Safe for *correctness* alone? | **No** | **No** (need fence) | Yes-ish + fence | Yes-ish + fence | Yes + fence/conditional |
| Native fencing token | No (RFencedLock adds it) | No | **Yes** (seq #) | **Yes** (revision) | Via sequence |
| Liveness mechanism | Wall-clock TTL | Wall-clock TTL | Session ephemeral | Lease + keepalive | Session / TTL column |
| Latency | Lowest (µs–ms) | Low (N round trips) | Low–med (quorum) | Low–med (quorum) | Med (DB load) |
| Extra infra to run | Maybe (already have Redis) | N independent Redis | ZK ensemble | etcd cluster | None (reuse DB) |
| Best for | Efficiency locks, caching-adjacent | Efficiency, no SPOF | Correctness coord, election | Correctness coord, K8s-native | Simple cases, reuse infra |
| Operational complexity | Low | Medium | Medium–high | Medium | Low |

### 8.2 Decision rules

**Use a distributed lock when:**
- The protected resource has *no* native concurrency control and you can't add one.
- Occasional double-execution is acceptable (efficiency), OR you *can* fence at the resource (correctness).
- You need cluster-wide coordination (singleton job, leader election) that the data layer can't express.

**Avoid a distributed lock when:**
- A DB transaction, unique constraint, or conditional update (`WHERE version=?`) can enforce the invariant — do that instead.
- Correctness matters but you *cannot* fence the resource — redesign so the resource enforces the invariant; a lock won't save you.
- The operation rate is high — coordination overhead and contention will dominate.
- You'd be using a single async-replicated Redis for money-grade correctness — that's an incident waiting to happen.

**Choose the store:**
- Already run Redis, efficiency only → **single-Redis lock / Redisson**.
- Need correctness + fencing, no consensus infra, want no SPOF, efficiency-leaning → **Redlock** (still fence).
- Need real correctness coordination / leader election, can run infra → **etcd** (K8s-native) or **ZooKeeper** (mature, Curator recipes).
- Small scale, already have a DB, don't want new infra → **DB advisory lock / lock table / `SKIP LOCKED`**.
- Just "run my Spring cron on one node" → **ShedLock** on your existing DB/Redis.

---

## 9. Failure modes & debugging

### 9.1 The canonical failure: GC pause → split-brain write

**Symptom:** rare data corruption / double side-effects despite "having a lock."
**Mechanism:** holder GC-pauses past the lease; another client acquires; both write.
**Diagnose:**
- Correlate corruption timestamps with **GC logs** (`-Xlog:gc*:file=gc.log:time,uptime`) — look for STW pauses near the incident.
- Check the lock store for **lease-expired-while-held** events and **fence-token rejections**.
- Look for two distinct owner tokens / fence tokens writing to the same resource in the window.
**Fix:** add fencing enforced at the resource; lengthen lease above worst-case pause; add watchdog renewal; tune GC (e.g., switch to a low-pause collector like ZGC/Shenandoah and verify pause histograms).

### 9.2 Lock-not-released / deadlock

**Symptom:** a resource is permanently blocked; everyone waits.
**Cause:** acquire without a lease (or with a too-long lease) and the holder crashed; or release not in `finally`; or wrong owner token so release silently no-ops.
**Diagnose:** inspect the lock key directly — `redis-cli TTL lock:x` / `GET lock:x`; ZK `get /locks/x/...` and `stat`; `etcdctl get --prefix /locks/`; in PG `SELECT * FROM pg_locks` joined with `pg_stat_activity`.
**Fix:** always set a lease and always release in `finally`; verify the owner-check release actually matches.

### 9.3 Thundering herd / herd effect

**Symptom:** every lock release causes a CPU/network spike across all waiters; ZK ensemble load spikes.
**Cause:** all waiters watch the whole lock directory.
**Fix:** watch only the predecessor (Curator does this correctly).

### 9.4 Lock store outage / partition

**Symptom:** acquires hang or fail cluster-wide; jobs stall (CP store) or split-brain (AP store).
**Diagnose:** check quorum/leader status — ZK `mntr`/`stat` 4-letter words or AdminServer; `etcdctl endpoint status --cluster` and `endpoint health`; Redis `INFO replication`.
**Fix/operate:** size ensembles for the failures you must tolerate (3 nodes survive 1 loss, 5 survive 2); decide app behavior on lock-store-unavailable — usually **fail closed** (do nothing) for correctness work, not fail open.

### 9.5 Clock-skew incident

**Symptom:** locks expire early or late on some nodes; intermittent double-runs after NTP events.
**Diagnose:** check NTP/chrony status and offset history; look for `currentTimeMillis` going backward in logs.
**Fix:** use monotonic clocks for elapsed-time decisions; rely on session/lease-based stores; add fencing.

### 9.6 Real-world incident patterns

- **GitHub-style / general async-failover lesson:** several outages across the industry trace to a primary failing over to a replica that hadn't received the latest writes — directly the single-Redis lock hole. The lesson baked into this chapter: don't use async-replicated stores for correctness locks.
- **Kubernetes leader-election flapping:** aggressive `LeaseDuration`/`RenewDeadline` under API-server latency causes controllers to repeatedly lose and re-grab leadership, thrashing reconcilers. Fix: widen the timing relative to observed API latency.
- **JVM STW lock loss:** services with large heaps and the throughput (parallel) collector experienced multi-second pauses that exceeded short Redis leases, causing intermittent double-processing until fencing was added and leases lengthened.

### 9.7 Debugging toolkit quick reference

| Store | Inspect a lock | Health/quorum |
|---|---|---|
| Redis | `GET lock:x`, `TTL lock:x`, `PTTL lock:x` | `INFO replication`, `redis-cli --latency` |
| ZooKeeper | `get /locks/x/lock-000…`, `ls /locks/x`, `stat` | 4-letter `mntr`/`stat`/`ruok`, AdminServer |
| etcd | `etcdctl get --prefix /locks/`, `lease list` | `endpoint status/health --cluster` |
| PostgreSQL | `SELECT * FROM pg_locks; pg_stat_activity` | `pg_stat_replication` |

---

## 10. Interview drill

**Q1. What is a distributed lock and how is it different from a JVM `ReentrantLock`?**
*Model answer:* Both provide mutual exclusion, but a `ReentrantLock` arbitrates threads in one JVM via shared memory and a hardware CAS, with no clocks and no possibility of the holder silently vanishing. A distributed lock coordinates separate processes via an external store over a network, so it must add an owner token, a lease/TTL (because a holder can crash or pause undetectably), and atomic delete-if-owner release — and it can never be perfectly safe by itself because of GC pauses and clock skew.
- *Probe:* Why a lease? → Liveness: without it a crashed holder blocks everyone forever. *Probe:* Why not a long lease? → Slow recovery after real crashes. *Probe:* Why is delete-if-owner needed? → Avoid deleting another client's lock after your lease expired.

**Q2. Explain Redlock and Kleppmann's critique.**
*Model answer:* Redlock acquires `SET NX PX` on a majority of N independent Redis masters within the TTL to avoid a single point of failure. Kleppmann argued it's unsafe for correctness because the dangerous window is a client pause *after* acquisition — during which the lease expires and another client acquires — and quorum doesn't help that; it also assumes bounded clocks. His fix is a monotonic fencing token enforced at the resource, which makes the elaborate quorum unnecessary for safety. Conclusion: Redlock is fine for efficiency, not correctness.
- *Probe:* What does the fencing token defend against exactly? → A paused/stale holder writing after losing the lock. *Probe:* Does ZooKeeper avoid the need to fence? → No; it narrows the LOST window and gives you a token (the sequence number), but the client can still be stale, so you must still fence at the resource. *Probe:* Why is async replication the core single-Redis problem? → Failover can promote a replica missing your lock write → two holders.

**Q3. What is a fencing token and who enforces it?**
*Model answer:* A strictly increasing number issued by the lock authority on each grant. The client attaches it to every write; the *resource* remembers the highest token seen and rejects any write with a lower-or-equal token. The resource — not the lock — enforces correctness. Without resource enforcement the token is decorative.
- *Probe:* Where do you get a monotonic token? → ZK sequential node, etcd revision, DB sequence; not natively from Redlock. *Probe:* What if the resource can't fence? → You don't have correctness; redesign to push the invariant into the resource.

**Q4. How do ZooKeeper ephemeral-sequential locks work, and why are they nice?**
*Model answer:* Create an ephemeral-sequential child; the lowest sequence number holds the lock; others watch only their predecessor. Ephemeral = auto-deleted on session loss (great liveness, no TTL guessing); sequential = a built-in monotonic fence; watching the predecessor avoids the herd effect; ZAB consensus makes the ordering linearizable.
- *Probe:* Connection loss vs session expiry? → Connection loss is recoverable; session expiry deletes your ephemeral node = you lost the lock; you must stop work on `SUSPENDED`/`LOST`. *Probe:* Herd effect? → If everyone watched the whole dir, one release wakes all N; watch only predecessor.

**Q5. (Senior signal) When is a distributed lock the wrong tool?**
*Model answer:* When the resource can enforce the invariant itself — a DB transaction, unique constraint, or conditional `UPDATE … WHERE version=?` — those give correctness without a separate lock service and its failure modes. Also when correctness matters but you cannot fence the resource (a bare lock can't be safe), when the rate is high (coordination overhead dominates), or when you'd back it with an async-replicated store for money-grade work. A lock is a last resort for *dumb* resources or *efficiency* dedup.
- *Probe:* Give a concrete replacement for "increment balance under a lock." → `UPDATE accounts SET bal=bal-? WHERE id=? AND bal>=?` in a transaction. *Probe:* Efficiency vs correctness example? → Cache rebuild (efficiency, lock fine) vs payment capture (correctness, fence/transaction).

**Q6. (Senior signal) How would you size a lock's lease for a JVM service?**
*Model answer:* Measure worst-case stop-the-world pauses from GC logs and worst-case work duration; set the lease above the larger of (p99.9 pause + RTT margin) and the work time, and renew at lease/3 via a watchdog. Too short → false expirations and split-brain; too long → slow crash recovery. Prefer session/lease-bound stores so liveness keys off real disconnects, and add fencing so a mis-sized lease degrades to rejected writes instead of corruption.
- *Probe:* What GC change reduces risk? → Low-pause collector (ZGC/Shenandoah) to shrink the LOST window. *Probe:* Why renew at lease/3? → Tolerate a missed heartbeat or two before the lease lapses.

**Q7. (Senior signal) Compare ZooKeeper, etcd, Redis, and a relational DB for locking.**
*Model answer:* (Walk the §8.1 table.) ZK/etcd are CP, consensus-backed, give native monotonic fence tokens and session/lease liveness — the safe choice for correctness/coordination at the cost of running an ensemble. Redis is fast and often already present but async-replicated single-instance is unsafe for correctness; Redlock adds HA but not safety. A relational DB (advisory locks, lock table, `SKIP LOCKED`) reuses existing infra and is CP-ish, ideal for simpler needs and for pushing exclusion into the resource. Choose by: correctness need, whether you can fence, infra you already run, and acceptable latency/contention.
- *Probe:* K8s-native pick? → etcd (it's the backing store; client-go leader election). *Probe:* Cheapest correct option for a small team? → DB conditional update / advisory lock.

**Q8. How does leader election relate to locking?**
*Model answer:* They're the same primitive: holding the lock = being leader. Election is long-lived and renewed; followers watch to take over. Curator `LeaderLatch`/`LeaderSelector` and etcd `Election` implement it on the same ephemeral/lease+revision machinery. Critically, leaders must also fence — tag commands with a leader epoch/term and reject stale terms — to avoid zombie leaders after pauses (Raft does exactly this with terms).
- *Probe:* What's a zombie leader? → A former leader that paused past its lease and acts as leader after being demoted. *Probe:* How do you fence leadership? → Monotonic term/epoch on every command, rejected if stale.

**Q9. Why must lock release be atomic and owner-checked? Show the bug.**
*Model answer:* With `GET`+`DEL`, after `GET` confirms ownership your lease can expire and client B can acquire; your subsequent `DEL` deletes B's lock → two holders. The fix is a Lua script that checks the owner token and deletes in one atomic step.
- *Probe:* Why does the value need to be a per-acquire UUID? → So the owner check is specific to *your* acquisition, not just "some lock exists." *Probe:* Same risk for renewal? → Yes; renewal must also be owner-checked `PEXPIRE`.

**Q10. What happens to your lock when the lock store is partitioned away from your process?**
*Model answer:* You can't renew. Depending on the store, your lease/session expires and the store gives the lock to someone reachable. Your process may still *think* it holds the lock (LOST state) until it reconnects and learns otherwise. For correctness you must (a) stop critical-section work when you detect connection loss/`SUSPENDED`, and (b) fence at the resource so any late write is rejected. The app should generally fail closed on lock-store unavailability for correctness work.
- *Probe:* Fail open or closed? → Closed for correctness; possibly open for pure efficiency if double-work is harmless. *Probe:* How detect it in Curator? → `ConnectionStateListener` `SUSPENDED`/`LOST`.

**Q11. (Bonus) When would `SELECT … FOR UPDATE SKIP LOCKED` beat a distributed lock?**
*Model answer:* For a work queue: each worker atomically claims a distinct row without blocking the others, the transaction is the mutual exclusion, and a status column makes retries idempotent — all with no separate lock service, no leases, no fencing complexity, and strong DB consistency.
- *Probe:* Downsides? → DB becomes the contention point/SPOF; long transactions hold locks; not suitable at extreme throughput. *Probe:* Difference from `FOR UPDATE` without `SKIP LOCKED`? → Without `SKIP LOCKED`, workers serialize/queue on the same rows instead of grabbing different ones.

---

## 11. Glossary

- **Advisory lock:** an application-honored named lock provided by a DB (`pg_advisory_lock`, MySQL `GET_LOCK`); auto-releases on session/txn end.
- **AP / CP (CAP):** under a partition, a system favors Availability (always answers, maybe stale) or Consistency (refuses conflicting answers, maybe unavailable). Locks need CP behavior.
- **Atomic operation:** an operation that completes entirely or not at all, with no observable intermediate state to others.
- **CAS / compare-and-swap / test-and-set:** atomic "if value is X set to Y, report success." Basis of all locking.
- **Consensus:** a protocol for a set of nodes to agree on one value/order despite failures (Paxos, Raft, ZAB).
- **Critical section:** code/operation that must not run concurrently.
- **Deadlock:** parties wait on each other forever; none progress.
- **Ephemeral node (ZK):** a znode that exists only while its creator's session lives; auto-deleted on session end.
- **etcd:** Raft-backed CP key-value store with leases, transactions, watches; Kubernetes' datastore.
- **Fencing token:** a monotonically increasing number issued on lock grant; the resource rejects writes with stale tokens. The real fix for correctness.
- **GC pause / stop-the-world (STW):** the JVM halts application threads to do garbage collection; can last seconds, freezing a lock holder.
- **Herd effect / thundering herd:** many waiters wake simultaneously on one event, spiking load.
- **Idempotency:** an operation that can be applied multiple times with the same effect as once; key to safe retries.
- **Lease / TTL:** time-bounded ownership; auto-revoked if not renewed. Trades safety for liveness.
- **Leader election:** choosing a single coordinator among nodes; equivalent to holding a lock.
- **Linearizability:** strongest single-object consistency; operations appear instantaneous in one real-time order. A linearizable set-if-absent is a correct lock acquire.
- **Liveness:** "something good eventually happens" — e.g., a crashed holder's lock is eventually freed.
- **Monotonic clock:** a clock that never goes backward (`System.nanoTime()`); use it for elapsed-time, not wall-clock.
- **Mutual exclusion / mutex:** at most one actor uses a resource at a time.
- **NTP:** Network Time Protocol; synchronizes clocks but can *step* (jump) them, breaking timeout reasoning.
- **Optimistic / pessimistic locking:** optimistic = no lock, conditional update with version check + retry; pessimistic = take and hold a lock up front.
- **Network partition:** alive nodes that can't communicate; the system splits.
- **Quorum:** a majority of nodes required to commit a consensus write.
- **Raft:** leader-based consensus algorithm ordering writes via a replicated log; uses terms.
- **Reentrancy:** the same owner can acquire a lock it already holds without deadlocking.
- **Redis:** fast in-memory key-value store; single-threaded command execution; default async replication.
- **Redisson:** Java Redis client with distributed locks (`RLock`, `RFencedLock`) and a renewal watchdog.
- **Redlock:** Redis multi-master quorum lock algorithm; safe for efficiency, debated for correctness.
- **Revision (etcd):** global monotonic counter incremented per write; usable as a fence token.
- **Session (ZK):** a client's logical connection kept alive by heartbeats; its end deletes ephemeral nodes.
- **ShedLock:** Java library to run a scheduled task on one node; an efficiency tool, not correctness.
- **Split-brain:** two nodes both act as owner/leader simultaneously.
- **Term / epoch:** monotonically increasing leadership-reign number; a fence token for leadership.
- **Watchdog:** background task that renews a held lease so long jobs don't lose the lock.
- **Watch (ZK/etcd):** a subscription notifying a client when a node/key changes.
- **ZAB:** ZooKeeper Atomic Broadcast, ZK's Paxos-like consensus protocol.
- **ZooKeeper / znode / ensemble:** CP coordination service; data nodes (znodes) in a tree; cluster of servers (ensemble).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Lock = lease on a key in a shared CP store.** Four building blocks: atomic acquire (set-if-absent) · per-acquire owner UUID · lease+renewal · delete-if-owner release.
- **Efficiency vs correctness is THE distinction.** Efficiency: any lock fine, double-run merely wasteful. Correctness: **lock alone is never enough — you must FENCE at the resource** (or push the invariant into the resource via a DB transaction/conditional write).
- **Why locks alone fail:** GC pause / clock skew / network delay → holder enters **LOST state** (thinks it holds the lock after the lease expired) → split-brain writes. Fencing token (monotonic, resource-enforced) rejects the stale write.
- **Stores:** single-Redis = fast, **unsafe for correctness** (async failover). Redlock = HA, still no fence, efficiency only. **ZooKeeper** (ephemeral-sequential, session liveness, seq# fence) and **etcd** (lease+txn, revision fence) = CP, safe substrate. **RDBMS** (advisory / lock table / `SKIP LOCKED`) = reuse infra, often the better answer.
- **Redis correct pattern:** acquire `SET k uuid NX PX ttl`; renew via owner-check `PEXPIRE`; release via owner-check `DEL` (Lua). Never `SETNX`+`EXPIRE`, never `GET`+`DEL`.
- **Numbers/knobs:** renew at lease/3; lease > worst-case STW pause + RTT margin; Redisson watchdog default 30 s, renews every 10 s; ZK `tickTime` 2 s, session 2×–20× tick; etcd lease keepalive streaming; ZK ensemble of 3 tolerates 1 loss, 5 tolerates 2.
- **Leader election = a lock.** Fence leadership with an epoch/term (Raft does this); beware zombie leaders.
- **On lock-store loss for correctness: fail closed; stop work on `SUSPENDED`/`LOST`.**
- **Observability must-haves:** lease-expired-while-held (LOST alarm, target ~0) and fence-token rejections at the resource.

### 12.2 Self-test (no answers — recall actively)

1. Walk the full state machine of a lock client and explain exactly how the **LOST** state arises and why neither Redlock nor ZooKeeper eliminates it.
2. Write, from memory, the correct Redis acquire, owner-checked renew, and owner-checked release — and explain the bug in each naive alternative (`SETNX`+`EXPIRE`, `GET`+`DEL`).
3. A teammate proposes a single-Redis lock to prevent double-charging a customer. Diagnose every way this can double-charge and redesign it to be correct.
4. Explain the ZooKeeper ephemeral-sequential recipe end to end, including why you watch only the predecessor and how the sequence number doubles as a fencing token.
5. Given a JVM service with observed 4 s p99.9 GC pauses, choose a lease length, renewal interval, store, and fencing strategy, and justify each number.
6. Describe a concrete resource-side fencing implementation (SQL or object-store conditional write) and prove it rejects a stale writer from the LOST state.
7. Compare etcd vs ZooKeeper vs RDBMS for a leader-elected controller, and state your pick with the tradeoffs that drove it.

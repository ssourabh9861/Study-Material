# Time, Clocks & Ordering in Distributed Systems

> A definitive engineering-handbook chapter for senior backend engineers (Java/JVM focus). From first principles to deep internals: why wall-clock time lies, logical clocks (Lamport & vector), hybrid logical clocks, Google TrueTime/Spanner commit-wait, the happens-before relation, and how ordering underpins causal consistency and conflict resolution.

---

## 1. Overview & where it fits

### 1.1 What this is

"Time, clocks, and ordering" is the body of theory and engineering practice that lets a distributed system answer one deceptively simple question: **"Did event A happen before event B?"** In a single program on a single machine, the answer is trivial — the CPU executes one instruction after another, and a single monotonic counter (or even a single thread's program order) gives you a total order for free. Across many machines connected by an unreliable network, with independent clocks that drift, there is **no shared "now"** and no free global order. You have to *construct* ordering, and you have to decide *which kind* of ordering you actually need.

This chapter covers:

- **Why physical (wall-clock) time is unreliable** across machines: clock drift, NTP skew, leap seconds, virtualization, and the gap between *monotonic* and *wall-clock* time.
- **Logical clocks** — counters that capture *causality* (who-caused-what) rather than real time. Two flavors: **Lamport timestamps** (a single integer) and **vector clocks** (one integer per node).
- **Hybrid Logical Clocks (HLC)** — a clever combination of physical and logical time that stays close to wall-clock time *and* respects causality.
- **Google TrueTime and Spanner** — the industrial answer that makes physical time *trustworthy enough* by bounding its uncertainty with atomic clocks and GPS, then waiting out that uncertainty ("commit-wait") to get globally consistent timestamps.
- **The happens-before relation** — Leslie Lamport's 1978 partial order that is the mathematical backbone of everything here.
- **Ordering as the foundation of correctness** — how causal consistency, conflict resolution (last-writer-wins, CRDTs), snapshot isolation, and consensus all rest on some notion of ordering.

### 1.2 The problem it solves

Concrete questions that all reduce to "ordering":

1. **Database replication.** Node A and node B both received a write to key `x`. Which write is newer? If you pick wrong, you lose data.
2. **Caches / cache invalidation.** You read a stale value, then your "set" message arrives out of order, and you re-cache the stale value forever.
3. **Distributed debugging / tracing.** You collect logs from 50 services. The timestamps say service B responded *before* service A sent the request. Did time go backwards? (Yes, effectively.)
4. **Leases & fencing.** A node thinks it still holds a lock because its clock says the lease hasn't expired, but the lock service already gave the lock to someone else. Two writers corrupt your data ("split brain").
5. **Causal messaging.** In a chat app, a reply must not be shown before the message it replies to. That's a *causal* ordering requirement, not a real-time one.
6. **Externally consistent transactions.** If transaction T1 commits and *then* (in real wall-clock time) a human starts transaction T2, T2 must see T1's effects. This is what Spanner guarantees globally.

### 1.3 When you reach for each tool

| You need… | Reach for… |
|---|---|
| "Did A causally precede B, or are they concurrent?" | **Vector clocks** (exact) or **Lamport** (one-directional only) |
| A cheap monotonic tiebreaker / total order respecting causality | **Lamport timestamps** |
| Timestamps that are *both* causally correct *and* close to real time (for TTLs, MVCC) | **Hybrid Logical Clocks (HLC)** |
| Globally consistent commit timestamps with external consistency, willing to spend money on hardware | **TrueTime + commit-wait (Spanner)** |
| Just "roughly when did this happen" for human-readable logs | **NTP-synced wall-clock**, but never for correctness decisions |
| Measure elapsed durations on one machine | **Monotonic clock** (`System.nanoTime()`), never wall-clock |

### 1.4 One-paragraph mental model

> In a distributed system there is no global clock, only many local clocks that disagree and drift. **Physical time** tells you *roughly when* something happened but cannot be trusted to *order* events across machines. **Logical time** (Lamport, vector) ignores real time and instead tracks *causality* — it can prove "A could have caused B" or "A and B are concurrent." **Hybrid time** (HLC) glues a logical counter onto physical time so timestamps are both causally correct and human-meaningful. **TrueTime** takes the opposite bet: make physical time trustworthy by *quantifying its error* and *waiting it out*. Which one you pick depends on whether you need real-time semantics (TrueTime), pure causality (vector clocks), or a practical middle ground (HLC).

---

## 2. Foundations from first principles

### 2.1 What "time" even means to a computer

A computer doesn't have one clock; it has several, and they answer different questions.

- **Hardware oscillator / crystal.** Almost every clock is ultimately driven by a quartz crystal oscillating at a nominal frequency (e.g., 32.768 kHz, or higher in modern systems). The crystal is imperfect: temperature, age, and manufacturing variance make it run slightly fast or slow. This rate error is **drift**, measured in **parts per million (ppm)**. A typical commodity crystal drifts on the order of **±10–100 ppm**, i.e., up to ~100 microseconds per second, which compounds to **~8.6 seconds per day at 100 ppm** if uncorrected.

- **Wall-clock time (a.k.a. "real time" / "time-of-day" clock).** This is "what a wall clock shows": seconds since an epoch (Unix epoch = 1970-01-01 00:00:00 UTC). In Java this is `System.currentTimeMillis()` or `Instant.now()`. **Crucial property: it can jump.** NTP can step it backward or forward; an operator can set it; leap seconds can perturb it; a VM can have its clock reset on migration. **Never use wall-clock time to measure elapsed time or to order events for correctness.**

- **Monotonic clock.** A counter that only ever moves forward at a (roughly) steady rate and is **not affected by NTP steps or clock setting**. It has no defined relationship to real-world time — its zero point is arbitrary (often boot time). In Java this is `System.nanoTime()`. Use it for measuring durations, timeouts, and rate limiting. **It is meaningless to compare `nanoTime()` values between two different machines** (different arbitrary zero points and different drift).

> **Beginner note — why two clocks?** The OS exposes two because they solve different problems. Wall-clock answers "what is today's date and time?" (and must be correctable, hence it can jump). Monotonic answers "how much time has elapsed?" (and must never go backward, so it ignores corrections). Mixing them up is one of the most common production bugs.

### 2.2 NTP: how machines try to agree on time, and why it isn't enough

**NTP (Network Time Protocol)** is the standard protocol (RFC 5905, NTPv4) for synchronizing a computer's wall clock to a reference time source over a network.

How it works in brief:

1. Time sources are arranged in **strata**. *Stratum 0* is a reference clock (atomic clock, GPS receiver). *Stratum 1* servers are directly attached to stratum 0. *Stratum 2* sync to stratum 1, and so on. Higher stratum = further from the source = generally less accurate.
2. A client sends a request and records four timestamps: `t0` (client send), `t1` (server receive), `t2` (server send), `t3` (client receive).
3. It estimates **round-trip delay** `δ = (t3 − t0) − (t2 − t1)` and **offset** `θ = ((t1 − t0) + (t2 − t3)) / 2`. The offset assumes the network path is *symmetric* (request and reply take equally long).
4. NTP applies the offset, usually by **slewing** (gradually speeding up or slowing down the clock — `adjtime`) for small corrections, or **stepping** (jumping) for large ones (default step threshold in ntpd is **128 ms**; beyond that it steps).

Why NTP is not a basis for ordering:

- **Asymmetric paths.** If the outbound path is faster than the return path (common on the internet, in congested datacenters, or across cloud regions), the offset estimate is biased by up to half the asymmetry. Datacenter NTP typically achieves **single-digit milliseconds** of accuracy; over the public internet, **tens of milliseconds** is common, occasionally worse.
- **It can step the clock backward.** A correctness algorithm that assumed time only moves forward will break. Two consecutive `currentTimeMillis()` calls can return a *smaller* second value.
- **Drift between syncs.** Between NTP updates (poll interval typically 64 s to 1024 s) the clock free-runs on the crystal and drifts.
- **No bound is exposed.** Vanilla NTP tells you *a* time; it does **not** hand you a trustworthy *error bound* on that time. (This is the gap TrueTime fills.)
- **Misconfiguration / bad upstreams.** A bad stratum-1 server, a firewall blocking UDP 123, or a falseticker can poison time. NTP's "intersection algorithm" tries to discard falsetickers but isn't foolproof.

> **Beginner note — what's a "leap second"?** Earth's rotation is irregular, so UTC is occasionally adjusted by inserting (or theoretically removing) a single second to stay aligned with astronomical time. On a leap-second day, the UTC minute can have **61 seconds** (`23:59:60`). Naive software either sees a repeated second, a jump, or a clock that pauses. Google's famous answer was the **"leap smear"**: instead of one weird second, smear the extra second across ~24 hours by slowing the clock ~0.0014%, so no single jump occurs. (Note: the international timekeeping bodies have resolved to **stop inserting leap seconds by 2035**, but decades of existing systems still must cope.)

### 2.3 Clock skew vs clock drift (precise definitions)

- **Skew (offset):** the *instantaneous difference* between two clocks at a moment, e.g., node A reads 10:00:00.000 while node B reads 10:00:00.030 → 30 ms skew.
- **Drift (rate):** the *difference in the rate* at which clocks advance, e.g., one runs 50 ppm fast. Drift causes skew to grow over time if not corrected.
- **Maximum drift rate (ρ):** a bound you assume, e.g., "no clock drifts more than 200 ppm." If two clocks are synced at time `t` and you wait `Δ` seconds, their skew can grow by up to `2 · ρ · Δ`. This product appears in TrueTime's ε and in lease-safety math.

### 2.4 Why there is no "global now"

This is not merely engineering difficulty — it is closer to physics. Information cannot travel faster than the network allows, and there is genuine relativity-of-simultaneity at play: two events on different machines may have **no fact of the matter** about which happened "first" in real time, because no signal connected them. Lamport's key insight (next section) is that the *only* ordering we can ever truly establish across machines is the one induced by **actual communication** (message passing) and **local sequencing**. Everything else is, at best, a heuristic.

### 2.5 The happens-before relation (→) — Lamport, 1978

This is the single most important definition in the chapter. Define a relation **"happens-before"**, written `→`, over events:

1. **Local order:** If `a` and `b` are events in the *same process* and `a` occurs before `b` in that process's program order, then `a → b`.
2. **Message order:** If `a` is the event "process P **sends** message m" and `b` is the event "process Q **receives** message m", then `a → b`. (A message cannot be received before it is sent.)
3. **Transitivity:** If `a → b` and `b → c`, then `a → c`.

Two events `a` and `b` are **concurrent** (written `a ∥ b`) if **neither** `a → b` **nor** `b → a`. Concurrency means "causally independent" — no chain of local steps and messages connects them.

Key consequences:

- `→` is a **partial order**, not a total order. Many event pairs are simply incomparable (concurrent). This is fundamental, not a defect.
- `a → b` means "**a could have causally influenced b**." It does *not* mean it did, only that information could have flowed from `a` to `b`.
- If `a ∥ b`, the system is *free* to order them however it likes (or treat them as a conflict to resolve), because no observer could have seen one cause the other.

> **Beginner note — "causality" vs "real time".** Happens-before is about *potential causal influence via communication*, not wall-clock time. Two events can be far apart in real time yet concurrent (no messages between them). Conversely, if A → B, then in *any* sensible global timeline A is not after B. This is exactly the property a clock should preserve.

### 2.6 The two big families of clocks

| | Captures real time? | Captures causality? | Cost |
|---|---|---|---|
| **Physical clocks** (NTP wall-clock) | Approximately | No (skew can invert order) | Cheap |
| **Logical clocks** (Lamport, vector) | No | Yes | Cheap–moderate |
| **Hybrid (HLC)** | Approximately (close to physical) | Yes (one-directional like Lamport) | Cheap |
| **Tightly-bounded physical (TrueTime)** | Yes, with a *known error bound* | Yes (via commit-wait) | Expensive (hardware + waiting) |

The rest of the chapter builds each of these up.

---

## 3. How it works internally

This is the heart of the chapter. We go mechanism-by-mechanism, with step-by-step internal workflows, state, and worked numeric examples.

### 3.1 Lamport timestamps (scalar logical clocks)

**Goal:** assign each event an integer `L(e)` such that **if `a → b` then `L(a) < L(b)`**. (Note the one-way implication — see the caveat below.)

**State per process:** a single integer counter `C`, initially 0.

**Algorithm (the three rules):**

1. **Before any local event** (including a send), increment: `C := C + 1`. Assign the event timestamp `C`.
2. **On sending a message,** piggyback the current `C` on the message.
3. **On receiving a message** carrying timestamp `t`: set `C := max(C, t) + 1`. Assign the receive event timestamp `C`.

That's it. The `max` is what propagates causal knowledge: a receiver's clock jumps to at least one past anything it has "heard about."

#### Worked example

Three processes P1, P2, P3. Events in program order, with two messages.

```
P1:  a(send m1 to P2) ............ d ........
P2:        b(recv m1) ... c(send m2 to P3) ..
P3:  ............................. e(recv m2)
```

Step through:

- P1.a: `C1 = 0 → 1`. Event a = **1**. m1 carries 1.
- P2.b (recv m1, t=1): `C2 = max(0,1)+1 = 2`. Event b = **2**.
- P2.c (send m2): `C2 = 2 → 3`. Event c = **3**. m2 carries 3.
- P1.d (local): `C1 = 1 → 2`. Event d = **2**.
- P3.e (recv m2, t=3): `C3 = max(0,3)+1 = 4`. Event e = **4**.

Check causality: `a → b` (1 < 2) ✓. `b → c` (2 < 3) ✓. `c → e` (3 < 4) ✓. So `a → e` ⇒ 1 < 4 ✓.

**The crucial caveat — the implication is one-way.** `a → b ⇒ L(a) < L(b)` holds, but the **converse does not**. Here `L(a)=1 < L(d)=2`, yet `a` and `d` are both on P1 so `a → d`. But consider `d` (=2) and `b` (=2): equal-ish ordering issues aside, look at `d`(=2 on P1) vs `c`(=3 on P2): `L(d) < L(c)` but `d ∥ c` (no message connects them). **So `L(x) < L(y)` does NOT imply `x → y`.** Lamport timestamps can tell you when two events are *possibly* ordered, but cannot detect concurrency. To get a *total* order you break ties (equal `C`) by process ID; this total order is *consistent with* `→` but invents orderings between concurrent events.

**Why this matters:** Lamport timestamps are perfect when you only need a total order consistent with causality (e.g., a state-machine total-order multicast, or a tiebreaker). They are insufficient when you must *detect* conflicts (concurrent writes) — for that you need vector clocks.

### 3.2 Vector clocks

**Goal:** a timestamp that captures causality *exactly* — you can decide for any two events whether `a → b`, `b → a`, or `a ∥ b`.

**State per process i (of N processes):** a vector `V` of N integers, all initially 0. `V[i]` counts events at process i that this process knows about.

**Algorithm:**

1. **Before any local event at process i:** `V[i] := V[i] + 1`.
2. **On send from process i:** increment `V[i]` (rule 1) and attach the whole vector `V` to the message.
3. **On receive at process i of a message with vector `Vm`:** first merge element-wise `V[k] := max(V[k], Vm[k])` for all k, then increment own component `V[i] := V[i] + 1`.

**Comparison rules** for two vectors `V` and `W`:

- `V ≤ W` iff `V[k] ≤ W[k]` for **all** k.
- `V < W` (i.e., `V` happens-before `W`) iff `V ≤ W` **and** `V ≠ W`.
- `V ∥ W` (concurrent) iff **neither** `V < W` **nor** `W < V` (each has at least one component strictly greater than the other).

#### Worked example

Three processes A, B, C; vectors ordered [A,B,C].

```
A: a1 -------- send mAB ----------------- a2
B:        b1  recv mAB  b2  send mBC ----
C: ----------------------- recv mBC  c1
```

- Init: A=[0,0,0], B=[0,0,0], C=[0,0,0].
- A.a1 (local): A=[1,0,0]. Event a1 = [1,0,0].
- A send mAB: A=[2,0,0], message carries [2,0,0].
- B.b1 (local): B=[0,1,0].
- B recv mAB [2,0,0]: merge → [max(0,2),max(1,0),max(0,0)]=[2,1,0], then +own → [2,2,0]. Event = [2,2,0].
- B send mBC: B=[2,3,0], message carries [2,3,0].
- C recv mBC [2,3,0]: merge with [0,0,0] → [2,3,0], +own → [2,3,1]. Event c1 = [2,3,1].
- A.a2 (local, later): A=[3,0,0]. Event a2 = [3,0,0].

Now compare:

- a1=[1,0,0] vs b's recv=[2,2,0]: a1 ≤ that and ≠ → **a1 → recv**. ✓ (correct: a1's send influenced B.)
- a2=[3,0,0] vs c1=[2,3,1]: a2 has A=3 > 2, c1 has B=3 > 0 and C=1 > 0. Neither dominates → **a2 ∥ c1**. ✓ (a2 happened on A *after* the message left; C never heard about a2.)

That last result — detecting **concurrency** — is exactly what scalar Lamport clocks cannot do.

**Size problem.** A vector clock has one entry per process *that has ever existed*. In a system with many short-lived clients (e.g., every browser is a "process"), vectors grow without bound. Mitigations:

- **Dotted version vectors (DVV)** — used in Riak — track per-*server* causality plus a "dot" for the specific event, decoupling the vector size from the number of clients. This keeps vectors O(replicas) instead of O(clients).
- **Pruning** entries for nodes that are demonstrably caught up, with care.
- **Interval Tree Clocks (ITC)** — a data structure that supports fork/join/retire of identities, so the clock grows and shrinks with the active set of actors rather than the all-time set.

### 3.3 Hybrid Logical Clocks (HLC)

**Motivation.** Lamport/vector clocks lose all relationship to real time (you can't use them as a TTL or to query "give me the value as of 3pm"). Pure physical clocks lose causality. **HLC** (Kulkarni, Demirbas, et al., 2014) gives you a timestamp that is:

- **Monotonic** and **respects happens-before** (like Lamport): `a → b ⇒ HLC(a) < HLC(b)`.
- **Close to physical time**: the HLC value is always within a bounded distance of the node's physical clock (specifically, the logical-drift component stays bounded), so it's meaningful as a wall-clock-ish timestamp.
- **Constant size** (a pair of integers), unlike vector clocks.

**State per node:** a pair `(l, c)`:
- `l` = the largest physical time seen so far (the "logical" high-water mark of physical time).
- `c` = a small integer counter used to order events that share the same `l`.

Let `pt` = the node's current physical clock reading (e.g., `currentTimeMillis()`).

**Algorithm — local event or send:**

```
l_old := l
l := max(l_old, pt)              # advance to physical time if it moved ahead
if l == l_old:                   # physical clock didn't advance past l
    c := c + 1                   # bump the logical counter to keep ordering
else:
    c := 0                       # physical time advanced, reset counter
timestamp := (l, c)
```

**Algorithm — on receive of message timestamp `(l_m, c_m)`:**

```
l_old := l
l := max(l_old, l_m, pt)         # take the max of all three
if l == l_old and l == l_m:
    c := max(c, c_m) + 1
elif l == l_old:
    c := c + 1
elif l == l_m:
    c := c_m + 1
else:                            # l == pt, physical time is ahead of both
    c := 0
timestamp := (l, c)
```

**Reading it:** `(l, c)` is encoded as a single 64-bit (or 128-bit) value — e.g., 48 bits of physical time in milliseconds (or microseconds) and 16 bits of counter `c`. Comparison is just integer comparison of the packed value: compare `l` first, then `c`.

**Key safety property:** `c` only grows while physical time is "stuck" relative to `l`. Because physical clocks are roughly synchronized and keep advancing, `c` stays small in practice (typically single digits), and `l` never drifts more than a bounded amount ahead of the true physical time across the cluster (bounded by the max clock skew ε). This is why HLC values are simultaneously good wall-clock approximations *and* causally correct.

**Where HLC is used in practice:** **CockroachDB** uses HLC for its timestamps and MVCC; **YugabyteDB** uses HLC; **MongoDB** uses a related "cluster time" (a signed HLC-like `(timestamp, increment)` ordered via `$clusterTime`) for causal consistency and change streams. We trace CockroachDB's use of HLC + a `max_offset` bound (and "uncertainty restarts") in §7.

#### Worked HLC example

Two nodes, physical clocks slightly skewed. Times in ms.

- Node A physical time ~ 100, Node B physical time ~ 98 (B is 2 ms behind).
- A local event, pt=100: `l=max(0,100)=100`, l moved ahead so `c=0`. TS = (100, 0).
- A sends to B with (100, 0).
- B receives at pt=98 (B's clock is behind!): `l = max(0, 100, 98) = 100`. Here `l == l_m (100)` and `l != l_old(0)` → `c = c_m + 1 = 1`. TS = (100, 1). 

Notice: even though B's *physical* clock (98) was behind the message's time (100), HLC refused to go backward — it kept `l=100` and bumped the counter. Causality preserved, and the timestamp is still "about 100ms," close to real time. A pure physical clock on B would have stamped 98 and violated happens-before.

### 3.4 Google TrueTime and Spanner's commit-wait

**The bet.** Instead of giving up on physical time, Google made physical time *trustworthy enough to order events* by (a) using better hardware and (b) **exposing the uncertainty** so software can wait it out.

**TrueTime API.** TrueTime does not return a single timestamp. It returns an **interval** that is *guaranteed to contain the true absolute time*:

```
TT.now() → TTinterval { earliest, latest }
TT.after(t)  → true if t has definitely passed
TT.before(t) → true if t definitely has not yet arrived
```

The width of the interval is **2·ε** (ε = "epsilon", the half-width of uncertainty). At any instant, the true time is somewhere in `[now.earliest, now.latest]`. Published Spanner figures: ε is typically a few milliseconds, sawtoothing roughly between **~1 ms and ~7 ms**, with a mean around **~4 ms** (it grows between time-master polls — every ~30 s — as local clocks drift at an assumed worst-case rate of ~200 µs/s, then snaps back down on resync).

**How the bound is achieved.** Each datacenter has **time masters**: most equipped with **GPS receivers** (which deliver atomic time from satellites) and some with **atomic clocks** (rubidium) as an independent failure domain (GPS and atomic clocks fail in uncorrelated ways — GPS can have antenna/spoofing/leap-second issues; atomic clocks drift slowly). Machines run a **timeslave daemon** that polls multiple masters, applies a variant of Marzullo's algorithm to reject liars and compute a tight interval, and continually widens the local ε between polls to account for drift. This is fundamentally different from NTP: NTP gives you *a* time; TrueTime gives you a time *plus a proven worst-case error*.

> **Beginner note — Spanner & external consistency.** **Spanner** is Google's globally-distributed SQL database. Its headline guarantee is **external consistency** (a.k.a. *strict serializability*): if transaction T1 commits before T2 *starts* in real time, then T1's commit timestamp is less than T2's, and T2 sees T1's writes — even if T1 and T2 ran on opposite sides of the planet. Achieving this without a global clock is the whole game.

**Commit-wait — the core mechanism.** Spanner assigns each read-write transaction a commit timestamp `s` and then **waits until that timestamp is definitely in the past everywhere** before releasing locks / acknowledging the commit. Step by step for a single transaction (simplified, using two-phase commit + two-phase locking under the hood):

1. Coordinator picks a commit timestamp `s ≥ TT.now().latest` (i.e., at least the latest possible "now"). It also ensures `s` is greater than any timestamp it has previously assigned and any prepare timestamps from participants — this enforces monotonicity.
2. **Commit-wait:** the coordinator *blocks* until `TT.after(s)` is true — i.e., until it is *certain* that real time has passed `s`. Because the worst-case uncertainty is ε, this wait is on the order of `2·ε` (commonly cited average commit-wait ≈ **~2·ε**, a handful of milliseconds).
3. Only *after* the wait does it apply the commit and let clients observe it / release locks.

Why this yields external consistency: by waiting until `s` is definitely in the past, Spanner guarantees that **any transaction that starts later in real time will pick a commit timestamp strictly greater than `s`** (because that later transaction's `TT.now().latest` will exceed `s`). So real-time order ⇒ timestamp order ⇒ visible order. The cost is latency: every read-write transaction pays ~2·ε of commit-wait. This is why Google invests so heavily in keeping ε small — **ε is literally added to write latency.**

**Reads.** Read-only transactions and snapshot reads exploit timestamps to avoid locks entirely: a snapshot read at timestamp `t_read` simply reads the MVCC version as of `t_read` on any sufficiently up-to-date replica, no coordination. "Strong" reads pick `t_read = TT.now().latest` and may wait briefly for a replica to catch up (the "safe time").

**TrueTime's failure mode.** If ε ever *blows up* (e.g., a datacenter loses its GPS and atomic references, or network partitions the time masters), Spanner doesn't return wrong answers — it **slows down or stops**. The system would rather pay latency (longer commit-waits) or refuse to commit than violate the uncertainty bound. Reportedly, if a machine's local clock drifts beyond the trusted bound, it removes itself from service. **Correctness is preserved by sacrificing availability/latency** — a CAP-flavored choice.

### 3.5 Putting ordering to work: causal consistency and conflict resolution

**Consistency models, briefly (beginner ladder):**

- **Strong / linearizable:** every operation appears to take effect at a single instant between its invocation and response; there's one global order matching real time. Expensive; requires coordination (consensus or TrueTime).
- **Sequential consistency:** there's *a* total order consistent with each process's program order, but not necessarily real-time order.
- **Causal consistency:** if `a → b`, every node observes `a` before `b`. Concurrent operations may be seen in different orders by different nodes. This is the *strongest* model achievable **without** sacrificing availability under network partitions (a key result related to the CAP theorem — see Glossary). Vector clocks / HLC are the natural machinery for it.
- **Eventual consistency:** replicas converge *eventually* if writes stop; says nothing about ordering in the meantime.

**How ordering underpins conflict resolution:**

- **Last-Writer-Wins (LWW).** When two concurrent writes conflict, keep the one with the higher timestamp. With wall-clock timestamps this silently *loses data* under clock skew (the write with the faster clock wins regardless of true order). Cassandra's default conflict resolution is LWW by cell timestamp — a well-known footgun if clocks are skewed. HLC makes LWW *causally safe*: a write that causally followed another will always have a larger HLC, so it correctly wins; only truly concurrent writes are broken by the tiebreaker.
- **Conflict detection with vector clocks.** Dynamo/Riak attach a vector clock (or DVV) to each value. On a write, if the incoming vector *dominates* the stored one (`stored < incoming`), it's a clean overwrite. If they're concurrent (`∥`), the system keeps **both** as **siblings** and lets the application (or a CRDT) resolve them. This is *not losing data*, at the cost of pushing resolution to the app.
- **CRDTs (Conflict-free Replicated Data Types).** Data types (counters, sets, registers, maps) designed so that concurrent updates *always* merge deterministically and commutatively — order of delivery doesn't matter, only the set of updates. Many CRDTs internally use version vectors or dotted version vectors to track causality and to distinguish "I haven't seen your add" from "I saw your add and then you removed it." (See the OR-Set example in §5.)
- **MVCC + snapshot isolation.** Multi-Version Concurrency Control stores multiple timestamped versions of each row. A transaction reads "as of" a timestamp and sees a consistent snapshot. The *quality of the timestamp* determines the isolation guarantee: HLC-based MVCC (CockroachDB) gives serializability with uncertainty handling; TrueTime-based MVCC (Spanner) gives external consistency.

---

## 4. The complete toolkit

### 4.1 Java/JVM clock APIs

| API | Returns | Clock type | Resolution / notes | Use for |
|---|---|---|---|---|
| `System.currentTimeMillis()` | `long` ms since Unix epoch | Wall-clock | Millisecond unit; **actual** granularity often ~1–15 ms (OS-dependent); **can jump backward** | Human-readable timestamps, logging, NOT durations or ordering |
| `System.nanoTime()` | `long` ns, arbitrary origin | Monotonic | High resolution; **monotonic per JVM**; meaningless across machines/JVMs; can wrap after ~292 years | Measuring elapsed time, timeouts, benchmarks |
| `Instant.now()` | `Instant` | Wall-clock (UTC) | Backed by `Clock.systemUTC()`; up to ns precision on JDK 9+ (was ms on JDK 8) | Timestamps, persistence (store UTC) |
| `Clock` (abstract) | injectable time source | Either | `Clock.systemUTC()`, `Clock.system(zone)`, `Clock.fixed(...)`, `Clock.tick(...)`, `Clock.offset(...)` | **Inject into code for testability** |
| `Clock.tickMillis(zone)` / `tickSeconds` | truncated `Clock` | Wall-clock | Truncates to ms/s | Deterministic coarse time |
| `Clock.fixed(instant, zone)` | frozen `Clock` | — | Always returns same instant | **Unit tests** |
| `ZonedDateTime` / `OffsetDateTime` / `LocalDateTime` | calendar types | — | `LocalDateTime` has **no** zone — avoid for timestamps | Display/calendar logic |
| `Duration` / `Period` | spans | — | `Duration` for machine time, `Period` for calendar | Arithmetic |
| `Timestamp` (`java.sql`) | DB timestamp | Wall-clock | Legacy; prefer `Instant` with JDBC 4.2 | DB interop |

**Critical idioms:**

```java
// CORRECT: measure elapsed time with monotonic clock
long start = System.nanoTime();
doWork();
long elapsedMs = (System.nanoTime() - start) / 1_000_000;

// WRONG: this can be negative or wildly off if NTP steps the clock
long badStart = System.currentTimeMillis();
doWork();
long badElapsed = System.currentTimeMillis() - badStart; // BUG under clock step
```

> **Beginner note — `currentTimeMillis()` resolution.** On many systems the *value* is in milliseconds but the *update granularity* of the underlying OS counter is coarser (historically ~10–16 ms on Windows). So two rapid calls can return the same value. `nanoTime()` is much finer.

### 4.2 OS / CLI time tools

| Tool / call | Purpose | Key flags / notes |
|---|---|---|
| `clock_gettime(2)` | Linux syscall for clocks | `CLOCK_REALTIME` (wall, steppable), `CLOCK_MONOTONIC` (no steps, but slewed by NTP), `CLOCK_MONOTONIC_RAW` (hardware, no NTP adjustment), `CLOCK_BOOTTIME` (includes suspend), `CLOCK_TAI` (atomic, no leap seconds) |
| `adjtime(3)` / `adjtimex(2)` | Gradually slew the clock | Used by NTP for small corrections instead of stepping |
| `chronyc` | Control/inspect **chrony** (modern NTP daemon, better than ntpd for VMs and intermittent networks) | `chronyc tracking` (offset, drift, **root dispersion** = error estimate), `chronyc sources -v`, `chronyc sourcestats` |
| `ntpq -p` | Query classic ntpd peers | Shows offset, jitter, reach; `*` = selected sync peer |
| `timedatectl` | systemd time control | `timedatectl status`, `timedatectl set-ntp true` |
| `ntpdate` | One-shot step (deprecated) | Avoid in production (causes jumps) |
| `phc2sys` / `ptp4l` (linuxptp) | **PTP (Precision Time Protocol, IEEE 1588)** | Hardware-timestamped sub-microsecond sync within a LAN; needs NIC/switch support |
| `date` | Read/set wall clock | `date -u`, `date +%s%N` (ns) |
| `hwclock` | Read/write RTC (hardware clock) | `hwclock --systohc` |

> **Beginner note — chrony vs ntpd vs PTP.** **ntpd** is the classic NTP daemon. **chrony** is a newer implementation that handles unstable networks, virtual machines, and laptops that sleep far better — most cloud distros default to it now. **PTP** is a different protocol entirely for *very* high precision (sub-µs) inside a single LAN using hardware timestamps in the NIC; it's how finance and TrueTime-style systems get tight bounds. **Root dispersion** (chrony's term) is the accumulated maximum error estimate — the closest classic NTP gets to TrueTime's ε.

### 4.3 Logical/hybrid clock building blocks (what you implement or import)

| Component | Purpose | Notes |
|---|---|---|
| Lamport counter | total order consistent with `→` | One `long`; bump on event, `max+1` on receive |
| Vector clock | exact causality | `Map<NodeId,long>` or `long[]`; merge = element-wise max |
| Dotted version vector (DVV) | causality with bounded size | Riak; O(replicas) not O(clients) |
| HLC | causal + near-physical | Pack `(physical, counter)` into a `long`; libraries exist for Go (CockroachDB's), JS, Java ports |
| Interval Tree Clock (ITC) | dynamic identities | fork/join/event ops |

### 4.4 Datastore-level ordering features (vendor-specific — flagged)

| System | Mechanism | Notes / flags |
|---|---|---|
| **Spanner** (Google Cloud) | TrueTime + commit-wait | External consistency by default; `staleness` bounds for stale reads; ε exposed internally |
| **CockroachDB** | HLC + `--max-offset` | Default `--max-offset=500ms`; clocks beyond this → node *crashes itself*; "uncertainty interval" causes read restarts |
| **YugabyteDB** | HLC | `--max_clock_skew_usec` (default 500000 µs = 500 ms) |
| **MongoDB** | `$clusterTime` (HLC-like), `afterClusterTime` | Causal consistency via causally consistent sessions; signed cluster time to prevent forgery |
| **Cassandra** | LWW by cell timestamp (µs) | `USING TIMESTAMP`; **clock skew = silent data loss**; client-provided or server-side timestamps |
| **DynamoDB / Riak** | (Riak) vector clocks / DVV; Dynamo paper | Siblings on concurrent writes; `allow_mult` |
| **Kafka** | per-partition offset = total order *within* partition | No cross-partition order; `log.message.timestamp.type` = `CreateTime`/`LogAppendTime` |

---

## 5. Code examples by use case

All Java unless noted. These are designed to be adapted, not just read.

### 5.1 Lamport clock (thread-safe), for total-order multicast tiebreaking

```java
import java.util.concurrent.atomic.AtomicLong;

/** A process-local Lamport logical clock. Thread-safe. */
public final class LamportClock {
    private final AtomicLong counter = new AtomicLong(0);

    /** Call on any local event (including before sending). Returns the event's timestamp. */
    public long tick() {
        return counter.incrementAndGet();
    }

    /** Call when receiving a message stamped with `received`. Returns the receive event's timestamp. */
    public long update(long received) {
        // C := max(C, received) + 1  — done atomically against concurrent ticks.
        return counter.updateAndGet(local -> Math.max(local, received) + 1);
    }

    public long peek() { return counter.get(); }
}
```

Using it to produce a **total order** (Lamport timestamp, then node id as tiebreaker):

```java
public record OrderedEvent(long lamport, int nodeId, String payload)
        implements Comparable<OrderedEvent> {
    @Override public int compareTo(OrderedEvent o) {
        int c = Long.compare(this.lamport, o.lamport);
        return c != 0 ? c : Integer.compare(this.nodeId, o.nodeId); // deterministic tiebreak
    }
}
```

**What matters:** `update` uses `updateAndGet` so concurrent `tick()`s on other threads can't lose the `max`. The `nodeId` tiebreaker turns the partial order into a *total* order, but remember (§3.1) that order between concurrent events is arbitrary.

### 5.2 Vector clock with full comparison (causality detection)

```java
import java.util.*;

public final class VectorClock {
    private final Map<String, Long> v = new HashMap<>();
    private final String selfId;

    public VectorClock(String selfId) { this.selfId = selfId; }

    /** Local event / before send. */
    public synchronized void increment() {
        v.merge(selfId, 1L, Long::sum);
    }

    /** On receive: element-wise max, then bump self. */
    public synchronized void merge(Map<String, Long> other) {
        for (var e : other.entrySet()) {
            v.merge(e.getKey(), e.getValue(), Long::max);
        }
        v.merge(selfId, 1L, Long::sum);
    }

    public synchronized Map<String, Long> snapshot() { return new HashMap<>(v); }

    public enum Ord { BEFORE, AFTER, EQUAL, CONCURRENT }

    /** Compare two vector clock snapshots. */
    public static Ord compare(Map<String, Long> a, Map<String, Long> b) {
        boolean aLessSomewhere = false, aGreaterSomewhere = false;
        Set<String> keys = new HashSet<>();
        keys.addAll(a.keySet()); keys.addAll(b.keySet());
        for (String k : keys) {
            long av = a.getOrDefault(k, 0L);
            long bv = b.getOrDefault(k, 0L);
            if (av < bv) aLessSomewhere = true;
            if (av > bv) aGreaterSomewhere = true;
        }
        if (!aLessSomewhere && !aGreaterSomewhere) return Ord.EQUAL;
        if (aLessSomewhere && !aGreaterSomewhere) return Ord.BEFORE;   // a → b
        if (!aLessSomewhere && aGreaterSomewhere) return Ord.AFTER;    // b → a
        return Ord.CONCURRENT;                                          // a ∥ b
    }
}
```

**What matters:** `compare` is the entire payoff — it returns `CONCURRENT`, which scalar clocks cannot. Use this to decide "clean overwrite vs keep-both siblings" in a Dynamo-style store.

### 5.3 Hybrid Logical Clock packed into a single `long`

```java
/**
 * HLC packed into a 64-bit long: high 48 bits = physical millis, low 16 bits = logical counter.
 * Single-node thread-safety via synchronized; use one instance per node.
 */
public final class HybridLogicalClock {
    private static final int COUNTER_BITS = 16;
    private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;       // 0xFFFF
    private static final int MAX_COUNTER = (int) COUNTER_MASK;

    private long l = 0;   // last physical high-water mark (millis)
    private int  c = 0;   // logical counter

    private long physicalNow() { return System.currentTimeMillis(); }

    public synchronized long now() {          // local event or send
        long pt = physicalNow();
        long lOld = l;
        l = Math.max(lOld, pt);
        c = (l == lOld) ? c + 1 : 0;
        guardCounter();
        return pack(l, c);
    }

    public synchronized long update(long received) {  // on receive
        long lm = unpackPhysical(received);
        int  cm = unpackCounter(received);
        long pt = physicalNow();
        long lOld = l;
        l = Math.max(Math.max(lOld, lm), pt);
        if (l == lOld && l == lm)      c = Math.max(c, cm) + 1;
        else if (l == lOld)            c = c + 1;
        else if (l == lm)              c = cm + 1;
        else                           c = 0;          // l == pt, physical ahead of both
        guardCounter();
        return pack(l, c);
    }

    private void guardCounter() {
        // If the counter overflows, physical time is badly behind — fail loud rather than corrupt order.
        if (c > MAX_COUNTER) throw new IllegalStateException("HLC counter overflow: clock skew too large");
    }

    static long pack(long physMillis, int counter) {
        return (physMillis << COUNTER_BITS) | (counter & COUNTER_MASK);
    }
    static long unpackPhysical(long ts) { return ts >>> COUNTER_BITS; }
    static int  unpackCounter(long ts)  { return (int) (ts & COUNTER_MASK); }
}
```

**What matters:** the packed `long` makes comparison a single `Long.compare`, which is exactly how MVCC stores and orders versions. The overflow guard turns "clocks too skewed" into a loud failure instead of silent ordering corruption.

### 5.4 Testable, injectable time (the single most useful production pattern)

```java
import java.time.*;

public class LeaseManager {
    private final Clock clock;                 // inject — never call Instant.now() directly
    private final Duration leaseDuration;
    private volatile Instant leaseExpiry = Instant.MIN;

    public LeaseManager(Clock clock, Duration leaseDuration) {
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    public synchronized void renew() {
        leaseExpiry = clock.instant().plus(leaseDuration);
    }

    public boolean holdsLease() {
        return clock.instant().isBefore(leaseExpiry);
    }
}
```

Test, with time fully under control:

```java
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.*;

class LeaseManagerTest {
    @Test void leaseExpires() {
        Instant t0 = Instant.parse("2030-01-01T00:00:00Z");
        // mutable test clock
        MutableClock clock = new MutableClock(t0, ZoneOffset.UTC);
        LeaseManager lm = new LeaseManager(clock, Duration.ofSeconds(10));
        lm.renew();
        assertTrue(lm.holdsLease());
        clock.advance(Duration.ofSeconds(9));
        assertTrue(lm.holdsLease());
        clock.advance(Duration.ofSeconds(2));   // now 11s > 10s lease
        assertFalse(lm.holdsLease());
    }
}

class MutableClock extends Clock {
    private Instant instant; private final ZoneId zone;
    MutableClock(Instant i, ZoneId z) { this.instant = i; this.zone = z; }
    void advance(Duration d) { instant = instant.plus(d); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(instant, z); }
    @Override public Instant instant() { return instant; }
}
```

**What matters:** injecting `Clock` makes all time-dependent logic deterministically testable and lets you simulate clock skew, leap smears, and expiry without `Thread.sleep`. This is the antidote to flaky time-based tests.

### 5.5 Causally-safe Last-Writer-Wins register using HLC

```java
/** A replicated register where the highest HLC wins. Causally correct (unlike wall-clock LWW). */
public final class LwwRegister<T> {
    private volatile long ts = Long.MIN_VALUE;  // packed HLC
    private volatile T value;

    /** Apply a remote/local write stamped with an HLC timestamp. Returns true if it won. */
    public synchronized boolean assign(T newValue, long hlcTs) {
        if (hlcTs > ts) {            // strictly greater HLC wins
            ts = hlcTs; value = newValue;
            return true;
        }
        // equal or older: ignore (ties resolved by encoding node id into low bits in real systems)
        return false;
    }
    public T get() { return value; }
    public long timestamp() { return ts; }
}
```

**What matters:** because HLC respects happens-before, a write that *causally* followed another always has a larger `hlcTs`, so the right value wins. Pure-wall-clock LWW could let a skewed-fast node overwrite a logically newer value — silent data loss. (Tiebreak truly-concurrent equal stamps by appending node id to the low bits.)

### 5.6 OR-Set CRDT (observed-remove set) — order-independent merge

```java
import java.util.*;

/**
 * OR-Set: add wins over concurrent remove; merge is commutative/idempotent.
 * Each add is tagged with a unique dot (node + counter) so removes only cancel adds they observed.
 */
public final class ORSet<E> {
    record Dot(String node, long seq) {}
    private final Map<E, Set<Dot>> adds = new HashMap<>();
    private final Set<Dot> tombstones = new HashSet<>();
    private final String node; private long seq = 0;

    public ORSet(String node) { this.node = node; }

    public synchronized void add(E e) {
        adds.computeIfAbsent(e, k -> new HashSet<>()).add(new Dot(node, ++seq));
    }
    public synchronized void remove(E e) {
        Set<Dot> dots = adds.get(e);
        if (dots != null) { tombstones.addAll(dots); dots.clear(); } // only remove OBSERVED adds
    }
    public synchronized Set<E> values() {
        Set<E> out = new HashSet<>();
        for (var en : adds.entrySet())
            if (en.getValue().stream().anyMatch(d -> !tombstones.contains(d))) out.add(en.getKey());
        return out;
    }
    public synchronized void merge(ORSet<E> other) {
        for (var en : other.adds.entrySet())
            adds.computeIfAbsent(en.getKey(), k -> new HashSet<>()).addAll(en.getValue());
        tombstones.addAll(other.tombstones);
        // prune tombstoned dots from live adds
        for (var dots : adds.values()) dots.removeAll(tombstones);
    }
}
```

**What matters:** the *dot* (a per-node monotonic counter, i.e., a slice of a version vector) lets a remove cancel only the adds it *observed*. Concurrent add-then-remove resolves to "present" (add-wins) deterministically — no clock needed, no order dependence. This is causality tracking applied to conflict-free convergence.

### 5.7 Detecting clock skew at the application layer (defensive)

```java
/** Reject/flag messages whose embedded timestamp implies impossible clock skew. */
public final class SkewGuard {
    private final long maxSkewMillis;            // e.g., 500
    public SkewGuard(long maxSkewMillis) { this.maxSkewMillis = maxSkewMillis; }

    public void checkInbound(long remoteWallClockMillis) {
        long local = System.currentTimeMillis();
        long skew = Math.abs(local - remoteWallClockMillis);
        if (skew > maxSkewMillis) {
            // In CockroachDB-style systems, a node exceeding max_offset removes itself.
            throw new IllegalStateException(
                "Clock skew " + skew + "ms exceeds max " + maxSkewMillis + "ms — refusing to proceed");
        }
    }
}
```

**What matters:** this mirrors what HLC/CockroachDB do — bound the trust you place in physical time and fail fast when reality violates the assumption, rather than silently mis-ordering.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Logical clocks are cheap:** Lamport = one atomic add; HLC = a synchronized read of `currentTimeMillis()` plus integer ops. The contention point is the lock/atomic if you stamp at very high rates; shard clocks per partition/thread when needed (each partition can keep its own HLC).
- **`System.currentTimeMillis()` and `nanoTime()` are not free.** Under heavy load and on some virtualized clock sources, a `gettimeofday`/`clock_gettime` can cost tens to hundreds of nanoseconds. On Linux with `vDSO` (virtual dynamic shared object, a kernel mechanism that lets certain syscalls run in user space without a context switch) these are fast; without it (e.g., some hypervisor clock sources like Xen's), they fall back to a real syscall and become a bottleneck. **Check your `clocksource`:** `cat /sys/devices/system/clocksource/clocksource0/current_clocksource` — prefer `tsc` (CPU timestamp counter) or `kvm-clock` over `hpet`/`acpi_pm`.
- **TrueTime's cost is latency, not CPU:** commit-wait adds ~2·ε (a few ms) to *every* read-write transaction. Reducing ε directly reduces this — hence Google's time-master investment.
- **Vector clock cost is space and bandwidth:** O(N) per message. With many actors this dominates payloads. Use DVV/ITC or prune.

### 6.2 Correctness & concurrency

- **Never order events across machines by wall-clock.** This is the cardinal rule. Use logical/hybrid clocks or a real coordination service.
- **Monotonic for durations, wall-clock for timestamps — never swap them.**
- **Guard against counter overflow** in HLC (it signals skew too large — fail loud, §5.3).
- **Make clock access injectable** (`java.time.Clock`) so logic is testable and skew is simulatable.
- **Be explicit about ties.** Equal Lamport/HLC values need a deterministic tiebreaker (node id) or you get nondeterministic merges.

### 6.3 Security

- **Spoofed timestamps.** If clients supply timestamps (Cassandra `USING TIMESTAMP`), a malicious or buggy client can write "from the future" and make its value permanently win LWW (a denial-of-correctness). Validate/clamp client timestamps server-side.
- **GPS spoofing.** TrueTime uses GPS, which can be spoofed; that's *why* Spanner also deploys independent atomic clocks — uncorrelated failure domains.
- **NTP as an attack surface.** NTP has had amplification-DDoS and time-shifting attacks (move a victim's clock to expire/revalidate certs or replay nonces). Use authenticated time (NTS — *Network Time Security*, RFC 8915) where possible, and restrict NTP servers.
- **`$clusterTime` signing** (MongoDB) prevents clients from forging cluster time to skip ahead.

### 6.4 Observability

- **Always log both a wall-clock timestamp (UTC, ISO-8601) and, where ordering matters, the logical/HLC timestamp.** Human triage needs wall-clock; causal analysis needs logical.
- **Monitor clock offset/dispersion**: `chronyc tracking` (System time offset, Root dispersion), Prometheus `node_timex_offset_seconds`, `node_timex_maxerror_seconds`. Alert when offset approaches your `max_offset`/skew budget.
- **Emit HLC counter values**; a persistently high counter `c` means physical time is lagging (skew) — an early warning.
- **Trace context** (OpenTelemetry) should carry a logical/causal hint, not rely on span start-times to order cross-service causality.

### 6.5 Cost

- **TrueTime** = capex (GPS antennas, atomic clocks, redundant time masters) + opex (engineering). Justified at Google scale; rarely DIY-able, though cloud providers now sell tightly-bounded time (e.g., AWS Time Sync Service with the **ClockBound** library exposing an error bound; Google Cloud exposes TrueTime to Spanner customers).
- **Commit-wait** = throughput/latency cost on writes. Tighter ε directly buys back latency.
- **Vector clocks** = bandwidth/storage cost that grows with actor count.

### 6.6 Testing

- **Inject `Clock`.** Use `Clock.fixed` / a mutable clock (see §5.4). Never `Thread.sleep` to test expiry.
- **Simulate skew and steps**: feed nodes clocks that disagree; assert ordering still holds (logical) or that the system refuses unsafe operations (HLC overflow, CockroachDB self-removal).
- **Property-based tests** for vector-clock comparison: generate random event DAGs, assert `compare` matches the ground-truth happens-before.
- **Deterministic simulation** (à la FoundationDB) — run the whole cluster in one process with a controllable virtual clock to find ordering bugs.
- **Jepsen** — the standard tool for testing distributed consistency under partitions and clock skew; it can inject clock skew (`--max-clock-skew`) to validate that your LWW/causal claims hold.

### 6.7 Production hardening checklist

- Run **chrony** (not ad-hoc `ntpdate`), with multiple upstream sources, and alert on offset/dispersion.
- Set and enforce a **max clock offset** (CockroachDB `--max-offset`, Yugabyte `--max_clock_skew_usec`); ensure nodes self-fence beyond it.
- Use **leap smear** or `CLOCK_TAI`-aware handling so leap seconds don't cause jumps. Confirm your cloud provider's smear matches across your fleet (mixing a smeared and non-smeared source within the smear window causes skew!).
- Disable NTP **stepping** in latency-critical clusters where a backward jump breaks invariants; prefer slewing (but understand slewing changes the *rate* and so subtly affects `nanoTime` consistency too on some platforms — `CLOCK_MONOTONIC` is slewed; `CLOCK_MONOTONIC_RAW` is not).
- Store and transmit timestamps in **UTC**; never `LocalDateTime`.

### 6.8 Anti-patterns

| Anti-pattern | Why it bites | Fix |
|---|---|---|
| Ordering distributed events by `currentTimeMillis()` | Skew inverts order; data loss in LWW | Logical/HLC clock |
| Measuring elapsed time with wall-clock | NTP step → negative/huge durations | `nanoTime()` / `CLOCK_MONOTONIC` |
| Trusting client-supplied timestamps for LWW | "Write from the future" wins forever | Server-side stamp / clamp |
| Unbounded vector clocks with many clients | Payload bloat | DVV / ITC / per-server vectors |
| Assuming NTP gives an error bound | It gives a value, not a proven bound | Use root dispersion / ClockBound / TrueTime |
| Mixing smeared and unsmeared time sources | Two clocks disagree by up to 1s during smear | One smear policy fleet-wide |
| Comparing `nanoTime()` across machines | Arbitrary, drifting origins | Never do it |
| Using wall-clock for lease/lock safety without fencing | Skew → two holders (split brain) | Fencing tokens (monotonic) + bounded leases |

---

## 7. Advanced topics & deep internals

### 7.1 CockroachDB's HLC + uncertainty intervals (a concrete deep dive)

CockroachDB (CRDB) uses HLC for all MVCC timestamps. Because clocks are only *loosely* synced (NTP/chrony, not TrueTime), CRDB cannot know exact real-time order, so it introduces an **uncertainty interval**:

- A configurable **`--max-offset`** (default 500 ms) is the assumed worst-case clock skew between any two nodes.
- When a transaction with timestamp `t` reads a key and finds a committed version with a timestamp in `(t, t + max_offset]`, it *cannot tell* whether that write really happened before or after its own start in real time. This is the **uncertainty window**.
- CRDB then performs an **uncertainty restart**: it bumps the transaction's read timestamp above the observed value and retries, ensuring it observes a causally consistent snapshot. This trades some retries for correctness without TrueTime hardware.
- **Self-fencing:** if a node detects its clock has drifted beyond `max_offset` relative to a quorum of peers (measured via heartbeat round-trips), it **terminates itself** rather than risk serving stale-but-confident data. This is HLC's safety valve in the absence of a proven bound.

This is essentially "TrueTime for the rest of us": instead of waiting out a *proven* ε, CRDB *restarts* transactions that fall in an *assumed* uncertainty window. The tradeoff: cheaper hardware, occasional read restarts and higher tail latency, and a hard dependency on clocks staying within `max_offset` (violate it and you can get stale reads — a real risk if NTP misbehaves).

### 7.2 Spanner edge cases & tuning

- **ε sawtooth:** ε grows linearly between time-master polls (drift) and snaps down on resync. The *worst-case* ε (just before a poll) dictates commit-wait; reducing the poll interval lowers ε at the cost of more time-master load.
- **Geo-distributed Paxos groups:** Spanner replicates each split via **Paxos** (a consensus protocol — see Glossary) across regions; the commit-wait interacts with cross-region RTT. The leader does commit-wait, and a smart implementation overlaps the wait with the 2PC/replication latency it would pay anyway (the wait is partly "free" because the network round-trips already consume time).
- **TrueTime degradation:** if GPS+atomic references degrade, ε balloons → commit-wait balloons → write latency spikes (observable as the system "slowing down" rather than returning wrong answers).
- **Read-your-writes & bounded staleness:** stale reads pick an older timestamp (`exact_staleness`/`max_staleness`) to read locally without waiting — a knob trading freshness for latency.

### 7.3 Closed timestamps / safe time

In MVCC systems, replicas advertise a **closed timestamp** (a.k.a. "safe time"): a timestamp below which *no more writes will arrive*. Follower replicas can then serve consistent reads at or below that timestamp without contacting the leader (CRDB "follower reads", Spanner snapshot reads). The mechanism: the leader periodically closes out a timestamp slightly in the past (e.g., `now − target_duration`) and propagates it; this is pure ordering machinery enabling local, coordination-free reads.

### 7.4 Matrix clocks and causal stability

A **matrix clock** is N vector clocks (an N×N matrix): row i is process i's knowledge of everyone's vector clock. It lets a process know *what everyone knows*, enabling **causal stability** detection — knowing when a message has been seen by *all* nodes so its metadata can be garbage-collected. Expensive (O(N²) per message); used in specialized causal-broadcast systems.

### 7.5 Plausible clocks / Resettable Encoded Vector Clocks

For huge dynamic systems, research clocks (e.g., **REVC**, **Bloom-clock**) trade exactness for bounded size: they can have *false positives* (say "ordered" when concurrent) but never *false negatives*, with probabilistic guarantees. Used where exact vector clocks are infeasible and rare misclassification is tolerable.

### 7.6 Leap second handling internals

- **Repeat (POSIX default):** the kernel can step `CLOCK_REALTIME` back by 1 s, so `time()` returns the same second twice — breaks monotonic assumptions on wall-clock.
- **Leap smear:** slow the clock over a window (Google: ~24 h linear, centered on the leap; some use 20 h). During the smear, a smeared host disagrees with a non-smeared NTP source by up to ~1 s — never mix.
- **`CLOCK_TAI`:** International Atomic Time has *no* leap seconds; the kernel knows the current TAI−UTC offset (currently 37 s as of 2024) and `CLOCK_TAI` advances smoothly. Best basis for ordering if available, but you must convert for human display.

### 7.7 Why happens-before can't capture *all* causality

Lamport's `→` captures causality *through the system's own channels* (messages + program order). It misses **hidden channels**: if process A writes a file that a human reads and then tells process B about, A's event causally influenced B's, but no in-system message records it — so `→` says they're concurrent. **External consistency (TrueTime)** is precisely the attempt to close this gap using *real time* as the universal channel. This is the deep reason TrueTime exists: to order events that have *no in-system causal link* yet have a real-time relationship a human can observe.

### 7.8 Interaction with consensus

Consensus protocols (Paxos, Raft) provide a **total order** of log entries *without* trusting clocks for correctness — they use **terms/epochs** (monotonic integers chosen by leaders) and quorum intersection, not wall time. Clocks appear only as *liveness/performance* helpers: election timeouts (Raft), lease-based leadership to avoid read coordination, and to bound how long a stale leader can serve. **Key principle: never use a clock for *safety* in consensus; use it only for *liveness* (timeouts) and *optimization* (leases with fencing).** A lease's safety still rests on a bounded clock skew assumption + fencing tokens, which is why lease-based reads are an explicit, carefully-bounded optimization.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Clock-mechanism comparison

| Property | Wall-clock (NTP) | Lamport | Vector clock | HLC | TrueTime |
|---|---|---|---|---|---|
| Captures causality | No | One-way (`a→b ⇒ ts↑`) | **Exact** (detects concurrency) | One-way | Yes (via commit-wait) + real time |
| Detects concurrency | No | **No** | **Yes** | No | N/A (uses real time) |
| Relation to real time | Approx (±ms–tens ms) | **None** | **None** | Close to physical | **Exact ± ε (proven)** |
| Size per timestamp | 8 B | 8 B | O(N) | 8–16 B | interval (2 timestamps) |
| Hardware cost | Cheap | Cheap | Cheap | Cheap | **Expensive** (GPS+atomic) |
| Main runtime cost | none | none | bandwidth O(N) | none | **commit-wait latency ~2ε** |
| Used by | logs everywhere | total-order multicast | Dynamo, Riak | CockroachDB, Yugabyte, Mongo | Spanner |
| Fails by | silent mis-order | can't detect conflicts | bloat | counter overflow / skew → restart/self-fence | slows/stops (safe) |

### 8.2 Consistency model vs mechanism

| Want | Minimum mechanism | Notes |
|---|---|---|
| Human-readable "when" | NTP wall-clock | Never for correctness |
| Detect concurrent writes / keep siblings | Vector clock / DVV | App resolves conflicts |
| Causal consistency (cross-node `a→b` order) | Vector clock or HLC | Strongest AP-compatible model |
| Causally-safe LWW (auto-resolve) | HLC | Tiebreak by node id |
| MVCC / time-travel reads | HLC (or TrueTime) | Needs near-physical timestamps |
| Serializability | HLC + uncertainty restarts (CRDB) **or** consensus | |
| External consistency / strict serializability | **TrueTime + commit-wait** | Only practical industrial answer |
| Total order of a replicated log | Consensus (Raft/Paxos) — terms, not clocks | Clocks only for timeouts |

### 8.3 Decision rules

- **Use Lamport when** you need *a* total order consistent with causality and don't care to detect concurrency (e.g., totally-ordered broadcast, a cheap monotonic sequence). **Avoid when** you must distinguish concurrent updates.
- **Use vector clocks when** you must *detect* concurrent updates to resolve conflicts (Dynamo-style) and the number of actors is bounded/manageable. **Avoid when** actors are unbounded (many clients) — use DVV/ITC instead.
- **Use HLC when** you want causal correctness *and* timestamps usable as wall-clock-ish values (TTLs, MVCC, time-bucketing), with constant size, and can tolerate an *assumed* skew bound + occasional restarts. **Avoid when** you need *proven* external consistency.
- **Use TrueTime/commit-wait when** you genuinely need external consistency across regions and can afford the hardware + per-write latency (financial ledgers, global SQL). **Avoid when** a few ms of write latency or the hardware/cloud lock-in is unacceptable and causal consistency suffices.
- **Use consensus (Raft/Paxos) for the *order itself*** when you need a single agreed total order with strong safety regardless of clocks; layer leases (with fencing) only as an optimization.

---

## 9. Failure modes & debugging

### 9.1 The classic incidents

- **Cassandra LWW data loss under clock skew.** Two replicas accept writes to the same cell; the one whose host clock was ahead "wins" regardless of true order, silently dropping the logically-newer write. **Diagnosis:** compare cell write times (`WRITETIME(col)` in CQL) against expected order; check per-node NTP offset history. **Fix:** server-side timestamps from a synced clock, tighten NTP, or move to HLC-based stores; consider CRDTs.
- **Split-brain from clock-based leases.** Node A's clock is slow, so it thinks its lease is still valid after it expired; the lock service already granted the lease to B. Both write → corruption. **Diagnosis:** overlapping lease validity in logs; two leaders. **Fix:** **fencing tokens** — every lease grant returns a monotonically increasing token; the storage layer rejects writes with a stale token. Now even a deluded A is rejected. (This is the canonical example from Kleppmann's *Designing Data-Intensive Applications*.)
- **Leap-second meltdowns (2012).** A Linux kernel bug on leap-second insertion caused livelock/high CPU across many companies (Reddit, LinkedIn, etc.) due to `hrtimer` handling. **Fix going forward:** leap smear; kernel patches; `CLOCK_TAI`.
- **NTP backward step breaks "monotonic" assumption.** Code measured timeouts with `currentTimeMillis()`; an NTP correction stepped the clock back; timeouts computed as negative or never firing/firing instantly. **Diagnosis:** negative durations in logs, sudden timeout storms after an NTP event. **Fix:** `nanoTime()`/`CLOCK_MONOTONIC`.
- **VM live-migration / pause.** A VM is paused (migration, host overcommit) for seconds; on resume the wall clock jumps forward, leases look expired, HLC counters may have been "stuck." **Diagnosis:** correlated pause across processes on a host, large monotonic gaps (`CLOCK_BOOTTIME` jumps). **Fix:** chrony fast re-sync, pause-aware lease renewal, generous `max-offset` or self-fence.
- **CockroachDB stale reads from skew beyond max-offset.** If NTP fails and a node's clock drifts past 500 ms without the node noticing in time, the uncertainty logic's assumption is violated → possible stale read. **Diagnosis:** `clock-offset` metrics, `liveness` logs; CRDB normally crashes the offending node. **Fix:** monitor offset, ensure self-fencing works, keep NTP healthy.

### 9.2 Diagnostic toolkit

| Symptom | Tool / command | What to look for |
|---|---|---|
| Suspected skew | `chronyc tracking` | `System time` offset, `Root dispersion`, `Frequency` (drift ppm) |
| NTP peer health | `ntpq -p` / `chronyc sources -v` | `*` selected peer, `reach`, `offset`, `jitter` |
| Clock source / vDSO | `cat /sys/devices/system/clocksource/.../current_clocksource` | prefer `tsc`/`kvm-clock`, avoid `hpet` |
| Drift / max error over time | `node_timex_offset_seconds`, `node_timex_maxerror_seconds` (Prometheus node_exporter) | trend toward your skew budget |
| Backward jumps | log both wall + `nanoTime`/`CLOCK_BOOTTIME` | wall decreases while monotonic increases ⇒ a step |
| Cross-service causal inversion | distributed trace + logged HLC/Lamport | child span "before" parent ⇒ skew, trust logical ts |
| Consistency under partition+skew | **Jepsen** | nemesis: `clock-skew`, partition; checker: linearizability/causal |

### 9.3 A debugging mindset

1. **Distrust wall-clock immediately** when ordering looks wrong across machines.
2. **Correlate with NTP/chrony events**: did an offset correction or step happen at the failure time?
3. **Prefer logical/HLC timestamps** in your logs for causal reasoning; use wall-clock only for "roughly when."
4. **Reproduce with injected skew** (Jepsen, simulated clocks) before claiming a fix.
5. **Verify self-fencing actually triggers** — many "we assume the node crashes itself" guarantees are never tested.

---

## 10. Interview drill

**Q1. Why can't you order events in a distributed system by `System.currentTimeMillis()`?**
*Model answer:* Each machine's wall clock drifts (ppm-level) and is corrected by NTP, which has ms-to-tens-of-ms error from asymmetric network paths and can *step the clock backward*. So one machine's "later" timestamp can correspond to an earlier real event. Ordering decisions (LWW, leases) based on it silently corrupt data. You need logical/hybrid clocks or proven-bounded time.
- *Probe: What's the difference between drift and skew?* Skew = instantaneous offset between clocks; drift = difference in their rates, which grows skew over time. Bound is `2ρΔ` over interval Δ at max drift ρ.
- *Probe: Which Java call is safe for measuring elapsed time and why?* `System.nanoTime()` — monotonic, immune to NTP steps; arbitrary origin so only valid as a *difference* within one JVM.
- *Probe: How does a leap second break naive code?* The UTC minute gets 61 seconds (`23:59:60`); kernels may repeat or step a second, so wall-clock can go backward or stall — leap smear avoids it.

**Q2. State the happens-before relation precisely. What does `a ∥ b` mean?**
*Model answer:* `a → b` if (1) same process, a before b in program order; (2) a is send of m, b is receive of m; (3) transitivity. `a ∥ b` (concurrent) iff neither `a → b` nor `b → a` — no chain of local steps and messages connects them, so neither could have causally influenced the other. `→` is a partial order.
- *Probe: Is happens-before about real time?* No — about potential causal influence via the system's own channels; concurrent events can be far apart in real time.
- *Probe: What causality does `→` miss?* Hidden/out-of-band channels (a human relays info between processes); that's what external consistency/TrueTime addresses.

**Q3. Lamport timestamps: state the algorithm and its key limitation.**
*Model answer:* Counter C; on local event/send `C++`; piggyback C; on receive `C = max(C, t)+1`. Guarantees `a→b ⇒ L(a)<L(b)`. **Limitation:** the converse is false — `L(a)<L(b)` does *not* imply `a→b`; can't detect concurrency. Total order via (L, nodeId) tiebreak is consistent with causality but invents order between concurrent events.

**Q4. How do vector clocks detect concurrency? Walk an example.**
*Model answer:* Per-process vector of N counters; local event bumps own index; receive does element-wise max then bumps own. `V<W` iff V≤W elementwise and V≠W (V→W); concurrent iff each has a strictly-greater component. (Walk a 2–3 node trace as in §3.2.) The element-wise comparison is exactly what yields a `CONCURRENT` verdict.
- *Probe: What's the size problem and a fix?* O(actors); with many clients vectors bloat → use **dotted version vectors** (O(replicas), Riak) or **interval tree clocks**.

**Q5. (Senior signal) When would you choose HLC over both NTP timestamps and vector clocks?**
*Model answer:* When you need causal correctness (`a→b ⇒ ts↑`) **and** timestamps that double as near-real-time values (for MVCC, TTLs, time-bucketed reads) **and** constant size. Vector clocks give exact causality but are O(N) and have no real-time meaning; NTP gives real time but mis-orders under skew. HLC is the practical middle ground (CockroachDB, Yugabyte, Mongo cluster time). Tradeoff: one-directional only (can't *detect* concurrency) and relies on a bounded-skew assumption + occasional uncertainty restarts.

**Q6. Explain TrueTime and commit-wait. Why does it give external consistency?**
*Model answer:* TrueTime returns an interval `[earliest, latest]` guaranteed to contain true time, width 2ε (a few ms), built from GPS + atomic clocks with quantified uncertainty. A read-write txn picks commit timestamp `s ≥ TT.now().latest`, then **waits until `TT.after(s)`** (≈2ε) before releasing locks. So any later-starting txn's `TT.now().latest` exceeds `s`, guaranteeing later real-time ⇒ larger timestamp ⇒ correct visible order = external consistency.
- *Probe: What's the cost?* ~2ε added to every write's latency — hence Google minimizes ε with time-master hardware.
- *Probe: What happens if ε blows up?* Commit-wait lengthens → the system slows or refuses, never returns wrong answers; misbehaving nodes self-remove. Safety over availability.
- *Probe: How is this different from NTP?* NTP gives a value; TrueTime gives a value *plus a proven worst-case error bound* you can wait out.

**Q7. (Senior signal) You're designing a multi-region key-value store. Pick a clock/ordering strategy and justify.**
*Model answer:* Depends on the consistency requirement. If the product needs *causal consistency* and high availability under partitions, use **HLC + version/dotted-version vectors** for conflict detection and CRDTs/siblings for resolution — no cross-region coordination on writes. If it needs *external/strict serializability* (e.g., a financial ledger), accept coordination: use **consensus (Raft/Paxos) per shard** for the order, and either TrueTime-style commit-wait (if available) or CRDB-style HLC + uncertainty restarts. Justify by partition behavior (CAP), latency budget (commit-wait vs restarts), and operational cost (GPS/atomic vs NTP).
- *Probe: How do you bound the blast radius of a bad clock?* Enforce `max-offset` and self-fence; fencing tokens on any lease; monitor offset/dispersion.

**Q8. (Senior signal) Last-writer-wins seems simple. Why is it dangerous, and how do you make it safe?**
*Model answer:* With wall-clock timestamps, the *faster* clock wins, not the *logically later* write — silent data loss under skew, and clients can write "from the future" to win forever (a correctness DoS). Make it safe by (a) using **HLC** so causal order ⇒ timestamp order (only truly-concurrent writes are arbitrarily broken), (b) server-side timestamping / clamping client values, and (c) where you can't tolerate any loss, keep **siblings** (vector clocks) or use **CRDTs** so concurrent writes merge instead of one being discarded.

**Q9. How do consensus protocols (Raft/Paxos) handle time, and why don't they trust clocks for safety?**
*Model answer:* They order entries via monotonic **terms/epochs** chosen by leaders plus quorum intersection — purely logical, no wall time for *safety*. Clocks are used only for *liveness* (election/heartbeat timeouts) and *optimization* (leader leases enabling local reads). Trusting clocks for safety would let skew cause two leaders to both believe they're valid; quorum + fencing prevents that.
- *Probe: Why are leader leases still risky?* Their safety rests on a bounded-skew assumption; pair them with fencing tokens and conservative lease lengths.

**Q10. (Senior signal) CockroachDB doesn't have TrueTime hardware. How does it still get serializability?**
*Model answer:* HLC timestamps + an assumed `--max-offset` (default 500 ms). On a read that finds a value within `(read_ts, read_ts+max_offset]`, it can't tell real-time order, so it performs an **uncertainty restart** at a higher timestamp to read a consistent snapshot; nodes that drift beyond max-offset **crash themselves**. It substitutes *cheap retries + an assumed bound* for TrueTime's *proven bound + commit-wait*. Tradeoff: cheaper hardware, occasional restart latency, and a hard dependency on NTP staying within max-offset.

**Q11. What is a vector-clock "sibling" and who resolves it?**
*Model answer:* When an incoming write's vector clock is *concurrent* with the stored one (neither dominates), the store keeps both values as siblings rather than choosing. Resolution is pushed to the application (merge function) or handled automatically by a CRDT. This avoids the data loss inherent in LWW for genuinely concurrent updates.

**Q12. (Senior signal) Your distributed trace shows a child span starting before its parent. Walk your diagnosis.**
*Model answer:* This is almost certainly clock skew between hosts, not real causality violation. Confirm by (1) checking per-host NTP/chrony offset at that time (`chronyc tracking`, node_exporter offset metric); (2) comparing logged logical/HLC timestamps (which respect happens-before) instead of wall-clock span start times; (3) checking for an NTP step or VM pause around that moment. Fix tracing to carry a causal hint and treat cross-host span times as ±skew. The "impossible" ordering is the clocks lying, exactly the core lesson of this topic.

---

## 11. Glossary

- **Atomic clock:** Timekeeping device based on atomic transitions (e.g., rubidium/cesium); extremely stable, used as a reference (stratum 0) and in TrueTime as a GPS-independent failure domain.
- **AP / CP (CAP):** In the **CAP theorem**, under a network **P**artition a system must choose **A**vailability (answer, possibly stale) or **C**onsistency (refuse to answer incorrectly). Causal consistency is the strongest model compatible with AP.
- **Causal consistency:** If `a → b`, all nodes observe `a` before `b`; concurrent ops may be seen in different orders. Achievable without sacrificing availability under partition.
- **`CLOCK_MONOTONIC` / `_RAW` / `_BOOTTIME` / `_TAI`:** Linux clock IDs. MONOTONIC (no steps, NTP-slewed), RAW (no NTP adjustment), BOOTTIME (includes suspend), TAI (atomic, no leap seconds).
- **Clock skew:** Instantaneous difference between two clocks. **Drift:** difference in their rates (ppm).
- **Closed timestamp / safe time:** A timestamp below which no more writes will arrive, letting replicas serve consistent local reads.
- **Commit-wait:** Spanner's step of waiting until a commit timestamp is *definitely* in the past (≈2ε) before releasing locks, yielding external consistency.
- **Consensus (Paxos / Raft):** Protocols for a set of nodes to agree on a single value/total order of log entries despite failures, using quorums and monotonic terms — not clocks for safety.
- **CRDT (Conflict-free Replicated Data Type):** Data types whose concurrent updates merge commutatively/idempotently, so replicas converge regardless of message order.
- **Dotted version vector (DVV):** Vector clock variant tracking per-server causality plus a per-event "dot"; size O(replicas) not O(clients). Used by Riak.
- **External consistency / strict serializability:** If T1 commits before T2 starts in real time, T2 sees T1's effects and orders after it. Spanner's guarantee.
- **ε (epsilon):** TrueTime's half-width of time uncertainty; interval width is 2ε (a few ms in Spanner).
- **Fencing token:** A monotonically increasing number issued with a lease; storage rejects writes bearing a stale token, preventing split-brain even with clock skew.
- **Happens-before (`→`):** Lamport's partial order: program order + send-before-receive + transitivity. Captures potential causal influence.
- **HLC (Hybrid Logical Clock):** A `(physical, counter)` timestamp that respects happens-before *and* stays close to physical time, in constant size.
- **Interval Tree Clock (ITC):** Causality clock supporting dynamic fork/join/retire of identities, so size tracks active actors.
- **Lamport timestamp:** Scalar logical clock; `a→b ⇒ L(a)<L(b)` (one-way). Cannot detect concurrency.
- **Leap second:** A 1-second adjustment to UTC for Earth-rotation irregularity; can make a minute 61 s. **Leap smear** spreads it over hours to avoid jumps.
- **Linearizable:** Each operation appears atomic at a single point between call and return, consistent with real-time order; the strongest single-object consistency.
- **LWW (Last-Writer-Wins):** Conflict resolution keeping the highest-timestamped write; unsafe with wall-clock under skew, safe-ish with HLC.
- **Marzullo's algorithm:** Method to compute the smallest interval consistent with a set of estimate intervals (rejecting outliers); basis for selecting time from multiple sources (NTP/TrueTime).
- **Matrix clock:** N vector clocks (N×N) giving each node knowledge of what every node knows; enables causal-stability GC. O(N²) cost.
- **Monotonic clock:** A clock that only moves forward at a steady rate, immune to time-setting (`System.nanoTime()`); for durations, not dates.
- **MVCC (Multi-Version Concurrency Control):** Storing multiple timestamped versions of data so readers see a consistent snapshot without blocking writers.
- **NTP (Network Time Protocol):** Protocol to sync wall clocks over a network via stratum hierarchy; ms-level accuracy, no proven error bound. **NTS** = secured NTP (RFC 8915).
- **PTP (IEEE 1588):** Precision Time Protocol; sub-microsecond LAN sync using hardware timestamps.
- **Quorum:** A majority (or intersecting subset) of nodes whose agreement guarantees overlap with any other quorum; the safety basis of consensus.
- **Root dispersion:** chrony/NTP's estimate of maximum accumulated time error — the closest classic NTP gets to an error bound.
- **Sibling:** Two concurrent values kept side-by-side because their vector clocks don't dominate each other.
- **Slew vs step:** Slew = gradually adjust clock *rate* to correct; step = jump the clock value. NTP slews small corrections, steps large ones (default >128 ms).
- **Spanner:** Google's globally-distributed SQL database providing external consistency via TrueTime + commit-wait.
- **Stratum:** NTP distance from the reference clock (0 = reference, 1 = directly attached, etc.).
- **TrueTime:** Google's time API returning an interval guaranteed to contain true time, with bounded uncertainty 2ε.
- **TSC (Time Stamp Counter):** A CPU register counting cycles; fast clock source for `clock_gettime` via vDSO when reliable.
- **Uncertainty interval / restart:** CockroachDB's window `(read_ts, read_ts+max_offset]` where real-time order is unknown; reads in it restart at a higher timestamp.
- **Vector clock:** Per-process vector of counters capturing causality exactly, including concurrency detection. Size O(actors).
- **vDSO:** Kernel mechanism mapping certain syscalls (like `clock_gettime`) into user space to avoid the syscall cost.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Golden rules**
- Wall-clock = "roughly when," never for ordering. Monotonic (`nanoTime`) = durations only, per-machine.
- `a → b ⇒ logical_ts(a) < logical_ts(b)`. Converse holds *only* for vector clocks (concurrency-detecting).
- Inject `java.time.Clock`; test with `Clock.fixed`/mutable clock.
- Fencing tokens (monotonic) protect leases regardless of clock skew.

**Mechanisms in one line each**
- **Lamport:** one int; `recv: C=max(C,t)+1`. Total order, no concurrency detection.
- **Vector:** int per node; merge = elementwise max; **detects `∥`**; size O(N).
- **HLC:** `(physical, counter)` packed in a long; causal + near-real-time; constant size.
- **TrueTime:** interval `[earliest, latest]`, width 2ε; **commit-wait ≈2ε** ⇒ external consistency.

**Numbers to remember**
- Crystal drift: ~10–100 ppm (~8.6 s/day at 100 ppm).
- NTP accuracy: ms (datacenter) to tens of ms (internet); step threshold ~128 ms.
- Spanner ε: ~1–7 ms, mean ~4 ms; time-master poll ~30 s; assumed drift ~200 µs/s.
- CockroachDB `--max-offset` default: 500 ms (node self-crashes beyond it).
- Leap smear window: ~24 h (Google); leap second makes a minute 61 s.

**Decision shortcut**
- Need concurrency detection → vector/DVV. Need causal + real-time-ish → HLC. Need external consistency → TrueTime + commit-wait. Need agreed total order → consensus (terms, not clocks). Just logging → NTP wall-clock.

**Top anti-patterns**
- Ordering by `currentTimeMillis()`; elapsed time via wall-clock; trusting client LWW timestamps; unbounded vector clocks; mixing smeared/unsmeared time; clock-based leases without fencing.

### 12.2 Self-test (no answers — active recall)

1. Prove that with Lamport timestamps, `L(a) < L(b)` does **not** imply `a → b`, using a concrete two-process trace. Then explain why vector clocks fix this and what it costs.
2. A node receives an HLC timestamp `(l_m=200, c_m=5)` while its own state is `(l=200, c=2)` and its physical clock reads `198`. Compute the resulting HLC and explain each branch you used.
3. Walk through *why* commit-wait of ~2ε is sufficient for external consistency. What exactly would break if Spanner skipped the wait? What if it waited only ε?
4. Your service uses wall-clock LWW. Describe two distinct production failures this can cause (one from skew, one from a malicious/buggy client) and the specific fix for each.
5. CockroachDB has no atomic clocks. Explain how it nonetheless provides serializability, what an "uncertainty restart" is, and the exact condition under which its guarantee can break.
6. Design fencing for a lease-based leader so that a node with a slow clock cannot corrupt data even if it believes its lease is still valid. What must the *storage layer* enforce?
7. Distinguish `CLOCK_MONOTONIC`, `CLOCK_MONOTONIC_RAW`, and `CLOCK_TAI`, and give one ordering/timekeeping scenario where each is the right choice.

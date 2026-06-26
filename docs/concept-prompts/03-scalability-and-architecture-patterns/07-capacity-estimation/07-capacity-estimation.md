# Capacity Estimation

> **Concept area:** Scalability & Architecture Patterns
> **Subtopic:** Capacity Estimation
> **Reader profile:** A senior Java/JVM backend developer who wants to fully master capacity estimation — from first principles to deep internals — well enough to design with it, operate it in production, teach it, and answer any interview question on it.

---

## Table of contents

1. [Overview & where it fits](#1-overview--where-it-fits)
2. [Foundations from first principles](#2-foundations-from-first-principles)
3. [How it works internally](#3-how-it-works-internally)
4. [The complete toolkit](#4-the-complete-toolkit)
5. [Code examples by use case](#5-code-examples-by-use-case)
6. [Implementation concerns & best practices](#6-implementation-concerns--best-practices)
7. [Advanced topics & deep internals](#7-advanced-topics--deep-internals)
8. [Tradeoffs & decision frameworks](#8-tradeoffs--decision-frameworks)
9. [Failure modes & debugging](#9-failure-modes--debugging)
10. [Interview drill](#10-interview-drill)
11. [Glossary](#11-glossary)
12. [Cheat-sheet & self-test](#12-cheat-sheet--self-test)

---

## 1. Overview & where it fits

### 1.1 What it is

**Capacity estimation** (also called **capacity planning** in its operational form, or **back-of-the-envelope estimation** in its interview/design form) is the discipline of predicting how many resources a system will consume so you can decide *how much hardware, money, and headroom* to provision. The resources you estimate are almost always the same five families:

1. **Compute / QPS** — how many requests per second (and CPU cores) the system must serve.
2. **Storage** — how many bytes you must persist (databases, blob stores, logs), today and over time.
3. **Bandwidth / throughput** — how many bytes per second must flow in and out (network egress/ingress).
4. **Memory** — how much RAM you need for caches, working sets, and per-connection overhead.
5. **Number of servers / instances** — the bottom line: how many boxes (or pods, or VMs) you must run, and therefore the cost.

Capacity estimation answers questions like: *"If we have 100 million daily active users, how many app servers do we need? How big does the database get in 5 years? Will a single Redis node hold the cache? How much will the CDN egress cost?"*

> **The phrase "back-of-the-envelope" (BOTE)** means a quick calculation you could literally do on the back of an envelope — using rounded numbers and simple arithmetic to get an answer that is correct to *within an order of magnitude* (a factor of ~10). It is named for the habit of physicists and engineers jotting rough estimates on whatever paper is nearby. The point is not precision; it is to rule out wildly wrong designs fast.

### 1.2 The problem it solves

Without capacity estimation you fall into one of two failure traps:

- **Under-provisioning:** you ship a design that physically cannot serve the load. The site falls over under launch traffic, the database disk fills, the cache thrashes, latency spikes, and you have an outage. Worse, you discover this *after* committing to an architecture that can't scale.
- **Over-provisioning:** you buy 100× the hardware you need "to be safe." You burn money, your unit economics break, and the over-built system is harder to operate. In the cloud this shows up directly on the monthly bill.

Capacity estimation gives you a *defensible, quantitative* basis for the design decision **before** you write code or sign a cloud contract. In a **system design interview**, it is the moment where you prove you can reason about scale numerically rather than hand-wave ("we'll just add more servers").

### 1.3 When you reach for it

| Situation | Why you estimate capacity |
|---|---|
| **System design interview** | The interviewer explicitly or implicitly expects QPS / storage / bandwidth math to justify your architecture (sharding, caching, CDN, etc.). |
| **Designing a new service** | To choose datastore, instance types, replication factor, and partition count. |
| **Planning a product launch / marketing spike** | To pre-scale and avoid the launch-day outage. |
| **Cloud cost reviews / FinOps** | To right-size instances and forecast spend. |
| **Capacity reviews / SRE** | To set autoscaling limits, alert thresholds, and procurement lead time. |
| **Database growth planning** | To decide when you must shard or archive before the disk fills. |
| **Quota / rate-limit design** | To translate user counts into per-tenant limits. |

> **SRE** = *Site Reliability Engineering*, Google's term for operations work done with software-engineering rigor. **FinOps** = *Financial Operations*, the practice of managing and forecasting cloud spend collaboratively between engineering and finance. **Autoscaling** = automatically adding/removing servers based on live load.

### 1.4 The one-paragraph mental model

> Capacity estimation is **dimensional reasoning with rounded numbers**. You start from a single anchor — usually **daily active users (DAU)** or **total requests** — and multiply or divide it through a chain of *ratios* and *unit conversions* (requests per user, bytes per request, seconds per day, reads per write) until you arrive at the resource you care about (QPS, GB, Gbps, server count). You deliberately round every input to one significant figure, convert all time bases to **seconds** and all data to **bytes**, and you always finish by multiplying by a **peak factor** and a **headroom factor** so your answer is the *provisioned* number, not the *average* number. The skill is choosing the right anchor, knowing a handful of memorized constants (seconds/day, latency numbers, QPS per box), and keeping units honest.

### 1.5 Where it sits among related disciplines

- **Capacity estimation (this doc):** *predict* resource needs from first principles, often before the system exists.
- **Load testing / benchmarking:** *measure* actual capacity of real hardware/code under synthetic load (e.g., with JMeter, Gatling, wrk2). Estimation predicts; load testing verifies.
- **Performance engineering:** *reduce* the resources each request consumes (lower CPU, fewer bytes).
- **Capacity management / autoscaling:** *operate* the system so live capacity tracks live demand.
- **Queueing theory / the USE & RED methods:** the *mathematical* and *observability* frameworks that make estimates rigorous (covered in §7).

Capacity estimation is the front of this pipeline: it produces the first numbers, which the others refine.

---

## 2. Foundations from first principles

This section builds the entire toolkit from zero. If you already know "QPS" and "seconds per day," skim — but the memorized numbers in §2.6 are non-negotiable.

### 2.1 The four base quantities and their units

Everything reduces to four physical quantities. Get the units right and the arithmetic follows.

| Quantity | Base unit | Common multiples | Per-time form |
|---|---|---|---|
| **Requests / operations** | 1 request | thousand, million, billion | **QPS** = requests/second |
| **Data / storage** | 1 byte (B) | KB, MB, GB, TB, PB | n/a (it's a stock, not a flow) |
| **Bandwidth / throughput** | bits/second OR bytes/second | Kbps, Mbps, Gbps; KB/s, MB/s | already per-time |
| **Time** | 1 second | minute, hour, day, year | n/a |

> **Stock vs flow:** A **stock** is an amount that exists at a point in time (total stored bytes, total users). A **flow** is a rate — an amount *per unit time* (QPS, bytes/second). Storage is a stock; bandwidth and QPS are flows. Confusing the two is the #1 estimation bug: "we store 1 TB" (stock) is not the same as "we write 1 TB/day" (flow).

> **Bits vs bytes — the eternal trap.** 1 **byte** (B) = 8 **bits** (b). Network speeds are quoted in **bits per second** (Mbps, Gbps — lowercase b), while file sizes and storage are quoted in **bytes** (MB, GB — uppercase B). A "1 Gbps" link moves about **125 MB/s**, not 1000 MB/s. Always convert: divide bits by 8 to get bytes. Confusing these gives an 8× error.

### 2.2 Decimal vs binary multiples (and why you can ignore the difference)

There are two conventions for "kilo / mega / giga":

| Prefix | Decimal (SI) | Binary (IEC) | Binary name |
|---|---|---|---|
| K | 10³ = 1,000 | 2¹⁰ = 1,024 | Ki (kibi) |
| M | 10⁶ = 1,000,000 | 2²⁰ = 1,048,576 | Mi (mebi) |
| G | 10⁹ | 2³⁰ ≈ 1.074×10⁹ | Gi (gibi) |
| T | 10¹² | 2⁴⁰ ≈ 1.10×10¹² | Ti (tebi) |

Disk vendors and networking use decimal; RAM and many tools use binary. The gap grows with size: at the terabyte level binary is ~10% larger than decimal. **For BOTE estimation, use decimal (powers of 10) everywhere** — it makes mental math trivial and the ~7–10% error is irrelevant when your inputs are already rounded to one significant figure. Only switch to binary when you are sizing RAM precisely or reconciling a billing line item.

### 2.3 The seconds-per-day constant (memorize this)

The single most useful conversion in all of capacity estimation:

> **1 day ≈ 86,400 seconds.** Round it to **~100,000 (10⁵) seconds/day** for mental math.

Why round 86,400 up to 100,000? Because it makes division trivial and it bakes in a small safety margin. So:

- **QPS ≈ requests per day ÷ 100,000.**
- Equivalently: **1 request/day ≈ 1.16 × 10⁻⁵ QPS**, and **1 QPS = 86,400 requests/day ≈ 100k/day**.

Other handy time conversions:

| Span | Seconds | Rounded |
|---|---|---|
| 1 minute | 60 | 60 |
| 1 hour | 3,600 | ~3.6k |
| 1 day | 86,400 | ~10⁵ |
| 1 month (30 d) | 2,592,000 | ~2.5–3 × 10⁶ |
| 1 year | 31,536,000 | ~3 × 10⁷ (≈ π × 10⁷, a famous mnemonic) |

> Mnemonic: **"A year is about π × 10⁷ seconds"** (3.14×10⁷ ≈ 31.4M, actual 31.5M). Useful when converting per-second flows into yearly storage.

### 2.4 The estimation chain: anchor → ratios → resource

Every estimate is a **chain of multiplications** that converts a starting *anchor* into a target resource by applying *ratios*. The general form:

```
target_resource = anchor × ratio₁ × ratio₂ × … × peak_factor × headroom
```

The classic chains:

**QPS chain:**
```
DAU × requests_per_user_per_day ÷ seconds_per_day = average QPS
average QPS × peak_factor = peak QPS
```

**Storage chain:**
```
writes_per_day × bytes_per_write × retention_days × replication_factor = total bytes
```

**Bandwidth chain:**
```
QPS × bytes_per_response = bytes/second  →  ×8 = bits/second
```

**Server-count chain:**
```
peak QPS ÷ QPS_per_server × (1 / target_utilization) = server count
```

Get fluent at writing the chain *first*, then plugging numbers. The chain is the reasoning; the numbers are mechanical.

### 2.5 Ratios you will be handed or must assume

These are the "knobs" you either get from the interviewer/PM or must state as an assumption:

- **DAU and MAU** — *Daily* and *Monthly Active Users*. A common ratio is **DAU/MAU ≈ 0.1–0.5** (a "stickiness" metric; 0.5 means half your monthly users come back daily — very high, like a messaging app). State which one you're given.
- **Requests per user per day** — how many actions an average user takes. For a feed app maybe 20–50 reads; for a posting flow maybe 1–2 writes.
- **Read/Write ratio (R:W)** — for most consumer apps reads dominate writes by **10:1 to 1000:1** (Twitter/X is famously read-heavy; a logging system is write-heavy). This ratio drives caching and replica decisions (see §2.8).
- **Bytes per request / response / row** — the payload size. A tweet ~300 B of text; a JSON API response 1–10 KB; a photo 200 KB–2 MB; a video far more.
- **Retention period** — how long you keep data (forever, 90 days, 30 days). Drives storage.
- **Replication factor (RF)** — how many copies of each byte you keep for durability/availability. Typically **3** for distributed databases (e.g., HDFS, Cassandra default), **2** for cheaper setups.
- **Fan-out** — how many downstream operations one request triggers (see §2.9).
- **Peak factor** — ratio of peak load to average load (see §2.7).

### 2.6 Latency numbers every programmer should know (memorize)

These are **Jeff Dean's "Numbers Everyone Should Know,"** the canonical latency ladder. They tell you the *relative cost* of operations and let you sanity-check whether a design's per-request work is even physically possible in the latency budget. Values below are the widely-taught approximations (originally circa 2012, with modern adjustments noted); treat them as **orders of magnitude**, not exact specs of any one chip.

| Operation | Latency | In human terms (×10⁹ to "seconds") |
|---|---|---|
| L1 cache reference | ~0.5 ns | 0.5 s |
| Branch mispredict | ~5 ns | 5 s |
| L2 cache reference | ~7 ns | 7 s |
| Mutex lock/unlock | ~25 ns | 25 s |
| Main memory (RAM) reference | ~100 ns | ~1.5 min |
| Compress 1 KB (Snappy/Zippy) | ~1–3 µs | — |
| Read 1 MB sequentially from RAM | ~3–10 µs | — |
| SSD random read | ~16–150 µs (~100 µs typical) | — |
| Round trip within same datacenter | ~0.5 ms | ~6 days |
| Read 1 MB sequentially from SSD | ~0.1–1 ms | — |
| Read 1 MB sequentially from disk (HDD) | ~5–20 ms | — |
| Disk seek (HDD) | ~5–10 ms | ~ a year+ |
| Round trip CA ↔ Netherlands (cross-continent) | ~150 ms | thousands of years |

> **ns** = nanosecond = 10⁻⁹ s. **µs** = microsecond = 10⁻⁶ s. **ms** = millisecond = 10⁻³ s. The "human terms" column multiplies everything by ~10⁹ so a CPU cycle feels like a second — it dramatizes *why* a disk seek or cross-continent round trip is catastrophically expensive compared to RAM.

**The key takeaways from this ladder (the reason to memorize it):**

1. **RAM is ~100,000× faster than disk seek, and ~1,000× faster than an SSD random read.** This is why caching exists.
2. **A same-datacenter round trip (~0.5 ms) is ~300× a memory access** but still ~10–40× cheaper than an HDD seek. Network calls are not free, but cross-region calls (~50–150 ms) are *brutal* — avoid them on the hot path.
3. **Sequential access crushes random access.** Reading 1 MB sequentially from disk (~10 ms) beats doing many random seeks. This is why log-structured storage (Kafka, LSM-trees) and batching win.
4. **SSD is the modern default** (NVMe SSD random read ≈ 10–100 µs); spinning disk numbers matter mainly for cold/archival storage.

> **L1/L2 cache** = small, fast memory physically on the CPU. **Mutex** = *mutual exclusion lock*, a primitive that lets one thread at a time enter a critical section. **Branch mispredict** = the CPU guessed the wrong path of an `if` and had to throw away speculative work. **Snappy/Zippy** = fast (low-ratio) compression libraries from Google. **NVMe** = a modern fast interface for SSDs over PCIe.

### 2.7 Peak vs average and the peak factor

Real traffic is **never flat.** Users are awake during the day and in certain time zones; there are launch spikes, lunchtime spikes, end-of-month payroll spikes, Black Friday, and viral events.

- **Average QPS** = total daily requests ÷ 86,400. This *understates* what you must provision.
- **Peak QPS** = the highest sustained rate you must serve.
- **Peak factor (a.k.a. peak-to-average ratio)** = peak QPS ÷ average QPS.

Typical peak factors:

| Workload type | Peak factor (peak ÷ average) | Reasoning |
|---|---|---|
| Global, 24×7, evenly distributed | ~1.5–2× | time-zone smoothing |
| Single-region consumer app | ~3–5× | diurnal (day/night) cycle |
| Event-driven (sports, sales, news) | ~10–100× | spikes dwarf baseline |
| Batch / cron-aligned | huge, bursty | everything fires at :00 |

> **Diurnal** = following a daily (24-hour) cycle. Most human-facing traffic is diurnal: low at 4 a.m. local, high mid-evening.

**Always provision for peak, not average.** A common interview-safe default if you have no data: **peak ≈ 2× average**, or compute peak from a stated "X% of traffic arrives in Y hours" statement. Example: "80% of daily traffic in 8 hours" → that 8-hour window is 28,800 s; peak QPS ≈ (0.8 × daily) ÷ 28,800, which is ~2.5× the flat average.

### 2.8 Read/write ratio and why it dominates design

The **read/write ratio** decides the *shape* of your architecture more than almost any other number:

- **Read-heavy (e.g., 100:1):** reads dominate. You can absorb most of them with **caches** (Redis/Memcached) and **read replicas**, so the *primary* database only handles writes. Capacity for reads is mostly a caching/CDN problem.
- **Write-heavy (e.g., 1:5):** writes dominate (logging, metrics, IoT ingestion). Caching doesn't help writes. You need write-optimized stores (LSM-tree databases like Cassandra/RocksDB, append-only logs like Kafka), **sharding** to spread write load, and you must size the *write* path carefully.
- **Balanced (~1:1):** transactional systems (e-commerce checkout).

> **Read replica** = a copy of a database that serves read queries, kept in sync with the primary; it offloads reads but lags slightly behind (eventual consistency). **LSM-tree** (Log-Structured Merge tree) = a storage engine that buffers writes in memory and flushes them as sorted files, making writes cheap and sequential; used by Cassandra, RocksDB, LevelDB, ScyllaDB. **Sharding** = splitting data across multiple machines by a key so each machine holds a slice.

When given a read/write ratio, immediately split your QPS estimate into **read QPS** and **write QPS** and size each path separately, because they hit different components.

### 2.9 Fan-out

**Fan-out** = the number of secondary operations a single inbound request triggers. It is the silent multiplier that wrecks naïve estimates.

Two flavors:

1. **Write fan-out (push model):** one write causes many writes. Classic example: a celebrity with 100M followers posts once → the system writes that post into 100M follower timelines. One inbound write = 100M downstream writes.
2. **Read fan-out (pull model):** one read causes many reads. Example: rendering a home feed reads the latest posts from each of the 500 accounts you follow → one read = 500 sub-reads (or a scatter-gather across shards).

> **Scatter-gather** = a pattern where one request is "scattered" to many shards/services in parallel and the results "gathered" back. Its latency is governed by the *slowest* responder (tail latency), and its cost is the *sum* of all sub-requests.

Fan-out turns a modest top-line QPS into an enormous internal QPS. **Always ask "what does one request fan out to?"** and multiply. The push-vs-pull tradeoff (write fan-out vs read fan-out) is the core of newsfeed design (§5.2).

### 2.10 Headroom, utilization, and why you never run at 100%

You must never size a system to run at 100% utilization, for several reasons:

- **Queueing theory:** as utilization → 100%, queue length and latency go to **infinity**, not linearly. (See §7.1, Little's Law / M/M/1.) A server at 90% utilization already has dramatically worse tail latency than at 50%.
- **Variance/bursts:** real traffic is bursty; you need slack to absorb spikes between autoscale reactions.
- **Failure tolerance:** if you run N servers and one dies, the rest must absorb its load (the **N+1 / N+2** rule). If each was at 80%, losing one of five pushes the rest to 100% — over the edge.
- **Maintenance:** deploys, restarts, and rolling upgrades temporarily remove capacity.

> **Utilization** = the fraction of a resource's maximum capacity currently in use (e.g., CPU at 70%). **Headroom** = the unused fraction you deliberately keep (e.g., target 60% utilization ⇒ 40% headroom). **N+1 redundancy** = provision one extra unit beyond what the load requires so a single failure doesn't cause an outage; **N+2** tolerates two simultaneous failures.

Practical rule: **target 50–70% utilization** for the steady state, which means dividing your "raw needed" capacity by ~0.5–0.7 (i.e., multiplying by 1.4–2×). Combined with peak factor, your provisioned number is often **3–10× the naïve average**. This is normal and correct.

### 2.11 Significant figures and the "one digit" discipline

Round every input to **one significant figure** (sometimes two for the anchor). You're after order-of-magnitude correctness. "37.4 million DAU" becomes "40 million." "312 bytes per tweet" becomes "300 bytes." This keeps mental arithmetic feasible and signals to an interviewer that you understand BOTE is about ruling out bad designs, not bookkeeping. Carry **powers of ten** explicitly (write `4 × 10⁷`, not `40000000`) — it makes multiplication = add exponents, division = subtract exponents, and prevents zero-counting errors.

---

## 3. How it works internally

Capacity estimation is a *procedure*. This section gives the step-by-step workflow you run every single time, plus the "state machine" of an estimation session and the data/control flow of the reasoning.

### 3.1 The canonical 9-step workflow

Run these in order. Skipping steps is where estimates go wrong.

**Step 1 — Clarify scope and pick the anchor.**
Nail down: which feature(s) are in scope, the user base size (DAU/MAU), the timeframe (today vs 5 years), and the SLA targets (latency, availability). Choose your **anchor number** — the one input everything multiplies from. Usually DAU. State every assumption out loud / in writing.

> **SLA** = *Service Level Agreement*, a promised target like "99.9% availability" or "p99 latency < 200 ms." **p99** = the 99th-percentile latency: 99% of requests are faster than this. Tail latency (p99/p999) is what users actually feel during bad moments.

**Step 2 — Enumerate the operations and their ratios.**
List each user action and how often it happens per user per day. Establish the read/write ratio. Note fan-out for each operation.

**Step 3 — Compute average QPS.**
`avg QPS = (DAU × actions_per_user_per_day) ÷ 86,400`. Split into read QPS and write QPS using the R:W ratio. Apply fan-out to get *internal* QPS where relevant.

**Step 4 — Compute peak QPS.**
`peak QPS = avg QPS × peak_factor`. Use a stated peak rule or default to ~2–3×.

**Step 5 — Compute storage.**
`bytes/day = writes/day × bytes_per_write`. Then `total = bytes/day × retention_days × replication_factor`. Add indexes/metadata overhead (often ×1.2–2). Project growth over the timeframe.

**Step 6 — Compute bandwidth.**
Ingress: `write QPS × bytes_per_request`. Egress: `read QPS × bytes_per_response`. Multiply by 8 to express in bits/sec. Flag the dominant direction (usually egress, especially for media).

**Step 7 — Compute memory / cache.**
Decide the cacheable working set. A common rule: cache the **hot 20%** that serves **80%** of reads (Pareto). `cache_bytes = hot_items × bytes_per_item`. Compare to a node's RAM to get node count.

**Step 8 — Compute server / node counts.**
For each tier: `nodes = ceil( peak_demand ÷ (per_node_capacity × target_utilization) )`, then add N+1/N+2 redundancy and a multi-AZ factor.

> **AZ** = *Availability Zone*, an isolated datacenter within a cloud region; running across ≥2–3 AZs protects against a single datacenter failure.

**Step 9 — Apply headroom, summarize, and sanity-check.**
Multiply by headroom factors, round, and present a small table: QPS (avg/peak), storage (now/Nyr), bandwidth (in/out), server counts per tier, and the cost ballpark. Sanity-check against the latency ladder and against "does one big box do it?" (see §3.5).

### 3.2 Control flow as a flowchart (textual)

```
            ┌─────────────────────────┐
            │ 1. Clarify scope + anchor│
            └───────────┬─────────────┘
                        ▼
            ┌─────────────────────────┐
            │ 2. List ops + ratios     │
            └───────────┬─────────────┘
                        ▼
            ┌─────────────────────────┐
            │ 3. avg QPS (split R/W)   │◄──────────┐
            └───────────┬─────────────┘            │
                        ▼                          │ (revise ratios
            ┌─────────────────────────┐            │  if numbers look
            │ 4. peak QPS (×peakfac)   │            │  absurd)
            └───────────┬─────────────┘            │
            ┌───────────┴──────────────┐           │
            ▼            ▼              ▼           │
      ┌──────────┐ ┌──────────┐  ┌──────────┐      │
      │5 Storage │ │6 Bandwidth│  │7 Memory  │      │
      └────┬─────┘ └────┬─────┘  └────┬─────┘       │
           └─────────┬──┴─────────────┘             │
                     ▼                              │
            ┌─────────────────────────┐             │
            │ 8. Server counts/tier    │             │
            └───────────┬─────────────┘             │
                        ▼                           │
            ┌─────────────────────────┐             │
            │ 9. Headroom + sanity     │─────────────┘
            └───────────┬─────────────┘
                        ▼
                 ┌────────────┐
                 │ Summary tbl│
                 └────────────┘
```

The loop-back from step 9 is real: if a sanity check (latency ladder, single-box test, cost) returns something absurd, you revise an assumption and recompute. Estimation is iterative.

### 3.3 Data flow: how a number propagates

Trace how the single anchor `DAU = 100M` flows through to a server count, so you can see the dependency graph:

```
DAU (100M)
  ├─×reads/user (50) ─────────► read req/day (5×10⁹)
  │                                 └─÷86,400 ─► avg read QPS (~58k)
  │                                       └─×peak(2) ─► peak read QPS (~116k)
  │                                             └─÷QPS/box(1000)÷util(0.6) ─► app nodes (~190) ─► +N+2 ─► ~200
  ├─×writes/user (2) ─────────► write req/day (2×10⁸)
  │                                 ├─÷86,400 ─► avg write QPS (~2.3k) ─► peak (~5k) ─► DB write nodes
  │                                 └─×bytes/write (1KB)×RF(3)×retention ─► storage
  └─(reads)×bytes/resp (5KB) ─► egress bytes/s ─► ×8 ─► egress Gbps
```

Notice the **branching**: from DAU you split into reads and writes, and each branch fans into multiple resources. A change to the anchor ripples through every leaf. This is why you keep the anchor explicit and parameterized (and why a spreadsheet or the code in §5 beats redoing arithmetic by hand).

### 3.4 The "lifecycle" of an estimate (state transitions)

An estimate is not a one-shot artifact; it moves through states:

| State | What it is | Trigger to next state |
|---|---|---|
| **Assumed** | Inputs are guesses you stated. | Product/analytics data arrives. |
| **Modeled** | A reproducible formula/spreadsheet exists. | First load test runs. |
| **Validated** | Load test confirms (or corrects) per-node capacity. | Production launch. |
| **Observed** | Real telemetry replaces assumptions. | Continuous. |
| **Re-forecast** | Updated with growth + seasonality. | Periodically / before events. |

The mistake is treating an *Assumed* estimate as *Validated*. Every assumed number should later be replaced by a measured one. Capacity *planning* (the production discipline) is the steady-state of this lifecycle: continuously re-forecasting from observed data.

### 3.5 Internal sanity checks (run these mentally, every time)

1. **The single-box test.** Can one reasonably big modern server (say 32–64 vCPU, 128–256 GB RAM, a few TB NVMe) handle the whole thing? Modern hardware is *huge*. Many "needs a distributed system!" problems fit on one box. If your estimate says "we need 3 servers," question whether you need distribution at all. If it says "we need 5,000 servers," that's a different (and real) regime.
2. **The latency-budget test.** Add up the per-request work using the latency ladder. If your p99 budget is 200 ms and your design does 10 sequential cross-region calls at 100 ms each, it's physically impossible — redesign.
3. **The order-of-magnitude test.** Is the answer 10², 10⁶, or 10⁹? If you expected "thousands of QPS" and got "billions," you dropped or added a factor of 1000 (likely a seconds/day or KB/MB slip).
4. **The cost test.** Convert server count to dollars/month. If a side feature "costs" \$10M/month, an assumption is wrong or the design is.
5. **The bits/bytes test.** Re-examine any bandwidth number: did you confuse Mbps with MB/s? Off-by-8 is the most common bandwidth bug.

---

## 4. The complete toolkit

This section enumerates the constants, formulas, conversions, hardware reference numbers, and software tools you draw on.

### 4.1 Memorized constants (the "instruction set")

| Constant | Value | Use |
|---|---|---|
| Seconds per day | 86,400 ≈ **10⁵** | QPS = req/day ÷ 10⁵ |
| Seconds per year | 3.15×10⁷ (≈ π×10⁷) | flow → yearly storage |
| Byte | 8 bits | bandwidth conversions |
| 1 Gbps | ≈ 125 MB/s | network sizing |
| Default replication factor | 3 | storage durability |
| Default peak factor (no data) | 2–3× | peak QPS |
| Default target utilization | 50–70% | headroom |
| Pareto cache rule | 20% of items → 80% of reads | cache sizing |
| DAU/MAU stickiness | 0.1 (typical) – 0.5 (very sticky) | derive DAU from MAU |

### 4.2 Powers of ten and "users → scale" table

Knowing roughly how many zeros a population has saves time:

| Scale | Count | Example |
|---|---|---|
| Thousand | 10³ | a small startup's users |
| Million | 10⁶ | a successful app |
| 10 million | 10⁷ | a popular national app |
| 100 million | 10⁸ | a top-tier app's DAU |
| Billion | 10⁹ | Facebook/WhatsApp/YouTube scale; world population is ~8×10⁹ |

**Multiplying powers of ten:** add exponents. `10⁸ DAU × 10¹ reads ÷ 10⁵ s/day = 10⁴ QPS` average. Memorizing this lets you do most estimates *in your head*.

### 4.3 Data-size reference table ("bytes per X")

Use these as defaults when not told otherwise; **flag them as assumptions**.

| Item | Typical size | Notes |
|---|---|---|
| ASCII/UTF-8 char | 1 byte | UTF-8 is 1–4 B/char; English ≈ 1 |
| UUID / GUID | 16 B raw, 36 B as string | primary keys |
| Unix timestamp | 4 B (sec) / 8 B (ms epoch) | |
| int / long | 4 B / 8 B | |
| A tweet/short post (text) | ~140–300 B | |
| A typical JSON API response | 1–10 KB | |
| A DB row (mixed columns) | ~0.1–1 KB | + index overhead |
| A web page (HTML, no media) | ~50–100 KB | |
| A thumbnail image | ~10–50 KB | |
| A photo (compressed JPEG) | ~200 KB – 2 MB | |
| 1 minute of MP3 audio (128 kbps) | ~1 MB | |
| 1 minute of 1080p video (~5 Mbps) | ~40 MB | bitrate-dependent |
| 1 hour of 1080p video | ~2–3 GB | |

> **UUID** = Universally Unique Identifier, a 128-bit random ID. **bitrate** = bits per second of an audio/video stream; size = bitrate × duration ÷ 8.

### 4.4 Hardware reference numbers (per-node capacity ballparks)

These are **rough, version- and workload-specific** capacities of a single commodity/cloud node. Treat as order-of-magnitude defaults and *validate with load tests*. They drift over time and depend heavily on payload, language, and tuning.

| Resource (single node) | Ballpark capacity | Caveats |
|---|---|---|
| Stateless app server (JVM, simple JSON) | ~1,000–10,000 QPS | Depends entirely on per-request CPU; CPU-bound vs I/O-bound. A trivial endpoint can do 10k+; a heavy one <1k. |
| NGINX/static or reverse proxy | tens of thousands of req/s | mostly I/O, very cheap per request |
| Redis / Memcached (single instance) | ~100k–1M+ ops/s | small values, single-threaded core (Redis); network-bound at the top |
| Relational DB (Postgres/MySQL) write throughput | ~hundreds–low thousands of writes/s per primary | durable commits are fsync-bound; tune with batching/group commit |
| Relational DB read throughput | ~thousands–tens of thousands/s | with cache hits + replicas much higher |
| Cassandra / LSM store per node | ~10k–50k+ writes/s | write-optimized |
| Kafka broker | ~hundreds of MB/s to GB/s per broker | sequential disk; partition-parallel |
| RAM per server (cloud) | 16 GB – 1 TB+ | choose instance family by need |
| NVMe SSD throughput | ~1–7 GB/s, ~100k–1M IOPS | per device |
| HDD throughput | ~100–200 MB/s sequential, ~100 IOPS random | archival/cold |
| NIC bandwidth | 1 / 10 / 25 / 100 Gbps | per server |

> **fsync** = a syscall that forces buffered writes to durable storage before returning; durable databases must fsync on commit, which caps write throughput to disk-commit latency unless batched. **IOPS** = I/O Operations Per Second. **NIC** = Network Interface Card. **syscall** = system call, a request from a program to the OS kernel (e.g., read, write, fsync). **Stateless** = the server keeps no per-client session in memory, so any node can serve any request — the property that makes horizontal scaling trivial.

> **JVM-specific flag:** A Java app server's per-request capacity is sensitive to **GC** (garbage collection) pauses, thread-pool sizing, and connection-pool limits. A poorly tuned GC can make p99 latency dominated by stop-the-world pauses, slashing effective QPS. **GC** = automatic reclamation of unused heap memory; "stop-the-world" GC briefly pauses all application threads. See §6 and §7.

### 4.5 The core formulas (reference card)

```
avg_QPS            = DAU × actions_per_user_per_day / 86_400
peak_QPS           = avg_QPS × peak_factor
read_QPS           = total_QPS × R / (R + W)         // R:W ratio
write_QPS          = total_QPS × W / (R + W)
internal_QPS       = external_QPS × fan_out
storage_per_day    = writes_per_day × bytes_per_write
total_storage      = storage_per_day × retention_days × replication_factor × overhead
storage_5yr        = storage_per_day × 365 × 5 × replication_factor   // if retain forever
egress_Bps         = read_QPS × bytes_per_response
egress_bps         = egress_Bps × 8
cache_bytes        = hot_fraction × total_items × bytes_per_item
nodes_for_tier     = ceil( peak_demand / (per_node_capacity × target_util) ) + redundancy
monthly_cost       = nodes × hourly_price × 730                       // 730 ≈ hours/month
```

### 4.6 Software tools that produce/validate the inputs

Estimation needs *real* numbers eventually. The toolkit for getting them:

| Tool | Category | What it gives you | Key flags / notes |
|---|---|---|---|
| **JMeter** | Load test | QPS a service sustains, latency percentiles | thread groups, ramp-up; GUI + CLI (`-n -t plan.jmx`) |
| **Gatling** | Load test | high-throughput load, nice reports | Scala/Java DSL, low client overhead |
| **wrk / wrk2** | Load test | HTTP throughput & latency; wrk2 fixes coordinated omission | `-t threads -c conns -d dur -R rate` |
| **k6** | Load test | scriptable JS load tests, cloud option | `vus`, `duration`, thresholds |
| **JMH** | Microbench (JVM) | per-operation latency/throughput of Java code | `@Benchmark`, warmup iterations |
| **async-profiler / JFR** | JVM profiling | where CPU/alloc goes per request | JFR = Java Flight Recorder, built-in |
| **prometheus + grafana** | Metrics/observability | live QPS, latency, utilization (RED/USE) | `rate()`, histograms for p99 |
| **`pidstat` / `vmstat` / `iostat` / `sar`** | OS metrics | CPU, memory, disk I/O per process | Linux `sysstat` |
| **`ss` / `netstat` / `iftop` / `nload`** | Network | connections, bandwidth | |
| **cloud cost calculators** | Cost | \$/instance/month, egress \$/GB | AWS/GCP/Azure pricing pages |
| **VisualVM / `jstat` / `jmap`** | JVM memory | heap usage, GC behavior | `jstat -gcutil <pid> 1s` |

> **Coordinated omission** = a measurement bug where a load generator that waits for slow responses *fails to send* the requests it should have, hiding the true tail latency; wrk2/JMeter-with-fixed-rate and HdrHistogram address it. **RED method** = monitor **R**ate, **E**rrors, **D**uration per service. **USE method** = for each resource monitor **U**tilization, **S**aturation, **E**rrors (Brendan Gregg). **HdrHistogram** = a library for recording latency distributions at constant memory with high dynamic range.

---

## 5. Code examples by use case

These examples are runnable/adaptable Java (plus one shell and one SQL) and span *different* scenarios, not variations of one. The point is to make the estimation reproducible and parameterized so you can change one assumption and see the whole chain update.

### 5.1 A reusable capacity estimator (Java)

A small, dependency-free Java model you can drop into a `jshell` or a `main`. It encodes the formulas from §4.5 so you stop redoing arithmetic by hand and stop making bits/bytes errors.

```java
// CapacityEstimator.java
// A back-of-the-envelope capacity model. Pure functions, explicit units.
// Compile: javac CapacityEstimator.java ; Run: java CapacityEstimator
public final class CapacityEstimator {

    // ---- unit constants (keep all time in seconds, all data in bytes) ----
    static final long SECONDS_PER_DAY   = 86_400L;
    static final long SECONDS_PER_YEAR  = 31_536_000L; // 365 days
    static final long HOURS_PER_MONTH   = 730L;        // ~365*24/12
    static final double BITS_PER_BYTE   = 8.0;
    static final long KB = 1_000, MB = 1_000_000, GB = 1_000_000_000L, TB = 1_000_000_000_000L; // decimal

    /** Average QPS from a DAU anchor and per-user action count. */
    static double avgQps(long dau, double actionsPerUserPerDay) {
        return (double) dau * actionsPerUserPerDay / SECONDS_PER_DAY;
    }

    /** Peak QPS = average * peakFactor (provision for THIS, never the average). */
    static double peakQps(double avgQps, double peakFactor) {
        return avgQps * peakFactor;
    }

    /** Split a total QPS into read and write using an R:W ratio (e.g., 100:1). */
    static double readQps(double totalQps, double r, double w)  { return totalQps * r / (r + w); }
    static double writeQps(double totalQps, double r, double w) { return totalQps * w / (r + w); }

    /** Total stored bytes given a daily write flow. retentionDays = Long.MAX-ish for "forever". */
    static double totalStorageBytes(double writesPerDay, double bytesPerWrite,
                                    double retentionDays, double replicationFactor,
                                    double overheadFactor /* indexes/metadata, e.g. 1.5 */) {
        return writesPerDay * bytesPerWrite * retentionDays * replicationFactor * overheadFactor;
    }

    /** Egress in bits/sec from read QPS and response size — multiply by 8 ONCE, here. */
    static double egressBitsPerSec(double readQps, double bytesPerResponse) {
        return readQps * bytesPerResponse * BITS_PER_BYTE;
    }

    /** Cache RAM for the hot working set (Pareto: cache the hot fraction). */
    static double cacheBytes(long totalItems, double hotFraction, double bytesPerItem) {
        return totalItems * hotFraction * bytesPerItem;
    }

    /** Node count for a tier, with utilization headroom and explicit redundancy (N+k). */
    static long nodes(double peakDemand, double perNodeCapacity,
                      double targetUtilization /* 0.6 */, int redundancyK /* +2 */) {
        long base = (long) Math.ceil(peakDemand / (perNodeCapacity * targetUtilization));
        return base + redundancyK;
    }

    /** Rough monthly cost. */
    static double monthlyCost(long nodes, double hourlyPricePerNode) {
        return nodes * hourlyPricePerNode * HOURS_PER_MONTH;
    }

    static String gb(double bytes) { return String.format("%.1f GB", bytes / GB); }
    static String tb(double bytes) { return String.format("%.2f TB", bytes / TB); }
    static String gbps(double bps) { return String.format("%.2f Gbps", bps / 1e9); }

    public static void main(String[] args) {
        // ----- assumptions (rounded to 1 sig fig; flag each as an assumption) -----
        long   dau              = 100_000_000L;  // 100M DAU  (ANCHOR)
        double readsPerUser     = 50;            // feed reads/user/day
        double writesPerUser    = 2;             // posts/user/day
        double peakFactor       = 2.0;           // peak ~ 2x average
        double bytesPerPost     = 1_000;         // 1 KB stored per post
        double bytesPerResp     = 5_000;         // 5 KB per read response
        double replication      = 3;             // RF=3
        double overhead         = 1.5;           // indexes/metadata
        double retentionDays    = 365 * 5;       // keep 5 years
        double appQpsPerNode    = 1_000;         // measured later via load test!
        double targetUtil       = 0.6;           // 60% utilization, 40% headroom

        // ----- chain -----
        double avgRead  = avgQps(dau, readsPerUser);
        double avgWrite = avgQps(dau, writesPerUser);
        double pkRead   = peakQps(avgRead, peakFactor);
        double pkWrite  = peakQps(avgWrite, peakFactor);

        double writesPerDay = (double) dau * writesPerUser;
        double storage      = totalStorageBytes(writesPerDay, bytesPerPost, retentionDays, replication, overhead);
        double storage1day  = totalStorageBytes(writesPerDay, bytesPerPost, 1, replication, overhead);

        double egress = egressBitsPerSec(pkRead, bytesPerResp);

        long appNodes = nodes(pkRead, appQpsPerNode, targetUtil, /*N+*/2);

        // ----- report -----
        System.out.printf("Avg read QPS : %,.0f%n", avgRead);
        System.out.printf("Peak read QPS: %,.0f%n", pkRead);
        System.out.printf("Peak write QPS: %,.0f%n", pkWrite);
        System.out.printf("New storage/day: %s%n", gb(storage1day));
        System.out.printf("Storage @5yr (RF=3, +overhead): %s%n", tb(storage));
        System.out.printf("Peak egress: %s%n", gbps(egress));
        System.out.printf("App nodes (60%% util, +2): %d%n", appNodes);
        System.out.printf("App tier monthly cost @ $0.10/hr: $%,.0f%n",
                          monthlyCost(appNodes, 0.10));
    }
}
```

What this prints (with these assumptions) and *why each line matters*:

- **Avg read QPS ≈ 57,870; peak ≈ 115,741.** That's `100M × 50 ÷ 86,400 × 2`. Sanity: ~10⁵ QPS is a real-but-tractable read tier.
- **Peak write QPS ≈ 4,630.** Two orders of magnitude below reads — confirms a **read-heavy** system; lean on caching/CDN.
- **New storage/day ≈ 0.3 GB** stored as posts (×RF×overhead). Sanity: tiny per day because posts are 1 KB; media would change this completely.
- **Storage @5yr ≈ 0.55 TB.** Fits comfortably; you don't *need* sharding for size — you might still shard for write throughput or to spread reads.
- **Peak egress ≈ 4.63 Gbps.** Multiplied by 8 exactly once (the function does it), avoiding the off-by-8 bug.
- **App nodes ≈ 195.** `ceil(115,741 ÷ (1000 × 0.6)) + 2`. The `appQpsPerNode = 1000` is the *most uncertain* input — load-test it before trusting this.

The lesson: **parameterize the assumptions, never the formulas.** Change `appQpsPerNode` to 5000 (a leaner endpoint) and node count drops ~5×.

### 5.2 Newsfeed capacity — push (fan-out-on-write) vs pull (fan-out-on-read)

The defining capacity question of a social feed is the **fan-out tradeoff.** Here we estimate *internal* write amplification for the push model and *internal* read amplification for the pull model, then show the hybrid.

```java
// FeedFanout.java — estimate the internal load each model creates.
public class FeedFanout {
    static final long SEC_PER_DAY = 86_400L;

    public static void main(String[] args) {
        long   dau              = 100_000_000L; // 100M DAU
        double postsPerUserDay  = 2;            // each user posts twice/day
        double feedReadsPerDay  = 20;           // each user refreshes feed 20x/day
        double avgFollowers     = 200;          // average fan-out
        long   celebFollowers   = 50_000_000L;  // a celebrity's followers
        double avgFollowing     = 200;          // accounts an average user follows

        // ---------- PUSH model (fan-out on WRITE) ----------
        // One post => write into every follower's timeline.
        double postsPerDay      = dau * postsPerUserDay;        // 2e8 posts/day
        double timelineWrites   = postsPerDay * avgFollowers;   // each post * followers
        double pushWriteQps     = timelineWrites / SEC_PER_DAY;
        // Reads are CHEAP: a feed read just reads a precomputed timeline.
        double feedReadsTotal   = dau * feedReadsPerDay;
        double pushReadQps      = feedReadsTotal / SEC_PER_DAY;

        // The celebrity problem: ONE celebrity post = 50M writes.
        double celebSpikeWrites = celebFollowers; // per single post, instantaneously

        // ---------- PULL model (fan-out on READ) ----------
        // Writes are CHEAP: just append to the author's own posts.
        double pullWriteQps     = postsPerDay / SEC_PER_DAY;
        // Reads are EXPENSIVE: each feed read gathers posts from everyone you follow.
        double pullSubreads     = feedReadsTotal * avgFollowing; // scatter-gather
        double pullReadQps      = pullSubreads / SEC_PER_DAY;

        System.out.println("=== PUSH (fan-out on write) ===");
        System.out.printf("Timeline write QPS (avg): %,.0f%n", pushWriteQps);
        System.out.printf("Feed read QPS (cheap)    : %,.0f%n", pushReadQps);
        System.out.printf("ONE celeb post bursts     : %,.0f writes%n", celebSpikeWrites);

        System.out.println("\n=== PULL (fan-out on read) ===");
        System.out.printf("Post write QPS (cheap)   : %,.0f%n", pullWriteQps);
        System.out.printf("Sub-read QPS (expensive) : %,.0f%n", pullReadQps);
    }
}
```

Interpreting the output (orders of magnitude):

- **Push:** timeline write QPS ≈ `2e8 × 200 ÷ 86,400` ≈ **463k writes/s** average — large but spread out; feed *reads* are cheap (~23k/s) because timelines are precomputed. **But** one celebrity post detonates **50M writes** in a burst — the **hot-key / celebrity problem.**
- **Pull:** post writes are trivial (~2.3k/s) but feed reads explode to `2e9 × 200 ÷ 86,400` ≈ **4.6M sub-reads/s**, each a scatter-gather across the accounts you follow — brutal read amplification and tail-latency risk.
- **Conclusion (and the real-world answer):** use a **hybrid** — push for normal users (cheap, precomputed feeds), pull for celebrities (don't fan out 50M writes; merge their posts in at read time). This is essentially what Twitter/X and Instagram do. The estimate *forces* this design conclusion, which is exactly why interviewers ask for the numbers.

### 5.3 URL shortener — storage growth and ID space (Java)

A URL shortener (TinyURL/Bitly) is the canonical storage + read-heavy estimate. The two interesting questions are: *how big does the DB get over N years?* and *how many characters must the short code be?*

```java
// UrlShortener.java
public class UrlShortener {
    static final long SEC_PER_DAY = 86_400L, GB = 1_000_000_000L, TB = 1_000_000_000_000L;

    public static void main(String[] args) {
        // assumptions
        double newUrlsPerMonth = 500_000_000.0;   // 500M new short URLs/month
        double readWriteRatio  = 100;             // 100 reads (redirects) per write
        double bytesPerRecord  = 500;             // shortCode + longUrl + meta ≈ 500 B
        int    years           = 5;

        // ---- write QPS ----
        double writesPerSec = newUrlsPerMonth / (30.0 * SEC_PER_DAY);
        double readsPerSec  = writesPerSec * readWriteRatio;

        // ---- storage over N years ----
        double recordsTotal = newUrlsPerMonth * 12 * years;       // 5 years of records
        double storageBytes = recordsTotal * bytesPerRecord;

        // ---- ID space: how many chars in base62 to cover recordsTotal? ----
        // base62 (a-zA-Z0-9) gives 62 values/char. n chars => 62^n keys.
        int n = (int) Math.ceil(Math.log(recordsTotal) / Math.log(62));

        System.out.printf("Write QPS (avg)       : %,.0f%n", writesPerSec);
        System.out.printf("Read QPS  (avg)       : %,.0f%n", readsPerSec);
        System.out.printf("Total records (5yr)   : %,.0f%n", recordsTotal);
        System.out.printf("Storage (5yr, no RF)  : %.2f TB%n", storageBytes / TB);
        System.out.printf("Base62 short-code len : %d chars (62^%d = %.2e keys)%n",
                          n, n, Math.pow(62, n));
    }
}
```

Output and reasoning:

- **Write QPS ≈ 193/s, Read QPS ≈ 19,300/s.** Heavily read-skewed → cache the hot URLs (a small fraction of URLs get the vast majority of redirects). A cache in front of the key-value store absorbs nearly all reads.
- **Total records (5 yr) = 500M × 12 × 5 = 30 billion.** This is the *count* that drives the ID space.
- **Storage ≈ 15 TB** (before replication). With RF=3, ~45 TB — too big for one node ⇒ **shard** the key-value store, or pick a distributed KV store. The estimate is what tells you sharding is mandatory.
- **Base62 length:** `62^n ≥ 3e10` ⇒ `n = 6` (62⁶ ≈ 5.7×10¹⁰, enough; 62⁵ ≈ 9.2×10⁸ is *not*). So a **7-char** code (the common choice) gives huge headroom. This is a pure capacity argument for an interview.

> **base62** = encoding numbers using 62 symbols (0–9, a–z, A–Z); used for short, URL-safe IDs. **Key-value (KV) store** = a database that maps a key directly to a value with O(1)-ish lookup (e.g., DynamoDB, Redis, RocksDB); ideal for `shortCode → longUrl`.

### 5.4 Video streaming platform — egress bandwidth dominates (Java)

For media, **bandwidth and CDN egress cost** dwarf compute and storage. This estimate shows why a CDN is non-negotiable.

```java
// VideoStreaming.java
public class VideoStreaming {
    static final long SEC_PER_DAY = 86_400L;

    public static void main(String[] args) {
        long   dau                 = 50_000_000L;  // 50M DAU
        double watchMinutesPerUser = 60;           // 1 hour/day average
        double avgBitrateMbps      = 5.0;          // 1080p ≈ 5 Mbps
        double peakFactor          = 3.0;
        double cdnEgressPerGB      = 0.05;         // $/GB (illustrative)

        // total minutes streamed/day
        double minutesPerDay = dau * watchMinutesPerUser;          // 3e9 min/day
        double secondsPerDay = minutesPerDay * 60;
        // bits streamed/day = seconds * bitrate
        double bitsPerDay    = secondsPerDay * avgBitrateMbps * 1e6;
        double bytesPerDay   = bitsPerDay / 8.0;

        // average egress bandwidth (bits/sec) = total bits/day / seconds/day
        double avgEgressBps  = bitsPerDay / SEC_PER_DAY;
        double peakEgressBps = avgEgressBps * peakFactor;

        // monthly egress cost (bytes/day * 30 / GB * $/GB)
        double monthlyGB     = bytesPerDay * 30 / 1e9;
        double monthlyCost   = monthlyGB * cdnEgressPerGB;

        System.out.printf("Avg egress  : %.1f Tbps%n", avgEgressBps / 1e12);
        System.out.printf("Peak egress : %.1f Tbps%n", peakEgressBps / 1e12);
        System.out.printf("Egress/month: %,.0f TB%n", monthlyGB / 1000);
        System.out.printf("CDN egress cost/month: $%,.0f%n", monthlyCost);
    }
}
```

Reasoning:

- **Average egress ≈ 1.04 Tbps; peak ≈ 3.1 Tbps.** Terabits per second. No origin fleet serves this directly — it *must* be served from a **CDN** with edge caches, or your origin NICs and egress bill explode.
- **Egress/month ≈ 11,250 TB (~11 PB).** At even \$0.05/GB that's **~\$560k/month** in egress alone — and this is why streaming companies negotiate custom CDN deals or build their own (e.g., Netflix Open Connect). The estimate makes the business case for the CDN architecture.
- Storage of the *catalog* is comparatively trivial; the dominant resource is clearly **bandwidth**. Different workloads have different dominant resources — always identify which one dominates.

> **CDN** = *Content Delivery Network*, a fleet of geographically distributed edge caches that serve content close to users, offloading the origin and reducing latency and egress. **Origin** = your authoritative servers behind the CDN. **Tbps** = terabits/sec = 10¹² bits/sec.

### 5.5 Rate-limiter / quota sizing (Java) — memory for counters

Sizing a distributed rate limiter is a memory-and-ops estimate: how much RAM do per-user counters need, and can one Redis hold them?

```java
// RateLimiterSizing.java
public class RateLimiterSizing {
    public static void main(String[] args) {
        long   activeUsers     = 100_000_000L; // 100M users with live counters
        long   bytesPerCounter = 100;          // key + value + Redis overhead ≈ 100 B
        double opsPerRequest   = 2;            // INCR + EXPIRE/check per request
        double peakReqPerSec   = 500_000;      // 500k req/s at peak

        double counterRamBytes = (double) activeUsers * bytesPerCounter;
        double redisOpsPerSec  = peakReqPerSec * opsPerRequest;

        System.out.printf("Counter RAM: %.1f GB%n", counterRamBytes / 1e9);
        System.out.printf("Redis ops/s: %,.0f%n", redisOpsPerSec);
        System.out.println(counterRamBytes < 64e9
            ? "Fits in one large Redis node (with headroom)."
            : "Exceeds one node -> shard by user-id hash.");
        System.out.println(redisOpsPerSec < 1_000_000
            ? "One Redis can handle the op rate (validate!)."
            : "Op rate exceeds one node -> Redis Cluster / sharding.");
    }
}
```

Reasoning: **counter RAM ≈ 10 GB** (`100M × 100 B`) — fits in one large Redis with room to spare; **ops/s ≈ 1M** — right at the edge of a single Redis instance's quoted ceiling, so you'd shard with Redis Cluster for safety and headroom. This is a clean example where the dominant resource is **memory + op-rate**, not storage or egress.

### 5.6 Quick mental math at the shell (one-liners)

Sometimes you just want the number now. Keep these patterns handy:

```bash
# Average QPS from 100M DAU * 50 reads/day
python3 -c "print(100e6*50/86400)"          # -> ~57870 QPS

# Peak egress Gbps: 116k read QPS * 5 KB response, *8 bits
python3 -c "print(116000*5000*8/1e9)"        # -> ~4.64 Gbps

# 5-year storage TB: 500M/mo * 12 * 5 * 500 bytes
python3 -c "print(500e6*12*5*500/1e12)"      # -> ~15 TB

# Node count: ceil(116000 / (1000*0.6)) + 2
python3 -c "import math;print(math.ceil(116000/(1000*0.6))+2)"  # -> 195
```

Using a tiny script keeps your units honest and avoids the most common manual errors (dropped zeros, off-by-8). In an interview you do this on the whiteboard, but the discipline — explicit units, one operation per line — is the same.

### 5.7 SQL: estimate table growth from row size and row count

When the unknown is "how big will this table get," you can measure real row size in the DB rather than assume:

```sql
-- PostgreSQL: average row size and projected growth for the 'events' table.
SELECT
    pg_size_pretty(pg_total_relation_size('events'))         AS current_total_size, -- table+indexes+toast
    reltuples::bigint                                        AS approx_row_count,
    pg_total_relation_size('events') / NULLIF(reltuples,0)   AS bytes_per_row_total -- incl. index overhead
FROM pg_class
WHERE relname = 'events';

-- Then project: if you insert 2e8 rows/day, 5-year size =
--   bytes_per_row_total * 2e8 * 365 * 5   (before replication)
```

The key insight: **`pg_total_relation_size` includes index and TOAST overhead**, so `bytes_per_row_total` is the *real* per-row cost — far more honest than counting only the column widths. Multiply that by your projected insert rate and retention to get true growth, then apply replication factor. (TOAST = PostgreSQL's mechanism for storing oversized column values out-of-line.)

---

## 6. Implementation concerns & best practices

Estimation interacts with every cross-cutting concern. Here is how to make estimates *correct* and *useful*, and the anti-patterns that make them dangerous.

### 6.1 Performance

- **Estimate the dominant resource first.** Every workload has one resource that dominates (compute for an API gateway, egress for video, write-throughput for logging, RAM for an in-memory cache). Find it; the rest is rounding error. Don't spend 10 minutes on storage when the system is obviously bandwidth-bound.
- **Use measured per-node capacity, not folklore.** "10,000 QPS per box" is meaningless without the endpoint. A `/health` check does 100k+/s; a request that joins three services and serializes 50 KB might do 200/s. **Load-test the real endpoint** (JMeter/Gatling/wrk2) and feed *that* into your model. Until you do, treat per-node capacity as the *most uncertain* input and carry it as a variable.
- **Account for tail latency, not just throughput.** Throughput sizing (QPS/node) and latency sizing (p99 budget) are different constraints; the binding one is whichever you hit first. A box might do 5,000 QPS but blow the p99 SLA at 3,000. Provision to the *tighter* limit.

### 6.2 Correctness & concurrency

- **Keep units symbolic until the end.** Track `[req/s]`, `[bytes]`, `[bits/s]` literally; dimensional analysis catches errors (if your "QPS" comes out in `[bytes]`, you multiplied wrong).
- **Don't double-count or under-count fan-out.** The single biggest *correctness* error in feed/notification estimates is forgetting the fan-out multiplier (or applying it to the wrong direction). Decide push vs pull explicitly and apply fan-out to exactly the right side.
- **Concurrency model affects per-node capacity.** A thread-per-request server (one OS thread blocked per in-flight request) caps concurrency at thread-pool size; an async/reactive server (Netty, Project Loom virtual threads) holds far more concurrent connections per box. Your QPS/node assumption must match the actual concurrency model. (**Project Loom** = JVM virtual threads, lightweight threads that make blocking code scale like async.)

### 6.3 Memory (JVM-specific)

- **Cache sizing must include overhead, not just payload.** An entry's true RAM = key + value + data-structure overhead + fragmentation. In the JVM, object headers (12–16 B), references (4–8 B), and `HashMap`/`ConcurrentHashMap` node overhead can **double or triple** the naive payload size. Off-heap caches (Caffeine with weighers, or off-heap stores) and compact encodings reduce this. Always multiply naive cache size by an overhead factor (1.5–3×).
- **Leave heap headroom for GC.** A JVM running near max heap spends more time in GC and risks long stop-the-world pauses or OOM. Size heap so steady-state live set is well under `-Xmx`; reserve headroom (e.g., live set < 50–70% of heap). Choose a GC (G1, ZGC, Shenandoah) suited to your latency goals — **ZGC/Shenandoah** target sub-millisecond pauses for large heaps at some throughput cost.
- **Per-connection memory adds up.** Each TCP connection, thread stack (~512 KB–1 MB default per OS thread), and buffer consumes RAM. At high connection counts this can dominate; it's a real line item in memory estimates for gateways and proxies.

### 6.4 Security & multi-tenancy

- **Estimate for abuse and worst-case tenants, not just average users.** A multi-tenant system sized on *average* tenant load gets killed by one whale tenant or an attacker. Capacity plans should include rate limits/quotas (sized as in §5.5) and a per-tenant cap so one tenant can't consume the fleet.
- **DDoS headroom is a security capacity decision.** Edge/CDN/WAF capacity and absorb-or-shed policies are part of the estimate for any public endpoint. (**WAF** = Web Application Firewall; **DDoS** = Distributed Denial of Service, overwhelming a service with traffic from many sources.)

### 6.5 Cost

- **Translate every estimate into dollars.** Server count × instance price × 730 hours; storage GB × \$/GB-month; egress GB × \$/GB. Cost is the universal sanity check and the thing leadership actually decides on. **Egress is often the surprise** — cross-region and internet egress are billed per GB and can exceed compute.
- **Reserved/committed vs on-demand vs spot.** Steady baseline → reserved/committed (cheaper); spiky burst → on-demand or autoscaling; fault-tolerant batch → spot/preemptible. Capacity plans should split the bill across these.
- **Right-size, then commit.** Over-provisioning "to be safe" is a cost anti-pattern; the right answer is correct headroom (50–70% util, N+1/N+2), validated by load tests, with autoscaling for the spiky part.

### 6.6 Observability

- **Instrument what you estimated.** Every assumed input (QPS/node, bytes/response, R:W ratio, peak factor) should have a live metric so you can replace the assumption with reality (lifecycle state *Observed*, §3.4). Use the **RED** method per service (Rate, Errors, Duration) and the **USE** method per resource (Utilization, Saturation, Errors).
- **Record latency as histograms, not averages.** Averages hide tails; you need p50/p95/p99/p999 to size for the SLA. Use HdrHistogram / Prometheus histograms and beware coordinated omission in load tests.
- **Alert on saturation, not just utilization.** Saturation (queue depth, run-queue length, GC time, connection-pool waiters) predicts the cliff before utilization hits 100%.

### 6.7 Testability & validation

- **Validate per-node numbers with a load test before you trust the model.** The whole model hinges on per-node capacity; measure it. Use wrk2/Gatling/k6 at a *fixed request rate* (not closed-loop) to get honest tail latency.
- **Test the failure case.** Kill a node and confirm the rest absorb the load at acceptable latency (validates your N+1/N+2 and utilization headroom). This is a capacity test, not just a chaos test.
- **Re-validate after major changes.** New features change bytes/request and CPU/request; re-run the estimate and load test.

### 6.8 Production hardening

- **Provision for peak + headroom + redundancy, then autoscale the rest.** Static baseline for the predictable floor; autoscaling for the diurnal/spiky part; pre-scale (manual or scheduled) ahead of known events (launches, sales, sports).
- **Mind autoscaling lag.** Autoscalers react in minutes; instant spikes (a viral event) outrun them. Keep warm headroom or use predictive/scheduled scaling for known spikes.
- **Plan procurement lead time** for on-prem or constrained instance types — capacity you can't get in time is capacity you don't have.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's wrong | Fix |
|---|---|---|
| Sizing for **average**, not peak | Falls over at peak | × peak factor |
| Running at **100% utilization** | Latency → ∞ near saturation | target 50–70%, N+1 |
| **Bits/bytes confusion** | 8× bandwidth error | always note the unit, ÷8 explicitly |
| **Dropping seconds/day** | 10⁵× or 86,400× error | keep the constant in every QPS calc |
| Ignoring **fan-out** | massive under-count | ask "what does one request trigger?" |
| Trusting **folklore QPS/node** | could be 50× off | load-test the real endpoint |
| Counting **only payload** for cache RAM | 1.5–3× under-count | add overhead factor |
| Forgetting **replication factor** | 2–3× under-count on storage | × RF |
| Forgetting **index/metadata overhead** | DB bigger than estimated | × 1.2–2 |
| **Premature distribution** | complexity with no need | run the single-box test |
| Treating estimate as **final truth** | reality differs | move through the lifecycle, observe |
| **Forgetting egress cost** | budget blown | price egress GB explicitly |

---

## 7. Advanced topics & deep internals

### 7.1 Queueing theory: why headroom is non-negotiable

The reason you can't run at 100% comes from **queueing theory.** Model a server as an **M/M/1 queue**: random (Poisson) arrivals, exponentially distributed service times, one server.

> **M/M/1 notation (Kendall):** first M = Markovian (Poisson) arrivals; second M = Markovian (exponential) service; 1 = one server. **Poisson arrivals** = independent random events at a steady average rate. **ρ (rho)** = utilization = arrival rate ÷ service rate.

For M/M/1, the average number in the system and the average response time blow up as ρ → 1:

```
average response time  T = (1/μ) / (1 − ρ)
```

where `1/μ` is the bare service time and `ρ` is utilization. The `1/(1−ρ)` factor is the killer:

| Utilization ρ | Latency multiplier 1/(1−ρ) |
|---|---|
| 50% | 2× |
| 70% | 3.3× |
| 80% | 5× |
| 90% | 10× |
| 95% | 20× |
| 99% | 100× |

So a server at 90% utilization has ~**10× the latency** of the same server at idle, purely from queueing. This is the mathematical justification for targeting 50–70% and the reason "we have spare CPU at 90%, why is it slow?" is a confused question. Real systems are worse than M/M/1 (variable service times, M/G/1) so the cliff is even steeper.

> **Little's Law:** `L = λ × W` — the average number of items in a system (L) equals arrival rate (λ) times average time in system (W). It's exact for any stable system and lets you cross-check estimates: e.g., if you serve 10,000 QPS (λ) at 50 ms each (W), you have on average `L = 10,000 × 0.05 = 500` requests in flight — so you need ≥500 concurrent slots (threads/connections). This converts a latency+throughput estimate into a **concurrency** estimate.

### 7.2 The Universal Scalability Law (USL) — diminishing and negative returns

Linear scaling ("2× servers = 2× throughput") is a fiction. **Amdahl's Law** caps speedup because of a serial fraction; **Gunther's Universal Scalability Law** goes further and predicts throughput can *decrease* past a point due to coordination cost.

> **Amdahl's Law:** if fraction `s` of work is serial, max speedup with N nodes is `1 / (s + (1−s)/N)` — bounded by `1/s`. **USL** adds a **coherency/crosstalk** term (κ) for the cost of nodes coordinating (locks, cache coherence, consensus); throughput `C(N) = N / (1 + α(N−1) + κN(N−1))`. When κ>0, there's an optimal N beyond which adding nodes *reduces* throughput (retrograde scaling).

Practical implication for estimation: **don't assume perfectly linear horizontal scaling.** Apply a scaling-efficiency discount (e.g., assume 70–90% efficiency), and be especially wary for systems with shared coordination (distributed locks, single-leader consensus, hot shards). Validate the scaling curve with load tests at increasing node counts.

### 7.3 Tail latency amplification in scatter-gather

When one request fans out to N parallel sub-requests and waits for *all* (scatter-gather), the overall latency is the **maximum** of N samples — so even a small per-call tail becomes the common case. If each sub-call has p99 = 100 ms (1% chance of being slow), a fan-out of 100 has roughly `1 − 0.99^100 ≈ 63%` chance that *at least one* is slow. This **tail amplification** is why read-fan-out (pull) feeds are dangerous at scale and why techniques like **hedged requests** (send a duplicate after a delay, take the first to return) and **tied requests** exist. Account for it: a high-fan-out read tier needs far more latency headroom than its average suggests. (From Dean & Barroso, "The Tail at Scale.")

### 7.4 Storage internals that change the bytes estimate

- **Write amplification (LSM-trees):** Cassandra/RocksDB rewrite data during compaction, so one logical write causes several physical writes (write amplification often 2–30×). This inflates disk I/O and SSD wear estimates, even if logical bytes look small. (**Compaction** = merging LSM SSTable files to reclaim space and keep reads fast.)
- **Space amplification:** the same engines may temporarily hold more on-disk bytes than logical data (old + new during compaction). Provision disk for the worst case, not steady state.
- **B-tree fill factor & fragmentation (Postgres/MySQL InnoDB):** pages are rarely 100% full; fill factor (~70–90%) and bloat from updates/deletes mean real disk use exceeds row-size × row-count. **VACUUM** (Postgres) reclaims dead tuples but needs headroom.
- **Compression:** columnar/analytic stores (Parquet, ClickHouse) compress 3–10×; text logs compress well; already-compressed media doesn't. Apply a compression factor for the right data type.
- **Index overhead:** secondary indexes can equal or exceed table size. Count them.

### 7.5 Hot keys, skew, and the celebrity problem

Capacity math assumes *uniform* distribution by default, but real keys are **skewed** (Zipfian): a few keys get most of the traffic. A single hot shard/partition can be saturated while the fleet averages 30%. Estimation must consider **per-shard peak**, not fleet average. Mitigations to plan for: key salting/splitting, dedicated handling for hot keys, request coalescing, and the push/pull hybrid for celebrities. (**Zipfian distribution** = popularity ∝ 1/rank; the #1 item is far more popular than #2, etc.)

### 7.6 Cache hit ratio sensitivity

Server count for a read tier is dominated by the **cache miss rate**, and the relationship is nonlinear. If a cache fronts the DB and serves hit ratio `h`, DB read QPS = `read_QPS × (1 − h)`. Going from 90% → 99% hit ratio cuts DB load **10×** (from 10% to 1% misses). So small hit-ratio changes massively change downstream sizing — always state the assumed hit ratio and treat it as a high-leverage knob. Cache sizing (RAM) and hit ratio are coupled via the working-set distribution.

### 7.7 Little-known but high-impact estimation knobs

- **Connection overhead at the DB:** each DB connection costs memory and a backend process/thread (Postgres: a process per connection). High app-node counts × connections-per-node can exhaust DB connections long before CPU; you need **connection pooling** (HikariCP) and possibly a proxy (PgBouncer). Estimate connections, not just QPS.
- **TLS/serialization CPU:** TLS handshakes and JSON/Protobuf (de)serialization can be a large fraction of per-request CPU; for chatty small requests, serialization may dominate. Factor it into QPS/node.
- **Cold-start and JIT warmup (JVM):** a freshly started JVM is slower until the JIT compiler optimizes hot paths; autoscaled JVM instances serve *less* QPS for the first seconds/minutes. Provision for warmup or use AOT/CRaC. (**JIT** = Just-In-Time compiler; **CRaC** = Coordinated Restore at Checkpoint, a JVM feature for fast warm starts.)
- **Headroom for retries:** retry storms multiply load during partial failures. A system at 70% can hit 100%+ when clients retry. Budget for it and use jittered backoff + circuit breakers.

### 7.8 Two-significant-figure mode for forecasting

BOTE uses one sig fig, but *production forecasting* (lifecycle state *Re-forecast*) needs more rigor: fit growth curves (linear vs exponential vs S-curve), incorporate seasonality (weekday/weekend, monthly, annual), and add confidence intervals. Tools: time-series forecasting (Prophet, ARIMA) on historical telemetry. The mindset shifts from "order of magnitude" to "p90 forecast with lead-time buffer."

---

## 8. Tradeoffs & decision frameworks

### 8.1 Push vs pull (fan-out) decision

| Dimension | Push (fan-out on write) | Pull (fan-out on read) | Hybrid (recommended) |
|---|---|---|---|
| Write cost | High (×followers) | Low | Push for normal, skip for celebs |
| Read cost | Low (precomputed) | High (×following) | Mostly precomputed |
| Read latency | Low, predictable | High, tail-prone | Low |
| Celebrity / hot key | Catastrophic burst | Fine | Pull for celebs |
| Storage | More (duplicated timelines) | Less | Medium |
| **Use when** | Reads ≫ writes, few followers/user | Writes cheap, sparse reads, huge fan-out users | Real social feeds at scale |
| **Avoid when** | Massive-fan-out users exist | Read latency SLA is tight | (rarely — hybrid is usually right) |

### 8.2 Scale up vs scale out

| | Scale up (bigger box) | Scale out (more boxes) |
|---|---|---|
| Simplicity | High (no distribution) | Lower (sharding, coordination) |
| Ceiling | Hardware max (but huge now) | Effectively unbounded |
| Failure blast radius | One box = whole service | One box = a slice |
| Cost curve | Superlinear at the top | ~Linear (with USL discount) |
| **Use when** | Fits one box w/ headroom; strong consistency needs | Exceeds one box; need fault isolation; elastic |
| **Avoid when** | Already near box limits | Single box would do (premature distribution) |

**Decision rule:** run the **single-box test** first. If a modern large instance (with headroom and an N+1 standby) serves the peak, scale up — it's dramatically simpler. Scale out only when the estimate proves one box can't do it, or when fault isolation/elasticity demand it.

### 8.3 Cache vs read replicas vs more primary capacity (read scaling)

| Option | Scales | Cost | Consistency | Use when |
|---|---|---|---|---|
| Add cache (Redis/CDN) | reads, cheaply | low | stale within TTL | hot working set, tolerant of staleness |
| Add read replicas | reads | medium | replica lag | many distinct reads, can tolerate lag |
| Bigger/faster primary | reads + writes | high | strong | small scale, strong-consistency reads |
| Shard | reads + writes | high complexity | per-shard | exceeds single-primary capacity |

### 8.4 Where to spend the estimation effort (by dominant resource)

| Workload archetype | Dominant resource | Estimate focus |
|---|---|---|
| Public API gateway | CPU / QPABS-per-node | QPS, p99, node count |
| Social feed | fan-out, read/write split | push/pull, internal QPS |
| URL shortener / KV | storage + read QPS | DB size, cache hit ratio, ID space |
| Video/media | egress bandwidth + CDN cost | Gbps, \$/GB egress |
| Logging/metrics/IoT | write throughput + storage | write QPS, retention, compression |
| In-memory cache/limiter | RAM + op-rate | counter size, ops/s, node count |
| Analytics/OLAP | storage + scan throughput | compressed bytes, scan GB/s |

### 8.5 When to do estimation at all (and how deep)

- **Skip / minimal:** a CRUD app for an internal team of 50 — a single box is obviously enough; a one-line sanity check suffices.
- **Standard BOTE:** any new external-facing service, any design interview, any growth-sensitive feature.
- **Deep, validated forecasting:** large-scale systems, paid SLAs, expensive infra, known traffic events, or anything where being wrong costs an outage or a big bill.

---

## 9. Failure modes & debugging

### 9.1 Estimation failure modes (errors in the math/model)

| Symptom | Likely error | How to catch it |
|---|---|---|
| Answer off by ~86,400 or 10⁵ | dropped seconds/day | re-derive QPS from req/day ÷ 86,400 |
| Bandwidth off by 8× | bits vs bytes | label units; ÷8 once, explicitly |
| Storage 2–3× too small | forgot replication / overhead | × RF × index overhead |
| QPS estimate way too low | forgot fan-out | "what does one request trigger?" |
| "We need 5,000 servers" for a small app | folklore QPS/node, or peak/headroom double-applied | load-test; recompute chain |
| Cost line absurd (\$10M/mo side feature) | unit slip somewhere | the dollar sanity check |
| Cache "fits" but evicts constantly | counted payload only, not overhead | × 1.5–3 overhead, measure live |

### 9.2 Production failure modes (the system, once built)

| Failure | Capacity root cause | Diagnose with |
|---|---|---|
| Latency cliff at peak | ran near 100% util (queueing) | utilization + queue-depth metrics; M/M/1 reasoning |
| Cascading overload after one node dies | no N+1 headroom; survivors saturate | per-node util; chaos/kill test |
| DB connections exhausted, CPU fine | sized QPS not connections | DB `pg_stat_activity`; pool metrics (HikariCP) |
| Disk full unexpectedly | forgot write amplification / index / WAL growth | `df`, `iostat`, table-size queries |
| Cache stampede / thundering herd | mass expiry → DB hit by all misses | cache hit-ratio metric; add jittered TTL + request coalescing |
| Hot shard saturated, fleet idle | key skew, sized on average | per-shard metrics; rebalance/salt keys |
| Egress bill explosion | egress under-estimated, no CDN | CDN hit ratio; egress GB metric |
| Long GC pauses tank p99 | heap too small / wrong GC for size | `jstat -gcutil`, GC logs, JFR |
| Autoscaler can't keep up with spike | spike outruns autoscale lag | scaling event logs; pre-warm/scheduled scaling |
| Retry storm during partial failure | no headroom for retries | request-rate vs upstream-error correlation |

### 9.3 The debugging toolkit (commands you actually run)

```bash
# CPU / run-queue / context switches (saturation signals)
vmstat 1
mpstat -P ALL 1
pidstat -u -p <pid> 1

# Disk I/O and saturation (is the DB disk-bound?)
iostat -xz 1            # watch %util, await, aqu-sz
df -h                   # is the disk filling?

# Network bandwidth (egress reality check)
iftop -i eth0
ss -s                   # socket/connection summary

# JVM: GC behavior and heap (Java app servers)
jstat -gcutil <pid> 1s  # S0/S1/E/O/M, YGC/FGC counts & times
jcmd <pid> GC.heap_info
# Java Flight Recorder for a 60s profile:
jcmd <pid> JFR.start duration=60s filename=rec.jfr settings=profile

# Live QPS / latency from Prometheus (PromQL)
#   rate(http_requests_total[1m])                      -> QPS
#   histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))  -> p99

# Honest load test at a FIXED rate (avoids coordinated omission)
wrk2 -t8 -c200 -d60s -R5000 --latency http://svc/endpoint
```

> **WAL** = Write-Ahead Log, the durability journal a database writes before applying changes; its growth is a real storage line item. **Cache stampede / thundering herd** = many clients simultaneously missing the cache (often after a synchronized expiry) and all hammering the backend at once.

### 9.4 Real-world incident patterns (capacity flavored)

- **The launch-day outage:** sized for *average* expected users; the launch spike (peak factor 10×) crushed the under-provisioned tier. Lesson: provision for peak and pre-scale events.
- **The celebrity meltdown:** a pure push-fan-out feed; a celebrity post fanned out to tens of millions of timeline writes, saturating the write tier and queue. Lesson: hybrid push/pull, special-case hot keys.
- **The retry-storm collapse:** a dependency slowed slightly; clients retried aggressively without backoff; offered load tripled and took the whole system down — a metastable failure where load stays high even after the trigger clears. Lesson: budget headroom for retries, use jittered exponential backoff + circuit breakers + load shedding.
- **The silent disk-fill:** logical data looked small but LSM compaction space amplification + WAL + indexes filled the disk during a compaction; the DB went read-only. Lesson: provision worst-case disk, monitor compaction.
- **The egress surprise:** an internal estimate ignored cross-region/internet egress; the bill arrived an order of magnitude over budget. Lesson: price egress GB explicitly; put a CDN in front.

> **Metastable failure** = a system that, once pushed past a threshold, sustains the overloaded state via a feedback loop (e.g., retries) even after the original trigger is gone; recovery requires shedding load, not just removing the trigger.

---

## 10. Interview drill

Each question has a crisp model answer plus deep-probe follow-ups with answers. Three "senior-signal" questions (marked ★) test judgment, not recall.

### Q1. Estimate the QPS for a service with 100M DAU where each user makes 30 requests/day.

**Model answer:** Average QPS = `100M × 30 ÷ 86,400 ≈ 34,700` ≈ ~35k QPS. Provision for peak: with a ~2× peak factor, ~70k peak QPS. I'd round and state the assumption that peak is 2× average; if I had a "X% of traffic in Y hours" figure I'd compute peak precisely.

- **Probe: where did 86,400 come from?** Seconds in a day; I round to 10⁵ for mental math, which also adds a small margin.
- **Probe: average or peak for provisioning?** Always peak — averages cause outages at the daily traffic peak. I multiply by the peak factor and divide by target utilization (0.5–0.7).
- **Probe: how would you validate the per-node QPS?** Load-test the *actual* endpoint with wrk2/Gatling at a fixed rate to get honest p99, since per-node capacity is the most uncertain input.

### Q2. Design the capacity for a URL shortener: 500M new URLs/month, 100:1 read:write.

**Model answer:** Writes ≈ `500M ÷ (30 × 86,400) ≈ 193/s`; reads ≈ `193 × 100 ≈ 19,300/s`. 5-year records = `500M × 12 × 5 = 30B`. At ~500 B/record → ~15 TB (×RF 3 ≈ 45 TB) ⇒ shard a KV store. Reads are skewed/hot so a cache fronts the store and absorbs most reads. base62 length: `62^7 ≈ 3.5×10¹²` ≫ 30B, so 7 chars is ample (6 would also fit 30B with `62^6 ≈ 5.7×10¹⁰`).

- **Probe: KV or relational?** KV/wide-column — the access pattern is a point lookup `code → url`; no joins; scales horizontally. Relational works at small scale.
- **Probe: how do you generate unique short codes?** Counter/ID-generator (e.g., snowflake/ticket server) encoded to base62, or hash-of-URL with collision handling. A central counter must be sharded/ranged to avoid a bottleneck.
- **Probe: cache sizing?** Cache the hot fraction (Pareto ~20% → ~80% of reads); RAM = hot_items × bytes_per_item; a 99% hit ratio cuts DB read load 100×.

### Q3. Design the capacity for a Twitter-like newsfeed. ★ (senior-signal)

**Model answer:** The crux is fan-out, so I'd compute *internal* load for both models. With 100M DAU, 2 posts/user/day, avg 200 followers: push fan-out ≈ `2e8 × 200 ÷ 86,400 ≈ 463k timeline writes/s` (reads cheap, precomputed). Pull: reads explode to `feed_reads × following ÷ 86,400` ≈ millions of sub-reads/s with scatter-gather tail risk. Neither is ideal alone: push detonates on celebrities (one post = tens of millions of writes); pull has brutal read amplification. **So I'd recommend a hybrid**: push for normal users, pull-and-merge for celebrities. This is justified entirely by the fan-out numbers and the celebrity hot-key problem.

- **Probe: why does push fail for celebrities?** A single celebrity post fans out to `#followers` writes (tens of millions) in a burst, saturating the write tier — a hot-key problem.
- **Probe: what's the read-path latency risk in pull?** Scatter-gather over hundreds of followees; overall latency = max of sub-calls, so tail amplification makes slow common. Mitigate with hedged requests and caps.
- **Probe: how do you bound the hybrid threshold?** Choose a follower-count cutoff (e.g., >X00k followers ⇒ pull) by comparing the marginal write cost of fan-out vs the read-merge cost; tune from telemetry.

### Q4. How much storage will a system that ingests 200M events/day at 1 KB each need over 5 years?

**Model answer:** Daily = `2e8 × 1 KB = 200 GB/day`. 5 years = `200 GB × 365 × 5 ≈ 365 TB` logical. With RF=3 ≈ ~1.1 PB; add index/overhead (×1.2–2) and account for write/space amplification if LSM-based. Compression (3–10× for text/columnar) could bring it back down substantially. I'd state the retention policy explicitly — if we only keep 90 days, it's `200 GB × 90 × 3 ≈ 54 TB`.

- **Probe: replication factor effect?** Linear multiplier on durable storage (RF=3 triples it); a real cost/durability tradeoff.
- **Probe: what about write amplification?** LSM compaction rewrites data (2–30×), inflating disk I/O and SSD wear, and space amplification needs temporary headroom during compaction.
- **Probe: would you keep it all hot?** No — tier: hot (recent) on SSD, cold (old) on cheap object storage/HDD with compression; archive or delete past retention.

### Q5. Estimate bandwidth for a video platform: 50M DAU, 1 hour/day, 5 Mbps.

**Model answer:** Bits/day = `50M × 3600 s × 5 Mbps = 9×10¹⁷ bits`. Average egress = that ÷ 86,400 ≈ ~1 Tbps; peak (×3) ≈ ~3 Tbps. This is far beyond any origin fleet ⇒ **CDN mandatory.** Monthly egress ≈ ~11 PB; even at \$0.05/GB that's ~\$0.5M/month — the dominant cost, which justifies the CDN architecture (or building one, like Netflix Open Connect).

- **Probe: bits or bytes?** I quote bandwidth in bits/sec (network convention) and divide by 8 for byte sizes/costs; mixing them is an 8× error.
- **Probe: why CDN, specifically?** Edge caches serve content near users, offloading origin egress and bandwidth and cutting latency; origin only serves cache misses.
- **Probe: how does adaptive bitrate change the estimate?** Average bitrate varies with device/network; I'd use a weighted average bitrate across resolutions rather than a single 5 Mbps.

### Q6. Your service runs at 90% CPU and latency is terrible despite "spare" capacity. Why? ★ (senior-signal)

**Model answer:** Queueing theory: as utilization ρ → 1, latency scales by `1/(1−ρ)`. At 90% that's a 10× latency multiplier from queueing alone — the "spare 10%" is an illusion because the queue grows nonlinearly. The fix is to add capacity to bring steady-state utilization to ~50–70%, not to interpret 90% as "almost full but fine."

- **Probe: cite the model.** M/M/1: `T = (1/μ)/(1−ρ)`. Real systems with variable service times (M/G/1) are even worse.
- **Probe: what metric warns you before the cliff?** Saturation — run-queue length, queue depth, connection-pool waiters, GC time — not just utilization.
- **Probe: how does this change provisioning?** Divide raw needed capacity by target utilization (0.5–0.7), i.e., multiply by 1.4–2×, on top of peak and redundancy factors.

### Q7. How many app servers for 200k peak QPS, given each box does ~2,000 QPS?

**Model answer:** Raw = `200k ÷ 2,000 = 100`. Apply target utilization 60%: `200k ÷ (2000×0.6) ≈ 167`. Add N+2 redundancy and round for multi-AZ → ~170–180 boxes, distributed across ≥3 AZs. The 2,000 QPS/box is an assumption I'd validate by load-testing the real endpoint.

- **Probe: why divide by utilization?** To keep headroom for bursts, failover, and to stay off the latency cliff.
- **Probe: how does AZ distribution affect count?** You want each AZ able to absorb the others' load on failure, which can push you toward N+1 *per AZ* — more boxes than the bare math.
- **Probe: throughput vs latency limit?** Provision to whichever binds first; a box might do 2,000 QPS on throughput but breach p99 SLA at 1,200 — then size to 1,200.

### Q8. ★ (senior-signal) When would you NOT shard, even at large scale?

**Model answer:** When the single-box test passes — modern instances are huge (dozens of cores, hundreds of GB RAM, multi-TB NVMe at GB/s). If peak load, working set, and storage fit one big box with headroom plus an N+1 standby, I'd scale up: sharding adds rebalancing, cross-shard queries, hot-shard risk, and operational complexity for no benefit. I shard only when the estimate proves one box can't hold the data or serve the throughput, or when I need fault isolation/elastic scale-out. Premature distribution is a top anti-pattern.

- **Probe: what forces sharding regardless of box size?** Data exceeding one disk, write throughput exceeding one primary, or a need for blast-radius isolation/elasticity.
- **Probe: USL implication?** Adding nodes isn't free — coordination cost (USL κ term) gives diminishing/negative returns; fewer bigger nodes can outperform many small ones for coordination-heavy work.
- **Probe: consistency angle?** A single primary gives strong consistency trivially; distributing introduces consensus/replication-lag tradeoffs (CAP) you may not want.

### Q9. How do you size a cache?

**Model answer:** Identify the hot working set (Pareto: ~20% of items → ~80% of reads, or measure the access distribution). Cache bytes = hot_items × bytes_per_item × overhead factor (1.5–3× for structure overhead). Pick a target hit ratio and note its leverage: 90%→99% cuts backend load 10×. Compare to node RAM to get node count; choose eviction (LRU/LFU) and TTL with jitter to avoid stampedes.

- **Probe: why the overhead factor?** Real RAM = payload + key + data-structure/object overhead + fragmentation; JVM/Redis overhead can double or triple naive size.
- **Probe: hit-ratio sensitivity?** Downstream DB QPS = read_QPS × (1−hit); it's nonlinear, so small hit-ratio gains hugely cut DB sizing.
- **Probe: how prevent stampedes?** Jittered TTLs, request coalescing/single-flight, and serving stale-while-revalidate.

### Q10. ★ (senior-signal) How do peak factor, utilization headroom, and redundancy combine — won't they over-provision?

**Model answer:** They stack multiplicatively but each addresses a distinct risk: peak factor (×2–3) handles diurnal/event spikes; dividing by utilization (×1.4–2) keeps you off the queueing cliff; N+1/N+2 covers failures and deploys. Together they often make provisioned capacity 3–10× the naïve average — which is correct, not wasteful, because each protects a real failure mode. The way to avoid *true* over-provisioning is to (a) validate per-node capacity with load tests so the base number is right, and (b) handle the spiky portion with autoscaling/scheduled pre-scaling rather than a static fleet, while keeping the predictable floor statically provisioned.

- **Probe: how do you avoid double-counting?** Apply each factor once and for its specific purpose; don't, e.g., bake redundancy into both the peak factor and a separate N+1.
- **Probe: where does autoscaling fit?** Static baseline for the floor, autoscaling for the diurnal/burst portion — but mind autoscale lag for instant spikes; pre-warm for known events.
- **Probe: how would you justify the spend to finance?** Translate to dollars and tie each multiplier to an avoided-outage/SLA risk; show the cost of an outage vs the cost of headroom.

### Q11. Estimate memory for a per-user counter rate limiter at 100M users.

**Model answer:** ~100 B/counter (key + value + overhead) × 100M ≈ **10 GB** — fits one large Redis with headroom. Op rate: 2 ops/request (INCR + check) × peak req/s; at 500k req/s that's ~1M ops/s, near a single Redis ceiling ⇒ shard with Redis Cluster for safety. Dominant resources here are RAM and op-rate, not storage.

- **Probe: why 100 B not 16 B?** Real entry includes key string, value, TTL, and Redis internal overhead — count the true per-entry cost, not just the integer.
- **Probe: single-threaded Redis implications?** One core per instance caps ops/s; near the ceiling you shard by key hash (Redis Cluster) to add cores.
- **Probe: what about token-bucket state vs simple counters?** Token bucket needs (tokens, last-refill-ts) per key — a bit more memory and a small compute step per request; factor it in.

### Q12. How do you present a capacity estimate in an interview?

**Model answer:** (1) State assumptions out loud and write them (DAU, requests/user, R:W, sizes, peak factor, RF). (2) Pick the anchor and write the chain symbolically before numbers. (3) Round to one sig fig, keep powers of ten, and convert time to seconds / data to bytes. (4) Compute QPS (split R/W), storage, bandwidth, memory, server count — narrating units. (5) Apply peak + headroom + redundancy. (6) Summarize in a small table and sanity-check (single-box test, latency budget, cost). (7) Connect the numbers to a design decision ("15 TB ⇒ shard"; "3 Tbps ⇒ CDN"). The interviewer is grading *reasoning and units*, not arithmetic precision.

- **Probe: what if you don't know a number?** State a labeled assumption and move on; offer how you'd validate it (load test, analytics). Never invent false precision.
- **Probe: how do you recover from an arithmetic slip?** Use sanity checks (order of magnitude, off-by-8, seconds/day) to catch it, and recompute calmly — catching your own error is a positive signal.
- **Probe: how detailed should you go?** Match the interviewer's cues; go deep on the *dominant* resource and the design-driving number, lighter on the rest.

---

## 11. Glossary

- **Anchor (estimation):** the single starting input everything multiplies from, usually DAU or total requests.
- **Amdahl's Law:** speedup is capped by the serial fraction of work; max ≈ 1/s.
- **AOT:** Ahead-Of-Time compilation; compiling code before run time (vs JIT) for faster startup.
- **Autoscaling:** automatically adding/removing instances based on live load.
- **AZ (Availability Zone):** an isolated datacenter within a cloud region.
- **Backoff (exponential, jittered):** retrying after exponentially increasing, randomized delays to avoid synchronized retry storms.
- **Bandwidth / throughput:** data flow rate, bits/sec (network) or bytes/sec.
- **base62:** number encoding using 0-9, a-z, A-Z; 62 symbols per character.
- **Bit vs byte:** 1 byte = 8 bits; network in bits/sec, storage in bytes.
- **BOTE (back-of-the-envelope):** quick order-of-magnitude calculation with rounded numbers.
- **Branch mispredict:** CPU guessed the wrong branch and discarded speculative work.
- **CAP theorem:** in a network partition, a distributed system can't have both perfect consistency and availability; you choose.
- **CDN (Content Delivery Network):** distributed edge caches serving content near users.
- **Circuit breaker:** a guard that stops calling a failing dependency to prevent cascading failure.
- **Coordinated omission:** a load-test bug where waiting for slow responses suppresses sends, hiding tail latency.
- **Compaction (LSM):** merging on-disk sorted files to reclaim space and keep reads fast.
- **CRaC:** Coordinated Restore at Checkpoint; JVM fast-restart from a snapshot.
- **DAU / MAU:** Daily / Monthly Active Users; ratio measures stickiness.
- **DDoS:** Distributed Denial of Service attack.
- **Diurnal:** following a 24-hour day/night cycle.
- **Egress:** outbound data leaving your network/region (often billed per GB).
- **Fan-out:** number of secondary operations one request triggers (write fan-out / read fan-out).
- **FinOps:** discipline of managing/forecasting cloud spend.
- **fsync:** syscall forcing buffered writes to durable storage; caps durable write throughput.
- **GC (Garbage Collection):** automatic reclamation of unused heap; can cause stop-the-world pauses.
- **G1 / ZGC / Shenandoah:** JVM garbage collectors; ZGC/Shenandoah target very low pause times.
- **Headroom:** deliberately unused capacity fraction kept for bursts/failures.
- **HdrHistogram:** library for high-dynamic-range latency histograms.
- **Hedged request:** sending a duplicate request after a delay and taking the first response, to cut tail latency.
- **Hot key / hot shard:** a key/partition receiving disproportionate traffic.
- **IOPS:** I/O operations per second.
- **JIT:** Just-In-Time compiler; optimizes hot code paths at run time after warmup.
- **Kendall notation (M/M/1):** describes a queue's arrival/service/server characteristics.
- **KV store:** key-value database with direct key lookup.
- **L1/L2 cache:** small fast memory on the CPU.
- **Little's Law:** L = λ × W; items-in-system = arrival rate × time-in-system.
- **Load testing:** measuring real capacity under synthetic load.
- **LSM-tree:** Log-Structured Merge tree; write-optimized storage engine.
- **Metastable failure:** an overloaded state sustained by a feedback loop even after the trigger clears.
- **Mutex:** mutual-exclusion lock allowing one thread at a time in a critical section.
- **N+1 / N+2 redundancy:** provisioning extra units to survive 1 / 2 failures.
- **NIC:** Network Interface Card.
- **NVMe:** fast SSD interface over PCIe.
- **Origin:** authoritative servers behind a CDN.
- **p50/p95/p99/p999:** latency percentiles; p99 = 99% of requests faster than this.
- **Pareto principle:** ~80% of effects from ~20% of causes; used for cache hot-set sizing.
- **Peak factor:** ratio of peak load to average load.
- **Poisson process:** independent random events at a steady average rate.
- **Project Loom:** JVM virtual threads; lightweight threads for scalable blocking code.
- **QPS:** Queries (requests) Per Second.
- **RED method:** monitor Rate, Errors, Duration per service.
- **Read replica:** synced read-only DB copy that offloads reads (with lag).
- **Read/write ratio (R:W):** proportion of reads to writes; drives caching/replication.
- **Replication factor (RF):** number of stored copies of each datum (commonly 3).
- **Retry storm:** clients retrying en masse, multiplying load during failures.
- **Scatter-gather:** fan a request to many shards/services in parallel, gather results.
- **Scale up / scale out:** bigger box vs more boxes.
- **Sharding:** splitting data across machines by key.
- **SLA / SLO:** Service Level Agreement / Objective (latency, availability targets).
- **SRE:** Site Reliability Engineering.
- **Stateless:** server keeps no per-client session state; enables easy horizontal scaling.
- **Stock vs flow:** an amount at a point in time vs an amount per unit time.
- **Syscall:** request from a program to the OS kernel.
- **Tail latency:** high-percentile (p99+) latency; what users feel in bad moments.
- **Thundering herd / cache stampede:** many simultaneous cache misses hammering the backend.
- **TOAST:** PostgreSQL mechanism for storing oversized column values out-of-line.
- **TTL:** Time To Live; how long a cache entry or record is valid.
- **USE method:** monitor Utilization, Saturation, Errors per resource.
- **USL (Universal Scalability Law):** models contention + coherency cost; predicts diminishing/negative returns.
- **Utilization (ρ):** fraction of a resource's max capacity in use.
- **UUID:** 128-bit universally unique identifier.
- **VACUUM:** PostgreSQL process reclaiming dead tuples.
- **WAF:** Web Application Firewall.
- **WAL:** Write-Ahead Log; durability journal written before applying DB changes.
- **Working set:** the subset of data actively accessed in a time window.
- **Write amplification:** physical writes exceeding logical writes (LSM compaction).
- **Zipfian distribution:** popularity ∝ 1/rank; produces strong skew/hot keys.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Constants to memorize**
- 1 day ≈ **86,400 s ≈ 10⁵ s**. 1 year ≈ **π×10⁷ ≈ 3.15×10⁷ s**.
- 1 byte = **8 bits**. 1 Gbps ≈ **125 MB/s**.
- Default RF = **3**; default peak factor = **2–3×**; target util = **50–70%**; cache Pareto = **20%→80%**.

**Latency ladder (orders of magnitude)**
- L1 ~0.5 ns · RAM ~100 ns · SSD random ~100 µs · same-DC RTT ~0.5 ms · HDD seek ~10 ms · cross-continent RTT ~150 ms.
- RAM ≈ 100,000× faster than disk seek; sequential ≫ random; cross-region calls are brutal.

**Core formulas**
- avg QPS = DAU × actions/user ÷ 86,400 · peak QPS = avg × peak_factor.
- split: read = total × R/(R+W); write = total × W/(R+W).
- storage = writes/day × bytes/write × retention_days × RF × overhead.
- egress bits/s = read QPS × bytes/response × 8.
- nodes = ceil(peak ÷ (per_node × util)) + redundancy.
- cost/mo = nodes × \$/hr × 730.
- Little's Law: in-flight = QPS × latency. Queueing: latency × 1/(1−util).

**Procedure (9 steps):** clarify+anchor → ops+ratios → avg QPS (split R/W) → peak QPS → storage → bandwidth → memory → server counts → headroom+sanity.

**Decision rules**
- Provision for **peak**, never average. Target **50–70% util**. Add **N+1/N+2**.
- Run the **single-box test** before sharding (modern boxes are huge).
- Identify the **dominant resource** and focus there.
- Apply **fan-out**; choose **push/pull/hybrid** from the numbers.
- Convert everything to **dollars** as the final sanity check.
- **Bits vs bytes** and **seconds/day** are the two error hotspots.
- Load-test to replace the **QPS/node** assumption.

**Sanity checks:** order-of-magnitude · off-by-8 · seconds/day · single-box · latency budget · cost.

### 12.2 Self-test (no answers — active recall)

1. A service has 80M DAU, each making 40 reads and 2 writes/day, read responses 4 KB, write payload 1 KB, peak factor 3, RF 3, retained 3 years. Compute: average and peak read/write QPS, daily and 3-year storage, and peak egress bandwidth in Gbps. State every assumption you add.
2. Without looking, write the latency ladder from L1 cache to a cross-continent round trip, and explain in one sentence each why caching exists, why sequential beats random, and why cross-region calls are avoided on the hot path.
3. You estimate a read tier needs 250k peak QPS and each box does ~3,000 QPS. How many boxes do you provision across 3 AZs, and why is the answer not simply 250,000 ÷ 3,000? Show every factor you apply.
4. For a newsfeed with 200M DAU, 1 post/user/day, average 300 followers, and a celebrity with 80M followers: compute push-model average timeline-write QPS and the burst from one celebrity post; then argue from the numbers whether to use push, pull, or hybrid.
5. A counter-based limiter serves 800k req/s at peak with 2 ops/request for 150M users at ~120 B/counter. Compute counter RAM and Redis ops/s, and decide whether one node suffices or you must shard — justify both the memory and op-rate decisions.
6. Your service sits at 92% CPU with terrible p99 but "8% spare." Explain quantitatively (with the queueing relationship) why latency is bad, what utilization you'd target instead, and what single metric would have warned you before the cliff.
7. Explain why a storage estimate of "logical bytes only" is wrong for an LSM-backed store, naming at least three multipliers/overheads you must add and roughly how big each can be.

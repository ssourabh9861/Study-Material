# Redundancy, Failover & Multi-Region

> An exhaustive engineering-handbook chapter for senior JVM/backend developers. Goal: master redundancy models, failover mechanics, multi-AZ/multi-region architecture, replication, RTO/RPO, DNS/health-checked routing, blast-radius containment, split-brain, and disaster recovery — from first principles to deep internals.

---

## 1. Overview & where it fits

### What it is

**Redundancy** is the deliberate duplication of components so the system keeps working when one copy fails. **Failover** is the act of switching traffic or responsibility from a failed component to a healthy spare, ideally automatically and quickly. **Multi-region** extends both ideas across geographically separate data-center footprints so that the failure of an entire region — power, network, control-plane, natural disaster — does not take your service down.

These three are layers of the same defensive idea applied at increasing scope:

- **Within a host:** redundant disks (RAID), dual power supplies, ECC memory.
- **Within a rack/data center:** redundant servers behind a load balancer.
- **Within a region:** redundant **Availability Zones** (AZs) — physically separate data centers a few km apart, low-latency-linked.
- **Across regions:** redundant **regions** — separated by hundreds or thousands of km, independent failure domains.

> **Availability Zone (AZ):** A cloud provider's term (AWS, Azure "zones", GCP "zones") for one or more physically isolated data centers inside a region, each with independent power, cooling, and networking, but connected to sibling AZs by high-bandwidth, low-latency (typically sub-millisecond to ~2 ms) private links. The point of an AZ is to be an *independent failure domain* close enough for synchronous replication.
>
> **Region:** A large geographic area (e.g. `us-east-1` in N. Virginia, `eu-west-1` in Ireland) containing multiple AZs. Regions are far apart, so inter-region latency is tens to hundreds of milliseconds and replication is usually asynchronous.

### The problem it solves

Every physical and logical component has a non-zero failure rate. Disks die, NICs flap, processes OOM, a deploy ships a bug, a fiber cut isolates a building, a config push bricks a region's control plane. If your service maps 1:1 onto any single such component, your availability is capped by that component's reliability. Redundancy decouples *service* availability from *component* availability. Failover makes the decoupling automatic. Multi-region makes it survive correlated, large-scale failures.

The quantitative driver is the **availability budget**, usually expressed in "nines":

| Availability | Downtime / year | Downtime / month (30d) | Downtime / day |
|---|---|---|---|
| 99% (two nines) | 3.65 days | 7.2 h | 14.4 min |
| 99.9% (three nines) | 8.77 h | 43.8 min | 1.44 min |
| 99.95% | 4.38 h | 21.9 min | 43.2 s |
| 99.99% (four nines) | 52.6 min | 4.38 min | 8.64 s |
| 99.999% (five nines) | 5.26 min | 26.3 s | 0.86 s |

You generally cannot reach three-plus nines with a single instance of anything. You buy nines by adding independent redundant copies and automating failover. The math: if a component is available with probability *a*, and you have *n* truly independent copies any one of which suffices, the system is unavailable only when **all** fail: unavailability `= (1 - a)^n`, so availability `= 1 - (1 - a)^n`. Two independent 99% components in parallel → `1 - 0.01^2 = 99.99%`. The catch — and the entire subtlety of this chapter — is the word **independent**.

### When you reach for it

- Your SLA/SLO demands more nines than a single node provides.
- A single failure (host, AZ, region) currently causes user-visible downtime or data loss.
- Regulatory or business continuity rules require disaster recovery (DR) with bounded recovery time and data loss.
- You are scaling and want to remove single points of failure (SPOFs) before they bite.

### The one-paragraph mental model

Think of availability as a **chain of failure domains**, and redundancy as **putting independent copies in parallel** at whatever level the chain is weakest. Each level — disk, host, AZ, region — is a blast radius; you contain failures by making the next level up redundant and by detecting failure (health checks) and routing around it (failover) faster than your error budget can be spent. The two hard problems are not the happy path but (a) **detection without false positives** and (b) **avoiding two copies both believing they're in charge** (split-brain) — and across regions, the speed-of-light tax forces a choice between data freshness (RPO) and write availability, which is just the CAP theorem wearing a DR costume.

> **SLA / SLO / SLI:** An **SLI** (Service Level Indicator) is a measured number (e.g. % of successful requests). An **SLO** (Objective) is the target you hold yourself to (e.g. 99.95% success). An **SLA** (Agreement) is the contractual promise to customers, usually with penalties, set *below* the SLO so you have margin.
>
> **Error budget:** `1 - SLO`. At 99.9% you may be "down" 43.8 min/month. You spend this budget on incidents, risky deploys, and chaos experiments. It turns reliability into a number you can manage.
>
> **SPOF (Single Point of Failure):** Any component whose failure alone takes the system down. The whole game is finding and eliminating SPOFs — including sneaky ones like a shared config service, a single DNS provider, or one human with the only deploy key.

---

## 2. Foundations from first principles

### 2.1 Failure domains and correlation

A **failure domain** (a.k.a. fault domain) is the set of things that fail together when one underlying thing fails. A power strip is a failure domain for the servers plugged into it. A top-of-rack switch is a failure domain for its rack. An AZ is a failure domain for everything in it. A region's shared control plane (the APIs that launch instances, attach volumes, update load balancers) can be a failure domain for the whole region.

Redundancy only helps when copies live in **different** failure domains. Two replicas in the same rack survive a single disk failure but not a rack power loss. The recurring engineering error is *hidden correlation*: replicas you believe are independent share a dependency (same AZ, same database, same certificate, same DNS provider, same deploy pipeline, same Kafka cluster). When that hidden dependency fails, your "redundant" system fails as one.

> **Correlated failure:** When multiple components fail at the same time because of a shared cause. The independence assumption in the `(1-a)^n` formula breaks completely under correlation; this is why real availability is almost always worse than the naive math predicts.

### 2.2 Redundancy is not the same as load balancing or scaling

- **Scaling (horizontal):** adding capacity to handle more load. Coincidentally provides redundancy if any node can serve any request.
- **Load balancing:** distributing traffic across nodes. The mechanism that *uses* redundancy, but a load balancer can itself be a SPOF.
- **Redundancy for availability:** keeping spare capacity specifically so a failure does not reduce service below acceptable levels.

These overlap but are distinct: you can be horizontally scaled and still have zero spare headroom — losing one node then causes cascading overload. Redundancy requires *spare* capacity, which is the cost discussed in §6 and §8.

### 2.3 The core metrics: RTO and RPO

Two numbers define the business requirement for failover and DR.

> **RTO (Recovery Time Objective):** The maximum acceptable *time to restore service* after a failure. "We must be back up within 5 minutes." It is about **downtime**.
>
> **RPO (Recovery Point Objective):** The maximum acceptable amount of *data loss*, measured in time. "We can lose at most 30 seconds of writes." It is about **data freshness** at the recovery point.

A picture in time:

```
        last good backup / last replicated write          failure
        |<----------------- RPO window ------------------>|  occurs
   ─────●─────────────────────────────────────────────────X──────────► time
                                                            |<-- RTO -->|
                                                            recovery   service
                                                            begins     restored
```

- **RPO = 0** means zero data loss → requires synchronous replication (every write is committed in ≥2 places before acknowledging the client). This costs latency and write availability.
- **RTO = 0** means zero downtime → requires already-running hot standby with instant, automatic traffic switch (active-active, or active-passive with pre-warmed capacity and fast health-checked routing).

Both near-zero is the most expensive corner of the design space. Most systems pick numbers per data class: payments RPO ~0, analytics RPO hours, session caches RPO = "who cares, regenerate."

Related, often-confused terms:

> **MTBF (Mean Time Between Failures):** average uptime between failures of a component. Higher is better.
> **MTTR (Mean Time To Recover/Repair):** average time to restore after a failure. Lower is better. Availability ≈ `MTBF / (MTBF + MTTR)`. Failover automation attacks MTTR; redundancy attacks the *impact* of MTBF.
> **MTTD (Mean Time To Detect):** time from failure occurring to it being noticed. Failover can't start until detection happens, so MTTD is part of RTO.

### 2.4 Redundancy models

These describe *how much* spare you keep and *how* you arrange it.

> **N:** the number of units required to serve the load at capacity. If you need 4 app servers to handle peak, N = 4.

| Model | Meaning | Survives | Spare cost | Typical use |
|---|---|---|---|---|
| **N** | Exactly enough, no spare | nothing | 0% | dev/test, non-critical |
| **N+1** | One spare beyond requirement | any 1 unit failure | ~1/N extra | most stateless tiers, power, cooling |
| **N+2** | Two spares | 2 simultaneous failures (or 1 failure during maintenance) | ~2/N extra | higher-criticality, maintenance windows |
| **2N** | Full duplicate of the whole set | loss of an entire set/path | 100% extra | data center power, active-passive DR |
| **2N+1** | Full duplicate plus one | a set loss plus a unit failure | >100% | extreme criticality (some financial/medical) |

- **N+1** is the workhorse. With N=10 web servers and 1 spare, losing any one still meets capacity, at only 10% overhead. The risk: a *second* concurrent failure (e.g. during maintenance) drops you below N.
- **2N** duplicates everything — two independent power feeds, two full server fleets, two regions. Expensive but tolerates whole-path loss. Note: in **2N**, the spare must be *truly* independent or you've paid double for correlated copies.
- **N+1 vs 2N for power** is the canonical data-center example: N+1 UPS shares a bus (cheaper, one bus is a SPOF); 2N gives each load two independent feeds (no shared bus).

A crucial subtlety: **N+1 protects against one failure at a time and assumes you repair before the next.** If MTTR is long relative to failure rate, N+1 is insufficient — you want N+2 or a faster repair loop.

### 2.5 Active-active vs active-passive

This is *how the redundant copies relate during normal operation*.

> **Active-passive (a.k.a. active-standby, primary-backup, master-slave):** One copy (active/primary) serves all traffic; the other (passive/standby) is idle or read-only, kept in sync, ready to take over. Failover promotes the standby.
>
> **Active-active:** All copies serve traffic simultaneously. There is no idle spare; the loss of one copy just shifts its share to the survivors.

| Dimension | Active-passive | Active-active |
|---|---|---|
| Idle capacity | Standby usually idle (waste) | None — all serving |
| Failover | Promote standby; some RTO | Just stop routing to dead node; ~0 RTO |
| Complexity | Simpler; one writer | Harder; concurrent writers → conflicts |
| Data consistency | Easy (single writer) | Hard if multi-writer (conflict resolution) |
| Capacity utilization | ~50% (with 2N) | High (must keep headroom for `N` failure though) |
| Split-brain risk | Lower (passive doesn't write) | Higher (both may accept writes) |
| Read scaling | Standby can serve reads | Native |
| Cost efficiency | Pay for unused standby | Better — use what you pay for |

**Standby flavors** (affects RTO and cost):

- **Cold standby:** machines/data exist but services are off; must boot, restore data, warm caches. RTO = minutes to hours. Cheapest.
- **Warm standby:** services running at reduced scale, data continuously replicated; scale up + cut over on failover. RTO = minutes. Medium cost.
- **Hot standby:** full-scale running and in sync, just not taking traffic (or taking shadow traffic). RTO = seconds. Most expensive of the passive options.

> **Important capacity trap in active-active:** If two regions each run at 70% of their capacity and one dies, the survivor needs to absorb 140% — it can't. To survive losing one of *k* active sites without overload, each must run at most `(k-1)/k` of its capacity headroom-wise. For two active sites that means each ≤50% utilized for full survivability — so active-active does **not** automatically save cost over 2N unless you accept degraded service or have many sites.

### 2.6 Replication: the spine of stateful redundancy

Stateless tiers are easy to make redundant — just run more copies behind a balancer. The hard part is **state**: databases, queues, file stores. Keeping copies of state in sync is **replication**.

> **Synchronous replication:** A write is acknowledged to the client only after it is durably committed on the primary *and* at least one (or a quorum of) replica(s). Guarantees RPO = 0 for those replicas, at the cost of write latency = the slowest required replica's round trip. Across a region (sub-ms AZ links) this is feasible; across distant regions (tens of ms) it makes every write painfully slow and ties write availability to the remote site.
>
> **Asynchronous replication:** The primary acknowledges the client immediately after its own commit and ships changes to replicas in the background. Low write latency, write availability independent of replicas — but a primary failure loses any not-yet-shipped writes (RPO > 0, equal to the **replication lag** at the moment of failure).
>
> **Semi-synchronous:** A middle ground (e.g. MySQL `rpl_semi_sync`) where the primary waits for the replica to *receive* (not necessarily apply) the change before acking. Bounds data loss to in-flight-but-unapplied writes; still adds one network round trip.

> **Replication lag:** How far behind a replica is, in time or in bytes/log-position. The RPO of asynchronous replication equals the lag at failure time. Monitoring lag is mandatory (see §6).

> **Quorum:** A majority (or configured threshold) of replicas that must agree/acknowledge for an operation to count. With `N` replicas, write quorum `W` and read quorum `R`, choosing `W + R > N` guarantees a read sees the latest write (strong consistency); `W > N/2` prevents two conflicting writes both succeeding. Quorums are how you get consistency without a single fixed primary (used by Raft, Paxos, Dynamo-style stores, etcd, ZooKeeper).

### 2.7 Consistency and the CAP/PACELC reality

Across regions, the speed of light is ~5 ms per 1000 km one-way in fiber (slower than vacuum due to refractive index, plus routing overhead). Synchronous cross-region writes therefore cost tens of ms each. This forces the classic tradeoff.

> **CAP theorem:** In the presence of a network **P**artition (some replicas can't talk to others), a distributed system must choose between **C**onsistency (every read sees the latest write, i.e. linearizability) and **A**vailability (every request gets a non-error response). You cannot have both *during a partition*. You can't "give up P" — partitions happen whether you like it or not; you choose CP or AP behavior when one occurs.
>
> - **CP system:** During a partition, refuse writes (or reads) on the minority side to stay consistent. Examples: ZooKeeper, etcd, HBase, traditional RDBMS with synchronous quorum, MongoDB (with majority writes), Spanner.
> - **AP system:** During a partition, keep accepting writes everywhere and reconcile later (eventual consistency, conflict resolution). Examples: Cassandra/DynamoDB (tunable but default-leaning AP), Riak, DNS itself.

> **PACELC:** Extends CAP: *if* **P**artition, choose **A** vs **C**; **E**lse (normal operation), choose **L**atency vs **C**onsistency. This is more useful for everyday design because partitions are rare but the latency-vs-consistency tradeoff is paid on **every** request. A cross-region synchronous store is "PC/EC" (consistent always, slow). DynamoDB global tables are "PA/EL" (available and fast, eventually consistent).

> **Consistency models, plainly:**
> - **Strong / linearizable:** Reads always see the most recent committed write, as if there were one copy. Easiest to reason about, hardest/slowest across regions.
> - **Eventual:** If writes stop, all replicas converge to the same value *eventually*. Reads may be stale. Cheap and available.
> - **Causal:** Operations that are causally related are seen in order by everyone; unrelated ones may be seen in any order. A useful middle ground.
> - **Read-your-writes:** A client always sees its own prior writes (even if others lag). Often achieved by sticky routing to the primary or a session token.
> - **Monotonic reads:** A client never sees time go backwards (a value it already saw won't "un-update").

> **Conflict resolution (for multi-writer/AP):** When two regions accept conflicting writes during a partition, you must reconcile. Strategies: **LWW (Last-Writer-Wins)** by timestamp (simple, lossy — silently drops a write); **vector clocks** (detect concurrent writes, surface conflicts to the app); **CRDTs (Conflict-free Replicated Data Types)** — data structures (counters, sets, maps) mathematically designed to merge deterministically without coordination; **application-level merge** (custom logic, e.g. shopping-cart union).

### 2.8 Health checking and failure detection

You cannot fail over to what you cannot detect has failed.

> **Health check:** A periodic probe that reports whether an instance is healthy. Types: **liveness** (is the process up?), **readiness** (is it ready to serve traffic right now — caches warm, dependencies reachable?), and **deep/dependency** checks (can it actually reach its database?). The probe can be passive (observe real traffic errors) or active (synthetic request).

> **Heartbeat:** A periodic "I'm alive" signal a node sends. Missing *k* consecutive heartbeats marks it dead. Tuning `k` and the interval trades detection speed against false positives.

The fundamental detection dilemma: **you cannot distinguish a slow/partitioned node from a dead one.** This is the root of split-brain and of every false-failover incident. Detection is a *guess* under uncertainty; the whole design hardens that guess (timeouts, quorums, fencing) and limits the damage of a wrong guess.

### 2.9 Split-brain

> **Split-brain:** A failure mode where a network partition causes two (or more) nodes to each believe they are the sole active/primary and both accept writes. The copies diverge, and reconciling them later means lost or corrupted data. It is the single most dangerous outcome of naive failover.

The defenses (detailed in §3.6): **quorum** (only the majority side may act), **fencing / STONITH** ("Shoot The Other Node In The Head" — forcibly disable the old primary), **leases with generation/epoch numbers** (a token that invalidates the stale primary's writes), and **witness/tiebreaker nodes** in a third location.

---

## 3. How it works internally

This section is the heart of the chapter: the step-by-step machinery of detection, failover, routing, replication, and split-brain prevention.

### 3.1 The failover lifecycle (state machine)

A typical primary-backup failover proceeds through these states:

```
        ┌─────────────┐  heartbeats OK   ┌─────────────┐
        │   HEALTHY   │◀────────────────│  RECOVERING │
        │ (primary P, │                  │ (new primary │
        │  standby S) │                  │  promoted)  │
        └──────┬──────┘                  └──────▲──────┘
               │ missed heartbeats               │ standby caught up,
               │ / health-check fail             │ traffic switched
               ▼                                 │
        ┌─────────────┐  confirm via quorum ┌────┴────────┐
        │  SUSPECTED  │────────────────────▶│  FAILING_OVER│
        │ (P maybe    │   + fence old P     │ (promote S,  │
        │  dead)      │                     │  redirect)   │
        └──────┬──────┘                     └─────────────┘
               │ heartbeats resume (false alarm)
               ▼
        back to HEALTHY (no failover)
```

Step by step, what happens under the hood when a primary dies:

1. **Steady state.** Primary `P` serves writes; standby `S` applies the replication stream and stays in sync (sync or async). A failure detector (heartbeats, health checks, or a coordination service) watches `P`.
2. **Detection (MTTD).** `P` stops responding. The detector misses `k` heartbeats or sees `m` consecutive health-check failures over a window. Crucially, it waits long enough to reduce false positives but not so long as to blow RTO. Example: probe every 2 s, fail after 3 misses → detect in ~6 s.
3. **Suspicion & confirmation.** To avoid acting on a transient blip or a partition, the detector seeks **confirmation**: a quorum of observers must agree `P` is gone, or a coordination service (ZooKeeper/etcd) observes `P`'s ephemeral session expire. This is the anti-split-brain gate.
4. **Fencing the old primary.** Before promoting `S`, ensure `P` cannot still be writing. Mechanisms: revoke `P`'s lease (its writes will be rejected by storage), network-isolate it, power-cycle it (STONITH), or rely on `P` self-demoting when it can't renew its lease. **Skipping this step is how split-brain happens.**
5. **Promotion.** `S` is promoted to primary: it stops being read-only, replays any remaining replication log to reach the latest applied position, increments an **epoch/generation number**, and announces itself (writes its identity to the coordination store / updates the service registry).
6. **Re-pointing traffic.** Clients must now send writes to the new primary. Mechanisms (see §3.4): update a **virtual IP (VIP)**, change DNS, update a service-discovery record, or have a smart client/proxy query the coordination store. Each has different propagation latency (VIP/proxy: ms–seconds; DNS: seconds–minutes due to caching).
7. **Catch-up / re-replication.** A new standby is brought up (or old `P`, once it returns, is demoted, fenced of its stale state, and re-synced from the new primary as a fresh replica). The system returns to `N`-redundancy.
8. **Recovery of old primary (the dangerous moment).** When `P` comes back, it must **not** resume as primary. It checks the coordination store, sees a higher epoch, and demotes itself. Any writes it accepted while partitioned are rolled back or quarantined for reconciliation (with async replication, those writes may be permanently lost — that's the RPO cost).

### 3.2 Control flow vs data flow

- **Data flow** is the replication stream: write → primary's commit/WAL → ship log records → replica receives → applies → acks. Whether the client ack waits for the replica defines sync vs async.

> **WAL (Write-Ahead Log) / redo log / binlog / oplog:** A durable, append-only log of every change, written *before* the change is applied to data files. It is the source of truth for crash recovery and for replication — replicas simply replay the log. Postgres calls it WAL, MySQL calls it the binary log (binlog), MongoDB calls it the oplog, Kafka *is* essentially a replicated log. Understanding the log is understanding replication.

- **Control flow** is the failover orchestration: heartbeats, leader election, lease management, promotion decisions, traffic-switch commands. This usually runs through a **coordination service**.

> **Coordination service (ZooKeeper / etcd / Consul):** A small, strongly-consistent (CP) replicated key-value store used to hold cluster metadata, elect leaders, and store ephemeral session state. **ZooKeeper** (Apache, used by Kafka pre-KRaft, HBase, older Hadoop) uses the ZAB protocol; **etcd** (used by Kubernetes) uses Raft; **Consul** (HashiCorp) uses Raft plus service discovery and health checks. They provide primitives like **ephemeral nodes** (auto-deleted when a client's session dies — perfect for liveness) and **leader election** via atomic compare-and-set. They are themselves made redundant via an odd-sized quorum (3 or 5 nodes) so they survive a minority failure.

> **Raft / Paxos / ZAB:** Consensus algorithms that let a group of nodes agree on a value (e.g. "who is leader", "what's the next log entry") despite failures, as long as a majority is alive. **Raft** (the most teachable) elects a leader who appends to a replicated log; followers acknowledge; an entry is committed once a majority store it. A leader needs a majority to act, which is exactly why a partitioned minority cannot make progress — built-in split-brain protection. **Paxos** is the older, harder-to-implement ancestor; **ZAB** is ZooKeeper's variant. **Multi-Paxos** and Raft are practically equivalent for log replication.

### 3.3 Leader election under the hood (Raft sketch)

1. Nodes start as **followers** with randomized election timeouts (e.g. 150–300 ms).
2. If a follower hears no heartbeat from a leader before its timeout, it becomes a **candidate**, increments the **term** (a monotonically increasing epoch number), votes for itself, and requests votes from peers.
3. A node grants its vote to at most one candidate per term (first-come) — preventing two leaders in the same term.
4. A candidate that gets votes from a **majority** becomes **leader** and starts sending heartbeats (empty AppendEntries) to suppress new elections.
5. The randomized timeouts make split votes rare; a split vote just times out and retries with new random timeouts.
6. **Term numbers are the epoch/fencing mechanism:** any message carrying a stale (lower) term is rejected, so a returning old leader is instantly demoted. This is the canonical, correct way to prevent split-brain: *the minority cannot win an election (no majority) and the old leader's term is stale.*

### 3.4 How traffic actually gets redirected

This is where many failover designs are sound on the data side but slow or broken on the routing side. Options, ordered roughly by propagation speed:

> **Virtual IP (VIP) + ARP failover:** A floating IP address that "belongs" to the current primary on a LAN. On failover, the new primary sends a **gratuitous ARP** to tell switches "this IP is now at my MAC." Switchover is sub-second but only works within one L2 network (one AZ/data center). Tools: keepalived (VRRP), Pacemaker/Corosync.
>
> **VRRP (Virtual Router Redundancy Protocol):** A standard where routers/hosts share a virtual IP; a backup takes it over via priority election when the master stops advertising. Used by keepalived. Same L2 limitation.
>
> **Load balancer health-checked routing:** An L4/L7 load balancer (AWS NLB/ALB, Envoy, HAProxy, NGINX) probes backends and stops sending traffic to unhealthy ones automatically. Failover within the LB's pool is seconds. The LB itself must be redundant (multiple nodes / cross-zone).
>
> **Service discovery / smart client:** Clients (or a sidecar proxy) query a registry (Consul, Eureka, Kubernetes Endpoints, ZooKeeper) for the current healthy set / current primary, and re-resolve on failure. Propagation = registry update + client refresh interval (often seconds). Common in microservices and JVM ecosystems (Spring Cloud, Netflix Ribbon/Eureka historically).
>
> **DNS failover:** Change the DNS record to point at the healthy endpoint. Simple and works across regions, but slow and unreliable for fast failover because of **caching** (next).
>
> **Anycast:** Advertise the *same* IP from multiple locations via **BGP**; the network routes each client to the topologically nearest healthy site. Withdrawing the route from a failed site reroutes traffic in seconds without DNS changes. Used by global CDNs, DNS providers, and anycast-based load balancing (AWS Global Accelerator, Cloudflare).

> **BGP (Border Gateway Protocol):** The protocol that routes traffic between autonomous systems (networks) on the internet. Anycast relies on BGP: multiple sites announce the same prefix; routers pick the best path. Withdraw the announcement at a dead site and the rest of the internet converges to the others (seconds to a couple of minutes). Misconfigured BGP is also a famous cause of global outages.

#### Why DNS failover is slow: TTL and caching

> **TTL (Time To Live):** A DNS record carries a TTL telling resolvers how long they may cache it. A 300 s TTL means a client/resolver may keep using the old (failed) IP for up to 5 minutes after you change it. Worse, some resolvers and many client stacks (browsers, JVMs!) ignore or extend TTLs.

> **JVM DNS caching gotcha (version-specific):** The JVM caches DNS lookups in-process via `networkaddress.cache.ttl`. **Historically** (with a SecurityManager installed) the default was `-1` = **cache forever**, which silently breaks DNS failover for long-running Java services. Without a SecurityManager the default has been ~30 s (implementation-specific). **Always set this explicitly** (e.g. `-Dnetworkaddress.cache.ttl=5`) and prefer not relying on DNS for fast failover. Flag this as version- and configuration-specific — verify in your JDK.

Practical TTL strategy: keep failover-relevant records at a **low TTL (30–60 s)**; accept the extra query load; never use DNS alone when RTO must be < ~1 min; combine with health checks (Route 53 health-checked records, see §4).

### 3.5 Multi-AZ vs multi-region internals

**Multi-AZ (within a region):**

- AZs are close (sub-ms to ~2 ms). **Synchronous** replication is practical → RPO = 0 across AZs.
- Managed databases (AWS RDS Multi-AZ, Aurora, Cloud SQL HA) keep a synchronous standby in another AZ; on primary AZ failure they auto-promote in **~60–120 s** (RDS) or faster for Aurora (shared storage layer; typically ~30 s or less). Numbers are vendor- and version-specific.
- The control plane (instance launch, EBS attach, LB config) is **regional** — a regional control-plane outage can prevent failover even if your AZs are fine. This is a known, real risk (AWS has had regional API outages while data planes mostly kept serving).
- Stateless tiers: just run instances in ≥2 (ideally 3) AZs behind a cross-zone load balancer.

> **Why 3 AZs, not 2, for quorum systems:** A quorum needs a strict majority. With replicas in only 2 AZs, losing the AZ holding the majority loses the quorum. With 3 AZs (and an odd member count), any single AZ loss still leaves a majority. This is why etcd/ZooKeeper/Kafka are spread across 3 AZs.

**Multi-region (across regions):**

- Regions are far (tens–hundreds of ms). Synchronous cross-region writes are usually too slow → **asynchronous** replication → RPO > 0 (lag-dependent).
- Failover crosses control planes, DNS, and data-replication boundaries — much more involved. RTO is typically minutes, and RPO equals replication lag (seconds to minutes) unless you pay for synchronous global consistency (e.g. **Google Spanner**, **CockroachDB**, **AWS Aurora Global** with limited sync semantics; Spanner uses **TrueTime** atomic-clock/GPS bounds to order writes globally and still gives strong consistency — but pays commit-wait latency).
- You must decide: **active-passive** (one region serves writes; another is warm/hot DR) or **active-active** (both serve, needing conflict resolution or partitioned ownership).
- **Data residency / sovereignty** may legally pin some data to a region — a constraint that overrides pure availability design (e.g. GDPR for EU personal data).

### 3.6 Split-brain prevention, mechanically

Layered defenses, usually combined:

1. **Quorum / majority.** Only the side with a strict majority may elect a leader or accept writes. The minority side blocks. This is the strongest and most common defense (Raft, Paxos, etcd, ZooKeeper, MongoDB replica sets with majority write concern, Kafka with `min.insync.replicas`). The cost: the minority is *unavailable* during a partition (CP behavior).
2. **Witness / tiebreaker / arbiter.** An extra lightweight voter in a *third* location so the cluster size is odd and a 50/50 split can't happen. MongoDB's **arbiter**, SQL Server's **file-share witness**, Galera's **garbd**. The witness holds no data, just a vote.
3. **Fencing tokens (epoch/generation numbers).** Every leadership grants a monotonically increasing token. Storage/clients reject any write carrying a token lower than the highest they've seen. So even if an old leader (slow, not dead) tries to write, its stale token is rejected. This is the *correct* defense against the "I thought you were dead but you were just slow" problem and is described in Kleppmann's *Designing Data-Intensive Applications*.
4. **Leases with bounded clock assumptions.** A leader holds a time-bounded **lease**; it must stop acting before the lease expires unless renewed. The new leader waits out the old lease before acting. Relies on bounded clock drift (hence NTP/PTP discipline).

> **Lease:** A time-limited grant of authority (e.g. "you are primary until T+10 s"). The holder must self-demote if it cannot renew before expiry. Combined with fencing tokens, leases let a system safely assume an unreachable holder has stopped acting once its lease times out.

5. **STONITH / fencing the node.** Physically/network isolate or power-off the suspected-dead node so it *cannot* write, then promote. Common in Pacemaker/Corosync HA clusters with hardware power controllers (IPMI/iLO) or storage fencing.

> **Quorum with even numbers is dangerous:** 4 nodes split 2-2 → neither has majority → total unavailability. 6 nodes split 3-3 → same. Always use odd counts (3, 5, 7) for quorum systems, or add a witness to make it odd.

### 3.7 Cell-based architecture and shuffle sharding (blast-radius containment)

Redundancy keeps you up when a copy dies; **blast-radius containment** keeps a *single failure or poison input* from taking down *all* copies at once (correlated failure caused by shared fate).

> **Blast radius:** The scope of impact of a single failure. The goal is to make it small — one customer, one shard, one cell — not "everyone."

> **Cell-based architecture:** Partition the entire stack into independent, self-contained **cells**, each a full copy of the service (its own compute, its own data partition, its own dependencies) serving a subset of customers/traffic. A bug, overload, or bad deploy is contained to one cell; the blast radius is `1/number_of_cells`. Cells share *nothing* on the request path (or as little as possible). A thin **routing layer** maps each request to its cell. AWS uses this extensively internally; it's the modern answer to "how do we avoid one bad request taking down the whole region."

Mechanics:
- A **cell router** (kept deliberately simple and ultra-reliable) hashes/looks-up the customer → cell mapping.
- Deploys roll **cell by cell** (a bad deploy hits one cell, gets caught, never reaches the rest).
- Each cell has its own capacity ceiling, so one cell can't starve another.
- Cells are sized so the loss of one is an acceptable fraction of users.

> **Shuffle sharding:** A clever way to assign each customer to a *random subset* (a "shuffle shard") of the available workers/nodes, such that any two customers overlap on few or no nodes. If one customer sends a poison request that takes down their nodes, *other* customers, mapped to different (mostly non-overlapping) subsets, are unaffected. With `n` nodes and shards of size `k`, the number of distinct shards is C(n, k), which is huge, so collisions are rare. AWS Route 53 and other AWS services use shuffle sharding to isolate a noisy/abusive tenant to a tiny fraction of the fleet. It dramatically reduces the probability that any one bad actor affects any given other customer.

Example intuition: 8 nodes, shard size 2 → C(8,2)=28 possible pairs. Two random customers share *both* their nodes with probability 1/28 ≈ 3.6%, and share *at least one* node much of the time but rarely *all*. Scale up nodes and you make full overlap astronomically unlikely — so a single tenant's failure rarely takes down another tenant entirely.

### 3.8 Disaster recovery strategies (the AWS-popularized ladder)

Ordered by cost and by improving RTO/RPO:

| Strategy | What runs in DR | RTO | RPO | Cost |
|---|---|---|---|---|
| **Backup & Restore** | Nothing; restore from backups/snapshots on demand | Hours–days | Hours (last backup) | Lowest |
| **Pilot Light** | Core data replicated + minimal always-on (DB replica); rest off | 10s of min–hours | Minutes (async repl) | Low |
| **Warm Standby** | Scaled-down full stack always running; scale up on failover | Minutes | Seconds–minutes | Medium |
| **Hot Standby / Active-Active (Multi-site)** | Full-scale, serving (active-active) or ready (hot-passive) | Seconds | ~0–seconds | Highest |

> **Pilot light:** Like the always-lit pilot flame in a furnace: the critical core (your replicated database, AMIs/images, IaC) is kept warm and in sync, while the rest of the infrastructure is dormant and spun up only during a disaster. Cheaper than warm standby; slower RTO because you must launch the bulk of the stack.

> **Snapshot / point-in-time recovery (PITR):** A backup is a copy of data at a moment; **PITR** combines a base backup with the WAL/binlog so you can restore to *any* second in the retained window — essential for recovering from logical corruption (a bad migration, a `DELETE` without `WHERE`) that replication would faithfully copy to all replicas. Note: replication is **not** a backup — it propagates corruption instantly. You need *both*.

---

## 4. The complete toolkit

### 4.1 Cloud building blocks (vendor-specific; verify current behavior)

| Tool / service | Layer | Purpose | Key params / notes |
|---|---|---|---|
| **AWS Route 53** | DNS | Health-checked, latency/geo/failover routing; anycast DNS | Routing policies: `failover`, `latency`, `geolocation`, `geoproximity`, `weighted`, `multivalue`; health checks with interval (10s/30s) & failure threshold; `Evaluate Target Health` |
| **AWS Global Accelerator** | Anycast network | Static anycast IPs, instant cross-region failover at network layer | Endpoint groups per region, traffic dials, health checks; faster than DNS failover |
| **AWS ELB (ALB/NLB)** | LB | Cross-zone health-checked routing | `HealthCheckIntervalSeconds` (default 30/health, 30 for ALB), `HealthyThresholdCount` (default 5 ALB / 3 NLB), `UnhealthyThresholdCount`, cross-zone LB toggle |
| **AWS RDS Multi-AZ** | DB | Synchronous standby in another AZ; auto-failover | RPO ≈ 0 across AZs; failover ~60–120 s; uses DNS CNAME flip |
| **AWS Aurora (Global DB)** | DB | Shared storage HA + cross-region async replica | In-region failover often <30 s; Aurora Global cross-region RPO ~1 s typical, RTO ~1 min managed failover (verify) |
| **AWS DynamoDB Global Tables** | DB | Multi-region active-active, eventual consistency, LWW | Per-region writes; conflict = last writer wins by timestamp |
| **Azure equivalents** | — | Availability Zones, Traffic Manager (DNS), Front Door (anycast L7), Cosmos DB (multi-region, tunable consistency) | Cosmos offers 5 consistency levels: strong, bounded staleness, session, consistent-prefix, eventual |
| **GCP equivalents** | — | Zones/regions, Cloud Load Balancing (global anycast), Cloud DNS, Spanner (global strong via TrueTime), Cloud SQL HA | Spanner: external consistency at global scale via TrueTime commit-wait |
| **Cloudflare / Akamai / Fastly** | CDN/anycast | Global anycast, health-checked load balancing, DDoS edge | Pool/origin health checks, steering policies |

### 4.2 Self-managed / OSS HA tooling

| Tool | Role | Notes |
|---|---|---|
| **keepalived** | VIP failover via VRRP + health checks | L2 only; sub-second VIP move |
| **Pacemaker + Corosync** | Cluster resource manager + messaging/quorum | Supports STONITH/fencing; runs DBs, VIPs, services as managed resources |
| **HAProxy / NGINX / Envoy** | L4/L7 LB + health checks | Active/passive checks; Envoy adds outlier detection, retries, circuit breaking |
| **ZooKeeper** | Coordination, leader election, ephemeral liveness | ZAB; odd ensemble (3/5); used by Kafka (pre-KRaft), HBase |
| **etcd** | Coordination KV (Raft) | Kubernetes' brain; `--quota-backend-bytes`, lease TTLs |
| **Consul** | Service discovery + health + KV (Raft) | Multi-DC federation, prepared queries for failover |
| **Patroni** | PostgreSQL HA orchestration on etcd/Consul/ZK | Automated leader election, fencing, REST failover API |
| **Orchestrator / MySQL Group Replication / Galera** | MySQL HA | Group Replication = quorum-based; Galera = synchronous multi-master with certification-based conflict detection |
| **MongoDB replica set** | Built-in primary election + arbiter | `writeConcern: majority`, `readConcern: majority`, automatic election |
| **Kafka** | Replicated log | `replication.factor`, `min.insync.replicas`, `acks=all`, rack awareness; KRaft replaces ZK with Raft |

### 4.3 Key configuration knobs (with typical defaults — verify per version)

| Knob | System | What it does | Typical default |
|---|---|---|---|
| `acks` | Kafka producer | Durability: `0` fire-forget, `1` leader only, `all`/`-1` all in-sync replicas | `all` (since Kafka 3.0; was `1`) |
| `min.insync.replicas` | Kafka topic/broker | Min replicas that must ack with `acks=all` | `1` (set ≥2 for safety) |
| `replication.factor` | Kafka | Number of copies per partition | typically set to 3 |
| `writeConcern` | MongoDB | How many nodes must ack a write | `majority` (since 5.0) |
| `readConcern` | MongoDB | Consistency of reads | `local` default; use `majority`/`linearizable` for strong |
| `synchronous_commit` | PostgreSQL | sync vs async durability/replication | `on` (local fsync); `remote_apply`/`remote_write` for sync repl |
| `rpl_semi_sync_master_enabled` | MySQL | semi-sync replication | off by default |
| `wal_level` | PostgreSQL | logical/replica WAL detail | `replica` |
| `networkaddress.cache.ttl` | JVM | DNS cache TTL (sec) | ~30s (no SecurityManager); `-1`/forever historically with one — **set explicitly** |
| `connect/read timeouts` | HTTP/JDBC clients | how fast you detect a dead peer | often unset/infinite — **always set** |
| `election timeout` | etcd/Raft | follower→candidate trigger | etcd `--election-timeout` default 1000 ms |
| `heartbeat interval` | etcd/Raft | leader heartbeat | etcd `--heartbeat-interval` default 100 ms |
| TTL on failover records | Route 53/DNS | cache lifetime | set 30–60 s for failover |
| `HealthCheckIntervalSeconds` | AWS ELB | probe frequency | 30 s (ALB) |
| `deletionPolicy` / quorum size | etcd/ZK | ensemble size | 3 or 5 (odd) |

---

## 5. Code examples by use case

### 5.1 Resilient client: timeouts, retries with jitter, circuit breaker (Java, Resilience4j)

Failover starts at the client: a client that hangs forever on a dead node never *uses* the redundancy you built. This is the most common JVM mistake.

```java
// Maven: io.github.resilience4j:resilience4j-all, plus an HTTP client.
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import io.github.resilience4j.timelimiter.*;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.*;

public class ResilientCaller {

  // Circuit breaker: stop hammering a failing dependency; "open" fast, let it recover.
  private final CircuitBreaker breaker = CircuitBreaker.of("payments",
      CircuitBreakerConfig.custom()
          .failureRateThreshold(50)                       // open if >=50% of calls fail
          .slowCallRateThreshold(80)                      // treat slow calls as failures too
          .slowCallDurationThreshold(Duration.ofMillis(800))
          .waitDurationInOpenState(Duration.ofSeconds(5)) // cool-off before half-open probe
          .slidingWindowSize(50)                          // last 50 calls
          .permittedNumberOfCallsInHalfOpenState(5)
          .build());

  // Retry with EXPONENTIAL BACKOFF + JITTER to avoid retry storms / thundering herd.
  private final Retry retry = Retry.of("payments",
      RetryConfig.custom()
          .maxAttempts(3)
          .intervalFunction(IntervalFunction
              .ofExponentialRandomBackoff(Duration.ofMillis(100), 2.0, 0.5)) // jittered
          .retryExceptions(java.io.IOException.class, TimeoutException.class)
          .build());

  // Hard per-call timeout — never wait forever for a (maybe) dead replica.
  private final TimeLimiter timeLimiter = TimeLimiter.of(
      TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(1000)).build());

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofMillis(300)) // detect dead host fast at TCP connect
      .build();

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  public String charge(String body) throws Exception {
    Callable<String> call = () -> {
      HttpRequest req = HttpRequest.newBuilder(URI.create("https://payments.svc/charge"))
          .timeout(Duration.ofMillis(800))   // request-level read timeout
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 500) throw new java.io.IOException("upstream 5xx");
      return resp.body();
    };

    // Compose: timeout -> retry -> circuit breaker. Order matters: the breaker
    // sees the *final* outcome after retries; retries are bounded by the breaker state.
    Callable<String> decorated =
        Retry.decorateCallable(retry,
            CircuitBreaker.decorateCallable(breaker,
                () -> timeLimiter.executeFutureSupplier(
                    () -> CompletableFuture.supplyAsync(callUnchecked(call)))));
    return decorated.call();
  }

  private static java.util.function.Supplier<String> callUnchecked(Callable<String> c) {
    return () -> { try { return c.call(); } catch (Exception e) { throw new CompletionException(e); } };
  }
}
```

Why each piece matters: **connect/read timeouts** turn an invisible hang into a fast, retriable failure (so your LB/discovery can route elsewhere). **Jittered backoff** prevents every client retrying in lockstep and re-killing the recovering node (a retry storm — a classic self-inflicted correlated failure). The **circuit breaker** stops you from spending all your latency budget on a dependency that's clearly down, and gives it room to recover.

### 5.2 Health-checked readiness endpoint (Spring Boot) for LB/orchestrator failover

```java
// Spring Boot Actuator exposes /actuator/health; add a deep readiness check so the
// load balancer / Kubernetes only routes to instances that can actually serve.
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;

@Component("databaseReadiness")
public class DbReadinessIndicator implements HealthIndicator {
  private final DataSource ds;
  public DbReadinessIndicator(DataSource ds) { this.ds = ds; }

  @Override public Health health() {
    try (Connection c = ds.getConnection()) {
      // Cheap liveness query with a short timeout; if the DB primary failed over,
      // this fails fast and we report DOWN so traffic stops hitting this instance.
      boolean ok = c.isValid(1); // 1-second validation timeout
      return ok ? Health.up().withDetail("db", "reachable").build()
                : Health.down().withDetail("db", "validation-failed").build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
```

Kubernetes wiring (so the orchestrator restarts the dead and stops routing to the not-ready):

```yaml
# Liveness: is the process wedged? (restart if so)  Readiness: should it get traffic now?
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 20
  periodSeconds: 10
  failureThreshold: 3          # ~30s to declare dead -> restart
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  periodSeconds: 5
  failureThreshold: 2          # ~10s to pull from Service endpoints on dependency loss
# Spread replicas across AZs so one zone loss can't take all pods:
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: DoNotSchedule
    labelSelector: { matchLabels: { app: orders } }
```

Key point: **distinguish liveness from readiness.** A wedged process should be *restarted* (liveness). A healthy process that temporarily can't reach its DB should be *pulled from rotation* (readiness) but not killed — killing it loses warm caches and worsens the incident.

### 5.3 Leader election / single-active failover with ZooKeeper (Apache Curator)

Use case: an active-passive *background job* (e.g. a scheduler, a sequence generator) where exactly one instance must be active and another must take over on failure — with split-brain prevention via ZooKeeper's ephemeral nodes.

```java
// Maven: org.apache.curator:curator-recipes
import org.apache.curator.framework.*;
import org.apache.curator.framework.recipes.leader.*;
import org.apache.curator.retry.ExponentialBackoffRetry;

public class HaScheduler {
  public static void main(String[] args) throws Exception {
    CuratorFramework zk = CuratorFrameworkFactory.newClient(
        "zk1:2181,zk2:2181,zk3:2181",          // 3-node ensemble (quorum, odd)
        new ExponentialBackoffRetry(1000, 3));
    zk.start();

    LeaderSelector selector = new LeaderSelector(zk, "/scheduler/leader",
        new LeaderSelectorListenerAdapter() {
          @Override public void takeLeadership(CuratorFramework client) throws Exception {
            // We are now the ONLY leader. ZooKeeper guarantees this via an ephemeral,
            // session-bound lock: if our session dies (we crash or partition off),
            // ZK auto-deletes the lock and another instance is elected.
            System.out.println("Became leader. Running scheduled work...");
            try {
              while (!Thread.currentThread().isInterrupted()) {
                doWorkOneTick();                // do the active-only work
                Thread.sleep(1000);
                // IMPORTANT: before each externally-visible action, re-check we still
                // hold leadership; on session loss ZK calls stateChanged -> we must stop.
              }
            } finally {
              System.out.println("Lost leadership; demoting.");
            }
          }
        });
    selector.autoRequeue();   // re-enter the election after losing leadership
    selector.start();
    Thread.currentThread().join();
  }
  static void doWorkOneTick() { /* ... */ }
}
```

Subtlety (the split-brain trap): ZooKeeper's ephemeral node guarantees only-one-leader *as ZK sees it*, but a GC pause or network blip can make ZK expire your session while your JVM, frozen, still thinks it's leader. When it unfreezes it may perform one more action before noticing. The robust pattern: **carry a fencing token** (e.g. the ZK node's monotonically increasing `czxid`/version) on every write to the protected resource, and have the resource reject stale tokens — exactly as in §3.6. Do **not** rely on "I checked I was leader 1 ms ago."

### 5.4 Kafka producer configured for safe cross-AZ durability (no data loss on broker/AZ failure)

```java
import org.apache.kafka.clients.producer.*;
import java.util.Properties;

public class DurableProducer {
  public static Producer<String,String> create() {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092,b2:9092,b3:9092");
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   "org.apache.kafka.common.serialization.StringSerializer");
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");

    // Durability: require ALL in-sync replicas to ack. Combined with the broker/topic
    // setting min.insync.replicas=2 and replication.factor=3 spread across 3 AZs,
    // a write survives the loss of any one broker OR one whole AZ with RPO=0.
    p.put(ProducerConfig.ACKS_CONFIG, "all");

    // Exactly-once-ish: idempotent producer prevents duplicate writes on retry after
    // a transient failover (no double-charge on a retried message).
    p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5); // ordering preserved with idempotence
    p.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
    p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);       // bound total time incl. retries
    p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
    return new KafkaProducer<>(p);
  }
}
```

Topic + broker side (CLI), enforcing the quorum and rack/AZ spread:

```bash
# replication.factor=3 across 3 AZs; require 2 in-sync acks so one AZ loss is survivable.
kafka-topics.sh --create --topic orders --partitions 12 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --bootstrap-server b1:9092

# On each broker, set broker.rack to its AZ so Kafka spreads replicas across AZs:
#   broker.rack=us-east-1a    (and 1b, 1c on the others)
```

The combination `acks=all` + `min.insync.replicas=2` + `replication.factor=3` is the canonical "no single-broker, no single-AZ data loss" recipe. If you set `min.insync.replicas=1`, you silently lose the guarantee; if you set it equal to `replication.factor`, you lose *availability* when any one replica is down (can't satisfy quorum) — a tradeoff to choose deliberately.

### 5.5 Route 53 active-passive failover with health checks (Terraform / IaC)

Use case: region-level failover. Primary region serves; if its health check fails, DNS flips to the DR region.

```hcl
resource "aws_route53_health_check" "primary" {
  fqdn              = "primary.app.example.com"
  port              = 443
  type              = "HTTPS"
  resource_path     = "/actuator/health/readiness"
  request_interval  = 10          # fast checks (10s) for low RTO
  failure_threshold = 3           # ~30s to declare unhealthy
}

resource "aws_route53_record" "primary" {
  zone_id = var.zone_id
  name    = "app.example.com"
  type    = "A"
  set_identifier  = "primary"
  failover_routing_policy { type = "PRIMARY" }
  health_check_id = aws_route53_health_check.primary.id
  alias { name = aws_lb.primary.dns_name, zone_id = aws_lb.primary.zone_id, evaluate_target_health = true }
}

resource "aws_route53_record" "secondary" {
  zone_id = var.zone_id
  name    = "app.example.com"
  type    = "A"
  set_identifier = "secondary"
  failover_routing_policy { type = "SECONDARY" }
  alias { name = aws_lb.dr.dns_name, zone_id = aws_lb.dr.zone_id, evaluate_target_health = true }
}
```

Caveat reiterated: DNS-based failover RTO is bounded by **client/resolver caching**, not just the health-check window. Keep TTLs low and, when you need seconds-level RTO across regions, prefer **AWS Global Accelerator** (anycast) so the switch happens in the network, independent of DNS caches.

### 5.6 Application-level CRDT counter for active-active (conflict-free) merging

Use case: an active-active "likes" counter replicated across regions where both accept writes; you must merge without coordination and without losing increments. A naive `value++` with last-writer-wins loses concurrent increments. A **G-Counter CRDT** doesn't.

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Grow-only counter CRDT: each replica only increments its OWN slot; merge takes the
 *  element-wise max. Commutative/associative/idempotent => converges regardless of order
 *  or duplicate delivery. Perfect for active-active replication without coordination. */
public final class GCounter {
  private final String replicaId;
  private final Map<String, Long> counts = new ConcurrentHashMap<>();

  public GCounter(String replicaId) { this.replicaId = replicaId; }

  public void increment() { counts.merge(replicaId, 1L, Long::sum); } // only my slot

  public long value() { return counts.values().stream().mapToLong(Long::longValue).sum(); }

  /** Merge a counter received from another region. */
  public void merge(GCounter other) {
    other.counts.forEach((id, v) -> counts.merge(id, v, Long::max)); // element-wise max
  }

  public Map<String,Long> state() { return new HashMap<>(counts); } // ship this between regions
}
```

This is how AP/active-active stores avoid the LWW data-loss trap for certain types. The lesson: **multi-writer active-active is only as safe as your conflict-resolution model** — pick CRDTs/causal merge for commutative data, route by ownership (sharding) for the rest, and reserve true cross-region strong consistency (Spanner/CockroachDB) for data that truly needs it.

### 5.7 Bash: a minimal heartbeat + VIP failover sanity check (illustrative)

```bash
#!/usr/bin/env bash
# Illustrative active-passive heartbeat. In production use keepalived/Pacemaker — this
# shows the LOGIC: detect, confirm, fence, promote. Naive version omits real fencing!
PEER="10.0.0.5"; VIP="10.0.0.100"; MISS=0; THRESHOLD=3
while true; do
  if ping -c1 -W1 "$PEER" >/dev/null; then
    MISS=0
  else
    MISS=$((MISS+1))
    echo "missed heartbeat ($MISS/$THRESHOLD)"
    if [ "$MISS" -ge "$THRESHOLD" ]; then
      # DANGER: without fencing, if PEER is only PARTITIONED (not dead) we now have
      # split-brain. Real tools fence (STONITH) the peer before claiming the VIP.
      echo "promoting self: claiming VIP $VIP"
      ip addr add "$VIP/24" dev eth0
      arping -c3 -A -I eth0 "$VIP"   # gratuitous ARP so switches learn new MAC
      break
    fi
  fi
  sleep 1
done
```

The comments make the central lesson explicit: **detection alone is not safe failover** — you need confirmation + fencing, which is why you use battle-tested tools, not a ping loop.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Synchronous replication adds latency equal to the slowest required ack.** Within AZs (sub-ms) it's tolerable; across regions (tens of ms) it dominates. Decide per-data-class. Use semi-sync or async where RPO permits.
- **Capacity headroom is mandatory.** In N+1 you need 1 spare unit's worth of headroom; in active-active across `k` sites you need each site able to absorb a peer's share — budget for it or you'll cascade.
- **Cross-AZ data transfer costs money and adds latency** (typically ~1–2 ms and per-GB charges on AWS). Chatty cross-AZ traffic can dominate both latency and bill — co-locate request paths within an AZ where possible (zone-aware routing) while keeping *replicas* cross-AZ.
- **Health-check frequency vs load:** aggressive probes detect faster but add load and false positives. Tune interval × threshold to your RTO and false-positive tolerance.

### 6.2 Correctness & concurrency

- **Always use fencing tokens / epochs** for any single-active resource. "I checked I'm the leader" is not safe across GC pauses and partitions.
- **Use odd quorum sizes (3/5/7)** or a witness; never even.
- **`min.insync.replicas` / `writeConcern: majority`** must be set deliberately — defaults can silently give you weaker guarantees than you assume.
- **Idempotency everywhere on the retry/failover path.** Retries and failovers cause duplicate delivery; make operations idempotent (idempotency keys, dedup, idempotent producers) or you'll double-charge/double-ship.
- **Replication is not backup.** It propagates logical corruption (bad migrations, accidental deletes) to all replicas instantly. Keep independent, tested backups + PITR.

### 6.3 Memory & resource

- Standby/replica nodes consume the same memory/disk as the primary (hot standby) — budget for it; "passive" doesn't mean "free."
- Replication buffers and lag can balloon memory/disk on the primary if a replica falls behind (e.g. Postgres WAL accumulation when a replica is down, or Kafka under-replicated partitions). Monitor and cap (`wal_keep_size`, replication slots with bounds).

### 6.4 Security

- Replication and cross-region traffic must be **encrypted in transit** (TLS / VPN / private interconnect) — it's your raw data crossing networks.
- Failover endpoints expand attack surface; secure DR environments to the same standard as prod (they often get neglected — a real risk).
- **Don't share secrets/keys as a hidden SPOF**: if both regions depend on one KMS/region for decryption, a region outage breaks the "redundant" region too. Replicate keys (multi-region KMS keys) and certificates.

### 6.5 Cost

- 2N doubles infrastructure cost; warm/hot standby is a large recurring spend for capacity you hope never to use.
- Cross-AZ/region data transfer and replication egress are recurring line items, often underestimated.
- **Right-size DR to RTO/RPO per data class** — don't pay for hot-standby everything when backup-and-restore suffices for cold data. The DR ladder (§3.8) is a cost optimization tool.

### 6.6 Observability

Instrument the things that fail silently:

- **Replication lag** (seconds and bytes) per replica — alert before it becomes your RPO.
- **Quorum health / in-sync replica count** (Kafka under-replicated partitions, etcd/ZK ensemble health, MongoDB members in sync).
- **Health-check pass rate and flapping** — flapping health checks cause flapping failovers.
- **Failover events** (count, duration, success) — measure your *actual* RTO, don't assume it.
- **Leader changes / elections** — frequent elections signal instability or too-tight timeouts.
- **DNS TTL and propagation**, **circuit-breaker open rate**, **retry rate** (retry storms).
- **Cross-AZ/region latency and error rates** broken out by AZ/region so you can spot a degrading zone *before* it's declared dead.

### 6.7 Testability — you must test failover, regularly

- **Game days / chaos engineering:** deliberately kill instances, AZs, regions, and dependencies in production-like conditions and verify the system recovers within RTO/RPO. Tools: Chaos Monkey / **Simian Army / AWS Fault Injection Simulator (FIS)**, Gremlin, Chaos Mesh (k8s).
- **Test the *recovery* path, not just the failure** — restore from backup end-to-end on a schedule. An untested backup is Schrödinger's backup.
- **Test split-brain and partition scenarios** (e.g. with `tc`/`iptables`/Toxiproxy to inject latency and partitions), not just clean kills.
- **DR drills with real failover** (and failback!) at least quarterly; measure RTO/RPO and fix the gaps. Many orgs discover their DR plan doesn't work only during a real disaster.

> **Chaos engineering:** The discipline of injecting controlled failures into a system to find weaknesses before they cause outages, pioneered by Netflix (Chaos Monkey randomly kills production instances). The point is to make redundancy and failover *proven*, not *assumed*.

### 6.8 Production hardening checklist

- Spread across ≥3 AZs (quorum) and, for critical services, ≥2 regions.
- Independent failure domains *verified* — no shared DNS provider, KMS, config service, or deploy pipeline acting as hidden SPOF.
- Fencing/epochs on every single-active resource.
- Low DNS TTLs + anycast for fast cross-region failover; explicit JVM DNS TTL.
- Timeouts, jittered retries, circuit breakers, bulkheads on every remote call.
- Idempotent writes on retry/failover paths.
- Backups + PITR + tested restores, independent of replication.
- Cell-based isolation / shuffle sharding for large multi-tenant blast radii.
- Monitored replication lag, quorum, failover RTO/RPO, election rate.
- Regular game days and DR drills, including failback.
- Capacity headroom so survivors can absorb a failed peer's load.

### 6.9 Anti-patterns (avoid)

- **Two-node quorum** (split-brain inevitable on partition).
- **Failover without fencing** ("ping it, if no reply, take over").
- **Relying on DNS alone for fast RTO** (caching makes it slow/unreliable).
- **Treating replication as backup** (corruption propagates).
- **Active-active multi-writer with LWW on data that can't tolerate silent loss.**
- **No timeouts on remote calls** (a dead dependency hangs your whole pool, defeating redundancy).
- **Retry without backoff/jitter** (retry storms turn a blip into an outage — a self-inflicted correlated failure).
- **Untested DR** (the plan that only runs during the disaster).
- **Hidden shared dependency** across "independent" replicas/regions (the most common cause of correlated failure).
- **Running active-active sites at >50% (for two sites) utilization** — survivor overloads on failover.
- **Failing over automatically with too-aggressive detection** (flapping) — sometimes a brief human-confirmed delay beats thrashing.

---

## 7. Advanced topics & deep internals

### 7.1 The detection-vs-safety frontier (Phi Accrual & adaptive detectors)

Binary "missed k heartbeats = dead" is crude. **Phi Accrual Failure Detector** (used by Cassandra, Akka) outputs a *continuous suspicion level* (phi) based on the statistical distribution of past heartbeat inter-arrival times, so the threshold adapts to network conditions. Higher phi = more confident the node is dead. This reduces false positives on a jittery network without slowing detection on a clean one. Tradeoff: more complex; still cannot solve the fundamental slow-vs-dead ambiguity — only manage it probabilistically.

### 7.2 Generation/epoch numbers and the "fencing token" formalism

The deep reason fencing tokens work: they impose a **total order on leadership terms** and require every protected operation to present its term, with the resource enforcing monotonicity. This converts an *unsafe distributed agreement* problem ("are you really still the leader?") into a *local check* at the resource ("is this token ≥ the highest I've seen?"). Raft's `term`, ZooKeeper's `zxid`, etcd's `revision`, and Kafka's leader `epoch` are all instances. Without resource-side enforcement, leases and elections alone do *not* prevent a stalled-then-resumed old leader from corrupting data.

### 7.3 Lease safety and clock assumptions

Leases assume **bounded clock drift**: the old leader and new leader must agree, within a margin, on when the lease expired. This requires disciplined time sync (**NTP**, or **PTP** for sub-microsecond) and a safety margin larger than max expected drift + clock-read error. Google **Spanner** generalizes this with **TrueTime**: an API returning a time *interval* `[earliest, latest]` with bounded uncertainty (from GPS + atomic clocks); Spanner waits out the uncertainty interval before committing (**commit-wait**) to guarantee external consistency globally. The cost is added commit latency proportional to the uncertainty bound (single-digit ms). This is how you get strong consistency across regions — by paying the time-uncertainty tax explicitly.

> **NTP / PTP:** Network Time Protocol synchronizes clocks to ~ms over the internet, sub-ms on a LAN. Precision Time Protocol (PTP) achieves sub-microsecond with hardware timestamping. Many consistency and lease mechanisms quietly depend on bounded clock error; large drift breaks them.

### 7.4 Tunable consistency knobs and their meaning

- **Dynamo-style (`W`, `R`, `N`):** With `N` replicas, choose write quorum `W` and read quorum `R`. `W+R>N` → strong (read sees latest). `W=1` → fast writes, weak. `R=1` → fast reads, may be stale. `W=R=quorum` (`⌈(N+1)/2⌉`) → balanced strong consistency. Cassandra/DynamoDB expose this per query (`ONE`, `QUORUM`, `ALL`, `LOCAL_QUORUM`). **`LOCAL_QUORUM`** keeps the quorum within the local datacenter for latency while still replicating cross-DC asynchronously — a key multi-region pattern.
- **MongoDB:** `writeConcern: {w: "majority"}` + `readConcern: "majority"` ≈ strong; add `readConcern: "linearizable"` for the strongest (slower) reads.
- **Cosmos DB's 5 levels** (strong → bounded staleness → session → consistent-prefix → eventual) let you dial the PACELC latency/consistency tradeoff explicitly, even setting **bounded staleness** ("at most K versions or T seconds behind"), a useful middle ground.

### 7.5 Hinted handoff, read repair, anti-entropy (AP convergence internals)

How eventually-consistent stores heal:
- **Hinted handoff:** if a target replica is down during a write, a coordinator stores a "hint" and replays it when the replica returns — improving durability/availability without blocking the write.
- **Read repair:** on a read, if replicas disagree, return the newest and asynchronously fix the stale ones.
- **Anti-entropy / Merkle trees:** background process compares replicas using hash trees (Merkle trees) to efficiently find and repair divergent ranges. (A **Merkle tree** is a tree of hashes where each parent hashes its children, so two replicas can compare roots and drill down only into differing subtrees — comparing huge datasets cheaply.)

### 7.6 Failback — the forgotten half

Failover gets the attention; **failback** (returning to the original/primary after recovery) is where many incidents happen. Pitfalls: the recovered primary has stale data (must re-sync as a replica before any promotion), unfenced old primary resuming writes, and "split-brain on failback." Best practice: treat failback as a *planned, controlled* operation (re-sync fully, verify, then switch during low traffic), never an automatic snap-back. Some shops adopt **"failover and stay"** — once you fail over to DR, you operate there until a deliberate, scheduled failback, avoiding repeated risky flips.

### 7.7 Gray failures and the limits of binary health checks

> **Gray failure:** A component that is *partially* failing — slow, dropping a fraction of requests, high tail latency — but still passing simple health checks ("the process is up, returns 200 on /health"). These are insidious because failover never triggers, yet users suffer. Detection requires **differential observability** (the system's own health view vs. what clients actually experience) and SLI-based, request-outcome health signals — not just "is the port open."

Mitigations: outlier detection (Envoy ejects backends with elevated error/latency vs peers), client-side hedging (send a second request after a delay and take the first response), and load-balancing on real success/latency rather than naive round-robin.

### 7.8 Hedged requests and tail-tolerance

> **Hedged / tied requests:** To tame tail latency (the slow 99.9th percentile that redundancy *should* hide), send the request to one replica, and if no response within, say, the 95th-percentile latency, send a duplicate to another replica and take whichever returns first (then cancel the other). This converts redundancy into latency insurance, not just availability insurance. From Google's "The Tail at Scale." Requires idempotency and careful budget (don't double all load).

### 7.9 Static stability

> **Static stability:** Designing so that during a failure the system keeps working **without needing the control plane to take action**. Example: pre-provision DR capacity *before* the disaster so failover doesn't depend on the (possibly-also-failing) cloud control plane to launch instances. AWS preaches this because regional control-plane outages can coincide with the very failure you're trying to recover from. A statically stable design degrades to a known-good state using only the data plane.

### 7.10 Bulkheads and isolation

> **Bulkhead pattern:** Like a ship's watertight compartments — isolate resources (separate thread pools / connection pools / queues per dependency or per tenant) so one overloaded/failing dependency can't consume *all* resources and sink the whole service. A complement to circuit breakers and to cell-based architecture at the in-process level.

### 7.11 Multi-region write architectures, compared

- **Single-writer (active-passive writes):** one region owns writes, others read; simplest consistency; write latency for far users; failover promotes a region. (RDS/Aurora primary, Postgres primary.)
- **Partitioned ownership (active-active by sharding):** each region is primary for a subset of keys/customers (often by geography/data-residency); no write conflicts because each key has one home; cross-shard ops are harder. Common and robust.
- **Multi-master with conflict resolution:** any region accepts any write; needs CRDTs/LWW/app merge; risk of silent loss (LWW) or complexity. (DynamoDB global tables, Cosmos multi-region writes, Cassandra multi-DC.)
- **Globally strong (consensus per write):** Spanner/CockroachDB/YugabyteDB use Raft/Paxos per shard across regions; strong consistency everywhere; pays cross-region consensus latency on writes (commit-wait / quorum RTT). Choose when correctness > write latency.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Redundancy model selection

| Use when… | Choose |
|---|---|
| Stateless tier, cost-sensitive, fast repair | **N+1** |
| Need to tolerate a failure *during* maintenance | **N+2** |
| Whole-path/site loss must be survivable | **2N** (or multi-region) |
| Extreme criticality (finance/medical/safety) | **2N+1** + multi-region |
| You can run many sites and accept partial degradation | **Active-active** with headroom |
| Simpler ops, single writer, can tolerate seconds-RTO | **Active-passive** (warm/hot per RTO) |

### 8.2 Multi-AZ vs multi-region

| Dimension | Multi-AZ | Multi-region |
|---|---|---|
| Latency between sites | sub-ms–~2 ms | tens–hundreds ms |
| Sync replication / RPO=0 | Feasible | Usually impractical (Spanner-class excepted) |
| Survives | AZ/data-center loss | Region loss, disasters, regional control-plane outage |
| RTO | seconds–~2 min (managed) | minutes (active-passive) / ~0 (active-active) |
| Complexity | Low–medium | High |
| Cost | Moderate | High |
| Use when | Default for any prod service needing HA | SLA/regulatory/DR demands region survival |

**Rule of thumb:** *Multi-AZ is table stakes for production HA; multi-region is for disaster recovery and the highest SLAs.* Do multi-AZ first and well before reaching for multi-region.

### 8.3 Consistency vs availability (CAP/PACELC) per data class

| Data class | Priority | Choice |
|---|---|---|
| Money, inventory, identity | Correctness | CP / strong (quorum, single-writer or Spanner-class); accept latency & minority unavailability |
| User sessions, carts | Availability + read-your-writes | Sticky routing or session consistency; AP-leaning |
| Social counters, likes, feeds | Availability | AP + CRDTs/eventual |
| Analytics/logs | Throughput, can lose a bit | Async, AP, high RPO acceptable |

### 8.4 DR strategy selection (RTO/RPO vs cost)

| If you need… | Use |
|---|---|
| RTO hours, RPO hours, lowest cost | **Backup & restore** |
| RTO ~tens of min, RPO minutes, low cost | **Pilot light** |
| RTO minutes, RPO seconds–minutes | **Warm standby** |
| RTO seconds, RPO ~0, highest cost | **Hot standby / active-active** |

### 8.5 Failover routing mechanism selection

| Need | Mechanism |
|---|---|
| Fast (<1s) within one L2/AZ | **VIP + VRRP** (keepalived) |
| Within an LB pool, seconds | **Health-checked LB** |
| Microservices, dynamic topology | **Service discovery / smart client** |
| Cross-region, simple, tolerant of minutes | **DNS failover (low TTL)** |
| Cross-region, fast, cache-proof | **Anycast** (Global Accelerator / Cloudflare / BGP) |

### 8.6 When NOT to add redundancy

- The component is cheap to recreate and downtime is acceptable (a stateless batch job that can rerun).
- The added complexity introduces *more* failure modes than it removes (a flaky auto-failover that flaps is worse than a stable single node + paging a human).
- Cost outweighs the value of the marginal nine for that data/service.
- A hidden shared dependency means the "redundancy" is illusory — fix the dependency first.

---

## 9. Failure modes & debugging

### 9.1 Catalog of real failure modes

| Failure mode | Cause | Symptom | Mitigation / fix |
|---|---|---|---|
| **Split-brain** | Partition + failover without quorum/fencing | Two primaries, divergent data, conflicts | Quorum, fencing tokens, witness; reconcile/quarantine on detect |
| **Flapping failover** | Too-aggressive detection / transient blips | Rapid leader changes, thrash | Increase threshold/hysteresis, phi-accrual, confirm via quorum |
| **Retry storm / thundering herd** | Retries without jitter on recovery | Recovering node re-killed; cascading 503s | Exponential backoff + jitter, circuit breakers, load shedding |
| **Replication lag → data loss on failover** | Async replica behind at failover | RPO larger than expected; lost writes | Monitor/alert on lag; semi-sync; cap lag; promote most-caught-up replica |
| **DNS-cached stale endpoint** | High/ignored TTL, JVM cache | Clients keep hitting dead region | Low TTL, set JVM `networkaddress.cache.ttl`, use anycast |
| **Hidden shared dependency** | Same DNS/KMS/config across "regions" | Both regions fail together | Map dependencies; replicate keys/config; remove SPOF |
| **Control-plane outage blocks failover** | Regional API down; can't launch capacity | Failover stalls despite healthy DR data plane | Static stability: pre-provision DR capacity |
| **Gray failure** | Partial degradation passing health checks | Users see errors/latency, no failover fires | SLI-based health, outlier detection, differential observability |
| **Failback split-brain** | Old primary resumes after repair | Conflicts/corruption on return | Re-sync as replica first; fence; controlled failback |
| **Capacity collapse on failover** | Survivor can't absorb peer's load | Cascading overload after one site dies | Headroom (≤(k-1)/k util), autoscale, load shedding |
| **Quorum loss (even cluster / 2 AZs)** | Even members or majority in one AZ | Total unavailability on one failure | Odd counts, 3 AZs, witness |
| **Backup that won't restore** | Untested backups, corrupt/incomplete | DR fails at the worst time | Scheduled restore tests, PITR verification |
| **Idempotency violation on retry** | Non-idempotent op retried across failover | Double-charge / duplicate side effects | Idempotency keys, dedup, idempotent producers/transactions |

### 9.2 Diagnostic tools & commands

- **Replication lag:**
  - Postgres: `SELECT now() - pg_last_xact_replay_timestamp();` on the replica; `pg_stat_replication` on the primary (`sent_lsn`, `replay_lsn`).
  - MySQL: `SHOW REPLICA STATUS\G` → `Seconds_Behind_Source`.
  - MongoDB: `rs.printSecondaryReplicationInfo()` / `rs.status()`.
  - Kafka: `kafka-topics.sh --describe --under-replicated-partitions`; consumer lag via `kafka-consumer-groups.sh --describe`.
- **Quorum/cluster health:** etcd `etcdctl endpoint health` / `endpoint status --cluster`; ZK `echo stat | nc zk 2181` (four-letter words: `stat`, `mntr`, `ruok`); Consul `consul operator raft list-peers`; MongoDB `rs.status()`.
- **Network partition simulation/diagnosis:** `iptables`/`tc` (latency/loss injection), **Toxiproxy** (programmable network faults), `mtr`/`traceroute`, `ss`/`netstat`.
- **DNS:** `dig +trace app.example.com`, check TTL with `dig`; verify resolver caching; for JVM, confirm `-Dnetworkaddress.cache.ttl`.
- **JVM pauses (cause of false failover):** GC logs (`-Xlog:gc*`), check for long stop-the-world pauses that expire ZK/etcd leases; correlate election timestamps with GC pauses.
- **Failover timing:** record timestamps of detection, promotion, traffic-switch; compute *actual* RTO; compare to objective.
- **Anycast/BGP:** looking-glass tools, `bgp` route visibility from providers.

### 9.3 Real-world incident patterns (illustrative, publicly known classes)

- **Regional control-plane outages (AWS, multiple over the years):** the data plane mostly kept serving but the *control plane* (APIs to launch/modify resources) was down, so anything whose failover *required* control-plane actions (scaling DR up, attaching volumes, modifying LBs) stalled. Lesson: **static stability** — pre-provision; don't depend on the control plane during a disaster. (DNS/internal service dependencies have also been root causes of large AWS events.)
- **BGP misconfiguration / route leaks (multiple providers, e.g. large CDN and ISP events):** anycast and the internet route around healthy sites — but a bad BGP announcement can *withdraw* or *hijack* routes globally, taking down even multi-region setups. Lesson: BGP is a global shared dependency; guard it (RPKI, route filtering, staged changes).
- **DNS provider as SPOF (Dyn DDoS, 2016):** many "redundant" services shared one DNS provider; a DDoS on that provider took them all down together. Lesson: redundant DNS providers; DNS is a hidden shared dependency.
- **Cascading failure from retries (numerous):** a brief dependency blip triggered synchronized client retries that prevented recovery (retry storm). Lesson: jittered backoff, circuit breakers, load shedding.
- **Split-brain in self-managed clusters (classic with 2-node or no-fencing setups):** partition led to dual primaries and data divergence requiring manual reconciliation. Lesson: quorum + fencing, always.
- **Config/deploy as correlated failure (numerous, incl. large global outages):** a single bad config/policy push propagated to all regions simultaneously, defeating geographic redundancy. Lesson: **cell-by-cell / staged rollouts**, canaries, and treating the deploy pipeline as a blast-radius concern.

### 9.4 A debugging playbook for "did failover work?"

1. **Confirm the failure** (which domain: host/AZ/region/dependency?) via metrics and the health-check history.
2. **Check detection timing**: when did the detector mark it down? Compare to actual failure time (MTTD).
3. **Check for split-brain**: are there two leaders/primaries? Inspect coordination store (etcd/ZK), epoch/term numbers, and write paths.
4. **Check replication position at failover**: how far behind was the promoted replica? That's your realized RPO.
5. **Check traffic re-pointing**: did DNS/LB/discovery actually move clients? Inspect TTL, resolver caches, LB target health.
6. **Check survivor capacity**: did the remaining site saturate? CPU/latency/error rates, autoscaling activity.
7. **Check for retry storms**: spike in retry rate / 503s on the recovering node.
8. **Verify data integrity post-failover**: any lost or conflicting writes? Reconcile or restore from PITR if corruption.
9. **Plan controlled failback** — never auto-snap-back.

---

## 10. Interview drill

**Q1. Explain RTO vs RPO and how each constrains your architecture.**
*Model answer:* RTO is max acceptable downtime; RPO is max acceptable data loss (in time). RTO drives how hot your standby must be and how fast your traffic-switch (active-active/hot ⇒ low RTO; cold/restore ⇒ high). RPO drives replication mode: RPO=0 needs synchronous (or quorum) replication; RPO>0 allows async (RPO ≈ replication lag at failure). Both near-zero is the most expensive corner; set them per data class.
- *Probe: Why can't you trivially have RTO=0 and RPO=0 across regions?* Speed of light: synchronous cross-region writes add tens of ms and tie write availability to the remote site; you either pay that latency (Spanner-class) or accept RPO>0. RTO=0 needs already-running hot capacity with cache-proof routing (anycast), which is costly. CAP says during a partition you can't have both consistency (RPO=0) and availability.
- *Probe: How do you measure realized RPO after an incident?* Compare the promoted replica's last-applied log position/timestamp to the primary's last-committed at failure — the gap is the data lost.

**Q2. What is split-brain and how do you prevent it?**
*Model answer:* Two nodes both believe they're primary (usually after a partition) and both accept writes, diverging the data. Prevent with: quorum/majority (only the majority side acts), witness/arbiter to keep counts odd, fencing tokens/epoch numbers (resource rejects stale-token writes), leases with bounded clocks, and STONITH (physically isolate the suspected-dead node) before promoting.
- *Probe: Why isn't "check I'm still the leader before writing" enough?* A GC pause or partition can expire your lease while your process is frozen; on resume it may act on stale belief. Only a fencing token enforced at the *resource* is safe.
- *Probe: Why odd quorum sizes?* Even sizes can split 50/50 → no majority → total unavailability; odd guarantees one side has a strict majority.
- *Probe: What does a witness/arbiter store?* Only a vote (no data) — it exists to make the member count odd and break ties, ideally in a third failure domain.

**Q3. Multi-AZ vs multi-region — when do you need each?**
*Model answer:* Multi-AZ protects against data-center loss within a region; AZs are close enough for synchronous replication (RPO=0) and managed auto-failover in seconds–minutes; it's the baseline for prod HA. Multi-region protects against region loss/disasters/regional control-plane outages and is needed for the highest SLAs and regulatory DR; it forces async replication (RPO>0) or expensive global-strong stores, and higher complexity/cost. Do multi-AZ first.
- *Probe: Why ≥3 AZs for a database cluster?* Quorum needs a strict majority; with 2 AZs, losing the majority AZ loses quorum. 3 AZs survive any single-AZ loss.
- *Probe: A multi-region setup, but both regions went down together — likely cause?* A hidden shared dependency (one DNS provider, one KMS/region for keys, one config/deploy pipeline) — correlated failure defeating geographic redundancy.

**Q4. Active-active vs active-passive — tradeoffs?**
*Model answer:* Active-passive: one writer, simpler consistency, idle standby (cost), failover has RTO and promotion. Active-active: all serve (better utilization, ~0 RTO on node loss), but concurrent writers ⇒ conflict resolution and higher split-brain risk; and you must keep headroom so survivors absorb the load. Choose active-passive for simplicity/single-writer correctness; active-active for low RTO and when you can solve conflicts (CRDTs/partitioned ownership) and afford headroom.
- *Probe (senior-signal): "Active-active saves money because nothing is idle" — true?* Not necessarily: to survive losing one of two sites, each must run ≤50% utilized, so you still pay ~2x for full survivability — same as 2N — unless you have many sites or accept degraded service on failover.

**Q5. How does DNS-based failover work and what are its limits?**
*Model answer:* You point a DNS record at the healthy endpoint and use health checks (e.g. Route 53) to flip on failure. Limits: TTL caching means clients keep using the old IP for up to the TTL (and some resolvers/JVMs ignore TTLs), so RTO is bounded by caching, not the health check. Use low TTLs (30–60 s), set JVM DNS TTL explicitly, and for seconds-level RTO prefer anycast (Global Accelerator/Cloudflare) which switches in the network.
- *Probe: What's the JVM-specific gotcha?* `networkaddress.cache.ttl` historically defaulted to cache-forever with a SecurityManager, silently breaking failover in long-running JVMs — set it explicitly.
- *Probe: How does anycast differ?* Same IP announced via BGP from multiple sites; withdrawing the route at a dead site reroutes clients to the nearest healthy site in seconds, immune to DNS caches.

**Q6. Explain cell-based architecture and shuffle sharding.**
*Model answer:* Cell-based: partition the whole stack into independent self-contained cells, each serving a subset of customers with its own compute/data/dependencies and a thin router; a bug/overload/bad deploy is contained to one cell (blast radius = 1/cells), and deploys roll cell-by-cell. Shuffle sharding: assign each customer to a random subset of workers so any two customers rarely fully overlap; a poison-pill from one customer takes down only their subset, sparing others. Both contain *correlated* failures that plain redundancy doesn't.
- *Probe: Why does shuffle sharding work statistically?* With n nodes and shard size k there are C(n,k) shards; the chance two customers share *all* their nodes is tiny, so one tenant's failure rarely fully impacts another.
- *Probe (senior-signal): How is blast-radius containment different from redundancy?* Redundancy keeps you up when a *copy* dies (uncorrelated). Containment limits damage when a *shared cause* (bad input, bad deploy) would otherwise hit all copies at once — it attacks correlation, not component failure.

**Q7. How do you choose a consistency model for a multi-region datastore?**
*Model answer:* By data class via PACELC. Money/inventory/identity ⇒ strong/CP (quorum or single-writer or Spanner-class), accept latency and minority unavailability. Sessions/carts ⇒ read-your-writes/session consistency. Counters/feeds ⇒ eventual + CRDTs. Analytics ⇒ async/high-RPO. PACELC matters because the latency-vs-consistency tradeoff is paid on every request (the "else" branch), not just during rare partitions.
- *Probe: What's `LOCAL_QUORUM` and why use it?* A Cassandra-style read/write that achieves quorum *within the local datacenter* for latency while replicating cross-DC asynchronously — strong-ish locally, low cross-region latency.
- *Probe: Why is LWW dangerous?* It silently drops one of two concurrent writes by timestamp — fine for idempotent overwrites, data-losing for accumulating data (use CRDTs/causal merge there).

**Q8. (Senior-signal) You're asked to make a single-region service "highly available." Walk through your plan and tradeoffs.**
*Model answer:* (1) Find SPOFs (LB, DB, config, DNS, deploy). (2) Make stateless tiers multi-AZ (≥3) behind cross-zone health-checked LBs. (3) Make the DB multi-AZ with synchronous standby (RPO=0) and automated, fenced failover; ≥3 AZs for any quorum system. (4) Add client resilience: timeouts, jittered retries, circuit breakers, idempotency. (5) Set RTO/RPO per data class; add backups+PITR (replication ≠ backup). (6) Decide if multi-region/DR is required by SLA/regulation; if so, pick a DR tier (pilot light/warm/hot) and routing (anycast). (7) Contain blast radius (cells, staged deploys, canaries). (8) Observability for lag/quorum/failover RTO. (9) Prove it with game days and DR drills. Tradeoffs: each step adds cost and complexity; over-aggressive auto-failover can flap; active-active needs headroom and conflict handling. Sequence by cost/benefit: multi-AZ + client resilience + backups first; multi-region only if justified.
- *Probe: Where would you deliberately NOT add redundancy?* Cheap-to-recreate stateless batch work, or where auto-failover adds more failure modes than it removes — sometimes a stable single node + paging beats a flaky failover.
- *Probe: What's the most common way this plan still fails?* A hidden shared dependency making the "independent" replicas correlated, and untested DR/failback.

**Q9. (Senior-signal) Your async cross-region replica is 30s behind and the primary region just died. Walk through the decision.**
*Model answer:* Realized RPO ≈ 30s of lost writes if I promote now. Decide by data criticality and the RTO/RPO contract: if RPO budget ≥30s, promote the most-caught-up replica, fence the dead primary's identity (higher epoch), re-point traffic (anycast/low-TTL DNS), and after recovery treat the old primary as a fresh replica (never auto-snap-back). If RPO budget <30s for critical data, I may wait briefly for more drain if reachable, or accept the loss and reconcile the quarantined writes when the old primary returns. Communicate the data-loss window to stakeholders; record actual RTO/RPO; plan controlled failback.
- *Probe: How do you avoid making it worse?* Don't let two regions both accept writes (fence + epoch); don't trigger a retry storm on the survivor (backoff, load shedding); ensure survivor has capacity.
- *Probe: How would Spanner/CockroachDB change this?* They keep a per-shard consensus quorum across regions, so a region loss leaves a majority that's already current — RPO≈0 — at the cost of cross-region write latency you paid all along.

**Q10. What is static stability and why does it matter for failover?**
*Model answer:* Designing so the system continues operating during a failure **without needing the control plane to act** — e.g. pre-provision DR capacity so failover doesn't depend on launching new instances. It matters because the control plane (cloud APIs) can be down during the very event you're recovering from (regional control-plane outages are real), and a failover that depends on it will stall. Statically stable systems degrade to a known-good state using only the data plane.
- *Probe: Give a concrete example.* Pre-warm DR fleet at full size rather than relying on autoscaling-up during the disaster; pre-create LB targets and DNS so no API call is needed at failover.

**Q11. How do you detect failure without causing false failovers?**
*Model answer:* Combine interval × threshold tuning (probe every Xs, fail after k misses) with *confirmation* (quorum of observers, or coordination-service session expiry) and adaptive detectors (phi accrual) to handle jitter. Distinguish liveness (restart) from readiness (pull traffic). Beware gray failures that pass naive checks — use SLI/outcome-based health and outlier detection. The fundamental limit: you can't distinguish slow from dead, so add fencing so a wrong guess is *safe*, not just rare.
- *Probe: Why can a GC pause cause a false failover?* A long stop-the-world pause stops heartbeats/lease renewals; peers expire the node and fail over even though it's alive — correlate election timing with GC logs to confirm.

**Q12. Why isn't replication a backup?**
*Model answer:* Replication copies *all* changes — including logical corruption (a bad migration, an accidental `DELETE`) — to every replica instantly, so it offers no protection against logical errors or ransomware. Backups + point-in-time recovery let you restore to a moment *before* the corruption. You need both: replication for availability, backups for recoverability.
- *Probe: What's PITR?* Base backup + retained WAL/binlog so you can restore to any second in the window — essential for undoing logical corruption.
- *Probe: How do you ensure a backup actually works?* Scheduled, automated restore tests end-to-end; an untested backup is unverified.

---

## 11. Glossary

- **Active-active:** All redundant copies serve traffic simultaneously.
- **Active-passive (active-standby):** One copy serves; another stands ready to take over.
- **Anti-entropy:** Background process (often using Merkle trees) that reconciles divergent replicas.
- **Anycast:** Same IP announced from multiple locations via BGP; the network routes clients to the nearest healthy site.
- **Arbiter / witness / tiebreaker:** A voting-only member (no data) that keeps quorum counts odd and breaks ties.
- **Availability Zone (AZ):** A physically isolated data center within a cloud region, an independent failure domain with low-latency links to siblings.
- **BGP (Border Gateway Protocol):** The protocol routing traffic between networks on the internet; the basis of anycast.
- **Blast radius:** The scope of impact of a single failure; the goal is to minimize it.
- **Bulkhead:** Isolating resources (thread/connection pools) so one failing dependency can't exhaust everything.
- **CAP theorem:** During a network partition, you must choose Consistency or Availability.
- **Causal consistency:** Causally related operations are seen in order by all replicas.
- **Cell-based architecture:** Partitioning the whole stack into independent self-contained cells to contain blast radius.
- **Circuit breaker:** A client pattern that stops calling a failing dependency to let it recover and to protect the caller.
- **Cold/warm/hot standby:** Increasing readiness levels of a passive replica (off / scaled-down running / full running).
- **Consensus (Raft/Paxos/ZAB):** Algorithms letting a group agree on values despite failures, requiring a majority.
- **Consistency (strong/linearizable):** Reads always reflect the latest committed write.
- **Coordination service (ZooKeeper/etcd/Consul):** A small CP store for cluster metadata, leader election, and liveness.
- **Correlated failure:** Multiple components failing together due to a shared cause.
- **CRDT (Conflict-free Replicated Data Type):** Data structures that merge deterministically without coordination.
- **Disaster Recovery (DR):** Strategies/processes to restore service after a major (often region-scale) failure.
- **DNS / TTL:** Name resolution; TTL is how long a record may be cached, bounding DNS-failover speed.
- **Epoch / generation / term number:** Monotonic leadership counter used as a fencing token.
- **Error budget:** `1 - SLO`; the allowable unreliability you spend on risk.
- **Eventual consistency:** Replicas converge to the same value eventually if writes stop.
- **Failback:** Returning to the original site after recovery; must be controlled to avoid split-brain.
- **Failover:** Switching from a failed component to a healthy spare.
- **Failure domain:** The set of things that fail together when one underlying thing fails.
- **Fencing / STONITH:** Forcibly disabling a suspected-dead node (or rejecting its stale writes) before/while promoting a new one.
- **Fencing token:** A monotonically increasing token presented on writes; resources reject stale tokens to prevent split-brain.
- **Gray failure:** Partial degradation that passes naive health checks while users suffer.
- **Health check (liveness/readiness/deep):** Probes determining whether to restart or route to an instance.
- **Heartbeat:** Periodic "I'm alive" signal; missing k of them marks a node dead.
- **Hedged request:** Sending a duplicate request to another replica to cut tail latency.
- **Hinted handoff:** Storing a write hint for a down replica and replaying it on return.
- **Idempotency:** An operation safe to apply more than once; essential on retry/failover paths.
- **Lease:** A time-limited grant of authority that must be renewed or relinquished.
- **Linearizable:** The strongest single-object consistency; behaves as if one copy.
- **Load balancer:** Distributes traffic across backends; uses health checks to route around failures.
- **LWW (Last-Writer-Wins):** Conflict resolution that keeps the newest write by timestamp (lossy).
- **Merkle tree:** A hash tree enabling efficient comparison of large datasets between replicas.
- **MTBF / MTTR / MTTD:** Mean time between failures / to recover / to detect.
- **Multi-AZ / Multi-region:** Redundancy across availability zones / across regions.
- **MVCC (Multi-Version Concurrency Control):** A DB technique keeping multiple versions of data so readers don't block writers; relevant to snapshot reads/PITR. (A version is a snapshot of a row at a point in time.)
- **N / N+1 / N+2 / 2N / 2N+1:** Redundancy models by amount of spare capacity.
- **NTP / PTP:** Time-synchronization protocols; lease/consistency safety depends on bounded clock drift.
- **PACELC:** Extension of CAP: if Partition choose A/C, Else choose Latency/Consistency.
- **Partition (network):** A break in connectivity isolating some replicas from others.
- **Phi Accrual detector:** Adaptive failure detector outputting a continuous suspicion level.
- **Pilot light:** DR strategy keeping only the critical core warm and spinning up the rest on disaster.
- **PITR (Point-in-Time Recovery):** Restoring to any moment using a base backup + logs.
- **Quorum:** A majority/threshold of replicas required to agree for an operation to count.
- **Region:** A large geographic area containing multiple AZs.
- **Replication (sync/async/semi-sync):** Keeping copies of state in sync; the mode sets the RPO and latency.
- **Replication lag:** How far a replica trails the primary; equals async RPO at failure.
- **Retry storm / thundering herd:** Synchronized retries overwhelming a recovering service.
- **RPO (Recovery Point Objective):** Max acceptable data loss (in time).
- **RTO (Recovery Time Objective):** Max acceptable downtime to restore service.
- **Read repair:** Fixing stale replicas detected during a read.
- **Shuffle sharding:** Assigning customers to random subsets of nodes to isolate noisy/poison tenants.
- **SLA / SLO / SLI:** Agreement / objective / indicator for service levels.
- **Split-brain:** Two nodes both acting as primary, diverging data.
- **SPOF (Single Point of Failure):** A component whose failure alone takes the system down.
- **Static stability:** Operating through failures without needing control-plane actions.
- **STONITH:** "Shoot The Other Node In The Head" — power/network fencing of a suspected-dead node.
- **Synchronous/asynchronous commit:** Whether a write waits for replica acknowledgment before returning.
- **TrueTime:** Google Spanner's bounded-uncertainty clock API enabling global external consistency via commit-wait.
- **VIP / VRRP:** Floating virtual IP and the protocol used to move it between hosts on failover (L2 only).
- **WAL / binlog / oplog:** Write-ahead/redo logs that drive crash recovery and replication.
- **Witness:** See arbiter.
- **W/R/N quorum:** Write/read/replica counts; `W+R>N` gives strong reads.
- **ZooKeeper / etcd / Consul:** Coordination services (see coordination service).

---

## 12. Cheat-sheet & self-test

### Dense recap (one screen)

**Nines → downtime/yr:** 99%=3.65d · 99.9%=8.77h · 99.99%=52.6min · 99.999%=5.26min.
**Availability math:** parallel independent copies → `1-(1-a)^n`; the word *independent* is everything (correlation kills it).
**RTO** = downtime budget (drives standby heat + routing speed). **RPO** = data-loss budget (drives sync vs async). RPO=0 ⇒ sync/quorum; RTO≈0 ⇒ hot/active-active + anycast. Both≈0 = priciest corner.
**Redundancy models:** N+1 (1 spare, workhorse) · N+2 (survive failure during maintenance) · 2N (full duplicate) · 2N+1 (extreme).
**Active-passive** = simple, single writer, idle standby. **Active-active** = all serve, conflicts to solve, need ≤(k-1)/k utilization headroom (2 sites ⇒ ≤50% each).
**Multi-AZ** = sub-ms, sync, RPO≈0, baseline HA, use ≥3 AZs for quorum. **Multi-region** = tens-of-ms, async, RPO>0, for DR/top SLAs/regulation.
**CAP:** partition ⇒ choose C or A. **PACELC:** else ⇒ Latency vs Consistency (paid every request).
**Split-brain defenses:** quorum (odd counts!) + witness + fencing tokens/epochs (enforced at resource) + leases + STONITH.
**Routing failover speed:** VIP/VRRP (sub-s, L2) < LB health-check (s) < service discovery (s) < DNS (TTL-bound, slow) ; **anycast** = fast, cache-proof, cross-region.
**JVM gotcha:** set `-Dnetworkaddress.cache.ttl` (historically cache-forever with SecurityManager).
**Kafka no-loss recipe:** `acks=all` + `min.insync.replicas=2` + `replication.factor=3` across 3 AZs + idempotent producer.
**DR ladder (cheap→pricey, RTO better→):** Backup&Restore (hrs) → Pilot Light (10s min) → Warm Standby (min) → Hot/Active-active (s).
**Blast-radius containment:** cells (1/cells impact, cell-by-cell deploys) + shuffle sharding (random node subsets isolate poison tenants) — attacks *correlation*, not component failure.
**Client resilience:** timeouts + jittered backoff + circuit breaker + bulkheads + idempotency (else retry storms / hung pools).
**Hard truths:** replication ≠ backup (it copies corruption); can't tell slow from dead (so fence); untested DR/failback fails; hidden shared dependency = correlated failure; survivor needs capacity headroom; static stability (don't need control plane to fail over).
**Diagnose:** repl lag (`pg_stat_replication` / `Seconds_Behind_Source` / `rs.printSecondaryReplicationInfo` / under-replicated partitions); quorum (`etcdctl endpoint health`, ZK `mntr`, `rs.status`); partitions (Toxiproxy/iptables/tc); GC pauses → false failover; record actual RTO/RPO.

### Self-test (no answers — for active recall)

1. You have replicas in two AZs of a 3-node etcd cluster (2 in AZ-A, 1 in AZ-B). AZ-A fails. What happens, and how would you fix the design?
2. A teammate proposes active-active across two regions "to fully use both and save money." What capacity and consistency objections do you raise, and what must be true for it to work?
3. Your async replica is 45s behind when the primary region dies, and the data is financial. Walk through your failover decision, the realized RPO, and how you prevent split-brain on the old primary's return.
4. A long JVM GC pause keeps triggering failovers of a healthy leader. Explain the mechanism and three independent mitigations.
5. Design DNS/routing for a cross-region service that must fail over within 10 seconds. Why is plain Route 53 failover insufficient, and what do you use instead — and what JVM setting must you not forget?
6. Explain why shuffle sharding limits a single abusive tenant's blast radius, with the combinatorial intuition, and contrast it with plain N+1 redundancy.
7. Pick three data classes in a typical e-commerce system and assign each an RPO, a consistency model, and a replication strategy — justify via PACELC.
8. Your "redundant" multi-region service went fully down in one incident. List five plausible hidden shared dependencies and how you'd detect each.

---

*End of chapter.*

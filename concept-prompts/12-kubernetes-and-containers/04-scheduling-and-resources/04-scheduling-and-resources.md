# Kubernetes Scheduling & Resources

> A definitive engineering-handbook chapter for senior backend developers (Java/JVM-centric) who want to master how Kubernetes decides *where* a Pod runs and *how much* compute it gets — from first principles to deep internals, production debugging, and interview-grade depth.

---

## 1. Overview & where it fits

### What it is

Kubernetes is a **container orchestrator**: you tell it *what* you want running (declaratively, as objects in its database) and it continuously works to make reality match that desired state. Two of the most consequential decisions it makes on your behalf are:

1. **Scheduling** — *which node* (machine) should each Pod run on?
2. **Resource management** — *how much CPU and memory* (and other resources) does each container get, and what happens when a node runs short?

These two subjects are deeply intertwined. The scheduler's primary job is essentially a **bin-packing problem**: it has a set of Pods that each declare how much CPU/memory they need (their *requests*), and a set of nodes that each have finite capacity. It must place Pods onto nodes such that no node is overcommitted on requested resources, while also respecting a large set of constraints (affinity, taints, spreading rules) and optimizing for goals (balance, locality, cost). Once a Pod is placed, the node's **kubelet** and the Linux kernel's **cgroup** machinery enforce the resource boundaries: throttling CPU and killing processes that exceed memory.

> **Beginner aside — Pod:** The smallest deployable unit in Kubernetes. A Pod is one or more containers that share a network namespace (same IP/port space) and can share storage volumes. You almost never run a single container alone; you run a Pod that wraps it. Resources are requested *per container*, but scheduling and eviction decisions operate on the *Pod* as a whole (the sum of its containers).

> **Beginner aside — Node:** A worker machine (physical or virtual) in the cluster that runs Pods. Each node runs a **kubelet** (the node agent that talks to the control plane and manages containers) and a **container runtime** (containerd, CRI-O, etc.).

> **Beginner aside — control plane:** The "brain" of the cluster. It includes the **API server** (the front door — everything goes through it), **etcd** (the database storing all cluster state), the **scheduler** (assigns Pods to nodes), and the **controller manager** (runs reconciliation loops). Nodes are the "muscle."

### The problem it solves

Without an orchestrator, you would manually decide which server runs which process, eyeball CPU/RAM headroom, and hand-pack workloads. That doesn't scale past a handful of machines and breaks the moment a node dies. Kubernetes scheduling solves:

- **Placement at scale**: deciding placement for thousands of Pods across hundreds of nodes, continuously, as Pods come and go.
- **Resource isolation & fairness**: ensuring one noisy workload can't starve another, via kernel enforcement of CPU/memory boundaries.
- **Resilience**: spreading replicas so a single node/rack/zone failure doesn't take down a whole service.
- **Prioritization**: when resources are scarce, running important workloads and evicting less-important ones (preemption, QoS-based eviction).
- **Cost efficiency**: packing workloads densely enough to not waste capacity, while leaving enough headroom to avoid instability.

### When you reach for it

You're *always* using the scheduler the moment you create a Pod — it's not optional. The deeper machinery (affinity, taints, priority classes, QoS tuning, right-sizing) is what you reach for when:

- A latency-sensitive service is getting CPU-throttled.
- A JVM app keeps getting OOMKilled even though "it has enough memory."
- You need replicas spread across availability zones for HA.
- You have heterogeneous nodes (GPU nodes, ARM nodes, spot/preemptible nodes) and need to steer workloads.
- Batch jobs are crowding out user-facing services and you need preemption.
- Your cloud bill is dominated by half-empty nodes (poor bin-packing / over-requesting).

### One-paragraph mental model

Think of the scheduler as a **matchmaker** running a two-phase tournament for each pending Pod: first it **filters** out every node that *can't* host the Pod (not enough free requested capacity, wrong labels, untolerated taint, anti-affinity violation), then it **scores** the survivors and **binds** the Pod to the winner by writing `nodeName` into the Pod object. After binding, the **kubelet** on that node admits the Pod, and the Linux kernel's **cgroups** become the bouncer: CPU `requests` set scheduling *weights* (CPU shares), CPU `limits` set a hard *throttle* (CFS quota), memory `requests` inform scheduling, and memory `limits` set a hard *kill threshold* (OOMKill). The Pod's mix of requests-vs-limits determines its **QoS class**, which decides the order in which it gets evicted when a node runs out of memory. Everything is "requests for scheduling, limits for enforcement."

---

## 2. Foundations from first principles

### 2.1 The two resource quantities every container can declare

Every container in a Pod can declare, independently, for each resource (primarily `cpu` and `memory`):

- **`requests`** — the amount the container is *guaranteed* and that the **scheduler uses** to decide placement. This is the floor.
- **`limits`** — the *maximum* the container is allowed to use; the **kubelet/kernel enforces** this. This is the ceiling.

```yaml
resources:
  requests:
    cpu: "500m"        # 0.5 of a CPU core, guaranteed for scheduling
    memory: "256Mi"    # 256 mebibytes, guaranteed
  limits:
    cpu: "1"           # may burst up to 1 full core, then throttled
    memory: "512Mi"    # hard ceiling; exceed it and the kernel OOMKills
```

The single most important sentence in this whole chapter:

> **Requests are for the scheduler (how Pods are placed). Limits are for the runtime (how usage is capped).**

### 2.2 Resource units — be precise

**CPU** is measured in **CPU units**, where `1` = 1 vCPU/core (one hyperthread on bare metal, one vCPU on a cloud VM). It is *compressible*: you can throttle it without killing the process.

- `1000m` = `1` = one core. `m` means "milliCPU" (thousandths). `500m` = half a core. `100m` = one-tenth.
- The smallest precision Kubernetes accepts is `1m`. You can't request `0.5m`.
- CPU is fungible across cores: `500m` does not mean "half of core #0"; it means "500 milliseconds of CPU time per 1000ms across whatever cores the scheduler/kernel gives you."

**Memory** is measured in **bytes**, and you use binary (power-of-two) or decimal (power-of-ten) suffixes. It is *incompressible*: you cannot "throttle" a process's memory — you can only kill it.

| Suffix | Meaning | Bytes |
|--------|---------|-------|
| `Ki` | kibibyte | 1024 |
| `Mi` | mebibyte | 1024² = 1,048,576 |
| `Gi` | gibibyte | 1024³ |
| `Ti` | tebibyte | 1024⁴ |
| `k` | kilobyte | 1000 |
| `M` | megabyte | 1,000,000 |
| `G` | gigabyte | 1,000,000,000 |

> **Watch out:** `256M` ≠ `256Mi`. `256M` = 256,000,000 bytes; `256Mi` = 268,435,456 bytes (~7% larger). Always prefer the binary suffixes (`Mi`, `Gi`) for memory — they match how the kernel and JVM think.

> **Beginner aside — compressible vs incompressible:** A *compressible* resource (CPU, network bandwidth, disk IO) can be temporarily withheld and given back later without breaking the process — the process just runs slower. An *incompressible* resource (memory) cannot: once a process has allocated a page of RAM, you can't take it back without crashing the process. This asymmetry is *why* exceeding a CPU limit throttles but exceeding a memory limit kills.

### 2.3 Allocatable vs Capacity

A node's **Capacity** is its raw total (e.g., 16 cores, 64 GiB). But Kubernetes doesn't let Pods use all of it. **Allocatable** = Capacity − (system-reserved + kube-reserved + eviction-threshold + hard-eviction headroom). The scheduler bin-packs against **Allocatable**, not Capacity.

> **Beginner aside — why reserve?** The node itself needs CPU/RAM to run the OS, the kubelet, the container runtime, and monitoring agents. If Pods could consume 100% of RAM, the kubelet would starve and the node would go *NotReady* — far worse than evicting a Pod. So Kubernetes carves out reservations.

```bash
kubectl describe node <node>   # shows Capacity and Allocatable side by side
```

The reservations are set via kubelet flags (or `KubeletConfiguration`):

- `--system-reserved=cpu=500m,memory=1Gi` — for OS daemons (sshd, systemd…).
- `--kube-reserved=cpu=500m,memory=1Gi` — for kubelet + runtime.
- `--eviction-hard=memory.available<500Mi,nodefs.available<10%` — the threshold below which the kubelet starts evicting Pods.

So a 64 GiB node might present ~60 GiB Allocatable. Your bin-packing must respect that.

### 2.4 The QoS classes (Quality of Service)

Based purely on how a Pod sets requests and limits, the kubelet assigns one of **three QoS classes**. You never set this directly; it's *derived*. It controls **eviction order** under memory pressure and influences the OOM score.

| QoS Class | Condition | Eviction priority |
|-----------|-----------|-------------------|
| **Guaranteed** | *Every* container has CPU **and** memory set, with `requests == limits` for both | Evicted **last** (most protected) |
| **Burstable** | At least one container has a request or limit set, but it doesn't meet the Guaranteed bar | Evicted **second** |
| **BestEffort** | *No* container sets any request or limit at all | Evicted **first** (least protected) |

> **Mnemonic:** Guaranteed = "I told you exactly what I need and I'll never want more." Burstable = "Here's my floor, but I might burst." BestEffort = "Whatever you've got, I'll take." Under pressure, the kernel and kubelet sacrifice the greedy and uncommitted first.

### 2.5 cgroups — the kernel mechanism behind it all

> **Beginner aside — cgroup (control group):** A Linux kernel feature that groups processes and limits/accounts their resource usage (CPU, memory, IO, PIDs). Every container runs inside a cgroup. Kubernetes translates your `requests`/`limits` into cgroup settings. There are two versions: **cgroup v1** (older, separate hierarchies per controller) and **cgroup v2** (unified hierarchy, now the default on modern distros and required for some features like memory QoS). Kubernetes supports both.

The mapping (cgroup v1 terminology, with v2 equivalents noted):

| K8s setting | cgroup v1 knob | cgroup v2 knob | Effect |
|-------------|----------------|----------------|--------|
| `cpu.requests` | `cpu.shares` | `cpu.weight` | Relative CPU *weight* under contention; not a cap |
| `cpu.limits` | `cpu.cfs_quota_us` + `cpu.cfs_period_us` | `cpu.max` | Hard CPU *throttle* per period |
| `memory.requests` | (informational, used for scheduling) | (informational) | Not directly enforced by kernel |
| `memory.limits` | `memory.limit_in_bytes` | `memory.max` | Hard memory ceiling → OOMKill on exceed |

Two crucial facts that surprise people:

1. **`cpu.requests` becomes CPU *shares*, not a reservation in the throttling sense.** Shares only matter under contention. If a node is idle, a container requesting `100m` can use *all* available CPU (up to its limit). Requests don't "hold back" CPU when nobody else wants it.
2. **`cpu.limits` is a hard ceiling enforced by the CFS quota**, and it throttles even when the node has spare CPU. This is the #1 source of mysterious latency. (Deep dive in §3.4.)

> **Beginner aside — CFS (Completely Fair Scheduler):** The default Linux process scheduler since 2007. It divides CPU time fairly among runnable tasks using virtual runtime accounting. The **CFS bandwidth control** feature adds per-cgroup quotas: a cgroup gets at most `quota` microseconds of CPU per `period` microseconds (default period = 100ms = `100000us`). When the quota is exhausted, every task in the cgroup is *throttled* (descheduled) until the next period.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle: a Pod is created → the scheduler picks a node → the kubelet admits it → cgroups enforce resources → under pressure, eviction/OOM kicks in.

### 3.1 The scheduling pipeline (high level)

The default scheduler (`kube-scheduler`) runs a continuous loop:

```
                    ┌─────────────────────────────────────────────┐
   API server  ───► │  Scheduling Queue (activeQ / backoffQ / unschedulableQ) │
   (new Pods)       └────────────────────────┬────────────────────┘
                                              │ pop highest-priority pod
                                              ▼
                                  ┌───────────────────────┐
                                  │  SCHEDULING CYCLE      │  (single-threaded, per pod)
                                  │  1. PreFilter          │
                                  │  2. Filter (predicates)│  ← node feasibility
                                  │  3. PostFilter         │  ← preemption if no node fits
                                  │  4. PreScore           │
                                  │  5. Score (priorities) │  ← rank feasible nodes
                                  │  6. NormalizeScore     │
                                  │  7. Reserve            │  ← tentatively claim resources
                                  │  8. Permit             │  ← (gang scheduling hooks)
                                  └───────────┬───────────┘
                                              ▼
                                  ┌───────────────────────┐
                                  │  BINDING CYCLE         │  (async, can overlap)
                                  │  9.  PreBind           │
                                  │  10. Bind (write nodeName via Bind API)
                                  │  11. PostBind          │
                                  └───────────────────────┘
```

> **Beginner aside — scheduling queue:** Pending Pods (those with no `nodeName` yet) sit in an in-memory priority queue inside the scheduler. **activeQ** holds Pods ready to schedule, ordered by Pod priority then arrival time. **backoffQ** holds Pods that failed recently and are waiting out an exponential backoff. **unschedulableQ** holds Pods that couldn't be placed; they get retried when cluster state changes (e.g., a node is added) — this is event-driven, not just timer-based, since K8s 1.22+ (the "QueueingHint" mechanism in newer versions makes this even more precise).

The scheduler is **single-threaded in the scheduling cycle** (one Pod's filter+score runs to completion before the next) but **binding is asynchronous**, so it can overlap binding of one Pod with the scheduling of the next. This is why throughput is "hundreds of Pods/sec" not "thousands."

### 3.2 Phase 1 — Filtering (predicates): "can this Pod run here?"

The scheduler asks each registered **Filter plugin**, for each node, a yes/no question. A node survives only if *all* filters say yes. Key built-in filters:

| Filter plugin | What it checks |
|---------------|----------------|
| **NodeResourcesFit** | Does the node have enough *Allocatable − sum(requests of existing pods)* for this Pod's requests? Checks cpu, memory, ephemeral-storage, and extended resources (e.g., GPUs). |
| **NodeAffinity** | Does the node match the Pod's `requiredDuringScheduling` nodeAffinity / nodeSelector? |
| **NodeName** | If the Pod hardcodes `spec.nodeName`, only that node passes (bypasses scheduler entirely). |
| **NodePorts** | If the Pod uses `hostPort`, is that port free on the node? |
| **NodeUnschedulable** | Is the node cordoned (`spec.unschedulable=true`)? If so, filter it out (unless Pod tolerates). |
| **TaintToleration** | Does the Pod tolerate all of the node's `NoSchedule`/`NoExecute` taints? |
| **PodTopologySpread** | Would placing here violate a *required* (`whenUnsatisfiable: DoNotSchedule`) topology spread constraint? |
| **InterPodAffinity** | Does the node satisfy required pod affinity / anti-affinity rules? |
| **VolumeBinding** | Can the Pod's PersistentVolumeClaims be satisfied here (zone-pinned volumes, topology)? |
| **VolumeZone / VolumeRestrictions / AzureDiskLimits / EBSLimits etc.** | Storage attach limits and zone constraints. |

**Crucial detail about NodeResourcesFit:** it compares the Pod's *requests* against the node's *remaining requestable capacity*, computed as `Allocatable − Σ(requests of all pods already assigned to that node)`. It does **not** look at *actual current usage*. A node can be 90% idle on real CPU but still rejected because existing Pods have *requested* (reserved) all of it. This is a frequent "why won't my Pod schedule, the node is empty?!" surprise.

To reduce wasted work on huge clusters, the scheduler doesn't filter *all* nodes — it uses **percentageOfNodesToScore** (default behavior scales down: roughly `max(50% − numNodes/125%, minimum 5%)`, with a floor). It finds "enough" feasible nodes then stops, sweeping the node list round-robin across cycles for fairness.

### 3.3 Phase 2 — Scoring (priorities): "which feasible node is best?"

Each surviving node gets scored 0–100 by each **Score plugin**; scores are weighted and summed; highest total wins (ties broken randomly). Key scorers:

| Score plugin | What it favors | Default weight |
|--------------|----------------|----------------|
| **NodeResourcesBalancedAllocation** | Nodes where CPU and memory utilization (post-placement) are *balanced* (avoids a node that's 90% CPU / 10% mem) | 1 |
| **NodeResourcesFit** (with a scoring strategy) | Depends on strategy: `LeastAllocated` (default) spreads load; `MostAllocated` bin-packs tightly; `RequestedToCapacityRatio` lets you shape a custom curve | 1 |
| **ImageLocality** | Nodes that already have the Pod's container image cached (faster start, less pull bandwidth) | 1 |
| **InterPodAffinity** | Nodes satisfying *preferred* pod affinity | 1+ |
| **NodeAffinity** | Nodes satisfying *preferred* node affinity | 1+ |
| **PodTopologySpread** | Nodes that improve topology balance for *preferred* spread constraints | 2 |
| **TaintToleration** | Nodes with fewer `PreferNoSchedule` taints the Pod must tolerate | 1+ |
| **NodeResourcesFit / DefaultPreemption interactions** | — | — |

The default scoring strategy (`LeastAllocated`) **spreads** Pods — it prefers the *emptiest* feasible node. If you want to **bin-pack** (consolidate onto fewer nodes to scale down and save money, common with the Cluster Autoscaler), switch the NodeResourcesFit plugin to `MostAllocated` via a `KubeSchedulerConfiguration` profile.

```yaml
# KubeSchedulerConfiguration: bin-pack instead of spread
apiVersion: kubescheduler.config.k8s.io/v1
kind: KubeSchedulerConfiguration
profiles:
  - schedulerName: default-scheduler
    pluginConfig:
      - name: NodeResourcesFit
        args:
          scoringStrategy:
            type: MostAllocated   # prefer the fullest node that still fits
```

### 3.4 Phase 3 — Reserve, Permit, Bind

- **Reserve:** the scheduler tentatively updates its in-memory cache to count the Pod's resources against the chosen node, so the *next* Pod in the same cycle sees accurate availability (prevents two Pods racing for the same slot).
- **Permit:** a hook point used by gang/coscheduling plugins to wait until a whole group of Pods can be admitted (used by batch/ML frameworks like Volcano, Kueue). Default scheduler approves immediately.
- **Bind:** the scheduler issues a **Binding** API call, which sets `pod.spec.nodeName`. This is the moment of truth — the Pod is now "assigned." If binding fails (e.g., the API server rejects it), the Pod returns to the queue.

After `nodeName` is set, the **kubelet on that node** notices (via its watch on Pods filtered by node) and takes over.

### 3.5 Kubelet admission & cgroup setup

The scheduler's view can be stale (it works off a cache). The kubelet does a **second, authoritative admission check** before starting the Pod, including:

- **NodeResourcesFit-equivalent** check against *actual* node allocatable and currently running Pods (catches races where the scheduler over-committed).
- **Topology Manager / CPU Manager / Memory Manager** admission (for guaranteed Pods requesting whole CPUs or hugepages — see §7).
- If admission fails, the Pod is rejected with reason **OutOfcpu / OutOfmemory** and won't run there; the Pod may go to `Failed` (and a controller reschedules a replacement).

On admission, the kubelet, via the container runtime, creates the cgroup hierarchy:

```
/sys/fs/cgroup/                         (cgroup v2 unified root)
└── kubepods.slice/                     (all pods; gets node-allocatable limits)
    ├── kubepods-guaranteed.slice/      (no extra cap beyond node)
    ├── kubepods-burstable.slice/       (capped at allocatable; CPU shares from requests)
    │   └── kubepods-burstable-pod<uid>.slice/
    │       └── cri-containerd-<id>.scope/
    │           ├── cpu.weight   = (request mapped from shares)
    │           ├── cpu.max      = "<quota> <period>"   (if limit set)
    │           └── memory.max   = <limit bytes>        (if limit set)
    └── kubepods-besteffort.slice/      (lowest CPU shares: 2)
```

The **cgroup hierarchy mirrors QoS**: there are top-level slices for guaranteed, burstable, and besteffort. This is how QoS influences kernel-level CPU fairness and the OOM-kill ordering.

### 3.6 CPU enforcement in detail (the CFS quota / throttling trace)

When a container has `cpu.limits: "1"`:

1. The kubelet sets `cpu.cfs_period_us = 100000` (100ms, the default period) and `cpu.cfs_quota_us = 100000` (100ms of CPU per 100ms = 1 core's worth). For a limit of `500m`, quota = `50000`. For `2`, quota = `200000`.
2. The kernel CFS bandwidth controller tracks, per 100ms period, how much CPU time the cgroup's tasks consumed.
3. The moment the cgroup's tasks consume their full quota within a period, **all** tasks in the cgroup are **throttled** — taken off the CPU until the period rolls over. They simply don't get scheduled, even if there are idle cores.
4. Throttling is recorded in `cpu.stat`: `nr_periods`, `nr_throttled`, `throttled_time` (v1) or `nr_throttled` / `throttled_usec` (v2).

**The classic multithreaded JVM trap:** Suppose a JVM (or any multithreaded app) has `cpu.limits: "1"` (100ms quota/period) but runs 8 threads on an 8-core node. In the first ~12.5ms of a period, those 8 threads can collectively burn the entire 100ms quota (8 cores × 12.5ms = 100ms). The cgroup is then throttled for the remaining 87.5ms of the period. To a single request, this looks like an 87ms stall — catastrophic p99 latency, even though average CPU usage is well under the limit. This is *bursty throttling* and it's why people see CPU throttling at 30% average utilization.

Mitigations:
- **Remove CPU limits** for latency-sensitive services (keep requests). Controversial but common at scale — let CPU shares handle fairness. (See §6/§7 for the nuanced debate.)
- **Raise the limit** to match real parallelism (e.g., limit ≥ number of active threads / cores).
- **Reduce the CFS period** with the kubelet flag `--cpu-cfs-quota-period=10ms` (alpha, cluster-wide) so throttling is finer-grained — but this increases scheduler overhead.
- **Cap JVM/runtime parallelism** to align thread count with the CPU limit (`-XX:ActiveProcessorCount`, `GOMAXPROCS`, etc.) so the app doesn't over-thread.

There was a real Linux kernel CFS bug (fixed in ~kernel 5.4, backported to many distros around 2019–2020) that caused *excessive* throttling even when quota wasn't truly exhausted, due to how unused slices were returned to the global pool. On old kernels (4.x), throttling can be far worse than the math suggests.

### 3.7 Memory enforcement (OOMKill trace)

Memory has no "throttle." Enforcement is binary:

1. The container's cgroup `memory.max` (v2) / `memory.limit_in_bytes` (v1) is set to the memory limit.
2. As the process allocates and *touches* pages (RSS grows), the kernel charges them to the cgroup.
3. When a memory allocation would exceed `memory.max`, the kernel tries to reclaim (drop page cache, swap if enabled — usually disabled in K8s). If it can't reclaim enough, the kernel's **OOM killer** fires *within that cgroup* and kills the most expensive process (by `oom_score`), almost always the container's main process.
4. The container exits with code **137** (= 128 + SIGKILL signal 9). `kubectl describe pod` shows `Reason: OOMKilled`.
5. The kubelet restarts the container per the Pod's `restartPolicy` (default `Always` for Deployments), with exponential backoff (`CrashLoopBackOff` if it keeps failing).

> **Beginner aside — RSS (Resident Set Size):** The amount of physical RAM a process is actually using (pages currently in memory), as opposed to virtual memory it has reserved but not touched. The cgroup limit is enforced against memory charged to the cgroup (RSS + page cache attributable to it), not virtual size. A JVM can reserve 4 GiB of virtual address space but only OOMKill when its *touched* memory crosses the limit.

**Two distinct kill mechanisms — don't confuse them:**

| Mechanism | Trigger | Scope | Code/Reason | Who acts |
|-----------|---------|-------|-------------|----------|
| **cgroup OOMKill** | Container exceeds its *own* `memory.limit` | Single container | Exit 137, `OOMKilled` | Linux kernel |
| **kubelet eviction** | *Node* memory drops below eviction threshold | Whole Pods, by QoS order | `Evicted` (status), Pod deleted | kubelet |

The cgroup OOMKill is local and immediate; the kubelet eviction is node-wide and proactive (it tries to evict *before* the node hits hard OOM). There's also a node-level kernel OOM killer that can fire if the kubelet's eviction is too slow.

### 3.8 Node-pressure eviction (the kubelet's role) — step by step

The kubelet monitors node resources every `housekeeping-interval` (~10s) and compares against **eviction thresholds**:

- **Hard eviction** (`--eviction-hard`, e.g., `memory.available<100Mi`, `nodefs.available<10%`, `imagefs.available<15%`, `nodefs.inodesFree<5%`): immediate eviction when crossed.
- **Soft eviction** (`--eviction-soft` + `--eviction-soft-grace-period`): waits a grace period, allowing transient spikes to pass.

When memory pressure triggers, the kubelet sets the node condition `MemoryPressure=true` (which also adds a `NoSchedule` taint to repel new Pods), then ranks Pods for eviction:

**Eviction ranking (memory pressure):**
1. **BestEffort** Pods first.
2. Then **Burstable** Pods whose usage *exceeds their requests* — ranked by how far over request they are (worst offenders first), then by **Pod priority** (lower priority evicted first).
3. **Guaranteed** Pods and Burstable Pods *under* their requests are evicted last (only if absolutely necessary).

The exact ordering uses a tuple: `(QoS via whether usage > requests, Pod priority, memory usage above requests)`. Pod priority (from PriorityClass) is now a factor in eviction ordering (since ~1.22 it's respected). The kubelet evicts the lowest-ranked Pod, checks if pressure cleared, and repeats.

> **Key insight:** Setting `memory.requests` accurately *protects* a Burstable Pod — staying under your request makes you a low-priority eviction target only after over-request Pods are gone. A Guaranteed Pod (requests == limits) is the best protected because its usage can never exceed its request without first hitting its own cgroup OOMKill.

### 3.9 The full lifecycle, end to end

```
1. kubectl apply  → API server validates, writes Pod to etcd (nodeName empty)
2. Scheduler watches → sees pending Pod → enqueues
3. Scheduling cycle  → Filter → (PostFilter/preempt if needed) → Score → Reserve → Bind
4. Binding          → API server sets pod.spec.nodeName, persists to etcd
5. Kubelet (on node)→ watch fires → admission check → reject or admit
6. Container runtime→ create cgroups, pull image (if not cached), create+start containers
7. Steady state     → CFS shares/quota govern CPU; memory.max governs RAM
8a. CPU over limit   → CFS throttles (latency hit)
8b. Mem over limit   → cgroup OOMKill (exit 137) → restart per policy
8c. Node mem pressure→ kubelet evicts Pods by QoS/priority order
9. Pod deleted/evicted → controller (Deployment/ReplicaSet) creates replacement → back to step 1
```

---

## 4. The complete toolkit

### 4.1 Pod-level resource & scheduling fields

| Field (under `spec` or `spec.containers[].resources`) | Purpose | Default |
|--------|---------|---------|
| `resources.requests.cpu` / `.memory` | Scheduling guarantee (floor) | none (→ BestEffort) |
| `resources.limits.cpu` / `.memory` | Runtime ceiling (throttle / OOM) | none (uncapped) |
| `resources.requests.ephemeral-storage` / `.limits` | Local scratch disk (logs, emptyDir, writable layer) | none |
| `resources.requests['nvidia.com/gpu']` etc. | Extended/device resources (GPUs, etc.) | none |
| `nodeSelector` | Hard label match for node (simplest affinity) | none |
| `nodeName` | Hardcode node, bypass scheduler entirely | none |
| `affinity.nodeAffinity` | Required/preferred node label rules | none |
| `affinity.podAffinity` / `podAntiAffinity` | Co-locate / spread relative to other Pods | none |
| `topologySpreadConstraints` | Even spread across topology domains | (cluster default may inject) |
| `tolerations` | Allow scheduling onto tainted nodes | (some auto-added, e.g. not-ready) |
| `priorityClassName` | Reference a PriorityClass (priority + preemption) | none → priority 0 |
| `schedulerName` | Use a non-default scheduler | `default-scheduler` |
| `overhead` | Pod sandbox overhead (added by RuntimeClass) | none |
| `restartPolicy` | `Always` / `OnFailure` / `Never` | `Always` |
| `terminationGracePeriodSeconds` | Time before SIGKILL on eviction/delete | 30 |

### 4.2 Node-side / kubelet configuration

| Flag / KubeletConfiguration key | Purpose | Default |
|----------------------|---------|---------|
| `--kube-reserved` | Reserve resources for kubelet/runtime | none |
| `--system-reserved` | Reserve for OS daemons | none |
| `--eviction-hard` | Hard eviction thresholds | `memory.available<100Mi,nodefs.available<10%,imagefs.available<15%` (varies by version/distro) |
| `--eviction-soft` / `--eviction-soft-grace-period` | Soft eviction with grace | none |
| `--eviction-minimum-reclaim` | How much to reclaim past threshold | 0 |
| `--cpu-manager-policy` | `none` or `static` (pin guaranteed Pods to exclusive cores) | `none` |
| `--topology-manager-policy` | NUMA alignment of CPU/memory/devices | `none` |
| `--cpu-cfs-quota` | Whether to enforce CPU limits via CFS quota at all | `true` |
| `--cpu-cfs-quota-period` | CFS period length | `100ms` |
| `--memory-manager-policy` | `None` or `Static` (guaranteed memory + hugepages NUMA) | `None` |
| `--max-pods` | Max Pods per node | 110 |

### 4.3 Cluster-scope objects

| Object | Purpose |
|--------|---------|
| **PriorityClass** | Defines a named integer priority; `globalDefault`; `preemptionPolicy` (`PreemptLowerPriority` or `Never`) |
| **LimitRange** (namespace) | Default requests/limits for containers that omit them; min/max bounds; default request==limit ratio caps |
| **ResourceQuota** (namespace) | Caps total requests/limits/object-counts per namespace |
| **RuntimeClass** | Selects a runtime (e.g., gVisor, Kata) and declares `overhead` |
| **KubeSchedulerConfiguration** | Configures scheduler profiles, plugins, weights, scoring strategy |
| **Node** (`.spec.taints`) | Taints repel Pods; labels enable affinity |

### 4.4 PriorityClass essentials

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata:
  name: high-priority
value: 1000000               # higher = more important
globalDefault: false         # if true, applies to Pods with no class
preemptionPolicy: PreemptLowerPriority   # or Never
description: "Critical user-facing services"
```

Reserved system classes: `system-cluster-critical` (2,000,000,000) and `system-node-critical` (2,000,001,000) — for control-plane/critical add-ons. Don't exceed these with app classes.

### 4.5 The CLI / debugging toolkit

| Command | What it tells you |
|---------|-------------------|
| `kubectl describe pod <p>` | Events (FailedScheduling reasons), QoS class, requests/limits, last state (OOMKilled), restart count |
| `kubectl get pod <p> -o jsonpath='{.status.qosClass}'` | The derived QoS class |
| `kubectl describe node <n>` | Capacity, Allocatable, "Allocated resources" (sum of requests), conditions (MemoryPressure, DiskPressure), taints |
| `kubectl top pod` / `kubectl top node` | Live CPU/mem usage (needs metrics-server) |
| `kubectl get events --sort-by=.lastTimestamp` | Cluster-wide events incl. Evicted, FailedScheduling, Preempted |
| `kubectl get pods --field-selector=status.phase=Failed` | Find evicted/failed Pods |
| `kubectl describe node \| grep -A5 "Allocated"` | Quick over-commit check |
| `kubectl get pod -o wide` | Which node each Pod landed on |
| `crictl stats` (on node) | Per-container runtime stats |
| `cat /sys/fs/cgroup/.../cpu.stat` | `nr_throttled`, `throttled_usec` — proof of CPU throttling |

---

## 5. Code examples by use case

### 5.1 A right-sized Burstable web service (the common default)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: web-api }
spec:
  replicas: 6
  selector: { matchLabels: { app: web-api } }
  template:
    metadata: { labels: { app: web-api } }
    spec:
      containers:
        - name: api
          image: registry.example.com/web-api:1.4.2
          resources:
            requests:           # scheduler reserves this; node guarantees it
              cpu: "250m"
              memory: "512Mi"
            limits:             # memory hard cap; NO cpu limit (avoid throttling)
              memory: "512Mi"   # equal to request → no memory burst, predictable
            # NOTE: cpu limit intentionally omitted so the app can burst under load
          ports: [{ containerPort: 8080 }]
```

Why this shape: memory `request == limit` makes RAM predictable (effectively Guaranteed-like for memory, no surprise eviction from over-using). Omitting the CPU limit avoids CFS throttling while CPU shares (from the request) still ensure fairness under contention. This is the "no CPU limits, set CPU requests" pattern used by many large shops (e.g., Zalando, Buffer's public writeups). It makes the Pod **Burstable** (because no CPU limit).

### 5.2 A Guaranteed latency-critical service (CPU pinning)

```yaml
apiVersion: v1
kind: Pod
metadata: { name: trading-engine }
spec:
  priorityClassName: high-priority
  containers:
    - name: engine
      image: trading-engine:9
      resources:
        requests: { cpu: "4", memory: "8Gi" }   # whole integer CPUs
        limits:   { cpu: "4", memory: "8Gi" }    # requests==limits → Guaranteed
```

With the kubelet flag `--cpu-manager-policy=static`, a **Guaranteed** Pod requesting *integer* CPUs gets **exclusive, pinned cores** (no other Pods share them; no cache thrashing; no CFS throttling because it owns the cores). This is the gold standard for low-jitter latency-sensitive workloads. QoS class here is **Guaranteed**.

### 5.3 Topology spread across zones (HA)

```yaml
spec:
  topologySpreadConstraints:
    - maxSkew: 1                              # zones may differ by at most 1 pod
      topologyKey: topology.kubernetes.io/zone
      whenUnsatisfiable: DoNotSchedule        # hard: refuse to imbalance
      labelSelector: { matchLabels: { app: web-api } }
    - maxSkew: 1
      topologyKey: kubernetes.io/hostname     # also spread across nodes
      whenUnsatisfiable: ScheduleAnyway       # soft: prefer but allow
      labelSelector: { matchLabels: { app: web-api } }
```

> **Beginner aside — topologyKey:** A node label that defines a "domain." `topology.kubernetes.io/zone` groups nodes by cloud availability zone; `kubernetes.io/hostname` makes each node its own domain. **maxSkew** is the max allowed difference in matching-Pod count between any two domains. This is the modern, preferred way to spread replicas (replaces clunky pod anti-affinity for spreading).

### 5.4 Pod anti-affinity (never co-locate two replicas on one node)

```yaml
spec:
  affinity:
    podAntiAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        - labelSelector: { matchLabels: { app: redis } }
          topologyKey: kubernetes.io/hostname   # one redis per node, hard rule
```

> **Beginner aside — `requiredDuringSchedulingIgnoredDuringExecution`:** "Required when scheduling, but ignored once running." If the rule is satisfied at placement time, the Pod stays even if labels later change. The other form, `preferredDuringScheduling...`, is a soft preference (with a `weight` 1–100). There is *no* `RequiredDuringExecution` form yet for affinity — Kubernetes won't evict a running Pod for affinity changes.

### 5.5 Node affinity to steer onto GPU / ARM / spot nodes

```yaml
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
          - matchExpressions:
              - { key: kubernetes.io/arch, operator: In, values: ["amd64"] }
              - { key: node.kubernetes.io/instance-type, operator: In, values: ["g4dn.xlarge"] }
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 80
          preference:
            matchExpressions:
              - { key: eks.amazonaws.com/capacityType, operator: In, values: ["SPOT"] }
  tolerations:
    - key: "nvidia.com/gpu"        # tolerate the GPU node's taint
      operator: "Exists"
      effect: "NoSchedule"
  containers:
    - name: trainer
      image: trainer:cuda12
      resources:
        limits: { nvidia.com/gpu: 1 }   # GPUs are integer-only, request==limit forced
```

### 5.6 Taints & tolerations (reserve nodes for specific workloads)

```bash
# Taint a node so ONLY tolerating pods land there
kubectl taint nodes gpu-node-1 dedicated=ml:NoSchedule
# NoExecute also evicts already-running non-tolerating pods
kubectl taint nodes node-7 maintenance=true:NoExecute
```

```yaml
# A pod that tolerates the dedicated taint
spec:
  tolerations:
    - key: "dedicated"
      operator: "Equal"
      value: "ml"
      effect: "NoSchedule"
    - key: "node.kubernetes.io/not-ready"   # tolerate node-not-ready for 5 min
      operator: "Exists"
      effect: "NoExecute"
      tolerationSeconds: 300
```

> **Beginner aside — taint effects:** `NoSchedule` (don't place new non-tolerating Pods), `PreferNoSchedule` (soft — avoid if possible), `NoExecute` (don't place AND evict running non-tolerating Pods; `tolerationSeconds` lets a tolerating Pod stay for N seconds before it too is evicted). Taints *repel*; tolerations *permit*. A toleration doesn't *attract* — you still need affinity/nodeSelector to *steer* onto the node.

### 5.7 Priority + preemption (let critical Pods evict batch jobs)

```yaml
---
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: batch-low }
value: 100
preemptionPolicy: Never        # batch jobs never preempt others
---
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: critical }
value: 1000000
preemptionPolicy: PreemptLowerPriority
```

When a `critical` Pod can't be scheduled (all nodes full), the scheduler's **PostFilter** runs **preemption**: it finds a node where evicting one or more *lower-priority* Pods would make room, and deletes them (respecting their `terminationGracePeriodSeconds` and PodDisruptionBudgets where possible). The victims go back to pending. `preemptionPolicy: Never` means the Pod waits politely instead of evicting anyone.

### 5.8 LimitRange + ResourceQuota (namespace governance)

```yaml
---
apiVersion: v1
kind: LimitRange
metadata: { name: defaults, namespace: team-a }
spec:
  limits:
    - type: Container
      default:        { cpu: "500m", memory: "512Mi" }  # applied as LIMIT if omitted
      defaultRequest: { cpu: "100m", memory: "128Mi" }  # applied as REQUEST if omitted
      max:            { cpu: "2",    memory: "4Gi" }     # reject pods exceeding
      min:            { cpu: "50m",  memory: "32Mi" }
---
apiVersion: v1
kind: ResourceQuota
metadata: { name: team-a-quota, namespace: team-a }
spec:
  hard:
    requests.cpu: "20"
    requests.memory: 40Gi
    limits.cpu: "40"
    limits.memory: 80Gi
    pods: "100"
```

> **Important interaction:** If a **ResourceQuota** sets `requests.cpu`/`requests.memory`, then *every* Pod in that namespace **must** declare those requests, or the API server rejects it. A **LimitRange** with defaults is the usual way to satisfy this automatically. This is a common "why are my Pods suddenly rejected?" cause after a platform team adds a quota.

### 5.9 The JVM-in-container memory configuration (the big one)

```yaml
spec:
  containers:
    - name: spring-app
      image: my-spring-app:3.2
      resources:
        requests: { cpu: "1",   memory: "1Gi" }
        limits:   { cpu: "2",   memory: "1Gi" }   # memory request==limit
      env:
        # Modern JVMs (8u191+, 11+, 17+) are container-aware by default.
        # Cap the HEAP as a % of the cgroup limit, leaving room for non-heap:
        - name: JAVA_TOOL_OPTIONS
          value: >-
            -XX:MaxRAMPercentage=70.0
            -XX:InitialRAMPercentage=70.0
            -XX:+UseContainerSupport
            -XX:MaxMetaspaceSize=128m
```

Java specifics covered in §7.4. The headline: a 1 GiB limit does **not** mean `-Xmx1g`. The JVM uses non-heap memory too (Metaspace, thread stacks, code cache, GC structures, direct/native buffers, JIT). If heap + non-heap > 1 GiB, the *kernel* OOMKills you (exit 137) — the JVM's own `OutOfMemoryError` may never fire. Leave ~25–30% headroom.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **CPU limits cause throttling; weigh them carefully.** For latency-sensitive services, prefer setting CPU *requests* and omitting CPU *limits* (or setting limits well above realistic parallelism). Monitor `container_cpu_cfs_throttled_periods_total / container_cpu_cfs_periods_total` (Prometheus, via cAdvisor); if the ratio is high (>10–20%) while CPU usage is moderate, you're throttling.
- **Align app concurrency to CPU allotment.** A JVM defaults thread-pool/`ForkJoinPool`/GC-thread sizing to `Runtime.availableProcessors()`, which (since 8u191/JDK 10) reflects the cgroup CPU *quota* (rounded up). If you set a fractional limit like `500m`, `availableProcessors()` may report `1`, shrinking GC/JIT threads — sometimes good, sometimes starving throughput. Use `-XX:ActiveProcessorCount=N` to override deliberately.
- **Bin-pack vs spread tradeoff.** `LeastAllocated` (default) spreads — good for resilience/headroom but leaves nodes half-empty (cost). `MostAllocated` packs — good for autoscaler scale-down and cost, but a node failure hurts more and there's less burst headroom.
- **Scheduler throughput.** On large clusters, tune `percentageOfNodesToScore` lower to speed scheduling (fewer nodes evaluated) at the cost of placement optimality.

### 6.2 Correctness & concurrency

- **Always set memory requests = limits for predictability.** This makes memory non-burstable, so a Pod can't sneak up and cause node pressure, and its eviction protection is maximized. The cost: less efficient packing of bursty memory workloads.
- **The scheduler is eventually consistent.** It works off a cache; binding can race with other schedulers or the kubelet's authoritative admission. Expect occasional `OutOfcpu` rejections — controllers retry, so it self-heals.
- **PodDisruptionBudgets (PDBs)** protect against *voluntary* disruptions (node drains, preemption tries to honor them) but **not** against involuntary OOMKill or hard node failure. Don't rely on PDBs for memory safety.

### 6.3 Security & multi-tenancy

- **ResourceQuota + LimitRange** are your guardrails against a tenant hogging the cluster or scheduling resource-bombs.
- **Priority abuse:** any Pod can reference a PriorityClass unless you restrict it. Use a **ValidatingAdmissionPolicy / OPA Gatekeeper / Kyverno** policy or RBAC on PriorityClasses to stop tenants from self-declaring `system-cluster-critical`. (There's also a built-in admission controller that limits use of priority above a threshold.)
- **BestEffort Pods are eviction fodder** — never run anything you care about as BestEffort.

### 6.4 Observability

- **Prometheus / cAdvisor metrics to watch:**
  - `container_cpu_cfs_throttled_periods_total` and `..._periods_total` → throttling ratio.
  - `container_memory_working_set_bytes` vs `kube_pod_container_resource_limits{resource="memory"}` → headroom; alert at ~85–90%.
  - `kube_pod_status_reason{reason="Evicted"}` and `kube_pod_container_status_last_terminated_reason="OOMKilled"`.
  - `scheduler_pending_pods`, `scheduler_pod_scheduling_duration_seconds`, `scheduler_schedule_attempts_total{result="unschedulable"}`.
- **Vertical Pod Autoscaler (VPA)** in *recommendation mode* is an excellent right-sizing tool: it observes real usage and suggests requests/limits without acting.
- **`kubectl describe`** is your front-line: it surfaces FailedScheduling reasons verbatim ("0/12 nodes are available: 8 Insufficient cpu, 4 node(s) had untolerated taint").

### 6.5 Cost

- Over-requesting is the #1 cluster cost leak: the scheduler reserves based on requests, so requesting `2` CPU and using `0.2` wastes 1.8 CPU of *schedulable* capacity. Right-size with VPA recommendations + historical p95/p99 usage.
- Use **MostAllocated** scoring + **Cluster Autoscaler** (or Karpenter on AWS) so emptied nodes scale down.

### 6.6 Testing

- Load-test with realistic concurrency to surface throttling before prod.
- Use `stress-ng` / memory-balloon test Pods to validate eviction order and OOM behavior in staging.
- Test preemption by filling a node and submitting a high-priority Pod; confirm the right victims are chosen and PDBs are respected.

### 6.7 Anti-patterns (avoid these)

| Anti-pattern | Why it bites | Fix |
|--------------|--------------|-----|
| No requests/limits at all (BestEffort) | First to be evicted; no scheduling guarantee | Set at least requests |
| CPU limit == CPU request, both small, multithreaded app | Severe CFS throttling at low utilization | Raise/remove CPU limit; cap app threads |
| `-Xmx` set equal to the memory limit | No room for non-heap → kernel OOMKill (137) | Use `MaxRAMPercentage~70` or `-Xmx` ≤ ~70% of limit |
| Requests way above real usage | Wasted capacity, fewer Pods per node, higher cost | Right-size from observed p95/p99 |
| Using pod anti-affinity for spreading | O(n²) scheduling cost, slow on big clusters | Use `topologySpreadConstraints` |
| `memory.limit` set, `memory.request` omitted | LimitRange may set request=limit; otherwise scheduling under-counts | Always set memory request |
| Relying on PDB to prevent OOM | PDB only covers voluntary disruptions | Right-size memory, set requests=limits |

---

## 7. Advanced topics & deep internals

### 7.1 CPU Manager (static policy) and exclusive cores

With `--cpu-manager-policy=static`, **Guaranteed** Pods that request **integer** CPUs get **exclusively pinned** cores from a shared pool; the rest of the Pods share the leftover. This eliminates CFS throttling and CPU steal/cache-thrash for those Pods. Caveats: requires whole-number CPU requests; doesn't help Burstable Pods; reduces the shared pool for everyone else. State is persisted in `cpu_manager_state` on the node.

### 7.2 Topology Manager & NUMA alignment

> **Beginner aside — NUMA (Non-Uniform Memory Access):** On multi-socket servers, each CPU socket has its own local RAM. Accessing local RAM is faster than crossing the interconnect to another socket's RAM. **Topology Manager** (`--topology-manager-policy=single-numa-node|restricted|best-effort`) aligns a Pod's CPUs, memory, and devices (NICs, GPUs) onto the *same* NUMA node for max performance. Critical for HPC, telco (DPDK), and high-throughput data planes.

### 7.3 Memory QoS (cgroup v2) and `memory.min`/`memory.high`

On cgroup v2, Kubernetes can set `memory.min` (protected memory that won't be reclaimed) from requests and `memory.high` (a soft throttle that triggers reclaim before the hard `memory.max`). This **MemoryQoS** feature (alpha/beta depending on version) makes memory behave a bit more like CPU — applying reclaim pressure before the hard OOMKill — smoothing behavior under pressure. Check your version's feature-gate status before relying on it.

### 7.4 The JVM-in-container memory trap (deep dive)

**Timeline of JVM container-awareness:**
- Pre-8u131: JVM ignored cgroups; `availableProcessors()` and default heap used the *host's* full CPU/RAM → instant over-allocation and OOMKills.
- 8u131–8u190: experimental `-XX:+UseCGroupMemoryLimitForHeap` (unreliable).
- **8u191+, JDK 10+:** container support is **on by default** (`-XX:+UseContainerSupport`). The JVM reads the cgroup memory limit and CPU quota.
- JDK 11+: `-XX:MaxRAMPercentage`, `InitialRAMPercentage`, `MinRAMPercentage` let you size heap as a fraction of the *container* limit.

**The memory budget a JVM actually needs** (all charged to the cgroup):
1. **Heap** (`-Xmx` / `MaxRAMPercentage`) — objects.
2. **Metaspace** (`MaxMetaspaceSize`) — class metadata; *unbounded by default* → a leak source.
3. **Thread stacks** — `~512KB–1MB × thread count` (`-Xss`). 500 threads ≈ 250–500 MiB.
4. **Code cache** (JIT-compiled code) — up to `ReservedCodeCacheSize` (~240 MiB default).
5. **GC overhead** — card tables, remembered sets (G1/ZGC structures).
6. **Direct byte buffers / native memory** — Netty, NIO, mmaped files, compression libs (`MaxDirectMemorySize`).
7. **JVM internal / Symbol / runtime** structures.

If `heap + non-heap > memory.limit`, the kernel OOMKills the process (exit 137) — you'll see *no* Java `OutOfMemoryError`, no stack trace, just a dead container. This is the trap: people set `-Xmx` = limit and get killed by native overhead.

**Recommended configuration for a 1 GiB limit:**
```bash
-XX:MaxRAMPercentage=70.0     # heap ≈ 700 MiB, leaving ~300 MiB for non-heap
-XX:MaxMetaspaceSize=128m     # bound metaspace (prevent slow native creep)
-XX:MaxDirectMemorySize=64m   # bound direct buffers if you use NIO/Netty
-XX:ActiveProcessorCount=2    # align thread pools/GC to your CPU allotment
```
Use **Native Memory Tracking** to debug: start with `-XX:NativeMemoryTracking=summary`, then `jcmd <pid> VM.native_memory summary` to see exactly where memory went. Confirm the kernel kill via `dmesg | grep -i oom` on the node or `kubectl describe pod` showing `OOMKilled` / exit 137.

> **Subtle CPU pitfall:** `Runtime.availableProcessors()` reflects `ceil(cpu.quota / cpu.period)`. A limit of `1500m` → `availableProcessors() == 2`. A limit of `500m` → `1`. No CPU *limit* → reports the host's full core count (which over-threads!). For predictable thread-pool sizing, set a CPU limit *or* pin with `-XX:ActiveProcessorCount`.

### 7.5 Pod overhead & RuntimeClass

When using sandboxed runtimes (gVisor, Kata Containers — VM-isolated containers), the sandbox itself consumes CPU/RAM. A **RuntimeClass** can declare `overhead`, which the scheduler adds to the Pod's effective requests so bin-packing accounts for the sandbox tax.

### 7.6 Extended resources & device plugins

GPUs, FPGAs, and other devices are exposed as **extended resources** (e.g., `nvidia.com/gpu`) via **device plugins** running as DaemonSets. They're integer-only and **`requests` must equal `limits`** (you can't "burst" a GPU). The scheduler's NodeResourcesFit treats them like any countable resource.

### 7.7 Scheduler extensibility

- **Scheduling Framework** (the plugin model shown in §3.1) is the modern way to extend the scheduler in-tree.
- **Scheduler extenders** (HTTP webhooks) — older, slower, still supported.
- **Multiple scheduler profiles** in one `kube-scheduler` binary, selected via `schedulerName`.
- **Second schedulers** (e.g., Volcano, Kueue, YuniKorn) for batch/gang/quota-aware scheduling — run alongside the default and Pods opt in via `schedulerName`.
- **Descheduler** — a separate component that *evicts* Pods that are sub-optimally placed (e.g., violating new affinity, on over-utilized nodes) so they get rescheduled better. The scheduler never moves a running Pod; the descheduler fills that gap.

### 7.8 In-place Pod resize (newer feature)

Historically, changing a Pod's resources required recreating it. **In-place Pod Vertical Scaling** (`resizePolicy`, beta-ish in recent versions) lets you change CPU/memory of a running container without restart for resources marked `RestartPolicy: NotRequired`. Memory shrink may still require restart. Check feature-gate status for your version.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Should I set a CPU limit?

| Situation | Set CPU limit? | Rationale |
|-----------|----------------|-----------|
| Latency-sensitive online service | **No** (or very high) | Avoid CFS throttling; CPU shares handle fairness |
| Multi-tenant cluster, untrusted tenants | **Yes** | Prevent one tenant from starving others; predictable billing |
| Batch / best-effort throughput jobs | Optional | Throttling is acceptable; limits aid fairness |
| Strict capacity planning / chargeback | **Yes** | Limits make utilization deterministic |
| Need exclusive cores (Guaranteed + static CPU mgr) | **Yes**, integer + requests==limits | Gets pinned cores, no throttling |

### 8.2 Memory request vs limit policy

| Policy | Pros | Cons | Use when |
|--------|------|------|----------|
| request == limit (Guaranteed-ish) | Predictable; best eviction protection; no surprise node pressure | Less dense packing; can't burst | Production services; JVM apps |
| request < limit (Burstable) | Denser packing; allows bursts | Risk of node pressure & eviction; harder capacity planning | Bursty, tolerant workloads |
| neither (BestEffort) | Maximum density | Evicted first; no guarantees | Throwaway/dev only |

### 8.3 Spreading: which mechanism?

| Goal | Mechanism | Notes |
|------|-----------|-------|
| Even spread across zones/nodes | **topologySpreadConstraints** | Preferred; supports soft/hard, maxSkew |
| Never two replicas on a node | **podAntiAffinity (required, hostname)** | Works but O(n²) cost on large clusters |
| Co-locate cache near app | **podAffinity** | Use sparingly; can create hot nodes |
| Steer onto special hardware | **nodeAffinity / nodeSelector** | Combine with tolerations for tainted nodes |
| Reserve nodes for a team/workload | **taints + tolerations (+ affinity)** | Taint repels; affinity attracts |

### 8.4 Bin-pack vs spread (scoring strategy)

| Strategy | Density | Resilience | Cost (with autoscaler) | Use when |
|----------|---------|------------|------------------------|----------|
| `LeastAllocated` (default) | Low | High (headroom) | Higher (half-empty nodes) | HA-first, bursty load |
| `MostAllocated` | High | Lower | Lower (scale down nodes) | Cost-first, Karpenter/CA |
| `RequestedToCapacityRatio` | Tunable curve | Tunable | Tunable | Custom packing goals |

### 8.5 Priority / preemption: use when… / avoid when…

- **Use when:** mixed criticality (user-facing vs batch), you want batch to yield to online traffic, capacity is contended.
- **Avoid when:** all workloads equally important (priority adds churn), or you can't tolerate the disruption of preemption victims. Set `preemptionPolicy: Never` for non-preempting high-priority Pods that should just wait.

---

## 9. Failure modes & debugging

### 9.1 Pod stuck `Pending` (FailedScheduling)

```bash
kubectl describe pod <p>      # read the Events section
# Typical: "0/20 nodes available: 12 Insufficient memory, 5 untolerated taint, 3 node affinity mismatch"
```

Diagnose by the reason string:
- **Insufficient cpu/memory** → sum of *requests* on every node exceeds Allocatable. Check `kubectl describe node | grep -A8 Allocated`. The node may be empty on real usage but full on requests. Fix: lower requests, add nodes, or enable autoscaler.
- **untolerated taint** → add a toleration or remove the taint.
- **node affinity/selector mismatch** → no node has the required label.
- **didn't match pod topology spread / anti-affinity** → constraint can't be satisfied; loosen `whenUnsatisfiable` to `ScheduleAnyway` or add nodes/zones.
- **had volume node affinity conflict** → PVC is zone-pinned to a zone with no fitting node.

### 9.2 Container `OOMKilled` (exit 137)

```bash
kubectl describe pod <p>      # Last State: Terminated, Reason: OOMKilled, Exit Code: 137
kubectl get pod <p> -o jsonpath='{.status.containerStatuses[0].lastState}'
# On the node:
dmesg -T | grep -iE "oom|killed process"
```
- **App genuinely over limit** → raise `memory.limit` or fix the leak.
- **JVM native overhead** → heap fits but non-heap pushed past limit. Use NMT (`jcmd <pid> VM.native_memory summary`), lower `MaxRAMPercentage`, bound Metaspace/DirectMemory.
- **Page cache pressure** → large file IO inflating cgroup memory; tune workload or raise limit.

### 9.3 Mysterious latency / CPU throttling at low utilization

```bash
# Inside the container or on node, read cgroup CPU stats (v2):
cat /sys/fs/cgroup/cpu.stat        # nr_throttled, throttled_usec climbing = throttling
# Prometheus:
rate(container_cpu_cfs_throttled_periods_total[5m])
  / rate(container_cpu_cfs_periods_total[5m])    # >0.2 is a red flag
```
- **Fix:** remove/raise CPU limit, reduce CFS period (`--cpu-cfs-quota-period`), or cap app thread count. Verify the kernel is ≥5.4 to avoid the old CFS over-throttling bug.

### 9.4 Pods being `Evicted` under node pressure

```bash
kubectl get events --field-selector reason=Evicted -A
kubectl describe node <n> | grep -i pressure   # MemoryPressure/DiskPressure=True
```
- Find what blew up node memory (a Burstable Pod over its request, or page cache). Set memory requests=limits on offenders; tune `--eviction-hard`; add capacity.
- **DiskPressure** (`nodefs`/`imagefs`): runaway logs, large emptyDirs, image bloat. Set `ephemeral-storage` requests/limits; add log rotation; garbage-collect images.

### 9.5 Preemption churn / cascading eviction

- High-priority Pods repeatedly preempting and the victims re-triggering preemption elsewhere. Diagnose with `reason=Preempted` events. Fix: reduce priority spread, set sane PDBs, ensure batch uses `preemptionPolicy: Never` and lives on its own (tainted) node pool.

### 9.6 Real-world incident patterns

- **"30% CPU but p99 spiking":** classic CFS throttling on a multithreaded JVM with a `1` CPU limit on a many-core node. Removing the CPU limit (keeping the request) dropped p99 by an order of magnitude — documented publicly by several engineering orgs.
- **"Container keeps dying with 137, no Java stack trace":** `-Xmx` set to the full container limit; native memory (Netty direct buffers + Metaspace) pushed total RSS over the cgroup `memory.max`. Fix: `MaxRAMPercentage=70`, bound direct memory.
- **"New Pods won't schedule after we added a ResourceQuota":** quota required requests on every Pod; Pods omitting them were rejected by the API server until a LimitRange supplied defaults.
- **"Node went NotReady and took half our Pods down":** no reservations (`kube-reserved`/`system-reserved`) → Pods consumed everything → kubelet starved. Fix: set reservations so Allocatable < Capacity.

---

## 10. Interview drill

**Q1. Explain the difference between resource requests and limits.**
*Model answer:* Requests are what the *scheduler* uses to place a Pod — they reserve capacity and guarantee a floor; the node won't accept a Pod if the sum of requests exceeds Allocatable. Limits are what the *runtime/kernel* enforces as a ceiling: CPU limits throttle via CFS quota, memory limits trigger an OOMKill on exceed. Requests = scheduling/guarantee; limits = enforcement/cap.
- *Probe: What happens if you set limit but not request?* For CPU/memory, if you set a limit without a request, Kubernetes sets the request equal to the limit (so they end up equal). A LimitRange may also fill defaults.
- *Probe: Is CPU request a hard reservation?* No — it becomes cgroup CPU *shares* (weight), only relevant under contention. An idle node lets a small-request container use spare CPU up to its limit.
- *Probe: Why is memory incompressible and CPU compressible?* You can withhold CPU time and give it back later harmlessly (throttle); you can't reclaim allocated RAM without killing the process — so memory over-limit = kill, CPU over-limit = throttle.

**Q2. Walk me through the scheduler's decision pipeline.**
*Model answer:* For each pending Pod popped from the priority queue: PreFilter → Filter (predicates: NodeResourcesFit, affinity, taints, topology spread) eliminates infeasible nodes; if none feasible, PostFilter runs preemption; then PreScore → Score (priorities: balanced/least/most-allocated, image locality, spread) ranks feasible nodes 0–100 weighted; Reserve tentatively claims; Permit (gang hooks); then async Bind writes `nodeName`. Kubelet does a final authoritative admission check.
- *Probe: Filtering vs scoring difference?* Filter is boolean feasibility (can it run here?); scoring is ranking (which is best?).
- *Probe: How does it scale to thousands of nodes?* `percentageOfNodesToScore` evaluates only "enough" nodes, sweeping round-robin for fairness, so it doesn't score every node every time.

**Q3. Explain QoS classes and eviction order.**
*Model answer:* Guaranteed (all containers have cpu+mem with requests==limits), Burstable (some requests/limits but not Guaranteed), BestEffort (no requests/limits). Under memory pressure the kubelet evicts BestEffort first, then Burstable Pods over their requests (worst-over-request and lowest-priority first), then Guaranteed/under-request last.
- *Probe: How do I make a Pod Guaranteed?* Set both cpu and memory, request==limit, for every container.
- *Probe: Does Pod priority affect eviction?* Yes — within the over-request Burstable group, lower priority is evicted first.

**Q4. A JVM Pod has a 2 GiB memory limit and `-Xmx2g`. What happens and why?**
*Model answer:* It gets OOMKilled (exit 137) because the JVM needs memory beyond heap — Metaspace, thread stacks, code cache, GC structures, direct buffers — all charged to the cgroup. heap(2G)+non-heap > 2 GiB limit → kernel OOM. There's no Java OOMError because the kernel kills first. Fix: `-Xmx` ≤ ~70% of limit (or `MaxRAMPercentage=70`) and bound Metaspace/direct memory.
- *Probe: How to debug which memory bucket grew?* Native Memory Tracking: `-XX:NativeMemoryTracking=summary` + `jcmd <pid> VM.native_memory summary`.
- *Probe: Does the JVM see the cgroup limit?* Since 8u191/JDK10 yes — container support is on by default.

**Q5. Why do I see CPU throttling at 25% utilization? (senior-signal)**
*Model answer:* CFS bandwidth control enforces the limit per 100ms period. A multithreaded app can exhaust its full quota in the first few ms (N threads × short time = full quota), then all threads are throttled for the rest of the period — producing tail-latency stalls even though *average* utilization is low. Fixes: remove/raise CPU limit, shorten CFS period, or cap thread/parallelism to match the limit. Justify with the throttled-periods ratio metric.
- *Probe: Tradeoff of removing CPU limits?* You lose hard isolation — a noisy Pod can burst into others' CPU (mitigated by shares/requests). Bad in untrusted multi-tenant; fine in trusted single-team clusters.
- *Probe: Kernel relevance?* Old kernels (<5.4) had a CFS bug over-throttling even with unused quota; upgrade.

**Q6. Taints/tolerations vs node affinity — when each? (senior-signal)**
*Model answer:* Taints *repel* Pods from nodes (opt-out by default); tolerations let specific Pods onto tainted nodes but don't attract them. Node affinity/nodeSelector *attract* Pods to labeled nodes. To dedicate a node pool to a workload you typically need *both*: taint the nodes (so random Pods stay off) and add affinity (so your Pods go there) plus a toleration. Use taints for "keep things off," affinity for "pull things on."
- *Probe: NoSchedule vs NoExecute?* NoSchedule blocks new placement; NoExecute also evicts running non-tolerating Pods (with optional `tolerationSeconds`).
- *Probe: Auto-added tolerations?* The not-ready/unreachable tolerations (300s) so Pods aren't instantly evicted on transient node blips.

**Q7. Explain priority and preemption.**
*Model answer:* PriorityClass assigns an integer priority; the scheduler orders the queue by it. If a high-priority Pod can't fit, PostFilter preemption deletes lower-priority Pods on a node to make room (honoring grace periods and trying to respect PDBs). `preemptionPolicy: Never` makes a Pod wait instead of evicting others.
- *Probe: Does preemption guarantee the freed node?* No — between preempting and binding, another Pod could grab the space; the scheduler nominates a node but re-evaluates.
- *Probe: Can preemption cascade?* Yes; victims re-enter the queue and could preempt elsewhere. Mitigate with node pools and PDBs.

**Q8. topologySpreadConstraints vs podAntiAffinity for spreading? (senior-signal)**
*Model answer:* Topology spread is the modern, scalable mechanism: `maxSkew` bounds imbalance across a `topologyKey` domain (zone/hostname), supports soft (`ScheduleAnyway`) and hard (`DoNotSchedule`). Pod anti-affinity can spread but scales poorly (O(n²) pairwise checks) and is binary. For HA across zones, prefer topology spread; reserve anti-affinity for strict "never co-locate" rules.
- *Probe: What's maxSkew?* Max allowed difference in matching-Pod count between any two domains.
- *Probe: What if a hard spread can't be satisfied?* The Pod stays Pending (DoNotSchedule). Use ScheduleAnyway to degrade gracefully.

**Q9. What's Allocatable vs Capacity and why does it matter?**
*Model answer:* Capacity is the node's raw total; Allocatable = Capacity − system-reserved − kube-reserved − eviction headroom. The scheduler bin-packs against Allocatable so the OS/kubelet/runtime always have resources and the node stays Ready. Without reservations, Pods can starve the kubelet → NotReady.
- *Probe: Where set?* kubelet flags `--system-reserved`, `--kube-reserved`, `--eviction-hard`.

**Q10. Difference between cgroup OOMKill and kubelet eviction?**
*Model answer:* cgroup OOMKill is the kernel killing a single container that exceeded its *own* memory limit (exit 137, OOMKilled). Kubelet eviction is node-level: when *node* memory drops below the eviction threshold, the kubelet proactively deletes whole Pods by QoS/priority order to relieve pressure (status Evicted). One is per-container/limit-driven; the other is per-node/threshold-driven.

**Q11. (senior-signal) When would you run a Pod with NO CPU limit in production, and what are the risks?**
*Model answer:* For latency-sensitive, trusted, single-tenant services where CFS throttling hurts tail latency more than the risk of bursting. CPU requests still provide fairness via shares. Risk: a misbehaving Pod can transiently consume idle CPU that another wanted; under contention shares arbitrate, so it's bounded, but you lose deterministic capacity planning and chargeback. In untrusted multi-tenant clusters, keep limits.

**Q12. How do you right-size a workload?**
*Model answer:* Observe real usage (Prometheus `container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`) at p95/p99 under representative load; set requests near steady-state p95, memory request==limit for predictability, CPU limit per the throttling tradeoff. Use VPA in recommendation mode to automate. Iterate; alert at ~85% memory of limit and on throttling ratio.
- *Probe: Why p95 not max?* Max includes rare spikes; sizing to max wastes capacity. Requests target the common case; bursts use spare/limit headroom.

---

## 11. Glossary

- **Allocatable:** Node resources available to Pods after reservations (Capacity − system/kube reserved − eviction headroom).
- **Affinity (node/pod):** Rules attracting Pods to nodes (nodeAffinity) or to/away from other Pods (pod (anti-)affinity).
- **Binding:** The scheduler action that sets `pod.spec.nodeName`, assigning the Pod to a node.
- **BestEffort:** QoS class for Pods with no requests/limits; evicted first.
- **Burstable:** QoS class with some requests/limits but not meeting Guaranteed; evicted second.
- **cAdvisor:** Container metrics collector embedded in the kubelet; source of container CPU/mem/throttle metrics.
- **Capacity:** A node's raw total resources.
- **cgroup:** Linux kernel control group; limits/accounts resource use per process group. v1 (split) and v2 (unified).
- **CFS (Completely Fair Scheduler):** Linux CPU scheduler; its bandwidth control enforces CPU limits via quota/period.
- **CFS quota/period:** `cpu.cfs_quota_us` / `cpu.cfs_period_us` (default period 100ms); caps CPU per period → throttling.
- **Cluster Autoscaler / Karpenter:** Components that add/remove nodes based on pending Pods and utilization.
- **Cordon:** Mark a node unschedulable (`spec.unschedulable`) without evicting current Pods.
- **CPU Manager (static):** Pins exclusive cores to integer-CPU Guaranteed Pods.
- **CPU shares / cpu.weight:** Relative CPU weight derived from requests; matters only under contention.
- **Descheduler:** Add-on that evicts sub-optimally placed Pods so they reschedule better.
- **Device plugin:** DaemonSet exposing hardware (GPU/FPGA) as an extended resource.
- **Drain:** Cordon + evict Pods from a node (e.g., for maintenance), respecting PDBs.
- **Eviction (node-pressure):** Kubelet deleting Pods when node resource thresholds are crossed.
- **Eviction threshold:** `--eviction-hard`/`--eviction-soft` levels (e.g., memory.available<100Mi).
- **etcd:** The cluster's key-value store holding all object state.
- **Extended resource:** Countable custom resource (e.g., `nvidia.com/gpu`), integer-only, request==limit.
- **Guaranteed:** QoS class where every container sets cpu+mem with requests==limits; evicted last.
- **kubelet:** Per-node agent managing Pods/containers and enforcing eviction.
- **LimitRange:** Namespace object setting default/min/max requests/limits per container.
- **MaxRAMPercentage:** JVM flag sizing heap as a % of the container memory limit.
- **MemoryPressure / DiskPressure:** Node conditions signaling resource shortage; auto-taint to repel Pods.
- **MostAllocated / LeastAllocated:** Scheduler scoring strategies for bin-packing vs spreading.
- **NUMA:** Non-Uniform Memory Access; per-socket local memory topology.
- **Native Memory Tracking (NMT):** JVM facility (`jcmd VM.native_memory`) to break down JVM native memory use.
- **OOMKill:** Kernel killing a process exceeding its cgroup memory limit (exit 137).
- **PDB (PodDisruptionBudget):** Limits voluntary disruptions (min available / max unavailable).
- **percentageOfNodesToScore:** Scheduler setting limiting how many nodes are evaluated per cycle.
- **Pod:** Smallest deployable unit; one or more co-located containers.
- **Preemption:** Scheduler evicting lower-priority Pods to fit a higher-priority pending Pod.
- **PriorityClass:** Named integer priority + preemption policy.
- **QoS class:** Guaranteed/Burstable/BestEffort, derived from requests/limits; governs eviction order.
- **Requests / Limits:** Scheduling floor / runtime ceiling for a resource.
- **ResourceQuota:** Namespace cap on aggregate requests/limits/object counts.
- **RSS:** Resident Set Size; physical RAM a process actually uses.
- **RuntimeClass:** Selects a container runtime and declares sandbox `overhead`.
- **Scheduler (kube-scheduler):** Control-plane component assigning Pods to nodes.
- **Scheduling Framework:** Plugin architecture (Filter/Score/Reserve/Bind etc.) inside kube-scheduler.
- **System/kube-reserved:** Resources carved out of Capacity for OS and kubelet/runtime.
- **Taint / Toleration:** Node mark repelling Pods / Pod permission to tolerate it.
- **Topology Manager:** Aligns CPU/memory/devices on the same NUMA node.
- **topologySpreadConstraints:** Even spreading of Pods across topology domains via maxSkew.
- **Throttling:** CFS withholding CPU when a cgroup exhausts its quota in a period.
- **VPA (Vertical Pod Autoscaler):** Recommends/sets Pod resource requests from observed usage.
- **Working set:** Memory actively in use (the metric the kubelet uses for eviction decisions).

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Golden rule:** *Requests → scheduler. Limits → kernel.* CPU over limit = **throttle**; memory over limit = **OOMKill (137)**.

**Units:** `1 CPU = 1000m`. Memory: prefer `Mi/Gi` (binary). `256M ≠ 256Mi`.

**QoS / eviction order:** BestEffort (evicted 1st) → Burstable-over-request (by priority) → Guaranteed/under-request (last).
- Guaranteed = every container, cpu+mem, request==limit.

**cgroup mapping:** cpu.request→shares/weight (contention only); cpu.limit→CFS quota (hard throttle); mem.limit→memory.max (OOMKill); CFS default period = 100ms.

**Scheduler:** queue → **Filter** (feasibility) → PostFilter (**preempt**) → **Score** (rank) → Reserve → **Bind** (nodeName) → kubelet admission. Default scoring spreads (`LeastAllocated`); switch to `MostAllocated` to bin-pack.

**Allocatable** = Capacity − system-reserved − kube-reserved − eviction headroom. Scheduler packs against requests vs Allocatable, NOT live usage.

**Spreading:** prefer `topologySpreadConstraints` (maxSkew, zone/hostname). Taints repel; affinity attracts; dedicate a pool = taint + affinity + toleration.

**Priority:** PriorityClass `value`; preemption deletes lower-priority Pods; `preemptionPolicy: Never` = wait.

**JVM trap:** never `-Xmx == limit`. Use `MaxRAMPercentage≈70`, bound Metaspace + direct memory, leave ~25–30% headroom. Debug with `jcmd VM.native_memory`.

**CPU throttling:** alert when `throttled_periods/periods > ~0.2`. Fix: remove/raise CPU limit, shorten CFS period, cap threads, kernel ≥5.4.

**Debug commands:** `kubectl describe pod/node`, `kubectl get events`, `kubectl top`, `cat /sys/fs/cgroup/cpu.stat`, `dmesg | grep oom`, `jcmd VM.native_memory summary`.

**Exit code 137 = SIGKILL (OOM).** Status `Evicted` = node-pressure eviction (different from OOMKill).

### Self-test (no answers — recall practice)

1. A node shows 5% CPU usage but the scheduler reports "0/10 nodes available: Insufficient cpu." Explain precisely how both can be true, and how you'd confirm it with one command.
2. You set `resources.limits.memory: 1Gi` and `-Xmx1g` on a Spring Boot service; it dies with exit 137 and no Java stack trace. Walk through *why*, and give the exact JVM flags you'd change.
3. Describe the full ordering the kubelet uses to choose eviction victims under MemoryPressure, including how Pod priority and "usage vs requests" factor in.
4. A multithreaded service shows healthy 30% average CPU but terrible p99 latency. Name the kernel mechanism responsible, the metric that proves it, and three distinct fixes with their tradeoffs.
5. You must run exactly one replica of a service per availability zone, refuse to schedule if that's impossible, and additionally spread softly across nodes. Write the `topologySpreadConstraints` and explain each field.
6. Explain why removing CPU limits is safe in a trusted single-team cluster but risky in an untrusted multi-tenant one, referencing cgroup shares vs quota.
7. Distinguish, with the resulting Pod status/exit code for each, between a cgroup OOMKill, a kubelet node-pressure eviction, and a scheduler preemption.
8. Your platform team adds a ResourceQuota and suddenly new Pods are rejected at submission time. Explain the mechanism and the one object you'd add to fix it cluster-wide for a namespace.
```


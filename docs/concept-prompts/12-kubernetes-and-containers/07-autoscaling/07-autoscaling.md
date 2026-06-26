# Autoscaling in Kubernetes

> An exhaustive engineering-handbook chapter on autoscaling in Kubernetes & containers — from first principles to deep internals, written for a senior Java/JVM backend developer who wants to design with it, operate it, debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What "autoscaling" means here

**Autoscaling** is the automated adjustment of compute capacity in response to observed demand. In Kubernetes there are *three distinct, independently-developed autoscalers*, each operating on a different axis, plus a popular fourth add-on:

| Autoscaler | Axis it scales | Unit it adds/removes | Typical reaction time |
|---|---|---|---|
| **HPA** (Horizontal Pod Autoscaler) | More/fewer **replicas** of a workload | **Pods** | seconds → ~1 min |
| **VPA** (Vertical Pod Autoscaler) | Bigger/smaller **resource requests** per Pod | **CPU/memory per Pod** | minutes (often requires Pod restart) |
| **Cluster Autoscaler** (CA) / **Karpenter** | More/fewer **nodes** | **VMs / nodes** | 1–10 min (depends on cloud) |
| **KEDA** (Kubernetes Event-Driven Autoscaling) | More/fewer **replicas** driven by **external events** | **Pods** (drives HPA under the hood) + **scale-to-zero** | seconds → tens of seconds |

A useful slogan: **HPA scales pods out, VPA scales pods up, Cluster Autoscaler scales nodes out, KEDA scales pods out based on events (and down to zero).**

> **Beginner aside — Pod, replica, node, workload.**
> A **container** is a packaged process (image + isolated filesystem/cgroups). A **Pod** is the smallest deployable unit in Kubernetes: one or more co-located containers sharing a network namespace and (optionally) storage. A **replica** is one running copy of a Pod; a Deployment with `replicas: 5` runs 5 identical Pods. A **node** is a worker machine (VM or bare metal) that runs Pods. A **workload** is the higher-level object that manages Pods — typically a **Deployment**, **StatefulSet**, or **ReplicaSet**. We'll define each precisely as we go.

### 1.2 The problem it solves

Static capacity planning forces a bad choice:
- **Over-provision** → you pay for idle CPU/RAM 24/7. For a JVM service sized for Black-Friday peak, that can be 5–20× waste off-peak.
- **Under-provision** → latency spikes, OOM kills, dropped requests, SLO breaches when traffic rises.

Autoscaling lets capacity *track* demand: scale up when load arrives, scale down when it leaves, and (with CA/Karpenter) only pay your cloud provider for nodes you actually need. The end goal is **elasticity**: capacity that is roughly proportional to load, within latency and cost bounds you control.

> **Beginner aside — SLO/SLI/SLA.** An **SLI** (Service Level Indicator) is a measured number, e.g. p99 latency or error rate. An **SLO** (Objective) is a target for it, e.g. "p99 < 300 ms, 99.9% of the time." An **SLA** (Agreement) is a contractual promise with penalties. Autoscaling is one lever for meeting SLOs without permanent over-provisioning. **OOM** = Out Of Memory; the Linux kernel's OOM killer terminates a process (here, a container) that exceeds its memory limit.

### 1.3 When you reach for each

- **HPA**: stateless or near-stateless services whose load correlates with a measurable signal — CPU%, requests-per-second (RPS), queue depth. The default and most common choice.
- **VPA**: workloads where you don't know the right `requests`/`limits`, or where load varies in *intensity per request* rather than *number of requests* (e.g., a batch worker, a JVM whose heap needs grow). Also used in *recommendation-only* mode to right-size everything.
- **Cluster Autoscaler / Karpenter**: always, in any cluster where you don't want to manually manage node count. It's the layer that makes HPA/KEDA's new pods actually get scheduled.
- **KEDA**: event-driven and queue-driven workloads — Kafka consumers, RabbitMQ/SQS workers, cron-based scaling, scale-to-zero for rarely-used services, and any metric source HPA can't natively read.

### 1.4 One-paragraph mental model

Think of a **control loop** (PID-ish controller) sitting beside your cluster. It repeatedly reads a *signal* (CPU%, queue lag, custom metric), compares it to a *setpoint* (target value), computes a *desired replica count* with a simple ratio formula, and writes that number to your Deployment's `.spec.replicas`. The scheduler then tries to place those pods on nodes; if there's no room, a *second* control loop (Cluster Autoscaler/Karpenter) notices the unschedulable pods and provisions nodes. VPA is a *third* loop that watches resource usage and rewrites each pod's `requests`. KEDA is an *adapter* that turns event sources into metrics the HPA loop can consume — plus a switch that flips replicas to/from zero. Everything is eventually-consistent, polling-based, and deliberately damped to avoid thrashing.

---

## 2. Foundations from first principles

### 2.1 Requests, limits, and why they're the foundation of all autoscaling

Every container can declare:

```yaml
resources:
  requests:        # what the scheduler reserves; the basis for scheduling & HPA%
    cpu: "500m"    # 500 millicores = 0.5 of one vCPU
    memory: "512Mi"
  limits:          # hard ceiling enforced at runtime
    cpu: "1000m"
    memory: "1Gi"
```

> **Beginner aside — millicores and Mi/Gi.** CPU in Kubernetes is measured in **cores**; `1000m` ("millicores") = 1 vCPU. `500m` = half a core. Memory uses binary units: `Mi` = mebibyte = 2²⁰ bytes, `Gi` = gibibyte = 2³⁰ bytes (note: not MB/GB which are decimal). `Ki/Mi/Gi/Ti` are the ones you'll use.

**Why requests matter for autoscaling:** HPA's CPU utilization is computed as a *percentage of the request*, **not** a percentage of the node or the limit. If a pod uses 400m and its request is 500m, HPA sees **80%** utilization. If the request were 1000m, the *same* pod would read **40%**. So **the request value directly scales the autoscaler's perception of load.** Set requests wrong and HPA scales at the wrong time or never. This is the single most common autoscaling mistake (covered in §5.5 and §9).

**CPU vs memory enforcement (critical distinction):**
- **CPU limits** are enforced by **throttling** — the kernel's CFS (Completely Fair Scheduler) bandwidth controller caps the cgroup's CPU time per period. The container isn't killed; it just runs slower. This is dangerous for latency-sensitive JVM services (see §7.6).
- **Memory limits** are enforced by **OOM kill** — exceed the limit and the kernel kills the process. Containers that exceed memory get restarted; repeated OOM kills show as `OOMKilled` in pod status.

> **Beginner aside — cgroups and CFS.** **cgroups** (control groups) are a Linux kernel feature that limits and accounts for a process group's resources (CPU, memory, I/O). Containers are isolated using cgroups + namespaces. **CFS** is the default Linux CPU scheduler; **CFS bandwidth control** uses a `cpu.cfs_period_us` (default 100 ms) and `cpu.cfs_quota_us` to cap how much CPU a cgroup may use per period. A `cpu` limit of `1000m` ≈ a quota equal to one full period's worth of one CPU.

> **Beginner aside — QoS classes.** Kubernetes assigns each pod a **Quality of Service** class based on requests/limits:
> - **Guaranteed**: requests == limits for *every* container, both CPU and memory set. Last to be evicted.
> - **Burstable**: at least one request set, but not equal to limits. Middle.
> - **BestEffort**: no requests or limits at all. First evicted under node pressure.
> QoS determines eviction order when a node runs out of resources.

### 2.2 The Kubernetes control-loop / reconciliation model

All Kubernetes controllers — including every autoscaler — follow the same pattern, the **reconciliation loop**:

1. **Observe** the current state (read from the API server / metrics).
2. **Compare** to the desired state (the setpoint).
3. **Act** to reduce the difference (write a new desired state, e.g. `replicas`).
4. **Repeat** forever, on a fixed interval.

> **Beginner aside — declarative vs imperative; API server; etcd.** Kubernetes is **declarative**: you describe the *desired* end state in YAML; controllers continuously drive *actual* state toward it. The **API server** (`kube-apiserver`) is the front door — every read/write goes through it. State is persisted in **etcd**, a distributed key-value store using the **Raft** consensus protocol. **Raft** is an algorithm for keeping a replicated log consistent across a cluster of machines by electing a leader and replicating entries to a majority before committing — that's how etcd survives node failures without losing data.

This is exactly the loop a Java engineer would recognize as a feedback controller: read sensor, compute error, apply correction, damp to avoid oscillation. HPA's "damping" is the **stabilization window** (§3.2). It is **not** a true PID controller — there's no integral or derivative term; it's a proportional controller with hysteresis and rate limits.

### 2.3 The metrics plumbing — where the numbers come from

HPA does not measure anything itself. It *reads* metrics through Kubernetes **aggregated APIs**:

| API group | Served by | Provides | Used for |
|---|---|---|---|
| `metrics.k8s.io` (Resource Metrics API) | **metrics-server** | live CPU/memory of pods & nodes | HPA CPU/memory utilization; `kubectl top` |
| `custom.metrics.k8s.io` (Custom Metrics API) | a custom-metrics adapter (e.g. **Prometheus Adapter**, KEDA's metrics server) | arbitrary metrics tied to a Kubernetes object (e.g. RPS per pod) | HPA `type: Pods` / `type: Object` |
| `external.metrics.k8s.io` (External Metrics API) | an external-metrics adapter (KEDA, Prometheus Adapter, cloud adapters) | metrics from outside the cluster (Kafka lag, SQS depth, cloud LB queue) | HPA `type: External` |

> **Beginner aside — metrics-server.** A lightweight, cluster-wide aggregator that scrapes the **kubelet** on each node (specifically the kubelet's `/metrics/resource` endpoint, sourced from **cAdvisor**) every ~15 s and exposes current CPU/memory through the Resource Metrics API. It keeps only the *latest* sample in memory — it is **not** a time-series database and cannot do history. Without it, HPA's CPU/memory targets simply do not work. **kubelet** is the per-node agent that runs containers and reports their status. **cAdvisor** (Container Advisor) is built into the kubelet and collects per-container resource usage from cgroups.

> **Beginner aside — Prometheus.** A pull-based time-series monitoring system. It scrapes `/metrics` HTTP endpoints, stores samples with labels, and answers PromQL queries. The **Prometheus Adapter** translates PromQL query results into the Custom/External Metrics API so HPA can act on them.

### 2.4 The three scaling axes, visualized

```
                         demand rises ──►

  VERTICAL (VPA): bigger pods         HORIZONTAL (HPA/KEDA): more pods
  ┌────────┐   ┌──────────────┐       ┌──┐ ┌──┐    ┌──┐ ┌──┐ ┌──┐ ┌──┐
  │  pod   │ → │     pod       │       │pod│ │pod│ → │pod│ │pod│ │pod│ │pod│
  │ 0.5cpu │   │   2 cpu       │       └──┘ └──┘    └──┘ └──┘ └──┘ └──┘
  └────────┘   └──────────────┘

  CLUSTER (CA/Karpenter): more nodes to hold the pods
  ┌─────────────┐            ┌─────────────┐ ┌─────────────┐
  │   node 1    │     →      │   node 1    │ │   node 2    │
  └─────────────┘            └─────────────┘ └─────────────┘
```

Horizontal and vertical are *complementary in concept* but *conflicting in implementation* when applied to the **same metric** on the **same workload** (§3.3). Cluster scaling is *downstream* of both — it reacts to pods that can't be scheduled.

---

## 3. How it works internally

This is the heart of the chapter. We go axis by axis.

### 3.1 HPA internals — the control loop and the algorithm

#### 3.1.1 Where the loop runs

The HPA controller lives inside the **kube-controller-manager**, a core control-plane component. By default it reconciles every HPA object on a fixed cadence:

- `--horizontal-pod-autoscaler-sync-period` — default **15 s**. Every 15 s, for each HPA, the controller fetches metrics and recomputes desired replicas.

> **Beginner aside — kube-controller-manager.** A single binary that runs dozens of built-in controllers (Deployment, ReplicaSet, Node, Job, HPA, …) each as a loop. On managed clusters (EKS/GKE/AKS) it runs on the provider-managed control plane, so you typically can't change its flags directly — you adjust HPA behavior through the HPA object's `behavior` field instead (see §3.2).

#### 3.1.2 The scaling algorithm (the formula you must memorize)

For each metric, HPA computes:

```
desiredReplicas = ceil( currentReplicas × ( currentMetricValue / desiredMetricValue ) )
```

- `currentMetricValue` is the **average across all ready pods** for utilization/`AverageValue` targets.
- `desiredMetricValue` is your `target` (e.g. 50% CPU, or 100 messages/pod).
- `ceil` = round **up** (always round toward more capacity).

**Worked numbers.** 4 pods averaging 90% CPU, target 50%:
```
desired = ceil(4 × (90/50)) = ceil(4 × 1.8) = ceil(7.2) = 8 pods
```

**The tolerance band (anti-flapping).** HPA ignores ratios within a tolerance of the target:
- `--horizontal-pod-autoscaler-tolerance` — default **0.1 (10%)**. If `currentMetric/desiredMetric` is within `[0.9, 1.1]`, HPA does **nothing**. So at 52% with a 50% target, the ratio is 1.04 → inside the band → no action. This prevents constant ±1 jitter.

> Note: in Kubernetes 1.33+ there is *configurable per-HPA tolerance* via an alpha feature (`HPAConfigurableTolerance`), but the cluster-wide default remains 0.1. Flag this as version-specific.

**Multiple metrics.** If an HPA lists several metrics, it computes a desired-replica count for **each**, then takes the **maximum**. The reasoning: any single metric breaching its target should be honored, so you scale up to satisfy the most demanding one.

**Readiness and missing metrics handling.** This is subtle and exam-worthy:
- Pods that are **not yet Ready**, or are **still in their initial readiness window**, are excluded from the average to avoid being misled by cold-starting pods. The relevant flag historically was `--horizontal-pod-autoscaler-initial-readiness-delay` (default **30 s**) and `--horizontal-pod-autoscaler-cpu-initialization-period` (default **5 min**) — during CPU initialization, a not-yet-ready pod's CPU is ignored or treated conservatively.
- For pods **missing metrics**, HPA assumes the worst case in the *direction that opposes* the proposed move: it assumes **100% usage** when considering scale-**down** and **0%** when considering scale-**up**, biasing toward stability.

#### 3.1.3 The full step-by-step HPA reconcile

For one HPA, one tick:

1. **List target pods.** Resolve the `scaleTargetRef` (Deployment/StatefulSet/etc.) → its current `replicas` and the set of pods.
2. **Fetch metrics** for each listed metric source via the appropriate metrics API.
3. **Filter pods:** drop unready/initializing pods and (for some calculations) those missing metrics; track missing-metric and unready sets separately.
4. **Compute the per-metric ratio** `usage/target` over the *remaining ready* pods.
5. **Apply the missing/unready bias** described above.
6. **Apply the tolerance band**: if every metric's ratio is within ±10%, exit with no change.
7. **Compute desiredReplicas per metric** with the formula; take the **max** across metrics.
8. **Clamp** to `[minReplicas, maxReplicas]`.
9. **Apply `behavior` rate limits & stabilization** (§3.2): the desired value is filtered by scale-up/scale-down policies and the stabilization windows.
10. **Write** `scale.spec.replicas` on the target via the `/scale` subresource (HPA never edits the Deployment spec directly — it uses the scale subresource so it works generically).
11. **Record events & status** (`kubectl describe hpa` shows the decision and any "unable to fetch metrics" conditions).

> **Beginner aside — the `/scale` subresource.** Kubernetes exposes a generic `scale` endpoint on scalable objects. HPA writes a tiny `{spec:{replicas:N}}` patch there; the object's own controller (e.g. the Deployment controller) then reconciles the new replica count into ReplicaSets/Pods. This indirection is why HPA can scale *any* object that implements `/scale`, including many CRDs.

#### 3.1.4 Metric types in the HPA v2 spec

| `type` | Reads from | Meaning | Example |
|---|---|---|---|
| `Resource` | metrics-server | CPU/memory of the target's pods | CPU `Utilization: 50%` |
| `ContainerResource` | metrics-server | CPU/memory of a **specific container** in the pod | sidecar-aware scaling |
| `Pods` | custom metrics API | a per-pod metric, averaged across pods | `http_requests_per_second` per pod |
| `Object` | custom metrics API | a metric describing **one** k8s object | Ingress `requests-per-second` |
| `External` | external metrics API | a metric outside the cluster | Kafka consumer lag, SQS depth |

For `Resource`/`ContainerResource` you can target either:
- `Utilization` (a percentage of **requests**), or
- `AverageValue` (an absolute average like `500m` CPU per pod — independent of requests).

### 3.2 The `behavior` field — stabilization windows & scaling policies

HPA `autoscaling/v2` supports a `behavior` block that controls *how fast* it may move, separately for up and down:

```yaml
behavior:
  scaleDown:
    stabilizationWindowSeconds: 300   # default 300s (5 min) for scale-DOWN
    policies:
    - type: Percent
      value: 100        # may remove up to 100% of current pods...
      periodSeconds: 15 # ...per 15s window
    - type: Pods
      value: 4          # OR remove at most 4 pods per 15s
    selectPolicy: Min   # pick the policy allowing the SMALLER change (most conservative)
  scaleUp:
    stabilizationWindowSeconds: 0     # default 0s for scale-UP (react immediately)
    policies:
    - type: Percent
      value: 100        # may double pod count...
      periodSeconds: 15 # ...every 15s
    - type: Pods
      value: 4          # OR add 4 pods per 15s
    selectPolicy: Max   # pick the policy allowing the LARGER change (scale up fast)
```

**Stabilization window — the key anti-flapping mechanism.** Within the window, HPA keeps a *buffer of recent desired-replica computations* and uses the **maximum** of recent recommendations when scaling **down** (and the minimum when scaling up, under the inverted semantics). Concretely, for scale-down with a 300 s window, HPA will only shrink to N if N was the *highest recommended* count for the last 5 minutes — i.e., it won't shrink off a momentary dip. This is why **scale-down is slow by default (5 min)** and **scale-up is instant by default (0 s)**: you want to grab capacity fast and release it cautiously.

> **Beginner aside — flapping / thrashing.** Rapid oscillation between scaling up and down (e.g., load hovers near the target so the controller adds and removes pods every cycle). It wastes resources, churns connections, and on a JVM repeatedly pays cold-start/JIT-warmup costs. Stabilization windows + the tolerance band exist to damp this.

**`selectPolicy`** picks between multiple policies: `Max` (most aggressive — used for scale-up), `Min` (most conservative — used for scale-down), or `Disabled` (forbid scaling in that direction entirely — e.g., disable automatic scale-down).

### 3.3 VPA internals

VPA is **not** part of core Kubernetes; it's an add-on (in `kubernetes/autoscaler`) with three components:

1. **VPA Recommender** — watches historical and live resource usage (via metrics-server / its own history store), models the distribution, and emits **recommended** requests: a `target`, plus `lowerBound` and `upperBound`. It uses a decaying-histogram model: it tracks CPU and memory usage histograms over a sliding window (default history ~8 days, with exponential decay so recent data weighs more) and picks high percentiles (e.g. ~90th for CPU target, near-max for memory) to set the recommendation.
2. **VPA Updater** — decides which running pods are out of bounds and **evicts** them so they're recreated with new requests (in `Auto`/`Recreate` mode). It respects PodDisruptionBudgets.
3. **VPA Admission Controller** — a mutating admission webhook that **rewrites the pod's requests at creation time** to match the recommendation, so the recreated pod comes up correctly sized.

> **Beginner aside — admission webhook.** When you create/update an object, the API server can call external HTTP **admission controllers**: *validating* ones can reject, *mutating* ones can modify the object (e.g., inject sidecars, or — here — rewrite resource requests). VPA's mutating webhook is how the new pod gets the recommended size.

**VPA update modes (`updatePolicy.updateMode`):**

| Mode | Behavior |
|---|---|
| `Off` | Recommender produces numbers; nothing is applied. **Recommendation-only** — great for right-sizing analysis. |
| `Initial` | Requests are set only at pod **creation**; never updated on running pods. |
| `Recreate` | Evicts & recreates pods to apply changes. Disruptive. |
| `Auto` | Currently behaves like `Recreate` (evict to resize). In future may use **in-place resize**. |

> **In-place pod resize (version-specific).** Kubernetes added in-place resource resize for pods (`resize` subresource) — beta around 1.33 (feature gate `InPlacePodVerticalScaling`). It lets CPU (and, where supported, memory) requests/limits change **without** restarting the container, by adjusting cgroups live. VPA integration with in-place resize is evolving; verify support for your version and runtime. Flag this as moving target.

#### 3.3.1 Why HPA + VPA on the SAME metric conflict (must-know)

If both autoscalers act on **CPU** for the **same Deployment**:
- HPA reads **CPU utilization (% of request)** and adds/removes **pods**.
- VPA changes the **CPU request** itself.

The conflict: VPA *raising the request* lowers the measured utilization % (same absolute usage ÷ bigger request = smaller %), which makes HPA think load dropped and **scale down pods** — even though nothing changed. Conversely VPA *lowering* requests inflates the %, triggering HPA scale-up. The two controllers fight, oscillating both pod count and pod size. **This is unstable and explicitly unsupported.**

**Rules to live by:**
- **Never** run HPA on CPU/memory *and* VPA in `Auto`/`Recreate` on the *same* resource for the *same* workload.
- **Allowed combinations:**
  - HPA on a **custom/external metric** (e.g. RPS, queue lag) + VPA on **CPU/memory**. They act on disjoint signals → no feedback loop. (Common and recommended.)
  - HPA on CPU/memory + VPA in **`Off`** (recommendation-only) — VPA just advises; you apply manually.
- Multidimensional Pod Autoscaler (GKE **MPA**) is a managed offering that coordinates both safely — vendor-specific.

### 3.4 Cluster Autoscaler internals

The **Cluster Autoscaler (CA)** scales the *number of nodes* by interacting with the cloud's node-group abstraction (AWS Auto Scaling Group, GCP Managed Instance Group, Azure VMSS, etc.).

> **Beginner aside — node group / ASG / MIG / VMSS.** A **node group** is a set of identical VMs managed as a unit. On AWS it's an **Auto Scaling Group (ASG)**; GCP a **Managed Instance Group (MIG)**; Azure a **Virtual Machine Scale Set (VMSS)**. CA changes the group's *desired size*, and the cloud provisions/terminates VMs to match.

**Scale-UP loop (every ~10 s scan):**
1. CA lists **unschedulable pods** — pods stuck in `Pending` because no node has room (insufficient CPU/memory, or no node matches their nodeSelector/affinity/taints).
2. For each node group, CA runs a **scheduling simulation**: "If I add one node of this group's shape, would these pending pods fit?"
3. It picks the node group (per the configured **expander** strategy — see §4.4) and **increments that group's desired size**.
4. The cloud boots a VM; kubelet registers the node; the scheduler places the pending pods.

> **Beginner aside — taints & tolerations, affinity.** A **taint** on a node repels pods unless the pod has a matching **toleration** (used to reserve nodes for specific workloads, e.g. GPU). **node/pod affinity** are scheduling rules ("run near/away from X"). CA must respect all of these in its simulation, or it would add nodes that still can't host the pending pods.

**Scale-DOWN loop:**
1. CA looks for nodes whose utilization is below `--scale-down-utilization-threshold` (default **0.5** = 50%) for a sustained period.
2. It checks the node has been underutilized for `--scale-down-unneeded-time` (default **10 min**).
3. It **simulates evicting** all the node's pods elsewhere; if every pod can be rescheduled and no blocking conditions exist, the node is marked for removal.
4. CA **cordons & drains** the node (evicting pods, honoring PodDisruptionBudgets and graceful termination), then tells the cloud to terminate the VM.

> **Beginner aside — cordon / drain / PodDisruptionBudget.** **Cordon** marks a node unschedulable (no new pods). **Drain** evicts existing pods so they reschedule elsewhere. A **PodDisruptionBudget (PDB)** caps how many replicas of a workload may be down simultaneously (e.g. `minAvailable: 2`), so draining can't take down your whole service at once.

**Pods that BLOCK scale-down (very common gotcha):**
- Pods with **no controller** (bare pods, not managed by a Deployment/Job).
- Pods using **local storage** (`emptyDir`/hostPath) unless annotated to allow eviction.
- Pods that **don't fit elsewhere** (would just move the unschedulable problem).
- Pods with **restrictive PDBs** that would be violated.
- Pods/nodes annotated `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"` or nodes annotated `scale-down-disabled`.

**Scale-from-zero.** CA can scale a node group from **0 nodes**. The challenge: with zero nodes running, CA doesn't *know* the shape (CPU/memory/labels/taints) of a node that would appear. It infers it from the node group's template / cloud tags. On AWS, you tag the ASG (e.g. `k8s.io/cluster-autoscaler/node-template/label/...` and `.../taint/...` and resource hints) so CA can simulate scheduling against the *would-be* node. Scale-to-zero of node groups is great for expensive, rarely-used pools (GPU, batch).

#### 3.4.1 Karpenter (the modern alternative — vendor-specific to AWS, expanding)

**Karpenter** replaces CA's "pick a pre-defined node group" model with **just-in-time node provisioning**: it looks at the *exact* pending pods and provisions a *right-sized* instance (any instance type that fits) directly, without predefined ASGs. It also does **consolidation** — actively repacking pods onto fewer/cheaper nodes (including spot) to cut cost.

| Aspect | Cluster Autoscaler | Karpenter |
|---|---|---|
| Node selection | From predefined node groups (ASG/MIG/VMSS) | Picks instance type per-pod-fit, from a broad allowlist |
| Provisioning speed | Slower (goes through ASG) | Faster (talks to EC2 fleet directly) |
| Bin-packing / consolidation | Limited | First-class (active consolidation) |
| Portability | All major clouds | Primarily AWS (Azure provider emerging) |
| Config object | `--nodes`/ASG tags | `NodePool` + `EC2NodeClass` CRDs |

Flag Karpenter as **AWS-centric** today, though the project is becoming multi-cloud.

### 3.5 KEDA internals

**KEDA** (CNCF project) adds two things core Kubernetes lacks: **event-driven metrics** and **scale-to-zero**.

> **Beginner aside — CNCF.** The **Cloud Native Computing Foundation** hosts Kubernetes and many ecosystem projects (Prometheus, Envoy, KEDA, …). "CNCF graduated/incubating" signals maturity/governance.

**Architecture:**
1. **`ScaledObject` / `ScaledJob` CRDs** — you declare a **trigger** (a *scaler*) like Kafka, RabbitMQ, AWS SQS, Prometheus, cron, etc., plus min/max replicas.

   > **Beginner aside — CRD & operator.** A **Custom Resource Definition (CRD)** extends the Kubernetes API with new object types. An **operator/controller** watches those objects and reconciles real state. KEDA is an operator that watches `ScaledObject`s.

2. **KEDA Operator** — for each `ScaledObject`, it **creates and manages a standard HPA** under the hood (targeting your Deployment). KEDA does *not* reimplement the scaling math — it leans on HPA. It also flips replicas between 0 and 1 (the part HPA can't do alone).
3. **KEDA Metrics Server** — implements the **External Metrics API**, so the HPA KEDA created can read the scaler's value (e.g. Kafka lag) as an external metric. The HPA formula then applies normally.

**Scale-to-zero flow (the headline feature):**
- HPA's native `minReplicas` must be ≥ 1 (HPA alone can't reach 0). KEDA's operator watches the trigger directly; when the source is **empty** (e.g. zero Kafka lag) for the cooldown period, KEDA sets the Deployment's replicas to **0**. When the first event arrives (lag > 0), KEDA bumps it to **1**, after which HPA takes over for 1→N. `cooldownPeriod` (default **300 s**) controls how long the metric must stay at zero before scaling to 0.

> **Beginner aside — Kafka consumer lag.** Kafka is a distributed log; messages live in **partitions** within a **topic**. A **consumer group** reads them; its **offset** is how far it has read. **Lag** = (latest offset) − (committed offset) = unprocessed backlog. Scaling consumers by lag means: more backlog → more consumer pods (up to the partition count — beyond that, extra pods sit idle because each partition is consumed by at most one member of the group).

**Worked KEDA logic (Kafka):** with `lagThreshold: 100` and lag = 1,000 across a 12-partition topic: desired ≈ ceil(1000/100) = 10 consumers, but **capped at min(maxReplicaCount, partitions=12)**. KEDA's Kafka scaler applies the partition cap automatically.

### 3.6 How the three (four) interact end-to-end

A realistic sequence when traffic spikes on an HPA-managed JVM API:

1. RPS climbs → CPU per pod rises past target → **HPA** computes more replicas, writes `replicas: 12`.
2. Deployment controller creates 4 new pods → they go `Pending` (no node room).
3. **Cluster Autoscaler/Karpenter** sees unschedulable pods → provisions nodes (1–10 min on cloud).
4. New node registers → scheduler binds the pending pods → they pull images, start the JVM, warm up, pass readiness → join the Service.
5. Meanwhile **VPA** (if in `Off` mode) has been recording usage and may recommend a larger request next deploy.
6. Traffic recedes → HPA waits out the 5-min scale-down stabilization window, then shrinks replicas; CA later removes now-idle nodes after 10 min underutilized.

The **total latency to serve more traffic** = HPA reaction (≤15 s) + node provisioning (minutes if CA was needed) + image pull + JVM cold start + readiness. The node-provisioning leg dominates — which is why teams keep **headroom / overprovisioning** (§7.4).

---

## 4. The complete toolkit

### 4.1 HPA object (`autoscaling/v2`) — fields

| Field | Purpose | Default / notes |
|---|---|---|
| `spec.scaleTargetRef` | The workload to scale (Deployment/StatefulSet/CRD with `/scale`) | required |
| `spec.minReplicas` | Floor | default **1** (must be ≥1 unless `HPAScaleToZero` gate + KEDA) |
| `spec.maxReplicas` | Ceiling | required |
| `spec.metrics[]` | List of metric sources (`Resource`/`ContainerResource`/`Pods`/`Object`/`External`) | desired = max across them |
| `metrics[].resource.target.type` | `Utilization` or `AverageValue` | — |
| `metrics[].resource.target.averageUtilization` | % of request to target | e.g. `50` |
| `spec.behavior.scaleUp/scaleDown` | rate limits & stabilization | up: window **0s**; down: window **300s** |
| `behavior.*.policies[]` | `type: Pods|Percent`, `value`, `periodSeconds` | — |
| `behavior.*.selectPolicy` | `Max|Min|Disabled` | up→Max, down→Min |
| `status.currentReplicas/desiredReplicas/currentMetrics` | observed decision state | read-only |

**Controller flags (kube-controller-manager — self-managed clusters only):**

| Flag | Default | Effect |
|---|---|---|
| `--horizontal-pod-autoscaler-sync-period` | 15s | reconcile cadence |
| `--horizontal-pod-autoscaler-tolerance` | 0.1 | ±10% no-op band |
| `--horizontal-pod-autoscaler-downscale-stabilization` | 5m | global scale-down stabilization (overridable per-HPA via `behavior`) |
| `--horizontal-pod-autoscaler-cpu-initialization-period` | 5m | ignore CPU of just-started pods |
| `--horizontal-pod-autoscaler-initial-readiness-delay` | 30s | grace before counting a pod's metrics |

### 4.2 VPA objects & components

| Object/component | Purpose |
|---|---|
| `VerticalPodAutoscaler` CR | declares `targetRef`, `updatePolicy.updateMode`, `resourcePolicy` (per-container min/max, controlled resources) |
| `updateMode` | `Off` / `Initial` / `Recreate` / `Auto` |
| `resourcePolicy.containerPolicies[].minAllowed/maxAllowed` | bound the recommendation per container |
| `resourcePolicy...controlledResources` | which of `cpu`/`memory` VPA may set |
| `resourcePolicy...controlledValues` | `RequestsOnly` or `RequestsAndLimits` |
| **vpa-recommender** | emits recommendations into `status.recommendation` |
| **vpa-updater** | evicts out-of-bound pods |
| **vpa-admission-controller** | mutates pod requests on creation |

Inspect with: `kubectl describe vpa <name>` → shows `Target`, `Lower Bound`, `Upper Bound`, `Uncapped Target` per container.

### 4.3 Cluster Autoscaler flags (commonly tuned)

| Flag | Default | Purpose |
|---|---|---|
| `--scan-interval` | 10s | how often CA evaluates |
| `--scale-down-utilization-threshold` | 0.5 | node considered underused below this |
| `--scale-down-unneeded-time` | 10m | how long a node must be idle before removal |
| `--scale-down-delay-after-add` | 10m | don't scale down right after scaling up |
| `--scale-down-delay-after-delete` | scan-interval | cooldown after a delete |
| `--scale-down-delay-after-failure` | 3m | backoff after a failed scale-down |
| `--max-node-provision-time` | 15m | give up waiting on a stuck node |
| `--expander` | `random` | node-group choice strategy (see §4.4) |
| `--balance-similar-node-groups` | false | spread across similar groups (multi-AZ) |
| `--max-nodes-total` | — | hard cap on cluster size |

**Useful annotations:**
- `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"` (on a pod) → don't drain me, blocks scale-down of my node.
- `cluster-autoscaler.kubernetes.io/scale-down-disabled: "true"` (on a node) → never remove this node.

### 4.4 CA expander strategies

| Expander | Picks the node group that… |
|---|---|
| `random` | random eligible group (default) |
| `most-pods` | schedules the most pending pods |
| `least-waste` | leaves the least idle CPU/mem after the add (best bin-packing) |
| `price` | is cheapest (cloud-dependent) |
| `priority` | matches your configured priority list (ConfigMap) |

### 4.5 KEDA objects & key scalers

| Object | Purpose |
|---|---|
| `ScaledObject` | scales a **Deployment/StatefulSet** (0↔N), manages an HPA |
| `ScaledJob` | scales **Jobs** (one Job per queued item — good for long, non-idempotent tasks) |
| `TriggerAuthentication` / `ClusterTriggerAuthentication` | how a scaler authenticates to the source (secrets, IAM, etc.) |

| Field (`ScaledObject`) | Default | Purpose |
|---|---|---|
| `minReplicaCount` | 0 | floor (0 = scale to zero) |
| `maxReplicaCount` | 100 | ceiling |
| `pollingInterval` | 30s | how often KEDA queries the source |
| `cooldownPeriod` | 300s | idle time before scaling to 0 |
| `idleReplicaCount` | (unset) | optional distinct "idle" floor (e.g. 0) vs active floor |
| `advanced.horizontalPodAutoscalerConfig.behavior` | — | pass-through HPA `behavior` |
| `fallback` | — | replicas to use if the scaler errors |

Popular scalers: **kafka**, **rabbitmq**, **aws-sqs-queue**, **aws-cloudwatch**, **azure-servicebus**, **gcp-pubsub**, **prometheus**, **cron**, **redis (list/stream)**, **postgresql/mysql** (query result).

### 4.6 CLI cheat-commands

```bash
# Resource metrics (needs metrics-server)
kubectl top pods
kubectl top nodes

# HPA
kubectl get hpa
kubectl describe hpa my-api            # shows current metrics, decisions, conditions
kubectl autoscale deploy my-api --cpu-percent=50 --min=2 --max=10   # quick imperative HPA

# Inspect why a pod is Pending (CA trigger)
kubectl get pods --field-selector=status.phase=Pending
kubectl describe pod <pending-pod>     # Events: "0/3 nodes available: insufficient cpu"

# VPA recommendation
kubectl describe vpa my-api

# Cluster Autoscaler status (configmap it writes)
kubectl -n kube-system get configmap cluster-autoscaler-status -o yaml

# KEDA
kubectl get scaledobject
kubectl describe scaledobject my-consumer
kubectl get hpa    # KEDA-created HPA appears as keda-hpa-<name>
```

---

## 5. Code examples by use case

### 5.1 Baseline CPU HPA for a JVM REST service (with tuned behavior)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: orders-api }
spec:
  replicas: 3
  selector: { matchLabels: { app: orders-api } }
  template:
    metadata: { labels: { app: orders-api } }
    spec:
      containers:
      - name: app
        image: registry.example.com/orders-api:1.4.2
        resources:
          requests: { cpu: "500m", memory: "768Mi" }  # HPA % is measured against this CPU
          limits:   {            memory: "768Mi" }     # NOTE: no CPU limit on purpose (see §7.6)
        readinessProbe:                                # keep cold pods out of the load-balancer
          httpGet: { path: /actuator/health/readiness, port: 8080 }
          initialDelaySeconds: 20
          periodSeconds: 5
        startupProbe:                                  # JVM warmup gate (don't kill slow starters)
          httpGet: { path: /actuator/health/liveness, port: 8080 }
          failureThreshold: 30
          periodSeconds: 5
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: orders-api }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: orders-api }
  minReplicas: 3
  maxReplicas: 30
  metrics:
  - type: Resource
    resource:
      name: cpu
      target: { type: Utilization, averageUtilization: 60 }  # scale when avg CPU > 60% of request
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0          # grab capacity instantly
      policies:
      - { type: Percent, value: 100, periodSeconds: 30 }  # at most double every 30s
      - { type: Pods,    value: 4,   periodSeconds: 30 }  # ...or +4 pods/30s
      selectPolicy: Max
    scaleDown:
      stabilizationWindowSeconds: 300        # release capacity cautiously (5 min)
      policies:
      - { type: Pods, value: 1, periodSeconds: 120 }      # remove 1 pod / 2 min
      selectPolicy: Min
```

Why these choices: 60% target leaves CPU headroom for the bursts a JVM exhibits during GC and JIT; no CPU limit avoids throttling pauses; the slow, 1-pod-per-2-min scale-down prevents dropping connections during traffic troughs.

### 5.2 Request-rate (custom-metric) HPA via Prometheus Adapter

Scale on **HTTP requests per second per pod** instead of CPU — more directly tied to user-facing load.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: orders-api-rps }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: orders-api }
  minReplicas: 3
  maxReplicas: 50
  metrics:
  - type: Pods
    pods:
      metric: { name: http_requests_per_second }   # exposed by Prometheus Adapter
      target: { type: AverageValue, averageValue: "50" }  # aim for ~50 rps per pod
```

The Prometheus Adapter config that produces that metric (PromQL rate over 2 minutes):

```yaml
# prometheus-adapter values (rules.custom)
rules:
- seriesQuery: 'http_server_requests_seconds_count{namespace!="",pod!=""}'
  resources: { overrides: { namespace: {resource: "namespace"}, pod: {resource: "pod"} } }
  name: { matches: "^http_server_requests_seconds_count", as: "http_requests_per_second" }
  metricsQuery: 'sum(rate(<<.Series>>{<<.LabelMatchers>>}[2m])) by (<<.GroupBy>>)'
```

> **Beginner aside — `rate()` and the metric name.** `http_server_requests_seconds_count` is the default Spring Boot / Micrometer counter for HTTP requests. `rate(...[2m])` computes per-second increase over a 2-minute window. Summed per pod, it yields per-pod RPS. Tying HPA to RPS makes scaling track *traffic* rather than the noisier CPU signal.

### 5.3 KEDA: scale a Kafka consumer (Java) by lag, down to zero

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: payment-consumer }
spec:
  scaleTargetRef: { name: payment-consumer }   # the Deployment
  minReplicaCount: 0                            # scale to zero when no lag
  maxReplicaCount: 12                           # never exceed partition count
  pollingInterval: 15                           # check lag every 15s
  cooldownPeriod: 120                           # wait 2 min of zero lag before going to 0
  triggers:
  - type: kafka
    metadata:
      bootstrapServers: kafka.svc:9092
      consumerGroup: payment-consumer-group
      topic: payments
      lagThreshold: "200"                       # target ~200 messages backlog per pod
      offsetResetPolicy: latest
    authenticationRef: { name: kafka-auth }     # references a TriggerAuthentication
```

Java consumer note: each replica is one member of the consumer group; Kafka rebalances partitions across the live members. Because a partition is owned by one member, **`maxReplicaCount` should not exceed the partition count** or extra pods idle. Make the consumer **idempotent** and commit offsets *after* processing, so KEDA scale-downs/rebalances don't reprocess or drop messages.

### 5.4 KEDA `ScaledJob` for long batch tasks from SQS

When each message means a long, non-restartable job, use one Job per message rather than scaling a long-lived Deployment:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledJob
metadata: { name: video-transcode }
spec:
  jobTargetRef:
    template:
      spec:
        containers:
        - name: transcode
          image: registry.example.com/transcoder:2.1
        restartPolicy: Never
  minReplicaCount: 0
  maxReplicaCount: 50
  pollingInterval: 20
  successfulJobsHistoryLimit: 5
  failedJobsHistoryLimit: 5
  triggers:
  - type: aws-sqs-queue
    metadata:
      queueURL: https://sqs.us-east-1.amazonaws.com/123/transcode-jobs
      queueLength: "1"           # one Job per message
      awsRegion: us-east-1
    authenticationRef: { name: sqs-auth }
```

### 5.5 VPA in recommendation-only mode (right-sizing without disruption)

```yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata: { name: orders-api-vpa }
spec:
  targetRef: { apiVersion: apps/v1, kind: Deployment, name: orders-api }
  updatePolicy: { updateMode: "Off" }      # ONLY recommend; never evict. Safe with HPA-on-CPU.
  resourcePolicy:
    containerPolicies:
    - containerName: app
      controlledResources: ["cpu", "memory"]
      minAllowed: { cpu: "250m", memory: "512Mi" }
      maxAllowed: { cpu: "4",    memory: "4Gi" }
```

Read the advice and apply it to your Deployment manifest yourself:
```bash
kubectl describe vpa orders-api-vpa
#  Target:      cpu: 740m,  memory: 1100Mi   ← set requests near here
#  Lower Bound: cpu: 520m,  memory: 900Mi
#  Upper Bound: cpu: 1200m, memory: 1600Mi
```

### 5.6 Safe combo: HPA on RPS + VPA on CPU/memory

Because HPA acts on RPS (an external/custom signal) and VPA acts on CPU/memory (disjoint), they don't form a feedback loop. Use §5.2's HPA with §5.5's VPA — but here set VPA to `Auto` only if you accept pod restarts:

```yaml
# (same RPS-based HPA as 5.2) + VPA in Auto on CPU/memory
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata: { name: orders-api-vpa-auto }
spec:
  targetRef: { apiVersion: apps/v1, kind: Deployment, name: orders-api }
  updatePolicy: { updateMode: "Auto" }     # OK: VPA changes CPU/mem, HPA scales on RPS
  resourcePolicy:
    containerPolicies:
    - containerName: app
      controlledValues: RequestsOnly       # don't let VPA set limits (avoid surprise throttling)
```

### 5.7 Cluster Autoscaler with a scale-from-zero GPU pool (AWS ASG tags)

Tag the ASG so CA can simulate against a node that doesn't exist yet:

```
# ASG tags (key = value)
k8s.io/cluster-autoscaler/enabled                                   = true
k8s.io/cluster-autoscaler/my-cluster                                = owned
k8s.io/cluster-autoscaler/node-template/label/workload              = gpu
k8s.io/cluster-autoscaler/node-template/taint/nvidia.com/gpu        = present:NoSchedule
k8s.io/cluster-autoscaler/node-template/resources/nvidia.com/gpu    = 1
```

A GPU pod with a matching toleration + nodeSelector `workload=gpu` will trigger CA to grow the GPU ASG from 0 → 1; when the job finishes and the node sits idle past `--scale-down-unneeded-time`, CA returns it to 0.

### 5.8 Overprovisioning to mask node cold-start (pause-pod trick)

Keep a buffer of low-priority "balloon" pods that get evicted the instant real pods need room — so CA has already provisioned spare capacity.

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: overprovisioning }
value: -10              # NEGATIVE: lower than default (0); evicted first
globalDefault: false
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: overprovisioning }
spec:
  replicas: 4
  selector: { matchLabels: { app: overprovisioning } }
  template:
    metadata: { labels: { app: overprovisioning } }
    spec:
      priorityClassName: overprovisioning
      containers:
      - name: pause
        image: registry.k8s.io/pause:3.9     # does nothing, just reserves resources
        resources:
          requests: { cpu: "1", memory: "1Gi" }   # sized to ~one real pod's footprint
```

> **Beginner aside — PriorityClass & preemption.** A **PriorityClass** assigns a numeric priority to pods. When a high-priority pod can't be scheduled, the scheduler may **preempt** (evict) lower-priority pods to make room. Negative-priority balloon pods are evicted instantly when real workloads arrive, and their eviction leaves *already-running* node capacity free — so real pods schedule immediately while CA replaces the balloons in the background. This trades steady-state cost for faster scale-up.

---

## 6. Implementation concerns & best practices

### 6.1 Setting requests so autoscaling works (the linchpin)

- **CPU request = the value at which `usage/request` is a meaningful percentage.** Pick a request near the pod's *typical* steady-state CPU, then choose an HPA target (e.g. 60%) that leaves burst headroom. Requests far above real usage make HPA scale too late (utilization always low); far below makes it scale too early/constantly.
- **Memory request ≈ working set with margin** (JVM: heap + metaspace + thread stacks + off-heap/native + code cache + a buffer). HPA on memory is risky (memory rarely drops, so it ratchets up and never scales down) — most teams scale on CPU/RPS and only *limit* memory.
- **Use VPA in `Off` mode to discover good requests** from real traffic, then bake them in.
- **Guaranteed QoS for latency-critical pods** (requests == limits) to minimize eviction risk — but beware CPU limits + throttling (§7.6).

### 6.2 Performance

- HPA's `sync-period` (15 s) + metrics-server scrape (~15 s) means up to ~30 s before HPA *reacts* to a spike. Add node-provisioning time when CA is involved. Don't expect sub-second elasticity from HPA.
- KEDA's `pollingInterval` (default 30 s) gates event responsiveness; lower it (e.g. 5–15 s) for spiky queues, mindful of source load.
- For very fast reactions to external bursts, KEDA + overprovisioning beats waiting on cloud node boot.

### 6.3 Correctness / concurrency

- **Idempotency for scaled consumers:** scaling Kafka/SQS workers triggers rebalances; messages can be redelivered. Design handlers to be idempotent and commit offsets after side effects.
- **Graceful shutdown:** on scale-down, pods get `SIGTERM` then (after `terminationGracePeriodSeconds`, default 30 s) `SIGKILL`. Implement `preStop` hooks / drain logic so in-flight requests finish and the pod deregisters from the load balancer before exit. For Spring Boot, enable graceful shutdown (`server.shutdown=graceful`) and a readiness flip on SIGTERM.
- **PodDisruptionBudgets** to bound how many replicas scale-down/drain can remove at once.

### 6.4 Observability

Watch these signals:
- `kubectl describe hpa` conditions: `AbleToScale`, `ScalingActive`, `ScalingLimited` (the last means you hit min/max — a sign to widen bounds or fix the metric).
- HPA metrics in the controller (`horizontalpodautoscaler_status_*`), Prometheus `kube_horizontalpodautoscaler_status_*` via kube-state-metrics.
- CA: `cluster-autoscaler-status` ConfigMap, and logs for "scale up" / "scale down" / "pod didn't trigger scale-up" lines.
- KEDA exposes Prometheus metrics for active triggers and metric values.
- Dashboards: desired vs current replicas, metric value vs target, pending-pod count, node count, throttling (`container_cpu_cfs_throttled_periods_total`).

### 6.5 Cost

- HPA/VPA right-sizing cuts requests → better bin-packing → fewer nodes. The biggest cost lever is usually **node-level** (CA scale-down threshold, Karpenter consolidation, spot instances).
- Beware aggressive scale-down on nodes you'll just re-add minutes later — the churn costs API load, image pulls, and JVM warmups. Tune `--scale-down-unneeded-time` and stabilization windows for *your* traffic shape.

### 6.6 Testing

- **Load-test the autoscaler, not just the app:** ramp traffic (k6/Gatling/Locust) and assert replica count, p99 latency, and that no requests drop during scale events.
- **Chaos:** kill nodes and confirm CA backfills; verify PDBs hold.
- **Scale-to-zero:** confirm cold start latency is acceptable from a user's perspective (first request after idle).

### 6.7 Production hardening checklist

- Set realistic `requests` (validated by VPA-Off recommendations).
- `minReplicas ≥ 2` for HA (single-replica HPA has no redundancy).
- Tune `behavior` per workload (fast up, slow down).
- PDBs on every scalable workload.
- `maxReplicas`/`max-nodes-total` to cap blast radius and runaway cost.
- Readiness/startup probes so cold JVMs don't take traffic or get killed during warmup.
- Avoid HPA(CPU)+VPA(CPU) on the same workload.
- Keep overprovisioning headroom sized to your worst node-provision latency.

### 6.8 Anti-patterns

| Anti-pattern | Why it bites |
|---|---|
| HPA on memory for a JVM | Heap rarely shrinks → replicas ratchet up, never down |
| No requests set | HPA utilization is undefined; pods are BestEffort, evicted first |
| CPU limit == request on latency-critical service | CFS throttling causes tail-latency spikes |
| HPA(CPU) + VPA(Auto, CPU) together | Feedback loop, oscillation |
| `maxReplicas` huge with no node cap | One bad metric can melt your cloud bill |
| Scale-down stabilization too short | Flapping, connection churn, JVM re-warmup cost |
| `maxReplicaCount` > Kafka partitions | Idle consumer pods, wasted money |
| Relying on HPA for instant burst absorption | Node provisioning is minutes; you need headroom |

---

## 7. Advanced topics & deep internals

### 7.1 The missing-metric & unready bias, precisely

When some pods lack metrics, HPA splits pods into *ready-with-metrics*, *unready*, and *missing-metric* sets. It computes the ratio on the first set, then **adds back** the missing/unready pods with worst-case assumptions: for a *scale-up* candidate it assumes those pods contribute **0%** (so they don't justify adding more), and for a *scale-down* candidate it assumes **100%** (so they prevent over-shrinking). This conservative padding is why HPA sometimes "won't scale down" while pods are still initializing.

### 7.2 Stabilization window math

The window keeps a sliding buffer of past *recommendations*. The applied value when scaling **down** is `max(recommendations in window)`; when scaling **up** it's `min(recommendations in window)`. So a 300 s down-window means HPA must see a lower recommendation sustained for the full 5 minutes before it shrinks. Setting `scaleDown.stabilizationWindowSeconds: 0` makes scale-down immediate (rarely wise for user-facing services).

### 7.3 `AverageValue` vs `Utilization`

`Utilization` is request-relative (breaks if requests change — hence VPA conflict). `AverageValue` is absolute (e.g. "500m CPU per pod" or "50 rps per pod") and is **immune** to request changes — making it the safer choice when VPA also runs, and the natural choice for custom/external metrics that have no notion of a request.

### 7.4 Cold-start & scale-up latency — full anatomy

End-to-end "spike to served" latency stacks:
1. **Detection**: metrics scrape (≤15 s) + HPA sync (≤15 s) ≈ up to 30 s.
2. **Scheduling**: instant if room exists; else CA/Karpenter node provision (cloud-dependent: ~30–120 s Karpenter, often 2–10 min CA).
3. **Image pull**: 0 (cached) to tens of seconds (cold cache, large image). Mitigate with image pre-pulling/`imagePullPolicy` and smaller images.
4. **JVM cold start**: classloading + JIT warmup. A Spring Boot app may need many seconds before steady-state throughput; until JIT compiles hot paths, latency is higher. Mitigations: **CDS/AppCDS** (Class Data Sharing) to speed classloading, **CRaC** (Coordinated Restore at Checkpoint) or GraalVM **native image** to slash startup, and adequate `startupProbe.failureThreshold` so the platform doesn't kill a slow-warming JVM.
5. **Readiness**: don't route traffic until warm — use a readiness probe that only passes after warmup (optionally after a synthetic warm-up request loop).

> **Beginner aside — JIT, AppCDS, CRaC, GraalVM native.** The JVM **JIT** (Just-In-Time compiler) compiles hot bytecode to machine code at runtime, so apps get faster as they "warm up." **AppCDS** caches parsed class metadata to disk to speed startup. **CRaC** snapshots a fully-warmed JVM and restores it in milliseconds. **GraalVM native image** compiles ahead-of-time to a standalone binary with near-instant startup (at the cost of peak throughput and build complexity). All four reduce horizontal-scale cold-start pain for JVM services.

### 7.5 Scale-from-zero subtleties

- **CA**: needs node-group templates/tags to simulate a phantom node's resources/labels/taints (§5.7). Without them it won't scale a zero-size group for a pod that needs special placement.
- **KEDA scale-to-zero**: the *first* request after idle pays full cold start (node maybe + image + JVM). For user-facing endpoints, scale-to-zero is usually reserved for internal/async workloads; for sync APIs, keep `minReplicaCount: 1` or use overprovisioning.

### 7.6 CPU limits & throttling — the JVM-specific trap

With a CPU limit, the kernel's CFS bandwidth controller can **throttle** the container even when the node has spare CPU, because the limit caps per-period quota. For multi-threaded JVMs, threads can exhaust the quota early in a 100 ms period and then **stall** for the remainder, producing periodic latency spikes invisible in average CPU. Symptoms: high `container_cpu_cfs_throttled_periods_total`, bimodal latency. Mitigations: **remove CPU limits** (rely on requests + scheduling), or set generous limits, and right-size thread pools (e.g., `-XX:ActiveProcessorCount` reflecting the *limit*, not the node, so the JVM doesn't size pools for 64 cores when it has 1). Modern JVMs are container-aware (`UseContainerSupport`, default on since JDK 10/8u191) and read cgroup limits for `availableProcessors()` and heap sizing — but a too-low CPU limit then under-sizes thread pools.

### 7.7 HPA on multiple & external metrics together

You can mix CPU + RPS + Kafka lag in one HPA; desired = max of all. This is useful for services that are both CPU-bound *and* queue-fed. Watch for the metric that "always wins" — it effectively becomes your scaler and the others are dead weight.

### 7.8 Karpenter consolidation & disruption budgets

Karpenter's **consolidation** actively deletes/replaces nodes to bin-pack tighter or move to cheaper instances/spot. Guard it with **disruption budgets** and PDBs so consolidation doesn't churn your fleet during business hours. `do-not-disrupt` annotations protect specific pods. Flag as AWS-specific and version-sensitive (Karpenter v1 changed several APIs from beta).

### 7.9 Predictive / scheduled scaling

- **Scheduled scaling**: KEDA `cron` trigger, or set `minReplicas` higher during known peaks (e.g., pre-scale before a marketing event). Reactive autoscaling alone can't beat node-provisioning latency for a sudden, anticipated spike — pre-scale instead.
- **Predictive** (vendor): some platforms offer ML-based predictive HPA; treat as proprietary and validate carefully.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which autoscaler for which problem

| Situation | Reach for | Avoid |
|---|---|---|
| Stateless API, load ∝ CPU | **HPA on CPU** | VPA-Auto on CPU simultaneously |
| Load ∝ request rate, CPU noisy | **HPA on RPS** (custom metric) | HPA on memory |
| Don't know correct requests | **VPA `Off`** to recommend | VPA `Auto` before you trust it |
| Per-request work varies wildly | **VPA** (size pods) ± HPA on a non-CPU metric | HPA-only |
| Queue/event-driven worker | **KEDA** (lag/queue depth) | plain HPA (can't read the source) |
| Rarely-used internal service | **KEDA scale-to-zero** | scale-to-zero on a sync user API |
| Cluster ran out of node room | **CA / Karpenter** | nothing (pods stay Pending) |
| Need fast burst absorption | **Overprovisioning** + HPA | relying on cloud node boot time |
| Spot/cost optimization | **Karpenter consolidation** | aggressive CA scale-down causing churn |

### 8.2 Horizontal vs vertical

| | Horizontal (HPA/KEDA) | Vertical (VPA) |
|---|---|---|
| Adds | replicas | CPU/mem per pod |
| Disruption | low (new pods) | high (pod restart, unless in-place) |
| Limit | external deps, max replicas, partitions | single-node max instance size |
| Best for | stateless, parallelizable load | hard-to-parallelize / per-pod-intensive |
| Latency impact | new pod cold start | brief restart |
| Combine? | with VPA on **disjoint** signals only |

### 8.3 CA vs Karpenter (decision)

Use **Karpenter** on AWS when you want fast, right-sized, cost-optimized nodes and active consolidation, and can adopt its CRDs. Use **Cluster Autoscaler** for multi-cloud portability, conservative behavior, or when you must stick to predefined node groups for compliance/operational reasons.

### 8.4 "Use when / avoid when" quick rules

- **HPA — use when** the metric is a smooth, per-pod-divisible signal; **avoid when** load isn't parallelizable or the only signal is memory.
- **VPA Auto — use when** you accept restarts and run no CPU/mem HPA on the same workload; **avoid when** the service is latency-critical and can't tolerate eviction, or HPA shares the metric.
- **KEDA — use when** the trigger lives outside the cluster or you need scale-to-zero; **avoid when** a built-in Resource metric already does the job (don't add a dependency for nothing).
- **CA/Karpenter — always**, unless you deliberately run fixed-size clusters.

---

## 9. Failure modes & debugging

### 9.1 "HPA shows `<unknown>` / won't scale"

**Symptom:** `kubectl get hpa` → `TARGETS: <unknown>/60%`.
**Cause:** metrics-server missing/unhealthy, or the container has **no CPU request**.
**Diagnose:**
```bash
kubectl top pods                       # if this errors, metrics-server is the problem
kubectl describe hpa my-api            # look for "unable to fetch metrics" / "missing request for cpu"
kubectl get deploy -n kube-system metrics-server
kubectl logs -n kube-system deploy/metrics-server
```
**Fix:** install/repair metrics-server (commonly needs `--kubelet-insecure-tls` in dev clusters or proper certs); set CPU requests on the container.

### 9.2 "HPA scaled up but pods stuck `Pending`"

**Cause:** no node room and CA didn't (or couldn't) add a node.
**Diagnose:**
```bash
kubectl describe pod <pending>         # Events: "0/5 nodes are available: insufficient cpu"
kubectl -n kube-system logs deploy/cluster-autoscaler | grep -i "scale.up\|didn't trigger"
kubectl -n kube-system get cm cluster-autoscaler-status -o yaml
```
**Common reasons CA won't scale up:** node group at `max-nodes`; pod requests larger than any node type; nodeSelector/affinity/taint mismatch (no group matches); missing scale-from-zero tags; quota/limits at the cloud provider.

### 9.3 "Cluster never scales down"

**Cause:** a pod on the node blocks drain.
**Diagnose:** CA logs show `"node ... not removed: pod X blocks"`. Look for: kube-system pods without `safe-to-evict`, pods with local storage, restrictive PDBs, bare pods, or the node annotated `scale-down-disabled`.
**Fix:** add `safe-to-evict: "true"` where appropriate, loosen PDBs, move blocking system pods to a dedicated node group.

### 9.4 "Replicas oscillate (flapping)"

**Cause:** target near steady-state load + short stabilization, or HPA+VPA on same metric.
**Fix:** widen tolerance/stabilization window for scale-down; ensure you're not running VPA-Auto on the HPA's metric; consider `AverageValue` targets.

### 9.5 "Latency spikes under load despite low average CPU"

**Cause:** **CFS throttling** from CPU limits (§7.6).
**Diagnose:**
```bash
# throttled periods climbing = throttling
kubectl exec <pod> -- cat /sys/fs/cgroup/cpu.stat   # nr_throttled, throttled_usec (cgroup v2)
# or via Prometheus:
#   rate(container_cpu_cfs_throttled_periods_total[5m]) / rate(container_cpu_cfs_periods_total[5m])
```
**Fix:** remove/raise CPU limits; align JVM thread pools to the limit (`-XX:ActiveProcessorCount`).

### 9.6 "KEDA consumer won't scale past N / scales to zero too eagerly"

- **Capped at partition count:** expected for Kafka — `maxReplicaCount` beyond partitions is wasted.
- **Scales to zero then thrashes:** raise `cooldownPeriod`; check `pollingInterval`; verify the lag metric reads correctly (`kubectl describe scaledobject`, check the KEDA-created HPA `keda-hpa-*`).

### 9.7 "VPA keeps restarting my pods"

**Cause:** `updateMode: Auto/Recreate` evicting pods as recommendations drift.
**Fix:** switch to `Off` (recommend-only) or `Initial`; set `minAllowed`/`maxAllowed` to stop oscillation; ensure PDBs limit simultaneous evictions; wait for/adopt in-place resize where available.

### 9.8 Real-world incident patterns

- **The "free fall" scale-down:** scale-down stabilization set too low; a midnight traffic dip drops replicas hard, then a sudden batch job hammers the few survivors before scale-up + node provisioning catch up → cascading latency. Fix: longer down-window + overprovisioning.
- **The "request typo" outage:** someone set `cpu: 5` (5 cores) request instead of `500m`; pods became unschedulable, HPA tried to add more, CA spun up giant nodes, cloud bill spiked. Fix: admission validation/limits on requests, `max-nodes-total`.
- **VPA+HPA death spiral:** both on CPU; replica count and pod size oscillated for hours. Fix: separate the signals.
- **Kafka over-scaled:** `maxReplicaCount: 100` on a 12-partition topic; 88 idle pods cost money and added rebalance churn. Fix: cap at partition count.

---

## 10. Interview drill

**Q1. Write the HPA scaling formula and walk through it.**
*Answer:* `desiredReplicas = ceil(currentReplicas × currentMetric/desiredMetric)`, where current metric is the average across ready pods, and a ±10% tolerance band suppresses small changes. Example: 4 pods @ 90% CPU, target 50% → ceil(4×1.8)=8.
- *Probe — why ceil?* Always round toward more capacity to avoid under-provisioning.
- *Probe — what's the tolerance?* Default 0.1; within `[0.9,1.1]` ratio HPA does nothing, preventing jitter.
- *Probe — multiple metrics?* Compute per metric, take the max.

**Q2. Why does HPA scale on a percentage of requests, and what breaks if requests are wrong?**
*Answer:* Utilization = usage ÷ request. Too-high a request → utilization always low → never scales (under-provision under load). Too-low → constant scaling/flapping. Requests calibrate the controller's perception.
- *Probe — how to find good requests?* Run VPA in `Off` mode and read the recommendation.
- *Probe — `AverageValue` vs `Utilization`?* `AverageValue` is request-independent — safer with VPA, natural for custom metrics.

**Q3. Why can't you run HPA and VPA on CPU for the same Deployment?** *(senior-signal)*
*Answer:* They form a feedback loop: VPA raises the CPU request → measured utilization drops → HPA scales **down** even though real load is unchanged; lowering requests inflates utilization → HPA scales up. They oscillate. Allowed instead: HPA on a non-CPU metric (RPS/lag) + VPA on CPU/mem (disjoint signals), or VPA in `Off`.
- *Probe — how would you design autoscaling for a service that's both CPU-bound and bursty?* HPA on RPS or an `AverageValue` CPU target + VPA `Off` to right-size + overprovisioning for burst.

**Q4. Explain end-to-end latency from a traffic spike to serving extra capacity.** *(senior-signal)*
*Answer:* detection (metrics scrape + 15 s HPA sync ≈ up to 30 s) + scheduling (instant if room, else node provisioning minutes via CA, faster via Karpenter) + image pull + JVM cold start/JIT warmup + readiness. Node provisioning dominates; mitigate with overprovisioning/headroom and faster startup (AppCDS/CRaC/native).
- *Probe — how to cut JVM cold start?* AppCDS, CRaC checkpoint/restore, GraalVM native image, proper startup probes.
- *Probe — why overprovisioning beats waiting?* Negative-priority pause pods reserve already-running node capacity; real pods preempt them instantly while CA backfills.

**Q5. How does Cluster Autoscaler decide to add/remove a node?**
*Answer:* Scale-up: sees `Pending` pods, simulates whether adding a node from a group would let them schedule (respecting taints/affinity), grows that group. Scale-down: node under 50% utilization for 10 min, simulates rescheduling all its pods, drains (honoring PDBs), terminates VM.
- *Probe — what blocks scale-down?* Bare pods, local storage, restrictive PDBs, `safe-to-evict:false`, pods that won't fit elsewhere.
- *Probe — scale-from-zero?* CA infers a phantom node's shape from node-group template/tags to simulate scheduling.

**Q6. How does KEDA achieve scale-to-zero when HPA can't?**
*Answer:* KEDA creates/manages a normal HPA for 1→N (feeding it the trigger value via the External Metrics API), but KEDA's own operator handles the 0↔1 transition by directly setting replicas based on whether the source has work, gated by `cooldownPeriod`.
- *Probe — Kafka cap?* Replicas are capped at partition count; extra pods idle.
- *Probe — `ScaledObject` vs `ScaledJob`?* Object scales a Deployment; Job runs one Job per queued item (for long, non-idempotent tasks).

**Q7. What are stabilization windows and the default up/down asymmetry?**
*Answer:* A sliding buffer of recent recommendations; scale-down uses the max over the window (so a momentary dip won't shrink you). Defaults: scale-up window 0 s (react fast), scale-down 300 s (release slow). Prevents flapping and JVM re-warmup churn.
- *Probe — `selectPolicy`?* `Max` for aggressive (up), `Min` for conservative (down), `Disabled` to forbid a direction.

**Q8. Why are CPU limits dangerous for latency-sensitive JVM services?** *(senior-signal)*
*Answer:* CFS bandwidth control throttles the cgroup when it exhausts its per-100 ms quota — even with idle node CPU — causing periodic stalls and tail-latency spikes that don't show in average CPU. Multi-threaded JVMs hit this easily. Mitigate by removing/raising CPU limits and sizing thread pools to the limit (`ActiveProcessorCount`).
- *Probe — how to detect?* `container_cpu_cfs_throttled_periods_total` ratio; cgroup `cpu.stat` `nr_throttled`.
- *Probe — container-aware JVM?* Since JDK 10 / 8u191 the JVM reads cgroup limits for `availableProcessors()` and default heap — but too-low limits then under-size pools.

**Q9. You set `maxReplicas: 100` and one night the bill exploded. What happened and how do you prevent it?**
*Answer:* A runaway metric (bad request value, stuck dependency, or memory-based HPA ratcheting) drove HPA to the max, and CA provisioned nodes to host them. Prevent with sane `maxReplicas`, `--max-nodes-total`, alerts on `ScalingLimited`, request validation, and not scaling on memory.

**Q10. How would you autoscale a Kafka consumer with strict ordering and exactly-once needs?** *(senior-signal)*
*Answer:* Cap replicas at partition count (ordering is per-partition; one consumer per partition preserves order). Use KEDA lag scaling within that cap; make processing idempotent and commit offsets after side effects to survive rebalances; consider transactional/exactly-once semantics at the Kafka level. Avoid scale-to-zero if first-message latency matters.

**Q11. metrics-server is healthy but HPA still says `<unknown>` — why?**
*Answer:* The container has no CPU request, so utilization (a % of request) is undefined. Set requests.

**Q12. Compare Cluster Autoscaler and Karpenter.**
*Answer:* CA grows predefined node groups via scheduling simulation; portable, conservative. Karpenter provisions right-sized instances just-in-time and actively consolidates for cost; faster and cheaper but AWS-centric and CRD-based. Pick by cloud, need for consolidation, and operational constraints.

---

## 11. Glossary

- **Admission webhook** — HTTP callback the API server invokes to validate (reject) or mutate (modify) objects on create/update. VPA uses a mutating one to rewrite requests.
- **AppCDS** — Application Class Data Sharing; caches parsed class metadata to speed JVM startup.
- **ASG / MIG / VMSS** — cloud node-group abstractions on AWS / GCP / Azure.
- **cAdvisor** — container resource collector built into the kubelet.
- **CA** — Cluster Autoscaler; scales node count.
- **cgroups** — Linux kernel resource isolation/accounting for process groups; the basis of container limits.
- **CFS / CFS bandwidth** — Linux CPU scheduler; bandwidth control caps a cgroup's CPU per period (throttling).
- **CNCF** — Cloud Native Computing Foundation; hosts Kubernetes, Prometheus, KEDA, etc.
- **Cold start** — time for a fresh pod/JVM to become ready and fast (classloading, JIT warmup).
- **Consolidation** — Karpenter actively repacking pods onto fewer/cheaper nodes.
- **Cordon / Drain** — mark a node unschedulable / evict its pods.
- **CRaC** — Coordinated Restore at Checkpoint; snapshot/restore a warmed JVM for near-instant start.
- **CRD** — Custom Resource Definition; extends the Kubernetes API with new object kinds.
- **Custom/External Metrics API** — aggregated APIs (`custom.metrics.k8s.io`, `external.metrics.k8s.io`) HPA reads non-resource metrics through.
- **Deployment / StatefulSet / ReplicaSet** — workload controllers managing pod replicas.
- **etcd** — distributed key-value store (Raft-backed) holding cluster state.
- **Flapping / thrashing** — rapid scale up/down oscillation.
- **GraalVM native image** — ahead-of-time compiled JVM app binary with near-instant startup.
- **Guaranteed / Burstable / BestEffort** — pod QoS classes by requests/limits; set eviction order.
- **Headroom / overprovisioning** — spare capacity (often pause pods) kept ready to absorb bursts.
- **HPA** — Horizontal Pod Autoscaler; scales replica count.
- **In-place pod resize** — change pod CPU/mem without restart (newer feature gate).
- **JIT** — Just-In-Time compiler; warms up the JVM at runtime.
- **Karpenter** — AWS-centric just-in-time node provisioner / CA alternative.
- **KEDA** — Kubernetes Event-Driven Autoscaling; event metrics + scale-to-zero on top of HPA.
- **kubelet** — per-node agent running containers and reporting status.
- **kube-controller-manager** — runs core controllers, including HPA.
- **Lag (Kafka)** — unprocessed message backlog = latest offset − committed offset.
- **Limits / Requests** — runtime ceiling / scheduler reservation for CPU & memory.
- **metrics-server** — cluster CPU/memory aggregator serving the Resource Metrics API.
- **millicore (`m`)** — 1/1000 of a vCPU; `1000m` = 1 core.
- **Mi/Gi** — binary memory units (2²⁰ / 2³⁰ bytes).
- **Node group** — set of identical nodes managed as a unit.
- **OOM / OOMKilled** — kernel kills a container exceeding its memory limit.
- **PDB (PodDisruptionBudget)** — caps simultaneous voluntary disruptions of a workload.
- **Pod** — smallest deployable unit; one or more co-located containers.
- **Preemption** — scheduler evicts lower-priority pods to place higher-priority ones.
- **PriorityClass** — numeric pod priority governing scheduling/preemption.
- **Prometheus / Prometheus Adapter** — TSDB + adapter exposing PromQL results as custom/external metrics.
- **QoS class** — see Guaranteed/Burstable/BestEffort.
- **Raft** — consensus algorithm behind etcd.
- **Readiness / Startup / Liveness probe** — gates for traffic / startup grace / restart-on-hang.
- **Reconciliation loop** — observe→compare→act→repeat control pattern of all controllers.
- **Replica** — one running copy of a pod.
- **`/scale` subresource** — generic endpoint HPA writes replica counts to.
- **ScaledObject / ScaledJob** — KEDA CRDs for Deployments / Jobs.
- **Stabilization window** — sliding buffer damping scale decisions (down: 300 s default).
- **Taint / Toleration / Affinity** — node-repel / pod-allow / placement rules.
- **Tolerance band** — ±10% no-op zone around the HPA target.
- **TriggerAuthentication** — KEDA credential reference for a scaler.
- **VPA** — Vertical Pod Autoscaler; adjusts per-pod requests; modes Off/Initial/Recreate/Auto.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Formula:** `desired = ceil(replicas × current/target)`; tolerance ±10%; multi-metric → take **max**.
**Defaults to memorize:** HPA sync **15 s**; tolerance **0.1**; scale-up window **0 s**, scale-down **300 s**; CPU-init period **5 m**; CA scan **10 s**; scale-down util **0.5**, unneeded-time **10 m**, delay-after-add **10 m**; KEDA polling **30 s**, cooldown **300 s**; metrics-server keeps only the **latest** sample.
**Axes:** HPA = +pods; VPA = +size; CA/Karpenter = +nodes; KEDA = +pods on events & 0↔N.
**Golden rules:**
- Requests calibrate HPA% — set them right (use VPA `Off` to find them).
- Never HPA(CPU)+VPA(Auto,CPU) on the same workload.
- Scale fast up, slow down.
- Cap `maxReplicas` (Kafka: ≤ partitions) and `max-nodes-total`.
- Avoid CPU limits on latency-critical JVMs (CFS throttling); size thread pools to the limit.
- Don't scale on memory for the JVM; prefer CPU or RPS.
- Node provisioning is the slow leg → keep overprovisioning headroom.
- KEDA for events/scale-to-zero; CA/Karpenter make new pods schedulable.

**Debug first moves:** `kubectl describe hpa` (conditions), `kubectl top pods` (metrics-server), `kubectl describe pod <pending>` (CA), `kubectl describe scaledobject` (KEDA), CFS `nr_throttled` (latency spikes), `cluster-autoscaler-status` ConfigMap.

### 12.2 Self-test (no answers — recall practice)

1. A Deployment has 6 pods averaging 40% CPU with a 50% target and 10% tolerance. Does HPA scale, and to what? Show the ratio and the band check.
2. Explain precisely, with the utilization formula, why running VPA-Auto and HPA both on CPU causes oscillation — and give two safe alternative pairings.
3. Your HPA shows `TARGETS: <unknown>/60%` while `kubectl top pods` works fine. Give the most likely cause and the fix.
4. A user-facing JVM API shows p99 latency spikes under moderate load though average CPU is 35%. Name the likely cause, the metric you'd check, and two fixes.
5. Design autoscaling for a Kafka consumer on a 16-partition topic that must preserve per-partition ordering and run scale-to-zero overnight. Specify the tool, key fields/values, the replica cap, and how you avoid duplicate processing.
6. Walk through every latency component from a sudden 5× traffic spike to serving the extra load on a cluster that needs new nodes, and state which component dominates and how you'd shrink it.
7. Which pods/conditions block Cluster Autoscaler from removing an underutilized node, and how would you confirm the blocker from CA's output?

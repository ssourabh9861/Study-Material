# Rollouts & Probes (Kubernetes & Containers)

> An exhaustive engineering-handbook chapter on how Kubernetes ships new versions of your application without downtime, how it decides whether a Pod is alive and ready to receive traffic, and how it shuts Pods down cleanly. Written for a senior JVM/backend developer who wants to design, operate, debug, teach, and interview on this material.

---

## 1. Overview & where it fits

### 1.1 What this chapter is about

When you run a service on Kubernetes, two recurring questions dominate its day-to-day operational life:

1. **How do I replace the running version of my app with a new version — repeatedly, safely, and without dropping requests?** That is **rollouts** (and their cousins: rollbacks, canary, blue-green).
2. **How does the platform know whether a given replica of my app is healthy enough to receive user traffic, and what should it do when a replica goes sick?** That is **probes** (liveness, readiness, startup) plus the surrounding machinery: **graceful shutdown**, **PodDisruptionBudgets**, and **endpoint management**.

These two themes are deeply intertwined. A rollout is essentially "spin up new Pods, wait for them to become *ready* (a probe concept), shift traffic to them, then tear down the old Pods *gracefully* (a shutdown concept), all while honoring availability guarantees (a PodDisruptionBudget concept)." You cannot do safe rollouts without correct probes, and you cannot reason about probes without understanding the rollout lifecycle. This chapter treats them as one connected system.

### 1.2 The problem it solves

Before orchestrators, deploying a new version of a backend service typically meant one of:

- **Stop the old process, start the new one** — causes downtime (the "Recreate" pattern, manually).
- **Run two fleets behind a load balancer (LB) and manually re-point the LB** — works but is laborious, error-prone, and hard to automate or roll back.
- **In-place binary swap with a process manager** — fragile, no health verification, no automatic rollback.

The hard parts that a human operator gets wrong, and that Kubernetes automates:

- **Don't send traffic to a replica until it can actually serve it.** A freshly started JVM service might need 30 seconds to warm up the JIT (Just-In-Time compiler — the part of the JVM that compiles hot bytecode to native machine code at runtime), fill connection pools, and load caches. If you route traffic to it during that window, users get errors or slow responses.
- **Don't kill a replica that is still finishing in-flight requests.** Yanking a Pod mid-request returns 5xx errors (server-side HTTP error codes in the 500–599 range) to users.
- **Don't take down so many replicas at once that the service capacity collapses.**
- **Detect a sick replica and replace it automatically**, without paging a human at 3 a.m.
- **If the new version is broken, stop the rollout and revert** — automatically and fast.

### 1.3 When you reach for it

You use this machinery essentially *always* when running a long-lived service (an HTTP API, a gRPC service, a queue consumer) on Kubernetes. Concretely:

- Every time you `kubectl apply` a new container image to a `Deployment`, you trigger a **rollout**.
- Every Pod that serves traffic should have a **readiness probe**; most should have a **liveness probe** with care; slow starters (JVM apps are the canonical example) should have a **startup probe**.
- Every service that matters for availability should have a **PodDisruptionBudget**.
- Every container should handle **SIGTERM** and have a sensible **terminationGracePeriodSeconds** (and often a **preStop** hook).

You reach for the *advanced* tooling (Argo Rollouts, Flagger, canary/blue-green) when a plain rolling update isn't enough — i.e., when you want automated, metric-driven, progressive delivery with automatic rollback based on real traffic.

### 1.4 The one-paragraph mental model

A **Deployment** is a declarative spec that says "I want N identical Pods running this image." It owns **ReplicaSets** (each ReplicaSet pins one specific Pod template/version), and a rollout is the controlled hand-off of replica count from the old ReplicaSet to a new one. During that hand-off, Kubernetes uses **readiness probes** to decide which Pods may receive traffic (it adds/removes their IPs from the **Service**'s endpoint list), **liveness probes** to decide which Pods to restart in place, and **startup probes** to give slow Pods a grace window before the other probes engage. When a Pod must die — during a rollout, a node drain, or a scale-down — Kubernetes removes it from endpoints, sends it **SIGTERM**, runs any **preStop** hook, waits up to **terminationGracePeriodSeconds**, then **SIGKILL**s it. A **PodDisruptionBudget** caps how many Pods of a given app may be voluntarily disrupted at once so a rollout or node drain can't collapse your capacity. Tools like **Argo Rollouts** and **Flagger** replace the Deployment's built-in (and fairly blunt) rollout logic with richer, metric-gated **canary** and **blue-green** strategies.

---

## 2. Foundations from first principles

This section builds the vocabulary. If you already know Kubernetes objects cold, skim — but the precise definitions here are load-bearing for the rest of the chapter.

### 2.1 Container

A **container** is an isolated, packaged process: your application binary plus its dependencies bundled into an image, run with its own filesystem view, network namespace, and resource limits, but sharing the host kernel. Compared to a virtual machine (VM — a full guest operating system running on virtualized hardware), a container is lighter because it does not carry its own kernel. On Kubernetes, containers are created from **images** (immutable, layered filesystem snapshots, e.g. `myapp:1.4.2`) by a **container runtime** (the low-level software that actually starts containers — e.g. **containerd** or **CRI-O**; Docker's engine used to be common but Kubernetes removed the built-in "dockershim" adapter in v1.24).

### 2.2 Pod

A **Pod** is the smallest deployable unit in Kubernetes: one *or more* containers that share a network namespace (same IP address and port space) and can share storage volumes. Most Pods run a single application container, sometimes plus **sidecars** (helper containers — e.g. a logging agent or a service-mesh proxy). A Pod is **ephemeral**: it has a finite lifecycle, gets a (usually) cluster-internal IP that changes when the Pod is recreated, and is never "repaired" — when unhealthy beyond recovery, it is *replaced* by a new Pod, not fixed.

A Pod moves through **phases**: `Pending` (accepted but not yet running — e.g. waiting to be scheduled or pulling images), `Running` (bound to a node, at least one container started), `Succeeded` (all containers terminated successfully — for batch jobs), `Failed` (all containers terminated, at least one in error), and `Unknown` (state could not be obtained). Inside a Running Pod, each container has its own **state**: `Waiting`, `Running`, or `Terminated`. Conditions like `Ready` and `ContainersReady` are separate booleans on the Pod — and `Ready` is the one that gates traffic.

### 2.3 Node and kubelet

A **node** is a worker machine (VM or physical) in the cluster. On each node runs the **kubelet** — the per-node agent that talks to the control plane, starts/stops containers via the runtime, and *runs the probes*. This is crucial: **probes are executed by the kubelet on the node, not by the control plane and not over the Service**. The kubelet hits the Pod directly on its Pod IP.

### 2.4 Control plane, API server, controllers, etcd

The **control plane** is the brain of the cluster. Key pieces:

- **kube-apiserver** — the front door; everything reads and writes cluster state through it (a REST API over HTTPS).
- **etcd** — a distributed, strongly consistent key-value store (it uses the **Raft** consensus algorithm — a protocol where a leader replicates a log to followers and a majority must agree before a write is committed, guaranteeing all nodes converge on the same state). etcd is the source of truth for all cluster state.
- **kube-controller-manager** — runs the **controllers**: control loops that continuously compare *desired state* (what you declared) with *observed state* (what exists) and take action to reconcile them. The **Deployment controller**, **ReplicaSet controller**, and **endpoints/EndpointSlice controllers** all live here. This **reconciliation loop** ("observe → diff → act → repeat") is the fundamental pattern of Kubernetes.
- **kube-scheduler** — assigns unscheduled Pods to nodes based on resource requests, affinity rules, taints/tolerations, etc.

### 2.5 ReplicaSet

A **ReplicaSet** is a controller object whose entire job is: "ensure exactly `replicas` Pods matching this exact template (label selector + Pod spec) exist." It does this by creating or deleting Pods. You rarely create ReplicaSets directly; **Deployments create and manage them for you**. The key mental model: **one ReplicaSet = one immutable Pod template = one app version**. When you change the image, the Deployment makes a *new* ReplicaSet for the new template and orchestrates moving replicas from the old RS to the new RS.

### 2.6 Deployment

A **Deployment** is the higher-level object you actually author for stateless services. It wraps a `PodTemplate` (the spec for the Pods it should run), a desired replica count, and a **rollout strategy**. The Deployment controller's job is to make the live set of ReplicaSets and Pods match the spec, and — when the template changes — to *roll out* the change using the chosen strategy. Deployments keep a bounded **revision history** (old ReplicaSets scaled to 0) so you can **roll back**.

> **Deployment vs StatefulSet vs DaemonSet (so the names aren't a mystery later):**
> - **StatefulSet** — for stateful, identity-sensitive workloads (databases, Kafka). Pods get stable names (`app-0`, `app-1`) and stable storage, and roll out *in order*. Its rollout knobs differ (no `maxSurge`; it has `partition`-based updates).
> - **DaemonSet** — runs exactly one Pod per node (log shippers, CNI agents). Has its own `RollingUpdate` with `maxUnavailable`/`maxSurge`.
> - This chapter focuses on **Deployments**, but calls out StatefulSet/DaemonSet differences where relevant.

### 2.7 Service, Endpoints, EndpointSlice

A **Service** is a stable virtual address (a cluster IP and DNS name) that load-balances across a *dynamic* set of Pods selected by labels. Because Pod IPs come and go, the Service provides indirection. The set of currently-eligible Pod IPs behind a Service is stored in **EndpointSlice** objects (the modern, shardable replacement for the older single **Endpoints** object). A Pod's IP is added to the EndpointSlice **only when the Pod is `Ready`** (subject to `publishNotReadyAddresses` overrides). This is precisely how the readiness probe controls traffic: *ready → in endpoints → receives traffic; not ready → out of endpoints → no new traffic.* The data-plane component **kube-proxy** (or a CNI/eBPF dataplane, or a service mesh) programs the actual packet routing based on those endpoints.

### 2.8 Labels, selectors, annotations

- **Labels** — key/value tags on objects (`app=checkout`, `version=v2`) used for selection.
- **Selectors** — queries over labels; a Service selects Pods by label, a ReplicaSet selects its Pods by label.
- **Annotations** — non-identifying metadata (often used by tools like Argo/Flagger to store config or by you to record `kubernetes.io/change-cause`).

### 2.9 Probe — the core definition

A **probe** is a periodic diagnostic that the kubelet runs against a container to answer a yes/no health question. There are three kinds, each answering a *different* question and triggering a *different* action:

| Probe | Question it answers | Action on failure |
|---|---|---|
| **Liveness** | "Is this container wedged and unable to recover on its own?" | **Restart the container** (in place, same Pod). |
| **Readiness** | "Can this container serve traffic *right now*?" | **Remove the Pod from Service endpoints** (stop sending traffic); do **not** restart. |
| **Startup** | "Has this container finished its (possibly slow) startup yet?" | While running, it **gates** liveness/readiness; on failure past its budget, **restart the container**. |

The single most important sentence in this chapter: **readiness gates traffic; liveness restarts.** Confusing the two is the number-one source of self-inflicted outages in this domain (covered in detail in §9).

### 2.10 Probe mechanisms (handlers)

A probe checks health via one of four handler types:

- **`httpGet`** — kubelet sends an HTTP GET to a path/port; success = HTTP status `>= 200 and < 400`.
- **`tcpSocket`** — kubelet opens a TCP connection to a port; success = connection accepted.
- **`exec`** — kubelet runs a command inside the container; success = exit code `0`.
- **`grpc`** — kubelet uses the standard gRPC Health Checking Protocol (a well-known gRPC service `grpc.health.v1.Health` with a `Check` method). Became **stable (GA) in Kubernetes 1.27**; was beta from 1.24. (gRPC = a high-performance RPC framework over HTTP/2 using Protocol Buffers.)

### 2.11 Probe timing parameters

Every probe shares these timing knobs (defaults shown):

- **`initialDelaySeconds`** (default `0`) — wait this long after the container starts before the first probe.
- **`periodSeconds`** (default `10`) — how often to probe.
- **`timeoutSeconds`** (default `1`) — how long a single probe may take before counting as a failure. **One second is aggressive for JVM apps** — see §6/§9.
- **`successThreshold`** (default `1`; must be `1` for liveness and startup) — consecutive successes needed to flip to "passing."
- **`failureThreshold`** (default `3`) — consecutive failures needed to flip to "failing" / trigger the action.

### 2.12 Graceful shutdown vocabulary

- **SIGTERM** — the polite "please terminate" Unix signal (number 15) Kubernetes sends first; a well-behaved process catches it and begins draining.
- **SIGKILL** — the unstoppable "die now" signal (number 9) the kubelet sends if the process hasn't exited within the grace period. Cannot be caught or ignored.
- **`terminationGracePeriodSeconds`** (default `30`) — total seconds the kubelet waits after SIGTERM before SIGKILL.
- **`preStop` hook** — a command or HTTP call the kubelet runs *before* sending SIGTERM, used to start draining or to sleep so load balancers can deregister the Pod.
- **Draining** — finishing in-flight requests and refusing new ones before exiting.

### 2.13 Disruptions and PodDisruptionBudget

A **disruption** is anything that removes a running Pod. **Involuntary** disruptions (node hardware failure, kernel panic, out-of-resources eviction) cannot be budgeted away. **Voluntary** disruptions (node drains for maintenance/upgrades, rollouts, autoscaler scale-down) *can* be governed. A **PodDisruptionBudget (PDB)** is an object that says "for Pods matching this selector, keep at least `minAvailable` (or at most `maxUnavailable`) available during voluntary disruptions." The **Eviction API** (used by `kubectl drain` and the cluster autoscaler) consults the PDB and *blocks* an eviction that would violate it.

With this vocabulary, we can now go deep on the internals.

---

## 3. How it works internally

This is the heart of the chapter. We trace the actual control flow and data flow, step by step.

### 3.1 The reconciliation model (the substrate everything runs on)

Every Kubernetes controller runs the same loop:

1. **Watch** the API server for changes to objects it cares about (using efficient long-lived watch streams, not polling).
2. **Compute desired vs. actual.**
3. **Act** by creating/updating/deleting objects through the API server.
4. The action changes state, which generates new watch events, which feed the loop again. The system is **eventually consistent** and **level-triggered** (it reacts to the *current state*, not to a one-time *edge* event — so it self-heals if it misses an event or if something changes underneath it).

Keep this in mind: there is no single procedural "deploy script." A rollout is an *emergent behavior* of several controllers reconciling concurrently.

### 3.2 Anatomy of a rolling update — step by step

Suppose you run a Deployment `checkout` with `replicas: 10`, strategy `RollingUpdate`, `maxSurge: 25%`, `maxUnavailable: 25%`, currently on image `checkout:1.0` (ReplicaSet `rs-old`, 10 Pods, all Ready). You run:

```bash
kubectl set image deployment/checkout checkout=checkout:1.1
```

Here is what happens under the hood, in order:

1. **API write.** `kubectl` PATCHes the Deployment's Pod template (`.spec.template`) at the API server, which persists it to etcd and emits a watch event.
2. **Deployment controller detects a template change.** It computes a hash of the new Pod template (`pod-template-hash`). No existing ReplicaSet matches that hash, so it **creates a new ReplicaSet `rs-new`** with `replicas: 0` and the new template. (If you'd rolled back to a previously seen template, it would *reuse* the old RS with that hash instead of creating one.)
3. **Surge math.** With 10 desired replicas, `maxSurge: 25%` rounds **up** to 3 (you may run up to 13 Pods total). `maxUnavailable: 25%` rounds **down** to 2 (at least 8 Pods must remain available). So the controller's invariants during the rollout are: *total Pods ≤ 13* and *available Pods ≥ 8*.
4. **Scale up new, scale down old, in waves.** The controller scales `rs-new` up toward 3 surge Pods first (because it has surge headroom and zero new Pods are available yet). As soon as it scales up `rs-new`, the ReplicaSet controller for `rs-new` creates new Pods.
5. **New Pods schedule and start.** The scheduler binds each new Pod to a node; the kubelet pulls `checkout:1.1` (if not cached), creates the container, and the container starts. The Pod is `Running` but **not yet `Ready`.**
6. **Readiness gating.** The kubelet runs the **startup probe** (if defined) until it passes, then begins running the **readiness probe**. Until readiness succeeds `successThreshold` times, the Pod is **not** added to the EndpointSlice — it receives **no traffic**. This is the safety mechanism: surged new Pods can't break users while warming up.
7. **A new Pod becomes Ready.** Its IP is added to the EndpointSlice; kube-proxy/mesh starts routing a share of traffic to it. The Deployment controller now sees one more available Pod, which *increases the budget* to retire old Pods.
8. **Retire old Pods.** With availability headroom, the controller scales `rs-old` down. Each old Pod selected for termination goes through the **graceful shutdown sequence** (§3.5): removed from endpoints, SIGTERM, preStop, grace period, SIGKILL.
9. **Repeat the dance.** The controller keeps scaling `rs-new` up and `rs-old` down in increments, always respecting *total ≤ 13* and *available ≥ 8*, **waiting for new Pods to become Ready before retiring more old ones.** This is why a stuck readiness probe stalls the entire rollout (and why that is a *feature* — it prevents shipping a broken version).
10. **Completion.** When `rs-new` has 10 Ready Pods and `rs-old` is at 0, the rollout is **complete**. The Deployment's status condition `Progressing` flips to reason `NewReplicaSetAvailable`. `rs-old` is retained at 0 replicas as a revision for rollback (up to `revisionHistoryLimit`, default `10`).

> **Why "wait for Ready" is the linchpin:** the entire correctness of a rolling update depends on the readiness probe being an honest signal of "I can serve traffic." If readiness lies (returns 200 before the app is warm, or never depends on real dependencies), the rollout will happily replace good Pods with broken ones.

### 3.3 maxSurge / maxUnavailable math, precisely

- Both accept an **integer** (absolute Pod count) or a **percentage string** (e.g. `"25%"`).
- **`maxSurge`** is rounded **up** (ceil). It limits how many Pods you may run *above* the desired count.
- **`maxUnavailable`** is rounded **down** (floor). It limits how many Pods below desired may be unavailable.
- **They cannot both be zero** (that would mean "can't add Pods and can't remove Pods" — no progress possible). The API rejects it.
- **Defaults:** `maxSurge: 25%`, `maxUnavailable: 25%`.

Worked examples (desired = `replicas`):

| replicas | maxSurge | maxUnavailable | Max total Pods | Min available | Behavior |
|---|---|---|---|---|---|
| 10 | 25% (→3) | 25% (→2) | 13 | 8 | Default: surge a few, retire a few, fast and safe. |
| 4 | 1 | 0 | 5 | 4 | Zero-downtime, full capacity always — surge one, never drop below 4. Slightly slower, needs +1 capacity. |
| 4 | 0 | 1 | 4 | 3 | No extra capacity used; tolerates losing 1 at a time. Good when nodes are tight. |
| 1 | 25% (→1) | 25% (→0) | 2 | 1 | Single replica: must surge (maxUnavailable floors to 0) to avoid downtime. |
| 1 | 0 | 1 | 1 | 0 | Single replica with no surge → **guaranteed brief downtime** during update. |

**Practical guidance:**
- For **zero-downtime at full capacity**, use `maxSurge: 1` (or more) and `maxUnavailable: 0`. You temporarily need extra node capacity for the surge Pods.
- For **capacity-constrained** clusters, use `maxSurge: 0`, `maxUnavailable: 1` — but accept reduced capacity during the rollout.
- Percentages with small replica counts behave surprisingly because of ceil/floor — prefer integers for `replicas < 10`.

### 3.4 The `Recreate` strategy

`strategy.type: Recreate` is the blunt instrument: **terminate ALL old Pods, wait for them to fully die, then create the new Pods.** There is a window of **zero available Pods** → guaranteed downtime. Use it only when:

- Two versions **cannot** run simultaneously (e.g., an exclusive lock, a singleton license, a schema migration that breaks the old version, an exclusive `ReadWriteOnce` volume that only one Pod can mount).
- You're doing a dev/test workload where downtime is acceptable.

`Recreate` ignores `maxSurge`/`maxUnavailable` (those are RollingUpdate-only fields).

### 3.5 The Pod termination lifecycle — step by step

This sequence runs whenever a Pod is deleted (rollout retiring old Pods, scale-down, node drain, manual `kubectl delete pod`). Understanding it is essential to avoid dropped requests.

1. **Deletion requested.** Something calls the delete API on the Pod. The API server sets `metadata.deletionTimestamp` and the grace period starts (`terminationGracePeriodSeconds`, default 30). The Pod's phase is still Running but it is now "Terminating."
2. **Endpoint removal begins — concurrently.** The Pod is marked not-ready/terminating, and the EndpointSlice controller removes (or marks `terminating`) the Pod's IP from the EndpointSlice. kube-proxy/mesh/LB then stops sending **new** connections to it. **This is asynchronous and eventually consistent** — there's a propagation delay (often tens to hundreds of ms, sometimes seconds for external LBs) during which traffic may still arrive. This race is the reason for the `preStop` sleep trick (§3.6).
3. **preStop hook runs (if defined).** The kubelet executes the `preStop` handler *and waits for it to finish* before sending SIGTERM. The grace-period clock is already ticking, and **preStop runtime counts against `terminationGracePeriodSeconds`.**
4. **SIGTERM sent.** After preStop completes, the kubelet sends **SIGTERM** to PID 1 of each container. Your app should catch it and begin draining: stop accepting new requests/connections, finish in-flight ones, close pools, flush buffers.
5. **Grace period countdown.** The kubelet waits for the container to exit on its own, up to the remaining grace period.
6. **SIGKILL.** If the container hasn't exited when the grace period elapses, the kubelet sends **SIGKILL** (uncatchable) and the container dies hard. In-flight work is lost.
7. **Pod object removed.** Once all containers are gone, the kubelet reports termination and the Pod object is deleted from the API/etcd.

> **Order subtlety:** Endpoint removal (step 2) and preStop/SIGTERM (steps 3–4) happen *concurrently*, not strictly serially. The kubelet doesn't wait for global endpoint propagation before sending SIGTERM. Hence: if your app starts rejecting connections the instant it gets SIGTERM, but the LB hasn't finished deregistering it, you drop requests. The fix is to *delay* SIGTERM's effect long enough for deregistration — via a `preStop` sleep, or by having the app keep serving for a short grace window after SIGTERM.

### 3.6 The classic `preStop` sleep pattern (and why it exists)

Because endpoint deregistration is eventually consistent and racy, the battle-tested pattern is:

```yaml
lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "sleep 5"]  # give kube-proxy/LB time to deregister this Pod
```

The Pod is already removed from endpoints (no *new* traffic should arrive), the `sleep` buys ~5 s for that removal to propagate everywhere, the app keeps serving any in-flight requests during the sleep, and only *then* does SIGTERM arrive. Set `terminationGracePeriodSeconds` to at least `preStop duration + worst-case request duration + buffer`.

### 3.7 How probes execute internally (kubelet's prober)

Inside the kubelet there is a **prober manager**. For each container with probes:

1. A goroutine (lightweight Go thread) per probe runs on the `periodSeconds` cadence.
2. After `initialDelaySeconds`, it invokes the handler (`httpGet`/`tcpSocket`/`exec`/`grpc`) against the **container's IP/port directly on the node** — never via the Service.
3. It tracks consecutive results. After `failureThreshold` consecutive failures (or `successThreshold` consecutive successes), it flips the cached probe state.
4. On a **liveness** state → fail, the kubelet tells the runtime to **restart the container** (respecting the Pod's `restartPolicy`, which for Deployments is always effectively `Always`). The restart count increments; repeated restarts incur **CrashLoopBackOff** exponential backoff (10s, 20s, 40s … capped at 5 min).
5. On a **readiness** state → fail, the kubelet updates the Pod's `Ready` condition → the EndpointSlice controller removes the IP from endpoints. On recovery, it's re-added.
6. **Startup probe** behavior: while a startup probe is configured and not yet succeeded, the kubelet **does not run** liveness or readiness probes (they're disabled). Once the startup probe succeeds once, it's done forever (until container restart) and liveness/readiness take over. If the startup probe fails `failureThreshold` times, the container is killed/restarted. The startup probe's effective max startup time is `failureThreshold × periodSeconds` (+ `initialDelaySeconds`).

> **`exec` probe cost:** an `exec` probe forks a process inside the container every `periodSeconds`. At high replica counts and short periods this is non-trivial CPU/fork overhead and can leave zombie processes if the binary misbehaves. Prefer `httpGet`/`grpc` where possible.

### 3.8 Probe `terminationGracePeriodSeconds` override

Since Kubernetes **1.22 (stable 1.25)**, a **probe** (liveness or startup) can carry its own `terminationGracePeriodSeconds`. When that probe triggers a restart, the kubelet uses the *probe's* grace period for that kill instead of the Pod's. Use a short probe-level grace (e.g. 5–10s) so a genuinely wedged container is killed fast, while keeping a longer Pod-level grace for normal graceful shutdown.

### 3.9 Pod readiness gates (extending readiness beyond probes)

A **readiness gate** (`spec.readinessGates`) lets an external controller contribute to a Pod's overall readiness. The Pod is `Ready` only when **all containers are ready (probes pass) AND all readiness-gate conditions are `True`.** This is how, e.g., the AWS Load Balancer Controller can hold a Pod "not ready" until the ALB target group has actually registered and health-checked it — closing the rollout race for cloud LBs. The controller sets a custom condition (e.g. `target-health.elbv2.k8s.aws/...`) that the gate references.

### 3.10 Canary and blue-green — the conceptual internals

**Plain Deployments cannot do true canary or blue-green by themselves** in a clean, automated way. Here's why and what the patterns mean:

- **Blue-green:** run two complete environments — **blue** (current) and **green** (new) — side by side at full size, then flip 100% of traffic from blue to green atomically (by re-pointing a Service's selector or an Ingress). Instant cutover, instant rollback (flip back), but you pay for **2× capacity** during the transition. With raw Kubernetes you implement this with two Deployments and a Service whose `selector` you patch.
- **Canary:** send a *small fraction* of real traffic to the new version, watch metrics (error rate, latency), then progressively increase if healthy or abort if not. The "poor-man's canary" with raw Deployments is to run two Deployments (stable + canary) behind one Service and tune replica counts so the canary gets ~N% of traffic (traffic share ≈ canary replicas / total replicas — *crude*, since it's per-Pod, not true weighted routing). Real canary needs **weighted traffic splitting** (via a service mesh like Istio/Linkerd, or an Ingress/Gateway that supports weights) plus **automated metric analysis** — which is exactly what Argo Rollouts and Flagger provide (§3.11).

### 3.11 Argo Rollouts and Flagger — internals at a glance

Both are **controllers that extend Kubernetes with progressive-delivery logic**. They are the canonical answer to "how do I do automated canary/blue-green on K8s."

**Argo Rollouts:**
- Introduces a **`Rollout`** custom resource (CRD — Custom Resource Definition, a way to add new object *types* to the K8s API) that is a *drop-in replacement for a Deployment*'s `spec` plus a `strategy` block describing canary steps or blue-green.
- You define **steps**: `setWeight: 20`, `pause: {duration: 5m}`, `setWeight: 40`, `analysis: ...`, etc.
- It manages the ReplicaSets and works with a **traffic router** (Istio, NGINX Ingress, AWS ALB, SMI, Gateway API, etc.) to set traffic weights precisely.
- **AnalysisTemplate/AnalysisRun** objects query a metrics provider (Prometheus, Datadog, CloudWatch, New Relic, a web/job check…) and **auto-promote or auto-abort** based on the result.
- Provides a CLI (`kubectl argo rollouts`) and a dashboard.

**Flagger:**
- Works *with your existing Deployment*; you create a **`Canary`** custom resource pointing at the Deployment and Service.
- Flagger automatically creates the primary/canary versions and manipulates the mesh/ingress weights.
- It runs **periodic analysis** (e.g. every 1 min, step weight +10%) checking success-rate and latency metrics against thresholds; promotes on success, rolls back on breach.
- Integrates with Istio, Linkerd, App Mesh, NGINX, Contour, Gloo, Gateway API, plus alerting (Slack/Teams) and load-testing hooks.

The core internal idea in both: **a control loop that (1) shifts a small traffic weight to the new version, (2) runs metric analysis over a window, (3) decides promote/hold/abort, and (4) loops** — turning "deploy" from a one-shot event into a *governed, observable, reversible process*.

---

## 4. The complete toolkit

### 4.1 Deployment rollout fields (`spec`)

| Field | Purpose | Default |
|---|---|---|
| `replicas` | Desired Pod count | `1` |
| `strategy.type` | `RollingUpdate` or `Recreate` | `RollingUpdate` |
| `strategy.rollingUpdate.maxSurge` | Max Pods above desired during rollout (int or %) | `25%` |
| `strategy.rollingUpdate.maxUnavailable` | Max unavailable Pods during rollout (int or %) | `25%` |
| `minReadySeconds` | Pod must be Ready *and stay Ready* this long before counted available | `0` |
| `progressDeadlineSeconds` | If no progress within this window, mark rollout failed (`Progressing=False`, reason `ProgressDeadlineExceeded`) | `600` |
| `revisionHistoryLimit` | How many old ReplicaSets to retain for rollback | `10` |
| `paused` | If `true`, rollout is paused (used for manual canary/checkpoints) | `false` |

> **`minReadySeconds` is underused and powerful:** it forces a Pod to survive a stabilization window before the rollout trusts it. Set it to, say, `15`–`30` for JVM apps so a Pod that flaps right after warmup isn't counted as good. **Note:** failing the deadline does **not** auto-rollback by default — it just marks the rollout failed; you (or CI/CD) decide to roll back.

### 4.2 `kubectl rollout` commands

| Command | What it does |
|---|---|
| `kubectl rollout status deployment/<name>` | Watches a rollout until it completes or fails (great in CI; non-zero exit on failure). `--timeout=5m`. |
| `kubectl rollout history deployment/<name>` | Lists revisions; `--revision=N` shows details. |
| `kubectl rollout undo deployment/<name>` | Roll back to the previous revision. `--to-revision=N` for a specific one. |
| `kubectl rollout pause deployment/<name>` | Pause — subsequent edits accumulate without triggering rollout (manual canary checkpoint). |
| `kubectl rollout resume deployment/<name>` | Resume a paused rollout. |
| `kubectl rollout restart deployment/<name>` | Trigger a rollout with *no spec change* (rotates all Pods — used to pick up new Secrets/ConfigMaps or to clear bad state). |

> **`change-cause`:** Annotate revisions for readable history with `kubectl annotate deployment/<name> kubernetes.io/change-cause="bump to 1.1"` or `--record` (deprecated). It populates the `CHANGE-CAUSE` column in `rollout history`.

### 4.3 Probe fields (per container: `livenessProbe`, `readinessProbe`, `startupProbe`)

| Field | Purpose | Default |
|---|---|---|
| `httpGet.{path,port,scheme,httpHeaders}` | HTTP probe; success = 2xx/3xx | — |
| `tcpSocket.{port}` | TCP connect probe | — |
| `exec.command` | Exec probe; success = exit 0 | — |
| `grpc.{port,service}` | gRPC health probe (GA 1.27) | — |
| `initialDelaySeconds` | Delay before first probe | `0` |
| `periodSeconds` | Probe interval | `10` |
| `timeoutSeconds` | Per-probe timeout | `1` |
| `successThreshold` | Consecutive successes to pass (must be 1 for liveness/startup) | `1` |
| `failureThreshold` | Consecutive failures to fail | `3` |
| `terminationGracePeriodSeconds` | Grace period applied when *this probe* triggers a kill (stable 1.25) | inherits Pod value |

### 4.4 Lifecycle & graceful-shutdown fields

| Field | Purpose | Default |
|---|---|---|
| `spec.terminationGracePeriodSeconds` | Total wait between SIGTERM and SIGKILL (preStop counts toward it) | `30` |
| `lifecycle.preStop.{exec,httpGet}` | Hook run before SIGTERM | — |
| `lifecycle.postStart.{exec,httpGet}` | Hook run right after container start (no ordering guarantee vs entrypoint) | — |
| `spec.readinessGates[].conditionType` | External readiness condition that must be True for Pod readiness | — |
| `spec.{terminationMessagePath,terminationMessagePolicy}` | Where the kubelet reads a container's last words | `/dev/termination-log`, `File` |

### 4.5 PodDisruptionBudget fields (`policy/v1`)

| Field | Purpose | Notes |
|---|---|---|
| `spec.selector` | Which Pods this PDB governs (label selector) | Required; should match your Deployment's Pods. |
| `spec.minAvailable` | Min Pods that must stay available during voluntary disruption (int or %) | Mutually exclusive with `maxUnavailable`. |
| `spec.maxUnavailable` | Max Pods that may be unavailable | Mutually exclusive with `minAvailable`. |
| `spec.unhealthyPodEvictionPolicy` | `IfHealthyBudget` (default) vs `AlwaysAllow` — whether *unhealthy* Pods can be evicted even if budget is at the edge (stable 1.27) | `AlwaysAllow` prevents stuck drains when Pods are crashlooping. |

> **Gotchas:** A PDB with `minAvailable` equal to the replica count (e.g. `minAvailable: 100%` or `minAvailable: 3` for 3 replicas) **blocks all voluntary evictions forever** — node drains will hang. PDBs only constrain *voluntary* disruptions and the *eviction* path; a hard `kubectl delete node` or node crash ignores them.

### 4.6 Argo Rollouts `Rollout` strategy fields (selected)

| Field | Purpose |
|---|---|
| `strategy.canary.steps[]` | Ordered steps: `setWeight`, `pause`, `setCanaryScale`, `analysis`, `experiment`. |
| `strategy.canary.maxSurge` / `maxUnavailable` | Same semantics as Deployment, for the canary RS. |
| `strategy.canary.trafficRouting` | Which router to use (`istio`, `nginx`, `alb`, `smi`, `plugins`, Gateway API). |
| `strategy.canary.analysis` | Background/inline metric analysis with `AnalysisTemplate`s. |
| `strategy.blueGreen.activeService` / `previewService` | Two Services: live and preview. |
| `strategy.blueGreen.autoPromotionEnabled` | Auto-flip to green or wait for manual promotion. |
| `strategy.blueGreen.scaleDownDelaySeconds` | How long to keep blue around after cutover for fast rollback. |

### 4.7 Flagger `Canary` fields (selected)

| Field | Purpose |
|---|---|
| `spec.targetRef` | The Deployment to manage. |
| `spec.service` | Service/port/mesh config Flagger generates primary+canary for. |
| `spec.analysis.interval` | How often to step/analyze (e.g. `1m`). |
| `spec.analysis.stepWeight` / `maxWeight` | Canary weight increment and ceiling. |
| `spec.analysis.threshold` | Consecutive failed checks before rollback. |
| `spec.analysis.metrics[]` | Metric queries + thresholds (request-success-rate, request-duration, custom). |
| `spec.analysis.webhooks[]` | Pre/post hooks: load tests, manual gates, alerts. |

### 4.8 Inspection commands (debugging toolkit)

| Command | Use |
|---|---|
| `kubectl get deploy <n> -o wide` | READY/UP-TO-DATE/AVAILABLE columns. |
| `kubectl describe deploy <n>` | Events, conditions, surge/unavailable settings. |
| `kubectl get rs -l app=<n>` | See old/new ReplicaSets and their replica counts. |
| `kubectl describe pod <p>` | Probe failures, restart reasons, events, last state. |
| `kubectl get endpointslices -l kubernetes.io/service-name=<svc>` | Which Pod IPs are actually receiving traffic. |
| `kubectl get pdb` | PDB status: ALLOWED DISRUPTIONS column. |
| `kubectl get events --sort-by=.lastTimestamp` | Timeline of Unhealthy/Killing/Started events. |
| `kubectl argo rollouts get rollout <n> --watch` | Live Argo Rollouts progress. |

---

## 5. Code examples by use case

All examples are complete and adaptable. JVM-specific notes are called out because the reader is a JVM backend dev.

### 5.1 Spring Boot Deployment with all three probes done correctly

Spring Boot Actuator exposes purpose-built probe endpoints (`/actuator/health/liveness` and `/actuator/health/readiness`) when Kubernetes detection is on. These are *better* than a single `/actuator/health` because the readiness group automatically reports `OUT_OF_SERVICE` during graceful shutdown.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: checkout
  labels: { app: checkout }
spec:
  replicas: 6
  revisionHistoryLimit: 5
  minReadySeconds: 15            # JVM must stay Ready 15s before counted available
  progressDeadlineSeconds: 300   # fail the rollout if no progress in 5 min
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 2                 # add up to 2 extra Pods (need spare node capacity)
      maxUnavailable: 0           # never drop below desired -> zero-downtime
  selector:
    matchLabels: { app: checkout }
  template:
    metadata:
      labels: { app: checkout }
    spec:
      terminationGracePeriodSeconds: 60   # SIGTERM..SIGKILL window; >= drain time + buffer
      containers:
        - name: checkout
          image: registry.example.com/checkout:1.1.0
          ports: [{ containerPort: 8080 }]
          # --- Startup probe: gives the slow JVM up to 5min to warm up ---
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 5
            failureThreshold: 60          # 60 * 5s = 300s budget for cold JVM start
            timeoutSeconds: 3
          # --- Liveness: only restart on TRUE deadlock, not on slow deps ---
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 10
            timeoutSeconds: 3             # 1s default too aggressive for JVM GC pauses
            failureThreshold: 6           # tolerate ~60s of trouble before restart
          # --- Readiness: gate traffic; reflects dependency health ---
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 3
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 5"]   # let endpoints deregister
          resources:
            requests: { cpu: "500m", memory: "768Mi" }
            limits:   { memory: "1Gi" }                  # avoid CPU limit on JVM (throttling)
```

Enable the Spring Boot endpoints (`application.yaml`):

```yaml
management:
  endpoint.health.probes.enabled: true       # split liveness/readiness groups
  health.livenessstate.enabled: true
  health.readinessstate.enabled: true
server:
  shutdown: graceful                          # finish in-flight requests on SIGTERM
spring:
  lifecycle.timeout-per-shutdown-phase: 30s   # bound graceful shutdown; <= grace period
```

**Why each choice matters:**
- **`startupProbe`** decouples "slow start" from "deadlocked." Without it, you'd be forced to set a huge `livenessProbe.initialDelaySeconds`, which also *delays* detecting a real deadlock for the container's whole life.
- **`maxUnavailable: 0` + `maxSurge: 2`** = zero-downtime, full-capacity rollout (needs spare capacity).
- **Liveness uses `/liveness` not `/readiness`** so a *dependency outage* (DB down) doesn't restart healthy Pods (it should make them *not ready*, removing them from traffic, not kill them).
- **No CPU `limit`:** CPU limits cause CFS throttling that can stall a JVM mid-GC and make liveness time out — a classic restart-loop cause.

### 5.2 gRPC service using the native gRPC probe (K8s ≥ 1.27)

```yaml
        startupProbe:
          grpc: { port: 9090 }     # uses grpc.health.v1.Health/Check
          failureThreshold: 30
          periodSeconds: 5
        livenessProbe:
          grpc: { port: 9090 }
          periodSeconds: 10
          failureThreshold: 6
        readinessProbe:
          grpc: { port: 9090, service: "checkout.v1.ReadinessService" }  # named service
          periodSeconds: 5
```

In Java (grpc-java), register the standard health service and toggle serving status:

```java
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;

HealthStatusManager health = new HealthStatusManager();
Server server = ServerBuilder.forPort(9090)
    .addService(yourService)
    .addService(health.getHealthService())     // exposes grpc.health.v1.Health
    .build().start();

// Mark NOT_SERVING during warmup / shutdown so the readiness probe pulls us from traffic:
health.setStatus("", ServingStatus.NOT_SERVING);
// ... after pools warm and dependencies verified:
health.setStatus("", ServingStatus.SERVING);

Runtime.getRuntime().addShutdownHook(new Thread(() -> {   // graceful drain on SIGTERM
    health.setStatus("", ServingStatus.NOT_SERVING);       // stop being "ready"
    server.shutdown();                                      // refuse new RPCs, finish in-flight
    try { server.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS); }
    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
}));
```

### 5.3 Robust graceful shutdown in plain Java (no framework)

```java
public final class Main {
  public static void main(String[] args) throws Exception {
    HttpServer http = startHttpServer();      // your server
    var draining = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Readiness endpoint reports 503 once draining begins:
    http.createContext("/readyz", ex -> {
      int code = draining.get() ? 503 : 200;
      ex.sendResponseHeaders(code, -1);
      ex.close();
    });

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      draining.set(true);                     // 1. fail readiness -> removed from endpoints
      sleep(java.time.Duration.ofSeconds(5)); // 2. let LB/kube-proxy deregister us
      http.stop(20);                          // 3. stop accepting; finish in-flight (<=20s)
      closePoolsAndFlush();                   // 4. close DB pools, flush metrics/logs
    }));

    Thread.currentThread().join();            // keep main alive
  }
}
```

Pair with:
```yaml
spec:
  terminationGracePeriodSeconds: 40   # 5s sleep + 20s drain + buffer
```

### 5.4 PodDisruptionBudget for an HA service

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: checkout-pdb
spec:
  minAvailable: 5                      # for replicas: 6 -> at most 1 voluntary disruption
  selector:
    matchLabels: { app: checkout }
  unhealthyPodEvictionPolicy: AlwaysAllow   # don't let crashlooping Pods block node drains
```

Express the *same* intent as a percentage (clearer when replicas autoscale):

```yaml
spec:
  maxUnavailable: "25%"   # at most 25% of selected Pods voluntarily disrupted at once
```

### 5.5 Blue-green with raw Kubernetes (Service selector flip)

```yaml
# Two Deployments: checkout-blue (version=blue), checkout-green (version=green).
# One Service routes to whichever 'version' you select:
apiVersion: v1
kind: Service
metadata: { name: checkout }
spec:
  selector: { app: checkout, version: blue }   # live = blue
  ports: [{ port: 80, targetPort: 8080 }]
```

Cut over after verifying green is healthy:

```bash
# Deploy green, wait until its Pods are all Ready, smoke-test the preview Service,
# then flip 100% of traffic atomically:
kubectl patch service checkout -p '{"spec":{"selector":{"app":"checkout","version":"green"}}}'
# Roll back instantly if needed:
kubectl patch service checkout -p '{"spec":{"selector":{"app":"checkout","version":"blue"}}}'
```

### 5.6 Automated canary with Argo Rollouts (metric-gated)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata: { name: checkout }
spec:
  replicas: 6
  selector: { matchLabels: { app: checkout } }
  template:        # identical to a Deployment Pod template (probes, resources, etc.)
    metadata: { labels: { app: checkout } }
    spec:
      containers:
        - name: checkout
          image: registry.example.com/checkout:1.2.0
          # ... probes as in 5.1 ...
  strategy:
    canary:
      trafficRouting:
        nginx: { stableIngress: checkout-ingress }   # weighted routing via NGINX Ingress
      steps:
        - setWeight: 10
        - pause: { duration: 2m }
        - analysis:                                   # auto-abort on bad metrics
            templates: [{ templateName: success-rate }]
        - setWeight: 30
        - pause: { duration: 5m }
        - setWeight: 60
        - pause: { duration: 5m }
        # implicit final step -> 100% (full promotion)
---
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata: { name: success-rate }
spec:
  metrics:
    - name: success-rate
      interval: 1m
      successCondition: result[0] >= 0.99   # >= 99% success or abort & rollback
      failureLimit: 2
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            sum(rate(http_requests_total{app="checkout",status!~"5.."}[2m]))
            / sum(rate(http_requests_total{app="checkout"}[2m]))
```

### 5.7 Flagger canary (works with an existing Deployment)

```yaml
apiVersion: flagger.app/v1beta1
kind: Canary
metadata: { name: checkout }
spec:
  targetRef: { apiVersion: apps/v1, kind: Deployment, name: checkout }
  service: { port: 80, targetPort: 8080 }
  analysis:
    interval: 1m
    threshold: 5            # 5 failed checks -> rollback
    maxWeight: 50
    stepWeight: 10          # +10% traffic each successful interval
    metrics:
      - name: request-success-rate
        thresholdRange: { min: 99 }      # abort if success rate < 99%
        interval: 1m
      - name: request-duration
        thresholdRange: { max: 500 }     # abort if p99 latency > 500ms
        interval: 1m
    webhooks:
      - name: load-test
        url: http://flagger-loadtester.test/
        metadata: { cmd: "hey -z 1m -q 10 -c 2 http://checkout-canary/" }
```

### 5.8 CI/CD gate: deploy and wait, fail the pipeline on bad rollout

```bash
set -euo pipefail
kubectl set image deployment/checkout checkout=registry.example.com/checkout:1.3.0
# Block until rollout completes; non-zero exit if it stalls past progressDeadlineSeconds:
if ! kubectl rollout status deployment/checkout --timeout=300s; then
  echo "Rollout failed; rolling back."
  kubectl rollout undo deployment/checkout
  exit 1
fi
echo "Rollout succeeded."
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Probe overhead:** `httpGet`/`grpc` are cheap; `exec` forks a process every period — at 1000 Pods × 5s period that's 200 forks/sec cluster-wide. Prefer HTTP/gRPC.
- **`timeoutSeconds: 1` is dangerous for JVMs.** A GC pause (garbage collection — when the JVM reclaims memory, sometimes stopping app threads for tens to hundreds of ms) or a cold endpoint can exceed 1s, failing the probe spuriously. Set `timeoutSeconds: 3`–`5` for JVM apps.
- **Readiness probe must be cheap and dependency-aware but not cascade-prone.** Don't make readiness ping every downstream synchronously on every probe — a single slow dependency can flap your whole fleet out of rotation (correlated failure). Cache dependency health with a short TTL.
- **`maxSurge` needs spare capacity.** A `maxSurge` that the cluster can't schedule (no room) stalls the rollout in `Pending`.

### 6.2 Correctness & concurrency

- **Liveness must test only *local, self-recoverable* state** (event loop alive, not deadlocked). It must **NOT** depend on databases, caches, or other services — otherwise an external outage triggers mass restarts that make things worse.
- **Readiness should reflect "can I serve a request end-to-end,"** which *may* include critical hard dependencies — but be careful about turning a dependency blip into a fleet-wide outage.
- **Use `successThreshold > 1` on readiness** (e.g. 2) to avoid flapping into rotation prematurely.
- **`minReadySeconds`** guards against Pods that pass readiness then immediately fall over.

### 6.3 Memory

- **Container memory limits + JVM:** modern JVMs (8u191+/11+) are container-aware and size the heap off the *cgroup* limit (`-XX:MaxRAMPercentage`, default 25%). If the JVM exceeds the limit, the kernel **OOM-kills** it (exit 137) — which looks like a liveness restart but isn't. Always check `kubectl describe pod` for `OOMKilled` before blaming probes. Set `-XX:MaxRAMPercentage=75` and a generous limit.
- Native memory (threads, metaspace, direct buffers, Netty pools) lives *outside* the heap and also counts against the cgroup limit — a common surprise.

### 6.4 Security

- **Don't expose probe endpoints publicly.** Bind actuator/health to a separate management port not routed via Ingress, or protect it. Health endpoints can leak version/dependency info.
- **`exec` probes run arbitrary commands** in the container — keep them minimal and avoid shelling out to untrusted input.
- **PDBs are a DoS-resistance tool** during maintenance — but a too-strict PDB is itself a self-DoS (blocked drains).

### 6.5 Observability

- **Emit metrics for probe state and rollout progress.** Scrape `kube_pod_status_ready`, `kube_deployment_status_replicas_available`, restart counts (`kube_pod_container_status_restarts_total`) via kube-state-metrics.
- **Alert on:** rising container restarts (liveness loops), Deployment `Available < desired` for >N min, `ProgressDeadlineExceeded`, PDB `ALLOWED DISRUPTIONS = 0` blocking drains.
- **Correlate `Killing`/`Unhealthy`/`BackOff` events** with deploy timestamps. `kubectl get events --sort-by=.lastTimestamp`.

### 6.6 Cost

- High `maxSurge` + frequent deploys = transient extra node cost (and may trigger cluster autoscaler scale-up). Blue-green is the most expensive (2× during cutover). Canary with weighted routing is cheaper (small canary fleet).

### 6.7 Testing

- **Test SIGTERM handling locally:** `docker run` your image, `docker stop` it (sends SIGTERM then SIGKILL after 10s by default) and confirm clean drain.
- **Chaos-test probes:** inject a fake dependency failure and verify Pods go *not-ready* (not restarted) and recover.
- **Game-day a node drain** with your PDB in place to confirm drains complete and capacity holds.

### 6.8 Production hardening checklist

- Readiness on every traffic-serving Pod; startup probe for slow JVM starters; liveness with generous thresholds.
- `terminationGracePeriodSeconds` ≥ preStop + max request duration + buffer.
- `preStop: sleep 5–10s` (or readiness gate for cloud LBs).
- `maxUnavailable: 0` (+ surge) for user-facing services if capacity allows.
- A PDB on every HA workload, `minAvailable` < replicas.
- `progressDeadlineSeconds` set, with CI watching `rollout status` and auto-`undo` on failure.
- Avoid CPU limits on latency-sensitive JVM services (or set them generously) to dodge CFS throttling.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it bites | Fix |
|---|---|---|
| Liveness probe that checks the database | DB outage → all Pods restart → thundering herd → worse outage | Liveness checks only local liveness; put deps in readiness. |
| Same endpoint for liveness & readiness | Slow dep restarts healthy Pods | Separate `/livez` and `/readyz`. |
| No startup probe on slow JVM, huge `initialDelaySeconds` on liveness | Delays real-deadlock detection forever | Use a startup probe. |
| `timeoutSeconds: 1` (default) on JVM | GC pause = spurious failure/restart | Bump to 3–5s. |
| No `preStop`, app exits instantly on SIGTERM | Dropped in-flight requests during deregistration race | `preStop sleep` + graceful drain. |
| PDB `minAvailable = replicas` | Node drains hang forever | `minAvailable < replicas`. |
| `Recreate` for a user-facing API | Guaranteed downtime each deploy | `RollingUpdate` (surge). |
| CPU limit too low on JVM | CFS throttling stalls GC → liveness fails → restart loop | Remove/raise CPU limit; tune GC. |

---

## 7. Advanced topics & deep internals

### 7.1 Rounding edge cases and the "both zero" rule

`maxSurge` ceils, `maxUnavailable` floors. For `replicas: 1`, `25%` → surge 1, unavailable 0; the controller *must* surge to update without downtime. The API rejects `maxSurge: 0` + `maxUnavailable: 0` (deadlock). For very small replica counts, percentages are deceptive — use integers.

### 7.2 `progressDeadlineSeconds` does NOT auto-rollback

When the deadline trips, the Deployment gets `Progressing=False / ProgressDeadlineExceeded`, but it **keeps trying** and does **not** revert. Auto-rollback is your CI/CD's job (check `rollout status`, then `rollout undo`) — *or* use Argo Rollouts/Flagger, which *do* auto-abort and revert.

### 7.3 Readiness during termination & the endpoint race in detail

EndpointSlice has a per-endpoint `conditions` with `ready`, `serving`, and `terminating`. During termination a Pod can be `serving: true, terminating: true, ready: false` — meaning "don't count it as a fresh target, but it can still finish in-flight requests." Topology-aware routing and `ProxyTerminatingEndpoints` (kube-proxy feature) use these to keep draining gracefully. This is why a good `preStop` + graceful drain matters: the data plane *will* keep sending some traffic briefly.

### 7.4 `publishNotReadyAddresses` and headless Services

A **headless Service** (`clusterIP: None`) returns Pod IPs directly via DNS (used by StatefulSets for stable per-Pod addressing). Setting `publishNotReadyAddresses: true` publishes Pods even when not-ready — useful for peer discovery in clustered systems (e.g. a Kafka/Cassandra cluster needs to find peers during bootstrap before any is "ready").

### 7.5 Probe-level `terminationGracePeriodSeconds`

A liveness/startup probe can set its own short grace period so a wedged container is killed fast, independent of the Pod's longer normal-shutdown grace. Great for catching deadlocks quickly without sacrificing graceful drains in the happy path.

### 7.6 `minReadySeconds` interaction with rollout speed

`minReadySeconds` delays counting a Pod as "available," which *throttles* how fast old Pods are retired (the controller needs available Pods to free up budget). It's a deliberate brake that catches "Ready but immediately crashing" regressions. Combine with a modest value (10–30s) for JVM apps.

### 7.7 StatefulSet & DaemonSet rollout differences

- **StatefulSet** updates Pods **in reverse ordinal order**, one at a time (`OrderedReady`), waiting for each to be Running+Ready before the next. The **`partition`** knob enables *staged* (canary-like) rollouts: only Pods with ordinal ≥ partition are updated, so you can update `app-4..app-9` first, observe, then lower the partition. No `maxSurge` (identity is fixed); supports `maxUnavailable` (beta).
- **DaemonSet** RollingUpdate uses `maxUnavailable`/`maxSurge` per node; one Pod per node so the dynamics differ.

### 7.8 Argo Rollouts deep behaviors

- **Analysis modes:** *inline* (a step blocks on an `AnalysisRun`) vs *background* (analysis runs throughout the canary and aborts at any point).
- **`setCanaryScale`** decouples *traffic weight* from *replica count* — you can send 10% traffic to a canary that is scaled to just 1 Pod, saving cost.
- **Experiment** resources run ephemeral A/B variants for a fixed duration.
- **Anti-affinity / `abortScaleDownDelaySeconds`** control how aborts and scale-downs behave; on abort, Argo shifts traffic back to stable instantly and scales the canary down after a delay (fast re-promote possible).

### 7.9 Service mesh and weighted routing internals

True percentage canary needs **weighted routing** at L7 (application layer). A mesh sidecar (Istio's Envoy proxy) or an L7 Ingress/Gateway can split, e.g., 90/10 by HTTP request — independent of replica counts. Without a mesh/ingress weights, "canary by replica ratio" is the only raw-K8s option and is coarse and capacity-coupled.

### 7.10 Sidecars and probe/shutdown ordering

With multiple containers, **all containers' readiness must pass for the Pod to be Ready**, and on shutdown all get SIGTERM. A classic bug: the app drains but the mesh sidecar dies first, severing connectivity mid-drain. Kubernetes **native sidecars** (init containers with `restartPolicy: Always`, GA 1.29) fix ordering: sidecars start before and **terminate after** app containers, so the proxy stays up during the app's drain.

### 7.11 `restartPolicy` nuance

For Deployments the Pod `restartPolicy` is effectively `Always`. **CrashLoopBackOff** is the kubelet exponentially backing off restarts (10→20→40→80→160→300s cap). It is a *symptom*, not a probe setting — diagnose the underlying crash (logs, `lastState.terminated`).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Rollout strategy chooser

| Strategy | Downtime | Extra capacity | Rollback speed | Traffic control | Complexity | Use when… |
|---|---|---|---|---|---|---|
| **Recreate** | Yes (full) | None | Re-deploy old | None | Lowest | Versions can't coexist; dev/test; exclusive volume/lock. |
| **RollingUpdate** | None* | maxSurge worth | `rollout undo` (minutes) | None (all-or-nothing per Pod) | Low | Default for stateless services. |
| **Blue-green (raw)** | None | 2× | Instant (flip back) | All-or-nothing cutover | Medium | Need instant atomic cutover/rollback; can afford 2×. |
| **Canary (Argo/Flagger)** | None | Small (canary fleet) | Instant auto-abort | Weighted, metric-gated | High | High-risk changes; want progressive, automated, observable delivery. |

\* With correct probes + grace + PDB.

### 8.2 Probe decision rules

- **Always** add a **readiness** probe to anything serving traffic.
- **Add a startup probe** if startup time is variable/long (JVM apps: yes).
- **Add liveness sparingly and conservatively.** If you can't articulate a *self-recoverable wedge* that a restart fixes, you may not need liveness at all — a missing liveness probe is safer than a misconfigured one.
- **Liveness: local-only.** **Readiness: serve-ability (incl. critical deps, carefully).**

### 8.3 maxSurge/maxUnavailable rules

- **Latency-sensitive, capacity available:** `maxUnavailable: 0`, `maxSurge: 1+`.
- **Capacity-constrained:** `maxSurge: 0`, `maxUnavailable: 1` (accept reduced capacity).
- **Many replicas, speed matters:** keep `25%`/`25%` defaults.
- **Single replica:** must surge → expect a brief 2-Pod overlap, or accept downtime.

### 8.4 PDB rules

- HA service: `minAvailable: N-1` (or `maxUnavailable: 1`) so drains proceed one node at a time.
- Autoscaling replicas: prefer **percentage** form.
- Set `unhealthyPodEvictionPolicy: AlwaysAllow` to keep drains from getting stuck on crashlooping Pods.
- **Never** `minAvailable == replicas` for drainable workloads.

### 8.5 Argo Rollouts vs Flagger

| Dimension | Argo Rollouts | Flagger |
|---|---|---|
| Workload model | Replaces Deployment with `Rollout` CRD | Wraps existing Deployment via `Canary` CRD |
| Control model | Explicit ordered **steps** (imperative-ish) | **Declarative** auto-stepping analysis |
| Manual gates | First-class (`pause`, promote) | Via webhooks/confirm |
| UI/CLI | Rich dashboard + `kubectl argo rollouts` | CLI/metrics; relies on alerting |
| Routers | Istio, NGINX, ALB, SMI, Gateway API, plugins | Istio, Linkerd, App Mesh, NGINX, Contour, Gloo, Gateway API |
| Best for | Fine-grained, manual+automated control | "Set it and forget it" automated canary |

---

## 9. Failure modes & debugging

### 9.1 Liveness-induced restart loop (the cardinal sin)

**Symptom:** Pods restart repeatedly, eventually `CrashLoopBackOff`, especially under load or during a dependency blip. Whole fleet may cycle.

**Root causes:**
- Liveness probe depends on a DB/downstream → outage restarts everyone (a **thundering herd** that worsens the outage).
- `timeoutSeconds: 1` + JVM GC pause/CPU throttling → spurious timeouts.
- Liveness and readiness share an endpoint that goes 503 on dependency failure.

**Diagnose:**
```bash
kubectl describe pod <p>          # look for "Liveness probe failed", "Killing", restart count
kubectl get pod <p> -o jsonpath='{.status.containerStatuses[0].lastState.terminated}'
kubectl get events --field-selector involvedObject.name=<p> --sort-by=.lastTimestamp
```
Distinguish from **OOMKilled** (reason `OOMKilled`, exit `137`) — that's memory, not probes.

**Fix:** make liveness local-only; raise `timeoutSeconds`/`failureThreshold`; remove tight CPU limits; add a startup probe.

### 9.2 Rollout stuck / hangs

**Symptom:** `kubectl rollout status` never completes; `kubectl get deploy` shows AVAILABLE < desired indefinitely; eventually `ProgressDeadlineExceeded`.

**Causes & checks:**
- New Pods never become Ready (readiness failing). `kubectl describe pod` on a new Pod → readiness failures.
- New Pods can't schedule (no capacity, taints, anti-affinity). `kubectl describe pod` → `Pending`, `FailedScheduling`.
- Image pull errors. → `ImagePullBackOff` / `ErrImagePull`.
- Bad config crashes container. → `CrashLoopBackOff`, check logs `kubectl logs <p> --previous`.
- `maxSurge` can't fit and `maxUnavailable: 0` → no progress possible (can't add, can't remove). Loosen one.

The hang is often *correct behavior*: Kubernetes refuses to retire good Pods because the new ones aren't Ready. That's the safety net protecting you from a broken deploy. Fix the new version or `kubectl rollout undo`.

### 9.3 Dropped requests during deploy (5xx blips)

**Symptom:** brief error/latency spikes on every rollout.

**Causes:** no `preStop` and app exits instantly on SIGTERM while still in endpoints (deregistration race); or grace period too short and SIGKILL cuts in-flight requests; or no readiness on startup so surged Pods get traffic before warm.

**Fix:** `preStop sleep 5–10s`; graceful drain on SIGTERM; adequate `terminationGracePeriodSeconds`; honest readiness/startup probes; consider readiness gates for cloud LBs.

### 9.4 Node drain hangs forever

**Symptom:** `kubectl drain node` stuck on "evicting pod… cannot evict — would violate PDB."

**Cause:** PDB too strict (`minAvailable` == replicas) or all candidate Pods unhealthy with `IfHealthyBudget` policy.

**Diagnose:** `kubectl get pdb` → `ALLOWED DISRUPTIONS: 0`.

**Fix:** loosen PDB, scale up replicas temporarily, or set `unhealthyPodEvictionPolicy: AlwaysAllow`.

### 9.5 Readiness flapping → endpoint churn

**Symptom:** Pods bounce in/out of endpoints; latency/jitter spikes; connection resets.

**Cause:** readiness too sensitive (`timeout: 1`, `failureThreshold: 1`) or readiness pings a flaky dependency on every probe.

**Fix:** raise timeouts/thresholds, `successThreshold: 2`, cache dependency health with TTL, don't fan out to all deps synchronously.

### 9.6 Canary "passes" but is actually broken

**Symptom:** canary promoted on green metrics, but users hit a bug not covered by the metric.

**Cause:** analysis query too narrow (only checks 5xx rate, misses business errors/latency tail).

**Fix:** analyze p99 latency *and* success rate *and* a business KPI; longer analysis windows; baseline comparison (canary vs stable), not absolute thresholds.

### 9.7 Real-world incident patterns

- **The DB-in-liveness cascade:** a brief primary-DB failover made a liveness probe (which queried the DB) fail across the fleet; Kubernetes restarted all Pods simultaneously; cold JVMs + reconnect storm extended a 30s blip into a multi-minute outage. Lesson: never put external deps in liveness.
- **The CPU-limit GC stall:** tight CPU limits caused CFS throttling; during a long GC the liveness HTTP handler couldn't respond within `timeoutSeconds: 1`; mass restarts. Lesson: generous CPU + realistic timeouts + startup probe.
- **The instant-exit drop:** app called `System.exit(0)` on SIGTERM with no drain and no preStop; every rollout dropped a slug of requests during the deregistration race. Lesson: preStop sleep + graceful drain.
- **The drain deadlock:** an over-eager `minAvailable: 100%` PDB blocked a cluster upgrade for hours. Lesson: `minAvailable < replicas`.

---

## 10. Interview drill

**Q1. What's the difference between liveness and readiness probes, and why does it matter?**
*Model answer:* Liveness answers "is the container wedged?" — failing it **restarts** the container. Readiness answers "can it serve traffic now?" — failing it **removes the Pod from Service endpoints** (no traffic) but does **not** restart. It matters because using the wrong one causes outages: a readiness-style dependency check used as liveness will restart healthy Pods during a dependency outage, amplifying it.
- *Follow-up: When would you use neither?* A short-lived batch container, or a service where you have no self-recoverable wedge condition and a misconfigured liveness would be riskier than none.
- *Follow-up: Can a Pod be live but not ready?* Yes — that's the normal warmup/draining state: process is fine but shouldn't get traffic.
- *Follow-up: Which one gates a rolling update's progress?* Readiness — the controller waits for new Pods to be Ready before retiring old ones.

**Q2. Walk me through what happens, step by step, when I `kubectl set image` on a Deployment.**
*Model answer:* (See §3.2.) New template → new ReplicaSet (hash) at 0 replicas → surge new Pods up to `maxSurge` → new Pods start, pass startup/readiness → added to endpoints → controller scales old RS down respecting `maxUnavailable` → repeat until new RS = desired, old = 0 → revision retained for rollback.
- *Follow-up: What stalls it?* New Pods not becoming Ready, scheduling/capacity, image pull errors, or `maxSurge:0 + maxUnavailable:0`.
- *Follow-up: Does hitting `progressDeadlineSeconds` roll back?* No — it just marks the rollout failed; rollback is manual or tool-driven.

**Q3. How do `maxSurge` and `maxUnavailable` work, including rounding?**
*Model answer:* maxSurge = max Pods above desired (ceil); maxUnavailable = max unavailable below desired (floor); both can't be zero. For zero-downtime at full capacity use `maxUnavailable:0, maxSurge:1+`; for tight capacity use `maxSurge:0, maxUnavailable:1`.
- *Follow-up: replicas=1, defaults 25%/25%?* Surge ceils to 1, unavailable floors to 0 → must surge (briefly 2 Pods) to avoid downtime.

**Q4. Why do JVM apps specifically need a startup probe, and how do you size it?**
*Model answer:* JVM cold start (classloading, JIT warmup, pool fill) is slow and variable. A startup probe disables liveness/readiness until startup succeeds, so you get a generous startup budget (`failureThreshold × periodSeconds`) *without* permanently delaying real-deadlock detection (which a large liveness `initialDelaySeconds` would).
- *Follow-up: What if you skip it and just set a big liveness initialDelay?* You delay detecting genuine deadlocks for the container's entire life.
- *Follow-up: Why is `timeoutSeconds:1` risky on JVM?* GC pauses/CPU throttling can exceed 1s → spurious failures.

**Q5. Describe the Pod termination sequence and how to avoid dropped requests. (senior-signal)**
*Model answer:* deletionTimestamp set → endpoint removal begins (async, racy) concurrently with preStop → preStop runs (counts toward grace) → SIGTERM → app drains → grace countdown → SIGKILL. To avoid drops: `preStop sleep` to cover the deregistration race, graceful drain on SIGTERM, grace period ≥ drain + buffer, readiness gates for cloud LBs.
- *Follow-up: Why isn't endpoint removal instantaneous?* It's eventually consistent — controller updates EndpointSlice, kube-proxy/LB reprogram asynchronously.
- *Follow-up: How do native sidecars help?* They terminate after app containers, keeping the proxy up during drain.

**Q6. When would you choose Recreate over RollingUpdate? (senior-signal)**
*Model answer:* When two versions can't coexist — exclusive lock/singleton, ReadWriteOnce volume mounted by one Pod, or a breaking schema migration. Accept the downtime window; otherwise RollingUpdate.
- *Follow-up: Alternative to Recreate that avoids downtime for incompatible schemas?* Expand/contract (backward-compatible migrations) so versions coexist, then RollingUpdate.

**Q7. Explain canary vs blue-green and how you'd implement each on K8s. (senior-signal)**
*Model answer:* Blue-green = two full environments, atomic 100% flip (Service selector/Ingress), instant rollback, 2× cost. Canary = small fraction of traffic to new version, metric-gated progressive ramp, cheaper, needs weighted routing (mesh/ingress) + automated analysis → use Argo Rollouts/Flagger.
- *Follow-up: Poor-man's canary with raw Deployments?* Two Deployments behind one Service, ratio replicas — coarse, capacity-coupled, no real weights.
- *Follow-up: What makes a canary analysis trustworthy?* Multiple signals (success rate + latency tail + business KPI), adequate window, baseline comparison, auto-abort.

**Q8. What is a PodDisruptionBudget and what does it NOT protect against?**
*Model answer:* PDB caps voluntary disruptions (drains, evictions, autoscaler scale-down) via the Eviction API, keeping `minAvailable`/within `maxUnavailable`. It does **not** protect against involuntary disruptions (node crash, kernel panic, hard `delete node`) and doesn't apply to direct Pod deletes that bypass eviction.
- *Follow-up: How can a PDB cause an outage?* `minAvailable == replicas` blocks all drains/upgrades; or it can stall a cluster upgrade.
- *Follow-up: `unhealthyPodEvictionPolicy`?* `AlwaysAllow` lets unhealthy Pods be evicted even at the budget edge, preventing stuck drains.

**Q9. Your rollout is stuck and `kubectl rollout status` hangs. How do you debug?**
*Model answer:* `kubectl get deploy/rs/pods` to see counts; `describe` a new Pod for readiness failures, scheduling (`Pending`), image errors; `logs --previous` for crashes; check `maxSurge/maxUnavailable` aren't deadlocked; check capacity. Often the hang is correct safety behavior — fix the new version or `rollout undo`.
- *Follow-up: How distinguish "stuck because broken" from "slow"?* Look at new-Pod Ready transitions and events; if Pods reach Ready and old ones retire, it's just slow.

**Q10. How do you achieve a truly zero-downtime deploy end to end?**
*Model answer:* Honest readiness + startup probes; `maxUnavailable:0 + maxSurge≥1` (with capacity); graceful SIGTERM drain + `preStop sleep`; grace period sized to drain; PDB for concurrent node events; `minReadySeconds` to catch flappers; CI watches `rollout status` and auto-rolls-back on failure. For high-risk changes, layer canary with metric gating.
- *Follow-up: Where do dropped requests still sneak in?* The endpoint deregistration race and the new-Pod cold-traffic window — closed by preStop and startup/readiness respectively.

**Q11. (senior-signal) You put the database in your liveness probe and a 20-second DB failover happened. What occurs, and how should it have been designed?**
*Model answer:* Liveness fails fleet-wide → all Pods restart simultaneously → cold JVMs + reconnect storm + lost in-flight work turn a 20s blip into a multi-minute outage. Correct design: liveness local-only; DB dependency in readiness (so Pods go *not-ready*, stop taking traffic, and recover when the DB returns — no restarts); consider circuit breakers and `failureThreshold` tuned so transient blips don't even flip readiness.

**Q12. (senior-signal) Argo Rollouts vs Flagger — when would you pick each, and what do they fundamentally add over a Deployment?**
*Model answer:* Both add metric-gated, weighted, reversible progressive delivery (a control loop: shift weight → analyze → promote/abort) that Deployments lack. Pick **Argo Rollouts** for explicit step control, manual gates, and a rich UI (replaces Deployment with a `Rollout` CRD). Pick **Flagger** for declarative, hands-off automated canary wrapping an existing Deployment. Both need a traffic router (mesh/ingress) for real weighted splits.

---

## 11. Glossary

- **Annotation** — non-identifying key/value metadata on an object.
- **AnalysisTemplate/AnalysisRun (Argo)** — metric-query definitions and their executions used to auto-promote/abort canaries.
- **Blue-green** — two full environments; atomic 100% traffic flip; instant rollback; 2× capacity.
- **Canary** — progressive ramp of a small traffic fraction to a new version, metric-gated.
- **cgroup** — Linux kernel feature limiting/accounting resources (CPU, memory) per container.
- **CFS throttling** — the Linux Completely Fair Scheduler pausing a process that exceeds its CPU quota; can stall JVMs.
- **CNI** — Container Network Interface; plugins that wire Pod networking.
- **Control plane** — cluster brain: API server, etcd, controllers, scheduler.
- **Controller** — control loop reconciling desired vs actual state.
- **CRD (Custom Resource Definition)** — extends the K8s API with new object types (e.g. `Rollout`, `Canary`).
- **CrashLoopBackOff** — kubelet exponentially backing off restarts of a repeatedly-crashing container.
- **DaemonSet** — one Pod per node.
- **Deployment** — declarative manager of stateless Pods via ReplicaSets; owns rollout strategy.
- **Draining** — finishing in-flight work and refusing new requests before exit.
- **Endpoints / EndpointSlice** — the set of (ready) Pod IPs behind a Service; EndpointSlice is the modern shardable form.
- **etcd** — strongly consistent key-value store (Raft) holding cluster state.
- **Eviction API** — graceful Pod removal path that respects PDBs.
- **Flagger** — progressive-delivery controller wrapping Deployments with `Canary` CRDs.
- **GC (garbage collection)** — JVM memory reclamation, sometimes pausing app threads.
- **Grace period** (`terminationGracePeriodSeconds`) — SIGTERM-to-SIGKILL window (default 30s).
- **gRPC** — RPC framework over HTTP/2; has a standard health-check protocol used by `grpc` probes.
- **Headless Service** — `clusterIP: None`; DNS returns Pod IPs directly.
- **httpGet/tcpSocket/exec/grpc** — the four probe handler types.
- **Involuntary disruption** — uncontrollable Pod loss (node crash, OOM eviction).
- **JIT** — Just-In-Time compiler; compiles hot JVM bytecode to native code at runtime.
- **kubelet** — per-node agent; starts containers and **runs probes** (directly on Pod IP).
- **kube-proxy** — programs node networking to route Service traffic to endpoints.
- **Label / Selector** — tags and queries used to group and select objects.
- **Liveness probe** — restarts a wedged container.
- **maxSurge / maxUnavailable** — rolling-update bounds (ceil / floor).
- **minReadySeconds** — Pod must stay Ready this long before counted available.
- **Native sidecar** — init container with `restartPolicy: Always`; starts first, terminates last (GA 1.29).
- **Node** — worker machine running the kubelet and Pods.
- **OOMKilled** — kernel killing a container that exceeds its memory limit (exit 137).
- **PodDisruptionBudget (PDB)** — caps voluntary disruptions to preserve availability.
- **Pod** — smallest deployable unit; one+ containers sharing network/storage.
- **postStart / preStop hooks** — lifecycle commands run after start / before SIGTERM.
- **progressDeadlineSeconds** — window after which a no-progress rollout is marked failed (no auto-rollback).
- **publishNotReadyAddresses** — publish Pod IPs even when not-ready (peer discovery).
- **Raft** — leader-based consensus algorithm used by etcd.
- **Readiness gate** — external condition contributing to Pod readiness.
- **Readiness probe** — gates traffic by adding/removing Pod from endpoints.
- **Reconciliation loop** — observe→diff→act cycle; level-triggered, self-healing.
- **Recreate** — kill all old Pods then create new (downtime).
- **ReplicaSet** — keeps N Pods of one immutable template; one RS per version.
- **revisionHistoryLimit** — number of old ReplicaSets kept for rollback (default 10).
- **RollingUpdate** — incremental Pod replacement (default strategy).
- **Rollout (Argo CRD)** — Deployment replacement with canary/blue-green strategies.
- **Service** — stable virtual address load-balancing across selected Pods.
- **SIGTERM / SIGKILL** — polite vs unstoppable termination signals (15 / 9).
- **Sidecar** — helper container in a Pod (proxy, logger).
- **Startup probe** — gates liveness/readiness until slow startup finishes.
- **StatefulSet** — ordered, identity-stable workloads; `partition` for staged rollouts.
- **Thundering herd** — many clients/Pods reacting simultaneously, overloading a recovering system.
- **Voluntary disruption** — controllable Pod removal (drain, rollout, scale-down).
- **Weighted routing** — L7 traffic splitting by percentage (mesh/ingress), required for true canary.

---

## 12. Cheat-sheet & self-test

### 12.1 Dense recap (one screen)

- **One sentence:** readiness **gates traffic**; liveness **restarts**; startup **delays the other two** until warm.
- **Defaults:** probe `periodSeconds 10`, `timeoutSeconds 1` (raise to 3–5 for JVM), `failureThreshold 3`, `successThreshold 1`, `initialDelaySeconds 0`. RollingUpdate `maxSurge 25%` (ceil), `maxUnavailable 25%` (floor). `terminationGracePeriodSeconds 30`. `progressDeadlineSeconds 600`. `revisionHistoryLimit 10`. `minReadySeconds 0`.
- **Zero-downtime recipe:** honest readiness + startup probe; `maxUnavailable:0, maxSurge≥1`; `preStop sleep 5–10s`; graceful SIGTERM drain; grace ≥ drain+buffer; PDB `minAvailable<replicas`; CI watches `rollout status`, `undo` on fail.
- **Never:** put external deps in **liveness**; share one endpoint for liveness+readiness; `maxSurge:0+maxUnavailable:0`; `minAvailable==replicas`; tight CPU limits on latency-sensitive JVMs; `System.exit` on SIGTERM with no drain/preStop.
- **Probe handlers:** `httpGet` (2xx/3xx), `tcpSocket`, `exec` (exit 0, costly), `grpc` (GA 1.27).
- **Termination order:** deletionTimestamp → endpoint removal (async) ∥ preStop → SIGTERM → drain → grace → SIGKILL.
- **PDB:** voluntary only; Eviction API; `unhealthyPodEvictionPolicy: AlwaysAllow` unsticks drains.
- **Canary/BG:** Deployments can't do true canary → use **Argo Rollouts** (steps + UI) or **Flagger** (declarative auto-canary) + a traffic router; blue-green = 2× cost, instant flip.
- **Key commands:** `rollout status|undo|history|restart|pause|resume`; `describe pod` (probe/restart reasons); `get pdb` (ALLOWED DISRUPTIONS); `get endpointslices`.
- **Watch for:** `OOMKilled` (exit 137 ≠ probe), `CrashLoopBackOff`, `ImagePullBackOff`, `FailedScheduling`, `ProgressDeadlineExceeded`.

### 12.2 Self-test (no answers — recall practice)

1. A node failover briefly takes your DB offline and your liveness probe queries the DB. Trace exactly what Kubernetes does and why the outage gets worse. How would you redesign the probes?
2. You have `replicas: 3`, `maxSurge: 25%`, `maxUnavailable: 25%`. Compute the exact max-total and min-available Pod counts during a rollout, and explain the rounding.
3. A teammate reports 5xx blips on every deploy even though Pods "shut down cleanly." Name the two race/timing windows responsible and the precise fixes for each.
4. Write the full SIGTERM→SIGKILL timeline for a Pod with a 5-second `preStop sleep` and `terminationGracePeriodSeconds: 30`. Where does preStop time come from, and how much grace remains for the app to drain?
5. Your `kubectl drain` hangs on a Pod with "would violate PodDisruptionBudget." Give two distinct root causes and the command(s) you'd run to confirm and resolve each.
6. Explain why a JVM service should use a startup probe instead of a large liveness `initialDelaySeconds`, and how you'd size the startup probe's `failureThreshold × periodSeconds`.
7. Contrast how you'd implement a 10% canary with (a) raw Deployments and (b) Argo Rollouts + a service mesh, and explain why only one gives true weighted traffic.
8. Your canary's analysis only checks 5xx rate and it promoted a build that corrupts orders. What additional signals and analysis design would have caught it?

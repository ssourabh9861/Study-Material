# Kubernetes Workload Objects — A Definitive Reference

> Scope: Pods, ReplicaSets, Deployments, StatefulSets, DaemonSets, Jobs/CronJobs, the controller pattern, labels/selectors/annotations, ownerReferences and garbage collection, and how to choose the right object. Written for a senior Java/JVM backend engineer who wants to master this from first principles through deep internals.

---

## 1. Overview & where it fits

### What a "workload object" is

In Kubernetes, a **workload object** is an API resource whose purpose is to **run your application processes** (your containers) somewhere in a cluster and keep them running the way you declared. You don't `ssh` into a machine and start a process; instead you submit a YAML/JSON document describing *desired state* ("I want 5 copies of this container image, with these env vars, these resource limits"), and a set of background control loops works continuously to make reality match that description.

The core workload objects are:

- **Pod** — the smallest deployable unit; one or more co-located containers that share a network namespace and storage. The "atom."
- **ReplicaSet** — keeps *N* identical Pods running. The replication primitive.
- **Deployment** — manages ReplicaSets to give you rolling updates, rollbacks, and revision history for **stateless** apps. The object you use most.
- **StatefulSet** — like a Deployment but gives each Pod a **stable identity** (stable name, stable network hostname, stable per-Pod storage) and **ordered** lifecycle. For databases, queues, clustered systems.
- **DaemonSet** — runs exactly one Pod **per node** (or per matching node). For node-level agents: log shippers, metrics agents, CNI plugins, storage drivers.
- **Job** — runs a Pod (or several) **to completion** then stops. For batch work.
- **CronJob** — creates Jobs on a **time schedule**. The cluster's `cron`.

### The problem it solves

Before orchestration, "running a service reliably" meant: provision a VM, install a process supervisor (systemd, supervisord), write health checks, write deploy scripts, manually handle "what if the box dies," manually balance copies across machines, and hand-roll rolling deploys. Kubernetes workload objects encode all of that — supervision, replication, scheduling, self-healing, rolling updates, and lifecycle — into **declarative API objects backed by controllers**. You declare the end state once; the system maintains it indefinitely, surviving node failures, process crashes, and your own deploys.

> **Declarative vs imperative.** Imperative = "do these steps" (`docker run`, `kubectl run`). Declarative = "this is the state I want; you figure out the steps" (`kubectl apply -f deploy.yaml`). Kubernetes is fundamentally declarative: every workload object is a *record of intent*, and controllers reconcile reality toward that intent forever.

### When you reach for which

| You want to… | Use |
|---|---|
| Run a stateless web/API service, do zero-downtime deploys | **Deployment** |
| Run a database / clustered stateful system needing stable identity + storage | **StatefulSet** |
| Run one agent on every node (logs, metrics, networking) | **DaemonSet** |
| Run a one-off or batch task that finishes | **Job** |
| Run a task on a schedule | **CronJob** |
| Hand-manage replicas with no rollouts (rare) | **ReplicaSet** |
| Run something truly ad-hoc / debug | bare **Pod** |

### One-paragraph mental model

A **Pod** is the unit of scheduling — a small group of containers that always run together on one node sharing an IP. You almost never create Pods directly; instead you create a **higher-level controller** (Deployment, StatefulSet, DaemonSet, Job) that *owns* Pods (sometimes via an intermediate ReplicaSet) and runs a **reconciliation loop**: observe actual state, compare to desired state, take corrective action, repeat. Pods are intentionally **cattle, not pets** — disposable, replaceable, identified by labels rather than names — except in StatefulSets, where they get just enough identity to be "pets with rules."

---

## 2. Foundations from first principles

Let's build the vocabulary you need before going deep. Every term a newcomer might not know is defined inline.

### 2.1 Containers, images, and the runtime

A **container** is an isolated process (or process tree) on a Linux host. The isolation comes from two kernel features:

- **Namespaces** — kernel feature that gives a process its *own view* of a global resource. The main ones: PID (own process tree), network (own interfaces/IP/ports), mount (own filesystem view), UTS (own hostname), IPC, user, and cgroup namespaces. A container is "isolated" because it lives in its own set of namespaces.
- **cgroups (control groups)** — kernel feature that *limits and accounts for* resource usage (CPU, memory, I/O) of a group of processes. This is how Kubernetes enforces "this container gets at most 512 MiB and 0.5 CPU."

A **container image** is a tarball-of-layers plus metadata (entrypoint, env, exposed ports) describing the root filesystem and how to start the process. Built per the **OCI (Open Container Initiative)** spec — the vendor-neutral standard for image format and runtime, so images built by Docker run under any compliant runtime.

A **container runtime** actually starts containers. Kubernetes talks to runtimes via the **CRI (Container Runtime Interface)**, a gRPC API. Common runtimes: **containerd** and **CRI-O**. (Docker itself was removed as a direct runtime in Kubernetes 1.24 — "dockershim" deprecation; containerd, which Docker uses under the hood, remains.) The low-level piece that does the namespace/cgroup syscalls is usually **runc**.

### 2.2 The cluster, nodes, and the control plane

A **cluster** is a set of machines (**nodes**) managed as one. Two roles:

- **Control plane** (the "brain"): runs `kube-apiserver`, `etcd`, `kube-scheduler`, and `kube-controller-manager`.
  - **kube-apiserver** — the single front door. Everything (kubectl, controllers, kubelets) talks REST to it. It validates requests and reads/writes state.
  - **etcd** — a distributed, strongly-consistent key-value store; the **single source of truth** for all cluster state. It uses the **Raft consensus algorithm** (a protocol where a leader replicates an ordered log to followers and a write is committed once a majority/quorum acknowledges it — this is what makes etcd survive node failures without losing data). All workload objects live in etcd.
  - **kube-scheduler** — decides which node each new Pod runs on, based on resource requests, affinity rules, taints, etc.
  - **kube-controller-manager** — a single binary running *most* of the built-in controllers (Deployment controller, ReplicaSet controller, Job controller, the garbage collector, etc.) as goroutines.
- **Worker nodes** (the "muscle"): run `kubelet` and `kube-proxy`.
  - **kubelet** — the node agent. Watches the API server for Pods assigned to its node and instructs the container runtime (via CRI) to start/stop containers, runs probes, reports status.
  - **kube-proxy** — programs the node's networking (iptables/IPVS) so Service virtual IPs route to Pods.

### 2.3 Desired state, reconciliation, and the controller pattern

This is the single most important concept in Kubernetes. A **controller** is a control loop that watches a resource type and drives the world toward the declared spec.

Every object has two key parts:
- **`spec`** — *desired state*, written by you (or another controller).
- **`status`** — *observed state*, written by the controller.

The loop, conceptually:

```
for {
    desired := readSpecFromAPIServer()
    actual  := observeWorld()
    diff    := desired - actual
    if diff != 0 {
        takeCorrectiveAction(diff)   // create/delete/update child objects
    }
    updateStatus(actual)
}
```

This is **level-triggered**, not edge-triggered. (Edge-triggered = react to events/transitions; if you miss the event you miss the change. Level-triggered = react to the *current state* regardless of how you got there; you can drop, dedupe, or replay events and still converge.) Controllers don't trust that they saw every event; they always reconcile against the full current state. That's why Kubernetes self-heals: kill a Pod, the controller simply notices "actual=4, desired=5" and creates one.

> **Mental analogy for a JVM dev:** think of a controller like a thermostat plus a `while(true)` reconciler, or like a `ScheduledExecutorService` that, every tick, diffs an in-memory desired model against an observed model and issues the minimal set of CRUD operations to close the gap — idempotently. The "events" (watches) are just an optimization to wake the loop promptly; correctness comes from re-reading the full state.

### 2.4 The API object envelope

Every workload object shares the same top-level shape:

```yaml
apiVersion: apps/v1        # which API group + version
kind: Deployment           # the object type
metadata:                  # identity & bookkeeping
  name: my-app
  namespace: default
  labels: {...}            # key/value tags used for selection
  annotations: {...}       # arbitrary non-identifying metadata
spec:                      # desired state (you write this)
  ...
status:                    # observed state (controller writes this)
  ...
```

- **`apiVersion`** — `v1` for Pods (core group), `apps/v1` for Deployment/ReplicaSet/StatefulSet/DaemonSet, `batch/v1` for Job/CronJob.
- **`metadata.uid`** — a server-generated UUID; uniquely identifies *this instance* of the object even after delete+recreate of the same name. Used by ownerReferences (see §2.7).
- **`metadata.resourceVersion`** — an opaque, monotonically-increasing token (backed by etcd's revision) used for **optimistic concurrency**. (Optimistic concurrency = no locks; you submit an update tagged with the version you read, and the server rejects it with a `409 Conflict` if someone changed it meanwhile. Like a JPA `@Version` field.)
- **`metadata.generation`** — increments each time `.spec` changes; controllers report `status.observedGeneration` to tell you "I've processed up to spec generation N."

### 2.5 Labels and selectors

A **label** is a key/value pair attached to an object for **identification and grouping** (`app: payments`, `tier: backend`, `env: prod`). Labels are *queryable*; this is how loosely-coupled components find each other.

A **selector** is a query over labels. Two forms:
- **Equality-based**: `app=payments,tier=backend` (comma = AND).
- **Set-based**: `environment in (prod, staging)`, `tier notin (frontend)`, `key`, `!key` (exists / not-exists).

Controllers use selectors to know *which Pods they own*. A ReplicaSet with selector `app=foo` "adopts" any Pod labeled `app=foo` that lacks a controller. This is also how **Services** find their backend Pods. **Critical rule:** a controller's `spec.selector` must match the labels in its `spec.template.metadata.labels`, and in `apps/v1` the selector is **immutable** after creation.

> **Why labels instead of names?** Decoupling. A Service routing to `app=payments` doesn't care if there are 3 Pods or 300, named anything; it routes to whatever currently matches. This is the basis of Kubernetes' dynamic, self-healing wiring.

### 2.6 Annotations

An **annotation** is also a key/value pair on `metadata`, but it is **not** queryable and **not** used for selection. Annotations hold arbitrary metadata: build info, last-applied config (`kubectl.kubernetes.io/last-applied-configuration`), tool hints, ingress controller config, change-cause for rollout history (`kubernetes.io/change-cause`), checksum of a ConfigMap to force a rollout, etc. Labels are for *machines to select*; annotations are for *tools and humans to read*.

### 2.7 ownerReferences and the object graph

Workload objects form a **parent→child ownership graph**, recorded in each child's `metadata.ownerReferences`. Example chain:

```
Deployment  --owns-->  ReplicaSet  --owns-->  Pod
CronJob     --owns-->  Job         --owns-->  Pod
```

Each child Pod's `ownerReferences` lists its ReplicaSet (with `uid`, `kind`, `controller: true`, `blockOwnerDeletion: true`). This graph powers **cascading deletion / garbage collection** (§3.7): delete the Deployment and the GC controller deletes the orphaned ReplicaSets, which orphans the Pods, which then get collected. The `controller: true` flag marks the *single* managing controller (a child can have multiple owners but only one controller).

---

## 3. How it works internally

This is the heart of the document. We trace, step by step, what actually happens for each object.

### 3.1 The Pod: anatomy and lifecycle

#### What a Pod *is* at the kernel level

A Pod is a group of containers that share:
- **A network namespace** → one IP address, one `localhost`, shared port space. Containers in a Pod reach each other via `localhost:<port>`.
- **IPC and (optionally) PID namespace**.
- **Volumes** — shared storage mounts visible to multiple containers.

They do **not** share a mount namespace by default (each container has its own root filesystem) but can share specific volumes.

#### The pause container (the "infra container")

When a Pod starts, the kubelet first creates a tiny, almost-do-nothing container called the **pause container** (image `registry.k8s.io/pause`). Its job: **own and hold the Pod's shared namespaces** (especially the network namespace) so that app containers can join them and so the namespaces persist even if an app container restarts. The pause process just calls `pause()` and reaps zombie processes (it's PID 1 of the Pod). You normally never see it via `kubectl`, but you'll see it in `crictl ps` or `ctr` on the node. Without it, restarting your app container would tear down and recreate the Pod IP.

#### Init containers

**Init containers** run **before** the app containers, **sequentially**, each to **completion**, in declared order. The next won't start until the previous exits 0. Uses: wait for a dependency, run a schema migration, fetch a secret, set kernel sysctls, clone a git repo into a shared volume. If an init container fails, the kubelet retries it per the Pod's `restartPolicy` (and the Pod stays in `Init:` state). Init containers can have *higher* privileges than app containers and don't run concurrently with them, so they're a clean place for one-time setup.

#### Sidecar containers

A **sidecar** is a helper container co-located with the main app to extend it: a log forwarder, a service-mesh proxy (Envoy/Istio), a config reloader, a metrics adapter. Historically sidecars were just "another container in `spec.containers`," which created lifecycle problems (the sidecar wouldn't stop after the main app, breaking Jobs; or wouldn't be ready before the app). **Kubernetes 1.28 introduced native sidecar support (stable in 1.33):** a sidecar is declared as an **init container with `restartPolicy: Always`**. Such a container starts before normal app containers, *keeps running* alongside them, and is terminated *after* them — solving the Job-never-completes and startup-ordering problems.

#### Pod phases and container states

`status.phase` is a coarse summary:

| Phase | Meaning |
|---|---|
| `Pending` | Accepted, but not all containers running (image pulling, scheduling, init containers) |
| `Running` | Bound to a node, at least one container running |
| `Succeeded` | All containers exited 0 and won't restart |
| `Failed` | All containers terminated, at least one failed |
| `Unknown` | Node unreachable; kubelet can't report |

Each container has finer state: `Waiting` (with a `reason` like `ImagePullBackOff`, `CrashLoopBackOff`), `Running`, `Terminated` (with exit code/reason).

`restartPolicy` (Pod-level): `Always` (default; used by Deployments), `OnFailure` (restart only on non-zero exit; used by Jobs), `Never`. Restarts use **exponential backoff** capped at 5 minutes — this is what `CrashLoopBackOff` is: the kubelet is waiting out the backoff between restart attempts.

#### Probes (health checks)

The kubelet runs three probe types per container:

- **liveness probe** — "is the process healthy?" Failing it → kubelet **kills and restarts** the container. Use for deadlock detection. Misuse (e.g. probing a slow dependency) causes restart storms.
- **readiness probe** — "can it serve traffic *now*?" Failing it → Pod removed from Service endpoints (no traffic), but **not** restarted. Use for warm-up, dependency outages.
- **startup probe** — "has the slow-starting app finished booting?" While it runs, liveness/readiness are suspended. Solves the "JVM takes 90s to warm up but I want a tight liveness interval" problem.

Probe mechanisms: `httpGet`, `tcpSocket`, `exec` (run a command, check exit code), and `grpc`. Key params: `initialDelaySeconds`, `periodSeconds` (default 10), `timeoutSeconds` (default 1), `failureThreshold` (default 3), `successThreshold`.

#### Step-by-step: Pod creation → running

1. **Submit.** `kubectl apply` POSTs the Pod (or a controller creates it) to the API server.
2. **Admission & validation.** API server runs authentication, authorization (RBAC), then **admission controllers** (mutating webhooks may inject sidecars/defaults; validating webhooks may reject). Defaults are applied. The object is persisted to **etcd**. At this point `spec.nodeName` is empty.
3. **Scheduling.** The **scheduler** watches for Pods with no `nodeName`. It runs **filtering** (which nodes *can* fit: resource requests, taints/tolerations, node selectors, affinity) then **scoring** (which node is *best*), picks one, and writes a binding (sets `spec.nodeName`). This is just an API update; the scheduler never touches the node.
4. **Kubelet picks it up.** The kubelet on the chosen node is watching for Pods bound to it. It sees the new Pod.
5. **Setup.** Kubelet calls the CRI runtime: pull images (respecting `imagePullPolicy`), create the **pause container** + network namespace (calls the **CNI** plugin to allocate an IP — *CNI = Container Network Interface, the plugin standard for wiring Pod networking*), attach volumes (calls **CSI** drivers — *CSI = Container Storage Interface*), then run **init containers** in order, then start **app/sidecar containers**.
6. **Probes & readiness.** Kubelet runs startup → liveness/readiness probes. Once readiness passes, the **endpoints controller** adds the Pod IP to matching Services.
7. **Status reporting.** Kubelet continuously patches `status` (phase, container statuses, conditions like `Ready`, `PodScheduled`) back to the API server.

#### Step-by-step: Pod termination (graceful shutdown)

1. **Delete request** sets `metadata.deletionTimestamp` and starts the **grace period** (`terminationGracePeriodSeconds`, default **30**).
2. Pod is **removed from Service endpoints** (readiness flips) so new traffic stops.
3. Kubelet runs the **`preStop` hook** (if defined), then sends **SIGTERM** to PID 1 of each container.
4. App should drain in-flight requests and exit. If it doesn't exit by the end of the grace period, kubelet sends **SIGKILL**.
5. Volumes detached, IP released, Pod object removed from etcd.

> **JVM gotcha:** if your Java process isn't PID 1 (e.g. launched via a shell script), it may not receive SIGTERM. Use an init system like `tini`, exec the JVM directly, or run with the JVM as PID 1, and implement a shutdown hook (`Runtime.getRuntime().addShutdownHook`) to drain gracefully.

### 3.2 ReplicaSet: keeping N Pods alive

A **ReplicaSet (RS)** ensures exactly `spec.replicas` Pods matching `spec.selector` exist. Its `spec.template` is the Pod blueprint.

Reconcile loop:
1. List Pods matching `selector` (via a cached **informer**, not a live API call each time — see §7).
2. **Adopt** orphan Pods (matching label, no controller owner) by setting their ownerReference.
3. **Release** Pods that no longer match the selector.
4. Compute `diff = desired - len(activePods)`.
5. If `diff > 0`, **create** that many Pods from the template (with a generated name suffix). Creation is **rate-limited with a "slow start"** batch (1, then 2, then 4, …) to avoid hammering the API server / scheduler if Pods are crash-looping.
6. If `diff < 0`, **delete** that many, choosing victims by a ranking that prefers killing Pods that are: not ready, on nodes with more replicas, younger, etc. (so deletes are "safe").
7. Update `status.replicas`, `readyReplicas`, `availableReplicas`.

You rarely manage RSs directly; Deployments create and manage them. But understanding the RS is essential because a Deployment is "a controller of ReplicaSets."

### 3.3 Deployment: rollouts, rollbacks, revision history

A **Deployment** manages ReplicaSets to give you **versioned, controlled updates** of stateless Pods. Key idea: each distinct Pod template = one ReplicaSet (a **revision**). To deploy a new version, the Deployment controller creates a new RS and shifts replicas from old to new.

#### How a rollout works (RollingUpdate, the default)

`spec.strategy.type: RollingUpdate` with:
- **`maxUnavailable`** (default 25%) — how many Pods *below* desired count you tolerate during the rollout.
- **`maxSurge`** (default 25%) — how many Pods *above* desired count you may temporarily create.

Step by step for "update image from v1 to v2," `replicas: 10`, defaults:
1. You `kubectl set image` / `apply` a new template. The Deployment's `spec.template` changes; `metadata.generation` bumps.
2. The Deployment controller computes a **hash** of the new Pod template (`pod-template-hash`) and looks for an RS with that hash. None exists → it **creates a new RS** (replicas 0) labeled with the hash.
3. It scales the **new** RS up and the **old** RS down in steps, always honoring the constraints: with maxSurge=25% it can have up to 13 Pods total; with maxUnavailable=25% it must keep at least 8 *available*. So it might scale new→3, then as those become Ready, old→7, new→5, … until new=10, old=0.
4. **Readiness gates the rollout.** A new Pod counts as "available" only after it's Ready (and stays ready for `minReadySeconds`). If new Pods never become Ready, the rollout **stalls** — it does not blindly proceed. (`progressDeadlineSeconds`, default **600**, marks the rollout `Failed`/`ProgressDeadlineExceeded` if no progress, but does **not** auto-rollback.)
5. When done, the old RS sits at 0 replicas (kept for history/rollback).

`Recreate` strategy: kill **all** old Pods, then create new ones. Causes downtime; used when two versions can't coexist (e.g. exclusive DB lock, incompatible schema).

#### Revision history and rollback

Each scaled-down old RS is a saved **revision**. `spec.revisionHistoryLimit` (default **10**) caps how many old RSs are retained; older ones are GC'd. The annotation `deployment.kubernetes.io/revision` numbers them. The `kubernetes.io/change-cause` annotation (set via `--record` or manually) describes each.

- `kubectl rollout history deployment/x` — list revisions.
- `kubectl rollout undo deployment/x [--to-revision=N]` — roll back by scaling an old RS back up (it's just another rollout, in reverse).
- `kubectl rollout pause/resume` — freeze a rollout mid-flight (to batch multiple changes into one rollout, or to canary by hand).
- `kubectl rollout status` — block until rollout completes or fails.

> **Important nuance:** Deployments do **not** auto-rollback on failure by default. A failed rollout (Pods crash-looping) simply stalls with old Pods still serving. You roll back manually, or use a higher-level tool (Argo Rollouts, Flagger) for automated canary/rollback.

### 3.4 StatefulSet: stable identity, ordering, per-Pod storage

Use a **StatefulSet (STS)** when Pods are *not* interchangeable — they need stable names, stable network identity, and stable storage that follows the Pod. Examples: Kafka, ZooKeeper, etcd, Cassandra, PostgreSQL primary/replica, Elasticsearch.

> **ZooKeeper** (named because it's a common STS workload): a distributed coordination service providing a consistent hierarchical key-value store, used for leader election, configuration, and naming in clustered systems. Each node needs a stable identity and persistent storage — exactly StatefulSet's reason for existing.

What an STS guarantees:

1. **Stable, ordinal names.** Pods are named `<sts-name>-0`, `<sts-name>-1`, … `-N`. Not random suffixes. `web-0` is always `web-0`; if it dies it's recreated with the same name.
2. **Stable network identity.** Combined with a **headless Service** (`clusterIP: None`), each Pod gets a stable DNS name: `web-0.web-svc.namespace.svc.cluster.local`. (A *headless Service* has no virtual IP; DNS returns the individual Pod IPs / per-Pod records, so clients can address specific members — essential for clustered systems that must know their peers.)
3. **Stable per-Pod storage.** Via `volumeClaimTemplates`, each Pod gets its **own PersistentVolumeClaim** (`data-web-0`, `data-web-1`, …). When a Pod is rescheduled, **the same PVC reattaches** — the data follows the identity. PVCs are **not** deleted automatically when you scale down or delete the STS (by default), to protect data. (Since 1.27+, `persistentVolumeClaimRetentionPolicy` can opt into deletion.)
4. **Ordered, sequential operations.**
   - **Scale up / create:** `-0` first, fully Ready, then `-1`, etc. (governed by `podManagementPolicy: OrderedReady`, the default).
   - **Scale down / delete:** reverse order — highest ordinal first.
   - **Rolling update:** also reverse order, one at a time, waiting for each to be Ready.
   - `podManagementPolicy: Parallel` relaxes ordering for create/delete (not updates) when your app doesn't need it.

#### Update strategies for STS

- **`RollingUpdate`** (default): update Pods from highest ordinal down, one at a time.
  - **`partition: N`** — only Pods with ordinal **≥ N** are updated. Set partition to do a **canary**: e.g. partition=2 on a 3-replica STS updates only `-2`; verify; then lower partition to roll the rest. This is the canonical STS canary mechanism.
- **`OnDelete`**: the controller does **not** update Pods automatically; you delete a Pod and it's recreated with the new spec. Full manual control.

#### Internals worth knowing

- STS reconciliation is more careful than RS: it won't proceed to ordinal N+1 until N is **Running and Ready** (in `OrderedReady`). A stuck `-0` blocks the whole set.
- If a node dies, an STS Pod is **not** automatically rescheduled until Kubernetes is *sure* the old Pod is gone (it can't have two `-0`s with the same identity/storage — that risks split-brain/data corruption). You may need to force-delete or the node must be confirmed dead. This is the "StatefulSet Pod stuck in Terminating after node failure" scenario.
- Headless Service is **required** (`serviceName` field) for stable DNS.

### 3.5 DaemonSet: one Pod per node

A **DaemonSet (DS)** ensures a copy of a Pod runs on **every node** (or every node matching `spec.template.spec.nodeSelector` / affinity). When a node joins, the DS controller adds a Pod to it; when a node leaves, the Pod is GC'd.

Uses: log collectors (Fluentd/Fluent Bit), node metrics (`node-exporter`), CNI network plugins (Calico, Cilium), CSI node drivers, security agents.

Internals:
- The DS controller computes, per node, whether a Pod *should* exist there (matches selectors, fits, tolerations satisfied) and creates/deletes accordingly.
- DaemonSet Pods typically carry **tolerations** for control-plane and "not-ready" taints so they run even on tainted/master nodes (a logging agent should run *everywhere*).
- **Update strategies:** `RollingUpdate` (default) with `maxUnavailable` (and, newer, `maxSurge`) controlling how many node-Pods update at once; or `OnDelete`.
- DaemonSet Pods used to bypass the default scheduler (the DS controller set `nodeName` directly); modern Kubernetes uses the **default scheduler with node affinity** so DS Pods respect resources and affinity like everything else.

### 3.6 Job and CronJob: run to completion / on schedule

#### Job

A **Job** runs Pods until a specified number **succeed**, then stops. Key fields:

- **`completions`** (default 1) — total successful Pod completions required.
- **`parallelism`** (default 1) — how many Pods run at once.
- **`completionMode`**: `NonIndexed` (default — any N successes count) or `Indexed` (each Pod gets a unique `JOB_COMPLETION_INDEX` 0..completions-1; for partitioned/MPI-style work).
- **`backoffLimit`** (default 6) — retries before the Job is marked `Failed`. Restarts use exponential backoff (10s, 20s, 40s … capped at 6 min).
- **`activeDeadlineSeconds`** — wall-clock cap; exceeding it fails the Job and kills its Pods.
- **`ttlSecondsAfterFinished`** — auto-delete the Job (and its Pods) N seconds after it finishes. Without this, completed Jobs accumulate.
- **`podFailurePolicy`** (1.26+) — fine-grained rules: e.g. *don't* count exit code 42 against `backoffLimit`, or *fail fast* on a specific code without retrying.
- **`restartPolicy`** for Job Pods must be `OnFailure` or `Never` (never `Always`).

Patterns: single task (`completions:1, parallelism:1`); fixed completion count (process N items); work-queue (`parallelism:M, completions` unset — Pods pull from an external queue until empty, then exit 0).

#### CronJob

A **CronJob** creates a Job on a **cron schedule**. Fields:

- **`schedule`** — standard cron (`"0 */6 * * *"`), evaluated in `spec.timeZone` (1.27+; previously controller's local time, usually UTC).
- **`concurrencyPolicy`**: `Allow` (default — overlapping runs OK), `Forbid` (skip the new run if the previous is still running), `Replace` (kill the running one, start fresh).
- **`startingDeadlineSeconds`** — if the controller misses the scheduled time by more than this (e.g. control plane was down), skip that run.
- **`suspend`** — pause scheduling without deleting.
- **`successfulJobsHistoryLimit`** (default 3) / **`failedJobsHistoryLimit`** (default 1) — how many finished Jobs to retain.
- The CronJob owns Jobs, which own Pods (full ownership chain for GC).

> **Caveat:** CronJob guarantees *at-least-once-ish*, not exactly-once. Missed schedules (control plane downtime, clock skew) and rare double-creates happen; make your Jobs **idempotent**.

### 3.7 ownerReferences & garbage collection (deep)

The **garbage collector (GC)** is a controller in `kube-controller-manager` that deletes objects whose owners are gone.

- **OwnerReference fields:** `apiVersion`, `kind`, `name`, `uid`, `controller` (bool — is this the managing controller), `blockOwnerDeletion` (bool — should "Foreground" deletion wait for this child).
- **Cascading deletion policies** (`deletionPropagation`, set via `--cascade` or the delete options):
  - **`Background`** (default for `kubectl delete`): delete the owner immediately; GC deletes children asynchronously afterward.
  - **`Foreground`**: owner gets a `foregroundDeletion` **finalizer**; it's marked deleting but *not removed* until all children with `blockOwnerDeletion: true` are gone first. Then the owner is removed. (A **finalizer** is a string in `metadata.finalizers` that *blocks* actual deletion until some controller does cleanup and removes the finalizer — like a pre-delete hook / try-finally.)
  - **`Orphan`**: delete the owner but **leave children**, stripping their ownerReference. (`kubectl delete deploy x --cascade=orphan` leaves the ReplicaSet and Pods running — useful in rare migrations.)
- **Adoption vs orphaning** happens continuously: a controller adopts matching ownerless Pods and orphans Pods that stop matching its selector. This is why editing a Pod's labels can make a ReplicaSet abandon it (then create a replacement) — a classic "why is there an extra Pod?" puzzle.

---

## 4. The complete toolkit

### 4.1 Workload object reference

| Object | apiVersion | Owns | Identity model | Primary use |
|---|---|---|---|---|
| Pod | `v1` | (containers) | ephemeral random name | atom; rarely created directly |
| ReplicaSet | `apps/v1` | Pods | interchangeable | replication primitive (managed by Deployment) |
| Deployment | `apps/v1` | ReplicaSets→Pods | interchangeable | stateless apps, rollouts |
| StatefulSet | `apps/v1` | Pods (+ PVCs) | stable ordinal `-0`,`-1` | stateful/clustered apps |
| DaemonSet | `apps/v1` | Pods | one per node | node agents |
| Job | `batch/v1` | Pods | run-to-completion | batch tasks |
| CronJob | `batch/v1` | Jobs→Pods | scheduled | periodic tasks |

### 4.2 Key spec fields by object (with defaults)

**Pod (`spec`):**

| Field | Default | Purpose |
|---|---|---|
| `containers[]` | — | app containers (required) |
| `initContainers[]` | — | run-once setup, sequential |
| `restartPolicy` | `Always` | `Always`/`OnFailure`/`Never` |
| `terminationGracePeriodSeconds` | `30` | SIGTERM→SIGKILL window |
| `nodeSelector` / `affinity` | — | placement constraints |
| `tolerations[]` | — | tolerate node taints |
| `topologySpreadConstraints[]` | — | spread across zones/nodes |
| `serviceAccountName` | `default` | identity for API access |
| `securityContext` | — | runAsUser, fsGroup, capabilities |
| `priorityClassName` | — | preemption priority |

**Container resources & probes:**

| Field | Default | Purpose |
|---|---|---|
| `resources.requests.{cpu,memory}` | — | scheduling guarantee; for CPU it's the cgroup `shares` weight |
| `resources.limits.{cpu,memory}` | — | hard cap; mem over-limit → **OOMKill**; cpu over-limit → **throttle** |
| `livenessProbe` | — | restart on failure |
| `readinessProbe` | — | gate traffic |
| `startupProbe` | — | shield slow boots |
| `imagePullPolicy` | `IfNotPresent` (or `Always` if tag is `:latest`) | when to pull |
| `lifecycle.preStop` / `postStart` | — | shutdown/startup hooks |

**Deployment (`spec`):**

| Field | Default | Purpose |
|---|---|---|
| `replicas` | `1` | desired Pod count |
| `selector` | — | which Pods (immutable) |
| `template` | — | Pod blueprint |
| `strategy.type` | `RollingUpdate` | or `Recreate` |
| `strategy.rollingUpdate.maxUnavailable` | `25%` | rollout headroom (down) |
| `strategy.rollingUpdate.maxSurge` | `25%` | rollout headroom (up) |
| `minReadySeconds` | `0` | stability wait before "available" |
| `revisionHistoryLimit` | `10` | old RSs kept |
| `progressDeadlineSeconds` | `600` | stall → `Failed` condition |
| `paused` | `false` | freeze rollouts |

**StatefulSet (`spec`):**

| Field | Default | Purpose |
|---|---|---|
| `serviceName` | — | headless Service (required) |
| `replicas` | `1` | members |
| `volumeClaimTemplates[]` | — | per-Pod PVCs |
| `podManagementPolicy` | `OrderedReady` | or `Parallel` |
| `updateStrategy.type` | `RollingUpdate` | or `OnDelete` |
| `updateStrategy.rollingUpdate.partition` | `0` | canary cutoff ordinal |
| `persistentVolumeClaimRetentionPolicy.{whenDeleted,whenScaled}` | `Retain` | PVC cleanup (1.27+) |

**DaemonSet (`spec`):**

| Field | Default | Purpose |
|---|---|---|
| `updateStrategy.type` | `RollingUpdate` | or `OnDelete` |
| `updateStrategy.rollingUpdate.maxUnavailable` | `1` | node-Pods down at once |
| `updateStrategy.rollingUpdate.maxSurge` | `0` | (newer) extra node-Pods |
| `minReadySeconds` | `0` | stability wait |

**Job (`spec`):**

| Field | Default | Purpose |
|---|---|---|
| `completions` | `1` | required successes |
| `parallelism` | `1` | concurrent Pods |
| `backoffLimit` | `6` | retries before fail |
| `activeDeadlineSeconds` | — | wall-clock cap |
| `ttlSecondsAfterFinished` | — | auto-cleanup |
| `completionMode` | `NonIndexed` | or `Indexed` |
| `podFailurePolicy` | — | per-exit-code handling |
| `suspend` | `false` | hold before start |

**CronJob (`spec`):**

| Field | Default | Purpose |
|---|---|---|
| `schedule` | — | cron expression |
| `timeZone` | controller TZ (UTC) | IANA TZ (1.27+) |
| `concurrencyPolicy` | `Allow` | `Forbid`/`Replace` |
| `startingDeadlineSeconds` | — | miss tolerance |
| `successfulJobsHistoryLimit` | `3` | retained successes |
| `failedJobsHistoryLimit` | `1` | retained failures |
| `suspend` | `false` | pause schedule |

### 4.3 kubectl command reference

| Command | Purpose |
|---|---|
| `kubectl apply -f x.yaml` | declarative create/update (preferred) |
| `kubectl create deployment nginx --image=nginx` | imperative quick-create |
| `kubectl get deploy,rs,pod -o wide` | list with node/IP columns |
| `kubectl get pods --show-labels` / `-l app=foo` | view/select by label |
| `kubectl describe pod x` | events + state (first debug stop) |
| `kubectl logs x [-c container] [--previous] [-f]` | logs (incl. crashed container) |
| `kubectl exec -it x -- /bin/sh` | shell into a container |
| `kubectl set image deploy/x c=img:v2` | trigger a rollout |
| `kubectl scale deploy/x --replicas=5` | manual scale |
| `kubectl rollout status/history/undo/pause/resume deploy/x` | rollout control |
| `kubectl rollout restart deploy/x` | restart all Pods (rolling) |
| `kubectl delete deploy x --cascade=orphan` | delete owner, keep children |
| `kubectl explain deploy.spec.strategy` | inline schema docs |
| `kubectl get events --sort-by=.lastTimestamp` | recent cluster events |
| `kubectl drain node-x --ignore-daemonsets` | evict Pods for maintenance |
| `crictl ps` / `crictl logs` (on node) | inspect at runtime level (sees pause container) |

---

## 5. Code examples by use case

### 5.1 Stateless Java service — Deployment with probes, resources, graceful shutdown

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders-api
  labels: { app: orders-api, tier: backend }
spec:
  replicas: 4
  selector:
    matchLabels: { app: orders-api }       # MUST match template labels
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0                     # keep full capacity during deploy
      maxSurge: 1                           # add 1 extra Pod at a time
  minReadySeconds: 10                       # let JVM stabilize before "available"
  template:
    metadata:
      labels: { app: orders-api }
    spec:
      terminationGracePeriodSeconds: 45     # > drain time; JVM shutdown hook drains
      containers:
        - name: app
          image: registry.example.com/orders-api:1.8.3   # never :latest in prod
          ports: [{ containerPort: 8080 }]
          env:
            # Container-aware heap: let JVM read cgroup limits (JDK 10+ does by default)
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
          resources:
            requests: { cpu: "500m", memory: "512Mi" }   # scheduling floor
            limits:   { cpu: "1",    memory: "768Mi" }   # mem cap → OOMKill if exceeded
          startupProbe:                     # shield a slow Spring Boot boot
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            failureThreshold: 30            # 30 * 5s = up to 150s to start
            periodSeconds: 5
          livenessProbe:                    # restart only on true deadlock
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:                   # gate traffic on deps being up
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
            failureThreshold: 3
          lifecycle:
            preStop:
              exec:
                # give the LB time to deregister before SIGTERM hits the JVM
                command: ["sh", "-c", "sleep 5"]
```

Why these choices: `maxUnavailable: 0` + `maxSurge: 1` gives a strictly additive rollout (no capacity dip). `MaxRAMPercentage` keeps heap below the cgroup memory limit so the JVM isn't OOMKilled by the kernel. The `preStop sleep` covers the window between "removed from endpoints" and "SIGTERM," avoiding dropped requests.

### 5.2 Stateful cluster — StatefulSet (e.g. a 3-node quorum service) + headless Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: zk                  # headless Service for stable DNS
spec:
  clusterIP: None           # headless: DNS returns per-Pod records
  selector: { app: zk }
  ports: [{ name: client, port: 2181 }]
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: zk
spec:
  serviceName: zk           # ties STS to the headless Service
  replicas: 3
  podManagementPolicy: OrderedReady   # -0 ready, then -1, then -2
  selector:
    matchLabels: { app: zk }
  template:
    metadata: { labels: { app: zk } }
    spec:
      containers:
        - name: zk
          image: registry.example.com/zookeeper:3.9
          ports:
            - { name: client, containerPort: 2181 }
            - { name: peer,   containerPort: 2888 }
            - { name: leader, containerPort: 3888 }
          env:
            # derive node id from the ordinal in the stable hostname (zk-0 -> 1)
            - name: POD_NAME
              valueFrom: { fieldRef: { fieldPath: metadata.name } }
          volumeMounts:
            - { name: data, mountPath: /var/lib/zookeeper }
  volumeClaimTemplates:               # each Pod gets its OWN PVC that follows it
    - metadata: { name: data }
      spec:
        accessModes: ["ReadWriteOnce"]
        resources: { requests: { storage: 10Gi } }
```

Each Pod resolves peers at `zk-0.zk`, `zk-1.zk`, `zk-2.zk`. PVCs `data-zk-0/1/2` persist across rescheduling. Scaling down to 1 deletes `zk-2` then `zk-1` (reverse order) but **keeps** their PVCs by default.

### 5.3 Node agent — DaemonSet (log shipper on every node)

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: log-agent
spec:
  selector: { matchLabels: { app: log-agent } }
  updateStrategy:
    type: RollingUpdate
    rollingUpdate: { maxUnavailable: 1 }   # update one node at a time
  template:
    metadata: { labels: { app: log-agent } }
    spec:
      tolerations:                          # run even on control-plane/tainted nodes
        - operator: Exists
      containers:
        - name: agent
          image: registry.example.com/fluent-bit:2.2
          resources:
            requests: { cpu: "50m", memory: "64Mi" }
            limits:   { memory: "128Mi" }
          volumeMounts:
            - { name: varlog, mountPath: /var/log, readOnly: true }
      volumes:
        - name: varlog
          hostPath: { path: /var/log }      # read the node's logs
```

`tolerations: [operator: Exists]` makes it tolerate *all* taints so the agent truly runs everywhere. `hostPath` mounts the node's filesystem.

### 5.4 Batch — parallel Job over a fixed work set (Indexed)

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: shard-reindex
spec:
  completions: 8            # 8 shards to process
  parallelism: 4            # 4 at a time
  completionMode: Indexed   # each Pod sees JOB_COMPLETION_INDEX 0..7
  backoffLimit: 6
  activeDeadlineSeconds: 3600
  ttlSecondsAfterFinished: 600   # auto-clean 10 min after finish
  template:
    spec:
      restartPolicy: OnFailure
      containers:
        - name: worker
          image: registry.example.com/reindexer:2.0
          command: ["java", "-jar", "/app/reindex.jar"]
          env:
            - name: SHARD_INDEX            # use the index to pick a shard
              valueFrom:
                fieldRef: { fieldPath: metadata.annotations['batch.kubernetes.io/job-completion-index'] }
```

`completionMode: Indexed` partitions deterministic work; each worker handles exactly one shard via its index.

### 5.5 Scheduled task — CronJob (nightly report, no overlap)

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: nightly-report
spec:
  schedule: "0 2 * * *"        # 02:00 daily
  timeZone: "Asia/Kolkata"     # 1.27+; otherwise controller TZ (UTC)
  concurrencyPolicy: Forbid    # skip if last night's run still going
  startingDeadlineSeconds: 300 # skip if missed by >5 min (e.g. CP outage)
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 1
  jobTemplate:
    spec:
      backoffLimit: 2
      ttlSecondsAfterFinished: 86400
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: report
              image: registry.example.com/reporter:1.4
              command: ["java", "-jar", "/app/report.jar", "--date=yesterday"]
```

### 5.6 Multi-container Pod — app + native sidecar (1.28+ pattern) + init container

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: meshed-api }
spec:
  replicas: 2
  selector: { matchLabels: { app: meshed-api } }
  template:
    metadata: { labels: { app: meshed-api } }
    spec:
      initContainers:
        - name: migrate                    # run-once schema migration before app
          image: registry.example.com/migrator:1.0
          command: ["java", "-jar", "/migrate.jar"]
        - name: proxy                       # NATIVE SIDECAR: init container that stays up
          image: registry.example.com/envoy:1.29
          restartPolicy: Always             # <-- makes it a sidecar (starts first, dies last)
          ports: [{ containerPort: 15001 }]
      containers:
        - name: app
          image: registry.example.com/meshed-api:3.1
          ports: [{ containerPort: 8080 }]
```

The `proxy` starts and becomes ready **before** `app`, runs **alongside** it, and is terminated **after** it — so the app always has its mesh proxy and (critically for Jobs) the sidecar doesn't keep the Pod from completing.

### 5.7 Inspecting ownership and the object graph

```bash
# See the Deployment -> ReplicaSet -> Pod chain and pod-template-hash
kubectl get rs -l app=orders-api -o wide
kubectl get pod -l app=orders-api \
  -o custom-columns='POD:.metadata.name,OWNER:.metadata.ownerReferences[0].name'

# Delete the Deployment but KEEP the ReplicaSet+Pods (orphan)
kubectl delete deployment orders-api --cascade=orphan

# Foreground delete: owner removed only after children are gone
kubectl delete deployment orders-api --cascade=foreground
```

---

## 6. Implementation concerns & best practices

### Performance
- **Always set `requests` and `limits`.** Requests drive scheduling and CPU shares; without them the scheduler bin-packs blindly and you get noisy-neighbor contention. CPU *limits* cause **throttling** (cgroup CFS quota) — for latency-sensitive JVM apps, consider setting CPU requests but **no CPU limit** (controversial but common) to avoid p99 spikes from throttling; always keep a **memory limit** (memory has no graceful throttle — over-limit = OOMKill).
- **`maxSurge`/`maxUnavailable` tuning:** for capacity-critical services use `maxUnavailable: 0`; for fast deploys raise `maxSurge`.
- **Informer-driven controllers** (§7) scale well, but very high object counts (tens of thousands of Pods) stress etcd and watch caches; shard with namespaces.

### Correctness / concurrency
- **Selector immutability** in `apps/v1`: get it right at creation; changing it requires recreating the object.
- **Idempotent Jobs/CronJobs:** assume at-least-once. Use a dedupe key, conditional writes, or a leader lock.
- **StatefulSet split-brain:** never force-delete a stuck STS Pod unless the node is *confirmed* dead; two Pods sharing one identity/PVC corrupts data.
- **Optimistic concurrency:** automation patching objects must handle `409 Conflict` by re-reading `resourceVersion` and retrying.

### Memory (JVM-specific)
- Use **`-XX:MaxRAMPercentage`** (not fixed `-Xmx`) so heap scales with the cgroup limit. Modern JDKs are cgroup-v2 aware; very old JDK 8 builds read the *host* memory and over-allocate → OOMKill. Account for off-heap (metaspace, thread stacks, direct buffers): heap ≈ 65–75% of the limit.

### Security
- Run as **non-root** (`securityContext.runAsNonRoot: true`, `runAsUser`), drop capabilities, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`.
- Use a **dedicated ServiceAccount** with least-privilege RBAC, not `default`.
- Pin images by **digest** (`@sha256:...`) for immutability; scan images; avoid `:latest`.
- Use `seccompProfile: RuntimeDefault` and a restricted **Pod Security Standard**.

### Observability
- Expose `/metrics` (Prometheus), structured logs to stdout (DaemonSet ships them), and traces.
- Watch: `kube_deployment_status_replicas_available`, `kube_pod_container_status_restarts_total`, OOMKills, CrashLoopBackOff counts, rollout duration.
- `kubectl describe` + events are your first diagnostic; `kubectl get events --sort-by=.lastTimestamp`.

### Cost
- Right-size requests (over-requesting wastes cluster capacity — the scheduler reserves it). Use VPA recommendations or historical usage. Use `ttlSecondsAfterFinished` and history limits so finished Jobs don't pile up in etcd.

### Testing & production hardening
- **PodDisruptionBudget (PDB):** declare `minAvailable`/`maxUnavailable` so voluntary disruptions (node drains, upgrades) don't take you below capacity.
- **topologySpreadConstraints / anti-affinity:** spread replicas across nodes and zones so one failure doesn't kill all replicas.
- **PriorityClass** for critical workloads (preempt lower-priority Pods under pressure).
- Test rollouts in staging with `kubectl rollout status` gating CI.

### Anti-patterns to avoid
- Creating **bare Pods** for services (no self-healing — if the node dies, the Pod is gone forever).
- Using a **Deployment for stateful data** (no stable identity/storage → data loss/corruption).
- **Liveness probe pointing at a dependency** → cascading restart storms when the dependency blips.
- **No resource requests** → unschedulable surprises and noisy neighbors.
- **`:latest` tags** → non-reproducible rollouts; can't roll back to a known image.
- **Mutating a Deployment's `selector`** or hand-editing managed Pods.
- **CronJobs assuming exactly-once** → duplicate side effects.
- **No `terminationGracePeriodSeconds`/`preStop`** for apps that need draining → dropped requests on every deploy.

---

## 7. Advanced topics & deep internals

### 7.1 Informers, the shared cache, and work queues

Controllers don't poll the API server in a tight loop. Each uses an **informer**: a client-go component that does an initial **LIST** of a resource type, then opens a **WATCH** (a long-lived HTTP stream of add/update/delete events from etcd via the API server) to keep a **local in-memory cache (the store/indexer)** up to date. The controller's reconcile loop reads from this cache (fast, no API round-trips). Events are funneled into a **rate-limited work queue** keyed by object; the queue **dedupes** (multiple events for one object collapse) and supports **exponential backoff** on retry. **SharedInformerFactory** lets many controllers share one watch per type. This is the engine that makes the whole control plane scale.

> **JVM analogy:** an informer is like a local read-through cache fed by a change-data-capture stream, and the work queue is a deduplicating `DelayQueue`/`BlockingQueue` with backoff. Reconcile pops a key, reads current state from cache, acts idempotently, and re-enqueues with backoff on error.

### 7.2 The `pod-template-hash` and RS identity

Deployments compute a hash of the Pod template and stamp it as the `pod-template-hash` label on the RS and its Pods, and fold it into the RS name and selector. This guarantees that a new template → new RS (new revision) and that Pods are unambiguously owned. Editing the template recomputes the hash; reverting to a prior template **reuses the old RS** (rollback is "scale the old RS back up").

### 7.3 Server-Side Apply and field management

`kubectl apply --server-side` records **field ownership** (`metadata.managedFields`): which manager set which field. Multiple controllers/users can co-own an object without clobbering each other; conflicts are explicit. This replaces the fragile client-side three-way merge based on the `last-applied-configuration` annotation.

### 7.4 Scaling subresource & HPA interaction

Deployments/StatefulSets/RS expose a `/scale` subresource. The **HorizontalPodAutoscaler (HPA)** writes `spec.replicas` through it based on metrics (CPU, memory, custom). Pitfall: if you also set `replicas` in your YAML and `apply`, you fight the HPA. Solution: omit `replicas` from managed manifests when an HPA owns it, or use SSA field ownership.

### 7.5 StatefulSet ordinal/partition deep behavior
- `partition` lets you stage updates by ordinal — the canonical canary. With `replicas: 5, partition: 4`, only `-4` updates; verify, then drop partition stepwise.
- **Start ordinal** (`spec.ordinals.start`, beta) lets a StatefulSet begin at a nonzero ordinal — used for splitting/migrating StatefulSets across clusters.
- A StatefulSet with a perpetually-not-Ready `-0` (e.g. bad config) **wedges** the entire rollout/scale-up under `OrderedReady`; switch to `Parallel` only if your app tolerates it.

### 7.6 Job `podFailurePolicy` & `successPolicy`
- `podFailurePolicy` (1.26+ GA later) lets you classify failures: `Ignore` certain exit codes (don't count toward `backoffLimit`), `FailJob` immediately on a fatal code, or react to a `DisruptionTarget` condition (don't penalize preemption). Critical for long, expensive Jobs.
- `successPolicy` (1.31+) for Indexed Jobs: succeed when a subset of indexes complete.

### 7.7 DaemonSet scheduling internals
Modern DaemonSets use the default scheduler with **node affinity** injected per node and a special toleration for the `node.kubernetes.io/unschedulable` taint, so DS Pods land even on cordoned nodes (a node agent should keep running during drains). `kubectl drain --ignore-daemonsets` exists precisely because DS Pods are *meant* to stay.

### 7.8 Finalizers and stuck deletions
An object with a finalizer stays in `Terminating` until the responsible controller removes the finalizer. If that controller is broken/gone, the object is stuck forever. Diagnose with `kubectl get x -o yaml | grep finalizers`; last-resort fix is to patch them out (`kubectl patch ... -p '{"metadata":{"finalizers":[]}}' --type=merge`) — but only after confirming cleanup is truly unnecessary, or you leak the underlying resource.

### 7.9 Lesser-known behaviors
- **`minReadySeconds`** applies to Deployments, RS, DaemonSets, StatefulSets — a Pod isn't "available" until Ready for this long; protects against flappy readiness during rollouts.
- **`progressDeadlineSeconds`** sets a `Progressing=False/ProgressDeadlineExceeded` *condition* but does **not** roll back.
- **`revisionHistoryLimit: 0`** keeps no old RSs — you lose rollback.
- **Pod `restartPolicy: Always`** is the only legal value for Deployment/RS Pods; Jobs forbid it.
- **`activeDeadlineSeconds`** exists on both Pod spec and Job spec with different scopes (Pod = per-Pod active time; Job = whole-Job wall clock).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing the right workload object

| Need | Choose | Avoid | Because |
|---|---|---|---|
| Stateless service, zero-downtime deploy | **Deployment** | bare Pod, RS | rollouts + self-heal + history |
| Stable network ID / per-Pod storage / ordered | **StatefulSet** | Deployment | identity & data follow the Pod |
| One agent per node | **DaemonSet** | Deployment with replicas=#nodes | auto-tracks node add/remove |
| Finite batch, must finish | **Job** | Deployment | run-to-completion semantics |
| Periodic task | **CronJob** | external cron + kubectl | native scheduling + history |
| Manual replica control, no rollouts | **ReplicaSet** | (almost never) | Deployment is strictly better |

### 8.2 Deployment vs StatefulSet

| Aspect | Deployment | StatefulSet |
|---|---|---|
| Pod names | random suffix | stable ordinal `-0`,`-1` |
| Network identity | ephemeral, via Service VIP | stable per-Pod DNS (headless Svc) |
| Storage | shared/none | per-Pod PVC that persists & follows |
| Ordering | none (parallel) | ordered (OrderedReady) |
| Update | RS swap, parallel-ish | one-at-a-time, reverse ordinal, `partition` canary |
| Scale-down data | n/a | PVCs retained by default |
| Use for | web/API, workers | DBs, queues, quorum systems |

### 8.3 RollingUpdate vs Recreate vs (canary/blue-green)

| Strategy | Downtime | Two versions coexist? | Use when |
|---|---|---|---|
| RollingUpdate | none | yes | default; backward-compatible changes |
| Recreate | yes | no | incompatible versions / exclusive locks |
| Canary (Argo/Flagger) | none | yes, gradual | risk-managed, metric-gated rollout |
| Blue-Green | none | both fully, then switch | instant cutover/rollback, 2× capacity cost |

### 8.4 Job parallelism patterns

| Pattern | `completions` | `parallelism` | Mode | Use |
|---|---|---|---|---|
| Single task | 1 | 1 | NonIndexed | one-shot |
| Fixed count | N | M | NonIndexed | N independent items |
| Indexed | N | M | Indexed | partitioned/static-sharded |
| Work queue | unset | M | NonIndexed | Pods drain an external queue |

---

## 9. Failure modes & debugging

### 9.1 Common failures, causes, and diagnosis

| Symptom | Likely cause | Diagnose with |
|---|---|---|
| `Pending` forever | unschedulable (no node fits requests, taints, affinity, no PV) | `kubectl describe pod` → Events ("0/5 nodes available: insufficient cpu") |
| `ImagePullBackOff` / `ErrImagePull` | bad image/tag, missing registry creds | `kubectl describe pod`; check `imagePullSecrets` |
| `CrashLoopBackOff` | app exits/crashes; kubelet backing off restarts | `kubectl logs --previous`; check exit code |
| `OOMKilled` (exit 137) | memory limit exceeded | `kubectl describe pod` → Last State; tune `MaxRAMPercentage`/limit |
| Liveness restart storms | liveness probe too aggressive / probes a dependency | check probe config & timings |
| Rollout stuck `Progressing` | new Pods never Ready | `kubectl rollout status`; `kubectl get rs`; logs of new Pods |
| STS Pod stuck `Terminating` | node down; can't safely recreate identity | confirm node dead; force-delete only then |
| Job never completes | non-native sidecar keeps Pod alive; or backoff loop | use native sidecar; check `backoffLimit`/logs |
| Extra/unexpected Pod | label edit caused orphan + replacement | check ownerReferences & labels |
| CronJob not firing | wrong timezone, `suspend: true`, missed `startingDeadline` | `kubectl describe cronjob`; check `lastScheduleTime` |

### 9.2 Diagnostic workflow

1. `kubectl get pods -o wide` — phase, restarts, node.
2. `kubectl describe pod <p>` — **Events** section is the gold mine (scheduling, pulls, probe failures, OOM).
3. `kubectl logs <p> [-c <c>] [--previous]` — app output, including the crashed instance.
4. `kubectl get rs / kubectl rollout status deploy/x` — rollout state and which RS is active.
5. `kubectl get events --sort-by=.lastTimestamp` — cluster-wide recent events.
6. On the node: `crictl ps`, `crictl logs`, `dmesg | grep -i oom` for kernel OOM kills.
7. `kubectl exec -it <p> -- sh` — inspect live; check `/proc`, env, mounts.

### 9.3 Real-world incident patterns
- **Memory limit + JDK 8 (pre-8u191):** JVM read host RAM, set a huge heap, kernel OOMKilled the container at random GC times → flapping. Fix: cgroup-aware JDK + `MaxRAMPercentage`.
- **Liveness probe on a shared DB:** DB had a 30s blip; every Pod's liveness failed simultaneously; kubelet restarted them all at once; the restart stampede prolonged the outage. Fix: move dependency checks to *readiness*, keep liveness to "is the JVM alive."
- **Job stuck because of Istio sidecar:** pre-1.28, the Envoy sidecar never exited, so the Job Pod stayed `Running` and `completions` never hit. Fix: native sidecars or a sidecar-quit signal.
- **Lost data after "redeploy a database with a Deployment":** ephemeral storage + parallel rollout → split brain and wiped volume. Fix: StatefulSet with `volumeClaimTemplates`.
- **CronJob double-run after control-plane restart:** missed schedules fired late and overlapped. Fix: `concurrencyPolicy: Forbid` + `startingDeadlineSeconds` + idempotent job.

---

## 10. Interview drill

**Q1. What's the difference between a Deployment and a ReplicaSet, and why do you almost never create a ReplicaSet directly?**
*Model answer:* A ReplicaSet keeps N identical Pods alive matching a selector — pure replication, no versioning. A Deployment manages ReplicaSets to add rolling updates, rollbacks, and revision history: each Pod-template change creates a new RS (identified by `pod-template-hash`) and the Deployment shifts replicas old→new under `maxSurge`/`maxUnavailable`. You use Deployments because the RS alone can't do controlled updates.
- *Follow-up: How does rollback work?* The old RS is scaled to 0 but retained (up to `revisionHistoryLimit`); `rollout undo` scales the chosen old RS back up — a reverse rollout. No image re-pull logic; it's just replica shifting.
- *Follow-up: What is `pod-template-hash`?* A hash of the Pod template stamped on RS name/selector/labels so each distinct template maps to exactly one RS and Pods are unambiguously owned.

**Q2. When do you need a StatefulSet instead of a Deployment?**
*Model answer:* When Pods are not interchangeable: you need stable network identity (`pod-N` with stable DNS via a headless Service), per-Pod persistent storage that follows the Pod (`volumeClaimTemplates`), and/or ordered, sequential lifecycle. Typical: databases, Kafka, ZooKeeper, etcd, Cassandra.
- *Follow-up: Why is a headless Service required?* It has no cluster IP, so DNS returns per-Pod records (`pod-0.svc...`), letting cluster members address each other by stable name.
- *Follow-up: Why isn't a StatefulSet Pod auto-rescheduled when its node dies?* Because two Pods can't share one identity/PVC without risking split-brain/data corruption; Kubernetes waits until it's certain the old Pod is gone (or you force-delete after confirming the node is dead).

**Q3. Explain the controller/reconciliation pattern. Why level-triggered?**
*Model answer:* Controllers run a loop: read desired (`spec`), observe actual, diff, take corrective idempotent action, repeat. Level-triggered means they react to the *current full state*, not to events, so they converge even if events are missed, duplicated, or replayed — that's what gives self-healing.
- *Follow-up: How do they avoid polling?* Informers: initial LIST + WATCH stream feed a local cache; a deduplicating, backoff-aware work queue drives reconciles.
- *(Senior signal) Follow-up: What breaks if a controller's actions aren't idempotent?* Re-reconciles (which happen constantly) would create duplicates or oscillate; correctness depends on "apply desired state" being safe to repeat.

**Q4. Walk through what happens from `kubectl apply` of a Pod to it running.**
*Model answer:* API server authn/authz → admission (mutating/validating webhooks) → persist to etcd (no nodeName) → scheduler filters+scores+binds a node → kubelet on that node pulls images, creates pause container + network ns (CNI), mounts volumes (CSI), runs init containers, starts app/sidecar containers, runs probes, reports status; endpoints controller adds it to Services on readiness.
- *Follow-up: What's the pause container for?* Holds the Pod's shared namespaces (esp. network) so app containers can join them and the Pod IP survives container restarts; it's PID 1 and reaps zombies.
- *Follow-up: How does graceful termination work?* deletionTimestamp + grace period (default 30s); removed from endpoints; preStop hook; SIGTERM; SIGKILL if not exited in time.

**Q5. How do rolling updates stay safe? What if new Pods never become Ready?**
*Model answer:* `maxSurge`/`maxUnavailable` bound how far above/below desired count the rollout goes; new Pods count as available only after readiness (+`minReadySeconds`). If new Pods never become Ready, the rollout **stalls** with old Pods still serving; `progressDeadlineSeconds` flips a `ProgressDeadlineExceeded` condition but does **not** auto-rollback.
- *(Senior signal) Follow-up: maxUnavailable: 0 vs maxSurge: 0 — tradeoffs?* `maxUnavailable:0`+`maxSurge>0` = never lose capacity but temporarily need extra resources/quota. `maxSurge:0`+`maxUnavailable>0` = no extra capacity but you dip below desired during deploy. Choose by whether capacity headroom or spare quota is scarcer.

**Q6. How does garbage collection / cascading deletion work?**
*Model answer:* Children carry `ownerReferences` to parents. The GC controller deletes children whose owners are gone. `--cascade=background` (default) deletes owner now, children async; `foreground` uses a finalizer so the owner waits for children with `blockOwnerDeletion`; `orphan` keeps children and strips their ownerRef.
- *Follow-up: What's a finalizer?* A string blocking actual deletion until a controller does cleanup and removes it — a pre-delete hook; broken finalizers cause stuck `Terminating`.

**Q7. Job vs CronJob; how do you make a CronJob safe?**
*Model answer:* A Job runs Pods to completion (`completions`/`parallelism`/`backoffLimit`); a CronJob creates Jobs on a schedule. Make it safe with `concurrencyPolicy: Forbid/Replace`, `startingDeadlineSeconds`, history limits, `timeZone`, and idempotent job logic (CronJobs are at-least-once-ish).
- *Follow-up: How do you stop completed Jobs from piling up?* `ttlSecondsAfterFinished` on Jobs and `successful/failedJobsHistoryLimit` on the CronJob.

**Q8. Why must liveness and readiness probes be designed differently?**
*Model answer:* Liveness failure → restart (for true deadlocks); readiness failure → remove from traffic (for warm-up/dependency outages, no restart). Putting a dependency check in liveness causes restart storms when the dependency blips.
- *Follow-up: When add a startup probe?* For slow-booting apps (JVM warm-up): it disables liveness/readiness until startup succeeds, so you can keep tight liveness intervals without false restarts.

**Q9. (Senior signal) You're told to "just use a Deployment with replicas=3 and a PVC" for Postgres. Push back.**
*Model answer:* A Deployment gives no stable identity and a single shared PVC (RWO) can't attach to multiple Pods; parallel rollouts can run two Pods against the same volume → corruption/split-brain, and there's no ordered primary/replica bring-up. The right tool is a StatefulSet (or an operator) with `volumeClaimTemplates` per Pod, a headless Service for stable peer DNS, ordered startup, and a `partition`-based canary for upgrades.

**Q10. (Senior signal) How do you achieve zero-downtime deploys for a JVM service end to end?**
*Model answer:* `maxUnavailable: 0` + `maxSurge`, readiness probe gating traffic, `minReadySeconds` for stability, startup probe for warm-up, `preStop sleep` + adequate `terminationGracePeriodSeconds` so in-flight requests drain after endpoint removal, JVM shutdown hook to stop accepting and finish work, a PodDisruptionBudget so drains don't breach capacity, and anti-affinity/topology spread so a node loss doesn't take all replicas.

**Q11. (Senior signal) Two controllers and a user keep overwriting each other's fields on one object. How do you fix it cleanly?**
*Model answer:* Use Server-Side Apply: each manager declares the fields it owns (`managedFields`), conflicts become explicit instead of silent clobbers. For replicas owned by an HPA, omit `replicas` from your manifest (or let SSA track ownership) so `apply` doesn't fight the autoscaler.

---

## 11. Glossary

- **Admission controller** — API-server plugin that mutates or validates objects before persistence (e.g. inject sidecars, enforce policy).
- **Affinity / anti-affinity** — rules attracting/repelling Pods to/from nodes or other Pods.
- **Annotation** — non-identifying, non-selectable metadata key/value on an object.
- **Available (replica)** — a Pod that has been Ready for at least `minReadySeconds`.
- **Backoff (exponential)** — increasing wait between retries (kubelet restarts, work queues).
- **cgroups** — Linux kernel feature limiting/accounting CPU, memory, I/O per process group.
- **CNI (Container Network Interface)** — plugin standard for wiring Pod networking and IPs.
- **Control plane** — cluster brain: API server, etcd, scheduler, controller-manager.
- **Controller** — control loop reconciling actual state toward desired (`spec`).
- **CRI (Container Runtime Interface)** — gRPC API between kubelet and the container runtime.
- **CronJob** — creates Jobs on a cron schedule.
- **CSI (Container Storage Interface)** — plugin standard for attaching/mounting storage.
- **DaemonSet** — runs one Pod per (matching) node.
- **Declarative** — describe desired end state; system computes steps.
- **Deployment** — manages ReplicaSets for stateless rollouts/rollbacks.
- **Edge- vs level-triggered** — react to transitions vs react to current state; Kubernetes is level-triggered.
- **etcd** — distributed strongly-consistent KV store; cluster source of truth (uses Raft).
- **Finalizer** — string blocking deletion until a controller performs cleanup.
- **Garbage collection (GC)** — controller deleting objects whose owners are gone.
- **Generation / observedGeneration** — spec change counter / controller's processed counter.
- **Headless Service** — Service with `clusterIP: None`; DNS returns per-Pod records.
- **HPA (HorizontalPodAutoscaler)** — adjusts `replicas` based on metrics via the scale subresource.
- **Image (OCI)** — packaged container filesystem + metadata per the OCI spec.
- **Informer** — client-go LIST+WATCH-backed local cache feeding a controller.
- **Init container** — run-once, ordered, pre-app container.
- **Job** — runs Pods to completion.
- **kubelet** — node agent driving the container runtime and reporting status.
- **kube-proxy** — programs node networking for Services.
- **Label / selector** — identifying key/value tags / queries over them; the basis of loose coupling.
- **Liveness probe** — health check; failure restarts the container.
- **maxSurge / maxUnavailable** — rollout headroom above/below desired count.
- **minReadySeconds** — readiness-stability wait before "available."
- **Namespaces (kernel)** — per-process isolated views of system resources.
- **Node** — a worker machine in the cluster.
- **OOMKilled** — kernel killed a container for exceeding its memory limit (exit 137).
- **ownerReference** — parent pointer on a child enabling GC.
- **Pause container** — tiny container holding a Pod's shared namespaces; PID 1; reaps zombies.
- **Partition (StatefulSet)** — ordinal cutoff staging updates (canary).
- **PDB (PodDisruptionBudget)** — limits voluntary disruptions to keep availability.
- **Pod** — smallest deployable unit; co-located containers sharing network/storage.
- **podManagementPolicy** — `OrderedReady` vs `Parallel` for StatefulSet create/delete.
- **PriorityClass** — Pod priority for scheduling/preemption.
- **Probe (startup/liveness/readiness)** — kubelet health/traffic checks.
- **PVC / PV (PersistentVolumeClaim/Volume)** — a request for / a piece of durable storage.
- **Raft** — leader-based consensus protocol; how etcd stays consistent across failures.
- **Reconciliation loop** — observe→diff→act→repeat.
- **Readiness probe** — gates traffic; failure removes Pod from Service endpoints.
- **ReplicaSet** — keeps N Pods matching a selector.
- **resourceVersion** — opaque token for optimistic concurrency (`409` on stale write).
- **restartPolicy** — `Always`/`OnFailure`/`Never`.
- **Rollout** — controlled replacement of Pods to a new template.
- **runc** — low-level OCI runtime doing namespace/cgroup syscalls.
- **Scheduler** — assigns Pods to nodes (filter + score).
- **Selector (immutable)** — controller's set of owned Pods; fixed at creation in `apps/v1`.
- **Server-Side Apply** — field-ownership-tracked apply preventing clobbers.
- **Sidecar** — helper container; natively an init container with `restartPolicy: Always` (1.28+).
- **StatefulSet** — stable identity, ordered lifecycle, per-Pod storage.
- **terminationGracePeriodSeconds** — SIGTERM→SIGKILL window (default 30).
- **Toleration / taint** — taint repels Pods from a node unless they tolerate it.
- **topologySpreadConstraints** — even Pod distribution across zones/nodes.
- **TTL controller** — deletes finished Jobs after `ttlSecondsAfterFinished`.
- **Work queue** — deduplicating, backoff-aware queue driving reconciles.
- **ZooKeeper** — distributed coordination service; canonical StatefulSet workload.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Objects:** Pod (atom) → ReplicaSet (N copies) → Deployment (rollouts/rollback). StatefulSet (stable id + per-Pod PVC + ordered). DaemonSet (per node). Job (to completion) → CronJob (scheduled).

**Defaults to memorize:** `terminationGracePeriodSeconds` 30 · `restartPolicy` Always · Deployment `maxSurge`/`maxUnavailable` 25% · `revisionHistoryLimit` 10 · `progressDeadlineSeconds` 600 · `minReadySeconds` 0 · Job `backoffLimit` 6, `completions`/`parallelism` 1 · CronJob `concurrencyPolicy` Allow, history 3/1 · DaemonSet `maxUnavailable` 1 · probe `periodSeconds` 10, `timeoutSeconds` 1, `failureThreshold` 3 · backoff cap 5–6 min · OOMKill exit 137.

**Controller pattern:** observe→diff→act, idempotent, level-triggered, informer cache + dedup work queue. Self-heals.

**Ownership/GC:** Deployment→RS→Pod; CronJob→Job→Pod. `--cascade=background`(default)/`foreground`(finalizer)/`orphan`.

**Probes:** liveness=restart, readiness=traffic gate, startup=shield slow boot. Never probe a dependency from liveness.

**Decision rules:** stateless+deploy→Deployment · stateful/clustered→StatefulSet · per-node agent→DaemonSet · finite task→Job · scheduled→CronJob. Never bare Pods for services; never Deployment for databases; never `:latest` in prod; always set requests+memory limit; always `MaxRAMPercentage` for the JVM.

**Debug first moves:** `describe pod` (Events!), `logs --previous`, `rollout status`, `get events --sort-by=.lastTimestamp`, `dmesg | grep oom` on node.

### Self-test (no answers — recall actively)

1. Trace, step by step, what happens from `kubectl apply` of a Deployment YAML until traffic reaches a new Pod — name every controller and component involved.
2. You set `maxUnavailable: 0` and `maxSurge: 1` on a 10-replica Deployment whose new image crash-loops. Exactly what state does the cluster end up in, and why doesn't it roll back?
3. Explain why a StatefulSet Pod can get stuck `Terminating` after a node failure, and what you must verify before force-deleting it.
4. Design the full set of Pod fields you'd use to guarantee zero-downtime, drain-safe deploys for a slow-starting JVM service — and justify each.
5. A teammate edited a label on a Pod owned by a ReplicaSet and now there's "an extra Pod." Explain the adoption/orphaning mechanics that produced this.
6. Compare `--cascade=background`, `foreground`, and `orphan`, including the role of finalizers and `blockOwnerDeletion`.
7. Your CronJob occasionally double-runs and sometimes skips. List every field and design choice you'd apply to make it correct, and explain the at-least-once caveat.

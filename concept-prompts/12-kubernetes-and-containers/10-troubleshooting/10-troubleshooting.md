# Kubernetes & Containers: Troubleshooting

> An exhaustive engineering-handbook chapter on diagnosing, triaging, and fixing failures in Kubernetes workloads and the containers that run inside them. Written for a senior backend developer (Java/JVM-centric) who wants to operate, debug, and reason about Kubernetes in production from first principles to deep internals.

---

## 1. Overview & where it fits

**What it is.** "Troubleshooting Kubernetes" is the discipline of taking a symptom — a Pod that won't start, a request that times out, a node that disappears — and walking it back to a root cause using a small, well-defined set of observable signals (object status, events, logs, metrics, exit codes) and a systematic method. It is *not* one tool; it is a way of reading the cluster's own self-reported state and combining it with Linux/container fundamentals.

**The problem it solves.** Kubernetes is a **declarative, control-loop system**: you tell it the *desired state* ("I want 3 replicas of this image with 512Mi of memory"), and a set of background processes called **controllers** continuously try to make *actual state* match. When something is wrong, nothing throws a stack trace at you. Instead, the gap between desired and actual state shows up as a status, a condition, an event, or a restart count. Troubleshooting is the skill of locating that gap and explaining it.

> **Beginner aside — declarative vs imperative.** *Imperative* means "run these steps" (`docker run ...`). *Declarative* means "here is the end state I want; you figure out the steps" (a YAML manifest). Kubernetes is declarative: you `kubectl apply` a desired state, and controllers reconcile reality toward it. This matters for debugging because failures are reported as *state*, not as a command that errored.

**When you reach for it.** Any time the gap between desired and actual is non-zero and not self-healing: Pods stuck `Pending`, `CrashLoopBackOff`, `ImagePullBackOff`, `OOMKilled`, `Evicted`; Services that don't route; DNS that doesn't resolve; nodes going `NotReady`; rollouts that hang; latency spikes.

**One-paragraph mental model.** Picture Kubernetes as a stack of nested loops. The **API server** holds the single source of truth (in **etcd**). **Controllers** watch that truth and create child objects (a Deployment creates a ReplicaSet, which creates Pods). The **scheduler** assigns each Pod to a node. On each node, the **kubelet** turns the Pod spec into running containers via a **container runtime** (containerd/CRI-O), and the **kube-proxy**/CNI plumb the network. *Every* failure lives at exactly one of these layers, and each layer leaves a trail: API objects have a `status`, the scheduler and kubelet emit `events`, containers emit `logs` and `exit codes`, and the OS emits kernel/cgroup signals. Troubleshooting = identify the layer, then read that layer's trail.

> **Beginner aside — etcd.** etcd is a distributed, strongly-consistent key-value store (it uses the **Raft** consensus algorithm to keep replicas in agreement). It is the *only* stateful component of the Kubernetes control plane: the entire cluster state lives there. If etcd is unhealthy, the whole control plane is effectively read-broken.
>
> **Beginner aside — Raft.** Raft is a consensus protocol: a cluster of nodes elects a *leader*; the leader accepts writes, replicates them to followers, and a write is "committed" once a majority (quorum) acknowledges it. This is why etcd clusters are sized 3 or 5 (odd numbers): you need a majority to make progress.

---

## 2. Foundations from first principles

Before you can debug Kubernetes, you must hold an accurate model of *what objects exist* and *who acts on them*. Skipping this is the #1 reason people flail during incidents.

### 2.1 The core objects (the nouns)

- **Container.** A process (or process tree) isolated using two Linux kernel features: **namespaces** (isolate *what a process can see* — its own PID list, network stack, mounts, hostname) and **cgroups** (isolate *how much a process can use* — CPU, memory, IO). A container image is a stack of read-only filesystem layers plus metadata (entrypoint, env, ports).
  > **Beginner aside — namespaces vs cgroups.** Namespaces = *visibility* isolation (a container thinks it's the only thing on the machine). cgroups (control groups) = *resource* limits and accounting (this container may use at most 512Mi RAM and 0.5 CPU). OOMKilled and CPU throttling are cgroup phenomena; "container can't see another container's process" is a namespace phenomenon.

- **Pod.** The smallest schedulable unit. One or more containers that **share a network namespace** (same IP and localhost), share certain volumes, and are always co-located on one node and scheduled/scaled as a unit. A Pod is *mortal* — it is never rescheduled; if it dies, a controller makes a *new* Pod with a *new* name.
  > **Beginner aside — sidecar.** A second container in the same Pod that augments the main one (e.g., a logging agent, a service-mesh proxy like Envoy, or a Java app's metrics exporter). They share localhost, so the main container can reach the sidecar at `127.0.0.1`.

- **ReplicaSet.** A controller that guarantees N identical Pods exist. You rarely create it directly.

- **Deployment.** A controller that manages ReplicaSets to give you declarative *rolling updates* and *rollbacks*. `Deployment → ReplicaSet → Pods`.

- **StatefulSet.** Like a Deployment but for stateful apps: stable network identity (`pod-0`, `pod-1`), stable per-Pod storage, ordered rollout. Used for databases, Kafka, ZooKeeper.
  > **Beginner aside — ZooKeeper.** A distributed coordination service (config, leader election, locks) used by systems like Kafka and HBase. It's relevant here only as an example of something you'd run on a StatefulSet because each node needs a stable identity and disk.

- **DaemonSet.** Runs exactly one Pod per node (log collectors, CNI agents, node exporters).

- **Job / CronJob.** Run-to-completion / scheduled batch work.

- **Service.** A stable virtual IP (ClusterIP) + DNS name that load-balances to a dynamic set of Pods selected by labels. Decouples clients from mortal Pod IPs.

- **Endpoints / EndpointSlice.** The actual list of Pod IP:port behind a Service. *This is gold for debugging routing* — an empty EndpointSlice means "Service selects nothing healthy."

- **Ingress / Gateway.** L7 (HTTP) routing from outside the cluster to Services.

- **ConfigMap / Secret.** Configuration and credentials injected as env vars or mounted files.

- **PersistentVolume (PV) / PersistentVolumeClaim (PVC) / StorageClass.** PV = a piece of storage. PVC = a request for storage. StorageClass = a template for dynamically provisioning PVs. A Pod that mounts a PVC that can't be bound will sit `Pending`.

- **Node.** A worker machine (VM or physical). Runs the kubelet, a container runtime, kube-proxy.

- **Namespace (Kubernetes).** A logical partition of the cluster for names and quotas. *Different from Linux namespaces* — same word, different concept.

### 2.2 The actors (the verbs / control loops)

- **kube-apiserver.** The front door and the only thing that talks to etcd. Every read/write goes through it. RESTful, with an audit log.
- **etcd.** The database (see §1).
- **kube-scheduler.** Watches for Pods with no node assigned (`spec.nodeName == ""`) and binds each to a feasible, optimal node based on resource requests, taints/tolerations, affinity, etc. *Scheduling failures are the scheduler talking.*
- **kube-controller-manager.** Hosts dozens of controllers (Deployment, ReplicaSet, Node, Job, endpoint controllers...). The **node controller** here is what marks a node `NotReady` and triggers eviction.
- **kubelet.** The node agent. Watches the API for Pods assigned to its node, then drives the container runtime to start/stop containers, runs **probes** (liveness/readiness/startup), reports node and Pod status, enforces **eviction** under node pressure. *Most Pod-level runtime failures are the kubelet talking.*
- **Container runtime (containerd / CRI-O).** Pulls images, creates the container (via **runc**), manages its lifecycle. Talks to the kubelet over the **CRI** (Container Runtime Interface) gRPC API.
  > **Beginner aside — runc & OCI.** `runc` is the low-level tool that actually `clone()`s the process with the right namespaces/cgroups. OCI (Open Container Initiative) is the standard for image format and runtime, so images are portable across runtimes.
- **kube-proxy.** Programs the node's packet rules (iptables or IPVS) so the Service ClusterIP load-balances to Pod IPs.
- **CNI plugin (Calico, Cilium, AWS VPC CNI, Flannel...).** Gives each Pod an IP and sets up the routes/overlay so Pods can reach each other across nodes.
  > **Beginner aside — CNI.** Container Network Interface — the plugin standard the kubelet calls to attach a Pod to the network. If the CNI is broken, Pods get stuck in `ContainerCreating` or have no connectivity.
- **CoreDNS.** The in-cluster DNS server. Resolves `my-svc.my-ns.svc.cluster.local` to a ClusterIP. DNS failures usually mean CoreDNS or the Pod's `resolv.conf`/`ndots` config.

### 2.3 The signals (what you actually read)

Every troubleshooting move reduces to reading one of these:

| Signal | Where it lives | What it tells you | Primary command |
|---|---|---|---|
| **Object status** | `.status` of any object | Phase, conditions, IPs, ready counts | `kubectl get`, `kubectl describe` |
| **Conditions** | `.status.conditions[]` | Boolean health facts (`Ready`, `PodScheduled`, `DiskPressure`) | `kubectl describe`, `get -o yaml` |
| **Events** | `Event` objects (TTL ~1h) | Scheduler/kubelet/controller narration of *why* | `kubectl describe`, `kubectl get events` |
| **Container logs** | runtime, stdout/stderr | The app's own output | `kubectl logs` |
| **Exit codes** | `.status.containerStatuses[].lastState.terminated` | How a process died (137=OOM/SIGKILL, etc.) | `kubectl describe`, `get -o jsonpath` |
| **Metrics** | metrics-server / Prometheus | CPU/mem usage vs limits over time | `kubectl top`, PromQL |
| **Node state** | `Node` object + kubelet | Pressure conditions, capacity, allocatable | `kubectl describe node` |

### 2.4 The Pod lifecycle (you must memorize this)

A Pod's `.status.phase` is one of:

- **Pending** — accepted by the API but not all containers running yet. Either *not scheduled* (no suitable node) or *scheduled but still pulling images / creating sandbox / mounting volumes*.
- **Running** — bound to a node, all containers created, at least one running/starting/restarting.
- **Succeeded** — all containers exited 0 and won't restart (Jobs).
- **Failed** — all containers terminated, at least one non-zero, won't restart.
- **Unknown** — the node's kubelet can't be reached (node down / partition).

Per-container `state` is finer-grained: `Waiting` (with a `reason` like `CrashLoopBackOff`, `ImagePullBackOff`, `ContainerCreating`), `Running`, or `Terminated` (with `exitCode`, `reason`, `signal`). **The container `Waiting.reason` and `Terminated.exitCode` are the two most diagnostic fields in all of Kubernetes.**

> **Critical distinction.** `CrashLoopBackOff`, `ImagePullBackOff`, `OOMKilled`, `Evicted` are **not** Pod phases. The first two are container `Waiting.reason`s, `OOMKilled` is a container `Terminated.reason`, and `Evicted` is a Pod-level `status.reason` (the phase becomes `Failed`). Knowing *which layer* the word comes from tells you which actor to interrogate.

---

## 3. How it works internally — the path from `apply` to running (and where it breaks)

This is the heart of the chapter. To troubleshoot, you must know the exact sequence so you can ask "which step failed?" Here is the full control + data flow for a Deployment, with the failure point annotated at each step.

### 3.1 Step-by-step: from `kubectl apply` to a running container

1. **`kubectl apply -f deploy.yaml`** → client sends the manifest to the **API server**. The API server runs **admission**: authentication → authorization (RBAC) → mutating admission webhooks → validation → validating admission webhooks → schema validation → persist to **etcd**.
   - *Breaks here:* RBAC `Forbidden`, a webhook rejecting/timing out (`failed calling webhook`), invalid schema, ResourceQuota exceeded.
   > **Beginner aside — admission webhook.** A pluggable HTTP callback the API server invokes to mutate (e.g., inject a sidecar) or validate objects before they're stored. A *failing or unreachable* webhook can block *all* creates/updates cluster-wide — a classic outage.

2. **Deployment controller** (in kube-controller-manager) sees the new/changed Deployment, creates/updates a **ReplicaSet**.
   - *Breaks here:* rarely; if it does, look at controller-manager logs.

3. **ReplicaSet controller** sees it needs N Pods, creates N **Pod** objects (with `nodeName` empty).
   - *Breaks here:* `ResourceQuota` on pods/objects; PodSecurity admission rejecting the Pod spec.

4. **Scheduler** watches for unscheduled Pods. For each, it runs **filtering** (which nodes are *feasible*?) then **scoring** (which feasible node is *best*?), then **binds** the Pod to the winning node (sets `spec.nodeName`).
   - *Breaks here:* `Pending` with `FailedScheduling` — insufficient CPU/memory, taints not tolerated, node affinity/anti-affinity unsatisfiable, no node matches `nodeSelector`, unbound PVC, topology spread constraints, `PodTopologySpread`.
   > **Beginner aside — taints & tolerations.** A *taint* on a node says "don't schedule here unless you explicitly tolerate me" (e.g., `node-role.kubernetes.io/control-plane:NoSchedule`). A Pod *tolerates* a taint to be allowed. Affinity is the inverse pull ("prefer/require nodes with label X").

5. **kubelet on the chosen node** sees a Pod assigned to it. It now drives the runtime:
   - a. **Create the Pod sandbox** (the "pause" container holding the shared network namespace). The kubelet calls the **CNI** to assign an IP.
     - *Breaks here:* `ContainerCreating` stuck — CNI failure (no IP available, `failed to allocate`), CNI daemon down.
   - b. **Pull images** for each container (respecting `imagePullPolicy`).
     - *Breaks here:* `ErrImagePull` → `ImagePullBackOff` — wrong tag, private registry without `imagePullSecrets`, registry down, rate-limited (Docker Hub), wrong architecture.
   - c. **Mount volumes** (ConfigMaps, Secrets, PVCs). For a PVC, the **attach/detach controller** + CSI driver attach the volume to the node, then the kubelet mounts it.
     - *Breaks here:* `ContainerCreating` with `FailedMount`/`FailedAttachVolume` — PVC unbound, volume already attached elsewhere (RWO), CSI driver error, secret/configmap not found.
     > **Beginner aside — CSI.** Container Storage Interface — the plugin standard for storage drivers (EBS, GCE PD, Ceph...). Replaces old in-tree volume code.
   - d. **Run init containers** in order (each must exit 0 before the next).
     - *Breaks here:* `Init:CrashLoopBackOff`, `Init:Error` — a failing init step (e.g., a migration or a wait-for-dependency that never succeeds).
   - e. **Start app containers.** Run `startupProbe` (if any) until success, then begin `liveness`/`readiness` probes.
     - *Breaks here:* container exits → `CrashLoopBackOff`; bad config; missing env; `OOMKilled` (137); probe failures.

6. **Probes drive ongoing state:**
   - **startupProbe** — gates the others; for slow-starting apps (a JVM warming up). While failing, liveness/readiness are suppressed. If it ultimately fails past `failureThreshold`, the container is killed.
   - **livenessProbe** — if it fails `failureThreshold` times, the kubelet **kills and restarts** the container. A bad liveness probe causes self-inflicted `CrashLoopBackOff`.
   - **readinessProbe** — if it fails, the Pod is removed from Service EndpointSlices (no traffic) but **not** restarted. A bad readiness probe causes "Service has no endpoints / 503s" with healthy-looking Pods.

7. **Endpoint controller / kube-proxy.** Once a Pod is `Ready`, its IP is added to the Service's EndpointSlice; kube-proxy programs iptables/IPVS so the ClusterIP routes to it.
   - *Breaks here:* Service `selector` doesn't match Pod `labels` → empty endpoints; `targetPort` wrong; `containerPort`/named port mismatch; NetworkPolicy blocking.

### 3.2 The restart/backoff state machine (CrashLoopBackOff internals)

When a container exits and its `restartPolicy` allows restart (`Always` for Deployments, `OnFailure`/`Never` for Jobs), the kubelet restarts it with **exponential backoff**:

- Backoff doubles: **10s → 20s → 40s → 80s → 160s → 300s (cap)**. The cap is **5 minutes (300s)**.
- The `Waiting.reason` is `CrashLoopBackOff` *during the wait window*. The actual failure reason is in `lastState.terminated`.
- After the container stays up **(historically ≥10 minutes; in newer kubelets the backoff resets on a sustained healthy run)**, the backoff timer resets.
- `restartCount` in the Pod status increments each restart — a fast-climbing count is the fingerprint of a crash loop.

> **Key mental move:** `CrashLoopBackOff` is a *symptom*, never a *cause*. The cause is always in `lastState.terminated` (exit code/signal) plus the previous container's logs (`kubectl logs --previous`).

### 3.3 Node-pressure eviction internals

The kubelet watches node resources against **eviction thresholds**. Two modes:

- **Soft eviction** — threshold breached for `eviction-soft-grace-period`; kubelet evicts gracefully (respects `terminationGracePeriod`, capped by `eviction-max-pod-grace-period`).
- **Hard eviction** — threshold breached *now*; kubelet kills immediately, no grace.

Default hard thresholds (kubelet defaults, version-dependent — verify on your distro):

| Signal | Default hard threshold | Meaning |
|---|---|---|
| `memory.available` | `< 100Mi` | Node almost out of RAM |
| `nodefs.available` | `< 10%` | Root/kubelet filesystem low |
| `nodefs.inodesFree` | `< 5%` | Out of inodes |
| `imagefs.available` | `< 15%` | Image/container layer FS low |

When evicting for memory, the kubelet ranks Pods by **QoS class** and how far each exceeds its memory *request*:

- **Guaranteed** (requests == limits for all resources) — evicted last.
- **Burstable** (has requests < limits) — evicted by overage above request.
- **BestEffort** (no requests/limits) — evicted first.

> **Beginner aside — QoS classes.** Kubernetes derives a quality-of-service class from how you set requests/limits. Guaranteed Pods are protected; BestEffort Pods are sacrificed first under pressure. *Always set memory requests on important workloads* to keep them out of the BestEffort bucket.

Eviction marks the Pod `Failed` with `reason: Evicted` and a message like `The node was low on resource: memory`. Evicted Pod objects *linger* (for postmortem) until garbage-collected or you delete them.

### 3.4 The OOMKill path (exit 137) internals

There are **two distinct kills people conflate**:

1. **cgroup OOM kill (container exceeded its memory *limit*).** The Linux kernel's cgroup memory controller sees the container's memory cgroup hit `memory.limit_in_bytes`, invokes the OOM killer, and SIGKILLs (signal 9) the offending process. The container `Terminated.reason` is **`OOMKilled`**, exit code **137** (= 128 + 9). The node itself is healthy.
2. **Node-level (system) OOM.** The whole node runs out of RAM (overcommit), and the kernel OOM killer picks victims by `oom_score`. The kubelet may also evict. This shows as eviction + dmesg OOM messages.

> **Exit code arithmetic.** A process killed by signal N reports exit code **128 + N**. So SIGKILL (9) → 137, SIGTERM (15) → 143, SIGSEGV (11) → 139, SIGABRT (6) → 134, SIGINT (2) → 130. Exit **1** = generic app error; **126** = command not executable; **127** = command not found (bad entrypoint/PATH); **0** = clean.

> **JVM-specific gotcha.** A JVM that respects cgroup limits (`-XX:+UseContainerSupport`, default and on since JDK 8u191/10+) sizes its heap from the *container* memory limit (`-XX:MaxRAMPercentage`). But the JVM also uses *off-heap* memory (Metaspace, thread stacks, code cache, direct buffers, GC structures). If `limit ≈ heap`, off-heap pushes the *total* RSS over the cgroup limit → exit 137 even though heap looks fine. The fix is almost never "increase heap"; it's "leave headroom" (e.g., `MaxRAMPercentage=75`) and/or raise the limit.

---

## 4. The complete toolkit

### 4.1 `kubectl` — the primary diagnostic verbs

| Command | Purpose | Key flags / notes |
|---|---|---|
| `kubectl get <kind>` | List objects + summary status | `-o wide` (node, IP), `-o yaml`/`-o json` (full), `-w` (watch), `-A` (all namespaces), `--show-labels`, `-l key=val` (label filter), `--field-selector status.phase=Pending` |
| `kubectl describe <kind> <name>` | Human-readable object + **its Events** | The single most useful command. Shows conditions, container states, mounts, and the recent event timeline for *that object* |
| `kubectl logs <pod>` | Container stdout/stderr | `-c <container>` (multi-container), `--previous`/`-p` (the *crashed* container's logs — essential for CrashLoop), `-f` (follow), `--since=10m`, `--tail=200`, `--timestamps`, `--all-containers`, `-l <selector>` (logs across Pods) |
| `kubectl exec -it <pod> -- <cmd>` | Run a command inside a *running* container | `-c <container>`; needs a shell in the image |
| `kubectl debug` | **Ephemeral debug container** / node debug / copy-with-changes | See §4.2 — works even on distroless / crashed Pods |
| `kubectl get events` | Cluster events, time-ordered | `--sort-by=.lastTimestamp` (or `.metadata.creationTimestamp`), `-A`, `--field-selector type=Warning`, `--for pod/<name>` (newer) |
| `kubectl top pod` / `top node` | Live CPU/mem (needs metrics-server) | `--containers`, `-A`; compares usage to scheduled |
| `kubectl rollout status deploy/<n>` | Watch a rollout to completion/hang | `kubectl rollout history`, `rollout undo` |
| `kubectl get --raw /healthz` | Hit API server / component health endpoints | `/livez`, `/readyz?verbose`, `/healthz` |
| `kubectl cp` | Copy files in/out of a container | For pulling heap dumps, thread dumps |
| `kubectl port-forward` | Tunnel a local port to a Pod/Service | Bypass Service/Ingress to test a Pod directly |
| `kubectl auth can-i` | Check RBAC | `--as system:serviceaccount:ns:sa`, `--list` |
| `kubectl explain <kind>.<field>` | Schema/docs for a field | `--recursive` |
| `kubectl api-resources` | List kinds + short names + namespaced-ness | |

**High-value jsonpath / custom-column extractions:**

```bash
# Exit code and reason of the last termination for each container
kubectl get pod <pod> -o jsonpath='{range .status.containerStatuses[*]}{.name}{"  exit="}{.lastState.terminated.exitCode}{"  reason="}{.lastState.terminated.reason}{"\n"}{end}'

# All Pods not Running/Succeeded, cluster-wide
kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded

# Restart counts, descending hot-spots
kubectl get pods -A --sort-by='.status.containerStatuses[0].restartCount' -o wide

# Why is a node unschedulable? (its taints)
kubectl get nodes -o custom-columns=NAME:.metadata.name,TAINTS:.spec.taints

# Requests vs allocatable pressure on a node
kubectl describe node <node> | sed -n '/Allocated resources/,/Events/p'
```

### 4.2 `kubectl debug` — the modern superpower (Kubernetes 1.23+ GA)

Three modes; learn all three:

```bash
# (1) EPHEMERAL DEBUG CONTAINER: attach a debug container to a RUNNING pod,
#     sharing its process namespace, WITHOUT restarting it. Great for distroless.
kubectl debug -it <pod> --image=nicolaka/netshoot --target=<container> -- bash
#   --target makes the debug container share the PID namespace of <container>,
#   so you can `ps`, read /proc/<pid>/, strace, etc. of the real app.

# (2) COPY-WITH-CHANGES: clone a CrashLooping pod with a fixed command so you can
#     poke around instead of having it crash. Original keeps running/crashing.
kubectl debug <pod> -it --copy-to=<pod>-debug --container=<c> \
  --image=busybox --share-processes -- sh
#   You can also override command: append `-- sleep infinity` to stop the crash.

# (3) NODE DEBUG: get a root shell on a NODE via a privileged pod that mounts
#     the host filesystem at /host. For NotReady nodes, disk pressure, kubelet logs.
kubectl debug node/<node-name> -it --image=ubuntu
#   Inside: `chroot /host` then run `journalctl -u kubelet`, `crictl ps`, `df -h`, `dmesg`.
```

> **Beginner aside — distroless / scratch images.** Minimal images with *no shell and no package manager* (Google "distroless", or `FROM scratch`). `kubectl exec ... -- sh` fails because there's no `sh`. `kubectl debug` solves this by attaching a *separate* container (with tools) into the same namespaces. `nicolaka/netshoot` is the de-facto debug image (dig, curl, tcpdump, iproute2, etc.).

### 4.3 Node / runtime-level tools (when you're on the box)

| Tool | Purpose |
|---|---|
| `crictl ps -a`, `crictl logs`, `crictl inspect` | CRI-level view of containers when kubectl/kubelet is broken. `crictl` talks straight to containerd/CRI-O. |
| `journalctl -u kubelet -f` | The kubelet's own logs — eviction decisions, probe failures, mount errors, CNI errors. |
| `dmesg -T | grep -i -E 'oom|killed'` | Kernel OOM-killer messages (system OOM). |
| `systemctl status containerd kubelet` | Are the agents even running? |
| `df -h` / `df -i` | Disk space / **inodes** (inode exhaustion is a sneaky `DiskPressure` cause). |
| `cat /sys/fs/cgroup/.../memory.max` (cgroup v2) or `memory.limit_in_bytes` (v1) | The actual cgroup limit the kernel enforces. |
| `ip a`, `ip route`, `iptables-save`, `ipvsadm -Ln` | Network plumbing for routing/Service issues. |
| `nsenter -t <pid> -n <cmd>` | Run a command in a Pod's network namespace from the host. |

### 4.4 In-cluster network/DNS diagnostics

```bash
# Spin up a throwaway diagnostics pod
kubectl run netshoot --rm -it --image=nicolaka/netshoot -- bash
# Then, inside:
nslookup my-svc.my-ns.svc.cluster.local      # DNS resolution
dig +search +short my-svc                     # honor search domains / ndots
curl -v http://my-svc.my-ns.svc:8080/healthz  # connectivity to Service
cat /etc/resolv.conf                          # search domains, ndots, nameserver
```

### 4.5 Cluster-wide health

| Command | Purpose |
|---|---|
| `kubectl get componentstatuses` (deprecated) / `kubectl get --raw /readyz?verbose` | Control-plane health |
| `kubectl get nodes` | Node readiness at a glance |
| `kubectl get apiservices | grep -v True` | Broken aggregated/extension API servers (e.g., metrics-server down breaks `kubectl top` and HPA) |
| `kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp` | The cluster's recent pain, ranked |

---

## 5. Code examples by use case

These are *complete, adaptable* manifests and command sequences spanning different real scenarios. The JVM examples reflect the reader's stack.

### 5.1 A correctly-instrumented JVM Deployment (the baseline that prevents most incidents)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders-api
  labels: { app: orders-api }
spec:
  replicas: 3
  selector:
    matchLabels: { app: orders-api }   # MUST match template labels or no Pods are managed
  template:
    metadata:
      labels: { app: orders-api }       # MUST match the Service selector to receive traffic
    spec:
      containers:
        - name: app
          image: registry.example.com/orders-api:1.8.3   # pin a tag, never :latest
          ports:
            - { name: http, containerPort: 8080 }
          env:
            # Leave ~25% headroom for off-heap so RSS stays under the cgroup limit (avoids exit 137)
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dump"
          resources:
            requests: { cpu: "250m", memory: "512Mi" }   # scheduler uses these
            limits:   { cpu: "1",    memory: "768Mi" }    # kernel enforces memory; CPU is throttled
          # JVMs start slow: gate liveness/readiness behind a startup probe so we don't kill a warming app
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: 5
            failureThreshold: 30          # 30 * 5s = 150s budget to start
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: 10
            failureThreshold: 3           # restart only after 30s of sustained failure
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: http }
            periodSeconds: 5
            failureThreshold: 3
          volumeMounts:
            - { name: dump, mountPath: /dump }   # somewhere to write a heap dump on OOM
      volumes:
        - name: dump
          emptyDir: { sizeLimit: 1Gi }
```

> **Why this prevents incidents:** the startup probe stops self-inflicted CrashLoops on slow JVM boot; `MaxRAMPercentage=75` leaves off-heap headroom to avoid exit 137; `ExitOnOutOfMemoryError` turns a Java `OutOfMemoryError` into a clean container exit (so the kubelet restarts it) instead of a zombie limping process; the heap-dump volume gives you postmortem evidence.

### 5.2 Triaging a `CrashLoopBackOff` (command sequence)

```bash
# 1. Confirm the symptom and restart velocity
kubectl get pod orders-api-xyz -o wide
# READY 0/1   STATUS CrashLoopBackOff   RESTARTS 7 (40s ago)

# 2. Read the *previous* container's logs — THIS is where the cause is
kubectl logs orders-api-xyz -p --tail=100
# e.g. "Caused by: org.postgresql.util.PSQLException: Connection refused"

# 3. Read events + last termination state
kubectl describe pod orders-api-xyz | sed -n '/Last State/,/Events/p'
#   Last State: Terminated  Reason: Error  Exit Code: 1   <- app error, not OOM

# 4. If exit 137 instead -> it's OOM; check usage vs limit
kubectl get pod orders-api-xyz -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}{"\n"}'
# OOMKilled
kubectl top pod orders-api-xyz --containers

# 5. If the app crashes too fast to inspect, clone it with the crash disabled
kubectl debug orders-api-xyz -it --copy-to=orders-debug \
  --container=app --image=registry.example.com/orders-api:1.8.3 -- sh -c 'sleep infinity'
# Now exec in and run the entrypoint manually to see the full error, check env/config/DNS.
```

### 5.3 Diagnosing `ImagePullBackOff` with a private registry

```bash
kubectl describe pod web-7d... | grep -A3 Events
#   Failed to pull image ".../web:1.2": ... 401 Unauthorized
#   -> missing/invalid imagePullSecret

# Create a registry secret and wire it to the SA or the Pod
kubectl create secret docker-registry regcred \
  --docker-server=registry.example.com \
  --docker-username=ci --docker-password="$TOKEN"

# Attach to the pod's service account (applies to all pods using that SA)
kubectl patch serviceaccount default \
  -p '{"imagePullSecrets":[{"name":"regcred"}]}'
```

Other `ImagePullBackOff` root causes to check in the event text: `manifest unknown`/`not found` (wrong tag), `no match for platform` (arm64 image on amd64 node), `toomanyrequests` (Docker Hub rate limit — use a pull-through cache or auth), `dial tcp ... i/o timeout` (node can't reach the registry — egress/DNS/firewall).

### 5.4 Diagnosing a `Pending` Pod (unschedulable)

```bash
kubectl describe pod batch-job-abc | sed -n '/Events/,$p'
#  Warning  FailedScheduling  0/12 nodes are available:
#    3 Insufficient cpu, 6 node(s) had untolerated taint {gpu: true}, 3 Insufficient memory.

# Cross-check the cluster's free capacity vs the Pod's requests
kubectl get pod batch-job-abc -o jsonpath='{.spec.containers[*].resources.requests}{"\n"}'
kubectl describe nodes | grep -A5 "Allocated resources"
```

Decode the scheduler message by clause:
- `Insufficient cpu/memory` → requests too high or cluster too small → lower requests, add nodes, or enable Cluster Autoscaler.
- `had untolerated taint` → add the matching `tolerations` (and/or `nodeSelector`/affinity for the right pool).
- `node(s) didn't match Pod's node affinity/selector` → label mismatch.
- `pod has unbound immediate PersistentVolumeClaims` → check the PVC: `kubectl describe pvc <n>` (no matching PV / StorageClass / zone mismatch).
- `node(s) didn't match pod topology spread constraints` → relax `whenUnsatisfiable` to `ScheduleAnyway` or add capacity in the missing zone.

### 5.5 "My Service returns no data / 503" — endpoints debugging

```bash
# 1. Does the Service have ANY endpoints? Empty = selector mismatch or no Ready pods.
kubectl get endpointslices -l kubernetes.io/service-name=orders-api
kubectl get endpoints orders-api          # legacy view

# 2. Compare Service selector to Pod labels (the classic typo bug)
kubectl get svc orders-api -o jsonpath='{.spec.selector}{"\n"}'   # {"app":"orders"}
kubectl get pods --show-labels | grep orders                      # app=orders-api  <- MISMATCH

# 3. If endpoints exist but traffic fails, test the Pod directly (bypass Service)
kubectl port-forward pod/orders-api-xyz 18080:8080
curl -v localhost:18080/healthz

# 4. Check targetPort vs containerPort, and readiness (NotReady pods are excluded)
kubectl get svc orders-api -o jsonpath='{.spec.ports[*].targetPort}{"\n"}'
```

> **The #1 cause of "no endpoints" is a readiness probe failing** (Pods Running but not Ready) — they're silently pulled from the EndpointSlice. The #2 cause is a label/selector typo.

### 5.6 DNS failure investigation

```bash
kubectl run dnstest --rm -it --image=nicolaka/netshoot -- bash
# inside:
cat /etc/resolv.conf
#   nameserver 10.96.0.10
#   search my-ns.svc.cluster.local svc.cluster.local cluster.local
#   options ndots:5
nslookup kubernetes.default        # short name -> exercises search domains
nslookup orders-api.my-ns.svc.cluster.local

# Is CoreDNS healthy?
kubectl -n kube-system get pods -l k8s-app=kube-dns
kubectl -n kube-system logs -l k8s-app=kube-dns --tail=50
```

> **Beginner aside — `ndots:5`.** `resolv.conf` says: if a name has fewer than 5 dots, try it with each *search* suffix first before trying it as-is. So `api.external.com` (2 dots) triggers 4 failing lookups (`api.external.com.my-ns.svc.cluster.local`, etc.) before the real one — a notorious latency/load source. Fixes: use a trailing dot (FQDN `api.external.com.`), set `dnsConfig.options ndots:2`, or use NodeLocal DNSCache.

### 5.7 Node `NotReady` triage (from the node, via `kubectl debug node`)

```bash
kubectl get nodes                      # node-7 NotReady
kubectl describe node node-7 | sed -n '/Conditions/,/Addresses/p'
#   MemoryPressure True / DiskPressure True / Ready False (kubelet stopped posting status)

kubectl debug node/node-7 -it --image=busybox
# inside the privileged pod:
chroot /host
systemctl status kubelet               # is it running?
journalctl -u kubelet --no-pager | tail -50
df -h /var/lib/kubelet /var/lib/containerd   # disk pressure?
df -i /                                # inode pressure?
dmesg -T | grep -i oom | tail          # system OOM?
crictl ps -a | head                    # runtime alive?
```

### 5.8 Capturing a JVM thread dump / heap dump from a live Pod

```bash
# Thread dump (deadlock / high CPU). jcmd ships in the JDK image.
kubectl exec orders-api-xyz -- jcmd 1 Thread.print > threads.txt

# Heap dump on a memory leak, then copy it out for Eclipse MAT / VisualVM
kubectl exec orders-api-xyz -- jcmd 1 GC.heap_dump /dump/heap.hprof
kubectl cp orders-api-xyz:/dump/heap.hprof ./heap.hprof

# If the image is distroless (no jcmd), attach a JDK debug container sharing PID ns
kubectl debug -it orders-api-xyz --image=eclipse-temurin:21-jdk \
  --target=app -- jcmd 1 Thread.print
```

> Note: `jcmd 1` targets PID 1 because the JVM is usually the container's init process. With `--target`/`--share-processes` the debug container sees the same PID namespace, so PID 1 is the app.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & resource correctness
- **Always set memory requests** on production workloads → keeps them out of BestEffort QoS (first to be evicted). Setting `requests == limits` for memory makes a Pod Guaranteed.
- **Be deliberate about CPU limits.** CPU is *compressible*: exceeding a CPU limit causes **throttling** (the kernel CFS quota stalls the process), not a kill. CPU throttling shows up as latency, not crashes. Many teams set CPU *requests* but **no CPU limit** to avoid throttling tail latency; verify against your governance rules.
  > **Beginner aside — CFS throttling.** The Linux Completely Fair Scheduler enforces CPU limits over 100ms periods. If a container uses its quota early, it's frozen for the rest of the period — visible as `container_cpu_cfs_throttled_periods_total`.
- **Memory is incompressible:** exceed the limit and you're SIGKILLed (137). No throttling, no warning. Headroom is mandatory for JVMs (off-heap).

### 6.2 Probes (the most-misconfigured feature)
- **Liveness ≠ readiness.** Liveness restarts; readiness gates traffic. Never make a liveness probe check downstream dependencies (DB, cache) — a DB blip will then restart your whole fleet (cascading CrashLoop). Liveness should check *only "is this process wedged?"*. Readiness can check dependencies.
- **Use a startupProbe for slow starters** (JVMs, apps loading large models) so liveness doesn't kill them mid-warmup.
- **Tune `failureThreshold`/`periodSeconds`** so a transient GC pause doesn't trip liveness. A 3×10s liveness gives ~30s grace.

### 6.3 Observability (you can't debug what you don't record)
- **Logs to stdout/stderr**, structured (JSON), with correlation IDs. Ship them off-node (Loki/ELK/Cloud logging) — `kubectl logs` only has what's on the node *now*, lost on Pod deletion.
- **Events have a TTL (~1 hour default, `--event-ttl`)** — export important events to your logging/monitoring system or they vanish. Use an event exporter.
- **metrics-server** for `kubectl top`/HPA; **Prometheus + kube-state-metrics + cAdvisor** for history. The metrics you actually need during incidents: `container_memory_working_set_bytes` vs limit, `container_cpu_cfs_throttled_seconds_total`, `kube_pod_container_status_restarts_total`, `kube_pod_status_phase`.
- **Keep heap-dump-on-OOM volumes** for JVMs.

### 6.4 Security hardening that affects debugging
- Distroless images are great for security but require `kubectl debug` (no shell) — make sure your team has the RBAC and a standard debug image approved.
- `readOnlyRootFilesystem: true`, dropped capabilities, non-root user, and PodSecurity "restricted" can *block* a Pod from starting → `CreateContainerConfigError`/`CreateContainerError`. Read those event reasons literally.
- Ephemeral debug containers and `kubectl debug node` are **privileged**; gate them with RBAC and audit them.

### 6.5 Cost
- Crash loops and pull backoffs burn money quietly (registry egress, autoscaler thrash, wasted reserved capacity). Alert on `restartCount` velocity and `Pending` duration.
- Over-requesting resources strands node capacity (low bin-packing efficiency). Right-size with VPA recommendations.

### 6.6 Testing & production hardening
- Test failure paths: chaos-kill Pods, drain nodes (`kubectl drain`), simulate image-pull failures, verify probes actually fail when the app is wedged.
- Set `PodDisruptionBudget`s so voluntary disruptions (drains, upgrades) don't take you below a safe replica count.
- Set `terminationGracePeriodSeconds` and handle SIGTERM (drain in-flight requests) — abrupt 137/143 on rollout is a graceful-shutdown bug, not a Kubernetes bug.

### 6.7 Common anti-patterns
- `image: app:latest` → non-reproducible pulls, cache confusion, `imagePullPolicy: Always` surprises. **Pin tags/digests.**
- Liveness probe that calls the database (cascading restarts).
- No memory request (BestEffort) on a critical service → first evicted under pressure.
- Setting memory limit == heap size for a JVM → guaranteed exit 137.
- Debugging by deleting Pods ("turn it off and on") — destroys the evidence (`--previous` logs, terminated state) before you've read it.
- Ignoring `kubectl describe` and jumping straight to logs — you miss the scheduler/kubelet/mount events that explain *why it never started*.

---

## 7. Advanced topics & deep internals

### 7.1 cgroup v1 vs v2 and what changes for memory debugging
- **cgroup v2** (default on modern distros — RHEL 9, recent Ubuntu, k8s 1.25+ widely) unifies the hierarchy and changes file names: limit is `memory.max`, current is `memory.current`, and there's `memory.events` (with `oom_kill` counter) and PSI (`memory.pressure`). **PSI (Pressure Stall Information)** gives `some`/`full` stall percentages — a far better early-warning signal than "available bytes." On v1 you read `memory.limit_in_bytes`/`memory.usage_in_bytes` and there's no PSI.
- The kubelet's **MemoryQoS** feature (using cgroup v2 `memory.min`/`memory.high`) can throttle/reclaim *before* a hard OOM — version- and feature-gate-dependent.

### 7.2 The `working_set` vs RSS subtlety
- The kubelet/OOM decisions use **working set** = `memory.usage` − inactive file cache. Page cache that can be reclaimed isn't counted against you for *eviction*, but *cgroup hard-limit* OOM still triggers on the cgroup's accounted usage. This is why `kubectl top` (working set) can look fine moments before a 137 — anonymous memory (heap, native) spiked.

### 7.3 Scheduler internals worth knowing
- The scheduler is a **framework** of plugins at extension points: `PreFilter`, `Filter`, `PostFilter` (this is where **preemption** lives), `Score`, `Reserve`, `Permit`, `Bind`. When a Pod can't fit, `PostFilter`/preemption may **evict lower-priority Pods** (`PriorityClass`/`preemptionPolicy`) to make room — you'll see victims terminated with a preemption message.
  > **Beginner aside — PriorityClass & preemption.** A higher-priority Pending Pod can cause the scheduler to evict ("preempt") lower-priority running Pods to schedule itself. Set `PriorityClass` deliberately; misconfigured priorities cause surprise evictions.
- **`nominatedNodeName`** in a Pod's status during preemption tells you the scheduler has earmarked a node and is waiting for victims to vacate.

### 7.4 Probe edge cases
- **`exec` probes fork a process every period** — a heavy exec probe (e.g., a JVM CLI) can itself cause CPU/memory pressure or zombie accumulation. Prefer `httpGet`/`grpc` probes.
- **gRPC probes** are built-in since 1.24 (`grpc:` field) — no more grpc-health-probe binary needed.
- A liveness probe with `initialDelaySeconds` too short is the textbook cause of a "healthy app that keeps restarting." Prefer startupProbe over big `initialDelaySeconds`.

### 7.5 Eviction vs OOM vs preemption — three different "kills"
| Mechanism | Who | Trigger | QoS-aware? | Pod result |
|---|---|---|---|---|
| **cgroup OOM kill** | Kernel | Container exceeds *its* memory limit | No (per-container) | `OOMKilled`, exit 137, restarts |
| **kubelet eviction** | kubelet | *Node* resource pressure | Yes (BestEffort→Burstable→Guaranteed) | `Evicted`, phase Failed |
| **scheduler preemption** | scheduler | Higher-priority Pod needs room | Yes (PriorityClass) | Graceful delete (preempted) |
| **node-level system OOM** | Kernel | Whole node out of RAM | oom_score (kubelet biases system daemons) | process killed; dmesg OOM |

### 7.6 ContainerCreating that never resolves
Long `ContainerCreating` (no CrashLoop, no pull error) almost always = **CNI** or **volume**:
- CNI: `kubectl describe pod` shows `failed to setup network ... no IP addresses available` (IPAM exhaustion — common on AWS VPC CNI when ENIs/IPs run out) or CNI daemonset not ready on that node.
- Volume: `FailedAttachVolume` (RWO PVC still attached to a dead node — needs force-detach), `FailedMount` (CSI driver issue, wrong zone), `MountVolume.SetUp failed for secret/configmap "x" not found`.

### 7.7 API-server / control-plane self-troubleshooting
- `kubectl get --raw='/readyz?verbose'` lists each readiness check (etcd, informers, webhooks). A failing **admission webhook** is the most common control-plane self-inflicted outage: `failed calling webhook ... context deadline exceeded` blocks creates. Mitigate with `failurePolicy: Ignore` for non-critical webhooks and tight `timeoutSeconds`.
- etcd troubles surface as slow/failed writes and `etcdserver: request timed out` / leader-election churn. `etcdctl endpoint status --cluster -w table` shows leader, raft index, DB size. etcd has a **default DB size quota of 2GiB** (`--quota-backend-bytes`); exceeding it puts the cluster into a maintenance-required *alarm* (read-only) state — defrag and compact to recover.

### 7.8 Image & runtime deep cuts
- `imagePullPolicy` defaults: **`Always`** if tag is `:latest` (or omitted), **`IfNotPresent`** otherwise. This trips people: a mutable tag with `IfNotPresent` serves a stale cached image.
- `CreateContainerConfigError` (config wrong: missing ConfigMap/Secret key referenced in env) vs `CreateContainerError` (runtime couldn't create: e.g., bad `command`, missing mount path, seccomp) vs `RunContainerError` — read the reason verbatim; they point at different fixes.
- `ErrImageNeverPull` = `imagePullPolicy: Never` but image isn't pre-loaded on the node.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which signal to read first (triage decision rules)

| Symptom (STATUS column) | Read first | Most likely root cause |
|---|---|---|
| `Pending` | `describe pod` → Events (FailedScheduling) | Resources / taints / affinity / unbound PVC |
| `ContainerCreating` (stuck) | `describe pod` → Events | CNI (no IP) or volume mount |
| `ImagePullBackOff`/`ErrImagePull` | `describe pod` → Events | Wrong tag / auth / registry / arch |
| `CrashLoopBackOff` | `logs -p` + last terminated exitCode | App error (1) or OOM (137) or bad probe |
| `OOMKilled` (exit 137) | `top`, limit vs working set, heap dump | Limit too low / off-heap / leak |
| `Running` but `0/1 Ready` | readiness probe + endpoints | Readiness failing → no traffic |
| `Evicted` | `describe pod` reason + node conditions | Node memory/disk pressure |
| `Error`/`Completed` loop (Job) | `logs`, exitCode | Job command failing |
| Node `NotReady` | `describe node` conditions + kubelet logs | kubelet/runtime down, pressure, network |
| Service 503 / timeouts | endpointslices, port-forward to Pod | No endpoints / readiness / targetPort / NetworkPolicy |
| DNS errors | resolv.conf + CoreDNS logs | ndots, CoreDNS down, NetworkPolicy on :53 |

### 8.2 Request/limit strategy

| Strategy | Use when | Avoid when |
|---|---|---|
| **Guaranteed** (req==limit, mem & cpu) | Latency-critical, must not be evicted/throttled-surprised | You want bursting headroom |
| **Burstable** (req < limit) | Typical services; bursty but bounded | You can't tolerate OOM at the limit |
| **No CPU limit** (req only) | Tail-latency-sensitive (avoid CFS throttling) | Hard multi-tenant CPU isolation required |
| **BestEffort** (nothing) | Throwaway/dev only | Anything production (evicted first) |

### 8.3 exec vs ephemeral debug vs copy-to

| Approach | Use when | Limitation |
|---|---|---|
| `kubectl exec` | Image has a shell; container is *running* | Fails on distroless / crashed Pods |
| `kubectl debug` (ephemeral) | Distroless or need extra tools, Pod is running | Adds a container; needs feature on (GA 1.25) |
| `kubectl debug --copy-to` | Pod is CrashLooping (can't stay up) | Operates on a *copy*, not the live Pod |
| `kubectl debug node/` | Node-level (NotReady, disk, kubelet) | Privileged; broad blast radius |

### 8.4 When to escalate from Pod to node to control plane
- **Single Pod failing** → app/config (logs, exit code).
- **All Pods of one workload failing identically** → image/config/dependency (a bad release, a down DB).
- **Many unrelated Pods on the *same* node failing** → node (pressure, kubelet, runtime, CNI). `kubectl get pods -A -o wide | grep <node>`.
- **Cluster-wide creates failing / kubectl slow** → control plane (apiserver, webhook, etcd).

---

## 9. Failure modes & debugging — real scenarios

### Scenario A — "Deploy went out, now half the fleet is CrashLoopBackOff"
**Symptoms:** new ReplicaSet Pods `CrashLoopBackOff`, old ones fine; rollout stuck.
**Diagnose:** `kubectl logs -p` on a new Pod → `Caused by: java.lang.NoClassDefFoundError` (a dependency dropped in the new build) — exit 1.
**Fix:** `kubectl rollout undo deployment/orders-api`. Rolling update with `maxUnavailable`/readiness gating *should* have stopped the rollout if readiness was correct — root cause is also that readiness passed despite a broken app. **Lesson:** readiness must reflect real serving health.

### Scenario B — "Random 137s under load, heap looks fine"
**Symptoms:** `OOMKilled` exit 137 intermittently; `kubectl top` shows working set just under limit; JVM heap graphs flat.
**Diagnose:** limit 768Mi, `MaxRAMPercentage=75` → ~576Mi heap, but Metaspace + thread stacks (hundreds of threads × 1MB) + direct buffers (Netty/gRPC) push RSS past 768Mi. `dmesg`/`memory.events oom_kill` confirms cgroup OOM.
**Fix:** cap thread pools, set `-XX:MaxMetaspaceSize`, bound `-XX:MaxDirectMemorySize`, lower `MaxRAMPercentage` or raise the limit. **Lesson:** heap ≠ container memory.

### Scenario C — "Service intermittently returns 503; Pods all show Running"
**Symptoms:** `Running` but flapping `0/1 Ready`; EndpointSlice churns.
**Diagnose:** readiness probe `periodSeconds:2, timeout:1s` against an endpoint that does a DB round-trip; under GC pauses it times out → Pod yanked from endpoints → 503 → recovers → re-added. Classic flapping.
**Fix:** make readiness cheap and local; widen timeout/threshold; don't couple readiness to slow dependencies. **Lesson:** probe tuning is a correctness concern, not a nicety.

### Scenario D — "New nodes added but Pods still Pending"
**Symptoms:** `FailedScheduling: had untolerated taint {nodegroup: spot}`.
**Diagnose:** the new node group is tainted (spot pool), Pods lack the toleration; autoscaler scaled the wrong pool.
**Fix:** add `tolerations` + nodeSelector for the spot pool, or scale the on-demand pool. **Lesson:** the scheduler's message is literal — read every clause.

### Scenario E — "Node went NotReady, Pods stuck Unknown/Terminating"
**Symptoms:** node `NotReady`, Pods on it `Unknown`; after `pod-eviction-timeout` the node controller marks Pods for deletion but they're `Terminating` forever (kubelet unreachable can't confirm).
**Diagnose:** `kubectl debug node/` → kubelet OOM'd due to `DiskPressure` (image FS full, `imagefs.available<15%`). Inodes also low (`df -i`).
**Fix:** free disk (`crictl rmi --prune`), garbage-collect images, raise disk; for RWO volumes stuck attached, force-detach. **Lesson:** `DiskPressure`/inode exhaustion silently takes down kubelets.

### Scenario F — "Sporadic DNS timeouts, p99 latency spikes"
**Symptoms:** occasional `UnknownHostException`; tcpdump shows bursts of failing DNS queries.
**Diagnose:** external hostname with `ndots:5` causing 4 wasted search-domain lookups each; plus a known conntrack race on UDP DNS under load.
**Fix:** use FQDN with trailing dot or `ndots:2`; deploy **NodeLocal DNSCache** (per-node DNS cache over TCP to CoreDNS) to kill the race and the round-trips. **Lesson:** DNS is a top-3 source of mysterious cluster latency.

### Scenario G — "kubectl apply suddenly fails cluster-wide"
**Symptoms:** every create errors `failed calling webhook "x": context deadline exceeded`.
**Diagnose:** a validating webhook's backing Service has 0 endpoints (its Pods crashed) and `failurePolicy: Fail` → it blocks all matching writes.
**Fix:** delete/patch the `ValidatingWebhookConfiguration` (or fix its backend); set `failurePolicy: Ignore` and tight timeouts for non-critical webhooks. **Lesson:** admission webhooks are a single point of failure for the API server.

---

## 10. Interview drill

**Q1. A Pod is in `CrashLoopBackOff`. Walk me through your diagnosis.**
*Model:* It's a symptom of repeated container exit, not a cause. I'd `kubectl describe pod` to read `lastState.terminated` (exit code/reason) and the events; `kubectl logs -p` for the crashed container's output. Exit 1 → app error (read the stack trace); exit 137 + reason `OOMKilled` → memory limit; bad liveness probe → self-inflicted restarts. If it crashes too fast, `kubectl debug --copy-to` with the command overridden to `sleep` and inspect.
- *Follow-up: Why `-p`?* Because `kubectl logs` shows the *current* container; in a crash loop the informative output is in the *previous*, already-dead instance.
- *Follow-up: Backoff timing?* Exponential 10s→…→capped at 300s; restartCount increments each loop.
- *Follow-up: How could a probe cause this?* A liveness probe that fails (too-short delay, or checking a slow dependency) makes the kubelet kill+restart repeatedly.

**Q2. Exit code 137 — what is it and what are the two ways to get it?**
*Model:* 137 = 128 + 9 (SIGKILL). Either a **cgroup OOM kill** (container exceeded its memory limit → kernel SIGKILLs it; reason `OOMKilled`) or any external SIGKILL (e.g., failed-probe kill, node OOM). Distinguish via `lastState.terminated.reason` and `dmesg`.
- *Follow-up: JVM heap looks fine but still 137?* Off-heap (Metaspace, stacks, direct buffers) pushes RSS over the limit; leave headroom.
- *Follow-up: 143?* 128+15 = SIGTERM — graceful shutdown, usually a normal stop/rollout.

**Q3. A Pod is `Pending`. What do you check, in order?**
*Model:* `describe pod` → Events. If `FailedScheduling`, decode each clause: insufficient cpu/mem (requests vs capacity → scale/lower), untolerated taint (add toleration), node affinity/selector mismatch (labels), unbound PVC (`describe pvc`), topology spread. If *no* scheduling event but Pending, it scheduled and is stuck pulling/mounting → different layer.
- *Follow-up: Insufficient memory but `top` shows free RAM?* The scheduler uses **requests**, not live usage — sum of requests exceeds allocatable even if actual usage is low.
- *Follow-up: How does autoscaling help?* Cluster Autoscaler reacts to unschedulable Pods by adding nodes (if a matching node group exists and isn't tainted away).

**Q4. Service returns 503 but Pods are `Running`. Why?**
*Model:* Running ≠ Ready. Failing readiness probes remove Pods from EndpointSlices → no backends → 503. Check `kubectl get endpointslices`; if empty, also check selector/label match and targetPort. Port-forward to a Pod to confirm it serves directly.
- *Follow-up: Liveness vs readiness here?* Readiness gates traffic (no restart); liveness restarts. A 503 with stable Pods is readiness.
- *Follow-up: Probe flapping cause?* Expensive/dependency-coupled readiness timing out under GC/load.

**Q5. Explain the difference between OOMKilled, Evicted, and preempted.** *(senior-signal)*
*Model:* OOMKilled = per-container cgroup limit breach → kernel SIGKILL, restarts in place. Evicted = *node* pressure → kubelet sacrifices Pods by QoS (BestEffort first), phase Failed. Preempted = scheduler removes lower-PriorityClass Pods to fit a higher-priority Pending Pod. Different actor, trigger, and remedy each.
- *Follow-up: How do you protect a critical Pod from eviction?* Give it memory requests (≥ usage) and Guaranteed QoS / high PriorityClass.
- *Follow-up: Why is CPU different?* CPU is compressible — over-limit → throttling (latency), not kill.

**Q6. How would you debug a distroless container with no shell that's misbehaving?** 
*Model:* `kubectl debug -it <pod> --image=nicolaka/netshoot --target=<container>` attaches an ephemeral debug container sharing the target's PID/network namespaces, so I can `ps`, read `/proc`, run `tcpdump`/`curl`, attach a JDK to dump threads — all without modifying or restarting the original Pod.
- *Follow-up: It's crashing, not running?* `--copy-to` a clone with command overridden to keep it alive.
- *Follow-up: Node-level?* `kubectl debug node/<n>` → privileged Pod with host FS at /host, `chroot /host`, then `journalctl -u kubelet`, `crictl`.

**Q7. Walk the full path from `kubectl apply` to a running container and name a failure at each stage.** *(senior-signal)*
*Model:* (Recite §3.1.) apiserver/admission (RBAC/webhook), Deployment→RS→Pod (quota), scheduler (Pending/FailedScheduling), sandbox+CNI (ContainerCreating/no IP), image pull (ImagePullBackOff), volume mount (FailedMount), init containers (Init crash), app start + probes (CrashLoop/OOM/readiness), endpoints+kube-proxy (no endpoints). Demonstrating you can localize a failure to one stage is the signal.

**Q8. Your readiness probe checks the database. Critique this.** *(senior-signal)*
*Model:* Bad for liveness (a DB blip restarts the whole fleet — cascading CrashLoop), debatable for readiness. Readiness *may* reflect dependency health so a Pod with a dead DB stops taking traffic, but if *all* Pods share one DB you can take the whole Service to zero endpoints on a transient DB issue, turning a degraded state into a full outage. Prefer: liveness = local "am I wedged"; readiness = lightweight, possibly with a circuit-breaker rather than a hard dependency gate.
- *Follow-up: Better pattern?* Serve degraded with a fast-failing dependency and surface health via separate signals; use startupProbe for warmup.

**Q9. Node is `NotReady`. How do you investigate and what are common causes?**
*Model:* `describe node` for conditions (MemoryPressure/DiskPressure/PIDPressure/Ready) and the kubelet's last heartbeat; `kubectl debug node/` then `systemctl status kubelet`, `journalctl -u kubelet`, `df -h`/`df -i`, `dmesg`, `crictl ps`. Common: disk/inode pressure killing kubelet, runtime down, CNI down, network partition, certificate expiry, kubelet OOM.
- *Follow-up: Pods stuck Terminating on it?* kubelet unreachable can't confirm deletion; after pod-eviction-timeout the controller force-handles, RWO volumes may need force-detach.

**Q10. `ImagePullBackOff` — enumerate causes and how you'd confirm each.**
*Model:* `describe pod` event text tells you: `401/403` (auth → imagePullSecret), `manifest unknown`/`not found` (wrong tag/repo), `no match for platform` (arch mismatch), `toomanyrequests` (registry rate limit), `i/o timeout`/`no such host` (node egress/DNS/firewall to registry), `ErrImageNeverPull` (`policy: Never`, not preloaded).
- *Follow-up: imagePullPolicy defaults?* `Always` for `:latest`/untagged, else `IfNotPresent`.

**Q11. Events are missing when you go to investigate an hour later. Why, and how do you fix the gap?**
*Model:* Events are first-class objects with a TTL (~1h, `--event-ttl` on apiserver) and are GC'd; they're not durable. Fix: run an event exporter to ship them to logging/monitoring, and rely on metrics (restarts, phase) + shipped logs for historical postmortems.

**Q12. The whole cluster's `kubectl apply` is failing with webhook timeouts. What's happening and how do you recover?** *(senior-signal)*
*Model:* An admission webhook with `failurePolicy: Fail` whose backing Service is unhealthy (0 endpoints) blocks all matching writes. Recover by deleting/patching the `Validating/MutatingWebhookConfiguration` or fixing its backend; long-term set tight `timeoutSeconds`, scope `objectSelector`/`namespaceSelector` narrowly, and use `failurePolicy: Ignore` for non-critical webhooks. It's a classic self-inflicted control-plane outage.

---

## 11. Glossary

- **Admission webhook** — HTTP callback the API server invokes to mutate/validate objects pre-persist.
- **Affinity / anti-affinity** — scheduling rules pulling Pods toward/away from nodes or other Pods by labels.
- **Allocatable** — node capacity minus reserved (system/kube) resources; what the scheduler can hand out.
- **Backoff (exponential)** — increasing wait between restart attempts (10s→…→300s cap).
- **BestEffort / Burstable / Guaranteed** — QoS classes derived from requests/limits; eviction order.
- **cAdvisor** — per-node container metrics collector embedded in the kubelet.
- **cgroup** — Linux control group; enforces/accounts CPU, memory, IO per container.
- **CFS throttling** — CPU limit enforcement that stalls a container that exceeds its quota in a period.
- **CNI** — Container Network Interface; plugin that gives Pods IPs and connectivity.
- **ClusterIP** — a Service's stable virtual IP, load-balanced to Pod IPs.
- **CoreDNS** — in-cluster DNS server resolving Service/Pod names.
- **CrashLoopBackOff** — container `Waiting.reason`: it keeps exiting and the kubelet is backing off restarts.
- **CRI** — Container Runtime Interface; gRPC API between kubelet and runtime.
- **crictl** — CLI to talk to the CRI runtime directly (debugging when kubelet is broken).
- **CSI** — Container Storage Interface; storage driver plugin standard.
- **Conditions** — boolean health facts on objects (`Ready`, `PodScheduled`, `DiskPressure`).
- **Controller** — control loop reconciling actual state toward desired state.
- **DaemonSet** — one Pod per node.
- **Declarative** — you specify desired end state; the system reconciles to it.
- **DiskPressure / MemoryPressure / PIDPressure** — node conditions signaling resource shortage.
- **Distroless** — minimal image with no shell/package manager.
- **EndpointSlice / Endpoints** — the list of Pod IP:port backing a Service.
- **etcd** — the strongly-consistent key-value store holding all cluster state (Raft-based).
- **Eviction** — kubelet terminating Pods under node pressure, by QoS order.
- **Ephemeral container** — temporary container injected into a running Pod for debugging.
- **ErrImagePull / ImagePullBackOff** — image couldn't be pulled; backing off retries.
- **Exit code** — process termination code; 128+signal for signal kills (137=SIGKILL, 143=SIGTERM).
- **FQDN** — fully-qualified domain name (trailing dot bypasses search-domain expansion).
- **HPA** — Horizontal Pod Autoscaler; scales replica count on metrics.
- **Init container** — container that must complete before app containers start.
- **IPAM** — IP Address Management (CNI subsystem); exhaustion blocks Pod IP assignment.
- **kube-apiserver** — front door to the cluster; only component talking to etcd.
- **kube-controller-manager** — hosts built-in controllers (node, deployment, job…).
- **kube-proxy** — programs iptables/IPVS to route ClusterIPs to Pods.
- **kube-scheduler** — assigns Pods to nodes (filter + score + bind).
- **kubelet** — node agent driving the runtime and reporting status; runs probes; enforces eviction.
- **Liveness probe** — restarts a container if it fails.
- **MaxRAMPercentage** — JVM flag: heap as % of container memory limit.
- **metrics-server** — lightweight cluster-wide CPU/mem metrics for `top`/HPA.
- **Namespace (Kubernetes)** — logical partition for names/quotas (≠ Linux namespace).
- **Namespace (Linux)** — kernel isolation of visibility (PID, net, mount…).
- **ndots** — resolv.conf option controlling search-domain expansion threshold.
- **NetworkPolicy** — firewall rules for Pod-to-Pod/ingress/egress traffic.
- **NodeLocal DNSCache** — per-node DNS cache reducing CoreDNS load and conntrack races.
- **NotReady (node)** — kubelet not posting healthy status; node unusable for scheduling.
- **OCI** — Open Container Initiative; image/runtime standards.
- **OOMKilled** — container killed by the cgroup OOM killer (exceeded memory limit); exit 137.
- **PDB (PodDisruptionBudget)** — minimum availability constraint during voluntary disruptions.
- **Pending** — Pod accepted but not all containers running (unscheduled or starting).
- **Phase** — top-level Pod status: Pending/Running/Succeeded/Failed/Unknown.
- **Pod** — smallest schedulable unit; co-located containers sharing network/IPC.
- **Preemption** — scheduler evicting lower-priority Pods to fit a higher-priority one.
- **PriorityClass** — Pod scheduling priority; drives preemption order.
- **PSI** — Pressure Stall Information; cgroup v2 stall metrics (early pressure warning).
- **PV / PVC / StorageClass** — storage object / request / dynamic provisioning template.
- **QoS class** — see BestEffort/Burstable/Guaranteed.
- **Raft** — leader-based consensus protocol behind etcd.
- **Readiness probe** — gates whether a Pod receives Service traffic (no restart).
- **ReplicaSet** — controller maintaining N identical Pods.
- **Requests / Limits** — scheduler reservation / kernel-enforced ceiling for CPU/memory.
- **restartPolicy** — Always/OnFailure/Never; governs container restarts.
- **RSS** — Resident Set Size; physical memory a process holds.
- **runc** — low-level OCI runtime that creates the container process.
- **Sandbox (pause container)** — holds a Pod's shared namespaces/IP.
- **Sidecar** — auxiliary container in the same Pod.
- **StartupProbe** — gates liveness/readiness for slow-starting apps.
- **StatefulSet** — controller for stateful apps with stable identity/storage.
- **Taint / Toleration** — node repels Pods unless they tolerate the taint.
- **Topology spread constraints** — even Pod distribution across zones/nodes.
- **Working set** — memory usage minus reclaimable file cache; basis for eviction decisions.
- **ZooKeeper** — distributed coordination service (example StatefulSet workload).

---

## 12. Cheat-sheet & self-test

### One-screen recap

**First moves:** `kubectl get pod -o wide` → `kubectl describe pod` (read Events + last terminated) → `kubectl logs -p` → only then act. **Never delete the Pod before reading `--previous`.**

**Status → meaning:**
- `Pending` = scheduler (resources/taints/affinity/PVC) or stuck mount/pull.
- `ContainerCreating` (stuck) = CNI (no IP) or volume.
- `ImagePullBackOff` = tag/auth/arch/registry.
- `CrashLoopBackOff` = read exit code: **1**=app, **137**=OOM/SIGKILL, **143**=SIGTERM, **127**=cmd not found, **139**=SIGSEGV, **126**=not executable.
- `Running 0/1 Ready` = readiness failing → no endpoints → 503.
- `Evicted` = node pressure (memory/disk/inodes).
- Node `NotReady` = kubelet/runtime/pressure/network.

**Exit code formula:** signal N → 128+N. Default backoff cap **300s**. Default hard eviction: `memory.available<100Mi`, `nodefs<10%`, `imagefs<15%`, `inodesFree<5%` (verify per distro). etcd default DB quota **2GiB**. `ndots:5` default in Pods. QoS eviction order: **BestEffort → Burstable → Guaranteed**.

**Debug tools:** `kubectl debug -it pod --image=nicolaka/netshoot --target=c` (running, distroless), `--copy-to` (crashing), `node/<n>` then `chroot /host` (node). On the node: `journalctl -u kubelet`, `crictl ps -a`, `df -h`/`df -i`, `dmesg | grep -i oom`.

**Decision rules:** single Pod = app/config; whole workload = image/release/dependency; many Pods one node = node; cluster-wide creates fail = control plane (webhook/etcd/apiserver). Always set **memory requests** on prod. Liveness = local "wedged?"; readiness = traffic gate; startupProbe = warmup. Leave **off-heap headroom** for JVMs (`MaxRAMPercentage≈75`).

**JVM evidence:** `jcmd 1 Thread.print` (deadlock/CPU), `jcmd 1 GC.heap_dump /dump/heap.hprof` + `kubectl cp` (leak), `-XX:+HeapDumpOnOutOfMemoryError`.

### Self-test (no answers — recall practice)
1. A Pod shows `Running` with `0/1 READY` and clients get 503s. Name the most likely cause and the exact two commands you'd run to confirm it.
2. You see exit code 137. Describe the two mechanistically different ways a container can get it and how you'd tell them apart on the node.
3. The scheduler logs `0/9 nodes are available: 3 Insufficient cpu, 6 node(s) had untolerated taint`. What two independent fixes address this, and which signal (live usage or requests) is the scheduler actually using?
4. Your container image is distroless and the Pod is in `CrashLoopBackOff`. Give the single `kubectl debug` invocation that lets you inspect it without it crashing, and explain each flag.
5. A node is `NotReady` and its Pods are stuck `Terminating`. Outline your investigation from `kubectl` down to the kernel, naming at least four commands.
6. Explain why coupling a *liveness* probe to your database can convert a transient DB outage into a full-fleet CrashLoopBackOff, and what you'd do instead.
7. `kubectl apply` is failing cluster-wide with `context deadline exceeded` calling a webhook. What is happening, and what is the fastest safe recovery?

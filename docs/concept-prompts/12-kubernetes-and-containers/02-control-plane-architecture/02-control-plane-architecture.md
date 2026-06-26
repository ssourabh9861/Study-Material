# Control Plane Architecture (Kubernetes)

> An exhaustive engineering-handbook chapter for senior backend developers (Java/JVM background) who want to fully master how Kubernetes works under the hood — enough to design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### What it is

Kubernetes (often abbreviated **K8s** — "K", then eight letters, then "s") is a **container orchestrator**: software that decides *where* and *how* your containerized workloads run across a fleet of machines, keeps them running, restarts them when they die, scales them up and down, and exposes them on the network. A **container** is a lightweight, isolated package of an application plus its dependencies that shares the host operating system's kernel (think of a Docker image running as a process group with its own filesystem and network namespace, not a full virtual machine).

The **control plane** is the "brain" of a Kubernetes cluster — the set of processes that make global decisions about the cluster (scheduling, responding to events, enforcing policy) and store the cluster's authoritative state. Everything that runs your actual application containers happens on **worker nodes** (machines, physical or virtual), but the *decisions* about what should run where are made by the control plane.

A useful framing: the control plane is a **distributed, eventually-consistent, declarative state machine**. You tell it *what you want* (the **desired state**), it observes *what actually exists* (the **actual / observed state**), and it continuously works to make the second match the first. That continuous matching is called **reconciliation**, and the loops that perform it are **control loops** (or **controllers**).

### The problem it solves

Before orchestrators, running a service in production meant manually (or with brittle scripts/Ansible/Chef) deciding which servers run which processes, wiring up load balancers, handling a dead machine by paging a human, and rolling out new versions by hand. This does not scale to hundreds of services across thousands of machines. The control plane solves:

- **Placement / bin-packing:** Which node has enough CPU/memory for this workload? (The **scheduler**.)
- **Self-healing:** A container crashed or a node died — recreate the work elsewhere. (Controllers + kubelet.)
- **Declarative config & drift correction:** You declared "5 replicas"; someone or something killed one; the system restores 5 without human intervention.
- **A single source of truth:** All cluster state lives in one consistent store (**etcd**) behind one API (**kube-apiserver**), so tooling, humans, and automation all agree on reality.
- **Extensibility:** You can teach the control plane new concepts (**Custom Resource Definitions** + **operators**) without forking Kubernetes.

### When you reach for it

You reach for Kubernetes (and thus must understand the control plane) when you have **multiple services**, **multiple machines**, and a need for **automated lifecycle management** (scaling, rolling updates, self-healing) with a **uniform declarative interface**. You do *not* reach for it for a single small app on one box — that's overkill. As a backend engineer you most often interact with the control plane indirectly (via `kubectl`, CI/CD, Helm) but you must understand it to debug "why is my pod stuck in Pending?", to write operators, to set resource requests correctly, and to reason about availability during a control-plane outage.

### One-paragraph mental model

Think of the control plane as a **bank with one ledger and many tireless clerks**. The **ledger** is etcd — the single, consistent, append-and-overwrite record of "what the cluster wants and what it knows." The **only teller window** to that ledger is the kube-apiserver: every read and write goes through it; it validates, authorizes, and records transactions; nothing else touches the ledger directly. The **clerks** are controllers — each watches the ledger for a specific kind of entry (Deployments, ReplicaSets, Nodes, Jobs…), notices when reality diverges from the ledger, and takes action to close the gap, then records what it did back into the ledger. The **scheduler** is a specialized clerk whose only job is to assign unscheduled work to specific machines. On every machine, an agent (**kubelet**) reads the ledger (via the teller) to learn "what should run here," makes it so by talking to the container runtime, and reports back "here's what's actually running." This loop never stops; it's why Kubernetes is self-healing.

---

## 2. Foundations from first principles

We build up every concept from zero. If you already know a term, skim; nothing is assumed.

### 2.1 Declarative vs imperative

- **Imperative** means you issue commands describing *steps*: "create a container, then attach this volume, then start it." You own the sequencing and the error handling. Example: `docker run ...`.
- **Declarative** means you describe the *end state* you want and let the system figure out the steps: "I want a Deployment named `web` with 5 replicas of image `web:1.4`." You hand Kubernetes a document (a **manifest**, usually YAML) and it converges reality to match. `kubectl apply -f deploy.yaml` is declarative.

Why declarative wins at scale: it is **idempotent** (applying the same desired state twice changes nothing the second time), it supports **drift correction** (the system continuously re-checks and re-converges), and it makes config **diffable and version-controllable** (the foundation of **GitOps** — driving cluster state from a Git repository).

> **Idempotent**: an operation you can repeat any number of times with the same result as doing it once. Declarative `apply` is idempotent; imperative `create` is not (the second `create` errors because the object already exists).

### 2.2 Desired state, actual state, and the reconciliation loop

- **Desired state** (a.k.a. *spec*): what you *want*. In a Kubernetes object this lives under the `spec:` field.
- **Actual / observed state** (a.k.a. *status*): what *is*. This lives under the `status:` field and is written by controllers, not by you.
- **Reconciliation**: the act of comparing spec to status and taking action to reduce the difference.

A **control loop** (control-theory term, borrowed from how a thermostat works) is the simple, eternal cycle:

```
loop forever:
    observe   -> read desired state and actual state
    diff      -> compute the difference (the "error" in control-theory terms)
    act       -> take steps to reduce the difference
    (optionally) write observed state back
```

A **thermostat** is the canonical analogy: desired = 21°C (you set it), actual = current room temperature (it senses it), action = turn the heater on/off. It never "finishes" — it keeps the room near 21°C against constant disturbances (an open window, the sun setting). Kubernetes controllers work identically against disturbances like node failures, manual `kubectl delete`, and crashing processes.

This **level-triggered** design (react to *current state*, not to *the event that changed it*) is fundamental and is why Kubernetes is robust: even if a controller misses an event (it crashed, the network dropped a notification), the next time it observes state it will still converge, because it acts on the *level* (the current snapshot) rather than the *edge* (the transition). Contrast **edge-triggered** systems that act only on the change event — if they miss the event, they're permanently wrong.

> **Level-triggered vs edge-triggered**: borrowed from electronics. *Edge-triggered* fires on a transition (0→1). *Level-triggered* fires on a state (the line is currently high). Kubernetes is level-triggered, which makes it self-correcting and tolerant of lost messages.

### 2.3 Objects, kinds, and the resource model

Everything in Kubernetes is a **resource** (a.k.a. **object** / **API object**): a persisted entity describing intent or status. Each has:

- **apiVersion** — which API group and version this object belongs to (e.g. `apps/v1`, `v1`, `batch/v1`).
- **kind** — the type (e.g. `Pod`, `Deployment`, `Service`, `Node`, `ConfigMap`).
- **metadata** — name, namespace, labels, annotations, UID, resourceVersion, owner references, finalizers.
- **spec** — the desired state (written by users/controllers).
- **status** — the observed state (written by controllers/components).

> **Namespace**: a virtual partition of the cluster used to scope names and apply policy/quota. `default`, `kube-system` (control-plane add-ons), and `kube-public` exist by default. Two objects can share a name if they're in different namespaces. Some objects are *cluster-scoped* (e.g. `Node`, `Namespace`, `PersistentVolume`, `ClusterRole`) and have no namespace.

> **Label**: an arbitrary `key=value` tag on an object, used for *selection* (e.g. a Service selects Pods with `app=web`). **Annotation**: also `key=value` but for *non-identifying* metadata not used for selection (e.g. last-applied config, build info, tooling hints).

The core building blocks, from lowest to highest level:

- **Pod**: the smallest deployable unit — one or more containers that share a network namespace (same IP and port space) and can share storage volumes. You rarely create Pods directly; controllers create them. *Why a Pod and not just a container?* Some processes are tightly coupled (an app + a log-shipping **sidecar**); a Pod co-schedules and co-locates them.
- **ReplicaSet**: ensures *N* identical Pods exist. If one dies, it makes another. Rarely managed directly.
- **Deployment**: manages ReplicaSets to provide *declarative rolling updates and rollbacks* of stateless apps. The thing you actually deploy.
- **StatefulSet**: like a Deployment but for stateful apps that need stable identities and stable storage (databases). Pods are named `name-0`, `name-1`, … and keep their identity and volumes across restarts.
- **DaemonSet**: ensures one Pod per node (e.g. a log collector, a CNI agent, kube-proxy itself).
- **Job / CronJob**: run-to-completion batch work / scheduled batch work.
- **Service**: a stable virtual IP and DNS name that load-balances across a set of Pods (selected by labels). Pods are ephemeral and change IP; Services give a fixed front door.
- **ConfigMap / Secret**: configuration and sensitive data injected into Pods.
- **PersistentVolume / PersistentVolumeClaim**: storage abstraction.

### 2.4 The two halves: control plane vs data plane

- **Control plane** components (the brain): `kube-apiserver`, `etcd`, `kube-scheduler`, `kube-controller-manager`, `cloud-controller-manager`. These run on **control-plane nodes** (historically called "master" nodes — that term is deprecated).
- **Node (worker) components** (the muscle): `kubelet`, `kube-proxy`, and a **container runtime**. These run on every node, including control-plane nodes.

> **Data plane** vs **control plane** (general distributed-systems terms): the *control plane* makes decisions and manages configuration; the *data plane* carries the actual workload traffic/processing. In Kubernetes the running app Pods and the traffic between them are the data plane.

### 2.5 etcd and Raft (explained from zero)

**etcd** is a **distributed, strongly-consistent key-value store**. It's where *all* Kubernetes cluster state is persisted. "Key-value store" means it maps string keys (e.g. `/registry/pods/default/web-abc`) to byte values (the serialized object). "Strongly consistent" means every read returns the most recent committed write — there's no stale-read window for linearizable reads.

How does a *distributed* store stay consistent across multiple machines that can crash or get partitioned from each other? Via a **consensus algorithm**. etcd uses **Raft**.

> **Consensus**: the problem of getting a group of unreliable machines to agree on a single sequence of values (a replicated log) even when some machines fail or messages are lost/delayed. Solving it lets you build a fault-tolerant store that behaves like a single reliable machine.

**Raft** in brief (you should be able to sketch this on a whiteboard):

1. Nodes elect a single **leader** for a **term** (a logical time period). Only the leader accepts writes.
2. A write (a **log entry**) goes to the leader, which **replicates** it to **followers**.
3. Once a **majority (quorum)** of nodes have persisted the entry, the leader **commits** it and applies it to its state machine; followers apply it too. Majority = ⌊N/2⌋ + 1.
4. If the leader dies, followers notice (a **heartbeat** timeout) and hold a new election; a node needs votes from a majority to win, and Raft guarantees the new leader has all committed entries.

> **Quorum / majority**: more than half the members. With 3 nodes, quorum = 2 (tolerates 1 failure). With 5 nodes, quorum = 3 (tolerates 2 failures). This is why etcd clusters are sized at **odd** numbers (3 or 5): an even number gives you no extra fault tolerance but a larger quorum to maintain. A 4-node cluster still only tolerates 1 failure (quorum 3) — same as 3 nodes but more expensive and slower.

> **Split-brain**: when a partition lets two halves of a cluster each think they're in charge, producing divergent state. Raft prevents this by requiring a majority to elect a leader and to commit — a minority partition can elect no leader and accept no writes, so it cannot diverge.

Practical etcd facts you must know:

- Default storage quota is **2 GiB** (`--quota-backend-bytes`, default 2147483648). Exceeding it puts etcd into a read-only **NOSPACE alarm** until you compact/defrag — a classic production outage.
- etcd keeps a **multi-version** history (MVCC). **Compaction** discards old revisions; **defragmentation** reclaims the freed disk space (compaction alone doesn't shrink the file).
- etcd is **latency-sensitive** to disk fsync and network. It wants fast SSDs (low **fsync** latency) and low-latency networking between members. Slow disks cause leader elections and apiserver timeouts.

> **MVCC (Multi-Version Concurrency Control)**: keeping multiple versions of data keyed by a monotonically increasing revision number, so readers can read a consistent snapshot without blocking writers. etcd's `revision` underpins Kubernetes' `resourceVersion` and the **watch** mechanism (§2.6).

> **fsync**: a syscall that forces buffered file writes to durable storage. A **syscall** is a request from a user-space program into the OS kernel to do something privileged (I/O, etc.). etcd fsyncs every committed Raft entry, so disk fsync latency directly bounds write throughput.

### 2.6 Watches, resourceVersion, and informers (the nervous system)

Controllers must react to changes without hammering the API with polling. Kubernetes provides a **watch**: a long-lived streaming connection (over HTTP/1.1 chunked transfer or HTTP/2) where the apiserver pushes events (`ADDED`, `MODIFIED`, `DELETED`, `BOOKMARK`) as objects change.

> **resourceVersion**: an opaque string (backed by etcd's revision) attached to every object and to list/watch responses. It lets a client say "give me everything that changed since version X." It is **not** a timestamp and not globally orderable across resource types — treat it as an opaque cursor.

To consume watches efficiently, client libraries use an **informer**:

- A **Reflector** does a `LIST` to get the full current state plus a starting `resourceVersion`, then opens a `WATCH` from that version to stay current.
- Events flow into a **DeltaFIFO** queue and update a thread-safe in-memory cache called the **store / indexer / local cache**.
- The informer invokes registered **event handlers** (`OnAdd`, `OnUpdate`, `OnDelete`).
- A **workqueue** (rate-limited, deduplicating) holds keys to process; worker goroutines pop keys and run the **reconcile** function, which reads from the local cache (cheap) instead of the API (expensive).

> **Goroutine**: a lightweight thread managed by the Go runtime. Kubernetes is written in Go; controllers run many goroutines. (Mentally, a goroutine ≈ a very cheap green thread; thousands are normal.)

This **list-then-watch** pattern with a local cache is *the* mechanism that lets thousands of controllers stay current cheaply. We'll trace it in detail in §3 and §7.

### 2.7 Admission, authentication, authorization (the gate)

Every write to the apiserver passes a pipeline (detailed in §3.3):

- **Authentication (authn)**: *who are you?* (client certs, bearer tokens, OIDC, service-account tokens).
- **Authorization (authz)**: *are you allowed to do this verb on this resource?* (almost always **RBAC** — Role-Based Access Control).
- **Admission control**: *should this specific request be allowed/modified?* Mutating admission can change the object (e.g. inject a sidecar); validating admission can reject it (e.g. enforce policy).

> **RBAC (Role-Based Access Control)**: permissions are grouped into **Roles** (namespaced) / **ClusterRoles** (cluster-wide) listing allowed verbs (`get`, `list`, `watch`, `create`, `update`, `patch`, `delete`) on resources, and **RoleBindings** / **ClusterRoleBindings** attach those roles to users, groups, or service accounts.

### 2.8 CRDs and operators (extension)

A **Custom Resource Definition (CRD)** teaches the apiserver a brand-new `kind` (e.g. `kind: PostgresCluster`). Once registered, that kind behaves like a native object: stored in etcd, served by the API, watchable, RBAC-controlled. An **operator** is a custom controller that watches your custom resource and reconciles it — encoding the operational knowledge of running some software (e.g. "to scale a Postgres cluster, do X, Y, Z"). This is how the control plane is extended without modifying Kubernetes core.

---

## 3. How it works internally (the heart of the doc)

This section is the deep, step-by-step machinery. We go component by component, then trace a full `kubectl apply` end to end, then drill into admission and watches.

### 3.1 kube-apiserver — the front door and the only client of etcd

The **kube-apiserver** is a stateless (no local persistent state) HTTP/JSON + Protobuf server that exposes the Kubernetes REST API. Critical properties:

- **It is the only component that talks to etcd.** Scheduler, controllers, kubelet — none of them touch etcd directly. This single-writer-of-truth design centralizes validation, versioning, authz, and conversion. If you ever read "X talks to etcd," it's wrong unless X is the apiserver (or `etcdctl` for ops).
- **It is horizontally scalable and stateless** — you run *N* replicas behind a load balancer; any replica can serve any request because all state is in etcd. (etcd itself is the consistency bottleneck, not the apiservers.)
- **It does API versioning and conversion.** Internally it has a hub-and-spoke conversion model: every external version (`v1`, `v1beta1`) converts to/from a single **internal version**, so adding a new API version doesn't require N×N converters. Objects are stored in etcd in a single **storage version** per resource.
- **It hosts the aggregation layer and CRD machinery** (so extension APIs and custom resources are served through the same front door).

REST verbs map to operations: `GET` (get/list/watch), `POST` (create), `PUT` (replace/update), `PATCH` (partial update — strategic merge, JSON merge, or JSON patch), `DELETE`. List/watch can stream.

The request pipeline inside the apiserver (handler chain), in order:

1. **Panic recovery, request timeout, max-in-flight throttling** (and **API Priority and Fairness**, APF, which fairly queues requests by priority level).
2. **Authentication** — try configured authenticators until one succeeds; produces a user + groups.
3. **Authorization** — RBAC (and/or Node, Webhook, ABAC) decides allow/deny.
4. **Mutating admission** — webhooks/plugins may modify the object.
5. **Schema validation** — OpenAPI/structural schema, required fields, etc.
6. **Validating admission** — webhooks/plugins may reject.
7. **Persist to etcd** — serialize to the storage version and write.
8. **Return** the stored object (with assigned `resourceVersion`, `uid`, defaults filled).

> **API Priority and Fairness (APF)**: a mechanism (GA since v1.20+) that classifies incoming requests into priority levels and uses fair queuing so that, e.g., a flood of low-priority list requests can't starve critical leader-election or node-heartbeat traffic. It replaced the older crude `--max-requests-inflight` / `--max-mutating-requests-inflight` global limits (those still exist as backstops).

### 3.2 etcd — the store (operational view)

The apiserver stores objects under a key prefix, by default `/registry/...`, e.g. `/registry/deployments/<namespace>/<name>`. Each Kubernetes resource maps to an etcd key; the value is the serialized object (Protobuf for core types — compact and fast). The apiserver:

- Uses etcd **transactions** (compare-and-swap on the key's mod revision) to implement **optimistic concurrency**: an update only succeeds if the object hasn't changed since you read it (matching `resourceVersion`). If it changed, you get a **409 Conflict** and must re-read and retry. This is how thousands of controllers safely write the same store without locks.
- Uses etcd **watches** to power Kubernetes watches: the apiserver maintains a **watch cache** (an in-memory cache of recent state per resource) so that many client watches don't each open an etcd watch — instead the apiserver watches etcd once per resource and fans out to clients.

> **Optimistic concurrency control (OCC)**: instead of locking, you read a version, do your work, and at write time assert "the version is still what I read." If not, you retry. Great for high-read, low-conflict workloads — exactly Kubernetes' profile.

### 3.3 Admission control & webhooks (the policy gate, in depth)

Admission controllers run *after* authn/authz but *before* persistence. There are two kinds:

- **Built-in (compiled-in) admission plugins**, enabled via the apiserver flag `--enable-admission-plugins`. Important ones:
  - `NamespaceLifecycle` — blocks creating objects in terminating namespaces.
  - `LimitRanger` — applies default/limit resource constraints.
  - `ServiceAccount` — injects the default service account + token.
  - `ResourceQuota` — enforces per-namespace quotas (runs at the very end, validating).
  - `DefaultStorageClass`, `PodSecurity` (replaces the old PodSecurityPolicy), `MutatingAdmissionWebhook`, `ValidatingAdmissionWebhook`, etc.
- **Dynamic admission webhooks** — external HTTPS endpoints you register so the apiserver calls *your* code on matching requests:
  - **MutatingWebhookConfiguration** — runs first; your webhook returns a **JSONPatch** to modify the object (e.g. service-mesh sidecar injection like Istio/Linkerd).
  - **ValidatingWebhookConfiguration** — runs after mutation+schema validation; your webhook returns allow/deny (e.g. policy engines like OPA Gatekeeper, Kyverno).

Order within admission: **all mutating webhooks → re-validate against schema → all validating webhooks**. Mutating webhooks can run in multiple passes for reinvocation if `reinvocationPolicy: IfNeeded`.

Key webhook config knobs and their stakes:

- `failurePolicy: Fail | Ignore` — if the webhook is unreachable/errors, do we **reject** the request (`Fail`, safe but can take down the cluster if the webhook is down) or **let it through** (`Ignore`, available but unsafe)? A misconfigured `Fail` webhook for a broad resource (e.g. all Pods) is a famous way to brick a cluster.
- `timeoutSeconds` — default 10s, max 30s. Slow webhooks add latency to *every matching write*.
- `namespaceSelector` / `objectSelector` — scope which requests trigger the webhook (always exclude `kube-system` and the webhook's own namespace to avoid deadlocks).
- `sideEffects` — declare whether your webhook mutates external state (must be `None` or `NoneOnDryRun` to support `--dry-run`).
- `matchPolicy: Exact | Equivalent` — whether to match only the exact API version or all equivalent versions.

There is also **ValidatingAdmissionPolicy** (CEL-based, in-process, GA in v1.30) — write validation rules in **CEL** (Common Expression Language, a fast, sandboxed expression language) right in the API, no external webhook server needed. This avoids the availability/latency risk of webhooks for many validation use cases.

### 3.4 kube-scheduler — placing Pods on nodes

The scheduler's *only* job: watch for Pods with `spec.nodeName == ""` (unscheduled) and assign each one a node by setting `spec.nodeName` (via a **binding** subresource). It does **not** start containers — the kubelet on the chosen node does. Its loop:

1. **Watch** for unscheduled Pods (they land in a scheduling queue: active queue, backoff queue, unschedulable queue).
2. For one Pod, run the **scheduling cycle** (single-threaded, one Pod at a time) through a **framework** of plugin **extension points**:
   - **PreFilter** → **Filter** (a.k.a. **predicates**): eliminate nodes that *can't* run the Pod — insufficient CPU/memory, taints not tolerated, node selector/affinity mismatch, no matching volume zone, port conflicts. Output: list of *feasible* nodes.
   - **PostFilter**: runs only if no node is feasible — may attempt **preemption** (evict lower-priority Pods to make room).
   - **PreScore** → **Score** (a.k.a. **priorities**): rank feasible nodes 0–100 by goodness — spread across nodes/zones, least-requested or most-requested resources, affinity preferences, image locality (node already has the image). Weighted sum picks the winner.
   - **Reserve** → **Permit**: tentatively reserve resources; Permit can delay/approve (used by gang/coscheduling).
3. **Bind cycle** (can run asynchronously): **PreBind** → **Bind** (write `nodeName`) → **PostBind**. Includes **volume binding** for dynamic provisioning.

> **Taint / Toleration**: a **taint** on a node (`key=value:Effect`, effects `NoSchedule`, `PreferNoSchedule`, `NoExecute`) *repels* Pods that don't *tolerate* it. Used to reserve nodes (e.g. GPU nodes) or to mark unhealthy nodes (the node controller taints `node.kubernetes.io/not-ready:NoExecute` so Pods get evicted).

> **Affinity / Anti-affinity**: rules to attract Pods to nodes/Pods (`nodeAffinity`, `podAffinity`) or repel them (`podAntiAffinity`) — e.g. "spread my replicas across zones" or "co-locate with the cache." Each can be `required...` (hard) or `preferred...` (soft).

> **Preemption**: when a high-`priorityClass` Pod can't be scheduled, the scheduler may evict lower-priority Pods to free room, then schedule the important one. The victims go back to Pending.

The scheduler optimizes for speed at large scale by only scoring a *percentage* of feasible nodes (`percentageOfNodesToScore`, default adaptive — ~50% at 100 nodes, lower for larger clusters) rather than all of them.

### 3.5 kube-controller-manager — the bundle of clerks

A single binary that runs *many* built-in controllers as goroutines, each its own reconcile loop. Notable controllers:

- **Deployment controller** — manages ReplicaSets to roll out updates.
- **ReplicaSet controller** — keeps the desired number of Pods.
- **Node controller** — monitors node health (heartbeats/leases), taints/evicts on failure.
- **Job/CronJob controllers** — run-to-completion and scheduled jobs.
- **EndpointSlice/Endpoints controllers** — keep Service→Pod IP mappings current.
- **ServiceAccount & Token controllers** — create default SAs and tokens.
- **Namespace controller** — drives namespace deletion (deletes contained objects, then removes the namespace).
- **PersistentVolume controllers** — bind PVCs to PVs, provision dynamically.
- **HorizontalPodAutoscaler** (in some distros runs here or via metrics) — scales replica counts based on metrics.
- **Garbage collector** — deletes orphaned objects using **owner references** (and runs **cascading deletion**).

> **Owner reference & cascading deletion**: child objects (a Pod) carry an `ownerReferences` pointer to their parent (a ReplicaSet), which points to its parent (a Deployment). Deleting the parent triggers the **garbage collector** to delete children. Deletion can be **Foreground** (delete children first, then parent), **Background** (delete parent now, children async — the default), or **Orphan** (leave children).

> **Finalizer**: a string in `metadata.finalizers`. While present, the apiserver will *not* hard-delete the object on a delete request — instead it sets `deletionTimestamp` and waits. The responsible controller does cleanup (e.g. release a cloud load balancer) and then *removes* its finalizer; once the list is empty, the object is actually deleted. This is how Kubernetes does reliable pre-delete cleanup. A *stuck* finalizer (controller gone/broken) is a classic "namespace stuck Terminating" incident.

> **Leader election**: you run multiple control-plane replicas for HA, but only one instance of each controller should act at a time (to avoid double-acting). Controllers acquire a **Lease** object (`coordination.k8s.io/v1 Lease`) in `kube-system` and renew it; whoever holds it is the active leader, the rest stand by. Same for the scheduler. The apiserver is the exception — all replicas serve concurrently (it's stateless).

### 3.6 cloud-controller-manager — the cloud bridge

Splits out cloud-provider-specific logic (so core Kubernetes stays vendor-neutral). It runs:

- **Node controller (cloud part)** — fills in node addresses/zones, detects deleted cloud VMs.
- **Route controller** — configures cloud network routes for Pod CIDRs.
- **Service controller** — provisions cloud load balancers for `type: LoadBalancer` Services.

On managed offerings (EKS, GKE, AKS) the cloud-controller-manager and the rest of the control plane are run *for you* by the provider; you don't see those processes.

### 3.7 kubelet — the node agent

The **kubelet** runs on every node and is the bridge between the control plane and the container runtime. It does **not** read etcd; it talks to the apiserver. Loop:

1. **Watch** the apiserver for Pods bound to *its* node (`spec.nodeName == thisNode`).
2. For each such Pod, ensure it's running: pull images, set up the Pod sandbox (network namespace via **CNI**), create/start containers via the **container runtime** over **CRI**, mount volumes via **CSI**.
3. Run **probes** (liveness/readiness/startup) and act (restart container, mark not-ready).
4. Report **status** back to the apiserver (which writes it to etcd): Pod phase, container statuses, node conditions, resource usage.
5. Send **heartbeats** via **Node Lease** objects (lightweight, frequent) and periodically update node status (heavier).

> **CRI (Container Runtime Interface)**: a gRPC API the kubelet uses to talk to *any* compliant container runtime (containerd, CRI-O). It decoupled Kubernetes from Docker; the old "dockershim" was removed in v1.24. **gRPC** is a high-performance RPC framework over HTTP/2 using Protobuf.

> **CNI (Container Network Interface)**: a plugin spec for configuring Pod networking — assigning the Pod an IP and wiring it into the cluster network (Calico, Cilium, Flannel, AWS VPC CNI). **CSI (Container Storage Interface)**: the analogous plugin spec for storage volumes.

> **Liveness / Readiness / Startup probes**: health checks. *Liveness* failing → restart the container. *Readiness* failing → remove the Pod from Service endpoints (no traffic) but don't restart. *Startup* gates the other two during slow boot.

### 3.8 kube-proxy — Service networking on the node

**kube-proxy** runs on every node (as a DaemonSet) and implements the **Service** abstraction at the network layer. It watches Services and EndpointSlices and programs the node's packet-routing rules so that traffic to a Service's **ClusterIP** is load-balanced (DNAT'd) to one of the healthy backend Pod IPs. Modes:

- **iptables** mode (long-time default): programs iptables rules; load balancing is random; O(N) rule traversal can get slow at very large service counts.
- **IPVS** mode: uses the Linux kernel's IP Virtual Server (hash tables, more scheduling algorithms, better at scale).
- **nftables** mode (newer, GA in recent versions): successor to iptables mode, better performance.
- Increasingly, **eBPF**-based dataplanes (Cilium) replace kube-proxy entirely.

> **DNAT (Destination NAT)**: rewriting a packet's destination address — here, ClusterIP → a chosen Pod IP. **eBPF**: a Linux kernel technology to run sandboxed programs in the kernel (e.g. for networking/observability) without changing kernel source.

Note kube-proxy is *data-plane plumbing on the node*, conceptually distinct from the control plane, but you must know it because "Service has no endpoints" / "traffic not balancing" bugs trace through it.

### 3.9 End-to-end trace: `kubectl apply -f deploy.yaml`

Manifest:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
spec:
  replicas: 3
  selector:
    matchLabels: { app: web }
  template:
    metadata:
      labels: { app: web }
    spec:
      containers:
        - name: web
          image: nginx:1.25
          ports: [{ containerPort: 80 }]
```

Step by step, what happens across the whole system:

1. **Client side (`kubectl`).** `kubectl` reads the YAML, discovers the API endpoint for `apps/v1/Deployment` (via the apiserver's discovery API, cached locally), and computes a **strategic-merge patch** vs the **last-applied-configuration** annotation (for `apply`'s 3-way merge). It authenticates using your kubeconfig (cert/token).
2. **Transport.** `kubectl` sends an HTTPS request to the apiserver (often through a load balancer in front of multiple apiserver replicas).
3. **Apiserver pipeline.** Authn → authz (RBAC: can this user `create/patch deployments` in this namespace?) → mutating admission (e.g. defaulting, sidecar injection) → schema validation → validating admission (e.g. OPA/Kyverno policy) → **persist** the Deployment object to etcd under `/registry/deployments/<ns>/web`. Apiserver returns the stored object with a `uid` and `resourceVersion`. **At this point no Pods exist yet** — only the Deployment intent is stored.
4. **etcd commit.** The write goes through Raft: leader replicates to followers, commits on quorum, applies. The apiserver's watch cache and all watchers are notified of the new Deployment.
5. **Deployment controller reacts.** It's watching Deployments via an informer. It sees a Deployment with no matching ReplicaSet, so it **creates a ReplicaSet** (with the Pod template and `replicas: 3`) via the apiserver — another full pipeline + etcd write. It sets owner references (ReplicaSet owned by Deployment).
6. **ReplicaSet controller reacts.** It's watching ReplicaSets and Pods. It sees a ReplicaSet wanting 3 Pods but 0 exist, so it **creates 3 Pod objects** via the apiserver (each owned by the ReplicaSet). These Pods have `spec.nodeName` empty — **unscheduled**.
7. **Scheduler reacts.** Watching unscheduled Pods, it picks each Pod, runs Filter+Score over nodes, and **binds** it by writing `spec.nodeName` (via the Pod's `binding` subresource → apiserver → etcd).
8. **Kubelet reacts.** On each chosen node, the kubelet (watching Pods bound to *its* node) sees a new Pod. It: pulls `nginx:1.25` via the runtime (CRI), sets up the Pod network (CNI assigns an IP), mounts volumes (CSI/none here), starts the container, and runs probes.
9. **Status flows back.** The kubelet writes Pod status (phase `Running`, container ready) to the apiserver → etcd. The ReplicaSet controller observes `Ready` Pods and updates the ReplicaSet's `status.readyReplicas`; the Deployment controller rolls that up into the Deployment's `status` and conditions (`Available`, `Progressing`).
10. **Service wiring (if a Service selects `app=web`).** The EndpointSlice controller observes the now-Ready Pods and adds their IPs to the Service's EndpointSlice; kube-proxy on every node observes the EndpointSlice and programs routing so ClusterIP traffic balances to those Pods.
11. **Convergence.** `kubectl get deploy web` now shows `3/3`. If a Pod dies, the ReplicaSet controller recreates it; if a node dies, the node controller taints it, Pods are evicted/rescheduled, and the loop repeats. The system never "finishes" — it holds the desired state against disturbance.

**Crucial takeaways from the trace:** (a) every state change is a write to etcd via the apiserver; (b) components communicate *indirectly* through the apiserver/etcd, never directly with each other (loose coupling); (c) it's a *cascade of independent reconcile loops*, each doing one small thing, watching for the next layer's objects. This decoupling is why Kubernetes is robust and extensible.

### 3.10 A rolling update trace (so you see the state machine)

`kubectl set image deploy/web web=nginx:1.26` (or editing the manifest and re-applying):

1. Deployment `spec.template` changes → apiserver/etcd write.
2. Deployment controller notices the template hash changed and **creates a new ReplicaSet** (replicas 0 initially), keeping the old one.
3. Per the **RollingUpdate** strategy (`maxSurge`, `maxUnavailable`), the controller incrementally **scales up the new RS and scales down the old RS**, a few Pods at a time, waiting for new Pods to become `Ready` (governed by `minReadySeconds`, readiness probes) before proceeding.
4. It tracks progress; if Pods fail to become ready within `progressDeadlineSeconds` (default 600s), it marks the Deployment `Progressing=False` (stalled) — but does **not** auto-rollback (you must `kubectl rollout undo`).
5. When the new RS is at full replicas and old is at 0, the rollout is complete. The old RS is kept (up to `revisionHistoryLimit`, default 10) to allow rollback.

This is a textbook **state machine** driven entirely by the reconcile loop comparing desired template-hash to the set of ReplicaSets that exist.

---

## 4. The complete toolkit

### 4.1 Control-plane & node components (what each is)

| Component | Where it runs | Role | Talks to etcd? | HA model |
|---|---|---|---|---|
| `kube-apiserver` | control-plane nodes | REST API front door; authn/authz/admission/validation; the only etcd client | **Yes (only one)** | All replicas active (stateless, behind LB) |
| `etcd` | control-plane (or external) | Consistent KV store of all cluster state | n/a (is etcd) | Raft quorum, odd count (3/5) |
| `kube-scheduler` | control-plane nodes | Assigns unscheduled Pods to nodes | No (via apiserver) | Leader election (1 active) |
| `kube-controller-manager` | control-plane nodes | Runs built-in reconcile controllers | No (via apiserver) | Leader election (1 active) |
| `cloud-controller-manager` | control-plane / managed | Cloud-specific node/route/LB logic | No (via apiserver) | Leader election (1 active) |
| `kubelet` | every node | Manages Pods/containers on its node; reports status | No (via apiserver) | One per node (no election) |
| `kube-proxy` | every node | Implements Service routing (iptables/IPVS/nftables) | No (via apiserver) | One per node (DaemonSet) |
| Container runtime | every node | Runs containers via CRI (containerd/CRI-O) | No | One per node |

### 4.2 Key kube-apiserver flags (selected, with defaults)

| Flag | Purpose | Default / note |
|---|---|---|
| `--etcd-servers` | etcd endpoints | required |
| `--enable-admission-plugins` | turn on admission plugins | a sane default set is on already |
| `--disable-admission-plugins` | turn off defaults | — |
| `--authorization-mode` | authz modes, comma list | `Node,RBAC` typical |
| `--max-requests-inflight` | non-mutating concurrency cap | 400 (backstop; APF supersedes) |
| `--max-mutating-requests-inflight` | mutating concurrency cap | 200 |
| `--request-timeout` | default request timeout | 1m0s |
| `--watch-cache` | enable watch cache | true |
| `--encryption-provider-config` | encrypt Secrets at rest in etcd | off unless set |
| `--audit-policy-file` / `--audit-log-path` | audit logging | off unless set |
| `--feature-gates` | toggle alpha/beta features | per version |

### 4.3 Key etcd flags / ops (selected)

| Flag / command | Purpose | Default / note |
|---|---|---|
| `--quota-backend-bytes` | DB size quota | ~2 GiB (2147483648); raise carefully (≤8 GiB recommended) |
| `--auto-compaction-mode` / `--auto-compaction-retention` | auto compaction | e.g. `periodic` + `1h`/`8h` |
| `etcdctl endpoint status --write-out=table` | leader, DB size, term | ops |
| `etcdctl endpoint health` | health of members | ops |
| `etcdctl defrag` | reclaim disk after compaction | run per-member, off-peak |
| `etcdctl snapshot save snap.db` | backup | **do this regularly** |
| `etcdctl snapshot restore` | disaster recovery | rebuilds member data dir |
| `etcdctl alarm list` / `alarm disarm` | check/clear NOSPACE etc. | ops |

### 4.4 Key kube-scheduler config (selected)

| Knob | Purpose | Default |
|---|---|---|
| `percentageOfNodesToScore` | fraction of feasible nodes scored | adaptive (50% at small scale, lower at large) |
| `KubeSchedulerConfiguration` profiles/plugins | enable/disable/weight scheduling plugins | framework defaults |
| `PriorityClass` objects | Pod priority for preemption | none until you create them |
| Pod fields: `nodeSelector`, `affinity`, `tolerations`, `topologySpreadConstraints`, `resources.requests` | influence placement | — |

### 4.5 Key kube-controller-manager flags (selected)

| Flag | Purpose | Default |
|---|---|---|
| `--controllers` | enable/disable specific controllers | `*` (all) |
| `--node-monitor-period` | how often to check node health | 5s |
| `--node-monitor-grace-period` | mark node NotReady after | ~40s |
| `--concurrent-deployment-syncs` etc. | per-controller worker count | varies (often 5) |
| `--leader-elect` | enable leader election | true |
| `--terminated-pod-gc-threshold` | GC of terminated pods | 12500 |

### 4.6 Key kubelet flags (selected)

| Flag | Purpose | Default |
|---|---|---|
| `--max-pods` | max Pods on this node | 110 |
| `--node-status-update-frequency` | status report cadence | 10s |
| `--eviction-hard` | resource thresholds to evict Pods | e.g. `memory.available<100Mi` |
| `--container-runtime-endpoint` | CRI socket | containerd/CRI-O socket |
| `--cgroup-driver` | cgroup driver (must match runtime) | `systemd` typical |
| `--rotate-certificates` | auto-rotate kubelet client cert | true (with CSR approval) |

### 4.7 Essential `kubectl` commands (operator's toolkit)

| Command | What it does |
|---|---|
| `kubectl apply -f x.yaml` | declarative create/update (3-way merge) |
| `kubectl diff -f x.yaml` | preview what apply would change |
| `kubectl get <kind> -o wide / -o yaml` | read objects |
| `kubectl describe <kind> <name>` | human view incl. **Events** (great first debug step) |
| `kubectl get events --sort-by=.lastTimestamp` | recent cluster events |
| `kubectl logs <pod> [-c container] [--previous]` | container logs (`--previous` = crashed instance) |
| `kubectl rollout status/history/undo deploy/<name>` | manage rollouts |
| `kubectl scale deploy/<name> --replicas=N` | imperative scale |
| `kubectl explain <kind>.spec...` | schema docs from the apiserver |
| `kubectl auth can-i <verb> <resource>` | test RBAC |
| `kubectl api-resources` / `api-versions` | discovery |
| `kubectl get --raw /metrics` | apiserver metrics |
| `kubectl get componentstatuses` (deprecated) / health endpoints | control-plane health |
| `kubectl -n kube-system get leases` | see leader-election holders |

> **`kubectl describe` Events** are usually your fastest signal: scheduling failures, image pull errors, probe failures, and admission rejections all show up there.

---

## 5. Code examples by use case

These are idiomatic and copy-adaptable. Where the topic is language-relevant we default to Java (the reader's ecosystem); for cluster artifacts we use YAML/CLI as that's the native language of the control plane.

### 5.1 A complete Deployment + Service (the bread and butter)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  labels: { app: web }
spec:
  replicas: 3
  selector:
    matchLabels: { app: web }
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1          # may temporarily exceed desired replicas by 1
      maxUnavailable: 0    # never drop below desired during rollout (zero-downtime)
  minReadySeconds: 5       # a new Pod must stay Ready 5s before counted available
  template:
    metadata:
      labels: { app: web }
    spec:
      containers:
        - name: web
          image: nginx:1.25
          ports: [{ containerPort: 80 }]
          resources:
            requests: { cpu: "100m", memory: "128Mi" }  # scheduler uses requests for bin-packing
            limits:   { cpu: "500m", memory: "256Mi" }  # kubelet enforces; exceeding mem => OOMKilled
          readinessProbe:                                 # gate traffic until ready
            httpGet: { path: /healthz, port: 80 }
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:                                  # restart if hung
            httpGet: { path: /livez, port: 80 }
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: web
spec:
  selector: { app: web }   # routes to Pods with this label
  ports: [{ port: 80, targetPort: 80 }]
  # type: ClusterIP (default) — internal stable VIP
```

What matters: `requests` drive scheduling (§3.4); `maxUnavailable: 0` + `maxSurge: 1` gives a zero-downtime rollout; the readiness probe is what keeps a not-yet-ready Pod out of the Service endpoints.

### 5.2 A Java client interacting with the control plane (official client)

Using the official Kubernetes Java client (`io.kubernetes:client-java`) to list and watch Pods — exactly the list-then-watch pattern controllers use.

```java
// build.gradle: implementation 'io.kubernetes:client-java:21.0.0'
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import com.google.gson.reflect.TypeToken;

public class PodWatcher {
  public static void main(String[] args) throws Exception {
    // Auto-detects kubeconfig (~/.kube/config) or in-cluster service-account token.
    ApiClient client = Config.defaultClient();
    client.setReadTimeout(0); // 0 = no timeout, required for long-lived watches
    CoreV1Api api = new CoreV1Api(client);

    // 1) LIST: get current state + a starting resourceVersion (the informer pattern).
    V1PodList list = api.listNamespacedPod("default")
        .execute();                       // builder-style in recent client versions
    String rv = list.getMetadata().getResourceVersion();
    list.getItems().forEach(p ->
        System.out.println("EXISTING " + p.getMetadata().getName()));

    // 2) WATCH from that resourceVersion: stream changes without polling.
    try (Watch<V1Pod> watch = Watch.createWatch(
            client,
            api.listNamespacedPod("default")
               .resourceVersion(rv)
               .watch(true)
               .buildCall(null),
            new TypeToken<Watch.Response<V1Pod>>() {}.getType())) {
      for (Watch.Response<V1Pod> ev : watch) {
        // ev.type is ADDED / MODIFIED / DELETED / BOOKMARK
        System.out.printf("%s %s phase=%s%n",
            ev.type,
            ev.object.getMetadata().getName(),
            ev.object.getStatus() == null ? "?" : ev.object.getStatus().getPhase());
      }
    }
    // In production: when the watch closes (it will), re-list to refresh rv and re-watch.
    // The SharedInformer abstraction does this for you (see client-java's informer package).
  }
}
```

Key teaching points: `setReadTimeout(0)` because watches are long-lived; you **must** handle watch closure by re-listing (apiservers periodically close watches and may return `410 Gone` if your `resourceVersion` is too old — then you re-LIST). The Java client also ships `SharedInformerFactory` which implements the full Reflector/DeltaFIFO/cache machinery for you.

### 5.3 A minimal controller in Java (reconcile loop with the informer framework)

```java
// A toy "PodLabeler": ensures every Pod in a namespace carries label managed=true.
// Demonstrates: informer cache + workqueue + level-triggered reconcile.
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.extended.controller.*;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.*;
import io.kubernetes.client.extended.workqueue.WorkQueue;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.openapi.ApiClient;

public class PodLabeler {
  public static void main(String[] args) throws Exception {
    ApiClient client = Config.defaultClient();
    client.setReadTimeout(0);
    CoreV1Api core = new CoreV1Api(client);
    SharedInformerFactory factory = new SharedInformerFactory(client);

    SharedIndexInformer<V1Pod> podInformer = factory.sharedIndexInformerFor(
        params -> core.listNamespacedPod("default")
                      .resourceVersion(params.resourceVersion)
                      .timeoutSeconds(params.timeoutSeconds)
                      .watch(params.watch)
                      .buildCall(null),
        V1Pod.class, io.kubernetes.client.openapi.models.V1PodList.class);

    Reconciler reconciler = request -> {
      // request = namespace/name. We read from the LOCAL CACHE (cheap), not the API.
      V1Pod pod = podInformer.getIndexer()
          .getByKey(request.getNamespace() + "/" + request.getName());
      if (pod == null) return new Result(false); // deleted; nothing to do (level-triggered)
      boolean labeled = pod.getMetadata().getLabels() != null
          && "true".equals(pod.getMetadata().getLabels().get("managed"));
      if (!labeled) {
        // PATCH via apiserver -> etcd. Use a JSON merge patch.
        // (omitted: build and send patch with core.patchNamespacedPod(...))
        System.out.println("Would label " + request.getName());
      }
      return new Result(false); // false = no requeue; return Result(true, delay) to retry
    };

    Controller controller = ControllerBuilder
        .defaultBuilder(factory)
        .watch(q -> ControllerBuilder.controllerWatchBuilder(V1Pod.class, q).build())
        .withReconciler(reconciler)
        .withWorkerCount(2)
        .build();

    factory.startAllRegisteredInformers();
    controller.run(); // blocks; runs reconcile loop forever
  }
}
```

Teaching points: the reconcile function is **level-triggered** — it re-derives the action from current state and is safe to call repeatedly; it reads from the **local cache**; writes go back through the apiserver; the **workqueue** rate-limits and dedups so a hot object doesn't spin the CPU.

### 5.4 A Custom Resource Definition + custom object (extending the control plane)

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: widgets.example.com
spec:
  group: example.com
  scope: Namespaced
  names: { plural: widgets, singular: widget, kind: Widget, shortNames: [wd] }
  versions:
    - name: v1
      served: true
      storage: true                 # exactly one version is the storage version
      subresources: { status: {} }  # enables /status subresource (spec/status split)
      schema:
        openAPIV3Schema:
          type: object
          properties:
            spec:
              type: object
              required: [size]
              properties:
                size: { type: integer, minimum: 1, maximum: 100 }
                color: { type: string, enum: [red, green, blue] }
            status:
              type: object
              properties:
                ready: { type: boolean }
      additionalPrinterColumns:      # what `kubectl get widgets` shows
        - { name: Size, type: integer, jsonPath: .spec.size }
        - { name: Ready, type: boolean, jsonPath: .status.ready }
---
apiVersion: example.com/v1
kind: Widget
metadata: { name: my-widget }
spec: { size: 3, color: blue }
```

After `kubectl apply`, `kubectl get widgets` works as if `Widget` were native. An **operator** (a controller like §5.3 but watching `Widget`) would reconcile each Widget into real resources (Pods, Services). This is the standard extension path.

### 5.5 RBAC for a controller's service account (least privilege)

```yaml
apiVersion: v1
kind: ServiceAccount
metadata: { name: widget-operator, namespace: ops }
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata: { name: widget-operator }
rules:
  - apiGroups: ["example.com"]
    resources: ["widgets", "widgets/status"]
    verbs: ["get", "list", "watch", "update", "patch"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata: { name: widget-operator }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: widget-operator }
subjects:
  - { kind: ServiceAccount, name: widget-operator, namespace: ops }
```

Note the operator gets `watch` (for its informers) and only the verbs it needs. Granting `*` is the anti-pattern.

### 5.6 A validating admission policy in CEL (no webhook server needed)

```yaml
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata: { name: require-resource-limits }
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
      - apiGroups: ["apps"]; apiVersions: ["v1"]; operations: ["CREATE","UPDATE"]; resources: ["deployments"]
  validations:
    - expression: >
        object.spec.template.spec.containers.all(c,
          has(c.resources) && has(c.resources.limits) && has(c.resources.limits.memory))
      message: "every container must set a memory limit"
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata: { name: require-resource-limits-binding }
spec:
  policyName: require-resource-limits
  validationActions: ["Deny"]
  matchResources:
    namespaceSelector: {}   # all namespaces
```

This rejects any Deployment whose containers lack a memory limit — enforced **in-process** in the apiserver via CEL, avoiding the availability risk of an external webhook.

### 5.7 etcd disaster recovery (the ops example you hope to never run)

```bash
# Backup (do this on a schedule, e.g. CronJob or systemd timer):
ETCDCTL_API=3 etcdctl \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key \
  snapshot save /backup/etcd-$(date +%F-%H%M).db

# Inspect the snapshot:
etcdutl snapshot status /backup/etcd-....db --write-out=table

# Restore (stop apiserver+etcd first; restore on each member with matching peer URLs):
etcdutl snapshot restore /backup/etcd-....db \
  --name etcd-node1 \
  --initial-cluster etcd-node1=https://10.0.0.1:2380 \
  --initial-advertise-peer-urls https://10.0.0.1:2380 \
  --data-dir /var/lib/etcd-restored
# Point etcd's --data-dir at the restored dir, restart etcd, then apiserver.
```

Key fact: **your cluster is only as durable as your etcd backups.** Losing etcd quorum without a backup loses the cluster's entire declared state.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **etcd is the scaling bottleneck.** It's a single Raft group; write throughput is bounded by fsync + network round-trips for quorum. Keep etcd on **fast local SSD/NVMe**, low-latency links between members, and *separate disks* from the rest. Watch `etcd_disk_wal_fsync_duration_seconds` (p99 should be single-digit ms) and `etcd_disk_backend_commit_duration_seconds`.
- **Keep objects small and few.** Huge ConfigMaps/Secrets, giant annotations (e.g. the `last-applied-configuration` from `apply` on big objects), and high object counts bloat etcd and the watch cache. Kubernetes scalability SLOs target ~5,000 nodes, ~150,000 Pods, ~300,000 total objects per cluster (version-dependent guidance, not hard limits).
- **Use APF** to protect critical traffic; watch `apiserver_flowcontrol_*` metrics for rejected/queued requests.
- **Don't poll the API in tight loops.** Use informers/watches. Polling thousands of times a second is a classic self-inflicted apiserver overload.
- **Right-size `resources.requests`.** Over-requesting wastes capacity (poor bin-packing); under-requesting causes contention and evictions. The scheduler bin-packs on *requests*; the kubelet enforces *limits*.

### 6.2 Correctness & concurrency

- **Embrace optimistic concurrency.** On a `409 Conflict`, re-read and retry — never blindly overwrite. The Java client and informer framework give you the `resourceVersion` to do this.
- **Make reconcile idempotent and level-triggered.** Never assume you'll see every event; always derive action from current state. Returning early when the object is gone (deleted) is correct.
- **Use finalizers for cleanup that must happen before deletion** (releasing external resources). Always *remove your finalizer* when done, or you'll wedge deletion.
- **Set ownerReferences** so garbage collection cleans up children — otherwise you leak orphaned objects.

### 6.3 Security

- **etcd holds your Secrets in plaintext by default.** Enable **encryption at rest** (`--encryption-provider-config`, ideally KMS-backed) and restrict etcd to mutual-TLS, firewalled to apiserver only. Anyone with etcd access owns the cluster.
- **RBAC least privilege.** No wildcard verbs/resources for app service accounts. Audit `ClusterRoleBinding`s to `cluster-admin`.
- **Lock down admission webhooks**: use `Fail` only where you can guarantee webhook HA; always exclude `kube-system`; set tight `timeoutSeconds`.
- **Enable audit logging** (`--audit-policy-file`) for a forensic trail of every API request.
- **Protect the apiserver**: it's the single front door — put it behind authn that you control, rotate certs/tokens, and never expose it unauthenticated.

### 6.4 Observability

- **Apiserver metrics** (Prometheus format at `/metrics`): `apiserver_request_duration_seconds` (latency by verb/resource/code), `apiserver_request_total` (watch for `429`/`5xx`), `apiserver_current_inflight_requests`, `etcd_request_duration_seconds`, `apiserver_flowcontrol_rejected_requests_total`.
- **etcd metrics**: fsync/commit durations, `etcd_server_leader_changes_seen_total` (frequent leader changes = unhealthy), `etcd_mvcc_db_total_size_in_bytes` (vs quota), `etcd_server_has_leader`.
- **Controller metrics**: `workqueue_depth` (backed-up reconciles), `workqueue_adds_total`, `rest_client_requests_total` (your controller's API load), reconcile error rates.
- **Events**: `kubectl get events` and the Events API; these explain *why* (FailedScheduling, FailedMount, Unhealthy probe).
- **Audit logs** for "who did what."

### 6.5 Cost

- The control plane (3 etcd + apiservers + controllers + scheduler, replicated for HA) is fixed overhead; on managed services you pay a per-cluster control-plane fee (e.g. GKE/EKS charge roughly ~$0.10/hr per cluster — vendor- and time-specific, verify). The cost lever you actually control is **node count and right-sizing** (requests/limits) and avoiding over-provisioned HA you don't need.

### 6.6 Testing

- **`kubectl diff` / `--dry-run=server`** to preview changes (server dry-run runs admission without persisting).
- **`envtest`** (controller-runtime) spins up a real apiserver+etcd binary (no kubelet/scheduler) for fast integration tests of controllers.
- **`kind`** (Kubernetes-in-Docker) and **minikube** for local full clusters in CI.
- **Conformance tests / e2e** for cluster validation.
- Test your controller's reconcile as a pure function over fake informer caches.

### 6.7 Production hardening

- **etcd**: odd member count (3 or 5), regular snapshots tested by *actual restore drills*, auto-compaction enabled, defrag scheduled off-peak, monitored quota.
- **HA control plane**: ≥3 control-plane nodes, apiservers behind a load balancer, leader election on for scheduler/controller-manager.
- **Stacked vs external etcd**: decide deliberately (§8).
- **Upgrade discipline**: skew policy — kubelet may be up to 3 minor versions behind the apiserver; the apiserver must be ≥ every other control-plane component; upgrade one minor version at a time, control plane first.
- **PodDisruptionBudgets** so voluntary disruptions (node drains, upgrades) don't take your app below quorum.

### 6.8 Anti-patterns to avoid

- Treating Kubernetes imperatively (lots of `kubectl create/edit` instead of versioned `apply`/GitOps) — you lose reproducibility and drift correction.
- Writing edge-triggered controllers that assume every event arrives.
- `failurePolicy: Fail` webhooks matching `*/Pods` with a single non-HA webhook server → cluster-wide outage when the webhook is down.
- Putting application data in etcd (huge objects, frequent writes) — etcd is not your app database.
- Ignoring `resources.requests` (scheduler can't bin-pack; everything looks "free" then nodes thrash).
- No etcd backups / never tested a restore.
- Granting `cluster-admin` to app workloads.

---

## 7. Advanced topics & deep internals

### 7.1 The watch cache and how watches really scale

Each apiserver keeps a per-resource **watch cache**: a ring buffer of recent events plus the current object set, fed by a *single* etcd watch per resource. When 1,000 controllers each open a watch on Pods, they don't open 1,000 etcd watches — they all read from the apiserver's watch cache. The cache also serves **`resourceVersion=0` LISTs from memory** (cheaper than hitting etcd). **Watch bookmarks** (`BOOKMARK` events) periodically tell clients "you're current as of version X" so that after a disconnect they can resume without a full re-list. When a client's `resourceVersion` falls out of the ring buffer (too old), the apiserver returns **`410 Gone`** and the client must re-LIST. The newer **WatchList / streaming list** feature streams a consistent LIST as watch events to reduce the memory spike of huge LISTs.

### 7.2 Consistent reads, the watch cache, and a subtle pitfall

Historically, LISTs served from the watch cache could be slightly stale relative to a quorum read. Kubernetes added **consistent reads from cache** (using etcd's progress notifications to know the cache is caught up to a given revision) so the apiserver can serve consistent LISTs from memory without a quorum etcd read — a big efficiency win. Know that *quorum reads* (linearizable) go to etcd and cost a Raft round; cached reads are cheaper but the apiserver now guarantees their freshness for consistent requests.

### 7.3 Storage version, conversion, and CRD versioning

Each resource has one **storage version** in etcd. When you add a new API version, the apiserver converts on the fly (native types via internal hub version; CRDs via **conversion webhooks** if versions aren't structurally identical). The **storage-version-migrator** rewrites stored objects to the new storage version during upgrades so old encodings don't linger. For CRDs, exactly one version has `storage: true`; serving multiple versions with schema changes requires a conversion strategy (`None` if identical, else `Webhook`).

### 7.4 Server-Side Apply (SSA) and field management

**Server-Side Apply** moves merge logic into the apiserver and tracks **field ownership** via `managedFields` — each manager (a user, a controller, kubectl) "owns" the fields it set. Conflicts (two managers setting the same field) are detected and reported, replacing the brittle client-side 3-way merge. This is how multiple controllers can co-own one object's different fields without clobbering each other. Use `kubectl apply --server-side`; controllers should use SSA with a stable `fieldManager`.

### 7.5 Scheduler internals: queues, backoff, and preemption

The scheduler maintains three queues: **activeQ** (ready to schedule, a priority heap), **backoffQ** (recently failed, waiting out exponential backoff), and **unschedulableQ** (couldn't schedule; moved back to active when relevant cluster events occur — e.g. a node added, a Pod deleted). **Event-driven requeueing** (`EnqueueExtensions` / `QueueingHint`) avoids periodically retrying Pods that have no chance, a major scalability improvement. **Preemption** computes a victim set on a node such that removing them makes the high-priority Pod fit while minimizing disruption, respecting PodDisruptionBudgets best-effort.

### 7.6 Node heartbeats: Lease objects

Originally nodes updated their full `Node.status` every 10s — expensive at scale (every update is an etcd write). The **Node Lease** (`coordination.k8s.io/v1`, one tiny Lease per node in `kube-node-lease`) is a cheap heartbeat the kubelet renews frequently, while the heavier `Node.status` is updated only when something changes or every ~5 minutes. The node controller watches Leases to detect dead nodes far more cheaply.

### 7.7 Graceful node shutdown, eviction, and disruption

The kubelet supports **graceful node shutdown** (detecting systemd shutdown and terminating Pods in priority order within a grace window). The node controller adds **`NoExecute` taints** on unhealthy nodes; the **taint-based eviction** then deletes Pods after `tolerationSeconds`. **API-initiated eviction** (`/eviction` subresource) respects **PodDisruptionBudgets**, which is what `kubectl drain` uses.

### 7.8 Bootstrapping the control plane (the chicken-and-egg)

How does the apiserver start before there are Pods? On self-managed clusters (`kubeadm`), control-plane components run as **static Pods** — manifests in `/etc/kubernetes/manifests/` that the *kubelet itself* starts directly (no scheduler, no apiserver needed). The kubelet starts the apiserver/etcd/scheduler/controller-manager as static Pods; once the apiserver is up, the rest of the cluster bootstraps normally. This breaks the chicken-and-egg.

### 7.9 The aggregation layer

The **aggregation layer** lets you register additional API servers behind the main apiserver (`APIService` objects), so extension APIs (e.g. `metrics.k8s.io` served by metrics-server, used by HPA/`kubectl top`) appear under the same endpoint. Unlike CRDs (stored in etcd by the core apiserver), aggregated APIs are served by *your* server with *your* storage — more power, more responsibility.

### 7.10 Tuning knobs worth knowing

- etcd `--heartbeat-interval` (default 100ms) and `--election-timeout` (default 1000ms) — raise on high-latency networks to prevent spurious elections; election-timeout should be ~5–10× heartbeat.
- apiserver `--default-watch-cache-size` and per-resource overrides.
- controller-manager `--kube-api-qps` / `--kube-api-burst` and scheduler equivalents — client-side rate limits to the apiserver; raise on large clusters or your controllers self-throttle.
- `--terminated-pod-gc-threshold`, `revisionHistoryLimit`, `ttlSecondsAfterFinished` (Jobs) to control object accumulation in etcd.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Stacked vs external etcd

| Topology | Description | Pros | Cons | Use when |
|---|---|---|---|---|
| **Stacked** | etcd runs on the same nodes as control-plane components (kubeadm default) | Simpler, fewer machines, easy with kubeadm | Losing a control-plane node loses an etcd member too; coupled failure domains | Small/medium clusters, simplicity prioritized |
| **External** | etcd on dedicated machines, separate from apiservers | Decoupled failure domains; tune/scale etcd independently; safer | More machines, more ops complexity | Large/critical clusters; you need etcd isolation/HA |

### 8.2 kube-proxy mode

| Mode | Mechanism | Pros | Cons | Use when |
|---|---|---|---|---|
| **iptables** | iptables DNAT rules | Mature, ubiquitous | O(N) rule scaling; slower with many Services | Default, small/medium service counts |
| **IPVS** | kernel IPVS hash tables | Better scaling, more LB algorithms | Slightly more setup; module deps | Many Services, need scheduling algorithms |
| **nftables** | nftables ruleset | Better perf than iptables, modern | Newer; needs recent kernel/k8s | Recent clusters wanting iptables successor |
| **eBPF (Cilium)** | kernel eBPF programs, replaces kube-proxy | Highest perf, rich features | Different operational model | Large/perf-critical, adopting Cilium |

### 8.3 Admission policy: webhook vs CEL (ValidatingAdmissionPolicy)

| Aspect | External webhook | ValidatingAdmissionPolicy (CEL) |
|---|---|---|
| Where it runs | Your HTTPS server | In-process in apiserver |
| Availability risk | High (down webhook can block/allow incorrectly) | None (no external dependency) |
| Latency | Network round-trip per request | Negligible |
| Mutation | Mutating webhooks can mutate | Validation only (mutating CEL policy is newer/limited) |
| Power | Arbitrary code, external data | CEL expressions only |
| Use when | Complex logic, external lookups, mutation/injection | Pure validation expressible in CEL |

### 8.4 Self-managed vs managed control plane

| | Self-managed (kubeadm/kops) | Managed (EKS/GKE/AKS) |
|---|---|---|
| Who runs apiserver/etcd/controllers | You | Cloud provider |
| etcd backups, upgrades, HA | Your responsibility | Provider handles |
| Cost | Your VMs only | Per-cluster control-plane fee + nodes |
| Control / flexibility | Full (custom flags, admission, etcd tuning) | Constrained to provider knobs |
| Use when | Need full control, on-prem, special requirements | Want to offload control-plane ops |

### 8.5 Quick "use when / avoid when"

- **Use a CRD + operator when** the lifecycle has nontrivial operational logic (stateful systems, multi-step orchestration) and you want it expressed declaratively in the cluster. **Avoid when** a Helm chart or plain Deployment suffices — operators are real software you must maintain.
- **Use leader election** for any controller you run >1 replica of. **Avoid** running active-active controllers (double action).
- **Use external etcd** for large/critical clusters. **Avoid** the extra complexity for small clusters.
- **Use validating admission (CEL)** for policy that's pure validation. **Reach for webhooks** only when you need mutation or external data.

---

## 9. Failure modes & debugging

### 9.1 Pod stuck in `Pending`

**Cause:** scheduler can't place it. **Diagnose:** `kubectl describe pod <p>` → Events show `FailedScheduling` with the reason: insufficient cpu/memory, unsatisfiable affinity/anti-affinity, taints not tolerated, no node matches `nodeSelector`, PVC unbound (no matching PV / storage zone). **Fix:** add capacity, fix requests, add tolerations, fix selectors, or fix storage.

### 9.2 Pod `CrashLoopBackOff`

**Cause:** container starts then exits/fails repeatedly; kubelet backs off exponentially. **Diagnose:** `kubectl logs <p> --previous` (logs of the crashed instance), `kubectl describe pod` for exit codes (137 = OOMKilled/SIGKILL, 1/2 = app error). **Fix:** app bug, missing config/secret, bad command, or memory limit too low (raise `limits.memory` or fix the leak).

### 9.3 Namespace stuck `Terminating`

**Cause:** a **finalizer** on the namespace or a contained object whose controller is gone/broken won't complete cleanup. **Diagnose:** `kubectl get namespace <ns> -o yaml` → look at `spec.finalizers` / `status`. Often an aggregated API (`metrics.k8s.io`) is `Unavailable`, so the namespace can't enumerate its contents. **Fix:** restore the missing API/controller; as a *last resort* remove the finalizer (dangerous — leaks resources).

### 9.4 etcd NOSPACE / cluster read-only

**Cause:** etcd hit `--quota-backend-bytes`; raises a NOSPACE alarm; the cluster goes read-only for writes. **Diagnose:** `etcdctl alarm list`, `etcdctl endpoint status` (DB size), apiserver logs full of etcd errors. **Fix:** compact (`etcdctl compact <rev>`), `etcdctl defrag` each member, then `etcdctl alarm disarm`. Prevent with auto-compaction + monitoring + not stuffing huge objects into etcd. (Real incidents: events/leases or a runaway controller creating millions of objects fills etcd.)

### 9.5 etcd lost quorum

**Cause:** majority of etcd members down (e.g. 2 of 3 nodes died). **Symptom:** apiserver writes fail; cluster can't make decisions; existing Pods keep running (data plane survives) but no scheduling/healing. **Diagnose:** `etcdctl endpoint health`, `etcd_server_has_leader=0`. **Fix:** restore quorum by bringing members back; if unrecoverable, **restore from snapshot** (§5.7). This is the canonical "no backups = lost cluster" disaster.

### 9.6 Admission webhook outage bricks the cluster

**Cause:** a `failurePolicy: Fail` webhook matching a broad resource (e.g. all Pods) becomes unreachable → every create/update of that resource is rejected → you can't even deploy the fix. **Diagnose:** apiserver returns errors mentioning the webhook; `kubectl get validatingwebhookconfigurations`. **Fix:** delete/patch the offending webhook configuration (you need RBAC to do so) or restore the webhook server; scope `namespaceSelector` to exclude `kube-system`. Prevent: HA webhook, tight scope, `Ignore` where safe.

### 9.7 Apiserver overload / 429s / slow

**Cause:** a controller or client polling/listing too aggressively (e.g. LISTing all Pods every second), or a hot loop creating objects. **Diagnose:** `apiserver_request_total` by client/verb, `apiserver_flowcontrol_rejected_requests_total`, `apiserver_current_inflight_requests`; audit logs to find the noisy `userAgent`. **Fix:** make the offender use informers/watches; tune its `--kube-api-qps`; use APF to fence it off; cache LISTs (`resourceVersion=0`).

### 9.8 Node `NotReady`

**Cause:** kubelet not heartbeating (Lease not renewed) — kubelet crashed, network partition, disk pressure, runtime down. **Diagnose:** `kubectl describe node`, conditions (`MemoryPressure`, `DiskPressure`, `Ready`), `journalctl -u kubelet`, check CRI socket. The node controller will taint and (after grace) evict Pods. **Fix:** restart kubelet/runtime, clear disk pressure, fix networking.

### 9.9 Slow etcd disk → leader churn → apiserver latency

**Cause:** etcd on slow/contended disks; fsync latency spikes; Raft heartbeats time out; frequent leader elections; apiserver requests time out. **Diagnose:** `etcd_disk_wal_fsync_duration_seconds` p99, `etcd_server_leader_changes_seen_total` climbing. **Fix:** move etcd to dedicated fast NVMe, isolate from other I/O, raise election timeout only as a stopgap.

### 9.10 General debugging playbook

1. `kubectl describe` the object → read **Events** first.
2. `kubectl logs [--previous]` for app/container issues.
3. `kubectl get events --sort-by=.lastTimestamp -A` for cluster-wide signal.
4. Control-plane health: apiserver `/livez`, `/readyz`, `/healthz?verbose`; etcd `endpoint health`; leader-election leases.
5. Metrics (apiserver/etcd/controller workqueue) for systemic issues.
6. Audit logs to answer "who/what changed this."

---

## 10. Interview drill

**Q1. Walk me through what happens when I run `kubectl apply -f deployment.yaml`.**
*Model answer:* kubectl computes a 3-way merge and sends an HTTPS request to the apiserver; the apiserver runs authn → authz (RBAC) → mutating admission → schema validation → validating admission → persists the Deployment to etcd via Raft. The Deployment controller (watching via informer) creates a ReplicaSet; the ReplicaSet controller creates Pods (unscheduled); the scheduler binds each Pod to a node; the kubelet on that node pulls the image, sets up networking (CNI), starts containers (CRI), runs probes, and reports status back through the apiserver to etcd. Each step is an independent reconcile loop reacting to objects via watches.
- *Follow-up: Where does etcd get written in that flow?* Only by the apiserver, at each object create/update (Deployment, ReplicaSet, Pods, binding, status updates).
- *Follow-up: Which components talk to each other directly?* None — they communicate indirectly through the apiserver. Loose coupling.
- *Follow-up: At what point do Pods actually exist on a node?* Only after the scheduler binds and the kubelet acts; the initial apply only stores the Deployment intent.

**Q2. Why is etcd the only thing the apiserver talks to, and why does only the apiserver talk to etcd?**
*Model answer:* Centralizing etcd access in the apiserver gives one place for authn/authz, admission, validation, API versioning/conversion, and optimistic-concurrency control. It keeps etcd's surface area small and secure (etcd has crude auth) and lets apiservers scale statelessly while etcd remains the single consistency authority.
- *Follow-up: How does the apiserver scale if etcd is a single Raft group?* Apiservers are stateless and scale horizontally behind an LB; reads can be served from the watch cache; etcd remains the write bottleneck, mitigated by caching and small objects.
- *Follow-up: What protects etcd from a flood of client watches?* The watch cache: one etcd watch per resource fans out to all client watches.

**Q3. Explain Raft and why etcd clusters are odd-sized.**
*Model answer:* Raft elects a leader per term; writes go to the leader, replicate to followers, and commit once a majority persists them; a new leader needs majority votes and is guaranteed to have all committed entries. Odd sizing maximizes fault tolerance per node: 3 tolerates 1 failure (quorum 2), 5 tolerates 2 (quorum 3); 4 tolerates only 1 (quorum 3) like 3 but costs more and has a bigger quorum.
- *Follow-up: What happens during a network partition?* The majority side keeps a leader and serves writes; the minority can't elect a leader or commit, preventing split-brain.
- *Follow-up: What's the durability cost per write?* An fsync on a quorum of members plus network round-trips — why etcd needs fast disks/network.

**Q4. What is the reconciliation model and why is level-triggered design important?**
*Model answer:* Controllers run loops that compare desired (`spec`) to actual (`status`) and act to converge. Level-triggered means they act on current state, not on the change event, so a missed event doesn't break correctness — the next observation still converges. This makes Kubernetes self-healing and tolerant of dropped notifications.
- *Follow-up: How does a controller observe state efficiently?* Informers: list-then-watch with a local cache and a workqueue.
- *Follow-up: What's the danger of an edge-triggered controller?* If it misses the event, it's permanently wrong.

**Q5. Describe the admission control pipeline and the risks of webhooks.**
*Model answer:* After authn/authz: mutating admission (can modify the object) → schema validation → validating admission (allow/deny). Webhooks let you inject custom logic. Risks: a `failurePolicy: Fail` webhook that's unreachable can block all matching writes (cluster-bricking), and slow webhooks add latency to every matching request. Mitigate with HA, tight scope, excluding `kube-system`, short timeouts, or use in-process CEL `ValidatingAdmissionPolicy`.
- *Follow-up: Mutating vs validating order?* All mutating first, re-validate schema, then validating.
- *Follow-up: How would you enforce "all containers must set memory limits" most safely?* A `ValidatingAdmissionPolicy` (CEL) — no external dependency, no availability risk.

**Q6. What does the scheduler actually do, and how does it scale to thousands of nodes?**
*Model answer:* It watches unscheduled Pods and, per Pod, runs Filter (feasibility) then Score (ranking) plugins, then binds the best node by setting `spec.nodeName`. It scales by scoring only a fraction of feasible nodes (`percentageOfNodesToScore`) and by event-driven requeueing so it doesn't retry hopeless Pods.
- *Follow-up: What's preemption?* Evicting lower-priority Pods to fit a higher-priority one when nothing's feasible.
- *Follow-up: Does the scheduler start containers?* No — the kubelet does; the scheduler only assigns the node.

**Q7. How do finalizers and garbage collection work?**
*Model answer:* Owner references link children to parents; deleting a parent triggers cascading deletion of children via the GC controller. Finalizers block hard deletion: on delete, the apiserver sets `deletionTimestamp` and waits while finalizers remain; the responsible controller does cleanup and removes its finalizer, after which the object is deleted. A stuck finalizer wedges deletion.
- *Follow-up: Foreground vs background vs orphan deletion?* Foreground deletes children first; background (default) deletes parent now and children async; orphan leaves children.
- *Follow-up: How do you debug a namespace stuck Terminating?* Inspect finalizers and check for unavailable aggregated APIs.

**Q8. (Senior-signal) When would you choose external etcd over stacked, and what are the operational consequences?**
*Model answer:* Choose external etcd for large or business-critical clusters where you want decoupled failure domains and independent etcd scaling/tuning; the cost is more machines and operational complexity (separate TLS, backups, monitoring, upgrades). Stacked is fine for small/medium clusters where simplicity wins, accepting that a control-plane node loss also costs an etcd member. The deciding factors are blast-radius tolerance, scale, and ops maturity.
- *Follow-up: How many etcd members and why?* 3 for most (tolerate 1), 5 for critical (tolerate 2); odd for quorum efficiency.
- *Follow-up: What's your etcd DR plan?* Scheduled snapshots, monitored quota, periodic *tested* restores, fast isolated disks.

**Q9. (Senior-signal) Your apiserver p99 latency spiked and you see 429s. How do you diagnose and what are the likely root causes?**
*Model answer:* Pull `apiserver_request_total`/`apiserver_request_duration_seconds` by verb/resource/client and `apiserver_flowcontrol_rejected_requests_total`; check `etcd_request_duration_seconds` and etcd fsync metrics to separate "apiserver overloaded by clients" from "etcd is slow." Common causes: a controller polling or LISTing aggressively, a hot create loop, slow etcd disk, or APF starving a priority level. Fixes: convert offenders to informers, tune QPS/burst, fix APF priority levels, speed up etcd disk. The senior signal is methodically isolating client-side load vs etcd-side latency.
- *Follow-up: How do you find the noisy client?* Audit logs / `userAgent` in metrics.
- *Follow-up: How does APF help?* Fair queuing by priority so non-critical floods can't starve node heartbeats/leader election.

**Q10. (Senior-signal) Justify using a CRD + operator versus a Helm chart for deploying a stateful database.**
*Model answer:* A Helm chart templates static manifests — great for install/upgrade, but it has no runtime intelligence. A database needs ongoing operational logic: ordered scaling, backups, failover, version-aware upgrades, reacting to failures. An operator encodes that as a controller reconciling a custom resource, giving day-2 automation and a declarative API (`kind: PostgresCluster`). The tradeoff: the operator is real software you must build, test, secure (RBAC), and maintain, and it adds a controller to your cluster. Choose the operator when day-2 operational complexity is high; choose Helm when the lifecycle is essentially static.
- *Follow-up: How does the operator extend the control plane technically?* A CRD registers the new kind in the apiserver (stored in etcd, watchable); the operator is a controller watching it.
- *Follow-up: What RBAC should the operator have?* Least privilege: watch/update its CRD + status, and only the verbs it needs on the resources it manages.

**Q11. What is the watch/informer mechanism and why not just poll?**
*Model answer:* An informer does an initial LIST (full state + starting resourceVersion) then a WATCH stream of deltas, maintaining a local cache and a workqueue; reconcilers read the cheap cache and write through the apiserver. Polling would hammer the apiserver and etcd and add latency; watches push changes efficiently and the local cache offloads reads.
- *Follow-up: What's a 410 Gone in a watch?* Your resourceVersion is too old (fell out of the watch cache ring buffer); you must re-LIST and re-watch.
- *Follow-up: What are watch bookmarks for?* Periodic "you're current as of X" events so a reconnecting client can resume without a full re-list.

**Q12. What survives an etcd outage, and what doesn't?**
*Model answer:* The **data plane** survives: already-running Pods keep running, kube-proxy keeps routing with its last-known config, Services keep working. The **control plane** stops making decisions: no scheduling, no self-healing, no scaling, no config changes (writes fail). Recovery means restoring etcd quorum or restoring from a snapshot.
- *Follow-up: Why do running Pods survive?* The kubelet and kube-proxy operate on last-known desired state and don't need etcd directly (they need the apiserver, which needs etcd, but the *running* containers don't).
- *Follow-up: What's the first thing you'd do?* Check `etcdctl endpoint health` and quorum; restore members or snapshot.

---

## 11. Glossary

- **ABAC** — Attribute-Based Access Control; an older authz mode, largely superseded by RBAC.
- **Admission controller** — code that runs after authz to allow/modify/reject API requests before persistence.
- **Affinity / Anti-affinity** — scheduling rules attracting/repelling Pods to nodes or other Pods.
- **Aggregation layer** — mechanism to serve extension APIs behind the main apiserver via `APIService` objects.
- **annotation** — non-identifying key/value metadata on an object (not used for selection).
- **API group / version** — namespacing of the API (e.g. `apps/v1`); enables independent evolution of resource types.
- **APF (API Priority and Fairness)** — fair queuing of apiserver requests by priority to prevent starvation.
- **apiserver (kube-apiserver)** — the REST front door; the only etcd client; handles authn/authz/admission/validation.
- **BOOKMARK** — a watch event signaling the client is current as of a given resourceVersion.
- **cascading deletion** — deleting children when a parent is deleted, via owner references.
- **CEL (Common Expression Language)** — sandboxed expression language used for in-process admission validation.
- **cloud-controller-manager** — runs cloud-provider-specific controllers (LB, routes, node).
- **CNI (Container Network Interface)** — plugin spec for Pod networking.
- **compaction** — discarding old etcd revisions (history) to bound growth.
- **consensus** — getting unreliable machines to agree on a replicated log; solved here by Raft.
- **container** — isolated process package sharing the host kernel.
- **container runtime** — software that runs containers (containerd, CRI-O) via CRI.
- **control loop / controller** — a reconcile loop converging actual state to desired state.
- **control plane** — the cluster's decision-making and state-storing components.
- **CRD (Custom Resource Definition)** — registers a new API kind, extending the cluster.
- **CRI (Container Runtime Interface)** — gRPC API between kubelet and the runtime.
- **CSI (Container Storage Interface)** — plugin spec for storage volumes.
- **DaemonSet** — controller ensuring one Pod per node.
- **data plane** — the components/traffic doing the actual workload (running Pods).
- **declarative** — describing desired end state rather than steps.
- **defragmentation** — reclaiming etcd disk space freed by compaction.
- **Deployment** — controller for declarative rolling updates of stateless apps.
- **desired state (spec)** — what you want.
- **DNAT** — destination network address translation (ClusterIP → Pod IP).
- **eBPF** — running sandboxed programs in the Linux kernel (networking/observability).
- **edge-triggered** — reacting to change events (fragile if events are missed).
- **EndpointSlice** — scalable mapping of a Service to its backend Pod IPs.
- **etcd** — distributed strongly-consistent key-value store; the cluster's source of truth.
- **eventual consistency** — replicas converge to the same state over time.
- **fieldManager / managedFields** — Server-Side Apply's record of which manager owns which fields.
- **finalizer** — a marker that blocks hard deletion until cleanup completes.
- **fsync** — syscall forcing buffered writes to durable storage.
- **garbage collector** — controller deleting orphaned objects via owner references.
- **GitOps** — driving cluster state declaratively from a Git repository.
- **goroutine** — a lightweight Go thread; controllers run many.
- **gRPC** — RPC framework over HTTP/2 with Protobuf.
- **heartbeat** — periodic liveness signal (Raft heartbeats; node Leases).
- **HPA (HorizontalPodAutoscaler)** — scales replica counts based on metrics.
- **idempotent** — repeatable with the same effect as doing it once.
- **informer** — client machinery doing list-then-watch with a local cache and event handlers.
- **iptables / IPVS / nftables** — Linux packet-routing mechanisms kube-proxy uses for Services.
- **kubectl** — the CLI client to the apiserver.
- **kubelet** — node agent managing Pods/containers and reporting status.
- **kube-proxy** — node component implementing Service networking.
- **label** — identifying key/value tag used for selection.
- **Lease** — small object used for heartbeats and leader election (`coordination.k8s.io`).
- **leader election** — ensuring only one active instance of a controller acts at a time.
- **level-triggered** — reacting to current state (robust to missed events).
- **LimitRanger / ResourceQuota** — admission plugins enforcing defaults/limits and namespace quotas.
- **manifest** — a YAML/JSON file describing one or more objects.
- **MVCC** — multi-version concurrency control; etcd keeps versioned history by revision.
- **namespace** — virtual partition scoping names and policy.
- **node** — a worker machine running kubelet/kube-proxy/runtime.
- **Node Lease** — cheap node heartbeat object.
- **OCC (optimistic concurrency control)** — version-check-on-write instead of locking; yields `409 Conflict` on conflict.
- **OIDC** — OpenID Connect; an authn method for the apiserver.
- **operator** — a custom controller encoding operational knowledge for a CRD.
- **owner reference** — pointer from a child object to its parent (enables GC/cascading delete).
- **PodDisruptionBudget (PDB)** — limits voluntary disruptions to keep a minimum available.
- **Pod** — smallest deployable unit; one or more co-located containers.
- **predicates / priorities** — older names for scheduler Filter / Score phases.
- **preemption** — evicting lower-priority Pods to schedule a higher-priority one.
- **PriorityClass** — defines Pod priority for scheduling/preemption.
- **probe (liveness/readiness/startup)** — kubelet health checks.
- **Protobuf** — compact binary serialization used for core API objects in etcd.
- **quorum / majority** — more than half the members; needed to commit/elect in Raft.
- **Raft** — the consensus algorithm etcd uses.
- **RBAC** — Role-Based Access Control; the standard authz model.
- **reconciliation** — converging actual state to desired state.
- **Reflector / DeltaFIFO** — informer internals doing list/watch and queuing deltas.
- **ReplicaSet** — controller maintaining N identical Pods.
- **resourceVersion** — opaque cursor (etcd revision) for list/watch.
- **revision** — etcd's monotonically increasing version counter (MVCC).
- **scheduler (kube-scheduler)** — assigns unscheduled Pods to nodes.
- **Secret / ConfigMap** — sensitive / non-sensitive configuration objects.
- **Server-Side Apply (SSA)** — apiserver-side merge with field ownership tracking.
- **Service** — stable virtual IP/DNS load-balancing across selected Pods.
- **sidecar** — a helper container in the same Pod as the main app.
- **split-brain** — divergent state from a partition acting as two clusters; prevented by quorum.
- **static Pod** — a Pod the kubelet runs directly from a local manifest (used to bootstrap the control plane).
- **StatefulSet** — controller for stateful apps with stable identity/storage.
- **storage version** — the single API version objects are stored as in etcd.
- **syscall** — a request from user space into the kernel.
- **taint / toleration** — node repulsion mechanism and Pod opt-in.
- **topologySpreadConstraints** — rules to spread Pods across topology domains (zones/nodes).
- **ValidatingAdmissionPolicy** — in-process CEL-based admission validation.
- **watch** — streaming API of object change events.
- **watch cache** — apiserver in-memory cache backing many client watches from one etcd watch.
- **workqueue** — rate-limited, deduplicating queue feeding a controller's reconcile workers.

---

## 12. Cheat-sheet & self-test

### Dense recap (one screen)

**Components:** apiserver (only etcd client; authn→authz→mutate→validate→persist), etcd (Raft KV, source of truth), scheduler (binds Pods to nodes), controller-manager (reconcile loops), cloud-controller-manager (LB/routes/node), kubelet (runs Pods via CRI, reports status), kube-proxy (Service routing), runtime (containerd/CRI-O).

**Model:** declarative; `spec` = desired, `status` = actual; controllers = level-triggered reconcile loops; communicate only through apiserver; self-healing.

**etcd:** odd members (3 tol 1, 5 tol 2); quorum = ⌊N/2⌋+1; default quota ~2 GiB; needs fast SSD/low fsync; MVCC → compaction (discard history) + defrag (reclaim disk); back it up and test restores.

**apply flow:** kubectl → apiserver pipeline → etcd → Deployment ctrl → ReplicaSet → Pods (unscheduled) → scheduler binds → kubelet runs → status flows back → EndpointSlice → kube-proxy.

**Admission:** built-in plugins (`--enable-admission-plugins`) + dynamic webhooks (mutating then validating) + CEL `ValidatingAdmissionPolicy` (in-process, safer). Beware `failurePolicy: Fail` on broad resources.

**Watch/informer:** list-then-watch + local cache + workqueue; `resourceVersion` = opaque cursor; `410 Gone` → re-list; watch cache fans out one etcd watch to many clients.

**Scheduler:** Filter (feasibility) → Score (rank) → Bind; `percentageOfNodesToScore`; preemption via PriorityClass; taints/tolerations/affinity/topologySpread shape placement.

**HA:** ≥3 control-plane nodes; apiservers active-active behind LB; scheduler/controller-manager use leader election (Lease); kubelet/kube-proxy one-per-node.

**Key numbers:** etcd quota ~2GiB; webhook timeout default 10s (max 30s); kubelet max-pods 110; node-monitor-grace ~40s; node status update 10s; scheduler `progressDeadlineSeconds` 600s default; `revisionHistoryLimit` 10; SLO targets ~5,000 nodes / ~150k Pods (version-dependent).

**Decision rules:** external etcd for large/critical, stacked for simple; CEL policy over webhook unless you need mutation/external data; operator over Helm when day-2 logic is complex; least-privilege RBAC always.

**Survives etcd outage:** running Pods + existing routing. **Stops:** scheduling, healing, scaling, config changes.

**Debug order:** `describe` (Events) → `logs --previous` → cluster `events` → control-plane `/readyz`+etcd health+leases → metrics → audit logs.

### Self-test (no answers — recall these cold)

1. Trace `kubectl apply -f deploy.yaml` end to end, naming every component that acts and exactly when (and only) etcd is written. At what point do Pods first exist on a node?
2. Why are etcd clusters odd-sized? Compute the fault tolerance and quorum for 3, 4, and 5 members, and explain why 4 is pointless.
3. Explain level-triggered vs edge-triggered reconciliation and give a concrete failure that level-triggering survives but edge-triggering does not.
4. You deploy a `failurePolicy: Fail` validating webhook matching all Pods and its server crashes. What happens to the cluster, how do you diagnose it, and how do you recover and prevent it?
5. Describe the informer machinery (Reflector, DeltaFIFO, local cache, workqueue) and explain what a `410 Gone` on a watch means and how the client must respond.
6. The apiserver's p99 latency spikes with 429s. List the metrics you'd pull and how you'd distinguish "apiserver overloaded by a noisy client" from "etcd disk is slow," then give a fix for each.
7. Compare CRD+operator vs Helm chart for a stateful database, and justify a choice for a team running PostgreSQL with automated failover and backups.
8. What exactly survives an etcd quorum loss and what stops, and why do already-running Pods keep serving traffic?

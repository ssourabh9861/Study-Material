# Kubernetes & Containers — Storage

> A definitive engineering-handbook chapter for a senior Java/JVM backend developer who wants to fully master Kubernetes storage: from first principles to deep internals, production operations, and interview-grade fluency.

---

## 1. Overview & where it fits

### What "storage" means in Kubernetes

Kubernetes (K8s) is an orchestrator: it schedules containers (packaged as **Pods**) onto a fleet of machines (**Nodes**), restarts them when they die, moves them when nodes fail, and scales them up and down. That mobility is wonderful for *stateless* workloads — a web server can be killed and recreated anywhere. But the moment your workload needs to **remember something across restarts** (a database file, an uploaded user document, a write-ahead log, a cache that's expensive to rebuild), you collide with a hard truth: **a container's filesystem is ephemeral**. When a container restarts, its writable layer is wiped. When a Pod is rescheduled to another node, anything on the old node's local disk is gone.

Kubernetes **storage** is the entire subsystem that answers one question: *how does data outlive the container, the Pod, and even the node?* It spans:

- **Volumes** — a directory mounted into a Pod's containers, whose lifetime is tied to the Pod (some types) or to an external system (most useful types).
- **PersistentVolume (PV)** and **PersistentVolumeClaim (PVC)** — a decoupling abstraction that separates "I need 20 GiB of fast read-write storage" (the claim) from "here is an actual EBS volume / NFS export / Ceph RBD image" (the volume).
- **StorageClass** — a template that lets the cluster **dynamically provision** real storage on demand from a cloud or storage system, instead of an admin hand-creating volumes.
- **CSI (Container Storage Interface)** — the standardized plugin API that lets any storage vendor (AWS EBS, Google PD, Azure Disk, Ceph, Portworx, NetApp, etc.) integrate with Kubernetes without modifying Kubernetes itself.
- **StatefulSets** — a controller that gives each Pod a stable identity and **stable, per-Pod persistent storage**, which is the backbone of running databases on K8s.

### The problem it solves

Containers were designed to be cattle, not pets — interchangeable, disposable. Stateful systems are the opposite: the data *is* the value, and it must survive failures, migrations, and upgrades. Kubernetes storage bridges the gap by **externalizing durable data** out of the container filesystem and into volumes whose lifecycle you control independently of the Pod. It also provides a **portable, declarative contract**: you describe the storage you need (size, access mode, performance class) in YAML, and the cluster — through pluggable drivers — fulfills it, whether you're on AWS, GCP, Azure, bare metal, or your laptop.

### When you reach for it

- **Always**, even for stateless apps, because at minimum you mount **ConfigMaps and Secrets** as volumes, and you often need scratch space (`emptyDir`) for caches, temp files, or sidecar communication.
- **Persistent volumes** when a workload must keep data across Pod restarts/reschedules: databases (PostgreSQL, MySQL, Cassandra), message brokers (Kafka, RabbitMQ), search/indexes (Elasticsearch, Solr), object/artifact stores, or any JVM service that maintains local state (e.g., embedded RocksDB, Kafka Streams state stores, Lucene indexes).
- **Shared volumes (RWX)** when multiple Pods must read/write the same data (legacy apps expecting a shared filesystem, media processing, CI artifact caches).

### The one-paragraph mental model

> Think of a Pod as a short-lived process and storage as a **mountable, durable resource** living *outside* the Pod. A **PersistentVolumeClaim** is the Pod's *request slip* ("give me 20 GiB, fast, read-write-once"). A **StorageClass** is the *vending machine* that mints a real backing disk (a PersistentVolume) to satisfy that slip on demand. A **CSI driver** is the *hands* that actually create, attach, mount, snapshot, and delete the real disk in the underlying storage system. The Pod just sees a directory at a mount path; everything behind that directory — provisioning, attaching to the right node, formatting, mounting — is orchestrated by Kubernetes and the driver, in a defined sequence of steps you can observe and debug.

---

## 2. Foundations from first principles

We build up from the very bottom. Every adjacent term gets defined the first time it appears.

### 2.1 The container filesystem and why it's ephemeral

A **container** is a process (or group of processes) isolated using Linux kernel features:

- **Namespaces** — kernel feature that gives a process its own *view* of system resources (its own process tree, network interfaces, mount table, etc.). The **mount namespace** in particular gives a container its own filesystem view.
- **cgroups (control groups)** — kernel feature that *limits and accounts* a process's resource usage (CPU, memory, I/O).
- **Union/overlay filesystem** — the container image is a stack of read-only layers; the running container gets a thin **writable layer** on top (typically via **OverlayFS**, a Linux union filesystem that presents multiple directories as one, with writes going to an "upper" layer). 

When the container exits, that writable upper layer is discarded by default. So: **anything a process writes to its own filesystem is lost on restart** unless it was written into a *volume* mounted from outside the container's image layers. This is the root motivation for everything in this chapter.

> **Term: Pod.** The smallest deployable unit in Kubernetes. A Pod is one or more containers that share the same network namespace (same IP/port space) and can share storage volumes. Containers in a Pod are co-scheduled on the same node. A Pod is itself ephemeral — it has a lifecycle and can be deleted/recreated.

> **Term: Node.** A worker machine (VM or physical) in the cluster that runs Pods. The component on each node that manages Pods and their containers is the **kubelet**.

> **Term: kubelet.** The per-node agent that talks to the control plane, ensures the containers described in Pod specs are running and healthy, and — critically for us — is responsible for **mounting volumes** into Pods.

### 2.2 Volumes: the base abstraction

A **Volume** in Kubernetes is a directory, possibly backed by some medium, that is **accessible to the containers in a Pod**. You declare volumes in the Pod spec under `spec.volumes`, and you mount them into specific containers via `spec.containers[].volumeMounts`.

Two things to internalize immediately:

1. **A volume is defined at the Pod level and mounted at the container level.** Multiple containers in the same Pod can mount the same volume (this is how sidecars share files).
2. **A volume's lifetime depends on its type.** Some volume types live and die with the Pod; others reference external storage that outlives the Pod entirely.

#### Ephemeral volume types (live and die with the Pod)

- **`emptyDir`** — an empty directory created when the Pod is assigned to a node, and **deleted permanently when the Pod is removed** from the node. It survives *container* restarts within the Pod but not Pod deletion/rescheduling. Backed by the node's disk by default, or by RAM (`tmpfs`, an in-memory filesystem) if you set `medium: Memory`. Uses: scratch space, sidecar-to-main file sharing, caches, checkpoint dirs.
- **`configMap` / `secret`** — mount configuration data or secrets as files. Backed by `tmpfs`. Read-only data injected by the control plane.
- **`downwardAPI`** — exposes Pod metadata (labels, annotations, resource limits) as files.
- **`projected`** — combines several sources (secret, configMap, downwardAPI, serviceAccountToken) into one directory.

> **Term: ConfigMap.** A Kubernetes object holding non-secret key/value configuration data. Can be consumed as environment variables or mounted as files.
> **Term: Secret.** Like a ConfigMap but for sensitive data (passwords, tokens, TLS keys). Base64-encoded at rest in etcd (NOT encrypted by default — see security section).

#### Persistent volume types (outlive the Pod)

These reference storage that exists independently of any Pod:

- **Cloud block storage:** AWS EBS, GCP Persistent Disk, Azure Disk — network-attached block devices.
- **Network/shared filesystems:** NFS, AWS EFS, Azure Files, CephFS, GlusterFS.
- **Distributed block:** Ceph RBD, Portworx, Longhorn, OpenEBS.
- **`local`** — a disk physically attached to a specific node (fast, but ties the Pod to that node).
- **`hostPath`** — mounts a file/dir from the node's filesystem directly. Dangerous in production (breaks portability and isolation); mainly for single-node dev/testing or privileged system Pods.

> **Important historical note:** Most of these used to be **in-tree** plugins — code compiled directly into Kubernetes itself (e.g., `awsElasticBlockStore`, `gcePersistentDisk`). Kubernetes has **migrated almost all of these to CSI** (covered in §3.6 and §7). As of modern Kubernetes (1.27+), in-tree cloud volume plugins are removed or deprecated; you use the CSI driver instead. We'll flag version specifics as we go.

### 2.3 Block vs file vs object storage (know the difference)

- **Block storage** — exposes a raw block device (like a virtual hard disk). You format it with a filesystem (ext4, xfs) and mount it. Generally **attachable to one node at a time** (hence ReadWriteOnce). Examples: EBS, GCP PD, Azure Disk, Ceph RBD. Best latency for databases.
- **File storage** — a shared filesystem you mount over the network, supporting **concurrent access from many nodes** (ReadWriteMany). Examples: NFS, EFS, Azure Files, CephFS. Higher latency, but shareable.
- **Object storage** — HTTP API for blobs (PUT/GET by key), no POSIX filesystem semantics. Examples: S3, GCS, Azure Blob. **Not normally mounted as a Kubernetes volume** (though CSI drivers like `s3-csi` exist via FUSE). Apps usually talk to it via SDK. Mentioned for completeness; the rest of this chapter focuses on block and file.

> **Term: POSIX semantics.** The traditional Unix filesystem behavior — files, directories, byte-level random reads/writes, `fsync`, file locking, permissions. Block and file storage provide it; object storage does not.

### 2.4 The PV / PVC decoupling

The core design principle of persistent storage in Kubernetes is **separation of concerns** between two roles:

- **Cluster administrator** — provisions and manages real storage (the *supply*).
- **Application developer** — requests storage by characteristics, without knowing the underlying tech (the *demand*).

This is implemented as two objects:

- **PersistentVolume (PV)** — a **cluster-scoped** resource representing a *piece of real storage* (an EBS volume, an NFS export, etc.). It has a capacity, access modes, a reclaim policy, and a reference to the actual backend. PVs are not namespaced; they belong to the whole cluster.
- **PersistentVolumeClaim (PVC)** — a **namespaced** resource representing a *request* for storage by a user/Pod: "I want X capacity with these access modes from this StorageClass." A Pod references a PVC, not a PV directly.

Kubernetes **binds** a PVC to a suitable PV (one-to-one, exclusive). The Pod mounts the PVC; the PVC is bound to a PV; the PV points at the real storage. This indirection is what makes manifests portable: the same Deployment YAML referencing `claimName: my-data` works on AWS, GCP, or bare metal — only the StorageClass/PV differs.

```
Pod  ──references──▶  PVC  ──bound 1:1──▶  PV  ──points at──▶  Real storage (EBS/NFS/Ceph…)
(namespaced)         (namespaced)          (cluster-scoped)
```

### 2.5 Static vs dynamic provisioning

- **Static provisioning** — an admin manually creates PV objects ahead of time (each backed by a pre-created disk/export). PVCs then bind to whichever existing PV matches. Tedious; used for pre-existing storage (e.g., a specific NFS share) or when you want tight control.
- **Dynamic provisioning** — the cluster **creates the real storage and the PV automatically** when a PVC is created, using a **StorageClass**. This is the modern default for cloud-native workloads: the developer creates a PVC, and a backing disk springs into existence.

> **Term: StorageClass (SC).** A cluster-scoped template describing *how* to dynamically provision storage of a particular kind: which **provisioner** (CSI driver) to use, what parameters (disk type, IOPS, filesystem), the reclaim policy, the volume binding mode, and whether volumes are expandable. A cluster can have many StorageClasses (e.g., `gp3`, `io2`, `standard`, `fast-ssd`). One can be marked **default** so PVCs without an explicit class still get provisioned.

### 2.6 Access modes (RWO / ROX / RWX / RWOP)

An access mode describes how a volume can be mounted **with respect to nodes** (not Pods — this is the #1 misconception):

| Mode | Short | Meaning (per the spec) |
|---|---|---|
| `ReadWriteOnce` | **RWO** | Mounted read-write by **a single node**. Multiple Pods *on that same node* can use it. |
| `ReadOnlyMany` | **ROX** | Mounted read-only by **many nodes** simultaneously. |
| `ReadWriteMany` | **RWX** | Mounted read-write by **many nodes** simultaneously. |
| `ReadWriteOncePod` | **RWOP** | Mounted read-write by **a single Pod** (the whole cluster). Stable since K8s 1.29. Enforces exclusivity at the Pod level. |

Critical nuances:
- The unit for RWO/ROX/RWX is the **node**, not the Pod. RWO means "one node," so two Pods scheduled to the *same* node can both mount an RWO volume — but a Pod on a *different* node cannot.
- **Block storage (EBS/PD/Azure Disk) is fundamentally RWO** — a cloud block device attaches to one VM at a time. You cannot get RWX from raw EBS.
- **RWX requires a shared filesystem** (NFS, EFS, CephFS, Azure Files) or a clustered/distributed storage system.
- **RWOP** exists because RWO's node-level semantics surprised people; if you truly need single-writer exclusivity, use RWOP.

### 2.7 Reclaim policies

When a PVC is deleted, what happens to the underlying PV and real storage? The **reclaim policy** (set on the PV, often inherited from the StorageClass) decides:

| Policy | Behavior |
|---|---|
| `Delete` | The PV **and the underlying real storage** are deleted automatically. Default for dynamically provisioned volumes. Convenient but dangerous (data loss). |
| `Retain` | The PV is **kept** (status `Released`) and the underlying storage is **not** deleted. An admin must manually clean up / re-provision. Safest for important data. |
| `Recycle` | **Deprecated/removed.** Used to scrub data (`rm -rf`) and make the PV available again. Don't use. |

### 2.8 The CSI model (one paragraph for now, deep dive in §3.6)

> **Term: CSI (Container Storage Interface).** An industry-standard gRPC API (also used by Mesos, Nomad, Cloud Foundry) that defines how container orchestrators talk to storage systems. A **CSI driver** is a vendor-supplied program implementing this API. Kubernetes ships small "sidecar" controllers (external-provisioner, external-attacher, external-resizer, external-snapshotter) that translate Kubernetes events into CSI gRPC calls to the driver. This is why new storage backends can be added **without recompiling Kubernetes** — they just ship a CSI driver and register it via a `CSIDriver` object.

### 2.9 StatefulSet vs Deployment (storage angle)

- **Deployment** — manages a set of *identical, interchangeable* Pods (stateless). If you attach a PVC, **all replicas share the same PVC** (which only works for RWX or a single replica). Pod names are random.
- **StatefulSet** — manages Pods with **stable identities** (`app-0`, `app-1`, …), stable network names, ordered startup/shutdown, and — via **`volumeClaimTemplates`** — **a separate, dedicated PVC per Pod**. `app-0` gets `data-app-0`, `app-1` gets `data-app-1`, and each keeps its own volume across reschedules. This is the foundation for running databases.

We'll return to all of these in depth. Now to the heart of the chapter.

---

## 3. How it works internally

This section traces, step by step, what actually happens under the hood — the control flow, data flow, and state machines. We'll cover: the PV/PVC binding lifecycle, dynamic provisioning, the attach/mount pipeline, volume binding modes (topology), the CSI architecture and its gRPC calls, StatefulSet volume orchestration, expansion, and snapshots.

### 3.1 The Kubernetes control loop background you need

> **Term: Control plane.** The set of components that manage cluster state: the **API server** (the front door; everything goes through it), **etcd** (the consistent key-value store that is the source of truth), the **scheduler** (decides which node a Pod runs on), and **controllers** (loops that drive actual state toward desired state).

> **Term: etcd.** A distributed, strongly-consistent key-value store using the **Raft consensus algorithm** (a protocol where a leader replicates a log to followers and commits an entry once a majority acknowledge it, guaranteeing all nodes agree on order). etcd holds every Kubernetes object. The desired state of your PVs/PVCs lives here.

> **Term: Controller / reconcile loop.** A control loop that watches objects via the API server, compares *desired* vs *actual* state, and takes action to converge them. Storage involves several controllers: the **PersistentVolume controller** (binding/recycling), CSI sidecars (provisioning/attaching), and the **AttachDetachController** in `kube-controller-manager`.

> **Term: Reconciliation.** The continuous "observe → diff → act" cycle controllers run. It's level-triggered (acts on current state), not edge-triggered, so it self-heals after missed events.

### 3.2 PV/PVC binding lifecycle (the state machine)

A PV moves through phases (`.status.phase`):

```
Available ──▶ Bound ──▶ Released ──▶ (Failed)
   ▲                        │
   └──── (Retain: stays Released; admin recycles manually)
```

- **Available** — a free PV not yet claimed.
- **Bound** — exclusively bound to a PVC.
- **Released** — the PVC was deleted but the storage hasn't been reclaimed (typical with `Retain`).
- **Failed** — automatic reclamation failed.

A PVC has phases: **Pending** (no matching PV / not yet provisioned) → **Bound**.

**Binding algorithm (static provisioning):** The PV controller's `findBestMatchForClaim` looks for a PV that satisfies the PVC's requirements, preferring the **smallest PV that is large enough**:
1. **StorageClass must match** (`storageClassName` equal, or both empty).
2. **Access modes** must be a superset of the requested modes.
3. **Capacity** must be ≥ requested.
4. **Selector** (optional `matchLabels`/`matchExpressions` on the PVC) must match the PV's labels.
5. **VolumeName** — if the PVC names a specific PV, it binds to exactly that one.
6. Among candidates, pick the smallest sufficient one to minimize waste.

Binding is **bidirectional and atomic**: the PVC's `.spec.volumeName` is set to the PV, and the PV's `.spec.claimRef` is set to the PVC. The 1:1 exclusivity is enforced by `claimRef`.

### 3.3 Dynamic provisioning — full step-by-step

When a PVC requests a StorageClass and no matching PV exists, dynamic provisioning kicks in. With CSI, the flow is:

1. **User creates a PVC** referencing a StorageClass (explicitly or via the default). PVC is `Pending`.
2. **The external-provisioner sidecar** (running alongside the CSI controller) watches PVCs. It sees a Pending PVC whose StorageClass names *its* provisioner (e.g., `ebs.csi.aws.com`).
3. **VolumeBindingMode check** (see §3.5):
   - If `Immediate`: provision now.
   - If `WaitForFirstConsumer`: **do nothing yet** — wait until a Pod using the PVC is scheduled, so the volume can be created in the right topology (zone). The scheduler informs the binding.
4. **external-provisioner calls `CreateVolume`** (CSI gRPC) on the driver's **controller service**, passing capacity, parameters (from the StorageClass), and topology requirements. The driver creates the real storage (e.g., calls AWS `CreateVolume`) and returns a **volume handle** (the cloud volume ID).
5. **external-provisioner creates a PV object** representing that storage, with the correct `claimRef`, capacity, access modes, reclaim policy, and CSI source (driver name + volume handle).
6. **The PV controller binds** the PVC to the new PV. PVC → `Bound`.
7. Later, when the Pod is scheduled to a node, the **attach/mount pipeline** (§3.4) runs.

If `WaitForFirstConsumer`, steps 4–6 happen *after* the Pod is scheduled, and the volume is created in the chosen node's zone.

### 3.4 The attach → mount pipeline (what kubelet & controllers do)

Once a Pod that uses a bound PVC is scheduled to a node, three distinct phases occur. This is the part people most often get confused about.

**Phase A — Attach (controller-side, optional per driver):**
- The **AttachDetachController** (in kube-controller-manager) sees that a Pod on node N needs a volume. It creates a **`VolumeAttachment`** object (the desired state: "volume X should be attached to node N").
- The **external-attacher** sidecar watches VolumeAttachment objects and calls **`ControllerPublishVolume`** (CSI) on the driver's controller service. For EBS, this performs the cloud "attach volume to instance" operation, making the block device appear at the OS level on node N (e.g., `/dev/xvdba`).
- Not all drivers need attach (NFS, for example, has no attach step — `ATTACH_REQUIRED` capability is false). For those, this phase is skipped.

> **Term: VolumeAttachment.** A cluster-scoped Kubernetes object that records the intent and status of attaching a specific volume to a specific node. Bridges the controller's decision and the attacher's action.

**Phase B — Stage / NodeStageVolume (node-side, global mount):**
- The kubelet's **VolumeManager** notices the Pod needs the volume and the attach succeeded.
- It calls the CSI driver's **node service** `NodeStageVolume`, which **formats** the device if needed (e.g., mkfs.ext4) and mounts it once at a **global, node-level staging path** (e.g., `/var/lib/kubelet/plugins/kubernetes.io/csi/pv/<pv>/globalmount`). This "stage once per node" step exists so that if multiple Pods on the node use the same volume, the filesystem is mounted only once.

**Phase C — Publish / NodePublishVolume (node-side, into the Pod):**
- The kubelet calls **`NodePublishVolume`**, which **bind-mounts** the staged path into the Pod's specific directory (`/var/lib/kubelet/pods/<pod-uid>/volumes/...`), which is then exposed inside the container at your `mountPath`.

> **Term: bind mount.** A Linux mechanism to make an existing directory appear at another path; both paths refer to the same underlying files. Used to project the staged volume into each Pod.

**Tear-down is the reverse:** `NodeUnpublishVolume` → `NodeUnstageVolume` → (`ControllerUnpublishVolume` = detach). The kubelet's **reconciler** loop continuously compares the "desired state of world" (volumes Pods need) with the "actual state of world" (volumes mounted) and drives mount/unmount accordingly.

Summary of the CSI calls in order for a typical attachable driver:

```
CreateVolume (controller)            ← provisioning
  ↓
ControllerPublishVolume (controller) ← attach to node
  ↓
NodeStageVolume (node)               ← format + mount to global staging path
  ↓
NodePublishVolume (node)             ← bind-mount into the Pod
  … Pod runs …
NodeUnpublishVolume (node)
  ↓
NodeUnstageVolume (node)
  ↓
ControllerUnpublishVolume (controller) ← detach
  ↓
DeleteVolume (controller)            ← on PVC delete if reclaimPolicy=Delete
```

### 3.5 Volume binding modes and topology (a subtle but vital concept)

A StorageClass has a **`volumeBindingMode`**:

- **`Immediate`** (default if unset) — the PV is provisioned/bound **as soon as the PVC is created**, *before* any Pod is scheduled. Problem: on a multi-zone cluster, the volume might be created in zone `us-east-1a` while the only nodes with spare capacity for the Pod are in `us-east-1b`. Block volumes can't cross zones → the Pod is unschedulable. This is the classic "volume node affinity conflict."
- **`WaitForFirstConsumer` (WFFC)** — provisioning/binding is **delayed until a Pod that uses the PVC is being scheduled**. The scheduler picks a node considering all constraints, then the volume is provisioned in that node's zone. This is the **recommended setting** for zonal block storage in multi-AZ clusters.

> **Term: Topology / topology-aware provisioning.** Storage often has a locality constraint (a zone/region/rack). Kubernetes labels nodes with topology keys (e.g., `topology.kubernetes.io/zone`). With WFFC, the CSI driver receives topology requirements in `CreateVolume` and the PV gets a **`nodeAffinity`** that pins it to compatible nodes. This is how K8s keeps a Pod and its block volume co-located in the same zone.

> **Term: Availability Zone (AZ).** An isolated datacenter location within a cloud region. Block volumes are typically zonal — they exist in and attach only within one AZ.

### 3.6 The CSI architecture in depth

CSI defines three gRPC services a driver can implement:

1. **Identity service** — `GetPluginInfo`, `GetPluginCapabilities`, `Probe`. Health & metadata.
2. **Controller service** — runs centrally (usually a Deployment). Implements `CreateVolume`, `DeleteVolume`, `ControllerPublishVolume` (attach), `ControllerUnpublishVolume` (detach), `CreateSnapshot`, `DeleteSnapshot`, `ControllerExpandVolume`, `ListVolumes`, etc. Capabilities are advertised so K8s knows what the driver supports.
3. **Node service** — runs on **every node** (a DaemonSet). Implements `NodeStageVolume`, `NodeUnstageVolume`, `NodePublishVolume`, `NodeUnpublishVolume`, `NodeGetVolumeStats`, `NodeExpandVolume`, `NodeGetInfo` (reports node ID + topology).

> **Term: DaemonSet.** A controller that runs exactly one copy of a Pod on every (or selected) node. The CSI node plugin is a DaemonSet because mounting must happen locally on each node.

**Kubernetes-side "sidecar" containers** (maintained by the Kubernetes storage SIG) translate K8s API events into CSI calls so vendors only implement the gRPC services:

| Sidecar | Watches | Calls (CSI) | Purpose |
|---|---|---|---|
| **external-provisioner** | PVCs | `CreateVolume` / `DeleteVolume` | Dynamic provisioning; creates PV objects |
| **external-attacher** | VolumeAttachment | `ControllerPublishVolume` / `ControllerUnpublishVolume` | Attach/detach |
| **external-resizer** | PVCs (size change) | `ControllerExpandVolume` | Online/offline expansion |
| **external-snapshotter** | VolumeSnapshot | `CreateSnapshot` / `DeleteSnapshot` | Snapshots |
| **node-driver-registrar** | (local) | registers via kubelet plugin registration | Tells kubelet about the node plugin |
| **livenessprobe** | (local) | `Probe` | Health checking |

> **Term: gRPC.** A high-performance RPC framework using Protocol Buffers over HTTP/2. CSI is defined as gRPC services over a Unix domain socket between the sidecars and the driver.

> **Term: CSIDriver object.** A cluster-scoped K8s resource that registers a driver's name and behaviors: `attachRequired` (does it need the attach phase?), `podInfoOnMount` (pass Pod info to NodePublish?), `fsGroupPolicy` (how to apply group ownership), `volumeLifecycleModes` (Persistent vs Ephemeral), `storageCapacity` (does it report capacity?), `requiresRepublish`. Kubernetes reads this to decide which steps to run.

> **Term: CSINode object.** Per-node resource listing which CSI drivers are installed on that node and their node-specific topology/IDs. The scheduler consults it.

### 3.7 StatefulSet volume orchestration

A StatefulSet has a `volumeClaimTemplates` array. For each template and each ordinal replica, the StatefulSet controller creates a PVC named `<template>-<statefulset>-<ordinal>`:

- StatefulSet `kafka` with template `data` and 3 replicas →  PVCs `data-kafka-0`, `data-kafka-1`, `data-kafka-2`.
- Each PVC dynamically provisions its own PV. Pod `kafka-0` always mounts `data-kafka-0`, even after rescheduling to another node (assuming RWX, networked block in same zone, or topology-aware binding).
- **Pods are created/deleted in order** (0, then 1, then 2 for scale-up; reverse for scale-down) by default (`podManagementPolicy: OrderedReady`); `Parallel` relaxes this.
- **Crucially: deleting the StatefulSet does NOT delete the PVCs by default.** The volumes (and data) persist so you can recreate the StatefulSet and reattach. Since K8s 1.27+ (stable), `persistentVolumeClaimRetentionPolicy` lets you opt into deleting PVCs on `whenDeleted` and/or `whenScaled`.

> **Term: Headless Service.** A Service with `clusterIP: None`. StatefulSets use one to give each Pod a stable DNS name (`kafka-0.kafka.namespace.svc`). Not storage per se, but part of the stable-identity story.

### 3.8 Volume expansion (resize) internals

If a StorageClass has `allowVolumeExpansion: true`, you can grow a PVC by editing its `spec.resources.requests.storage` to a larger value. Internally:
1. **external-resizer** sees the larger request and calls **`ControllerExpandVolume`** → the backend grows the block device (e.g., AWS modifies the EBS volume).
2. If the filesystem needs growing on the node, the PVC gets condition `FileSystemResizePending`; the kubelet calls **`NodeExpandVolume`** (often on next mount or online) to run `resize2fs`/`xfs_growfs`.
3. **Shrinking is not supported.** You can only grow.

### 3.9 Volume snapshots internals

> **Term: VolumeSnapshot / VolumeSnapshotClass / VolumeSnapshotContent.** Snapshot analogues of PVC/StorageClass/PV. A `VolumeSnapshot` (namespaced) requests a point-in-time copy of a PVC; a `VolumeSnapshotClass` (cluster-scoped) defines how (which driver, parameters); a `VolumeSnapshotContent` (cluster-scoped) is the actual snapshot in the backend. These are CSI features, served by the **external-snapshotter** sidecar and a separate **snapshot-controller**. You can then create a new PVC `dataSource: <snapshot>` to **restore** (clone) it.

Flow: create `VolumeSnapshot` → snapshot-controller creates `VolumeSnapshotContent` → external-snapshotter calls `CreateSnapshot` on the driver → backend creates snapshot → status becomes `readyToUse: true`. Restore: new PVC with `spec.dataSource` pointing at the snapshot → provisioner calls `CreateVolume` with the snapshot as source.

---

## 4. The complete toolkit

### 4.1 Core API objects (resources)

| Object | Scope | Purpose | Key fields |
|---|---|---|---|
| `Pod.spec.volumes` | namespaced | Declare volumes available to the Pod | `name`, type (`emptyDir`, `persistentVolumeClaim`, `configMap`, …) |
| `Pod.spec.containers[].volumeMounts` | — | Mount a volume into a container | `name`, `mountPath`, `subPath`, `readOnly`, `mountPropagation` |
| `PersistentVolume` (PV) | cluster | A piece of real storage | `capacity.storage`, `accessModes`, `persistentVolumeReclaimPolicy`, `storageClassName`, `csi`/`nfs`/`local`, `nodeAffinity`, `volumeMode`, `claimRef` |
| `PersistentVolumeClaim` (PVC) | namespaced | A request for storage | `accessModes`, `resources.requests.storage`, `storageClassName`, `volumeMode`, `selector`, `dataSource`, `volumeName` |
| `StorageClass` (SC) | cluster | Dynamic provisioning template | `provisioner`, `parameters`, `reclaimPolicy`, `volumeBindingMode`, `allowVolumeExpansion`, `allowedTopologies`, `mountOptions` |
| `VolumeAttachment` | cluster | Attach intent/status | `spec.attacher`, `spec.nodeName`, `spec.source`, `status.attached` |
| `CSIDriver` | cluster | Driver registration/behavior | `attachRequired`, `podInfoOnMount`, `fsGroupPolicy`, `volumeLifecycleModes`, `storageCapacity` |
| `CSINode` | cluster | Per-node driver/topology info | `drivers[].name`, `nodeID`, `topologyKeys` |
| `VolumeSnapshot` | namespaced | Request a snapshot | `source.persistentVolumeClaimName`, `volumeSnapshotClassName` |
| `VolumeSnapshotClass` | cluster | Snapshot template | `driver`, `deletionPolicy`, `parameters` |
| `VolumeSnapshotContent` | cluster | The actual snapshot | `driver`, `source`, `volumeSnapshotRef` |
| `CSIStorageCapacity` | namespaced | Reported capacity per topology | used by scheduler with `storageCapacity: true` |

### 4.2 `volumeMode`: Filesystem vs Block

| Value | Meaning |
|---|---|
| `Filesystem` (default) | Volume is formatted and mounted as a directory. |
| `Block` | The **raw block device** is exposed to the container at a `devicePath` (no filesystem). For apps that manage their own storage (some databases, Ceph). |

### 4.3 StorageClass parameters — common provisioners

Parameters are **provisioner-specific**. Examples (verify against your driver's docs — these are version/vendor-specific):

**AWS EBS CSI (`ebs.csi.aws.com`):**

| Parameter | Purpose | Example / default |
|---|---|---|
| `type` | EBS volume type | `gp3` (recommended), `gp2`, `io1`, `io2`, `st1`, `sc1` |
| `iops` | Provisioned IOPS | e.g. `3000` (gp3 default), up to `16000` |
| `throughput` | gp3 throughput MiB/s | `125` default, up to `1000` |
| `encrypted` | Encrypt at rest | `"true"` |
| `kmsKeyId` | KMS key ARN | (optional) |
| `fsType` | Filesystem | `ext4` (default) / `xfs` |

**GCP PD CSI (`pd.csi.storage.gke.io`):** `type` (`pd-balanced`, `pd-ssd`, `pd-standard`, `hyperdisk-balanced`), `replication-type` (`none`/`regional-pd`).

**Azure Disk CSI (`disk.csi.azure.com`):** `skuName` (`Premium_LRS`, `StandardSSD_LRS`, `UltraSSD_LRS`), `cachingMode`.

### 4.4 `kubectl` commands (the operational toolkit)

| Command | What it does |
|---|---|
| `kubectl get pv` | List PersistentVolumes (and their STATUS/CLAIM) |
| `kubectl get pvc -n NS` | List claims and their bound PV/capacity |
| `kubectl get sc` | List StorageClasses (default marked with `(default)`) |
| `kubectl describe pvc NAME` | Events/why a PVC is Pending (provisioning errors) |
| `kubectl describe pv NAME` | Backend details, node affinity, reclaim policy |
| `kubectl get volumeattachment` | See what's attached where |
| `kubectl get csidrivers` / `csinodes` | Installed drivers / per-node info |
| `kubectl get volumesnapshot -n NS` | Snapshots and `readyToUse` |
| `kubectl patch pvc NAME -p '{"spec":{"resources":{"requests":{"storage":"50Gi"}}}}'` | Trigger expansion |
| `kubectl patch storageclass NAME -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'` | Make default |
| `kubectl exec POD -- df -h /path` | Verify mount/size inside the Pod |
| `kubectl get events --field-selector involvedObject.kind=PersistentVolumeClaim` | Storage events |

### 4.5 Annotations & feature flags worth knowing

- `volume.beta.kubernetes.io/storage-provisioner` / `volume.kubernetes.io/storage-provisioner` — set by K8s on a PVC to record which provisioner owns it.
- `pv.kubernetes.io/bound-by-controller`, `pv.kubernetes.io/provisioned-by` — provenance annotations.
- `storageclass.kubernetes.io/is-default-class: "true"` — marks the default SC.
- Feature gates (mostly GA now, but for older clusters): `CSIMigration*`, `ReadWriteOncePod`, `VolumeSnapshotDataSource`, `StatefulSetAutoDeletePVC`.

---

## 5. Code examples by use case

All YAML is illustrative; adapt provisioner names/parameters to your platform and Kubernetes version.

### 5.1 Use case: scratch space and sidecar sharing with `emptyDir`

A Java app writes temp files; a sidecar tails/ships them. They share an `emptyDir`.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: app-with-sidecar
spec:
  volumes:
    - name: scratch
      emptyDir:
        sizeLimit: 1Gi          # cap node-disk usage; Pod evicted if exceeded
    - name: ramcache
      emptyDir:
        medium: Memory          # tmpfs — counts against the Pod's memory limit!
        sizeLimit: 256Mi
  containers:
    - name: app
      image: my-java-app:1.0
      env:
        - name: JAVA_TOOL_OPTIONS
          value: "-Djava.io.tmpdir=/scratch"   # point JVM temp dir at the volume
      volumeMounts:
        - name: scratch
          mountPath: /scratch
        - name: ramcache
          mountPath: /ramcache
    - name: log-shipper
      image: fluent/fluent-bit:latest
      volumeMounts:
        - name: scratch
          mountPath: /scratch
          readOnly: true          # sidecar only reads
```

Notes that matter:
- `medium: Memory` uses RAM (`tmpfs`); fast, but **counts toward the Pod's memory limit** and is lost on Pod removal. Don't put a database here.
- `sizeLimit` on a disk-backed `emptyDir` triggers **eviction** if exceeded (protects the node).
- `emptyDir` survives container restarts but **not Pod rescheduling**.

### 5.2 Use case: a basic dynamically-provisioned PVC for a single-replica app

```yaml
# StorageClass (often pre-installed by your cloud; shown for clarity)
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: fast-gp3
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  iops: "3000"
  throughput: "250"
  encrypted: "true"
reclaimPolicy: Delete
allowVolumeExpansion: true
volumeBindingMode: WaitForFirstConsumer   # zone-aware; avoids unschedulable Pods
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: app-data
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: fast-gp3
  resources:
    requests:
      storage: 20Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: single-writer-app
spec:
  replicas: 1                  # >1 would conflict: RWO + shared PVC across nodes
  selector: { matchLabels: { app: sw } }
  template:
    metadata: { labels: { app: sw } }
    spec:
      containers:
        - name: app
          image: my-java-app:1.0
          volumeMounts:
            - name: data
              mountPath: /var/lib/app
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: app-data
```

### 5.3 Use case: StatefulSet with per-Pod storage (running PostgreSQL/Kafka pattern)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: pg            # headless service for stable DNS
spec:
  clusterIP: None
  selector: { app: pg }
  ports: [{ port: 5432, name: pg }]
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: pg
spec:
  serviceName: pg
  replicas: 3
  podManagementPolicy: OrderedReady
  persistentVolumeClaimRetentionPolicy:   # K8s 1.27+ stable
    whenDeleted: Retain                   # keep data if StatefulSet deleted
    whenScaled: Retain                    # keep data when scaling down
  selector: { matchLabels: { app: pg } }
  template:
    metadata: { labels: { app: pg } }
    spec:
      terminationGracePeriodSeconds: 60   # let the DB flush/checkpoint
      securityContext:
        fsGroup: 999                      # chown the volume so the DB user can write
      containers:
        - name: postgres
          image: postgres:16
          ports: [{ containerPort: 5432 }]
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
              subPath: pgdata             # avoid lost+found at mount root
          readinessProbe:
            exec: { command: ["pg_isready","-U","postgres"] }
            initialDelaySeconds: 10
  volumeClaimTemplates:                   # ONE PVC PER POD
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: fast-gp3
        resources:
          requests:
            storage: 50Gi
```

This creates PVCs `data-pg-0`, `data-pg-1`, `data-pg-2`, each a dedicated 50Gi EBS volume that follows its Pod identity. `subPath: pgdata` keeps Postgres data in a subdirectory so the ext4 `lost+found` directory at the mount root doesn't trip up the init check. `fsGroup` makes the kernel set group ownership of the volume so a non-root DB user can write.

### 5.4 Use case: shared ReadWriteMany volume across Pods (NFS/EFS)

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: efs-shared
provisioner: efs.csi.aws.com
parameters:
  provisioningMode: efs-ap          # access-point per volume
  fileSystemId: fs-0123456789abcdef
  directoryPerms: "700"
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: shared-assets
spec:
  accessModes: ["ReadWriteMany"]    # RWX: many nodes, many Pods, read-write
  storageClassName: efs-shared
  resources:
    requests:
      storage: 100Gi                # EFS is elastic; size is largely nominal
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-cluster
spec:
  replicas: 5                       # all 5 share the same RWX volume
  selector: { matchLabels: { app: web } }
  template:
    metadata: { labels: { app: web } }
    spec:
      containers:
        - name: web
          image: my-java-web:1.0
          volumeMounts:
            - name: assets
              mountPath: /srv/assets
      volumes:
        - name: assets
          persistentVolumeClaim:
            claimName: shared-assets
```

Use RWX only when the application is **designed for concurrent writers** (or each Pod writes to disjoint paths). Most relational databases must NOT share a filesystem across instances — they assume exclusive access and will corrupt data.

### 5.5 Use case: statically provisioned PV for an existing NFS export

```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: legacy-nfs
spec:
  capacity: { storage: 500Gi }
  accessModes: ["ReadWriteMany"]
  persistentVolumeReclaimPolicy: Retain     # never auto-delete the NFS data
  storageClassName: ""                      # empty so only matching PVCs bind
  mountOptions: ["nfsvers=4.1","hard","timeo=600","retrans=2"]
  nfs:
    server: 10.0.0.50
    path: /exports/shared
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: legacy-claim
spec:
  accessModes: ["ReadWriteMany"]
  storageClassName: ""                      # bind to the static PV above
  resources: { requests: { storage: 500Gi } }
  volumeName: legacy-nfs                     # pin to this exact PV
```

### 5.6 Use case: local persistent volume for low-latency, high-throughput workloads

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: local-nvme
provisioner: kubernetes.io/no-provisioner   # local PVs are NOT dynamically provisioned
volumeBindingMode: WaitForFirstConsumer
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: local-pv-node1
spec:
  capacity: { storage: 1Ti }
  accessModes: ["ReadWriteOnce"]
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-nvme
  local:
    path: /mnt/disks/nvme0                   # a pre-mounted local disk on the node
  nodeAffinity:                              # MANDATORY: pins PV to its node
    required:
      nodeSelectorTerms:
        - matchExpressions:
            - key: kubernetes.io/hostname
              operator: In
              values: ["node1"]
```

Local PVs give you raw disk speed but **no data mobility** — if `node1` dies, the data is stranded. Use them only when the app replicates data itself (Cassandra, Kafka, distributed databases).

### 5.7 Use case: snapshot and restore

```yaml
apiVersion: snapshot.storage.k8s.io/v1
kind: VolumeSnapshotClass
metadata:
  name: ebs-snap
driver: ebs.csi.aws.com
deletionPolicy: Delete
---
apiVersion: snapshot.storage.k8s.io/v1
kind: VolumeSnapshot
metadata:
  name: pg0-backup-2026-06-24
spec:
  volumeSnapshotClassName: ebs-snap
  source:
    persistentVolumeClaimName: data-pg-0
---
# Restore: a new PVC seeded from the snapshot
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: data-pg-0-restored
spec:
  storageClassName: fast-gp3
  dataSource:
    name: pg0-backup-2026-06-24
    kind: VolumeSnapshot
    apiGroup: snapshot.storage.k8s.io
  accessModes: ["ReadWriteOnce"]
  resources: { requests: { storage: 50Gi } }   # must be >= snapshot size
```

Snapshots are **crash-consistent** by default (like pulling the power) unless you quiesce the app. For databases, snapshot during low activity or use the DB's own backup tooling for application-consistent backups.

### 5.8 Use case: raw block volume for an app managing its own storage

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: raw-block }
spec:
  accessModes: ["ReadWriteOnce"]
  volumeMode: Block                # no filesystem
  storageClassName: fast-gp3
  resources: { requests: { storage: 100Gi } }
---
apiVersion: v1
kind: Pod
metadata: { name: block-consumer }
spec:
  containers:
    - name: app
      image: my-block-app:1.0
      volumeDevices:               # note: volumeDevices, not volumeMounts
        - name: data
          devicePath: /dev/xvda    # raw device inside the container
  volumes:
    - name: data
      persistentVolumeClaim:
        claimName: raw-block
```

### 5.9 Use case: generic ephemeral volume (per-Pod scratch that needs PVC features)

```yaml
apiVersion: v1
kind: Pod
metadata: { name: ephemeral-scratch }
spec:
  containers:
    - name: app
      image: my-java-app:1.0
      volumeMounts:
        - { name: scratch, mountPath: /scratch }
  volumes:
    - name: scratch
      ephemeral:                        # created with the Pod, deleted with it
        volumeClaimTemplate:
          spec:
            accessModes: ["ReadWriteOnce"]
            storageClassName: fast-gp3
            resources: { requests: { storage: 10Gi } }
```

Generic ephemeral volumes give you the full StorageClass/CSI feature set (encryption, IOPS, snapshots) for **per-Pod temporary** storage that is automatically deleted when the Pod goes away — unlike a StatefulSet PVC, which persists.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Pick the right backend for the access pattern.** Block (EBS gp3/io2, local NVMe) for low-latency random I/O (databases). Shared file (EFS/NFS) for concurrency but expect higher and more variable latency; many small-file workloads (e.g., a Maven `.m2` cache on NFS) can be painfully slow.
- **Provision IOPS/throughput explicitly.** Defaults are conservative (gp3 = 3000 IOPS / 125 MiB/s). For a busy JVM database, raise `iops`/`throughput` in the StorageClass or use io2.
- **Filesystem choice:** `xfs` often outperforms `ext4` for large files and parallel I/O; `ext4` is the safe default. Set via `fsType`.
- **Avoid `emptyDir` medium: Memory for large data** — it eats your Pod memory limit and can trigger OOM.
- **Co-locate compute and storage zone** (WFFC) to avoid cross-AZ latency and unschedulable Pods.
- **JVM specifics:** put `-Djava.io.tmpdir` and any RocksDB/Lucene/Kafka-Streams state on a fast persistent volume, not the container layer. Beware `fsync` heavy workloads on networked storage — write latency dominates commit latency.

### 6.2 Correctness & concurrency

- **Never share an RWO block volume across writers expecting POSIX exclusivity.** Two database instances on one filesystem = corruption. Use RWOP for guaranteed single-Pod exclusivity.
- **RWX does not give you a distributed lock.** Concurrent writers still need application-level coordination; NFS file locking is notoriously flaky.
- **Crash consistency:** snapshots and abrupt Pod kills give crash-consistent state. Ensure your app (or the DB) has a recovery path (WAL replay). Set a sane `terminationGracePeriodSeconds` so the app can flush.
- **`subPath` pitfalls:** `subPath` is resolved once at mount; updates to ConfigMaps mounted via `subPath` are NOT propagated to the container. Use full-directory mounts for live-updating config.

### 6.3 Memory & resource accounting

- `emptyDir` with `medium: Memory` counts against Pod memory limits.
- Disk-backed `emptyDir` and image layers count against **ephemeral storage** requests/limits (`resources.requests.ephemeral-storage`); exceed it and the kubelet evicts the Pod. Set these for log-heavy or cache-heavy apps to protect nodes.

### 6.4 Security

- **Secrets at rest:** Kubernetes Secrets are only base64-encoded in etcd, not encrypted, unless you enable **encryption at rest** (`EncryptionConfiguration`) or use a KMS provider. Enable it.
- **Encrypt volumes at rest:** set `encrypted: "true"` (+ KMS key) in the StorageClass for cloud disks.
- **`fsGroup` / `fsGroupChangePolicy`:** controls volume ownership. `fsGroupChangePolicy: OnRootMismatch` avoids slow recursive chowns on huge volumes.
- **Restrict `hostPath`:** it can mount any node path — a container escape risk. Forbid via Pod Security Admission / OPA Gatekeeper / Kyverno policies.
- **Run as non-root**, drop capabilities, set `readOnlyRootFilesystem: true` and mount writable volumes only where needed.
- **CSI driver RBAC:** the controller has powerful cloud credentials (create/delete disks). Scope IAM tightly (e.g., IRSA on EKS, Workload Identity on GKE).

### 6.5 Cost

- **Delete reclaim policy + StatefulSet PVCs not cleaned up** = orphaned cloud disks billing forever. Audit `kubectl get pv` / cloud console regularly.
- gp3 is cheaper and more flexible than gp2; provisioned IOPS (io2) is expensive — only where needed.
- Snapshots accrue storage cost; set lifecycle/retention.
- EFS/NFS billed per GB-month + throughput; "infinite size" is convenient but costly if abused.

### 6.6 Observability

- **`kubectl describe pvc`** for provisioning failures (quota, IAM, zone).
- **`NodeGetVolumeStats`** powers `kubelet_volume_stats_*` Prometheus metrics: `kubelet_volume_stats_available_bytes`, `_capacity_bytes`, `_used_bytes`, `_inodes_used`. **Alert on volume fullness AND inode exhaustion** (many small files can exhaust inodes before bytes).
- CSI sidecars expose metrics (provisioning duration, errors).
- Watch `VolumeAttachment` objects and `volume_manager` kubelet logs for stuck attach/detach.

### 6.7 Testing

- Use **kind** or **minikube** with a local provisioner / CSI hostpath driver for local testing.
- Test failover: `kubectl delete pod` and verify data persists; cordon/drain a node and verify reschedule.
- Chaos-test detach: simulate node loss and confirm 6-minute detach timeout behavior (see §9).
- Validate restores from snapshots regularly — a backup you've never restored is not a backup.

### 6.8 Production hardening checklist

- Set `reclaimPolicy: Retain` for anything you can't afford to lose.
- Set `volumeBindingMode: WaitForFirstConsumer` for zonal block storage.
- Define `persistentVolumeClaimRetentionPolicy` explicitly on StatefulSets.
- Enable `allowVolumeExpansion: true` so you can grow without downtime.
- Set resource requests/limits including `ephemeral-storage`.
- Establish backup (snapshots + app-consistent dumps) and test restores.
- Pin CSI driver versions; track CSI migration status for your K8s version.

### 6.9 Common anti-patterns

- Using a **Deployment with replicas>1 and a single RWO PVC** (works only if all land on one node; flaky).
- Relying on **`hostPath`** in production.
- Putting databases on **NFS/EFS** expecting block-like correctness/performance.
- Forgetting that **StatefulSet PVCs survive deletion** → orphaned disks.
- Using **`Immediate`** binding in multi-AZ clusters → unschedulable Pods.
- Treating **snapshots as application-consistent backups** without quiescing.
- Mounting **ConfigMaps via `subPath`** and expecting live updates.

---

## 7. Advanced topics & deep internals

### 7.1 CSI migration (in-tree → CSI)

Historically, cloud volume plugins lived **in-tree** (compiled into kubelet/controller-manager). The **CSIMigration** effort transparently routes those in-tree APIs to CSI drivers behind the scenes. Status:
- AWS EBS, GCE PD, Azure Disk/File, OpenStack Cinder, vSphere — migration is **GA / on by default** in modern releases; several in-tree plugins are **removed** as of K8s 1.27–1.31. Practically: install the CSI driver; your old `awsElasticBlockStore` specs are auto-translated where still supported, but you should migrate manifests. **Verify exact removal versions for your cluster** — this is version-specific.

### 7.2 Topology-aware scheduling deep dive

With WFFC, the scheduler's **VolumeBinding plugin** participates in scheduling: it pre-filters nodes by topology so a Pod is only placed where its volume can live (or be created). The provisioned PV gets `nodeAffinity` matching the chosen zone/node. `allowedTopologies` on a StorageClass can constrain which zones are eligible. The `CSIStorageCapacity` objects (when the driver sets `storageCapacity: true`) let the scheduler avoid nodes whose backend lacks capacity.

### 7.3 fsGroup mechanics and large-volume chown cost

When `securityContext.fsGroup` is set, the kubelet recursively `chown`/`chmod`s the volume so the group can write. On a multi-TB volume with millions of files this can add **minutes** to Pod startup. Mitigations: `fsGroupChangePolicy: OnRootMismatch` (only chown if the top-level dir mismatches), or set `CSIDriver.fsGroupPolicy: File`/`None` to delegate to the driver.

### 7.4 Volume health monitoring

The **VolumeHealth** feature (CSI `NodeGetVolumeStats`/controller volume health, with the external-health-monitor sidecar) can surface abnormal volume conditions (e.g., underlying disk failure) as events/conditions. Support varies by driver.

### 7.5 Mount propagation

`volumeMounts[].mountPropagation` controls whether mounts created inside a container propagate to the host and other containers: `None` (default, private), `HostToContainer`, `Bidirectional` (requires privileged; used by storage drivers themselves). Relevant mainly for CSI node plugins and tools like rclone/FUSE mounts.

### 7.6 Inline ephemeral CSI volumes

Distinct from generic ephemeral volumes: a CSI driver with `volumeLifecycleModes: Ephemeral` can be referenced **inline in the Pod spec** (e.g., secrets-store CSI driver to mount Vault/Secrets Manager secrets). These have no PVC and are tied to the Pod.

### 7.7 Volume cloning

Besides snapshot restore, CSI supports **PVC-to-PVC cloning** directly: a new PVC with `dataSource: {kind: PersistentVolumeClaim, name: source}` triggers `CreateVolume` with a clone source. Source and clone must be same StorageClass and (usually) same zone.

### 7.8 Tuning knobs (controller-manager / kubelet)

- `--attach-detach-reconcile-sync-period` (controller-manager): how often attach/detach reconciles.
- The **multi-attach / detach-on-node-failure** behavior: when a node is unreachable, the controller waits (≈6 min, governed by pod eviction / `--node-monitor-grace-period` + maxWaitForUnmountDuration) before force-detaching so the volume can attach elsewhere. Tuning these trades data-safety vs failover speed.
- `--max-volumes-per-node` / driver-reported attach limits: cloud instances cap attachable disks (e.g., EBS limits per instance type, often dozens). The scheduler respects `CSINode` reported limits.

### 7.9 Filesystem-level concerns

- **Inodes:** ext4 fixes inode count at format time; a volume can run out of inodes (millions of tiny files) while having free bytes → `ENOSPC`. xfs allocates inodes dynamically (better for huge file counts).
- **Discard/TRIM:** mount option `discard` (or periodic `fstrim`) returns freed blocks to thin-provisioned/SSD backends, controlling cost; can add latency, so often done periodically.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Volume type selection

| Requirement | Recommended | Avoid |
|---|---|---|
| Single-writer DB, low latency | Cloud block (EBS gp3/io2, PD-SSD) or local NVMe | NFS/EFS |
| Many Pods read-write same files | RWX: EFS/NFS/CephFS/Azure Files | Block (impossible) |
| Single-Pod exclusivity guaranteed | RWO**P** | RWO (node-level) |
| Max throughput, app self-replicates | `local` PVs (NVMe) | Networked block |
| Per-Pod temp with PVC features | Generic ephemeral volume | StatefulSet PVC (persists) |
| Pure scratch, no persistence | `emptyDir` | PVC overhead |
| App manages raw device | `volumeMode: Block` | Filesystem mode |

### 8.2 Local vs networked storage

| Dimension | Local PV (NVMe/SSD on node) | Networked (EBS/PD/Ceph/NFS) |
|---|---|---|
| Latency / IOPS | Best (no network hop) | Higher, variable |
| Data mobility | None — pinned to node | High — re-attaches on other nodes |
| Node failure | Data stranded/lost | Re-attach elsewhere (block) |
| Capacity flexibility | Fixed by hardware | Resizable, often elastic |
| HA responsibility | App must replicate | Storage system + app |
| Cost | Often cheaper raw | Managed premium |
| Use when | Cassandra, Kafka, distributed DBs that replicate | Anything needing failover/mobility |

### 8.3 StorageClass settings decision rules

- **Multi-AZ + block storage →** `volumeBindingMode: WaitForFirstConsumer`.
- **Important data →** `reclaimPolicy: Retain`.
- **Want to grow later →** `allowVolumeExpansion: true`.
- **Need snapshots →** ensure driver supports them; create a `VolumeSnapshotClass`.

### 8.4 Should you run stateful workloads on Kubernetes at all?

A genuine senior-level judgment call. Considerations:

**Arguments for:** unified platform/tooling, GitOps for DB config, operators (e.g., CloudNativePG, Strimzi for Kafka, Vitess for MySQL) that automate failover/backup/upgrades, portability, cost control vs managed services.

**Arguments against:** managed services (RDS, Cloud SQL, MSK, Aurora) remove enormous operational burden (HA, backups, patching, failover) at a price; running a database well on K8s requires deep expertise in storage, networking, and the specific DB; data gravity makes mistakes catastrophic.

**Decision rule:**
- **Use a managed service** when the database is your system of record, the team lacks deep DB+K8s ops expertise, and cost allows. The reduced operational risk usually wins.
- **Run on K8s** when you have a mature platform team, a battle-tested **operator**, strong storage (good CSI, snapshots, tested restores), and a real reason (cost at scale, portability/multi-cloud, on-prem, or the workload is natively distributed like Cassandra/Kafka that don't need shared block storage).
- **Hybrid:** stateless on K8s, stateful on managed services — the most common pragmatic choice.

### 8.5 Snapshot vs application backup

| | CSI Snapshot | App-level backup (pg_dump, mysqldump, Kafka mirror) |
|---|---|---|
| Consistency | Crash-consistent (unless quiesced) | Application-consistent |
| Speed | Fast (block-level) | Slower (logical) |
| Granularity | Whole volume | Table/object level |
| Portability | Tied to backend | Portable across systems |
| Best for | Fast clone/rollback | Long-term, cross-version, point-in-time recovery |

Best practice: **use both** — snapshots for fast operational rollback, logical backups for durable, portable, application-consistent recovery.

---

## 9. Failure modes & debugging

### 9.1 PVC stuck in `Pending`

**Causes & checks:**
- No default StorageClass and PVC didn't name one → `kubectl get sc`; set a default or specify `storageClassName`.
- `WaitForFirstConsumer` and no Pod scheduled yet → **expected**; it binds when a Pod is scheduled.
- Provisioner errors (IAM/permissions, quota, invalid zone) → `kubectl describe pvc <name>` reads the events: e.g., `failed to provision volume ... UnauthorizedOperation` (fix IAM), `VolumeLimitExceeded`, `InvalidParameter`.
- No matching static PV (size/access-mode/selector mismatch) → compare PVC vs `kubectl get pv`.

### 9.2 Pod stuck in `ContainerCreating` / `FailedMount`

```
kubectl describe pod <pod>     # look at Events
```
Common messages:
- `Unable to attach or mount volumes: ... timed out waiting for the condition` — attach/mount stuck.
- `Multi-Attach error for volume "pvc-..." Volume is already exclusively attached to one node` — an RWO volume is still attached to a dead/other node (see §9.3).
- `mount failed: ... wrong fs type, bad option` — filesystem/mount-option mismatch.
- `volume node affinity conflict` — PV pinned to a zone/node where the Pod can't run (Immediate binding in multi-AZ; fix with WFFC or schedule into the right zone).

### 9.3 The "Multi-Attach" / 6-minute detach delay

When a node goes **NotReady** (crash/network partition), an RWO block volume is still recorded as attached to it. Kubernetes won't force-detach immediately because if the node is merely partitioned (still writing), detaching could corrupt data. After a timeout (≈6 minutes total: node-monitor grace + pod eviction + force-detach), the controller force-detaches so the rescheduled Pod can attach elsewhere. **Symptom:** a StatefulSet Pod stuck `ContainerCreating` for ~6 minutes after a node dies. **Mitigations:** ensure the dead node is actually gone (autoscaler/terminate it), tune timeouts cautiously, or use storage that supports faster fencing. This is a famous real-world incident pattern for K8s databases.

### 9.4 Volume full / inode exhaustion

- Bytes full: app errors `No space left on device`; check `kubelet_volume_stats_available_bytes`; expand the PVC (`allowVolumeExpansion`) and confirm `NodeExpandVolume` ran.
- **Inodes full** (subtle): `df -h` shows free space but writes fail; check `df -i` / `kubelet_volume_stats_inodes_free`. Caused by millions of tiny files (e.g., session files, cache fragments). Fix: clean up, use xfs, or restructure.

### 9.5 Expansion didn't take effect

After patching PVC size, the device grew but the filesystem didn't. Check PVC conditions:
```
kubectl get pvc <name> -o jsonpath='{.status.conditions}'
```
`FileSystemResizePending` means the node-side `NodeExpandVolume` hasn't run; for some drivers the Pod must restart, or the resize happens online on next reconcile. Verify with `kubectl exec ... df -h`.

### 9.6 Orphaned PVs / cloud disks (cost incident)

Deleting StatefulSets leaves PVCs (and `Retain` PVs leave disks) behind. Audit:
```
kubectl get pv --sort-by=.spec.capacity.storage
kubectl get pvc -A
# cross-check against cloud volume list; delete truly-orphaned disks
```

### 9.7 Stuck terminating PVC/PV (finalizers)

A PVC won't delete because a Pod still uses it (`kubernetes.io/pvc-protection` finalizer) or a PV has `kubernetes.io/pv-protection`. This is a **safety feature**, not a bug — delete the consuming Pod first. Only remove finalizers manually as a last resort (risk of orphaning backend storage).

### 9.8 Data corruption from improper sharing

Two writers on an RWO volume (e.g., a misconfigured Deployment with replicas>1 landing on the same node, or RWX used by a DB) → filesystem/DB corruption. Diagnose via DB logs (checksum failures, WAL errors). Prevention: RWOP, leader election, or single-replica StatefulSets with proper PVC-per-Pod.

### 9.9 Debugging toolkit recap

```
kubectl describe pvc/pv/pod              # events are gold
kubectl get volumeattachment            # stuck attach/detach
kubectl get events --sort-by=.lastTimestamp
kubectl exec POD -- sh -c 'df -h; df -i; mount | grep MOUNTPATH'
kubectl logs -n kube-system <csi-controller> -c csi-provisioner   # provisioning errors
journalctl -u kubelet | grep -i volume   # on the node, mount errors
```

---

## 10. Interview drill

**Q1. What's the difference between a Volume, a PV, and a PVC?**
Model answer: A *Volume* is a Pod-level directory mounted into containers, defined inline in the Pod spec; its lifetime depends on its type. A *PersistentVolume* is a cluster-scoped object representing a real piece of storage (with capacity, access modes, reclaim policy, backend). A *PersistentVolumeClaim* is a namespaced request for storage; Kubernetes binds it 1:1 to a matching PV. Pods reference PVCs, not PVs, which decouples the developer's request from the admin's supply and makes manifests portable.
- *Probe: Why reference a PVC instead of a PV directly?* Portability and separation of concerns — the same Pod spec works across clouds; the developer doesn't need to know the backend.
- *Probe: Is binding always 1:1?* Yes — a PV binds to exactly one PVC (`claimRef`) and vice versa (`volumeName`); binding is exclusive.
- *Probe: Can a PVC bind to a PV in another namespace?* PVs are cluster-scoped (not namespaced); the PVC is namespaced; the `claimRef` records which namespace's PVC owns it.

**Q2. Explain access modes. Does ReadWriteOnce mean one Pod?**
Model answer: No — RWO means one **node** can mount it read-write; multiple Pods on that same node can share it. ROX = many nodes read-only; RWX = many nodes read-write (requires shared filesystem). RWOP (ReadWriteOncePod) is the one that means a single **Pod** cluster-wide. Block storage is inherently RWO; RWX needs NFS/EFS/CephFS.
- *Probe: How do you get true single-Pod exclusivity?* Use `ReadWriteOncePod` (stable since 1.29).
- *Probe: Can EBS be RWX?* No — a cloud block device attaches to one VM at a time; you need a shared filesystem.
- *Probe: Two replicas, one RWO PVC — will it work?* Only if both Pods land on the same node; otherwise the second fails to mount (Multi-Attach). It's an anti-pattern.

**Q3. Walk me through dynamic provisioning end to end.**
Model answer: User creates a PVC referencing a StorageClass. The external-provisioner sidecar sees the Pending PVC; if `volumeBindingMode` is `WaitForFirstConsumer` it waits for the Pod to be scheduled (topology), else it provisions immediately. It calls CSI `CreateVolume` on the driver's controller service, the backend creates the disk and returns a handle, the provisioner creates the PV with the right `claimRef`, and the PV controller binds the PVC. When the Pod is scheduled, attach (`ControllerPublishVolume`) → stage (`NodeStageVolume`, format+global mount) → publish (`NodePublishVolume`, bind-mount into Pod) run.
- *Probe: Why WaitForFirstConsumer?* So zonal volumes are created in the zone where the Pod is actually scheduled, avoiding volume-node-affinity conflicts.
- *Probe: Which component creates the PV object?* The external-provisioner sidecar.
- *Probe: What if CreateVolume fails?* PVC stays Pending; `describe pvc` shows the error (IAM/quota/zone).

**Q4. What is CSI and why does it exist?**
Model answer: CSI is a standardized gRPC interface for storage plugins so orchestrators don't bake vendor code in-tree. A driver implements Identity/Controller/Node services; Kubernetes provides sidecars (provisioner, attacher, resizer, snapshotter, registrar) that translate K8s events into CSI calls. This lets vendors ship/upgrade drivers independently of Kubernetes and is why in-tree plugins are being migrated/removed.
- *Probe: Where do the Controller vs Node services run?* Controller centrally (Deployment); Node service on every node (DaemonSet) because mounting is local.
- *Probe: Which drivers skip the attach step?* Those with `attachRequired: false` (e.g., NFS) — no VolumeAttachment/ControllerPublish.
- *Probe: What does the CSIDriver object configure?* attachRequired, podInfoOnMount, fsGroupPolicy, volumeLifecycleModes, storageCapacity, etc.

**Q5. How does a StatefulSet provide stable storage?**
Model answer: Via `volumeClaimTemplates` — the controller creates a dedicated PVC per Pod named `<template>-<sts>-<ordinal>` (e.g., `data-pg-0`). Each Pod always reattaches to its own PVC across reschedules, preserving identity and data. PVCs are not deleted when the StatefulSet is deleted unless `persistentVolumeClaimRetentionPolicy` says so.
- *Probe: What happens to PVCs on scale-down?* By default retained; configurable via `whenScaled`.
- *Probe: How does pod-0 reattach to its volume on a new node (block storage)?* Topology-aware binding + the volume re-attaching in the same zone; for RWO, the old node must release it.
- *Probe: Why ordered startup?* So clustered systems form membership deterministically (e.g., seed nodes first).

**Q6. Reclaim policies — Delete vs Retain?**
Model answer: `Delete` (default for dynamic) deletes the PV and the underlying disk when the PVC is deleted — convenient, risky. `Retain` keeps both; the PV goes `Released` and an admin manually reclaims. Use Retain for data you can't lose. `Recycle` is deprecated.
- *Probe: Where is the policy set?* On the PV (inherited from the StorageClass for dynamic volumes).
- *Probe: How do you reuse a Retained PV?* Clear its `claimRef` so it becomes Available again, or re-import the disk into a new PV.

**Q7. (Senior signal) Should you run a production database on Kubernetes?**
Model answer: It depends on team maturity and risk tolerance. Managed services (RDS/Cloud SQL/MSK) offload HA, backups, patching, and failover — usually the right default unless you have a mature platform team, a proven operator (CloudNativePG, Strimzi), robust CSI storage with tested snapshots/restores, and a concrete reason (cost at scale, on-prem, multi-cloud portability, or natively distributed systems like Cassandra/Kafka that don't need shared block storage). The common pragmatic answer is stateless on K8s, stateful on managed services.
- *Probe: What storage features are prerequisites?* Reliable CSI with attach/detach fencing, snapshots, expansion, topology-aware binding, and tested backup/restore.
- *Probe: Biggest operational risk?* The 6-minute detach delay and split-brain on node failure; data gravity makes mistakes catastrophic.
- *Probe: Why do operators help?* They encode failover, backup, and upgrade logic that's otherwise manual and error-prone.

**Q8. (Senior signal) Local PV vs networked block — when each?**
Model answer: Local PVs give best latency/throughput but no mobility — data is pinned to the node and lost if it dies, so only use them where the application replicates data itself (Cassandra, Kafka, distributed DBs). Networked block (EBS/PD) trades latency for re-attachability and failover, suitable for single-instance stateful apps that rely on the storage layer for durability. Choose by who owns HA: the app (local) or the storage (networked).
- *Probe: How do you bind a local PV to its node?* Mandatory `nodeAffinity` on the PV plus `WaitForFirstConsumer`.
- *Probe: Can local PVs be dynamically provisioned?* Not by the built-in `no-provisioner`; you need an external static provisioner or operator.

**Q9. (Senior signal) Explain the volume-node-affinity conflict and how to prevent it.**
Model answer: With `Immediate` binding in a multi-AZ cluster, a zonal block volume may be created in zone A while the Pod can only schedule in zone B (capacity/affinity), since the disk can't cross zones the Pod is unschedulable ("volume node affinity conflict"). Prevent it with `volumeBindingMode: WaitForFirstConsumer`, so the scheduler picks the node first and the volume is provisioned in that zone, and the PV's `nodeAffinity` keeps them co-located.
- *Probe: What component enforces this at schedule time?* The scheduler's VolumeBinding plugin.
- *Probe: Can `allowedTopologies` help?* Yes — constrain provisioning to specific zones.

**Q10. How do snapshots and restores work, and are they backups?**
Model answer: CSI snapshots use VolumeSnapshot (request), VolumeSnapshotClass (how), VolumeSnapshotContent (the actual snapshot), served by the snapshot-controller + external-snapshotter calling `CreateSnapshot`. You restore by creating a PVC with `dataSource` pointing at the snapshot. They're crash-consistent by default and tied to the backend — useful for fast rollback/clone, but not a substitute for application-consistent, portable, point-in-time backups. Use both.
- *Probe: How do you make a snapshot app-consistent?* Quiesce/flush the app (e.g., DB checkpoint, freeze) before snapshotting.
- *Probe: Restore size constraint?* The new PVC must be ≥ the snapshot's size.

**Q11. What is the attach/mount pipeline and which component does each step?**
Model answer: AttachDetachController creates a VolumeAttachment → external-attacher calls `ControllerPublishVolume` (cloud attach) → kubelet's VolumeManager calls `NodeStageVolume` (format + global mount once per node) → `NodePublishVolume` (bind-mount into the Pod). Teardown reverses it, ending in `ControllerUnpublishVolume` (detach) and `DeleteVolume` if reclaimPolicy is Delete.
- *Probe: Why a separate stage step?* So a volume is mounted once per node even if several Pods use it.
- *Probe: Which steps are skipped for NFS?* Attach (no ControllerPublish/VolumeAttachment), since `attachRequired: false`.

**Q12. How do you grow a volume without downtime?**
Model answer: Use a StorageClass with `allowVolumeExpansion: true`, patch the PVC's requested size upward; external-resizer calls `ControllerExpandVolume` to grow the device and the kubelet runs `NodeExpandVolume` (e.g., resize2fs) to grow the filesystem (online for many drivers). Shrinking is unsupported.
- *Probe: How do you know the filesystem resize finished?* PVC condition `FileSystemResizePending` clears; verify with `df -h` in the Pod.
- *Probe: What if the driver requires a restart?* Some drivers complete the FS resize on remount; cycle the Pod.

---

## 11. Glossary

- **Access mode** — How a PV may be mounted relative to nodes: RWO (one node RW), ROX (many nodes RO), RWX (many nodes RW), RWOP (one Pod RW).
- **AttachDetachController** — Controller in kube-controller-manager that creates/removes VolumeAttachment objects to attach/detach volumes to nodes.
- **Availability Zone (AZ)** — An isolated datacenter within a cloud region; block volumes are typically zonal.
- **Bind mount** — Linux mechanism making an existing directory appear at another path; used to project staged volumes into Pods.
- **Block storage** — Raw block device (virtual disk) you format and mount; attaches to one node at a time (e.g., EBS).
- **cgroups** — Kernel feature limiting/accounting process resource usage.
- **Claim (PVC)** — A request for storage by capacity/access mode/class; binds 1:1 to a PV.
- **ConfigMap** — K8s object holding non-secret config; can be mounted as files.
- **Control plane** — API server, etcd, scheduler, controllers that manage cluster state.
- **Controller / reconcile loop** — Loop converging actual state to desired state.
- **Crash-consistent** — State as if power were cut at an instant; may need recovery (WAL replay).
- **CSI (Container Storage Interface)** — Standard gRPC API for storage plugins.
- **CSIDriver / CSINode** — Objects registering driver behavior and per-node driver/topology info.
- **DaemonSet** — Runs one Pod per (selected) node; used for CSI node plugins.
- **Deployment** — Controller for stateless, interchangeable Pods.
- **Dynamic provisioning** — Auto-creating storage + PV from a StorageClass when a PVC appears.
- **emptyDir** — Ephemeral Pod-scoped scratch volume; deleted with the Pod.
- **Ephemeral storage** — Node-local non-persistent storage (container layers, emptyDir, logs); subject to eviction.
- **etcd** — Strongly consistent key-value store (Raft) holding all K8s state.
- **File storage** — Shared network filesystem (NFS/EFS) supporting concurrent multi-node access (RWX).
- **Finalizer** — Annotation preventing deletion until cleanup completes (e.g., pvc-protection).
- **fsGroup** — securityContext field setting group ownership of mounted volumes.
- **gRPC** — RPC framework (Protobuf over HTTP/2) used by CSI.
- **Headless Service** — Service with clusterIP None giving Pods stable DNS; used by StatefulSets.
- **hostPath** — Mounts a node path directly into a Pod; risky in production.
- **In-tree plugin** — Storage code compiled into Kubernetes itself (being migrated to CSI).
- **Inode** — Filesystem metadata entry per file; can be exhausted independently of bytes.
- **kubelet** — Per-node agent that runs Pods and mounts volumes.
- **Local PV** — PersistentVolume backed by a disk on a specific node; pinned via nodeAffinity.
- **Mount propagation** — Controls whether mounts inside a container propagate to host/other containers.
- **Namespaces (Linux)** — Kernel feature isolating a process's view of resources (incl. mounts).
- **Node** — Worker machine running Pods.
- **NodeStageVolume / NodePublishVolume** — CSI node calls: global mount/format, then bind-mount into Pod.
- **OverlayFS** — Union filesystem stacking image layers + a writable layer for containers.
- **Pod** — Smallest deployable unit; one or more co-scheduled containers sharing network/volumes.
- **POSIX semantics** — Traditional Unix filesystem behavior (files, fsync, locking, permissions).
- **PersistentVolume (PV)** — Cluster-scoped object representing real storage.
- **PersistentVolumeClaim (PVC)** — Namespaced request for storage.
- **Provisioner** — The CSI driver named in a StorageClass that creates volumes.
- **Raft** — Consensus algorithm (leader + log replication) used by etcd.
- **Reclaim policy** — What happens to PV/backend on PVC delete: Delete / Retain / (Recycle, deprecated).
- **Reconciliation** — Observe→diff→act loop controllers run.
- **StatefulSet** — Controller giving Pods stable identity, ordered lifecycle, and per-Pod PVCs.
- **StorageClass** — Template for dynamic provisioning (provisioner, parameters, binding mode, reclaim, expansion).
- **subPath** — Mount a subdirectory of a volume; note ConfigMap subPath mounts don't live-update.
- **Snapshot (VolumeSnapshot/Content/Class)** — Point-in-time copy of a PVC via CSI.
- **Topology-aware provisioning** — Creating/binding volumes in the right zone/node for the Pod.
- **tmpfs** — In-memory filesystem (RAM-backed); used by emptyDir medium: Memory and Secrets.
- **TRIM/discard** — Returning freed blocks to the backend (thin provisioning/SSD).
- **VolumeAttachment** — Object recording attach intent/status of a volume on a node.
- **volumeBindingMode** — Immediate vs WaitForFirstConsumer (topology-aware) provisioning timing.
- **volumeClaimTemplates** — StatefulSet field generating a PVC per Pod.
- **volumeMode** — Filesystem (default) or Block (raw device).
- **Volume node affinity conflict** — Pod unschedulable because its zonal volume is in the wrong zone.

---

## 12. Cheat-sheet & self-test

### Dense recap

- **Hierarchy:** Pod → PVC (request, namespaced) → PV (real storage, cluster) → backend, via a **CSI driver**. StorageClass = vending machine for dynamic PVs.
- **Access modes:** RWO = one **node** RW; ROX = many nodes RO; RWX = many nodes RW (needs NFS/EFS/Ceph); **RWOP** = one **Pod** RW. Block ≈ RWO only.
- **Provisioning:** static (admin makes PVs) vs dynamic (StorageClass auto-creates). Set `volumeBindingMode: WaitForFirstConsumer` in multi-AZ to avoid node-affinity conflicts.
- **Reclaim:** `Delete` (default dynamic, deletes disk) vs `Retain` (keep — use for important data). Recycle deprecated.
- **CSI calls in order:** CreateVolume → ControllerPublishVolume (attach) → NodeStageVolume (format+global mount) → NodePublishVolume (bind-mount into Pod). Reverse to tear down; DeleteVolume on PVC delete if Delete policy.
- **Sidecars:** external-provisioner (create/delete), external-attacher (attach), external-resizer (expand), external-snapshotter (snapshot), node-driver-registrar, livenessprobe.
- **StatefulSet:** `volumeClaimTemplates` → `data-<sts>-<n>` PVC per Pod; PVCs survive deletion unless `persistentVolumeClaimRetentionPolicy` set.
- **Expansion:** `allowVolumeExpansion: true`, patch PVC up; grow only, never shrink. `NodeExpandVolume` runs resize2fs/xfs_growfs.
- **Defaults to remember (verify per version/vendor):** gp3 = 3000 IOPS / 125 MiB/s; default reclaim for dynamic = Delete; default binding mode = Immediate (override to WFFC!); ~6-minute detach delay on node failure; ext4 default fsType.
- **Key failure signatures:** PVC Pending (no SC / IAM / WFFC waiting); Multi-Attach error (RWO on dead node, ~6 min); volume node affinity conflict (Immediate + multi-AZ); inode exhaustion (df -i); orphaned disks (StatefulSet/Retain cleanup).
- **Stateful-on-K8s rule:** default to managed services; run on K8s only with mature platform + proven operator + tested snapshots/restores, or for natively distributed systems.
- **Top commands:** `describe pvc/pv/pod` (events), `get volumeattachment`, `get sc`, `exec -- df -h && df -i`.

### Self-test (no answers — recall practice)

1. A teammate sets `replicas: 3` on a Deployment with a single RWO PVC. What happens, and how do you fix it correctly?
2. Trace every CSI call (controller and node side) from PVC creation to a running Pod for an attachable block driver, naming the K8s component that triggers each.
3. Your StatefulSet Pod `db-1` is stuck in `ContainerCreating` for several minutes after its node crashed. What's happening, why, and what are your options?
4. When and why would you choose `volumeBindingMode: WaitForFirstConsumer`, and what specific failure does it prevent in a multi-AZ cluster?
5. You deleted a StatefulSet to "clean up," but your cloud bill didn't drop. Explain why, and how to actually reclaim the storage safely.
6. Design storage for: (a) a single-instance PostgreSQL, (b) a 5-node Cassandra cluster, (c) a 5-replica web app sharing uploaded media. Justify backend, access mode, StorageClass settings, and reclaim policy for each.
7. A volume shows free space in `df -h` but writes fail with `No space left on device`. What's the likely cause and the fix?
```

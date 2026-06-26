# Containers & Linux Primitives

> An exhaustive engineering-handbook chapter for senior Java/JVM backend developers who want to master what a container *actually is* — from the kernel primitives up through OCI images, runtimes, and cgroup-aware JVM behavior.

---

## 1. Overview & where it fits

### What it is

A **container** is a normal Linux process (or a tree of processes) that the kernel has been told to *lie to*. The kernel gives that process a restricted, private view of the system — its own process IDs, its own network stack, its own filesystem root, its own hostname — and enforces limits on how much CPU, memory, and I/O it can consume. There is no separate operating system inside a container. There is no virtual hardware. There is just your `java -jar app.jar` process, running directly on the host kernel, wearing a disguise.

This is the single most important mental model in this entire chapter, and almost every confusion about containers dissolves once you internalize it:

> **A container is a process, not a virtual machine.** The "container" is not a thing the kernel knows about as a first-class object. It is an *emergent* construct assembled from several independent Linux kernel features — namespaces, cgroups, and a layered filesystem — that, used together, make a process *feel* like it has a machine to itself.

When you run `ps aux` on the host, you can see the container's processes directly in the host process list. When you run `top`, the container's memory and CPU show up as ordinary host usage. The isolation is real and enforced, but it is *configuration applied to a process*, not a boundary around a separate computer.

### The problem it solves

Before containers, deploying software meant one of two unpleasant extremes:

1. **Bare-metal / shared-host deployment.** Install your app and its dependencies directly onto a server. This is fast and resource-efficient, but "dependency hell" is real: app A needs `libssl 1.0`, app B needs `libssl 3.0`, the system Python is the wrong version, two apps both want port 8080, and a config change for one app silently breaks another. There is no isolation and no reproducibility ("works on my machine").

2. **Virtual machines (VMs).** Give each app its own full guest operating system running on virtualized hardware via a **hypervisor** (software like VMware ESXi, KVM, or Hyper-V that emulates or mediates access to CPU/memory/devices so multiple OSes can share one physical machine). This gives strong isolation and reproducibility, but every VM carries a full OS kernel, init system, and userland — gigabytes of disk, hundreds of MB of RAM overhead, and tens of seconds to boot. You cannot pack many small services onto a host this way without enormous waste.

Containers split the difference. They give you:

- **Isolation** approaching what a VM offers (private process tree, network, filesystem) but enforced by the *shared host kernel* rather than by virtualized hardware.
- **Reproducibility**: the entire userland (libraries, runtime, your app, config) is packaged into an *image* — a frozen, content-addressed, versioned filesystem snapshot. The same image runs identically on your laptop, in CI, and in production.
- **Density and speed**: because there is no guest kernel to boot, a container starts in milliseconds-to-seconds and adds only kilobytes-to-megabytes of overhead. You can run hundreds on a single host.

### When you reach for it

- You want **"build once, run anywhere"** — bit-for-bit identical runtime environments across dev, CI, staging, and prod.
- You are deploying **microservices** and want fast, dense, independently-versioned units of deployment.
- You want **immutable infrastructure**: never patch a running box; instead rebuild the image and replace the container.
- You are adopting an **orchestrator** like Kubernetes, which schedules and manages containers as its fundamental unit of work.
- You need **process-level isolation** for multi-tenant workloads but cannot afford the overhead of a VM per tenant.

### When you do *not* reach for it

- You need **hard, hostile-multi-tenant security isolation** equal to a VM (e.g., running arbitrary untrusted customer code on shared hardware). A shared kernel is a shared attack surface; a kernel exploit escapes all containers on the host. (Mitigations exist — gVisor, Kata Containers, Firecracker microVMs — covered later.)
- You need to run a **different kernel or OS** (e.g., a Windows app on a Linux host, or a workload requiring a specific custom kernel module). Containers share the host kernel by definition.
- The workload is a **single, long-lived, stateful monolith** on dedicated hardware where the packaging overhead buys you nothing.

### One-paragraph mental model

> A container is a Linux process whose view of the world has been narrowed by **namespaces** (it sees only its own PIDs, network interfaces, mounts, hostname, IPC objects, and user-ID mappings), whose resource appetite has been capped by **cgroups** (so much CPU, so much RAM, so much I/O), and whose filesystem is a stack of read-only image **layers** with a thin writable layer on top, glued together by a **union filesystem** (usually OverlayFS). The image is a portable, content-addressed bundle of those layers plus metadata, defined by the **OCI** standard. A **runtime** (runc, under containerd, under Docker or Kubernetes) is the small program that calls the kernel syscalls to set all this up and then `exec`s your process. That is the entire trick.

---

## 2. Foundations from first principles

We will build the concept of a container from absolute zero. To do that, we first need a precise vocabulary for the kernel features it stands on. Read this section slowly; everything later depends on it.

### 2.1 What is a process, really?

A **process** is a running instance of a program. The kernel represents each process with a data structure (in Linux, `task_struct`) holding its **PID** (process ID, a number), its memory map, its open file descriptors, its credentials (which user/group it runs as), and pointers to the resources it is allowed to see. A process is created by **forking** (the `fork()` or `clone()` syscall, which makes a near-copy of the calling process) and typically then **exec**ing (the `execve()` syscall, which replaces the process's program image with a new executable). PID 1 is the first userspace process the kernel starts at boot (historically `init`, today often `systemd`); it is special — it is the ancestor of every other process and it adopts orphaned children.

A **syscall** (system call) is the controlled doorway through which a userspace process asks the kernel to do something privileged on its behalf — open a file, allocate memory, send a network packet, create another process. Userspace code cannot touch hardware or kernel data structures directly; it *must* go through syscalls. Containers are built almost entirely out of a handful of syscalls (`clone`, `unshare`, `setns`, `mount`, `pivot_root`) plus writes to special kernel filesystems. Keep this in mind: **everything a container "is" reduces to syscalls a normal process can make.**

### 2.2 Namespaces — the isolation primitive

A **namespace** is a kernel feature that **partitions a global system resource** so that processes inside the namespace see their own isolated instance of that resource, while processes outside see a different instance. The classic analogy: a namespace is like changing what a process can *see*, not what *exists*. The resource still physically exists once; the namespace controls which processes' view it belongs to.

Linux has (as of modern kernels) **eight** namespace types. The six most relevant to containers, with beginner-level definitions:

| Namespace | Isolates | Plain-English meaning | Created/`unshare` flag |
|---|---|---|---|
| **PID** | Process IDs | A process inside sees its own PID tree starting at PID 1; it cannot see or signal host processes. | `CLONE_NEWPID` |
| **NET** | Network stack | Its own network interfaces, IP addresses, routing tables, port space, firewall (`iptables`) rules, sockets. Two containers can both bind port 8080. | `CLONE_NEWNET` |
| **MNT** (mount) | Mount points | Its own filesystem mount table; it can have a totally different `/` than the host. | `CLONE_NEWNS` |
| **UTS** | Hostname & domain name | Its own `hostname`/`domainname` (UTS = "Unix Time-sharing System," a historical name). | `CLONE_NEWUTS` |
| **IPC** | Inter-process communication | Its own System V IPC objects and POSIX message queues (shared memory segments, semaphores). | `CLONE_NEWIPC` |
| **USER** | User & group IDs | Maps UIDs/GIDs so that UID 0 (root) *inside* the container maps to an unprivileged UID *outside*. The cornerstone of rootless containers. | `CLONE_NEWUSER` |

The two you will hear about less often:

- **cgroup namespace** (`CLONE_NEWCGROUP`): virtualizes the *view* of the cgroup hierarchy so a container sees its own cgroup as the root, hiding the host's cgroup layout.
- **Time namespace** (`CLONE_NEWTIME`, Linux 5.6+): lets a container have its own offsets for `CLOCK_MONOTONIC` and `CLOCK_BOOTTIME` (useful for checkpoint/restore).

**How namespaces are created.** A process enters a new namespace in one of three ways:

1. `clone()` with one or more `CLONE_NEW*` flags — create a child process directly in fresh namespaces.
2. `unshare()` — move the *calling* process into new namespaces (used by the `unshare` CLI).
3. `setns()` — join an *existing* namespace (this is how `docker exec` or `nsenter` jumps into a running container's namespaces).

Namespaces are reference-counted and live as long as any process is a member *or* any file descriptor / bind-mount pins them. You can observe them under `/proc/<pid>/ns/` — each entry is a magic symlink whose target encodes the namespace type and inode number; two processes sharing a namespace show the same inode.

**A worked thought experiment.** When you start a container, the runtime calls `clone()` (or `unshare`) with `CLONE_NEWPID | CLONE_NEWNET | CLONE_NEWNS | CLONE_NEWUTS | CLONE_NEWIPC` (and optionally `CLONE_NEWUSER`). The new process becomes PID 1 *in its PID namespace*, gets an empty network namespace (only a loopback), and an isolated mount table. The runtime then sets up the filesystem (next section) and `execve`s your application. From inside, your app sees a tiny private machine. From outside, the host sees one extra process tree it can fully inspect.

### 2.3 The filesystem: chroot, pivot_root, and union/overlay filesystems

Isolating PIDs and the network is not enough; a container also needs its *own root filesystem* so it can ship its own libraries and not see the host's files. The historical primitive is **`chroot`** (change root) — a syscall that changes a process's idea of `/` to some subdirectory. `chroot` is weak (escapable, doesn't isolate mounts), so real runtimes use the mount namespace plus **`pivot_root`**, which swaps the root mount entirely and lets the old root be unmounted, giving genuine isolation.

But where does the container's root filesystem *come from*, and why is it efficient? Enter **union filesystems**.

A **union filesystem** (a.k.a. overlay/layered filesystem) presents *multiple* directory trees stacked on top of each other as a *single* merged directory tree. Reads see the top-most version of any file; writes go to a designated writable top layer. This is the magic that makes container images small and fast to start.

The dominant implementation today is **OverlayFS** (in the mainline kernel since 3.18; the v2 driver `overlay2` is Docker's default). Its model has these parts:

- **lowerdir** — one or more **read-only** layers (your image layers). Multiple lowerdirs are themselves stacked.
- **upperdir** — a single **read-write** layer where all changes land (the container's writable layer).
- **workdir** — an empty scratch directory OverlayFS needs for atomic operations (must be on the same filesystem as upperdir).
- **merged** — the unified view the container actually uses as its root.

Key behaviors you must understand:

- **Copy-up.** When a process modifies a file that exists only in a lower (read-only) layer, OverlayFS *copies the whole file up* into the upperdir first, then applies the write. This means the first write to a large file in a base layer is expensive (it copies the entire file), and it is per-file, not per-block.
- **Whiteouts.** "Deleting" a file that exists in a lower layer doesn't really delete it (you can't write to read-only layers). Instead OverlayFS creates a **whiteout** — a special character device with major/minor 0/0 — in the upperdir that masks the lower file from the merged view. This is why you cannot shrink an image by deleting files in a *later* `RUN` layer; the bytes still live in the earlier layer, and you've only added a whiteout on top. (This is the #1 cause of bloated images.)
- **Opaque directories.** A directory in the upper layer marked with the `trusted.overlay.opaque` xattr completely hides the corresponding lower directory's contents.

Why this is brilliant: dozens of containers started from the same image **share the read-only lower layers on disk and in the page cache**, and each gets only a tiny private upperdir for its changes. Pulling a new image only downloads layers you don't already have. This is **content-addressable, deduplicated, copy-on-write storage** — and it is the reason a 200 MB image can start 50 container instances using barely more disk than one.

Other union/storage drivers you may encounter (mostly historical or special-purpose):

| Driver | Notes |
|---|---|
| `overlay2` | The modern default. Efficient, kernel-native. Use this. |
| `aufs` | Older out-of-tree union FS; predates OverlayFS in Docker. Deprecated. |
| `devicemapper` | Block-level CoW via thin provisioning. Used on old RHEL; complex, problematic. Deprecated. |
| `btrfs` / `zfs` | Use the filesystem's native snapshot/CoW. Powerful but ties you to that FS. |
| `vfs` | No CoW at all — full copy per layer. Only for testing or where overlay is unavailable. Very slow, huge disk. |

### 2.4 cgroups — the resource-limiting primitive

Namespaces control what a process can *see*; **cgroups** (control groups) control what a process can *use*. A cgroup is a kernel mechanism to **group processes and meter, limit, and prioritize their consumption of resources** — CPU time, memory, block-I/O bandwidth, the number of PIDs, network priority, and more.

cgroups are exposed as a **pseudo-filesystem** (a filesystem backed by kernel data structures, not disk) mounted under `/sys/fs/cgroup`. You create a cgroup by `mkdir`-ing a directory; you add a process by writing its PID into that directory's `cgroup.procs` file; you set a limit by writing a number into a controller file. There are two generations:

**cgroups v1** — a *separate hierarchy per controller*. Each resource (cpu, memory, blkio, pids, …) has its own independent tree, and a process can be placed at different positions in each. Flexible but a coordination nightmare; controllers don't cooperate well (e.g., memory and I/O limits couldn't be jointly enforced for writeback). Mounted as multiple directories: `/sys/fs/cgroup/memory`, `/sys/fs/cgroup/cpu`, etc.

**cgroups v2** — a *single unified hierarchy* for all controllers (stable since kernel 4.5; the default on modern distros like Fedora, RHEL 9, Ubuntu 22.04+, Debian 11+). One tree; you enable controllers per-subtree via `cgroup.subtree_control`. Cleaner semantics, better resource coordination, **pressure stall information (PSI)**, and improved support for rootless/unprivileged containers. Mounted as a single `/sys/fs/cgroup`.

The controllers that matter for containers (v2 names):

| Controller | Limits/meters | Key v2 files |
|---|---|---|
| **cpu** | CPU time (bandwidth + weight) | `cpu.max` (quota/period), `cpu.weight` (proportional share), `cpu.stat` |
| **memory** | RAM + swap usage | `memory.max` (hard limit), `memory.high` (soft throttle), `memory.swap.max`, `memory.current`, `memory.stat`, `memory.events` |
| **io** | Block-device bandwidth/IOPS | `io.max`, `io.weight`, `io.stat` |
| **pids** | Number of processes/threads | `pids.max`, `pids.current` |
| **cpuset** | Pin to specific CPUs/NUMA nodes | `cpuset.cpus`, `cpuset.mems` |

Two crucial semantic details a senior engineer must know cold:

- **CPU "limit" is a quota, not a core count.** `cpu.max` is written as `"<quota> <period>"` in microseconds, e.g. `200000 100000` means "this group may use 200 ms of CPU per 100 ms of wall-clock," i.e., the equivalent of **2 full cores**. When a container exhausts its quota within a period, the kernel **throttles** it — every runnable thread is stopped until the next period begins. This causes the dreaded CPU-throttling latency spikes. (More in §6 and §7.)
- **Memory "limit" is enforced by killing.** When a cgroup hits `memory.max` and cannot reclaim enough, the kernel **OOM-kills** a process *inside that cgroup* (cgroup-scoped OOM). The container's PID 1 dying typically terminates the container. This is the famous "exit code 137" (128 + signal 9, SIGKILL). Crucially, **container memory limits are the JVM's blind spot historically** — see §7.6.

### 2.5 Putting the primitives together: anatomy of "running a container"

So when you type `docker run nginx`, here is the conceptual sequence (we trace the *real* one in §3):

1. **Image resolution & pull.** The runtime checks if the `nginx` image (its layers) exist locally; if not, it pulls the layers from a registry. Each layer is content-addressed by a SHA-256 digest.
2. **Filesystem assembly.** It stacks the read-only image layers as OverlayFS lowerdirs, creates a fresh upperdir + workdir, and produces a merged root filesystem.
3. **Namespace creation.** It `clone()`s a process into new PID/NET/MNT/UTS/IPC (and maybe USER) namespaces.
4. **cgroup placement.** It creates a cgroup, applies CPU/memory/pids limits, and assigns the new process to it.
5. **Root pivot.** Inside the mount namespace, it `pivot_root`s into the merged filesystem, mounts `/proc`, `/sys`, `/dev`, etc.
6. **Security tightening.** It drops Linux capabilities, applies a seccomp filter, sets up AppArmor/SELinux labels, drops to a non-root user if configured.
7. **Exec.** It `execve`s the image's entrypoint (`nginx -g 'daemon off;'`). That process is now PID 1 inside its namespace.

That is the whole thing. No VM, no guest OS, no hypervisor. Just a carefully-dressed process.

---

## 3. How it works internally

This is the heart of the chapter. We will trace the lifecycle end to end, then drill into each subsystem with the actual syscalls, files, and state transitions.

### 3.1 The runtime stack: who calls whom

When you run a container in production (especially under Kubernetes), there is a *stack* of programs, each with a narrow job. Understanding the boundaries between them is essential for debugging.

```
kubelet  (Kubernetes node agent)
   │  speaks CRI (gRPC) over a unix socket
   ▼
containerd  (high-level runtime: image pull, snapshot mgmt, lifecycle)
   │  spawns one shim per container
   ▼
containerd-shim-runc-v2  (keeps the container's stdio + reaps it,
   │                       lets containerd restart without killing containers)
   ▼
runc  (low-level OCI runtime: makes the syscalls, then execs your process, then exits)
   │  clone()/unshare(), cgroup setup, pivot_root, capabilities, seccomp...
   ▼
YOUR PROCESS  (PID 1 in its namespaces — e.g., the JVM)
```

Let's define each layer for a newcomer:

- **runc** — the reference **low-level OCI runtime**. It is a small Go binary that takes an OCI **runtime bundle** (a root filesystem directory + a `config.json` spec) and does the actual kernel work: create namespaces, set up cgroups, pivot root, apply security, then `exec` the process. runc then *exits* — it does not stay running. (Alternatives: `crun`, a faster C implementation; `runsc`/gVisor and `kata-runtime`, which add stronger isolation.)
- **containerd-shim** — a tiny long-lived parent process for each container. Why does it exist? So that the heavyweight `containerd` daemon can be restarted (upgraded, crashed) **without killing your containers**. The shim holds the container's stdout/stderr pipes, becomes the subreaper for the container's process tree, reports exit status back to containerd, and keeps the container alive across containerd restarts.
- **containerd** — the **high-level runtime daemon**. It manages the full lifecycle: pulling and storing images, managing **snapshots** (the OverlayFS layer assembly), creating containers, and talking to shims. It exposes a gRPC API. It is what Docker uses under the hood and what Kubernetes talks to directly today.
- **CRI (Container Runtime Interface)** — a **gRPC API** that Kubernetes' node agent (**kubelet**) uses to talk to *any* compliant runtime, so Kubernetes is not tied to Docker. containerd implements CRI directly (via its `cri` plugin); CRI-O is an alternative runtime built specifically to implement CRI.
- **Docker (the `dockerd` daemon + `docker` CLI)** — the original developer-facing toolchain. Modern Docker delegates the real work *down to containerd and runc*; `dockerd` adds the build engine, networking, volumes, the friendly CLI, and the REST API. (Historical note: Kubernetes used to talk to Docker via a "dockershim" adapter; this was **removed in Kubernetes 1.24** (2022). Kubernetes now talks to containerd/CRI-O directly. This caused much "Docker is deprecated in Kubernetes" panic — it only meant the *node runtime*, not that your Docker-built images stopped working; OCI images are universal.)

### 3.2 Lifecycle state machine

An OCI container moves through a defined state machine:

```
(no container)
     │  create
     ▼
 [created]  ── runc create: bundle parsed, namespaces+cgroups set up,
     │                       process forked but BLOCKED before exec
     │  start
     ▼
 [running]  ── runc start: signals the blocked process to execve() the entrypoint
     │
     │  (process exits / is killed)
     ▼
 [stopped]  ── exit status captured by the shim
     │  delete
     ▼
(no container)  ── cgroup removed, namespaces torn down, upperdir handling per policy
```

The `created → running` split is deliberate and powerful: between `create` and `start`, everything is set up but your code hasn't run yet, so tooling (e.g., for network setup, CNI plugins in Kubernetes) can attach to the namespaces first.

### 3.3 Deep trace: what `runc` actually does

Here is the ordered internal workflow runc performs (slightly simplified but faithful):

1. **Parse the bundle.** Read `config.json` (the OCI runtime spec): which namespaces, cgroup limits, mounts, capabilities, seccomp profile, the rootfs path, the process args/env/user.
2. **First clone.** runc re-executes itself as a child via `clone()` with the requested `CLONE_NEW*` flags. (It uses a small C constructor called **`nsexec`** that runs before the Go runtime starts, because the Go runtime is multithreaded and `CLONE_NEWUSER`/`setns` have constraints that require single-threaded execution at that moment.)
3. **User namespace + ID maps.** If a user namespace is requested, write the UID/GID mappings to `/proc/<pid>/uid_map` and `gid_map` so that, e.g., container-root (UID 0) maps to host UID 100000.
4. **cgroup setup.** Create the cgroup directory under `/sys/fs/cgroup/...`, write the limits (`cpu.max`, `memory.max`, `pids.max`, …), and write the child's PID into `cgroup.procs` so the process and all its descendants are accounted and capped.
5. **Mount setup inside the mount namespace.** Make the propagation private (`mount --make-rprivate /`), then prepare the new root, bind/mount the configured volumes, and mount the virtual filesystems: `/proc` (process info), `/sys` (kernel objects), `/dev` (a minimal devtmpfs with a few allowed devices), `/dev/pts` (pseudo-terminals), `/dev/shm`, etc.
6. **pivot_root.** Switch the root to the merged image filesystem and unmount the old root so the container truly cannot see the host FS.
7. **Drop capabilities.** Linux splits root's omnipotence into ~40 discrete **capabilities** (e.g., `CAP_NET_BIND_SERVICE` to bind ports <1024, `CAP_SYS_ADMIN` the "almost-root" mega-capability). runc drops every capability not in the allowed set. Default Docker keeps a curated ~14; everything else is removed.
8. **Apply seccomp.** Install a **seccomp-bpf** filter (a kernel-enforced syscall allowlist/denylist) so the process can only make approved syscalls. Docker's default profile blocks ~40+ dangerous syscalls (e.g., `mount`, `reboot`, `kexec_load`).
9. **Apply MAC.** Apply the AppArmor profile or SELinux label (Mandatory Access Control — a kernel policy layer enforcing what files/operations a process may touch, independent of file permissions).
10. **No-new-privileges.** Set the `no_new_privs` bit so the process can never gain privileges via setuid binaries.
11. **Set the user.** `setresuid`/`setresgid` to the configured non-root UID/GID if specified.
12. **Block at the sync point.** The child now waits. runc's parent reports "created."
13. **On `start`:** runc signals the child, which finally calls `execve()` on the entrypoint. Your program is now running as PID 1 in its namespaces, capped by its cgroup, jailed in its rootfs.

### 3.4 Networking internals (the NET namespace in practice)

A fresh network namespace has only a `lo` (loopback) interface, down. To give a container connectivity, the runtime (or CNI plugin in Kubernetes) typically:

1. Creates a **veth pair** — a virtual ethernet cable with two ends (`veth0`/`veth1`). One end stays in the host namespace; the other is *moved into the container's NET namespace* and renamed `eth0`.
2. Attaches the host-side end to a **bridge** (a virtual L2 switch, e.g., `docker0`) or to the CNI's chosen datapath.
3. Assigns the container an IP, sets up routes, and configures NAT/iptables on the host so the container can reach the outside world and receive port-forwarded traffic.

This is why `docker exec <c> ip addr` shows `eth0` with a private IP, and why two containers can both listen on `:8080` — they're in different network namespaces, so the port spaces don't collide. **Kubernetes** delegates all of this to **CNI (Container Network Interface)** plugins (Calico, Cilium, Flannel, etc.) and gives every Pod its own network namespace shared by all containers in the Pod.

### 3.5 The "pause" container (Kubernetes-specific internal detail)

In Kubernetes, a **Pod** is a group of containers sharing the same network and IPC namespaces. How? Kubernetes starts a tiny, do-nothing **pause container** (also called the "infra container") first. The pause container's only job is to *hold the namespaces open* (it just sleeps). All the real containers in the Pod then `setns()` into the pause container's network and IPC namespaces. If an app container crashes and restarts, the namespaces (and thus the Pod's IP) survive because the pause container never dies. This is a beautiful, practical use of the primitives from §2.

### 3.6 OCI image format & layers (internal structure)

An **OCI image** (the standard format, evolved from the Docker image format) is not a single file — it's a content-addressed bundle of JSON documents and tarballs. Structure:

- **Image Index** (optional, "manifest list" / "fat manifest"): maps platforms (`linux/amd64`, `linux/arm64`) to specific manifests. This is how one tag like `eclipse-temurin:21` works on both x86 and ARM (multi-arch).
- **Image Manifest**: a JSON document listing, by SHA-256 digest, the **config** blob and the ordered list of **layer** blobs for one platform.
- **Image Config**: JSON describing how to run the image — entrypoint, cmd, env, working dir, exposed ports, the user, and an *ordered list of layer diff-IDs* plus the build **history** (one entry per Dockerfile instruction).
- **Layers**: each layer is a **gzipped tarball** of the *filesystem changes* introduced by one build step (added/modified/deleted files; deletions are recorded as whiteout files). Layers are content-addressed: the digest *is* the identity, so identical layers are stored and transferred once.

Two digests you'll see and must distinguish:

- **diffID** = SHA-256 of the *uncompressed* layer tar. Used to compute the filesystem identity (chainID) and dedupe on disk.
- **digest** = SHA-256 of the *compressed* blob as stored/transferred in the registry. Used in manifests and `docker pull image@sha256:...`.

**chainID**: layers are stacked, so the on-disk identity of "layers 1..N applied in order" is a rolling hash (`chainID(N) = sha256(chainID(N-1) + " " + diffID(N))`). This is how the runtime knows whether it can reuse an already-unpacked snapshot.

When you `docker pull`, the client fetches the manifest, sees which layer digests it lacks, downloads only those, verifies each against its digest, decompresses, and registers each as an OverlayFS lower layer. **Layer sharing** across images is automatic: if `myapp:v1` and `myapp:v2` share the same base OS and JVM layers, those are stored once.

---

## 4. The complete toolkit

This section enumerates the commands, files, flags, and APIs you actually use. Defaults given are for common modern setups; flag anything version-specific.

### 4.1 Raw Linux tools (no Docker) — to *see* the primitives

| Tool / file | Purpose | Key options / fields |
|---|---|---|
| `unshare` | Run a program in new namespaces | `--pid --fork --mount-proc` (PID ns), `--net`, `--uts`, `--ipc`, `--user --map-root-user`, `--mount` |
| `nsenter` | Enter an existing process's namespaces | `-t <pid> -n` (net), `-m` (mnt), `-p` (pid), `-u`, `-i`, `-a` (all) |
| `ip netns` | Manage named network namespaces | `add`, `exec <ns> <cmd>`, `list` |
| `lsns` | List namespaces on the system | `-t net`, `-p <pid>` |
| `/proc/<pid>/ns/` | The namespace handles of a process | symlinks: `pid`, `net`, `mnt`, `uts`, `ipc`, `user`, `cgroup` |
| `chroot` | Change root dir (weak isolation) | `chroot <newroot> <cmd>` |
| `mount -t overlay` | Manually assemble an overlay FS | `-o lowerdir=...,upperdir=...,workdir=...` |
| `/sys/fs/cgroup/...` | The cgroup pseudo-FS | write PIDs to `cgroup.procs`; write limits to controller files |
| `capsh` | Inspect/drop capabilities | `--print`, `--drop=cap_sys_admin --` |
| `getcap`/`setcap` | File capabilities | `setcap cap_net_bind_service+ep ./bin` |

### 4.2 OCI / low-level runtimes

| Tool | Purpose | Notable commands |
|---|---|---|
| `runc` | Reference OCI low-level runtime | `runc spec` (generate `config.json`), `runc create`, `runc start`, `runc list`, `runc exec`, `runc kill`, `runc delete` |
| `crun` | Faster C OCI runtime (drop-in) | same OCI verbs; lower latency, lower memory |
| `runsc` (gVisor) | Userspace kernel for strong isolation | configured as a runtime; intercepts syscalls |
| `kata-runtime` | Lightweight VM-per-container isolation | each container gets a microVM |

### 4.3 High-level runtimes / daemons

| Tool | Purpose | Notable commands |
|---|---|---|
| `containerd` | High-level runtime daemon | (daemon) — config at `/etc/containerd/config.toml` |
| `ctr` | Low-level containerd debug CLI | `ctr images pull/ls`, `ctr run`, `ctr task ls/kill`, `ctr c ls` |
| `nerdctl` | Docker-compatible CLI for containerd | nearly 1:1 with `docker` (`nerdctl run`, `build`, `compose`) |
| `crictl` | CRI-level debug CLI (Kubernetes nodes) | `crictl ps`, `crictl pods`, `crictl images`, `crictl logs`, `crictl inspect` |
| `CRI-O` | CRI-only runtime for Kubernetes | configured via kubelet/`crio.conf` |

### 4.4 Docker / developer CLI

| Command | Purpose | Key flags (with defaults/notes) |
|---|---|---|
| `docker run` | Create + start a container | `-d` detach; `-p host:ctr` publish port; `-e K=V` env; `--name`; `--rm` auto-remove; `-v src:dst` volume; `--restart` (no/on-failure/always); `--read-only` |
| | resource limits | `--memory 512m` (sets `memory.max`); `--memory-swap`; `--cpus 1.5` (sets `cpu.max` quota); `--cpu-shares` (weight, default 1024); `--cpuset-cpus 0-3`; `--pids-limit` |
| | security | `--user 1000:1000`; `--cap-drop ALL --cap-add NET_BIND_SERVICE`; `--security-opt no-new-privileges`; `--security-opt seccomp=profile.json`; `--read-only`; `--privileged` (DANGER: disables most isolation) |
| `docker build` | Build image from Dockerfile | `-t name:tag`; `-f Dockerfile`; `--target <stage>` (multi-stage); `--build-arg`; `--platform linux/amd64,linux/arm64` (with buildx); `--no-cache`; `--cache-from`/`--cache-to` |
| `docker exec` | Run a command in a running container | `-it` interactive TTY; uses `setns` under the hood |
| `docker ps` / `docker inspect` | List / introspect | `inspect` dumps the full config incl. the merged OverlayFS dirs, cgroup limits |
| `docker logs` | Stream stdout/stderr | `-f` follow; `--since`; `--tail` |
| `docker stats` | Live resource usage | shows cgroup CPU%/mem against limit |
| `docker images` / `docker history` | List images / inspect layers | `history` shows per-layer size & the instruction that created it |
| `docker system df` / `prune` | Disk usage / cleanup | reclaims dangling images, stopped containers, unused volumes |
| `docker buildx` | Modern BuildKit builder | multi-arch, better caching, secrets, `--mount=type=cache` |

### 4.5 Dockerfile instructions (the image build DSL)

| Instruction | Purpose | Notes that bite people |
|---|---|---|
| `FROM <img>[:tag] [AS stage]` | Base image / start a stage | Pin a digest for reproducibility; use `scratch` for an empty base |
| `RUN <cmd>` | Execute at build time → new layer | Each `RUN` = a layer. Chain with `&&` and clean in the *same* layer |
| `COPY src dst` / `ADD` | Copy build context into image | Prefer `COPY`; `ADD` also untars/fetches URLs (surprising). Use `--chown` |
| `WORKDIR` | Set working dir | Creates the dir if missing |
| `ENV K=V` | Environment variable (persists at runtime) | |
| `ARG K[=default]` | Build-time variable | Not present at runtime unless re-declared |
| `EXPOSE <port>` | Document a port | Does NOT publish; purely metadata |
| `USER <uid>[:gid]` | Run as this user | Prefer a numeric UID for Kubernetes `runAsNonRoot` checks |
| `ENTRYPOINT ["exec","form"]` | The main process | Use exec form (JSON array) so signals reach PID 1 directly |
| `CMD ["..."]` | Default args / command | Overridable on `docker run`; combine with ENTRYPOINT |
| `HEALTHCHECK` | Container-level liveness probe | Ignored by Kubernetes (uses its own probes) |
| `VOLUME` | Declare a mount point | Anonymous volumes can surprise; often better to mount explicitly |
| `LABEL` | Metadata key/values | Use for provenance (`org.opencontainers.image.*`) |
| `STOPSIGNAL` | Signal sent on stop | Default `SIGTERM` |

### 4.6 The OCI spec files

| File | Spec | Contents |
|---|---|---|
| `config.json` | OCI **runtime** spec | The runtime bundle's instructions: namespaces, cgroup limits, mounts, capabilities, seccomp, rootfs path, process args/env/user |
| `manifest.json` / image manifest | OCI **image** spec | Config digest + ordered layer digests for one platform |
| image `config` blob | OCI image spec | entrypoint/cmd/env/user/exposed ports + diffID list + build history |
| index / manifest list | OCI image spec | platform → manifest mapping (multi-arch) |

---

## 5. Code examples by use case

These are deliberately *different* scenarios, not variations of one. Comments explain the non-obvious lines.

### 5.1 Build a container from scratch using only Linux tools (to prove "it's just a process")

```bash
#!/usr/bin/env bash
# Demonstrates: a "container" is namespaces + cgroups + a new root. No Docker.
set -euo pipefail

# 1) Get a minimal root filesystem (Alpine mini rootfs tarball)
mkdir -p /tmp/myroot
curl -fsSL https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-minirootfs-3.20.0-x86_64.tar.gz \
  | tar -xz -C /tmp/myroot              # this is exactly what an "image layer" is: a tar of a filesystem

# 2) Run a shell in fresh PID + mount + UTS + net namespaces, with /tmp/myroot as root.
#    --fork + --pid + --mount-proc: the shell becomes PID 1 and gets its own /proc.
sudo unshare --pid --fork --mount-proc --uts --net --mount \
  chroot /tmp/myroot /bin/sh -c '
    hostname container-demo          # only changes hostname INSIDE the UTS namespace
    echo "Inside: my PID is $$"       # prints 1 — we are PID 1 in our PID namespace
    ps aux                            # sees only our own processes
    ip addr                           # only loopback: empty network namespace
  '
# Back on the host, `ps aux | grep sh` would still show this shell as an ordinary process.
```

### 5.2 Apply a cgroup v2 memory + CPU limit by hand and watch the OOM kill

```bash
#!/usr/bin/env bash
# Demonstrates cgroup v2 directly: cap a process at ~50MB RAM and 0.5 CPU, then trip the OOM killer.
set -euo pipefail
CG=/sys/fs/cgroup/demo
sudo mkdir -p "$CG"

# Enable controllers in the parent (required in cgroup v2 before children can use them)
echo "+cpu +memory" | sudo tee /sys/fs/cgroup/cgroup.subtree_control >/dev/null

echo $((50*1024*1024)) | sudo tee "$CG/memory.max" >/dev/null   # hard cap: 50 MiB
echo "50000 100000"   | sudo tee "$CG/cpu.max"    >/dev/null    # 50 ms per 100 ms = 0.5 CPU

# Launch a memory hog, place IT (and children) into the cgroup, then watch it die.
sudo bash -c "
  echo \$\$ > $CG/cgroup.procs        # move this shell into the cgroup
  # Allocate ~200MB; the kernel will OOM-kill us when we cross 50MB
  python3 -c 'a=bytearray()
while True:
    a += bytearray(10*1024*1024)     # +10MB each loop'
" || echo "Process was killed (likely cgroup OOM, exit 137)."

cat "$CG/memory.events"               # shows oom / oom_kill counters
sudo rmdir "$CG"                      # cleanup (must have no processes left)
```

### 5.3 A production-grade multi-stage Dockerfile for a Spring Boot service

```dockerfile
# syntax=docker/dockerfile:1.7
# ---- Stage 1: build (heavy: JDK + Maven + sources) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Copy ONLY the build descriptors first so dependency resolution is cached
# (the layer below is reused unless pom.xml changes — huge speedup).
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -o=false dependency:go-offline

# Now copy sources and build. Source changes don't invalidate the dependency layer above.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -DskipTests package \
 && java -Djarmode=layertools -jar target/*.jar extract --destination /layers
# ^ Spring Boot "layered jars": split deps / loader / app into separate dirs so
#   Docker layers map to change frequency (deps rarely change, app changes often).

# ---- Stage 2: runtime (tiny: JRE only, no compiler, no Maven, no sources) ----
FROM eclipse-temurin:21-jre AS runtime
# Create an unprivileged user; never run as root.
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app

# Copy the extracted layers in change-frequency order (best cache reuse).
COPY --from=build /layers/dependencies/         ./
COPY --from=build /layers/spring-boot-loader/   ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/          ./

USER 10001:10001                # numeric UID satisfies Kubernetes runAsNonRoot
EXPOSE 8080
# Exec form => the JVM is PID 1 and receives SIGTERM directly for graceful shutdown.
# These flags make the JVM read cgroup limits and behave well in a container (see §7.6).
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseContainerSupport", \
  "-XX:+ExitOnOutOfMemoryError", \
  "org.springframework.boot.loader.launch.JarLauncher"]
```

Key points: the final image contains **no JDK, no Maven, no source code** — only a JRE and the app, drastically reducing size and attack surface. The layer ordering maximizes cache hits.

### 5.4 An even smaller image with a distroless base and a non-root user

```dockerfile
# Build stage omitted (same as 5.3). Runtime on Google's "distroless" base:
# distroless = base image with the JRE and its runtime deps, but NO shell, NO package
# manager, NO apt — almost nothing to exploit, and tiny.
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
# The :nonroot tag already runs as UID 65532, so no USER line needed.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
# Caveat: no shell means `docker exec -it ... sh` won't work — debug via ephemeral
# debug containers (kubectl debug) or a separate debug image.
```

### 5.5 Demonstrating that the JVM is now cgroup-aware (Java code)

```java
// Run this inside a container started with `--memory 512m --cpus 2`.
// On a modern JVM (10+, properly on 11+/17+/21) the numbers reflect the CONTAINER
// limits, not the host's. This is the single most important container-JVM behavior.
public class ContainerAwareness {
    public static void main(String[] args) {
        Runtime rt = Runtime.getRuntime();
        long maxBytes = rt.maxMemory();           // honors -XX:MaxRAMPercentage of cgroup memory.max
        int procs     = rt.availableProcessors(); // honors cgroup cpu.max quota (rounds up)

        System.out.printf("availableProcessors() = %d%n", procs);
        System.out.printf("Runtime.maxMemory()   = %d MB%n", maxBytes / (1024 * 1024));
        // Pre-Java-8u191 / without UseContainerSupport, these would report the HOST's
        // 64 cores and 256GB, the JVM would size its heap and thread pools for the host,
        // and the kernel would OOM-kill it the moment it touched the cgroup limit.
    }
}
```

### 5.6 Inspecting a running container's namespaces and cgroup from the host

```bash
# Find the host PID of a container's main process (works with Docker or containerd)
PID=$(docker inspect -f '{{.State.Pid}}' my-container)

# 1) Prove it has its own namespaces (compare inode numbers with the host's)
sudo ls -l /proc/$PID/ns                 # net/pid/mnt/... point to per-container inodes
sudo ls -l /proc/1/ns                    # the host's; different inodes => isolated

# 2) Jump into the container's network namespace from the host (no shell needed inside)
sudo nsenter -t $PID -n ip addr          # debug networking even on distroless images!

# 3) Read its effective cgroup limits straight from the kernel
CG=$(cat /proc/$PID/cgroup | sed 's/^0:://')   # cgroup v2 path
cat /sys/fs/cgroup$CG/memory.max
cat /sys/fs/cgroup$CG/cpu.max
cat /sys/fs/cgroup$CG/memory.events           # oom_kill count if it's been throttled to death
```

### 5.7 A rootless, capability-minimal `docker run` for hardened production

```bash
docker run -d --name api \
  --read-only \                                  # root FS is immutable
  --tmpfs /tmp:rw,size=64m \                      # writable scratch where actually needed
  --user 10001:10001 \                            # non-root
  --cap-drop ALL \                                # remove ALL capabilities...
  --cap-add NET_BIND_SERVICE \                    # ...then add back ONLY what's needed (port <1024)
  --security-opt no-new-privileges \              # can't escalate via setuid
  --security-opt seccomp=default.json \           # syscall allowlist
  --pids-limit 256 \                              # fork-bomb protection
  --memory 512m --memory-swap 512m \              # cap RAM, disable swap (swap=mem => no swap)
  --cpus 2 \
  --restart on-failure:5 \
  myorg/api:1.4.2@sha256:abc123...                # pin by digest for immutability
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **CPU throttling is the silent latency killer.** A `--cpus 1` limit (`cpu.max = 100000 100000`) does not mean "slow but smooth"; it means the cgroup gets 100 ms of CPU per 100 ms period and is then *hard-stopped* until the next period. A multithreaded JVM that briefly bursts (GC, JIT compilation, request spikes) can exhaust its quota in the first few ms of a period and then sit *frozen* for the rest — producing p99 latency spikes of tens of milliseconds even though average CPU is low. Diagnose via `cpu.stat`'s `nr_throttled` / `throttled_usec`. **Mitigations:** raise the limit, set requests==limits or remove the CPU *limit* (keep the request) in Kubernetes for latency-sensitive services, tune the CFS period, or use static CPU pinning (`cpuset`) for the most latency-critical pods.
- **First-byte / cold-start cost.** Image pull dominates cold start; keep images small and use layer caching and registry locality. OverlayFS copy-up makes the *first write* to a large base-layer file expensive — avoid mutating big files at runtime; use volumes for heavy writes.
- **Memory page-cache sharing** across containers from the same image is automatic with overlay2 — a real density win you lose if you use the `vfs` driver.

### 6.2 Correctness & concurrency

- **PID 1 and signals.** Your app runs as PID 1. PID 1 has special kernel semantics: signals without a registered handler are *not* applied with default actions, and PID 1 must **reap zombie children** (collect exited child processes) or they accumulate. If your entrypoint uses the *shell form* (`ENTRYPOINT java -jar app.jar`), a shell becomes PID 1 and may not forward `SIGTERM` to the JVM — so `docker stop`/Kubernetes graceful shutdown hangs until the 30 s kill timeout. **Fix:** always use exec form, and if your process spawns children that can be orphaned, add a lightweight init like **tini** (`docker run --init`, or `ENTRYPOINT ["/tini","--", ...]`).
- **Graceful shutdown.** On `SIGTERM` the JVM must stop accepting traffic, finish in-flight requests, then exit before the kill grace period. Wire Spring's graceful shutdown and a shutdown hook.

### 6.3 Memory

- **Set the heap relative to the cgroup limit, leave headroom for non-heap.** The JVM uses far more memory than the heap: metaspace, thread stacks (~512KB–1MB each), code cache, direct/native buffers (Netty!), and GC structures. If `-Xmx` (or `MaxRAMPercentage`) consumes the whole `memory.max`, the *non-heap* usage pushes total RSS over the limit and the kernel OOM-kills you (exit 137) with no Java `OutOfMemoryError` and no heap dump. **Best practice:** `-XX:MaxRAMPercentage=75.0` (leave ~25% for non-heap on typical services), and always set the container memory **request == limit** in Kubernetes to avoid noisy-neighbor eviction.
- **Disable or bound swap.** Swap inside a cgroup leads to thrashing; many production setups disable swap entirely.

### 6.4 Security (defense in depth)

| Control | What it does | Recommended posture |
|---|---|---|
| **Run as non-root** (`USER`, `runAsNonRoot`) | Limits blast radius of an app compromise | Always; numeric UID |
| **User namespaces** (rootless) | Container-root maps to unprivileged host UID | Strongly preferred |
| **Drop capabilities** | Remove root powers you don't need | `--cap-drop ALL`, add back minimal |
| **`no-new-privileges`** | Block setuid escalation | Always |
| **seccomp** | Syscall allowlist | Keep default; restrict further if possible |
| **AppArmor/SELinux** | MAC file/op policy | Enable; don't disable |
| **Read-only root FS** | Immutable container | Use + tmpfs for scratch |
| **Avoid `--privileged`** | It disables nearly all isolation | Never in prod; it's effectively host root |
| **Avoid mounting the Docker socket** | `/var/run/docker.sock` inside a container = host takeover | Never |
| **Scan images** | Find CVEs in layers | Trivy/Grype in CI; fail the build on criticals |
| **Pin by digest, sign images** | Supply-chain integrity | `@sha256:...`, cosign/Sigstore, SBOMs |

### 6.5 Observability

- **Logs:** write to **stdout/stderr** (the runtime captures them). Don't log to files inside the container.
- **Resource metrics:** scrape **cAdvisor** (built into kubelet) / cgroup files for CPU throttling, memory working set, OOM events. Alert on `container_cpu_cfs_throttled_periods_total` and memory near limit.
- **PSI (cgroup v2):** `cpu.pressure`, `memory.pressure`, `io.pressure` give "% of time stalled waiting for the resource" — far more actionable than raw utilization.

### 6.6 Cost & density

- Smaller images = faster pulls, less registry storage/egress, faster autoscaling. A distroless/JRE image (~80–200 MB) vs. a full-OS+JDK image (~600 MB–1 GB) directly affects scale-out speed and cost.
- Right-size requests/limits using observed usage; over-requesting wastes cluster capacity, under-requesting causes throttling/OOM.

### 6.7 Testing

- **Testcontainers** (Java library) spins up real dependencies (Postgres, Kafka, your own image) as containers inside JUnit tests — true integration tests with disposable infra. Idiomatic for the JVM ecosystem.
- Test image builds in CI; scan; run the image, hit the health endpoint, verify it runs as non-root (`docker inspect`/`id`).

### 6.8 Production hardening checklist

Multi-stage build → small/distroless base → non-root numeric UID → read-only root FS + tmpfs → drop all caps, add minimal → `no-new-privileges` → default+custom seccomp → pin base by digest → scan & sign → exec-form entrypoint → `--init`/tini if needed → set requests==limits → `MaxRAMPercentage` + container support → graceful SIGTERM handling → ship logs to stdout → monitor throttling & OOM.

### 6.9 Anti-patterns to avoid

- One giant `RUN` that installs build tools and never cleans them (bloats the image; deletes in later layers don't shrink it — whiteouts).
- Running as root; using `latest` tags; baking secrets into image layers (they're extractable from history!).
- Treating containers as pets (SSH in and patch); they should be immutable and replaceable.
- Putting databases' heavy state in the container writable layer instead of a volume.
- Shell-form ENTRYPOINT (breaks signals); `--privileged`; mounting the Docker socket.

---

## 7. Advanced topics & deep internals

### 7.1 cgroups v1 vs v2 — the subtle differences that bite

| Aspect | cgroups v1 | cgroups v2 |
|---|---|---|
| Hierarchy | One tree per controller (independent) | Single unified tree |
| Memory+swap | `memory.limit_in_bytes` + separate `memsw` | `memory.max` + `memory.swap.max` (cleaner) |
| CPU limit file | `cpu.cfs_quota_us` / `cpu.cfs_period_us` | `cpu.max` ("quota period") |
| CPU shares | `cpu.shares` (default 1024) | `cpu.weight` (1–10000, default 100) |
| Pressure (PSI) | No | Yes (`*.pressure`) |
| Rootless support | Poor | Good (with systemd delegation) |
| "no internal processes" rule | No | Yes (only leaf cgroups hold processes) |
| Default on modern distros | Legacy | **Default** (RHEL 9, Ubuntu 22.04+, Fedora, Debian 11+) |

**JVM gotcha:** the JVM detects which cgroup version is in use to read limits. Very old JVMs only understood v1; if your distro flipped to v2, an old JVM could mis-detect limits. Java 15+ understands v2; use a current JDK (17/21).

### 7.2 cgroup-aware JVM — the full history and the exact flags

This is the topic senior JVM engineers are most often burned by, so we go deep.

**The original problem (pre-2018).** The JVM sized two things from "the machine": the **default heap** (a fraction of total RAM) and **`availableProcessors()`** (which seeds GC thread counts, ForkJoinPool common pool size, `Runtime.availableProcessors()`-based thread pools, etc.). In a container, the JVM read the **host's** `/proc/meminfo` and `/proc/cpuinfo` — so a JVM in a 512 MB / 1-CPU container on a 256 GB / 64-core host thought it had 256 GB and 64 cores. Result: it set a huge heap and 64 GC threads, immediately blew past `memory.max`, and got OOM-killed (137) with no Java-level error.

**The fix timeline:**

- **Java 8u131 / 9 (2017):** experimental `-XX:+UseCGroupMemoryLimitForHeap` (clumsy, deprecated).
- **Java 8u191 / 10 (2018):** **`-XX:+UseContainerSupport`** added and **enabled by default**. The JVM now reads cgroup `memory.max` and `cpu.max`. Heap sizing via `-XX:InitialRAMPercentage`, `-XX:MinRAMPercentage`, `-XX:MaxRAMPercentage` (the old `-XX:{Initial,Max,Min}RAMFraction` are deprecated).
- **Java 11+:** mature; `availableProcessors()` honors the CPU quota (computed as `ceil(quota/period)`, with `cpu.shares` as a fallback hint; behavior of using shares was later refined and partly disabled by default because it caused under-provisioning).
- **Java 15+:** understands **cgroups v2**.
- **Java 17 / 21 (LTS):** the recommended baselines; container detection is solid.

**The exact flags to set today:**

| Flag | Effect | Recommended |
|---|---|---|
| `-XX:+UseContainerSupport` | Read cgroup limits | On by default (don't disable) |
| `-XX:MaxRAMPercentage=75.0` | Max heap = 75% of `memory.max` | Set explicitly; leave ~25% for non-heap |
| `-XX:InitialRAMPercentage` | Initial heap % | Optional; match Max for predictable allocation |
| `-XX:+ExitOnOutOfMemoryError` | Crash on heap OOM so the orchestrator restarts cleanly | Recommended |
| `-XX:+HeapDumpOnOutOfMemoryError` + `-XX:HeapDumpPath=/dump` | Capture heap dump (heap OOM only — *not* cgroup OOM) | Recommended |
| `-XX:ActiveProcessorCount=N` | Override detected CPU count | Use when quota rounding misbehaves |

**Critical nuance:** a **cgroup OOM-kill** (exceeding `memory.max`, exit 137) is *invisible to the JVM* — there is no `OutOfMemoryError`, no heap dump, no shutdown hook. Only a *heap* OOM (exceeding `-Xmx`) produces a Java `OutOfMemoryError`. So if you see 137s, your **total RSS** (heap + metaspace + threads + native/direct buffers + code cache) exceeded the cgroup limit — lower `MaxRAMPercentage` or raise the limit; don't just stare at heap graphs.

**`availableProcessors()` and thread pools.** Many libraries size pools as `availableProcessors() * k`. If the JVM correctly sees 2 (the cgroup quota) instead of 64, your pools are right-sized. With a *very* old JVM or `UseContainerSupport` off, you'd get 64-thread pools fighting over 2 cores → massive context-switch overhead. Always verify with §5.5.

### 7.3 Containers vs VMs — the precise tradeoff

| Dimension | Container | Virtual Machine |
|---|---|---|
| Isolation boundary | Shared host kernel (namespaces+cgroups) | Virtualized hardware + separate guest kernel |
| Security isolation strength | Weaker (kernel = shared attack surface) | Stronger (hypervisor boundary) |
| Startup time | ms–seconds | seconds–minutes |
| Overhead per instance | KB–MB | hundreds of MB (guest OS) |
| Density per host | hundreds–thousands | tens |
| Can run a different kernel/OS | No | Yes |
| Image size | MB | GB |
| Use when | microservices, density, CI/CD, dev parity | hostile multi-tenant, different OS, kernel-level workloads |

**The blended middle:** **Kata Containers** (each container in a lightweight microVM — VM isolation, container UX), **Firecracker** (AWS's minimal VMM powering Lambda/Fargate — boots a microVM in ~125 ms), and **gVisor** (a userspace kernel `runsc` that intercepts container syscalls, shrinking the host kernel attack surface without a full VM). These give you stronger isolation while keeping much of the container workflow.

### 7.4 OverlayFS deep behaviors

- **Inode/`d_type` quirks** and **rename-with-redirect** (`redirect_dir`) for moving directories across layers; **metacopy** to copy up metadata without copying file data (faster for chmod/chown-only changes).
- **`nodev`/page-cache sharing**, **lower layer immutability**, and the rule that **upperdir and workdir must be on the same filesystem**.
- **Why deletions don't shrink images:** whiteouts (§2.3). To actually shrink, restructure the build or `docker build --squash` (experimental) / use multi-stage so the bytes never enter the final layers.

### 7.5 Image layer caching internals (BuildKit)

Modern `docker build` uses **BuildKit**, which builds a **DAG** of the Dockerfile, caches each step by a content hash of (instruction + inputs), supports `--mount=type=cache` (persistent build caches like `~/.m2` that *don't* end up in the image), `--mount=type=secret` (secrets available only during a `RUN`, never baked into a layer), and concurrent stage execution. **Cache invalidation rule:** changing any instruction invalidates it *and all subsequent layers*. Hence: put rarely-changing steps (deps) early, frequently-changing steps (your app code) late.

### 7.6 Rootless containers — how UID 0 inside ≠ root outside

With a **user namespace**, the kernel maps container UIDs to host UIDs. The runtime writes `/proc/<pid>/uid_map` like `0 100000 65536` meaning "container UID 0 → host UID 100000, for 65536 IDs." So a process that is "root" inside has, on the host, the powers of unprivileged UID 100000 — it cannot touch host files it doesn't own, cannot load kernel modules, etc. This is what lets **Podman** and **rootless Docker** run entirely without daemon root, and is the strongest practical mitigation against container-escape-to-host-root.

### 7.7 Lesser-known behaviors

- **`/proc/self/cgroup`** reveals a container's cgroup path; **`/sys/fs/cgroup`** inside is virtualized by the cgroup namespace.
- **`docker run --init`** injects tini as PID 1 to reap zombies and forward signals.
- **`STOPSIGNAL`** and Kubernetes `terminationGracePeriodSeconds` (default 30 s) interact: after grace, SIGKILL.
- **Layer count limits:** historically ~125 layers max; keep it well under by chaining `RUN`s.
- **`tmpfs`/`emptyDir` memory medium** counts against the cgroup memory limit — a sneaky OOM source.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Runtime choice

| Need | Pick |
|---|---|
| Local dev, build images, simple UX | **Docker** (Desktop / Engine) |
| Kubernetes node runtime, lean | **containerd** (default) or **CRI-O** |
| Drop-in faster/lighter low-level runtime | **crun** instead of runc |
| Strong isolation for untrusted code | **gVisor** (runsc) or **Kata Containers** |
| Rootless, daemonless, dev/CI | **Podman + Buildah** |

### 8.2 Base image choice

| Base | Size | Has shell/pkg mgr? | Use when |
|---|---|---|---|
| `scratch` | ~0 | No | Static Go binaries; nothing else needed |
| `distroless` | tiny | No | Production JVM/Go/Python; max security |
| `alpine` | ~5 MB | Yes (musl libc!) | Small + need a shell; beware musl vs glibc subtle bugs in JNI/native libs |
| `debian-slim`/`ubuntu` | ~30–80 MB | Yes (glibc) | Max compatibility, native deps, easy debugging |
| `eclipse-temurin:*-jre` | ~80–180 MB | Yes | Standard JVM runtime, well-supported |

**Alpine + JVM caution:** Alpine uses **musl** libc, not glibc; some native libraries and certain JVM builds historically had issues. Use a glibc-based slim image or a JVM build explicitly compiled for musl (`temurin:*-alpine`) if you go Alpine.

### 8.3 Containers vs VMs vs bare metal — use-when rules

- **Use containers when:** you want density, fast deploys, dev/prod parity, microservices, Kubernetes. Avoid when you need hostile-tenant isolation or a different kernel.
- **Use VMs when:** strong isolation, mixed OSes, kernel modules, regulatory boundaries. Avoid when density/speed dominate.
- **Use bare metal when:** maximum performance, specialized hardware, single dominant workload. Avoid when you need elasticity and packing.

### 8.4 CPU limit vs no-limit (Kubernetes)

- **Latency-sensitive service:** set CPU *request* but consider **no CPU limit** (avoid CFS throttling spikes), or request==limit with `cpuset` pinning.
- **Batch/throughput job:** set a limit to protect neighbors; throttling is acceptable.
- **Always** set memory request==limit to avoid eviction and get predictable OOM behavior.

---

## 9. Failure modes & debugging

### 9.1 Exit code 137 — cgroup OOM-kill

**Symptom:** container restarts; `kubectl describe pod` shows `OOMKilled`, exit 137; no Java `OutOfMemoryError` in logs. **Cause:** total RSS exceeded `memory.max` (heap + metaspace + threads + native/direct buffers + `tmpfs`). **Diagnose:** `cat /sys/fs/cgroup/.../memory.events` (oom_kill counter), `kubectl describe`, check `dmesg` on the node for `Memory cgroup out of memory`. **Fix:** lower `MaxRAMPercentage`, raise the limit, find native-memory leaks (NMT: `-XX:NativeMemoryTracking=summary` + `jcmd <pid> VM.native_memory`).

### 9.2 CPU throttling latency spikes

**Symptom:** p99 latency spikes, low average CPU. **Diagnose:** `cat .../cpu.stat` → rising `nr_throttled`/`throttled_usec`; metric `container_cpu_cfs_throttled_periods_total`. **Fix:** raise/remove CPU limit, more replicas, tune GC/JIT thread counts, `cpuset` pinning.

### 9.3 Container won't stop / slow `docker stop`

**Symptom:** `docker stop` / pod termination takes the full 30 s then SIGKILLs. **Cause:** shell-form ENTRYPOINT → shell is PID 1 and swallows/doesn't forward SIGTERM. **Fix:** exec-form ENTRYPOINT; use `--init`/tini; handle SIGTERM for graceful shutdown.

### 9.4 Zombie processes accumulate

**Symptom:** `<defunct>` processes pile up. **Cause:** PID 1 (your app) doesn't reap children. **Fix:** add tini (`--init`) or a proper init as PID 1.

### 9.5 Image is huge / pulls are slow

**Diagnose:** `docker history <image>` (per-layer sizes + the instruction). **Common cause:** build tools/caches left in a layer; deletes in later layers (whiteouts don't reclaim). **Fix:** multi-stage build, chain+clean in one `RUN`, smaller base, `--mount=type=cache`.

### 9.6 "Works on my machine" arch mismatch

**Symptom:** `exec format error` on the node. **Cause:** image built for `amd64`, node is `arm64` (or vice versa, common on Apple Silicon dev). **Fix:** build multi-arch with `docker buildx build --platform linux/amd64,linux/arm64 --push`.

### 9.7 Networking: container can't reach a service / port conflicts

**Diagnose from the host:** `nsenter -t <pid> -n ip addr / ip route / ss -tlnp` to inspect inside the container's net namespace even on distroless. Check bridge/iptables NAT, DNS (`/etc/resolv.conf`), and in Kubernetes the CNI plugin logs and `kubectl get endpoints`.

### 9.8 Permission denied after going non-root

**Symptom:** app can't write to a directory / bind a low port. **Cause:** files owned by root, or port <1024 without `CAP_NET_BIND_SERVICE`. **Fix:** `COPY --chown`, write to a writable volume/tmpfs, add only the needed capability, or listen on a high port and map it.

### 9.9 Real-world incident patterns

- **The "64-core ghost":** legacy JVM in a 2-CPU container spawned 64 GC/FJP threads, context-thrashing → fixed by upgrading the JDK / `UseContainerSupport`.
- **Netty direct-buffer OOM:** heap looked fine (`-Xmx` respected) but off-heap direct buffers pushed RSS over `memory.max` → exit 137 with no Java error; fixed by bounding `-XX:MaxDirectMemorySize` and lowering `MaxRAMPercentage`.
- **Secret-in-layer leak:** a `RUN curl -H "Authorization: $TOKEN"` baked the token into image history → leaked via `docker history`/registry; fixed with BuildKit `--mount=type=secret`.

### 9.10 Debugging toolbox quick reference

`crictl ps/logs/inspect` (Kubernetes node), `docker stats/inspect/history/logs`, `nsenter`/`lsns`, cgroup files (`memory.events`, `cpu.stat`, `*.pressure`), `dmesg` (OOM), `jcmd`/`jstat`/NMT (JVM internals), `kubectl debug` ephemeral containers (debug distroless), Trivy/Grype (CVE scan).

---

## 10. Interview drill

**Q1. Is a container a lightweight VM? Explain precisely.**
*Model answer:* No. A VM virtualizes hardware via a hypervisor and runs a full guest OS with its own kernel. A container is just a host process that the kernel isolates with **namespaces** (private view of PIDs, network, mounts, hostname, IPC, user IDs) and limits with **cgroups** (CPU/memory/IO/PID caps), with a layered root filesystem (OverlayFS). It shares the host kernel — no guest OS, no virtual hardware.
- *Probe: So what's the security implication?* A shared kernel is a shared attack surface; a kernel exploit can escape all containers. VMs have a stronger hypervisor boundary. Mitigations: user namespaces, seccomp, gVisor, Kata.
- *Probe: Why do containers start so fast?* No kernel to boot and no hardware to initialize — it's just `clone()` + cgroup setup + `execve()`.

**Q2. Walk me through what happens, step by step, when a container starts.**
*Model answer:* Resolve & pull image layers (content-addressed) → assemble OverlayFS (RO lowers + RW upper + work → merged) → `clone()` into new namespaces → create cgroup and apply limits, add PID → set up mounts, `pivot_root` into the merged FS, mount `/proc`,`/sys`,`/dev` → drop capabilities, apply seccomp/AppArmor, `no_new_privs`, drop to non-root → `execve()` the entrypoint as PID 1.
- *Probe: Where do runc, containerd, and the shim fit?* runc does the syscalls and exits; the shim is the long-lived per-container parent that keeps it alive across containerd restarts and reports exit; containerd is the high-level daemon (images, snapshots, lifecycle).
- *Probe: Why split `create` from `start`?* So tooling (CNI networking) can attach to the namespaces before the app runs.

**Q3. Explain namespaces and cgroups — and the difference.**
*Model answer:* Namespaces isolate *what a process can see* (PID/net/mnt/uts/ipc/user); cgroups limit *what it can use* (cpu/memory/io/pids). Both are independent kernel features; a container combines them.
- *Probe: What does the user namespace buy you?* Rootless containers: UID 0 inside maps to an unprivileged host UID, so container-root isn't host-root.
- *Probe: How does a Kubernetes Pod share a network namespace?* A pause/infra container holds the namespaces open; app containers `setns` into them, so they share the Pod IP and it survives container restarts.

**Q4. How does the JVM behave in a memory-limited container, and how do you configure it?**
*Model answer:* Modern JVMs (8u191/10+, `-XX:+UseContainerSupport` on by default) read cgroup `memory.max`. Set the heap relative to it with `-XX:MaxRAMPercentage=75.0`, leaving ~25% for non-heap (metaspace, threads, code cache, direct buffers). A *cgroup* OOM (RSS > limit) gives exit 137 with **no** Java `OutOfMemoryError` and no heap dump — only a *heap* OOM throws.
- *Probe: You see exit 137 but heap graphs look fine. Why?* Off-heap/native memory (direct buffers, threads, metaspace) pushed total RSS over the limit. Bound `MaxDirectMemorySize`, use NMT, lower `MaxRAMPercentage`.
- *Probe: How does cgroup CPU quota affect thread pools?* `availableProcessors()` reflects the quota (`ceil(quota/period)`), sizing GC/ForkJoin pools correctly — old JVMs saw host cores and over-threaded.

**Q5 (senior-signal). When would you NOT set a Kubernetes CPU limit, and why?**
*Model answer:* For latency-sensitive services. A CPU *limit* enables CFS hard throttling: when the quota is exhausted in a period, every thread is frozen until the next period, causing p99 spikes even at low average CPU. Setting only a *request* (guaranteed share) without a limit avoids throttling while still scheduling fairly; for the strictest latency, pin with `cpuset`. Always keep memory request==limit, though, for predictable OOM and no eviction.
- *Probe: Risk of no CPU limit?* Noisy-neighbor — a bug can starve co-located pods. Acceptable for trusted latency-critical workloads with monitoring; not for untrusted/batch.
- *Probe: How do you detect throttling?* `cpu.stat` `nr_throttled`/`throttled_usec`, `container_cpu_cfs_throttled_periods_total`.

**Q6 (senior-signal). Defend your base-image and build strategy for a Spring Boot service in prod.**
*Model answer:* Multi-stage: JDK+Maven build stage, JRE/distroless runtime stage so the final image has no compiler/Maven/source — smaller, smaller attack surface. Layer ordering for cache reuse (deps before app; Spring layered jars). Non-root numeric UID, read-only root FS + tmpfs, drop all caps, pin base by digest, scan in CI, sign with cosign.
- *Probe: Alpine or Debian-slim?* Debian-slim/glibc unless I've validated all native deps under musl; Alpine's musl can break JNI/native libs subtly. Or distroless (no shell → debug via ephemeral containers).
- *Probe: Why exec-form ENTRYPOINT?* So the JVM is PID 1 and receives SIGTERM directly for graceful shutdown; shell form breaks signal delivery.

**Q7. Why doesn't deleting files in a later Dockerfile layer shrink the image?**
*Model answer:* Layers are immutable and stacked via OverlayFS. "Deleting" a lower-layer file just writes a **whiteout** marker in the upper layer to hide it from the merged view; the original bytes still live in the earlier layer and ship in the image. To actually reduce size, don't add the bytes in the first place — chain+clean in the same `RUN`, or use multi-stage so they never enter the final image.
- *Probe: What is copy-up?* First write to a lower-layer file copies the whole file into the writable upper layer before modifying — costly for big files.
- *Probe: How are layers identified?* By SHA-256 (diffID uncompressed, digest compressed); identical layers are deduped/shared across images.

**Q8. What is the OCI, and what does "Docker is deprecated in Kubernetes" actually mean?**
*Model answer:* OCI (Open Container Initiative) standardizes the **image format**, the **runtime spec** (`config.json`), and a **distribution spec**. "Docker deprecated in Kubernetes" (dockershim removed in 1.24) only means kubelet no longer talks to the Docker daemon as a *node runtime*; it talks to containerd/CRI-O via CRI. Your Docker-*built* images are OCI-standard and run everywhere unchanged.
- *Probe: What is CRI?* The gRPC Container Runtime Interface kubelet uses to drive any compliant runtime.
- *Probe: Does Docker still use runc?* Yes — Docker → containerd → containerd-shim → runc.

**Q9. How do you achieve strong isolation when a shared kernel isn't enough?**
*Model answer:* gVisor (`runsc`, a userspace kernel intercepting syscalls), Kata Containers (microVM per container), Firecracker (minimal VMM, ~125 ms boot, powers Lambda/Fargate). Plus user namespaces, seccomp, MAC, dropping capabilities.
- *Probe: Tradeoff of gVisor?* Stronger isolation, but syscall interception adds overhead and some syscalls are unsupported.

**Q10 (senior-signal). A service intermittently gets OOMKilled under load with no Java error. Lead the investigation.**
*Model answer:* It's a cgroup OOM (RSS > `memory.max`), not a heap OOM. Confirm: `kubectl describe` (OOMKilled/137), node `dmesg`, `memory.events` oom_kill. Then decompose RSS: enable NMT (`-XX:NativeMemoryTracking=summary`, `jcmd VM.native_memory`) to see metaspace/threads/direct buffers/code cache; check thread count (pool sizing vs `availableProcessors()`), Netty/direct-buffer usage, and any in-container `tmpfs` counting against the limit. Fix: lower `MaxRAMPercentage`, bound `MaxDirectMemorySize`, cap thread pools, or raise the limit; set request==limit; add monitoring on working-set vs limit.
- *Probe: Why no heap dump?* SIGKILL from the kernel is uncatchable; only a heap `OutOfMemoryError` triggers `HeapDumpOnOutOfMemoryError`.
- *Probe: How prevent recurrence?* Alert on memory.working_set/limit ratio and PSI memory pressure; load-test to size limits.

---

## 11. Glossary

- **AppArmor** — Linux MAC system enforcing per-program access policy profiles.
- **Bridge (virtual)** — A software L2 switch (e.g., `docker0`) connecting container veth interfaces to the host network.
- **BuildKit** — Modern Docker build engine: DAG builds, advanced caching, build secrets/mounts.
- **Capability (Linux)** — A discrete slice of root's privileges (e.g., `CAP_NET_BIND_SERVICE`), grantable/droppable independently.
- **cgroup (control group)** — Kernel mechanism to group processes and limit/meter their resource usage.
- **cgroup OOM-kill** — Kernel killing a process because its cgroup hit `memory.max`; exit code 137, no Java error.
- **chroot** — Syscall/command changing a process's root directory (weak isolation).
- **CNI (Container Network Interface)** — Plugin spec Kubernetes uses to configure Pod networking.
- **Container** — A host process isolated by namespaces and limited by cgroups, with a layered root filesystem.
- **containerd** — High-level container runtime daemon (images, snapshots, lifecycle); implements CRI.
- **containerd-shim** — Long-lived per-container parent that survives containerd restarts and reports exit status.
- **Content-addressed** — Identified by the hash of the content (SHA-256), enabling dedup and integrity checks.
- **Copy-on-write (CoW)** — Share data until a write happens, then copy; OverlayFS uses per-file copy-up.
- **Copy-up** — OverlayFS copying a lower-layer file into the writable upper layer on first write.
- **CRI (Container Runtime Interface)** — gRPC API kubelet uses to drive any compliant runtime.
- **CRI-O** — A CRI-only runtime for Kubernetes.
- **`crictl`** — CRI-level debug CLI on Kubernetes nodes.
- **`crun`** — Fast C implementation of an OCI low-level runtime (runc alternative).
- **Distroless** — Minimal base image with a runtime but no shell/package manager.
- **Docker** — Developer-facing container toolchain (daemon + CLI + build); delegates to containerd/runc.
- **dockershim** — Removed (K8s 1.24) adapter that let kubelet talk to the Docker daemon.
- **Exit code 137** — 128 + 9 (SIGKILL); typically a cgroup OOM-kill.
- **Firecracker** — Minimal VMM booting microVMs in ~125 ms; powers AWS Lambda/Fargate.
- **`fork()` / `clone()`** — Syscalls creating a new process; `clone` with `CLONE_NEW*` flags creates namespaces.
- **gVisor (`runsc`)** — Userspace kernel intercepting container syscalls for stronger isolation.
- **Hypervisor** — Software mediating hardware so multiple guest OSes share one machine (VMs).
- **Image (OCI)** — Content-addressed bundle of read-only layers + config describing how to run them.
- **Image config** — JSON with entrypoint/cmd/env/user, layer diffIDs, and build history.
- **Image manifest** — JSON listing config + ordered layer digests for one platform.
- **Index / manifest list** — Maps platforms to manifests (multi-arch images).
- **IPC namespace** — Isolates System V IPC and POSIX message queues.
- **Kata Containers** — Runtime giving each container its own lightweight microVM.
- **kubelet** — The Kubernetes per-node agent that drives the container runtime via CRI.
- **Layer** — A tarball of filesystem changes from one build step; content-addressed and shareable.
- **MAC (Mandatory Access Control)** — Kernel policy layer (AppArmor/SELinux) constraining processes.
- **Mount namespace (MNT)** — Isolates the set of mount points / filesystem view.
- **Multi-stage build** — Dockerfile with multiple `FROM` stages so the final image excludes build tooling.
- **musl** — Lightweight libc used by Alpine (vs glibc); source of some native-lib compatibility issues.
- **Namespace** — Kernel feature partitioning a global resource so processes see isolated instances.
- **`nsenter`** — Tool to enter an existing process's namespaces (uses `setns`).
- **OCI (Open Container Initiative)** — Standards body defining image, runtime, and distribution specs.
- **OOM killer** — Kernel routine that kills a process to reclaim memory; cgroup-scoped under limits.
- **OverlayFS / overlay2** — Kernel union filesystem stacking RO lowers + RW upper into a merged view.
- **Pause / infra container** — Tiny Kubernetes container holding a Pod's namespaces open.
- **PID 1** — First process in a PID namespace; must reap zombies; special signal semantics.
- **PID namespace** — Isolates process IDs; container's main process is PID 1 inside.
- **`pivot_root`** — Syscall swapping the root mount, enabling true rootfs isolation.
- **Pod** — Kubernetes' smallest unit: one or more containers sharing network/IPC namespaces.
- **Podman** — Daemonless, rootless container engine (Docker-compatible CLI).
- **PSI (Pressure Stall Information)** — cgroup v2 metric: % time stalled waiting for cpu/memory/io.
- **Registry** — Server storing/distributing OCI images (Docker Hub, ECR, GHCR, etc.).
- **Rootless container** — Container run without host root, using user namespaces.
- **runc** — Reference low-level OCI runtime; makes the syscalls then execs the process.
- **Runtime bundle** — A rootfs directory + `config.json` an OCI runtime executes.
- **seccomp** — Kernel syscall filtering (allowlist/denylist) via BPF.
- **SELinux** — Label-based MAC system (common on RHEL/Fedora).
- **`setns()`** — Syscall to join an existing namespace.
- **Snapshot (containerd)** — A prepared, layered filesystem ready to be a container root.
- **Syscall** — Controlled entry point for userspace to request kernel services.
- **Testcontainers** — Java library running real dependencies as containers in tests.
- **tini** — Minimal init for PID 1: reaps zombies, forwards signals (`docker run --init`).
- **`tmpfs`** — RAM-backed filesystem; in containers it counts against the cgroup memory limit.
- **Union filesystem** — Filesystem presenting multiple stacked directories as one merged tree.
- **`unshare()`** — Syscall/command moving the caller into new namespaces.
- **`UseContainerSupport`** — JVM flag (default on, 8u191/10+) to read cgroup limits.
- **User namespace** — Maps UIDs/GIDs so container-root ≠ host-root.
- **UTS namespace** — Isolates hostname/domainname.
- **veth pair** — Virtual ethernet "cable" with two ends; connects a container's net namespace to the host.
- **VM (Virtual Machine)** — Full guest OS on virtualized hardware via a hypervisor.
- **Whiteout** — OverlayFS marker hiding a lower-layer file/dir (how deletions work in layers).

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **Container = process + namespaces (isolation) + cgroups (limits) + overlay layers (FS).** Shares host kernel. Not a VM.
- **Namespaces:** PID, NET, MNT, UTS, IPC, USER (+cgroup, time). Created via `clone`/`unshare`; join via `setns`/`nsenter`.
- **cgroups v2 files:** `memory.max` (hard, OOM=137), `memory.high` (soft), `cpu.max` ("quota period", throttles), `pids.max`. Watch `memory.events`, `cpu.stat`, `*.pressure`.
- **OverlayFS:** lowerdir (RO layers) + upperdir (RW) + workdir → merged. Copy-up on write; whiteouts on delete (deletes don't shrink images).
- **OCI image:** index → manifest → config + layers. diffID (uncompressed) vs digest (compressed). Layers shared/deduped by SHA-256.
- **Runtime stack:** kubelet → CRI → containerd → shim → runc → your process. Docker → containerd → runc. dockershim gone in K8s 1.24.
- **JVM in containers:** `-XX:+UseContainerSupport` (default on, 8u191/10+), `-XX:MaxRAMPercentage=75.0`, leave ~25% non-heap. cgroup OOM (137) ≠ heap OOM (no Java error/dump). `availableProcessors()` = `ceil(cpu.max quota/period)`.
- **Good images:** multi-stage, small/distroless base, non-root numeric UID, read-only FS, drop caps, `no-new-privileges`, seccomp, pin by digest, scan+sign, exec-form ENTRYPOINT, `--init` for zombies.
- **Containers vs VMs:** weaker isolation/shared kernel vs stronger/hypervisor; ms-start & MB vs sec-start & GB; hundreds vs tens per host. Middle ground: gVisor, Kata, Firecracker.
- **Top failures:** 137 (cgroup OOM → check RSS incl. native), CPU throttling (p99 spikes → `cpu.stat`), slow stop (shell-form ENTRYPOINT), zombies (no init), bloated image (`docker history`), arch mismatch (`buildx --platform`).
- **Debug:** `nsenter -t <pid> -n`, `lsns`, cgroup files, `crictl`/`docker stats/inspect/history`, `dmesg`, NMT/`jcmd`, `kubectl debug`.
- **Numbers:** CPU `1` core = `100000 100000`; default `cpu.weight` 100 (v2) / shares 1024 (v1); K8s `terminationGracePeriodSeconds` default 30 s; Firecracker boot ~125 ms; layer cap ~125.

### Self-test (no answers — recall actively)

1. Trace, syscall by syscall, what `runc` does between parsing the bundle and `execve`-ing your process. Where does the shim fit, and why does it exist separately from containerd?
2. You set `--memory 1g` and `-XX:MaxRAMPercentage=90`. Under load the container is OOMKilled (137) but there's no Java `OutOfMemoryError` and no heap dump. Explain exactly why, and list every memory region you'd account for to fix it.
3. Why does deleting a 300 MB file in a later Dockerfile `RUN` fail to shrink the image, and what two concrete techniques actually reduce the size? Tie your answer to OverlayFS internals.
4. A latency-sensitive service shows p99 spikes at 20% average CPU. Name the mechanism, the exact cgroup file to confirm it, and two different mitigations with their tradeoffs.
5. Explain how a Kubernetes Pod's containers come to share one IP, and why that IP survives an app container crash. Which namespaces are shared, and what kernel calls make sharing possible?
6. Contrast cgroups v1 and v2 in three concrete ways that affect a JVM workload, and state which JDK versions you'd require and why.
7. Give the precise difference between a *diffID* and a *digest*, and explain how the runtime decides it can reuse an already-unpacked layer.

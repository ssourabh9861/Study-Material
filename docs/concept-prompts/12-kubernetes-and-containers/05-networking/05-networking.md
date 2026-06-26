# Kubernetes Networking — A Definitive Engineering Chapter

> Reader profile: a senior backend developer (Java/JVM world) who wants to *fully master* Kubernetes networking — design with it, operate and debug it in production, teach it, and answer any interview question on it. We start from first principles and climb to deep internals (iptables/IPVS, eBPF, CNI, CoreDNS, NetworkPolicy enforcement, service mesh).

---

## 1. Overview & where it fits

### What it is
Kubernetes networking is the set of rules, abstractions, and software components that let containers running across many machines talk to each other and to the outside world **as if they were on one flat, friendly network**. It answers four distinct questions:

1. **Container-to-container inside a Pod** — how do two containers in the same Pod talk? (Answer: `localhost`, they share a network namespace.)
2. **Pod-to-Pod across the cluster** — how does a Pod on node A reach a Pod on node B? (Answer: every Pod has a unique, routable IP, and there is *no NAT* between Pods. This is the central rule.)
3. **Service-to-Pod (stable virtual endpoints)** — Pods are ephemeral (they die and get rescheduled with new IPs), so how do clients reach "the thing" reliably? (Answer: Services give a stable virtual IP / DNS name and load-balance to the live Pods behind them.)
4. **External-to-Service (north–south traffic)** — how does a user on the internet reach an app inside the cluster? (Answer: NodePort, LoadBalancer, and Ingress.)

> **Term — namespace (Linux), not Kubernetes Namespace.** Linux *namespaces* are a kernel feature that isolates a process's view of the system. A **network namespace** gives a process its own network stack: its own interfaces, routing table, ARP table, and firewall rules. Containers use network namespaces to get isolated networking. Do not confuse this with a Kubernetes *Namespace* (an API object that partitions cluster resources by name). When ambiguous below, we write "netns" for the Linux kind.

> **Term — NAT (Network Address Translation).** NAT rewrites the source and/or destination IP/port of packets as they cross a boundary (your home router does source-NAT so many devices share one public IP). NAT is the normal way to stretch scarce address space, but it *hides* the original IP and complicates connection tracking. Kubernetes deliberately avoids NAT *between Pods* so that the IP a Pod sees as the peer's address is the peer's *real* address.

### The problem it solves
Without a network model, every container platform reinvents ad-hoc port mapping, and apps must discover each other through brittle host:port conventions. Docker's default single-host model (a `docker0` bridge plus host port mapping with source-NAT) doesn't scale to a cluster: two containers on different hosts can't address each other directly, and ports collide. Kubernetes imposes a **uniform network model** so that application authors can pretend the cluster is one big LAN, and so that platform authors (the CNI plugins) can implement that illusion however their infrastructure allows.

### When you reach for it
You "reach for" Kubernetes networking constantly, mostly implicitly:
- You expose an app → you create a **Service** (and maybe an **Ingress**).
- You need DNS-based discovery → **CoreDNS** is already doing it.
- You need to lock down which Pods may talk to which → **NetworkPolicy**.
- You need mTLS, retries, traffic shifting, observability between services → a **service mesh** (Istio, Linkerd) layered on top.
- You pick the network substrate (overlay vs. native routing, eBPF vs. iptables) → you choose a **CNI plugin** (Calico, Cilium, Flannel, AWS VPC CNI, etc.).

### One-paragraph mental model
Think of the cluster as a single flat IP network. **Every Pod is a tiny VM with its own IP** (actually a network namespace wired up by a CNI plugin). Pods come and go, so **Services** put a stable, virtual front door (a ClusterIP) in front of a changing set of Pods; the kernel data plane (**kube-proxy** programming iptables/IPVS, or **eBPF** in Cilium) silently load-balances and redirects traffic from the virtual IP to a real Pod IP. **CoreDNS** turns service names into those virtual IPs. To let outside traffic in, you punch a hole with **NodePort/LoadBalancer** or, at L7, route by hostname/path with an **Ingress**. **NetworkPolicies** are the firewall. A **service mesh** is an optional L7 overlay (sidecar or per-node proxy) that adds identity, encryption, and fine-grained traffic control the core model doesn't provide.

---

## 2. Foundations from first principles

### 2.1 The Linux building blocks (start at zero)

Everything in Kubernetes Pod networking is built on a handful of Linux primitives. Master these and the rest is plumbing.

- **Network namespace (netns):** an isolated copy of the network stack. `ip netns list` shows them on a host. A Pod = one netns shared by all its containers (plus the per-container mount/PID isolation). Inside that netns there's a loopback (`lo`) and one end of a virtual cable.

- **veth pair (virtual ethernet):** a virtual patch cable with two ends. Packets in one end come out the other. The CNI plugin creates a veth pair, puts **one end inside the Pod's netns** (named `eth0` from the Pod's view) and leaves the **other end in the host's root netns** (named something like `vethXXXX`). That's literally the wire from the Pod to the node.

- **Bridge:** a virtual L2 switch (`brctl`/`ip link add type bridge`). Several host-side veth ends plug into a bridge (often `cni0` or `cbr0`) so Pods on the same node can talk at layer 2.

- **Routing table & FIB:** `ip route` shows how the kernel decides the next hop for a destination IP. Cross-node Pod traffic is ultimately a routing problem: "to reach 10.244.2.x, send to node-2."

- **iptables / netfilter:** the classic in-kernel packet filter and NAT engine. Hooks (`PREROUTING`, `INPUT`, `FORWARD`, `OUTPUT`, `POSTROUTING`) let you DNAT (rewrite destination), SNAT/masquerade (rewrite source), drop, and mark packets. kube-proxy's default mode writes thousands of these rules.

  > **Term — DNAT / SNAT.** *Destination* NAT rewrites the packet's destination address (used to send "traffic for the Service VIP" to "a chosen Pod IP"). *Source* NAT (masquerade) rewrites the source address (used so a Pod's reply comes back through the right node). Connection tracking (`conntrack`) remembers the mapping so the reverse packets get un-translated.

- **IPVS (IP Virtual Server):** a kernel L4 load balancer built for high connection counts, using a hash table instead of a linear rule list. kube-proxy can use IPVS instead of iptables.

- **eBPF (extended Berkeley Packet Filter):** a way to run small, verified programs inside the kernel at hook points (sockets, XDP at the NIC driver, tc on interfaces). Cilium uses eBPF to do routing, load balancing, and policy *without* the iptables rule explosion.

  > **Term — XDP (eXpress Data Path).** An eBPF hook at the earliest point in the NIC driver, before the kernel allocates its packet structure. It enables extremely fast drop/redirect (used for DDoS scrubbing and fast load balancing).

- **conntrack (connection tracking):** netfilter's table of active flows (`conntrack -L`). Its size (`nf_conntrack_max`) is a real production limit — exhaust it and new connections fail.

- **Encapsulation (overlay):** wrapping a Pod packet inside another packet to ship it across a network that doesn't know about Pod IPs. **VXLAN** wraps L2 frames in UDP (port 4789); **IP-in-IP** wraps an IP packet in another IP packet; **Geneve** is a flexible successor to VXLAN. The cost is overhead (typically ~50 bytes for VXLAN) and a smaller usable MTU.

  > **Term — MTU (Maximum Transmission Unit).** The largest packet a link will carry (commonly 1500 bytes on Ethernet). Overlays steal bytes for their headers, so the Pod-visible MTU must be reduced (e.g., 1450 for VXLAN) or packets get fragmented/dropped — a classic source of "works for small requests, hangs on big ones" bugs.

### 2.2 The four Kubernetes networking rules (the contract)

Kubernetes mandates that any conforming network implementation satisfies:

1. **Every Pod gets its own unique IP** within the cluster (the *Pod CIDR* / *cluster CIDR*).
2. **Pods can communicate with all other Pods on any node without NAT.** The peer sees your real Pod IP.
3. **Agents on a node (kubelet, system daemons) can reach all Pods on that node.**
4. **(Host network Pods)** Pods in the host's network namespace use the node's IP.

These rules are what make application code portable: you never write NAT-traversal logic; a Pod IP is a real, reachable address. *How* this is achieved is delegated to the CNI plugin.

> **Term — CIDR (Classless Inter-Domain Routing notation).** A way to write an IP range as `address/prefix-length`, e.g., `10.244.0.0/16` = all addresses from `10.244.0.0` to `10.244.255.255` (~65k addresses). The **Pod CIDR** is the pool Pods draw from; the **Service CIDR** is a separate, *virtual* pool for Service ClusterIPs (those IPs never belong to a real interface — they exist only in iptables/IPVS rules).

### 2.3 Pod networking, concretely

When a Pod is scheduled to a node:
1. The kubelet creates the **pause container** (a.k.a. *sandbox* / *infra container*) first. Its only job is to hold the network namespace open so application containers can join it and so the netns survives container restarts.

   > **Term — pause container.** A near-empty container (`registry.k8s.io/pause`) that does nothing but sleep. It *owns* the Pod's netns and IPC namespace. Because all the app containers share the pause container's network namespace, they share one IP and can reach each other over `localhost`.

2. The kubelet calls the **CNI plugin** (via the Container Network Interface) with `ADD`. The plugin allocates an IP from the node's slice of the Pod CIDR (this is **IPAM**, IP Address Management), creates the veth pair, moves one end into the Pod netns as `eth0`, configures the IP, routes, and (if overlay) the tunnel.

   > **Term — CNI (Container Network Interface).** A simple contract: the kubelet runs a CNI plugin binary, passes config on stdin and the container/netns on env vars, and the plugin sets up (or tears down) the Pod's network and prints the result (assigned IPs) as JSON. CNI is intentionally minimal, which is why so many implementations exist.

3. Containers in the Pod now share `eth0` and a single IP. Same-Pod containers use `localhost:<port>`; they must not collide on ports.

### 2.4 Why Services exist

A Pod's IP is stable *for the life of that Pod* — but Pods are cattle, not pets. A Deployment rolls, a node dies, an autoscaler scales: Pod IPs churn constantly. Clients can't be expected to track them. **Services** solve this:
- A Service has a stable name and (usually) a stable virtual IP (**ClusterIP**) for its whole lifetime.
- A **selector** (label query) defines which Pods are "behind" it.
- The control plane keeps a live list of healthy backing Pod IPs (**Endpoints / EndpointSlices**).
- The data plane (kube-proxy / eBPF) load-balances connections to the Service VIP across those Pod IPs.

This is **server-side, L4 (connection-level) load balancing done in the kernel** — there is usually no proxy process in the path for ClusterIP traffic; the kernel rewrites packets.

---

## 3. How it works internally (the heart of the doc)

We'll trace the full lifecycle: Pod creation → Service creation → a connection from client to backend → DNS resolution → external ingress → policy enforcement. Then we go under the hood of each data plane.

### 3.1 Pod network setup — step by step

```
kube-scheduler  → picks node N for Pod P
kubelet on N    → CRI: create pause container (holds netns)
kubelet on N    → CNI ADD:
    1. IPAM: allocate Pod IP from node's Pod CIDR slice (e.g. 10.244.2.7)
    2. create veth pair (host: vethABCD, pod: eth0)
    3. move pod end into pause container's netns
    4. assign 10.244.2.7/24 to eth0, add default route via gateway
    5. plug host end into bridge (cni0) OR set up routing/tunnel
    6. (overlay) program VXLAN/IP-in-IP FDB so other nodes can reach it
    7. return result JSON {ip: 10.244.2.7}
kubelet         → records Pod IP in status, starts app containers
```

The reverse (`CNI DEL`) happens on Pod teardown: release the IP, delete the veth, clean tunnel/FDB entries. **Leaked IPs** (DEL not called or IPAM state corrupted) are a real failure mode that exhausts the Pod CIDR.

### 3.2 Cross-node Pod-to-Pod — the two big strategies

**(a) Native routing (no encapsulation).** Each node "owns" a Pod CIDR slice (e.g., node-1 = `10.244.1.0/24`, node-2 = `10.244.2.0/24`). The network is told "to reach `10.244.2.0/24`, route to node-2." How the network learns this distinguishes plugins:
- **Calico (BGP mode):** each node runs a BGP speaker (`bird`/`Felix`) and advertises its Pod CIDR to peers (other nodes and/or the physical fabric/top-of-rack routers). Packets travel un-encapsulated; routers know the routes.

  > **Term — BGP (Border Gateway Protocol).** The routing protocol of the internet; routers use it to advertise "I can reach these IP ranges." Calico repurposes BGP inside the cluster so nodes announce their Pod CIDRs to each other and optionally to the data-center fabric, giving fully native (NAT-free, no-overlay) Pod routing.

- **AWS VPC CNI:** Pods get *real VPC IPs* attached to the node's ENIs (Elastic Network Interfaces). The VPC router already knows how to route them — no overlay, but Pod density is capped by ENI/IP limits per instance type.

**(b) Overlay (encapsulation).** When the underlying network can't route Pod IPs (most cloud VPCs by default, multi-subnet on-prem), the plugin encapsulates:
- **Flannel (VXLAN, the common default):** the source node wraps the Pod packet in a VXLAN/UDP packet addressed node→node; the destination node decapsulates and delivers to the Pod. A user-space/kernel `flanneld` maintains the forwarding database (FDB) mapping Pod CIDRs to node IPs.
- **Calico (IP-in-IP)** and **Cilium (VXLAN or Geneve)** offer overlay modes too.

Tradeoff: overlay "just works" anywhere but adds header overhead, lowers MTU, and makes packet captures harder to read. Native routing is faster and more transparent but requires cooperation from the network (BGP peering or cloud-native IPs).

### 3.3 Service implementation by kube-proxy — iptables mode (the default for years)

When you create a `ClusterIP` Service, the **control plane** does the bookkeeping and **kube-proxy** (a DaemonSet on every node) programs the **data plane**.

Control flow:
1. You `kubectl apply` a Service with a selector.
2. The **EndpointSlice controller** (in kube-controller-manager) watches Pods matching the selector + their readiness, and writes **EndpointSlice** objects (lists of `{podIP, port, ready}`).
3. **kube-proxy** on each node watches Services and EndpointSlices via the API server's watch stream and rewrites the node's iptables rules to match.

Data flow for a client Pod connecting to `ClusterIP 10.96.0.10:80`:
```
client sends SYN to 10.96.0.10:80
 → packet hits iptables OUTPUT/PREROUTING, jumps to KUBE-SERVICES chain
 → matches KUBE-SVC-<hash> for that service
 → KUBE-SVC chain uses 'statistic --mode random --probability' rules
   to pick one of N KUBE-SEP-<hash> (service endpoint) chains
 → KUBE-SEP chain DNATs destination to a real Pod IP, e.g. 10.244.2.7:8080
 → conntrack records the mapping
 → packet routed (native or overlay) to that Pod
 → replies come back, conntrack un-DNATs so client sees 10.96.0.10
```

Key properties of iptables mode:
- **Selection is random per-connection** (weighted by `probability` rules), not true round-robin; connection affinity holds for the flow via conntrack.
- **Rule count grows O(services × endpoints).** Tens of thousands of rules slow rule *updates* (each change can rewrite large chains) — historically a scaling pain at 5k+ services.
- **`sessionAffinity: ClientIP`** pins a client IP to one endpoint via conntrack for `timeoutSeconds` (default 10800s = 3h).

### 3.4 Service implementation — IPVS mode

kube-proxy in **IPVS mode** (`--proxy-mode=ipvs`) uses the kernel's IPVS load balancer:
- Each Service VIP becomes an IPVS *virtual server*; each endpoint a *real server*. Lookups are **hash-table O(1)**, so it scales to thousands of services far better than iptables' linear chains.
- Supports real LB algorithms: `rr` (round robin, default), `lc` (least connection), `dh` (destination hash), `sh` (source hash), `wrr`, `wlc`, etc., via `--ipvs-scheduler`.
- Still uses *some* iptables rules (and `ipset`, a kernel hash set) for masquerade, NodePort, and policy hooks — IPVS handles the load balancing, iptables handles the edges.
- A dummy interface `kube-ipvs0` holds all the ClusterIPs so the kernel "owns" them.

When to prefer IPVS: large clusters (many services/endpoints), need for LB algorithms beyond random. Caveat: historically had quirks with certain conntrack/affinity edge cases; well-proven today.

### 3.5 The eBPF data plane (Cilium) — replacing kube-proxy

**Cilium** can run in **kube-proxy-free** mode. Instead of iptables/IPVS:
- eBPF programs attached at the socket layer (`connect()` time, via `cgroup/connect4` hooks) and at tc/XDP do the Service VIP → Pod IP translation *directly*, often before the packet is even built (socket-level load balancing avoids per-packet DNAT and conntrack for in-cluster traffic).
- Service backends live in an **eBPF map** (a kernel hash map), updated by the Cilium agent watching the API. Lookups are O(1); updates don't rewrite giant rule sets.
- NetworkPolicy is enforced by eBPF using **identities** (a numeric label-derived identity per endpoint) rather than IP-based rules — far more scalable and stable as Pods churn.

  > **Term — eBPF map.** A kernel key/value data structure shared between eBPF programs and user space. Cilium stores service backends, policy, and connection state in maps, enabling O(1) lookups and live updates without reloading rules.

Benefits: lower latency, no iptables rule explosion, rich L3–L7 observability (Hubble), and policy that survives IP churn. Cost: kernel version requirements, a steeper operational/learning curve, and more moving parts to debug (`cilium` CLI, `bpftool`).

### 3.6 Endpoints vs. EndpointSlices

- **Endpoints (legacy):** one object per Service listing *all* backing IPs. Problem: a Service with 5,000 Pods = one giant object; any change rewrites the whole thing and every kube-proxy re-reads it → control-plane and network churn.
- **EndpointSlices (GA since 1.21, default):** the endpoint list is *sharded* into slices of (default) up to **100 endpoints** each (`--max-endpoints-per-slice`, up to 1000). A single Pod change touches one small slice. They also carry richer metadata: topology hints (`zone`), per-address `ready`/`serving`/`terminating` conditions, and address types (IPv4/IPv6/FQDN).

  > **Term — Topology Aware Routing / topology hints.** A feature where EndpointSlices carry zone hints so kube-proxy prefers endpoints in the *same availability zone* as the client, cutting cross-AZ traffic (and cloud egress cost) — at the risk of imbalance if zones are uneven.

### 3.7 Cluster DNS & service discovery (CoreDNS)

**CoreDNS** is the cluster's DNS server (a Deployment, fronted by a Service usually at `10.96.0.10`, exposed via the `kube-dns` Service name for backward compatibility).

> **Term — CoreDNS.** A pluggable DNS server written in Go. In Kubernetes it runs the `kubernetes` plugin, which watches Services/EndpointSlices and answers DNS queries for cluster names. It replaced the older `kube-dns` (which chained dnsmasq + SkyDNS).

How a Pod resolves a name:
1. The kubelet writes the Pod's `/etc/resolv.conf` with `nameserver 10.96.0.10` (the CoreDNS Service IP) and a `search` list, e.g.:
   ```
   search myns.svc.cluster.local svc.cluster.local cluster.local
   options ndots:5
   ```
2. App calls `getaddrinfo("orders")`. Because `ndots:5` means "a name with fewer than 5 dots is treated as relative," the resolver tries the search-domain suffixes first: `orders.myns.svc.cluster.local`, then `orders.svc.cluster.local`, etc.

   > **Term — ndots.** The threshold of dots below which the resolver appends search domains before trying the name as-is. `ndots:5` is large, so short names trigger several lookups — a notorious DNS-amplification/latency issue. Adding a trailing dot (`orders.myns.svc.cluster.local.`) makes the name absolute and skips the search loop.
3. CoreDNS's `kubernetes` plugin answers from its in-memory view: `orders.myns.svc.cluster.local` → the Service's ClusterIP (an **A** record).

DNS naming scheme:
- **Service:** `<service>.<namespace>.svc.cluster.local` → ClusterIP.
- **Headless Service** (`clusterIP: None`): returns **A records for each Pod IP** (no VIP) — used for stateful sets and client-side LB.
- **StatefulSet Pod:** `<pod>.<service>.<namespace>.svc.cluster.local` → stable per-Pod DNS (e.g., `kafka-0.kafka.default.svc.cluster.local`).
- **SRV records** for named ports; **PTR** for reverse lookups.

`NodeLocal DNSCache` is an optional DaemonSet that runs a DNS cache on each node (listening on a link-local IP) to cut latency and CoreDNS load and dodge a conntrack race that caused intermittent DNS timeouts.

### 3.8 Service types — internal behavior

- **ClusterIP (default):** virtual IP reachable *only inside* the cluster. The base case above.
- **NodePort:** allocates a port (default range **30000–32767**) on *every* node; traffic to `nodeIP:nodePort` is DNAT'd to the Service. Builds on ClusterIP. External LBs and bare-metal setups use this as a hook.
- **LoadBalancer:** provisions an *external* cloud load balancer (via the cloud-controller-manager) that targets the NodePorts. Superset of NodePort + ClusterIP. On bare metal, **MetalLB** fills this role (via ARP/L2 or BGP).
- **ExternalName:** no proxying at all — CoreDNS returns a **CNAME** to an external DNS name (e.g., map `db.prod.svc` → `mydb.rds.amazonaws.com`). Pure DNS aliasing.
- **Headless (`clusterIP: None`):** no VIP, no load balancing; DNS returns Pod IPs directly. For peer discovery (databases, StatefulSets) and client-side LB (e.g., gRPC).

  > **Term — externalTrafficPolicy.** For NodePort/LoadBalancer: `Cluster` (default) accepts traffic on any node and may forward to a Pod on another node (extra hop, but even spread; source IP is SNAT'd/lost). `Local` only sends to Pods on the *receiving* node (preserves client source IP, no extra hop, but a node with no local Pod drops traffic — health checks must account for this).

### 3.9 Ingress & ingress controllers

A **Service of type LoadBalancer per app** is expensive (one cloud LB each) and L4-only. **Ingress** is an L7 (HTTP/HTTPS) router: one entry point, host/path-based routing, TLS termination.

> **Term — Ingress vs. Ingress Controller.** The **Ingress** is just an *API object* (rules: "host a.com path /api → service X"). It does nothing by itself. An **Ingress Controller** is the actual running proxy (NGINX, HAProxy, Traefik, Envoy/Contour, cloud ALB controller) that watches Ingress objects and configures itself to implement them. No controller installed = your Ingress object is inert.

Flow: external client → cloud LB → ingress-controller Pods → (controller reads Ingress rules) → routes to the right Service/Pod, terminating TLS, rewriting paths, adding headers. The newer **Gateway API** (GA `Gateway`/`HTTPRoute`) is the successor designed to fix Ingress's limitations (vendor annotations sprawl, weak multi-team/role separation, no native L4/gRPC/TLS-passthrough modeling).

### 3.10 NetworkPolicy enforcement

By default, **all Pods can talk to all Pods** (the flat model). A **NetworkPolicy** changes that: once *any* policy selects a Pod, that Pod becomes "default-deny" for the selected direction (ingress/egress), and only explicitly allowed traffic passes.

Key facts:
- NetworkPolicy is **namespaced** and selects Pods by label.
- It is **enforced by the CNI plugin**, not by Kubernetes core. If your CNI doesn't support policy (e.g., plain Flannel), NetworkPolicy objects are silently ignored — a dangerous gotcha.
- Rules match by **podSelector**, **namespaceSelector**, and **ipBlock** (CIDR), plus ports.
- Enforcement under the hood: Calico compiles policies to iptables/eBPF rules keyed on Pod IPs; Cilium compiles to eBPF keyed on **identities** (label-derived), so churning Pod IPs don't invalidate rules.

> **Term — default-deny.** A baseline policy (`podSelector: {}` with empty ingress/egress) that selects *all* Pods in a namespace and allows nothing, forcing every allowed flow to be declared. The secure-by-default starting point.

### 3.11 Service mesh interaction

A **service mesh** adds L7 features the core model lacks: mutual TLS (mTLS) between services, retries/timeouts/circuit breaking, fine-grained traffic shifting (canary/blue-green), and deep telemetry.

> **Term — sidecar.** An extra container injected into each Pod (e.g., an Envoy proxy in Istio). It intercepts *all* the Pod's traffic (via iptables redirect set up by an init container, or by Istio's CNI plugin) so the mesh can apply policy/encryption transparently. **Ambient mesh / per-node proxy** (Istio ambient, Linkerd) is the newer sidecar-less approach using a node-level proxy (ztunnel) to cut the per-Pod overhead.

The mesh sits *on top of* Kubernetes networking: Services and CNI still move packets; the mesh's proxies intercept and re-route at L7. Interaction gotchas: meshes change source IPs and ports (everything looks like it comes from the local proxy), which collides with `externalTrafficPolicy: Local`, NetworkPolicy IP rules, and ingress source-IP preservation. mTLS also means raw `tcpdump` shows ciphertext.

---

## 4. The complete toolkit

### 4.1 Service spec fields

| Field | Purpose | Key values / defaults |
|---|---|---|
| `spec.type` | Exposure model | `ClusterIP` (default), `NodePort`, `LoadBalancer`, `ExternalName` |
| `spec.clusterIP` | Virtual IP | auto-assigned; `None` = headless; can pin from Service CIDR |
| `spec.selector` | Which Pods back the Service | label query; omit for manual Endpoints |
| `spec.ports[].port` | Service port (VIP side) | required |
| `spec.ports[].targetPort` | Pod-side port | defaults to `port`; can be a named port |
| `spec.ports[].nodePort` | Node port (NodePort/LB) | auto from 30000–32767, or pinned |
| `spec.ports[].protocol` | L4 protocol | `TCP` (default), `UDP`, `SCTP` |
| `spec.sessionAffinity` | Stickiness | `None` (default) or `ClientIP` |
| `spec.sessionAffinityConfig.clientIP.timeoutSeconds` | Affinity TTL | default 10800 (3h) |
| `spec.externalTrafficPolicy` | NodePort/LB hop behavior | `Cluster` (default) / `Local` |
| `spec.internalTrafficPolicy` | In-cluster routing | `Cluster` (default) / `Local` |
| `spec.externalName` | Target FQDN | only for `ExternalName` |
| `spec.ipFamilyPolicy` | IPv4/IPv6 | `SingleStack` (default) / `PreferDualStack` / `RequireDualStack` |
| `spec.loadBalancerClass` | Choose LB implementation | e.g., for MetalLB or custom |
| `spec.allocateLoadBalancerNodePorts` | Skip NodePort alloc for LB | default `true` |

### 4.2 kube-proxy flags (selected, key ones)

| Flag | Purpose | Default |
|---|---|---|
| `--proxy-mode` | Data plane | `iptables` (Linux); `ipvs`; `kernelspace` (Windows); `nftables` (newer) |
| `--ipvs-scheduler` | IPVS LB algo | `rr` |
| `--cluster-cidr` | Pod CIDR (for masquerade decisions) | unset (must match cluster) |
| `--masquerade-all` | SNAT all service traffic | `false` |
| `--nodeport-addresses` | Which node IPs answer NodePorts | all interfaces |
| `--conntrack-max-per-core` | conntrack table sizing | 32768/core |
| `--conntrack-tcp-timeout-established` | flow TTL | 24h |
| `--metrics-bind-address` | Prometheus metrics | 127.0.0.1:10249 |

> **Term — nftables mode.** kube-proxy gained an `nftables` mode (beta ~1.31, GA-track) that uses the modern `nftables` successor to iptables, fixing iptables' O(n) rule scaling without IPVS's extra components. Version-specific — check your cluster version.

### 4.3 NetworkPolicy fields

| Field | Purpose |
|---|---|
| `spec.podSelector` | Pods this policy applies to (`{}` = all in namespace) |
| `spec.policyTypes` | `Ingress`, `Egress`, or both |
| `ingress[].from` | Allowed sources: `podSelector`, `namespaceSelector`, `ipBlock` |
| `egress[].to` | Allowed destinations (same selector types) |
| `*.ports` | Allowed ports/protocols |
| `ipBlock.cidr` / `ipBlock.except` | CIDR allow + carve-outs |

### 4.4 Ingress fields

| Field | Purpose |
|---|---|
| `spec.ingressClassName` | Which controller handles it (replaces old annotation) |
| `spec.rules[].host` | Hostname match |
| `spec.rules[].http.paths[].path` + `pathType` | Path match: `Prefix` / `Exact` / `ImplementationSpecific` |
| `...backend.service.name/port` | Target Service |
| `spec.tls[]` | TLS termination (secretName holds cert/key) |
| `spec.defaultBackend` | Catch-all |

### 4.5 CNI plugin landscape

| Plugin | Data plane | Default mode | Policy | Notable |
|---|---|---|---|---|
| **Flannel** | iptables/kube-proxy | VXLAN overlay | none (alone) | Simplest; pair with Calico for policy |
| **Calico** | iptables/eBPF | BGP native (or IP-in-IP/VXLAN overlay) | rich (GlobalNetworkPolicy too) | BGP to fabric; mature policy engine |
| **Cilium** | eBPF | VXLAN/Geneve or native; kube-proxy-free | rich L3–L7 (identity-based) | Hubble observability, eBPF LB |
| **AWS VPC CNI** | native VPC | real VPC IPs on ENIs | via security groups / Calico add-on | No overlay; IP density limited by ENI |
| **Azure CNI / GKE** | cloud-native | native | cloud-specific | Integrated with cloud routing |
| **Weave Net** | overlay | VXLAN-ish | yes | (Largely legacy now) |

### 4.6 Essential CLI/debug commands

| Command | What it tells you |
|---|---|
| `kubectl get svc,endpointslice -o wide` | Service VIPs and live backends |
| `kubectl get endpointslices -l kubernetes.io/service-name=X` | Endpoints behind a Service |
| `kubectl describe ingress X` | Rules, backends, controller events |
| `kubectl exec POD -- nslookup orders` | DNS resolution from inside a Pod |
| `kubectl run tmp --rm -it --image=nicolaka/netshoot` | A debug Pod with all net tools |
| `iptables-save -t nat | grep KUBE-SVC` | kube-proxy iptables rules |
| `ipvsadm -Ln` | IPVS virtual/real servers |
| `conntrack -L` / `conntrack -S` | Live flows / drops |
| `cilium status`, `cilium monitor`, `hubble observe` | Cilium/eBPF state & flow logs |
| `calicoctl get ippool`, `calicoctl node status` | Calico pools, BGP peers |
| `ip route`, `ip netns`, `bridge fdb show` | Host routing, namespaces, overlay FDB |

---

## 5. Code examples by use case

### 5.1 ClusterIP service for an internal Java (Spring Boot) API

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: orders, namespace: shop }
spec:
  replicas: 3
  selector: { matchLabels: { app: orders } }
  template:
    metadata: { labels: { app: orders } }
    spec:
      containers:
        - name: app
          image: registry.example.com/orders:1.4.2
          ports:
            - containerPort: 8080      # the Spring Boot server port
          readinessProbe:              # readiness gates EndpointSlice membership
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata: { name: orders, namespace: shop }
spec:
  selector: { app: orders }            # picks the 3 Pods above
  ports:
    - name: http
      port: 80                         # clients call orders:80
      targetPort: 8080                 # forwarded to the container's 8080
  # type defaults to ClusterIP
```
A peer service reaches it as `http://orders.shop.svc.cluster.local/` (or just `http://orders/` from within the `shop` namespace). The **readiness probe matters**: an unready Pod is removed from the EndpointSlice, so kube-proxy stops sending it traffic — this is how rolling updates avoid 500s. In a Java client, prefer DNS each time / honor TTLs; the JVM historically caches DNS forever (`networkaddress.cache.ttl`), so set it sanely:

```java
// Avoid the JVM pinning a dead Pod/Service IP forever.
// In code, or via -Dsun.net.inetaddr.ttl=30, or java.security policy:
java.security.Security.setProperty("networkaddress.cache.ttl", "30");
java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
```

### 5.2 Headless service + StatefulSet for a clustered datastore (stable peer DNS)

```yaml
apiVersion: v1
kind: Service
metadata: { name: kafka, namespace: data }
spec:
  clusterIP: None                      # headless: no VIP, DNS returns Pod IPs
  selector: { app: kafka }
  ports: [{ name: broker, port: 9092 }]
---
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: kafka, namespace: data }
spec:
  serviceName: kafka                   # ties stable DNS names to this headless svc
  replicas: 3
  selector: { matchLabels: { app: kafka } }
  template:
    metadata: { labels: { app: kafka } }
    spec:
      containers: [{ name: kafka, image: bitnami/kafka:3.7, ports: [{ containerPort: 9092 }] }]
```
Now each broker has a *stable* DNS name: `kafka-0.kafka.data.svc.cluster.local`, `kafka-1...`, etc., surviving reschedules. This is exactly what a Java Kafka client (or any quorum system needing fixed peer addresses) wants for its bootstrap/advertised listeners.

### 5.3 Exposing externally: LoadBalancer with source-IP preservation

```yaml
apiVersion: v1
kind: Service
metadata:
  name: gateway
  namespace: edge
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"   # cloud-specific
spec:
  type: LoadBalancer
  externalTrafficPolicy: Local     # preserve client source IP, no extra hop
  selector: { app: gateway }
  ports: [{ port: 443, targetPort: 8443 }]
```
With `Local`, only nodes running a `gateway` Pod pass the cloud LB's health check, and the real client IP reaches your app (important for rate-limiting, geo, audit logging). The cost: traffic distribution depends on how Pods spread across nodes.

### 5.4 Ingress with TLS and path routing (NGINX controller)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: shop
  namespace: shop
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts: [shop.example.com]
      secretName: shop-tls           # k8s Secret with tls.crt/tls.key
  rules:
    - host: shop.example.com
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend: { service: { name: orders, port: { number: 80 } } }
          - path: /
            pathType: Prefix
            backend: { service: { name: frontend, port: { number: 80 } } }
```
One hostname, one cloud LB, TLS terminated at the controller, `/api/*` → orders, everything else → frontend.

### 5.5 ExternalName: aliasing a managed database

```yaml
apiVersion: v1
kind: Service
metadata: { name: orders-db, namespace: shop }
spec:
  type: ExternalName
  externalName: orders.cluster-xyz.us-east-1.rds.amazonaws.com
```
Now `jdbc:postgresql://orders-db.shop.svc.cluster.local:5432/orders` works inside the cluster, and you can swap the backing DB by editing one object — no app config change. (No load balancing or TLS handling; pure DNS CNAME.)

### 5.6 NetworkPolicy: default-deny then explicit allow

```yaml
# 1) Default-deny all ingress in the namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: default-deny-ingress, namespace: shop }
spec:
  podSelector: {}                      # selects every Pod in shop
  policyTypes: [Ingress]
---
# 2) Allow only the frontend to reach orders on 8080
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: allow-frontend-to-orders, namespace: shop }
spec:
  podSelector: { matchLabels: { app: orders } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: { matchLabels: { app: frontend } }
      ports:
        - { protocol: TCP, port: 8080 }
```
Remember: this only works if your CNI enforces policy (Calico/Cilium yes; bare Flannel no).

### 5.7 Egress lockdown (allow DNS + one external CIDR)

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: orders-egress, namespace: shop }
spec:
  podSelector: { matchLabels: { app: orders } }
  policyTypes: [Egress]
  egress:
    - to:                              # allow DNS to CoreDNS (kube-system)
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
      ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
    - to:                              # allow the payment partner's API range
        - ipBlock: { cidr: 203.0.113.0/24 }
      ports: [{ protocol: TCP, port: 443 }]
```
A classic mistake is locking egress without allowing port 53 to CoreDNS — *all DNS breaks* and the app appears to hang on every outbound call.

### 5.8 Debug Pod and a one-liner connectivity test

```bash
# Spin up a fully-loaded debug shell in the target namespace
kubectl -n shop run netshoot --rm -it --image=nicolaka/netshoot -- /bin/bash

# Inside it:
nslookup orders                                   # DNS works?
curl -v http://orders:80/actuator/health          # ClusterIP path works?
curl -v http://10.244.2.7:8080/actuator/health    # raw Pod IP works? (isolates Service vs Pod)
nc -zv orders 80                                   # TCP reachability
dig +search orders.shop                            # see search-domain expansion
```

---

## 6. Implementation concerns & best practices

**Performance**
- iptables mode degrades on **rule-update latency** at high service/endpoint counts; switch to **IPVS** or **eBPF/Cilium** (or kube-proxy `nftables` mode) beyond a few thousand services.
- Overlays cost CPU (encap/decap) and MTU; prefer native routing (BGP/cloud IPs) for latency-sensitive paths.
- **conntrack table** is a hard ceiling: high connection rates exhaust `nf_conntrack_max` → dropped connections. Monitor `conntrack -S` for `insert_failed`/`drop`.
- DNS: `ndots:5` multiplies lookups; use FQDNs with trailing dots for hot paths, deploy **NodeLocal DNSCache**, and tune CoreDNS replicas/cache.

**Correctness & concurrency**
- **Readiness probes** must accurately reflect "ready to serve," or you'll route to cold/broken Pods.
- **Graceful shutdown:** on Pod termination, Kubernetes removes it from EndpointSlices *and* sends SIGTERM roughly concurrently; in-flight requests can hit a closing Pod. Add a `preStop` sleep (a few seconds) and handle SIGTERM to drain.
- Connection-level LB means **long-lived connections (HTTP/2, gRPC, JDBC pools) don't rebalance** when you scale up — new Pods get no traffic. Use client-side LB (headless + gRPC), periodic reconnects, or a mesh.

**Security**
- Adopt **default-deny** NetworkPolicies per namespace; allow explicitly. Verify your CNI actually enforces them.
- Beware that **ExternalName + Ingress** can be abused for SSRF-style routing; restrict who can create such objects.
- Service meshes give **mTLS** and identity; without one, in-cluster traffic is plaintext by default.

**Observability**
- Scrape kube-proxy metrics, CoreDNS metrics (query latency, `SERVFAIL`), and CNI metrics (Calico Felix, Cilium/Hubble).
- Use **Hubble** (Cilium) or mesh telemetry for per-flow visibility — invaluable when "service A can't reach B."

**Cost**
- Cross-AZ traffic is billed by clouds; **Topology Aware Routing** / `internalTrafficPolicy: Local` keeps traffic in-zone.
- One LoadBalancer Service per app is expensive; consolidate behind **Ingress/Gateway**.

**Testing & hardening**
- Test NetworkPolicies with actual connection attempts (allow *and* deny cases), not just by reading YAML.
- Pin Service CIDR/Pod CIDR sizing for growth; CIDR exhaustion is a painful, disruptive migration.

**Anti-patterns**
- Relying on Pod IPs directly in app config (they churn) — use Services/DNS.
- `hostNetwork: true` "to make it work" — breaks isolation and port management.
- NetworkPolicy that forgets DNS egress (port 53).
- Assuming round-robin from iptables mode (it's randomized).
- Headless service expecting a VIP (there isn't one).

---

## 7. Advanced topics & deep internals

- **Socket-level load balancing (Cilium):** translating the Service VIP at `connect()` time means in-cluster traffic never carries the VIP on the wire and skips per-packet DNAT/conntrack — lower latency and no conntrack pressure for east-west traffic.
- **Identity-based policy:** Cilium assigns each set of labels a numeric *identity*; policy and flow logs reference identities, so policies remain valid as Pod IPs churn (a structural advantage over IP-keyed iptables policy).
- **Direct Server Return / Maglev-style hashing:** advanced LB modes (Cilium's Maglev consistent hashing, IPVS `mh`) minimize backend remapping when endpoints change — important for connection stability.
- **MTU math:** native = link MTU; VXLAN ≈ MTU − 50; IP-in-IP ≈ MTU − 20; Geneve variable. Mis-set MTU causes large-payload hangs while small requests succeed — diagnose with `ping -M do -s <size>` (DF bit).
- **conntrack races & DNS:** the historical 5-second DNS timeout bug stemmed from a kernel conntrack insert race on parallel UDP DNS lookups; mitigations: NodeLocal DNSCache (uses TCP/local), single-request options, or `use-vc`.
- **Dual-stack (IPv4/IPv6):** Services can have both families (`ipFamilyPolicy`), each with its own ClusterIP; ordering and default family matter for clients.
- **`internalTrafficPolicy: Local`:** keeps in-cluster Service traffic on the node (node-local caches/agents) — node with no local backend = no traffic.
- **Topology Aware Routing internals:** kube-proxy uses EndpointSlice `hints.forZones`; the controller only populates hints when it can keep zones balanced, else it falls back to cluster-wide.
- **kube-proxy `nftables` mode:** uses verdict maps for O(1)-ish service dispatch, fixing iptables scaling without IPVS; version-gated.
- **BGP route reflectors (Calico):** in large clusters full-mesh BGP is O(n²); route reflectors (or the ToR fabric) scale peering.

---

## 8. Tradeoffs & decision frameworks

### kube-proxy data plane

| | iptables | IPVS | eBPF (Cilium, no kube-proxy) | nftables |
|---|---|---|---|---|
| Lookup cost | O(n) rule chains | O(1) hash | O(1) map | ~O(1) maps |
| Update cost at scale | poor | good | excellent | good |
| LB algorithms | random only | rr/lc/sh/dh/... | maglev/random | random |
| Extra deps | none | ipset/ipvsadm | kernel ≥ ~4.19+ | newer kernel |
| Observability | weak | weak | strong (Hubble) | weak |
| **Use when** | small/simple | large svc count | scale + L7 + observability | modern kernel, want iptables successor |

### CNI choice
- **Flannel:** simplest overlay; pick when you just need Pods to talk and don't need policy. Add Calico for policy ("Canal").
- **Calico:** want mature NetworkPolicy + native BGP routing + on-prem fabric integration.
- **Cilium:** want eBPF performance, kube-proxy-free, L7 policy, deep observability — at the cost of complexity/kernel requirements.
- **Cloud CNI (AWS/Azure/GKE):** want native VPC integration and security-group policy; accept IP-density limits.

### Service type
- **ClusterIP:** internal only. (Default; use for all east-west.)
- **NodePort:** dev/bare-metal/behind-your-own-LB. Avoid as a public front door.
- **LoadBalancer:** one L4 entry per app in cloud. Use sparingly.
- **ExternalName:** alias an external service via DNS. No proxying.
- **Headless:** peer discovery / client-side LB / StatefulSets.

### Ingress vs. Gateway API vs. LoadBalancer
- **LoadBalancer (L4):** raw TCP/UDP, non-HTTP, or one-app simplicity.
- **Ingress (L7):** HTTP host/path routing, shared entry, TLS — but annotation sprawl.
- **Gateway API (L7/L4):** multi-team, role separation, richer routing, gRPC/TLS modes — the strategic direction.

### Mesh: use when / avoid when
- **Use when:** you need mTLS everywhere, fine traffic shifting/canaries, automatic retries, golden-signal telemetry across many services.
- **Avoid when:** small system, latency/overhead-sensitive, or the team can't absorb the operational complexity. Start with NetworkPolicy + good metrics first.

---

## 9. Failure modes & debugging

**Methodical isolation:** Is it DNS, the Service VIP, the Pod, or policy? Test each layer:
```
1. nslookup <svc>           # DNS layer
2. curl http://<svc>:port   # Service/VIP + endpoint selection
3. curl http://<podIP>:port # raw Pod (isolates Service vs app)
4. check EndpointSlices     # are there ANY ready backends?
5. check NetworkPolicy      # is traffic being denied?
```

| Symptom | Likely cause | Diagnose with |
|---|---|---|
| `nslookup` fails / 5s hangs | CoreDNS down, conntrack DNS race, egress blocks 53 | `kubectl -n kube-system logs deploy/coredns`; check egress policy; deploy NodeLocal DNSCache |
| Service has no endpoints | selector/labels mismatch, all Pods unready | `kubectl get endpointslices`; `kubectl describe svc`; check readiness probes |
| Pod IP works, Service IP doesn't | kube-proxy broken/misprogrammed | `iptables-save -t nat | grep KUBE`; `ipvsadm -Ln`; restart/check kube-proxy DaemonSet |
| Cross-node fails, same-node OK | overlay/routing/MTU/firewall | `ip route`, `bridge fdb show`, `ping -M do -s 1472`; check security groups / VXLAN 4789 |
| Intermittent timeouts under load | conntrack exhaustion | `conntrack -S` (insert_failed); raise `nf_conntrack_max` |
| External LB unhealthy | `externalTrafficPolicy: Local` + no local Pod | spread Pods; check LB health-check node ports |
| NetworkPolicy ignored | CNI doesn't enforce policy (Flannel) | confirm CNI supports policy; switch/add Calico/Cilium |
| Large requests hang, small OK | MTU mismatch (overlay) | DF-bit ping; lower Pod MTU |
| New Pods get no traffic after scale-up | long-lived L4 connections don't rebalance | client-side LB / periodic reconnect / mesh |
| Source IP is wrong/lost | SNAT under `Cluster` policy or mesh sidecar | use `externalTrafficPolicy: Local`; check mesh config |

**Real-world incident shapes**
- *"5-second DNS latency everywhere"* → kernel conntrack insert race on parallel UDP lookups; fixed by NodeLocal DNSCache / TCP DNS.
- *"App can't reach DB after we added NetworkPolicy"* → egress policy missing port 53 (DNS) so the JDBC URL never resolves.
- *"Half our requests 502 during deploy"* → no `preStop`/graceful drain; Pod killed while still in some proxy's connection pool.
- *"Service intermittently drops connections at peak"* → conntrack table full; `insert_failed` climbing.

---

## 10. Interview drill

**Q1. Explain the Kubernetes network model in one minute.**
Every Pod gets a unique, routable IP; Pods communicate without NAT (peer sees real IP); each Pod is a netns wired by a CNI plugin. Services provide stable VIPs over churning Pods; kube-proxy (or eBPF) load-balances VIP→Pod in the kernel; CoreDNS maps names to VIPs.
- *Follow-up: Why no NAT between Pods?* So the network looks flat and apps need no NAT-traversal logic; the peer's address is its real address, simplifying discovery, logging, and policy.
- *Follow-up: Who implements the model?* The CNI plugin; Kubernetes only specifies the contract.

**Q2. How does kube-proxy implement a ClusterIP in iptables mode?**
Control plane writes EndpointSlices; kube-proxy programs `KUBE-SERVICES`→`KUBE-SVC-*` chains that randomly (probability-weighted) jump to a `KUBE-SEP-*` chain which DNATs to a Pod IP; conntrack tracks the flow so replies are un-translated.
- *Follow-up: Round robin?* No — randomized per connection.
- *Follow-up: Why does it scale poorly?* O(n) rule chains and costly full-chain rewrites on changes.
- *Follow-up: IPVS fix?* Hash-table O(1) lookups + real LB algorithms.

**Q3. ClusterIP vs NodePort vs LoadBalancer vs ExternalName vs headless?**
ClusterIP = internal VIP; NodePort = port on every node (30000–32767) on top of ClusterIP; LoadBalancer = cloud LB on top of NodePort; ExternalName = DNS CNAME, no proxy; headless = no VIP, DNS returns Pod IPs.
- *Follow-up: When headless?* StatefulSets, peer discovery, client-side LB (gRPC).
- *Follow-up: externalTrafficPolicy Local vs Cluster?* Local preserves source IP/no extra hop but drops on nodes with no local Pod.

**Q4. Walk DNS resolution of `orders` from a Pod.**
resolv.conf points at CoreDNS VIP with `ndots:5` + search domains; short name triggers search-suffix attempts; CoreDNS `kubernetes` plugin returns the Service ClusterIP (A record).
- *Follow-up: ndots pitfall?* Many failed lookups before the right one; use FQDN + trailing dot.
- *Follow-up: NodeLocal DNSCache?* Per-node cache cutting latency and dodging the conntrack DNS race.

**Q5. Endpoints vs EndpointSlices?**
Endpoints = one big object per Service (poor scaling); EndpointSlices = sharded (≤100/slice default), richer metadata (zone hints, ready/serving/terminating), the default since 1.21.
- *Follow-up: Why sharding helps?* One Pod change touches one small slice, not a giant object every kube-proxy re-reads.

**Q6. Ingress vs Ingress Controller; what's the Gateway API for?**
Ingress is an inert API object (rules); the controller is the running proxy implementing them. Gateway API is the successor fixing annotation sprawl, role separation, and richer L4/L7 routing.
- *Follow-up: No controller installed?* Ingress does nothing.

**Q7. How is NetworkPolicy enforced and what's the default?**
Default is allow-all; a policy selecting a Pod makes it default-deny for that direction; enforced by the CNI (Calico iptables/eBPF, Cilium identity-based eBPF) — not by core K8s.
- *Follow-up: Flannel gotcha?* It ignores policies silently.
- *Follow-up: Identity vs IP enforcement?* Identity-based survives Pod IP churn.

**Q8. (Senior signal) Overlay vs native routing — when each?**
Overlay (VXLAN/IP-in-IP) works on any underlay but adds header overhead and MTU constraints; native (Calico BGP / cloud VPC IPs) is faster/transparent but needs network cooperation (BGP peering or cloud IPs, with density limits). Choose native for latency-sensitive, transparent setups where you control the fabric; overlay for portability.

**Q9. (Senior signal) When introduce a service mesh, and what does it cost you?**
Introduce for mTLS-everywhere, fine traffic shifting/canary, retries/circuit breaking, cross-service telemetry. Costs: latency/CPU per hop (sidecars), operational complexity, and interactions with source-IP preservation, NetworkPolicy IP rules, and ingress. Exhaust NetworkPolicy + metrics first; consider ambient/per-node mode to cut sidecar cost.

**Q10. (Senior signal) Designing networking for a 5,000-service cluster — choices?**
Avoid iptables kube-proxy (rule scaling) → IPVS, nftables, or Cilium eBPF (kube-proxy-free). Use EndpointSlices (already default). Mind conntrack sizing and DNS scaling (NodeLocal DNSCache, CoreDNS autoscale). Reduce cross-AZ cost with Topology Aware Routing. Consolidate ingress under Gateway API. Default-deny NetworkPolicies with a policy-capable CNI.

**Q11. Why might a new Pod get no traffic after scale-up?**
L4 connection-level LB doesn't rebalance existing long-lived connections (HTTP/2, gRPC, JDBC pools); new Pods only get *new* connections. Fix with client-side LB (headless + gRPC), periodic reconnects, or a mesh that load-balances per request.
- *Follow-up: Why does gRPC suffer specifically?* It multiplexes many requests over one persistent HTTP/2 connection, which pins to one backend.

**Q12. A Pod can reach another by Pod IP but not by Service IP. Diagnose.**
Service-layer problem: kube-proxy not programming rules, no ready endpoints, or selector mismatch. Check `kubectl get endpointslices`, `iptables-save -t nat | grep KUBE` / `ipvsadm -Ln`, and kube-proxy health.
- *Follow-up: Empty endpoints but Pods running?* Readiness failing or label/selector mismatch.

---

## 11. Glossary

- **ARP:** protocol mapping IP→MAC on a local network.
- **BGP:** routing protocol advertising reachable IP ranges; Calico uses it for native Pod routing.
- **CIDR:** `addr/prefix` IP-range notation (Pod CIDR, Service CIDR).
- **CNI:** Container Network Interface; the plugin contract that wires up Pod networking.
- **conntrack:** netfilter's table of active flows; finite (`nf_conntrack_max`).
- **CoreDNS:** the cluster DNS server resolving Service/Pod names.
- **default-deny:** baseline NetworkPolicy allowing nothing until explicitly permitted.
- **DNAT/SNAT:** destination/source NAT (rewrite dest/source address).
- **eBPF:** verified in-kernel programs at hook points; Cilium's data plane.
- **EndpointSlice:** sharded, metadata-rich list of a Service's backing endpoints.
- **Endpoints:** legacy single-object endpoint list.
- **ENI:** AWS Elastic Network Interface; carries Pod IPs in AWS VPC CNI.
- **externalTrafficPolicy:** `Cluster`/`Local` — node-hop & source-IP behavior for NodePort/LB.
- **Gateway API:** successor to Ingress for richer, role-separated routing.
- **Geneve/VXLAN/IP-in-IP:** overlay encapsulation formats.
- **headless service:** `clusterIP: None`; DNS returns Pod IPs, no VIP/LB.
- **Hubble:** Cilium's flow-observability tool.
- **Ingress / Ingress Controller:** L7 routing rules (object) vs. the proxy that implements them.
- **internalTrafficPolicy:** keep in-cluster Service traffic node-local.
- **IPAM:** IP Address Management (allocating Pod IPs).
- **iptables / netfilter:** kernel packet filter/NAT engine; kube-proxy's default backend.
- **IPVS:** kernel L4 load balancer (hash-table, multiple algorithms).
- **kube-proxy:** node agent programming the Service data plane.
- **LoadBalancer (Service):** provisions an external cloud LB.
- **MetalLB:** bare-metal LoadBalancer implementation (L2/BGP).
- **mTLS:** mutual TLS; both sides authenticate (mesh feature).
- **MTU:** max packet size on a link; overlays reduce it.
- **NAT:** rewriting IP/port at a boundary; avoided between Pods.
- **ndots:** resolver threshold for appending search domains.
- **netns (network namespace):** isolated kernel network stack; a Pod's networking unit.
- **NetworkPolicy:** Pod-level firewall, enforced by the CNI.
- **NodeLocal DNSCache:** per-node DNS cache reducing latency/CoreDNS load.
- **NodePort:** port (30000–32767) on every node mapping to a Service.
- **nftables:** modern successor to iptables; a kube-proxy mode.
- **overlay / native routing:** encapsulated vs. directly-routed Pod traffic.
- **pause container:** holds the Pod's netns open; owner of the Pod IP.
- **Pod CIDR / Service CIDR:** address pools for Pod IPs / virtual Service IPs.
- **readiness probe:** health check gating EndpointSlice membership.
- **service mesh:** L7 overlay (sidecar/per-node proxy) adding mTLS, traffic control, telemetry.
- **sidecar:** injected proxy container intercepting Pod traffic.
- **StatefulSet:** workload giving Pods stable identities/DNS.
- **Topology Aware Routing:** zone-preferring endpoint selection to cut cross-AZ traffic.
- **veth pair:** virtual cable connecting Pod netns to host.
- **VIP (virtual IP):** Service ClusterIP that exists only in proxy rules.
- **XDP:** earliest eBPF NIC-driver hook for fast packet processing.

---

## 12. Cheat-sheet & self-test

### One-screen recap
- **Model:** every Pod a routable IP; **no NAT** Pod↔Pod; CNI implements it; pause container holds the netns.
- **Service types:** ClusterIP (internal VIP) · NodePort (30000–32767 on all nodes) · LoadBalancer (cloud LB) · ExternalName (DNS CNAME) · headless (`None`, Pod IPs via DNS).
- **kube-proxy modes:** iptables (random, O(n)) · IPVS (hash O(1), algorithms) · eBPF/Cilium (kube-proxy-free, identity policy) · nftables (newer).
- **Endpoints:** use **EndpointSlices** (≤100/slice default; zone hints; ready/serving/terminating).
- **DNS:** `<svc>.<ns>.svc.cluster.local` via CoreDNS at ~`10.96.0.10`; `ndots:5`; use trailing-dot FQDNs hot paths; NodeLocal DNSCache.
- **Ingress:** object + controller (NGINX/Envoy/Traefik); Gateway API is the future.
- **NetworkPolicy:** default allow-all → selecting a Pod makes it default-deny; CNI enforces (not Flannel-alone); always allow egress :53.
- **externalTrafficPolicy:** `Local` preserves source IP/no hop but can drop; `Cluster` spreads but SNATs.
- **Debug ladder:** DNS → Service VIP → raw Pod IP → EndpointSlices → NetworkPolicy. Tools: `netshoot`, `iptables-save`, `ipvsadm -Ln`, `conntrack -S`, `hubble observe`, `calicoctl`.
- **Watch:** conntrack exhaustion, MTU mismatch (overlay), missing DNS egress, long-lived connections not rebalancing, CNI without policy support.

### Self-test (no answers)
1. Trace a packet from a client Pod to a backend Pod through a ClusterIP in iptables mode, naming every NAT/conntrack step.
2. Why does scaling up a Deployment fronted by a ClusterIP not relieve an overloaded gRPC service, and what three fixes apply?
3. You add an egress NetworkPolicy and the app starts timing out on every outbound call. What's the most likely single missing rule, and why does it manifest as "hangs"?
4. Compare iptables, IPVS, and eBPF data planes for a 4,000-service cluster across lookup cost, update cost, and observability — and recommend one with justification.
5. Design DNS and Service topology for a 3-broker Kafka cluster needing stable peer addresses; name the exact object types and the resulting FQDNs.
6. A Pod reaches a backend by Pod IP but not by the Service IP. List your diagnosis steps in order and the command for each.
7. Explain how `externalTrafficPolicy: Local` interacts with a cloud LoadBalancer's health checks and client source-IP preservation, and the failure it can introduce.

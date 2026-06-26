# Load Balancing & Service Discovery

> An exhaustive engineering-handbook chapter for senior backend developers (Java/JVM-centric) who want to master load balancing and service discovery from first principles to deep internals — enough to design, operate, debug, teach, and interview on the topic.

---

## 1. Overview & where it fits

**What it is.** *Load balancing* is the practice of spreading inbound work (requests, connections, packets, messages) across multiple *backends* (server instances) so that no single instance is overwhelmed, capacity scales horizontally, and failures of individual instances are masked from clients. *Service discovery* is the complementary mechanism that answers the question **"which instances exist right now, and where (IP:port) are they?"** — because in any dynamic system, the set of healthy backends changes constantly as instances start, stop, crash, get redeployed, or autoscale.

The two are joined at the hip: a load balancer is useless without an up-to-date list of backends, and a discovery system is useless unless something acts on its output to route traffic. In practice you almost always design them together.

**The problem they solve.** Consider a single server. It has a finite CPU, finite memory, a finite number of open file descriptors and TCP connections, and a single failure domain (if it dies, you have a total outage). To serve more traffic and to survive failures you run *N* copies of the service. The instant you have *N* copies you face three new problems:

1. **Distribution** — how do you decide which of the *N* instances handles each request, fairly and efficiently?
2. **Discovery** — how does the thing making that decision learn the current set of healthy instances when they come and go every few seconds?
3. **Failure handling** — how do you stop sending traffic to an instance that is dead, slow, or returning errors, *quickly*, without a human in the loop?

Load balancing + service discovery is the standard answer to all three.

**When you reach for it.** Essentially always, once you run more than one instance of anything that receives traffic:

- A stateless web/API tier behind a public endpoint.
- Internal service-to-service (microservices) calls.
- Database read replicas, cache clusters, message brokers.
- Multi-region / multi-datacenter deployments (global load balancing).

**One-paragraph mental model.** Think of load balancing as a **dispatcher** standing in front of a pool of identical workers. The dispatcher keeps a *live roster* of which workers are present and healthy (that roster is maintained by *service discovery*, via heartbeats and health checks). For each incoming job the dispatcher applies a *policy* (round-robin, least-busy, hash-by-key, etc.) to pick a worker, forwards the job, watches the result, and if a worker is slow or failing, *ejects* it from the roster temporarily. Everything else in this chapter is detail on: where the dispatcher sits (in the network, the client, or a sidecar), what layer it operates at (L4 packets vs L7 requests), how the policy is computed, how the roster is kept fresh, and how all of this behaves under failure, retries, and autoscaling.

**Key terms you'll see immediately (each fully defined later in §11 Glossary):**
- *Backend / upstream / origin*: one server instance that actually does the work.
- *Frontend / VIP (virtual IP)*: the single address clients talk to.
- *L4 / L7*: which OSI layer the balancer inspects — transport (TCP/UDP) vs application (HTTP/gRPC).
- *Health check*: an active or passive probe to decide if a backend is usable.
- *Service registry*: the database of "who is alive and where."

---

## 2. Foundations from first principles

We build the concepts from zero. If you already know the OSI model, skim — but the precise definitions matter for L4-vs-L7 reasoning.

### 2.1 The networking layers (just enough)

The **OSI model** is a 7-layer reference model for how data moves across a network. For load balancing only a few layers matter:

- **Layer 3 — Network layer (IP).** Concerned with *IP addresses* and routing packets between hosts. An *IP address* is the numeric address of a host (e.g., `10.0.1.5`). Routing here is per-*packet*.
- **Layer 4 — Transport layer (TCP/UDP).** Concerned with *ports* and *connections*. **TCP (Transmission Control Protocol)** is a connection-oriented, reliable, ordered byte-stream protocol: a *connection* is established with a 3-way handshake (SYN, SYN-ACK, ACK) and identified by the 4-tuple `(src IP, src port, dst IP, dst port)`. **UDP (User Datagram Protocol)** is connectionless and unreliable (fire-and-forget datagrams). A *port* is a 16-bit number identifying which application on a host a packet is for (e.g., 443 for HTTPS).
- **Layer 7 — Application layer.** The actual protocol semantics: **HTTP** (the web's request/response protocol), **gRPC** (a high-performance RPC framework over HTTP/2), WebSocket, MySQL wire protocol, etc. Here you can see *URLs, headers, methods, cookies, and message boundaries*.

A load balancer that operates at **L4** only sees connections and packets — it forwards a whole TCP connection to one backend and never looks inside. A load balancer at **L7** terminates the connection, parses the application protocol, and can route per-request (different URLs to different pools), inject headers, retry, etc.

### 2.2 What "balancing" actually means

You have a *pool* of backends `B = {b1, b2, ..., bn}`. A stream of *units of work* arrives. A load balancer is a function that, for each unit, picks a backend. "Balanced" can mean several different objectives, and they conflict:

- **Equal count** — each backend gets the same *number* of units (round-robin's goal).
- **Equal load** — each backend has the same *instantaneous load* (least-connections / least-requests goal). This is better when requests have wildly different costs.
- **Minimized latency** — route to wherever the response will come back fastest (EWMA / least-latency goal).
- **Affinity** — the *same key* always goes to the *same backend* (consistent hashing, sticky sessions), trading balance for cache locality or session state.

There is no universally best policy; the right one depends on whether requests are uniform, whether backends are uniform, and whether state/cache locality matters. §3 and §4 detail each.

### 2.3 The unit of distribution: connection vs request

This distinction is the source of endless confusion, so nail it now:

- An **L4 load balancer balances *connections*.** Once a TCP connection is pinned to backend `b3`, *every* request on that connection goes to `b3` for the connection's lifetime. With HTTP/1.1 keep-alive or HTTP/2 multiplexing (many requests over one long-lived connection), this means L4 balances poorly at the *request* level — one chatty connection can pile work on one backend.
- An **L7 load balancer balances *requests*.** It terminates the client connection, then forwards each request independently, often over a *pool of reused connections* to the backends. This gives true per-request balancing and is why L7 is preferred for HTTP/2 and gRPC, where a single client connection may carry thousands of requests.

### 2.4 Stateless vs stateful backends

A **stateless** backend keeps no per-client state between requests — any instance can serve any request. This is the ideal: you can balance freely and add/remove instances at will. A **stateful** backend stores something tied to the client (an in-memory session, a local cache, a shard of data). State forces *affinity* — the client must keep hitting the same instance — which is exactly what sticky sessions and consistent hashing provide, at the cost of balance and resilience. The modern best practice is to **externalize state** (sessions in Redis, data in a database) so the application tier stays stateless.

### 2.5 Why discovery is unavoidable

If backends had fixed, forever-stable IPs, you could hardcode the pool. They don't:

- **Autoscaling** adds/removes instances based on load (could change every minute).
- **Rolling deploys** replace every instance, one batch at a time.
- **Crashes / spot-instance reclaims / node failures** remove instances unpredictably.
- **Cloud / container schedulers** (Kubernetes, Nomad) place instances on arbitrary hosts with ephemeral IPs.

So *something* must continuously track the live set. That something is the **service registry** plus a **propagation mechanism** that gets the current set to whoever is doing the balancing. §3.4 and §4.4 go deep.

### 2.6 Health: liveness vs readiness vs outlier

Three different "is it usable?" questions, often conflated:

- **Liveness** — "is the process alive at all?" If not, restart it. (Kubernetes *liveness probe*.)
- **Readiness** — "is it ready to receive traffic *right now*?" A process can be alive but warming up, doing GC, or draining for shutdown. (Kubernetes *readiness probe*.) Only ready instances should be in the LB pool.
- **Outlier detection** — "is it *misbehaving* relative to its peers, based on real traffic?" Even a backend that passes its health check might be returning 5xx or being slow; *passive* outlier ejection notices this from actual request outcomes and temporarily removes it.

### 2.7 The CAP context for registries

A service registry is a distributed datastore, so the **CAP theorem** applies: in the presence of a *network partition* (P — some nodes can't talk to each other), you must choose between **Consistency** (C — every read sees the latest write) and **Availability** (A — every request gets a non-error response). 

- **CP registries** (ZooKeeper, Consul's default, etcd) prefer consistency: during a partition, the minority side may refuse to serve, but you never get stale/conflicting registration data.
- **AP registries** (Eureka by design) prefer availability: during a partition each side keeps serving whatever it last knew, accepting that the data may be stale.

For *discovery*, AP is often the better choice — a slightly stale list of mostly-correct backends is far better than no list at all, and health checks at the LB will catch the few wrong entries. We revisit this in §4.4 and §8.

---

## 3. How it works internally

This is the heart of the chapter. We trace the actual control flow and data flow for each major piece.

### 3.1 The life of a request through a server-side L7 load balancer

Consider a client calling `https://api.example.com/v1/orders` behind an L7 LB (e.g., Envoy, NGINX, AWS ALB, HAProxy in HTTP mode). Step by step:

1. **DNS resolution.** The client resolves `api.example.com` to one or more IPs (the LB's *VIP*s — virtual IPs). DNS may itself be a crude load balancer (returns multiple A records; client picks one). *DNS* (Domain Name System) is the distributed directory mapping names to IPs.
2. **TCP + TLS to the LB.** Client opens a TCP connection to the VIP and performs the **TLS handshake** (TLS = Transport Layer Security, encrypts the connection). The LB **terminates TLS** — it holds the certificate and decrypts, so it can read L7 data. (Alternatively *TLS passthrough* keeps it encrypted, forcing L4 behavior.)
3. **Request parsing.** The LB parses the HTTP request line, headers, and (for routing) maybe the path/host. It now has enough to choose a *route* → a *cluster* (named backend pool).
4. **Backend selection.** Within the chosen cluster the LB applies its **load-balancing policy** (round-robin, least-request, ring-hash, etc.) over the set of *healthy* endpoints. Healthy = passing active health checks AND not currently ejected by outlier detection.
5. **Connection management.** The LB picks (or opens) a connection from its **upstream connection pool** to the chosen backend. Reusing pooled connections avoids per-request TCP/TLS setup cost.
6. **Forwarding.** It rewrites/adds headers (`X-Forwarded-For` = original client IP, `X-Request-ID` for tracing, etc.) and streams the request to the backend.
7. **Response handling.** Backend responds; LB streams the response back to the client. It records *timing* and *status code* — feeding latency stats (for EWMA) and outlier detection (for 5xx counting).
8. **Retry / failover (optional).** If the backend returns a retryable failure (connection refused, 503, timeout — only for *idempotent* requests by default), the LB may transparently retry on a *different* backend, subject to a retry budget.
9. **Connection lifecycle.** Keep-alive connections stay in the pool for reuse; idle ones are closed after a timeout.

The **control plane** (separate from this data path) continuously: pulls the backend set from service discovery, runs active health checks, computes which endpoints are healthy, and pushes that to the data plane.

### 3.2 The life of a packet through a server-side L4 load balancer

L4 LBs (AWS NLB, Google Maglev, Linux IPVS, HAProxy in TCP mode) work very differently — they never parse application data. There are two dominant forwarding modes:

**(a) NAT / proxy mode.** The LB rewrites packet addresses:
1. Client SYN arrives at VIP.
2. LB picks a backend for this *new connection* using a hash of the 4-tuple (so all packets of the connection go to the same backend) or a stateful connection table.
3. LB rewrites destination IP to the backend, forwards. Return traffic flows back through the LB, which rewrites the source back to the VIP. The LB is on both the request and response path.

**(b) Direct Server Return (DSR) / Maglev-style.** The LB only touches the *inbound* path; responses go *directly* from backend to client, bypassing the LB. This massively reduces LB bandwidth (responses are usually much bigger than requests). Google's **Maglev** is a famous software L4 LB using **consistent hashing + connection tracking** so that even as the backend set changes, existing connections stay pinned (minimal disruption), implemented with ECMP (Equal-Cost Multi-Path) routing spreading flows across many Maglev instances.

Key consequence: L4 has no idea about HTTP. It cannot do path-based routing, header injection, per-request retries, or response-code-based ejection. It *can* be blazing fast and protocol-agnostic (works for any TCP/UDP service, including databases and custom protocols).

### 3.3 Load-balancing algorithms — the actual math and mechanics

#### Round-robin (RR)
Maintain a counter `i`; for each request pick `B[i mod n]`, increment `i`. **Weighted round-robin (WRR)** assigns each backend a weight `w_k` and distributes proportionally (e.g., interleaved or via a smooth WRR algorithm like NGINX's, which avoids bursts to the heavy node). 
- *Assumes*: requests are roughly equal cost and backends are roughly equal capacity.
- *Fails when*: request costs vary wildly (one slow query stuck behind 100 fast ones), or backends are heterogeneous and unweighted.

#### Least-connections / least-request
Track the number of in-flight requests (or open connections) per backend; route the new request to the one with the fewest. Naturally adapts to slow requests and slow backends (they accumulate in-flight work, so they get sent less). 
- *Implementation note*: exact least-connections requires global state (a shared counter), which is expensive across many LB instances or many clients. Hence the popular approximation below.

#### Power of Two Choices (P2C / "the two random choices" / `least_request` in Envoy)
Pick **two** backends at random, then send the request to whichever of the two has fewer in-flight requests. 
- *Why it's brilliant*: It avoids the need for global coordination yet provably keeps the *maximum* load very close to the average — the expected max load is exponentially better than pure random and almost as good as exact least-connections, with O(1) cost and no thundering-herd "everyone picks the same idle node" problem that exact least-connections can cause in a distributed setting.
- This is the **default** least-request implementation in Envoy and many modern meshes.

#### EWMA / least-latency (least-time)
Route to the backend with the lowest **EWMA (Exponentially Weighted Moving Average)** of recent response latency. EWMA = a moving average that weights recent samples more heavily: `ewma = α·sample + (1-α)·ewma`, where `α` (0–1) controls responsiveness. 
- Reacts to *actual* slowness (GC pauses, hot backends) faster than connection counts.
- Often combined with P2C: pick two at random, choose the lower-EWMA one. NGINX Plus and Finagle (Twitter's JVM RPC library) use latency-aware policies; Envoy's `least_request` uses P2C on in-flight counts (a latency proxy).

#### Consistent hashing
Goal: the *same key* (e.g., a user ID, a cache key, a session) maps to the *same backend*, even as backends are added/removed — with **minimal remapping**. Naive `hash(key) mod n` is terrible: changing `n` remaps almost every key. Consistent hashing solves this:
- Imagine a *ring* of hash values `[0, 2^32)`. Place each backend at multiple points on the ring (its *virtual nodes* / *vnodes*, e.g., 100–1000 per backend for even spread). To route a key, hash it to a point and walk clockwise to the first backend. 
- Adding/removing a backend only remaps the keys in the arc it owned — roughly `1/n` of keys move, not all of them.
- **Ring hash** (Envoy `ring_hash`) and **Maglev hashing** (Envoy `maglev`, a flat lookup table giving better balance and faster lookups than a ring) are the two common implementations. Maglev hashing trades a tiny bit of disruption-on-change for much better load uniformity and O(1) lookups.
- *Use for*: cache affinity (route a key to the node that already cached it), sticky-ish routing without server-side session state, sharding.

#### Random
Pure uniform random pick. Cheap, stateless, surprisingly decent at scale, but variance is high (some backends get unlucky bursts). P2C is "random, but fix the worst case."

**Summary comparison** (expanded in §4.1):

| Algorithm | State needed | Adapts to slow backends? | Affinity? | Best when |
|---|---|---|---|---|
| Round-robin | counter | No | No | uniform requests & backends |
| Weighted RR | counter+weights | No | No | heterogeneous capacity |
| Least-connections | per-backend in-flight | Yes | No | variable request cost |
| P2C least-request | per-backend in-flight | Yes | No | distributed LB, want least-conn benefits cheaply |
| EWMA / least-time | per-backend latency | Yes (fastest) | No | latency-sensitive, GC-prone backends |
| Consistent hash | ring/table | indirectly | Yes | cache locality, sharding, sticky |
| Random | none | No | No | huge scale, simplicity |

### 3.4 Health checks & outlier ejection — internal workflow

**Active health checks** (the LB proactively probes):
1. On an interval (e.g., every 5s) the LB sends a probe to each backend: a TCP connect, an HTTP GET to `/healthz` expecting 200, or a gRPC health-check RPC.
2. It applies a **healthy threshold** (e.g., 2 consecutive successes to mark healthy) and an **unhealthy threshold** (e.g., 3 consecutive failures to mark unhealthy) — hysteresis prevents flapping.
3. Healthy/unhealthy state is updated; only healthy endpoints are eligible for selection.

**Passive health checks / outlier detection** (the LB learns from *real* request outcomes — no extra probes):
1. As real requests flow, the LB counts per-backend failures: consecutive 5xx, consecutive gateway errors (connection failures/timeouts), or a backend whose success rate / latency is a statistical *outlier* vs the pool.
2. When a backend crosses a threshold (e.g., 5 consecutive 5xx), it is **ejected** — temporarily removed from selection.
3. Ejection duration uses **exponential backoff**: base ejection time × number of times this host has been ejected. So a repeatedly-bad host stays out longer.
4. A safety valve — `max_ejection_percent` — caps how much of the pool can be ejected at once (e.g., 10–50%), so a *correlated* failure (everything returns 5xx because a downstream DB is down) doesn't eject the entire pool and cause a total outage.
5. After the ejection time, the host is returned to the pool and re-evaluated.

The combination matters: active checks catch dead/unready hosts; outlier detection catches hosts that *pass* their health check but are actually serving errors or slowly. Real systems use both.

### 3.5 Service discovery — internal workflows

There are two architectural shapes. Understand both.

**A. The registry/heartbeat model (client- or LB-driven):**
1. **Registration.** When an instance starts, it *registers* itself with the registry: `POST /register {service: orders, ip: 10.0.1.5, port: 8080, metadata: {...}}`. (Or an agent/sidecar registers on its behalf.)
2. **Heartbeats / leases.** The instance periodically sends a heartbeat (renews a *lease*/TTL). If heartbeats stop, the registry expires the entry after the TTL — automatic deregistration of dead instances.
3. **Health.** The registry (or its agents) may also run health checks and mark entries healthy/unhealthy.
4. **Discovery / watch.** Consumers either *poll* (`GET /services/orders` periodically) or *watch* (long-poll / streaming subscription) to get push updates when the set changes.
5. **Caching.** Consumers cache the result locally so a registry outage doesn't immediately break traffic (graceful degradation).

**B. The orchestrator-native model (Kubernetes):**
1. Each *Pod* (the smallest deployable unit, one or more containers) has a label, e.g., `app=orders`.
2. A **Service** object defines a stable virtual IP (the *ClusterIP*) and a *selector* (`app=orders`).
3. The control plane's *endpoints controller* watches Pods matching the selector and maintains an **EndpointSlice** (the live list of ready Pod IPs:ports). *Readiness probes* gate membership.
4. On every node, **kube-proxy** (or a CNI/eBPF dataplane like Cilium) programs the kernel (iptables or IPVS rules, or eBPF maps) so that traffic to the ClusterIP is DNAT'd (destination-rewritten) to a randomly/round-robin-chosen ready Pod IP. This is *L4, client-side-in-the-kernel* load balancing.
5. **DNS** (CoreDNS) maps `orders.namespace.svc.cluster.local` → ClusterIP, so apps just use the name.

So in Kubernetes the "registry" is the API server + EndpointSlices, "discovery" is DNS + the endpoints controller, and "load balancing" is kube-proxy at L4. For L7 you add an *Ingress*/Gateway controller or a service mesh.

### 3.6 Client-side vs server-side load balancing — control/data flow

**Server-side LB:** clients send to a single VIP; a dedicated middlebox (the LB) holds the backend list and chooses. Clients are dumb. Pros: simple clients, central policy. Cons: extra network hop, the LB is a bottleneck/failure domain, it must scale with traffic.

**Client-side LB:** the client itself holds the backend list (from discovery) and chooses a backend directly — no middlebox in the data path. Examples: gRPC's built-in client-side LB, Netflix Ribbon (legacy), Spring Cloud LoadBalancer, Finagle. Pros: no extra hop (lower latency), no central bottleneck, fine-grained per-client policy. Cons: every client must embed LB + discovery logic (language-specific libraries), config changes must propagate to all clients, harder to enforce global policy.

**Sidecar / service-mesh LB (the modern hybrid):** each service instance gets a co-located proxy (the *sidecar*, e.g., Envoy in Istio/Linkerd). The app makes a plain `localhost` call; the sidecar does discovery, LB, retries, mTLS, and observability. You get client-side LB's no-central-bottleneck benefit and server-side LB's "app stays dumb, policy is centralized (in the mesh control plane)" benefit, at the cost of running a proxy next to everything (latency + memory overhead). This is covered more in §7.

---

## 4. The complete toolkit

### 4.1 Load-balancing algorithm reference

| Algorithm | Envoy name | NGINX directive | HAProxy `balance` | Notes / defaults |
|---|---|---|---|---|
| Round-robin | `ROUND_ROBIN` (default) | `round_robin` (default) | `roundrobin` | Weighted variants supported everywhere |
| Weighted RR | weights on endpoints | `weight=` on server | `weight` per server | NGINX uses *smooth* WRR |
| Least-request/conn | `LEAST_REQUEST` (P2C, choice_count default 2) | `least_conn` | `leastconn` | Envoy's is P2C, not exact |
| Ring hash | `RING_HASH` | `hash $key consistent` | `hash-type consistent` | min/max ring size tunable |
| Maglev hash | `MAGLEV` | — | — | flat table, O(1), better balance |
| Random | `RANDOM` | `random` (NGINX 1.15.1+) | `random` | `random two least_conn` ≈ P2C |
| EWMA/least-time | (via subset/Finagle) | `least_time` (NGINX **Plus** only) | — | latency-aware |

### 4.2 Health-check / outlier configuration (Envoy as the concrete example, with typical defaults)

| Concept | Envoy field | Typical / default value | Meaning |
|---|---|---|---|
| Active check interval | `health_checks.interval` | 5s (you set it) | how often to probe |
| Timeout | `health_checks.timeout` | 1–5s | probe timeout |
| Healthy threshold | `healthy_threshold` | 2 | consecutive OK to mark healthy |
| Unhealthy threshold | `unhealthy_threshold` | 3 | consecutive fail to mark unhealthy |
| HTTP check path | `http_health_check.path` | `/healthz` (you set) | endpoint probed |
| Consecutive 5xx | `outlier_detection.consecutive_5xx` | 5 | eject after N 5xx |
| Consecutive gateway errors | `consecutive_gateway_failure` | 5 | conn refused/timeout |
| Base ejection time | `base_ejection_time` | 30s | ejection backoff base |
| Max ejection % | `max_ejection_percent` | **10%** (default) | safety cap on pool removal |
| Interval | `outlier_detection.interval` | 10s | analysis sweep period |
| Success-rate ejection | `enforcing_success_rate` | 100% (when enough hosts) | statistical outlier ejection |

> Version note: exact defaults and field names are Envoy-specific and change across versions; always check the version's docs. The *shape* (interval / thresholds / backoff / safety cap) is universal across LBs.

### 4.3 Sticky-session (session affinity) mechanisms

| Mechanism | How affinity is keyed | Where used | Caveat |
|---|---|---|---|
| Source-IP hash | client IP | L4 LBs, NLB, IPVS | breaks behind NAT/proxies (many clients = 1 IP) |
| Cookie-based (LB-generated) | LB sets a cookie (e.g., `AWSALB`, `SERVERID`) naming the backend | L7 LBs (ALB, NGINX, HAProxy) | needs L7; cookie can be lost/cleared |
| Application cookie | hash of an existing app cookie (e.g., `JSESSIONID`) | L7 LBs | requires app cooperation |
| Consistent hash on header/key | `ring_hash`/`maglev` on a header | Envoy, NGINX | best modern approach; survives backend churn |

**When sticky sessions hurt** (covered fully in §6/§8): they undermine balance (a "whale" client pins load), break graceful scaling (removing a sticky backend dumps its sessions), complicate deploys (draining), and create a hidden dependency on local state. Prefer externalizing session state (Redis/DB) and using affinity only for *cache locality*, not *correctness*.

### 4.4 Service-discovery systems reference

| System | Model | CAP lean | Discovery mechanism | Health | Typical ecosystem |
|---|---|---|---|---|---|
| **DNS** | name→IP records | (eventual) | A/AAAA/SRV records, TTL | none (DNS itself) | universal, simplest |
| **Consul** (HashiCorp) | agent + Raft KV | **CP** (Raft) | DNS interface + HTTP API + watches | rich (script/HTTP/TCP/gRPC) | polyglot, VMs+K8s |
| **Eureka** (Netflix/Spring) | registry + client cache | **AP** (by design) | REST, client-side cache, self-preservation | client heartbeats | Spring Cloud / JVM |
| **etcd** | Raft KV | **CP** | watch on keys | external | Kubernetes' own store |
| **ZooKeeper** | ZAB consensus | **CP** | ephemeral znodes + watches | session/ephemeral nodes | Hadoop/Kafka era |
| **Kubernetes Services** | API + EndpointSlices | (CP store, AP-ish DNS cache) | ClusterIP + CoreDNS + kube-proxy | readiness probes | K8s native |
| **AWS Cloud Map / ELB target groups** | managed | managed | DNS/API | ELB health checks | AWS |

**Adjacent terms defined:**
- **Raft** — a consensus algorithm that keeps a replicated log consistent across a cluster by electing a *leader* that orders all writes; a write commits once a *majority* (quorum) acknowledges. Used by Consul and etcd. Strong consistency, but a partition can make a minority unavailable.
- **ZAB (ZooKeeper Atomic Broadcast)** — ZooKeeper's leader-based consensus protocol, conceptually similar to Raft.
- **Ephemeral node (znode)** — in ZooKeeper, a node tied to a client *session*; if the client's session dies (heartbeat lost), the node auto-deletes. This is how ZooKeeper does liveness: instances create ephemeral nodes, and their disappearance signals death.
- **SRV record** — a DNS record type that returns *host + port* (and priority/weight), unlike A records which return only an IP. Useful when ports vary.
- **TTL (Time To Live)** — how long a DNS answer (or registry lease) may be cached before it must be refreshed. Low TTL = faster failover but more lookups.
- **Self-preservation (Eureka)** — if Eureka suddenly stops receiving expected heartbeats from *many* clients at once, it assumes a *network partition* (not mass death) and *stops expiring* registrations, keeping stale-but-mostly-correct data rather than emptying the registry. Classic AP behavior.

### 4.5 Java/JVM client-side LB & discovery toolkit

| Tool | Role | Notes |
|---|---|---|
| **Spring Cloud LoadBalancer** | client-side LB | replaced Ribbon; `@LoadBalanced RestTemplate`/`WebClient`; round-robin & random built in, pluggable |
| **Spring Cloud Discovery** | discovery abstraction | `DiscoveryClient` over Eureka/Consul/Zookeeper/K8s |
| **Netflix Ribbon** | client-side LB (legacy) | deprecated; rules: `RoundRobinRule`, `WeightedResponseTimeRule`, `AvailabilityFilteringRule` |
| **Netflix Eureka** | AP registry | `eureka-client` registers + caches |
| **gRPC-Java** | client-side LB | `pick_first` (default) and `round_robin`; xDS for advanced; name resolver SPI for discovery |
| **Resilience4j** | retries/circuit-breaker | pairs with LB for failure handling |
| **Finagle** (Scala/JVM) | client-side RPC | P2C, EWMA, aperture LB, very advanced |
| **Consul / ZooKeeper / etcd Java clients** | registry access | `consul-api`, Curator (ZK), jetcd |

### 4.6 CLI / config quick reference

```bash
# DNS-based discovery: see what a name resolves to
dig +short api.example.com
dig SRV _http._tcp.orders.service.consul     # Consul SRV lookup

# Consul
consul members                                # cluster membership
consul catalog services                       # registered services
consul catalog nodes -service=orders          # instances of a service
curl localhost:8500/v1/health/service/orders?passing   # only healthy

# Kubernetes
kubectl get svc orders                        # the Service (ClusterIP)
kubectl get endpointslices -l kubernetes.io/service-name=orders
kubectl get pods -l app=orders -o wide        # the actual backend Pods + IPs
kubectl describe svc orders                   # selector, ports, endpoints

# Envoy admin (data-plane introspection)
curl localhost:9901/clusters                  # backends, health, in-flight, ejections
curl localhost:9901/stats | grep upstream     # LB & health-check stats

# HAProxy runtime
echo "show servers state" | socat stdio /var/run/haproxy.sock
echo "show stat" | socat stdio /var/run/haproxy.sock
```

---

## 5. Code examples by use case

### 5.1 Server-side L7 LB config — Envoy: P2C + active health + outlier ejection

```yaml
# envoy.yaml — an L7 HTTP proxy in front of an "orders" service.
static_resources:
  listeners:
  - name: ingress
    address: { socket_address: { address: 0.0.0.0, port_value: 8080 } }
    filter_chains:
    - filters:
      - name: envoy.filters.network.http_connection_manager
        typed_config:
          "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
          stat_prefix: ingress
          route_config:
            virtual_hosts:
            - name: orders_vh
              domains: ["*"]
              routes:
              - match: { prefix: "/" }
                route:
                  cluster: orders
                  # Per-request retries — ONLY safe defaults for idempotent verbs.
                  retry_policy:
                    retry_on: "5xx,connect-failure,refused-stream"
                    num_retries: 2
                    per_try_timeout: 1s
          http_filters:
          - name: envoy.filters.http.router
            typed_config:
              "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
  clusters:
  - name: orders
    connect_timeout: 1s
    type: STRICT_DNS            # discovery: re-resolve DNS for endpoints
    dns_refresh_rate: 5s
    lb_policy: LEAST_REQUEST    # Power-of-two-choices on in-flight requests
    least_request_lb_config: { choice_count: 2 }
    load_assignment:
      cluster_name: orders
      endpoints:
      - lb_endpoints:
        - endpoint: { address: { socket_address: { address: orders.svc, port_value: 8080 } } }
    health_checks:             # ACTIVE health checks
    - timeout: 1s
      interval: 5s
      unhealthy_threshold: 3
      healthy_threshold: 2
      http_health_check: { path: "/healthz" }
    outlier_detection:         # PASSIVE outlier ejection
      consecutive_5xx: 5
      base_ejection_time: 30s
      max_ejection_percent: 50   # never eject more than half (avoid total outage)
      interval: 10s
```

What matters: `STRICT_DNS` makes Envoy *re-resolve* the name on `dns_refresh_rate`, so discovery is "DNS does it." `LEAST_REQUEST` with `choice_count: 2` is P2C. Active checks + outlier detection run together. `max_ejection_percent: 50` is the safety cap against correlated failures.

### 5.2 Client-side LB in plain Java with gRPC (round-robin + DNS discovery)

```java
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class OrdersClient {
  public static ManagedChannel buildChannel() {
    return ManagedChannelBuilder
        // "dns:///" tells gRPC to use the DNS name resolver: it resolves
        // orders.svc to ALL A records and balances across them client-side.
        .forTarget("dns:///orders.svc:8080")
        // Client-side LB policy. pick_first (default) uses one backend;
        // round_robin spreads across every resolved address.
        .defaultLoadBalancingPolicy("round_robin")
        .usePlaintext()
        // Re-resolve DNS periodically so new/removed backends are picked up.
        .build();
  }
}
```

Here there is **no middlebox** — the gRPC channel itself holds the address list (from the DNS resolver) and round-robins requests across the live backends. Note the gotcha: by default gRPC only re-resolves DNS when a connection breaks, so for autoscaling you typically front it with a discovery system that pushes updates (xDS) or use a headless Kubernetes Service so each A record is a Pod IP.

### 5.3 Spring Cloud LoadBalancer + Eureka discovery (idiomatic JVM microservice)

```java
// build: spring-cloud-starter-loadbalancer + spring-cloud-starter-netflix-eureka-client

@Configuration
class HttpClientConfig {
  @Bean
  @LoadBalanced // <-- intercepts requests to logical service names and balances
  WebClient.Builder loadBalancedWebClientBuilder() {
    return WebClient.builder();
  }
}

@Service
class OrderGateway {
  private final WebClient client;
  OrderGateway(WebClient.Builder builder) { this.client = builder.build(); }

  public Mono<Order> fetch(String id) {
    // "orders" is the SERVICE NAME registered in Eureka, NOT a host.
    // Spring Cloud LoadBalancer resolves it to a live instance and round-robins.
    return client.get()
        .uri("http://orders/v1/orders/{id}", id)
        .retrieve()
        .bodyToMono(Order.class);
  }
}
```

```yaml
# application.yml — register with Eureka and discover via it
eureka:
  client:
    serviceUrl:
      defaultZone: http://eureka-1:8761/eureka/,http://eureka-2:8761/eureka/
    registry-fetch-interval-seconds: 30   # how often the local cache refreshes
  instance:
    lease-renewal-interval-in-seconds: 30 # heartbeat period (TTL ~90s default)
spring:
  cloud:
    loadbalancer:
      configurations: round-robin          # or 'health-check', or custom
```

The app calls `http://orders/...` — a *logical* name. Eureka client caches the instance list locally (AP: survives Eureka outages), and Spring Cloud LoadBalancer picks an instance. Heartbeats every 30s; lease expiry ~90s.

### 5.4 A correct application health endpoint (readiness vs liveness)

```java
// Spring Boot Actuator splits liveness (process alive) from readiness (take traffic).
// Liveness failing -> Kubernetes RESTARTS the pod.
// Readiness failing -> Kubernetes REMOVES it from the Service endpoints (no restart).

@Component
class DownstreamReadinessIndicator implements HealthIndicator {
  private final DataSource ds;
  DownstreamReadinessIndicator(DataSource ds) { this.ds = ds; }

  @Override public Health health() {
    // Readiness should reflect whether THIS instance can serve real traffic:
    // dependencies reachable, warm-up done, not draining. Keep it cheap & fast.
    try (var c = ds.getConnection()) {
      return c.isValid(1) ? Health.up().build()
                          : Health.down().withDetail("db", "invalid").build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
```

```yaml
# k8s deployment probes
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  periodSeconds: 5
  failureThreshold: 3        # 3 fails (≈15s) -> removed from LB pool
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  periodSeconds: 10
  failureThreshold: 3        # 3 fails -> restarted
```

The classic bug: putting a *deep dependency check* in the **liveness** probe. When the DB has a hiccup, every instance fails liveness and gets restarted simultaneously → cascading outage. Deep checks belong in **readiness** (remove from pool), not liveness (restart).

### 5.5 Consistent-hash routing for cache locality (Envoy ring_hash on a header)

```yaml
clusters:
- name: cache_tier
  lb_policy: RING_HASH
  ring_hash_lb_config:
    minimum_ring_size: 1024       # more vnodes => smoother distribution
  # ... endpoints ...
# In the route, hash on a stable key so the same user/key hits the same node:
routes:
- match: { prefix: "/" }
  route:
    cluster: cache_tier
    hash_policy:
    - header: { header_name: "x-user-id" }   # same user -> same cache node
```

When `cache_tier` scales up/down, ring hashing remaps only ~`1/n` of keys, so cache hit-rate stays high (unlike `hash mod n` which would invalidate nearly everything).

### 5.6 Registering & discovering with Consul from Java (Spring Cloud Consul)

```yaml
# application.yml
spring:
  cloud:
    consul:
      host: consul-agent
      port: 8500
      discovery:
        instance-id: ${spring.application.name}:${random.value}
        health-check-path: /actuator/health
        health-check-interval: 10s     # Consul actively probes this
        prefer-ip-address: true
        deregister-critical-service-after: 1m  # auto-cleanup dead instances
```

```java
@RestController
class Discovery {
  private final DiscoveryClient discovery;     // Spring abstraction over Consul
  Discovery(DiscoveryClient d) { this.discovery = d; }

  @GetMapping("/instances/{svc}")
  List<String> instances(@PathVariable String svc) {
    // Returns the CURRENT healthy instances of a service from Consul.
    return discovery.getInstances(svc).stream()
        .map(si -> si.getHost() + ":" + si.getPort())
        .toList();
  }
}
```

### 5.7 NGINX server-side LB with health checks and weighted backends

```nginx
upstream orders {
    least_conn;                       # least-connections policy
    server 10.0.1.5:8080 weight=3;    # bigger box gets 3x the traffic
    server 10.0.1.6:8080 weight=1;
    server 10.0.1.7:8080 backup;      # only used if the others are down
    keepalive 32;                     # reuse upstream connections (perf)
}
server {
    listen 443 ssl;
    location / {
        proxy_pass http://orders;
        proxy_next_upstream error timeout http_502 http_503;  # retry/failover
        proxy_connect_timeout 1s;
        proxy_read_timeout 5s;
    }
}
```

(Note: active health checks `health_check` and `least_time` are NGINX **Plus** features; open-source NGINX has only passive failover via `proxy_next_upstream` + `max_fails`/`fail_timeout`.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Connection reuse is everything.** Per-request TCP+TLS handshakes are expensive (extra RTTs, CPU for crypto). Always enable upstream keep-alive pools (NGINX `keepalive`, Envoy connection pools). For L7 in front of HTTP/2 backends, one multiplexed connection can carry many requests.
- **L4 is cheaper than L7.** L4 (especially DSR/Maglev) avoids TLS termination and payload parsing — use it when you don't need L7 features. eBPF/IPVS dataplanes (Cilium, kube-proxy IPVS mode) outperform iptables at high endpoint counts (iptables is O(n) rule evaluation).
- **Algorithm cost.** RR/random are O(1); exact least-connections needs shared/atomic counters; P2C gives ~least-connections quality at O(1) and no global contention — prefer it in distributed/multi-LB setups.
- **DNS TTL vs failover speed.** Low TTL = faster reaction to backend changes but more lookups and resolver load. Many clients/JVMs *cache DNS too long* (see §9) — a real production trap.

### 6.2 Correctness & concurrency
- **Retries must respect idempotency.** Auto-retrying a non-idempotent `POST` can double-charge a customer. Default to retrying only idempotent methods (GET/PUT/DELETE) or requests tagged with an *idempotency key*.
- **Retry budgets / retry storms.** Naive "retry 3x on every client" multiplies load exactly when the system is already struggling → *retry storm* → metastable collapse. Use a **retry budget** (cap retries to e.g. 10–20% of original requests) and **exponential backoff with jitter**.
- **Health-check semantics.** A health check that is too shallow (TCP connect only) marks a hung app as healthy; too deep (checks every dependency) causes correlated ejection. Aim for "can I serve a representative request?"

### 6.3 Security
- **Where TLS terminates** decides who sees plaintext. Terminating at the LB simplifies certs but means the LB↔backend hop may be plaintext (use **mTLS** internally; a service mesh automates this). **mTLS (mutual TLS)** = both client and server present certificates, so each authenticates the other — the backbone of zero-trust meshes.
- **The registry is a high-value target.** Anyone who can write to it can hijack traffic (register a malicious endpoint). Lock down registration (ACLs, mTLS), and validate endpoints.
- **Header trust.** `X-Forwarded-For` can be spoofed; only trust it from your own LBs. The LB should *overwrite*, not append blindly, the client-supplied value.

### 6.4 Observability
- **The four golden signals per backend:** latency, traffic, errors, saturation — and crucially *per endpoint* so you can spot the one bad host.
- **Must-have metrics:** in-flight requests per backend, per-backend 5xx rate, health-check pass/fail, **ejection events and ejection percentage**, connection-pool overflow, retry counts. Envoy's `/clusters` and `/stats` expose all of these.
- **Distributed tracing** (`X-Request-ID`, W3C `traceparent`) lets you follow a request across the LB hops and through retries.

### 6.5 Cost
- **Cross-zone traffic costs money** (cloud charges for cross-AZ data transfer). LBs that prefer *same-zone* backends (Envoy `LOCALITY_WEIGHTED_LB`/zone-aware routing, Kubernetes `Topology Aware Routing`) cut both latency and bill — but you must keep enough same-zone capacity or you'll overload one zone.
- **LB instances themselves cost** (managed LB hourly + per-GB/LCU fees). L4 + DSR reduces LB bandwidth cost dramatically.

### 6.6 Testing & production hardening
- **Test the failure paths, not just the happy path:** kill a backend mid-request, make one backend slow (not dead), partition the registry, return 503s, exhaust connection pools. Use fault injection (Envoy fault filter, chaos tooling).
- **Graceful shutdown / connection draining:** on shutdown, set readiness to *not ready* first (so the LB stops sending new requests), then finish in-flight requests, *then* exit. Otherwise rolling deploys drop requests. Honor `SIGTERM` and a `preStop` delay in Kubernetes.
- **Slow-start / warm-up:** a freshly-added backend (cold caches, JIT not warmed) shouldn't immediately get full traffic. NGINX `slow_start`, Envoy `slow_start_config` ramp it up.

### 6.7 Anti-patterns (avoid these)
- Deep dependency checks in the **liveness** probe (causes mass restarts).
- **Sticky sessions for correctness** (instead of externalizing state) — fragile, unbalanced.
- `hash(key) mod n` for sharding/caching (massive remapping on scale change) — use consistent hashing.
- Unbounded auto-retries with no budget/backoff (retry storms).
- `max_ejection_percent: 100` or no cap (a correlated failure ejects the whole pool → total outage).
- Trusting DNS round-robin alone for failover (no health awareness; clients cache stale IPs).
- One giant connection from a chatty client through an L4 LB expecting per-request balance (L4 balances connections, not requests).

---

## 7. Advanced topics & deep internals

### 7.1 Service mesh & the xDS protocol
A **service mesh** (Istio, Linkerd, Consul Connect) moves LB, discovery, retries, mTLS, and telemetry into a **sidecar proxy** (usually Envoy) next to every workload. A central **control plane** computes config and pushes it to the proxies via **xDS** — Envoy's "discovery service" gRPC streaming APIs: **EDS** (Endpoint Discovery — the live backend list, i.e., service discovery), **CDS** (Clusters), **LDS** (Listeners), **RDS** (Routes), **SDS** (Secrets/certs). The data plane (sidecars) does client-side LB with mesh-grade policies; the control plane gives centralized governance. The cost: per-pod proxy memory/CPU and an added in-process hop (typically sub-millisecond, but real). *Ambient/sidecarless* meshes (Istio ambient, Cilium) reduce this by moving L4 to a per-node eBPF dataplane and L7 to shared waypoint proxies.

### 7.2 Locality / zone-aware load balancing
Backends are tagged with region/zone (locality). The LB prefers the *closest healthy* locality and only spills to farther ones when the local pool is degraded — using **locality-weighted** algorithms that compute an "overprovisioning factor": if local healthy capacity drops below a threshold, weight gradually shifts to remote zones rather than all-or-nothing. This balances latency, cost (cross-AZ fees), and resilience.

### 7.3 Subset load balancing
With thousands of backends, having every client/LB track and probe *all* of them is wasteful (the *N×M* connection problem). **Subsetting** (Envoy subset LB, gRPC deterministic subsetting) has each client connect to only a *random subset* (e.g., 20) of backends, chosen so that overall every backend gets roughly equal client coverage. Reduces connection counts and health-check overhead at large scale; the tradeoff is slightly worse balance and the risk of an unlucky subset if not deterministic.

### 7.4 Maglev hashing internals
Maglev builds a fixed-size **lookup table** (e.g., 65537 entries — a prime) by having each backend "claim" table slots in a deterministic permutation order until the table is full and roughly even. Lookups are O(1) (hash the flow → index the table). When a backend is removed, only its slots are reassigned, so *most* flows keep their backend (minimal disruption), and the result is far more *uniform* than a hash ring with vnodes. Combined with **connection tracking**, in-flight connections are protected even across table changes. This is why Google's Maglev and Envoy's `MAGLEV` policy are preferred over plain ring hash for both balance and stability.

### 7.5 The thundering-herd / herd-on-recovery problem
When a downed backend recovers, naive least-connections sends it *all* new traffic at once (it has 0 in-flight) → it gets crushed and may fail again (oscillation). Mitigations: **slow-start ramp**, P2C (it only sometimes picks the empty node), and EWMA (latency rises before it's overwhelmed). Same family of problems: simultaneous health-check probes (add jitter), simultaneous DNS re-resolution, simultaneous retry waves.

### 7.6 Connection-pool internals & HTTP/2 head-of-line
For HTTP/1.1 the LB needs one upstream connection per concurrent request (pool sized accordingly). For HTTP/2/gRPC, one connection multiplexes many streams — but a single TCP connection can suffer **head-of-line blocking** at the TCP layer (one lost packet stalls all streams). HTTP/3 (QUIC over UDP) fixes this with independent streams. LBs cap `max_concurrent_streams` and `max_requests_per_connection` to force periodic re-balancing (otherwise long-lived H2 connections pin too much to one backend — a real L7 balance pitfall with gRPC).

### 7.7 Eventual consistency of the endpoint set
There is always a window where the *true* set of live backends and the LB's *believed* set differ: an instance just died but its heartbeat TTL hasn't expired, or a new instance registered but EDS hasn't propagated. This is why **the LB must have its own active health checks and outlier detection** as a fast safety net independent of the (slower, eventually-consistent) discovery system. Discovery answers "who *should* exist"; health checks answer "who *can* serve *right now*."

### 7.8 GSLB / Anycast / global routing
- **GSLB (Global Server Load Balancing)** balances across *datacenters/regions*, usually via **DNS-based** steering: the authoritative DNS server returns different IPs based on the client's geo-location, latency, or datacenter health (e.g., AWS Route 53 latency/geo/failover routing policies, NS1, Akamai). Limitation: DNS caching/TTL means failover isn't instant and the client's *resolver* location (not the user's) is what's measured.
- **Anycast** advertises the *same IP* from many locations via **BGP (Border Gateway Protocol — the internet's routing protocol)**; the network routes each client to the *topologically nearest* advertisement. Used by CDNs and DNS roots. Failover is fast (BGP withdrawal) and there's no DNS-TTL lag, but you have little per-request control and TCP connections can occasionally re-route mid-flow ("anycast flap"). Modern stacks combine **Anycast at the edge** (to the nearest PoP) with **L7 LB inside** each PoP.

### 7.9 Interaction with autoscaling
- **Scale-out:** new instances must (1) register/become discoverable, (2) pass readiness, (3) ideally slow-start. If discovery propagation is slow, new capacity sits idle while existing instances overload.
- **Scale-in:** removing instances must drain connections (readiness off → finish in-flight → terminate). Sticky sessions make scale-in painful (where do the sessions go?).
- **Feedback loop:** autoscalers often scale on metrics the LB produces (per-instance CPU, in-flight requests, latency). Beware oscillation: aggressive scaling + slow discovery + cold-start latency can cause flapping. Tune cooldowns and use P2C/slow-start to smooth.
- **Load-shedding** complements scaling: when overloaded, instances should fail fast (return 503 quickly so the LB retries elsewhere) rather than queueing — coupled with **circuit breakers** at the LB (Envoy `max_requests`/`max_pending_requests`) that reject early instead of piling on a dying backend.

---

## 8. Tradeoffs & decision frameworks

### 8.1 L4 vs L7

| Dimension | L4 | L7 |
|---|---|---|
| Inspects | connections/packets | full requests (HTTP/gRPC) |
| Balances | connections | requests |
| Features | none app-level | path routing, header rewrite, per-req retry, response-based ejection, TLS termination |
| Latency/CPU | very low | higher (parse + crypto) |
| Protocol support | any TCP/UDP | protocol-specific |
| Good for | DBs, custom protocols, raw throughput, HTTP/2 *connection* spread | HTTP/gRPC APIs, microservices, anything needing routing/retries |

**Use L4 when:** maximum throughput/low latency, non-HTTP protocols, or you want a simple resilient flow-router (often L4 in front of L7: NLB → ALB/Envoy). **Use L7 when:** you need request-level balancing, routing, retries, or observability (the common case for HTTP/gRPC).

### 8.2 Client-side vs server-side vs mesh

| | Server-side LB | Client-side LB | Sidecar mesh |
|---|---|---|---|
| Extra network hop | yes | no | yes (localhost) |
| Central bottleneck | yes (the LB) | no | no |
| Client complexity | none | high (per-language libs) | none (app is dumb) |
| Policy governance | central | distributed/hard | central (control plane) |
| Overhead | LB fleet | none | proxy per pod |
| Best for | edge/public ingress, mixed clients | high-perf JVM/gRPC internal | large polyglot microservice estates |

### 8.3 Algorithm choice (use-when)

| Use when… | Pick |
|---|---|
| Requests uniform, backends uniform | Round-robin |
| Backends heterogeneous capacity | Weighted RR |
| Request costs vary a lot | Least-request / P2C |
| Latency-sensitive, GC-prone JVM backends | EWMA / least-time (+P2C) |
| Need cache locality or sharding | Consistent hash (Maglev > ring) |
| Massive scale, want simplicity | Random or P2C |

### 8.4 Discovery system choice

| Use when… | Pick |
|---|---|
| On Kubernetes | K8s Services/EndpointSlices (+ mesh for L7) |
| Polyglot, VMs + containers, rich health | Consul |
| Spring Cloud / JVM, want AP availability | Eureka |
| Need the simplest, universal mechanism | DNS (SRV for ports) |
| Need strong consistency for config too | etcd / ZooKeeper (CP) |

**AP vs CP for discovery:** prefer **AP** (Eureka-style) for the *discovery* path — a stale-but-available list + LB health checks beats a "correct but unavailable" registry during a partition. Reserve **CP** (Consul/etcd/ZK) for things that truly need linearizable correctness (leader election, locks, config). This is the most important senior-level discovery tradeoff.

---

## 9. Failure modes & debugging

### 9.1 JVM/Java DNS caching trap (a classic real incident)
The JVM caches DNS lookups. Historically, with a `SecurityManager` installed, `networkaddress.cache.ttl` defaulted to **-1 (cache forever)**; otherwise typically **30s** for positive lookups and **10s** for negative (`networkaddress.cache.negative.ttl`). Symptom: you fail over an endpoint (DNS now points to a new IP), but JVM clients keep hammering the *old* dead IP for minutes/forever. **Fix:** set `java.security.Security.setProperty("networkaddress.cache.ttl", "5")` (or via `$JAVA_HOME/conf/security/java.security`), or use a client that re-resolves (gRPC name resolver, connection pools with DNS refresh). *Diagnose:* `netstat`/`ss` shows connections to the stale IP; `dig` shows the new one.

### 9.2 Correlated ejection → total outage
A shared dependency (DB) fails; *every* backend starts returning 5xx; outlier detection ejects them *all*; now the pool is empty and the LB returns 503 for everything — turning a degraded state into a total outage. **Prevent:** `max_ejection_percent` cap (e.g., 50%). **Diagnose:** Envoy `/clusters` shows ejection counts; `outlier_detection.ejections_active` metric spikes; healthy host count → 0.

### 9.3 Retry storm / metastable failure
A backend slows down → clients time out and retry → effective load multiplies → more timeouts → system stays collapsed even after the original trigger is gone (*metastable failure*). **Prevent:** retry budgets, exponential backoff *with jitter*, circuit breakers, load-shedding. **Diagnose:** request count to backends ≫ client-originated count; rising `upstream_rq_retry` metrics; CPU pegged on retries.

### 9.4 Sticky sessions + deploy = dropped sessions
You roll a deploy; sticky backends are replaced; users on those backends lose their in-memory session → forced logout / lost cart. **Prevent:** externalize sessions; or drain + consistent-hash so re-keying is graceful. **Diagnose:** error spike correlates exactly with deploy of specific instances.

### 9.5 Health check too shallow / too deep
- *Too shallow* (TCP connect only): app threadpool is wedged but the LB keeps sending traffic → user-visible errors despite "healthy" status. *Fix:* HTTP/gRPC check that exercises a real code path.
- *Too deep* (checks DB on every probe): DB blip fails all probes → whole pool removed → outage; also probe load can overload the DB. *Fix:* keep checks cheap and self-contained; separate readiness from liveness.

### 9.6 Kubernetes "Service has no endpoints"
Traffic to a Service gets connection-refused. **Diagnose:**
```bash
kubectl get endpointslices -l kubernetes.io/service-name=orders   # empty?
kubectl get pods -l app=orders                                    # pods Ready?
kubectl describe svc orders                                       # selector matches labels?
```
Usual causes: selector/label mismatch, all pods failing readiness, wrong `targetPort`. The endpoints list is empty because no *ready* pod matches the selector.

### 9.7 L4-balancing-an-H2-connection imbalance
With gRPC behind an L4 LB, one client opens one long-lived H2 connection → *all* its requests land on one backend → that backend is hot, others idle. **Diagnose:** per-backend in-flight is wildly uneven despite many requests. **Fix:** use an L7 (request-level) LB or a mesh; cap `max_requests_per_connection` to force re-balancing; use gRPC client-side `round_robin` with multiple resolved addresses.

### 9.8 Stale registry / split brain
A network partition makes the CP registry's minority side unavailable, or an AP registry serves stale entries pointing at dead hosts. **Diagnose:** discovery returns hosts the LB can't reach; health checks fail for "registered" hosts. **Mitigate:** LB active health checks (independent of registry) drop the dead ones; Eureka self-preservation prevents mass-deregistration; alert on "registered but unhealthy" divergence.

### 9.9 Tooling cheat for diagnosis
```bash
ss -tn state established '( dport = :8080 )'   # who am I connected to? (stale IP?)
dig +short orders.svc                          # what SHOULD I connect to?
curl -s localhost:9901/clusters | grep -E 'health|ejection|rq_active'  # Envoy
curl -s localhost:9901/stats | grep -E 'upstream_rq_retry|membership_healthy'
echo "show stat" | socat stdio /var/run/haproxy.sock   # HAProxy backend states
kubectl get endpointslices -l kubernetes.io/service-name=<svc>
```

---

## 10. Interview drill

**Q1. Explain L4 vs L7 load balancing and when you'd use each.**
*Model answer:* L4 balances at transport level — it pins a whole TCP connection to one backend based on the 4-tuple, never reads payload; fast, protocol-agnostic, but can't route per-request or do HTTP-aware retries. L7 terminates the connection, parses HTTP/gRPC, and balances *per request*, enabling path routing, header rewrite, retries, and response-code-based ejection — at higher CPU/latency cost. Use L4 for raw throughput, non-HTTP protocols, or as a fast front layer (NLB → ALB); use L7 for HTTP/gRPC APIs needing routing/retries.
- *Probe: Why can L4 give poor balance for gRPC?* Because gRPC uses one long-lived multiplexed HTTP/2 connection; L4 pins that whole connection to one backend, so all of a client's requests hit one node. Fix with L7 or `max_requests_per_connection`.
- *Probe: How does DSR change the picture?* Direct Server Return has responses bypass the LB, slashing LB bandwidth; only viable at L4 since the LB never needs to see the response.

**Q2. Compare round-robin, least-connections, P2C, and EWMA.**
*Model answer:* RR equalizes request *count* — good only when requests/backends are uniform. Least-connections equalizes in-flight work — adapts to variable cost but needs shared state. P2C picks two random backends and takes the less-loaded — gets near-least-connections quality at O(1) with no global coordination, avoiding thundering herd; it's the modern default. EWMA routes by recent latency (exponentially weighted average), reacting fastest to GC pauses/slow nodes; often combined with P2C.
- *Probe: Why not exact least-connections everywhere?* It needs global/synchronized counters across many LBs/clients and can stampede onto a just-recovered empty node; P2C avoids both.
- *Probe: What's α in EWMA?* The smoothing factor; higher α weights recent samples more (more reactive but noisier).

**Q3. How does consistent hashing work and why beat `hash mod n`?**
*Model answer:* Place backends at many points (vnodes) on a hash ring; a key hashes to a point and walks to the next backend. Adding/removing a node remaps only ~1/n of keys, vs `hash mod n` which remaps nearly all keys when n changes — catastrophic for cache hit-rate.
- *Probe: Maglev vs ring hash?* Maglev builds a fixed prime-sized lookup table — O(1) lookups, much more uniform distribution, slightly more disruption on change; ring hash with enough vnodes is simpler but less uniform.
- *Probe: When use it?* Cache locality, sharding, or affinity without server-side session state.

**Q4. Walk through health checks vs outlier detection.**
*Model answer:* Active health checks proactively probe each backend on an interval with healthy/unhealthy thresholds (hysteresis to avoid flapping); only passing hosts are eligible. Outlier detection is passive — it watches *real* request outcomes (consecutive 5xx, gateway errors, latency/success-rate outliers) and ejects misbehaving hosts with exponential backoff, capped by `max_ejection_percent`. You need both: active catches dead/unready; outlier catches hosts that pass checks but actually serve errors.
- *Probe: Why the max-ejection cap?* A correlated failure (shared DB down) makes everything 5xx; without a cap you'd eject the whole pool → total outage.
- *Probe: Liveness vs readiness?* Liveness failing restarts; readiness failing removes from pool. Deep dependency checks belong in readiness, never liveness.

**Q5. Client-side vs server-side LB — pros/cons.**
*Model answer:* Server-side: a middlebox holds the backend list; simple clients, central policy, but extra hop and a scaling bottleneck/failure domain. Client-side: the client holds the list and chooses directly; no hop, no central bottleneck, but every client needs LB+discovery logic per language and policy is harder to govern. Mesh sidecars hybridize: app stays dumb, proxy does client-side LB, control plane gives central governance — at per-pod proxy overhead.
- *Probe: How does gRPC do client-side LB?* A name resolver (e.g., DNS, xDS) supplies addresses; a policy (`pick_first`/`round_robin`) chooses; xDS enables mesh-grade policies.

**Q6. (Senior signal) AP vs CP for a service registry — which and why?**
*Model answer:* For the *discovery* path I'd lean **AP** (Eureka-style). During a partition, a stale-but-available list of mostly-correct backends, backed by the LB's own active health checks to drop the few wrong ones, keeps traffic flowing — far better than a CP registry's minority side refusing to answer and breaking discovery entirely. I reserve **CP** (etcd/Consul/ZK with Raft/ZAB) for things needing linearizable correctness — leader election, locks, distributed config — where a stale read is genuinely dangerous. The key insight: discovery tolerates staleness because health checks are a second line of defense; coordination does not.
- *Probe: What's Eureka self-preservation?* When Eureka loses many heartbeats at once it assumes a partition, not mass death, and stops expiring registrations — preventing it from wrongly emptying the registry.
- *Probe: Downside of AP here?* You may briefly route to dead hosts; mitigated by LB health checks and retries.

**Q7. (Senior signal) Design failover for a critical payment API across two regions.**
*Model answer:* Edge: Anycast or DNS-based GSLB (Route 53 latency+failover) to steer users to the nearest healthy region; health checks at the region level drive DNS failover (accepting TTL-bound delay) or BGP withdrawal (faster, for anycast). Inside each region: L7 LB/mesh with P2C, active health checks, outlier detection capped at ~50%, retries restricted to idempotent calls with a retry budget + jitter, and idempotency keys so a cross-region retry can't double-charge. Sessions/state externalized to a replicated store. Capacity headroom so a region can absorb the other's traffic on failover. I'd explicitly test region failover under load.
- *Probe: Why retry budgets here?* To prevent a slow region from triggering a retry storm that collapses the healthy one.
- *Probe: Why not rely on DNS TTL alone?* TTL + resolver caching delay failover and measure resolver, not user, location; anycast or active connection draining tightens it.

**Q8. (Senior signal) Sticky sessions: when justified, when harmful?**
*Model answer:* Justified mainly for *cache locality* (route a key to the node that already cached it) or unavoidable in-memory state during a migration. Harmful as a *correctness* crutch: it unbalances load (a whale client pins one node), breaks graceful scale-in/deploys (replacing a sticky node drops its sessions), and hides a stateful dependency. Best practice: externalize session state (Redis/DB) so the tier is stateless, and if you need affinity, use consistent hashing (Maglev) so backend churn remaps minimally rather than source-IP stickiness that breaks behind NAT.
- *Probe: Why does source-IP affinity break?* Many clients behind one NAT/proxy share an IP, so they all pin to one backend — terrible balance.

**Q9. How do retries interact with idempotency and what guards do you add?**
*Model answer:* Only auto-retry idempotent operations (GET/PUT/DELETE) or requests carrying an idempotency key; never blindly retry POSTs (double side-effects). Guards: retry budget (cap retries to a small % of original traffic), exponential backoff with jitter, per-try timeout < overall deadline, and circuit breakers to stop retrying a clearly-dead backend.
- *Probe: What's a per-try vs overall timeout?* Per-try bounds each attempt; the overall deadline bounds the whole operation including retries, so you don't blow the client's SLA chasing retries.

**Q10. Trace a request through Kubernetes Service load balancing.**
*Model answer:* App resolves `orders.ns.svc.cluster.local` via CoreDNS to the Service ClusterIP. The packet hits kube-proxy's kernel rules (iptables/IPVS) or an eBPF dataplane, which DNAT it to a *ready* Pod IP chosen from the EndpointSlice (maintained by the endpoints controller watching ready Pods matching the selector). That's L4, in-kernel, per-connection balancing. For L7 you add an Ingress/Gateway or mesh sidecar.
- *Probe: Why might a Service have no endpoints?* Selector/label mismatch or all Pods failing readiness — the EndpointSlice only contains ready, matching Pods.
- *Probe: iptables vs IPVS mode?* iptables evaluates rules O(n) (slow at high endpoint counts); IPVS uses a hash table and supports real LB algorithms — better at scale.

**Q11. What's GSLB and how does anycast differ from DNS-based global LB?**
*Model answer:* GSLB balances across datacenters/regions. DNS-based GSLB returns different IPs by geo/latency/health (Route 53), but is limited by DNS caching/TTL and measures the resolver's location. Anycast advertises one IP from many sites via BGP; the network routes to the nearest, giving fast failover (BGP withdrawal) and no TTL lag, but less per-request control and occasional mid-flow re-routing. Real stacks combine anycast at the edge with L7 inside each PoP.

**Q12. How does load balancing interact with autoscaling and what oscillations can occur?**
*Model answer:* Scale-out adds instances that must register, pass readiness, and ideally slow-start before taking full traffic; if discovery propagation lags, new capacity sits idle while old instances overload. Scale-in must drain (readiness off → finish in-flight → terminate). Autoscalers often act on LB-produced metrics, so slow discovery + cold-start latency + aggressive scaling can oscillate (flapping). Smooth with P2C, slow-start, scaling cooldowns, and load-shedding + circuit breakers so overloaded nodes fail fast instead of queueing.

---

## 11. Glossary

- **Anycast** — advertising one IP from many locations via BGP so clients reach the nearest; used for fast global failover.
- **AP / CP (CAP)** — under a network partition, prioritize Availability (serve, maybe stale) vs Consistency (be correct, maybe unavailable).
- **Backend / upstream / origin** — a server instance that actually serves work.
- **BGP (Border Gateway Protocol)** — the internet's inter-network routing protocol; underlies anycast.
- **Circuit breaker** — a guard that stops sending requests to a failing dependency once errors cross a threshold, failing fast to allow recovery.
- **ClusterIP** — a Kubernetes Service's stable virtual IP inside the cluster.
- **Connection draining** — letting in-flight requests finish before removing/terminating a backend.
- **Consistent hashing** — ring/table mapping of keys→backends that remaps only ~1/n keys when the set changes.
- **CoreDNS** — Kubernetes' DNS server resolving service names to ClusterIPs.
- **DNS (Domain Name System)** — distributed directory mapping names to IPs; can act as a coarse LB and discovery layer.
- **DSR (Direct Server Return)** — L4 mode where responses bypass the LB, going straight from backend to client.
- **ECMP** — Equal-Cost Multi-Path routing; spreads flows across multiple equal-cost next hops (used to fan traffic across L4 LB instances).
- **EDS/CDS/LDS/RDS/SDS (xDS)** — Envoy discovery APIs for Endpoints/Clusters/Listeners/Routes/Secrets pushed by a mesh control plane.
- **EndpointSlice** — Kubernetes object listing the ready Pod IP:ports backing a Service.
- **Ephemeral node (ZooKeeper)** — a node tied to a client session that auto-deletes when the session dies (liveness signal).
- **Eureka** — Netflix/Spring AP service registry with client-side caching and self-preservation.
- **EWMA** — Exponentially Weighted Moving Average; latency metric weighting recent samples more, used for least-latency LB.
- **Health check (active/passive)** — active = LB probes backends; passive (outlier detection) = LB infers health from real request outcomes.
- **HTTP/2** — multiplexed binary HTTP carrying many concurrent streams over one TCP connection; used by gRPC.
- **Idempotency** — a request that can be safely repeated with no additional effect; required for safe auto-retries.
- **Ingress / Gateway** — Kubernetes L7 entry point routing external HTTP to Services.
- **IPVS** — Linux kernel L4 load balancer (hash-table based) used by kube-proxy in IPVS mode.
- **kube-proxy** — Kubernetes per-node component programming kernel rules to L4-balance Service traffic to Pods.
- **L4 / L7** — transport-layer (connection/packet) vs application-layer (request) load balancing.
- **Least-connections / least-request** — route to the backend with the fewest in-flight units of work.
- **Liveness probe** — "is the process alive?"; failing it restarts the instance.
- **Locality / zone-aware LB** — prefer same-zone backends to cut latency and cross-AZ cost, spilling over only when local capacity degrades.
- **Maglev** — Google's software L4 LB; also a consistent-hashing scheme using a flat lookup table for uniform, O(1), low-disruption mapping.
- **Metastable failure** — a system stuck in a degraded state (often via retry storms) even after the original trigger is gone.
- **mTLS (mutual TLS)** — both sides present certificates and authenticate each other; backbone of zero-trust meshes.
- **Outlier ejection** — temporarily removing a misbehaving backend based on real traffic, with exponential backoff and a max-ejection cap.
- **P2C (Power of Two Choices)** — pick two random backends, route to the less-loaded; near-least-connections quality at O(1).
- **Pod** — Kubernetes' smallest deployable unit (one or more containers sharing network/storage).
- **Quorum** — a majority of nodes required to commit a write in consensus systems (Raft/ZAB).
- **Raft** — leader-based consensus algorithm providing strong consistency; used by Consul/etcd.
- **Readiness probe** — "can it take traffic now?"; failing it removes the instance from the LB pool (no restart).
- **Retry budget** — a cap on retries (e.g., 10–20% of original requests) to prevent retry storms.
- **Round-robin (weighted)** — cycle through backends evenly (or proportional to weights).
- **Self-preservation (Eureka)** — stops expiring registrations during suspected partitions to avoid emptying the registry.
- **Service mesh** — infrastructure layer (sidecar proxies + control plane) handling LB, discovery, retries, mTLS, telemetry.
- **Service registry** — the database of live instances and their addresses.
- **Sidecar** — a co-located proxy next to each app instance handling networking concerns.
- **Slow-start / warm-up** — ramping traffic to a freshly-added backend so cold caches/JIT don't get overwhelmed.
- **SRV record** — DNS record returning host+port (and priority/weight).
- **Sticky session (session affinity)** — pinning a client to one backend (source-IP, cookie, or hash-based).
- **Subset LB** — each client connects to a subset of backends to limit connection/health-check fan-out at scale.
- **TCP / UDP** — reliable connection-oriented vs connectionless transport protocols (L4).
- **Thundering herd** — many actors hitting one resource simultaneously (e.g., all traffic to a just-recovered node).
- **TLS termination / passthrough** — LB decrypts (can read L7) vs forwards encrypted (forces L4).
- **TTL (Time To Live)** — caching lifetime of a DNS answer or registry lease.
- **VIP (virtual IP)** — the single front-end address clients use; maps to many backends.
- **vnodes (virtual nodes)** — multiple ring positions per backend to smooth consistent-hash distribution.
- **xDS** — Envoy's gRPC discovery-service protocols (see EDS/CDS/etc.).
- **ZAB** — ZooKeeper Atomic Broadcast, ZooKeeper's consensus protocol.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap
- **L4** = balances *connections* (fast, any protocol, no app awareness). **L7** = balances *requests* (routing, retries, header rewrite, response-based ejection).
- **Algorithms:** RR (uniform) · Weighted RR (heterogeneous) · Least-request (variable cost) · **P2C** (least-conn quality, O(1), default) · EWMA (latency, fastest reaction) · Consistent hash / **Maglev** (locality/sharding, ~1/n remap) · Random.
- **Health:** *active* probes (interval, healthy/unhealthy thresholds ~2/3) + *passive* outlier ejection (consecutive 5xx ~5, base ejection 30s, **max_ejection_percent ~10–50%**). Liveness=restart, Readiness=remove from pool. Never put deep checks in liveness.
- **Discovery:** DNS (simple, TTL-bound) · Consul (CP, rich health) · Eureka (AP, self-preservation) · etcd/ZK (CP) · K8s Services (EndpointSlices + CoreDNS + kube-proxy L4). **Prefer AP for discovery; LB health checks are the safety net.**
- **Topologies:** server-side (hop + bottleneck, dumb clients) · client-side (no hop, per-language libs) · mesh sidecar (dumb app + central policy + proxy overhead, xDS).
- **Retries:** idempotent only / idempotency keys · retry budget (~10–20%) · backoff + jitter · per-try < overall deadline · circuit breakers. Avoid retry storms / metastable collapse.
- **Sticky sessions:** for cache locality only; externalize state; use consistent hash, not source-IP.
- **Global:** Anycast (BGP, fast, low control) + DNS GSLB (geo/latency/failover, TTL-bound) + L7 inside each PoP.
- **Autoscaling:** register → readiness → slow-start; drain on scale-in; watch oscillation; load-shed under overload.
- **JVM trap:** DNS cache TTL (historically -1/forever with SecurityManager, else ~30s) → set `networkaddress.cache.ttl` low or use a re-resolving client.
- **Diagnose:** `dig` (what should I hit) vs `ss` (what I'm hitting) · Envoy `/clusters` & `/stats` (health, ejections, retries) · `kubectl get endpointslices`.

### 12.2 Self-test (no answers — recall actively)
1. Why does an L4 load balancer give uneven request distribution for a single long-lived gRPC client, and what are two fixes?
2. Walk through exactly what `max_ejection_percent` protects against, and give a concrete value you'd set with reasoning.
3. You change a backend's DNS to a new IP but JVM clients keep hitting the old one for minutes. What is happening and how do you fix it permanently?
4. Contrast P2C and exact least-connections: why is P2C usually preferred in a distributed multi-LB deployment?
5. For a service registry under a network partition, would you choose AP or CP, and what second mechanism makes that choice safe?
6. Explain why putting a database connectivity check in the Kubernetes *liveness* probe can cause a full outage, and where that check belongs instead.
7. Design retry behavior for a payment `POST` API across two regions so a slow region can't double-charge or trigger a retry storm.

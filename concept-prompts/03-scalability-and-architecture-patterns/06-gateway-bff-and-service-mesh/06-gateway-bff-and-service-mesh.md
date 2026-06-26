# API Gateway, BFF & Service Mesh

> A definitive engineering-handbook chapter for senior JVM/backend developers who want to fully master edge and east-west traffic management — from first principles to deep internals, production operation, and interview-grade fluency.

---

## 1. Overview & where it fits

### 1.1 The problem space

In a monolith, a request comes in over one socket, is dispatched by one router, calls in-process methods, and returns. There is no "network" between components: a method call cannot be slow because of TCP retransmits, cannot be rejected by a peer's rate limiter, and cannot fail because TLS handshakes timed out. Authentication happens once, at the front door, and the rest of the call tree trusts the authenticated principal in a thread-local.

When you decompose that monolith into **microservices** (independently deployable services that communicate over the network), every in-process method call that crossed a service boundary becomes a *network call*. Now you have two fundamentally different kinds of traffic:

- **North-south traffic** — traffic that crosses the boundary of your system. A mobile app, browser, or partner system on the public internet talks to your cluster. ("North-south" is a data-center metaphor: the client is "north" of the system, the backends are "south.")
- **East-west traffic** — traffic *between* your own services inside the cluster. Service A calls Service B calls Service C. ("East-west" because it flows laterally between peers at the same tier.)

Each kind of traffic raises the same cross-cutting concerns — authentication, encryption, retries, timeouts, load balancing, observability, rate limiting — but at different scales and trust levels. The three patterns in this chapter are the canonical industry answers:

| Pattern | Primarily solves | Traffic direction | Lives where |
|---|---|---|---|
| **API Gateway** | North-south edge concerns: auth, routing, TLS termination, throttling, request shaping | North-south | At the edge, one logical hop in front of services |
| **Backend-for-Frontend (BFF)** | Client-specific API shaping and aggregation | North-south (one per client type) | Just behind the gateway, one per frontend |
| **Service Mesh** | East-west concerns: mTLS, retries, load balancing, observability, traffic shifting — *without app code changes* | East-west (and optionally north-south) | A sidecar proxy next to every service instance |

### 1.2 The one-paragraph mental model

**An API gateway is a single, smart front door** that terminates client connections, authenticates callers, and routes/shapes requests into the cluster — it centralizes the concerns that *every* external request shares. **A BFF is a gateway specialized for one frontend** (iOS, web, partner API), owned by the frontend team, that aggregates and reshapes backend responses into exactly what that one UI needs. **A service mesh is infrastructure that moves the cross-cutting concerns of service-to-service calls — encryption, retries, timeouts, load balancing, telemetry — out of your application code and into a network of proxies** (one "sidecar" next to each service), controlled centrally. The gateway is about the *edge*; the mesh is about the *interior*; the BFF is about *client fit*. They are complementary, not competing — large systems run all three.

### 1.3 When you reach for each

- **API Gateway:** the moment you have more than one externally-reachable service, or any external clients at all and you want one place to do auth, TLS, and rate limiting. Almost every microservice system has one.
- **BFF:** when you have *multiple distinct client types* (e.g., a data-hungry web SPA, a bandwidth-constrained mobile app, and a partner REST API) whose ideal API shapes diverge enough that a single one-size-fits-all API becomes a source of constant churn and over/under-fetching.
- **Service Mesh:** when you have *enough services* (rule of thumb: dozens, not three) that re-implementing mTLS, retries, circuit breaking, and consistent telemetry in every service — and across multiple languages — becomes a tax you no longer want to pay in application code. Below that threshold a mesh is usually overkill (see §8).

### 1.4 What this chapter assumes you already know

You know what HTTP, TCP, and TLS are at a basic level; you've written a REST endpoint; you understand that microservices talk over the network. Everything else — Envoy, xDS, mTLS, sidecars, control plane vs data plane, L4 vs L7, circuit breakers, the specific failure modes — is built up from first principles below.

---

## 2. Foundations from first principles

Before we can discuss gateways and meshes precisely, we need a shared vocabulary. Each term below is defined the first time it matters.

### 2.1 The OSI-ish layers that matter: L4 vs L7

Networking is conventionally described in layers. Two matter constantly here:

- **Layer 4 (L4) — the transport layer.** This is **TCP** (Transmission Control Protocol) and **UDP** (User Datagram Protocol). At L4 a proxy or load balancer sees connections, IP addresses, and ports — but *not* the meaning of the bytes flowing through. An L4 load balancer can say "send this TCP connection to backend #3" but cannot say "send all `GET /users` to the read replica," because it can't read the HTTP inside.
- **Layer 7 (L7) — the application layer.** This is **HTTP**, **gRPC**, **WebSocket**, etc. An L7 proxy *parses* the protocol: it sees the HTTP method, path, headers, and body. This lets it route on URL path, retry idempotent requests, inject headers, and collect per-route metrics. L7 is more powerful but more expensive (it must buffer and parse).

Gateways and meshes are predominantly **L7** for HTTP/gRPC, with L4 as a fallback for opaque TCP traffic (e.g., a raw database connection).

### 2.2 Proxy, reverse proxy, forward proxy

- A **proxy** is an intermediary that relays traffic between a client and a server.
- A **forward proxy** sits in front of *clients* and represents them to the outside world (e.g., a corporate egress proxy). The server doesn't know the real client.
- A **reverse proxy** sits in front of *servers* and represents them to clients (e.g., NGINX in front of your app). The client thinks it's talking to one server; the proxy fans out to many. **Gateways, BFFs, and mesh sidecars are all reverse proxies.**

### 2.3 Load balancing

**Load balancing** is distributing incoming requests across multiple backend instances so no single instance is overwhelmed and so the system tolerates instance failures. Key algorithms:

- **Round-robin:** rotate through backends in order. Simple, ignores load.
- **Least-request (least-connections):** send to the backend currently handling the fewest in-flight requests. Adapts to uneven request costs.
- **Random / power-of-two-choices (P2C):** pick two backends at random, send to the less-loaded of the two. Surprisingly close to optimal with O(1) cost; this is the modern default in Envoy and many meshes.
- **Consistent hashing / ring hash:** map a request key (e.g., user ID) to a backend via a hash ring so the same key tends to hit the same backend (useful for cache affinity). Adding/removing a backend only remaps a small fraction of keys.
- **Maglev hashing:** Google's consistent-hashing variant optimized for even distribution and minimal disruption.

### 2.4 TLS, mTLS, and termination

- **TLS (Transport Layer Security)** is the protocol that encrypts a connection and authenticates the *server* to the client via a certificate signed by a **Certificate Authority (CA)** — a trusted entity that vouches for "this public key really belongs to example.com." The successor to SSL.
- **TLS termination** means a proxy decrypts the TLS connection (it "terminates" the encryption) and forwards plaintext (or re-encrypted) traffic to backends. Terminating at the gateway means backends don't each need certificates or pay decryption cost.
- **mTLS (mutual TLS)** means *both* sides present certificates: the client proves its identity to the server *and* the server proves its identity to the client. In a service mesh, every service gets a cryptographic identity (a certificate), and sidecars use mTLS so that Service A can be sure it's really talking to Service B and the traffic is encrypted — even inside the cluster. This is the foundation of **zero-trust networking** (never trust the network; authenticate every hop).

### 2.5 Authentication vs authorization

- **Authentication (authn):** *who are you?* Verifying identity. Examples: validating a **JWT** (JSON Web Token — a signed, base64-encoded token carrying claims like user ID and expiry), an API key, or an mTLS client certificate.
- **Authorization (authz):** *what are you allowed to do?* Checking permissions for the authenticated principal. Examples: "this user may read order #42 but not delete it."

Gateways typically do *coarse* authn (validate the token, reject if invalid/expired) and sometimes coarse authz (this token has the `admin` scope). *Fine-grained* authz ("user owns this resource") almost always belongs in the service, not the gateway (see §3.6).

### 2.6 Rate limiting and throttling

- **Rate limiting** caps how many requests a client may make per unit time (e.g., 100 req/s per API key). It protects backends from overload and enforces fair use / commercial tiers.
- **Throttling** is the act of slowing or rejecting requests that exceed a limit (usually returning HTTP `429 Too Many Requests`).
- Common algorithms: **token bucket** (a bucket refills at a fixed rate; each request consumes a token; empty bucket = reject — allows bursts up to bucket size), **leaky bucket** (requests drain at a constant rate — smooths bursts), **fixed window** (count per calendar second/minute — simple but has boundary spikes), **sliding window** (smooths the fixed-window boundary problem).

### 2.7 Retries, timeouts, circuit breakers, bulkheads

These are **resilience patterns** — techniques to keep a distributed system working when parts of it fail or slow down.

- **Timeout:** give up on a call after N milliseconds. Without timeouts, one slow dependency can exhaust all your threads/connections.
- **Retry:** re-issue a failed request, hoping the failure was transient. Must be paired with **backoff** (wait longer between attempts) and **jitter** (randomize the wait to avoid synchronized retry storms). Only **idempotent** operations (safe to repeat — like `GET` or a `PUT` with the same body) should be retried automatically.
- **Circuit breaker:** after too many failures to a dependency, "open the circuit" and fail fast (reject immediately) instead of waiting for timeouts. Periodically allow a trial request ("half-open") to see if the dependency recovered. Named after electrical circuit breakers. Prevents a struggling dependency from dragging down its callers.
- **Bulkhead:** isolate resources (e.g., separate thread pools or connection pools per dependency) so that one saturated dependency can't consume all resources and sink unrelated traffic. Named after a ship's watertight compartments.
- **Outlier detection / ejection:** automatically remove a backend instance from the load-balancing pool when it returns errors or is slow, then re-admit it after a cooldown. This is "passive health checking."

### 2.8 Service discovery

**Service discovery** answers "what are the current network addresses of the healthy instances of service X?" In dynamic environments (Kubernetes, autoscaling), instances come and go constantly, so you can't hardcode IPs. Mechanisms:

- **DNS-based:** look up a service name; get back IPs. Simple but DNS caching/TTL makes it slow to react to changes.
- **Registry-based:** services register themselves in a **service registry** (e.g., Consul, Eureka, etcd) and clients query it. (**etcd** is a distributed key-value store used by Kubernetes; **Consul** is HashiCorp's service-discovery and KV system; **Eureka** is Netflix's registry.)
- **Platform-native:** Kubernetes maintains **Endpoints/EndpointSlices** objects listing the live pod IPs behind a Service; the mesh control plane watches these.

### 2.9 The control plane / data plane split

This distinction is *the* central architectural idea of a service mesh, and it appears in gateways too.

- The **data plane** is the set of components that actually touch every request/packet — the proxies. It must be fast, because it's in the hot path of every call.
- The **control plane** is the brain that *configures* the data plane — it computes routing rules, distributes certificates, aggregates telemetry, and pushes config to the proxies. It is *not* in the request hot path; if the control plane is briefly down, existing proxies keep forwarding traffic with their last-known config.

Analogy: the data plane is the network of roads and the cars on them; the control plane is the traffic-control center that sets the signals and speed limits. A blackout at the control center doesn't instantly stop all cars.

### 2.10 Sidecar

A **sidecar** is a helper process/container deployed *alongside* your application instance, sharing its lifecycle and network namespace, that handles cross-cutting concerns so the app doesn't have to. In a service mesh the sidecar is a proxy (usually **Envoy**) injected into the same Kubernetes **Pod** as your app container. (A **Pod** is Kubernetes' smallest deployable unit: one or more containers that share a network namespace and IP — so the app and its sidecar reach each other over `localhost`.) All of the app's inbound and outbound traffic is transparently redirected through the sidecar.

### 2.11 Envoy, the workhorse proxy

**Envoy** is a high-performance L7 proxy originally built at Lyft, now a graduating CNCF project, written in C++. It is the data-plane proxy for most modern meshes (Istio, Consul) and many gateways. It's important because it standardized a dynamic configuration API called **xDS** (see §7) that lets a control plane reconfigure proxies at runtime without restarts. Whenever you see "sidecar proxy," mentally substitute "Envoy" unless told otherwise (Linkerd is the main exception — it uses its own Rust micro-proxy).

With this vocabulary in hand, we can now go deep on how each pattern actually works.

---

## 3. How it works internally — The API Gateway

### 3.1 What an API gateway *is*, precisely

An API gateway is an L7 reverse proxy plus a policy engine that sits at the north-south edge. A single externally-reachable endpoint (e.g., `https://api.example.com`) terminates here, and the gateway applies a *pipeline* of cross-cutting policies to each request before routing it to the appropriate backend service, then applies a (usually smaller) pipeline to the response on the way out.

### 3.2 The request lifecycle inside a gateway (step by step)

Here is the control/data flow for a typical request through a gateway, in order:

1. **Connection accept & TLS termination.** The client opens a TCP connection and begins a TLS handshake. The gateway presents its server certificate, negotiates a cipher, and decrypts the connection. (Optionally it requests a client certificate for mTLS.) The gateway now has plaintext HTTP.
2. **Protocol parsing & normalization.** The gateway parses the HTTP request line, headers, and (lazily) the body. It may normalize the path (collapse `//`, decode `%2F`), enforce max header/body sizes, and reject malformed requests early.
3. **Route matching.** The gateway matches the request against its **route table** — an ordered set of rules keyed on host, path, method, headers, or query. The first matching route determines the upstream cluster and the policy set. Example: `Host: api.example.com` + `path: /orders/*` → `orders-service`.
4. **Authentication.** The gateway runs the authn filter: validate the JWT signature against the issuer's public keys (often fetched from a **JWKS** endpoint — JSON Web Key Set, a URL publishing the issuer's signing keys), check expiry (`exp`) and audience (`aud`), or validate an API key, or verify the mTLS client cert. On failure → `401 Unauthorized`, request never reaches a backend.
5. **Authorization (coarse).** Optionally check scopes/claims/roles required by the route (e.g., route requires scope `orders:write`). On failure → `403 Forbidden`.
6. **Rate limiting / quota.** Identify the client (by API key, user ID claim, or IP), consult the rate-limit counter (often in a shared store like Redis for cluster-wide limits), decrement/check the token bucket. On exceed → `429 Too Many Requests`, ideally with a `Retry-After` header.
7. **Request transformation.** Add/remove/rewrite headers (e.g., strip the client's `Authorization`, inject an internal `X-User-Id` and `X-Request-Id`), rewrite the path (`/v1/orders` → `/orders`), maybe transform the body (protocol translation, e.g., REST→gRPC).
8. **Load balancing & upstream selection.** Resolve the target service's healthy instances via service discovery and pick one via the LB algorithm. Apply connection pooling (reuse keep-alive connections to the backend).
9. **Outbound resilience policies.** Apply per-route timeout, retry policy (with backoff/jitter), and circuit breaker before/while calling the upstream.
10. **Proxy the request & stream the response.** Forward to the backend, stream the response back, applying response transformations (CORS headers, caching headers, header stripping), and possibly response aggregation (less common at a pure gateway; more a BFF concern — see §4).
11. **Observability emission.** Throughout, emit an access log line, metrics (latency, status code, route), and a tracing span (propagating/originating a trace ID — see §6.4).

### 3.3 The filter-chain mental model

Most gateways (and Envoy specifically) implement this as a **filter chain**: an ordered list of pluggable filters, each of which can inspect/modify the request, short-circuit it (e.g., reject), or pass it on. Request filters run top-to-bottom on the way in; response filters run on the way out. This is the same "middleware pipeline" idea you see in web frameworks, applied at the infrastructure layer. Understanding the *order* of filters is critical: authn must run before rate-limiting-by-user (you need the identity first), and TLS termination obviously runs first of all.

### 3.4 State the gateway holds

A gateway is *mostly* stateless per-request but holds operational state:

- **Route/config tables** (hot-reloaded, ideally without dropping connections).
- **Connection pools** to upstreams (keep-alive).
- **Rate-limit counters** — local (per-instance, fast, approximate) or distributed (Redis/dedicated rate-limit service, accurate cluster-wide, slower).
- **JWKS / cert caches** — cached public keys with TTL.
- **Circuit-breaker state** per upstream.

A key design point: if rate limits must be enforced *globally* across N gateway instances, you need a shared counter store, which adds a network hop and a dependency. Local per-instance limits are faster but let a client get up to N× the limit. Many systems accept local limits for DoS protection and use a distributed limiter only for billing-grade quotas.

### 3.5 What an API gateway is responsible for (the canonical list)

| Responsibility | What it means | Why centralize it |
|---|---|---|
| **TLS termination** | Decrypt inbound TLS, manage certs | Backends avoid cert management & crypto cost |
| **Routing** | Map host/path/method → backend | One place to change topology |
| **Authentication** | Validate JWT/API key/mTLS | Reject bad callers before they hit services |
| **Coarse authorization** | Check scopes/roles per route | Cheap gate; offload from services |
| **Rate limiting / quotas** | Token-bucket per client/tier | Protect backends; enforce commercial tiers |
| **Request/response transformation** | Header & path rewrites, protocol translation | Decouple client API from internal APIs |
| **Aggregation (light)** | Combine a few calls into one response | Reduce client round-trips |
| **Observability** | Access logs, metrics, tracing at the edge | Single pane for all external traffic |
| **Caching** | Cache cacheable responses at the edge | Offload read-heavy backends |
| **Versioning & canary routing** | Route `/v2` or % of traffic to new backend | Safe rollouts at the edge |
| **CORS, request validation, WAF hooks** | Enforce browser policy, schema, block attacks | Standardize edge security |

### 3.6 What should NOT live in the gateway

This is a frequent interview topic and a frequent production mistake. Keep out of the gateway:

- **Business logic.** The gateway must not "know" that an order over $10k needs manager approval. That couples your domain to infrastructure and turns the gateway into a distributed monolith — the dreaded **"gateway-as-ESB"** anti-pattern (ESB = Enterprise Service Bus, the heavyweight middleware of the SOA era that became an unmaintainable logic dumping ground).
- **Fine-grained, data-dependent authorization.** "Can *this* user read *this specific* order?" requires domain data the gateway shouldn't fetch. Do coarse authz (scopes) at the edge; do resource-level authz in the owning service.
- **Heavy aggregation / orchestration with business rules.** Light aggregation is fine; multi-step sagas and domain orchestration belong in a service (or a BFF that's explicitly owned by a product team — see §4).
- **Per-service custom code that only one team needs.** That's a sign you want a BFF for that team, not gateway bloat shared by everyone.
- **Stateful session storage of domain data.** The gateway should stay close to stateless.
- **Data transformation that requires understanding the domain model.** Schema-level transforms (REST↔gRPC) are okay; semantic transforms are not.

The litmus test: *if changing a business rule would require a gateway deploy, it's in the wrong place.*

### 3.7 Gateway topologies

- **Single shared gateway:** one gateway (cluster of instances) for all external traffic. Simplest; risk of a shared bottleneck and a "everyone's config in one file" problem.
- **Per-domain gateways:** separate gateways for, say, the public API vs the admin API vs partner API. Better blast-radius isolation.
- **Two-tier (edge + internal):** a thin L4/L7 edge (TLS, DDoS, WAF) in front of richer L7 gateways. Common at scale.
- **Gateway + BFF layer:** the gateway does generic edge concerns; BFFs behind it do client-specific shaping (§4).

---

## 4. How it works internally — Backend-for-Frontend (BFF)

### 4.1 The problem BFF solves

A single, general-purpose API serving every client type is a compromise that satisfies none of them well:

- A **web SPA** (single-page application) on a fast connection wants rich, denormalized payloads with many fields, and can issue several calls.
- A **mobile app** on a flaky cellular link wants *fewer, smaller* responses (every byte and round-trip costs battery and latency) and a payload shaped exactly to one screen.
- A **partner/public API** wants a stable, versioned, conservative contract that rarely changes.

Trying to serve all three from one API produces **over-fetching** (clients download fields they ignore), **under-fetching** (clients must make N calls to assemble one screen), and **coupling churn** (every client's needs pull the shared API in different directions, so it changes constantly and breaks someone).

### 4.2 The BFF pattern

A **Backend-for-Frontend** is a dedicated backend service *per frontend experience*, owned by the team that owns that frontend, whose job is to **aggregate, transform, and tailor** downstream service responses into exactly the shape that one client needs. Coined at SoundCloud (Phil Calçado / Sam Newman popularized it). You get e.g. `bff-web`, `bff-ios`, `bff-android`, `bff-partner`. Each BFF:

- Calls the same set of internal microservices (`orders`, `catalog`, `pricing`, `user`).
- Aggregates several backend calls into one client-facing response (cuts round-trips).
- Shapes/renames/trims fields for that client (cuts over-fetching).
- Owns client-specific concerns: pagination style, image-size negotiation, feature flags, view-model assembly.

### 4.3 Where the BFF sits and how it differs from a gateway

```
                       ┌────────────┐
  iOS app  ───────────▶│  bff-ios   │──┐
                       └────────────┘  │
  Web SPA ──┐          ┌────────────┐  │   ┌──────────┐  ┌──────────┐
            ├─Gateway─▶│  bff-web   │──┼──▶│ catalog  │  │  orders  │  ... internal services
  Partner ──┘          └────────────┘  │   └──────────┘  └──────────┘
                       ┌────────────┐  │
                       │bff-partner │──┘
                       └────────────┘
```

The gateway handles *generic* edge concerns (TLS, authn, global rate limiting) for *all* clients. Each BFF handles *specific* shaping for *one* client. Critically:

- **Ownership:** a gateway is typically owned by a platform/infra team. A BFF is owned by the **frontend team** — it's "their" backend, and they can iterate on it without cross-team coordination. This organizational alignment is the real point of BFF (Conway's Law working *for* you).
- **Logic:** a BFF legitimately contains *presentation/aggregation* logic ("assemble the home screen view-model"). It must *not* contain core domain business rules — those stay in the domain services. The BFF orchestrates and shapes; it does not own truth.

### 4.4 BFF request lifecycle (step by step)

1. Request arrives (already authenticated by the gateway, which forwards a verified identity header or token).
2. BFF endpoint maps to a specific *screen/use-case* (e.g., `GET /home` for the iOS home screen).
3. BFF fans out **concurrent** calls to the needed downstream services (`catalog`, `recommendations`, `cart`, `loyalty`) — typically in parallel, since they're independent.
4. BFF applies per-call resilience (timeouts, fallbacks). A common pattern: if `recommendations` times out, return the screen *without* recommendations rather than failing the whole screen (**graceful degradation**).
5. BFF assembles a single response **view-model** tailored to that client (only the fields the iOS home screen renders, image URLs at the right resolution, etc.).
6. BFF returns one compact payload; the client does one round-trip.

### 4.5 BFF anti-patterns

- **The "distributed monolith" BFF:** business logic creeps into the BFF until it duplicates or contradicts the domain services. Rule: BFF orchestrates and shapes; domain services own rules and data.
- **The mega-BFF:** one "BFF" that serves all clients — that's just another general-purpose API; you've reinvented the problem. One BFF per *experience*, not one for everything.
- **Too many BFFs:** a separate BFF for every minor client variant multiplies operational cost. Group clients with genuinely similar needs (e.g., one mobile BFF for iOS+Android if their needs are ~identical).
- **BFF as auth boundary:** don't make the BFF the only place validating identity if the gateway already does it; avoid duplicate/inconsistent authn. Let the gateway authenticate; the BFF trusts the forwarded identity (verifying its integrity, e.g., a signed internal token).
- **Shared code coupling:** heavy shared libraries across BFFs re-introduce the coupling BFFs were meant to break. Some duplication across BFFs is acceptable and even desirable.

### 4.6 BFF vs GraphQL

A frequently asked comparison. **GraphQL** is a query language and runtime where the *client* specifies exactly the fields it wants in a single request, and a GraphQL server resolves them by calling downstream sources. GraphQL can address over/under-fetching *without* a per-client BFF, because each client tailors its own query. Trade-offs:

| Dimension | BFF (per client) | GraphQL gateway |
|---|---|---|
| Who shapes the response | Server (BFF) per client | Client, via its query |
| Aggregation | Hand-written in BFF | Resolver layer |
| N+1 risk | Controlled in code | Needs dataloaders/batching |
| Caching | Standard HTTP caching easy | Harder (POST queries, varied shapes) |
| Client autonomy | Team owns its BFF | Clients self-serve fields |
| Best when | Few client types, divergent needs, strong team ownership | Many clients, evolving field needs, one graph |

They're not mutually exclusive: a GraphQL server *is* effectively a generic BFF; some teams run GraphQL as the BFF for web and a thin REST BFF for mobile.

---

## 5. How it works internally — Service Mesh

### 5.1 What a service mesh actually solves

Once you have many services in many languages, you find yourself re-implementing the same east-west concerns in every service: TLS between services, retries with backoff, timeouts, circuit breaking, client-side load balancing, consistent metrics/tracing, and traffic-shifting for deploys. Doing this *in application code* means:

- Every language needs its own library (Java's Resilience4j, Go's go-kit, etc.) — N libraries to maintain and keep consistent.
- Upgrading a retry policy means redeploying every service.
- Polyglot teams drift into inconsistent behavior and telemetry.

A **service mesh** moves all of that into a dedicated infrastructure layer of **proxies** that sit beside every service instance and intercept all its network traffic. The application makes a plain `http://orders/...` call to localhost; the sidecar transparently handles mTLS, load balancing, retries, timeouts, and telemetry. **The app code becomes blissfully ignorant of the network's complexity.** Centrally, a control plane configures all the proxies consistently.

The mesh's headline capabilities:

- **mTLS everywhere** — automatic encryption + cryptographic identity for every service, with cert issuance/rotation handled for you (zero-trust east-west).
- **Resilience** — retries, timeouts, circuit breaking, outlier ejection, configured by policy, not code.
- **Traffic management** — fine-grained routing, weighted traffic splits (canary/blue-green), fault injection, mirroring/shadowing.
- **Observability** — uniform L7 metrics (rate/errors/duration — the "RED" metrics), distributed tracing headers, access logs — for *every* hop, with no app instrumentation.

### 5.2 Data plane vs control plane (the heart of mesh internals)

- **Data plane = the sidecar proxies** (Envoy in Istio/Consul; Linkerd's Rust micro-proxy in Linkerd). Every service instance has one. *All* of that instance's inbound and outbound traffic is redirected through its sidecar (via iptables rules or eBPF). The proxies do the actual mTLS, LB, retries, and metric collection. They're in the hot path of every request.
- **Control plane = the brain** (Istio's `istiod`; Linkerd's control plane; Consul servers). It does *not* touch request traffic. It:
  1. Watches service discovery (Kubernetes Endpoints) to know what's healthy.
  2. Translates your high-level intent (e.g., "send 10% of traffic to v2," "retry 3×," "mTLS strict") into low-level proxy config.
  3. Pushes that config to every sidecar dynamically (via xDS — see §7.1).
  4. Acts as (or integrates with) the **CA** that issues each service's identity certificate and rotates it.

If the control plane dies, proxies keep running on last-known config — traffic flows, but you can't *change* config until it recovers. This separation is what makes meshes operable at scale.

### 5.3 How a sidecar gets into your Pod (injection)

1. You label a namespace for injection (e.g., Istio's `istio-injection=enabled`).
2. When you create a Pod, a Kubernetes **mutating admission webhook** (a hook that can modify objects as they're created) run by the control plane *rewrites* the Pod spec to add the sidecar container (and an init container).
3. The **init container** sets up `iptables` rules (or eBPF) inside the Pod's network namespace so that *all* TCP traffic in/out of the app container is redirected to the sidecar's ports. The app is unaware.
4. The sidecar starts, connects to the control plane, fetches its certificate and config, and begins proxying.

### 5.4 The east-west request lifecycle through the mesh (step by step)

Suppose `frontend` calls `orders`:

1. App in `frontend` makes a normal call to `orders` (resolved via cluster DNS to the service).
2. `iptables`/eBPF in the Pod transparently redirects the outbound connection to the **local sidecar** (the *outbound* listener).
3. The sidecar resolves healthy `orders` endpoints from the config the control plane pushed, picks one via the LB policy (e.g., least-request / P2C).
4. The sidecar initiates **mTLS** to the *destination's* sidecar, presenting `frontend`'s certificate and validating `orders`'s certificate. Both certs are issued by the mesh CA and encode a **SPIFFE identity** (a standardized, URI-form workload identity like `spiffe://cluster.local/ns/shop/sa/orders` — SPIFFE = Secure Production Identity Framework For Everyone).
5. Outbound resilience applies: per-route timeout, retry policy, circuit-breaker checks, outlier ejection of bad endpoints.
6. The destination sidecar terminates mTLS, optionally enforces **authorization policy** ("is `frontend` allowed to call `orders` on this path?"), then forwards plaintext to the local `orders` app over `localhost`.
7. `orders` responds; the path reverses. Both sidecars emit metrics (request count, latency, response code), append/propagate tracing headers, and write access logs.

Every hop is encrypted, observed, and resilient — and the application code did none of it.

### 5.5 The three big mesh implementations

| Mesh | Data plane | Control plane | Notable traits |
|---|---|---|---|
| **Istio** | Envoy sidecars (or "ambient" sidecar-less mode, newer) | `istiod` (single binary: Pilot+Citadel+Galley merged) | Most features, most complex; rich traffic management (VirtualService/DestinationRule), strong on multi-cluster |
| **Linkerd** | Custom ultralight **Rust** micro-proxy (`linkerd2-proxy`) | Linkerd control plane | Simplicity & low overhead first; opinionated, fewer knobs; CNCF graduated; great defaults |
| **Consul (Connect)** | Envoy sidecars | Consul servers (also a KV/discovery system) | Works well outside Kubernetes too (VMs); multi-platform |

**Istio Ambient Mesh** (newer, GA-ish) is worth flagging as version-specific: it splits the data plane into a per-node L4 component (**ztunnel**, handling mTLS and L4) and an optional per-namespace L7 proxy (**waypoint**), removing the per-Pod sidecar to cut overhead and operational friction. This is an active area; treat specifics as version-dependent.

### 5.6 Gateway vs Mesh vs Library — the three ways to do cross-cutting concerns

This is the central comparison of the whole chapter.

| Approach | Where the logic runs | Pros | Cons | Best for |
|---|---|---|---|---|
| **Library in-app** (e.g., Resilience4j, Spring Cloud LoadBalancer, OpenTelemetry SDK) | Inside each service process | No extra hop (lowest latency); full language/framework integration; no infra to run | Per-language reimplementation; upgrades = redeploy everyone; inconsistent across polyglot; couples resilience to app code | Few services, single language (esp. JVM), latency-critical |
| **Service mesh sidecar** | Out-of-process proxy per instance | Language-agnostic; uniform behavior & telemetry; config without redeploy; mTLS for free | Latency/CPU/memory overhead per Pod; operational complexity; new failure surface | Many services, polyglot, need uniform mTLS/observability |
| **API gateway** | Centralized edge proxy | Centralizes *edge* (north-south) concerns | Not for east-west; central bottleneck if misused | The external edge — always |

The mesh and the library do *overlapping* jobs for east-west (resilience, LB), so they're genuine alternatives. The gateway addresses a *different* axis (north-south edge), so it's complementary, not an alternative, to the mesh. Many mature systems run a gateway at the edge *and* a mesh inside *and* selective libraries where latency is critical.

---

## 6. The complete toolkit

### 6.1 API gateway products & what each is

| Gateway | Type / engine | Notes |
|---|---|---|
| **Kong** | NGINX/OpenResty (Lua) + plugins; also Kong on Envoy | Huge plugin ecosystem; OSS + enterprise |
| **NGINX / NGINX Plus** | NGINX core | Ubiquitous reverse proxy; gateway features via config/modules |
| **Envoy Gateway** | Envoy + Kubernetes Gateway API | CNCF; implements the standard Gateway API |
| **Spring Cloud Gateway** | Reactive (Project Reactor/Netty), JVM | Native to Spring ecosystem; filters in Java; great for JVM shops |
| **Amazon API Gateway** | AWS managed | REST/HTTP/WebSocket APIs; deep AWS integration; per-request pricing |
| **AWS ALB** | Managed L7 LB | Lighter than API Gateway; path/host routing |
| **Apigee (Google)** | Managed, API-management-heavy | Strong on developer portal, monetization, analytics |
| **Azure API Management** | Managed | Policy engine, dev portal |
| **Traefik** | Go, dynamic config | Popular with Docker/K8s; auto service discovery |
| **Tyk** | Go | OSS API management |
| **Zuul / Spring Cloud Gateway** | Netflix (Zuul 1 blocking, Zuul 2 async) / Spring | Zuul historically common in JVM/Netflix stacks; Spring Cloud Gateway is its modern JVM successor |

### 6.2 Kubernetes Gateway API & Ingress (the standard config surface)

- **Ingress** — the older Kubernetes API for L7 HTTP routing into the cluster; limited (host/path), extended via controller-specific annotations.
- **Gateway API** — the newer, role-oriented standard: `GatewayClass` (the implementation), `Gateway` (a listener/IP), `HTTPRoute`/`GRPCRoute`/`TCPRoute` (routing rules). It's the convergence point for gateways *and* meshes (the **GAMMA** initiative extends Gateway API to mesh east-west routing). Implemented by Envoy Gateway, Istio, Contour, etc.

### 6.3 Service mesh config objects

**Istio (version-specific; using classic sidecar APIs):**

| Object | Purpose | Key fields |
|---|---|---|
| `VirtualService` | Routing rules: match → route, splits, retries, timeouts, fault injection, mirroring | `http.match`, `route.weight`, `retries`, `timeout`, `fault` |
| `DestinationRule` | Per-destination policy: LB algorithm, connection pool limits, outlier detection, mTLS mode, **subsets** (named versions) | `trafficPolicy.loadBalancer`, `connectionPool`, `outlierDetection`, `subsets` |
| `Gateway` | Edge listener for ingress/egress at the mesh boundary | `servers.port`, `hosts`, `tls` |
| `PeerAuthentication` | mTLS mode (`STRICT`/`PERMISSIVE`/`DISABLE`) | `mtls.mode` |
| `AuthorizationPolicy` | L7 authz: who may call what | `rules.from`, `to`, `when` |
| `ServiceEntry` | Register external services into the mesh | `hosts`, `ports`, `resolution` |
| `Sidecar` | Limit a proxy's config scope (perf optimization) | `egress.hosts` |
| `Telemetry` | Configure metrics/tracing/logging | `tracing`, `metrics`, `accessLogging` |

**Linkerd:** uses Kubernetes-native + its own CRDs: `ServiceProfile` (per-route metrics, retries, timeouts), `HTTPRoute`/Gateway API for traffic splits, `Server`/`ServerAuthorization` (or newer authorization policy CRDs) for authz. Philosophy: fewer knobs, safer defaults.

### 6.4 Observability vocabulary the toolkit emits

- **RED metrics:** **R**ate (req/s), **E**rrors (error %), **D**uration (latency distribution) — the golden L7 signals a mesh emits per service per route.
- **Distributed tracing:** a **trace** is the end-to-end story of one request across services, composed of **spans** (one unit of work per hop), correlated by a **trace ID** propagated in headers. Standards: **W3C Trace Context** (`traceparent` header), **B3** (Zipkin's headers). Backends: Jaeger, Zipkin, Tempo. *Caveat:* meshes can create spans automatically, but they **cannot propagate trace headers across an app's internal logic** — the app must forward the incoming trace headers on its outbound calls, or traces break. This is a classic gotcha.
- **OpenTelemetry (OTel):** the CNCF standard for instrumentation (traces/metrics/logs) and the wire protocol **OTLP**. Both apps and meshes can export to OTel collectors.
- **Prometheus:** the de facto metrics scraper/store; meshes expose Prometheus-format metrics from proxies.

### 6.5 JVM-relevant resilience & gateway libraries (the "library" alternative)

| Library | Purpose | Notes |
|---|---|---|
| **Resilience4j** | Circuit breaker, retry, rate limiter, bulkhead, time limiter | Modern, functional, Spring Boot 3 integration; replaced Hystrix |
| **Hystrix** | Netflix circuit breaker | **Maintenance mode — do not start new projects on it**; mentioned for legacy context |
| **Spring Cloud Gateway** | JVM gateway with `GatewayFilter`s, predicates | Reactive (Netty) |
| **Spring Cloud LoadBalancer** | Client-side LB | Replaced Ribbon (also EOL) |
| **OpenFeign** | Declarative HTTP client | Often paired with Resilience4j |
| **OpenTelemetry Java agent** | Auto-instrumentation | Zero-code tracing for many libs |

---

## 7. Code examples by use case

The examples span: (1) a JVM API gateway with auth+rate-limit+routing; (2) a JVM BFF that fans out and aggregates; (3) JVM in-app resilience (the "library" approach); (4) Istio canary + mTLS + retries (the "mesh" approach); (5) a Kubernetes Gateway API route. Different scenarios, not variations of one.

### 7.1 Use case A — Spring Cloud Gateway: route, JWT auth, rate limit, header rewrite (Java/YAML)

`application.yml` for a Spring Cloud Gateway service:

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - RemoveRequestHeader=Cookie           # strip browser cookies before hitting backends
      routes:
        - id: orders-route
          uri: lb://orders-service             # lb:// = use Spring Cloud LoadBalancer + discovery
          predicates:
            - Path=/api/orders/**               # match path
            - Method=GET,POST
          filters:
            - StripPrefix=2                      # /api/orders/123 -> /123 for the backend
            - name: RequestRateLimiter           # token-bucket limiter backed by Redis
              args:
                redis-rate-limiter.replenishRate: 100   # tokens/sec (sustained rate)
                redis-rate-limiter.burstCapacity: 200    # bucket size (max burst)
                key-resolver: "#{@userKeyResolver}"      # rate-limit per authenticated user
            - AddRequestHeader=X-Source, gateway
  security:
    oauth2:
      resourceserver:
        jwt:
          # JWKS endpoint: gateway fetches issuer public keys to validate JWT signatures
          jwk-set-uri: https://auth.example.com/.well-known/jwks.json
```

The rate-limit key resolver and security config in Java:

```java
@Configuration
@EnableWebFluxSecurity
public class GatewayConfig {

    // Rate-limit bucket keyed by the authenticated subject (falls back to IP if anonymous).
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
            .map(Principal::getName)
            .defaultIfEmpty(
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress())
            .map(String::valueOf);
    }

    // Validate JWT on every request; reject unauthenticated calls at the edge.
    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
            .authorizeExchange(ex -> ex
                .pathMatchers("/api/public/**").permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())) // verifies signature, exp, etc.
            .csrf(ServerHttpSecurity.CsrfSpec::disable) // APIs use tokens, not cookies+CSRF
            .build();
    }
}
```

What matters: auth runs before routing to backends; the limiter is **distributed** (Redis) so the limit holds across gateway replicas; `StripPrefix`/`AddRequestHeader` decouple the public path/headers from the internal contract.

### 7.2 Use case B — A BFF that fans out concurrently and degrades gracefully (Java, Spring WebFlux)

```java
@RestController
class HomeScreenBff {

    private final WebClient catalog;       // pre-configured WebClients (base URLs to internal svcs)
    private final WebClient recommendations;
    private final WebClient loyalty;

    // Assembles the iOS home screen from 3 backend calls in parallel, tailored to the iOS client.
    @GetMapping("/ios/home")
    Mono<HomeView> home(@AuthenticationPrincipal Jwt user) {
        String userId = user.getSubject();

        Mono<List<Product>> featured = catalog.get().uri("/featured")
            .retrieve().bodyToFlux(Product.class).collectList()
            .timeout(Duration.ofMillis(300));   // hard per-call timeout

        Mono<List<Product>> recs = recommendations.get().uri("/for/{u}", userId)
            .retrieve().bodyToFlux(Product.class).collectList()
            .timeout(Duration.ofMillis(200))
            .onErrorReturn(List.of());           // GRACEFUL DEGRADATION: no recs -> empty, screen still renders

        Mono<LoyaltyStatus> loyaltyStatus = loyalty.get().uri("/status/{u}", userId)
            .retrieve().bodyToMono(LoyaltyStatus.class)
            .timeout(Duration.ofMillis(150))
            .onErrorReturn(LoyaltyStatus.unknown());

        // zip = run all three concurrently, combine when all complete (or degrade)
        return Mono.zip(featured, recs, loyaltyStatus)
            .map(t -> HomeView.forIos(             // shape into the iOS-specific view-model
                t.getT1(), t.getT2(), t.getT3()));
    }
}
```

What matters: three independent backend calls run **concurrently** (`Mono.zip`), each has its own **timeout**, and non-critical data (recs, loyalty) **degrades to a default** instead of failing the whole screen. The output is an iOS-specific view-model — a different endpoint (`/web/home`) would assemble a richer one. No domain business rules live here; it's pure aggregation+shaping.

### 7.3 Use case C — In-app resilience with Resilience4j (the "library" approach, Java)

When you do *not* have a mesh and want resilience in code:

```java
@Service
class PricingClient {

    private final WebClient pricing;

    // Declarative annotations: circuit breaker + retry + timeout, all in-process (no extra hop).
    @CircuitBreaker(name = "pricing", fallbackMethod = "cachedPrice")
    @Retry(name = "pricing")          // retries only on configured (transient) exceptions
    @TimeLimiter(name = "pricing")
    public CompletableFuture<Price> getPrice(String sku) {
        return pricing.get().uri("/price/{sku}", sku)
            .retrieve().bodyToMono(Price.class)
            .toFuture();
    }

    // Fallback invoked when the circuit is open or all retries fail.
    private CompletableFuture<Price> cachedPrice(String sku, Throwable t) {
        return CompletableFuture.completedFuture(PriceCache.last(sku));
    }
}
```

`application.yml` tuning:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pricing:
        slidingWindowSize: 100              # evaluate failure rate over last 100 calls
        failureRateThreshold: 50            # open circuit if >=50% fail
        waitDurationInOpenState: 10s        # stay open 10s before half-open trial
        permittedNumberOfCallsInHalfOpenState: 5
  retry:
    instances:
      pricing:
        maxAttempts: 3
        waitDuration: 200ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2     # 200ms, 400ms, 800ms
  timelimiter:
    instances:
      pricing:
        timeoutDuration: 500ms
```

What matters: this is the *same* resilience the mesh gives you, but in JVM code — lowest latency (no sidecar hop), full type-safety, but you'd reimplement it in every language and redeploy to change a threshold. Contrast directly with §7.4.

### 7.4 Use case D — Istio: mTLS, weighted canary, retries, outlier ejection (YAML)

Enforce mTLS for a namespace, then do a 90/10 canary to `orders` v2 with retries and circuit breaking — all without touching app code:

```yaml
# 1) Require mTLS for all workloads in the 'shop' namespace (zero-trust east-west).
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata: { name: default, namespace: shop }
spec:
  mtls: { mode: STRICT }                 # reject any non-mTLS traffic
---
# 2) Define named subsets (versions) and per-destination policy.
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata: { name: orders, namespace: shop }
spec:
  host: orders
  trafficPolicy:
    loadBalancer: { simple: LEAST_REQUEST }     # P2C-style least-request LB
    connectionPool:
      http: { http2MaxRequests: 1000, maxRequestsPerConnection: 100 }
    outlierDetection:                            # passive health check / circuit breaking
      consecutive5xxErrors: 5
      interval: 10s
      baseEjectionTime: 30s                      # eject a bad host for 30s
      maxEjectionPercent: 50
  subsets:
    - { name: v1, labels: { version: v1 } }
    - { name: v2, labels: { version: v2 } }
---
# 3) Route 90% to v1, 10% to v2; retry idempotent failures; hard timeout.
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata: { name: orders, namespace: shop }
spec:
  hosts: [ orders ]
  http:
    - route:
        - { destination: { host: orders, subset: v1 }, weight: 90 }
        - { destination: { host: orders, subset: v2 }, weight: 10 }
      timeout: 2s
      retries:
        attempts: 3
        perTryTimeout: 500ms
        retryOn: 5xx,reset,connect-failure       # only safe/transient conditions
```

What matters: identical resilience semantics to §7.3, but declared as *policy*, applied to *all* callers of `orders` regardless of language, changeable by editing YAML (no redeploy). The canary weight shift is a one-line edit — the data-plane proxies reconfigure live.

### 7.5 Use case E — Kubernetes Gateway API HTTPRoute (vendor-neutral north-south routing)

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: edge, namespace: infra }
spec:
  gatewayClassName: envoy                  # the implementation (Envoy Gateway here)
  listeners:
    - name: https
      protocol: HTTPS
      port: 443
      tls:
        mode: Terminate                    # TLS termination at the gateway
        certificateRefs: [ { name: example-tls } ]
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: orders, namespace: shop }
spec:
  parentRefs: [ { name: edge, namespace: infra } ]
  hostnames: [ "api.example.com" ]
  rules:
    - matches: [ { path: { type: PathPrefix, value: /orders } } ]
      filters:
        - type: RequestHeaderModifier
          requestHeaderModifier: { set: [ { name: X-Source, value: edge } ] }
      backendRefs: [ { name: orders-service, port: 8080, weight: 100 } ]
```

What matters: this is the *portable* config surface — the same `HTTPRoute` works across any Gateway API implementation, decoupling your routing intent from the specific proxy vendor.

---

## 8. Implementation concerns & best practices

### 8.1 Performance & overhead

- **Sidecar latency tax.** Each mesh hop adds *two* extra proxy traversals (caller's sidecar out, callee's sidecar in) per request — so a single A→B call traverses 2 proxies; a deep call chain multiplies this. Typical added **p50 latency** is on the order of **~0.5–few milliseconds per hop** for Envoy, lower for Linkerd's micro-proxy, but **tail latency (p99) and CPU** are the real costs under load. Treat any specific number as version/workload-specific and *measure your own*.
- **Resource cost.** Each Envoy sidecar consumes memory (tens to low-hundreds of MB depending on config scope) and CPU. With thousands of Pods this is a material cluster cost. Mitigations: the `Sidecar` resource to *scope* each proxy's config (don't push the whole mesh's config to every proxy); Istio Ambient mode to drop per-Pod sidecars; Linkerd for lower baseline overhead.
- **Gateway as bottleneck.** A single gateway tier is in the path of *all* external traffic. Size it, autoscale it, and avoid CPU-heavy work (e.g., large body transforms, synchronous calls to external authz on every request without caching).
- **mTLS cost.** TLS handshakes are CPU-expensive; meshes amortize this via long-lived, pooled connections between sidecars (handshake once, reuse). Connection pool tuning matters.
- **Library approach has the *lowest* latency** (no extra hop) — the reason latency-critical paths sometimes keep resilience in-process even in a meshed cluster.

### 8.2 Correctness & concurrency

- **Retries amplify load.** Naive retries during a partial outage create a **retry storm** that turns a brownout into an outage. Always cap attempts, use exponential backoff + jitter, and consider **retry budgets** (limit retries to e.g. 10% of total requests — Linkerd and Envoy support this). Retry only **idempotent** requests.
- **Double resilience.** If both the mesh *and* the app library do retries/timeouts, you get *multiplicative* retries (3 app × 3 mesh = 9) and conflicting timeouts. **Pick one layer per concern.** A common rule: let the mesh own LB/mTLS/retries; let the app own only fallbacks/business-aware degradation.
- **Timeout budget ordering.** Outer timeouts must exceed inner ones (client > gateway > mesh per-try × attempts), or you'll cancel work that would have succeeded. Compute a coherent **timeout budget** down the call tree.
- **Idempotency keys.** For non-idempotent operations (`POST /payment`), give the client an **idempotency key** so safe retries don't double-charge — this must be handled in the service, not the proxy.

### 8.3 Security

- **mTLS ≠ authorization.** mTLS proves *identity*; you still need `AuthorizationPolicy` to say *who may call what*. Default-deny is the zero-trust posture.
- **Don't leak the client's raw token to every backend.** At the gateway, validate the external token and forward a *minimal, internally-signed* identity (e.g., a short-lived internal JWT or trusted header set), so a compromised internal service can't replay the external credential.
- **Strip dangerous inbound headers.** Clients can spoof `X-User-Id`/`X-Forwarded-For`; the gateway must overwrite (not trust) such headers.
- **JWKS caching & rotation.** Cache issuer keys with sane TTL; handle key rotation (new `kid`) by refetching, or an attacker-revoked key lingers.
- **WAF/DDoS at the edge.** A **WAF** (Web Application Firewall) inspects requests for attack patterns (SQLi, XSS); pair the gateway with WAF/DDoS protection — the mesh does not do this for north-south.
- **Egress control.** Use the mesh to restrict which external endpoints services may reach (`ServiceEntry` + egress policy), limiting data-exfiltration blast radius.

### 8.4 Observability

- Emit **RED** metrics per route at the gateway and per service at the mesh; alert on error rate and p99, not just averages.
- Ensure **trace propagation**: the mesh creates spans but the *app must forward `traceparent`/B3 headers* on outbound calls, or the trace fragments. Add an OTel SDK/agent in apps for in-process spans.
- Correlate with a **request ID** generated at the gateway (`X-Request-Id`) and propagated everywhere — the single most useful debugging field.
- Watch **proxy-level dashboards** (Envoy/Linkerd) separately from app dashboards; a latency spike "in the mesh" vs "in the app" is diagnosed by comparing client-side proxy latency to server-side app latency.

### 8.5 Cost

- Mesh cost = sidecar CPU/RAM × Pod count + control-plane footprint + operational/learning cost. For a 20-service shop this can be larger than the problem it solves — hence "overkill below a threshold."
- Managed gateways (AWS API Gateway, Apigee) charge **per request** — at very high volume this can dwarf self-hosted Envoy/Kong on raw compute; model it.

### 8.6 Testing

- **Contract tests** (e.g., Pact) between BFFs and downstream services catch breaking changes without full integration.
- **Fault injection** (Istio `fault` / chaos tools) to test resilience policies: inject delays/aborts and verify graceful degradation and circuit breaking.
- **Local dev:** sidecars complicate local testing; many teams run apps *without* the mesh locally and rely on the mesh only in clusters, which can hide config bugs — test policies in a staging cluster.

### 8.7 Production hardening checklist

- Default-deny authz; STRICT mTLS once all workloads are meshed (roll out via PERMISSIVE first to avoid breaking un-meshed callers).
- Retry budgets + jitter; per-try and overall timeouts that form a coherent budget.
- Connection-pool and circuit-breaker limits sized to backend capacity (`maxConnections`, `http2MaxRequests`).
- Graceful gateway config reload (drain connections; don't drop in-flight requests).
- Control-plane HA and resource limits; alert if proxies fall behind on config (xDS staleness).
- Cert rotation tested; short cert lifetimes (mesh CAs often rotate workload certs every ~24h or less).
- Capacity-test the gateway tier; autoscale on CPU and connection count.

### 8.8 Anti-patterns recap

- Gateway-as-ESB (business logic at the edge).
- Mega-BFF / BFF-as-domain-service.
- Double retries/timeouts across mesh + library.
- Mesh adopted for a handful of services ("résumé-driven mesh").
- Trusting spoofable headers from clients.
- Synchronous external-authz call per request without caching (latency + availability coupling).
- Treating mTLS as authorization.

---

## 9. Advanced topics & deep internals

### 9.1 Envoy's xDS protocol — how the control plane programs the data plane

**xDS** is the family of discovery-service APIs Envoy uses to receive configuration dynamically over gRPC, instead of static files. The control plane is an **xDS server**; each Envoy is a client that subscribes and receives streamed updates. The sub-APIs:

- **LDS (Listener Discovery Service):** what ports/listeners to open and their filter chains.
- **RDS (Route Discovery Service):** HTTP route tables (path→cluster).
- **CDS (Cluster Discovery Service):** upstream clusters (groups of endpoints) and their policies (LB, circuit breaking).
- **EDS (Endpoint Discovery Service):** the actual endpoint IPs/health for each cluster.
- **SDS (Secret Discovery Service):** TLS certs/keys delivered securely — this is how mesh certs are pushed and rotated without restarts.
- **ADS (Aggregated Discovery Service):** all of the above over a single ordered stream, to avoid update-ordering races (e.g., routing to a cluster that doesn't exist yet).

The update sequence matters: to add a route you must CDS→EDS→RDS in a consistent order, or Envoy briefly references nonexistent config. ADS solves the ordering by serializing updates. This dynamic, ordered, restart-free reconfiguration is what makes "edit YAML, traffic shifts live" possible.

### 9.2 How traffic redirection actually works (iptables vs eBPF)

The init container installs `iptables` `REDIRECT`/`OWNER`-match rules in the Pod's netns so that outbound traffic (except the proxy's own) is sent to Envoy's outbound port (15001 in Istio) and inbound to its inbound port (15006), with carve-outs for health checks and the proxy's UID. Newer approaches use **eBPF** (a Linux kernel technology to run sandboxed programs on networking hooks) — e.g., Cilium or Istio Ambient's ztunnel paths — to redirect more efficiently and even do some L4 work in the kernel, reducing per-packet overhead. This is version/CNI-specific.

### 9.3 Identity, SPIFFE/SVID, and cert rotation

The mesh CA issues each workload a short-lived **SVID** (SPIFFE Verifiable Identity Document — typically an X.509 cert) encoding its SPIFFE ID derived from its Kubernetes ServiceAccount. The sidecar requests/renews this via SDS automatically (often hourly/daily). Because lifetimes are short, a leaked cert has a tiny exploit window, and revocation is "wait for expiry." This is *workload identity*, distinct from *user identity* (the JWT at the gateway) — keep the two layers conceptually separate.

### 9.4 Locality-aware & zone-aware load balancing

To cut cross-zone network cost and latency, proxies can prefer endpoints in the *same availability zone*, spilling over to other zones only when local capacity is unhealthy. Tuning the spillover (priority/weights) trades resilience vs cost. Misconfiguration causes either expensive cross-zone traffic or local hot spots.

### 9.5 Circuit breaking vs outlier detection (they're different)

- **Circuit breaking** in Envoy is really *connection/request limits* (`maxConnections`, `maxPendingRequests`, `maxRequests`): when limits are hit, new requests are shed immediately (fail fast). It protects against overload.
- **Outlier detection** is *passive health checking*: eject specific endpoints that return errors/are slow. It protects against a few bad instances.
  Both together approximate the classic "circuit breaker" concept; know the distinction for interviews.

### 9.6 Sidecar-less / ambient architectures

Istio Ambient splits concerns: **ztunnel** (a per-node L4 proxy) handles mTLS and L4 transport for all Pods on the node, while L7 features (retries, routing) require an optional per-namespace **waypoint** proxy. Benefit: Pods without L7 needs pay only the cheap L4 path; no per-Pod sidecar injection/upgrades. Trade-off: a different, newer operational model; L7 features cost an extra waypoint hop. **eBPF-based meshes** (e.g., Cilium Service Mesh) push more into the kernel. Treat all of this as fast-moving and version-specific.

### 9.7 Multi-cluster & mesh federation

Meshes can span clusters (for HA/locality): the control plane(s) share service discovery and trust (a shared root CA), so `frontend` in cluster A can mTLS to `orders` in cluster B via east-west gateways. Adds complexity in trust distribution and cross-cluster discovery; reserved for serious scale/DR requirements.

### 9.8 Protocol nuances

- **HTTP/2 & gRPC** multiplex many streams over one connection; LB must balance *requests*, not connections (hence `http2MaxRequests`, least-request LB), or one connection pins all traffic to one backend.
- **WebSockets / long-lived streams** complicate timeouts and draining (you can't just cut them on config reload); configure idle timeouts and graceful drain explicitly.
- **Protocol detection:** meshes sniff whether traffic is HTTP/1, HTTP/2, or raw TCP; misdetection (e.g., a non-HTTP protocol on an HTTP port) breaks routing — declare ports explicitly (`appProtocol`).

---

## 10. Tradeoffs & decision frameworks

### 10.1 Gateway vs Mesh vs Library — decision table

| If you need… | Choose | Why |
|---|---|---|
| North-south auth/TLS/throttling | **Gateway** | Edge is the gateway's job; mesh doesn't do public auth/WAF |
| Uniform mTLS across many polyglot services | **Mesh** | Free, consistent, no app changes |
| Resilience in a single-language, latency-critical service | **Library** | No extra hop; full control |
| Client-specific API shaping + team ownership | **BFF** | Aligns API with frontend team |
| Canary/traffic-shift for east-west deploys | **Mesh** | Weighted routing without redeploy |
| Canary at the public edge | **Gateway** | Edge routing/splits |
| Consistent east-west telemetry with zero app work | **Mesh** | Proxies emit RED + traces |
| Only ~3–10 services, one language | **Library (+ small gateway)** | Mesh overkill |

### 10.2 "Use when / avoid when"

**API Gateway** — *Use when:* you have external clients and want centralized edge concerns (almost always). *Avoid when:* you'd push business logic into it, or use it for east-west traffic.

**BFF** — *Use when:* multiple client types with divergent needs and you want frontend teams to own their backend. *Avoid when:* a single client, or when GraphQL gives you per-client shaping more cheaply, or when it'd become a domain-logic dumping ground.

**Service Mesh** — *Use when:* many services, polyglot, hard mTLS/observability/traffic-management requirements, and a platform team to operate it. *Avoid when:* few services, single JVM stack (libraries suffice), tight latency budgets you can't spend on hops, or no one to own the operational complexity.

**Library** — *Use when:* single language, latency-critical, small fleet. *Avoid when:* polyglot, or when redeploy-to-change-policy is too slow, or you need uniform org-wide behavior.

### 10.3 Gateway vs BFF vs "API composition in a service"

| Approach | Aggregation logic owner | Coupling to clients | Best when |
|---|---|---|---|
| Thin gateway (no aggregation) | None | Low | Clients tolerate multiple calls |
| BFF per client | Frontend team | Per-client, decoupled | Divergent clients |
| GraphQL gateway | Resolver/graph team | Client self-serves | Many evolving clients |
| Aggregation in a domain service | That service's team | Risky (domain leaks UI concerns) | Rarely — avoid UI shaping in domain svcs |

### 10.4 The adoption ladder (pragmatic progression)

1. **Monolith / few services:** in-process calls + one gateway for the edge. No mesh, no BFF.
2. **Growing, one client:** gateway + libraries (Resilience4j) for resilience.
3. **Multiple clients:** add BFFs per client type.
4. **Many polyglot services, security/observability mandates:** adopt a mesh (start Linkerd if simplicity wins; Istio if you need its feature depth), rolled out incrementally (PERMISSIVE mTLS → STRICT).
5. **Multi-cluster/global scale:** mesh federation, locality LB, ambient/eBPF optimizations.

---

## 11. Failure modes & debugging

### 11.1 Gateway failure modes

- **Cert expiry at the edge** → all clients get TLS errors at once. *Diagnose:* `openssl s_client -connect host:443 | openssl x509 -noout -dates`; monitor cert expiry proactively. *Real-world:* many large outages (across the industry) trace to an expired TLS cert.
- **JWKS endpoint unreachable / key rotation** → all auth fails with 401. *Diagnose:* gateway logs show signature/JWKS fetch errors; check cached key TTL and issuer availability. Mitigate with longer JWKS cache + stale-while-revalidate.
- **Rate-limiter store (Redis) down** → either fail-open (limits not enforced, backends exposed) or fail-closed (everything 429'd). Decide the policy deliberately; test it.
- **Gateway CPU saturation** → rising p99 across *all* routes simultaneously. *Diagnose:* gateway CPU/connection metrics; look for expensive transforms or per-request external authz. Autoscale; cache authz.
- **Route misconfig / shadowed routes** → traffic to the wrong backend or 404. *Diagnose:* config dump (`curl localhost:.../config_dump` in Envoy) and route-match tracing.

### 11.2 Mesh failure modes

- **mTLS turned STRICT before all callers are meshed** → un-meshed clients suddenly get connection resets. *Diagnose:* sidecar inbound metrics show TLS errors; roll back to PERMISSIVE; mesh incrementally. (A famous category of self-inflicted mesh outages.)
- **Control plane (`istiod`) outage** → you can't push new config and new Pods may not get configured/injected, but existing traffic keeps flowing on cached config. *Diagnose:* control-plane health, xDS connection metrics; ensure HA.
- **xDS config staleness / push storms** → proxies lag behind intended config; a config change "doesn't take." *Diagnose:* `istioctl proxy-status` (shows SYNCED/STALE per proxy), Envoy `config_dump`, control-plane push metrics.
- **Retry storms / cascading failure** → one slow service causes mesh retries to multiply load and topple the cluster. *Diagnose:* request-volume amplification in metrics; fix with retry budgets, circuit breaking, lower attempts.
- **Double timeout/retry (mesh + app)** → inexplicably long tail latency or premature cancellations. *Diagnose:* compare app-side vs proxy-side timeouts; remove duplication.
- **Sidecar OOM / CPU** under load → injected latency, request drops. *Diagnose:* per-Pod Envoy resource metrics; scope config with `Sidecar`; raise limits.
- **Trace gaps** → traces start at the gateway but break mid-chain because an app didn't propagate `traceparent`. *Diagnose:* spans with no parent; add header propagation in the app.
- **iptables/CNI conflict** → traffic not redirected to the sidecar, mTLS silently bypassed, or Pod can't reach anything. *Diagnose:* check init-container logs, `iptables -t nat -L` in the Pod netns, CNI compatibility notes.

### 11.3 The debugging toolkit (concrete commands)

| Tool / command | Use |
|---|---|
| `istioctl proxy-status` | Which proxies are SYNCED vs STALE with the control plane |
| `istioctl proxy-config {routes,clusters,endpoints,listeners} <pod>` | Inspect what config a specific Envoy actually has |
| `istioctl analyze` | Lint mesh config for common mistakes |
| `linkerd check` / `linkerd viz stat` | Linkerd health + live RED metrics |
| `linkerd viz tap` | Live per-request inspection in Linkerd |
| Envoy `GET localhost:15000/config_dump` / `/stats` / `/clusters` | Raw proxy config, metrics, endpoint health |
| `kubectl logs <pod> -c istio-proxy` | Sidecar access/error logs |
| `openssl s_client` | TLS/cert diagnosis at the edge |
| Prometheus + Grafana / Kiali | RED metrics, mesh topology graph |
| Jaeger/Tempo + trace IDs | Cross-service latency attribution |

### 11.4 A worked diagnostic flow

Symptom: p99 latency for `/checkout` spiked. Steps: (1) Is it the gateway or interior? Compare gateway route p99 vs each service's proxy p99 — find where the jump happens. (2) At the offending hop, compare *client-side proxy* latency vs *server-side app* latency: if the proxy waited but the app was fast, it's LB/connection-pool/queueing; if the app was slow, it's the app/its dependency. (3) Check outlier-detection ejections (a few bad pods?). (4) Check retry amplification (request count > usual?). (5) Pull a trace for a slow request and read span durations to pinpoint the exact culprit hop. This top-down, proxy-vs-app comparison is the canonical mesh debugging discipline.

---

## 12. Interview drill

**Q1 (basics). What is an API gateway and what belongs in it vs not?**
*Model answer:* An L7 reverse proxy at the north-south edge that centralizes cross-cutting external concerns: TLS termination, authn, routing, coarse authz, rate limiting, request/response transformation, edge observability, light aggregation, canary routing. What does *not* belong: business logic, fine-grained data-dependent authorization, heavy orchestration — anything where a business-rule change would force a gateway deploy.
- *Probe: Why not fine-grained authz at the gateway?* It needs domain data the gateway shouldn't own; do scope/role checks at the edge, resource ownership checks in the owning service.
- *Probe: Local vs distributed rate limiting?* Local is fast/approximate (each replica enforces independently → client can get N× the limit); distributed (Redis) is accurate cluster-wide but adds a hop/dependency. Use local for DoS protection, distributed for billing-grade quotas.
- *Probe: What's the gateway-as-ESB anti-pattern?* Accreting business logic at the edge until it becomes an unmaintainable middleware monolith.

**Q2 (basics). Explain the BFF pattern and when you'd use it.**
*Model answer:* A backend dedicated to one frontend experience, owned by that frontend's team, that aggregates and reshapes downstream services into exactly that client's needs — cutting over/under-fetching and decoupling client evolution. Use it with multiple divergent client types and strong team ownership; don't use it for a single client or let it accumulate domain logic.
- *Probe: BFF vs GraphQL?* GraphQL lets each client shape its own response via queries (one graph, client autonomy); BFF shapes server-side per client (team ownership, easy HTTP caching). A GraphQL server is essentially a generic BFF.
- *Probe: How many BFFs?* One per *experience*, grouping clients with near-identical needs; too many multiplies ops cost, too few re-creates the one-size-fits-all problem.

**Q3 (basics). What is a service mesh and what does it actually solve?**
*Model answer:* Infrastructure that moves east-west cross-cutting concerns — mTLS, retries, timeouts, load balancing, circuit breaking, traffic shifting, telemetry — out of app code into a data plane of sidecar proxies, configured by a central control plane. It solves the "re-implement resilience+security+observability in every service and language" problem, giving uniform behavior without code changes.
- *Probe: Data plane vs control plane?* Data plane = sidecar proxies in every request's hot path; control plane = the off-path brain that computes and pushes config (and issues certs). Control-plane outage doesn't stop existing traffic.
- *Probe: How does traffic reach the sidecar?* iptables/eBPF rules in the Pod netns transparently redirect all in/out traffic to the local proxy; injected via a mutating admission webhook.

**Q4 (mechanism). Walk through an east-west request in a mesh.**
*Model answer:* App calls service by DNS → iptables redirects out to local sidecar → sidecar picks a healthy endpoint via LB → initiates mTLS to the destination sidecar (validating SPIFFE identities) → applies timeout/retry/circuit-breaker → destination sidecar terminates mTLS, enforces authz, forwards to app over localhost → response reverses; both proxies emit metrics/traces. App code did none of it.
- *Probe: What's SPIFFE/SVID?* A standard workload identity (URI) materialized as a short-lived X.509 cert (SVID) issued by the mesh CA, delivered/rotated via SDS.
- *Probe: How is config pushed live?* Via Envoy's xDS (LDS/RDS/CDS/EDS/SDS, aggregated as ADS) over gRPC — restart-free, ordered updates.

**Q5 (mechanism). Gateway vs mesh vs library — when each?**
*Model answer:* Library = in-process resilience, lowest latency, but per-language and redeploy-to-change; best for small single-language fleets. Mesh = out-of-process sidecars, language-agnostic uniform mTLS/resilience/telemetry, at the cost of latency/CPU/ops; best for many polyglot services. Gateway = the north-south edge, complementary to both. Big systems run all three.
- *Probe: Why not always mesh?* Overhead (latency tax per hop, CPU/RAM per Pod, operational complexity) outweighs benefit below ~dozens of services or in tight latency budgets.
- *Probe: Can mesh and library conflict?* Yes — double retries multiply (3×3=9) and conflicting timeouts cancel good work; assign each concern to one layer.

**Q6 (senior-signal, tradeoff). You have 12 Java microservices, single language, p99 latency budget is tight. A staff engineer proposes Istio. Argue.**
*Model answer:* Push back. With 12 single-language JVM services, libraries (Resilience4j + Spring Cloud LoadBalancer + OTel agent) deliver the same resilience/telemetry with *no* per-hop latency tax and no sidecar CPU/RAM or new control-plane ops surface — and the team already knows the JVM stack. The mesh's headline win (uniform polyglot mTLS/observability without code) is weak here because it's one language and a small fleet, and its costs (latency budget, operational complexity, new failure modes) are high. I'd adopt a gateway at the edge, use libraries internally, and revisit a mesh (likely Linkerd first for low overhead) only when service count, polyglot pressure, or a hard zero-trust mandate crosses a threshold. If zero-trust mTLS is mandated *now*, that single requirement might justify a mesh — but I'd quantify the latency overhead against the budget first.
- *Probe: What single requirement would flip you to "mesh now"?* A hard org-wide mTLS/zero-trust mandate or rapidly growing polyglot services — encryption+identity for free across languages is the one thing libraries can't cheaply standardize.

**Q7 (senior-signal, tradeoff). Your gateway team wants to add response aggregation and per-client field shaping in the shared gateway. Good idea?**
*Model answer:* No — that's BFF work masquerading as gateway work. Per-client shaping in a shared gateway couples every client's needs into one component owned by infra, recreating the one-size-fits-all churn and making the gateway a logic dumping ground. Keep the gateway generic (TLS/auth/routing/throttling); introduce BFFs owned by each frontend team for aggregation and shaping. Light, cross-client aggregation can stay at the gateway; client-*specific* view-models cannot.
- *Probe: Where's the line between "light aggregation" (ok) and "BFF" (not)?* If the aggregation is generic and client-agnostic, gateway is fine; the moment it encodes one client's screen/view-model or one team's evolving needs, it belongs in that team's BFF.

**Q8 (senior-signal, justification). Design the resilience strategy for a meshed cluster so retries don't cause cascading failure.**
*Model answer:* (1) Retry only idempotent requests, with bounded attempts (e.g., 2–3), exponential backoff + jitter. (2) Use **retry budgets** to cap retries to a small fraction (e.g., 10–20%) of base traffic so retries can't multiply load during a brownout. (3) Circuit-break/fail-fast via connection/request limits and outlier ejection so a struggling dependency sheds load instead of queueing. (4) Coherent timeout budget: outer > inner across the call tree. (5) Don't double up — let the mesh own retries/LB; app owns only business-aware fallbacks. (6) Load-test with fault injection to verify the system *degrades* rather than amplifies under partial failure.
- *Probe: Why is a retry budget better than just capping attempts?* Per-request attempt caps still let *aggregate* retries explode when many requests fail simultaneously; a budget caps the *total* retry volume, directly preventing the storm.
- *Probe: How do timeouts and retries interact dangerously?* If the overall timeout is shorter than attempts × per-try timeout, later retries never run; if longer, retries can pile up past the client's patience — you must compute the budget so attempts fit within the outer deadline.

**Q9 (mechanism). What is mTLS in a mesh and why isn't it sufficient for security?**
*Model answer:* mTLS encrypts the connection and mutually authenticates both sidecars via mesh-issued certs (SPIFFE identities), giving zero-trust *identity* and confidentiality east-west. It is not sufficient because it only proves *who* is calling, not *what they may do* — you still need authorization policy (default-deny + explicit allow), and it doesn't address north-south auth, WAF, or input validation.
- *Probe: How are certs managed?* Mesh CA issues short-lived SVIDs per workload (tied to ServiceAccount), auto-rotated via SDS; short lifetimes shrink the leak window and make revocation "wait for expiry."

**Q10 (ops). The control plane (`istiod`) is down. What's the impact?**
*Model answer:* Existing data-plane proxies keep forwarding traffic using their last-pushed config — running traffic is fine. What breaks: you can't push config changes, new Pods may not get injected/configured, and cert rotation/issuance may stall (risking expiry if the outage is long). Fix: HA control plane, alert on xDS sync staleness, and ensure cert lifetimes comfortably exceed expected control-plane recovery time.
- *Probe: How do you detect proxies are stale?* `istioctl proxy-status` shows SYNCED/STALE; monitor control-plane push and xDS connection metrics.

**Q11 (basics→depth). Difference between L4 and L7 proxying, and why does it matter for gateways/meshes?**
*Model answer:* L4 sees connections/IPs/ports but not protocol content; L7 parses HTTP/gRPC (method, path, headers). L7 enables path routing, header-based auth, retries on idempotent methods, per-route metrics — but costs parsing/buffering. Gateways and meshes are mostly L7 for HTTP/gRPC with L4 fallback for opaque TCP.
- *Probe: Why might you prefer L4 for some mesh traffic?* Lower overhead for protocols that don't need L7 features (e.g., raw DB connections) — Istio Ambient's ztunnel does L4-only mTLS cheaply, adding L7 only when needed.

**Q12 (depth). Why can a mesh create spans automatically but still leave broken traces?**
*Model answer:* The sidecar can emit a span for each hop it proxies, but it can't stitch spans into one trace unless the *trace context headers* (`traceparent`/B3) flow through the application: when an app receives a request and makes outbound calls, it must copy the incoming trace headers onto the outbound request. If it doesn't, each hop starts a new, unparented trace and the end-to-end trace fragments. So apps still need minimal header-propagation (an OTel SDK/agent handles it for common frameworks).
- *Probe: What header standard and what generates the root?* W3C Trace Context (`traceparent`) or B3; the gateway typically originates the root span/`X-Request-Id` and everything downstream propagates it.

---

## 13. Glossary

- **ADS (Aggregated Discovery Service):** Envoy xDS variant delivering all config types over one ordered gRPC stream to avoid update-ordering races.
- **API Gateway:** L7 reverse proxy at the north-south edge centralizing auth, TLS, routing, throttling, and shaping.
- **Authentication (authn):** verifying *who* a caller is.
- **Authorization (authz):** verifying *what* a caller may do.
- **Backoff / jitter:** increasing wait between retries / randomizing it to avoid synchronized retry storms.
- **BFF (Backend-for-Frontend):** a backend dedicated to one frontend experience, owned by that frontend team, for aggregation/shaping.
- **Bulkhead:** resource isolation (separate pools) so one saturated dependency can't sink everything.
- **CA (Certificate Authority):** trusted issuer that signs certificates vouching for identities.
- **Canary deployment:** routing a small % of traffic to a new version to validate before full rollout.
- **CDS/EDS/LDS/RDS/SDS:** Envoy xDS sub-APIs for clusters/endpoints/listeners/routes/secrets.
- **Circuit breaker:** fail-fast mechanism that stops calling a failing dependency, periodically probing recovery; in Envoy, connection/request limits.
- **Consistent hashing:** mapping keys to backends via a hash ring so adding/removing backends remaps few keys.
- **Control plane:** the off-path "brain" that configures the data plane and issues certs.
- **CORS (Cross-Origin Resource Sharing):** browser policy controlling cross-origin requests.
- **Data plane:** the proxies in every request's hot path.
- **DDoS:** distributed denial-of-service attack; flooding to exhaust resources.
- **East-west traffic:** service-to-service traffic inside the system.
- **eBPF:** Linux kernel tech to run sandboxed programs at networking/other hooks; used for efficient traffic redirection.
- **Egress:** outbound traffic leaving the mesh/cluster.
- **Envoy:** high-performance C++ L7 proxy; the data plane of most meshes/gateways; defined xDS.
- **ESB (Enterprise Service Bus):** heavyweight SOA-era middleware; the anti-pattern of edge logic dumping grounds.
- **Endpoints/EndpointSlice:** Kubernetes objects listing live Pod IPs behind a Service.
- **Fault injection:** deliberately injecting delays/errors to test resilience.
- **Forward proxy:** intermediary in front of clients.
- **Gateway API (Kubernetes):** standard role-oriented routing API (GatewayClass/Gateway/HTTPRoute), extends to mesh via GAMMA.
- **GraphQL:** query language where clients specify exactly the fields they want in one request.
- **Graceful degradation:** returning a partial/default result when a non-critical dependency fails.
- **gRPC:** HTTP/2-based RPC framework with multiplexed streams.
- **Hystrix:** Netflix circuit breaker, now in maintenance mode (legacy).
- **Idempotent:** safe to repeat with the same effect; prerequisite for safe automatic retries.
- **Idempotency key:** client-supplied key letting a service dedupe retried non-idempotent operations.
- **Ingress:** older Kubernetes L7 routing API.
- **iptables:** Linux packet-filtering/NAT framework; used to redirect Pod traffic to the sidecar.
- **Istio:** feature-rich Envoy-based mesh; control plane `istiod`.
- **JWKS (JSON Web Key Set):** URL publishing an issuer's public keys for JWT validation.
- **JWT (JSON Web Token):** signed token carrying claims (subject, expiry, scopes).
- **L4 / L7:** transport layer (TCP/UDP) / application layer (HTTP/gRPC).
- **Least-request LB:** send to the backend with fewest in-flight requests (P2C in practice).
- **Linkerd:** lightweight Rust-micro-proxy mesh prioritizing simplicity/low overhead.
- **Load balancing:** distributing requests across backend instances.
- **Maglev:** Google consistent-hashing LB variant.
- **mTLS (mutual TLS):** both peers authenticate via certs; basis of zero-trust east-west.
- **Mutating admission webhook:** Kubernetes hook that modifies objects on creation (injects sidecars).
- **North-south traffic:** traffic crossing the system boundary (clients ↔ system).
- **OpenTelemetry (OTel):** standard for traces/metrics/logs instrumentation; OTLP wire protocol.
- **Outlier detection:** passive health check ejecting bad endpoints from the LB pool.
- **Over-/under-fetching:** clients receiving too many / too few fields, needing extra calls.
- **P2C (power-of-two-choices):** pick two random backends, route to the less loaded; near-optimal at O(1).
- **PeerAuthentication / AuthorizationPolicy (Istio):** mTLS mode / L7 authz rules.
- **Pod:** Kubernetes smallest deployable unit; containers sharing a network namespace/IP.
- **Prometheus:** metrics scraper/store; meshes expose Prometheus metrics.
- **Rate limiting / throttling:** capping request rate / slowing-or-rejecting over-limit requests (429).
- **RED metrics:** Rate, Errors, Duration — golden L7 signals.
- **Retry budget:** cap on total retry volume as a fraction of base traffic, preventing retry storms.
- **Reverse proxy:** intermediary in front of servers.
- **Resilience4j:** modern JVM resilience library (circuit breaker/retry/rate limiter/bulkhead).
- **Service discovery:** finding current healthy addresses of a service.
- **Service mesh:** infrastructure layer of proxies handling east-west cross-cutting concerns.
- **Sidecar:** helper proxy container alongside an app, sharing its Pod/netns.
- **SPIFFE / SVID:** standard workload identity / its X.509 (or JWT) materialization.
- **Spring Cloud Gateway:** JVM reactive API gateway.
- **TLS termination:** decrypting inbound TLS at a proxy.
- **Trace / span / trace context:** end-to-end request story / one unit of work / propagated correlation headers (`traceparent`, B3).
- **Token bucket / leaky bucket:** rate-limiting algorithms allowing bursts / smoothing them.
- **VirtualService / DestinationRule (Istio):** routing rules / per-destination policy (LB, outlier, mTLS, subsets).
- **WAF (Web Application Firewall):** inspects/blocks malicious HTTP (SQLi, XSS).
- **xDS:** Envoy's dynamic configuration API family over gRPC.
- **Zero-trust:** never trust the network; authenticate and authorize every hop.
- **ztunnel / waypoint:** Istio Ambient per-node L4 proxy / per-namespace L7 proxy (sidecar-less mode).

---

## 14. Cheat-sheet & self-test

### 14.1 One-screen recap

- **Three patterns, two axes:** Gateway = north-south edge. Mesh = east-west interior. BFF = client-fit (per frontend). Library = in-process east-west alternative to the mesh.
- **Gateway does:** TLS termination, authn, routing, coarse authz, rate limiting, transforms, light aggregation, edge observability, canary. **Gateway must NOT do:** business logic, fine-grained data authz, heavy orchestration, per-client view-models (→ that's a BFF).
- **BFF:** one per *experience*, owned by the frontend team; aggregate + shape; no domain rules. Alternative: GraphQL (client self-shapes).
- **Mesh = data plane (sidecars, hot path) + control plane (brain, off-path).** Solves mTLS, retries/timeouts, LB, circuit breaking, traffic shifting, RED+tracing — *without app code*. Control-plane down ⇒ existing traffic OK, no new config.
- **Config flows via xDS** (LDS/RDS/CDS/EDS/SDS, aggregated = ADS) over gRPC; traffic redirected via iptables/eBPF; identity via SPIFFE/SVID from mesh CA (short-lived, auto-rotated).
- **Implementations:** Istio (Envoy, most features), Linkerd (Rust micro-proxy, simplest/lowest overhead), Consul (Envoy, works on VMs). Istio Ambient = sidecar-less (ztunnel L4 + waypoint L7).
- **Decision:** few + single-language + latency-tight ⇒ **library**. Many + polyglot + mTLS/observability mandate ⇒ **mesh**. External clients ⇒ **gateway** (always). Divergent clients ⇒ **BFF**.
- **Resilience rules:** retry idempotent only; bounded attempts; exp backoff + jitter; **retry budgets**; don't double up mesh+library; coherent timeout budget (outer > inner).
- **Numbers/defaults (verify per version):** Resilience4j sample — failureRate 50%, sliding window 100, open 10s; Istio outlier — 5 consecutive 5xx, eject 30s, ≤50% ejected; mesh per-hop latency ≈ sub-ms to a few ms (measure!); cert lifetimes often ~24h or less.
- **Debug discipline:** compare gateway-route p99 vs per-service proxy p99; at the bad hop compare client-side-proxy vs server-side-app latency; check outlier ejections + retry amplification; pull a trace. Tools: `istioctl proxy-status`/`proxy-config`/`analyze`, `linkerd check`/`viz stat`/`tap`, Envoy `/config_dump` `/stats` `/clusters`, `openssl s_client`, Prometheus/Grafana/Kiali, Jaeger/Tempo.
- **Top anti-patterns:** gateway-as-ESB; mega-BFF; double retries; mesh for a handful of services; trusting spoofable client headers; mTLS treated as authz; per-request external authz without caching.

### 14.2 Self-test (no answers — recall actively)

1. Draw the request lifecycle through an API gateway in order, and state at which step a `401`, a `429`, and a `403` are emitted and why.
2. A teammate sets Istio `PeerAuthentication` to STRICT mTLS namespace-wide on Friday afternoon and half your traffic starts getting connection resets. What happened, how do you confirm it, and what's the correct rollout sequence?
3. You see end-to-end traces that start at the gateway but break after the second service. The mesh is creating spans. Explain precisely why the trace fragments and what must change — and in which component.
4. Given 9× the expected retry volume during a brownout, explain mechanistically how a per-request attempt cap fails to prevent the storm while a retry budget does, and write the rough policy you'd configure.
5. Justify, for a *specific* system you choose (state its size, languages, latency budget, and security requirements), whether to use a service mesh, in-process resilience libraries, or both — and name the single requirement that would flip your decision.
6. Explain the difference between Envoy "circuit breaking" and "outlier detection," and how together they approximate the classic circuit-breaker concept.
7. Where is the boundary between "light aggregation that's fine in a gateway" and "aggregation that must move to a BFF"? Give a concrete example on each side of the line.


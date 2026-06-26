# API Gateway & Management

> An engineering handbook chapter for senior backend developers. From first principles to deep internals: how to design with an API gateway, operate and debug it in production, and reason about the full API management lifecycle.

---

## 1. Overview & where it fits

### 1.1 What it is

An **API gateway** is a server that sits at the edge of your system and is the single, managed entry point for client requests destined for one or more backend services. Every external (and sometimes internal) call to your APIs flows *through* the gateway, which inspects the request, applies a chain of cross-cutting policies (authentication, rate limiting, transformation, etc.), routes it to the correct upstream service, and then processes the response on the way back out.

A useful first analogy: the gateway is the **reverse proxy with a brain**. A plain reverse proxy (e.g. nginx forwarding to backends) just relays traffic. An API gateway is a reverse proxy that *also* understands the *semantics* of your APIs — it knows about routes, consumers, API keys, quotas, request shapes, and SLAs — and enforces policy per route and per consumer.

> **Reverse proxy (term):** A server that receives client requests and forwards them to one or more backend ("upstream") servers, then returns the backend's response to the client. The client thinks it is talking to one server. Contrast with a *forward* proxy, which sits in front of *clients* (e.g. a corporate egress proxy) and forwards their outbound requests to the internet.
>
> **Upstream / downstream (terms):** Conventions vary, but in proxy/gateway parlance the **upstream** is the backend service the gateway forwards to (the thing "up the river" that produces the response); **downstream** is the client side. Some teams use the words in the opposite sense, so always confirm the convention in a given doc. This chapter uses *upstream = backend*.

**API management** is the broader discipline (and the product category) that wraps the gateway with everything needed to run APIs as a product over their full lifecycle: designing and documenting them, mocking them before they exist, publishing them, onboarding developers via a portal, issuing and rotating credentials, measuring usage with analytics, and sometimes charging money for them (monetization). The gateway is the *runtime* (data plane) component; API management is the *runtime + lifecycle + governance + business* envelope around it.

### 1.2 The problem it solves

Without a gateway, in a microservices system, every client must know about every service, and every service must independently implement the same cross-cutting concerns:

- **Client coupling.** A mobile app would need URLs, ports, and protocol knowledge for dozens of services. When you split or move a service, every client breaks.
- **Duplicated cross-cutting logic.** Authentication, TLS termination, rate limiting, logging, CORS, and request validation would each be re-implemented (slightly differently, with slightly different bugs) in every service.
- **No central control.** No single place to enforce a global rate limit, rotate a signing key, block a misbehaving client, or get a unified view of traffic.
- **Security surface sprawl.** Every service exposed directly to the internet is an attack surface that must be individually hardened.
- **Chatty clients.** A screen that needs data from 5 services would make 5 round-trips over a high-latency mobile network.

The gateway centralizes all of this at the edge: one ingress point, one place to terminate TLS, one place to authenticate, one place to enforce quotas, one place to observe traffic, and a place to *aggregate* multiple backend calls into a single client response.

> **Cross-cutting concern (term):** Functionality that is needed across many parts of a system but is not the core business logic of any one of them — e.g. authentication, logging, rate limiting, metrics. The aspiration is to implement it once, centrally, rather than scattering it.
>
> **CORS (term):** Cross-Origin Resource Sharing. A browser security mechanism: by default a web page loaded from `https://a.com` may not make scripted requests to `https://b.com`. The server at `b.com` must return specific `Access-Control-Allow-*` HTTP headers to permit it. Gateways commonly handle CORS centrally.

### 1.3 When you reach for it

- You have **more than a couple of services** behind a public API and want one controlled ingress.
- You need **uniform auth, rate limiting, and observability** across many endpoints.
- You are **exposing APIs to external developers or partners** and need keys, quotas, a portal, and analytics (this is the API-management case, not just gateway).
- You want to **decouple client-facing API shape** from internal service boundaries (versioning, aggregation, protocol translation).
- You are migrating a **monolith to microservices** and want the gateway as a *strangler facade* (route some paths to the old monolith, others to new services).

When you do **not** need one (yet): a single backend service with no external consumers; an internal-only system where service-to-service auth is handled by a service mesh; a tiny project where the operational cost of running and securing a gateway outweighs the benefit. A gateway is itself a system you must run, scale, secure, and debug.

> **Strangler fig / strangler facade (term):** A migration pattern (named after the strangler fig vine) where you put a facade in front of a legacy system and incrementally route slices of functionality to new implementations, until the legacy system is fully "strangled" and can be removed. The gateway is a natural place to host this facade.
>
> **Service mesh (term):** Infrastructure (e.g. Istio, Linkerd) that handles service-to-service ("east-west") communication concerns — mTLS, retries, load balancing, observability — usually via sidecar proxies next to each service. Complementary to a gateway, which handles "north-south" edge traffic. (See §1.6 and §7.)

### 1.4 The one-paragraph mental model

> Think of the gateway as a **programmable funnel and policy engine at the edge of your system**. Requests pour in from the outside world, hit a *route-matching* layer that decides which backend each request belongs to, then pass through an ordered *pipeline of plugins/filters* — TLS termination, authn/z, rate limiting, request transformation, caching — before being load-balanced to a healthy upstream instance; the response flows back through a (usually reverse-ordered) pipeline for response transformation, header injection, metrics, and caching. Around this runtime sits a **control plane** (config store, admin API, portal, analytics) that lets you declare routes/policies/consumers and observe what's happening, without redeploying the data plane.

> **Data plane vs control plane (terms):** The **data plane** is the part that actually handles live request traffic (the proxy doing the work, on the hot path of every request). The **control plane** is the management layer that configures the data plane and collects telemetry — it is *not* on the request hot path. Keeping them separate is a core design principle: the control plane can be slow, eventually-consistent, and occasionally down without dropping live traffic. (See §3.)

### 1.5 The seven canonical gateway responsibilities (preview)

A gateway typically owns these (each expanded in §2 and §3):

1. **Routing** — match an incoming request to an upstream service/cluster.
2. **Authentication & authorization** — verify *who* the caller is (authn) and *what* they may do (authz).
3. **Rate limiting / throttling / quotas** — protect backends and enforce fairness and plans.
4. **Transformation** — rewrite paths, headers, bodies; translate protocols (REST↔gRPC, JSON↔XML).
5. **Aggregation / composition** — fan out to several backends and merge responses.
6. **TLS termination** — terminate HTTPS at the edge; optionally re-encrypt to upstreams.
7. **Caching** — cache upstream responses to cut latency and load.

Plus: load balancing, health checking, circuit breaking, retries, observability (logs/metrics/traces), and CORS.

> **The cardinal anti-pattern (flagged early, expanded in §6):** Do **not** put *business logic* in the gateway. The gateway is for *cross-cutting, request-shaping, policy* concerns. The moment domain rules ("a gold customer gets a 10% discount", "orders over $X need manager approval") live in gateway config or plugins, you have created a distributed monolith with logic split across an operationally fragile, hard-to-test edge component. Business logic belongs in services.

### 1.6 North-south vs east-west traffic

- **North-south traffic:** Traffic that crosses the boundary of your system — clients ↔ your services. "North" = into your data center from outside, "south" = back out. This is the gateway's home turf.
- **East-west traffic:** Traffic *between* services inside your system (service A calling service B). This is the service mesh's home turf.

The mental picture is a data-center diagram: external clients at the top (north), internal services laid out horizontally (east-west between them). A request enters from the north through the gateway, then fans out east-west among services, and the response goes back north.

| Dimension | North-south (gateway) | East-west (service mesh) |
|---|---|---|
| Who talks | External clients ↔ your services | Service ↔ service inside the cluster |
| Trust | Untrusted/partially trusted | Internal, but "zero trust" still applies |
| Primary tools | API gateway | Service mesh (Istio/Linkerd), or plain mTLS |
| Auth model | API keys, OAuth2/OIDC, JWT, mTLS for partners | mTLS identities (SPIFFE/SVID), service tokens |
| Volume/latency budget | Lower volume, higher per-call value, latency-tolerant | Very high volume, microsecond-sensitive |
| Caching/aggregation | Common | Rare |

> **mTLS (mutual TLS) (term):** TLS where *both* sides present certificates and verify each other, not just the server. Used to give each service a cryptographic identity for east-west auth.
>
> **Zero trust (term):** A security model that assumes the network is hostile even inside your perimeter; every request must be authenticated and authorized regardless of where it originates. East-west calls are *not* implicitly trusted just because they're "internal".
>
> **SPIFFE / SVID (terms):** SPIFFE (Secure Production Identity Framework For Everyone) is a standard for issuing cryptographic identities to workloads; an SVID (SPIFFE Verifiable Identity Document, often an X.509 cert or JWT) is the credential a workload presents. Common in service meshes for east-west identity.

---

## 2. Foundations from first principles

We build the gateway up from the simplest possible thing and add one capability at a time, defining each term as it appears.

### 2.1 Start with: a single backend and a client

A client (browser, mobile app, partner server) wants data. The simplest architecture: client → HTTP → one service. No gateway. The client hard-codes the service's address. This works until you have *more than one* service or *more than one* concern.

### 2.2 Add a reverse proxy (the seed of a gateway)

Put nginx (or HAProxy, or Envoy) in front of the service. Now:

- The client talks to one stable address; the backend can move.
- You can terminate TLS once at the proxy.
- You can do basic path-based routing: `/users/*` → user-service, `/orders/*` → order-service.

> **HAProxy, Envoy, nginx (tools):** Layer-4/Layer-7 proxies/load balancers. **nginx** — ubiquitous web server/reverse proxy. **HAProxy** — high-performance TCP/HTTP load balancer. **Envoy** — a modern L7 proxy built by Lyft, designed for dynamic configuration via a control plane (the xDS APIs); it's the data plane under Istio and many gateways. (See §4.7.)
>
> **Layer 4 vs Layer 7 (terms):** Refers to the OSI model. **L4** = transport layer (TCP/UDP); an L4 proxy routes by IP/port without reading the HTTP content. **L7** = application layer (HTTP, gRPC); an L7 proxy can read headers, paths, methods, and bodies, enabling content-based routing, header manipulation, and per-API policy. API gateways are L7.

This is already a "gateway" in the loosest sense. What turns a reverse proxy into an *API gateway* is the addition of API-aware policy: consumers, keys, quotas, auth schemes, transformations, and analytics.

### 2.3 Core domain objects of a gateway

Most gateways model these (names vary by vendor):

| Object | Meaning | Example |
|---|---|---|
| **Route** (a.k.a. API, resource) | A match condition on incoming requests (host + path + method + headers) | `GET host=api.acme.com path=/v1/orders/*` |
| **Service / Upstream** | The logical backend a route forwards to | `order-service` cluster |
| **Target / Endpoint** | A concrete backend instance (host:port) | `10.0.3.7:8080` |
| **Consumer / Client / App** | The identity making the request | "AcmeMobileApp", "PartnerBobCorp" |
| **Credential** | A secret bound to a consumer | API key `ak_live_…`, OAuth2 client, JWT issuer |
| **Plugin / Filter / Policy** | A unit of cross-cutting behavior attached to a route/service/consumer/global scope | `rate-limiting`, `jwt`, `cors` |
| **Plan / Product** | A bundle of routes + quota + price offered to consumers | "Free: 1k req/day", "Pro: 1M req/day" |

> **Why "consumer" matters:** Rate limits, quotas, analytics, and billing are almost always *per-consumer*, not per-IP. The gateway must identify the consumer (via key/JWT/mTLS) *before* it can enforce per-consumer policy. That's why authentication runs early in the pipeline.

### 2.4 The pipeline (filter chain) model

The defining internal structure of a gateway is an **ordered pipeline of stages** applied to each request (and, in reverse, to each response). Conceptually:

```
[ TLS termination ]
        ↓
[ Route matching ]
        ↓
[ Request transforms / header normalization ]
        ↓
[ Authentication ]      ← identify the consumer
        ↓
[ Authorization ]       ← check scopes/permissions
        ↓
[ Rate limiting / quota ]   ← per-consumer enforcement
        ↓
[ Caching (lookup) ]    ← short-circuit if cache hit
        ↓
[ Load balancing → upstream call ] ───→ backend
        ↓                                  │
[ Caching (store) ] ←──────────────────────┘
        ↓
[ Response transforms ]
        ↓
[ Metrics / logging / tracing ]
        ↓
   client
```

The ordering is not arbitrary and is a frequent source of bugs (see §9). Authentication must precede per-consumer rate limiting; cache lookup should usually be after auth (so you don't serve cached private data to the wrong consumer) but before the upstream call; transformation order matters when one plugin's output is another's input.

> **Short-circuit (term):** When a pipeline stage produces a response without calling the upstream — e.g. a cache hit returns immediately, a rate-limit rejection returns `429` immediately, an auth failure returns `401`. Short-circuiting saves backend load and latency.

### 2.5 The seven responsibilities, defined

**Routing.** Matching a request to a backend. Match keys: host (`Host` header / SNI), path (prefix, exact, regex), HTTP method, headers, query params, and sometimes weight (for canaries). Output: the chosen upstream cluster and the (possibly rewritten) upstream path.

> **SNI (term):** Server Name Indication, a TLS extension where the client states which hostname it wants during the TLS handshake, so a single IP/port serving many domains can present the right certificate. Gateways use SNI to route HTTPS by hostname.

**Authentication (authn).** Verifying *who* the caller is. Schemes: API key, Basic auth, OAuth2 access tokens, OpenID Connect (OIDC) ID tokens, JWT validation, HMAC-signed requests, mTLS client certs.

> **OAuth2 (term):** A delegation framework where a client obtains an **access token** (often a JWT) from an authorization server and presents it to APIs. The API never sees the user's password.
>
> **OIDC (term):** OpenID Connect, an identity layer on top of OAuth2 that adds an **ID token** (a JWT describing *who* the user is). OAuth2 = "what you can access"; OIDC = "who you are".
>
> **JWT (term):** JSON Web Token — a base64url-encoded, signed (and optionally encrypted) token of the form `header.payload.signature`. The payload ("claims") carries identity and scopes. Gateways validate the signature (via the issuer's public key, often fetched from a **JWKS** endpoint) and the claims (`exp`, `aud`, `iss`).
>
> **JWKS (term):** JSON Web Key Set — a JSON document published by an identity provider at a well-known URL listing the public keys used to verify JWT signatures. Gateways fetch and cache it.
>
> **HMAC (term):** Hash-based Message Authentication Code — the client signs the request with a shared secret; the server recomputes the signature to verify integrity and authenticity. Used by some partner APIs (e.g. AWS SigV4 is HMAC-based).

**Authorization (authz).** Verifying *what* the authenticated caller may do — scopes, roles, ABAC/RBAC rules, ownership checks. Gateways do *coarse-grained* authz (does this token have the `orders:read` scope?); *fine-grained* authz ("can user 42 read order 99?") usually belongs in the service.

> **RBAC / ABAC (terms):** Role-Based and Attribute-Based Access Control. RBAC grants permissions to roles, roles to users. ABAC decides based on attributes (user dept, resource owner, time of day) via policies.

**Rate limiting / throttling / quotas.** Limiting request rate to protect backends and enforce fairness/plans.
- **Rate limit:** requests per unit time (e.g. 100 req/s).
- **Quota:** a longer-window cap (e.g. 1M req/month) tied to a plan.
- **Throttling:** slowing/delaying rather than rejecting.
- **Spike arrest:** smoothing bursts (e.g. no more than 1 req per 50ms).
Algorithms: fixed window, sliding window log, sliding window counter, token bucket, leaky bucket (see §7.2).

**Transformation.** Rewriting requests/responses: path rewrite, header add/remove/rename, body mapping, content negotiation, protocol translation (REST→gRPC, JSON→XML), and version adaptation (present a stable v1 API while the backend changed).

**Aggregation / composition.** One client request → multiple backend calls → one merged response. Reduces client round-trips. Closely related to the **BFF** pattern (§8).

**TLS termination.** Decrypting HTTPS at the gateway so internal services can speak plaintext HTTP (inside a trusted network) or re-encrypted TLS (zero trust). The gateway holds the public certificate and private key, handles cipher negotiation, OCSP stapling, and SNI.

> **TLS termination vs passthrough vs re-encryption (terms):** *Termination* — gateway decrypts and reads the request (needed for L7 policy). *Passthrough* — gateway forwards encrypted bytes without decrypting (L4 only; gateway can't read content). *Re-encryption (TLS bridging)* — gateway terminates the client TLS, then opens a *new* TLS connection to the upstream.

**Caching.** Storing upstream responses keyed by request attributes to serve future identical requests without hitting the backend. Honors HTTP cache semantics (`Cache-Control`, `ETag`, `Vary`) and supports gateway-level TTL overrides.

> **ETag / Cache-Control / Vary (terms):** HTTP caching headers. `Cache-Control` states cacheability and max age. `ETag` is a version tag for a resource enabling conditional requests (`If-None-Match` → `304 Not Modified`). `Vary` tells caches which request headers (e.g. `Accept-Language`) make responses distinct.

### 2.6 The API management lifecycle (from first principles)

Running APIs *as a product* is a lifecycle. Each stage:

1. **Design** — define the contract first, ideally in **OpenAPI/Swagger** (REST) or `.proto` (gRPC) or GraphQL SDL. Design-first means the contract is agreed before code.
2. **Mock** — stand up a fake server that returns example responses conforming to the contract, so client teams can build in parallel before the backend exists.
3. **Implement & validate** — build the backend; validate requests/responses against the schema; run contract tests.
4. **Publish** — deploy routes/policies to the gateway and expose them through environments (dev/staging/prod).
5. **Developer portal** — a website where developers discover APIs, read docs, try calls ("try it" console), and self-serve credentials.
6. **Keys & credentials** — issue, scope, rotate, and revoke API keys / OAuth clients per consumer/app.
7. **Analytics** — measure traffic, latency, errors, top consumers, top endpoints; feed capacity planning and product decisions.
8. **Monetization** — package APIs into plans/products with quotas and pricing; meter usage; integrate billing.
9. **Versioning & deprecation** — introduce new versions, communicate deprecation, sunset old ones gracefully.
10. **Retire** — remove the API and clean up.

> **OpenAPI / Swagger (terms):** OpenAPI Specification (OAS) is a standard, machine-readable description of a REST API (paths, params, schemas, responses). "Swagger" was the original name and now refers to the tooling (Swagger UI, Swagger Editor). A single OpenAPI file drives docs, mocks, client SDK generation, request validation, and gateway config import.
>
> **Contract testing (term):** Tests that verify a service honors the agreed API contract (e.g. Pact, Spring Cloud Contract), catching breaking changes before deploy.
>
> **GraphQL SDL (term):** Schema Definition Language for GraphQL, a query language where the client specifies exactly which fields it wants from a single endpoint; a GraphQL gateway/router can federate multiple services.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full life of a request through a gateway, then cover the control plane, configuration propagation, and state machines.

### 3.1 Architectural split: data plane and control plane

A modern gateway is two cooperating subsystems:

- **Data plane (proxy):** On the hot path. Accepts connections, runs the filter chain, talks to upstreams. Must be fast, low-allocation, and resilient. Often written in C/C++ (Envoy), Lua-on-nginx (Kong), or the JVM with non-blocking I/O (Spring Cloud Gateway on Netty).
- **Control plane (management):** Off the hot path. Stores configuration (routes, plugins, consumers), exposes an admin API and/or declarative config, pushes config to data-plane nodes, aggregates analytics, and serves the developer portal.

> **Why split them:** The data plane must keep serving traffic even if the control plane is down or slow. Config changes are *eventually consistent*: you declare a new route, the control plane validates and stores it, then propagates it to data-plane nodes within seconds. The data plane caches its config locally so it never blocks on the control plane per-request.
>
> **Eventually consistent (term):** A consistency model where, after an update, different nodes may briefly disagree, but all converge to the same value if no new updates occur. Config propagation in gateways is eventually consistent — acceptable because a few seconds of staleness for a route change is fine, whereas per-request consistency would be a performance disaster.

Envoy formalizes this split with the **xDS** protocol family.

> **xDS (term):** A set of gRPC/REST APIs Envoy uses to fetch dynamic config from a control plane: **LDS** (Listeners), **RDS** (Routes), **CDS** (Clusters/upstreams), **EDS** (Endpoints/instances), **SDS** (Secrets/certs). The control plane (e.g. Istio's istiod, or a custom one) implements xDS; Envoy subscribes and updates live without restart.

### 3.2 Connection lifecycle (event-loop internals)

High-throughput gateways use **non-blocking, event-loop I/O** rather than thread-per-request, because a gateway holds many concurrent, mostly-idle connections (waiting on slow clients and slow upstreams).

> **Event loop / non-blocking I/O / epoll (terms):** Instead of dedicating one OS thread per connection (expensive: ~1MB stack each, context-switch overhead), an event-loop model uses a small pool of threads that each watch many sockets via an OS multiplexing syscall (**epoll** on Linux, **kqueue** on BSD/macOS, **IOCP** on Windows). The thread sleeps until the kernel reports a socket is readable/writable, then processes that event and moves on. This lets one thread handle tens of thousands of connections. Netty (JVM), nginx, and Envoy all use this model.
>
> **C10k / C10M problem (term):** The historical challenge of handling 10,000 (then 10 million) concurrent connections on one machine — the reason event-loop architectures replaced thread-per-connection for proxies.

Lifecycle of one connection:

1. **Accept.** The listener socket (bound to e.g. `:443`) accepts a TCP connection. The OS hands back a connection socket; the event loop registers it for read events.
2. **TLS handshake** (if HTTPS). The gateway reads SNI, selects the cert, negotiates cipher suite, completes the handshake (and verifies client cert if mTLS). This is CPU-intensive (asymmetric crypto); gateways use session resumption and hardware/AES-NI acceleration.
3. **Read & parse request.** Read bytes until headers are complete; parse the request line and headers. For HTTP/2 and HTTP/3, demultiplex streams over one connection.
4. **Dispatch to the filter chain** (§3.3).
5. **Keep-alive.** After the response, the connection may be reused for another request (HTTP keep-alive), avoiding repeat handshakes.

> **HTTP/2 and HTTP/3 (terms):** HTTP/2 multiplexes many concurrent request/response streams over a single TCP connection (eliminating head-of-line blocking at the HTTP layer) and uses header compression (HPACK). HTTP/3 runs over **QUIC** (a UDP-based transport with built-in TLS 1.3 and stream multiplexing), eliminating TCP head-of-line blocking too. Gateways increasingly terminate H2/H3 from clients and may downgrade to H1 toward older upstreams.

### 3.3 The filter chain in detail (step-by-step)

Once a request is parsed, the gateway runs its ordered filter chain. Using a concrete Kong-style / Envoy-style model, here is what happens per request, in order. Each step can **short-circuit** (return a response immediately) or **continue**.

**Step 1 — Route matching.**
The gateway evaluates the request against its route table. Matching dimensions: host, path, method, headers, query. Routes have **priority/specificity** (exact path beats prefix beats regex; more constraints beat fewer). The router is typically a compiled trie or radix tree over paths for O(path length) matching, plus per-host buckets.

> **Radix tree / trie (term):** A tree data structure for prefix matching. Path segments form tree edges; routing becomes a tree walk proportional to path length, not to the number of routes. This is why a gateway with 50,000 routes can still match in microseconds.

If no route matches → `404` (short-circuit). If matched, the route names the upstream service and any route-scoped plugins.

**Step 2 — Plugin/filter resolution & ordering.**
The gateway computes the effective plugin set by merging plugins attached at four scopes: **global → service → route → consumer** (consumer-scoped plugins resolve after auth identifies the consumer). Each plugin declares a fixed **priority** that defines execution order (e.g. in Kong, `rate-limiting` has a numeric priority placing it after auth plugins).

**Step 3 — Request transformation (pre-auth normalization).**
Normalize headers, strip hop-by-hop headers, add `X-Forwarded-For`/`X-Forwarded-Proto`, optionally rewrite the path for the upstream.

> **X-Forwarded-For / X-Forwarded-Proto / Forwarded (terms):** Headers the proxy adds so the upstream knows the original client IP and scheme (since from the upstream's view, the connection came from the gateway). `Forwarded` (RFC 7239) is the standardized successor. Trusting these headers blindly from untrusted clients is a security hole — strip/overwrite them at the edge.
>
> **Hop-by-hop headers (term):** Headers meaningful only for a single transport hop (e.g. `Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`) and must not be forwarded by proxies, per RFC 7230. Mishandling them causes request-smuggling bugs.

**Step 4 — Authentication.**
Run the configured authn plugin(s):
- *API key:* extract key from header/query, look it up (in a local cache backed by the config store) → resolve consumer. Miss → `401`.
- *JWT:* parse token, fetch issuer's public key from cached JWKS, verify signature, check `exp`/`nbf`/`aud`/`iss`. Invalid → `401`.
- *OAuth2 introspection:* call the auth server's introspection endpoint (or validate JWT locally) to confirm the token is active.
- *mTLS:* the client cert from the handshake is mapped to a consumer.

The authenticated **consumer identity** is now attached to the request context; later plugins and the upstream can use it (often injected as a header like `X-Consumer-ID`).

> **Token introspection (term):** An OAuth2 endpoint (RFC 7662) where the gateway sends an opaque access token and the auth server returns whether it's active plus its scopes. Used for *opaque* tokens (random strings); JWTs can be validated locally without a network call, which is faster but harder to revoke instantly.

**Step 5 — Authorization.**
Check scopes/roles/claims against the route's requirements (`orders:read`). Fail → `403`. Optionally call an external policy engine (OPA) for ABAC.

> **OPA / Rego (terms):** Open Policy Agent — a general-purpose policy engine; you write authorization rules in its **Rego** language, and the gateway queries OPA with the request context to get an allow/deny decision. Decouples policy from the gateway and services.

**Step 6 — Rate limiting / quota.**
Now that the consumer is known, enforce per-consumer (and/or per-route, per-IP, global) limits. The gateway increments a counter in a shared store (Redis, or a gossip-replicated local counter) and compares to the limit. Over limit → `429 Too Many Requests` with `Retry-After` and `RateLimit-*` headers (short-circuit).

> **Why a shared store:** With N gateway nodes behind a load balancer, a purely local counter would allow N× the intended limit. A central store (Redis) gives a global count at the cost of a network round-trip per request; alternatives trade accuracy for speed (local counters synced periodically, or sliding-window approximations). (See §7.2.)
>
> **429 / Retry-After / RateLimit headers (terms):** `429 Too Many Requests` is the standard rate-limit rejection. `Retry-After` tells the client how long to wait. The IETF `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers communicate quota state to well-behaved clients.

**Step 7 — Cache lookup.**
Compute a cache key (method + path + query + selected `Vary` headers + consumer, if private). On hit and fresh → return cached response (short-circuit). On stale → revalidate with the upstream via `If-None-Match`.

**Step 8 — Upstream selection & load balancing.**
Choose a healthy target from the upstream's pool using a load-balancing algorithm (round-robin, least-connections, consistent-hashing, etc.; §7.4). **Health checks** (active probes and/or passive circuit-breaker observations) keep unhealthy targets out of rotation.

> **Circuit breaker (term):** A resilience pattern: after a threshold of failures to an upstream, the breaker "opens" and the gateway fails fast (returns an error or fallback immediately) instead of hammering a sick backend, giving it time to recover; after a cooldown it "half-opens" to test, then "closes" on success. States: closed → open → half-open → closed.
>
> **Outlier detection (term, Envoy):** Passive health checking — Envoy ejects a host from the pool when it returns too many consecutive 5xx errors or timeouts, then gradually re-admits it.

**Step 9 — Upstream request, with retries/timeouts.**
Open (or reuse a pooled) connection to the target; send the (transformed) request; apply a per-try **timeout**; on retriable failures (connection error, 502/503, idempotent methods) **retry** up to a budget on a *different* host. Apply an overall request **deadline**.

> **Retry budget / idempotency (terms):** Retries can amplify load (a "retry storm") and double-execute non-idempotent operations. Only retry **idempotent** requests (GET, PUT, DELETE — same effect if repeated) or those carrying an idempotency key; cap retries with a *budget* (e.g. ≤10% of requests may be retries) to prevent storms.

**Step 10 — Response processing (reverse pipeline).**
As the response streams back: response transformation (header rewrite, body mapping), CORS headers, cache store (if cacheable), security headers (`Strict-Transport-Security`, etc.).

**Step 11 — Observability emission.**
Emit access log, metrics (latency histograms, status-code counters, by route/consumer), and a distributed trace span. Then stream the response to the client.

> **Distributed tracing / span / trace context (terms):** A trace follows one request across services; each hop is a **span**. The gateway is usually the *root* span and **injects/propagates** trace headers (W3C `traceparent`, or B3) so downstream services attach their spans to the same trace. Tools: OpenTelemetry, Jaeger, Zipkin.

### 3.4 Configuration propagation & state machine

How a route you declare reaches the data plane:

1. **Declare.** You POST to the admin API or apply declarative config (YAML / `kubectl apply` of a CRD).
2. **Validate.** Control plane validates schema, references (does the upstream exist?), and conflicts.
3. **Persist.** Stored in the config DB (Postgres for Kong's DB mode, etcd, or a Git repo for GitOps).
4. **Compile.** Control plane compiles into the data-plane's internal representation (Envoy snapshot, Kong's in-memory router).
5. **Distribute.** Pushed (xDS streaming) or pulled (polling) to all data-plane nodes.
6. **Hot-swap.** Each node atomically swaps the new config in (versioned snapshots) so in-flight requests aren't disrupted; new requests use the new config.
7. **Drain.** Removed routes/clusters are drained: existing connections finish, no new ones routed.

> **CRD (term):** Custom Resource Definition — a way to extend Kubernetes with your own object types. Gateways on Kubernetes expose routes/policies as CRDs (or the standard Gateway API resources) so you manage them with `kubectl` and GitOps.
>
> **GitOps (term):** Managing infrastructure/config declaratively in Git as the single source of truth; an agent (ArgoCD/Flux) continuously reconciles the cluster to match Git. Gateway config-as-code fits naturally.

The **route lifecycle state machine** (simplified): `Declared → Validated → Stored → Compiled → Distributed → Active → (Updated | Drained → Removed)`. A failed validation never reaches Active.

### 3.5 Where state lives (and the stateless ideal)

Data-plane nodes should be **stateless** w.r.t. user requests so they scale horizontally and any node can serve any request. State that *does* exist:
- **Config** — cached locally, sourced from control plane. (Recoverable, not user state.)
- **Rate-limit counters** — externalized to Redis or replicated, *not* node-local, else limits break behind a load balancer.
- **Cache** — node-local (fast, but low hit rate across nodes) or shared (Redis, higher hit rate, network cost).
- **Sessions/sticky** — avoid; if needed, use consistent hashing rather than node memory.

> **Stateless (term):** A server is stateless if it stores no client-session data between requests; any instance can handle any request. Statelessness is what lets you run many gateway replicas behind a load balancer and add/remove them freely — essential for HA (§7.5).

---

## 4. The complete toolkit

This section enumerates the gateway feature toolkit, then the major products and their specific knobs. Vendor- and version-specific items are flagged.

### 4.1 Generic policy/plugin toolkit (vendor-neutral)

| Capability | What it does | Key parameters | Typical defaults |
|---|---|---|---|
| Routing | Match request → upstream | host, paths, methods, headers, strip-path, preserve-host | strip-path often `true` |
| TLS termination | Terminate HTTPS | cert/key, min TLS version, ciphers, SNI, mTLS (`verify`/`optional`/`off`) | TLS 1.2 min (1.3 preferred) |
| API key auth | Key-based authn | key location (header/query), key names, hide-credentials | header `apikey`/`X-API-Key` |
| JWT auth | Verify JWT | issuer, JWKS URL, audience, allowed algs, clock skew | skew ~30–60s; RS256 |
| OAuth2/OIDC | Token validation/introspection | issuer, introspection URL, scopes, token location | bearer header |
| Rate limiting | Req/time window | limit, window (s/m/h/d), policy (local/cluster/redis), identifier (consumer/IP) | per-consumer |
| Request transformer | Mutate request | add/remove/rename/replace headers, query, body | — |
| Response transformer | Mutate response | add/remove/rename headers, body | — |
| CORS | Cross-origin headers | allowed origins/methods/headers, credentials, max-age, preflight | — |
| Caching | Cache responses | TTL, cache key, cacheable methods/status, storage | GET/200, short TTL |
| Request size limiting | Reject huge bodies | max body size | e.g. 10MB |
| Bot/IP control | Allow/deny lists | CIDR allow/deny | — |
| Circuit breaking | Fail fast on sick upstream | max failures, window, cooldown, half-open trials | — |
| Retries | Retry failed upstream calls | retry-on conditions, max retries, per-try timeout, budget | idempotent only |
| Load balancing | Spread across targets | algorithm, health checks | round-robin |
| Aggregation | Fan-out + merge | (usually custom/serverless) | — |
| gRPC/Protocol translation | REST↔gRPC, gRPC-Web | proto descriptors | — |
| Observability | Logs/metrics/traces | log format, metrics backend, trace sampler | — |
| Request validation | Validate vs OpenAPI/JSON schema | schema, validate body/params | — |

### 4.2 HTTP status codes the gateway itself emits

| Code | When the gateway returns it |
|---|---|
| `400` | Malformed request the gateway can't parse/validate |
| `401` | Authentication missing/invalid |
| `403` | Authenticated but not authorized (scope/IP/quota-tier) |
| `404` | No route matched |
| `405` | Method not allowed on a matched route |
| `408` / `499` | Client timed out (`499` is nginx-specific: client closed) |
| `413` | Payload too large |
| `426` | Upgrade required (e.g. TLS) |
| `429` | Rate limit / quota exceeded |
| `431` | Request header fields too large |
| `502` | Upstream returned an invalid response / connection error |
| `503` | No healthy upstream / circuit open / overloaded |
| `504` | Upstream timed out |

> Knowing whether a `502/503/504` came from the *gateway* vs the *upstream* is a key debugging skill (§9). Gateways usually add a header (e.g. `Via`, or a custom `X-Kong-*`) and emit distinct metrics.

### 4.3 Kong (open-source + Enterprise) — vendor-specific

Kong is an nginx + OpenResty (Lua) based gateway with a plugin architecture. Two operation modes:
- **Traditional (DB) mode:** Postgres-backed; config via Admin API; multiple nodes share the DB. (Cassandra support was removed in Kong 3.4.)
- **DB-less / declarative mode:** Config from a single YAML file loaded into memory; immutable at runtime; great for GitOps/Kubernetes.
- **Hybrid mode:** Separate **control plane (CP)** node(s) holding the DB and **data plane (DP)** nodes that receive config over a secure channel — combines central management with stateless DP nodes.

Key Admin API objects (REST under `:8001` by default): `services`, `routes`, `upstreams`, `targets`, `consumers`, `plugins`, `certificates`, `key-auth`/`jwt`/`acl` credentials.

Selected built-in plugins and notable parameters:

| Plugin | Notable params (defaults) |
|---|---|
| `key-auth` | `key_names` (`["apikey"]`), `hide_credentials` (`false`), `key_in_header/query/body` (true/true/false) |
| `jwt` | `claims_to_verify` (e.g. `exp`), `key_claim_name` (`iss`), `maximum_expiration` |
| `rate-limiting` | `second/minute/hour/day/month/year`, `policy` (`local` default; `cluster`; `redis`), `limit_by` (`consumer` default, `ip`, `credential`, `service`), `fault_tolerant` (`true`) |
| `rate-limiting-advanced` (Enterprise) | sliding window, multiple windows, Redis, `namespace` |
| `proxy-cache` | `response_code` (`[200,301,404]`), `request_method` (`[GET,HEAD]`), `cache_ttl` (300s), `strategy` (`memory`) |
| `cors` | `origins`, `methods`, `headers`, `credentials`, `max_age` |
| `request-transformer` / `response-transformer` | `add/remove/rename/replace` for headers/querystring/body |
| `acl` | `allow`/`deny` consumer groups |
| `oauth2` | `enable_*_grant`, `token_expiration` (7200s) |
| `prometheus`, `zipkin`, `opentelemetry`, `datadog`, `statsd` | observability exporters |

> **OpenResty (term):** A bundle of nginx plus the LuaJIT runtime and modules that let you script nginx's request handling in Lua. Kong's plugins are Lua code running inside nginx's event loop — fast, but CPU-bound work in a plugin can stall the loop.

Ports (defaults): `:8000` proxy (HTTP), `:8443` proxy (HTTPS), `:8001` Admin API, `:8444` Admin API HTTPS, `:8002` Kong Manager (Enterprise GUI). Plugin execution order is governed by each plugin's `PRIORITY` constant.

### 4.4 AWS API Gateway — vendor-specific

A fully managed, serverless gateway. Three API types:
- **REST API** — feature-rich (request/response mapping templates via **VTL**, API keys + usage plans, caching, WAF, request validation, canary deploys). Higher latency/cost.
- **HTTP API** — newer, cheaper (~70% lower price), lower latency, fewer features (JWT authorizers, simpler). Prefer for most new HTTP/Lambda work.
- **WebSocket API** — bidirectional.

Core concepts: **Stages** (deployment environments: `dev`, `prod`), **Stage variables**, **Resources/Methods**, **Integrations** (Lambda, HTTP, AWS service, mock), **Authorizers** (Lambda/`REQUEST`/`TOKEN`, Cognito, JWT), **Usage plans** + **API keys** (throttling + quota), **Mapping templates** (VTL), **Caching** (REST only; 0.5GB–237GB; TTL default 300s, 0–3600s), **Throttling** (account default historically 10,000 req/s, 5,000 burst — confirm current limits; per-method and per-key overrides via usage plans). Integrates with **CloudWatch** (metrics/logs), **X-Ray** (tracing), **WAF** (firewall), **Cognito** (user pools), **Route 53/ACM** (custom domains/certs).

> **VTL (term):** Velocity Template Language — the templating language AWS API Gateway uses in mapping templates to transform requests/responses (e.g. reshape JSON, inject context). Powerful but awkward to test; a frequent source of subtle bugs and a mild case of the "logic in the gateway" anti-pattern.
>
> **Lambda authorizer (term):** A Lambda function the gateway calls to authorize a request; it returns an IAM policy (allow/deny) and optional context. The decision is cached by token/identity for a TTL.
>
> **WAF (term):** Web Application Firewall — inspects requests for attack patterns (SQLi, XSS), enforces IP/rate rules. AWS WAF attaches to API Gateway/CloudFront.

Notable limits (confirm current values): integration timeout max **29 seconds** (historically; raised in some configs — verify), payload size 10MB, max 10,000 RPS default account throttle.

### 4.5 Google Apigee — vendor-specific

A full **API management platform** (not just a gateway), strong on the *management/monetization* side. Concepts:
- **API proxies** — the deployed unit; each has a **ProxyEndpoint** (client-facing) and **TargetEndpoint** (backend), connected by **flows**.
- **Policies** — ~50+ XML-configured policies attached to flow steps: `VerifyAPIKey`, `OAuthV2`, `Quota`, `SpikeArrest`, `ResponseCache`, `JSONToXML`/`XMLToJSON`, `AssignMessage`, `JavaScript`/`JavaCallout`, `ServiceCallout`, `MessageLogging`.
- **Quota vs SpikeArrest:** `Quota` counts requests over a window (business plan limit); `SpikeArrest` smooths instantaneous bursts (protection), e.g. `30ps` becomes ~1 per 33ms.
- **API products** — bundles of proxies + quota exposed to developers.
- **Developer / Developer app** — registered consumers; apps get keys.
- **Monetization** — rate plans, billing.
- **Environments** (test/prod), **Analytics**, **Developer portal** (integrated, Drupal-based historically; newer integrated portal).
- Editions: Apigee Edge (legacy), Apigee X / hybrid (runtime in your GKE cluster, management in Google Cloud).

> Apigee leans heavily on policies and even code callouts (JavaScript/Java), which makes it *easy* to slip business logic into the proxy — guard against this.

### 4.6 Spring Cloud Gateway (SCG) — vendor/library-specific (Java/JVM)

A **library/framework**, not a managed product — you embed it in a Spring Boot app. Built on **Project Reactor** and **Netty** (reactive, non-blocking). This is the gateway most relevant to the Java reader who wants to *build* one.

Core abstractions:
- **Route** — `id`, `uri` (target), `predicates`, `filters`, `order`.
- **Predicate** — match condition: `Path`, `Host`, `Method`, `Header`, `Query`, `Cookie`, `After`/`Before`/`Between` (time), `RemoteAddr`, `Weight` (canary).
- **GatewayFilter** — per-route filters: `AddRequestHeader`, `RewritePath`, `StripPrefix`, `RequestRateLimiter`, `CircuitBreaker`, `Retry`, `RemoveResponseHeader`, `SetStatus`, `RedirectTo`, `PreserveHostHeader`, `RequestSize`, `ModifyRequestBody`/`ModifyResponseBody`, `TokenRelay` (OAuth2), `SaveSession`.
- **GlobalFilter** — applies to all routes (e.g. `NettyRoutingFilter` does the actual upstream call; `LoadBalancerClientFilter`/`ReactiveLoadBalancerClientFilter` resolves `lb://service-id` via service discovery; `ForwardRoutingFilter`).
- Config via `application.yml` *or* a fluent Java `RouteLocator` bean.

> **Project Reactor / Mono / Flux (terms):** Reactor is a reactive-streams library for the JVM. `Mono<T>` = 0/1 async value, `Flux<T>` = 0..N async stream. SCG composes filters as reactive chains so a small Netty event-loop pool handles many concurrent requests without blocking threads. **Never block** (no JDBC, no `RestTemplate`, no `Thread.sleep`) inside a reactive filter — it stalls the event loop. Use `WebClient` for outbound HTTP.
>
> **Netty (term):** A JVM non-blocking I/O framework (event loops over epoll/kqueue). SCG and WebFlux run on it.

Built-in `RequestRateLimiter` uses a **Redis** token-bucket by default with params `redis-rate-limiter.replenishRate`, `.burstCapacity`, `.requestedTokens`, and a `KeyResolver` bean (e.g. by user/principal/IP).

> *Note:* "Spring Cloud Gateway MVC" (a servlet/blocking variant) also exists for teams not on WebFlux; the reactive variant is the original and most common.

### 4.7 Envoy & emerging standards

- **Envoy** — the L7 proxy data plane underneath many gateways/meshes; configured via static YAML or dynamic xDS. Concepts: **listeners → filter chains → HTTP connection manager → route config → clusters → endpoints**.
- **Istio / Linkerd** — service meshes (east-west) with an **ingress gateway** for north-south.
- **Kubernetes Gateway API** — the standard, role-oriented successor to Ingress: `GatewayClass`, `Gateway`, `HTTPRoute`/`GRPCRoute`/`TCPRoute`. Implemented by Envoy Gateway, Istio, Kong, etc. Prefer it over the older `Ingress` for new clusters.
- **Tyk, KrakenD, Gloo, Ambassador/Emissary, Zuul** — other notable gateways. **Zuul** (Netflix) is the Java predecessor to SCG; Zuul 1 was blocking, Zuul 2 became non-blocking (Netty).
- **KrakenD** — stateless, declarative, optimized for *aggregation* (its core strength).

---

## 5. Code examples by use case

Idiomatic, copy-adaptable examples across genuinely different scenarios. Java-first where relevant.

### 5.1 Spring Cloud Gateway: routing, rewrite, rate limiting, circuit breaker (YAML)

```yaml
# application.yml — a production-shaped SCG config
spring:
  cloud:
    gateway:
      default-filters:
        # Add a correlation id to every request for tracing
        - AddRequestHeader=X-Request-Source, edge-gateway
      routes:
        - id: orders-service
          uri: lb://order-service          # 'lb://' = resolve via service discovery + load balance
          predicates:
            - Path=/api/v1/orders/**        # match condition
            - Method=GET,POST
          filters:
            - StripPrefix=2                 # drop '/api/v1' so upstream sees '/orders/**'
            - name: RequestRateLimiter      # per-consumer token bucket in Redis
              args:
                redis-rate-limiter.replenishRate: 50     # tokens added/sec (steady rate)
                redis-rate-limiter.burstCapacity: 100    # max burst
                redis-rate-limiter.requestedTokens: 1
                key-resolver: "#{@userKeyResolver}"      # bean deciding the limit key
            - name: CircuitBreaker          # fail fast if order-service is sick
              args:
                name: ordersCB
                fallbackUri: forward:/fallback/orders    # served by a local controller
            - name: Retry
              args:
                retries: 2
                methods: GET                # only retry idempotent methods!
                statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE
                backoff:
                  firstBackoff: 50ms
                  maxBackoff: 500ms
                  factor: 2
  data:
    redis:
      host: redis
      port: 6379

resilience4j.circuitbreaker:
  instances:
    ordersCB:
      slidingWindowSize: 20
      failureRateThreshold: 50          # open if >=50% of last 20 calls fail
      waitDurationInOpenState: 10s
      permittedNumberOfCallsInHalfOpenState: 3
```

```java
// KeyResolver bean: rate-limit per authenticated user, falling back to client IP.
@Configuration
public class RateLimitConfig {
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
            .map(Principal::getName)                          // per-user when authenticated
            .switchIfEmpty(Mono.fromSupplier(() ->            // else per source IP
                Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(addr -> addr.getAddress().getHostAddress())
                        .orElse("unknown")))
            .map(key -> "rl:" + key);                         // namespaced Redis key
    }
}
```

```java
// Fallback controller for the open circuit (returns a degraded but valid response).
@RestController
class FallbackController {
    @RequestMapping("/fallback/orders")
    Mono<ResponseEntity<Map<String,Object>>> ordersFallback() {
        return Mono.just(ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "10")
            .body(Map.of("error", "orders temporarily unavailable", "degraded", true)));
    }
}
```

Why it matters: `lb://` decouples the route from concrete hosts; `StripPrefix` is path transformation; the rate limiter is *per consumer* via the `KeyResolver`; retries are *GET-only* to preserve correctness; the circuit breaker gives a graceful fallback instead of cascading failure.

### 5.2 Spring Cloud Gateway: programmatic routes + JWT relay (Java DSL)

```java
@Configuration
public class GatewayRoutes {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
            // Canary: send 10% of traffic to v2 of the catalog service
            .route("catalog-v2-canary", r -> r
                .path("/api/catalog/**")
                .and().weight("catalog", 10)               // 10% weight
                .filters(f -> f.stripPrefix(1)
                               .addRequestHeader("X-Version", "v2"))
                .uri("lb://catalog-service-v2"))
            .route("catalog-v1-stable", r -> r
                .path("/api/catalog/**")
                .and().weight("catalog", 90)               // 90% weight
                .filters(f -> f.stripPrefix(1))
                .uri("lb://catalog-service-v1"))
            // Relay the user's OAuth2 token downstream (token relay)
            .route("profile", r -> r
                .path("/api/profile/**")
                .filters(f -> f.tokenRelay()               // forwards the bearer token
                               .stripPrefix(1))
                .uri("lb://profile-service"))
            .build();
    }
}
```

```yaml
# Securing the gateway itself as an OAuth2 resource server (validates incoming JWTs).
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.acme.com/realms/acme   # JWKS auto-discovered
```

This shows weighted canary routing (gradual rollout) and `tokenRelay()` (the gateway authenticates the user once, then forwards the token so services don't re-authenticate the end user).

### 5.3 Backend-for-Frontend (BFF) aggregation in Spring WebFlux

A BFF is a *thin, client-specific* API that aggregates several services. (Why a BFF and not the gateway — see §8.)

```java
@RestController
@RequestMapping("/bff/mobile")
public class MobileHomeBff {

    private final WebClient web;     // non-blocking HTTP client
    public MobileHomeBff(WebClient.Builder b) { this.web = b.build(); }

    // One mobile call → fan out to 3 services → one merged, mobile-shaped payload.
    @GetMapping("/home")
    public Mono<HomeView> home(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();

        Mono<Profile> profile = get("http://profile-service/users/" + userId, Profile.class)
            .timeout(Duration.ofMillis(300))
            .onErrorReturn(Profile.empty());                 // degrade, don't fail the page

        Mono<List<Order>> orders = get("http://order-service/users/" + userId + "/recent", Order[].class)
            .map(Arrays::asList)
            .timeout(Duration.ofMillis(300))
            .onErrorReturn(List.of());

        Mono<List<Promo>> promos = get("http://promo-service/feed?aud=mobile", Promo[].class)
            .map(Arrays::asList)
            .timeout(Duration.ofMillis(200))
            .onErrorReturn(List.of());

        // zip = run concurrently, combine when all (or fallbacks) complete
        return Mono.zip(profile, orders, promos)
            .map(t -> new HomeView(t.getT1(), trimForMobile(t.getT2()), t.getT3()));
    }

    private <T> Mono<T> get(String url, Class<T> type) {
        return web.get().uri(url).retrieve().bodyToMono(type);
    }
    private List<Order> trimForMobile(List<Order> o) {
        return o.stream().limit(5).toList();                 // mobile shows fewer
    }
}
```

Key points: concurrent fan-out via `Mono.zip`; per-call timeouts and graceful degradation (`onErrorReturn`) so one slow service doesn't break the whole screen; payload shaped specifically for the mobile client. This is *aggregation* + *client-specific shaping* — appropriate in a BFF, not in a shared gateway.

### 5.4 Kong declarative config (DB-less) — auth + rate limit + key

```yaml
# kong.yaml — apply with: kong config db_import kong.yaml  (or via decK)
_format_version: "3.0"

services:
  - name: order-service
    url: http://order-service.internal:8080      # upstream
    routes:
      - name: orders-route
        paths: ["/v1/orders"]
        strip_path: false
        methods: ["GET", "POST"]
    plugins:
      - name: key-auth                            # require an API key
        config:
          key_names: ["X-API-Key"]
          hide_credentials: true                  # don't forward the key upstream
      - name: rate-limiting
        config:
          minute: 600                             # 600 req/min ...
          policy: redis                           # ...counted in Redis (cluster-wide)
          limit_by: consumer
          redis:
            host: redis.internal
            port: 6379
          fault_tolerant: true                    # if Redis is down, allow rather than block
      - name: proxy-cache
        config:
          response_code: [200]
          request_method: ["GET"]
          content_type: ["application/json"]
          cache_ttl: 30
          strategy: memory

consumers:
  - username: acme-mobile
    keyauth_credentials:
      - key: "ak_live_4f9c...redacted"            # the API key for this consumer
    plugins:
      - name: rate-limiting                       # per-consumer override: higher plan
        config:
          minute: 6000
          policy: redis
          redis: { host: redis.internal, port: 6379 }
```

Shows scoped plugins (service-level default, consumer-level override), Redis-backed cluster-wide rate limiting, `fault_tolerant` (fail-open on Redis outage), and edge caching.

> **decK (tool):** Kong's declarative configuration CLI — diff/sync your YAML against a running Kong (`deck gateway sync`), enabling GitOps for Kong.

### 5.5 AWS API Gateway (HTTP API) via SAM/CloudFormation — JWT auth + throttling

```yaml
# template.yaml (AWS SAM) — HTTP API with a JWT authorizer and Lambda integration
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31
Resources:
  OrdersApi:
    Type: AWS::Serverless::HttpApi
    Properties:
      StageName: prod
      Auth:
        DefaultAuthorizer: CognitoJwt
        Authorizers:
          CognitoJwt:
            IdentitySource: "$request.header.Authorization"
            JwtConfiguration:
              issuer: https://cognito-idp.us-east-1.amazonaws.com/us-east-1_abc123
              audience: ["my-app-client-id"]      # validates aud claim
      RouteSettings:                              # per-route throttling
        "GET /orders":
          ThrottlingBurstLimit: 200
          ThrottlingRateLimit: 100

  GetOrdersFn:
    Type: AWS::Serverless::Function
    Properties:
      Runtime: java21
      Handler: com.acme.GetOrdersHandler::handleRequest
      Events:
        GetOrders:
          Type: HttpApi
          Properties:
            ApiId: !Ref OrdersApi
            Path: /orders
            Method: GET
```

The gateway validates the Cognito-issued JWT (signature, `iss`, `aud`, `exp`) *before* invoking Lambda, and throttles per route. No business logic in the gateway — it authenticates and routes; the Lambda owns the domain logic.

### 5.6 Envoy: REST routing with rate limiting filter (YAML excerpt)

```yaml
static_resources:
  listeners:
  - address: { socket_address: { address: 0.0.0.0, port_value: 8443 } }
    filter_chains:
    - transport_socket:                         # TLS termination
        name: envoy.transport_sockets.tls
        typed_config:
          "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
          common_tls_context:
            tls_certificates:
            - certificate_chain: { filename: /certs/tls.crt }
              private_key:       { filename: /certs/tls.key }
      filters:
      - name: envoy.filters.network.http_connection_manager
        typed_config:
          "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
          stat_prefix: edge
          route_config:
            virtual_hosts:
            - name: acme
              domains: ["api.acme.com"]
              routes:
              - match: { prefix: "/v1/orders" }
                route:
                  cluster: order_service
                  timeout: 3s                    # upstream deadline
                  retry_policy:
                    retry_on: "5xx,connect-failure,refused-stream"
                    num_retries: 2
          http_filters:
          - name: envoy.filters.http.ratelimit   # talks to external RLS
            typed_config: { "@type": type.googleapis.com/envoy.extensions.filters.http.ratelimit.v3.RateLimit, domain: edge }
          - name: envoy.filters.http.router
  clusters:
  - name: order_service
    connect_timeout: 1s
    type: STRICT_DNS
    lb_policy: LEAST_REQUEST
    load_assignment:
      cluster_name: order_service
      endpoints:
      - lb_endpoints:
        - endpoint: { address: { socket_address: { address: order-service, port_value: 8080 } } }
    outlier_detection:                           # passive health check
      consecutive_5xx: 5
      base_ejection_time: 30s
```

Shows TLS termination, prefix routing, upstream timeout + retries, least-request load balancing, and passive outlier ejection.

### 5.7 Mocking from an OpenAPI spec (lifecycle: mock before backend exists)

```bash
# Use Prism to serve a mock from an OpenAPI file — clients can build against it today.
npm i -g @stoplight/prism-cli
prism mock openapi.yaml --port 4010
# Prism returns examples (or schema-generated data) for every defined operation.
# Validate that a client request conforms to the contract:
prism proxy openapi.yaml https://real-backend --errors   # proxies AND validates
```

```yaml
# openapi.yaml (excerpt) — the single source of truth for docs, mock, validation, SDKs
openapi: 3.0.3
info: { title: Orders API, version: 1.0.0 }
paths:
  /orders/{id}:
    get:
      operationId: getOrder
      parameters:
        - { name: id, in: path, required: true, schema: { type: string } }
      responses:
        "200":
          description: ok
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Order" }
              examples:
                sample: { value: { id: "o-1", total: 42.5, status: "PAID" } }
components:
  schemas:
    Order:
      type: object
      required: [id, total, status]
      properties:
        id:     { type: string }
        total:  { type: number }
        status: { type: string, enum: [NEW, PAID, SHIPPED] }
```

> **Prism (tool):** A Stoplight tool that turns an OpenAPI file into a live mock server and a validating proxy. Enables design-first parallel development and contract enforcement.

### 5.8 gRPC-to-REST transcoding (protocol translation use case)

Expose a gRPC backend as JSON/REST through Envoy's gRPC-JSON transcoder, so browser clients use REST while the service speaks gRPC.

```yaml
- name: envoy.filters.http.grpc_json_transcoder
  typed_config:
    "@type": type.googleapis.com/envoy.extensions.filters.http.grpc_json_transcoder.v3.GrpcJsonTranscoder
    proto_descriptor: /protos/orders.pb           # compiled descriptor set
    services: ["acme.orders.OrderService"]
    print_options: { add_whitespace: true, always_print_primitive_fields: true }
```

The gateway maps `GET /v1/orders/{id}` (REST) to the gRPC `GetOrder` method per `google.api.http` annotations in the proto — transformation/translation at the edge, leaving the service as pure gRPC.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Stay on the event loop; never block.** In SCG/WebFlux, blocking calls (JDBC, `RestTemplate`, file I/O, `Thread.sleep`) inside a filter stall an event-loop thread and collapse throughput. Use `WebClient`/reactive drivers. In Kong, heavy CPU work in a Lua plugin blocks the nginx worker.
- **Connection pooling & keep-alive to upstreams.** Reusing upstream connections avoids per-request TCP+TLS handshakes. Tune pool size, idle timeout, and max requests per connection.
- **Minimize per-request work.** Validate JWTs locally (cached JWKS) instead of per-request introspection calls. Cache config and credential lookups in memory.
- **Right-size buffers and avoid full-body buffering.** Streaming bodies (not buffering) reduces memory and latency; body transformation forces buffering — use sparingly.
- **TLS cost.** Asymmetric handshake crypto is expensive; enable session resumption (tickets) and HTTP keep-alive; use AES-NI; offload to dedicated TLS terminators if extreme scale.
- **Latency budget.** A well-tuned L7 gateway adds roughly **single-digit milliseconds** (often <1–5ms) of overhead per request, plus the cost of any synchronous plugin (e.g. a Redis hit for rate limiting ~0.2–1ms, an external authz/introspection call tens of ms). Measure your own — these are order-of-magnitude.
- **Beware aggregation tail latency.** A fan-out is as slow as its slowest dependency; add per-call timeouts and degrade.

### 6.2 Correctness & concurrency

- **Distributed rate limiting accuracy vs cost.** Local counters are fast but allow N× overshoot across N nodes; shared (Redis) counters are accurate but add a hop and a dependency. Sliding-window-counter approximations are a common compromise.
- **Idempotency & retries.** Retry only idempotent operations or those with idempotency keys; otherwise you risk double charges/orders. Cap retries with a budget to avoid retry storms that turn a blip into an outage.
- **Race in config hot-swap.** Use atomic, versioned config snapshots so a request is never served against a half-applied config.
- **Header trust.** Strip/normalize `X-Forwarded-*`, `Host`, and hop-by-hop headers at the edge; never trust client-supplied internal headers (e.g. `X-Consumer-ID`) — set them yourself.
- **Request smuggling.** Inconsistent parsing of `Content-Length` vs `Transfer-Encoding` between gateway and upstream enables HTTP request smuggling. Keep proxies patched and reject ambiguous framing.

### 6.3 Security

- **TLS everywhere; modern config.** TLS 1.2 minimum (prefer 1.3), strong cipher suites, HSTS, OCSP stapling. Re-encrypt to upstreams in zero-trust networks.
- **Authn at the edge, authz layered.** Coarse authz (scopes) at the gateway; fine-grained ownership checks in services (never assume the gateway alone is sufficient).
- **Secret management.** Store keys/certs in a vault (HashiCorp Vault, AWS Secrets Manager, K8s Secrets with encryption-at-rest), not in config files in Git. Rotate regularly.
- **Rate limit + WAF + bot protection** to blunt abuse and credential stuffing.
- **Validate input at the edge** against the OpenAPI schema to reject malformed/oversized payloads early (defense in depth — services must still validate).
- **Least privilege for the gateway's own credentials** to upstreams and the control plane; lock down the **Admin API** (Kong's `:8001` must never be public — a notorious breach vector).
- **Audit logging** of admin/config changes.

> **Defense in depth (term):** Layering multiple independent controls so that the failure of one doesn't compromise the system. Edge validation + service validation is one example.

### 6.4 Observability

- **The three pillars:** metrics (RED — Rate, Errors, Duration per route/consumer/upstream), logs (structured access logs with correlation IDs), traces (gateway as root span, propagate W3C trace context).
- **Distinguish gateway vs upstream errors** in metrics (e.g. separate `5xx_from_upstream` vs `gateway_no_healthy_upstream`).
- **SLOs and golden signals.** Track p50/p95/p99 latency, error rate, saturation (event-loop utilization, connection counts).
- **Expose** Prometheus metrics; ship logs to a central store; sample traces (e.g. 1–10%) to control cost.

> **RED / golden signals (terms):** RED = Rate, Errors, Duration (request-centric). Google SRE "golden signals" = Latency, Traffic, Errors, Saturation. Both are standard dashboards for an edge service.

### 6.5 Cost

- Managed gateways bill per-request (AWS REST API historically ~$3.50/M calls + data + cache hours; HTTP API ~$1.00/M — *verify current pricing*). At high volume a self-hosted gateway (Kong/Envoy/SCG) on your own compute can be far cheaper but adds operational cost.
- Caching reduces upstream cost and latency but adds correctness risk (stale data) and cache infra cost.
- Redis for rate limiting/caching is an extra always-on dependency to budget and run HA.

### 6.6 Testing

- **Contract tests** against the OpenAPI/proto (Pact, Spring Cloud Contract).
- **Policy/config tests:** assert routes, auth, and limits behave (integration tests hitting the gateway with/without keys, over/under limits).
- **Schema validation tests** with Prism proxy in CI.
- **Load tests** (k6, Gatling, wrk) to find the throughput knee and tune pools/limits.
- **Chaos:** kill upstreams, drop Redis, inject latency — verify circuit breakers and fail-open/closed behave as intended.
- **Config as code + CI gate:** validate gateway config (e.g. `deck gateway validate`, OpenAPI lint with Spectral) before deploy.

### 6.7 Production hardening checklist

- Run **≥3 stateless replicas** across **multiple AZs** behind a redundant L4 load balancer; never a single gateway instance (it's a SPOF — §7.5).
- **Autoscale** on CPU/connections/RPS; set sane connection and request-size limits.
- **Graceful shutdown / connection draining** on deploy.
- **Timeouts at every hop** (connect, per-try, overall) — no unbounded waits.
- **Circuit breakers + outlier detection** on every upstream.
- **Fail-open vs fail-closed** decided per policy (auth must fail-closed; rate limiting may fail-open if the counter store is down — your choice, document it).
- **Lock down admin/control plane**; separate networks; mTLS CP↔DP.
- **Blue/green or canary** config rollouts; keep a fast rollback.
- **Backpressure / load shedding** when overloaded (return `503` early rather than collapsing).

### 6.8 Anti-patterns to avoid

1. **Business logic in the gateway (the cardinal sin).** Domain rules in VTL templates, Lua/JS callouts, or filter code create a hard-to-test, operationally fragile distributed monolith. The gateway should be *thin* and *generic*. Litmus test: *if a product manager would care about the rule, it's business logic and belongs in a service.*
2. **One giant shared gateway as a coupling point / SPOF.** A monolithic gateway that all teams must change becomes a bottleneck and a single failure domain.
3. **Trusting client headers** (`X-User-Id`, `X-Forwarded-For`) without stripping/overwriting.
4. **Public Admin API** or weak control-plane auth.
5. **Synchronous, unbudgeted retries** (retry storms) and **no timeouts**.
6. **Per-node rate-limit counters** behind a multi-node deployment (silent N× overshoot).
7. **Caching private/per-user data with a public cache key** (data leakage across users).
8. **Aggregation in the shared gateway for one specific client** — that's a BFF concern; doing it in the shared gateway couples it to one client's needs.
9. **Versioning by mutating in place** instead of additive, non-breaking changes + deprecation policy.
10. **No fallback / no degradation** — a single slow dependency takes down the whole edge.

---

## 7. Advanced topics & deep internals

### 7.1 Route matching internals

Routers compile routes into a **radix tree** keyed by path segments, partitioned by host and method. Static segments are tree edges; parameters/wildcards are special edges evaluated after statics. Matching is O(path-length), independent of route count. Conflicts are resolved by **specificity**: longer/more-constrained matches win; equal specificity is broken by explicit priority/order. SCG evaluates predicates in `order`; Kong uses a router that (in 3.x) uses an expression-based engine (ATC) for flexible, fast matching. Pitfall: overlapping prefixes (`/orders` vs `/orders/{id}`) and regex routes that are expensive — prefer prefix/exact matches.

### 7.2 Rate-limiting algorithms in depth

| Algorithm | How it works | Pros | Cons |
|---|---|---|---|
| Fixed window | Count per calendar window (e.g. each minute) | Simplest, cheap | Boundary bursts: 2× limit across the window edge |
| Sliding window log | Store timestamp of each request; count those within window | Accurate | Memory O(requests); costly |
| Sliding window counter | Weighted blend of current + previous fixed windows | Near-accurate, cheap | Slight approximation |
| Token bucket | Tokens refill at rate R, bucket cap B; each request takes a token | Allows controlled bursts up to B | Two params to tune |
| Leaky bucket | Requests queue and drain at fixed rate | Smooths output | Adds latency; queue mgmt |

Distributed implementation: a Redis Lua script does an atomic `INCR`+`EXPIRE` (fixed/sliding window) or token-bucket math, returning allowed/remaining in one round trip. SCG's `RequestRateLimiter` ships a Redis token-bucket Lua script. Spike arrest (Apigee) ≈ leaky bucket at a per-instant rate.

> **Token bucket vs spike arrest:** Token bucket permits a *burst* up to bucket size then steady rate; spike arrest forbids bursts entirely by spacing requests (e.g. ≥33ms apart for 30ps). Use spike arrest to protect fragile backends from any burst; token bucket for friendlier client UX.

### 7.3 JWT validation internals & caching

The gateway fetches the issuer's **JWKS** (public keys) from `/.well-known/openid-configuration` → `jwks_uri`, caches it (respecting cache headers, with a refresh on unknown `kid`), and validates each JWT's signature with the matching key (`kid` header). It checks `exp`, `nbf`, `iat` (with allowed clock skew ~30–60s), `iss`, and `aud`. **Revocation gap:** local JWT validation can't instantly revoke a token before `exp`; mitigate with short token lifetimes + refresh tokens, a revocation list/introspection for high-value tokens, or `jti` denylists.

> **kid / jti / nbf (terms):** `kid` = key id in the JWT header selecting which JWKS key to use. `jti` = unique token id (for denylists). `nbf` = "not before" time.

### 7.4 Load-balancing & service discovery internals

- **Algorithms:** round-robin, weighted RR, least-connections/least-request, random-two-choices (P2C: pick 2 random hosts, send to the less-loaded — near-optimal with O(1) cost), consistent hashing / ring hash (sticky by key, minimal reshuffle on membership change), maglev hashing.
- **Service discovery:** static config, DNS (`STRICT_DNS`/`LOGICAL_DNS`), Kubernetes endpoints, Consul/Eureka. SCG resolves `lb://service-id` via Spring Cloud LoadBalancer + a discovery client.
- **Health checks:** active (periodic probe of `/health`) and passive (outlier detection on live traffic). Slow-start ramps traffic to freshly-healthy hosts.

> **Consistent hashing (term):** Maps keys and nodes onto a ring; a key goes to the next node clockwise. Adding/removing a node reshuffles only ~1/N of keys (vs nearly all with modulo hashing) — good for sticky routing and caches. **P2C / power of two choices (term):** Randomly sample two backends and route to the less-loaded; provably near-optimal balancing with trivial cost.

### 7.5 High availability — the gateway as a single point of failure

A gateway is on the critical path of *every* request, so a single instance is a **SPOF** (single point of failure): if it dies, *everything* is down. HA techniques:

- **Horizontal redundancy:** ≥2–3 **stateless** replicas; any can serve any request.
- **Multi-AZ / multi-region:** spread replicas across availability zones (and regions for DR). Use DNS/anycast/global load balancers (Route 53, GSLB) to fail over.
- **Front it with a redundant L4 LB** (cloud NLB/ELB, or keepalived+VIP on-prem) that health-checks gateway nodes and removes dead ones.
- **No node-local critical state** (counters/sessions externalized) so losing a node loses nothing.
- **Control-plane outage tolerance:** data plane keeps serving from cached config if the control plane is down.
- **Capacity headroom & autoscaling** so one AZ's loss doesn't overload survivors (run at <50% per-AZ if N+1 across 2 AZs).
- **Graceful degradation:** fail-open where safe (caching, non-critical enrichment), serve stale cache, shed load with `503` instead of collapsing.

> **SPOF / AZ / DR / VIP / anycast (terms):** **SPOF** — a component whose failure takes down the whole system. **AZ** (Availability Zone) — an isolated datacenter within a cloud region. **DR** (Disaster Recovery) — capability to recover from a region/site loss. **VIP** (Virtual IP) — an IP that can float between nodes for failover (keepalived/VRRP). **Anycast** — one IP advertised from many locations; the network routes a client to the nearest healthy one.

### 7.6 Caching internals & invalidation

Cache key = method + normalized path + query + `Vary` headers (+ consumer for private). Respect `Cache-Control` (`no-store`, `private`, `max-age`, `s-maxage`), `ETag`/`Last-Modified` for revalidation (`304`), and `Vary`. Invalidation strategies: TTL expiry, explicit purge API, event-driven purge (on write, publish an invalidation), stale-while-revalidate (serve stale while refreshing in background). Pitfalls: caching `Set-Cookie`/auth-varying responses without `Vary`, caching error responses, thundering herd on expiry (mitigate with request coalescing / single-flight).

> **Thundering herd / cache stampede (term):** When a popular cache entry expires, many concurrent requests all miss and hit the backend at once. Mitigate with single-flight (one request fetches, others wait) or jittered TTLs.

### 7.7 Protocol translation & GraphQL federation

Gateways may translate REST↔gRPC (transcoding), JSON↔XML (legacy SOAP), HTTP/1.1↔HTTP/2/3, and WebSocket upgrades. **GraphQL federation** (Apollo Router, others) is a specialized "gateway" that composes a single schema from multiple subgraph services and plans/executes queries across them — a different model from REST routing.

### 7.8 Lesser-known behaviors / tuning knobs

- **Plugin/filter ordering bugs:** the same plugins in a different order behave differently (auth after rate-limit = limit by IP not consumer). Always pin order explicitly.
- **`strip_path`/`StripPrefix` mismatches** silently send wrong paths upstream.
- **`preserve_host`/`PreserveHostHeader`:** whether the upstream sees the original `Host` (matters for vhost-based upstreams and absolute-URL generation).
- **Buffer/body-size limits** cause surprising `413`s on large uploads.
- **Header case/duplication** handling differs across proxies; multiple `Set-Cookie` handling is a classic footgun.
- **HTTP/2 stream and concurrency limits** (`SETTINGS_MAX_CONCURRENT_STREAMS`) can throttle multiplexed clients.
- **Slowloris protection:** header/body read timeouts to defend against slow-drip attacks.
- **Event-loop saturation:** in SCG, watch reactor-netty event-loop thread CPU; offload any unavoidable blocking work to a bounded scheduler (`publishOn(Schedulers.boundedElastic())`) — but better, don't block.

---

## 8. Tradeoffs & decision frameworks

### 8.1 BFF vs (shared) API gateway

> **BFF (Backend for Frontend) (term):** A pattern where each *client type* (web, iOS, Android, partner) gets its *own* thin backend that aggregates and shapes data specifically for that client. Coined at SoundCloud. The BFF owns *presentation/aggregation* logic for one client; it is *not* shared across clients.

| Dimension | Shared API gateway | BFF |
|---|---|---|
| Purpose | Generic edge policy for all clients | Client-specific aggregation & shaping |
| Owned by | Platform/infra team | The client's product team |
| Logic | Cross-cutting only (auth, limits, routing) | Per-client aggregation, response shaping |
| Coupling | Decoupled from any one client | Tightly coupled to one client (by design) |
| Count | Usually one (HA cluster) | One per client type |
| Risk if misused | Becomes a business-logic monolith | Duplicated logic across BFFs |

They are **complementary**: clients → shared gateway (auth, TLS, limits) → BFF (aggregate/shape for that client) → services. Put cross-cutting concerns in the gateway; put client-specific composition in the BFF. **Avoid** doing one client's aggregation in the shared gateway (couples it) and **avoid** re-implementing auth/limits in each BFF.

### 8.2 Choosing a gateway

| Option | Best when | Avoid when | Notes |
|---|---|---|---|
| **AWS API Gateway (HTTP API)** | Serverless/Lambda, AWS-native, low ops | Need rich transforms, multi-cloud, ultra-low latency, very high volume cost | Cheapest managed AWS option; fewer features |
| **AWS API Gateway (REST API)** | Need usage plans, VTL mapping, request validation, caching, WAF | Cost-sensitive high volume | Pricier; 29s integration limit (verify) |
| **Kong** | Self-host, plugin ecosystem, K8s/DB-less GitOps, hybrid CP/DP | Don't want to run infra; minimal needs | OpenResty/Lua; large plugin set; OSS + Enterprise |
| **Apigee** | Full API *management*/monetization, enterprise governance, partner programs | Just need a thin proxy; cost-sensitive | Heavy platform; policy/callout-driven |
| **Spring Cloud Gateway** | Java shop building a *custom* gateway/BFF, Spring ecosystem, reactive | Want a managed product; team not on Reactor | Library, not a product; you operate it |
| **Envoy / Gateway API** | Cloud-native, mesh integration, dynamic xDS, K8s standard | Want turnkey portal/monetization | Powerful, lower-level; pair with control plane |
| **KrakenD** | Heavy aggregation, stateless declarative | Need stateful plugins/portal | Aggregation-first |

Decision rules:
- **AWS-only + serverless** → start with AWS **HTTP API**; move to REST API only for its specific features.
- **Self-hosted, K8s, GitOps** → Kong (DB-less/hybrid) or Envoy Gateway/Gateway API.
- **Need monetization, partner portal, governance** → Apigee (or Kong/AWS + a portal).
- **You're a Java team building a bespoke edge/BFF** → Spring Cloud Gateway.
- **Mesh already in place (Istio)** → use its ingress gateway for north-south.

### 8.3 Where does each concern live?

| Concern | Gateway | BFF | Service | Mesh (east-west) |
|---|---|---|---|---|
| TLS termination (north) | Yes | — | — | — (mTLS) |
| Authn (verify token) | Yes | inherits | re-verify if needed | mTLS identity |
| Coarse authz (scopes) | Yes | maybe | Yes | — |
| Fine-grained authz (ownership) | No | No | **Yes** | — |
| Rate limiting (consumer/global) | Yes | — | local limits | — |
| Aggregation/shaping | No (shared) | **Yes** | — | — |
| Business rules | **No** | minimal presentation | **Yes** | — |
| Retries/timeouts/CB | Yes (north) | Yes (its fan-out) | client-side | **Yes** |

### 8.4 Managed vs self-hosted

| | Managed (AWS/Apigee) | Self-hosted (Kong/Envoy/SCG) |
|---|---|---|
| Ops burden | Low | High (you run HA, upgrades, scaling) |
| Cost model | Per-request (scales with usage) | Compute + people (fixed-ish) |
| Flexibility | Bounded by vendor | Full control / extensible |
| Lock-in | Higher | Lower |
| Latency floor | Vendor-dependent | You can tune to the metal |

---

## 9. Failure modes & debugging

### 9.1 Common production failures & first response

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Mass `503 no healthy upstream` | All targets failed health checks / scaled to zero / wrong port | Gateway upstream/health metrics; `kubectl get endpoints`; envoy `/clusters` | Fix health endpoint, scale up, correct target port |
| `504` upstream timeout | Slow backend, timeout too low, pool exhausted | p99 upstream latency; pool saturation metrics | Tune timeout/pool, fix backend, add circuit breaker |
| `502 bad gateway` | Upstream closed connection, protocol mismatch (H2 vs H1), TLS error to upstream | Gateway error logs; upstream logs; `Via` header | Fix protocol/TLS to upstream; keep-alive tuning |
| Intermittent `401` | JWKS rotated and cache stale; clock skew | Auth plugin logs; `kid` mismatch | Refresh JWKS on unknown `kid`; sync NTP; allow skew |
| Rate limits too loose | Per-node counters behind multi-node LB | Compare allowed vs limit across nodes | Switch to Redis/cluster policy |
| Rate limits too strict / flapping | Redis down + fail-closed; window boundary bursts | Redis health; limiter logs | Decide fail-open; use sliding window |
| Cache serves wrong user's data | Private response cached with public key | Inspect cache key & `Vary` | Add consumer to key / mark `private`/`no-store` |
| Latency spike on the gateway | Event-loop saturation, blocking call, GC, TLS CPU | Event-loop thread CPU, GC logs, flame graph | Remove blocking code; scale; tune TLS/GC |
| Random `404` after deploy | Config not propagated / route order changed | Config version per node; admin API diff | Re-sync config; verify route specificity |
| Total outage when gateway node dies | SPOF — single instance | Topology review | Run ≥3 replicas multi-AZ behind LB |
| Retry storm amplifies a blip into an outage | Unbudgeted retries | Retry-count metrics spike | Add retry budget; only idempotent; backoff+jitter |
| Request smuggling / header injection | CL/TE mismatch, trusted client headers | Security scan; access logs | Patch proxy; strip/normalize headers |

### 9.2 Diagnostic tooling & commands

```bash
# Is it the gateway or the upstream? Hit the upstream directly (inside the cluster).
kubectl exec -it debug-pod -- curl -sv http://order-service:8080/orders/o-1

# Envoy live introspection (admin port, keep it private!)
curl localhost:9901/clusters         # upstream health, outlier ejections
curl localhost:9901/stats | grep -E 'upstream_(rq_5xx|cx_connect_fail)'
curl localhost:9901/config_dump      # exactly what config this node is running
curl localhost:9901/server_info      # state: LIVE/DRAINING

# Kong: inspect routing & plugins for a request (Admin API — private!)
curl localhost:8001/routes
curl localhost:8001/services/order-service/plugins

# SCG: actuator endpoints
curl localhost:8080/actuator/gateway/routes        # effective routes
curl localhost:8080/actuator/metrics/spring.cloud.gateway.requests
curl localhost:8080/actuator/health

# Trace a request end-to-end (correlation id you injected at the edge)
grep "X-Request-Id: abc-123" gateway-access.log
# Then open the same trace id in Jaeger/Tempo to see gateway→service spans.

# Load test to find the knee and confirm limits
k6 run --vus 200 --duration 60s loadtest.js
```

> **Key triage move:** Reproduce against the upstream *directly* to bisect gateway-vs-backend. Then read the *gateway's own* error logs/metrics, which distinguish `no_healthy_upstream` (gateway's view) from a real upstream `5xx`. Use `config_dump`/actuator routes to confirm the node is running the config you think it is (propagation lag is a top cause of "it works on node A not node B").

### 9.3 Real-world incident patterns (anonymized/representative)

- **The N× rate-limit overshoot.** A team set `100 req/s` with Kong's default `policy: local`, ran 8 nodes, and a partner effectively got 800 req/s, overwhelming a fragile backend. Fix: `policy: redis`. Lesson: per-node counters silently multiply by node count.
- **Exposed Admin API.** A Kong Admin API (`:8001`) reachable from the internet let an attacker add a route to exfiltrate traffic. Lesson: never expose the control/admin plane; bind to localhost or a locked-down network, require auth/mTLS.
- **JWKS rotation outage.** An IdP rotated signing keys; the gateway cached JWKS for too long and rejected all valid tokens (`401` storm) until cache TTL expired. Lesson: refresh JWKS on unknown `kid`; short, sane cache.
- **Retry storm.** A 2-second backend blip caused clients + gateway retries to triple effective load, turning a blip into a 20-minute outage. Lesson: retry budgets, exponential backoff with jitter, idempotency only.
- **Logic-in-gateway debugging hell.** Pricing rules in VTL mapping templates produced wrong totals that no service test could catch and no debugger could step through. Lesson: business logic belongs in services.
- **Event-loop stall.** A "quick" synchronous DB lookup added to an SCG filter blocked Netty event-loop threads under load; p99 latency went from 5ms to 5s. Lesson: never block the event loop.

> **Backoff with jitter (term):** Exponential backoff spaces retries by increasing delays; *jitter* randomizes each delay so many clients don't retry in lockstep ("thundering herd" of retries). Standard practice (AWS "full jitter").
>
> **IdP (term):** Identity Provider — the system that authenticates users and issues tokens (e.g. Keycloak, Auth0, Okta, Cognito, Azure AD/Entra).

---

## 10. Interview drill

**Q1. What is an API gateway and what core responsibilities does it own?**
*Model answer:* A managed single entry point at the edge that reverse-proxies client traffic to backend services while applying cross-cutting policy: routing, authn/z, rate limiting/quotas, request/response transformation, aggregation, TLS termination, caching, plus load balancing, retries, circuit breaking, and observability. It's an L7 proxy that understands API semantics (consumers, keys, plans).
- *Probe: Gateway vs reverse proxy?* A reverse proxy relays traffic; a gateway adds API-aware policy — consumers, credentials, quotas, plans, analytics — and per-route/per-consumer plugins.
- *Probe: What must NOT go in it?* Business/domain logic — it creates a fragile distributed monolith that's hard to test and operate.
- *Probe: Data plane vs control plane?* Data plane is on the request hot path (the proxy); control plane configures it and collects telemetry, off the hot path and eventually consistent.

**Q2. Walk me through the lifecycle of a request through a gateway.**
*Model answer:* Accept connection → TLS handshake (read SNI, verify mTLS) → parse/demux → route match (radix tree by host/path/method) → request normalization → authentication (resolve consumer) → authorization (scopes) → rate limiting/quota (per consumer) → cache lookup (short-circuit on hit) → load-balance to a healthy target → upstream call with timeout/retries → response transform → cache store → emit logs/metrics/trace → stream to client. Any stage can short-circuit (`401/403/404/429`, cache hit).
- *Probe: Why must auth precede rate limiting?* Rate limits are per-consumer; you must know the consumer first or you only get per-IP limits.
- *Probe: Why event-loop I/O?* A gateway holds many idle connections; thread-per-connection wastes memory/CPU. Event loops (epoll) let few threads serve tens of thousands of connections.
- *Probe: Where can it short-circuit and why does that matter?* Cache hit, rate-limit reject, auth fail — saves upstream load and latency.

**Q3. How do you implement rate limiting across a multi-node gateway cluster?**
*Model answer:* Per-node counters allow N× overshoot, so use a shared store (Redis) with an atomic Lua script (token bucket or sliding-window counter) to get a global count, accepting one network hop. Choose the algorithm by needs: token bucket for friendly bursts, spike arrest/leaky bucket to protect fragile backends, sliding-window counter as an accurate-cheap compromise. Decide fail-open vs fail-closed if Redis is down.
- *Probe: Compare token bucket vs sliding window.* Token bucket allows bursts up to bucket size then steady refill; sliding window counter approximates true rate with low memory, no burst allowance unless configured.
- *Probe: Cost of Redis-based limiting?* ~sub-ms latency per request plus an always-on HA dependency; mitigate with local pre-checks and batching.
- *Probe (senior signal): When would you accept inaccurate local limits?* Very high RPS where Redis latency is unacceptable and slight overshoot is harmless — trade accuracy for latency and fewer dependencies.

**Q4. Gateway vs BFF — when each, and can you use both?**
*Model answer:* The gateway is a shared, generic edge for all clients (auth, TLS, limits, routing). A BFF is a per-client backend that aggregates/shapes data for one client type, owned by that client's team. Use both: client → gateway → BFF → services. Don't aggregate one client's data in the shared gateway (couples it), and don't re-implement auth/limits in each BFF.
- *Probe: Why per-client BFFs instead of one?* Client needs diverge (mobile wants less data, different shapes); one shared aggregator becomes a coupling monolith.
- *Probe: Downside of BFFs?* Logic duplication across BFFs; mitigate with shared libraries for truly common bits.
- *Probe: Where does fine-grained authz live?* In the service (ownership checks), not the gateway/BFF.

**Q5. The gateway is on every request's path — how do you keep it from being a single point of failure?**
*Model answer:* Run ≥3 stateless replicas across multiple AZs behind a redundant L4 load balancer; externalize all critical state (rate counters → Redis, no node-local sessions); keep capacity headroom (N+1) and autoscale; tolerate control-plane outages by serving cached config; multi-region + global DNS/anycast for DR; graceful degradation (fail-open where safe, serve stale cache, shed load with `503`).
- *Probe (senior signal): Fail-open or fail-closed when the auth dependency is down?* Auth fails closed (security); rate limiting may fail open (availability) — document per policy.
- *Probe: How does control-plane downtime affect traffic?* It shouldn't — data plane serves from locally cached config; only config *changes* pause.
- *Probe: How much headroom per AZ?* With N+1 across 2 AZs, run <50% per AZ so one AZ loss doesn't overload the other.

**Q6. North-south vs east-west traffic — and where does a gateway vs a service mesh fit?**
*Model answer:* North-south is client↔system edge traffic (gateway's domain); east-west is service↔service inside the system (mesh's domain). The gateway handles untrusted ingress (keys/OAuth/TLS, quotas, aggregation); the mesh handles internal mTLS identity, retries, and observability between services. They're complementary; some products do both (Istio ingress gateway).
- *Probe: Why zero trust east-west?* Internal networks are assumed hostile; each call is authenticated (mTLS/SPIFFE) regardless of origin.
- *Probe: Could the mesh replace the gateway?* Mesh ingress can do north-south, but dedicated gateways add API-management features (keys, portal, monetization).

**Q7. How does the gateway validate a JWT, and what are the failure modes?**
*Model answer:* Parse `header.payload.signature`, pick the key by `kid` from a cached JWKS (fetched from the issuer's discovery doc), verify the signature, then validate `exp`/`nbf`/`iat` (with clock-skew tolerance), `iss`, and `aud`. Failures: stale JWKS after key rotation (refresh on unknown `kid`), clock skew, wrong audience, and the revocation gap (can't revoke before `exp`).
- *Probe: JWT vs opaque token introspection?* JWT validates locally (fast, no network) but hard to revoke; opaque tokens need an introspection call (slower, revocable).
- *Probe: How to handle revocation?* Short token lifetimes + refresh, `jti` denylist, or introspection for high-value tokens.
- *Probe: Where do you cache JWKS and for how long?* In-memory per node, refreshed on unknown `kid` and on a sane TTL respecting cache headers.

**Q8. Describe the API management lifecycle beyond the runtime gateway.**
*Model answer:* Design (OpenAPI/contract-first) → mock (Prism, parallel client dev) → implement + contract test → publish to environments → developer portal (discovery, docs, try-it) → keys/credentials (issue, scope, rotate, revoke) → analytics (traffic/latency/errors/top consumers) → monetization (plans/products, metering, billing) → versioning/deprecation → retire. The gateway is the runtime; management wraps it with lifecycle, governance, and the business layer.
- *Probe: Why design-first?* The contract drives docs, mocks, validation, SDKs, and gateway config from one source; enables parallel work and prevents drift.
- *Probe: What's a "product/plan"?* A bundle of routes + quota + price offered to consumers — the monetization and access unit.

**Q9 (senior signal). A team wants to add a discount calculation in the gateway via a Lua/VTL/JS callout. What do you say, and why?**
*Model answer:* Push back. That's business logic; it belongs in a service. In the gateway it's hard to unit-test, can't be debugged with normal tooling, couples the edge to a domain rule, risks correctness (e.g. VTL math bugs), and runs on a latency- and reliability-critical shared component. The litmus test: if a PM cares about the rule, it's business logic. Keep the gateway thin and generic; expose a service endpoint and route to it.
- *Probe: Any acceptable "logic" in the gateway?* Generic request shaping (header/path rewrite, protocol translation, response filtering for transport reasons) — not domain decisions.
- *Probe: How would you migrate existing gateway logic out?* Strangler approach: stand up the service logic, route a slice to it, verify with contract tests, then remove the gateway callout.

**Q10 (senior signal). Choose a gateway for: a Java microservices shop on Kubernetes that also needs a partner developer portal and per-partner monetization. Justify.**
*Model answer:* Two layers. For the runtime, Kong (DB-less/hybrid) or Envoy Gateway via the K8s Gateway API fits a self-hosted K8s shop with GitOps. For portal + monetization + governance, either Kong Enterprise (has portal/RBAC) or a dedicated management layer (Apigee) — Apigee if monetization/partner governance is central and budget allows; Kong if you prefer self-hosted and lighter cost. If the team wants to build a bespoke aggregating edge/BFF in Java, Spring Cloud Gateway for the BFF layer, behind the platform gateway. Justify by ops model (self-host vs managed), feature need (portal/monetization), cost, and team skill (Java/Spring).
- *Probe: Why not just AWS API Gateway?* Fine if AWS-native and serverless; weaker fit for K8s-hosted services and rich partner-portal/monetization governance.
- *Probe: How avoid the gateway becoming a coupling SPOF across teams?* Federate ownership (per-team routes via Gateway API CRDs/GitOps), strong defaults, and keep it thin; HA cluster removes the availability SPOF.

**Q11 (senior signal). Your gateway's p99 latency jumped from 5ms to 800ms after a release, error rate is flat. How do you debug, and what are the usual culprits?**
*Model answer:* Confirm it's the gateway not upstreams (call upstream directly; compare gateway-added latency metric). Check event-loop/thread CPU and GC; a blocking call introduced into a reactive filter (JDBC, synchronous HTTP, lock) is the classic cause in SCG. Check connection-pool saturation to upstreams, new TLS overhead, and a new synchronous plugin (introspection/authz/Redis) added in the release. Look at flame graphs and `config_dump`/actuator to confirm config. Fix by removing the blocking work (or offloading to a bounded scheduler), tuning pools, or caching the synchronous lookup.
- *Probe: Why does one blocking call tank a reactive gateway?* It occupies an event-loop thread; with a tiny event-loop pool, throughput collapses and latency balloons under load.
- *Probe: How prevent it?* Lint/architecture rules banning blocking APIs in filters; load tests in CI; BlockHound to detect blocking on Reactor threads.

> **BlockHound (tool):** A Java agent that detects blocking calls on non-blocking (Reactor) threads at runtime, used to catch event-loop-blocking bugs in tests.

---

## 11. Glossary

- **ABAC** — Attribute-Based Access Control; authorization from attributes (user, resource, context) via policies.
- **Aggregation/composition** — Fanning one client request out to multiple backends and merging the responses.
- **Anycast** — One IP advertised from many locations; routing sends clients to the nearest healthy site.
- **API key** — A secret string identifying/authenticating a consumer app.
- **API management** — Runtime + lifecycle + governance + business layer around APIs (design→mock→publish→portal→keys→analytics→monetization).
- **API product/plan** — A bundle of routes + quota + price offered to consumers.
- **Authentication (authn)** — Verifying *who* the caller is.
- **Authorization (authz)** — Verifying *what* the caller may do.
- **AZ (Availability Zone)** — An isolated datacenter within a cloud region.
- **Backoff (with jitter)** — Increasing, randomized retry delays to avoid synchronized retry storms.
- **BFF (Backend for Frontend)** — A per-client backend that aggregates/shapes data for one client type.
- **BlockHound** — Java agent detecting blocking calls on reactive threads.
- **Circuit breaker** — Resilience pattern that fails fast on a sick upstream; states closed→open→half-open.
- **Consistent hashing** — Ring-based key→node mapping minimizing reshuffle on membership change.
- **Contract testing** — Tests verifying a service honors its API contract (Pact, Spring Cloud Contract).
- **Control plane** — Management layer that configures the data plane; off the request hot path.
- **CORS** — Browser cross-origin request permission mechanism via `Access-Control-*` headers.
- **CRD** — Kubernetes Custom Resource Definition; extends K8s with custom object types.
- **Cross-cutting concern** — Functionality (auth, logging, limits) needed across many components.
- **Data plane** — The proxy on the request hot path that actually serves traffic.
- **decK** — Kong's declarative-config CLI for GitOps.
- **Defense in depth** — Layering independent security controls.
- **DR (Disaster Recovery)** — Recovering from a region/site loss.
- **East-west traffic** — Service-to-service traffic inside the system.
- **Envoy** — Modern L7 proxy; data plane for many gateways/meshes; configured via xDS.
- **epoll/kqueue/IOCP** — OS I/O multiplexing syscalls enabling event-loop concurrency.
- **ETag/Cache-Control/Vary** — HTTP caching headers (version tag, cacheability, distinguishing headers).
- **Eventually consistent** — Nodes briefly disagree after an update but converge.
- **Event loop** — Few threads watching many sockets via epoll/kqueue; non-blocking concurrency model.
- **Fail-open / fail-closed** — On dependency failure, allow (availability) vs deny (security).
- **Forward proxy** — Proxy in front of clients forwarding their outbound requests.
- **GitOps** — Declarative config in Git as source of truth, reconciled by an agent.
- **Golden signals** — Latency, Traffic, Errors, Saturation (SRE dashboard).
- **GraphQL/SDL/federation** — Query language; schema language; composing a schema from subgraphs.
- **HAProxy** — High-performance TCP/HTTP load balancer.
- **HMAC** — Hash-based message auth code; shared-secret request signing.
- **Hop-by-hop headers** — Single-hop headers (`Connection`, `Transfer-Encoding`) not forwarded by proxies.
- **HSTS** — `Strict-Transport-Security`; forces HTTPS.
- **HTTP/2, HTTP/3, QUIC** — Multiplexed HTTP over TCP (H2) and over UDP/QUIC (H3).
- **Idempotency** — An operation safe to repeat with the same effect (GET/PUT/DELETE).
- **IdP (Identity Provider)** — Issues tokens and authenticates users (Keycloak/Auth0/Okta/Cognito).
- **Ingress (K8s)** — Older K8s resource for HTTP routing; superseded by Gateway API.
- **JWKS** — Published set of public keys for verifying JWT signatures.
- **JWT** — Signed JSON token carrying claims (identity/scopes).
- **kid/jti/nbf/exp/aud/iss** — JWT fields: key id, token id, not-before, expiry, audience, issuer.
- **L4/L7** — Transport vs application layer; L7 proxies read HTTP content.
- **Lambda authorizer** — A Lambda the gateway calls to authorize a request.
- **Leaky/token bucket** — Rate-limit algorithms (drain at fixed rate / refill tokens, allow bursts).
- **Load shedding/backpressure** — Dropping/rejecting excess load to protect the system.
- **mTLS** — Mutual TLS; both sides authenticate with certificates.
- **Monetization** — Charging for API usage via plans, metering, billing.
- **Netty** — JVM non-blocking I/O framework; underlies SCG/WebFlux.
- **North-south traffic** — Client↔system edge traffic.
- **OAuth2** — Token-based delegated authorization framework.
- **OIDC** — Identity layer on OAuth2 adding ID tokens.
- **OpenAPI/Swagger** — Standard machine-readable REST API description + tooling.
- **OpenResty** — nginx + LuaJIT; the basis of Kong.
- **OPA/Rego** — Policy engine and its language for externalized authorization.
- **Outlier detection** — Passive health check ejecting failing upstream hosts (Envoy).
- **P2C (power of two choices)** — Sample two backends, route to the less loaded.
- **Passthrough/termination/re-encryption** — TLS handling modes at the proxy.
- **Prism** — OpenAPI mock + validating proxy tool.
- **Project Reactor / Mono / Flux** — JVM reactive-streams library and its types.
- **QUIC** — UDP-based transport under HTTP/3 with built-in TLS 1.3.
- **Quota** — Long-window usage cap tied to a plan.
- **Radix tree/trie** — Prefix-tree structure for fast route matching.
- **Rate limiting / throttling / spike arrest** — Limiting/smoothing request rate.
- **RBAC** — Role-Based Access Control.
- **RED** — Rate, Errors, Duration metrics.
- **Reverse proxy** — Server fronting backends, relaying client requests to them.
- **Retry budget** — Cap on the fraction of requests that may be retries (prevents storms).
- **Service / Upstream / Target** — Logical backend / backend cluster / concrete instance.
- **Service mesh** — Infra for east-west concerns via sidecar proxies (Istio/Linkerd).
- **Short-circuit** — A pipeline stage returning a response without calling upstream.
- **SNI** — TLS extension naming the requested host for cert selection.
- **SPIFFE/SVID** — Workload identity standard and its credential.
- **SPOF** — Single point of failure.
- **Stateless** — Stores no per-client session state; any instance serves any request.
- **Strangler facade** — Migration pattern routing slices of a legacy system to new implementations.
- **Thundering herd / cache stampede** — Many concurrent misses hitting the backend on cache expiry.
- **Token introspection** — OAuth2 endpoint reporting whether an opaque token is active.
- **Transformation** — Rewriting requests/responses (paths, headers, bodies, protocols).
- **VIP** — Virtual IP that floats between nodes for failover.
- **VTL** — Velocity Template Language used by AWS API Gateway mapping templates.
- **WAF** — Web Application Firewall.
- **xDS (LDS/RDS/CDS/EDS/SDS)** — Envoy's dynamic config APIs.
- **X-Forwarded-For/Proto, Forwarded** — Headers conveying original client IP/scheme through proxies.
- **Zero trust** — Assume hostile network; authenticate/authorize every request.
- **Zuul** — Netflix's Java gateway; SCG's predecessor.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**What:** Managed L7 edge entry point. Reverse proxy + API-aware policy engine.
**7 responsibilities:** Routing · Authn/z · Rate limit/quota · Transformation · Aggregation · TLS termination · Caching. (+LB, retries, circuit breaking, observability.)
**Cardinal rule:** NO business logic in the gateway. Keep it thin and generic.
**Pipeline order:** TLS → route match → normalize → authn → authz → rate limit → cache lookup → LB → upstream (timeout+retry) → response transform → cache store → observe. Auth **before** rate limit (limits are per-consumer).
**Planes:** Data plane = hot path (fast, stateless, caches config). Control plane = config + telemetry (off hot path, eventually consistent). Data plane survives control-plane outage.
**North-south** = client↔system (gateway). **East-west** = service↔service (mesh, mTLS).
**BFF vs gateway:** Gateway = shared generic edge; BFF = per-client aggregation/shaping. Use both; don't aggregate one client's data in the shared gateway.
**HA / not a SPOF:** ≥3 stateless replicas, multi-AZ, behind redundant L4 LB, externalize counters (Redis), N+1 headroom, fail-open where safe, serve stale, shed load (`503`).
**Rate-limit distributed:** per-node = N× overshoot → use Redis. Algorithms: token bucket (bursts), leaky/spike arrest (smooth), sliding-window counter (accurate+cheap).
**JWT:** verify sig via cached JWKS (`kid`), check `exp/nbf/iss/aud`, ~30–60s skew; revocation gap → short TTLs.
**Status codes:** `401` authn, `403` authz, `404` no route, `429` rate limit, `502` bad upstream, `503` no healthy upstream/CB open, `504` upstream timeout.
**Lifecycle:** design (OpenAPI) → mock (Prism) → implement+contract test → publish → portal → keys → analytics → monetization → version/deprecate → retire.
**Gateways:** AWS HTTP API (cheap serverless) / REST API (rich, VTL, usage plans) · Kong (self-host, plugins, DB-less/hybrid) · Apigee (full mgmt/monetization) · Spring Cloud Gateway (Java/reactive library) · Envoy + K8s Gateway API (cloud-native) · KrakenD (aggregation).
**Top anti-patterns:** business logic in gateway · per-node counters · trusting client headers · public Admin API · unbudgeted retries · blocking the event loop · caching private data publicly.
**Top failures:** `503` no healthy upstream · `504` timeout · stale JWKS `401` storm · N× rate overshoot · retry storm · event-loop stall · config propagation lag · single-instance SPOF.
**Java pitfalls (SCG):** never block the event loop; use `WebClient`/reactive; offload unavoidable blocking to `boundedElastic`; detect with BlockHound.
**Latency budget:** L7 gateway ~<1–5ms overhead; Redis limit ~0.2–1ms; external introspection/authz tens of ms (verify your numbers).

### 12.2 Self-test (no answers — active recall)

1. Trace a request end-to-end through a multi-node gateway cluster, naming every pipeline stage in order and identifying which stages can short-circuit and why auth must precede rate limiting.
2. You deploy a gateway with `rate-limiting` set to 100 req/s and scale to 6 replicas; partners report effectively 600 req/s reaching the backend. Diagnose the root cause and give two fixes with their tradeoffs.
3. Explain the difference between a shared API gateway and a BFF, give a concrete example where putting logic in each is correct, and state the litmus test for "this is business logic."
4. Your gateway is the single ingress for all traffic. Enumerate every technique you'd use so it is not a single point of failure, including how you handle a control-plane outage and how much per-AZ headroom you keep with N+1 across two AZs.
5. Walk through JWT validation in the gateway (signature, claims, JWKS, `kid`) and explain the revocation gap and three ways to mitigate it; then contrast local JWT validation with opaque-token introspection on latency and revocability.
6. A release made p99 latency jump from 5ms to 800ms with flat error rate on a Spring Cloud Gateway deployment. Lay out your debugging plan and name the most likely root cause and how you'd prevent it in CI.
7. Compare token bucket, leaky bucket, fixed window, sliding-window log, and sliding-window counter for rate limiting; for each, state one scenario where it's the right choice.
8. Describe the full API management lifecycle from design to retirement, naming a concrete tool or artifact for the design, mock, validation, and credential stages.

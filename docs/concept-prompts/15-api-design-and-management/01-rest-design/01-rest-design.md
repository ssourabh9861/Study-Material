# REST Design — A Definitive Engineering Handbook Chapter

> Reader profile: a senior Java/JVM backend developer who wants to *fully master* REST API design — from first principles to deep internals — well enough to design with it, operate and debug it in production, teach it, and field any interview question on it.

---

## 1. Overview & where it fits

### What "REST" actually is

**REST** stands for **Representational State Transfer**. It is an *architectural style* — a named set of constraints — defined by Roy Fielding in Chapter 5 of his year-2000 PhD dissertation. Crucially, REST is **not** a protocol, **not** a standard, and **not** "JSON over HTTP." It is a description of *why the Web scales*: a set of design constraints that, when satisfied, give a distributed hypermedia system properties like scalability, evolvability, and loose coupling.

> **Architectural style (beginner note):** an architectural style is a reusable, named collection of design constraints. "Client-server" is a style. "Pipe-and-filter" (Unix pipes) is a style. REST is a hybrid style that *layers several constraints* on top of one another. A *concrete architecture* (your actual API) is an *instance* that may or may not satisfy a given style.

The thing most people call "a REST API" is more precisely an **HTTP API** or a **web API**. True, fully-constrained REST (with hypermedia driving the interaction) is rare in practice. This chapter teaches both: the disciplined, pragmatic HTTP-API design that real teams ship, *and* the full REST model so you understand the principles you're trading away.

### The problem REST solves

Before REST-style HTTP APIs dominated, distributed systems leaned on:

- **RPC (Remote Procedure Call):** call a remote function as if it were local. Examples: CORBA, Java RMI, XML-RPC, SOAP, today gRPC. The mental model is *verbs/operations* — `getUser`, `createOrder`, `deactivateAccount`.
  > **RPC (beginner note):** Remote Procedure Call makes a network request look like a normal function call. The downside historically: tight coupling between client and server stubs, brittle versioning, and protocols that ignored HTTP's built-in semantics (caching, status codes, intermediaries).
- **SOAP (Simple Object Access Protocol):** an XML-based messaging protocol, usually over HTTP `POST` only, with a heavy stack (WSDL contracts, WS-* standards for security/transactions). Powerful but verbose and tooling-heavy.
  > **WSDL (beginner note):** Web Services Description Language — an XML document describing a SOAP service's operations, message shapes, and endpoints. Clients generated code from it.

REST's pitch: **stop inventing a new vocabulary per service.** Reuse HTTP's *uniform* vocabulary — a small fixed set of methods (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, …), a shared set of status codes, and a universal addressing scheme (URIs). Model your domain as **resources** (nouns) that those methods act on. The payoff:

1. **Uniform interface** → any client/intermediary understands the semantics without per-service knowledge.
2. **Cacheability** → `GET` responses can be cached by browsers, CDNs, and proxies for free.
3. **Loose coupling & evolvability** → you can add fields and endpoints without breaking clients.
4. **Visibility for intermediaries** → load balancers, gateways, and caches can act on the standard semantics.

### When you reach for REST (vs. alternatives)

- **Reach for REST/HTTP APIs** when you have resource-oriented data, many heterogeneous clients (browsers, mobile, third parties), a public or partner-facing surface, a need for HTTP caching/CDNs, and a desire for broad tooling and low client friction.
- **Reach for gRPC** for high-throughput, low-latency internal service-to-service calls with strict contracts and streaming.
- **Reach for GraphQL** when clients need flexible, client-shaped queries over a graph of related data and you want to avoid over/under-fetching across many screens.

(Full decision framework in §8.)

### The one-paragraph mental model

> Think of your server as a **collection of addressable resources** (a customer, an order, a shopping cart, the *collection* of all orders). Each resource has a stable **URI** (its address). Clients manipulate resources by exchanging **representations** of them (typically JSON or XML documents) using a **uniform, fixed set of HTTP methods** whose meaning is the same everywhere. The server holds the **resource state**; the client holds the **application/session state** and advances it by following links and submitting representations. Everything that makes the Web scale — caching, statelessness, layered intermediaries — falls out of obeying those constraints.

---

## 2. Foundations from first principles

### 2.1 The six REST constraints (built up from zero)

REST is *defined* by constraints. An API earns the label "RESTful" only to the degree it satisfies them. Here they are, each with a beginner-level explanation and the property it buys.

#### (1) Client–Server

Separate the *user-interface concerns* (client) from the *data-storage concerns* (server) via a uniform interface. This separation lets each evolve independently and improves portability of the UI across platforms.

#### (2) Stateless

Each request from client to server must contain **all the information needed to understand it**. The server keeps **no client session state between requests**. Authentication tokens, pagination cursors, and any "where am I" context travel *in the request* (headers, URI, body).

> **Session state vs. resource state (beginner note):** *Resource state* is the durable data the server owns — your user record, your orders. *Application/session state* is "what step of a multi-step interaction this particular client is in." REST says: keep resource state on the server, push session state to the client. So no server-side `HttpSession` keyed by a cookie holding "user is on checkout step 2."

- **Buys:** horizontal scalability (any node can serve any request → trivial load balancing), reliability (a node dying loses no session), visibility (a request is self-describing).
- **Costs:** more data per request (token + context resent each time); some interactions are awkward without server memory.

#### (3) Cacheable

Responses must, implicitly or explicitly, label themselves as **cacheable or non-cacheable**. If cacheable, a client (or intermediary) may reuse the response for later equivalent requests.

> **Cache (beginner note):** a store of previously computed/fetched responses keyed so they can be reused, avoiding recomputation or a network round trip. In HTTP, caches live in browsers, in CDNs (geographically distributed edge caches), and in reverse proxies (e.g., Varnish, nginx).

- **Buys:** dramatically reduced latency and server load.
- **Mechanics:** `Cache-Control`, `Expires`, `ETag`, `Last-Modified` (deep dive in §7.5).

#### (4) Uniform Interface

The defining constraint. It has **four sub-constraints**:

1. **Identification of resources** — resources are named by URIs. A *resource* is any concept worth addressing (a thing, a collection, a computation result).
2. **Manipulation through representations** — clients don't touch the resource directly; they exchange *representations* (a JSON/XML/HTML document plus metadata). A representation captures the resource's current or intended state.
3. **Self-descriptive messages** — each message carries enough metadata (method, `Content-Type`, status, cache headers) to be understood in isolation.
4. **HATEOAS (Hypermedia As The Engine Of Application State)** — responses include **links** that tell the client what it can do next. The client navigates the app by following server-provided links, not by hardcoding URIs.

> **Representation (beginner note):** if a resource is "Order #42," a representation is a concrete document describing it *right now* — e.g., a JSON object `{"id":42,"total":19.99,"status":"SHIPPED"}` with `Content-Type: application/json`. The same resource can have multiple representations (JSON, XML, a PDF invoice).

- **Buys:** generality (one set of rules for everything), decoupling, visibility.
- **Costs:** efficiency — a uniform interface can be less efficient than one tailored to a specific client's exact needs.

#### (5) Layered System

The architecture is composed of **layers**; a component cannot "see" beyond the layer it talks to. A client can't tell whether it's talking to the origin server or to a cache/proxy/gateway in between.

> **Reverse proxy / gateway (beginner note):** a server that sits in front of your origin servers, receiving client requests and forwarding them. It can cache, authenticate, rate-limit, terminate TLS, and load-balance — all transparently. Examples: nginx, Envoy, AWS API Gateway, Kong.

- **Buys:** ability to insert caches, security boundaries, and load balancers without clients knowing.

#### (6) Code-On-Demand (optional)

Servers may extend client functionality by shipping executable code (e.g., JavaScript). This is the **only optional** constraint and is rarely relevant to backend JSON APIs.

> **Mnemonic:** **C**lient-server, **S**tateless, **C**acheable, **U**niform interface, **L**ayered, **C**ode-on-demand. ("CS-CULC" — ugly, but it sticks.)

### 2.2 Core vocabulary

| Term | Plain definition |
|---|---|
| **Resource** | Any addressable concept: an entity (`/users/42`), a collection (`/users`), a computed view (`/reports/2026/revenue`), or even a relationship. |
| **URI** | Uniform Resource Identifier — the resource's name/address, e.g. `https://api.example.com/v1/orders/42`. A **URL** is a URI that also says *how/where* to locate it (scheme + host). |
| **Representation** | A concrete serialization of a resource's state + metadata at a moment in time. |
| **Media type** | A label identifying a representation's format, e.g. `application/json`, `application/xml`, `text/csv`, `application/vnd.api+json`. Sent in `Content-Type`/`Accept`. |
| **Method (verb)** | The action: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`. |
| **Status code** | A 3-digit integer summarizing the result class (2xx success, 3xx redirect, 4xx client error, 5xx server error). |
| **Header** | Key–value metadata on requests/responses (`Authorization`, `Content-Type`, `ETag`, `Cache-Control`). |
| **Idempotent** | An operation that has the *same effect* whether applied once or N times (see §2.4). |
| **Safe** | An operation with *no intended side effects* on server state (read-only). |
| **HATEOAS** | Responses embed links describing available next actions/transitions. |

### 2.3 The Richardson Maturity Model (RMM)

Leonard Richardson described four "levels" of REST adoption. They're a useful *ladder*, not a scorecard to maximize blindly.

| Level | Name | Description | Example |
|---|---|---|---|
| **0** | The Swamp of POX | A single URI, single method (usually `POST`), HTTP as a dumb tunnel. RPC/SOAP-style. | `POST /api` with body `{"op":"getOrder","id":42}` |
| **1** | Resources | Many URIs (one per resource), but still essentially one verb. | `POST /orders/42` for everything about order 42 |
| **2** | HTTP Verbs | Proper use of HTTP methods + status codes. **This is where the vast majority of "REST APIs" live and where most value is.** | `GET /orders/42`, `DELETE /orders/42`, `201 Created` |
| **3** | Hypermedia Controls (HATEOAS) | Responses include links/forms that drive state transitions. The "Glory of REST." | Order response includes `{"_links":{"cancel":{"href":"/orders/42/cancel"}}}` |

> **POX (beginner note):** "Plain Old XML" — XML used as a generic message envelope over `POST`, ignoring HTTP semantics. The "swamp" label signals it's an anti-pattern for web APIs.

**Pragmatic truth:** Level 2 captures ~90% of REST's practical benefits (caching, statelessness, intermediary visibility, tooling). Level 3 (HATEOAS) is the academically "true REST" but is rarely adopted (§7.3 explains why). Don't let RMM purism distract from shipping a clean Level-2 API.

### 2.4 Safety and Idempotency (precise definitions — interviewers love these)

- **Safe method:** the request is read-only by intent; it must not modify resource state. (Logging/metrics don't count as "modification.") **Safe ⊂ Idempotent.**
- **Idempotent method:** issuing the request once or many times yields the same *server state*. The *response* may differ (e.g., second `DELETE` returns `404`), but the resulting state is identical.

| Method | Safe? | Idempotent? | Cacheable? | Has request body? | Typical use |
|---|---|---|---|---|---|
| `GET` | ✅ | ✅ | ✅ | No (ignored) | Read a resource/collection |
| `HEAD` | ✅ | ✅ | ✅ | No | Like GET but headers only |
| `OPTIONS` | ✅ | ✅ | No | No | Discover allowed methods / CORS preflight |
| `POST` | ❌ | ❌ (by default) | Only if explicitly fresh | Yes | Create subordinate / non-idempotent action |
| `PUT` | ❌ | ✅ | No | Yes | Create-or-replace at a known URI |
| `PATCH` | ❌ | ❌ (not required) | No | Yes | Partial update |
| `DELETE` | ❌ | ✅ | No | Optional | Remove resource |

**Why idempotency matters operationally:** networks fail mid-request. A client that doesn't get a response may **retry**. If the method is idempotent, retrying is safe. `POST` (create) is *not* idempotent → a naive retry can create duplicate orders. Solution: **idempotency keys** (§5.5, §7.2).

> **Cacheable nuance:** `POST` responses *can* technically be cached if explicitly marked fresh (`Cache-Control`), but in practice almost never are. `GET`/`HEAD` are the cacheable workhorses.

---

## 3. How it works internally — the request lifecycle, step by step

This section traces what *actually happens* end-to-end. Understanding this is what separates "I can annotate a controller" from "I can design and debug an API."

### 3.1 The anatomy of an HTTP request/response

A request:

```
GET /v1/orders/42?fields=id,total HTTP/1.1      ← request line: method, target, version
Host: api.example.com                            ← required in HTTP/1.1
Accept: application/json                          ← desired representation media type
Authorization: Bearer eyJhbGciOi...              ← credentials (self-describing → stateless)
If-None-Match: "a1b2c3"                           ← conditional request (caching)
                                                  ← blank line ends headers
                                                  ← (GET has no body)
```

A response:

```
HTTP/1.1 200 OK                                   ← status line
Content-Type: application/json                     ← representation format
ETag: "a1b2c3"                                     ← version tag for caching/concurrency
Cache-Control: private, max-age=60                 ← caching policy
Content-Length: 57
                                                  ← blank line
{"id":42,"total":19.99,"status":"SHIPPED"}        ← representation (the body)
```

> **Request target (beginner note):** the part after the method — usually the path + query (`/v1/orders/42?fields=...`). The full URI is reconstructed with `Host`.

### 3.2 The full path of a `GET /v1/orders/42` (control + data flow)

1. **DNS resolution.** Client resolves `api.example.com` → IP. (DNS = the phonebook of the internet.)
2. **TCP + TLS handshake.** A reliable connection is established; TLS negotiates encryption.
   > **TLS (beginner note):** Transport Layer Security encrypts the connection and authenticates the server via certificates. "HTTPS" = HTTP over TLS.
3. **Request travels through the layered system.** It may hit, in order: a **CDN edge** → an **API gateway / reverse proxy** → a **load balancer** → an **app server**. Each is a *layer* the client can't see past.
4. **Cache lookup (if cacheable).** A CDN/proxy may check whether it holds a fresh cached representation for this URI. If fresh → returns immediately (origin never touched). If stale but it holds an `ETag` → it may send a **conditional request** (`If-None-Match`) to the origin (see §7.5).
5. **Gateway concerns.** TLS termination, authentication, rate limiting, request validation, routing.
6. **App framework dispatch.** In a Java/Spring app: the servlet container (Tomcat/Jetty/Undertow) accepts the connection, `DispatcherServlet` matches the route to a handler method via `@GetMapping("/v1/orders/{id}")`, binds `id=42` and query params, runs filters/interceptors (auth, logging).
   > **Servlet container (beginner note):** the runtime (e.g., Tomcat) that turns raw HTTP into Java `HttpServletRequest`/`HttpServletResponse` objects and manages a thread (or virtual thread) per request.
7. **Business logic.** The controller calls a service → repository → database. Resource state is read.
8. **Representation construction.** The result object is serialized to JSON (Jackson, in Spring). An `ETag` may be computed (hash of the body or a version column). Cache headers set.
9. **Response travels back out** through the same layers; intermediaries may *store* it in cache per `Cache-Control`.
10. **Client deserializes** the representation and (if RMM Level 3) reads `_links` to know what to do next.

### 3.3 The "state machine" of an application (HATEOAS view)

In full REST, the *client's progress through the application is a state machine*, and **the server transmits the available transitions as links** in each response. The client starts at a single entry point and follows links — it never constructs URIs from out-of-band knowledge.

Example flow for an order:

```
GET /                → links: { orders: /orders }
GET /orders          → links: { create: POST /orders }, items: [...]
POST /orders         → 201, Location: /orders/42, links: { self, addItem, checkout }
POST /orders/42/items→ links: { ..., checkout: /orders/42/checkout }
POST /orders/42/checkout → links: { pay: /orders/42/payment }   (checkout now valid)
```

Notice the *cancel* link appears only while the order is cancellable, and the *pay* link appears only after checkout. The server encodes the legal state transitions; the client just follows what's offered. This is what "hypermedia as the engine of application state" means concretely.

### 3.4 Content negotiation internally

When a client sends `Accept: application/json, application/xml;q=0.8`, the server runs **proactive (server-driven) content negotiation**:

1. Parse `Accept` into media ranges with quality values (`q`, default `1.0`).
2. Compute the set of representations the endpoint can produce.
3. Choose the best match by `q` and specificity.
4. If none acceptable → `406 Not Acceptable`. If chosen → set `Content-Type` and `Vary: Accept`.

> **`q` value (beginner note):** a "quality"/preference weight from 0 to 1 in `Accept`/`Accept-Language` headers. `q=0.8` means "I'll take this, but I prefer the `q=1.0` option."
> **`Vary` header (beginner note):** tells caches which request headers affect the response, so they don't serve a JSON representation to a client that asked for XML. Critical for correctness with content negotiation.

### 3.5 Conditional request lifecycle (caching/concurrency engine)

This is the mechanism behind both **HTTP caching** and **optimistic concurrency**:

1. Server returns a resource with `ETag: "v7"` (an opaque version tag).
2. **Cache validation:** later, client/cache sends `GET ... If-None-Match: "v7"`. If unchanged → server returns `304 Not Modified` with no body (cheap). If changed → `200` + new body + new `ETag`.
3. **Optimistic locking:** client sends `PUT ... If-Match: "v7"`. If current ETag is still `"v7"` → apply. If it changed (someone else updated) → `412 Precondition Failed`, preventing a lost update.

> **Optimistic concurrency (beginner note):** instead of locking a row while a user edits, you let everyone read freely and detect conflicts at write time via a version tag. "Optimistic" because you assume conflicts are rare. The alternative is *pessimistic* locking (hold a lock), which scales worse.

---

## 4. The complete toolkit

### 4.1 HTTP methods — purpose, semantics, defaults

| Method | Purpose | Request body | Success status | Key rules |
|---|---|---|---|---|
| `GET` | Retrieve a representation | No | `200`, `206` (partial), `304` | Safe, idempotent, cacheable. Never mutate. |
| `HEAD` | Retrieve headers only (size, ETag, existence) | No | `200`, `304` | Identical to GET minus body. Cheap existence/metadata check. |
| `POST` | Create subordinate resource; run a non-idempotent process | Yes | `201` (created, with `Location`), `200`/`202` | Not safe, not idempotent. The "catch-all" for actions that don't fit other verbs. |
| `PUT` | Create-or-**replace** the resource at a *known* URI with the full representation | Yes | `200`/`204` (replaced), `201` (created) | Idempotent. Client supplies the entire new state. |
| `PATCH` | **Partial** modification | Yes | `200`/`204` | Not necessarily idempotent or safe. Body is a *patch document*, not the full resource (§5.4). |
| `DELETE` | Remove the resource | Optional | `204` (no content) or `200` (with body) | Idempotent (2nd delete → `404`, but state same). |
| `OPTIONS` | Discover communication options (allowed methods; CORS preflight) | No | `200`/`204` | Response uses `Allow:` header. |

> **PUT vs POST for creation:** Use `PUT` when the *client* chooses the URI (e.g., `PUT /files/my-key`). Use `POST` to a collection when the *server* assigns the ID (`POST /orders` → server returns `/orders/42`). `PUT` to a collection URI is wrong.

### 4.2 Status codes that matter (and common misuses)

#### 2xx — Success
| Code | Meaning | Use when |
|---|---|---|
| `200 OK` | Generic success with body | GET success; PUT/PATCH returning updated resource |
| `201 Created` | New resource created | After POST/PUT creation. **Must** include `Location:` header pointing to it. |
| `202 Accepted` | Accepted for async processing, not done yet | Long-running jobs; return a status URI |
| `204 No Content` | Success, no body | DELETE, or PUT/PATCH when you return nothing |
| `206 Partial Content` | Range request satisfied | Byte-range downloads, resumable transfers |

#### 3xx — Redirection / caching
| Code | Meaning | Use when |
|---|---|---|
| `301 Moved Permanently` | Resource moved | Permanent URI change |
| `304 Not Modified` | Cached copy still valid | Response to `If-None-Match`/`If-Modified-Since` |
| `307 Temporary Redirect` | Temporary, **method preserved** | Redirect a POST without it becoming a GET |
| `308 Permanent Redirect` | Permanent, method preserved | Like 301 but keeps method |

#### 4xx — Client errors
| Code | Meaning | Use when | Common misuse |
|---|---|---|---|
| `400 Bad Request` | Malformed/invalid request | Syntactic errors, validation failures (if you don't use 422) | Used as a catch-all for everything |
| `401 Unauthorized` | **Not authenticated** | Missing/invalid credentials | Confused with 403 |
| `403 Forbidden` | Authenticated but **not authorized** | Valid identity, insufficient permission | Used when you mean 401 |
| `404 Not Found` | Resource doesn't exist (or hidden) | Unknown URI; sometimes deliberately for "exists but you can't see it" | — |
| `405 Method Not Allowed` | Method not supported on this URI | `DELETE` on a read-only resource. **Must** send `Allow:` | Returning 404 instead |
| `406 Not Acceptable` | Can't produce an acceptable representation | Failed `Accept` negotiation | — |
| `409 Conflict` | Request conflicts with current state | Duplicate creation, edit conflict | — |
| `410 Gone` | Resource deliberately removed, permanently | Deprecated/deleted-forever resources | — |
| `412 Precondition Failed` | `If-Match`/`If-Unmodified-Since` failed | Optimistic-lock conflict | — |
| `415 Unsupported Media Type` | Body `Content-Type` not supported | Client sent XML to a JSON-only endpoint | Confused with 406 |
| `422 Unprocessable Entity` | Syntax OK, **semantics** invalid | Validation errors (business rules) | Overused where 400 fits |
| `428 Precondition Required` | Server requires a conditional request | Force `If-Match` to prevent lost updates | — |
| `429 Too Many Requests` | Rate limited | Throttling. Send `Retry-After` | — |

#### 5xx — Server errors
| Code | Meaning | Use when |
|---|---|---|
| `500 Internal Server Error` | Generic server fault | Unhandled exceptions (don't leak stack traces) |
| `502 Bad Gateway` | Upstream returned invalid response | Proxy/gateway failures |
| `503 Service Unavailable` | Temporarily down/overloaded | Maintenance, shed load. Send `Retry-After` |
| `504 Gateway Timeout` | Upstream timed out | Slow downstream dependency |

**Top misuses to avoid:**
- Returning `200 OK` with `{"error": "..."}` in the body (the "200-always" anti-pattern). Breaks every intermediary that relies on status codes.
- `401` vs `403` swapped: 401 = *who are you?* (authn), 403 = *I know you, you can't* (authz).
- `400` for everything. Distinguish `400` (malformed), `401/403` (auth), `404` (missing), `409` (conflict), `415` (wrong media type), `422` (validation).
- `404` instead of `405` when the URI exists but the method doesn't.

### 4.3 Essential headers

| Header | Direction | Purpose |
|---|---|---|
| `Accept` | Req | Desired response media type(s) |
| `Content-Type` | Both | Media type of the *body* |
| `Authorization` | Req | Credentials (`Bearer <token>`, `Basic ...`) |
| `Location` | Resp | URI of newly created/redirected resource |
| `ETag` | Resp | Opaque version identifier for cache/concurrency |
| `If-None-Match` / `If-Match` | Req | Conditional on ETag |
| `Last-Modified` / `If-Modified-Since` / `If-Unmodified-Since` | Resp/Req | Conditional on timestamp |
| `Cache-Control` | Both | Caching directives (`max-age`, `no-store`, `private`, …) |
| `Vary` | Resp | Which request headers affect the response (cache key) |
| `Retry-After` | Resp | Seconds (or date) to wait (429/503) |
| `Allow` | Resp | Methods allowed on the URI (405/OPTIONS) |
| `Link` | Resp | Hypermedia/pagination links (RFC 8288) |
| `Idempotency-Key` | Req | Client-supplied dedup key for safe POST retries (convention) |
| `Accept-Language` / `Content-Language` | Both | Localization negotiation |

### 4.4 Cache-Control directives

| Directive | Meaning |
|---|---|
| `max-age=N` | Fresh for N seconds |
| `s-maxage=N` | Like max-age but for *shared* caches (CDN/proxy) only |
| `no-cache` | May cache, but **must revalidate** with origin before use |
| `no-store` | Never store (sensitive data) |
| `private` | Only browser may cache, not shared caches |
| `public` | Any cache may store (even with auth headers) |
| `must-revalidate` | Once stale, must revalidate (no serving stale on error) |
| `stale-while-revalidate=N` | Serve stale up to N s while fetching fresh in background |
| `immutable` | Content never changes; skip revalidation entirely |

### 4.5 Java/Spring toolkit (the practical APIs)

| Tool/annotation | Purpose | Notes |
|---|---|---|
| `@RestController` | Marks a controller; combines `@Controller` + `@ResponseBody` | Spring MVC / Spring Boot |
| `@GetMapping` / `@PostMapping` / `@PutMapping` / `@PatchMapping` / `@DeleteMapping` | Bind method + path | Specializations of `@RequestMapping` |
| `@RequestMapping(produces=, consumes=)` | Constrain by `Accept`/`Content-Type` | Drives content negotiation & 406/415 |
| `@PathVariable` / `@RequestParam` | Bind path segments / query params | — |
| `@RequestBody` / `@ResponseBody` | (De)serialize body via Jackson | — |
| `ResponseEntity<T>` | Full control of status, headers, body | Set `201`+`Location`, `ETag`, etc. |
| `@ExceptionHandler` / `@ControllerAdvice` | Centralized error mapping | Map exceptions → status codes (RFC 9457 problem+json) |
| `@Valid` + Bean Validation (`jakarta.validation`) | Declarative request validation | Hook to return `400`/`422` |
| `ShallowEtagHeaderFilter` | Auto-compute ETags from body | Spring built-in servlet filter |
| `ResponseEntity.ok().eTag(...)` / `.lastModified(...)` | Set validators | Pairs with `request.checkNotModified(...)` |
| `WebClient` / `RestClient` / `RestTemplate` | HTTP clients | `RestClient`/`WebClient` modern; `RestTemplate` legacy |
| `springdoc-openapi` | Generate OpenAPI/Swagger UI | De-facto for Spring Boot 3 |
| JAX-RS (`@Path`, `@GET`, `Response`) | The Jakarta REST standard (Jersey/RESTEasy) | Alternative to Spring MVC |

> **OpenAPI / Swagger (beginner note):** OpenAPI is a language-agnostic specification (YAML/JSON) describing your HTTP API — paths, methods, schemas, responses. "Swagger" was the original name and now refers to the tooling (Swagger UI, codegen). It's the contract that powers docs, client SDK generation, and mock servers.

---

## 5. Code examples by use case

All examples are **Spring Boot 3 / Java 21** unless noted. They're trimmed to the load-bearing parts but adapt cleanly.

### 5.1 A clean collection + item controller (RMM Level 2)

```java
@RestController
@RequestMapping(path = "/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {

    private final OrderService orders;
    OrderController(OrderService orders) { this.orders = orders; }

    // GET /v1/orders/42 → 200 with body, 404 if missing
    @GetMapping("/{id}")
    public OrderDto getOne(@PathVariable long id) {
        return orders.findById(id)               // throws NotFoundException → mapped to 404 (see 5.6)
                     .orElseThrow(() -> new NotFoundException("order", id));
    }

    // POST /v1/orders → 201 Created + Location header (server assigns the id)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderDto> create(@Valid @RequestBody CreateOrderRequest req) {
        OrderDto created = orders.create(req);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();   // /v1/orders/42
        return ResponseEntity.created(location).body(created); // 201 + Location
    }

    // PUT /v1/orders/42 → full replace; idempotent
    @PutMapping("/{id}")
    public ResponseEntity<OrderDto> replace(@PathVariable long id,
                                            @Valid @RequestBody ReplaceOrderRequest req) {
        boolean existed = orders.exists(id);
        OrderDto saved = orders.replace(id, req);
        return existed ? ResponseEntity.ok(saved)            // 200 replaced
                       : ResponseEntity.created(             // 201 created via PUT
                             URI.create("/v1/orders/" + id)).body(saved);
    }

    // DELETE /v1/orders/42 → 204; idempotent
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        orders.delete(id);                       // no-op if already gone → still 204
        return ResponseEntity.noContent().build();
    }
}
```

Why it's idiomatic: `201`+`Location` on create, `204` on delete, distinct create/replace semantics for `PUT`, validation via `@Valid`, and no business logic leaking into the controller.

### 5.2 Filtering, sorting, field selection, and pagination

```java
// GET /v1/orders?status=SHIPPED&minTotal=10&sort=-createdAt,total
//                &fields=id,total,status&page[cursor]=eyJ...&page[size]=50
@GetMapping
public ResponseEntity<PageDto<OrderDto>> list(
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(required = false) BigDecimal minTotal,
        @RequestParam(name = "sort", required = false) String sortSpec,   // "-createdAt,total"
        @RequestParam(name = "fields", required = false) String fields,    // sparse fieldset
        @RequestParam(name = "page[cursor]", required = false) String cursor,
        @RequestParam(name = "page[size]", defaultValue = "50") @Max(200) int size) {

    var query = OrderQuery.builder()
            .status(status).minTotal(minTotal)
            .sort(SortSpec.parse(sortSpec))      // map "-x" → DESC, "x" → ASC; whitelist columns!
            .cursor(cursor).size(size).build();

    CursorPage<OrderDto> page = orders.search(query, FieldMask.parse(fields));

    // Cursor pagination: opaque cursor avoids the deep-offset performance cliff
    var body = new PageDto<>(page.items(), page.nextCursor());
    var resp = ResponseEntity.ok();
    if (page.nextCursor() != null) {
        // Standard hypermedia pagination via Link header (RFC 8288)
        resp.header(HttpHeaders.LINK,
            "<%s?page[cursor]=%s&page[size]=%d>; rel=\"next\""
              .formatted(basePath(), page.nextCursor(), size));
    }
    return resp.body(body);
}
```

Key design rules shown:
- **Filtering** via query params (`status`, `minTotal`). Whitelist allowed filters; never `eval` user input into SQL.
- **Sorting** with a compact `sort=-createdAt,total` convention (`-` = descending). **Whitelist** sortable columns to avoid injection and unindexed scans.
- **Sparse fieldsets** via `fields=id,total` to reduce payload (JSON:API style).
- **Cursor (keyset) pagination** over offset pagination.
  > **Offset vs cursor pagination (beginner note):** *Offset* (`LIMIT 50 OFFSET 100000`) makes the DB skip 100k rows every time — O(n) and prone to skipping/duplicating rows when data changes. *Cursor/keyset* pagination remembers "the last row I saw" (e.g., `WHERE (created_at, id) < (?, ?) ORDER BY ... LIMIT 50`) — O(log n) with an index, and stable under inserts.

### 5.3 Conditional GET (ETag) for caching + bandwidth savings

```java
@GetMapping("/{id}")
public ResponseEntity<OrderDto> getWithEtag(@PathVariable long id, WebRequest request) {
    Order order = orders.entity(id).orElseThrow(() -> new NotFoundException("order", id));
    String etag = "\"" + order.getVersion() + "\"";   // JPA @Version column → cheap, strong ETag

    // If client's If-None-Match matches → Spring returns 304 and we short-circuit
    if (request.checkNotModified(order.getVersion())) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate())
            .body(toDto(order));
}
```

The first response sends `ETag` + `Cache-Control`. Subsequent requests send `If-None-Match`; unchanged → `304` with **no body** (saves bandwidth, still confirms freshness).

### 5.4 Partial update: PATCH — JSON Patch vs JSON Merge Patch

Two competing standards:

| Aspect | JSON Patch (RFC 6902) | JSON Merge Patch (RFC 7386) |
|---|---|---|
| Media type | `application/json-patch+json` | `application/merge-patch+json` |
| Shape | Array of operations | A partial object |
| Example | `[{"op":"replace","path":"/total","value":20}]` | `{"total":20}` |
| Expressiveness | High: `add/remove/replace/move/copy/test` | Low: set fields; `null` = delete; can't target array elements |
| Null semantics | Explicit `remove` op | `null` **means delete the field** (can't set null!) |
| `test` op | Yes → optimistic checks in the patch itself | No |
| Best for | Precise, scriptable edits; arrays | Simple "change these fields" updates |

**JSON Merge Patch example:**
```java
@PatchMapping(path = "/{id}", consumes = "application/merge-patch+json")
public OrderDto mergePatch(@PathVariable long id, @RequestBody JsonNode patch) {
    Order current = orders.entity(id).orElseThrow(() -> new NotFoundException("order", id));
    JsonNode merged = JsonMergePatch.fromJson(patch)
                                    .apply(mapper.valueToTree(current)); // null → field removed
    Order updated = mapper.treeToValue(merged, Order.class);
    return toDto(orders.save(validate(updated)));
}
```

**JSON Patch example (using `com.github.java-json-tools:json-patch` or `jakarta.json`):**
```java
@PatchMapping(path = "/{id}", consumes = "application/json-patch+json")
public OrderDto jsonPatch(@PathVariable long id, @RequestBody JsonNode patchOps) {
    JsonNode original = mapper.valueToTree(
        orders.entity(id).orElseThrow(() -> new NotFoundException("order", id)));
    try {
        JsonNode patched = JsonPatch.fromJson(patchOps).apply(original); // applies ops in order
        Order updated = mapper.treeToValue(patched, Order.class);
        return toDto(orders.save(validate(updated)));
    } catch (JsonPatchException e) {
        throw new UnprocessableEntityException("invalid patch: " + e.getMessage()); // 422
    }
}
```

**Pitfall:** A plain `{"total":20}` body sent to a `PATCH` endpoint is ambiguous — is it merge patch or a custom partial-update format? Declare the media type (`consumes`) explicitly so clients can't guess wrong; return `415` for the wrong one.

### 5.5 Idempotent POST via an idempotency key (safe retries)

```java
@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<OrderDto> createIdempotent(
        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
        @Valid @RequestBody CreateOrderRequest req) {

    if (idemKey == null) throw new BadRequestException("Idempotency-Key header required");

    // Atomically reserve the key; if it exists, return the stored prior response.
    Optional<StoredResponse> prior = idempotencyStore.lookup(idemKey, req.fingerprint());
    if (prior.isPresent()) {
        StoredResponse r = prior.get();           // replay identical result → no duplicate order
        return ResponseEntity.status(r.status()).body(r.bodyAs(OrderDto.class));
    }

    OrderDto created = orders.create(req);
    URI location = URI.create("/v1/orders/" + created.id());
    idempotencyStore.store(idemKey, req.fingerprint(), 201, created, Duration.ofHours(24));
    return ResponseEntity.created(location).body(created);
}
```

The store (Redis/DB) makes `POST` *effectively* idempotent for 24h: a client that retries after a timeout gets the original `201` and the same order, not a duplicate. Stripe, PayPal, and most payment APIs work exactly this way.

### 5.6 Centralized error handling with RFC 9457 `application/problem+json`

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://errors.example.com/not-found"));
        pd.setTitle("Resource not found");
        pd.setProperty("resource", ex.resource());
        pd.setProperty("id", ex.id());
        return pd;   // Spring serializes as application/problem+json
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY); // 422
        pd.setTitle("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
            .toList());
        return pd;
    }
}
```

> **RFC 9457 (`problem+json`) (beginner note):** a standard JSON shape for HTTP error bodies (`type`, `title`, `status`, `detail`, `instance`, plus custom members). It replaces every team inventing its own `{"error":...}` format and is machine-parseable. (It obsoletes RFC 7807, same shape.)

### 5.7 Content negotiation: serve JSON and CSV from one endpoint

```java
@GetMapping(value = "/{id}",
    produces = { MediaType.APPLICATION_JSON_VALUE, "text/csv" })
public ResponseEntity<?> getNegotiated(@PathVariable long id,
                                       @RequestHeader HttpHeaders headers) {
    OrderDto dto = orders.findById(id).orElseThrow(() -> new NotFoundException("order", id));
    List<MediaType> accepts = headers.getAccept();
    if (accepts.stream().anyMatch(m -> m.includes(MediaType.valueOf("text/csv")))) {
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/csv"))
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)   // critical for cache correctness
            .body(toCsv(dto));
    }
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT).body(dto);
}
```

### 5.8 HATEOAS with Spring HATEOAS (RMM Level 3, when you actually want it)

```java
@GetMapping("/{id}")
public EntityModel<OrderDto> getHypermedia(@PathVariable long id) {
    OrderDto dto = orders.findById(id).orElseThrow(() -> new NotFoundException("order", id));
    EntityModel<OrderDto> model = EntityModel.of(dto);
    model.add(linkTo(methodOn(OrderController.class).getHypermedia(id)).withSelfRel());
    if (dto.status() == OrderStatus.PENDING) {
        // The 'cancel' affordance appears ONLY when the state allows it
        model.add(linkTo(methodOn(OrderController.class).cancel(id)).withRel("cancel"));
        model.add(linkTo(methodOn(OrderController.class).checkout(id)).withRel("checkout"));
    }
    return model;   // → produces application/hal+json with "_links"
}
```

> **HAL (beginner note):** Hypertext Application Language — a simple convention (`application/hal+json`) for embedding links (`_links`) and nested resources (`_embedded`) in JSON. Other hypermedia formats: JSON:API, Siren, Collection+JSON.

### 5.9 Long-running operation (async) with `202 Accepted`

```java
@PostMapping("/{id}/exports")
public ResponseEntity<Void> startExport(@PathVariable long id) {
    String jobId = exportService.start(id);                 // enqueue async work
    URI statusUri = URI.create("/v1/jobs/" + jobId);
    return ResponseEntity.accepted()                         // 202
            .location(statusUri)                             // poll here for status
            .build();
}
// Client polls GET /v1/jobs/{jobId} → 200 {status:"RUNNING"} ... then 303 See Other → result
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Lean on HTTP caching.** A correct `ETag` + `Cache-Control` lets CDNs/browsers absorb read traffic. `304` responses are tiny. This is the single highest-leverage performance lever in REST.
- **Avoid offset pagination at scale** (see §5.2). Use keyset/cursor pagination.
- **Avoid N+1 and over-fetching.** Sparse fieldsets and well-shaped DTOs reduce payload; but watch for the *under-fetching* problem (clients making 10 calls to assemble a screen) — that's where GraphQL or purpose-built aggregate endpoints (BFF) win.
  > **BFF (beginner note):** Backend-For-Frontend — a thin API layer tailored to one client (mobile/web) that aggregates calls to underlying services, reducing chattiness.
- **Compression:** enable `gzip`/`br` (`Accept-Encoding`). Set `Vary: Accept-Encoding`.
- **Connection reuse / HTTP/2:** multiplexing reduces head-of-line blocking; keep-alive avoids handshake costs.
- **Payload size:** prefer cursor over embedding huge collections; cap `page[size]`.

### 6.2 Correctness & concurrency

- **Use `ETag` + `If-Match` for optimistic concurrency** to prevent lost updates (the classic "two users edit the same record" bug). Map a stale precondition to `412`.
- **Make writes idempotent where possible.** `PUT`/`DELETE` are inherently idempotent; protect `POST` with idempotency keys (§5.5).
- **Be precise with status codes** (§4.2). Intermediaries and clients branch on them.
- **Validate at the edge** with Bean Validation; reject early with `400`/`422`.

### 6.3 Security

- **Always TLS.** Never plain HTTP for APIs. Consider HSTS.
- **Authentication:** `Authorization: Bearer <JWT/opaque token>` (OAuth2/OIDC). Never put secrets in the URI (URIs land in logs, caches, browser history).
  > **JWT (beginner note):** JSON Web Token — a signed, self-contained token carrying claims (subject, scopes, expiry). Stateless: the server verifies the signature without a DB lookup. Trade-off: hard to revoke before expiry.
- **Authorization** per resource/action (`403` when denied). Don't leak existence — sometimes return `404` instead of `403` for resources the caller can't see.
- **Input validation & injection:** whitelist filter/sort fields; never interpolate into SQL; cap sizes; reject unknown JSON fields if strict.
- **Mass assignment guard:** don't bind request bodies straight onto entities (a client could set `isAdmin:true`). Bind to explicit request DTOs.
- **Rate limiting / quotas:** return `429` + `Retry-After`. Protects against abuse and accidental retry storms.
- **CORS** for browser clients; configure `OPTIONS` preflight correctly.
  > **CORS (beginner note):** Cross-Origin Resource Sharing — browsers block cross-site JS calls unless the server opts in via `Access-Control-Allow-*` headers. The browser may first send an `OPTIONS` "preflight" to check.
- **Don't leak internals** in `500` bodies (no stack traces / SQL).
- **`Cache-Control: no-store`** on sensitive responses so they never hit shared caches.

### 6.4 Observability

- **Structured logging** with a correlation/trace ID propagated via headers (`traceparent` — W3C Trace Context).
- **Distributed tracing** (OpenTelemetry): span per request, propagated across services.
- **Metrics (RED):** Rate, Errors, Duration per endpoint + status-code histograms.
- **Log the right cardinality:** route templates (`/orders/{id}`) not raw URIs, to avoid metric explosion.

### 6.5 Cost

- Caching reduces compute and egress. CDN offload directly lowers origin cost.
- Chatty clients multiply request count → consider aggregate/BFF endpoints.
- Egress bandwidth is real money on cloud — compression and sparse fieldsets pay off.

### 6.6 Testability

- **Contract tests** against the OpenAPI spec (e.g., Spring Cloud Contract, Pact) catch breaking changes.
  > **Consumer-driven contract testing (beginner note):** consumers publish the shape they depend on; the provider's CI verifies it still satisfies that contract — catching breaks before deploy.
- **`@WebMvcTest` / `MockMvc` / `WebTestClient`** for controller-layer tests asserting status codes, headers, and body shape.
- **Snapshot/golden tests** for response payloads.

### 6.7 Production hardening checklist

- Versioning strategy decided up front (§7.4).
- Consistent error format (`problem+json`).
- Pagination caps, timeouts, and circuit breakers on downstreams.
- Idempotency on mutating endpoints exposed to retries.
- Health/readiness endpoints (`/healthz`) — these aren't "resources," and that's fine.
- Deprecation policy + `Deprecation`/`Sunset` headers (RFC 8594) for retiring endpoints.

### 6.8 Anti-patterns to avoid

- **Verbs in URIs:** `/getOrder?id=42`, `/createUser`. The method *is* the verb. (Exception: controller/action sub-resources like `/orders/42/checkout` are pragmatic and widely accepted.)
- **`200 OK` for errors** (the "always 200" anti-pattern).
- **Tunneling everything through `POST`** (RMM Level 0/1).
- **Exposing DB schema/IDs verbatim** and binding bodies onto entities (mass assignment).
- **Inconsistent plurals/casing:** mixing `/order` and `/Orders` and `/order_items`. Pick one (plural nouns, kebab-or-snake consistently).
- **Chatty designs** forcing dozens of calls per screen.
- **Putting auth tokens / PII in query strings.**
- **Ignoring `Vary`** with content negotiation → caches serve the wrong representation.
- **Using `GET` with side effects** (breaks safety; prefetchers/crawlers will trigger them).

---

## 7. Advanced topics & deep internals

### 7.1 Resource modeling at depth

- **Resources are nouns, not tables.** A resource may aggregate several tables or be a computed view (`/accounts/42/statement?month=2026-05`).
- **Sub-resources & relationships:** `/users/42/orders` (orders *of* user 42). Decide whether a relationship is its own resource (`/memberships/99`) or an attribute.
- **Singleton resources:** `/users/me/settings` — a single resource without a collection.
- **Controller resources (actions that aren't CRUD):** when an operation isn't a clean create/replace/delete — e.g., "checkout," "publish," "merge" — model it as a sub-resource the client `POST`s to: `POST /orders/42/checkout`. This is the pragmatic escape hatch from "everything must be CRUD." It's not *pure* REST but it's universally accepted.
- **Composite/batch operations:** `POST /batch` or `POST /orders:batchCreate` (Google AIP style) for bulk; weigh against breaking idempotency and partial-failure semantics (use `207 Multi-Status` from WebDAV if you must report per-item results).
- **URI design rules:** lowercase, hyphen-separated multiword segments (`/purchase-orders`), plural collection nouns, no file extensions (use `Accept`), no trailing slash inconsistency, stable IDs (prefer opaque IDs/UUIDs over leaking sequential DB keys which expose volume and enable enumeration).

### 7.2 Idempotency internals

True idempotency requires the *server* to make retries safe:
- `PUT`/`DELETE`: naturally idempotent because they describe a *target end state*.
- `POST` create: store `(idempotency-key, request-fingerprint) → response` atomically. The atomic step (e.g., Redis `SET NX` or a unique DB constraint) is what prevents a race between two concurrent retries. Fingerprint the request body so the same key with a *different* body is rejected (`422`/`409`) rather than silently replaying the wrong result.
- TTL the keys (commonly 24h) and persist the *response* so the replay is byte-identical.

### 7.3 HATEOAS — why it's powerful but rarely used

The promise: clients discover capabilities at runtime via links, so the server can move URIs and change available actions without breaking clients (extreme decoupling and evolvability). Reality check on adoption:

- **Most clients are written against fixed docs/SDKs**, not generic hypermedia agents. They hardcode URIs anyway, so the decoupling benefit goes unrealized.
- **No universal link semantics:** there's no agreed-upon machine vocabulary for *what* `rel="cancel"` means; a human still codes the behavior, so the "self-describing" promise is partial.
- **Cost:** larger payloads, more server complexity, harder caching.
- **Where it *does* pay off:** long-lived APIs with many independent third-party clients and frequently changing workflows; state-machine-heavy domains (payments, fulfillment) where exposing valid transitions reduces client bugs; PayPal, GitHub (partial), and some banking APIs use it meaningfully.

**Bottom line:** ship Level 2 cleanly; add hypermedia selectively (e.g., pagination `Link` headers and a handful of action affordances) rather than going full HAL everywhere.

### 7.4 Designing for evolvability & versioning

Goal: add capability without breaking existing clients.

**Non-breaking changes (safe to make without a new version):**
- Adding new endpoints/resources.
- Adding *optional* request fields.
- Adding new response fields (clients must ignore unknowns — the **"tolerant reader"** principle).
- Adding new enum values *only if clients are told to tolerate unknowns* (otherwise breaking!).

**Breaking changes (need versioning/migration):**
- Removing/renaming fields; changing types; tightening validation; changing status-code semantics; changing URI structure.

**Versioning strategies:**

| Strategy | Example | Pros | Cons |
|---|---|---|---|
| URI path | `/v1/orders` | Obvious, cache-friendly, easy routing | "v1/v2" violates "one URI per resource" purism; proliferates URIs |
| Header / media type | `Accept: application/vnd.example.v2+json` | URIs stay stable; pure | Harder to test in a browser; cache must `Vary`; less discoverable |
| Query param | `/orders?version=2` | Simple | Messes with caching; easy to forget |

**Pragmatic recommendation:** URI path versioning for major versions (it's what teams actually operate well), combined with additive evolution within a version. Communicate deprecation with `Deprecation` and `Sunset` headers (RFC 8594) and ample lead time.

> **Tolerant reader (beginner note):** a client coded to ignore fields it doesn't recognize and not break when extra data appears. Pairs with the server principle "be conservative in what you send." This is the backbone of REST evolvability.

### 7.5 HTTP caching deep dive

Two cache models:

1. **Freshness (no round trip):** `Cache-Control: max-age=N` (or `Expires`). Within N seconds the cache serves the stored copy without contacting the origin.
2. **Validation (cheap round trip):** when stale, the cache revalidates with `If-None-Match: <etag>` / `If-Modified-Since: <date>`. Origin replies `304` (reuse stored copy) or `200` (new copy).

**ETag flavors:**
- **Strong ETag** (`"abc"`): byte-for-byte identical. Required for range requests and `If-Match` concurrency.
- **Weak ETag** (`W/"abc"`): semantically equivalent but maybe not byte-identical (e.g., whitespace differs). OK for validation, not for ranges/optimistic locking.

**Where to source the ETag:** a row `@Version` column (cheapest, monotonic), a content hash (e.g., SHA-256 of the canonical body), or `updated_at` timestamp (use `Last-Modified` for that; 1-second granularity caveat).

**Shared vs private:** `private` (browser only — for per-user data); `public`/`s-maxage` (CDN may store — for shared/anonymous data). Sensitive data → `no-store`.

**`stale-while-revalidate`** lets a CDN serve a slightly stale copy instantly while fetching a fresh one in the background — great latency win for read-heavy endpoints.

**Cache key correctness:** the cache key includes URI + the headers named in `Vary`. Forget `Vary: Accept` and your XML clients get JSON. Auth-varying responses must be `private` or `no-store` (a shared cache must never serve user A's data to user B).

### 7.6 Range requests & partial content

`Range: bytes=0-1023` → `206 Partial Content` with `Content-Range`. Powers resumable downloads and video seeking. Server advertises support with `Accept-Ranges: bytes`. Requires strong ETags for safe resume across versions.

### 7.7 Content negotiation dimensions

Beyond `Accept`/`Content-Type`: `Accept-Language` ↔ `Content-Language`, `Accept-Encoding` ↔ `Content-Encoding`, `Accept-Charset` (deprecated, UTF-8 assumed). Each adds to `Vary`. *Agent-driven* negotiation (server returns `300 Multiple Choices` with options) exists but is essentially unused.

### 7.8 Lesser-known behaviors

- `DELETE` and `PUT` *may* carry bodies but many proxies strip/ignore them. Avoid relying on a `GET` body entirely (HTTP/1.1 says servers should ignore it; HTTP semantics RFC 9110 advises against).
- `POST` is the only method with no idempotency/safety guarantee *and* it can technically be cacheable — but in practice treat it as uncacheable.
- `201` without `Location` is technically allowed but a smell — always include where a URI exists.
- `204` must have **no body**; some clients choke on a body with `204`.
- A `405` *must* include `Allow:`; a `Vary`-less negotiated response is a caching bug, not a syntax error (so it's silent and nasty).

---

## 8. Tradeoffs & decision frameworks

### 8.1 REST vs gRPC vs GraphQL

| Dimension | REST (HTTP/JSON) | gRPC | GraphQL |
|---|---|---|---|
| Transport | HTTP/1.1 or 2, text | HTTP/2, binary (Protobuf) | Usually HTTP/1.1, JSON, single `POST /graphql` |
| Contract | OpenAPI (optional, looser) | `.proto` (strict, codegen) | SDL schema (strict) |
| Shape | Resource/noun-oriented | RPC/verb-oriented | Client-shaped graph queries |
| Caching | Native HTTP caching (huge plus) | Hard (binary, POST) | Hard (one URL, POST) — needs client cache layers |
| Browser support | Native | Needs gRPC-Web proxy | Native |
| Streaming | SSE / chunked (limited) | First-class bidi streaming | Subscriptions (over WS) |
| Over/under-fetching | Possible (mitigate w/ fieldsets) | N/A (tailored RPCs) | Solved by design |
| Tooling/ubiquity | Highest | High (internal) | Growing |
| Best fit | Public/partner APIs, web, cache-heavy reads | Internal microservices, low-latency, streaming | Aggregating many entities for rich UIs |

**Use REST when:** public/third-party surface, heterogeneous clients, HTTP caching/CDN matters, resource-oriented data, want maximum tooling/low client friction.
**Avoid/relax REST when:** ultra-low-latency internal calls (→ gRPC), or clients need flexible cross-entity queries to avoid chatty round trips (→ GraphQL), or the domain is fundamentally action/streaming-oriented.

### 8.2 PUT vs PATCH

| Use `PUT` when | Use `PATCH` when |
|---|---|
| Client has/sends the full resource | Client wants to change a few fields |
| You want idempotency guaranteed | Partial updates, possibly complex (JSON Patch ops) |
| Replace semantics are correct | Merge semantics are correct |

Avoid `PATCH` if your clients can't agree on a patch media type — a clean `PUT` is sometimes simpler.

### 8.3 Pagination strategies

| Strategy | Use when | Avoid when |
|---|---|---|
| Cursor/keyset | Large/changing datasets, infinite scroll | You need "jump to page 500" |
| Offset/limit | Small, stable datasets; need random page access | Deep pages, high write rate |
| Page-number | Human-facing tables with page jumps | Same caveats as offset |

### 8.4 Versioning decision (recap)

Default to **URI path versioning + additive evolution within a version**; use media-type versioning only if URI stability is a hard requirement and your clients/caches are sophisticated.

---

## 9. Failure modes & debugging

### 9.1 Common production failures and diagnosis

| Symptom | Likely cause | How to diagnose | Fix |
|---|---|---|---|
| Clients see stale/wrong-format data from CDN | Missing `Vary` or wrong `Cache-Control` | `curl -I` the URL through the CDN; inspect `Cache-Control`, `Vary`, `Age` | Add `Vary: Accept` (+ encoding); mark per-user data `private`/`no-store` |
| Duplicate orders/charges | Non-idempotent `POST` + client retries on timeout | Correlate duplicates by user+timestamp; check client retry logic | Idempotency keys (§5.5) |
| Lost updates (overwrites) | No optimistic concurrency | Reproduce with two concurrent `PUT`s | `ETag` + `If-Match` → `412` |
| Intermittent `502/504` | Upstream timeout / overload | Trace IDs + gateway logs; latency histograms | Timeouts, retries (idempotent only!), circuit breakers |
| `429` storms then cascade | Retry storm without backoff | Inspect `Retry-After`, request rate spikes | Exponential backoff + jitter; honor `Retry-After` |
| Slow listing endpoint over time | Offset pagination deep scans | DB `EXPLAIN`; look for large `OFFSET` | Switch to keyset pagination + index |
| `401` loops | Token refresh race / clock skew | Decode JWT `exp`/`iat`; check NTP | Fix clock; refresh-token logic |
| Mass-assignment privilege escalation | Binding body → entity | Audit which fields are bindable | Use explicit request DTOs |

### 9.2 Tools & commands

```bash
# Inspect headers/status without body
curl -sS -D - -o /dev/null https://api.example.com/v1/orders/42 -H 'Accept: application/json'

# Test conditional GET → expect 304
curl -i https://api.example.com/v1/orders/42 -H 'If-None-Match: "v7"'

# Test optimistic concurrency → expect 412 if stale
curl -i -X PUT https://api.example.com/v1/orders/42 \
     -H 'If-Match: "v7"' -H 'Content-Type: application/json' -d @order.json

# Verify CORS preflight
curl -i -X OPTIONS https://api.example.com/v1/orders \
     -H 'Origin: https://app.example.com' \
     -H 'Access-Control-Request-Method: POST'

# See full negotiation / timing / TLS
curl -v --compressed https://api.example.com/v1/orders/42
```

Other tools: **HTTPie** (`http GET ...` ergonomic), **Postman/Insomnia** (collections, contract testing), **mitmproxy/Charles** (intercepting proxy to see what really goes over the wire), **wireshark/tcpdump** (packet level), **OpenTelemetry + Jaeger/Tempo** (traces), **Grafana** (RED dashboards), **`ab`/`wrk`/`k6`** (load testing). In Java: enable Spring's `logging.level.web=DEBUG`, `ShallowEtagHeaderFilter` to validate ETag behavior, and actuator `/httptrace` (or Micrometer Observation) for request insight.

### 9.3 Real-world incident patterns

- **The duplicate-charge incident:** mobile client on flaky network times out, retries the `POST /payments`, server creates a second charge. Root cause: no idempotency key. Fix: mandatory `Idempotency-Key`, dedup store. (This is *the* canonical payments-API lesson; Stripe's idempotency design exists precisely for this.)
- **The CDN-poisoning incident:** an endpoint returned per-user data with `Cache-Control: public` (copy-pasted config). A shared CDN cached user A's account page and served it to user B. Fix: `private`/`no-store` for authenticated, user-specific responses; never `public` with `Authorization`.
- **The deep-pagination meltdown:** an analytics export paged with `OFFSET` to page 200,000; DB scanned hundreds of millions of rows, p99 latency spiked, connections exhausted. Fix: keyset pagination.
- **The enum-break incident:** server added a new `status: "REFUNDED"`; a strict client deserializer threw on the unknown enum, crashing the app. Fix: tolerant readers; treat unknown enums as a default/"other."
- **The `200`-always trap:** an API returned `200` with `{"error":...}`; a CDN cached the "error" as a success and a monitoring system reported 100% availability during an outage. Fix: real status codes.

---

## 10. Interview drill

**Q1. What is REST, precisely — and is "JSON over HTTP" REST?**
*Model answer:* REST is an architectural style defined by six constraints (client-server, stateless, cacheable, uniform interface, layered system, optional code-on-demand). "JSON over HTTP" is necessary-ish but not sufficient; you're RESTful to the degree you satisfy the constraints, especially the uniform interface. Most "REST APIs" are really HTTP APIs at RMM Level 2.
- *Probe: Which constraint is most often violated?* Statelessness (server sessions) and HATEOAS (no hypermedia).
- *Probe: Which constraint is optional?* Code-on-demand.
- *Probe: What property does statelessness buy and at what cost?* Horizontal scalability/reliability; cost is re-sending context each request.

**Q2. Explain idempotency and safety with examples; which methods are which?**
*Model answer:* Safe = no intended state change (`GET`, `HEAD`, `OPTIONS`). Idempotent = same end state for 1 or N calls (`GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE`). `POST` and `PATCH` are neither guaranteed. Matters for safe retries.
- *Probe: Is `DELETE` idempotent if the second call returns 404?* Yes — *state* is identical; the differing response doesn't break idempotency.
- *Probe: How do you make `POST` idempotent?* Idempotency keys + atomic dedup store, fingerprint the body, TTL the keys, replay the stored response.
- *Probe: Why can't you just retry any failed request?* Non-idempotent ops (create/charge) can duplicate effects.

**Q3. 401 vs 403 vs 404 — when each?**
*Model answer:* 401 = not authenticated (who are you?); 403 = authenticated but not authorized (you can't); 404 = not found. Sometimes return 404 instead of 403 to avoid leaking existence.
- *Probe: Client sent a valid token but lacks permission?* 403.
- *Probe: Expired token?* 401 (re-authenticate).

**Q4. Walk me through HTTP caching with ETags.**
*Model answer:* Freshness (`max-age`) avoids round trips; validation (`If-None-Match`/ETag → `304`) makes round trips cheap. Strong vs weak ETags; `Vary` for negotiation; `private` vs `public`; source ETag from a version column or content hash.
- *Probe: How does the same ETag enable optimistic concurrency?* `If-Match` on writes → `412` on mismatch, preventing lost updates.
- *Probe: What breaks if you omit `Vary`?* Caches serve the wrong representation (e.g., JSON to an XML client).

**Q5. PUT vs PATCH, and JSON Patch vs Merge Patch?**
*Model answer:* PUT replaces the whole resource (idempotent); PATCH partially updates. JSON Patch (RFC 6902) is an op array (`application/json-patch+json`), expressive incl. arrays/test; Merge Patch (RFC 7386, `application/merge-patch+json`) is a partial object where `null` deletes a field.
- *Probe: How do you set a field to null with Merge Patch?* You can't — `null` means delete; use JSON Patch `replace` with `value:null`.
- *Probe: Is PATCH idempotent?* Not required to be; Merge Patch usually is, JSON Patch with `add` to an array is not.

**Q6. Design pagination for a large, frequently-updated dataset.**
*Model answer:* Keyset/cursor pagination over an indexed `(sort_key, id)` tuple; opaque cursor; `Link: rel="next"`. Avoids offset's O(n) scan and instability under inserts.
- *Probe: Trade-off vs offset?* Can't jump to arbitrary page; only next/prev.
- *Probe: How to make cursors tamper-resistant?* Sign/encrypt them.

**Q7 (senior-signal). When would you NOT use REST, and what would you choose instead — justify.**
*Model answer:* For low-latency internal service-to-service with strict contracts and streaming → gRPC (binary Protobuf, HTTP/2 multiplexing, codegen). For rich UIs needing flexible cross-entity fetches to kill over/under-fetching → GraphQL. REST wins for public/partner APIs, heterogeneous clients, and cache-heavy reads (native HTTP caching/CDN). The deciding factors: caching needs, client diversity, contract strictness, latency, and chattiness.
- *Probe: Why is caching hard in GraphQL/gRPC?* Single `POST` endpoint / binary bodies defeat URL-keyed HTTP caches; you push caching into the client/app layer.
- *Probe: Could you mix them?* Yes — REST at the edge, gRPC internally, GraphQL BFF for one client.

**Q8 (senior-signal). How do you evolve a public API without breaking clients?**
*Model answer:* Additive, backward-compatible changes within a version (new optional fields/endpoints), tolerant-reader clients, URI path versioning for breaking majors, deprecation via `Deprecation`/`Sunset` headers with lead time, and contract tests in CI. Treat tightening validation/removing fields/changing types as breaking.
- *Probe: Is adding an enum value safe?* Only if clients tolerate unknowns; otherwise it's breaking.
- *Probe: Path vs header versioning trade-off?* Path is operable/cacheable/discoverable but proliferates URIs; header/media-type keeps URIs stable but complicates caching/testing.

**Q9 (senior-signal). HATEOAS sounds great in theory — why is it rarely used, and when is it worth it?**
*Model answer:* Payoff is runtime discoverability + extreme decoupling, but most clients hardcode against docs/SDKs and there's no universal machine semantics for link relations, so the benefit isn't realized; it adds payload size, server complexity, and caching difficulty. Worth it for long-lived APIs with many third-party clients and state-machine-heavy workflows (payments/fulfillment) where exposing valid transitions reduces client bugs.
- *Probe: A cheap, real-world hypermedia win that *is* common?* Pagination `Link` headers and a few action affordances — partial hypermedia.

**Q10. What status code and headers for creating a resource via POST, and why?**
*Model answer:* `201 Created` + `Location:` pointing to the new resource; optionally return the representation in the body. The `Location` lets the client GET the canonical resource.
- *Probe: If creation is async?* `202 Accepted` + a status URI (`Location` to a job resource).
- *Probe: PUT-based creation?* `201` if created, `200/204` if replaced — same `Location` semantics when newly created.

**Q11. How do you handle errors in a REST API at scale?**
*Model answer:* Real HTTP status codes + a consistent machine-readable body (`application/problem+json`, RFC 9457: `type/title/status/detail/instance` + custom members), centralized exception mapping (`@ControllerAdvice`), no leaked internals, include a correlation/trace ID.
- *Probe: 400 vs 422?* 400 = malformed/syntactic; 422 = syntactically valid but semantically invalid (business validation).
- *Probe: Why not 200 with an error body?* Breaks intermediaries, monitoring, and client error handling.

**Q12. Explain the layered-system constraint and what it enables operationally.**
*Model answer:* Components only know their adjacent layer; clients can't tell origin from proxy. Enables transparent caches, gateways (TLS, authn, rate limiting), and load balancers — improving scalability and security without client changes.
- *Probe: A downside?* Added latency per hop; harder end-to-end debugging (mitigate with trace propagation).

---

## 11. Glossary

- **Affordance:** in hypermedia, a server-offered action (link/form) telling the client what it can do next.
- **API Gateway:** a managed entry-point proxy handling routing, auth, rate limiting, TLS termination.
- **BFF (Backend-For-Frontend):** a client-specific API layer that aggregates downstream calls.
- **Bean Validation:** the Jakarta standard (`@NotNull`, `@Size`, …) for declarative validation in Java.
- **Cache:** store of reusable prior responses; lives in browsers, CDNs, reverse proxies.
- **CDN (Content Delivery Network):** geographically distributed edge caches/servers.
- **Conditional request:** a request guarded by `If-None-Match`/`If-Match`/`If-Modified-Since` enabling `304`/`412`.
- **Content negotiation:** picking a representation format/language/encoding via `Accept*` headers.
- **CORS:** browser mechanism letting servers opt into cross-origin JS requests.
- **CRUD:** Create, Read, Update, Delete — the basic data operations, loosely mapping to POST/GET/PUT-PATCH/DELETE.
- **Cursor (keyset) pagination:** paging by "last seen key" instead of numeric offset.
- **DTO (Data Transfer Object):** an explicit request/response object decoupled from persistence entities.
- **ETag:** opaque resource version tag for caching and optimistic concurrency.
- **Evolvability:** ability to change an API without breaking existing clients.
- **Freshness:** the period during which a cached response can be used without revalidation.
- **gRPC:** a high-performance RPC framework over HTTP/2 using Protobuf.
- **GraphQL:** a query language/runtime letting clients request exactly the data shape they need.
- **HAL:** Hypertext Application Language; a JSON hypermedia format (`_links`, `_embedded`).
- **HATEOAS:** Hypermedia As The Engine Of Application State; links drive client transitions.
- **HSTS:** HTTP Strict Transport Security; forces HTTPS via a response header.
- **HTTP method/verb:** GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS.
- **Idempotency:** same end state for one or many identical requests.
- **Idempotency key:** client-supplied header to dedup retried POSTs.
- **JAX-RS / Jakarta REST:** the Java standard API for REST services (Jersey, RESTEasy).
- **JSON Merge Patch (RFC 7386):** partial-object patch; `null` deletes a field.
- **JSON Patch (RFC 6902):** array-of-operations patch document.
- **JWT:** signed, self-contained token of claims for stateless auth.
- **Last-Modified:** timestamp validator for conditional requests.
- **Layered system:** REST constraint allowing transparent intermediaries.
- **Media type (MIME type):** format label like `application/json`.
- **Optimistic concurrency:** detect write conflicts via version checks instead of locking.
- **OpenAPI/Swagger:** spec + tooling describing an HTTP API.
- **Origin server:** the authoritative server holding the resource (behind any proxies/caches).
- **Pessimistic locking:** holding a lock during edits to prevent conflicts.
- **POX:** Plain Old XML used as a dumb message envelope (RMM Level 0).
- **Problem Details (RFC 9457/7807):** standard JSON error body (`application/problem+json`).
- **Proactive (server-driven) negotiation:** server picks representation from `Accept`.
- **Protobuf:** binary serialization format used by gRPC.
- **Representation:** a concrete serialized snapshot of a resource + metadata.
- **Resource:** any addressable concept identified by a URI.
- **REST:** Representational State Transfer — the architectural style.
- **Reverse proxy:** a server in front of origins (cache/auth/LB/TLS).
- **Richardson Maturity Model (RMM):** levels 0–3 grading REST adoption.
- **RPC:** Remote Procedure Call — verb/operation-oriented remote calls.
- **Safe method:** read-only by intent (no state change).
- **Servlet container:** Java runtime (Tomcat/Jetty/Undertow) handling HTTP ↔ Java request objects.
- **SOAP:** XML messaging protocol, usually over POST, with WSDL contracts.
- **Sparse fieldset:** `fields=` parameter limiting returned attributes.
- **Stateless:** server keeps no per-client session between requests.
- **Strong/Weak ETag:** byte-identical vs semantically-equivalent version tags.
- **Tolerant reader:** a client that ignores unknown fields and tolerates additions.
- **TLS:** Transport Layer Security; encryption + server authentication (HTTPS).
- **Uniform interface:** the defining REST constraint with four sub-constraints.
- **URI/URL:** Uniform Resource Identifier/Locator — a resource's name/address.
- **`Vary` header:** lists request headers that affect the response (cache key correctness).
- **WSDL:** XML description of a SOAP service.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Constraints:** Client-Server · Stateless · Cacheable · Uniform Interface (IDs/representations/self-descriptive/HATEOAS) · Layered · Code-on-demand (optional).
**RMM:** L0 POX swamp → L1 Resources → **L2 Verbs+status (ship here)** → L3 Hypermedia.
**Methods:** GET (safe, idempotent, cacheable) · HEAD · OPTIONS · PUT (idempotent, replace) · DELETE (idempotent) · POST (neither) · PATCH (partial, not guaranteed).
**Status mnemonics:** 200 OK · 201 Created (+Location) · 202 Accepted · 204 No Content · 304 Not Modified · 400 malformed · 401 authn · 403 authz · 404 missing · 405 (+Allow) · 409 conflict · 412 precondition failed · 415 bad media type · 422 validation · 429 (+Retry-After) · 500/503.
**Caching:** `Cache-Control: max-age` (freshness) + `ETag`/`If-None-Match` → `304` (validation). `Vary: Accept`. `private` for per-user, `no-store` for secrets.
**Concurrency:** `ETag` + `If-Match` → `412` prevents lost updates.
**Idempotent POST:** `Idempotency-Key` + atomic dedup store + replay stored response.
**PATCH:** JSON Patch (`json-patch+json`, op array) vs Merge Patch (`merge-patch+json`, `null`=delete).
**Pagination:** prefer cursor/keyset; cap page size; `Link: rel="next"`.
**Errors:** real status + `application/problem+json` (RFC 9457).
**Versioning:** URI path `/v1` + additive evolution; tolerant readers; `Deprecation`/`Sunset`.
**Decision:** REST for public/cache-heavy/heterogeneous; gRPC for internal/low-latency/streaming; GraphQL for flexible client-shaped queries.
**Anti-patterns:** verbs in URIs · 200-for-errors · POST-tunneling · mass assignment · ignoring `Vary` · GET with side effects · offset pagination at scale · auth tokens in URLs.

### Self-test (no answers — active recall)

1. A client edits a record, another client edited it 2 seconds earlier. Exactly which headers and status code make the first client's write fail safely instead of silently overwriting — and where does the validator value come from?
2. You add a new field to a response and a strict client crashes. Which REST principle did the *client* violate, and which kind of change (breaking vs non-breaking) was the *server's*?
3. Design the request/response for "checkout an order" that isn't a clean CRUD operation. What URI, method, and status codes do you use, and why isn't this "pure REST"?
4. Your CDN starts serving one user's account data to another user. Name two distinct misconfigurations that could cause this and the fix for each.
5. Distinguish `400` vs `422` vs `415` with a concrete example request that triggers each.
6. Explain how the *same* ETag value serves both HTTP caching and optimistic concurrency, naming the request header used in each case and the status code returned on the "no work needed" / "conflict" outcomes.
7. When would you deliberately return `404` instead of `403`, and what's the security rationale?

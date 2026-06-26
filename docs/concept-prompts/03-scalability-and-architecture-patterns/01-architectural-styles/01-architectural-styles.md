# Architectural Styles

> A definitive engineering-handbook chapter on the major architectural styles for backend systems — monolith, modular monolith, microservices, event-driven, and serverless — built from first principles up to deep internals, decomposition strategy, migration patterns, and decision frameworks. Examples default to the Java/JVM ecosystem.

---

## 1. Overview & where it fits

An **architectural style** is a *family of constraints* on how you partition a system into runtime and deployment units, how those units communicate, where the data lives, and how the whole thing is operated. It is the highest-leverage decision you make: it shapes your team structure, your release cadence, your failure modes, your cloud bill, and the cost of every future change. Picking the wrong style is one of the most expensive mistakes in software, because the structure tends to outlive the people who chose it.

The word *style* is deliberate. It is not a *pattern* (a reusable solution to a recurring local problem, like the Strangler Fig or Saga) and not a *topology* (the literal box-and-arrow diagram of one deployment). A style is a set of assumptions about *granularity* (how big are the pieces), *coupling* (how tightly do they depend on each other), and *deployment unit boundaries* (what ships and scales together). Everything else — frameworks, languages, databases — is downstream of the style.

**The problem it solves.** Every nontrivial system must be split so that humans can understand it, teams can work on it in parallel, and machines can run it within latency, throughput, and cost budgets. The tension is between two opposing forces:

- **Cohesion / simplicity** — keeping related things together so a developer can reason locally, change atomically, and call a function instead of a network. This pushes toward the *monolith*.
- **Independent evolution / scale / isolation** — letting teams ship, scale, and fail independently. This pushes toward *distribution* (microservices, event-driven, serverless).

The architectural style is the lever you set between these poles, and the central insight of this chapter is that distribution is *not free* — it converts in-process method calls (nanoseconds, transactional, type-checked at compile time) into network calls (milliseconds, partially-failing, schema-coupled at runtime). You buy organizational and operational independence by paying a tax in latency, consistency, and operational complexity.

**When you reach for each (one-line mental model):**

| Style | One-line mental model | Reach for it when |
|---|---|---|
| **Monolith** | One deployable, one process, one database, in-process calls. | Small team, early product, unknown domain boundaries, you value velocity over independent scaling. |
| **Modular monolith** | One deployable, but with hard internal module boundaries (enforced, not just folders). | You want monolith simplicity *and* clean boundaries that could later become services. The default modern starting point. |
| **Microservices** | Many small, independently-deployable services, each owning its data, communicating over the network. | Multiple teams need independent release cadence; parts of the system have wildly different scaling/availability needs; the org structure already mirrors the desired boundaries. |
| **Event-driven** | Components communicate by emitting/consuming events asynchronously through a broker; loose temporal coupling. | You need decoupling in *time*, high write throughput, audit/replay, or fan-out to many consumers. Often layered *on top of* microservices. |
| **Serverless (FaaS)** | Stateless functions triggered by events, scaled to zero, billed per-invocation, run by a cloud provider. | Spiky/unpredictable load, glue/integration code, event processing, low baseline traffic where paying-per-use beats paying-for-idle. |

**The mental model in one paragraph.** Start with the smallest number of moving parts that lets your team ship. That is almost always a **modular monolith**: a single deployable with strictly enforced internal boundaries. As organizational and load pressures grow, *selectively* extract the modules that have genuinely independent scaling, availability, or team-ownership needs into services — keeping the rest in the monolith. Use **events** wherever you need temporal decoupling or fan-out, and **serverless** for spiky, stateless, or glue workloads. The style is not a religion; it is a dial you turn per-capability, and Conway's Law (your architecture will mirror your communication structure whether you like it or not) means you must co-design the org and the architecture.

---

## 2. Foundations from first principles

Before comparing styles we must define the vocabulary precisely, because most architectural arguments are actually arguments about undefined terms.

### 2.1 The atoms: process, deployable, service, module

- **Process** — an OS-level unit of execution with its own virtual address space, file descriptors, and threads. Two pieces of code *in the same process* share memory and can call each other as ordinary function calls (nanoseconds, no serialization, no partial failure). Two pieces in *different processes* must communicate via IPC or the network.
- **Deployable (deployment unit / artifact)** — the thing you ship and start as a unit: a JAR/WAR, a container image, a Lambda zip. The number of independent deployables is the single best proxy for "how distributed" a style is.
- **Module** — a *logical* unit of code with a defined public interface and hidden internals. A module is a compile-time/source-time concept; it may or may not be its own deployable. In Java this can be a Maven/Gradle subproject, a JPMS (Java Platform Module System) module, or an enforced package boundary.
- **Service** — a deployable that exposes a network-addressable API and (in the microservices sense) owns its data. "Service" implies a *runtime* boundary; "module" implies a *code* boundary. The whole microservices-vs-monolith debate is largely about whether module boundaries should also be service boundaries.

> **Beginner aside — IPC (Inter-Process Communication):** mechanisms by which separate processes exchange data: pipes, Unix domain sockets, shared memory, or — across machines — TCP/IP networking. The key point: anything crossing a process boundary must be *serialized* (turned into bytes) and *deserialized*, and can *partially fail* (the other side may be down or slow). In-process calls have neither cost.

### 2.2 Coupling and cohesion — the master concepts

Almost every architectural judgment reduces to managing **coupling** and **cohesion**.

- **Cohesion** — how strongly the things *inside* a boundary belong together. High cohesion = a module does one well-defined job. A `Billing` module that handles invoices, payments, and refunds is cohesive; one that also handles user avatars is not.
- **Coupling** — how strongly two boundaries depend on each other. We distinguish several kinds because they have very different costs:
  - **Afferent / efferent coupling** — how many things depend *on* you (afferent, Ca) vs how many you depend *on* (efferent, Ce). High afferent coupling means you are hard to change without breaking others.
  - **Temporal coupling** — caller and callee must both be available *at the same time*. Synchronous HTTP couples temporally; an async message does not (the consumer can be down and catch up later).
  - **Data/schema coupling** — two parties share a data representation (a shared database table, a wire schema). Changing it requires coordinated change. This is the silent killer of microservices done wrong.
  - **Deployment coupling** — you cannot deploy A without also deploying B. A monolith has total deployment coupling by definition.

The design goal is universal: **maximize cohesion inside a boundary, minimize coupling across boundaries.** Styles differ only in *where they draw the boundary* and *what kind of coupling they trade away.*

### 2.3 Why distribution is fundamentally hard — the Fallacies and CAP

When you split a system across the network, you inherit a body of hard constraints. Two canonical lists:

**The Fallacies of Distributed Computing** (originally articulated at Sun Microsystems, attributed to L. Peter Deutsch and James Gosling, ~1994–97). These are assumptions naïve designers make that are *false*:

1. The network is reliable. (It isn't — packets drop, links flap.)
2. Latency is zero. (A LAN round-trip is ~0.5 ms; cross-region can be 50–150 ms.)
3. Bandwidth is infinite.
4. The network is secure.
5. Topology doesn't change.
6. There is one administrator.
7. Transport cost is zero. (Serialization/CPU/egress all cost money.)
8. The network is homogeneous.

Every one of these is a class of production incident. The monolith *avoids the entire list* by keeping calls in-process — which is exactly why "just make it a monolith" is so often the senior answer.

**CAP theorem** (Eric Brewer, 2000; formalized by Gilbert & Lynch, 2002): in the presence of a network **P**artition (some nodes can't talk to others), a distributed data system must choose between **C**onsistency (every read sees the latest write) and **A**vailability (every request gets a non-error response). You cannot have all three *during a partition*. Because partitions are unavoidable in real networks, the real choice is CP (refuse writes to stay consistent) vs AP (accept writes, reconcile later). Single-node monoliths sidestep CAP for their own data because there is no partition within one process.

> **Beginner aside — partition:** a network split where group A of nodes can reach each other but not group B, and vice-versa. Each side sees the other as "down." The danger is *split-brain*: both sides accept writes and diverge.

> **Beginner aside — PACELC:** an extension of CAP (Daniel Abadi, 2012): *if* there is a **P**artition, trade **A**vailability vs **C**onsistency; **E**lse (normal operation) trade **L**atency vs **C**onsistency. It captures that even with no partition, strong consistency costs latency (e.g., waiting for quorum acknowledgments).

### 2.4 Conway's Law — the law you cannot repeal

> "Any organization that designs a system … will produce a design whose structure is a copy of the organization's communication structure." — Melvin Conway, 1968.

In plain terms: **your architecture will end up shaped like your org chart**, because module interfaces are negotiated across team boundaries and teams optimize their own surface. Three corollaries every architect must internalize:

- If you have one team, you will tend to build one cohesive unit — a monolith — and fighting that with microservices creates a *distributed monolith* (the worst of both).
- If you have five autonomous teams, you will get five services whether you planned them or not; the boundaries will follow team boundaries, not necessarily domain boundaries.
- **The Inverse Conway Maneuver**: deliberately *restructure the teams* to match the architecture you want, then let Conway's Law produce that architecture. This is how organizations *intentionally* move to microservices: they form small, full-stack, independently-deployable teams first.

### 2.5 Synchronous vs asynchronous, request/response vs event

- **Synchronous request/response** — caller sends a request and *blocks* (or awaits) until a response returns. Temporally coupled. Simple to reason about; failure of the callee is immediately the caller's problem. Examples: REST/HTTP, gRPC, in-process method call.
- **Asynchronous messaging** — caller hands a message to a broker and moves on; the receiver processes it later. Temporally decoupled. The broker buffers, retries, and fans out. Examples: Kafka, RabbitMQ, SQS.
- **Commands vs events** — a *command* is an instruction to do something ("ChargeCard"); it has one logical owner and may be rejected. An *event* is a statement that something *happened* ("CardCharged"); it has zero or many consumers and cannot be rejected (it's history). Event-driven architecture is built on events, which is why it decouples producers from the *number and identity* of consumers.

### 2.6 Data ownership — the real boundary

The most underappreciated first principle: **a service boundary is a data ownership boundary.** In true microservices, *each service owns its own database and no other service may touch it directly* ("database per service"). This is what makes services independently deployable — you can change your schema without coordinating. The moment two services share a table, they are deployment-coupled and you have a distributed monolith with extra latency.

This single rule generates most of the *cost* of microservices: you can no longer use a single ACID transaction across the system, no longer JOIN across business capabilities, and must instead use sagas, eventual consistency, and data duplication. Sections 4, 6, and 7 unpack these.

> **Beginner aside — ACID:** the guarantees of a classic relational transaction. **A**tomicity (all-or-nothing), **C**onsistency (constraints hold before/after), **I**solation (concurrent transactions don't interfere), **D**urability (committed data survives crashes). A monolith on one database gets ACID across the whole system for free. Distribute the data and you lose cross-service atomicity.

---

## 3. How it works internally

This section walks through what actually happens *under the hood* in each style: the control flow of a request, the data flow, the lifecycle of a deployment, and the failure semantics. This is the heart of the chapter.

### 3.1 The Monolith — internal workflow

**Definition.** A single deployable artifact (one JAR/WAR/container) running as one process (or N identical replicas of that same process behind a load balancer), with all business capabilities compiled together and typically one shared database.

**Request lifecycle (e.g., a Spring Boot monolith handling `POST /orders`):**

1. **Ingress.** A load balancer / reverse proxy routes the HTTP request to one of the replicas. All replicas are identical, so routing is stateless (or sticky only for sessions).
2. **Thread assignment.** The embedded servlet container (Tomcat by default in Spring Boot) takes a thread from its worker pool (`server.tomcat.threads.max`, default **200**) to handle the request. (On Project Loom / virtual threads, see §7.)
3. **Dispatch.** The framework's front controller (Spring's `DispatcherServlet`) matches the URL to a `@RestController` method.
4. **In-process call chain.** The controller calls a `OrderService`, which calls `InventoryService`, `PricingService`, `PaymentGateway` — **all ordinary Java method calls in the same heap.** No serialization, no network, nanoseconds each, full stack traces, compile-time type safety.
5. **Single transaction.** A `@Transactional` boundary opens one JDBC transaction on the shared database. Inventory decrement, order insert, and ledger write all commit *atomically*. If anything throws, the whole thing rolls back. This is the monolith's superpower.
6. **Response & return.** The response object serializes once at the edge; the worker thread returns to the pool.

**Data flow.** All modules read/write the same database, often sharing tables and doing JOINs freely. Caching is in-process (a `ConcurrentHashMap` or Caffeine cache) and trivially coherent within one replica (but *not* across replicas — see §6).

**Deployment lifecycle.** Build one artifact → run tests against the whole thing → deploy all replicas (rolling, blue/green, or canary). A change to *any* line redeploys *everything*. Build and startup time grow with the codebase; a large monolith can take many minutes to build and tens of seconds to start, which slows the feedback loop.

**Failure semantics.** Failure is largely *all-or-nothing per replica*: if the process crashes (OOM, fatal bug), that replica is gone but its peers carry on. There is **no partial failure between modules** — a bug in `Reporting` can, however, take down the whole process (e.g., a memory leak in one module exhausts the shared heap and kills `Orders` too). This *blast radius coupling* is the monolith's chief structural weakness.

**State machine of a deployment:** `Build → Test (whole) → Stage → Roll out replicas → Healthy / Rollback`. There is exactly one pipeline.

### 3.2 The Modular Monolith — internal workflow

**Definition.** Physically a monolith (one deployable, one process, often one database) but logically partitioned into **modules with enforced boundaries**: each module exposes a small public API and hides its internals; cross-module calls go only through those APIs; ideally each module owns its own schema/tables and does not reach into another's.

**What "enforced" means (the crux).** A folder structure is *not* a module boundary — nothing stops a developer from importing across it. Enforcement requires a mechanism:

- **Build modules** — separate Maven/Gradle subprojects so module B simply cannot `import` module A's internal classes unless A declares them on its API artifact.
- **JPMS** — Java's module system (`module-info.java`) with explicit `exports`; the compiler and runtime reject access to non-exported packages.
- **ArchUnit tests** — a JUnit-based library that asserts architectural rules ("nothing in `billing.internal` may be accessed from `catalog`") and fails the build on violation.
- **Spring Modulith** — a Spring framework that defines modules by top-level packages, verifies boundaries, and even lets modules communicate via in-process application events that can later become real messages.

**Request lifecycle.** Identical to the monolith at the HTTP/thread level, *but* the call chain crosses module APIs only. `OrderModule` does not call `new InventoryRepository()`; it calls `inventoryApi.reserve(...)`. Internally this is still a method call (fast, transactional), so you keep the monolith's performance and ACID transactions while gaining the *option* to later replace `inventoryApi` with a remote client.

**Why this is the modern default.** It defers the irreversible decision. You get clean boundaries (so the code stays comprehensible and extraction is cheap) without paying the network/ops tax until a specific module *earns* extraction. Sam Newman and others now widely recommend "monolith first" / "modular monolith" as the prudent starting style.

**Failure semantics.** Same as monolith — one process, shared blast radius. The boundaries are about *change cost and future optionality*, not runtime isolation.

### 3.3 Microservices — internal workflow

**Definition.** The system is decomposed into many small services, each:
- independently deployable (its own pipeline and release cadence),
- owning its own data store (database-per-service),
- communicating over the network (sync via REST/gRPC, async via messaging),
- ideally aligned to a business capability / DDD bounded context (§7).

**Request lifecycle (the `POST /orders` example, now distributed):**

1. **Edge.** Request hits an **API gateway** — a reverse proxy that does auth, rate-limiting, TLS termination, and routing to the right service. (Examples: Spring Cloud Gateway, Kong, AWS API Gateway, Envoy.)
2. **Service discovery.** The gateway (or the calling service) must find a healthy instance of `OrderService`. A **service registry** (e.g., Consul, Eureka, or Kubernetes' built-in DNS + Endpoints) maps logical names to current IPs. Instances register on startup and send heartbeats; unhealthy ones are evicted.
   > *Beginner aside — service discovery:* in a monolith, "where is the inventory code?" is answered by the linker at compile time. In microservices, instances come and go (autoscaling, restarts), so the address must be resolved at *runtime*, dynamically.
3. **Network call chain.** `OrderService` now makes **remote calls** to `InventoryService`, `PricingService`, `PaymentService`. Each call is serialized (JSON/Protobuf), sent over TCP, deserialized, processed, and a response is sent back. Each hop adds latency (often 1–10 ms intra-cluster) and a *new failure point*.
4. **Resilience machinery.** Because any callee can be slow or down, each remote call is wrapped in:
   - a **timeout** (never wait forever),
   - **retries** (with backoff + jitter; idempotency required),
   - a **circuit breaker** (after N failures, stop calling for a cooldown so you don't pile up on a dying dependency — see §9),
   - a **bulkhead** (isolate thread pools so one slow dependency can't exhaust all threads).
   Libraries: Resilience4j (modern), historically Netflix Hystrix (now in maintenance).
5. **Distributed data problem.** `OrderService` cannot open one transaction across the inventory, payment, and order databases. Instead it coordinates a **saga** (a sequence of local transactions with compensating actions on failure — see §7). Consistency becomes *eventual*.
6. **Observability.** Because the call spans many processes, you need **distributed tracing**: a trace ID propagates across hops so you can reconstruct the end-to-end path (tools: OpenTelemetry, Jaeger, Zipkin). Logs are centralized and correlated by trace ID.

**Data flow.** Each service has its own database in its own technology (polyglot persistence — `OrderService` on PostgreSQL, `SearchService` on Elasticsearch, `CartService` on Redis). Data that one service needs from another is either *queried synchronously* (coupling) or *replicated asynchronously via events* (the service keeps a local read-model copy, kept fresh by consuming the other service's events — see §3.4, CQRS).

**Deployment lifecycle.** *N* independent pipelines. `OrderService` v2.3 deploys without touching `InventoryService`. This is the entire point — independent deployability. It requires **backward/forward-compatible APIs** (you can never deploy all services atomically), versioning discipline, and contract testing (§6).

**Service mesh (optional infra layer).** A **mesh** (Istio, Linkerd) injects a *sidecar proxy* (Envoy) next to each service to handle mTLS, retries, timeouts, traffic-splitting, and telemetry *outside the application code*, so resilience policy is configured rather than coded.

> *Beginner aside — sidecar:* a helper container that runs alongside your service container in the same pod, intercepting its network traffic. It lets the platform add cross-cutting concerns (security, observability) without changing the app.

**Failure semantics — the big shift.** Failure is now **partial and pervasive**. At any moment some dependency is degraded. The system must be designed to *degrade gracefully* (return cached/partial results, queue work, fail one feature without failing the page). The flip side: a crash in `Reporting` no longer kills `Orders` — *fault isolation* is the headline benefit you bought with all this complexity.

### 3.4 Event-Driven Architecture — internal workflow

**Definition.** Components communicate primarily by **producing and consuming events** through a **broker**, rather than calling each other directly. The producer does not know who consumes, or how many, or when. This is the strongest form of decoupling — in identity, number, and time.

**Two sub-styles:**
- **Broker / pub-sub topology** — producers publish events to topics; any number of consumers subscribe. Highly decoupled, no central orchestrator (this is *choreography*).
- **Mediator topology** — a central component (an *event mediator* / orchestrator) receives an event and directs a multistep workflow, calling each step. More control and visibility, more coupling to the mediator (this is *orchestration*).

**The broker — what it does internally (using Apache Kafka as the canonical example):**
- A **topic** is an append-only log, split into **partitions** for parallelism. Each partition is an ordered, immutable sequence of records; ordering is guaranteed *within* a partition only.
- Producers append records; each gets an **offset** (its position in the partition). Records are retained for a configured time/size regardless of consumption — so events can be **replayed**.
- **Consumer groups**: consumers in a group split the partitions among themselves (each partition is read by exactly one consumer in the group), enabling horizontal scaling. Different groups each get the *full* stream (fan-out).
- The consumer tracks its **committed offset** (how far it has processed). Delivery is typically **at-least-once** by default: if a consumer crashes after processing but before committing the offset, it reprocesses on restart — hence consumers must be **idempotent**.

> *Beginner aside — idempotent:* an operation that produces the same result whether applied once or many times. "Set balance to 100" is idempotent; "add 100 to balance" is not. At-least-once delivery means you *will* see duplicates, so handlers must be idempotent (e.g., dedupe by event ID).

**Control & data flow of an event-driven order flow (choreographed):**
1. `OrderService` writes the order locally and **publishes** `OrderPlaced` to the `orders` topic. It is now *done* — it does not wait for inventory or payment.
2. `InventoryService` consumes `OrderPlaced`, reserves stock, publishes `StockReserved` (or `StockReservationFailed`).
3. `PaymentService` consumes `StockReserved`, charges the card, publishes `PaymentCaptured` (or `PaymentFailed`).
4. `OrderService` consumes `PaymentCaptured` and marks the order confirmed; on a failure event it consumes the failure and triggers compensation.

Nobody blocks; the flow is a chain of reactions. The same `OrderPlaced` event also feeds an analytics consumer, a fraud-detection consumer, and a notification consumer — *added later without touching the producer*. That extensibility is the core EDA benefit.

**Key related patterns inside EDA:**
- **Event notification** — thin event ("order 123 changed"); consumer calls back for details. Low coupling, more chatter.
- **Event-carried state transfer** — fat event carrying all the data the consumer needs, so it never calls back. Reduces coupling and load but duplicates data.
- **Event sourcing** — store the *sequence of events* as the source of truth instead of current state; rebuild state by replaying events. Gives a perfect audit log and time-travel, but adds replay/snapshot/versioning complexity (§7).
- **CQRS (Command Query Responsibility Segregation)** — separate the write model (commands) from the read model (queries), often kept in sync by events. Lets reads scale independently and be denormalized for query speed.
- **Transactional outbox** — to publish an event *and* commit local state atomically (you can't do a distributed transaction with the broker reliably), write the event to an `outbox` table in the *same* DB transaction as the state change, then a relay process reads the outbox and publishes. Solves the "dual write" problem (see §9).

**Failure semantics.** The broker absorbs temporal failures: if a consumer is down, events queue and are processed on recovery. But you inherit *ordering*, *duplicate*, *poison-message*, and *consumer-lag* problems (§9), and the flow becomes hard to follow because there is no single call stack — you trace it through topics and offsets.

### 3.5 Serverless / FaaS — internal workflow

**Definition.** You deploy individual **functions**; the cloud provider runs them on demand in response to **triggers** (HTTP, queue message, file upload, schedule, stream record), scales them automatically (including to zero), and bills per invocation + compute-time. You do not manage servers, capacity, or (mostly) the runtime.

**Invocation lifecycle (AWS Lambda as the canonical example):**
1. **Trigger.** An event source (API Gateway, SQS, S3, EventBridge, Kinesis) invokes the function with an event payload.
2. **Cold start vs warm start.** If no warm execution environment exists, the platform performs a **cold start**: provision a micro-VM (AWS Firecracker), download your code, start the runtime (JVM!), and run your init code. This adds latency — for the JVM, historically **hundreds of ms to a few seconds**, which is *the* JVM-on-serverless pain point. If a warm environment is available, it's reused (a **warm start**, sub-millisecond overhead).
   > *Beginner aside — Firecracker:* a lightweight virtualization technology that boots a minimal "microVM" in ~125 ms, giving each function strong isolation with low overhead. It's how Lambda safely multi-tenants.
3. **Concurrency model.** Each execution environment handles **one request at a time**. To serve 100 concurrent requests, the platform spins up 100 environments. This is radically different from a monolith thread pool sharing one heap.
4. **Statelessness.** Functions are assumed stateless between invocations; any state must live in external stores (DynamoDB, S3, Redis). The local filesystem (`/tmp`) and memory may persist across warm invocations but you must not rely on it.
5. **Limits.** Hard ceilings shape the design: AWS Lambda max execution time **15 minutes**, payload size limits (6 MB sync / 256 KB async at time of writing), memory configurable (which also scales CPU). Always verify current limits — these are *version/vendor-specific and change*.
6. **Billing.** Per-request + per-GB-second of execution. Scale-to-zero means **you pay nothing when idle** — the headline economic win for spiky workloads.

**JVM-specific concern.** The JVM's startup cost (class loading + JIT warm-up) makes naïve Java a poor fit for latency-sensitive cold starts. Mitigations: **GraalVM native image** (AOT-compile to a native binary, ~tens-of-ms cold start; this is what Quarkus and Spring Native target), **AWS Lambda SnapStart** for Java (snapshots an initialized JVM and restores it, cutting cold starts dramatically), keeping the function small, and provisioned concurrency (pre-warmed environments, at a cost).

**Failure semantics.** Per-invocation isolation is excellent (one bad request can't corrupt others). But you inherit timeouts, retries (async invocations retry; you need idempotency + a dead-letter queue), and the operational opacity of not owning the runtime. Debugging is via logs/traces only.

### 3.6 Cross-style summary of internal mechanics

| Dimension | Monolith | Modular monolith | Microservices | Event-driven | Serverless |
|---|---|---|---|---|---|
| Deployables | 1 | 1 | N | N (+ broker) | Many functions |
| Inter-component call | method | method (via API) | network (sync/async) | async via broker | event/trigger |
| Cross-component transaction | ACID, free | ACID, free | Saga, eventual | Saga/eventual | external/eventual |
| Failure mode | shared blast radius | shared blast radius | partial, pervasive | broker-buffered | per-invocation isolated |
| Scaling unit | whole app | whole app | per service | per consumer group | per function |
| Discovery | linker (compile) | linker | runtime registry/DNS | topic subscription | trigger binding |

---

## 4. The complete toolkit

This section enumerates the concrete tools, frameworks, APIs, config, and commands you actually use, by category. Where a default or limit is given, treat cloud numbers as *version/vendor-specific* and verify against current docs.

### 4.1 Frameworks for building each style (JVM-centric)

| Tool | Style fit | Purpose | Key knobs / notes |
|---|---|---|---|
| **Spring Boot** | Monolith, microservices | The dominant JVM application framework; embedded server, auto-config, DI. | `server.tomcat.threads.max` (200), `spring.datasource.hikari.maximum-pool-size` (10), profiles. |
| **Spring Modulith** | Modular monolith | Define & verify module boundaries; in-process events; per-module integration tests. | Modules = top-level packages; `ApplicationModules.verify()`; `@ApplicationModuleListener`. |
| **Spring Cloud** | Microservices | Discovery (Eureka), gateway, config server, OpenFeign clients, circuit breakers. | Now largely superseded by platform features (K8s, mesh) but still common. |
| **Quarkus** | Microservices, serverless | "Supersonic subatomic Java"; fast startup, low memory, first-class GraalVM native. | Build-time DI, `quarkus.native.*`, great for FaaS cold starts. |
| **Micronaut** | Microservices, serverless | Compile-time DI/AOP (no runtime reflection) → fast startup, low memory. | Similar serverless/native story to Quarkus. |
| **Helidon** | Microservices | Oracle's lightweight MicroProfile framework (SE and MP flavors). | Loom-based "Níma" server in newer versions. |
| **JPMS (`module-info.java`)** | Modular monolith | Language-level module boundaries enforced by compiler + runtime. | `exports`, `requires`, `opens`. Coarse but real enforcement. |

### 4.2 Architecture-enforcement & boundary tools

| Tool | Purpose | Key API |
|---|---|---|
| **ArchUnit** | Assert architectural rules as unit tests; fail build on violation. | `classes().that().resideInAPackage("..billing.internal..").should().onlyBeAccessed().byAnyPackage("..billing..")` |
| **Maven/Gradle multi-module** | Compile-time boundaries via module dependency graph. | Module B can't see A's internals unless A exports them as an API artifact. |
| **jdeps** | Analyze actual class/package dependencies in a JAR. | `jdeps --print-module-deps app.jar` |
| **Structurizr / C4 model** | Document architecture as code at 4 zoom levels (Context, Container, Component, Code). | Keeps diagrams in sync with reality. |

### 4.3 Synchronous communication

| Tool | Purpose | Key parameters/defaults |
|---|---|---|
| **REST/HTTP (JSON)** | Ubiquitous, human-readable sync API. | Status codes, idempotency via HTTP method semantics (GET/PUT/DELETE idempotent, POST not). |
| **gRPC (HTTP/2 + Protobuf)** | High-performance, strongly-typed, streaming-capable RPC. | Contract-first `.proto`; deadlines (timeouts) are first-class; far smaller/faster than JSON. |
| **OpenFeign** | Declarative HTTP client for JVM. | Interface + annotations → client; integrates with Resilience4j. |
| **WebClient / RestClient (Spring)** | Reactive / blocking HTTP clients. | Connection pools, timeouts must be set explicitly. |
| **GraphQL** | Client-shaped queries over one endpoint; good gateway aggregation. | N+1 risk; needs dataloaders; not a transport for service-to-service usually. |

### 4.4 Asynchronous messaging / brokers

| Tool | Model | Strengths | Key concepts/defaults |
|---|---|---|---|
| **Apache Kafka** | Distributed log, pub-sub + replay | High throughput, retention, replay, ordering per partition. | partitions, offsets, consumer groups, `acks` (0/1/all), `retention.ms`. At-least-once default. |
| **RabbitMQ** | Smart broker, queues + exchanges | Flexible routing, per-message ack, priority, delayed. | exchanges (direct/topic/fanout), prefetch, DLX (dead-letter exchange). |
| **AWS SQS** | Managed queue | Simple, durable, scales automatically. | Standard (at-least-once, best-effort order) vs FIFO (exactly-once-ish, ordered); visibility timeout; DLQ. |
| **AWS SNS / EventBridge** | Managed pub-sub / event bus | Fan-out, content-based routing, schema registry. | EventBridge rules + targets; great serverless glue. |
| **Apache Pulsar** | Log + queue hybrid | Multi-tenancy, tiered storage, separate compute/storage. | topics, subscriptions (exclusive/shared/failover). |
| **NATS / JetStream** | Lightweight messaging | Very low latency, simple ops. | subjects, streams, consumers. |

### 4.5 Resilience & traffic management

| Tool | Purpose | Key config |
|---|---|---|
| **Resilience4j** | Circuit breaker, retry, rate limiter, bulkhead, timeout (lightweight, functional). | `failureRateThreshold`, `slidingWindowSize`, `waitDurationInOpenState`, `slowCallRateThreshold`. |
| **Hystrix** | (Legacy) circuit breaker + bulkhead. | In maintenance; prefer Resilience4j. |
| **API Gateway** (Kong, Spring Cloud Gateway, AWS API GW, Envoy) | Edge routing, auth, rate-limit, TLS. | Routes, plugins/filters, quotas. |
| **Service mesh** (Istio, Linkerd) | mTLS, retries, timeouts, traffic-splitting, telemetry via sidecars. | `VirtualService`, `DestinationRule`, retry/timeout policies as CRDs. |

### 4.6 Data-consistency patterns & tools

| Tool/Pattern | Purpose | Notes |
|---|---|---|
| **Saga (orchestration/choreography)** | Distributed "transaction" via local txns + compensations. | Frameworks: Axon, Eventuate Tram, Camunda/Temporal for orchestration. |
| **Transactional Outbox** | Atomic local-write + event-publish. | Often paired with **Debezium** (CDC) to tail the outbox/WAL. |
| **CDC (Change Data Capture)** — Debezium | Stream DB changes as events from the transaction log. | Captures inserts/updates/deletes from MySQL/Postgres WAL into Kafka. |
| **Temporal / Camunda / AWS Step Functions** | Durable workflow orchestration for sagas/long-running processes. | Survive crashes, retries, timers built-in. |

> *Beginner aside — CDC / WAL:* The **Write-Ahead Log** is the ordered log of every change a database makes (used for crash recovery). **Change Data Capture** tools tail that log and emit each change as an event, so other systems stay in sync without dual-writes.

### 4.7 Observability (mandatory in distributed styles)

| Tool | Pillar | Purpose |
|---|---|---|
| **OpenTelemetry** | All three | Vendor-neutral SDK/standard for traces, metrics, logs. |
| **Jaeger / Zipkin** | Tracing | Visualize distributed traces; find the slow hop. |
| **Prometheus + Grafana** | Metrics | Scrape metrics, alert (latency, error rate, consumer lag, saturation). |
| **ELK / Loki** | Logs | Centralized, correlated-by-trace-ID logging. |

### 4.8 Platform / deployment

| Tool | Purpose | Notes |
|---|---|---|
| **Docker** | Package a deployable as an image. | The unit of deployment for services/monoliths alike. |
| **Kubernetes** | Orchestrate containers: scheduling, scaling, service discovery (DNS), rolling deploys. | `Deployment`, `Service`, `HPA` (Horizontal Pod Autoscaler), readiness/liveness probes. |
| **AWS Lambda / Azure Functions / Google Cloud Functions** | FaaS runtimes. | Triggers, memory/timeout config, provisioned/reserved concurrency. |
| **AWS Lambda SnapStart** | JVM cold-start mitigation via snapshot/restore. | Java-specific; big cold-start reduction. |
| **GraalVM native-image** | AOT-compile JVM apps to native binaries. | Tens-of-ms startup; trades JIT peak throughput & some reflection convenience. |

---

## 5. Code examples by use case

These span genuinely different scenarios. They are idiomatic and adaptable; non-obvious lines are commented.

### 5.1 Enforcing module boundaries in a modular monolith (ArchUnit)

This is the *most valuable* example because enforcement is the whole point of a modular monolith — without it, you have a big ball of mud with a nicer folder layout.

```java
// File: src/test/java/com/shop/ArchitectureTest.java
// Run as a normal JUnit test; it FAILS THE BUILD when a boundary is violated.
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

class ArchitectureTest {

    // Import once: all production classes of the app.
    private final JavaClasses classes =
        new ClassFileImporter().importPackages("com.shop");

    @Test
    void billingInternalsAreHidden() {
        // Anything in billing.internal may only be touched from within billing.*
        // This is the boundary that a folder structure alone CANNOT enforce.
        ArchRule rule = classes()
            .that().resideInAPackage("..billing.internal..")
            .should().onlyBeAccessed().byAnyPackage("..billing..");
        rule.check(classes);
    }

    @Test
    void modulesDoNotFormCycles() {
        // Cyclic dependencies between modules make extraction impossible later.
        slices().matching("com.shop.(*)..")   // each top-level package = one module
            .should().beFreeOfCycles()
            .check(classes);
    }

    @Test
    void catalogMustNotDependOnOrders() {
        // Express an intentional dependency direction (lower layers don't know upper).
        noClasses().that().resideInAPackage("..catalog..")
            .should().dependOnClassesThat().resideInAPackage("..orders..")
            .check(classes);
    }
}
```

Cross-module calls then go through a published API only:

```java
// PUBLIC API of the inventory module — the only thing other modules may use.
package com.shop.inventory;            // note: NOT inventory.internal
public interface InventoryApi {
    ReservationResult reserve(String sku, int qty, String orderId);
}

// Orders module depends on the INTERFACE, never on inventory.internal.* classes.
package com.shop.orders.internal;
class PlaceOrderService {
    private final com.shop.inventory.InventoryApi inventory; // injected
    // ...
    void place(Order order) {
        var res = inventory.reserve(order.sku(), order.qty(), order.id());
        // still an in-process call -> fast, transactional, type-safe.
        // If we later extract Inventory to a service, we swap the impl behind InventoryApi.
    }
}
```

### 5.2 A resilient synchronous microservice call (Resilience4j + Spring)

Every remote call needs timeout + retry + circuit breaker. This shows the standard wrapping.

```java
@Service
class PricingClient {

    private final RestClient http;       // Spring 6 sync client; configure timeouts!
    PricingClient(RestClient.Builder b) {
        this.http = b.baseUrl("http://pricing-service").build();
    }

    // Annotations apply, in order: rate limiter -> bulkhead -> circuit breaker -> retry -> timeout.
    @CircuitBreaker(name = "pricing", fallbackMethod = "fallbackPrice")
    @Retry(name = "pricing")             // retries only on configured exceptions
    @TimeLimiter(name = "pricing")       // caps how long we wait
    CompletableFuture<Price> price(String sku) {
        return CompletableFuture.supplyAsync(() ->
            http.get().uri("/prices/{sku}", sku).retrieve().body(Price.class));
    }

    // Fallback runs when the breaker is OPEN or calls fail -> graceful degradation.
    CompletableFuture<Price> fallbackPrice(String sku, Throwable t) {
        return CompletableFuture.completedFuture(Price.listPriceFor(sku)); // safe default
    }
}
```

```yaml
# application.yml  (real, tunable defaults — adjust to your SLOs)
resilience4j:
  circuitbreaker:
    instances:
      pricing:
        slidingWindowSize: 50            # evaluate over last 50 calls
        failureRateThreshold: 50         # open if >=50% fail
        slowCallRateThreshold: 100
        slowCallDurationThreshold: 500ms # a call >500ms counts as "slow"
        waitDurationInOpenState: 10s     # cool-off before probing again
        permittedNumberOfCallsInHalfOpenState: 5
  retry:
    instances:
      pricing:
        maxAttempts: 3
        waitDuration: 200ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
  timelimiter:
    instances:
      pricing:
        timeoutDuration: 800ms           # never block the caller forever
```

### 5.3 Atomic state-change + event publish via the Transactional Outbox

This solves the *dual-write problem*: you cannot reliably write to your DB and publish to Kafka in one atomic step. The outbox writes the event to the *same* DB in the *same* transaction; a relay publishes it later.

```java
@Service
class OrderService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper json;

    @Transactional   // ONE local DB transaction — both rows commit or neither does.
    public void placeOrder(PlaceOrderCmd cmd) {
        Order order = Order.create(cmd);
        orders.save(order);                          // (1) business state

        OutboxEvent evt = OutboxEvent.of(
            "Order", order.id(), "OrderPlaced",
            json.writeValueAsString(new OrderPlaced(order.id(), order.total())));
        outbox.save(evt);                            // (2) event row — SAME transaction
        // No Kafka call here. If we crash now, both rows are committed atomically.
    }
}
```

```java
// A relay (or Debezium CDC tailing the WAL) reads the outbox and publishes,
// marking rows as sent. At-least-once: a duplicate publish is possible after a crash,
// so downstream consumers MUST be idempotent (dedupe by event id).
@Scheduled(fixedDelay = 500)
@Transactional
void relay() {
    for (OutboxEvent e : outbox.findUnpublishedBatch(100)) {
        kafka.send("orders", e.aggregateId(), e.payload());  // key by id => per-order ordering
        e.markPublished();
    }
}
```

### 5.4 Idempotent at-least-once Kafka consumer

Because Kafka delivers at-least-once by default, the consumer must tolerate duplicates.

```java
@Component
class PaymentEventConsumer {
    private final ProcessedEventRepository processed; // unique index on eventId
    private final PaymentService payments;

    @KafkaListener(topics = "orders", groupId = "payments")
    public void onMessage(ConsumerRecord<String, String> rec) {
        String eventId = extractEventId(rec.value());

        // Idempotency guard: if we've seen this eventId, skip. The unique constraint
        // also protects against a race between two consumer threads.
        if (!processed.tryInsert(eventId)) {
            return; // already processed -> safe to ignore the duplicate
        }
        payments.captureForOrder(parse(rec.value()));
        // Offset is committed by the container AFTER this returns successfully.
    }
}
```

### 5.5 A choreographed saga with a compensating action

```java
// PaymentService reacts to StockReserved; on success/failure it publishes the next event.
@KafkaListener(topics = "inventory-events", groupId = "payment")
void onStockReserved(StockReserved evt) {
    try {
        var receipt = gateway.charge(evt.orderId(), evt.amount());
        kafka.send("payment-events", evt.orderId(),
                   new PaymentCaptured(evt.orderId(), receipt.id()));
    } catch (PaymentDeclined ex) {
        // We cannot roll back the inventory reservation directly (different DB/service).
        // Instead we emit a failure event -> InventoryService COMPENSATES (releases stock).
        kafka.send("payment-events", evt.orderId(),
                   new PaymentFailed(evt.orderId(), ex.reason()));
    }
}

// InventoryService listens for the failure and undoes its earlier local transaction.
@KafkaListener(topics = "payment-events", groupId = "inventory")
void onPaymentFailed(PaymentFailed evt) {
    inventory.releaseReservation(evt.orderId()); // the COMPENSATING transaction
}
```

### 5.6 A serverless function (AWS Lambda, Java) processing SQS messages

```java
// Triggered by SQS. The platform scales concurrency by spinning up more environments.
public class ImageThumbnailHandler implements RequestHandler<SQSEvent, Void> {

    // Init OUTSIDE the handler: runs once per cold start, reused across warm invocations.
    private static final S3Client s3 = S3Client.create();

    @Override
    public Void handleRequest(SQSEvent event, Context ctx) {
        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                processOne(msg.getBody());
            } catch (Exception e) {
                // Don't swallow: let it throw so SQS retries (visibility timeout),
                // and after maxReceiveCount the message goes to the DLQ.
                throw new RuntimeException(e);
            }
        }
        return null;
    }
    private void processOne(String body) { /* fetch from S3, resize, put back */ }
}
```

```yaml
# Infrastructure-as-code sketch (e.g., AWS SAM). Note the limits that shape design.
Resources:
  ThumbnailFn:
    Type: AWS::Serverless::Function
    Properties:
      Runtime: java21
      MemorySize: 1024          # memory also scales CPU on Lambda
      Timeout: 30               # seconds; hard max is 900 (15 min)
      SnapStart:                # JVM cold-start mitigation (Java-specific)
        ApplyOn: PublishedVersions
      Events:
        FromQueue:
          Type: SQS
          Properties: { Queue: !GetAtt ThumbQueue.Arn, BatchSize: 10 }
```

### 5.7 Strangler Fig migration: routing a slice from monolith to a new service

You migrate one capability at a time by intercepting requests at the edge and routing the strangled slice to the new service while everything else still hits the monolith.

```yaml
# Spring Cloud Gateway: send /api/pricing/** to the NEW service,
# everything else still goes to the legacy MONOLITH. Flip routes per-slice as you migrate.
spring:
  cloud:
    gateway:
      routes:
        - id: pricing-extracted          # the strangled slice -> new microservice
          uri: http://pricing-service
          predicates: [ Path=/api/pricing/** ]
        - id: legacy-monolith            # catch-all: still the monolith
          uri: http://legacy-monolith
          predicates: [ Path=/api/** ]
```

The key property: this is reversible per-slice (just flip the route back), and the client/caller is unaware. Combine with a feature flag for percentage-based cutover and instant rollback.

---

## 6. Implementation concerns & best practices

### 6.1 Performance & latency

- **In-process vs network is ~5–6 orders of magnitude.** A method call is ~1 ns; a serialized intra-datacenter RPC is ~0.5–5 ms. A request that fans out to 10 services *serially* can easily add 50+ ms of pure overhead. Mitigate with **parallel fan-out** (call independent services concurrently), **caching**, **event-carried state transfer** (don't call back; carry the data), and **gRPC/Protobuf** over JSON for hot paths.
- **Tail latency amplification.** If a request needs responses from 10 services and each has a 99th-percentile latency of 100 ms, the *combined* p99 is far worse than 100 ms — you're likely to hit at least one slow service. This is the #1 hidden cost of fan-out. Mitigate with hedged requests, tighter timeouts, and reducing fan-out depth.
- **Serialization cost** is real CPU and GC pressure on the JVM. Reuse `ObjectMapper`, prefer binary formats on hot paths.
- **Cache coherency in the monolith:** an in-process cache is per-replica; with N replicas you get N possibly-stale caches. Use a distributed cache (Redis) or cache invalidation events for cross-replica consistency.

### 6.2 Correctness & concurrency (the distributed-data crux)

- **You lose ACID across services.** Replace with **sagas** + **idempotency** + **eventual consistency**. Design every event handler to be idempotent (dedupe by event ID) because delivery is at-least-once.
- **The dual-write problem.** Never write to your DB and then publish an event as two separate steps — a crash between them loses or duplicates the event. Use the **transactional outbox** (or CDC) so the write and the to-be-published event commit atomically.
- **Ordering.** Most brokers guarantee order only within a partition/queue. Key your messages by aggregate ID so all events for one entity land on one partition and stay ordered.
- **Exactly-once is mostly a myth across system boundaries.** Aim for *effectively-once* = at-least-once delivery + idempotent processing. Kafka offers exactly-once *within* its own transactions, but the moment you touch an external system the guarantee weakens.
- **Distributed transactions / 2PC** (two-phase commit) exist but are an anti-pattern at scale: they block, hold locks across the network, and fail badly under partitions. Prefer sagas.

> *Beginner aside — 2PC:* a coordinator asks all participants "can you commit?" (prepare), then if all say yes, "commit." If any can't, all roll back. It guarantees atomicity but locks resources for the whole round-trip and stalls if the coordinator dies mid-protocol — fragile in distributed systems.

### 6.3 Security

- **Monolith:** one trust boundary, one auth check at the edge, internal calls trusted. Simpler attack surface.
- **Distributed:** the network *between* services is now an attack surface. Adopt **zero-trust**: every service authenticates every caller. Use **mTLS** (mutual TLS, both sides present certificates) — often provided automatically by a service mesh. Propagate user identity via signed tokens (JWT) and validate them at each hop; don't trust "internal" traffic blindly.
- **Secrets** multiply with services; use a secret manager (Vault, cloud KMS), never bake into images.
- **Serverless:** each function is an IAM principal; apply least-privilege per function. Beware over-broad roles.

### 6.4 Observability (non-negotiable for distributed styles)

- **Three pillars:** metrics (Prometheus), logs (centralized, correlated), traces (OpenTelemetry → Jaeger). In a monolith a stack trace tells the whole story; in microservices you *must* have distributed tracing or you are blind.
- **Propagate a trace/correlation ID** from the edge through every sync call and *into event payloads*, so an async flow is reconstructable.
- **The Four Golden Signals** (Google SRE): **latency, traffic, errors, saturation** — instrument all four per service.
- **Watch consumer lag** in event-driven systems — it's the canary for a stuck or slow consumer.

### 6.5 Cost

- **Microservices have a high fixed operational cost**: per-service CI/CD, monitoring, on-call, infra. This is dead weight on a small team — a major reason a monolith is the senior choice early on.
- **Serverless** trades fixed cost for variable: cheap at low/spiky volume, but at sustained high throughput a provisioned container/monolith is often *cheaper* per request than per-invocation pricing. Model your real traffic before committing.
- **Network egress and inter-AZ traffic** cost money in microservices; chatty designs are expensive in dollars, not just latency.

### 6.6 Testing

- **Monolith:** straightforward — in-process integration tests cover real call chains with real transactions.
- **Microservices:** unit tests are easy; *integration* is the hard part. Use **consumer-driven contract testing** (Pact, Spring Cloud Contract) so a provider's CI fails if it breaks a consumer's expectations *without* needing to deploy everything together. Use **Testcontainers** to spin up real Kafka/Postgres in tests. Avoid brittle full end-to-end test suites as the primary safety net (slow, flaky); push verification down the test pyramid.
- **Event-driven:** test idempotency, out-of-order delivery, and poison messages explicitly.

### 6.7 Production hardening & anti-patterns

**Hardening:** readiness/liveness probes, graceful shutdown (drain in-flight + commit offsets), back-pressure, dead-letter queues, retry budgets, circuit breakers everywhere, schema registry for events, deploy strategy (canary/blue-green), and runbooks.

**Anti-patterns to avoid (each is a real way teams fail):**
- **Distributed monolith** — microservices that must be deployed together, share a database, or chain synchronous calls. You paid all the costs of distribution and kept all the coupling. *The most common microservices failure.*
- **Nano-services** — services so small the overhead dwarfs the work; the coordination cost explodes.
- **Shared database across services** — the boundary violation that silently re-couples everything.
- **Synchronous call chains** (A→B→C→D) — latency adds up and any failure cascades; prefer async or aggregation.
- **Premature decomposition** — splitting before you understand the domain; you'll draw the wrong boundaries and pay to redraw them across the network.
- **Entity services / CRUD-as-a-service** — services drawn around nouns (User, Order) instead of capabilities, forcing chatty orchestration.
- **Ignoring Conway's Law** — designing boundaries the org can't actually own.
- **No idempotency** in at-least-once consumers — leads to double charges, duplicate emails.

---

## 7. Advanced topics & deep internals

### 7.1 Domain-Driven Design & finding the right service boundaries

The single hardest part of decomposition is *where to cut*. DDD (Eric Evans, 2003) gives the vocabulary:

- **Bounded context** — an explicit boundary within which a domain model and its *ubiquitous language* are consistent. "Customer" means something different in Sales vs Support; each is its own bounded context. **A bounded context is the natural unit for a microservice** — it is the boundary inside which a single model and a single team are coherent.
- **Ubiquitous language** — a shared, precise vocabulary used by developers and domain experts alike *within* a context. Misaligned language across a boundary is a sign you've cut in the wrong place.
- **Aggregate** — a cluster of objects treated as a single unit for data changes, with one **aggregate root** as the only entry point and the **consistency boundary** for transactions. *Aggregates define what must be transactionally consistent — and therefore what must not be split across services.* If two pieces of data must change atomically, they belong in the same service.
- **Context mapping** — documenting how bounded contexts relate (shared kernel, customer/supplier, anti-corruption layer). An **anti-corruption layer (ACL)** is a translation layer that protects your model from a legacy or external model's concepts — essential when extracting from a messy monolith.
- **Event Storming** — a workshop technique (Alberto Brandolini) where the team maps domain *events* on a wall to discover aggregates and bounded contexts collaboratively. The fastest practical way to find service boundaries.

**Decomposition strategies, in order of preference:**
1. **By business capability / bounded context** (recommended) — align services to what the business *does* and to team ownership.
2. **By subdomain** (DDD): **core** (your differentiator — invest here), **supporting** (necessary but not special), **generic** (buy/outsource, e.g., auth). Extract core subdomains first.
3. **By volatility** — split off the parts that change at very different rates.
4. **By scalability profile** — split off the part with a wildly different load (e.g., search) so it scales independently.
- **Avoid** decomposing by technical layer (UI/logic/data services) — that maximizes coupling because every feature crosses all of them.

### 7.2 Migration patterns (monolith → services)

- **Strangler Fig** (Martin Fowler) — incrementally route capabilities from the monolith to new services behind a façade/gateway, slice by slice, until the monolith is "strangled" and removed. Each step is reversible. (See §5.7.) Named after the strangler fig vine that grows around a host tree.
- **Branch by Abstraction** — introduce an abstraction (interface) over the code you want to replace, build the new implementation behind it, switch, then remove the old. Lets you refactor in-place without a long-lived branch.
- **Parallel run / shadow traffic** — run old and new implementations on the same input and compare outputs before cutting over; catches behavioral regressions safely.
- **Database decomposition** — the hardest part. Steps: identify the data each new service owns → break foreign keys / JOINs that cross the boundary (replace with API calls or replicated read models) → split the schema (often via a transitional shared DB with separate schemas, then physical split) → migrate data → cut over. Tools: CDC/Debezium to keep old and new in sync during transition.
- **Anti-corruption layer** during migration so the new service's clean model isn't infected by the legacy schema.

### 7.3 Event sourcing internals & tuning

- **State = fold over events.** Current state is computed by replaying all events for an aggregate. To avoid replaying millions of events, take **snapshots** (periodic materialized state) and replay only events since the last snapshot.
- **Schema/event versioning** is the chief operational burden: events are immutable history, so you must support *upcasting* old event versions to new shapes forever, or use weak schemas.
- **Read models** are projections built by consuming the event stream (this is the read side of **CQRS**). They are eventually consistent with the write side — your UI must tolerate "I just placed an order but don't see it yet."
- Use only where the *audit log / temporal queries / replay* are genuinely valuable (finance, ledgers, compliance). It is overkill for CRUD.

### 7.4 JVM-specific deep internals across styles

- **Cold start (serverless):** JVM startup = classloading + JIT warm-up. Mitigations: **GraalVM native image** (AOT, no JIT, ~tens of ms start, ~10x less memory — but closed-world assumption breaks runtime reflection; Quarkus/Micronaut do build-time DI to fit this), and **Lambda SnapStart** (Firecracker snapshot of an initialized JVM restored on invoke — caveat: must handle "uniqueness" like random seeds and cached connections that were captured in the snapshot via runtime hooks).
- **Virtual threads (Project Loom, JDK 21+):** cheap user-mode threads let a monolith or service handle massive concurrency with simple blocking code, easing the thread-per-request model under high fan-out without going fully reactive. Caveat: pinning on `synchronized` blocks holding the carrier thread.
- **Reactive stacks (Project Reactor / WebFlux):** non-blocking I/O for high-concurrency I/O-bound services; harder to debug (no linear stack traces), so reserve for genuine high-concurrency needs.
- **GC pressure from serialization** in chatty microservices is a real source of latency jitter; profile allocation.

### 7.5 Lesser-known behaviors

- **Kafka rebalancing storms:** when a consumer joins/leaves, the group rebalances and *pauses processing*; frequent restarts cause repeated stop-the-world rebalances. Tune `session.timeout.ms`, use cooperative-sticky assignor, and static membership.
- **Outbox + CDC ordering:** Debezium emits in transaction-log order; ensure your downstream keys preserve per-aggregate order.
- **Saga isolation anomalies:** sagas are *not* isolated — other transactions can see intermediate states (e.g., reserved-but-not-yet-paid). Counter with semantic locks (status flags), commutative updates, or versioning.
- **Idempotency key TTLs:** dedupe stores grow unbounded; expire keys after the maximum possible retry window, not forever.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Master comparison

| Dimension | Monolith | Modular monolith | Microservices | Event-driven | Serverless |
|---|---|---|---|---|---|
| Initial velocity | Highest | High | Low (infra tax) | Medium | Medium-High |
| Independent deploy | No | No | Yes | Yes | Yes (per fn) |
| Independent scaling | No (scale all) | No | Yes (per service) | Yes (per consumer) | Yes (per fn, to zero) |
| Fault isolation | Poor (shared) | Poor (shared) | Good | Good (buffered) | Excellent (per call) |
| Transactional consistency | ACID (easy) | ACID (easy) | Saga/eventual (hard) | Eventual (hard) | Eventual (hard) |
| Operational complexity | Low | Low | High | High | Medium (vendor-run) |
| Observability need | Low | Low | Very high | Very high | High |
| Cost at low traffic | Low fixed | Low fixed | High fixed | High fixed | Lowest (pay-per-use) |
| Cost at sustained high traffic | Low | Low | Medium | Medium | Can be highest |
| Cognitive load to follow a flow | Low (one stack) | Low | High | Highest (no stack) | High |
| Team-count fit | 1–2 teams | 1–3 teams | Many teams | Many teams | Any (event-glue) |
| Reversibility of the choice | n/a | High (extract later) | Low (hard to merge back) | Low | Medium |

### 8.2 "Use when / avoid when"

**Monolith / modular monolith — use when:**
- Small-to-medium team (≤ ~2 teams), early-stage product, domain boundaries still uncertain.
- You value shipping speed, atomic transactions, and simple debugging over independent scaling.
- *Default to a **modular monolith*** so the option to extract later stays cheap.
**Avoid when:** multiple autonomous teams genuinely block on one deploy pipeline; parts have radically different scaling/availability needs you can't meet by scaling the whole.

**Microservices — use when:**
- Multiple teams need independent release cadence and ownership (Conway alignment).
- Distinct components have very different scaling, availability, or tech needs.
- The organization can fund the operational platform (CI/CD, observability, on-call) — this is a prerequisite, not an afterthought.
**Avoid when:** small team, unclear domain, no platform maturity, or strong cross-cutting transactional consistency requirements. Premature microservices is the classic startup-killer.

**Event-driven — use when:**
- You need temporal decoupling, fan-out to many/unknown consumers, high write throughput, audit/replay, or to integrate independently-evolving systems.
**Avoid when:** you need a simple synchronous answer right now, the team can't operate a broker, or strict ordering/immediate consistency dominates.

**Serverless — use when:**
- Spiky/unpredictable or low-baseline load, event processing, scheduled jobs, glue/integration, and you want zero idle cost and no server management.
**Avoid when:** sustained high throughput (cost), latency-critical paths sensitive to cold starts (mind JVM), long-running (>15 min) work, or you need fine control over the runtime.

### 8.3 The decision sequence (practical algorithm)

1. **How many teams will own this, and how independently must they ship?** One/few → (modular) monolith. Many autonomous → distribution. *(Conway first.)*
2. **Do the domain boundaries actually exist yet?** No → monolith; you don't know where to cut. Yes (clear bounded contexts) → candidates for services.
3. **Is there a genuine asymmetry** in scaling, availability, or tech among parts? Yes → extract *those* parts only. No → keep together.
4. **Can you afford the platform** (CI/CD, observability, mesh, on-call) for N services? No → stay monolith.
5. **Where do you need temporal decoupling / fan-out / replay?** There → events, layered onto whatever style.
6. **Where is load spiky and stateless?** There → serverless.
7. **Default and re-evaluate:** modular monolith now, extract by strangler fig when a module *earns* it. The senior move is to **delay irreversible decisions** until you have evidence.

---

## 9. Failure modes & debugging

Each style fails differently; below are the characteristic incidents, how to diagnose them with real tools, and real-world lessons.

### 9.1 Monolith / modular monolith

- **Resource leak takes down everything.** A memory leak or thread-pool exhaustion in one module crashes the shared process, killing unrelated features (blast-radius coupling).
  - *Diagnose:* heap dump + Eclipse MAT / `jmap`; thread dump (`jstack`) for stuck threads; GC logs for OOM trajectory; APM (e.g., flight recorder, `jcmd ... JFR.start`).
- **Slow build/startup** throttles the team — measure and modularize the build; cache; parallelize tests.
- **Deploy of a tiny fix risks the whole app** — mitigate with strong test coverage and canary releases.

### 9.2 Microservices

- **Cascading failure / retry storm.** Service C slows; B's calls to C pile up, exhausting B's threads; A then fails; the failure climbs the chain. Retries *amplify* the load on the dying service.
  - *Diagnose:* distributed trace (Jaeger) shows the slow hop; metrics show error-rate and thread-pool saturation propagating upstream; circuit-breaker state metrics.
  - *Fix/prevent:* circuit breakers (open to shed load), bulkheads (isolate thread pools), timeouts, retry budgets with exponential backoff + jitter, load shedding.
- **Distributed monolith symptoms:** you can't deploy one service without others; a schema change breaks several services. *Diagnose* by attempting an isolated deploy; *fix* by privatizing data and decoupling APIs.
- **Latency p99 blowup from fan-out** (tail amplification). *Diagnose* via trace percentiles per dependency. *Fix* by parallelizing, hedging, caching, reducing fan-out.
- **The dual-write inconsistency:** an event was published but the DB write rolled back (or vice versa). *Fix* with the transactional outbox.
- **Real incident archetype — the 2017 AWS S3 outage / general "thundering herd":** a dependency restart causes every client to reconnect/retry simultaneously, overwhelming it. Lesson: jittered backoff and capacity for recovery surges.

### 9.3 Event-driven

- **Poison message:** a malformed event repeatedly crashes the consumer, blocking the partition and stalling everything behind it.
  - *Diagnose:* consumer error logs + stuck offset / rising lag in Prometheus.
  - *Fix:* dead-letter queue after N failures; never block the stream on one bad message.
- **Consumer lag:** producers outrun consumers; events back up.
  - *Diagnose:* `kafka-consumer-groups.sh --describe` shows lag per partition; Grafana lag dashboard.
  - *Fix:* add partitions + consumers, optimize handler, batch.
- **Out-of-order / duplicate processing:** double-charge, duplicate email. *Fix:* idempotency + per-aggregate partition keying.
- **Rebalance storms:** frequent consumer churn pauses processing. *Fix:* static membership, cooperative-sticky assignor, tune timeouts.
- **Lost events from dual-write** (above) — outbox/CDC.

### 9.4 Serverless

- **Cold-start latency spikes**, especially JVM. *Diagnose:* CloudWatch `InitDuration`. *Fix:* SnapStart, GraalVM native, provisioned concurrency, smaller deps.
- **Throttling / concurrency limits hit:** invocations rejected at the account/function concurrency ceiling. *Diagnose:* `Throttles` metric. *Fix:* raise reserved concurrency, smooth bursts via a queue.
- **Runaway cost / recursive triggers:** a function writes to the bucket that triggers it → infinite loop and a surprise bill. *Fix:* guard triggers, set concurrency caps and budget alarms.
- **Silent retries causing duplicates:** async invocations retry on error. *Fix:* idempotency + DLQ.

### 9.5 General distributed debugging toolkit

- **Trace** a single request end-to-end by trace ID (OpenTelemetry/Jaeger) — answers "which hop is slow/failing."
- **Correlate** logs across services by trace/correlation ID (ELK/Loki).
- **Metrics dashboards** for the four golden signals + consumer lag + circuit-breaker state.
- **`kafka-consumer-groups.sh`, `jstack`, `jmap`, `jcmd`/JFR, `kubectl logs`, `kubectl describe`, mesh telemetry.**
- **Chaos engineering** (inject failures deliberately — kill pods, add latency — e.g., Chaos Monkey/Litmus) to verify resilience *before* production does it for you.

---

## 10. Interview drill

**Q1. What's the difference between a modular monolith and microservices, and why might you prefer the former?**
*Model answer:* Both have enforced boundaries; the difference is the *deployment unit*. A modular monolith is one deployable with internal module boundaries (enforced via build modules/JPMS/ArchUnit), so cross-module calls are in-process — fast, ACID-transactional, no network failures. Microservices make each boundary a separately deployable, network-addressable, data-owning service, gaining independent deploy/scale/fault-isolation at the cost of network latency, eventual consistency, and heavy operational machinery. Prefer the modular monolith when you want clean boundaries and future optionality without paying the distribution tax yet — it keeps the extract-later decision cheap and reversible.
- *Probe: How do you enforce module boundaries?* Build-tool module dependency graphs, JPMS `exports`, and ArchUnit tests in CI that fail on illegal cross-package access and cycles.
- *Probe: When does the modular monolith stop being enough?* When a module genuinely needs independent scaling/availability or a separate team's release cadence, or when the single process's blast radius/build time becomes the bottleneck.
- *Probe: What makes extraction cheap later?* No cyclic dependencies, calls only through published interfaces, and ideally per-module data ownership already in place.

**Q2. Explain the true costs of microservices beyond "it's more complex."**
*Model answer:* (1) **Network** — every internal call can be slow or fail; you need timeouts, retries, circuit breakers, and you suffer tail-latency amplification on fan-out. (2) **Data** — database-per-service means no cross-service ACID; you use sagas, eventual consistency, data duplication, and the dual-write problem. (3) **Ops** — N pipelines, distributed tracing, centralized logging, service discovery, on-call, mesh/gateway: a whole platform. (4) **Distributed transactions** — replaced by sagas with compensations and the loss of isolation. The fixed operational cost is the killer for small teams.
- *Probe: What's the dual-write problem and the fix?* Writing to DB then publishing an event non-atomically can lose/duplicate the event on crash; fix with the transactional outbox (or CDC) so both commit in one local transaction.
- *Probe: Why is exactly-once delivery essentially impossible?* Across system boundaries you can't atomically deliver-and-process; you get at-least-once and make processing idempotent → "effectively once."
- *Probe: What's tail-latency amplification?* If a request fans out to many services, the chance of hitting at least one slow (p99) service rises, so the aggregate latency is dominated by the worst dependency.

**Q3 (senior-signal). A startup with 6 engineers wants microservices from day one "to scale." What do you advise and why?**
*Model answer:* Push back. With 6 engineers and an unproven domain, microservices impose a large fixed operational tax (CI/CD, observability, on-call, distributed-data complexity) and you'll almost certainly draw the wrong boundaries — then pay to redraw them *across the network*. Conway's Law says one team naturally builds one cohesive unit; forcing splits yields a distributed monolith. Recommend a **modular monolith**: enforced boundaries, ACID, fast iteration, and cheap extraction later via strangler fig once the domain and load are understood and the team grows. Scale the monolith horizontally first; extract a service only when a capability *earns* it with a real asymmetry. This is the senior move: defer the irreversible, expensive decision until you have evidence.
- *Probe: When would you reverse this advice?* If they already have multiple autonomous teams, a mature platform, and a clearly bounded high-scale component (e.g., they're building infra where boundaries are known).
- *Probe: How do you keep "extract later" actually cheap?* No module cycles, calls through interfaces, per-module schemas, contract discipline.

**Q4. Explain Conway's Law and the Inverse Conway Maneuver.**
*Model answer:* Conway's Law: a system's structure mirrors the communication structure of the org that builds it, because interfaces are negotiated at team boundaries. Implication: architecture and org must be co-designed; a microservices architecture without aligned autonomous teams degrades into a distributed monolith. The Inverse Conway Maneuver deliberately restructures *teams* (small, full-stack, independently deploying) to produce the desired architecture, since the org shape will drive it anyway.
- *Probe: A symptom you'd look for?* Services that must always deploy together → the org/team boundaries don't match the service boundaries.
- *Probe: How does this affect the decision to split?* Don't create a service no single team can own end-to-end.

**Q5. How do you maintain data consistency across microservices without distributed transactions?**
*Model answer:* Use **sagas**: a business transaction is a sequence of local ACID transactions, each publishing an event that triggers the next; on failure, run **compensating transactions** to semantically undo prior steps. Choose **choreography** (events, decentralized) for simple flows or **orchestration** (a central coordinator like Temporal/Step Functions) for complex, observable ones. Accept **eventual consistency**, make handlers **idempotent**, key events per aggregate for ordering, and use the **outbox** for atomic publish.
- *Probe: Choreography vs orchestration tradeoff?* Choreography: loose coupling, hard to follow/observe; orchestration: clear/observable, but coupling to the orchestrator.
- *Probe: Sagas lack isolation — what breaks?* Others can read intermediate states (e.g., reserved-not-paid); mitigate with semantic locks/status flags, commutative updates, versioning.
- *Probe: When is a plain monolith transaction the better answer?* When the data genuinely belongs in one aggregate/consistency boundary — don't split what must be atomic.

**Q6. Walk me through migrating a monolith to microservices safely.**
*Model answer:* Use the **Strangler Fig**: put a façade/gateway in front, identify a bounded context, build it as a new service, route just that slice to it (reversible), repeat until the monolith is gone. Decompose the *database* carefully — break cross-boundary FKs/JOINs (replace with API calls or event-replicated read models), use CDC/Debezium to keep old and new in sync during transition, and shield the new clean model with an **anti-corruption layer**. Validate with **parallel-run/shadow traffic** before cutover. Prefer **branch by abstraction** for in-place swaps.
- *Probe: Hardest part?* The data — splitting the shared schema and removing cross-boundary joins/transactions.
- *Probe: How do you pick the first slice?* A core or high-asymmetry bounded context with few inbound dependencies, so it's low-risk and high-value.

**Q7. Compare event notification, event-carried state transfer, and event sourcing.**
*Model answer:* *Event notification* = thin "something changed," consumer calls back for data (low coupling, more chatter, runtime coupling on the callback). *Event-carried state transfer* = fat event carrying the data, so no callback (more decoupling, data duplication, larger events). *Event sourcing* = store the events themselves as the source of truth and rebuild state by replay (audit/time-travel, but snapshotting and event-versioning complexity).
- *Probe: When is event sourcing worth it?* Ledgers/finance/compliance where the audit log and replay are first-class requirements; overkill for CRUD.
- *Probe: What new operational burden does it add?* Forever-compatible event versioning (upcasting), snapshots, and eventually-consistent read-model projections (CQRS).

**Q8 (senior-signal). When is choosing a monolith the *more* senior decision than microservices?**
*Model answer:* When the team is small, the domain is unproven, there's no platform maturity, and the system needs strong cross-cutting transactional consistency. The senior signal is recognizing that microservices' benefits (independent deploy/scale/fault-isolation) are *organizational* solutions to *organizational* problems you don't yet have, while their costs (ops, distributed data, latency) are paid immediately. Choosing a modular monolith preserves velocity and optionality; you extract by strangler fig when evidence (load asymmetry, team growth) justifies it. Maturity is resisting résumé-driven architecture.
- *Probe: What evidence would flip you to extract a service?* A capability with genuinely different scaling/availability needs, or a team blocked by the shared pipeline.
- *Probe: How do you scale a monolith without microservices?* Horizontal replicas behind a load balancer, read replicas/caching/sharding for the DB, and offloading spiky work to async/serverless.

**Q9. How do you prevent cascading failures in a synchronous microservice mesh?**
*Model answer:* **Timeouts** (never block forever), **circuit breakers** (stop calling a failing dependency to shed load and let it recover), **bulkheads** (isolated thread pools so one slow dependency can't exhaust all threads), **retries with exponential backoff + jitter** (avoid retry storms/thundering herd), **load shedding**, and **graceful degradation** (fallbacks/cached results). A service mesh can enforce these via sidecars without code changes.
- *Probe: Why is naïve retry dangerous?* It amplifies load on an already-struggling dependency, accelerating collapse; need budgets + jitter + breakers.
- *Probe: What's a bulkhead vs a circuit breaker?* Bulkhead isolates resources (thread pools) so failures stay contained; breaker stops calls to a failing dependency entirely for a cooldown.

**Q10 (senior-signal). You're told to "go serverless to cut costs." How do you evaluate that?**
*Model answer:* Model the *actual* traffic. Serverless wins when load is spiky/low-baseline because you pay nothing while idle (scale-to-zero). At sustained high throughput, per-invocation pricing can exceed the cost of always-on containers, so it may *increase* cost. Also weigh cold starts (severe for JVM unless using SnapStart/GraalVM/provisioned concurrency), the 15-minute and payload limits, statelessness constraints, vendor lock-in, and operational opacity. Recommend serverless for spiky/event/glue workloads and keep steady high-throughput core on containers — i.e., a hybrid, decided by the traffic shape and latency SLOs, not by hype.
- *Probe: JVM-specific gotcha?* Cold-start latency from classloading + JIT; mitigate with SnapStart or GraalVM native image.
- *Probe: A hidden serverless failure mode?* Recursive triggers (function writes to the source that triggers it) → runaway cost; guard triggers and set concurrency/budget caps.

---

## 11. Glossary

- **ACID** — Atomicity, Consistency, Isolation, Durability: guarantees of a classic DB transaction.
- **ACL (Anti-Corruption Layer)** — translation layer protecting a clean model from a legacy/external model.
- **Aggregate / Aggregate root (DDD)** — cluster of objects changed as one unit; the consistency boundary; root is the only entry point.
- **API Gateway** — edge reverse proxy doing routing, auth, rate-limiting, TLS termination.
- **At-least-once / at-most-once / exactly-once** — message delivery guarantees; at-least-once (duplicates possible) + idempotency ≈ effectively-once.
- **Bounded context (DDD)** — boundary within which one domain model and its language are consistent; natural unit for a service.
- **Bulkhead** — resource isolation (e.g., separate thread pools) so one failure doesn't sink everything.
- **CAP theorem** — under a partition, choose Consistency or Availability.
- **CDC (Change Data Capture)** — streaming DB changes (from the WAL) as events; e.g., Debezium.
- **Choreography vs Orchestration** — decentralized event reactions vs a central coordinator directing steps.
- **Circuit breaker** — stops calls to a failing dependency for a cooldown to prevent cascades.
- **Cohesion** — degree to which elements inside a boundary belong together.
- **Conway's Law** — system structure mirrors org communication structure.
- **Coupling** — degree of interdependence between boundaries (afferent/efferent, temporal, data, deployment).
- **CQRS** — Command Query Responsibility Segregation: separate write and read models.
- **Cold start** — first invocation latency when a serverless environment must be provisioned.
- **Dead-letter queue (DLQ)** — destination for messages that repeatedly fail processing.
- **Distributed monolith** — services that must deploy together / share data: the worst of both worlds.
- **DDD (Domain-Driven Design)** — modeling software around the business domain; source of bounded contexts/aggregates.
- **Event sourcing** — storing events as the source of truth; rebuild state by replay.
- **Event-carried state transfer** — events carry full data so consumers needn't call back.
- **Eventual consistency** — replicas converge over time, not immediately.
- **Fallacies of Distributed Computing** — eight false assumptions about networks.
- **FaaS (Functions as a Service)** — serverless functions triggered by events.
- **Firecracker** — lightweight microVM tech powering AWS Lambda isolation.
- **Four Golden Signals** — latency, traffic, errors, saturation (SRE).
- **GraalVM native image** — AOT-compiled JVM binary with fast startup/low memory.
- **Idempotent** — same effect whether applied once or many times.
- **Inverse Conway Maneuver** — restructure teams to produce a desired architecture.
- **IPC** — inter-process communication.
- **JPMS** — Java Platform Module System (`module-info.java`, `exports`).
- **Modular monolith** — single deployable with enforced internal module boundaries.
- **mTLS** — mutual TLS; both client and server authenticate with certificates.
- **Outbox (transactional)** — write event + state in one local transaction; relay publishes later.
- **PACELC** — extension of CAP: under Partition trade A/C, Else trade Latency/Consistency.
- **Partition (network)** — split where node groups can't communicate; risks split-brain.
- **Poison message** — a message that repeatedly fails and blocks processing.
- **Polyglot persistence** — different services using different database technologies.
- **Saga** — distributed transaction as local transactions + compensations.
- **Service discovery / registry** — runtime resolution of logical service names to instances.
- **Service mesh** — sidecar-based layer for mTLS, retries, timeouts, telemetry.
- **Sidecar** — helper container alongside a service handling cross-cutting concerns.
- **SnapStart** — AWS Lambda JVM cold-start mitigation via snapshot/restore.
- **Strangler Fig** — incremental migration by routing slices to new services behind a façade.
- **Snapshot (event sourcing)** — periodic materialized state to avoid full replay.
- **Tail-latency amplification** — fan-out makes hitting a slow dependency likely, worsening aggregate latency.
- **Thundering herd** — many clients retry/reconnect simultaneously, overwhelming a recovering dependency.
- **Two-phase commit (2PC)** — prepare/commit protocol for distributed atomicity; blocking, fragile under partitions.
- **Ubiquitous language (DDD)** — shared precise vocabulary within a bounded context.
- **Virtual threads (Loom)** — cheap JVM threads enabling high-concurrency blocking code.
- **WAL (Write-Ahead Log)** — ordered log of DB changes; source for CDC and crash recovery.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The dial:** in-process simplicity ←→ distributed independence. Distribution is *never free*: it converts ns method calls into ms network calls that can partially fail and lose ACID.

**Default:** modular monolith → extract by strangler fig when a capability *earns* it.

**Numbers to know:** method call ≈ 1 ns; intra-DC RPC ≈ 0.5–5 ms (≈10⁶× costlier). Tomcat default threads = 200; HikariCP default pool = 10. Kafka default delivery = at-least-once. Lambda max runtime = 15 min; JVM cold start = 100s of ms–seconds (mitigate: SnapStart / GraalVM). CAP: under partition pick C or A.

**Laws/principles:** Conway (architecture mirrors org) + Inverse Conway (reshape teams first); Fallacies of Distributed Computing (network is *not* reliable/zero-latency/secure); CAP/PACELC.

**Data rule:** service boundary = data-ownership boundary (database-per-service). No shared DB. Cross-service consistency → saga + idempotency + outbox + eventual consistency.

**Boundaries:** find them with DDD bounded contexts / event storming; aggregate = transactional consistency boundary (don't split what must be atomic). Decompose by capability/subdomain, never by technical layer.

**Resilience kit:** timeout + retry(backoff+jitter) + circuit breaker + bulkhead + DLQ + graceful degradation.

**Anti-patterns:** distributed monolith, shared DB, synchronous call chains, nano-services, premature decomposition, no idempotency, ignoring Conway.

**Decision order:** teams & deploy independence → do boundaries exist → real scaling/availability asymmetry → can you afford the platform → where temporal-decoupling/fan-out → where spiky/stateless. Defer irreversible choices.

**Observability is mandatory when distributed:** distributed tracing (trace ID through sync calls *and* events) + four golden signals + consumer lag.

### 12.2 Self-test (no answers — recall actively)

1. Explain why two services sharing one database recreates the worst of both monolith and microservices, and name the specific couplings it introduces.
2. A request fans out synchronously to 8 services, each with p99 = 80 ms. Why is the aggregate p99 far worse than 80 ms, and what three techniques reduce it?
3. Write (from memory) the transactional-outbox flow and explain exactly which failure it prevents that a naïve "save then publish" suffers.
4. You must choose between choreographed and orchestrated sagas for a 6-step checkout with strict observability requirements. Which, and what do you give up?
5. Your JVM Lambda has acceptable warm latency but unacceptable p99 due to cold starts. List the mitigations in order of preference and the tradeoff each carries.
6. Give a concrete sequence of steps to split a shared `orders`/`customers` schema during a strangler-fig migration, keeping old and new in sync.
7. Describe a cascading-failure incident in a synchronous mesh and the exact resilience controls that would have contained it, in the order they engage.

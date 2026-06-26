# Distributed Tracing

> An engineering-handbook chapter for senior JVM/backend developers who want to master distributed tracing from first principles to deep internals: enough to design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What it is

**Distributed tracing** is a technique for following a single logical operation — usually a user request — as it travels through many processes, services, threads, queues, and machines, recording the path it took and how long each step cost. The output is a **trace**: a directed graph (almost always a tree) of timed work units called **spans**, stitched together by a shared identifier so you can see the whole request end-to-end even though it touched a dozen services.

If a single monolithic process gives you a **stack trace** (the call hierarchy *inside* one process at one instant), a distributed trace gives you the analogous thing *across* processes and *over time*: who called whom, in what order, how long each leg took, and where it failed.

### 1.2 The problem it solves

In a monolith, when a request is slow or wrong you attach a profiler or read a stack trace and you are done — everything happened in one address space. In a **microservices** architecture (an application split into many independently deployed services that communicate over the network), a single user click can fan out to 20–200 service calls across many hosts. Now the hard questions become:

- **Which** of the 40 services on the request path was slow? (Latency attribution.)
- **Where** in the call graph did the error actually originate, versus where it merely surfaced? (Root-cause localization.)
- **What** was the exact sequence and parallelism of calls for *this specific* slow request, not the average? (Per-request causality.)
- **How** does a 99th-percentile (p99) slow request differ from a median one? (Tail analysis.)

Logs alone can't answer these: logs from different services are interleaved across thousands of machines with no shared thread of causality. Metrics (aggregate counters/gauges/histograms) tell you *that* p99 latency is bad but not *which request* or *which hop*. Tracing supplies the missing dimension: **causal, per-request, cross-service structure with timing**.

> **The three pillars of observability.** *Observability* is the property of being able to ask arbitrary questions about a system's internal state from its external outputs, without shipping new code. It is conventionally built on three telemetry signals: **metrics** (cheap aggregate numbers over time — request rate, error rate, latency histograms), **logs** (discrete timestamped text/structured events), and **traces** (per-request causal graphs). The signals are complementary: metrics tell you *something is wrong*, traces tell you *where*, logs tell you *why*. The modern goal is to **correlate all three** (covered in §6.5).

### 1.3 When you reach for it

Reach for distributed tracing when:

- You run more than a handful of services and "which service is slow?" is a recurring, expensive question.
- You have high fan-out or deep call chains (service A → B → C → D …).
- You need to debug **latency tails** (p95/p99) rather than averages.
- You want to understand emergent behavior: retries, cascading timeouts, N+1 query explosions, hidden synchronous dependencies.
- You need **service dependency maps** derived from real traffic rather than stale architecture diagrams.

You probably *don't* need full tracing if you have a single service with no downstream calls — local profiling and structured logs suffice. But even then, span-based instrumentation is often worth adopting early because it's cheap to add and pays off the moment a second service appears.

### 1.4 The one-paragraph mental model

> Every inbound request gets a globally unique **trace ID**. Each unit of work within that request — an HTTP handler, a DB query, a Kafka publish — is a **span** with its own **span ID**, a start time, a duration, a set of key/value **attributes**, and a pointer to its **parent span**. When one service calls another, it injects the trace ID + current span ID into the outbound request (e.g., into an HTTP header); the callee extracts them and makes its spans children of the caller's span. This act of carrying the IDs across a boundary is **context propagation**. Spans are exported asynchronously to a **backend** that reassembles them by trace ID into a tree and lets you visualize the request as a waterfall/Gantt chart. Because recording everything is expensive, you **sample** — keep some traces, drop others.

That paragraph is the whole field in miniature. The rest of this chapter unpacks every clause of it.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Each term is defined the first time it appears.

### 2.1 Trace

A **trace** represents the end-to-end journey of one logical operation through a distributed system. Concretely, a trace is the set of all spans that share the same **trace ID**. When you reassemble those spans by their parent/child links you get a tree (sometimes a more general DAG when there's parallel fan-out and join), rooted at the **root span** — the first span created for the request, the one with no parent.

- A trace has no inherent duration field of its own; its "duration" is derived as the wall-clock span from the earliest span start to the latest span end.
- A trace ID in OpenTelemetry / W3C is a **128-bit** value (16 bytes), conventionally rendered as 32 lowercase hex characters, e.g. `4bf92f3577b34da6a3ce929d0e0e4736`. (Older systems like Zipkin started with 64-bit IDs and later supported 128-bit.)

### 2.2 Span

A **span** is the fundamental building block: a single named, timed unit of work. A span carries:

| Field | Meaning |
|---|---|
| **Name** | A low-cardinality human label, e.g. `GET /orders/{id}`, `SELECT orders`, `kafka.publish`. |
| **SpanContext** | The immutable, propagatable identity: trace ID + span ID + trace flags + trace state (see §2.4). |
| **Parent span ID** | The span ID of the span that caused this one (empty for the root). |
| **Start / end timestamps** | Wall-clock start and end; their difference is the span's **duration/latency**. |
| **SpanKind** | One of `SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL` (see §2.7). |
| **Status** | `UNSET` (default), `OK`, or `ERROR` (with an optional description). |
| **Attributes** | Key/value metadata (e.g. `http.request.method=GET`, `db.system=postgresql`). |
| **Events** | Timestamped points within the span (e.g. an exception, a "cache miss"). |
| **Links** | References to *other* spans not in the strict parent/child line (e.g. batch causes). |
| **Resource** | Description of the entity producing the span (service name, host, k8s pod). Usually attached once per process, not per span. |

A span is created (started), optionally annotated (attributes/events added), and then **ended**. Once ended it is immutable and eligible for export. The span's lifecycle is the unit of measurement; ending the span is what stamps its duration.

> **Cardinality.** *Cardinality* is the number of distinct values a field can take. Span **names** should be **low-cardinality** (template the route: `GET /orders/{id}`, never `GET /orders/12345`) so they aggregate well. High-cardinality data (the actual ID `12345`) belongs in **attributes**, not the name. Putting high-cardinality values in names explodes the number of distinct operations and breaks dashboards and indexes.

### 2.3 Parent / child relationships and the span tree

When span B is created *because* span A is executing — e.g., the order service (span A) calls the payment service (span B) — B records A's span ID as its **parent**. This builds the tree:

```
[root: GET /checkout]                         (SERVER span, service: gateway)
 ├─ [GET /cart]                                (CLIENT span; child = SERVER span in cart-svc)
 │   └─ [SELECT cart_items]                     (CLIENT span; DB)
 ├─ [POST /payment]                             (CLIENT span; child = SERVER span in payment-svc)
 │   ├─ [HTTP GET risk-engine]                  (CLIENT span)
 │   └─ [SELECT account_balance]                (CLIENT span; DB)
 └─ [kafka publish order.created]               (PRODUCER span; linked CONSUMER span downstream)
```

Each `└─`/`├─` nesting is a parent→child edge encoded by the child storing the parent's span ID and the same trace ID. The backend reconstructs the tree purely from (trace ID, span ID, parent span ID).

### 2.4 SpanContext, trace flags, trace state

The **SpanContext** is the small, serializable bundle that must travel across boundaries to keep a trace connected. It contains:

- **Trace ID** (128-bit): identifies the whole trace.
- **Span ID** (64-bit, 16 hex chars): identifies *this* span; becomes the *parent* span ID for downstream spans.
- **Trace flags** (8-bit bitfield): currently only the low bit is defined — the **sampled flag** (`0x01`), indicating that this trace is being recorded/exported. This is how the *sampling decision* propagates (head-based sampling, §2.10 / §3.6).
- **Trace state**: an optional, ordered list of vendor key/value pairs (e.g. `congo=t61rcWkgMzE,rojo=00f067aa0ba902b7`) that lets multiple tracing vendors carry their own data along the same trace. Rarely touched by application code.

SpanContext is **immutable** and is the *only* part of a span that is propagated; the attributes/events/etc. stay local and are exported separately.

### 2.5 Attributes (tags)

**Attributes** are typed key/value pairs attached to a span (also called **tags** in Zipkin/older OpenTracing terminology). They carry the dimensional metadata you filter and group by: `http.response.status_code=500`, `db.system=postgresql`, `messaging.system=kafka`, `user.tier=premium`. OpenTelemetry publishes **semantic conventions** — a standardized dictionary of attribute names — so that an HTTP method is *always* `http.request.method` regardless of language or library, which is what makes backends able to build generic dashboards.

Attribute values may be strings, booleans, ints, doubles, or homogeneous arrays of those. Avoid putting secrets (passwords, full card numbers, auth tokens) into attributes — they will be stored and visible in the backend (security, §6.4).

### 2.6 Events and links

- An **event** (called an **annotation/log** historically) is a timestamped message inside a span — a structured "log line scoped to this span." The canonical example is recording an exception: OpenTelemetry's `recordException` adds an event named `exception` with attributes `exception.type`, `exception.message`, `exception.stacktrace`.
- A **link** connects a span to one or more *other* spans that are causally related but not in a direct parent/child line. The classic use is **batching**: a consumer span that processes 100 messages from 100 different traces can't have 100 parents, so it **links** to all 100 producer spans. Another use is fan-in joins.

### 2.7 SpanKind

**SpanKind** tells the backend the role a span plays in a remote call, which is essential for building latency breakdowns and dependency maps:

| Kind | Meaning | Typical example |
|---|---|---|
| `SERVER` | Handles an inbound synchronous request. | An HTTP handler receiving a request. |
| `CLIENT` | Makes an outbound synchronous request and waits. | An HTTP client call, a JDBC query. |
| `PRODUCER` | Sends a message to be processed later (async). | Publishing to Kafka/RabbitMQ/SQS. |
| `CONSUMER` | Receives/handles an async message. | A Kafka consumer processing a record. |
| `INTERNAL` | Work within a service, no remote boundary. | A business-logic method, an in-process computation. |

A remote call is typically represented by a **CLIENT span on the caller and a SERVER span on the callee**, both children of the same logical edge, with the SERVER span's parent being the CLIENT span. The difference between the CLIENT span duration and the SERVER span duration is roughly the network + queueing time.

### 2.8 Resource

A **Resource** describes the *producer* of telemetry — the service and the environment it runs in. It's a set of attributes set once per process (or per SDK instance): `service.name`, `service.version`, `service.instance.id`, `host.name`, `cloud.region`, `k8s.pod.name`, etc. `service.name` is effectively mandatory — backends key dependency maps and service lists off it. If you forget to set it, OpenTelemetry defaults `service.name` to `unknown_service:java` (or similar), and your traces will be unattributed.

### 2.9 Instrumentation: auto vs manual

**Instrumentation** is the code that creates spans. Two flavors:

- **Automatic (auto) instrumentation**: a library or agent transparently creates spans for common frameworks (Spring MVC, JDBC, gRPC, Kafka clients, the JDK HTTP client) without you writing span code. On the JVM this is typically done with a **Java agent** that uses **bytecode instrumentation** (rewriting class bytecode at load time) to wrap library methods. You get HTTP, DB, and messaging spans "for free."

  > **Java agent / bytecode instrumentation.** A *Java agent* is a special JAR attached via the `-javaagent:` JVM flag. It hooks into the JVM's class-loading via the `java.lang.instrument` API and can rewrite (instrument) the bytecode of classes as they load, injecting tracing calls around library methods. This is how OpenTelemetry, Datadog, New Relic, etc. trace your app without source changes.

- **Manual instrumentation**: you call the tracing API yourself to create spans around *your* business logic (e.g., wrap an expensive pricing calculation in a span, add domain attributes like `order.value`). Auto gives breadth; manual gives the domain-specific depth auto can't know about. Real systems use both.

### 2.10 Sampling (preview)

Recording and storing every span for every request is expensive (CPU, network, storage). **Sampling** keeps a representative subset. The big distinction:

- **Head-based sampling**: the keep/drop decision is made at the *start* of the trace (at the root, "the head") and propagated via the sampled flag so all services agree. Cheap, simple, but you decide before you know whether the trace was interesting (slow/errored).
- **Tail-based sampling**: buffer all spans of a trace, then decide *after* the trace completes ("the tail"), so you can keep all errors and slow traces. More valuable but requires buffering whole traces in a collector — more memory and infrastructure.

This is fleshed out in §3.6 and §7.

### 2.11 Backend (tracing system)

A **backend** (a.k.a. tracing system) is where spans are sent, stored, indexed, and visualized. Examples: **Jaeger**, **Grafana Tempo**, **Zipkin**, plus commercial ones (Datadog, Honeycomb, Lightstep/ServiceNow, New Relic, AWS X-Ray, Google Cloud Trace). The backend reassembles spans into traces by trace ID and renders the waterfall/Gantt view, service maps, and latency analytics. Covered in §4 and §8.

### 2.12 OpenTelemetry (preview)

**OpenTelemetry (OTel)** is the cross-language, vendor-neutral standard for generating, collecting, and exporting telemetry (traces, metrics, logs). It is a **CNCF** project (the Cloud Native Computing Foundation — the open-source foundation that also hosts Kubernetes, Prometheus, etc.) formed in 2019 by merging two earlier projects, **OpenTracing** (an API standard) and **OpenCensus** (Google's API+SDK). OTel is now the de facto standard; most backends ingest OTel data. Details in §3 and §4.

---

## 3. How it works internally

This is the heart of the chapter. We trace (pun intended) the full lifecycle: how a span is born, annotated, propagated across a boundary, sampled, batched, exported, and reassembled. We use OpenTelemetry's architecture as the reference because it's the standard, and we focus on the Java SDK behavior.

### 3.1 The OpenTelemetry architecture at a glance

OTel separates concerns into layers so that instrumentation code is decoupled from the choice of backend:

```
   Your code + libraries
        │  (calls)
        ▼
   ┌──────────────┐      API: interfaces only; no-op by default.
   │   OTel API   │      Instrumentation depends ONLY on this.
   └──────────────┘
        │  (implemented by)
        ▼
   ┌──────────────┐      SDK: the real implementation — samplers,
   │   OTel SDK   │      span processors, exporters, resource.
   └──────────────┘
        │  (exports via OTLP / Jaeger / Zipkin protocol)
        ▼
   ┌──────────────┐      Collector (optional but recommended): receives,
   │  Collector   │      processes (batch, sample, redact, enrich), routes.
   └──────────────┘
        │
        ▼
   ┌──────────────┐
   │   Backend    │      Jaeger / Tempo / Zipkin / vendor.
   └──────────────┘
```

- **API**: the surface your code (and library authors) compile against — `Tracer`, `Span`, `SpanBuilder`, `Context`. Crucially, **if no SDK is installed, the API is a no-op** (zero-cost stubs). This means a library can ship OTel instrumentation safely; it does nothing until an app opts in by adding the SDK. This decoupling is OTel's central design move.
- **SDK**: the concrete engine. It owns the **Sampler**, the **SpanProcessor(s)**, the **SpanExporter(s)**, the **Resource**, the **ContextPropagators**, and ID generation. You configure it once at startup (the `SdkTracerProvider`).
- **Collector**: a standalone binary/sidecar/daemon that receives telemetry (commonly over **OTLP** — the OpenTelemetry Protocol, a gRPC/HTTP+protobuf wire format), runs it through **processors** (batching, redaction, tail-sampling, attribute enrichment), and **exports** to one or more backends. The Collector decouples your apps from backend specifics and centralizes policy.

### 3.2 The Context object and how "current span" works

To make instrumentation ergonomic, OTel keeps an implicit **current Context** — an immutable map that holds, among other things, the currently active SpanContext. Internally:

- **Context** is an immutable key→value container. Setting a value returns a *new* Context.
- The "active" Context is stored in a **ContextStorage**, which by default is backed by a **`ThreadLocal`**.

  > **ThreadLocal.** A `ThreadLocal<T>` in Java is a variable whose value is *per-thread*: each thread sees its own independent copy. OTel uses it so that "the current span" is whatever span the *current thread* has made active — no need to pass a span object through every method signature.

- You make a span current with `span.makeCurrent()`, which returns a `Scope` (an `AutoCloseable`). Code running on that thread inside the `try (Scope s = span.makeCurrent())` block sees that span as current via `Span.current()`. Closing the scope restores the previous Context.

The critical consequence: **the current span follows the thread, not the request.** If your request hops to another thread (a thread pool, a reactive scheduler, a `CompletableFuture` continuation) without explicitly carrying the Context, the new thread sees the *wrong* (or no) current span, and your child spans either attach to the wrong parent or become new roots ("broken traces"). Handling this is §3.5.

### 3.3 Span lifecycle — step by step

Here is the exact internal sequence when you create and end a span with the SDK installed.

**Start:**
1. Code calls `tracer.spanBuilder("name")...startSpan()`.
2. The builder resolves the **parent**: by default, the parent is the span in the *current Context* (`Context.current()`). If none, this becomes a **root** span (no parent; a fresh trace ID will be generated).
3. The SDK **generates IDs**: a new 64-bit span ID always; a new 128-bit trace ID *only if* this is a root span (otherwise it inherits the parent's trace ID). The default `IdGenerator` uses a `ThreadLocalRandom`-based source for performance.
4. The SDK invokes the **Sampler** (`shouldSample(parentContext, traceId, name, kind, attributes, links)`). The sampler returns one of:
   - `RECORD_AND_SAMPLE` — record the span *and* set the sampled flag (will be exported).
   - `RECORD_ONLY` — record in-process (so local span processors/metrics can see it) but do *not* set the sampled flag (won't be exported). Used for some advanced setups.
   - `DROP` — do not record; the span becomes a cheap **non-recording span** that still carries a valid SpanContext (so propagation works) but stores no attributes/events and won't be exported.
   The sampler can also *mutate* the trace state.
5. A `ReadWriteSpan` (recording) or a non-recording span is created. The sampled flag is written into the SpanContext's trace flags.
6. Each registered **SpanProcessor** gets an `onStart(span, parentContext)` callback (synchronous). The `BatchSpanProcessor` largely ignores `onStart`; some processors (e.g., ones that add baggage as attributes) act here.
7. The `Span` is returned. The code typically calls `makeCurrent()` to activate it.

**During:**
8. Code calls `span.setAttribute(...)`, `span.addEvent(...)`, `span.recordException(e)`, `span.setStatus(...)`. These mutate the in-memory span (only meaningful if it's recording; on a sampled-out span they are cheap no-ops).

**End:**
9. Code calls `span.end()` (ideally in a `finally`). This stamps the end timestamp/duration, marks the span immutable, and triggers each SpanProcessor's `onEnd(readableSpan)`.
10. The `BatchSpanProcessor.onEnd` checks the sampled flag; if sampled, it enqueues the span into an in-memory queue. (If the queue is full, the span is **dropped** and a counter increments — backpressure, §6.1.)
11. A background thread in the BatchSpanProcessor periodically drains the queue (every `scheduleDelay`, default 5s, or when `maxExportBatchSize` is reached) and hands a batch to the **SpanExporter**.
12. The exporter serializes the batch (e.g., to OTLP protobuf) and sends it to the Collector/backend over gRPC or HTTP. Export is asynchronous and off the request hot path.

### 3.4 Context propagation — the core mechanism

This is the magic that connects services. **Propagation** = serializing the SpanContext (and optionally baggage) at the *outbound* boundary of one process (**inject**) and deserializing it at the *inbound* boundary of the next process (**extract**).

**The W3C Trace Context standard** (a W3C Recommendation since 2020) defines two HTTP headers:

- **`traceparent`** — the required, fixed-format header carrying the core IDs. Format:
  ```
  traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
               ^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^
               |  trace-id (16 bytes / 32 hex)      span-id (8B/16h) flags
               version (00)                         (the parent span)  (01 = sampled)
    ```
  - `version`: currently `00`.
  - `trace-id`: 32 hex chars; must not be all zeros.
  - `parent-id` (a.k.a. span-id): the span ID of the *caller's current span*; becomes the parent of the callee's server span. 16 hex chars.
  - `trace-flags`: 2 hex chars; bit 0 = sampled. `01` means sampled, `00` means not.
- **`tracestate`** — the optional multi-vendor key/value list (mirrors §2.4).

> **W3C.** The World Wide Web Consortium — the standards body for web technologies. Trace Context becoming a W3C standard is why disparate vendors and languages interoperate; before it, everyone had their own header format and cross-vendor traces broke at boundaries.

**B3 propagation** is the older format originating from **Zipkin** (named after Brave/B3). It comes in two encodings:

- **Multi-header**: `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-ParentSpanId`, `X-B3-Sampled`, `X-B3-Flags`.
- **Single-header**: `b3: {traceId}-{spanId}-{samplingState}-{parentSpanId}` (e.g. `b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1-05e3ac9a4f6e3b90`).

B3 is still widely deployed (Istio/Envoy, many Spring Cloud Sleuth-era systems default to or support B3). Modern OTel defaults to **W3C `tracecontext` + `baggage`** propagators, but you can configure B3 for interop with a B3 mesh.

**Baggage** is a separate W3C standard header (`baggage: key1=val1,key2=val2`) for propagating *arbitrary application key/values* across the whole trace (e.g., `tenant.id=acme`, `session.id=...`). Baggage is **not** automatically copied onto spans as attributes (you must do that explicitly) and travels to *every* downstream service — so never put secrets or large values in baggage, and be aware it crosses trust boundaries.

**Inject/extract mechanics (the `TextMapPropagator` interface):**
- `inject(Context, carrier, setter)`: reads the current SpanContext from the Context and writes the headers into the *carrier* (e.g., the outbound HTTP request's header map) using a `setter` function.
- `extract(Context, carrier, getter)`: reads the headers from the inbound *carrier* and returns a new Context containing the remote SpanContext (which downstream span creation will use as parent).

A **carrier** is just the medium that holds the propagation data — HTTP headers, gRPC metadata, Kafka record headers, an AMQP message's headers, etc. The `getter`/`setter` are small adapters that know how to read/write that specific carrier type.

### 3.5 Propagation across the *hard* boundaries

HTTP is the easy case (auto-instrumentation handles inject/extract). The tricky boundaries:

- **Across threads (in-process):** because current span lives in a ThreadLocal, handing work to another thread loses it. Fixes:
  - Capture the Context with `Context current = Context.current();` and re-activate on the worker thread: `try (Scope s = current.makeCurrent()) { ... }`.
  - Use OTel's helper `Context.taskWrapping(executor)` or `context.wrap(runnable/callable)` to auto-propagate.
  - With Reactor/RxJava, use the project's context-propagation integration (e.g., Reactor's `ContextPropagation` + Micrometer's context-propagation library) so the OTel Context rides the reactive `Context`.
- **Across async messaging (Kafka/Rabbit/SQS):** the producer **injects** traceparent into the *message headers*; the consumer **extracts** them. Because consumption is async and may be batched, the consumer span is usually `CONSUMER` kind and uses **links** to the producer span(s) rather than a strict parent (especially for batch poll). OTel's Kafka instrumentation does this automatically.
- **Across process restarts / scheduled jobs / queues with no header support:** you may need to manually stash the traceparent (e.g., in a DB column or job payload) and reconstruct the Context on resume.

### 3.6 The sampling decision flow

Head sampling decisions happen at span start (step 4 above). The decision must be **consistent** across the whole trace, which is achieved by propagating the sampled flag:

- The **root** service runs its sampler. Common default: `ParentBased(root=TraceIdRatioBased(p))`.
  - **`ParentBased`**: if there's a remote parent, *respect the parent's sampled flag* (so the whole trace agrees); only if there's no parent (i.e., we're the root) do we consult the delegate sampler.
  - **`TraceIdRatioBased(p)`**: keep a fraction `p` of traces. It works by hashing the trace ID into the 64-bit space and keeping the trace if the value falls below `p × 2^64`. Because the decision is a deterministic function of the *trace ID*, every service that runs the same ratio sampler on the same trace ID makes the same decision — **consistent sampling** without coordination. (In practice ParentBased makes downstream services just obey the flag, but TraceIdRatioBased's determinism is the fallback that keeps things consistent.)
- The decision is encoded in the sampled flag and propagated. Downstream `ParentBased` samplers obey it. Net effect: **either the whole trace is kept or the whole trace is dropped** — you never get half a trace from head sampling.

**Tail sampling** can't happen in the SDK (the SDK has already let spans go), so it lives in the **Collector**: the `tail_sampling` processor buffers all spans of a trace (keyed by trace ID) for a configured wait time, then applies policies (keep if any span errored, keep if duration > X, keep p% of the rest, rate-limit, attribute-based) and decides per-trace. The cost is that the collector must (a) see *all* spans for a trace, which constrains how you shard/load-balance collectors, and (b) hold them in memory until the decision window elapses. Details and tuning in §7.

### 3.7 Reassembly at the backend

The backend receives spans (often out of order, from many services, across time). It:
1. Indexes each span by `traceId`, `spanId`, `service.name`, name, duration, and selected attributes.
2. On query, gathers all spans for a `traceId` and links them by `parentSpanId` to build the tree.
3. Renders a **waterfall** (each span a horizontal bar positioned by start time, length by duration, nested by depth) so you can eyeball where time went and where errors occurred.
4. Often derives **service dependency graphs** and **RED metrics** (Rate, Errors, Duration) per service/operation from the trace stream — these are **span metrics** (metrics generated *from* spans), e.g. Tempo's `metrics-generator` or the Collector's `spanmetrics` connector.

Clock skew is handled heuristically: because each service stamps timestamps with its own clock, a child can appear to start "before" its parent. Backends apply **clock-skew adjustment** (shifting child spans to fit within parents based on the known causal ordering) to make the waterfall sane.

> **Clock skew.** Different machines' clocks drift apart by milliseconds even with NTP (the Network Time Protocol that syncs clocks). Since spans on different hosts are timestamped by different clocks, raw timestamps can disagree about ordering. Backends correct for this using the causal constraints (a child must be within its parent).

---

## 4. The complete toolkit

This section enumerates the APIs, classes, SDK components, Collector pieces, environment variables, and CLI/config knobs you'll actually use — defaults included. Java-first where relevant.

### 4.1 OpenTelemetry Java API surface (what your code calls)

| Type / method | Purpose | Notes / key params |
|---|---|---|
| `OpenTelemetry` | Entry point; holds `TracerProvider`, `ContextPropagators`. | Get a global via `GlobalOpenTelemetry.get()` or inject your own instance. |
| `TracerProvider.get(name, version?)` | Factory for `Tracer`s. | `name` = instrumentation scope (your library/module name), not the service name. |
| `Tracer.spanBuilder(name)` | Begin building a span. | Returns `SpanBuilder`. |
| `SpanBuilder.setSpanKind(kind)` | Set CLIENT/SERVER/etc. | Default `INTERNAL`. |
| `SpanBuilder.setParent(context)` | Explicit parent. | Default = current context's span. |
| `SpanBuilder.setNoParent()` | Force a new root. | Use for genuinely independent traces. |
| `SpanBuilder.setAttribute(k,v)` | Pre-start attributes (visible to sampler). | Attributes set before start can influence sampling. |
| `SpanBuilder.addLink(spanContext, attrs?)` | Add a link. | For batch/fan-in causality. |
| `SpanBuilder.startSpan()` | Create and start. | Returns `Span`. |
| `Span.current()` | The active span on this thread. | Returns a no-op span if none. |
| `Span.makeCurrent()` | Activate span; returns `Scope`. | **Must** close the scope (try-with-resources). |
| `Span.setAttribute(k,v)` | Add attribute. | Prefer semantic-convention keys. |
| `Span.addEvent(name, attrs?)` | Add timestamped event. | |
| `Span.recordException(t, attrs?)` | Record an exception as an event. | Does *not* set status to ERROR by itself. |
| `Span.setStatus(StatusCode, desc?)` | Set OK/ERROR/UNSET. | Set ERROR explicitly on failures. |
| `Span.updateName(name)` | Rename (e.g., once route is known). | |
| `Span.end()` / `end(timestamp)` | Finish the span. | Idempotent-ish; must be called exactly once logically. |
| `Context` / `Context.current()` | Immutable context container. | Holds current span + baggage. |
| `context.with(span)` / `context.makeCurrent()` | Derive/activate context. | |
| `Baggage` / `Baggage.current()` | Read/write baggage. | `Baggage.current().toBuilder().put(k,v).build().makeCurrent()`. |

**Annotation-based instrumentation** (with the OTel Java agent or the `opentelemetry-instrumentation-annotations` lib):
- `@WithSpan` on a method auto-creates a span around it; method args can be captured with `@SpanAttribute`.

### 4.2 OpenTelemetry Java SDK components (what you configure at startup)

| Component | Purpose | Key options & defaults |
|---|---|---|
| `SdkTracerProvider` | The SDK's TracerProvider. | Holds sampler, processors, resource, ID generator, span limits. |
| `Resource` | Identifies the producer. | Set `service.name` (else `unknown_service`). Merge with `Resource.getDefault()`. |
| **Sampler** | The head-sampling policy. | Default in autoconfig: `ParentBased(AlwaysOn)`. Options: `AlwaysOn`, `AlwaysOff`, `TraceIdRatioBased(p)`, `ParentBased(...)`. |
| **SpanProcessor** | Hook on span start/end; routes to exporter. | `BatchSpanProcessor` (prod), `SimpleSpanProcessor` (dev/test only — exports synchronously per span). |
| `BatchSpanProcessor` config | Batches spans before export. | `scheduleDelay`=5s, `exporterTimeout`=30s, `maxQueueSize`=2048, `maxExportBatchSize`=512. |
| **SpanExporter** | Serializes + sends batches. | `OtlpGrpcSpanExporter`, `OtlpHttpSpanExporter`, `JaegerGrpcSpanExporter` (deprecated — use OTLP), `ZipkinSpanExporter`, `LoggingSpanExporter` (debug), `InMemorySpanExporter` (tests). |
| `IdGenerator` | Generates trace/span IDs. | Default random; `AwsXrayIdGenerator` for X-Ray format. |
| `SpanLimits` | Caps to bound memory. | `maxNumAttributes`=128, `maxNumEvents`=128, `maxNumLinks`=128, `maxAttributeValueLength`=unlimited by default. |
| `ContextPropagators` | Inject/extract policy. | Default (autoconfig): W3C `tracecontext` + `baggage`. Add `b3`/`b3multi`/`jaeger` for interop. |

### 4.3 OTLP exporter & autoconfiguration environment variables

The OTel SDK supports **zero-code autoconfiguration** via env vars / system properties. The most important:

| Variable | Purpose | Default |
|---|---|---|
| `OTEL_SERVICE_NAME` | Sets `service.name`. | `unknown_service:java` |
| `OTEL_RESOURCE_ATTRIBUTES` | Extra resource attrs (`k=v,k=v`). | — |
| `OTEL_TRACES_EXPORTER` | Which exporter(s). | `otlp` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Collector/backend endpoint. | `http://localhost:4317` (gRPC) / `:4318` (HTTP) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` / `http/protobuf`. | `grpc` (agent defaults vary; agent ≥ some versions default `http/protobuf`) |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers for the exporter. | — |
| `OTEL_TRACES_SAMPLER` | Sampler name. | `parentbased_always_on` |
| `OTEL_TRACES_SAMPLER_ARG` | Sampler arg (e.g., ratio). | e.g. `0.1` for 10% |
| `OTEL_PROPAGATORS` | Propagators list. | `tracecontext,baggage` |
| `OTEL_BSP_SCHEDULE_DELAY` | BatchSpanProcessor flush delay (ms). | `5000` |
| `OTEL_BSP_MAX_QUEUE_SIZE` | Queue cap. | `2048` |
| `OTEL_BSP_MAX_EXPORT_BATCH_SIZE` | Batch size. | `512` |
| `OTEL_JAVAAGENT_ENABLED` | Toggle the agent. | `true` (when agent attached) |
| `OTEL_INSTRUMENTATION_<lib>_ENABLED` | Toggle a specific auto-instrumentation. | `true` |
| `OTEL_METRICS_EXPORTER` / `OTEL_LOGS_EXPORTER` | Metrics/logs exporters. | `otlp` / `otlp` |

> **OTLP ports.** `4317` = OTLP/gRPC, `4318` = OTLP/HTTP. Memorize these; they're the most common "why isn't anything showing up" cause (wrong port/protocol mismatch).

### 4.4 The OpenTelemetry Collector

The Collector is configured by a YAML pipeline of **receivers → processors → exporters**, grouped into **pipelines** under `service:`.

| Component class | Examples | Purpose |
|---|---|---|
| **Receivers** | `otlp`, `jaeger`, `zipkin`, `kafka` | Accept telemetry in various protocols. |
| **Processors** | `batch`, `memory_limiter`, `tail_sampling`, `attributes`, `resource`, `filter`, `transform` | Buffer, cap memory, sample, redact/enrich, drop. |
| **Connectors** | `spanmetrics`, `servicegraph` | Generate metrics from spans / build service graphs (bridge pipelines). |
| **Exporters** | `otlp`, `otlphttp`, `debug`/`logging`, `prometheus`, `loki`, vendor exporters | Send to backends. |
| **Extensions** | `health_check`, `pprof`, `zpages` | Ops/diagnostics. |

Minimal Collector config (OTLP in, batch, OTLP out to Tempo + debug):

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }
processors:
  memory_limiter:            # protect the collector from OOM
    check_interval: 1s
    limit_percentage: 80
    spike_limit_percentage: 25
  batch:                     # batch before export for efficiency
    timeout: 5s
    send_batch_size: 8192
exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls: { insecure: true }
  debug:
    verbosity: basic
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlp/tempo, debug]
```

**Two deployment patterns**, often combined:
- **Agent/sidecar mode**: a Collector per host/pod (DaemonSet or sidecar) — close to apps, does fast local batching/enrichment.
- **Gateway mode**: a centralized, horizontally scaled Collector cluster — does heavy work like **tail sampling** and routing. For tail sampling you must route all spans of a trace to the *same* gateway instance, typically by putting a **`loadbalancing` exporter** (which shards by trace ID) in front of the gateway tier.

### 4.5 Backend-specific CLI / config (quick reference)

| Backend | Ingest | Quick-start |
|---|---|---|
| **Jaeger** | OTLP (4317/4318), Zipkin, (legacy Jaeger proto) | `docker run jaegertracing/all-in-one` (UI on 16686). v2 is built on the Collector. |
| **Grafana Tempo** | OTLP, Jaeger, Zipkin | Object-storage backed; query via Grafana + TraceQL. `tempo -config.file=...`. |
| **Zipkin** | Zipkin JSON/proto, some OTLP via collector | `docker run openzipkin/zipkin` (UI/API on 9411). |

### 4.6 JVM instrumentation toolkit

| Tool | What it does |
|---|---|
| **OpenTelemetry Java agent** (`opentelemetry-javaagent.jar`) | Zero-code auto-instrumentation for 100+ libraries (Spring, JDBC, Hibernate, Kafka, gRPC, OkHttp, JDK HTTP, Servlet, Reactor, Logback/Log4j MDC, etc.). Attach via `-javaagent:`. |
| **`opentelemetry-spring-boot-starter`** | Spring-native autoconfig (no agent) — good when you can't use `-javaagent`. |
| **OTel BOM** (`opentelemetry-bom`) | Version-aligned dependency management for the API/SDK. |
| **`opentelemetry-instrumentation-annotations`** | `@WithSpan`/`@SpanAttribute` support without the agent (via the starter/aspect). |
| **Micrometer Tracing** (Spring Boot 3+) | Spring's tracing facade; bridges to OTel or Brave. Replaces Spring Cloud Sleuth. |
| **Brave / Zipkin Reporter** | The classic Zipkin instrumentation lib for the JVM (still used; B3 native). |

---

## 5. Code examples by use case

All examples target **OpenTelemetry Java**. Use the BOM to align versions:

```xml
<!-- pom.xml: align all OTel artifact versions via the BOM -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-bom</artifactId>
      <version>1.40.0</version> <!-- pin to a real, recent release -->
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-api</artifactId></dependency>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk</artifactId></dependency>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
  <!-- autoconfigure reads OTEL_* env vars and wires the SDK for you -->
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk-extension-autoconfigure</artifactId></dependency>
</dependencies>
```

### 5.1 Zero-code: attach the Java agent (the 80% case)

For most services, the fastest path is the agent — no code changes at all.

```bash
# Download the agent JAR once (pin a version in real deployments)
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

# Run your app with the agent attached and configured via env vars
OTEL_SERVICE_NAME=order-service \
OTEL_RESOURCE_ATTRIBUTES="deployment.environment=prod,service.version=2.3.1" \
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
OTEL_TRACES_SAMPLER=parentbased_traceidratio \
OTEL_TRACES_SAMPLER_ARG=0.1 \
java -javaagent:./opentelemetry-javaagent.jar -jar order-service.jar
```

This auto-creates SERVER spans for inbound HTTP, CLIENT spans for outbound HTTP/JDBC/gRPC, PRODUCER/CONSUMER spans for Kafka, injects/extracts W3C headers, and (if you use Logback/Log4j) injects `trace_id`/`span_id` into the **MDC** for log correlation.

> **MDC.** The *Mapped Diagnostic Context* is a per-thread key/value map provided by SLF4J/Logback/Log4j. The OTel agent populates it with `trace_id` and `span_id` so your existing log lines can print those IDs, enabling logs↔traces correlation (§6.5).

### 5.2 Programmatic SDK bootstrap (when you can't use the agent)

```java
// Build the SDK explicitly. Use this when running without -javaagent
// (e.g., a library, a CLI, or a tightly controlled bootstrap).
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import static io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME;
import static io.opentelemetry.semconv.ServiceAttributes.SERVICE_VERSION;

import java.time.Duration;

public final class Telemetry {

  public static OpenTelemetry init() {
    // Resource: who is producing telemetry. service.name is mandatory in practice.
    Resource resource = Resource.getDefault().merge(
        Resource.create(Attributes.of(
            SERVICE_NAME, "order-service",
            SERVICE_VERSION, "2.3.1")));

    // Exporter: OTLP/gRPC to the collector on 4317.
    OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
        .setEndpoint("http://otel-collector:4317")
        .setTimeout(Duration.ofSeconds(10))
        .build();

    // Batch in the background; never export on the request thread.
    BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
        .setScheduleDelay(Duration.ofSeconds(5))   // flush cadence
        .setMaxQueueSize(2048)                      // backpressure cap
        .setMaxExportBatchSize(512)
        .build();

    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .setResource(resource)
        // Keep parent's decision; if root, sample 10% of traces.
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(0.10)))
        .addSpanProcessor(processor)
        .build();

    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        // W3C trace context + baggage propagation across HTTP boundaries.
        .setPropagators(ContextPropagators.create(
            io.opentelemetry.context.propagation.TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(),
                W3CBaggagePropagator.getInstance())))
        .buildAndRegisterGlobal(); // registers GlobalOpenTelemetry

    // Flush on shutdown so we don't lose the last batch.
    Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
    return sdk;
  }
}
```

### 5.3 Manual span around business logic (the common manual pattern)

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;

public class PricingService {
  private static final Tracer TRACER =
      GlobalOpenTelemetry.getTracer("com.acme.order", "2.3.1");
  private static final AttributeKey<Long> ORDER_VALUE =
      AttributeKey.longKey("order.value_cents");

  public long computeTotal(Order order) {
    // Child of whatever span is current on this thread (e.g., the HTTP SERVER span).
    Span span = TRACER.spanBuilder("computeTotal")
        .setSpanKind(SpanKind.INTERNAL)
        .startSpan();
    try (Scope scope = span.makeCurrent()) {          // make it current for nested calls
      long total = order.lineItems().stream()
          .mapToLong(this::lineTotal).sum();
      span.setAttribute(ORDER_VALUE, total);          // domain attribute (low-risk, useful)
      span.setStatus(StatusCode.OK);
      return total;
    } catch (RuntimeException e) {
      span.recordException(e);                         // adds an "exception" event
      span.setStatus(StatusCode.ERROR, e.getMessage()); // mark span failed (do BOTH)
      throw e;
    } finally {
      span.end();                                     // ALWAYS end, even on error
    }
  }
}
```

Key idioms: `try (Scope ...)` to activate, `record + setStatus(ERROR)` together on failure, `end()` in `finally`. Forgetting `end()` leaks the span and breaks durations.

### 5.4 Propagating context across a thread pool

Auto-instrumentation can't follow your custom executor. Wrap it:

```java
import io.opentelemetry.context.Context;
import java.util.concurrent.*;

ExecutorService raw = Executors.newFixedThreadPool(8);
// Context.taskWrapping captures the submitting thread's Context and
// re-activates it inside the worker thread for every submitted task.
ExecutorService traced = Context.taskWrapping(raw);

void handle(Request req) {
  Span parent = TRACER.spanBuilder("handle").startSpan();
  try (Scope s = parent.makeCurrent()) {
    // Without taskWrapping, this child would attach to the WRONG parent
    // (or none) because it runs on a different thread.
    Future<?> f = traced.submit(() -> {
      Span child = TRACER.spanBuilder("async-work").startSpan();
      try (Scope cs = child.makeCurrent()) { doWork(); }
      finally { child.end(); }
    });
    f.get();
  } catch (Exception e) {
    parent.recordException(e);
  } finally {
    parent.end();
  }
}
```

If you can't wrap the executor, do it manually:

```java
Context captured = Context.current();                 // snapshot on submitting thread
raw.submit(() -> {
  try (Scope s = captured.makeCurrent()) {            // restore on worker thread
    // ... spans here correctly parent to the captured span
  }
});
```

### 5.5 Kafka producer/consumer propagation (manual, to show the mechanics)

Auto-instrumentation does this for you, but here is what it does under the hood — useful when you use a non-instrumented client.

```java
// PRODUCER: inject traceparent into Kafka record headers.
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.clients.producer.ProducerRecord;

TextMapSetter<ProducerRecord<?, ?>> SETTER =
    (rec, key, val) -> rec.headers().add(key, val.getBytes());

void publish(KafkaProducer<String,byte[]> producer, String topic, byte[] payload) {
  Span span = TRACER.spanBuilder("publish " + topic)
      .setSpanKind(SpanKind.PRODUCER)
      .setAttribute("messaging.system", "kafka")
      .setAttribute("messaging.destination.name", topic)
      .startSpan();
  try (Scope s = span.makeCurrent()) {
    ProducerRecord<String,byte[]> rec = new ProducerRecord<>(topic, payload);
    // Inject the current SpanContext as headers on the outgoing record.
    GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
        .inject(Context.current(), rec, SETTER);
    producer.send(rec);
  } finally {
    span.end();
  }
}
```

```java
// CONSUMER: extract traceparent and LINK to the producer (async => link, not parent).
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;

TextMapGetter<ConsumerRecord<?, ?>> GETTER = new TextMapGetter<>() {
  public Iterable<String> keys(ConsumerRecord<?, ?> rec) {
    return () -> java.util.stream.StreamSupport
        .stream(rec.headers().spliterator(), false)
        .map(h -> h.key()).iterator();
  }
  public String get(ConsumerRecord<?, ?> rec, String key) {
    var h = rec.headers().lastHeader(key);
    return h == null ? null : new String(h.value());
  }
};

void consume(ConsumerRecord<String,byte[]> rec) {
  Context extracted = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
      .extract(Context.current(), rec, GETTER);
  SpanContext producerCtx = Span.fromContext(extracted).getSpanContext();

  Span span = TRACER.spanBuilder("process " + rec.topic())
      .setSpanKind(SpanKind.CONSUMER)
      .addLink(producerCtx)                  // link, not parent, for batch-safe causality
      .startSpan();
  try (Scope s = span.makeCurrent()) {
    process(rec.value());
  } finally {
    span.end();
  }
}
```

### 5.6 Adding baggage and reading it downstream

```java
import io.opentelemetry.api.baggage.Baggage;

// UPSTREAM: attach tenant id to baggage; it rides every downstream HTTP call.
try (Scope b = Baggage.current().toBuilder()
        .put("tenant.id", tenantId)        // small, non-secret values only
        .build().makeCurrent()) {
  callDownstream();   // W3CBaggagePropagator injects `baggage: tenant.id=...`
}

// DOWNSTREAM: read it and (optionally) promote to a span attribute for querying.
String tenant = Baggage.current().getEntryValue("tenant.id");
Span.current().setAttribute("tenant.id", tenant);
```

### 5.7 Spring Boot 3 with Micrometer Tracing (no agent)

```xml
<dependency><groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId></dependency>
<dependency><groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId></dependency>
<dependency><groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
```

```properties
# application.properties
management.tracing.sampling.probability=0.1
management.otlp.tracing.endpoint=http://otel-collector:4318/v1/traces
spring.application.name=order-service   # becomes service.name
# Correlate logs: include trace/span ids in the log pattern
logging.pattern.level=%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]
```

```java
// Micrometer's @Observed (or @NewSpan) creates spans declaratively.
import io.micrometer.observation.annotation.Observed;

@Service
class InventoryService {
  @Observed(name = "inventory.reserve",
            contextualName = "reserve-stock")     // becomes the span name
  public void reserve(String sku, int qty) { /* ... */ }
}
```

### 5.8 Testing instrumentation with an in-memory exporter

```java
// Unit-test that your code creates the spans you expect — no backend needed.
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import static org.assertj.core.api.Assertions.assertThat;

InMemorySpanExporter exporter = InMemorySpanExporter.create();
SdkTracerProvider tp = SdkTracerProvider.builder()
    .addSpanProcessor(SimpleSpanProcessor.create(exporter)) // sync export for tests
    .build();
OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(tp).build();

// ... exercise code that uses `otel.getTracer(...)` ...

List<SpanData> spans = exporter.getFinishedSpanItems();
assertThat(spans).anySatisfy(s -> {
  assertThat(s.getName()).isEqualTo("computeTotal");
  assertThat(s.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  assertThat(s.getAttributes().get(AttributeKey.longKey("order.value_cents")))
      .isNotNull();
});
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance & overhead

- **Span creation is cheap but not free.** A recording span allocates objects (attribute maps, the span itself). At very high throughput (100k+ spans/s per process), this shows up in allocation rate and GC. Mitigate by sampling, by not over-instrumenting (don't span every trivial method), and by keeping attribute counts modest.
- **Export must be off the hot path.** Always use `BatchSpanProcessor` in prod (async). `SimpleSpanProcessor` exports synchronously per span and will tank latency — dev/test only.
- **Bounded queue = bounded blast radius.** The BatchSpanProcessor's `maxQueueSize` (default 2048) caps memory; when the backend is slow and the queue fills, spans are **dropped** (a `dropped spans` counter increments) rather than blocking your request threads. This is the right default: telemetry must never take down the app. Monitor the drop counter.
- **Sampling is the primary cost lever.** 100% sampling at scale is usually prohibitive; head sampling at 1–10% (with tail sampling to keep all errors/slow) is typical (§7).
- **Agent startup cost.** The Java agent adds startup time (class transformation) and a few % steady-state CPU; usually acceptable, but flag-disable instrumentations you don't need with `OTEL_INSTRUMENTATION_<lib>_ENABLED=false`.

### 6.2 Correctness & concurrency

- **The #1 bug: broken context across threads.** Symptom: child spans appear as separate root traces, or attach to the wrong parent. Cause: ThreadLocal context not propagated to worker/reactive threads. Fix: `Context.taskWrapping`, `context.wrap(...)`, or reactive context-propagation (§3.5, §5.4).
- **Always `end()` every span** — leaks and missing durations otherwise. Use try/finally.
- **Set ERROR status explicitly.** `recordException` records an event but does *not* mark the span failed; backends color/aggregate by status, so also call `setStatus(ERROR)`.
- **Match SpanKind to reality** so the backend computes correct latency breakdowns and dependency maps.
- **Don't mutate a span after `end()`** — it's a no-op and indicates a lifecycle bug.

### 6.3 Memory

- `SpanLimits` cap attributes/events/links per span (default 128 each) to bound a single span's footprint; raise carefully.
- Tail sampling in the Collector buffers whole traces in memory — size `num_traces` and the decision wait carefully (§7) and always run `memory_limiter` first in the pipeline.

### 6.4 Security & privacy

- **Spans can leak PII/secrets.** Attributes, events, and especially auto-instrumented DB statements or HTTP query strings may capture sensitive data. Treat the tracing backend as a data store subject to your privacy/compliance rules.
- **Redact at the source or in the Collector.** Use the OTel agent's sanitization options (e.g., DB statement sanitization is on by default — it parameterizes SQL), and the Collector's `attributes`/`transform`/`redaction` processors to drop/hash sensitive fields before they reach the backend.
- **Never put secrets in baggage** — it propagates to *every* downstream service and crosses trust boundaries; a downstream third-party service could read it.
- **Header injection across trust boundaries.** Don't blindly trust inbound `traceparent`/`baggage` from untrusted clients; at the edge you may want to start a fresh trace or strip incoming baggage. Limit baggage size to avoid header-size DoS.
- **Transport security.** Use TLS for OTLP to the collector/backend; set `OTEL_EXPORTER_OTLP_HEADERS` for auth tokens to managed backends.

### 6.5 Observability of the observability: correlating traces, logs, metrics

The payoff of the three pillars is **navigating between them**:
- **Traces ↔ Logs**: inject `trace_id`/`span_id` into every log line (via MDC; the agent does this automatically). Then from a slow span you can jump to exactly the logs of that request, and from a log line you can open the full trace. Use **structured logging** (JSON) so the IDs are queryable fields.
- **Traces ↔ Metrics (exemplars)**: an **exemplar** is a metric sample tagged with a `trace_id`, so a spike in a latency histogram links directly to a representative slow trace. Prometheus + OTel support exemplars.
- **Span metrics**: generate RED metrics (Rate/Errors/Duration) per service/operation *from the trace stream* (Collector `spanmetrics` connector, Tempo `metrics-generator`). This gives you metrics even from sampled traces if you generate them before sampling.
- **Service graphs**: derived from CLIENT↔SERVER span pairs (Collector `servicegraph` connector / Tempo). Real, traffic-derived dependency maps.

### 6.6 Cost

Tracing cost has three components: (1) **app overhead** (CPU/alloc/GC from span creation + export), (2) **network/egress** (especially to a SaaS backend — bytes per span × span volume), (3) **storage + query** at the backend. Levers:
- **Sample** (head + tail) — the dominant lever.
- **Drop noisy spans** (e.g., health-check endpoints, super-chatty internal spans) in the Collector `filter` processor.
- **Trim attributes** to what you query on.
- **Choose storage wisely**: Tempo's object-storage model (keep *all* sampled traces cheaply, index minimally, query by trace ID/TraceQL) is far cheaper per GB than heavily indexed stores like Elasticsearch-backed Jaeger or per-span SaaS pricing.
- **Generate span metrics before sampling** so you keep accurate aggregate RED even at low trace sampling.

### 6.7 Testing

- Use `InMemorySpanExporter` to assert spans/attributes in unit tests (§5.8).
- Integration-test propagation across services (assert a single trace ID end-to-end).
- In CI, run with `LoggingSpanExporter` (or Collector `debug` exporter) to eyeball output.
- Contract-test that you set the **semantic-convention** attribute names backends expect.

### 6.8 Production hardening checklist

- `BatchSpanProcessor` (never Simple) in prod; tune queue/batch.
- Run a **Collector** between apps and backend (decouples, centralizes redaction/sampling, smooths bursts). Run `memory_limiter` first.
- Set `service.name`, `service.version`, `deployment.environment` on every service.
- W3C propagation by default; add B3/jaeger propagators only where a legacy mesh needs them.
- Tail sampling: keep 100% of errors + slow traces, low % of the rest.
- Redact PII; TLS + auth on exporters.
- Monitor: exporter failures, dropped-span counters, collector queue depth & refused spans, agent overhead.
- Graceful shutdown flush (`tracerProvider.close()` / shutdown hook) so the last batch isn't lost.

### 6.9 Anti-patterns

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| High-cardinality span names (`GET /orders/12345`) | Explodes operations, breaks aggregation | Template routes; put IDs in attributes |
| `SimpleSpanProcessor` in prod | Synchronous export adds latency | Use `BatchSpanProcessor` |
| Over-instrumentation (span per trivial method) | Allocation/GC + noisy waterfalls | Span at meaningful boundaries |
| Forgetting `end()` | Span leaks, no duration | try/finally |
| `recordException` without `setStatus(ERROR)` | Backend doesn't flag the failure | Do both |
| Secrets/PII in attributes/baggage | Data leak | Redact; never baggage secrets |
| 100% sampling at scale | Cost blowout | Head + tail sampling |
| No Collector (apps → SaaS directly) | No central policy, tight coupling, burst fragility | Insert a Collector |
| Mixing propagation formats silently | Broken traces at boundaries | Standardize on W3C; configure interop explicitly |

---

## 7. Advanced topics & deep internals

### 7.1 Sampling strategies in depth

**Head-based (decide at trace start):**
- **AlwaysOn / AlwaysOff**: keep all / keep none. AlwaysOn only viable at low volume or in dev.
- **Probabilistic (`TraceIdRatioBased`)**: keep fraction `p`. Deterministic on trace ID → **consistent** across services. Simple and cheap. Weakness: blind to whether the trace was interesting (you might drop the one error).
- **Rate-limiting**: cap at N traces/sec regardless of volume — protects the backend from traffic spikes, but the kept fraction varies with load.
- **ParentBased**: respect the upstream decision; only the root chooses. This is what makes head sampling consistent end-to-end.

**Tail-based (decide after trace completes, in the Collector):**
- Buffer all spans of a trace, then apply policies. Canonical policy set: keep **all** errored traces, keep **all** traces over a latency threshold, keep a small **probabilistic** slice of the rest, with an overall **rate limit**. Optional attribute/route-based policies (e.g., always keep `tenant.id=vip`).
- **Cost/complexity**: the Collector must (a) receive *all* spans for a trace (so you can't naively load-balance by round-robin — you need the `loadbalancing` exporter to shard by trace ID to a consistent gateway instance), and (b) hold traces in memory for a **decision wait** (must exceed the longest expected trace duration) — too short and you decide on incomplete traces; too long and memory balloons.

Example `tail_sampling` processor:

```yaml
processors:
  tail_sampling:
    decision_wait: 10s          # must exceed slowest trace; longer = more memory
    num_traces: 100000          # in-flight traces buffered
    expected_new_traces_per_sec: 2000
    policies:
      - name: keep-errors
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: keep-slow
        type: latency
        latency: { threshold_ms: 1000 }
      - name: sample-rest
        type: probabilistic
        probabilistic: { sampling_percentage: 5 }
```

**Consistent probability sampling / `tracestate` (`th`)**: OTel is standardizing a way to record the *effective sampling probability* in `tracestate` (the `th`/threshold field) so backends can **reweight** sampled traces to recover unbiased aggregate counts. This solves the classic problem: "if I sampled 1%, how do I count total requests?" — multiply by the adjusted count. Flag this as evolving/spec-version-dependent.

### 7.2 The cost of getting parent context wrong (deep)

Because the parent is resolved from the *current Context* at `startSpan()`, any place the ThreadLocal context is missing produces subtly wrong trees. Reactive frameworks are the worst offenders: a single request bounces across many scheduler threads. Solutions:
- **Reactor**: enable automatic context propagation (`Hooks.enableAutomaticContextPropagation()` + Micrometer context-propagation) so the OTel Context is captured into Reactor's `Context` and restored on each operator's thread.
- **Virtual threads (Project Loom)**: ThreadLocals work per virtual thread, so context generally behaves, but be careful with thread-pool-style executors of platform threads. Avoid `ThreadLocal` pinning concerns; OTel context is fine on virtual threads.
- **`StructuredTaskScope`**: capture and re-activate context in each subtask.

### 7.3 Sampling vs. always-record + tail (the modern best practice)

A powerful pattern: instrument at **100% in-process** (so span metrics and tail decisions have full data) but only **export** what tail sampling keeps. Achieved with `RECORD_ONLY` at the SDK plus tail sampling at the Collector, or by generating `spanmetrics` *before* the tail-sampling processor so RED metrics are accurate even though most traces are dropped.

### 7.4 Trace context in non-HTTP carriers

- **gRPC**: propagation rides gRPC **metadata** (HTTP/2 headers). Auto-instrumented.
- **Messaging**: headers on Kafka records, AMQP message headers, SQS message attributes, Pub/Sub attributes. Async ⇒ links (§5.5).
- **Databases**: there's **SQLCommenter** — appending the trace context as a SQL comment (`/*traceparent='...'*/`) so the DB (and tools like `pg_stat_statements`) can correlate the query back to the originating trace. Useful for "which trace caused this slow query."
- **Browser/RUM**: Real User Monitoring agents start the trace in the browser and propagate `traceparent` to the backend (CORS must allow the header), giving true end-to-end (browser → backend) traces. Beware exposing trace IDs to untrusted clients.

### 7.5 ID generation & collision

Trace IDs are 128-bit random; collision probability is negligible at realistic volumes. Span IDs are 64-bit; collisions within a single trace are what matter and are astronomically unlikely. Some ecosystems (AWS X-Ray) use a structured trace ID embedding a timestamp — use the `AwsXrayIdGenerator` and X-Ray propagator for interop.

### 7.6 Span limits, truncation, and the "giant trace" problem

A trace with tens of thousands of spans (e.g., an N+1 query loop firing 50k queries) is expensive to store and unusable to view. Defenses: cap fan-out instrumentation, span limits, and Collector-side filtering of pathological traces. Some backends truncate or warn on oversized traces.

### 7.7 Clock skew & duration anomalies (deep)

Negative or zero durations, children "outside" parents, and overlapping siblings that shouldn't overlap usually mean clock skew or a propagation bug. Backends apply causal clock-skew correction, but if you see wildly off timings, check NTP sync and whether you're accidentally creating spans with mismatched clocks (e.g., end timestamp from a different host).

### 7.8 OTLP wire details

OTLP is protobuf over gRPC (4317) or HTTP/protobuf (4318, path `/v1/traces`). It supports **partial success** responses (the backend can accept some spans and report a count of rejected ones) and **retries with backoff** on transient failures. Compression (gzip) is supported and recommended for egress-cost reduction.

### 7.9 Migration & interop notes (version/vendor-specific — flagged)

- **OpenTracing/OpenCensus → OTel**: bridges exist (`opentelemetry-opentracing-shim`) so you can migrate incrementally.
- **Spring Cloud Sleuth → Micrometer Tracing**: Sleuth is end-of-life; Spring Boot 3 uses Micrometer Tracing (bridges to OTel or Brave). Sleuth defaulted to **B3**; Micrometer/OTel default to **W3C** — a mixed fleet during migration can break traces unless you configure both propagators everywhere.
- **Jaeger v1 → v2**: Jaeger v2 is built on the OTel Collector; the standalone Jaeger client SDKs are deprecated in favor of OTel SDKs. The Jaeger gRPC exporter in OTel is deprecated — **export OTLP** to Jaeger instead.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Backends compared

| Dimension | **Jaeger** | **Grafana Tempo** | **Zipkin** |
|---|---|---|---|
| Origin | Uber (CNCF) | Grafana Labs (CNCF) | Twitter (oldest) |
| Storage | Cassandra / Elasticsearch / OpenSearch / Badger / (memory) | **Object storage** (S3/GCS/local) — cheap, scalable | In-memory / MySQL / Cassandra / ES |
| Indexing | Full span indexing (costly at scale) | Minimal index; query by trace ID + **TraceQL** | Lightweight |
| Query model | Tag/operation/duration search in own UI | Trace-ID lookup + TraceQL; tight Grafana integration | Basic tag/duration search |
| Cost profile | Higher (indexed store) | **Lowest per GB** (keep everything cheaply) | Low at small scale |
| Native metrics | SPM (via collector) | metrics-generator (span metrics + service graph) | Limited |
| OTLP ingest | Yes (and is OTel-Collector-based in v2) | Yes | Via collector |
| Best when | You want rich built-in trace search UI | You want cheap, high-volume retention + Grafana stack | Small/simple setups, legacy B3 systems |

### 8.2 Head vs tail sampling

| | Head sampling | Tail sampling |
|---|---|---|
| Decision point | Trace start (root) | After trace completes (collector) |
| Sees if trace is interesting? | No | **Yes** (errors/latency) |
| Cost in apps | Lowest (drop early) | Apps still emit (more egress) unless combined cleverly |
| Infra cost | Minimal | High (buffer whole traces in memory; trace-ID-affine routing) |
| Consistency | Easy (propagate flag) | Needs all spans at one collector |
| Use when | High volume, cost-sensitive, baseline visibility | You must keep all errors/slow traces |
| Best practice | **Combine**: head-sample a baseline; tail-sample to retain errors + tails | |

### 8.3 Agent vs manual vs starter

| Approach | Pros | Cons | Use when |
|---|---|---|---|
| **Java agent (auto)** | Zero code, broad coverage, fast adoption | Less control, some startup cost, can't easily add domain spans without also doing manual | Default for most services |
| **Manual SDK** | Full control, domain spans | Verbose, you own propagation | Libraries, special bootstraps, fine-grained needs |
| **Spring Boot starter / Micrometer** | Spring-native, no `-javaagent`, declarative `@Observed` | Spring-only, narrower coverage than agent | Spring shops that can't attach an agent |
| **Best practice** | Agent for breadth **+** manual/`@WithSpan` for domain depth | | Most production systems |

### 8.4 Propagation format choice

| Format | Use when | Notes |
|---|---|---|
| **W3C tracecontext** | Default for everything new | Standard; interoperable across vendors/languages |
| **B3 (single/multi)** | Interop with Zipkin/Brave, Istio/Envoy meshes, Sleuth-era systems | Configure alongside W3C during migration |
| **Jaeger (`uber-trace-id`)** | Legacy Jaeger-client systems | Migrating to W3C |
| **AWS X-Ray** | On AWS using X-Ray | Use X-Ray propagator + ID generator |

> Rule: **Standardize on W3C; add other propagators only at the specific boundaries that need them, and add them on *both* sides.**

---

## 9. Failure modes & debugging

### 9.1 "No traces are showing up"

The single most common situation. Walk the pipeline end to end:
1. **Is the SDK installed?** Without an SDK, the API is a no-op. (Agent attached? `-javaagent` present? `OTEL_JAVAAGENT_ENABLED=true`?)
2. **`service.name` set?** Else traces show as `unknown_service:java`.
3. **Exporter endpoint/protocol/port correct?** Classic: app exports OTLP/gRPC to `:4318` (the HTTP port) or vice versa. gRPC = 4317, HTTP = 4318 (`/v1/traces`). Check `OTEL_EXPORTER_OTLP_PROTOCOL`.
4. **Sampler dropping everything?** `OTEL_TRACES_SAMPLER=always_off` or a ratio of 0 → nothing exported. Temporarily set `always_on`.
5. **Collector receiving?** Add the `debug` exporter to the Collector and watch logs; check the Collector's `health_check` and `zpages` extensions and its internal metrics (`otelcol_receiver_accepted_spans`, `otelcol_exporter_sent_spans`, `otelcol_exporter_send_failed_spans`, `otelcol_processor_refused_spans`).
6. **Backend reachable from collector?** TLS/auth/endpoint to Tempo/Jaeger correct?
7. **Spans not ended / app exited before flush?** Missing `end()` or no shutdown flush → last batch lost.

### 9.2 "Traces are broken/fragmented" (spans show as separate traces)

- **Context lost across threads** (most common) — async/reactive/thread-pool work without context propagation. Fix per §3.5/§5.4.
- **Propagation format mismatch** — caller sends B3, callee only reads W3C (or vice versa). Configure both, or standardize.
- **A hop strips headers** — a proxy/LB/gateway dropping `traceparent`/`tracestate`/`baggage`. Allow-list those headers; for browser, enable CORS for them.
- **Sampling inconsistency** — a service not honoring the parent's sampled flag (not using `ParentBased`), so you get half a trace.

### 9.3 "High latency / GC pressure after enabling tracing"

- `SimpleSpanProcessor` in prod → switch to `BatchSpanProcessor`.
- Over-instrumentation / 100% sampling at high volume → reduce sampling, prune spans/attributes.
- Slow/unreachable backend with a too-large queue → exporter timeouts back up; tune `maxQueueSize`/timeouts, insert a Collector to absorb bursts. Watch the dropped-spans counter.

### 9.4 "Dropped spans / incomplete traces under load"

- Batch queue full (backend can't keep up) → spans dropped by design. Monitor drop counter; scale the Collector/backend, increase batch size, enable compression, or sample harder.
- Collector `memory_limiter` refusing data → you're at the memory cap; add capacity or reduce intake.
- Tail-sampling `decision_wait` too short → decisions made on incomplete traces; raise it (at memory cost).

### 9.5 "Wrong/negative durations, children outside parents"

- Clock skew across hosts (check NTP) — backends correct heuristically but extreme skew breaks the view.
- Span ended on a different host/clock than it started, or a propagation bug duplicating IDs.

### 9.6 Diagnostic tooling cheat-list

- **Collector**: `debug`/`logging` exporter, `zpages` (`/debug/tracez`), `pprof`, `health_check`, internal metrics (`otelcol_*`).
- **App**: `LoggingSpanExporter` to print spans; enable OTel SDK self-diagnostics/internal logging; verify env vars are actually picked up.
- **On the wire**: capture HTTP headers (`curl -v`, a proxy, or tcpdump) to confirm `traceparent` is present and well-formed.
- **Backend**: query by a known trace ID (printed in your logs via MDC) to verify ingestion.

### 9.7 Real-world incident patterns (illustrative)

- **Cascading timeout / retry storm**: a slow downstream causes upstream retries; traces reveal duplicated CLIENT spans and a fan-out explosion that aggregate metrics hid. Tracing localizes the originating slow service.
- **Hidden synchronous dependency**: a "fast" endpoint occasionally blocks on a rarely-hit synchronous call to a slow service; only the p99 traces show the extra hop.
- **N+1 queries**: the waterfall shows hundreds of sequential identical DB CLIENT spans under one request — instantly diagnostic, invisible to averages.
- **Sampling blind spot**: an incident's traces were head-sampled away at 1%; the fix was tail sampling to always keep errors. A classic lesson: head-only sampling can drop exactly the traces you need.

---

## 10. Interview drill

**Q1. What is a distributed trace, and how is it different from a log or a metric?**
*Model answer:* A trace is the causal, per-request graph of timed spans across services, sharing a trace ID. Logs are discrete events; metrics are aggregates over time. Metrics tell you *that* something's wrong, traces tell you *where* in the request path, logs tell you *why*. The three are complementary and most valuable when correlated.
- *Follow-up: Why can't metrics answer "which hop was slow"?* Metrics are pre-aggregated and lose per-request identity and causal structure; they can't reconstruct a single request's path. → *And how do you bridge metric→trace?* Exemplars (metric samples tagged with a trace ID). → *trace→log?* Inject trace_id/span_id into logs via MDC.

**Q2. Walk me through context propagation. What's in `traceparent`?**
*Model answer:* On an outbound call the current SpanContext is **injected** into headers; the callee **extracts** it and makes its server span a child. `traceparent` = `version-traceid-parentid-flags`, e.g. `00-<32hex>-<16hex>-01`; flag bit 0 = sampled. `tracestate` carries vendor data; `baggage` carries app key/values.
- *Follow-up: B3 vs W3C?* B3 (Zipkin) uses `X-B3-*` or single `b3`; W3C is the standard. → *What breaks if caller uses B3 and callee only W3C?* Trace fragments at that boundary — configure both. → *Where does the sampled flag come from and why propagate it?* From the root's sampler; propagating it keeps the whole trace's keep/drop decision consistent.

**Q3. How does head sampling stay consistent across services without coordination?**
*Model answer:* `TraceIdRatioBased` makes the keep/drop decision a deterministic function of the trace ID, so every service computes the same answer; and `ParentBased` makes downstream services simply obey the propagated sampled flag. Net: the whole trace is kept or dropped together.
- *Follow-up: weakness of head sampling?* It decides before knowing if the trace errored or was slow. → *fix?* Tail sampling. → *how do you recover accurate totals from a 1% sample?* Reweight by the inverse sampling probability (consistent-probability sampling records the adjusted count / `tracestate` threshold).

**Q4. (Senior signal) You're at 50k req/s across 60 services. Design the sampling + collection.**
*Model answer:* Run a Collector tier (agent per node for local batching + a gateway tier for tail sampling). Head-sample a low baseline (e.g., 1–5%) for cost; in the gateway, **tail-sample to keep 100% of errors and traces over a latency threshold** plus a small probabilistic slice. Route to the gateway with a `loadbalancing` exporter sharding by trace ID so each trace lands whole on one instance. Generate span metrics (RED) *before* sampling so aggregate dashboards stay accurate. Use object-storage backend (Tempo) for cheap retention. Justify each choice by cost vs. completeness.
- *Follow-up: why trace-ID-affine routing for tail sampling?* The collector must see *all* spans of a trace to decide. → *risk if `decision_wait` too short?* Decide on incomplete traces / drop late spans. → *too long?* Memory blowup; size `num_traces` + `memory_limiter`.

**Q5. (Senior signal) Auto vs manual instrumentation — when and why?**
*Model answer:* Auto (Java agent / bytecode) for breadth and zero-effort coverage of frameworks; manual for domain-specific spans and attributes the agent can't know (business operations, domain IDs). Production reality: use both — agent for the plumbing, `@WithSpan`/manual for the business logic. Tradeoff is control vs. effort and a little startup/CPU cost for the agent.
- *Follow-up: how does the agent work?* Java agent + bytecode instrumentation rewrites library classes at load time. → *cost?* Startup transform time + small steady CPU; disable unused instrumentations. → *can't use an agent (PaaS)?* Spring Boot starter / Micrometer Tracing.

**Q6. Why are spans exported asynchronously, and what happens when the backend is down?**
*Model answer:* The `BatchSpanProcessor` enqueues finished spans and a background thread exports batches, keeping export off the request hot path. If the backend is slow/down, the bounded queue fills and spans are **dropped** (counter increments) rather than blocking the app — telemetry must never take down the service.
- *Follow-up: defaults?* queue 2048, batch 512, flush 5s, export timeout 30s. → *how to reduce drops?* Insert a Collector to absorb bursts, increase batch/compression, scale backend, or sample harder. → *SimpleSpanProcessor?* Synchronous per-span export — dev/test only.

**Q7. How do you correlate a slow trace with the exact logs and metrics for that request?**
*Model answer:* Inject `trace_id`/`span_id` into the MDC so every log line carries them (agent does this); use structured JSON logs so the IDs are queryable. Use exemplars to jump from a metric spike to a trace. Generate span/RED metrics from traces for the metric side. Then from a span you pivot to its logs and from a histogram bucket to a representative trace.
- *Follow-up: what is MDC?* Per-thread key/value map in SLF4J/Logback used to enrich logs. → *exemplar?* A metric sample annotated with a trace ID. → *cardinality concern?* Don't put trace IDs as metric *labels* (unbounded) — use exemplars instead.

**Q8. (Senior signal) When would you NOT adopt distributed tracing, or adopt it minimally?**
*Model answer:* A single service with no downstream calls gains little over local profiling + structured logs; the cost (overhead, infra, storage) may not pay off. At extreme volume, cost forces aggressive sampling, so you weigh completeness vs. spend. But even small systems benefit from cheap span instrumentation early because the second service makes it invaluable. The senior point is matching telemetry investment to the questions you actually need answered and the cost you can bear.
- *Follow-up: cheaper alternatives?* RED metrics + structured logs with request IDs. → *what do you lose?* Causal cross-service structure and per-request waterfalls. → *cost levers if you do adopt?* Sampling, attribute trimming, dropping noisy spans, cheap object-storage backend.

**Q9. Why do traces break across async/reactive boundaries, and how do you fix it?**
*Model answer:* "Current span" lives in a ThreadLocal-backed Context, so handing work to another thread (thread pool, reactive scheduler, CompletableFuture) loses it; child spans then orphan. Fix by capturing and re-activating the Context (`Context.taskWrapping`, `context.wrap`, captured `makeCurrent()`), or enabling reactive context propagation (Reactor + Micrometer context-propagation).
- *Follow-up: virtual threads?* Generally fine since ThreadLocals are per-virtual-thread. → *symptom in the UI?* Spans appear as separate root traces. → *how to detect in tests?* Assert a single trace ID across the spans with `InMemorySpanExporter`.

**Q10. What's a link, and when do you use it instead of a parent?**
*Model answer:* A link references other spans not in the direct parent/child line. Use it for batch consumption (one consumer span processing N messages from N traces — can't have N parents) and fan-in joins. The consumer span links to the producer span(s) so causality is preserved without forcing a single parent.
- *Follow-up: messaging spankind?* PRODUCER/CONSUMER. → *why not parent for batch?* A span has one parent but can have many links. → *how does the producer pass context?* Injects traceparent into message headers; consumer extracts and links.

**Q11. (Senior signal) Tempo vs Jaeger vs Zipkin — pick one for high-volume, cost-sensitive retention and justify.**
*Model answer:* Tempo: object-storage-backed, minimal indexing, query by trace ID + TraceQL, integrates with Grafana, lowest cost per GB so you can retain far more sampled traces cheaply, with metrics-generator for RED/service graphs. Jaeger gives richer built-in search but indexed storage (Cassandra/ES) costs more at scale; Zipkin is simplest/legacy-B3-friendly but less feature-rich. For high volume + cost sensitivity, Tempo wins; the tradeoff is you lean on trace-ID lookup + TraceQL + correlated metrics rather than heavy ad-hoc tag search.
- *Follow-up: how do you find a trace in Tempo without a full index?* From logs (trace_id via MDC) or exemplars; TraceQL for structural queries. → *what do span metrics buy you?* Aggregate RED even at low sampling. → *all built on OTLP?* Yes — all three ingest OTLP; Jaeger v2 is OTel-Collector-based.

**Q12. Security: how do you keep PII out of traces?**
*Model answer:* Treat the backend as a sensitive datastore. Redact at source (agent SQL sanitization on by default; avoid high-risk attributes) and in the Collector (`attributes`/`transform`/redaction processors) before export. Never put secrets in baggage (propagates everywhere, crosses trust boundaries). At the edge, don't blindly trust inbound trace/baggage headers; cap baggage size. Use TLS + auth on exporters.
- *Follow-up: why is baggage especially risky?* It travels to *every* downstream service, including third parties. → *what's auto-redacted?* DB statement parameterization (sanitization). → *header DoS?* Limit baggage size at the edge.

---

## 11. Glossary

- **Agent (Java agent)**: a JAR attached via `-javaagent:` that rewrites class bytecode at load time to inject instrumentation without source changes.
- **Annotation (Zipkin)**: a timestamped event within a span (≈ OTel event).
- **Attribute (tag)**: a typed key/value on a span carrying dimensional metadata.
- **Auto-instrumentation**: spans created automatically for known libraries/frameworks (usually via an agent).
- **Baggage**: a W3C standard for propagating arbitrary app key/values across the whole trace via the `baggage` header.
- **B3**: Zipkin's propagation format (`X-B3-*` multi-header or single `b3` header).
- **Backend (tracing system)**: where spans are stored, indexed, and visualized (Jaeger/Tempo/Zipkin/SaaS).
- **BatchSpanProcessor (BSP)**: SDK component that queues finished spans and exports them in async batches.
- **Bytecode instrumentation**: rewriting compiled bytecode (via the JVM instrument API) to inject behavior.
- **Cardinality**: number of distinct values a field can take; span names should be low-cardinality.
- **Carrier**: the medium holding propagation data (HTTP headers, gRPC metadata, Kafka headers, etc.).
- **Clock skew**: clock differences across machines that distort raw span timestamps.
- **CNCF**: Cloud Native Computing Foundation, the open-source foundation hosting OTel, Kubernetes, Prometheus, Jaeger, etc.
- **Collector (OTel Collector)**: standalone receive→process→export pipeline for telemetry; central place for batching, sampling, redaction, routing.
- **Connector**: a Collector component bridging pipelines, e.g. generating metrics from spans (`spanmetrics`) or service graphs.
- **Context**: immutable container holding the current span + baggage; activated per thread (ThreadLocal by default).
- **Context propagation**: serializing the SpanContext at an outbound boundary (inject) and deserializing at the inbound boundary (extract).
- **Decision wait**: time the tail-sampling processor buffers a trace before deciding.
- **Event (span event)**: a timestamped record inside a span (e.g., an exception).
- **Exemplar**: a metric sample tagged with a trace ID, linking metrics to traces.
- **Exporter (SpanExporter)**: SDK component that serializes and sends span batches (OTLP/Jaeger/Zipkin).
- **Fan-out**: one request triggering many downstream calls.
- **Head-based sampling**: keep/drop decided at trace start and propagated.
- **Inject / extract**: write / read propagation data into / from a carrier.
- **Instrumentation**: code that creates spans (auto or manual).
- **Instrumentation scope**: the name/version identifying the library/module that produced spans (the `Tracer` name).
- **Jaeger**: CNCF tracing backend (Uber origin); v2 is OTel-Collector-based.
- **Link**: a reference from a span to other causally related spans not in its parent line (batch/fan-in).
- **Load-balancing exporter**: Collector exporter that shards spans by trace ID to keep each trace whole on one gateway instance (needed for tail sampling).
- **MDC (Mapped Diagnostic Context)**: per-thread key/value map in SLF4J/Logback/Log4j; used to put trace/span IDs in logs.
- **memory_limiter**: Collector processor that caps memory to prevent OOM; run first in the pipeline.
- **Metric**: an aggregate numeric measurement over time (rate/error/duration histograms).
- **Microservices**: an app split into many independently deployed networked services.
- **N+1 queries**: a pattern issuing one query per item in a loop; appears as many sequential DB spans.
- **NTP**: Network Time Protocol; synchronizes machine clocks.
- **Observability**: the ability to ask arbitrary questions about internal state from external outputs.
- **OpenCensus / OpenTracing**: the two predecessor projects merged into OpenTelemetry.
- **OpenTelemetry (OTel)**: the vendor-neutral CNCF standard/API/SDK/Collector for telemetry.
- **OTLP**: OpenTelemetry Protocol; protobuf over gRPC (4317) or HTTP (4318) for shipping telemetry.
- **ParentBased sampler**: obey the parent's sampled flag; only the root consults a delegate sampler.
- **PII**: Personally Identifiable Information; must be kept out of (or redacted from) traces.
- **Probabilistic sampling (TraceIdRatioBased)**: keep a fraction `p` of traces, deterministic on trace ID.
- **RED metrics**: Rate, Errors, Duration per service/operation.
- **Resource**: attributes describing the telemetry producer (`service.name`, host, pod).
- **Root span**: the first span of a trace; has no parent.
- **RUM (Real User Monitoring)**: browser-side telemetry that can start a trace in the user's browser.
- **Sampling**: keeping a subset of traces to control cost.
- **Semantic conventions**: OTel's standardized attribute names (e.g., `http.request.method`).
- **Service graph / dependency map**: traffic-derived map of which services call which, built from CLIENT/SERVER span pairs.
- **SimpleSpanProcessor**: synchronous per-span exporter; dev/test only.
- **Span**: a single named, timed unit of work; the building block of a trace.
- **SpanContext**: the propagatable identity (trace ID, span ID, flags, tracestate).
- **SpanKind**: SERVER/CLIENT/PRODUCER/CONSUMER/INTERNAL — the span's role at a boundary.
- **SpanProcessor**: SDK hook on span start/end routing to exporters.
- **SQLCommenter**: appends trace context as a SQL comment to correlate queries with traces.
- **Status (span status)**: UNSET/OK/ERROR.
- **Tail-based sampling**: decide keep/drop after a trace completes (in the Collector), enabling "keep all errors/slow."
- **Tempo (Grafana Tempo)**: object-storage-backed, low-cost CNCF tracing backend; TraceQL query language.
- **ThreadLocal**: a per-thread variable; backs the default OTel context storage.
- **Trace**: the set of all spans sharing a trace ID, assembled into a tree.
- **Trace flags**: 8-bit field in SpanContext; bit 0 is the sampled flag.
- **Trace ID**: 128-bit identifier shared by all spans of a trace.
- **traceparent / tracestate**: the W3C Trace Context headers (core IDs / vendor data).
- **TraceQL**: Tempo's query language for traces.
- **Virtual threads (Loom)**: lightweight JVM threads; OTel context generally works per virtual thread.
- **W3C Trace Context**: the standardized propagation format (`traceparent`/`tracestate`).
- **Waterfall (Gantt) view**: the timeline visualization of a trace's spans.
- **Zipkin**: the original open-source tracing system (Twitter); B3 propagation.
- **zpages**: Collector diagnostic web pages (e.g., `/debug/tracez`).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Model:** unique **trace ID** per request → each work unit is a **span** (name, start/end, kind, status, attributes, events, parent) → **propagate** SpanContext across boundaries (inject/extract) → **sample** → **export** async → backend reassembles by trace ID into a **waterfall**.

**IDs:** trace ID = 128-bit (32 hex); span ID = 64-bit (16 hex).

**Header:** `traceparent: 00-<traceid32>-<spanid16>-<flags2>` (flags `01` = sampled). Plus `tracestate`, `baggage`.

**Propagation formats:** W3C (default) · B3 (Zipkin/Envoy) · jaeger · X-Ray. Standardize on W3C; add others only where needed, on both sides.

**SpanKind:** SERVER (inbound) · CLIENT (outbound) · PRODUCER/CONSUMER (async) · INTERNAL.

**OTel layers:** API (no-op without SDK) · SDK (sampler/processor/exporter/resource) · Collector (receive→process→export) · Backend.

**OTLP ports:** gRPC **4317**, HTTP **4318** (`/v1/traces`).

**BSP defaults:** queue **2048**, batch **512**, flush **5s**, export timeout **30s**. Queue full ⇒ spans dropped. Prod = BatchSpanProcessor, never Simple.

**Span limits:** 128 attributes/events/links by default.

**Sampling:** head (decide at start, propagate flag, consistent via trace-ID hash) vs tail (decide in collector after completion → keep all errors/slow). Best practice: combine. Tail needs trace-ID-affine routing (`loadbalancing` exporter) + `decision_wait` > slowest trace.

**Defaults to remember:** sampler `parentbased_always_on`; propagators `tracecontext,baggage`; `service.name` defaults to `unknown_service` (set it!).

**Correlate:** trace_id/span_id in MDC → logs; exemplars → metrics; spanmetrics/servicegraph connectors → RED + dependency maps.

**Backends:** Jaeger (rich search, indexed, pricier) · Tempo (object storage, cheap, TraceQL) · Zipkin (simple, B3).

**Top bugs:** wrong OTLP port/protocol · `service.name` unset · context lost across threads (broken traces) · propagation-format mismatch · SimpleSpanProcessor in prod · secrets in baggage · 100% sampling at scale.

**Decision rules:**
- Adopt tracing when >a few services / latency-tail debugging / fan-out.
- Agent for breadth + manual for domain depth.
- Always-on Collector in prod (batch, redact, tail-sample, smooth bursts).
- Keep 100% of errors + slow traces via tail sampling; baseline head-sample the rest.
- Low-cardinality span names; IDs go in attributes.
- `recordException` **and** `setStatus(ERROR)`; always `end()`.

### 12.2 Self-test (no answers — for active recall)

1. A teammate sees each service's spans as separate single-span traces in the UI. List the three most likely causes and the exact fix for each.
2. You run 80k req/s and must guarantee every errored request's full trace is retained while keeping cost low. Design the end-to-end sampling + collection topology and justify why tail sampling forces trace-ID-affine routing and a tuned `decision_wait`.
3. Explain precisely how `TraceIdRatioBased` + `ParentBased` keep a head-sampling decision consistent across 12 services with no central coordinator, and what the sampled flag's role is.
4. Your `/orders/{id}` endpoint is p50-fast but p99-slow. Describe how you'd use the trace waterfall, span attributes, and span/RED metrics to localize the cause, and name two patterns (with their visual signature) you'd suspect.
5. Write (from memory) the `traceparent` format with field widths, say which field becomes the child's parent, and explain what flips the last field from `00` to `01`.
6. Why is putting secrets in baggage worse than putting them in a span attribute, and what two redaction points exist in an OTel pipeline?
7. Distinguish a span **event**, a span **link**, and a parent/child **edge**, giving one concrete scenario where you must use a link rather than a parent.
8. Your app's latency rose after enabling tracing. Enumerate the likely causes in priority order and the corresponding fix for each.

# The Three Pillars of Observability

> An engineering-handbook chapter for senior backend developers (Java/JVM focus) who want to master observability from first principles to deep internals — enough to design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

**Observability** is the property of a system that lets you understand its *internal state* purely from the *outputs* it emits — without shipping new code to ask a new question. The term is borrowed from **control theory** (Rudolf Kálmán, 1960), where a system is "observable" if you can reconstruct its internal state from its external outputs over time. In software, the "outputs" are the telemetry signals your services emit: **metrics, logs, and traces** — the *three pillars*.

The problem it solves: modern backends are **distributed, concurrent, and partially failing all the time**. A single user request fans out across dozens of services, queues, caches, and databases. When something is slow or wrong, the cause is rarely on the box you're staring at. Traditional **monitoring** — pre-built dashboards and alerts for failure modes you *anticipated* — breaks down because the interesting failures are the ones you *didn't* anticipate. Observability is the discipline and tooling that lets you ask **arbitrary new questions** of a running system after the fact.

**When you reach for it:**
- You have more than one service (or even one service with real traffic) and "ssh in and tail the log" no longer scales.
- You need to answer questions like "*why is the p99 latency for enterprise customers in eu-west spiking only for requests that hit the new pricing path?*" — a question nobody pre-built a dashboard for.
- You're doing **incident response**, **capacity planning**, **SLO management**, or **performance tuning**.

**The one-paragraph mental model.** Think of your system as a black box emitting three kinds of signal that trade off against each other along the axes of *cost*, *cardinality* (how many distinct values a field can take), and *granularity*. **Metrics** are cheap, aggregated numbers over time — great for "*is something wrong, and how much?*" but they throw away the per-event detail. **Logs** are discrete, timestamped records of events — great for "*what exactly happened to this one thing?*" but expensive at volume and awkward to aggregate. **Traces** stitch together the causal path of a single request as it crosses service boundaries — great for "*where in the call graph did the time/error go?*" You need all three because each answers a question the others answer badly, and the real power comes from **correlating** them — jumping from a spiking metric, to the trace of an exemplar slow request, to the logs emitted during that exact trace.

---

## 2. Foundations from first principles

### 2.1 Telemetry, signals, and instrumentation

- **Telemetry**: data a system emits about itself for the purpose of remote measurement (Greek *tele* = "remote", *metron* = "measure"). All three pillars are forms of telemetry.
- **Signal**: a category of telemetry. The canonical signals are metrics, logs, traces; emerging ones are **events** and **continuous profiles**.
- **Instrumentation**: the code (yours, a library's, or an agent's) that *produces* telemetry. Two flavors:
  - **Manual / explicit instrumentation**: you write `counter.increment()` or `span = tracer.startSpan(...)` by hand.
  - **Automatic instrumentation**: an agent or framework hooks into known libraries (HTTP clients, JDBC, Kafka, servlet filters) and emits telemetry without you writing it. On the JVM this is typically a **Java agent** — a `.jar` loaded via the `-javaagent:` JVM flag that uses **bytecode instrumentation** (rewriting class bytecode at load time) to inject telemetry calls.

### 2.2 Monitoring vs observability — known-unknowns vs unknown-unknowns

This distinction is the philosophical core of the whole topic.

- A **known-unknown** is a failure mode you *anticipated*: "the disk might fill up", "the database might get slow", "the queue might back up". You can pre-build a dashboard and an alert for each. This is the domain of classic **monitoring**.
- An **unknown-unknown** is a failure you did *not* anticipate and could not have built a dashboard for in advance: "requests fail only for users whose locale is `tr-TR` because of a Turkish dotless-i uppercase bug in a string comparison". You discover these by *exploring* the data with new questions.

| | **Monitoring** | **Observability** |
|---|---|---|
| Question style | Predefined: "is metric X above threshold?" | Ad hoc: "why is *this specific slice* behaving differently?" |
| Failure class | Known-unknowns | Unknown-unknowns |
| Data shape | Pre-aggregated, low-cardinality | High-cardinality, high-dimensional, drill-downable |
| Primary artifact | Dashboards & alerts | Queryable wide events / traces |
| Mental motion | Watching | Investigating |
| Analogy | Smoke detector | Forensics lab |

The key technical consequence: **monitoring tolerates aggressive aggregation; observability demands you keep enough dimensionality to slice along axes you didn't predict.** That dimensionality is **cardinality**, and it's the central cost driver of the whole field.

> **Cardinality** = the number of distinct values a field (or combination of fields) can take. `http.method` has cardinality ~7 (GET, POST, …). `user_id` has cardinality = number of users (millions). `customer_id × endpoint × region × status_code` is a **combinatorial explosion** — the product of each field's cardinality. High cardinality is what lets you answer narrow questions; it's also what makes telemetry expensive.

### 2.3 The three pillars defined

#### Metrics
A **metric** is a numeric measurement aggregated over a time interval, identified by a name and a set of key-value **labels** (a.k.a. **tags** or **dimensions**). Example: `http_requests_total{method="GET", status="200", route="/checkout"} = 18342`.

- Stored as a **time series**: a sequence of `(timestamp, value)` points for one unique `(name, label-set)` combination.
- A unique label-set defines a distinct time series. **Series count = product of label cardinalities.** This is the single most important cost fact about metrics.
- Cheap because they're *pre-aggregated*: instead of storing 1M individual request records, you store one number that ticks up. Storage cost is roughly *proportional to series count × scrape frequency × retention*, **independent of request volume**.

The four canonical metric **instrument types** (terminology mostly from Prometheus / OpenTelemetry):

1. **Counter** — monotonically increasing value that only goes up (or resets to 0 on restart). E.g. total requests, total bytes. You almost always look at its *rate* (`rate(...)`), not its raw value.
2. **Gauge** — a value that can go up or down, a snapshot of "right now". E.g. queue depth, memory in use, temperature.
3. **Histogram** — samples observations into configurable **buckets** (e.g. request durations into ≤10ms, ≤50ms, ≤100ms, …). Lets you compute **quantiles** (p50, p95, p99) approximately, server-side, and is **aggregatable across instances** (you can sum bucket counts).
4. **Summary** — like a histogram but computes quantiles **client-side** at observation time. Cheaper to query but **not aggregatable** across instances (you can't average percentiles) and you must pick quantiles in advance.

> **Quantile / percentile**: the value below which a given fraction of observations fall. p99 latency = the latency that 99% of requests are faster than. Percentiles matter because *averages lie*: a mean of 50ms can hide a p99 of 5s that's ruining your worst users' experience.

#### Logs
A **log** is a discrete, timestamped record of a single event. Three sub-flavors:

1. **Unstructured logs** — free text: `2026-06-24 10:01:33 ERROR could not connect to db host=10.0.0.5`. Human-readable, machine-hostile (you must regex-parse them).
2. **Structured logs** — key-value/JSON: `{"ts":"...","level":"ERROR","msg":"db connect failed","host":"10.0.0.5","attempt":3}`. Machine-queryable; the modern default.
3. **Wide structured events** — a single very wide structured record (dozens to hundreds of fields) emitted **once per unit of work** (e.g. once per HTTP request) capturing everything known about it. This is the "**canonical log line**" / "**observability event**" pattern (popularized by Honeycomb and Stripe). Distinct from emitting 30 thin log lines per request.

Logs are **high-fidelity but expensive**: cost scales with *event volume × bytes per event × retention*. A chatty service at 50k req/s emitting 10 lines/request = 500k log lines/s. Ingest, index, and storage costs dominate observability bills here.

> **Log level**: a severity tag (TRACE < DEBUG < INFO < WARN < ERROR < FATAL). Used to filter volume — you log everything at DEBUG in dev, INFO+ in prod.

#### Traces
A **trace** represents the end-to-end journey of a single request through a distributed system. It is a tree (technically a DAG) of **spans**.

- **Span**: a single named, timed operation — e.g. "HTTP GET /checkout", "SQL SELECT", "Kafka publish". Each span has a start time, duration, a **span ID**, a **parent span ID** (forming the tree), the shared **trace ID**, a status (OK/ERROR), and arbitrary **attributes** (tags) and **events** (timestamped logs within the span).
- **Trace ID**: a unique ID (128-bit in OpenTelemetry/W3C) shared by every span in one request's journey. **This is the join key that correlates all three pillars.**
- **Context propagation**: passing the trace ID and span ID across process boundaries — typically via HTTP headers (the W3C `traceparent` header), message metadata, etc. — so the downstream service knows it's part of the same trace.

> **W3C Trace Context**: a web standard (`traceparent` / `tracestate` HTTP headers) for propagating trace identity across services regardless of vendor. `traceparent: 00-{trace-id}-{span-id}-{flags}`. The `flags` byte carries the **sampled** bit.

Traces answer "**where did the latency/error go in the call graph?**" — which the other two pillars can't, because metrics aggregate away the per-request path and logs (without correlation) don't reconstruct the causal tree.

### 2.4 Why you need all three

Each pillar is strong exactly where the others are weak:

| Question | Best pillar | Why the others fail |
|---|---|---|
| "Is the error rate up, and by how much?" | **Metric** | Logs/traces require expensive aggregation to count |
| "What was the exact stack trace for *this* failure?" | **Log** | Metrics threw away the detail; trace shows shape not full payload |
| "Which downstream call ate the 800ms?" | **Trace** | Metrics don't know call order; logs don't reconstruct the tree |
| "How many users in region X hit the slow path?" | **Wide event / trace attributes** | Pre-aggregated metrics can't slice an axis you didn't pre-define |

The pillars form a **drill-down funnel**: a *metric* alert tells you *something* is wrong (cheap, always-on), a *trace* tells you *where*, and *logs* tell you *exactly what*. Correlation IDs glue the funnel together.

### 2.5 The RED, USE, and Four Golden Signals frameworks

To know *what* to measure, the field has three canonical recipes:

- **RED** (Tom Wilkie) — per-service request-level health: **R**ate (requests/s), **E**rrors (failed requests/s), **D**uration (latency distribution). Use for request-driven services.
- **USE** (Brendan Gregg) — per-resource health: **U**tilization, **S**aturation, **E**rrors. Use for resources (CPU, disk, NIC, thread pools, connection pools).
- **Four Golden Signals** (Google SRE book): **Latency, Traffic, Errors, Saturation**. A superset/blend of RED+USE for services.

> **SLO / SLI / SLA**: An **SLI** (Service Level Indicator) is a measured number (e.g. "% of requests served < 300ms"). An **SLO** (Objective) is the target for that SLI (e.g. "99.9% over 28 days"). An **SLA** (Agreement) is the contractual promise to a customer, usually looser than the SLO, with penalties. The gap between 100% and your SLO is your **error budget** — the amount of unreliability you're allowed to spend.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle of each pillar from "code calls an API" to "you query it in a UI", plus the unified **OpenTelemetry** pipeline that increasingly underlies all three.

### 3.1 The OpenTelemetry (OTel) data path — the common backbone

**OpenTelemetry (OTel)** is a CNCF (Cloud Native Computing Foundation) project and the de-facto vendor-neutral standard for generating, collecting, and exporting telemetry. It merged the older **OpenTracing** (tracing API) and **OpenCensus** (metrics + tracing) projects. It defines:

1. **API** — the interfaces your code calls (`Tracer`, `Meter`, `Logger`). Stable; no-ops if no SDK is installed.
2. **SDK** — the concrete implementation: sampling, batching, resource detection, exporters.
3. **OTLP** (OpenTelemetry Protocol) — the wire format (protobuf over gRPC or HTTP) for shipping all three signals.
4. **Collector** — a standalone proxy/pipeline (receive → process → export) that decouples your apps from backends.

**End-to-end flow (control + data):**

```
[Your code]
   │ calls API (startSpan / counter.add / logger.info)
   ▼
[OTel SDK]
   ├─ Span/Metric/LogRecord created in-process
   ├─ Sampler decides keep/drop (traces)            ← control decision
   ├─ Processor batches records (BatchSpanProcessor) ← buffering
   ├─ Resource attributes attached (service.name, host, k8s pod…)
   ▼ OTLP export (gRPC :4317 / HTTP :4318)
[OTel Collector]  (optional but recommended)
   ├─ Receivers (otlp, prometheus, filelog, jaeger…)
   ├─ Processors (batch, memory_limiter, attributes, tail_sampling, filter)
   ├─ Exporters (prometheusremotewrite, otlp, loki, kafka, s3…)
   ▼
[Backends]
   ├─ Metrics  → Prometheus / Mimir / Cortex / Datadog
   ├─ Logs     → Loki / Elasticsearch / OpenSearch / Datadog
   └─ Traces   → Tempo / Jaeger / Zipkin / Datadog
   ▼
[Query / UI]  Grafana, Jaeger UI, Kibana, vendor UIs
```

> **CNCF**: Cloud Native Computing Foundation — the open-source foundation (under the Linux Foundation) that stewards Kubernetes, Prometheus, OpenTelemetry, Envoy, etc.
> **gRPC**: a high-performance RPC framework using HTTP/2 and protobuf; OTLP's default transport on port 4317.
> **Resource**: in OTel, the immutable set of attributes describing the *entity* producing telemetry — `service.name`, `service.version`, `host.name`, `k8s.pod.name`. Attached once and shared by all signals from that process, which is part of what makes correlation possible.

### 3.2 Metrics internals: collection, the time series, and querying

We'll use **Prometheus** as the canonical example since it defines the dominant data model.

**Lifecycle of a metric (Prometheus pull model):**

1. **Instrument**: your code holds a `Counter`/`Gauge`/`Histogram` object in memory. Each `.inc()`/`.observe()` mutates an in-memory number (a few atomic operations — extremely cheap; on the order of nanoseconds).
2. **Expose**: a library (e.g. Micrometer + the Prometheus registry) renders the current values as plain text at an HTTP endpoint, conventionally `/actuator/prometheus` (Spring Boot) or `/metrics`.
3. **Scrape**: the Prometheus server **pulls** that endpoint on a fixed interval (default **15s**, set by `scrape_interval`). It performs **service discovery** (static config, Kubernetes API, Consul, EC2…) to find targets.
4. **Ingest & store**: each scraped sample becomes a point appended to a **time series** identified by its label-set. Prometheus's storage engine (the **TSDB**, time-series database) writes to a **WAL** (write-ahead log) first, then compacts into immutable 2-hour **blocks** on disk, using delta-of-delta timestamp encoding and XOR float compression (the **Gorilla** compression scheme from Facebook's paper) — typically ~1–2 bytes per sample.
5. **Query**: you run **PromQL** (Prometheus Query Language). `rate(http_requests_total[5m])` reads the raw counter series, computes per-second rate over a 5-minute window, and handles counter resets automatically.

> **Pull vs push**: Prometheus *pulls* (scrapes) targets; this gives it a built-in liveness check (a target that can't be scraped is "down") and central control of frequency. Push systems (StatsD, OTLP push, the Prometheus **Pushgateway** for batch jobs) suit ephemeral/short-lived jobs that die before a scrape.
> **WAL (write-ahead log)**: an append-only log written *before* the main data structure is updated, so an in-progress write survives a crash and can be replayed on restart. Used by Prometheus TSDB, and by virtually every database.
> **TSDB**: time-series database — a database optimized for append-mostly `(timestamp, value)` data with heavy compression and time-range queries.

**The histogram internals (why p99 is approximate):** A classic Prometheus histogram pre-defines bucket boundaries (`le` = "less than or equal"). Each `.observe(x)` increments every bucket whose boundary ≥ x. `histogram_quantile(0.99, ...)` then *interpolates within the bucket* where the 99th percentile falls — so accuracy depends entirely on bucket placement. If your p99 falls in a bucket spanning 1s–10s, your "p99" could be off by seconds. **Native/exponential histograms** (newer in Prometheus and OTel) fix this with automatically-scaled exponential buckets, giving consistent relative error (~1%) across the whole range at far lower storage cost.

**Cardinality blowup mechanics:** Prometheus holds an **inverted index** mapping each label value to the series containing it, plus the head block of each active series in memory (~few KB/series). Add a `user_id` label with 1M values to a metric that already has `method × status × route` (say 100 combos) and you don't get 100 + 1M series — you can approach 100 × 1M = **100M series**. Each consumes memory; the index grows; queries slow to a crawl. **This is the #1 way teams take down their own monitoring system.**

### 3.3 Logs internals: from `logger.info` to a searchable index

**Lifecycle of a log line (JVM + a backend like Loki or Elasticsearch):**

1. **Emit**: code calls `log.info("order placed", kv("orderId", id))`. A **logging facade** (**SLF4J** — Simple Logging Facade for Java) routes to an implementation (**Logback** or **Log4j2**).
2. **Format**: an encoder/layout renders the event. For observability you use a **structured (JSON) encoder** (e.g. `logstash-logback-encoder`) so fields stay machine-readable.
3. **Append**: an **appender** writes the line — to stdout (the **12-factor** convention in containers), a file, or directly to a network socket. Appenders can be **async** (Log4j2's `AsyncAppender` uses an **LMAX Disruptor** ring buffer to decouple the app thread from I/O).
4. **Collect**: a **log shipper/agent** (Promtail, Fluent Bit, Fluentd, Vector, the OTel Collector's `filelog` receiver) tails the file/stdout, parses, enriches with metadata (pod, namespace, trace ID), and forwards.
5. **Index & store**:
   - **Elasticsearch/OpenSearch**: builds a full-text **inverted index** over every field — fast arbitrary search, but storage- and CPU-heavy (the index can exceed the raw data size).
   - **Loki**: indexes **only a small set of labels** (like Prometheus) and stores the raw log body as compressed chunks — far cheaper, but full-text search within a label stream is a brute-force scan (`grep`-like via **LogQL**).
6. **Query**: KQL/Lucene (Elastic), LogQL (Loki), SPL (Splunk).

> **Inverted index**: a map from *term → list of documents containing it*, the core data structure of search engines. Powerful for arbitrary text queries; expensive to build and store. The "index everything vs index labels only" choice is the central log-storage cost tradeoff.
> **12-factor app**: a set of conventions for cloud-native apps; one says "treat logs as event streams" — write to stdout and let the platform handle routing, rather than managing log files in the app.

**The structured-vs-unstructured cost:** unstructured logs force a **parse step** (grok/regex) at ingest, which is fragile (a format change breaks the parser) and CPU-expensive. Structured logs skip parsing and let you query fields directly. Always emit structured logs in production.

### 3.4 Traces internals: span lifecycle, propagation, and sampling

**Lifecycle of a trace:**

1. **Root span starts**: the first service to receive a request (with no incoming `traceparent`) generates a new 128-bit **trace ID** and a 64-bit root **span ID**. The **sampler** runs *here* (head-based) and sets the sampled bit.
2. **Work happens; child spans created**: each significant operation (DB call, RPC, cache lookup) opens a child span carrying the same trace ID and a new span ID with the parent's ID recorded.
3. **Context propagation across the wire**: when the service calls another service, it **injects** the current context into outbound headers (`traceparent: 00-<traceid>-<spanid>-01`). The downstream service **extracts** it, so its root span becomes a *child* of the caller's span — stitching the trace across process boundaries.
4. **Span ends**: duration is computed; status and attributes finalized; the span is handed to a **SpanProcessor**.
5. **Batch & export**: `BatchSpanProcessor` buffers finished spans (default queue 2048, batch 512, flush every 5s) and exports them via OTLP. **Spans are exported independently and reassembled into a trace at the backend by trace ID** — there is no single object that holds a whole trace in your process.
6. **Backend assembly**: Tempo/Jaeger group spans by trace ID and reconstruct the tree for display as a **waterfall/Gantt** view.

> **Span context**: the minimal immutable trinfo that propagates — trace ID, span ID, trace flags (sampled bit), trace state. It's what travels on the wire; the heavy span data (attributes, events) stays local and is exported separately.

**Sampling — the key control for trace cost.** Storing every span at high traffic is ruinously expensive, so most systems sample. Two fundamental strategies:

- **Head-based sampling**: the keep/drop decision is made at the *root*, *before* you know how the request turned out. The sampled bit propagates so the whole trace is consistently kept or dropped. Cheap and simple, but you might **drop the rare error trace** you most wanted. Common samplers:
  - `AlwaysOn` / `AlwaysOff`
  - `TraceIdRatioBased(p)` — keep a fraction *p* (e.g. 0.01 = 1%); deterministic on trace ID so all services agree.
  - `ParentBased(...)` — respect the upstream decision; only the root decides.
- **Tail-based sampling**: buffer *all* spans of a trace until it completes, *then* decide based on the outcome (keep all errors, all slow traces, a baseline of normal ones). Catches the interesting traces but requires holding complete traces in memory in the **Collector** (the `tail_sampling` processor) — needs all spans of a trace routed to the same collector instance, which complicates horizontal scaling (you need a load-balancing exporter keyed by trace ID).

> **Exemplar**: a single concrete example linked from an aggregate. A Prometheus histogram bucket can attach an **exemplar** carrying a *trace ID* — so when you see a spike in the p99 latency *metric*, you can click straight to a *trace* of an actual slow request that landed in that bucket. This is the canonical metric→trace correlation bridge.

### 3.5 State machine summary

A span's state machine: `Created → Recording → (set attributes/events/status) → Ended → Queued in processor → Exported (or Dropped if unsampled/queue-full)`.

A metric counter's "state": a single monotonic accumulator mutated in place; on process restart it **resets to zero**, which is why all consumers must use `rate()`/`increase()` that handle resets, never raw deltas.

---

## 4. The complete toolkit

### 4.1 OpenTelemetry API/SDK (Java) — core types

| Type | Pillar | Purpose | Key methods / params | Default behavior |
|---|---|---|---|---|
| `Tracer` | Traces | Create spans | `spanBuilder(name)`, `.setSpanKind()`, `.startSpan()` | Obtained from `OpenTelemetry.getTracer(name)` |
| `Span` | Traces | A timed operation | `setAttribute(k,v)`, `addEvent()`, `recordException()`, `setStatus()`, `end()` | Must call `end()`; use try-with-`Scope` |
| `Meter` | Metrics | Create instruments | `counterBuilder`, `gaugeBuilder`, `histogramBuilder` | From `getMeter(name)` |
| `LongCounter` | Metrics | Monotonic count | `add(value, Attributes)` | Monotonic |
| `ObservableGauge` | Metrics | Async snapshot | callback returns current value | Polled at export |
| `DoubleHistogram` | Metrics | Distribution | `record(value, Attributes)` | Default explicit buckets |
| `Logger` (Logs API) | Logs | Emit log records | `logRecordBuilder().setBody().setSeverity().emit()` | Often via SLF4J bridge |
| `Context` | All | Carry trace context | `Context.current()`, `makeCurrent()` | Thread-local based |
| `Sampler` | Traces | Keep/drop decision | `traceIdRatioBased(p)`, `parentBased(root)` | `ParentBased(AlwaysOn)` |
| `BatchSpanProcessor` | Traces | Buffer + export | `maxQueueSize`(2048), `maxExportBatchSize`(512), `scheduleDelay`(5s) | Async batching |
| `Resource` | All | Identify producer | `service.name`, `service.version`, host/k8s attrs | Auto-detected + env |

### 4.2 Micrometer (the JVM metrics facade; underpins Spring Boot Actuator)

| Concept | Purpose | Notes |
|---|---|---|
| `MeterRegistry` | Backend abstraction (Prometheus, OTLP, Datadog, …) | "SLF4J for metrics" |
| `Counter` | Monotonic count | `Counter.builder("name").tag(...).register(reg)` |
| `Timer` | Latency + count | Records count, total time, max; backs histograms/percentiles |
| `Gauge` | Snapshot of a value | Holds a weak reference to the source object |
| `DistributionSummary` | Non-time distributions (e.g. payload size) | |
| `@Timed` / `@Counted` | Annotation-based instrumentation | AOP-driven |
| Common tags | Tags applied to all meters | `application`, `region` — set once |

### 4.3 PromQL essentials

| Expression | Meaning |
|---|---|
| `rate(http_requests_total[5m])` | Per-second avg rate over 5m (handles resets) |
| `sum by (route) (rate(...))` | Aggregate rate grouped by route |
| `histogram_quantile(0.99, sum by (le)(rate(http_request_duration_seconds_bucket[5m])))` | Approx p99 latency |
| `increase(errors_total[1h])` | Total increase over 1h |
| `avg_over_time(gauge[10m])` | Time-average of a gauge |
| `topk(5, ...)` | Top 5 series |

### 4.4 Collector pipeline building blocks

| Component | Type | Purpose |
|---|---|---|
| `otlp` | receiver | Accept OTLP gRPC(:4317)/HTTP(:4318) |
| `prometheus` | receiver | Scrape Prom endpoints |
| `filelog` | receiver | Tail log files |
| `batch` | processor | Batch for efficient export |
| `memory_limiter` | processor | Backpressure / OOM guard |
| `attributes` / `resource` | processor | Add/redact/hash fields (PII) |
| `tail_sampling` | processor | Outcome-aware trace sampling |
| `filter` | processor | Drop noisy telemetry |
| `prometheusremotewrite` | exporter | Push metrics to Prom/Mimir |
| `otlp` / `loki` / `loadbalancing` | exporter | Forward traces/logs; LB by trace ID |

### 4.5 CLI / operational tools

| Tool | Use |
|---|---|
| `promtool check rules` / `promtool query instant` | Validate/query Prometheus |
| `logcli query '{app="x"}'` | Query Loki from CLI |
| `jq` | Slice JSON logs locally |
| `async-profiler`, JFR | Continuous/on-demand JVM profiling |
| `kubectl logs` / `stern` | Tail pod logs |

---

## 5. Code examples by use case

### 5.1 Spring Boot + Micrometer: RED metrics with a histogram

```java
// build.gradle: io.micrometer:micrometer-registry-prometheus
@RestController
class CheckoutController {
    private final MeterRegistry registry;
    private final Timer checkoutTimer;

    CheckoutController(MeterRegistry registry) {
        this.registry = registry;
        // Timer records Rate, Errors (via tags), Duration — the RED triad.
        this.checkoutTimer = Timer.builder("checkout.duration")
            .description("Checkout latency")
            .tag("service", "checkout")
            // publishPercentileHistogram emits aggregatable histogram buckets,
            // so you can compute p99 server-side AND across instances.
            .publishPercentileHistogram()
            .register(registry);
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody Cart cart) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            process(cart);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            outcome = "error";                       // becomes the 'E' in RED
            throw e;
        } finally {
            // Low-cardinality tags only! outcome ∈ {success,error}.
            // NEVER tag with cart.userId() — that explodes series count.
            sample.stop(checkoutTimer.withTags("outcome", outcome));
        }
    }
}
```
Why it matters: `outcome` has cardinality 2; `userId` would have cardinality = #users and detonate your TSDB. Per-user questions belong in logs/traces, not metric labels.

### 5.2 Structured logging with trace correlation (Logback + MDC)

```java
// logstash-logback-encoder renders JSON; MDC injects trace_id/span_id.
private static final Logger log = LoggerFactory.getLogger(OrderService.class);

public void placeOrder(Order order) {
    // OTel's logback appender or a MDC bridge auto-populates trace_id/span_id
    // from the active span, so this log line is joinable to its trace.
    MDC.put("order_id", order.id());
    try {
        log.info("order placed",                       // structured event
            kv("amount", order.total()),               // net.logstash kv()
            kv("currency", order.currency()),
            kv("item_count", order.items().size()));
    } finally {
        MDC.remove("order_id");
    }
}
```
Resulting line: `{"ts":"...","level":"INFO","msg":"order placed","trace_id":"4bf92f...","span_id":"00f0...","order_id":"o-123","amount":42.0,"currency":"USD","item_count":3}`. The `trace_id` is the join key: from this log you can open the full trace; from the trace you can pull all logs sharing the ID.

### 5.3 The "wide event / canonical log line" pattern

```java
// Emit ONE wide structured event per request capturing everything known.
// This is the observability-event approach: high-cardinality, drill-downable.
public void onRequestComplete(RequestCtx ctx) {
    log.info("request",
        kv("trace_id", ctx.traceId()),
        kv("http_route", ctx.route()),
        kv("http_status", ctx.status()),
        kv("duration_ms", ctx.durationMs()),
        kv("user_id", ctx.userId()),          // high cardinality is FINE here
        kv("customer_tier", ctx.tier()),
        kv("region", ctx.region()),
        kv("db_calls", ctx.dbCalls()),
        kv("cache_hit", ctx.cacheHit()),
        kv("feature_flags", ctx.activeFlags()),
        kv("build_sha", ctx.buildSha()));
}
```
One event, dozens of dimensions. You can later ask "p99 duration for `customer_tier=enterprise` AND `region=eu-west` AND `cache_hit=false`" — a question no pre-built metric anticipated. This is the practical realization of *observability* (high-cardinality, ad-hoc slicing) vs *monitoring*.

### 5.4 Manual tracing with attributes, errors, and a child span

```java
private final Tracer tracer = openTelemetry.getTracer("payments");

public PaymentResult charge(Payment p) {
    Span span = tracer.spanBuilder("charge")
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute("payment.provider", p.provider())   // low-card attr
        .setAttribute("payment.amount", p.amount())
        .startSpan();
    try (Scope scope = span.makeCurrent()) {              // sets thread-local ctx
        // Any downstream OTel-instrumented client (HTTP, JDBC) now nests
        // its spans under THIS span automatically via Context.current().
        return providerClient.charge(p);
    } catch (ProviderException e) {
        span.recordException(e);                           // attaches stack trace
        span.setStatus(StatusCode.ERROR, e.getMessage());  // marks span failed
        throw e;
    } finally {
        span.end();                                        // MUST end (computes duration)
    }
}
```

### 5.5 Tail-based sampling in the Collector (keep all errors + slow traces)

```yaml
# otel-collector-config.yaml
processors:
  tail_sampling:
    decision_wait: 10s          # buffer this long to assemble full traces
    num_traces: 100000          # in-memory trace cap (memory budget!)
    policies:
      - name: keep-errors
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: keep-slow
        type: latency
        latency: { threshold_ms: 1000 }   # keep traces slower than 1s
      - name: baseline-1pct
        type: probabilistic
        probabilistic: { sampling_percentage: 1 }  # 1% of the boring traces
exporters:
  loadbalancing:               # REQUIRED so all spans of a trace hit one collector
    protocol: { otlp: {} }
    resolver: { dns: { hostname: collector-headless } }
    routing_key: traceID
```
This keeps 100% of failures and slow requests (the ones you care about) plus a 1% baseline — slashing cost while preserving signal. The `loadbalancing` exporter is mandatory because tail sampling needs every span of a trace co-located.

### 5.6 PromQL alert with an exemplar-driven SLO burn rate

```yaml
# Multi-window multi-burn-rate alert (Google SRE style) on a 99.9% SLO.
groups:
- name: slo
  rules:
  - alert: HighErrorBudgetBurn
    # error ratio over 1h is 14.4x the budgeted rate => budget gone in ~2 days
    expr: |
      (
        sum(rate(http_requests_total{status=~"5.."}[1h]))
        / sum(rate(http_requests_total[1h]))
      ) > (14.4 * 0.001)
    for: 2m
    labels: { severity: page }
    annotations:
      summary: "Burning 99.9% SLO error budget fast"
```
Histogram buckets exporting **exemplars** let you click from the firing alert's latency panel to a concrete slow trace — the metric→trace bridge in action.

---

## 6. Implementation concerns & best practices

**Performance.**
- Metric mutations are ~nanoseconds (atomic adds); the cost is in *series count*, not call frequency.
- Tracing overhead is dominated by export, not span creation; use `BatchSpanProcessor`, never `SimpleSpanProcessor` in prod (it exports synchronously per span and will throttle your request path).
- Logging is often the *biggest* per-request CPU cost — use **async appenders** and avoid string concatenation; guard expensive `debug` with `if (log.isDebugEnabled())` or parameterized logging (`log.debug("x={}", x)`).
- Auto-instrumentation Java agents add a one-time startup hit (bytecode rewriting) and modest steady-state overhead (typically low single-digit %), but watch memory from per-span attribute maps.

**Correctness / concurrency.**
- **Context propagation across threads** is the #1 tracing bug. Thread-local `Context` does *not* follow work onto a thread pool, `CompletableFuture`, or reactive scheduler. Wrap executors with `Context.taskWrapping(executor)` or use OTel's reactive/`@WithSpan` integrations; otherwise spans detach and traces fragment.
- Always `span.end()` in `finally`; a leaked unended span never exports and looks like a hung operation.
- Counters reset on restart — consumers must use `rate()`/`increase()`.

**Memory.**
- Prometheus head block + index is RAM-resident; cardinality is the OOM risk. Cap with relabeling/`metric_relabel_configs` to drop dangerous labels at scrape time.
- Tail sampling buffers whole traces in the Collector — size `num_traces` and `memory_limiter` deliberately.

**Security & privacy.**
- **PII** leaks via logs and span attributes constantly (emails, tokens, card numbers, full request bodies). Redact/hash at the Collector (`attributes`/`transform` processors) or at emit. Never log secrets/auth headers.
- Treat the **trace ID** as non-sensitive but the *attributes* as potentially sensitive.
- Lock down `/metrics` and `/actuator` endpoints — they leak topology, versions, and sometimes secrets.

**Cost.**
- The bill, in rough descending order, is usually: **logs > traces > metrics**, but high-cardinality metrics can flip that. Control with: drop noisy logs at the Collector, sample traces, cap metric cardinality, tier storage (hot/warm/cold), shorten retention, and use cheaper backends (Loki vs Elastic, native histograms vs many fixed buckets).

**Observability of your observability.**
- Monitor the Collector (dropped spans, queue length, export failures), Prometheus `prometheus_tsdb_head_series` (cardinality), and ingest error rates. A blind monitoring system is worse than none.

**Testability.**
- Use OTel's `InMemorySpanExporter` / Micrometer `SimpleMeterRegistry` in unit tests to assert spans/metrics were emitted with the right attributes.

**Anti-patterns to avoid.**
1. High-cardinality labels on metrics (`user_id`, `request_id`, raw URLs with IDs, full SQL).
2. Logging instead of metrics for things you count (you'll pay 1000x and can't alert cheaply).
3. `SimpleSpanProcessor` / synchronous exporters in prod.
4. Unstructured logs requiring fragile regex parsing.
5. Head-sampling at a low rate that drops all your error traces.
6. No trace ID in logs (kills correlation — the whole point).
7. Alerting on causes (CPU) instead of symptoms (latency/error-rate SLO).
8. One dashboard per metric, none tied to user-facing SLOs.

---

## 7. Advanced topics & deep internals

**High-cardinality observability (the unknown-unknowns engine).** The defining capability of true observability tooling (Honeycomb, modern column-stores) is storing **wide events with unbounded cardinality** and querying any dimension in seconds. They achieve this with **columnar storage** (each field stored as its own column, so a query touching 3 of 200 fields reads only those columns) plus aggressive compression and distributed scan-on-read — sidestepping the per-series index cost that kills Prometheus at high cardinality. The tradeoff: you compute aggregates at query time (scan-heavy) rather than storing pre-aggregated series.

**Native/exponential histograms.** Replace hand-tuned `le` buckets with base-2^(2^-scale) exponential buckets that auto-cover the full range at bounded relative error. Far fewer stored series, accurate quantiles everywhere. Now GA-ish in Prometheus and the OTel default direction. Version-specific: requires recent Prometheus (2.40+) and remote-write v2 / compatible backends.

**Exemplars deep-dive.** Stored alongside histogram buckets, each exemplar carries a value, timestamp, and labels including `trace_id`. Prometheus stores a small circular buffer of exemplars per series (limited, recent-only). Surfaced in Grafana as clickable dots on latency panels → opens Tempo trace. The bridge only works if both the metric (Micrometer with `SpanContextSupplier`) and the backend support exemplars.

**Profiling as the fourth/fifth pillar.** **Continuous profiling** samples stack traces across the fleet continuously (e.g. Parca, Pyroscope, Grafana Phlare, Datadog Profiler; on the JVM via **async-profiler** or **JFR** — Java Flight Recorder). It answers "*which exact lines of code burned the CPU/allocated the memory during that latency spike?*" — a granularity below the trace span. Visualized as **flame graphs** (Brendan Gregg). **eBPF** (extended Berkeley Packet Filter — a Linux kernel VM that runs sandboxed programs on kernel events) increasingly powers zero-instrumentation profiling and tracing.

**Events as a pillar.** A discrete state-change record (deploy, config change, scaling event, feature-flag flip) overlaid on time series. Most incidents correlate with a *change* — annotating dashboards with deploy events is one of the highest-ROI observability moves.

**Span links & async/fan-out.** A span can hold **links** to other spans/traces (not parent-child) — used for batch jobs that process many messages (one consumer span linking to N producer traces) where a strict tree doesn't fit.

**Tail-sampling at scale.** The hard part is consistent routing (all spans of a trace → one collector) and the memory/latency of `decision_wait`. Large shops run a two-tier collector: agent layer (per-node) → gateway layer (load-balanced by trace ID) doing tail sampling.

**Cardinality control techniques.** `metric_relabel_configs` to drop/aggregate labels; recording rules to pre-aggregate expensive queries; OTel `metricstransform`/`filter` processors; "spanmetrics" connector to derive RED metrics *from* traces so you don't double-instrument.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Pillar selection — choose the signal for the question

| You want to know… | Reach for | Don't use |
|---|---|---|
| Is something broken now, and trend over weeks | Metrics | Logs (too costly to aggregate) |
| Where in the call graph time/errors went | Traces | Metrics (no causal order) |
| The exact detail/payload of one event | Logs / wide events | Metrics |
| Behavior of an arbitrary high-card slice | Wide events / trace attrs | Metric labels |
| Which line of code burned CPU | Continuous profiling | Traces (too coarse) |
| What changed right before the incident | Events / deploy annotations | — |

### 8.2 Cost / cardinality profile

| Pillar | Cost scales with | Cardinality tolerance | Aggregation | Detail per event |
|---|---|---|---|---|
| Metrics | series count × scrape × retention | **Low** (kills you) | Excellent | None |
| Logs | volume × bytes × retention × index | High (but pricey) | Awkward | Full |
| Traces | spans × sampling rate | High (sampled) | Per-trace | High |
| Wide events | events × fields × retention | **Very high (the point)** | Query-time | Very high |
| Profiles | sample rate × fleet | Medium | Flame graph | Code-line |

### 8.3 Tooling choices

| Need | Options | Use when / Avoid when |
|---|---|---|
| Metrics store | Prometheus / Mimir / Cortex / Datadog / VictoriaMetrics | Prometheus for single-cluster; Mimir/Cortex/VM for long-term + horizontal scale; avoid Prometheus alone for multi-year retention |
| Logs store | Loki / Elastic / OpenSearch / Splunk | Loki when cost-sensitive & you query by label; Elastic when you need rich full-text search; avoid Elastic if budget-bound |
| Traces store | Tempo / Jaeger / Zipkin / Datadog | Tempo for cheap object-store-backed traces tied to Grafana; Jaeger for standalone |
| Sampling | Head / Tail | Head when simple & cost > completeness; Tail when you must keep all errors/slow traces |
| Instrumentation | Auto (Java agent) / Manual | Auto first for breadth; Manual for business-specific spans/attrs |

### 8.4 Pull vs push metrics, summary vs histogram

- **Pull (Prometheus)**: liveness for free, central control; bad for short-lived jobs → use Pushgateway/OTLP push.
- **Histogram**: aggregatable, server-side quantiles, must choose buckets (or use native) → default choice.
- **Summary**: client-side quantiles, *not* aggregatable across instances → avoid for fleet-wide percentiles.

---

## 9. Failure modes & debugging

**1. Cardinality explosion takes down Prometheus.**
- Symptom: Prometheus OOMs, scrapes lag, queries time out.
- Diagnose: `topk(20, count by (__name__)({__name__=~".+"}))`, check `prometheus_tsdb_head_series`, `/api/v1/status/tsdb` (top series by metric & label).
- Fix: drop the offending label via `metric_relabel_configs`; move per-entity questions to logs/traces.
- Real-world shape: an engineer adds `user_id`/`request_id`/`full_url` as a label "to be safe"; series jump from 50k to 50M overnight.

**2. Traces fragment (broken context propagation).**
- Symptom: traces show single spans or disconnected sub-trees; downstream services appear as separate root traces.
- Diagnose: check that `traceparent` is present on outbound requests (network capture / debug exporter); verify thread-pool/async boundaries wrap context.
- Fix: `Context.taskWrapping`, OTel reactor/executor instrumentation, ensure the propagator is configured (`W3CTraceContextPropagator`).

**3. Sampling dropped the error trace.**
- Symptom: an alert fired but no trace exists for the failed request.
- Diagnose: confirm sampler config; head sampling at 1% loses 99% of rare errors.
- Fix: tail sampling keep-on-error, or always-sample-on-error head sampler.

**4. Log volume blows the budget / loses lines.**
- Symptom: ingest backpressure, dropped logs, surprise bill.
- Diagnose: shipper drop metrics (Fluent Bit/Vector), Collector `otelcol_exporter_send_failed_log_records`.
- Fix: drop debug noise at Collector, sample logs, switch chatty multi-line logging to one wide event, tier retention.

**5. Quantiles look wrong / impossible (e.g. p99 < p50).**
- Cause: averaging summary percentiles across instances (mathematically invalid), or coarse histogram buckets, or aggregating quantiles instead of buckets.
- Fix: use histograms, aggregate *buckets* then `histogram_quantile`, or native histograms.

**6. Clock skew distorts traces.**
- Symptom: child span "starts before" parent; negative durations.
- Fix: NTP/chrony on all hosts; backends tolerate small skew but not large.

**7. The Collector is the bottleneck.**
- Symptom: span/metric drops, queue full, exporter retries.
- Diagnose: Collector's own metrics (`otelcol_processor_dropped_spans`, queue size, `memory_limiter` activity).
- Fix: scale gateway tier, tune `batch`/`sending_queue`, add `memory_limiter`.

**Debugging workflow (the funnel):** Alert (metric SLO burn) → find an exemplar trace → inspect the waterfall to localize the slow/failed span → pull logs by `trace_id` for that span → if it's CPU/alloc, open the continuous profile for that time window → correlate with a deploy/config **event**.

---

## 10. Interview drill

**Q1. What's the difference between monitoring and observability?**
Model answer: Monitoring watches *predefined* signals for *known* failure modes (known-unknowns) via dashboards/alerts; observability is the ability to ask *arbitrary new* questions of a running system to diagnose failures you didn't anticipate (unknown-unknowns), which requires keeping high-cardinality, high-dimensional data you can slice ad hoc.
- *Follow-up: Why can't you just add more dashboards?* Because you can only dashboard hypotheses you've already had; the combinatorial space of "which slice is misbehaving" is unbounded, so you need query-time exploration, not pre-aggregation.
- *Follow-up: What data property enables it?* High cardinality preserved per-event, plus a join key (trace ID) across signals.

**Q2. Explain the three pillars and what each is bad at.**
Model answer: Metrics — cheap aggregated numbers, great for "how much/trend", bad at per-event detail and high cardinality. Logs — full-fidelity discrete events, great for "what exactly happened", bad at cost and aggregation. Traces — causal request path across services, great for "where in the call graph", bad at standalone counting and cost without sampling.
- *Follow-up: Give a question each answers best.* (As in §8.1.)
- *Follow-up: Why need all three?* They're complementary; correlation across them (metric→trace→log) is the actual debugging workflow.

**Q3. How does cardinality affect each pillar's cost?**
Model answer: Metrics cost ≈ series count = product of label cardinalities, independent of request volume — high cardinality is catastrophic. Logs/traces/wide-events cost scales with event volume and tolerate (even want) high cardinality. So per-entity dimensions belong in logs/traces, not metric labels.
- *Follow-up: What happens if you add user_id as a metric label?* Series explosion → Prometheus OOM/slow queries.
- *Follow-up: How do high-cardinality observability tools cope?* Columnar storage + query-time aggregation, avoiding the per-series index.

**Q4. Head-based vs tail-based sampling — tradeoffs?**
Model answer: Head decides at the root before the outcome is known — cheap, simple, propagates via the sampled bit, but can drop the rare error/slow trace. Tail buffers the whole trace and decides on outcome — keeps errors/slow ones, but needs all spans of a trace co-located (load-balance by trace ID) and holds traces in memory.
- *Follow-up: How do you keep cost low yet never lose errors?* Tail-sample: 100% errors + 100% slow + small baseline %.
- *Follow-up: Why load-balance by trace ID?* So one collector sees the full trace to make an outcome decision.

**Q5. How do you correlate a metric spike with a specific trace and its logs?**
Model answer: Use **exemplars** — histogram buckets carry a sample trace ID, so a latency-metric dot links to a real slow trace; the trace's spans carry the trace ID; logs emitted during the request include the same `trace_id` (via MDC/OTel), so you query logs by trace ID. The trace ID is the universal join key.
- *Follow-up: What populates trace_id in logs?* OTel logback/log4j appender or an MDC bridge reading the active span context.
- *Follow-up: What if requests cross threads?* Context must be propagated explicitly (`Context.taskWrapping`), or correlation breaks.

**Q6. Why is averaging p99 across instances wrong, and what do you do instead?**
Model answer: Percentiles aren't linearly averageable — the mean of per-instance p99s isn't the fleet p99. Use histograms: sum the *buckets* across instances, then apply `histogram_quantile`. Summaries (client-side quantiles) can't be aggregated at all.

**Q7. (Senior-signal) You're starting from zero on a new service — what do you instrument first and why?**
Model answer: RED at the edges first (request rate, error rate, duration histogram) tied to an SLO, because it directly reflects user pain and is cheap. Then auto-instrument traces for the call graph, add a wide canonical event per request with high-cardinality business dimensions, ensure trace_id is in logs, and only then add bespoke business metrics. Justify by ROI: symptom-based SLO alerting catches the most user-facing breakage per dollar.
- *Follow-up: Why not start with detailed logs everywhere?* Cost and noise; you can't alert cheaply on logs and they don't give trend/SLO math.
- *Follow-up: Alert on causes or symptoms?* Symptoms (latency/errors/SLO burn); cause-based alerts (CPU) generate noise and miss novel failures.

**Q8. (Senior-signal) Logs cost is exploding. Walk me through cutting it without losing debuggability.**
Model answer: Audit volume by source; drop debug/noise at the Collector; replace N thin log lines/request with one wide structured event; sample non-error logs; ensure trace correlation so you can rely on traces for path and logs only for detail; tier retention (hot 7d / cold object store); consider Loki (label-index) over Elastic (full index) if query patterns allow. Trade full-text breadth for cost where acceptable.
- *Follow-up: Risk of sampling logs?* Losing the one line you needed — so never sample error logs; sample only successes.

**Q9. (Senior-signal) When would you deliberately NOT add tracing?**
Model answer: When propagation overhead/complexity outweighs value — e.g. a single monolith with no fan-out (metrics+logs suffice), ultra-low-latency hot paths where even batched export is unacceptable, or hard memory/budget constraints where tail sampling infra isn't justified. The signal you choose should match the question you actually have.

**Q10. Histogram vs summary in Micrometer/Prometheus — which and why?**
Model answer: Histogram, because buckets are aggregatable across instances and let the backend compute any quantile; summaries compute fixed quantiles client-side and can't be aggregated. Prefer native/exponential histograms for accuracy + lower storage.

**Q11. What is context propagation and how does it break?**
Model answer: Passing trace/span IDs across process and thread boundaries (W3C `traceparent` on the wire; thread-local `Context` in-process). Breaks across thread pools, `CompletableFuture`, reactive schedulers, and message queues unless explicitly carried — producing fragmented traces.

**Q12. What are the emerging "fourth/fifth pillars"?**
Model answer: **Continuous profiling** (CPU/alloc flame graphs across the fleet — sub-span granularity, often eBPF/async-profiler/JFR-based) and **events** (discrete change records like deploys overlaid on time series for correlation). They fill gaps the classic three leave: "which line of code" and "what changed".

---

## 11. Glossary

- **Observability**: ability to infer internal system state from external outputs; from control theory.
- **Monitoring**: watching predefined signals for anticipated failure modes.
- **Known-unknown / Unknown-unknown**: anticipated vs unanticipated failure modes.
- **Telemetry**: self-reported measurement data a system emits.
- **Instrumentation**: code that produces telemetry (manual or automatic).
- **Metric**: numeric measurement over time, identified by name + labels.
- **Time series**: sequence of `(timestamp, value)` for one unique label-set.
- **Label / Tag / Dimension**: key-value attribute on a metric/span.
- **Cardinality**: number of distinct values a field (or combination) can take.
- **Counter / Gauge / Histogram / Summary**: monotonic count / up-down snapshot / bucketed distribution / client-side-quantile distribution.
- **Quantile / Percentile**: value below which a fraction of observations fall (p99 etc.).
- **Bucket / `le`**: histogram boundary ("less than or equal").
- **Native/exponential histogram**: auto-scaled exponential buckets with bounded relative error.
- **Log**: discrete timestamped event record (unstructured / structured / wide event).
- **Structured log**: machine-readable key-value/JSON log.
- **Wide event / canonical log line**: one very wide structured record per unit of work.
- **Log level**: severity (TRACE…FATAL).
- **Inverted index**: term→documents map powering search.
- **Trace**: end-to-end path of one request as a tree of spans.
- **Span**: a single timed named operation within a trace.
- **Trace ID / Span ID / Parent span ID**: identifiers forming the trace tree; trace ID is the cross-pillar join key.
- **Context propagation**: passing trace context across process/thread boundaries.
- **W3C Trace Context / `traceparent`**: standard HTTP headers for trace propagation.
- **Sampling (head / tail)**: deciding which traces to keep, before vs after the outcome is known.
- **Exemplar**: a concrete example (with trace ID) linked from an aggregate metric bucket.
- **OpenTelemetry (OTel)**: CNCF standard API/SDK/protocol for all three signals.
- **OTLP**: OpenTelemetry wire protocol (protobuf over gRPC/HTTP).
- **Collector**: OTel pipeline (receive→process→export) decoupling apps from backends.
- **Resource**: immutable attributes identifying the telemetry producer.
- **CNCF**: Cloud Native Computing Foundation.
- **gRPC**: HTTP/2 + protobuf RPC framework.
- **Prometheus / Grafana / Loki / Tempo / Jaeger / Zipkin / Mimir / Cortex / Elastic**: open-source observability backends/tools.
- **PromQL / LogQL**: query languages for Prometheus / Loki.
- **TSDB / WAL**: time-series DB / write-ahead log.
- **Pull vs push**: scraping targets vs targets sending data; Pushgateway for batch jobs.
- **Micrometer / SLF4J / Logback / Log4j2**: JVM metrics facade / logging facade / logging impls.
- **MDC**: Mapped Diagnostic Context — thread-local key-values added to log lines (carries trace_id).
- **Java agent / bytecode instrumentation**: `-javaagent` jar that rewrites class bytecode to auto-instrument.
- **RED / USE / Four Golden Signals**: Rate-Errors-Duration / Utilization-Saturation-Errors / Latency-Traffic-Errors-Saturation — what-to-measure recipes.
- **SLI / SLO / SLA / Error budget**: indicator / objective / agreement / allowed unreliability.
- **Burn rate**: how fast you're consuming error budget.
- **Continuous profiling / Flame graph / async-profiler / JFR / eBPF**: fleet-wide CPU/alloc sampling / its visualization / JVM profilers / Linux kernel programmable observability.
- **Span link**: non-parent-child reference between spans (fan-out/batch).
- **Columnar storage**: per-column data layout enabling cheap wide-event high-cardinality queries.
- **PII**: personally identifiable information (must be redacted from telemetry).
- **12-factor app**: cloud-native conventions; logs as stdout event streams.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

- **Monitoring = known-unknowns (dashboards/alerts). Observability = unknown-unknowns (ad-hoc high-cardinality queries).**
- **3 pillars:** Metrics (how much / trend, cheap, **low cardinality**), Logs (what exactly, expensive, full detail), Traces (where in call graph, sampled). Emerging: **Events** (what changed), **Profiles** (which code line).
- **Metric series count = product of label cardinalities.** Never label with `user_id`/`request_id`/raw URL. Per-entity → logs/traces.
- **Instruments:** Counter (only up; use `rate()`), Gauge (up/down), Histogram (aggregatable, server-side quantiles — **default**), Summary (client-side, NOT aggregatable).
- **Join key = trace_id.** Put it in logs (MDC/OTel) and link from metrics via **exemplars**.
- **Sampling:** head = decide at root (cheap, may drop errors); tail = decide on outcome (keep all errors/slow, needs LB-by-trace-id + memory).
- **What to instrument first:** RED at edges → SLO alert on symptoms (latency/errors), not causes (CPU). Then traces, then wide events, then bespoke metrics.
- **Defaults to know:** Prometheus scrape 15s; OTLP gRPC 4317 / HTTP 4318; BatchSpanProcessor queue 2048 / batch 512 / 5s; W3C `traceparent` = version-traceid-spanid-flags.
- **Stack:** OTel (API/SDK/OTLP/Collector) → Prometheus(metrics)/Loki|Elastic(logs)/Tempo|Jaeger(traces) → Grafana.
- **Cost order (typical):** logs > traces > metrics, but high-card metrics flip it.
- **Top failure:** cardinality explosion OOMs Prometheus; broken context propagation fragments traces.
- **p99:** aggregate histogram *buckets* then `histogram_quantile`; never average percentiles.

### Self-test (no answers)

1. You add a `customer_id` label to an existing metric with 200 series and have 2 million customers. Estimate the new series count and explain what fails first.
2. A request crosses an HTTP boundary, lands on a thread pool, then publishes to Kafka. At each hop, what must happen for the trace to stay connected, and which hop most commonly breaks it?
3. Your latency-spike alert fired but you can find no trace for the slow request. Give three distinct root causes and the fix for each.
4. Design a sampling strategy that keeps 100% of errors and slow requests, ~1% of normal traffic, and explain the infra constraint it imposes on the Collector tier.
5. For each question, name the single best pillar: (a) "is checkout error rate up this week?" (b) "why did *order o-123* fail?" (c) "which downstream call ate the 900ms?" (d) "which code line burned CPU during the spike?" (e) "what changed right before the incident?"
6. Explain why a summary's p99 can't be averaged across 10 instances but a histogram's can, and what you'd actually compute.
7. You must cut a runaway log bill in half without losing debuggability. List the moves in priority order and the one log type you must never sample.

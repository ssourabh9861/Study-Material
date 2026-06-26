# Logging & Aggregation

> A definitive engineering-handbook chapter for senior JVM/backend developers. From first principles to deep internals: structured logging, correlation IDs, log levels, centralized aggregation (ELK, Loki, Splunk), cost control, querying, and the logs-vs-metrics-vs-traces distinction.

---

## 1. Overview & where it fits

**Logging** is the practice of emitting a durable, time-ordered record of discrete events that happened inside a running program. A *log line* (or *log record/event*) is a structured or unstructured statement like "user 42 placed order 9981 at 12:03:55.123Z." **Log aggregation** is the practice of collecting those records from many processes, machines, and containers into a central system where they can be searched, filtered, correlated, alerted on, and retained.

**The problem it solves.** A single process on your laptop can log to `stdout` and you read it with your eyes. In production you have dozens to thousands of process instances (pods, VMs, lambdas) that come and go, write to ephemeral disks, and fail at 3 a.m. You cannot SSH into 400 containers to `grep`. You need: (a) a *consistent, machine-parseable* log format, (b) a *pipeline* that ships logs off the box before it dies, (c) a *store* you can query in seconds across all instances, and (d) *correlation* so you can stitch one user request as it hops across five services. Logging & aggregation is the discipline that delivers all four.

**When you reach for it.** Always, for any service that runs unattended. Specifically you lean on logs (versus metrics or traces) when you need the *full detail of a specific event* — the exact exception stack trace, the exact request payload that triggered a bug, the exact sequence of decisions a piece of code made. Logs answer "what exactly happened to *this* request?" Metrics answer "what is the aggregate behavior over time?" Traces answer "where did the latency go across services?"

**One-paragraph mental model.** Think of logging as writing append-only sentences about events, and aggregation as a postal + library system: each service writes sentences in a *common grammar* (structured JSON with stable field names), stamps each with a *return address and a tracking number* (host, service, and a correlation/trace ID), a local *mail carrier* (an agent like Filebeat, Fluent Bit, or Promtail/Alloy) picks them up off the machine, a *sorting facility* (Logstash, Fluentd, or the ingest tier) parses and enriches them, and a *library* (Elasticsearch, Loki, or Splunk indexers) stores and indexes them so a *reading room* (Kibana, Grafana, Splunk Search) lets you find any sentence — or count of sentences — across the whole organization in seconds. The two engineering tensions that run through everything below are **cost** (logs are voluminous and storage/indexing is expensive) and **findability** (you must be able to locate the needle, which requires discipline at write time).

---

## 2. Foundations from first principles

### 2.1 What is a "log" really?

At the lowest level, a log is a sequence of bytes appended to a sink. The canonical sink on Unix is a **file descriptor** — an integer handle the kernel gives a process to refer to an open file or stream. Three are opened by convention at process start:

- **`stdin`** (fd 0) — standard input.
- **`stdout`** (fd 1) — standard output, the "normal results" stream.
- **`stderr`** (fd 2) — standard error, the "diagnostics" stream.

> **Why two output streams?** So you can redirect normal output and diagnostics separately. In modern container logging, both are usually captured. The **twelve-factor app** methodology (a popular set of cloud-app design rules) says a process should treat logs as an *event stream* written to `stdout` and let the *environment* handle routing/storage — the app should not manage log files or rotation itself.

A program does not write bytes to fd 1 directly for each event in practice; it goes through a **logging framework** (in Java: SLF4J + Logback or Log4j2; in other ecosystems: `zap`/`logrus` in Go, `winston`/`pino` in Node, Python's `logging`). The framework adds: levels, formatting/layout, asynchronous buffering, multiple destinations ("appenders"), and structured fields.

### 2.2 Unstructured vs structured logging

**Unstructured (string) logging** emits human-prose lines:

```
2026-06-24 12:03:55.123 ERROR [order-svc] Failed to charge order 9981 for user 42: gateway timeout after 3000ms
```

This is readable by a human but a nightmare for a machine. To find "all charge failures for user 42," a tool must apply a regular expression (a **regex** — a pattern-matching mini-language) to rip the user ID out of free text. That regex breaks the moment someone reworded the message, added a field, or a user ID happened to appear in another sentence.

**Structured logging** emits the same event as a set of named key/value fields, almost always serialized as **JSON** (JavaScript Object Notation — a text format of `{"key": value}` pairs, the lingua franca of machine data interchange):

```json
{"@timestamp":"2026-06-24T12:03:55.123Z","level":"ERROR","logger":"com.shop.order.ChargeService","service":"order-svc","msg":"charge failed","order_id":9981,"user_id":42,"reason":"gateway_timeout","timeout_ms":3000,"trace_id":"a1b2c3d4e5f6"}
```

Now "all charge failures for user 42" is the query `level:ERROR AND service:order-svc AND user_id:42` — exact, fast, and immune to message wording. This single shift (prose → fields) is the most important practical idea in the whole chapter.

#### Why structured beats string logs (the full list)

1. **Deterministic querying.** Field equality/range queries instead of fragile regex over prose.
2. **Indexability.** Aggregation backends index fields; numeric fields support range queries (`latency_ms > 500`); keyword fields support exact match and faceting.
3. **Aggregation/analytics.** You can compute "p99 of `latency_ms` grouped by `endpoint`" directly from logs because the value is a typed field, not a substring.
4. **Stable contract.** Field names form an API. Renaming `userId`→`user_id` is a breaking change you can manage; reworded prose silently breaks dashboards.
5. **Correlation.** IDs (`trace_id`, `request_id`) are first-class fields you can join on across services.
6. **Lower parse cost.** No per-line regex (Grok) at ingest; JSON parses cheaply and reliably. Grok regexes are a notorious CPU sink and a source of "_grokparsefailure" garbage.
7. **Tooling & alerting.** Alerts ("page if `error_code:DB_DEADLOCK` count > 10/min") are trivially expressible.
8. **PII governance.** Sensitive data lives in known fields you can redact/drop centrally, rather than buried mid-sentence.

The cost of structured logging: slightly more verbose on disk, marginally more CPU to serialize, and developers must think about field names. All are minor next to the operational payoff.

### 2.3 Log levels — the severity ladder

A **log level** is a severity tag attached to each record. It serves two purposes: it tells the reader *how alarmed to be*, and it acts as a *runtime filter* — the framework drops records below the configured threshold cheaply. The standard ladder (most frameworks, SLF4J/Logback/Log4j2):

| Level | Numeric (Log4j2) | Meaning | Typical prod use |
|---|---|---|---|
| **TRACE** | 600 | Extremely fine-grained; per-loop, per-byte. | Off in prod; on for targeted debugging. |
| **DEBUG** | 500 | Developer diagnostics; variable values, branch decisions. | Off in prod by default; toggle for incidents. |
| **INFO** | 400 | Normal, noteworthy lifecycle events (startup, request handled, job done). | On in prod, but be sparing. |
| **WARN** | 300 | Something unexpected but recoverable; degraded but not failed. | On. Should be rare enough to notice. |
| **ERROR** | 200 | An operation failed; a request/job did not complete. | On. Often alertable. |
| **FATAL** (Log4j2) / no SLF4J equivalent | 100 | The process cannot continue; about to exit. | On; usually paired with shutdown. |
| **OFF** | 0 | Disable logging. | — |

SLF4J (the Java logging *facade* — see §4) defines TRACE, DEBUG, INFO, WARN, ERROR (no FATAL). Logback maps FATAL→ERROR. **Key principle:** the level is the cheapest, most universal lever for log *volume* and *cost*. You can raise a service to DEBUG for one bad node during an incident and lower it back, ideally without redeploy (see §7.5).

> **What is a logging "facade"?** A facade is a thin, framework-agnostic API your code compiles against (e.g., `org.slf4j.Logger`). At runtime, a *binding* routes those calls to a concrete implementation (Logback, Log4j2, JUL). This decouples your code from the logging engine, so libraries don't force a backend on the application.

### 2.4 What to log — and what to *never* log

**Log generously at the right level:**

- Request boundaries: inbound request received (method, path, principal, request ID), response sent (status, latency).
- State transitions and decisions: "order moved PENDING→PAID", "circuit breaker opened", "cache miss → DB fetch".
- External calls: downstream service/DB/queue calls with target, latency, outcome.
- Errors with full context: exception type, message, **stack trace**, and the business identifiers needed to reproduce (order_id, tenant_id) — but not the sensitive payload.
- Lifecycle: startup config (sanitized), shutdown, config reloads, leader-election changes, schema migrations.

**Never log (PII/secrets/regulated data):**

- **Secrets:** passwords, API keys, tokens (JWTs, OAuth bearer/refresh tokens), private keys, DB connection strings with credentials, session cookies.
- **PII (Personally Identifiable Information):** full names tied to behavior, emails, phone numbers, home addresses, government IDs (SSN, Aadhaar, passport), full dates of birth, precise geolocation.
- **Financial/regulated:** full PAN (Primary Account Number — the card number), CVV (never, ever — PCI-DSS forbids storing it post-authorization), bank account numbers, full health records (PHI under HIPAA).
- **Anything that lets an attacker or a curious employee impersonate or harm a user.**

> **Why this is existential, not pedantic.** Logs are copied, fanned out to aggregation backends, retained for months, and read by far more people (and systems) than the database. A secret in a log is a secret in a dozen places. Real incidents: companies have leaked OAuth tokens via logs that were then indexed by SaaS log vendors. Regulatory regimes make this expensive: **GDPR** (EU General Data Protection Regulation) treats logs containing personal data as in-scope for deletion/retention rules; **PCI-DSS** (Payment Card Industry Data Security Standard) explicitly prohibits logging full card data and CVV; **HIPAA** governs PHI. Practical defenses: redact at the framework layer, maintain an allow-list of loggable fields for sensitive objects, and run a CI check / log-scanner that fails builds on patterns that look like card numbers or keys.

### 2.5 Correlation IDs, trace IDs, and MDC

In a distributed system one user action ("checkout") becomes many log lines across many services. To reassemble the story you need a shared key.

- **Request ID / correlation ID:** a unique ID minted at the system edge (API gateway or first service) for one inbound request, propagated to every downstream call (usually via an HTTP header like `X-Request-Id` or `X-Correlation-Id`). Every log line for that request carries it.
- **Trace ID / span ID:** the distributed-tracing version. A **trace** is the whole journey of a request across services; a **span** is one unit of work within it (one service handling the request, or one DB call). The **trace ID** identifies the whole journey; each **span ID** identifies a step. The modern standard is **W3C Trace Context**, carried in the `traceparent` header (format `version-traceid-spanid-flags`, e.g. `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`). **OpenTelemetry (OTel)** is the CNCF standard SDK/protocol for generating and propagating these.

> **CNCF?** The Cloud Native Computing Foundation, the open-source body that hosts Kubernetes, Prometheus, OpenTelemetry, Fluentd, Jaeger, and others. "CNCF project" is shorthand for a vendor-neutral, widely adopted standard.

**MDC (Mapped Diagnostic Context)** is the mechanism that makes correlation IDs appear on *every* log line without you passing them to each `log.info()` call. MDC is a **thread-local** key/value map provided by SLF4J/Logback/Log4j2.

> **Thread-local?** A `ThreadLocal` is a variable whose value is private to each thread — thread A and thread B see different values for the "same" variable. MDC stores its map in a `ThreadLocal`, so the IDs you put at the start of request handling are visible to all log calls made by that thread, then cleared at the end.

The lifecycle: at request entry, a filter/interceptor reads (or generates) the correlation/trace ID and calls `MDC.put("trace_id", id)`. Your log pattern includes `%X{trace_id}` (Logback) so every line emits it. At request exit, `MDC.clear()` (critical — see §6/§9 for the leak/cross-talk bug when you forget, especially with thread pools and reactive code).

### 2.6 Logs vs events vs metrics vs traces (the "pillars")

These overlap and people conflate them; precise definitions matter for cost and design.

| Concept | What it is | Cardinality/volume | Best at | Typical store |
|---|---|---|---|---|
| **Log** | A timestamped record of one discrete occurrence, often with free text + fields. | High volume, high-cardinality fields OK. | Deep per-event detail; forensics; "what happened to *this* one?" | Elasticsearch/Loki/Splunk |
| **Event** | A *structured, semantic* business/system fact (e.g., `OrderPaid`). Often a well-typed subset of logs, or its own stream (Kafka). "Wide events" / "canonical log lines" treat one rich structured log per request as the unit. | Medium. | Analytics, audit, replay, driving downstream systems. | Kafka, data warehouse, log store |
| **Metric** | A numeric measurement aggregated over time (counter, gauge, histogram), labeled with low-cardinality dimensions. | Low storage per series; explodes with high-cardinality labels. | Trends, alerting, dashboards; "how much / how fast in aggregate?" | Prometheus/Mimir, Graphite, Datadog |
| **Trace** | A causal tree of spans for one request across services, with timing. | Medium; usually sampled. | Latency attribution; "where did the time go / where did it fail across services?" | Jaeger/Tempo/Zipkin |

Key distinctions:

- **Logs are events with words; metrics are numbers without identity.** A metric `http_requests_total{status="500"}` tells you *how many* 500s; the log tells you *which* requests and *why*.
- **Cardinality** is the killer constraint. **Cardinality** = the number of distinct values a label/field can take. Metrics systems (Prometheus) die under high-cardinality labels (e.g., `user_id` as a metric label creates millions of time series). Logs *welcome* high-cardinality fields (that's the point of `user_id` in a log). This is the single most important reason you do not "just put everything in metrics."
- **Derivation:** you can derive metrics from logs (count error logs) and even traces from logs (if spans are logged), but it is lossy/expensive; purpose-built pipelines are better. OpenTelemetry unifies all three under one SDK and the **OTLP** protocol (OpenTelemetry Protocol) so you instrument once and route logs, metrics, traces to appropriate backends.

---

## 3. How it works internally

This section traces the full path of a single log statement from the call site in your Java code to a queryable document in a central store, and the internal machinery of the major aggregation backends.

### 3.1 The in-process path (Java/SLF4J/Logback)

Step by step, what happens when you call `log.info("charge failed", kv("order_id", 9981))`:

1. **Level check (the fast path / "guard").** The logger first compares the call's level to its **effective level**. Logger names are hierarchical and dot-separated (`com.shop.order.ChargeService`); a logger inherits its level from the nearest configured ancestor (down to the **root logger**). If INFO is enabled, proceed; if not, the call returns almost immediately. Logback/Log4j2 keep an effective-level cache so this is a cheap field compare, not a tree walk, on the hot path.

   > This is why `if (log.isDebugEnabled()) log.debug(expensiveToBuild())` exists: to avoid building the message argument when DEBUG is off. With SLF4J parameterized logging `log.debug("x={}", x)` you usually don't need the guard — the message isn't formatted unless the level passes — *unless* an *argument* itself is expensive to compute, in which case use the guard or a `Supplier` (Log4j2 supports `log.debug("x={}", () -> expensive())`).

2. **`LoggingEvent` construction.** The framework captures: timestamp (millis since epoch), level, logger name, the message template + arguments, the current thread name, a snapshot of the **MDC** map, any **markers**, and (if requested) caller location info (class/method/line — *expensive*, see §6).

3. **Filters & TurboFilters.** Logback evaluates filters (e.g., deny lines matching a marker, or rate-limit). TurboFilters run before the event is even created for cross-cutting decisions.

4. **Appender dispatch.** The event is handed to each **appender** attached to the logger (and ancestors, unless `additivity=false`). An appender is a destination: `ConsoleAppender` (stdout/err), `FileAppender`/`RollingFileAppender`, `SocketAppender`, or framework-specific ones.

5. **Layout/Encoder.** The appender uses an **encoder** (Logback) or **layout** (Log4j2) to turn the event into bytes — either a `PatternLayout` (the classic `%d %level %logger - %msg%n`) or a JSON encoder (e.g., `logstash-logback-encoder`'s `LogstashEncoder`, or Log4j2 `JsonTemplateLayout`).

6. **Async handoff (optional but important).** If you use `AsyncAppender` (Logback) or the **LMAX Disruptor**-backed async loggers (Log4j2), the event is placed on an in-memory ring buffer and a background thread does steps 5+7. This decouples your request thread from slow I/O.

   > **LMAX Disruptor?** A high-performance lock-free ring-buffer library. Log4j2's async loggers use it to pass events between threads with minimal contention, achieving very high throughput (millions of events/sec in benchmarks). The tradeoff: events sitting in the buffer can be lost on a hard crash.

7. **I/O write.** Bytes are written to the OS via a buffered stream. For a file, the JVM writes to its own buffer, then `write(2)` to the page cache; durability to disk requires `flush`/`fsync` (frameworks flush on each event by default for console/file unless `immediateFlush=false`). For console in containers, fd 1/2 go to the container runtime.

8. **Rotation (file appenders).** `RollingFileAppender` rolls by size and/or time (`TimeBasedRollingPolicy`, `SizeAndTimeBasedRollingPolicy`), optionally compresses (`.gz`) and deletes old files by `maxHistory`/`totalSizeCap`.

### 3.2 The off-box path — collection, shipping, parsing

In containerized/cloud environments the app typically writes JSON to `stdout`; from there:

1. **Container runtime capture.** The container runtime (containerd/CRI-O via Docker's `json-file` driver or Kubernetes) captures stdout/stderr and writes to a host file, e.g. `/var/log/pods/<ns>_<pod>_<uid>/<container>/0.log`, often wrapping each line in its own JSON with `time`, `stream`, `log`.

2. **Node agent (the collector / "shipper").** A lightweight agent runs on each node (commonly as a Kubernetes **DaemonSet** — one pod per node) and tails those files. Common agents:
   - **Filebeat** / **Elastic Agent** (Elastic stack).
   - **Fluentd** (CNCF, Ruby + C, plugin-rich, heavier) or **Fluent Bit** (CNCF, C, very lightweight — the modern default for nodes).
   - **Promtail** / **Grafana Alloy** (for Loki; Promtail is being superseded by Alloy).
   - **Vector** (Rust, high-performance, vendor-neutral).
   The agent does: tailing with offset tracking (so restarts resume, not re-send), multiline assembly (stitching Java stack traces into one event), parsing (JSON decode, or Grok/regex for legacy), enrichment (add k8s pod/namespace/labels, host metadata), buffering (memory + disk-backed for backpressure), and batching/compression to the next hop.

3. **Aggregation/processing tier (optional).** A heavier processor may sit between agents and store: **Logstash** (Elastic), **Fluentd** as aggregator, or a **Vector** aggregator. It does expensive transforms (Grok parsing, geoIP, field renaming, dropping/sampling, routing to multiple sinks). Many modern designs push parsing to the lightweight agent and *skip* this tier to save cost.

4. **Buffering & backpressure.** Between every hop there is a buffer. If the store is slow/down, buffers fill; agents apply backpressure (stop reading, spill to disk). If buffers overflow you lose logs or block the app (rare, but a real failure mode — see §9). A message bus (**Kafka**) is often inserted as a durable buffer for high-volume pipelines: agents → Kafka → consumers → store. Kafka decouples spikes and lets you replay.

5. **Ingest & index.** The store ingests, may run its own pipeline (Elasticsearch **ingest pipelines/processors**), then indexes.

6. **Store & retention.** Data lands in hot storage, ages to warm/cold/frozen tiers, then is deleted per retention policy.

7. **Query.** A UI (Kibana/Grafana/Splunk) queries the store.

### 3.3 Internals of Elasticsearch (the "E" in ELK/Elastic)

**Elasticsearch (ES)** is a distributed search engine built on **Apache Lucene**.

> **Lucene?** A Java library implementing an **inverted index** — a data structure mapping each term to the list of documents containing it (like a book's index), enabling fast full-text search. ES wraps Lucene with distribution, REST APIs, JSON documents, and aggregations.

Core concepts and the write path:

- A **document** is a JSON object (your log event). Documents live in an **index** (a named collection, e.g. `logs-order-svc-2026.06.24`).
- An index is split into **shards** (a shard = one self-contained Lucene index). Shards are spread across **nodes** for scale; each shard has **replicas** for redundancy. This is **horizontal sharding** — splitting data so no single node holds it all.
- **Mapping** is the schema: it declares each field's type. Two text-ish types matter hugely for cost: **`text`** (analyzed/tokenized for full-text search) and **`keyword`** (stored verbatim for exact match, sorting, aggregation). Numeric/date/ip types enable range queries.

Write path (indexing a log doc):
1. Doc is routed to a primary shard by hashing its ID.
2. Written to an **in-memory buffer** and an append-only **translog** (transaction log) for durability before it's searchable.
3. Periodically (default **`refresh_interval` = 1s**) the buffer is flushed to a new in-memory **segment** (an immutable Lucene index file), making docs searchable — this is **near-real-time** search (≈1s lag).
4. The **analyzer** tokenizes `text` fields (lowercasing, splitting on whitespace, etc.) and builds the inverted index. `keyword` fields are indexed whole. This analysis is the CPU and storage cost of "indexing everything."
5. The translog is `fsync`'d on each request by default (durability). Periodically a **flush** commits segments to disk and clears the translog.
6. Background **merges** combine small segments into larger ones (I/O cost; necessary to keep query speed up).
7. Replicas receive the same operations.

Read path: a query fans out to all relevant shards, each searches its segments via the inverted index, results are merged and ranked. **Aggregations** (terms, date_histogram, percentiles) run across shards and merge.

**Why ES is expensive:** it indexes *every field by default*, which costs CPU at write and disk for the inverted indexes and **doc values** (a columnar structure for sorting/aggregation). Mitigations: dynamic mapping control, `index: false` on fields you only display, `doc_values: false` on fields you never aggregate, and **ILM** (Index Lifecycle Management) to tier and delete.

### 3.4 Internals of Loki (Grafana)

**Loki** takes the opposite philosophy: *"like Prometheus, but for logs."* Its slogan is that it indexes **only labels, not the log content.**

- Each log stream is identified by a **set of labels** (e.g. `{namespace="prod", app="order-svc", level="error"}`). Loki builds an index on the *labels* only.
- The actual log *lines* are compressed into **chunks** and stored cheaply in **object storage** (S3/GCS/Azure Blob). The line text is **not** inverted-indexed.
- To search content, Loki uses **LogQL**: it first uses the small label index to select the relevant streams/chunks, then **brute-force scans (greps)** the chunk contents in parallel for your filter expression (`|= "timeout"`, `|~ "regex"`, or `| json | user_id=42`).

> **Object storage?** Cheap, durable, virtually unlimited blob storage (Amazon S3 and equivalents) with simple GET/PUT semantics, far cheaper per GB than SSD/indexed storage — but higher latency and no built-in indexing.

**Tradeoff:** Loki is dramatically cheaper to ingest and store (no per-field inverted index, cheap object storage) — but a content query over a large time range/many streams scans more data, so it can be slower than ES *unless* your labels narrow the search well. The **golden rule of Loki: keep label cardinality low.** Each unique label-set combination is a stream; high-cardinality labels (like `user_id` or `trace_id` as labels) cause a "stream explosion" that destroys the index. Put high-cardinality values *in the log line* (queryable via `| json | user_id=42` at query time), not in labels.

### 3.5 Internals of Splunk

**Splunk** is the heavyweight commercial incumbent. Components:

- **Forwarders** collect data (Universal Forwarder = lightweight shipper; Heavy Forwarder can parse).
- **Indexers** receive, parse, and store data in time-bucketed indexes. Splunk's model is **schema-on-read**: it stores raw events plus a lightweight index of timestamps and a **TSIDX** (time-series index) of terms, and *extracts fields at search time* via its query language **SPL** (Search Processing Language). This avoids upfront schema rigidity (you can extract new fields from old data without reindexing) but pushes work to query time.
- **Search Heads** run SPL queries, fan out to indexers (**map-reduce** style), and merge.
- Buckets age **hot → warm → cold → frozen** (frozen = deleted or archived to cheap storage).

Splunk's strength is power/maturity (SPL is extremely expressive, huge app ecosystem, strong security/SIEM features). Its weakness is **cost** — historically priced by GB/day ingested, which gets very expensive at scale and pushes teams toward Loki/ES or ingest-side filtering.

### 3.6 State machine of a log event end-to-end

```
[created in app] → (level filter) → [LoggingEvent] → (encoder→JSON)
   → [stdout] → [container runtime file] → (agent tail+parse+enrich)
   → [agent buffer] → (network, maybe via Kafka) → [processor: parse/drop/sample]
   → [store ingest pipeline] → [indexed/segmented] → [hot tier]
   → (age) → [warm] → [cold/frozen] → (retention) → [deleted]
                    ↘ (query at any tier) → [UI result]
```

Failure transitions at each arrow: dropped by filter (intended), parse failure (`_grokparsefailure`), buffer overflow → drop or block, network partition → agent retries/spills, store rejects (mapping conflict, disk full / ES read-only watermark), retention deletes data you still needed.

---

## 4. The complete toolkit

### 4.1 Java logging stack

| Component | Role | Notes / defaults |
|---|---|---|
| **SLF4J** | Logging *facade* (API your code uses). | `org.slf4j.Logger`, `LoggerFactory.getLogger(Class)`. Methods: `trace/debug/info/warn/error`, parameterized: `log.info("x={}, y={}", x, y)` (no string concat, no premature formatting). `org.slf4j.MDC` for context. SLF4J 2.x adds a **fluent API**: `log.atInfo().setMessage("...").addKeyValue("order_id", id).log()`. |
| **Logback** | Reference SLF4J implementation. | Config `logback.xml` / `logback-spring.xml`. Appenders, encoders, filters, async. |
| **Log4j2** | High-performance alternative. | Config `log4j2.xml`. Async loggers via LMAX Disruptor; `JsonTemplateLayout`; `ThreadContext` = its MDC. |
| **java.util.logging (JUL)** | JDK built-in. | Rarely used directly; bridged to SLF4J via `jul-to-slf4j`. |
| **logstash-logback-encoder** | JSON encoder + structured args for Logback. | Provides `LogstashEncoder`, `LoggingEventCompositeJsonEncoder`, and `net.logstash.logback.argument.StructuredArguments` (`kv()`, `keyValue()`, `value()`). The de-facto way to emit JSON from Logback. |
| **Bridges** | Route other APIs into your chosen backend. | `jul-to-slf4j`, `log4j-over-slf4j`, `jcl-over-slf4j`. **Never** have both a real backend and its bridge on the classpath (loops/conflicts). |

#### SLF4J / MDC API essentials

| Call | Purpose |
|---|---|
| `Logger log = LoggerFactory.getLogger(Foo.class);` | Get a named logger (name = FQCN). |
| `log.info("msg {}", arg)` | Parameterized log; arg formatted only if INFO enabled. |
| `log.error("failed", ex)` | Last arg as `Throwable` → logs stack trace. |
| `log.atInfo().addKeyValue("k", v).log("msg")` | SLF4J 2.x fluent structured logging. |
| `MDC.put("trace_id", id)` / `MDC.get` / `MDC.remove` / `MDC.clear()` | Per-thread context map; emitted via `%X{trace_id}`. |
| `MDC.putCloseable("k", v)` | Returns an `AutoCloseable` for try-with-resources auto-removal. |
| `Marker m = MarkerFactory.getMarker("AUDIT")` | Tag events for routing/filtering. |

### 4.2 Logback configuration elements

| Element | Purpose | Key attrs / defaults |
|---|---|---|
| `<configuration>` | Root. | `scan="true" scanPeriod="30 seconds"` to hot-reload config. |
| `<appender>` | A destination. | `class=ConsoleAppender / RollingFileAppender / AsyncAppender`. |
| `<encoder>` | Event→bytes. | `PatternLayoutEncoder` (text) or `LogstashEncoder` (JSON). |
| `RollingFileAppender` + `<rollingPolicy>` | File rotation. | `TimeBasedRollingPolicy` / `SizeAndTimeBasedRollingPolicy`; `maxFileSize` (e.g. `100MB`), `maxHistory` (days/files), `totalSizeCap`. |
| `AsyncAppender` | Off-thread I/O. | `queueSize` default **256**; `discardingThreshold` default **20%** (drops TRACE/DEBUG/INFO when queue ~80% full — set to 0 to never discard); `neverBlock` default false; `includeCallerData` default false. |
| `<logger name= level= additivity=>` | Per-package level/routing. | `additivity="false"` stops propagation to root. |
| `<root level=>` | Default level for all. | Common: `INFO`. |
| `<filter>` / `<turboFilter>` | Drop/accept events. | `LevelFilter`, `ThresholdFilter`, `EvaluatorFilter`, rate-limiting custom filters. |
| `%X{key}`, `%mdc` | Emit MDC in pattern layout. | — |
| `%d %level %logger %thread %msg %ex %n` | Pattern tokens. | `%ex`/`%throwable` for stack trace; `%caller` expensive. |
| `<springProfile>` | Profile-specific config (Spring Boot). | In `logback-spring.xml` only. |

### 4.3 Collectors / shippers

| Tool | Lang | Footprint | Strengths | Use when |
|---|---|---|---|---|
| **Fluent Bit** | C | Tiny (MBs RAM) | Fast, low-resource, k8s-native, OTLP support. | Node DaemonSet collector (modern default). |
| **Fluentd** | Ruby/C | Heavier | Huge plugin ecosystem, flexible routing/aggregation. | Aggregator tier, complex routing. |
| **Filebeat / Elastic Agent** | Go | Light | First-class Elastic integration, modules, registry offsets. | ELK/Elastic shops. |
| **Logstash** | JRuby/JVM | Heavy (GBs) | Powerful filters (Grok, mutate, geoIP), many inputs/outputs. | Heavy parsing/enrichment tier. |
| **Promtail / Grafana Alloy** | Go | Light | Loki-native; label extraction; Alloy unifies logs+metrics+traces (OTel collector distro). | Loki pipelines. |
| **Vector** | Rust | Light, fast | Vendor-neutral, VRL transform language, end-to-end backpressure. | High-throughput, multi-sink, cost-sensitive. |
| **OpenTelemetry Collector** | Go | Light–med | Unified logs/metrics/traces, OTLP, vendor-neutral processors/exporters. | Standardizing on OTel across signals. |

### 4.4 Backends & query languages

| Backend | Query lang | Index model | Cost driver | Sweet spot |
|---|---|---|---|---|
| **Elasticsearch / OpenSearch** | Query DSL (JSON), KQL/Lucene in Kibana, ES|QL | Inverted index on (by default) all fields | CPU/disk for indexing every field | Rich full-text + aggregations, APM, search. |
| **Loki** | LogQL | Index on **labels only**; content brute-forced | Very low ingest cost; query scan cost | Cheap, k8s-label-driven, Grafana shops. |
| **Splunk** | SPL | Schema-on-read, TSIDX | Per-GB ingest licensing | Enterprise, SIEM, mature analytics. |
| **Datadog Logs / CloudWatch / GCP Logging / Azure Monitor** | Vendor | Managed | Per-GB ingest + retention | Managed, low-ops, integrated with cloud. |

> **OpenSearch?** The Apache-2.0 fork of Elasticsearch (and Kibana→OpenSearch Dashboards) created after Elastic changed ES's license (SSPL) in 2021. AWS leads it. API-compatible with ES 7.10 era; has since diverged. ES later added back an AGPL/open option in 2024. If license matters, flag your version.

### 4.5 Key Elasticsearch ingest/lifecycle tooling

| Tool/feature | Purpose |
|---|---|
| **Ingest pipelines / processors** | In-ES transform: `grok`, `dissect`, `json`, `set`, `remove`, `rename`, `date`, `geoip`, `user_agent`, `drop`. |
| **Index templates / component templates** | Apply mappings/settings to indices by name pattern. |
| **ILM (Index Lifecycle Management)** | Roll over (`max_size`/`max_age`/`max_docs`), move hot→warm→cold→frozen, shrink/forcemerge, delete. |
| **Data streams** | Append-only abstraction over time-series indices (auto rollover). |
| **Searchable snapshots / frozen tier** | Query data stored in object storage cheaply. |
| **`_cat` APIs** | Quick ops: `_cat/indices?v`, `_cat/shards`, `_cat/nodes`. |
| **Cluster watermarks** | Disk thresholds: low **85%**, high **90%**, flood-stage **95%** → indices go read-only. (Defaults.) |

---

## 5. Code examples by use case

### 5.1 Idiomatic JSON logging from Logback (worked config)

`logback-spring.xml` for a Spring Boot service emitting JSON to stdout (12-factor) plus a rolling file for local debugging:

```xml
<configuration scan="true" scanPeriod="30 seconds">

  <!-- JSON to stdout: the production destination. The container runtime
       captures stdout; an agent ships it. No file management in prod. -->
  <appender name="JSON_STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <!-- Stable field names. timeZone in UTC; downstream stores expect UTC. -->
      <timeZone>UTC</timeZone>
      <!-- Promote selected MDC keys to top-level fields (omit to include all MDC). -->
      <includeMdcKeyName>trace_id</includeMdcKeyName>
      <includeMdcKeyName>span_id</includeMdcKeyName>
      <includeMdcKeyName>request_id</includeMdcKeyName>
      <includeMdcKeyName>user_id</includeMdcKeyName>
      <!-- Static fields identifying the service; usually set via env in real deploys. -->
      <customFields>{"service":"order-svc","env":"${APP_ENV:-dev}"}</customFields>
      <!-- Cap stack trace size so one runaway exception can't blow up costs. -->
      <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
        <maxDepthPerThrowable>30</maxDepthPerThrowable>
        <maxLength>8192</maxLength>
        <rootCauseFirst>true</rootCauseFirst>
      </throwableConverter>
    </encoder>
  </appender>

  <!-- Async wrapper: decouple request threads from I/O. -->
  <appender name="ASYNC_JSON" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="JSON_STDOUT"/>
    <queueSize>8192</queueSize>            <!-- default 256 is too small for busy svc -->
    <discardingThreshold>0</discardingThreshold> <!-- 0 = never silently drop INFO+ -->
    <neverBlock>true</neverBlock>          <!-- prefer dropping over blocking req thread -->
    <includeCallerData>false</includeCallerData> <!-- caller info is expensive; keep off -->
  </appender>

  <!-- Quiet noisy libraries; keep our app at INFO. -->
  <logger name="org.hibernate.SQL" level="WARN"/>
  <logger name="org.apache.kafka" level="WARN"/>
  <logger name="com.shop" level="INFO"/>

  <root level="INFO">
    <appender-ref ref="ASYNC_JSON"/>
  </root>
</configuration>
```

Example output (one line, pretty-printed here):

```json
{
  "@timestamp": "2026-06-24T12:03:55.123Z",
  "level": "ERROR",
  "logger_name": "com.shop.order.ChargeService",
  "thread_name": "http-nio-8080-exec-3",
  "message": "charge failed",
  "service": "order-svc", "env": "prod",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "user_id": "42", "order_id": 9981,
  "reason": "gateway_timeout", "timeout_ms": 3000,
  "stack_trace": "com.shop.PaymentGatewayTimeout: ..."
}
```

### 5.2 Structured arguments and MDC in Java

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static net.logstash.logback.argument.StructuredArguments.value;

public class ChargeService {
    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    public ChargeResult charge(Order order) {
        // kv("order_id", 9981) -> adds top-level JSON field order_id=9981
        // AND substitutes into the message {} for human-readable console logs.
        log.info("charging order {}", kv("order_id", order.id()),
                 kv("amount_cents", order.amountCents()));
        try {
            return gateway.charge(order);
        } catch (GatewayTimeoutException e) {
            // Exception as the LAST arg -> full stack trace captured as stack_trace.
            // Business identifiers added as fields, NOT the raw card/payload (PII!).
            log.error("charge failed", kv("order_id", order.id()),
                      kv("reason", "gateway_timeout"),
                      kv("timeout_ms", gateway.timeoutMs()), e);
            throw e;
        }
    }
}
```

### 5.3 Correlation/trace IDs via a Servlet filter + MDC

```java
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.UUID;

/** Ensures every request has a propagated correlation id present on all log lines. */
public class CorrelationIdFilter extends HttpFilter {
    private static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "request_id";

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String id = req.getHeader(HEADER);
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        // try-with-resources guarantees MDC cleanup even if the handler throws,
        // preventing the id from leaking to the NEXT request on a pooled thread.
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, id)) {
            res.setHeader(HEADER, id);          // echo back for the caller
            chain.doFilter(req, res);
        }
        // MDC.remove(MDC_KEY) happens automatically on close.
    }
}
```

With **OpenTelemetry + Spring Boot**, you usually don't write this yourself: the OTel Java agent (`-javaagent:opentelemetry-javaagent.jar`) auto-injects `trace_id`/`span_id` into MDC. In Spring Boot 3 with Micrometer Tracing, the default log pattern includes `[%X{traceId:-},%X{spanId:-}]`. The point of the example is to show the *mechanism* explicitly.

### 5.4 Propagating MDC across thread pools and async boundaries

The MDC-is-thread-local fact breaks when work hops threads (executors, `@Async`, reactive). You must propagate manually.

```java
import org.slf4j.MDC;
import java.util.Map;
import java.util.concurrent.*;

/** Wraps tasks so the SUBMITTING thread's MDC is restored on the WORKER thread. */
public final class MdcTaskDecorator {
    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap(); // snapshot at submit time
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) MDC.setContextMap(captured); else MDC.clear();
            try {
                task.run();
            } finally {
                // Restore worker's prior context (it's a pooled thread — don't leak!).
                if (previous != null) MDC.setContextMap(previous); else MDC.clear();
            }
        };
    }
}

// Usage:
ExecutorService pool = Executors.newFixedThreadPool(8);
pool.submit(MdcTaskDecorator.wrap(() -> log.info("processing in worker")));
```

For Spring's `@Async`, set a `TaskDecorator` on the `ThreadPoolTaskExecutor` that does the same. For **Project Reactor** (WebFlux), thread-local MDC does not naturally follow the reactive chain; use Reactor's `Context` + Micrometer's `context-propagation` library (`Hooks.enableAutomaticContextPropagation()` in Reactor 3.5+). Flag this as a top reactive-logging pitfall.

### 5.5 Log4j2 high-throughput async JSON (alternative backend)

`log4j2.xml` using async loggers and `JsonTemplateLayout`:

```xml
<Configuration status="WARN">
  <Appenders>
    <Console name="JsonConsole" target="SYSTEM_OUT">
      <!-- ECS layout produces Elastic Common Schema fields out of the box. -->
      <JsonTemplateLayout eventTemplateUri="classpath:EcsLayout.json"/>
    </Console>
  </Appenders>
  <Loggers>
    <!-- All loggers async via the Disruptor; enable with system property
         -Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector -->
    <Root level="info">
      <AppenderRef ref="JsonConsole"/>
    </Root>
  </Loggers>
</Configuration>
```

> **ECS (Elastic Common Schema)?** A standardized field-naming spec from Elastic (`@timestamp`, `log.level`, `service.name`, `trace.id`, `error.stack_trace`, …). Adopting a common schema across services is what makes cross-service dashboards and queries possible. OTel has its own **semantic conventions** playing the same role.

### 5.6 Loki / Promtail pipeline (label-based, low-cardinality)

`promtail.yaml` snippet showing the cardinality discipline:

```yaml
scrape_configs:
  - job_name: kubernetes-pods
    kubernetes_sd_configs: [{ role: pod }]
    pipeline_stages:
      - cri: {}                 # parse the container runtime wrapper
      - json:                   # parse our app's JSON line
          expressions:
            level: level
            service: service
            trace_id: trace_id  # extracted but NOT promoted to a label
            user_id: user_id
      - labels:                 # ONLY low-cardinality fields become Loki labels
          level:
          service:
      # trace_id/user_id remain in the line; query later with: | json | user_id="42"
```

LogQL queries:

```logql
# Count errors per service over 5m (label-indexed, fast):
sum by (service) (count_over_time({level="error"}[5m]))

# Find one user's failed charges (label-narrowed, then content scan):
{service="order-svc", level="error"} | json | user_id = "42" | reason = "gateway_timeout"

# Derive a metric from logs: error rate of order-svc:
sum(rate({service="order-svc", level="error"}[1m]))
```

### 5.7 Elasticsearch query examples (Query DSL + KQL)

```json
// Query DSL: errors for user 42 in the last 15 minutes, sorted newest first.
POST /logs-order-svc-*/_search
{
  "query": {
    "bool": {
      "filter": [
        { "term":  { "level": "ERROR" } },
        { "term":  { "user_id": "42" } },
        { "range": { "@timestamp": { "gte": "now-15m" } } }
      ]
    }
  },
  "sort": [ { "@timestamp": "desc" } ],
  "size": 50
}
```

```json
// Aggregation: count errors by reason (terms) bucketed over time (date_histogram).
POST /logs-order-svc-*/_search
{
  "size": 0,
  "query": { "term": { "level": "ERROR" } },
  "aggs": {
    "over_time": {
      "date_histogram": { "field": "@timestamp", "fixed_interval": "1m" },
      "aggs": { "by_reason": { "terms": { "field": "reason", "size": 10 } } }
    }
  }
}
```

In Kibana, the same as **KQL**: `level: ERROR and user_id: 42` over the time range; then a Lens/visualization for the breakdown.

### 5.8 Drop/redact at the pipeline (Logstash filter + Fluent Bit)

```ruby
# Logstash: drop DEBUG in prod, redact emails, drop a known noisy event.
filter {
  if [level] == "DEBUG" { drop {} }
  mutate {
    gsub => [ "message", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "[REDACTED_EMAIL]" ]
  }
  if [logger_name] == "org.apache.http.wire" { drop {} } # chatty wire logs
}
```

```ini
# Fluent Bit: keep only WARN+ for one noisy namespace to cut volume.
[FILTER]
    Name    grep
    Match   kube.var.log.containers.noisy-ns_*
    Regex   level (WARN|ERROR|FATAL)
```

### 5.9 Sampling high-volume INFO logs in-app

```java
import java.util.concurrent.atomic.AtomicLong;

/** Log only 1 of every N successful requests at INFO; always log failures. */
public final class SampledAccessLogger {
    private static final Logger log = LoggerFactory.getLogger("access");
    private static final long SAMPLE_N = 100;          // 1% sampling
    private final AtomicLong counter = new AtomicLong();

    public void logAccess(String path, int status, long latencyMs, String requestId) {
        boolean sampled = (counter.incrementAndGet() % SAMPLE_N == 0);
        boolean isError = status >= 500;
        if (isError || sampled) {
            log.info("access", kv("path", path), kv("status", status),
                     kv("latency_ms", latencyMs), kv("request_id", requestId),
                     kv("sampled", !isError),       // mark so consumers can scale counts
                     kv("sample_rate", isError ? 1 : (1.0 / SAMPLE_N)));
        }
    }
}
```

> **Why record `sample_rate`?** If you count sampled logs to estimate traffic, you must multiply by the inverse rate to recover true counts. Always emit the rate so downstream analytics are honest. This is **head-based sampling** (decide at emit time). **Tail-based sampling** (decide after seeing the whole trace/request, keeping all errors/slow ones) is richer but requires a buffering processor and is more common for traces than logs.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Use parameterized logging** (`log.info("x={}", x)`), never string concatenation (`"x=" + x`), so formatting is skipped when the level is disabled.
- **Guard expensive arguments** with `isDebugEnabled()` or Log4j2 `Supplier` lambdas — the level check is cheap; building a big string/serializing an object is not.
- **Avoid caller-location data** (`%caller`, `%class`, `%line`, `includeCallerData=true`). Capturing the stack to find the call site can be **10–100× slower** per log call. Off by default for a reason.
- **Go async** for I/O-bound appenders, but understand the tradeoff: async buffers can lose events on crash and can silently *drop* under load (Logback `AsyncAppender` drops INFO/DEBUG/TRACE when the queue is 80% full unless `discardingThreshold=0`). Log4j2 async loggers (Disruptor) are the throughput champions.
- **Bound everything:** queue sizes, stack-trace depth/length, max field sizes. One unbounded exception log or a request that logs a 50 MB payload can saturate the pipeline.
- **Don't log in tight loops.** Aggregate or sample. A `log.debug` inside a per-row loop over a million-row job is a self-inflicted outage.
- **Throughput numbers (order of magnitude, version/HW dependent):** modern frameworks do well over **100k–1M+ events/sec** with async; synchronous file logging with flush-per-line is far slower (tens of thousands/sec) and adds latency to the request path. Always benchmark your config.

### 6.2 Correctness & concurrency

- **MDC cleanup is mandatory.** Forgetting `MDC.clear()`/`remove()` on a pooled thread leaks context into the *next* request → wrong `trace_id` on someone else's logs (a confidentiality and debugging hazard). Use `putCloseable` + try-with-resources.
- **Thread-pool / async propagation** must be explicit (§5.4). This is the #1 silent correctness bug in MDC-based logging.
- **Reactive (WebFlux/Reactor) and virtual threads:** thread-local MDC semantics differ. With **virtual threads (JDK 21+)**, MDC works per-virtual-thread but copying MDC across virtual-thread-spawning boundaries still needs care; with reactive, use context-propagation. Flag your runtime.
- **Time:** always log in **UTC**, ISO-8601 with millisecond (or better) precision and an explicit `Z`/offset. Mixed local times across regions make correlation impossible. Clock skew across hosts is real — don't assume cross-host ordering is exact; use trace IDs + spans for ordering, not timestamps.

### 6.3 Security & privacy (recap + mechanics)

- **Redact at the framework layer** so it's centralized and testable: a custom Logback converter or a marker-driven masking encoder; or mask in DTO `toString()`.
- **Never log credentials/PII** (§2.4). Maintain a **deny-list of fields** and ideally an **allow-list for sensitive objects** (only fields explicitly marked loggable are emitted).
- **Defense in depth:** also scrub at the pipeline (Logstash `gsub`, Fluent Bit Lua/`modify`) for logs from third-party libs you don't control.
- **Access control & audit on the log store itself** — logs often contain more sensitive aggregate insight than any single DB row. Restrict who can query prod logs; log the access.
- **Log injection / forging:** an attacker who controls input that you log unescaped can inject fake newlines (CRLF) to forge log lines, or break parsers. Structured JSON logging with proper escaping defends against this; never log raw, unescaped attacker input into a text format that a parser will re-tokenize. (Recall **Log4Shell / CVE-2021-44228**: Log4j2's JNDI lookup feature executed attacker-controlled strings in log messages — a reminder that the logging layer is an attack surface; keep frameworks patched and disable message lookups.)
- **Tamper-evidence for audit logs:** security/audit logs may need write-once storage, hashing/signing, or shipping to a separate, restricted store (SIEM).

### 6.4 Observability *of* logging

- **Monitor the pipeline:** agent buffer fill %, drop counters, ingest lag, store ingest rate vs. capacity, `_grokparsefailure` rate, dead-letter queue size, disk watermarks. Logging that silently drops is worse than none.
- **Budget alerts on volume** — a sudden 10× ingest spike usually means a log-loop bug, a retry storm, or an attack; alert on it before it bankrupts you.
- **Track schema drift:** alert on new/changed field names if you depend on a schema.

### 6.5 Cost control (the perennial battle)

Levers, roughly in order of bang-for-buck:

1. **Levels & what-you-log discipline.** The cheapest log is the one you never write. Audit chatty INFO/DEBUG; demote or delete.
2. **Sampling** of high-volume, low-value successful events (§5.9). Keep 100% of errors.
3. **Retention tiers.** Hot (fast, searchable, days) → warm → cold/frozen (object storage, slow, weeks–months) → delete. Most queries hit the last few days; don't pay SSD/index prices for 90-day-old logs. Configure via ES **ILM** or store equivalents.
4. **Index/parse less.** In ES, set `index:false` for display-only fields, `doc_values:false` for non-aggregated fields, disable dynamic mapping. In Loki, keep labels low-cardinality.
5. **Drop noise at the edge** (agent/processor) so you don't pay to ingest it.
6. **Pick the right backend** for the data: cheap, label-driven app logs → Loki; full-text/SIEM → ES/Splunk; route, don't dump everything into the most expensive store.
7. **Compression** in transit and at rest (gzip/zstd).

> **Rule of thumb on economics:** ingest + indexing usually dominates cost in ES/Splunk; storage dominates in Loki. Optimize the dominant term for *your* backend.

### 6.6 Testability

- **Assert on structured fields, not message strings.** Use a test appender (Logback `ListAppender`) or libraries like `logcaptor` to capture events and assert `event.getMDCPropertyMap().get("trace_id")` or structured args. This keeps tests robust to wording changes.
- **Contract-test your log schema** if dashboards/alerts depend on field names — treat the schema like an API.
- **Test redaction:** unit test that a `toString()`/encoder masks card numbers, with a representative sample of secrets.

### 6.7 Anti-patterns (avoid)

- Logging then re-throwing the same exception at every layer → the same error logged 5×, exploding volume and confusing on-call ("log *or* handle, not both, and log once at the boundary").
- `printStackTrace()` / `System.out.println` instead of the logger (no level, no structure, no routing).
- Concatenating in the message and *also* passing the exception as a string (`log.error("err: " + e)`) → loses the stack trace; pass `e` as the last arg.
- High-cardinality values as **metric labels** or **Loki labels** (cardinality explosion).
- Logging large payloads/PII; logging inside hot loops.
- Relying on log *timestamps* for cross-host causal ordering.
- One giant `text`-indexed message field in ES and querying it with leading-wildcard regex (slow, expensive) — extract fields instead.
- Forgetting `MDC.clear()`.
- Treating logs as your metrics system (deriving every dashboard from log counts) when a cheap counter would do.

---

## 7. Advanced topics & deep internals

### 7.1 Canonical log lines / "wide events"

A powerful pattern (popularized by Stripe, Honeycomb): instead of scattering many small log lines per request, accumulate context throughout the request and emit **one wide, structured event** at the end containing everything — method, path, status, latency, user/tenant, feature flags, downstream call timings, error info, trace ID. Benefits: one event per request (predictable volume), trivially queryable, doubles as analytics. This blurs logs↔events and aligns with **high-cardinality observability** (Honeycomb's thesis: store wide events and slice by any high-cardinality dimension at query time, rather than pre-aggregating into low-cardinality metrics).

### 7.2 Dynamic log levels without redeploy

Production debugging often needs DEBUG on *one* service/class *temporarily*.
- **Spring Boot Actuator** exposes `/actuator/loggers/{name}` — `POST {"configuredLevel":"DEBUG"}` flips a logger at runtime. Revert after.
- Logback `scan="true"` reloads config from a mounted ConfigMap on change.
- Log4j2 supports programmatic level changes and config reload.
- Advanced: **conditional/contextual logging** — raise level only for requests matching a header or a specific `user_id`/`tenant` (via a TurboFilter keyed on MDC), so you get DEBUG for the one customer reproducing a bug without 1000× volume from everyone.

### 7.3 Trace ↔ log correlation (the unification)

With OpenTelemetry, inject `trace_id`/`span_id` into every log (auto via the Java agent). Then in Grafana/Kibana you can pivot from a slow trace directly to its logs ("trace to logs" linking), or from an error log to its trace. This is the practical payoff of the "three pillars": same `trace_id` joins them. **Exemplars** (a metric data point annotated with a trace ID) similarly let you jump from a spike on a latency histogram to a representative trace, then to its logs.

### 7.4 Elasticsearch tuning knobs (deep)

| Knob | Effect | Note |
|---|---|---|
| `refresh_interval` | How often new docs become searchable. | Default `1s`; raise to `30s` for log-ingest-heavy indices → big indexing throughput win (fewer segments). |
| `number_of_shards` | Parallelism vs overhead. | Target ~**10–50 GB/shard**; too many small shards = cluster bloat ("oversharding"). |
| `number_of_replicas` | Redundancy/read scale. | Often set 0 during bulk load, then 1. |
| `index.codec: best_compression` | Disk vs CPU. | Zstd-like; saves disk on logs at some CPU. |
| `translog.durability` | `request` (fsync each op, safe) vs `async` (faster, small loss window). | — |
| Bulk indexing | Use `_bulk` with sized batches (e.g. 5–15 MB). | Single-doc indexing wastes throughput. |
| Dynamic mapping | `dynamic: false`/`strict` to prevent mapping explosion. | Each new field = mapping cost; attacker can force "mapping explosion" with arbitrary field names. |
| Doc values / `index:false` | Disable for fields you don't aggregate/search. | Saves disk + CPU. |

### 7.5 Loki tuning (deep)

- **Label set is the schema.** Aim for a *handful* of labels with bounded values (`namespace`, `app`, `level`, maybe `cluster`). Never `pod`, `instance_id`, `trace_id`, `user_id` as labels.
- **Chunk sizing** (`chunk_target_size`, idle/age cutoffs) balances object-store object count vs query parallelism.
- **`split_queries_by_interval`** and parallelism shard big time-range queries across queriers — a content scan over 7 days is fine if parallelized.
- **Index period & TSDB index** (Loki's newer index) improve label lookup.
- **Per-tenant limits** (`ingestion_rate_mb`, `max_streams_per_user`, `max_label_names_per_series`) protect against stream explosion; tune carefully.

### 7.6 Multiline & stack-trace assembly

A Java stack trace is many physical lines but one logical event. If you log JSON (with the trace inside a `stack_trace` field), this problem disappears at the source — *strong argument for JSON-at-source*. If you must parse text logs, the agent needs a **multiline** rule (e.g., "a new event starts with a timestamp; continuation lines start with whitespace or `at `/`Caused by`"). Misconfigured multiline is a classic cause of split/garbled stack traces in the store.

### 7.7 Delivery guarantees

Most logging pipelines are **at-most-once** or **at-least-once**, almost never exactly-once:
- Async in-app buffers → can lose on crash (at-most-once for the tail).
- Agent file-tailing with persisted offsets → at-least-once (may resend after restart → duplicates; dedupe on a unique event ID if it matters).
- Kafka-buffered pipelines → durable, replayable, tunable. For audit logs needing guarantees, prefer synchronous + durable (Kafka, or write to DB) over fire-and-forget logging.

### 7.8 Container & k8s specifics

- Apps should log to **stdout/stderr** (12-factor); let the platform collect. Avoid sidecar log-file readers unless a legacy app insists on files.
- The runtime wraps each line (CRI log format: `2026-... stdout F {json}`); agents must strip that wrapper (`cri` parser) before JSON-parsing your payload.
- **Log rotation by kubelet** (default ~10Mi/file, limited history) means the node agent must keep up or lose data on rotation — monitor agent lag.
- Add k8s metadata (namespace, pod, labels, node) at the agent, not the app.

### 7.9 OTel logs (emerging)

OpenTelemetry now defines a **logs data model** and **OTLP** transport for logs, letting the OTel Collector be the single pipeline for logs+metrics+traces with shared resource attributes and correlation. Maturity is improving; many shops still ship logs via Fluent Bit/Vector and reserve OTLP for traces/metrics. Flag as evolving.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Structured vs unstructured

| | Structured (JSON) | Unstructured (text) |
|---|---|---|
| Query | Field-exact, fast | Regex, fragile |
| Human readability (raw) | Lower | Higher |
| Index/parse cost | Lower (no Grok) | Higher (Grok CPU, parse fails) |
| Schema discipline needed | Yes | No |
| **Verdict** | **Default for services** | OK only for human-only local/dev tooling |

Use a JSON encoder in prod; optionally a pretty pattern layout in local dev (Spring Boot profiles make this trivial).

### 8.2 Backend selection

| Need | Choose | Why |
|---|---|---|
| Cheap, k8s-label-driven app logs, already on Grafana | **Loki** | Lowest ingest/storage cost; LogQL; Grafana-native. |
| Rich full-text search, complex aggregations, APM, security search | **Elasticsearch/OpenSearch** | Inverted index on all fields; mature aggregations. |
| Enterprise SIEM, compliance, deepest analytics, budget available | **Splunk** | SPL power, app ecosystem, security features. |
| Low-ops managed, tight cloud integration | **CloudWatch / GCP Logging / Datadog / Azure Monitor** | No infra to run; pay per GB. |
| One pipeline for all three signals | **OTel Collector** + appropriate backends | Vendor-neutral, future-proof. |

**Use Loki when:** cost matters, you query by labels/recent windows, you're in Grafana. **Avoid Loki when:** you need fast arbitrary full-text search across huge volumes/long ranges, or rich relevance ranking → ES.
**Use ES when:** you need real search/analytics on log content. **Avoid ES when:** budget/ops are tight and your access pattern is "filter by labels, recent time" → Loki.
**Use Splunk when:** enterprise security/compliance and you can pay. **Avoid Splunk when:** cost-sensitive, high volume → it gets very expensive per GB.

### 8.3 Logs vs metrics vs traces (when to use which)

- **Need the exact detail of one occurrence / forensics?** → Logs (or wide events).
- **Need trends, SLOs, alerting on aggregate behavior cheaply?** → Metrics.
- **Need to know where latency/failure occurred across services?** → Traces.
- **High-cardinality dimension matters (per-user, per-order)?** → Logs/events or high-cardinality observability tools, *not* metric labels.

### 8.4 Indexing-time vs query-time schema

| | Schema-on-write (ES) | Schema-on-read (Splunk, Loki content) |
|---|---|---|
| Write cost | Higher (index everything) | Lower |
| Query speed (indexed fields) | Faster | Slower (scan/extract) |
| Flexibility on old data | Reindex to change | Extract new fields anytime |
| Storage | Larger (indexes) | Smaller (raw + light index) |

### 8.5 Sync vs async logging

| | Sync | Async (queue/Disruptor) |
|---|---|---|
| Request latency impact | Adds I/O latency to request | Decoupled |
| Throughput | Lower | Much higher |
| Loss on crash | None (flushed) | Possible (buffered tail) |
| Loss under load | Backpressure/block | Can drop (configurable) |
| **Use** | Audit/critical, low volume | Default for high-volume app logs |

---

## 9. Failure modes & debugging

### 9.1 "I can't find the logs / there's a gap"

- **Likely causes:** agent crashed/lagging; buffer overflowed and dropped; mapping conflict caused store to reject docs; index rolled and query time range excludes it; retention deleted them; multiline misparse merged/split events.
- **Diagnose:** check agent health/metrics (Fluent Bit `/api/v1/metrics`, Filebeat `_monitoring`), agent buffer/drop counters; ES `_cat/indices?v` and `_cat/recovery`; ES rejected-docs (look for `mapper_parsing_exception` / `illegal_argument_exception` in agent or ES logs); check ILM state. On the node, `kubectl logs` the agent DaemonSet; verify the runtime log files exist under `/var/log/pods`.

### 9.2 Mapping explosion / field-limit hit (Elasticsearch)

- **Symptom:** indexing errors `Limit of total fields [1000] has been exceeded`, or cluster instability.
- **Cause:** dynamic mapping turning every arbitrary JSON key (often from logging an unbounded map or attacker input) into a field.
- **Fix:** `dynamic: false`/`strict`, `index.mapping.total_fields.limit` raised cautiously, drop/flatten high-key-count objects at the pipeline, never log unbounded maps as top-level fields.

### 9.3 Disk full / cluster read-only (Elasticsearch)

- **Symptom:** writes fail; indices flip to read-only (`index.blocks.read_only_allow_delete`).
- **Cause:** **flood-stage watermark (default 95%)** disk usage triggers read-only to protect the node.
- **Fix:** free disk / add capacity, then clear the block via the settings API; long-term, fix retention/ILM and shard sizing. Watermarks: low 85%, high 90%, flood 95% (defaults; configurable).

### 9.4 Cardinality explosion

- **Loki:** "too many streams" / slow queries / ingester OOM because a high-cardinality field (e.g., `pod`, `request_id`) was made a label. **Fix:** remove it from `labels`, keep it in the line.
- **Metrics-derived-from-logs / Prometheus:** a high-cardinality label blows up time series → Prometheus OOM. **Fix:** drop the label; put detail in logs/traces.

### 9.5 Cross-request MDC contamination

- **Symptom:** logs show the *wrong* `trace_id`/`user_id` intermittently, especially under load or in async/pooled code.
- **Cause:** missing `MDC.clear()`/leak across pooled threads, or MDC not propagated (so it falls back to a stale value), or reactive context not propagated.
- **Diagnose/fix:** audit filters for try-with-resources `putCloseable`; ensure executors use an MDC-propagating decorator; for WebFlux enable context-propagation. Add a test that submits to a pool twice and asserts no leak.

### 9.6 Log loop / volume spike outage

- **Symptom:** ingest cost or pipeline saturates; store falls behind; alerts on volume.
- **Causes:** a retry storm logging each attempt, an error logged at every stack layer, DEBUG accidentally enabled in prod, an exception in a tight loop.
- **Fix:** find the dominant `logger_name`/`message` via a top-N aggregation (`terms` on `logger_name`), apply rate-limiting filter (Logback `EvaluatorFilter` or a deduplicating appender), demote the level, fix the code. Have a "volume circuit breaker" (drop low-value logs above a rate) as a safety net.

### 9.7 Grok parse failures

- **Symptom:** `_grokparsefailure` tag; fields missing; raw message dumped.
- **Cause:** message format changed; regex brittle. **Fix:** prefer JSON-at-source to eliminate Grok entirely; if stuck with text, monitor parse-failure rate and treat format as a contract.

### 9.8 Real-world incident patterns (illustrative)

- **Secrets in logs:** tokens/keys logged then indexed in a SaaS log vendor and visible to support/analytics; remediation = rotate all exposed secrets, purge logs, add redaction + scanners. (Numerous public postmortems describe this class.)
- **Log4Shell (CVE-2021-44228, Dec 2021):** Log4j2 evaluated JNDI lookups inside log messages, so logging an attacker-supplied string (`${jndi:ldap://...}`) led to remote code execution — an industry-wide emergency patch. Lesson: the logging layer is security-critical; disable lookups, patch promptly, treat logged input as untrusted.
- **Logging-induced latency/outage:** synchronous flush-per-line logging on the request path, or a DEBUG-in-prod misconfig, throttling a service under load; fixed by async + level discipline. (Common internal-postmortem pattern across many orgs.)

> Where I've labeled something "illustrative/common pattern," it reflects widely reported categories rather than a single attributed public postmortem; Log4Shell and the ES disk-watermark defaults are specific and verifiable.

---

## 10. Interview drill

**Q1. Why is structured (JSON) logging preferred over string logs?** 
*Model:* Fields are machine-parseable → exact, fast queries and aggregations without fragile regex; field names form a stable contract; correlation IDs become first-class join keys; lower ingest CPU (no Grok); easier PII governance. Cost: slightly more verbose, marginal CPU, requires field-name discipline. 
*Probe:* (a) *What's the downside at the store?* Potential mapping explosion/cost if you index every dynamic field — control dynamic mapping, set `index:false` where appropriate. (b) *How do you keep field names consistent across teams?* Adopt a shared schema (ECS or OTel semantic conventions) and contract-test it. (c) *Is JSON always best on disk?* It's verbose; compression and a compact schema mitigate; for ultra-high-volume some use binary/columnar, but JSON's interoperability usually wins.

**Q2. Explain MDC and the trace/correlation-ID lifecycle.** 
*Model:* MDC is a thread-local key/value map; set IDs at request entry (`MDC.put`/`putCloseable`), emit via `%X{key}`, clear at exit. Correlation/trace IDs are propagated via headers (`X-Request-Id`, W3C `traceparent`) so all services' logs for one request share a key. 
*Probe:* (a) *What breaks with thread pools?* Thread-local doesn't follow the work; you leak/lose context — wrap tasks to copy MDC and restore. (b) *And in reactive/WebFlux?* Thread-locals don't follow the reactive chain; use Reactor Context + context-propagation. (c) *Why try-with-resources?* Guarantees cleanup on exceptions, preventing cross-request contamination on pooled threads.

**Q3. Compare ELK/Elasticsearch, Loki, and Splunk.** 
*Model:* ES indexes all fields (inverted index) → powerful search/aggregation but expensive ingest/storage. Loki indexes only labels and brute-force-scans cheap object-stored content → very cheap, low-cardinality-label-driven, can be slower for broad content search. Splunk is schema-on-read, extremely powerful (SPL, SIEM) but costly per GB. 
*Probe:* (a) *When does Loki beat ES?* Cost-sensitive, label/recent-time access patterns. (b) *Loki's cardinality rule?* Labels must be low-cardinality; high-cardinality goes in the line. (c) *Splunk's schema-on-read benefit?* Extract new fields from old data without reindex.

**Q4. What should you never log, and how do you enforce it?** 
*Model:* Secrets (passwords, tokens, keys), PII (emails, government IDs, full DOB), card data/CVV (PCI), PHI (HIPAA). Enforce: redact at framework layer, allow-list loggable fields on sensitive objects, pipeline scrubbing as defense-in-depth, CI/log scanners, access control on the store. 
*Probe:* (a) *Why are logs riskier than the DB?* Fanned out, retained, broadly readable, indexed externally. (b) *GDPR implication?* Personal data in logs is subject to retention/erasure → minimize and tier. (c) *How test redaction?* Unit-test encoders/`toString()` with sample secrets.

**Q5 (senior-signal). Your log bill 5×'d this quarter. How do you bring it down without losing debuggability?** 
*Model:* Quantify first — top-N by `logger_name`/`message`/service to find the volume drivers. Then: demote/delete chatty INFO/DEBUG; sample high-volume successful events (keep 100% of errors, record `sample_rate`); implement retention tiers (hot→cold→delete); reduce indexed/aggregated fields (ES `index:false`/`doc_values:false`); drop noise at the edge; route low-value logs to a cheaper backend (Loki) and keep ES/Splunk for what needs search. Add volume alerts and a drop-circuit-breaker to prevent recurrence. Frame as preserving error/correlation fidelity while cutting low-value success-path volume. 
*Probe:* (a) *Which lever first?* What-you-log discipline + retention — highest ROI, lowest risk. (b) *Sampling risk?* Counting sampled logs without scaling → use `sample_rate`; never sample errors. (c) *Backend split risk?* Two query UIs/correlation seams — mitigate with shared `trace_id` and trace-to-logs linking.

**Q6 (senior-signal). Logs say success but users report failures. Diagnose.** 
*Model:* Distrust the logs: check for dropped logs (async discard, buffer overflow, agent lag), mapping rejections (docs silently dropped by store), level filtering hiding errors, sampling dropping the failing requests, or MDC contamination making you read the wrong request's lines. Correlate via trace ID end-to-end; check metrics (error counters) independently of logs; inspect agent drop counters and ES rejected-docs. 
*Probe:* (a) *How would async cause this?* `discardingThreshold` drops INFO under load; set to 0 or raise queue. (b) *Store-side silent loss?* Mapping conflicts → rejected docs; check ingest errors. (c) *Why trust metrics here?* They're a cheaper, independent signal; divergence localizes the fault.

**Q7. Logs vs metrics vs traces — when each, and why not just one?** 
*Model:* Logs = per-event detail/forensics; metrics = cheap aggregate trends/alerting; traces = cross-service latency/causality. You can't replace metrics with logs at scale (cost) or replace logs with metrics (no per-event detail, can't carry high cardinality). Use all three, unified by `trace_id`. 
*Probe:* (a) *Why not put `user_id` in metrics?* Cardinality explosion → series blowup/OOM. (b) *Derive metrics from logs?* Possible but lossy/costly; purpose-built counters are cheaper. (c) *How do they connect?* OTel shared `trace_id`/resource attrs; exemplars; trace-to-logs.

**Q8. Walk through what happens internally when `log.info(...)` executes through to a searchable document.** 
*Model:* Level guard → `LoggingEvent` (timestamp, level, logger, MDC snapshot, message+args) → filters → appenders → encoder→JSON → (async ring buffer) → stdout → container runtime file → node agent tail/parse/enrich/buffer → (Kafka?) → processor → store ingest pipeline → indexed into segments (ES) / chunked to object store (Loki) → hot tier → query. 
*Probe:* (a) *Where can it be lost?* Async buffer on crash, agent drop on overflow, store rejection, retention. (b) *ES near-real-time?* `refresh_interval` (1s default) controls searchability lag. (c) *Why async?* Decouple request latency from I/O; tradeoff is loss window.

**Q9 (senior-signal). Design logging for a 200-microservice platform on Kubernetes.** 
*Model:* Standard: SLF4J+Logback emitting ECS/OTel-conventioned JSON to stdout; OTel agent injects `trace_id`/`span_id` into MDC. Node-level Fluent Bit/Vector DaemonSet parses CRI wrapper + JSON, enriches with k8s metadata, buffers with disk fallback. Optionally Kafka as durable buffer for spikes. Route: app logs → Loki (cheap, label by namespace/app/level), security/audit → ES or SIEM. Central schema + governance (deny/allow PII lists, redaction). Retention tiers + ILM. Dynamic log levels via Actuator for targeted debugging. Pipeline observability + volume alerts. Trace↔log↔metric correlation via shared IDs in Grafana. 
*Probe:* (a) *Why Loki for app logs?* Cost at this scale; label-driven queries fit k8s. (b) *Handling a noisy team?* Per-tenant ingest limits + chargeback + sampling. (c) *Audit logs differently?* Durable (Kafka/DB), restricted store, tamper-evidence — not fire-and-forget.

**Q10. What's the difference between `text` and `keyword` in Elasticsearch and why does it matter for logs?** 
*Model:* `text` is analyzed/tokenized for full-text search (can't sort/aggregate efficiently, costs index space); `keyword` is stored verbatim for exact match, sorting, aggregation. For log fields like `level`, `service`, `status` you want `keyword`; for free-text `message` you may want `text` (or both via multi-field). Misusing them wastes storage and breaks aggregations. 
*Probe:* (a) *What's a multi-field?* Index the same value as both `text` and `keyword` (`message` + `message.keyword`). (b) *Aggregating on `text`?* Requires fielddata (memory-heavy) — avoid; use `keyword`. (c) *Cost angle?* Disable `index`/`doc_values` on fields you only display.

**Q11. How do correlation IDs get propagated across HTTP and async boundaries, and what standard governs it?** 
*Model:* Over HTTP via headers — legacy `X-Request-Id`/`X-Correlation-Id`, modern **W3C Trace Context** `traceparent`/`tracestate`. Across threads/queues you must propagate manually (MDC copy, message headers on Kafka). OpenTelemetry context propagation automates this. 
*Probe:* (a) *traceparent format?* `version-traceid-spanid-flags`. (b) *Messaging propagation?* Inject trace context into message headers; extract on consume. (c) *What if a service drops the header?* Trace breaks into disconnected fragments — enforce propagation in shared middleware/agent.

**Q12. Explain head vs tail sampling for logs/traces and the tradeoff.** 
*Model:* Head-based: decide to keep/drop at emit time (cheap, simple, but may drop the interesting events). Tail-based: buffer the whole request/trace, then decide (keep all errors/slow ones), richer but needs a stateful processor and memory. For logs, head sampling of success-path INFO is common; always keep errors; record sample rate. 
*Probe:* (a) *Why tail for traces?* You can keep exactly the slow/failed traces. (b) *Head sampling pitfall?* Counting requires scaling by 1/rate. (c) *Where implemented?* OTel Collector tail-sampling processor; app-level for head.

---

## 11. Glossary

- **Aggregation (log):** Centralizing logs from many sources into one searchable system.
- **Aggregation (query):** Computing summaries (count, terms, percentiles, histograms) over many records.
- **Analyzer (ES):** Tokenizer + filters that break a `text` field into searchable terms.
- **Appender (Logback) / Layout (Log4j2):** A log destination + its byte-formatting.
- **At-least/at-most/exactly-once:** Delivery guarantees; logs are usually at-least- or at-most-once.
- **Backpressure:** A slow consumer signaling upstream to slow down (or buffer/drop) to avoid overload.
- **Cardinality:** Number of distinct values a field/label can take; high cardinality is costly for metrics/labels, fine for log fields.
- **CNCF:** Cloud Native Computing Foundation; hosts OTel, Fluentd, Prometheus, etc.
- **Chunk (Loki):** Compressed block of log lines for a stream, stored in object storage.
- **Correlation/Request ID:** Unique ID for one request, propagated across services for log correlation.
- **CRI:** Container Runtime Interface; its log file format wraps each line with time/stream.
- **DaemonSet:** Kubernetes object running one pod per node (used for node log agents).
- **Doc values (ES):** Columnar on-disk structure enabling sorting/aggregation.
- **ECS (Elastic Common Schema):** Standard log field-naming spec.
- **ELK / Elastic Stack:** Elasticsearch + Logstash + Kibana (+ Beats).
- **Event (semantic):** A structured business/system fact; "wide event" = one rich structured log per request.
- **Exemplar:** A metric data point tagged with a trace ID to jump from metric to trace.
- **Fields (structured):** Named key/value pairs in a log record.
- **File descriptor / stdout / stderr:** Kernel handles for I/O streams; fd 1/2 are standard output/error.
- **Filebeat / Fluent Bit / Fluentd / Vector / Promtail / Alloy:** Log collectors/shippers.
- **fsync / flush:** Force buffered data to durable storage.
- **GDPR / PCI-DSS / HIPAA:** Regulations governing personal data, card data, and health data respectively.
- **Grok:** Logstash's named-regex parser for turning text into fields.
- **ILM (Index Lifecycle Management):** ES feature to roll over and tier/delete indices.
- **Index (ES):** Named collection of documents; physically a set of shards.
- **Inverted index:** Term → list-of-documents map enabling fast search (Lucene/ES).
- **JSON:** Text key/value format; default for structured logs.
- **KQL / Query DSL / LogQL / SPL:** Query languages for Kibana, ES, Loki, Splunk.
- **Level (log):** Severity tag (TRACE…FATAL) and runtime filter.
- **LMAX Disruptor:** Lock-free ring buffer powering Log4j2 async loggers.
- **Log4Shell (CVE-2021-44228):** Log4j2 RCE via JNDI lookups in log messages.
- **Loki:** Grafana's label-indexed, content-scanning, object-storage log store.
- **Lucene:** Java search library underlying Elasticsearch.
- **Mapping (ES):** Per-field schema (types like `text`, `keyword`, numeric, date).
- **Marker (SLF4J):** Tag attached to log events for routing/filtering.
- **MDC (Mapped Diagnostic Context):** Thread-local key/value map injected into every log line.
- **Metric:** Numeric aggregate measurement over time with low-cardinality labels.
- **Multiline:** Agent feature to stitch multi-line events (e.g., stack traces) into one record.
- **Near-real-time:** ES search lag (~1s) due to `refresh_interval`.
- **Object storage (S3/GCS/Blob):** Cheap, durable, unindexed blob store.
- **OpenSearch:** Apache-2.0 fork of Elasticsearch/Kibana.
- **OpenTelemetry (OTel) / OTLP:** Standard SDK/protocol for logs+metrics+traces.
- **PII / PHI / PAN / CVV:** Personally identifiable info / protected health info / card primary account number / card verification value.
- **Pillars of observability:** Logs, metrics, traces.
- **Refresh interval (ES):** How often new docs become searchable.
- **Retention tier (hot/warm/cold/frozen):** Storage classes by age/speed/cost.
- **Sampling (head/tail):** Keeping a subset of events to cut volume; tail keeps interesting ones.
- **Schema-on-read / -on-write:** Extract fields at query time (Splunk/Loki content) vs index time (ES).
- **Segment (Lucene):** Immutable index file; merged in the background.
- **Shard / replica (ES):** Horizontal partition of an index / its copy.
- **SIEM:** Security Information and Event Management (security-focused log analytics).
- **SLF4J:** Java logging facade API.
- **Span / trace / trace ID:** Unit of work / whole cross-service request / its identifier.
- **Splunk:** Enterprise schema-on-read log/SIEM platform; SPL query language.
- **Structured logging:** Logging as named fields (JSON) rather than prose.
- **Thread-local:** A variable with a separate value per thread.
- **Translog (ES):** Append-only durability log before commit.
- **TurboFilter (Logback):** Pre-event global filter for cross-cutting decisions.
- **Twelve-factor app:** Cloud-app methodology; logs as stdout event streams.
- **W3C Trace Context / `traceparent`:** Standard HTTP header for trace propagation.
- **Watermark (ES disk):** Disk thresholds (85/90/95%) controlling allocation and read-only.
- **Wide event / canonical log line:** One rich structured event per request.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **Structure beats strings:** emit JSON with stable fields; query by field, not regex. Adopt a shared schema (ECS/OTel).
- **Levels:** TRACE<DEBUG<INFO<WARN<ERROR<(FATAL). Prod usually INFO; flip DEBUG dynamically (Actuator `/loggers`).
- **Never log:** passwords, tokens, keys, PII, full PAN, **CVV (never)**, PHI. Redact at framework + pipeline; access-control the store.
- **Correlation:** mint request/trace ID at edge, propagate via `traceparent`/`X-Request-Id`, put in **MDC** (`putCloseable` + try-with-resources), emit `%X{trace_id}`. Propagate across pools/reactive explicitly. Always `clear()`.
- **In-process path:** level guard → event(+MDC snapshot) → filters → appender → JSON encoder → async ring buffer → stdout.
- **Off-box path:** runtime file → node agent (Fluent Bit/Vector) parse+enrich+buffer → (Kafka) → processor → store → tiers → delete.
- **Backends:** ES = index-everything (powerful, pricey); Loki = labels-only + scan (cheap, low-cardinality labels!); Splunk = schema-on-read (powerful, costly/GB).
- **ES numbers:** `refresh_interval` 1s; shard ~10–50 GB; disk watermarks 85/90/95%; field-limit 1000 default.
- **Cost levers (ROI order):** log less → sample success (keep errors, record `sample_rate`) → retention tiers → index/parse less → drop at edge → right backend → compress.
- **Async tradeoff:** higher throughput, possible loss on crash/overflow; Logback `AsyncAppender` discards INFO at ~80% queue unless `discardingThreshold=0`.
- **Cardinality rule:** high-cardinality (user_id, trace_id) → in the **log line / trace**, never as **metric/Loki labels**.
- **Pillars:** logs=detail, metrics=trends, traces=where; unify by `trace_id`.
- **Top anti-patterns:** log+rethrow at every layer; `printStackTrace`; logging PII/secrets; logging in tight loops; forgetting `MDC.clear()`; high-cardinality labels; leading-wildcard regex on a giant `text` field.
- **Top failure modes:** dropped logs (async/agent/store), mapping explosion, disk-full read-only, cardinality blowup, MDC contamination, log-volume spike, Grok failures.

### Self-test (no answers)

1. Trace a single `log.error(...)` call from the Java call site all the way to a searchable record in Loki *and* in Elasticsearch, naming where data could be silently lost at each hop.
2. Your service runs on a thread pool and in WebFlux; design MDC/trace-ID propagation that works in both and prove it can't contaminate the next request.
3. You must cut log spend 60% while keeping 100% error fidelity and full trace correlation. Lay out the exact levers in priority order and the risks of each.
4. Why would putting `user_id` as a Loki label or a Prometheus metric label be a serious mistake, and where should `user_id` live instead? Explain the underlying data-structure reason.
5. Design the PII/secret-redaction strategy for a 200-service platform: where do you enforce it, how do you test it, and how do you handle logs from third-party libraries you don't control?
6. Compare schema-on-write (ES) vs schema-on-read (Splunk/Loki content) for a team that frequently needs to extract *new* fields from *old* logs during incidents. Which do you pick and why?
7. An on-call engineer says "the logs show everything succeeded" but customers report errors. List every mechanism by which the logs could be lying, and how you'd confirm each with concrete tools/commands.

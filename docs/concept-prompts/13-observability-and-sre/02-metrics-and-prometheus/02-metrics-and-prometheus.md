# Metrics & Prometheus

> An exhaustive engineering-handbook chapter for senior backend developers (primarily Java/JVM) who want to master metrics and Prometheus from first principles to deep internals — well enough to design with it, run it in production, debug it under fire, and answer any interview question.

---

## 1. Overview & where it fits

### 1.1 What "metrics" are

A **metric** is a numeric measurement of some property of a system, sampled over time. "Number of HTTP requests served," "bytes of heap used," "current number of database connections in use," "latency of the last request" — each is a metric. When you record a metric repeatedly with timestamps, you get a **time series**: a stream of `(timestamp, value)` pairs.

Metrics are one of the **three pillars of observability**, the others being:

- **Logs** — discrete, timestamped, usually textual records of *events* ("user 42 logged in at 12:03:01.221"). High detail, high volume, expensive to aggregate.
- **Traces** — records of a single request's journey across services (a *distributed trace* with *spans* for each hop). Great for understanding causality and latency breakdown of one request.
- **Metrics** — aggregated numbers sampled at intervals. Cheap to store, cheap to query over long windows, ideal for dashboards and alerting, but they *aggregate away* per-event detail.

The mental contrast: **logs and traces are about individual events; metrics are about aggregate behavior over time.** You alert on metrics ("p99 latency > 500 ms for 5 minutes"), then drill into traces/logs to find the offending requests.

> **Observability** — the property of a system that lets you understand its internal state from its external outputs (metrics, logs, traces) without shipping new code. **SRE (Site Reliability Engineering)** — Google's discipline of applying software-engineering practices to operations; it leans heavily on metrics-based **SLIs/SLOs** (defined later) and alerting.

### 1.2 The problem Prometheus solves

Before Prometheus, monitoring was dominated by tools like **Nagios** (check-based: a central server runs scripts that return OK/WARN/CRIT) and **Graphite/StatsD** (push-based numeric time series). These had real limitations: weak multi-dimensional data models (you couldn't easily slice "errors by endpoint by region by version"), clumsy alerting, and operational fragility at scale.

**Prometheus** (created at SoundCloud in 2012, open-sourced 2015, the second project to graduate from the **CNCF** — the Cloud Native Computing Foundation, the vendor-neutral home of Kubernetes, Prometheus, Envoy, etc.) introduced a coherent package:

1. A **multi-dimensional data model**: every time series is identified by a metric name **plus a set of key/value labels**. This makes slicing/dicing trivial.
2. A **pull-based scrape model**: Prometheus reaches out to targets over HTTP and reads their current metrics. (Contrast with push, where apps send metrics to a collector.)
3. A purpose-built query language, **PromQL**, designed for time-series math (rates, quantiles, aggregations).
4. A built-in **local TSDB** (time-series database) optimized for this workload.
5. **Service discovery** integration (Kubernetes, Consul, EC2, file-based) so targets are found dynamically.
6. A separate **Alertmanager** for deduplicating, grouping, silencing, and routing alerts.

### 1.3 When you reach for it

- You need **operational monitoring and alerting** for services and infrastructure.
- You want **dashboards** (typically via **Grafana**) showing rates, error ratios, saturation, and latency percentiles.
- You're running **cloud-native / Kubernetes** workloads where targets come and go and service discovery matters.
- You want a **free, self-hostable, well-understood** standard with a huge exporter ecosystem.

When you would *not* reach for raw Prometheus alone: ultra-high-cardinality event analytics (use logs/columnar stores), per-request billing/audit (use logs/events), or extremely long retention at scale without extra tooling (add Thanos/Cortex/Mimir, §11).

### 1.4 One-paragraph mental model

> Prometheus periodically (every ~15s) makes an HTTP `GET /metrics` to each target it discovers, parses a text exposition of `metric_name{label="value"} 123.4` lines, and appends each value to the matching time series in a local TSDB keyed by `metric_name + sorted_labels`. You query that TSDB with PromQL — most usefully `rate(counter[5m])` for per-second rates and `histogram_quantile(0.99, ...)` for latency percentiles — render it in Grafana, and write alerting rules that Prometheus evaluates on a schedule and hands to Alertmanager for routing. Counters only go up; you `rate()` them. Cardinality (the number of distinct label combinations) is the thing that kills you, so you keep label values bounded.

---

## 2. Foundations from first principles

### 2.1 Time series, samples, and the data model

A **sample** is a single `(timestamp, float64 value)` measurement. A **time series** is an ordered sequence of samples that all belong to the same identity.

In Prometheus, a time series' identity is:

```
<metric_name>{<label_name_1>="<value_1>", <label_name_2>="<value_2>", ...}
```

For example:

```
http_requests_total{method="GET", handler="/api/users", status="200", instance="10.0.0.4:8080", job="api"}
```

Crucially, **the metric name is itself just a label** under the hood: the special label `__name__`. So the series above is really the label set:

```
{__name__="http_requests_total", method="GET", handler="/api/users", status="200", instance="10.0.0.4:8080", job="api"}
```

Two series are the **same series** if and only if every label name/value pair matches. Change any label value — even one — and you have a brand-new, independent time series. **This is the root cause of cardinality explosions (§2.7, §6).**

Key vocabulary:

- **Metric name**: must match `[a-zA-Z_:][a-zA-Z0-9_:]*`. Colons are *reserved by convention for recording rules* (user-defined), not exporters.
- **Label name**: must match `[a-zA-Z_][a-zA-Z0-9_]*`. Names beginning with `__` are reserved for internal use (e.g., `__name__`, `__address__`, `__meta_*`).
- **Label value**: any UTF-8 string. (A label with an empty value is equivalent to the label being absent.)
- **`job`**: a conventional label naming the *kind* of target (e.g., `job="api"`). Set by the scrape config.
- **`instance`**: a conventional label naming the *specific* target endpoint, usually `host:port`. Set automatically from the scrape target's address.

### 2.2 Pull vs push (and why Prometheus pulls)

**Pull**: the monitoring system initiates the connection and reads metrics from each target. **Push**: the target initiates and sends its metrics to a collector.

Prometheus is **pull-based** for these reasons:

- **Target health is free**: if a scrape fails, you immediately know the target is down (the synthetic `up` metric, §2.6, goes to 0). With push, silence is ambiguous — is the app dead or just not pushing?
- **Centralized control of scrape rate and targets**: you tune cadence in one place; you can run multiple Prometheis scraping the same targets (HA) without app changes.
- **Easier to detect duplicate/rogue targets** and to run ad-hoc scrapes (`curl host:port/metrics`) for debugging.
- **Service discovery** decides *what* to scrape; the app just exposes `/metrics`.

The classic objections to pull, and the answers:

- *"What about short-lived batch jobs that finish before a scrape?"* → Use the **Pushgateway** (§12) as an intermediary the job pushes to and Prometheus scrapes.
- *"What about targets behind NAT/firewalls Prometheus can't reach?"* → Network design problem; sometimes solved with push-based gateways or agents, or by putting Prometheus inside the network.
- *"Doesn't pull miss data between scrapes?"* → Yes, by design: metrics are *sampled*, not a complete event log. Counters preserve totals across the gap (you don't lose increments, you lose intra-interval resolution).

### 2.3 The scrape: what actually happens on the wire

Prometheus does an HTTP `GET` to the target's metrics path (default `/metrics`) on the configured schedule (default scrape interval **15s**, but commonly tuned). The target responds `200 OK` with a body in the **text exposition format** (or, since recent versions, optionally Protobuf or OpenMetrics). A minimal body looks like:

```text
# HELP http_requests_total Total HTTP requests processed.
# TYPE http_requests_total counter
http_requests_total{method="GET",status="200"} 102934
http_requests_total{method="POST",status="500"} 12
# HELP process_resident_memory_bytes Resident memory size in bytes.
# TYPE process_resident_memory_bytes gauge
process_resident_memory_bytes 5.3489664e+07
```

- `# HELP <name> <text>` — human-readable description (optional but recommended).
- `# TYPE <name> <type>` — declares the metric type (`counter`, `gauge`, `histogram`, `summary`, `untyped`). Used for tooling hints; Prometheus itself stores everything as plain float series.
- Each non-comment line is one sample for the *current* moment. The target does **not** send timestamps in the normal case; Prometheus stamps the sample with the scrape time. (You *can* include a timestamp, but it's discouraged for live exporters.)

The exposition is **stateless from Prometheus's view of the wire**, but the *target itself* holds the running state (e.g., the counter's accumulated total) in memory. Each scrape reads the current accumulated values.

### 2.4 The four core metric types

Prometheus client libraries expose four metric *types*. They are conventions/abstractions; on disk everything is float time series.

1. **Counter** — a cumulative value that **only ever increases** (or resets to 0 on process restart). Use for *counts of events*: requests served, errors, bytes sent, jobs completed. **You almost never graph a counter directly**; you apply `rate()`/`increase()` to get per-second rates or windowed deltas. Naming convention: suffix `_total`.

2. **Gauge** — a value that can **go up and down**. Use for *current state*: temperature, memory in use, queue depth, in-flight requests, number of goroutines/threads. You graph gauges directly and aggregate them with `sum`, `avg`, `max`, etc.

3. **Histogram** — samples observations (e.g., request durations or sizes) into **configurable buckets**, exposing a set of cumulative counters. For a histogram named `http_request_duration_seconds`, the target exposes:
   - `http_request_duration_seconds_bucket{le="0.1"}` — count of observations ≤ 0.1s (cumulative; `le` = "less than or equal").
   - one such series per bucket boundary, plus `le="+Inf"` (count of all observations).
   - `http_request_duration_seconds_sum` — sum of all observed values.
   - `http_request_duration_seconds_count` — total count of observations (equals the `+Inf` bucket).

   Because buckets are cumulative counters, percentiles can be **computed server-side and aggregated across instances** with `histogram_quantile()` over `rate(..._bucket[5m])`. This is the standard way to get latency p50/p90/p99 in Prometheus.

4. **Summary** — also tracks observations, but computes **quantiles client-side** (e.g., the client library maintains a streaming φ-quantile estimate). Exposes:
   - `..._sum`, `..._count` (like histogram), plus
   - `...{quantile="0.5"}`, `...{quantile="0.99"}`, etc. — precomputed quantiles for *that instance only*.

   The catch: **summary quantiles cannot be aggregated across instances.** You can't average two instances' p99 to get a fleet p99. Summaries are cheaper if you only need per-instance quantiles and don't know good bucket boundaries; histograms are preferred when you want aggregatable percentiles and Grafana heatmaps.

**Histogram vs Summary — the decision in one line:** prefer **histograms** for aggregatable, server-side percentiles (the common case); use **summaries** for accurate per-instance quantiles where you can't pick buckets and don't aggregate.

> **Percentile / quantile** — the value below which a given fraction of observations fall. p99 (the 0.99 quantile) is the latency that 99% of requests are faster than; it captures tail behavior that averages hide. **φ (phi)** is just the fraction (0.99). Histograms *estimate* quantiles by interpolating within the bucket that contains the target rank, so accuracy depends on bucket granularity around your SLO.

### 2.5 Native (sparse) histograms — the newer model

Classic histograms have a fixed, predeclared set of buckets — you must guess good boundaries up front, and each boundary is its own time series (cardinality cost). **Native histograms** (also called *sparse histograms*, experimental from Prometheus ~2.40, increasingly stable) store an entire histogram as a **single time series** with exponentially-spaced buckets at a chosen resolution, created lazily only where data lands. They give high-resolution, mergeable percentiles at far lower cardinality. They require client-library support (Java/Micrometer, Go, etc.), the Protobuf or native-histogram exposition, and `histogram_quantile()` works on them too. Flag them as **experimental/version-specific**; confirm support before relying on them in production.

### 2.6 Synthetic / per-scrape metrics

For every scrape, Prometheus injects bookkeeping series you didn't emit:

- `up{job=..., instance=...}` — `1` if the scrape succeeded, `0` if it failed (connection refused, timeout, non-200, parse error). **The single most important series for "is it alive?" alerting.**
- `scrape_duration_seconds` — how long the scrape took.
- `scrape_samples_scraped` — number of samples in the response (watch this to catch cardinality growth).
- `scrape_samples_post_metric_relabeling` — samples kept after relabeling/drops.
- `scrape_series_added` — new series introduced by this scrape (churn detector).

### 2.7 Cardinality — define it now, fear it forever

**Cardinality** is the number of *distinct time series*. For one metric, cardinality = the product of the number of distinct values of each of its labels (roughly; in practice it's the number of combinations actually observed).

```
total series for a metric ≈ Π (distinct values of each label)
```

Example: `http_requests_total` with labels `method` (5 values), `status` (8 values), `handler` (40 values) → up to 5×8×40 = 1,600 series per instance. Times 50 instances = 80,000 series for one metric. That's fine.

Now add a label `user_id`. With 1,000,000 users you've just multiplied by up to a million. **This is a cardinality explosion.** Each active series consumes memory in the head block and inverted index; runaway cardinality OOM-kills Prometheus or destroys query performance. The cure (detailed in §6) is: **never put unbounded values in labels** (user IDs, request IDs, full URLs with query strings, emails, raw error messages, timestamps).

---

## 3. How it works internally

This is the heart of the chapter. We'll trace the full lifecycle: discovery → scrape → relabel → ingest → TSDB write → query → rule evaluation → alerting.

### 3.1 Component architecture

```
                       ┌──────────────────────────────────────────────┐
                       │                 Prometheus server             │
 Service Discovery ───▶│  ┌───────────┐  ┌──────────┐  ┌────────────┐  │
 (k8s, file, consul…)  │  │ Scrape    │─▶│ Relabel  │─▶│ TSDB        │  │
                       │  │ manager   │  │ + parse  │  │ (head+WAL,  │  │
 Targets /metrics  ◀───┼──┤ (HTTP GET)│  └──────────┘  │  blocks)    │  │
                       │  └───────────┘                 └─────┬──────┘  │
                       │  ┌───────────────┐  ┌──────────┐     │         │
                       │  │ Rule manager  │◀─┤ PromQL    │◀────┘         │
                       │  │ (recording+   │  │ engine    │              │
                       │  │  alerting)    │  └──────────┘  ┌──────────┐ │
                       │  └──────┬────────┘                │ Web/API  │ │
                       └─────────┼──────────────────────────┼─────────┘ │
                                 │ alerts                    │ HTTP API  │
                                 ▼                           ▼           │
                          ┌────────────┐              Grafana / curl     │
                          │Alertmanager│──▶ email/Slack/PagerDuty/…      │
                          └────────────┘                                  │
```

### 3.2 Step-by-step: service discovery → target list

1. **Service Discovery (SD)** continuously produces a set of *targets*. Each target is initially just an `__address__` plus a bag of **meta labels** prefixed `__meta_*` (e.g., from Kubernetes: `__meta_kubernetes_pod_label_app`, `__meta_kubernetes_namespace`). SD mechanisms: `kubernetes_sd_config`, `consul_sd_config`, `ec2_sd_config`, `dns_sd_config`, `file_sd_config` (a JSON/YAML file you maintain), and `static_configs` (hardcoded list).
2. SD updates flow into the **scrape manager** whenever the discovered set changes (pods come and go). There's no fixed list — it's reactive.

> **Service discovery** — the mechanism by which the monitoring system learns the current set of running instances without a human editing config. In Kubernetes, Prometheus watches the API server for pods/endpoints matching selectors.

### 3.3 Step-by-step: relabeling (the powerful, confusing part)

Before scraping, each target's label set passes through **`relabel_configs`** (and the scraped samples later pass through **`metric_relabel_configs`**). Relabeling is a small rule engine that lets you rewrite, drop, or keep targets/series.

Each relabel rule has:
- `source_labels`: list of labels whose values are concatenated (with `separator`, default `;`).
- `regex`: applied to the concatenated value (default `(.*)`).
- `action`: what to do — `replace` (default), `keep`, `drop`, `labelmap`, `labeldrop`, `labelkeep`, `hashmod`, `lowercase`, `uppercase`, `keepequal`, `dropequal`.
- `target_label`, `replacement`: for `replace`, where to write and what to write (`$1` etc. capture groups).
- `modulus`: for `hashmod` (used for sharding scrapes across multiple Prometheis).

Common patterns:
- **Keep only pods you want:** `action: keep` if `__meta_kubernetes_pod_annotation_prometheus_io_scrape == "true"`.
- **Set the scrape path/port** from annotations via `replace` into `__metrics_path__` / `__address__`.
- **Promote meta labels to real labels:** map `__meta_kubernetes_namespace` → `namespace`.
- **Drop noisy series after scrape (`metric_relabel_configs`):** `action: drop` where `__name__` matches a high-cardinality metric you don't need.

After relabeling, any remaining `__*` labels are discarded (they're internal). What survives becomes the target's permanent label set, attached to every sample from it.

### 3.4 Step-by-step: the scrape loop

For each target, the scrape manager runs an independent loop:

1. **Jitter & schedule:** Prometheus offsets each target's scrape by a deterministic jitter so scrapes don't all fire at once (thundering herd). The interval is `scrape_interval` (per-job overridable; global default **15s**).
2. **HTTP GET** to `scheme://address+metrics_path` with `Accept` headers advertising supported formats, an `X-Prometheus-Scrape-Timeout-Seconds` header, and `User-Agent: Prometheus/<version>`. Timeout = `scrape_timeout` (default **10s**, must be ≤ interval).
3. **Read & size-check:** body is read up to `body_size_limit` (default unlimited unless set). `sample_limit` can cap accepted samples (0 = unlimited).
4. **Parse** the exposition format into samples. Each sample = labels + value (+ optional native histogram).
5. **Apply `metric_relabel_configs`** (drop/keep/rename series).
6. **Staleness handling:** if a series present last scrape is absent this scrape, Prometheus writes a special **stale marker** (a NaN sentinel) so queries know the series ended rather than carrying the last value forever.
7. **Append** surviving samples to the TSDB with timestamp = scrape start time, plus the synthetic `up`, `scrape_*` series.
8. **Honor labels / timestamps:** if the exposition includes labels that clash with target labels, `honor_labels` decides who wins (default: target labels win; if `honor_labels: true`, the exposed labels win — used for the Pushgateway and federation).

### 3.5 The TSDB: storage internals

Prometheus's local TSDB is custom-built for append-mostly, time-ordered float samples. Key structures:

- **Head block (in memory):** the most recent ~2 hours of data. Incoming samples append to per-series in-memory chunks. The head also holds the **inverted index** mapping label name/value → series, enabling fast label matching.
- **WAL (Write-Ahead Log):** every append is first written to an on-disk WAL so the head can be reconstructed after a crash. The WAL is segmented and periodically **checkpointed** (old segments truncated after their data is persisted to a block).
- **Chunks:** samples for a series are encoded into **chunks** of up to 120 samples (or 2h), using **delta-of-delta** timestamp encoding and **XOR (Gorilla) compression** for values — extremely compact for slowly-changing floats (often ~1–2 bytes per sample amortized).
- **Blocks (on disk):** every ~2h the head is "cut" into an immutable **block** directory containing chunks, an index, a `meta.json`, and `tombstones` (for deletions). Blocks are named by a ULID.
- **Compaction:** background process merges adjacent small blocks into larger ones (e.g., 2h→...→multi-day) to reduce file count and improve query/retention efficiency, and applies tombstones/dedup.
- **`m-mapping`:** completed head chunks are memory-mapped to disk so they don't all sit in heap, reducing RAM pressure for the head.
- **Retention:** controlled by `--storage.tsdb.retention.time` (default **15d**) and/or `--storage.tsdb.retention.size`. Blocks older than retention are deleted whole.

Why this design: floats arrive roughly in timestamp order per series; compression exploits that; immutable blocks make compaction and retention simple; the inverted index makes "find all series with `job="api"` and `status="500"`" fast.

> **WAL (Write-Ahead Log)** — durability technique: write the change to a sequential log *before* applying it to in-memory state, so you can replay the log to recover after a crash. **Inverted index** — like a search engine's: maps each label value to the postings list of series IDs that have it, so a query intersects postings lists instead of scanning all series.

### 3.6 The query path (PromQL evaluation)

When you run a PromQL query (instant or range):

1. **Parse** the expression into an AST.
2. **Select**: for each *vector selector* (e.g., `http_requests_total{job="api"}`), use the inverted index to find matching series, then read samples in the requested time range from head + relevant blocks.
3. **Evaluate** functions/operators at each evaluation timestamp. For a **range query** (Grafana graph), Prometheus evaluates the expression once per **step** (resolution), producing a matrix.
4. **Lookback delta:** for *instant vectors*, Prometheus considers a sample "current" if it's within the **lookback delta** (default **5m**) before the eval time; beyond that the series is treated as stale/absent. This is why graphs of irregular series can show gaps after 5 minutes of silence.
5. **Return** JSON via the HTTP API (`/api/v1/query`, `/api/v1/query_range`).

### 3.7 Recording & alerting rule evaluation

A separate **rule manager** evaluates rule groups on a schedule (`evaluation_interval`, default **15s**; per-group `interval` overridable):

- **Recording rules** precompute expensive/used-often expressions and **write the result back as new time series** (named by convention with a colon, e.g., `job:http_requests:rate5m`). This speeds dashboards and keeps alert expressions simple. Rules in a group run **sequentially**, so a later rule can use an earlier rule's output.
- **Alerting rules** evaluate an expression; any returned series whose condition holds becomes a **pending** alert. If it stays true for the `for:` duration, it becomes **firing** and is sent to Alertmanager. Each alert carries `labels` (for routing/grouping) and `annotations` (human text, templated).

### 3.8 Alertmanager lifecycle

Prometheus *fires* alerts; **Alertmanager** decides what humans see:

1. **Dedup:** identical alerts from HA-paired Prometheis are deduplicated.
2. **Grouping:** alerts sharing `group_by` labels (e.g., `alertname`, `cluster`) are batched into one notification (`group_wait`, `group_interval`).
3. **Inhibition:** a higher-severity alert can suppress lower ones (e.g., "cluster down" inhibits "pod down").
4. **Silencing:** humans mute alerts matching a matcher for a window (during maintenance).
5. **Routing:** a routing tree maps alert labels to **receivers** (Slack, PagerDuty, email, webhook), with repeat intervals.

### 3.9 Alert state machine

```
   (expr false)            (expr true)              (held for `for:`)
 ── inactive ───────────▶ pending ───────────────▶ firing
        ▲                    │                         │
        └────────────────────┴─────────────────────────┘
              (expr false again → resolved/inactive)
```

`for:` exists to avoid flapping: a transient spike won't page you; only a sustained condition will.

---

## 4. The complete toolkit

### 4.1 Metric types (client-library API surface)

| Type | Goes up only? | Typical use | Exposed series | Aggregate across instances? |
|---|---|---|---|---|
| Counter | Yes (resets at restart) | event counts, bytes | `name_total` | Yes (sum, then rate) |
| Gauge | No (up/down) | current state, in-flight | `name` | Yes (sum/avg/max) |
| Histogram | n/a | latency/size distributions | `_bucket{le}`, `_sum`, `_count` | **Yes** (rate buckets → quantile) |
| Summary | n/a | per-instance quantiles | `{quantile}`, `_sum`, `_count` | **No** (quantiles) |
| Native histogram | n/a | high-res latency, low cardinality | single series | Yes (experimental) |

### 4.2 Core PromQL functions (the ones you must know)

| Function / operator | Purpose | Notes / gotchas |
|---|---|---|
| `rate(counter[5m])` | per-second average rate over window | counters only; auto-handles resets; window ≥ ~4× scrape interval |
| `irate(counter[5m])` | instant rate (last two samples) | spiky; good for fast-moving graphs, bad for alerting |
| `increase(counter[1h])` | total increase over window | = `rate * window`; extrapolated, may be fractional |
| `histogram_quantile(φ, expr)` | quantile from histogram buckets | feed `sum(rate(..._bucket[5m])) by (le)`; needs `le` label |
| `sum / avg / min / max / count` | aggregation operators | use `by(...)` / `without(...)` to control grouping |
| `topk(k, expr)` / `bottomk(k, expr)` | k highest/lowest series | great for "top 10 noisy endpoints" |
| `count_values("v", expr)` | count series by value | histogram-of-values |
| `quantile(φ, expr)` | quantile *across series* (not over time) | different from histogram_quantile |
| `delta(gauge[5m])` | difference for gauges | gauges, not counters |
| `deriv(gauge[5m])` | per-second derivative (linear fit) | for gauges trending |
| `predict_linear(gauge[1h], 4*3600)` | extrapolate value 4h ahead | disk-full forecasting |
| `absent(expr)` / `absent_over_time(expr[5m])` | 1 if no series matched | alert on "metric stopped existing" |
| `clamp_min/max`, `round`, `abs` | math helpers | |
| `label_replace` / `label_join` | rewrite labels in queries | for joins/normalization |
| `vector(0)` | turn scalar into a vector | OR-with-zero for missing data |
| `offset 1h` | shift query back in time | week-over-week comparisons |
| `@ <timestamp>` modifier | evaluate at a fixed time | for stable comparisons |
| `* on(...) group_left(...)` | vector matching / joins | one-to-one vs many-to-one |

**Binary operators & matching:** `+ - * / % ^`, comparisons (`== != > < >= <=`, with `bool` modifier), set ops (`and`, `or`, `unless`). For arithmetic between two vectors, Prometheus matches series by *identical labels* unless you specify `on(...)`/`ignoring(...)` and `group_left/group_right` for many-to-one joins.

### 4.3 Key configuration (prometheus.yml)

| Setting | Scope | Default | Meaning |
|---|---|---|---|
| `global.scrape_interval` | global/job | 15s | how often to scrape |
| `global.scrape_timeout` | global/job | 10s | per-scrape timeout (≤ interval) |
| `global.evaluation_interval` | global | 15s | how often to evaluate rules |
| `global.external_labels` | global | none | labels added to data leaving this server (federation/remote write/alerts); identifies the Prometheus |
| `scrape_configs[].job_name` | job | — | sets `job` label |
| `scrape_configs[].metrics_path` | job | `/metrics` | scrape path |
| `scrape_configs[].scheme` | job | `http` | http/https |
| `scrape_configs[].sample_limit` | job | 0 (off) | cap samples/scrape (cardinality guard) |
| `scrape_configs[].label_limit` / `label_name_length_limit` / `label_value_length_limit` | job | 0 (off) | cardinality/abuse guards |
| `relabel_configs` | job | — | rewrite/keep/drop *targets* |
| `metric_relabel_configs` | job | — | rewrite/keep/drop *series* post-scrape |
| `honor_labels` | job | false | exposed labels override target labels (Pushgateway/federation) |
| `*_sd_config` | job | — | service discovery |
| `rule_files` | global | — | paths to recording/alerting rule files |
| `alerting.alertmanagers` | global | — | where to send alerts |
| `remote_write` / `remote_read` | global | — | long-term storage integration |

### 4.4 Key CLI flags (server)

| Flag | Default | Purpose |
|---|---|---|
| `--config.file` | `prometheus.yml` | config path |
| `--storage.tsdb.path` | `data/` | data dir |
| `--storage.tsdb.retention.time` | 15d | time-based retention |
| `--storage.tsdb.retention.size` | 0 (off) | size-based retention (e.g., `50GB`) |
| `--storage.tsdb.wal-compression` | on (recent) | compress WAL |
| `--web.enable-lifecycle` | off | allow `POST /-/reload` & `/-/quit` |
| `--web.enable-admin-api` | off | allow snapshot/delete-series admin API |
| `--query.max-samples` | 50,000,000 | guard against memory-blowing queries |
| `--query.timeout` | 2m | max query duration |
| `--query.max-concurrency` | 20 | concurrent queries |
| `--enable-feature=...` | — | turn on experimental features (e.g., `native-histograms`, `exemplar-storage`) |

### 4.5 Operational endpoints

| Endpoint | Purpose |
|---|---|
| `GET /metrics` | Prometheus's own metrics (it's also a target) |
| `GET /-/healthy`, `/-/ready` | liveness/readiness probes |
| `POST /-/reload` | hot-reload config (needs `--web.enable-lifecycle`) |
| `GET /api/v1/query` / `/query_range` | PromQL HTTP API |
| `GET /api/v1/targets` | current targets + scrape health |
| `GET /api/v1/rules` / `/alerts` | rule/alert state |
| `GET /api/v1/status/tsdb` | cardinality stats (top series by metric/label) — **invaluable for debugging cardinality** |
| `GET /api/v1/label/<name>/values` | distinct values of a label |
| `GET /api/v1/targets/metadata` | metric metadata |

### 4.6 The ecosystem (adjacent tools — each explained)

- **Grafana** — the de-facto dashboard/visualization layer; queries Prometheus via PromQL and draws graphs, heatmaps (great for histograms), and tables. Not part of Prometheus but nearly always paired.
- **Alertmanager** — alert routing/dedup/silencing (above).
- **node_exporter** — exposes Linux/Unix host metrics (CPU, memory, disk, network, filesystem). The canonical infra exporter.
- **cAdvisor** / **kube-state-metrics** — container resource metrics, and Kubernetes object state (deployments, pods, replicas) respectively.
- **blackbox_exporter** — probes endpoints externally (HTTP/TCP/ICMP/DNS) for black-box availability/latency.
- **Pushgateway** — accept-and-hold gateway for short-lived/batch jobs (§12).
- **Micrometer** — a vendor-neutral metrics facade for the JVM (think "SLF4J for metrics"); Spring Boot uses it; it has a Prometheus registry (§5).
- **OpenMetrics** — the IETF/CNCF standardization of Prometheus's exposition format (adds exemplars, native histogram concepts). Prometheus can scrape it.
- **OpenTelemetry (OTel)** — a broader telemetry standard (metrics+traces+logs). Its metrics can be exported to Prometheus (push via OTLP→remote write, or pull via a Prometheus exporter). Increasingly the instrumentation entry point.
- **Thanos / Cortex / Mimir** — long-term storage, global query, and horizontal scaling for Prometheus (§11).
- **promtool** — CLI for validating config/rules, unit-testing rules, and querying TSDB (§9).

---

## 5. Code examples by use case

These default to **Java/JVM** (the reader's ecosystem), with config/PromQL where appropriate. Each is complete enough to adapt.

### 5.1 Spring Boot + Micrometer: production-grade instrumentation

Spring Boot Actuator + Micrometer is the standard JVM path. Micrometer is the metrics facade; the Prometheus registry turns meters into the exposition format at `/actuator/prometheus`.

**`build.gradle` (Gradle):**

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    // Brings in micrometer-registry-prometheus transitively in recent Spring Boot:
    runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
}
```

**`application.yml`:**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus   # expose the prometheus endpoint
  endpoint:
    prometheus:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}   # add a common label to ALL metrics
    distribution:
      # Enable histogram buckets for HTTP server timing so we can do percentiles server-side:
      percentiles-histogram:
        http.server.requests: true
      # Optional explicit SLO buckets (seconds) tuned to your latency SLO:
      slo:
        http.server.requests: 50ms,100ms,200ms,500ms,1s,2s
```

That alone gives you `http_server_requests_seconds_bucket/_count/_sum` with labels `method`, `uri`, `status`, `outcome`, `application` — enough for RED metrics (Rate, Errors, Duration).

**Custom business metrics in code:**

```java
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final Counter ordersPlaced;
    private final Counter ordersFailed;
    private final Timer checkoutTimer;          // a Timer is a histogram of durations
    private final DistributionSummary orderValue; // a histogram of non-time values
    private final MeterRegistry registry;

    public OrderService(MeterRegistry registry) {
        this.registry = registry;

        // Counter: bounded labels only (region is low-cardinality; never order_id here!)
        this.ordersPlaced = Counter.builder("orders.placed")
            .description("Orders successfully placed")
            .tag("channel", "web")
            .register(registry);

        this.ordersFailed = Counter.builder("orders.failed")
            .description("Orders that failed")
            .register(registry);

        // Timer with histogram enabled -> emits _bucket series for histogram_quantile()
        this.checkoutTimer = Timer.builder("checkout.duration")
            .description("Checkout latency")
            .publishPercentileHistogram()          // emit native/percentile buckets
            .serviceLevelObjectives(
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofMillis(500),
                java.time.Duration.ofSeconds(1))
            .register(registry);

        this.orderValue = DistributionSummary.builder("order.value.dollars")
            .baseUnit("dollars")
            .publishPercentileHistogram()
            .register(registry);
    }

    public Order checkout(Cart cart) {
        // Time a block of code; records into the histogram
        return checkoutTimer.record(() -> {
            try {
                Order o = doCheckout(cart);
                ordersPlaced.increment();
                orderValue.record(o.totalDollars());
                return o;
            } catch (PaymentException e) {
                // Tag with a BOUNDED reason, never the raw exception message
                registry.counter("orders.failed", "reason", classify(e)).increment();
                throw e;
            }
        });
    }

    // Gauge: register a function that reads current state on every scrape.
    // The registry holds a weak reference; keep the gauged object alive.
    public void registerQueueDepthGauge(WorkQueue q) {
        Gauge.builder("work.queue.depth", q, WorkQueue::size)
             .description("Items waiting in the work queue")
             .register(registry);
    }

    private String classify(PaymentException e) {
        // Map arbitrary errors to a small fixed set of label values:
        return switch (e.code()) {
            case 402, 403 -> "declined";
            case 408, 504 -> "timeout";
            default       -> "other";
        };
    }
}
```

Why this is idiomatic: **bounded label values**, histograms via `publishPercentileHistogram()` for aggregatable percentiles, gauges via a supplier function (read lazily at scrape time), and a `classify()` step that *prevents* unbounded error-message cardinality.

### 5.2 Plain Java (no Spring) with the official client + an HTTP exposition server

```java
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

public class App {
    static final Counter REQUESTS = Counter.build()
        .name("app_requests_total").help("Total requests")
        .labelNames("method", "status").register();

    static final Histogram LATENCY = Histogram.build()
        .name("app_request_duration_seconds").help("Request latency")
        .buckets(0.05, 0.1, 0.2, 0.5, 1, 2, 5)   // explicit bucket boundaries
        .labelNames("method").register();

    static final Gauge INFLIGHT = Gauge.build()
        .name("app_inflight_requests").help("In-flight requests").register();

    public static void main(String[] args) throws Exception {
        DefaultExports.initialize();             // JVM/process metrics (heap, GC, fds…)
        HTTPServer server = new HTTPServer(9400); // exposes /metrics on :9400

        handle("GET", () -> { /* business logic */ });
    }

    static void handle(String method, Runnable work) {
        INFLIGHT.inc();
        Histogram.Timer t = LATENCY.labels(method).startTimer(); // start a stopwatch
        String status = "200";
        try { work.run(); }
        catch (Exception e) { status = "500"; }
        finally {
            t.observeDuration();                 // records elapsed seconds into the histogram
            REQUESTS.labels(method, status).inc();
            INFLIGHT.dec();
        }
    }
}
```

Scrape config to read it:

```yaml
scrape_configs:
  - job_name: 'app'
    static_configs:
      - targets: ['10.0.0.4:9400']
```

### 5.3 PromQL: the RED method (Rate, Errors, Duration)

```promql
# Request rate per second, by endpoint:
sum by (uri) (rate(http_server_requests_seconds_count[5m]))

# Error ratio (5xx) as a fraction of all requests, by endpoint:
sum by (uri) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
sum by (uri) (rate(http_server_requests_seconds_count[5m]))

# p99 latency across the fleet (aggregatable BECAUSE histogram):
histogram_quantile(
  0.99,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))
)
```

The p99 query is the canonical pattern: `rate()` each bucket, `sum ... by (le, ...)` to combine instances, then `histogram_quantile`. Forgetting to keep `le` in the `by(...)` is the #1 mistake (you'll get an empty/garbage result).

### 5.4 PromQL: USE method for a node (Utilization, Saturation, Errors)

```promql
# CPU utilization fraction (1 - idle):
1 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m]))

# Memory utilization:
1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)

# Disk will be full in < 4h? (predict_linear on free bytes trending down)
predict_linear(node_filesystem_avail_bytes{mountpoint="/"}[1h], 4*3600) < 0

# Saturation: run-queue / load relative to CPUs
node_load5 / count by (instance) (node_cpu_seconds_total{mode="idle"})
```

### 5.5 Recording rules (precompute hot expressions)

```yaml
# rules/recording.yml
groups:
  - name: http-aggregations
    interval: 30s
    rules:
      - record: job:http_requests:rate5m            # naming convention: level:metric:op
        expr: sum by (job) (rate(http_server_requests_seconds_count[5m]))

      - record: job:http_errors:ratio5m
        expr: |
          sum by (job) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          /
          sum by (job) (rate(http_server_requests_seconds_count[5m]))
```

### 5.6 Alerting rules (with multi-window burn-rate for SLOs)

```yaml
# rules/alerts.yml
groups:
  - name: availability
    rules:
      - alert: TargetDown
        expr: up == 0
        for: 2m
        labels: {severity: critical}
        annotations:
          summary: "Target {{ $labels.instance }} of job {{ $labels.job }} is down"

      - alert: HighErrorRate
        expr: job:http_errors:ratio5m > 0.05
        for: 10m
        labels: {severity: warning}
        annotations:
          summary: "5xx error ratio {{ $value | humanizePercentage }} on {{ $labels.job }}"

  - name: slo-burn
    rules:
      # SLO: 99.9% success => error budget = 0.1%. Page fast when burning the budget fast.
      - alert: ErrorBudgetBurnFast
        expr: |
          (job:http_errors:ratio5m > (14.4 * 0.001))
          and
          (sum by(job)(rate(http_server_requests_seconds_count{status=~"5.."}[1h]))
           / sum by(job)(rate(http_server_requests_seconds_count[1h]))) > (14.4 * 0.001)
        for: 2m
        labels: {severity: critical}
        annotations:
          summary: "Burning error budget 14.4x too fast on {{ $labels.job }}"
```

> **Burn rate** — how fast you're consuming your error budget relative to the rate that would exactly exhaust it over the SLO window. A 14.4× burn over 1h exhausts a 30-day budget in ~2 days; multi-window burn-rate alerts (Google SRE book) page fast on big burns and slow on small ones, balancing sensitivity vs noise.

### 5.7 Pushgateway for a batch (cron) job

```bash
# Java/shell cron job pushes its final metrics after completing:
cat <<EOF | curl --data-binary @- http://pushgateway:9091/metrics/job/nightly_etl/instance/host01
# TYPE etl_records_processed_total counter
etl_records_processed_total 184213
# TYPE etl_last_success_timestamp_seconds gauge
etl_last_success_timestamp_seconds $(date +%s)
EOF
```

Alert on staleness (the job stopped running):

```promql
time() - etl_last_success_timestamp_seconds{job="nightly_etl"} > 26*3600
```

### 5.8 Kubernetes scrape config with relabeling

```yaml
scrape_configs:
  - job_name: 'k8s-pods'
    kubernetes_sd_config: {role: pod}
    relabel_configs:
      # Only scrape pods annotated prometheus.io/scrape: "true"
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: "true"
      # Use the pod's annotated path, default /metrics
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      # Override the port from annotation
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
        target_label: __address__
      # Promote useful meta labels to real labels
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
    metric_relabel_configs:
      # Drop a known high-cardinality metric we don't need
      - source_labels: [__name__]
        regex: 'noisy_internal_metric_.*'
        action: drop
```

---

## 6. Implementation concerns & best practices

### 6.1 Cardinality — the cardinal sin (no pun intended)

- **Never use unbounded label values:** user/order/request IDs, emails, full URLs with query strings, raw error/exception messages, timestamps, IPs (often), random UUIDs. Each unique value spawns a permanent series.
- **Bound everything you can:** template URLs (`/users/{id}` not `/users/12345`), bucket error codes into a small set, cap status to standard codes.
- **Estimate before shipping:** series ≈ product of label cardinalities × instances. Do the multiplication for new labels.
- **Guardrails:** set `sample_limit`, `label_limit`, `label_value_length_limit` per job; `metric_relabel_configs` to `drop`/`labeldrop` known offenders.
- **Detect drift:** watch `scrape_samples_scraped`, `scrape_series_added`, and `prometheus_tsdb_head_series`. Use `/api/v1/status/tsdb` (top series by metric & top label-value counts). Query `count({__name__=~".+"})` per job, or `topk(10, count by (__name__)({__name__=~".+"}))`.
- **Rule of thumb:** a single Prometheus comfortably handles low single-digit millions of active series on a well-sized box; tens of millions needs serious RAM and tuning or sharding.

### 6.2 Performance & resources

- **Memory** is dominated by *active series* (head + index), not by sample volume. Each series costs a few KB resident. Sample appends are cheap and compress well (~1–2 bytes/sample on disk).
- **Scrape interval** vs resolution vs cost: smaller interval = more samples = more disk/CPU; 15–30s is typical. Don't go to 1s fleet-wide without reason.
- **Range `[window]` in `rate()`** should be ≥ ~4× scrape interval so each evaluation sees enough samples; too small → gaps/jitter, too large → over-smoothing.
- **Recording rules** for any expression used by many dashboard panels or alerts — precompute once instead of recomputing per view.
- **Query cost guards:** `--query.max-samples`, `--query.timeout`, `--query.max-concurrency`. A single careless `rate(huge_metric[1d])` across millions of series can OOM the query engine.
- **WAL replay time** on restart grows with head size; large heads mean slow restarts (minutes). Consider snapshotting / Thanos receive for faster recovery patterns.

### 6.3 Correctness & concurrency

- **Counters reset to 0 on restart;** `rate()`/`increase()` detect resets and handle them — but only if the series identity is stable. If your labels change on restart (e.g., a label carries the pod's start time), reset detection breaks. Keep label sets stable across restarts.
- **Don't expose timestamps** from live exporters; let Prometheus stamp scrape time. Custom timestamps interact badly with staleness and lookback.
- **Out-of-order samples** are rejected by default (recent versions allow a configurable OOO window). Federation/remote-write can surface this.
- **Histograms must use the same bucket boundaries across instances** to be aggregatable; mismatched `le` sets produce wrong quantiles.
- **Client libraries are thread-safe** for increments/observes; gauges via supplier functions are read at scrape time (ensure the supplier is cheap and non-blocking).

### 6.4 Security

- `/metrics` often leaks internal info (versions, internal hostnames, occasionally sensitive label values). **Don't expose it publicly unauthenticated.** Bind to internal interfaces, put behind mTLS/reverse proxy, or use network policy.
- The **admin API** (`--web.enable-admin-api`) can delete series and snapshot — keep it off or locked down. `--web.enable-lifecycle` exposes `/-/reload` and `/-/quit` — protect it.
- Prometheus itself historically had **no built-in auth/TLS for scraping or its UI** by default; recent versions add TLS and basic-auth via a web config file. Treat the monitoring network as sensitive.
- Beware **secrets in labels** (tokens, PII). Relabel-drop them.

### 6.5 Observability of Prometheus itself

Prometheus exposes its own `/metrics`. Watch:
- `prometheus_tsdb_head_series` (cardinality), `prometheus_tsdb_wal_corruptions_total`, `prometheus_tsdb_compactions_failed_total`.
- `prometheus_target_scrape_pool_*`, `prometheus_sd_*` (discovery health).
- `prometheus_rule_evaluation_duration_seconds`, `prometheus_rule_group_iterations_missed_total` (rules falling behind).
- `prometheus_remote_storage_*` (remote-write backlog/failures).
- `up` for itself and the `scrape_*` series for every job.

### 6.6 Cost

- Self-hosted: cost = RAM (series) + disk (retention) + ops time. Long retention at scale → push to object storage via Thanos/Mimir (cheap S3) instead of large local disks.
- Managed (Grafana Cloud, Amazon Managed Prometheus, etc.): typically billed by **active series and samples ingested** + queries. Cardinality directly drives the bill — another reason to bound labels.

### 6.7 Testing

- **`promtool check config/rules`** validates syntax in CI.
- **`promtool test rules`** unit-tests recording/alerting rules against synthetic series with expected outputs — actually test that your alerts fire when they should (§9.5).
- For instrumentation, unit-test that custom metrics exist with expected names/labels using the client library's registry (`CollectorRegistry`), and assert label values are bounded.

### 6.8 Production hardening checklist

- Run Prometheus **HA in pairs** (two identical scrapers) behind Alertmanager dedup.
- Set retention sized to disk; alert on disk usage (`predict_linear`).
- Set `sample_limit`/`label_limit` defaults on every job.
- Keep `external_labels` unique per server (for dedup/federation/remote-write).
- Back up nothing fancy — Prometheus data is ephemeral monitoring data; for durability use remote-write to long-term storage.
- Capacity test: load the expected series count in staging; watch RSS and WAL replay time.

### 6.9 Anti-patterns

- High-cardinality labels (the big one).
- Graphing raw counters (always `rate()`).
- Using a **summary** when you need fleet-wide percentiles (can't aggregate).
- Putting **email/PII/secrets** in labels.
- Over-alerting on **causes** instead of **symptoms** (page on user-facing SLO violations, not on every CPU spike).
- `avg()` of latency to "get p99" — averages hide tails; use histograms.
- One giant Prometheus for everything instead of sharding by team/region.
- Custom timestamps on live exporters.
- Reusing label names with inconsistent semantics across services.

---

## 7. Advanced topics & deep internals

### 7.1 Staleness handling in depth

When a series disappears from a scrape (or the target goes down), Prometheus inserts a **staleness marker** — a special NaN value — at the next eval, so instant queries return *no data* for that series rather than stale carry-forward. This replaced the old fixed-5m fade behavior for scrape-driven series and makes "the series ended" explicit. The **lookback delta** (default 5m) still governs how long an instant query will reach back for the last real sample of an irregularly-updated series.

### 7.2 Vector matching, `group_left`/`group_right`, and joins

PromQL "joins" are label-set matching. For `a * on(x) group_left(y) b`, the left side may have many series per matching `x` (many-to-one), and `group_left(y)` copies label `y` from the right (the "one") side. This is how you enrich metrics with metadata series like `kube_pod_info` or `*_info` gauges (value `1`, carrying version/owner labels). Mastering this is a senior signal.

```promql
# Attach the "version" label from a build-info gauge to request rates:
sum by (job) (rate(http_server_requests_seconds_count[5m]))
* on (job) group_left(version) build_info
```

### 7.3 `*_info` metric pattern

A common convention: expose a gauge always equal to `1` whose *labels* carry slowly-changing metadata (`build_info{version="1.4.2", commit="abc"} 1`, `node_uname_info`, `kube_pod_info`). You keep the metadata out of high-traffic metrics' labels (cardinality control) and **join it in at query time** with `group_left`.

### 7.4 Exemplars

**Exemplars** attach a specific trace/example to a metric sample — e.g., a histogram bucket sample carries a `trace_id` for one request that fell in that bucket. This bridges metrics→traces ("this latency spike — show me a trace"). Requires OpenMetrics exposition, `--enable-feature=exemplar-storage`, and client support (Micrometer can emit them). Grafana can render exemplars on graphs as clickable points.

### 7.5 Native histograms (deep)

Native histograms encode buckets as exponential schemas: `bucket_i` covers `(base^i, base^(i+1)]` where `base = 2^(2^-schema)`. Higher `schema` = finer resolution. Stored as one series with sparse bucket counts, they auto-adapt to the data's range, give smooth high-resolution `histogram_quantile`, and merge correctly across instances — all at a fraction of classic-histogram cardinality. Caveats: experimental flags, exposition format requirements, and tooling that must understand the new sample type. Treat as **version-specific** and pilot before fleet-wide adoption.

### 7.6 Federation

`/federate` lets one Prometheus scrape *selected aggregated series* from another (matched by `match[]` selectors). Used for **hierarchical** setups: per-cluster Prometheis expose only recording-rule aggregates; a global Prometheus federates those for cross-cluster dashboards. It does **not** scale to pulling all raw series (that's Thanos/Mimir's job). `honor_labels: true` is set so the source's `instance`/`job` survive.

### 7.7 Sharding scrapes with `hashmod`

To split scrape load across N Prometheis, use relabeling: `action: hashmod` on `__address__` into a temp label, then `keep` where `temp % N == shard_index`. Each shard scrapes a deterministic subset; a global query layer (Thanos) merges results.

### 7.8 Remote write/read internals

`remote_write` ships samples to an HTTP endpoint (Thanos Receive, Mimir, Cortex, managed services) using a queue with sharding, batching, retries, and backpressure. Tunables: `queue_config` (`max_shards`, `capacity`, `max_samples_per_send`, `batch_send_deadline`), and `write_relabel_configs` (drop series before shipping — cost control). Watch `prometheus_remote_storage_samples_pending` / `_failed_total` / `_retried_total`. `remote_read` lets queries fetch historical data from the remote store (less common; usually you query the remote system directly).

### 7.9 Agent mode

Prometheus **Agent mode** (`--enable-feature=agent` / agent config) disables querying/rule-eval/local-TSDB-querying and focuses purely on scrape→remote_write. Used as a lightweight forwarder into a central long-term store, minimizing local resource use.

### 7.10 OOO (out-of-order) ingestion

Recent TSDB supports a configurable out-of-order time window (`tsdb.out_of_order_time_window`), enabling ingestion of slightly late samples (helpful with remote-write fan-in and some exporters). Off by default; enabling has memory/compaction implications.

### 7.11 PromQL subqueries

A **subquery** evaluates an instant expression over a range to build a range vector on the fly: `max_over_time( rate(http_requests_total[5m])[30m:1m] )` = the max of the 5m rate, sampled every 1m over the last 30m. Powerful but expensive; the inner step (`:1m`) multiplies work.

### 7.12 `*_over_time` family

`avg_over_time`, `max_over_time`, `min_over_time`, `sum_over_time`, `count_over_time`, `quantile_over_time`, `stddev_over_time`, `last_over_time`, `present_over_time` — aggregate a single series *over time* (vs the aggregation operators which aggregate *across series* at one instant). Knowing which axis you're aggregating is a frequent interview/debug trap.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Histogram vs Summary vs Native histogram

| Dimension | Classic Histogram | Summary | Native Histogram |
|---|---|---|---|
| Quantile computed | server-side (query) | client-side | server-side |
| Aggregatable across instances | **Yes** | **No** | Yes |
| Must predefine buckets | Yes | No | No (auto exponential) |
| Cardinality | high (one series/bucket) | moderate | **low** (1 series) |
| Quantile accuracy | depends on buckets near SLO | high per-instance | high, adaptive |
| Grafana heatmap | Yes | No | Yes |
| Maturity | stable | stable | experimental/newer |
| Use when | fleet percentiles, dashboards | per-instance exact quantiles, unknown ranges | high-res low-cardinality (modern stacks) |

### 8.2 Pull vs Push

| | Pull (scrape) | Push (Pushgateway / push systems) |
|---|---|---|
| Target health | free (`up`) | ambiguous |
| Short-lived jobs | bad (may miss) | good |
| Firewall/NAT traversal | needs reachability | easier |
| Service discovery | central, dynamic | target must know collector |
| Default in Prometheus | yes | only for batch via Pushgateway |
| Risk | unreachable targets | stale pushed data lingers |

**Rule:** pull for long-lived services; Pushgateway *only* for short-lived/batch; never use Pushgateway as a generic push buffer for live services (it breaks health semantics and accumulates stale series).

### 8.3 Single Prometheus vs Federation vs Thanos/Cortex/Mimir

| Need | Solution |
|---|---|
| One team, ≤ a few million series, 15d retention | Single (or HA pair) Prometheus |
| Cross-cluster dashboards from aggregates | Federation of recording-rule outputs |
| Global query view across many Prometheis | Thanos Query / Mimir / Cortex |
| Long-term, cheap retention on object storage | Thanos Store + Compactor / Mimir / Cortex (S3/GCS) |
| Horizontal ingest scale (huge series counts) | Cortex/Mimir (or Thanos Receive) |

### 8.4 Thanos vs Cortex vs Mimir

| | Thanos | Cortex | Mimir (Grafana) |
|---|---|---|---|
| Model | sidecar + object storage + global query | push-based, multi-tenant microservices | fork/successor of Cortex, multi-tenant, scale-tuned |
| Ingest | scrape locally + upload blocks (sidecar) or Receive (push) | remote_write push | remote_write push |
| Long-term store | object storage (S3/GCS) | object storage | object storage |
| Multi-tenant | possible | core feature | core feature |
| Operational complexity | moderate–high | high | high (but well-packaged) |
| Pick when | you already have many Prometheis, want global view + cheap LTS with least disruption | need heavy multi-tenant SaaS-style | want Cortex-class scale with Grafana's tuning/packaging |

### 8.5 Metric vs Log vs Trace (when to use which)

| Question | Best pillar |
|---|---|
| "Is the error rate up right now? Alert me." | Metrics |
| "Which exact requests failed and why (stack trace)?" | Logs |
| "Why was *this one* request slow across services?" | Traces |
| "p99 latency trend over 90 days" | Metrics (+ LTS) |
| "Audit: who did what when" | Logs/events |

### 8.6 Symptom vs cause alerting

Alert on **symptoms users feel** (SLO burn, error rate, latency) at page severity; alert on **causes** (disk filling, queue growing) at ticket/warning severity for proactive fixes. This minimizes pager fatigue while preserving lead time.

---

## 9. Failure modes & debugging

### 9.1 "No data" on a graph

Diagnose in order:
1. **Is the target up?** `up{job="x"}` → 0 means scrape failing. Check `GET /api/v1/targets` for the error (connection refused, 401, timeout, "context deadline exceeded").
2. **Does the series exist?** Query just the metric name with no functions. If empty, check spelling, labels, and that relabeling didn't drop it.
3. **Stale/lookback:** if the series updates less often than every 5m, instant queries show gaps. Lengthen lookback or query a range.
4. **`rate()` on too-short window** with sparse scrapes → empty. Widen the range.
5. **Histogram quantile empty:** you forgot `le` in `by(...)`, or the buckets aren't being emitted (histogram not enabled).

### 9.2 Cardinality explosion (Prometheus OOM / slow)

Symptoms: rising RSS, slow queries, OOMKilled, `prometheus_tsdb_head_series` climbing, slow WAL replay.

Diagnose:
```promql
topk(10, count by (__name__)({__name__=~".+"}))     # worst metrics by series count
```
and use `GET /api/v1/status/tsdb` (returns top series by metric and **top label-value counts** — the smoking gun, usually a label like `user_id`).

Fix:
- `metric_relabel_configs` to `drop` the offender or `labeldrop` the bad label *now* (stops the bleeding; existing series age out at retention).
- Fix the instrumentation to stop emitting unbounded values.
- Add `sample_limit`/`label_limit` so future offenders are capped.

### 9.3 Scrapes timing out / slow target

`scrape_duration_seconds` near `scrape_timeout`. Causes: target's `/metrics` is huge (cardinality) or expensive to render (synchronous DB calls in gauge suppliers — anti-pattern). Fix the exporter or raise `scrape_timeout` (but ≤ interval).

### 9.4 Counter resets / weird negative-looking rates

If labels carry restart-varying values (start time, random IDs), `rate()` reset detection breaks and rates look wrong. Stabilize labels. Also, a target reusing the same address for a *different* process can blend two counters' series — keep `instance`/identity stable.

### 9.5 Alerts not firing / flapping

- **Not firing:** test with `promtool test rules`. Common cause: the `for:` is longer than the spike, or the expression returns no series (so nothing to fire). Verify the expr returns rows in the UI.
- **Flapping:** add/raise `for:`, use Alertmanager grouping/inhibition, smooth with longer rate windows or multi-window burn rate.

`promtool` unit test:
```yaml
# alert_test.yml
rule_files: [rules/alerts.yml]
evaluation_interval: 1m
tests:
  - interval: 1m
    input_series:
      - series: 'up{job="api", instance="i1"}'
        values: '0 0 0 0'          # target down for 4 minutes
    alert_rule_test:
      - eval_time: 3m
        alertname: TargetDown
        exp_alerts:
          - exp_labels: {severity: critical, job: api, instance: i1}
```
Run: `promtool test rules alert_test.yml`.

### 9.6 Remote-write backlog

`prometheus_remote_storage_samples_pending` growing and `_failed_total` rising = the remote store can't keep up or is erroring. Check the remote endpoint, raise `max_shards`/`max_samples_per_send`, or `write_relabel_configs` drop low-value series.

### 9.7 WAL corruption / slow restart

`prometheus_tsdb_wal_corruptions_total > 0` after an unclean shutdown — Prometheus truncates the corrupt tail and recovers (some recent samples lost). Slow restarts = large head/WAL replay; mitigate with smaller heads, faster disks, or agent/remote-write architectures.

### 9.8 Real-world incident patterns (representative, not vendor-specific)

- **The `user_id` label that took down monitoring:** a well-meaning dev added a per-user label to a request counter; series exploded into the millions overnight; Prometheus OOM-looped. Fix: emergency `labeldrop`, then code fix. Lesson: review every new label for cardinality.
- **Silent blind spot via aggressive relabel `drop`:** a `keep` regex was too strict and silently stopped scraping a whole job; nobody noticed until an outage had no metrics. Lesson: alert on `up`/`absent()` for critical jobs and on `scrape_samples_scraped == 0`.
- **Summary p99 that "couldn't be averaged":** dashboards showed nonsense fleet p99 because someone `avg()`'d summary quantiles. Fix: migrate to histograms. Lesson: know the aggregation rules.
- **Pushgateway as a live buffer:** a service pushed gauges; on deploy it stopped, but the Pushgateway kept serving the last values forever, so dashboards looked healthy during an outage. Lesson: Pushgateway is for batch jobs; alert on push freshness.

---

## 10. Interview drill

**Q1. Why is Prometheus pull-based, and what are the tradeoffs?**
Model answer: Pull gives free target-health detection (`up`), centralized control of cadence/targets, easy HA (multiple scrapers), and ad-hoc debuggability (`curl /metrics`). Tradeoffs: short-lived jobs may be missed (use Pushgateway), and targets must be network-reachable.
- *Follow-up: How do you monitor a cron job then?* Pushgateway, and alert on `time() - last_success_timestamp`.
- *Follow-up: How does pull detect a dead target faster than push?* If a scrape fails, `up` immediately goes 0; push relies on absence, which is ambiguous.
- *Follow-up: Doesn't pull lose data between scrapes?* It samples; counters preserve totals across gaps, you lose only intra-interval resolution.

**Q2. Counter vs Gauge vs Histogram vs Summary — when each?**
Counter for monotonically increasing event counts (rate them). Gauge for up/down current state. Histogram for aggregatable server-side percentiles (RED latency). Summary for accurate per-instance quantiles you won't aggregate.
- *Follow-up: Why can't you average summary p99s?* Quantiles aren't linearly combinable; you'd need the raw distribution. Histograms keep bucket counts you *can* sum.
- *Follow-up: How does a histogram compute p99?* `histogram_quantile(0.99, sum by(le)(rate(_bucket[5m])))` interpolates within the bucket containing the 99th-percentile rank.
- *Follow-up: What hurts histogram accuracy?* Coarse buckets around your SLO; pick boundaries near the values you care about (or use native histograms).

**Q3. What is cardinality and why is it dangerous? (senior-signal)**
Cardinality is the count of distinct time series ≈ product of label-value counts × instances. Each active series costs head memory + index. Unbounded labels (user IDs, URLs, error messages) cause explosions that OOM Prometheus or destroy query speed. Mitigate by bounding label values, dropping bad labels via relabeling, setting `sample_limit`/`label_limit`, and monitoring `head_series` + `/api/v1/status/tsdb`.
- *Follow-up: Emergency fix for a live explosion?* `metric_relabel_configs` `labeldrop`/`drop` the offender; series age out at retention; then fix code.
- *Follow-up: How estimate before shipping a label?* Multiply expected distinct values across labels × instances.
- *Follow-up: When is high cardinality unavoidable and what then?* High-cardinality analytics belong in logs/columnar stores or sampled traces, not Prometheus.

**Q4. Walk me through a scrape end-to-end.**
SD produces targets with meta labels → `relabel_configs` keep/drop/rewrite → HTTP GET `/metrics` with timeout → parse exposition → `metric_relabel_configs` → staleness markers for vanished series → append to TSDB with scrape timestamp + synthetic `up`/`scrape_*`.
- *Follow-up: Where do `job`/`instance` come from?* `job` from scrape config; `instance` from the target address (relabelable).
- *Follow-up: What's `honor_labels`?* Whether exposed labels override target labels (true for Pushgateway/federation).

**Q5. Explain `rate()` vs `irate()` vs `increase()`.**
`rate` = average per-second over the window (smooth, alert-friendly, handles resets). `irate` = instantaneous using last two samples (spiky, for fast graphs). `increase` = total delta over window (= rate × window, extrapolated).
- *Follow-up: Why can `increase` be fractional?* Extrapolation to window edges.
- *Follow-up: How does `rate` handle a counter reset?* Detects the drop and treats it as a reset, adding the pre-reset portion.
- *Follow-up: Minimum window?* ≥ ~4× scrape interval for ≥ 2 samples reliably.

**Q6. How do you build a latency SLO alert without paging on every blip? (senior-signal)**
Define an SLO (e.g., 99.9% success). Compute error ratio from histogram/counter rates. Use **multi-window, multi-burn-rate** alerts: page on fast burns (e.g., 14.4× over short windows) and ticket on slow burns; require both a short and long window to be true (`for:`) to avoid flapping.
- *Follow-up: Why two windows?* Short window = fast detection; long window = avoids firing on a transient spike.
- *Follow-up: Symptom vs cause?* Page on symptoms (user-facing SLO); ticket on causes.

**Q7. How does Prometheus store data on disk?**
In-memory head (~2h) + WAL for durability; samples compressed via delta-of-delta timestamps and XOR/Gorilla value encoding into chunks; every ~2h the head cuts an immutable block (chunks+index+meta); background compaction merges blocks; retention deletes old blocks. Inverted index enables label matching.
- *Follow-up: What's the WAL for?* Crash recovery of the head.
- *Follow-up: Why immutable blocks?* Simple compaction/retention and safe concurrent reads.

**Q8. Histogram quantile aggregation pitfall.**
You must `rate()` the buckets and `sum ... by (le, ...)` keeping `le` before `histogram_quantile`. Forgetting `le` or averaging precomputed quantiles gives wrong results.
- *Follow-up: How aggregate across instances correctly?* Sum the bucket *counts* (rates) by `le`, then quantile.

**Q9. When would you choose Thanos/Mimir over a bigger Prometheus? (senior-signal)**
When you need global query across many Prometheis, long-term cheap retention on object storage, multi-tenancy, or horizontal ingest scale beyond a single node's RAM. A single (HA) Prometheus is simpler and right until you hit those limits; scaling vertically has a ceiling (RAM/series, restart time).
- *Follow-up: Thanos vs Mimir?* Thanos = least-disruption sidecar+object-store+global query over existing Prometheis; Mimir = push-based, multi-tenant, built for very large scale.
- *Follow-up: What's the cost driver in managed Prometheus?* Active series and samples ingested — i.e., cardinality.

**Q10. How do you debug "Prometheus is using too much memory"?**
Check `prometheus_tsdb_head_series` and `/api/v1/status/tsdb`; run `topk(10, count by(__name__)({__name__=~".+"}))`; find the offending metric/label; `drop`/`labeldrop` via relabeling; set guards; fix instrumentation; consider sharding/remote-write.
- *Follow-up: What dominates memory — samples or series?* Active series (head + index), not sample volume.
- *Follow-up: Why slow restarts?* WAL/head replay scales with head size.

**Q11. Recording rules — why and how?**
Precompute expensive/reused expressions into new series (named `level:metric:op`) to speed dashboards/alerts and standardize aggregations. Rules in a group run sequentially so later rules can use earlier outputs.
- *Follow-up: Where do they run?* Rule manager on `evaluation_interval`.
- *Follow-up: Risk?* They add series (cardinality) and CPU; keep groups lean.

**Q12. What does the `up` metric tell you and how do you alert on a missing metric entirely?**
`up` = 1/0 per target per scrape (health). For a metric that should always exist, use `absent(metric)` or `absent_over_time(metric[10m])` to alert when it vanishes (e.g., exporter removed, relabel mis-drop).
- *Follow-up: Difference between `up==0` and `absent(up)`?* `up==0` = target discovered but scrape failed; `absent(up{...})` = target not even discovered.

---

## 11. Glossary

- **Active series** — a time series currently receiving samples (in the head). Drives memory.
- **Aggregation operator** — PromQL `sum/avg/min/max/count/topk/...` combining across series at one instant; controlled by `by()`/`without()`.
- **Alertmanager** — service that dedups, groups, silences, inhibits, and routes alerts to receivers.
- **Block** — immutable on-disk directory (~2h+) of chunks+index+meta produced from the head.
- **Burn rate** — speed of error-budget consumption relative to the rate that exactly exhausts it over the SLO window.
- **Cardinality** — number of distinct time series; the main scaling constraint.
- **cAdvisor** — exporter for per-container resource usage.
- **Chunk** — compressed run of samples for one series (delta-of-delta time, XOR value encoding).
- **CNCF** — Cloud Native Computing Foundation; hosts Prometheus, Kubernetes, etc.
- **Compaction** — merging small blocks into larger ones; applies deletions/dedup.
- **Counter** — monotonically increasing metric (resets at restart); rate it.
- **Cortex** — horizontally scalable, multi-tenant, push-based long-term Prometheus backend.
- **Exemplar** — a sample-attached pointer (e.g., trace_id) linking a metric to a specific example/trace.
- **Exporter** — a process that translates a system's stats into Prometheus exposition (node_exporter, blackbox_exporter, ...).
- **Exposition format** — the text (or Protobuf/OpenMetrics) `/metrics` body Prometheus parses.
- **External labels** — labels added to data leaving a server (federation/remote-write/alerts) to identify it.
- **Federation** — one Prometheus scraping selected (usually aggregated) series from another.
- **Gauge** — metric that goes up and down (current state).
- **Grafana** — visualization/dashboard tool querying Prometheus via PromQL.
- **Head** — in-memory recent data (~2h) plus inverted index.
- **Histogram** — observations bucketed into cumulative counters (`_bucket{le}`, `_sum`, `_count`); enables aggregatable percentiles.
- **`histogram_quantile`** — PromQL function computing a quantile from histogram bucket rates.
- **`honor_labels`** — whether exposed labels override target labels.
- **Instance** — conventional label for a specific target endpoint (`host:port`).
- **Inverted index** — maps label value → series IDs for fast matching.
- **`irate`/`rate`/`increase`** — instant rate / averaged per-second rate / windowed total for counters.
- **Job** — conventional label for the *kind* of target; set by scrape config.
- **Label** — key/value pair that (with the name) identifies a series; the dimension of the data model.
- **`le`** — "less than or equal"; the bucket-boundary label of histograms.
- **Lookback delta** — how far back (default 5m) an instant query reaches for a series' last sample.
- **Micrometer** — JVM metrics facade; has a Prometheus registry (used by Spring Boot).
- **Mimir** — Grafana's scalable multi-tenant Prometheus backend (Cortex lineage).
- **Native (sparse) histogram** — single-series, exponential-bucket histogram (experimental); low cardinality, high resolution.
- **node_exporter** — host-level metrics exporter.
- **OpenMetrics** — standardized successor of the exposition format (exemplars, etc.).
- **OpenTelemetry (OTel)** — cross-signal telemetry standard; metrics can flow to Prometheus.
- **Percentile/quantile (φ)** — value below which a fraction of observations fall (p99 = tail latency).
- **PromQL** — Prometheus's query language for time-series math.
- **Pull / Push** — monitor-initiated scrape vs target-initiated send.
- **Pushgateway** — gateway that holds metrics pushed by short-lived jobs for Prometheus to scrape.
- **Recording rule** — precomputed PromQL stored as new series (`level:metric:op`).
- **Relabeling** — rule engine to rewrite/keep/drop targets (`relabel_configs`) or series (`metric_relabel_configs`).
- **Remote write/read** — shipping/reading samples to/from external long-term stores.
- **SLI/SLO/SLA** — Service Level Indicator (a measured metric), Objective (target for it), Agreement (contractual SLO with consequences).
- **SRE** — Site Reliability Engineering; metrics-driven operations discipline.
- **Staleness marker** — NaN sentinel marking a series as ended when it disappears from scrapes.
- **Summary** — client-side quantile metric (`{quantile}`, `_sum`, `_count`); not aggregatable across instances.
- **Service discovery (SD)** — dynamic mechanism to find scrape targets (k8s, consul, file, ...).
- **Subquery** — PromQL `expr[range:step]` building a range vector from an instant expression.
- **Thanos** — sidecar + object-storage + global-query system for long-term, global Prometheus.
- **Time series** — sequence of `(timestamp, value)` samples sharing one identity.
- **TSDB** — Prometheus's local time-series database (head+WAL+blocks).
- **`up`** — synthetic 1/0 metric reporting scrape success per target.
- **USE method** — Utilization, Saturation, Errors (resource-centric monitoring).
- **RED method** — Rate, Errors, Duration (request-centric monitoring).
- **WAL** — Write-Ahead Log for crash recovery of the head.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Data model:** series = `name{labels}`; name is `__name__`; change any label → new series.
**Types:** counter (up only, `_total`, rate it) · gauge (up/down) · histogram (`_bucket{le}`,`_sum`,`_count`, aggregatable percentiles) · summary (`{quantile}`, NOT aggregatable) · native histogram (1 series, experimental).
**Scrape:** HTTP GET `/metrics`; default interval **15s**, timeout **10s**; injects `up`, `scrape_*`.
**Key defaults:** retention **15d**; lookback delta **5m**; eval interval **15s**; `query.max-samples` 50M.
**PromQL must-knows:**
- Rate: `sum by(uri)(rate(http_..._count[5m]))`
- Error ratio: `sum(rate(...{status=~"5.."}[5m])) / sum(rate(...[5m]))`
- p99: `histogram_quantile(0.99, sum by(le)(rate(..._bucket[5m])))`  ← keep `le`!
- Down: `up == 0`; Missing: `absent(metric)`
- Forecast: `predict_linear(free[1h], 4*3600) < 0`
- Top series: `topk(10, count by(__name__)({__name__=~".+"}))`
**Cardinality rules:** no IDs/URLs/emails/messages in labels; bound everything; set `sample_limit`/`label_limit`; watch `prometheus_tsdb_head_series` + `/api/v1/status/tsdb`.
**Decisions:** histogram for fleet percentiles; summary only per-instance; pull for services, Pushgateway only for batch; single/HA Prometheus until you need Thanos/Mimir for global+LTS+scale.
**Alerting:** page on symptoms (SLO burn), ticket on causes; use `for:` and multi-window burn rate to avoid flapping; Alertmanager dedups/groups/silences/routes.
**Storage:** head(~2h)+WAL → immutable blocks → compaction → retention; memory ∝ active series.
**JVM path:** Spring Boot Actuator + Micrometer Prometheus registry → `/actuator/prometheus`; enable `percentiles-histogram` for HTTP timing.

### 12.2 Self-test (no answers — recall actively)

1. Two scrapes return the same metric name but one adds a `region` label. How many series now exist, and why does that matter?
2. Write the PromQL for fleet-wide p95 request latency from a histogram, and explain every part — what breaks if you drop `le` from the `by()`?
3. A teammate proposes labeling a request counter with `customer_email` "so we can segment." Explain in numbers why you'll reject it and what you'd do instead.
4. Your nightly ETL job sometimes silently doesn't run. Design the metric + push mechanism + alert to catch it, and explain why a plain pull won't work.
5. Prometheus is OOMKilling. List the exact endpoints/queries you'd run, in order, and the emergency mitigation vs the real fix.
6. Explain `rate` vs `irate` vs `increase`, when you'd use each, and how counter resets are handled.
7. When would you migrate from a single HA Prometheus to Thanos vs Mimir, and what specifically changes about ingest and query?

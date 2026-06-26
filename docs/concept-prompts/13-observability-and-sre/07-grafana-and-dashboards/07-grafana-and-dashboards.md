# Grafana & Dashboards

> An exhaustive engineering-handbook chapter for senior backend developers (Java/JVM focus) who want to master Grafana and dashboarding from first principles through deep internals, production operation, and interview-level fluency.

---

## 1. Overview & where it fits

### What Grafana is

**Grafana** is an open-source (AGPLv3, with a commercial Grafana Enterprise/Cloud tier) **visualization and observability front-end**. It does not, by default, store any of your metrics, logs, or traces. Instead it is a *query-and-render layer* that connects to **data sources** (databases and APIs that hold telemetry) and turns the data they return into **dashboards** (collections of **panels**: graphs, tables, gauges, etc.), **alerts**, and **explorations**.

A useful one-line mental model: **Grafana is a browser-based "BI tool for time series," wired to whatever backends actually store your data.** You write queries against Prometheus, Loki, Tempo, Mimir, InfluxDB, Elasticsearch, a SQL database, CloudWatch, etc.; Grafana fans those queries out, normalizes the results into a common tabular structure (**data frames**), and paints them.

Terms used above, defined for a newcomer:

- **Telemetry**: the signals a running system emits about itself. The "three pillars" are **metrics** (numeric measurements over time, e.g., requests-per-second), **logs** (timestamped text/structured event records), and **traces** (records of how a single request flowed through many services).
- **Time series**: a sequence of `(timestamp, value)` pairs, usually labeled with key/value metadata (e.g., `http_requests_total{method="GET", status="200"}`). Most monitoring data is time series.
- **Data source**: a configured connection from Grafana to a backend (URL, credentials, query language). Grafana ships dozens of data-source plugins.
- **Panel**: the atomic visual unit on a dashboard — one query (or set of queries) rendered as one visualization.
- **Dashboard**: a saved arrangement of panels, plus variables, time range, and layout. Stored as JSON.

### The problem it solves

Raw telemetry is unusable by humans at scale. A Prometheus server might hold millions of active time series; a Loki cluster might ingest terabytes of logs daily. Engineers need to:

1. **See the current and historical state** of systems at a glance (is the service healthy right now? was it healthy at 3 a.m.?).
2. **Investigate incidents** — pivot from "error rate spiked" to "which endpoint, which host, which trace, which log line."
3. **Communicate** system behavior to on-call engineers, product owners, and execs with shared, durable artifacts.
4. **Define and watch SLOs** (Service Level Objectives — target reliability levels, e.g., "99.9% of requests succeed").

Grafana is the place all of these converge. Without it (or an equivalent), teams either query raw with `curl` and `promql` by hand (slow, not shareable, not real-time) or build bespoke dashboards per team (duplicated effort, inconsistent).

### When you reach for it

- You have one or more telemetry backends and need a **unified, shareable view** across them.
- You want **dashboards-as-code** so monitoring is versioned and reviewable like the rest of your infrastructure.
- You need **alerting** that can span multiple data sources, or you want a single alerting plane on top of heterogeneous backends.
- You want **correlation**: click a spike on a metrics graph and jump straight to the traces and logs behind it.

### When you might *not* reach for it (or use something alongside)

- If your vendor (Datadog, New Relic, Honeycomb, Dynatrace) already bundles storage + UI + alerting and you have no multi-backend or cost reason to separate them, you may not need Grafana at all.
- For ad-hoc, high-cardinality, "I don't know what I'm looking for" exploratory debugging, a query-first tool like Honeycomb or Grafana's own **Explore** mode and trace search can beat pre-built dashboards.
- Grafana is not an APM agent, not a profiler (though **Pyroscope** integration exists for continuous profiling), and not a storage system.

### The one-paragraph mental model

> Grafana is a stateless rendering and query orchestration layer. A dashboard is a JSON document describing a set of panels; each panel holds one or more queries written in the data source's native language (PromQL, LogQL, SQL, etc.). When you open a dashboard, the Grafana backend resolves **template variables**, substitutes them into the queries, sends those queries to the configured data sources over their APIs, receives results, converts everything into uniform **data frames**, applies **transformations** and **field overrides**, and the browser renders the frames as visualizations. Grafana itself stores only configuration (dashboards, data-source definitions, users, alert rules) in a small relational database; the actual telemetry stays in the backends.

---

## 2. Foundations from first principles

This section builds the vocabulary and the conceptual stack from zero. If you already know Prometheus deeply, you can skim, but the inline definitions are here so the rest of the chapter is self-contained.

### 2.1 The observability stack underneath Grafana

Grafana is the top of a stack. To reason about dashboards you must understand the layers below.

1. **Instrumentation**: code emits signals. In Java this is typically via **Micrometer** (a vendor-neutral metrics facade — think SLF4J but for metrics), **OpenTelemetry** (a cross-language standard for metrics, logs, and traces and the wire protocol **OTLP**), or older libraries (Dropwizard Metrics, Prometheus Java client).
2. **Collection/Transport**: an agent or library exposes or pushes the data. Prometheus **scrapes** (pulls over HTTP) a `/metrics` endpoint; OpenTelemetry **pushes** via the **OpenTelemetry Collector**; logs ship via **Promtail**/**Grafana Alloy**/**Fluent Bit**.
3. **Storage (the data sources)**:
   - **Prometheus**: a metrics TSDB (time-series database) and scraper. Stores numeric series locally with a powerful query language, **PromQL**.
   - **Grafana Mimir / Thanos / Cortex / VictoriaMetrics**: horizontally scalable, long-retention, multi-tenant Prometheus-compatible backends. They speak PromQL.
   - **Loki**: a log aggregation system, indexed by labels (not full-text by default), queried with **LogQL**.
   - **Tempo**: a distributed tracing backend; stores **spans** grouped into **traces**.
   - **InfluxDB**, **Graphite**, **Elasticsearch/OpenSearch**, **CloudWatch**, **Azure Monitor**, **Google Cloud Monitoring**, **SQL databases** (Postgres, MySQL, MSSQL), and many more.
4. **Visualization & alerting (Grafana)**: the subject of this chapter.

Definitions of the new terms:

- **TSDB (time-series database)**: a database optimized for storing and querying `(timestamp, value)` data with labels. It compresses heavily (delta-of-delta timestamp encoding, XOR float compression — the Gorilla/Facebook techniques) and indexes by label sets.
- **Scrape**: Prometheus periodically (default every 15s) issues an HTTP GET to a target's `/metrics` endpoint and parses the exposition format.
- **Span**: one unit of work in a trace (e.g., "DB query took 12ms"), with a start time, duration, and attributes. A **trace** is a tree of spans sharing a `trace_id`.
- **Label / tag / dimension**: a key/value attribute attached to a series or span (e.g., `region="us-east-1"`). The set of distinct label combinations is the **cardinality**.
- **Cardinality**: the number of distinct time series (unique label-set combinations). High cardinality (e.g., putting `user_id` in a label) is the number-one cause of metrics backend blowups.

### 2.2 What a metric actually is (Prometheus model)

Because Grafana dashboards in the JVM world are overwhelmingly Prometheus-backed, master the Prometheus data model.

- A **metric name** plus a set of **labels** identifies a **series**: `http_server_requests_seconds_count{uri="/api/v1/orders", method="POST", status="200"}`.
- **Metric types**:
  - **Counter**: monotonically increasing value (resets to 0 on process restart). Example: total requests served. You almost never graph a counter directly; you graph its **rate** (per-second derivative).
  - **Gauge**: a value that can go up or down. Example: current memory used, number of in-flight requests, queue depth.
  - **Histogram**: samples observations into **buckets** plus a `_sum` and `_count`. Example: request latency. Buckets are cumulative (`le="0.1"`, `le="0.5"`, `le="1"`, `le="+Inf"`). Lets you compute **quantiles** server-side via `histogram_quantile`.
  - **Summary**: like a histogram but quantiles are computed client-side at instrumentation time (cannot be aggregated across instances). Generally prefer histograms.
- **Exposition format**: plain text Prometheus exposes/ingests:
  ```
  # HELP http_requests_total Total HTTP requests
  # TYPE http_requests_total counter
  http_requests_total{method="GET",status="200"} 1027
  ```

New terms:

- **Quantile / percentile**: a value below which a given fraction of observations fall. p99 latency = the latency under which 99% of requests complete. Quantiles are what users actually feel; averages hide the tail.
- **Rate**: in PromQL, `rate(counter[5m])` computes the per-second average increase over a 5-minute sliding window, correctly handling counter resets.

### 2.3 PromQL in 5 minutes (because dashboards are mostly queries)

PromQL (Prometheus Query Language) is what most JVM-stack panels run. The essentials:

- **Instant vector**: a set of series each with a single value at one timestamp: `http_requests_total`.
- **Range vector**: a set of series each with a *range* of values over a window: `http_requests_total[5m]`. Range vectors are inputs to functions like `rate`.
- **Scalar**: a single number.
- Core functions: `rate()`, `irate()` (instant rate, last two points), `increase()` (total increase over window), `sum()`, `avg()`, `max()`, `histogram_quantile()`, `topk()`, `rate(...)` combined with `by (label)` / `without (label)` aggregation.

Canonical RED queries (explained in §3 and §5):

```promql
# Request rate (per second), summed across instances, broken out by status
sum(rate(http_server_requests_seconds_count[5m])) by (status)

# Error ratio
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  /
sum(rate(http_server_requests_seconds_count[5m]))

# p99 latency from a histogram
histogram_quantile(0.99,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
```

The `=~` is a regex matcher; `{status=~"5.."}` matches any 5xx status. `by (le)` keeps the bucket boundary label needed by `histogram_quantile`.

### 2.4 The Grafana object model

Memorize this hierarchy — every advanced topic refers back to it.

```
Organization (tenant)
└── Folder
    └── Dashboard (a JSON document)
        ├── time range + refresh interval
        ├── Template variables ($var)
        ├── Annotations (event overlays)
        ├── Links
        └── Panels[]
            ├── one or more Targets (queries) → a Data source
            ├── Visualization type (timeseries, table, gauge, ...)
            ├── Field config / overrides (units, thresholds, colors)
            ├── Transformations (post-query reshaping)
            └── (optional) panel-level data source / mixed
```

- **Organization (org)**: a tenant boundary in Grafana — its own users, dashboards, data sources. Most companies use one org plus folder-level RBAC.
- **Folder**: a container for dashboards used for organization and permissions.
- **Annotation**: a marker on a time axis ("deploy at 14:32", "incident started"). Can be manual, from a query, or from an alert firing.
- **Transformation**: a Grafana-side (post-query) operation on data frames — join, filter, rename, compute fields, reduce to a single value — without changing the upstream query.
- **Field config / override**: per-field display settings (unit = seconds, decimals = 2, thresholds = red above 0.5), optionally overriding specific series by name/regex.

### 2.5 Data frames: Grafana's universal currency

Internally, *every* query result becomes a **data frame**: a columnar table with typed **fields** (columns) and metadata. A time-series result is a frame with a `time` field plus one or more numeric fields, each carrying labels. A logs result is a frame with `time`, `line`, and label fields. A table is just a frame.

Why this matters: transformations, alerting, and the rendering pipeline all operate on frames, *regardless of the originating data source*. This is what lets you, e.g., join a Prometheus result with a SQL result in one panel. Understanding "everything is a frame" demystifies a lot of Grafana behavior (§3, §7).

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle of rendering a dashboard, then the alerting evaluation loop, then provisioning.

### 3.1 Architecture: the moving parts

Grafana runs as a single Go binary (`grafana-server`, or `grafana server` in v10+). Logical components:

- **HTTP server** (the frontend assets + the backend API at `/api/...`).
- **Frontend** (React/TypeScript SPA running in the browser; uses the `@grafana/data`, `@grafana/ui`, `@grafana/runtime` packages).
- **Backend services**: dashboard service, data-source proxy, query service, alerting engine (**Grafana Alerting**, internally based on a fork of the Prometheus rule evaluator + the Alertmanager), provisioning service, plugin manager, SQL store.
- **Configuration database**: SQLite (default, fine for single-instance/dev), or MySQL/Postgres (required for HA). Stores dashboards, data sources, users, orgs, alert rules, annotations, API keys. **Not** telemetry.
- **Data-source plugins**: each data source is a plugin (backend Go plugin and/or frontend). The backend plugin knows how to take a query, call the upstream API, and return data frames.
- **Image renderer** (optional separate service, headless Chromium) for rendering panels to PNG for alerts/reports.

New term — **HA (high availability)**: running multiple Grafana instances behind a load balancer so one can fail without downtime. Requires a shared external database and (for alerting) coordination so rules aren't evaluated redundantly or alert state is shared.

### 3.2 Lifecycle of rendering a dashboard (step by step)

Trace what happens when an engineer opens `https://grafana.example.com/d/abc123/checkout-service?var-namespace=prod`:

1. **Auth & authorization.** The browser sends a session cookie or token. Grafana's auth middleware validates it (against its DB, or via OAuth/LDAP/SAML/proxy auth). RBAC checks whether the user can view the dashboard's folder.
2. **Dashboard load.** Frontend calls `GET /api/dashboards/uid/abc123`. Backend reads the JSON from the config DB, returns it. The JSON contains panels, variable definitions, default time range.
3. **Variable resolution (templating).** The frontend processes **template variables** in dependency order:
   - For each variable, if it is a **query variable** (its values come from a data source, e.g., "list all namespaces"), the frontend issues that query (`POST /api/ds/query`). Example: `label_values(kube_pod_info, namespace)`.
   - URL parameters (`?var-namespace=prod`) override defaults.
   - **Chained variables** resolve in order: `$cluster` first, then `$namespace` filtered by `$cluster`, then `$pod` filtered by both. Grafana builds a dependency graph and resolves topologically.
4. **Time range resolution.** The dashboard time range (`from`/`to`, e.g., `now-6h` to `now`) and the auto-refresh interval are established. `$__interval` and `$__rate_interval` (see §4) are computed from the time range and panel width.
5. **Query construction.** For each visible panel, the frontend takes each **target** (query), performs **interpolation**: substitutes `$variables`, `$__interval`, `$__range`, etc., into the raw query string.
6. **Query dispatch.** The frontend batches panel queries into `POST /api/ds/query` calls. The Grafana **backend** receives them, and for each query:
   - Routes to the correct **data-source plugin**.
   - The plugin (running in the Grafana backend process or as a separate gRPC plugin process) calls the upstream API. For Prometheus this is `GET /api/v1/query_range?query=...&start=...&end=...&step=...`.
   - **The data-source proxy** can also be used: requests go through Grafana so credentials never reach the browser. (Some data sources support `Browse`/direct mode, but server-side proxying is the norm and is required to keep secrets server-side.)
7. **Result normalization.** The plugin converts the upstream response into **data frames**.
8. **Server-side transformations / alerting reuse.** (Transformations primarily run client-side in the panel; some operations and all alert-rule evaluation run server-side.)
9. **Return to browser.** Frames are serialized (Arrow/JSON) back to the frontend.
10. **Client-side transformations.** The panel applies its **transformations** pipeline to the frames (e.g., "merge", "organize fields", "add field from calculation").
11. **Field config & overrides.** Units, decimals, thresholds, color schemes, value mappings, and per-series overrides are applied to the frames' field config.
12. **Rendering.** The visualization plugin (e.g., the `timeseries` panel built on the **uPlot** canvas library) draws the frames. Legends, tooltips, and axes are computed.
13. **Refresh loop.** If auto-refresh is on (e.g., `30s`), steps 4–12 repeat for visible panels. Off-screen panels in collapsed rows are *not* queried until expanded (lazy loading) — an important performance behavior.

Key internal behaviors to internalize:

- **`$__interval`** is dynamic: Grafana picks a step so you get roughly one data point per pixel of panel width, snapped to nice intervals. This prevents over-fetching. A wide panel over 7 days might use `step=10m`; the same query over 15 minutes might use `step=15s`.
- **Max data points**: each panel requests at most `maxDataPoints` (default ~ panel-width pixels). The data source down-samples to this. This is why a graph can look "smoother" when narrow.
- **Lazy loading & query caching**: identical queries within a short window can be served from Grafana's **query caching** (Enterprise/Cloud feature) or browser caching, reducing backend load.

### 3.3 The alerting evaluation loop (Grafana Alerting / "Unified Alerting")

Since Grafana 8 (GA in 9), **Grafana Alerting** is the default and unifies dashboard alerts and standalone alert rules. Internals:

1. **Alert rule definition.** A rule has one or more **queries** (data-source queries with their own time range, evaluated independently of any dashboard), an **expression** pipeline (reduce, math, threshold, classic condition), an **evaluation interval** (how often to run, e.g., every `1m`), and a **for** duration ("pending" period before firing).
2. **Scheduler.** The alerting **scheduler** ticks on the evaluation interval. To avoid stampedes, rules in the same **group** evaluate sequentially; groups are spread out.
3. **Evaluation.** For each rule, Grafana runs its queries against the data source(s) **server-side**, produces frames, runs the expression pipeline:
   - **Reduce**: collapse a time series to a single number per series (e.g., `last`, `mean`, `max`).
   - **Math**: arithmetic/logical expression (`$A > 0.05`).
   - **Threshold/Classic condition**: produce a boolean per series.
   - Result: each series resolves to **Normal**, **Alerting**, **NoData**, or **Error**.
4. **State machine.** Per series (identified by its label set), the rule transitions through states:
   - `Normal` → `Pending` (condition true, but `for` not yet elapsed) → `Alerting` (firing) → back to `Normal` when condition clears.
   - `NoData` and `Error` have configurable handling (treat as alerting, normal, or keep last state).
5. **State storage.** Alert instance states persist in the config DB so restarts don't lose firing state (and so HA instances share it).
6. **Notification routing.** Firing alerts become **alert instances** with labels. They are sent to the embedded **Alertmanager** (or an external Prometheus Alertmanager). The Alertmanager applies:
   - **Notification policies** (routing tree by label matchers) → choose **contact points** (email, Slack, PagerDuty, OpsGenie, webhook, …).
   - **Grouping** (batch related alerts into one notification by label, e.g., group by `alertname, cluster`).
   - **Throttling**: `group_wait` (wait to batch initial alerts), `group_interval` (min time between notifications for a group with new alerts), `repeat_interval` (re-notify for still-firing alerts).
   - **Silences** (mute by matcher for a time window) and **mute timings** (e.g., mute non-critical alerts outside business hours).
   - **Inhibition** (suppress alert X while alert Y is firing).
7. **HA coordination.** Multiple Grafana instances form an alerting cluster (gossip protocol, like Prometheus Alertmanager's mesh) so notifications aren't duplicated and silences propagate.

New terms:

- **Alertmanager**: the component (originally from Prometheus) that takes fired alerts and decides who to notify, how to group/dedupe/throttle/silence them. Grafana embeds its own implementation.
- **Contact point**: a destination for notifications (a Slack webhook, a PagerDuty integration key, an email address).
- **Notification policy**: a routing tree matching alert labels to contact points and timing settings.
- **Gossip protocol**: a peer-to-peer protocol where nodes periodically exchange state with random peers so the whole cluster converges on shared state without a central coordinator.

### 3.4 Provisioning / dashboards-as-code internals

Grafana can load configuration from disk at startup and on a watch interval, instead of (or in addition to) the UI:

1. **Provisioning files** live under `conf/provisioning/` (path set by `[paths] provisioning`). Subfolders: `datasources/`, `dashboards/`, `alerting/`, `plugins/`, `notifiers/`.
2. **Datasource provisioning** (`datasources/*.yaml`): declaratively defines data sources. On start, Grafana upserts them into the DB.
3. **Dashboard provisioning** (`dashboards/*.yaml`): points at a folder of dashboard JSON files. A **file-based provider** watches that directory (`updateIntervalSeconds`, default 10s) and imports/updates dashboards. Provisioned dashboards are (by default) **read-only in the UI** to prevent drift, unless `allowUiUpdates: true`.
4. **Alerting provisioning** (`alerting/*.yaml`): rule groups, contact points, notification policies, templates, mute timings as YAML/JSON.
5. **API & Terraform path**: alternatively, the **Grafana HTTP API** and the **Grafana Terraform provider** (`grafana/grafana`) manage the same objects (dashboards, data sources, folders, alert rules, teams, RBAC) as Terraform resources, storing state and enabling code review.

We cover the concrete files and commands in §4 and §5.

---

## 4. The complete toolkit

This section enumerates panel types, query/templating constructs, configuration, CLI, APIs, and IaC tooling. Defaults are noted; version-specific items are flagged.

### 4.1 Panel (visualization) types

| Panel type | What it shows | Use when | Notes / gotchas |
|---|---|---|---|
| **Time series** | Lines/bars/points over time | The default for any metric over time (RED graphs, resource usage) | Replaced the old "Graph" panel in Grafana 8. Built on uPlot; fast. |
| **Stat** | One big number + optional sparkline | Single current KPI (current QPS, error %, uptime) | Use thresholds for color. Choose reduce calc (last, mean). |
| **Gauge** | Radial gauge vs thresholds | Bounded value with min/max (CPU %, SLO budget %) | Good for "fill level" intuition; weak for trends. |
| **Bar gauge** | Horizontal/vertical bars vs thresholds | Many bounded values (per-pod memory, top-N) | More compact than gauges for lists. |
| **Table** | Tabular data | Per-row detail (top endpoints, current alerts, inventory) | Powerful with transformations + cell value mappings + data links. |
| **Bar chart** | Categorical bars (not time) | Comparing discrete categories | Needs non-time-series shaped frame. |
| **Pie chart** | Proportions of a whole | Composition snapshots (rarely ideal; avoid for time trends) | Hard to read precise values; use sparingly. |
| **Histogram** | Distribution of values | Latency/size distributions client-side | Different from a Prometheus histogram metric. |
| **Heatmap** | Density over time (x=time, y=bucket, color=count) | Latency distributions over time; reveals multimodality | Pairs perfectly with Prometheus histogram buckets. |
| **State timeline** | Discrete state over time (up/down, status) | Health states, deploy states, feature flags | Great for "what state was this in?" |
| **Status history** | Grid of discrete states | Many entities' state history | Like state timeline for many series. |
| **Logs** | Log lines | Loki/Elasticsearch log panels | Supports live tailing, dedup, log details. |
| **Trace / Traces** | Trace waterfall / span list | Tempo traces; trace detail | Used with TraceQL and "Trace to logs/metrics." |
| **Node graph** | Service/dependency graphs | Service maps from traces | Requires nodes+edges frames. |
| **Geomap** | Data on a map | Geo-distributed metrics | Needs lat/long fields. |
| **Canvas** | Free-form layout with elements | Custom diagrams, NOC wallboards | Powerful but bespoke; harder to maintain. |
| **Text** | Markdown/HTML | Documentation, runbook links inside a dashboard | Put context and links here. |
| **Dashboard list / Alert list / Annotations list** | Lists | Navigation, alert overviews | Useful on landing dashboards. |
| **News** | RSS feed | Rarely used | — |

Adjacent term — **uPlot**: a tiny, very fast HTML5 canvas charting library Grafana uses for the time-series panel; it's why modern Grafana can render thousands of points smoothly.

### 4.2 Template variable types

| Variable type | Source of values | Typical use |
|---|---|---|
| **Query** | A data-source query (`label_values(...)`, `metrics(...)`, SQL `SELECT DISTINCT`) | Populate dropdowns from live data (namespaces, pods, hosts) |
| **Custom** | Comma-separated literal list you type | Fixed choices (e.g., `prod,staging,dev`) |
| **Constant** | A single hidden value | Inject a fixed value (often via provisioning) |
| **Datasource** | List of data sources of a type | Switch a dashboard between Prometheus instances/clusters |
| **Interval** | List of durations | Let the user pick the rate window (`1m,5m,1h`) → use as `$interval` |
| **Text box** | Free text input | Ad-hoc filters (a user ID to grep) |
| **Ad hoc filters** | Auto key/value filters applied to all queries of a data source | Powerful exploratory filtering without editing queries |

Built-in/global variables (always available):

| Variable | Meaning |
|---|---|
| `$__interval` | Auto step size based on time range / panel width |
| `$__interval_ms` | Same, in milliseconds |
| `$__rate_interval` | Interval guaranteed ≥ 4× scrape interval; **use this inside `rate()`** to avoid gaps |
| `$__range` | The current dashboard time range as a duration (`6h`) |
| `$__range_s` / `$__range_ms` | Range in seconds/ms |
| `$__from` / `$__to` | Start/end epoch ms of the time range |
| `$__dashboard`, `$__org`, `$__user` | Context metadata |
| `$__name`, `$__field`, `$__series` | Available in some panel/value contexts |
| `$timeFilter` / `$__timeFilter()` | Inserts the time range into SQL/Influx queries |

Why `$__rate_interval` matters (version-relevant, Grafana 7.2+): if you use `rate(metric[$__interval])` and the interval drops below ~2× the scrape interval, `rate` can return empty results. `$__rate_interval` is computed as roughly `max($__interval + scrape_interval, 4 * scrape_interval)`, eliminating those gaps. **Rule: use `$__rate_interval` inside `rate()`/`increase()`; use `$__interval` for step/resolution elsewhere.**

### 4.3 Selected configuration flags (`grafana.ini` / env vars)

Env-var form is `GF_<SECTION>_<KEY>` (uppercased). Common knobs:

| Setting | Section | Default | Purpose |
|---|---|---|---|
| `http_port` | `[server]` | `3000` | Listen port |
| `root_url` | `[server]` | `%(protocol)s://%(domain)s:%(http_port)s/` | External URL (needed behind proxy) |
| `type` (db) | `[database]` | `sqlite3` | Config DB: `sqlite3`/`mysql`/`postgres` (HA needs mysql/postgres) |
| `provisioning` | `[paths]` | `conf/provisioning` | Provisioning dir |
| `allow_sign_up` | `[users]` | `false` (Cloud) | Self sign-up |
| `auto_assign_org_role` | `[users]` | `Viewer` | Default role |
| `min_refresh_interval` | `[dashboards]` | `5s` | Floor on auto-refresh to protect backends |
| `concurrent_query_limit` | `[dataproxy]` | — | Cap concurrent upstream queries |
| `timeout` | `[dataproxy]` | `30` (s) | Data source proxy timeout |
| `evaluation_timeout` | `[unified_alerting]` | `30s` | Per-rule eval timeout |
| `min_interval` | `[unified_alerting]` | `10s` | Minimum alert eval interval |
| `enabled` | `[unified_alerting]` | `true` (v9+) | Use Grafana (unified) alerting |
| `reporting_enabled` | `[analytics]` | `true` | Phone-home usage stats |

Always check your exact Grafana version's docs for defaults; these have shifted across releases.

### 4.4 grafana-cli & server commands

| Command | Purpose |
|---|---|
| `grafana cli plugins install <id>` | Install a plugin |
| `grafana cli plugins list-remote` | List installable plugins |
| `grafana cli plugins update-all` | Update plugins |
| `grafana cli admin reset-admin-password <pw>` | Reset admin password |
| `grafana server --config <path> --homepath <path>` | Run the server |
| `grafana cli --pluginsDir <dir> ...` | Operate on a specific plugins dir |

(In Grafana 10+, the unified `grafana` binary subsumes `grafana-server`/`grafana-cli`; older docs use the split binaries.)

### 4.5 HTTP API (key endpoints)

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/dashboards/db` | POST | Create/update a dashboard (send its JSON) |
| `/api/dashboards/uid/:uid` | GET/DELETE | Fetch/delete by UID |
| `/api/search?query=` | GET | Search dashboards/folders |
| `/api/folders` | GET/POST | Manage folders |
| `/api/datasources` | GET/POST | Manage data sources |
| `/api/ds/query` | POST | Run data-source queries (used by panels) |
| `/api/v1/provisioning/alert-rules` | GET/POST | Manage alert rules (provisioning API) |
| `/api/alertmanager/grafana/config/api/v1/alerts` | POST | Manage Alertmanager config (contact points, policies) |
| `/api/annotations` | POST | Create annotations (e.g., from CI on deploy) |
| `/api/admin/...` | various | Admin/user/org management |
| `/api/health`, `/api/health` | GET | Health check |

Auth: **service account tokens** (preferred, since Grafana 9; replace legacy API keys), Basic auth, or session cookies.

### 4.6 Infrastructure-as-code tooling

| Tool | What it manages | Mechanism |
|---|---|---|
| **File provisioning** | Data sources, dashboards (JSON), alerting | YAML files Grafana reads at boot + watch |
| **Grafana Terraform provider** (`grafana/grafana`) | Dashboards, folders, data sources, alert rules, contact points, teams, RBAC, SLOs (Cloud) | Terraform resources via the HTTP API |
| **Grafonnet** | Dashboards as code | A **Jsonnet** library producing dashboard JSON |
| **grafana-foundation-sdk** | Dashboards/alerts as code | Typed builders in Go/Python/TS/Java that emit JSON |
| **grizzly (`grr`)** | Dashboards/datasources/rules | A CLI (`grr apply/diff`) that reconciles Jsonnet/YAML to a Grafana instance |
| **grafana-operator** (Kubernetes) | Dashboards, data sources, folders | Custom Resources (`GrafanaDashboard`, etc.) reconciled by an operator |
| **mixtool / monitoring-mixins** | Reusable dashboards+alerts+rules | "Mixins": Jsonnet bundles per system (e.g., kubernetes-mixin) |

New terms:

- **Jsonnet**: a data-templating language that compiles to JSON; lets you build dashboards with variables, functions, and composition instead of hand-editing huge JSON files.
- **Mixin**: a community-maintained Jsonnet bundle that emits a complete set of dashboards, recording rules, and alerts for a system (Kubernetes, Node Exporter, etc.).
- **Grafana Operator**: a Kubernetes controller that reconciles dashboard/data-source Custom Resources into a Grafana instance — GitOps-native.

---

## 5. Code examples by use case

These span distinct scenarios. Java/Micrometer for instrumentation; PromQL/JSON/YAML/HCL/Jsonnet for Grafana.

### 5.1 Instrumenting a Spring Boot service so its dashboard has signal (Java)

A dashboard is only as good as the metrics behind it. With Spring Boot + Micrometer + Prometheus:

```java
// build.gradle (key deps)
//   implementation 'org.springframework.boot:spring-boot-starter-actuator'
//   implementation 'io.micrometer:micrometer-registry-prometheus'

// application.yml
//   management:
//     endpoints.web.exposure.include: health,info,prometheus   # expose /actuator/prometheus
//     metrics.distribution.percentiles-histogram.http.server.requests: true  # emit histogram buckets
//     metrics.distribution.slo.http.server.requests: 50ms,100ms,200ms,500ms,1s  # explicit SLO buckets

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final MeterRegistry registry;

    public OrderService(MeterRegistry registry) {
        this.registry = registry;
    }

    // A custom counter for a business event. Keep label cardinality LOW:
    // 'result' has a small fixed set of values; never put orderId/userId here.
    @Timed(value = "order.process", histogram = true) // emits order_process_seconds_* with buckets
    public void processOrder(Order order) {
        try {
            // ... business logic ...
            registry.counter("orders.placed.total", "channel", order.channel(), "result", "success").increment();
        } catch (PaymentDeclinedException e) {
            registry.counter("orders.placed.total", "channel", order.channel(), "result", "declined").increment();
            throw e;
        }
    }
}
```

Why this matters for dashboards: enabling `percentiles-histogram` makes Micrometer emit `http_server_requests_seconds_bucket{le=...}` so Grafana can compute *aggregatable* p50/p95/p99 with `histogram_quantile`. The explicit `slo` buckets ensure your SLO threshold (e.g., 200 ms) is an exact bucket boundary — otherwise `histogram_quantile` interpolates and your SLO math is fuzzy.

The cardinality warning is the single most important production rule: a label like `userId` with millions of values multiplies your series count by millions and can OOM Prometheus.

### 5.2 The canonical RED dashboard (PromQL queries for the 3 panels)

**RED** = **R**ate, **E**rrors, **D**uration — the method (popularized by Tom Wilkie) for *request-driven* services. **USE** = **U**tilization, **S**aturation, **E**rrors (Brendan Gregg's method) for *resources* (CPU, disk, NICs).

RED panels for the order service:

```promql
-- Panel 1: Request rate (RPS) by endpoint (Time series, unit: req/s)
sum by (uri) (
  rate(http_server_requests_seconds_count{job="order-service", namespace="$namespace"}[$__rate_interval])
)

-- Panel 2: Error rate as a ratio (Time series or Stat, unit: percent 0-1)
sum(rate(http_server_requests_seconds_count{job="order-service", namespace="$namespace", status=~"5.."}[$__rate_interval]))
/
sum(rate(http_server_requests_seconds_count{job="order-service", namespace="$namespace"}[$__rate_interval]))

-- Panel 3: Latency p50/p95/p99 (Time series, unit: seconds). Three queries, one panel.
histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{job="order-service", namespace="$namespace"}[$__rate_interval])))
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="order-service", namespace="$namespace"}[$__rate_interval])))
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job="order-service", namespace="$namespace"}[$__rate_interval])))
```

Notes: `$namespace` is a template variable; `$__rate_interval` avoids `rate()` gaps; `sum by (le)` aggregates buckets across instances before quantile computation (you must aggregate the buckets, *not* the quantiles).

USE panels for the underlying nodes (Node Exporter metrics):

```promql
-- Utilization: CPU busy %
100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[$__rate_interval])))

-- Saturation: run-queue / load relative to cores
avg by (instance) (node_load1) / count by (instance) (node_cpu_seconds_total{mode="idle"})

-- Errors: NIC receive errors per second
sum by (instance) (rate(node_network_receive_errs_total[$__rate_interval]))
```

### 5.3 A SLO + error-budget burn-rate panel and alert (advanced PromQL)

An **SLO** (Service Level Objective) sets a target, e.g., 99.9% of requests succeed over 30 days. The **error budget** is the allowed failure (0.1%). **Burn rate** is how fast you're consuming that budget relative to a uniform spend; burn rate `> 1` means you'll exhaust the budget early.

```promql
-- 1h burn rate for a 99.9% availability SLO (multi-window alerting input)
(
  sum(rate(http_server_requests_seconds_count{job="order-service", status=~"5.."}[1h]))
  /
  sum(rate(http_server_requests_seconds_count{job="order-service"}[1h]))
) / (1 - 0.999)   -- divide error ratio by the budget (0.001) -> burn rate
```

Google SRE multi-window multi-burn-rate alerting fires when *both* a fast window (e.g., 1h burn > 14.4) and a slow window (5m or 6h) agree — fast to catch big outages, slow to avoid flapping on blips. The corresponding Prometheus/Grafana alert expression (using recording rules `job:slo_errors_per_request:ratio_rate1h`, etc.) is:

```promql
(
  job:slo_errors_per_request:ratio_rate1h{job="order-service"} > (14.4 * 0.001)
  and
  job:slo_errors_per_request:ratio_rate5m{job="order-service"} > (14.4 * 0.001)
)
```

Adjacent term — **recording rule**: a Prometheus rule that precomputes an expensive query at scrape-like intervals and stores the result as a new series, so dashboards/alerts read a cheap precomputed metric instead of recomputing heavy aggregations every time.

### 5.4 Exemplars: linking a metrics graph to a trace

An **exemplar** is a single sampled data point attached to a histogram bucket that carries a `trace_id` (and other labels). It lets you click a latency spike and jump to an example trace that experienced it — the bridge from "p99 is bad" to "*this exact request* shows why."

Setup chain:
1. Instrument with OpenTelemetry/Micrometer so histograms carry exemplars (Micrometer + Spring Boot supports exemplars when an OTel/Brave tracer is present; Prometheus must have `--enable-feature=exemplar-storage`).
2. In Grafana's Prometheus data source, enable **Exemplars** and map the `traceID` field to your **Tempo** data source.

Prometheus data source provisioning with exemplar → trace linking:

```yaml
# conf/provisioning/datasources/prometheus.yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    uid: prom-main                       # stable UID referenced elsewhere
    url: http://prometheus:9090
    jsonData:
      httpMethod: POST
      exemplarTraceIdDestinations:
        - name: traceID                  # the exemplar label holding the trace id
          datasourceUid: tempo-main      # jump to this Tempo data source
  - name: Tempo
    type: tempo
    uid: tempo-main
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:                    # from a trace, jump to correlated logs in Loki
        datasourceUid: loki-main
        filterByTraceID: true
      tracesToMetrics:                   # from a trace, jump to RED metrics
        datasourceUid: prom-main
```

Now exemplar dots appear on latency panels; clicking one opens the trace in a split Explore view; from the trace you can pivot to logs and metrics. This is the "**correlate signals**" workflow that turns dashboards from passive into investigative.

### 5.5 Dashboards-as-code: provisioning a dashboard JSON + folder

```yaml
# conf/provisioning/dashboards/services.yaml
apiVersion: 1
providers:
  - name: 'service-dashboards'
    orgId: 1
    folder: 'Services'                 # created if missing
    type: file
    disableDeletion: true              # don't delete dashboards removed from disk
    allowUiUpdates: false              # read-only in UI -> prevents drift
    updateIntervalSeconds: 30          # re-scan dir every 30s
    options:
      path: /var/lib/grafana/dashboards/services
      foldersFromFilesStructure: true  # subdirs become folders
```

A trimmed dashboard JSON (the kind you'd generate, not hand-write):

```json
{
  "uid": "order-service-red",
  "title": "Order Service — RED",
  "tags": ["service", "red", "owner:payments"],
  "timezone": "browser",
  "schemaVersion": 39,
  "refresh": "30s",
  "time": { "from": "now-6h", "to": "now" },
  "templating": {
    "list": [
      {
        "name": "namespace",
        "type": "query",
        "datasource": { "type": "prometheus", "uid": "prom-main" },
        "query": "label_values(http_server_requests_seconds_count{job=\"order-service\"}, namespace)",
        "refresh": 2,
        "includeAll": false
      }
    ]
  },
  "panels": [
    {
      "type": "timeseries",
      "title": "Request rate by endpoint",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prom-main" },
      "fieldConfig": { "defaults": { "unit": "reqps" } },
      "targets": [
        {
          "expr": "sum by (uri) (rate(http_server_requests_seconds_count{job=\"order-service\", namespace=\"$namespace\"}[$__rate_interval]))",
          "legendFormat": "{{uri}}"
        }
      ]
    }
  ]
}
```

`schemaVersion` is Grafana's internal dashboard-format version; Grafana migrates older versions forward on load. Hardcode it to match your Grafana to avoid surprises; the importer upgrades it anyway.

### 5.6 Dashboards-as-code with the Grafana Terraform provider

```hcl
terraform {
  required_providers {
    grafana = { source = "grafana/grafana", version = "~> 3.0" }
  }
}

provider "grafana" {
  url  = "https://grafana.example.com"
  auth = var.grafana_service_account_token   # service account token, not a UI password
}

resource "grafana_folder" "services" {
  title = "Services"
}

resource "grafana_dashboard" "order_red" {
  folder      = grafana_folder.services.id
  config_json = file("${path.module}/dashboards/order-service-red.json")
  # overwrite=true lets TF reconcile drift; keep the JSON as the source of truth
  overwrite   = true
}

# Alerting as code: a folder + rule group + rule
resource "grafana_rule_group" "order_slo" {
  name             = "order-slo"
  folder_uid       = grafana_folder.services.uid
  interval_seconds = 60
  rule {
    name      = "OrderServiceHighErrorBudgetBurn"
    condition = "C"
    for       = "5m"
    data {
      ref_id         = "A"
      datasource_uid = "prom-main"
      relative_time_range { from = 3600, to = 0 }
      model = jsonencode({
        expr = "(sum(rate(http_server_requests_seconds_count{job=\"order-service\",status=~\"5..\"}[1h])) / sum(rate(http_server_requests_seconds_count{job=\"order-service\"}[1h]))) / 0.001"
        instant = true
      })
    }
    data {
      ref_id         = "C"
      datasource_uid = "__expr__"          # Grafana expression data source
      model = jsonencode({ type = "threshold", expression = "A", conditions = [{ evaluator = { type = "gt", params = [14.4] } }] })
    }
  }
}
```

This is the "real" production pattern: dashboards and alerts live in Git, go through PR review, and are applied by CI. Anyone editing in the UI sees their change reverted on the next `terraform apply` (drift control).

### 5.7 Dashboards-as-code with Grafonnet (Jsonnet)

```jsonnet
// order-red.jsonnet
local g = import 'github.com/grafana/grafonnet/gen/grafonnet-latest/main.libsonnet';
local dashboard = g.dashboard;
local timeSeries = g.panel.timeSeries;
local prometheus = g.query.prometheus;

local namespaceVar = g.dashboard.variable.query.new('namespace', 'label_values(http_server_requests_seconds_count, namespace)')
  + g.dashboard.variable.query.withDatasource('prometheus', 'prom-main');

dashboard.new('Order Service — RED')
+ dashboard.withUid('order-service-red')
+ dashboard.withTags(['service', 'red'])
+ dashboard.withVariables([namespaceVar])
+ dashboard.withPanels([
  timeSeries.new('Request rate by endpoint')
  + timeSeries.panelOptions.withGridPos(8, 12, 0, 0)
  + timeSeries.queryOptions.withTargets([
    prometheus.new('prom-main',
      'sum by (uri) (rate(http_server_requests_seconds_count{job="order-service", namespace="$namespace"}[$__rate_interval]))')
    + prometheus.withLegendFormat('{{uri}}'),
  ])
  + timeSeries.standardOptions.withUnit('reqps'),
])
```

Compile with `jsonnet -J vendor order-red.jsonnet > order-red.json`, then provision or push via Terraform/grizzly. The win: reuse a `redRow(service)` function across 50 services instead of copy-pasting 50 JSON files.

### 5.8 Using transformations to join two data sources in one table

Scenario: a table that shows, per pod, its CPU (from Prometheus) and its container image (from a SQL inventory DB). Set the panel data source to **-- Mixed --**, add two queries (A: Prometheus, B: Postgres), then a transformation chain:

- **Outer join** (transformation `Join by field`) on `pod`.
- **Organize fields** to rename/reorder columns.
- **Add field from calculation** if you need a derived column.

This is impossible at the query layer (different databases) but trivial with Grafana's frame-level transformations — a direct payoff of the "everything is a data frame" design (§2.5).

### 5.9 Annotating deploys from CI (close the change-correlation loop)

Most incidents follow a change. Overlaying deploys on graphs is the cheapest way to spot "it broke right after the deploy."

```bash
# In CI, after a successful deploy, POST an annotation to Grafana.
curl -sS -X POST "https://grafana.example.com/api/annotations" \
  -H "Authorization: Bearer ${GRAFANA_SA_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"time\": $(date +%s000),
    \"tags\": [\"deploy\", \"order-service\", \"${GIT_SHA}\"],
    \"text\": \"Deployed order-service ${GIT_SHA} to prod\"
  }"
```

Then add an **annotation query** to the dashboard filtering on tags `deploy` + `order-service`, so vertical deploy markers appear on every panel.

---

## 6. Implementation concerns & best practices

### 6.1 What makes a *good* dashboard

A dashboard is good when it **answers a specific question for a specific audience** and **tells a story top-down**. Concrete principles:

- **One dashboard, one purpose, one audience.** "Order Service — On-call overview" is good. "All metrics for everything" is not.
- **Top-down narrative (SLO → symptom → cause).** First row: SLO/health and the RED golden signals (what users feel). Next rows: dependencies (DB, cache, downstream services). Lower rows: resource USE metrics (CPU, memory, GC). The eye should travel from "are users affected?" to "why?"
- **Every panel answers a question.** If you can't state the question a panel answers ("Are we serving errors?"), delete it. Title panels as the *question* or the *answer*, not the metric name.
- **Consistent time alignment.** All panels share the dashboard time range so spikes line up visually across panels — this visual correlation is half the diagnostic value.
- **Sensible units and thresholds.** Set units (seconds, bytes, reqps, percent). Add threshold lines for SLOs. A red band at the SLO boundary is worth a thousand words.
- **Annotations for deploys/incidents** so change-correlation is one glance away.
- **Links to the next step**: data links from panels to drill-down dashboards, runbooks (in a Text panel), logs, and traces.

### 6.2 The "wall of graphs" anti-pattern (and how to avoid it)

The most common failure: a dashboard with 40 near-identical line graphs, every metric the exporter emits, no hierarchy. On-call can't find signal. Fixes:

- **Curate, don't dump.** Auto-generated "all metrics" dashboards are reference material, not incident tools. Build a *focused* on-call dashboard by hand (or from a mixin) with the few panels that matter.
- **Use rows and collapse them.** Top row always visible (RED + SLO). Detail rows collapsed by default (also a performance win — collapsed panels don't query).
- **Stat/gauge for current state; time series for trends.** Don't make people read a trend graph to learn the current value.
- **Aggregate first, then allow drill-down via variables.** Show the service in aggregate; use a `$instance`/`$pod` variable to zoom in, rather than 30 per-instance panels.
- **Kill redundancy.** If two panels say the same thing, delete one.
- **Repeating panels/rows** (`repeat` by a multi-value variable) generate N panels from one definition — use this for "one row per region" instead of hand-cloning.

### 6.3 Performance

Dashboards can hammer your backends. Causes and mitigations:

- **Too many panels × short refresh.** 50 panels at 5s refresh = thousands of queries/min. Mitigate: raise refresh (30s–1m), `min_refresh_interval`, collapse rows (lazy load), reduce panel count.
- **Expensive queries.** High-cardinality aggregations recomputed every refresh. Mitigate: **recording rules** to precompute, narrower time ranges, `$__rate_interval` to avoid over-resolution.
- **`maxDataPoints` too high.** Forces fine `step`, fetching more points than pixels. Keep defaults; don't crank `maxDataPoints`.
- **Heavy template variable queries.** `label_values()` over huge series sets is slow and runs on every load. Cache where possible; scope variable queries with matchers; consider shorter variable refresh policies.
- **Query caching** (Enterprise/Cloud) and the **data source proxy timeout** tuning.

### 6.4 Correctness & concurrency (the subtle bugs)

- **Aggregating quantiles is wrong.** You cannot average p99s across instances. Aggregate the *buckets* with `sum by (le)` then apply `histogram_quantile`. Averaging precomputed quantiles is a classic, silently-wrong dashboard bug.
- **`rate()` window vs scrape interval.** Window must be ≥ ~2× scrape interval or you get gaps; use `$__rate_interval`.
- **Counter resets** are handled by `rate`/`increase`; never compute raw deltas on counters yourself.
- **Mismatched time ranges in alerts vs dashboards.** An alert rule has its *own* relative time range; a graph showing `now-6h` and an alert evaluating `[5m]` can look inconsistent — that's expected, not a bug.
- **Timezone confusion.** Dashboard timezone (`browser`/`UTC`/explicit) affects how on-call across regions read timestamps. Standardize on UTC for incident dashboards.
- **Mixed data source frame alignment.** Joining frames with different time steps needs alignment transformations; otherwise joins drop rows.

### 6.5 Security

- **Never expose secrets to the browser.** Use the **server-side data source proxy** so credentials stay in Grafana. Avoid direct/browser access mode for authenticated sources.
- **Service accounts + tokens**, scoped and rotated, instead of long-lived admin API keys.
- **RBAC and folder permissions.** Restrict who can edit data sources (a malicious/buggy SQL data source query can be an SSRF or data-exfiltration vector).
- **Disable risky query features** where not needed; sanitize SQL data source queries (parameterize; beware template variables enabling injection into raw SQL).
- **Auth integration**: OAuth/OIDC/SAML/LDAP, with `auto_assign_org_role` set conservatively (Viewer).
- **AGPL/licensing**: be aware Grafana OSS is AGPLv3; Enterprise features (RBAC fine-grained, reporting, query caching, data source permissions) are licensed.
- **Anonymous access**: if enabled for public dashboards, scope it tightly; consider Grafana's **public dashboards** feature, which exposes a read-only snapshot.

### 6.6 Observability of Grafana itself

- Grafana exposes its own `/metrics` (Prometheus). Watch `grafana_http_request_duration_seconds`, alerting eval durations, data source query errors, datasource request latency.
- Watch upstream data source error rates and the alerting **state-history** (Loki-backed in Cloud) to debug flapping rules.
- Set up a "meta-monitoring" dashboard for Grafana + each data source's health.

### 6.7 Testing & CI for dashboards

- **Lint dashboard JSON** with tools (e.g., the `dashboard-linter` from Grafana, or mixin lint) to enforce: every panel has a title, units set, no hardcoded data source UIDs that don't exist, no missing variables.
- **Render-test** in CI: spin up Grafana in Docker, provision the dashboards, hit the render API or run a headless check that panels return data (catches broken queries before prod).
- **Schema/version pinning**: keep `schemaVersion` and Grafana version aligned across environments; test migrations when upgrading.
- **Alert rule unit tests**: Prometheus `promtool test rules` validates recording/alerting rules against synthetic series — invaluable for SLO burn-rate logic.

### 6.8 Cost

- Cost lives mostly in the *backends*, driven by **cardinality** and **retention**, not Grafana. A careless `group by (pod)` on a churny Kubernetes cluster creates millions of short-lived series.
- Grafana Cloud bills on active series / log volume / traces; dashboards that query huge ranges or run frequent alerts increase query cost.
- Mitigate with recording rules, label-drop relabeling, sensible retention tiers, and avoiding high-cardinality labels.

### 6.9 Production hardening checklist

- External Postgres/MySQL for HA; backups of the config DB (dashboards live here unless fully provisioned).
- All dashboards/datasources/alerts as code (Terraform/provisioning); UI edits read-only in prod.
- `min_refresh_interval` set; image renderer in a separate, resource-limited container.
- Alerting HA cluster configured; external Alertmanager optional for unification with Prometheus alerts.
- Health checks, resource limits, and SLOs *for Grafana itself*.

### 6.10 Anti-patterns to avoid (catalog)

| Anti-pattern | Why it hurts | Do instead |
|---|---|---|
| Wall of graphs | No signal under pressure | Curated top-down dashboard |
| Averaging percentiles | Statistically meaningless | Aggregate buckets, then quantile |
| Hardcoding instances/namespaces | Not reusable | Template variables |
| `rate(m[$__interval])` | Gaps at fine resolution | `$__rate_interval` |
| 5s refresh on 50 panels | Hammers backends | 30s–1m, collapse rows |
| UI-only dashboards | No review, drift, lost on DB loss | Dashboards-as-code |
| High-cardinality labels | Backend OOM, cost | Keep labels bounded |
| Mixing alerting logic in two systems with no convention | Duplicate/contradictory alerts | One alerting strategy (see §6.11) |
| One mega-dashboard for everyone | Serves no one | Per-audience dashboards |

### 6.11 Alerting in Grafana vs Prometheus (best-practice framing)

This is a frequent design decision; here is the substance (decision table in §8).

- **Prometheus alerting** (rules in `prometheus.yml`/rule files, sent to a standalone **Alertmanager**): evaluation lives *next to* the data, is fully code/GitOps-driven, has no UI dependency, and is the battle-tested standard for Prometheus-only shops. Limitation: each Prometheus alerts only on its own data; cross–data-source alerts aren't native.
- **Grafana alerting** (Unified Alerting): evaluates rules *in Grafana*, across *any* data source (Prometheus, Loki, SQL, CloudWatch, …), with a UI for rules/policies/silences, expression pipelines (reduce/math/threshold), and a single notification plane. Cost: evaluation load moves to Grafana; Grafana becomes a critical path for alerting (must be HA); historically the data model differed from Prometheus rules (now bridgeable).
- **Common hybrid**: keep latency-/SLO-critical, data-local alerts in Prometheus (recording + alerting rules, `promtool`-tested), and use Grafana alerting for cross-source/business alerts and for teams that want UI-managed rules. Route both into one Alertmanager so notification policy/silencing is unified.

Rule of thumb: **alert as close to the data as practical; centralize *notification* routing.**

---

## 7. Advanced topics & deep internals

### 7.1 The data-frame model in depth

A **data frame** is `{ name, refId, fields[], meta }`. Each **field** is `{ name, type (time|number|string|boolean), config (unit, decimals, thresholds, mappings, links), labels, values[] }`. Time-series frames come in two layouts:

- **Wide**: one `time` field + N value fields (one per series). Efficient for many series sharing timestamps.
- **Long** (a.k.a. tall): `time`, `value`, and label columns — rows are observations. SQL results are usually long; Grafana can convert long→wide via the "prepare time series" transformation.

Knowing the layout explains transformation behavior, alert "reduce" semantics, and why some panels need a reshape transformation to render.

### 7.2 Interpolation, escaping, and variable formats

When substituting variables, the format matters. Grafana supports format hints: `${var:raw}`, `${var:regex}`, `${var:pipe}`, `${var:csv}`, `${var:json}`, `${var:singlequote}`, `${var:sqlstring}`, `${var:percentencode}`. Example: a multi-value variable in a PromQL regex matcher needs `=~"${pod:regex}"`; in a SQL `IN (...)` clause use `${pod:singlequote}` or `${pod:sqlstring}`. Getting this wrong is a top cause of "works for one value, breaks for All/multi."

### 7.3 `$__interval` vs `$__rate_interval` vs `$__range` — exact behavior

- `$__interval` = `max(timeRange / maxDataPoints, minInterval)`, snapped to a "nice" duration. Lowering `maxDataPoints` or raising panel `minInterval` increases it.
- `$__rate_interval` ≈ `max($__interval + scrapeInterval, 4 × scrapeInterval)`; Grafana reads the data source's configured scrape interval (the Prometheus data source has a "Scrape interval" setting — keep it accurate, default `15s`).
- `$__range` = the full visible window as a duration; use for cumulative `increase(metric[$__range])` "total over the visible window" panels.

### 7.4 Exemplars, span metrics, and the metrics↔traces↔logs triangle

Beyond §5.4: **span metrics** (generated by the Tempo metrics-generator or the OTel Collector `spanmetrics` connector) turn traces into RED metrics automatically (`traces_spanmetrics_calls_total`, `..._latency_bucket`), so you get RED dashboards even for services you didn't manually instrument. The **service graph** (node graph panel) is likewise derived from spans. With **TraceQL** you query traces by attributes/duration. The full correlation triangle:

- Metrics → traces: **exemplars** (`exemplarTraceIdDestinations`).
- Traces → logs: **trace-to-logs** (match by `trace_id` in Loki).
- Logs → traces: a **derived field** in the Loki data source that regex-extracts `trace_id` and links to Tempo.
- Traces → metrics: **trace-to-metrics** (jump to RED for the span's service).

### 7.5 Heatmaps from native histograms

Classic Prometheus histograms have fixed buckets; **native (sparse) histograms** (Prometheus 2.40+, experimental) auto-scale buckets and are far more storage-efficient and accurate. Grafana's heatmap can render them. Flag this as version- and experimental-specific; production use is still maturing.

### 7.6 Provisioning precedence, drift, and the read-only model

Provisioned dashboards carry a `provisioned: true` flag; the UI shows them read-only with a "cannot be saved" banner unless `allowUiUpdates: true`. On each scan, the file provider compares file checksums and upserts changed dashboards. **Deletion behavior**: if `disableDeletion: false`, removing a JSON file deletes the dashboard. Data source provisioning is **idempotent by name/uid**; changing a UID creates a second data source (a common footgun) — pin UIDs and never change them.

### 7.7 Alerting expression pipeline internals

The expression engine (`__expr__` data source) chains node types: **query** (A,B,…) → **reduce** (series→scalar) → **resample** (align time steps) → **math** (`$A / $B`) → **threshold/classic**. The final referenced node's per-series boolean determines state. Subtle behaviors: **NoData** propagation (a reduce on an empty frame yields NoData unless configured), and **`for` + missing series** (a series disappearing can resolve or hold state depending on "keep firing" / missing-series handling, version-dependent).

### 7.8 Recording rules, mixins, and reuse at scale

For org-scale consistency, adopt **monitoring-mixins**: import `kubernetes-mixin`, `node-mixin`, etc., which output dashboards + recording rules + alerts as Jsonnet. You vendor them, override config (job labels, SLO targets), compile, and provision. This is how large fleets get consistent, maintainable dashboards instead of snowflakes.

### 7.9 Scaling Grafana

- HA: N stateless Grafana replicas + shared Postgres + alerting HA (gossip). Sticky sessions not strictly required (token auth), but image-render and alerting need coordination.
- Query load: front data sources with caching/recording rules; use Mimir/Thanos query frontends with their own caching and query splitting.
- Multi-tenant: orgs, or Grafana Cloud's tenant model; data source permissions (Enterprise).

### 7.10 Lesser-known features

- **Library panels**: define a panel once, reuse across dashboards; edit propagates.
- **Repeating rows/panels**: generate per-value copies from a multi-value variable.
- **Data links & correlations**: clickable links from a series/field to another dashboard/Explore with variables carried over; **Correlations** (config-level) define source→target field links org-wide.
- **Value mappings**: map `1→"UP"`, ranges→colors.
- **Panel inspector** (`Inspect → Query`/`Data`/`JSON`/`Panel JSON`): see the exact query sent, the raw frames, and the panel JSON — the single best debugging tool (§9).
- **Public dashboards** and **snapshots** (point-in-time shareable copies with data embedded).
- **Reporting** (Enterprise): scheduled PDF/PNG via the image renderer.

---

## 8. Tradeoffs & decision frameworks

### 8.1 RED vs USE vs Four Golden Signals

| Method | Signals | Best for | Origin |
|---|---|---|---|
| **RED** | Rate, Errors, Duration | Request-driven services (APIs, RPC) | Tom Wilkie (Weaveworks) |
| **USE** | Utilization, Saturation, Errors | Resources (CPU, disk, memory, NIC, queues) | Brendan Gregg |
| **Four Golden Signals** | Latency, Traffic, Errors, Saturation | General services (Google SRE) | Google SRE book |

Use-when: RED for the service layer, USE for the infra layer, Golden Signals when you want one superset. A good service dashboard uses RED up top and USE for its resources lower down.

### 8.2 Panel choice decision rules

- Current single value → **Stat** (with sparkline) or **Gauge** (if bounded with min/max).
- Trend over time → **Time series**.
- Distribution over time → **Heatmap**.
- Per-entity detail / top-N → **Table** or **Bar gauge**.
- Discrete states over time → **State timeline**.
- Composition (rarely) → **Pie/Bar chart** — prefer a table.
- Logs → **Logs**; traces → **Traces**; service map → **Node graph**.

### 8.3 Alerting: Grafana vs Prometheus vs hybrid

| Dimension | Prometheus + Alertmanager | Grafana Unified Alerting | Hybrid (recommended for many) |
|---|---|---|---|
| Evaluation location | Next to data, in Prometheus | In Grafana | Both |
| Cross–data-source rules | No | Yes | Yes (in Grafana) |
| GitOps maturity | Excellent (`promtool` tests) | Good (provisioning/Terraform) | Best of both |
| UI management | No | Yes | Yes (for Grafana rules) |
| Critical-path dependency | Prometheus only | Grafana must be HA | Spreads risk |
| Notification routing | Alertmanager | Embedded or external AM | Unify via one AM |
| Best when | Prometheus-only shop, infra alerts | Multi-source, UI-managed, business alerts | Large mixed orgs |

### 8.4 Dashboards-as-code tool choice

| Tool | Ergonomics | Reuse/composition | Best when |
|---|---|---|---|
| Raw JSON + file provisioning | Lowest | None | Tiny scale, quick start |
| Terraform provider | Medium (HCL + embedded JSON) | Folder/module reuse | Already Terraform-centric infra |
| Grafonnet (Jsonnet) | Steep but powerful | Excellent (functions, mixins) | Many similar dashboards at scale |
| foundation-sdk (Go/Java/TS/Py) | Familiar to devs | Good (typed builders) | Teams who prefer a real language |
| grafana-operator (K8s) | Declarative CRs | Good with Kustomize/Helm | GitOps Kubernetes shops |
| grizzly | CLI reconcile | Good with Jsonnet | Mixed Jsonnet/YAML workflows |

### 8.5 Storage backend choice (affects what your dashboards can do)

| Backend | PromQL? | Scale/retention | Notes |
|---|---|---|---|
| Prometheus | Yes | Single node, weeks | Simple, default |
| Mimir | Yes | Massive, multi-tenant | Grafana's horizontally scalable backend |
| Thanos | Yes | Long retention via object storage | Sidecar + store gateway model |
| Cortex | Yes | Multi-tenant (older) | Largely superseded by Mimir |
| VictoriaMetrics | MetricsQL (PromQL-ish) | Very efficient | Some PromQL differences |
| InfluxDB | Flux/InfluxQL | TSDB | Different query language |

---

## 9. Failure modes & debugging

### 9.1 "No data" / empty panel

Causes and the actual checks:
1. **Open Panel Inspector → Query.** See the exact query sent (with variables interpolated). Copy it into Explore / `promtool query instant`.
2. **Variable interpolated to empty/`All` regex** — multi-value variable in a non-regex matcher. Fix with `=~"${var:regex}"`.
3. **`rate()` window too small** → use `$__rate_interval`. Symptom: data appears at wide ranges, vanishes when you zoom in.
4. **Wrong metric name/labels** (instrumentation changed; e.g., Micrometer renamed `http_server_requests` across Spring Boot versions). Verify with `label_values`/metric browser.
5. **Time range outside data** (looking at `now-90d` with 15-day retention).
6. **Data source down / auth expired** — check data source "Test" and `/api/health`, Grafana logs for proxy errors.

### 9.2 Wrong-looking numbers

- **Averaged quantiles** (§6.4) — fix the query.
- **Counter vs rate confusion** — graphing a raw counter shows an ever-rising line; you want `rate`.
- **Unit mismatch** — seconds shown as "1" because unit not set; set field unit.
- **Aggregation level** — `sum` vs `avg` vs per-instance changes the meaning entirely; state the question.

### 9.3 Dashboard slow / Grafana under load

- Inspector shows per-query timing. Identify the expensive panel.
- Check backend: Prometheus `prometheus_engine_query_duration_seconds`, slow-query logs; reduce cardinality, add recording rules.
- Reduce refresh, collapse rows, lower `maxDataPoints`, narrow ranges.
- Grafana's own metrics: data source request latency/error counters.

### 9.4 Alerts not firing / flapping / duplicated

- **Not firing**: rule state in **Alerting → state** view; check evaluation interval vs `for`; check the expression pipeline output in the rule editor's "preview." Confirm the query returns data server-side (alert queries run independently of dashboard time range).
- **Flapping**: add `for`, use multi-window burn-rate logic, increase `for`/`keep_firing_for`.
- **Duplicated notifications**: HA cluster not configured (each replica notifies) — set up alerting HA / single Alertmanager.
- **No notification despite firing**: notification policy doesn't match the alert's labels, or a silence/mute timing is active, or contact-point integration failing — check the contact point test and notification logs.
- **NoData/Error storms**: configure NoData/Error handling; a brief data source blip set to "Alerting" pages everyone.

### 9.5 Provisioning gotchas

- Dashboard read-only and "can't save" → it's provisioned; edit the source file or set `allowUiUpdates`.
- Duplicate data sources after a config change → you changed the `uid`/`name`; restore the original UID.
- Dashboard deleted on file removal → `disableDeletion` was false.
- Variables don't resolve in provisioned dashboards → data source UID in the JSON doesn't match the provisioned UID.

### 9.6 Real-world incident patterns (composite, representative)

- **The 3 a.m. wall-of-graphs failure.** On-call opens the team dashboard during an outage and faces 60 graphs with no SLO panel. Time-to-diagnose balloons. Postmortem action: build a curated on-call dashboard, RED + SLO on top, runbook links, deploy annotations. This is the single most common dashboarding postmortem item.
- **Silent SLO drift from averaged percentiles.** A "p99 latency" panel actually averaged per-instance p99s; the real p99 was far worse. Users complained while the dashboard looked green. Fix: bucket aggregation + `histogram_quantile`; alert on the corrected query.
- **Cardinality explosion from a new label.** A developer added `customer_id` to a histogram; Prometheus OOM'd, dashboards went blank during a launch. Fix: drop the label via relabeling, add a cardinality alert (`prometheus_tsdb_head_series`), code-review metric changes.
- **`rate()` gaps after a scrape-interval change.** Ops changed scrape interval to 30s; panels using `rate(m[15s])`/`$__interval` started showing gaps at fine zoom. Fix: `$__rate_interval` and correct the data source's configured scrape interval.
- **Duplicate pages after Grafana HA rollout.** Two Grafana replicas each notified PagerDuty. Fix: configure alerting HA peering (or route to one external Alertmanager).

---

## 10. Interview drill

Each question has a model answer plus deep-probe follow-ups. Senior-signal questions are marked **[SS]**.

**Q1. What does Grafana actually store, and what does it not?**
Model answer: Grafana stores *configuration* — dashboards (JSON), data-source definitions, users/orgs, folders, alert rules, annotations, service-account tokens — in a relational config DB (SQLite by default; Postgres/MySQL for HA). It does **not** store telemetry; metrics/logs/traces live in the data sources (Prometheus, Loki, Tempo, etc.). Grafana is a stateless query/render layer (modulo its config DB).
- Probe: *Why does HA require an external DB?* Because SQLite isn't safely shared across replicas; you need Postgres/MySQL plus alerting HA coordination.
- Probe: *What happens to your dashboards if the config DB is lost and you don't provision?* They're gone — hence dashboards-as-code + backups.
- Probe: *Where do alert states persist?* In the config DB, so firing state survives restarts and is shared in HA.

**Q2. Walk me through what happens when I open a dashboard with a `$namespace` variable.**
Model answer: Auth/RBAC → load dashboard JSON → resolve template variables in dependency order (query variables fire data-source queries; URL params override) → establish time range and compute `$__interval`/`$__rate_interval` → interpolate variables into each panel's query → dispatch via `/api/ds/query` through the server-side data-source proxy to each backend → normalize results to data frames → return → client applies transformations, field config/overrides → render via uPlot. Collapsed rows are lazy-loaded.
- Probe: *Where is the query actually executed?* In the backend data-source plugin calling the upstream API (server-side proxy), not the browser.
- Probe: *Why `$__rate_interval` vs `$__interval`?* To guarantee the `rate()` window is ≥ ~4× scrape interval and avoid gaps.

**Q3. Explain RED and USE and when you'd use each.**
Model answer: RED = Rate, Errors, Duration — for request-driven services. USE = Utilization, Saturation, Errors — for resources. A good service dashboard puts RED at the top (user-facing symptoms) and USE lower down (resource causes). Four Golden Signals (latency, traffic, errors, saturation) is the general superset.
- Probe: *Where does saturation come from for a thread pool?* Queue depth / active vs max threads (e.g., Tomcat `tomcat_threads_busy` vs `_config_max`).
- Probe: *Why is duration usually a histogram, not a gauge?* So you can aggregate buckets across instances and compute true quantiles.

**Q4. [SS] Would you put your alerting in Prometheus or Grafana? Justify.**
Model answer: Alert as close to the data as practical, centralize notification routing. Prometheus+Alertmanager for data-local, SLO-critical, GitOps-tested rules (`promtool`), especially Prometheus-only shops; Grafana Unified Alerting for cross–data-source and UI-managed/business alerts. Common hybrid: both evaluate, but route into a single Alertmanager so silencing/grouping is unified. Tradeoff: Grafana alerting makes Grafana a critical path (must be HA), while Prometheus alerting can't span sources.
- Probe: *What's the risk of Grafana-side alerting?* Grafana becomes critical infra; evaluation load and a single-point dependency.
- Probe: *How do you unit-test alert logic?* `promtool test rules` with synthetic series; rule preview in Grafana.
- Probe: *How do you avoid duplicate pages in HA?* Alerting HA gossip or a single external Alertmanager.

**Q5. Why is averaging p99 across instances wrong, and what's correct?**
Model answer: Percentiles aren't linearly aggregable; `avg(p99_i)` has no statistical meaning and usually *understates* the true tail. Correct: aggregate the histogram **buckets** (`sum by (le) (rate(..._bucket[w]))`) then apply `histogram_quantile`. Requires histogram metrics (Micrometer `percentiles-histogram`), and accuracy depends on bucket boundaries.
- Probe: *What if you only have a Summary, not a Histogram?* You can't aggregate quantiles across instances; that's a key reason to prefer histograms.
- Probe: *How do bucket boundaries affect accuracy?* `histogram_quantile` interpolates within a bucket; coarse buckets near your SLO threshold degrade accuracy — set explicit SLO buckets.

**Q6. What is the "wall of graphs" anti-pattern and how do you fix it?**
Model answer: A dump of every metric as near-identical graphs with no hierarchy, useless during incidents. Fix: curated, top-down, single-purpose, single-audience dashboards; RED+SLO on top; collapsed detail rows; stats for current values; variables for drill-down; repeating rows instead of cloning; delete redundancy. Auto-generated "all metrics" dashboards are reference, not incident tools.
- Probe: *How do collapsed rows help performance?* Off-screen/collapsed panels aren't queried until expanded.
- Probe: *How do you scale a per-service dashboard to 100 services?* Templating + repeating + dashboards-as-code (Grafonnet/mixins), not 100 copies.

**Q7. Explain exemplars and the metrics↔traces↔logs correlation flow.**
Model answer: An exemplar is a sampled point on a histogram bucket carrying a `trace_id`. With `exemplarTraceIdDestinations` mapping the exemplar label to a Tempo data source, clicking a latency spike opens the example trace; trace-to-logs jumps to Loki by `trace_id`; a Loki derived field links logs back to traces; trace-to-metrics jumps to RED. This turns dashboards investigative.
- Probe: *What's required upstream?* Exemplar-capable instrumentation + Prometheus `--enable-feature=exemplar-storage`.
- Probe: *What are span metrics?* RED metrics auto-derived from traces by Tempo/OTel `spanmetrics`, giving RED dashboards without manual instrumentation.

**Q8. How do you do dashboards-as-code, and why?**
Model answer: Why: versioning, code review, no drift, reproducibility, disaster recovery. How: file provisioning (YAML pointing at JSON, read-only in UI), the Grafana Terraform provider (dashboards/datasources/alerts as resources via the API), Grafonnet/foundation-sdk for composition, grafana-operator for GitOps Kubernetes, mixins for reusable bundles. Pin data source UIDs; make UI read-only in prod.
- Probe: *What's the UID footgun?* Changing a data source UID creates a duplicate and breaks dashboard references; never change UIDs.
- Probe: *How do you prevent UI drift?* `allowUiUpdates: false` or Terraform `overwrite=true` reverting changes.

**Q9. [SS] Design a monitoring dashboard for a checkout service. What's on it, in what order, and why?**
Model answer: Top row: SLO/availability stat + error-budget burn gauge + RED (request rate by endpoint, error ratio with SLO threshold band, latency p50/p95/p99). Next row: critical dependencies — payment gateway success/latency, DB query latency/connections, cache hit rate, downstream service RED. Then resources (USE): JVM heap/GC pauses, CPU, thread-pool saturation, container memory vs limit. Deploy annotations across all panels; runbook links in a Text panel; data links to per-pod drill-down and to logs/traces. Template variables: `$namespace`, `$cluster`, optional `$instance`. Single audience (on-call), single purpose (is checkout healthy and if not, why), top-down story.
- Probe: *Where do you set thresholds?* SLO boundary as a colored threshold on the error/latency panels.
- Probe: *How do you keep it from becoming a wall of graphs as the team adds metrics?* Governance: detail goes in collapsed rows or separate drill-down dashboards; the overview stays curated.
- Probe: *How do you make it reusable across services?* Grafonnet function/mixin parameterized by service/job label.

**Q10. [SS] Your dashboard is green but customers report errors. Diagnose the systemic causes.**
Model answer: Classic observability gaps: (a) averaged percentiles hiding the tail; (b) wrong aggregation (sum hiding a per-region failure); (c) sampling/missing instrumentation (errors not counted, e.g., client-side timeouts the server never sees); (d) metric covers only successful path; (e) SLI doesn't match user experience (measuring server latency, not end-to-end). Fixes: correct quantile math, slice by the failing dimension, ensure error paths increment counters, define SLIs from the user's perspective (synthetic/RUM), alert on burn rate of the *right* SLI.
- Probe: *How would synthetic monitoring help here?* It measures the user-facing path independent of internal metrics, catching gaps between SLI and reality.
- Probe: *How do exemplars/traces help?* Pivot from "no errors in metrics" to actual slow/failing traces to find uninstrumented failure.

**Q11. What's the difference between `$__interval`, `$__rate_interval`, and `$__range`?**
Model answer: `$__interval` = auto step ≈ timeRange/maxDataPoints (one point per pixel), for resolution. `$__rate_interval` ≈ max(`$__interval`+scrapeInterval, 4×scrapeInterval), for `rate()`/`increase()` to avoid gaps. `$__range` = the whole visible window as a duration, for cumulative `increase(m[$__range])`.
- Probe: *Where does Grafana get the scrape interval for `$__rate_interval`?* From the Prometheus data source's configured scrape interval (default 15s) — keep it accurate.
- Probe: *What breaks if you use `$__interval` inside `rate()`?* Gaps when zoomed in and the window drops below ~2× scrape interval.

**Q12. How do you operate Grafana in production (HA, security, observability of Grafana itself)?**
Model answer: External Postgres/MySQL + multiple stateless replicas behind a LB; alerting HA gossip (or external Alertmanager); image renderer as a separate limited container; server-side data source proxy so secrets never reach the browser; service-account tokens (scoped, rotated) over admin keys; RBAC + folder permissions; OIDC/SAML auth; dashboards/datasources/alerts as code with UI read-only; back up the config DB; meta-monitor Grafana via its `/metrics` (HTTP latency, datasource errors, alerting eval duration) and set SLOs for Grafana.
- Probe: *Why is the data source proxy a security control?* Credentials stay server-side; direct/browser mode would leak them.
- Probe: *What Grafana self-metrics page you first?* Datasource request errors/latency and alerting evaluation failures.

---

## 11. Glossary

- **AGPLv3**: the license of Grafana OSS; network use triggers source-availability obligations.
- **Alertmanager**: component that routes, groups, dedupes, silences, and throttles fired alerts into notifications.
- **Annotation**: a time-axis marker (deploy, incident) overlaid on panels.
- **Burn rate**: speed of consuming an error budget relative to a uniform spend; >1 means early exhaustion.
- **Cardinality**: number of distinct time series (unique label combinations); high cardinality is the main backend-scaling risk.
- **Classic condition / threshold**: alert-expression node producing a boolean per series.
- **Contact point**: a notification destination (Slack, PagerDuty, email, webhook).
- **Correlation (Grafana)**: org-level config linking a source field to a target query/dashboard.
- **Counter**: monotonically increasing metric; graph its `rate`.
- **Data frame**: Grafana's universal columnar result structure (typed fields + metadata).
- **Data link**: clickable link from a panel/field to another dashboard, Explore, or URL, carrying variables.
- **Data source**: a configured backend connection Grafana queries.
- **Derived field (Loki)**: regex-extracted field (e.g., `trace_id`) that links logs to other data sources.
- **Error budget**: allowed unreliability under an SLO (1 − target).
- **Exemplar**: a sampled histogram-bucket data point carrying a `trace_id`, linking metrics to traces.
- **Explore**: Grafana's ad-hoc query/investigation mode (not a saved dashboard).
- **Field / field config / override**: a frame column and its display settings; overrides target specific series.
- **Folder**: container for dashboards used for organization and permissions.
- **Four Golden Signals**: latency, traffic, errors, saturation (Google SRE).
- **Gauge (metric)**: a value that goes up and down. **Gauge (panel)**: a radial visualization vs thresholds.
- **Gossip protocol**: peer-to-peer state-sync used by alerting HA.
- **Grafana Alerting / Unified Alerting**: Grafana's built-in cross-source alerting engine (default since v9).
- **Grafonnet**: Jsonnet library for building dashboards as code.
- **HA (high availability)**: redundant Grafana instances + shared DB + alerting coordination.
- **Heatmap**: density visualization (time × bucket × color), ideal for latency histograms.
- **Histogram (metric)**: bucketed observation counts enabling server-side quantiles. **Histogram (panel)**: distribution visualization.
- **`histogram_quantile`**: PromQL function computing a quantile from histogram buckets.
- **`$__interval` / `$__rate_interval` / `$__range`**: built-in variables for step resolution, `rate()` windows, and full-range durations.
- **Jsonnet**: data-templating language compiling to JSON.
- **Label**: key/value metadata on a series/span.
- **Library panel**: a reusable panel definition shared across dashboards.
- **LogQL**: Loki's query language.
- **Loki**: Grafana's log aggregation backend, indexed by labels.
- **`maxDataPoints`**: per-panel cap on returned points (≈ panel width).
- **Micrometer**: JVM metrics facade (Spring Boot's default).
- **Mimir**: Grafana's horizontally scalable, multi-tenant Prometheus-compatible metrics backend.
- **Mixin**: Jsonnet bundle producing dashboards+rules+alerts for a system.
- **Notification policy**: routing tree matching alert labels to contact points + timing.
- **OpenTelemetry (OTel) / OTLP**: cross-language telemetry standard and wire protocol.
- **Panel**: one visualization (query + viz type + config) on a dashboard.
- **Provisioning**: loading data sources/dashboards/alerts from disk YAML at boot + watch.
- **PromQL**: Prometheus query language.
- **Prometheus**: pull-based metrics TSDB + query engine.
- **Quantile / percentile**: value below which a fraction of observations fall (p99 = 99th percentile).
- **`rate()` / `increase()`**: PromQL functions for per-second/total change over a window, reset-aware.
- **RBAC**: role-based access control.
- **Recording rule**: precomputed Prometheus query stored as a new series.
- **RED**: Rate, Errors, Duration (request-driven service method).
- **Repeating panel/row**: auto-generated copies per multi-value variable value.
- **Saturation**: how "full" a resource is (queue depth, run queue).
- **Schema version**: Grafana's internal dashboard-JSON format version; migrated forward on load.
- **Service account / token**: non-human identity + scoped token for API/IaC access.
- **SLI / SLO / SLA**: indicator (measured), objective (target), agreement (contract).
- **Span / trace / `trace_id`**: a unit of work / tree of spans / shared correlation id.
- **Span metrics**: RED metrics auto-derived from traces.
- **Stat (panel)**: big-number current-value visualization.
- **State machine (alerting)**: Normal → Pending → Alerting (+ NoData/Error) per series.
- **State timeline**: discrete-state-over-time visualization.
- **Summary (metric)**: client-side quantiles; not aggregatable across instances.
- **Template variable**: a dashboard `$variable` substituted into queries.
- **Tempo**: Grafana's distributed tracing backend.
- **Terraform provider (grafana/grafana)**: manages Grafana objects as IaC.
- **Thanos / Cortex / VictoriaMetrics**: scalable/long-retention Prometheus-compatible backends.
- **Time series (data / panel)**: `(timestamp, value)` data / the default trend visualization.
- **TraceQL**: Tempo's trace query language.
- **Transformation**: client-side data-frame reshaping (join/filter/calc/reduce).
- **TSDB**: time-series database.
- **uPlot**: fast canvas charting library underlying the time-series panel.
- **USE**: Utilization, Saturation, Errors (resource method).
- **Utilization**: fraction of a resource in use over time.
- **Variable format hints**: `${var:regex|csv|json|singlequote|sqlstring|...}` interpolation modes.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Mental model**: Grafana = stateless query/render layer over data sources; dashboards are JSON; queries run server-side via the data-source proxy; results become data frames; panels render frames.

**Default numbers**: HTTP port `3000`; Prometheus scrape `15s`; dashboard `min_refresh_interval` `5s`; alert `min_interval` `10s`, `evaluation_timeout` `30s`; data proxy timeout `30s`; provisioning watch ~`10s`.

**Golden methods**: RED (Rate/Errors/Duration → services) on top; USE (Utilization/Saturation/Errors → resources) below; Four Golden Signals = the superset.

**Query rules**: use `$__rate_interval` inside `rate()`/`increase()`; `$__interval` for resolution; `$__range` for cumulative-over-window. Aggregate histogram **buckets** then `histogram_quantile` — never average percentiles. Keep label cardinality bounded (no `user_id`/`order_id` labels).

**Good dashboard**: one purpose, one audience, top-down SLO→symptom→cause, every panel answers a question, units+thresholds set, deploy annotations, links to drill-down/logs/traces. Avoid the wall of graphs (curate, collapse, stats for current values, variables for drill-down, repeating rows).

**Correlation triangle**: metrics→traces via exemplars (`exemplarTraceIdDestinations`); traces→logs via trace-to-logs; logs→traces via Loki derived field; traces→metrics via trace-to-metrics; span metrics give RED from traces.

**Dashboards-as-code**: file provisioning (read-only UI) | Terraform provider | Grafonnet/foundation-sdk | grafana-operator | mixins. Pin data source UIDs; never change them; keep UI read-only in prod.

**Alerting**: Grafana Unified = cross-source, UI-managed, Grafana on critical path; Prometheus+Alertmanager = data-local, `promtool`-tested. Rule of thumb: alert near the data, centralize notification routing. HA: external DB + alerting gossip / single Alertmanager.

**Top debugging tool**: Panel Inspector → Query/Data/JSON. Top failure causes: empty-on-zoom (`rate` window), averaged percentiles, cardinality explosion, UID mismatch, duplicate pages from HA without coordination.

### 12.2 Self-test (no answers — for active recall)

1. Trace, step by step, everything that happens between clicking a dashboard link with `?var-namespace=prod` and seeing the first rendered line — name where the query actually executes and why secrets never reach the browser.
2. Write the three RED PromQL queries for a Spring Boot service, then explain exactly why you used `$__rate_interval` and `sum by (le)`, and what goes wrong if you average the per-instance p99 instead.
3. You must alert on an SLO that combines a Prometheus error ratio with a Loki log-pattern count. Where do you put the alert, how do you structure the expression pipeline, and how do you avoid duplicate pages in an HA Grafana cluster?
4. Design a checkout-service dashboard from scratch: list every panel, its visualization type, its query intent, its unit/threshold, and the row order — then justify the order in terms of SLO→symptom→cause.
5. Convert a hand-built dashboard into dashboards-as-code three different ways (provisioning, Terraform, Grafonnet), and explain the UID footgun and how you prevent UI drift in production.
6. Your dashboard is all green but customers report failures. Enumerate at least four systemic causes and the exact fix and verification step for each.
7. Explain the full metrics↔traces↔logs correlation chain for one slow request: which configuration links each hop, and what upstream instrumentation each hop requires.

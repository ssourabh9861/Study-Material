# SLI, SLO, SLA & Error Budgets

> An engineering-handbook chapter for senior Java/JVM backend developers who want to *fully master* service reliability targets — from first principles to deep internals, production operation, and interview-grade fluency.

---

## 1. Overview & where it fits

### What it is

**SLI, SLO, SLA, and error budgets** are the quantitative vocabulary of **reliability engineering**. They turn the fuzzy question "is the service healthy enough?" into arithmetic you can alert on, report on, and make release decisions with.

- **SLI — Service Level Indicator:** a *measured number* describing one dimension of service quality, almost always expressed as a **ratio of good events to total valid events** over a window. Example: "proportion of HTTP requests served in under 300 ms" = 99.2% over the last 28 days.
- **SLO — Service Level Objective:** a *target* for an SLI that you, the engineering org, commit to internally. Example: "99.9% of requests succeed over a rolling 28-day window."
- **SLA — Service Level Agreement:** a *contract* with an external party (a customer) that contains an SLO-like target **plus consequences** (refunds, service credits, penalties) when you miss it.
- **Error budget:** the *allowed amount of unreliability* implied by an SLO. If your SLO is 99.9% success, your error budget is the remaining **0.1%** of events you are permitted to fail. It is the bridge between reliability and velocity: as long as budget remains, you ship features; when it is exhausted, you slow down and fix reliability.

A compact mental model: **the SLI is the speedometer, the SLO is the speed limit you set for yourself, the error budget is how much you can still speed before you get pulled over, and the SLA is the ticket you pay if a *cop* (your customer) catches you over a *different, looser* limit.**

### The problem it solves

Without these constructs, reliability discussions degenerate into one of two failure modes:

1. **"Everything must be up 100% of the time."** This is impossible (more on the math below), infinitely expensive, and it paralyzes shipping. Every change is a risk, so the safest behavior is to never change anything — which is itself a reliability risk (security patches, scaling, dependency rot).
2. **"It feels slow / it feels broken."** Subjective, unfalsifiable, unprioritizable. Two engineers can argue forever because there is no shared number.

SLOs replace both with a **negotiated, measurable reliability target derived from what users actually need**, and the error budget gives **both reliability work and feature work a shared currency** so the eternal dev-vs-ops tension becomes a data-driven decision rather than a political one.

### When you reach for it

- You operate a service with real users (internal or external) and need to decide *how reliable is reliable enough*.
- You need to **prioritize** reliability work against features without endless debate.
- You need **paging alerts that fire on user-visible pain**, not on every CPU blip.
- You sign **contracts** promising availability and need to set internal targets stricter than the contract.
- You run **postmortems** and want an objective measure of customer impact.

> **Adjacent term — SRE (Site Reliability Engineering):** a discipline, originated and popularized by Google, that applies software-engineering practices to operations problems. SLIs/SLOs/error budgets are its central reliability primitives. The canonical references are the free Google "SRE Book" (*Site Reliability Engineering*, 2016) and "SRE Workbook" (2018). Throughout this chapter I flag where guidance is Google-canonical vs. industry-common vs. vendor-specific.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Define every term as it appears.

### 2.1 Events, the unit of everything

Almost all good SLIs are defined over **events**. An event is a discrete thing that either succeeded or failed against some criterion. The most common event is an **HTTP request/response**, but events can also be:

- A message consumed from a queue (e.g., Kafka) and processed.
- A batch job run.
- A row written to a database.
- A page load (a "user journey" event).

**Why events and not raw uptime?** Because users do not experience "uptime"; they experience *individual interactions*. A server can be "up" (process running, responds to a health-check ping) while serving 50% of real requests as `500 Internal Server Error`. Counting *good events / valid events* captures the user's reality; counting "is the box pingable" does not.

> **Adjacent term — health check:** a lightweight endpoint (e.g., `/healthz`, `/livez`, `/readyz` in Kubernetes) that a load balancer or orchestrator probes to decide whether to send traffic to an instance. A passing health check does **not** imply the SLO is met; it is a coarse liveness/readiness signal, not a quality measurement.

### 2.2 The SLI as a ratio

The strongest definition of an SLI, and the one the SRE Workbook recommends, is:

```
SLI = (count of good events) / (count of valid events) × 100%
```

Three words carry all the weight:

- **good** — events meeting your success criterion (status 200–499 except 429? latency under threshold? both?). *You must define "good" precisely.*
- **valid** — events that *count* toward the SLI. You deliberately exclude things you don't control or that aren't real user traffic: health-check pings, load-test traffic, requests with malformed input that are the *client's* fault (often `400`s), traffic from a customer who is being rate-limited because they exceeded their quota, etc.
- **window** — the time span over which you count.

A ratio SLI is preferred over alternatives (like raw averages) because it composes cleanly, is intuitive ("99.9% good"), and maps directly to an error budget.

> **Why exclude `400`s?** A `400 Bad Request` usually means the *client* sent something invalid. If you count it as a "bad event," a buggy client can blow up *your* SLO even though *your* service behaved correctly. But beware: a `400` storm caused by *your* breaking API change is your fault. The decision is nuanced — document it.

### 2.3 The four canonical SLI categories

Most services need a small set of SLIs. Google's "SRE Book" lists common categories; in practice you'll define SLIs in these families:

1. **Availability** — fraction of valid requests that succeed (don't error). `good = responses NOT in the failure set`.
2. **Latency** — fraction of valid requests served faster than a threshold. `good = requests with response time < T`.
3. **Quality / correctness** — fraction of responses that are *correct* (right data, not degraded). Harder to measure; sometimes proxied by "served full-fidelity vs. degraded".
4. **Freshness** (for data pipelines) — fraction of data served that is newer than some age. `good = data with age < T`.
5. **Throughput / coverage / durability** — specialized SLIs (e.g., "fraction of bytes durably stored").

### 2.4 The four golden signals

> **The four golden signals** (from the SRE Book, the "Monitoring Distributed Systems" chapter) are the four things you should measure if you can measure only four:

| Signal | What it is | Typical SLI use |
|---|---|---|
| **Latency** | Time to serve a request. Crucially, **separate successful from failed latency** — a fast `500` can hide a slow-failure problem. | Latency SLI (e.g., p99 < 300 ms) |
| **Traffic** | Demand on the system (requests/sec, transactions/sec, concurrent sessions). | Denominator of ratios; capacity context |
| **Errors** | Rate of requests that fail (explicit `5xx`, implicit wrong-content, policy failures like "too slow counts as failed"). | Availability SLI |
| **Saturation** | How "full" the most constrained resource is (CPU, memory, I/O, thread pool, connection pool). Often a *leading* indicator. | Usually a *cause* signal, not a top-level SLO |

Saturation is the odd one out: it is rarely a *user-facing* SLO (users don't feel "70% heap"), but it is the best **early warning** and the thing you'll dig into when latency/errors degrade.

### 2.5 RED and USE — two complementary frameworks

> **RED method** (coined by Tom Wilkie, Weaveworks/Grafana) — for **request-driven services**, measure **R**ate (requests/sec), **E**rrors (failed requests/sec), **D**uration (latency distribution). RED is essentially the golden signals minus saturation, reframed per-service. It is the natural basis for service-level SLOs.

> **USE method** (coined by Brendan Gregg) — for **resources** (CPU, memory, disks, network interfaces, queues), measure **U**tilization (% time busy), **S**aturation (queued/waiting work), and **E**rrors (error count). USE is for diagnosing *why* a resource is the bottleneck. It maps to the "saturation" golden signal.

Rule of thumb: **RED for the service-level SLOs your users feel; USE for the resource diagnostics you reach for when an SLO is burning.**

### 2.6 Percentiles, and why averages lie

A **percentile** `p(N)` is the value below which `N`% of observations fall. p50 (the **median**) is the typical experience; p99 is the experience of the worst 1% of requests.

> **Why not the mean (average)?** Latency distributions are **right-skewed with long tails**: a few very slow requests (GC pauses, cold caches, lock contention) pull the average up but, more importantly, the *average hides the tail entirely*. If your average is 50 ms but your p99 is 4 s, 1% of requests — which for a busy service is *millions per day* and often your most valuable, data-heavy users — are having a terrible time. **Users remember the worst experiences, not the average one.** This is why SLOs are written in percentiles, not means.

> **The "tail at scale" problem (Dean & Barroso, 2013):** in fan-out architectures, a single user request triggers many backend calls; the user waits for the *slowest* one. If each backend has a 1% chance of being slow, a request that fans out to 100 backends has a `1 − 0.99^100 ≈ 63%` chance of hitting at least one slow backend. So tail latency of *dependencies* dominates the *aggregate* user experience. This is why p99/p99.9 matter far more than the median in distributed systems.

Common percentile choices: **p50, p90, p95, p99, p99.9**. The higher the percentile, the more it reflects worst-case and the noisier/harder it is to measure reliably (you need lots of samples for a stable p99.9).

### 2.7 The "nines" and what they really cost

Availability targets are usually quoted in **"nines"**. The key insight is that each extra nine is **10× harder and ~10× more allowed downtime reduction**. Here is the allowed downtime per target (assuming a continuously-serving system, time-based approximation):

| SLO (availability) | "Nines" | Allowed unavailability / **30 days** | per **90 days** (quarter) | per **365 days** (year) |
|---|---|---|---|---|
| 90% | one nine | 3 days | 9 days | 36.5 days |
| 99% | two nines | ~7.2 hours | ~21.6 hours | ~3.65 days |
| 99.5% | — | ~3.6 hours | ~10.8 hours | ~1.83 days |
| 99.9% | three nines | ~43.2 min | ~2.16 hours | ~8.77 hours |
| 99.95% | — | ~21.6 min | ~1.08 hours | ~4.38 hours |
| 99.99% | four nines | ~4.32 min | ~12.96 min | ~52.6 min |
| 99.999% | five nines | ~25.9 sec | ~1.30 min | ~5.26 min |

> Computation: allowed unavailability = `(1 − SLO) × window`. For 99.9% over 30 days: `0.001 × 30 × 24 × 60 = 43.2` minutes. **Important caveat:** time-based "downtime" is only an approximation. Modern SLOs are usually **event/request-based**, where the budget is `(1 − SLO) × total_valid_events`, not minutes. A partial outage that fails 5% of requests for an hour burns a different amount of budget than a total outage for the same hour. We'll formalize this in §3.

**Practical reality check:** Five nines (~5 min/year of total downtime) is extraordinarily expensive and usually only justified for foundational infrastructure (e.g., core network, DNS). Most application services land at **99.9% or 99.95%**. Picking a target higher than your *dependencies* can sustain is a category error: if your database vendor promises 99.99%, your service that *requires* the database can't credibly promise more than ~99.99% on the database-dependent path.

### 2.8 SLA vs SLO vs SLI — the contract distinction

| Term | Audience | Nature | Consequence of breach |
|---|---|---|---|
| **SLI** | Internal | A measurement | None (it's just a number) |
| **SLO** | Internal | A target/goal | Internal: error-budget policy kicks in (e.g., freeze) |
| **SLA** | External (customer) | A contract clause | Financial/legal: refunds, credits, penalties, or right to terminate |

**The golden rule:** your **internal SLO must be stricter than your external SLA**, with margin. If your SLA promises 99.9%, set your SLO at 99.95% (or stricter). The gap is your safety buffer: you start reacting (page, freeze) when you breach the *SLO*, long before you breach the customer-facing *SLA* and owe money. A common ratio is to make the SLA's allowed downtime ~3–10× the SLO's. Also: **SLAs are often time-based and measured monthly with carve-outs** (scheduled maintenance, force majeure, customer's own fault), whereas SLOs are typically event-based over a rolling window.

> **Adjacent term — service credit:** the typical SLA remedy. If you breach (e.g., availability drops below 99.9% in a billing month), the customer gets a percentage of their monthly fee back as credit. Crucially, **service credits are usually capped and require the customer to file a claim** — they rarely make the customer whole, so SLAs protect the *vendor's* downside more than the customer's. This is exactly why your internal SLO bar is set higher.

---

## 3. How it works internally — the error-budget machine

This is the heart of the chapter. We'll trace the full lifecycle: measure → aggregate → compute SLI → derive budget → compute burn rate → alert → drive policy.

### 3.1 The pipeline at a glance

```
[1] Instrumentation     emit per-event signals (latency, status) at the right vantage point
        │
        ▼
[2] Collection          scrape/push metrics into a time-series DB (TSDB)
        │
        ▼
[3] Aggregation         turn raw counters into good/valid counts over time
        │
        ▼
[4] SLI computation     SLI(window) = sum(good) / sum(valid)
        │
        ▼
[5] Budget computation  budget_total = (1 − SLO) × valid;  budget_remaining = budget_total − bad
        │
        ▼
[6] Burn-rate calc      burn_rate = (error_rate over short window) / (1 − SLO)
        │
        ▼
[7] Alerting            multi-window multi-burn-rate rules → page / ticket
        │
        ▼
[8] Policy              error-budget policy: freeze releases, reprioritize, etc.
```

### 3.2 Step 1 — Instrumentation and the vantage-point question

**Where you measure determines what you measure.** The same logical event looks different at different points:

| Vantage point | Sees | Misses | Use when |
|---|---|---|---|
| **Application server** (in-process) | Your handler latency, your status codes | Network latency to user, LB failures, DNS, client-side render | Easy; good baseline; default for service SLOs |
| **Load balancer / API gateway / Envoy** | Everything in front of and through the LB, including some backend failures the app never logged | Last-mile network, client device | Strong choice — closest server-side proxy to the user |
| **Synthetic probes** (e.g., black-box prober) | End-to-end including DNS, TLS, geographic latency | Real user diversity (synthetic ≠ real traffic mix) | Catch total outages, measure from user regions |
| **Real User Monitoring (RUM)** in the client | The *actual* user experience including their device and network | Server attribution is harder; sampling/privacy | The "truest" SLI for UX-critical products |

> **Adjacent term — Envoy / API gateway:** an Envoy proxy (or an API gateway like Kong, AWS API Gateway, NGINX) sits in the request path and can emit per-request metrics (status, duration) for *all* traffic without touching application code. This is often the cleanest SLI source because it observes requests the app might have dropped before logging.

**Best practice:** measure as close to the user as is practical and attributable. The SRE Workbook's recommendation: **the load balancer is usually the best place** because it observes nearly the full server-side experience and is hard for a crashing app to "hide" failures from.

### 3.3 Step 2 — Collection into a TSDB

> **Adjacent term — TSDB (time-series database):** a database optimized for storing `(metric_name, labels, timestamp, value)` tuples, e.g., **Prometheus**, **VictoriaMetrics**, **Mimir**, **Thanos**, **InfluxDB**, **Datadog**, **Cloud Monitoring**. SLI data lives here.

> **Adjacent term — Prometheus & scraping:** Prometheus is a pull-based monitoring system. It periodically **scrapes** (HTTP GETs) a `/metrics` endpoint your app exposes, where you publish **counters** (monotonically increasing numbers like `http_requests_total`) and **histograms** (bucketed latency distributions like `http_request_duration_seconds_bucket`). Scrape interval is typically **15s–60s**. The TSDB stores each scrape as a sample.

The two metric types you need for SLOs:

- **Counter:** total count, only goes up (reset on restart). e.g., `http_requests_total{status="500"}`. You compute *rates* with `rate()`.
- **Histogram:** pre-bucketed observations. e.g., `http_request_duration_seconds_bucket{le="0.3"}` is "count of requests that took ≤ 0.3 s". This is exactly how you build a latency SLI without storing every raw sample.

> **Why histograms, not summaries, for SLOs:** a Prometheus **summary** computes percentiles *client-side per instance* and **cannot be aggregated across instances** (you can't average percentiles!). A **histogram** stores raw bucket counts that *can* be summed across instances, then you compute the percentile globally with `histogram_quantile()`. **For multi-instance SLOs, always use histograms.** Choose bucket boundaries that straddle your SLO threshold (e.g., put a bucket edge exactly at your 300 ms latency target so the SLI is precise there).

### 3.4 Step 3 & 4 — Aggregation and the SLI formula

For an **availability SLI** with Prometheus-style data:

```
SLI_availability(t) =
    sum(rate(http_requests_total{job="api", status!~"5.."}[28d]))
  / sum(rate(http_requests_total{job="api"}[28d]))
```

For a **latency SLI** ("% of requests faster than 300 ms"):

```
SLI_latency(t) =
    sum(rate(http_request_duration_seconds_bucket{job="api", le="0.3"}[28d]))
  / sum(rate(http_request_duration_seconds_count{job="api"}[28d]))
```

Note: the latency SLI's numerator uses the **bucket** counter for `le="0.3"` (requests ≤ 300 ms) and the denominator uses the total count. This is the **threshold (a.k.a. "good/bad" or "boolean") latency SLI** — far better for SLOs than tracking "p99 latency" as a number, because it composes into an error budget directly: a request is either good (fast enough) or bad (too slow).

> **Subtlety — excluding invalid events:** the "valid" filter goes in *both* numerator and denominator. To exclude health checks: `{path!="/healthz"}`. To exclude client errors from the failure set but still count them as valid traffic, you keep them in the denominator but not as "bad" — or you decide `4xx` are *not valid* and remove them from both. **Document the exact filter; this is the #1 source of SLO disputes.**

### 3.5 Step 5 — From SLO to error budget (event-based math)

Let:
- `SLO` = target ratio (e.g., 0.999).
- `N` = number of **valid** events in the window.
- `good`, `bad` = counts, `good + bad = N`.

Then:

```
error_budget_total      = (1 − SLO) × N            # events you're allowed to fail
budget_consumed         = bad                        # events you actually failed
budget_remaining        = error_budget_total − bad
budget_remaining_pct    = budget_remaining / error_budget_total × 100%
```

**Worked numbers.** SLO = 99.9% over 28 days. Suppose 28-day valid traffic `N = 200,000,000` requests.

- `error_budget_total = 0.001 × 200,000,000 = 200,000` allowed failures.
- If you've had `bad = 50,000` failures so far this window: `budget_remaining = 150,000` (75% of budget left).
- A 10-minute total outage at peak traffic of 5,000 req/s = `3,000,000` failed requests — **15× your entire 28-day budget gone in 10 minutes.** This is why a single bad deploy can blow a whole quarter.

> **Key insight — event-based budgets handle partial outages correctly.** A "time-based" budget (43.2 min/30 days for 99.9%) treats a 1%-failing brownout and a 100%-failing outage the same per minute. The event-based budget bills you `0.01 × traffic` for the brownout and `1.0 × traffic` for the outage. **Always prefer event-based when you have request counts.**

### 3.6 Step 6 — Burn rate: the central operational concept

> **Burn rate** is *how fast you are consuming the error budget, relative to the rate that would exactly exhaust it over the SLO window.* A burn rate of **1×** means you'll spend the entire budget exactly at the end of the window (you're failing exactly at the rate the SLO permits). A burn rate of **10×** means you'll exhaust the whole window's budget in 1/10th of the window.

Formally, over a measurement window:

```
burn_rate = (observed error ratio in window) / (1 − SLO)

where observed error ratio = bad_in_window / valid_in_window
```

**Worked example.** SLO = 99.9% (so `1 − SLO = 0.001`). Right now you're failing 1% of requests (`error ratio = 0.01`).

```
burn_rate = 0.01 / 0.001 = 10×
```

At 10× burn, a 30-day budget is gone in `30 days / 10 = 3 days`. If you were failing 10% (`0.10`), burn rate = `100×`, and the 30-day budget is gone in `30 / 100 = 0.3 days ≈ 7.2 hours`.

**The time-to-exhaustion shortcut:**

```
time_to_exhaust = SLO_window / burn_rate    (if budget were full)
```

This is the quantity alerting cares about: *"at the current burn rate, how long until we're out of budget?"*

### 3.7 Step 7 — Multi-window, multi-burn-rate (MWMB) alerting

This is the part that separates amateur SLO alerting from production-grade. The problem: how do you page **only** when budget burn is *significant*, *fast enough to matter*, and *not a transient blip* — and also catch *slow leaks* that never trip a fast alert?

#### The naive approaches and why they fail

1. **Alert when SLI < SLO (target threshold):** fires constantly on small dips, no urgency signal, terrible precision. ❌
2. **Alert on a single fixed error rate (e.g., >1% errors for 5 min):** doesn't connect to budget; a brief spike pages you at 3 AM for nothing; a slow 0.2% leak (which *will* exhaust budget over weeks) never fires. ❌
3. **Alert on single burn rate over a single window:** trade-off between **detection time** (short window = fast but noisy) and **reset time / precision** (long window = stable but you're alerted long after the incident, and the alert keeps firing long after it's resolved). You can't win with one window. ❌

> **Adjacent terms — precision and recall (in alerting):** *Precision* = of the alerts that fired, what fraction were "real" (worth waking someone). *Recall* = of the real budget-threatening events, what fraction did we catch. You want high both. Single-window alerting forces a bad trade between them.

#### The MWMB solution (SRE Workbook, Chapter 5)

Use **multiple burn-rate thresholds**, each paired with **two windows** — a **long window** (to require sustained burn = precision) and a **short window** (to confirm the problem is *still happening now* = fast reset and reduced false positives at the edges).

The canonical recommended setup for a **30-day** SLO window:

| Severity | Burn rate | Long window | Short window | Budget consumed if sustained | Detection time (approx) | Action |
|---|---|---|---|---|---|---|
| **Page (fast)** | **14.4×** | 1 hour | 5 min | ~2% of 30-day budget in 1h | minutes | Page on-call now |
| **Page (slow)** | **6×** | 6 hours | 30 min | ~5% in 6h | tens of minutes | Page on-call |
| **Ticket** | **3×** | 1 day | 2 hours | ~10% in 1 day | hours | Open a ticket (no page) |
| **Ticket** | **1×** | 3 days | 6 hours | ~10% in 3 days | ~1 day | Open a ticket (slow leak) |

> Where do 14.4 and 2% come from? `14.4 × (1h / 720h) = 0.02 = 2%`. (720h = 30 days.) `6 × (6/720) = 5%`. `3 × (24/720) = 10%`. `1 × (72/720) = 10%`. The thresholds are chosen so each tier consumes a *meaningful fraction* of the budget within its detection window. These exact numbers are the SRE Workbook's recommended starting point — **tune them**, don't treat them as sacred.

**Why two windows per alert?** The **long window** establishes that burn has been high *over a sustained period* (kills transient spikes → precision). The **short window** acts as a gate: the alert only fires (and only *keeps* firing) if burn is **also** high in the recent short window. This makes the alert **reset quickly** once the incident is fixed, instead of staying lit for the whole long window. Concretely: an alert condition is `burn_rate(long) > threshold AND burn_rate(short) > threshold`.

A Prometheus alerting rule for the fast page (using recording rules for the burn rates):

```yaml
# Burn rate = error_ratio / (1 - SLO). For SLO 99.9%, (1-SLO)=0.001.
# We precompute error ratios over each window with recording rules.
- alert: ErrorBudgetBurnFast
  expr: |
    (
      job:slo_errors_per_request:ratio_rate1h{job="api"} > (14.4 * 0.001)
      and
      job:slo_errors_per_request:ratio_rate5m{job="api"} > (14.4 * 0.001)
    )
  for: 2m            # tiny "for" to debounce flapping; keep short for fast detection
  labels:
    severity: page
  annotations:
    summary: "API burning error budget at >14.4x (fast). Budget at risk."
```

### 3.8 Step 8 — Error-budget policy (the organizational state machine)

The numbers are useless without a **policy** that says what *happens* when the budget is in a given state. A typical policy as a state machine:

```
            budget_remaining > 0
           ┌───────────────────────┐
           │     NORMAL OPS        │  ship features freely; SLO alerts only on real burn
           └──────────┬────────────┘
                      │ budget exhausted (or fast-burn during freeze)
                      ▼
           ┌───────────────────────┐
           │   RELEASE FREEZE      │  only reliability fixes / rollbacks ship;
           │                       │  feature work paused; on-call hands work to dev team
           └──────────┬────────────┘
                      │ budget recovered above threshold (e.g., next window, or burn stops)
                      ▼
              back to NORMAL OPS  (postmortem + action items first)
```

The policy is **agreed in advance, in writing, signed by both eng leadership and product**, so that freezing during a budget burn is *automatic*, not a negotiation in the heat of the moment. The SRE Workbook is explicit: **the consequence of blowing the budget should be pre-committed, not debated per-incident.**

Common policy clauses:
- **Budget exhausted →** freeze all non-reliability releases until back in budget (or until the window rolls and budget partially recovers).
- **Repeated exhaustion →** escalate: dedicate a sprint to reliability; SREs may "hand back the pager" (stop supporting on-call) until the service is healthy enough — a strong organizational lever Google uses.
- **Budget healthy and barely touched →** you may be *too* reliable: consider *spending* budget deliberately (faster releases, chaos experiments, planned maintenance) — over-reliability is wasted engineering money.

---

## 4. The complete toolkit

### 4.1 Concepts and their formulas (reference table)

| Quantity | Formula | Default/typical |
|---|---|---|
| SLI (ratio) | `good / valid` | per-service |
| Error budget (events) | `(1 − SLO) × valid_in_window` | — |
| Budget remaining % | `1 − (bad / ((1−SLO) × valid))` | — |
| Burn rate | `(bad/valid in window) / (1 − SLO)` | 1× = exact exhaustion at window end |
| Time to exhaust | `window / burn_rate` | — |
| Allowed downtime (time-based) | `(1 − SLO) × window` | see nines table §2.7 |
| SLO window | rolling, fixed at design time | **28 or 30 days** common; some use 7d or 90d |

### 4.2 Prometheus / PromQL functions you'll use constantly

| Function / construct | Purpose | Notes / defaults |
|---|---|---|
| `rate(counter[w])` | per-second average rate over window `w` | handles counter resets; `w` must be ≥ ~4× scrape interval |
| `increase(counter[w])` | total increase over `w` (= `rate × w`) | good for "how many errors in 1h" |
| `histogram_quantile(φ, sum by(le)(rate(bucket[w])))` | compute φ-quantile (e.g., 0.99) from histogram buckets | only as accurate as bucket boundaries |
| `sum by(...) / sum without(...)` | aggregate across instances/labels | **aggregate buckets, never pre-computed quantiles** |
| Recording rules | precompute expensive SLI/burn-rate expressions on a schedule | store as `job:slo_errors_per_request:ratio_rate1h` |
| `for:` in alert rules | require condition to hold for a duration before firing | debounce; keep short for fast burn alerts |

> **Adjacent term — recording rule:** a Prometheus config that evaluates an expression at each scrape interval and **stores the result as a new metric**. SLO dashboards and MWMB alerts query a *lot*; precomputing `ratio_rate5m`, `ratio_rate1h`, etc. as recording rules keeps queries cheap and consistent. Naming convention: `level:metric:operations` (e.g., `job:slo_errors_per_request:ratio_rate1h`).

### 4.3 Purpose-built SLO tooling

| Tool | What it does | Vendor/OSS | Notes |
|---|---|---|---|
| **Sloth** | Generates Prometheus recording + MWMB alerting rules from a simple SLO spec YAML | OSS | Great for "SLOs as code" with Prometheus; emits the standard multi-burn-rate rules |
| **Pyrra** | SLO UI + rule generator on top of Prometheus | OSS | Kubernetes-native CRDs for SLOs |
| **OpenSLO** | A vendor-neutral *specification* (YAML) for declaring SLOs | OSS spec | Lets you define SLOs once, target multiple backends |
| **Nobl9** | Commercial SLO platform (multi-source) | Vendor | Aggregates SLIs from Datadog, Prometheus, etc. |
| **Datadog SLOs** | Native SLO objects, error-budget burn, MWMB alerts | Vendor | Monitor-based or metric-based SLOs |
| **Grafana SLO / Cloud** | SLO definitions, burn-rate alerts, dashboards | Vendor/OSS | Built atop Mimir/Prometheus |
| **Google Cloud Monitoring SLOs** | Native SLO + burn-rate alerting on GCP services | Vendor | Canonical implementation of SRE Workbook math |
| **Sloth/Pyrra + Alertmanager** | the OSS canonical stack | OSS | Prometheus + Alertmanager for routing pages |

### 4.4 An "SLOs as code" spec (Sloth example)

```yaml
version: "prometheus/v1"
service: "checkout-api"
labels:
  team: "payments"
slos:
  - name: "requests-availability"
    objective: 99.9                      # SLO target %
    description: "99.9% of valid checkout requests succeed over 30d"
    sli:
      events:
        error_query: |                   # bad events
          sum(rate(http_requests_total{job="checkout-api",code=~"5.."}[{{.window}}]))
        total_query: |                   # valid events
          sum(rate(http_requests_total{job="checkout-api"}[{{.window}}]))
    alerting:
      name: CheckoutHighErrorBudgetBurn
      page_alert:   { labels: { severity: page } }
      ticket_alert: { labels: { severity: ticket } }
  - name: "requests-latency"
    objective: 99.0
    description: "99% of requests served < 300ms over 30d"
    sli:
      events:
        error_query: |                   # "bad" = slower than 300ms
          (
            sum(rate(http_request_duration_seconds_count{job="checkout-api"}[{{.window}}]))
            -
            sum(rate(http_request_duration_seconds_bucket{job="checkout-api",le="0.3"}[{{.window}}]))
          )
        total_query: |
          sum(rate(http_request_duration_seconds_count{job="checkout-api"}[{{.window}}]))
```

Sloth compiles this into ~30 Prometheus recording rules (per-window SLI ratios) and the full MWMB alert set — you don't hand-write the 14.4×/6×/3×/1× rules.

---

## 5. Code examples by use case

These are Java/JVM-centric where the topic is language-relevant (instrumentation), and config/PromQL where it's not.

### 5.1 Use case A — Instrumenting a Spring Boot service for availability + latency SLIs (Micrometer/Prometheus)

> **Adjacent term — Micrometer:** the metrics facade for the JVM (think "SLF4J for metrics"). Spring Boot Actuator uses it. It auto-instruments HTTP server requests into a timer `http.server.requests`, exported to Prometheus as a **histogram** `http_server_requests_seconds_bucket` with tags for `status`, `uri`, `method`, `outcome`.

```java
// build.gradle: implementation 'org.springframework.boot:spring-boot-starter-actuator'
//               implementation 'io.micrometer:micrometer-registry-prometheus'

// application.yml — expose Prometheus and enable SLO histogram buckets
// management:
//   endpoints.web.exposure.include: prometheus,health
//   metrics.distribution:
//     percentiles-histogram.http.server.requests: true   # publish histogram buckets
//     slo.http.server.requests: 100ms,300ms,1s           # bucket edges at SLO thresholds
//     minimum-expected-value.http.server.requests: 5ms
//     maximum-expected-value.http.server.requests: 10s

@RestController
class CheckoutController {

    private final MeterRegistry registry;

    CheckoutController(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/checkout")
    ResponseEntity<Order> checkout(@RequestBody Cart cart) {
        // Spring/Micrometer already times this and tags status+uri automatically.
        // We add a *business* SLI dimension: only "real" checkouts are valid events.
        // Tag with a custom dimension so we can filter the SLI denominator precisely.
        Timer.Sample sample = Timer.start(registry);
        try {
            Order order = process(cart);                 // domain logic
            sample.stop(registry.timer("checkout.sli",
                    "result", "good",                    // good event
                    "valid", "true"));                   // counts toward SLI
            return ResponseEntity.ok(order);
        } catch (InvalidCartException e) {
            // CLIENT fault: 400. Mark as NOT valid so it doesn't burn our budget.
            sample.stop(registry.timer("checkout.sli", "result", "client_error", "valid", "false"));
            return ResponseEntity.badRequest().build();
        } catch (DownstreamException e) {
            // OUR fault (dependency we own): 5xx, valid + bad → burns budget.
            sample.stop(registry.timer("checkout.sli", "result", "bad", "valid", "true"));
            return ResponseEntity.status(503).build();
        }
    }
}
```

The crucial design choice here is the `valid` tag: we **deliberately exclude client errors** from the SLI so a buggy partner integration can't blow our error budget, while keeping genuine `5xx`s in. The `slo.http.server.requests` config places histogram bucket edges *exactly at* 100 ms / 300 ms / 1 s so the latency SLI is accurate at those thresholds.

### 5.2 Use case B — The availability + latency SLI PromQL (with valid-event filtering)

```promql
# AVAILABILITY SLI over 28 days (good = valid AND not bad)
sum(rate(checkout_sli_seconds_count{valid="true", result="good"}[28d]))
/
sum(rate(checkout_sli_seconds_count{valid="true"}[28d]))

# LATENCY SLI over 28 days: fraction of *valid* requests served < 300ms
sum(rate(checkout_sli_seconds_bucket{valid="true", le="0.3"}[28d]))
/
sum(rate(checkout_sli_seconds_count{valid="true"}[28d]))

# CURRENT BURN RATE (5m) for the 99.9% availability SLO
(
  1 -
  (
    sum(rate(checkout_sli_seconds_count{valid="true", result="good"}[5m]))
    /
    sum(rate(checkout_sli_seconds_count{valid="true"}[5m]))
  )
) / 0.001        # divide error ratio by (1 - SLO) = 0.001 → burn rate
```

### 5.3 Use case C — Multi-burn-rate alert rules (hand-written, 99.9% SLO, 30d)

```yaml
groups:
- name: checkout-slo-burn
  rules:
  # --- recording rules: error ratio per window ---
  - record: job:checkout_errors:ratio_rate5m
    expr: |
      sum(rate(checkout_sli_seconds_count{valid="true",result="bad"}[5m]))
      / sum(rate(checkout_sli_seconds_count{valid="true"}[5m]))
  - record: job:checkout_errors:ratio_rate1h
    expr: |
      sum(rate(checkout_sli_seconds_count{valid="true",result="bad"}[1h]))
      / sum(rate(checkout_sli_seconds_count{valid="true"}[1h]))
  - record: job:checkout_errors:ratio_rate30m
    expr: |
      sum(rate(checkout_sli_seconds_count{valid="true",result="bad"}[30m]))
      / sum(rate(checkout_sli_seconds_count{valid="true"}[30m]))
  - record: job:checkout_errors:ratio_rate6h
    expr: |
      sum(rate(checkout_sli_seconds_count{valid="true",result="bad"}[6h]))
      / sum(rate(checkout_sli_seconds_count{valid="true"}[6h]))

  # --- FAST page: 14.4x over 1h, gated by 5m ---
  - alert: CheckoutBudgetBurnFast
    expr: |
      job:checkout_errors:ratio_rate1h  > (14.4 * 0.001)
      and
      job:checkout_errors:ratio_rate5m  > (14.4 * 0.001)
    labels: { severity: page, slo: checkout-availability }
    annotations:
      summary: "Checkout burning budget >14.4x (fast). ~2% of 30d budget/hour."

  # --- SLOWER page: 6x over 6h, gated by 30m ---
  - alert: CheckoutBudgetBurnSlow
    expr: |
      job:checkout_errors:ratio_rate6h  > (6 * 0.001)
      and
      job:checkout_errors:ratio_rate30m > (6 * 0.001)
    labels: { severity: page, slo: checkout-availability }
    annotations:
      summary: "Checkout burning budget >6x (slow). ~5% of 30d budget/6h."
```

### 5.4 Use case D — A data-pipeline freshness SLI (not request-based)

For a streaming/batch system, "availability" is the wrong SLI; **freshness** is right.

```java
// A Kafka consumer that records data freshness as an SLI.
// "good" event = a record whose end-to-end lag is under the freshness target.
public void onRecord(ConsumerRecord<String, Event> rec) {
    long eventTimeMs = rec.value().getEventTimestamp();
    long nowMs       = System.currentTimeMillis();
    long lagMs       = nowMs - eventTimeMs;             // end-to-end staleness

    // Freshness SLO: 99% of records processed within 60s of their event time.
    boolean fresh = lagMs <= 60_000;
    registry.counter("pipeline.freshness.sli",
            "result", fresh ? "good" : "bad").increment();

    // Also publish the raw lag as a histogram for percentile dashboards.
    registry.timer("pipeline.lag")
            .record(Duration.ofMillis(lagMs));

    process(rec);
}
```

```promql
# Freshness SLI: fraction of records processed within the freshness target
sum(rate(pipeline_freshness_sli_total{result="good"}[28d]))
/ sum(rate(pipeline_freshness_sli_total[28d]))
```

### 5.5 Use case E — Computing budget-remaining for a dashboard / release-gate

```promql
# Budget remaining as a fraction (1.0 = full, 0.0 = exhausted), 99.9% / 30d
1 -
(
  ( 1 -
    sum(rate(checkout_sli_seconds_count{valid="true",result="good"}[30d]))
    / sum(rate(checkout_sli_seconds_count{valid="true"}[30d]))
  )
  / 0.001                                  # error ratio / (1 - SLO)
)
```

A release-gate script that blocks deploys when budget is exhausted:

```bash
#!/usr/bin/env bash
# release-gate.sh — query Prometheus; exit non-zero (block CI deploy) if budget < threshold.
set -euo pipefail
PROM="http://prometheus:9090"
QUERY='1 - ((1 - (sum(rate(checkout_sli_seconds_count{valid="true",result="good"}[30d]))/sum(rate(checkout_sli_seconds_count{valid="true"}[30d])))) / 0.001)'

remaining=$(curl -sG "$PROM/api/v1/query" --data-urlencode "query=${QUERY}" \
  | jq -r '.data.result[0].value[1]')

# Block deploy if less than 10% of budget remains (policy: protect the buffer).
threshold=0.10
echo "Error budget remaining: ${remaining}"
awk -v r="$remaining" -v t="$threshold" 'BEGIN { exit !(r >= t) }' \
  || { echo "BLOCKED: error budget below ${threshold}. Release freeze in effect."; exit 1; }
echo "OK: budget sufficient; release allowed."
```

### 5.6 Use case F — A request classifier for "valid/good" in Java middleware

A reusable Servlet filter that centralizes the good/valid logic so it's consistent across endpoints (not scattered per-controller as in Use case A):

```java
@Component
public class SloFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;
    SloFilter(MeterRegistry registry) { this.registry = registry; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            int status = res.getStatus();
            double seconds = (System.nanoTime() - start) / 1e9;

            String valid  = isValid(req, status) ? "true" : "false";
            String result = classify(status);            // good / bad / client_error

            registry.timer("http.sli",
                    "result", result, "valid", valid,
                    "uri", normalizedRoute(req))          // normalize /users/123 → /users/{id}
                    .record((long)(seconds * 1000), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    // Health checks & load-test traffic are NOT valid events.
    private boolean isValid(HttpServletRequest req, int status) {
        String p = req.getRequestURI();
        if (p.startsWith("/actuator") || p.equals("/healthz")) return false;
        if ("true".equals(req.getHeader("X-Load-Test")))       return false;
        return true;
    }
    private String classify(int status) {
        if (status >= 500) return "bad";          // our fault
        if (status == 429) return "bad";          // we rate-limited a legit user → counts as our failure (policy choice)
        if (status >= 400) return "client_error"; // client fault, valid traffic but not "bad"
        return "good";
    }
    private String normalizedRoute(HttpServletRequest req) {
        return (String) req.getAttribute(
            org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    }
}
```

> **Why normalize the route?** Without normalization, `/users/123` and `/users/456` become distinct label values, causing **high-cardinality** metrics that explode TSDB memory. Always collapse path parameters to templates (`/users/{id}`). High cardinality is the #1 way to take down your own Prometheus.

---

## 6. Implementation concerns & best practices

### 6.1 Choosing SLIs — the discipline

- **Start with the user journey, not the metric you happen to have.** Ask "what does a user need from this service to be happy?" → that's the SLI. (Checkout: "my order goes through, quickly.")
- **Few SLOs, not many.** 1–3 per service (typically one availability + one latency, maybe a freshness/quality one). Too many SLOs dilute focus and create alert fatigue.
- **One SLO per *user-facing* critical journey,** not per endpoint. Aggregate the journey, but also tag by endpoint for diagnosis.
- **Separate latency of successes from failures** (golden-signals rule). A flood of fast `500`s shouldn't make your latency SLI look *better*.

### 6.2 Setting the target — methods

1. **Top-down from the SLA:** SLA 99.9% → SLO 99.95% (buffer).
2. **Bottom-up from dependencies:** if your hard dependencies multiply to ~99.95% best case, don't promise 99.99%.
3. **Empirical / "achievable":** measure current performance over 4 weeks; set the SLO at or slightly above what you reliably achieve, then tighten over time. **Don't set an aspirational SLO you constantly miss — it trains everyone to ignore it.**
4. **User-derived:** A/B / latency studies showing where users abandon (e.g., conversions drop sharply past 1 s) anchor the latency threshold.

### 6.3 Window choice

- **Rolling window (e.g., 28/30 days)** is standard for SLOs — it's a smooth, continuously-updated view. **28 days** is popular because it's exactly 4 weeks (constant number of each weekday → no weekly-seasonality bias; this is Google's preference).
- **Calendar window (monthly)** matches SLAs and billing but creates a "budget resets on the 1st" cliff and weekday imbalance.
- Shorter windows (7d) react faster but are noisier and let you "forget" incidents quickly; longer (90d) are stable but slow to recover budget after a big incident.

### 6.4 Performance & cost of the measurement system

- **Cardinality discipline:** every distinct combination of label values is a separate time series. Tags like `user_id`, raw `uri`, `trace_id` are cardinality bombs. Keep SLI label sets tiny (`job`, `route_template`, `status_class`, `result`, `valid`).
- **Histogram bucket count:** more buckets = more series. Put buckets only where you need precision (around SLO thresholds).
- **Recording rules** reduce query cost dramatically for dashboards and MWMB alerts — precompute, don't recompute on every dashboard refresh.
- **Long windows (28d `rate()`) are expensive** to evaluate raw; precompute per-window error ratios with recording rules and let MWMB rules reference them.

### 6.5 Correctness / concurrency

- **Counter atomicity:** Micrometer/Prometheus counters are thread-safe; don't roll your own non-atomic counters.
- **Counter resets:** `rate()`/`increase()` handle resets (pod restarts). Don't compute deltas manually across restarts.
- **Clock skew for freshness SLIs:** comparing event time to wall clock across machines requires NTP-synced clocks; skew shows up as negative or inflated lag. Clamp at 0 and alert on skew.

### 6.6 Observability of the SLO system itself

- **Dashboard the budget, not just the SLI:** show *remaining budget %* and *current burn rate* — those drive action; the raw SLI does not.
- **Alert on missing data:** if the SLI metric stops being reported (scrape failure, app crash so hard it can't emit), your SLI can look *perfect* (no bad events!). Add a **"no data" / absent()** alert and a synthetic prober as a backstop.

### 6.7 Testing

- **Unit-test your good/valid classifier** (the `classify()`/`isValid()` logic) — it's the most error-prone, highest-impact code.
- **Test alert rules** with `promtool test rules` against synthetic time series to verify burn-rate alerts fire at the right thresholds and reset properly.
- **Game-day / chaos:** deliberately inject errors and confirm the fast-burn alert pages within minutes and the budget dashboard reflects it.

### 6.8 Production hardening checklist

- Measure at the **LB/gateway** as the primary SLI source; app metrics as secondary.
- **Synthetic prober** from multiple regions as a backstop for "total outage with no traffic to measure."
- **Error-budget policy is written and signed** before you need it.
- Alerts route by severity: **fast/slow burn → page; ticket-tier → ticket queue**, never page on ticket-tier.
- **SLO definitions live in version control** (SLOs as code) and changes go through review — changing an SLO threshold is a meaningful decision, not a config tweak.

### 6.9 Anti-patterns (avoid these)

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| **100% SLO** | Impossible, infinitely costly, kills velocity | Pick a realistic target with a budget |
| **Alerting on raw resource metrics (CPU/mem)** as paging alerts | Pages on non-user-impacting blips → alert fatigue | Page on SLO burn; use USE metrics for *diagnosis* |
| **Tracking p99 as the SLO number** | Hard to budget; percentile-of-percentile aggregation is invalid | Use threshold (good/bad) latency SLI |
| **Averaging percentiles across instances** | Mathematically wrong | Aggregate histogram buckets, then `histogram_quantile` |
| **Setting SLO = SLA** | No buffer; you breach the contract the instant you breach internal target | SLO stricter than SLA |
| **Too many SLOs** | Diluted focus, alert noise | 1–3 per critical journey |
| **Counting client `4xx` as your failures** | Client bugs blow your budget | Define valid/good carefully |
| **No "no-data" alert** | A dead service reports a *perfect* SLI | `absent()`/synthetic backstop |
| **Aspirational SLO you always miss** | Everyone learns to ignore the SLO | Set achievable, tighten gradually |
| **High-cardinality SLI labels** | Kills your TSDB | Normalize routes, drop user/trace IDs from SLI metrics |

---

## 7. Advanced topics & deep internals

### 7.1 The burn-rate ↔ window arithmetic, derived

For an SLO window `W` (e.g., 30 days = 720 h) and an alert long-window `w`, an alert at burn rate `b` triggers when you'd consume budget fraction `f = b × (w / W)` within that window `w`. Rearranged:

```
b = f × (W / w)
```

- Want to detect when you'd burn **2%** of a 30-day budget within **1 hour**? `b = 0.02 × (720 / 1) = 14.4×`. (There's the 14.4.)
- Want **5%** in **6 hours**? `b = 0.05 × (720/6) = 6×`.
- Want **10%** in **1 day**? `b = 0.10 × (720/24) = 3×`.

**Detection time** for a given burn rate `b` and budget fraction you're willing to burn before alerting: `t_detect ≈ (budget_fraction × W) / (b)` — higher burn rates are detected faster *because* the long window's threshold is crossed sooner.

### 7.2 Why the short window is `1/12` of the long window

The SRE Workbook chooses short windows at roughly **1/12 of the long window** (5m for 1h; 30m for 6h; 2h for 1d). This ratio is a practical heuristic: long enough to avoid single-scrape noise, short enough to make the alert **reset within ~1/12 of the long window** after recovery, instead of lingering. You can tune it; smaller ratio = faster reset but more flapping risk.

### 7.3 Aggregating SLOs across a request that fans out

If a user request depends on N services in series, and each must succeed, the *journey* success probability ≈ the product of per-service success probabilities. To hit a 99.9% journey SLO across 5 serial dependencies, each needs ≈ `0.999^(1/5) ≈ 99.98%`. This is why **deep dependency chains force very high per-service SLOs** — a strong argument for reducing critical-path fan-out (caching, fallbacks, graceful degradation).

> **Adjacent term — graceful degradation:** designing so that when a non-critical dependency fails, the service returns a *reduced but still-good* response (e.g., show the product page without the "recommended items" widget). This *keeps the event "good"* for SLI purposes, protecting the budget. Pair with **circuit breakers** (e.g., Resilience4j) that stop calling a failing dependency to avoid cascading failures.

### 7.4 Budget-aware deployment automation

Advanced orgs wire the budget into CI/CD: progressive delivery tools (**Argo Rollouts**, **Flagger**) run canary analysis against SLI metrics and **auto-rollback** if the canary's error/latency SLI degrades, *before* the budget is meaningfully burned. The error budget becomes both a *gate* (block new releases when exhausted) and an *automated guardrail* (roll back individual bad releases).

### 7.5 SLO on heterogeneous traffic — bucketing by criticality

Not all requests are equal. Two patterns:
- **Per-class SLOs:** separate SLOs for "interactive" (tight latency) vs. "batch" (loose) traffic, by tagging requests with a criticality label.
- **Request prioritization / load shedding:** under saturation, shed low-priority traffic first to protect the SLO of high-priority traffic. Tools: Envoy's overload manager, adaptive concurrency limiters (Netflix concurrency-limits), token buckets.

### 7.6 Stochastic edge cases

- **Low-traffic services:** with few events, the SLI is statistically noisy — a single failure can swing it wildly. Mitigations: longer windows, looser SLOs, or **synthetic traffic** to create a stable denominator. Budgets on low-traffic services are inherently jittery; don't over-page.
- **Bimodal latency:** two clusters (cache hit vs. miss). A single percentile can sit *between* the modes and look stable while masking a growing miss rate. Watch the histogram shape, not just one quantile.
- **Coordinated omission:** when a load-testing or measurement tool pauses during a stall, it *fails to record* the very slow requests, making latency look better than reality. Use tools (e.g., proper HdrHistogram-based measurement) that correct for coordinated omission.

> **Adjacent term — HdrHistogram:** a high-dynamic-range histogram library (Gil Tene) that records values across a huge range with configurable precision and corrects for coordinated omission. The JVM ecosystem uses it heavily for accurate latency percentiles.

### 7.7 Lesser-known behaviors

- **Rolling-window budget "recovery":** with a rolling window, an old incident *falls off the back* of the window, so budget recovers gradually without any action. This means a freeze can be lifted simply because time passed — make sure the postmortem/fix still happens.
- **`rate()` extrapolation:** Prometheus `rate()` extrapolates at series boundaries, which can produce slightly fractional counts. Negligible for SLIs but surprising in unit tests.
- **Negative budgets:** budget can go below 0 (you've overspent). Dashboards should show negative remaining (e.g., −20%) — it's a strong signal, not an error.

---

## 8. Tradeoffs & decision frameworks

### 8.1 SLI source decision

| Source | Fidelity to user | Effort | Best when |
|---|---|---|---|
| App in-process | Low–medium | Low | Quick start, internal services |
| LB / gateway | High (server-side) | Low–medium | **Default recommendation** |
| Synthetic probe | Medium (not real mix) | Medium | Backstop for total outages, geo latency |
| RUM (client) | Highest | High | UX-critical consumer products |

### 8.2 Latency SLI: threshold vs. percentile

| Approach | Pros | Cons | Verdict |
|---|---|---|---|
| **Threshold (good/bad < T)** | Composes into budget, aggregates correctly, intuitive | Must pick T well; loses distribution detail in the SLI itself | **Use for the SLO** |
| **Percentile (p99 as a number)** | Rich, familiar | Can't aggregate across instances, doesn't map to a budget cleanly | Use for *dashboards/diagnosis*, not the SLO target |

### 8.3 Window: rolling vs. calendar

| Window | Pros | Cons |
|---|---|---|
| Rolling 28/30d | Smooth, no reset cliff, weekday-balanced (28d) | "Recovers" without action; harder to map to billing |
| Calendar month | Matches SLA/billing | Reset cliff, weekday imbalance, gameable near month-end |

### 8.4 Alerting: single-window vs. MWMB

| Strategy | Precision | Recall | Reset time | Verdict |
|---|---|---|---|---|
| Threshold on SLI | low | high | poor | ❌ |
| Single burn-rate, single window | medium | medium | poor (long) or noisy (short) | ❌ |
| **Multi-window multi-burn-rate** | **high** | **high** | **good** | ✅ standard |

### 8.5 "Use when / avoid when" rules

- **Use SLOs when:** the service has measurable user-facing events; you need to prioritize reliability vs. features; you page humans.
- **Avoid / defer SLOs when:** the service is internal-only, low-traffic, and pre-product-market-fit (the SLI is too noisy and the org isn't ready to act on a budget — start with the four golden signals and basic alerting instead).
- **Use an SLA when:** an external customer demands a contractual reliability commitment. **Always** set the internal SLO stricter.
- **Avoid an SLA when:** you can't yet reliably *meet* an internal SLO — never promise a customer what you can't measure and hit.

---

## 9. Failure modes & debugging

### 9.1 Common production failures and how to diagnose

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Fast-burn alert, errors spiking | Bad deploy / dependency outage | Correlate alert time with deploy events; check downstream SLIs; `rate(...{code=~"5.."})` by `uri` | Roll back; failover dependency; circuit-break |
| Slow-burn ticket, ~0.3% steady errors | A subtle bug on a specific code path; a single bad shard | Break SLI down by `uri`, `instance`, `pod`; look for one outlier | Targeted fix; drain bad instance |
| Latency SLI degrading, errors flat | Saturation (CPU, thread pool, GC, DB connections) | **USE method**: CPU util, GC pause logs, thread-pool queue depth, DB pool wait; JVM: `jstat -gcutil`, async-profiler, flame graphs | Scale out, tune pool sizes, fix GC, add caching |
| SLI looks *perfect*, users complain | Measuring at wrong vantage point; "no data" hiding failures; client/last-mile issues | Compare LB SLI vs. app SLI vs. synthetic vs. RUM; check `absent()` | Move measurement to LB; add synthetic; add no-data alert |
| Budget swings wildly | Low traffic; high-cardinality noise; window too short | Check event volume in denominator | Lengthen window; loosen SLO; synthetic traffic |
| Alert flaps on/off | Short window too short; `for:` missing | Inspect burn-rate series | Increase short window or `for:` slightly |

### 9.2 JVM-specific saturation diagnosis (when latency SLI burns)

> **Adjacent terms:** **GC pause** — a stop-the-world garbage-collection pause freezes all application threads; a 2 s GC pause becomes a 2 s tail-latency spike that burns your latency budget. **Thread-pool saturation** — when a bounded executor's queue fills, new tasks wait, adding latency. **Connection-pool exhaustion** (e.g., HikariCP) — threads block waiting for a DB connection.

Tools:
- `jstat -gcutil <pid> 1000` — GC frequency and pause behavior.
- GC logs (`-Xlog:gc*`) + a viewer (GCEasy) — pause durations and causes.
- **async-profiler** / flame graphs — where CPU/wall time goes (the long-tail offenders).
- Micrometer JVM metrics (`jvm_gc_pause_seconds`, `executor_queued_tasks`, `hikaricp_connections_pending`) — correlate saturation with the latency-SLI burn on one dashboard.

### 9.3 Real-world incident patterns (illustrative, anonymized)

- **The "fast 500" mirage:** a service started returning errors *quickly*. The team's latency SLI (which mistakenly included failed requests) *improved*, hiding the outage from the latency alert; only the availability alert caught it. **Lesson:** separate success/failure latency; always have an availability SLI too.
- **The buffer that saved a contract:** an SLA promised 99.9%; the internal SLO was 99.95%. A multi-hour partial outage burned the SLO budget and triggered a freeze + page, but because the buffer existed, the *contractual* 99.9% held for the month — no service credits owed. **Lesson:** the SLO-over-SLA buffer has direct financial value.
- **The cardinality self-inflicted outage:** a new label (`customer_id`) on the SLI metric exploded Prometheus memory, OOM-killed it, and the team went blind on *all* SLOs during a real incident. **Lesson:** treat SLI label cardinality as a production risk; review label changes.
- **The aspirational SLO no one believed:** a team set 99.99% they never met; alerts fired constantly; everyone muted them; a *real* outage went unnoticed for an hour. **Lesson:** set achievable SLOs and tighten.

### 9.4 Debugging the SLO definition itself

When stakeholders dispute the SLI, check, in order: (1) the **valid** filter (what's excluded?), (2) the **good** criterion (which statuses/latencies?), (3) the **vantage point** (where measured?), (4) the **window**, (5) **cardinality/aggregation** correctness (are you summing buckets, not averaging quantiles?). Most disputes are a definitional mismatch, not a measurement bug.

---

## 10. Interview drill

**Q1. Define SLI, SLO, SLA, and error budget, and state the relationship between them.**
*Model answer:* An SLI is a measured ratio of good to valid events (e.g., % of requests under 300 ms). An SLO is an internal target for that SLI (e.g., 99.9% over 28 days). An SLA is an external contract with the same kind of target *plus consequences* (service credits). The error budget is `1 − SLO` worth of allowed failures; it's the shared currency between reliability and feature velocity. The SLO is set *stricter* than the SLA to create a safety buffer.
- *Probe: Why must the SLO be stricter than the SLA?* So you start reacting (page, freeze) on internal SLO breach before the contractual SLA breach that costs money. The gap absorbs measurement noise and gives response time.
- *Probe: Can an SLI exist without an SLO?* Yes — it's just a measurement. The SLO adds the target; without it there's no budget and no action trigger.
- *Probe: Why express SLIs as good/valid ratios rather than raw counts?* Ratios compose across instances/time, are intuitive, and map directly to a budget.

**Q2. What are the four golden signals, and how do RED and USE relate to them?**
*Model answer:* Latency, traffic, errors, saturation. RED (Rate, Errors, Duration) covers the first three for *request-driven services* — the basis of service SLOs. USE (Utilization, Saturation, Errors) covers *resources* and maps to the saturation signal — used for diagnosis. You page on RED-derived SLO burn; you debug with USE.
- *Probe: Which golden signal is rarely a user-facing SLO and why?* Saturation — users don't feel "70% heap"; it's a leading *cause* indicator, not user-visible quality.
- *Probe: Why separate latency of successes from failures?* A flood of fast errors can make aggregate latency look better, masking an outage.

**Q3. Why use percentiles, not averages, for latency SLOs? Why p99 over p50?**
*Model answer:* Latency is right-skewed with long tails; averages hide the tail. p50 is the typical user; p99 is the worst 1% — which at scale is millions of requests and often your heaviest users. In fan-out architectures, tail latency of dependencies dominates aggregate experience (tail-at-scale), so high percentiles best reflect real pain.
- *Probe: Why not just track p99 as the SLO number?* You can't aggregate percentiles across instances, and a percentile doesn't map cleanly to an error budget. Use a threshold (good/bad) latency SLI instead.
- *Probe: When does p50 matter more?* When the median itself is bad (systemic slowness) or for capacity/cost work where typical load matters.

**Q4. Explain error budget and burn rate with numbers.**
*Model answer:* For SLO 99.9%, budget = 0.1% of valid events. Burn rate = (current error ratio)/(1−SLO). At 1% errors, burn = 0.01/0.001 = 10×, exhausting a 30-day budget in 3 days. Burn rate answers "how long until we're out?" = window/burn_rate.
- *Probe: Event-based vs time-based budget?* Event-based bills partial outages proportionally (5% failing ≠ 100% failing); time-based treats any downtime per-minute equally. Prefer event-based.
- *Probe: Can burn rate be negative or budget go below zero?* Burn rate isn't negative, but remaining budget can go negative (overspent) — a strong signal, show it.

**Q5. Walk me through multi-window multi-burn-rate alerting and why single-window fails.**
*Model answer:* Single-window forces a trade between detection speed (short=noisy) and precision/reset (long=slow, lingers). MWMB pairs a long window (sustained burn = precision) with a short window gate (still happening now = fast reset), at several burn-rate tiers: e.g., 14.4×/1h+5m page, 6×/6h+30m page, 3×/1d+2h ticket, 1×/3d+6h ticket. Each tier burns a meaningful budget fraction within its detection window.
- *Probe: Where does 14.4 come from?* `b = budget_fraction × (W/w) = 0.02 × (720h/1h) = 14.4×`.
- *Probe: Why does the short window make alerts reset faster?* Once errors stop, the short window clears within minutes, dropping the AND condition even though the long window still shows past burn.

**Q6 (senior signal). You're asked to set an SLO for a brand-new service. How do you choose the target and window, and what do you refuse to do?**
*Model answer:* I'd avoid committing to a number on day one. First, instrument the four golden signals at the LB and collect 2–4 weeks of baseline. Define the SLI from the critical user journey (good/valid). Set an *achievable* SLO at or slightly above measured performance, on a rolling 28-day window, then tighten over time. I'd refuse to (a) set 100% / aspirational targets we can't meet, (b) sign an SLA before we can reliably meet a stricter internal SLO, and (c) define the SLO without an agreed, written error-budget policy.
- *Probe: How does dependency reliability constrain the target?* Serial dependencies multiply; to hit 99.9% across 5 serial deps each needs ~99.98%. I won't promise more than dependencies + buffer allow.
- *Probe: Low-traffic service?* Longer window, looser SLO, possibly synthetic traffic; don't over-page on statistical noise.

**Q7 (senior signal). Reliability and product disagree: product wants to ship features, SRE wants a freeze. How does the error-budget framework resolve this without politics?**
*Model answer:* The error-budget *policy*, agreed and signed in advance, makes it automatic: while budget remains, product ships freely; when it's exhausted, releases freeze to reliability-only until recovered, and a postmortem with action items is required. The budget converts an opinion fight into a data-driven, pre-committed rule. If budget is *consistently untouched*, that's also actionable — we're over-investing in reliability and can spend budget on faster releases.
- *Probe: What if leadership overrides the freeze?* Then the policy needs renegotiation at leadership level, but the override is now an explicit, accountable decision rather than a per-incident scramble — and the budget data documents the risk taken.
- *Probe: How do you handle a single bad customer or path burning the shared budget?* Per-class SLOs / valid-event filtering so one client's `4xx`s or one low-priority path can't blow the whole budget; load-shed low priority first.

**Q8 (senior signal). Your SLI dashboard shows 99.99% but users are complaining loudly. Diagnose.**
*Model answer:* Classic vantage-point or no-data problem. Possible causes: (1) measuring in-process so LB/network/last-mile failures are invisible; (2) the app crashed so hard it stopped emitting metrics — "no bad events" looks perfect; (3) wrong "good" definition (counting wrong-content 200s as good); (4) excluding too much as "invalid." I'd compare LB SLI vs app SLI vs synthetic vs RUM, add an `absent()`/no-data alert, and audit the good/valid definitions. Likely fix: measure at the LB and add a synthetic prober.
- *Probe: How do you prevent the "no data = perfect" trap?* Alert on metric absence and run an independent synthetic prober as a backstop.
- *Probe: How would RUM change the picture?* It captures real device/network experience the server can't see, often revealing client-side or geographic latency the server SLI misses.

**Q9. How do you build a latency SLI in Prometheus correctly across multiple instances?**
*Model answer:* Use a **histogram** with bucket edges at the SLO threshold; the SLI is `sum(rate(bucket{le="0.3"}[w])) / sum(rate(count[w]))` — summing bucket counts across instances first. Never use summaries (per-instance percentiles can't aggregate) and never average percentiles.
- *Probe: Why histograms over summaries here?* Summaries compute quantiles client-side and aren't aggregatable; histograms expose raw buckets that sum globally.
- *Probe: How do bucket boundaries affect accuracy?* `histogram_quantile` interpolates within buckets, so accuracy is bounded by bucket width near the value of interest — put an edge at the SLO threshold.

**Q10. What's a healthy number of SLOs per service, and what goes wrong with too many?**
*Model answer:* 1–3 per critical journey (typically one availability + one latency, maybe freshness/quality). Too many cause alert fatigue, diluted focus, and conflicting signals; people stop trusting any of them.
- *Probe: Should every endpoint have an SLO?* No — SLO the user-facing journey; tag by endpoint for diagnosis only.

**Q11. What does it mean if you're consistently *under* your error budget (barely burning it)?**
*Model answer:* You may be over-reliable, which means over-invested engineering and possibly slowed velocity. The budget is meant to be *spent*: ship faster, run chaos experiments, do riskier-but-valuable work, or take planned maintenance. Persistent near-zero burn is a signal to loosen, not celebrate.
- *Probe: Could it instead mean the SLO is too loose?* Yes — if users are unhappy despite plenty of budget, the SLO target/threshold is mis-set; recalibrate from user data.

**Q12. How do error budgets integrate with CI/CD?**
*Model answer:* Two ways: as a **release gate** (block non-reliability deploys when budget is exhausted, per policy) and as an **automated guardrail** (canary/progressive delivery tools like Argo Rollouts/Flagger analyze the canary's SLI and auto-rollback bad releases before they burn meaningful budget).
- *Probe: Risk of gating deploys on budget?* You could block an *important reliability fix*; policy must always allow reliability/security releases through the freeze.

---

## 11. Glossary

- **Availability SLI:** fraction of valid requests that succeed (not in the failure set).
- **Burn rate:** how fast the error budget is consumed relative to the rate that exactly exhausts it over the SLO window; 1× = on pace to exhaust exactly at window end.
- **Calendar window:** SLO window aligned to a calendar period (e.g., a billing month).
- **Cardinality:** number of distinct time series (label combinations); high cardinality strains the TSDB.
- **Circuit breaker:** a pattern (e.g., Resilience4j) that stops calling a failing dependency to prevent cascading failure.
- **Coordinated omission:** measurement bias where a tool fails to record slow events during stalls, understating tail latency.
- **Counter (Prometheus):** monotonically increasing metric; use `rate()`/`increase()` to derive rates.
- **Error budget:** allowed unreliability implied by an SLO = `(1 − SLO)` of events.
- **Error-budget policy:** pre-agreed rules for what happens at each budget state (e.g., freeze on exhaustion).
- **Envoy / API gateway:** in-path proxy that can emit per-request SLI metrics for all traffic.
- **Four golden signals:** latency, traffic, errors, saturation.
- **Freshness SLI:** fraction of data served newer than a target age (data pipelines).
- **GC pause:** stop-the-world garbage-collection pause that freezes app threads and causes latency spikes.
- **Graceful degradation:** returning a reduced-but-still-good response when a non-critical dependency fails, preserving the "good" event.
- **HdrHistogram:** high-dynamic-range histogram library that records accurate percentiles and corrects coordinated omission.
- **Health check:** coarse liveness/readiness probe; not an SLI.
- **Histogram (Prometheus):** bucketed observation counts; aggregatable across instances for percentile/threshold SLIs.
- **Latency SLI (threshold):** fraction of valid requests faster than a threshold T.
- **Load shedding:** dropping low-priority requests under saturation to protect high-priority SLOs.
- **Micrometer:** JVM metrics facade used by Spring Boot Actuator.
- **MWMB (multi-window multi-burn-rate):** alerting using multiple burn-rate tiers, each gated by a long and short window.
- **Nines:** shorthand for availability targets (99.9% = "three nines").
- **Percentile (pN):** value below which N% of observations fall.
- **Precision / recall (alerting):** fraction of alerts that are real / fraction of real events caught.
- **Prometheus:** pull-based monitoring system and TSDB; scrapes `/metrics` endpoints.
- **RED method:** Rate, Errors, Duration — metrics for request-driven services.
- **Recording rule:** Prometheus rule that precomputes and stores an expression as a new metric.
- **RUM (Real User Monitoring):** client-side measurement of actual user experience.
- **Saturation:** how full the most constrained resource is; a leading indicator.
- **Service credit:** typical SLA remedy — partial refund when a target is missed.
- **SLA (Service Level Agreement):** external contract with reliability targets and consequences.
- **SLI (Service Level Indicator):** a measured quality ratio (good/valid).
- **SLO (Service Level Objective):** internal target for an SLI.
- **Sloth / Pyrra / OpenSLO / Nobl9:** SLO tooling (rule generation, UIs, specs, platforms).
- **SRE (Site Reliability Engineering):** discipline applying software engineering to operations; origin of SLO/error-budget practice.
- **Summary (Prometheus):** per-instance precomputed quantiles; NOT aggregatable — avoid for multi-instance SLOs.
- **Synthetic probe / black-box monitoring:** scripted external requests measuring end-to-end behavior; backstop for total outages.
- **Tail-at-scale:** phenomenon where dependency tail latency dominates aggregate user latency in fan-out systems.
- **TSDB (time-series database):** stores `(metric, labels, timestamp, value)`; home of SLI data.
- **USE method:** Utilization, Saturation, Errors — resource diagnostics.
- **Valid event:** an event that counts toward the SLI denominator (excludes health checks, load tests, etc.).
- **Vantage point:** where the SLI is measured (app, LB, synthetic, RUM); determines what's captured.
- **Window (SLO):** time span over which the SLI/budget is computed; commonly rolling 28/30 days.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Definitions:** SLI = good/valid (measured). SLO = internal target. SLA = external contract + penalties. Error budget = (1 − SLO) × valid events. **SLO must be stricter than SLA.**

**Golden signals:** Latency, Traffic, Errors, Saturation. **RED** (Rate/Errors/Duration) = services → SLOs. **USE** (Utilization/Saturation/Errors) = resources → diagnosis.

**Key formulas:**
- Budget (events) = `(1 − SLO) × N`
- Burn rate = `(bad/valid in window) / (1 − SLO)`; 1× = exhaust at window end
- Time to exhaust = `window / burn_rate`
- Alert burn rate = `budget_fraction × (W / w)`

**Nines (allowed downtime/30d):** 99% ≈ 7.2 h; 99.9% ≈ 43.2 min; 99.95% ≈ 21.6 min; 99.99% ≈ 4.32 min; 99.999% ≈ 26 s.

**MWMB starting set (30d SLO):** 14.4× (1h+5m) page · 6× (6h+30m) page · 3× (1d+2h) ticket · 1× (3d+6h) ticket.

**Latency SLI:** use **threshold** (good/bad < T) with a **histogram**; bucket edge at T; `sum(rate(bucket{le=T}))/sum(rate(count))`. Never average percentiles.

**Window:** rolling 28d (weekday-balanced) is the default.

**Decision rules:** measure at the LB by default; 1–3 SLOs per critical journey; define *valid* and *good* precisely and in version control; write the error-budget policy *before* you need it; page only on burn, ticket on slow burn; add a no-data/synthetic backstop; set achievable SLOs and tighten.

**Top anti-patterns:** 100% SLO · SLO = SLA · paging on CPU · p99-as-the-number · averaging percentiles · counting client 4xx as your failures · high-cardinality SLI labels · aspirational unmet SLOs · no no-data alert.

### Self-test (no answers)

1. Your SLO is 99.95% over 28 days and the service handled 400M valid requests this window. How many failures is your total error budget, and how many minutes of *total* outage at a steady 6,000 req/s would exhaust it?
2. Derive the burn-rate threshold that detects burning 5% of a 30-day budget within a 3-hour window. Show the arithmetic.
3. You measure latency in-process and report 99.99% under 300 ms, but support is flooded with "the site is slow" tickets. List four distinct hypotheses and the exact tool/metric you'd use to confirm or eliminate each.
4. Explain, with a concrete example, why summing per-instance p99 values is mathematically invalid and what to do instead in Prometheus.
5. Product wants to ship a risky feature; the service is at 4% of its error budget remaining (96% burned). Walk through exactly what your error-budget policy should make happen, and justify why the decision shouldn't be made ad hoc.
6. A serial request path crosses 4 services and the journey SLO is 99.9%. What per-service availability does each need (assume independence), and name two architectural changes that relax this requirement.
7. Your fast-burn alert flaps on and off every few minutes during a partial outage. Name two likely causes and the specific config you'd change for each.

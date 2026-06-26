# Alerting Design

> An exhaustive engineering-handbook chapter on designing alerts that wake the right human, at the right time, for the right reason — and never otherwise.

---

## 1. Overview & where it fits

### 1.1 What "alerting" is

**Alerting** is the part of an observability system that *automatically* evaluates the state of your service against rules and, when something is wrong, **notifies a human (or another system) to take action**. It is the bridge between *passive* telemetry (metrics, logs, traces sitting in a database) and *active* response (a person being paged at 3 a.m., a ticket appearing in a queue, a dashboard turning red).

Three terms appear constantly in this chapter, so define them once, up front:

- **Metric** — a numeric measurement sampled over time, e.g. `http_requests_total` or `cpu_usage_seconds`. Think of it as a named time series: a sequence of `(timestamp, value)` pairs, usually tagged with **labels** (key/value pairs like `service="checkout"`, `status="500"`) that let you slice it.
- **Telemetry** — the umbrella word for all the signals a system emits about itself: metrics, **logs** (timestamped text/structured event records), and **traces** (records of a single request as it hops between services). Alerting is overwhelmingly built on metrics because they are cheap to evaluate continuously.
- **Observability (o11y)** — the property of being able to ask arbitrary questions about your system's internal state from the outside, using its telemetry, *without* shipping new code to answer each question. Alerting is the "tell me when" half; dashboards and ad-hoc queries are the "let me look" half.

### 1.2 The problem alerting solves

You cannot watch a dashboard 24/7. Even if you could, humans are terrible at noticing slow drifts and rare spikes. Alerting solves: **"How do I get notified *only* when human judgment or action is genuinely required, *fast enough* to matter, and *not* otherwise?"**

The hard part is the *not otherwise*. A naive alerting setup fires constantly, trains humans to ignore it (**alert fatigue**), and then misses the one alert that mattered. The entire discipline of alerting *design* is about maximizing the signal-to-noise ratio of pages.

### 1.3 When you reach for it

You design or revisit alerting when:

- You are launching a service that has users who will notice if it breaks.
- You have **SLOs** (Service Level Objectives — see §2) and need to defend them.
- Your on-call rotation is drowning in pages and people are burning out.
- An incident happened that *nobody got paged for*, or that *everybody got paged for ten times*.
- You are migrating from "cause-based" alerts (CPU, disk, restarts) to "symptom-based" alerts (users seeing errors/slowness).

### 1.4 The one-paragraph mental model

> Alerting is a **control loop**: telemetry flows in continuously, rules evaluate it on a clock, firing rules become **alerts**, alerts are **routed/grouped/deduplicated/silenced** by a dispatcher, and survivors become **notifications** (a page, a ticket, a Slack message). A *good* alert fires on a **user-visible symptom**, is **urgent** (needs action soon), is **actionable** (the recipient can actually do something), and is **novel** (not a duplicate of something already firing). Everything else in this chapter is detail on how to make that loop produce exactly the right notifications and no others.

### 1.5 Where it sits in the broader SRE stack

**SRE** — Site Reliability Engineering — is Google's discipline (popularized by the 2016 book *Site Reliability Engineering*) of applying software-engineering rigor to operations. Within SRE, alerting connects several practices:

```
       SLIs  →  SLOs  →  Error budgets
        │                    │
        │ (measure)          │ (policy: how much failure is OK)
        ▼                    ▼
   ┌──────────┐        ┌──────────────┐
   │ Telemetry │──────▶│   Alerting    │──────▶ On-call human ──▶ Runbook ──▶ Fix
   │ (metrics) │  rules │ (this chapter)│  page          │
   └──────────┘        └──────────────┘                 ▼
                              │ ticket/dashboard    Incident response,
                              ▼                      postmortem, action items
                        Lower-urgency work
```

- **SLI** (Service Level Indicator): a *measurement* of how well the service is doing, e.g. "fraction of HTTP requests served in < 300 ms" or "fraction of requests that did not return 5xx."
- **SLO** (Service Level Objective): a *target* for an SLI over a window, e.g. "99.9% of requests succeed over 30 days."
- **Error budget**: `1 − SLO`. If your SLO is 99.9%, your error budget is 0.1% — the amount of failure you are *allowed* to spend. Alerting's most modern form is built directly on consuming this budget (see §3.5).

---

## 2. Foundations from first principles

We build the vocabulary you will need for the rest of the chapter, starting from zero.

### 2.1 SLIs, SLOs, SLAs — the measurement layer

- **SLI (Service Level Indicator)** — a ratio of *good events* to *total valid events*, expressed 0–1 or as a percentage. The canonical SLI is `good / total`. Examples:
  - **Availability SLI**: `successful_requests / total_requests`.
  - **Latency SLI**: `requests_faster_than_threshold / total_requests`.
  - **Quality SLI**: `requests_served_without_degradation / total_requests`.
- **SLO (Service Level Objective)** — the target value of an SLI over a defined **compliance window** (commonly 28 or 30 days, rolling or calendar). "99.9% availability over 30 days." This is an *internal engineering goal*.
- **SLA (Service Level Agreement)** — a *contract* with customers that includes consequences (refunds, credits) if a target is missed. SLAs are looser than SLOs by design: you set your internal SLO *tighter* than your SLA so you have warning before you breach the contract. SLAs are a business/legal artifact; SLOs are the engineering artifact alerting actually defends.

A common source of confusion: **the "number of nines."**

| Availability | Downtime / 30 days | Downtime / year | Informal name |
|---|---|---|---|
| 99% (two nines) | 7.2 hours | 3.65 days | "two nines" |
| 99.9% (three nines) | 43.2 minutes | 8.76 hours | "three nines" |
| 99.95% | 21.6 minutes | 4.38 hours | — |
| 99.99% (four nines) | 4.32 minutes | 52.6 minutes | "four nines" |
| 99.999% (five nines) | 25.9 seconds | 5.26 minutes | "five nines" |

These numbers matter because they directly bound how *fast* your alerting must be: if you only have 4.32 minutes of budget per month, an alert that takes 15 minutes to fire is useless for defending four nines.

### 2.2 Error budget

The **error budget** is the inverse of the SLO over the compliance window:

```
error_budget_fraction = 1 − SLO
                       = 1 − 0.999 = 0.001  (0.1%)
```

If the service handles `N` requests in the window, you are allowed `0.001 × N` bad requests. **Burning** the budget means consuming those allowed failures. **Burn rate** is *how fast* you are consuming it:

```
burn_rate = (observed_error_rate) / (error_budget_fraction)
```

A burn rate of **1** means you are spending budget exactly fast enough to exhaust it precisely at the end of the window. A burn rate of **10** means you'll exhaust the entire 30-day budget in 3 days. A burn rate of **1000** means you'll exhaust it in ~43 minutes. Burn rate is the single most important concept in modern alerting (§3.5), so internalize it.

### 2.3 Symptom vs cause — the foundational design rule

> **Alert on symptoms (what the user experiences), not causes (why it might be happening).**

- A **symptom** is user-visible: "errors are up," "latency p99 is 4 s," "the checkout success rate dropped." The user *feels* it.
- A **cause** is an internal mechanism: "CPU is 95%," "disk is 80% full," "a pod restarted," "GC pause was 2 s." The user does *not* directly feel any of these.

Why symptoms win:
1. **Causes are infinite; symptoms are few.** There are a hundred reasons latency could spike; you cannot write an alert for each. One latency-symptom alert catches all of them.
2. **Causes have false positives.** High CPU is fine if the service is still fast. A pod restart during a deploy is fine. Alerting on causes pages you for non-problems.
3. **Causes miss real problems.** A service can be 100% broken with low CPU (e.g., deadlocked, or a dependency is down). The cause-alert never fires; the symptom-alert does.

The corollary: **cause-based signals belong on dashboards and in runbooks, not on pages.** When the symptom alert fires, the on-call engineer *uses* CPU/disk/restart graphs to diagnose. They just don't get *paged* for them.

There is a narrow exception: a few causes are such reliable leading indicators of imminent, unavoidable user pain that they justify a page *before* the symptom appears — the canonical example is **"disk will be full in 4 hours"** (you cannot un-fill a disk instantly, so you need lead time). These are rare and should be treated as the exception, not the rule.

### 2.4 The three properties of a good alert: actionable, urgent, novel

Every page must satisfy all three:

- **Actionable** — there is something a human can *do right now* in response. If the answer to "what would you do about this page?" is "nothing" or "wait and see," it should not be a page. Non-actionable alerts are the #1 cause of fatigue.
- **Urgent** — it needs human attention *now*, not next business day. If it can wait until morning, it is a **ticket**, not a **page**. The test: "If I see this at 3 a.m., must I get out of bed?"
- **Novel** — it represents *new* information. Ten alerts for the same root cause is one piece of novel information plus nine pieces of noise. Deduplication and grouping (§3.6) exist to enforce novelty.

A fourth property is often added: **diagnosable** — the alert (and its linked runbook) gives enough context to begin diagnosis. A page that says only "PROBLEM" wastes the critical first minutes.

### 2.5 Page vs ticket vs dashboard — the three response tiers

This is the routing decision behind every alert:

| Tier | Trigger | Latency to human | Example | Wakes you up? |
|---|---|---|---|---|
| **Page** | Urgent + actionable symptom; SLO at risk *now* | Seconds–minutes | "Checkout error rate burning budget fast" | Yes |
| **Ticket** | Actionable but not urgent; can wait hours/days | Hours–next business day | "Slow budget burn; certificate expires in 20 days" | No |
| **Dashboard / log** | Informational; for diagnosis, capacity planning, trend-watching | When someone looks | CPU graphs, request rate, cache hit ratio | No |

The discipline: **only urgent+actionable symptoms become pages.** Everything actionable-but-not-urgent becomes a ticket. Everything else is a dashboard or a log. Misclassifying tickets as pages is how rotations die.

### 2.6 The on-call human

**On-call** is the rotation of engineers who are responsible for responding to pages during a shift (typically a week). The on-call experience is the *consumer* of your alerting design — and the most important constraint on it. Two field heuristics from Google SRE:

- **The "two pages per shift" rule of thumb**: an on-call engineer should be able to handle, on average, *no more than ~2 actionable incidents per 12-hour shift*. More than that and they cannot do them justice (investigate, mitigate, document). This is a *budget on pages*, and it forces ruthless prioritization of what gets to page.
- **The 50% rule**: SRE teams should spend ≤ 50% of time on operational ("toil") work, including being paged. If alerting volume pushes a team over this, that is a signal to fix the alerts (or the service), not to hire more humans to absorb noise.

### 2.7 Runbook

A **runbook** (or **playbook**) is a document linked from the alert that tells the responder: *what this alert means, how to confirm it's real, how to diagnose, how to mitigate, and how to escalate.* Every page-level alert should link to a runbook. A page without a runbook forces the responder to reconstruct tribal knowledge under pressure — the worst time to do it. (Details in §6.6.)

### 2.8 The alerting control loop (conceptual)

Putting it together, the loop every alerting system runs:

```
1. SCRAPE/INGEST   collect metrics (and sometimes logs/events)
2. EVALUATE        run alert rules on a fixed interval (e.g. every 15 s)
3. PEND            rule true → alert enters "pending" for its `for:` duration
4. FIRE            still true after `for:` → alert becomes "firing"
5. DISPATCH        firing alerts sent to a dispatcher (e.g. Alertmanager)
6. GROUP/DEDUP     batch related alerts; drop duplicates
7. INHIBIT/SILENCE suppress alerts implied by others, or muted by humans
8. ROUTE           match alert to a receiver based on labels
9. NOTIFY          send page/ticket/chat; honor repeat & resolve
10. ESCALATE       if unacked within N minutes, escalate to next responder
```

We will walk every one of these steps in detail in §3.

---

## 3. How it works internally

This is the heart of the chapter. We trace the lifecycle of an alert end to end, using the **Prometheus + Alertmanager** stack as the concrete reference implementation because it is open-source, ubiquitous, and its design is the de-facto vocabulary of the field. Where another system (Grafana Alerting, Datadog, PagerDuty) differs meaningfully, it is flagged.

> **Prometheus** is an open-source time-series database and monitoring system (a CNCF — Cloud Native Computing Foundation — graduated project). It **pulls** ("scrapes") metrics over HTTP from your services on an interval, stores them as time series, evaluates alerting/recording rules, and pushes firing alerts to **Alertmanager**, a separate process that handles grouping, routing, and notification. The split is deliberate: Prometheus decides *what* is wrong; Alertmanager decides *who hears about it and how*.

### 3.1 Step 1–2: Ingestion and rule evaluation

Prometheus scrapes targets every `scrape_interval` (default **15 s**, often tuned to 15–60 s). Each scrape pulls the current value of every exposed metric. Independently, on every `evaluation_interval` (default **15 s**, set globally and overridable per rule group), Prometheus evaluates all configured **rules**.

There are two rule types:

- **Recording rules** — precompute an expensive query and store the result as a new time series. Used to make alert rules cheaper and consistent. Example: precompute a 5-minute error ratio once, then reference it in multiple alerts.
- **Alerting rules** — a **PromQL** expression that, when it returns a non-empty result, marks the matching series as a *potential* alert.

> **PromQL** (Prometheus Query Language) is the query language for Prometheus. It works on time series and supports selectors (`metric{label="x"}`), functions (`rate()`, `increase()`, `histogram_quantile()`), and operators. `rate(http_requests_total[5m])` computes the per-second average rate of a counter over the trailing 5 minutes. A **counter** is a metric that only goes up (until reset); `rate()` turns it into a meaningful per-second rate and transparently handles resets.

An alerting rule looks like:

```yaml
groups:
- name: availability
  interval: 30s            # how often this group's rules evaluate (overrides global)
  rules:
  - alert: HighErrorRate
    expr: |                # PromQL: 5xx ratio over 5 min exceeds 5%
      sum(rate(http_requests_total{status=~"5.."}[5m]))
        /
      sum(rate(http_requests_total[5m]))
      > 0.05
    for: 10m               # must stay true continuously for 10 min before firing
    labels:
      severity: page       # used later by Alertmanager for routing
      team: checkout
    annotations:
      summary: "High 5xx error rate on {{ $labels.service }}"
      description: "Error ratio is {{ $value | humanizePercentage }} (>5%) for 10m."
      runbook_url: "https://runbooks.example.com/HighErrorRate"
```

### 3.2 Step 3–4: The pending → firing state machine (`for:` and `keep_firing_for:`)

This is the most misunderstood part of Prometheus alerting. An alert instance moves through a small state machine:

```
        expr true                  still true after `for:`
INACTIVE ─────────▶ PENDING ───────────────────────────────▶ FIRING
   ▲                  │  expr false                            │
   │                  │◀──────────── (reset to INACTIVE) ──────┤ expr false
   │                                                           │   AND
   └───────────────────────────────────────────────────────── ◀ `keep_firing_for:`
                          elapsed (default 0s)
```

- **INACTIVE** — the rule expression returns nothing for this label set. No alert.
- **PENDING** — the expression just became true. The alert is *held* for the duration of `for:` (default **0s** — fire immediately). If the expression goes false during this window, the alert resets to INACTIVE and never fires. **`for:` is your primary noise filter for transient blips.**
- **FIRING** — the expression has been true continuously for at least `for:`. Now Prometheus actually *sends* the alert to Alertmanager (and keeps re-sending it every evaluation cycle, with an `EndsAt` timestamp ~3–4× the evaluation interval in the future, so Alertmanager auto-resolves if Prometheus goes silent).
- **`keep_firing_for:`** (added in Prometheus 2.42, 2023) — keeps an alert FIRING for an extra duration *after* the expression goes false, to prevent **flapping** (an alert rapidly toggling firing/resolved). Default **0s**.

Key consequence: **the minimum time to detect is `for:` + up to one `evaluation_interval`.** If you need to defend a tight SLO, `for: 10m` may be too slow — this is exactly why burn-rate alerting (§3.5) uses multiple windows.

> **Why `for:` exists:** a single bad scrape (a transient network blip, one slow request) should not page anyone. `for:` requires the bad condition to *persist*, trading a little detection latency for a lot of noise reduction. The art is choosing it long enough to filter blips but short enough to honor your SLO.

### 3.3 Step 5: Dispatch to Alertmanager

When an alert is FIRING, Prometheus POSTs it to one or more Alertmanager instances over HTTP (`/api/v2/alerts`). It re-sends every evaluation cycle. Each alert carries:

- **Labels** — identity + routing keys (`alertname`, `severity`, `team`, plus any from the rule and the metric). The *set of labels* defines the alert's **fingerprint** (its unique identity). Two alerts with identical labels are the *same* alert — this is the basis of deduplication.
- **Annotations** — human-readable context (`summary`, `description`, `runbook_url`). Not part of identity; not used for routing.
- **`startsAt` / `endsAt`** — when it began firing and when it should be considered resolved if no longer received.

For high availability you run **multiple Alertmanagers in a cluster**; they **gossip** (share state over a mesh protocol) so that a notification is sent *once* even though every Alertmanager received the alert from Prometheus. Prometheus sends to *all* of them on purpose — the cluster deduplicates. (Gossip = a peer-to-peer protocol where nodes periodically exchange state with random peers until all converge; here, "have we already notified about alert X?")

### 3.4 Steps 6–9 in Alertmanager: the dispatch pipeline

Inside Alertmanager, every incoming alert passes through a pipeline. Order matters:

```
incoming alert
   │
   ▼
[ INHIBITION ]   drop alerts implied by a higher-severity alert that's firing
   │
   ▼
[ SILENCING ]    drop alerts matching an active human-created silence/mute
   │
   ▼
[ ROUTING ]      walk the routing tree; match labels → choose a receiver + group
   │
   ▼
[ GROUPING ]     batch alerts sharing `group_by` labels into one notification
   │   (wait group_wait on first alert; then group_interval between updates)
   ▼
[ DEDUP/NOTIFY ] send via receiver integration; honor repeat_interval; on resolve, send resolved notice
```

We unpack each below in §3.6–§3.9.

### 3.5 SLO / error-budget burn-rate alerting (multi-window, multi-burn-rate)

This is the modern, recommended approach for symptom alerting on availability/latency SLOs, codified in the Google SRE Workbook (Chapter 5, *Alerting on SLOs*). It directly solves the tension between **fast detection** and **few false positives**.

#### The problem with static thresholds
A static threshold ("error rate > 5% for 10m") has two failure modes:
1. **Too sensitive** → pages on brief spikes that don't threaten the SLO.
2. **Too slow / too lax** → a sustained-but-modest error rate slowly drains your entire month's budget and you only find out when it's gone.

Neither maps to "how much of my budget am I burning?" — which is what actually matters.

#### Burn rate, precisely
Recall `burn_rate = observed_error_rate / error_budget_fraction`. For a 99.9% SLO (budget = 0.001):
- 1% observed errors → burn rate `0.01 / 0.001 = 10` → budget gone in `30 days / 10 = 3 days`.
- 10% observed errors → burn rate `100` → budget gone in `30 days / 100 = 7.2 hours`.

#### Time-to-exhaustion table (30-day window, generic)

| Burn rate | Budget consumed/hr (of 30d budget) | Time to exhaust full budget | Use as |
|---|---|---|---|
| 1× | 0.14% | 30 days | (normal) |
| 2× | 0.28% | 15 days | slow ticket |
| 6× | 0.83% | 5 days | ticket / slow page |
| 14.4× | 2% | ~2.08 days | page (fast) |
| 1000× | — | ~43 min | page (very fast) |

The famous Google SRE Workbook recipe uses **two page-level rules and one ticket-level rule**, each combining a **long window** and a **short window** to confirm the burn is still happening *right now* (the short window) and not just a stale long-window artifact:

| Severity | Long window | Short window | Burn rate | Budget consumed before firing |
|---|---|---|---|---|
| **Page (fast)** | 1 hour | 5 min | **14.4** | 2% of 30-day budget |
| **Page (slower)** | 6 hours | 30 min | **6** | 5% of 30-day budget |
| **Ticket** | 3 days | 6 hours | **1** | 10% of 30-day budget |

> **Why two windows per alert?** The *long* window (e.g. 1h) determines sensitivity — how much budget must burn before we care. The *short* window (e.g. 5m, = long/12) is an **AND** condition that the burn is *still ongoing*. Without the short window, an alert based on a 1h average keeps firing for up to an hour *after* the incident is over (the average decays slowly), producing a long noisy tail. The short window makes the alert **reset quickly** once the burn stops.

#### The PromQL for multi-window multi-burn-rate

First, define a reusable error-ratio recording rule at several windows (cheaper and consistent):

```yaml
groups:
- name: slo:job_requests:error_ratio
  rules:
  # good = non-5xx; we record the ERROR ratio at each window length
  - record: job:slo_errors_ratio:rate5m
    expr: |
      sum(rate(http_requests_total{job="api",status=~"5.."}[5m]))
        / sum(rate(http_requests_total{job="api"}[5m]))
  - record: job:slo_errors_ratio:rate30m
    expr: |
      sum(rate(http_requests_total{job="api",status=~"5.."}[30m]))
        / sum(rate(http_requests_total{job="api"}[30m]))
  - record: job:slo_errors_ratio:rate1h
    expr: |
      sum(rate(http_requests_total{job="api",status=~"5.."}[1h]))
        / sum(rate(http_requests_total{job="api"}[1h]))
  - record: job:slo_errors_ratio:rate6h
    expr: |
      sum(rate(http_requests_total{job="api",status=~"5.."}[6h]))
        / sum(rate(http_requests_total{job="api"}[6h]))
```

Then the multi-burn-rate alerts (SLO = 99.9%, so the comparison threshold is `burn_rate × 0.001`):

```yaml
groups:
- name: slo:api:burnrate-alerts
  rules:
  - alert: ApiErrorBudgetBurn_Fast
    # 14.4x burn over 1h AND still burning over 5m  → page
    expr: |
      job:slo_errors_ratio:rate1h{job="api"}  > (14.4 * 0.001)
        and
      job:slo_errors_ratio:rate5m{job="api"}  > (14.4 * 0.001)
    for: 2m                 # tiny `for:` to filter a single bad scrape
    labels:
      severity: page
      slo: api-availability
    annotations:
      summary: "API burning error budget at 14.4x (will exhaust in ~2 days)"
      runbook_url: "https://runbooks.example.com/slo-burn"

  - alert: ApiErrorBudgetBurn_Slow
    # 6x burn over 6h AND still burning over 30m  → page
    expr: |
      job:slo_errors_ratio:rate6h{job="api"}   > (6 * 0.001)
        and
      job:slo_errors_ratio:rate30m{job="api"}  > (6 * 0.001)
    for: 15m
    labels:
      severity: page
      slo: api-availability
    annotations:
      summary: "API burning error budget at 6x (will exhaust in ~5 days)"
      runbook_url: "https://runbooks.example.com/slo-burn"

  - alert: ApiErrorBudgetBurn_Ticket
    # 1x burn over 3d AND still burning over 6h  → ticket
    expr: |
      job:slo_errors_ratio:rate3d{job="api"}   > (1 * 0.001)
        and
      job:slo_errors_ratio:rate6h{job="api"}   > (1 * 0.001)
    for: 1h
    labels:
      severity: ticket
      slo: api-availability
    annotations:
      summary: "API slowly burning error budget (~10% of monthly budget consumed)"
```

(You would add `rate3d` to the recording-rule group; omitted above only for brevity — note the honesty: don't reference a series you didn't define.)

This setup pages **fast** for severe burns (within minutes), pages **less urgently** for moderate sustained burns, and **tickets** for slow drains — all three derived from the *same* SLO and the *same* symptom. This is the gold standard.

### 3.6 Step 6: Grouping & deduplication

> **Deduplication** is automatic: Alertmanager identifies each alert by its label fingerprint. Prometheus re-sends a firing alert every cycle, and an Alertmanager cluster receives copies from every peer — Alertmanager collapses all of these into a single logical alert. You get *one* notification, not one per scrape per replica.

**Grouping** batches *distinct but related* alerts into a single notification. If a database goes down and 50 services start erroring, you want **one** notification listing all 50, not 50 pages. Controlled by:

- `group_by: [alertname, cluster]` — alerts sharing these label values are grouped. `group_by: ['...']` groups by *all* labels (rarely useful); a common choice is `['alertname', 'service']` or `['cluster', 'severity']`.
- `group_wait` (default **30s**) — after the *first* alert in a new group arrives, wait this long before sending, so near-simultaneous siblings join the same notification.
- `group_interval` (default **5m**) — minimum time before sending an *updated* notification for an existing group (e.g., when a new alert joins it).
- `repeat_interval` (default **4h**) — how long before re-notifying about an *unchanged*, still-firing group (a reminder).

### 3.7 Step 7a: Inhibition

**Inhibition** suppresses lower-priority alerts when a related higher-priority alert is already firing — *automatically encoding causality*. Classic example: if a whole datacenter is down (`severity=critical, scope=datacenter`), suppress the hundreds of `severity=warning` per-service alerts inside it; the responder doesn't need 200 pages, they need the one that says "datacenter down."

```yaml
inhibit_rules:
- source_matchers: [ 'severity = critical' ]   # if a critical alert is firing...
  target_matchers: [ 'severity = warning' ]    # ...mute matching warnings...
  equal: [ 'cluster', 'service' ]               # ...but only those sharing these labels
```

Inhibition is *causal* (A causes B, so mute B); grouping is *cosmetic* (bundle related notifications); deduplication is *identity* (same alert seen many times). Don't conflate them.

### 3.8 Step 7b: Silencing

A **silence** is a *human-created, time-bounded mute*: "I know about this, stop paging me about it until 14:00." Created via the Alertmanager UI/API with matchers (label selectors) and an expiry. Used during planned maintenance, known incidents, or deploys. Unlike inhibition (automatic, rule-based) silences are manual and temporary. **Always set an expiry** — a forgotten permanent silence is how real outages get missed.

### 3.9 Steps 8–9: Routing & notification

Alertmanager has a **routing tree** — a top-level route with nested child routes. An alert walks the tree; the **first matching leaf** (with `continue: false`, the default) determines its receiver. Child routes inherit and can override grouping/timing.

```yaml
route:
  receiver: 'default-slack'        # fallback receiver
  group_by: ['alertname', 'cluster']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
  - matchers: [ 'severity = page' ]      # page-level → PagerDuty
    receiver: 'pagerduty-primary'
    group_wait: 10s                      # pages should batch less; fire faster
    continue: false
  - matchers: [ 'severity = ticket' ]    # ticket-level → Jira
    receiver: 'jira-tickets'
    repeat_interval: 24h
  - matchers: [ 'team = checkout' ]      # team-specific override
    receiver: 'checkout-pagerduty'

receivers:
- name: 'pagerduty-primary'
  pagerduty_configs:
  - routing_key: '<pd-integration-key>'
    severity: 'critical'
- name: 'jira-tickets'
  webhook_configs:
  - url: 'http://jira-bridge.internal/alert'
- name: 'checkout-pagerduty'
  pagerduty_configs:
  - routing_key: '<checkout-pd-key>'
- name: 'default-slack'
  slack_configs:
  - api_url: '<slack-webhook>'
    channel: '#alerts'
```

> **PagerDuty / Opsgenie** are commercial **incident-management/on-call platforms**. Alertmanager (or any source) sends them an *event*; they own the **on-call schedule** (who is on-call right now), **escalation policies** (if unacked in N minutes, notify the next person/level), **notification channels** (push, SMS, phone call), and **acknowledgement** (the responder presses "ack" to stop escalation). Alertmanager decides *what* is wrong and *which team*; PagerDuty/Opsgenie decide *which human* and *how hard to nag them*.

### 3.10 Step 10: Escalation (in the on-call platform)

Escalation lives in PagerDuty/Opsgenie, not Alertmanager. A typical **escalation policy**:

```
Level 1: notify primary on-call (push + SMS).
         If not ACKed in 5 min →
Level 2: notify primary again (phone call) + secondary on-call (push).
         If not ACKed in 10 min →
Level 3: notify team lead + page the secondary by phone.
         If not ACKed in 15 min →
Level 4: notify engineering manager / incident commander.
```

The lifecycle of a paged incident in the platform: **triggered → acknowledged → resolved**. Ack stops escalation but starts a separate "ack timeout" (re-page if not resolved in, say, 30 min) so an ack-and-forget doesn't bury an incident.

### 3.11 Full end-to-end trace (worked example)

Let's trace one real incident through every step. A bad deploy makes the `checkout` service return 5xx for 8% of requests.

1. **t=0:00** Deploy ships. Error ratio jumps to 8%.
2. **t=0:15** Prometheus scrape #1 sees elevated 5xx. `rate(...[5m])` is still ramping.
3. **t=0:30–2:00** `job:slo_errors_ratio:rate5m` climbs to ~0.08; `rate1h` lags (≈0.006 so far). The **fast burn** rule needs *both* `rate1h > 0.0144` and `rate5m > 0.0144`. `rate5m` already > 0.0144; `rate1h` not yet.
4. **t=~9:00** `rate1h` finally exceeds `14.4 × 0.001 = 0.0144` (it's a 1h average, so it takes time to accumulate enough error mass). Now *both* windows are over threshold. Alert enters **PENDING** (`for: 2m`).
5. **t=~11:00** Still true after `for: 2m` → alert **FIRES**. (Note: the slower 6h-window rule would take much longer; the fast rule is what catches this.)
6. **t=11:00** Prometheus POSTs `ApiErrorBudgetBurn_Fast{severity=page, slo=api-availability, service=checkout}` to all Alertmanagers.
7. **Inhibition**: no higher-severity alert firing → not inhibited.
8. **Silencing**: no active silence → not silenced.
9. **Routing**: matches `severity = page` → `pagerduty-primary` receiver; `group_wait: 10s`.
10. **Grouping**: first alert in its group → wait `group_wait` (10s) for siblings; none → send.
11. **Notify**: Alertmanager calls PagerDuty Events API → incident **triggered**.
12. **Escalation**: PagerDuty pages primary on-call (push+SMS). Responder **acks** in 3 min → escalation stops.
13. **Response**: responder opens linked runbook, confirms via dashboards (CPU normal — good, it's not capacity; 5xx correlated with new deploy version label), **rolls back**.
14. **t=~20:00** Error ratio returns to baseline. `rate5m` drops below threshold within ~5 min; the **AND** with `rate5m` fails → alert expression goes false → alert **RESOLVES** (Prometheus stops sending; Alertmanager auto-resolves at `endsAt`).
15. **Notify (resolved)**: Alertmanager sends a "resolved" notification; PagerDuty incident **resolved**.

Note the *detection latency* (~9–11 min) is dominated by the 1h long window accumulating enough error mass — a deliberate tradeoff for *precision* (no false pages on brief blips). If checkout needed faster detection, you'd add a third, even-faster rule (e.g. 30m/2m windows at a higher burn rate) or shorten the long window at the cost of more false positives.

---

## 4. The complete toolkit

### 4.1 Prometheus alerting-rule fields

| Field | Purpose | Default | Notes |
|---|---|---|---|
| `alert` | Alert name (becomes `alertname` label) | — | Required; use a clear PascalCase name |
| `expr` | PromQL expression; non-empty result = potential alert | — | Required |
| `for` | Duration expr must stay true before firing | `0s` | Primary blip filter |
| `keep_firing_for` | Keep firing after expr goes false (anti-flap) | `0s` | Added v2.42 (2023) |
| `labels` | Extra labels added to the alert (routing/severity) | `{}` | `severity` convention is critical here |
| `annotations` | Human context; templated with `{{ }}` | `{}` | Put `summary`, `description`, `runbook_url` here |

### 4.2 Prometheus global / rule-group config

| Setting | Purpose | Default |
|---|---|---|
| `global.scrape_interval` | How often targets are scraped | `15s`* (1m historically) |
| `global.evaluation_interval` | How often rules are evaluated | `15s`* |
| `rule_files` | Glob paths to rule YAML files | — |
| `group.interval` | Per-group evaluation interval override | global value |
| `alerting.alertmanagers` | Where to send firing alerts | — |

\* Defaults vary by install method; `15s` is the documented default for both, but distributions (kube-prometheus, etc.) often set `30s`/`1m`. Always check your actual config — *version/vendor-specific*.

### 4.3 Alertmanager routing & timing

| Setting | Scope | Purpose | Default |
|---|---|---|---|
| `group_by` | route | Labels that define a notification group | `[]` (group everything together)†|
| `group_wait` | route | Delay before first notification of a new group | `30s` |
| `group_interval` | route | Min delay between updates to a group | `5m` |
| `repeat_interval` | route | Re-notify interval for unchanged firing group | `4h` |
| `receiver` | route | Which receiver gets matched alerts | — |
| `matchers` | route | Label matchers to enter this route | — |
| `continue` | route | Keep matching sibling routes after this one | `false` |
| `mute_time_intervals` | route | Mute during named time windows (e.g. weekends) | — |
| `active_time_intervals` | route | Only active during named windows | — |

† `group_by: []` literally groups *all* alerts into one notification — almost never what you want. Set it deliberately (e.g. `['alertname','cluster']`). `group_by: ['...']` means "group by every label."

### 4.4 Alertmanager top-level / inhibition / silence

| Object | Key fields | Purpose |
|---|---|---|
| `inhibit_rules` | `source_matchers`, `target_matchers`, `equal` | Auto-suppress implied alerts |
| `silences` (API/UI) | `matchers`, `startsAt`, `endsAt`, `comment`, `createdBy` | Human, time-bounded mute |
| `time_intervals` | `name`, `time_intervals[]` | Named windows for mute/active intervals |
| `templates` | file globs | Custom Go templates for notification bodies |

### 4.5 Receiver integrations (built-in)

| Receiver | Use | Notable params |
|---|---|---|
| `pagerduty_configs` | Page via PagerDuty | `routing_key`/`service_key`, `severity`, `dedup_key` |
| `opsgenie_configs` | Page via Opsgenie | `api_key`, `priority`, `responders` |
| `slack_configs` | Chat | `api_url`, `channel`, `title`, `text` |
| `webhook_configs` | Generic HTTP POST | `url`, `max_alerts` |
| `email_configs` | Email | `to`, `from`, `smarthost`, `require_tls` |
| `victorops_configs` (Splunk On-Call) | Page | `api_key`, `routing_key` |
| `msteams_configs` | MS Teams | `webhook_url` |
| `telegram_configs`, `pushover_configs`, `sns_configs`, `wechat_configs` | Various | — |

### 4.6 CLI & operational tools

| Tool | Command | Purpose |
|---|---|---|
| `promtool` | `promtool check rules rules.yml` | Validate rule syntax/semantics in CI |
| `promtool` | `promtool test rules tests.yml` | **Unit-test** alerting rules with synthetic series |
| `amtool` | `amtool check-config alertmanager.yml` | Validate Alertmanager config |
| `amtool` | `amtool config routes test --config.file=... severity=page team=checkout` | Show which receiver a label set routes to (dry-run the tree) |
| `amtool` | `amtool silence add alertname=Foo --duration=2h --comment="maint"` | Create a silence from CLI |
| `amtool` | `amtool alert query` | List current alerts |
| Prometheus UI | `/alerts`, `/rules` | See INACTIVE/PENDING/FIRING state |
| Alertmanager UI | `/#/alerts`, `/#/silences` | See grouped alerts, manage silences |
| `reloader` | `kill -HUP <pid>` or `POST /-/reload` | Hot-reload rules/config without restart |

### 4.7 Useful PromQL building blocks for alerts

| Expression | Meaning |
|---|---|
| `rate(counter[5m])` | per-second avg rate over 5m (handles resets) |
| `increase(counter[1h])` | total increase over 1h |
| `histogram_quantile(0.99, sum(rate(bucket[5m])) by (le))` | p99 latency from a histogram |
| `predict_linear(node_filesystem_free_bytes[6h], 4*3600) < 0` | disk full in 4h (linear extrapolation) |
| `absent(up{job="api"})` | metric/target missing entirely (detect dead scrape) |
| `up == 0` | a scrape target is down |
| `changes(process_start_time_seconds[15m]) > 3` | process restarting (flapping) |

> **`le`** is the histogram bucket upper-bound label ("less-than-or-equal"). **`histogram_quantile`** estimates a percentile from bucketed counts. **`absent()`** is critical: if no requests arrive at all, your ratio SLO has a 0/0 problem and won't fire — `absent()` catches "the thing stopped reporting."

---

## 5. Code examples by use case

Each example is a distinct *scenario*, not a variation of one. Java appears where the topic is language-relevant (instrumentation); otherwise PromQL/YAML/CLI.

### 5.1 Use case: Instrumenting a Java service so it *can* be alerted on (Micrometer)

You cannot alert on what you don't measure. This is the upstream half of alerting. **Micrometer** is the de-facto JVM metrics facade (used by Spring Boot); it exposes a Prometheus scrape endpoint.

```java
// build.gradle: implementation 'io.micrometer:micrometer-registry-prometheus'
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class CheckoutMetrics {

    private final MeterRegistry registry;

    public CheckoutMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Wrap the checkout call so every request is counted by outcome and timed. */
    public CheckoutResult checkout(CheckoutRequest req) {
        // Timer.Sample captures start time; we record on completion with tags.
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            CheckoutResult r = doCheckout(req);          // real work
            if (!r.isOk()) outcome = "client_error";     // 4xx-class
            return r;
        } catch (RuntimeException e) {
            outcome = "server_error";                    // 5xx-class → SLI "bad"
            throw e;
        } finally {
            // One timer with tags = both a counter (count) AND latency histogram.
            sample.stop(Timer.builder("http.server.requests")
                .tag("uri", "/checkout")
                .tag("outcome", outcome)                 // success|client_error|server_error
                .publishPercentileHistogram()            // emit le-buckets for histogram_quantile
                .register(registry));
        }
    }
    // ...
}
```

This exposes `http_server_requests_seconds_count{uri="/checkout",outcome="server_error"}` (for the availability SLI) and `http_server_requests_seconds_bucket{...,le="..."}` (for the latency SLI). Spring Boot auto-publishes these at `/actuator/prometheus`. **Design note:** tag with `outcome` (semantic) rather than raw status codes where possible — it keeps SLI definitions stable as status mappings evolve, and keeps **cardinality** bounded (see §6.1).

### 5.2 Use case: Latency-SLO burn-rate page (p99 too slow)

Symptom: too many requests slower than the 300 ms target. Define the latency SLI as *fraction served under 300 ms* and alert on burning its budget. SLO = 99% under 300ms (budget 0.01).

```yaml
groups:
- name: slo:checkout:latency
  rules:
  # "good" = requests with le <= 0.3s. Fast ratio = good / total at each window.
  - record: checkout:latency_good_ratio:rate5m
    expr: |
      sum(rate(http_server_requests_seconds_bucket{uri="/checkout",le="0.3"}[5m]))
        / sum(rate(http_server_requests_seconds_count{uri="/checkout"}[5m]))
  - record: checkout:latency_good_ratio:rate1h
    expr: |
      sum(rate(http_server_requests_seconds_bucket{uri="/checkout",le="0.3"}[1h]))
        / sum(rate(http_server_requests_seconds_count{uri="/checkout"}[1h]))

  - alert: CheckoutLatencyBudgetBurnFast
    # bad ratio = 1 - good ratio; page when bad burn rate >= 14.4 on both windows
    expr: |
      (1 - checkout:latency_good_ratio:rate1h) > (14.4 * 0.01)
        and
      (1 - checkout:latency_good_ratio:rate5m) > (14.4 * 0.01)
    for: 2m
    labels: { severity: page, slo: checkout-latency }
    annotations:
      summary: "Checkout latency SLO burning fast: too many requests > 300ms"
      runbook_url: "https://runbooks.example.com/checkout-latency"
```

**Why bucket-ratio, not `histogram_quantile`?** Alerting on `histogram_quantile(0.99,...) > 0.3` is intuitive but statistically fragile (interpolation error, sensitive to bucket boundaries, no notion of *budget*). The bucket-ratio approach ("what fraction breached my threshold") maps cleanly to an SLI and a budget. Use `histogram_quantile` on *dashboards*; use bucket-ratios in *SLO alerts*.

### 5.3 Use case: The rare legitimate cause-based alert (disk will fill)

`predict_linear` extrapolates a trend. This is the textbook "page before the symptom because you need lead time" case.

```yaml
- alert: DiskWillFillIn4Hours
  expr: |
    predict_linear(node_filesystem_avail_bytes{mountpoint="/data"}[6h], 4*3600) < 0
      and
    node_filesystem_avail_bytes{mountpoint="/data"} / node_filesystem_size_bytes{mountpoint="/data"} < 0.15
  for: 30m              # require the trend to persist; ignore transient dips
  labels: { severity: page }
  annotations:
    summary: "/data on {{ $labels.instance }} projected full within 4h"
    runbook_url: "https://runbooks.example.com/disk-fill"
```

The second clause (`< 15% free`) guards against `predict_linear` panicking over a tiny temporary downward blip when the disk is mostly empty. **Two conditions ANDed** is a recurring noise-reduction pattern: a *trend* AND a *current-severity floor*.

### 5.4 Use case: Detecting "the metric stopped" (dead-man's switch)

If a service stops emitting metrics entirely (crashed, network-partitioned, scrape misconfigured), ratio-based alerts silently *cannot* fire (no data). You need explicit absence detection — and a **heartbeat / dead-man's switch** to detect that *Prometheus or Alertmanager itself* is down.

```yaml
# 1. Detect a specific job going dark.
- alert: ApiTargetMissing
  expr: absent(up{job="api"}) == 1
  for: 5m
  labels: { severity: page }
  annotations: { summary: "No 'api' targets are being scraped" }

# 2. Watchdog: an alert that is ALWAYS firing on purpose.
- alert: Watchdog
  expr: vector(1)         # always true → always firing
  labels: { severity: none }
  annotations:
    summary: "This alert is always firing; absence means monitoring is broken."
```

The `Watchdog` is routed to a **dead-man's-switch receiver** (e.g. PagerDuty's "if I *stop* hearing from you, page" feature, or an external healthcheck like Dead Man's Snitch / healthchecks.io). The logic inverts: **silence from the watchdog = your whole monitoring pipeline is down.** Without this, a monitoring outage looks identical to "everything is healthy."

### 5.5 Use case: Multi-tenant routing with per-team escalation

A platform team runs Alertmanager for many product teams. Route by `team` label, give each its own receiver/escalation, and fall through to a catch-all.

```yaml
route:
  receiver: platform-catchall
  group_by: ['alertname', 'team', 'cluster']
  routes:
  - matchers: ['severity = page']
    group_wait: 10s
    routes:                                   # nested: severity=page AND team=X
    - matchers: ['team = payments']
      receiver: payments-pagerduty
    - matchers: ['team = search']
      receiver: search-opsgenie
    - matchers: ['team =~ "growth|marketing"']  # regex match
      receiver: growth-pagerduty
  - matchers: ['severity = ticket']
    receiver: jira-bridge
    repeat_interval: 24h
  # Mute non-critical pages during a known maintenance window (Sundays 02:00-04:00 UTC)
  - matchers: ['severity = page', 'team = batch']
    receiver: batch-pagerduty
    mute_time_intervals: [sunday-maint]

time_intervals:
- name: sunday-maint
  time_intervals:
  - weekdays: ['sunday']
    times: [{ start_time: '02:00', end_time: '04:00' }]
    location: 'UTC'
```

Validate routing *before* shipping:

```bash
amtool config routes test \
  --config.file=alertmanager.yml \
  severity=page team=payments
# → prints: payments-pagerduty
```

### 5.6 Use case: Unit-testing alert rules in CI (`promtool test`)

Alert rules are code; test them. This catches "I refactored the metric name and silently broke the page" — a real and common outage cause.

```yaml
# tests/burnrate_test.yml
rule_files: [ ../rules/slo_api.yml ]
evaluation_interval: 1m

tests:
- interval: 1m
  input_series:
    # 8% of requests are 5xx for the whole test window (high burn)
    - series: 'http_requests_total{job="api",status="500"}'
      values: '0+8x180'        # +8 each minute, 180 samples (3h)
    - series: 'http_requests_total{job="api",status="200"}'
      values: '0+92x180'       # +92 each minute
  alert_rule_test:
    - eval_time: 70m           # by 70m the 1h window has accumulated enough
      alertname: ApiErrorBudgetBurn_Fast
      exp_alerts:
        - exp_labels: { severity: page, slo: api-availability, job: api }
          exp_annotations:
            summary: "API burning error budget at 14.4x (will exhaust in ~2 days)"
```

Run in CI:

```bash
promtool check rules rules/slo_api.yml      # syntax/semantic lint
promtool test rules tests/burnrate_test.yml # behavioral test
```

### 5.7 Use case: Grafana Alerting (the non-Prometheus path)

> **Grafana** is a dashboarding/visualization tool that, since v8/v9, ships **Grafana Alerting** — a unified alerting engine that can query *any* datasource (Prometheus, Loki for logs, SQL, CloudWatch) and route through its own Alertmanager-compatible notification policies. It uses the same routing/grouping vocabulary but lets you alert on **logs** and **mixed datasources**, which Prometheus alone cannot.

A Grafana alert is defined as: a **query** (datasource expression) → a **reduce/threshold expression** → a **condition** → notification policy. The mental model is identical (symptom, `for:`, severity, route), but the UI/JSON differs. The portability lesson: **the design principles in this chapter are tool-agnostic; only the syntax changes.**

### 5.8 Use case: Routing a page to a webhook bridge (custom integration)

When no native receiver exists (custom ticketing, internal incident tool), use `webhook_configs` and write a tiny bridge. Example payload handling (the bridge, in Java/Spring):

```java
@RestController
public class AlertWebhookController {

    private final IncidentService incidents;

    public AlertWebhookController(IncidentService incidents) { this.incidents = incidents; }

    // Alertmanager POSTs the v4 webhook payload here.
    @PostMapping("/alert")
    public ResponseEntity<Void> receive(@RequestBody AmWebhookPayload payload) {
        for (AmAlert a : payload.alerts()) {
            if ("firing".equals(a.status())) {
                incidents.openOrUpdate(
                    a.labels().get("alertname"),
                    a.labels().get("severity"),
                    a.annotations().getOrDefault("runbook_url", ""),
                    a.fingerprint());                    // stable dedup key from AM
            } else { // "resolved"
                incidents.resolve(a.fingerprint());
            }
        }
        return ResponseEntity.ok().build();   // 2xx = AM considers delivery successful
    }
}
// AmWebhookPayload / AmAlert: records mirroring Alertmanager's JSON
// (status, alerts[], each with labels, annotations, status, fingerprint, startsAt, endsAt)
```

Critical detail: **honor `status: resolved`** and key everything on `fingerprint` (Alertmanager's stable per-alert hash), or you will create duplicate incidents that never close.

---

## 6. Implementation concerns & best practices

### 6.1 Cardinality and cost

> **Cardinality** = the number of *distinct label-value combinations* (= distinct time series). A metric `http_requests{user_id="...", status="..."}` with 1M users explodes into millions of series — this is the #1 way to OOM Prometheus and blow up your bill.

- **Never put unbounded values in labels**: `user_id`, `email`, full URLs with IDs, request IDs, raw error messages. Bucket or omit them.
- High cardinality also breaks alerting: per-series alerts fan out into thousands of alert instances, overwhelming Alertmanager and the responder.
- Cost driver in hosted backends (Grafana Cloud, Datadog, Chronosphere): you typically pay per **active time series** and per **custom metric**. Alerting design and metric design are linked — keep SLI metrics low-cardinality (aggregate by route/service, not by user).
- Use **recording rules** to pre-aggregate; alert on the aggregate, not the raw high-cardinality series.

### 6.2 Performance of rule evaluation

- Heavy `expr` over long ranges (`[6h]`, `[3d]`) on high-cardinality data is expensive every `evaluation_interval`. **Recording rules** amortize this: compute once, alert on the cheap recorded series.
- `evaluation_interval` too low (e.g. 5s) multiplies query load; too high delays detection. 15–30s is typical.
- A slow rule group can delay *all* rules in it (groups evaluate sequentially). Split heavy rules into their own group with a longer interval.
- For very large fleets, consider **Thanos/Cortex/Mimir** (horizontally scalable, long-term Prometheus-compatible backends) which run rule evaluation as a separate, scalable component (the "ruler").

### 6.3 Correctness & concurrency pitfalls

- **0/0 NaN traps**: `errors/total` is `NaN` when total is 0 (no traffic). A `NaN > 0.05` is *false*, so a fully-dead service can *fail to alert*. Guard with `absent()`/`up==0` (§5.4) and/or `clamp_min(total, 1)` patterns, or require a minimum traffic rate in the alert.
- **Counter resets**: always use `rate()`/`increase()`, never raw counter subtraction — `rate()` handles process restarts (counter resets to 0).
- **Clock/window skew**: the short-window AND in burn-rate alerts assumes both windows query the same data; ensure recording-rule names match the windows you reference (don't reference `rate3d` if you only recorded `rate1h`).
- **Eventual consistency of an HA Alertmanager cluster**: during a network partition, you can get *duplicate* notifications (gossip can't dedupe across the partition). This is by design — duplicate-but-delivered beats silent-and-missed.

### 6.4 Security

- **Secrets in config**: `routing_key`, Slack webhooks, SMTP creds live in `alertmanager.yml`. Inject via files/secret stores (Kubernetes Secrets, Vault), never commit them. Alertmanager supports `*_file` variants for some secrets.
- **Webhook authenticity**: a `/alert` webhook bridge is an unauthenticated incident-creation endpoint unless you protect it (mTLS, shared secret header, network policy). Attackers triggering fake incidents = a DoS on your humans.
- **Annotation injection**: annotations are templated from metric labels; a malicious/buggy service emitting crafted label values can inject misleading text or links into a page. Sanitize/limit which labels feed `runbook_url`.
- **Silences as an attack/footgun surface**: a broad silence (`alertname=~".*"`) mutes everything. Restrict who can create wide silences; log silence creation.

### 6.5 Observability of the alerting system itself (meta-monitoring)

You must monitor the monitor:

| Signal | Metric / check | Why |
|---|---|---|
| Prometheus up | external healthcheck + Watchdog | If it's down, *no* alerts fire |
| Rule eval health | `prometheus_rule_evaluation_failures_total`, `prometheus_rule_group_iterations_missed_total` | Failed/skipped evals = blind spots |
| Notification delivery | `alertmanager_notifications_failed_total` | PagerDuty/Slack delivery failing = silent pages |
| Alertmanager cluster | `alertmanager_cluster_members` | Split cluster → dupes or gaps |
| End-to-end | Dead-man's switch (§5.4) | The only true end-to-end test |

### 6.6 Runbooks & the on-call experience (production hardening)

A good runbook, linked from *every* page, contains:
1. **What this alert means** (in one sentence) and which SLO it protects.
2. **Verify it's real** — the dashboard link, the query, the "is this a known false positive?" check.
3. **Assess impact/severity** — how to tell how many users are affected.
4. **Mitigate** — concrete first actions (rollback, scale up, failover, feature-flag off). Mitigation before root-causing.
5. **Escalate** — who/what to pull in, and how to declare an incident.
6. **Links** — dashboards, logs query, related services, recent deploys.

On-call hygiene that alerting design enables:
- **Page budget**: track pages/shift; if > ~2/shift sustained, treat it as a bug to fix (tune or delete alerts, or fix the service).
- **Alert review ritual**: weekly, review every page that fired. For each: *was it actionable? urgent? novel?* If "no" to any → fix or delete it. This is the single highest-leverage practice for fighting fatigue.
- **Hand-off**: each rotation documents what's noisy/known so the next on-call isn't surprised.
- **Compensation/fairness**: pages have a human cost; rotations should be staffed and paid such that being on-call is sustainable.

### 6.7 Testing alerts

- **Unit test** rules with `promtool test rules` (§5.6) in CI — block merges on failures.
- **Routing tests** with `amtool config routes test` — assert a given label set reaches the intended receiver.
- **Fire drills / game days**: deliberately inject failure (or fire a test alert) end-to-end and confirm a human actually got paged and could ack. Schedule these; a paging path that's never tested is assumed broken.
- **Chaos for alerting**: kill a target and verify `absent()`/`up==0` fires; pause Prometheus and verify the dead-man's switch fires.

### 6.8 Anti-patterns (memorize this list)

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Paging on causes (CPU, RAM, restarts) | Noisy, misses real outages | Page on symptoms; dashboard the causes |
| Non-actionable pages ("FYI: traffic up") | Trains people to ignore pages | Make it a dashboard or delete it |
| Static thresholds for SLO availability | Wrong sensitivity, no budget notion | Multi-window burn-rate |
| No `for:` / `for:` too short | Pages on single bad scrape | Set `for:` to filter blips |
| One alert per cause (CPU, disk, GC, …) | Alert sprawl, fatigue | Consolidate to symptom alerts |
| No runbook link | Slow, error-prone response | Link a runbook on every page |
| No dead-man's switch | Monitoring outage = silent | Watchdog + external snitch |
| Forgotten permanent silences | Real outages muted | Always set expiry; audit silences |
| Unbounded label cardinality | OOM / cost blowup / alert fan-out | Bound labels; pre-aggregate |
| Treating tickets as pages | Burns out on-call | Route by urgency tier |
| `histogram_quantile` thresholds for SLO alerts | Statistically fragile | Bucket-ratio SLIs |
| 0/0 NaN blindness | Dead service doesn't alert | `absent()` / `up==0` / min-traffic guard |

---

## 7. Advanced topics & deep internals

### 7.1 Choosing burn-rate windows from first principles

The Google SRE Workbook gives the recipe; here's *how to derive your own*. For a long window `L` and burn rate `b`, the **fraction of budget consumed before the alert fires** is:

```
budget_consumed = b × (L / compliance_window)
```

For `b = 14.4`, `L = 1h`, `compliance_window = 30d = 720h`:
`14.4 × (1/720) = 0.02` → **2%** of the monthly budget burned before firing. Tune the pair `(b, L)` to your tolerances:
- **Detection time** (how fast it fires): roughly `L × (SLO_budget / observed_error_rate)` until the long window accumulates enough mass — shorter `L` and higher `b` = faster but more false positives.
- **Precision** (fraction of pages that are "real"): longer windows and the short-window AND raise precision.
- **Reset time**: governed by the short window (≈ `L/12` in the standard recipe); shorter short-window = faster reset, less noisy tail.

The four canonical knobs you trade: **detection time, precision, recall, reset time.** You cannot maximize all four; burn-rate multi-window is the Pareto-optimal compromise the SRE community converged on.

### 7.2 `keep_firing_for` and flap damping internals

Before `keep_firing_for` (Prometheus < 2.42), the only flap defenses were a long `for:` (delays *detection*, bad) or `keep_firing_for`-style hacks in Alertmanager. Now: an alert that resolves and re-fires within `keep_firing_for` is treated as *continuously firing* — no resolve/re-fire notification churn. Set it to ~5–15m for metrics known to oscillate near a threshold. Note it trades **faster resolution** for **fewer flaps** — the resolved notification is delayed.

### 7.3 Alertmanager grouping timing internals (the three timers)

When alerts hit a group, three timers interact:
- `group_wait` (default 30s): on the *first* alert of a *new* group, hold this long so siblings coalesce. Lower for pages (10s) to fire fast.
- `group_interval` (default 5m): after the initial notification, the *minimum* gap before a notification reflecting *changes* to the group (new/resolved members).
- `repeat_interval` (default 4h): if the group is *unchanged* and still firing, re-notify after this long (a nag). Set high for tickets (24h), moderate for pages.

Subtle behavior: a *resolved* member triggers a notification governed by `group_interval`, not `repeat_interval`. And a brand-new alert joining an existing group waits at most `group_interval`, not `group_wait`.

### 7.4 Inhibition edge cases

- Inhibition is *not transitive*: A inhibits B, B inhibits C does **not** mean A inhibits C. Define explicit rules.
- The `equal` labels must exist on *both* source and target, with the *same value*, for inhibition to apply. A typo'd or missing `equal` label silently disables inhibition — test it.
- Inhibition only suppresses *notification*; the target alert is still "firing" in the API/UI. Don't rely on inhibition to clear state.

### 7.5 Symptom alerting at the edge vs internally

The most user-truthful place to measure symptoms is **closest to the user**: load balancer / API gateway / CDN logs, or **black-box probing** (synthetic requests from outside). Internal service metrics can show "all green" while the edge is broken (DNS, LB misconfig, TLS expiry). Best practice: **combine** black-box (does an external probe succeed?) with white-box (internal SLIs explain *why*). Black-box alerts have *high precision for "users are affected"*; white-box alerts have *high diagnosability*.

> **Black-box monitoring** = testing the system from the outside as a user would (synthetic HTTP checks, e.g. Prometheus **Blackbox Exporter**). **White-box monitoring** = using internal instrumentation (the metrics your code emits). You want both.

### 7.6 Alert dependencies and topology-aware suppression

Beyond static inhibition, advanced platforms (BigPanda, Moogsoft, PagerDuty Event Intelligence, Grafana OnCall) do **alert correlation**: cluster alerts by time, topology, and ML similarity into a single incident, suppressing downstream noise automatically. This is the productized version of "the database is down so don't page me for all 50 dependent services." Useful at scale; adds a vendor and a black box — prefer explicit inhibition for critical, well-understood causalities.

### 7.7 Multi-window with multiple SLO windows (rolling vs calendar)

Compliance windows can be **rolling** (last 30 days, continuously) or **calendar** (this month, resets on the 1st). Rolling avoids the "budget resets, party on the 1st" cliff but means a bad day haunts you for 30 days. Burn-rate alerts work with either, but the *budget-consumed* math assumes a fixed window length; document which you use, because it changes how error-budget *policy* (freeze deploys when budget exhausted) is enforced.

### 7.8 Notification ordering, throttling, and rate limits

PagerDuty/Opsgenie/Slack impose API rate limits. A storm of distinct alerts (poor grouping) can hit them and get throttled → **dropped pages**. Defenses: aggressive `group_by`, `max_alerts` on webhook configs, inhibition, and severity-based routing so only true pages reach the rate-limited paging API. Monitor `alertmanager_notifications_failed_total` by integration.

### 7.9 Lesser-known behaviors

- Prometheus sends a firing alert's `endsAt` as `now + 4 × resend_interval` (default resend ~1m, so ~4m). If Prometheus crashes, Alertmanager auto-resolves after `endsAt` — which can **mask** an ongoing incident if Prometheus itself died. The dead-man's switch is the guard.
- `for:` is **not** persisted across Prometheus restarts: a restart resets pending alerts' timers, so a flapping restart loop can prevent any alert from ever satisfying `for:`. Keep Prometheus stable; alert on its restart count.
- Alertmanager silences are stored in its own state (gossiped + on disk). A wiped Alertmanager loses silences → previously-silenced alerts suddenly page.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Static threshold vs burn-rate alerting

| Dimension | Static threshold | Multi-window burn-rate |
|---|---|---|
| Maps to SLO budget | No | Yes |
| False positives on blips | High (unless long `for:`) | Low (short-window AND) |
| Detection of slow drains | Poor (never crosses threshold) | Yes (ticket-tier rule) |
| Setup complexity | Low | Medium (recording rules + 3 alerts) |
| Tunability | Coarse | Fine (4 knobs) |
| **Use when** | Quick wins, non-SLO causes (disk fill), low-traffic services where ratios are noisy | Any availability/latency SLO with sufficient traffic |
| **Avoid when** | You have a real SLO to defend | Very low traffic (ratios are statistically unstable — use absolute counts or relax) |

### 8.2 Page vs ticket vs dashboard (decision rule)

```
Is a human action required?         no  → dashboard/log (do not alert)
       yes ↓
Must it happen NOW (can't wait)?    no  → ticket
       yes ↓
Is it a user-visible symptom (or
unavoidable imminent one)?          no  → reconsider; likely dashboard
       yes ↓
                                          → PAGE  (link a runbook)
```

### 8.3 Tool/platform comparison

| Concern | Prometheus + Alertmanager | Grafana Alerting | Datadog | PagerDuty/Opsgenie |
|---|---|---|---|---|
| Role | Eval + dispatch | Eval (multi-source) + dispatch | Eval + dispatch (SaaS) | On-call, escalation, incident mgmt |
| Datasources | Prometheus metrics | Any (metrics/logs/SQL) | Datadog telemetry | (consumes events) |
| Alert on logs | No (need Loki) | Yes | Yes | n/a |
| Hosting | Self-host (or Mimir/Thanos) | Self/Cloud | SaaS only | SaaS |
| Cost model | Infra you run | Infra/Cloud | Per host + per series | Per user/seat |
| Strength | Open, powerful PromQL, k8s-native | Unified, multi-source | Turnkey, integrated | Best-in-class paging/escalation |
| Weakness | Single-binary scaling limits; no log alerts | Less mature than AM at huge scale | Vendor lock-in, cost | Not a metrics evaluator |

Common production combo: **Prometheus(+Mimir) → Alertmanager (route by severity/team) → PagerDuty (on-call + escalation)**, with **Grafana** for dashboards.

### 8.4 Black-box vs white-box alerting

| | Black-box (probe) | White-box (internal SLI) |
|---|---|---|
| Truth about user impact | High | Medium (can miss edge issues) |
| Diagnosability | Low | High |
| Catches LB/DNS/TLS issues | Yes | Often no |
| Use as | Outer "are users affected?" page | Burn-rate SLO pages + diagnosis |

Decision: **page on white-box SLO burn-rate as primary; back it with a black-box availability probe** for the failure modes internal metrics can't see.

---

## 9. Failure modes & debugging

### 9.1 "The alert didn't fire" (false negative)

Likely causes & checks:
1. **0/0 NaN** — no traffic so the ratio is NaN; `NaN > x` is false. *Check*: query the `expr` in Prometheus UI; add `absent()`/`up==0`/min-traffic guards.
2. **Metric renamed / label changed** — a refactor broke the selector. *Check*: `promtool check rules` in CI; query the raw metric exists; run `promtool test rules`.
3. **`for:` never satisfied** — flapping or Prometheus restarts reset the pending timer. *Check* Prometheus `/alerts` for stuck PENDING; check `changes(process_start_time_seconds[1h])`.
4. **Silenced/inhibited** — *Check* Alertmanager UI `/#/silences` and inhibition rules. `amtool silence query`.
5. **Routing dropped it** — matched a route with no real receiver, or a wrong matcher. *Check* `amtool config routes test <labels>`.
6. **Notification delivery failed** — PagerDuty key rotated/expired. *Check* `alertmanager_notifications_failed_total`; Alertmanager logs.
7. **Monitoring itself down** — *Check* the dead-man's switch fired (it should have).

### 9.2 "Too many alerts" (alert storm / fatigue)

1. **Cause-based sprawl** — dozens of CPU/restart alerts. *Fix*: delete causes; symptom-only pages.
2. **Bad grouping** — 50 notifications for one root cause. *Fix*: set `group_by` to the right shared labels; add inhibition for the known causal alert.
3. **Flapping** — alert toggles rapidly. *Fix*: `for:` and/or `keep_firing_for:`; check the metric near the threshold.
4. **Cardinality fan-out** — per-instance/per-user alerts. *Fix*: aggregate in the `expr` (`sum without(instance)`), pre-aggregate via recording rules.
5. **Diagnose volume**: `sum by (alertname) (ALERTS{alertstate="firing"})` ranks your noisiest alerts — start your weekly review there.

### 9.3 "Duplicate pages"

- HA Alertmanager partition (expected, transient). *Check* `alertmanager_cluster_members`, cluster logs.
- Webhook bridge not keying on `fingerprint` / not handling `resolved` (§5.8). *Fix* dedup by fingerprint.
- Multiple Prometheis sending overlapping alerts without consistent labels. *Fix* externalLabels to distinguish or dedupe.

### 9.4 "Pages are too slow"

- Long burn-rate window dominates detection (§3.11). *Fix*: add a faster window/higher burn-rate rule; accept more false positives for critical SLOs.
- `evaluation_interval` too high or rule group is slow/backed up. *Check* `prometheus_rule_group_last_duration_seconds`, `..._iterations_missed_total`. *Fix*: split groups, use recording rules.
- `group_wait`/`group_interval` adding delay on a page route. *Fix*: lower `group_wait` (e.g. 10s) on page routes.

### 9.5 Diagnostic command cheat-set

```bash
# What's firing right now, ranked by noisiness:
#   (run in Prometheus UI / API)
topk(20, sum by (alertname) (ALERTS{alertstate="firing"}))

# Did this rule group fail or fall behind?
rate(prometheus_rule_evaluation_failures_total[5m])
prometheus_rule_group_iterations_missed_total

# Are notifications being delivered?
rate(alertmanager_notifications_failed_total[5m])

# Where would this alert route?
amtool config routes test --config.file=alertmanager.yml severity=page team=payments

# What's silenced/inhibited?
amtool silence query
amtool alert query --inhibited

# Validate & test before shipping:
promtool check rules rules/*.yml
promtool test rules tests/*.yml
amtool check-config alertmanager.yml
```

### 9.6 Real-world incident archetypes (composite, illustrative)

- **The 2 a.m. CPU page that wasn't a problem.** A team paged on `cpu > 80%`. A nightly batch job ran every night at 2 a.m., spiking CPU; the service was perfectly fast. Six months of woken engineers. *Lesson*: never page on a cause; the symptom (latency/errors) was always green.
- **The silent outage.** A service crashed and stopped emitting metrics. The ratio-based error alert was `errors/total > 0.05` — but with zero total, it was NaN, never fired. Users were down for 40 minutes before a customer complained. *Lesson*: `absent()`/`up==0` and a dead-man's switch are not optional.
- **The forgotten silence.** During a deploy, an engineer silenced "all checkout alerts" for "a few hours" with a 7-day expiry and forgot. A real checkout outage three days later paged nobody. *Lesson*: tight silence expiries; audit active silences; never silence by broad regex.
- **The alert storm that hid the real page.** A core database failed; 200 dependent-service warning alerts fired simultaneously, the paging API got rate-limited, and the *one* critical "database down" page was throttled/buried. *Lesson*: inhibition + severity routing so only the causal critical alert pages; grouping to collapse the rest.
- **The slow drain nobody saw.** A 0.3% sustained error rate (just under any static threshold) quietly burned the entire monthly error budget over three weeks; the SLO was missed with no single page ever firing. *Lesson*: the ticket-tier 1× burn-rate rule exists precisely to catch this.

---

## 10. Interview drill

**Q1. Why alert on symptoms, not causes? Give an example where a cause-based alert fails.**
*Model answer:* Causes are infinite and noisy (high CPU is fine if the service is fast; a restart during deploy is benign), and they miss real outages (a deadlocked service with low CPU). Symptoms are few, user-truthful, and catch *any* cause. Example: a service whose dependency is down has normal CPU/disk but 100% errors — a CPU alert is silent, a symptom (error-rate) alert fires. Causes belong on dashboards/runbooks for *diagnosis*, not on pages.
- *Probe: Name a legitimate exception.* "Disk will fill in 4h" — you need lead time because you can't un-fill a disk instantly. Rare; treat as exception.
- *Probe: Where should the symptom be measured?* As close to the user as possible (edge/LB/black-box probe), backed by internal white-box SLIs for diagnosis.
- *Probe: How do causes still help?* They're the diagnostic graphs the on-call uses after the symptom page fires; they live on dashboards and in runbooks.

**Q2. Define burn rate and explain the multi-window multi-burn-rate approach.**
*Model answer:* Burn rate = observed_error_rate / error_budget_fraction; it's how many times faster than "sustainable" you're spending budget. Multi-window uses a long window (sets sensitivity / how much budget burned before firing) ANDed with a short window (confirms the burn is *still happening now*, so the alert resets fast). The standard recipe: page at 14.4× (1h/5m) and 6× (6h/30m), ticket at 1× (3d/6h).
- *Probe: Why the short window?* Without it, a 1h-average alert keeps firing for ~an hour after the incident ends (slow-decaying average) — a noisy tail. The short window makes it reset within minutes.
- *Probe: How do you derive budget-consumed-before-firing?* `b × (L / compliance_window)`; 14.4 × (1h/720h) = 2%.
- *Probe: When does this break?* Low-traffic services — ratios over short windows are statistically unstable; use absolute counts or a longer window, or relax to availability probes.

**Q3. Page vs ticket vs dashboard — how do you decide?**
*Model answer:* Three questions: (1) Is action required? No → dashboard. (2) Is it urgent (can't wait till morning)? No → ticket. (3) Is it a user-visible symptom (or unavoidable imminent one)? Yes → page with a runbook. Misclassifying tickets as pages is the top cause of on-call burnout.
- *Probe: What's your page budget?* ~≤2 actionable incidents per 12h shift (Google heuristic); sustained excess is a bug to fix, not staff around.
- *Probe: Give a ticket-worthy alert.* Cert expires in 20 days; slow 1× burn-rate drain. Actionable but not urgent.

**Q4. What is alert fatigue and how do you fight it?**
*Model answer:* Fatigue is responders becoming desensitized from too many low-value alerts, so they ignore or slow-ack the important ones. Fight it with: symptom-only pages, deduplication (identity), grouping (batch related), inhibition (suppress implied), silencing (planned mutes), tuning `for:`/`keep_firing_for:`, bounding cardinality, and a *weekly alert review* that deletes/fixes any page that wasn't actionable+urgent+novel.
- *Probe: Difference between dedup, grouping, inhibition?* Dedup = same alert seen many times → one. Grouping = different-but-related alerts → one notification. Inhibition = higher-severity alert suppresses implied lower ones (causal).
- *Probe: Metric to find your worst alerts?* `topk(sum by (alertname) (ALERTS{alertstate="firing"}))` and pages/shift per alert.

**Q5. Walk me through what `for:` does and a failure mode it has.**
*Model answer:* `for:` holds an alert in PENDING and only fires if the expression stays true continuously for that duration — filtering transient blips at the cost of detection latency. Failure mode: it's not persisted across Prometheus restarts, so a crash-looping Prometheus resets the timer and the alert may *never* fire; also too-long `for:` can violate a tight SLO's allowable detection time.
- *Probe: How is flapping handled separately?* `keep_firing_for:` keeps it firing briefly after the expr goes false, preventing resolve/re-fire churn.
- *Probe: What's the minimum detection time?* ≈ `for:` + one `evaluation_interval` (+ window accumulation time for rate-based exprs).

**Q6. (Senior signal) Your on-call is getting 15 pages a shift. Walk me through how you'd fix it.**
*Model answer:* Treat it as an engineering problem, not a staffing one. (1) Pull the data: rank alerts by fire count (`topk(sum by alertname ALERTS)`). (2) For each top alert, ask actionable/urgent/novel — delete or demote (to ticket/dashboard) any that fail. (3) Convert cause-based pages (CPU/restarts) to dashboards; replace with symptom/burn-rate pages. (4) Fix grouping (`group_by`) and add inhibition for known causal storms. (5) Add `for:`/`keep_firing_for:` to flappers. (6) Institute a weekly alert review and a page budget (~2/shift). (7) If a service is genuinely that unreliable, the fix is reliability work funded by the error budget, not more alerts. Track pages/shift as the KPI.
- *Probe: How do you get buy-in to delete alerts people are scared to remove?* Show that each deleted alert never produced an action in the last N weeks; demote rather than delete first (route to ticket) to de-risk.
- *Probe: What if the volume is legitimate (service really is on fire)?* Then it's an SLO/reliability problem: freeze feature work per error-budget policy and invest in reliability; alerting can't fix an unreliable service.

**Q7. (Senior signal) Static thresholds vs burn-rate — when would you *choose* static thresholds?**
*Model answer:* Burn-rate is right for SLO-backed availability/latency on services with enough traffic. Static thresholds are right for: (a) non-SLO causes that genuinely need lead time (disk-fill via `predict_linear`), (b) very low-traffic services where ratios are statistically noisy (use absolute counts/thresholds), (c) quick interim coverage before SLOs exist, and (d) hard physical/contractual limits (e.g., connection pool at 100%). The senior signal is recognizing burn-rate isn't universally superior — it requires traffic volume and a defined SLO to be meaningful.
- *Probe: How would you alert a 5-req/min service?* Avoid ratio alerts; use absolute error counts over a longer window, plus a black-box probe, and accept coarser detection.
- *Probe: What breaks burn-rate on low traffic?* 0/0 NaN and high variance — a single error is a huge instantaneous "rate," causing false pages or, with guards, silence.

**Q8. (Senior signal) Design the alerting + routing for a 6-team platform. What are the key decisions?**
*Model answer:* (1) Standardize a `severity` (page/ticket/none) and `team`/`slo` label convention enforced in CI. (2) Each team owns symptom/burn-rate alerts on their SLOs; platform owns infra symptom alerts + the dead-man's switch. (3) Alertmanager routing tree: top-level by severity, nested by team → each team's PagerDuty/Opsgenie escalation policy. (4) Inhibition for cross-cutting causes (cluster/DB down suppresses dependents). (5) Grouping by `[alertname, team, cluster]`; lower `group_wait` on page routes. (6) Meta-monitoring + watchdog. (7) Governance: weekly alert review per team, page budgets, runbook-on-every-page requirement, silence-audit. Validate routing with `amtool config routes test` in CI.
- *Probe: How do you stop a DB outage from paging all 6 teams?* Inhibition keyed on the causal critical alert + correlation; the DB-owning team pages, dependents' warnings are suppressed.
- *Probe: Where do secrets (PD keys) live?* In a secret store/Secrets, injected at deploy via `*_file`, never in the committed config.

**Q9. How do you detect that your monitoring pipeline itself is down?**
*Model answer:* A **dead-man's switch / watchdog**: an alert that is *always firing on purpose* (`expr: vector(1)`), routed to an external system (Dead Man's Snitch, healthchecks.io, or PagerDuty's "alert on absence") that pages when it *stops* hearing the heartbeat. Silence from the watchdog = monitoring is broken. Also meta-monitor `prometheus_rule_evaluation_failures_total` and `alertmanager_notifications_failed_total`.
- *Probe: Why can't you just monitor Prometheus from itself?* If Prometheus is down it can't evaluate its own alert; you need an *external* observer.
- *Probe: What masks an outage if Prometheus dies?* Alertmanager auto-resolves firing alerts after `endsAt` — making a dead Prometheus look "all clear"; the external watchdog is the guard.

**Q10. Explain dedup, grouping, inhibition, and silencing — and the order Alertmanager applies them.**
*Model answer:* Dedup = same fingerprint collapsed to one (automatic). Grouping = related-but-distinct alerts batched into one notification via `group_by`. Inhibition = a higher-severity alert auto-suppresses implied lower ones (causal). Silencing = human, time-bounded mute. Pipeline order in Alertmanager: **inhibition → silencing → routing → grouping → dedup/notify.**
- *Probe: Is inhibition transitive?* No — A→B and B→C does not give A→C; define explicit rules. The `equal` labels must match on both sides.
- *Probe: Risk of silencing?* Forgotten/broad silences mute real outages — always set expiry, restrict broad matchers, audit.

**Q11. How would you alert on latency for a 99.9%-under-300ms SLO without using `histogram_quantile` thresholds, and why avoid the quantile?**
*Model answer:* Define the SLI as the *fraction of requests in the ≤300ms bucket* (`sum(rate(..._bucket{le="0.3"}[w])) / sum(rate(..._count[w]))`) and run multi-window burn-rate on `1 − that_ratio`. Avoid `histogram_quantile(0.99) > 0.3` because quantile estimation interpolates within buckets (error sensitive to bucket boundaries), has no notion of error budget, and is noisy. Bucket-ratio maps cleanly to an SLI and a budget; use `histogram_quantile` for *dashboards*.
- *Probe: What if 300ms isn't a bucket boundary?* You must define a `le` bucket at exactly your threshold (configure histogram buckets to include 0.3) or your SLI is approximate.
- *Probe: Cardinality concern?* Histograms add a series per bucket per label set — keep label cardinality bounded.

**Q12. What goes in a good runbook and why does every page need one?**
*Model answer:* Meaning (one line + which SLO), verify-it's-real (dashboard/query, known-false-positive check), impact assessment, concrete mitigations (rollback/scale/failover/flag — mitigate before root-cause), escalation path, and links (dashboards/logs/recent deploys). Every page needs one because the worst time to reconstruct tribal knowledge is at 3 a.m. under pressure; the runbook turns a panicked archaeology dig into a checklist and shortens MTTR.
- *Probe: Mitigate or root-cause first?* Mitigate first — restore users, then investigate.
- *Probe: How do you keep runbooks fresh?* Update them in the postmortem of every incident that used (or lacked) them; link from the alert annotation so they're discoverable.

---

## 11. Glossary

- **Actionable (alert)** — there is something a human can do in response right now.
- **Alert** — a fired rule instance indicating something is wrong; identified by its label set (fingerprint).
- **Alertmanager** — Prometheus's companion service that groups, deduplicates, inhibits, silences, routes, and notifies.
- **Alert fatigue** — desensitization caused by too many low-value alerts.
- **amtool** — CLI for inspecting/validating Alertmanager (config, routes, silences, alerts).
- **Annotation** — human-readable, non-identity context attached to an alert (summary, runbook_url).
- **Availability** — fraction of (valid) requests served successfully; "number of nines."
- **Black-box monitoring** — testing the system from the outside as a user would (synthetic probes).
- **Burn rate** — `observed_error_rate / error_budget_fraction`; how fast budget is consumed.
- **Cardinality** — number of distinct time series (label-value combinations); high cardinality is costly/dangerous.
- **Cause** — an internal mechanism that *might* explain a symptom (CPU, disk, restart).
- **CNCF** — Cloud Native Computing Foundation; hosts Prometheus.
- **Compliance window** — the period over which an SLO is measured (e.g., 30 days), rolling or calendar.
- **Counter** — a metric that only increases; use `rate()`/`increase()` to read it.
- **Dead-man's switch / Watchdog** — an always-firing alert whose *absence* indicates the monitoring pipeline is broken.
- **Deduplication** — collapsing repeated copies of the same alert into one.
- **Error budget** — `1 − SLO`; the amount of failure allowed in the window.
- **Escalation policy** — rules (in PagerDuty/Opsgenie) for who to notify next if a page isn't acknowledged.
- **`evaluation_interval`** — how often Prometheus evaluates rules (default 15s).
- **Fingerprint** — hash of an alert's label set; its unique identity.
- **Firing** — an alert whose expression has been true for at least its `for:` duration.
- **Flapping** — an alert rapidly toggling between firing and resolved.
- **`for:`** — duration an alert's expr must stay true before firing (PENDING → FIRING).
- **Gossip protocol** — peer-to-peer state-sharing used by an Alertmanager cluster to dedupe notifications.
- **Grafana** — visualization tool; Grafana Alerting evaluates alerts across many datasources.
- **Grouping** — batching related-but-distinct alerts into one notification (`group_by`).
- **`group_wait` / `group_interval` / `repeat_interval`** — Alertmanager timers for first send / updates / re-nag.
- **Histogram** — a metric type bucketing observations by upper bound (`le`); enables percentile estimation.
- **`histogram_quantile`** — PromQL function estimating a percentile from histogram buckets.
- **Inhibition** — auto-suppressing lower-severity alerts implied by a firing higher-severity one.
- **`keep_firing_for:`** — keeps an alert firing briefly after its expr goes false (anti-flap; Prometheus ≥2.42).
- **`le`** — histogram bucket "less-than-or-equal" upper-bound label.
- **Micrometer** — JVM metrics facade (used by Spring Boot) exposing a Prometheus endpoint.
- **MTTR** — Mean Time To Repair/Resolve; alerting reduces detection latency, a component of MTTR.
- **Novel (alert)** — represents new information, not a duplicate of an active alert.
- **Observability (o11y)** — ability to infer internal state from external telemetry without new code.
- **On-call** — the rotation responsible for responding to pages during a shift.
- **Opsgenie** — Atlassian's on-call/incident-management platform.
- **Page** — an urgent, actionable notification that interrupts a human immediately.
- **PagerDuty** — a leading on-call/incident-management/escalation platform.
- **PENDING** — alert state while waiting out the `for:` duration.
- **`predict_linear`** — PromQL function extrapolating a series linearly (e.g., disk-fill prediction).
- **Prometheus** — open-source pull-based metrics system and TSDB that evaluates alert rules.
- **PromQL** — Prometheus Query Language.
- **promtool** — CLI to validate and unit-test Prometheus rules.
- **`rate()`** — PromQL per-second average rate of a counter over a range, handling resets.
- **Recording rule** — precomputed PromQL result stored as a new series (cheaper/consistent alerts).
- **Runbook / Playbook** — the response guide linked from a page.
- **Severity (label)** — routing label (e.g., page/ticket/none) distinguishing urgency tiers.
- **Silencing** — human-created, time-bounded muting of matching alerts.
- **SLA** — Service Level Agreement; a customer contract with consequences.
- **SLI** — Service Level Indicator; `good / total` measurement.
- **SLO** — Service Level Objective; target value of an SLI over a window.
- **SRE** — Site Reliability Engineering.
- **Symptom** — user-visible effect (errors, slowness) you should page on.
- **Ticket** — actionable-but-not-urgent work item (no immediate human interruption).
- **Time series** — a named, labeled sequence of timestamped values.
- **`up`** — synthetic metric: 1 if a scrape target is reachable, 0 if down.
- **White-box monitoring** — using internal instrumentation/metrics for alerting and diagnosis.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Golden rules**
- Page on **symptoms** (user-visible), not **causes**. Causes → dashboards/runbooks.
- Every page must be **Actionable + Urgent + Novel** (and diagnosable). If not all three → ticket or dashboard or delete.
- Every page links a **runbook**. Mitigate before root-cause.
- **Page budget ≈ ≤2 actionable incidents / 12h shift.** Excess = bug to fix, not staff around.

**Tiers:** Page (urgent+actionable now) · Ticket (actionable, not urgent) · Dashboard (informational).

**Burn-rate (SRE Workbook, 30d window):**
| Sev | Long/Short | Burn | Budget burned before firing |
|---|---|---|---|
| Page fast | 1h / 5m | 14.4× | 2% |
| Page slow | 6h / 30m | 6× | 5% |
| Ticket | 3d / 6h | 1× | 10% |
- `burn_rate = observed_error_rate / (1 − SLO)`; `budget_consumed = b × (L / window)`.
- Short window = "still burning now" AND-condition → fast reset.

**Nines:** 99.9% = 43.2 min/30d; 99.99% = 4.32 min/30d.

**Alertmanager pipeline order:** inhibition → silencing → routing → grouping → dedup/notify.
**Key timers/defaults:** `group_wait` 30s · `group_interval` 5m · `repeat_interval` 4h · `for:` 0s · `evaluation_interval` 15s.

**Dedup** = same alert ×many → 1. **Grouping** = related → 1 notification. **Inhibition** = higher-sev suppresses implied (causal, non-transitive). **Silence** = human, time-bounded (always set expiry).

**Must-haves:** `absent()`/`up==0` for dead services (0/0 NaN trap) · **dead-man's-switch watchdog** (`vector(1)`) routed externally · meta-monitor `alertmanager_notifications_failed_total`, `prometheus_rule_evaluation_failures_total`.

**SLO latency alerting:** use **bucket-ratio** SLI (`..._bucket{le="0.3"} / ..._count`), not `histogram_quantile` thresholds.

**Top anti-patterns:** cause-based pages · non-actionable pages · static thresholds for SLO availability · no `for:` · no runbook · no watchdog · forgotten broad silences · unbounded cardinality · tickets routed as pages.

**Tooling:** `promtool check rules` / `promtool test rules` (CI) · `amtool config routes test <labels>` · `amtool silence add/query` · diagnose noise with `topk(sum by(alertname)(ALERTS{alertstate="firing"}))`.

### 12.2 Self-test (no answers — recall actively)

1. A teammate proposes paging when JVM heap usage exceeds 85%. Argue for or against, and propose what (if anything) should page instead.
2. Derive the fraction of a 30-day error budget consumed before a `(burn=6, long_window=6h)` alert fires, and explain what the paired 30-minute short window adds.
3. Your availability ratio alert (`errors/total > 0.01`) did not fire during a total outage. Give two distinct reasons and the fix for each.
4. Design the Alertmanager routing tree fragment that sends `severity=page` to PagerDuty with a 10s group_wait, `severity=ticket` to Jira with a 24h repeat_interval, and mutes a `team=batch` page during a Sunday 02:00–04:00 UTC maintenance window.
5. Explain the difference between deduplication, grouping, and inhibition, give one concrete example of each, and state the order Alertmanager applies them.
6. You're on-call and getting 12 pages/shift. List, in priority order, the first five concrete actions you'd take to reduce it, and the single metric you'd track to know it worked.
7. Why is alerting on `histogram_quantile(0.99, ...) > 0.3` worse than a bucket-ratio SLI for a latency SLO? Give two specific technical reasons.
8. What is a dead-man's switch, why can't Prometheus implement it for itself, and what masks an outage if Prometheus crashes?

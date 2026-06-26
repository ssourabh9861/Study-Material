# DORA Metrics

> An exhaustive engineering-handbook chapter on the DORA metrics — the four (now five) measures of software delivery performance produced by the DevOps Research and Assessment program. Written for a senior JVM/backend engineer who wants to master the topic from first principles to deep internals: definition, measurement, instrumentation, statistics, pitfalls, and the organizational dynamics around them.

---

## 1. Overview & where it fits

**What DORA is.** *DORA* stands for **DevOps Research and Assessment**. It began as an independent research program (led by **Dr. Nicole Forsgren**, **Jez Humble**, and **Gene Kim**) that ran a multi-year, large-N survey of software organizations — the *State of DevOps* reports (2014–present). The program was acquired by **Google Cloud** in 2018 and now publishes the annual *Accelerate State of DevOps Report*. Its central empirical claim is that **software delivery performance is measurable**, that it **predicts organizational performance** (profitability, market share, productivity), and that the strongest, most reproducible measures of delivery performance reduce to a small set of metrics — the **four key metrics**, commonly called the **DORA metrics**.

**The problem it solves.** Engineering organizations have historically measured the wrong things: lines of code, story points, individual velocity, hours worked, server uptime in isolation. These are *output* or *activity* proxies, not *outcomes*, and they are trivially gamed and weakly correlated with business value. DORA's contribution is a small, balanced set of **system-level outcome metrics** that together capture both **throughput** (how fast value reaches users) and **stability** (how safely it gets there). They are measured at the level of the *delivery system* (a team's application/service), not the individual, which is the key to their integrity.

**The four (canonical) metrics:**

| # | Metric | Pillar | One-line meaning |
|---|--------|--------|------------------|
| 1 | **Deployment Frequency (DF)** | Throughput | How often you successfully release to production. |
| 2 | **Lead Time for Changes (LT)** | Throughput | Time from code committed to code running in production. |
| 3 | **Change Failure Rate (CFR)** | Stability | % of deployments that cause a production failure requiring remediation. |
| 4 | **Failed-Deployment Recovery Time** (historically **MTTR**) | Stability | How long to restore service after a failed deployment / degradation. |

In 2021 the program added a **fifth metric, Reliability** (operational performance against SLOs), to round out the picture, because the four delivery metrics say nothing about whether the service actually *works well* for users once shipped.

**When you reach for it.** Use DORA when you want a **defensible, comparable, longitudinal** read on how well your delivery system is performing and whether your investments (CI/CD, test automation, trunk-based development, observability) are paying off. It is the de facto language for engineering-leadership conversations about delivery health, and it is the backbone of platform-engineering OKRs.

**The one-paragraph mental model.** Picture your delivery pipeline as a factory line for *changes*. Two throughput questions matter — *how often does a finished change leave the line?* (Deployment Frequency) and *how long does one change take to travel from "committed" to "in customers' hands?"* (Lead Time). Two stability questions matter — *of the changes that ship, what fraction breaks something?* (Change Failure Rate) and *when one breaks, how fast do we recover?* (Recovery Time). The radical, counter-intuitive finding is that **these two dimensions are not a trade-off** — the fastest organizations are *also* the most stable. Speed and safety come from the *same* underlying capabilities (small batches, automation, fast feedback), so improving one tends to improve the other. DORA gives you the four-plus-one numbers to see where you are and to detect whether a change to your process actually moved the needle.

---

## 2. Foundations from first principles

We build the vocabulary from zero. If you already know CI/CD, skim — but every adjacent term is defined here because the metrics' definitions hinge on them.

### 2.1 The objects being measured

- **A change.** A unit of work that modifies the software — concretely, a **commit** or a set of commits merged into the main branch. In Git, a *commit* is an immutable snapshot of the codebase with a parent pointer; the *main branch* (often `main` or `master`) is the line of development that represents "the truth."
- **A deployment.** The act of putting a new version of the software into an environment. A *production deployment* is putting it where real users hit it. DORA cares specifically about **deployments to production** (or to users, e.g., an app-store release, a firmware push).
- **A release.** Sometimes used interchangeably with deployment, but technically *release* means *making functionality available to users*, which can be decoupled from *deploy* (the binary is on the servers) via **feature flags** — runtime toggles that turn code paths on/off without redeploying. DORA's Deployment Frequency counts *deployments to production*, not feature-flag flips.
- **A failure.** A deployment-induced degradation of service requiring remediation — a rollback, hotfix, patch, or forward-fix. Crucially, DORA defines failure *relative to a deployment*, not as any incident whatsoever (more in §3.4).

### 2.2 CI/CD — the machinery the metrics observe

- **Continuous Integration (CI).** The practice of merging every developer's changes into the shared mainline frequently (ideally many times a day) and verifying each merge with an automated build + test. The goal is to keep the mainline always releasable and to surface integration conflicts within minutes, not weeks. The opposite — long-lived feature branches that merge rarely — is *late integration* and is a primary driver of slow lead time.
- **Continuous Delivery (CD).** Extending CI so that the mainline is *always in a deployable state* and deploying to production is a **push-button** (or fully automatic) operation. The build artifact flows through a **deployment pipeline** — an automated sequence of stages (compile → unit test → integration test → package → deploy to staging → deploy to prod) that gates promotion.
- **Continuous Deployment.** A stricter variant: every change that passes the pipeline goes to production *automatically*, with no human gate. (Confusingly, "CD" can mean either; context disambiguates.)
- **Deployment pipeline.** The automated path from commit to production. In tools like Jenkins, GitLab CI, GitHub Actions, Argo CD, or Spinnaker, this is expressed as stages/jobs. DORA metrics are largely derived from events emitted by this pipeline.
- **Trunk-based development (TBD).** A branching model where developers integrate to a single shared branch (the trunk) at least daily, using short-lived branches (hours to a day). DORA research repeatedly finds TBD correlates with elite delivery performance because it keeps batch sizes small.
- **Artifact.** The deployable output of a build — e.g., a JAR/WAR for the JVM, a container image, a binary. Stored in an **artifact repository** (Nexus, Artifactory, a container registry).

### 2.3 Production, incidents, and recovery

- **Production (prod).** The live environment serving real users. Contrasted with *staging/pre-prod* (a prod-like rehearsal environment) and *dev/test*.
- **Incident.** An unplanned interruption or degradation of a service. Tracked in an **incident management** tool (PagerDuty, Opsgenie, FireHydrant, incident.io) or a ticketing system (Jira, ServiceNow).
- **Rollback.** Reverting to the previously known-good version. **Roll-forward / fix-forward.** Shipping a *new* version that fixes the problem rather than reverting. Both count as "remediation" for CFR/recovery purposes.
- **SLI / SLO / SLA.** A **Service Level Indicator** is a measured quantity (e.g., request success rate, p99 latency). A **Service Level Objective** is a target for an SLI (e.g., 99.9% success over 30 days). A **Service Level Agreement** is a contractual SLO with consequences. The fifth DORA metric, *Reliability*, is essentially "how well you meet your SLOs." An **error budget** is the allowed amount of failure under an SLO (for 99.9%, that's 0.1% of requests / ~43 minutes per 30 days).

### 2.4 Statistics you must know to use DORA honestly

DORA metrics are **distributions, not single numbers**, and using the wrong summary statistic is the single most common measurement error.

- **Mean (average).** Sum / count. Highly sensitive to outliers — one 30-day-stuck PR can quadruple your "average" lead time. Generally a *bad* summary for skewed delivery data.
- **Median (p50).** The middle value; half are below, half above. Robust to outliers. This is the **preferred** central measure for lead time and recovery time.
- **Percentiles (p75, p90, p95, p99).** The value below which that fraction of observations fall. p90 lead time = "90% of changes ship within X." Percentiles expose the *tail*, which is where customer pain and on-call pain live.
- **Skew.** Delivery metrics are almost always **right-skewed** (a long tail of slow outliers), which is exactly why mean ≫ median and why you must report medians/percentiles.
- **Rate vs. count.** DF is a *rate* (per day/week). CFR is a *ratio* (failures ÷ deployments). LT and recovery are *durations*. Mixing these up (e.g., averaging a ratio of ratios) produces nonsense.

---

## 3. How it works internally — precise definitions and the measurement workflow

This is the heart of the chapter. Each metric has (a) a precise definition, (b) the event timeline it is computed from, (c) the data sources, and (d) the gotchas.

### 3.0 The universal event model

Every DORA metric is derived from a small set of **events** with timestamps. If you instrument these, you can compute all five metrics:

| Event | Emitted when | Carries |
|-------|-------------|---------|
| `commit` | A change is committed/merged to mainline | commit SHA, author time, merge time |
| `deploy_started` | A production deploy begins | deployment ID, env, version, commit SHA(s) |
| `deploy_finished` | A production deploy completes | deployment ID, status (success/fail), end time |
| `incident_opened` | A production failure is detected/declared | incident ID, severity, linked deployment (if known) |
| `incident_resolved` | Service restored | incident ID, resolution time |

The whole DORA pipeline is: **collect these events → join them → bucket by time window → compute distributions → classify into performance clusters → trend over time.**

### 3.1 Deployment Frequency (DF)

**Definition.** How often the organization successfully deploys code to production (or releases to end users). It is a measure of **batch size and cadence** — frequent deploys imply small batches.

**How it is computed.** Count of *successful* production deployments per unit time. DORA's survey buckets it categorically (because exact rates are hard to self-report):

| Cluster | Deployment Frequency (per DORA surveys) |
|---------|------------------------------------------|
| Elite | On-demand (multiple deploys per day) |
| High | Between once per day and once per week |
| Medium | Between once per week and once per month |
| Low | Between once per month and once every six months |

**Event timeline.** Count `deploy_finished` events where `status = success` and `env = production`, grouped by day/week. Because DF is bursty and long-tailed at the org level, DORA often reports it as the **median time between deployments** rather than a raw count — the median gap is robust where a raw weekly count is distorted by deploy-heavy days.

> **Why median time-between-deploys?** If a team deploys 40 times on Monday and never again all week, "40/week" overstates cadence. The *median inter-deploy interval* answers "typically, how long between deploys?" and resists those bursts.

**Data sources.** Your CD tool's deployment records (Argo CD `Application` sync events, Spinnaker pipeline executions, GitHub Actions `deployment` events, GitLab `deployments` API), or a deploy webhook you fire from the pipeline.

**Gotchas.** (1) Count *production* deploys only — not staging. (2) Count *successful* deploys; a failed/rolled-back deploy is not a delivery of value (but see CFR, which *does* count it as a failure). (3) Decide per-service vs. per-org and stay consistent. (4) Feature-flag releases without a deploy do not increment DF — DF measures the *deploy* mechanism's throughput.

### 3.2 Lead Time for Changes (LT)

**Definition.** The time it takes a change to go **from code committed to code successfully running in production**. Note the precise endpoints: it is *not* the full "idea to production" cycle time (that's a broader product metric); DORA's LT starts at **commit** and ends at **deploy to prod**.

> **Why start at commit, not at "ticket created"?** DORA deliberately measures the *engineering delivery* portion — the part the delivery system controls. The upstream "idea → commit" portion (discovery, design, prioritization) is real and important but is a *product/flow* metric (often called *cycle time* or *lead time* in the Lean sense). Conflating them blurs what you can fix with CI/CD investment.

**How it is computed.** For each change, `LT = deploy_to_prod_time − commit_time`. Report the **median** (and ideally p75/p90). DORA buckets:

| Cluster | Lead Time for Changes |
|---------|------------------------|
| Elite | Less than one day |
| High | Between one day and one week |
| Medium | Between one week and one month |
| Low | Between one month and six months |

**Event timeline (per change).**
```
commit ──(CI build+test)──> merge ──(queue)──> deploy_started ──> deploy_finished(prod, success)
  ^                                                                              ^
  └────────────────────────── Lead Time for Changes ────────────────────────────┘
```
The interval decomposes into useful sub-stages: *commit→merge* (review + CI), *merge→deploy_started* (release queue / batching delay), *deploy duration*. Decomposing reveals **where** time goes (long code review? slow CI? infrequent deploy windows?).

**Which commit timestamp?** Use the commit's **author/commit time** of the *earliest* commit included in the deployment, joined to the deploy that first carried it to prod. Practically, tools attribute LT per-commit by tracing the SHA into the deployed version (via the artifact's embedded git SHA or the deploy record's `commit_sha`).

**Data sources.** Git history (commit timestamps) joined to deployment records (which SHA went to prod, and when). The join key is the **commit SHA** baked into the build (e.g., a `BuildInfo` resource, a `git.properties` from Spring Boot's `git-commit-id` plugin, or a container image label `org.opencontainers.image.revision`).

**Gotchas.** (1) Commit timestamps can be rewritten (rebase, squash) — prefer the *merge* commit time on the mainline if author times are unreliable. (2) Reverts and cherry-picks complicate attribution; pick a documented rule and stick to it. (3) Mean is misleading — use median/percentiles. (4) Don't reset the clock on amended commits.

### 3.3 Failed-Deployment Recovery Time (formerly MTTR)

**Definition.** How long it takes to **restore service** after a **deployment-caused** failure in production. DORA renamed this from "Time to Restore Service / MTTR" to **Failed-Deployment Recovery Time** to scope it precisely to *deployment-induced* failures (not all incidents — a cloud-provider outage isn't your deploy's fault).

> **MTTR caveat.** "MTTR" is overloaded across the industry — Mean Time To *Recover*, *Restore*, *Resolve*, *Repair*, *Respond*. DORA's metric is specifically *recovery from a failed deployment*. Don't import a generic "MTTR" from your incident tool without checking it matches this scope. Also note: DORA prefers the **median**, despite the legacy "M = Mean" in the acronym.

**How it is computed.** For each deployment-caused failure: `recovery = restored_time − failure_start_time`. Report the **median**. DORA buckets:

| Cluster | Failed-Deployment Recovery Time |
|---------|----------------------------------|
| Elite | Less than one hour |
| High | Less than one day |
| Medium | Less than one day (historically "between one day and one week" in some years) |
| Low | Between one week and one month |

*(The exact medium/low boundaries have shifted slightly year to year; flag this as report-version-specific.)*

**Event timeline.**
```
deploy_finished(prod) ──> [degradation begins] ──> incident_opened ──> remediation ──> incident_resolved(restored)
                          ^                                                              ^
                          └────────────── Failed-Deployment Recovery Time ──────────────┘
```
Ideally measure from *when the failure began* (degradation onset, from your monitoring), not from when a human noticed. In practice many teams measure from `incident_opened` to `incident_resolved` because onset is hard to pin down — document which you use.

**Data sources.** Incident management tool (PagerDuty/Opsgenie/incident.io) for open/resolve timestamps, joined to the deployment suspected of causing it.

**Gotchas.** (1) "Restore" means *service back to acceptable* — which can be a rollback (fast) even if the root-cause fix lands later. (2) Only count *deployment-caused* incidents for this metric. (3) Median, not mean.

### 3.4 Change Failure Rate (CFR)

**Definition.** The **percentage of deployments to production that result in a degraded service requiring remediation** (rollback, hotfix, patch, fix-forward). It is *failures ÷ total deployments*, expressed as a percentage.

**How it is computed.** `CFR = (# deployments that caused a failure) / (# total deployments) × 100`, over a window. DORA buckets:

| Cluster | Change Failure Rate |
|---------|----------------------|
| Elite | 0–15% (recent reports), historically cited as 0–15% |
| High | 16–30% |
| Medium | 16–30% (overlaps in some years) |
| Low | 16–30% / higher |

*(The cluster boundaries for CFR have been the noisiest across report years — in several years elite/high/medium all fell in similar ranges. Treat the precise cut-points as report-version-specific; the directional truth is "elite is well under 15%, low is much higher.")*

> **Critical subtlety — numerator vs. denominator scope.** CFR is *per deployment*. If you deploy rarely and bundle many changes, a single failing deploy is a *high* CFR for that window. If you deploy continuously, a failure is diluted across many deploys. This is why CFR must be read **together with** Deployment Frequency, never alone.

**Event timeline.** Join `incident_opened` events to the `deploy_finished` that caused them; the fraction of prod deploys with a linked failure within a reasonable causal window (e.g., the incident began before the next deploy) is the CFR.

**Data sources.** Deployment records (denominator) joined to incidents tagged as deployment-caused (numerator). The join is the hardest part of the whole DORA pipeline (see §6 and §9).

**Gotchas.** (1) Define "failure requiring remediation" crisply — a cosmetic bug nobody noticed vs. a customer-facing outage are not the same; pick a severity threshold and document it. (2) Attribution: which deploy caused the incident? (3) Don't double-count one incident across multiple deploys.

### 3.5 The fifth metric — Reliability (operational performance)

**Definition.** Added in 2021, **Reliability** captures **how well the service meets user expectations once deployed** — availability, latency, performance, and how reliably you meet your **SLOs**. Unlike the other four, DORA does not prescribe a single numeric formula or universal cluster cut-points; it asks teams to define their own SLOs and measure attainment. Reliability is the *outcome* dimension that the four delivery metrics enable but do not themselves guarantee — you can deploy fast and stably and still ship a service that's slow or flaky for users.

**How it is measured.** Via SLIs/SLOs and **error budgets**: e.g., availability SLI = good requests / total requests; SLO = 99.9%; error budget = 0.1%. Reliability "performance" = how consistently you stay within budget. Tools: Prometheus + a recording-rule/SLO library, Google Cloud SLO monitoring, Nobl9, Sloth, OpenSLO.

### 3.6 The performance clusters (elite / high / medium / low)

DORA clusters organizations into four performance tiers using a statistical technique called **cluster analysis** (specifically, the program has used methods like *hierarchical clustering* / *latent class–style grouping* on the survey responses). The point: the four metrics co-vary, so respondents naturally fall into coherent groups — and crucially, **the elite group is high on *both* throughput *and* stability simultaneously**, which is the headline finding.

> **What is cluster analysis?** A family of unsupervised statistical methods that group observations so that members of a group are more similar to each other than to members of other groups. DORA feeds the four metrics in and the data falls into ~4 natural tiers, rather than the tiers being arbitrarily defined by hand.

Approximate magnitude of the gap between elite and low (from the reports, directionally): elite teams deploy **hundreds to thousands of times** more frequently, have lead times **orders of magnitude shorter** (hours vs. months), recover **orders of magnitude faster**, and have **substantially lower** change failure rates. The cluster *names*, *count*, and *boundaries* have evolved year to year (some years dropped or merged a tier), so always cite the specific report year.

---

## 4. The complete toolkit

This section enumerates the concrete tools, APIs, configs, and data sources you use to instrument and compute DORA metrics.

### 4.1 Event/data sources by metric

| Metric | Primary data source | Specific signals |
|--------|--------------------|------------------|
| Deployment Frequency | CD tool / deploy webhook | Deployment records with `status`, `env`, `timestamp` |
| Lead Time | VCS + CD tool | Commit/merge timestamps joined by SHA to deploy time |
| Change Failure Rate | CD tool + incident tool | Deploy count (denominator) + deploy-caused incidents (numerator) |
| Recovery Time | Incident tool (+ monitoring) | Incident open/resolve timestamps (or degradation onset) |
| Reliability | Monitoring / SLO tool | SLIs, SLO attainment, error-budget burn |

### 4.2 Off-the-shelf DORA platforms

| Tool | Type | Notes |
|------|------|-------|
| **Four Keys** (Google) | Open-source reference impl | GCP-based (Cloud Build, BigQuery, Looker/Data Studio). Ingests GitHub/GitLab/Cloud Build events, computes all four. Good blueprint even if you don't run it. |
| **DevLake (Apache)** | Open-source data platform | Connectors for GitHub, GitLab, Jira, Jenkins, etc.; built-in DORA dashboards (Grafana). Self-hostable. |
| **LinearB, Sleuth, Haystack, Faros, Swarmia, Code Climate Velocity** | Commercial | Turnkey DORA + flow metrics; integrate VCS/CI/incident tools. Vendor-specific definitions — verify they match DORA's. |
| **GitLab** | Built-in | Native DORA metrics (DF, LT, CFR, recovery) in Premium/Ultimate via its CI/CD + incident data. |
| **Atlassian Compass / Jira** | Built-in-ish | DORA dashboards from Bitbucket/Jira/Opsgenie. |
| **Cloud-vendor** | Built-in | Some platforms expose DORA-style dashboards (e.g., GitHub via partner apps). |

### 4.3 CD / pipeline tools that emit the events

| Tool | Deployment-event signal |
|------|-------------------------|
| **Argo CD** | `Application` sync status, sync history (Kubernetes GitOps) |
| **Spinnaker** | Pipeline execution records, stages |
| **GitHub Actions** | `deployment` and `deployment_status` API/webhook events |
| **GitLab CI** | `deployments` API, environment events |
| **Jenkins** | Build/deploy job results (often need custom webhooks) |
| **Flux** | GitOps reconciliation events |

### 4.4 Incident / monitoring tools (numerator + recovery)

| Tool | Role |
|------|------|
| **PagerDuty / Opsgenie / incident.io / FireHydrant** | Incident open/resolve times, severity, deploy linkage |
| **Prometheus / Grafana** | SLIs, alerting, recording rules for Reliability |
| **Datadog / New Relic / Dynatrace** | APM + deploy markers + incident timing |
| **Sloth / OpenSLO / Nobl9** | SLO definition & error-budget tracking (Reliability) |

### 4.5 Key instrumentation hooks (JVM-relevant)

| Tool/flag | Purpose | Default/notes |
|-----------|---------|---------------|
| Spring Boot `git-commit-id-maven-plugin` (`io.github.git-commit-id`) | Embeds git SHA/build time into the artifact (`git.properties`, `/actuator/info`) | Lets you trace deployed version → commit for LT |
| Gradle `com.gorylenko.gradle-git-properties` | Same, for Gradle builds | Produces `git.properties` on classpath |
| OCI image label `org.opencontainers.image.revision` | Standard label carrying the source commit SHA | Read at deploy time to join SHA → deploy |
| Micrometer + `@Timed` / custom counters | Emit deploy/incident events as metrics | Feed Prometheus → DORA dashboards |
| OpenTelemetry CI/CD semantic conventions | Standardized span/event names for pipeline stages | Emerging standard for emitting deploy events |

### 4.6 Statistics toolkit (how to summarize)

| Quantity | Use | Don't use |
|----------|-----|-----------|
| Lead Time | **median + p75/p90** | mean |
| Recovery Time | **median** | mean (despite "MTTR" naming) |
| Deployment Frequency | count per window or **median inter-deploy interval** | mean of bursty counts |
| Change Failure Rate | ratio over a window (with confidence intervals if N is small) | comparing single-window CFR with tiny N |

---

## 5. Code examples by use case

These are concrete, adaptable examples spanning different real scenarios. Java/JVM where language-relevant; SQL/YAML/shell otherwise.

### 5.1 Emit a deployment event from a CI/CD pipeline (GitHub Actions → webhook)

```yaml
# .github/workflows/deploy.yml
# Fires a structured deployment event to your DORA collector after a successful prod deploy.
name: deploy-prod
on:
  push:
    branches: [ main ]   # trunk-based: every merge to main is a candidate deploy
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build & deploy
        run: ./gradlew build && ./deploy.sh prod
      - name: Emit DORA deployment event
        if: success()                         # only count SUCCESSFUL prod deploys for DF
        run: |
          curl -sf -X POST "$DORA_COLLECTOR_URL/events/deploy" \
            -H "Authorization: Bearer ${{ secrets.DORA_TOKEN }}" \
            -H "Content-Type: application/json" \
            -d '{
              "service": "payments-api",
              "env": "production",
              "status": "success",
              "version": "${{ github.sha }}",   # commit SHA = join key for Lead Time
              "deployed_at": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"
            }'
```
*Why it matters:* the `commit SHA` in `version` is the join key that ties this deploy to its commits for Lead Time. Emitting only on `success` keeps Deployment Frequency honest.

### 5.2 Embed the git SHA in a Spring Boot artifact (so deploys are traceable)

```xml
<!-- pom.xml — bakes commit metadata into the JAR; exposed at /actuator/info -->
<plugin>
  <groupId>io.github.git-commit-id</groupId>
  <artifactId>git-commit-id-maven-plugin</artifactId>
  <version>9.0.1</version>
  <executions>
    <execution><id>get-the-git-infos</id><goals><goal>revision</goal></goals></execution>
  </executions>
  <configuration>
    <generateGitPropertiesFile>true</generateGitPropertiesFile>
    <includeOnlyProperties>
      <p>git.commit.id.full</p>     <!-- full SHA -->
      <p>git.commit.time</p>        <!-- commit timestamp -> Lead Time start -->
      <p>git.branch</p>
    </includeOnlyProperties>
  </configuration>
</plugin>
```
At runtime, `/actuator/info` (with Spring Boot Actuator on the classpath) returns the SHA, letting your collector confirm exactly which commit is live in prod.

### 5.3 Compute Lead Time for Changes from raw events (SQL, BigQuery-style)

```sql
-- One row per (commit, first prod deploy that carried it). Lead time = deploy - commit.
WITH commit_to_deploy AS (
  SELECT
    c.commit_sha,
    c.committed_at,
    MIN(d.deployed_at) AS first_prod_deploy_at   -- first deploy that shipped this SHA
  FROM commits c
  JOIN deploy_commits dc ON dc.commit_sha = c.commit_sha   -- mapping of SHAs per deploy
  JOIN deployments d
    ON d.deployment_id = dc.deployment_id
   AND d.env = 'production' AND d.status = 'success'
  GROUP BY c.commit_sha, c.committed_at
)
SELECT
  DATE_TRUNC(first_prod_deploy_at, WEEK) AS wk,
  -- MEDIAN, not AVG: lead-time distributions are right-skewed.
  APPROX_QUANTILES(TIMESTAMP_DIFF(first_prod_deploy_at, committed_at, MINUTE), 100)[OFFSET(50)] AS median_lt_min,
  APPROX_QUANTILES(TIMESTAMP_DIFF(first_prod_deploy_at, committed_at, MINUTE), 100)[OFFSET(90)] AS p90_lt_min
FROM commit_to_deploy
GROUP BY wk
ORDER BY wk;
```

### 5.4 Compute Change Failure Rate, joining deploys to incidents (SQL)

```sql
-- CFR = deploys that caused an incident / total prod deploys, per week.
WITH prod_deploys AS (
  SELECT deployment_id, deployed_at
  FROM deployments
  WHERE env = 'production' AND status = 'success'
),
failed_deploys AS (
  SELECT DISTINCT d.deployment_id     -- DISTINCT: one bad deploy counts once even if it spawns many alerts
  FROM prod_deploys d
  JOIN incidents i
    ON i.caused_by_deployment_id = d.deployment_id   -- explicit linkage is best
   AND i.severity IN ('SEV1','SEV2')                 -- documented severity threshold
)
SELECT
  DATE_TRUNC(d.deployed_at, WEEK) AS wk,
  COUNT(*) AS total_deploys,
  COUNTIF(f.deployment_id IS NOT NULL) AS failed_deploys,
  SAFE_DIVIDE(COUNTIF(f.deployment_id IS NOT NULL), COUNT(*)) AS cfr
FROM prod_deploys d
LEFT JOIN failed_deploys f USING (deployment_id)
GROUP BY wk ORDER BY wk;
```
*Note:* `SAFE_DIVIDE` avoids divide-by-zero in weeks with no deploys; `DISTINCT`/severity threshold encode the documented failure definition.

### 5.5 Recovery Time from incident timestamps (Java, median computation)

```java
// Compute median failed-deployment recovery time (minutes) from deployment-caused incidents.
import java.time.Duration;
import java.util.*;

record Incident(boolean deploymentCaused, java.time.Instant opened, java.time.Instant resolved) {}

static double medianRecoveryMinutes(List<Incident> incidents) {
    List<Long> mins = incidents.stream()
        .filter(Incident::deploymentCaused)               // DORA scope: deploy-caused only
        .filter(i -> i.resolved() != null)                // exclude still-open incidents
        .map(i -> Duration.between(i.opened(), i.resolved()).toMinutes())
        .sorted()
        .toList();
    if (mins.isEmpty()) return Double.NaN;
    int n = mins.size();
    // Median, NOT mean — recovery times are heavily right-skewed.
    return (n % 2 == 1)
        ? mins.get(n / 2)
        : (mins.get(n / 2 - 1) + mins.get(n / 2)) / 2.0;
}
```

### 5.6 Deployment-Frequency as median inter-deploy interval (Java)

```java
// Robust DF: typical gap between successful prod deploys, resistant to deploy bursts.
import java.time.Duration;
import java.time.Instant;
import java.util.*;

static Optional<Duration> medianInterDeployGap(List<Instant> successfulProdDeploys) {
    if (successfulProdDeploys.size() < 2) return Optional.empty();
    List<Instant> sorted = new ArrayList<>(successfulProdDeploys);
    Collections.sort(sorted);
    List<Long> gaps = new ArrayList<>();
    for (int i = 1; i < sorted.size(); i++)
        gaps.add(Duration.between(sorted.get(i - 1), sorted.get(i)).getSeconds());
    Collections.sort(gaps);
    int n = gaps.size();
    long medianSec = (n % 2 == 1) ? gaps.get(n / 2)
                                  : (gaps.get(n / 2 - 1) + gaps.get(n / 2)) / 2;
    return Optional.of(Duration.ofSeconds(medianSec));   // e.g., PT3H = "deploys roughly every 3h"
}
```

### 5.7 Classify a service into a DORA cluster (Java, version-flagged thresholds)

```java
// Maps measured metrics to elite/high/medium/low. Thresholds are REPORT-VERSION-SPECIFIC — flag them.
enum Tier { ELITE, HIGH, MEDIUM, LOW }

static Tier leadTimeTier(Duration medianLeadTime) {
    long d = medianLeadTime.toDays();
    if (medianLeadTime.toHours() < 24) return Tier.ELITE;   // < 1 day
    if (d <= 7)  return Tier.HIGH;                          // 1 day – 1 week
    if (d <= 30) return Tier.MEDIUM;                        // 1 week – 1 month
    return Tier.LOW;                                        // > 1 month
}

static Tier recoveryTier(Duration medianRecovery) {
    if (medianRecovery.toHours() < 1) return Tier.ELITE;    // < 1 hour
    if (medianRecovery.toHours() < 24) return Tier.HIGH;    // < 1 day
    if (medianRecovery.toDays() <= 7) return Tier.MEDIUM;
    return Tier.LOW;
}
// CFR / DF tiers similar; cut-points vary by report year — keep them in config, not hardcoded.
```

### 5.8 SLO/error-budget recording rule (Prometheus → Reliability metric)

```yaml
# Reliability (5th metric): availability SLI and error-budget burn for a 99.9% SLO.
groups:
  - name: payments-slo
    rules:
      - record: job:request_success_ratio:rate5m   # SLI = good/total over 5m
        expr: |
          sum(rate(http_requests_total{job="payments",code!~"5.."}[5m]))
          / sum(rate(http_requests_total{job="payments"}[5m]))
      - record: job:error_budget_remaining          # 1 - (errors / allowed), allowed = 0.1%
        expr: |
          1 - (
            (1 - job:request_success_ratio:rate5m)
            / 0.001
          )
```

### 5.9 GitOps lead-time signal from Argo CD (shell)

```bash
# Pull Argo CD sync history to derive prod deploy timestamps (DF + LT denominator).
argocd app history payments-api -o json \
  | jq -r '.[] | select(.deployStartedAt != null)
            | {revision: .revision, deployedAt: .deployedAt}'   # revision = git SHA join key
```

---

## 6. Implementation concerns & best practices

### 6.1 Correctness of measurement (the hardest part)

- **Define each metric in writing, per organization.** Especially "what counts as a failure" (severity threshold), "what counts as production," and "per-service vs. per-team." Without a written definition, cross-team comparison is meaningless.
- **The CFR join is where projects die.** Linking incidents to the deploy that caused them is hard. Best (most reliable) to least: (1) explicit human/automation tag (`caused_by_deployment_id`); (2) deploy markers in monitoring + temporal correlation (incident started shortly after deploy X, before deploy Y); (3) pure heuristics (worst). Prefer making engineers tag the culprit deploy when they resolve an incident.
- **Use medians/percentiles, never means** for durations. Mean lead time is the most common reporting bug.
- **Small-N noise.** A team that deploys 5 times/week will have a CFR that swings wildly (0% → 40%) week to week. Use rolling windows (e.g., trailing 4–12 weeks) and consider confidence intervals; do not react to single-window blips.

### 6.2 Performance & cost of the pipeline

- Event collection is cheap (a webhook per deploy/incident). The cost is in the **data warehouse + dashboarding** (BigQuery/Snowflake + Looker/Grafana). Keep raw events; compute aggregates on read or via scheduled rollups.
- Avoid over-instrumenting: you need ~5 event types (§3.0), not a telemetry firehose.

### 6.3 Security & privacy

- DORA metrics must be **team/service-level, never individual-level**. Attaching lead time to a named developer turns a system metric into a surveillance tool, destroys trust, and invites gaming. Strip author identity from DORA aggregates.
- Deploy webhooks carry tokens — store in secret managers, scope minimally.

### 6.4 Observability of the metrics themselves

- Trend over time beats absolute snapshots: the question is "are we improving?" not "are we elite?"
- Show **distributions** (histograms), not just a single median, so tails are visible.
- Annotate dashboards with **process changes** (e.g., "introduced trunk-based dev on Mar 1") so you can attribute movement.

### 6.5 Testing the measurement pipeline

- Write unit tests on the aggregation logic with known fixtures (§5.5–5.7 are testable).
- Reconcile: periodically hand-count a week's deploys and compare to the dashboard.
- Test the SHA→deploy join with deliberately tricky cases (squash merges, reverts, cherry-picks).

### 6.6 Production hardening

- Make event emission **idempotent** (dedupe on `deployment_id`/`incident_id`) so retries don't double-count.
- Handle the pipeline failing *to emit* (a deploy that succeeded but the webhook failed) — backfill from the CD tool's API rather than trusting only push events.

### 6.7 Anti-patterns to avoid

| Anti-pattern | Why it's harmful |
|--------------|------------------|
| **Measuring individuals** | Gaming, fear, perverse incentives; DORA is a *system* metric. |
| **Optimizing one metric in isolation** | E.g., chasing Deployment Frequency by deploying empty/trivial commits inflates DF while ignoring batch size; chasing low CFR by deploying less; "fixing" recovery time by closing incidents fast without restoring. |
| **Using mean for lead/recovery time** | Outliers dominate; you optimize the wrong thing. |
| **Comparing teams as a leaderboard** | Different services have different risk profiles; comparison breeds gaming, not improvement. |
| **Treating DORA as the only metric** | It measures *delivery*, not product value, dev experience, or reliability (hence the 5th metric and complementary frameworks like SPACE). |
| **Setting DORA as a performance-review target** ("Goodhart's law") | "When a measure becomes a target, it ceases to be a good measure." Targets invite gaming. |
| **Ignoring the denominator** (CFR/DF) | Reading CFR without DF, or DF without batch size, gives a false picture. |

---

## 7. Advanced topics & deep internals

### 7.1 The stability–throughput "false dichotomy"

The traditional belief is a **trade-off**: go faster and you'll break more things; be more stable and you'll have to slow down. DORA's data **refutes** this. Throughput (DF, LT) and stability (CFR, recovery) are **positively correlated** — elite performers are simultaneously the fastest *and* the most stable. The mechanism: both come from the **same capabilities** — *small batch sizes*, *automated testing*, *continuous integration*, *fast feedback*, *loosely coupled architecture*, and *good monitoring*. Small batches make each deploy low-risk (less to go wrong, easy to diagnose, fast to roll back), which *simultaneously* enables frequent deploys (throughput) and low failure/quick recovery (stability). So you don't choose; you invest in the underlying capability and **both pillars improve together**. This is the single most important conceptual takeaway and a classic senior-signal interview point.

### 7.2 Batch size as the hidden master variable

DORA metrics are downstream of **batch size**. Small batches → short lead time (less to integrate), high deployment frequency (deploy each small batch as it's ready), low change failure rate (small surface area for bugs), and fast recovery (small, well-understood diff to revert). Conversely, large batches couple slow lead time *and* high failure rate. Many "improve DORA" programs are really "reduce batch size" programs (trunk-based dev, smaller PRs, feature flags to decouple deploy from release).

### 7.3 Why feature flags decouple deploy from release (and what that does to the metrics)

A **feature flag** is a runtime conditional that enables/disables a code path without redeploying. With flags you can **deploy continuously** (each small change goes to prod, behind a flag) while **releasing** (exposing to users) on a separate schedule. Effect on metrics: Deployment Frequency goes *up* and Lead Time goes *down* (code reaches prod fast, even if dark), while risk stays controlled (flags can be flipped off instantly — a fast recovery path). This is *the* technique elite teams use to reconcile "deploy constantly" with "release carefully."

### 7.4 Progressive delivery and its effect on CFR/recovery

**Canary deployments** (route a small % of traffic to the new version first) and **blue-green deployments** (run two prod environments and switch traffic) let you catch failures before full rollout and recover by shifting traffic back — dramatically lowering effective change failure rate and recovery time. These are deep CD techniques whose payoff shows up directly in the stability metrics.

### 7.5 Gaming dynamics (Goodhart's law in detail)

**Goodhart's law:** "When a measure becomes a target, it ceases to be a good measure." Each DORA metric has a degenerate gaming strategy:
- **DF** ↑ by deploying trivial/no-op changes.
- **LT** ↓ by committing late (squashing the long part of the work out of the measured window) or only measuring tiny changes.
- **CFR** ↓ by deploying *less* (fewer deploys, each huge — terrible) or by under-reporting incidents.
- **Recovery** ↓ by marking incidents "resolved" before service is truly restored.
The defense: (a) use all four together (gaming one usually worsens another), (b) never tie them to individual rewards/punishment, (c) treat them as *team-owned improvement signals*, and (d) annotate context.

### 7.6 Statistical depth: distributions, sampling, and significance

- Lead-time and recovery-time distributions are **log-normal-ish / power-law tailed**. Report **median + p75/p90/p95**; the spread between p50 and p90 is itself a signal (large spread = inconsistent flow).
- For small teams, weekly CFR is high-variance; use **rolling windows** and, where rigor matters, **Wilson confidence intervals** on the proportion.
- Detecting real improvement requires comparing **distributions over time** (e.g., a shift in the median with a control chart), not eyeballing two points.

### 7.7 Where DORA stops — complementary frameworks

- **SPACE framework** (Satisfaction, Performance, Activity, Communication, Efficiency): a broader *developer productivity* model that explicitly warns against single-metric thinking; DORA fits inside its "Performance/Efficiency" dimensions.
- **Flow metrics** (from Lean/Flow Framework): flow time, flow velocity, flow efficiency — measure the *full* idea→prod cycle, complementing DORA's commit→prod scope.
- **Reliability/SRE**: SLOs and error budgets (the 5th metric) come from Google's SRE practice.
- Treat DORA as the *delivery-system* lens, not the whole picture.

### 7.8 Per-service vs. per-org aggregation

DORA is best measured **per service/application/team** because clusters are most actionable there. Org-level rollups can hide a bimodal reality (one elite team, several low). When aggregating, aggregate the *underlying events*, not the *per-team tiers* (don't average tier labels). Weight by deploy volume if you must combine CFRs.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Build vs. buy the DORA pipeline

| Option | Use when | Avoid when |
|--------|----------|------------|
| **Buy commercial (LinearB, Sleuth, Faros…)** | You want fast time-to-value, standard definitions, and have budget; many teams | You need custom definitions or have data-residency constraints |
| **Open-source (DevLake, Four Keys)** | You want control, self-hosting, no per-seat cost; have platform engineers | You lack capacity to operate a data platform |
| **DIY (events → warehouse → dashboard)** | Your tooling is bespoke; you want full control of definitions | Small team, no data engineering capacity |
| **Built-in (GitLab/Atlassian)** | You're already all-in on that ecosystem | Multi-vendor toolchain that the built-in can't see |

### 8.2 Which metric to focus on first

| Symptom | Likely weakest metric | First lever |
|---------|----------------------|-------------|
| "Deploys are scary, we batch a release every month" | DF low, LT high | Trunk-based dev, smaller PRs, automate the pipeline |
| "We deploy often but break prod a lot" | CFR high | Automated testing, canary/blue-green, smaller batches |
| "Outages last hours" | Recovery time high | Fast rollback, better monitoring/alerting, runbooks, feature flags |
| "Fast and stable but users complain" | Reliability low | Define SLOs, error budgets, performance work |

### 8.3 Median vs. mean vs. percentile (summary)

| Metric | Default summary | When to add |
|--------|-----------------|-------------|
| Lead Time | median | p75/p90 to expose tail |
| Recovery | median | p90 for worst-case on-call pain |
| DF | count or median gap | distribution if bursty |
| CFR | ratio over rolling window | confidence interval if small N |

### 8.4 DORA vs. alternative engineering metrics

| Framework | Measures | Scope | Relationship to DORA |
|-----------|----------|-------|----------------------|
| **DORA (4+1)** | Delivery throughput + stability + reliability | Commit→prod + ops | The delivery-system lens |
| **SPACE** | Holistic productivity (incl. satisfaction) | Whole dev experience | Superset; DORA ⊂ SPACE |
| **Flow metrics** | Full value-stream flow | Idea→prod | Wider time window than LT |
| **Velocity/story points** | Estimated effort completed | Planning | Output proxy; *not* an outcome metric; avoid as a performance target |

**Use DORA when** you need a defensible, comparable, system-level read on delivery health and want to validate process investments. **Avoid leaning on DORA alone when** you need to understand product value, developer well-being, or full idea-to-customer flow — pair it with SPACE/flow/SLOs.

---

## 9. Failure modes & debugging

### 9.1 Measurement failure modes (the numbers lie)

| Failure mode | Symptom | Diagnosis | Fix |
|--------------|---------|-----------|-----|
| **Missing deploy events** | DF mysteriously low; LT computed off stale deploys | Reconcile dashboard count vs. CD tool's API for a week | Backfill from CD API; make emission idempotent + retried |
| **Broken SHA→deploy join** | Lead time wildly wrong or null for many commits | Spot-check that the live `/actuator/info` SHA matches the deploy record | Embed SHA in artifact (§5.2); standardize the join key |
| **CFR attribution wrong** | CFR near 0 (no incidents linked) or implausibly high | Sample incidents; check `caused_by_deployment_id` coverage | Make engineers tag the culprit deploy at resolution; add deploy markers to monitoring |
| **Mean instead of median** | Lead/recovery times look terrible after one outlier | Inspect the aggregation query | Switch to median/percentiles |
| **Small-N whiplash** | Weekly CFR swings 0%→50% | Look at N (deploys/week) | Rolling window; confidence intervals |
| **Counting staging as prod** | DF inflated | Filter on `env=production` | Tag environments correctly |
| **Resolved-but-not-restored** | Recovery time too good to be true | Compare incident "resolved" vs. monitoring recovery | Measure restore from monitoring, not ticket close |

### 9.2 Delivery failure modes the metrics reveal

- **High CFR + low DF** → big-bang releases; integrate more often, shrink batches.
- **High LT with low commit→merge but high merge→deploy** → infrequent deploy windows / manual gates; automate and deploy continuously.
- **High recovery time** → no fast rollback path, poor observability, no runbooks; add blue-green/canary and instrument.
- **Elite four metrics but poor Reliability** → you ship fast and stably but the service itself underperforms SLOs; invest in performance/capacity.

### 9.3 Organizational failure modes

- **Weaponized metrics.** If DORA appears in individual performance reviews, expect gaming and data corruption. The "incident" of choice here is a team that hits "elite" by under-reporting incidents and bundling work — the metrics look great while reality degrades. Diagnose via qualitative signals (engineer sentiment, surprise outages) diverging from the dashboard. Fix: re-establish DORA as a *team improvement* tool, decouple from comp.
- **Vanity dashboards.** Metrics displayed but never acted on. Fix: tie each metric to a specific improvement experiment and review the trend, not the snapshot.

### 9.4 Debugging toolkit

- Reconcile against source-of-truth APIs (`argocd app history`, GitHub `deployments` API, PagerDuty incidents API).
- `/actuator/info` (Spring Boot) to confirm the live SHA in prod.
- Control charts / histograms in Grafana to see distribution shifts.
- Periodic manual audit of one week's events end-to-end.

---

## 10. Interview drill

**Q1. Name the four DORA metrics and which pillar each belongs to.**
*Model answer:* Deployment Frequency and Lead Time for Changes (throughput); Change Failure Rate and Failed-Deployment Recovery Time (stability). A fifth, Reliability (SLO attainment), was added in 2021.
- *Probe: Why split into two pillars?* To capture both speed and safety; the headline finding is they correlate rather than trade off.
- *Probe: What does the 5th add?* Whether the service actually works well for users once shipped — the four delivery metrics don't guarantee that.

**Q2. Precisely define Lead Time for Changes. What are its endpoints?**
*Model answer:* Time from **code committed** to **code successfully running in production**. It deliberately excludes the upstream idea→commit portion, which is a separate flow/cycle-time metric.
- *Probe: Why commit, not ticket creation?* It scopes to the engineering delivery system you control with CI/CD.
- *Probe: How summarize it?* Median and p75/p90, never mean — the distribution is right-skewed.

**Q3. Walk me through measuring Change Failure Rate. What's the hardest part?**
*Model answer:* CFR = deploys that caused a remediation-requiring failure ÷ total prod deploys, over a rolling window. Hardest part is **attribution** — linking an incident to the deploy that caused it. Best is explicit tagging at incident resolution; fallback is deploy markers + temporal correlation in monitoring.
- *Probe: Why read CFR with DF?* A single bad deploy yields high CFR when you deploy rarely; diluted when you deploy often — denominator matters.
- *Probe: How handle small N?* Rolling windows and confidence intervals; don't react to single-week swings.

**Q4. Why is using the mean a bug for lead/recovery time?**
*Model answer:* These distributions are right-skewed; a single stuck change or long outage dominates the mean, so you'd optimize the wrong thing. Median + percentiles are robust and expose the tail.
- *Probe: What does a large p50–p90 gap tell you?* Inconsistent flow — some changes sail through, others get stuck; investigate the slow path.

**Q5 (senior-signal). The CTO says "we need to choose between moving faster and being stable." Respond.**
*Model answer:* That's the false dichotomy DORA disproves. Throughput and stability **positively correlate** because both stem from the same capabilities — small batches, automated testing, CI, fast feedback, good monitoring. Invest in those and *both* improve. The "trade-off" is an artifact of large-batch, manual, fragile processes.
- *Probe: Mechanism?* Small batches lower per-deploy risk (less to break, easy to diagnose/roll back) *and* enable frequent deploys — same root cause helps both pillars.
- *Probe: A concrete lever?* Feature flags to decouple deploy from release: deploy continuously (fast) behind flags you can flip off instantly (safe).

**Q6 (senior-signal). How would you prevent DORA metrics from being gamed?**
*Model answer:* Measure at team/service level, never individuals; use all four together (gaming one usually degrades another); never tie to comp/performance reviews (Goodhart's law); treat as improvement signals with context annotations; corroborate with qualitative signals.
- *Probe: Example of gaming one metric?* Lowering CFR by deploying less often (huge batches) — which worsens lead time and recovery, so the balanced set exposes it.
- *Probe: Goodhart's law?* "When a measure becomes a target, it ceases to be a good measure" — targets invite optimizing the metric instead of the outcome.

**Q7. What are the performance clusters and where do they come from?**
*Model answer:* Elite, High, Medium, Low — derived via cluster analysis on survey data, so they're empirical groupings, not hand-set thresholds. Elite is high on *both* throughput and stability simultaneously.
- *Probe: Stability of boundaries?* Names/counts/cut-points shift year to year (especially CFR), so cite the report year.

**Q8. How do you measure Failed-Deployment Recovery Time, and why the rename from MTTR?**
*Model answer:* Median time from a deployment-caused failure beginning to service being restored. Renamed from MTTR because (a) MTTR is overloaded (recover/restore/resolve/repair/respond) and (b) DORA scopes it to *deployment-caused* failures and prefers the *median* despite the legacy "Mean" in the acronym.
- *Probe: Restore vs. fix?* "Restore" can be a fast rollback even if the permanent fix lands later — the metric is about acceptable service.

**Q9. What does Deployment Frequency actually proxy, and how do you report it robustly?**
*Model answer:* It proxies batch size and cadence. Report a count per window or, more robustly, the **median inter-deploy interval**, which resists deploy bursts.
- *Probe: Do feature-flag flips count?* No — DF measures the deploy mechanism's throughput, not release toggles.

**Q10 (senior-signal). A team is "elite" on all four metrics but customers are unhappy with the product. Reconcile.**
*Model answer:* DORA measures the *delivery system* (how fast/safely changes ship), not product value, UX, or even operational reliability. Add the 5th metric (Reliability/SLOs) and complement with product and SPACE-style metrics. Being elite at delivery means you can *iterate* fast — it doesn't guarantee you're shipping the right things or meeting user expectations.
- *Probe: Which complementary framework?* SPACE (broader productivity) and flow metrics (full idea→prod), plus SLO-based reliability.
- *Probe: Risk of over-indexing on DORA?* Optimizing delivery while ignoring product outcomes and developer well-being.

**Q11. How do you detect that a process change actually improved delivery?**
*Model answer:* Compare distributions over time (control charts / shifting medians), annotate the dashboard at the change date, and use rolling windows to cut noise — not a two-point eyeball comparison.

**Q12 (senior-signal). Per-service vs. org-level DORA — what's your stance?**
*Model answer:* Measure per service/team; clusters are actionable there and org rollups hide bimodality. When aggregating, aggregate underlying *events*, not tier labels, and weight by deploy volume — never average tier names.

---

## 11. Glossary

- **APM (Application Performance Monitoring):** Tools (Datadog, New Relic, Dynatrace) that trace requests, surface latency/errors, and mark deploys.
- **Artifact:** The deployable build output (JAR/WAR, container image, binary).
- **Artifact repository:** Storage for artifacts (Nexus, Artifactory, container registry).
- **Batch size:** Amount of change shipped per deploy; the hidden master variable behind all four metrics.
- **Blue-green deployment:** Two production environments; traffic switches from old (blue) to new (green), enabling instant rollback.
- **Canary deployment:** Routing a small traffic % to a new version first to catch failures before full rollout.
- **Change:** A commit or set of commits merged to mainline.
- **Change Failure Rate (CFR):** % of prod deploys that cause a failure needing remediation.
- **CI (Continuous Integration):** Frequently merging and auto-verifying changes to mainline.
- **CD (Continuous Delivery/Deployment):** Keeping mainline always deployable (delivery) / auto-deploying every passing change (deployment).
- **Cluster analysis:** Unsupervised statistical grouping; DORA uses it to derive elite/high/medium/low tiers.
- **Control chart:** A statistical chart showing whether a process metric is varying within normal bounds or shifted.
- **Cycle time / flow time:** Broader idea→prod (or in-progress→done) duration; wider than DORA's lead time.
- **Deployment:** Putting a new version into an environment; DORA counts prod deploys.
- **Deployment Frequency (DF):** How often you successfully deploy to prod.
- **Deployment pipeline:** Automated commit→prod stage sequence.
- **DORA:** DevOps Research and Assessment; the research program and its metrics.
- **Elite/High/Medium/Low:** The four empirical performance clusters.
- **Error budget:** Allowed failure under an SLO (e.g., 0.1% for 99.9%).
- **Failed-Deployment Recovery Time:** Median time to restore service after a deploy-caused failure (DORA's renamed MTTR).
- **Feature flag:** Runtime toggle enabling/disabling code without redeploy; decouples deploy from release.
- **Fix-forward / roll-forward:** Shipping a new fixing version instead of reverting.
- **GitOps:** Operating systems by reconciling live state to a git-declared desired state (Argo CD, Flux).
- **Goodhart's law:** "When a measure becomes a target, it ceases to be a good measure."
- **Incident:** Unplanned service degradation/interruption.
- **Lead Time for Changes (LT):** Commit→running-in-prod duration.
- **Mainline / trunk:** The shared branch representing the source of truth.
- **Mean / Median / Percentile:** Average / middle value / value below which X% fall.
- **MTTR:** Overloaded acronym (Mean Time To Recover/Restore/Resolve/Repair/Respond); DORA scopes it to deploy-caused recovery and uses the median.
- **Production:** The live environment serving real users.
- **Progressive delivery:** Gradual rollout techniques (canary, blue-green, ring deployments).
- **Reliability:** DORA's 5th metric — operational performance against SLOs.
- **Release:** Making functionality available to users (can be decoupled from deploy via flags).
- **Right-skew:** A distribution with a long high-value tail; typical of delivery durations.
- **Rollback:** Reverting to the previous known-good version.
- **SLI / SLO / SLA:** Measured indicator / target / contractual target with consequences.
- **SHA:** Git commit hash; the join key linking commits to deploys.
- **SPACE framework:** Holistic developer-productivity model (Satisfaction, Performance, Activity, Communication, Efficiency).
- **SRE (Site Reliability Engineering):** Google-originated discipline that gave us SLOs/error budgets.
- **State of DevOps Report / Accelerate:** DORA's annual publication and the book summarizing the research.
- **Trunk-based development (TBD):** Integrating to a single shared branch at least daily via short-lived branches.
- **Value stream:** The full sequence of steps from idea to delivered value.
- **Wilson confidence interval:** A statistically sound interval for a proportion (useful for small-N CFR).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The four (+1) metrics**
- **Deployment Frequency** — how often you deploy to prod (throughput). Report: count/window or median inter-deploy gap. Elite ≈ on-demand (multiple/day).
- **Lead Time for Changes** — commit→prod (throughput). Report: **median** + p75/p90. Elite < 1 day.
- **Change Failure Rate** — failed deploys ÷ total deploys (stability). Read **with** DF. Elite ≈ 0–15%.
- **Failed-Deployment Recovery Time** — median time to restore after deploy-caused failure (stability). Elite < 1 hour.
- **Reliability (5th)** — SLO attainment / error-budget health.

**Cluster ladder (directional; report-year-specific):**
| Metric | Elite | High | Medium | Low |
|--------|-------|------|--------|-----|
| DF | on-demand | daily–weekly | weekly–monthly | monthly–6mo |
| LT | < 1 day | day–week | week–month | month–6mo |
| Recovery | < 1 hr | < 1 day | ~day–week | week–month |
| CFR | 0–15% | 16–30% | 16–30% | higher |

**Core truths**
- Throughput and stability **correlate** — *not* a trade-off (the false dichotomy).
- **Batch size** is the master variable behind all four.
- Use **median/percentiles**, never mean, for durations.
- Measure **per team/service**, never per individual.
- Read CFR/DF **together** (denominator matters).
- Don't make them **targets** (Goodhart) — they're improvement signals.
- The CFR **incident→deploy attribution** is the hardest engineering problem in the pipeline.
- DORA measures *delivery*; pair with **Reliability (SLOs)**, **SPACE**, and **flow metrics** for the full picture.
- Join key = **commit SHA**; embed it in the artifact.
- 5 event types (commit, deploy_started/finished, incident_opened/resolved) compute everything.

**Levers by weak metric:** low DF / high LT → trunk-based dev, smaller PRs, automate pipeline. High CFR → automated tests, canary/blue-green, smaller batches. High recovery → fast rollback, feature flags, monitoring, runbooks. Low Reliability → SLOs + error budgets + perf work.

### 12.2 Self-test (no answers — recall practice)

1. Define each of the four DORA metrics precisely, including the exact start/end events for Lead Time and Recovery Time. Which two are throughput and which two are stability?
2. Explain the stability–throughput "false dichotomy" and the mechanism by which both improve together. What single variable underlies all four metrics?
3. Why must Change Failure Rate be interpreted alongside Deployment Frequency? Give a concrete scenario where reading CFR alone misleads you.
4. Describe how you would build a DORA measurement pipeline from raw events end-to-end: the ~5 event types, the join keys, and which summary statistic you'd use for each metric (and why mean is wrong for some).
5. List four ways DORA metrics get gamed (one per metric) and the organizational practices that prevent gaming. State Goodhart's law and explain its relevance.
6. What does the 5th metric add, and reconcile the case of a team that is "elite" on all four delivery metrics yet has unhappy users? Which complementary frameworks would you bring in?
7. Why is the incident→deployment attribution the hardest part of computing CFR, and what are the three approaches ranked best to worst?
```

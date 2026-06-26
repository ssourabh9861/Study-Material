# Deployment Strategies

> A definitive engineering-handbook chapter on how software changes are pushed into production safely, with a focus on zero-downtime delivery, progressive rollout, automated risk control, and the often-missed problem of database evolution.

---

## 1. Overview & where it fits

A **deployment strategy** is the *plan* by which a new version of software replaces (or coexists with) the currently running version in an environment, especially production. It answers four questions:

1. **How do old and new versions overlap in time** — do they ever run simultaneously, and for how long?
2. **How is traffic routed** between them during the transition?
3. **How do you decide the new version is healthy** before committing fully?
4. **How do you back out** if it is not?

**The problem it solves.** Naively, deploying means "stop the old binary, start the new one." That causes downtime (a window where nothing serves traffic) and is all-or-nothing (if the new version is broken, *every* user is broken immediately, and recovery is slow). Modern services have uptime expectations measured in "nines" — **99.9% availability** is ~43 minutes of downtime per month; **99.99%** is ~4.3 minutes per month. You cannot hit those numbers if every deploy causes even a minute of outage and you deploy many times a day. Deployment strategies exist to make releases **zero-downtime** and **low-blast-radius**: a bad release should affect as few users as possible, for as short a time as possible, and be reversible fast.

> **Blast radius** — a borrowed military/nuclear term meaning "how much is damaged by one failure." In deployments it means: if this release is bad, what fraction of users/requests/data is harmed before you stop it? Small blast radius is the central goal of progressive strategies.

**When you reach for which strategy (one-line mental model each):**

- **Recreate** — "turn it off, turn it on." Accepts downtime. Use for dev, or stateful singletons that cannot run two versions at once.
- **Rolling** — "replace instances a few at a time." The default for most stateless services and the Kubernetes default. Zero-downtime, slow-ish, limited control over *who* sees the new version.
- **Blue-green** — "stand up a full second environment, then flip a switch." Instant cutover and instant rollback, at roughly **2× the infrastructure** during the transition.
- **Canary** — "send 1% of traffic to the new version, watch metrics, ramp up." The lowest-blast-radius strategy; the foundation of progressive delivery.
- **Shadow / dark launch** — "mirror real traffic to the new version but throw away its responses." Tests with production load *without* affecting any user. Read-path validation; dangerous for write paths.

**The one-paragraph mental model.** Think of a deployment as moving a population (your traffic) from one building (the old version) to another (the new version). Recreate evacuates everyone, demolishes the old building, builds the new one, and lets everyone back in — there is a gap with no building. Rolling moves people room-by-room as you renovate. Blue-green builds an identical second building next door and flips which front door is "open." Canary sends a handful of volunteers into the new building first and watches whether they get sick before sending the crowd. Shadow sends *copies* of people into the new building to test the plumbing while the real people stay in the old one. The art is choosing the move that matches your **statefulness**, your **risk tolerance**, your **budget**, and how good your **observability** is at telling healthy from sick.

**Where this fits in CI/CD.** Continuous Integration (CI) builds and tests an artifact. Continuous Delivery/Deployment (CD) ships it. Deployment strategy is the *last mile of CD* — the runtime mechanics of swapping versions. It sits downstream of artifact builds and upstream of (or interleaved with) **release management** (feature flags, gradual rollout) and **observability** (the signals that gate promotion). In a mature org, deployment strategy is automated by a delivery controller (Argo Rollouts, Flagger, Spinnaker) and governed by **SLOs** (Service Level Objectives — the numeric reliability targets, e.g. "99.9% of requests succeed in <300ms").

---

## 2. Foundations from first principles

Before strategies, you must understand the primitives they manipulate.

### 2.1 What "a deployment" actually touches

A running service version is a set of **process instances** (pods, VMs, containers, lambdas) behind a **traffic router** (a load balancer, an Ingress, a service mesh, DNS). To deploy a new version you must:

1. Provision instances running the new artifact.
2. Make them **ready** (passing health checks).
3. Direct some or all traffic to them.
4. Drain and terminate old instances.

Every strategy is a different ordering and pacing of these four steps.

### 2.2 Core terms (defined as introduced)

- **Health check / probe** — a request the platform makes to an instance to ask "are you alive and able to serve?" Kubernetes has three:
  - **Liveness probe** — "is the process wedged?" Failing it *restarts* the container.
  - **Readiness probe** — "should this instance receive traffic right now?" Failing it removes the instance from the load balancer *without* killing it. This is the probe deployment strategies care about most — an instance that is starting up, warming caches, or shedding load is *live* but *not ready*.
  - **Startup probe** — "is the app still doing slow first-time initialization?" Used to avoid liveness killing a slow-booting app (e.g. a JVM doing JIT warmup and class loading).
- **Graceful shutdown / connection draining** — when an old instance is told to stop, it should (a) stop accepting *new* connections, (b) finish in-flight requests, (c) then exit. The window allowed for this is the **termination grace period** (Kubernetes default **30 seconds**, `terminationGracePeriodSeconds`). Without draining you get a burst of dropped requests / 502s on every deploy.
- **Idempotency** — an operation that produces the same result whether run once or many times. Critical because during overlapping versions, retries and duplicate processing happen; non-idempotent writes (e.g. "increment balance") corrupt data.
- **Stateless vs stateful** — a **stateless** service keeps no per-client data in memory between requests (all state is in a database/cache); any instance can serve any request. A **stateful** service (a database, a leader, a stateful stream processor) holds data or identity locally. Stateless services can use any strategy; stateful ones are constrained (you often cannot just run two leaders).
- **Backward / forward compatibility** —
  - **Backward compatible**: new code can read/handle data and requests produced by the *old* code.
  - **Forward compatible**: old code can tolerate data/requests produced by the *new* code (e.g. ignores unknown fields).
  During any zero-downtime deploy, old and new run simultaneously, so you need **both** directions of compatibility — old must not choke on new's data and vice versa. This is the single most violated requirement in real incidents.
- **SLI / SLO / error budget** —
  - **SLI** (Service Level Indicator): a measured number, e.g. request error rate, p99 latency.
  - **SLO** (Service Level Objective): the target for an SLI, e.g. "error rate < 0.1% over 28 days."
  - **Error budget**: `1 − SLO`. If your SLO is 99.9%, you may "spend" 0.1% of requests on failures (including bad deploys). Canary analysis literally watches whether a release is eating the error budget too fast.
- **Traffic shaping / weighted routing** — the ability of the router to send, say, 5% of requests to v2 and 95% to v1. Implemented by load-balancer weights, mesh routing rules (Istio `VirtualService`, Linkerd `TrafficSplit`/Gateway API), or replica-count ratios (the cheap approximation).
- **Bake time / soak** — a deliberate waiting period during which a new version receives a slice of traffic and you *watch* before increasing exposure. Bugs that only appear under load, over time (memory leaks, connection-pool exhaustion, cache cold-start), need bake time to surface.
- **Promotion / rollback / roll-forward** — *promotion* = increasing the new version's traffic share (eventually 100%). *Rollback* = reverting to the previous known-good version. *Roll-forward* = fixing the bug and deploying a *newer* version rather than going back.

### 2.3 Why downtime happens (so you can avoid it)

Downtime during deploys comes from a handful of mechanical causes, each with a fix:

| Cause | Mechanism | Fix |
|---|---|---|
| Kill before ready | New instance gets traffic before it can serve | Readiness probe gates LB membership |
| Kill without draining | Old instance dies mid-request | `preStop` hook + grace period + drain |
| LB lag | LB still routes to a dead instance for a few seconds | `preStop sleep` to let endpoints propagate |
| Schema mismatch | New code expects a column old code didn't write / dropped a column old code reads | Expand-contract migrations (§7.4) |
| Capacity dip | Replacing instances drops below required capacity | `maxUnavailable: 0` + surge |
| Cold caches / JIT | New JVM instance is slow until warmed | Startup probe + warmup + gradual ramp |

Hold these in mind; every strategy is judged by how it handles them.

---

## 3. How it works internally

This section walks each strategy step by step — the control flow, data flow, and state transitions — then covers the controllers that automate them.

### 3.1 Recreate

**Mechanics.** Terminate all old instances, then create all new instances.

State transitions:
```
v1 (N replicas, serving)
  → scale v1 to 0        [DOWNTIME WINDOW STARTS]
  → wait for v1 to die
  → create v2 (N replicas)
  → wait for v2 readiness [DOWNTIME WINDOW ENDS]
  → v2 serving
```

- **Downtime** = (time to drain v1) + (time for v2 to become ready). For a JVM service with slow startup this can be tens of seconds to minutes.
- **Why use it at all?** When v1 and v2 *must not run simultaneously*: e.g. they hold an exclusive lock, a single-writer file, a non-shareable license, or a schema that genuinely cannot be made compatible across versions. Also fine for dev/staging.
- **Rollback story:** redeploy v1 the same way — another downtime window.
- **Kubernetes:** `strategy.type: Recreate`.

### 3.2 Rolling update

**Mechanics.** Incrementally replace old instances with new ones, a controlled number at a time, keeping capacity up throughout. This is the **Kubernetes Deployment default**.

The controller (Kubernetes Deployment controller) maintains two **ReplicaSets** — one for v1, one for v2 — and shifts the replica counts:

```
v1=10, v2=0
  → create surge of v2, wait readiness
  → once a v2 is Ready and serving, terminate a v1 (drain it)
  → repeat, respecting maxUnavailable / maxSurge
  → v1=0, v2=10
```

Two knobs govern pacing (under `strategy.rollingUpdate`):
- **`maxUnavailable`** — how many replicas (or %) may be *down* relative to desired count during the roll. Default **25%**. Set to **0** for strict no-capacity-loss (requires surge to add new before removing old).
- **`maxSurge`** — how many *extra* replicas (or %) may exist above desired during the roll. Default **25%**. Higher surge = faster roll but more peak resource use.

So with `replicas: 10, maxSurge: 25%, maxUnavailable: 25%`, the controller may at any moment run up to 13 pods and have as few as 8 serving.

**Internal lifecycle of replacing one pod (Kubernetes):**
1. Controller creates a new pod from the v2 ReplicaSet.
2. **Startup probe** (if any) passes → liveness/readiness begin.
3. **Readiness probe** passes → pod's IP is added to the Service's **EndpointSlice** → kube-proxy / mesh / Ingress start routing to it.
4. To remove a v1 pod: the API server marks it `Terminating`, removes its IP from EndpointSlices, and sends **SIGTERM**.
5. The pod's **`preStop` hook** runs (commonly `sleep 5–15s` to let endpoint removal propagate to all routers, avoiding routing to a draining pod).
6. App receives SIGTERM, stops accepting new connections, finishes in-flight work.
7. After `terminationGracePeriodSeconds`, if still alive, **SIGKILL**.

**Properties:** zero-downtime if probes + draining are correct; gradual; but you cannot precisely control *which users* see v2, cannot easily do metric-gated promotion, and rollback means rolling *forward* to the old image (another full roll, which is not instant). Native rolling has **no automated analysis** — it only checks readiness, not business metrics.

### 3.3 Blue-green

**Mechanics.** Keep the current version ("blue") fully serving. Deploy the new version ("green") as a *complete parallel environment* at full capacity. Test green privately (smoke tests, internal traffic). Then **flip the router** so 100% of traffic goes to green in one atomic switch. Keep blue idle for a while as an instant rollback target; then decommission it.

```
blue=N (serving 100%), green=0
  → deploy green=N (NOT serving)
  → run smoke/integration tests against green
  → atomically repoint router: blue 0% / green 100%
  → observe
  → keep blue idle (rollback insurance) for a bake window
  → tear down blue
```

- **Cutover** is near-instant (a routing change), and **rollback** is equally instant (point back at blue) as long as blue is still up. This is blue-green's superpower.
- **Cost:** ~**2× capacity** during the overlap window. Mitigated by short overlap windows or autoscaling blue down after cutover.
- **The hidden hard part:** anything *shared* between blue and green — primarily the **database** — must be compatible with both at the instant of flip and during rollback. Blue-green does *not* solve schema evolution; you still need expand-contract (§7.4). Sessions, caches, queues, and in-flight async jobs also straddle the cutover.
- **Implementations:** swap a load-balancer target group (AWS: two target groups behind one ALB listener, or CodeDeploy blue/green), swap an Ingress backend Service, DNS weight flip (slow due to TTL/caching — avoid for fast rollback), or an Argo Rollouts `blueGreen` strategy with `activeService`/`previewService`.

### 3.4 Canary

**Mechanics.** Release the new version to a *small* fraction of traffic first, observe health, and progressively increase. The name comes from "canary in a coal mine" — a sensitive sentinel that detects danger before it reaches everyone.

```
stable=100%, canary=0%
  → deploy canary (small replica count or weight)
  → route e.g. 5% → canary, 95% → stable
  → BAKE: collect metrics for canary vs stable (the baseline)
  → analysis passes? ramp 5→20→50→100%, baking at each step
  → analysis fails at any step? abort, route 100% back to stable, kill canary
```

Two ways to split traffic:
- **Weighted routing (precise):** a mesh/LB sends exactly X% to canary regardless of replica count. Requires Istio/Linkerd/Gateway API/ALB weights.
- **Replica-ratio (approximate):** if canary has 1 of 20 pods, it gets ~5% by random LB distribution. Cheap, no mesh, but imprecise and coupled to capacity.

**Canary vs blue-green:** blue-green is 0%→100% in one step (binary); canary is a *ramp* with checkpoints. Canary has the smallest blast radius but the most moving parts (you need good metrics and a controller).

**Session stickiness consideration:** if a user can be routed to canary on one request and stable on the next, behavior may flip-flop. For UI/stateful flows you may want sticky routing (hash by user/session) so a given user consistently sees one version.

### 3.5 Automated Canary Analysis (ACA) — the heart of progressive delivery

A canary is only as good as the decision "is it healthy?" Doing this by eyeball does not scale. **Automated Canary Analysis** makes promotion **metrics-driven**.

How it works internally:
1. Define **metrics** that indicate health: error rate (HTTP 5xx, gRPC non-OK), latency percentiles (p50/p95/p99), saturation (CPU, memory, GC pauses), and **business/SLO metrics** (checkout success rate, etc.).
2. Define a **baseline** to compare against. The robust pattern is **canary vs a fresh "baseline" deployment of the *old* version** — *not* canary vs the long-running stable fleet. Why: the stable fleet has warm caches, warm JIT, and different request mix; comparing a cold canary to a warm stable produces false alarms. So Spinnaker's Kayenta launches a *baseline* of the old version alongside the canary, sending both the same small slice of traffic, so you compare apples to apples.
3. At each step, run an **analysis** over a time window (the **bake/soak**): for each metric, compute whether canary deviates from baseline beyond a threshold. Methods range from simple thresholds (`error_rate < 1%`) to statistical comparison (Mann-Whitney U test in Kayenta) producing a **score 0–100**.
4. **Decision:** score above promote-threshold → advance to next weight; below fail-threshold → automatic rollback; in between → optionally hold/manual.
5. Repeat across the **ramp schedule** until 100%.

**Bake time** must be long enough for slow failures (memory leaks, connection-pool exhaustion, downstream rate limits, cron-triggered bugs) to appear — minutes for fast signals, but sometimes 30–60 min or hours for resource leaks. Too short and you promote a time-bomb; too long and deploys crawl. Tune per service.

### 3.6 Progressive delivery (the umbrella)

**Progressive delivery** = canary + ACA + feature flags + observability, generalized: ship to a *progressively larger and more representative* audience, gated by automated checks, with instant rollback. It decouples **deploy** (code is running) from **release** (users can reach the new behavior). Feature flags let you ship dark code and "release" by flipping a flag — independent of the deployment strategy. The two compose: deploy the binary via canary, then expose features via flags to cohorts (internal → beta → % of users → all).

### 3.7 Shadow / dark launch (traffic mirroring)

**Mechanics.** The router sends each real request to the stable version (whose response the user gets) **and a copy** to the new version, whose response is **discarded**. The new version thus experiences real production traffic patterns and load *without* affecting any user.

```
request → router ─┬→ stable  → response to user
                  └→ (mirror) shadow → response DISCARDED
```

- **Purpose:** validate a rewrite/refactor under real load and real input distributions — performance, error rates, correctness (by comparing shadow responses to stable responses offline, "diffing").
- **Critical danger — side effects.** If the shadow service performs **writes** (DB inserts, sends emails, charges cards, publishes events, calls payment APIs), mirroring causes **double effects**. Shadowing is safe only when the new path is **read-only** or its writes are routed to a **sandbox** (separate DB, mocked downstreams, swallowed side effects). This is the most common shadow-launch incident.
- **Implementations:** Istio `VirtualService` `mirror` + `mirrorPercentage`; Envoy `request_mirror_policies`; NGINX `mirror` directive; GoReplay (gor) for capture/replay; Diffy/diffy-style tools for response diffing.
- **Note on naming:** "dark launch" sometimes means *feature-flagged code shipped off* and sometimes means *traffic mirroring*. They overlap; mirroring is the traffic-level form.

### 3.8 The controllers that automate all this

- **Kubernetes Deployment** — native rolling/recreate only; no traffic weighting beyond replica ratios; no metric analysis.
- **Argo Rollouts** — a CRD (`Rollout`) replacing Deployment, adding `canary` and `blueGreen` strategies, **traffic-weight steps**, **AnalysisTemplates** (query Prometheus/Datadog/etc. and pass/fail), pause steps, and integrations with Istio/SMI/Gateway API/ALB/NGINX for precise weighting.
- **Flagger** — a controller that *wraps existing Deployments*; you keep your Deployment and add a `Canary` CR. Flagger automates canary/blue-green/A-B, drives the mesh/ingress weights, runs metric checks and **webhooks** (load tests, acceptance tests), and auto-promotes or rolls back. Works with Istio, Linkerd, App Mesh, Contour, Gloo, NGINX, Gateway API.
- **Spinnaker + Kayenta** — multi-cloud CD platform; **Kayenta** is its ACA engine doing statistical canary scoring (the baseline-vs-canary, Mann-Whitney approach).

---

## 4. The complete toolkit

### 4.1 Kubernetes Deployment strategy fields

| Field | Purpose | Default |
|---|---|---|
| `spec.strategy.type` | `RollingUpdate` or `Recreate` | `RollingUpdate` |
| `strategy.rollingUpdate.maxUnavailable` | Max pods unavailable during roll (count or %) | `25%` |
| `strategy.rollingUpdate.maxSurge` | Max extra pods during roll (count or %) | `25%` |
| `spec.minReadySeconds` | Pod must be ready this long before counted available | `0` |
| `spec.progressDeadlineSeconds` | Mark rollout failed if no progress in this time | `600` |
| `spec.revisionHistoryLimit` | Old ReplicaSets kept for rollback | `10` |
| `terminationGracePeriodSeconds` (pod) | Time between SIGTERM and SIGKILL | `30` |
| `lifecycle.preStop` (container) | Hook run before SIGTERM (e.g. drain sleep) | none |

Probe fields (per container, under `readinessProbe`/`livenessProbe`/`startupProbe`): `initialDelaySeconds`, `periodSeconds` (default 10), `timeoutSeconds` (1), `successThreshold` (1; readiness can be >1), `failureThreshold` (3).

Relevant `kubectl` commands:

| Command | Does |
|---|---|
| `kubectl rollout status deploy/X` | Wait/report on roll progress |
| `kubectl rollout history deploy/X` | List revisions |
| `kubectl rollout undo deploy/X` | Roll back to previous revision |
| `kubectl rollout undo deploy/X --to-revision=N` | Roll back to a specific revision |
| `kubectl rollout pause/resume deploy/X` | Pause mid-roll (manual canary) |
| `kubectl rollout restart deploy/X` | Re-roll without spec change (e.g. pick up new config/secret) |

### 4.2 Argo Rollouts (`Rollout` CRD) key fields

| Field | Purpose |
|---|---|
| `strategy.canary.steps[]` | Ordered steps: `setWeight`, `pause`, `analysis`, `setCanaryScale`, `experiment` |
| `setWeight: N` | Send N% to canary (needs traffic provider) |
| `pause: {duration: 5m}` or `{}` | Timed pause or pause-until-manual-promote |
| `analysis.templates[]` | Reference `AnalysisTemplate`s to gate the step |
| `strategy.canary.trafficRouting` | `istio` / `nginx` / `alb` / `smi` / `plugins` (Gateway API) |
| `strategy.canary.maxSurge` / `maxUnavailable` | Like Deployment |
| `strategy.blueGreen.activeService` / `previewService` | Two Services for BG |
| `strategy.blueGreen.autoPromotionEnabled` | Auto flip vs manual |
| `strategy.blueGreen.scaleDownDelaySeconds` | Keep old up after flip (rollback window), default 30 |
| `strategy.blueGreen.prePromotionAnalysis` / `postPromotionAnalysis` | Gate the flip with metrics |

`AnalysisTemplate` fields: `metrics[].provider` (prometheus, datadog, newrelic, wavefront, cloudwatch, web, job, kayenta), `interval`, `count`, `successCondition`, `failureCondition`, `failureLimit`, `inconclusiveLimit`.

CLI: `kubectl argo rollouts get rollout X --watch`, `... promote X`, `... abort X`, `... retry X`, `... set image`.

### 4.3 Flagger (`Canary` CR) key fields

| Field | Purpose |
|---|---|
| `spec.targetRef` | The Deployment to manage |
| `spec.provider` | istio / linkerd / appmesh / nginx / contour / gloo / gatewayapi |
| `analysis.interval` | How often to evaluate |
| `analysis.threshold` | Number of failed checks before rollback |
| `analysis.maxWeight` | Max canary weight before full promotion |
| `analysis.stepWeight` | Weight increment per interval |
| `analysis.metrics[]` | Built-in (`request-success-rate`, `request-duration`) or custom `MetricTemplate` |
| `analysis.webhooks[]` | Pre-rollout/rollout/confirm/load-test hooks |

### 4.4 Traffic-shaping providers

| Tool | Mechanism | Granularity |
|---|---|---|
| Istio `VirtualService` | Mesh sidecar weights, `mirror`, header/cookie match | Per-route % + match rules |
| Linkerd + Gateway API / SMI `TrafficSplit` | Service-to-service weights | Per-backend % |
| Envoy | `weighted_clusters`, `request_mirror_policies` | % + mirror |
| AWS ALB | Weighted target groups | % per target group |
| NGINX Ingress | `canary-weight` / `canary-by-header` annotations | % or header |
| Kubernetes Gateway API | `HTTPRoute` `backendRefs[].weight` | % per backend |
| Spinnaker | Deployment pipeline stages + Kayenta | Step-based |

### 4.5 Database migration tools (for §7.4)

| Tool | Niche |
|---|---|
| Flyway | SQL-first versioned migrations (`V1__init.sql`), JVM-native |
| Liquibase | XML/YAML/SQL changesets, rollback support, preconditions |
| gh-ost / pt-online-schema-change | Online MySQL `ALTER` without long locks |
| Online schema change in Postgres | `ALTER ... ADD COLUMN` is cheap (no rewrite for nullable/defaulted since PG11); use `CREATE INDEX CONCURRENTLY`; `lock_timeout` to avoid queue pileups |
| Reshape / pgroll | Expand-contract automation for Postgres |

---

## 5. Code examples by use case

### 5.1 Kubernetes rolling update with safe draining (the production default)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders-api
spec:
  replicas: 10
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0    # never drop below desired capacity
      maxSurge: 2          # add up to 2 extra during the roll
  minReadySeconds: 10      # require 10s of stable readiness before counting a pod
  progressDeadlineSeconds: 300
  template:
    spec:
      terminationGracePeriodSeconds: 45
      containers:
        - name: app
          image: registry/orders-api:1.4.2
          lifecycle:
            preStop:
              exec:
                # Sleep so the LB/EndpointSlice removal propagates BEFORE we
                # start refusing connections — prevents 502s during drain.
                command: ["sh", "-c", "sleep 10"]
          startupProbe:        # JVM can be slow to boot; protect it from liveness
            httpGet: { path: /healthz/startup, port: 8080 }
            failureThreshold: 30
            periodSeconds: 5   # allows up to 150s to start
          readinessProbe:      # gates LB membership
            httpGet: { path: /healthz/ready, port: 8080 }
            periodSeconds: 5
            failureThreshold: 3
          livenessProbe:
            httpGet: { path: /healthz/live, port: 8080 }
            periodSeconds: 10
            failureThreshold: 3
```

The Spring Boot side that makes draining actually work:

```java
// application.yml equivalents shown as comments
// server.shutdown=graceful           -> finish in-flight requests on SIGTERM
// spring.lifecycle.timeout-per-shutdown-phase=30s
// management.endpoint.health.probes.enabled=true  (separate /readiness & /liveness)

@Component
class ReadinessController {
    private final ApplicationAvailability availability;
    ReadinessController(ApplicationAvailability a) { this.availability = a; }

    // When SIGTERM arrives, Spring flips readiness to REFUSING_TRAFFIC,
    // so the readiness probe fails and the LB stops routing to us
    // BEFORE we stop the server — combined with preStop sleep this is clean.
    @EventListener
    void onShutdown(AvailabilityChangeEvent<ReadinessState> e) { /* log */ }
}
```

### 5.2 Argo Rollouts canary with automated analysis (Prometheus-gated)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata: { name: checkout }
spec:
  replicas: 10
  strategy:
    canary:
      trafficRouting:
        istio:
          virtualService: { name: checkout-vs, routes: [primary] }
      steps:
        - setWeight: 5
        - pause: { duration: 5m }          # bake at 5%
        - analysis:                         # gate before going further
            templates: [{ templateName: success-rate }]
        - setWeight: 25
        - pause: { duration: 5m }
        - analysis: { templates: [{ templateName: success-rate }] }
        - setWeight: 50
        - pause: { duration: 10m }
        - analysis: { templates: [{ templateName: success-rate }] }
        - setWeight: 100
  selector: { matchLabels: { app: checkout } }
  template:
    metadata: { labels: { app: checkout } }
    spec:
      containers:
        - name: app
          image: registry/checkout:2.0.0
---
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata: { name: success-rate }
spec:
  metrics:
    - name: success-rate
      interval: 1m
      count: 5                 # 5 samples over ~5 min
      successCondition: result[0] >= 0.99   # >=99% success
      failureLimit: 2          # 2 bad samples -> abort & rollback
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            sum(rate(http_requests_total{app="checkout",code!~"5..",
                     stage="canary"}[1m]))
            /
            sum(rate(http_requests_total{app="checkout",stage="canary"}[1m]))
```

If the success rate dips below 99% for two samples, Argo automatically aborts and shifts 100% back to stable — no human in the loop.

### 5.3 Argo Rollouts blue-green with pre-promotion gate

```yaml
spec:
  strategy:
    blueGreen:
      activeService: checkout-active     # prod traffic
      previewService: checkout-preview   # test traffic only
      autoPromotionEnabled: false        # require manual/gated promote
      scaleDownDelaySeconds: 600         # keep blue 10 min for instant rollback
      prePromotionAnalysis:              # run smoke metrics on green BEFORE flip
        templates: [{ templateName: success-rate }]
        args: [{ name: service, value: checkout-preview }]
```

Promote with `kubectl argo rollouts promote checkout`; rollback within the 10-minute window with `... abort` (instant — blue is still running).

### 5.4 Istio canary with header-based "internal users first" routing

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata: { name: api-vs }
spec:
  hosts: [api.internal]
  http:
    - match:                              # employees opt in via header
        - headers: { x-canary: { exact: "true" } }
      route:
        - destination: { host: api, subset: v2 }
    - route:                              # everyone else: 95/5 split
        - destination: { host: api, subset: v1 }
          weight: 95
        - destination: { host: api, subset: v2 }
          weight: 5
```

This gives a "dogfood" cohort (employees set the header) plus a small random canary — a common progressive-delivery pattern.

### 5.5 Shadow / traffic mirroring (read-only safety)

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata: { name: search-vs }
spec:
  hosts: [search]
  http:
    - route:
        - destination: { host: search, subset: v1 }   # users get v1's response
      mirror: { host: search, subset: v2 }             # v2 gets a copy
      mirrorPercentage: { value: 10.0 }                # mirror 10% of traffic
```
The v2 deployment **must** be configured to treat all downstream writes as no-ops or point at a sandbox DB — otherwise mirrored requests double-write. Verify via a kill-switch env var:

```java
// In the mirrored (shadow) deployment, side effects are disabled.
@Service
class OrderWriter {
  @Value("${shadow.mode:false}") boolean shadow;
  void persist(Order o) {
    if (shadow) { metrics.increment("shadow.write.suppressed"); return; }
    repo.save(o);
  }
}
```

### 5.6 Flyway expand-contract migration (rename a column with zero downtime)

The goal: rename `users.name` → `users.full_name` while old and new code run together. You can NEVER do a single `ALTER ... RENAME` during a zero-downtime deploy because old code reads `name`, new code reads `full_name`.

```sql
-- V10__expand_add_full_name.sql  (deploy with code release R1)
ALTER TABLE users ADD COLUMN full_name VARCHAR(255);   -- nullable, cheap
-- Backfill in batches to avoid long locks / huge transactions:
UPDATE users SET full_name = name WHERE full_name IS NULL LIMIT 10000; -- repeat
-- Dual-write trigger so old code's writes to `name` keep full_name in sync:
CREATE TRIGGER sync_name BEFORE INSERT OR UPDATE ON users
  FOR EACH ROW SET NEW.full_name = COALESCE(NEW.full_name, NEW.name);
```
Release sequence:
1. **R1 (expand):** add `full_name`, backfill, dual-write trigger. Code still reads `name`. Both versions work.
2. **R2 (migrate reads/writes):** new code reads & writes `full_name`; still writes `name` too (dual-write) for any lingering old pods. Deploy via canary.
3. **R3 (contract):** once *no* code references `name`, drop the trigger and the column.

```sql
-- V12__contract_drop_name.sql  (deploy after R2 is fully rolled out)
DROP TRIGGER sync_name;
ALTER TABLE users DROP COLUMN name;
```

Each step is independently safe to roll back because at no point does either running version depend on a column the other can't satisfy.

### 5.7 NGINX Ingress canary (no service mesh)

```yaml
# stable Ingress unchanged; add a second Ingress for the canary:
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: app-canary
  annotations:
    nginx.ingress.kubernetes.io/canary: "true"
    nginx.ingress.kubernetes.io/canary-weight: "10"   # 10% to canary
    # or: canary-by-header: "x-canary"  / canary-by-cookie: "canary"
spec:
  rules:
    - host: app.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend: { service: { name: app-v2, port: { number: 80 } } }
```

---

## 6. Implementation concerns & best practices

**Performance.**
- New JVM instances are *cold*: empty caches, interpreted-then-JIT-compiled hot paths, unfilled connection pools. A cold canary will show worse p99 than warm stable — compare against a **cold baseline**, not the warm fleet, or include a **warmup** phase (synthetic traffic / `setCanaryScale` ramp) before analysis.
- `maxSurge` too low → slow rolls; too high → resource spikes and possible scheduler pressure. Size against cluster headroom.

**Correctness & concurrency.**
- During every overlapping-version strategy you have **N-1/N version skew**: old and new run together. *All* contracts must be backward+forward compatible: API request/response, message/event schemas (use schema registries with compatibility checks), DB schema (expand-contract), and serialized cache/session formats. The classic failure: new code adds a required field; old producers omit it; new consumers crash.
- Make message consumers tolerant of unknown fields (Protobuf/Avro do this naturally; raw JSON needs `FAIL_ON_UNKNOWN_PROPERTIES=false`).
- Idempotency keys on writes so retries during cutover don't double-process.

**Memory.** Watch for leaks that only appear under sustained traffic — this is *why bake time exists*. Add a long-bake step for memory-sensitive services and alert on RSS/heap growth slope, not just instantaneous value.

**Security.**
- Shadow traffic can leak PII into a less-hardened environment; ensure the shadow env meets the same data-handling controls or strip sensitive fields.
- Preview/green environments are real prod with real data — secure them identically; don't expose `previewService` publicly.
- Rollback must not reintroduce a *security* fix regression; for security patches prefer **roll-forward**.

**Cost.**
- Blue-green ~2× during overlap; canary ~+5–25% transient. Use autoscaling and short overlap windows. Spinnaker/Kayenta's baseline deployment also costs extra capacity during analysis.

**Observability (non-negotiable).**
- You cannot do canary/ACA without per-version metrics. **Label every metric/log/trace with the version (`stage=canary|stable`, `version=2.0.0`)** so you can compare. Without this, all progressive strategies degrade to "deploy and pray."
- Required signals (the "golden signals"): latency, traffic, errors, saturation — per version. Plus business KPIs.

**Testability.**
- Smoke/acceptance tests run against green/preview before promotion (Flagger `webhooks`, Argo `prePromotionAnalysis` + job analysis).
- Test the *rollback path* itself — many teams discover at 3 AM that `rollout undo` doesn't work because the old image was garbage-collected or the schema already contracted.

**Production hardening checklist.** Readiness + liveness + startup probes correct; `preStop` drain; `maxUnavailable: 0`; PodDisruptionBudget so cluster ops don't take you below quorum; per-version metrics; defined SLOs and auto-abort thresholds; expand-contract for all schema changes; a tested rollback runbook; feature flags decoupling release from deploy.

**Anti-patterns.**
- **"Big bang" deploys** of binary + breaking schema + config in one irreversible step.
- **Canary without analysis** ("we sent 5% but watched nothing").
- **Bake time of zero** — promoting in seconds.
- **Comparing cold canary to warm stable** — false negatives/positives.
- **Shadowing a write path** without sandboxing — duplicate side effects.
- **DNS-based blue-green for fast rollback** — TTL/caching means the flip and the rollback are *not* instant.
- **Assuming rollback is free** when you've already run a destructive ("contract") migration.

---

## 7. Advanced topics & deep internals

### 7.1 Kayenta statistical analysis

Kayenta (Spinnaker's ACA) doesn't just threshold; it compares the *distributions* of canary vs baseline metrics using a non-parametric test (Mann-Whitney U), tolerant of outliers and not assuming normality. It buckets metrics, computes per-metric pass/fail, aggregates into a **score 0–100**, and maps the score to *Pass / Marginal / Fail* via configurable thresholds. The "baseline = freshly deployed old version" pattern is essential: it controls for cache warmth, host placement, and time-of-day traffic, isolating the *version* as the only variable.

### 7.2 Traffic weight vs replica count under the hood

With a mesh, weight is enforced at the sidecar/proxy: each Envoy independently makes weighted random choices, so 5% is statistically accurate even with few replicas. Without a mesh, "weight" = replica ratio, which is *coupled to capacity* — to get 5% you need ≥20 replicas, and HPA autoscaling silently changes your weight. Argo Rollouts' `setCanaryScale` lets you decouple replica count from traffic weight (e.g. weight 50% but scale canary to 10% of pods if it's CPU-light) when using mesh routing.

### 7.3 Sticky canaries and consistency

For flows where a user must see *one* version consistently (multi-step checkout, A/B tests), route by a hash of user/session ID (Istio `consistentHash` on a header/cookie), so the same user always lands on the same subset. Otherwise per-request randomness flips them between versions and can break stateful UX or skew experiment metrics.

### 7.4 Database migrations during zero-downtime deploys — the deep version

This is the most-missed, highest-risk area. The DB is **shared state** that *no* deployment strategy can swap atomically with the code. The rule: **schema changes and code changes must each be independently deployable and independently reversible; never couple a breaking schema change to a single release.** Use **expand-contract** (a.k.a. parallel change):

1. **Expand** — make additive, backward-compatible changes only: add nullable columns/tables/indexes. Old code is unaffected.
2. **Migrate** — deploy code that writes to *both* old and new shapes (dual-write) and prefers reading new; backfill historical rows in **batches** (small transactions to avoid lock contention and replication lag).
3. **Contract** — only after *all* code no longer touches the old shape, remove it.

Per-operation gotchas:
- **Adding a column:** keep it nullable or with a default; in older MySQL a `DEFAULT` could rewrite the whole table (locking) — use online tools (gh-ost, pt-osc). Postgres ≥11 adds defaulted columns without rewrite.
- **Adding an index:** use `CREATE INDEX CONCURRENTLY` (Postgres) / online DDL (MySQL 5.6+) to avoid locking writes; concurrent index builds can't run in a transaction and may fail and need cleanup.
- **Renaming:** never rename in place — add new, dual-write, backfill, switch reads, drop old (§5.6).
- **Changing a type / NOT NULL:** add new column with new type, dual-write/backfill, swap, drop. Adding NOT NULL on a populated column requires a validated `CHECK` then `SET NOT NULL` (Postgres: `ADD CONSTRAINT ... NOT VALID` then `VALIDATE CONSTRAINT` to avoid a full-table lock).
- **Dropping a column/table:** the contract step — irreversible-ish; ensure nothing references it (grep code + monitor query logs) and that you can restore from backup if wrong.
- **Long-running migrations** can hold locks behind which *all* queries queue (the "lock queue pileup"): set a `lock_timeout`, do work in batches, and watch replication lag — a giant backfill can lag read replicas and break read-after-write assumptions.
- **Migrations vs deploy ordering:** typically run "expand" migrations *before* the code that needs them (init container / pipeline pre-step) and "contract" migrations *after* the old code is fully gone. Coordinate so a mid-deploy state (old+new code) is always valid against the *current* schema.

### 7.5 Rollback vs roll-forward

- **Rollback** = revert to previous version. Best when the new release is broadly broken and the previous version is known-good and still deployable. Fast with blue-green (point back) or Argo abort (shift weight back). **But:** rollback is unsafe if you've already executed a *contract* (destructive) migration, or if the new version wrote data the old version can't read. This is precisely why expand-contract keeps every step rollback-safe.
- **Roll-forward** = fix the bug and deploy a newer version. Best for: security fixes (don't reintroduce the hole), data-format-forward changes (can't go back), or when the previous version also has the bug. Requires fast pipelines so the fix ships in minutes.
- **Decision:** if previous version is good *and* schema/data permit → roll back (fastest recovery). Otherwise → roll forward. Mature teams optimize *both* recovery paths and track **MTTR** (Mean Time To Recovery), one of the four DORA metrics.

### 7.6 Stateful workloads

StatefulSets roll **one pod at a time, in reverse ordinal order**, respecting identity/ordering — relevant for quorum systems (e.g. Kafka, etcd, ZooKeeper). **ZooKeeper** is a distributed coordination service (a consistent, replicated key-value store used for leader election/config); rolling it requires maintaining quorum (a majority of nodes up) at all times, so you update one node, wait for it to rejoin the ensemble, then the next. Databases often need leader-aware rolling (upgrade replicas first, fail over, then the old leader).

### 7.7 Multi-region & DNS

Global rollouts often canary **per region** (deploy to a low-traffic region first, bake, then expand). DNS-level weighting (Route 53 weighted records) is coarse and slow to change (resolver caching/TTL), so prefer it for *region selection*, not for fast in-region rollback.

### 7.8 Combining strategies

Real pipelines compose: blue-green at the cluster level + canary within green; canary the binary + feature-flag the behavior; shadow first (validate), then canary (expose), then full. Progressive delivery is this composition under automated gates.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Strategy comparison

| Strategy | Downtime | Blast radius | Rollback speed | Extra cost | Traffic control | Needs mesh/ACA? | Best for |
|---|---|---|---|---|---|---|---|
| Recreate | Yes (full) | All users | Slow (redeploy) | None | None | No | Dev; exclusive-lock/singleton stateful |
| Rolling | None* | Grows as roll proceeds | Slow (re-roll) | ~maxSurge | Coarse (replica ratio) | No | Default stateless services |
| Blue-green | None | All-at-once at flip | **Instant** (if blue up) | ~2× | Binary 0/100 | No (LB swap) | Need instant rollback; atomic cutover |
| Canary | None | **Smallest** (X%) | Fast (shift back) | +5–25% | Fine (weighted) | Mesh helpful; ACA ideal | High-risk changes; high traffic |
| Shadow | None | **Zero** (no user impact) | N/A (no real exposure) | +N% mirror | Mirror % | Mesh/proxy | Validating rewrites under real load |

\*Zero only if probes + draining are correct.

### 8.2 Use-when / avoid-when

- **Recreate** — *Use when* two versions truly can't coexist or it's non-prod. *Avoid when* uptime matters.
- **Rolling** — *Use when* you want a simple zero-downtime default and don't need per-user control. *Avoid when* you need instant rollback or metric-gated promotion.
- **Blue-green** — *Use when* you need instant, atomic cutover/rollback and can afford 2× briefly. *Avoid when* infra cost is tight or shared state (DB) makes "instant rollback" a lie.
- **Canary** — *Use when* changes are risky and traffic is high enough that 1–5% is a meaningful sample, and you have per-version metrics. *Avoid when* you lack observability or traffic is too low for a small slice to mean anything.
- **Shadow** — *Use when* validating a read-path rewrite under real load. *Avoid when* the path has side effects you can't sandbox.

### 8.3 Choosing a controller

| Need | Pick |
|---|---|
| Simplest, native, rolling only | Kubernetes Deployment |
| Canary/BG + analysis, replace Deployment | Argo Rollouts |
| Keep existing Deployments, add canary | Flagger |
| Multi-cloud, statistical ACA, complex pipelines | Spinnaker + Kayenta |

---

## 9. Failure modes & debugging

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| 502/connection-reset spikes on every deploy | No drain; LB routes to dying pods | LB/ingress 5xx by pod; `kubectl get endpointslices` | `preStop` sleep + readiness flip on SIGTERM + grace period |
| Roll stalls / "progress deadline exceeded" | New pods never become Ready | `kubectl rollout status`, `describe pod`, probe logs | Fix readiness path; raise startup `failureThreshold` |
| Canary looks worse but is fine | Cold canary vs warm stable | Compare cache hit / JIT / pool metrics by version | Warmup phase or cold baseline comparison |
| Promotion happened despite a bug | Bake too short or wrong metric | Argo/Flagger analysis run logs | Lengthen bake; add the right SLI; lower failureLimit |
| Data corruption after deploy | Schema skew; non-idempotent writes | Query logs, app errors referencing missing columns | Expand-contract; idempotency keys |
| Rollback fails | Old image GC'd or schema already contracted | `kubectl rollout history`; migration log | Keep image history; never contract before rollback window closes |
| All queries hang during migration | Long migration holds a lock; queue pileup | DB lock views (`pg_locks`, `SHOW PROCESSLIST`); replica lag | Batch + `lock_timeout`; online DDL tools |
| Shadow caused duplicate emails/charges | Mirrored writes hit prod downstreams | Trace shadow requests; downstream dupes | Sandbox/no-op writes in shadow; kill-switch |
| Mesh weight ignored | Too few replicas (ratio routing) or misconfigured route | `istioctl proxy-config route`; Argo/Flagger events | Use mesh weighted routing; enough replicas; verify VirtualService |

**Real-world incident patterns (industry, generic):**
- *The "missing column" outage:* a migration dropped/renamed a column in the same release that switched reads; during the rolling window old pods queried the gone column → 500s for a fraction of traffic until the roll finished. Root cause: skipped the expand-contract sequence.
- *Cold-cache thundering herd on blue-green flip:* flipping 100% to green at once hit a cold cache and an unwarmed connection pool, causing a latency spike and downstream timeouts; canary/gradual ramp would have surfaced this at 5%.
- *Shadow double-charge:* a payment rewrite was shadowed without disabling its writes; mirrored requests issued real charges. Fixed by a shadow-mode kill switch and sandbox downstreams.
- *DNS rollback that wasn't instant:* a team did blue-green via DNS; when green broke, the "instant" rollback took 5–10 minutes because resolvers cached the record past TTL.

**Debugging toolkit:** `kubectl rollout status/history/undo`, `kubectl describe pod`, `kubectl get events --sort-by=.lastTimestamp`, `kubectl argo rollouts get rollout --watch`, Flagger events (`kubectl describe canary`), `istioctl proxy-config route/cluster`, Prometheus per-version dashboards, distributed traces filtered by version label, DB lock/replication views.

---

## 10. Interview drill

**Q1. Walk me through how a Kubernetes rolling update achieves zero downtime.**
*Model answer:* Two ReplicaSets; the controller adds new pods (respecting `maxSurge`) and only counts them available after readiness (+`minReadySeconds`); it removes old pods only while staying within `maxUnavailable`. Removal sends SIGTERM after pulling the pod from EndpointSlices; a `preStop` sleep lets that removal propagate so no traffic hits a draining pod; the app finishes in-flight requests within the grace period. Zero downtime requires correct readiness probes + draining; otherwise you drop requests.
- *Probe:* What does `maxUnavailable: 0` buy you? → Never dipping below desired capacity; requires surge to add-before-remove.
- *Probe:* Why `preStop sleep` if SIGTERM already drains? → Endpoint removal isn't instantaneous across all proxies; the sleep covers the propagation lag.
- *Probe:* Liveness vs readiness here? → Liveness restarts a wedged pod; readiness gates LB membership — only readiness matters for draining/ramp.

**Q2. Compare blue-green and canary; when do you pick each?**
*Model answer:* Blue-green flips 0→100% atomically — instant cutover and instant rollback (blue stays up) but all-or-nothing blast radius and ~2× cost. Canary ramps with checkpoints — smallest blast radius and metric-gated promotion, but needs good per-version observability and (ideally) weighted routing. Pick blue-green when you need atomic cutover/instant rollback and can afford the cost; canary for risky changes with enough traffic and solid metrics.
- *Probe:* Is blue-green rollback truly instant? → Only if blue is still running and shared state (DB) is compatible; DNS-based flips aren't instant.
- *Probe:* Canary at 1% on a 10-RPS service? → Too little signal; lengthen bake or skip canary — small slices need volume.

**Q3. How do you rename a database column with zero downtime?**
*Model answer:* Never rename in place. Expand (add nullable new column, backfill in batches, dual-write via app or trigger), migrate (deploy code reading/writing the new column while still writing old), contract (after all old code is gone, drop trigger and old column). Each step is independently reversible because neither running version ever depends on a shape the other can't satisfy.
- *Probe:* Why batched backfill? → Avoid long locks, huge transactions, and replication lag.
- *Probe:* When is the contract step safe? → Only when no deployed code references the old column — verify via code + query-log audit.

**Q4. What is automated canary analysis and what's the right baseline?**
*Model answer:* ACA evaluates the canary's metrics against a baseline over a bake window and auto-promotes or auto-rolls-back. The robust baseline is a *freshly deployed copy of the old version* receiving the same small traffic slice — not the warm stable fleet — to control for cache/JIT warmth and traffic mix, isolating the version as the only variable. Kayenta uses Mann-Whitney to score distributions.
- *Probe:* Why not compare to stable? → Warmth/placement differences cause false signals.
- *Probe:* How long should bake be? → Long enough for slow failures (leaks, pool exhaustion) — service-dependent, often minutes to an hour.

**Q5. Explain shadow traffic and its #1 hazard.**
*Model answer:* Mirror real requests to the new version, discard its responses, so it sees real load without user impact. The #1 hazard is side effects: if the shadow path writes to real DBs or calls real downstreams, you double-write/charge. Safe only for read paths or with sandboxed/no-op writes.

**Q6 (senior signal). You must ship a risky change to a 200k-RPS service with a tight error budget. Design the rollout.**
*Model answer:* Ensure per-version metrics and SLO-based auto-abort. Expand-contract any schema first. Shadow the new path if it's a rewrite to validate offline. Then canary via mesh weights: 1%→5%→25%→50%→100% with bakes and Prometheus-gated analysis (success rate, p99, saturation) comparing against a cold baseline; auto-abort on budget burn. Keep feature flags so behavior can be dark-launched independently. Define rollback (shift weight back) and only contract the schema after full rollout. Tradeoff: more steps/time for far smaller blast radius — justified by the tight budget and high RPS (plenty of signal at 1%).
- *Probe:* Why not blue-green? → Atomic 100% flip risks the whole error budget at once; canary bounds exposure.
- *Probe:* What gates promotion? → Statistical/threshold analysis on golden signals + business KPI vs baseline.

**Q7 (senior signal). Rollback or roll-forward — how do you decide, and what makes rollback unsafe?**
*Model answer:* Roll back when the prior version is known-good *and* data/schema permit and it's still deployable — it's the fastest recovery. Roll forward for security fixes, forward-only data changes, or when the old version shares the bug. Rollback is unsafe after a destructive (contract) migration or when the new version wrote data the old can't read — which is exactly why expand-contract keeps each step reversible. Optimize MTTR for both paths.

**Q8 (senior signal). Your canary metrics look bad but the change is fine. What's happening and how do you prevent it?**
*Model answer:* Almost always cold-canary vs warm-stable: empty caches, interpreted code pre-JIT, unfilled pools inflate latency/errors at startup. Prevent by comparing against a freshly deployed baseline (cold-vs-cold), adding a warmup phase before analysis, decoupling `setCanaryScale` from weight, and choosing metrics robust to startup transients (analyze a window after warmup).

**Q9. Why do both versions need to be backward AND forward compatible during a rolling deploy?**
*Model answer:* Because old and new run simultaneously: new code must handle data/requests from old (backward), and old code must tolerate data/requests from new (forward — e.g. ignore unknown fields). Violating either crashes a fraction of traffic mid-roll. Applies to APIs, event schemas, DB schema, and serialized cache/session formats.

**Q10. How does Kubernetes know a pod is ready to receive traffic, and how does that interact with the LB?**
*Model answer:* The readiness probe; when it passes, the pod's IP is added to the Service's EndpointSlice, and kube-proxy/mesh/ingress route to it. Failing readiness removes it from endpoints without killing it. On termination, the IP is removed from endpoints, but propagation lag means a `preStop` delay is needed to avoid routing to a draining pod.
- *Probe:* What if readiness flaps? → Pod oscillates in/out of the LB, causing intermittent errors — fix the probe or its dependencies.

**Q11. What is progressive delivery and how do feature flags relate?**
*Model answer:* Progressive delivery = canary + automated analysis + observability + feature flags, exposing changes to a growing, gated audience with instant rollback. It separates *deploy* (binary running) from *release* (users reach the behavior): you deploy via canary, then flip flags per cohort — so you can ship dark code and release independently of the deployment mechanics.

**Q12. How do you do canary when you have no service mesh?**
*Model answer:* Use replica-ratio canarying (canary pods as a fraction of total → approximate %), NGINX Ingress `canary-weight`/`canary-by-header` annotations, or ALB weighted target groups. Caveat: replica-ratio couples weight to capacity (autoscaling shifts your %), and it's imprecise at low replica counts — prefer ingress/ALB weighting or adopt a mesh for accuracy.

---

## 11. Glossary

- **ACA (Automated Canary Analysis)** — automatic metric-based pass/fail decision on a canary.
- **Backward compatible** — new code handles old data/requests.
- **Bake / soak time** — deliberate wait at a traffic step to let slow failures surface.
- **Baseline (canary)** — a fresh deploy of the *old* version used as the comparison for the canary.
- **Blast radius** — how much is harmed by one bad release.
- **Blue-green** — two full environments; atomic flip between them.
- **Canary** — release to a small traffic slice first, then ramp.
- **Connection draining** — finishing in-flight requests before terminating an instance.
- **Contract (migration)** — final step removing the old schema shape.
- **DORA metrics** — deployment frequency, lead time, change-failure rate, MTTR.
- **Drain** — see connection draining.
- **EndpointSlice** — Kubernetes object listing the IPs backing a Service; the routing table.
- **Error budget** — `1 − SLO`; allowable failure share.
- **Expand-contract (parallel change)** — additive change, dual-write/backfill, then remove old.
- **Feature flag** — runtime toggle exposing behavior independently of deploy.
- **Forward compatible** — old code tolerates new data/requests (e.g. ignores unknown fields).
- **Golden signals** — latency, traffic, errors, saturation.
- **Graceful shutdown** — stop new work, finish in-flight, then exit.
- **HPA** — Horizontal Pod Autoscaler; scales replicas on metrics.
- **Idempotency** — same result whether an op runs once or many times.
- **Liveness/readiness/startup probe** — restart / route-gate / boot-protect health checks.
- **Mann-Whitney U** — non-parametric test comparing two distributions (used by Kayenta).
- **maxSurge / maxUnavailable** — rolling-update pacing knobs.
- **MTTR** — Mean Time To Recovery.
- **Progressive delivery** — gated, gradual exposure with auto-rollback.
- **preStop hook** — container hook run before SIGTERM.
- **Promotion** — increasing the new version's traffic share.
- **Recreate** — stop all old, start all new (downtime).
- **ReplicaSet** — Kubernetes object maintaining N identical pods.
- **Rolling update** — replace instances incrementally.
- **Rollback / roll-forward** — revert to prior / fix-and-ship-newer.
- **Shadow / dark launch / mirroring** — copy traffic to new version, discard its responses.
- **SLI / SLO** — measured indicator / target for it.
- **StatefulSet** — Kubernetes controller for stateful, identity-stable pods (ordered rolling).
- **Sticky routing** — consistently route a user to one version (hash by id/cookie).
- **Termination grace period** — SIGTERM→SIGKILL window (default 30s).
- **Traffic shaping / weighted routing** — sending a precise % to each version.
- **Version skew (N-1/N)** — old and new versions running simultaneously.
- **ZooKeeper** — distributed coordination/consistent KV store for leader election/config.

---

## 12. Cheat-sheet & self-test

**One-screen recap**

- Strategies, smallest→largest blast radius: **shadow (0) < canary (X%) < rolling (grows) < blue-green / recreate (all)**.
- **Recreate** = downtime. **Rolling** = K8s default, knobs `maxSurge`/`maxUnavailable` (both 25% default), needs probes + drain. **Blue-green** = 2× cost, instant flip & rollback. **Canary** = ramp + ACA, smallest radius. **Shadow** = mirror, discard, never on write paths.
- **Zero-downtime requires:** readiness probe + `preStop` drain + grace period (30s default) + `maxUnavailable: 0`.
- **Two ReplicaSets** drive a rolling update; readiness gates EndpointSlice membership.
- **ACA:** compare canary to a **cold baseline (fresh old version)**, over a **bake window**, on golden signals + business KPI; auto-promote/abort. Kayenta = Mann-Whitney → score 0–100.
- **DB = shared state no strategy swaps atomically.** Always **expand → migrate (dual-write + batched backfill) → contract**. Never rename in place; `CREATE INDEX CONCURRENTLY`; `lock_timeout`.
- **Rollback** if prior version good + schema permits (fastest); **roll-forward** for security/forward-only/shared-bug. Contract migrations break rollback.
- **Always label metrics/logs/traces with version.** No observability → no progressive delivery.
- **Controllers:** native Deployment (rolling), **Argo Rollouts** (canary/BG + analysis, replaces Deployment), **Flagger** (wraps Deployment), **Spinnaker/Kayenta** (multi-cloud + statistical ACA).
- **Availability:** 99.9% ≈ 43 min/mo down; 99.99% ≈ 4.3 min/mo.

**Self-test (no answers — recall actively)**
1. Sketch the exact pod lifecycle from "controller creates v2 pod" to "v1 pod SIGKILLed," naming every probe and hook in order.
2. You must add a NOT NULL column and a unique index to a 500M-row table while serving traffic. Write the expand-contract steps, including the specific DDL safety techniques.
3. Your canary auto-aborts every deploy at 5% even though staging is green. List four distinct causes and how you'd distinguish them with specific metrics/tools.
4. Design a rollout that is shadow → canary → full for a payment-service rewrite, and state exactly what makes the shadow phase safe.
5. Explain why blue-green's "instant rollback" can be a dangerous assumption, giving two concrete scenarios where it fails.
6. Given a 12-RPS internal service, argue whether canary or blue-green is more appropriate and justify with the signal-volume math.
7. Decide rollback vs roll-forward for: (a) a perf regression, (b) a security CVE fix that's slightly buggy, (c) a release after a contract migration. Justify each.

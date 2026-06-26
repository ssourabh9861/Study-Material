# Circuit Breakers

> **Concept area:** Resilience & Fault Tolerance
> **Subtopic:** Circuit Breakers
> **Reader profile:** Senior JVM/Java backend engineer who wants to master circuit breaking from first principles through deep internals — enough to design with it, operate and debug it in production, teach it, and answer any interview question.

---

## 1. Overview & where it fits

### What it is

A **circuit breaker** is a stateful wrapper around a remote (or otherwise fallible) call that *monitors how that call is going* and, when it goes badly enough, **stops making the call entirely for a while** — failing fast instead of waiting on something that is already broken. After a cool-down period it cautiously lets a few calls through to see if the dependency has recovered, and either reopens the floodgates or shuts them again.

The name is a direct analogy to the electrical device in your house's breaker panel. A physical circuit breaker detects a fault condition (a current surge that would melt your wiring or start a fire) and **trips**, physically interrupting the circuit. You don't get electricity until someone fixes the underlying problem and resets the breaker. The software pattern does the same for *call traffic*: when a downstream dependency is faulting, the breaker "trips" and short-circuits calls to it, protecting the *caller* (and the wider system) from being dragged down.

The pattern was popularized for software by **Michael Nygard** in his 2007 book *Release It!*, which is the canonical reference. Netflix's **Hystrix** library (2012) then made it ubiquitous in the Java microservices world.

### The problem it solves

In a distributed system you make calls across the network all the time: service A calls service B calls service C; everyone calls the database, the cache, a third-party payment gateway, an internal recommendation engine. Network calls have a property local calls don't: **they can hang**. A downed dependency rarely says "no" quickly; it says nothing at all, and your call sits there consuming a thread, a socket, and memory until a timeout fires (often *seconds* later).

The dangerous failure mode this creates is the **cascading failure** (a.k.a. *retry storm* / *failure amplification*):

1. Dependency B becomes slow or unavailable.
2. Every request in service A that needs B now blocks for the full timeout duration.
3. Those blocked requests **hold threads** from A's request-handling thread pool.
4. New requests arrive faster than slow ones drain, so the pool fills up.
5. A's thread pool is now **exhausted** — even requests that don't touch B can't get a thread.
6. A is now effectively down. A's *own* callers start timing out and exhausting *their* pools.
7. The failure propagates "up the call graph" and the whole system browns out — from one slow leaf dependency.

A circuit breaker breaks this chain at step 2–3. Once it observes that B is failing, it stops waiting on B and returns immediately (an error, or a fallback). Threads aren't held hostage. The blast radius is contained to "features that need B are degraded" instead of "the entire service is down."

### When you reach for it

Use a circuit breaker around any call where **all** of these are true:

- The call goes to a **separate failure domain** — another process, host, service, or network hop. (Wrapping a pure in-process function in a breaker is almost always pointless.)
- The call **can fail or hang** in ways you don't fully control (timeouts, 5xx, connection refused, dependency overload).
- **Failing fast is better than waiting** — i.e., a quick error or a degraded-but-useful fallback beats a thread blocked for 30 seconds.
- The dependency has **shared, exhaustible resources** behind it on your side (threads, connections) that a flood of slow calls would starve.

Classic targets: synchronous HTTP/gRPC calls to other microservices, calls to a flaky third-party API, database/cache access, message-broker publishes.

Do **not** reach for it when: the operation is local and fast; the failure is a *client* error (a 400/404 is the caller's fault and retrying/tripping won't help); or the call is part of a strict transaction where "fail fast with a fallback" would silently corrupt correctness.

### One-paragraph mental model

> A circuit breaker is a tiny finite-state machine sitting in front of a remote call. It counts outcomes over a **sliding window**. While healthy it's **CLOSED** and lets everything through. When the recent failure rate (or slow-call rate) crosses a **threshold**, it flips to **OPEN** and rejects calls instantly — without even attempting them — for a fixed **wait duration**. After that wait it goes **HALF-OPEN**, allowing a small, capped number of *trial* calls; if they mostly succeed it returns to CLOSED, and if they don't it snaps back to OPEN. The whole point is to *stop hammering something that's already down* and to *fail in milliseconds instead of seconds* so the failure doesn't cascade.

---

## 2. Foundations from first principles

Let's build the necessary vocabulary from zero. If you already know a term, skim; nothing here assumes prior knowledge of *this* pattern.

### 2.1 The setting: synchronous remote calls

A **synchronous call** is one where the caller waits for the result before continuing — `String r = service.call();` blocks the calling thread until `r` is ready or an error is thrown. The opposite is **asynchronous**, where you get a handle (a `Future`/`CompletableFuture`) immediately and the result arrives later.

A **remote call** crosses a process or machine boundary, usually over the network (TCP, HTTP, gRPC). The defining cost of remote calls is **latency variance**: a healthy call might take 5 ms, but a sick one can take as long as your timeout allows, which is often 1000× longer.

A **thread** is the unit of execution the OS schedules; in a typical synchronous Java server (Tomcat, Jetty) each in-flight request occupies one thread from a bounded **thread pool**. If all threads are stuck waiting on a slow dependency, the server can accept no new work. This is **thread-pool exhaustion** and it is *the* failure the circuit breaker exists to prevent.

> **Beginner aside — what is a timeout?** A timeout is a self-imposed deadline: "if I don't hear back in N milliseconds, give up and throw an error." Without timeouts, a hung dependency can block a thread *forever*. Timeouts are necessary but *not sufficient*: a 10-second timeout still ties up a thread for 10 seconds, and under load that's enough to exhaust your pool. The circuit breaker complements timeouts by *not even starting* calls it expects to fail.

### 2.2 Cascading failure, precisely

Picture three services in a chain: **Frontend → Orders → Inventory**. Each has, say, a 200-thread request pool and a 2-second timeout on its downstream call.

Inventory's database locks up. Inventory's calls to it start taking the full 2 s and failing. Orders calls Inventory; those calls now take 2 s each. Orders receives 150 req/s. At 2 s each, the number of concurrently blocked threads is roughly `rate × latency = 150 × 2 = 300` — but Orders only has 200 threads. Within seconds Orders' pool is full; it can't even serve `/health`. Frontend calls Orders, times out, exhausts *its* pool, and now the whole product is down — all because one database got slow.

The mathematical relationship is **Little's Law**: `L = λ × W`, where `L` is the number of concurrent in-flight requests, `λ` is arrival rate, and `W` is average time-in-system. When `W` blows up (slow dependency), `L` blows up proportionally, and once `L` exceeds your pool size you're exhausted. The circuit breaker keeps `W` small by failing fast (returning in ~microseconds while OPEN), which keeps `L` small.

### 2.3 Fail fast vs. fail slow

**Failing fast** means returning an error (or fallback) *immediately* when you have good reason to believe the call will fail. **Failing slow** means letting every doomed call run to its timeout. Fail-fast is strictly better for system stability because it doesn't hold resources. The circuit breaker is, at its core, a **fail-fast policy with hysteresis** — it remembers recent failures so it can fail fast on the *next* call without re-learning the lesson each time.

> **Beginner aside — hysteresis.** Hysteresis means the system's current state depends on its recent history, not just the present input. A thermostat uses it: it turns the heater on at 19°C and off at 21°C rather than toggling frantically at exactly 20°C. The circuit breaker's three states are exactly this — it doesn't decide per-call from scratch; it carries memory (the window of recent outcomes) so it stays tripped for a while and recovers gradually.

### 2.4 The three states (the core state machine)

Every circuit breaker is a **finite-state machine (FSM)** — a system that is always in exactly one of a fixed set of states and moves between them on defined events.

- **CLOSED** — *normal operation.* All calls pass through to the real dependency. The breaker records each outcome (success/failure/slow) into its sliding window. This is the "circuit is complete, current flows" state. (Counter-intuitive at first: *closed* = healthy = traffic flows, just like a closed electrical switch.)

- **OPEN** — *tripped.* The breaker has decided the dependency is unhealthy. It **rejects all calls immediately** without attempting them, throwing a fast exception (e.g. `CallNotPermittedException`) or invoking your fallback. It stays OPEN for a configured **wait duration**. ("Open circuit" = broken circuit = no current flows = no traffic passes.)

- **HALF-OPEN** — *probing / convalescing.* After the wait duration the breaker tentatively allows a **small, fixed number of trial calls** through to test the waters. If enough of them succeed, it concludes the dependency has recovered and transitions to CLOSED. If they fail, it returns to OPEN and the wait duration starts over. HALF-OPEN exists to avoid two bad extremes: instantly trusting the dependency again (which would re-trigger the cascade if it's still sick) and never trusting it again (which would leave the feature dead forever).

Two optional administrative states most modern libraries add:

- **DISABLED** — the breaker is forced to always pass calls through (no metrics-based tripping). Used to bypass the breaker.
- **FORCED_OPEN** — the breaker is forced to always reject. Used to manually take a dependency offline (e.g., during a known outage or maintenance).
- **METRICS_ONLY** (Resilience4j) — records outcomes and emits metrics but *never* trips; useful to observe what the breaker *would* do before enabling it.

### 2.5 Thresholds and windows — how it decides to trip

The breaker needs a rule for "the dependency is unhealthy enough to trip." That rule is computed over a **window** of recent calls.

- **Sliding window** — the set of most-recent outcomes the breaker considers. Two flavors:
  - **Count-based window:** the last *N* calls (e.g., last 100). A new call evicts the oldest.
  - **Time-based window:** all calls in the last *T* seconds (e.g., last 10 s), bucketed into sub-intervals.

- **Failure-rate threshold** — the percentage of calls in the window that failed, above which the breaker trips. E.g., "trip when ≥50% of the last 100 calls failed."

- **Slow-call rate threshold** — separately, the percentage of calls that *succeeded but took too long* (longer than a **slow-call duration threshold**). A dependency that returns correct answers but takes 8 seconds is functionally a failure for your latency budget; tripping on slowness catches the brownout *before* it becomes a full outage. (This is a key advance of Resilience4j over classic Hystrix, which keyed only on failures/timeouts.)

- **Minimum number of calls** — a floor on how many calls must be recorded in the window before the rates are even evaluated. Without it, the *very first* call failing would read as "100% failure rate" and trip the breaker — statistically meaningless. E.g., "don't evaluate until at least 10 calls are in the window."

- **Wait duration in open state** — how long to stay OPEN before going HALF-OPEN. Trade-off: too short and you re-probe a still-broken dependency too often (and risk re-tripping the cascade); too long and you leave the feature degraded longer than necessary after it recovers.

- **Permitted number of calls in half-open** — the cap on trial calls. The breaker computes the failure/slow rate over *just these* trial calls to decide CLOSED vs OPEN.

### 2.6 Fallbacks and graceful degradation

A **fallback** is the alternative behavior you run when the call is short-circuited (or fails). It's how you turn a hard failure into **graceful degradation** — the system does *something* useful instead of erroring out. Examples: return a cached/stale value, return a sensible default (empty recommendation list, "service temporarily unavailable" instead of a stack trace), queue the request for later, or call a secondary provider. Note: a circuit breaker doesn't *require* a fallback — without one it simply throws fast — but fallbacks are what make breaking *user-friendly* rather than just *system-friendly*.

### 2.7 The neighbors: bulkhead, timeout, retry, rate limiter

The circuit breaker is one of a family of resilience patterns that are almost always used *together*. Brief definitions now; we combine them properly in §6–7.

- **Timeout** — a deadline on a single call. (Covered above.) Pairs with the breaker because *slow calls must be detectable*; an infinite-timeout call can't be counted as "slow" and can't be aborted.

- **Retry** — automatically re-attempt a failed call, usually with **exponential backoff** (wait 100 ms, then 200, 400…) and **jitter** (randomize the wait so all clients don't retry in lockstep — a "thundering herd"). Retries help with *transient* blips but *amplify* load against a struggling dependency, so they must be bounded and breaker-aware.

- **Bulkhead** — isolate resources so one failing dependency can't consume all of them. Named after a ship's watertight compartments: if one floods, the others keep the ship afloat. In software, you give each dependency its *own* thread pool or its *own* limited concurrency budget, so a slow dependency can only exhaust *its* bulkhead, not the whole service.

- **Rate limiter** — cap the *rate* of calls (e.g., 100/s) regardless of health, to protect a dependency from being overwhelmed or to respect a quota.

The clean mental separation: **timeout** bounds *one call's duration*; **bulkhead** bounds *concurrency*; **rate limiter** bounds *throughput*; **retry** handles *transient errors*; **circuit breaker** handles *sustained errors by stopping calls entirely*.

---

## 3. How it works internally

This is the heart of the document. We'll trace the full lifecycle, the data structures, the control flow, and the exact state-transition logic — using Resilience4j's design as the concrete reference (it's the modern standard) and noting where Hystrix differs.

### 3.1 The decorated-call control flow

Resilience4j works by **decoration**: you wrap your real call (a `Supplier`, `Callable`, `CompletableFuture`, etc.) with breaker logic. The decorated call, when invoked, runs this control flow:

```
caller invokes decorated call
        │
        ▼
[1] circuitBreaker.tryAcquirePermission()  ──► permission denied?
        │ (granted)                                  │ yes
        ▼                                            ▼
[2] record start time (nanoTime)             throw CallNotPermittedException
        │                                    (→ fallback runs, if any)
        ▼
[3] invoke the real supplier ── throws? ──► [5b] onError(duration, throwable)
        │ returns normally                          │
        ▼                                           ▼
[4] measure duration                         update window with FAILURE
        │                                    re-evaluate state, maybe trip
        ▼
[5a] onSuccess(duration)
        │
        ▼
   was it slower than slowCallDurationThreshold?
        │ yes → record SUCCESS but flag SLOW
        │ no  → record SUCCESS
        ▼
   update window, re-evaluate state
        │
        ▼
   return result to caller
```

Key points that surprise newcomers:

- The breaker's decision to *permit* a call (step 1) is separate from *recording its outcome* (steps 5a/5b). Permission is the gate; recording is the feedback.
- When OPEN, the gate (`tryAcquirePermission`) returns false **without running your code at all** — that's the "fail fast" — and it's *cheap* (a few atomic reads/CAS operations, sub-microsecond).
- A "success" can still be recorded as a *slow* call; success ≠ healthy if it was slow.

### 3.2 The sliding window data structures (Resilience4j internals)

Resilience4j stores outcomes in one of two window implementations. Understanding these explains the timing and memory characteristics.

**Count-based window** (`FixedSizeSlidingWindowMetrics`):
- A **ring buffer** (circular array) of size `N` (e.g., 100). Each slot holds one **Measurement** (an outcome: success, slow-success, failure, slow-failure, plus its duration).
- An aggregate `TotalAggregation` keeps running totals (total calls, failed calls, slow calls, total duration) so rate computation is O(1), not O(N).
- When call N+1 arrives, it overwrites the oldest slot; the aggregate is updated by *subtracting* the evicted measurement and *adding* the new one. So memory is fixed at `N` measurements and updates are constant-time.

**Time-based window** (`SlidingTimeWindowMetrics`):
- A ring buffer of `T` **partial aggregations**, one per second (so size = window duration in seconds, e.g., 10 buckets for a 10 s window).
- Each second's bucket aggregates all calls in that second. A `total` aggregation spans all buckets.
- Every second, the head advances; the bucket being reused is *reset* and its contribution subtracted from the total. So you always reflect "the last T seconds," and memory is `O(T)` buckets, **independent of call volume** — a high-throughput service doesn't blow up memory.

> **Beginner aside — ring buffer.** A ring buffer is a fixed-size array used as if its ends were joined into a circle. A write index advances and wraps around to 0 when it passes the end, so the oldest data is naturally overwritten by the newest. It gives O(1) inserts and bounded memory — perfect for "last N items."

> **Beginner aside — CAS / atomic.** "CAS" is *compare-and-swap*, a CPU instruction that atomically checks a memory location equals an expected value and, if so, sets it to a new value — all in one uninterruptible step. It's how lock-free concurrent data structures avoid mutexes. Resilience4j uses atomics/CAS for its state and metrics so the breaker is fast and thread-safe under high concurrency without coarse locking.

### 3.3 The state machine, transition by transition

Resilience4j models the breaker as a state object (`CircuitBreakerStateMachine`) holding a reference to the current state (CLOSED/OPEN/HALF_OPEN/etc.). Each state knows how to handle `tryAcquirePermission`, `onSuccess`, `onError`. Here is the exact logic.

**CLOSED state:**
- `tryAcquirePermission()` → always **true** (all calls allowed).
- On each recorded outcome, the metrics are updated. *If* the number of recorded calls ≥ `minimumNumberOfCalls`, the breaker computes `failureRate` and `slowCallRate`.
- **Trip condition:** if `failureRate ≥ failureRateThreshold` **OR** `slowCallRate ≥ slowCallRateThreshold`, transition **CLOSED → OPEN**. On entering OPEN it records the current time and starts the wait timer.
- If thresholds aren't met, stay CLOSED.

**OPEN state:**
- `tryAcquirePermission()` → checks whether the `waitDurationInOpenState` has elapsed since entering OPEN.
  - Not yet elapsed → return **false** (reject; throw `CallNotPermittedException`). It also increments a "not permitted" counter for metrics.
  - Elapsed → transition **OPEN → HALF_OPEN** and allow this call as one of the trial calls.
- `onSuccess`/`onError` while OPEN: outcomes from calls that were already in flight when it tripped may still arrive; they're recorded but don't change the state (the state is time-driven in OPEN).
- *Automatic vs. on-demand transition:* by default the OPEN→HALF_OPEN move happens **lazily**, on the first call after the wait elapses (no background thread). You can enable `automaticTransitionFromOpenToHalfOpenEnabled=true` to use a scheduler thread that flips it proactively even with zero traffic — useful if you want metrics/events to reflect HALF_OPEN promptly, at the cost of a thread.

**HALF_OPEN state:**
- On entry, it resets metrics to a fresh window sized to `permittedNumberOfCallsInHalfOpenState`.
- `tryAcquirePermission()` → allows up to `permittedNumberOfCallsInHalfOpenState` concurrent trial calls; beyond that, returns **false** (reject extra calls so you don't flood a possibly-still-sick dependency).
- As trial outcomes come in, once `permittedNumberOfCalls` results are recorded:
  - Compute failure/slow rates over just those trials.
  - If rates are **below** thresholds → **HALF_OPEN → CLOSED** (recovered; reset to full window).
  - If rates are **at/above** thresholds → **HALF_OPEN → OPEN** (still sick; restart wait timer).

**Forced/disabled states:**
- **DISABLED:** `tryAcquirePermission` always true; outcomes are *not* recorded; never trips. (Bypass.)
- **FORCED_OPEN:** `tryAcquirePermission` always false; outcomes not recorded; never auto-transitions. (Manual kill-switch.)
- **METRICS_ONLY:** records outcomes and emits metrics/events but never changes operational state away from "calls allowed." Great for shadow/observe mode.

A compact transition table:

| From | Event / condition | To |
|---|---|---|
| CLOSED | failureRate ≥ threshold OR slowCallRate ≥ threshold (and ≥ minCalls) | OPEN |
| CLOSED | rates below thresholds | CLOSED (stay) |
| OPEN | waitDuration elapsed, next call arrives (or scheduler fires) | HALF_OPEN |
| OPEN | waitDuration not yet elapsed | OPEN (reject calls) |
| HALF_OPEN | trial calls completed, rates below thresholds | CLOSED |
| HALF_OPEN | trial calls completed, rates at/above thresholds | OPEN |
| any | manual transitionToDisabled() | DISABLED |
| any | manual transitionToForcedOpen() | FORCED_OPEN |
| DISABLED/FORCED_OPEN | manual transitionToClosedState() etc. | CLOSED (etc.) |

### 3.4 Concurrency model — what happens under load

Many threads hit one breaker simultaneously. How is this safe and fast?

- **State** is held in an `AtomicReference` to the current state object. Transitions use CAS to swap state objects, so two threads racing to "trip" the breaker don't double-fire or corrupt state — one wins the CAS, the other sees the new state.
- **Metrics aggregation** uses atomic accumulation (and, in the time-based window, per-bucket atomics). The hot path (`onSuccess`/`onError`) is lock-free.
- **Permission check** in OPEN compares the current time against the stored "open until" nanotime — a cheap read.
- In HALF_OPEN, the count of permitted in-flight trials is an `AtomicInteger` decremented via CAS; once exhausted, further `tryAcquirePermission` calls fail fast.

The practical upshot: the breaker adds **negligible overhead** on the happy path — typically a handful of nanoseconds to low microseconds per call — far cheaper than the network call it guards.

### 3.5 How Hystrix did it (and why it's different)

Hystrix's model is worth knowing because tons of legacy code uses it and interviewers ask about it.

- Each protected call is a **`HystrixCommand`** (or `HystrixObservableCommand`). You subclass it and implement `run()` (the real call) and optionally `getFallback()`.
- **Thread isolation by default:** Hystrix ran each command in its own **per-dependency thread pool** (a built-in bulkhead). The calling thread submitted the work and waited with a timeout; if it timed out, the calling thread was freed even though the worker thread might still be stuck. This made Hystrix a combined *bulkhead + timeout + breaker* in one — powerful but heavier (thread context-switch cost, extra threads). A lighter **semaphore isolation** mode used a counting semaphore instead of a separate pool (no extra threads, but it couldn't interrupt a hung call).
- **Health snapshot:** Hystrix computed metrics over a rolling 10-second window (default), split into 10 one-second buckets, and tripped based on **error percentage** (`circuitBreaker.errorThresholdPercentage`, default **50%**) once volume exceeded `circuitBreaker.requestVolumeThreshold` (default **20** requests in the window).
- **Sleep window:** after tripping, it waited `circuitBreaker.sleepWindowInMilliseconds` (default **5000 ms**) before allowing a *single* trial request (its HALF_OPEN was effectively one probe, not a configurable count).
- Hystrix tripped on **failures and timeouts** but had **no first-class slow-call-rate** concept the way Resilience4j does (a slow call only mattered if it crossed the command timeout).

Netflix put Hystrix into **maintenance mode in late 2018** (no new features), explicitly steering users toward **Resilience4j** and adaptive, real-time approaches (e.g., Netflix's internal **concurrency-limits**/adaptive load shedding). So: Hystrix = battle-tested but frozen; Resilience4j = the modern Java standard.

---

## 4. The complete toolkit

This section enumerates the configuration surface, the APIs, and the tools. Resilience4j is the primary reference; Hystrix and Sentinel get their own tables.

### 4.1 Resilience4j `CircuitBreakerConfig` — every property

These are the knobs on `io.github.resilience4j.circuitbreaker.CircuitBreakerConfig`. Defaults are for **Resilience4j 1.7.x/2.x**; flag version sensitivity in production.

| Property | Meaning | Default |
|---|---|---|
| `failureRateThreshold` | % of failed calls in the window that trips the breaker (CLOSED→OPEN). | **50** (%) |
| `slowCallRateThreshold` | % of *slow* calls in the window that trips the breaker. | **100** (%) — effectively off until you lower it |
| `slowCallDurationThreshold` | A call slower than this counts as "slow" (even if successful). | **60s** (`Duration.ofSeconds(60)`) |
| `slidingWindowType` | `COUNT_BASED` (last N calls) or `TIME_BASED` (last N seconds). | **COUNT_BASED** |
| `slidingWindowSize` | N: number of calls (count-based) or seconds (time-based) the window spans. | **100** |
| `minimumNumberOfCalls` | Minimum recorded calls before rates are evaluated. | **100** |
| `waitDurationInOpenState` | How long to stay OPEN before allowing HALF_OPEN probes. | **60s** |
| `permittedNumberOfCallsInHalfOpenState` | Number of trial calls allowed in HALF_OPEN. | **10** |
| `automaticTransitionFromOpenToHalfOpenEnabled` | Use a scheduler to move OPEN→HALF_OPEN without waiting for a call. | **false** |
| `maxWaitDurationInHalfOpenState` | Cap on time in HALF_OPEN before forcing a decision (0 = wait indefinitely for the permitted calls). | **0** (disabled) |
| `recordExceptions` | Exception types that count as failures. | empty = all exceptions are failures (unless ignored) |
| `ignoreExceptions` | Exception types that are *neither* success nor failure (not recorded at all). | empty |
| `recordException` (predicate) | A `Predicate<Throwable>` to decide failure programmatically (e.g., HTTP 5xx = failure, 4xx = ignore). | none |
| `ignoreException` (predicate) | Predicate to ignore certain throwables. | none |
| `recordResult` / `transitionOnResult` (a.k.a. `recordResult` via `Predicate<Object>` in newer versions) | Treat a *returned value* (not exception) as a failure, e.g., an HTTP `Response` with status 503. | none |
| `writableStackTraceEnabled` | Whether `CallNotPermittedException` includes a full stack trace (cheaper without). | **true** |
| `enableExponentialBackoff` / `enableRandomizedWait` (via `IntervalBiFunction`) | Make `waitDurationInOpenState` grow on repeated trips (back off harder if it keeps failing). | off (fixed wait) |

> **Key correctness gotcha:** with the defaults `slidingWindowSize=100` *and* `minimumNumberOfCalls=100`, the breaker won't trip until it has seen 100 calls — and for a low-traffic endpoint that may take a long time, during which it fails slow on every call. Tune both for your traffic. Also note `minimumNumberOfCalls` is compared against calls *in the window*; for time-based windows it's calls within the last `slidingWindowSize` seconds.

### 4.2 Resilience4j core API surface

| Class / method | Purpose |
|---|---|
| `CircuitBreakerRegistry.of(config)` | Creates a registry — a thread-safe store of named breakers sharing default config. Central place to manage many breakers. |
| `registry.circuitBreaker("name")` | Get-or-create a named breaker. Names show up in metrics; one breaker per dependency. |
| `CircuitBreaker.of(name, config)` | Create a standalone breaker without a registry. |
| `CircuitBreaker.decorateSupplier(cb, supplier)` | Wrap a `Supplier<T>` so calling it goes through the breaker. Also `decorateCallable`, `decorateRunnable`, `decorateFunction`, `decorateCompletionStage`, `decorateCheckedSupplier`. |
| `cb.executeSupplier(supplier)` | Execute immediately through the breaker (no separate decorate step). |
| `cb.tryAcquirePermission()` / `cb.releasePermission()` | Low-level manual gating (acquire before call; release if you didn't actually call). |
| `cb.onSuccess(nanos, timeUnit)` / `cb.onError(nanos, unit, throwable)` | Low-level manual outcome recording (for custom integrations). |
| `cb.getState()` | Current `State` enum. |
| `cb.getMetrics()` | Snapshot: `getFailureRate()`, `getSlowCallRate()`, `getNumberOfBufferedCalls()`, `getNumberOfFailedCalls()`, `getNumberOfSlowCalls()`, `getNumberOfNotPermittedCalls()`. |
| `cb.transitionToOpenState()` / `...HalfOpenState()` / `...ClosedState()` / `...DisabledState()` / `...ForcedOpenState()` / `...MetricsOnlyState()` | Manual/admin transitions. |
| `cb.getEventPublisher()` | Subscribe to events: `onStateTransition`, `onCallNotPermitted`, `onError`, `onSuccess`, `onSlowCallRateExceeded`, `onFailureRateExceeded`, `onIgnoredError`, `onReset`. |
| `Decorators.ofSupplier(...)` | Fluent builder to *compose* breaker + retry + bulkhead + rate limiter + timelimiter + fallback in the right order. |

### 4.3 Spring Boot / Spring Cloud integration

| Tool | Purpose |
|---|---|
| `@CircuitBreaker(name="...", fallbackMethod="...")` | Annotation (from `resilience4j-spring-boot2/3`) to wrap a bean method. Fallback method must share the signature plus a trailing `Throwable`/`Exception` param. |
| `application.yml` under `resilience4j.circuitbreaker.instances.<name>.*` | Declarative config per breaker; plus `configs.default.*` for shared defaults. |
| `@Retry`, `@Bulkhead`, `@TimeLimiter`, `@RateLimiter` | Companion annotations; **aspect order matters** (see §6.5). |
| `CircuitBreakerFactory` / `ReactiveCircuitBreakerFactory` (Spring Cloud CircuitBreaker) | Vendor-neutral abstraction so you can swap Resilience4j/Sentinel/Hystrix under one API; used by Spring Cloud Gateway, Feign, etc. |
| Spring Cloud OpenFeign `@FeignClient(fallback=...)` | Declarative HTTP client with breaker + fallback wiring. |
| Actuator endpoints `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents`, `/actuator/health` | Inspect state, recent events, and health contribution at runtime. |
| Micrometer metrics (auto-bound) | Exposes `resilience4j_circuitbreaker_*` metrics to Prometheus/etc. (see §6.6). |

### 4.4 Hystrix configuration (legacy, for reference)

| Property | Meaning | Default |
|---|---|---|
| `circuitBreaker.requestVolumeThreshold` | Min requests in rolling window before tripping is possible. | **20** |
| `circuitBreaker.errorThresholdPercentage` | Error % to trip. | **50** |
| `circuitBreaker.sleepWindowInMilliseconds` | Wait before a HALF_OPEN trial. | **5000** |
| `metrics.rollingStats.timeInMilliseconds` | Rolling window length. | **10000** |
| `metrics.rollingStats.numBuckets` | Buckets in the window. | **10** |
| `execution.isolation.strategy` | `THREAD` or `SEMAPHORE`. | **THREAD** |
| `execution.isolation.thread.timeoutInMilliseconds` | Per-command timeout. | **1000** |
| `coreSize` (thread pool) | Threads per dependency bulkhead. | **10** |
| `execution.isolation.semaphore.maxConcurrentRequests` | Concurrency cap in semaphore mode. | **10** |

### 4.5 Alibaba Sentinel (the third major option)

Sentinel is a flow-control/resilience library from Alibaba, strong in the cloud-native and Spring Cloud Alibaba ecosystem. Its "circuit breaking" is rule-driven (`DegradeRule`).

| Concept / property | Meaning |
|---|---|
| `DegradeRule.grade` | Strategy: `RT` (slow request ratio — average response time based), `EXCEPTION_RATIO` (exception ratio), or `EXCEPTION_COUNT` (absolute exception count). |
| `count` | Threshold value (RT ms, ratio 0–1, or count) depending on grade. |
| `timeWindow` | Seconds to stay broken before half-open probe. |
| `minRequestAmount` | Minimum requests before evaluation (like `minimumNumberOfCalls`). |
| `statIntervalMs` | Statistics window length. |
| `slowRatioThreshold` | For RT grade, the ratio of slow calls that trips. |
| `SphU.entry("resource")` / `@SentinelResource` | Define a protected resource (programmatic or annotation). |
| Dashboard | Sentinel ships a web dashboard for live rules, metrics, and manual control — a notable operational advantage. |

Sentinel uniquely unifies **flow control (rate limiting), circuit breaking, system-adaptive protection (load shedding based on system load/CPU), and hotspot parameter limiting** under one model, with dynamic rule sources (Nacos, ZooKeeper, etc.).

> **Beginner aside — Nacos / ZooKeeper.** Both are **distributed configuration / service-discovery** systems. ZooKeeper (Apache) is a consistent coordination service often used for config, leader election, and locks. Nacos (Alibaba) is a config + service-discovery server popular in Spring Cloud Alibaba. Sentinel can pull its rules from them so you can change circuit-breaker thresholds across a fleet without redeploying.

---

## 5. Code examples by use case

All examples target **Resilience4j 2.x** unless noted. Maven coordinates: `io.github.resilience4j:resilience4j-circuitbreaker` (core), `:resilience4j-spring-boot3` (Spring), `:resilience4j-all` (everything). They are written to be copy-adaptable.

### 5.1 Programmatic core — guarding an HTTP call with a fallback

```java
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.decorators.Decorators;
import java.time.Duration;
import java.util.function.Supplier;

public class InventoryClient {

    private final CircuitBreaker breaker;
    private final HttpClient http; // your real client

    public InventoryClient(HttpClient http) {
        this.http = http;

        // Tuned for a MEDIUM-traffic endpoint (not the verbose defaults).
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(10)                 // last 10 seconds
            .minimumNumberOfCalls(20)              // need 20 calls before judging
            .failureRateThreshold(50)              // trip at 50% failures
            .slowCallRateThreshold(80)             // trip at 80% slow calls...
            .slowCallDurationThreshold(Duration.ofMillis(800)) // ...where "slow" = >800ms
            .waitDurationInOpenState(Duration.ofSeconds(5))    // stay OPEN 5s
            .permittedNumberOfCallsInHalfOpenState(3)          // 3 probes
            // Treat IllegalArgumentException as a CLIENT bug, not a dependency failure:
            .ignoreExceptions(IllegalArgumentException.class)
            .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.breaker = registry.circuitBreaker("inventory-service");

        // Observability: log every state change.
        breaker.getEventPublisher()
               .onStateTransition(e -> System.out.println("[CB] " + e));
    }

    public InventoryResult getStock(String sku) {
        Supplier<InventoryResult> call =
            () -> http.getStock(sku); // the real, fallible remote call

        // Decorate the call with the breaker, then attach a fallback.
        Supplier<InventoryResult> guarded = Decorators.ofSupplier(call)
            .withCircuitBreaker(breaker)
            .withFallback(
                // Fallback runs for CallNotPermittedException AND real failures
                // matched here. Keep the fallback CHEAP and LOCAL.
                throwable -> InventoryResult.unknown(sku))
            .decorate();

        return guarded.get();
    }
}
```

What matters here: the **time-based window** suits a busy endpoint; **`ignoreExceptions`** prevents *client* errors from tripping the breaker (a critical correctness point — you only want to trip on *dependency* faults); the **fallback returns a degraded-but-valid value** so the caller's UI can still render ("stock unknown") rather than 500.

### 5.2 Spring Boot declarative — `@CircuitBreaker` + fallback

`application.yml`:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:                     # shared defaults all instances inherit
        sliding-window-type: COUNT_BASED
        sliding-window-size: 50
        minimum-number-of-calls: 20
        failure-rate-threshold: 50
        slow-call-rate-threshold: 90
        slow-call-duration-threshold: 1s
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 5
        automatic-transition-from-open-to-half-open-enabled: true
        register-health-indicator: true   # contributes to /actuator/health
    instances:
      paymentGateway:              # one named instance, overrides as needed
        base-config: default
        wait-duration-in-open-state: 30s   # payments: back off harder
        slow-call-duration-threshold: 2s
        ignore-exceptions:
          - com.example.InvalidCardException   # client error, don't trip
```

Service:

```java
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentGatewayClient client;

    public PaymentService(PaymentGatewayClient client) {
        this.client = client;
    }

    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "chargeFallback")
    public PaymentReceipt charge(ChargeRequest req) {
        return client.charge(req); // remote call to the gateway
    }

    // Fallback signature == original args + a trailing Throwable.
    // You can overload to handle different exception types differently.
    private PaymentReceipt chargeFallback(ChargeRequest req, CallNotPermittedException ex) {
        // Breaker is OPEN: don't even try. Queue for async retry instead.
        paymentQueue.enqueue(req);
        return PaymentReceipt.pending(req.id(), "Gateway unavailable; will retry");
    }

    private PaymentReceipt chargeFallback(ChargeRequest req, Throwable t) {
        // Real failure while CLOSED/HALF_OPEN: surface a controlled error.
        return PaymentReceipt.failed(req.id(), "Payment failed: " + t.getMessage());
    }
}
```

Note the **two fallback overloads**: one for `CallNotPermittedException` (breaker OPEN — we *enqueue* rather than fail the user) and a general `Throwable` one. Resilience4j picks the most specific matching signature. This is the idiomatic way to branch behavior on "breaker tripped" vs "call actually failed."

### 5.3 Combining breaker + retry + bulkhead + time limiter (the full stack)

This is the production-grade composition. Order matters; see §6.5 for *why* this order.

```java
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import io.github.resilience4j.timelimiter.*;
import io.github.resilience4j.decorators.Decorators;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class RecommendationClient {

    private final CircuitBreaker cb;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2);

    public RecommendationClient() {
        cb = CircuitBreaker.ofDefaults("recs");

        retry = Retry.of("recs", RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                Duration.ofMillis(100), 2.0))   // 100ms, ~200ms, ~400ms + jitter
            .retryExceptions(TimeoutException.class, IOException.class)
            .build());

        bulkhead = Bulkhead.of("recs", BulkheadConfig.custom()
            .maxConcurrentCalls(25)             // cap concurrency to this dependency
            .maxWaitDuration(Duration.ZERO)     // fail fast if bulkhead is full
            .build());

        timeLimiter = TimeLimiter.of("recs", TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(500))
            .cancelRunningFuture(true)          // interrupt the slow call
            .build());
    }

    public CompletableFuture<List<Item>> recommend(String userId) {
        Supplier<CompletableFuture<List<Item>>> futureSupplier =
            () -> CompletableFuture.supplyAsync(() -> realRecsCall(userId));

        // Decoration order (outer→inner as written, executed inner→outer):
        // Retry( CircuitBreaker( TimeLimiter( Bulkhead( call ) ) ) )
        return Decorators.ofCompletionStage(futureSupplier)
            .withBulkhead(bulkhead)
            .withTimeLimiter(timeLimiter, scheduler)
            .withCircuitBreaker(cb)
            .withRetry(retry, scheduler)
            .withFallback(asList(TimeoutException.class,
                                 CallNotPermittedException.class,
                                 BulkheadFullException.class),
                          t -> Collections.<Item>emptyList()) // degrade to no recs
            .get();
    }

    private List<Item> realRecsCall(String userId) { /* ... */ }
}
```

The composition guarantees: each attempt is **bounded in time** (TimeLimiter), **bounded in concurrency** (Bulkhead), **counted by the breaker** (so persistent timeouts trip it), and **retried with backoff** for transient blips — but only while the breaker is CLOSED/HALF_OPEN, because the breaker is *inside* the retry, so a retry against an OPEN breaker fails instantly with `CallNotPermittedException` rather than re-hammering.

### 5.4 Result-based tripping — treating HTTP 503 as a failure without throwing

Sometimes the client returns a `Response` object instead of throwing on a 5xx. You still want those to count as failures.

```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)
    // Treat any returned Response with a 5xx status as a FAILURE outcome:
    .recordResult(result -> {
        if (result instanceof HttpResponse resp) {
            return resp.statusCode() >= 500;   // true => count as failure
        }
        return false;
    })
    .build();
```

This closes a common blind spot: a breaker that only watches *exceptions* will happily stay CLOSED while a dependency returns thousands of 503s, because none of them threw.

### 5.5 Manual gating for a non-Supplier integration (streaming / custom protocol)

When you can't easily wrap the call in a `Supplier` (e.g., a long-lived stream or a callback API), use the low-level permission API:

```java
CircuitBreaker cb = registry.circuitBreaker("stream-svc");

public void consume() {
    if (!cb.tryAcquirePermission()) {
        // Breaker OPEN — skip and degrade.
        useCachedStream();
        return;
    }
    long start = System.nanoTime();
    try {
        openStreamAndProcess();                 // your custom call
        cb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    } catch (Exception e) {
        cb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
        throw e;
    }
}
```

The contract: call `tryAcquirePermission()` *exactly once* per attempt; if you got permission but decide not to call, invoke `releasePermission()` so HALF_OPEN trial slots aren't leaked.

### 5.6 Observing without enforcing — METRICS_ONLY shadow mode

Before turning a breaker on in a critical path, run it in observe mode to see what it *would* do:

```java
CircuitBreaker cb = registry.circuitBreaker("risky-dep");
cb.transitionToMetricsOnlyState();   // records + emits metrics, never trips

cb.getEventPublisher()
  .onFailureRateExceeded(e ->
      log.warn("Would TRIP: failureRate={}%", e.getFailureRate()))
  .onSlowCallRateExceeded(e ->
      log.warn("Would TRIP (slow): slowRate={}%", e.getSlowCallRate()));
```

You get the alarms and dashboards without the behavior change — a safe way to validate thresholds against real traffic, then flip to enforcing.

### 5.7 Hystrix (legacy) — for maintaining old code

```java
public class GetUserCommand extends HystrixCommand<User> {
    private final String userId;
    private final UserClient client;

    public GetUserCommand(String userId, UserClient client) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserGroup"))
            .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                .withCircuitBreakerErrorThresholdPercentage(50)
                .withCircuitBreakerRequestVolumeThreshold(20)
                .withCircuitBreakerSleepWindowInMilliseconds(5000)
                .withExecutionTimeoutInMilliseconds(1000)));
        this.userId = userId; this.client = client;
    }

    @Override protected User run() { return client.getUser(userId); } // real call
    @Override protected User getFallback() { return User.anonymous(); } // degrade
}
// Usage: User u = new GetUserCommand(id, client).execute();
```

Recognize this shape in old repos; the migration target is one of the Resilience4j forms above.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Hot-path cost is tiny.** A Resilience4j permission check + outcome record is a handful of atomic operations — nanoseconds to low microseconds. Compared to a network call (hundreds of microseconds to seconds), it's free. The *win* dwarfs the cost: while OPEN, you replace a multi-second timeout with a sub-microsecond rejection.
- **Window memory:** count-based is `O(slidingWindowSize)` measurements; time-based is `O(seconds)` buckets regardless of QPS. Prefer **time-based for high/variable throughput** so memory and "what counts as recent" don't depend on call rate.
- **Avoid `automaticTransition...=true` unless you need it** — it spins up a scheduler thread per registry to flip OPEN→HALF_OPEN proactively. For most services, lazy transition (on next call) is fine and cheaper.
- **Stack traces:** set `writableStackTraceEnabled(false)` on hot breakers so `CallNotPermittedException` doesn't pay the cost of filling in a stack trace on every rejected call during an outage (when rejections can be very frequent).

### 6.2 Correctness & concurrency

- **One breaker per dependency, not per call site.** All call sites that hit the same downstream should share the same named breaker so its window sees the full signal. Two breakers for the same dependency split the signal and trip late.
- **But scope to the right granularity.** If one downstream service has independent endpoints with very different reliability (e.g., a fast `/quote` and a flaky `/report`), give them *separate* breakers so a sick endpoint doesn't trip the healthy one — and vice versa. Granularity is a judgment call: per-service is the default; per-endpoint when failure isolation matters.
- **Classify exceptions correctly.** *Server-side/dependency* failures (5xx, timeouts, connection refused) should count; *client-side* errors (4xx, validation) should be **ignored** — they're not the dependency's fault and tripping won't help. Use `recordExceptions`/`ignoreExceptions`/predicates.
- **Thread-safety is built in**, but *your fallback* must be thread-safe and side-effect-careful — it can run concurrently for many requests during an outage.

### 6.3 Memory & resource safety

- Breakers themselves are light. The real resource concern is the **bulkhead** you pair with them: an unbounded thread pool or unbounded queue defeats the purpose. Bound concurrency and prefer `maxWaitDuration=0` (fail fast when full) over deep queues that just move the latency problem.
- Beware **fallbacks that themselves do remote work** (e.g., fallback calls another service). That can introduce a *second* failure domain inside your degradation path; if you must, wrap the fallback's call in *its own* breaker.

### 6.4 Security

- A tripped breaker shouldn't leak internals: ensure the fallback / error response doesn't expose stack traces, internal hostnames, or dependency details to end users.
- Consider whether short-circuiting can be **abused**: an attacker who can make a dependency fail (e.g., by sending malformed inputs that error) could *trip your breaker on purpose* to deny the feature to everyone. Mitigation: ignore client-error exceptions (they shouldn't count), and rate-limit/validate inputs upstream.
- **Admin transitions** (`transitionToForcedOpen`, exposed via Actuator) are powerful operational levers — protect those endpoints with auth.

### 6.5 Composition order (the single most common source of subtle bugs)

When stacking patterns, order changes behavior. Resilience4j's recommended order, from **outermost to innermost**:

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( call ) ) ) ) )
```

Reasoning:
- **Retry outermost:** you want to retry the *whole* protected operation. With Retry outside the breaker, once the breaker is OPEN, the retry's attempts hit a fast `CallNotPermittedException` and stop quickly — retries don't hammer a dead dependency. (If you put Retry *inside* the breaker, each retry would be counted by the breaker, inflating failure counts and tripping faster — sometimes desired, but usually not.)
- **CircuitBreaker above TimeLimiter/Bulkhead:** so that **timeouts and bulkhead rejections are visible to the breaker** as failures — persistent timeouts *should* trip it. If the breaker were innermost, it wouldn't see the timeout (the TimeLimiter would have already aborted).
- **Bulkhead innermost (closest to the call):** it directly limits concurrency on the actual call.

For Spring annotations, control order with the `*.aspect.order` properties (e.g., `resilience4j.retry.retryAspectOrder`); higher order = outer. The default aspect order is **Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead** (Retry outermost). Verify for your version.

### 6.6 Observability (non-negotiable for production)

A breaker you can't see is a liability. Emit and alert on:

| Metric (Micrometer name) | Why it matters |
|---|---|
| `resilience4j_circuitbreaker_state` (gauge per state) | Is it OPEN right now? Alert on OPEN. |
| `resilience4j_circuitbreaker_calls` (tagged `kind`=successful/failed/ignored) | Volume and outcome mix. |
| `resilience4j_circuitbreaker_failure_rate` | How close to the threshold. |
| `resilience4j_circuitbreaker_slow_call_rate` | Catch brownouts before full trips. |
| `resilience4j_circuitbreaker_not_permitted_calls_total` | How many calls were short-circuited (blast radius of the trip). |
| `resilience4j_circuitbreaker_buffered_calls` | Window fill level (helps debug "why didn't it trip?"). |

Also:
- **Log every state transition** (subscribe to `onStateTransition`) with timestamps — the first thing you'll want during an incident is "when did it open?"
- **Wire breaker state into `/actuator/health`** carefully: an OPEN breaker meaning the service is *degraded but up* should usually be a `DEGRADED`/warning, **not** `DOWN` — otherwise your load balancer or k8s readiness probe yanks the pod for a *downstream* problem the pod is correctly surviving. (Use `register-health-indicator` thoughtfully and consider `allow-health-indicator-to-fail: false`.)
- **Dashboards:** a per-dependency panel of state-over-time + failure rate is the single most useful resilience dashboard you'll build.

### 6.7 Testing

- **Unit-test the state machine** by forcing transitions: configure tiny windows (`slidingWindowSize=5`, `minimumNumberOfCalls=5`), feed failures via `cb.onError(...)`, assert `cb.getState()` flips to OPEN; advance using a controllable clock or `transitionToHalfOpenState()`; assert recovery.
- **Inject failures** in integration tests: use a mock server (WireMock, MockWebServer) that returns 5xx / delays, and assert the breaker opens and the fallback fires.
- **Use a fake/controllable `Clock`** for `waitDurationInOpenState` so tests don't sleep real seconds. Resilience4j's config can take a custom `Clock` for the time-based window (version-dependent).
- **Chaos / game days:** in staging (or carefully in prod), kill or slow a real dependency and confirm the breaker contains the blast radius and the dashboards/alerts fire. This is the only way to know your *thresholds* are right, not just that the code compiles.

### 6.8 Anti-patterns to avoid

- **Tripping on client errors (4xx).** Validation failures shouldn't open the breaker; they're not dependency outages and the breaker can't fix them.
- **No fallback *and* no plan.** Throwing `CallNotPermittedException` to the user as a 500 is failing fast but not gracefully. Decide the degradation for each breaker.
- **Defaults left untuned for low-traffic endpoints.** `minimumNumberOfCalls=100` on an endpoint doing 2 req/min means the breaker essentially never engages during a short outage.
- **Sharing one giant breaker across unrelated dependencies.** A trip then blocks everything, including healthy dependencies.
- **Retry storms.** Retry *outside* a breaker without backoff/jitter, or retrying non-idempotent operations, turns a blip into a self-inflicted DDoS.
- **Wait duration far too short.** Re-probing every 200 ms during an outage can keep re-triggering the cascade and never lets the dependency recover.
- **Fallback that's slower/riskier than the primary.** A fallback that calls *another* remote service can be the thing that actually takes you down.
- **Breaker on a non-idempotent write with naive retry.** "Failed" might mean the write actually succeeded but the response was lost; retrying double-charges. Make writes idempotent (idempotency keys) before retrying.
- **Treating breaker OPEN as service DOWN in health checks**, causing orchestrators to kill healthy pods over a downstream issue.

---

## 7. Advanced topics & deep internals

### 7.1 Count-based vs time-based windows — the subtle behavioral difference

A **count-based** window of 100 calls means the relevant time span *shrinks as traffic rises*: at 1000 req/s, "the last 100 calls" covers only 100 ms — extremely reactive, maybe too twitchy. At 1 req/min it covers 100 minutes — far too sluggish. A **time-based** window of, say, 10 seconds always means "the last 10 seconds," so its sensitivity is **traffic-independent**, which is usually what you want for a breaker whose job is to react to *recent* health. Rule of thumb: **time-based for variable/high throughput; count-based when traffic is steady and you reason in calls.**

### 7.2 Slow-call detection — catching brownouts, not just outages

Most real outages start as **brownouts**: the dependency still answers, but slowly (GC pauses, connection-pool saturation, a degraded DB). A failure-only breaker stays CLOSED through a brownout because nothing technically *fails* — until the slowness exhausts your threads and *then* everything fails at once. Resilience4j's `slowCallRateThreshold` + `slowCallDurationThreshold` let the breaker trip on *latency*, catching the problem in the brownout phase. This is one of the strongest reasons to prefer Resilience4j over plain Hystrix-style failure counting. Set `slowCallDurationThreshold` to your **latency SLO/budget** for that call, not to the timeout — you want to trip *before* you'd time out, not at the cliff.

### 7.3 Exponential backoff on the wait duration

By default OPEN lasts a fixed `waitDurationInOpenState`. For dependencies that fail *repeatedly* (each HALF_OPEN probe fails and you re-OPEN), you can make the open duration **grow** each cycle via a custom `IntervalBiFunction` (e.g., 5s, 10s, 20s, capped). This backs off the probing pressure on a dependency that clearly isn't recovering, while still recovering quickly from a brief blip. (Resilience4j exposes this through the wait-duration interval function in 1.7+/2.x; verify exact API for your version.)

### 7.4 HALF_OPEN concurrency control and `maxWaitDurationInHalfOpenState`

A trap: if `permittedNumberOfCallsInHalfOpenState=10` but those probe calls themselves hang, the breaker can sit in HALF_OPEN indefinitely waiting for 10 results. `maxWaitDurationInHalfOpenState` (default 0/disabled) caps the time in HALF_OPEN — when it elapses, the breaker decides based on whatever results it has (or re-opens). Set it when your probe calls can hang, so you don't get a stuck HALF_OPEN that never resolves.

### 7.5 Per-instance vs shared (distributed) state

By default, **each JVM has its own breaker state** — it's an in-process object. In a fleet of 50 pods, each learns about a dependency's health independently from *its own* traffic. Consequences:
- A pod that hasn't sent enough calls (`minimumNumberOfCalls`) won't trip even if the dependency is down for everyone else.
- The fleet "heals" in a staggered way as each pod's HALF_OPEN probes succeed.

This is usually **fine and even desirable** (decentralized, no coordination, no single point of failure). But some teams want **shared breaker state** (e.g., backed by Redis) so the whole fleet trips/recovers together. Sentinel supports cluster flow control; Resilience4j is fundamentally local but you can build shared state with custom event propagation. The tradeoff: shared state adds a coordination dependency (and its own failure mode) to your *resilience* layer — often not worth it. **Prefer local breakers unless you have a concrete reason.**

### 7.6 The thundering-herd recovery problem

When a popular dependency recovers and many pods' breakers go HALF_OPEN and then CLOSED around the same time, the dependency can get slammed by the sudden return of full traffic and fall over again — an oscillation. Mitigations: **jitter the wait duration** across pods (so they probe at different times), keep `permittedNumberOfCallsInHalfOpenState` small, and pair with a **rate limiter / adaptive concurrency limiter** so traffic ramps rather than spikes. Netflix's move *away* from Hystrix toward **adaptive concurrency limits** (TCP-Vegas-style, measuring latency to find the right concurrency) is largely about smoothing exactly this.

### 7.7 Breakers vs. load shedding vs. adaptive limits

A circuit breaker protects the **caller** from a sick **callee**. It does *not* protect a callee from too many callers — that's **load shedding** / **rate limiting** / **adaptive concurrency limiting** (the callee rejecting excess work to stay healthy). Mature systems use both: breakers on the client side, load shedding on the server side. Adaptive concurrency limits (e.g., Netflix concurrency-limits, the "Vegas" algorithm) dynamically discover the right in-flight limit by watching latency, which can be more robust than static breaker thresholds — but they're complementary, not replacements.

### 7.8 Idempotency and exactly-once concerns

Breakers and retries interact dangerously with **non-idempotent** operations (operations whose repetition changes state, like "charge card" or "send email"). When a call times out, you don't know if it *failed* or *succeeded-but-the-reply-was-lost*. Retrying (inside or around the breaker) can double-execute. The fix is **idempotency keys**: the caller sends a unique key; the server deduplicates, so a retried request with the same key is a no-op that returns the original result. Make writes idempotent *before* you let retry/breaker logic re-issue them.

### 7.9 Reactive and virtual-thread interplay

- **Reactive (Project Reactor / RxJava):** Resilience4j has dedicated operators (`resilience4j-reactor`) so you decorate `Mono`/`Flux` without blocking threads; the breaker counts the reactive signal's terminal outcome.
- **Virtual threads (Java 21+ / Project Loom):** virtual threads make blocking calls cheap (millions of them), which *reduces* thread-pool-exhaustion pressure — but it does **not** remove the need for breakers. You still don't want to keep issuing doomed calls (they consume sockets, downstream capacity, and time), and you still want fail-fast + fallback semantics. With virtual threads, the bulkhead's *thread* concern softens, but the **breaker's "stop hitting a dead dependency" value is undiminished**; you may shift bulkheading from thread-pool isolation to **semaphore-based concurrency limits**.

### 7.10 Events and the `EventPublisher` firehose

Resilience4j emits a typed event for every interesting moment (`onSuccess`, `onError`, `onIgnoredError`, `onCallNotPermitted`, `onStateTransition`, `onFailureRateExceeded`, `onSlowCallRateExceeded`, `onReset`). These power metrics, audit logs, and custom automation (e.g., page on-call when a *critical* dependency's breaker opens). There's also a bounded in-memory `EventConsumerRegistry` exposed via `/actuator/circuitbreakerevents` for the last N events — handy for post-incident forensics without log spelunking.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Library comparison

| Dimension | Hystrix (Netflix) | Resilience4j | Sentinel (Alibaba) |
|---|---|---|---|
| Status | **Maintenance mode** (since 2018), no new features | **Active**, de-facto Java standard | Active, strong in cloud-native/China ecosystem |
| Java baseline | Java 6+ | Java 8+ (functional) / Java 17 for 2.x | Java 8+ |
| Dependencies | Pulls in RxJava, Archaius | **Zero deps** (modular: pick what you need) | Lightweight; integrates with Nacos etc. |
| Isolation built-in | **Yes** (thread/semaphore bulkhead built into command) | Separate **Bulkhead** module (compose explicitly) | Separate flow rules |
| Programming model | Subclass `HystrixCommand` (heavy) | Decorate functions / annotations (light) | Resource + rules / `@SentinelResource` |
| Slow-call rate tripping | No (only failures/timeouts) | **Yes** (first-class) | Yes (RT grade) |
| Window | Time-based, 10s/10 buckets | **Count or time-based**, configurable | Time-based, configurable |
| HALF_OPEN probes | Single trial request | **Configurable count** | Single probe |
| Dynamic rule source | Archaius | Code/YAML (+ refresh) | **Nacos/ZK/Apollo**, live dashboard |
| Built-in dashboard | Hystrix Dashboard + Turbine | No (use Micrometer + Grafana) | **Yes**, rich dashboard |
| Adaptive/system protection | No | No (use external) | **Yes** (system-load-based) |
| Best for | Legacy maintenance only | New Java/Spring services | Spring Cloud Alibaba; flow control + breaking unified |

### 8.2 Window-type decision

| Choose | When |
|---|---|
| **Time-based** | Throughput is high or variable; you reason about "last N seconds"; you want traffic-independent sensitivity. (Good default.) |
| **Count-based** | Steady, predictable traffic; you reason in "last N calls"; low-volume where you want a fixed sample size. |

### 8.3 Should I even use a breaker here?

| Use a circuit breaker when… | Avoid / reconsider when… |
|---|---|
| Call crosses a process/network boundary | Call is local and fast (no remote failure domain) |
| Dependency can hang or fail in bulk | Failure is a client error (4xx) — breaker can't help |
| Fail-fast or a fallback is acceptable | The operation must complete for correctness and has no safe fallback |
| Shared exhaustible resources at risk (threads/conns) | Truly fire-and-forget async with no resource pressure |
| You have observability to operate it | You can't monitor it (a blind breaker is risky) |

### 8.4 Breaker vs. neighbors — what to use

| Symptom | Reach for |
|---|---|
| Dependency is *down/erroring for a sustained period* | **Circuit breaker** (+ fallback) |
| Individual calls *hang* | **Timeout** (TimeLimiter) — feeds the breaker |
| Too many *concurrent* calls to one dependency | **Bulkhead** |
| *Transient* blips, occasional errors | **Retry** with backoff+jitter (breaker-aware) |
| Protecting a *callee* from too much inbound load | **Rate limiter / load shedding / adaptive limits** |
| All of the above (real production) | **Compose them** in the right order (§6.5) |

---

## 9. Failure modes & debugging

### 9.1 "The breaker never trips even though the dependency is down"

Likely causes, in order of frequency:
1. **`minimumNumberOfCalls` not reached** — low traffic; the window never gathers enough samples. *Diagnose:* check `resilience4j_circuitbreaker_buffered_calls` / `cb.getMetrics().getNumberOfBufferedCalls()`; if it's well below the minimum, that's it. *Fix:* lower the minimum and/or use a time-based window.
2. **Failures are being *ignored*** — an `ignoreExceptions` rule (or a wrapping exception type) means the real failures aren't counted. *Diagnose:* watch `onIgnoredError` events. *Fix:* correct the classification predicate.
3. **The dependency returns a *value* (e.g., 503 `Response`) instead of throwing**, and you have no `recordResult`. *Fix:* add a result predicate (§5.4).
4. **Multiple breakers for the same dependency** split the signal. *Fix:* consolidate to one named breaker.

### 9.2 "The breaker trips too easily / flaps"

1. **Threshold too low or window too small** (especially a tiny count-based window at high QPS — "last 20 calls" is one bad GC pause). *Fix:* widen the window or use time-based; raise `minimumNumberOfCalls`.
2. **Client errors counted as failures** — a burst of 404s trips it. *Fix:* `ignoreExceptions` / predicate for 4xx.
3. **`waitDurationInOpenState` too short** causing OPEN↔HALF_OPEN oscillation. *Fix:* lengthen it; add backoff and jitter.

### 9.3 "Stuck in HALF_OPEN forever"

Probe calls are hanging and there's no `maxWaitDurationInHalfOpenState`. *Diagnose:* state gauge shows HALF_OPEN persistently; probe calls show no terminal outcome. *Fix:* set `maxWaitDurationInHalfOpenState`, and ensure probes have a TimeLimiter so they can't hang.

### 9.4 "Breaker is OPEN but I don't know why / when"

You lack event logging. *Fix retroactively:* hit `/actuator/circuitbreakerevents/{name}` for the recent event ring (state transitions, recent errors) — this often pinpoints the first failing call and the exact trip moment without trawling logs. *Fix permanently:* subscribe to `onStateTransition` and log with timestamps; alert on the state gauge.

### 9.5 "Tripping the breaker took down healthy features too"

One breaker guards multiple unrelated dependencies, so its trip blocks all of them. *Fix:* split into per-dependency (and possibly per-endpoint) breakers.

### 9.6 "Orchestrator keeps killing healthy pods"

An OPEN breaker is wired to make `/actuator/health` report DOWN, so k8s/LB removes the pod even though it's correctly surviving a *downstream* outage — reducing your capacity exactly when you need it. *Fix:* don't let downstream-breaker state fail readiness; report DEGRADED, or set `allow-health-indicator-to-fail: false` / exclude it from readiness.

### 9.7 "Retries made the outage worse"

Retry is amplifying load against a struggling dependency (or is *outside* the breaker without backoff). *Diagnose:* downstream sees a traffic *spike* the moment it gets slow. *Fix:* bound `maxAttempts`, add exponential backoff + jitter, place Retry *outside* the breaker so OPEN short-circuits retries, and ensure retried ops are idempotent.

### 9.8 Real-world incident patterns (representative)

- **AWS / large-fleet retry storms:** multiple historical large-scale outages were *amplified* by aggressive client retries hammering a recovering control plane; the canonical mitigation guidance is exponential backoff **with jitter** and circuit breaking to stop the storm. (This is the textbook cascading-failure scenario the pattern targets.)
- **Netflix's own motivation for Hystrix:** a single misbehaving dependency causing thread-pool exhaustion and cascading API failures — which is precisely why they built per-dependency isolation + breaking, and later moved to adaptive concurrency limits to handle the recovery-oscillation problem more smoothly.
- **The "slow, not down" brownout:** the most insidious production incident is a dependency that's *slow but answering*; failure-only breakers stay CLOSED while threads pile up. Teams that learned this lesson added **slow-call-rate tripping** and latency SLO-based thresholds.

(Flag: specific public post-mortems vary in detail and I'm describing the *pattern* of these incidents rather than quoting exact figures; verify specifics against the official write-ups before citing numbers.)

### 9.9 Debugging toolkit cheat list

- `cb.getState()` / `cb.getMetrics()` — instant in-process snapshot.
- `/actuator/circuitbreakers` and `/actuator/circuitbreakerevents` — runtime state + recent event ring.
- Micrometer/Prometheus gauges (`..._state`, `..._failure_rate`, `..._not_permitted_calls_total`) on a Grafana panel.
- `onStateTransition` logs with timestamps — your incident timeline.
- WireMock/MockWebServer fault injection — reproduce in a test.
- Thread dumps (`jstack`) — confirm whether threads are blocked on the dependency (the symptom the breaker prevents).

---

## 10. Interview drill

**Q1. Explain the circuit-breaker states and the transitions between them.**
*Model answer:* Three operational states. **CLOSED:** all calls pass; outcomes are counted in a sliding window. When the failure rate (or slow-call rate) crosses a threshold — after a minimum number of calls — it trips to **OPEN**. **OPEN:** all calls are rejected immediately (fail fast, no attempt) for a wait duration; this is what prevents cascading failure. After the wait, it goes **HALF_OPEN:** a small fixed number of trial calls are allowed; if they mostly succeed it returns to CLOSED, otherwise back to OPEN with the wait timer restarted. (Plus admin states: DISABLED, FORCED_OPEN, METRICS_ONLY.)
- *Follow-up: Why HALF_OPEN instead of going straight CLOSED?* Because instantly trusting the dependency could re-trigger the cascade if it's still sick; HALF_OPEN tests with a *limited* probe so a still-broken dependency only sees a trickle, not the full flood.
- *Follow-up: What triggers OPEN→HALF_OPEN — a timer or a call?* By default it's lazy: the transition happens on the first call after the wait elapses (no background thread). You can enable an automatic scheduler-based transition if you want it to flip with zero traffic.
- *Follow-up: Why a `minimumNumberOfCalls`?* To avoid statistically meaningless rates — without it, the first failing call reads as 100% failure and trips spuriously.

**Q2. How exactly does a circuit breaker prevent cascading failure?**
*Model answer:* Cascading failure happens when a slow dependency causes calls to block, those blocked calls hold threads, the thread pool exhausts, and the service (then its callers) goes down — Little's Law: in-flight = arrival_rate × latency, so when latency explodes, in-flight exceeds the pool. The breaker keeps latency *low* on the caller side by failing in microseconds while OPEN instead of waiting for multi-second timeouts, so threads aren't held and the pool doesn't exhaust. It contains the blast radius to "features needing that dependency are degraded."
- *Follow-up: A timeout already frees the thread — why also need a breaker?* A timeout still ties up a thread for the *whole* timeout duration on *every* doomed call; under load that's enough to exhaust the pool. The breaker stops even *starting* calls it expects to fail, so the cost goes to ~zero.
- *Follow-up: Where does the bulkhead fit?* The bulkhead caps how many threads/concurrent calls a single dependency can ever consume, so even before the breaker trips, one dependency can't starve the whole service.

**Q3. Hystrix vs Resilience4j — when and why?**
*Model answer:* Hystrix is in maintenance mode since 2018 — fine to keep running but no new features; it bundles thread-pool isolation (bulkhead) into each command and trips on failures/timeouts over a 10s window. Resilience4j is the active standard: zero external deps, modular, functional decoration or annotations, and crucially adds **slow-call-rate tripping** and a **configurable HALF_OPEN probe count** and choice of **count- or time-based windows**. New work → Resilience4j (or Spring Cloud CircuitBreaker as the abstraction). Sentinel if you're in the Spring Cloud Alibaba world and want unified flow control + breaking + a dashboard.
- *Follow-up (senior-signal): Why did Netflix move away from Hystrix?* Static thresholds and per-dependency thread pools are operationally heavy and don't handle recovery oscillation well; Netflix moved toward **adaptive concurrency limits** that infer the right in-flight limit from latency in real time, which smooths the thundering-herd recovery problem.

**Q4. Walk through configuring a breaker for a high-traffic HTTP dependency with a 200ms latency SLO.**
*Model answer:* Use a **time-based** window (traffic-independent), e.g. 10s. Set `minimumNumberOfCalls` to a meaningful sample for that traffic (say 50). `failureRateThreshold` ~50%. For latency: set `slowCallDurationThreshold` to the **SLO (200ms), not the timeout** — you want to trip in the brownout, before timeouts hit — with `slowCallRateThreshold` ~50–80%. `waitDurationInOpenState` a few seconds with jitter; `permittedNumberOfCallsInHalfOpenState` small (3–5). Add a TimeLimiter just above the SLO and a bulkhead. Ignore 4xx.
- *Follow-up: Why threshold below timeout?* Because waiting for timeouts is what exhausts threads; tripping on SLO-level slowness catches it earlier.
- *Follow-up: Count vs time window here?* Time-based — at high, bursty QPS a count-based window's time span swings wildly and gets twitchy.

**Q5. What's the correct order to compose Retry, CircuitBreaker, TimeLimiter, and Bulkhead, and why?**
*Model answer:* Outer→inner: **Retry( CircuitBreaker( TimeLimiter( Bulkhead( call )))).** Retry outermost so that when the breaker is OPEN, retries hit a fast `CallNotPermittedException` and stop — no hammering a dead dependency. CircuitBreaker above TimeLimiter/Bulkhead so persistent **timeouts and bulkhead rejections are counted** by the breaker and can trip it. Bulkhead innermost to cap concurrency on the actual call.
- *Follow-up: What if Retry were inside the breaker?* Each retry attempt would be counted by the breaker, inflating failures and tripping faster — occasionally what you want, usually not.
- *Follow-up: What if the breaker were innermost?* It wouldn't see timeouts (the TimeLimiter aborts first), so chronic slowness wouldn't trip it.

**Q6. How should a fallback behave? Give good and bad examples.**
*Model answer:* A good fallback is **fast, local, and side-effect-safe**, turning a hard failure into graceful degradation: return cached/stale data, a sensible default (empty list), or enqueue for async retry. Bad fallbacks: calling *another* remote service (new failure domain that can itself fail), doing heavy work, or silently returning wrong data for an operation that needs correctness. Branch on `CallNotPermittedException` (breaker OPEN) vs general failure when the right degradation differs.
- *Follow-up: Should every breaker have a fallback?* No — sometimes failing fast with a clean error is the right behavior. But you should *consciously decide* the degradation per breaker, not default to leaking a 500.

**Q7. Per-JVM vs shared breaker state across a fleet — tradeoffs.**
*Model answer (senior-signal):* Default is per-JVM/local: each pod learns from its own traffic. Pros: no coordination, no single point of failure, decentralized. Cons: a low-traffic pod may not trip; the fleet recovers in a staggered way. Shared state (e.g., Redis-backed) makes the fleet trip/recover together but adds a coordination dependency *inside your resilience layer* — a new failure mode. Usually keep it local; only centralize with a concrete need. Pair local breakers with jittered wait durations to avoid synchronized recovery storms.
- *Follow-up: How do you stop synchronized recovery from re-toppling a dependency?* Jitter the wait duration across pods, keep half-open probes small, and ramp traffic via a rate limiter / adaptive concurrency limiter.

**Q8. Your breaker isn't tripping during a real outage. How do you debug it?**
*Model answer:* Check `getNumberOfBufferedCalls` vs `minimumNumberOfCalls` (most common: not enough samples on a low-traffic endpoint). Check for `onIgnoredError` events and an over-broad `ignoreExceptions`. Check whether the client returns a 5xx *value* instead of throwing (needs `recordResult`). Check for duplicate breakers splitting the signal. Tools: `/actuator/circuitbreakerevents`, Micrometer gauges, breaker metrics snapshot.
- *Follow-up: It trips in staging but not prod — why?* Different traffic shape: prod QPS makes a count window cover milliseconds, or prod has more 4xx noise being miscounted, or `minimumNumberOfCalls` interacts differently with prod volume.

**Q9 (senior-signal). When is a circuit breaker the wrong tool?**
*Model answer:* When the failure is a **client error** (4xx) the breaker can't help; when the operation **must complete for correctness** and has no safe fallback (you'd be trading an error for silent wrong data); for **local, fast** calls with no remote failure domain; or when the real need is protecting the **callee** from overload (that's rate limiting / load shedding / adaptive limits, server-side). Also, a breaker without observability is risky — you can't operate what you can't see.
- *Follow-up: Do virtual threads (Loom) make breakers obsolete?* No. They reduce *thread-pool-exhaustion* pressure, softening the bulkhead concern, but the breaker's core value — stop issuing doomed calls, fail fast, degrade gracefully — is undiminished; sockets, downstream capacity, and latency budgets still matter.

**Q10 (senior-signal). How do you choose thresholds, and how do you know they're right?**
*Model answer:* Derive `slowCallDurationThreshold` from the call's **latency SLO**, not its timeout. Set `failureRateThreshold` from how much error you can tolerate before degrading is better than trying (often 50%). Size the window/minimum from traffic so the rate is statistically meaningful and reacts within seconds. Then **validate empirically**: run in METRICS_ONLY shadow mode against real traffic to see what it *would* do, and run **game-day chaos tests** (kill/slow the dependency) to confirm it contains the blast radius and the alarms fire. Thresholds are a hypothesis you must test, not a one-time guess.
- *Follow-up: How would you tune for a dependency that's "important but flaky"?* Slightly higher tolerance + a strong fallback + exponential backoff on the open duration so repeated failures back off, plus jitter to avoid synchronized probing.

**Q11. What metrics do you put on the dashboard and alert on?**
*Model answer:* The **state gauge** (alert on OPEN for critical breakers), **failure rate** and **slow-call rate** (leading indicators), **not-permitted-calls count** (blast radius), **call outcome mix**, and **buffered-calls** (to explain non-trips). Log every state transition with timestamps for the incident timeline.
- *Follow-up: Should OPEN page someone?* For a *critical* dependency, yes; for a non-critical one with a good fallback, maybe just a warning — alert fatigue is real, so tie severity to the user impact of the degraded feature.

**Q12. Explain slow-call detection and why it matters more than people think.**
*Model answer:* `slowCallDurationThreshold` marks any call slower than X as "slow" even if it succeeds; `slowCallRateThreshold` trips when too many calls are slow. It matters because most outages begin as **brownouts** — the dependency answers but slowly — and a failure-only breaker stays CLOSED while threads pile up until everything fails at once. Tripping on latency catches the problem in the brownout phase, before it becomes an outage. Hystrix lacked this first-class.
- *Follow-up: Set the threshold to the timeout or the SLO?* The SLO — you want to trip *before* the timeout cliff, when slowness is degrading you but hasn't yet exhausted threads.

---

## 11. Glossary

- **Adaptive concurrency limit** — a server- or client-side mechanism that dynamically discovers the right number of concurrent in-flight requests by observing latency (e.g., TCP-Vegas-style), instead of a fixed limit. Smooths recovery oscillation.
- **Atomic / CAS (compare-and-swap)** — a hardware-supported operation that updates a value only if it still equals an expected value, all in one uninterruptible step; the basis of lock-free concurrency.
- **Backoff (exponential)** — increasing the wait between retries multiplicatively (100ms, 200ms, 400ms…) to reduce load on a struggling dependency.
- **Brownout** — partial degradation where a dependency still responds but slowly; the precursor to a full outage.
- **Bulkhead** — resource isolation (separate thread pool or concurrency budget per dependency) so one failure can't consume all resources; named after ship compartments.
- **Cascading failure** — a failure that propagates across services as each exhausts its resources waiting on the next; the primary thing breakers prevent.
- **CircuitBreakerConfig** — Resilience4j's configuration object holding thresholds, window settings, and durations.
- **CLOSED** — breaker state where calls pass through normally (healthy).
- **`CallNotPermittedException`** — Resilience4j exception thrown when a call is rejected because the breaker is OPEN (or HALF_OPEN limit reached).
- **Count-based window** — sliding window over the last N *calls*.
- **Decoration** — wrapping a function with resilience logic (Resilience4j's model).
- **DISABLED** — admin state: breaker always allows calls, never trips, doesn't record.
- **Failure-rate threshold** — the percentage of failed calls that trips the breaker.
- **Fallback** — alternative behavior when a call is short-circuited or fails; the basis of graceful degradation.
- **Fail fast** — return an error/fallback immediately rather than waiting on a doomed call.
- **Finite-state machine (FSM)** — a system always in exactly one of a fixed set of states, moving between them on events.
- **FORCED_OPEN** — admin state: breaker always rejects (manual kill switch).
- **Graceful degradation** — continuing to provide reduced-but-useful service during partial failure.
- **HALF_OPEN** — breaker state allowing a limited number of trial calls to test recovery.
- **Hysteresis** — state depending on recent history, not just current input; gives the breaker its "stay tripped for a while" behavior.
- **Hystrix** — Netflix's circuit-breaker/bulkhead library, now in maintenance mode.
- **Idempotency / idempotency key** — property (or token) ensuring repeating an operation has the same effect as doing it once; essential for safe retries of writes.
- **IntervalFunction / IntervalBiFunction** — Resilience4j abstraction computing wait durations (e.g., backoff with jitter) for retries or open-state duration.
- **Jitter** — randomization added to wait/backoff times to de-synchronize many clients and avoid thundering herds.
- **Little's Law** — `L = λ × W`: concurrent in-flight = arrival rate × time-in-system; explains thread-pool exhaustion.
- **Load shedding** — a server proactively rejecting excess work to protect its own health.
- **Maintenance mode** — a project receiving only critical fixes, no new features (Hystrix's status).
- **`maxWaitDurationInHalfOpenState`** — cap on time spent in HALF_OPEN before forcing a decision.
- **Measurement** — a single recorded outcome (success/slow/failure + duration) in Resilience4j's window.
- **METRICS_ONLY** — Resilience4j state that records and reports but never trips (shadow/observe mode).
- **Micrometer** — JVM metrics facade that exposes Resilience4j metrics to Prometheus/Grafana/etc.
- **`minimumNumberOfCalls`** — minimum recorded calls before rates are evaluated.
- **MVCC** — *(adjacent term)* Multi-Version Concurrency Control, a database technique for concurrent reads/writes; mentioned only as an example of a term to define inline — not central here.
- **Nacos** — Alibaba's config + service-discovery server; a dynamic rule source for Sentinel.
- **OPEN** — breaker state that rejects all calls for a wait duration.
- **`permittedNumberOfCallsInHalfOpenState`** — number of trial calls allowed in HALF_OPEN.
- **Rate limiter** — caps the rate of calls (throughput) regardless of health.
- **Raft** — *(adjacent term)* a consensus algorithm for distributed agreement; not used here directly, defined for completeness when distributed shared state is discussed.
- **Resilience4j** — the modern, modular, zero-dependency Java resilience library; current standard for circuit breaking.
- **Retry** — automatically re-attempting failed calls, ideally with bounded attempts, backoff, and jitter.
- **Ring buffer** — fixed-size circular array; oldest entries overwritten by newest. Backs the count-based window.
- **Sentinel** — Alibaba's flow-control/resilience library unifying rate limiting, circuit breaking, and system protection, with a dashboard.
- **Sliding window** — the set of recent outcomes the breaker evaluates (count- or time-based).
- **Slow call** — a call that completes but exceeds `slowCallDurationThreshold`.
- **Slow-call-rate threshold** — percentage of slow calls that trips the breaker.
- **SLO (Service Level Objective)** — a target for a metric like latency (e.g., p99 < 200ms); should drive the slow-call threshold.
- **Spring Cloud CircuitBreaker** — vendor-neutral abstraction letting you swap Resilience4j/Sentinel under one API.
- **Thread-pool exhaustion** — all request-handling threads blocked, so the service can accept no new work; the core failure breakers prevent.
- **Thundering herd** — many clients acting in synchronized lockstep (retrying or recovering at once), overwhelming a dependency.
- **TimeLimiter** — Resilience4j component imposing a timeout on an async call so slow calls are aborted and countable.
- **Time-based window** — sliding window over the last T *seconds*, bucketed per second.
- **Timeout** — a deadline on a single call after which it's aborted as failed.
- **Turbine** — Hystrix's stream aggregator that combined many instances' metrics for the Hystrix Dashboard.
- **Virtual threads (Project Loom)** — cheap JVM threads (Java 21+) that make blocking calls inexpensive, softening (not removing) bulkhead/breaker thread concerns.
- **`waitDurationInOpenState`** — how long the breaker stays OPEN before allowing HALF_OPEN probes.
- **`writableStackTraceEnabled`** — whether `CallNotPermittedException` fills in a stack trace (turn off on hot paths).
- **ZooKeeper** — Apache distributed coordination service (config, locks, leader election); a possible rule source for Sentinel.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**States:** CLOSED (pass + count) → OPEN (reject fast for `waitDuration`) → HALF_OPEN (limited probes) → CLOSED or back to OPEN. Admin: DISABLED, FORCED_OPEN, METRICS_ONLY.

**Why:** breaks the cascade chain (slow dep → blocked threads → pool exhaustion → service down). Fail in µs, not seconds. Little's Law: `inflight = rate × latency`.

**Resilience4j defaults (2.x — verify version):** `failureRateThreshold=50%`, `slowCallRateThreshold=100%` (off), `slowCallDurationThreshold=60s`, `slidingWindowType=COUNT_BASED`, `slidingWindowSize=100`, `minimumNumberOfCalls=100`, `waitDurationInOpenState=60s`, `permittedNumberOfCallsInHalfOpenState=10`, `automaticTransition=false`.

**Tuning rules:**
- Time-based window for high/variable QPS; count-based for steady traffic.
- `slowCallDurationThreshold` = your **latency SLO**, not the timeout.
- Lower `minimumNumberOfCalls` for low-traffic endpoints or it never trips.
- **Ignore 4xx**; trip only on dependency faults (5xx/timeouts/slow). Use `recordResult` if 5xx comes back as a value, not an exception.
- Jitter the wait duration to avoid synchronized fleet recovery.

**Composition order (outer→inner):** `Retry( CircuitBreaker( TimeLimiter( Bulkhead( call ))))`.

**Fallback:** fast, local, side-effect-safe; branch on `CallNotPermittedException` vs real failure.

**Watch:** state gauge (alert OPEN), failure_rate, slow_call_rate, not_permitted_calls, buffered_calls. Log every transition. Don't make OPEN = readiness DOWN.

**Libraries:** Hystrix (maintenance, thread-isolation built-in, no slow-rate) · **Resilience4j** (active default, slow-rate, configurable probes, count/time window) · Sentinel (flow control + breaking + dashboard, Alibaba ecosystem).

**Top anti-patterns:** trip on 4xx · no fallback plan · untuned defaults on low traffic · one breaker for many deps · retry storms (no backoff/jitter, retry outside breaker) · too-short wait causing flapping · fallback that does remote work · retrying non-idempotent writes · OPEN→pod-killed.

**Debug map:** not tripping → check buffered vs minimum, ignored exceptions, value-not-thrown, duplicate breakers · flapping → widen window/raise min, ignore 4xx, lengthen wait · stuck HALF_OPEN → set `maxWaitDurationInHalfOpenState` + TimeLimiter on probes · why/when open → `/actuator/circuitbreakerevents` + transition logs.

### Self-test (no answers — recall practice)

1. Without looking, draw the full state machine including the admin states and label every transition condition (including what `minimumNumberOfCalls` gates).
2. A dependency is *slow but answering* (a brownout). A failure-only breaker stays CLOSED — walk through, using Little's Law, how this still takes the service down, and how slow-call tripping prevents it. What value would you set `slowCallDurationThreshold` to and why?
3. You have Retry, CircuitBreaker, TimeLimiter, and Bulkhead. Give the correct nesting order and justify each placement by describing what breaks if it were moved one position.
4. Your breaker uses `slidingWindowSize=100` (count-based) and `minimumNumberOfCalls=100`. The endpoint serves 1 request/minute and the dependency is down. Predict the breaker's behavior over the next 30 minutes and propose a fix.
5. Design the metrics + alerts for a *critical* payment-gateway breaker and a *non-critical* recommendations breaker. Justify why the alerting severity differs, and explain why you would (or wouldn't) wire either into the pod's readiness probe.
6. Compare per-JVM vs Redis-shared breaker state for a 50-pod fleet. Name a concrete failure mode introduced by sharing, and describe how you'd mitigate synchronized recovery either way.
7. Explain why a circuit breaker is the *wrong* tool for (a) a 404 from a dependency, (b) protecting a callee from too many callers, and (c) a non-idempotent write — and name the right tool for each.

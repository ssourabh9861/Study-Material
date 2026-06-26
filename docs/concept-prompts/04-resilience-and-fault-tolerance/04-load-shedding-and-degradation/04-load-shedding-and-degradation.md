# Load Shedding & Graceful Degradation

> **Concept area:** Resilience & Fault Tolerance
> **Subtopic:** Load Shedding & Graceful Degradation
> **Reader:** A senior JVM/Java backend engineer who wants to master this end-to-end — design, operate, debug, teach, and interview.

---

## 1. Overview & where it fits

### 1.1 The one-paragraph mental model

A server has a finite capacity: a maximum sustainable rate of useful work it can do per second before its internal queues grow without bound, latency climbs, and it eventually falls over. **Load shedding** is the deliberate, controlled act of *refusing or dropping some work* when demand exceeds that capacity, so that the work you *do* accept still completes within an acceptable latency and the server stays alive. **Graceful degradation** is the broader discipline of *reducing the quality, fidelity, or completeness of responses* (rather than failing entirely) when the system is under stress or a dependency is impaired. Put crudely: load shedding decides *which requests die so the rest can live*; graceful degradation decides *how a request can still return something useful even when the system can't do its full job*. Both exist because **a partially-working system that protects itself beats a fully-featured system that collapses under load.**

### 1.2 The problem it solves

Distributed systems do not degrade gracefully *by default*. The default behavior of an unprotected server under overload is a **congestion collapse / metastable failure**:

1. Requests arrive faster than the server can process them.
2. The work queue (thread pool queue, socket accept queue, event-loop backlog) grows.
3. Every request now waits behind a long queue, so **latency for every request increases**.
4. Memory used by queued requests grows; GC pressure rises (on the JVM, this causes long GC pauses, which makes throughput drop *further*).
5. Clients time out waiting. But the server **doesn't know** the client gave up — it still does the full work for a request whose answer nobody will read. This is **wasted work**.
6. Clients that timed out **retry**, adding *more* load. This is **retry amplification**, and it is the single most common cause of a small blip turning into a full outage.
7. Throughput of *useful* (completed-before-the-client-gave-up) work collapses toward zero while CPU is pegged at 100%. The system is now doing maximum work and accomplishing nothing. This is the **metastable failure state**: even after the original trigger is gone, the system stays broken because it's trapped doing wasted work and serving retries.

Load shedding and graceful degradation break this loop. Instead of accepting all 20,000 requests/sec into a server that can only complete 10,000/sec, you **accept 10,000 and reject 10,000 immediately and cheaply**, so the 10,000 you accept get answered quickly. The rejected callers get a fast, honest "no" (an HTTP 503/429) instead of a slow timeout — which, crucially, lets *them* fail fast and shed *their* load too.

### 1.3 When you reach for it

- Any service whose request rate can spike beyond provisioned capacity (almost all of them).
- Any service fronting a slow or limited downstream (a database with a fixed connection pool, a third-party API with a quota).
- Any service where **tail latency** matters (p99/p99.9) — load shedding is one of the most powerful tail-latency tools.
- Any multi-tenant or multi-priority system where some traffic is more valuable than other traffic (paying customers vs. crawlers; checkout vs. recommendations).
- Any system that has ever had, or could have, a **retry storm** or **thundering herd**.

### 1.4 Where it sits relative to its cousins

These four mechanisms are constantly confused. They are *complementary*, operate at different points, and you typically want several at once. Here is the precise distinction (expanded in §8):

| Mechanism | Who it protects | Where it lives | Question it answers |
|---|---|---|---|
| **Rate limiting** | The *server*, against a *client/tenant* exceeding an agreed budget | At the edge / per-caller | "Has *this caller* used more than its fair/contracted share?" |
| **Load shedding** | The *server*, against *aggregate overload* regardless of source | At admission, dynamically | "Is the *server as a whole* about to be overwhelmed right now?" |
| **Backpressure** | The *whole pipeline*, by *slowing the producer* | Across a connection/stream | "Can I signal upstream to *send slower* instead of dropping?" |
| **Graceful degradation** | The *user experience*, against *partial failure* | In business logic | "If I can't do the full job, what *useful subset* can I still return?" |

Mental shortcut: **rate limiting is per-client and usually static; load shedding is global and dynamic; backpressure is flow control that propagates upstream; degradation is about response quality.**

---

## 2. Foundations from first principles

Before the mechanics, we need a precise vocabulary. We build from queueing theory because *overload is fundamentally a queueing phenomenon*.

### 2.1 Capacity, utilization, and why "just add headroom" isn't enough

- **Throughput (λ, lambda):** the arrival rate of requests, e.g. requests per second (RPS).
- **Service rate (μ, mu):** the rate at which a single server (or worker) can complete requests when busy.
- **Service time (S):** how long one request takes to process, on average. `μ = 1/S` per worker.
- **Utilization (ρ, rho):** `ρ = λ / (c · μ)` where `c` is the number of parallel workers (threads/cores). Utilization is the fraction of capacity in use. When `ρ ≥ 1`, arrivals meet or exceed capacity and **the queue grows without bound** — this is the formal definition of overload.

**The killer fact from queueing theory (Kingman's formula / M/M/c queues):** *average queueing delay rises hyperbolically as ρ → 1.* Even at ρ = 0.8, your wait time is already several times the service time; at ρ = 0.95 it explodes. This is why you cannot "just run hot." A system at 85% utilization is one traffic blip away from the cliff. Load shedding is what keeps you off the cliff by **capping the effective arrival rate** so ρ never reaches 1.

> **Little's Law** (the most useful law in systems): `L = λ · W`, where `L` is the average number of requests *in the system* (concurrency / in-flight), `λ` is throughput, and `W` is average latency (time in system). Rearranged: `concurrency = throughput × latency`. This law is the theoretical foundation of **concurrency-limit-based load shedding** (§3.5): if you cap concurrency `L` and your latency `W` rises, throughput `λ` automatically falls — but bounded. It holds for *any* stable system regardless of arrival distribution, which is why it's so powerful.

### 2.2 Open-loop vs closed-loop load, and why retries are dangerous

- **Open-loop load:** new, independent users keep arriving regardless of how the system behaves (e.g., organic web traffic). Slowing responses doesn't reduce arrivals.
- **Closed-loop load:** a *fixed* set of clients each wait for a response before sending the next request (e.g., a fixed thread pool of workers calling you in a loop). Slowing responses *naturally* reduces arrival rate — the system is self-limiting.
- **Retries convert open-loop into something worse than closed-loop:** when you slow down, clients time out and *re-send*, so a slowdown *increases* load. This positive feedback loop is the engine of retry-amplified outages.

### 2.3 Goodput vs throughput — the metric that actually matters

- **Throughput:** requests handled per second (including ones whose answers arrive too late to be useful).
- **Goodput:** requests *completed successfully and in time to be useful* per second.

Under overload, an unprotected server's throughput stays high (CPU pegged) while its **goodput collapses to ~0** because everything it produces is stale (the client already timed out). **The entire point of load shedding is to maximize goodput, not throughput.** A correctly shedding server has lower throughput (it rejects work) but dramatically higher goodput.

### 2.4 The shapes of overload

- **Demand-driven overload:** more requests than capacity (a sale, a viral event, a Super Bowl ad).
- **Capacity-driven overload:** demand is normal but capacity *dropped* — a dependency got slow (so each request now ties up a thread longer, shrinking effective `c`), an instance died (so survivors absorb its share), a GC pause stalled the JVM, a noisy neighbor stole CPU.
- **Self-inflicted overload:** retry storms, a buggy client in a tight loop, a cache stampede (a hot key expires and a million requests simultaneously hit the database — the **thundering herd**).

Load shedding must handle all three; it doesn't care *why* the queue is growing, only *that* it is.

### 2.5 Priority, fairness, and "the right load"

Not all requests are equal. A core insight (Netflix, Google, Amazon all converge here): **shedding load is easy; shedding the *right* load is the hard, valuable part.** This requires:

- **Request criticality / priority:** classify requests as e.g. `CRITICAL` (login, checkout, payment), `DEGRADED_OK` (search), `BEST_EFFORT` (recommendations, analytics, prefetch).
- **Cost awareness:** an expensive query consumes more capacity than a cheap one; shedding by count alone is crude.
- **Fairness:** under shed, you don't want one abusive tenant to starve everyone, *and* you don't want to evenly punish a premium tenant and a free crawler.

### 2.6 Key terms defined inline (beginner glossary, used throughout)

- **Admission control:** the decision, made *as a request enters*, of whether to accept or reject it. Load shedding is admission control driven by load signals.
- **Thread pool / worker pool:** a fixed set of threads that pull work off a queue. The queue is where overload manifests on the JVM.
- **Bounded vs unbounded queue:** an unbounded queue *never rejects* — it just grows until you OOM (out of memory). **Unbounded queues are an anti-pattern**; they hide overload until catastrophe. Bounded queues are the first, simplest form of load shedding (reject when full).
- **Backpressure:** a mechanism that lets a slow consumer signal a fast producer to *slow down*, propagating the "I'm full" signal upstream rather than dropping data.
- **Circuit breaker:** a wrapper around a *dependency call* that "trips open" after repeated failures, failing fast for a cooldown period instead of hammering a sick dependency. (Related but distinct: a circuit breaker protects *you from a bad downstream*; load shedding protects *you from too much upstream*.)
- **Bulkhead:** isolating resources (e.g., separate thread pools per dependency or per tenant) so a failure in one "compartment" can't sink the whole ship. Named after a ship's watertight compartments.
- **Brownout:** intentionally running the system at *reduced functionality* (dimmed, like a power-grid brownout) to stay within capacity — a degradation technique.
- **Stale read / serve-stale:** returning a cached/older value when the fresh one is too expensive or unavailable.
- **FIFO / LIFO:** First-In-First-Out / Last-In-First-Out queue disciplines. Under overload, *which* discipline you use dramatically changes who gets served (§3.7).
- **Tail latency (p99, p99.9):** the latency experienced by the slowest 1% / 0.1% of requests. The "tail" is where overload shows up first.
- **CoDel (Controlled Delay):** an algorithm (from networking) that sheds based on how long items *sat in the queue*, not how full the queue is. Detailed in §3.6.

---

## 3. How it works internally

This is the heart of the document. We'll walk the full lifecycle of a request through a load-shedding server, then dissect each detection and decision mechanism.

### 3.1 The request lifecycle with admission control (control flow)

Consider a typical JVM HTTP service (Tomcat/Jetty/Netty + a thread pool). A request travels through several queues, *each* a potential shed point:

```
client → TCP SYN → OS accept queue (somaxconn) → acceptor thread →
   → HTTP parse → [ADMISSION CONTROL POINT] → worker thread pool queue →
   → worker thread → business logic → [DEPENDENCY CALLS w/ their own pools] →
   → response serialization → socket write buffer → client
```

Step by step, what a well-built shedding server does as a request arrives:

1. **OS-level accept queue.** The kernel holds completed TCP handshakes in the *accept queue*, sized by `somaxconn` / the listen backlog. If full, the kernel drops/refuses connections — the crudest, earliest shed, and one you don't control finely. (Defined: `somaxconn` is a Linux sysctl, default historically 128, raised to 4096 in newer kernels, capping the listen backlog.)
2. **Cheap pre-parse checks.** Before doing expensive work, check a fast load signal (e.g., a concurrency counter or CPU gauge). Reject *here* with a 503 if overloaded — this is the cheapest place to shed because you've spent almost nothing.
3. **Classify & prioritize.** Extract the request's criticality (from a header, the route, the caller's identity, or a tenant tag).
4. **Admission decision.** Consult the load-shedder: given current load signal and this request's priority, *admit or reject*. (Algorithms in §3.4–3.7.)
5. **Enqueue (bounded).** If admitted, place on the worker queue. If the queue is full, reject (a second shed line). Optionally, set a **queue-entry timestamp** so the worker can drop it if it sat too long (CoDel/deadline).
6. **Worker picks up.** Before doing real work, the worker checks: *is this request already past its deadline / has the client already given up?* If so, drop it without working (avoid wasted work). On Netty/async stacks you can check if the channel is still open.
7. **Execute with per-dependency bulkheads.** Downstream calls go through *their own* bounded pools / semaphores / circuit breakers, so a slow dependency can't consume all your threads.
8. **Degrade if needed.** If a non-critical dependency is shedding/circuit-open, substitute a fallback (cached/stale/default) rather than failing the whole request (§3.8).
9. **Respond,** updating the load-shedder's feedback signals (observed latency, success/failure) so it can adapt.

The two most important *internal* ideas here are: **shed as early and as cheaply as possible** (step 2 beats step 6 beats step 8), and **never do work whose result will be discarded** (steps 6, the deadline checks).

### 3.2 Where overload actually manifests (the signals)

To shed, you must *detect* overload. The four canonical signals, with their tradeoffs:

| Signal | What it measures | Latency to detect | Pros | Cons |
|---|---|---|---|---|
| **Queue depth** | # items waiting | Fast | Direct, cheap | Depth alone doesn't tell you *how long* they waited; a deep-but-fast queue is fine |
| **Queue *sojourn* time** (time-in-queue) | How long the *oldest* item waited | Fast | Directly correlates with user pain; basis of CoDel | Needs per-item timestamps |
| **Latency (p99, EWMA)** | End-to-end response time | Medium (lagging) | Reflects true user experience | Lagging indicator — by the time p99 spikes you may be deep in trouble |
| **Concurrency / in-flight** | # requests being processed simultaneously | Fast | Little's Law makes it adaptive; capacity-agnostic | Needs a good limit; static limits go stale |
| **CPU / load average** | Host CPU saturation | Medium | Good for CPU-bound work | Useless for I/O-bound work (you can be overloaded at 30% CPU waiting on a DB); noisy with GC; affected by noisy neighbors |
| **GC pause / allocation rate** (JVM-specific) | Memory pressure | Medium | Catches JVM-specific death spirals | Indirect |

**Best practice:** the most robust signals are **queue sojourn time** (CoDel) and **adaptive concurrency limits** (Little's Law), because they are *self-calibrating* and *capacity-agnostic* — they don't require you to know your magic RPS number in advance, which is good because that number changes with payload size, dependency health, deploys, and instance type. CPU is a useful *secondary* signal but a poor *primary* one (especially for I/O-bound services).

### 3.3 The state machine of an adaptive load shedder

An adaptive concurrency-limit shedder (the Netflix `concurrency-limits` / TCP-Vegas style model) behaves like a TCP congestion-control loop. States/transitions:

```
        ┌─────────────┐  latency stable, no drops   ┌──────────────┐
        │   PROBING   │ ───────────────────────────▶│   INCREASING │
        │  (measure   │                              │  limit += k  │
        │   RTT_noload)│◀──────────────────────────  │  (more       │
        └─────────────┘   latency rising / drop      │   headroom)  │
              │                                       └──────┬───────┘
              │                                              │ latency > threshold
              ▼                                              ▼
        ┌─────────────────────────────────────────────────────────┐
        │                   DECREASING / BACKOFF                    │
        │  limit = limit × β  (multiplicative decrease, β≈0.7–0.9)  │
        │  reject requests above the new, smaller limit (SHEDDING)  │
        └─────────────────────────────────────────────────────────┘
```

- **Probe / measure baseline (`RTT_noload`):** the minimum observed round-trip latency, taken as "the latency when *not* queued." This is the reference.
- **AIMD (Additive Increase, Multiplicative Decrease):** like TCP. When healthy, raise the concurrency limit by a small additive amount (cautiously claim more capacity). When latency inflates beyond a multiple of `RTT_noload` (meaning requests are queueing internally), *cut the limit multiplicatively* (back off fast). The "gradient" between current latency and baseline latency drives the size of the adjustment.
- **Shedding region:** whenever in-flight requests ≥ current limit, *reject* (or queue briefly then reject). The limit *is* the shed threshold, and it moves continuously.

This design's beauty: it discovers capacity automatically and tracks it as conditions change. If a downstream slows, latency inflates, the limit shrinks, and you shed *exactly enough* to keep latency bounded — no magic numbers.

### 3.4 Static threshold shedding (the simplest)

The baseline algorithm:

```
on request:
  if currentInFlight >= MAX_CONCURRENT:   reject(503)
  else:                                    accept; currentInFlight++
on response:                               currentInFlight--
```

Or a queue-length variant: reject when `queue.size() >= MAX_QUEUE`. This is what a bounded thread pool gives you for free. **Pros:** dead simple, predictable. **Cons:** the constant is a guess, goes stale, and is wrong the moment your service time changes (e.g., a dependency slows and each request now holds a thread 5× longer — your "safe" concurrency is suddenly 5× too high).

### 3.5 Concurrency-limit shedding (Little's Law in action)

Set a limit on *in-flight* requests, not on RPS. Why concurrency, not RPS?

- RPS limits are wrong whenever service time changes. 1000 RPS at 10ms is 10 in-flight; 1000 RPS at 100ms is 100 in-flight. The *same RPS* can be fine or catastrophic.
- Concurrency directly bounds resource use (threads, memory, DB connections) regardless of how long each request takes. By Little's Law, capping `L` is capping the actual load on your finite resources.

When the limit is *adaptive* (§3.3), you get the best of both worlds. This is the recommended default for modern JVM services.

### 3.6 CoDel — Controlled Delay (shed by how long things waited)

CoDel comes from network router queue management (Nichols & Jacobson, 2012) and was adapted for application request queues (notably by Facebook). The core idea: **a queue is only bad if items sit in it for a long time.** A queue that is momentarily deep but drains instantly is *fine* (a healthy buffer absorbing a burst). A queue where items linger is a *standing queue* — the sign of true overload.

CoDel algorithm (application-adapted, two parameters):

- `TARGET` (default ~5ms): acceptable queue sojourn time.
- `INTERVAL` (default ~100ms): the window over which we judge.

Logic each time a worker dequeues an item:
1. Compute `sojournTime = now - item.enqueueTime`.
2. If `sojournTime < TARGET` **or** `queue.isEmpty()` → we're healthy; reset the "overloaded since" timer. Process the item.
3. If `sojournTime ≥ TARGET` continuously for longer than `INTERVAL` → we're overloaded. **Drop this item** (and shorten the interval for the next drop, dropping more aggressively the longer overload persists).

The genius: CoDel **ignores short bursts** (a deep queue that drains fast never trips `TARGET` for a full `INTERVAL`) but **reacts to standing queues**. Facebook reported using exactly this for their PHP/Thrift queues. Twitter/Finagle and Envoy implement variants.

### 3.7 LIFO vs FIFO under overload — a non-obvious, high-leverage choice

Default queues are **FIFO** (first in, first out). Under overload, FIFO is **the worst possible discipline**, and here's the counterintuitive reason:

- Under FIFO overload, the request a worker picks up next is the *oldest* one — the one that has been waiting longest. But if you're overloaded, the oldest request has *probably already exceeded the client's timeout*. So you do full work for a request whose answer nobody will read (**wasted work**), then move to the next-oldest, which is *also* likely expired. **Under FIFO + overload, you can serve every request *just after* it became useless — goodput → 0 while throughput stays high.**
- Under **LIFO** (last in, first out / stack), the worker picks the *newest* request — the one most likely *still within* its client's timeout. The old, probably-abandoned requests sit at the bottom and get dropped when the queue overflows. **LIFO sacrifices the doomed-anyway old requests to keep goodput high.**

So a strong overload strategy is: **switch the queue discipline to LIFO (or "adaptive LIFO") when a standing queue is detected, and FIFO when healthy.** Facebook's "adaptive LIFO + CoDel" combination is famous: CoDel detects the standing queue and starts dropping; LIFO ensures the requests you *do* serve are the freshest. (Caveat: pure LIFO can *starve* old requests indefinitely under sustained overload and reorders fairness; that's why it's used *only* during detected overload, paired with CoDel to drop the starved oldies.)

### 3.8 Graceful degradation: the mechanics

Degradation is applied *inside* business logic, usually when a dependency is shedding/slow/circuit-open. The patterns, ordered roughly from cheapest to most impactful:

1. **Serve stale (stale-while-revalidate):** return the last cached value (even if expired) and refresh in the background. Turns a hard dependency failure into a slightly-old answer. Used everywhere (CDNs, DNS, config systems).
2. **Reduce fidelity:** return a cheaper version — fewer results, lower-res images, no personalization, approximate counts instead of exact, top-10 instead of top-100. The user gets *something*.
3. **Disable non-critical features (feature dimming / brownout):** turn off recommendations, related items, "people also viewed," real-time analytics, A/B-test logging — anything not on the critical path — to free capacity for the core function (checkout, login, read the article).
4. **Default/empty responses:** return a sensible default (empty recommendation list, generic banner) instead of erroring.
5. **Asynchronous offload:** accept the request, return "queued/accepted," process later (turn a synchronous failure into eventual processing) — only valid where the operation can be async.
6. **Read-only mode:** disable writes to protect a stressed primary database while still serving reads.
7. **Load shedding as degradation:** reject the lowest-priority requests entirely (this is the overlap point — prioritized shedding *is* a degradation strategy at the fleet level).

The crucial design principle: **degradation must be a first-class, tested code path, not an exception handler bolted on.** It should be controllable by a feature flag / "panic button" so operators can dim features manually during an incident.

### 3.9 Brownout in detail

"Brownout" (term popularized by research from Klein et al. and adopted in industry) means **continuously tunable degradation**: the application exposes a *dimmer* (e.g., a knob from 0.0 → 1.0) controlling how much optional work it does. A controller adjusts the dimmer to keep latency at a target — like a thermostat. At dimmer = 1.0, full features; at 0.3, only critical work runs. Brownout is degradation made *adaptive and continuous* rather than a binary on/off feature flag. It pairs naturally with adaptive concurrency limits.

### 3.10 Prioritized admission control (dropping the *right* load)

The mechanics of priority-aware shedding:

1. **Assign a criticality level** to each request (e.g., `CRITICAL=0`, `STANDARD=1`, `DEGRADED=2`, `BEST_EFFORT=3`). Sources: route, caller identity, an explicit `X-Request-Priority` header propagated through the call graph, or an SLA tier.
2. **Maintain per-priority admission thresholds.** As load rises, you progressively lower the threshold so that *low-priority traffic gets rejected first*. E.g., shed `BEST_EFFORT` at 70% load, `DEGRADED` at 85%, `STANDARD` at 95%, never shed `CRITICAL` (until the absolute hard limit).
3. **Propagate priority across the call graph.** A user-facing "checkout" request should carry its CRITICAL priority into every downstream call so the inventory service also protects it. Google's term for this is **criticality propagation**; it's essential — otherwise a downstream sheds your critical request because it can't tell it's critical.
4. **Combine with cost.** Weight admission by estimated request cost so a few expensive requests don't slip under a count-based limit.

Google's internal RPC framework (Stubby/gRPC) and Amazon's services do exactly this; AWS published the "Using load shedding to avoid overload" Builders' Library article describing priority-based shedding.

---

## 4. The complete toolkit

### 4.1 JVM / Java libraries and primitives

| Tool | Type | What it does | Key knobs | Notes / defaults |
|---|---|---|---|---|
| **`java.util.concurrent.Semaphore`** | JDK primitive | Cap concurrent entries; `tryAcquire()` to shed without blocking | permits; `tryAcquire(timeout)` | The simplest correct concurrency limiter |
| **`ThreadPoolExecutor`** | JDK | Bounded worker pool + bounded queue + **rejection policy** | `corePoolSize`, `maxPoolSize`, `BlockingQueue` capacity, `RejectedExecutionHandler` | The rejection handler *is* a load shedder. Default policy `AbortPolicy` throws `RejectedExecutionException` |
| **`ArrayBlockingQueue` / `LinkedBlockingQueue`** | JDK | Bounded/unbounded work queues | capacity (bounded!) | **Never use the unbounded `LinkedBlockingQueue()` constructor for request queues** |
| **`RejectedExecutionHandler`** | JDK SPI | Decide what to do when pool+queue full | custom impl | Built-ins: `AbortPolicy` (throw), `CallerRunsPolicy` (run on caller thread — *backpressure!*), `DiscardPolicy` (silently drop), `DiscardOldestPolicy` (drop oldest — crude LIFO-ish) |
| **Netflix `concurrency-limits`** | Library | Adaptive concurrency limit (Gradient2, Vegas, AIMD algorithms) + servlet/gRPC filters | algorithm, `minLimit`, `maxLimit`, `smoothing`, `rttTolerance` | The reference adaptive shedder for JVM; integrates with Servlet, gRPC, Tomcat |
| **Resilience4j** | Library | `Bulkhead` (semaphore), `ThreadPoolBulkhead`, `RateLimiter`, `CircuitBreaker`, `TimeLimiter` | `maxConcurrentCalls`, `maxWaitDuration`, limits per second, failure thresholds | The modern, lightweight successor to Hystrix; functional, no thread overhead in semaphore mode |
| **Netflix Hystrix** (deprecated) | Library | Bulkheads, circuit breakers, fallbacks, request collapsing | thread pool sizes, queue sizes, timeouts, fallback methods | **In maintenance mode — do not adopt new.** Conceptually foundational; you'll see it in older code |
| **Envoy / service mesh (Istio, Linkerd)** | Infra | Out-of-process load shedding: connection/request limits, circuit breaking, **adaptive concurrency filter (Gradient)**, **fault injection** | `max_connections`, `max_pending_requests`, `max_requests`, adaptive-concurrency `concurrency_limit_params` | Sheds *before* traffic reaches the JVM; language-agnostic |
| **Tomcat connector** | Server | Accept + thread limits | `maxConnections` (default 8192 NIO), `maxThreads` (default 200), `acceptCount` (accept queue, default 100) | When `maxThreads` + `acceptCount` exceeded, kernel refuses connections |
| **Jetty `QoSFilter` / `DoSFilter`** | Servlet filter | `QoSFilter` prioritizes & caps concurrent requests with a semaphore + priority; `DoSFilter` rate-limits | `maxRequests`, `waitMs`, `maxPriority` | `QoSFilter` is built-in prioritized admission control |
| **Spring Cloud Gateway / Spring Cloud Circuit Breaker** | Framework | `RequestRateLimiter` filter (Redis token bucket), Resilience4j integration | replenishRate, burstCapacity | Edge-level shedding/limiting in Spring stacks |
| **gRPC** | RPC | Per-channel `maxConcurrentStreams`, flow control (HTTP/2 windows = built-in backpressure), deadlines | `maxConcurrentCallsPerConnection`, deadlines | Deadlines propagate — basis for don't-do-wasted-work |
| **Caffeine** | Library | `refreshAfterWrite` / `expireAfter` for **serve-stale** degradation | refresh/expiry durations | Async refresh enables stale-while-revalidate |

### 4.2 The `RejectedExecutionHandler` choices (a load-shedding decision in disguise)

| Handler | Behavior | When to use |
|---|---|---|
| `AbortPolicy` (default) | Throws `RejectedExecutionException` | When the caller should translate to a 503 immediately — the honest "shed now" |
| `CallerRunsPolicy` | Runs the task on the *submitting* thread | Crude **backpressure**: slows the producer because it's busy running work. Good for internal pipelines; dangerous for the request-accepting thread (blocks accepting new conns) |
| `DiscardPolicy` | Silently drops the new task | Only for truly fire-and-forget, droppable work (metrics) — silent drops are usually an anti-pattern |
| `DiscardOldestPolicy` | Drops the oldest queued task, retries new one | A poor-man's "freshest wins" — but no deadline awareness |
| *custom* | Your logic: emit metric, return 503, classify by priority | **Recommended for request paths** |

### 4.3 Resilience4j `Bulkhead` parameters

| Parameter | Default | Meaning |
|---|---|---|
| `maxConcurrentCalls` | 25 | Max simultaneous calls allowed (semaphore permits) |
| `maxWaitDuration` | 0 | How long a call waits for a permit before being rejected (0 = fail fast) |

`ThreadPoolBulkhead` additionally: `maxThreadPoolSize`, `coreThreadPoolSize`, `queueCapacity` (default 100), `keepAliveDuration`.

### 4.4 Netflix `concurrency-limits` algorithms

| Algorithm | Idea | Best for |
|---|---|---|
| `VegasLimit` | TCP Vegas: estimate queue size from RTT inflation; adjust limit | General adaptive limiting |
| `Gradient2Limit` | Compare short-term vs long-term latency (gradient); the default recommendation | Most services; handles noisy latency well |
| `AIMDLimit` | Additive-increase/multiplicative-decrease on drops/timeouts | Simple, drop-driven |
| `FixedLimit` | Static limit | Baseline / testing |

Key knobs: `initialLimit`, `minLimit` (don't shrink below, e.g. 1–4), `maxLimit` (hard cap), `smoothing` (how fast the limit moves, 0–1), `rttTolerance` (how much latency inflation to tolerate before backing off), `queueSize` (a small FIFO/LIFO before reject).

### 4.5 Kernel / infra knobs (Linux)

| Knob | What | Default-ish |
|---|---|---|
| `net.core.somaxconn` | Max accept-queue length | 4096 (modern), 128 (old) |
| `net.ipv4.tcp_max_syn_backlog` | Half-open SYN queue | varies |
| listen `backlog` arg | App-requested accept queue | app-set, capped by `somaxconn` |
| cgroup CPU/memory limits | Container resource caps | deployment-set |

### 4.6 Observability toolkit (you cannot shed what you can't see)

- **Metrics to emit:** shed count (by reason + priority), current concurrency limit, in-flight count, queue depth, queue sojourn time (histogram), goodput vs throughput, latency percentiles (p50/p99/p99.9), rejection rate, degradation-mode active (gauge), circuit-breaker state.
- **Tools:** Micrometer (JVM metrics façade) → Prometheus/Datadog/CloudWatch; distributed tracing (OpenTelemetry) to see *where* requests are queued; JFR (Java Flight Recorder) and async-profiler for thread/queue stalls; GC logs (`-Xlog:gc*`).

---

## 5. Code examples by use case

> All examples are Java 17+, idiomatic, and commented on the non-obvious lines. They are deliberately *different* scenarios, not variations of one.

### 5.1 Use case A — Semaphore-based concurrency limiter (the minimal correct shedder)

```java
import java.util.concurrent.Semaphore;

/** Caps concurrent in-flight requests; sheds instantly when full. */
public final class ConcurrencyLimiter {
    private final Semaphore permits;

    public ConcurrencyLimiter(int maxConcurrent) {
        // 'false' = non-fair: higher throughput, slight chance of starvation.
        // Use 'true' (fair, FIFO) only if you must guarantee no starvation.
        this.permits = new Semaphore(maxConcurrent, false);
    }

    /** @return true if admitted (caller MUST release), false if shed. */
    public boolean tryAdmit() {
        // tryAcquire() with no timeout returns immediately — this is the
        // key to FAST shedding: we never block the accepting thread.
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }
}

// --- usage in a servlet/handler ---
ConcurrencyLimiter limiter = new ConcurrencyLimiter(200);

void handle(Request req, Response resp) {
    if (!limiter.tryAdmit()) {
        resp.setStatus(503);
        resp.setHeader("Retry-After", "1");   // tell clients to back off
        SHED_COUNTER.increment();              // observability!
        return;                                // shed fast, do NO work
    }
    try {
        doWork(req, resp);
    } finally {
        limiter.release();                     // release even on exception
    }
}
```

Why it matters: `tryAcquire()` (non-blocking) is the whole trick. A blocking `acquire()` would just *queue* the overflow on the accepting thread — recreating the very congestion you're trying to avoid.

### 5.2 Use case B — Bounded thread pool with a priority-aware rejection handler

```java
import java.util.concurrent.*;

public class PrioritizedExecutor {

    // Bounded queue of 1000; reject when full. NEVER use new LinkedBlockingQueue()
    // (unbounded) here — that hides overload until OutOfMemoryError.
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            50,                                   // core threads
            200,                                  // max threads
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),       // BOUNDED work queue
            new PriorityAwareRejectionHandler()   // our shedder
    );

    /** When the pool+queue are full, shed BEST_EFFORT but try harder for CRITICAL. */
    static class PriorityAwareRejectionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor exec) {
            PriorityTask task = (PriorityTask) r;
            if (task.priority == Priority.CRITICAL) {
                // For critical work, briefly try to enqueue (mild backpressure).
                try {
                    if (exec.getQueue().offer(r, 50, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            // Otherwise shed: record reason+priority, fail fast.
            Metrics.shed(task.priority);
            throw new RejectedExecutionException("shed " + task.priority);
        }
    }

    enum Priority { CRITICAL, STANDARD, BEST_EFFORT }

    static class PriorityTask implements Runnable {
        final Priority priority; final Runnable body;
        PriorityTask(Priority p, Runnable b){ this.priority=p; this.body=b; }
        public void run(){ body.run(); }
    }
}
```

Shows: bounded queue = first shed line; custom rejection handler = where you encode *which* load to drop; critical requests get a small extra grace.

### 5.3 Use case C — Adaptive concurrency limit with Netflix `concurrency-limits` (recommended default)

```java
// build.gradle: implementation 'com.netflix.concurrency-limits:concurrency-limits-core:0.4.x'
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;

import java.util.Optional;

public class AdaptiveShedder {

    // Gradient2: compares recent latency to a long-term baseline; the limit
    // auto-adjusts up when healthy and DOWN (shed) when latency inflates.
    private final Limiter<Void> limiter = SimpleLimiter.newBuilder()
            .limit(Gradient2Limit.newBuilder()
                    .minLimit(20)          // never shrink below 20 in-flight
                    .maxConcurrency(2000)  // hard ceiling
                    .build())
            .build();

    public void handle(Request req, Response resp) {
        // acquire() returns empty when we're at the adaptive limit -> SHED.
        Optional<Limiter.Listener> listener = limiter.acquire(null);
        if (listener.isEmpty()) {
            resp.setStatus(503);
            Metrics.shed("adaptive_limit");
            return;
        }
        Limiter.Listener l = listener.get();
        try {
            doWork(req, resp);
            l.onSuccess();    // feeds latency back so the limit can grow
        } catch (Exception e) {
            l.onIgnore();     // don't let app errors shrink the limit unfairly
            throw e;
        }
        // l.onDropped() would be called for timeouts/overload signals,
        // causing the limit to multiplicatively decrease.
    }
}
```

Why this is the default recommendation: **no magic numbers**. You don't declare "200 concurrent." The limit *finds itself* and tracks dependency health automatically.

### 5.4 Use case D — Graceful degradation: serve-stale with Caffeine + circuit breaker fallback

```java
import com.github.benmanes.caffeine.cache.*;
import io.github.resilience4j.circuitbreaker.*;
import java.time.Duration;

public class RecommendationService {

    private final RecClient recClient;          // the (sheddable) dependency
    private final CircuitBreaker breaker = CircuitBreaker.of("recs",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50)           // trip if >50% calls fail
            .slowCallRateThreshold(80)          // or >80% are "slow"
            .slowCallDurationThreshold(Duration.ofMillis(200))
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .build());

    // expireAfterWrite makes entries "stale" after 5 min, but we keep the
    // old value around to SERVE STALE while refreshing.
    private final LoadingCache<String, List<Item>> cache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .refreshAfterWrite(Duration.ofMinutes(5))   // async refresh in background
        .build(this::loadFresh);

    public List<Item> recommendations(String userId) {
        try {
            // Cache returns possibly-stale-but-instant data; refresh happens async.
            return cache.get(userId);
        } catch (Exception e) {
            // DEGRADE: recommendations are BEST_EFFORT — never fail the page for them.
            Metrics.degraded("recs_fallback");
            return DEFAULT_RECS;   // generic, non-personalized fallback
        }
    }

    /** Wrapped so a sick downstream trips the breaker -> we fail fast to fallback. */
    private List<Item> loadFresh(String userId) {
        return breaker.executeSupplier(() -> recClient.fetch(userId));
    }

    private static final List<Item> DEFAULT_RECS = List.of(/* curated defaults */);
}
```

Shows three degradation tools at once: **serve-stale** (Caffeine async refresh), **circuit breaker** (fail fast on a sick dependency), and **default fallback** (never fail the critical page for best-effort content).

### 5.5 Use case E — Don't do wasted work: deadline/abandonment check before processing

```java
public class DeadlineAwareWorker {

    /** Each queued item carries when it was enqueued + the client's deadline. */
    record Job(long enqueueNanos, long deadlineNanos, Request req, Response resp) {}

    void process(Job job) {
        long now = System.nanoTime();

        // 1) Deadline check: if the client already gave up, drop without working.
        if (now > job.deadlineNanos()) {
            Metrics.droppedExpired();
            return;                                  // avoid wasted work
        }

        // 2) CoDel-style sojourn check: if this item sat too long, we're in a
        //    standing queue — shed to recover (drop the freshest-doomed too if needed).
        long sojournMs = (now - job.enqueueNanos()) / 1_000_000;
        if (sojournMs > CODEL_TARGET_MS && standingQueueDetected()) {
            Metrics.shed("codel");
            job.resp().setStatus(503);
            return;
        }

        // 3) Connection-liveness check (Netty/async): if the client disconnected,
        //    skip — nobody is listening.
        if (!job.resp().isConnectionOpen()) {
            Metrics.droppedDisconnected();
            return;
        }

        doWork(job.req(), job.resp());
    }
}
```

This is the single highest-leverage anti-wasted-work pattern. In a FIFO overload, *most* dequeued items will fail check (1) — and that's exactly the goodput-killing scenario LIFO + CoDel fixes.

### 5.6 Use case F — Adaptive LIFO under overload

```java
import java.util.concurrent.*;

/** FIFO when healthy, LIFO (stack) when a standing queue is detected. */
public class AdaptiveLifoQueue<E> {
    private final Deque<E> deque = new ConcurrentLinkedDeque<>();
    private volatile boolean overloaded = false;   // flipped by a CoDel monitor

    public void enqueue(E item) { deque.addLast(item); }

    /** Workers call this to get the next item. */
    public E next() {
        // LIFO during overload: serve the FRESHEST request (most likely
        // still within the client's timeout). FIFO when healthy (fairness).
        return overloaded ? deque.pollLast() : deque.pollFirst();
    }

    public void setOverloaded(boolean v) { this.overloaded = v; }
    public int size() { return deque.size(); }
}
```

Pair this with a CoDel monitor that flips `overloaded` based on sojourn time, and a dropper that discards the *oldest* (bottom of stack) items so LIFO can't starve them forever.

### 5.7 Use case G — Edge/infra shedding with Envoy adaptive concurrency (config, not code)

```yaml
# Envoy HTTP filter: adaptive concurrency (Gradient controller).
# Sheds at the proxy BEFORE traffic reaches your JVM, language-agnostic.
http_filters:
- name: envoy.filters.http.adaptive_concurrency
  typed_config:
    "@type": type.googleapis.com/envoy.extensions.filters.http.adaptive_concurrency.v3.AdaptiveConcurrency
    gradient_controller_config:
      sample_aggregate_percentile: { value: 90 }   # use p90 latency
      concurrency_limit_params:
        max_concurrency_limit: 1000
        concurrency_update_interval: 0.1s           # re-evaluate every 100ms
      min_rtt_calc_params:
        interval: 30s        # periodically re-measure the no-load latency
        request_count: 50
        min_concurrency: 3   # drop to this while measuring baseline RTT
```

And a circuit-breaker / connection-limit block on the cluster:

```yaml
circuit_breakers:
  thresholds:
  - priority: DEFAULT
    max_connections: 1024
    max_pending_requests: 256    # queue cap before shedding
    max_requests: 1024           # concurrent request cap
    max_retries: 3               # CAP retries to prevent amplification!
```

Note `max_retries`: capping retries *at the mesh* is a primary defense against retry storms (§9).

### 5.8 Use case H — Token-bucket rate limiter (for contrast — this is *limiting*, not *shedding*)

```java
/** Per-client rate limit (a different mechanism than load shedding). */
public class TokenBucket {
    private final long capacity;        // max burst
    private final double refillPerSec;  // sustained rate
    private double tokens;
    private long lastNanos = System.nanoTime();

    public TokenBucket(long capacity, double refillPerSec) {
        this.capacity = capacity; this.refillPerSec = refillPerSec;
        this.tokens = capacity;
    }

    public synchronized boolean tryConsume() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastNanos) / 1e9;
        tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);  // refill
        lastNanos = now;
        if (tokens >= 1) { tokens -= 1; return true; }
        return false;   // over budget: reject (429 Too Many Requests)
    }
}
```

Included to make the contrast concrete: this caps *one caller* at an *agreed static rate* (per §1.4). It does **not** know whether the *server* is overloaded. You want *both* this and a load shedder.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Shed cheaply and early.** The cost of the shed decision must be tiny vs. the work avoided. A `Semaphore.tryAcquire()` or an atomic counter compare is ~nanoseconds; that's the budget.
- **Avoid contention in the shedder itself.** A single global lock or a `synchronized` token bucket can become the bottleneck at high RPS. Prefer `LongAdder`, lock-free structures, or sharded/striped limiters.
- **Beware the accepting thread.** If your shed decision *blocks* (blocking `acquire`, `CallerRunsPolicy` on the acceptor), you stall connection acceptance and make things worse. Shed decisions on the accept path must be non-blocking.
- **Set `Retry-After` and prefer fast 503s.** A fast rejection lets the *caller* fail fast and shed *its* load — this is how shedding composes across a system.

### 6.2 Correctness & concurrency

- **Always release permits in `finally`.** A leaked permit permanently shrinks your limit — a classic slow-bleed bug that looks like a capacity regression days later.
- **Idempotency under shed + retry.** Because shedding *encourages* clients to retry, every mutating endpoint should be **idempotent** (safe to apply twice) or use idempotency keys. Otherwise retries cause double-charges/double-writes.
- **Don't let application errors shrink the adaptive limit.** Distinguish *overload signals* (timeouts, queue drops) from *business errors* (a 404). Feeding 404s into the limiter as "drops" makes it shed unnecessarily (use `onIgnore()` vs `onDropped()`).
- **Priority propagation correctness.** If priority isn't propagated downstream, a downstream service sheds your critical request blind. Propagate via headers/context and *honor* it.

### 6.3 Memory

- **Bounded everything.** Every queue, buffer, and in-flight set must have a cap. Unbounded queues turn overload into OOM, and an OOM is a *worse* failure (full crash + slow restart + cold caches) than a shed.
- **Queued requests hold memory.** Each in-flight/queued request retains its buffers, headers, deserialized body. Deep queues = heap pressure = GC = the JVM death spiral. Capping concurrency caps heap held by in-flight work (Little's Law again).

### 6.4 Security

- **Shedding is a DoS mitigation *and* a DoS surface.** An attacker who learns you shed best-effort first might forge a high-priority header. **Never trust client-supplied priority for external callers** — derive priority from authenticated identity/SLA tier, not an unauthenticated header.
- **Fairness vs. abuse.** Pure global shedding can let one abusive tenant consume the admitted capacity. Combine global shedding with *per-tenant* rate limits / fair queuing to prevent one caller from monopolizing the admitted slots.

### 6.5 Observability (non-negotiable)

- **Make shed visible.** Emit a counter for *every* shed, tagged with reason (`adaptive_limit`, `codel`, `queue_full`, `circuit_open`) and priority. Silent drops (`DiscardPolicy`) are an anti-pattern precisely because they're invisible.
- **Track goodput, not just throughput.** Dashboards that only show "requests handled" *hide* a goodput collapse. Show "successful responses delivered within deadline."
- **Surface the dynamic limit.** Graph the current adaptive concurrency limit over time; sudden drops reveal dependency degradation before user complaints.
- **Degradation mode as an alertable gauge.** When you enter read-only/stale/feature-dimmed mode, that should be a visible, alertable state, not a silent log line.

### 6.6 Cost

- **Shedding is cheaper than over-provisioning.** Provisioning for peak-of-peak (e.g., a 50× spike) is wasteful; shedding lets you provision for the 95th-percentile load and *gracefully* refuse the rare extreme spike.
- **But shedding-induced retries cost too** (extra requests, extra mesh hops). Budget retries.

### 6.7 Testing

- **Load test past the knee.** Don't just test up to capacity; test *200–500% over* capacity and verify goodput stays flat (the hallmark of correct shedding) rather than collapsing. Plot goodput vs. offered load — a correct system shows a flat plateau; a broken one shows a cliff.
- **Fault injection / chaos.** Use Envoy fault injection, Toxiproxy, or Chaos Monkey to inject latency into dependencies and verify your concurrency limit shrinks and you degrade, not collapse.
- **Test the degradation path explicitly.** The serve-stale / fallback paths are the *least*-exercised in normal operation and the *most* important in an incident. Unit + integration test them; run game days that force them.
- **Test retry behavior end-to-end.** Verify that retries are capped, jittered, and budgeted; simulate a downstream brownout and confirm you don't generate a retry storm.

### 6.8 Production hardening checklist

1. Every request queue is **bounded**.
2. Shed decisions are **non-blocking** and on the cheap path.
3. **Adaptive** (not static) concurrency limit as the primary shedder; CPU as a secondary guard.
4. **Priority/criticality** assigned and **propagated** downstream; priority derived from trusted identity for external callers.
5. **Deadlines/timeouts** on every call, **propagated** (so workers can drop expired work).
6. **Retries** capped, jittered (full jitter), budgeted (e.g., retry budget ≤ 10% of requests), and **never retried on 503-with-Retry-After-overload**.
7. **Circuit breakers** on every dependency; **bulkheads** isolating each dependency's threads/permits.
8. **Idempotency** for all mutating endpoints.
9. **Degradation modes** are tested, flag-controlled, and observable.
10. **Dashboards** show goodput, shed-by-reason, dynamic limit, degradation state. Alerts on sustained shedding.
11. **Manual override / panic button** to force-dim features during an incident.

### 6.9 Anti-patterns to avoid

- **Unbounded queues** ("we'll just buffer it") → OOM, hidden overload.
- **Static RPS limits** that go stale the moment service time changes.
- **CPU as the only signal** for an I/O-bound service (you overload at 30% CPU).
- **FIFO under overload** → goodput collapse (serve everything *just after* it expired).
- **Silent drops** → invisible failures, impossible to debug.
- **Uncapped/unjittered retries** → retry-amplified outages.
- **Retrying on overload responses** (retrying a 503/429 with no backoff) → pouring fuel on the fire.
- **Degradation as an afterthought** (catch-all exception → 500) instead of a designed, tested path.
- **Trusting client-supplied priority** from unauthenticated callers.
- **Shedding *late*** (after expensive work) instead of at admission.
- **Per-request thread-per-request with unbounded thread creation** → context-switch storm, memory exhaustion.

---

## 7. Advanced topics & deep internals

### 7.1 The gradient algorithm in detail

Netflix's `Gradient2Limit` works like this internally:

- Maintain two latency aggregates: a **short-window** measurement (recent, reactive) and a **long-window** EWMA (the baseline, "what's normal").
- Compute `gradient = longRtt / shortRtt`, clamped to `[0.5, 1.0]`. When recent latency ≈ baseline, gradient ≈ 1 (healthy). When recent latency spikes above baseline, gradient < 1 (queueing detected).
- New limit ≈ `currentLimit × gradient + queueSize`, smoothed. So a gradient of 1 holds steady, a gradient of 0.7 cuts the limit ~30%. The `queueSize` term (proportional to `sqrt(limit)`) provides headroom to probe upward.
- This is **self-tuning** and resilient to noisy latency because the gradient is a *ratio*, not an absolute threshold.

### 7.2 Why concurrency limits beat RPS limits (deep)

Consider a service whose downstream DB latency jumps from 10ms to 100ms (a common capacity-driven overload). With an **RPS limit** of, say, 1000 RPS: by Little's Law, in-flight jumps from `1000 × 0.01 = 10` to `1000 × 0.1 = 100` — a 10× increase in threads/connections/memory held, *with no change in offered RPS*. Your RPS limit didn't protect you at all; you've blown through your thread pool. With a **concurrency limit** of, say, 50: when latency rises, in-flight hits 50 and you start shedding *automatically* — the limit *is* the protection, and it's denominated in the resource (concurrency) that actually runs out. This is the deep reason modern guidance favors concurrency limits.

### 7.3 Metastability and hysteresis

A system in a **metastable failure** (§1.2) won't recover on its own even after load returns to normal, because it's trapped in a high-wasted-work, high-retry state. Recovery often needs an *external* push: shed *aggressively* (cut the limit hard), let queues fully drain, let caches warm, *then* ramp back. This is why some shedders include **hysteresis**: they shed more aggressively to *exit* overload than the threshold at which they *entered* it (like a thermostat with a deadband), preventing oscillation around the knee. Without hysteresis, a shedder right at the knee flaps on/off, causing latency oscillation.

### 7.4 Cold-start, warmup, and JIT/JVM specifics

- A freshly started JVM is *slower* (JIT not warm, caches cold, connection pools empty). If your adaptive limiter immediately admits full traffic, the cold instance overloads and sheds — or worse, dies. Solutions: **slow-start / warmup** (gradually raise the limit after startup), readiness probes that gate traffic, and load balancers that ramp new instances (e.g., Envoy slow-start mode).
- **GC pauses masquerade as overload.** A long stop-the-world pause inflates latency, which an adaptive limiter reads as overload and sheds. That's *correct* (you genuinely can't serve during a pause) but can cause sawtooth limits. Tune GC (G1/ZGC/Shenandoah for low pause), and don't let allocation from deep queues drive GC (bound the queues).

### 7.5 Coordinated omission (a measurement trap)

When you measure latency with a load generator that *waits* for each response before sending the next (closed-loop), you **omit** the latency of requests that *would have been sent* during a stall. This makes your p99 look far better than reality during overload — a phenomenon called **coordinated omission** (Gil Tene). It matters here because *evaluating* a load shedder with a naive load test will understate the tail and overstate how well you handle overload. Use load generators that correct for it (wrk2, Gatling with open-model) and measure *service time from intended send time*.

### 7.6 Adaptive LIFO + CoDel, the combined Facebook recipe (deep)

The combination works because each covers the other's weakness:
- **CoDel** decides *when* to shed (standing queue detected via sojourn time) and *how much* (drop more as overload persists), but doesn't choose *which* request to process.
- **LIFO** chooses *which* request to process (freshest, most likely alive), but pure LIFO can *starve* old requests forever.
- Together: CoDel drops the starved-old requests (which are expired anyway) while LIFO serves the fresh ones → maximal goodput, no indefinite starvation. The system uses FIFO normally (fairness) and only flips to LIFO under detected overload.

### 7.7 Fair queuing & weighted shedding

Beyond simple priority tiers, advanced systems use **weighted fair queuing (WFQ)** or **deficit round-robin (DRR)** to allocate admitted capacity proportionally among tenants by weight, so a premium tenant gets, say, 3× the admitted slots of a free tenant, and no single tenant can starve others. Combine with per-tenant token buckets for upper bounds. Google's "fair shedding" and AWS's per-customer throttling embody this.

### 7.8 Criticality propagation & "request budgets" (Google-style)

Google propagates a **criticality** label (e.g., `CRITICAL_PLUS`, `CRITICAL`, `SHEDDABLE_PLUS`, `SHEDDABLE`) through every RPC. Each server sheds the lowest criticality first. There's also the notion of a **request "cost"/budget** so that admission accounts for expensive requests. The deep lesson (from *Site Reliability Engineering*): **without criticality propagation, prioritized shedding is a local optimization that fails globally** — the leaf service sheds your most important request because the label didn't travel.

### 7.9 The "shed the right load" theorem, informally

Given fixed admitted capacity `C` and requests with values `v_i` and costs `c_i`, maximizing delivered value under load is a *knapsack* problem: admit the set maximizing `Σv_i` subject to `Σc_i ≤ C`. Real systems approximate this greedily (admit by value/cost ratio, highest first). This formalizes why naive count-based shedding is suboptimal: it ignores both value and cost.

### 7.10 Brownout controllers as control theory

A brownout dimmer driven by a PID/integral controller (proportional-integral) treats target latency as a setpoint and the dimmer as the actuator. Tuning the controller's gains matters: too aggressive → oscillation; too sluggish → slow to react. This is the same control-theoretic framing as TCP congestion control and adaptive concurrency limits — overload protection is fundamentally a *feedback control* problem.

### 7.11 Virtual threads (Project Loom) and shedding (JVM-specific, modern)

Java 21+ virtual threads make "thread-per-request" cheap (millions of threads), which **removes the thread pool as a natural concurrency limiter**. This is a subtle trap: without the implicit bound of a fixed pool, you can now admit unbounded concurrency and overwhelm a *downstream* resource (DB connections, memory). **With virtual threads, explicit load shedding / semaphores become *more* important**, not less, because you've removed the accidental backpressure that platform-thread pools provided. Use a `Semaphore` or adaptive limiter to bound concurrency to a downstream's real capacity.

---

## 8. Tradeoffs & decision frameworks

### 8.1 The four mechanisms, expanded

| Dimension | Rate limiting | Load shedding | Backpressure | Graceful degradation |
|---|---|---|---|---|
| **Goal** | Enforce per-caller fairness/quota | Survive aggregate overload | Slow the producer to match consumer | Return useful partial result |
| **Granularity** | Per client/key/tenant | Global / per-priority | Per stream/connection | Per feature/response |
| **Static or dynamic** | Usually static (agreed quota) | Dynamic (load-driven) | Dynamic (flow-driven) | Dynamic (dependency-driven) |
| **Signal** | Request count vs budget | Concurrency/latency/queue | Buffer fullness, demand/credits | Dependency health, timeouts |
| **On reject** | 429 Too Many Requests | 503 Service Unavailable | Pause/slow upstream (no drop) | 200 with reduced content |
| **Composes by** | Client respects its quota | Caller fails fast & sheds too | Propagating "slow down" upstream | Caller sees degraded but OK |
| **Best for** | Multi-tenant quotas, abuse | Spikes, dependency slowdowns | In-process pipelines, streaming | User-facing partial failures |
| **Can't do** | Detect *server* overload | Slow producer without dropping | Apply across stateless HTTP easily | Help when core path is down |

**They are layered, not alternatives.** A mature system: edge rate-limits per client → load-sheds adaptively at admission → uses backpressure within async pipelines → degrades gracefully when dependencies fail.

### 8.2 Choosing a detection signal

| Use… | When | Avoid when |
|---|---|---|
| **Adaptive concurrency (Gradient/Vegas)** | Default; you don't know/can't fix a magic number; dependency latency varies | Extremely bursty sub-ms work where measurement noise dominates |
| **CoDel (sojourn time)** | You have an explicit request queue; want burst-tolerant shedding | No explicit queue (e.g., pure semaphore admission) |
| **Static concurrency limit** | Very stable service time; simplicity prized; a safe hard cap | Service time varies (dependency slowdowns) |
| **Static RPS** | A contractual external quota (it's really rate limiting) | As your *overload* protection — it goes stale |
| **CPU/load** | CPU-bound work; as a secondary guard | I/O-bound work; sole signal |

### 8.3 Queue discipline decision

| Discipline | Use when | Avoid when |
|---|---|---|
| **FIFO** | Normal operation; fairness/ordering matters | Sustained overload (goodput collapse) |
| **LIFO (adaptive)** | Detected overload + bounded by CoDel dropping | Without a dropper (starvation); when ordering matters |
| **Priority queue** | Mixed-criticality traffic | When it lets low-priority starve forever (add aging) |

### 8.4 Degradation strategy selection

| Strategy | Use when | Cost / risk |
|---|---|---|
| Serve stale | Data tolerates being slightly old; cache exists | Staleness bugs; users see old data |
| Reduce fidelity | A cheaper variant is acceptable | More code paths to maintain |
| Disable non-critical features | Clear critical/non-critical split | Must classify features in advance |
| Read-only mode | Writes overload a primary DB | Users can't write; needs UX |
| Async offload | Operation can be eventual | Complexity; need a queue/worker |
| Default response | A sensible default exists | May mislead if not signaled |

### 8.5 Build vs. buy / where to enforce

| Layer | Pros | Cons |
|---|---|---|
| **Service mesh / proxy (Envoy/Istio)** | Language-agnostic; sheds before app; centralized policy | Less app-context (can't see business priority easily); extra hop |
| **In-app library (Resilience4j, concurrency-limits)** | Rich context (priority, cost); precise | Per-language; must be wired in everywhere |
| **API gateway** | Good for per-client rate limiting at the edge | Coarse for dynamic load shedding |
| **Kernel/LB (somaxconn, conn limits)** | Cheapest, earliest | Crude, no app awareness |

**Recommended:** rate-limit per client at the gateway/mesh, load-shed adaptively in-app (where priority/cost context lives), with a coarse mesh/kernel backstop.

---

## 9. Failure modes & debugging

### 9.1 Retry-amplified outage (the canonical incident)

**Mechanism:** A small slowdown (a slow dependency, a GC pause, a deploy) causes some requests to time out. Clients retry. Now the server sees the original load *plus* retries. With N retries, a momentary blip becomes (N+1)× load — pushing a marginally-overloaded system deep into overload, which causes *more* timeouts, which causes *more* retries. The system locks into a **metastable** state and won't recover even after the trigger clears. Each layer of the stack with retries *multiplies*: client retries × LB retries × service-A retries × service-B retries = exponential amplification down the call graph.

**Real-world flavor:** AWS's DynamoDB 2015 disruption and many published postmortems trace to retry storms / metadata-service overload amplified by retries. AWS's Builders' Library explicitly warns about retry amplification and recommends retry budgets. Google SRE devotes a chapter to "Addressing Cascading Failures" centered on this.

**Diagnosis:**
- Ratio of *total* requests to *unique/first-attempt* requests spikes (retries dominate).
- Goodput collapses while throughput/CPU stays pegged.
- Latency p99 hits the client timeout value and clusters there (everything times out).
- Downstream sees a multiple of upstream's request rate.

**Fixes (defense in depth):**
1. **Retry budgets:** cap retries to a small fraction (e.g., ≤10%) of total requests — when the budget is exhausted, stop retrying. (Implemented in gRPC, Finagle, Envoy.)
2. **Exponential backoff with full jitter** (`sleep = random(0, base × 2^attempt)`) to de-synchronize retries (prevents the thundering herd of synchronized retries).
3. **Don't retry on overload signals.** A 503/429 with `Retry-After` (or a "retriable=false" overload flag) means "I'm overloaded" — retrying is harmful. Retry only on signals indicating the *specific request* failed transiently.
4. **Circuit breakers** to stop hammering a sick dependency.
5. **Load shedding** so the server fails *fast* (so callers' own timeouts/circuit breakers engage quickly).
6. **Cap retries at every layer** (don't let retries compound across the call graph) — often "retry only at the layer closest to the user."

### 9.2 Thundering herd / cache stampede

**Mechanism:** A hot cache key expires (or a cache flushes, or a popular item drops); thousands of concurrent requests all miss simultaneously and hit the origin/DB at once, overloading it.

**Diagnosis:** Sharp synchronized spike to the origin correlated with a cache expiry/flush; DB connection pool exhaustion; a single hot key.

**Fixes:** request coalescing / single-flight (one request fetches, others wait — e.g., Caffeine `LoadingCache` does this per-key), staggered/jittered TTLs, serve-stale-while-revalidate, probabilistic early expiration, and load shedding at the origin as a backstop.

### 9.3 The permit/connection leak (slow bleed)

**Mechanism:** A code path forgets to release a semaphore permit / return a pooled connection on some error branch. Each leak permanently shrinks effective capacity. Over hours/days the limit erodes until the service sheds everything despite low load.

**Diagnosis:** Available permits / idle pool connections trend monotonically downward; capacity "regresses" with no deploy; correlated with a specific error path. Thread dumps show threads parked on `acquire`.

**Fix:** `try/finally` around every acquire; pool leak detection (HikariCP `leakDetectionThreshold`); metrics on available permits.

### 9.4 Shedding the *wrong* load

**Mechanism:** Priority not propagated → a leaf service sheds critical requests; or priority is count-based so a few expensive requests slip through and expensive ones starve cheap critical ones; or one abusive tenant fills the admitted slots.

**Diagnosis:** Critical-path SLOs violated while best-effort traffic still succeeds; one tenant's success rate high while others' collapse.

**Fix:** propagate criticality, derive it from trusted identity, add per-tenant fairness, weight by cost.

### 9.5 Unbounded queue → OOM

**Mechanism:** An unbounded `LinkedBlockingQueue` (or an async buffer) grows during overload until the JVM throws `OutOfMemoryError` and crashes — converting a survivable overload into a full crash + slow restart + cold caches (which then can't handle the recovery load → crash loop).

**Diagnosis:** Heap usage climbs with queue depth; long GC pauses precede the crash; heap dump shows millions of queued request objects.

**Fix:** bound every queue; reject when full; size the bound by memory budget, not optimism.

### 9.6 CPU-signal blind spot

**Mechanism:** An I/O-bound service is overloaded (all threads blocked on a slow DB) at *30% CPU*. A CPU-threshold shedder never triggers; the service piles up in-flight requests and falls over.

**Diagnosis:** High latency + high in-flight at low CPU; thread dump shows most threads in `WAITING`/`TIMED_WAITING` on socket reads.

**Fix:** use concurrency/latency signals, not CPU, for I/O-bound services.

### 9.7 Debugging toolkit (the actual commands)

- **Thread dumps:** `jstack <pid>` (or `kill -3 <pid>`) → see where threads are parked (queue, semaphore, DB). Repeated dumps reveal stuck patterns.
- **JFR:** `jcmd <pid> JFR.start duration=60s filename=rec.jfr` → low-overhead profiling of thread states, locks, allocation, GC.
- **async-profiler:** flame graphs of where CPU/wall time goes under load (`./profiler.sh -d 30 -f flame.html <pid>`).
- **GC logs:** `-Xlog:gc*:file=gc.log` → confirm/deny GC-driven latency.
- **Metrics:** in-flight gauge, shed counters by reason, dynamic limit, queue sojourn histogram, goodput — graphed together on one dashboard.
- **Distributed tracing (OpenTelemetry/Jaeger):** find *which hop* queues; see retry fan-out in the trace tree.
- **`ss -ltn` / `netstat`:** inspect the OS accept queue (`Recv-Q` on a listening socket = current backlog, `Send-Q` = max). A full accept queue means you're shedding at the kernel.
- **Load test past the knee:** wrk2 / Gatling (open model) to reproduce overload and verify the goodput plateau.

### 9.8 A short incident narrative (composite, illustrative)

A checkout service ran healthy at 70% utilization. A database index rebuild slowed queries from 8ms to 80ms. Each request now held its worker thread 10× longer, so effective concurrency soared and the bounded pool's queue filled. The *static* concurrency limit (tuned for 8ms) was now 10× too generous, so the service kept admitting requests it couldn't serve. Requests timed out at the mobile client's 2s deadline; the app retried twice with no jitter, tripling load in a synchronized wave. Goodput fell to near zero while CPU sat at 45% (it was I/O-bound, so the CPU-only autoscaler never scaled out). The on-call saw "low CPU, high errors," which was confusing. Recovery: manually shed hard (drop the limit to 10), drain the queue, the DB finished its rebuild, then ramp back. Permanent fixes: switched to an **adaptive concurrency limit** (it would have shrunk automatically as latency rose), added **retry budgets + full jitter**, made the autoscaler use **latency/concurrency** signals not CPU, and added a **goodput dashboard**. Every element of §9.1–9.6 appears here.

---

## 10. Interview drill

### Q1. What's the difference between load shedding, rate limiting, and backpressure?
**Model answer:** Rate limiting enforces a *per-caller* budget (usually static, e.g., 1000 req/s per API key) and protects against any one client over-consuming; it answers "has *this client* exceeded its share?" Load shedding is *global and dynamic*: it drops work when the *server as a whole* is overloaded regardless of who's calling, based on live signals (concurrency, latency, queue depth); it answers "is the *server* about to fall over right now?" Backpressure is flow control that *slows the producer* to match the consumer (e.g., HTTP/2 flow control, reactive streams' `request(n)`) — it propagates "slow down" upstream instead of dropping. They're layered, not alternatives.
- *Probe: When does rate limiting fail to protect you?* When aggregate load from many *within-budget* clients still exceeds capacity, or when a dependency slows so your real capacity drops — each client is under its limit but the server is overloaded. You need load shedding for that.
- *Probe: Why can't HTTP REST easily use backpressure?* Stateless request/response has no standing channel to signal "send slower"; the best you can do is reject (shed) and rely on the client's backoff. Backpressure shines in streaming/async pipelines with a persistent flow-control channel.
- *Probe: Can backpressure cause problems?* Yes — if it propagates all the way to an *open-loop* source (real users), you can't actually slow them, so unbounded buffering or a fallback shed is still needed; and backpressure can deadlock if cyclic.

### Q2. Why prefer a concurrency limit over an RPS limit for overload protection?
**Model answer:** By Little's Law (`concurrency = throughput × latency`), the *same RPS* implies wildly different resource use depending on service time. If a downstream slows from 10ms to 100ms, in-flight requests jump 10× at constant RPS — blowing through threads/connections/memory — yet an RPS limit notices nothing. A concurrency limit is denominated in the resource that actually runs out (in-flight work), so it protects automatically when latency rises. Adaptive concurrency limits (Gradient/Vegas) go further: they discover capacity and track it without any magic number.
- *Probe: How does an adaptive limit decide to shrink?* It compares recent latency to a baseline (no-load) latency; when the ratio (gradient) drops — meaning requests are queueing — it multiplicatively decreases the limit (AIMD), like TCP congestion control.
- *Probe: What's a downside of concurrency limits?* You still need sane min/max bounds and warmup handling; and for extremely bursty sub-millisecond work, latency measurement noise can make the limit jittery.

### Q3. Under overload, why might LIFO beat FIFO, and what's the catch?
**Model answer:** Under FIFO, the worker always picks the *oldest* queued request — which under overload has likely *already exceeded the client's timeout*. So you do full work for answers nobody reads (wasted work), and goodput collapses to ~0 while throughput stays high. LIFO picks the *newest* request, most likely still within its deadline, so the work you do is more likely to be *useful* → higher goodput. The catch: pure LIFO can *starve* old requests indefinitely. The fix is **adaptive LIFO + CoDel**: use FIFO normally, flip to LIFO only under detected overload, and let CoDel drop the starved-old (already-expired) items.
- *Probe: What signal flips you into LIFO?* A standing queue detected via CoDel — when the queue *sojourn time* stays above a target (e.g., 5ms) for longer than an interval (e.g., 100ms).
- *Probe: Why not always LIFO?* It breaks fairness/ordering and starves old requests during *non*-overload too; you only want it as an overload measure.

### Q4. Explain a retry-amplified outage and how to prevent it.
**Model answer:** A small slowdown causes timeouts; clients retry; retries add load; more requests time out; more retries — a positive feedback loop that drives the system into a *metastable* state that won't recover even after the trigger clears. It compounds across call-graph layers (each retrying layer multiplies). Prevent with: retry budgets (cap retries to ~10% of requests), exponential backoff with **full jitter**, *not* retrying on overload responses (503/429 with Retry-After), circuit breakers, and load shedding so the server fails fast and lets callers' breakers engage. Often: retry only at the layer closest to the user.
- *Probe: Why full jitter, not just exponential backoff?* Plain backoff still synchronizes retries into waves (thundering herd at each backoff boundary); full jitter (`random(0, base·2^n)`) spreads them out.
- *Probe: What's a retry budget concretely?* A token-bucket-like cap so the client library *refuses to retry* once retries exceed, say, 10% of recent requests — converting "retry forever" into "retry only while there's slack."
- *Probe (senior signal): Where should retries live in a deep call graph, and why?* Ideally at a single layer (closest to the user) or with strictly decreasing budgets going down, because retries at every layer *multiply*; uncoordinated per-layer retries turn a 2× client retry into 8–16× at the leaf.

### Q5. (Senior signal) You can shed load easily; how do you shed the *right* load?
**Model answer:** Assign each request a **criticality/priority** (CRITICAL checkout vs. BEST_EFFORT recommendations), derived from *trusted* signals (route, authenticated SLA tier) — never from an unauthenticated client header. **Propagate** criticality through every downstream RPC so leaf services don't shed your critical request blind. Shed lowest priority first, progressively (best-effort at 70% load, standard at 95%, never critical until a hard cap). Weight by **cost** (an expensive query consumes more capacity) so count-based limits don't admit a few crushing queries. Add **per-tenant fairness** so one abusive tenant can't fill the admitted slots. Formally it's a knapsack: maximize delivered value subject to a capacity budget; greedily admit by value/cost.
- *Probe: What breaks without criticality propagation?* A leaf service, seeing only undifferentiated load, sheds your most important request — a local optimization that fails globally.
- *Probe: Security risk of priority?* External callers could forge high priority; derive it from authenticated identity, not client input.

### Q6. (Senior signal) Why is CPU a poor primary overload signal, and what would you use instead?
**Model answer:** CPU only reflects load for CPU-bound work. An I/O-bound service can be fully overloaded — every thread blocked on a slow DB, in-flight piling up, latency exploding — at *30% CPU*. A CPU-threshold shedder/autoscaler never fires, and the service falls over while looking idle. GC pauses and noisy neighbors also corrupt CPU readings. Use **concurrency/latency** signals (adaptive concurrency limit, queue sojourn time / CoDel) as primary, with CPU as a *secondary* guard for genuinely CPU-bound paths.
- *Probe: How does this affect autoscaling?* Autoscalers keyed on CPU won't scale out an I/O-bound service under overload; scale on latency/concurrency/queue depth (or RPS-per-instance with a tested ceiling).
- *Probe: Give a concrete diagnostic.* High p99 + high in-flight + low CPU, with a thread dump showing most threads in TIMED_WAITING on socket reads = I/O-bound overload.

### Q7. What is graceful degradation and how do you design for it?
**Model answer:** Degradation is returning a *useful, reduced* response instead of failing when the system is stressed or a dependency is impaired: serve stale, reduce fidelity (fewer/cheaper results), disable non-critical features (brownout), default responses, read-only mode, async offload. Design it as a *first-class, tested* code path controllable by feature flags (a "panic button"), with the degraded state observable/alertable. Classify features as critical vs. non-critical up front so you know what to dim. The principle: protect the core function (checkout, login) by sacrificing optional embellishments (recommendations, analytics).
- *Probe: What's a brownout?* Continuously-tunable degradation — a "dimmer" (0–1) on optional work, adjusted by a controller to hold latency at a target, like a thermostat.
- *Probe: Why is the degradation path risky?* It's the least-exercised path in normal operation but the most important during incidents — so it rots silently. Test it explicitly and run game days.

### Q8. How do you detect overload, and what are the tradeoffs of each signal?
**Model answer:** Signals: queue depth (direct but doesn't capture *how long* items waited), queue *sojourn* time (correlates with user pain; basis of CoDel; needs timestamps), latency p99/EWMA (true user experience but a *lagging* indicator), concurrency/in-flight (adaptive via Little's Law, capacity-agnostic), CPU (good for CPU-bound, useless for I/O-bound), GC pressure (catches JVM death spirals). The most robust are sojourn time and adaptive concurrency because they're self-calibrating. Combine: adaptive concurrency primary, CPU secondary.
- *Probe: Why is latency a lagging indicator?* By the time p99 visibly spikes, your queues are already deep and you're partway into the death spiral; concurrency/sojourn react earlier.
- *Probe: What's coordinated omission and why does it matter here?* Closed-loop load tests that wait for each response omit the latency of requests that *would* have been sent during a stall, understating the tail — so they make a shedder look better than it is. Use open-model generators (wrk2).

### Q9. Walk through what a request goes through in a load-shedding server.
**Model answer:** OS accept queue (somaxconn) → cheap pre-parse load check (shed early/cheaply with 503) → classify priority → admission decision (adaptive limiter) → bounded queue (reject if full; timestamp for CoDel) → worker checks deadline/connection-liveness (drop if expired/abandoned — no wasted work) → execute with per-dependency bulkheads + circuit breakers → degrade with fallbacks if a dependency is sick → respond, feeding latency/success back to the limiter. Two principles: shed as early/cheaply as possible, and never do work whose result will be discarded.
- *Probe: Why check the deadline at the worker, not just at admission?* A request can be admitted but sit in the queue past the client's timeout; checking at dequeue avoids working on already-dead requests (huge under FIFO overload).
- *Probe: Where would you put priority propagation?* In the context/headers carried into every downstream call so they honor the same criticality.

### Q10. (Senior signal) Java 21 virtual threads make thread-per-request cheap. Does that remove the need for load shedding?
**Model answer:** No — the opposite. A fixed platform-thread pool *implicitly* bounded concurrency (and gave you accidental backpressure). Virtual threads let you spawn millions cheaply, removing that natural limit, so you can now admit unbounded concurrency and overwhelm a *downstream* (DB connection pool, memory, a rate-limited API). You must add **explicit** concurrency limiting — a semaphore or adaptive limiter sized to the real downstream capacity — *and* keep load shedding. Virtual threads change *where* the bottleneck is, not whether you need to protect it.
- *Probe: What's the new failure mode?* Memory/connection exhaustion: a million virtual threads each holding a request's buffers and trying for a 50-connection DB pool.
- *Probe: How do you bound it idiomatically?* Wrap downstream calls in a `Semaphore.tryAcquire()` (or Resilience4j bulkhead) sized to the downstream's capacity; shed when no permit.

### Q11. What's the difference between shedding load and shedding the *right* load, and how does this connect to goodput?
**Model answer:** Shedding load = dropping *some* requests to stay alive (easy). Shedding the *right* load = dropping the *least valuable* requests (low priority, abusive tenants, doomed/expired requests) while preserving the most valuable (critical, fresh, premium). The connection to goodput: goodput is *useful work completed in time*. Dropping the right load (e.g., expired requests under LIFO, best-effort traffic) maximizes goodput per unit capacity; dropping the wrong load (critical requests, or — under FIFO — serving expired ones) tanks goodput even though throughput looks fine.
- *Probe: How is dropping expired requests "shedding the right load"?* An expired request will produce an answer nobody reads — its value is zero. Dropping it (deadline check / LIFO+CoDel) frees capacity for requests whose answers still matter.

### Q12. (Senior signal) Design overload protection for a checkout service that calls inventory, pricing, payments, and recommendations. Walk the tradeoffs.
**Model answer:** Classify: payments + inventory + pricing = CRITICAL (checkout fails without them); recommendations = BEST_EFFORT. Edge: per-client rate limit at the gateway. In-app: **adaptive concurrency limit** as the primary shedder (handles dependency slowdowns automatically). **Bulkhead** each dependency in its own semaphore/pool so a slow recommendations service can't consume threads needed for payments. **Circuit breaker + serve-stale/default** for recommendations (degrade, never fail checkout for it). **Deadlines propagated** to every downstream with a budget (e.g., 2s total, sub-budgets per hop), and workers drop expired requests. **Retry budgets + full jitter**, and *no retries* on overload responses. **Idempotency keys** on payment so a retry can't double-charge. **Criticality propagated** so inventory sheds someone's recommendations before our checkout. Observability: goodput, shed-by-reason/priority, dynamic limit, circuit states, degradation gauge. Tradeoffs: payments must be the *last* thing shed and idempotent; recommendations the *first*; concurrency limits over RPS because dependency latency varies; mesh-level limits as a backstop but in-app for priority context.
- *Probe: What gets shed first under extreme overload?* Recommendations (drop entirely / serve default), then progressively standard traffic, never payments/inventory until the absolute hard cap.
- *Probe: Why idempotency specifically here?* Because shedding *encourages* retries; without idempotency keys, a retried payment double-charges — a correctness/financial bug, not just a perf one.

---

## 11. Glossary

- **Admission control:** Deciding, as a request enters, whether to accept or reject it.
- **AIMD (Additive Increase, Multiplicative Decrease):** A control policy (from TCP) that grows a limit slowly and additively when healthy but cuts it sharply (multiplicatively) on trouble; basis of adaptive concurrency limits.
- **Backoff (exponential):** Increasing the wait between retries exponentially (`base·2^n`) to reduce load on a struggling server.
- **Backpressure:** Flow control that signals a fast producer to slow down to match a slow consumer, propagating "slow down" upstream rather than dropping.
- **Brownout:** Intentional, often continuously-tunable reduction of functionality to stay within capacity (named after power-grid voltage reduction).
- **Bulkhead:** Resource isolation (e.g., separate thread pools/semaphores per dependency or tenant) so one failure can't sink the whole system; named after a ship's watertight compartments.
- **Circuit breaker:** A wrapper that "trips open" after repeated dependency failures, failing fast for a cooldown instead of hammering a sick dependency.
- **Closed-loop load:** A fixed set of clients each waiting for a response before sending the next; self-limiting under slowdown.
- **CoDel (Controlled Delay):** An algorithm that sheds based on how long items *sat in the queue* (sojourn time) rather than queue depth, tolerating bursts but reacting to standing queues.
- **Concurrency limit:** A cap on the number of simultaneously in-flight requests (vs. a rate/RPS limit).
- **Congestion collapse:** A state where increasing offered load *decreases* useful throughput, often to near zero.
- **Coordinated omission:** A measurement error where closed-loop load tests omit the latency of requests that would have been sent during a stall, understating the tail.
- **Criticality / priority propagation:** Carrying a request's importance label through every downstream call so all services shed consistently.
- **Deadline propagation:** Passing the remaining time budget to downstream calls so they can give up (and you can drop expired work).
- **EWMA (Exponentially Weighted Moving Average):** A smoothed average that weights recent samples more; used for latency baselines.
- **Fair queuing (WFQ/DRR):** Allocating admitted capacity proportionally among tenants by weight to prevent starvation/monopolization.
- **FIFO / LIFO:** First-In-First-Out / Last-In-First-Out queue disciplines.
- **Full jitter:** Randomizing backoff in `[0, base·2^n]` to de-synchronize retries.
- **GC (Garbage Collection):** The JVM's automatic memory reclamation; long stop-the-world pauses inflate latency and can masquerade as overload.
- **Goodput:** Useful work completed *and delivered in time to matter*, as opposed to raw throughput.
- **Gradient (limit algorithm):** An adaptive concurrency algorithm comparing recent vs. baseline latency (their ratio) to adjust the limit.
- **Graceful degradation:** Returning a useful, reduced-quality response instead of failing when stressed.
- **Hysteresis:** A deadband so a controller exits a state at a different threshold than it enters, preventing oscillation/flapping.
- **Idempotency:** The property that applying an operation multiple times has the same effect as once; essential when retries are likely.
- **Kingman's formula:** A queueing-theory approximation showing wait time rising hyperbolically as utilization → 1.
- **Little's Law:** `L = λ·W` — in-flight = throughput × latency; the basis of concurrency limiting.
- **Load shedding:** Deliberately dropping/refusing work under aggregate overload to keep accepted work fast and the server alive.
- **Metastable failure:** A failure that persists even after the original trigger is removed, because the system is trapped doing wasted work/serving retries.
- **M/M/c queue:** A queueing model (Poisson arrivals, exponential service, c servers) used to reason about wait times.
- **Open-loop load:** Independent arrivals that don't slow down when the system does (e.g., organic users).
- **PID controller:** A feedback controller (Proportional-Integral-Derivative) used in brownout/control-theoretic overload schemes.
- **Rate limiting:** Capping a *per-caller* request rate against an agreed budget.
- **RejectedExecutionHandler:** The JDK SPI deciding what happens when a `ThreadPoolExecutor`'s pool+queue are full — effectively a load shedder.
- **Retry amplification / retry storm:** Retries multiplying load during a slowdown, driving cascading/metastable failure.
- **Retry budget:** A cap on retries as a fraction of total requests, to bound amplification.
- **Semaphore:** A counter-based concurrency primitive granting a fixed number of permits; `tryAcquire()` enables non-blocking shedding.
- **Serve-stale / stale-while-revalidate:** Returning a cached (possibly expired) value while refreshing in the background.
- **Single-flight / request coalescing:** Letting one request fetch a value while concurrent duplicates wait, preventing stampedes.
- **Sojourn time:** How long an item waited in a queue before being processed.
- **somaxconn:** Linux sysctl capping the TCP accept-queue length.
- **Standing queue:** A queue whose items persistently linger (vs. a transient burst that drains fast) — the signature of true overload.
- **Tail latency (p99/p99.9):** Latency of the slowest 1% / 0.1% of requests.
- **Thundering herd / cache stampede:** Many requests simultaneously hitting an origin (often after a cache expiry), overloading it.
- **Throughput:** Requests handled per second (regardless of usefulness).
- **Token bucket:** A rate-limiting algorithm with a refilling bucket of tokens; one token per request, reject when empty.
- **Utilization (ρ):** Fraction of capacity in use; `ρ ≥ 1` means overload.
- **Virtual threads (Project Loom):** Cheap JVM-managed threads (Java 21+) that remove thread-pool-based implicit concurrency bounds, making explicit shedding more important.
- **Wasted work:** Processing a request whose result will be discarded (client already timed out/disconnected).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Mental model:** Load shedding = *which requests die so the rest live*. Graceful degradation = *how to still return something useful when you can't do the full job*. Goal = **maximize goodput**, not throughput.

**The four mechanisms:** Rate limit (per-client, static) · Load shed (global, dynamic) · Backpressure (slow the producer) · Degrade (reduce response quality). **Use all four, layered.**

**Key law:** Little's Law → `concurrency = throughput × latency`. Prefer **concurrency limits** over RPS limits (RPS goes stale when latency changes). Prefer **adaptive** limits (Gradient/Vegas — no magic number) over static.

**Detect overload by (best → worst as *primary*):** adaptive concurrency / queue sojourn time (CoDel) → latency p99 (lagging) → CPU (useless for I/O-bound). Numbers: CoDel `TARGET≈5ms`, `INTERVAL≈100ms`.

**Queue discipline:** FIFO healthy, **LIFO under overload** (serve freshest, most-likely-alive) + CoDel to drop the starved-old. FIFO under overload → goodput collapse (serve everything *just* after it expired).

**Shed the *right* load:** priority by *trusted* identity (never client header), **propagate criticality** downstream, weight by cost, add per-tenant fairness. Shed best-effort first, never critical until hard cap.

**Degrade by:** serve stale → reduce fidelity → disable non-critical (brownout) → default response → read-only → async offload. Make it a tested, flag-controlled, observable path.

**Retry-amplified outage:** slowdown → timeouts → retries → more load → metastable collapse. Defenses: **retry budget (~10%)**, **full jitter**, **don't retry on 503/429-overload**, circuit breakers, fail-fast shedding, cap retries per layer.

**Hardening must-haves:** bound every queue (no unbounded → OOM), shed non-blocking & early, release permits in `finally`, idempotency on mutations, deadlines propagated + dropped-when-expired, bulkheads per dependency, goodput + shed-by-reason dashboards, panic button.

**JVM toolkit:** `Semaphore.tryAcquire` · `ThreadPoolExecutor` + bounded queue + `RejectedExecutionHandler` · Netflix `concurrency-limits` (Gradient2) · Resilience4j (Bulkhead/CircuitBreaker/RateLimiter/TimeLimiter) · Caffeine (serve-stale) · Envoy adaptive concurrency + `max_retries`. **Loom caveat:** virtual threads remove implicit bounds → explicit shedding matters *more*.

**Debug:** `jstack` (parked threads) · JFR/async-profiler · GC logs · `ss -ltn` (accept queue) · tracing (retry fan-out) · load test *past the knee* with open-model (wrk2) and watch for a goodput **plateau** vs **cliff**.

### 12.2 Self-test (no answers — recall practice)

1. A downstream's latency jumps 10×; your offered RPS is unchanged. Explain, using a named law, why an RPS limit fails to protect you and a concurrency limit does — and quantify the change in in-flight requests.
2. Your service is at 503-spewing overload but CPU sits at 35% and your autoscaler (CPU-keyed) won't scale out. Diagnose the likely cause, name the misconfigured signal, and give the thread-dump pattern you'd expect to confirm it.
3. Walk through, step by step, why a server under **FIFO** overload can have ~0 goodput while throughput stays maxed — then explain exactly how adaptive LIFO + CoDel fixes it and why CoDel is needed alongside LIFO.
4. Design retry behavior for a 4-layer call graph (client → gateway → service A → service B) that *cannot* produce a retry storm. Specify where retries live, the budget, the backoff/jitter formula, and which response codes you must *not* retry.
5. You must shed load on a multi-tenant API where one tenant is paying 10× and another is an abusive crawler, during a spike. Describe a scheme that (a) protects the premium tenant, (b) prevents the crawler from monopolizing admitted capacity, and (c) derives priority safely. Name the algorithms/primitives you'd use.
6. Your team adopts Java 21 virtual threads and removes the fixed worker pool. Two weeks later you get intermittent `OutOfMemoryError` and DB connection-pool timeouts under load that the old version handled fine. Explain the mechanism and the precise fix.
7. Define goodput vs throughput, then describe two dashboard panels and one load-test methodology that would reveal a goodput collapse that a throughput-only view would hide.

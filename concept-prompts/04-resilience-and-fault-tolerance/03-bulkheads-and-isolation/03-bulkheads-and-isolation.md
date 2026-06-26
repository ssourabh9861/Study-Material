# Bulkheads & Isolation

> **Concept area:** Resilience & Fault Tolerance
> **Subtopic:** Bulkheads & Isolation
> **Reader:** A senior JVM/Java backend engineer who wants to master this from first principles to deep internals — enough to design with it, operate it, debug it in production, teach it, and ace any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What it is

A **bulkhead** is a resilience pattern that **partitions a finite, shared resource into isolated compartments** so that the failure, saturation, or slowness of one part of a system cannot consume *all* of that resource and take down the *other* parts. The name is a metaphor borrowed from naval engineering (Section 2.1).

In software, the "finite shared resource" is almost always one of:

- **Threads** (a thread pool — a fixed set of worker threads that execute tasks).
- **Connections** (a connection pool — a fixed set of pre-opened TCP/DB/HTTP connections).
- **Memory** (heap, off-heap buffers, queue capacity).
- **Concurrency permits** (semaphores — counters that cap how many things run at once).
- **CPU / scheduling slots**, **file descriptors**, **disk I/O bandwidth**.

A bulkhead **caps how much of that resource any single consumer (a downstream dependency, a tenant, a request class) may hold at once.** If consumer A misbehaves and grabs its entire cap, consumers B and C still have their own caps and keep working.

### 1.2 The problem it solves

The canonical failure it prevents is **cascading failure via resource exhaustion**:

> A single slow or failed downstream dependency causes calls to it to pile up and *hold* shared resources (threads, connections) far longer than normal. Because the resource is shared, those held resources are unavailable to *everything else*. Soon the shared pool is fully occupied by stuck calls to the one bad dependency. Now *every* request — even ones that never touch the bad dependency — blocks waiting for a free resource. The whole service goes down, even though only one of its five dependencies failed.

The bulkhead breaks that chain: the bad dependency can only ever consume *its* compartment. The blast radius is **contained**.

A concrete trace of exactly how a shared pool gets exhausted is in **Section 9.1** — read it; it is the heart of *why* this pattern exists.

### 1.3 When you reach for it

Reach for a bulkhead when **a single process calls multiple independent downstream dependencies (or serves multiple independent consumers) over a shared, finite resource, and you cannot tolerate one of them taking down the rest.** Specifically:

- A service aggregates several backends (e.g., a product page calling `inventory`, `pricing`, `reviews`, `recommendations`). One of them being slow must not make the whole page time out.
- A multi-tenant system where one noisy tenant's load must not starve the others.
- A request mix with different priorities (e.g., user-facing reads vs. background batch jobs) sharing a DB connection pool.
- Any place you already use a **circuit breaker** or **timeout** — bulkheads are the third leg of that stool (Section 1.5).

### 1.4 The one-paragraph mental model

> Think of your service's finite resources (threads, connections) as lifeboats on a ship. If you let every passenger crowd into one lifeboat, a single panic sinks everyone. Instead you assign each *group* of passengers (each downstream dependency, each tenant) its **own** lifeboat with a **fixed number of seats**. When a group's calls get stuck, only *that* boat fills up and gets turned away (fast-fail / rejection); the other boats sail on unaffected. You trade a little efficiency (some seats sit empty while others overflow) for **fault containment**: no single failure can sink the ship.

### 1.5 Where it sits among resilience patterns

Bulkheads are one of a small family of fault-tolerance patterns that are almost always used **together**, not in isolation:

| Pattern | What it limits / does | The question it answers |
|---|---|---|
| **Timeout** | Maximum time to wait for a single call | "How long before I give up?" |
| **Retry** | Re-issue a failed call (ideally with backoff + jitter) | "Should I try again?" |
| **Circuit Breaker** | Stops calling a dependency that is *already* failing (open/half-open/closed states) | "Should I even bother calling?" |
| **Bulkhead** | Caps *concurrent* in-flight calls / resources per consumer | "How much of my capacity may this consume?" |
| **Rate Limiter** | Caps *rate* (calls per unit time) | "How fast may this be called?" |
| **Fallback** | Degraded alternative when the primary fails | "What do I return when it fails?" |
| **Load Shedding** | Drop excess work to protect the system under overload | "What do I drop to survive?" |

Key distinction newcomers blur: a **rate limiter caps throughput over time** (e.g., 100 req/s), a **bulkhead caps concurrency at an instant** (e.g., 25 in-flight at once). A slow dependency may receive a *low rate* yet still saturate a bulkhead because each call *stays in flight* for a long time. Concurrency = rate × latency (Little's Law — Section 7.3), so the two are related but distinct knobs.

A typical hardened call path layers them: **Bulkhead → Circuit Breaker → Retry → Timeout → actual call → Fallback.** (Order matters — see Section 6.7.)

---

## 2. Foundations from first principles

### 2.1 The naval metaphor (and why it is precise)

A ship's hull is divided by transverse walls called **bulkheads** into watertight **compartments**. If the hull is breached, water floods only the breached compartment(s); the bulkheads stop the water from spreading to the rest of the ship, so it stays afloat. The *Titanic* failed partly because its bulkheads did not extend high enough — water overtopped them and flowed compartment-to-compartment.

The software lesson is exact:

- **Compartment = an isolated slice of a resource** (a sub-pool of threads/connections).
- **Flooding = a runaway consumer holding resources** (stuck calls, leaked connections).
- **Bulkhead height = the strictness of isolation.** A "too short" bulkhead — e.g., a shared queue behind two pools, or a shared lower-level resource — lets the flood spill over. Watch for *hidden shared resources* below your bulkhead (Section 6.6, 9.6).

### 2.2 What is a "finite shared resource"? (define every term)

Everything a server does consumes a **bounded** resource. The bound exists either physically or by policy:

- **Thread** — the OS/JVM unit of execution. A thread has its own stack (default ~512 KB–1 MB on the JVM, set by `-Xss`). Creating millions is impossible; each costs memory and scheduling overhead. So we pool them.
- **Thread pool** — a fixed (or bounded) set of reusable worker threads plus a **work queue**. Tasks are submitted; idle workers pull from the queue and run them. In Java this is `java.util.concurrent.ThreadPoolExecutor`. Bounding the pool bounds concurrency.
- **Connection** — an established communication channel: a TCP socket, often wrapped (a JDBC `Connection`, an HTTP keep-alive connection). Opening one is expensive (TCP handshake, TLS handshake, DB auth), so we keep them open and reuse them.
- **Connection pool** — a fixed set of open connections handed out and returned (e.g., **HikariCP** for JDBC, the connection pool inside an HTTP client). Bounded because the *database* itself can only handle so many connections (e.g., Postgres `max_connections`, often ~100).
- **Semaphore** — a counter with a maximum value. To do work you must **acquire** a permit (decrement); when done you **release** it (increment). If no permits are free, you wait or fail. It caps concurrency *without* allocating a thread per slot. In Java: `java.util.concurrent.Semaphore`.
- **File descriptor (fd)** — a small integer the OS uses to track an open file or socket. Each process has a limit (`ulimit -n`, often 1024 or 65536). Connections consume fds; leak them and you hit "Too many open files."

Because each is **finite**, contention is inevitable, and *un-partitioned* contention means one greedy consumer can take it all.

### 2.3 What "isolation" means here

**Isolation** = a guarantee that one consumer's resource usage **cannot exceed a bound** and therefore cannot reduce another consumer's *guaranteed* share below an agreed minimum. Two strengths:

- **Hard isolation:** physically separate resources (separate thread pools, separate connection pools, even separate processes/hosts). Strong guarantee, higher cost (idle resources, overhead).
- **Soft isolation:** a shared resource with **per-consumer caps** (semaphores/permits over one pool). Cheaper, but isolation is only as good as the cap and the absence of hidden shared bottlenecks below it.

### 2.4 Synchronous vs. asynchronous, and why it matters for bulkheads

- **Synchronous (blocking) call:** the calling thread *waits* (is blocked) until the call returns. While blocked, that thread is unusable for anything else. This is why slow downstreams are deadly: a slow sync call **pins a thread** for the whole duration. Bulkheads that isolate threads exist precisely for this model.
- **Asynchronous (non-blocking) call:** the calling thread issues the call and is freed immediately; a callback/future completes later. Here a slow downstream pins a *connection* and some memory, but not a dedicated thread. The relevant resource to bulkhead shifts from threads to **concurrent permits / connections / in-flight requests**.

This single distinction drives the **thread-pool vs. semaphore isolation** tradeoff (Section 3) — the most important decision in this topic.

### 2.5 Backpressure & rejection: the "what happens when full" question

A bulkhead's whole value shows up at the moment it is **full**. The options when a new request arrives and no permit/thread is free:

1. **Reject immediately (fast-fail)** — throw/return an error now (e.g., `BulkheadFullException`). Best for protecting latency: the caller learns instantly and can fall back. This is **load shedding**.
2. **Queue (bounded)** — wait in a fixed-size queue for a slot; reject when the queue is also full. Smooths brief bursts; adds latency.
3. **Block (wait up to a timeout)** — caller waits up to `maxWaitDuration`; reject if not admitted in time. A middle ground.
4. **Block forever** — *anti-pattern*. An unbounded wait reintroduces the very pile-up the bulkhead was meant to prevent.

"**Backpressure**" is the general principle of signaling *upstream* "I'm full, slow down / stop" rather than silently buffering without limit. Rejection *is* backpressure made explicit.

### 2.6 First-principles summary

A bulkhead is just: **(a finite resource) + (a partitioning into per-consumer caps) + (a defined behavior when a cap is hit).** Everything else — Resilience4j config, Hystrix, Hikari per-route pools — is an implementation of those three things.

---

## 3. How it works internally

This is the heart of the chapter. We trace the two dominant implementations — **thread-pool isolation** and **semaphore isolation** — step by step, then the connection-pool variant.

### 3.1 Thread-pool isolation — internal workflow

**Idea:** Give each downstream dependency its **own dedicated thread pool**. The *caller* thread (e.g., a Tomcat HTTP worker handling the incoming request) does **not** execute the downstream call itself. Instead it **hands the call to the dependency's dedicated pool** and waits on a Future (with a timeout). The downstream call runs on a *bulkhead thread*, not the caller thread.

**Step-by-step control flow (one request):**

1. Request arrives on a **Tomcat worker thread** (from the server's HTTP thread pool, e.g., `maxThreads=200`).
2. Application logic needs data from dependency `payments`. It calls a wrapper that targets the **`payments` thread pool** (say, `core=10, max=10, queue=5`).
3. The wrapper creates a `Callable` for the actual remote call and submits it to the `payments` `ExecutorService`. This returns a `Future`.
   - If a `payments` worker thread is free → it begins executing the call immediately.
   - If all 10 `payments` threads are busy → the task goes into the **bounded queue** (capacity 5).
   - If queue is also full → submission is **rejected** (`RejectedExecutionException` → mapped to a bulkhead-full error). **Fast-fail.**
4. The Tomcat worker now waits on `future.get(timeout)`.
   - If the call completes in time → result returned, the `payments` thread is released back to its pool.
   - If it exceeds the timeout → `future.cancel(true)` (best-effort interrupt), a `TimeoutException` is raised, the Tomcat worker is freed to run the fallback.
5. Crucial property: **the dedicated pool's size is the hard ceiling on concurrent `payments` calls.** No matter how slow `payments` is, at most 10 calls (+5 queued) are tied up. The other dependencies have their own pools; Tomcat workers are *not* pinned for the call duration on the bulkhead thread (though the calling thread *does* wait on the future — see the nuance in 3.1.1).

**Data flow:** request → Tomcat thread → submit Callable → bulkhead pool thread executes remote I/O → result/exception → Future → back to Tomcat thread → response/fallback.

**State of the bulkhead pool** is captured by `ThreadPoolExecutor` internals:
- `corePoolSize`, `maximumPoolSize`, `workQueue` (e.g., `ArrayBlockingQueue` of fixed size), `keepAliveTime`, `RejectedExecutionHandler`.
- Live counters: `getActiveCount()` (threads running tasks), `getQueue().size()`, `getCompletedTaskCount()`, `getPoolSize()`.

#### 3.1.1 The subtle cost: thread-hopping and double-occupancy

While the call runs on a bulkhead thread, the **caller thread still blocks on `future.get`** in the simplest synchronous implementation. So you temporarily occupy *two* threads (caller + bulkhead worker). This is acceptable because the caller is freed the instant the timeout fires — the bulkhead guarantees the *caller* can't wait forever — but it does mean thread-pool isolation has real overhead:

- **Context switch + thread hop**: the work executes on a different thread than the caller. Measured overhead is small but nonzero — Netflix Hystrix reported a **median overhead around the order of single-digit milliseconds at the 99.5th percentile** for thread isolation under realistic load (it is cheaper at the median). Treat exact numbers as version/workload-specific; the point is it is *not free*.
- **ThreadLocal loss**: anything stored in a `ThreadLocal` on the caller thread (security context, MDC logging context, transaction context) is **not** automatically visible on the bulkhead thread. You must propagate it explicitly (e.g., Spring's `DelegatingSecurityContextExecutor`, SLF4J MDC copy, `TransmittableThreadLocal`). Forgetting this is a top source of "works in dev, breaks in prod" bugs.

### 3.2 Semaphore isolation — internal workflow

**Idea:** No extra threads. The **caller thread executes the downstream call itself**, but must first **acquire a permit** from a per-dependency `Semaphore`. The permit count *is* the concurrency cap.

**Step-by-step control flow (one request):**

1. Request arrives on a Tomcat worker thread.
2. Application calls the `payments` wrapper. The wrapper calls `semaphore.tryAcquire(maxWaitDuration)` on the `payments` semaphore (say, `maxConcurrentCalls=25`).
   - If a permit is free → acquired; proceed.
   - If none free and `maxWaitDuration=0` → **immediate rejection** (`BulkheadFullException`). Fast-fail.
   - If none free and `maxWaitDuration>0` → block up to that duration, then admit or reject.
3. The **same Tomcat thread** now executes the remote call directly (synchronously).
4. On completion (success *or* exception, via try/finally) → `semaphore.release()`.
5. Property: at most 25 Tomcat threads can be inside `payments` concurrently. The remaining Tomcat threads are free for other dependencies.

**Key difference vs. thread pool:** there is **no separate thread, no queue, no thread hop, no ThreadLocal loss** (same thread throughout). It is much cheaper.

**The big caveat:** because the call runs on the *caller's* thread, **semaphore isolation cannot enforce a timeout on a truly blocking call** by itself. A `Semaphore.tryAcquire` times out the *wait for a permit*, not the *call*. If the downstream hangs and the underlying client has no socket timeout, the caller thread is pinned forever, the permit is never released, and the bulkhead silently fills with stuck threads — defeating the purpose. **Therefore: semaphore isolation MUST be paired with a real client-level timeout** (socket/read timeout on the HTTP/DB client). This is the single most important rule for semaphore bulkheads.

### 3.3 Thread-pool vs. semaphore — the decisive comparison

| Dimension | **Thread-pool isolation** | **Semaphore isolation** |
|---|---|---|
| Extra threads | Yes — dedicated pool per dependency | No — runs on caller thread |
| Can enforce timeout independently | **Yes** (waits on Future, can cancel) | **No** — relies on client timeout |
| Overhead per call | Higher (thread hop, context switch, queue) | Very low (a counter inc/dec) |
| ThreadLocal / context propagation | Manual (lost across hop) | Free (same thread) |
| Protects against *blocking* clients with no timeout | **Yes** (caller is freed on timeout) | **No** (caller hangs) |
| Memory cost | Higher (N pools × threads × stacks) | Minimal |
| Concurrency ceiling | `maxPoolSize + queueCapacity` | `maxConcurrentCalls` |
| Best for | Network calls to remote systems that can hang; need timeout isolation | Fast, in-process or already-timeout-bounded calls; very high call rates where overhead matters |
| Async (reactive) code | Often unnecessary; use semaphore-style permit on the async pipeline | Natural fit |

**Rule of thumb (Netflix/Hystrix-era wisdom):** use **thread-pool isolation for network calls** (the default in Hystrix), because remote calls can hang in ways you don't control, and only a separate thread lets you *abandon* the caller. Use **semaphore isolation for very high-volume, low-latency, in-process calls** (e.g., calling a local cache, or a client that *already* enforces strict timeouts) where the thread-hop overhead would dominate. In **reactive/async** stacks, threads aren't pinned, so a **semaphore/permit-based bulkhead is the natural choice** (this is what Resilience4j's reactive `Bulkhead` does).

### 3.4 Connection-pool partitioning (per-dependency pools)

A different but equally important bulkhead: **don't share one connection pool across multiple destinations.** Give each downstream its own pool.

**Why:** Suppose one HikariCP pool of 20 connections is shared to talk to two databases, `orders_db` and `analytics_db`. If `analytics_db` gets slow, queries to it hold connections for a long time. Soon all 20 are held by slow `analytics_db` queries; `orders_db` queries — fast and healthy — can't get a connection and time out. The pool is the shared resource; partition it.

**Per-dependency connection pools (internal model):**
- One pool per *distinct downstream* (per DB, per remote host, per route).
- Each pool sized independently to that dependency's needs (Section 7.3).
- A slow dependency exhausts *only its* pool; others are untouched.

This applies to:
- **JDBC:** separate `HikariDataSource` per database/schema/tenant.
- **HTTP clients:** Apache HttpClient `PoolingHttpClientConnectionManager` supports `maxTotal` *and* `maxPerRoute` — `maxPerRoute` is effectively a per-host bulkhead inside one manager. Reactor Netty / OkHttp similarly bound connections per host.
- **gRPC:** separate channels (each with its own subchannels) per backend.

### 3.5 Isolating tenants and priorities

Two important non-dependency partitionings:

- **Tenant isolation (multi-tenancy):** in a system serving many customers from one process, give each tenant (or tenant tier) its own bulkhead so one tenant's spike can't starve others. Common in SaaS. Implemented as a *map of bulkheads keyed by tenant*, or per-tenant connection pools, or "**shuffle sharding**" (Section 7.6) for many tenants.
- **Priority isolation:** reserve capacity per request class. E.g., split the DB pool: 16 connections for *interactive* user requests, 4 for *batch* jobs. A batch storm can take at most its 4; interactive traffic keeps 16. Equivalent: separate thread pools per priority, or a single pool with per-class permits.

### 3.6 The lifecycle / state machine of a bulkhead

A bulkhead is simpler than a circuit breaker (no open/closed/half-open). Its "state" is just the **current occupancy** vs. **capacity**, but the request-level state transitions are:

```
            tryAcquire / submit
   [IDLE/AVAILABLE] ───────────────► [OCCUPIED slot]
        ▲                                   │
        │ release / task complete            │ task runs (bounded by timeout)
        └───────────────────────────────────┘

   If no slot available:
   [AVAILABLE=0] ──tryAcquire──► (wait up to maxWaitDuration)
                                   ├─ slot frees → ACQUIRED
                                   └─ timeout    → REJECTED (BulkheadFull)
```

Continuously exported metrics describe the bulkhead's health: **available permits / free slots**, **occupied slots**, **queue depth** (thread-pool), **rejection count/rate**, **wait time**. When `available == 0` and rejections climb, the bulkhead is doing its job — *shedding load to protect the rest*. That is a *signal*, not necessarily a bug (Section 9).

---

## 4. The complete toolkit

### 4.1 Java core building blocks (`java.util.concurrent`)

| API / class | Purpose | Key parameters / methods | Defaults & notes |
|---|---|---|---|
| `ThreadPoolExecutor` | The engine behind thread-pool bulkheads | `corePoolSize`, `maximumPoolSize`, `keepAliveTime`, `workQueue`, `ThreadFactory`, `RejectedExecutionHandler` | For a bulkhead set `core == max` and a **bounded** `ArrayBlockingQueue`; choose `AbortPolicy` (default — throws `RejectedExecutionException`) to fast-fail |
| `Executors.newFixedThreadPool(n)` | Convenience fixed pool | `n` | **Anti-pattern for bulkheads**: uses an *unbounded* `LinkedBlockingQueue` → no rejection → unbounded memory/latency. Build the executor explicitly instead |
| `Semaphore` | The engine behind semaphore bulkheads | `permits`, `fair`; `acquire()`, `tryAcquire(timeout)`, `release()`, `availablePermits()` | `fair=false` by default (higher throughput, possible starvation); `fair=true` for FIFO fairness |
| `RejectedExecutionHandler` | Behavior when pool+queue full | `AbortPolicy` (throw), `CallerRunsPolicy` (run on caller — *backpressure but breaks isolation!*), `DiscardPolicy`, `DiscardOldestPolicy` | `CallerRunsPolicy` is a foot-gun in a bulkhead: it makes the *caller* run the task, re-pinning the protected thread |
| `Future` / `CompletableFuture` | Await/cancel the offloaded call | `get(timeout, unit)`, `cancel(true)` | `cancel(true)` *interrupts* but cannot kill code that ignores interrupts (e.g., blocked in a non-interruptible socket read) — hence client timeouts still matter |
| `ArrayBlockingQueue` | Bounded queue for the bulkhead pool | capacity | Always bound it; this is the queue depth knob |

### 4.2 Resilience4j — `Bulkhead` and `ThreadPoolBulkhead`

Resilience4j is the de-facto modern Java resilience library (the successor to the now-deprecated Netflix Hystrix). It offers **two** bulkhead implementations:

**(A) `Bulkhead` (SemaphoreBulkhead)** — semaphore-based, runs on caller thread.

`BulkheadConfig` parameters:

| Parameter | Meaning | Default |
|---|---|---|
| `maxConcurrentCalls` | Max concurrent permits | **25** |
| `maxWaitDuration` | How long a caller waits for a permit before rejection | **0** (fail immediately) |
| `fairCallHandlingEnabled` | Use a fair (FIFO) semaphore | **true** (R4j ≥ 1.x) |
| `writableStackTraceEnabled` | Include stack trace in `BulkheadFullException` | true |

> Verify exact defaults against your Resilience4j version; the values above reflect commonly documented 1.7.x defaults. Treat them as version-specific.

**(B) `ThreadPoolBulkhead`** — backed by a `ThreadPoolExecutor`; offloads to a separate pool (so it also gives you async + timeout-style isolation). Returns a `CompletionStage`.

`ThreadPoolBulkheadConfig` parameters:

| Parameter | Meaning | Default |
|---|---|---|
| `maxThreadPoolSize` | Max threads | `Runtime.getRuntime().availableProcessors()` |
| `coreThreadPoolSize` | Core threads | `availableProcessors() - 1` |
| `queueCapacity` | Bounded queue size | **100** |
| `keepAliveDuration` | Idle thread TTL | **20 ms** |

> Again, confirm defaults for your version; these reflect 1.7.x docs.

Common methods/decorators:
- `Bulkhead.decorateSupplier(bulkhead, supplier)`, `decorateCallable`, `decorateRunnable`, `decorateCompletionStage`.
- `ThreadPoolBulkhead.decorateSupplier(...)` → returns a `Supplier<CompletionStage<T>>`.
- `BulkheadRegistry.of(config)` — central registry; create/lookup named bulkheads.
- Events: `bulkhead.getEventPublisher().onCallPermitted/onCallRejected/onCallFinished(...)`.
- Metrics: `bulkhead.getMetrics().getAvailableConcurrentCalls()`, `getMaxAllowedConcurrentCalls()`. For thread-pool: `getQueueDepth()`, `getThreadPoolSize()`, `getQueueCapacity()`, `getRemainingQueueCapacity()`.

### 4.3 Netflix Hystrix (legacy — know it for interviews & maintenance)

Hystrix is **in maintenance mode / deprecated** (Netflix stopped active development around 2018), but it pioneered the pattern and appears in interviews and legacy code.

| Hystrix concept | Meaning | Default |
|---|---|---|
| `execution.isolation.strategy` | `THREAD` or `SEMAPHORE` | **THREAD** |
| `coreSize` (thread pool) | Threads per command group | **10** |
| `maxQueueSize` | Queue capacity | **-1** (uses `SynchronousQueue` → no real queue, immediate reject) |
| `queueSizeRejectionThreshold` | Reject threshold even if queue larger | **5** |
| `execution.isolation.semaphore.maxConcurrentRequests` | Semaphore permits | **10** |
| `execution.isolation.thread.timeoutInMilliseconds` | Command timeout | **1000 ms** |

Hystrix's default of **thread isolation with coreSize 10 and 1s timeout** is a useful historical anchor for "sane starting numbers."

### 4.4 Connection-pool tools

| Tool | Knob | Purpose | Default (note) |
|---|---|---|---|
| **HikariCP** | `maximumPoolSize` | Max DB connections in this pool | **10** |
| HikariCP | `minimumIdle` | Min idle kept warm | = `maximumPoolSize` (i.e., fixed-size by default) |
| HikariCP | `connectionTimeout` | Max wait to borrow a connection before failing | **30000 ms** (lower it! e.g., 250–2000 ms for fast-fail) |
| HikariCP | `maxLifetime`, `idleTimeout`, `validationTimeout` | Connection recycling/health | 1.8e6 ms / 6e5 ms / 5000 ms |
| **Apache HttpClient** `PoolingHttpClientConnectionManager` | `maxTotal` | Total connections across all routes | **20** |
| Apache HttpClient | `defaultMaxPerRoute` | Per-host (per-route) cap = **per-dependency bulkhead** | **2** (notoriously low — almost always must raise) |
| **OkHttp** | `Dispatcher.maxRequests`, `maxRequestsPerHost` | Concurrent request caps (async) | **64** / **5** |
| **Reactor Netty** | `ConnectionProvider.builder().maxConnections(n).pendingAcquireMaxCount(m).pendingAcquireTimeout(d)` | Per-pool concurrency + bounded waiting queue | configurable |
| **Tomcat** | `server.tomcat.threads.max` (`maxThreads`) | HTTP worker threads (the *top-level* shared pool) | **200** |
| **Tomcat** | `acceptCount` | OS accept backlog when all threads busy | **100** |

> The Apache `defaultMaxPerRoute = 2` default is a famous gotcha: it silently throttles you to 2 concurrent calls per host even if `maxTotal` is huge. Always set it deliberately.

### 4.5 OS-level isolation tools (the "bulkhead below the JVM")

| Tool | What it isolates | Example |
|---|---|---|
| `ulimit -n` / `/etc/security/limits.conf` | File descriptors per process | Raise to handle many connections |
| **cgroups v2** (`cpu.max`, `memory.max`, `io.max`) | CPU/memory/I/O per container/process | Kubernetes resource `limits`/`requests` |
| Kubernetes **requests/limits**, separate **Deployments**, **node pools** | Process/host-level bulkheads | Run latency-sensitive and batch workloads on different node pools |
| Separate **processes / services** | Strongest isolation | Microservice decomposition is bulkheading at the service boundary |

The strongest bulkhead is **physical separation** (separate hosts/clusters); the weakest is a **shared in-process pool with permits**. Choose the level that matches your blast-radius requirement and cost tolerance.

---

## 5. Code examples by use case

All examples are Java unless noted, compile-ready or trivially adaptable, with non-obvious lines commented.

### 5.1 A hand-rolled thread-pool bulkhead (no library)

Shows the raw mechanics so the abstraction isn't magic.

```java
import java.util.concurrent.*;

/** A minimal thread-pool bulkhead for ONE dependency. */
public class ThreadPoolBulkhead<T> {
    private final ThreadPoolExecutor pool;
    private final Duration callTimeout;

    public ThreadPoolBulkhead(String name, int threads, int queueCapacity, Duration callTimeout) {
        this.callTimeout = callTimeout;
        this.pool = new ThreadPoolExecutor(
            threads, threads,                 // core == max → fixed size (hard ceiling)
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),   // BOUNDED queue → enables rejection
            r -> {                             // named threads make thread dumps readable
                Thread t = new Thread(r, "bulkhead-" + name);
                t.setDaemon(true);             // don't block JVM shutdown
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy() // throw RejectedExecutionException when full → fast-fail
        );
    }

    /** Run the call on the bulkhead pool; abandon it (free the caller) if it exceeds the timeout. */
    public T execute(Callable<T> call) throws Exception {
        Future<T> f;
        try {
            f = pool.submit(call);             // may throw RejectedExecutionException if pool+queue full
        } catch (RejectedExecutionException e) {
            throw new BulkheadFullException("Bulkhead full", e); // load shed immediately
        }
        try {
            return f.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS); // caller waits, but BOUNDED
        } catch (TimeoutException e) {
            f.cancel(true);                    // best-effort interrupt of the stuck call
            throw e;                           // caller is freed → can run fallback
        }
    }

    static class BulkheadFullException extends RuntimeException {
        BulkheadFullException(String m, Throwable c) { super(m, c); }
    }
}
```

Key teaching points (commented inline): `core==max` + **bounded** queue + `AbortPolicy` = a hard concurrency ceiling with fast-fail; `future.get(timeout)` bounds the *caller's* wait; `cancel(true)` is best-effort (a non-interruptible socket read won't actually stop — that's why you also need a client socket timeout, Section 3.2).

### 5.2 A hand-rolled semaphore bulkhead

```java
import java.util.concurrent.*;

/** Minimal semaphore bulkhead: runs the call on the CALLER thread. */
public class SemaphoreBulkhead {
    private final Semaphore permits;
    private final long maxWaitMillis;

    public SemaphoreBulkhead(int maxConcurrent, long maxWaitMillis, boolean fair) {
        this.permits = new Semaphore(maxConcurrent, fair); // fair=true → FIFO, no starvation, lower throughput
        this.maxWaitMillis = maxWaitMillis;
    }

    public <T> T execute(Callable<T> call) throws Exception {
        // tryAcquire bounds the WAIT FOR A PERMIT — NOT the call itself.
        if (!permits.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Bulkhead full"); // fast-fail / load shed
        }
        try {
            return call.call();   // runs on caller thread → MUST be wrapped with a client socket/read timeout!
        } finally {
            permits.release();    // ALWAYS release, even on exception, or the bulkhead leaks permits
        }
    }
}
```

The `finally`-release is the single most important line: forget it and an exception leaks a permit until the bulkhead is permanently smaller — and eventually empty.

### 5.3 Resilience4j semaphore `Bulkhead` (idiomatic)

```java
import io.github.resilience4j.bulkhead.*;
import java.util.function.Supplier;

BulkheadConfig cfg = BulkheadConfig.custom()
    .maxConcurrentCalls(25)                       // hard concurrency cap for THIS dependency
    .maxWaitDuration(java.time.Duration.ofMillis(0)) // 0 = fail fast, do not queue
    .build();

BulkheadRegistry registry = BulkheadRegistry.of(cfg);
Bulkhead paymentsBh = registry.bulkhead("payments"); // named → metrics & per-dependency isolation

Supplier<PaymentResult> decorated =
    Bulkhead.decorateSupplier(paymentsBh, () -> paymentClient.charge(req));

// io.vavr Try for clean fallback; or use try/catch on BulkheadFullException
PaymentResult result = io.vavr.control.Try.ofSupplier(decorated)
    .recover(BulkheadFullException.class, ex -> PaymentResult.degraded()) // shed → fallback
    .get();
```

### 5.4 Resilience4j `ThreadPoolBulkhead` (async, with timeout) + stacking with a circuit breaker

```java
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.timelimiter.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

ThreadPoolBulkheadConfig tpCfg = ThreadPoolBulkheadConfig.custom()
    .maxThreadPoolSize(10)
    .coreThreadPoolSize(10)
    .queueCapacity(20)
    .build();
ThreadPoolBulkhead tpBulkhead =
    ThreadPoolBulkheadRegistry.of(tpCfg).bulkhead("inventory");

TimeLimiter timeLimiter = TimeLimiter.of(java.time.Duration.ofMillis(800)); // enforce a hard timeout
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

CircuitBreaker cb = CircuitBreaker.ofDefaults("inventory");

// Order: Bulkhead (admit/offload) -> TimeLimiter (timeout) -> CircuitBreaker (skip if open)
Supplier<CompletionStage<Inventory>> supplier =
    ThreadPoolBulkhead.decorateSupplier(tpBulkhead, () -> inventoryClient.fetch(sku));

CompletionStage<Inventory> stage =
    timeLimiter.executeCompletionStage(scheduler, supplier.get());

stage = cb.executeCompletionStage(() -> stage); // record success/failure for the breaker

Inventory inv = stage
    .exceptionally(ex -> Inventory.unknown())   // fallback on bulkhead-full / timeout / open breaker
    .toCompletableFuture()
    .join();
```

> Note: `ThreadPoolBulkhead` returns a `CompletionStage`, so the `TimeLimiter` pairs naturally. In Spring Boot, the `@Bulkhead(name="inventory", type = Type.THREADPOOL)` annotation plus `@TimeLimiter` and `@CircuitBreaker` wire this declaratively.

### 5.5 Spring Boot declarative config (`application.yml`) + annotations

```yaml
resilience4j:
  bulkhead:                 # semaphore bulkheads
    instances:
      reviews:
        maxConcurrentCalls: 20
        maxWaitDuration: 10ms
  thread-pool-bulkhead:     # thread-pool bulkheads
    instances:
      payments:
        maxThreadPoolSize: 10
        coreThreadPoolSize: 10
        queueCapacity: 20
```

```java
import io.github.resilience4j.bulkhead.annotation.Bulkhead;

@Service
public class ReviewService {

    // Semaphore bulkhead; fallback invoked on BulkheadFullException
    @Bulkhead(name = "reviews", fallbackMethod = "reviewsFallback")
    public List<Review> getReviews(String productId) {
        return reviewClient.fetch(productId);
    }

    private List<Review> reviewsFallback(String productId, BulkheadFullException ex) {
        return List.of(); // degrade gracefully: show the page without reviews
    }
}
```

### 5.6 Per-dependency connection pools (HikariCP) — connection bulkhead

```java
// Two SEPARATE pools so a slow analytics DB cannot starve the orders DB.
HikariConfig ordersCfg = new HikariConfig();
ordersCfg.setJdbcUrl("jdbc:postgresql://orders-db:5432/orders");
ordersCfg.setMaximumPoolSize(20);
ordersCfg.setConnectionTimeout(500);  // FAST-FAIL: don't wait 30s for a connection
ordersCfg.setPoolName("orders-pool");
HikariDataSource ordersDs = new HikariDataSource(ordersCfg);

HikariConfig analyticsCfg = new HikariConfig();
analyticsCfg.setJdbcUrl("jdbc:postgresql://analytics-db:5432/analytics");
analyticsCfg.setMaximumPoolSize(5);   // analytics gets a SMALL, isolated slice
analyticsCfg.setConnectionTimeout(2000);
analyticsCfg.setPoolName("analytics-pool");
HikariDataSource analyticsDs = new HikariDataSource(analyticsCfg);
// A slow analytics query now exhausts only analytics-pool (max 5), never orders-pool.
```

### 5.7 HTTP per-route bulkhead (Apache HttpClient)

```java
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;

PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
cm.setMaxTotal(100);                       // total across all hosts
cm.setDefaultMaxPerRoute(10);              // default per-host cap (raise from the awful default of 2!)
cm.setMaxPerRoute(                         // dedicated, larger bulkhead for a hot dependency
    new org.apache.hc.client5.http.classic.methods.HttpRoute(new HttpHost("pricing", 443)),
    25);
// pricing gets 25 concurrent; every other host shares the default of 10; total capped at 100.
```

### 5.8 Tenant isolation: a map of per-tenant bulkheads

```java
import io.github.resilience4j.bulkhead.*;
import java.util.concurrent.ConcurrentHashMap;

public class TenantBulkheads {
    private final BulkheadRegistry registry;
    private final ConcurrentHashMap<String, Bulkhead> byTenant = new ConcurrentHashMap<>();
    private final int perTenantConcurrency;

    public TenantBulkheads(int perTenantConcurrency) {
        this.perTenantConcurrency = perTenantConcurrency;
        this.registry = BulkheadRegistry.ofDefaults();
    }

    public Bulkhead forTenant(String tenantId) {
        // One isolated bulkhead per tenant → a noisy tenant cannot starve others.
        return byTenant.computeIfAbsent(tenantId, id ->
            registry.bulkhead("tenant-" + id,
                BulkheadConfig.custom().maxConcurrentCalls(perTenantConcurrency).build()));
    }
}
```
> Caution: with *many* tenants this creates many bulkheads (memory + the sum of caps can exceed real capacity). For large tenant counts prefer **tiered** bulkheads (gold/silver/bronze) or **shuffle sharding** (Section 7.6).

### 5.9 Priority isolation: split the pool by request class

```java
// Reserve DB capacity so batch jobs cannot starve interactive users.
Semaphore interactive = new Semaphore(16); // user-facing reads
Semaphore batch       = new Semaphore(4);  // background jobs — capped low on purpose

<T> T withClass(Semaphore cls, Callable<T> q) throws Exception {
    if (!cls.tryAcquire(50, TimeUnit.MILLISECONDS)) throw new RuntimeException("class saturated");
    try { return q.call(); } finally { cls.release(); }
}
// A batch storm consumes at most 4 connections; interactive traffic always keeps 16.
```

### 5.10 Reactive bulkhead (Project Reactor + Resilience4j)

```java
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import reactor.core.publisher.Mono;

Bulkhead bh = Bulkhead.of("recs",
    BulkheadConfig.custom().maxConcurrentCalls(30).maxWaitDuration(java.time.Duration.ZERO).build());

Mono<Recs> recs = recommendationClient.fetch(userId)      // non-blocking call
    .transformDeferred(BulkheadOperator.of(bh))           // permit-based concurrency cap on the stream
    .onErrorResume(BulkheadFullException.class, ex -> Mono.just(Recs.empty())); // shed → fallback
```
In reactive code no thread is pinned per call, so a **semaphore-style permit cap** is the right tool — there's no need for a separate thread pool.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Thread-pool isolation costs** a thread hop + context switch + (in the sync model) double thread occupancy during the call. Hystrix measured ~single-digit-ms p99.5 overhead under load — fine for network calls (which take tens of ms anyway), wasteful for sub-millisecond in-process calls. Use **semaphore isolation for hot, fast paths**.
- **Semaphore contention:** with `fair=true`, the semaphore enforces FIFO via an internal queue — correct but slower. With `fair=false` (default), throughput is higher but a thread can theoretically starve. For most bulkheads `fair=false` is fine; use `fair=true` only when ordering/anti-starvation matters.
- **Sizing too small** throttles healthy traffic and *causes* the latency you feared; **too large** defeats isolation (one dependency can again hog the machine). Size with Little's Law (Section 7.3), then load-test.

### 6.2 Correctness & concurrency

- **Always release in `finally`** (semaphores) / let the executor manage lifecycle (thread pools). Leaked permits silently shrink the bulkhead.
- **Bound every queue.** An unbounded queue (the default in `Executors.newFixedThreadPool`) converts a concurrency limit into an *unbounded latency + memory* bomb — the opposite of a bulkhead.
- **`maxWaitDuration` ≈ 0** for fast-fail unless you specifically want to absorb micro-bursts; long waits reintroduce pile-up.
- **Never use `CallerRunsPolicy` in a bulkhead** — it runs the rejected task on the *caller* (e.g., the Tomcat thread), re-pinning exactly the thread you were protecting.

### 6.3 Memory

- Each thread pool consumes `threads × stack size` (`-Xss`, default ~512 KB–1 MB). With many dependencies × many threads this adds up: 20 pools × 10 threads × 1 MB ≈ 200 MB of stacks. Prefer semaphore isolation when you have *many* dependencies, or right-size pools aggressively.
- Bounded queues hold references to queued tasks (and their captured request data) — size queues with memory in mind.

### 6.4 Security & multi-tenancy

- **Tenant isolation is also a security/abuse control**: it prevents a malicious or runaway tenant from a denial-of-service on co-tenants (the "noisy neighbor"). Combine with rate limiting and quotas.
- **Context propagation**: across a thread hop you must explicitly carry the **SecurityContext** (else authorization checks on the bulkhead thread see no user) and the **MDC** logging context (else logs lose trace/tenant IDs). Use `DelegatingSecurityContextExecutorService`, MDC copy, or `TransmittableThreadLocal`.

### 6.5 Observability (you cannot operate what you can't see)

Export per-bulkhead metrics and **alert on rejections and saturation**:

| Metric | Why it matters |
|---|---|
| `resilience4j_bulkhead_available_concurrent_calls` | Free slots; → 0 means saturated |
| `resilience4j_bulkhead_max_allowed_concurrent_calls` | The cap (sanity check config) |
| `...thread_pool_bulkhead.queue_depth` / `remaining_queue_capacity` | Queue pressure |
| Rejection count/rate (`onCallRejected` events) | The bulkhead is shedding load — investigate *which* dependency |
| Call duration histogram per dependency | Detects the *slow* dependency before it saturates |

Resilience4j publishes Micrometer metrics out of the box (`resilience4j-micrometer`), so they flow to Prometheus/Grafana. Hikari publishes `hikaricp_connections_active`, `..._pending`, `..._timeout_total`. **`pending > 0` and rising timeouts = pool exhaustion.**

Add **distributed tracing** spans around the bulkhead so a saturated bulkhead shows up as a rejection span, not a mystery latency spike.

### 6.6 Watch for hidden shared resources *below* the bulkhead

A bulkhead only isolates the resource it controls. If two "isolated" thread pools both submit to one **shared downstream connection pool**, or one **shared database**, or one **shared CPU/host**, the isolation leaks at the lower layer (the "bulkhead too short" problem, Section 2.1). Map the *full* resource stack and bulkhead at the layer that actually saturates. Common culprits: shared connection pool under separate thread pools; shared DB; shared event loop; shared host CPU.

### 6.7 Composition order with other patterns

A common, defensible decorator order (outermost first):

```
Bulkhead  →  CircuitBreaker  →  RateLimiter  →  TimeLimiter/Timeout  →  Retry  →  call  →  Fallback
```
- **Bulkhead outermost**: reject *before* spending any other resource when saturated (cheap shed).
- **Circuit breaker before retry**: don't retry into a known-open breaker.
- **Retry inside the timeout/bulkhead?** Beware: retries *multiply* concurrency. If each logical call retries 3×, a bulkhead of 25 can host far fewer *logical* operations, and retries amplify load on an already-struggling dependency. Generally **bulkhead/breaker should sit outside retry**, and retries should be conservative (with backoff + jitter). Resilience4j's documented aspect order (Spring) is: Bulkhead → TimeLimiter → RateLimiter → CircuitBreaker → Retry (verify for your version).

### 6.8 Testing

- **Unit:** saturate the bulkhead (`maxConcurrentCalls=1`, hold the first call) and assert the second is rejected / falls back.
- **Latency injection:** use a fake client (e.g., a controllable `CompletableFuture`, Toxiproxy, or WireMock fixed-delay) to make one dependency slow; assert *other* dependencies stay healthy. This is the test that proves isolation works.
- **Chaos/GameDay:** in staging (or carefully in prod), inject downstream latency/faults and confirm rejections rise *only* for that dependency, error budgets hold, and dashboards/alerts fire.

### 6.9 Production hardening checklist

- [ ] Every bulkhead is **named** and emits metrics.
- [ ] Queues and pools are **bounded**; `connectionTimeout`/`maxWaitDuration` are **short**.
- [ ] Semaphore bulkheads are paired with **client socket/read timeouts**.
- [ ] **Context (security/MDC/trace) is propagated** across thread hops.
- [ ] Sizes derived from **Little's Law + load test**, not guesses; re-reviewed after traffic changes.
- [ ] **Alerts** on rejection rate, saturation, and Hikari `pending`.
- [ ] **Fallbacks** defined for `BulkheadFullException` (degrade, don't crash).
- [ ] No hidden shared resource below the bulkhead.
- [ ] No `CallerRunsPolicy`; no unbounded `newFixedThreadPool`.

### 6.10 Anti-patterns (memorize these)

1. **Unbounded queue** behind the pool (latency/memory bomb).
2. **Semaphore isolation without a client timeout** (permits leak on hangs; bulkhead fills with stuck threads).
3. **One giant shared pool** for all dependencies (no isolation at all — the very problem).
4. **`maxWaitDuration` / `connectionTimeout` set huge** (30s default Hikari) → callers pile up instead of fast-failing.
5. **`CallerRunsPolicy`** in a bulkhead pool.
6. **Sum of all caps far exceeds machine capacity**, so "isolated" pools still collectively overload the host (no global ceiling).
7. **Retries inside the bulkhead** multiplying concurrency.
8. **Forgetting context propagation** across thread hops (auth/logging breaks).
9. **Bulkhead too short** — isolating threads while the real bottleneck (DB, CPU, downstream pool) stays shared.
10. **No metrics/alerts** — you discover saturation from a customer ticket, not a dashboard.

---

## 7. Advanced topics & deep internals

### 7.1 Why `core == max` for a bulkhead `ThreadPoolExecutor`

`ThreadPoolExecutor` only grows past `corePoolSize` toward `maximumPoolSize` **after the queue is full**. So with `core < max` and a non-trivial queue, you get the surprising behavior that the pool prefers *queuing* over *adding threads* until the queue saturates. For a bulkhead you want a **predictable, fixed concurrency**, so set `core == max` and rely on the bounded queue purely for short bursts. (This JDK behavior surprises many engineers — it's a classic interview "gotcha.")

### 7.2 Fairness, starvation, and the semaphore's internal queue

`Semaphore(permits, fair)`:
- **Non-fair (default):** `tryAcquire` can "barge" — a newly arriving thread may grab a just-released permit ahead of long-waiting threads. Higher throughput, but a thread can theoretically starve under sustained contention.
- **Fair:** uses an internal FIFO wait queue (built on `AbstractQueuedSynchronizer`, AQS — the JDK framework underpinning locks/semaphores that manages a queue of waiting threads via a single atomic state int). Guarantees order; costs throughput because every acquire consults the queue.

Choose fair when you must bound worst-case wait per caller (e.g., latency SLOs per tenant); non-fair for raw throughput.

### 7.3 Sizing with Little's Law (the core quantitative tool)

**Little's Law** (queueing theory): for a stable system,

```
L = λ × W
```
- **L** = average number of items *in the system concurrently* (= the concurrency / pool size you need),
- **λ** (lambda) = average **arrival rate** (requests per second),
- **W** = average **time in system** (latency per request, in seconds).

So **required concurrency = throughput × latency**.

**Worked example.** A dependency you call at **λ = 500 req/s**, average latency **W = 40 ms = 0.04 s**:
```
L = 500 × 0.04 = 20 concurrent calls.
```
So a bulkhead of ~20 sustains steady state. But you must size for the **peak/tail**, not the average:
- Use **p99 latency**, not mean (one slow tail multiplies concurrency).
- Add headroom (e.g., 1.5–2×) for bursts.
- If latency spikes to 200 ms under stress: `L = 500 × 0.2 = 100` — your pool of 20 will saturate and reject. That's *intended*: better to shed than to let it consume everything. But it tells you the *steady* size and the *failure* threshold.

**Connection pool sizing** uses the same law. PostgreSQL's own guidance and the HikariCP wiki famously argue pools should be **small** — often `connections ≈ ((core_count × 2) + effective_spindle_count)` as a starting heuristic — because a DB processes only a few queries truly in parallel; an oversized pool *increases* contention and latency. Little's Law + that heuristic together: size to actual concurrent need, not "as big as possible."

### 7.4 The cost of isolation (be honest about the downside)

Isolation is **not free**. The costs:

| Cost | Explanation |
|---|---|
| **Lower utilization** | Capacity is partitioned. If dependency A is idle and B is overloaded, A's reserved slots sit empty while B's callers are rejected. A shared pool would have lent A's slots to B. Isolation trades *peak efficiency* for *containment*. |
| **More tuning surface** | N dependencies × {size, queue, timeout, wait} = many knobs, each needing data and maintenance as traffic shifts. |
| **Memory/threads** | Each thread pool costs stacks + scheduling overhead (Section 6.3). |
| **Overhead per call** | Thread hops/context switches for thread-pool isolation. |
| **Risk of *under*-provisioning** | Too-small caps reject healthy traffic — the isolation itself becomes the outage. |
| **Complexity** | Harder to reason about, more failure surfaces, more dashboards. |

The decision is fundamentally **statistical multiplexing (shared pool, high utilization, correlated failure) vs. isolation (partitioned, lower utilization, contained failure).** You isolate where correlated failure is unacceptable, and share where efficiency dominates and dependencies are independent/reliable.

### 7.5 Adaptive / dynamic bulkheads

Static caps are a guess. Advanced systems make them **adaptive**:
- **Concurrency-limiting algorithms** (e.g., Netflix's *concurrency-limits* library, which applies TCP-Vegas/AIMD-style control): the bulkhead *measures* latency and dynamically raises the limit while latency is low and lowers it when latency climbs — auto-finding the optimal concurrency without manual sizing. This avoids both the "too small" and "too large" failure modes.
- **Adaptive connection pools** that grow/shrink within bounds based on observed `pending` and latency.
- Tradeoff: adaptivity adds its own failure modes and is harder to reason about; static caps are predictable and auditable.

### 7.6 Shuffle sharding (bulkheads at scale, for many tenants)

When you have *many* tenants/consumers and can't give each its own pool, **shuffle sharding** (popularized by AWS) assigns each tenant a *random small subset* of shared workers. Because two tenants rarely share the *same* full subset, a single bad tenant degrades only the small overlap, not everyone. It's a probabilistic bulkhead that gives near-isolation with far fewer resources than one-pool-per-tenant. Combine with per-tenant caps for defense in depth.

### 7.7 Bulkheads and virtual threads (JDK 21+ Project Loom)

**Virtual threads** are JVM-managed lightweight threads (millions are cheap) that *unmount* from their carrier OS thread when they block on I/O. This changes the calculus:
- The classic reason for *thread-pool* isolation — "blocking pins a scarce OS thread" — is weakened, because a blocked virtual thread doesn't pin a carrier. So **per-dependency OS-thread pools become less necessary** for *thread* exhaustion.
- **But** you still need to bound concurrency against the **downstream** (it still has finite capacity) and against **shared scarce resources** (connections, DB, memory). So bulkheads shift toward **semaphore/permit isolation** over virtual threads: cheap virtual threads + a semaphore cap per dependency. Resilience4j's semaphore `Bulkhead` and structured concurrency fit this model. The pattern survives; the *implementation* leans semaphore.

### 7.8 Interrupt semantics and the limits of `cancel(true)`

`Future.cancel(true)` interrupts the worker thread. But interruption only stops code that **checks** the interrupt flag or is blocked in an **interruptible** method. A thread blocked in a **non-interruptible** native socket read (classic blocking I/O without a socket timeout) **ignores the interrupt** and keeps waiting — the bulkhead thread stays occupied, even though the caller was freed. Hence: thread-pool isolation frees the *caller* but cannot guarantee freeing the *worker* — which is why **socket/read timeouts on the client are mandatory regardless of isolation strategy.**

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing an isolation strategy

| If… | Use | Because |
|---|---|---|
| Calling a remote system that can hang, sync blocking client | **Thread-pool isolation** | Only a separate thread lets you abandon the caller on timeout |
| Very high-rate, low-latency, in-process or already-timeout-bounded call | **Semaphore isolation** | Avoids thread-hop overhead |
| Reactive/async stack | **Semaphore/permit bulkhead on the stream** | No thread is pinned; threads aren't the scarce resource |
| JDK 21+ with virtual threads | **Semaphore over virtual threads** | Virtual threads remove thread scarcity; cap downstream concurrency instead |
| Multiple databases/hosts | **Per-dependency connection pools** | Slow dependency exhausts only its pool |
| Many tenants, can't pool-per-tenant | **Shuffle sharding** (+ tiers) | Near-isolation at low resource cost |
| Distinct priorities (interactive vs batch) | **Per-class pools/permits with reserved capacity** | Guarantees a floor for high-priority traffic |

### 8.2 Isolate vs. share decision

**Use isolation when:** dependencies are independent; correlated failure is unacceptable; one consumer can plausibly run away (slow, looping, abusive); you have tenants/priorities with different SLAs; the dependency is unreliable or external.

**Avoid / minimize isolation when:** dependencies are uniformly fast and reliable; utilization/efficiency dominates and the blast radius is already small (e.g., a single internal dependency); resource overhead of many pools is prohibitive; traffic is so spiky that static partitioning wastes capacity (prefer adaptive limits or a shared pool with global admission control).

### 8.3 Bulkhead vs. adjacent patterns

| Question | Right tool |
|---|---|
| "One dependency hangs and exhausts my threads" | **Bulkhead** (isolate per dependency) |
| "A dependency is *already* failing; stop hammering it" | **Circuit breaker** |
| "Cap how *fast* a client can call me" | **Rate limiter** |
| "Give up on a single slow call" | **Timeout** |
| "Survive overload by dropping excess work" | **Load shedding** (bulkhead rejection is a form of this) |
| "One tenant must not affect others" | **Tenant bulkhead / shuffle sharding / quotas** |

They compose; the question identifies which knob you're missing.

### 8.4 Library/tool choice

| Need | Pick |
|---|---|
| Modern Java resilience (active) | **Resilience4j** (`Bulkhead`, `ThreadPoolBulkhead`) |
| Legacy maintenance / interviews | **Hystrix** (deprecated; understand, don't adopt) |
| Adaptive concurrency | **Netflix concurrency-limits** (or service-mesh adaptive limits) |
| JDBC connection bulkhead | **HikariCP** (one pool per DB/tenant) |
| HTTP per-host bulkhead | Apache HttpClient `maxPerRoute`, OkHttp dispatcher, Reactor Netty `ConnectionProvider` |
| Service/host-level isolation | Kubernetes (separate Deployments/node pools, requests/limits, cgroups) |
| Mesh-level | Envoy/Istio circuit-breaking + connection limits per upstream cluster |

---

## 9. Failure modes & debugging

### 9.1 The canonical incident: one slow dependency exhausts a shared pool (full trace)

**Setup:** A `product-page` service runs on Tomcat with `maxThreads=200` (one shared pool of HTTP worker threads). It calls four dependencies *synchronously on the worker thread*: `inventory` (p50 5 ms), `pricing` (5 ms), `reviews` (10 ms), `recommendations` (8 ms). No bulkheads. Each worker handles a request end-to-end, calling all four in sequence. Steady state: maybe 30–40 workers busy at any instant. Healthy.

**The trigger (t=0):** `recommendations`'s database gets a bad query plan. `recommendations` latency jumps from 8 ms to **20 seconds** (effectively hung), and it has **no client read timeout**.

**The trace, second by second:**

- **t=0–1s:** Requests keep arriving (say 500 req/s). Each one eventually calls `recommendations`. That worker thread now **blocks for 20 s** in the `recommendations` call (no timeout to free it).
- **t≈0.4s:** By Little's Law, threads stuck in `recommendations` accumulate at `λ × W = 500 × 20 = 10,000` *desired* concurrency — but only **200** worker threads exist. Within well under a second, **all 200 Tomcat workers are blocked inside `recommendations`.**
- **t≈0.5s onward:** New requests arrive, but **no worker thread is free**. They sit in Tomcat's `acceptCount` backlog (100), then connections are refused. **Every** endpoint is now dead — including a `/health` check and requests for products that don't even need `recommendations` — because *all threads are pinned on the one bad dependency.*
- **Result:** A single slow dependency took down the **entire** service. Health checks fail → orchestrator may kill/restart pods → thundering-herd on restart → outage spreads to the cluster.

**Why it happened:** the **shared** worker pool + **synchronous blocking** call + **no timeout** + **no isolation**. The blast radius was the whole service instead of just `recommendations`.

**How a bulkhead fixes it:** give `recommendations` its own bulkhead of, say, 25 (thread-pool isolation with an 800 ms timeout, or semaphore isolation *with an 800 ms client read timeout* + fallback). Now: at most 25 calls get stuck (then they time out and free the caller); the remaining 175 Tomcat workers keep serving `inventory`/`pricing`/`reviews` and the product page renders **without** recommendations (fallback = empty recs). Blast radius contained to one feature.

### 9.2 Diagnosing pool exhaustion — the tools & commands

**Thread exhaustion (JVM):**
- **Thread dump:** `jstack <pid>` (or `kill -3 <pid>`, or `jcmd <pid> Thread.print`). Look for **many threads in the same stack** blocked in a downstream call (e.g., dozens of `http-nio-*` threads parked in `SocketInputStream.read` / a specific client). That's the smoking gun: a thread cluster pinned on one dependency.
- **`jcmd <pid> Thread.print` repeatedly** to see if the same threads stay stuck (true hang) vs. churn (slowness).
- **Tomcat metrics:** `tomcat.threads.busy` near `tomcat.threads.config.max` (200), `tomcat.threads.current` maxed, rising `acceptCount` usage / connection refusals.

**Connection-pool exhaustion (HikariCP):**
- Metrics: `hikaricp_connections_active == maximumPoolSize`, `hikaricp_connections_pending > 0` and rising, `hikaricp_connections_timeout_total` climbing.
- Logs: Hikari logs *"Connection is not available, request timed out after Nms"* — the explicit exhaustion message.
- DB side: `SELECT * FROM pg_stat_activity` (Postgres) to see long-running/`idle in transaction` queries holding connections; check the slow query log.

**Bulkhead (Resilience4j):**
- `resilience4j_bulkhead_available_concurrent_calls == 0` for a dependency + rising `onCallRejected` rate = that bulkhead is saturated (doing its job, but tells you *which* dependency is sick).
- `BulkheadFullException` rate in logs/metrics.

**Off-CPU / latency profiling:** async-profiler in wall-clock mode or a continuous profiler shows threads spending time *off-CPU blocked in I/O* on a specific call — pinpointing the slow dependency.

### 9.3 Symptom → likely cause map

| Symptom | Likely cause |
|---|---|
| All endpoints slow/dead, but only one dependency is sick | No bulkhead; shared pool exhausted by the sick dependency (Section 9.1) |
| `BulkheadFullException` spikes for one dependency, others fine | Bulkhead working; that dependency is slow/saturated — investigate *it*, or raise its cap if undersized |
| `BulkheadFullException` even at low traffic | Bulkhead sized too small, or permits leaking (missing `finally` release), or retries multiplying concurrency |
| Bulkhead never rejects but service still hangs | Hidden shared resource below the bulkhead (shared DB/conn pool), or semaphore isolation with no client timeout |
| Hikari `pending > 0`, timeouts | Connection pool exhausted; slow queries holding connections; or pool too small for `λ×W` |
| Auth/logging context missing after adding thread-pool isolation | Context not propagated across the thread hop |

### 9.4 Real-world incident patterns (well-documented in industry)

- **Netflix → Hystrix:** Netflix built Hystrix after repeated incidents where one slow backend in a fan-out (the API service calling dozens of microservices) exhausted shared thread pools and degraded the whole edge. Thread-pool isolation per dependency + fallbacks contained these. This is the *origin story* of the pattern in modern microservices.
- **The "30-second JDBC timeout" class of incident:** services using the default Hikari `connectionTimeout=30000` and a slow DB: callers queue up for 30 s, every worker thread fills, the whole service times out — fixed by short connection timeouts + per-dependency pools + fast-fail. (A recurring, well-known production failure shape across many companies.)
- **Apache HttpClient `maxPerRoute=2`:** teams hitting a mysterious 2-concurrent-request ceiling to one host under load — an accidental, far-too-tight bulkhead from the library default. Fixed by raising `defaultMaxPerRoute`/`maxPerRoute`.

(Treat company-specific details as illustrative; the *shapes* are what generalize.)

### 9.5 Live mitigation playbook (when it's on fire)

1. **Identify the sick dependency** (thread dump cluster / per-dependency latency dashboard / which bulkhead is at 0).
2. **Shed it:** if no bulkhead, *add/tighten* one or trip its circuit breaker so calls fail fast and free threads; deploy a feature flag to disable that feature (fallback to degraded).
3. **Lower timeouts** for the sick dependency so stuck calls release threads/connections sooner.
4. **Confirm recovery:** other endpoints' latency returns to normal while the sick dependency stays degraded — *that's the win*.
5. **After:** root-cause the dependency; right-size the bulkhead; add the missing client timeout; add alerts.

### 9.6 The insidious failure: isolation that doesn't isolate

You add per-dependency thread pools and feel safe — but all those pools borrow connections from **one shared HikariCP**. A slow DB still exhausts that single pool, every thread pool's tasks block waiting for a connection, and the service dies *despite* the thread bulkheads. Lesson: **bulkhead the resource that actually saturates** (here, connections), and verify with a latency-injection test that one dependency's slowness leaves the others measurably healthy. If it doesn't, your bulkhead is "too short."

---

## 10. Interview drill

### Q1. What is the bulkhead pattern and what problem does it solve? *(recall)*
**Model answer:** It partitions a finite shared resource (threads, connections, permits) into per-consumer compartments with fixed caps, so one consumer's failure/saturation can't consume the whole resource and take down the others. It prevents **cascading failure via resource exhaustion** — e.g., one slow downstream pinning every shared worker thread and killing the entire service.
- *Probe: Where does the name come from?* Ship hulls divided into watertight compartments; a breach floods one compartment, not the whole ship.
- *Probe: How does it differ from a circuit breaker?* The breaker *stops calling* a dependency that's already failing (stateful open/closed). The bulkhead *caps concurrency* so a still-failing dependency can only consume its slice. They compose.
- *Probe: From a rate limiter?* Rate limiter caps calls *per unit time*; bulkhead caps *concurrent* in-flight calls. A low rate can still saturate a bulkhead if each call is slow (concurrency = rate × latency).

### Q2. Thread-pool vs. semaphore isolation — when each? *(senior-signal: tradeoff)*
**Model answer:** Thread-pool isolation offloads the call to a dedicated pool, so it can **enforce a timeout and abandon the caller** even if the client hangs — use it for **remote network calls** that can hang. Semaphore isolation runs on the caller thread with just a permit cap — **much cheaper (no thread hop, no ThreadLocal loss)** — use it for **fast/high-rate/in-process calls or calls already bounded by a strict client timeout**, and in reactive/virtual-thread stacks. The catch: semaphore isolation **can't enforce a call timeout itself**, so it *must* be paired with a client socket/read timeout, or stuck calls leak permits.
- *Probe: Why can't a semaphore enforce a timeout?* `tryAcquire(timeout)` bounds only the *wait for a permit*, not the running call; the call executes on the caller thread, which blocks indefinitely without a client timeout.
- *Probe: Overhead of thread isolation?* Thread hop + context switch (Hystrix measured ~single-digit-ms p99.5) + double thread occupancy during the call + manual context propagation.
- *Probe: How do virtual threads change this?* They remove OS-thread scarcity, so per-dependency OS-thread pools matter less; you lean on semaphore/permit caps over cheap virtual threads to bound downstream concurrency.

### Q3. Walk through exactly how one slow dependency takes down a service without bulkheads. *(recall → reasoning)*
**Model answer:** Give the trace from Section 9.1: shared Tomcat pool (200), one dependency hangs to 20 s with no timeout, by Little's Law desired concurrency (λ×W) vastly exceeds 200, all workers block inside the bad call within ~1 s, no thread is free for any other endpoint, health checks fail, the whole service dies even though only one of four dependencies failed.
- *Probe: What's the minimal fix?* A timeout on that call frees threads; a bulkhead bounds how many can be stuck at once; a fallback degrades that one feature.
- *Probe: Why isn't a timeout alone enough?* A timeout limits each call's duration but, without a concurrency cap, a high arrival rate can still keep *many* threads occupied (λ × timeout). The bulkhead caps the *count*.

### Q4. How do you size a bulkhead / connection pool? *(senior-signal: justification)*
**Model answer:** Little's Law: `L = λ × W` → required concurrency = throughput × latency. Size for **peak λ and tail (p99) latency**, add headroom (≈1.5–2×), then **load-test**. For connection pools, *smaller is often better* (the DB only runs a few queries truly in parallel; oversizing increases contention) — the Hikari/Postgres heuristic `~(2×cores)+spindles`. The cap is also a deliberate **failure threshold**: beyond it you *want* to shed.
- *Probe: Why not size for worst-case latency?* You'd over-provision massively and lose isolation (the pool could hog the host). You size for steady state and *accept rejection* during pathological latency — that's the point.
- *Probe: What if you size too small?* The bulkhead rejects healthy traffic and becomes the outage; watch `available==0` + rejections at *normal* load as the signal to enlarge.

### Q5. What happens when a bulkhead is full? What are the options? *(recall)*
**Model answer:** Fast-fail (reject now → `BulkheadFullException`, best for latency, enables fallback/load-shedding), bounded queue (absorb micro-bursts, adds latency, reject when full), block up to `maxWaitDuration` (middle ground), or block forever (anti-pattern — reintroduces pile-up). Prefer fast-fail with a fallback for user-facing latency-sensitive paths.
- *Probe: Default Resilience4j `maxWaitDuration`?* 0 — fail immediately (version-specific; verify).
- *Probe: Why is `CallerRunsPolicy` dangerous here?* It runs the rejected task on the caller (e.g., Tomcat) thread, re-pinning exactly the thread the bulkhead was protecting.

### Q6. What is the *cost* of isolation? When would you NOT bulkhead? *(senior-signal: tradeoff)*
**Model answer:** Isolation trades **utilization for containment**: partitioned capacity means idle reserved slots in one compartment while another rejects — a shared pool would have multiplexed them. Plus more tuning surface, memory/threads, per-call overhead, and the risk of under-provisioning causing self-inflicted outages. **Don't** over-isolate when dependencies are uniformly fast/reliable, blast radius is already tiny, overhead is prohibitive, or traffic is so spiky that static partitions waste capacity (prefer adaptive limits). It's statistical multiplexing vs. fault containment — choose per dependency.
- *Probe: How to get isolation without the utilization hit?* Adaptive concurrency limits (Netflix concurrency-limits) or shuffle sharding — near-isolation with higher utilization.
- *Probe: Global ceiling problem?* If the sum of all per-dependency caps exceeds machine capacity, "isolated" pools can still collectively overload the host — you also need a global admission limit.

### Q7. How would you isolate tenants or priorities? *(application)*
**Model answer:** Per-tenant bulkheads (a map of bulkheads keyed by tenant) or per-tenant connection pools so a noisy neighbor can't starve others; for *many* tenants, **shuffle sharding** (random worker subsets per tenant → bad tenant degrades only a small overlap) plus tiers (gold/silver/bronze). For priorities, **reserve capacity per class** (e.g., 16 interactive / 4 batch permits) so high-priority traffic always has a floor.
- *Probe: Downside of one bulkhead per tenant at scale?* Memory + the sum of caps can exceed real capacity; many tiny pools waste resources. Hence tiers/shuffle sharding.
- *Probe: Security angle?* Tenant isolation is also an anti-abuse/DoS control against the noisy/malicious neighbor.

### Q8. Composition order: bulkhead, retry, circuit breaker, timeout? *(senior-signal)*
**Model answer:** Roughly Bulkhead (outermost, cheap shed when saturated) → Circuit Breaker (skip known-failing) → Rate Limiter → Timeout → Retry (innermost) → call → Fallback. Crucially, **retries must sit inside** the bulkhead/breaker and be conservative (backoff + jitter), because retries *multiply* concurrency and amplify load on an already-struggling dependency — undoing the bulkhead if placed wrong.
- *Probe: Why bulkhead outermost?* So you reject before spending any other resource when saturated.
- *Probe: Resilience4j default aspect order?* Bulkhead → TimeLimiter → RateLimiter → CircuitBreaker → Retry (verify per version).

### Q9. You added per-dependency thread pools but a slow DB still took the service down. Why? *(senior-signal: debugging/reasoning)*
**Model answer:** The thread pools were isolated, but they all shared **one** connection pool / one DB — a hidden shared resource *below* the bulkhead ("bulkhead too short"). The slow DB exhausted that single connection pool; every thread pool's tasks then blocked waiting for a connection. Fix: bulkhead the resource that actually saturates — per-dependency *connection* pools — and verify with latency-injection that one dependency's slowness leaves the others healthy.
- *Probe: How to detect this pre-prod?* Inject downstream latency (Toxiproxy/WireMock) and assert other dependencies stay healthy; check Hikari `pending`/`active`.
- *Probe: Other hidden shared resources?* Shared host CPU, shared event loop, shared cache, shared file descriptors.

### Q10. How do you observe and debug a saturated bulkhead in production? *(application)*
**Model answer:** Per-bulkhead metrics — `available_concurrent_calls` (→0 = saturated), `onCallRejected` rate, queue depth — plus per-dependency latency histograms; Hikari `active/pending/timeout_total`. To debug: take a `jstack`/`jcmd Thread.print` and look for a **cluster of threads blocked in the same downstream stack**; check `pg_stat_activity` for connection hogs; use wall-clock profiling for off-CPU I/O. The bulkhead at 0 + rejections rising tells you *which* dependency to investigate.
- *Probe: Is a saturated bulkhead a bug?* Not necessarily — it means the bulkhead is shedding to protect the rest. Investigate the dependency; only enlarge the cap if it saturates at *normal* load.
- *Probe: What single dashboard panel catches the canonical incident earliest?* Per-dependency p99 latency (it rises *before* the pool fully exhausts), plus bulkhead available-slots trending to zero.

### Q11. Why set `core == max` on a bulkhead `ThreadPoolExecutor`? *(deep internals)*
**Model answer:** `ThreadPoolExecutor` only adds threads beyond `core` *after the queue is full*. With `core < max` plus a queue, it prefers queuing over spawning threads until the queue saturates — surprising and non-deterministic for a bulkhead. Setting `core == max` with a small bounded queue gives a **fixed, predictable concurrency ceiling**.
- *Probe: Why bound the queue?* An unbounded queue (the `newFixedThreadPool` default) turns a concurrency cap into unbounded latency/memory — the opposite of a bulkhead.

### Q12. Compare Hystrix and Resilience4j for bulkheads. *(recall + judgment)*
**Model answer:** Hystrix pioneered the pattern (thread/semaphore isolation, commands, fallbacks) but is **in maintenance/deprecated**. Resilience4j is the modern, lightweight, functional successor with both `Bulkhead` (semaphore) and `ThreadPoolBulkhead`, first-class Micrometer metrics, reactive operators, and composability with other decorators. Use Resilience4j for new work; know Hystrix for legacy/interviews.
- *Probe: Hystrix defaults worth remembering?* Thread isolation, coreSize 10, 1 s command timeout, semaphore max 10.
- *Probe: A Resilience4j advantage?* No mandatory thread offloading (semaphore bulkhead has near-zero overhead), modular, reactive-friendly, Spring Boot annotations + YAML config.

---

## 11. Glossary

- **AQS (AbstractQueuedSynchronizer):** JDK framework underpinning locks/semaphores; manages a queue of waiting threads via one atomic state integer.
- **Arrival rate (λ):** Requests entering the system per unit time.
- **Asynchronous/non-blocking call:** Caller is freed immediately; completion arrives via callback/future. No thread pinned for the call's duration.
- **Backpressure:** Signaling upstream "I'm full — slow/stop" instead of unbounded buffering. Rejection is explicit backpressure.
- **Blast radius:** The extent of damage a single failure causes; bulkheads shrink it.
- **Bulkhead:** A partition of a finite shared resource into per-consumer capped compartments to contain failure.
- **Cascading failure:** A failure that propagates across components, often via shared-resource exhaustion.
- **cgroups:** Linux kernel feature limiting CPU/memory/I/O per process group (used by containers/Kubernetes).
- **Circuit breaker:** Pattern that stops calling an already-failing dependency (open/half-open/closed states).
- **Concurrency:** Number of operations in flight at the same instant (≠ rate).
- **Connection pool:** A bounded set of pre-opened, reused connections (e.g., HikariCP for JDBC).
- **Context propagation:** Carrying thread-bound context (security, MDC, trace) across a thread hop.
- **CompletionStage / CompletableFuture:** JDK abstractions for asynchronous results.
- **Fast-fail / load shedding:** Rejecting work immediately when at capacity to protect latency and the rest of the system.
- **File descriptor (fd):** OS handle for an open file/socket; per-process limited (`ulimit -n`).
- **Future:** A handle to a result computed asynchronously; `get(timeout)` waits, `cancel(true)` interrupts.
- **Hikari (HikariCP):** A fast, popular JDBC connection pool.
- **Hystrix:** Netflix's pioneering (now deprecated) resilience library; origin of mainstream bulkhead/circuit-breaker use.
- **Isolation (hard/soft):** Guaranteeing a consumer can't exceed a resource bound; hard = physically separate resources, soft = caps over a shared resource.
- **Little's Law:** `L = λ × W` — average concurrency = arrival rate × time in system; the core sizing tool.
- **maxPerRoute:** Apache HttpClient per-host connection cap (a per-dependency bulkhead); default 2.
- **MDC (Mapped Diagnostic Context):** Per-thread key/value store for logging (e.g., trace/tenant IDs).
- **Multi-tenancy:** One system serving many isolated customers; needs tenant bulkheads to prevent noisy neighbors.
- **Permit:** A unit of a semaphore's capacity; you acquire one to proceed and release it when done.
- **Project Loom / virtual threads (JDK 21+):** Lightweight JVM threads that unmount on blocking I/O; make threads cheap and abundant.
- **Rate limiter:** Caps calls per unit time (≠ concurrency cap).
- **RejectedExecutionHandler:** `ThreadPoolExecutor` policy when pool+queue are full (`AbortPolicy`, `CallerRunsPolicy`, …).
- **Resilience4j:** The modern, modular Java resilience library (bulkhead, circuit breaker, retry, rate limiter, time limiter).
- **Semaphore:** A counter capping concurrent access via acquire/release permits.
- **Semaphore isolation:** Bulkhead that runs the call on the caller thread, gated by a permit cap (cheap; needs a client timeout).
- **Shuffle sharding:** Assigning each consumer a random small subset of shared workers for near-isolation at low cost (AWS technique).
- **Statistical multiplexing:** Sharing a resource pool across consumers for high utilization (trades containment for efficiency).
- **Synchronous/blocking call:** Caller thread waits until the call returns; a slow call pins the thread.
- **Thread pool:** A bounded set of reusable worker threads plus a work queue (`ThreadPoolExecutor`).
- **Thread-pool isolation:** Bulkhead that offloads each dependency's calls to its own pool; can enforce timeouts independently.
- **ThreadLocal:** Per-thread variable storage; not visible across a thread hop unless propagated.
- **Timeout:** Maximum time to wait for a single call before giving up.
- **Tomcat maxThreads:** Size of Tomcat's shared HTTP worker thread pool (default 200) — the top-level shared resource.
- **ulimit:** Shell/OS per-process resource limit (e.g., `-n` for open file descriptors).
- **Utilization:** Fraction of a resource actively used; isolation typically lowers peak utilization.
- **W (time in system):** Average latency per request in Little's Law.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Core idea:** Partition finite resource → per-consumer caps → defined behavior when full. Contains blast radius; trades utilization for containment.

**Two implementations:**
- **Thread-pool:** dedicated pool per dependency; *can enforce timeout*; higher overhead; loses ThreadLocal. → **network calls**.
- **Semaphore:** permit cap on caller thread; cheap; *cannot enforce timeout alone* → **pair with client socket timeout**. → **fast/in-process/reactive/virtual-thread**.

**When full:** fast-fail (preferred, → fallback) | bounded queue | wait ≤ `maxWaitDuration` | never block forever.

**Sizing (Little's Law):** `L = λ × W` → concurrency = throughput × latency. Use **p99**, add ~1.5–2× headroom, then load-test. Connection pools: smaller is often better (`~2×cores+spindles`).

**Key numbers (verify per version):** Resilience4j `Bulkhead` default `maxConcurrentCalls=25`, `maxWaitDuration=0`. `ThreadPoolBulkhead` queue=100. Hikari `maximumPoolSize=10`, `connectionTimeout=30000ms` (lower it!). Apache `defaultMaxPerRoute=2` (raise it!). Tomcat `maxThreads=200`. Hystrix: thread isolation, coreSize 10, 1s timeout.

**Decision rules:**
- Remote, can-hang call → thread-pool isolation (+ timeout).
- Hot/in-process/reactive/virtual-thread → semaphore (+ client timeout).
- Multiple DBs/hosts → per-dependency connection pools.
- Many tenants → shuffle sharding + tiers; few → per-tenant bulkhead.
- Priorities → reserved per-class capacity.

**Anti-patterns:** unbounded queue · semaphore w/o client timeout · one shared pool · huge wait/connection timeout · `CallerRunsPolicy` · caps summing past host capacity · retries inside bulkhead · lost context across hop · hidden shared resource below bulkhead · no metrics/alerts.

**Debug:** `jstack`/`jcmd Thread.print` → thread cluster in one downstream stack = exhaustion. Hikari `pending>0`/timeouts = pool exhausted. Bulkhead `available==0` + rejections = which dependency is sick. Inject latency to *prove* isolation.

**Compose:** Bulkhead → CircuitBreaker → RateLimiter → Timeout → Retry → call → Fallback. Retries multiply concurrency — keep them inside, conservative, with jitter.

### 12.2 Self-test (no answers — recall actively)

1. Without notes, write the second-by-second trace of how one 20-second-latency dependency with no timeout takes down a 200-thread Tomcat service — and identify the *three* root-cause ingredients.
2. A dependency runs at 800 req/s with p99 latency 60 ms. What bulkhead size sustains steady state, and what size would you actually configure, and why? Now its latency spikes to 300 ms — what happens, and is that correct behavior?
3. You must isolate calls to a remote service that occasionally hangs, in a JDK 21 codebase using virtual threads. Which isolation strategy do you choose, what *must* you also configure, and why does the thread-pool argument weaken here?
4. Your team added per-dependency thread pools, but a slow database still caused a full outage. Diagnose the most likely cause and describe the exact test you'd write to confirm isolation actually works.
5. Explain the difference between a bulkhead and a rate limiter using the relationship between concurrency, rate, and latency. Give one scenario where a low call rate still saturates a bulkhead.
6. List the four behaviors a bulkhead can take when full, rank them for a user-facing latency-sensitive endpoint, and explain why `CallerRunsPolicy` is a foot-gun.
7. Why is `cancel(true)` insufficient to free a worker thread stuck in a blocking socket read, and what does that imply you must *always* configure regardless of isolation strategy?
8. Sketch the correct decorator/composition order for bulkhead + circuit breaker + retry + timeout, and explain specifically what goes wrong if retries sit *outside* the bulkhead.

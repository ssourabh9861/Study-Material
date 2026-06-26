# Rate Limiting

> An exhaustive engineering-handbook chapter for senior JVM/backend developers. From first principles to deep internals, distributed systems, production hardening, and interview mastery.

---

## 1. Overview & where it fits

**Rate limiting** is the practice of *bounding the rate at which a client (or set of clients) may consume a resource* — typically the number of requests per unit of time against an API, service, queue, or database. When a client exceeds its allowance, the system **rejects, delays, or degrades** the excess instead of serving it.

### The problem it solves

A server has finite capacity: CPU, memory, file descriptors, database connections, downstream quota, money. Demand, by contrast, is unbounded and bursty. Without a bound, a single misbehaving or malicious client — or an unexpected traffic spike — can:

- **Exhaust shared resources** (the classic "noisy neighbor" problem), starving well-behaved clients.
- **Cause cascading failure**: an overloaded service slows down, callers time out and retry, retries multiply load, the whole fleet topples. This is a **retry storm** / **metastable failure**.
- **Run up unbounded cost**: each call to a paid downstream API (e.g., a third-party SMS, LLM, or payment provider) costs money; uncontrolled calls = uncontrolled bill.
- **Enable abuse**: brute-force password guessing, credential stuffing, scraping, denial-of-service.

Rate limiting is the **first line of defense** that keeps a system inside its safe operating envelope.

### When you reach for it

- **Public/partner APIs** — enforce per-API-key quotas (the basis of API monetization tiers: free = 100 req/min, pro = 10,000 req/min).
- **Authentication endpoints** — cap login attempts to thwart brute force.
- **Expensive operations** — bound calls to costly compute (report generation, LLM inference, video transcode).
- **Protecting downstreams** — your service calls a fragile legacy DB; you limit your own outbound rate (client-side rate limiting / **adaptive concurrency**).
- **Fairness in multi-tenant SaaS** — prevent one tenant from monopolizing a shared cluster.
- **Cost control** — bound spend on metered third-party services.

### The one-paragraph mental model

> Think of a rate limiter as a **gate with a counter**. Each arriving request asks the gate, "may I pass?" The gate consults a small piece of state — *how many requests has this client made recently?* — and answers **yes** (admit), **no** (reject, usually with HTTP 429), or **wait** (queue/delay). The entire discipline of rate limiting is about (a) choosing the **algorithm** that decides yes/no/wait, (b) choosing **where** the gate lives (gateway vs. service vs. client), and (c) — the hard part at scale — making the counter **correct and fast when it must be shared across many machines**.

### Where it sits in the stack

```
                                  ┌───────────────────────────────────────────┐
 Client ──► CDN/WAF ──► Edge LB ──► API Gateway ──► Service Mesh ──► Service ──► DB/Downstream
            (1)          (2)         (3)             (4)             (5)          (6)
```

Rate limiting can be enforced at **any** of these layers, and mature systems enforce at several simultaneously (defense in depth):

1. **CDN/WAF** (Cloudflare, Akamai, AWS WAF) — coarse, IP-based, absorbs volumetric DDoS before it reaches you.
2. **Edge load balancer** (NGINX, Envoy, HAProxy, ALB) — per-IP or per-route connection/request limits.
3. **API Gateway** (Kong, Amazon API Gateway, Apigee, Spring Cloud Gateway) — per-API-key, per-tenant quotas; the most common place for *business* rate limiting.
4. **Service mesh** (Istio/Envoy) — service-to-service limits inside the cluster.
5. **Application/service** — fine-grained, business-aware limits (per-user, per-action) that require app context.
6. **Database/downstream** — connection pools and statement timeouts act as implicit limiters.

We'll define each of these terms in §11 (Glossary) and revisit "where to enforce" in §8.

---

## 2. Foundations from first principles

Build the concept from zero. Every core term is defined as it appears.

### 2.1 Rate, quota, burst — the three numbers

Three quantities describe almost any rate-limit policy:

- **Rate (sustained / average rate)** — the steady-state allowance, e.g., *100 requests per second (RPS)* or *1,000 requests per minute*. This is what the client can do indefinitely.
- **Burst (peak allowance / capacity)** — how much *instantaneous* over-rate the limiter tolerates before clamping. Real traffic is bursty; a limiter that allows exactly 100 RPS and not one more is brittle. A burst of, say, 50 lets a client momentarily send 150 then settle back to 100.
- **Window / refill period** — the time unit over which the rate is measured (per second, per minute, per hour, per day).

The relationship: `rate = quota / window`. A policy of "600 requests per minute" can be enforced as `600/60s` (averages 10 RPS) but *how* the 600 are allowed to be distributed inside the minute is exactly what distinguishes the algorithms in §3.

### 2.2 Admit / reject / shape — the three outcomes

When a request arrives, a limiter chooses one of:

- **Admit** — let it through.
- **Reject (hard limit / shedding)** — refuse it immediately, typically with **HTTP 429 Too Many Requests**. Cheap, predictable, but the client loses the request.
- **Shape (delay / queue / throttle)** — hold the request and release it later when budget is available. Smooths traffic but consumes memory and adds latency; unbounded queues are dangerous.

> **HTTP 429 Too Many Requests** — the standardized status code (RFC 6585) a server returns when a client has sent too many requests in a given time. It is the canonical "rate limited" response. We design the full response in §3.9.

### 2.3 The token / permit abstraction

Most limiters model the allowance as a pool of **tokens** (a.k.a. **permits**). Conceptually:

- The pool holds up to `capacity` tokens.
- Tokens are **added** over time at the configured `rate`.
- Each admitted request **removes** one (or more) tokens.
- If no token is available, the request is rejected or made to wait.

This single abstraction underlies the token-bucket algorithm and Java's `Semaphore` and Guava's `RateLimiter`. Keep it in mind.

### 2.4 Identity & granularity — *who* is being limited

A limiter limits a **key**. Choosing the key is a design decision:

| Granularity | Key | Use case |
|---|---|---|
| Global | constant (e.g., `"global"`) | Protect a single fragile downstream; cap total system throughput |
| Per-IP | client IP | Anti-DDoS, anonymous endpoints |
| Per-user | user ID (from auth token) | Fairness among logged-in users |
| Per-API-key / per-tenant | API key or tenant ID | SaaS quotas, monetization tiers |
| Per-endpoint | route path | Different limits for `/search` vs `/login` |
| Composite | `tenant + endpoint` | "Tenant X may call `/search` 100×/min" |

> **Tenant** — in multi-tenant SaaS, a single customer organization whose data and usage are isolated from other customers sharing the same physical infrastructure. Per-tenant limiting enforces fairness so one customer can't degrade another's experience.

The key is usually composed by concatenation: `ratelimit:{tenant}:{endpoint}`.

### 2.5 Local vs. distributed — the central tension

- **Local (in-process) rate limiting** — the counter lives in the memory of a single process/JVM. Fast (nanoseconds), no network, but only knows about traffic hitting *that one instance*.
- **Distributed rate limiting** — the counter is shared across many instances, usually in a central store (Redis). Correct globally, but every decision may involve a network round-trip and faces concurrency/consistency challenges.

The naive failure: you run 10 service instances behind a load balancer, each with a local "100 RPM" limiter. A client's true global allowance becomes `10 × 100 = 1,000 RPM` — 10× the intended limit, and it drifts as you autoscale. §3.7 covers how to fix this.

### 2.6 Idempotency, retries, and why limits interact with them

> **Idempotent operation** — one that produces the same result whether performed once or many times (e.g., `GET`, or a `PUT` that sets a value). Non-idempotent operations (e.g., "charge card", a naive `POST`) change state each time.

Clients that get rejected (429) typically **retry**. If many clients retry simultaneously after an outage, you get a **thundering herd**. Good rate-limit responses include a **`Retry-After`** hint and clients should add **jitter** (randomized backoff) — both defined and detailed in §3.9 and §6.

### 2.7 A first, minimal limiter (to anchor the rest)

The simplest correct limiter — a fixed window counter — in plain Java:

```java
// A naive single-key, single-instance fixed-window limiter. NOT thread-safe yet.
class NaiveFixedWindow {
    private final int limit;        // max requests per window
    private final long windowMs;    // window length in milliseconds
    private long windowStart;       // start timestamp of current window
    private int count;              // requests seen in current window

    NaiveFixedWindow(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.windowStart = System.currentTimeMillis();
        this.count = 0;
    }

    boolean allow() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= windowMs) {   // window expired → reset
            windowStart = now;
            count = 0;
        }
        if (count < limit) {                    // budget remains → admit
            count++;
            return true;
        }
        return false;                           // budget exhausted → reject
    }
}
```

This is correct for one thread and one process. The rest of this chapter is, in essence, the story of everything wrong or incomplete about this 20-line class — thread safety, burst behavior at window edges, multi-instance correctness, fairness, response design, and operations.

---

## 3. How it works internally

This is the heart of the document. We cover each algorithm in depth: the data it keeps, the step-by-step control flow, its burst and edge-case behavior, and complete code.

### 3.0 What every algorithm must decide

For each incoming request keyed by `K` at time `t`, the limiter must:

1. **Locate or initialize** the per-key state.
2. **Account for elapsed time** since the last update (refill tokens / advance window / expire old entries).
3. **Test** whether budget remains.
4. **Mutate** state (consume a token / increment a counter) if admitting.
5. **Return** a decision plus metadata (remaining, reset time, retry-after).

Steps 2–4 must be **atomic** under concurrency (multiple threads, multiple machines) — otherwise two requests both read "budget=1" and both consume it, admitting 2 when only 1 was allowed. Atomicity is the recurring difficulty.

---

### 3.1 Fixed Window Counter

**Idea.** Divide time into fixed, non-overlapping windows (e.g., each calendar minute). Keep one counter per key per window. Admit while `count < limit`; increment on admit; the counter resets to zero at the window boundary.

**State per key:** `{ windowStart, count }` (or, in a key-value store, a counter at key `K:{windowId}` with a TTL).

**Control flow:**

```
on request(K, t):
    windowId = floor(t / windowSize)
    counterKey = K + ":" + windowId
    c = INCR(counterKey)              # atomic increment, returns new value
    if c == 1: EXPIRE(counterKey, windowSize)  # set TTL on first write
    if c <= limit: ADMIT
    else: REJECT
```

**Strengths:** trivially simple; O(1) memory per key per window; maps perfectly to Redis `INCR`+`EXPIRE`; counters auto-expire.

**The fatal flaw — boundary bursting (a.k.a. the "double-rate at the edges" problem).** Because each window is independent, a client can send `limit` requests in the *last instant* of window N and another `limit` in the *first instant* of window N+1 — i.e., **`2 × limit` requests in a span shorter than one window**.

```
Limit = 100/min.
12:00:59.900 → 100 requests (fills window [12:00, 12:01))
12:01:00.100 → 100 requests (fills window [12:01, 12:02))
Net: 200 requests in 200 milliseconds, while the "limit" was 100/min.
```

This is the canonical reason fixed window is considered a *coarse* limiter. It's fine for protecting against gross abuse, bad for precise fairness.

**Complete thread-safe Java (single instance):**

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single-instance fixed-window limiter, multi-key, thread-safe. */
public class FixedWindowLimiter {
    private final int limit;
    private final long windowMs;
    // Per key: packed state. We store windowId and count in two maps for clarity.
    private final ConcurrentHashMap<String, AtomicLong> windowIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> counts    = new ConcurrentHashMap<>();

    public FixedWindowLimiter(int limit, long windowMs) {
        this.limit = limit; this.windowMs = windowMs;
    }

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        long currentWindow = now / windowMs;
        AtomicLong windowId = windowIds.computeIfAbsent(key, k -> new AtomicLong(currentWindow));
        AtomicLong count    = counts.computeIfAbsent(key, k -> new AtomicLong(0));

        // Synchronize the read-modify-write on this key's state to avoid a race
        // where two threads both reset/increment inconsistently.
        synchronized (count) {
            if (windowId.get() != currentWindow) {   // new window → reset
                windowId.set(currentWindow);
                count.set(0);
            }
            if (count.get() < limit) {
                count.incrementAndGet();
                return true;
            }
            return false;
        }
    }
}
```

Note: even `AtomicLong` alone isn't enough here because the *reset-then-test-then-increment* sequence is a compound action; we guard it with `synchronized` on the per-key object. (A lock-free version is possible with a single packed `long` and CAS — shown in §7.)

---

### 3.2 Sliding Window Log

**Idea.** Keep the **exact timestamp of every request** for each key in a sorted structure. On each request at time `t`, drop all timestamps older than `t − window`, then admit iff the remaining count `< limit`, appending `t` on admit.

**State per key:** an ordered collection (sorted set / deque) of timestamps within the last `window`.

**Control flow:**

```
on request(K, t):
    log = timestamps[K]
    evict all e in log where e <= t - window      # drop expired
    if size(log) < limit:
        log.add(t)
        ADMIT
    else:
        REJECT
```

**Strengths:** **perfectly accurate** — it enforces "no more than `limit` requests in *any* rolling `window`," with no boundary bursting at all. It's the ground truth other algorithms approximate.

**Weakness:** **memory cost is O(limit) per key.** If you allow 10,000 req/min for 1M keys, you store up to 10 billion timestamps. Also more CPU (eviction scan). This is why pure log is reserved for *low-limit, high-value* cases (e.g., login: 5 attempts / 15 min).

**Complete Java (single instance):**

```java
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window LOG limiter: exact, memory-heavy. */
public class SlidingWindowLogLimiter {
    private final int limit;
    private final long windowMs;
    private final ConcurrentHashMap<String, Deque<Long>> logs = new ConcurrentHashMap<>();

    public SlidingWindowLogLimiter(int limit, long windowMs) {
        this.limit = limit; this.windowMs = windowMs;
    }

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (log) {
            long cutoff = now - windowMs;
            // Evict timestamps older than the window from the head (oldest first).
            while (!log.isEmpty() && log.peekFirst() <= cutoff) {
                log.pollFirst();
            }
            if (log.size() < limit) {
                log.addLast(now);
                return true;
            }
            return false;
        }
    }
}
```

In Redis this maps to a **Sorted Set (ZSET)** where score = timestamp: `ZREMRANGEBYSCORE` to evict, `ZCARD` to count, `ZADD` to insert — done atomically in a Lua script (§3.7).

---

### 3.3 Sliding Window Counter (the pragmatic favorite)

**Idea.** A hybrid that approximates the sliding-window-log's accuracy with the fixed-window's cheapness. Keep counters for the **current** and **previous** fixed windows, and **weight the previous window's count by the fraction that still overlaps** the rolling window.

**Formula.** Let `prev` = previous window's count, `cur` = current window's count, and let `f` ∈ [0,1] be the fraction of the *previous* window still inside the rolling window:

```
estimated_count = prev * f + cur
admit iff estimated_count < limit
```

where `f = 1 − (elapsed_in_current_window / window)`.

**Example.** Limit 100/min. At 75% through the current minute, `f = 0.25`. If previous minute had 80 and current has 30, estimate = `80*0.25 + 30 = 50 < 100` → admit. As the current window advances, `f → 0`, smoothly forgetting the previous window's contribution — eliminating the hard boundary burst of fixed window.

**Why it's loved:** O(1) memory (two counters per key), no boundary cliff, and accuracy is excellent in practice. Cloudflare published a well-known analysis showing the approximation error is tiny (well under 1% for typical traffic) compared to the exact sliding log, at a fraction of the cost. (Cloudflare's blog reports ~0.003% requests wrongly allowed/denied on their data — verify against your own traffic shape; the error grows if traffic is extremely bursty right at window edges.)

**Assumption baked in:** it assumes requests in the previous window were *uniformly distributed*. If the previous window's traffic was all clustered at its end, the estimate slightly under-counts the still-relevant requests. For nearly all production traffic this is acceptable.

**Complete Java (single instance):**

```java
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window COUNTER limiter: O(1) memory, smooth, near-exact. */
public class SlidingWindowCounterLimiter {
    private final int limit;
    private final long windowMs;

    private static final class State {
        long curWindowId = -1;
        long curCount = 0;
        long prevCount = 0;
    }
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public SlidingWindowCounterLimiter(int limit, long windowMs) {
        this.limit = limit; this.windowMs = windowMs;
    }

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        long windowId = now / windowMs;
        State s = states.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            if (s.curWindowId == windowId) {
                // same window: nothing to roll
            } else if (s.curWindowId == windowId - 1) {
                // moved to the immediately next window: current becomes previous
                s.prevCount = s.curCount;
                s.curCount = 0;
                s.curWindowId = windowId;
            } else {
                // jumped 2+ windows ahead: both windows are stale → reset
                s.prevCount = 0;
                s.curCount = 0;
                s.curWindowId = windowId;
            }
            long elapsedInWindow = now - windowId * windowMs;
            double f = 1.0 - ((double) elapsedInWindow / windowMs); // overlap fraction of prev window
            double estimated = s.prevCount * f + s.curCount;
            if (estimated < limit) {
                s.curCount++;
                return true;
            }
            return false;
        }
    }
}
```

---

### 3.4 Token Bucket (the most widely used)

**Idea.** A bucket holds up to `capacity` tokens. Tokens are added at a steady `refillRate` (tokens per second), capping at `capacity`. Each request costs `cost` tokens (usually 1, but heavier operations can cost more — *weighted* rate limiting). Admit iff enough tokens; consume on admit. If short, reject (or wait until enough tokens accrue).

**The crucial property:** the bucket **allows bursts up to `capacity`** while enforcing the long-run average `refillRate`. This matches real traffic beautifully — clients can spike briefly (drain the bucket) then must throttle to the refill rate. `capacity` is the burst size; `refillRate` is the sustained rate. Decoupling them is the algorithm's superpower.

**Lazy refill (the standard efficient implementation).** You don't run a background thread adding tokens. Instead, on each request you compute how many tokens *should* have accrued since the last access and add them then:

```
on request(K, t, cost):
    state = bucket[K]
    elapsed = t - state.lastRefill
    state.tokens = min(capacity, state.tokens + elapsed * refillRatePerMs)
    state.lastRefill = t
    if state.tokens >= cost:
        state.tokens -= cost
        ADMIT
    else:
        REJECT  (or compute wait = (cost - tokens) / refillRatePerMs and SHAPE)
```

**State per key:** `{ tokens (double), lastRefill (timestamp) }` — O(1) memory.

**Complete Java (single instance, lazy refill):**

```java
import java.util.concurrent.ConcurrentHashMap;

/** Token-bucket limiter: O(1) memory, supports bursts and weighted costs. */
public class TokenBucketLimiter {
    private final double capacity;       // max tokens (burst size)
    private final double refillPerMs;    // tokens added per millisecond (sustained rate)

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
    }
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** @param ratePerSec sustained rate, @param burst max burst capacity */
    public TokenBucketLimiter(double ratePerSec, double burst) {
        this.capacity = burst;
        this.refillPerMs = ratePerSec / 1000.0;
    }

    public boolean allow(String key) { return allow(key, 1.0); }

    /** cost lets heavy operations consume multiple tokens (weighted limiting). */
    public boolean allow(String key, double cost) {
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = capacity;      // start full so the first burst is allowed
            nb.lastRefillMs = now;
            return nb;
        });
        synchronized (b) {
            // Lazy refill: add tokens for the elapsed time, capped at capacity.
            double elapsed = now - b.lastRefillMs;
            b.tokens = Math.min(capacity, b.tokens + elapsed * refillPerMs);
            b.lastRefillMs = now;
            if (b.tokens >= cost) {
                b.tokens -= cost;
                return true;
            }
            return false;
        }
    }

    /** Compute milliseconds until `cost` tokens are available (for Retry-After / shaping). */
    public long millisUntilAvailable(String key, double cost) {
        Bucket b = buckets.get(key);
        if (b == null) return 0;
        synchronized (b) {
            double deficit = cost - b.tokens;
            if (deficit <= 0) return 0;
            return (long) Math.ceil(deficit / refillPerMs);
        }
    }
}
```

This is essentially what **Guava's `RateLimiter`** (smooth) and **Bucket4j** implement, with more sophistication (Guava also "borrows" against future permits — see §7.2).

---

### 3.5 Leaky Bucket (the traffic shaper)

**Idea.** Imagine a bucket with a hole that leaks at a fixed rate. Incoming requests are water poured in. If the bucket overflows (queue full), excess is dropped. The hole drains at a constant `outflowRate`, so **output is perfectly smooth regardless of input burstiness**.

There are two formulations, often conflated:

**(a) Leaky bucket as a queue (shaping).** Requests enter a FIFO queue of size `capacity`. A background process dequeues and processes at exactly `outflowRate`. Bursts are buffered and released at a steady pace; overflow is rejected.
- **Output is constant-rate and smooth** — its defining feature, ideal for protecting a downstream that hates bursts.
- Adds latency (requests wait in the queue); needs memory for the queue; if you need *immediate* responses, this is awkward.

**(b) Leaky bucket as a meter (the "GCRA"/virtual-scheduling view).** Mathematically equivalent to a token bucket with `cost`-sized leaks. We'll treat the queue formulation here; the meter formulation is covered as **GCRA** in §7.3.

**Token bucket vs leaky bucket — the key distinction:**
- **Token bucket** allows bursts (output can be bursty up to capacity); it limits the *average*. Good when bursts are fine.
- **Leaky bucket (queue)** *forbids* output bursts; it produces a smooth stream. Good when the protected resource cannot tolerate bursts at all.

**Complete Java (queue-based leaky bucket):**

```java
import java.util.concurrent.*;

/** Leaky-bucket shaper: admits into a bounded queue, drains at fixed rate. */
public class LeakyBucketShaper {
    private final BlockingQueue<Runnable> queue;
    private final ScheduledExecutorService leaker = Executors.newSingleThreadScheduledExecutor();

    /** @param capacity max queued tasks, @param ratePerSec drain rate */
    public LeakyBucketShaper(int capacity, double ratePerSec) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        long periodMicros = (long) (1_000_000.0 / ratePerSec);  // one leak per period
        leaker.scheduleAtFixedRate(() -> {
            Runnable task = queue.poll();   // leak exactly one drop per tick
            if (task != null) task.run();
        }, 0, periodMicros, TimeUnit.MICROSECONDS);
    }

    /** Returns false if the bucket is full (request dropped). Non-blocking. */
    public boolean offer(Runnable task) {
        return queue.offer(task);   // overflow → false → caller returns 429
    }
}
```

In production you'd use a proper executor/scheduler and likely a leaky-bucket *meter* (GCRA) for synchronous APIs rather than literally queueing HTTP requests.

---

### 3.6 Side-by-side: the five core algorithms

| Algorithm | State/key | Memory | Burst behavior | Accuracy | Best for |
|---|---|---|---|---|---|
| Fixed window | counter | O(1) | **Up to 2× at edges** | Coarse | Cheap coarse caps, gross abuse |
| Sliding window log | timestamps | **O(limit)** | None (exact) | **Exact** | Low limits, high precision (login) |
| Sliding window counter | 2 counters | O(1) | None (smooth) | ~99%+ | **General-purpose default** |
| Token bucket | tokens+ts | O(1) | **Allowed up to capacity** | Average-exact | APIs that should tolerate bursts |
| Leaky bucket (queue) | queue | O(capacity) | **Smoothed away** | Output-exact | Protecting burst-intolerant downstreams |

Rule of thumb: **sliding window counter** for "fair, smooth caps"; **token bucket** when you *want* to permit bursts; **sliding window log** when you need exactness on small limits; **leaky bucket** when the downstream demands constant-rate input.

---

### 3.7 Distributed rate limiting — the hard part

When traffic is spread across N instances, a shared, authoritative counter is needed. **Redis** is the de-facto store because it's single-threaded per shard (giving atomicity), in-memory (fast — sub-millisecond ops), and supports server-side **Lua scripts** (atomic multi-step logic) and `INCR`/`EXPIRE`/`ZSET` primitives.

> **Redis** — an in-memory key-value data store. Commands on a single Redis instance execute one at a time (single-threaded command processing), so each command is atomic. It supports rich types (strings, hashes, sorted sets) and **Lua scripting**, which runs a whole script atomically with no other command interleaving.

> **Lua script (in Redis)** — a small program sent to Redis with `EVAL`/`EVALSHA`. Redis executes the entire script atomically and in isolation. This lets you bundle "read counter, compute, conditionally write" into one indivisible operation — exactly what a correct limiter needs. `EVALSHA` runs a script previously cached by its SHA-1 hash to avoid resending the source each call.

#### 3.7.1 The race condition you must avoid

Naive distributed fixed window using two round-trips:

```
GET counter        # instance A reads 99, instance B reads 99 (both think 1 left)
... (both decide ADMIT) ...
INCR counter        # A → 100, B → 101
```

Both admitted; you served 101 against a limit of 100. This is a classic **read-modify-write race** (a.k.a. **check-then-act** / **TOCTOU — time-of-check to time-of-use**). The fix is **atomicity**: do the whole decision in one indivisible operation.

> **Race condition** — a bug where the outcome depends on the unpredictable interleaving of concurrent operations. Here, two clients interleave their read and write so both observe stale state and both proceed.

Two correct approaches:

1. **`INCR` first, then test** (for fixed window): `INCR` is atomic and returns the post-increment value. Admit iff the returned value `≤ limit`. No read-modify-write gap. (Downside: rejected requests still increment, so a flood of rejects keeps the counter pinned high until the window expires — usually fine, but be aware.)
2. **Lua script** for anything more complex (token bucket, sliding window) where you must read, compute, *conditionally* write, and return rich metadata — all atomically.

#### 3.7.2 Distributed fixed window (atomic, with `INCR`)

```lua
-- KEYS[1] = counter key (already includes window id)
-- ARGV[1] = limit, ARGV[2] = window TTL seconds
local current = redis.call("INCR", KEYS[1])
if current == 1 then
    redis.call("EXPIRE", KEYS[1], tonumber(ARGV[2]))
end
if current > tonumber(ARGV[1]) then
    return 0   -- reject
else
    return 1   -- allow
end
```

Note we still over-count on rejects (each rejected call INCRs). A refinement only counts admits, but then you reintroduce a check-then-act unless done in Lua — which is exactly why the Lua scripts below decrement/increment *conditionally*.

#### 3.7.3 Distributed token bucket (atomic Lua) — production pattern

Stores tokens + last-refill timestamp in a Redis hash, refills lazily inside the script:

```lua
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (burst)
-- ARGV[2] = refill rate (tokens per second)
-- ARGV[3] = now (ms, from the client/caller — see note on clocks below)
-- ARGV[4] = requested tokens (cost)
-- ARGV[5] = TTL seconds (auto-expire idle buckets)
local capacity   = tonumber(ARGV[1])
local rate       = tonumber(ARGV[2])
local now        = tonumber(ARGV[3])
local requested  = tonumber(ARGV[4])
local ttl        = tonumber(ARGV[5])

local data = redis.call("HMGET", KEYS[1], "tokens", "ts")
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then            -- first time: start full
    tokens = capacity
    ts = now
end

-- Lazy refill: add tokens for elapsed time, capped at capacity.
local elapsed = math.max(0, now - ts) / 1000.0   -- seconds
tokens = math.min(capacity, tokens + elapsed * rate)
ts = now

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call("HMSET", KEYS[1], "tokens", tokens, "ts", ts)
redis.call("EXPIRE", KEYS[1], ttl)

-- Return: allowed flag, tokens remaining, ms until next token (for Retry-After)
local retry_ms = 0
if allowed == 0 then
    retry_ms = math.ceil((requested - tokens) / rate * 1000)
end
return { allowed, math.floor(tokens), retry_ms }
```

> **Clock note (important).** Notice `now` is passed in (`ARGV[3]`) rather than read inside Redis with `redis.call("TIME")`. Two correctness considerations: (1) Using `redis.call("TIME")` makes the script **non-deterministic**, which historically broke replication on older Redis versions (Redis 5+ uses *effect* replication by default, so it's now generally fine, but passing time in is the portable, deterministic choice). (2) If you pass time from *callers*, all callers must have **synchronized clocks** (NTP); skew causes refill miscalculations. The safest production choice is to read time once in Redis (single source of truth) on modern Redis, or pass a single coordinator's time. Flag this in design reviews.

#### 3.7.4 Distributed sliding window log (atomic Lua with ZSET)

```lua
-- KEYS[1] = zset key
-- ARGV[1] = now (ms), ARGV[2] = window (ms), ARGV[3] = limit, ARGV[4] = unique member id
local now    = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit  = tonumber(ARGV[3])
local member = ARGV[4]            -- e.g., "now-uuid" to avoid score collisions

redis.call("ZREMRANGEBYSCORE", KEYS[1], 0, now - window)   -- evict expired
local count = redis.call("ZCARD", KEYS[1])
if count < limit then
    redis.call("ZADD", KEYS[1], now, member)
    redis.call("PEXPIRE", KEYS[1], window)                 -- auto-clean idle keys
    return { 1, limit - count - 1 }                        -- allowed, remaining
else
    return { 0, 0 }                                        -- rejected
end
```

#### 3.7.5 Cell-based / token-bucket via Redis module — `CL.THROTTLE`

The **`redis-cell`** module exposes `CL.THROTTLE key max_burst count_per_period period [quantity]`, a GCRA-based token bucket implemented in Rust, atomic and returning rich metadata (allowed flag, limit, remaining, retry-after, reset-after) in one command. If you can install modules, it's a battle-tested option that removes the need to maintain your own Lua.

#### 3.7.6 Reducing Redis load: approximate & batched approaches

A Redis hit per request can itself become a bottleneck and a SPOF (single point of failure). Mitigations:

- **Local-token batching / "take a slice":** each instance periodically *leases* a batch of tokens from Redis (e.g., 50 at a time) and serves locally until depleted, then re-leases. Cuts Redis traffic ~50× at the cost of slight over-admission near boundaries. (This is roughly how some large systems and the Stripe/Lyft-style limiters work.)
- **Eventually-consistent local + async sync:** each instance limits locally with `limit/N` and periodically reconciles via Redis or gossip. Accepts drift for resilience.
- **Sharding the key space:** distribute hot keys across Redis cluster slots; use hash tags `{tenant}` to keep a tenant's keys co-located when a script touches multiple keys.

> **SPOF (single point of failure)** — a component whose failure takes down the whole system. A single Redis instance fronting all rate-limit decisions is a SPOF; use replicas, clustering, or graceful degradation (§9).

#### 3.7.7 Fail-open vs fail-closed

If Redis is unreachable, what do you do?

- **Fail-open** — admit the request (don't let the limiter's outage become an availability outage). Standard for *protective* limiting where availability > strict enforcement. Risk: during the Redis outage you have no protection.
- **Fail-closed** — reject. Used where exceeding the limit is unacceptable (e.g., a hard cost or legal cap, or a fragile downstream that *must* be protected).

Most public-API gateways **fail open with a local fallback limiter** (degrade to per-instance limits) so you still have *some* protection without coupling availability to Redis. Make this an explicit, tested decision.

---

### 3.8 Multi-tier limits: per-user, per-tenant, global

Real systems compose **multiple limits checked together** (all must pass):

```
allow(req) =
    perUserLimiter.allow(req.userId)        AND
    perTenantLimiter.allow(req.tenantId)    AND
    perEndpointLimiter.allow(req.route)     AND
    globalLimiter.allow("global")
```

- **Per-user** — fairness among individuals.
- **Per-tenant** — fairness among customer orgs; the tenant's plan determines the limit (free vs enterprise).
- **Global** — a hard ceiling protecting the cluster/downstream regardless of who's calling (a *safety valve*).

Design tips:
- **Check cheap/local limits before expensive/remote ones** to short-circuit (test in-process global cap before a Redis per-user call).
- **Hierarchical quotas:** a tenant's total budget can be sub-allocated to its users (e.g., tenant gets 10k/min; each user capped at 2k/min). Implement as nested checks.
- **Atomicity across multiple limiters** is usually *not* enforced (each check is independent); if one passes and a later one rejects, you may want to *refund* the earlier token (decrement). Decide whether a partial admit "wastes" budget; for most systems the tiny over/under-count is acceptable, but a Lua script can check-and-consume all tiers atomically if you co-locate keys with a hash tag.

---

### 3.9 Response design — speaking "rate-limited" correctly

When you reject, the *response* is part of the contract. Get it right and clients behave; get it wrong and they hammer you.

**Status code:**
- **`429 Too Many Requests`** — the correct, standardized code (RFC 6585) for "you've been rate limited."
- Avoid `403`/`503` for rate limiting — `503 Service Unavailable` is acceptable for *load shedding* (server-wide overload) but `429` specifically means *this client* exceeded *its* quota.

**Headers — tell the client where it stands:**

| Header | Meaning | Example |
|---|---|---|
| `Retry-After` | Seconds (or HTTP-date) to wait before retrying | `Retry-After: 30` |
| `RateLimit-Limit` | The ceiling for this window | `RateLimit-Limit: 100` |
| `RateLimit-Remaining` | Tokens/requests left in current window | `RateLimit-Remaining: 0` |
| `RateLimit-Reset` | Seconds until the window resets (or epoch) | `RateLimit-Reset: 30` |

> **Standardization caveat (version/vendor-specific).** Historically vendors used `X-RateLimit-*` (GitHub, Twitter). The IETF has been standardizing **`RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset`** (draft, formerly "RateLimit Header Fields for HTTP"; the newest draft uses a structured `RateLimit` field). As of writing this is still draft/evolving — pick a convention, document it, and ideally emit both the legacy `X-RateLimit-*` and the new form for compatibility. `Retry-After` (RFC 7231) is fully standard and the one clients respect most universally.

**Body:** a small JSON with a machine-readable error code and human message:

```json
{ "error": "rate_limited", "message": "Quota exceeded. Retry after 30s.", "retry_after": 30 }
```

**Spring example emitting a correct 429:**

```java
import org.springframework.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;

public class RateLimitInterceptor implements HandlerInterceptor {
    private final TokenBucketLimiter limiter;
    public RateLimitInterceptor(TokenBucketLimiter limiter) { this.limiter = limiter; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String key = resolveKey(req);                 // e.g., API key or user id
        if (limiter.allow(key)) {
            // Optionally emit RateLimit-* headers on success too.
            return true;                              // continue to the controller
        }
        long retryMs = limiter.millisUntilAvailable(key, 1.0);
        long retrySec = Math.max(1, (long) Math.ceil(retryMs / 1000.0));
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());        // 429
        res.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retrySec));
        res.setHeader("RateLimit-Remaining", "0");
        res.setHeader("RateLimit-Reset", String.valueOf(retrySec));
        res.setContentType("application/json");
        res.getWriter().write(
            "{\"error\":\"rate_limited\",\"retry_after\":" + retrySec + "}");
        return false;                                 // stop the request here
    }
    private String resolveKey(HttpServletRequest req) {
        String k = req.getHeader("X-API-Key");
        return k != null ? k : req.getRemoteAddr();
    }
}
```

---

## 4. The complete toolkit

What's available across the JVM ecosystem and infra, with purpose, key parameters, and defaults. Flagged where version/vendor-specific.

### 4.1 JDK / standard library primitives

| Tool | Class | Purpose | Key params / methods | Notes |
|---|---|---|---|---|
| Counting semaphore | `java.util.concurrent.Semaphore` | Bound *concurrency* (in-flight count), not rate | `acquire()`, `tryAcquire(timeout)`, `release()`, fairness flag | Limits *simultaneous* permits, not requests/sec. Use for concurrency limiting; combine with a clock for rate. |
| Atomic counter | `AtomicLong`/`LongAdder` | Lock-free counters for fixed-window | `incrementAndGet`, `getAndAdd` | `LongAdder` scales better under high contention. |
| Scheduled refill | `ScheduledExecutorService` | Background token refill / leaky drain | `scheduleAtFixedRate` | Prefer lazy refill to avoid a thread per bucket. |
| Delay / shaping | `DelayQueue`, `BlockingQueue` | Queue-based leaky bucket | `offer`, `poll` | Bounded queues only — never unbounded. |

> **`Semaphore` vs rate limiter.** A semaphore limits **concurrency** ("at most 10 requests in flight"); a rate limiter limits **rate** ("at most 10 requests *per second*", regardless of how long each takes). They solve different problems and are often used together. Concurrency limiting is actually a very robust form of overload protection (see §7.5, adaptive concurrency).

### 4.2 Guava `RateLimiter` (Google Guava)

| Aspect | Detail |
|---|---|
| Class | `com.google.common.util.concurrent.RateLimiter` |
| Factory | `RateLimiter.create(double permitsPerSecond)` ; `RateLimiter.create(rate, warmupPeriod, unit)` |
| Acquire | `acquire()` (blocks, returns seconds slept), `acquire(n)`, `tryAcquire()`, `tryAcquire(timeout, unit)` |
| Algorithm | Token-bucket variant; **smooth** (`SmoothBursty`) by default, or **`SmoothWarmingUp`** to ramp permits after idleness |
| Burst | Can accumulate unused permits for up to ~1 second of bursts (`maxBurstSeconds`); also *borrows from the future* — `acquire()` returns immediately but the **next** caller pays the wait |
| Scope | **In-process only** (single JVM). Not distributed. `@Beta` historically. |
| Defaults | No warmup; permits accumulate up to 1s worth |

**Gotcha:** Guava `RateLimiter.acquire()` is *blocking* by design — it sleeps the calling thread. For non-blocking server endpoints prefer `tryAcquire()`. And it is **not distributed** — only good for client-side outbound throttling or single-instance use.

### 4.3 Bucket4j (the popular JVM rate-limiting library)

| Aspect | Detail |
|---|---|
| Model | Token bucket with one or more **bandwidths** (multiple limits on one bucket, e.g., 100/sec AND 5000/hour) |
| Build | `Bandwidth.simple(capacity, Duration)` / `Bandwidth.classic(capacity, Refill.greedy(...))`; `Bucket.builder().addLimit(...).build()` |
| Refill modes | `Refill.greedy` (refills continuously/proportionally) vs `Refill.intervally` (refills the whole batch at interval boundaries) |
| Consume | `tryConsume(n)`, `tryConsumeAndReturnRemaining(n)` (returns `ConsumptionProbe` with remaining + nanos-to-refill for `Retry-After`), `asBlocking().consume(n)` |
| Distributed | **Yes** — backends (`JCache`, Hazelcast, Ignite, **Redis** via Redisson/Lettuce, Infinispan, PostgreSQL, MySQL) with atomic CAS-based logic |
| Use | First-class Spring integration; the go-to for production JVM rate limiting |

Bucket4j's distributed mode handles the atomicity for you over the chosen grid/store — you don't write Lua. **Recommended default for JVM apps** needing distributed limits.

### 4.4 Resilience4j `RateLimiter`

| Aspect | Detail |
|---|---|
| Class | `io.github.resilience4j.ratelimiter.RateLimiter` |
| Algorithm | **Atomic, fixed window of permits per refresh period** (`limitForPeriod` permits each `limitRefreshPeriod`) |
| Config | `limitForPeriod` (e.g., 10), `limitRefreshPeriod` (e.g., 1s), `timeoutDuration` (how long a call waits for a permit before failing) |
| Style | Functional decorators / annotations (`@RateLimiter(name="x")`), integrates with circuit breaker, retry, bulkhead |
| Scope | In-process (per node). Pair with a distributed store for global limits. |

Resilience4j is part of the broader **resilience pattern** family (circuit breaker, bulkhead, retry, time limiter), so it's chosen when you want one library for *all* resilience concerns.

> **Circuit breaker** — a pattern that stops calling a failing dependency after an error threshold ("trips open"), failing fast for a cooldown, then cautiously retrying ("half-open"). Complementary to rate limiting: limiter caps *incoming* load; breaker protects *outgoing* calls to a sick dependency.
> **Bulkhead** — isolates resources (e.g., a separate thread pool per dependency) so one failing dependency can't consume all threads. Often a *concurrency* limit.

### 4.5 Gateway & proxy layer

| System | Mechanism | Key config | Scope/notes |
|---|---|---|---|
| **NGINX** | `limit_req` (leaky bucket), `limit_conn` | `limit_req_zone $key zone=name:10m rate=10r/s; limit_req zone=name burst=20 nodelay;` | Per-node by default; shared via Redis only with 3rd-party/NGINX Plus. `burst` = queue, `nodelay` = serve burst immediately. |
| **Envoy** | Local rate limit filter + **global RLS** (gRPC Rate Limit Service) | `token_bucket` (max_tokens, tokens_per_fill, fill_interval); descriptors for keys | Global RLS uses an external service backed by Redis/Memcached. Used by Istio. |
| **Spring Cloud Gateway** | `RequestRateLimiter` filter with `RedisRateLimiter` | `replenishRate`, `burstCapacity`, `requestedTokens`; `KeyResolver` bean | Token bucket via **Redis + Lua** out of the box. Distributed. |
| **Kong** | `rate-limiting` / `rate-limiting-advanced` plugins | `minute`, `hour`, `policy=local|cluster|redis`, `sync_rate` | `redis` policy = distributed; advanced uses sliding window + Redis. |
| **AWS API Gateway** | Usage plans + throttling | `rateLimit` (steady RPS, token bucket), `burstLimit` (bucket capacity), per-key quotas | Account-level default soft limit (historically ~10,000 RPS, 5,000 burst — *verify current AWS docs*). |
| **HAProxy** | stick-tables + `http-request track-sc`/`deny` | `stick-table type ip size 1m expire 10s store http_req_rate(10s)` | Per-instance; peers protocol can share tables across nodes. |

> **API Gateway** — a single entry point in front of backend services that handles cross-cutting concerns: auth, routing, rate limiting, request transformation, observability. The most common place to enforce *business* rate limits because it sees the API key/tenant and protects all services uniformly.
> **Service mesh / Envoy** — Envoy is a high-performance L7 proxy deployed as a "sidecar" next to each service in a **service mesh** (e.g., Istio), handling inter-service traffic including rate limiting. **RLS (Rate Limit Service)** is a separate gRPC service Envoy queries for global decisions.

### 4.6 Redis primitives & commands relevant to limiting

| Command | Use |
|---|---|
| `INCR` / `INCRBY` | Atomic counter for fixed window |
| `EXPIRE` / `PEXPIRE` / `SET key v EX n` | TTL so counters/buckets auto-clean |
| `EVAL` / `EVALSHA` | Run atomic Lua scripts (token bucket, sliding window) |
| `HMGET` / `HMSET` / `HINCRBYFLOAT` | Store token-bucket state (tokens + ts) in a hash |
| `ZADD` / `ZREMRANGEBYSCORE` / `ZCARD` | Sliding-window-log via sorted set |
| `CL.THROTTLE` (redis-cell module) | Turnkey GCRA token bucket |
| Hash tags `{...}` | Co-locate multiple keys on one cluster slot for multi-key Lua |

### 4.7 Cloud-native API quota services

| Service | Notes |
|---|---|
| AWS WAF rate-based rules | IP-based, 5-minute evaluation window (*verify*), good for volumetric defense |
| GCP Apigee / Cloud Armor | Apigee `Quota` and `SpikeArrest` policies (quota = counted limit, spike arrest = smoothing/leaky) |
| Azure API Management | `rate-limit`, `rate-limit-by-key`, `quota` policies |

---

## 5. Code examples by use case

Distinct real scenarios, each idiomatic and copy-adaptable.

### 5.1 Public API per-API-key quota at the gateway (Spring Cloud Gateway + Redis)

`application.yml`:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: orders-service
          uri: lb://orders
          predicates:
            - Path=/api/orders/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100   # sustained tokens/sec
                redis-rate-limiter.burstCapacity: 200    # bucket size (burst)
                redis-rate-limiter.requestedTokens: 1    # cost per request
                key-resolver: "#{@apiKeyResolver}"       # bean below
```

Key resolver bean (limit per API key, falling back to IP):

```java
@Configuration
public class RateLimitConfig {
    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (apiKey == null || apiKey.isBlank()) {
                // Fall back to client IP for anonymous traffic.
                apiKey = exchange.getRequest().getRemoteAddress() == null ? "anon"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            }
            return Mono.just(apiKey);   // reactive: KeyResolver returns Mono<String>
        };
    }
}
```

Spring's `RedisRateLimiter` uses a token-bucket Lua script and emits `X-RateLimit-Remaining`/`X-RateLimit-Burst-Capacity`/`X-RateLimit-Replenish-Rate` headers automatically. Distributed and correct out of the box.

### 5.2 Per-tenant tiered limits with Bucket4j (distributed via Redis/Redisson)

```java
import io.github.bucket4j.*;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import java.util.function.Supplier;

public class TenantRateLimiter {
    private final ProxyManager<String> proxyManager;  // backed by Redis (Redisson/Lettuce)

    public TenantRateLimiter(ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    // Plan-based configuration: tier → (rate per minute, burst).
    private BucketConfiguration configFor(Plan plan) {
        Bandwidth perMinute = switch (plan) {
            case FREE       -> Bandwidth.classic(100,   Refill.greedy(100,   Duration.ofMinutes(1)));
            case PRO        -> Bandwidth.classic(10_000,Refill.greedy(10_000,Duration.ofMinutes(1)));
            case ENTERPRISE -> Bandwidth.classic(1_000_000, Refill.greedy(1_000_000, Duration.ofMinutes(1)));
        };
        // A second bandwidth caps daily usage simultaneously (multi-limit bucket).
        Bandwidth perDay = Bandwidth.classic(planDailyCap(plan),
                Refill.intervally(planDailyCap(plan), Duration.ofDays(1)));
        return BucketConfiguration.builder().addLimit(perMinute).addLimit(perDay).build();
    }

    public ConsumptionProbe tryConsume(String tenantId, Plan plan) {
        Supplier<BucketConfiguration> cfg = () -> configFor(plan);
        Bucket bucket = proxyManager.builder().build("rl:tenant:" + tenantId, cfg);
        // Atomic across all instances via Redis; returns remaining + nanos to refill.
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    private long planDailyCap(Plan p) {
        return switch (p) { case FREE -> 10_000; case PRO -> 5_000_000; case ENTERPRISE -> Long.MAX_VALUE; };
    }
    enum Plan { FREE, PRO, ENTERPRISE }
}
```

Then in a filter, translate `ConsumptionProbe` to headers:

```java
ConsumptionProbe probe = limiter.tryConsume(tenantId, plan);
if (probe.isConsumed()) {
    res.setHeader("RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
    chain.doFilter(req, res);
} else {
    long waitSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
    res.setStatus(429);
    res.setHeader("Retry-After", String.valueOf(waitSec));
}
```

### 5.3 Login brute-force protection — sliding window log (exact, small limit)

5 failed attempts per 15 minutes per (username + IP), distributed via Redis ZSET:

```java
public class LoginThrottle {
    private final JedisPool pool;
    private final String sha;                  // cached EVALSHA of the ZSET script
    private static final int LIMIT = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000L;

    public LoginThrottle(JedisPool pool, String luaSource) {
        this.pool = pool;
        try (Jedis j = pool.getResource()) { this.sha = j.scriptLoad(luaSource); }
    }

    /** Call ONLY on a failed login attempt; success should reset the key. */
    public boolean tooManyFailures(String username, String ip) {
        String key = "login:fail:" + username + ":" + ip;
        long now = System.currentTimeMillis();
        String member = now + "-" + java.util.UUID.randomUUID();
        try (Jedis j = pool.getResource()) {
            Object r = j.evalsha(sha, 1, key,
                    String.valueOf(now), String.valueOf(WINDOW_MS),
                    String.valueOf(LIMIT), member);
            // Lua returns {allowed, remaining}; allowed==0 means blocked.
            return ((java.util.List<?>) r).get(0).equals(0L);
        }
    }
    public void onSuccess(String username, String ip) {
        try (Jedis j = pool.getResource()) { j.del("login:fail:" + username + ":" + ip); }
    }
}
```

Note the design choice: count **only failures**, and **reset on success** — so legitimate users aren't throttled by their own successful logins. Exactness matters here (you don't want 9 attempts when policy says 5), so the log algorithm is appropriate despite its memory cost (limit is tiny).

### 5.4 Client-side outbound throttling to a paid third-party API (Guava)

Protect *yourself* from blowing a downstream's quota and your own bill:

```java
import com.google.common.util.concurrent.RateLimiter;

public class SmsClient {
    // Provider allows 20 messages/sec; stay under it with a smooth limiter.
    private final RateLimiter limiter = RateLimiter.create(20.0);
    private final HttpClient http;

    public SmsClient(HttpClient http) { this.http = http; }

    public void send(Sms sms) {
        limiter.acquire();   // blocks just enough to keep <=20/s; returns time slept
        // ... perform the HTTP POST to the SMS provider ...
    }

    /** Non-blocking variant for a reactive pipeline: skip/queue instead of blocking. */
    public boolean trySend(Sms sms) {
        if (!limiter.tryAcquire()) return false;   // caller decides to buffer/retry
        // ... send ...
        return true;
    }
}
```

This is in-process and fine because *one client instance* governs its own outbound rate; if you have many sender instances, lease tokens from a shared store instead.

### 5.5 Weighted / cost-based limiting (heavy endpoints cost more tokens)

Not all requests are equal — a search costing 5 tokens, a simple GET costing 1:

```java
public class WeightedApiLimiter {
    private final TokenBucketLimiter bucket;     // from §3.4, capacity 1000, rate 100/s

    public WeightedApiLimiter(TokenBucketLimiter bucket) { this.bucket = bucket; }

    private double costOf(String endpoint) {
        return switch (endpoint) {
            case "/search"        -> 5.0;   // expensive
            case "/reports/build" -> 50.0;  // very expensive
            case "/export"        -> 20.0;
            default               -> 1.0;
        };
    }
    public boolean allow(String apiKey, String endpoint) {
        return bucket.allow(apiKey, costOf(endpoint));   // consume cost tokens atomically
    }
}
```

This models real capacity better than counting requests: a client doing 10 reports/min should "use up" far more budget than one doing 10 trivial reads. Stripe's API famously uses cost-based limiting.

### 5.6 Global safety valve + per-user, checked together (layered)

```java
public class LayeredLimiter {
    private final TokenBucketLimiter global;    // in-process, protects the box: e.g., 5000/s
    private final TenantRateLimiter perUser;    // distributed, fairness

    public LayeredLimiter(TokenBucketLimiter global, TenantRateLimiter perUser) {
        this.global = global; this.perUser = perUser;
    }
    public Decision check(String userId, TenantRateLimiter.Plan plan) {
        // Cheap local check first; short-circuits before any network call.
        if (!global.allow("global")) return Decision.REJECT_GLOBAL;
        var probe = perUser.tryConsume(userId, plan);  // distributed Redis check
        if (!probe.isConsumed()) {
            global.refund("global", 1.0);  // optional: give back the token we took
            return Decision.REJECT_USER;
        }
        return Decision.ALLOW;
    }
    enum Decision { ALLOW, REJECT_GLOBAL, REJECT_USER }
}
```

(`refund` would re-add a token to the global bucket; implement as `tokens = min(capacity, tokens + cost)`.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Hot-path cost.** Every request pays the limiter cost. Local limiters are nanoseconds; a Redis round-trip adds **~0.2–1 ms** (same-AZ) — acceptable for most APIs but significant at very high RPS. Batch/lease tokens (§3.7.6) to amortize.
- **Avoid lock contention.** A single global lock around the limiter serializes all requests. Shard state per key (`ConcurrentHashMap` of per-key locks/atomics), or use lock-free CAS (§7.1). Prefer `LongAdder` over `AtomicLong` under extreme contention.
- **Pipeline / single round-trip.** Do the *entire* decision in one Lua `EVALSHA`, not multiple `GET`+`INCR` calls. Multiple round-trips = both slower *and* racy.
- **Connection pooling.** A starved Redis connection pool becomes its own bottleneck; size it for peak RPS.
- **Cache the Lua SHA.** Use `EVALSHA` with a pre-loaded script; only fall back to `EVAL` (with `SCRIPT LOAD`) on `NOSCRIPT`.

### 6.2 Correctness & concurrency

- **Atomicity is non-negotiable** for shared state — Lua scripts or atomic commands only; never read-then-write across the network.
- **Clock skew** breaks token-bucket math when time is passed from multiple callers. Use NTP; prefer a single time source (Redis `TIME` on modern Redis, or a coordinator). Document the assumption.
- **Boundary bursting** — don't pick fixed window where precise fairness matters.
- **Monotonic clocks for elapsed time.** Use `System.nanoTime()` for *durations* in local limiters (immune to wall-clock adjustments); `currentTimeMillis()` is fine for window IDs but can jump backward on NTP corrections — guard against negative elapsed (`Math.max(0, ...)`).

### 6.3 Memory

- **Per-key state must expire.** Without TTLs, your key space grows unbounded (one bucket per IP forever = memory leak / Redis OOM). Always set TTL ≥ window. For local maps, use a size-bounded cache (`Caffeine` with `maximumSize`/`expireAfterAccess`).
- **Sliding window log memory** is O(limit) per key — bound your limits or use it only for small limits.
- **Cardinality explosion.** Per-IP limiting under a DDoS with spoofed/rotating IPs can create millions of keys. Cap distinct keys (e.g., evict LRU) and have a coarse global limiter behind it.

### 6.4 Security

- **Don't trust client-supplied identity** for the limit key (a client can fake `X-Forwarded-For` or an API key). Derive the key from *authenticated* identity where possible; for IP, use the *real* client IP (the trusted hop, e.g., from your LB, not a forgeable header).
- **`X-Forwarded-For` spoofing** — clients can prepend fake IPs; only trust the portion added by *your* infrastructure. Configure `trusted proxies`.
- **Rate-limit the limiter's own failure modes** — e.g., cap the cost of building 429 responses; logging every rejected request can itself DoS your log pipeline (sample logs).
- **Limit by the right dimension for the threat** — credential stuffing rotates usernames, so limit per-IP *and* per-username *and* globally.
- **Don't leak quota internals** that aid attackers timing their attacks — but standard `RateLimit-*` headers are generally fine and helpful.

### 6.5 Observability

Emit metrics (Micrometer/Prometheus):
- `ratelimit_allowed_total{key_type, route}` and `ratelimit_rejected_total{...}` (counters).
- `ratelimit_remaining` (gauge / histogram) to see how close clients run to their cap.
- `ratelimit_redis_latency_seconds` (histogram) — watch the limiter store's health.
- **Top-N rejected keys** — to spot abusers and misconfigured clients.
- **Rejection rate alerts** — a sudden spike in 429s may mean an attack, a misconfigured client, or a too-tight limit after a deploy.
- **Structured logs** on reject (sampled): key, route, limit, current count — for debugging "why am I being limited?" tickets.

### 6.6 Cost

- The limiter store costs money (Redis cluster) and adds latency. Weigh distributed accuracy vs. the cheaper "local with reconciliation" approach.
- Rate limiting *saves* far more than it costs by capping downstream spend and preventing outage-driven over-provisioning. Quantify the saved third-party API spend to justify it.

### 6.7 Testability

- **Inject a clock** (`java.time.Clock` / a `LongSupplier nowMs`) instead of calling `System.currentTimeMillis()` directly, so tests can advance time deterministically.

```java
public class TestableTokenBucket {
    private final java.util.function.LongSupplier clock;   // inject in tests
    // ... use clock.getAsLong() everywhere instead of System.currentTimeMillis()
}
```

- **Test the boundary cases**: window edges (fixed window 2× burst), refill exactness, concurrency (hammer with N threads, assert total admits == limit), TTL/expiry, fail-open path (Redis down).
- **Property test**: over any timeline, admits in any rolling window never exceed limit (for sliding algorithms).
- **Load test** the limiter itself — it must not become the bottleneck.

### 6.8 Production hardening checklist

- [ ] TTLs on every key (no unbounded growth).
- [ ] Atomic decisions (Lua / atomic commands), single round-trip.
- [ ] Defined fail-open/closed behavior with a **local fallback limiter** when the store is down.
- [ ] Redis HA (replica/cluster/sentinel) — no naked SPOF.
- [ ] Correct `429` + `Retry-After` + `RateLimit-*` headers.
- [ ] Per-key sharding / hash tags for multi-key scripts.
- [ ] Metrics, alerts, and sampled reject logs.
- [ ] Configurable limits **without redeploy** (dynamic config / feature flags) so you can tighten under attack.
- [ ] Allow-list for critical internal/health-check traffic (don't rate-limit your own load balancer's health checks!).
- [ ] Client SDKs that honor `Retry-After` with jittered exponential backoff.

### 6.9 Anti-patterns to avoid

- **Per-instance local limits as if they were global** — multiplies the true limit by instance count and drifts with autoscaling.
- **Read-then-write across the network** — racy; always over- or under-counts.
- **No TTL** — slow memory leak that eventually OOMs the store.
- **Fixed window where fairness matters** — boundary bursting.
- **Blocking the request thread to "shape"** in a thread-per-request server — exhausts the thread pool; you've turned a rate limit into an availability outage.
- **Unbounded queues** for leaky-bucket shaping — buffers memory until OOM; bound the queue and shed on overflow.
- **Hard fail-closed coupling availability to Redis** — a Redis blip becomes a full outage.
- **Limiting on a spoofable key** (raw `X-Forwarded-For`, client-claimed user id).
- **Returning `200` or `503` instead of `429`** — confuses clients and breaks their retry logic.
- **No `Retry-After`** — clients retry immediately, causing the thundering herd you were trying to prevent.
- **Logging every rejection unsampled** — your own log pipeline becomes the casualty under attack.

---

## 7. Advanced topics & deep internals

### 7.1 Lock-free fixed window with packed state + CAS

Pack `windowId` (high bits) and `count` (low bits) into one `long`, mutate with compare-and-swap:

```java
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free fixed window: window id in high 21 bits, count in low 43 bits. */
public class LockFreeFixedWindow {
    private final long limit, windowMs;
    private final AtomicLong state = new AtomicLong(0);  // per-key in practice

    public LockFreeFixedWindow(long limit, long windowMs) { this.limit = limit; this.windowMs = windowMs; }

    public boolean allow() {
        long now = System.currentTimeMillis();
        long win = now / windowMs;
        while (true) {                                   // CAS retry loop
            long cur = state.get();
            long curWin = cur >>> 43;
            long curCnt = cur & ((1L << 43) - 1);
            long newWin, newCnt;
            if (curWin == win) {
                if (curCnt >= limit) return false;       // exhausted this window
                newWin = curWin; newCnt = curCnt + 1;
            } else {
                newWin = win; newCnt = 1;                // new window resets count
            }
            long next = (newWin << 43) | newCnt;
            if (state.compareAndSet(cur, next)) return true;  // success
            // else: another thread won the race; retry with fresh state
        }
    }
}
```

> **CAS (compare-and-set / compare-and-swap)** — an atomic CPU instruction: "set this memory to NEW only if it currently equals EXPECTED, all in one indivisible step." Lock-free algorithms loop on CAS, retrying when another thread changed the value first. Avoids lock overhead and contention but can spin under very high contention.

### 7.2 Guava's "borrow from the future" and warm-up

Guava `SmoothBursty` returns from `acquire()` *immediately* but charges the wait to the *next* caller — it tracks `nextFreeTicketMicros`. This means the *first* request after idleness is fast even if the bucket is "behind"; the cost is deferred. `SmoothWarmingUp` deliberately *slows* the first requests after idleness (the bucket "cools down"), modeling resources that need to warm up (JIT, caches, connection pools). The warm-up period and the cold/stable rate ratio are tuning knobs.

### 7.3 GCRA — Generic Cell Rate Algorithm

GCRA is a leaky-bucket *meter* (from ATM networking) that needs only **one timestamp** per key — the **TAT (Theoretical Arrival Time)**, the earliest time the next request may arrive. It enforces both an *emission interval* (the inverse of the rate) and a *burst tolerance*, with no per-request state beyond one number. It's elegant, exact, and memory-minimal — used by `redis-cell` (`CL.THROTTLE`) and many CDNs. Mechanics: each request computes `TAT' = max(TAT, now) + emission_interval`; admit iff `TAT' - burst_tolerance <= now`, then store `TAT'`. It is mathematically equivalent to a token bucket but stores time instead of token count.

### 7.4 Tuning knobs cheat-list

| Knob | Effect | Typical choice |
|---|---|---|
| `capacity` / `burst` | How big a spike is tolerated | 1–2× sustained rate; higher for bursty clients |
| `refillRate` / `replenishRate` | Sustained throughput | = your SLA / plan tier |
| `windowSize` | Granularity of fixed/sliding window | 1s–1min; smaller = smoother but more keys |
| `cost` per op | Weighted limiting | proportional to real resource use |
| TTL | Memory reclamation | ≥ window; e.g., 2× window |
| `timeoutDuration` (Resilience4j) | How long a caller waits for a permit | 0 for fail-fast, small for slight shaping |
| Lease batch size (token leasing) | Redis-traffic vs accuracy tradeoff | 10–100 |
| Warm-up period (Guava) | Ramp after idleness | seconds, for cache/JIT warmup |

### 7.5 Adaptive / dynamic rate limiting

Static limits are blunt. Advanced systems adjust limits based on **system health signals**:

- **Adaptive concurrency limiting** (Netflix `concurrency-limits`, TCP-Vegas-style): instead of a fixed RPS, infer the optimal *in-flight concurrency* by watching latency — when latency rises (queueing), shrink the limit; when it's healthy, grow it. This auto-tunes to actual capacity and is remarkably robust to changing backend speed.
- **Load-based limits**: tie the global cap to CPU/queue-depth so you shed more aggressively as you approach saturation. This bridges into **load shedding** (§8).
- **Priority-aware limiting**: when over budget, drop low-priority requests first (e.g., shed `prefetch`/`batch` before `interactive`). Requires a request criticality tag.

### 7.6 Quotas vs rate limits (different time scales)

- **Rate limit** — short-term smoothing (req/sec, req/min).
- **Quota** — long-horizon cap (req/day, req/month) often tied to billing. Implemented as a separate, larger counter (daily/monthly key with matching TTL). A request must pass *both*. Bucket4j multi-bandwidth (§5.2) does this in one bucket.

### 7.7 Distributed coordination beyond Redis

- **Envoy global RLS** uses a dedicated gRPC rate-limit service backed by Redis/Memcached, with descriptor-based keys and "shadow mode" (count but don't enforce) for safe rollout.
- **Sliding-window with sketches**: for *extremely* high cardinality with approximate counts, count-min sketches or HyperLogLog-style structures trade exactness for memory — niche, usually overkill.
- **Gossip-based local limiters**: instances share counts via gossip (no central store) — used where Redis is undesirable; eventually consistent.

### 7.8 Cold start & "thundering herd" after a window reset

With fixed windows, *every* client's window resets at the same wall-clock instant (e.g., top of the minute), so a horde fires the instant the window opens — a synchronized spike. Mitigations: (1) **jitter the window start** per key (offset by `hash(key) % windowMs`), or (2) use token bucket / sliding window which have no synchronized reset, or (3) ensure clients add jitter to their own retry/poll loops.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Rate limiting vs throttling vs load shedding

These three are often conflated. Precise distinctions:

| Concept | What it does | Trigger | Granularity | Typical response |
|---|---|---|---|---|
| **Rate limiting** | Caps a *client's* request rate against a *policy/quota* | Client exceeds *its* allowance | Per-key (user/tenant/IP) | `429` + Retry-After |
| **Throttling** | *Slows/shapes* traffic (delay, queue) rather than reject | Often used interchangeably; emphasizes *shaping/delay* | Per-key or global | Delay, then serve (or 429) |
| **Load shedding** | Drops requests to protect the *server* from overload, regardless of who's calling | *Server* near saturation (CPU, queue depth, latency) | Global / by priority | `503` (or drop low-priority) |

- **Rate limiting is policy-driven** ("your plan allows 100/min"); **load shedding is health-driven** ("I'm overloaded, I must drop *something* to survive"). You need both: rate limiting for fairness/cost, load shedding as the last-resort survival mechanism.
- **Throttling** is loosely "rate limiting that prefers to *delay* rather than *reject*." Many sources use "throttling" and "rate limiting" interchangeably; in interviews, define your terms.

> **Load shedding** — under overload, intentionally dropping a fraction of work (preferably the least important) so the rest is served at healthy latency, preventing a death spiral. Often paired with **priority queues** and **admission control**.
> **Backpressure** — a related mechanism: a slow consumer signals upstream to slow down (e.g., a bounded queue refusing new items, or reactive streams `request(n)`). Rate limiting is a form of *imposed* backpressure at the boundary.

### 8.2 Which algorithm? (decision rules)

- **Use sliding window counter** when you want general-purpose, fair, smooth limiting at O(1) memory — *the default*.
- **Use token bucket** when bursts are desirable/expected and you limit the *average* (most public APIs).
- **Use sliding window log** when the limit is small and exactness is required (auth, sensitive actions).
- **Use leaky bucket (queue)** when the protected resource needs a *smooth, constant* input rate and you can afford to buffer/delay.
- **Use fixed window** only for cheap coarse caps where 2× boundary bursting is acceptable.

### 8.3 Local vs distributed (decision rules)

- **Use local** when: single instance; or per-instance protection (a global safety valve per box); or you can tolerate `limit × N` and want zero network cost; or as the **fail-open fallback**.
- **Use distributed** when: limits must be *globally* accurate across instances (billing, fairness, hard caps). Accept the latency/SPOF and mitigate with HA + leasing + fail-open.

### 8.4 Where to enforce — gateway vs service vs client

| Location | Pros | Cons | Use when |
|---|---|---|---|
| **Edge/CDN/WAF** | Absorbs volumetric DDoS before it costs you; cheap | Coarse (IP-only); no business context | Always, for DDoS defense |
| **API Gateway** | Sees API key/tenant; one place protects all services; offloads app | Doesn't know fine business state; another hop | **Default for business quotas** |
| **Service mesh (Envoy)** | Inter-service protection inside cluster | Operational complexity | Microservice fan-out protection |
| **Application/service** | Full business context (per-action, per-resource limits) | Each service must implement; harder to keep consistent | Fine-grained, context-dependent limits |
| **Client (SDK)** | Prevents waste before the call; protects downstream | Untrusted (client can disable it) | Outbound throttling to paid APIs; politeness |

**Rule of thumb:** enforce *coarse* limits as early as possible (edge/gateway) and *fine-grained, business-aware* limits at the service. Defense in depth — multiple layers.

### 8.5 Build vs buy

- **Use the gateway's built-in** (Spring Cloud Gateway, Kong, API Gateway) for standard per-key quotas — least code.
- **Use Bucket4j/Resilience4j** when you need rate limiting *inside* JVM services with distributed backing.
- **Use redis-cell / Envoy RLS** when you want a turnkey, language-agnostic global limiter.
- **Hand-roll Lua** only when you have requirements the libraries don't cover (exotic weighted/hierarchical logic) — and even then, prefer extending a library.

---

## 9. Failure modes & debugging

### 9.1 Common production failures and diagnosis

| Symptom | Likely cause | How to diagnose | Fix |
|---|---|---|---|
| Effective limit is N× configured | Per-instance local limits behind a LB | Compare configured limit vs observed admits across fleet; metric per instance | Move to distributed store, or set local = limit/N (brittle) |
| Clients hammering after reset | No `Retry-After`; synchronized window resets | Inspect 429 responses; look for spike at top-of-minute | Add `Retry-After` + jitter; switch to token/sliding |
| Random over/under-limit | Read-then-write race (non-atomic) | Concurrency load test; check for multi-command decisions | Use Lua / atomic `INCR` |
| Memory/OOM in Redis | Missing TTLs; per-IP key explosion | `redis-cli INFO memory`, `DBSIZE`, `--bigkeys`, `MEMORY USAGE key` | Add TTLs; cap key cardinality; coarser keys |
| Latency spike on every request | Redis round-trip on hot path; pool exhaustion | Latency histogram by route; `redis-cli --latency`; pool metrics | Token leasing; bigger pool; co-locate AZ |
| Full outage when Redis blips | Fail-closed with no fallback | Chaos test: kill Redis | Fail-open with local fallback limiter |
| Token bucket admits wrong amount | Clock skew between callers | Compare callers' clocks; check `now` source | Use single time source / NTP; pass server time |
| Legit users blocked at login | Counting successes, or per-IP only behind NAT | Inspect throttle key + logic | Count failures only; reset on success; combine keys |
| `NOSCRIPT` errors | Redis restarted, script cache lost | Logs show NOSCRIPT | Auto `SCRIPT LOAD` on NOSCRIPT, then `EVALSHA` |

### 9.2 Diagnostic tools & commands

- **Redis:** `redis-cli MONITOR` (watch commands live — *high overhead, use briefly*), `INFO stats` (ops/sec, keyspace hits/misses), `DBSIZE`, `redis-cli --bigkeys`, `MEMORY USAGE <key>`, `TTL <key>` (confirm expiry set), `SLOWLOG GET` (slow Lua scripts), `--latency`/`--latency-history`.
- **Inspect a token bucket:** `HGETALL rl:tenant:123` → see `tokens` and `ts`.
- **Inspect a sliding log:** `ZRANGE login:fail:bob:1.2.3.4 0 -1 WITHSCORES`, `ZCARD ...`.
- **App metrics:** Prometheus queries on `ratelimit_rejected_total` by key/route; alert on rate-of-change.
- **Trace a single request:** structured logs at the limiter with key, decision, remaining; correlate by trace ID.

### 9.3 Real-world incident patterns (illustrative)

- **The autoscale-doubled limit.** A team set "1000 req/min" as a per-pod local limit. During a traffic event Kubernetes scaled from 3 to 30 pods; the effective limit silently became 30,000/min, the fragile downstream DB it was protecting fell over, triggering a cascading outage. *Lesson:* per-instance limits don't compose; use a distributed limiter or make the local limit a function of replica count (still brittle).
- **The retry storm.** A downstream had a brief blip; clients without jitter retried in lockstep, and the synchronized retry wave (a **thundering herd**) kept the service pinned even after the original cause cleared — a **metastable failure**: the system stays broken under its own retry load until load is forcibly removed. *Lesson:* `Retry-After` + jittered exponential backoff on clients; server-side load shedding as a circuit-breaker of last resort.
- **The fail-closed cascade.** A limiter configured fail-closed lost its Redis for 90 seconds; *every* request was rejected, turning a dependency blip into a full customer-facing outage. *Lesson:* fail open with a local fallback for protective limiting.
- **GitHub/Stripe-style header confusion.** A client SDK read `X-RateLimit-Reset` as "seconds to wait" when the server emitted it as a Unix epoch timestamp, computing a multi-decade backoff and silently going dormant. *Lesson:* document header semantics precisely; emit standard `Retry-After` (unambiguous seconds-or-date).

> **Metastable failure** — a state where a system, once pushed past a tipping point (often by retries/queue buildup), *stays* in a degraded/failed state under its own induced load even after the original trigger is gone, until load is externally reduced. Rate limiting and load shedding are primary defenses.

---

## 10. Interview drill

Each question has a model answer plus deep-probe follow-ups. Senior-signal questions are marked ★.

**Q1. Walk me through the common rate-limiting algorithms and their tradeoffs.**
Model: Fixed window (one counter per window; cheap, O(1), but 2× boundary bursting). Sliding window log (stores every timestamp; exact but O(limit) memory). Sliding window counter (weights previous window by overlap fraction; O(1), smooth, ~99% accurate — the general default). Token bucket (tokens refilled at rate, capacity = burst; allows bursts, limits average — most public APIs). Leaky bucket (FIFO queue draining at fixed rate; smooths output, adds latency — protects burst-intolerant downstreams).
- *Follow-up: When would you choose sliding window log over counter?* When the limit is small and exactness matters (login: 5/15min); the memory cost is tiny at small limits and you can't tolerate the counter's approximation.
- *Follow-up: Token vs leaky bucket?* Token bucket allows output bursts (limits average); leaky bucket forbids them (constant output). Choose leaky when the downstream cannot tolerate bursts.

**Q2. How do you make rate limiting work across many service instances?**
Model: A shared authoritative counter, typically in Redis, with the whole decision done atomically (single `INCR`, or a Lua script for token bucket / sliding window). Avoid read-then-write across the network — that's a TOCTOU race admitting too many. Add TTLs for cleanup, HA for the store, fail-open with a local fallback, and consider token leasing to cut Redis traffic.
- *Follow-up: Show the race if you don't use atomic ops.* Two instances `GET` 99, both decide admit, both `INCR` → 101 admitted against a 100 limit.
- *Follow-up: Why Redis specifically?* In-memory (sub-ms), single-threaded command execution (atomicity), Lua for atomic compound logic, native TTL and sorted sets.

**Q3. ★ You're protecting a fragile downstream DB that supports ~500 QPS, fronted by an autoscaling fleet. Design the limiting.**
Model: The limit must be *global* (the DB's capacity is global), so per-instance local limits are wrong — they'd scale with replica count and overshoot. Use a distributed token bucket (capacity ~ small burst, refill ~ 450/s to leave headroom) in Redis, checked atomically. Add a local in-process safety valve as a fail-open fallback. Layer load shedding by request priority so when over budget we drop batch/prefetch before interactive. Emit `429` + `Retry-After` and ensure clients use jittered backoff. Watch the DB's own latency as an adaptive signal — consider adaptive concurrency limiting that auto-tunes to observed latency rather than a static QPS.
- *Follow-up: Why not just per-pod limits = 500/N?* N changes with autoscaling; the math drifts and you'll either overshoot (DB dies) or undershoot (waste capacity).
- *Follow-up: Redis dies — now what?* Fail open to local fallback limiters sized conservatively, alert, and rely on the DB's own connection-pool/timeout limits and load shedding as the backstop.

**Q4. Design the response when you reject a request.**
Model: HTTP `429 Too Many Requests`, a `Retry-After` header (seconds or HTTP-date — the one clients universally honor), plus `RateLimit-Limit`/`RateLimit-Remaining`/`RateLimit-Reset` (IETF draft; also emit legacy `X-RateLimit-*` for compatibility), and a small JSON body with a machine code (`rate_limited`) and the retry hint. Never `200`/`503` for per-client limiting; `503` is for server-wide load shedding.
- *Follow-up: Why is `Retry-After` important?* Without it clients retry immediately and synchronously, creating a thundering herd that prolongs the problem.
- *Follow-up: Epoch vs seconds in reset header?* Ambiguity causes client bugs; document it and prefer `Retry-After` which is unambiguous.

**Q5. Explain the fixed-window boundary problem and how sliding-window-counter fixes it.**
Model: Because windows are independent, a client can send `limit` at the end of one window and `limit` at the start of the next — 2× limit in a sub-window span. Sliding window counter weights the previous window's count by the fraction of it still inside the rolling window (`estimate = prev*f + cur`), so as the boundary passes the previous contribution decays smoothly — no cliff.
- *Follow-up: What assumption does the counter make?* Uniform distribution within the previous window; if traffic clustered at the prev window's end, it slightly under-counts — acceptable for typical traffic.

**Q6. ★ Rate limiting vs throttling vs load shedding — when do you use each?**
Model: Rate limiting enforces a *per-client policy/quota* (fairness, cost) and responds `429`. Throttling emphasizes *shaping/delaying* rather than rejecting. Load shedding is *server-survival* — under overload, drop work (ideally lowest priority) regardless of who's calling, responding `503`. You need all: rate limiting for fairness/cost at the boundary, load shedding as the last-resort survival valve when health degrades.
- *Follow-up: Can a client respect a rate limit and still overload you?* Yes — many compliant clients summing up, or expensive requests; that's why you also need a global cap + load shedding.
- *Follow-up: Where does backpressure fit?* It's imposed/propagated slowdown (bounded queues, reactive `request(n)`); rate limiting is a form of imposed backpressure at the edge.

**Q7. How do you choose the limit key (granularity)?**
Model: By the fairness/threat dimension: per-user for individual fairness, per-tenant for SaaS plan enforcement, per-IP for anonymous/anti-DDoS, per-endpoint for differing costs, composite (tenant+endpoint) for precision, and a global safety valve. Derive the key from *authenticated* identity, never spoofable client input; for IP use the trusted hop, not raw `X-Forwarded-For`.
- *Follow-up: Credential stuffing rotates usernames — what key?* Combine per-IP, per-username, and global limits; no single key suffices.

**Q8. Write the atomic Redis token-bucket logic; why must it be one operation?**
Model: A Lua script that reads `{tokens, ts}`, lazily refills `tokens = min(cap, tokens + elapsed*rate)`, conditionally consumes, writes back, and returns allowed/remaining/retry — all atomic because Redis runs the whole script with no interleaving. If split into multiple commands, concurrent callers race on the read-modify-write and over-admit.
- *Follow-up: Where does `now` come from?* Pass a single trusted server time or use Redis `TIME` (deterministic on modern Redis); never trust unsynced caller clocks.
- *Follow-up: How do idle buckets get cleaned?* `EXPIRE` the key each call with TTL ≥ window.

**Q9. ★ Your 429 rate jumped 10× right after a deploy. Triage.**
Model: First, did limits change in the deploy (config)? Check the rate-limit config diff and dynamic-config values. Second, did *traffic* change (new client, retry loop)? Look at top rejected keys/routes and per-client breakdown. Third, did the *limiter store* degrade (latency up → timeouts treated as rejects)? Check Redis latency/errors and the fail mode. Decide: roll back config, allow-list a misconfigured internal client, or loosen the limit. Confirm the limiter itself isn't the bottleneck.
- *Follow-up: Rejects are concentrated on one internal service.* Likely a too-tight limit or a missing allow-list for internal/health traffic; allow-list and re-tune.
- *Follow-up: Redis latency is fine but rejects are global.* Suspect a global safety-valve misfire or a clock issue inflating elapsed-negative; inspect bucket state.

**Q10. How do you test a rate limiter?**
Model: Inject a controllable clock to advance time deterministically; unit-test boundaries (fixed-window 2× burst, refill exactness, TTL expiry), concurrency (N threads → assert total admits == limit), the fail-open path (store down), and property-test (no rolling window exceeds limit). Load-test the limiter so it doesn't become the bottleneck.
- *Follow-up: How verify atomicity?* Hammer the distributed limiter from many threads/processes and assert the global admit count never exceeds the limit.

**Q11. ★ Justify spending engineering effort on rate limiting to a skeptical PM.**
Model: It directly prevents the most common availability incidents (noisy neighbor, retry storms, cascading failure), caps third-party/cost exposure (quantify saved spend), enables monetization tiers (free vs pro quotas are a product feature, not just protection), and is cheap to add at the gateway. The downside of *not* having it is an outage whose cost dwarfs the implementation — and outages erode customer trust disproportionately.
- *Follow-up: Why not just over-provision?* Over-provisioning doesn't stop abuse or unbounded cost, scales linearly with spend, and still topples under a sufficiently large or malicious spike; rate limiting bounds the problem regardless of capacity.

**Q12. Explain token leasing / local-token batching and its tradeoff.**
Model: Each instance leases a batch of tokens from Redis (e.g., 50) and serves locally until depleted, then re-leases — cutting Redis traffic ~batch-fold. Tradeoff: slight over-admission near boundaries and less precise global enforcement, in exchange for lower latency and Redis load. Good when approximate global limits are acceptable and per-request Redis cost is too high.
- *Follow-up: How big a batch?* Balance Redis-traffic reduction vs accuracy loss (10–100 typical); larger batch = less accurate but cheaper.

---

## 11. Glossary

- **429 Too Many Requests** — HTTP status (RFC 6585) meaning the client exceeded its rate limit.
- **503 Service Unavailable** — HTTP status used for server-wide overload / load shedding (not per-client limiting).
- **Adaptive concurrency limiting** — dynamically inferring the optimal in-flight concurrency from observed latency, auto-tuning the limit (Netflix concurrency-limits, TCP-Vegas-style).
- **Admission control** — deciding at the boundary whether to accept a unit of work into the system at all.
- **API Gateway** — single entry point fronting backend services, handling auth, routing, rate limiting, observability.
- **Atomic operation** — an indivisible operation that completes fully with no observable intermediate state and no interleaving by others.
- **Backpressure** — propagating "slow down" signals upstream when a consumer can't keep up (bounded queues, reactive `request(n)`).
- **Bandwidth (Bucket4j)** — one rate constraint on a bucket; a bucket can have several (e.g., per-sec and per-day).
- **Bucket4j** — a JVM token-bucket library with distributed backends (Redis, Hazelcast, etc.).
- **Bulkhead** — isolating resources (e.g., separate thread pools) so one failure can't sink everything; often a concurrency limit.
- **Burst / burst capacity** — the amount of instantaneous over-rate a limiter tolerates; in token bucket, the bucket capacity.
- **CAS (compare-and-swap)** — atomic "set to NEW iff currently EXPECTED"; foundation of lock-free algorithms.
- **Circuit breaker** — stops calling a failing dependency after an error threshold, fails fast, then retries cautiously.
- **Clock skew** — disagreement between machines' clocks; breaks time-based limiter math when time is sourced from multiple callers.
- **Concurrency limiting** — bounding the number of *simultaneous* in-flight requests (vs rate = per unit time).
- **Cost-based / weighted limiting** — heavier requests consume more tokens than light ones.
- **Distributed rate limiting** — sharing the limiter's counter across many instances via a central store.
- **EVAL / EVALSHA** — Redis commands to run a Lua script atomically; `EVALSHA` runs a cached script by its SHA-1.
- **Fail-open / fail-closed** — on limiter-store failure, admit (open) or reject (closed).
- **Fixed window** — counter reset at fixed time boundaries; cheap but allows 2× boundary bursting.
- **GCRA (Generic Cell Rate Algorithm)** — a leaky-bucket meter storing one timestamp (TAT); exact and memory-minimal.
- **Guava RateLimiter** — Google's in-process token-bucket limiter (smooth/warm-up); not distributed.
- **Idempotent** — produces the same result whether applied once or many times.
- **Jitter** — randomization added to retry delays to desynchronize clients and avoid thundering herds.
- **Leaky bucket** — queue draining at a fixed rate, smoothing output; overflow is dropped.
- **Load shedding** — under overload, dropping (preferably low-priority) work to keep the rest healthy.
- **Lua script (Redis)** — a program run atomically inside Redis; used for compound limiter logic.
- **Metastable failure** — a system stuck in a degraded state under its own induced load (e.g., retries) even after the trigger clears.
- **Monotonic clock** — a clock that never goes backward (`System.nanoTime()`); use for measuring durations.
- **NTP (Network Time Protocol)** — protocol that synchronizes machine clocks; mitigates clock skew.
- **Permit / token** — a unit of allowance consumed by an admitted request.
- **Quota** — a long-horizon cap (per day/month), often tied to billing; distinct from short-term rate.
- **Race condition** — outcome depends on unpredictable concurrent interleaving; here, non-atomic read-modify-write.
- **Rate (sustained)** — the steady-state allowance, e.g., 100 RPS.
- **Rate limiting** — bounding a client's request rate against a policy/quota.
- **Redis** — in-memory key-value store with atomic single-threaded command execution and Lua scripting.
- **redis-cell / CL.THROTTLE** — a Redis module providing a turnkey GCRA token-bucket command.
- **Resilience4j** — JVM resilience library (rate limiter, circuit breaker, bulkhead, retry).
- **Retry-After** — HTTP header (RFC 7231) telling the client when to retry; the most universally honored hint.
- **RLS (Rate Limit Service)** — Envoy's external gRPC service for global rate-limit decisions.
- **Semaphore** — a counting permit primitive bounding *concurrency*, not rate.
- **Service mesh** — infrastructure (e.g., Istio + Envoy sidecars) managing inter-service traffic including limiting.
- **Sliding window counter** — weights the previous fixed window by overlap fraction; O(1), smooth, near-exact.
- **Sliding window log** — stores every request timestamp; exact but O(limit) memory.
- **SPOF (single point of failure)** — a component whose failure brings down the whole system.
- **TAT (Theoretical Arrival Time)** — in GCRA, the earliest permitted time for the next request.
- **Tenant** — a single customer organization in multi-tenant SaaS.
- **Throttling** — slowing/shaping traffic (delay/queue), often used loosely as a synonym for rate limiting.
- **Thundering herd** — many clients acting in lockstep (e.g., retrying simultaneously) overwhelming a resource.
- **Token bucket** — tokens refill at a rate up to a capacity; allows bursts while limiting the average.
- **TOCTOU (time-of-check to time-of-use)** — a race where state changes between checking and acting on it.
- **TTL (time to live)** — expiry on a key so state auto-cleans, preventing unbounded growth.
- **WAF (Web Application Firewall)** — edge security layer that can apply coarse IP-based rate limits / DDoS rules.
- **Window** — the time unit over which a rate is measured.
- **X-Forwarded-For** — header listing client IPs through proxies; spoofable, so trust only the hop your infra added.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Algorithms (state / burst / use):**
- Fixed window — counter — 2× edge burst — cheap coarse caps.
- Sliding log — timestamps O(limit) — exact — small high-value limits.
- Sliding counter — 2 counters — smooth — **default**.
- Token bucket — tokens+ts — burst≤capacity — **APIs tolerating bursts**.
- Leaky bucket — queue — smoothed output — **burst-intolerant downstreams**.

**Key formulas:**
- `rate = quota / window`; token-bucket lazy refill: `tokens = min(capacity, tokens + elapsed*rate)`.
- Sliding counter: `estimate = prev*(1 − elapsed/window) + cur`.

**Distributed must-dos:** atomic decision (Lua / `INCR`), single round-trip, TTL on keys, Redis HA, **fail-open with local fallback**, token leasing to cut load, single trusted time source.

**Response:** `429` + `Retry-After` (seconds/date) + `RateLimit-Limit/Remaining/Reset` (draft; also legacy `X-RateLimit-*`) + JSON `{error:"rate_limited", retry_after}`.

**Where:** edge/WAF (DDoS, IP) → gateway (per-key/tenant business quotas — **default**) → mesh → service (fine business limits) → client (outbound politeness). Defense in depth.

**Distinctions:** rate limit = per-client policy (`429`); throttling = shaping/delay; load shedding = server-survival drop (`503`); backpressure = propagated slowdown.

**Keys:** global / IP / user / API-key-tenant / endpoint / composite — derive from *authenticated* identity, not spoofable input.

**Top anti-patterns:** per-instance ≠ global; read-then-write race; no TTL (OOM); fixed window for fairness; blocking request threads to shape; unbounded shaping queue; fail-closed coupling availability to Redis; no `Retry-After`.

**Numbers to remember:** Redis op ~0.2–1 ms same-AZ; Guava accumulates ~1s of burst; sliding-counter error typically <1% (Cloudflare reported ~0.003% on their data — verify yours). Login throttle 5/15min is a common policy.

**Libraries:** Guava `RateLimiter` (in-proc), Bucket4j (distributed token bucket), Resilience4j (resilience suite), Spring Cloud Gateway `RedisRateLimiter`, Kong/Envoy RLS, redis-cell `CL.THROTTLE`.

**JVM tips:** inject a clock for tests; `System.nanoTime()` for durations; `LongAdder` under contention; lock-free packed-long CAS for hot fixed windows; bound local key maps with Caffeine.

### 12.2 Self-test (no answers — active recall)

1. Draw the timeline that produces 2× the limit under a fixed window, then explain precisely how the sliding-window-counter formula prevents it — including the assumption the counter makes and when that assumption is wrong.
2. You run 12 instances behind a load balancer and need a *hard* global cap of 600 QPS to protect a downstream. Write the atomic Redis token-bucket Lua and explain every step that prevents over-admission, plus what happens when Redis becomes unreachable.
3. Compare token bucket and leaky bucket for an API whose downstream is a batch system that corrupts data if it receives more than 10 writes/second in any burst. Which do you pick and why, and what latency do you trade?
4. Design the complete rejection response (status, headers, body) for a public API, justify each field, and explain how a client SDK should consume it (including jittered backoff) to avoid a thundering herd.
5. Distinguish rate limiting, throttling, load shedding, and backpressure with a concrete example of each, and describe a single request path where all four could plausibly act.
6. Your 429 rate jumped 10× after a deploy with no traffic change. List the diagnostic steps in order and the specific tools/commands you'd run at each step.
7. Explain token leasing/batching: what it optimizes, the correctness it sacrifices, how to size the batch, and a scenario where it's the right call versus one where it's not.

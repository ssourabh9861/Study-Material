# Rate Limiting & Quotas

> An engineering handbook chapter on protecting, sharing, and monetizing API capacity. Written for a senior JVM-backend developer who wants to master the topic from first principles through production internals.

---

## 1. Overview & where it fits

### What it is

**Rate limiting** is the practice of *capping how frequently a client may call your API over a unit of time* — for example, "no more than 100 requests per second per API key." A **quota** is a *cap on total consumption over a longer accounting window* — for example, "no more than 1,000,000 requests per month" or "no more than 50 GB of egress per day." The two are siblings: rate limits protect *instantaneous capacity and stability*; quotas protect *aggregate fairness, cost, and business plan enforcement*.

If you remember nothing else: **rate limiting answers "how fast?" and quotas answer "how much, total?"** They are usually enforced by the same machinery (counters keyed by client identity) but with different windows and different business intent.

### The problem it solves

An API is a shared, finite resource. Without limits, any one of these will eventually hurt you:

- **Resource exhaustion.** A single buggy client in a retry loop, or a misconfigured cron job, can saturate your thread pool, database connections, or downstream dependency, taking the service down for *everyone*. This is the "noisy neighbor" problem.
- **Cost runaway.** If your API is backed by expensive operations (LLM inference, third-party APIs you pay per-call, large database scans), uncapped usage is uncapped spend.
- **Abuse & attacks.** Credential-stuffing, scraping, content spam, and **DDoS** (Distributed Denial of Service — many machines flooding you with traffic to exhaust your capacity) all manifest as *too many requests*.
- **Unfair sharing.** In a multi-tenant system, one tenant's spike should not starve others. Limits enforce **fairness** — a roughly equitable share of capacity.
- **Monetization & plan enforcement.** SaaS tiers ("Free: 1k/day, Pro: 100k/day, Enterprise: custom") are literally implemented as quotas.

### When you reach for it

You reach for rate limiting/quotas whenever an interface is exposed to clients you don't fully control: public APIs, partner APIs, internal platform APIs shared across many teams, login/auth endpoints (to slow brute force), webhook receivers, and any endpoint fronting an expensive or fragile backend. You reach for *quotas* specifically when usage maps to cost or to a contractual plan.

### Where it sits in the stack

Rate limiting can live at multiple layers, and mature systems use several at once (**defense in depth**):

| Layer | Example | What it's good at | Blind spots |
|---|---|---|---|
| **Edge / CDN** | Cloudflare, Fastly, AWS CloudFront + WAF | Absorbing volumetric DDoS before it reaches you; cheap IP-based limits | No app-level identity (can't see API key/tenant easily) |
| **L4/L7 load balancer** | NGINX, Envoy, HAProxy, AWS ALB | Connection/request-rate caps, coarse limits | Limited business logic |
| **API gateway** | Kong, Apigee, AWS API Gateway, Envoy/Istio, Spring Cloud Gateway | Per-key/per-route limits, plan enforcement, header injection | Distributed state coordination cost |
| **Application / service mesh** | Resilience4j, Bucket4j, Istio's `EnvoyFilter` | Fine-grained per-endpoint, business-aware limits | You own the complexity |
| **Datastore** | Redis, DynamoDB | Shared counter store backing the above | Single point of contention if naive |

The **mental model in one paragraph:** picture a turnstile in front of your service. Each client carries an identity card (API key, user ID, tenant ID, or IP). Behind the turnstile is a bucket of tokens that refills at a fixed rate; each request spends a token. If the bucket is empty, the turnstile rejects the request with HTTP **429 Too Many Requests** and a note saying *when to come back* (`Retry-After`). A separate ledger tracks lifetime/monthly consumption against the client's plan (the quota). When many turnstiles (many servers) must agree on one bucket, they share the count in a fast central store like Redis — and the entire challenge of "distributed rate limiting" is making that sharing correct, fast, and cheap.

---

## 2. Foundations from first principles

We build up the vocabulary before touching algorithms.

### 2.1 The three core controls: rate limit vs quota vs spike arrest

These terms are often blurred. Precise definitions:

- **Rate limit** — a cap on *request frequency* over a *short, rolling or fixed* window. Units: requests per second (RPS), requests per minute (RPM). Intent: protect *throughput capacity and latency*. Example: 100 RPS per key.
- **Quota** — a cap on *cumulative consumption* over a *long accounting period* (hour, day, month, billing cycle). Intent: enforce *fairness and commercial plans*; control *cost*. Example: 1,000,000 calls/month. A quota does not care whether you spend it in one burst or evenly.
- **Spike arrest** — a cap that *smooths bursts* by enforcing a *minimum spacing between requests* rather than counting over a window. The term comes from **Apigee**, where "Spike Arrest" is a distinct policy. A spike arrest of "30 per second" is internally enforced as "at most 1 request every ~33ms" — it doesn't let you fire 30 in the first millisecond and idle for the rest of the second. Intent: protect against *sudden traffic spikes / thundering herds* that would otherwise pass a windowed limit.

> **Thundering herd**: when many clients act simultaneously (e.g., all retry at the exact same second after an outage, or all caches expire at once), producing a synchronized spike. Spike arrest and jittered backoff defend against it.

The key distinction between a rate limit and a spike arrest: a *quota/rate limit* of "30 per second" using a simple counter allows all 30 in the first instant; a *spike arrest* of "30 per second" forces them to be spread out. Many gateways implement rate limits with token buckets, which sit between these two behaviors (allow a burst up to bucket capacity, then throttle to the refill rate).

### 2.2 The dimensions: what are you keying on?

A limit is always *per something*. That "something" is the **dimension** (also called the *limit key* or *scope*). Common dimensions, roughly from narrow to broad:

| Dimension | Key derived from | Use case | Caveat |
|---|---|---|---|
| **Per IP** | Source IP address | Anonymous/unauthenticated traffic, basic DDoS dampening | NAT/CGNAT means many users share one IP; cloud clients rotate IPs |
| **Per API key / credential** | Header (e.g., `Authorization`, `X-API-Key`) | The standard for authenticated APIs | A user may have many keys |
| **Per user** | Authenticated user ID (from JWT/session) | User-facing app fairness | One user, many devices |
| **Per tenant / org / account** | Account ID claim or key→tenant mapping | Multi-tenant SaaS fairness & plans | Must aggregate across the tenant's keys |
| **Per route / endpoint** | URL path + method | Protect expensive endpoints harder | Combine with identity |
| **Global** | (no key — single counter) | Protect a downstream with a hard ceiling | Doesn't isolate noisy neighbors |
| **Composite** | Tuple, e.g. `(tenant, endpoint)` | Real-world policies | Cardinality explosion if careless |

> **Multi-tenant / tenant**: a *tenant* is an isolated customer (a company, an account) within a system that serves many customers from shared infrastructure. *Multi-tenancy* is that shared-infra model. Fair rate limiting per tenant is what keeps one customer from degrading another.

> **JWT (JSON Web Token)**: a signed, base64-encoded token carrying *claims* (key-value assertions like `sub` = subject/user, `tenant_id`, `plan`). Because it's signed, the gateway can trust the claims without a database lookup. Rate-limit dimensions are frequently extracted directly from JWT claims.

> **CGNAT (Carrier-Grade NAT)**: ISPs put thousands of subscribers behind a handful of public IPs. So "per IP" limits can accidentally throttle an entire neighborhood. This is why per-IP limits must be generous and never the *only* control for authenticated traffic.

**Cardinality** matters operationally: each distinct key needs its own counter in your store. Per-IP across IPv6 is effectively unbounded; `(tenant, endpoint, minute)` for 10k tenants × 50 endpoints is 500k live keys per minute — fine for Redis, but you must set TTLs (time-to-live, auto-expiry) so dead keys evaporate.

### 2.3 Soft vs hard limits, and what "exceeding" means

- **Hard limit**: requests over the cap are *rejected* (HTTP 429). Deterministic, protective, but can break clients.
- **Soft limit / throttling**: requests over the cap are *delayed* (queued, slowed) rather than dropped. Better UX for bursty-but-patient clients; worse for latency SLAs and can amplify queueing problems.
- **Shadow / monitor mode**: the limit is *evaluated and logged but not enforced*. Essential when rolling out a new limit — you discover who *would* be throttled before you actually break them.

### 2.4 Fairness models

- **Static per-key limits**: everyone with the same plan gets the same fixed cap. Simple, predictable. The default.
- **Tiered plans**: limits vary by plan (Free/Pro/Enterprise). The plan is a property of the key/tenant, resolved at request time.
- **Fair-share / max-min fairness**: dynamically divide a *shared* capacity pool among active clients so no one starves and idle capacity is reused. More complex; used when a global ceiling must be split fairly under contention.
- **Weighted fairness**: clients get shares proportional to a weight (e.g., paid tiers get bigger shares of a burst pool).
- **Priority / preemption**: critical traffic (health checks, payments) bypasses or outranks bulk traffic.

### 2.5 The two failure philosophies: fail-open vs fail-closed

When the rate-limiting *infrastructure itself* fails (Redis is down, the limit service times out), you must choose:

- **Fail-open**: if you can't check the limit, *allow* the request. Prioritizes availability. Risk: a coordinated attack during a Redis outage gets through. This is the most common default for non-security limits.
- **Fail-closed**: if you can't check the limit, *deny* the request (or fall back to a strict local limit). Prioritizes protection/cost-control. Risk: a Redis blip causes a self-inflicted outage.

A pragmatic middle ground: **fail-open to a conservative local in-memory limit** — if the central store is unavailable, fall back to a small per-instance cap rather than no cap.

---

## 3. How it works internally

This is the heart of the chapter. We'll trace the algorithms, then the *distributed* enforcement that makes them work across many servers.

### 3.1 The algorithms (with internals)

Four classic algorithms. (Token bucket and sliding window are the workhorses and are cross-referenced from the broader "algorithms" chapter; here we focus on their *API-layer* behavior and internals.)

#### 3.1.1 Fixed window counter

**Idea:** divide time into fixed windows (e.g., each calendar minute). Keep a counter per `(key, window)`. Increment on each request; reject when it exceeds the limit; the counter resets when the window rolls.

**Internal workflow, step by step:**
1. Compute the current window id: `window = floor(now / window_size)`.
2. Build a counter key: `rl:{client}:{window}`.
3. Atomically `INCR` the counter; on first increment set a TTL = `window_size` so it self-cleans.
4. If the returned value `> limit`, reject; else allow.

**Pros:** trivial, O(1) memory per key, one atomic op.
**Fatal flaw — the boundary burst:** a client can send `limit` requests at `00:00:59.9` and another `limit` at `00:01:00.0`, i.e. **2× the limit in a ~1-second span** straddling the boundary. For a 100/min limit this means a real-world burst of ~200 in two seconds.

```
limit = 5, window = 1 min
  00:00 ████  (4 reqs)
  00:00:59  █ (5th, allowed — window total = 5)
  00:01:00  █████ (5 more, new window — allowed)
  → 9 requests in ~1.1s, but each window saw ≤5
```

#### 3.1.2 Sliding window log

**Idea:** store the *timestamp of every request* in a sorted set per key. To check, drop timestamps older than `now - window`, then count what remains.

**Internal workflow:**
1. `ZREMRANGEBYSCORE key 0 (now - window)` — evict old entries.
2. `ZCARD key` — count current entries.
3. If `count < limit`: `ZADD key now now` and allow; else reject.
4. Set/refresh TTL = `window`.

**Pros:** *perfectly accurate* — true rolling window, no boundary burst.
**Cons:** memory is O(requests in window) per key — a 10k/min limit stores up to 10k timestamps per key. Expensive at scale. Use only for low-limit, high-value endpoints (e.g., login attempts).

#### 3.1.3 Sliding window counter (approximation)

**Idea:** a hybrid that fixes the boundary burst cheaply. Keep two fixed-window counters (current and previous). Estimate the rolling count as a *weighted blend*:

```
estimated = current_count
          + previous_count * (1 - elapsed_fraction_of_current_window)
```

**Example:** limit 100/min. At 15s into the current minute (`elapsed_fraction = 0.25`), with 80 in the previous minute and 30 so far this minute:
```
estimated = 30 + 80 * (1 - 0.25) = 30 + 60 = 90  → allow (≤100)
```

**Internals:** two `INCR`s and a tiny arithmetic — O(1) memory, near-accurate (the only inaccuracy is the assumption that the previous window's requests were uniformly distributed). This is what most production API gateways and Redis-based limiters use because it's the best accuracy/cost tradeoff. **Cloudflare** famously published that this approximation is within ~0.003% of exact for their traffic.

#### 3.1.4 Token bucket

**Idea:** a bucket holds up to `capacity` tokens and refills at `refill_rate` tokens/sec. Each request removes `cost` tokens (usually 1). If not enough tokens, reject (or wait). The bucket lets clients **burst** up to `capacity` after idle periods, then settles to the sustained `refill_rate`. This burst tolerance is exactly why it's the most popular API rate-limit algorithm.

**State per key:** just two numbers — `tokens` (current count) and `last_refill_timestamp`. This is *lazy refill*: you don't run a background timer; you compute how many tokens *should have* accrued since `last_refill` only when a request arrives.

**Internal workflow (the canonical "lazy" computation):**
1. `now = current_time`.
2. `elapsed = now - last_refill`.
3. `tokens = min(capacity, tokens + elapsed * refill_rate)`  — accrue tokens for elapsed time, capped at capacity.
4. `last_refill = now`.
5. If `tokens >= cost`: `tokens -= cost`; **allow**. Else: **reject** (and compute `retry_after = (cost - tokens) / refill_rate`).
6. Persist `(tokens, last_refill)`.

**State machine:**
```
            request arrives
                 │
         ┌───────▼────────┐
         │ refill tokens   │  tokens = min(cap, tokens + elapsed*rate)
         └───────┬────────┘
        tokens>=cost?  ──no──► REJECT (429, Retry-After=(cost-tokens)/rate)
                 │yes
                 ▼
         tokens -= cost ; ALLOW
```

> **Leaky bucket** is a close cousin: think of a bucket with a hole that *leaks* (processes) requests at a constant rate; incoming requests fill it, and overflow is dropped. As a *queue*, it smooths output to a constant rate (good for spike arrest / shaping). As a *meter* (the "leaky bucket as a meter" formulation) it is mathematically equivalent to token bucket. The practical difference: token bucket allows bursts up to capacity; leaky-bucket-as-queue enforces a strict constant output rate. Apigee's Spike Arrest is essentially a leaky bucket.

#### 3.1.5 Algorithm comparison

| Algorithm | Memory/key | Accuracy | Allows burst? | Smooths spikes? | Typical use |
|---|---|---|---|---|---|
| Fixed window | O(1) | Poor (2× boundary) | Yes (accidental) | No | Quick & dirty, internal |
| Sliding window log | O(n reqs) | Exact | No | Somewhat | Low-volume, high-value (login) |
| Sliding window counter | O(1) | ~Exact | No | Mild | **Default for gateways** |
| Token bucket | O(1) | Exact (to rate) | **Yes (controlled)** | Partially | **Default for rate limits** |
| Leaky bucket (queue) | O(queue) | Exact | No | **Yes (hard)** | Spike arrest, traffic shaping |

### 3.2 Distributed enforcement — the real hard part

A single server with an in-memory token bucket is easy. The challenge: you run **N stateless instances** behind a load balancer, and the limit "100 RPS per key" must hold *across the fleet*, not per instance. If you naively give each of 10 instances a local "100 RPS" bucket, the real limit is 1,000 RPS.

There are three families of solutions.

#### 3.2.1 Centralized counter store (Redis) — the dominant pattern

All instances read/update a shared counter in **Redis** (an in-memory key-value store; extremely fast, single-threaded command execution, supports atomic operations and Lua scripts). The single-threaded execution model is *why Redis is great for this*: commands don't interleave, so counter updates are naturally atomic.

> **Atomic operation**: an operation that completes entirely or not at all, with no other operation observing an intermediate state. Counter increments *must* be atomic; otherwise two instances read `99`, both increment to `100`, and you over-admit (a **race condition** — outcome depends on timing of concurrent operations).

**Why a plain `GET`+`INCR` from the app is wrong:** the read-modify-write is split across the network, so two instances can interleave. The fix is to make the *entire* check-and-update atomic *inside Redis*, via one of:

- **`INCR` + `EXPIRE`** (fixed window): `INCR` is atomic. But you need the `EXPIRE` only on first creation; doing `INCR` then `EXPIRE` as two calls has a subtle bug (if the process dies between them, the key never expires — a leak). Use a Lua script or `SET key 1 EX win NX` semantics.
- **Lua script** (the standard for token bucket / sliding window): Redis runs a Lua script *atomically* — nothing else executes during it. You ship the whole algorithm (refill, check, decrement, set TTL) as one script via `EVALSHA`. This is how Bucket4j, `redis-cell`, and most serious limiters work.
- **`redis-cell` module**: a Redis module exposing `CL.THROTTLE key max_burst count_per_period period [quantity]` implementing the **GCRA** (Generic Cell Rate Algorithm — a leaky-bucket variant from telecom that tracks a single "theoretical arrival time" instead of a token count) atomically in one command, returning whether to allow, the limit, remaining, and retry-after. Elegant but requires installing the module.

**Internal data flow for a Redis token-bucket limiter (Lua):**
```
app instance ── EVALSHA(token_bucket.lua, key, capacity, rate, now, cost) ──► Redis
                                                                              │ (atomic)
   ◄── [allowed:0|1, remaining_tokens, retry_after_ms] ──────────────────────┘
```
Inside the script: read `tokens` and `last_refill` from a hash, recompute refill, decide, write back, set TTL. One round trip, fully atomic.

#### 3.2.2 Local cache + async sync (sloppy counters / token leasing)

Pure-Redis means *one network hop per request* — adds latency and load. At very high RPS you reduce coordination:

- **Token leasing / batching**: each instance *leases* a chunk of the budget from Redis (e.g., reserves 10 tokens at once), serves locally until depleted, then leases again. Cuts Redis traffic ~10×. Cost: slight over-admission near boundaries and unfairness if leases are uneven.
- **Sloppy counters**: each instance counts locally and periodically (every X ms) flushes deltas to a central aggregate, reading back the global total. Eventual consistency; you might briefly exceed the limit. Good when *approximate* enforcement is acceptable (most quotas are).
- **Probabilistic / sampled** enforcement: only check a fraction of requests at very high volume.

This is the classic **accuracy-vs-coordination-cost tradeoff**: tighter accuracy needs more synchronization, which costs latency and throughput.

#### 3.2.3 Gossip / mesh-native enforcement

In service meshes (**Istio/Envoy**), Envoy proxies can do **local rate limiting** (per-proxy token bucket, no coordination — fast but per-instance) or **global rate limiting** (calling out to a shared **RLS** — Rate Limit Service — over gRPC, which itself usually backs onto Redis/Memcached). Envoy's global limiter is essentially pattern 3.2.1 with a dedicated gRPC service in front of the store.

> **Service mesh**: an infrastructure layer (sidecar proxies next to each service instance) that handles service-to-service networking — including rate limiting, retries, mTLS, observability — transparently to the application. **Envoy** is the most common sidecar proxy; **Istio** is a control plane that configures Envoy.

> **gRPC**: a high-performance RPC framework using HTTP/2 and Protocol Buffers (a compact binary serialization). Envoy talks to its rate-limit service over gRPC for low latency.

#### 3.2.4 Clock skew and the time problem

Distributed limiting depends on *time*, and clocks on different machines disagree (**clock skew**). For sliding windows and token-bucket refill, if instances pass their *local* `now` to Redis, skew causes inconsistency. Defenses:
- Use **Redis server time** (`TIME` command, or `redis.call('TIME')` inside the Lua script) as the single source of truth so all instances share one clock.
- Synchronize hosts with **NTP** (Network Time Protocol — keeps machine clocks aligned to within a few ms of a reference).

### 3.3 The quota lifecycle (longer windows)

Quotas reuse the counter machinery but with a long window and extra concerns:

1. **Window alignment**: monthly quotas align to the *billing cycle* (which may be the signup anniversary, not the calendar month). The counter key embeds the cycle id.
2. **Reset / rollover**: at cycle end the counter resets (or unused quota *rolls over* if the plan allows — rare; complicates accounting).
3. **Metering vs enforcement**: quotas often *meter* (record usage for billing) even past the cap, charging overage, rather than hard-blocking. So "quota" sometimes means "billing meter with a soft alarm" not "hard wall."
4. **Eventual consistency is usually fine**: monthly quotas don't need per-request strong consistency; a sloppy counter flushed every few seconds is acceptable, since being off by a few calls in a million doesn't matter.
5. **Persistence**: unlike short rate limits (ephemeral, fine to lose on Redis restart), quota counters often must survive restarts → backed by a durable store (Redis with AOF/RDB persistence, or a database) and reconciled against an authoritative usage log.

### 3.4 Putting it together: request lifecycle through a gateway

End-to-end control flow for one request hitting an API gateway with rate limiting:

```
1. Client → TLS termination at edge/LB
2. Edge WAF: coarse per-IP volumetric / DDoS filtering (may drop here)
3. Gateway receives request
4. Authn: validate API key / JWT → resolve identity (user, tenant, plan)
5. Resolve limit policy for (identity, route, plan)  [cache the policy lookup]
6. Derive limit key(s): e.g. rl:tenant:{t}:route:{r}:min
7. Atomic check against Redis (token bucket Lua / sliding window)
      ├─ ALLOWED  → annotate request with X-RateLimit-* for response
      └─ DENIED   → short-circuit: 429 + Retry-After + X-RateLimit-* (don't hit backend)
8. (If allowed) Quota check: increment monthly counter; if over → 429/402
9. Forward to upstream service
10. On response, gateway injects X-RateLimit-* headers
11. Emit metrics/logs: allowed/denied, key, remaining
```

Note step 7: **denied requests must short-circuit** — never forward them to the backend, because the whole point is to protect the backend.

---

## 4. The complete toolkit

A senior JVM engineer should know what's available at each layer. Tables of concrete tools, their knobs, and defaults.

### 4.1 JVM libraries (in-process limiting)

| Library | Algorithm(s) | Distributed? | Key API | Notes |
|---|---|---|---|---|
| **Bucket4j** | Token bucket | Yes (Redis/Hazelcast/Ignite/Infinispan/JCache backends) | `Bucket`, `Bandwidth`, `Refill`, `ConsumptionProbe` | The de-facto JVM rate-limiter; supports multiple bandwidths per bucket |
| **Resilience4j `RateLimiter`** | Atomic time-window (semaphore-style) | No (in-JVM only) | `RateLimiter`, `RateLimiterConfig` | Part of the resilience suite (circuit breaker, bulkhead); great for *protecting your own outbound calls* |
| **Guava `RateLimiter`** | Token bucket (smooth + warmup) | No | `RateLimiter.create(permitsPerSecond)`, `acquire()`, `tryAcquire()` | Single-JVM; `acquire()` *blocks*; warmup ramps rate after idle |
| **Spring Cloud Gateway `RequestRateLimiter`** | Redis sliding/token | Yes (Redis) | `RedisRateLimiter` filter, `KeyResolver` | Built into Spring's reactive gateway |
| **resilience4j-ratelimiter + Reactor/RxJava** | as above | No | operators | For reactive pipelines |

**Bucket4j key parameters (`Bandwidth`):**
- `capacity` — bucket size (max burst).
- `refillGreedy(tokens, duration)` — refills `tokens` smoothly over `duration` (continuous accrual).
- `refillIntervally(tokens, duration)` — adds all `tokens` at once at each interval (bursty refill).
- `refillIntervallyAligned(...)` — aligns refill to wall-clock boundaries (good for "X per calendar minute").
- A `Bucket` can hold **multiple `Bandwidth`s** (e.g., 50/sec *and* 10,000/hour simultaneously — all must pass).
- `tryConsumeAndReturnRemaining(n)` → `ConsumptionProbe` with `isConsumed()`, `getRemainingTokens()`, `getNanosToWaitForRefill()` (→ `Retry-After`).

**Resilience4j `RateLimiterConfig` parameters & defaults:**
- `limitForPeriod` — permits per cycle (default 50).
- `limitRefreshPeriod` — cycle length (default 500ns — you will override this, e.g. to `Duration.ofSeconds(1)`).
- `timeoutDuration` — how long a caller *waits* for a permit before failing (default 5s; set to `0` for non-blocking reject).

**Guava `RateLimiter` parameters:**
- `RateLimiter.create(double permitsPerSecond)` — smooth bursty.
- `RateLimiter.create(permitsPerSecond, warmupPeriod, unit)` — ramps up after idle (warmup) to protect cold caches/connection pools.
- `acquire()` blocks and returns time slept; `tryAcquire(timeout)` returns boolean.

### 4.2 Redis primitives & tools

| Tool | What it does | Key params | Defaults / notes |
|---|---|---|---|
| `INCR` / `INCRBY` | Atomic counter increment | key, amount | Returns new value |
| `EXPIRE` / `PEXPIRE` / `SET ... EX/PX` | Set TTL (auto-delete) | key, seconds/ms | Use `SET k v EX n NX` to create-with-TTL atomically |
| `EVALSHA` / `EVAL` | Run Lua atomically | script SHA, keys, args | Standard for token bucket / sliding window |
| Sorted sets (`ZADD`/`ZREMRANGEBYSCORE`/`ZCARD`) | Sliding window log | key, score=timestamp | O(log n); memory heavy |
| `redis-cell` module: `CL.THROTTLE` | GCRA limiter in one command | key, max_burst, count, period, [quantity] | Returns `[allowed, limit, remaining, retry_after, reset_after]` |
| `redis.call('TIME')` | Server clock | — | Defeats client clock skew |

### 4.3 API gateways & their rate-limit policies

| Gateway | Policy/plugin | Algorithms | Store | Notable knobs |
|---|---|---|---|---|
| **Kong** | `rate-limiting`, `rate-limiting-advanced` | fixed/sliding window | local, Redis, cluster, DB | `limit`, `window_size`, `sync_rate`, `strategy`, `hide_client_headers` |
| **Apigee** | `Quota`, `SpikeArrest`, `ResponseCache` | quota counters + spike arrest (leaky) | distributed | `Allow`, `Interval`, `TimeUnit`, `Distributed`, `Synchronous`; SpikeArrest `Rate` |
| **AWS API Gateway** | Usage Plans + throttling | token bucket | managed | `rateLimit` (steady RPS), `burstLimit` (bucket capacity), `quotaLimit` (per day/week/month) |
| **Envoy / Istio** | local & global rate limit | token bucket + RLS | in-proxy / Redis-backed RLS | `tokens_per_fill`, `fill_interval`, `max_tokens`; descriptors |
| **Spring Cloud Gateway** | `RequestRateLimiter` | Redis token/sliding | Redis | `replenishRate`, `burstCapacity`, `requestedTokens`, `KeyResolver` |
| **NGINX** | `limit_req`, `limit_conn` | leaky bucket | shared memory zone | `rate`, `burst`, `nodelay`, `zone` size |
| **Cloudflare** | Rate Limiting Rules / WAF | sliding window counter | edge-distributed | `requests`, `period`, `mitigation_timeout`, characteristics |

**NGINX `limit_req` specifics (very common, worth memorizing):**
- `limit_req_zone $binary_remote_addr zone=mylimit:10m rate=10r/s;` — defines a 10 MB shared zone keyed by client IP, 10 requests/second (leaky bucket).
- `limit_req zone=mylimit burst=20 nodelay;` — allow a burst queue of 20; `nodelay` serves burst immediately rather than spacing them. Returns **503** by default on rejection (configurable via `limit_req_status 429;`).
- A 10 MB zone holds ~160,000 keys (each state ~64 bytes).

**AWS API Gateway specifics:**
- Account-level default throttle: historically **10,000 RPS** steady, **5,000** burst (region/account-specific; check current quotas — these are soft limits raisable via support).
- Usage Plan: `throttle.rateLimit`, `throttle.burstLimit`, `quota.limit` + `quota.period` (DAY/WEEK/MONTH). Per-method overrides allowed.

### 4.4 Response-design toolkit (HTTP semantics)

| Element | Spec / convention | Meaning |
|---|---|---|
| **429 Too Many Requests** | RFC 6585 | The canonical rate-limit rejection status |
| **503 Service Unavailable** | RFC 7231 | Sometimes used for overload/shedding (NGINX default) — but 429 is preferred for *client*-attributable limits |
| **`Retry-After`** | RFC 7231 | Seconds to wait (`Retry-After: 30`) or HTTP-date. Honor it in clients! |
| **`X-RateLimit-Limit`** | de-facto (GitHub/Twitter style) | The cap for the window |
| **`X-RateLimit-Remaining`** | de-facto | Calls left in current window |
| **`X-RateLimit-Reset`** | de-facto | When the window resets (epoch seconds, or seconds-until — *be explicit & consistent!*) |
| **`RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset`** | IETF draft `draft-ietf-httpapi-ratelimit-headers` (no `X-`) | The emerging standard, possibly with a `RateLimit` structured field combining all three |
| **402 Payment Required** | RFC 7231 | Sometimes for *quota/plan* exhaustion (you've used your paid allotment) |

> **RFC (Request for Comments)**: the document series that defines internet/HTTP standards. RFC 6585 (2012) added status code 429. The `X-RateLimit-*` headers are *not* in an RFC — they're conventions popularized by GitHub/Twitter; an IETF draft is standardizing the non-`X` versions.

---

## 5. Code examples by use case

Idiomatic, copy-adaptable. Java where relevant.

### 5.1 In-process token bucket with Bucket4j (single service, tiered plans)

```java
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-API-key, tiered, in-memory limiter. Suitable for a single instance
 * or as a fast local fallback in front of a distributed store.
 */
public class TieredRateLimiter {

    // One bucket per key. ConcurrentHashMap is thread-safe for the map;
    // Bucket4j buckets are themselves thread-safe (lock-free CAS internally).
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    enum Plan {
        FREE(10, Duration.ofSeconds(1), 1_000, Duration.ofHours(1)),
        PRO(100, Duration.ofSeconds(1), 100_000, Duration.ofHours(1)),
        ENTERPRISE(1_000, Duration.ofSeconds(1), 5_000_000, Duration.ofHours(1));

        final long rps; final Duration rpsWindow;
        final long hourly; final Duration hourWindow;
        Plan(long rps, Duration rpsWindow, long hourly, Duration hourWindow) {
            this.rps = rps; this.rpsWindow = rpsWindow;
            this.hourly = hourly; this.hourWindow = hourWindow;
        }
    }

    private Bucket newBucket(Plan plan) {
        // Two simultaneous bandwidths: a per-second burst cap AND an hourly cap.
        // A request must satisfy BOTH. This is how you combine a rate limit
        // (smoothness) with a quota-ish ceiling (volume) in one bucket.
        Bandwidth perSecond = Bandwidth.classic(
                plan.rps, Refill.greedy(plan.rps, plan.rpsWindow)); // smooth refill
        Bandwidth perHour = Bandwidth.classic(
                plan.hourly, Refill.intervally(plan.hourly, plan.hourWindow)); // bursty refill
        return Bucket.builder()
                .addLimit(perSecond)
                .addLimit(perHour)
                .build();
    }

    /** @return Decision with allow flag and Retry-After hint (seconds). */
    public Decision check(String apiKey, Plan plan) {
        Bucket bucket = buckets.computeIfAbsent(apiKey, k -> newBucket(plan));
        // Try to consume 1 token; get a probe describing the outcome.
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, probe.getRemainingTokens(), 0);
        }
        // Nanos until enough tokens accrue → round up to whole seconds for Retry-After.
        long retryAfterSec = (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L;
        return new Decision(false, 0, retryAfterSec);
    }

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {}
}
```

**What matters:** two `Bandwidth`s on one bucket give you "100/sec *and* 100k/hour" enforced together. `greedy` refill drips tokens continuously (smooth); `intervally` dumps them at interval boundaries (matches "per hour" billing semantics). `ConsumptionProbe` hands you the `Retry-After` directly.

### 5.2 Distributed token bucket via a Redis Lua script (the production pattern)

The Lua script (atomic, runs entirely inside Redis):

```lua
-- token_bucket.lua
-- KEYS[1] = bucket state key (a Redis hash with fields: tokens, ts)
-- ARGV[1] = capacity (max burst)
-- ARGV[2] = refill_rate (tokens per second, may be fractional)
-- ARGV[3] = requested tokens (cost)
-- ARGV[4] = ttl seconds (auto-clean idle buckets)
-- Returns: { allowed(1/0), remaining_tokens, retry_after_ms }

local capacity    = tonumber(ARGV[1])
local rate        = tonumber(ARGV[2])
local requested   = tonumber(ARGV[3])
local ttl         = tonumber(ARGV[4])

-- Use Redis server time so every app instance shares ONE clock (no skew).
local t = redis.call('TIME')                 -- { seconds, microseconds }
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000.0

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local last   = tonumber(state[2])

if tokens == nil then                        -- first time we see this key
  tokens = capacity
  last = now
end

-- Lazy refill: accrue tokens for the elapsed time, capped at capacity.
local elapsed = math.max(0, now - last)
tokens = math.min(capacity, tokens + elapsed * rate)

local allowed = 0
local retry_after_ms = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  -- How long until enough tokens accrue?
  retry_after_ms = math.ceil(((requested - tokens) / rate) * 1000)
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('EXPIRE', KEYS[1], ttl)           -- self-cleaning

return { allowed, math.floor(tokens), retry_after_ms }
```

The Java caller (using Lettuce or Jedis; shown with Spring Data Redis):

```java
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

public class RedisTokenBucketLimiter {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;

    public RedisTokenBucketLimiter(StringRedisTemplate redis, String luaSource) {
        this.redis = redis;
        // Redis caches the script by SHA; EVALSHA avoids re-sending the body.
        this.script = new DefaultRedisScript<>(luaSource, List.class);
    }

    public Decision check(String key, long capacity, double ratePerSec,
                          long cost, long ttlSeconds) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> r = (List<Long>) redis.execute(
                    script,
                    List.of("rl:" + key),                       // KEYS[1]
                    String.valueOf(capacity),                   // ARGV...
                    String.valueOf(ratePerSec),
                    String.valueOf(cost),
                    String.valueOf(ttlSeconds));
            boolean allowed = r.get(0) == 1L;
            return new Decision(allowed, r.get(1), r.get(2));
        } catch (Exception e) {
            // FAIL-OPEN policy: if Redis is unreachable, allow the request
            // (optionally fall back to a small local bucket here).
            return new Decision(true, -1, 0);
        }
    }

    public record Decision(boolean allowed, long remaining, long retryAfterMs) {}
}
```

**What matters:** the *entire* refill/check/decrement/TTL sequence runs atomically inside Redis — no race across instances. `redis.call('TIME')` removes clock-skew bugs. The `catch` block encodes the **fail-open** decision explicitly — make that choice consciously, don't let it be an accident.

### 5.3 Spring Boot servlet filter that emits correct 429 + headers

```java
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

/** Enforces the limit and writes spec-correct rejection responses. */
public class RateLimitFilter extends HttpFilter {

    private final RedisTokenBucketLimiter limiter;
    private final PlanResolver plans; // maps API key -> plan limits

    public RateLimitFilter(RedisTokenBucketLimiter limiter, PlanResolver plans) {
        this.limiter = limiter; this.plans = plans;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res,
                            FilterChain chain) throws IOException, ServletException {
        String apiKey = req.getHeader("X-API-Key");
        if (apiKey == null) { res.sendError(401, "missing api key"); return; }

        var plan = plans.resolve(apiKey);   // {capacity, rate, limit}
        var d = limiter.check(apiKey, plan.capacity(), plan.rate(), 1, 3600);

        // Always advertise the limit so well-behaved clients can self-pace.
        res.setHeader("X-RateLimit-Limit", String.valueOf(plan.capacity()));
        res.setHeader("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, d.remaining())));

        if (!d.allowed()) {
            long retryAfterSec = (d.retryAfterMs() + 999) / 1000;
            res.setStatus(429);                                  // RFC 6585
            res.setHeader("Retry-After", String.valueOf(retryAfterSec));
            res.setHeader("Content-Type", "application/problem+json");
            res.getWriter().write("""
                {"type":"https://api.example.com/errors/rate-limit",
                 "title":"Too Many Requests",
                 "status":429,
                 "detail":"Rate limit exceeded. Retry after %d seconds.",
                 "retryAfter":%d}""".formatted(retryAfterSec, retryAfterSec));
            return; // SHORT-CIRCUIT: never call chain.doFilter → backend protected
        }
        chain.doFilter(req, res);
    }
}
```

**What matters:** `429`, `Retry-After`, and `X-RateLimit-*` are all set; the body uses **RFC 7807 `application/problem+json`** (a standard machine-readable error format) so clients can parse it. The denied path returns *before* `chain.doFilter`, protecting the backend.

### 5.4 Spring Cloud Gateway declarative config (Redis-backed, per-user)

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: orders
          uri: lb://orders-service
          predicates:
            - Path=/api/orders/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100     # tokens/sec (sustained)
                redis-rate-limiter.burstCapacity: 200      # bucket size (burst)
                redis-rate-limiter.requestedTokens: 1      # cost per request
                key-resolver: "#{@userKeyResolver}"        # bean below
```

```java
@Bean
KeyResolver userKeyResolver() {
    // Extract the JWT subject claim as the limit key (per-user limiting).
    return exchange -> exchange.getPrincipal()
            .map(p -> ((JwtAuthenticationToken) p).getToken().getSubject())
            .switchIfEmpty(Mono.just("anonymous")); // fall back so a single
                                                    // bucket throttles all anon
}
```

**What matters:** zero custom limiter code — the gateway uses a built-in Redis Lua sliding/token limiter and injects `X-RateLimit-*` headers automatically. `burstCapacity > replenishRate` permits controlled bursts.

### 5.5 Login brute-force protection (sliding window log, low limit, fail-closed)

```java
/** Strict per-account login throttle: 5 attempts / 15 min, EXACT, fail-CLOSED. */
public class LoginThrottle {
    private final StringRedisTemplate redis;
    private static final int MAX = 5;
    private static final long WINDOW_MS = 15 * 60_000L;

    public boolean allowAttempt(String account) {
        String key = "login:" + account;
        long now = System.currentTimeMillis();
        try {
            // Sliding-window log via a sorted set: exact count, small N (≤5).
            redis.opsForZSet().removeRangeByScore(key, 0, now - WINDOW_MS);
            Long count = redis.opsForZSet().zCard(key);
            if (count != null && count >= MAX) return false;
            redis.opsForZSet().add(key, now + ":" + Math.random(), now);
            redis.expire(key, java.time.Duration.ofMillis(WINDOW_MS));
            return true;
        } catch (Exception e) {
            return false; // FAIL-CLOSED: security limit — deny if store is down
        }
    }
}
```

**What matters:** for *security* limits the philosophy flips to **fail-closed**, and the low cap makes the exact sliding-window-log affordable. (For brute-force you'd also add escalating delays/CAPTCHA, and limit per-IP too.)

### 5.6 Cost-weighted limiting (not all requests are equal)

```java
/**
 * Weighted token bucket: a cheap GET costs 1 token, an expensive
 * report-generation costs 50. Protects the backend by ACTUAL load,
 * not request count.
 */
public Decision checkWeighted(String key, String endpoint, RedisTokenBucketLimiter rl) {
    long cost = switch (endpoint) {
        case "GET /v1/status"        -> 1;
        case "GET /v1/search"        -> 5;   // hits the search cluster
        case "POST /v1/reports"      -> 50;  // heavy aggregation
        case "POST /v1/bulk-import"  -> 100; // very heavy
        default                      -> 1;
    };
    return rl.check(key, /*capacity*/ 500, /*rate*/ 100, cost, 3600);
}
```

**What matters:** rate limiting by raw request count misprices load. Cost-weighting (a.k.a. "credits" or "compute units" — what AWS, Shopify, and many LLM APIs do) charges proportional to real backend cost. The same token-bucket Lua handles it via the `cost` argument.

### 5.7 Envoy global rate limit (config, no app code)

```yaml
# Envoy route descriptor → calls the external Rate Limit Service (RLS over gRPC)
rate_limits:
  - actions:
      - request_headers: { header_name: "x-tenant-id", descriptor_key: "tenant" }
      - request_headers: { header_name: ":path",        descriptor_key: "path" }
```
```yaml
# RLS config (lyft/ratelimit), backed by Redis
descriptors:
  - key: tenant
    descriptors:
      - key: path
        value: "/v1/reports"
        rate_limit: { unit: minute, requests_per_unit: 60 }
```

**What matters:** the mesh extracts descriptors `(tenant, path)` from headers and asks a central RLS (Redis-backed) for a verdict — distributed limiting with no application code, enforced at the proxy.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Minimize Redis round trips.** One atomic Lua call per request is the target; never do `GET` then `INCR` (two trips + race). At extreme RPS, use **token leasing/batching** (§3.2.2) to amortize.
- **Use `EVALSHA`, not `EVAL`.** Send the SHA, not the script body, every call.
- **Pipeline / connection-pool Redis** (Lettuce shares a single connection with multiplexing; Jedis needs a pool). A blocking, single-connection client at 50k RPS is a bottleneck.
- **Co-locate Redis** in the same AZ/region as the gateway to keep the per-request latency add < ~1ms. Cross-region Redis adds tens of ms per request — unacceptable in the hot path.
- **Local-first checks**: do cheap, certain rejections (missing key, malformed) before the Redis call.
- **Budget the overhead**: a well-built Redis limiter adds ~0.2–1ms p50. If yours adds 10ms, you've made the limiter the bottleneck.

### 6.2 Correctness & concurrency

- **Atomicity is non-negotiable** for the counter update (§3.2.1). Race conditions = over-admission.
- **Clock skew** breaks distributed time-based algorithms → use server-side time (§3.2.4).
- **TTL hygiene**: every short-lived counter must have a TTL or you leak memory in Redis (unbounded key growth → OOM → eviction → silent limit failures).
- **Beware eviction policy**: if Redis `maxmemory-policy` is `allkeys-lru`, your *counters* can be evicted under memory pressure, silently resetting limits. Use a dedicated Redis or `volatile-*` policy and monitor eviction stats.
- **Idempotency vs limiting**: a client retrying an *idempotent* request after a 429 is fine; ensure retries respect `Retry-After` so they don't worsen the storm.

### 6.3 Security

- **Don't key solely on IP for authenticated traffic** (CGNAT, IP rotation). Key on credential/tenant; use IP only for anonymous endpoints and as a secondary signal.
- **Spoofable headers**: `X-Forwarded-For` can be forged. Only trust it from your own edge/LB; configure `trusted proxies` so an attacker can't inject a fake client IP to dodge per-IP limits.
- **Per-endpoint hardening of auth routes**: login, password-reset, token endpoints get *strict, fail-closed* limits to throttle brute force and credential stuffing.
- **Don't leak too much in headers**: exposing exact remaining/reset can help attackers time their bursts; some APIs hide client headers on certain plans (`hide_client_headers` in Kong). Tradeoff against client-friendliness.
- **Layered DDoS defense**: app-level limits won't stop a 100 Gbps volumetric flood — that must be absorbed at the edge/CDN/WAF (§6.7). App limits handle *application-layer* (L7) abuse.

### 6.4 Observability

Instrument from day one. Emit metrics with the **limit key dimension** (carefully — high cardinality! Aggregate or sample tenant labels):

- **Counters**: allowed vs throttled (429) per route/plan/tenant.
- **Rate of 429s** — a spike means either an attack or a too-tight limit hurting real users. You must be able to tell which.
- **Top throttled keys** — who's hitting limits? (Could be a buggy customer, an attacker, or a customer who needs an upsell.)
- **Limiter health**: Redis latency p99, error rate, fail-open activations (alert if fail-open fires — it means you're flying blind).
- **Remaining-quota distribution** for quota plans (proactively warn customers nearing limits).
- **Tracing**: tag spans with the limit decision so you can correlate a customer's 429s with their traffic pattern.

### 6.5 Cost

- Redis is cheap per-op but not free at millions of RPS; batching/leasing reduces both cost and latency.
- Quotas are themselves a *cost-control* mechanism — uncapped LLM/third-party-API usage is the runaway-cost scenario quotas exist to prevent.
- Sliding-window-log memory cost (O(n) per key) can dominate; reserve it for low-limit endpoints.

### 6.6 Testing

- **Unit-test the algorithm** with a *mock clock* (inject `now`) — never sleep in tests; advance the fake clock to assert refill behavior deterministically.
- **Test the boundary** (the fixed-window 2× burst; the exact moment of refill).
- **Load test** with a tool (`k6`, `wrk`, `vegeta`, `Gatling`) to confirm the limit holds across the *fleet*, not per instance — run multiple gateway replicas and verify aggregate enforcement.
- **Chaos-test the store**: kill Redis mid-load and verify your fail-open/closed behavior is what you intended (this is where most teams discover their fallback is wrong).
- **Test header correctness**: assert `429`, `Retry-After`, `X-RateLimit-*` values match the algorithm's internal state.

### 6.7 Production hardening

- **Roll out in shadow/monitor mode first** (§2.3) to learn real traffic shapes before enforcing.
- **Make limits configurable at runtime** (config service / feature flags) — you *will* need to raise a limit at 3 a.m. for a legitimate customer spike without a deploy.
- **Provide an allowlist/bypass** for critical internal callers, health checks, and trusted partners.
- **Graceful degradation** (see §6.8).
- **Document limits publicly** and surface them in headers so clients can self-throttle — this prevents most accidental abuse.

### 6.8 Graceful degradation when limits hit

Rejection is not the only response to overload. A spectrum, from gentle to harsh:

1. **Advertise & let clients self-pace** (`X-RateLimit-*` headers + good docs).
2. **Serve cached/stale data** for read endpoints instead of 429 (`stale-while-revalidate`).
3. **Degrade the response** — drop optional fields, lower result counts, disable expensive enrichment.
4. **Throttle (delay) instead of reject** for patient, non-latency-critical traffic.
5. **Queue & process asynchronously** — accept with `202 Accepted` and process later (good for writes/imports).
6. **Shed by priority / load shedding** — drop low-priority traffic first (analytics) to preserve high-priority (checkout). 
7. **Hard reject (429)** — last resort for the noisy neighbor / attacker.

> **Load shedding**: deliberately dropping a fraction of work when overloaded so the rest succeeds, rather than collapsing under the full load. Rate limiting is *predictable, per-client* shedding; generic load shedding is *reactive, system-wide*. Use both.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it bites | Fix |
|---|---|---|
| Per-instance in-memory limits behind a LB | Real limit = N× intended | Centralized/distributed counter |
| `GET` then `INCR` from the app | Race → over-admission | One atomic Lua/`INCR` |
| No TTL on counters | Redis memory leak → OOM | Always set TTL |
| Fixed window for strict limits | 2× boundary burst | Sliding window counter / token bucket |
| Keying only on IP for auth'd traffic | CGNAT collateral damage; trivially evaded | Key on credential/tenant |
| Returning 200 with empty body on throttle | Clients can't tell; retry storms | Proper 429 + Retry-After |
| No `Retry-After` | Clients hammer immediately, worsening the storm | Always include it |
| Trusting `X-Forwarded-For` blindly | IP spoofing bypasses limits | Trusted-proxy config |
| Limiter in the hot path with cross-region Redis | Adds 50ms to every request | Co-locate; lease/batch |
| Enforcing a new limit with no shadow period | Surprise customer outage | Monitor mode first |
| Silent fail-open with no alert | You're unprotected and don't know | Metric + alert on fallback |

---

## 7. Advanced topics & deep internals

### 7.1 GCRA — the limiter you didn't know you were using

The **Generic Cell Rate Algorithm** (from ATM telecom) is what `redis-cell` and many CDNs use. Instead of tracking a token count, it tracks a single timestamp: the **Theoretical Arrival Time (TAT)** — the earliest time the next request *should* arrive to stay within rate. State is *one number per key*. On each request: if `now >= TAT - burst_tolerance`, allow and advance `TAT = max(TAT, now) + emission_interval`; else reject. It is mathematically equivalent to a leaky bucket but with minimal state and built-in burst tolerance — extremely memory-efficient and naturally produces a precise `Retry-After`. Worth knowing because "token bucket" and "GCRA" often describe the *same observable behavior*.

### 7.2 Sliding window counter math — when the approximation lies

The weighted blend (§3.1.3) assumes the previous window's requests were *uniformly distributed*. If a client front-loaded all of last window's requests into its final second, the estimate undercounts and admits slightly more than the limit; if they back-loaded, it overcounts and rejects slightly early. The maximum error is bounded by the previous window's count × the fractional overlap. For typical traffic the error is sub-percent (Cloudflare reported ~0.003%). Know this so you can answer "is it exact?" with "no, but the error is bounded and tiny for real traffic, in exchange for O(1) memory."

### 7.3 Multi-tier / hierarchical limits

Real policies stack limits at multiple scopes simultaneously, and a request must pass *all*:
```
global:        100,000 RPS (protect the cluster)
per-tenant:      1,000 RPS (fairness)
per-key:           100 RPS (one credential)
per-endpoint:       10 RPS (expensive route)
```
Each is a separate counter; the request is admitted only if every applicable bucket has capacity. **Order matters for cost**: check the cheapest/most-likely-to-reject limit first to short-circuit. Atomicity across *multiple* counters is tricky — if you decrement the global bucket then the per-key bucket rejects, you've "spent" a global token you shouldn't have. Solutions: (a) check-all-then-commit-all in one Lua script over multiple keys (all keys must hash to the same Redis slot in cluster mode — use **hash tags** `{tenant}`), or (b) accept slight over-counting and reconcile.

> **Redis hash tag / slot**: in Redis Cluster, keys are sharded across 16,384 *slots* by hashing the key. A multi-key command (or Lua script) requires all keys in the *same slot*. Wrapping part of the key in braces — `rl:{tenant42}:perkey` and `rl:{tenant42}:global` — forces both into the same slot so one atomic script can touch both.

### 7.4 Fair-share dynamic allocation

When you have a *shared* global budget (say a downstream that can do 1,000 RPS total) and many tenants, static per-tenant caps waste capacity (idle tenants' shares go unused). **Max-min fair sharing** dynamically divides the budget: give each active tenant an equal share, and redistribute any unused share to tenants that want more, capped at their demand. Implementations track active-tenant counts in a sliding window and recompute shares periodically. This is significantly more complex and is used by sophisticated platforms (e.g., internal cloud control planes). The tradeoff: better utilization vs. unpredictable per-tenant limits (a tenant's effective limit now depends on others' behavior).

### 7.5 Burst credits / token banking

Some systems let unused capacity *accumulate* as burst credits up to a cap (AWS does this with several services' burst-balance model — e.g., the old t2/t3 CPU credits, and gp2 EBS). Token bucket already does a bounded version (idle → bucket fills to `capacity`). "Burst credits" extends the idea with a larger, slower-refilling reserve for occasional big spikes while keeping the steady rate low. Tradeoff: rewards bursty-but-light usage; harder to reason about worst-case load.

### 7.6 The "retry storm" / metastable failure

A subtle, dangerous interaction: when a backend slows down, clients time out and **retry**, *increasing* load, which slows the backend further — a positive feedback loop that can keep the system down even after the original trigger is gone (**metastable failure**). Rate limiting + `Retry-After` + **exponential backoff with jitter** on the client are the primary defenses.

> **Exponential backoff with jitter**: on each retry, wait `base * 2^attempt` plus a *random* amount (jitter). Exponential growth reduces retry pressure; jitter de-synchronizes clients so they don't all retry at the same instant (avoiding the thundering herd). "Full jitter" (`random(0, base*2^attempt)`) is the AWS-recommended variant.

A server-side complement is **circuit breaking**: stop calling a failing dependency for a cooldown, returning fast errors so threads aren't tied up.

> **Circuit breaker**: a state machine (closed → open → half-open) that trips "open" after a failure threshold, short-circuiting calls to a failing dependency, then periodically tests recovery ("half-open"). Resilience4j and Envoy provide this. It's the protective sibling of rate limiting — limiting controls *inbound* pressure, circuit breaking controls *outbound* pressure.

### 7.7 Concurrency limits vs rate limits

A *rate* limit caps requests per time. A **concurrency limit** caps *simultaneous in-flight* requests (e.g., "≤ 50 concurrent per tenant") — implemented with a counter incremented on start and decremented on completion, or a semaphore/bulkhead. Concurrency limits protect against slow requests that a rate limit misses: 10 RPS of 30-second requests = 300 concurrent. **Bulkhead** (Resilience4j `Bulkhead`/`ThreadPoolBulkhead`) isolates concurrency per dependency so one slow dependency can't exhaust all threads. Mature systems enforce *both* rate and concurrency limits.

### 7.8 Adaptive / dynamic rate limiting

Static limits are set conservatively for worst case. **Adaptive limiting** adjusts the limit based on real-time signals — backend latency, error rate, CPU. **Netflix's `concurrency-limits`** library implements TCP-Vegas-style **AIMD** (Additive Increase, Multiplicative Decrease) on a concurrency limit: increase the limit while latency is healthy, sharply cut it when latency rises. This finds the system's actual capacity dynamically instead of guessing. Tradeoff: more complex, can oscillate, harder to reason about for capacity planning.

### 7.9 Quota accounting edge cases

- **Refunds**: if a request is admitted, decrements the quota, then fails *before doing work* (backend 500), should you refund the quota token? For metered/billed quotas, yes (don't charge for failed work). This requires *post-hoc adjustment* — increment on admit, decrement-back on failure, which reintroduces correctness complexity.
- **Reservation pattern**: for expensive multi-step operations, *reserve* quota up front and *commit/release* at the end — like a database transaction. Prevents over-commit but adds a two-phase flow.
- **Billing-cycle boundaries & time zones**: a "daily" quota resets at midnight *in which time zone?* Mismatches cause confusing customer reports. Define and document it (usually UTC).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Algorithm selection

| Need | Choose | Why |
|---|---|---|
| Allow controlled bursts, sustained rate | **Token bucket** | Burst up to capacity, then settle to rate |
| Strict per-window cap, cheap, near-exact | **Sliding window counter** | O(1), fixes boundary burst |
| Exact count, low volume, high stakes (login) | **Sliding window log** | Perfectly accurate; affordable at low N |
| Smooth output, hard spike arrest | **Leaky bucket / GCRA** | Constant output rate |
| Internal, quick, don't care about boundary | **Fixed window** | Simplest |

### 8.2 Where to enforce

| Need | Layer | Why |
|---|---|---|
| Volumetric DDoS absorption | Edge/CDN/WAF | Capacity to soak floods; closest to attacker |
| Per-key/plan limits, header injection | API gateway | Sees identity; centralizes policy |
| Fine-grained, business-aware, expensive endpoints | Application | Full context; custom cost weighting |
| Protect *outbound* calls to a dependency | App (Resilience4j) | You're the client there |

### 8.3 Distributed enforcement strategy

| Need | Strategy | Tradeoff |
|---|---|---|
| Strong accuracy, moderate RPS | Centralized Redis Lua | +1 round trip latency |
| Very high RPS, approximate OK | Token leasing / sloppy counters | Slight over-admission |
| Mesh-native, no app code | Envoy global RLS | Operational complexity |
| Single instance / dev | In-memory (Bucket4j/Guava) | Doesn't hold across fleet |

### 8.4 Fail philosophy

- **Use when fail-open:** general traffic shaping, non-security limits, where availability > strictness.
- **Use when fail-closed:** auth/security endpoints, hard cost ceilings (expensive backends), where a breach is worse than an outage.
- **Best of both:** fail-open to a conservative *local* limit.

### 8.5 Rate limit vs quota vs spike arrest — when to use which

- **Rate limit** when protecting *instantaneous capacity / latency* (per-second/minute caps).
- **Quota** when enforcing *plans/fairness/cost* over long windows (daily/monthly).
- **Spike arrest** when a *sudden synchronized burst* would pass a windowed limit but still overwhelm a fragile backend (smoothing).
- In practice, large APIs use **all three together**: spike arrest to smooth, rate limit per second/minute, quota per month.

---

## 9. Failure modes & debugging

### 9.1 Common production failures

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Real limit is N× intended | Per-instance in-memory limits | Compare configured vs observed aggregate; count gateway replicas | Move to distributed store |
| Sudden 429 spike, real users hurt | Limit too tight, or a deploy changed it, or legit customer growth | 429 metric broken down by tenant; compare to traffic; check config change log | Raise limit (runtime config), or upsell |
| 429s only at minute boundaries | Fixed-window boundary burst | Histogram of request times within window | Switch to sliding window counter / token bucket |
| Limits silently stop working | Redis evicted counters (memory pressure) or fell over (fail-open) | Redis `INFO` (evicted_keys, used_memory), eviction metric, fail-open counter | Dedicated Redis, `volatile-*` policy, alert on eviction & fail-open |
| Intermittent over/under admission | Clock skew across instances | Compare host clocks; NTP offset | Use Redis server time in Lua |
| Latency spike on every request | Cross-region/overloaded Redis in hot path | Redis command latency p99; trace the limiter span | Co-locate Redis; lease/batch; pipeline |
| One IP throttles many users | CGNAT keyed on IP | Map throttled IPs to user counts | Key on credential, not IP |
| Memory leak in Redis | Missing TTLs on counter keys | `redis-cli --bigkeys`, monitor key count over time | Add TTL to every counter |
| Cascading outage after a blip | Retry storm / metastable failure | Trace retry patterns; correlation of retries with errors | Client backoff+jitter; circuit breakers; `Retry-After` |

### 9.2 Diagnostic tools & commands

- **Redis introspection**: `redis-cli INFO stats` (look at `instantaneous_ops_per_sec`, `evicted_keys`, `keyspace_hits/misses`), `INFO memory` (`used_memory`, `maxmemory`, `mem_fragmentation_ratio`), `--bigkeys` (find oversized counter keys), `MONITOR` (see live commands — *dev only*, it's expensive), `SLOWLOG GET` (slow commands), `DBSIZE` / key-count trend (leak detection), `CLIENT LIST` (connection count).
- **Inspect a specific bucket**: `HGETALL rl:tenant42` to see `tokens`/`ts`; `TTL rl:...` to confirm expiry is set.
- **Gateway logs/metrics**: Kong's `RateLimiting` plugin emits headers and logs; Envoy exposes `ratelimit.*` stats; check 429 rate by route.
- **Load/repro tools**: `k6 run` with a ramp profile to reproduce a limit; `vegeta attack -rate=200/s` to confirm the cap; `hey`/`wrk` for quick checks.
- **Client-side debugging**: inspect `X-RateLimit-*` and `Retry-After` headers (`curl -i`) to see what the server *claims* the limit is vs. observed behavior — mismatches reveal bugs.

### 9.3 Real-world failure stories (representative patterns)

- **The 2× boundary outage**: a team sets "1000/min" with a fixed window; a partner's hourly batch fires at minute boundaries and pushes ~2000/min through, overwhelming the DB. Fix: sliding window counter. (This is the single most common rate-limiting bug.)
- **The fail-open during attack**: Redis hiccups during a maintenance window; the limiter fails open (sensible default), but an attacker is mid-scrape and the now-unlimited traffic takes down the backend. Lesson: fail-open to a *conservative local limit*, and alert on fail-open activation.
- **CGNAT collateral**: a mobile app keys limits on IP; a large carrier NATs thousands of users behind a few IPs; legitimate users get throttled en masse and the support queue explodes. Fix: key on the authenticated user.
- **The retry-storm meltdown (metastable failure)**: a brief DB slowdown causes timeouts; clients without jittered backoff retry in lockstep; the synchronized retries keep the DB pinned long after the original cause cleared. Famous public postmortems (across the industry) trace major outages to exactly this loop. Fix: backoff+jitter, circuit breakers, and `Retry-After`.
- **Counter eviction silently disables limits**: Redis used for both caching and rate limiting with `allkeys-lru`; under cache pressure the rate-limit counters get evicted, resetting limits and silently removing protection. Fix: separate Redis instance / `volatile-lru` + alert on evictions.

---

## 10. Interview drill

**Q1. What's the difference between a rate limit, a quota, and spike arrest?**
*Model answer:* A rate limit caps request *frequency* over a short window (RPS/RPM) to protect instantaneous capacity and latency. A quota caps *cumulative consumption* over a long window (daily/monthly) to enforce plans, fairness, and cost. Spike arrest caps the *spacing between requests* (a min interval) to smooth sudden bursts that would pass a windowed limit. Large APIs use all three.
- *Probe: Why isn't a per-second rate limit enough; why add spike arrest?* Because a windowed limit of 30/sec can let all 30 arrive in the first millisecond; spike arrest forces ~33ms spacing, protecting a fragile backend from instantaneous bursts.
- *Probe: Is a quota a rate limit with a long window?* Mechanically similar (a counter), but semantically different: a quota doesn't care about distribution within the window and is usually about billing/fairness, often metered rather than hard-blocked, and may need durability and refunds.

**Q2. Walk me through the token bucket algorithm and its state.**
*Model answer:* A bucket holds up to `capacity` tokens, refilling at `rate` tokens/sec. Each request consumes tokens; if insufficient, reject. State is just `tokens` and `last_refill_time`. On each request you *lazily* refill: `tokens = min(capacity, tokens + elapsed*rate)`, update timestamp, then decrement if enough. It allows bursts up to `capacity` after idle, then settles to `rate`.
- *Probe: Why lazy refill instead of a timer?* No background threads/timers per key; O(1) state; you compute accrual on demand. Scales to millions of keys.
- *Probe: How do you compute Retry-After?* `(cost - tokens) / rate` — time for enough tokens to accrue.

**Q3. How do you enforce a single limit across many stateless servers?**
*Model answer:* Use a shared atomic counter store (Redis), with the whole check-and-update done atomically in one Lua script per request, using Redis server time to avoid clock skew, and TTLs for cleanup. Naive per-instance limits multiply the real limit by the instance count.
- *Probe: Why must the update be atomic and how do you achieve it?* Otherwise two instances read the same value and both increment → over-admission (race). A Lua script (or single atomic command) executes without interleaving in Redis's single-threaded model.
- *Probe: At 200k RPS, one Redis call per request is heavy. What now?* Token leasing/batching (reserve a chunk locally) or sloppy counters with periodic sync — trading exactness for fewer round trips.

**Q4. Compare fixed window, sliding window log, sliding window counter, and token bucket.**
*Model answer:* Fixed window: O(1), simple, but 2× boundary burst. Sliding log: exact, but O(n) memory. Sliding counter: O(1), near-exact via weighted blend of two windows — the default for gateways. Token bucket: O(1), exact to the rate, allows controlled bursts — the default for rate limits. (Reference the comparison table.)
- *Probe: The sliding window counter is approximate — when does it err?* It assumes uniform distribution of the previous window's requests; front/back-loaded traffic causes bounded sub-percent error.
- *Probe: Which for login brute-force protection?* Sliding window log — exact, and the low limit (e.g., 5/15min) makes O(n) cheap.

**Q5 (senior signal). You're designing limits for a multi-tenant SaaS with Free/Pro/Enterprise tiers. Walk me through the design.**
*Model answer:* Resolve identity → tenant + plan from the API key/JWT. Enforce hierarchical limits: global (protect cluster), per-tenant (fairness), per-key (one credential), per-endpoint (expensive routes) — all must pass. Use token bucket (burst + sustained) in Redis via atomic Lua, hash-tagged by tenant so multi-key checks share a slot. Plans drive `capacity`/`rate`/`quota`. Add monthly quotas for billing. Emit `X-RateLimit-*` + 429/`Retry-After`. Roll out in shadow mode; make limits runtime-configurable; allowlist internal callers; fail-open to a conservative local limit with alerting. Cost-weight expensive endpoints.
- *Probe: How do you keep one tenant from starving others while not wasting idle capacity?* Per-tenant caps give isolation but waste idle capacity; max-min fair sharing redistributes unused budget dynamically — more utilization, less predictability.
- *Probe: Where exactly do you enforce — edge, gateway, or app?* Defense in depth: edge/WAF for volumetric DDoS, gateway for per-key/plan, app for business-aware/cost-weighted. Each layer has different visibility.

**Q6 (senior signal). Fail-open or fail-closed when Redis is down? Justify.**
*Model answer:* It depends on what the limit protects. For general traffic shaping, fail-open (availability > strictness) — a Redis blip shouldn't take you down. For security endpoints (login) and hard cost ceilings (expensive LLM backend), fail-closed — a breach/cost-blowout is worse than rejecting some traffic. Best practice: fail-open to a *conservative local in-memory limit* so you're never fully unprotected, and alert on every fail-open activation.
- *Probe: What's the risk of silent fail-open?* You're unprotected during the exact window an attacker may exploit, and you don't know it — hence the alert.
- *Probe: How do you test this?* Chaos-test: kill Redis under load and assert the observed behavior matches the intended policy.

**Q7. Design the HTTP response for a throttled request.**
*Model answer:* Status **429 Too Many Requests** (RFC 6585). Include **`Retry-After`** (seconds or HTTP-date) so clients know when to retry. Include `X-RateLimit-Limit/Remaining/Reset` (or the IETF `RateLimit-*` draft) so clients self-pace. Body in `application/problem+json` (RFC 7807) with a machine-readable error. Never forward a denied request to the backend.
- *Probe: 429 vs 503?* 429 attributes the limit to the *client* ("you sent too much"); 503 is server-side unavailability/overload. Use 429 for per-client limits.
- *Probe: Why is omitting Retry-After dangerous?* Clients retry immediately and in lockstep, amplifying the storm.

**Q8. What is the fixed-window boundary problem and how do you fix it?**
*Model answer:* A fixed-window counter resets at window boundaries, so a client can send the full limit at the end of one window and again at the start of the next — up to 2× the limit in a short span across the boundary. Fix with a sliding window counter (weighted blend of current+previous windows) or a token bucket, both of which avoid the reset cliff.
- *Probe: Quantify it.* For 100/min, ~200 requests within a ~2-second span straddling the boundary.

**Q9 (senior signal). Your API is suddenly throwing lots of 429s and customers are complaining. How do you triage?**
*Model answer:* First, distinguish *attack vs. self-inflicted vs. legit growth*. Break down the 429 metric by tenant/route. If concentrated on one key → likely a buggy client or attacker (check their request pattern, consider per-key block/allowlist). If broad and correlated with traffic growth → limits too tight; raise via runtime config (don't deploy). Check the config change log for a recent limit change. Verify the limiter store is healthy (Redis evictions/latency, fail-open activations). Inspect specific buckets (`HGETALL`) and header values vs. observed behavior. If boundary-clustered, suspect fixed-window burst.
- *Probe: How do you tell an attack from legit growth quickly?* Cardinality and pattern: an attack is usually few keys/IPs with abnormal patterns (no auth, repetitive paths, off-hours); growth is broad and tracks business metrics.
- *Probe: What's your immediate mitigation if it's an attack mid-incident?* Block/strict-limit the offending keys/IPs at the edge/WAF, allowlist known-good tenants, and tighten anonymous limits — fast, reversible, edge-level changes.

**Q10. How do you handle requests that aren't all equal in cost?**
*Model answer:* Cost-weighted limiting — assign each endpoint a token cost proportional to backend load (cheap GET = 1, heavy report = 50) and consume that many tokens. The same token-bucket machinery handles it via the `cost` parameter. This prices by real load, not request count (what AWS/Shopify/LLM APIs do with "credits"/"compute units").
- *Probe: How do you advertise weighted limits to clients?* Document the cost per endpoint and report remaining *credits* in headers; some APIs return the cost of the just-served request.

**Q11. Rate limit vs concurrency limit — when do you need each?**
*Model answer:* A rate limit caps requests per time; a concurrency limit caps simultaneous in-flight requests. A pure rate limit misses slow requests — 10 RPS of 30s requests = 300 concurrent, exhausting threads. Use both: rate limits for frequency, concurrency limits (bulkhead/semaphore) for resource saturation by slow work.
- *Probe: How implement a concurrency limit?* Increment a counter/semaphore on start, decrement on completion (try/finally); reject when at cap. Resilience4j `Bulkhead` does this.

**Q12 (senior signal). Explain a retry storm / metastable failure and how rate limiting relates.**
*Model answer:* A transient slowdown causes client timeouts and retries; retries add load, slowing the system further — a feedback loop that persists even after the trigger clears (metastable). Rate limiting + `Retry-After` (server side) and exponential backoff with jitter + circuit breakers (client side) break the loop. Limiting controls inbound pressure; circuit breaking controls outbound; jitter de-synchronizes the herd.
- *Probe: Why jitter specifically?* Without it, all clients retry at the same instant after a uniform backoff, recreating the synchronized spike (thundering herd).
- *Probe: How does a circuit breaker help here?* It fails fast on a known-bad dependency, freeing threads and stopping retries from piling onto a struggling backend, giving it room to recover.

---

## 11. Glossary

- **AIMD (Additive Increase / Multiplicative Decrease)**: a control strategy (from TCP congestion control) that slowly raises a limit while healthy and sharply cuts it on trouble; used in adaptive limiting.
- **Atomic operation**: completes wholly or not at all, with no observable intermediate state; required for correct counter updates.
- **Bandwidth (Bucket4j)**: a configured rate (capacity + refill) on a bucket; a bucket may have several.
- **Backoff (exponential, with jitter)**: client retry strategy — wait grows exponentially per attempt plus randomness to avoid synchronized retries.
- **Bucket4j**: the de-facto JVM token-bucket rate-limiting library, with distributed backends.
- **Bulkhead**: isolates concurrency/resources per dependency so one failure can't sink everything (named after ship compartments).
- **Burst capacity**: the max tokens a bucket holds = the largest instantaneous burst allowed.
- **CGNAT (Carrier-Grade NAT)**: ISP technique putting many subscribers behind few public IPs; breaks per-IP limiting.
- **Circuit breaker**: state machine (closed/open/half-open) that stops calling a failing dependency, then tests recovery.
- **Clock skew**: disagreement between machine clocks; breaks distributed time-based algorithms unless a shared clock is used.
- **Concurrency limit**: cap on simultaneous in-flight requests (vs. requests per time).
- **Cost-weighted limiting**: charging a variable token cost per request based on backend load ("credits"/"compute units").
- **DDoS (Distributed Denial of Service)**: many machines flooding a target to exhaust capacity.
- **Defense in depth**: layering controls (edge + gateway + app) so no single failure removes protection.
- **EVALSHA / EVAL**: Redis commands to run a Lua script atomically (by SHA / by body).
- **Fail-open / fail-closed**: when the limiter infra fails, allow (open) vs. deny (closed) requests.
- **Fairness (max-min)**: dividing shared capacity so no client starves and idle capacity is reused.
- **Fixed window**: counter that resets each fixed interval; simple but suffers the 2× boundary burst.
- **GCRA (Generic Cell Rate Algorithm)**: leaky-bucket variant tracking a single "theoretical arrival time"; minimal state, precise; used by `redis-cell`.
- **gRPC**: high-performance HTTP/2 + Protobuf RPC framework; Envoy uses it to call its rate-limit service.
- **Hash tag (Redis)**: braces in a key (`{tenant}`) forcing keys into the same cluster slot for multi-key atomic ops.
- **JWT (JSON Web Token)**: signed token carrying claims (user, tenant, plan); rate-limit dimensions often come from its claims.
- **Leaky bucket**: bucket leaking at a constant rate; as a queue it shapes output to a fixed rate (spike arrest); as a meter it equals token bucket.
- **Load shedding**: deliberately dropping some work under overload to keep the rest healthy.
- **Lua script (Redis)**: code run atomically inside Redis; the standard way to implement token bucket/sliding window.
- **Metastable failure**: a feedback loop (e.g., retry storm) that keeps a system down even after the original trigger clears.
- **Multi-tenancy / tenant**: serving many isolated customers from shared infrastructure; a tenant is one such customer.
- **NTP (Network Time Protocol)**: keeps machine clocks synchronized to a reference, limiting skew.
- **Quota**: cap on cumulative consumption over a long window; enforces plans/fairness/cost.
- **Race condition**: a bug where the outcome depends on the timing of concurrent operations.
- **Rate limit**: cap on request frequency over a short window; protects capacity/latency.
- **Redis**: fast in-memory key-value store with atomic ops and Lua; the standard distributed-limiter backend.
- **redis-cell**: a Redis module exposing `CL.THROTTLE`, a one-command GCRA limiter.
- **Retry-After**: HTTP header telling a client when to retry after a 429/503.
- **RFC (Request for Comments)**: internet/HTTP standards documents (e.g., RFC 6585 defines 429).
- **RLS (Rate Limit Service)**: Envoy's external gRPC service that makes global limit decisions, usually backed by Redis.
- **Service mesh / Envoy / Istio**: sidecar-proxy networking layer; Envoy is the proxy, Istio the control plane; both can rate-limit.
- **Sliding window counter**: O(1) near-exact limiter blending current+previous fixed windows; gateway default.
- **Sliding window log**: exact limiter storing every request timestamp; O(n) memory.
- **Sloppy counter**: locally counted, periodically synced approximate counter for high-RPS quotas.
- **Spike arrest**: smoothing control enforcing minimum spacing between requests; protects against synchronized bursts.
- **Thundering herd**: synchronized client behavior producing a spike (e.g., simultaneous retries / cache expiries).
- **Token bucket**: capacity + refill-rate limiter allowing controlled bursts; the default rate-limit algorithm.
- **Token leasing / batching**: reserving a chunk of budget locally to cut central-store round trips.
- **TTL (Time To Live)**: auto-expiry on a key; essential for counter cleanup to prevent memory leaks.
- **WAF (Web Application Firewall)**: edge security layer for filtering malicious/volumetric traffic.
- **X-RateLimit-* headers**: de-facto response headers advertising limit/remaining/reset (IETF standardizing the non-`X` forms).
- **429 Too Many Requests / 503 / 402**: HTTP statuses for rate-limit rejection / overload / quota-payment respectively.

---

## 12. Cheat-sheet & self-test

### One-screen recap

**Three controls:** rate limit (how fast, short window) · quota (how much total, long window) · spike arrest (min spacing, smooth bursts).
**Dimensions:** IP < API key < user < tenant < route < global · composite tuples · always per *something*.
**Algorithms:** fixed window (O(1), **2× boundary burst**) · sliding log (exact, O(n)) · **sliding counter** (O(1), near-exact, gateway default) · **token bucket** (O(1), controlled burst, rate-limit default) · leaky bucket/GCRA (smooth output, spike arrest).
**Token bucket internals:** state = `{tokens, last_refill}`; lazy refill `tokens=min(cap, tokens+elapsed*rate)`; `Retry-After=(cost-tokens)/rate`.
**Distributed:** shared Redis counter, **one atomic Lua call/request**, **Redis server TIME** (no skew), **TTL on every key**. Scale via leasing/sloppy counters. Hash-tag `{tenant}` for multi-key atomicity.
**Response:** **429** + **`Retry-After`** + `X-RateLimit-Limit/Remaining/Reset` + `application/problem+json`; **short-circuit** denied requests.
**Fail policy:** fail-open (availability) vs fail-closed (security/cost); best = fail-open to conservative *local* limit + **alert**.
**Layers (defense in depth):** edge/WAF (DDoS) · gateway (per-key/plan) · app (cost-weighted, business).
**Graceful degradation ladder:** advertise → cache/stale → degrade fields → throttle → queue (202) → shed by priority → 429.
**Anti-patterns:** per-instance limits behind LB · GET+INCR race · no TTL · fixed window for strict caps · IP-only for auth'd · no Retry-After · trust `X-Forwarded-For` · cross-region Redis in hot path · enforce without shadow mode · silent fail-open.
**Adjacent:** concurrency limit (in-flight cap) · bulkhead · circuit breaker · backoff+jitter (defeat retry storms / metastable failure) · adaptive AIMD limiting.
**Key numbers:** fixed-window worst case = **2× limit** across boundary · well-built Redis limiter adds **~0.2–1ms** · sliding-counter error **~sub-percent** (Cloudflare ~0.003%) · 10MB NGINX `limit_req` zone ≈ **160k keys**.
**Toolkit:** JVM = Bucket4j, Resilience4j, Guava · gateways = Kong, Apigee (Quota/SpikeArrest), AWS API GW (rate/burst/quota), Envoy RLS, Spring Cloud Gateway, NGINX `limit_req` · store = Redis (`INCR`/`EXPIRE`/`EVALSHA`/sorted sets/`redis-cell`).

### Self-test (no answers — recall actively)

1. A teammate sets "600/min" with a fixed-window counter. A partner's batch job fires exactly on the minute. Predict the worst-case burst they could push through in a 2-second span, and name two algorithms that fix it.
2. You run 8 gateway replicas, each with a Guava `RateLimiter.create(50)`. A customer is configured for 50 RPS but observes ~400. Explain precisely why, and how you'd fix it without changing the per-replica code's intent.
3. Write the Redis-side reasoning for why `GET key; if < limit then INCR` from the application is incorrect, and what single mechanism makes it correct.
4. Design the response (status, headers, body type) for a throttled request, and justify why each element exists. Then explain what *must not* happen to a denied request.
5. Your login endpoint must allow exactly 5 attempts per 15 minutes per account, must never under-count even if it costs more, and must deny if the limiter store is unreachable. Specify the algorithm, the store structure, and the fail policy, and justify each choice.
6. Distinguish a rate limit from a concurrency limit with a concrete number that shows a system can pass the former while being destroyed by the latter.
7. Explain how clock skew across instances corrupts a distributed sliding-window limiter and the one change that eliminates the problem.
8. A senior asks: "Fair per-tenant caps waste our idle capacity. What's the alternative and what do we give up?" Answer with the mechanism and the tradeoff.
9. During an incident you see a surge of 429s. Give the decision tree you'd use to classify it as attack vs. tight-limit vs. legit-growth, and the first reversible mitigation for each.
10. Explain a retry storm end to end and list the server-side and client-side controls that break the loop, including why jitter specifically is required.

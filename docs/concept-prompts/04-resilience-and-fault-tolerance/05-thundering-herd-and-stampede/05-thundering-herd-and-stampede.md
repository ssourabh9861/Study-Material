# Thundering Herd & Cache Stampede

> **Concept area:** Resilience & Fault Tolerance
> **Subtopic:** Thundering Herd & Cache Stampede
> **Reader:** A senior Java/JVM backend developer who wants to master this topic from first principles to deep internals — enough to design, operate, debug, teach, and interview on it.

---

## Table of contents

1. [Overview & where it fits](#1-overview--where-it-fits)
2. [Foundations from first principles](#2-foundations-from-first-principles)
3. [How it works internally](#3-how-it-works-internally)
4. [The complete toolkit](#4-the-complete-toolkit)
5. [Code examples by use case](#5-code-examples-by-use-case)
6. [Implementation concerns & best practices](#6-implementation-concerns--best-practices)
7. [Advanced topics & deep internals](#7-advanced-topics--deep-internals)
8. [Tradeoffs & decision frameworks](#8-tradeoffs--decision-frameworks)
9. [Failure modes & debugging](#9-failure-modes--debugging)
10. [Interview drill](#10-interview-drill)
11. [Glossary](#11-glossary)
12. [Cheat-sheet & self-test](#12-cheat-sheet--self-test)

---

## 1. Overview & where it fits

### 1.1 What it is, in one paragraph

A **thundering herd** is what happens when a large number of independent actors (clients, threads, processes, pods) all wake up and contend for the *same* scarce resource at the *same* instant, instead of spreading their demand out over time. The classic symptom: one event (a cache key expiring, a service coming back online, a notification fan-out, a lock being released) triggers a synchronized stampede that overwhelms whatever sits behind it — a database, a downstream service, a CPU, a lock. A **cache stampede** (also called the **dogpile effect**) is the most common and most damaging concrete instance of this pattern: a hot cache entry expires, and every request that needed it simultaneously misses the cache and slams the origin (the database or upstream service) to recompute the same value, multiplying load by the number of concurrent requests precisely when the system is already busy.

### 1.2 The problem it solves (or rather, prevents)

Caching exists to *reduce* load on an expensive backend. The cruel irony of cache stampede is that the caching layer, at the worst possible moment, *amplifies* load instead. Consider a homepage cached for 60 seconds, serving 10,000 requests/second. For 60 seconds the database sees ~0 queries for that key. At second 60 the key expires and — if nothing protects you — up to 10,000 requests in the next few milliseconds all miss, all decide "I'll recompute it," and all hit the database with the *identical* query at once. The database that was idle a moment ago now gets a 10,000× burst. It slows down, which makes each recompute take longer, which means the cache stays empty longer, which lets *even more* requests pile in. This positive feedback loop is the dogpile, and it routinely causes full outages.

The techniques in this chapter — **request coalescing / single-flight**, **per-key locking**, **probabilistic early expiration**, **TTL jitter / staggered expiry**, and **stale-while-revalidate** — are the standard toolkit for converting that N×-amplification spike back into a single (or near-single) backend call.

### 1.3 When you reach for it

You need thundering-herd / stampede mitigation whenever **all three** of these are true:

1. **Concentration** — many requests want the *same* key / resource (a "hot key"), not a uniform spread over millions of keys.
2. **Expensive recomputation** — regenerating the cached value is costly (a heavy SQL aggregate, an external API call, a CPU-bound render) relative to serving from cache.
3. **Synchronization** — there is a moment that aligns demand: a TTL expiry, a deploy, a cache flush, a scheduled cron, a coordinated retry, a "drop" / flash sale, a leader election.

If keys are uniformly cold and cheap to compute, you usually don't need this. If one key serves a huge fraction of traffic, you absolutely do.

### 1.4 The one-paragraph mental model

> **Think of a single narrow door (the origin) and a crowd (the requests).** A cache is a sign on the door saying "the answer is X, don't bother going in." When the sign expires, everyone rushes the door at once. The fixes are all variants of crowd control: (a) **single-flight** — send *one* person through and have everyone else wait for them to come back with the answer; (b) **locks** — bolt the door so only one gets in; (c) **early/probabilistic refresh** — replace the sign *before* it expires, while the crowd is calm, so the door is never unguarded; (d) **jitter** — make different signs expire at slightly different times so crowds never form; (e) **stale-while-revalidate** — keep showing the old (slightly stale) sign to the crowd while one person quietly fetches a fresh one.

### 1.5 Where it sits in the resilience landscape

Thundering herd is a member of the broader family of **load-amplification / correlated-failure** problems, alongside **retry storms**, **metastable failures**, and **cascading failures**. It is deeply intertwined with:

- **Backoff & jitter** (Chapter on retries): the same jitter math that de-synchronizes retries also de-synchronizes cache expiry.
- **Circuit breakers**: a tripped breaker that closes all at once re-creates a herd against the recovering dependency (the "half-open probe" exists precisely to avoid this).
- **Rate limiting / load shedding**: the backstop when coalescing isn't enough.
- **Bulkheads**: isolate the blast radius so a herd on one resource doesn't exhaust threads needed elsewhere.

---

## 2. Foundations from first principles

This section assumes you are sharp but new to *this specific topic*. We define every term as it appears.

### 2.1 What is a cache?

A **cache** is a fast store that holds copies of data that is expensive to fetch or compute, so future requests can be served quickly. The slow place the data really comes from is the **origin** or **backend** (a database, an upstream microservice, a remote API, a filesystem, a CPU-heavy computation). A **cache hit** means the data was found in the cache; a **cache miss** means it wasn't and you must go to the origin. The **hit ratio** = hits / (hits + misses); a well-tuned cache might run 95–99.9%.

Caches come in layers:

- **In-process / local cache** — lives inside your JVM heap (e.g., a `ConcurrentHashMap`, Guava `Cache`, or **Caffeine**). Nanosecond–microsecond access, but each process has its own copy (no sharing, possible inconsistency).
- **Distributed / remote cache** — a separate service shared by many app instances (e.g., **Redis** or **Memcached**). Sub-millisecond to low-millisecond access over the network, but shared and consistent across instances.
- **CDN / edge cache** — caches HTTP responses near users (e.g., Cloudflare, Fastly, Akamai, CloudFront).
- **CPU / OS caches** — hardware and page caches; out of scope here but the same herd dynamics appear (e.g., many threads stampeding a cold mmap'd file).

> **Redis** is an in-memory key-value data store, commonly used as a distributed cache. It is (classically) single-threaded for command execution, supports rich data types and atomic operations, and has scripting via **Lua** (a small embeddable language). We'll use it heavily in examples.
> **Memcached** is a simpler, multi-threaded, purely key-value in-memory cache. It lacks Redis's data structures and scripting, which matters for some mitigations.
> **Caffeine** is a high-performance Java caching library (the de facto successor to Guava Cache), notable for near-optimal hit rates (W-TinyLFU eviction) and a built-in async refresh mechanism that is directly relevant to this chapter.

### 2.2 What is a TTL?

**TTL** = **Time To Live**: how long a cached entry is considered valid before it must be discarded or refreshed. After the TTL elapses, the entry **expires**. Expiry is the single most common trigger for a stampede, because TTL gives every copy of a key a *shared deadline*.

Two flavors:

- **Absolute / write-time expiry** (`expireAfterWrite`): entry dies a fixed time after it was *written*. Hot keys written together expire together → herd risk.
- **Access-time / idle expiry** (`expireAfterAccess`): entry dies a fixed time after it was last *read*. Hot keys keep getting read so they rarely expire on idle, but a synchronized read pattern can still align.

### 2.3 What is a "herd" and why "thundering"?

The term **thundering herd** comes from operating-system kernels. Historically, when many processes were blocked waiting (`accept()`/`epoll`) on the same listening socket and a single connection arrived, the kernel would wake **all** of them. They'd all race to handle one connection; one wins, the rest go back to sleep having burned CPU and cache lines for nothing. The "thunder" is the simultaneous wake-up of the whole "herd." Linux later added flags (`EPOLLEXCLUSIVE`, `SO_REUSEPORT`) to wake only one waiter — these are themselves thundering-herd mitigations baked into the kernel (more in §7).

The pattern generalizes to *any* synchronized contention: lock release, condition-variable `notifyAll`, leader election, a CDN purge, a coordinated client retry.

### 2.4 What is cache stampede / dogpile?

**Cache stampede** (a.k.a. **dogpile**, a.k.a. **cache miss storm**) is the thundering herd applied to a cache miss. Sequence:

1. A hot key `K` is in the cache, serving N concurrent readers cheaply.
2. `K` expires (TTL hits, or it's evicted, or it's flushed).
3. In the time window between expiry and the first successful refill, **every** reader misses.
4. With no coordination, all N readers independently recompute `K` against the origin.
5. The origin sees N identical expensive requests at once → overload → latency spike → longer refill window → even more pile-up.

The damage scales with **N (concurrency at the moment of expiry) × C (cost per origin call)**. Mitigations attack one of three levers: reduce N reaching the origin (coalescing/locks), avoid the unguarded window (early refresh, SWR), or desynchronize so multiple keys never expire together (jitter).

### 2.5 The core mitigation primitives — defined plainly

| Primitive | One-line definition |
|---|---|
| **Single-flight / request coalescing** | Collapse many concurrent identical requests into ONE origin call; all callers share its result. |
| **Per-key lock (mutex)** | Only the lock holder recomputes the value; others wait for it or serve stale. |
| **Probabilistic early expiration (XFetch)** | Each reader *probabilistically* decides to refresh *before* the TTL, with probability rising as expiry nears, so exactly one (statistically) refreshes early and the herd never forms. |
| **TTL jitter / staggered TTL** | Add randomness to each key's TTL so a batch of keys written together don't all expire at the same instant. |
| **Stale-while-revalidate (SWR)** | Keep serving the stale value to readers while a single background task refreshes it; nobody waits on the origin. |
| **Negative caching** | Cache the *absence* of a value (or an error) briefly so a missing/failing key doesn't stampede the origin on every miss. |
| **Backoff + jitter** | On retry, wait a randomized, growing delay so failed clients don't all retry in lockstep. |

> A **mutex** (mutual exclusion lock) is a synchronization primitive that lets at most one thread/process into a critical section at a time. A **distributed lock** does the same across machines (e.g., a lock stored in Redis or ZooKeeper).
> **ZooKeeper** is a distributed coordination service providing strongly-consistent primitives (locks, leader election, config) via an ordered, replicated log. Used when you need correctness guarantees a single Redis can't give.

### 2.6 A worked numeric intuition

Suppose a key is requested at **5,000 RPS**, costs **40 ms** to recompute at the origin, and has a **60 s** TTL.

- **No protection:** during the ~40 ms refill window, ~`5000 × 0.040 = 200` requests miss and each fires an origin call → a **200×** burst. If the origin slows to 400 ms under that load, the window grows to 400 ms → ~2,000 piled-up calls → it gets worse (metastable).
- **Single-flight:** exactly **1** origin call; the other ~199 wait ~40 ms and share the result. Burst factor **1×**.
- **Stale-while-revalidate:** **1** background origin call; the ~199 readers get the stale value in microseconds and never wait. Burst factor **1×**, and zero added latency.
- **Probabilistic early refresh:** the refresh happens *before* expiry while traffic is normal, so there's no miss window at all; statistically ~1 refresher.

This is the whole game: turn 200× into 1×.

---

## 3. How it works internally

This is the heart of the chapter. We trace each mitigation's control flow, data flow, lifecycle, and state machine.

### 3.1 The unprotected read path (the baseline that breaks)

```
read(K):
  v = cache.get(K)
  if v != null:            # HIT
      return v
  # MISS — the dangerous branch
  v = origin.load(K)       # every concurrent misser runs this
  cache.put(K, v, TTL)
  return v
```

**Why it stampedes:** the gap between `cache.get` returning null and `cache.put` writing the value is a **race window**. Every thread that enters the MISS branch during that window independently calls `origin.load`. There is no coordination, so coordination is exactly what every mitigation adds.

State of a key over time:

```
[FRESH]──TTL elapses──▶[EXPIRED/ABSENT]──first put──▶[FRESH]
                          ▲                  │
                          └── all readers miss here (the herd)
```

### 3.2 Single-flight / request coalescing — internals

**Goal:** within one process (or one shard), if K computations are already in flight, new callers *join* the in-flight one instead of starting their own.

**Data structure:** a map from key → an in-flight **promise/future** (`ConcurrentHashMap<K, CompletableFuture<V>>` in Java).

**Control flow:**

```
loadCoalesced(K):
  f = inFlight.get(K)
  if f != null:                      # someone is already loading K
      return f.join()                # JOIN the existing call
  promise = new CompletableFuture()
  prev = inFlight.putIfAbsent(K, promise)   # ATOMIC: only one wins
  if prev != null:                   # lost the race; join the winner
      return prev.join()
  try:
      v = origin.load(K)             # WINNER does the single real call
      promise.complete(v)
      cache.put(K, v, TTL)
      return v
  finally:
      inFlight.remove(K, promise)    # cleanup so future misses re-trigger
```

**Key correctness points:**
- The `putIfAbsent` (compare-and-set) is the linchpin: it atomically elects exactly one winner per key. `ConcurrentHashMap.computeIfAbsent` does the same and is even tighter (it holds a per-bin lock so the lambda runs once).
- Removal in `finally` must use the *value-checked* `remove(K, promise)` to avoid deleting a *newer* in-flight entry that replaced this one.
- On failure, you complete the promise exceptionally; decide whether followers retry or share the failure (see negative caching).

**Lifecycle / state machine of an in-flight entry:**

```
ABSENT ──first caller wins putIfAbsent──▶ IN_FLIGHT ──origin returns──▶ COMPLETED
   ▲                                          │                            │
   │                                          └──origin throws──▶ FAILED   │
   └──────────────── remove() in finally ◀───────────────────────────────┘
```

**Scope matters:** plain Java single-flight coalesces *within one JVM*. With M app instances, you get up to **M** origin calls (one per instance) — far better than M×N, but not 1. To reach a true single call cluster-wide you need a *distributed* lock or a distributed coalescing layer (§3.4, §7).

> **Go's `singleflight`** package (`golang.org/x/sync/singleflight`) is the canonical reference implementation; its `Do(key, fn)` is exactly the algorithm above and its name is now the generic term.

### 3.3 Per-key lock (mutex) — internals

Two sub-variants: **local** (within JVM) and **distributed** (across instances).

#### 3.3.1 Local per-key lock

```
read(K):
  v = cache.get(K); if v != null return v
  lock = locks.computeIfAbsent(K, k -> new ReentrantLock())  # one lock per key
  lock.lock()
  try:
      v = cache.get(K)            # DOUBLE-CHECK: maybe filled while we waited
      if v != null return v
      v = origin.load(K)
      cache.put(K, v, TTL)
      return v
  finally:
      lock.unlock()
```

The **double-check** after acquiring the lock is essential: the thread that held the lock before you probably already filled the cache, so you should re-read before deciding to recompute. This is functionally identical to single-flight; the difference is that lock-based code *blocks* threads (one per waiter) whereas future-based coalescing lets one thread complete and wakes the rest — cheaper under high N. Use a **striped lock** (a fixed array of N locks indexed by `hash(key) % N`) instead of a per-key lock map if you want to bound memory; Guava's `Striped<Lock>` does this. The tradeoff: two unrelated keys can map to the same stripe and block each other (false sharing of the lock).

#### 3.3.2 Distributed lock (Redis `SET NX PX`) — internals

To collapse to one origin call across the *whole cluster*, the lock must live where all instances can see it.

```
read(K):
  v = redis.get(K); if v != null return deserialize(v)
  token = randomUUID()
  acquired = redis.set("lock:"+K, token, NX=true, PX=lockTtlMs)  # atomic acquire
  if acquired:
      try:
          v = origin.load(K)
          redis.set(K, serialize(v), PX=ttlMs)
          return v
      finally:
          # release ONLY if we still own it (atomic compare-and-delete via Lua)
          redis.eval(RELEASE_LUA, keys=["lock:"+K], args=[token])
  else:
      # someone else holds the lock — wait & poll, or serve stale, or fail fast
      return waitForFillOrStale(K)
```

`SET key value NX PX ms` is atomic: **NX** = set only if not exists (acquire), **PX ms** = auto-expire the lock so a crashed holder doesn't deadlock the key forever. The random **token** + Lua release ensures you only delete *your* lock (else a slow holder whose lock already expired could delete a *new* holder's lock):

```lua
-- RELEASE_LUA: atomic check-and-delete
if redis.call("get", KEYS[1]) == ARGV[1] then
  return redis.call("del", KEYS[1])
else
  return 0
end
```

**The follower's dilemma** (the `else` branch) is the hard part:
- **Poll-and-wait:** sleep a few ms, re-check the cache, loop with a timeout. Simple but adds latency and can itself become a mini-herd if poll intervals align (add jitter to the poll!).
- **Serve stale:** if you kept the old value (logically expired but physically present), return it immediately — this is SWR (§3.6) and is usually the best follower behavior.
- **Fail fast / fallback:** return a default or error rather than wait (good for non-critical data).

**Distributed-lock correctness caveats (very important):** a single Redis lock is *not* a perfect mutex under partitions/GC pauses. A holder can pause (GC, VM stall) past the lock TTL; Redis expires the lock; another instance acquires it; now *two* think they hold it. For *cache fill* this is usually tolerable (worst case: 2 origin calls + a last-writer-wins put), which is why simple `SET NX` is the standard, pragmatic choice here. For *correctness-critical* mutual exclusion you'd need **fencing tokens** (monotonic tokens checked by the resource) or a consensus-backed lock — see the **Redlock** debate in §7.5.

### 3.4 Probabilistic early expiration (XFetch / "optimal" early recompute) — internals

This is the most elegant mitigation and the least known. It comes from the 2015 paper *"Optimal Probabilistic Cache Stampede Prevention"* (Vattani, Chierichetti, Lowenstein).

**Idea:** Don't wait for the TTL to hit zero and then have everyone miss. Instead, *each reader*, on every read, runs a small random gamble: "should *I* proactively refresh this now, even though it's still technically valid?" The probability of saying yes is ~0 long before expiry and rises sharply as expiry nears. Tuned correctly, statistically *one* reader refreshes slightly early, while the value is still present, so **the cache is never empty** and **no herd ever forms**.

**Stored alongside the value:** the time the last recompute *took* (`delta`) and the absolute `expiry` time.

**The decision rule each reader evaluates:**

```
shouldRecomputeEarly =  now - delta * beta * ln(random())  >=  expiry
```

Where:
- `now` = current time.
- `delta` = how long the last recomputation took (e.g., 40 ms). Costlier recomputes → refresh earlier (more headroom).
- `beta` = a tunable constant ≥ 1 (default **1.0**); larger β shifts refreshes earlier (more eager), smaller β later (riskier).
- `random()` = uniform random in (0,1]; `ln(random())` is a negative number, so `-delta*beta*ln(random())` is a positive "early offset" with an exponential distribution.

**Why it works:** as `now` approaches `expiry`, even a small random offset pushes the left side past `expiry`, so the *probability* a given reader triggers a refresh climbs smoothly toward 1. Crucially it's a *gamble per reader*, so among many concurrent readers, the expected number that trigger near any instant is ≈1 — no coordination, no lock, no central state, and it works across instances because the gamble is local and the value is still served from the shared cache while one instance refreshes.

**Control flow:**

```
xfetchRead(K):
  (value, delta, expiry) = cache.getWithMeta(K)
  if value == null:                       # true miss (cold) — fall back to lock/single-flight
      return loadAndStoreWithMeta(K)
  if now() - delta*beta*ln(rand()) >= expiry:
      # this reader volunteers to refresh early (value still valid, still served)
      asyncOrInlineRefresh(K)             # ideally async + single-flight guarded
  return value                            # everyone keeps getting a valid value
```

**Properties:**
- **No miss window** in steady state → no herd from TTL.
- **No locks**, no extra round-trips in the common path.
- Tuning `beta` trades freshness eagerness vs. number of early recomputes.
- Still pair it with single-flight to guard the rare case where two readers gamble "yes" at once, and with a true-miss path for cold start.

### 3.5 TTL jitter / staggered expiry — internals

**Problem it targets:** the *correlated* expiry of *many keys written together*. E.g., a deploy warms 10,000 keys with `TTL=300s` at the same instant → all 10,000 expire within the same millisecond 5 minutes later → simultaneous multi-key stampede. Single-flight helps per key but you still get 10,000 origin calls in a thin window.

**Fix:** randomize each key's TTL: `effectiveTTL = baseTTL ± random(0, jitter)`. Now the 10,000 expiries spread over a window, smoothing origin load.

```
put(K, v):
  jitter = random(-0.1*baseTTL, +0.1*baseTTL)   # ±10% spread
  cache.put(K, v, baseTTL + jitter)
```

**Control/data flow:** purely at *write* time; no read-path change. Choosing the jitter magnitude is the knob: too little and clusters survive; too much and some keys live noticeably longer/shorter than intended. A common default is **±10–25%** of the base TTL, or an additive uniform like `[0, 60s]` on top of a multi-minute base.

**Relation to backoff jitter:** identical mathematics. Both spread a synchronized population over time to break correlated bursts. The retry-jitter literature (AWS "full jitter": `sleep = random(0, min(cap, base*2^attempt))`) is the same de-synchronization principle applied to retries rather than expiries.

### 3.6 Stale-while-revalidate (SWR) — internals

**Idea:** separate "logically fresh" from "physically present." Keep two notions of age:
- **soft TTL** (freshness deadline) — after this, the value is *stale but still usable*.
- **hard TTL** (eviction deadline) — after this, the value is truly gone.

While `soft < now < hard`, you **serve the stale value immediately** and kick off a **single** background refresh. Readers never block on the origin; only the background refresher touches it (guarded by single-flight so only one refresh runs).

```
swrRead(K):
  (value, softExpiry, hardExpiry) = cache.getWithMeta(K)
  if value == null or now > hardExpiry:    # truly gone -> must block-load (cold path)
      return blockingSingleFlightLoad(K)
  if now > softExpiry:                      # stale but usable
      triggerBackgroundRefreshOnce(K)       # single-flight guarded, non-blocking
  return value                              # return stale immediately
```

**State machine of a value under SWR:**

```
[FRESH]── soft TTL passes ──▶[STALE]── hard TTL passes ──▶[GONE]
   │ serve fresh                │ serve stale + refresh        │ block & load
   └────────── refresh writes new value ────────────────────◀─┘
```

**Why it's powerful:** it removes user-facing latency from refresh entirely (readers always hit cache) *and* removes the herd (one background refresh). The cost is **staleness**: readers may see data up to `(hard - soft)` old. This is the standard HTTP cache mechanism — `Cache-Control: max-age=60, stale-while-revalidate=120` (RFC 5861) means "fresh 60s, then serve stale up to 120s more while revalidating." Caffeine's `refreshAfterWrite` is the in-JVM equivalent (serves old value, refreshes asynchronously).

### 3.7 Negative caching — internals

When the origin returns "no such row" or an error, an *unprotected* miss path will hit the origin on *every* request for that key (because nothing gets cached). A malicious or accidental flood of requests for nonexistent keys becomes a stampede that single-flight alone won't fully solve (each distinct missing key bypasses caching). **Negative caching** stores a sentinel ("MISS" / null marker) with a *short* TTL so repeated lookups for the same absent key are served from cache.

```
load(K):
  v = origin.load(K)
  if v == null:
      cache.put(K, NULL_SENTINEL, shortNegativeTTL)   # e.g. 5–30s
  else:
      cache.put(K, v, normalTTL)
```

Pair with a **Bloom filter** (a compact probabilistic set membership structure with no false negatives) to reject definitely-absent keys before they ever reach the cache/origin — a standard defense against *cache penetration* (attacker requests random nonexistent keys).

> **Cache penetration** = requests for keys that exist in neither cache nor origin, bypassing the cache to hammer the DB. **Cache breakdown** (Chinese-literature term 缓存击穿) = a *single hot key* expiring and stampeding — exactly cache stampede. **Cache avalanche** (缓存雪崩) = *many* keys expiring together (the jitter problem). Worth knowing because a lot of practical writing uses these three terms.

### 3.8 How the pieces compose (the production read path)

A hardened cache read typically layers several of these:

```
read(K):
  (value, softExp, hardExp, delta) = cache.getWithMeta(K)
  if value != null:
      if now > softExp or xfetchSaysRefresh(now, delta, hardExp):
          if tryAcquireSingleFlight(K):       # only one refresher per key, per instance
              submitAsyncRefresh(K)           # SWR: don't block
      return value                            # always return a usable value when present
  # cold / truly-gone path:
  if tryAcquireDistributedLock(K):
      try { v = origin.load(K); putWithJitterTTL(K, v); return v }
      finally { releaseDistributedLock(K) }
  else:
      return waitBrieflyThenReadOrFallback(K) # poll w/ jitter, or default
```

This single function embodies: **SWR + XFetch + single-flight + distributed lock (cold only) + TTL jitter + a fallback**. That is what "production-grade stampede protection" looks like.

---

## 4. The complete toolkit

### 4.1 Java in-process caches (Caffeine / Guava)

| API / option | Library | Purpose | Key params / defaults |
|---|---|---|---|
| `Caffeine.newBuilder().build(loader)` | Caffeine | Build a `LoadingCache` that loads on miss **with built-in per-key coalescing** | Loader runs once per key even under concurrent misses (single-flight built in). |
| `expireAfterWrite(d)` | Caffeine/Guava | Absolute TTL after write | No default; you set it. Aligns expiry → use with jitter. |
| `expireAfterAccess(d)` | Caffeine/Guava | Idle TTL after last access | — |
| `refreshAfterWrite(d)` | Caffeine | **SWR**: after `d`, next access returns *old* value and triggers async reload | Async refresh uses `ForkJoinPool.commonPool()` unless `executor(...)` set. Refresh is single-flight per key. |
| `AsyncLoadingCache` / `buildAsync` | Caffeine | Store `CompletableFuture<V>`; concurrent misses share the same future = coalescing | — |
| `asyncReloading(loader)` | Caffeine | Make a sync loader refresh asynchronously | — |
| `maximumSize(n)` / `maximumWeight` | Caffeine/Guava | Bound size (eviction can also trigger herds on hot keys) | — |
| `Striped<Lock>` / `Striped<Semaphore>` | Guava | Bounded per-key locking (stripes) | `Striped.lock(stripes)`; choose stripes ≈ 4× cores. |
| `CacheLoader.load` vs `loadAll` | Guava/Caffeine | Single vs batch load | `getAll` coalesces and batches missing keys. |

> A **`LoadingCache`** is a cache that knows how to compute missing values itself via a supplied loader; Caffeine guarantees the loader is invoked at most once per key for concurrent misses — i.e., **single-flight is the default**, which is why "just use Caffeine" solves in-process stampede for free.

### 4.2 Java concurrency primitives (build-your-own coalescing)

| Primitive | Purpose | Notes |
|---|---|---|
| `ConcurrentHashMap.computeIfAbsent` | Atomic "compute once per key" | Holds a per-bin lock; the mapping function runs once. Don't do blocking I/O of *other* keys inside it (can deadlock on resize in old JDKs; fine in modern JDK for single key). |
| `CompletableFuture` | Share an in-flight result among callers | Store `CHM<K, CompletableFuture<V>>`; remove in `whenComplete`. |
| `ReentrantLock` / `tryLock(timeout)` | Per-key mutex with timeout | `tryLock` lets followers bail to stale instead of blocking forever. |
| `Semaphore(permits)` | Limit concurrency to the origin (a bulkhead) | Cap simultaneous origin calls globally even across keys. |
| `Phaser` / `CountDownLatch` | Coordinate a known set of waiters | Rarely needed vs. futures. |

### 4.3 Redis primitives & commands

| Command / feature | Purpose | Key params / defaults |
|---|---|---|
| `SET k v NX PX ms` | Atomic lock acquire with auto-expiry | NX=only-if-absent; PX=ms TTL. **Always set PX** to avoid deadlock. |
| `SET k v XX` | Set only if exists (conditional refresh) | — |
| `EVAL <lua> ...` | Atomic multi-step logic (check-and-del release, get-or-set) | Lua runs atomically (Redis single-threaded). |
| `GETEX k PX ms` | Get and reset TTL atomically (touch on read) | Redis ≥ 6.2. Useful for SWR sliding. |
| `TTL k` / `PTTL k` | Remaining TTL (seconds/ms) | Read to drive XFetch/SWR decisions. |
| `HSET` (value + meta) | Store value alongside `delta`/`expiry`/`soft` in one hash | Enables XFetch metadata. |
| `EXPIRE k s NX/XX/GT/LT` | Conditional TTL update | NX/XX/GT/LT flags Redis ≥ 7.0. |
| Pub/Sub / keyspace notifications | React to expiry events | `notify-keyspace-events Ex` emits `expired` events (note: fired on access/lazy-expire, not exactly at TTL). |
| **Lua scripting** | Server-side single-flight-ish atomic get-or-flag | Avoids round-trip races. |
| **Redisson** `RLock`, `RMapCache`, `RLocalCachedMap` | Java Redis client with distributed locks, near-cache, `tryLock(wait, lease)` | `RLock` implements lock+lease+watchdog auto-renew; `RMapCache` supports per-entry TTL; `RLocalCachedMap` = near-cache with invalidation. |
| Redlock (multi-node) | Distributed lock across N independent Redis masters | Contested correctness (see §7.5). |

### 4.4 HTTP / CDN cache controls

| Header / directive | Purpose | Notes |
|---|---|---|
| `Cache-Control: max-age=N` | Fresh lifetime in seconds | — |
| `stale-while-revalidate=N` (RFC 5861) | Serve stale up to N s while revalidating | Supported by browsers & many CDNs. |
| `stale-if-error=N` (RFC 5861) | Serve stale up to N s if origin errors | Resilience bonus. |
| ETag / `If-None-Match`, `Last-Modified`/`If-Modified-Since` | Conditional revalidation (304) | Cheap revalidate without full transfer. |
| CDN "request collapsing" / "coalescing" | Edge dedupes concurrent misses to one origin fetch | Cloudflare "Concurrent Streaming Acceleration"/Tiered Cache; Fastly "request collapsing" (on by default); Varnish "request coalescing." |
| Varnish `grace` mode | SWR for Varnish: serve graced (stale) object while fetching | `beresp.grace` / `obj.grace`. |
| Nginx `proxy_cache_lock on;` | Only one request populates a cache entry; others wait | `proxy_cache_lock_timeout` (default 5s); `proxy_cache_use_stale updating;` enables SWR. |

> A **CDN (Content Delivery Network)** caches and serves content from edge nodes near users. **Request collapsing/coalescing** at the edge is single-flight implemented by the CDN: many simultaneous misses for the same URL become one origin fetch. This is one of the most effective stampede defenses for HTTP traffic and is often on by default (Fastly/Varnish), opt-in elsewhere.

### 4.5 OS / kernel-level controls (the original thundering herd)

| Mechanism | Purpose | Notes |
|---|---|---|
| `EPOLLEXCLUSIVE` (Linux ≥ 4.5) | Wake only ONE epoll waiter per event | Fixes accept herd among threads sharing one epoll. |
| `SO_REUSEPORT` (Linux ≥ 3.9) | Each thread has its own listen socket; kernel load-balances | Eliminates accept herd; used by Nginx, Envoy. |
| `accept()` serialization (`accept_mutex` in Nginx) | Only one worker accepts at a time | Older alternative to `SO_REUSEPORT`. |
| Futex `FUTEX_WAKE` count | Wake N waiters not all | Library-level (glibc) thundering-herd avoidance for cond vars. |

### 4.6 Backoff / jitter helpers

| Tool | Purpose | Notes |
|---|---|---|
| Resilience4j `RetryConfig` w/ `IntervalFunction.ofExponentialRandomBackoff` | Jittered exponential retry | `randomizationFactor` default 0.5. |
| Spring Retry `@Retryable(backoff=@Backoff(multiplier, random=true))` | Jittered retry in Spring | — |
| AWS SDK adaptive/standard retry mode | Built-in full-jitter backoff | — |
| Failsafe `RetryPolicy.withJitter(...)` | Add jitter to delays | — |

---

## 5. Code examples by use case

All Java examples target JDK 17+. Comments mark the non-obvious lines.

### 5.1 In-process single-flight with Caffeine (the easy win)

```java
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;

public class ProductPrices {

    // LoadingCache: the loader is invoked at most ONCE per key under concurrent
    // misses -> single-flight is built in. This alone kills in-JVM stampede.
    private final LoadingCache<Long, Price> cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(60))        // hard TTL
            .refreshAfterWrite(Duration.ofSeconds(45))       // SWR: at 45s, serve old + async refresh
            .recordStats()                                   // hit/miss/load metrics
            .build(this::loadFromDb);                        // the CacheLoader

    private Price loadFromDb(Long productId) {
        // expensive origin call; runs once per key even if 1000 threads miss together
        return Database.queryPrice(productId);
    }

    public Price get(long productId) {
        return cache.get(productId);   // hit -> instant; miss -> coalesced single load
    }
}
```

Why it matters: `refreshAfterWrite(45) < expireAfterWrite(60)` gives you a 15s SWR window — between 45s and 60s, reads return the *old* price instantly and trigger a single async reload, so the value is refreshed *before* it can expire and stampede.

### 5.2 Hand-rolled single-flight with `CompletableFuture` (when you need control)

```java
import java.util.concurrent.*;
import java.util.function.Function;

public class SingleFlight<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public V get(K key, Function<K, V> loader) {
        // computeIfAbsent atomically elects ONE creator of the future per key
        CompletableFuture<V> f = inFlight.computeIfAbsent(key, k -> {
            CompletableFuture<V> cf = new CompletableFuture<>();
            // run the real load off-thread so we don't hold the CHM bin lock during I/O
            CompletableFuture.supplyAsync(() -> loader.apply(k))
                .whenComplete((v, ex) -> {
                    if (ex != null) cf.completeExceptionally(ex);
                    else cf.complete(v);
                    inFlight.remove(k, cf);   // value-checked remove: don't drop a newer future
                });
            return cf;
        });
        return f.join();   // all concurrent callers for `key` block here on the SAME future
    }
}
```

Use when you want explicit failure handling, custom executors, metrics per join, or to coalesce something that isn't a plain cache (e.g., an expensive RPC).

### 5.3 Distributed single-flight via Redis lock + SWR fallback (cross-instance)

```java
import io.lettuce.core.api.sync.RedisCommands;
import java.util.UUID;

public class RedisStampedeGuard {
    private final RedisCommands<String, String> redis;
    private final long valueTtlMs = 60_000;
    private final long lockTtlMs  = 5_000;

    private static final String RELEASE_LUA =
        "if redis.call('get', KEYS[1]) == ARGV[1] " +   // own the lock?
        "then return redis.call('del', KEYS[1]) else return 0 end";

    public String get(String key) {
        String cached = redis.get(key);
        if (cached != null && !isStale(cached)) return value(cached);  // fresh hit

        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        // SET NX PX: atomic acquire with auto-expiry so a crash can't deadlock the key
        boolean iAmLeader = "OK".equals(
            redis.set(lockKey, token, io.lettuce.core.SetArgs.Builder.nx().px(lockTtlMs)));

        if (iAmLeader) {
            try {
                String fresh = serialize(origin.load(key));            // the ONE real call
                redis.psetex(key, valueTtlMs, fresh);
                return value(fresh);
            } finally {
                redis.eval(RELEASE_LUA, io.lettuce.core.ScriptOutputType.INTEGER,
                           new String[]{lockKey}, token);              // safe release
            }
        } else {
            // FOLLOWER: prefer stale-while-revalidate over blocking the origin
            if (cached != null) return value(cached);                  // serve stale (SWR)
            return waitForFill(key, 200 /*ms budget*/);                // else brief jittered poll
        }
    }

    private String waitForFill(String key, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            String v = redis.get(key);
            if (v != null) return value(v);
            sleepJittered(10, 30);   // JITTER the poll so followers don't form a sub-herd
        }
        return value(serialize(origin.loadFallbackOrDefault(key)));    // last resort
    }
    // serialize/value/isStale/sleepJittered/origin elided for brevity
}
```

Key decisions: leader does the single origin call; followers serve stale if they have it (SWR), else poll *with jitter*, else fall back. The lock TTL (5s) must exceed the worst-case origin latency, but be short enough that a crash doesn't strand the key — tune to p99.9 of `origin.load`.

### 5.4 Probabilistic early expiration (XFetch) over Redis

```java
import java.util.concurrent.ThreadLocalRandom;

public class XFetchCache {
    private final RedisCommands<String, String> redis;
    private final double beta = 1.0;     // >1 = refresh earlier; <1 = later/riskier
    private final long ttlMs = 60_000;

    // Stores value + delta(ms) + absolute expiry(ms) in a Redis hash.
    public String get(String key, java.util.function.Supplier<String> loader) {
        var h = redis.hgetall(key);
        long now = System.currentTimeMillis();
        if (!h.isEmpty()) {
            long delta  = Long.parseLong(h.get("delta"));
            long expiry = Long.parseLong(h.get("expiry"));
            // The gamble: -delta*beta*ln(U(0,1]) is an exponentially-distributed early offset.
            double early = -delta * beta * Math.log(ThreadLocalRandom.current().nextDouble());
            if (now + early < expiry) {
                return h.get("value");                 // still fresh enough; just serve it
            }
            // else: THIS reader volunteers to recompute early (value is still valid & served)
        }
        return recompute(key, loader);                 // guard with single-flight in practice
    }

    private String recompute(String key, java.util.function.Supplier<String> loader) {
        long t0 = System.currentTimeMillis();
        String value = loader.get();
        long delta = System.currentTimeMillis() - t0;  // remember how costly this was
        long expiry = System.currentTimeMillis() + ttlMs;
        redis.hset(key, java.util.Map.of(
            "value", value, "delta", String.valueOf(delta), "expiry", String.valueOf(expiry)));
        redis.pexpire(key, ttlMs + 5_000);             // physical TTL slightly > logical expiry
        return value;
    }
}
```

This is the textbook XFetch. In production, wrap `recompute` in a per-key single-flight (local or Redis lock) so the rare double-volunteer doesn't double-hit the origin.

### 5.5 TTL jitter on write (kill cache avalanche)

```java
import java.util.concurrent.ThreadLocalRandom;

public final class JitteredTtl {
    /** Spread a batch of same-time writes so they don't all expire together. */
    public static long ttlMsWithJitter(long baseMs, double fraction /* e.g. 0.20 = ±20% */) {
        long span = (long) (baseMs * fraction);
        long jitter = ThreadLocalRandom.current().nextLong(-span, span + 1);
        return Math.max(1, baseMs + jitter);
    }
}

// usage on every cache put:
redis.psetex(key, JitteredTtl.ttlMsWithJitter(300_000, 0.20), value);  // 300s ±20%
```

Apply this *everywhere you set a TTL*, especially in bulk warm-ups and write-through paths. It's a one-line change that prevents the most common multi-key avalanche.

### 5.6 Jittered exponential backoff for synchronized retries (Resilience4j)

```java
import io.github.resilience4j.retry.*;
import java.time.Duration;

RetryConfig config = RetryConfig.custom()
    .maxAttempts(5)
    // Exponential backoff WITH randomization so failed clients don't retry in lockstep.
    .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
        Duration.ofMillis(100),   // initial interval
        2.0,                      // multiplier (100, 200, 400, ...)
        0.5))                     // randomizationFactor: each delay = base * (1 ± 0.5)
    .retryOnException(e -> e instanceof java.io.IOException)
    .build();

Retry retry = Retry.of("origin", config);
Supplier<Price> guarded = Retry.decorateSupplier(retry, () -> origin.loadPrice(42));
Price p = guarded.get();
```

Without jitter, a downstream blip makes thousands of clients fail at t0 and retry at exactly t0+100ms, t0+300ms, ... — a self-inflicted herd on the recovering service. Randomization spreads them out. Equivalent to AWS "full jitter."

### 5.7 Negative caching + Bloom filter (stop penetration herds)

```java
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import java.nio.charset.StandardCharsets;

public class PenetrationGuard {
    // Bloom filter of all KNOWN keys: no false negatives -> "definitely absent" is reliable.
    private final BloomFilter<String> known =
        BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 10_000_000, 0.01);

    private final Cache<String, Object> cache;  // your distributed/local cache
    private static final Object NULL = new Object();   // sentinel for "known absent"

    public Optional<User> get(String id) {
        if (!known.mightContain(id)) return Optional.empty();  // reject impossible key cheaply

        Object v = cache.getIfPresent(id);
        if (v == NULL) return Optional.empty();                // negative cache hit
        if (v != null) return Optional.of((User) v);

        User u = db.find(id);                                  // origin
        if (u == null) {
            cache.put(id, NULL /* short TTL */);               // cache the absence briefly
            return Optional.empty();
        }
        cache.put(id, u);
        return Optional.of(u);
    }
}
```

The Bloom filter rejects keys that cannot exist before they touch the origin; the NULL sentinel (with a *short* TTL) stops repeated lookups of a genuinely-missing-but-possible key from hammering the DB.

### 5.8 Nginx edge coalescing + SWR (no app code)

```nginx
proxy_cache_path /var/cache/nginx keys_zone=app:100m inactive=10m;

location / {
    proxy_cache app;
    proxy_cache_valid 200 60s;

    proxy_cache_lock on;                 # only ONE request fills a given entry...
    proxy_cache_lock_timeout 5s;         # ...others wait up to 5s, then go to origin

    # SWR: serve stale while updating, and on error/timeout (resilience)
    proxy_cache_use_stale updating error timeout http_500 http_502 http_503;
    proxy_cache_background_update on;    # refresh in the background, return stale now

    proxy_pass http://backend;
}
```

`proxy_cache_lock on` is single-flight at the edge; `proxy_cache_use_stale updating` + `background_update on` is stale-while-revalidate. Combined, a hot URL is protected with zero application changes.

### 5.9 Cold-start warm-up with staggered priming

```java
// On deploy, warm the top-N hot keys but STAGGER both the priming and the TTLs
// so you don't create an avalanche 5 minutes later.
List<Long> hotKeys = topHotKeys(1000);
ScheduledExecutorService pool = Executors.newScheduledThreadPool(8);
for (int i = 0; i < hotKeys.size(); i++) {
    long key = hotKeys.get(i);
    long delayMs = (long) (i * 5);                     // spread priming over ~5s
    pool.schedule(() -> {
        Price p = origin.loadPrice(key);
        long ttl = JitteredTtl.ttlMsWithJitter(300_000, 0.25);  // ±25% spread expiry too
        cache.put(key, p, ttl);
    }, delayMs, TimeUnit.MILLISECONDS);
}
```

Two anti-avalanche moves at once: spread the *priming* (don't slam the DB during warm-up) and spread the *expiry* (don't create a synchronized future stampede).

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Single-flight is nearly free on the hit path**; its cost appears only during misses (one CHM lookup + future join). Prefer future-based coalescing over lock-based blocking when N is large — blocking N threads ties up N carrier threads; awaiting a shared future is cheaper, especially with virtual threads (JDK 21+) or reactive stacks.
- **Async refresh executor:** Caffeine's default async refresh uses `ForkJoinPool.commonPool()`. Under heavy refresh load this pool can be starved by your own application's parallel streams. Provide a dedicated `executor(...)`.
- **XFetch's `ln(random())`** is cheap, but the extra metadata read (delta/expiry) costs a round-trip if stored separately in Redis — store value+meta in one hash (`HGETALL`) to keep it to one call.
- **Lock granularity:** per-key locks have unbounded memory (one lock object per hot key). Striped locks bound memory but cause false contention. Pick stripes ≈ 4–8× cores; monitor stripe contention.

### 6.2 Correctness & concurrency

- **Always double-check the cache after acquiring a lock** — the previous holder probably filled it.
- **Value-checked removal** of in-flight entries (`remove(k, future)`) to avoid clobbering a newer load.
- **Distributed locks are advisory and imperfect.** For *cache fill*, the worst case (two leaders) is a duplicated origin call + last-writer-wins — acceptable. Do **not** use a plain Redis lock to guard a non-idempotent side effect (money movement) without **fencing tokens**.
- **Lease/lock TTL must exceed worst-case origin latency** (use p99.9), or the lock expires mid-load and a second leader starts. Redisson's **watchdog** auto-renews a held lock to avoid this; if you hand-roll, either set a generous PX or renew.
- **Idempotency of the loader:** if a herd does leak through, the loader running multiple times must be safe.

### 6.3 Memory

- Per-key locks/in-flight maps grow with the number of *distinct* hot keys; ensure they're cleaned up (`finally remove`) or bounded (stripes).
- Negative caching consumes cache space for absent keys; keep negative TTLs short to bound it and resist a penetration attack from filling the cache with garbage sentinels (cap with a separate small cache or Bloom filter).

### 6.4 Security

- **Cache penetration as an attack vector:** an attacker requests random nonexistent keys to bypass cache and exhaust the DB (a deliberate stampede). Defend with Bloom filters + negative caching + per-IP rate limits.
- **Cache poisoning:** ensure the key namespace can't be influenced to collide (e.g., user-controlled cache keys that overwrite shared entries). Single-flight on a poisoned key would *amplify* a bad value to all waiters.
- **Lock token must be unguessable** (UUID/secure random) so a tenant can't release another's lock.

### 6.5 Observability (you cannot tune what you cannot see)

Instrument and alert on:

- **Cache hit/miss ratio** and especially **miss *rate* (misses/sec)** — a stampede shows as a sudden miss-rate spike.
- **Origin QPS for hot keys** vs. expected (should be ~1/refresh, not N). A jump from 1 to hundreds = stampede.
- **Single-flight join count / coalesce ratio** (callers served per actual load) — high is good.
- **Lock acquisition failures / wait time** for distributed locks.
- **Refresh latency & refresh failures** (SWR/XFetch background tasks).
- **Stale-serve count** (how often you served stale) — informs your soft/hard TTL gap.
- **p99/p999 latency at TTL boundaries** — sawtooth latency = miss windows leaking.

Caffeine exposes `cache.stats()` (`hitRate`, `loadCount`, `averageLoadPenalty`, `loadFailureCount`). Wire to Micrometer/Prometheus.

### 6.6 Cost

- Each stampede that reaches a managed DB (RDS/Aurora/Cloud Spanner) can spike CPU/IOPS and trigger autoscaling or throttling — a direct dollar cost. Coalescing cuts origin calls by orders of magnitude → measurable savings.
- SWR/XFetch refreshes proactively (slightly more total origin calls in steady state than lazy expiry) but eliminates the catastrophic spike — trade a little steady cost for no tail catastrophe.

### 6.7 Testing

- **Concurrency test:** fire 1,000 threads at a cold key behind your guard; assert the loader/origin was invoked exactly once (use a `LongAdder` counter inside a fake loader). This is the single most valuable test.
- **Expiry race test:** with a tiny TTL, hammer the key and assert origin call count stays ≈ refresh count, not request count.
- **Chaos:** kill the lock holder mid-load and assert the lock TTL releases and a follower recovers.
- **Jitter test:** write 10k keys, assert their expiry timestamps are spread (std-dev > 0), not clustered.
- **Deterministic time:** inject a `Clock`/`Ticker` (Caffeine supports a test `Ticker`) so you can advance time and test SWR/XFetch transitions without sleeping.

### 6.8 Production hardening checklist

- [ ] Single-flight (in-process) on every expensive loader — or just use a `LoadingCache`.
- [ ] Distributed lock or edge coalescing for cross-instance hot keys.
- [ ] TTL jitter on **every** TTL set.
- [ ] SWR (soft/hard TTL or `refreshAfterWrite`) for hot, tolerably-stale data.
- [ ] XFetch for the hottest keys where any miss window is unacceptable.
- [ ] Negative caching + Bloom filter for penetration-prone endpoints.
- [ ] Jittered exponential backoff on all retries to the origin.
- [ ] Lock TTL > p99.9 origin latency; tokenized safe release.
- [ ] Fallback (stale/default/shed) for followers — never block unbounded.
- [ ] Dashboards on miss-rate, origin-QPS-per-key, coalesce ratio.
- [ ] Load test the TTL boundary explicitly.

### 6.9 Anti-patterns (avoid)

| Anti-pattern | Why it bites |
|---|---|
| Same fixed TTL for a bulk-warmed key set | Synchronized future avalanche. |
| Followers busy-poll with no jitter | Followers form a secondary herd. |
| No lock TTL on a distributed lock | One crash deadlocks the hot key forever. |
| Releasing a lock without a token check | You delete someone else's lock; concurrency bug. |
| Caching only positive results | Penetration / repeated-miss herd. |
| Lazy expiry on a single mega-hot key | Guaranteed stampede every TTL. Use SWR/XFetch. |
| Retrying without jitter | Retry storm = self-inflicted herd. |
| Coalescing a poisoned/expensive-but-wrong value | Amplifies the bad result to all waiters. |
| Holding a CHM bin lock during I/O inside `computeIfAbsent` | Throughput collapse / potential deadlock. |

---

## 7. Advanced topics & deep internals

### 7.1 The XFetch math, derived

Recall the rule: refresh early when `now − delta·β·ln(rand) ≥ expiry`. Rearranged, a reader refreshes when the random `−ln(U)` (an Exponential(1) variate) exceeds `(expiry − now) / (delta·β)`. As `now → expiry`, the threshold `(expiry − now)/(delta·β) → 0`, and `P(Exp(1) ≥ 0) = 1`, so the refresh probability → 1 *before* expiry. Earlier (when `expiry − now` is several multiples of `delta·β`), the probability is exponentially small, so almost nobody refreshes prematurely. The paper proves this minimizes the expected number of recomputations subject to never being caught with an empty cache — hence "optimal." `β` scales the headroom: higher β → refresh starts earlier (more total refreshes, safer); β slightly below 1 saves refreshes but risks the occasional true miss. `delta` adapting to actual recompute cost is what makes it self-tuning: a key that suddenly gets slower to compute automatically gets refreshed earlier next time.

### 7.2 Single-flight scope: process vs. cluster vs. edge

- **Process-local single-flight** (Caffeine / your CHM) → caps origin calls at **M** (instances).
- **Cluster-wide** requires a shared coordination point: distributed lock (Redis/ZooKeeper) or a **near-cache with leader fill**. Redisson `RLocalCachedMap` gives a near-cache (local copy + invalidation messages) that drastically cuts both stampede and read latency.
- **Edge coalescing** (CDN/Varnish/Nginx) collapses to **1** origin call for HTTP, often with no app changes — frequently the highest-ROI fix.
- **Tiered caching** (CDN shield/origin-shield tier) funnels all edge misses through one mid-tier so the origin sees one request — single-flight at internet scale.

### 7.3 Kernel thundering herd (the original)

- **Accept herd:** pre-fork servers with many workers blocked on `accept()`/`epoll_wait()` on a shared listen socket. Old kernels woke all workers per connection (the herd). Fixes: glibc/kernel now wake one for `accept()`; for epoll, use `EPOLLEXCLUSIVE` (Linux ≥ 4.5) to wake a single waiter, or `SO_REUSEPORT` (Linux ≥ 3.9) to give each worker its own socket and let the kernel hash-balance connections. Nginx historically used an `accept_mutex` to serialize; modern Nginx/Envoy prefer `SO_REUSEPORT`.
- **Condition variable herd:** `pthread_cond_broadcast`/`notifyAll` wakes all waiters when typically one can proceed; prefer `signal`/`notify` (wake one) plus a correctly-rechecked predicate. Java's `Condition.signal()` vs `signalAll()` is the same choice. `notifyAll` is safe but can thunder; use it only when multiple waiters genuinely can proceed.

### 7.4 Caffeine internals relevant to stampede

- **W-TinyLFU** admission keeps the *right* hot keys resident, indirectly reducing eviction-induced misses on hot keys (an eviction of a hot key is a stampede trigger just like TTL).
- `refreshAfterWrite` returns the *old* value to the caller that triggers the refresh and reloads asynchronously; only **one** refresh per key runs at a time — built-in SWR + single-flight. But note: `refreshAfterWrite` only triggers **on access**; a key with no traffic won't auto-refresh (it'll eventually hit `expireAfterWrite` and the next access cold-loads). For truly always-warm keys, add a scheduler.
- `AsyncLoadingCache` stores futures, so concurrent misses naturally share one in-flight future (coalescing) and the cache never holds a "computing" gap.

### 7.5 Distributed locking correctness: the Redlock debate

**Redlock** is Redis's proposed algorithm for locking across N independent masters (acquire on a majority within a time bound). Martin Kleppmann's well-known critique argues it's unsafe for *correctness-critical* mutual exclusion because clock drift, GC/VM pauses, and network delays can let two clients believe they hold the lock; the robust fix is **fencing tokens** — a monotonically increasing number issued with each lock grant that the protected resource checks and rejects if stale. Salvatore Sanfilippo (Redis author) rebutted parts of it. **Practical takeaway for *this* topic:** for cache-fill, you do not need perfect mutual exclusion — a rare double-fill is harmless (idempotent put, last write wins). So the simple single-node `SET NX PX` is the right, pragmatic tool here; reserve Redlock/ZooKeeper/fencing for guarding genuinely non-idempotent operations.

### 7.6 Metastable failures and the herd

A stampede can tip a system into a **metastable failure**: a state where load amplification sustains itself even after the original trigger is gone (the cache stays empty because the overloaded DB can't refill it fast enough, and the empty cache keeps the DB overloaded). Escaping requires breaking the loop: shed load, serve stale/degraded, or temporarily *increase* TTL ("freeze" the cache) so the DB can recover. Designing for SWR/XFetch *prevents* entering metastability because the cache is never empty.

### 7.7 Lesser-known behaviors & gotchas

- **Redis keyspace `expired` events fire lazily**, on access or during the background expiry cycle — not exactly at the TTL instant — so you cannot rely on them for precise pre-expiry refresh; use XFetch/SWR instead.
- **`expireAfterWrite` + bulk warm = avalanche** unless jittered, even with per-key single-flight (single-flight dedupes *per key*, not *across keys*).
- **`computeIfAbsent` re-entrancy:** calling `computeIfAbsent` for the *same* map inside the mapping function can throw/deadlock; never nest cache loads for the same cache key.
- **Virtual threads (JDK 21+)** make blocking lock-based coalescing far cheaper (a blocked virtual thread doesn't pin a carrier), narrowing the historical advantage of future-based coalescing — but futures still beat locks for fan-out to many waiters.
- **CDN request collapsing has limits:** very long origin fetches or streaming responses may not collapse perfectly; check vendor docs (Fastly collapses by default; Cloudflare requires specific settings/tiers).
- **GETEX/EXPIRE conditional flags** (`GT`/`LT`/`NX`/`XX`) let you implement sliding TTL or "extend only if longer" semantics atomically — useful for SWR lease extension.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Mitigation comparison

| Mitigation | Origin calls during event | Reader latency added | Staleness | Cross-instance? | Complexity | Best for |
|---|---|---|---|---|---|---|
| **Single-flight (local)** | ~M (1/instance) | One waits ~load time; rest share | None | No (per JVM) | Low | In-JVM hot keys; default via Caffeine |
| **Distributed lock** | ~1 | Followers wait/poll | None (or stale if SWR) | Yes | Medium | Cross-instance hot keys, cold start |
| **Probabilistic early (XFetch)** | ~1 (no miss window) | None (refresh before expiry) | Up to ~one refresh interval | Yes (stateless) | Medium | Hottest keys where any miss is bad |
| **TTL jitter** | Spread over window | None | ±jitter | Yes | Trivial | Bulk-warmed key sets (avalanche) |
| **Stale-while-revalidate** | ~1 background | None | Up to (hard−soft) | Yes | Low–Med | Tolerably-stale hot data; HTTP/CDN |
| **Negative caching** | ~1 per missing key/TTL | None | Short | Yes | Low | Penetration / missing-key floods |
| **Edge coalescing (CDN/Nginx)** | ~1 | Followers wait briefly | Optional SWR | Yes | Low (config) | HTTP traffic — highest ROI |

### 8.2 Decision rules

**Use single-flight when…** the hot keys live behind one process or you accept M origin calls; you want the simplest robust fix → just use a `LoadingCache`. **Avoid when…** you need a guaranteed single cluster-wide call.

**Use a distributed lock when…** one key serves cross-instance traffic and a duplicate fill is acceptable. **Avoid when…** you need true mutual exclusion of a non-idempotent op (then add fencing tokens / use ZooKeeper).

**Use XFetch when…** a key is extremely hot and *any* miss window is unacceptable, and slight extra steady-state refresh load is fine. **Avoid when…** the value is cheap to compute (overkill) or extreme freshness is required (it serves up to ~one interval stale).

**Use TTL jitter when…** you ever set TTLs on a batch of keys (i.e., always). It's free; there's almost no reason not to.

**Use SWR when…** stale data for seconds is acceptable and you want zero reader-facing refresh latency. **Avoid when…** correctness demands strictly fresh reads (financial balances at settlement, inventory at oversell boundary).

**Use negative caching when…** missing/erroring keys are common or attackable. **Avoid when…** absence changes rapidly and stale "absent" would be wrong (keep negative TTL tiny).

**Use edge coalescing when…** the traffic is cacheable HTTP. It's usually the cheapest and most effective lever for web traffic.

### 8.3 Combine, don't choose

These are layers, not alternatives. A robust system commonly uses **jitter (always) + SWR/XFetch (hot keys) + single-flight (in-process) + distributed lock (cold start cross-instance) + negative caching + jittered backoff (retries) + edge coalescing (HTTP)** simultaneously. §3.8 shows the composed path.

---

## 9. Failure modes & debugging

### 9.1 Signatures of each failure

| Symptom | Likely cause | Confirm with |
|---|---|---|
| Periodic latency sawtooth aligned to TTL | Stampede at expiry; no SWR/single-flight | Overlay TTL period on p99 latency graph; origin QPS spikes at boundaries |
| One huge origin spike every few minutes | Avalanche: many keys, same TTL, no jitter | Histogram of key expiry times → tight cluster |
| Origin overload right after deploy | Cold-start herd (empty cache) | Cache hit ratio near 0 post-deploy; warm-up missing |
| Origin overload right after cache flush/restart | Cold cache + concurrent reload | Correlate flush/restart events |
| DB stays pegged after the trigger ends | Metastable failure (self-sustaining) | Load doesn't drop when input drops; refill window keeps growing |
| Repeated DB hits for nonexistent keys | Cache penetration; no negative cache | Logs of misses for keys that return null |
| Synchronized client retries hammering a recovering service | Retry storm; no jitter | Retry timestamps cluster at fixed intervals |
| Hot key deadlocked / never refilling | Distributed lock with no TTL, holder crashed | `TTL lock:<key>` shows −1 (no expiry) and key never refills |
| Two leaders fill simultaneously | Lock TTL < origin latency, or GC pause | Lock-renew logs / duplicated load metrics |

### 9.2 Diagnostic tools & commands

- **Redis hot-key & lock inspection:**
  - `redis-cli --hotkeys` (requires LFU maxmemory-policy) — finds the hottest keys.
  - `redis-cli monitor` (use briefly! it's expensive) — watch live commands for a stampede burst of identical reads.
  - `redis-cli --bigkeys` / `MEMORY USAGE key` — size of hot values.
  - `TTL key` / `PTTL key` — confirm expiry timing and lock TTLs.
  - `INFO stats` → `expired_keys`, `keyspace_hits`/`keyspace_misses` — miss-ratio trend.
  - `SLOWLOG GET` — slow commands during the spike.
  - `CLIENT LIST` — connection pile-up.
- **JVM:**
  - Caffeine `cache.stats()` → `loadCount`, `hitRate`, `averageLoadPenalty`, `loadFailureCount`.
  - Thread dump (`jstack`) during the spike → many threads parked on the *same* lock / future = coalescing working or contention.
  - JFR / async-profiler → flame graph showing concentrated time in `origin.load`.
  - Micrometer/Prometheus: alert on `cache_misses_total` rate and origin QPS.
- **DB:** active-query counts (`SHOW PROCESSLIST` / `pg_stat_activity`) showing N identical queries; query-store/slow-log spike.
- **Edge:** CDN analytics for "origin requests" vs "edge requests"; a low collapse ratio = coalescing not working.

### 9.3 Real-world incident patterns (illustrative)

- **Facebook memcache leases (documented in their NSDI paper):** to fight stampede ("stale set" + "thundering herd"), memcache hands out a **lease** to the first misser to recompute, while others get told to wait briefly and retry or serve stale — an at-scale single-flight + SWR. This is the canonical industry example.
- **Cron-aligned avalanche:** a fleet that refreshes config "every hour on the hour" creates a herd at :00 every hour. Fix: jitter the schedule (`0 * * * *` → randomized minute/second per host).
- **Circuit-breaker recovery herd:** when a breaker transitions from OPEN to CLOSED all at once, all clients resume simultaneously and re-overload the dependency. The HALF-OPEN state (let a trickle through) is the built-in mitigation; ensure your breaker uses it and ramps gradually.
- **Deploy cold-start:** a rolling deploy restarts pods with empty local caches; each new pod cold-misses the same hot keys → multi-pod herd. Fix: warm caches on startup (staggered) and rely on the distributed cache as a second tier.

### 9.4 Live remediation (when it's happening now)

1. **Stop the bleed:** temporarily **increase the hot key's TTL** (or freeze refresh) so the origin can refill once and breathe — breaks the metastable loop.
2. **Serve stale:** flip on SWR / `stale-if-error` so readers stop waiting on the origin.
3. **Shed load:** rate-limit or shed low-priority traffic to the origin.
4. **Enable edge coalescing / `proxy_cache_lock`** if available and off.
5. **Then** root-cause: which key, which trigger (TTL/flush/deploy/cron), add the permanent layer (jitter/SWR/XFetch/single-flight).

---

## 10. Interview drill

### Q1. What is a cache stampede and why is it dangerous?
**Model answer:** When a hot cached key expires, all concurrent requests miss simultaneously and independently recompute the same value against the origin, multiplying load by the concurrency at that instant — precisely when the origin is busy. The danger is the positive feedback loop: the overloaded origin gets slower, the refill window grows, more requests pile in, potentially tipping into a metastable, self-sustaining outage.
- *Follow-up: How does it differ from cache penetration and avalanche?* Stampede/breakdown = one hot key expiring; penetration = requests for keys absent in both cache and origin bypassing the cache; avalanche = many keys expiring together.
- *Follow-up: What's the load multiplier formula?* ~N (concurrency in the miss window) per origin call; the window = origin latency, which itself grows under load.
- *Follow-up: Where does the term "thundering herd" come from?* OS kernels waking all `accept`/`epoll` waiters for one event.

### Q2. Explain single-flight / request coalescing and implement it in Java.
**Model answer:** Collapse concurrent identical loads into one origin call via an atomic `ConcurrentHashMap<K, CompletableFuture<V>>`; the first caller per key creates and runs the future, others join it; remove the entry on completion with a value-checked remove. (See §5.2.)
- *Follow-up: Lock-based vs future-based?* Locks block one thread per waiter; futures wake all waiters from one result — cheaper at high fan-out. Virtual threads narrow the gap.
- *Follow-up: Does it work across instances?* No — it caps at one call per JVM; cross-instance needs a distributed lock or edge coalescing.
- *Follow-up: How do you avoid removing a newer in-flight entry?* `remove(key, future)` value-checked removal.

### Q3. How does probabilistic early expiration (XFetch) prevent stampede without locks?
**Model answer:** Each reader gambles per read: refresh early with probability that rises as expiry nears (`now − delta·β·ln(rand) ≥ expiry`). Statistically ~one reader refreshes slightly *before* expiry while the value is still served, so the cache is never empty and no herd forms — fully decentralized, no coordination. (See §3.4, §7.1.)
- *Follow-up: Role of `delta` and `β`?* `delta` = last recompute cost (costlier → refresh earlier, self-tuning); `β` scales eagerness (>1 earlier/safer, <1 later/riskier).
- *Follow-up: Still need single-flight?* Yes, to guard the rare simultaneous double-volunteer and the cold-start true miss.

### Q4. Compare stale-while-revalidate with locking. When choose which?
**Model answer:** SWR serves the stale value instantly and refreshes once in the background — zero reader latency, one origin call, at the cost of bounded staleness. Locking forces (at least one) reader to wait for a fresh value — strictly fresh but adds latency and risks follower pile-up. Choose SWR when seconds of staleness are acceptable (most read-heavy web data); choose locking/blocking only when reads must be fresh.
- *Follow-up: Implement SWR in Caffeine?* `refreshAfterWrite(d)` with `d < expireAfterWrite`.
- *Follow-up: Risk of SWR?* Serving stale beyond tolerance; mitigate with a hard TTL and monitoring stale-serve count.

### Q5. (Senior signal) You have a single mega-hot key behind 50 app instances and a Postgres origin. Design the protection and justify each layer.
**Model answer:** (1) **TTL jitter** on every write — cheap baseline. (2) **In-process single-flight** (Caffeine `LoadingCache`) — caps to ≤50 origin calls. (3) **SWR** (`refreshAfterWrite` or soft/hard TTL) so readers never wait. (4) **Distributed lock** (`SET NX PX` + token release) so cold start collapses to ~1 cross-instance call; followers serve stale or poll with jitter. (5) **XFetch** for this specific key to eliminate the miss window entirely. (6) **Jittered backoff** on origin retries. Justify: jitter prevents avalanche; single-flight handles steady state cheaply; SWR removes latency; the distributed lock handles the cold-start cross-instance case where local single-flight would still yield 50 calls; XFetch removes the residual miss window; backoff prevents retry storms. I'd accept a rare duplicate fill (idempotent put) rather than pay for perfect distributed mutual exclusion.
- *Follow-up: Why not just a distributed lock everywhere?* Latency + follower pile-up + lock-correctness fragility; locks are for the cold path, not the hot path.
- *Follow-up: Lock TTL value?* > p99.9 of the Postgres query; or use Redisson watchdog to auto-renew.

### Q6. (Senior signal) Your team adds aggressive retries and the next downstream blip turns into a worse outage. What happened and how do you fix it without removing retries?
**Model answer:** A **retry storm** — synchronized clients failed together and retried in lockstep, amplifying load on the recovering dependency (a self-inflicted herd). Fix: **exponential backoff with jitter** (full jitter: `random(0, min(cap, base·2^attempt))`), cap max attempts, add a **circuit breaker** (so retries stop entirely when the dependency is down) with a **half-open** ramp, and **retry budgets** (cap the fraction of traffic that may be retries). Same de-sync math as TTL jitter.
- *Follow-up: Full vs equal jitter?* Full jitter (`random(0, cap)`) generally spreads best per AWS analysis; equal jitter keeps a minimum delay.
- *Follow-up: Where does the breaker help vs jitter?* Jitter spreads retries; the breaker stops them when the dependency is clearly down, preventing any herd at all.

### Q7. (Senior signal) Stale data is unacceptable for this endpoint, yet it's a hot key. How do you prevent stampede?
**Model answer:** Since SWR/stale is off the table, prevent the *empty-cache window* rather than tolerate staleness: use **XFetch** (refresh slightly early while the value is still fresh-and-valid) or **proactive scheduled refresh** that writes a new fresh value before TTL; combine with **single-flight/distributed lock** so only one fresh recompute runs. If truly no caching of any kind is allowed, fall back to **request coalescing on the live read** + **load shedding/rate limiting** + provisioning. The key insight: you can avoid stampede *without* serving stale by ensuring the refresh happens before expiry, not after.
- *Follow-up: Difference between XFetch and scheduled refresh?* XFetch is traffic-driven and decentralized (no separate scheduler, scales with read load); scheduled refresh is time-driven and needs a job + leader to avoid its own herd.

### Q8. How does a distributed lock with Redis work, and what are its correctness limits?
**Model answer:** `SET lock:K token NX PX ms` atomically acquires with auto-expiry; release via Lua check-and-delete so you only delete your own token. Limits: GC/VM pauses or clock drift can let the lock expire mid-hold and a second client acquire it → two holders. For cache fill that's tolerable (idempotent, last-write-wins). For correctness-critical mutual exclusion you need **fencing tokens** or a consensus service; Redlock is debated for that use.
- *Follow-up: Why the token?* So a slow holder whose lock already expired doesn't delete a *new* holder's lock.
- *Follow-up: Why PX is mandatory?* A holder crash without TTL deadlocks the key forever.

### Q9. What's TTL jitter and when is it insufficient on its own?
**Model answer:** Randomizing each key's TTL (`base ± random`) so a batch written together doesn't expire together — kills avalanche. Insufficient alone for a *single* mega-hot key (jitter spreads *across keys*, not *within one key*), where you still need single-flight/SWR/XFetch.
- *Follow-up: Typical magnitude?* ±10–25% of base, or an additive uniform window.

### Q10. How do CDNs and reverse proxies prevent stampede?
**Model answer:** **Request collapsing/coalescing** (Varnish/Fastly/Nginx `proxy_cache_lock`) dedupes concurrent misses for the same URL into one origin fetch; **grace/stale-while-revalidate** (`proxy_cache_use_stale updating`, RFC 5861 `stale-while-revalidate`) serves stale while refreshing; **tiered/shield caching** funnels all edge misses through one mid-tier so the origin sees ~1 request. Often the highest-ROI fix for HTTP, with no app code.
- *Follow-up: Caveat?* Long/streaming responses may not collapse perfectly; verify vendor behavior and defaults.

### Q11. Explain the kernel thundering herd and its modern fixes.
**Model answer:** Many workers blocked on a shared listen socket all woke for one connection. Fixes: kernel now wakes one for `accept()`; for epoll use `EPOLLEXCLUSIVE` (≥4.5) to wake one waiter, or `SO_REUSEPORT` (≥3.9) to give each worker its own socket with kernel load-balancing (used by Nginx/Envoy). Condition-variable analog: prefer `signal`/`notify` over `broadcast`/`notifyAll` when only one waiter can proceed.

### Q12. (Scenario) Every hour on the hour your DB CPU spikes to 100% for 30 seconds. Diagnose and fix.
**Model answer:** Classic cron-aligned avalanche — many caches/jobs refresh "on the hour," expiring/firing simultaneously. Diagnose: correlate the spike to :00; inspect key expiry-time histogram (tight cluster) and scheduled jobs. Fix: **jitter the schedule per host/key** (randomize the minute/second), **jitter TTLs**, add **single-flight/SWR** on the refreshed keys, and stagger the refresh fan-out.

---

## 11. Glossary

- **Backend / origin:** The authoritative, slow source of data behind a cache (DB, service, API, computation).
- **Backoff:** Increasing the wait between retries; **exponential backoff** doubles each attempt.
- **Bloom filter:** Compact probabilistic set; "definitely not present" is reliable, "maybe present" may be a false positive. Used to reject impossible keys before they hit the origin.
- **Bulkhead:** Isolation that caps resources per dependency so one overload doesn't sink the whole app.
- **Cache:** Fast store of expensive-to-fetch data.
- **Cache avalanche (缓存雪崩):** Many keys expiring at once → multi-key stampede. Fixed by TTL jitter.
- **Cache breakdown (缓存击穿):** A single hot key expiring → classic stampede.
- **Cache hit / miss:** Data found / not found in cache.
- **Cache penetration:** Requests for keys absent in cache *and* origin, bypassing the cache. Fixed by negative caching + Bloom filters.
- **Cache stampede / dogpile / cache miss storm:** Many concurrent misses recomputing the same value against the origin.
- **CDN (Content Delivery Network):** Edge cache serving content near users.
- **Circuit breaker:** Stops calls to a failing dependency; **half-open** lets a trickle through to test recovery (prevents a recovery herd).
- **CompletableFuture:** Java promise; a placeholder for an async result that many callers can await.
- **ConcurrentHashMap:** Thread-safe Java map; `computeIfAbsent`/`putIfAbsent` give atomic compute-once semantics.
- **Coalescing (request coalescing):** Merging concurrent identical requests into one. Synonym of single-flight.
- **Cold start:** A freshly started instance with an empty cache; prone to herds.
- **Delta (XFetch):** Measured cost (duration) of the last recomputation; drives early-refresh probability.
- **Distributed lock:** Mutual exclusion across machines (e.g., Redis `SET NX PX`, ZooKeeper).
- **Dogpile:** Synonym for cache stampede.
- **EPOLLEXCLUSIVE / SO_REUSEPORT:** Linux flags that wake one waiter / give each worker its own socket — kernel thundering-herd fixes.
- **ETag:** HTTP validator for conditional revalidation (304 Not Modified).
- **Eviction:** Removing an entry to free space (LRU/LFU/W-TinyLFU); can trigger a herd if it drops a hot key.
- **Fencing token:** Monotonic number issued with a lock; the protected resource rejects stale tokens — makes distributed locking safe for correctness.
- **Full jitter:** `random(0, min(cap, base·2^attempt))` — AWS-recommended retry spread.
- **Hit ratio:** hits / total lookups.
- **Idempotent:** Safe to run multiple times with the same effect; important if a herd leaks through.
- **Jitter:** Deliberate randomness added to delays/TTLs to break synchronization.
- **Lease:** A time-bounded grant (e.g., Facebook memcache hands a lease to the first misser to recompute).
- **LoadingCache:** A cache that loads missing values itself; Caffeine's invokes the loader once per key under concurrent misses (built-in single-flight).
- **Lua (in Redis):** Embedded scripting language; scripts run atomically on the single-threaded Redis server.
- **Metastable failure:** A self-sustaining overloaded state that persists after the trigger is gone.
- **Memcached / Redis:** Distributed in-memory caches (Redis is richer: data types, Lua, single-threaded command execution).
- **Mutex:** Mutual-exclusion lock; one holder at a time.
- **Negative caching:** Caching the absence/error for a key briefly to stop repeated misses hammering the origin.
- **Near-cache:** A local cache layered in front of a distributed cache, with invalidation (e.g., Redisson `RLocalCachedMap`).
- **`notifyAll` / broadcast:** Wake all waiters — can thunder; prefer `notify`/`signal` when one can proceed.
- **Probabilistic early expiration (XFetch):** Refresh slightly before expiry with rising probability so ~one reader refreshes early and the cache never empties.
- **Redlock:** Multi-node Redis locking algorithm; contested for correctness-critical use.
- **Redisson:** Java Redis client offering `RLock` (with watchdog renewal), near-caches, and TTL maps.
- **Request collapsing:** Edge/proxy term for single-flight (Varnish/Fastly/Nginx).
- **Retry storm:** Synchronized retries amplifying load on a recovering dependency.
- **Single-flight:** Collapse concurrent identical loads into one; the de facto term (from Go's `singleflight`).
- **Soft / hard TTL:** Freshness deadline vs eviction deadline; the gap is the SWR window.
- **Stale-while-revalidate (SWR):** Serve stale immediately while one background task refreshes (RFC 5861; Caffeine `refreshAfterWrite`; Nginx `proxy_cache_use_stale updating`).
- **Striped lock:** Fixed array of locks indexed by key hash; bounds lock memory at the cost of false contention (Guava `Striped`).
- **Thundering herd:** Many actors waking/contending for one resource simultaneously.
- **TTL (Time To Live):** Validity duration of a cached entry before expiry.
- **W-TinyLFU:** Caffeine's high-hit-rate admission/eviction policy.
- **ZooKeeper:** Strongly-consistent coordination service for locks/leader election/config.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Problem:** hot key expires → all concurrent misses recompute the same value → origin load × N → feedback loop → outage.

**Three terms:** *breakdown* = one hot key; *avalanche* = many keys at once; *penetration* = absent keys bypassing cache.

**Five levers (memorize):**
1. **Single-flight / coalescing** → 1 (per process) call; default via Caffeine `LoadingCache`. Caps cross-instance at M.
2. **Distributed lock** (`SET NX PX` + Lua token release) → ~1 cluster-wide; for cold path; followers serve stale or poll-with-jitter.
3. **XFetch** (`now − delta·β·ln(rand) ≥ expiry`, β default 1) → refresh before expiry; no miss window; stateless across instances.
4. **TTL jitter** (`base ± 10–25%`) → kills avalanche; apply on *every* TTL set. Free.
5. **SWR** (soft<hard TTL; Caffeine `refreshAfterWrite`; HTTP `stale-while-revalidate`) → serve stale + 1 background refresh; zero reader latency.

**Also:** negative caching + Bloom filter (penetration); jittered exponential backoff (retry storms); edge coalescing (`proxy_cache_lock`, Varnish/Fastly) — highest ROI for HTTP; circuit-breaker half-open (recovery herd).

**Key numbers/defaults:** Caffeine async refresh pool = `ForkJoinPool.commonPool()` (override it); set `refreshAfterWrite < expireAfterWrite` for SWR; Redis lock **always** needs `PX`; lock TTL > p99.9 origin latency; full jitter = `random(0, min(cap, base·2^attempt))`; Resilience4j `randomizationFactor` default 0.5; `EPOLLEXCLUSIVE` Linux ≥ 4.5, `SO_REUSEPORT` ≥ 3.9; Nginx `proxy_cache_lock_timeout` default 5s.

**Correctness rules:** double-check cache after acquiring lock; value-checked in-flight removal; tokenized lock release; distributed lock = OK for idempotent cache fill, NOT for non-idempotent ops without fencing tokens.

**Diagnose:** TTL-aligned latency sawtooth, origin-QPS-per-key spike, miss-rate spike, expiry-time histogram clustering. Tools: `redis-cli --hotkeys`/`monitor`/`TTL`, Caffeine `stats()`, `jstack`, `pg_stat_activity`.

**Live fix order:** raise TTL/freeze → serve stale → shed load → enable edge coalescing → root-cause → add permanent layer.

### 12.2 Self-test (no answers — recall actively)

1. Derive why XFetch's refresh probability approaches 1 as `now → expiry`, and explain how `delta` makes it self-tuning.
2. You run 50 instances with Caffeine single-flight on one hot key. How many origin calls at cold start, and what single change reduces it to ~1?
3. Write the Lua needed to safely release a Redis lock and explain what breaks if you skip the token check.
4. Your DB CPU spikes exactly on the hour every hour. Give the diagnosis and the three changes you'd make.
5. SWR and locking both yield ~1 origin call. State two concrete situations where you must NOT use SWR, and why.
6. Explain the difference between cache avalanche, breakdown, and penetration, and name the primary mitigation for each.
7. A new aggressive-retry feature turned a minor downstream blip into a full outage. Name the failure, the math of the fix, and two complementary safeguards.
8. When is a simple Redis `SET NX PX` lock correct for cache fill but unsafe for guarding a payment, and what would make the payment case safe?

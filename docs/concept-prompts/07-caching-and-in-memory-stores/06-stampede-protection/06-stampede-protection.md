# Stampede Protection

> Concept area: Caching & In-Memory Stores
> Subtopic: Stampede Protection (a.k.a. cache stampede, dogpile effect, thundering herd against the origin)

---

## 1. Overview & where it fits

### 1.1 What it is

**Cache stampede protection** is the family of techniques that prevent a cache from forwarding a sudden flood of identical (or near-identical) requests to the slow, expensive thing behind it — your database, an upstream service, a third-party API, or a CPU-heavy computation — at the exact moment the cached value becomes unavailable.

The "stampede" happens at a very specific instant: a popular cache key **expires** or is **evicted**, and many concurrent requests all look it up, all miss simultaneously, and all decide independently "the value isn't cached, I'd better compute/fetch it myself." Instead of one recomputation, you get N recomputations of the **same** value, all racing each other, all hammering the origin at once.

Three near-synonyms you will hear, all describing the same root event:

- **Cache stampede** — the generic name.
- **Dogpile effect** — older term from the web-caching world (Memcached/Django era). "Dogpile" is American slang for many people piling onto one thing at once; the image is many workers piling onto the database.
- **Thundering herd** — strictly, this term comes from operating systems: when many threads/processes are blocked waiting on one event (e.g. a single socket becomes readable), the kernel wakes *all* of them, they all race for the one available unit of work, all but one fail and go back to sleep — wasting CPU. In caching we borrow the phrase to mean "many clients woken at once all charge the origin." The mechanism is analogous: one event (cache expiry) releases a herd.

> **Beginner note — "origin":** Throughout this doc, *origin* (also *backend* or *source of truth*) means whatever the cache is sitting in front of: a SQL/NoSQL database, a microservice, a REST/gRPC API, a search cluster, or an in-process function that is expensive to run. The cache exists precisely because hitting the origin is slow or costly.

### 1.2 The problem it solves

A cache's whole value proposition is **load amplification reduction**: if a key is requested 10,000 times/second and the cached value is valid, the origin sees ~0 of those requests. The cache *absorbs* the load. Stampede is the failure mode where that absorption suddenly **inverts**: at the instant of expiry, the cache stops absorbing and instead **amplifies** — it lets through a burst that the origin was never provisioned to handle, because the whole point of the cache was that the origin would *never* see that load.

This is dangerous in a way ordinary "high load" is not, because:

1. **It is correlated.** All N requests miss in the same millisecond. The origin sees N concurrent identical queries, not N spread over time.
2. **It is self-amplifying.** The first query is slow because the origin is now overloaded; because it's slow, the cache stays empty longer; because the cache stays empty, *more* requests miss and pile on; which makes the origin slower still. This is a positive feedback loop — a **metastable failure** — that can take down a database that was at 5% CPU a second earlier.
3. **It hits hardest exactly when you're most popular.** The hottest keys (most-requested) produce the biggest stampedes. Your most valuable traffic causes your worst outage.

> **Beginner note — "metastable failure":** A system is *metastable* when, after a trigger pushes it past a threshold, it stays broken even after the trigger is gone, because the broken state feeds itself. A cache stampede is a classic trigger: a brief expiry causes overload, the overload causes retries and more misses, and the system won't recover even if original traffic drops — until you intervene (e.g. shed load or warm the cache).

### 1.3 When you reach for it

You need stampede protection whenever **all** of these are true:

- The cached item is **expensive** to produce (slow query, heavy aggregation, remote call, paid API).
- The item is **hot** — many concurrent readers want the *same* key.
- A miss is **inevitable** at some point (TTL expiry, eviction, deploy/restart that empties an in-memory cache, manual invalidation).

If the value is cheap to compute, or each request wants a *different* key, or the cache never empties, stampede protection is over-engineering. The danger is specifically **concurrent misses on a shared hot key**.

### 1.4 The one-paragraph mental model

> A cache is a dam holding back a reservoir of requests. While the dam is full (cache valid), almost nothing flows downstream to the origin. A cache stampede is the moment a crack opens (key expires): everything that was held back rushes through at once. Stampede protection is the set of valves you install so that when the dam needs refilling, **exactly one** stream of water is let through to do the refilling (single-flight / locking), the refill happens **before** the dam fully empties (early/background refresh), the refill is **spread out in time** across keys (jittered TTL), and meanwhile everyone else is handed **slightly stale water from the last fill** rather than being sent down to the river themselves (serve-stale-while-revalidate).

Keep that picture: **one refiller, refill early, stagger the refills, serve stale meanwhile.** Every technique in this document is one of those four valves.

---

## 2. Foundations from first principles

We build up from the simplest possible cache and add the failure, then the fixes.

### 2.1 The cache-aside pattern (the starting point)

The most common caching pattern is **cache-aside** (also called *lazy loading* or *look-aside*). The application — not the cache — owns the logic:

```
1. read(key):
2.   value = cache.get(key)
3.   if value is present:        // CACHE HIT
4.       return value
5.   value = origin.load(key)    // CACHE MISS -> go to source of truth
6.   cache.set(key, value, ttl)  // populate cache for next time
7.   return value
```

> **Beginner note — patterns:** *Cache-aside* means the app reads the cache, and on a miss, reads the DB and writes the cache itself. Contrast with *read-through* (the cache library loads from the origin for you on a miss) and *write-through/write-behind* (writes go through the cache to the DB). Stampede is overwhelmingly a *read-path* problem and arises in all of these, but cache-aside is where you see it most because the app code does the loading.

> **Beginner note — TTL:** *TTL* = "time to live," the duration a cached entry stays valid before it's considered expired. After TTL, a `get` returns "miss" (or in some caches the entry is physically deleted by a background reaper). TTL exists to bound staleness: it guarantees the cache can't serve data older than `now - TTL`.

### 2.2 Why the naive version stampedes

Look at lines 2–6. They are **not atomic**, and they are executed **independently by every request thread.** Now imagine key `K` is requested 5,000×/sec, TTL expires at `t=0`:

- At `t=0+0ms`, thread A runs line 2, gets a miss, and starts the slow origin load (line 5) which takes 200ms.
- At `t=0+1ms`, thread B runs line 2. The cache is *still empty* (A hasn't reached line 6 yet). B also misses, also starts the slow origin load.
- … this repeats for every thread that arrives in the 200ms window. At 5,000 req/s, that's **~1,000 concurrent identical origin loads** for one key.

The cache provided **zero** protection during that 200ms window. Worse, all 1,000 loads write the same value back (line 6) — wasted work, wasted DB connections, wasted memory churn. This is the stampede.

The crucial insight: **the vulnerability window equals the origin latency.** Anything that arrives between "first miss" and "first successful write-back" stampedes. Lower origin latency → smaller window → smaller herd, but you can never make it zero.

### 2.3 Two distinct triggers: single-key vs. mass-expiry

There are two flavors of stampede and they need different fixes:

1. **Single hot-key stampede** (the one above): one popular key expires, and the concurrent readers of *that key* all miss together. Fixed primarily by **coalescing** (one refiller).

2. **Mass-expiry / fleet stampede** (a.k.a. *correlated expiry* or *synchronized expiry*): many *different* keys expire at the *same instant*, because they were all written at the same instant with the same TTL. Classic cause: a deploy warms 100,000 keys at startup with `ttl=3600s`; exactly one hour later, all 100,000 expire in the same second, and the origin gets a giant correlated burst across many keys. Coalescing does **not** help here (the keys are different, so there's nothing to coalesce); the fix is **TTL jitter / staggering**.

A close cousin of #2 is the **cold-cache herd**: the cache is *completely empty* (after a restart, a flush, a failover to a fresh node, or scaling out a new cache node) and the first wave of traffic across *all* keys misses. This is mass-expiry's evil twin and needs the same staggering plus warmup strategies.

### 2.4 Negative results and the "miss storm"

A subtle variant: what if the origin legitimately returns **nothing** (no row, 404, empty result)? If you only cache *positive* results, then every request for a non-existent key is a permanent miss → permanent origin load → a perpetual stampede on "things that don't exist." This is why **negative caching** (caching the *absence* of a value, usually with a short TTL) is part of the stampede toolkit; see §6 and §7.5.

### 2.5 The four core mitigations (preview, defined plainly)

Everything else in this document elaborates these four ideas. Defined from scratch:

1. **Locking / per-key mutex / single-flight (request coalescing):** Ensure that for a given key, **only one** thread/process recomputes at a time; the rest **wait** for that one and reuse its result. Turns N origin loads into 1.

   > **Beginner note — "mutex":** A *mutex* (mutual-exclusion lock) is a primitive that lets only one thread hold it at a time; others trying to acquire it block until it's released. A *per-key mutex* means one lock object per cache key, so different keys don't block each other.
   > **Beginner note — "single-flight":** A pattern (named after Go's `golang.org/x/sync/singleflight`) where concurrent calls with the same key are *deduplicated*: the first call executes, all others attach to it and receive the same result. It's coalescing implemented as a small in-process map of in-progress calls.

2. **Probabilistic early recomputation (XFetch):** Instead of recomputing *exactly* at expiry (when everyone misses together), let each request *randomly* decide, as the TTL approaches, whether it is the unlucky one that will recompute **early** — while the old value is still served to everyone else. Spreads the recompute over time and across requests so it almost never happens that "everyone misses at once."

3. **Staggered / jittered TTL:** Add randomness to TTLs so keys don't expire in lockstep. Breaks correlated expiry (mass-expiry herd).

4. **Serve-stale-while-revalidate (SWR) / background refresh:** Keep serving the *old* (slightly stale) value to readers while a single background task refreshes it. Readers never block and never miss; the origin sees one refresh. This decouples "the value expired" from "readers must wait."

These compose. A production-grade hot cache often uses **all four** at once.

---

## 3. How it works internally

This is the core of the document. We will trace each mitigation's control flow, data flow, lifecycle, and state machine in detail, then show how they combine.

### 3.1 Anatomy of the vulnerability window (precise timeline)

Define:

- `t_exp` = the instant the entry becomes invalid (TTL boundary or eviction).
- `L` = origin load latency (time from "start load" to "value in hand").
- `λ` = request arrival rate for this key (requests/second).
- `W` = vulnerability window = the interval during which a concurrent request will *also* trigger a load.

Without protection, `W ≈ L` (from first miss until first write-back). The expected number of redundant loads in a single stampede is approximately:

```
redundant_loads ≈ λ × L
```

Example: `λ = 5000 req/s`, `L = 0.2 s` → ~1000 redundant loads per expiry. This single formula explains why hot + slow = catastrophe: both factors multiply. It also tells you the two non-protection levers: reduce `λ` per key (sharding/replicas) or reduce `L` (faster origin) — but real fixes shrink `W` toward 0 or eliminate the synchronized miss entirely.

### 3.2 Mitigation 1 — Per-key mutex / lock (in-process)

**Goal:** within one JVM, only one thread loads a given key; others wait and reuse.

**Control flow:**

```
read(key):
  v = cache.get(key)
  if hit: return v
  lock = locks.computeIfAbsent(key, k -> new ReentrantLock())  // per-key lock
  lock.lock()
  try:
    v = cache.get(key)        // DOUBLE-CHECK: someone may have filled it while we waited
    if hit: return v
    v = origin.load(key)      // exactly one thread reaches here per key
    cache.set(key, v, ttl)
    return v
  finally:
    lock.unlock()
```

**Why the double-check (lines after `lock.lock()`)?** When threads B…Z block on `lock.lock()`, thread A is loading. When A finishes and unlocks, B acquires the lock — but **the value is now in cache**. If B didn't re-check, it would redundantly load again. The second `cache.get` (*double-checked locking*) is what converts "N loads" into "1 load + (N-1) cache hits after a brief wait."

> **Beginner note — "double-checked locking":** A pattern where you check a condition cheaply (cache.get), then take a lock, then check *again* inside the lock. The first check avoids locking on the common path (hit); the second check avoids redundant work after waiting for the lock.

**State machine for a key's lock:**

```
   ┌─────────┐  first miss   ┌──────────┐  load done   ┌─────────┐
   │ NO_LOCK │ ────────────▶ │ LOADING  │ ───────────▶ │ NO_LOCK │
   └─────────┘  (A holds)    │ (others  │  (unlock,    └─────────┘
                             │  wait)   │   value set)
                             └──────────┘
```

**Lifecycle / memory concern:** the `locks` map grows one entry per distinct key ever loaded. Use a **bounded, self-evicting** structure (e.g. Caffeine cache of locks, or `ConcurrentHashMap` with explicit removal in `finally`, or weak values) or you leak memory. Removing the lock in `finally` is racy (another thread may be about to use it), so production code usually uses a small bounded cache of locks keyed by hashed key, accepting occasional false sharing (two keys sharing a lock — harmless correctness-wise, slightly reduces concurrency).

**Failure cases inside the lock:**

- If `origin.load` throws, you must release the lock (the `finally`) and decide whether to cache a negative result, retry, or propagate. Do **not** hold the lock while sleeping/retrying — that serializes failures into a new stampede of waiters.
- **Lock-wait timeout:** waiters should use `lock.tryLock(timeout)` so they don't block forever if the loader hangs. On timeout, the waiter can serve stale (if available) or fail fast — never spin into its own load (that re-creates the stampede).

**Scope limit:** this only coordinates threads **within one process**. With M app instances, you get up to **M** concurrent loads (one per JVM). For hot keys behind a shared DB, M may still be too many → you need a **distributed lock** (§3.3).

### 3.3 Mitigation 1b — Distributed lock (cross-process coalescing with Redis)

**Goal:** across the whole fleet, only one *process* loads a given key.

The canonical primitive is a Redis lock acquired with:

```
SET lock:{key} {token} NX PX {ttl_ms}
```

- `NX` = "set only if Not eXists" → atomic acquire; only the first caller succeeds.
- `PX {ttl_ms}` = the lock auto-expires after `ttl_ms` so a crashed holder doesn't deadlock the key forever.
- `{token}` = a unique random value the holder remembers, so it only deletes *its own* lock (release must check the token; see below).

> **Beginner note — Redis:** *Redis* is an in-memory key-value data store, single-threaded for command execution, commonly used as a cache and for coordination. Its single-threaded command loop makes individual commands atomic, which is what makes `SET ... NX` a correct lock acquire.

**Release must be atomic and ownership-checked** (do it with a Lua script so check-and-delete can't interleave):

```lua
-- KEYS[1] = lock key, ARGV[1] = our token
if redis.call("GET", KEYS[1]) == ARGV[1] then
  return redis.call("DEL", KEYS[1])
else
  return 0
end
```

> **Beginner note — Lua in Redis:** Redis runs Lua scripts atomically (no other command interleaves), so the GET-then-DEL is safe. Without it, your lock could expire, another process could acquire it, and your stale DEL would delete *their* lock.

**Control flow for the loser threads (the herd):** When you *fail* to acquire the lock, you have three choices, in order of preference:

1. **Serve stale** if you kept the old value around (best — no waiting; see §3.6).
2. **Wait-and-retry-get:** sleep briefly (with backoff + jitter), re-`GET` the cache a few times hoping the winner filled it. Bounded retries only.
3. **Fail fast** (return error / fallback) if neither is possible.

The dangerous default is "loser also loads" — that defeats the lock entirely.

**The hard part — distributed locks are *not* perfectly safe.** A single-node Redis lock with TTL can be **violated** if:

- The holder pauses (GC, VM stall) longer than the lock TTL; the lock expires; another process acquires it; now **two** processes load simultaneously (and worse, both think they're the sole holder).
- A Redis failover loses the lock key (master crashes before replicating the SET to a replica that gets promoted).

> **Beginner note — Redlock & the debate:** *Redlock* is an algorithm that acquires the lock on a majority of N independent Redis masters to tolerate single-node failure. Martin Kleppmann famously argued it's still unsafe for *correctness*-critical mutual exclusion (clock/pause issues); Salvatore Sanfilippo (Redis author) pushed back. **For stampede protection this debate mostly doesn't matter:** the lock is a *performance optimization*, not a correctness guarantee. If it occasionally lets 2 loaders through instead of 1, you've still cut the herd from 1000 to 2 — a 500× win. So we accept a "best-effort" lock here and never rely on it for data correctness. Use a fencing-token-protected lock only if the loaded value's *write* must be exclusive.

**Lock TTL tuning:** set lock TTL `≈ p99 of L` plus margin, and have the holder **extend** the lock (a *watchdog*/lease renewal) if the load runs long, so it doesn't expire mid-load. Too short → premature expiry → double loads. Too long → a crashed holder blocks the key for that long → all readers stale/erroring until expiry.

### 3.4 Mitigation 1c — Single-flight (in-process call deduplication)

Single-flight is the cleanest *in-process* coalescer. It maintains a map `key → in-flight Future`. Control flow:

```
loadCoalesced(key):
  existing = inflight.get(key)
  if existing != null: return existing.get()   // attach to in-progress call
  CompletableFuture<V> f = new CompletableFuture<>()
  prev = inflight.putIfAbsent(key, f)          // atomic "claim"
  if prev != null: return prev.get()           // lost the race, attach to winner
  try:
    V v = origin.load(key)
    cache.set(key, v, ttl)
    f.complete(v)
  catch (e):
    f.completeExceptionally(e)
  finally:
    inflight.remove(key, f)                     // remove only our own future
  return f.get()
```

**Data flow:** the *first* caller creates and owns the future, runs the load, and **fans out** the single result to all attached callers. Memory is self-cleaning: the future is removed when the call finishes.

**Key difference vs. lock:** with single-flight, waiters don't re-run the cache get — they receive the *future's* value directly (the load's fresh result), which is strictly correct. With a lock + double-check, waiters re-read the cache (one extra get). Both are fine; single-flight is slightly cleaner and avoids the lock-map memory issue because futures are removed immediately.

> **Beginner note — Future / CompletableFuture:** A *Future* is a placeholder for a value that will be produced later by some asynchronous computation; calling `get()` blocks until it's ready (or returns immediately if done). `CompletableFuture` is Java's completable variant — you can manually `complete` it, chain callbacks, and share it among many callers, which is exactly what fan-out needs.

**Caffeine's built-in single-flight:** Caffeine's `LoadingCache.get(key, loader)` and `AsyncLoadingCache` already coalesce concurrent misses for the same key in-process — only one loader runs, others wait. This is single-flight, for free. (More in §4.)

### 3.5 Mitigation 2 — Probabilistic early recomputation (XFetch / PER)

This is the most elegant single-key technique and worth understanding deeply.

**The idea:** don't wait for `t_exp`. As the TTL approaches expiry, each reader independently "rolls dice" on whether *it* should recompute **now**, while the value is still valid and still served. The probability of recomputing rises as you near expiry. The expensive loads (`L`) are factored into the decision so that values that take longer to compute get refreshed *earlier* (more lead time). Because the decision is randomized and per-request, the population of readers spreads recomputation across the pre-expiry window, and the chance that **zero** readers recompute before expiry (forcing a real miss-storm) becomes vanishingly small.

**The algorithm (from "Optimal Probabilistic Cache Stampede Prevention", Vattani, Chierichetti, Lowenstein, VLDB 2015):**

When you write a value, also store **the time `delta` it took to compute it** (`delta = L`). On every read, you have:

- `value`, its stored `delta`, and its absolute `expiry` time.
- A tuning constant `beta ≥ 0` (default `beta = 1`).

Compute, per read:

```
now = current_time()
// XFetch test: recompute early if this is true
if (now - delta * beta * ln(random())) >= expiry:
    value = recompute()            // refresh; store new delta and new expiry
    return value
else:
    return value                   // serve the still-valid cached value
```

> **Beginner note — `ln(random())`:** `random()` is a uniform random number in `(0,1]`. `ln` of it is a *negative* number (since ln of <1 is negative), and `-ln(random())` is an exponentially distributed positive number with mean 1. Multiplying by `delta*beta` gives a random "look-ahead" gap whose typical size scales with how expensive the value was to compute. So expensive values get a larger random lead time before expiry → they get refreshed earlier, exactly when you want.

**Why it works (intuition):**

- Far from expiry, `now - delta*beta*ln(random())` is well below `expiry` for almost all random draws → almost nobody recomputes → you serve cached freely.
- As `now → expiry`, the probability that the random term pushes you past `expiry` rises smoothly toward 1. So *somebody* almost surely recomputes **before** the true expiry, refreshing the value, and after refresh everyone sees a new, far-off expiry — the herd never forms.
- It's **decentralized**: no locks, no coordination, no extra round-trips. Each reader decides locally from data it already fetched.

**`beta` tuning:** `beta > 1` → recompute earlier and more aggressively (more freshness, more redundant recomputes); `beta < 1` → recompute later (riskier, closer to the cliff). `beta = 1` is the proven near-optimal default. In practice many implementations expose `beta` as a knob and leave it at 1.

**Limitations:**

- It reduces but does **not** strictly guarantee single-flight: two readers can roll "recompute" at nearly the same moment. So XFetch is often **combined** with a lightweight lock/single-flight so the rare double-roll still collapses to one load.
- It needs the **`delta` (compute time)** stored alongside the value — a small schema change to your cache entries.
- It assumes you *can* serve a still-valid value during the early recompute (you can: the value hasn't expired yet). The early recompute is essentially a *proactive* refresh.

**State machine (per key, with XFetch):**

```
 FRESH ──(read, XFetch test fails)──▶ FRESH        (serve cached)
 FRESH ──(read, XFetch test passes)─▶ REFRESHING ─▶ FRESH (new expiry)
 FRESH ──(true expiry reached w/o refresh, rare)─▶ MISS ─▶ load
```

The whole goal is to keep the system in the top two transitions and almost never reach `MISS`.

### 3.6 Mitigation 3 — Serve-stale-while-revalidate (SWR) + background refresh

**Core idea:** decouple "the value is stale" from "the reader must block." Keep two notions of age:

- **Soft TTL (fresh-until):** after this, the value is considered *stale but still usable*.
- **Hard TTL (usable-until / evict-after):** after this, the value is truly gone.

Control flow:

```
read(key):
  entry = cache.get(key)
  if entry == null:                 // hard miss (past hard TTL or never set)
      return loadAndCache(key)      // must block (use lock/single-flight here)
  if now < entry.softExpiry:        // FRESH
      return entry.value
  // STALE-BUT-USABLE: serve immediately, refresh in background
  if tryAcquireRefreshSlot(key):    // only ONE refresher
      submitAsync(() -> { v = origin.load(key); cache.set(key, v, soft, hard); releaseRefreshSlot(key); })
  return entry.value                // reader gets stale value with ZERO latency
```

**Why this is powerful:** in the steady state for a hot key, **readers never miss and never wait.** They always get a value (fresh or slightly stale), and a single background task quietly refreshes it. The vulnerability window `W` shrinks to ~0 for readers because the cache *never empties* under normal operation; only the background refresher touches the origin, and only one at a time.

> **Beginner note — `stale-while-revalidate`:** This is also a standard **HTTP caching** directive (`Cache-Control: max-age=600, stale-while-revalidate=120`). It tells caches/CDNs: "the response is fresh for 600s; for the next 120s after that, serve the stale copy *and* asynchronously fetch a fresh one." It's the same idea standardized for the web (RFC 5861). There's a companion `stale-if-error` directive: serve stale if the origin errors — a built-in resilience valve.

**Background refresh (proactive variant):** instead of waiting for a reader to trigger refresh on the first stale read, a scheduled job refreshes hot keys *before* soft expiry. Caffeine's `refreshAfterWrite` does exactly this: the first read *after* the refresh interval triggers an **async** reload while returning the old value immediately — i.e., it's SWR built into the cache library (more in §4).

**The "stale forever" risk:** if the origin is down, SWR will keep serving the stale value past hard TTL? No — past **hard** TTL the entry is gone and you fall back to blocking load (which fails). The mitigation is `stale-if-error`: explicitly extend usability when the origin errors, capped to a max staleness you can tolerate. You must bound it, or a long origin outage = serving very old data silently.

### 3.7 Mitigation 4 — Staggered / jittered TTL (mass-expiry herd)

**Problem recap:** N different keys all written with identical TTL at the same time → all expire together → correlated burst.

**Fix:** add randomness to each TTL so expirations are spread over a window:

```
effectiveTtl = baseTtl + random_uniform(0, jitterRange)
// or multiplicative:
effectiveTtl = baseTtl * (1 + random_uniform(-jitterFraction, +jitterFraction))
```

Typical: `baseTtl=3600s`, `jitter = ±10%` (i.e. `jitterRange=360s`), so expirations smear across a ~6–12 minute window instead of one second. The origin sees a gentle ramp, not a cliff.

**Where to apply jitter:**

- On **write** TTL (most common): every `cache.set` adds jitter.
- On **eviction batches:** when warming a large set of keys, also jitter so the warm itself doesn't create a synchronized cohort.
- Combine with **per-key** randomness, not per-batch, so even keys written in the same loop don't share an expiry.

**It does not solve single-hot-key stampede** — jitter spreads *different* keys, but a single key still has one expiry instant with a concurrent herd. So jitter is mandatory for mass-expiry but must be paired with coalescing/SWR/XFetch for hot keys.

### 3.8 Cold-cache herd & warmup

A *cold cache* (empty after restart/flush/scale-out) is mass-expiry taken to the limit: **every** key misses. Internal handling:

1. **Warmup / pre-population:** before taking traffic, a new node/instance loads the top-K hottest keys from the origin (or copies from a peer cache). This converts "everything misses" into "the long tail misses gradually."
2. **Gradual traffic ramp / slow start:** a new cache node receives traffic ramped over seconds/minutes (load balancer slow-start) so misses arrive spread out, not all at once.
3. **Tiered caches:** an L1 in-process cache backed by an L2 shared cache (Redis). When L1 is cold (restart), most reads still hit warm L2, so the origin is shielded. (More in §7.)
4. **Coalescing still applies:** even cold, single-flight/locks ensure each distinct hot key loads once per process, not once per request.

### 3.9 Negative caching (anti-"miss storm")

When the origin returns "no result," cache that fact:

```
v = origin.load(key)
if v == null:
    cache.set(key, NEGATIVE_SENTINEL, shortNegativeTtl)   // e.g. 5-30s
else:
    cache.set(key, v, normalTtl)
```

- Use a distinct **sentinel** (not Java `null`, which often *can't* be stored and is ambiguous with "absent"). E.g., a singleton `ABSENT` object or an `Optional.empty()` wrapper.
- Use a **short** negative TTL so newly-created items appear quickly (don't tell users "not found" for an hour after they sign up).
- This prevents stampedes on **nonexistent** keys, and it's the cache-layer companion to a **Bloom filter** at the edge that rejects keys that definitely don't exist before they ever reach the cache/DB (defense against *cache penetration* attacks where someone hammers random nonexistent keys).

> **Beginner note — "cache penetration":** An attack/pathology where requests deliberately target keys that don't exist, so they always miss the cache and always hit the DB (because there's nothing to cache). Negative caching + a Bloom filter are the standard defenses.
> **Beginner note — "Bloom filter":** A compact probabilistic set that answers "is X possibly in the set?" with no false negatives and tunable false positives. Put all valid keys in it; if a key isn't in the filter, it definitely doesn't exist → reject without touching cache or DB.

### 3.10 How the four valves compose (the integrated read path)

A hardened hot-cache read, combining everything:

```
read(key):
  entry = cache.get(key)                       // includes value, delta, soft/hard expiry, isNegative
  if entry != null and not pastHardExpiry(entry):
      if entry.isNegative: return ABSENT
      if shouldEarlyRecompute(entry) /*XFetch*/ or pastSoftExpiry(entry) /*SWR*/:
          if tryAcquireRefreshLock(key):       // single-flight / distributed lock
              refreshAsync(key)                // background; serve stale meanwhile
      return entry.value                        // FRESH or STALE-but-usable -> no blocking
  // hard miss (cold start, evicted, or first ever)
  if acquireLoadLock(key):                       // coalesce concurrent hard misses
      v = origin.load(key)
      cache.set(key, v or NEGATIVE, jittered(ttl), withDelta=L)   // jitter + negative + store delta
      releaseLoadLock(key); return v
  else:
      return waitForOrServeStaleOrFail(key)
```

Read path summary: **fresh → serve. Approaching expiry → XFetch may refresh early (one refresher), serve old. Soft-expired → SWR refresh in background (one refresher), serve old. Hard miss → coalesced blocking load with jittered+negative-aware write.** That is stampede protection, fully assembled.

---

## 4. The complete toolkit

This section enumerates the concrete APIs, classes, methods, config flags, and commands you'll actually use, with parameters and defaults. Java/JVM-first, then Redis, then HTTP/CDN.

### 4.1 Caffeine (the de-facto JVM in-process cache)

Caffeine is a high-performance Java caching library (the successor to Guava Cache, used internally by Spring's default cache, Micronaut, etc.). It provides single-flight and SWR-style refresh out of the box.

| API / method | Purpose | Key parameters | Default | Stampede relevance |
|---|---|---|---|---|
| `Caffeine.newBuilder()` | Start building a cache | — | — | entry point |
| `.maximumSize(n)` | Size-bounded eviction (count) | `n` entries | none | controls *eviction*-driven misses |
| `.maximumWeight(w)` + `.weigher(...)` | Weight-bounded eviction | total weight | none | size by bytes, not count |
| `.expireAfterWrite(d)` | Hard TTL from write | `Duration` | none | sets expiry instant |
| `.expireAfterAccess(d)` | TTL from last access | `Duration` | none | idle eviction |
| `.expireAfter(Expiry)` | Custom per-entry TTL | `Expiry` impl | none | **per-key jittered TTL goes here** |
| `.refreshAfterWrite(d)` | **Async** refresh after interval | `Duration` | none | **SWR/background refresh**: returns old value, reloads async |
| `.build(loader)` → `LoadingCache` | Synchronous load-through | `CacheLoader` | — | **single-flight on miss** (coalesces concurrent misses) |
| `.buildAsync(loader)` → `AsyncLoadingCache` | Async load-through | `AsyncCacheLoader` | — | non-blocking single-flight; returns `CompletableFuture` |
| `CacheLoader.reload(k, oldValue)` | Custom refresh logic | old value | calls `load` | hook for SWR refresh behavior |
| `cache.get(key, k -> compute)` | Compute-if-absent, atomic per key | mapping fn | — | **in-process coalescing** even without a LoadingCache |
| `.recordStats()` | Enable hit/miss metrics | — | off | observability of stampede risk |
| `.executor(Executor)` | Where async refresh/eviction run | `Executor` | `ForkJoinPool.commonPool()` | control refresh concurrency |

**Critical Caffeine semantics for stampede:**
- `LoadingCache.get` and `cache.get(k, fn)` guarantee the mapping function runs **at most once concurrently per key** within the JVM — that's built-in single-flight. (Different keys load concurrently.)
- `refreshAfterWrite(d)`: when an entry is read *after* `d` since its last write, Caffeine **returns the current (stale) value immediately** and triggers an **asynchronous** reload. This is SWR. It only triggers on **read** (lazy) — a never-read stale entry isn't refreshed.
- **Combine `refreshAfterWrite` < `expireAfterWrite`** (e.g. refresh at 5 min, expire at 10 min). This gives a "stale-but-usable" window: between refresh and expire you serve stale + reload async; only past `expireAfterWrite` does a read block. If `refreshAfterWrite >= expireAfterWrite`, refresh never kicks in before expiry — a common misconfiguration.

### 4.2 Spring Cache abstraction (when you use `@Cacheable`)

| Element | Purpose | Stampede relevance |
|---|---|---|
| `@Cacheable(sync = true)` | Synchronize concurrent loads of same key | **single-flight**; only with caches that support it (Caffeine yes). Without `sync=true`, `@Cacheable` does **not** coalesce → stampede. |
| `CaffeineCacheManager` | Spring-managed Caffeine | configure `refreshAfterWrite`, etc. via `Caffeine` spec | gets you SWR + single-flight under Spring |
| `@CacheEvict` / `@CachePut` | Invalidate / update | manual invalidation = a deliberate miss → still needs protection on the next read |

> Note: `@Cacheable(sync=true)` coalesces **within one JVM** only. For cross-JVM coalescing you still need a distributed lock around the load.

### 4.3 Redis primitives for distributed coalescing & state

| Command / construct | Purpose | Key options | Stampede use |
|---|---|---|---|
| `SET k v NX PX ms` | Atomic lock acquire with auto-expiry | `NX`, `PX ms`, `EX s` | **distributed lock** acquire |
| `GET` + Lua `DEL`-if-token | Safe lock release | atomic via `EVAL` | ownership-checked release |
| `PEXPIRE` / `PEXPIREAT` | Set ms-precision expiry | — | precise TTL / lock extension |
| `EVAL` / `EVALSHA` | Atomic multi-step logic | Lua script | XFetch test, get-or-set, lock renew — all atomic |
| `TTL` / `PTTL` | Remaining TTL | — | compute how close to expiry (XFetch input) |
| `HSET` / `HGETALL` | Store value + metadata together | fields | store `{value, delta, expiry}` for XFetch in one key |
| `SET k v EX s` (jittered s) | Value write with TTL | `EX`/`PX` | apply **jittered TTL** here |
| `OBJECT IDLETIME` / `OBJECT FREQ` | Inspect access recency/frequency | needs LFU policy | find hot keys |
| `MEMORY USAGE k` | Bytes per key | — | sizing |
| `maxmemory-policy` (config) | Eviction policy | `allkeys-lru`, `allkeys-lfu`, `volatile-ttl`, `noeviction`, … | **`allkeys-lfu` reduces eviction of hot keys**; eviction is a stampede trigger |
| `maxmemory` (config) | Memory cap before eviction | bytes | too low → frequent eviction → more cold misses |
| Keyspace notifications (`notify-keyspace-events Ex`) | Pub/sub on expiry | `E` (events), `x` (expired) | can drive **proactive refresh** on expiry events |

> **Beginner note — LRU vs LFU:** *LRU* (Least Recently Used) evicts whatever wasn't touched for the longest. *LFU* (Least Frequently Used) evicts whatever is requested least often. For stampede protection, **LFU is safer**: it tends to *keep* hot keys (the ones whose eviction would cause the worst herd) and evict cold ones. Redis offers `allkeys-lfu` since 4.0.

### 4.4 Redisson (Java Redis client with high-level objects)

| API | Purpose | Stampede relevance |
|---|---|---|
| `RLock` / `getLock(key)` | Distributed reentrant lock with **watchdog** auto-renewal | distributed coalescing without writing the Lua yourself; watchdog extends lease while held |
| `RLock.tryLock(wait, lease, unit)` | Bounded acquire + explicit lease | losers can fall back instead of blocking forever |
| `RReadWriteLock` | Distributed RW lock | many readers / one writer for refresh |
| `RMapCache` / `RLocalCachedMap` | Map with per-entry TTL; near-cache | `RLocalCachedMap` = L1+L2 tiering (cold-L1 shielded by L2) |
| `RBatch` | Pipeline commands | reduce round-trips during refresh |

> **Beginner note — "watchdog":** Redisson's watchdog is a background timer that periodically extends ("leases") a held lock's TTL (default lease 30s, renewed every 10s) so a long-running holder doesn't have its lock expire mid-work. It auto-stops when you unlock or the client dies. This solves the "lock TTL too short for a slow load" problem in §3.3.

### 4.5 Go `singleflight` (reference for the pattern)

Even in a Java doc this is worth knowing because it's the canonical name:

| API | Purpose |
|---|---|
| `singleflight.Group.Do(key, fn)` | Run `fn` once for `key`; concurrent callers share result (returns `shared bool`) |
| `Group.DoChan(key, fn)` | Same, returns a channel |
| `Group.Forget(key)` | Drop in-flight tracking so next call re-runs (useful after a failed load you don't want to cache) |

### 4.6 HTTP / CDN directives (edge stampede control)

| Directive (Cache-Control) | Meaning | Stampede role |
|---|---|---|
| `max-age=N` | Fresh for N seconds | base TTL |
| `s-maxage=N` | Fresh for N at shared caches/CDN | CDN-side TTL |
| `stale-while-revalidate=N` | Serve stale up to N s while async-refreshing | **SWR at the edge** (RFC 5861) |
| `stale-if-error=N` | Serve stale up to N s if origin errors | **resilience valve** |
| CDN "collapsed forwarding" / "request coalescing" | CDN sends **one** origin request for N concurrent misses on same URL | **distributed single-flight at the edge** (e.g. Varnish *request coalescing*, NGINX `proxy_cache_lock`, Fastly/Cloudflare *origin shield*) |

| Tool/flag | Tool | Purpose |
|---|---|---|
| `proxy_cache_lock on;` | NGINX | only one request populates a cache entry; others wait |
| `proxy_cache_lock_timeout` | NGINX | how long others wait before being allowed to go to origin |
| `proxy_cache_use_stale updating error timeout;` | NGINX | serve stale while updating / on error |
| `proxy_cache_background_update on;` | NGINX | refresh in background, serve stale meanwhile |
| Origin Shield | Fastly/CloudFront/Cloudflare | a single intermediate cache tier so all edge PoPs collapse to one origin fetch |

> **Beginner note — "origin shield":** A designated single cache node (or region) that *all* edge points of presence route their misses through. Instead of 200 edge locations each fetching from origin on a miss, they all hit the shield, which fetches once. It's request coalescing across a CDN's geography.

---

## 5. Code examples by use case

Idiomatic, copy-adaptable Java. Each targets a *different* real scenario.

### 5.1 In-process single-flight with Caffeine `LoadingCache` (simplest correct default)

Scenario: a service computes an expensive per-user dashboard; many concurrent requests for the same user.

```java
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;

public class DashboardCache {

    private final LoadingCache<Long, Dashboard> cache;

    public DashboardCache(DashboardService origin) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(50_000)
            // hard TTL: entry is fully gone after 10 minutes
            .expireAfterWrite(Duration.ofMinutes(10))
            // SWR: after 5 min, the NEXT read returns the OLD value and triggers
            // an ASYNC reload. refresh < expire is what creates the stale-but-usable window.
            .refreshAfterWrite(Duration.ofMinutes(5))
            .recordStats()                              // expose hit/miss for monitoring
            .build(origin::computeDashboard);           // CacheLoader: single-flight per key
    }

    public Dashboard get(long userId) {
        // Caffeine guarantees computeDashboard runs at most once concurrently per userId
        // (in-process single-flight). Concurrent callers for the same id wait for the one load.
        return cache.get(userId);
    }
}
```

Why this is the recommended *first* thing to do: it gives you **single-flight + SWR** in five lines, no locks, no Redis. It only protects *within one JVM*, which is enough when the origin can tolerate `M` (instances) concurrent loads.

### 5.2 Per-key mutex with double-checked locking (manual, fine-grained, no Caffeine)

Scenario: you can't add Caffeine (legacy code, custom store) but must coalesce in-process. Demonstrates the lock map and double-check.

```java
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class LockingCache<K, V> {

    private final ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    // Bounded lock map prevents unbounded growth; striping is acceptable for stampede.
    private final ConcurrentHashMap<K, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Function<K, V> loader;
    private final long lockWaitMs;

    public LockingCache(Function<K, V> loader, long lockWaitMs) {
        this.loader = loader;
        this.lockWaitMs = lockWaitMs;
    }

    public V get(K key) throws InterruptedException {
        V v = store.get(key);
        if (v != null) return v;                       // fast path: hit

        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        // Bounded wait: never block forever on a hung loader.
        if (!lock.tryLock(lockWaitMs, TimeUnit.MILLISECONDS)) {
            // Loser path under contention: serve stale if you have it, else fail fast.
            V stale = store.get(key);
            if (stale != null) return stale;           // someone filled it while we waited
            throw new TimeoutException("load in progress; no stale value");
        }
        try {
            v = store.get(key);                         // DOUBLE-CHECK after acquiring lock
            if (v != null) return v;                    // winner already filled it
            v = loader.apply(key);                      // exactly one loader per key
            store.put(key, v);
            return v;
        } finally {
            lock.unlock();
            locks.remove(key, lock);                    // best-effort cleanup (racy but safe-ish)
        }
    }
}
```

> Note the `locks.remove(key, lock)`: it removes only if the mapped lock is *still* this exact instance, limiting leaks. For very hot keys consider a fixed-size striped lock array (`lock = stripes[key.hashCode() & (N-1)]`) to cap memory at the cost of occasional unrelated-key contention.

### 5.3 Distributed lock with Redis + Lua (cross-process coalescing) — Jedis

Scenario: 20 app instances behind a shared Postgres; a single hot key must be loaded **once across the whole fleet**, not 20×.

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;
import java.util.*;

public class DistributedLoader<V> {

    private static final String RELEASE_LUA =
        "if redis.call('get', KEYS[1]) == ARGV[1] " +
        "then return redis.call('del', KEYS[1]) else return 0 end";

    private final Jedis redis;             // use a pool in real code
    private final long lockTtlMs;          // ~ p99 origin latency + margin
    private final ValueLoader<V> loader;
    private final ValueCodec<V> codec;     // (de)serialize value <-> String/bytes

    public V getOrLoad(String key) throws InterruptedException {
        String cached = redis.get(key);
        if (cached != null) return codec.decode(cached);  // hit

        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();      // proves ownership on release

        // Try to become the sole loader across the fleet.
        String ok = redis.set(lockKey, token, new SetParams().nx().px(lockTtlMs));
        if ("OK".equals(ok)) {
            try {
                // double-check: another process may have filled the cache between our get and lock
                cached = redis.get(key);
                if (cached != null) return codec.decode(cached);

                V value = loader.load(key);               // THE single fleet-wide load
                int jitteredTtl = jitter(300, 0.10);      // e.g. 300s ±10%
                redis.set(key, codec.encode(value), new SetParams().ex(jitteredTtl));
                return value;
            } finally {
                // ownership-checked atomic release
                redis.eval(RELEASE_LUA, List.of(lockKey), List.of(token));
            }
        }

        // Loser path: don't load. Poll briefly for the winner's result, then give up.
        for (int i = 0; i < 5; i++) {
            Thread.sleep(20L + new Random().nextInt(30));  // backoff + jitter
            cached = redis.get(key);
            if (cached != null) return codec.decode(cached);
        }
        // Last resort: serve stale if your design keeps one, else fail/fallback.
        throw new IllegalStateException("could not load " + key + " (winner slow/failed)");
    }

    private int jitter(int base, double fraction) {
        double f = 1.0 + (Math.random() * 2 - 1) * fraction; // base * (1 ± fraction)
        return (int) Math.round(base * f);
    }
}
```

Key points: atomic `NX/PX` acquire, double-check inside the lock, jittered TTL on write, ownership-checked Lua release, bounded loser polling, no loser load.

### 5.4 Probabilistic early recomputation (XFetch) over Redis

Scenario: a single extremely hot key (e.g. the homepage feed) where you want to avoid the expiry cliff entirely without locks. Store `value`, `delta`, and `expiry` together.

```java
import redis.clients.jedis.Jedis;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class XFetchCache<V> {

    private final Jedis redis;
    private final ValueLoader<V> loader;
    private final ValueCodec<V> codec;
    private final long ttlSeconds;        // logical lifetime of a value
    private final double beta;            // 1.0 = proven near-optimal default

    public V get(String key) {
        // Stored as a hash: { v: <encoded>, delta: <ms compute time>, exp: <epoch ms> }
        Map<String, String> e = redis.hgetAll(key);
        long now = System.currentTimeMillis();

        if (!e.isEmpty()) {
            long delta = Long.parseLong(e.get("delta"));   // how long the last compute took
            long exp   = Long.parseLong(e.get("exp"));      // absolute expiry
            // XFetch test: recompute EARLY with rising probability as we near exp.
            // -ln(U(0,1]) is exponential(mean 1); scaled by delta*beta gives a random lead time.
            double gap = delta * beta * -Math.log(ThreadLocalRandom.current().nextDouble());
            if (now - gap < exp) {
                return codec.decode(e.get("v"));            // still serve cached; not yet our turn
            }
            // fall through: this reader was chosen to recompute (old value still in cache for others)
        }

        long start = System.currentTimeMillis();
        V value = loader.load(key);                         // recompute
        long computedDelta = System.currentTimeMillis() - start;
        long newExp = now + ttlSeconds * 1000;

        // Store value + metadata atomically-ish; set a hard expiry a bit beyond logical exp
        Map<String, String> fields = new HashMap<>();
        fields.put("v", codec.encode(value));
        fields.put("delta", Long.toString(computedDelta));
        fields.put("exp", Long.toString(newExp));
        redis.hset(key, fields);
        redis.pexpire(key, ttlSeconds * 1000 + jitterMs()); // physical TTL ≥ logical, jittered
        return value;
    }

    private long jitterMs() { return ThreadLocalRandom.current().nextLong(0, 30_000); }
}
```

Note: XFetch reduces but doesn't *guarantee* single-flight; for the very hottest keys, wrap the `loader.load` in the distributed lock from 5.3 so a rare simultaneous "recompute" roll still collapses to one load.

### 5.5 Serve-stale-while-revalidate with background refresh (explicit soft/hard TTL)

Scenario: an in-memory cache where you want zero reader latency even at expiry, with exactly one background refresher.

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SwrCache<K, V> {

    private record Entry<V>(V value, long softExpiry, long hardExpiry) {}

    private final ConcurrentHashMap<K, Entry<V>> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, AtomicBoolean> refreshing = new ConcurrentHashMap<>();
    private final ExecutorService refreshPool =
        new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),                  // bounded: shed refreshes under overload
            new ThreadPoolExecutor.DiscardPolicy());          // drop extra refreshes (we still serve stale)
    private final Function<K, V> loader;
    private final long softTtlMs, hardTtlMs;

    public V get(K key) {
        Entry<V> e = store.get(key);
        long now = System.currentTimeMillis();

        if (e != null && now < e.hardExpiry()) {
            if (now >= e.softExpiry()) {
                triggerRefresh(key);          // stale-but-usable: refresh async, serve old now
            }
            return e.value();                 // FRESH or STALE: zero-latency return
        }
        // hard miss (cold or fully expired): must load synchronously, coalesced
        return loadBlocking(key);
    }

    private void triggerRefresh(K key) {
        AtomicBoolean flag = refreshing.computeIfAbsent(key, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {                // only ONE refresher wins
            refreshPool.execute(() -> {
                try {
                    V v = loader.apply(key);
                    put(key, v);
                } catch (Exception ex) {
                    // swallow: we already served stale; optionally extend softExpiry (stale-if-error)
                } finally {
                    flag.set(false);
                }
            });
        }
        // losers do nothing — they already have the stale value to return
    }

    private V loadBlocking(K key) {
        // simple per-key coalescing for the cold/hard-miss path
        AtomicBoolean flag = refreshing.computeIfAbsent(key, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            try { V v = loader.apply(key); put(key, v); return v; }
            finally { flag.set(false); }
        }
        // brief wait for the winner, then read
        for (int i = 0; i < 50; i++) {
            Entry<V> e = store.get(key);
            if (e != null) return e.value();
            try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        // last resort
        V v = loader.apply(key); put(key, v); return v;
    }

    private void put(K key, V value) {
        long now = System.currentTimeMillis();
        store.put(key, new Entry<>(value, now + softTtlMs, now + hardTtlMs));
    }
}
```

Highlights: explicit soft/hard TTL, `compareAndSet` as a lock-free single-refresher gate, a **bounded** refresh pool with `DiscardPolicy` (under overload it drops *extra* refreshes rather than queuing forever — safe because stale is still served), and a coalesced hard-miss path for cold starts.

### 5.6 Negative caching + jittered TTL (anti miss-storm + anti mass-expiry)

Scenario: a lookup that often returns "not found," frequently for the same nonexistent keys (e.g. SKU lookups for discontinued items).

```java
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class ProductCache {

    // Cache Optional so we can store "known absent" distinctly from "not cached".
    private final LoadingCache<String, Optional<Product>> cache;

    public ProductCache(ProductRepo repo) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(200_000)
            // Per-entry TTL with jitter, and SHORTER TTL for negatives so new products appear fast.
            .expireAfter(new Expiry<String, Optional<Product>>() {
                public long expireAfterCreate(String key, Optional<Product> val, long t) {
                    long base = val.isPresent() ? Duration.ofMinutes(30).toNanos()   // positive
                                                : Duration.ofSeconds(20).toNanos();  // NEGATIVE: short
                    long jitter = ThreadLocalRandom.current().nextLong(base / 10);    // +0..10% jitter
                    return base + jitter;                                             // breaks mass-expiry
                }
                public long expireAfterUpdate(String k, Optional<Product> v, long t, long cur) { return cur; }
                public long expireAfterRead(String k, Optional<Product> v, long t, long cur) { return cur; }
            })
            .build(sku -> Optional.ofNullable(repo.findBySku(sku)));  // single-flight; caches empty too
    }

    public Optional<Product> find(String sku) {
        return cache.get(sku);    // returns cached Optional.empty() for known-absent SKUs (no DB hit)
    }
}
```

This stops nonexistent SKUs from perpetually hitting the DB (negative caching), and jittered per-entry TTLs prevent a synchronized expiry of the 200k entries.

### 5.7 Atomic get-or-set + lock in a single Redis Lua script (one round-trip)

Scenario: minimize Redis round-trips and races by doing "check cache, else grab lock" atomically server-side.

```lua
-- KEYS[1] = value key, KEYS[2] = lock key
-- ARGV[1] = lock token, ARGV[2] = lock ttl ms
-- Returns: {"HIT", value} | {"LOCK", ""} | {"WAIT", ""}
local v = redis.call('GET', KEYS[1])
if v then return {'HIT', v} end
local got = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', tonumber(ARGV[2]))
if got then return {'LOCK', ''} end   -- caller becomes the loader
return {'WAIT', ''}                    -- caller waits / serves stale
```

The Java caller branches on the returned tag: `HIT` → decode and return; `LOCK` → load from origin, write value, release lock; `WAIT` → backoff-poll or serve stale. Doing the get-and-acquire atomically removes the small race in §5.3 between the `GET` and the `SET NX`.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Per-key, not global, coordination.** Never use one global lock for the whole cache — that serializes *all* loads and turns a stampede into a queue. Lock per key (or striped).
- **Keep the critical section tiny.** Inside the lock: double-check, load, write, release. Never do retries, sleeps, or unrelated I/O while holding it.
- **Async refresh beats sync load.** SWR/`refreshAfterWrite` keep readers off the critical path entirely; readers should almost never block in steady state.
- **Bound everything that can pile up:** refresh thread pools, loser-wait retries, lock-wait timeouts. Under overload, *shed* extra refreshes (serve stale) rather than queue them — queuing recreates the herd with a delay.
- **Pipeline/batch Redis** during refresh (`RBatch`, `MGET`) to cut round-trips.
- **Tiered (L1/L2) caches** dramatically cut both `λ` per node and origin exposure: L1 in-process absorbs most reads; only L1 misses hit L2 (Redis); only L2 misses hit origin. Stampede protection is applied at *each* tier.

### 6.2 Correctness & concurrency

- **Double-check after acquiring any lock.** Always re-read the cache inside the lock/critical section.
- **Distributed locks are best-effort.** For stampede (a perf optimization) that's fine. If the *write* must be exclusive (e.g. compute-and-store must not be done twice for correctness), add a **fencing token** (monotonic counter from the lock service; the origin write rejects stale tokens). Don't over-engineer if double-write is merely wasteful, not wrong.
- **Release locks in `finally`** and only release your *own* lock (token check). A holder that dies must auto-expire (lock TTL).
- **Cache exceptions carefully.** A failed load should usually **not** be cached as a value; but you may briefly negative-cache *known* "not found" results. Caching a transient error long-term turns a blip into an outage.
- **Idempotent loaders.** Because coalescing/retries can re-run loads, the origin load should be idempotent and side-effect-free (it's a read).

### 6.3 Memory

- **Lock maps and in-flight maps must be bounded or self-cleaning.** Striped locks (fixed array) or removal-on-completion (single-flight futures) prevent unbounded growth.
- **Storing `delta`/expiry metadata** for XFetch and soft/hard TTLs adds a few bytes/entry — negligible vs. the value, but account for it at scale (millions of keys).
- **Negative cache entries consume memory too** — bound their count and TTL, especially under a penetration attack (random nonexistent keys could otherwise fill the cache). Pair with a Bloom filter to reject obviously-invalid keys before they create negative entries.

### 6.4 Security

- **Cache penetration / negative-key floods:** an attacker spamming random nonexistent keys bypasses positive caching. Defenses: negative caching with short TTL **plus** a Bloom filter of valid keys at the edge, plus rate limiting per client.
- **Cache poisoning via key collision:** ensure cache keys incorporate *all* inputs that affect the value (auth scope, tenant, locale). A stampede-induced refresh that writes a value computed under the wrong scope poisons the cache for everyone. Namespacing keys by tenant/user prevents cross-tenant leaks.
- **Lock-key squatting:** if lock keys are guessable and writable, an attacker could hold them to cause perpetual "WAIT." Keep coordination keys internal; don't expose lock acquisition to untrusted callers.

### 6.5 Observability (you cannot tune what you can't see)

Track, per cache and ideally per hot key:

- **Hit ratio** and, crucially, **miss rate over time** — a *spike* in misses is a stampede signature.
- **Origin load count / origin QPS attributable to cache misses** — the number you're protecting. A jump here at TTL boundaries is the smoking gun.
- **Concurrent loads per key** (or lock-contention/lock-wait time) — directly measures coalescing effectiveness; should be ~1 even under load.
- **Stale-serve count** and **background-refresh count/failures** — SWR health.
- **Refresh queue depth / discards** — backpressure indicator.
- **Load latency `L` distribution** — feeds lock TTL and XFetch `beta` decisions.
- Caffeine: enable `.recordStats()` → `cache.stats()` gives `hitRate`, `loadCount`, `loadFailureRate`, `averageLoadPenalty`, `evictionCount`. Export via Micrometer.
- Redis: `INFO stats` (`keyspace_hits/misses`, `evicted_keys`, `expired_keys`), `--latency`, slowlog. A surge in `expired_keys` in one second = mass-expiry event.

> **Beginner note — Micrometer:** A vendor-neutral JVM metrics facade (think SLF4J for metrics) that exports to Prometheus, Datadog, etc. Caffeine and Spring integrate with it so you get cache metrics on dashboards with minimal code.

### 6.6 Cost

- Redundant origin loads cost real money: paid-API calls, DB CPU, egress. A single uncoalesced stampede on a metered API can be a surprise bill. Coalescing is often justified on cost alone.
- More aggressive XFetch (`beta>1`) and tighter SWR refresh intervals trade **freshness for more refreshes** — i.e., more origin load even without a stampede. Don't refresh more often than the data actually changes.

### 6.7 Testing

- **Concurrency test:** fire N threads at a freshly-expired key; assert the origin loader was invoked **exactly once** (use an `AtomicInteger` counter in the test loader). This is the canonical stampede-protection unit test.
- **Mass-expiry test:** write 10k keys with the same nominal TTL + jitter; assert expirations spread over the expected window (record expiry timestamps).
- **Chaos/fault injection:** make the loader slow/hang and assert waiters time out and serve stale rather than piling on; kill the lock holder and assert lock auto-expires and another loader takes over.
- **Cold-start test:** flush cache, ramp traffic, assert origin QPS stays under a ceiling.
- Tools: JMH for micro-benchmarks, `awaitility` for async assertions, Toxiproxy to inject latency/partitions on the Redis/DB connection.

### 6.8 Production hardening checklist

- [ ] Hot keys identified and explicitly protected (coalescing + SWR).
- [ ] All TTLs jittered (no synchronized cohorts).
- [ ] Negative results cached with short TTL; Bloom filter for penetration if applicable.
- [ ] Lock TTL ≈ p99 `L` + margin; long loads renew the lease (watchdog).
- [ ] Loser path serves stale or fails fast — **never** loads.
- [ ] Refresh pools bounded; overload sheds refreshes, keeps serving stale.
- [ ] `stale-if-error` window configured so origin outages don't cascade.
- [ ] Eviction policy favors keeping hot keys (LFU); `maxmemory` sized so hot set fits.
- [ ] Warmup + LB slow-start for new/restarted nodes (cold-cache).
- [ ] Metrics: miss-rate, origin-loads, concurrent-loads-per-key, stale-serves, refresh-failures — alerted.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| No coalescing on hot keys | full stampede at every expiry | single-flight / lock / SWR |
| One global lock for the cache | serializes all loads; throughput cliff | per-key / striped locks |
| Identical TTL for all keys | mass-expiry herd | jitter every TTL |
| Caching only positive results | perpetual stampede on nonexistent keys | negative caching |
| Loser thread also loads | defeats the lock | loser serves stale / waits / fails |
| Holding the lock during retries/sleeps | serializes failures into a new herd | tiny critical section; fail outside the lock |
| Unbounded refresh queue | delayed herd + memory blowup | bounded pool + discard, serve stale |
| Lock TTL shorter than load time | premature expiry → double loads | TTL ≈ p99 L + watchdog renewal |
| Treating a distributed lock as a correctness guarantee | rare double-execution corrupts data | fencing tokens *only if* exclusivity is required for correctness |
| Caching transient errors long-term | a blip becomes an outage | don't cache errors, or cache very briefly |
| `@Cacheable` without `sync=true` | no in-JVM coalescing | set `sync=true` (Caffeine) |
| `refreshAfterWrite >= expireAfterWrite` | refresh never fires before expiry | refresh < expire |

---

## 7. Advanced topics & deep internals

### 7.1 XFetch derivation & why `beta=1` is optimal

The Vattani et al. result frames recomputation as minimizing the expected cost of (a) recomputing too early (wasted work) plus (b) the probability-weighted cost of a stampede if you recompute too late. Modeling reads as a Poisson process and the recompute cost proportional to `delta`, the optimal policy is exactly the exponential look-ahead `delta·beta·(−ln U)` test, and the expected cost is minimized near `beta=1` for typical cost ratios. The practical takeaway: the math doesn't require tuning; `beta=1` is robust. Increase `beta` only if you observe occasional cliffs (rare late recomputes); decrease it only if early recomputes are wastefully frequent.

> **Beginner note — Poisson process:** A model of independent random events arriving at a constant average rate (like reads hitting a key). It's the standard assumption for "many independent clients," and it's what makes the probabilistic analysis tractable.

### 7.2 Combining XFetch with single-flight

XFetch decides *when* to recompute and spreads it across readers; single-flight ensures the recompute happens *once* even if two readers roll "go" together. The composition: run the XFetch test; if it says "recompute," enter a single-flight/lock block; inside, double-check whether someone *just* refreshed (compare stored `exp`); if still stale, load. This yields "early, spread-out, and exactly-once" — the gold standard for a single ultra-hot key.

### 7.3 Probabilistic dropping vs. probabilistic recompute

A related idea: under extreme miss pressure, have each *missing* request recompute only with some probability `p` and otherwise fail fast / serve stale, so the herd is statistically thinned. This is cruder than XFetch (it accepts some failures) but trivially cheap and useful as a last-ditch *load-shedding* valve when even your coalescer is saturated.

### 7.4 Mass-expiry math & jitter sizing

If you write `N` keys at time `0` with TTL `T` and uniform jitter `[0, J]`, expirations spread roughly uniformly over `[T, T+J]`, so the *peak* expiry rate ≈ `N/J` keys/sec (vs. `N` in one second with no jitter). Choose `J` so `N/J` × `L` (concurrent loads) stays under origin capacity. Example: `N=1,000,000`, origin can handle `2,000` loads/sec, `L≈0.1s` → you need expiry rate ≤ 20,000/sec → `J ≥ N/20,000 = 50s`. So a 50–60s (or larger, with margin) jitter window tames a million-key cohort. Multiplicative jitter (`±x%`) is usually preferable to additive so the spread scales with TTL.

### 7.5 Negative caching nuances

- **TTL asymmetry:** negative TTL ≪ positive TTL (e.g. 5–30s vs minutes/hours) so newly-created entities surface quickly. But too short → negative-key stampedes return. Tune to "how fast must a newly-created item appear."
- **Invalidate-on-create:** when an entity *is* created, proactively delete or overwrite its negative cache entry so users don't see "not found" until the negative TTL lapses.
- **Distinguish "absent" from "error."** Negative-cache only *authoritative* absences (origin said "definitely no"), never transient failures (timeouts), or you'll cache outages.

### 7.6 Lock leasing, watchdogs, and fencing tokens (deep)

- **Lease renewal (watchdog):** a background timer extends the lock's TTL while the holder is alive and working. Redisson does this by default (30s lease, renew every 10s). It removes the "lock TTL too short for a slow load" failure without making the TTL dangerously long.
- **Fencing token:** a monotonically increasing number issued at lock acquisition; every write to the protected resource includes it, and the resource rejects writes with a token older than the highest it has seen. This makes "two holders due to a pause" *safe*: the stale holder's write is rejected. For stampede protection you rarely need this (double-load is wasteful, not wrong) — include it only when the *write* must be exclusive.

### 7.7 Eviction interactions (the silent stampede trigger)

Stampede protection focuses on TTL, but **eviction** (capacity-driven removal, independent of TTL) is an equally real miss trigger and is often overlooked. If `maxmemory` is too low or the policy evicts hot keys, you get *eviction-induced* misses that look like random stampedes. Mitigations: size memory so the hot working set fits; use **LFU** (`allkeys-lfu`) so hot keys survive; monitor `evicted_keys`. Caffeine's W-TinyLFU admission policy is specifically designed to keep high-frequency entries and resist one-hit-wonder pollution — a good default for the same reason.

> **Beginner note — W-TinyLFU:** Caffeine's eviction algorithm. It uses a tiny frequency sketch to decide whether a *newly arriving* item deserves to displace an existing one, strongly favoring keeping frequently-used (hot) entries. This directly reduces eviction-driven stampedes on hot keys.

### 7.8 Multi-tier (near-cache) stampede dynamics

With L1 (in-process) + L2 (Redis) + origin:

- A **single key's** L1 expiry causes at most one L2 fetch per JVM; L2 absorbs it (L2 is warm). Origin sees nothing.
- An **L2 (or origin) refresh** is coalesced by a distributed lock so the whole fleet does one origin load.
- A **cold L1** (restart) is shielded by warm L2 — the most important reason near-caches dramatically cut origin stampede exposure.
- Danger: **synchronized L1 expiry across the fleet** (all JVMs deployed together, same TTL) can still hit L2 hard simultaneously. Jitter L1 TTL per-instance, and rely on L2 coalescing.

### 7.9 Request coalescing at the CDN/proxy tier

At the edge, the "loader" is the origin fetch and the "cache key" is typically the URL. NGINX `proxy_cache_lock on` + `proxy_cache_background_update on` + `proxy_cache_use_stale updating` gives you single-flight + SWR for HTTP. CDNs add **origin shield** (geographic coalescing). The same four valves, just at a different layer — and they stack: edge coalescing + app-tier coalescing + DB-tier nothing-left-to-stampede.

### 7.10 Interaction with consistency requirements

SWR and XFetch deliberately serve **stale** data. That's unacceptable for strongly-consistent reads (e.g., "show the balance immediately after a transfer"). For such reads, bypass the cache or use **explicit invalidation + write-through**, accepting that those (rarer) reads forgo stampede protection or use short, coalesced loads. Decide per-key/per-endpoint how much staleness is acceptable; stampede protection's freshness cost must be a conscious choice.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Technique comparison

| Technique | Solves | Reader latency at expiry | Origin loads per expiry | Cross-process? | Serves stale? | Complexity | Main risk |
|---|---|---|---|---|---|---|---|
| In-process mutex / single-flight | hot-key herd (per JVM) | one wait per JVM | 1 per JVM | No | No (unless added) | Low | M JVMs → M loads |
| Distributed lock (Redis) | hot-key herd (fleet) | one wait fleet-wide | 1 fleet-wide | Yes | No (unless added) | Medium | lock safety edge cases |
| XFetch (probabilistic early) | hot-key herd, no cliff | ~0 (serve cached) | ~1, spread in time | per-reader, decentralized | implicitly (still-valid) | Medium | needs `delta` metadata; rare double-roll |
| Serve-stale + bg refresh (SWR) | hot-key herd, zero reader wait | ~0 (serve stale) | 1 (the refresher) | with distributed refresh lock | **Yes** | Medium | staleness; stale-forever if origin down (bound it) |
| Jittered/staggered TTL | mass-expiry / cold cohort | n/a | smears peak rate | n/a | No | Very low | doesn't help single hot key |
| Negative caching | miss-storm on absent keys | ~0 | avoids repeated loads | yes (shared cache) | n/a | Low | stale "not found"; tune TTL |
| Warmup + slow start | cold-cache herd | n/a | gradual | n/a | No | Medium | operational complexity |

### 8.2 Use-when / avoid-when

- **In-process single-flight (Caffeine):** *Use when* the origin tolerates `M` concurrent loads (M = instances) and you want the simplest correct fix. *Avoid when* M is large and origin is fragile → add distributed lock or SWR.
- **Distributed lock:** *Use when* even M concurrent loads is too much for the origin, or loads are very expensive/metered. *Avoid when* the operational cost of Redis coordination outweighs the benefit, or when stale-serving already shields the origin (prefer SWR).
- **XFetch:** *Use when* you have a few ultra-hot, expensive keys and want to eliminate the expiry cliff without locks/extra round-trips. *Avoid when* you can't store compute-time metadata, or you need strict single-flight (then pair with a lock).
- **SWR / background refresh:** *Use when* slight staleness is acceptable and zero reader latency is the priority (most read-heavy web caches). *Avoid when* reads must be strongly consistent.
- **Jittered TTL:** *Use always* — it's nearly free and prevents an entire failure class. *Avoid* only if you genuinely need synchronized expiry (rare).
- **Negative caching:** *Use when* "not found" is common or attackable. *Avoid* / shorten when newly-created items must appear instantly.

### 8.3 Decision flow (text)

1. Are many keys written together with the same TTL? → **Jitter TTLs** (do this regardless).
2. Is there a small set of very hot keys? → add **single-flight** (in-process first).
3. Does the origin still get too much load (many instances / very fragile origin)? → add **distributed lock** or, better, **SWR** so readers serve stale and only one background task loads.
4. Are a few keys both hot *and* you want zero cliff/latency? → **XFetch**, optionally + lock.
5. Do "not found" results cause repeated loads? → **negative cache** (+ Bloom filter if attacked).
6. Does the cache go cold on deploy/restart/scale? → **warmup + LB slow start + tiered cache**.

### 8.4 Stampede protection vs. alternatives that reduce need for it

- **Never-expiring + explicit invalidation:** keep values forever, refresh only on write events (CDC / pub-sub). Eliminates TTL-driven stampede entirely but needs reliable invalidation and risks unbounded staleness on missed events.
- **Write-through / write-behind:** the cache is updated on writes, so reads rarely miss. Reduces stampede surface but doesn't help cold start or eviction.
- **Bigger/faster origin:** raising `L` capacity reduces the *consequences* but not the correlated burst; expensive and doesn't fix the feedback loop. Stampede protection is far cheaper.

> **Beginner note — CDC:** *Change Data Capture* streams a database's changes (inserts/updates/deletes) as events (e.g. via Debezium/Kafka). Caches can subscribe to invalidate or refresh affected keys exactly when data changes, instead of relying on TTL expiry — removing a major stampede trigger.

---

## 9. Failure modes & debugging

### 9.1 Signatures: how to recognize a stampede in production

- **Sawtooth on origin QPS** synchronized with TTL boundaries (spikes every `T` seconds/minutes).
- **Cliff at a round time** (e.g. exactly one hour after a deploy) → mass-expiry from synchronized TTLs.
- **Cache miss-rate spike** coinciding with a **DB CPU/connection-pool saturation** spike.
- **Latency multimodal:** most requests fast (hits), a cluster of very slow ones (the herd waiting on the overloaded origin).
- **DB "active query" count** shows many *identical* queries running simultaneously.
- **Metastable behavior:** origin stays pegged even after the traffic spike that triggered it subsides → positive feedback loop.

### 9.2 Diagnostic tools & commands

- **Application metrics (Micrometer/Prometheus):** plot `cache_misses`, `cache_loads`, `cache_load_seconds`, and origin QPS together. The correlation pinpoints stampede.
- **Caffeine:** `cache.stats()` → check `loadCount` jumping far above `missCount`-expected; `averageLoadPenalty` rising (origin slow under herd). Per-key concurrent loads need custom instrumentation (counter in the loader keyed by current loads).
- **Redis:** `redis-cli INFO stats` → watch `expired_keys` (sudden batch = mass-expiry), `evicted_keys` (eviction-driven misses), `keyspace_misses`. `redis-cli --latency` and `SLOWLOG GET` for Redis-side slowness. `redis-cli --hotkeys` (needs LFU) to find the hottest keys (likely stampede culprits). `MONITOR` (briefly, it's costly) to see the duplicate `GET`/`SET` flood.
- **Database:** `SELECT * FROM pg_stat_activity` (Postgres) to see many identical concurrent queries; `SHOW PROCESSLIST` (MySQL). Connection-pool metrics (HikariCP `pending`/`active`) pegged.
- **Distributed tracing:** a trace fan-out where dozens of spans all call the same origin op at the same timestamp is a coalescing failure.
- **Thread dumps:** many threads blocked on the same `ReentrantLock`/Redis `GET` is expected (waiters); many threads *in the origin call* simultaneously is the bug (no coalescing).

### 9.3 Concrete remediation during an incident

1. **Manually warm the key(s):** set the hot key directly in the cache to break the empty-cache feedback loop immediately. This is the fastest way to stop an in-progress stampede.
2. **Extend TTL temporarily** on hot keys so they stop expiring while you fix the loader path.
3. **Shed load:** rate-limit or reject a fraction of requests to let the origin recover (breaks metastability).
4. **Lower origin concurrency** (cap pool) so the herd queues at the app, not the DB — protects the DB from collapse.
5. Then deploy the real fix (coalescing/SWR/jitter).

### 9.4 Real-world incident patterns (representative, anonymized)

- **The hourly cliff:** a fleet warmed millions of config keys at boot with `ttl=3600`. Every hour on the hour the DB spiked to 100% and latency tripled for ~30s. Fix: ±10% multiplicative TTL jitter → cliff became a gentle ramp; problem gone. (Mass-expiry; classic.)
- **The deploy stampede:** rolling deploy emptied each instance's in-process cache; the new instance took full traffic with a cold cache and hammered the DB. Fix: tiered cache (warm Redis L2 shielded cold L1) + LB slow-start ramp. (Cold-cache herd.)
- **The popular-item dogpile:** a flash sale made one product page TTL-expire under 50k req/s with a 300ms origin load → ~15k concurrent identical queries → DB meltdown. Fix: `@Cacheable(sync=true)` (in-JVM single-flight) + distributed lock across the fleet + SWR. (Single hot-key herd.)
- **The 404 flood:** scrapers requested random nonexistent product IDs; every request missed and hit the DB. Fix: negative caching (20s TTL) + Bloom filter of valid IDs at the edge. (Cache penetration / miss-storm.)
- **The lock that expired mid-load:** distributed lock TTL was 2s but p99 load was 4s; the lock expired, a second loader started, both wrote — load doubled, not eliminated. Fix: watchdog lease renewal (Redisson) and TTL set to p99 + margin. (Lock misconfiguration.)
- **Memcached/Facebook leases (historical, well-documented):** Facebook's memcached used a *lease* mechanism — on a miss, memcached hands exactly one client a "lease token" to fetch and set; others get told to wait/retry or are served a slightly stale value. This is single-flight implemented *inside* the cache server, plus stale-serving — an industrial reference design for stampede control at scale.

---

## 10. Interview drill

**Q1. What is a cache stampede and why is it dangerous?**
Model answer: When a hot cached key expires/evicts, many concurrent requests miss simultaneously and each independently loads from the origin, turning the cache from a load *absorber* into a load *amplifier*. Dangerous because the misses are correlated (all in one window ≈ origin latency), the redundant load count ≈ `λ × L`, and it can trigger a metastable feedback loop that takes down an origin that was idle moments earlier — and it hits hardest on your most popular keys.
- Probe: *Why correlated, not just "high load"?* Because expiry is a single instant; everyone misses together, so the origin sees N concurrent identical queries, not N spread over time.
- Probe: *What makes it metastable?* The empty cache + overloaded origin feed each other: slow loads keep the cache empty longer, which causes more misses, which slows the origin more; it persists after the trigger.
- Probe: *Estimate redundant loads.* `λ × L`; e.g. 5000 req/s × 0.2s = ~1000.

**Q2. Walk me through single-flight / request coalescing.**
Model answer: Maintain a per-key map of in-flight loads. The first caller for a key starts the load and registers a shared future; concurrent callers find the future and attach to it, receiving the same result. The future is removed when done. N origin loads collapse to 1. Caffeine's `LoadingCache.get` and `@Cacheable(sync=true)` do this in-process for free.
- Probe: *Lock vs. single-flight difference?* With a lock, waiters re-read the cache after acquiring (double-check); with single-flight they receive the future's value directly. Single-flight self-cleans memory; lock maps need bounding.
- Probe: *Why double-check after the lock?* The value may have been filled while you waited; without re-checking, you'd redundantly reload.
- Probe: *Scope limit?* In-process only → M instances → up to M loads → need a distributed lock for fleet-wide coalescing.

**Q3. How does probabilistic early recomputation (XFetch) work and why is it elegant?**
Model answer: Store the compute time `delta` with each value. On read, compute `now − delta·beta·ln(rand())` and if it ≥ expiry, recompute *early* while still serving the valid cached value; else serve cached. As you near expiry the probability of being chosen rises, so someone almost surely refreshes before the cliff — decentralized, no locks, no extra round-trips. `beta=1` is near-optimal.
- Probe: *Why scale by `delta`?* Expensive values get a longer random lead time → refreshed earlier, giving more headroom.
- Probe: *Does it guarantee single-flight?* No — two readers can roll "go" together; pair with a lock for the hottest keys.
- Probe: *What does `beta` tune?* Higher = earlier/more aggressive refresh (fresher, more waste); lower = later/riskier.

**Q4. Explain serve-stale-while-revalidate and its risks.**
Model answer: Use soft and hard TTLs. Before soft TTL, serve fresh. Between soft and hard, serve the stale value *immediately* and trigger a single async refresh. Only past hard TTL do reads block. Readers never wait in steady state; the origin sees one refresher. Risk: serving stale data (unacceptable for strong consistency) and "stale forever" if the origin is down past hard TTL — bound it and use `stale-if-error`.
- Probe: *Caffeine equivalent?* `refreshAfterWrite` (must be < `expireAfterWrite`); the first read after the interval returns old value and reloads async.
- Probe: *How ensure one refresher?* A `compareAndSet` gate or distributed refresh lock per key.
- Probe: *When NOT to use?* Strongly-consistent reads (e.g. balance after a transfer).

**Q5. What is mass-expiry / correlated expiry and how do you fix it?**
Model answer: Many *different* keys written at the same time with the same TTL all expire in the same instant, creating a fleet-wide burst across many keys. Coalescing doesn't help (different keys). Fix with TTL jitter (additive or multiplicative ±x%) so expirations smear over a window; peak expiry rate drops from `N` to ≈ `N/J`.
- Probe: *Size the jitter.* `J ≥ N / (origin_load_capacity_per_sec)`, accounting for `L`. E.g. 1M keys, 20k/s tolerable → J ≥ 50s.
- Probe: *Where else to jitter?* Warmup batches, lock retries (backoff jitter).
- Probe: *Additive vs multiplicative?* Multiplicative scales with TTL and is usually preferable.

**Q6. (Senior signal) You have 30 app instances and a fragile shared DB. A few keys are extremely hot. Walk me through your design and justify each choice.**
Model answer: Layer defenses. (1) Jitter all TTLs — free, prevents cohorts. (2) Tiered cache: in-process L1 (Caffeine, single-flight + `refreshAfterWrite`) backed by Redis L2 — L1 absorbs most reads and shields the DB even on restart via warm L2. (3) SWR at L2 with a *distributed* refresh lock so the whole fleet does one origin load per refresh, and readers serve stale (zero latency). (4) For the very hottest keys, XFetch to remove the cliff, paired with the lock. (5) Negative caching + Bloom filter if "not found" traffic exists. Justification: in-process single-flight alone gives 30 concurrent loads (too many for a fragile DB); SWR + distributed lock collapses that to 1 while keeping reader latency ~0; XFetch prevents even the brief blocking refresh on the elite keys. I'd avoid relying on the distributed lock for correctness (it's best-effort) since here it's only a perf optimization.
- Probe: *Why not just a bigger DB?* Doesn't fix correlated bursts or the feedback loop; far more expensive than protection.
- Probe: *Lock TTL?* ≈ p99 load + margin, with watchdog renewal for long loads.
- Probe: *How verify it works?* Concurrency test asserting exactly-one load; production metric "concurrent loads per key" ≈ 1; origin QPS flat across TTL boundaries.

**Q7. (Senior signal) When would you deliberately NOT add stampede protection, and what's the cost of over-engineering it?**
Model answer: Skip it when the value is cheap to compute, when each request targets a distinct key (no shared hot key to coalesce), when the cache never empties, or when strong consistency forbids stale-serving. Over-engineering costs: distributed locks add latency and a coordination dependency (Redis becomes a SPOF for the read path); aggressive SWR/XFetch increase origin load even without stampedes (more refreshes than the data changes); complexity makes the cache harder to reason about and debug. Match the mechanism to the actual risk: hot + slow + shared key + inevitable miss.
- Probe: *Cheapest universally-worth-it measure?* TTL jitter — nearly free, prevents a whole failure class.
- Probe: *Risk of distributed lock as read-path dependency?* If Redis is slow/down, reads stall on lock acquisition — prefer SWR (serve stale) so a coordination hiccup degrades gracefully.

**Q8. (Senior signal) Distributed locks for stampede protection are "unsafe" per Kleppmann. Do you care?**
Model answer: For *stampede protection*, mostly no — the lock is a performance optimization, not a mutual-exclusion correctness requirement. If a GC pause or failover occasionally lets 2 loaders through instead of 1, the herd is still cut from thousands to 2 (a huge win), and the loaded value is the same (idempotent read). I'd only add fencing tokens / Redlock-grade rigor if the *write* must be exclusive for correctness (e.g., compute-and-persist that must not double-apply). So I accept a best-effort lock here and pair it with stale-serving so even a lock failure degrades gracefully.
- Probe: *What's a fencing token?* A monotonic number issued at acquire; the resource rejects writes carrying a token older than the max it has seen — makes "two holders" safe.
- Probe: *Mitigate the pause problem?* Lease/watchdog renewal and TTL ≈ p99 load + margin; still treat it as best-effort.

**Q9. How do you handle "not found" results to avoid stampedes?**
Model answer: Negative caching — store a sentinel for authoritative absences with a *short* TTL (5–30s) so repeated requests for nonexistent keys don't hit the origin, while newly-created items appear quickly. Distinguish "absent" from "transient error" (never cache errors). For attack traffic of random nonexistent keys (cache penetration), add a Bloom filter of valid keys at the edge plus per-client rate limiting.
- Probe: *Why short TTL?* So a just-created entity isn't reported "not found" for long; balance vs. re-enabling negative-key stampedes.
- Probe: *Bloom filter false positives?* It can say "maybe present" for an absent key (passes through to cache/DB), but never "absent" for a present one — safe; tune size for acceptable false-positive rate.

**Q10. What metrics tell you stampede protection is working (or failing)?**
Model answer: Concurrent loads per key (~1 means coalescing works), origin QPS attributable to misses (flat across TTL boundaries = good), miss-rate (no synchronized spikes), stale-serve and background-refresh counts/failures (SWR health), refresh-queue depth/discards (backpressure), and load-latency distribution (feeds lock TTL/`beta`). In Redis: `expired_keys` spikes signal mass-expiry; `evicted_keys` signal eviction-driven misses.
- Probe: *Single most diagnostic metric?* Origin loads (or DB identical-query count) correlated with TTL boundaries.
- Probe: *Catch mass-expiry specifically?* A spike in `expired_keys` within one second.

**Q11. Describe how a CDN/proxy prevents stampedes at the edge.**
Model answer: Request coalescing/collapsed forwarding — concurrent misses for the same URL are merged into one origin fetch (NGINX `proxy_cache_lock`, Varnish request coalescing). SWR via `stale-while-revalidate` and `proxy_cache_background_update` serves stale while refreshing; `stale-if-error` serves stale on origin failure. Origin shield collapses all edge PoPs' misses through one node. Same four valves, applied at the HTTP layer, and they stack with app-tier protection.
- Probe: *NGINX directives?* `proxy_cache_lock on`, `proxy_cache_lock_timeout`, `proxy_cache_use_stale updating error`, `proxy_cache_background_update on`.
- Probe: *Origin shield benefit?* One origin fetch instead of one-per-PoP on a miss.

**Q12. Your in-process single-flight works in tests but the DB still spikes in prod. Why?**
Model answer: Single-flight is per-JVM; with many instances you still get up to M concurrent loads, and at scale M can overwhelm a fragile DB. Also possible: synchronized TTLs across instances (mass-expiry), eviction (not TTL) causing misses, `@Cacheable` missing `sync=true`, or `refreshAfterWrite >= expireAfterWrite` so SWR never fires. Add fleet-wide coalescing (distributed lock) or SWR with a distributed refresh lock, jitter TTLs per instance, and verify config.
- Probe: *How confirm it's M-load and not no-coalescing?* Metric: loads-per-key per JVM ≈1 but total ≈ M → it's the M-instance fan-out.
- Probe: *Fix without Redis lock?* Tiered cache so warm L2 shields the DB, plus SWR serving stale.

---

## 11. Glossary

- **Cache stampede / dogpile / thundering herd:** Many concurrent requests missing a hot cache key at once and all loading the origin simultaneously.
- **Origin / backend / source of truth:** The slow/expensive system the cache fronts (DB, service, API, computation).
- **Cache-aside (look-aside / lazy loading):** App reads cache; on miss, reads origin and populates cache itself.
- **Read-through:** The cache library loads from the origin on a miss for you.
- **Write-through / write-behind:** Writes go through the cache to the origin (synchronously / asynchronously).
- **TTL (time to live):** How long a cached entry is valid before expiry.
- **Soft TTL / hard TTL:** Soft = becomes stale-but-usable; hard = fully removed. Used by SWR.
- **Eviction:** Capacity-driven removal of entries (independent of TTL), e.g. LRU/LFU.
- **LRU / LFU:** Least Recently / Least Frequently Used eviction policies. LFU keeps hot keys.
- **W-TinyLFU:** Caffeine's admission/eviction algorithm favoring high-frequency entries.
- **Vulnerability window (`W`):** Interval from first miss to first write-back; ≈ origin latency `L`; herd forms within it.
- **Mutex / per-key mutex:** Mutual-exclusion lock; per-key = one lock per cache key.
- **Double-checked locking:** Check cache, lock, check again inside the lock.
- **Single-flight / request coalescing:** Deduplicate concurrent identical loads into one; fan out the result.
- **Distributed lock:** Cross-process mutual exclusion (e.g. Redis `SET NX PX`).
- **Redis:** In-memory key-value store; single-threaded command loop makes commands atomic.
- **Lua (in Redis):** Server-side scripts run atomically; used for safe lock release and atomic get-or-set.
- **NX / PX / EX:** Redis `SET` options — set-if-not-exists / TTL in ms / TTL in seconds.
- **Redlock:** Multi-node Redis locking algorithm for fault tolerance (correctness debated).
- **Fencing token:** Monotonic number making "two lock holders" safe by rejecting stale writes.
- **Watchdog / lease renewal:** Background extension of a held lock's TTL while the holder works (Redisson default).
- **XFetch / probabilistic early recomputation (PER):** Randomized early refresh based on compute time and proximity to expiry; `beta` tunes aggressiveness.
- **`beta`:** XFetch tuning constant; `1` is near-optimal; higher = earlier refresh.
- **`delta`:** Stored compute time of a value, used by XFetch.
- **Serve-stale-while-revalidate (SWR):** Serve the stale value immediately while a single background task refreshes.
- **`stale-if-error`:** Serve stale when the origin errors (resilience valve).
- **Background / proactive refresh:** Refresh entries before they expire, off the read path.
- **Jittered / staggered TTL:** Randomized TTLs to avoid synchronized expiry.
- **Mass-expiry / correlated / synchronized expiry:** Many keys expiring together due to identical TTLs.
- **Cold-cache herd:** Empty cache (restart/flush/scale) causing widespread misses.
- **Warmup / pre-population:** Loading hot keys before taking traffic.
- **Slow start (LB):** Ramping traffic to a new node gradually.
- **Tiered / near cache (L1/L2):** In-process L1 backed by shared L2 (Redis), shielding the origin.
- **Negative caching:** Caching the absence of a value (short TTL) to stop repeated origin loads for missing keys.
- **Cache penetration:** Requests for nonexistent keys that always miss and hit the origin.
- **Bloom filter:** Compact probabilistic set; no false negatives, tunable false positives; rejects definitely-absent keys.
- **Cache poisoning:** Storing a wrong/wrongly-scoped value so all readers get bad data.
- **Metastable failure:** A self-sustaining broken state that persists after the trigger is gone.
- **Poisson process:** Model of independent events at a constant average rate; underpins XFetch analysis.
- **Caffeine:** High-performance JVM cache (single-flight + `refreshAfterWrite` built in).
- **Redisson:** Java Redis client with high-level distributed objects (`RLock`, near-caches).
- **Micrometer:** Vendor-neutral JVM metrics facade.
- **CDC (Change Data Capture):** Streaming DB changes as events to drive cache invalidation/refresh.
- **Origin shield:** A single CDN cache tier through which all edge PoPs' misses are funneled.
- **Collapsed forwarding / request coalescing (CDN/proxy):** Merging concurrent identical origin requests into one at the edge.
- **`refreshAfterWrite` / `expireAfterWrite` (Caffeine):** Async-refresh interval / hard-expiry interval; refresh must be < expire.
- **`@Cacheable(sync=true)` (Spring):** Enables in-JVM single-flight for the annotated method.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The event:** hot key expires/evicts → concurrent misses → N identical origin loads → metastable overload.
**Redundant loads ≈ `λ × L`** (rate × origin latency). Reduce by shrinking the vulnerability window `W ≈ L` to ~0, or removing the synchronized miss.

**The four valves (memorize):**
1. **One refiller** — single-flight / per-key mutex / distributed lock. In-process: Caffeine `LoadingCache.get`, `@Cacheable(sync=true)`. Cross-process: Redis `SET NX PX` + Lua release (best-effort; watchdog for long loads).
2. **Refill early, never block readers** — SWR (soft/hard TTL, serve stale + async refresh; Caffeine `refreshAfterWrite < expireAfterWrite`) and XFetch (`now − delta·beta·ln(rand) ≥ expiry`, `beta=1`).
3. **Stagger refills** — jitter every TTL (±10% multiplicative typical); sizes peak expiry to ≈ `N/J`.
4. **Handle absence** — negative caching (short TTL, sentinel, distinguish from errors) + Bloom filter for penetration.

**Two stampede types:** single hot-key (→ coalesce/SWR/XFetch) vs. mass-expiry/cold-cache (→ jitter + warmup + tiered cache).

**Key defaults/numbers:** XFetch `beta=1`; negative TTL ~5–30s; TTL jitter ~±10%; lock TTL ≈ p99 `L` + margin (renew if long); Redisson watchdog 30s lease / 10s renew; eviction policy `allkeys-lfu` to keep hot keys.

**Anti-patterns:** global lock; identical TTLs; loser-also-loads; loading inside long-held lock; unbounded refresh queue; lock TTL < load time; caching transient errors; `@Cacheable` without `sync=true`; `refreshAfterWrite ≥ expireAfterWrite`.

**Diagnose:** origin QPS sawtooth at TTL boundaries; miss-rate spike + DB saturation; `expired_keys`/`evicted_keys` spikes (Redis); many identical concurrent queries (`pg_stat_activity`); metric "concurrent loads per key" should be ≈1.

**Incident fixes (fast → durable):** manually warm key → extend TTL → shed load / cap origin concurrency → deploy coalescing/SWR/jitter.

**Decision one-liner:** Protect when hot **and** slow **and** shared key **and** miss inevitable; jitter TTLs always; otherwise match the mechanism to the actual risk.

### 12.2 Self-test (no answers — recall practice)

1. Derive the expected number of redundant origin loads in a single stampede and explain what each factor implies for mitigation.
2. Your service has 40 instances and a fragile DB; in-process single-flight is already enabled but the DB still spikes on hot keys. Design the next two layers of protection and justify each.
3. Write the XFetch test condition from memory, explain why it scales by `delta`, and state what `beta` controls and its default.
4. You must guarantee zero added reader latency even at expiry. Which technique do you choose, how do you ensure exactly one refresher, and what new risk have you introduced — how do you bound it?
5. A dashboard shows the DB CPU spiking to 100% exactly one hour after every deploy. Name the failure mode, the root cause, and the precise fix (including how you'd size any parameter).
6. Explain why a distributed lock used for stampede protection can be "best-effort" and still be a good design, and the one condition under which you would instead require fencing tokens.
7. Describe how negative caching and a Bloom filter together defend against a flood of requests for nonexistent keys, including the failure each one alone would still allow.
8. List the metrics you would alert on to detect a stampede, and for each say what value indicates "protection is working."

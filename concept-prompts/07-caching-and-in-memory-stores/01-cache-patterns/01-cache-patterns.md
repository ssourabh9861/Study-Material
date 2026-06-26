# Cache Patterns

> An exhaustive engineering-handbook chapter on **caching patterns** — cache-aside, read-through, write-through, write-behind, and refresh-ahead — built up from first principles to deep internals, with idiomatic Java examples using Caffeine (local) and Redis (distributed).

---

## 1. Overview & where it fits

### What a cache is

A **cache** is a secondary copy of data, kept in a faster-to-access location than the **system of record (SoR)** — the authoritative, durable store that owns the truth for that data (usually a relational database like PostgreSQL/MySQL, a NoSQL store like Cassandra/DynamoDB, or a downstream service/API). The cache trades **freshness and durability** for **latency and throughput**: reads served from the cache avoid a slow trip to the SoR, but the cached copy may be stale, and it can vanish at any moment (process restart, eviction, node loss) without data loss because the SoR still holds the truth.

> **System of record (SoR):** the durable, authoritative source for a piece of data. If the cache and the SoR disagree, the SoR wins. A cache must never be the only place a write lives unless you have explicitly accepted that risk.

### The problem caches solve

1. **Latency.** RAM access is ~100 ns; a local in-process map lookup is tens of ns. A network round-trip to a distributed cache (Redis) on the same LAN is ~0.2–1 ms. A query to a relational database — parse, plan, execute, fetch over the network — is typically **1–50 ms** and can spike to seconds under load. Caching collapses repeated work into a single fast hop.
2. **Throughput / load shedding.** Databases have finite connections and CPU. A cache absorbs the **read amplification** of hot keys so the SoR sees only cache misses, not every request.
3. **Cost.** A cache hit avoids CPU, I/O, and (in the cloud) per-request or per-IOPS billing on the SoR. Serving 95% of reads from RAM is dramatically cheaper than scaling the database to the same QPS.
4. **Resilience.** A cache can serve stale-but-usable data when the SoR is degraded (a deliberate "serve stale on error" strategy).

> **Read amplification:** when one logical user action causes many reads of the same data. A product page viewed a million times a minute is a million reads of one row; a cache turns that into ~one DB read per TTL window.

> **QPS (queries per second) / RPS (requests per second):** the rate of operations hitting a system. Hot keys can push a single row to tens of thousands of QPS.

### What a *cache pattern* is

A **cache pattern** is the **interaction protocol** that defines *who* talks to the cache and the SoR, *in what order*, and *who is responsible* for keeping them consistent. The five canonical patterns differ along two axes:

- **Who orchestrates** — your application code (you call cache and DB explicitly) vs. the cache library/provider (you call only the cache; it talks to the DB for you).
- **Read path vs. write path** — how a miss is filled, and how a write propagates to cache and SoR.

The five patterns this chapter covers:

| Pattern | Read path | Write path | Who orchestrates |
|---|---|---|---|
| **Cache-aside (lazy)** | App checks cache → on miss, app loads from DB, app populates cache | App writes DB, then invalidates/updates cache | **Application** |
| **Read-through** | App asks cache; cache loads from DB on miss transparently | (read-only concern) | **Cache provider** |
| **Write-through** | (read concern) | App writes to cache; cache synchronously writes to DB before returning | **Cache provider** |
| **Write-behind (write-back)** | (read concern) | App writes to cache; cache asynchronously flushes to DB later | **Cache provider** |
| **Refresh-ahead** | Cache proactively reloads hot entries *before* they expire | (read concern, proactive) | **Cache provider** |

Read-through and write-through/-behind are often combined into a **read-through + write-through cache** (a single component sitting in front of the DB). Cache-aside is the odd one out: the application stays in control and the cache is a "dumb" key-value store.

### When you reach for caching at all

- The same data is **read far more often than it is written** (read-heavy workload).
- Reads are **expensive** (complex joins, aggregations, downstream API calls) relative to the cost of storing the result.
- Some **staleness is tolerable** — or you have a precise invalidation strategy.
- There is **temporal or spatial locality** — recently/related data is likely to be requested again soon.

You do *not* cache when: data is written as often as read, every read must be strictly fresh (e.g., a bank balance at the moment of a transfer), or the working set is so large and uniformly accessed that the hit rate would be near zero (no locality → cache is pure overhead).

### One-paragraph mental model

Think of a cache as a **fast notebook** next to a **slow filing cabinet (the SoR)**. The *pattern* is the office rule for who writes in the notebook and when. **Cache-aside:** the worker (your app) checks the notebook; if it's blank, they go to the cabinet, read it, and copy it into the notebook themselves — and when they change something, they update the cabinet and cross out the notebook entry. **Read-through/write-through:** there's an assistant (the cache library) standing between the worker and the cabinet — the worker only ever talks to the assistant, who silently fetches from or writes to the cabinet. **Write-behind:** the assistant accepts your change into the notebook instantly and promises to update the cabinet later (faster for you, riskier if the assistant faints). **Refresh-ahead:** the assistant notices a notebook entry is about to expire and quietly refreshes it from the cabinet before anyone asks, so the worker never waits.

---

## 2. Foundations from first principles

Before the patterns, we need the vocabulary and the physics. Every term is defined as it appears.

### 2.1 The memory/latency hierarchy

Caching exists because access cost is wildly non-uniform. Approximate, order-of-magnitude latencies (Jeff Dean's "numbers every programmer should know," updated):

| Operation | Latency | Relative |
|---|---|---|
| L1 cache reference | ~1 ns | 1× |
| Main memory (RAM) reference | ~100 ns | 100× |
| In-process hash map get | ~20–100 ns | ~100× |
| SSD random read | ~16 µs | ~16,000× |
| Same-datacenter network round trip | ~0.5 ms | ~500,000× |
| Redis GET over LAN | ~0.2–1 ms | ~200,000–1,000,000× |
| Simple indexed DB query | ~1–10 ms | millions× |
| Cross-region round trip | ~50–150 ms | hundreds of millions× |

The takeaway: **moving data "up" the hierarchy is the whole game.** A local (in-JVM) cache is ~1000× faster than a remote cache, which is ~10–50× faster than a DB query.

### 2.2 Core cache vocabulary

- **Hit:** the requested key is present (and valid) in the cache. Served fast.
- **Miss:** the key is absent or expired; the cache must fall back to the SoR (or return empty).
- **Hit ratio (hit rate):** hits / (hits + misses). The single most important cache health metric. A read-heavy cache should target **≥ 90–95%**; below ~80% the cache may be costing more than it saves.
- **Population / fill:** writing a value into the cache, typically after a miss.
- **Eviction:** the cache *itself* removing an entry to free space, governed by an **eviction policy** (see 2.4). Distinct from expiration.
- **Expiration (TTL/TTI):** an entry becomes invalid after a time bound. **TTL (time-to-live):** expire N seconds after *write*. **TTI (time-to-idle / access-based):** expire N seconds after the *last access*. (Caffeine: `expireAfterWrite` vs `expireAfterAccess`; Redis: `EXPIRE`/`SET ... EX` is write-based TTL.)
- **Invalidation:** *deliberately* removing or updating an entry because the underlying data changed. The hard part of caching.
- **Staleness:** the cached value no longer matches the SoR. Bounded by TTL in the worst case (under TTL-only strategies).
- **Working set:** the set of keys actually accessed in a time window. The cache should be sized to hold the hot portion of the working set.
- **Locality:** **temporal** (recently used → likely reused) and **spatial** (nearby/related data → likely accessed together). Caches exploit both.

> *Phil Karlton's adage:* "There are only two hard things in Computer Science: cache invalidation and naming things." This chapter is largely about the first one.

### 2.3 Where the cache lives (cache topology)

The same *pattern* can run at several **layers**. Knowing where the cache physically sits is essential because it dictates consistency, latency, blast radius, and invalidation difficulty.

| Layer | Location | Latency | Shared across nodes? | Invalidation difficulty | Example |
|---|---|---|---|---|---|
| **Client-side** | Browser, mobile app, SDK | 0 (local) | No (per client) | Very hard (you don't control clients) | HTTP cache, ETag, `Cache-Control` |
| **CDN / edge** | Geographically distributed POPs | 1–30 ms | Shared per region | Hard (purge APIs, slow propagation) | CloudFront, Fastly, Cloudflare |
| **Local / in-process** | Inside the JVM heap (or off-heap) | tens of ns | **No** — each instance has its own | Hard (must broadcast invalidation) | Caffeine, Ehcache, Guava |
| **Distributed / remote** | Separate cache cluster | 0.2–1 ms | **Yes** — single shared view | Easier (one place to invalidate) | Redis, Memcached, Hazelcast |
| **Near-cache (hybrid)** | Local cache backed by distributed cache | tens of ns hit / ms miss | Local tier no, remote tier yes | Hardest (two tiers to keep coherent) | Hazelcast/Coherence near-cache, Caffeine+Redis |

> **POP (point of presence):** a CDN's edge data center close to users. Serving from a POP avoids crossing oceans.

> **ETag / Cache-Control:** HTTP caching primitives. `Cache-Control: max-age=60` tells clients/proxies to reuse the response for 60s; an `ETag` is a content fingerprint so a client can ask "has this changed?" with `If-None-Match` and get a cheap `304 Not Modified`.

**Local vs distributed — the central trade:** a local cache is ~1000× faster and has no network dependency, but each application instance holds its *own copy*, so the copies drift (one node updates, others are stale) and you must **broadcast invalidations** across the fleet. A distributed cache gives a **single shared, coherent view** (every node sees the same value) at the cost of a network hop and a new operational dependency. **Near-cache** tries to have both: a tiny local L1 in front of the shared L2 — fastest on hits, but now you have two tiers to invalidate, which is the hardest coherence problem of all.

### 2.4 Eviction policies (the "when full, who leaves" rule)

When the cache reaches its size bound, it must evict. Policies:

- **LRU (Least Recently Used):** evict the entry not touched for the longest time. Cheap, intuitive, good default. Weakness: a single scan of cold data ("scan pollution") flushes the hot set.
- **LFU (Least Frequently Used):** evict the least-often-accessed entry. Good for stable popularity; weakness: stale popularity ("a once-hot key that's now cold lingers"), and cold-start counting bias.
- **FIFO (First-In-First-Out):** evict oldest by insertion. Rarely ideal; ignores access patterns.
- **TinyLFU / W-TinyLFU:** a modern admission+eviction policy (used by **Caffeine**). It keeps a tiny probabilistic frequency sketch (a Count-Min Sketch) and only *admits* a new item if it's predicted to be more valuable than the eviction candidate; combines a small LRU "window" with a main LFU region. Achieves near-optimal hit rates across diverse workloads. (More in §7.)
- **Random / second-chance / CLOCK:** approximations used where exact ordering is too costly.
- **Redis maxmemory policies:** `noeviction`, `allkeys-lru`, `allkeys-lfu`, `volatile-lru`, `volatile-lfu`, `allkeys-random`, `volatile-random`, `volatile-ttl`. (`volatile-*` only evict keys that have a TTL set.) Default is `noeviction` (writes fail with an error when memory is full).

> **Count-Min Sketch:** a compact probabilistic data structure that estimates how many times it has seen each item, using a few hash functions and counters. It can overcount but never undercount, and uses far less memory than exact counters — perfect for frequency-based admission.

### 2.5 Consistency vocabulary

Caching is fundamentally a **distributed-systems consistency problem** (two copies of data). Terms you'll need:

- **Strong consistency:** every read returns the most recent write. Caches generally *cannot* offer this cheaply; achieving it usually means bypassing the cache for the read or writing through synchronously with locks.
- **Eventual consistency:** if writes stop, all copies converge to the same value "eventually." Most caches are eventually consistent (bounded by TTL/invalidation latency).
- **Read-your-writes consistency:** a client that just wrote sees its own write on subsequent reads. Caches can break this (you write the DB, but a stale cache serves your read). Fixes: update-on-write, or read-from-DB-then-repopulate.
- **Monotonic reads:** a client never sees data go "backward" in time. Multi-node local caches can violate this (hit node A with fresh data, then node B with stale).
- **TTL as a consistency bound:** with TTL-only invalidation, the maximum staleness is the TTL. Shorter TTL → fresher but lower hit rate and more DB load. This is the fundamental TTL trade.

> **CAP theorem:** in a network partition (P), a distributed system must choose between **C**onsistency (every node agrees) and **A**vailability (every request gets a non-error response). Caches almost always lean **AP** — they prefer to serve a possibly-stale answer over failing.

### 2.6 The two paths: read and write

Every cache pattern is defined by what it does on these two paths.

**Read path** (serving a `get(key)`):
1. Look in cache.
2. **Hit** → return value (done, fast).
3. **Miss** → obtain value from SoR, optionally store it in the cache, return it.

**Write path** (handling a `put(key, value)` / `update`):
1. Persist to the SoR (or to the cache first, depending on pattern).
2. Reconcile the cache: **invalidate** (delete) or **update** (overwrite) the cached entry.

The patterns are exactly the different answers to "who does each step, in what order, synchronously or asynchronously."

---

## 3. How it works internally — the five patterns, step by step

This is the heart of the chapter. For each pattern: the precise sequence, the consistency implication, failure behavior, and the concurrency hazards.

### 3.1 Cache-aside (lazy loading)

The application is fully in charge. The cache is a passive key-value store that knows nothing about the DB.

#### Read path (cache-aside)
1. App calls `cache.get(key)`.
2. **Hit** → return value.
3. **Miss** →
   a. App reads from DB: `value = db.load(key)`.
   b. App writes to cache: `cache.set(key, value, ttl)`.
   c. App returns `value`.

#### Write path (cache-aside)
The standard, recommended ordering is **write DB, then invalidate cache**:
1. App writes the SoR: `db.update(key, value)`.
2. App **deletes** the cache entry: `cache.delete(key)`. (Delete, not update — see "why invalidate, not update" below.)

The next read for that key misses and lazily repopulates with the fresh value.

#### Consistency implication
Eventually consistent. Between the DB write and the next read-fill, the cache is empty (a miss, not stale). Worst-case staleness without writes is the TTL.

#### The notorious race conditions of cache-aside

**Race A — concurrent miss + update (stale write-back):**
1. Reader R misses, loads `v1` from DB (old value), but pauses before writing the cache.
2. Writer W updates DB to `v2` and deletes the cache (no-op, it's empty).
3. R resumes and writes `v1` into the cache.
4. Cache now holds the **stale** `v1` until TTL expires. The SoR says `v2`.

This is the classic cache-aside hazard. Mitigations:
- **Short TTL** to bound the staleness.
- **"Delete twice" / delayed double-delete:** after updating the DB, delete the cache, wait a short window (longer than a typical read), and delete again — catching the late write-back.
- **Versioning / CAS:** store a version with the value; only write the cache if your loaded version is ≥ the cached version (compare-and-set).
- **Bind reads and writes through a single-flight loader** (see Race B) plus invalidation messaging.

**Race B — thundering herd / cache stampede:** a hot key expires; thousands of concurrent readers all miss simultaneously and all hammer the DB with the same query. Mitigations:
- **Single-flight / request coalescing:** ensure only *one* loader runs per key; others wait and share the result. (Caffeine's `LoadingCache.get` does this per key; Redis needs an app-level lock or `SETNX` mutex.)
- **Probabilistic early expiration** (see §7): refresh slightly before TTL with a randomized jitter so not everyone expires at the same instant.
- **Locking:** acquire a per-key mutex before loading.

> **Why invalidate (delete), not update, the cache on write?** Deleting is idempotent and race-tolerant: the worst case is an extra miss. Updating the cache directly re-introduces the stale-write-back race (two writers can interleave and leave the older value) and wastes work caching data nobody may read. Update-the-cache is acceptable only with proper concurrency control (versioning/CAS) or for write-heavy keys you know will be re-read immediately.

#### Failure behavior
- **Cache down:** reads fall through to the DB (degraded latency, higher DB load, but correct). Writes still hit the DB; the `cache.delete` may fail — handle by tolerating the failure (entry will TTL out) or retrying.
- **DB down:** misses fail; you may serve stale-on-error if you keep a longer "soft TTL" copy.

#### Pros / cons
- **Pros:** simple, cache-agnostic (works with any KV store), resilient to cache outages, caches only what's actually read (no wasted population).
- **Cons:** application code is littered with cache logic (or you centralize it), first read of each key is always a miss (cold start), the write-invalidation races above.

### 3.2 Read-through

A cache that knows how to load from the SoR on a miss. The application talks only to the cache.

#### Read path (read-through)
1. App calls `cache.get(key)`.
2. **Hit** → return.
3. **Miss** → the **cache itself** invokes a configured **loader/`CacheLoader`** to fetch from the SoR, stores the result, and returns it — all transparently to the app.

#### Consistency implication
Same read-time semantics as cache-aside (lazy fill), but the loading logic is centralized in the cache layer, eliminating duplicated/ inconsistent fill code and giving the library a chance to add **single-flight** (one loader per key) for free.

#### Failure behavior
- The loader can throw; the cache propagates the exception (and typically does *not* cache the failure unless you opt into negative caching). On a DB outage, gets fail or fall back per your loader's logic.
- **Single-flight protection** against stampedes is usually built in (e.g., Caffeine `LoadingCache`, Guava `LoadingCache`, Ehcache read-through).

#### Pros / cons
- **Pros:** clean application code (one `get`), centralized + reusable load logic, built-in stampede protection, easy to add metrics/retries in one place.
- **Cons:** requires a cache library/provider that supports loaders, less control over per-call behavior, still lazy (first read = miss). The cache layer becomes a critical dependency for reads.

### 3.3 Write-through

A cache that synchronously writes to the SoR on every write. The application writes only to the cache.

#### Write path (write-through)
1. App calls `cache.put(key, value)`.
2. The cache **synchronously** writes to the SoR via a configured **writer/`CacheWriter`**: `db.write(key, value)`.
3. Only after the DB write succeeds does the cache store the value and return.

(Pairs naturally with read-through, forming a single component in front of the DB.)

#### Consistency implication
Cache and SoR are **always in sync after a successful write** (within this pattern's own boundary): the cache holds the just-written value and the DB is durable. No stale-write-back race because the cache is the front door for writes. *Read-your-writes is satisfied* through the cache.
Caveat: if other writers bypass the cache and write the DB directly, the cache becomes stale — write-through only guarantees consistency if **all writes go through the cache.**

#### Failure behavior
- **DB write fails:** the whole `put` fails (synchronous). No silent data loss — the app learns immediately. The cache should *not* hold a value that didn't persist.
- **Cache down:** writes fail (unless you fall back to direct DB writes). Single point of failure on the write path.

#### Pros / cons
- **Pros:** cache never holds unpersisted data; strong-ish read-your-writes; simple write code.
- **Cons:** **every write pays DB latency** (no write speedup — the point is consistency, not write performance); writes to keys that are never read still populate the cache (wasted memory) unless you only write-on-read; the cache is now on the critical write path.

### 3.4 Write-behind (write-back)

Like write-through, but the SoR write is **asynchronous and batched**.

#### Write path (write-behind)
1. App calls `cache.put(key, value)`.
2. The cache stores the value **immediately** and returns (fast). The DB is *not* yet updated.
3. The cache enqueues the write into a **write buffer / dirty queue**.
4. A background flusher periodically (by time and/or batch size) drains the queue and writes to the SoR — often **coalescing** multiple updates to the same key into one DB write and **batching** many keys into a bulk operation.

#### Consistency implication
Eventual consistency with a **durability gap**: data lives only in the (often volatile) cache between the `put` and the flush. Reads from the same cache are read-your-writes consistent; the DB lags by up to the flush interval.

#### Failure behavior — the dangerous one
- **Cache crashes before flush → DATA LOSS.** Any acknowledged write still sitting in the dirty queue is gone. This is the defining risk. Mitigations:
  - Persist the write buffer (e.g., Redis AOF, a durable WAL, Kafka as the buffer).
  - Replicate the cache so the queue survives a single node loss.
  - Use write-behind only where some loss is acceptable (metrics, counters, view counts, non-critical telemetry).
- **DB temporarily down:** the queue grows; the flusher retries. You need **backpressure** (cap the queue, block or shed writes when full) or you'll OOM.
- **Out-of-order / lost updates:** coalescing must preserve last-write-wins per key; ordering across keys may not be preserved (matters for foreign-key/transactional constraints).

#### Pros / cons
- **Pros:** **fastest writes** (DB latency hidden), massive **write throughput** via batching/coalescing (turning N writes to one key into 1 DB write), smooths DB load spikes.
- **Cons:** **durability risk** (the big one), complex (queue, retries, backpressure, ordering), DB and cache diverge during the lag, harder to reason about transactions and failures.

> **Write coalescing:** merging multiple pending writes to the *same* key into a single DB write (only the latest value matters). A counter incremented 10,000 times in a second can flush as one `UPDATE`. This is the superpower of write-behind for high-write-rate keys.

### 3.5 Refresh-ahead

A *read-path optimization*: proactively refresh entries that are popular and *about to expire*, so reads keep hitting fresh data without ever paying a miss/load latency.

#### How it works
1. Each entry has a TTL and a **refresh-ahead factor** (e.g., refresh when ≥ 75% of the TTL has elapsed, *if* the entry is accessed in that window).
2. On a read that falls in the refresh window, the cache **returns the current (still-valid) value immediately** and triggers an **asynchronous** reload from the SoR in the background.
3. The reload replaces the entry; the next reads continue to hit, now with refreshed data.

Caffeine implements this as `refreshAfterWrite`: after the refresh duration, the *first* access returns the old value and kicks off an async refresh (the value is not blocked). Compare with `expireAfterWrite`, which *removes* the value and forces the next reader to block on a synchronous load.

> **`refreshAfterWrite` vs `expireAfterWrite` (Caffeine):** `expireAfterWrite` = "after N, the value is dead; next read blocks to reload." `refreshAfterWrite` = "after N, the value is *stale-but-usable*; next read returns it instantly and reloads in the background." Use both together: `refreshAfterWrite(1m).expireAfterWrite(5m)` means refresh hot keys every minute without blocking, but if a key isn't touched for 5 minutes, drop it entirely. Refresh only fires on access, so cold keys aren't needlessly refreshed.

#### Consistency implication
Bounds staleness like TTL but **hides load latency** — readers almost never block on a miss for hot keys. Slightly staler than strict expiration (you serve the old value during the async refresh window).

#### Failure behavior
- Background refresh fails → the old value typically remains until it hard-expires (graceful). Configure whether a failed refresh keeps or drops the entry.
- Only helps keys that are accessed often enough to enter the refresh window before expiry; cold keys still miss.

#### Pros / cons
- **Pros:** eliminates latency spikes from misses on hot keys; smooths DB load (refreshes are spread out and async); great for predictable hot data.
- **Cons:** refreshes keys that may not be needed again (wasted load) unless gated on access; serves slightly stale data during refresh; extra complexity; doesn't help cold/long-tail keys.

### 3.6 Side-by-side internal summary

| Aspect | Cache-aside | Read-through | Write-through | Write-behind | Refresh-ahead |
|---|---|---|---|---|---|
| Path | Read + write | Read | Write | Write | Read (proactive) |
| Orchestrator | App | Cache | Cache | Cache | Cache |
| Fill timing | Lazy (on miss) | Lazy (on miss) | On write | On write | Proactive (pre-expiry) |
| DB write timing | App, after DB | n/a | Sync, before return | Async, batched | n/a |
| Consistency | Eventual (delete-on-write) | Eventual | Strong-ish (if all writes go through) | Eventual + durability gap | Eventual (bounded) |
| Worst failure | Stale write-back race | Loader failure on read | Write fails on cache/DB outage | **Data loss on crash** | Failed background refresh (graceful) |
| Best for | General read-heavy | Read-heavy, clean code | Consistency on write | Write-heavy, loss-tolerant | Hot, predictable keys |

---

## 4. The complete toolkit

### 4.1 Caffeine (local, in-JVM cache)

Caffeine is the de-facto high-performance Java caching library (successor to Guava Cache), built around the **W-TinyLFU** policy. Maven: `com.github.ben-manes.caffeine:caffeine`.

**Builder configuration (`Caffeine.newBuilder()`):**

| Method | Purpose | Default |
|---|---|---|
| `maximumSize(long)` | Size-based eviction (entry count) | unbounded (must set one bound) |
| `maximumWeight(long)` + `weigher(...)` | Size by computed weight (e.g., bytes) | none |
| `expireAfterWrite(Duration)` | TTL from last write | none |
| `expireAfterAccess(Duration)` | TTI from last read/write | none |
| `expireAfter(Expiry)` | Per-entry custom expiry (create/update/read) | none |
| `refreshAfterWrite(Duration)` | Async refresh-ahead trigger | none |
| `weakKeys()` / `weakValues()` / `softValues()` | GC-sensitive references | strong |
| `recordStats()` | Enable hit/miss/eviction stats | off |
| `removalListener(...)` | Callback on eviction/removal (async) | none |
| `evictionListener(...)` | Callback on eviction (sync, during eviction) | none |
| `executor(Executor)` | Where async work (refresh, listeners) runs | `ForkJoinPool.commonPool()` |
| `ticker(Ticker)` | Custom time source (for tests) | system nanoTime |
| `scheduler(Scheduler)` | Prompt expiration scheduling | none (lazy on access) |

**Cache types:**

| Type | Built via | Use |
|---|---|---|
| `Cache<K,V>` | `.build()` | Manual put/get (cache-aside style) |
| `LoadingCache<K,V>` | `.build(CacheLoader)` | Read-through; `get(key)` auto-loads |
| `AsyncCache<K,V>` | `.buildAsync()` | Returns `CompletableFuture`; non-blocking |
| `AsyncLoadingCache<K,V>` | `.buildAsync(loader)` | Async read-through |

**Key methods:** `getIfPresent(k)`, `get(k, k->load(k))` (compute-if-absent, single-flight), `put(k,v)`, `getAll(keys)`, `invalidate(k)`, `invalidateAll()`, `asMap()` (live view), `policy()` (introspect/modify eviction at runtime), `stats()` (`hitRate()`, `missRate()`, `evictionCount()`, `loadFailureCount()`, `averageLoadPenalty()`).

> **Single-flight in Caffeine:** `LoadingCache.get(key)` (and `Cache.get(key, loader)`) guarantees the mapping function runs **at most once per key** even under concurrent misses — other threads block and receive the computed value. This gives you stampede protection for free.

### 4.2 Redis (distributed cache)

Redis is the dominant distributed in-memory store. Relevant commands and config:

**Core string/KV commands:**

| Command | Purpose |
|---|---|
| `GET key` / `MGET k1 k2` | Read one/many |
| `SET key val [EX s] [PX ms] [NX|XX] [KEEPTTL] [GET]` | Write with optional TTL, conditional, return old |
| `SETEX key s val` | Set with TTL (seconds) |
| `SETNX key val` | Set only if absent (used for locks/stampede mutex) |
| `DEL key` / `UNLINK key` | Delete (UNLINK = async, non-blocking reclaim) |
| `EXPIRE key s` / `PEXPIRE key ms` | Set/refresh TTL |
| `TTL key` / `PTTL key` | Remaining TTL (-1 no TTL, -2 missing) |
| `PERSIST key` | Remove TTL |
| `INCR`/`INCRBY`/`DECR` | Atomic counters (great with write-behind) |
| `EXISTS`, `TYPE`, `SCAN` | Introspection (use SCAN, never KEYS in prod) |

**Data structures beyond strings:** Hashes (`HSET`/`HGET` — cache an object's fields), Sorted Sets (`ZADD`/`ZRANGE` — leaderboards, time-ordered), Sets, Lists, Streams (durable logs — useful as a write-behind buffer), HyperLogLog, Bitmaps, Geo.

**Memory & eviction config (`redis.conf` / `CONFIG SET`):**

| Setting | Purpose | Default |
|---|---|---|
| `maxmemory <bytes>` | Cap memory usage | 0 (unlimited) |
| `maxmemory-policy` | Eviction policy when full | `noeviction` |
| `maxmemory-samples` | Sample count for approximate LRU/LFU | 5 |
| `lfu-log-factor` / `lfu-decay-time` | Tune LFU counter growth/decay | 10 / 1 |

**Persistence (affects write-behind durability):**

| Mechanism | What it is | Trade |
|---|---|---|
| **RDB** | Point-in-time binary snapshots | Compact, fast restart; can lose minutes of data |
| **AOF** | Append-only log of every write command | Better durability (`appendfsync everysec`/`always`); larger, slower restart |
| **No persistence** | Pure cache | Fastest; total loss on restart |

> **AOF (Append-Only File):** Redis logs every write operation to a file so it can replay them after a crash. `appendfsync everysec` (default) flushes to disk ~once/second — at most ~1s of writes at risk. `always` fsyncs every write (durable, slower). This is the lever that makes Redis-backed write-behind safer.

**Pub/Sub & keyspace notifications (for cross-node invalidation):** `SUBSCRIBE`/`PUBLISH`, plus **keyspace notifications** (`notify-keyspace-events`) which emit events on key changes/expiry — useful to invalidate local near-caches when the shared cache changes.

**Clustering & HA:** Redis Cluster (sharding by hash slot, 16384 slots), Redis Sentinel (failover for primary/replica), client-side sharding. Replication is **asynchronous** by default → a failover can lose the last few writes (relevant to durability claims).

> **Redis is single-threaded** for command execution (the main event loop), which is *why* `INCR`, `SETNX`, and single commands are atomic without locks. Long-running commands (e.g., `KEYS *`, big `SMEMBERS`) block everything — a classic production footgun.

### 4.3 Java client libraries for Redis

| Library | Style | Notes |
|---|---|---|
| **Lettuce** | Async/reactive, Netty-based | Thread-safe shared connection; default in Spring Boot |
| **Jedis** | Synchronous, simple | Connection pool per thread; very common |
| **Redisson** | High-level objects, distributed locks, near-cache | Implements many patterns (RMapCache, write-behind, read-through) out of the box |

### 4.4 Spring Cache abstraction

Spring provides annotation-driven caching that lets you switch providers (Caffeine, Redis, Ehcache, etc.) without changing code:

| Annotation | Effect |
|---|---|
| `@Cacheable` | Read-through-ish: check cache; on miss run method and cache result |
| `@CachePut` | Always run method and update cache (write-through-ish at app level) |
| `@CacheEvict` | Remove entry/entries (invalidation on write) |
| `@Caching` | Combine multiple cache ops |
| `@CacheConfig` | Class-level cache defaults |

Backed by a `CacheManager` (`CaffeineCacheManager`, `RedisCacheManager`, etc.). Note: Spring's `@Cacheable` does *not* by itself provide single-flight stampede protection unless the underlying provider does (Caffeine's does via `sync = true`).

### 4.5 Other notable providers (for awareness)

- **Ehcache 3** — JSR-107 (JCache) compliant; tiered (heap/off-heap/disk); read-through/write-through/write-behind via `CacheLoaderWriter`.
- **Hazelcast / Apache Ignite / Oracle Coherence** — distributed data grids with near-cache, read/write-through, write-behind built in.
- **Memcached** — simple, multi-threaded, LRU-only KV cache; no persistence, no data structures; pure cache-aside companion.
- **Guava Cache** — Caffeine's predecessor; same API shape, lower performance; legacy.
- **JCache (JSR-107)** — the Java caching standard API (`javax.cache`); `CacheLoader`/`CacheWriter` interfaces define read/write-through.

---

## 5. Code examples by use case

All examples are Java 17+, idiomatic, and copy-adaptable. Imports abbreviated for brevity where obvious.

### 5.1 Cache-aside with Redis (the workhorse pattern)

Scenario: a product catalog service. Reads dominate; writes (price/stock updates) invalidate.

```java
public class ProductCacheAsideService {

    private final JedisPool pool;          // Redis connection pool (Jedis)
    private final ProductRepository db;    // System of record
    private final ObjectMapper json = new ObjectMapper();
    private static final Duration TTL = Duration.ofMinutes(10);

    public ProductCacheAsideService(JedisPool pool, ProductRepository db) {
        this.pool = pool;
        this.db = db;
    }

    private static String key(long id) { return "product:" + id; }

    /** READ PATH: cache-aside. */
    public Product getProduct(long id) {
        try (Jedis r = pool.getResource()) {
            String cached = r.get(key(id));
            if (cached != null) {                       // HIT
                return json.readValue(cached, Product.class);
            }
            // MISS: load from SoR
            Product p = db.findById(id);
            if (p == null) {
                // Negative caching: cache the "not found" briefly to stop
                // repeated DB hits for bogus IDs (penetration protection).
                r.set(key(id), "__NULL__", SetParams.setParams().nx().ex(60));
                return null;
            }
            // Populate cache with a TTL (bounds staleness).
            r.set(key(id), json.writeValueAsString(p),
                  SetParams.setParams().ex(TTL.toSeconds()));
            return p;
        } catch (JsonProcessingException e) {
            // On serialization error, fall back to DB — never fail the read
            // just because the cache misbehaved.
            return db.findById(id);
        }
    }

    /** WRITE PATH: write DB, then INVALIDATE (delete) the cache. */
    public void updateProduct(Product p) {
        db.save(p);                          // 1. Persist to SoR first
        try (Jedis r = pool.getResource()) {
            r.del(key(p.getId()));           // 2. Delete (idempotent, race-tolerant)
        }
        // Next read repopulates lazily with the fresh value.
    }
}
```

Key points: write-DB-then-delete-cache ordering; TTL bounds staleness; negative caching guards against **cache penetration** (queries for nonexistent keys that always miss and always hit the DB); cache failures degrade to DB rather than failing.

### 5.2 Read-through + refresh-ahead with Caffeine (local cache)

Scenario: feature flags / config that is read on nearly every request, changes rarely, and must never block the hot path on a miss.

```java
public class FeatureFlagCache {

    private final LoadingCache<String, FlagSet> cache;
    private final ConfigClient configClient; // SoR (e.g., remote config service)

    public FeatureFlagCache(ConfigClient configClient) {
        this.configClient = configClient;
        this.cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            // Refresh-ahead: after 30s, the first access returns the OLD value
            // immediately and reloads asynchronously in the background.
            .refreshAfterWrite(Duration.ofSeconds(30))
            // Hard expiry: if a flag isn't touched for 10 min, drop it entirely.
            .expireAfterWrite(Duration.ofMinutes(10))
            .recordStats()                    // expose hitRate(), etc.
            .build(this::loadFlags);          // READ-THROUGH loader (single-flight)
    }

    /** Loader = read-through. Caffeine guarantees one call per key under load. */
    private FlagSet loadFlags(String tenantId) {
        return configClient.fetchFlags(tenantId);   // hits SoR on miss/refresh
    }

    public boolean isEnabled(String tenantId, String flag) {
        // get() never blocks for hot keys: refresh-ahead keeps them warm.
        return cache.get(tenantId).isEnabled(flag);
    }

    public CacheStats stats() { return cache.stats(); }
}
```

Key points: `refreshAfterWrite` (proactive, non-blocking) + `expireAfterWrite` (hard upper bound on staleness for cold keys); `LoadingCache` gives read-through + per-key single-flight stampede protection; `recordStats()` for observability.

### 5.3 Async read-through with stampede protection (AsyncLoadingCache)

Scenario: an aggregation endpoint whose load is an expensive downstream call; you want non-blocking I/O and to share one in-flight load among concurrent callers.

```java
AsyncLoadingCache<String, Report> reports = Caffeine.newBuilder()
    .maximumSize(5_000)
    .expireAfterWrite(Duration.ofMinutes(2))
    .refreshAfterWrite(Duration.ofSeconds(90))
    // The loader returns a CompletableFuture; many callers awaiting the same
    // key share ONE future (coalesced load — no stampede on the downstream).
    .buildAsync((key, executor) ->
        reportService.computeAsync(key));   // returns CompletableFuture<Report>

public CompletableFuture<Report> getReport(String key) {
    return reports.get(key);   // returns the shared in-flight future on a miss
}
```

Key points: `AsyncLoadingCache` returns `CompletableFuture`s; concurrent gets for the same missing key receive the *same* future — true request coalescing across threads with zero locking on your side.

### 5.4 Write-through with Spring + Redis (consistency on write)

Scenario: user-profile service where reads must reflect writes immediately and you never want the cache to hold unpersisted data.

```java
@Service
@CacheConfig(cacheNames = "users")
public class UserService {

    private final UserRepository repo;       // SoR (JPA/Postgres)

    public UserService(UserRepository repo) { this.repo = repo; }

    /** READ-THROUGH-ish: cache on miss, then serve from cache. */
    @Cacheable(key = "#id", sync = true)     // sync=true → single-flight (Caffeine)
    public User getUser(long id) {
        return repo.findById(id).orElseThrow();
    }

    /** WRITE-THROUGH-ish: persist AND update the cache atomically per call. */
    @CachePut(key = "#user.id")
    public User updateUser(User user) {
        return repo.save(user);   // saved value is also placed in the cache
    }

    /** Invalidate on delete. */
    @CacheEvict(key = "#id")
    public void deleteUser(long id) {
        repo.deleteById(id);
    }
}
```

```yaml
# application.yml — Redis-backed cache with per-cache TTL
spring:
  cache:
    type: redis
  redis:
    host: redis.internal
    port: 6379
  cache.redis:
    time-to-live: 600000   # 10 min default TTL (ms)
    cache-null-values: false
```

Key points: `@CachePut` writes through to the cache after the DB save (true write-through to the *DB* is the `repo.save`; the cache update keeps them consistent). `@Cacheable(sync=true)` enables single-flight where the provider supports it. Caveat: if any code path writes the DB *without* going through these methods, the cache goes stale — enforce a single write entry point.

### 5.5 Write-behind for high-frequency counters (Redis + batched flush)

Scenario: page-view / like counters at very high write rate where per-write DB latency is unacceptable and ~seconds of potential loss on crash is tolerable.

```java
public class ViewCounterWriteBehind {

    private final JedisPool pool;
    private final ViewCountRepository db;          // SoR
    private final ScheduledExecutorService flusher =
        Executors.newSingleThreadScheduledExecutor();
    private static final String DIRTY_SET = "views:dirty";

    public ViewCounterWriteBehind(JedisPool pool, ViewCountRepository db) {
        this.pool = pool;
        this.db = db;
        // Background flusher drains the dirty set every 5 seconds.
        flusher.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    /** WRITE PATH: increment in Redis instantly; mark key dirty. No DB hit. */
    public void recordView(long pageId) {
        try (Jedis r = pool.getResource()) {
            r.incr("views:count:" + pageId);  // atomic, single-threaded Redis
            r.sadd(DIRTY_SET, String.valueOf(pageId)); // track what to flush
        }
    }

    /** READ PATH: serve from Redis (write-behind keeps it authoritative-ish). */
    public long getViews(long pageId) {
        try (Jedis r = pool.getResource()) {
            String v = r.get("views:count:" + pageId);
            return v == null ? db.getCount(pageId) : Long.parseLong(v);
        }
    }

    /** Coalesced, batched flush to the SoR. */
    private void flush() {
        try (Jedis r = pool.getResource()) {
            Set<String> dirty = r.smembers(DIRTY_SET);
            if (dirty.isEmpty()) return;
            Map<Long, Long> batch = new HashMap<>();
            for (String id : dirty) {
                String v = r.get("views:count:" + id);
                if (v != null) batch.put(Long.parseLong(id), Long.parseLong(v));
            }
            db.upsertCounts(batch);            // ONE bulk DB write (coalesced)
            // Remove only the flushed members (new dirties since are kept).
            r.srem(DIRTY_SET, dirty.toArray(new String[0]));
        } catch (Exception e) {
            // Leave keys dirty → retried next cycle (at-least-once flush).
            log.error("View flush failed; will retry", e);
        }
    }
}
```

Durability hardening for this pattern: enable Redis **AOF (`appendfsync everysec`)** so the counters survive a crash; replicate Redis; accept that a crash can lose up to one flush interval (here 5s) of un-flushed increments unless AOF persisted them. For stricter durability, use **Kafka or a Redis Stream as the durable write buffer** instead of an in-memory queue.

### 5.6 Near-cache: Caffeine (L1) in front of Redis (L2) with cross-node invalidation

Scenario: ultra-low-latency reads of shared data; you want JVM-local speed but a coherent shared view, and you must invalidate every node's L1 when data changes.

```java
public class NearCache {

    private final Cache<String, String> l1;     // local (Caffeine)
    private final JedisPool pool;                // distributed (Redis) = L2
    private final ProductRepository db;          // SoR
    private static final String INVALIDATE_CHANNEL = "cache:invalidate";

    public NearCache(JedisPool pool, ProductRepository db) {
        this.pool = pool; this.db = db;
        this.l1 = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofSeconds(30)) // short L1 TTL = safety net
            .build();
        // Subscribe to invalidation events from other nodes.
        new Thread(this::subscribeInvalidations, "near-cache-invalidator").start();
    }

    public String get(String id) {
        String v = l1.getIfPresent(id);          // 1. L1 (local, ~ns)
        if (v != null) return v;
        try (Jedis r = pool.getResource()) {
            v = r.get("product:" + id);          // 2. L2 (Redis, ~ms)
            if (v == null) {
                v = serialize(db.findById(id));  // 3. SoR
                r.setex("product:" + id, 600, v);// fill L2
            }
            l1.put(id, v);                        // fill L1
            return v;
        }
    }

    public void update(String id, String value) {
        db.save(deserialize(value));              // 1. SoR
        try (Jedis r = pool.getResource()) {
            r.del("product:" + id);               // 2. invalidate L2
            // 3. Broadcast: tell ALL nodes (including self) to drop L1.
            r.publish(INVALIDATE_CHANNEL, id);
        }
    }

    private void subscribeInvalidations() {
        try (Jedis r = pool.getResource()) {
            r.subscribe(new JedisPubSub() {
                @Override public void onMessage(String channel, String id) {
                    l1.invalidate(id);            // drop stale local copy
                }
            }, INVALIDATE_CHANNEL);
        }
    }
    // serialize/deserialize omitted
}
```

Key points: L1 short TTL is a **safety net** in case an invalidation message is missed (pub/sub is fire-and-forget — no delivery guarantee). The hardest coherence problem in caching; the broadcast keeps the fleet roughly coherent, the short L1 TTL bounds the damage of a dropped message.

### 5.7 Stampede protection with a Redis mutex (when you don't have single-flight)

Scenario: cache-aside on Redis where a hot key's expiry would otherwise cause a thundering herd against the DB.

```java
public String getWithMutex(String key) {
    try (Jedis r = pool.getResource()) {
        String v = r.get(key);
        if (v != null) return v;                       // HIT

        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        // Try to win the right to load: SET lock NX with short TTL.
        boolean gotLock = "OK".equals(
            r.set(lockKey, token, SetParams.setParams().nx().px(3000)));
        if (gotLock) {
            try {
                String value = serialize(db.load(key)); // only ONE loader runs
                r.setex(key, 600, value);
                return value;
            } finally {
                // Release lock only if we still own it (avoid deleting others').
                releaseLock(r, lockKey, token);
            }
        } else {
            // Someone else is loading; brief backoff then re-read the cache.
            sleep(50);
            return getWithMutex(key);
        }
    }
}
```

Key points: `SET NX PX` is the canonical Redis lock; the random token + check-on-release prevents deleting a lock you no longer hold (lock TTL expired and another thread acquired it). For correctness under failover, consider Redlock or, better, the **probabilistic early refresh** technique (§7) which avoids locks entirely.

---

## 6. Implementation concerns & best practices

### 6.1 Performance
- **Maximize hit ratio.** Right-size the cache to hold the hot working set; measure `hitRate()` and tune `maximumSize`/`maxmemory`. Below ~80% hit rate, reconsider TTLs, key design, or whether to cache at all.
- **Serialization cost matters.** For Redis, JSON is convenient but slow and large; consider compact binary (Protobuf, Kryo, MessagePack) for hot, large objects. For Caffeine you store live objects (no serialization) — far faster, but objects share heap (watch for mutation/aliasing bugs — store immutable copies).
- **Pipeline / batch Redis calls.** `MGET`, pipelines, and Lua scripts cut round-trips. One `MGET` of 100 keys ≈ one RTT vs. 100 RTTs.
- **Avoid big values and big keys.** Large values cause network and memory spikes; "big keys" (huge hashes/sets) make single-threaded Redis stall on operations over them.
- **Local beats remote when you can tolerate per-node copies.** A near-cache or pure local cache removes the network hop for hot keys.

### 6.2 Correctness & concurrency
- **Prefer delete (invalidate) over update on the write path** for cache-aside (idempotent, race-tolerant).
- **Use single-flight** (LoadingCache, AsyncLoadingCache, or a Redis mutex) to prevent stampedes and stale-write-back.
- **Bound staleness with TTL even if you also invalidate** — invalidation messages get lost; TTL is the backstop.
- **Beware multi-instance local-cache drift:** without broadcast invalidation, each JVM's local cache diverges. Use short TTLs or a pub/sub invalidation channel.
- **Read-your-writes:** for flows that must see their own write, either bypass the cache for the immediate read or update/invalidate synchronously before returning.

### 6.3 The three cache "anomalies" (memorize these)
- **Cache penetration:** requests for keys that *don't exist* always miss the cache and always hit the DB (attack vector). Fix: **negative caching** (cache the "not found" with a short TTL) and/or a **Bloom filter** of known-existing keys in front of the DB.
- **Cache breakdown (hot-key expiry / dogpile):** one *very hot* key expires and a flood of concurrent requests stampedes the DB. Fix: single-flight/mutex, never-expire-hot-keys + async refresh, probabilistic early expiration.
- **Cache avalanche:** *many* keys expire at the *same time* (e.g., all set with the same TTL at startup), or the cache cluster fails, causing a load spike that can topple the DB. Fix: **TTL jitter** (randomize TTLs ±10–20%), staggered warming, cache HA/replication, and a circuit breaker / rate limiter in front of the DB.

> **Bloom filter:** a compact probabilistic set that answers "is this key *possibly* present?" with no false negatives (if it says "no," the key truly doesn't exist) but possible false positives. Placed before the DB, it cheaply rejects queries for keys that definitely don't exist, killing penetration.

### 6.4 TTL design
- Choose TTL from your **staleness tolerance**, not arbitrarily. Max staleness ≈ TTL (TTL-only) or invalidation latency (with invalidation).
- **Add jitter** to avoid avalanche: `ttl = base ± rand(0..jitter)`.
- **Two-tier TTL ("soft" + "hard"):** serve within soft TTL freely; between soft and hard, serve stale while refreshing; after hard, force reload. (Refresh-ahead encodes this.)
- **Different TTLs per data class:** config/flags (seconds–minutes, refresh-ahead), reference data (hours), user sessions (TTI), expensive aggregates (minutes).

### 6.5 Cache warming (avoiding cold-start misses)
- **Eager preload** at startup for known-hot keys (e.g., top products, all feature flags).
- **Background warmer** that periodically loads/refreshes the predicted hot set.
- **Replay-based warming:** after a deploy/restart, replay recent access logs to repopulate.
- **Avoid cold-start avalanche:** warm gradually with jittered TTLs so everything doesn't expire together later.
- **Caution:** don't over-warm cold/long-tail data — it wastes memory and DB load and can evict genuinely hot entries.

### 6.6 Security
- **Don't cache sensitive data unencrypted** in shared caches; consider TLS to Redis (`rediss://`), `requirepass`/ACLs, and network isolation.
- **Cache-key isolation / multi-tenancy:** include the tenant/user in the key to prevent cross-tenant data leakage (a classic caching CVE pattern: shared key serves another user's data).
- **HTTP caching pitfalls:** never let a CDN cache an authenticated, per-user response under a shared key; set `Cache-Control: private`/`no-store` appropriately.
- **DoS via penetration:** mitigate as in §6.3.

### 6.7 Observability
- **Per-cache metrics:** hit rate, miss rate, load count, load latency (`averageLoadPenalty`), eviction count, size, error/failure count. Caffeine: `recordStats()`. Redis: `INFO stats` (`keyspace_hits`, `keyspace_misses`), `INFO memory`, `evicted_keys`.
- **Trace cache calls** (hit/miss as span tags) so you can see cache behavior per request.
- **Alert on:** hit-rate drop (often signals a bug, a bad deploy, or an avalanche), eviction-rate spike (undersized), memory near `maxmemory`, growing write-behind queue depth.

### 6.8 Cost
- Remote cache nodes cost money and add a dependency; ensure the saved DB cost/latency justifies it. For very high hit-rate hot keys, a local cache can be nearly free vs. a Redis fleet.

### 6.9 Testability
- Inject a **`Ticker`** (Caffeine) or a fake clock to test expiration deterministically without `Thread.sleep`.
- Test miss-then-fill, invalidation-on-write, stampede behavior (concurrent gets → loader called once), and failure fallbacks (cache down → DB path).
- Use an embedded/testcontainers Redis for integration tests; assert TTLs and invalidations.

### 6.10 Anti-patterns to avoid
- **Caching everything** (no locality → pure overhead and eviction churn).
- **No TTL + no invalidation** (permanent staleness).
- **Update-cache-then-DB** ordering (if DB write fails, cache is wrong; also race-prone).
- **Using the cache as the SoR** (write-behind without durability = silent data loss).
- **`KEYS *` in production Redis** (blocks the single thread; use `SCAN`).
- **Same TTL on bulk-loaded keys** (avalanche).
- **Caching per-user data under a shared key** (data leak).
- **Ignoring failure fallbacks** (cache outage takes down the whole app instead of degrading to DB).

---

## 7. Advanced topics & deep internals

### 7.1 Caffeine's W-TinyLFU in depth
Caffeine doesn't use plain LRU/LFU. Its admission policy is **Window TinyLFU**:
1. New entries enter a small **window cache** (a tiny LRU, ~1% of capacity) — this captures recency/bursts.
2. When the window evicts, the candidate competes against the victim of the **main region** (a Segmented LRU governed by frequency).
3. A **frequency sketch** (a 4-bit Count-Min Sketch) estimates each item's access frequency; the candidate is *admitted* to the main region only if its estimated frequency exceeds the victim's — otherwise it's rejected. This prevents one-off scans from polluting the hot set.
4. The sketch is **aged** periodically (counters halved) so popularity reflects recent behavior, not all-time.

This gives near-optimal hit rates across recency-heavy *and* frequency-heavy workloads — strictly better than LRU or LFU alone on most real traces. Caffeine also uses **ring buffers + amortized maintenance** so reads/writes are lock-free fast and policy bookkeeping happens off the hot path.

### 7.2 Probabilistic early expiration (XFetch / "stochastic" refresh)
A lock-free stampede fix: instead of all readers refreshing exactly at TTL, each reader independently decides to refresh *early* with a probability that rises as expiry approaches. The classic formula (Vattani et al.):

```
refresh now if:  now - delta * beta * ln(rand()) >= expiry
```
where `delta` is the measured load time and `beta` (~1) tunes aggressiveness. Effect: a few readers refresh slightly early and asynchronously; the herd is spread out so the DB sees ~one load, with no locks. This is what high-end caches use under the hood.

### 7.3 Negative caching, Bloom filters, and penetration defense
Combine: a Bloom filter of existing keys rejects most bogus lookups before they reach the cache/DB; negative caching (short-TTL "null" markers) catches the false positives. For mutable existence (keys get created), refresh the Bloom filter or use a counting/scalable variant.

### 7.4 Cross-region & multi-tier coherence
- **Active-active multi-region caches** must reconcile concurrent writes (last-write-wins by timestamp, CRDTs, or route writes to a primary region). Cross-region invalidation is slow (50–150 ms+), so accept higher staleness or use versioned reads.
- **CDN purge** is eventually consistent and can take seconds–minutes; prefer **cache-busting versioned URLs** (`/asset.v123.js`) over relying on purge for correctness.

### 7.5 Redis internals relevant to patterns
- **Approximate LRU/LFU:** Redis doesn't track exact LRU; it samples `maxmemory-samples` keys and evicts the best candidate — cheap but approximate. LFU uses a logarithmic counter with decay (`lfu-log-factor`, `lfu-decay-time`).
- **Lazy vs. active expiration:** expired keys are removed both on access (lazy) and by a background sampler (active). A key past its TTL may still occupy memory until sampled — relevant for memory accounting.
- **`UNLINK` vs `DEL`:** `UNLINK` reclaims memory in a background thread, avoiding a blocking free of large objects on the main thread.
- **Single-threaded atomicity:** lets you build correct read-modify-write with `INCR`, `SETNX`, or a single **Lua script** (atomic, no interleaving) — the right tool for compound cache operations (e.g., "get, and if absent set with token").

### 7.6 Consistency-hardening for cache-aside writes
- **Delayed double-delete:** `DB.update; cache.del; sleep(~ms); cache.del` — the second delete clears any stale value a slow concurrent reader wrote between the update and first delete.
- **Binlog-driven invalidation:** subscribe to the DB's change log (MySQL binlog via Debezium/Canal, Postgres logical replication) and invalidate the cache from the change stream. This decouples invalidation from app code and catches *all* writers (including those bypassing the app). Powerful and increasingly common.

> **Debezium / Canal (CDC):** Change-Data-Capture tools that tail the database's replication log and emit a stream of row changes (often to Kafka). Consumers invalidate or update caches from that stream, guaranteeing the cache reacts to every committed write regardless of who made it.

### 7.7 Tuning knobs cheat list
- Caffeine: `maximumSize`/`maximumWeight`, `expireAfterWrite`/`Access`, `refreshAfterWrite`, `executor`, `scheduler` (for prompt expiry), `recordStats`.
- Redis: `maxmemory`, `maxmemory-policy`, `maxmemory-samples`, `lfu-*`, `appendfsync`, `notify-keyspace-events`, `timeout`, `tcp-keepalive`, client pool size.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing a pattern by workload

| If your workload… | Use | Why |
|---|---|---|
| Read-heavy, some staleness OK, want simplicity & resilience | **Cache-aside** | Simple, cache-agnostic, survives cache outages |
| Read-heavy, want clean code + centralized loading + stampede safety | **Read-through** (Caffeine LoadingCache) | One `get`, single-flight, reusable loader |
| Writes must be durable & immediately visible | **Write-through** | Cache never holds unpersisted data |
| Very high write rate, loss-tolerant, need write throughput | **Write-behind** | Hides DB latency, coalesces/batches writes |
| Hot, predictable keys; latency spikes from misses unacceptable | **Refresh-ahead** | Proactive async refresh, never blocks hot reads |

Commonly you **combine**: read-through + cache-aside-invalidation, or read-through + refresh-ahead + write-through.

### 8.2 Local vs distributed vs near-cache

| Criterion | Local (Caffeine) | Distributed (Redis) | Near-cache (both) |
|---|---|---|---|
| Latency | Best (ns) | Good (sub-ms) | Best on L1 hit |
| Shared/coherent view | No (per node) | Yes | Partial (L2 yes, L1 drifts) |
| Survives app restart | No | Yes | L2 yes |
| Capacity | Bounded by heap | Large (cluster) | Mixed |
| Invalidation complexity | High (broadcast) | Low (one place) | Highest (two tiers) |
| Operational cost | None (in-process) | Cluster to run | Both |
| Use when | Hot, per-node-tolerable, tiny | Shared state, coherence | Extreme latency + shared data |

### 8.3 TTL vs invalidation vs versioning

| Strategy | Freshness | Complexity | Catches all writers? |
|---|---|---|---|
| TTL only | Bounded by TTL | Lowest | Yes (eventually) |
| Invalidate on write (app) | Near-immediate | Medium | Only app writers |
| CDC/binlog invalidation | Near-immediate | High | **Yes (all writers)** |
| Versioned reads (store version) | Strong-ish | High | Yes |

### 8.4 Eviction policy choice

| Policy | Use when |
|---|---|
| LRU | General default, recency-driven access |
| LFU | Stable, skewed popularity (a few keys very hot long-term) |
| W-TinyLFU (Caffeine) | You want best-in-class hit rate without tuning |
| `volatile-ttl` (Redis) | Mixed cache+persistent data; evict soonest-to-expire first |
| `noeviction` | Cache holds must-keep data; prefer write-failure over loss |

### 8.5 When NOT to cache
- Write rate ≈ read rate (no amplification to absorb).
- Every read must be strictly fresh (financial postings, inventory at checkout decrement).
- No locality / uniform access over a huge keyspace (hit rate ≈ 0).
- Data is already cheap to fetch (sub-ms indexed lookups on a lightly loaded DB).

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → diagnosis → fix

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| DB CPU/latency spikes periodically | **Avalanche** (synchronized TTL expiry) | Correlate DB spikes with TTL windows; check uniform TTLs | TTL jitter; staggered warming |
| One key causes DB hammering | **Breakdown / hot-key dogpile** | Redis `--hotkeys`, slowlog, per-key QPS metrics | Single-flight/mutex; never-expire+refresh; probabilistic early refresh |
| DB hit on nonexistent IDs | **Penetration** | High miss rate with no fills; logs of unknown keys | Negative caching; Bloom filter; input validation |
| Stale data after a write | Bad write ordering, lost invalidation, local-cache drift | Compare cache vs DB; check invalidation path/logs | Delete-on-write; CDC invalidation; short TTL; pub/sub broadcast |
| Falling hit rate | Undersized cache / bad keys / churn / bad deploy | Caffeine `stats()`, Redis `INFO stats` keyspace hits/misses, `evicted_keys` | Resize; fix key cardinality; review TTLs |
| Redis latency spikes / blocking | `KEYS *`, big keys, large `SMEMBERS`/`HGETALL`, slow Lua | `SLOWLOG GET`, `LATENCY DOCTOR`, `--bigkeys` | Use `SCAN`; split big keys; `UNLINK`; avoid blocking commands |
| OOM in app | Unbounded local cache, large values, leak via strong refs | Heap dump, GC logs, cache size metric | Set `maximumSize`/`maximumWeight`; weigher; soft/weak values |
| Lost writes after Redis restart | Write-behind queue not persisted / no AOF | Compare DB vs expected; check `appendonly`, replication | Enable AOF `everysec`; durable buffer (Kafka/Stream); replicate |
| Cross-tenant data shown | Shared cache key across tenants | Inspect keys; review key construction | Include tenant/user in key |

### 9.2 Diagnostic toolbox
- **Caffeine:** `cache.stats()` → `hitRate`, `missRate`, `loadFailureCount`, `evictionCount`, `averageLoadPenalty`; `cache.estimatedSize()`; `cache.policy()` to inspect eviction/expiration and even peek at per-entry TTLs.
- **Redis:** `INFO stats` (`keyspace_hits/misses`, `evicted_keys`, `expired_keys`), `INFO memory` (`used_memory`, `mem_fragmentation_ratio`), `SLOWLOG GET`, `LATENCY DOCTOR`, `MEMORY USAGE key`, `redis-cli --bigkeys`, `redis-cli --hotkeys`, `MONITOR` (debug only — heavy), `CLIENT LIST`.
- **App-level:** distributed tracing with hit/miss span tags; dashboards for hit rate, load latency, eviction rate, write-behind queue depth.

### 9.3 Representative real-world incidents
- **Mass TTL expiry avalanche:** a service warmed its cache at deploy with a single fixed TTL; every key expired at the same second hours later, the DB took the full uncached load and fell over (cascading timeouts). Fix: TTL jitter + soft/hard two-tier TTL. (A widely reported class of outage.)
- **Hot-key dogpile during a viral event:** one product/post became hot; its cache entry expired and tens of thousands of concurrent requests stampeded the DB in milliseconds. Fix: single-flight + refresh-ahead so hot keys never hard-expire under load.
- **Write-behind data loss:** a counter/metrics service used in-memory write-behind without persistence; a node crash lost the un-flushed buffer (minutes of counts). Fix: AOF/replication or a durable buffer; treat write-behind as loss-tolerant only.
- **`KEYS *` outage:** an admin script ran `KEYS *` on a large production Redis; the single thread blocked for seconds, all clients timed out. Fix: `SCAN`, and ban blocking commands in prod.
- **Multi-instance stale local cache:** an app used a local cache with invalidation only on the writing node; other instances served stale data for the full TTL. Fix: pub/sub broadcast invalidation + short L1 TTL.

---

## 10. Interview drill

**Q1. Explain cache-aside and walk through its read and write paths.**
*Model answer:* On read, the app checks the cache; on a hit it returns, on a miss it loads from the SoR, populates the cache (with a TTL), and returns. On write, it updates the SoR first, then **deletes** (invalidates) the cache entry; the next read repopulates lazily. The app orchestrates everything; the cache is a passive KV store.
- *Probe: Why delete instead of update the cache?* Delete is idempotent and race-tolerant — worst case is an extra miss. Updating reintroduces the stale-write-back race and caches data nobody may read.
- *Probe: What's the classic race?* A reader loads an old value and pauses; a writer updates the DB and deletes the (empty) cache; the reader resumes and writes the stale value, which then lives until TTL. Mitigate with short TTL, delayed double-delete, versioning, or single-flight.
- *Probe: Why is it resilient to cache outages?* Reads fall through to the DB and writes still persist; only latency/load degrade.

**Q2. Read-through vs cache-aside — what's actually different?**
*Model answer:* The read-time semantics are the same (lazy fill on miss). The difference is *who* loads: in read-through the cache library runs a configured loader transparently, centralizing load logic and typically adding single-flight; in cache-aside the application code does the load and population.
- *Probe: What does centralization buy you?* One place for retries, metrics, single-flight, and consistent fill logic — no duplicated, drift-prone cache code across call sites.
- *Probe: Downside?* You need a provider that supports loaders, and the cache becomes a hard dependency for reads.

**Q3. Write-through vs write-behind — when would you choose each? (senior-signal)**
*Model answer:* Write-through writes the SoR **synchronously** before returning — cache never holds unpersisted data, you get read-your-writes, but every write pays DB latency. Write-behind writes the cache instantly and flushes to the SoR **asynchronously and batched** — far higher write throughput and lower latency via coalescing, but a crash before flush loses acknowledged writes. Choose write-through for correctness-critical writes (profiles, orders); choose write-behind for high-volume, loss-tolerant writes (counters, metrics, telemetry) — and only with durability hardening (AOF, replication, or a durable buffer).
- *Probe: How do you make write-behind safe enough?* Persist the buffer (Redis AOF `everysec`, Kafka/Stream), replicate the cache, add backpressure on the queue, and accept a bounded loss window equal to the flush interval.
- *Probe: What does coalescing do for throughput?* Multiple writes to the same key collapse into one DB write — a counter hit 10k times flushes once.

**Q4. What is refresh-ahead and how does it differ from plain expiration?**
*Model answer:* Refresh-ahead proactively reloads an entry **before** it expires (e.g., at 75% of TTL) on access, returning the current value immediately and reloading asynchronously. Plain expiration removes the value, forcing the next reader to block on a synchronous load. Caffeine: `refreshAfterWrite` (non-blocking, serves stale during refresh) vs `expireAfterWrite` (removes, next read blocks).
- *Probe: Downside?* Wastes loads on keys that won't be reused (mitigated by gating on access), and serves slightly stale data during the refresh window; doesn't help cold/long-tail keys.
- *Probe: How do you combine the two?* `refreshAfterWrite(short).expireAfterWrite(longer)` — keep hot keys warm without blocking, but hard-drop keys nobody touches.

**Q5. Local vs distributed cache — tradeoffs? (senior-signal)**
*Model answer:* Local (Caffeine) is ~1000× faster (ns vs ms), has no network dependency, and survives cache-cluster outages, but each instance has its own copy → drift and a hard cross-fleet invalidation problem, and it doesn't survive restarts. Distributed (Redis) gives a single coherent shared view and easy central invalidation at the cost of a network hop and an operational dependency. Near-cache combines them for best-case latency but has the hardest (two-tier) coherence problem.
- *Probe: How do you invalidate a fleet of local caches?* Broadcast via pub/sub (Redis channel) or CDC; back it with a short TTL safety net because pub/sub has no delivery guarantee.
- *Probe: When is local clearly right?* Tiny, very hot, read-mostly data where per-node staleness within a short TTL is acceptable (feature flags, config).

**Q6. What is a cache stampede / thundering herd, and how do you prevent it?**
*Model answer:* When a hot key expires, many concurrent misses all hit the DB at once. Prevent with **single-flight** (one loader per key — Caffeine LoadingCache/AsyncLoadingCache, or a Redis `SET NX` mutex), **probabilistic early expiration** (readers refresh slightly early with rising probability so the herd is spread out, no locks), or **never-hard-expire hot keys + async refresh-ahead**.
- *Probe: How does single-flight work in Caffeine?* `get(key, loader)` guarantees the mapping function runs at most once per key concurrently; other threads block and share the result.
- *Probe: Lock vs probabilistic — which is better?* Probabilistic avoids lock contention and failover correctness issues with distributed locks; locks are simpler to reason about. Many shops prefer probabilistic early refresh.

**Q7. Define cache penetration, breakdown, and avalanche, with fixes.**
*Model answer:* **Penetration:** queries for nonexistent keys always miss and hit the DB → negative caching + Bloom filter + input validation. **Breakdown:** one very hot key expires and stampedes the DB → single-flight/mutex, never-expire-hot-keys + refresh. **Avalanche:** many keys expire simultaneously (or the cache fails), spiking DB load → TTL jitter, staggered warming, cache HA, circuit breaker.
- *Probe: How does a Bloom filter stop penetration?* It cheaply answers "possibly present / definitely absent" before the DB; "definitely absent" requests are rejected without touching the DB.
- *Probe: Why jitter TTLs?* So bulk-loaded keys don't all expire in the same instant and cause an avalanche.

**Q8. How do you keep a cache consistent with the DB, and what are the limits? (senior-signal)**
*Model answer:* Strategies, increasing in strength/cost: TTL-only (staleness ≤ TTL, simplest); invalidate-on-write (delete after DB write — near-immediate but only catches writers that go through your code); CDC/binlog invalidation (tail the DB log via Debezium/Canal — catches *all* writers, near-immediate, more infra); versioned reads/CAS (store a version, reject stale fills — strongest). There's no free strong consistency: a cache is a second copy, so you accept eventual consistency bounded by your invalidation latency, or you pay with locking/bypassing the cache for reads.
- *Probe: Why is CDC invalidation powerful?* It's decoupled from app code and reacts to every committed write, including out-of-band DB writes and migrations.
- *Probe: How do you guarantee read-your-writes?* Bypass the cache for the immediate post-write read, or update/invalidate synchronously before returning.

**Q9. Walk me through what Caffeine does internally on a get. (deep)**
*Model answer:* `getIfPresent` does a lock-free concurrent-hash-map lookup; on hit it records the access into a striped ring buffer (deferred, off-hot-path) so the eviction policy bookkeeping is amortized. `get(key, loader)` adds compute-if-absent with single-flight per key. Eviction uses W-TinyLFU: new entries go into a tiny LRU window; on overflow the candidate competes against the main region's victim using a 4-bit Count-Min Sketch frequency estimate, admitting only if more valuable; the sketch is periodically aged.
- *Probe: Why is W-TinyLFU better than LRU?* It resists scan pollution (one-off scans don't evict the hot set) and adapts to both recency and frequency, yielding near-optimal hit rates.
- *Probe: How is it so fast under contention?* Reads are lock-free; policy maintenance is batched via ring buffers and run by a single drainer, keeping the hot path nearly lock-free.

**Q10. You see DB load spiking every 10 minutes exactly. Diagnose. (senior-signal, scenario)**
*Model answer:* A 10-minute period strongly suggests synchronized TTL expiry — an avalanche from keys all set with a 10-minute TTL (e.g., warmed at deploy). Confirm by correlating DB spikes with cache `expired_keys`/miss-rate spikes and checking that TTLs are uniform. Fix: add TTL jitter, adopt refresh-ahead (soft/hard TTL) so hot keys refresh asynchronously instead of all expiring, and stagger warming.
- *Probe: What if it's one key, not many?* Then it's breakdown, not avalanche — look at per-key QPS / `--hotkeys`; fix with single-flight/refresh.
- *Probe: How would you prevent recurrence systemically?* Standardize jittered TTLs and refresh-ahead in the shared caching library so every team gets it by default.

**Q11. Why is Redis able to make `INCR` and `SETNX` atomic without locks?**
*Model answer:* Redis executes commands on a single thread (one at a time in its event loop), so each command runs to completion without interleaving — compound effects within a single command (or a single Lua script) are atomic by construction. That's also why blocking commands (`KEYS *`, huge `SMEMBERS`) are dangerous: they stall *all* clients.
- *Probe: How do you do an atomic multi-step op?* A Lua script via `EVAL` — it runs atomically as one unit.
- *Probe: Does single-threaded mean slow?* No — it avoids lock overhead and context switches; throughput is very high for small ops; you scale out via clustering/replicas.

**Q12. Design the caching for a product page at 100k RPS, 95% reads. (senior-signal, design)**
*Model answer:* Read-heavy with hot keys → multi-tier: a near-cache (Caffeine L1 with short TTL + refresh-ahead) in front of Redis (L2, coherent shared view) in front of the DB (SoR). Read-through fill with single-flight; cache-aside-style delete on writes plus CDC-based invalidation to catch all writers; broadcast L1 invalidations over Redis pub/sub with a short L1 TTL safety net; jittered TTLs to avoid avalanche; negative caching + Bloom filter for penetration; never-hard-expire hot keys (refresh-ahead). Observability on hit rate, load latency, eviction, and a circuit breaker protecting the DB.
- *Probe: Where do writes go?* DB first, then invalidate L2 and broadcast L1 invalidation; CDC as the authoritative invalidation backstop.
- *Probe: How do you protect the DB if Redis dies?* Circuit breaker + rate limiter in front of the DB, serve-stale-on-error from L1 where acceptable, and HA Redis (replicas/Sentinel/Cluster).

---

## 11. Glossary

- **AOF (Append-Only File):** Redis durability log of every write command, replayed on restart; `appendfsync everysec` risks ≤1s of writes.
- **Avalanche (cache):** many keys expire (or the cache fails) at once, spiking SoR load.
- **Bloom filter:** probabilistic set with no false negatives; rejects definitely-absent keys before the DB.
- **Breakdown (cache) / dogpile:** one very hot key expires and stampedes the SoR.
- **Cache-aside (lazy loading):** app checks cache, loads from SoR on miss and populates; deletes on write.
- **CAP theorem:** under a partition, choose Consistency or Availability; caches lean AP.
- **CDC (Change Data Capture):** streaming the DB's change log (Debezium/Canal) to drive cache invalidation.
- **CDN (Content Delivery Network):** geographically distributed edge caches (POPs).
- **Coalescing (write):** merging multiple pending writes to one key into a single SoR write.
- **Count-Min Sketch:** compact frequency estimator (may overcount, never undercount); used by W-TinyLFU.
- **Eviction:** the cache removing entries to free space, per an eviction policy (LRU/LFU/W-TinyLFU/…).
- **Expiration (TTL/TTI):** time-based invalidation; TTL = since write, TTI = since last access.
- **ETag / Cache-Control:** HTTP caching primitives (content fingerprint; freshness directives).
- **Eventual consistency:** copies converge after writes stop.
- **Hit / Miss / Hit ratio:** found / not found in cache; hits / (hits+misses).
- **Invalidation:** deliberately removing/updating a cached entry because the SoR changed.
- **JCache (JSR-107):** the Java caching standard API with `CacheLoader`/`CacheWriter`.
- **Lettuce / Jedis / Redisson:** Java Redis clients (async/Netty; sync; high-level objects+locks).
- **Locality (temporal/spatial):** recently/related data likely to be accessed again.
- **LRU / LFU / FIFO:** least-recently-used / least-frequently-used / first-in-first-out eviction.
- **Near-cache:** a local L1 cache backed by a distributed L2.
- **Negative caching:** caching "not found" results briefly to stop repeated SoR hits for absent keys.
- **Penetration (cache):** queries for nonexistent keys that always miss and hit the SoR.
- **POP (Point of Presence):** a CDN edge data center near users.
- **Probabilistic early expiration (XFetch):** refresh slightly before TTL with rising probability to avoid stampedes without locks.
- **QPS / RPS:** queries / requests per second.
- **RDB:** Redis point-in-time snapshot persistence.
- **Read amplification:** one logical action causing many reads of the same data.
- **Read-through:** the cache loads from the SoR transparently on a miss.
- **Read-your-writes:** a client sees its own writes on later reads.
- **Refresh-ahead:** proactively reload hot entries before they expire, asynchronously.
- **Single-flight / request coalescing:** ensuring only one loader runs per key under concurrent misses.
- **SoR (System of Record):** the durable authoritative store that owns the data.
- **Stampede / thundering herd:** many concurrent misses on the same key hitting the SoR at once.
- **Staleness:** cached value differing from the SoR; bounded by TTL/invalidation latency.
- **Strong consistency:** every read returns the latest write.
- **TTL / TTI:** time-to-live (since write) / time-to-idle (since last access).
- **W-TinyLFU:** Caffeine's window + frequency-sketch admission/eviction policy; near-optimal hit rates.
- **Working set:** the set of keys actually accessed in a time window.
- **Write-behind (write-back):** write cache instantly, flush to SoR asynchronously/batched.
- **Write-through:** write cache and synchronously write SoR before returning.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Five patterns:**
- **Cache-aside** — app orchestrates; read: check→load→fill; write: DB then **delete** cache. Resilient, simple; watch stale-write-back race + stampede.
- **Read-through** — cache loads via loader; centralized + single-flight. (Caffeine `LoadingCache`.)
- **Write-through** — cache writes SoR **sync** before return; never unpersisted; pays DB latency.
- **Write-behind** — cache writes SoR **async/batched**; fastest writes, **data-loss risk on crash** (need AOF/replication/durable buffer); coalesces.
- **Refresh-ahead** — reload hot keys **before** expiry, async; never block hot reads. (Caffeine `refreshAfterWrite`.)

**Where it lives:** client → CDN/edge → local (Caffeine, ns) → distributed (Redis, sub-ms) → near-cache (both). Local = fast but per-node drift; distributed = coherent but networked; near-cache = fastest but two-tier coherence.

**Three anomalies:** Penetration (absent keys → negative cache + Bloom). Breakdown (hot key expiry → single-flight/refresh). Avalanche (mass expiry → **TTL jitter** + HA + refresh).

**Key numbers:** RAM ~100ns; local map ~tens of ns; Redis LAN ~0.2–1ms; DB query ~1–50ms. Target hit ratio ≥90–95%. Redis default `maxmemory-policy=noeviction`, `appendfsync everysec` (≤1s loss). Caffeine eviction = W-TinyLFU.

**Decision rules:** read-heavy+simple → cache-aside; clean+single-flight → read-through; durable writes → write-through; high write rate+loss-ok → write-behind; hot+latency-sensitive → refresh-ahead. Delete (not update) on write. Always TTL even if you invalidate. Jitter TTLs. Single-flight hot keys. Negative-cache absent keys. Never `KEYS *` in prod.

**Consistency ladder:** TTL-only < invalidate-on-write (app writers only) < CDC/binlog invalidation (all writers) < versioned/CAS reads. No free strong consistency.

**Caffeine knobs:** `maximumSize`, `expireAfterWrite`/`Access`, `refreshAfterWrite`, `recordStats`, `LoadingCache`/`AsyncLoadingCache`.
**Redis knobs/cmds:** `SET … EX/NX`, `INCR`, `SETNX`, `UNLINK`, `SCAN`(not KEYS), `maxmemory`, `maxmemory-policy`, `appendonly`, `notify-keyspace-events`; diagnose with `INFO stats`, `SLOWLOG`, `LATENCY DOCTOR`, `--bigkeys`, `--hotkeys`.

### 12.2 Self-test (no answers — recall actively)

1. Walk through the exact read and write paths of cache-aside, and name the race condition that can leave a stale value in the cache. How do you bound or eliminate it?
2. You must support 100k RPS on a read-mostly endpoint with hot keys and a strict latency SLA. Design the layering, patterns, invalidation, and stampede/penetration/avalanche defenses. Justify each choice.
3. Compare write-through and write-behind on durability, latency, throughput, and failure behavior. What concrete steps make write-behind "safe enough," and what loss window remains?
4. Explain `expireAfterWrite` vs `refreshAfterWrite` in Caffeine. Give a configuration that keeps hot keys warm without ever blocking a reader, yet drops untouched keys — and explain why each setting is needed.
5. Your DB load spikes at an exact periodic interval. Give the two most likely cache causes, how you'd distinguish them with specific metrics/commands, and the fix for each.
6. Describe four strategies for keeping a cache consistent with its SoR, ordered by strength, and state what each fails to catch and what it costs.
7. Why are `INCR` and `SETNX` atomic in Redis, and what production hazard arises from the same property?
8. You run a local (in-JVM) cache across 12 application instances. A write on one instance must be reflected on the others within ~1 second. How do you achieve this, and what is the failure mode of your mechanism — and your backstop for it?

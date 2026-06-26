# Cache Consistency & Multi-Layer Caching

> **Concept area:** Caching & In-Memory Stores
> **Subtopic:** Cache Consistency & Multi-Layer Caching
> **Reader:** A senior Java/JVM backend engineer who wants to master this end-to-end — design, operate, debug, teach, and interview.

---

## 1. Overview & where it fits

### 1.1 What it is

**Caching** is keeping a copy of data somewhere *cheaper or closer to read* than the authoritative source, so that future reads are faster and the source is offloaded. The authoritative source — the place that holds the single, correct, durable version of the data — is called the **system of record** or **source of truth** (usually a database, but it can be another service, a file, or a remote API).

**Multi-layer caching** (also called **tiered** or **layered** caching) means you stack *several* caches in front of that source of truth, each one closer to the consumer than the last. A read request passes through the layers in order; whichever layer first holds the value answers, and the request never travels further down. Each layer is faster but smaller and more local than the one below it.

**Cache consistency** (also called **cache coherence** when we talk about multiple copies agreeing) is the question: *how stale, divergent, or wrong are the cached copies relative to the source of truth, and relative to each other?* The instant you make a copy of mutable data, you create the possibility that the copy and the original disagree. Consistency is the discipline of bounding, detecting, and repairing that disagreement.

The two topics are inseparable: the more layers you add, the faster your reads, but the more independent copies of the data exist, and the harder it becomes to keep them all in agreement. This document is about that tension.

### 1.2 The problem it solves

Two problems, really:

1. **Latency and throughput.** A read from a database over the network might take 1–10 milliseconds (ms). A read from a local in-process cache takes tens to hundreds of *nanoseconds* (ns) — roughly 10,000× to 100,000× faster. A read from CPU L1 cache is ~1 ns. Caching turns slow, contended reads into fast, local ones.

2. **Cost and protection.** Databases and downstream services are expensive to scale and easy to overwhelm. A cache absorbs the bulk of read traffic (often 90–99%+ of it), so the source of truth only sees the small fraction that *misses* the cache. This is **load shedding** and **origin protection**.

The cost you pay for those benefits is **staleness risk** and **complexity**: copies can lag the source, and now you must reason about *when* and *how* they get refreshed or invalidated.

> **Mental model (one paragraph).** Think of caching as a series of progressively faster "memory" tiers, exactly like the CPU memory hierarchy (registers → L1 → L2 → L3 → RAM → disk), but extended across the *whole distributed system*: CPU caches → an in-process (local) cache → a shared distributed cache (e.g. Redis) → a CDN at the network edge → the user's browser. A read trickles upward through the tiers until it hits a copy; a write must somehow ripple back down through all of them to keep the copies honest. **Reads are easy; keeping the copies honest on writes is the entire hard problem.** Every consistency strategy is just a different answer to "what do we do to the copies when the truth changes?" — and every answer trades latency, freshness, and complexity against each other.

### 1.3 When you reach for it

- Read-heavy workloads where the same data is read far more often than it changes (the **read:write ratio** is high). Product catalogs, user profiles, configuration, pricing, feature flags, rendered HTML/JSON fragments.
- When the source of truth is a latency or throughput bottleneck.
- When you need to serve users globally with low latency (CDN/edge layers).
- When the cost of a slightly stale read is *acceptable* for some bounded window (this is the crux — you must know your **staleness tolerance**).

### 1.4 When NOT to reach for it (or be very careful)

- Data that must always be *perfectly* current for correctness: account balances at the moment of a transfer, inventory at the moment of checkout, security/permission decisions. (You can still cache these, but with strong invalidation and short bounds — see §7.)
- Write-heavy data with low read reuse — caching just adds invalidation overhead with little hit-rate payoff.
- Data with high cardinality and low repeat-access — the **working set** (the set of items actually accessed in a time window) doesn't fit and you thrash.

---

## 2. Foundations from first principles

We'll build the vocabulary from zero. If you already know a term, skim; nothing here is assumed.

### 2.1 The cache, formally

A cache is a **key → value store with eviction**, sitting in front of a slower **backing store**. Three operations dominate:

- **Lookup (get):** Given a key, return the cached value if present.
- A **cache hit** = the key was present. A **cache miss** = it wasn't, so you must fetch from the backing store (and usually populate the cache).
- The **hit ratio** (or hit rate) = hits / (hits + misses). The single most important cache metric. A 95% hit ratio means 1 in 20 reads falls through to the origin.

Because a cache is smaller than the backing store, it must **evict** (throw out) entries to make room. The **eviction policy** decides what to throw out:

- **LRU (Least Recently Used):** evict the entry not touched for the longest time. Most common default; assumes recent access predicts future access (**temporal locality**).
- **LFU (Least Frequently Used):** evict the entry accessed the fewest times. Better for stable popularity, worse for shifting trends.
- **FIFO (First In First Out):** evict the oldest-inserted entry regardless of use. Simple, cache-unaware of access.
- **W-TinyLFU:** a modern admission+eviction policy (used by Caffeine, see §4) combining a frequency sketch with LRU-like recency; near-optimal hit ratios in practice.

### 2.2 TTL, TTI, and expiration vs eviction

- **TTL (Time To Live):** a fixed lifetime after which an entry is considered **expired** (stale) regardless of access. The most important *consistency* knob in caching — it bounds how stale any entry can be. "Expire after write."
- **TTI (Time To Idle):** lifetime since last *access*; resets on every read. "Expire after access." Keeps hot data alive, drops cold data.
- **Expiration** ≠ **eviction**. Expiration is about *time/correctness* (the value is too old). Eviction is about *space* (we need room). Both remove entries, but for different reasons. A value can be evicted while still fresh, or expire while there's plenty of room.

### 2.3 Staleness, freshness, and consistency models

- **Stale data:** a cached copy that no longer matches the source of truth because the source changed and the copy didn't.
- **Freshness:** how recently the copy was validated against truth. Often expressed as **age** (now − time the copy was fetched) bounded by **max-age/TTL**.
- **Strong consistency:** every read returns the most recent write. As if there were one copy. Expensive across layers/regions.
- **Eventual consistency:** if writes stop, all copies *eventually* converge to the latest value. They may disagree transiently. This is what most caches actually give you.
- **Bounded staleness:** a *guarantee* that copies are never more than X seconds (or N versions) behind. TTL gives you a soft form of this: max staleness ≈ TTL (plus propagation delay).
- **Read-your-writes (read-after-write) consistency:** a *single client* that just wrote a value will read that same value back (not an older cached copy). Subtle and important — see §2.7.
- **Monotonic reads:** a client never sees data go *backwards* in time across successive reads (e.g., read v5, then v3). Multi-layer caches can violate this if different layers hold different versions.

> **CAP theorem (explained, since we'll lean on it).** In a distributed system that gets **partitioned** (a network split where nodes can't talk), you must choose between **C**onsistency (every read sees the latest write) and **A**vailability (every request gets a non-error answer). You can't have both *during a partition*. Caches almost always lean **AP**: keep serving (possibly stale) data rather than erroring. **PACELC** extends this: *if Partitioned, choose A or C; Else (normal operation) choose between Latency and Consistency.* Caching is fundamentally an "**EL**" choice — in normal operation we trade Consistency for Latency.

### 2.4 The two cache topologies: local vs distributed

- **Local (in-process) cache:** lives inside your application's heap (e.g., a Java `Caffeine` cache in your JVM). Fastest possible (nanoseconds, no network, no serialization). But: each application instance has its *own* copy, so N instances = N independent caches that can disagree. Bounded by heap; lost on restart.
- **Distributed (remote) cache:** a separate service (e.g., Redis, Memcached) shared by all instances over the network. One logical copy (per key), so instances agree. Slower (network + serialization, ~0.2–2 ms typical same-AZ). Survives app restarts; can be huge.
- **Near cache / two-tier:** combine them — a small local cache in front of the distributed cache. Best latency *and* shared truth, but reintroduces the "N local copies" coherence problem on top.

### 2.5 The CPU memory hierarchy (the original multi-layer cache)

Before app caches existed, CPUs already did multi-layer caching, and the same principles apply.

- **Registers:** a handful of slots inside the CPU core, ~0 cycles.
- **L1 cache:** ~32–64 KB per core, ~1 ns / ~4 cycles. Split into L1d (data) and L1i (instructions).
- **L2 cache:** ~256 KB–2 MB per core, ~3–10 ns.
- **L3 cache (LLC, last-level):** shared across cores, ~8–64 MB, ~10–40 ns.
- **Main memory (RAM):** ~100 ns.
- **Disk/SSD:** ~10–100 µs (SSD) to ~10 ms (HDD).

A **cache line** is the unit of transfer — typically **64 bytes**. The CPU never fetches one byte; it fetches the whole line. This matters for false sharing (§7).

**Cache coherence in hardware (MESI protocol).** When multiple cores cache the same memory line, hardware must keep them coherent. **MESI** tags each cached line as **M**odified (this core has the only, dirty copy), **E**xclusive (only copy, clean), **S**hared (multiple clean copies), or **I**nvalid (stale, must refetch). When one core writes, it broadcasts an invalidation so other cores mark their copy Invalid and refetch. **This is the exact same pattern we'll re-invent in software for distributed caches: write → invalidate other copies → they refetch.** The hardware solved multi-layer coherence decades ago; distributed systems just do a slower, lossier version of MESI over a network.

> **Why this matters for app developers:** The CPU layer is *transparent* — you can't directly control it, but cache-friendly data layout (arrays over linked lists, avoiding false sharing) gives 2–10× speedups. The app/distributed/CDN layers are *explicit* — you choose the policy. The principles (locality, lines/granularity, invalidation, coherence) are identical at every layer.

### 2.6 The full caching ladder (CPU → browser)

From closest-to-data to closest-to-user:

| Layer | Where it lives | Typical latency | Scope/Shared? | You control it via |
|---|---|---|---|---|
| CPU caches (L1/L2/L3) | On the CPU die | 1–40 ns | Per core / per socket | Data layout (indirect) |
| App heap structures | JVM heap | 10–100 ns | Per process | Your code |
| Local cache (Caffeine/Ehcache) | JVM heap (or off-heap) | 50–500 ns | Per process | Cache API + config |
| Distributed cache (Redis/Memcached) | Separate node(s) | 0.2–2 ms (same AZ) | Cluster-wide | Cache client + server config |
| Database buffer pool | DB server RAM | sub-ms | DB cluster | DB config (implicit) |
| Reverse proxy / gateway cache (Varnish, Nginx) | Your edge/DC | sub-ms–ms | DC-wide | HTTP cache headers + config |
| CDN (CloudFront, Fastly, Cloudflare, Akamai) | Global PoPs | 1–50 ms to user | Global, per-PoP | HTTP headers + purge API |
| Browser cache | End-user device | ~0 (disk read) | Per user | HTTP headers |

> **PoP (Point of Presence):** a CDN's physical location (a data center near users). A CDN has dozens to hundreds of PoPs worldwide; a user is served from the nearest one. **Edge** = "as close to the user as possible," i.e., the CDN PoP or the browser.

### 2.7 The read-after-write problem (the canonical pitfall)

A user updates their profile name from "Alex" to "Alexandra." The write goes to the DB. Then the same user reloads the page. Their request hits an app instance whose *local cache* still has "Alex" — or the distributed cache wasn't invalidated yet — so they see their *own* change vanish. This is a violation of **read-your-writes consistency**, and it's the #1 user-visible caching bug. We'll return to its fixes in §6 and §7, but note now: it's caused by a *copy that wasn't updated/invalidated when the write happened*, and it's especially nasty with **per-instance local caches** because there's no single place to invalidate.

### 2.8 Negative caching (foundational concept)

A **negative cache** stores the *absence* of a value — "this key does not exist" or "this lookup returned 404/empty." Without it, a key that doesn't exist will *always miss the cache* and *always hit the origin*, every single time it's requested. If an attacker (or a buggy client) requests millions of non-existent keys, every one falls through — this is a **cache penetration** attack. Negative caching stores a sentinel ("not found") with a short TTL so repeated misses are absorbed. The cost: if the item *is later created*, you might serve "not found" until the negative entry's TTL expires. So negative TTLs are kept short (seconds to a minute). See §6 and §7 for hardening (Bloom filters, etc.).

---

## 3. How it works internally

This is the heart of the doc. We'll trace the actual control flow and data flow for each caching pattern, then for multi-layer reads/writes, then for invalidation propagation.

### 3.1 The five canonical cache patterns (with full traces)

These define *who reads/writes the cache and when*. They are the foundation of everything else.

#### 3.1.1 Cache-aside (lazy loading / "look-aside")

The application code orchestrates the cache; the cache doesn't know about the DB.

**Read flow:**
1. App asks cache for key `K`.
2. **Hit** → return value. Done.
3. **Miss** → app reads `K` from DB.
4. App writes `(K, value)` into cache (usually with a TTL).
5. App returns value.

**Write flow (the contentious part):**
1. App writes new value to DB.
2. App **invalidates** (deletes) `K` from cache. *(Not updates — see §3.3 for why delete beats update.)*

```
READ:                          WRITE:
  get(K) ──hit──> value          UPDATE db SET ...
     │                               │
     └──miss──> SELECT db           DELETE cache[K]   (invalidate)
                   │
                set cache[K]=v, ttl
                   │
                return v
```

**Properties:** Only requested data is cached (lazy = efficient memory). Cache and DB can be in different technologies. The app must handle the orchestration. This is the **most common** pattern in practice. Its consistency hazards are the famous race conditions of §3.3.

#### 3.1.2 Read-through

The cache itself knows how to load from the DB. The app only ever talks to the cache.

**Read flow:**
1. App asks cache for `K`.
2. Hit → return. Miss → the **cache library** synchronously calls a loader function you supplied, which fetches from DB, stores, and returns.

The difference from cache-aside is *who calls the DB*: in cache-aside it's your app code; in read-through it's the cache library (via a `CacheLoader`). Caffeine's `LoadingCache` and Spring's `@Cacheable` are read-through. Benefit: dedup of concurrent misses (see §3.6) and cleaner code.

#### 3.1.3 Write-through

Every write goes *through* the cache, which synchronously writes to the DB before returning.

**Write flow:**
1. App writes `(K, v)` to cache.
2. Cache writes `(K, v)` to DB **synchronously**.
3. Only when the DB write succeeds does the call return.

**Properties:** Cache and DB are always consistent *for that key* (the cache is never ahead of or behind the DB on a successful write). Reads after writes are clean. Cost: writes are as slow as the DB write *plus* cache write. Cache holds even data that's never read (wasteful unless paired with read-through).

#### 3.1.4 Write-behind (write-back)

The write goes to the cache and returns immediately; the DB is updated **asynchronously** later (batched/coalesced).

**Write flow:**
1. App writes `(K, v)` to cache → returns immediately (fast).
2. A background flusher later writes `(K, v)` (or a batch) to DB.

**Properties:** Fastest writes, great for write-heavy/bursty workloads, enables write coalescing (10 updates to the same key → 1 DB write). **Danger:** if the cache node crashes before the flush, those writes are **lost** — the cache became a temporary source of truth and it wasn't durable. Also the DB lags the cache (DB is now the *stale* copy). Requires careful durability (write-ahead log, replication) for anything important.

#### 3.1.5 Refresh-ahead (proactive refresh)

The cache *proactively* reloads entries that are about to expire and are "hot," *before* a request needs them — so requests rarely hit an expired entry.

Caffeine's `refreshAfterWrite` implements this: after the refresh interval, the *next* access returns the **old** value immediately and triggers an **asynchronous** reload in the background. The requester never blocks; subsequent requests get the fresh value. Contrast with `expireAfterWrite`, where the next access *blocks* on a synchronous reload. Refresh-ahead trades a tiny bit of extra staleness (you serve old once during refresh) for never blocking on reload — excellent for hot keys.

#### 3.1.6 Pattern comparison

| Pattern | Who reads DB | Who writes DB | Write latency | Consistency on write | Memory use | Crash risk |
|---|---|---|---|---|---|---|
| Cache-aside | App | App | Fast (DB + invalidate) | Eventual; race-prone | Lazy (only read keys) | None extra |
| Read-through | Cache lib | App (separately) | n/a (read path) | n/a | Lazy | None extra |
| Write-through | (paired) | Cache lib (sync) | Slow (DB sync) | Strong per-key | Holds all writes | None |
| Write-behind | (paired) | Cache lib (async) | Fastest | Weak (DB lags) | Holds all writes | **Data loss** |
| Refresh-ahead | Cache lib (async) | n/a | n/a | Bounded stale | Hot keys refreshed | None |

In practice you often **combine**: read-through + write-through (e.g., Spring's `@Cacheable` + `@CachePut`/`@CacheEvict`), or cache-aside + refresh-ahead for hot keys.

### 3.2 Multi-layer read flow (the waterfall)

A read in a fully layered system (say browser → CDN → reverse proxy → distributed cache → DB) cascades:

1. **Browser cache:** Is there a fresh copy locally (per `Cache-Control: max-age`)? If yes and not expired → serve instantly, **zero network**. If expired but has a validator (`ETag`/`Last-Modified`) → send a **conditional request** (see §3.7).
2. **CDN PoP:** The request reaches the nearest PoP. Fresh copy for this **cache key** (§3.8)? Hit → serve from edge (1–50 ms). Miss → forward toward origin (this is a "fill").
3. **Reverse proxy / API gateway (Varnish/Nginx):** Same logic at the data-center edge.
4. **Application + local cache:** App checks its in-process cache. Hit → return.
5. **Distributed cache (Redis):** App checks Redis. Hit → return (and optionally populate the local cache — "near cache").
6. **Database (and its buffer pool):** Miss all the way down → query the DB. The DB's own buffer pool may still serve from RAM.
7. The value then **back-fills** each layer on the way out, each applying its own TTL.

**Key insight — independent TTLs per layer.** Each layer expires on its own clock. A value can be fresh in Redis but stale in a local cache, or fresh at the CDN but expired in the browser. The *effective* staleness of what a user sees is the **sum of the layers' independent lags**, bounded by the **longest TTL on the path**. This is why naive multi-layer caching can show data that's *much* staler than any single TTL suggests.

> **Worked example of compounding staleness.** Suppose: browser `max-age=60s`, CDN TTL `300s`, local cache TTL `30s`, Redis TTL `120s`. A write happens. Worst case before *every* layer reflects it: a browser tab loaded just before the write keeps showing old data for up to 60s; even after it revalidates, the CDN may still serve a 300s-old fill; behind that, Redis may be 120s stale; etc. Worst-case user-visible staleness ≈ the **path's max TTL** if layers fill from each other (≈300s here), not the min. **Design rule: TTLs should generally *decrease* as you move closer to the user**, or you risk a near-layer pinning stale data fetched from a farther layer. (Common practical rule: edge/browser TTLs short; deeper shared-cache TTLs can be longer because you can purge them explicitly.)

### 3.3 The cache-aside write race conditions (deep)

This is the most asked-about, most misunderstood part of cache consistency. Two classic races.

#### 3.3.1 Why **delete (invalidate)**, not **update**, the cache on write

If two concurrent writers both *update* the cache after writing the DB, their cache writes can land in the *opposite order* of their DB writes, leaving the cache holding the **older** value permanently (until TTL). Deleting the entry instead means the next reader simply reloads the current DB value. Deletion is **idempotent and order-insensitive** in a way updates are not. Hence the near-universal rule: **on write, invalidate (delete) the cache entry; let the next read repopulate it.**

#### 3.3.2 The "stale set" race (read miss interleaving a write)

Even with delete-on-write, this interleaving corrupts the cache:

```
Time →
T1 (reader):   miss K → SELECT db → gets v_old ............ set cache[K]=v_old
T2 (writer):              UPDATE db (v_new) → DELETE cache[K]
```

The reader read `v_old` *before* the writer updated, then wrote `v_old` into the cache *after* the writer's delete. The cache now holds `v_old` until TTL — a stale-set that survives the invalidation. This is rare (requires a slow read to straddle a write) but real under load.

**Fixes, in increasing strength:**

- **TTL backstop:** the stale entry self-heals when TTL expires. Cheap, bounds the damage, always do it. Doesn't *prevent* the window.
- **Delayed double-delete:** writer deletes the cache key, updates DB, then deletes the key *again* after a short delay (e.g., 0.5–1s, longer than a typical read). The second delete clears any stale-set that snuck in during the window. Common in high-traffic Chinese tech stacks; pragmatic but hacky.
- **Versioning / CAS on set:** store a version with each value; a reader only sets the cache if its version is ≥ what's there (compare-and-swap). Eliminates the stale-set but needs version tracking.
- **Read repair via the DB's change stream (CDC):** instead of the app invalidating, a **Change Data Capture** pipeline (reading the DB's replication log — e.g., MySQL binlog via Debezium) emits invalidations *after* commits in commit order, so the cache delete always follows the actual write. This decouples invalidation from app code and orders it correctly. The gold standard for large systems (see §7).
- **Single-writer / serialized updates per key:** route all writes for a key through one owner (sharding by key) so there's no concurrent write race.

> **CDC (Change Data Capture):** a technique that taps a database's transaction/replication log to emit a stream of every row change (insert/update/delete) in commit order. Tools: **Debezium** (open source, Kafka-based), AWS DMS, Maxwell. Because it reads the log *after* commit, the events are ordered and never miss a write — ideal for driving cache invalidation reliably.

#### 3.3.3 The cache↔DB consistency strategies and their failure windows

| Strategy | What happens on write | Failure window / hazard |
|---|---|---|
| **Update DB, then delete cache** (recommended) | Commit DB → delete key | Stale-set race (§3.3.2); bounded by TTL. If the delete *fails* after commit → cache stale until TTL. |
| **Delete cache, then update DB** | Delete key → commit DB | A read between the two reloads the **old** DB value into cache → stale until TTL. Worse than the above. |
| **Update DB, then update cache** | Commit DB → put new value | Concurrent writers can reorder the puts → permanent stale (§3.3.1). |
| **Update cache, then update DB** | Put value → commit DB | If DB write fails → cache ahead of DB (lost durability); reads see uncommitted data. |
| **Write-through (sync both)** | Atomic-ish both | If not transactional, a crash between writes splits them; needs 2-phase or transactional outbox. |
| **CDC-driven invalidation** | Commit DB → log → consumer deletes key | Propagation delay (ms–seconds); consumer lag; but correct ordering. |

**Universal truths:**
- *There is no race-free cache-aside without either a TTL backstop or an ordered invalidation channel (CDC).* Plan for the cache to be wrong sometimes; bound how wrong with TTL.
- *The delete can always fail.* Treat invalidation as best-effort + TTL backstop, or make it reliable with a queue/CDC + retries.

### 3.4 Ensuring the DB write and the cache invalidation both happen (dual-write problem)

If your app does `commitDB(); deleteCache();` and crashes between them, the cache is stale until TTL. This is the **dual-write problem** — two stores updated without a transaction. Solutions:

- **Transactional outbox:** within the *same DB transaction* as the write, insert a row into an `outbox` table describing the invalidation. A separate poller (or CDC on the outbox table) reads committed outbox rows and performs the cache delete, retrying until success. Because the outbox row is committed atomically with the data, you never lose the invalidation. This is the standard reliable pattern.
- **CDC** (as above) — the simplest correct option if you already have a binlog reader.
- **At-least-once invalidation queue:** publish the invalidation to a durable queue (Kafka/SQS) and have a consumer apply it with retries. Combine with idempotent deletes (deletes are naturally idempotent).

### 3.5 Multi-layer invalidation & propagation

When truth changes, you must propagate the invalidation **down to every layer** that might hold a copy. Strategies:

- **TTL-only (passive):** don't actively invalidate; just wait for each layer's TTL. Simple, but max staleness = each layer's TTL. Fine for tolerant data.
- **Explicit purge (active):** call each layer's purge/invalidate API. Distributed cache: `DEL key`. CDN: purge API by URL or **surrogate key** (§3.8). Reverse proxy: PURGE request. Local caches are the hard part (below).
- **Hybrid (recommended):** active purge for fast convergence + TTL backstop in case a purge is missed.

**Invalidating per-instance local caches — the pub/sub fan-out.** Because each app instance has its own local cache, you can't just delete one place. You broadcast an invalidation event that *every* instance subscribes to:

1. On write, app publishes `invalidate K` to a pub/sub channel (Redis Pub/Sub, Kafka topic, NATS, etc.).
2. Every app instance is subscribed; on receiving the event, each evicts `K` from its **local** cache.
3. The distributed cache key is also deleted (once, centrally).

```
   writer ──UPDATE db──▶ DELETE redis[K] ──▶ PUBLISH "inv K"
                                                │
              ┌─────────────────────┬──────────┴──────────┐
           instance A            instance B            instance C
          localCache.del(K)    localCache.del(K)    localCache.del(K)
```

> **Pub/Sub (publish–subscribe):** a messaging pattern where publishers send messages to a named channel/topic without knowing who listens; subscribers receive all messages on channels they subscribed to. Decouples sender from receivers. **Redis Pub/Sub** is fire-and-forget (no persistence — a subscriber that's down misses the message); **Kafka** persists, so a restarted consumer can catch up. For cache invalidation, missed messages mean transient staleness, recovered by the TTL backstop.

**Propagation delay.** Every active invalidation has latency: pub/sub delivery (ms), CDN purge (seconds to ~minutes globally), consumer lag. During this window, layers disagree. **You cannot make multi-layer invalidation instantaneous; you can only bound and observe the delay.** This is the irreducible "consistency vs latency" cost of layering (§8).

### 3.6 Thundering herd, cache stampede, and dogpile (and dedup)

When a hot key expires (or the cache is cold), *many concurrent readers miss at once* and *all* hit the DB simultaneously — a **thundering herd / cache stampede / dogpile**. The DB can fall over from the spike. Internal mechanisms to prevent it:

- **Request coalescing (single-flight):** only the *first* missing reader loads from the DB; concurrent readers for the same key **wait** and share that one result. Caffeine's `LoadingCache.get(key, loader)` does this per key. In Go this is `singleflight`. This is built into read-through caches and is a major reason to prefer them over hand-rolled cache-aside for hot keys.
- **Mutex / distributed lock on miss:** the first miss acquires a lock (e.g., Redis `SET key NX PX`), loads, populates, releases; others briefly wait or serve stale. Needed when coalescing must span *multiple app instances* (local single-flight only dedups within one JVM).
- **Probabilistic early expiration (XFetch):** before an entry actually expires, each read has a small, rising probability of *proactively* refreshing it, so refreshes are spread out instead of synchronized at the TTL boundary. Avoids the synchronized stampede when many entries share an expiry.
- **Stale-while-revalidate:** serve the stale value to readers while *one* background task refreshes it (see §3.7). No reader blocks; origin sees one refresh.
- **TTL jitter:** add randomness to TTLs (e.g., `ttl ± 10%`) so a batch of keys cached together don't all expire at the same instant.

### 3.7 HTTP caching internals (browser, CDN, reverse proxy)

The browser, CDN, and reverse proxy all speak the **HTTP caching protocol** (RFC 9111). Understanding it is essential because layers 6–8 are governed by HTTP headers, not your code.

**Freshness model.** A response is **fresh** if its age < its freshness lifetime; otherwise **stale**. Lifetime is set by (in priority order):
- `Cache-Control: s-maxage=N` — lifetime in *shared* caches (CDN/proxy), in seconds.
- `Cache-Control: max-age=N` — lifetime everywhere (browser uses this; shared caches use it unless `s-maxage` overrides).
- `Expires: <date>` — legacy absolute expiry.

**Cacheability directives:**
- `public` — any cache may store it. `private` — only the browser (not shared CDN); use for per-user data.
- `no-cache` — *may* store, but must **revalidate** with the origin before serving (a conditional request). NOT "don't cache."
- `no-store` — never store anywhere. The true "don't cache."
- `must-revalidate` — once stale, must revalidate (can't serve stale on error).
- `immutable` — never revalidate during freshness (for fingerprinted assets like `app.4f2a.js`).

**Validators & conditional requests (revalidation).** When a cached copy is stale, the cache can *ask the origin if it's still valid* instead of refetching the whole body:
- `ETag: "abc123"` — an opaque version token for the resource. The cache sends `If-None-Match: "abc123"`. If unchanged, origin returns **304 Not Modified** (tiny, no body) and the cache reuses its copy with a refreshed lifetime. If changed, **200** with the new body.
- `Last-Modified: <date>` — the cache sends `If-Modified-Since: <date>`; same 304/200 logic.
Revalidation turns a full transfer into a cheap "still good?" check — huge bandwidth savings.

**Stale-serving extensions (RFC 5861) — the consistency/latency sweet spot:**
- `stale-while-revalidate=N` — after freshness expires, the cache may serve the **stale** copy for up to N more seconds *while it revalidates in the background*. Users get instant (slightly stale) responses; the origin sees one async refresh. Kills latency spikes at TTL boundaries.
- `stale-if-error=N` — if the origin is **down or errors**, serve the stale copy for up to N seconds instead of failing. A free availability boost. (Lean AP — see CAP.)

Example header for a cacheable API JSON that tolerates brief staleness:
```
Cache-Control: public, max-age=30, s-maxage=300, stale-while-revalidate=60, stale-if-error=86400
ETag: "v42-9af3"
```
Interpretation: browsers treat fresh for 30s; CDN/proxy fresh for 300s; after that, serve stale up to 60s while refreshing; if origin errors, serve stale up to 24h; revalidate with the ETag.

**`Vary` header.** Tells caches that the response varies by certain request headers, so the cache key must include them. `Vary: Accept-Encoding` (gzip vs not), `Vary: Accept-Language`, etc. Misusing `Vary` (e.g., `Vary: User-Agent`) explodes cache cardinality and tanks hit ratio — a classic mistake.

### 3.8 CDN internals: cache keys, surrogate keys, and purging

**Cache key.** The identity under which the CDN stores a response. By default it's roughly the **host + path + query string** (sometimes a subset). You can customize it: include/exclude specific query params, headers, or cookies. Getting this right is critical:
- *Too broad* (e.g., including a per-user session cookie or a cache-busting random param) → near-zero hit ratio (every request is "unique").
- *Too narrow* (ignoring a param that changes the response, like `?currency=EUR`) → you serve the **wrong** cached variant to users. A real and dangerous bug.

**Surrogate keys / cache tags.** A way to tag a cached object with one or more labels so you can purge *groups* by tag instead of enumerating URLs. E.g., tag every page that shows product 123 with `product-123`; when that product changes, issue one purge for tag `product-123` and *all* pages displaying it drop instantly. Fastly calls these **Surrogate-Key**; Cloudflare calls them **Cache-Tag**; Akamai has cache tags too. The response sets `Surrogate-Key: product-123 category-shoes`; the purge API targets a key. This is the single most powerful CDN-consistency tool — it solves "I changed one entity that appears on 10,000 pages."

**Purging mechanisms:**
- **Purge by URL** — invalidate one object. Precise but you must know every URL.
- **Purge by surrogate key/tag** — invalidate all objects with that tag (above).
- **Purge all / wildcard** — nuke the whole cache or a path prefix. Causes a stampede to origin afterward — use sparingly.
- **Soft purge** — mark objects stale (so they serve via `stale-while-revalidate`) instead of deleting them; avoids origin stampede. **Hard purge** deletes immediately.
- **Purge latency:** typically seconds globally for tag/URL purges on modern CDNs (Fastly advertises ~150 ms; others seconds to minutes). Plan for non-zero propagation delay (§3.5).

**Surrogate-Control header.** Like `Cache-Control` but addressed *only* to the CDN/surrogate, invisible to the browser: `Surrogate-Control: max-age=3600`. Lets you cache long at the edge while telling browsers something stricter.

### 3.9 Distributed-cache internals you should know (Redis focus)

- **Single-threaded command execution (Redis core):** Redis processes commands one at a time on a single thread (I/O is multiplexed; recent versions add I/O threads for networking, but command execution is serialized). This makes individual commands atomic — no locks needed for a single op, which is why `INCR`, `SET NX`, and Lua scripts are powerful primitives.
- **Atomic multi-step via Lua / `MULTI`:** to do "get-then-set-if-version" atomically, use a Lua script (runs atomically server-side) or `WATCH/MULTI/EXEC` (optimistic transaction). Essential for CAS-style cache updates and distributed locks.
- **Eviction policies (`maxmemory-policy`):** `noeviction` (errors on full — dangerous for a cache), `allkeys-lru`, `allkeys-lfu`, `volatile-lru` (only keys with TTL), `volatile-ttl`, `allkeys-random`, etc. For a pure cache, `allkeys-lru` or `allkeys-lfu` is typical. Default is `noeviction` (a footgun if you treat Redis as a cache and forget to change it).
- **Keyspace notifications:** Redis can publish events when keys change/expire (`notify-keyspace-events`). Useful to drive local-cache invalidation off Redis itself.
- **Client-side caching (RESP3 tracking):** Redis 6+ supports server-assisted client-side caching — the server *tracks* which keys a client cached and *pushes an invalidation* when they change. This is essentially MESI-over-the-wire for a near cache: the client keeps a local copy and Redis tells it when to drop it. Two modes: **default tracking** (server remembers exact keys per client) and **broadcast (BCAST)** mode (server notifies by key prefix, less memory). This is the cleanest built-in solution to the "local cache coherence" problem if you're on Redis.

### 3.10 Lifecycle / state machine of a cache entry

```
 [absent] ──load/set──▶ [fresh] ──(age ≥ TTL)──▶ [stale]
    ▲                      │  │                      │
    │                  hit │  │ invalidate/delete    │ revalidate? ──304──▶ [fresh]
    │                      ▼  ▼                      │              ──200──▶ replace value
    └──────evict (space)──[gone]                     ├─ swr window: serve stale + bg refresh
                                                     └─ expire fully ──▶ [gone] (next read = miss)
 Negative entry: [absent in DB] ──set sentinel, short TTL──▶ [neg-cached] ──TTL──▶ [absent]
```

States: **absent** (no entry) → **fresh** (within TTL) → **stale** (past TTL, maybe serve-while-revalidate) → **gone** (evicted/expired). Transitions are driven by reads (hit/miss/revalidate), writes (invalidate/update), the clock (TTL), and memory pressure (evict).

---

## 4. The complete toolkit

### 4.1 Java local caches

#### 4.1.1 Caffeine (the modern default)

Caffeine is the de-facto high-performance Java local cache (successor to Guava cache). Near-optimal hit ratios (W-TinyLFU), async support, refresh-ahead.

| API / config | Purpose | Key params / defaults |
|---|---|---|
| `Caffeine.newBuilder()` | Builder entry point | — |
| `.maximumSize(n)` | Bound by entry count | no default bound (you must set a bound) |
| `.maximumWeight(w)` + `.weigher(...)` | Bound by computed weight (e.g., bytes) | — |
| `.expireAfterWrite(d)` | TTL since write | off by default |
| `.expireAfterAccess(d)` | TTI since last access | off |
| `.expireAfter(Expiry)` | Custom per-entry expiry | — |
| `.refreshAfterWrite(d)` | Refresh-ahead: async reload after d, serve old meanwhile | off; needs a `LoadingCache` |
| `.weakKeys()/.weakValues()/.softValues()` | GC-based eviction | off; values via GC references |
| `.recordStats()` | Enable hit/miss/load metrics | off |
| `.evictionListener(...)` / `.removalListener(...)` | Hooks on eviction/removal | — |
| `.buildAsync(...)` / `AsyncLoadingCache` | Non-blocking loads (CompletableFuture) | — |
| `LoadingCache.get(k)` | Read-through + single-flight dedup | loader required |
| `.getAll(keys)` | Bulk read-through | — |
| `cache.policy()` | Inspect/alter eviction at runtime | — |

```java
LoadingCache<Long, Product> cache = Caffeine.newBuilder()
    .maximumSize(100_000)                 // cap entries to bound heap
    .expireAfterWrite(Duration.ofMinutes(10))   // hard staleness bound (TTL)
    .refreshAfterWrite(Duration.ofMinutes(2))   // refresh-ahead: async reload, serve old
    .recordStats()                        // expose hit ratio for monitoring
    .build(id -> productRepository.load(id));    // read-through loader + single-flight
```
Note the interplay: `refreshAfterWrite(2m)` keeps hot entries fresh without blocking; `expireAfterWrite(10m)` is the *backstop* so a key not requested between refreshes can't exceed 10m staleness.

#### 4.1.2 Ehcache 3 / JSR-107 (JCache)

Ehcache is a mature cache with **off-heap** and **disk** tiers and a standard API.

| Feature | Purpose |
|---|---|
| `CacheManager` / `CacheConfigurationBuilder` | Configure caches |
| Tiered storage: **heap → off-heap → disk → clustered** | Multi-tier *within* one cache (own mini multi-layer) |
| `ExpiryPolicy` (TTL/TTI) | Expiration |
| `CacheLoaderWriter` | Read-through / write-through / write-behind |
| JSR-107 `javax.cache` annotations | Standard `@CacheResult`, `@CachePut`, `@CacheRemove` |
| Terracotta clustering | Distributed Ehcache |

Off-heap means storing serialized bytes *outside the JVM heap* (in native memory), so large caches don't cause garbage-collection pauses. Crucial for multi-GB local caches (see §7).

#### 4.1.3 Spring Cache abstraction

Spring decouples your code from the cache provider via annotations; you plug in Caffeine, Redis, Ehcache, etc.

| Annotation | Effect |
|---|---|
| `@Cacheable` | Read-through: check cache; on miss run method, cache result |
| `@CachePut` | Always run method, update cache (write-through-ish) |
| `@CacheEvict(key=..., allEntries=...)` | Invalidate one key or the whole cache |
| `@Caching` | Combine multiple cache ops |
| `@EnableCaching` + `CacheManager` bean | Wire it up |
| `sync = true` on `@Cacheable` | Single-flight (only one loader runs per key) |

```java
@Cacheable(cacheNames="products", key="#id", sync=true) // sync=true → dedup concurrent misses
public Product getProduct(long id) { return repo.findById(id); }

@CacheEvict(cacheNames="products", key="#p.id")          // invalidate on write (delete, not update)
public void update(Product p) { repo.save(p); }
```

### 4.2 Distributed caches

| System | Model | Notable for consistency |
|---|---|---|
| **Redis** | Single-logical-copy KV, rich types, Lua, Pub/Sub, client-side tracking | `SET NX PX` locks, Lua atomic CAS, keyspace notifications, RESP3 invalidation push |
| **Memcached** | Pure KV, multithreaded, no persistence | `cas` (check-and-set) op for optimistic updates; simple, fast, volatile |
| **Hazelcast / Apache Ignite** | In-memory data grid, near-cache, compute | Built-in **near cache with invalidation**, partitioned, MapStore (read/write-through) |
| **Redis Cluster** | Sharded Redis | Per-key consistency within a shard; cross-slot ops limited |

Redis client libraries (Java): **Lettuce** (async/reactive, Netty), **Jedis** (sync, simple), **Redisson** (high-level objects, distributed locks, near cache with invalidation).

Key Redis commands for caching:

| Command | Purpose |
|---|---|
| `GET/SET key val` | Basic read/write |
| `SET key val EX 30 NX` | Set with 30s TTL only if absent (lock / negative-cache write) |
| `SETEX`, `PSETEX` | Set with TTL (sec/ms) |
| `DEL key` / `UNLINK key` | Invalidate (UNLINK = async free) |
| `EXPIRE key 30` | Add/refresh TTL |
| `TTL key` | Remaining TTL (−1 no TTL, −2 absent) |
| `INCR/DECR` | Atomic counters (rate limiting, refcounts) |
| `MGET/MSET` | Bulk |
| `EVAL <lua>` | Atomic multi-step (CAS, get-or-set) |
| `MULTI/WATCH/EXEC` | Optimistic transaction |
| `PUBLISH/SUBSCRIBE` | Invalidation fan-out |
| `CLIENT TRACKING ON` | Server-assisted client-side cache invalidation |
| `CONFIG SET maxmemory-policy allkeys-lru` | Eviction policy |

### 4.3 HTTP / CDN / reverse-proxy toolkit

| Header / tool | Purpose |
|---|---|
| `Cache-Control` (`max-age`,`s-maxage`,`public/private`,`no-cache`,`no-store`,`must-revalidate`,`immutable`) | Freshness & cacheability |
| `Expires` | Legacy absolute expiry |
| `ETag` + `If-None-Match` | Strong/weak validator + conditional GET |
| `Last-Modified` + `If-Modified-Since` | Time validator |
| `Vary` | Cache-key dimensions by request header |
| `Surrogate-Control` | Directives to CDN only |
| `Surrogate-Key` / `Cache-Tag` | Group purge tags |
| `stale-while-revalidate`, `stale-if-error` | Stale-serving windows |
| **Varnish** VCL | Programmable reverse-proxy cache; `vcl_recv`/`vcl_backend_response`/`PURGE`; `ban`/`purge` |
| **Nginx** `proxy_cache` | `proxy_cache_path`, `proxy_cache_valid`, `proxy_cache_use_stale`, `proxy_cache_lock` (single-flight), `proxy_cache_key` |
| CDN purge APIs | Fastly `Surrogate-Key` purge; Cloudflare `Cache-Tag`/`purge_cache`; CloudFront invalidations; Akamai Fast Purge |

Nginx single-flight + stale-on-error example:
```nginx
proxy_cache_path /var/cache keys_zone=api:100m max_size=10g inactive=60m;
location /api/ {
  proxy_cache api;
  proxy_cache_key "$scheme$host$uri$is_args$args"; # explicit cache key
  proxy_cache_valid 200 30s;                       # cache 200s for 30s
  proxy_cache_lock on;                             # single-flight: one fill per key
  proxy_cache_use_stale error timeout updating http_500; # stale-if-error / swr
  add_header X-Cache-Status $upstream_cache_status; # HIT/MISS/EXPIRED for debugging
}
```

### 4.4 Supporting tools

- **Debezium / Kafka** — CDC-driven invalidation.
- **Bloom filter** (Guava `BloomFilter`, Redis `BF.*` via RedisBloom) — probabilistic "definitely-absent" set to short-circuit negative lookups and prevent penetration (§7).
- **Micrometer / Prometheus** — cache metrics (hit ratio, load time, evictions). Caffeine integrates via `CaffeineCacheMetrics`.

---

## 5. Code examples by use case

### 5.1 Two-tier (near) cache: Caffeine local + Redis distributed + pub/sub invalidation

The single most useful pattern for low-latency reads with cross-instance coherence.

```java
public class TwoTierCache {
    private final Cache<String, byte[]> local;          // L1: per-instance, nanoseconds
    private final StatefulRedisConnection<String,byte[]> redis; // L2: shared, ~1ms
    private final RedisPubSubAdapter<String,String> invalidator;
    private final Function<String, byte[]> dbLoader;
    private static final String INV_CHANNEL = "cache:inv";

    public TwoTierCache(RedisClient client, Function<String,byte[]> dbLoader) {
        this.dbLoader = dbLoader;
        this.local = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofSeconds(30)) // SHORT L1 TTL = backstop if an inv msg is missed
            .recordStats()
            .build();
        this.redis = client.connect(new ByteArrayCodec()); // separate connection for data
        // Subscribe to invalidations so every instance drops its local copy:
        StatefulRedisPubSubConnection<String,String> sub = client.connectPubSub();
        sub.addListener(new RedisPubSubAdapter<>() {
            @Override public void message(String ch, String key) { local.invalidate(key); }
        });
        sub.sync().subscribe(INV_CHANNEL);
    }

    public byte[] get(String key) {
        byte[] v = local.getIfPresent(key);            // L1 hit?
        if (v != null) return v;
        v = redis.sync().get(key);                     // L2 hit?
        if (v != null) { local.put(key, v); return v; }
        v = dbLoader.apply(key);                        // L3 (DB) — origin
        if (v != null) {
            redis.sync().setex(key, 300, v);           // L2 TTL 300s (longer than L1)
            local.put(key, v);
        }
        return v; // (omit negative caching here; see 5.4)
    }

    public void put(String key, byte[] value) {
        // 1) write through to DB happens in caller's transaction (not shown)
        redis.sync().del(key);                          // 2) invalidate shared copy (delete, not update)
        local.invalidate(key);                          // 3) drop our own local copy
        redis.sync().publish(INV_CHANNEL, key);         // 4) tell every other instance to drop theirs
    }
}
```
Why it's shaped this way: **L1 TTL (30s) is shorter than L2 TTL (300s)** so a missed pub/sub message can only cause ≤30s of local staleness; the pub/sub gives *fast* convergence in the common case; the central `DEL` keeps the shared copy honest; deletes (not updates) avoid the reordering race. (Production-grade version: use Redis RESP3 `CLIENT TRACKING` instead of hand-rolled pub/sub.)

### 5.2 Cache-aside with single-flight and stale-on-error (Caffeine `AsyncLoadingCache`)

Prevents stampedes and survives a flaky origin.

```java
AsyncLoadingCache<Long, Quote> quotes = Caffeine.newBuilder()
    .maximumSize(10_000)
    .refreshAfterWrite(Duration.ofSeconds(5))   // refresh hot quotes async every 5s
    .expireAfterWrite(Duration.ofSeconds(60))   // backstop staleness
    .buildAsync((id, executor) ->
        CompletableFuture.supplyAsync(() -> priceService.fetch(id), executor)
            .exceptionally(ex -> {
                // stale-if-error: on failure keep the previous value (Caffeine keeps old on refresh failure)
                meterRegistry.counter("quote.refresh.fail").increment();
                throw new CompletionException(ex);   // refresh failure keeps old entry, doesn't evict
            }));

CompletableFuture<Quote> q = quotes.get(42L); // concurrent callers for 42 share ONE fetch (single-flight)
```
Caffeine's `get` coalesces concurrent misses per key; on a *refresh* failure the **old value is retained** (graceful degradation) rather than the entry being removed.

### 5.3 Distributed lock to prevent cross-instance stampede on a cold hot key

When single-flight within one JVM isn't enough (many instances, one very hot key):

```java
public byte[] getWithDistLock(String key) {
    byte[] v = redis.sync().get(key);
    if (v != null) return v;
    String lockKey = "lock:" + key;
    String token = UUID.randomUUID().toString();
    // Acquire lock: SET lock NX PX 3000  → only one instance loads
    boolean got = "OK".equals(redis.sync().set(lockKey, token,
                       SetArgs.Builder.nx().px(3000)));
    if (got) {
        try {
            v = dbLoader.apply(key);                 // single loader across the fleet
            redis.sync().setex(key, 300, v);
            return v;
        } finally {
            // Release only if we still own it (atomic compare-and-del via Lua)
            redis.sync().eval(
              "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
              ScriptOutputType.INTEGER, new String[]{lockKey}, token);
        }
    } else {
        // Someone else is loading: brief backoff then read the populated value (or serve stale)
        sleepMillis(50);
        v = redis.sync().get(key);
        return v != null ? v : dbLoader.apply(key);  // fallback to avoid blocking forever
    }
}
```
The Lua release is **compare-and-delete** so you never delete a lock you no longer own (it might have expired and been re-acquired) — a classic distributed-lock correctness point. (For production, prefer Redisson's `RLock` or the Redlock-aware client; naive single-node locks aren't safe under failover — see §9.)

### 5.4 Negative caching with a Bloom filter front-stop

```java
// Bloom filter holds ALL known-existing IDs; "not in filter" ⇒ definitely absent (skip cache+DB).
BloomFilter<Long> existing = BloomFilter.create(Funnels.longFunnel(), 50_000_000, 0.01);
// populate at startup from DB and update on inserts

public Optional<User> getUser(long id) {
    if (!existing.mightContain(id)) return Optional.empty(); // penetration shield: never touches DB
    byte[] cached = redis.sync().get("u:" + id);
    if (cached != null) {
        if (cached.length == 0) return Optional.empty();     // negative sentinel = empty byte[]
        return Optional.of(deserialize(cached));
    }
    Optional<User> u = userRepo.findById(id);
    if (u.isPresent()) {
        redis.sync().setex("u:" + id, 300, serialize(u.get()));     // positive: 5 min
    } else {
        redis.sync().setex("u:" + id, 30, new byte[0]);             // NEGATIVE: short 30s TTL
    }
    return u;
}
```
Bloom filter gives "definitely absent vs maybe present" with ~1% false positives (those fall through to the negative cache, still cheap). Negative TTL is deliberately short so a later-created user isn't hidden for long.

### 5.5 Reliable invalidation via transactional outbox + CDC

Guarantees the cache invalidation isn't lost if the app crashes after commit.

```java
@Transactional
public void updateProductPrice(long id, BigDecimal price) {
    productRepo.updatePrice(id, price);                 // 1) the actual write
    outboxRepo.insert(new OutboxEvent(
        "CACHE_INVALIDATE", "product:" + id, Instant.now())); // 2) same transaction!
    // commit makes BOTH atomic — invalidation can never be lost
}
```
```java
// Separate poller/consumer (or Debezium tailing the outbox table) applies invalidations:
void drainOutbox() {
    for (OutboxEvent e : outboxRepo.fetchUnprocessed(100)) {
        redis.sync().del(extractKey(e));                // delete shared copy
        redis.sync().publish("cache:inv", extractKey(e)); // fan-out to local caches
        cdn.purgeByTag(extractKey(e));                  // purge CDN by surrogate key
        outboxRepo.markProcessed(e.id());               // at-least-once; deletes are idempotent
    }
}
```

### 5.6 HTTP API responses tuned for CDN + browser with surrogate keys

```java
@GetMapping("/products/{id}")
public ResponseEntity<ProductDto> getProduct(@PathVariable long id) {
    ProductDto p = service.get(id);
    String etag = "\"" + p.version() + "\"";
    return ResponseEntity.ok()
        .eTag(etag)                                     // enables 304 revalidation
        .header(HttpHeaders.CACHE_CONTROL,
            "public, max-age=30, s-maxage=300, stale-while-revalidate=60, stale-if-error=86400")
        .header("Surrogate-Key", "product-" + id + " catalog") // group purge tag
        .body(p);
}
```
When the product changes, your write path calls the CDN purge API for surrogate key `product-<id>`, dropping every cached page/response tagged with it across the edge in seconds.

### 5.7 Spring `@Cacheable` two-cache setup (local L1 + Redis L2) the easy way

```java
@Configuration @EnableCaching
class CacheConfig {
  @Bean CacheManager cacheManager(RedisConnectionFactory rcf) {
    var caffeine = new CaffeineCacheManager();
    caffeine.setCaffeine(Caffeine.newBuilder()
        .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)));
    var redis = RedisCacheManager.builder(rcf)
        .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))).build();
    return new CompositeCacheManager(caffeine, redis); // L1 then L2 fallthrough
  }
}
```
(For real near-cache coherence add a Redis keyspace-notification or pub/sub listener that evicts the Caffeine entries — `CompositeCacheManager` alone does not invalidate L1 across instances.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Measure hit ratio first.** Below ~80% hit ratio, a cache may add latency variance without much benefit. Target depends on workload; many production read caches run 95–99%+.
- **Right-size to the working set.** Make the cache big enough to hold the hot set; too small → thrashing (constant eviction of soon-needed entries).
- **Prefer local (L1) for the hottest keys** — nanoseconds vs the network ms of a distributed cache. A two-tier near cache often cuts p99 dramatically.
- **Batch** (`MGET`/`getAll`) to amortize network round-trips.
- **Avoid serialization tax:** JSON is slow/large; prefer compact binary (Protobuf, Kryo, MessagePack) for distributed-cache values; consider storing already-rendered payloads.
- **TTL jitter** to avoid synchronized expiry stampedes (§3.6).
- **Connection pooling** for Redis (Lettuce shares one connection efficiently; Jedis needs a pool).

### 6.2 Correctness / concurrency

- **Delete, don't update, on write** (§3.3.1).
- **Always set a TTL backstop**, even with active invalidation — it's your safety net for missed purges/messages.
- **Make invalidation reliable** for important data (outbox/CDC), idempotent everywhere.
- **TTLs decrease toward the user** so no near layer pins stale data (§3.2).
- **Read-your-writes:** for the writing user, either (a) read from the source of truth for a short window after their write, (b) include their just-written value via a write-then-read session pin, or (c) cache-bust their next request (e.g., append a version to the URL/key). Per-instance local caches are the worst offenders — sticky sessions help but don't fully solve it; pub/sub invalidation does.
- **Monotonic reads:** pin a user's reads to a consistent layer/version when going-backwards is unacceptable.
- **Beware caching mutable objects by reference** in local caches — return immutable copies or you'll have spooky action when callers mutate the cached object.

### 6.3 Memory

- **Bound every cache** (size or weight). An unbounded cache is a memory leak and an OOM waiting to happen.
- **GC pressure (JVM):** large on-heap caches lengthen GC pauses. For multi-GB caches use **off-heap** (Ehcache off-heap, or store in Redis). Watch for **humongous allocations** in G1 (objects ≥ half a region) from big cached blobs.
- **Avoid memory fragmentation in Redis** (jemalloc); monitor `mem_fragmentation_ratio`.
- **Value size:** very large values hurt (network, eviction granularity). Compress or split.

### 6.4 Security

- **Never cache per-user data in a *shared* cache without isolating by user** (cache key must include the user/tenant; mark HTTP responses `private`). The classic disaster: a CDN caches a logged-in user's page (because of a stray cookie or wrong `Vary`) and serves *their* data to other users. Audit cache keys and `Cache-Control: private` for anything authenticated.
- **Don't cache secrets/tokens** in shared layers; if you must, encrypt and TTL tightly.
- **Cache penetration / poisoning:** validate inputs; negative-cache + Bloom filter to stop penetration; for **cache poisoning** (an attacker tricks the cache into storing a malicious response keyed under a normal URL, often via unkeyed headers), normalize and *key on* all request inputs that affect the response, and don't reflect unkeyed input into cached responses.
- **TLS** for distributed cache traffic in untrusted networks; auth (`requirepass`/ACLs) on Redis.

### 6.5 Observability

Instrument and alert on:
- **Hit ratio** per cache/tier (the headline metric). A sudden drop = a key-space change, a bad deploy, or a purge storm.
- **Load/miss latency** and **load failure rate**.
- **Eviction rate** and **size/used memory** (rising evictions = undersized).
- **Expiration count**, **stale-serve count** (swr), **revalidation 304 ratio**.
- **Invalidation lag** (time from write to all layers reflecting it) — measure it explicitly.
- **CDN:** `X-Cache: HIT/MISS`, `Age` header, hit ratio by PoP, origin offload %.
- **Per-key hotness** to find hot keys (Redis `--hotkeys`, `MONITOR` sparingly).
Tools: Micrometer + Prometheus + Grafana; Caffeine `recordStats()`; Redis `INFO stats`, `SLOWLOG`, `LATENCY`.

### 6.6 Cost

- A cache cluster (Redis) has real cost; size it to the working set, not the whole DB.
- CDN cost is dominated by **origin egress** and requests — high hit ratio *saves* money (less origin traffic). Track **origin offload %**.
- Over-aggressive purging (purge-all) triggers expensive origin stampedes — prefer surrogate-key/soft purge.

### 6.7 Testing

- **Unit-test cache logic** with a fake clock to exercise TTL/expiry deterministically (Caffeine `ticker(...)`).
- **Test the invalidation path** explicitly: write → assert the right keys/tags purged, with retries.
- **Concurrency tests** for stampede/single-flight and the stale-set race (jcstress for low-level, or controlled interleavings).
- **Chaos:** kill the distributed cache and verify the app degrades to origin (not falls over); kill origin and verify `stale-if-error` keeps serving.
- **Contract-test cache headers** for HTTP endpoints (assert `Cache-Control`, `Vary`, `ETag`).

### 6.8 Production hardening

- Treat the cache as **best-effort, not the source of truth** (except deliberately, with durability, in write-behind).
- **Graceful degradation:** if the cache is down, serve from origin (with concurrency limits/circuit breakers so you don't melt the DB) — never return errors just because the cache is down.
- **Warm-up / pre-fill** critical keys after a deploy or cache flush to avoid a cold-start stampede.
- **Circuit breaker** around origin to survive a stampede.
- **Rolling restarts** of distributed cache will cold-start it — fill gradually, expect a temporary hit-ratio dip.

### 6.9 Anti-patterns (avoid)

- Unbounded caches (OOM).
- Updating instead of deleting on write (reorder race).
- No TTL backstop with active invalidation (one missed purge = permanent staleness).
- Caching per-user data in shared layers without isolation (data leak).
- `Vary: User-Agent` / per-request random cache-busting params (kills hit ratio).
- Treating Redis as durable storage with `maxmemory-policy noeviction` then OOM-erroring; or as a cache with `allkeys-lru` but relying on it for durability.
- Caching highly volatile data with long TTLs "to be fast" (users see wrong data).
- One global TTL for everything (different data has different staleness tolerance).
- Synchronous CDN purge-all on every write (origin stampede).
- Ignoring the dual-write problem (lost invalidations).

---

## 7. Advanced topics & deep internals

### 7.1 Probabilistic early recomputation (XFetch) — the math

To spread refreshes and beat stampedes, recompute a value *before* expiry with a probability that grows as expiry nears. The XFetch rule recomputes when:
```
now − delta * beta * ln(rand()) ≥ expiry
```
where `delta` is the measured recomputation time, `beta ≥ 1` tunes eagerness, and `rand()` is uniform in (0,1]. Expensive-to-recompute values get refreshed earlier; cheap ones nearer expiry. This converts a synchronized cliff at TTL into a smooth, self-throttling refresh.

### 7.2 Hot-key and big-key problems in Redis

- **Hot key:** one key gets a disproportionate share of traffic, saturating the single shard/CPU that owns it. Mitigations: replicate the value across N suffixed keys (`key#0..key#N`) and pick randomly; add an L1 local cache in front (best); use read replicas.
- **Big key:** a single large value/collection (e.g., a multi-MB hash) blocks the single-threaded server during access/expiry. Split into smaller keys; use `UNLINK` (async free) not `DEL`; avoid `KEYS *`/`HGETALL` on huge structures.

### 7.3 False sharing (CPU layer, but bites JVM caches)

Two unrelated variables on the *same 64-byte cache line* updated by different cores cause the line to ping-pong between cores' caches (MESI invalidations), silently killing throughput. Java fix: `@Contended` (with `-XX:-RestrictContended`) or manual padding to push hot fields onto separate lines. Relevant when your "cache" is a high-frequency in-memory counter/structure.

### 7.4 Near-cache coherence done right: Redis server-assisted client-side caching (RESP3)

Instead of hand-rolling pub/sub, enable `CLIENT TRACKING`: the client keeps a local copy; Redis tracks which keys the client read and **pushes** an invalidation message the instant those keys change/expire, including from other clients. **BCAST mode** notifies by key *prefix* (cheaper server memory, more invalidations). Lettuce and Redisson expose this as a maintained near cache. This is the closest production analog to hardware MESI: the server is the directory, clients are cores, invalidations are coherence messages. Caveat: there's still a tiny propagation window, and the push is best-effort (use a short L1 TTL backstop).

### 7.5 Consistency of the invalidation itself across regions

In a multi-region deploy, a write in region A must invalidate caches in regions B and C. Cross-region pub/sub/CDC adds tens to hundreds of ms of propagation; during it, B/C serve stale. Options: (a) accept bounded staleness sized to replication lag; (b) route a user's reads to the region where they wrote (sticky region) for read-your-writes; (c) for strongly-consistent needs, read through to the primary region or a globally-consistent store (e.g., Spanner) and skip caching that path. There is no free lunch — cross-region adds the WAN to your invalidation delay.

### 7.6 Caching at the edge with compute (edge functions)

Cloudflare Workers / Fastly Compute / Lambda@Edge let you run logic at the PoP: custom cache keys, per-request personalization with shared base caching (**ESI** — Edge Side Includes — assembles a page from cacheable fragments + per-user holes), and programmatic purges. ESI lets you cache the 95% static shell and only fetch the 5% dynamic bits, hugely raising effective hit ratio for personalized pages.

### 7.7 Write-behind durability internals

Production write-behind (e.g., Ehcache, Ignite) uses a **write-behind queue** with: coalescing (dedup same-key updates), batching (group writes), configurable delay and batch size, and **persistence/replication of the queue** so a crash doesn't lose un-flushed writes. Tuning knobs: `maxWriteDelay`, `writeBatchSize`, `writeBehindConcurrency`, queue capacity (backpressure when full). Always pair with a durable WAL/replica if the data matters.

### 7.8 Subtleties of `no-cache` vs `no-store` vs `private` vs `max-age=0`

- `no-store` = never persist (true opt-out).
- `no-cache` = may persist but must revalidate every time (good for "always fresh but use 304s").
- `max-age=0` ≈ "immediately stale, must revalidate" (similar effect to `no-cache` in practice but technically a freshness=0, not a forced revalidation flag).
- `private` = browsers only, never shared caches (per-user data).
- `must-revalidate` = once stale, *must not* serve stale (overrides `stale-if-error`). Use for data where stale is unacceptable.
These distinctions cause real bugs; memorize them.

### 7.9 Coherence vs consistency terminology (pedantic but interview-relevant)

In hardware/distributed-systems language, **coherence** = *all copies of a single datum agree (and writes to it are seen in a consistent order)*; **consistency** = *the ordering/visibility rules across multiple data items*. Multi-layer caches usually deliver per-key coherence (eventually) but *not* cross-key consistency — two related keys can reflect different points in time. If two cached values must be mutually consistent (e.g., a balance and a transaction list), cache them together (one composite entry) or version them jointly.

### 7.10 Lesser-known behaviors

- **Caffeine eviction is amortized/asynchronous:** `maximumSize` is a target, not a hard cap; size can briefly exceed it because eviction runs on access/maintenance cycles. Don't assert exact sizes in tests.
- **Redis lazy + active expiration:** expired keys aren't all removed at TTL; Redis removes them on access (lazy) and via a background sampler (active). So memory may hold expired keys briefly; a `GET` on an expired key returns nil and triggers its removal. `scan` may still report them transiently.
- **`Age` header** from a CDN tells you how long the object has been cached — use it to detect over-long edge staleness.
- **CDN `Vary` + compression interplay:** ensure `Vary: Accept-Encoding` or you may serve gzipped bytes to a client that didn't ask, or fragment the cache.

---

## 8. Tradeoffs & decision frameworks

### 8.1 The master tradeoff: consistency vs latency vs cost

| Lever | More consistency | More latency-win / less cost |
|---|---|---|
| TTL | Short TTL (fresh, more origin load) | Long TTL (stale risk, big offload) |
| Invalidation | Active purge + CDC (complex) | TTL-only (simple, staler) |
| Layers | Fewer layers (easier coherence) | More layers (faster, harder coherence) |
| Pattern | Write-through (sync) | Write-behind (async, risk) |
| Stale serving | `must-revalidate` (no stale) | `stale-while-revalidate`/`stale-if-error` (serve stale) |

**Rule of thumb:** start with **cache-aside + delete-on-write + a TTL sized to your staleness tolerance + stale-while-revalidate**. Add active invalidation (pub/sub, CDC, surrogate-key purge) only where the TTL alone is too stale. Add a local L1 tier only where the distributed-cache latency is the bottleneck, and pair it with invalidation + short L1 TTL.

### 8.2 Choosing a write strategy

| Use when… | Strategy |
|---|---|
| Read-heavy, can tolerate seconds of staleness | Cache-aside + delete-on-write + TTL |
| Reads must reflect writes immediately for that key | Write-through |
| Write-heavy/bursty, can risk small loss (or have durable queue) | Write-behind |
| Hot keys, want never-block freshness | Refresh-ahead |
| Must never lose an invalidation | Outbox/CDC-driven |

### 8.3 Local vs distributed vs near (two-tier)

| | Local only | Distributed only | Near (two-tier) |
|---|---|---|---|
| Latency | Best (ns) | ms (network) | Best for hot, ms for warm |
| Cross-instance coherence | **Hard** (N copies) | Easy (one copy) | Hard for L1 (needs invalidation) |
| Capacity | Heap-bound | Large | Large (L2) + small L1 |
| Survives restart | No | Yes | L2 yes |
| Complexity | Low | Medium | High |
| Use when | Small hot set, staleness OK | Shared truth, ms OK | Need ns *and* shared truth |

### 8.4 Redis vs Memcached vs in-memory grid

| | Redis | Memcached | Hazelcast/Ignite |
|---|---|---|---|
| Data types | Rich (lists, sets, hashes, streams) | KV only | Objects, queries, compute |
| Persistence | Optional (RDB/AOF) | None | Optional |
| Threading | Single (cmd) + I/O threads | Multi-threaded | JVM, multi |
| Client-side invalidation | Yes (RESP3 tracking) | No (manual) | Yes (near cache) |
| Best for | Most caches + primitives | Pure simple KV at scale | JVM data grids, compute-near-data |

### 8.5 When to skip caching entirely

- Strong correctness at the moment of decision (final inventory decrement, money movement) → read/write the source of truth, optionally with its own internal cache and locks.
- Low reuse / huge cardinality → no hit ratio payoff.
- Data changes nearly every read → invalidation churn dominates.

---

## 9. Failure modes & debugging

### 9.1 Stampede / thundering herd

**Symptom:** periodic origin CPU/latency spikes aligned with TTL boundaries; many identical concurrent queries. **Diagnose:** correlate DB query spikes with cache `expiration`/`miss` metrics; look for synchronized expiry (same TTL on many keys). **Fix:** single-flight (`sync=true`/`LoadingCache`/`proxy_cache_lock`), TTL jitter, stale-while-revalidate, distributed lock for cross-instance.

### 9.2 Stale data / read-after-write failures

**Symptom:** users report "my change didn't save" or seeing old values; intermittent. **Diagnose:** check whether the write path actually invalidated all layers (add an `X-Cache`/`Age` header trace; log invalidation events); look for per-instance local caches (the usual culprit) and missed pub/sub. **Fix:** delete-on-write, pub/sub fan-out to L1, short L1 TTL backstop, read-from-source after own write, CDN surrogate-key purge.

### 9.3 Lost invalidation (dual-write crash)

**Symptom:** a specific record is stale "forever" (until TTL) after a deploy/crash. **Diagnose:** trace the write → did the cache delete run? was there a crash between commit and delete? **Fix:** transactional outbox or CDC; idempotent retried deletes; TTL backstop bounds the blast radius.

### 9.4 Cache penetration (missing keys hammering origin)

**Symptom:** high miss rate, origin load from lookups of non-existent IDs (often malicious or a buggy crawler). **Diagnose:** miss metrics by key pattern; logs of 404s with high cardinality. **Fix:** negative caching (short TTL) + Bloom filter front-stop.

### 9.5 Cache avalanche (mass simultaneous expiry / cache down)

**Symptom:** huge origin spike when many keys expire together or the cache cluster restarts/fails. **Diagnose:** correlated mass-expiry or a cache outage event. **Fix:** TTL jitter, layered fallback (L1 survives L2 outage), circuit breaker + concurrency limit to origin, gradual warm-up, multi-replica cache.

### 9.6 Serving wrong variant (cache-key bug)

**Symptom:** users get content for the wrong language/currency/device; or a logged-in user sees another user's page (security incident). **Diagnose:** inspect the CDN cache key config and `Vary`; reproduce with differing headers/params; check for `Cache-Control: public` on authenticated responses. **Fix:** include the differentiating input in the cache key or `Vary`; mark per-user responses `private`; never let an unkeyed cookie/header change the body.

### 9.7 Cache poisoning

**Symptom:** all users receive a malicious/broken response under a normal URL. **Diagnose:** a response cached based on an *unkeyed* attacker-controlled header (e.g., `X-Forwarded-Host`) that influenced the body/links. **Fix:** don't reflect unkeyed inputs; normalize and key on everything that affects the response; restrict which headers reach origin.

### 9.8 Distributed-lock failover bug (Redlock caveat)

**Symptom:** under Redis primary failover, two instances both "hold" the lock and both write (split brain). **Diagnose:** failover event coinciding with duplicate origin loads/writes. **Note:** single-node `SET NX PX` is not safe across failover; even Redlock is debated for correctness under GC pauses/clock skew. **Fix:** use locks only as a *best-effort stampede reducer* (not for correctness), or use a fencing-token approach / a real consensus store (e.g., ZooKeeper/etcd) when correctness depends on it.

> **ZooKeeper / etcd:** strongly-consistent coordination stores using consensus protocols (**ZAB** / **Raft**) to provide reliable distributed locks, leader election, and config with linearizable guarantees — slower than Redis but correct under partitions/failover. **Raft:** a consensus algorithm where a leader replicates a log to followers and a majority must acknowledge each entry, guaranteeing a single agreed order of operations even with failures.

### 9.9 Memory/GC blowups

**Symptom:** OOM or long GC pauses tied to cache growth. **Diagnose:** heap dump shows the cache map dominating; rising eviction=0 with rising size (unbounded). **Fix:** bound the cache, off-heap/Redis for big sets, weigher by bytes, compress values.

### 9.10 Real-world incident patterns (illustrative)

- **The "public on a logged-in page" leak:** an authenticated response missing `Cache-Control: private` got cached by a CDN/proxy and served to other users — a recurring, high-severity class of incident across many companies. *Lesson:* default authenticated responses to `private, no-store` and opt-in to caching deliberately.
- **The "TTL cliff" outage:** a popular site set the same long TTL on millions of keys cached at deploy time; they all expired within the same minute, the resulting origin stampede took the DB down. *Lesson:* jitter TTLs; single-flight; warm-up.
- **The "purge-all" self-DDoS:** an over-eager invalidation purged the entire CDN on every content edit, repeatedly stampeding origin. *Lesson:* surrogate-key/soft purge, never purge-all on routine writes.

(These are representative patterns drawn from common postmortems; I'm describing the shape rather than attributing specific named incidents I can't verify.)

---

## 10. Interview drill

**Q1. Why delete the cache entry on write instead of updating it?**
*Model answer:* Two concurrent writers updating the cache can land their cache writes in the opposite order of their DB writes, leaving the cache permanently holding the older value (until TTL). Deletion is idempotent and order-insensitive; the next reader reloads the current DB value. So we delete-on-write and lazily repopulate on read.
- *Follow-up: Doesn't delete-then-read create its own race?* Yes — the stale-set race: a slow reader that read the old DB value before the write can set it into the cache after the delete. Bound it with a TTL backstop; eliminate it with versioned CAS-on-set or CDC-ordered invalidation.
- *Follow-up: What if the delete fails?* The cache is stale until TTL. Make invalidation reliable (transactional outbox/CDC + retries) and always keep a TTL backstop.
- *Follow-up: Update DB then delete, or delete then update DB?* Update DB *then* delete: a read in between still gets the new value reloaded. Delete *then* update DB is worse — a read in the gap reloads the *old* value and pins it.

**Q2. Walk me through read-after-write consistency with a multi-instance app using local caches.**
*Model answer:* Each instance has its own local cache, so invalidating one doesn't touch the others. After a user writes, their next request may hit a different instance whose local copy is stale → they see their change vanish. Fixes: broadcast invalidation via pub/sub so every instance evicts; keep L1 TTL short as a backstop; for the writing user, read from the source of truth (or the distributed cache after a central delete) for a short window; or sticky-session/sticky-region the user.
- *Follow-up: Why not just update the local cache directly?* You can only update the local cache of the instance handling the write; the others are still stale — you need the broadcast.
- *Follow-up: Is pub/sub reliable?* Redis Pub/Sub is fire-and-forget; a down subscriber misses messages. Use a short L1 TTL backstop, or Kafka/CDC for durable delivery, or Redis RESP3 client-side tracking.

**Q3. Design end-to-end caching for a product page served globally.** *(senior-signal)*
*Model answer:* Layers: browser (`max-age=30`), CDN (`s-maxage=300`, `stale-while-revalidate=60`, `stale-if-error=86400`, `Surrogate-Key: product-<id>`), reverse proxy optional, app local L1 (Caffeine, 30s TTL, refresh-ahead for hot SKUs), Redis L2 (TTL 5m). DB is source of truth. Writes: update DB in a transaction with an outbox row; a CDC/outbox consumer deletes the Redis key, publishes a pub/sub invalidation for L1, and calls the CDN purge API by surrogate key `product-<id>`. TTLs decrease toward the user; everything has a TTL backstop. Personalized bits (cart, recommendations) are pulled client-side or via ESI so the page shell stays cacheable. Negative-cache 404s. Justify each TTL by the data's staleness tolerance and the cost of origin load.
- *Follow-up: Where does staleness come from and how bad?* Sum of per-layer lags, bounded by purge propagation (seconds at CDN) + pub/sub (ms) + TTL backstops. Worst-case ≈ the longest TTL on the path if a purge is missed.
- *Follow-up: How do you keep price and availability mutually consistent?* Cache them together as one composite entry (joint versioning) so you never show a new price with stale availability.

**Q4. Explain stale-while-revalidate and stale-if-error and when you'd use each.**
*Model answer:* `stale-while-revalidate=N` lets a cache serve the stale copy for N seconds after expiry while it refreshes in the background — no reader blocks, origin sees one refresh, eliminating TTL-boundary latency spikes. `stale-if-error=N` serves stale for N seconds when the origin errors/is down — an availability boost (AP lean). Use SWR everywhere staleness for a few seconds is fine; use SIE generously (even hours/days) since stale-on-outage beats an error.
- *Follow-up: When must you NOT serve stale?* When correctness forbids it — add `must-revalidate` (and don't set SIE), e.g., for authorization decisions.

**Q5. How do you prevent a cache stampede on a hot key?** *(covered, but they'll ask for depth)*
*Model answer:* Single-flight/request coalescing (one loader per key), distributed lock for cross-instance, probabilistic early expiration (XFetch), stale-while-revalidate, TTL jitter, and an L1 near cache to absorb the hot key locally.
- *Follow-up: Single-flight within a JVM vs across the fleet?* Caffeine `LoadingCache` coalesces within one JVM only; across instances you need a distributed lock or to rely on the L2 cache being populated by whichever instance got there first (others read L2).

**Q6. What's the dual-write problem and how do you solve it for cache invalidation?** *(senior-signal)*
*Model answer:* Updating the DB and the cache as two separate operations isn't atomic; a crash between them leaves the cache stale. Solve with a transactional outbox (insert the invalidation event in the same DB transaction; a separate consumer applies it with retries) or CDC reading the binlog (ordered, never-missed events). Both make invalidation reliable and correctly ordered; keep a TTL backstop regardless.
- *Follow-up: Why is CDC ordering better than app-issued invalidation?* CDC events come from the commit log in commit order *after* commit, so they can't be reordered or precede the write — eliminating the stale-set race.

**Q7. CDN cache keys and surrogate keys — what are they and how do they prevent bugs?**
*Model answer:* The cache key is the identity under which a response is stored (host+path+query by default; customizable). Too broad → low hit ratio; too narrow → wrong variant served (e.g., ignoring `?currency`). Surrogate keys/cache tags label objects so you can purge groups by tag (e.g., purge `product-123` to invalidate every page showing that product) without enumerating URLs.
- *Follow-up: How would caching a per-user page leak data?* If an authenticated response is `public` and the cache key omits the user, the CDN serves one user's cached page to others. Fix: `private`/`no-store` for authenticated responses; include user in key only if you must cache per-user.

**Q8. Compare write-through, write-behind, and cache-aside on consistency, latency, and durability.**
*Model answer:* Write-through: cache writes synchronously to DB → strong per-key consistency, slower writes, no loss. Write-behind: async DB write → fastest writes, DB lags (weak), risk of loss on crash unless the queue is durable. Cache-aside: app writes DB then invalidates → simple, eventual consistency with race windows bounded by TTL. Choose by write latency needs and tolerance for loss/staleness.
- *Follow-up: How do you make write-behind safe?* Durable, replicated write-behind queue (WAL), coalescing+batching, backpressure, and accept that DB is eventually consistent.

**Q9. Your hit ratio dropped from 98% to 70% after a deploy. How do you debug?** *(senior-signal)*
*Model answer:* Check what changed in the cache key (added a query param, a cookie, a `Vary` header → cardinality explosion), TTL changes (now too short), a key-format change (old entries orphaned), a cache flush/cold start, or a working-set shift. Look at miss-rate by key pattern, recent config/header diffs, eviction rate (undersized), and `X-Cache`/`Age` headers. Roll back the suspect change; warm up.
- *Follow-up: How would a `Vary: User-Agent` cause this?* It makes the cache key vary per browser string → millions of variants → near-zero reuse.
- *Follow-up: What metric would have alerted you sooner?* Hit ratio per tier with anomaly alerting, plus origin offload %.

**Q10. How do CPU cache coherence (MESI) and distributed cache invalidation relate?**
*Model answer:* Both keep multiple copies of one datum coherent: on a write, invalidate other copies so they refetch. MESI does it in hardware with states (Modified/Exclusive/Shared/Invalid) and bus messages; distributed caches do a slower, lossy version over a network (pub/sub, CDC, or Redis client-side tracking acting as a directory). The principles — single-writer ordering, invalidation messages, a directory of who holds copies — are identical; the network just adds latency and the possibility of lost messages, hence TTL backstops.
- *Follow-up: What's the software analog of the MESI "directory"?* Redis RESP3 `CLIENT TRACKING` (the server tracks which keys each client cached and pushes invalidations) — a software directory protocol.

**Q11. Negative caching — what, why, and the risk?**
*Model answer:* Cache the *absence* of a value (e.g., 404/empty) with a short TTL so repeated lookups of non-existent keys don't hammer the origin (cache penetration). Risk: if the item is later created, you may serve "not found" until the negative entry expires — so keep negative TTLs short and/or invalidate on creation. Pair with a Bloom filter to reject definitely-absent keys before touching the cache/DB.

**Q12. How do you bound and observe staleness across all layers?** *(senior-signal)*
*Model answer:* Give each layer a TTL backstop sized to its staleness tolerance, decreasing toward the user; add active invalidation (pub/sub for L1, DEL for L2, surrogate-key purge for CDN) for fast convergence. Observe: emit an invalidation timestamp/version with the data and measure "time until every layer reflects version N" (invalidation lag); monitor CDN `Age`, hit ratios, stale-serve counts, and purge propagation. The user-visible worst case is the longest TTL on the path (if a purge is missed) plus purge/pub-sub propagation delay.

---

## 11. Glossary

- **AP / CP (CAP):** During a network partition, a system chooses Availability (answer, maybe stale) or Consistency (latest or error). Caches lean AP.
- **Avalanche (cache):** Mass simultaneous expiry or cache outage causing an origin overload.
- **Backing store / source of truth / system of record:** The authoritative, durable copy of the data (usually the DB).
- **Bloom filter:** Probabilistic set answering "definitely absent" or "maybe present"; used to short-circuit lookups of non-existent keys.
- **Cache-aside (look-aside):** App checks cache, loads from DB on miss, populates cache; invalidates on write.
- **Cache coherence:** All copies of a single datum agree (and writes are ordered consistently).
- **Cache key:** The identity under which a response/value is stored.
- **Cache line:** CPU transfer unit, typically 64 bytes.
- **Cache penetration:** Repeated lookups of non-existent keys bypassing the cache and hitting origin.
- **Cache poisoning:** Attacker tricks a cache into storing a malicious response under a normal key.
- **CAS (compare-and-swap / check-and-set):** Update only if the current value/version matches expectation; basis of optimistic concurrency.
- **CDC (Change Data Capture):** Streaming row changes from a DB's commit log (e.g., Debezium) in commit order.
- **CDN (Content Delivery Network):** Geographically distributed caches (PoPs) serving content near users.
- **Coalescing (request / write):** Merging concurrent identical loads (single-flight) or repeated writes to one operation.
- **Conditional request / revalidation:** Asking the origin "is my copy still valid?" via `ETag`/`Last-Modified`; gets 304 (reuse) or 200 (replace).
- **Dogpile / stampede / thundering herd:** Many concurrent misses for the same key hitting origin at once.
- **Dual-write problem:** Updating two stores (DB + cache) without atomicity; a crash between them leaves them inconsistent.
- **Edge:** Closest point to the user (CDN PoP or browser).
- **ESI (Edge Side Includes):** Assembling a page at the edge from cacheable fragments plus per-user holes.
- **ETag:** Opaque version token for a resource enabling revalidation.
- **Eventual consistency:** Copies converge to the latest value if writes stop.
- **Eviction:** Removing entries to free space (LRU/LFU/FIFO/W-TinyLFU).
- **Expiration:** Removing/invalidating entries because they're too old (TTL/TTI).
- **False sharing:** Two unrelated variables on one cache line causing coherence ping-pong between cores.
- **Fencing token:** A monotonically increasing token proving lock ownership order, used to make distributed locks safe.
- **Freshness / staleness:** Whether a copy is within its lifetime (fresh) or past it (stale).
- **Hit / miss / hit ratio:** Found in cache / not found / fraction of reads that hit.
- **Hot key / big key:** A disproportionately accessed key / an oversized value straining a (single-threaded) cache server.
- **Invalidation:** Removing or marking stale a cached copy when truth changes.
- **JCache (JSR-107):** Java standard caching API (`javax.cache`).
- **Local / distributed / near cache:** In-process / shared remote / small local in front of remote.
- **LRU / LFU / FIFO / W-TinyLFU:** Eviction policies (recency / frequency / insertion order / frequency-sketch+recency).
- **MESI:** Hardware cache-coherence protocol (Modified/Exclusive/Shared/Invalid).
- **Monotonic reads:** A client never sees data move backward in time.
- **MVCC (Multi-Version Concurrency Control):** DB technique keeping multiple versions so readers don't block writers; relevant when reasoning about what "the latest" means.
- **Negative caching:** Caching the absence of a value with a short TTL.
- **Off-heap:** Storing data in native memory outside the JVM heap to avoid GC pressure.
- **Origin:** The source the cache fetches from on a miss (often the app/DB behind a CDN).
- **Outbox (transactional):** An events table written in the same transaction as the data, drained by a consumer for reliable side effects.
- **PACELC:** If Partitioned choose A/C, Else choose Latency/Consistency.
- **PoP (Point of Presence):** A CDN's physical location near users.
- **Pub/Sub:** Publish–subscribe messaging decoupling senders from receivers.
- **Purge (hard/soft) / ban:** CDN/proxy invalidation; soft marks stale, hard deletes; ban invalidates by pattern.
- **Raft / ZAB:** Consensus algorithms (etcd uses Raft; ZooKeeper uses ZAB) for linearizable coordination.
- **Read-after-write / read-your-writes:** A client reads back its own writes.
- **Read-through / write-through / write-behind / refresh-ahead:** Cache patterns (lib loads on miss / sync DB write / async DB write / proactive async reload).
- **Redlock:** A multi-node Redis distributed-lock algorithm (correctness debated under failover/GC).
- **Refresh-ahead:** Proactively reload near-expiry hot entries, serving the old value until the reload completes.
- **RESP3 client-side caching / `CLIENT TRACKING`:** Redis server-assisted near-cache invalidation.
- **Single-flight:** Only one concurrent loader per key; others share the result.
- **stale-if-error / stale-while-revalidate:** Serve stale on origin error / serve stale while refreshing in background.
- **Surrogate key / cache tag:** A label on cached objects enabling group purges.
- **`Surrogate-Control`:** Cache directives addressed only to the CDN/proxy.
- **Temporal / spatial locality:** Recently accessed (or nearby) data is likely accessed again soon.
- **Thundering herd:** See dogpile/stampede.
- **TTL / TTI:** Time To Live (since write) / Time To Idle (since last access).
- **`Vary`:** Tells caches which request headers affect the response (part of the cache key).
- **WAL (Write-Ahead Log):** Durable log of changes written before applying them; provides crash recovery.
- **Working set:** The set of items actually accessed within a time window.
- **XFetch:** Probabilistic early recomputation to spread refreshes and avoid stampedes.
- **ZooKeeper / etcd:** Strongly-consistent coordination services (locks, leader election, config).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Latencies (orders of magnitude):** L1 ~1 ns · RAM ~100 ns · local cache 50–500 ns · Redis same-AZ ~0.2–2 ms · DB query 1–10 ms · CDN→user 1–50 ms. Cache line = 64 B. Network read ≈ 10,000–100,000× slower than local cache.

**The golden rules:**
- On write: **delete (invalidate), don't update**. Update DB **then** delete cache.
- **Always set a TTL backstop**, even with active invalidation.
- **TTLs decrease toward the user.** No near layer should pin stale far-layer data.
- Cache is **best-effort, not source of truth** (unless durable write-behind on purpose).
- **Make invalidation reliable** (outbox/CDC) and **idempotent** for important data.
- **Mark authenticated responses `private`/`no-store`** before they touch a shared cache.

**Stampede toolkit:** single-flight · distributed lock · XFetch (probabilistic early refresh) · stale-while-revalidate · TTL jitter · L1 near cache.

**Consistency strategies & windows:** update-DB-then-delete (stale-set race, TTL-bounded) · delete-then-update-DB (worse: reloads old) · update-cache (reorder race) · write-through (strong per-key) · CDC (ordered, ms–s lag).

**HTTP knobs:** `max-age` (everyone) · `s-maxage` (shared only) · `private` (browser only) · `no-cache` (revalidate) · `no-store` (never) · `immutable` · `ETag`/`If-None-Match` → 304 · `stale-while-revalidate` · `stale-if-error` · `Vary` · `Surrogate-Key`/`Cache-Tag`.

**Decision quickrules:** Default = cache-aside + delete-on-write + TTL(tolerance) + SWR. Add active invalidation where TTL is too stale. Add L1 near cache where distributed latency hurts (with invalidation + short L1 TTL). Skip caching for at-decision correctness (money/inventory). Negative-cache 404s (short TTL) + Bloom filter for penetration. Surrogate-key purge, never purge-all on routine writes.

**Failure → fix:** stampede→single-flight/jitter/SWR · stale-after-write→pub/sub L1 invalidation+short TTL · lost invalidation→outbox/CDC · penetration→negative cache+Bloom · avalanche→jitter+fallback+breaker · wrong variant→fix cache key/`Vary` · data leak→`private` · lock split-brain→fencing/consensus store.

**MESI ⇄ distributed:** write → invalidate other copies → they refetch. Redis `CLIENT TRACKING` = software directory.

### 12.2 Self-test (no answers — recall actively)

1. A user updates their profile and immediately reloads, seeing the old value. Name three distinct root causes across the layers and the fix for each.
2. Why is "update DB, then update cache" unsafe under concurrency, and what's the minimal change that makes the common case correct?
3. Design the TTLs and invalidation channels for browser/CDN/L1/L2/DB for a frequently-edited news article that must look fresh within ~10 seconds globally. Justify each number.
4. You enable a local near cache and your read-your-writes bug count goes *up*. Explain the mechanism and two fixes that don't remove the near cache.
5. Explain `stale-while-revalidate` and `stale-if-error`, give a single `Cache-Control` line using both plus an `ETag`, and state one situation where you must NOT use them.
6. Your CDN hit ratio collapses after adding personalization. List the three most likely cache-key/`Vary` causes and how you'd confirm each.
7. Walk through the transactional-outbox + CDC flow that guarantees a cache invalidation is never lost, and explain why CDC ordering eliminates the stale-set race.
8. Compare hardware MESI to Redis RESP3 client-side caching point-by-point (directory, invalidation message, single-writer ordering, failure mode).

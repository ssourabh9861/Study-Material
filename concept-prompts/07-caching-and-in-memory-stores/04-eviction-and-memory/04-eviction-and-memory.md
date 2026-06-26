# Eviction & Memory Management

> Concept area: **Caching & In-Memory Stores** · Subtopic: **Eviction & Memory Management**
> Audience: a senior JVM/backend engineer who wants to master this end-to-end — design, operate, debug, teach, and interview on it.

---

## 1. Overview & where it fits

A cache is a bounded store that holds copies of data so future reads are faster than recomputing or re-fetching from the **system of record** (the authoritative source — a database, an upstream service, a file system, an expensive computation). Because the cache is *bounded* — it has a finite memory budget — the central problem of cache management is: **when the cache is full and you want to add something new, what do you throw away?** That decision is **eviction**. The set of rules that make it is an **eviction policy**.

Memory management is the broader discipline that eviction lives inside. It covers:

- **Eviction policy** — which entry to remove when space is needed (LRU, LFU, FIFO, random, TTL-based, segmented variants).
- **Admission policy** — a newer idea: before you even *insert* a new item, decide whether it is worth admitting at all (TinyLFU in Caffeine). Admission complements eviction.
- **Expiration (TTL)** — entries that become invalid after a time-to-live, independent of memory pressure. Removing them is *expiry*, mechanically distinct from eviction even though both free memory.
- **Sizing** — how big should the cache be, measured in entries, bytes, or a weight function, and how that interacts with the hit ratio and its economics.
- **The allocator** — the low-level memory subsystem (the JVM heap and GC for Java caches; **jemalloc** for Redis) that actually hands out and reclaims bytes, and which introduces **fragmentation** — wasted space between allocations.

**The problem it solves.** Without a bound, a cache grows until it exhausts memory and the process dies (an `OutOfMemoryError` on the JVM, the OOM killer on Linux for Redis). Eviction + memory management let you trade a *controlled* loss of cache contents (some misses) for *bounded, predictable* memory use and stable latency. The art is losing the *least valuable* entries so the **hit ratio** (fraction of lookups served from cache) stays as high as possible for a given memory budget.

**When you reach for it.** Always, the moment your cache is bounded — which it always should be in production. An unbounded cache is not a cache; it is a memory leak with good intentions. You tune eviction and memory specifically when: (1) the cache is large or hot enough that a few percentage points of hit ratio matter for cost/latency, (2) memory is constrained or expensive, (3) you see eviction storms, latency spikes at the memory limit, or `OOM`s, or (4) a scan or batch job is "polluting" the cache and pushing out your hot working set.

**One-paragraph mental model.** Picture the cache as a small, expensive shelf in front of a huge, slow warehouse. Every item on the shelf earns its keep by saving you a warehouse trip. When the shelf is full and a new item arrives, you must remove something. A good eviction policy removes the item *least likely to be asked for again soon*; a good admission policy refuses to even put a one-time fluke item on the shelf if it would displace a proven regular. TTL is a separate rule: some items spoil and must come off the shelf on a timer regardless of demand. And underneath the shelf is a real allocator that hands out bytes in fixed-size bins, so the shelf can be "full" while still wasting some space — that waste is fragmentation. Mastering this subtopic means knowing each of those mechanisms, how the two big systems you'll meet (Redis and Caffeine) actually implement them, and how to measure and tune them in production.

---

## 2. Foundations from first principles

### 2.1 What is a cache, precisely

A cache maps **keys** to **values** and answers one question fast: *given this key, do I have a fresh copy of its value?* Two outcomes:

- **Hit** — the key is present (and valid); return the cached value.
- **Miss** — the key is absent (or expired); go to the system of record, optionally store the result, and return it.

**Hit ratio** = hits / (hits + misses). **Miss ratio** = 1 − hit ratio. These are the headline metrics; almost every tuning decision is "did this raise the hit ratio for the same memory, or cut memory for the same hit ratio?"

### 2.2 Why caches are bounded

Memory is finite and (relative to disk) expensive. RAM gives you nanosecond-to-microsecond access; disk is microseconds-to-milliseconds; network another hop on top. A cache buys speed by spending RAM. Because RAM is finite, the cache must have a **capacity** — a hard limit beyond which it will not grow. Capacity can be expressed three ways:

- **By count** — e.g. "at most 100,000 entries." Simple; works when entries are uniform in size.
- **By size/weight** — e.g. "at most 512 MB," or a custom weigher like "sum of `value.length()`." Necessary when entries vary wildly in size.
- **By memory budget at the process level** — e.g. Redis `maxmemory 4gb`, where the server tracks its own allocator usage.

### 2.3 The core terms (defined as introduced)

- **Eviction** — removing an entry *to make room*, driven by capacity pressure. The entry was still valid; you discarded it by choice.
- **Expiry / expiration** — removing an entry because its **TTL (time-to-live)** elapsed; it is now considered stale/invalid. Driven by time, not by space. (A subtle but important distinction: a metric that lumps them together hides whether you are under memory pressure or just churning short-lived keys.)
- **TTL (time-to-live)** — a duration after which an entry is considered expired. Often set at write time (`SET key val EX 60`). Sometimes refreshed on access (sliding) or fixed from creation (absolute).
- **Recency** — how recently an entry was accessed. The basis of **LRU**.
- **Frequency** — how often an entry has been accessed. The basis of **LFU**.
- **Working set** — the set of keys actively in use during a window of time. The ideal cache holds exactly the working set. Eviction policies are heuristics for approximating "keep the working set."
- **Hot vs cold** — hot = frequently/recently used; cold = rarely used. Eviction wants to keep hot, drop cold.
- **Cache pollution** — when low-value entries (e.g. from a one-off table scan) flood the cache and evict the hot working set, tanking the hit ratio. **Scan resistance** is a cache's ability to resist this.
- **Admission** — deciding whether a *new* candidate is worth inserting at all, often by comparing its estimated future value to the victim it would evict.
- **Allocator** — the component that hands out raw memory. On the JVM it's the GC-managed heap; in Redis it's typically **jemalloc**.
- **Fragmentation** — usable memory that the allocator can't pack tightly: **internal** (you asked for 50 bytes, the allocator gave a 64-byte bin, 14 bytes wasted) and **external** (free memory exists but in pieces too small/scattered to satisfy a request).

### 2.4 The eviction policies, from simplest to smartest

A policy is fundamentally a way to pick a **victim** — the entry to evict — when capacity is exceeded. Build them up:

**FIFO (First-In-First-Out).** Evict the oldest *inserted* entry, regardless of how often it's used. Implemented with a simple queue. Cheap, but blind to access patterns: a frequently used item inserted long ago gets evicted even though it's hot. Rarely the right default; mostly a teaching baseline and occasionally fine for time-ordered data.

**Random.** Pick a victim uniformly at random. Sounds terrible, but it's O(1), needs no metadata, and is surprisingly competitive when access is near-uniform. It's the fallback behind Redis's *approximate* policies (it samples a few random keys rather than scanning all). Pure random has no scan resistance and no notion of hot/cold.

**LRU (Least Recently Used).** Evict the entry not used for the longest time. Intuition: recency predicts reuse (temporal locality). The canonical "good default." Classic exact implementation: a hash map for O(1) lookup plus a **doubly linked list** ordered by recency — on every access, unlink the node and move it to the head (most-recently-used end); the victim is always the tail. O(1) per operation but the per-access pointer rewiring and the list metadata cost memory and can be a concurrency bottleneck (every read mutates shared list state). **Weakness:** no scan resistance — a big sequential scan touches each key once, all "recently used," flushing the hot set.

**LFU (Least Frequently Used).** Evict the entry with the lowest access *count*. Intuition: frequency predicts reuse better than recency for skewed workloads (a few keys are popular for a long time). Naive LFU has two problems: (1) it needs a counter per entry and a way to find the minimum quickly; (2) **cache pollution by aging** — an item that was hugely popular last week keeps a high count forever and won't be evicted even after it goes cold. Real LFU implementations therefore **age/decay** counts over time. Exact LFU often uses a min-heap or a frequency-bucketed structure (an O(1) LFU exists using doubly linked lists of frequency buckets — the "Constant LFU" design).

**TTL-based eviction.** Not a recency/frequency policy at all — evict whatever is *closest to expiring* (or already expired). Useful when freshness, not popularity, dominates. Redis has `volatile-ttl` for exactly this.

**Segmented / adaptive policies** (the modern state of the art):

- **SLRU (Segmented LRU)** — split the cache into a **probationary** segment and a **protected** segment. New items enter probation; on a *second* hit they're promoted to protected. This gives scan resistance: a one-touch scan item stays in probation and is evicted first, never displacing protected hot items.
- **ARC (Adaptive Replacement Cache)** — IBM's design that keeps four lists: recently-used-once, recently-used-twice, and "ghost" lists (keys recently *evicted* from each) used to adaptively rebalance how much space goes to recency vs frequency. Patent history limited its open-source adoption; conceptually important.
- **TinyLFU / W-TinyLFU** — the policy Caffeine uses (Section 7). A tiny, approximate frequency sketch acts as an **admission filter** in front of an LRU/SLRU eviction, getting near-optimal hit ratios at a fraction of LFU's memory.
- **2Q, LIRS, CLOCK, CLOCK-Pro** — other named families; CLOCK is the classic low-overhead LRU approximation used in OS page caches (a circular buffer with reference bits).

### 2.5 allkeys vs volatile (Redis vocabulary, but a general idea)

When the cache is full, *which subset of keys is eligible for eviction*?

- **allkeys-*** policies consider **every** key. Use when Redis is a pure cache and any key may be sacrificed.
- **volatile-*** policies consider **only keys that have a TTL set** (an expiration). Use when Redis mixes durable data (no TTL — must survive) with cacheable data (has TTL — disposable). The risk: if no volatile keys exist when memory is full, eviction can't free anything and writes fail.

### 2.6 Lazy vs active expiration (the two ways TTLs get cleaned up)

A key with an elapsed TTL is logically expired immediately, but its memory isn't freed until something removes it. Two mechanisms, used together:

- **Lazy (passive) expiration** — when a client touches a key, the server checks "is it expired? if so, delete it now and behave as if it were absent." Cost is paid only on access. Downside: a key that's never touched again sits in memory forever, occupying space.
- **Active expiration** — a background job periodically samples keys and proactively deletes expired ones, reclaiming memory from keys nobody is touching. Trades a little CPU for bounded memory.

Both are needed: lazy guarantees *correctness* (you never serve an expired value), active guarantees *space reclamation* (memory doesn't pile up from cold expired keys).

---

## 3. How it works internally

This is the heart. We trace the actual internal workflows for the two systems a JVM engineer overwhelmingly meets: **Redis** (the remote in-memory store) and **Caffeine** (the in-process JVM cache). Where behavior is version-specific, it's flagged.

### 3.1 Redis: the maxmemory + eviction control flow

**Setup vocabulary.** Redis is a single-threaded (for command execution) in-memory key-value store; data lives in the process heap, allocated by **jemalloc** (a general-purpose memory allocator tuned for low fragmentation and multithread scalability; Redis bundles its own copy). The `maxmemory` config sets the byte budget; `maxmemory-policy` sets what to do at the limit.

**Step-by-step: what happens on a write when memory is at/over the limit.**

1. A client sends a write command (e.g. `SET`). Redis begins processing it in the main command loop.
2. Before (or as part of) applying the command, Redis calls `performEvictions()` (historically `freeMemoryIfNeeded()`). It computes current memory usage via the allocator (`used_memory`) and compares against `maxmemory`, after subtracting memory that doesn't count toward the limit (e.g. replication buffers, AOF buffers in some accounting modes).
3. If under the limit, proceed normally. If over, and the policy is `noeviction`, the write is **rejected** with an `OOM command not allowed when used memory > 'maxmemory'` error (reads still work). Otherwise, enter the eviction loop.
4. **Eviction loop** — repeatedly select and delete victims until usage drops below the limit (or it gives up after enough failures):
   - **Pick the candidate pool.** For `allkeys-*`, candidates come from the entire keyspace; for `volatile-*`, only from keys with a TTL (the separate expires dict). Redis does **not** scan all keys — it **samples** a small number (`maxmemory-samples`, default **5**) of random keys.
   - **Score the sampled keys** by the policy:
     - `*-lru` — by approximate idle time (least recently used wins eviction). Each object stores a 24-bit LRU clock; Redis compares it to a global clock to estimate idle time. This is *approximate* LRU — it never maintains a global recency-ordered list; it just samples and picks the oldest in the sample.
     - `*-lfu` — by an approximate access frequency counter (least frequently used wins eviction). See 3.2.
     - `*-random` — pick at random from the sample (or keyspace).
     - `volatile-ttl` — pick the key with the nearest expiration.
   - **Eviction pool optimization.** Since Redis 3.0, instead of evicting the single best sampled key each round, Redis maintains a small **eviction pool** (default 16 slots) of the best candidates seen across samples, sorted by eviction-worthiness, so each round's sampling contributes to a higher-quality victim choice — this makes approximate LRU much closer to true LRU.
   - **Evict** the chosen key: delete it, fire keyspace notifications if enabled, propagate a `DEL`/`UNLINK` to replicas and the AOF, update `evicted_keys` stat, free the memory.
   - Re-check usage; loop.
5. If, after the loop, memory still can't be brought under the limit (e.g. `volatile-*` policy but no volatile keys, or everything is too big), the original write is rejected with an OOM error.

**Key consequence:** eviction is *synchronous with command processing* and runs on the single main thread. A heavy eviction storm at the limit therefore *adds latency to every command* — this is the classic "latency spike at maxmemory" production symptom (Section 9).

### 3.2 Redis approximate LRU and LFU in detail

**Approximate LRU.** Each Redis object header carries a 24-bit field repurposed for LRU. There's a global `server.lruclock` updated ~once per second (resolution: the clock wraps about every 194 days at 1-second resolution). On access, the object's LRU field is set to the current clock. Idle time = clock − object.lru. Eviction samples `maxmemory-samples` keys and evicts the one with the largest idle time (helped by the eviction pool). Raising `maxmemory-samples` (e.g. to 10) makes the approximation closer to true LRU at higher CPU cost; the default of 5 is a good speed/accuracy tradeoff (Redis docs note 10 is "very close to true LRU" but costs more CPU).

**Approximate LFU (Redis 4.0+).** The 24-bit field is split into two parts:

- **8 bits: a counter** (`LOG_C`) that does **not** count raw accesses. It's a *logarithmic, probabilistic* counter that saturates near 255, representing roughly an order-of-magnitude-scaled frequency. On each access it increments with probability decreasing as the counter grows, tuned by `lfu-log-factor` (default **10**). This lets one byte represent a huge dynamic range of access frequencies.
- **16 bits: last-decrement time** in minutes. The counter is *halved/decremented* over time so old popularity decays — controlled by `lfu-decay-time` (default **1** minute = the counter loses points roughly every minute of idleness). This solves naive LFU's "never forgets" problem.

So Redis LFU is genuinely *aging* frequency, approximated in one byte. New keys start at counter value **5** (`LFU_INIT_VAL`) so they aren't instantly evicted before they've had a chance to prove popularity. Eviction samples keys and evicts the lowest counter (with ties broken / refined by the eviction pool).

### 3.3 Redis TTL expiry internals (lazy + active)

**Lazy expiration.** Every key access funnels through `lookupKey` → `expireIfNeeded`. If the key has an expire and the current time passed it, Redis deletes it (synchronously, or via `UNLINK` for large objects if lazy-freeing is on), propagates a `DEL` to replicas/AOF, and treats the key as missing. **Replica subtlety:** replicas do *not* expire keys on their own to avoid divergence; they wait for the master's `DEL`. A replica will *logically* hide an expired key from reads but keep it until told to delete (this changed/tightened across versions; pre-Redis-6 reads on replicas could return expired data in edge cases).

**Active expiration.** A background cycle, `activeExpireCycle`, runs on the server cron (`serverCron`, ~10 Hz by default via `hz`, default **10**). Each cycle, for each database, it: samples a batch (e.g. 20) of keys *from the expires dict*, deletes the expired ones, and if more than a threshold (historically 25%) of the sampled batch was expired, it loops again — adaptively spending more effort when there's a lot to clean. It is time-bounded so it can't monopolize the CPU (capped at a fraction of CPU time, governed by `hz` and an internal effort cap; `active-expire-effort` 1–10, default **1**, tunes aggressiveness in newer versions). This bounds the amount of "expired but not yet reclaimed" memory.

**State machine for a key's life:** `created (maybe with TTL)` → `live` → (TTL elapses) `logically expired` → (lazy on access *or* active cycle) `deleted, memory freed`. Independently, at any point while `live`, memory pressure can move it `live → evicted`.

### 3.4 Caffeine (JVM): the in-process workflow

**Setup vocabulary.** Caffeine is the de-facto modern Java caching library (successor to Guava Cache), built around **W-TinyLFU**. It's an on-heap cache: entries are Java objects on the JVM heap, reclaimed by the **garbage collector**. Because reads must be fast and concurrent, Caffeine does *not* update shared eviction state on the read path synchronously.

**Step-by-step read path:**

1. `cache.getIfPresent(key)` (or `get(key, loader)`) hashes the key and looks it up in a concurrent hash table (Caffeine's own striped/segmented structure, conceptually like `ConcurrentHashMap`). This is lock-free in the common case.
2. On a hit, Caffeine **does not** immediately reorder its LRU/frequency structures (that would serialize reads). Instead it records the access into a **read buffer** — a lossy, ring-buffer-per-stripe structure. If the buffer is full, the access is simply dropped (acceptable: it's only a hint to the eviction policy).
3. The value is returned to the caller immediately.

**Step-by-step write path:**

1. `cache.put(key, value)` inserts into the hash table and appends the operation to a **write buffer** (a bounded MPSC queue).
2. The write buffer is drained under a try-lock by a maintenance task.

**The maintenance task (where eviction actually happens):**

- Caffeine amortizes eviction onto a single-threaded **maintenance** routine, scheduled on an `Executor` (default `ForkJoinPool.commonPool()`) and/or triggered opportunistically when buffers fill. Under a non-blocking try-lock, it:
  1. Drains the read buffer → replays accesses into the **frequency sketch** and reorders the eviction structures.
  2. Drains the write buffer → applies inserts/updates/removals to the eviction structures.
  3. Runs **expiration** (see below) and **size-based eviction** if over capacity.
- This design means a read never blocks on eviction; eviction quality is maintained "eventually" by replaying buffered hints.

**W-TinyLFU eviction structure (the layout):**

- The cache space is split into a small **window** cache (LRU, ~1% by default) and a large **main** cache governed by **SLRU** (probationary + protected, ~99%).
- New entries go into the **window**. When the window overflows, the candidate evicted from the window is *not* simply dropped — it goes to the **admission filter**.
- **Admission filter (TinyLFU):** the candidate's estimated frequency (from the sketch) is compared to the frequency of the **victim** that the main cache would evict. If the candidate is estimated more frequent, it's admitted and the victim evicted; otherwise the candidate is rejected. This is the crucial scan-resistance + admission step: a one-hit scan item rarely beats a proven hot victim, so it gets rejected and never pollutes the main cache.
- **The frequency sketch** is a **Count-Min Sketch** with 4-bit counters (a probabilistic frequency estimator: several hash functions index into counter arrays; the estimate is the minimum across them — minimizing overcount). It uses ~ a few bits per cache entry, far less than a full counter per key. It is **aged** by halving all counters once the total observed events reach a reset threshold (≈ sketch size × something), giving decay so old popularity fades.
- **Adaptive window (Caffeine ≥ 2.x "hill-climbing"):** Caffeine dynamically resizes the window vs main split using hill-climbing on the observed hit ratio, adapting between recency-favoring and frequency-favoring workloads.

**Caffeine expiration internals.** Caffeine supports `expireAfterWrite`, `expireAfterAccess`, and `expireAfter(Expiry)` (per-entry, variable). For fixed durations it uses ordered structures (access-order / write-order linked queues) so the oldest is at the head and expiration is O(1) to find; for variable per-entry TTLs it uses a **hierarchical timer wheel** (a bucketed timer structure giving amortized O(1) scheduling of expirations across many time resolutions). Expiration is processed during maintenance — so, like eviction, it's amortized and slightly lazy, not a dedicated wall-clock timer per entry. Removal can also be driven by **reference-based eviction** (`weakKeys`, `weakValues`, `softValues`) which hands eviction decisions to the GC.

### 3.5 The allocator layer: jemalloc and JVM heap

**jemalloc (Redis).** jemalloc serves allocations from **size classes** (bins): e.g. 8, 16, 32, 48, 64, … bytes, then larger spaced classes. A request is rounded *up* to the nearest size class → **internal fragmentation** (a 50-byte string in a 64-byte bin wastes 14 bytes). It groups allocations into **arenas** and **runs/extents** to reduce lock contention and **external fragmentation** (free memory scattered such that a large request can't be satisfied even though total free ≥ request). Redis exposes the ratio `mem_fragmentation_ratio = used_memory_rss / used_memory` (RSS = resident set size, the actual OS-resident physical memory; `used_memory` = what Redis thinks it allocated). A ratio > ~1.5 suggests significant fragmentation; < 1.0 means Redis memory has been swapped to disk (bad). Redis 4.0+ ships **active defragmentation** (`activedefrag`) which, with jemalloc's help, moves objects to compact memory live (see 7.4).

**JVM heap (Caffeine).** On-heap caches don't have an allocator *you* tune the way you tune jemalloc; instead the **garbage collector** reclaims dead entries. Large caches create **GC pressure**: many long-lived objects sit in the **old generation**, and a big cache can lengthen GC pauses or, with reference-based eviction (`softValues`), cause the GC to evict cache entries under memory pressure (unpredictably). This is why heavy JVM caches often move **off-heap** (e.g. Ehcache with off-heap tiers, or `ByteBuffer`/`Unsafe`-based stores) to keep them out of GC's accounting.

---

## 4. The complete toolkit

### 4.1 Redis: `maxmemory-policy` values

| Policy | Eligible keys | Victim selection | Use when |
|---|---|---|---|
| `noeviction` | — | none; writes error at limit | Redis is a database / durability matters more than availability of writes |
| `allkeys-lru` | all keys | approx least-recently-used | Pure cache, recency-driven workload (good default cache policy) |
| `allkeys-lfu` (4.0+) | all keys | approx least-frequently-used (aging) | Pure cache, skewed popularity, scan-heavy |
| `allkeys-random` | all keys | random sample | Uniform access; minimal overhead |
| `volatile-lru` | keys with TTL | approx LRU among them | Mixed durable+cache data, recency-driven |
| `volatile-lfu` (4.0+) | keys with TTL | approx LFU among them | Mixed data, frequency-driven |
| `volatile-random` | keys with TTL | random among them | Mixed data, uniform |
| `volatile-ttl` | keys with TTL | nearest expiration first | Mixed data; freshness/TTL is the priority |

### 4.2 Redis: relevant config directives (with defaults)

| Directive | Default | What it does |
|---|---|---|
| `maxmemory` | `0` (unlimited) | Byte budget. `0` = no limit (dangerous in prod). Accepts `100mb`, `4gb`, etc. |
| `maxmemory-policy` | `noeviction` | Eviction policy (table above). |
| `maxmemory-samples` | `5` | Keys sampled per eviction round; higher = closer to true LRU/LFU, more CPU. |
| `maxmemory-eviction-tenacity` (6.0+) | `10` | How hard Redis tries to keep up with eviction vs serving clients (0–100). |
| `maxmemory-clients` (7.0+) | `0`/auto | Caps memory used by client connection buffers; can evict heavy clients. |
| `lfu-log-factor` | `10` | Controls how fast the LFU log-counter saturates (higher = slower saturation, finer high-freq resolution). |
| `lfu-decay-time` | `1` (min) | Minutes of idleness per counter decrement (aging). `0` = decay on every access. |
| `hz` | `10` | Background cron frequency (Hz). Affects active expiry cadence. |
| `dynamic-hz` | `yes` | Lets Redis raise effective `hz` under load for more responsive expiry. |
| `active-expire-effort` | `1` | 1–10; aggressiveness of the active expiry cycle (more = more CPU, less stale memory). |
| `lazyfree-lazy-eviction` | `no` | Free evicted objects asynchronously (`UNLINK`-style) to avoid blocking on large objects. |
| `lazyfree-lazy-expire` | `no` | Async free on expiry. |
| `lazyfree-lazy-server-del` | `no` | Async free on overwrite/`DEL`-like operations. |
| `activedefrag` | `no` | Enable active defragmentation (jemalloc only). |
| `active-defrag-ignore-bytes` | `100mb` | Don't start defrag below this much fragmentation waste. |
| `active-defrag-threshold-lower` | `10` (%) | Start defrag when fragmentation ≥ this percent. |
| `active-defrag-threshold-upper` | `100` (%) | Max-effort defrag at/above this percent. |
| `active-defrag-cycle-min`/`-max` | `1`/`25` (% CPU) | CPU budget bounds for defrag. |

### 4.3 Redis: relevant commands

| Command | Purpose |
|---|---|
| `CONFIG GET maxmemory` / `CONFIG SET maxmemory-policy allkeys-lru` | Read/change config at runtime (persist with `CONFIG REWRITE`). |
| `INFO memory` | `used_memory`, `used_memory_rss`, `mem_fragmentation_ratio`, `maxmemory`, `maxmemory_policy`, `mem_allocator`, `evicted_keys`, etc. |
| `INFO stats` | `evicted_keys`, `expired_keys`, `keyspace_hits`, `keyspace_misses`. |
| `MEMORY USAGE key` | Estimated bytes used by a key (including overhead). |
| `MEMORY DOCTOR` | Human-readable memory health advice. |
| `MEMORY STATS` | Detailed allocator/dataset breakdown. |
| `MEMORY PURGE` | Ask the allocator to release free pages back to the OS (jemalloc). |
| `OBJECT FREQ key` | LFU access frequency counter (only under an `*-lfu` policy). |
| `OBJECT IDLETIME key` | Idle seconds (only under non-LFU policies). |
| `TTL key` / `PTTL key` | Remaining TTL in seconds / milliseconds (`-1` no TTL, `-2` missing). |
| `EXPIRE`/`PEXPIRE`/`EXPIREAT` (+ `NX/XX/GT/LT` flags, 7.0+) | Set/adjust TTLs conditionally. |
| `SET key val EX 60` / `PX` / `EXAT` / `KEEPTTL` | Write with TTL options; `KEEPTTL` preserves existing TTL on overwrite. |
| `PERSIST key` | Remove a key's TTL (makes it durable / volatile-ineligible). |
| `SCAN` (+ `redis-cli --bigkeys` / `--memkeys`) | Cursor iterate keys / find big keys without blocking. |
| `DEBUG SLEEP`, `LATENCY DOCTOR`, `LATENCY HISTORY` | Diagnose latency, including eviction-induced spikes. |

### 4.4 Caffeine (Java): the builder API

| Method | Purpose / default |
|---|---|
| `maximumSize(long)` | Count-based capacity (entry count). |
| `maximumWeight(long)` + `weigher((k,v) -> int)` | Size/weight-based capacity (e.g. bytes). |
| `expireAfterWrite(Duration)` | Absolute TTL from last write/create. |
| `expireAfterAccess(Duration)` | Sliding TTL from last read or write. |
| `expireAfter(Expiry)` | Per-entry variable TTL (create/update/read). |
| `refreshAfterWrite(Duration)` | Async reload after duration on next access (serves stale while refreshing). |
| `weakKeys()` / `weakValues()` / `softValues()` | Reference-based (GC-driven) eviction; `softValues` = evict under heap pressure. |
| `recordStats()` | Enable hit/miss/eviction counters (`cache.stats()`). |
| `removalListener((k,v,cause) -> …)` | Callback on removal; `cause` ∈ {EXPLICIT, REPLACED, COLLECTED, EXPIRED, SIZE}. |
| `executor(Executor)` | Where maintenance runs (default `ForkJoinPool.commonPool()`). |
| `ticker(Ticker)` | Inject a clock for deterministic tests. |
| `evictionListener(...)` | Synchronous notification at eviction time (vs async removalListener). |
| `build()` / `build(loader)` | `Cache` vs `LoadingCache` (auto-loads on miss). |
| `buildAsync(...)` | `AsyncCache` returning `CompletableFuture`. |

**Caffeine `CacheStats` fields:** `hitCount`, `missCount`, `hitRate()`, `missRate()`, `loadSuccessCount`, `loadFailureCount`, `totalLoadTime`, `evictionCount`, `evictionWeight`. These are your tuning instruments.

---

## 5. Code examples by use case

### 5.1 Caffeine: size-bounded LRU-ish cache with stats (the default workhorse)

```java
import com.github.benmanes.caffeine.cache.*;

LoadingCache<String, User> users = Caffeine.newBuilder()
    .maximumSize(100_000)                 // count-bounded; W-TinyLFU under the hood
    .expireAfterWrite(Duration.ofMinutes(10)) // absolute TTL: freshness guard
    .recordStats()                         // enable metrics for tuning
    .removalListener((String k, User v, RemovalCause cause) ->
        log.debug("removed {} cause={}", k, cause)) // SIZE vs EXPIRED tells you why
    .build(key -> userRepository.findById(key)); // loader runs on miss

User u = users.get("u-42");               // hit or auto-load; never returns null on success
CacheStats s = users.stats();
log.info("hitRate={} evictions={}", s.hitRate(), s.evictionCount());
```

Why it matters: `maximumSize` + `recordStats` is 90% of real usage. Watch `evictionCount` rising with a falling `hitRate` — that's an undersized cache. `expireAfterWrite` bounds staleness independently of memory.

### 5.2 Caffeine: byte-bounded cache with a custom weigher (variable-size values)

```java
Cache<String, byte[]> blobs = Caffeine.newBuilder()
    .maximumWeight(512L * 1024 * 1024)     // 512 MB budget
    .weigher((String k, byte[] v) -> k.length() + v.length + 48) // approx heap cost
    .recordStats()
    .build();
```

Use when values vary wildly (HTML fragments, serialized blobs). The weigher returns an `int`; total weight is bounded, not entry count. Note: weight is computed once at insert (or update) — don't mutate values in place expecting reweighing.

### 5.3 Caffeine: refresh-ahead to avoid stampedes on hot keys

```java
LoadingCache<String, Config> cfg = Caffeine.newBuilder()
    .maximumSize(1_000)
    .refreshAfterWrite(Duration.ofSeconds(30)) // async reload after 30s on access
    .expireAfterWrite(Duration.ofMinutes(5))   // hard cap if never accessed
    .build(this::loadConfig);
```

`refreshAfterWrite` serves the **stale** value immediately while reloading in the background — so a hot key never blocks N threads on a synchronous miss (the **thundering-herd / cache-stampede** problem). Combine with `expireAfterWrite` as a hard staleness ceiling.

### 5.4 Redis: configure a pure cache with approximate LFU + TTLs

```bash
# redis.conf (or CONFIG SET at runtime)
maxmemory 4gb
maxmemory-policy allkeys-lfu      # frequency-aware, scan-resistant, aging
maxmemory-samples 10              # closer to true LFU at modest CPU cost
lfu-log-factor 10
lfu-decay-time 1
lazyfree-lazy-eviction yes        # don't block on freeing big evicted objects
```
```bash
# write with a TTL so even durable-looking keys are reclaimable
redis-cli SET session:abc "{...}" EX 1800      # 30-min session
redis-cli OBJECT FREQ session:abc              # inspect LFU counter
redis-cli INFO stats | grep -E 'evicted_keys|expired_keys|keyspace_(hits|misses)'
```

### 5.5 Redis: mixed durable + cache data with `volatile-lru`

```bash
maxmemory 8gb
maxmemory-policy volatile-lru     # only TTL'd keys are evictable
```
```bash
redis-cli SET feature_flags "..." # NO TTL -> never evicted (durable config)
redis-cli SET page:123 "<html>" EX 600   # TTL -> evictable cache entry
```
Pitfall to guard against: if memory fills and **no** volatile keys exist, writes fail with OOM even though tons of durable data is present. Always ensure a healthy population of TTL'd keys, or use an `allkeys-*` policy.

### 5.6 Scan-resistance demonstration (conceptual, Java pseudo-test)

```java
// Warm a hot working set, then run a one-pass "scan" of cold keys.
Cache<Integer,Integer> c = Caffeine.newBuilder().maximumSize(1000).recordStats().build();
for (int round = 0; round < 50; round++)         // establish hot set 0..999
    for (int k = 0; k < 1000; k++) c.put(k, k);
long beforeHot = c.stats().hitCount();
for (int k = 1_000_000; k < 1_010_000; k++) c.getIfPresent(k); // cold scan, 10k one-hit keys
// Re-touch hot set:
for (int k = 0; k < 1000; k++) c.getIfPresent(k);
System.out.println("hot survivors: " + c.estimatedSize()); // W-TinyLFU rejects most scan keys
```
With a plain LRU the hot set would be flushed by the scan; W-TinyLFU's admission filter rejects the one-hit cold keys, preserving the hot set — that's scan resistance you can observe.

### 5.7 Measuring Redis memory health from code (Jedis)

```java
try (Jedis j = pool.getResource()) {
    String mem = j.info("memory");        // parse mem_fragmentation_ratio, used_memory_rss
    String stats = j.info("stats");       // evicted_keys, expired_keys, keyspace_hits/misses
    // Alert if evicted_keys is climbing AND hitRate dropping -> undersized / wrong policy
}
```

---

## 6. Implementation concerns & best practices

**Performance.**
- *Redis eviction runs on the main thread* — at the limit, every write may trigger an eviction loop, adding tail latency to all commands. Keep headroom (operate below `maxmemory`, e.g. target 70–80%), enable `lazyfree-lazy-eviction` so freeing big objects doesn't block, and prefer fewer/smaller large objects.
- *Caffeine* keeps reads lock-free via buffered hints + amortized maintenance; the cost is that eviction quality and expiration are *eventually* applied. Don't assume `expireAfterWrite` removes an entry the instant the clock passes — it's removed on the next maintenance cycle/access.
- Sampling cost: raising `maxmemory-samples` improves accuracy but costs CPU per eviction; 5 is the default sweet spot, 10 for accuracy-sensitive workloads.

**Correctness / concurrency.**
- *TTL is not a transaction.* A key can be served just before it expires and missing the next millisecond; design for eventual staleness.
- *Replica expiry* — replicas don't independently expire; reads must tolerate the master-driven `DEL` lag. Don't build correctness on "the replica deleted it."
- *Caffeine `get(k, loader)`* guarantees the loader runs at most once per key under contention (no duplicate loads), preventing stampedes within a process. Across processes/nodes you still need distributed coordination.

**Memory.**
- Watch **fragmentation** (`mem_fragmentation_ratio`): > ~1.5 → consider `activedefrag` / `MEMORY PURGE`; < 1.0 → swapping, fix the host.
- On the JVM, a large on-heap cache is **old-gen pressure**: it can lengthen GC pauses. For multi-GB caches consider off-heap stores. Avoid `softValues` for predictability — GC decides eviction timing, which is opaque and bursty.
- Account for **per-entry overhead**: Redis object headers, dict entries, expires-dict entries; Java object headers (~12–16 bytes), map node overhead. A "small" 50-byte value can cost 100+ bytes resident.

**Security.** Cache memory can hold sensitive data; TTLs limit exposure window. `maxmemory-clients` (Redis 7) caps client-buffer memory to prevent a slow/abusive client from causing OOM (a memory-exhaustion DoS vector). Don't log full values in removal listeners.

**Cost.** RAM is the dominant cost of caching. The decision is economic (Section 8): each extra GB buys some hit-ratio improvement with diminishing returns; spend RAM only where the marginal misses are expensive.

**Observability.** Track, at minimum: hit/miss ratio, `evicted_keys` vs `expired_keys` (separately!), `used_memory`/`maxmemory` utilization, `mem_fragmentation_ratio`, eviction latency, and (Caffeine) `evictionCount`/`hitRate`/`loadTime`. Separating evictions from expirations tells you *whether you're memory-bound or just churning TTLs*.

**Testability.** Inject a `Ticker` (Caffeine) or use a fake clock to test TTL/expiry deterministically; never `Thread.sleep`. For Redis, test policy behavior with `OBJECT FREQ`/`IDLETIME` and small `maxmemory` in integration tests.

**Production hardening.**
- Always set `maxmemory` and a non-`noeviction` policy for caches.
- Always set TTLs on cache entries even under `allkeys-*` (defense in depth — bounds staleness and gives the active-expire cycle work to do).
- Keep memory headroom; alert before the limit, not at it.
- Use `UNLINK`/lazyfree for big keys.

**Anti-patterns.**
- Unbounded cache (memory leak).
- `noeviction` on a pure cache (writes start failing under load).
- `volatile-*` policy with few/no TTL'd keys (eviction can't free memory → OOM).
- One giant value/collection that can't be evicted granularly (a 2 GB hash can't be partially evicted).
- Caching low-value, high-churn data (scans, analytics) into the same namespace as the hot working set (pollution) — segregate or rely on admission/scan-resistant policies.
- Treating `expireAfterWrite` as a precise timer.

---

## 7. Advanced topics & deep internals

### 7.1 Why approximate beats exact in practice
Exact LRU needs a globally ordered list mutated on *every* read — a synchronization bottleneck and memory tax. Redis's sampled approximate LRU and Caffeine's buffered/amortized approach both trade a sliver of accuracy for massive throughput. Empirically, the eviction-pool-assisted sampling (Redis 3.0+) gets within a few percent of true LRU's hit ratio, and W-TinyLFU often *beats* exact LRU on real workloads because frequency-awareness + admission outperforms pure recency.

### 7.2 W-TinyLFU internals deeper
- **Count-Min Sketch**: `d` hash functions index `w`-wide 4-bit counter rows; `estimate(x) = min over rows`. 4 bits caps each counter at 15; the **aging** step halves all counters when a global event counter hits the sample size, so the sketch tracks *recent* frequency, not all-time. Memory ≈ a handful of bits per tracked element — orders of magnitude less than per-key 64-bit counters.
- **Doorkeeper** (optional optimization): a small Bloom filter in front of the sketch absorbs the flood of one-hit-wonders so they don't even enter the main sketch.
- **Window (W-)**: the small front LRU captures very recent items (good for bursty recency) before they face the frequency-based admission gate, fixing TinyLFU's weakness on freshly-popular keys.
- **Adaptivity**: hill-climbing periodically nudges the window/main ratio and keeps the change if hit ratio improved — automatically tuning between recency- and frequency-leaning workloads.

### 7.3 Redis LFU counter math
The increment probability is `1 / ((counter - LFU_INIT_VAL) * lfu-log-factor + 1)`. With `lfu-log-factor=10`, a counter of 5 (init) climbs readily; reaching ~255 requires on the order of millions of accesses — the log scale lets 8 bits cover ~1 to ~1M+ frequency. `lfu-decay-time` minutes of idleness subtracts from the counter, so trending keys overtake formerly-hot-but-now-cold keys. Tune `lfu-log-factor` higher to better distinguish *very* hot keys; tune `lfu-decay-time` lower to forget faster (more recency-like).

### 7.4 Active defragmentation
With jemalloc, Redis can query whether a given allocation would benefit from relocation and, if so, copy the object to a fresh allocation and free the old one — compacting live, without a stop-the-world pass. It's CPU-throttled by `active-defrag-cycle-min/max` and only kicks in past `active-defrag-threshold-lower` and `active-defrag-ignore-bytes`. Trade CPU for reclaimed RSS. `MEMORY PURGE` separately tells jemalloc to return free (already-unused) pages to the OS.

### 7.5 `used_memory` vs RSS vs `maxmemory`
`maxmemory` is compared against `used_memory` (allocator-reported), **not** RSS. So you can be under `maxmemory` while RSS (what the OS and your container cgroup see) is much higher due to fragmentation — risking the **cgroup/OS OOM killer** even though Redis thinks it's fine. Always size the container with fragmentation headroom and watch RSS, not just `used_memory`.

### 7.6 Lesser-known behaviors
- New Redis keys under LFU start at counter **5**, not 0, so they survive their infancy.
- `volatile-ttl` is *approximate* too (sampled), not a perfect priority queue of expirations.
- Caffeine expiration via timer wheel means variable per-entry TTLs are cheap even with millions of entries.
- Caffeine `evictionListener` is synchronous (runs during maintenance, can affect timing); `removalListener` is asynchronous — pick deliberately.
- A Redis write that's part of a transaction/Lua script still triggers eviction; a script that allocates a lot can push past the limit mid-execution.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Policy selection

| If your workload… | Use | Avoid |
|---|---|---|
| Has strong temporal locality (recent = soon again) | LRU (`allkeys-lru`) | LFU may keep stale-popular keys |
| Has skewed popularity (Zipfian; few hot keys long-term) | LFU (`allkeys-lfu`) / W-TinyLFU | FIFO/random lose the hot set |
| Is dominated by scans/batch passes | W-TinyLFU / SLRU / LFU | Plain LRU (flushed by scans) |
| Mixes durable + cache data in one Redis | `volatile-*` | `allkeys-*` (would evict durable data) |
| Cares about freshness over popularity | `volatile-ttl` / short TTLs | LFU (ignores age) |
| Is near-uniform access | random / LRU | complex policies (no benefit) |
| Must never drop data (it's a DB) | `noeviction` + monitoring | any `allkeys-*` |

### 8.2 In-process (Caffeine) vs remote (Redis)

| Dimension | Caffeine (on-heap, in-process) | Redis (remote) |
|---|---|---|
| Latency | nanoseconds (no network) | microseconds–ms (network) |
| Shared across nodes | No (per-JVM) | Yes |
| Capacity | bounded by heap; GC pressure | bounded by `maxmemory`/RAM |
| Eviction quality | W-TinyLFU (excellent) | approx LRU/LFU (very good) |
| Consistency | per-node, can diverge | single shared view |
| Memory mgmt | GC; weigher; off-heap if huge | jemalloc; defrag; TTL cycles |

Common pattern: **near-cache / two-tier** — Caffeine as L1 (per-node, tiny TTL) in front of Redis as L2 (shared) in front of the DB.

### 8.3 Sizing & hit-ratio economics
Hit ratio vs cache size typically follows a **concave** curve (diminishing returns), often modeled by Zipf: doubling cache size might raise hit ratio from 80%→88%, then 88%→92%, then 92%→94%. The economic rule: keep adding RAM while `Δhit_ratio × cost_per_miss × request_rate > cost_of_RAM`. Compute `cost_per_miss` concretely (the latency/$$ of a DB hit) and stop where the marginal saved-miss value drops below marginal RAM cost. Don't chase 99%+ hit ratios blindly — the last few points cost the most RAM.

---

## 9. Failure modes & debugging

**1. Latency spikes at `maxmemory`.** *Symptom:* p99 jumps when `used_memory` reaches `maxmemory`. *Cause:* synchronous eviction loop on the main thread per write. *Diagnose:* `INFO memory` (used vs max), `INFO stats` (`evicted_keys` climbing), `LATENCY HISTORY`/`LATENCY DOCTOR`. *Fix:* add memory headroom, enable `lazyfree-lazy-eviction`, reduce big-object churn, raise `maxmemory` or shard.

**2. OOM despite under `maxmemory` (RSS blowup).** *Symptom:* container/cgroup kills Redis though `used_memory` < `maxmemory`. *Cause:* fragmentation → high RSS; `maxmemory` is checked against `used_memory`, not RSS. *Diagnose:* `mem_fragmentation_ratio` > ~1.5, compare RSS to used. *Fix:* `activedefrag yes`, `MEMORY PURGE`, size container with headroom.

**3. Writes start failing (`OOM command not allowed`).** *Cause:* `noeviction`, or `volatile-*` with no TTL'd keys to evict. *Diagnose:* `CONFIG GET maxmemory-policy`; count keys with TTL. *Fix:* switch to `allkeys-*`, or ensure TTLs are set.

**4. Hit ratio collapses after a batch job (pollution).** *Cause:* a scan flooded the cache, evicting the hot set under LRU. *Diagnose:* hit ratio drop correlated with the job; `evicted_keys` spike. *Fix:* `allkeys-lfu` / W-TinyLFU (scan-resistant), separate namespace/instance for batch data, or short TTLs on scan results.

**5. Memory never drops though keys expired.** *Cause:* cold expired keys never touched (lazy can't fire) and active cycle too gentle. *Diagnose:* `expired_keys` vs key count; rising `used_memory` with stable traffic. *Fix:* raise `active-expire-effort`/`hz`, ensure TTLs, consider `MEMORY PURGE`.

**6. JVM GC pauses from a giant on-heap cache.** *Symptom:* long old-gen pauses. *Diagnose:* GC logs show large live old-gen; correlate with cache size. *Fix:* shrink the cache (weigher in bytes), move off-heap, avoid `softValues` (unpredictable GC-driven eviction).

**7. Caffeine "expired entry still served / still counted."** *Cause:* expiration is amortized to maintenance; not an instant timer. *Diagnose:* check `cleanUp()` behavior, `removalListener` causes. *Fix:* understand the semantics; call `cache.cleanUp()` in tests; use `Ticker` for determinism.

**Real-world flavored incidents.** (a) A team ran `noeviction` "to be safe," traffic grew, Redis hit `maxmemory`, and *all writes* (including session updates) began erroring — an outage caused by the conservative setting. (b) A nightly export ran a full `SCAN`+`GET` under `allkeys-lru`, flushing the hot product cache nightly and spiking DB load every morning until they moved to `allkeys-lfu`. (c) A container OOM-killed Redis weekly; `used_memory` was 6 GB under an 8 GB limit but RSS was 9 GB from fragmentation — fixed by enabling `activedefrag` and adding headroom.

---

## 10. Interview drill

**Q1. LRU vs LFU — when each?**
*Model:* LRU evicts least-recently-used; great for temporal locality. LFU evicts least-frequently-used (with aging); better for skewed, long-lived popularity and scan resistance. Use LFU when a stable hot set dominates and scans threaten LRU.
*Probes:* (a) *Why does naive LFU fail?* — it never forgets old popularity; need decay/aging. (b) *How does Redis age LFU?* — log counter + `lfu-decay-time` minute decrements. (c) *Cost of exact LFU?* — per-key counter + min-finding; approximated via sketches.

**Q2. How does Redis implement "LRU" if it doesn't keep a recency list?**
*Model:* Approximate LRU via per-object 24-bit LRU clock + random sampling (`maxmemory-samples`, default 5) + an eviction pool of best candidates; picks the highest idle time in the sample.
*Probes:* (a) *Effect of raising samples to 10?* — closer to true LRU, more CPU. (b) *What's the eviction pool?* — 16-slot best-candidate buffer across rounds (3.0+). (c) *Where does eviction run?* — main thread, synchronous with writes.

**Q3. Lazy vs active expiration — why both?**
*Model:* Lazy deletes on access (correctness, cheap); active background-samples and deletes (reclaims memory from untouched expired keys). Lazy alone leaks memory; active alone wastes CPU.
*Probes:* (a) *How does the active cycle adapt?* — loops more when many sampled keys are expired; CPU-bounded by `hz`/`active-expire-effort`. (b) *Replica expiry?* — replicas wait for master `DEL`. (c) *Eviction vs expiry difference?* — space-driven vs time-driven.

**Q4. What happens at `maxmemory`?**
*Model:* On a write, `performEvictions` runs; if `noeviction` the write errors; else it samples/evicts until under the limit; if it can't free enough, the write errors.
*Probes:* (a) *`maxmemory` vs RSS?* — checked against `used_memory`, not RSS; fragmentation can still OOM. (b) *`volatile-*` with no TTL keys?* — can't free → OOM. (c) *Latency impact?* — adds work to every write at the limit.

**Q5. Explain W-TinyLFU and why it beats LRU.**
*Model:* A small admission window (LRU) feeds candidates to a TinyLFU admission filter using a Count-Min Sketch of recent frequencies; only candidates more frequent than the would-be victim are admitted; main cache is SLRU. Result: scan resistance + frequency-awareness at tiny memory cost; adaptive window self-tunes.
*Probes:* (a) *Why a sketch not counters?* — bits/entry vs 64-bit/key. (b) *How is it aged?* — halve counters at a reset threshold. (c) *What does the window fix?* — TinyLFU's weakness on freshly popular keys.

**Q6. (senior signal) You have a Redis cache mixing session data (must persist) and page fragments (disposable). Pick and justify a memory policy.**
*Model:* Put TTLs on disposable fragments, none on sessions, and use `volatile-lru`/`volatile-lfu` so only fragments are evicted. Justify: protects sessions, reclaims from the disposable set. Caveat: ensure enough TTL'd keys exist or eviction can't free memory; otherwise split into two instances or use `allkeys-*` with sessions stored durably elsewhere.
*Probes:* (a) *Failure if no volatile keys?* — OOM on writes. (b) *Alternative architecture?* — separate session store; keep Redis a pure cache with `allkeys-lfu`. (c) *How to monitor?* — `evicted_keys`, keys-with-TTL count, hit ratio.

**Q7. (senior signal) Hit ratio is 92%; product wants 98%. Is that worth it?**
*Model:* Frame economically: the curve is concave; going 92→98 may require several × the RAM. Compute marginal value = `Δhit × miss_cost × req_rate` vs marginal RAM cost. Often the last points aren't worth it; better to cut miss cost (faster DB, read replicas) or fix pollution. Show the math, don't just add RAM.
*Probes:* (a) *What shapes the curve?* — workload skew (Zipf). (b) *Cheaper wins than RAM?* — scan-resistant policy, TTL tuning, two-tier near-cache. (c) *How measure miss cost?* — DB latency/$ per miss under load.

**Q8. (senior signal) Caffeine cache causing GC pauses — what do you do?**
*Model:* Diagnose old-gen pressure via GC logs vs cache size; bound by *bytes* (weigher) not count; consider off-heap for multi-GB; avoid `softValues` (GC-driven, bursty). Possibly tune GC (G1/ZGC) but the root cause is too many long-lived heap objects.
*Probes:* (a) *Why avoid `softValues`?* — eviction timing is opaque/bursty, GC decides. (b) *Off-heap tradeoffs?* — serialization cost, no GC accounting. (c) *Why is Caffeine read path lock-free?* — buffered access hints + amortized maintenance.

**Q9. Difference between eviction and expiration in metrics, and why care?**
*Model:* `evicted_keys` = memory-pressure removals; `expired_keys` = TTL removals. Separating them tells you whether you're undersized (evictions) or just churning TTLs (expirations). Conflating them hides the real problem.
*Probes:* (a) *Both rising — meaning?* — undersized *and* short TTLs; raise memory and/or TTLs. (b) *Only evictions rising?* — undersized or wrong policy/pollution. (c) *Only expirations?* — normal TTL churn.

**Q10. Memory fragmentation — what, how to detect, fix?**
*Model:* Internal (size-class rounding) + external (scattered free) waste; detect via `mem_fragmentation_ratio` (RSS/used); fix with `activedefrag`, `MEMORY PURGE`, and headroom; ratio < 1.0 means swapping.
*Probes:* (a) *Why can RSS > used by a lot?* — freed-but-not-returned pages + fragmentation. (b) *How does active defrag work?* — jemalloc-assisted live relocation, CPU-throttled. (c) *Risk of ignoring it?* — cgroup OOM kill though under `maxmemory`.

**Q11. Cache stampede on a hot key — prevent it?**
*Model:* Within a JVM, Caffeine `get(k, loader)` collapses concurrent loads to one. Use `refreshAfterWrite` to serve stale while reloading. Across nodes, use a distributed lock/single-flight, request coalescing, or probabilistic early expiration (XFetch).
*Probes:* (a) *refresh vs expire?* — refresh serves stale + async reload; expire blocks/misses. (b) *Distributed coordination?* — lock or leader-loads pattern. (c) *Tradeoff of serving stale?* — bounded staleness vs availability.

**Q12. Why is admission policy a different idea from eviction, and when does it matter?**
*Model:* Eviction picks what to remove; admission decides whether to even insert. It matters under pollution: a one-hit scan item shouldn't displace a proven hot item, so the admission filter rejects it. TinyLFU is the canonical example.
*Probes:* (a) *Risk of bad admission?* — rejecting a soon-to-be-hot key (mitigated by the window). (b) *How decide admit?* — compare candidate vs victim estimated frequency. (c) *Memory cost?* — tiny sketch.

---

## 11. Glossary

- **Admission policy** — rule deciding whether a new candidate is inserted at all (vs always inserting).
- **AOF (Append-Only File)** — Redis persistence log of write commands.
- **ARC (Adaptive Replacement Cache)** — adaptive policy balancing recency and frequency using ghost lists.
- **Arena (jemalloc)** — an independent allocation pool reducing lock contention.
- **Bloom filter** — probabilistic set membership structure (no false negatives, possible false positives); used as TinyLFU's doorkeeper.
- **cgroup** — Linux control group bounding a process's memory; can OOM-kill on RSS.
- **CLOCK** — low-overhead LRU approximation using a circular buffer + reference bits.
- **Count-Min Sketch** — probabilistic frequency estimator; estimate = min across hashed counters.
- **Eviction** — removing an entry due to capacity pressure.
- **Eviction pool (Redis)** — a small buffer of best eviction candidates across sampling rounds.
- **Expiry / expiration** — removing an entry because its TTL elapsed.
- **FIFO** — evict oldest-inserted entry.
- **Fragmentation (internal/external)** — wasted memory from size-class rounding / scattered free space.
- **Garbage collector (GC)** — JVM subsystem reclaiming unreachable objects; manages on-heap cache memory.
- **Ghost list** — list of recently *evicted* keys' identities (not values), used to adapt policy (ARC).
- **Hit / Miss / Hit ratio** — found / not-found lookups; fraction found.
- **`hz` (Redis)** — background cron frequency (Hz), default 10.
- **jemalloc** — memory allocator bundled with Redis, optimized for fragmentation and concurrency.
- **Lazy expiration** — delete an expired key on access.
- **Active expiration** — background sampling/deletion of expired keys.
- **LFU (Least Frequently Used)** — evict the least-accessed entry (with aging in practice).
- **LRU (Least Recently Used)** — evict the entry idle the longest.
- **`maxmemory` / `maxmemory-policy`** — Redis byte budget and eviction policy.
- **MVCC** — multi-version concurrency control (DB technique; mentioned as adjacent — keeps multiple versions for readers).
- **`noeviction`** — policy where writes error at the limit instead of evicting.
- **OOM** — Out Of Memory; JVM `OutOfMemoryError` / Linux OOM killer.
- **RSS (Resident Set Size)** — physical memory the OS sees a process using.
- **Scan resistance** — a cache's ability not to be flushed by one-pass scans.
- **SLRU (Segmented LRU)** — probationary + protected segments for scan resistance.
- **Sliding vs absolute TTL** — TTL reset on access vs fixed from creation.
- **Stampede / thundering herd** — many concurrent misses on the same key hitting the backend at once.
- **System of record** — authoritative data source behind the cache.
- **Timer wheel** — bucketed structure for efficient scheduling of many timeouts (Caffeine variable expiry).
- **TinyLFU / W-TinyLFU** — sketch-based admission policy; W- adds a recency window. Caffeine's policy.
- **TTL** — time-to-live; duration after which an entry expires.
- **`used_memory` (Redis)** — allocator-reported bytes Redis believes it's using (vs RSS).
- **volatile vs allkeys** — eviction eligible set = TTL'd keys vs all keys.
- **Weigher** — function returning an entry's size/weight for size-bounded caches.
- **Working set** — keys actively used in a time window.
- **Zipfian distribution** — skewed popularity model where few keys get most accesses.

---

## 12. Cheat-sheet & self-test

**Cheat-sheet (one screen):**

- **Eviction = space-driven; Expiry = time-driven.** Track `evicted_keys` and `expired_keys` separately.
- **Redis policies:** `noeviction`, `{allkeys,volatile}-{lru,lfu,random}`, `volatile-ttl`. Default `noeviction` — change it for caches.
- **Redis defaults:** `maxmemory-samples 5`, `lfu-log-factor 10`, `lfu-decay-time 1`, `hz 10`, `active-expire-effort 1`, LFU init counter **5**.
- **Approx LRU** = 24-bit clock + sampling + eviction pool. **Approx LFU** = 8-bit log counter + 16-bit decay time.
- **`maxmemory` checked vs `used_memory`, NOT RSS** → fragmentation can OOM the container. Watch `mem_fragmentation_ratio` (>1.5 bad fragmentation, <1.0 swapping).
- **Eviction runs on Redis's main thread** → latency spikes at the limit. Keep ~20–30% headroom; use `lazyfree-lazy-eviction`.
- **Caffeine = W-TinyLFU:** window LRU + Count-Min sketch admission + SLRU main; reads lock-free via buffers; eviction/expiry amortized in maintenance (not an instant timer).
- **Caffeine knobs:** `maximumSize` / `maximumWeight`+`weigher`, `expireAfterWrite/Access`, `refreshAfterWrite`, `recordStats`, `removalListener`.
- **Policy picks:** recency→LRU; skewed/scan→LFU/W-TinyLFU; mixed durable+cache→`volatile-*`; freshness→`volatile-ttl`/short TTL; DB→`noeviction`.
- **Stampede fix:** Caffeine `get(loader)` single-flight + `refreshAfterWrite`; distributed lock across nodes.
- **Sizing economics:** concave hit-ratio curve; add RAM while `Δhit × miss_cost × rate > RAM_cost`. Don't chase 99%.
- **Always:** bound the cache, set TTLs, pick a non-`noeviction` policy for caches, alert before the limit.

**Self-test (no answers):**
1. Trace exactly what Redis does, step by step, when a `SET` arrives and `used_memory == maxmemory` under `allkeys-lfu`.
2. Your container OOM-kills Redis while `INFO memory` shows `used_memory` well under `maxmemory`. List the likely cause and the commands you'd run to confirm and fix it.
3. Explain why W-TinyLFU resists cache pollution that would defeat plain LRU, naming each structure involved.
4. You must store both never-expire config and disposable rendered pages in one Redis. Choose a policy, list its failure mode, and state how you'd monitor for that failure.
5. Hit ratio is 90% and stakeholders want 97%. Lay out the economic analysis and three non-RAM levers you'd try first.
6. Describe how Caffeine keeps the read path lock-free yet still maintains good eviction quality, and what semantic surprise this creates for `expireAfterWrite`.
7. Distinguish lazy from active expiration; give a scenario where having only one of them causes a production problem.

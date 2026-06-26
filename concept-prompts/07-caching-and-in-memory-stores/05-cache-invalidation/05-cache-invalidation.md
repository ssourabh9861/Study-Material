# Cache Invalidation

> *"There are only two hard things in Computer Science: cache invalidation and naming things."* — Phil Karlton

This chapter is a complete, self-contained reference on **cache invalidation**: the discipline of keeping cached copies of data correct (or *correct enough*) relative to their source of truth, and of removing or refreshing those copies at the right time. We build from first principles, climb to deep internals and production tuning, and end with interview drills and a cheat-sheet.

---

## 1. Overview & where it fits

### 1.1 What a cache is (one paragraph for grounding)

A **cache** is a fast, usually smaller store that holds copies of data whose authoritative ("canonical") version lives somewhere slower or more expensive — a relational database, an object store, a remote microservice, or the result of an expensive computation. The slow authoritative store is called the **source of truth** (often abbreviated **SoT**) or the **system of record (SoR)**. You read from the cache to avoid paying the cost of going to the source of truth on every request. The moment you keep a *copy*, you have created the possibility that the copy and the original disagree. **Cache invalidation is the set of techniques for managing that disagreement.**

### 1.2 What cache invalidation *is*

Cache invalidation is the act of declaring a cached entry no longer valid so that subsequent reads either:

1. **Miss** and re-fetch the fresh value from the source of truth (a *passive* re-population), or
2. Are served a value that was **proactively refreshed** (an *active* re-population), or
3. Are served a clearly-marked stale value while a refresh happens in the background (*stale-while-revalidate*).

Invalidation is not one mechanism; it is a *family* of strategies (TTL expiry, explicit delete-on-write, versioned keys, event/CDC-driven purge, generational/tag-based bulk purge), each with different correctness, latency, and cost tradeoffs.

### 1.3 The problem it solves

The core problem is **staleness**: a cached value that no longer matches the source of truth because the source changed after the value was cached. Concretely:

- A user updates their profile email. The old email is cached. Without invalidation, other parts of the system keep reading the old email — possibly for the cache's entire lifetime.
- A product's price drops. The cached page still shows the old price. You overcharge or undercharge.
- A feature flag is flipped off. The cached "on" value keeps a broken feature live.
- A permission is revoked. The cached "allowed" decision keeps a fired employee logged in.

Some of these are cosmetic; some are correctness-critical; one (the permissions case) is a **security** problem. Invalidation is how you bound or eliminate staleness.

### 1.4 When you reach for it

You need an explicit invalidation strategy whenever **all three** of these hold:

1. You cache data that **can change** at the source (i.e., it is not immutable).
2. The cost of serving a stale value is **non-trivial** (correctness, money, security, or user trust).
3. The natural expiry of the cache (its TTL) is **longer than your tolerance for staleness**.

If your cached data is immutable (e.g., the rendered thumbnail of a specific image version, content addressed by a hash), you barely need invalidation — you need *eviction* (removing entries to free space), which is a different problem (covered in §11 glossary). If you can tolerate the data being stale for as long as the TTL, a TTL alone *is* your invalidation strategy.

### 1.5 The one-paragraph mental model

Think of every cached entry as a **lease on a fact** with three attributes: *what fact it claims*, *when that claim was made*, and *how long the claim is trusted*. Invalidation is the protocol by which the system breaks a lease early (because the underlying fact changed) or lets it expire naturally. The two fundamental ways to break a lease are **pull** (the reader checks whether the fact is still fresh — TTL, conditional requests, version checks) and **push** (the writer tells the cache the fact changed — explicit delete, event/CDC notification). Almost every real system is a *blend* of pull and push, chosen per-key based on its **staleness budget** — the maximum amount of time you are willing to serve a wrong answer for that particular piece of data.

### 1.6 Why it's "one of the two hard problems"

The Karlton quip is funny but the difficulty is real and specific:

- **It is a distributed-consistency problem in disguise.** The cache and the source of truth are two replicas of the same data. Keeping two replicas consistent under concurrent writes is exactly the problem distributed databases spend entire research literatures on (consensus, linearizability, MVCC). A cache is "just" a poorly-controlled replica with weaker guarantees, so all the hard race conditions reappear — but now you also lack the database's machinery to handle them.
- **The failure is silent and time-shifted.** A stale read does not throw an exception. It returns a plausible-looking wrong answer, often long after the write that caused it, in a different request, possibly on a different machine. There is no stack trace pointing back to the cause.
- **Correctness vs. the entire point of caching are in tension.** The safest invalidation (re-fetch on every read) gives you a 0% hit rate — i.e., no cache. Every gain in freshness costs you hit rate, latency, or load on the source of truth. You are always trading along that frontier.
- **Partial failure.** In a distributed cache (multiple nodes, multiple regions, multiple layers — browser, CDN, app, database buffer pool), an invalidation message can reach some caches and not others. Now different users see different versions of reality.

---

## 2. Foundations from first principles

We build the vocabulary and the core ideas from zero. Every term is defined the first time it appears.

### 2.1 The basic objects

- **Key**: the identifier under which a value is stored (e.g., `user:42:profile`).
- **Value**: the cached data (e.g., the serialized profile JSON).
- **Entry**: the key→value pair plus metadata (insert time, TTL, tags, version).
- **Source of truth (SoT)**: the authoritative store the value was derived from.
- **Hit**: a read that finds a usable entry in the cache.
- **Miss**: a read that does not (entry absent, expired, or invalidated) and must go to the SoT.
- **Hit ratio**: hits / (hits + misses). The headline metric of a cache. A 95% hit ratio means 1 in 20 reads pays the slow path.
- **Population / fill**: writing a value into the cache, usually after a miss.

### 2.2 Staleness, freshness, and the staleness budget

- **Fresh**: the cached value equals the current SoT value.
- **Stale**: the cached value no longer equals the current SoT value.
- **Staleness window**: the interval between the moment the SoT changes and the moment the cache stops serving the old value. This is the quantity invalidation tries to bound.
- **Staleness budget** (sometimes *freshness SLA* or *bounded staleness*): the **maximum staleness window you are willing to accept** for a given piece of data, expressed as a duration (or as 0 for "must be strongly consistent"). This is a *product/business* decision dressed as an engineering one. Examples:
  - Bank account balance shown in an app: budget often *seconds* or *0* (strong consistency required for some operations).
  - A user's display name in a comment thread: budget *minutes* is fine.
  - "Number of likes": budget can be *tens of seconds*; everyone tolerates approximate counters.
  - A product price at checkout: budget effectively *0* — you must charge the real price (often solved by *not caching* the authoritative price at checkout even if you cache it on the listing page).

The staleness budget is the single most useful concept in this chapter. **You pick your invalidation strategy per-key by matching its cost/complexity to the staleness budget of that key.** A 24-hour TTL is a perfectly correct invalidation strategy for data whose staleness budget is 24 hours.

### 2.3 Eviction vs. invalidation vs. expiry — three different things people conflate

These are routinely confused; keep them distinct:

| Term | Trigger | Reason | Correctness role |
|---|---|---|---|
| **Eviction** | Cache is full | Make room (capacity pressure) | None — eviction never *intends* correctness; it just removes something, possibly fresh. Governed by policies like LRU/LFU. |
| **Expiry (TTL)** | A timer elapses | The entry's allotted lifetime ended | A *coarse* invalidation: bounds staleness to the TTL. |
| **Invalidation** | A write at the SoT (or an explicit command) | The underlying fact changed | The *targeted* mechanism: removes/refreshes exactly the affected entry. |

> **Beginner note — LRU / LFU.** *LRU (Least Recently Used)* evicts the entry that hasn't been read for the longest time. *LFU (Least Frequently Used)* evicts the entry read the fewest times. These are *eviction* (capacity) policies, not invalidation strategies. A common bug is to rely on LRU to "eventually get rid of stale data" — it won't reliably, because a hot stale key is *frequently* used and thus *protected* from eviction.

### 2.4 Pull vs. push: the two fundamental axes

Everything in invalidation is a point on (or a blend of) two axes:

- **Pull (validation on read):** the reader is responsible for checking freshness. Mechanisms: TTL (trust until timer), conditional requests (`If-Modified-Since`, `ETag`), version comparison, lease tokens. *Pros:* simple, the cache doesn't need to know about every writer, robust to lost messages (worst case you serve up to TTL of staleness). *Cons:* you either re-check often (cost) or accept staleness up to the TTL.
- **Push (invalidation on write):** the writer is responsible for telling the cache. Mechanisms: explicit `DELETE key` on update, publish an event, CDC stream, broadcast purge. *Pros:* near-zero staleness window. *Cons:* the writer must know which keys are affected; messages can be lost, duplicated, or reordered; needs delivery infrastructure.

> **Beginner note — ETag and conditional requests (HTTP).** An *ETag* is an opaque token a server returns with a response that identifies that exact version of the content (often a hash). On the next request the client sends `If-None-Match: <etag>`. If the content is unchanged the server replies `304 Not Modified` with an empty body — cheap revalidation. `If-Modified-Since` does the same using a timestamp instead of a token. These are *pull-based revalidation* primitives baked into HTTP.

### 2.5 The classic caching patterns (so we know *where* invalidation hooks in)

Invalidation strategy is tightly coupled to how the cache is written. The standard patterns:

- **Cache-aside (a.k.a. lazy loading / look-aside):** the application code manages the cache. On read: check cache; on miss, load from SoT, then put into cache. On write: write the SoT, then **invalidate** (or update) the cache entry. This is the most common pattern and the one where you, the engineer, own invalidation explicitly. The famous race (§3.6) lives here.
- **Read-through:** the cache itself knows how to load from the SoT on a miss (you configure a loader). Invalidation is still typically your job on writes.
- **Write-through:** every write goes *through* the cache, which synchronously writes both the cache and the SoT before returning. The cache is never stale relative to the write path (but other writers bypassing the cache can still make it stale). No separate invalidation needed *for that write path* — the cache is updated in lockstep.
- **Write-behind (write-back):** writes go to the cache and are flushed to the SoT asynchronously later. Fast writes, but the SoT is now the stale one (and you risk data loss if the cache dies before flush). Invalidation semantics invert.
- **Write-around:** writes go straight to the SoT, bypassing the cache; the cache is populated only on subsequent reads. Combined with explicit invalidation on write to avoid the old value lingering.

> **Beginner note — "write-through invalidation."** The prompt mentions this; it means: on a write, instead of leaving the cache untouched, you *push* an update or a delete through the same write path that hits the SoT, so the cache is corrected as part of the write. In practice "write-through invalidation" most often means "delete (or overwrite) the cache key as part of committing the write."

### 2.6 The two write-time choices: update-in-place vs. delete (invalidate)

When the SoT changes, you can either:

- **Update the cache in place** (write the new value into the cache), or
- **Delete the cache entry** (invalidate; let the next read re-populate).

**Delete is usually safer than update.** Why: if two concurrent writes both *update* the cache, they can interleave such that the *older* value wins and sticks (a lost-update on the cache). If instead each writer *deletes*, the worst case is an extra miss; whatever read repopulates will read the committed SoT value, which is by definition the latest committed state. **Rule of thumb: invalidate (delete) rather than update, unless the value is expensive to recompute and you have ordering guarantees.** (Facebook's memcache architecture and the famous "Scaling Memcache at Facebook" NSDI 2013 paper articulate exactly this preference, with leases to fix the residual races — see §7.)

### 2.7 Consistency models, briefly (because invalidation *is* a consistency choice)

> **Beginner note — consistency models.** A *consistency model* defines what reads are allowed to return given the writes that happened. **Strong consistency / linearizability**: every read sees the most recent committed write (as if there were a single copy). **Eventual consistency**: if writes stop, all replicas eventually converge to the same value, but in the meantime reads may see stale data. **Bounded staleness**: reads may be stale, but never by more than a fixed time/version bound. **Read-your-writes**: a client always sees its own prior writes (even if others' writes lag).

A cache with TTL gives you **bounded staleness** (bounded by the TTL). A cache with perfect push invalidation *approaches* strong consistency on the read path but never quite reaches it because of the write/invalidate race and message loss. **You almost never get true linearizability through a cache** — if you truly need it, you read from the SoT (or use a cache protocol with leases + versioning). Naming your target consistency model up front prevents most invalidation design mistakes.

---

## 3. How it works internally

This is the heart of the chapter. We trace the actual control and data flow of each major mechanism, the lifecycle of an entry, and the canonical race condition.

### 3.1 Lifecycle of a cache entry (state machine)

An entry moves through these states:

```
        put()                         read after invalidate / TTL
  ┌──────────────┐    invalidate()   ┌──────────────┐
  │   ABSENT     │ ────────────────► │   ABSENT     │
  └──────┬───────┘ ◄──────────────── └──────────────┘
         │ fill (after miss)   evict (capacity)
         ▼
  ┌──────────────┐    TTL elapses    ┌──────────────┐
  │   FRESH      │ ────────────────► │   EXPIRED    │
  │ (valid,      │                   │ (present but │
  │  not expired)│ ◄──── refresh ─── │  not served) │
  └──────┬───────┘                   └──────┬───────┘
         │ SoT changes (no push)            │ next read → miss → fill
         ▼                                   ▼
  ┌──────────────┐                   ┌──────────────┐
  │ STALE-FRESH  │   push invalidate │   ABSENT     │
  │ (served but  │ ────────────────► │              │
  │  wrong!)     │                   └──────────────┘
  └──────────────┘
```

Key insight: the dangerous state is **STALE-FRESH** — the entry is *within its TTL* (so the cache happily serves it) but the SoT has already changed. The job of *push* invalidation is to shorten the time spent in STALE-FRESH to near zero. The job of *pull* (TTL) is to guarantee you leave STALE-FRESH within the TTL even if every push fails.

### 3.2 TTL expiry — internal mechanics

A TTL (time-to-live) is a per-entry deadline. There are two implementation styles, and the difference matters operationally:

1. **Passive / lazy expiration:** the entry carries an expiry timestamp. The cache does *not* actively delete it at that moment. Instead, on the next *access*, it checks `now > expiry`; if so, it treats the read as a miss and (often) deletes the entry then. This is how **Redis** primarily works for its keyspace, supplemented by active sampling (below). Memory is only reclaimed when the key is touched or sampled.
2. **Active expiration:** a background task periodically scans for expired entries and removes them. **Redis** runs an active cycle ~10 times/second (configurable via `hz`, default 10) that samples 20 keys with TTLs from the expires dictionary; if more than 25% were expired, it repeats, bounded by a CPU time budget (~25% of a cycle). This bounds memory waste from never-accessed expired keys without scanning the whole keyspace.

> **Beginner note — Redis `hz`.** `hz` controls how many times per second Redis runs its internal "serverCron" housekeeping (expiry sampling, client timeout checks, etc.). Default 10; `dynamic-hz` (default `yes`) lets Redis raise it when there are many clients. Higher `hz` = more responsive expiry/cleanup, slightly more CPU.

**TTL caveats that cause real bugs:**
- TTL is measured from *insert* (or last write), not from when the SoT last changed. So an entry can be 0–TTL seconds stale at any instant — your *expected* staleness from TTL alone is ~TTL/2 and *worst case* is TTL.
- **Thundering herd / cache stampede:** if a popular key expires, *all* concurrent readers miss simultaneously and slam the SoT. Mitigations: §6.1.
- **TTL jitter:** if you set the same TTL on many keys created together (e.g., on deploy), they all expire at the same instant → synchronized stampede. Fix: add randomized jitter, e.g., `ttl = base ± rand(0, base*0.1)`.

### 3.3 Explicit delete-on-write (push) — internal flow

The canonical cache-aside write path:

```
1. Application begins a write (e.g., UPDATE users SET email=? WHERE id=42).
2. Commit the write to the SoT (database).        ← SoT is now new
3. DELETE the cache key (user:42).                ← cache now ABSENT
4. Return success to caller.
5. (Later) some reader misses on user:42, loads from SoT (new value), fills cache.
```

The *order* of steps 2 and 3 is the whole ballgame (see §3.6). The principle: **write the SoT first, then invalidate the cache.** If you invalidate first and then write, a concurrent reader can re-populate the cache with the *old* value in the gap before your write commits, and that old value can then live until TTL.

### 3.4 Versioned keys & generational keys — internal flow

Instead of mutating or deleting an entry, you **change the key** so old readers naturally stop finding old data and new readers compute a new key.

- **Versioned (single-object) keys:** embed a version/etag in the key, e.g. `user:42:v7`. On write, bump the version (e.g., a counter or a hash of the row). New reads compute `user:42:v8` → miss → fill. Old `...:v7` entries are now orphaned and die by TTL/eviction. **No explicit delete needed** — invalidation is implicit because nobody asks for the old key anymore. The challenge: every reader must know the current version, so you store the version somewhere cheap and fast (often a tiny, frequently-read cache key, or derive it from the row's own version column / `updated_at`).
- **Generational keys (namespace/group versioning):** prefix many keys with a shared generation token, e.g. `gen:product-catalog:5:product:42`. To invalidate the *entire group* in O(1), bump `gen:product-catalog` from 5 to 6. Every key under generation 5 is instantly orphaned. This is the classic trick for **bulk invalidation without enumerating keys** — invaluable when you can't list all affected keys cheaply (most cache servers make `KEYS *`-style scans expensive/dangerous).

Internal flow for generational/group invalidation:

```
Write that affects the whole catalog:
1. INCR gen:product-catalog            (5 → 6)   ← O(1), atomic
2. Done. All "gen:...:5:..." keys are now unreachable; they expire by TTL.

Read:
1. g = GET gen:product-catalog          (= 6)
2. v = GET gen:product-catalog:6:product:42
3. on miss → load from SoT → SET that key
```

The tradeoff: orphaned entries waste memory until they expire/evict, so you keep TTLs reasonable. The win: invalidation is O(1) and atomic regardless of how many entries are affected.

### 3.5 Event/CDC-driven invalidation — internal flow

> **Beginner note — CDC (Change Data Capture).** CDC is a technique that *reads the database's own write log* (e.g., MySQL's binary log "binlog", PostgreSQL's WAL via logical decoding, MongoDB's oplog) and emits a stream of "row X changed" events. Tools: **Debezium** (the de-facto open-source CDC platform, runs on Kafka Connect), AWS DMS, Maxwell, Oracle GoldenGate. The point: you capture *every committed change*, in commit order, *without* asking application code to remember to publish events.

> **Beginner note — WAL / binlog / oplog.** The *Write-Ahead Log* (WAL, Postgres) / *binary log* (binlog, MySQL) / *oplog* (MongoDB) is the durable, ordered record of every change the database commits, used for crash recovery and replication. CDC piggybacks on this same log, so it sees exactly what was committed, in order.

> **Beginner note — Kafka.** *Apache Kafka* is a distributed, durable, ordered log (a "topic" is an append-only sequence split into "partitions"). Producers append; consumers read at their own pace and track an "offset" (their position). It's the common transport for CDC events because it preserves order within a partition and retains events so a crashed consumer can resume.

The CDC-driven invalidation flow:

```
1. App commits write to DB (no special code needed).
2. DB appends the change to its WAL/binlog.
3. Debezium tails the log, emits {table, primary key, op, before, after} to Kafka.
4. An "invalidator" service consumes Kafka, maps the changed row → affected cache keys
   (e.g., users.id=42 → user:42, and maybe org:7:members), and issues DELETE to the cache(s).
5. Subsequent reads miss → repopulate from DB (now consistent).
```

Why this is powerful:
- **No dual-write problem.** Application code does *one* thing — write the DB. Invalidation is derived from the committed log, so you can't "forget to invalidate" or have the app crash between writing the DB and publishing the event. (The *dual-write problem*: when code must update two systems — DB and event bus — a crash between them leaves them inconsistent. CDC eliminates it by deriving the event *from* the DB commit.)
- **Ordered & exactly the committed truth.** Events arrive in commit order per key (within a Kafka partition keyed by primary key), so you invalidate in the right order.
- **Decoupled.** New caches/consumers can subscribe without touching the write path.

Costs: operational complexity (Kafka + Debezium + connectors), end-to-end latency (typically tens to hundreds of ms — log read + transport + consume), and the mapping problem (a single row change may invalidate *many* derived/aggregated cache keys — you must encode that fan-out logic).

### 3.6 The DB-write / cache-update race (the central correctness problem)

This is the race the prompt specifically calls out. Setup: cache-aside, with delete-on-write. The danger is a reader and a writer interleaving so the cache ends up holding the **old** value *after* the write committed — and then it stays old until TTL (potentially forever for a hot key).

**Race A — invalidate-then-write (WRONG ordering):**
```
Reader R wants user:42 (cache empty). Writer W updates email.
t1  R: cache miss on user:42
t2  R: read DB → gets OLD email
t3  W: DELETE user:42        (nothing there, no-op)
t4  W: UPDATE DB → NEW email (commits)
t5  R: SET user:42 = OLD email   ← cache now holds OLD value, lives to TTL. BUG.
```

**Race B — even write-SoT-first can lose (the subtle one):**
```
Same cache-aside, write DB first then delete:
t1  R: cache miss
t2  R: read DB → OLD (R is slow / paused here)
t3  W: UPDATE DB → NEW (commit)
t4  W: DELETE user:42 (empty, no-op)
t5  R: SET user:42 = OLD   ← BUG again: R's stale read overwrites after the delete.
```

Race B shows that **ordering the delete after the DB write is necessary but NOT sufficient.** A reader that read the DB *before* the write but *fills the cache after* the delete can still re-introduce the stale value. This is a real, observed bug at scale (it is exactly what Facebook's leases were invented to fix).

**How to actually avoid serving stale forever:**

1. **TTL as a backstop (always).** Even if a race plants a stale value, a TTL guarantees it dies within TTL. This converts "stale forever" into "stale for at most TTL" — the single most important safety net. *Never run a writable cache with infinite TTL and only push invalidation.*

2. **Leases (Facebook memcache approach).** On a miss, the cache hands the reader a short-lived **lease token** and remembers it. When the reader tries to `SET`, it must present the token. If a `DELETE`/invalidate happened in the meantime, the cache *invalidates the outstanding lease*, so the late `SET` is **rejected** — the stale fill can't land. The cache may also serve a recent value or tell other readers "wait" to throttle the stampede. This closes Race B precisely.

3. **Delayed double-delete (a.k.a. "double-delete" / "delete twice"):** delete the key *before* the DB write *and again after a short delay* (e.g., 500 ms) following the commit. The second delayed delete cleans up any stale value a racing reader planted during the window. Pattern (common in Chinese-language engineering blogs as "延迟双删"):
   ```
   DELETE user:42
   UPDATE DB ... (commit)
   sleep(Δ)            // Δ > a typical slow read's duration
   DELETE user:42      // mops up any stale fill that happened during the write
   ```
   It's a probabilistic mitigation, not a proof; you still keep a TTL. Δ must exceed the worst realistic read+fill latency, which is hard to know — hence "probabilistic."

4. **Version/CAS on fill.** Store a version with the value. A reader fills with `(value, version_it_read)`. On `SET`, the cache (or a Lua script in Redis) accepts the fill *only if* its version ≥ the currently cached version (or only if no newer invalidation marker exists). A stale reader's lower version loses. This is a *compare-and-set* (CAS) on the cache.

   > **Beginner note — CAS (compare-and-set/swap).** An atomic operation: "set X to B *only if* X currently equals A." It's the fundamental primitive for avoiding lost updates without locks. Redis supports it via `WATCH`/`MULTI`/`EXEC` (optimistic transactions) or Lua scripts (atomic execution).

5. **Read from SoT for read-your-writes.** For the specific user who just wrote, bypass the cache (or pin to the primary DB) for a short window so *they* always see their own change, regardless of the global race. Cheap and removes the most user-visible symptom.

6. **Single-writer / serialize per key.** Route all writes (and the consequent invalidations) for a given key through one ordered channel (e.g., a Kafka partition keyed by the entity id), so deletes and fills can't be reordered chaotically.

**Bottom line:** ordering (write SoT, then invalidate) + a finite TTL backstop handles the vast majority of cases. For high-contention hot keys where even TTL-bounded staleness is unacceptable, add leases or version-CAS.

### 3.7 Invalidation across multiple cache layers and regions

Real systems cache the same fact at several layers:

```
Browser cache → CDN edge → API gateway cache → app in-process (L1) → shared Redis (L2) → DB buffer pool
```

Each layer is a separate replica with its own invalidation semantics. **Invalidation must be propagated layer by layer**, and the *outer* (closer to the user) the layer, the *harder* it is to push-invalidate:

- **DB buffer pool / page cache:** the DB manages this; it's always consistent with committed data (not your problem).
- **Shared distributed cache (L2, e.g., Redis):** you control it; delete the key directly. Single authoritative cache → easy.
- **In-process per-instance caches (L1, e.g., Caffeine in each app JVM):** *every instance has its own copy.* A delete on instance A does nothing to instance B's copy. You must **broadcast** the invalidation to all instances (via Redis pub/sub, a Kafka topic every instance subscribes to, or a gossip mechanism). This is the **fan-out invalidation** problem. Keep L1 TTLs short (seconds) precisely because broadcast is lossy and you want a tight backstop.
- **CDN / edge:** you invalidate via the CDN's purge API (CloudFront `CreateInvalidation`, Fastly `purge`, Cloudflare purge). Purges are *eventually* applied across hundreds of POPs (points of presence), typically seconds; some CDNs charge per invalidation path. Prefer **versioned URLs** (cache-busting, e.g., `/app.4f9a.js`) over purging for static assets — a new URL is an instant, free, race-free invalidation.
- **Browser:** you cannot push. You rely on `Cache-Control` (max-age, must-revalidate), `ETag` revalidation, or changing the URL. The browser will hold a stale copy up to its `max-age` no matter what — so set conservative `max-age` for mutable resources, or version the URL.

> **Beginner note — CDN / POP.** A *CDN (Content Delivery Network)* is a fleet of caching servers spread geographically. A *POP (Point of Presence)* is one such location. Content cached at the POP nearest the user is served locally for low latency. Invalidating a CDN means telling every relevant POP to drop its copy.

**Multi-region distributed caches** add the further wrinkle that an invalidation in region us-east must reach region eu-west. Approaches:
- **Per-region caches + per-region invalidation derived from a globally-replicated change stream** (e.g., Kafka MirrorMaker / Debezium fanned out to each region's invalidator). Each region invalidates its own cache from the same ordered log.
- **Accept bounded cross-region staleness** equal to replication lag + invalidation propagation, and keep TTLs ≥ that bound.
- **Region-local writes with versioned keys** so a stale region simply computes an old key and misses, rather than serving a known-stale value.

The cross-region staleness window is essentially `DB_replication_lag + invalidation_transport_latency`. Measure both; set TTLs and product expectations to that floor.

### 3.8 Tagging / grouped invalidation — internal flow

Often a single SoT change should invalidate *many* related cache entries that you can't enumerate cheaply (e.g., "all cached pages that include product 42's price"). Two implementations:

1. **Tag index (reverse index):** maintain `tag:product:42 → {set of cache keys that depend on product 42}`. On read/fill, register the key under each tag it depends on. On invalidate-by-tag, read the set and delete each member, then clear the set. In Redis this is a SET per tag; deletion is `SMEMBERS` + `DEL` (or `UNLINK`) + `DEL tag`. Cost: extra writes to maintain the index; the set can grow large; you must garbage-collect dead members.

2. **Generational/namespace tag (no enumeration):** as in §3.4, store `tag:product:42:gen = N`. Each cached key that depends on the tag stores the gen it was built with; on read you compare the stored gen to the current gen and treat a mismatch as a miss. Or you fold the gen into the key. Invalidate-by-tag = `INCR tag:product:42:gen` — O(1), no enumeration, but stale entries linger until TTL/eviction.

Many frameworks expose tagging as a first-class feature:
- **Symfony Cache / Doctrine:** `TagAwareAdapter`, `$item->tag(['product-42'])`, `$cache->invalidateTags(['product-42'])`.
- **ASP.NET Core / OutputCache:** tag-based eviction.
- **Spring Cache abstraction (Java):** caches are grouped by *cache name* (a coarse tag); `@CacheEvict(cacheNames="products", key="#id")` or `@CacheEvict(cacheNames="products", allEntries=true)` to nuke a whole logical group. Spring does not ship fine-grained arbitrary-tag invalidation out of the box; you implement it via generational keys or a tag index on top of Redis.
- **Varnish (HTTP cache):** `ban` and `purge` with surrogate keys / `xkey` (the `vmod_xkey` module) — `obj.http.x-key` lets you tag objects and ban by tag.
- **Fastly:** **Surrogate Keys** — attach `Surrogate-Key: product-42 catalog` to responses and `POST /service/.../purge/product-42` to purge everything tagged, across all POPs, typically in ~150 ms globally. This is the gold-standard production tag invalidation.

---

## 4. The complete toolkit

Below: the concrete APIs, commands, config flags, and tools you actually use, by domain. Defaults are flagged where known; version/vendor-specific items are marked.

### 4.1 Redis — invalidation-relevant commands

| Command | Purpose | Key parameters / notes |
|---|---|---|
| `DEL key [key…]` | Delete (invalidate) one or more keys, synchronously frees memory | Blocking for large values |
| `UNLINK key [key…]` | Like `DEL` but reclaims memory in a background thread | Preferred for large values / many keys (Redis ≥ 4.0) |
| `EXPIRE key seconds` | Set TTL on existing key | `NX/XX/GT/LT` flags (Redis ≥ 7.0) to set only under conditions |
| `PEXPIRE`, `EXPIREAT`, `PEXPIREAT` | TTL in ms / at absolute time | `EXPIREAT` for "expire at midnight" semantics |
| `TTL key` / `PTTL key` | Read remaining TTL | Returns -1 (no TTL), -2 (no key) |
| `PERSIST key` | Remove TTL (make permanent) | Dangerous for mutable data |
| `SET key val EX s` / `PX ms` | Set with TTL atomically | Also `KEEPTTL` (≥6.0) to preserve existing TTL on overwrite; `NX`/`XX` |
| `SET … NX` | Set only if absent | Used to build locks for stampede control |
| `INCR / INCRBY` | Atomic counter — power generational/version keys | O(1) bulk invalidation via gen bump |
| `WATCH/MULTI/EXEC/DISCARD` | Optimistic transaction (CAS) | `WATCH key` aborts EXEC if key changed — version-guarded fills |
| `EVAL`/`EVALSHA` (Lua) | Atomic multi-step logic | Implement compare-version-then-set, lease checks atomically |
| `FLUSHDB` / `FLUSHALL` | Nuke a whole DB / all DBs | Almost never in prod; `ASYNC` option |
| `SCAN cursor MATCH pat COUNT n` | Iterate keys without blocking | Use instead of `KEYS` (which blocks O(N)) |
| `KEYS pattern` | Match keys by glob | **Avoid in prod** — O(N) blocking scan |
| `OBJECT IDLETIME key` | Seconds since last access | Diagnostics |

**Client-side caching / invalidation push (Redis ≥ 6, "tracking"):**

| Feature | Purpose | Notes |
|---|---|---|
| `CLIENT TRACKING ON [REDIRECT id] [BCAST] [PREFIX p] [OPTIN/OPTOUT] [NOLOOP]` | Server *pushes* invalidation messages to clients that cached keys (RESP3 push, or via a pub/sub redirect connection) | Enables a *server-driven* L1 invalidation: when a tracked key changes, Redis notifies the client to drop its local copy |
| `BCAST` mode | Track by key *prefix* instead of per-key, lower server memory | Notifies on any key matching prefix; more false-positive invalidations |
| `NOLOOP` | Don't notify the client that itself made the change | Avoids self-invalidation churn |
| Invalidation channel `__redis__:invalidation` | The pub/sub channel that carries invalidation messages | Clients subscribe to learn which keys to drop |

**Keyspace notifications (event-driven hooks):**

| Config / channel | Purpose | Notes |
|---|---|---|
| `notify-keyspace-events` (default `""` = off) | Emit pub/sub events on key changes/expiry | Flags: `K`=keyspace, `E`=keyevent, `g`=generic, `x`=expired, `e`=evicted, `A`=all. Set e.g. `KEA` |
| Channel `__keyevent@<db>__:expired` | Fires when a key expires | Caveat: fires on *passive/active* expiry, not exactly at TTL instant; **not reliable as a precise timer** |
| Channel `__keyevent@<db>__:del` | Fires on delete | Useful to cascade invalidations |

> **Caveat:** keyspace notifications are *fire-and-forget pub/sub* — if no subscriber is connected, the message is lost. Don't use them as a guaranteed invalidation transport for critical data; use a durable stream (Kafka) for that.

**Memory/eviction config that interacts with invalidation:**

| Config | Default | Purpose |
|---|---|---|
| `maxmemory` | `0` (no limit) | Memory cap before eviction kicks in |
| `maxmemory-policy` | `noeviction` | What to evict when full: `noeviction`, `allkeys-lru`, `allkeys-lfu`, `volatile-lru`, `volatile-lfu`, `volatile-ttl`, `allkeys-random`, `volatile-random`. `volatile-*` only evict keys *with* a TTL |
| `maxmemory-samples` | `5` | Sample size for approximate LRU/LFU |
| `hz` | `10` | serverCron frequency (drives active expiry) |
| `dynamic-hz` | `yes` | Auto-scale `hz` under load |
| `lazyfree-lazy-expire` / `-eviction` / `-server-del` | `no` (historically) | Free memory in background thread on expire/evict/del |

> **Beginner note — `volatile-ttl` policy.** When memory is full, evict the key with the *shortest remaining TTL* among keys that have one. A way to bias eviction toward soon-to-expire entries.

### 4.2 Caffeine (Java in-process cache) — invalidation-relevant API

> **Beginner note — Caffeine.** Caffeine is the high-performance Java in-process caching library (successor to Guava Cache), using the W-TinyLFU eviction policy. It's the de-facto L1 cache in JVM apps and the default backing for Spring's `CaffeineCacheManager`.

| API | Purpose | Notes |
|---|---|---|
| `cache.invalidate(key)` | Remove one entry | The explicit single-key invalidation |
| `cache.invalidateAll(keys)` | Remove many | |
| `cache.invalidateAll()` | Clear everything | Coarse |
| `Caffeine.expireAfterWrite(Duration)` | TTL from last write | Most common TTL knob |
| `Caffeine.expireAfterAccess(Duration)` | TTL from last read | Idle expiry |
| `Caffeine.expireAfter(Expiry)` | Per-entry custom TTL | Variable TTL by value |
| `Caffeine.refreshAfterWrite(Duration)` | Async refresh on read after N — serves stale during reload (stale-while-revalidate) | Needs a `LoadingCache` with a loader; only refreshes on access |
| `Caffeine.maximumSize(n)` / `maximumWeight(w)` | Capacity-based eviction | Eviction, not invalidation |
| `LoadingCache.refresh(key)` | Force async reload of one key | |
| `cache.policy().refreshAfterWrite()` etc. | Inspect/adjust policies at runtime | |
| `Caffeine.removalListener(...)` / `evictionListener(...)` | Hook on removal/eviction (for cascades, metrics) | `evictionListener` is synchronous on eviction only |

**Key distinction:** `expireAfterWrite` vs `refreshAfterWrite`. `expireAfter*` *removes* the entry (next read blocks to reload); `refreshAfterWrite` keeps serving the *old* value and reloads *asynchronously*, so reads never block but may see one-cycle-stale data (this is stale-while-revalidate). They are often combined: `expireAfterWrite(10m).refreshAfterWrite(2m)` → refresh attempts every 2 min, hard expiry at 10 min if refresh keeps failing.

### 4.3 Spring Cache abstraction (Java) — annotations

| Annotation / element | Purpose | Key attributes |
|---|---|---|
| `@Cacheable(cacheNames, key, condition, unless, sync)` | Read-through: cache method result | `sync=true` serializes concurrent misses (stampede control) |
| `@CachePut(cacheNames, key)` | Always run method *and* update cache (write-through-ish) | Use for update-in-place |
| `@CacheEvict(cacheNames, key, allEntries, beforeInvocation)` | **Invalidate** | `allEntries=true` clears whole cache name; `beforeInvocation=true` evicts even if method throws |
| `@Caching(...)` | Combine multiple cache annotations | E.g., evict from two caches at once |
| `@CacheConfig(cacheNames=...)` | Class-level defaults | |
| `CacheManager` / `Cache.evict(key)` / `Cache.clear()` | Programmatic invalidation | |

> **Gotcha (Spring/AOP):** `@CacheEvict` works via proxies, so a *self-invocation* (a method in the same bean calling another `@CacheEvict` method directly) **bypasses the proxy and does nothing.** Call through the injected bean or use `AopContext.currentProxy()`. This is a top source of "my eviction silently doesn't work" bugs.

### 4.4 HTTP / CDN invalidation toolkit

| Mechanism | Layer | Purpose |
|---|---|---|
| `Cache-Control: max-age=N, s-maxage=N` | Browser/CDN | TTL; `s-maxage` overrides for shared (CDN) caches |
| `Cache-Control: no-cache` | Browser/CDN | Must revalidate before use (NOT "don't store") |
| `Cache-Control: no-store` | Browser/CDN | Never cache at all |
| `Cache-Control: stale-while-revalidate=N` | Browser/CDN | Serve stale up to N s while refreshing in background |
| `Cache-Control: stale-if-error=N` | Browser/CDN | Serve stale on origin error |
| `ETag` + `If-None-Match` | All HTTP | Conditional revalidation → 304 |
| `Last-Modified` + `If-Modified-Since` | All HTTP | Timestamp-based revalidation |
| `Surrogate-Key` (Fastly) / `Cache-Tag` (Cloudflare) | CDN | Tag responses for grouped purge |
| `Vary: Header` | Browser/CDN | Cache separate copies per header value (prevents serving wrong variant) |
| Versioned/fingerprinted URLs (`app.4f9a.js`) | All | Cache-busting; instant race-free invalidation |
| CloudFront `CreateInvalidation` (paths) | CDN | Purge by path; first 1000 paths/month free, then charged |
| Fastly `purge` / `purge_all` / surrogate-key purge | CDN | Instant (~150ms) global purge |
| Cloudflare `purge_cache` (by URL, tag, prefix, or everything) | CDN | Tags/prefixes are Enterprise-tier |
| Varnish `PURGE` (single object) / `BAN` (regex over objects) / `xkey` (tagged) | Reverse proxy | `ban` is lazy (checked on next fetch) |

> **Beginner note — `no-cache` vs `no-store`.** A classic trap: `no-cache` does **not** mean "don't cache." It means "you may store it, but you must revalidate with the origin (via ETag) before using it." `no-store` is the one that means "never keep a copy." Mixing these up causes either unexpected staleness or unexpected origin load.

### 4.5 CDC / streaming invalidation tooling

| Tool | Role |
|---|---|
| **Debezium** | CDC connectors for MySQL/Postgres/Mongo/SQL Server/Oracle → Kafka; emits row-change events |
| **Kafka / Kafka Connect** | Durable ordered transport; partition by primary key for per-key ordering |
| **Maxwell / Canal** | Lighter MySQL binlog → JSON streamers (Canal is Alibaba's) |
| **AWS DMS** | Managed CDC |
| **Apache Flink / Kafka Streams** | Transform/aggregate change events → derived invalidation keys |
| Custom "invalidator" consumer | Maps changed rows → affected cache keys → issues `DEL`/purge |

### 4.6 JVM-specific distributed caches with built-in invalidation

| System | Invalidation feature |
|---|---|
| **Hazelcast** | Distributed `IMap`; `Near Cache` with *invalidation events* pushed from members to clients; `map.evict(key)`, `map.remove(key)`, per-entry TTL/`maxIdle` |
| **Apache Ignite** | Distributed cache; `cache.clear(key)`, near-cache invalidation, `ExpiryPolicy` |
| **Ehcache 3** | Tiered (heap/offheap/disk); `cache.remove(key)`, `ExpiryPolicy`, clustered via Terracotta with invalidation |
| **Infinispan** | Has an explicit **INVALIDATION clustering mode**: data isn't replicated, but a *remove* is broadcast so other nodes drop their local copy |
| **JCache (JSR-107)** | Standard API: `cache.remove(key)`, `cache.removeAll(keys)`, `ExpiryPolicy` (`CreatedExpiryPolicy`, `AccessedExpiryPolicy`, `ModifiedExpiryPolicy`) |

> **Beginner note — Near Cache.** A *near cache* is a small L1 copy kept *inside the client/app process*, in front of a larger distributed cache. It's fast but per-process, so it needs *invalidation events* from the cluster to stay correct — exactly the fan-out problem of §3.7, solved by the product pushing invalidation messages.

---

## 5. Code examples by use case

Idiomatic, runnable/adaptable Java unless noted. Non-obvious lines are commented.

### 5.1 Cache-aside with correct ordering + TTL backstop (Redis via Lettuce/Jedis-style pseudocode in Java)

```java
// Use case: user profile read/write with delete-on-write and a TTL safety net.
public class UserProfileService {
    private final RedisCommands<String, String> redis; // sync Redis client (Lettuce)
    private final UserRepository db;
    private static final Duration TTL = Duration.ofMinutes(10); // backstop: max staleness if a race plants stale data

    public User read(long id) {
        String key = "user:" + id;
        String cached = redis.get(key);
        if (cached != null) {
            return deserialize(cached);                  // HIT
        }
        User u = db.findById(id);                         // MISS → source of truth
        // SET with EX so even a racing stale fill cannot live forever.
        redis.set(key, serialize(u),
                  SetArgs.Builder.ex(TTL.getSeconds()).nx()); // NX: don't clobber a concurrent fresher fill
        return u;
    }

    public void updateEmail(long id, String email) {
        db.updateEmail(id, email);                        // 1) WRITE SoT FIRST (commit)
        redis.del("user:" + id);                          // 2) THEN invalidate cache
        // Optional hardening: delayed second delete to mop up racing stale fills.
        scheduler.schedule(() -> redis.del("user:" + id), 500, TimeUnit.MILLISECONDS);
    }
}
```

Why it's correct *enough*: SoT-first ordering removes the obvious race; `NX` on fill avoids clobbering a fresher value; the TTL caps any residual staleness at 10 minutes; the delayed delete shrinks the residual window further.

### 5.2 Version-guarded fill with a Lua CAS (closes Race B without leases)

```java
// Use case: hot key where TTL-bounded staleness is too loose. Store (value, version);
// only accept a fill whose version is >= the version already cached.
private static final String FILL_IF_NEWER = """
    local cur = redis.call('HGET', KEYS[1], 'ver')
    if (cur == false) or (tonumber(ARGV[2]) >= tonumber(cur)) then
        redis.call('HSET', KEYS[1], 'val', ARGV[1], 'ver', ARGV[2])
        redis.call('EXPIRE', KEYS[1], ARGV[3])
        return 1
    end
    return 0
    """; // atomic: compare version, set only if not older

public User read(long id) {
    String key = "user:" + id;
    Map<String,String> h = redis.hgetall(key);
    if (h != null && h.containsKey("val")) return deserialize(h.get("val")); // HIT

    // MISS: read row AND its version (e.g., updated_at epoch or a version column)
    Versioned<User> v = db.findWithVersion(id);
    redis.eval(FILL_IF_NEWER, ScriptOutputType.INTEGER,
        new String[]{key},
        serialize(v.value), Long.toString(v.version), "600"); // 10m TTL
    return v.value;
}

public void updateEmail(long id, String email) {
    long newVer = db.updateEmailReturningVersion(id, email); // commit, get new version
    // Push the new value with its version; older racing fills will be rejected by the Lua CAS.
    redis.eval(FILL_IF_NEWER, ScriptOutputType.INTEGER,
        new String[]{"user:"+id}, serialize(db.findById(id)), Long.toString(newVer), "600");
}
```

A stale reader (who read version 7) issuing a late fill is rejected because the cache already holds version 8. This is the practical, lease-free way to defeat Race B.

### 5.3 Generational / namespace invalidation for O(1) bulk purge

```java
// Use case: invalidate the ENTIRE product catalog cache atomically on a bulk price import,
// without enumerating keys.
public class CatalogCache {
    private static final String GEN = "gen:catalog";

    private long generation() {
        String g = redis.get(GEN);
        if (g == null) { redis.set(GEN, "1", SetArgs.Builder.nx()); return 1; }
        return Long.parseLong(g);
    }

    public Product read(long productId) {
        long gen = generation();
        String key = "catalog:" + gen + ":product:" + productId; // gen baked into key
        String cached = redis.get(key);
        if (cached != null) return deserialize(cached);
        Product p = db.findProduct(productId);
        redis.set(key, serialize(p), SetArgs.Builder.ex(3600)); // TTL reaps orphaned old-gen keys
        return p;
    }

    public void bulkPriceImportCompleted() {
        redis.incr(GEN);   // O(1) atomic: every "catalog:<old>:..." key is now unreachable
    }
}
```

After `INCR`, every reader computes a new key prefix and misses → refills from the SoT. Old-generation keys are never read again and die by TTL. No `KEYS`, no scan, no enumeration.

### 5.4 Tag-based invalidation with a reverse index

```java
// Use case: a rendered "deal page" depends on product 42, 99, and the homepage banner.
// Invalidating product 42 must purge every page that referenced it.
public void cacheRenderedPage(String pageKey, String html, Set<String> tags) {
    redis.set(pageKey, html, SetArgs.Builder.ex(1800));
    for (String tag : tags) {
        redis.sadd("tag:" + tag, pageKey);  // reverse index: tag → keys depending on it
        redis.expire("tag:" + tag, 7200);   // bound index lifetime
    }
}

public void invalidateTag(String tag) {
    String tagKey = "tag:" + tag;
    // Iterate members in batches; UNLINK to free memory off-thread.
    ScanArgs args = ScanArgs.Builder.limit(500);
    KeyScanCursor<String> cur = null;
    do {
        var sc = redis.sscan(tagKey, cur == null ? ScanCursor.INITIAL : cur, args);
        if (!sc.getValues().isEmpty())
            redis.unlink(sc.getValues().toArray(new String[0])); // purge the dependent pages
        cur = sc;
    } while (!cur.isFinished());
    redis.unlink(tagKey); // drop the index set itself
}
```

For very large tag sets prefer the generational-tag variant (§3.8 option 2) to avoid the enumeration cost.

### 5.5 Two-layer cache (Caffeine L1 + Redis L2) with broadcast invalidation

```java
// Use case: per-instance Caffeine in front of shared Redis; a write must invalidate
// the local copy on EVERY app instance, not just the writer's.
public class TwoTierCache {
    private final Cache<String,String> l1 =
        Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(30)) // short L1 TTL: backstop for missed broadcasts
            .build();
    private final RedisCommands<String,String> l2;
    private static final String INVALIDATION_CHANNEL = "cache-invalidations";

    public TwoTierCache(StatefulRedisPubSubConnection<String,String> pubsub, ...) {
        pubsub.addListener(new RedisPubSubAdapter<>() {
            @Override public void message(String ch, String key) {
                l1.invalidate(key); // every instance drops its local copy on broadcast
            }
        });
        pubsub.sync().subscribe(INVALIDATION_CHANNEL);
    }

    public String read(String key) {
        String v = l1.getIfPresent(key);
        if (v != null) return v;                 // L1 hit
        v = l2.get(key);
        if (v != null) { l1.put(key, v); return v; } // L2 hit → fill L1
        v = loadFromDb(key);
        l2.set(key, v, SetArgs.Builder.ex(600));
        l1.put(key, v);
        return v;
    }

    public void invalidate(String key) {
        l2.del(key);                                  // 1) drop shared copy
        l1.invalidate(key);                           // 2) drop my local copy
        l2.publish(INVALIDATION_CHANNEL, key);        // 3) tell every other instance to drop theirs
    }
}
```

The short L1 `expireAfterWrite(30s)` is deliberate: pub/sub is fire-and-forget, so if a broadcast is lost (a subscriber was disconnected), the worst-case L1 staleness is still bounded to 30 seconds. **Layered caches always pair push (broadcast) with a short pull (TTL) backstop.**

### 5.6 Stale-while-revalidate with Caffeine `refreshAfterWrite`

```java
// Use case: an expensive-to-compute dashboard aggregate. Never make a user wait for a recompute;
// serve the slightly-stale value and refresh in the background.
LoadingCache<String, Dashboard> cache = Caffeine.newBuilder()
    .refreshAfterWrite(Duration.ofMinutes(2))  // after 2m, next read triggers ASYNC reload, serves old value now
    .expireAfterWrite(Duration.ofMinutes(30))  // hard cap: if refresh keeps failing, force a blocking reload at 30m
    .buildAsync(key -> computeDashboard(key))  // async loader runs off the calling thread
    .synchronous();

Dashboard d = cache.get("team-7"); // returns instantly even when a refresh is in flight
```

Staleness budget here is "up to ~2 minutes is fine, we never want a slow read." `expireAfterWrite` guarantees you don't serve *arbitrarily* old data if the source is down.

### 5.7 CDC-driven invalidator (Kafka consumer)

```java
// Use case: derive invalidation from the DB's committed changes (no dual-write).
// Debezium publishes row changes to topic "dbserver1.app.users".
@KafkaListener(topics = "dbserver1.app.users", groupId = "cache-invalidator")
public void onUserChange(ConsumerRecord<String, String> rec) {
    JsonNode payload = MAPPER.readTree(rec.value()).path("payload");
    String op = payload.path("op").asText();      // "c"=create, "u"=update, "d"=delete, "r"=snapshot
    JsonNode after = payload.path("after");
    JsonNode before = payload.path("before");
    long id = (op.equals("d") ? before : after).path("id").asLong();

    redis.del("user:" + id);                       // primary entity
    // Fan-out: derived/aggregate keys that depend on this row
    long orgId = (op.equals("d") ? before : after).path("org_id").asLong();
    redis.del("org:" + orgId + ":member-list");    // invalidate the org's cached member list
    // Kafka offset commit (handled by container) gives at-least-once; deletes are idempotent so re-delivery is safe.
}
```

Note the key property: **deletes are idempotent**, so at-least-once delivery (which Kafka gives by default) is safe — re-processing the same change just deletes an already-absent key. This is a major reason delete-based invalidation pairs beautifully with streaming.

### 5.8 HTTP/CDN: versioned asset + tag purge (config + curl)

```http
# Static asset: cache hard, but the URL itself encodes the version → no purge ever needed.
GET /static/app.4f9a2c.js
Cache-Control: public, max-age=31536000, immutable

# Dynamic page tagged for grouped purge (Fastly):
GET /deals/summer
Surrogate-Key: page-deals product-42 product-99
Cache-Control: public, max-age=300
```

```bash
# Purge everything tagged product-42 across all Fastly POPs (global, ~150ms):
curl -X POST -H "Fastly-Key: $TOKEN" \
  https://api.fastly.com/service/$SERVICE_ID/purge/product-42

# CloudFront path invalidation (slower, eventually consistent, first 1000 paths/mo free):
aws cloudfront create-invalidation --distribution-id E123 --paths "/deals/*"
```

`immutable` + fingerprinted URL is the *ideal* invalidation: changing the content changes the URL, so there is nothing to invalidate and no race.

### 5.9 Redis 6 server-assisted client-side caching (RESP3 tracking)

```java
// Use case: app keeps an in-process copy AND lets Redis tell it when to drop it,
// avoiding both per-write broadcasts and short blind TTLs.
StatefulRedisConnection<String,String> conn = client.connect();
conn.sync().clientTracking(TrackingArgs.Builder.enabled().bcast().prefixes("user:"));
// In BCAST mode Redis pushes invalidation messages for any changed key under "user:".
conn.addListener(message -> {
    if (message.getType() == PushType.INVALIDATE) {
        for (String key : message.getKeys()) localCache.invalidate(key); // drop local copy on server push
    }
});
```

Here the *server* drives L1 invalidation — closer to a coherent cache protocol, at the cost of Redis tracking memory.

---

## 6. Implementation concerns & best practices

### 6.1 Performance — cache stampede / thundering herd

When a hot key is invalidated/expires, many concurrent readers miss at once and overload the SoT. Mitigations:

- **Request coalescing / single-flight:** the first miss acquires a lock (`SET lock NX EX 5`); others wait and read the freshly filled value. Spring: `@Cacheable(sync=true)`. Caffeine `LoadingCache` coalesces per-key automatically. Go's `singleflight` is the canonical name.
- **Probabilistic early expiration (XFetch):** refresh *before* expiry with a probability that grows as TTL approaches, so one lucky request refreshes while the value is still valid and others keep hitting. Formula (Vattani et al.): recompute if `now - delta*beta*ln(rand()) >= expiry`, where `delta` = last recompute cost.
- **TTL jitter:** randomize TTLs so keys created together don't expire in lockstep.
- **Stale-while-revalidate:** serve the old value while one background task refreshes (Caffeine `refreshAfterWrite`, HTTP `stale-while-revalidate`).
- **Negative caching:** cache "not found" briefly to stop repeated misses for absent keys (guards against cache-penetration attacks, see §9).

### 6.2 Correctness / concurrency

- **Write SoT first, then invalidate.** Never the reverse.
- **Prefer delete over update.** Avoids cache lost-updates.
- **Keep a finite TTL even with push invalidation** — it's your only defense against lost messages and undetected races.
- **Make invalidation idempotent** (deletes are) so at-least-once delivery is safe.
- **Serialize per-key writes** (e.g., partition by id) to avoid reordering deletes/fills.
- **Use CAS/versioning or leases for hot, contended keys** where TTL staleness is unacceptable.
- **Beware self-invocation in Spring** (`@CacheEvict` via proxy).

### 6.3 Memory

- Generational/tag-namespace invalidation leaves **orphaned entries** that consume memory until TTL/eviction — size your `maxmemory` and TTLs accordingly, and set a `maxmemory-policy` that can evict them (`allkeys-lru`/`allkeys-lfu`, or `volatile-*` if all your keys carry TTLs).
- Tag reverse-index sets can grow unbounded; cap with TTLs and GC dead members.
- Use `UNLINK`/lazy-free for large-value or bulk invalidations so the event loop isn't blocked.

### 6.4 Security

- **Permission/authorization caches are the most dangerous to get wrong.** A stale "allow" after a revoke is a security hole. For these, either don't cache, use very short TTLs, or push-invalidate immediately on revoke (and accept fail-closed on uncertainty).
- **Session/token revocation:** a logged-out or compromised token cached as valid lets an attacker in. Maintain a *revocation list* (deny-list) checked on every request, or keep auth token TTLs short.
- **Cache poisoning (HTTP):** an attacker crafts a request whose response gets cached and served to others. Defend with correct `Vary`, keyed-by-everything-relevant cache keys, and not caching responses that depend on un-keyed headers.
- **Cache key injection:** ensure user input that flows into a cache key is normalized/escaped so it can't collide with or overwrite another tenant's entry.

### 6.5 Observability

Instrument and alert on:
- **Hit ratio** (sudden drop = mass invalidation, stampede, or a key-naming bug).
- **Staleness / replication lag** (CDC end-to-end lag, cross-region lag).
- **Invalidation throughput & failures** (delete error rate, pub/sub subscriber count, Kafka consumer lag of the invalidator).
- **Stampede signals** (origin QPS spikes correlated with TTL boundaries; lock-wait counts).
- **Eviction rate** (high eviction with low hit ratio = undersized cache).
- **Per-key staleness audits:** periodically sample cache vs. SoT for critical keys and emit a "stale entries detected" metric — the only way to *catch* silent staleness.

> **Beginner note — consumer lag.** For a Kafka consumer, *lag* is how far behind the latest message it is (in offsets). A growing invalidator lag means invalidations are being applied late → growing staleness window. It's the canary for CDC-based invalidation health.

### 6.6 Cost

- CDN purges can be billed per path (CloudFront) — prefer versioned URLs and tag purges over path-by-path invalidation.
- CDC infra (Kafka + Debezium) has real operational cost; justify it by scale/freshness needs.
- Over-aggressive invalidation tanks hit ratio → more SoT load → more DB cost. Match invalidation granularity to the staleness budget; don't `allEntries=true` when one key would do.

### 6.7 Testing

- **Race tests:** deterministically interleave reader/writer (inject pauses) to assert no stale value survives past TTL; assert the version-CAS rejects stale fills.
- **Idempotency tests:** deliver the same invalidation twice; assert no harm.
- **Fan-out tests:** assert a single row change invalidates *all* derived keys (member lists, aggregates, rendered pages).
- **Chaos:** drop pub/sub messages / pause the invalidator; assert staleness is still bounded by TTL.
- **Property test the staleness budget:** with TTL T and push enabled, observed staleness ≤ T always; with push disabled, ≤ T as well.

### 6.8 Production hardening

- Always set a TTL backstop.
- Add TTL jitter on bulk fills/deploys.
- Make all invalidation idempotent and retried.
- Keep L1 TTLs short (seconds) because broadcast is lossy.
- Have a **manual purge runbook** (single key, by tag, whole namespace via gen-bump) and *test it*.
- Guard `FLUSHALL`/`@CacheEvict(allEntries=true)` behind review — a fat-fingered global flush triggers a cluster-wide stampede.
- For critical reads, support a **bypass-cache flag** (read from SoT) for incident response.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Infinite TTL + push-only invalidation | One lost message = stale forever | Always add a finite TTL |
| Invalidate (delete) *before* committing the write | Race A: reader refills with old value | Write SoT first, then invalidate |
| Update-in-place under concurrency | Cache lost-update | Delete instead of update |
| Relying on LRU to "expire" stale data | Hot stale keys are protected from eviction | Explicit TTL/invalidation |
| `KEYS pattern` to find keys to delete | O(N) blocking, freezes Redis | `SCAN`, tags, or generational keys |
| Identical TTLs on bulk-loaded keys | Synchronized stampede | Add jitter |
| `@CacheEvict` via self-invocation (Spring) | Proxy bypassed, eviction silently no-ops | Call through the proxy/bean |
| Caching auth decisions with long TTL | Security: stale "allow" after revoke | Short TTL / push purge / deny-list |
| Treating keyspace notifications as guaranteed | Fire-and-forget pub/sub loses messages | Use durable stream (Kafka) for critical invalidation |
| Caching the price at checkout | Charges the wrong amount | Read authoritative price from SoT at checkout |

---

## 7. Advanced topics & deep internals

### 7.1 Facebook memcache leases (the canonical hot-key solution)

From "Scaling Memcache at Facebook" (NSDI 2013): two problems plagued cache-aside at scale — **stale sets** (Race B) and **thundering herds**. The fix is **leases**:

- On a miss, memcached returns a 64-bit **lease token** to the client and remembers it for that key. The client must include the token when it later `SET`s the value.
- If the key is **deleted/invalidated** between the lease grant and the `SET`, memcached *invalidates the token*, so the stale `SET` is **rejected** — the stale value can't land (kills Race B).
- memcached hands out **at most one lease per key per ~10 s**. Other concurrent readers either get told to *retry shortly* (and find the value the first reader fills) or are served the *last known value* — killing the thundering herd.
- A short grace window lets readers see slightly-stale-but-recent data instead of stampeding the DB.

Leases are the most precise general solution to the write/fill race short of reading from the SoT. Few off-the-shelf caches expose them; you approximate with the version-CAS of §5.2.

### 7.2 Bounded staleness as a tunable, and read-your-writes

- You can engineer a **read-your-writes** guarantee cheaply: after a write, set a short per-user "sticky" marker (e.g., `user:42:writepin` with a 5 s TTL); while present, that user's reads bypass cache (or read the DB primary, not a replica). Removes the most user-visible staleness symptom (a user not seeing their own edit) without strengthening global consistency.
- **Monotonic reads** (never see time go backwards): pin a client/session to one cache region/replica, or carry a min-version the read must satisfy.

### 7.3 The dual-write problem and the transactional outbox

If you *must* publish invalidation events from application code (no CDC), the **dual-write problem** bites: a crash between "commit DB" and "publish event" leaves the cache un-invalidated. The standard fix is the **transactional outbox**:

> **Beginner note — Transactional Outbox.** Within the *same DB transaction* as your data write, insert a row into an `outbox` table describing the change. A separate relay (often Debezium on the outbox table) reads committed outbox rows and publishes them. Because the data write and the outbox insert commit atomically, you can never publish an invalidation for a change that didn't commit, nor miss one that did.

This is CDC applied to an intent table, and it's the bridge between "app publishes events" and "log-derived events."

### 7.4 Cross-region invalidation ordering and conflict

In active-active multi-region setups, two regions may write the same key concurrently. Invalidation alone doesn't resolve *which write wins* — that's the SoT's job (last-write-wins by timestamp, vector clocks/CRDTs, or a single global primary). The cache invalidation just needs to ensure each region eventually drops its copy and refetches the *converged* value. Practically: drive invalidation per-region from a per-region change stream and accept staleness = max(replication lag, invalidation lag).

> **Beginner note — CRDT / vector clock.** A *vector clock* tags each write with per-replica counters so you can tell whether two writes are concurrent or one happened-before the other. A *CRDT (Conflict-free Replicated Data Type)* is a data structure designed so concurrent updates merge deterministically without a coordinator (e.g., a counter that sums per-replica increments). Used by SoTs to converge; the cache just refetches the converged result.

### 7.5 Negative caching and cache penetration

Caching "miss"/"not found" for a short TTL prevents an attacker (or a buggy client) from hammering the DB with queries for keys that don't exist (**cache penetration**). Bound the negative TTL tightly (seconds) so a newly-created key isn't masked for long, and consider a **Bloom filter** in front.

> **Beginner note — Bloom filter.** A compact probabilistic set that answers "definitely not present" or "possibly present" with zero false negatives and tunable false positives. Put one in front of the cache/DB: if the Bloom filter says "definitely not present," you skip the DB entirely — cheap penetration defense.

### 7.6 Cache avalanche

Distinct from a single-key stampede: an **avalanche** is when *many* keys expire (or the whole cache restarts/flushes) at once, dumping full traffic on the SoT and potentially toppling it. Defenses: TTL jitter, layered caches (L1 absorbs while L2 refills), circuit breakers / load shedding to the SoT, warm-up/pre-population on cache restart, and never `FLUSHALL` a hot cache during peak.

### 7.7 Redis active vs. lazy expiry interaction with replicas

On a **Redis primary**, expiry is driven by lazy + active sampling. **Replicas do NOT independently expire keys** — they wait for the primary to send an explicit `DEL`/`UNLINK` via the replication stream (since Redis 3.2, replicas will *logically* hide an expired key from reads but not delete it until told). This means a replica can briefly hold a key the primary considers expired; reads against replicas can be momentarily stale by the expiry-propagation delay. Relevant if you read from replicas for scale.

### 7.8 Tuning knobs summary (Redis)

- `hz` / `dynamic-hz`: responsiveness of active expiry vs. CPU.
- `maxmemory-policy`: ensure orphaned/expired keys *can* be evicted under pressure.
- `lazyfree-*`: avoid event-loop stalls on big invalidations.
- `notify-keyspace-events`: enable only the classes you consume; `KEA` is broad and chatty.
- RESP3 + `CLIENT TRACKING`: server-assisted L1 coherence; watch tracking-table memory.

### 7.9 Probabilistic early recompute (XFetch) detail

The recompute condition `time() - delta * beta * ln(rand()) >= expiry` makes the probability of an early refresh rise as you approach `expiry`; `delta` is the measured recompute cost (expensive items refresh earlier, smoothing the load), `beta` ≥ 0 tunes aggressiveness (1.0 default). It probabilistically guarantees *one* request recomputes while the value is still valid, so others never miss — eliminating stampedes without locks. (Vattani, Chierichetti, Lowenstein, "Optimal Probabilistic Cache Stampede Prevention," VLDB 2015.)

---

## 8. Tradeoffs & decision frameworks

### 8.1 Strategy comparison

| Strategy | Staleness window | Write-path cost | Read-path cost | Bulk invalidation | Infra needed | Best for |
|---|---|---|---|---|---|---|
| **TTL only** | 0–TTL | none | none | TTL only | none | Tolerant data; simplest |
| **Delete-on-write (cache-aside)** | ~0 (+ race risk; TTL backstop) | 1 delete | none | hard (enumerate) | none | General mutable entities |
| **Write-through update** | ~0 on that path | sync cache+SoT write | none | n/a | cache-managed write | Read-heavy, single write path |
| **Versioned keys** | ~0 (old key orphaned) | bump version | read version | per-object | tiny version store | Per-object, race-resistant |
| **Generational keys** | ~0 for group | INCR (O(1)) | read gen | **O(1) group** | counter | Bulk group invalidation |
| **Tag reverse-index** | ~0 | maintain index | none | enumerate set | set storage | Arbitrary dependency graphs |
| **Event/CDC-driven** | tens–hundreds ms | none (derived) | none | mapped fan-out | Kafka+Debezium | No-dual-write, decoupled, large scale |
| **Leases** | ~0, race-proof | delete invalidates lease | lease round-trip | per-key | cache support | Hot contended keys |
| **Stale-while-revalidate** | up to refresh interval (intentional) | none | none (async refresh) | per-key | loader | Expensive recompute, latency-critical |

### 8.2 Pull vs. push decision

| | Pull (TTL/revalidate) | Push (delete/event) |
|---|---|---|
| Staleness | up to TTL | near zero |
| Writer must know affected keys | no | yes |
| Robust to lost messages | yes | no (needs TTL backstop) |
| Infra | none | delivery channel |
| Use when | tolerant data, simple | tight staleness budget |

**Real systems use both:** push for freshness, pull (TTL) as the safety net. The TTL backstop is non-negotiable.

### 8.3 "Use when / avoid when"

- **TTL only — use when** staleness budget ≥ a convenient TTL and writes are rare/uncorrelated with reads; **avoid when** budget is tight or a stale read is costly/security-sensitive.
- **Delete-on-write — use when** you control the write path and want simple, near-fresh reads; **avoid when** writes are external (other services/jobs write the SoT) — you'll miss invalidations (then prefer CDC).
- **Generational keys — use when** you must invalidate a large group cheaply; **avoid when** memory for orphans is scarce and TTLs are long.
- **CDC-driven — use when** multiple writers/services touch the SoT, you need decoupling, or you've hit the dual-write problem; **avoid when** scale doesn't justify Kafka/Debezium ops, or sub-50 ms invalidation is required (CDC adds latency).
- **Leases / version-CAS — use when** a hot key's TTL-bounded staleness is still unacceptable; **avoid when** the extra complexity isn't warranted by contention.
- **Write-through update — use when** the value is expensive to recompute, reads vastly outnumber writes, and there's a single write path; **avoid when** multiple writers can race (prefer delete).

### 8.4 Granularity decision

Invalidate at the **finest granularity that's cheap to compute**: single key > tag/group > whole namespace > FLUSHALL. Coarser invalidation is simpler but tanks hit ratio and risks avalanche. Use generational keys to make *coarse* invalidation cheap while keeping it scoped (one group, not everything).

---

## 9. Failure modes & debugging

### 9.1 Failure catalog

| Symptom | Likely cause | Diagnose with | Fix |
|---|---|---|---|
| Users see stale data indefinitely | Infinite TTL + lost push; or Race B planting stale fill | Compare cache vs SoT (`GET key` vs DB); check `TTL key` = -1 | Add finite TTL; SoT-first ordering; version-CAS/leases |
| A user doesn't see their own edit | Read from replica/cache lagging | Check replica lag, read path | Read-your-writes pin to primary briefly |
| Origin/DB QPS spikes at TTL boundaries | Cache stampede on hot key | Correlate origin QPS with expiry; lock-wait metrics | `sync` load, single-flight, XFetch, jitter |
| Huge DB load after cache restart/flush | Cache avalanche | Cache hit ratio crater + DB CPU spike | TTL jitter, warm-up, L1 buffer, load shed |
| Eviction high, hit ratio low | Undersized cache | `INFO stats` `evicted_keys`, `keyspace_misses` | Grow cache or shrink working set |
| Some instances stale, others fresh | L1 broadcast missed | Compare per-instance L1; pub/sub subscriber count | Short L1 TTL; durable invalidation channel; RESP3 tracking |
| Invalidations applied late | CDC consumer lag | Kafka consumer lag of invalidator; Debezium connector lag | Scale consumers; partition by key |
| Eviction freezes Redis | `DEL` of huge value / `KEYS` scan | `SLOWLOG GET`, `LATENCY DOCTOR` | `UNLINK`, `SCAN`, lazy-free |
| Stale "allow" after permission revoke | Auth cache TTL too long | Audit auth cache TTL; revocation path | Short TTL / push purge / deny-list |
| Wrong variant served (e.g., mobile gets desktop) | Missing/incorrect `Vary` or cache key | Inspect cache key composition | Add `Vary` / include the discriminator in key |

### 9.2 Diagnostic commands & tools

```bash
# Redis: is this key cached, and how stale can it be?
redis-cli GET user:42
redis-cli TTL user:42        # -1 = no TTL (danger), -2 = absent
redis-cli OBJECT IDLETIME user:42

# Cache health
redis-cli INFO stats | egrep 'keyspace_hits|keyspace_misses|evicted_keys|expired_keys'
redis-cli INFO memory | egrep 'used_memory|maxmemory|maxmemory_policy'

# Find slow / blocking ops (e.g., big DEL, KEYS)
redis-cli SLOWLOG GET 10
redis-cli LATENCY DOCTOR

# Watch invalidation events live (requires notify-keyspace-events e.g. KEA)
redis-cli PSUBSCRIBE '__keyevent@0__:*'

# Kafka invalidator lag (CDC health)
kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group cache-invalidator
```

For Caffeine: enable `recordStats()` and expose `cache.stats()` (hitRate, evictionCount, loadFailureCount) via Micrometer. For CDNs: check the response `Age` header (how long the edge has held it) and the cache-status header (`X-Cache: HIT/MISS`, Fastly `X-Served-By`/`X-Cache`).

### 9.3 Real-world incident patterns

- **The synchronized-TTL avalanche:** a deploy warms 1M keys with identical 1-hour TTLs; exactly one hour later they all expire together and the DB falls over. Fix: jittered TTLs. (A recurring postmortem across many companies.)
- **The lost-broadcast split brain:** an app instance's Redis pub/sub connection silently dropped; it kept serving its stale Caffeine copy for hours while peers were fresh. Fix: short L1 TTL backstop + connection health alerting. The L1 TTL turns "hours" into "seconds."
- **The Race-B stale-set at Facebook:** the documented motivation for memcache leases — a slow reader's stale fill landing after a delete, persisting for the key's lifetime. Fix: leases.
- **The permission-cache security incident:** a fired employee retained access because the authorization decision was cached with a long TTL and the revoke didn't push-purge. Fix: short TTL + explicit purge on revoke + deny-list checked per request.
- **The CDN purge that wasn't free:** a service path-invalidated thousands of URLs per deploy on CloudFront and got a surprise bill. Fix: versioned/fingerprinted URLs (free, instant, race-free).

---

## 10. Interview drill

**Q1. Why is cache invalidation considered one of the two hard problems?**
*Model answer:* Because it's a distributed-consistency problem (two replicas — cache and SoT — under concurrent writes) where failures are silent (a stale read returns a plausible wrong answer with no error), time-shifted (the symptom appears far from the cause), partial (different caches/regions diverge), and fundamentally in tension with the purpose of caching (perfect freshness = re-fetch every read = no cache). You're always trading freshness against hit ratio and SoT load.
- *Follow-up: Give a concrete data type and its staleness budget.* A bank balance at transfer time → budget ~0 (read SoT); a "likes" counter → tens of seconds is fine; a display name → minutes.
- *Follow-up: When is invalidation trivial?* For immutable, content-addressed data (hash in the key) — you never invalidate, you only evict for capacity.

**Q2. Walk me through the DB-write/cache race and how you'd prevent serving stale forever.**
*Model answer:* In cache-aside, a slow reader can read the old SoT value, then *after* the writer commits and deletes the key, fill the cache with the old value — which then lives until TTL (Race B). Prevention layers: (1) write SoT first, then invalidate (necessary, not sufficient); (2) a finite TTL backstop converts "stale forever" into "stale ≤ TTL"; (3) version-CAS on fill (accept a fill only if its version ≥ cached); (4) leases (reject a stale SET whose lease was invalidated by a delete); (5) delayed double-delete to mop up; (6) read-your-writes pin for the writer.
- *Follow-up: Why is delete preferred over update?* Concurrent updates can interleave so an older value wins and sticks (cache lost-update); concurrent deletes at worst cause an extra miss that repopulates from the committed SoT.
- *Follow-up: Why isn't write-SoT-first sufficient alone?* Because a reader that read the SoT *before* the commit can still fill *after* the delete (Race B). You need TTL/version/leases on top.

**Q3. Compare TTL, explicit delete-on-write, and CDC-driven invalidation.**
*Model answer:* TTL: zero write-path work, staleness 0–TTL, no infra — good for tolerant data. Delete-on-write: near-zero staleness, requires the writer to know affected keys and control the write path, race-prone (needs TTL backstop). CDC: derives invalidation from the DB's committed log so it can't be forgotten and avoids the dual-write problem, handles multiple/external writers, decoupled — but adds Kafka+Debezium ops and tens-to-hundreds-of-ms latency. Real systems combine push (delete/CDC) with a TTL backstop.
- *Follow-up: When does delete-on-write break?* When another service or batch job writes the SoT directly — your app never sees the write to invalidate. Switch to CDC.
- *Follow-up: What's the dual-write problem and how does CDC solve it?* Updating DB and event bus as two separate operations risks a crash between them. CDC derives the event from the committed DB log, so the event exists iff the write committed.

**Q4. How do you invalidate a large group of related keys cheaply?**
*Model answer:* Generational/namespace keys: bake a generation counter into the key prefix and bump it with one atomic `INCR` to orphan the whole group in O(1) — old keys are never read again and die by TTL. Alternative: a tag reverse-index (`tag → {keys}`) you enumerate and delete, at the cost of maintaining and GC'ing the index. Avoid `KEYS pattern`/full scans in prod.
- *Follow-up: Downside of generational keys?* Orphaned entries waste memory until TTL/eviction; ensure `maxmemory-policy` can evict them.
- *Follow-up: How do CDNs do this?* Surrogate/cache-tags (Fastly Surrogate-Key, Cloudflare Cache-Tag, Varnish xkey) — tag responses and purge by tag globally.

**Q5. You cache in Caffeine (per-instance) in front of Redis. A write must invalidate every instance. How?**
*Model answer:* Each instance has its own L1 copy, so a local delete doesn't reach peers — that's the fan-out problem. Broadcast the invalidation: publish the key to a Redis pub/sub channel (or a Kafka topic) that every instance subscribes to and calls `l1.invalidate(key)`. Because pub/sub is fire-and-forget (lossy), keep the L1 TTL short (seconds) as a backstop. Even better: Redis 6 `CLIENT TRACKING` lets the server push invalidations to clients.
- *Follow-up: Why short L1 TTL?* If a broadcast is missed (subscriber disconnected), the TTL bounds worst-case L1 staleness to seconds instead of hours.
- *Follow-up: Difference between expireAfterWrite and refreshAfterWrite?* The former removes the entry (next read blocks to reload); the latter serves the old value and reloads asynchronously (stale-while-revalidate).

**Q6. What's a cache stampede and how do you prevent it?**
*Model answer:* When a hot key expires/invalidates, many concurrent readers miss simultaneously and overload the SoT. Prevent with: request coalescing/single-flight (one loader, others wait — Spring `sync=true`, Caffeine LoadingCache), probabilistic early recompute (XFetch — refresh before expiry with rising probability), TTL jitter, and stale-while-revalidate.
- *Follow-up: Stampede vs avalanche?* Stampede = one hot key; avalanche = many keys expire together (or cache flush/restart) overwhelming the SoT.
- *Follow-up: How does XFetch avoid locks?* The recompute probability rises as expiry approaches and scales with recompute cost, so statistically one request refreshes while the value is still valid; others keep hitting.

**Q7. (Senior signal) You're told to make a feature flag change take effect "instantly" across a global fleet. Walk me through the design and its tradeoffs.**
*Model answer:* "Instant" globally is bounded below by propagation physics: cross-region transport + per-instance apply. I'd push changes via a globally-replicated, durable, ordered stream (e.g., flag service → Kafka per region via mirroring, or a managed flag SDK with streaming) so each instance updates its in-memory flag cache on receipt, with a short polling TTL backstop (seconds) for missed pushes. I'd set product expectations at "≤ a few seconds, eventually consistent," provide read-your-writes for the operator who toggled it, and a kill-switch path that fails safe. Tradeoffs: push gives ~seconds at the cost of streaming infra and the need for a backstop; pure polling is simpler but adds the poll interval to latency and load. I would *not* promise linearizable global instant — that requires consensus on the read path and defeats the point of caching the flag locally.
- *Follow-up: Why a backstop if you have push?* Pushes are lossy/partial; without a TTL/poll, a missed push leaves an instance permanently wrong.
- *Follow-up: How do you make the toggling operator's own UI consistent?* Read-your-writes: pin their session to the authoritative flag service or stamp a min-version their reads must satisfy.

**Q8. (Senior signal) Pick an invalidation strategy for a product catalog with 50M items, frequent bulk price imports, and per-item edits from an admin tool — and justify it.**
*Model answer:* Per-item edits → delete-on-write (or version-CAS) for ~0 staleness on the edited item. Bulk price import touching millions of rows → generational key bump (O(1)) rather than millions of deletes, so the import is cheap and atomic; orphans die by TTL with `allkeys-lfu` eviction. Because the import and admin tool are *different writers*, I'd ideally derive invalidation from CDC on the catalog table so neither path can "forget" — the admin edit and the import both land in the DB log, and one invalidator maps rows→keys (single item) or bumps the generation (bulk). TTL backstop ~1 h with jitter. Checkout reads the authoritative price from the SoT, never the cache, since a wrong price is unacceptable.
- *Follow-up: Why CDC over app-side deletes here?* Two independent writers (import job + admin tool); CDC guarantees both are captured without dual-write risk.
- *Follow-up: How do you keep the import from causing an avalanche?* Generational bump avoids mass deletes; TTL jitter on refills; L1 absorbs while L2 refills; rate-limit/circuit-break the SoT.

**Q9. (Senior signal) When would you choose NOT to cache, despite read pressure?**
*Model answer:* When the staleness budget is ~0 and the cost of a stale read is high or irreversible — money (price/balance at transaction time), security (authz decisions, token validity), or legally/financially binding reads — and you can't get strong-enough consistency through the cache cheaply. Also when the data is so write-heavy that invalidation churn drives the hit ratio near zero (you'd pay cache cost for no benefit), or when correctness debugging cost outweighs latency savings. Instead: read the SoT (possibly a read replica with bounded lag), use request coalescing to protect it, and cache only *immutable derivatives* (e.g., a signed receipt) rather than the mutable authoritative value.
- *Follow-up: What if reads still overwhelm the SoT?* Scale the SoT (read replicas, partitioning), coalesce identical concurrent reads, and cache only the immutable parts; accept bounded staleness explicitly where the budget allows.
- *Follow-up: How do you cache authz safely if you must?* Very short TTL, push-purge on revoke, and a per-request deny-list check so revocation is effective immediately even if the positive cache lags.

**Q10. Explain leases (Facebook memcache) and what problem they solve that a TTL doesn't.**
*Model answer:* On a miss, the cache grants a reader a lease token it must present on `SET`. If a delete happens before that `SET`, the token is invalidated and the stale fill is rejected — directly defeating Race B (stale-set), which a TTL only *bounds* (≤ TTL) rather than *prevents*. Leases also throttle stampedes by granting at most one lease per key per ~10 s and serving/short-waiting others.
- *Follow-up: TTL vs leases on Race B?* TTL caps stale duration; leases prevent the stale value from ever landing.
- *Follow-up: Approximate leases without native support?* Version-CAS on fill (accept only if version ≥ cached) via a Redis Lua script.

**Q11. What is a staleness budget and how does it drive design?**
*Model answer:* It's the maximum time you'll tolerate serving a value that disagrees with the SoT, per data type — a product decision. It directly picks the strategy: budget = hours → TTL only; budget = seconds → push (delete/CDC) + short TTL backstop; budget = 0 → don't cache the authoritative value (read SoT) or use leases/version-CAS. Matching invalidation complexity to the budget avoids both over-engineering and correctness bugs.
- *Follow-up: How do you measure actual staleness?* Sample cache vs SoT periodically for critical keys; track CDC end-to-end lag and cross-region replication lag.
- *Follow-up: Can the budget differ per field of one object?* Yes — split into separate keys (e.g., cache the name long, never cache the balance), or store fields with different TTLs.

**Q12. How do `Cache-Control: no-cache`, `no-store`, and `max-age` differ, and how do you invalidate browser/CDN caches?**
*Model answer:* `max-age=N` = usable without revalidation for N seconds. `no-cache` = may store but must revalidate (via ETag/Last-Modified) before use. `no-store` = never keep a copy. You cannot *push* to a browser; you rely on `max-age` expiry, ETag revalidation, or — best — versioned/fingerprinted URLs (`immutable`) so new content has a new URL. For CDNs, purge by path/tag (Surrogate-Key) via API, but prefer versioned URLs for static assets (instant, free, race-free).
- *Follow-up: Why prefer versioned URLs over purge?* A new URL is an instant, atomic, global invalidation with no purge cost and no race; purges are eventually consistent across POPs and can be billed.
- *Follow-up: What does the `Age` header tell you?* How long the shared cache has held the response — a quick staleness check.

---

## 11. Glossary

- **At-least-once delivery:** a messaging guarantee that every message is delivered, possibly more than once — safe for idempotent operations like cache deletes.
- **Avalanche (cache):** many keys expiring (or a cache flush/restart) at once, overwhelming the SoT.
- **Backstop (TTL):** a finite TTL kept even with push invalidation, bounding staleness if a push is lost.
- **binlog / WAL / oplog:** the database's durable, ordered log of committed changes (MySQL/Postgres/Mongo). CDC reads it.
- **Bloom filter:** compact probabilistic set; "definitely not present" or "possibly present"; used to skip the SoT for nonexistent keys.
- **Bounded staleness:** consistency model where reads may be stale but never beyond a fixed time/version bound.
- **Cache-aside (look-aside):** app manages the cache — read: check then load-and-fill; write: write SoT then invalidate.
- **Cache penetration:** repeated queries for keys that don't exist, bypassing the cache and loading the SoT. Mitigated by negative caching / Bloom filters.
- **Cache poisoning:** an attacker gets a malicious response cached and served to others.
- **CAS (compare-and-set/swap):** atomic "set X to B only if X equals A"; basis for lock-free correctness.
- **CDC (Change Data Capture):** streaming the DB's committed row changes (via binlog/WAL) as events; e.g., Debezium.
- **CDN (Content Delivery Network):** geographically distributed caching servers serving content near users.
- **Coalescing / single-flight:** collapsing many concurrent misses for the same key into one load.
- **Consensus / Raft:** algorithms by which distributed nodes agree on a value/order (Raft is a widely-used one). Relevant when invalidation needs ordering guarantees.
- **CRDT:** Conflict-free Replicated Data Type — merges concurrent updates deterministically without coordination.
- **Debezium:** open-source CDC platform on Kafka Connect.
- **Deny-list (revocation list):** set of tokens/permissions explicitly disallowed, checked per request to make revocation immediate.
- **Dual-write problem:** inconsistency risk when code must update two systems (DB + event bus) non-atomically.
- **ETag:** opaque version token for HTTP content, used with `If-None-Match` for conditional revalidation (→ 304).
- **Eventual consistency:** replicas converge after writes stop; reads may be stale meanwhile.
- **Eviction:** removing entries due to capacity pressure (LRU/LFU), independent of correctness.
- **Expiry (TTL):** removal of an entry when its lifetime elapses; coarse invalidation.
- **Fan-out (invalidation):** propagating one invalidation to many caches/instances or many derived keys.
- **Generational / namespace key:** key prefixed by a shared generation counter; bumping the counter invalidates the whole group in O(1).
- **Hit / Miss / Hit ratio:** found / not-found in cache; fraction found.
- **Kafka:** distributed durable ordered log; partitions preserve per-key order; consumers track offsets.
- **Lease:** a token granted on a cache miss that the filler must present on SET; invalidated by a concurrent delete to reject stale fills (Facebook memcache).
- **Linearizability / strong consistency:** every read returns the latest committed write, as if a single copy.
- **LRU / LFU:** Least Recently / Frequently Used eviction policies.
- **MVCC (Multi-Version Concurrency Control):** DB technique keeping multiple versions of a row so readers don't block writers; analogous to versioned cache keys.
- **Monotonic reads:** a client never sees data go backward in time.
- **Near cache:** a small in-process L1 in front of a distributed cache, needing invalidation events.
- **Negative caching:** caching "not found" briefly to stop repeated misses.
- **`no-cache` / `no-store`:** HTTP directives — revalidate before use / never store, respectively.
- **POP (Point of Presence):** one CDN location.
- **Pull / Push:** reader-checks-freshness vs writer-notifies-cache.
- **Read-your-writes:** a client always sees its own prior writes.
- **RESP3 / CLIENT TRACKING:** Redis 6 protocol/feature enabling server-pushed client-side cache invalidation.
- **Source of truth (SoT) / System of record (SoR):** the authoritative store the cache copies.
- **Stale / Staleness window / Staleness budget:** wrong copy / how long it stays wrong / how long you'll tolerate it.
- **Stale-while-revalidate:** serve the old value while refreshing in the background.
- **Stampede / thundering herd:** many concurrent misses on one hot key hitting the SoT.
- **Surrogate key / cache tag:** CDN/proxy tag for grouped purge (Fastly Surrogate-Key, Cloudflare Cache-Tag, Varnish xkey).
- **Tag reverse-index:** `tag → {keys}` map enabling enumerate-and-delete grouped invalidation.
- **Transactional outbox:** writing an event row in the same DB transaction as the data, relayed later — avoids dual-write.
- **TTL (time-to-live):** per-entry lifetime.
- **Vary (HTTP):** header instructing caches to key separate copies by a request header's value.
- **Vector clock:** per-replica counters used to order/compare distributed writes.
- **Versioned key:** key embedding a version/etag so old data is naturally unreferenced after a bump.
- **Write-through / write-behind / write-around:** write synchronously through the cache / async later / bypass the cache.
- **XFetch:** probabilistic early-recompute algorithm to prevent stampedes without locks.
- **ZooKeeper:** a coordination service (distributed config, locks, leader election); sometimes underpins invalidation broadcast/coordination.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Definition:** keep cache copies correct/correct-enough vs the SoT; remove/refresh at the right time.
- **Two hard reasons:** silent + time-shifted failures; distributed-consistency race; partial across layers/regions; freshness vs hit-ratio tension.
- **Mental model:** each entry = a *lease on a fact* (what / when claimed / how long trusted). Break leases via **pull** (TTL, ETag, version) or **push** (delete, event/CDC). Real systems = push + TTL backstop.
- **Golden rules:**
  1. **Write SoT first, THEN invalidate.**
  2. **Prefer DELETE over update** (avoids cache lost-updates).
  3. **Always keep a finite TTL backstop** — even with push.
  4. **Make invalidation idempotent** (deletes are) → at-least-once safe.
  5. **Short L1 TTLs** because broadcast is lossy.
  6. **Match strategy to the staleness budget.**
- **Strategies:** TTL only · delete-on-write · write-through update · versioned keys · **generational keys (O(1) group purge)** · tag reverse-index · CDC/event-driven · leases/version-CAS · stale-while-revalidate.
- **The race (Race B):** slow reader reads OLD, writer commits NEW + deletes, slow reader fills OLD → stale until TTL. Fix: SoT-first + TTL + version-CAS/leases (+ delayed double-delete + read-your-writes).
- **Layers (hardest to push outward):** browser (no push — versioned URLs / ETag / max-age) → CDN (purge/surrogate-key, prefer versioned URLs) → app L1 (broadcast invalidation + short TTL) → shared L2 Redis (direct DEL) → DB buffer pool (DB's job).
- **Stampede vs avalanche:** one hot key vs many keys at once. Fixes: single-flight/`sync`, XFetch, **TTL jitter**, stale-while-revalidate, warm-up.
- **Redis numbers/defaults:** active expiry via `hz`=10 (~10×/s, samples 20 keys, repeat if >25% expired); `maxmemory-policy` default `noeviction`; `maxmemory-samples`=5; use `UNLINK`/`SCAN` not `DEL`-huge/`KEYS`; `notify-keyspace-events` default off; replicas don't expire independently.
- **Security:** never long-TTL authz/tokens; short TTL + push purge + per-request deny-list.
- **Observability:** hit ratio, evicted/expired keys, CDC/invalidator consumer lag, replication lag, stampede QPS spikes, per-key cache-vs-SoT staleness audits, CDN `Age`/`X-Cache`.
- **Don't cache when:** staleness budget ≈ 0 and stale read is costly/irreversible (price at checkout, balance, authz) — read SoT, coalesce, cache only immutable derivatives.

### 12.2 Self-test (no answers — recall actively)

1. Explain Race B step by step and list four independent defenses against it, stating what each one guarantees (prevents vs bounds).
2. Your bulk job updates 5,000,000 catalog rows in one import. Design the invalidation so the import is O(1) and you don't enumerate keys — then explain what happens to the old cache entries and why memory is still bounded.
3. You run Caffeine (L1) in 40 app pods in front of Redis (L2). Walk through exactly what happens on a write so every pod ends up consistent, and justify the L1 TTL you'd pick.
4. Two different writers touch the same SoT table: an admin UI and a nightly batch. Why might app-side delete-on-write be unsafe here, and what would you switch to? What new failure modes does that introduce and how do you monitor them?
5. Give three data types with staleness budgets of (a) ~0, (b) seconds, (c) hours, and the invalidation strategy you'd pick for each — and justify the match.
6. Distinguish `Cache-Control: no-cache`, `no-store`, and `max-age=600`, then explain why a fingerprinted URL (`app.4f9a.js`, `immutable`) is a better invalidation tool than a CDN path purge.
7. What do Facebook-style leases prevent that a TTL only bounds, and how would you approximate leases in Redis without native lease support?

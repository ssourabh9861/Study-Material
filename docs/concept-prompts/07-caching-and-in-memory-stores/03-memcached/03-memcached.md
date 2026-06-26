# Memcached — A Definitive Engineering Handbook Chapter

> **Concept area:** Caching & In-Memory Stores
> **Subtopic:** Memcached
> **Reader profile:** Senior JVM/backend developer who wants to master Memcached from first principles to deep internals — to design with it, operate it, debug it, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What Memcached is, in one paragraph

Memcached is a **distributed, in-memory, key-value cache**. It stores small chunks of arbitrary data (strings or binary blobs) keyed by a string, entirely in RAM, with **no persistence to disk**, **no replication**, **no rich data types**, and **no clustering logic on the server**. Each Memcached server is a dumb, independent bag of bytes that knows nothing about its peers. The intelligence — deciding *which* server holds *which* key — lives entirely in the client library. Its single design goal is to be a blisteringly fast, simple, horizontally scalable **look-aside cache** that takes read load off a slower backing store (a relational database, a service, a rendered HTML fragment, the result of an expensive computation). When a value is not in the cache (a "miss"), your application fetches it from the source of truth and writes it into the cache for next time. Memcached deliberately does *less* than Redis, and that minimalism is the point: fewer features means a smaller, more predictable, multi-threaded server that scales close to linearly with cores and saturates the network before it saturates the CPU.

### 1.2 The problem it solves

Databases are expensive to read from. A query that joins three tables, scans an index, deserializes rows, and travels over a connection might take 5–50 ms and consume a database connection (a scarce, contended resource). The *same* query repeated thousands of times per second for the same hot keys is pure waste. Memcached lets you answer that repeated read from RAM in **tens to low hundreds of microseconds**, off-loading the database so it can spend its limited capacity on writes and cold reads.

The canonical use cases:

- **Database query result caching** — cache the deserialized result of `SELECT … WHERE id = ?`.
- **Object/row caching** — cache a user profile object by `user:123`.
- **Session storage** — cache web session blobs (with the caveat that no-persistence means sessions vanish on restart).
- **Rendered fragment caching** — cache an expensive HTML fragment or JSON response.
- **Rate-limiting / counters** — atomic `incr`/`decr` for "requests this minute."
- **Computed/derived data** — cache the output of an expensive ML scoring or aggregation.

### 1.3 When you reach for it (and when you don't)

**Reach for Memcached when:**
- You need a *pure* cache — ephemeral data that can be regenerated from a source of truth.
- Your values are simple blobs (a serialized object, a string, a JSON document) and you don't need server-side data structures.
- You want maximum throughput per server and the simplest possible operational model.
- You want to scale cache capacity horizontally by just adding nodes.
- Your workload is multi-threaded and you want a server that uses many cores per instance.

**Don't reach for Memcached when:**
- You need **persistence** (data must survive a restart) — use Redis (with RDB/AOF) or a database.
- You need **rich data types** (lists, sorted sets, hashes, streams, pub/sub, geospatial) — use Redis.
- You need **replication / high availability at the data level** — Memcached has none natively.
- You need **values larger than ~1 MB** (the default item size cap).
- You need **atomic multi-key transactions** or Lua-style server-side scripting — use Redis.

### 1.4 The one-paragraph mental model

Picture a wall of identical lockers (servers), each a fixed-size grid of pre-sized cubbies (slabs). You (the client) carry a deterministic formula (a hash ring) that, given a key, tells you *exactly which locker* to walk to — no directory, no coordinator. You put a labeled blob in a cubby that fits it. If the locker is full, the oldest unused blob in that cubby-size class is thrown out to make room (LRU eviction). Lockers never talk to each other; if a locker burns down, the formula simply starts pointing those keys at a different locker, and they'll miss until refilled. That's Memcached: **client-side routing + server-side slab-allocated LRU + zero coordination**.

---

## 2. Foundations from first principles

This section builds Memcached from zero. Each new term is defined the moment it appears.

### 2.1 Cache, source of truth, hit, miss

- **Cache:** a fast, smaller store that holds copies of data whose authoritative version lives elsewhere. A cache is allowed to be incomplete and stale; it is *not* the system of record.
- **Source of truth (SoT) / backing store:** the authoritative store (e.g., your SQL database). If the cache and the SoT disagree, the SoT wins.
- **Cache hit:** the requested key was found in the cache. Fast path.
- **Cache miss:** the key was absent; you must go to the SoT. Slow path.
- **Hit ratio:** `hits / (hits + misses)`. A 95% hit ratio means 19 of every 20 reads are served from RAM. Small improvements here have outsized effects on database load.

### 2.2 Look-aside (cache-aside) vs other patterns

Memcached is almost always used **look-aside** (also called **cache-aside**), because the server has no hooks into your database. The application orchestrates everything:

**Read path (look-aside):**
1. `value = cache.get(key)`
2. If hit → return `value`.
3. If miss → `value = db.query(...)`; `cache.set(key, value, ttl)`; return `value`.

**Write path (look-aside):** update the database, then **invalidate** (delete) the cache key, so the next read repopulates it.
```
db.update(...)
cache.delete(key)   // prefer delete-on-write over set-on-write; see §6
```

Contrast with patterns Memcached does *not* do natively:
- **Read-through / write-through:** the cache itself reads from / writes to the backing store on your behalf. Memcached has no backing-store integration, so this must be emulated in a wrapper library.
- **Write-behind (write-back):** writes go to the cache first and are flushed to the SoT asynchronously. Risky without persistence; Memcached cannot do this safely because a crash loses unflushed writes.

### 2.3 In-memory and "no persistence"

- **In-memory:** all data lives in process RAM. There is no disk I/O on the hot path, which is why operations are microsecond-scale.
- **No persistence:** Memcached never writes data to disk. A process restart, crash, or `flush_all` empties everything. This is a feature, not a bug — it keeps the server simple and fast, and it forces you to treat the cache as disposable.

> **Adjacent term — TTL (time to live):** an expiry duration attached to each item. After the TTL elapses, the item is considered expired and will be evicted/ignored on next access. In Memcached, TTL is expressed in seconds; a value `> 2592000` (30 days) is interpreted as an absolute Unix timestamp, not a relative duration. A TTL of `0` means "never expire" (but the item can still be evicted under memory pressure).

### 2.4 Key-value store

- **Key:** a string identifier. In the classic ASCII protocol a key is limited to **250 bytes** and may not contain spaces or control characters/newlines (the binary protocol relaxes the character restriction but keeps the length cap).
- **Value:** an opaque byte blob, up to **1 MB by default** (configurable via `-I`).
- **Flags:** a small client-defined integer (32-bit) stored alongside each value. Clients use flags to record metadata such as "this value is gzip-compressed" or "this value is a Java-serialized object vs a raw string." The server never interprets flags.

### 2.5 Distributed — but the server doesn't know it

Crucially, "distributed" in Memcached means *the client distributes keys across many independent servers*. The servers form a pool but have **no awareness of one another**: no gossip, no consensus, no shared state. This is the opposite of clustered databases.

> **Adjacent term — consistent hashing:** an algorithm for mapping keys to nodes such that when a node is added or removed, only a small fraction (`~1/N`) of keys need to move, instead of nearly all of them. Without it, the naive `hash(key) % N` scheme remaps almost every key when `N` changes, causing a cache-wide miss storm. We cover this in depth in §3.5.

### 2.6 RAM, allocation, and fragmentation (the why behind slabs)

To understand Memcached's internals you need three OS/runtime concepts:

> **Adjacent term — heap & dynamic allocation (`malloc`/`free`):** general-purpose memory allocators hand out variable-size chunks on request and reclaim them on release. Over time, with many different-sized allocations and frees, the heap develops **fragmentation**: free memory exists but is broken into pieces too small to satisfy a large request. **External fragmentation** is wasted *gaps between* allocations; **internal fragmentation** is wasted space *inside* an allocation that's bigger than needed.

> **Adjacent term — page / chunk / slab:** Memcached sidesteps `malloc` fragmentation by carving memory into fixed-size **pages** (default **1 MB**), each page belonging to a **slab class**. A slab class chops its pages into equal-size **chunks**. An incoming item is rounded up to the smallest chunk that fits. This trades a *bounded, predictable* amount of internal fragmentation for the elimination of unpredictable external fragmentation — the core design decision examined in §3.2–3.3.

### 2.7 LRU — Least Recently Used

> **Adjacent term — LRU:** an eviction policy that, when space is needed, discards the item that was accessed least recently, on the bet that recently-used items are more likely to be used again (temporal locality). Memcached maintains an LRU list *per slab class* (not one global list), and modern versions split it into segments (HOT/WARM/COLD/TEMP — see §3.4) to approximate LRU more cheaply and to resist scan pollution.

### 2.8 Multi-threading and the event loop

> **Adjacent term — event-driven I/O / `libevent`:** rather than one thread per connection (which doesn't scale to tens of thousands of connections), Memcached uses an **event loop** built on **libevent** — a library that wraps OS readiness-notification syscalls (`epoll` on Linux, `kqueue` on BSD/macOS) so a single thread can watch thousands of sockets and wake only when one has data. Memcached runs **multiple** such worker threads (default 4), each with its own event loop, sharing the item hash table and slab memory under fine-grained locks. This is a key differentiator from single-threaded Redis (pre-6.x).

---

## 3. How it works internally (the heart of the doc)

We now trace Memcached's internals end-to-end: the threading model, slab allocation, the LRU machinery, the item lifecycle, the hash table, and client-side routing.

### 3.1 Process & threading architecture

A running `memcached` process consists of:

1. **A main/listener thread.** It binds the listening socket(s) (TCP and/or UDP and/or a Unix domain socket), accepts new connections, and hands each accepted connection off to a worker thread via a round-robin dispatch over a small pipe/queue.
2. **N worker threads** (default `-t 4`). Each worker owns a libevent loop and processes the protocol for its assigned connections: parse command → look up/modify item → write response. Workers share global state (the hash table, the slab allocator, the LRU lists) protected by locks.
3. **Background maintenance threads** (depending on version/flags):
   - **LRU crawler** — periodically walks LRU lists reclaiming expired items proactively (so memory isn't held by dead items until they're next requested).
   - **LRU maintainer (`lru_maintainer_thread`)** — rebalances items between LRU sub-segments (HOT/WARM/COLD) and drives the segmented-LRU algorithm. On by default in modern versions.
   - **Slab rebalancer / `slab_automove`** — moves whole pages from one slab class to another when eviction pressure is unbalanced (the cure for *slab calcification*, §3.3).
   - **Hash table expander** — grows the hash table when it gets too dense.
   - **idle-timeout / logger / extstore** threads as configured.

```
                       ┌──────────────────────────┐
   clients ───TCP───▶  │  listener thread          │
                       │  accept() → dispatch pipe │
                       └──────────┬───────────────┘
            ┌─────────────────────┼─────────────────────┐
            ▼                     ▼                     ▼
     ┌────────────┐       ┌────────────┐        ┌────────────┐
     │ worker 0   │       │ worker 1   │  ...   │ worker 3   │
     │ libevent   │       │ libevent   │        │ libevent   │
     └─────┬──────┘       └─────┬──────┘        └─────┬──────┘
           └──────────┬─────────┴───────────┬─────────┘
                      ▼                      ▼
            ┌────────────────────┐  ┌────────────────────┐
            │ item hash table    │  │ slab allocator +    │
            │ (chained buckets)  │  │ per-class LRU lists │
            └────────────────────┘  └────────────────────┘
            ┌───────────────────────────────────────────┐
            │ bg threads: LRU crawler, LRU maintainer,   │
            │ slab automover, hashtable expander         │
            └───────────────────────────────────────────┘
```

**Locking:** Memcached uses fine-grained locking. There's an array of **item locks** (a striped lock — `hash(key)` selects which of many mutexes guards that item), a slab lock, and LRU locks. Striping means two operations on different keys usually don't contend, which is what lets multiple workers scale across cores. Under the `-o hashpower` / item-lock tuning you can change the number of locks.

### 3.2 The slab allocator (the central idea)

Memcached **never calls `malloc` per item on the hot path**. Instead:

1. At startup it knows a memory ceiling (`-m`, in MB; default 64 MB). It does **not** allocate it all up front by default (unless `-L`/preallocation is on); it grows lazily.
2. Memory is acquired from the OS in **1 MB pages** (the "slab page size," `-I` affects max item size and indirectly page sizing).
3. There are ~**63–64 slab classes** by default. Each class has a fixed **chunk size**. Class 1 might be ~96 bytes; each subsequent class is the previous size multiplied by the **growth factor** (`-f`, default **1.25**), rounded up to alignment (typically 8 bytes). So sizes grow geometrically: ~96, ~120, ~152, ~192, … up to ~1 MB (the largest class holds one item per page).
4. When you store an item, the server computes the *total* size it needs: key + value + ~48–56 bytes of item header + suffix/flags + CAS field. It picks the **smallest slab class whose chunk size ≥ that total**. The item goes into a free chunk of that class. If the class has no free chunk and no free page is available, the class **evicts** its LRU item (or, with `slab_automove`, may steal a page from another class).

**Worked example.** Suppose you store a value whose total footprint (with overhead) is 130 bytes. The slab classes near there might be 120 and 152 bytes. 130 doesn't fit in 120, so it goes into the **152-byte** class. **22 bytes are wasted** inside that chunk — that's **internal fragmentation**, and it's *bounded* by the growth factor: with `-f 1.25`, worst-case internal waste per item is ~20% (the gap between adjacent class sizes). Lowering `-f` (e.g., `1.08`) creates more, finer-grained classes → less waste per item, but more classes to manage and potentially worse page utilization. Raising `-f` does the opposite.

**Why this beats `malloc`:** by reusing fixed-size chunks, Memcached can `set`/`evict` items endlessly without ever fragmenting the heap. Allocation is O(1) (pop a free chunk off a free list). The price is the bounded internal fragmentation above, plus the slab-calcification problem next.

### 3.3 Slab calcification (a famous Memcached failure mode)

**The problem:** slab pages are assigned to a class **and historically stayed there forever**. Imagine your traffic for the first hour stores millions of 100-byte items, filling, say, 90% of memory with the 120-byte slab class. Then your workload shifts to storing 500-byte items. The 600-byte class has almost no pages, so it evicts heavily *even though tons of memory is "free" but locked inside the 120-byte class*. This is **slab calcification**: pages are calcified to a class that no longer needs them, while a hot class starves. Symptoms: high eviction rate in one class, low memory utilization overall, surprising cache misses after a workload change.

**The fixes:**
- **`slab_automove`** (modes `0` off, `1` standard/default in modern builds, `2` aggressive): a background thread watches per-class eviction rates and **moves a free page from a class that doesn't need it to one that does**. Mode 1 moves pages conservatively (a page must be entirely free of recently-used items); mode 2 will forcibly free a page (causing some evictions) to satisfy a starving class faster.
- **Manual rebalance:** `slabs reassign <src_class> <dst_class>` forces a page move, and `slabs automove <0|1|2>` toggles the policy at runtime.
- **Page mover thread (`slab_rebalance`)** does the actual relocation: it picks a page in the source class, evicts/relocates the items in it, and re-buckets the now-free page to the destination class.

> Historically (pre-1.4.11-ish) automove didn't exist and calcification was a top operational pain point. On modern versions (1.4.x late / 1.5+ / 1.6+) `slab_automove=1` is on by default and calcification is largely mitigated, but you should still **monitor per-class evictions** because mode-1 is conservative and a sudden, large workload shift can still starve a class faster than pages are reclaimed.

### 3.4 The LRU machinery (segmented LRU)

Naive LRU (one doubly-linked list, bump to head on every access) has two problems at scale: (a) bumping on *every* read causes lock contention and cache-line ping-pong, and (b) a one-time scan of cold data ("scan pollution") can evict your genuinely hot set. Modern Memcached (1.5+) uses **segmented LRU per slab class**:

- **HOT:** newly-set items and items proven active start/live here. Items that age out without re-access flow to WARM or COLD.
- **WARM:** items that were accessed again after entering the system (proven "active"). Frequently re-hit items stay warm.
- **COLD:** the eviction candidate pool. When memory is needed, items are evicted from the tail of COLD.
- **TEMP:** a special segment for items set with a very short TTL (via the `-o temporary_ttl` threshold); they bypass the normal segments since they'll expire soon anyway, avoiding LRU churn.

Items move between segments based on access (the `fetched`/active bit) and age, driven by the **LRU maintainer thread**. Crucially, on a `get`, Memcached often does **not** move the item to the list head immediately; instead it sets a bit, and the maintainer thread reconciles segments in the background. This makes reads cheaper and reduces lock contention. To enable classic single-queue LRU you can disable the maintainer (`-o lru_maintainer` off), but the segmented mode is the default and recommended.

**LRU crawler:** independent of eviction, the crawler periodically walks each class's LRU reclaiming **already-expired** items (TTL passed) so their memory returns to the free list without waiting for a `get` to notice the expiry. Tunables: `lru_crawler enable`, `lru_crawler sleep <microseconds>`, `lru_crawler tocrawl <n>`.

### 3.5 Client-side consistent hashing (key → server)

Because servers don't coordinate, the **client** must deterministically map each key to exactly one server, and all clients in the fleet must agree on that mapping (otherwise client A writes `user:1` to server X and client B reads it from server Y → permanent miss). The standard solution is **consistent hashing with a hash ring (continuum)**, classically the **ketama** algorithm:

1. **Build the ring.** For each server, compute many **virtual nodes** (typically ~160 per server, often via repeated `MD5` of `"<server>-<i>"`). Each virtual node is a point on a 32-bit (or 2^32) ring. More virtual nodes → smoother key distribution. (Virtual nodes / "replicas on the ring" are *not* data replicas; they're just hash points to balance load.)
2. **Place a key.** Compute `hash(key)` (ketama uses MD5 → take a 32-bit slice). Walk **clockwise** on the ring to the first virtual node; that node's server owns the key.
3. **Add/remove a node.** Only the keys in the arc that the changed node covers move; `~1/N` of keys remap. With plain modulo (`hash % N`), changing N remaps `~(N-1)/N` of keys → catastrophic miss storm. This is the entire reason consistent hashing exists.

> **Why "ketama" specifically:** libketama (from Last.fm) standardized the exact MD5-based ring construction so that **clients written in different languages produce the identical mapping**. If your PHP, Java, and Python services must share one cache pool, they must all use the *same* hashing scheme and the *same* server-list ordering, or they'll disagree. Always verify cross-language clients use compatible ketama settings (hash function, weights, virtual-node count, whether the port is included in the node string).

**Weights:** servers can be given weights (e.g., a box with 2× RAM gets 2× the ring arcs) so capacity-proportional distribution is possible.

**Alternative client strategies:** naive modulo (fast, terrible on topology change — only acceptable for a static, never-changing pool), rendezvous/HRW hashing (highest-random-weight; another minimal-disruption scheme), or a coordinator like **mcrouter** (§3.7) that hides routing from the app.

### 3.6 Item lifecycle — step by step

**SET (store):**
1. Client picks server via the ring; opens/reuses a connection.
2. Client sends `set <key> <flags> <exptime> <bytes>\r\n<data>\r\n` (ASCII protocol).
3. Worker parses, computes total item size, selects slab class.
4. Allocates a chunk (free list pop; if empty, may evict COLD-tail item of that class, or trigger slab page assignment / automove).
5. Copies key+flags+value+TTL into the item; computes the item's hash; inserts into the **hash table** bucket (chaining on collision); links into the class's LRU (HOT segment).
6. Responds `STORED\r\n`.

**GET (retrieve):**
1. Client → server via ring; sends `get <key>\r\n` (or `gets` for the CAS-token variant, or a multi-key `get k1 k2 k3`).
2. Worker hashes key, finds the bucket, walks the chain comparing keys.
3. If found and **not expired** (TTL check is lazy — done on access): mark active bit (for segmented LRU), return `VALUE <key> <flags> <bytes>\r\n<data>\r\nEND\r\n`.
4. If not found or expired: return just `END\r\n` (a miss). Expired items are reclaimed lazily here and/or by the crawler.

**DELETE:** `delete <key>\r\n` → unlink from hash table + LRU, free chunk back to class free list → `DELETED\r\n` (or `NOT_FOUND`).

**Expiration (lazy + crawler):** Memcached does **not** actively scan-and-expire on a timer for every key; expiry is checked **lazily on access**, and the **LRU crawler** sweeps for the rest. So an expired item can occupy memory until either someone reads it or the crawler reaches it.

**Eviction:** when a class needs a chunk and has none free, it evicts from the **COLD** tail of that class's LRU (not globally). An eviction is *not* an expiry — it's a live item thrown out under pressure. The `evictions` stat counts these and is a primary capacity signal.

### 3.7 mcrouter — the routing layer (Facebook/Meta)

> **Adjacent term — mcrouter:** an open-source Memcached **protocol router/proxy** built by Facebook. The app talks plain Memcached protocol to a local mcrouter, and mcrouter handles pool routing, consistent hashing, connection pooling, failover, replication-by-fan-out, shadowing, and prefix-based routing to different pools. It turns "client-side everything" into "a smart proxy you operate," which is how Memcached scales to thousands of servers. Concepts it introduces: **pools** (named server lists), **route handles** (composable routing rules), **TKO** ("technical knockout" — marking a failing server down after consecutive errors so traffic reroutes), and **failover** routes.

---

## 4. The complete toolkit

### 4.1 Server command-line flags (most-used)

| Flag | Meaning | Default | Notes |
|---|---|---|---|
| `-m <MB>` | Max memory for item data | 64 MB | The big one. Does **not** include overhead/connection buffers; size the box larger. |
| `-p <port>` | TCP listen port | 11211 | |
| `-U <port>` | UDP listen port | 11211 | Set `-U 0` to disable UDP (recommended; UDP has been an amplification-attack vector). |
| `-s <file>` | Unix socket path | — | For same-host clients; lower latency than TCP. |
| `-l <addr>` | Listen address(es) | INADDR_ANY | **Bind to localhost/private IP**; never expose to the internet. |
| `-c <n>` | Max simultaneous connections | 1024 | Raise for high-fanout fleets. |
| `-t <n>` | Worker threads | 4 | Set near core count; diminishing returns past ~8 for many workloads. |
| `-f <factor>` | Slab growth factor | 1.25 | Lower → finer classes, less internal frag, more classes. |
| `-n <bytes>` | Min space for key+value+flags (chunk floor) | 48 | Sets smallest slab class size. |
| `-I <size>` | Max item size | 1m | e.g., `-I 4m`. Raising it changes page math and wastes memory for big classes. |
| `-M` | Disable eviction; return error when full | off | Dangerous for a cache; turns OOM into `SERVER_ERROR`. |
| `-L` | Try to use large memory pages (huge pages) | off | Reduces TLB misses on big instances. |
| `-o <opts>` | Extended options (comma-list) | — | Where modern features live; see below. |
| `-d` | Daemonize | — | |
| `-u <user>` | Drop privileges to user | — | Don't run as root. |
| `-v`/`-vv`/`-vvv` | Verbosity | — | `-vv` prints each command — debugging only. |

**Extended `-o` options (selected):**

| `-o` option | Meaning | Default |
|---|---|---|
| `modern` | Enable a bundle of modern defaults (segmented LRU, automove, etc.) | on in 1.5+ |
| `slab_automove=<0|1|2>` | Slab page rebalancing policy | 1 |
| `lru_maintainer` | Background LRU segment maintainer | on |
| `lru_crawler` | Background expired-item reaper | on |
| `hashpower=<n>` | Initial hash-table size = 2^n buckets | auto |
| `tail_repair_time=<s>` | Recover items stuck by crashed connections | off/large |
| `temporary_ttl=<s>` | TTL threshold for the TEMP LRU | — |
| `ext_path=<file>:<size>` | **extstore**: spill cold items to SSD/flash | off |
| `ext_item_size`, `ext_low_ttl`, … | extstore tuning | — |
| `idle_timeout=<s>` | Close idle connections | off |
| `no_modern` | Revert to legacy defaults | — |

> **extstore** is the one big exception to "no disk": it's an optional, opt-in tier that keeps **keys + hot values in RAM** but spills **cold values to flash/SSD**, vastly increasing effective cache size per dollar. It is *not* persistence — a restart still loses everything; it's a RAM-extension, not durability.

### 4.2 Text (ASCII) protocol commands

| Command | Form | Purpose |
|---|---|---|
| `set` | `set k flags exp bytes [noreply]\r\ndata\r\n` | Unconditional store. |
| `add` | `add …` | Store **only if key absent** (atomic create). |
| `replace` | `replace …` | Store **only if key exists**. |
| `append` / `prepend` | `append k 0 0 bytes\r\ndata\r\n` | Concatenate to existing value (no flags/exp update). |
| `cas` | `cas k flags exp bytes casunique\r\ndata\r\n` | Compare-and-swap; store only if unchanged since `gets`. |
| `get` | `get k\r\n` or `get k1 k2 k3\r\n` | Retrieve one or many (**multiget**). |
| `gets` | `gets k\r\n` | Get + CAS token. |
| `gat` / `gats` | `gat exp k\r\n` | Get-and-touch (read + reset TTL atomically). |
| `delete` | `delete k\r\n` | Remove. |
| `incr` / `decr` | `incr k 1\r\n` | Atomic 64-bit add/subtract on a numeric-string value. |
| `touch` | `touch k exp\r\n` | Update TTL only. |
| `stats` | `stats [sub]\r\n` | Server/sub-system metrics. |
| `flush_all` | `flush_all [delay]\r\n` | Expire **everything** (optionally after delay). Dangerous in prod. |
| `version` | `version\r\n` | Server version. |
| `slabs` | `slabs reassign … / automove …` | Slab management. |
| `lru_crawler` | `lru_crawler enable / crawl <classes>` | Crawler control. |
| `lru` | `lru tune / mode …` | Tune segmented-LRU ratios. |
| `watch` | `watch [fetchers|mutations|evictions]` | **Live event stream** (1.5.6+) — tail real-time activity. |
| `meta` | `mg/ms/md/ma …` | **Meta protocol** (1.6+) — flag-rich, efficient, supports many features in one command (see §7.4). |

> **Adjacent term — CAS (compare-and-swap):** an optimistic-concurrency primitive. `gets` returns a value plus a unique 64-bit version token. `cas` writes **only if** the server's current token still matches, i.e., nobody changed the key in between. If it changed, you get `EXISTS` and must retry. This lets multiple clients safely read-modify-write the same key without a lock.

> **Adjacent term — `noreply`:** appending `noreply` to a mutation tells the server not to send a response, saving a round-trip's worth of reply parsing — useful for fire-and-forget bulk sets, at the cost of not knowing if it succeeded.

### 4.3 `stats` you must know

| Stat | Meaning | Watch for |
|---|---|---|
| `get_hits` / `get_misses` | Hit/miss counts | Compute hit ratio. |
| `cmd_get` / `cmd_set` | Total gets/sets | Read/write mix. |
| `evictions` | Live items thrown out under pressure | **Nonzero & rising = undersized memory** (or calcification). |
| `expired_unfetched` | Items that expired before ever being read | Wasted writes / over-caching. |
| `reclaimed` | Expired items whose space was reused | Healthy churn. |
| `bytes` | Current bytes stored | vs `limit_maxbytes`. |
| `curr_items` / `total_items` | Live count / lifetime count | |
| `curr_connections` / `total_connections` | Open / lifetime conns | Connection leaks. |
| `bytes_read` / `bytes_written` | Network volume | NIC saturation check. |
| `threads` | Worker count | |
| `stats slabs` | Per-class chunk size, used/free chunks | **Where calcification shows up.** |
| `stats items` | Per-class item counts, ages, evictions, `evicted_time` | Per-class eviction & age. |
| `stats sizes` | Histogram of item sizes (locks server briefly!) | Sizing the growth factor. |

### 4.4 Java client libraries

| Client | Maintainer/status | Highlights |
|---|---|---|
| **spymemcached** | Long-standing (net.spy) | Fully async (NIO), single I/O thread, ketama (`KetamaConnectionFactory`), bulk ops, binary protocol. Mature; widely used. |
| **xmemcached** | Active community | Multi-connection per node, ketama, binary & text, talkative API. |
| **AWS ElastiCache Cluster Client** | AWS fork of spymemcached | Adds **Auto Discovery** of nodes via the `config` endpoint. |
| **Folsom** | Spotify | Modern, `CompletionStage`-based async, lightweight. |
| **Memcached over JCache (JSR-107)** | via wrappers | If you need the standard `javax.cache` API. |

> **Adjacent term — NIO (Java New I/O) / async client:** spymemcached uses non-blocking sockets and a single I/O thread with a selector (Java's `epoll` wrapper), exposing operations as `Future`s. This means many in-flight requests share few connections — efficient, but a slow/stalled node can back up that single I/O thread, so timeouts and node-failure handling matter.

---

## 5. Code examples by use case

All Java examples use **spymemcached** (`net.spy:spymemcached:2.12.3`) unless noted. Maven coordinate:
```xml
<dependency>
  <groupId>net.spy</groupId>
  <artifactId>spymemcached</artifactId>
  <version>2.12.3</version>
</dependency>
```

### 5.1 Look-aside read with a typed loader (the bread-and-butter pattern)

```java
import net.spy.memcached.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class UserCache {
    private final MemcachedClient mc;
    private final UserRepository db;
    private static final int TTL_SECONDS = 300; // 5 min

    public UserCache(List<InetSocketAddress> nodes, UserRepository db) throws Exception {
        // KetamaConnectionFactory => consistent hashing across nodes.
        // Without this, adding/removing a node remaps almost every key.
        this.mc = new MemcachedClient(new KetamaConnectionFactory(), nodes);
        this.db = db;
    }

    public User getUser(long id) {
        String key = "user:v1:" + id;          // version the key prefix so a schema
                                                // change is a no-op invalidation (just bump v1->v2)
        User cached = (User) mc.get(key);       // microsecond-scale on a hit
        if (cached != null) return cached;      // HIT

        User fresh = db.findById(id);           // MISS -> source of truth
        if (fresh != null) {
            // Fire-and-forget set; we don't block the read path on the write.
            mc.set(key, TTL_SECONDS, fresh);
        }
        return fresh; // may be null -> consider negative caching (see 5.6)
    }

    public void invalidate(long id) {
        // Delete-on-write (NOT set-on-write) avoids races; see best practices.
        mc.delete("user:v1:" + id);
    }
}
```
**What matters:** `KetamaConnectionFactory` (consistent hashing), key versioning (`v1`), delete-on-write invalidation, and not blocking the read path on the cache write.

### 5.2 Multiget — collapse N round-trips into one per server

```java
import net.spy.memcached.MemcachedClient;
import java.util.*;

public List<Product> getProducts(MemcachedClient mc, List<Long> ids, ProductRepo db) {
    // Build the key list and a reverse map back to ids.
    Map<String, Long> keyToId = new LinkedHashMap<>();
    for (long id : ids) keyToId.put("prod:v1:" + id, id);

    // getBulk issues a MULTIGET: the client groups keys by destination server
    // (per the hash ring) and sends ONE pipelined request per server.
    Map<String, Object> hits = mc.getBulk(keyToId.keySet());

    List<Long> misses = new ArrayList<>();
    Map<Long, Product> result = new HashMap<>();
    for (Map.Entry<String, Long> e : keyToId.entrySet()) {
        Product p = (Product) hits.get(e.getKey());
        if (p != null) result.put(e.getValue(), p);
        else misses.add(e.getValue());
    }

    if (!misses.isEmpty()) {
        // One batched DB query for the misses, then backfill the cache.
        Map<Long, Product> fromDb = db.findByIds(misses);
        fromDb.forEach((id, p) -> {
            result.put(id, p);
            mc.set("prod:v1:" + id, 600, p);
        });
    }
    // Preserve request order.
    List<Product> ordered = new ArrayList<>(ids.size());
    for (long id : ids) ordered.add(result.get(id));
    return ordered;
}
```
**Why multiget is critical:** fetching 100 keys with 100 `get`s = 100 round-trips. `getBulk` fans out by server and pipelines, so it's effectively one round-trip per *server* involved. This is one of Memcached's biggest latency wins. (Caveat: a multiget that touches many servers is only as fast as the *slowest* server — the "multiget hole" problem at Facebook scale, §7.)

### 5.3 Atomic counter (rate limiting / hit counters)

```java
// incr/decr are atomic on the server. The value must be a numeric ASCII string.
public long incrementRequestCount(MemcachedClient mc, String userId) {
    String key = "rl:" + userId + ":" + (System.currentTimeMillis() / 60000); // per-minute bucket
    // 'incr' fails if the key is absent, so seed it atomically with 'add' first.
    mc.add(key, 70, "0");          // create with 70s TTL only if absent (no-op if present)
    long count = mc.incr(key, 1);  // atomic +1, returns the new value
    return count;
}
// Reject when count > limit. The per-minute key auto-expires, so no cleanup needed.
```
**What matters:** `incr` requires the key to exist, so seed with `add` (atomic create). The time-bucketed key gives a sliding-ish window that self-cleans via TTL. Counters survive only as long as the process; for a hard rate limit you may want Redis with persistence.

### 5.4 CAS — safe read-modify-write of a shared structure

```java
import net.spy.memcached.CASResponse;
import net.spy.memcached.CASValue;

// Atomically add an item to a serialized shopping cart, retrying on contention.
public boolean addToCart(MemcachedClient mc, String userId, CartItem item) {
    String key = "cart:" + userId;
    for (int attempt = 0; attempt < 5; attempt++) {
        CASValue<Object> cv = mc.gets(key);          // value + CAS token
        if (cv == null) {                            // cart doesn't exist yet
            Cart c = new Cart(); c.add(item);
            // add() succeeds only if still absent -> handles the create race
            if (mc.add(key, 3600, c).getStatus().isSuccess()) return true;
            continue; // someone created it; loop to read+CAS
        }
        Cart c = (Cart) cv.getValue();
        c.add(item);
        CASResponse r = mc.cas(key, cv.getCas(), c); // store only if token unchanged
        if (r == CASResponse.OK) return true;        // success
        // EXISTS => concurrent modification; loop and retry
    }
    return false; // gave up after retries (caller should surface/contend)
}
```
**What matters:** CAS turns a lost-update race into a retryable loop without any server-side lock. This is the correct way to mutate a shared cached object from multiple writers.

### 5.5 Storing compressed/serialized blobs with flags (transcoder)

```java
import net.spy.memcached.transcoders.SerializingTranscoder;

// Built-in SerializingTranscoder gzip-compresses values above a threshold and
// records that fact in the item FLAGS, so reads transparently decompress.
SerializingTranscoder tc = new SerializingTranscoder();
tc.setCompressionThreshold(4 * 1024); // compress values > 4 KB (default ~16 KB)

mc.set("doc:42", 600, bigJsonString, tc);
String back = (String) mc.get("doc:42", tc); // auto-decompressed via flags

// Custom transcoder example: store raw UTF-8 bytes (no Java serialization overhead),
// useful when interoperating with non-Java clients on the same pool.
```
**What matters:** **flags** carry the "how to decode" metadata; the server treats the value as opaque. Cross-language pools must agree on transcoding/flags or they'll read garbage.

### 5.6 Negative caching + stampede protection (advanced look-aside)

```java
// Cache "not found" briefly to stop a hot missing key from hammering the DB,
// and use a short lock key to prevent a thundering herd on expiry.
public User getUserSafe(MemcachedClient mc, long id, UserRepository db) {
    String key = "user:v1:" + id;
    Object v = mc.get(key);
    if (v instanceof User) return (User) v;
    if (v instanceof Tombstone) return null;          // negative cache HIT

    // Stampede guard: only one caller per key gets to rebuild.
    String lock = key + ":lock";
    boolean iWon = mc.add(lock, 5, "1").getStatus().isSuccess(); // 5s lease
    if (!iWon) {
        sleep(20); return getUserSafe(mc, id, db);    // back off & retry the cache
    }
    try {
        User fresh = db.findById(id);
        if (fresh != null) mc.set(key, 300, fresh);
        else mc.set(key, 30, new Tombstone());        // short negative TTL
        return fresh;
    } finally {
        mc.delete(lock);
    }
}
```
**What matters:** the `add`-based **lease/lock** ensures only one rebuilder runs per missing key (Facebook's "lease" idea in miniature, §7.3); the **tombstone** stops repeated misses for a non-existent id from stampeding the DB.

### 5.7 Raw protocol over `nc`/`telnet` (ops & debugging)

```bash
# Talk the ASCII protocol directly — invaluable for prod debugging.
printf 'set greeting 0 60 5\r\nhello\r\n' | nc -q1 127.0.0.1 11211   # -> STORED
printf 'get greeting\r\n'                | nc -q1 127.0.0.1 11211   # -> VALUE greeting 0 5 / hello / END
printf 'stats\r\n'                       | nc -q1 127.0.0.1 11211 | egrep 'get_hits|get_misses|evictions|bytes|curr_items'
printf 'stats slabs\r\n'                 | nc -q1 127.0.0.1 11211   # per-class chunk sizes & usage
printf 'stats items\r\n'                 | nc -q1 127.0.0.1 11211   # per-class evictions & item ages
printf 'flush_all\r\n'                   | nc -q1 127.0.0.1 11211   # DANGER: wipes everything
```
**What matters:** Memcached's protocol is human-readable; you can diagnose hit ratio, evictions, and calcification with nothing but `nc`.

### 5.8 Spring Boot integration via spymemcached (a `Cache` abstraction sketch)

```java
// Wire a MemcachedClient bean, then wrap it behind Spring's Cache SPI or use
// simple-spring-memcached (SSM) for @Cacheable-style annotations.
@Configuration
public class CacheConfig {
    @Bean(destroyMethod = "shutdown")
    public MemcachedClient memcachedClient() throws Exception {
        return new MemcachedClient(
            new KetamaConnectionFactory(),
            AddrUtil.getAddresses("cache-1:11211 cache-2:11211 cache-3:11211"));
    }
}
// With simple-spring-memcached you can then annotate:
// @ReadThroughSingleCache(namespace="user", expiration=300)
// public User getUser(@ParameterValueKeyProvider long id) { return db.findById(id); }
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Network is usually the bottleneck, not CPU.** A single Memcached box can push hundreds of thousands to low millions of ops/sec; you'll typically saturate the NIC or hit the client's round-trip limit first. **Batch with multiget** and **pipeline**; use `noreply` for bulk sets.
- **Set `-t` near core count** but expect diminishing returns past ~8 threads for many workloads; the hash-table and LRU locks eventually contend.
- **Use Unix domain sockets** for same-host clients (sidecar pattern) to skip the TCP stack.
- **Keep values small.** Big values waste slab chunks and chew bandwidth; compress > a few KB.
- **Connection reuse / pooling.** Don't open a connection per request. spymemcached multiplexes; xmemcached pools.
- **Co-locate via routing layer** (mcrouter) to cut per-app connection counts: 10,000 app processes × 100 servers = 1M connections without a proxy.

### 6.2 Correctness & concurrency

- **Prefer delete-on-write over set-on-write.** Setting the cache after a DB write invites a race: reader A reads old DB value, then writer commits new value + sets cache, then A sets cache with the *old* value → stale forever. Deleting forces the next reader to repopulate from the now-current DB. (For the truly paranoid, Facebook uses **leases**, §7.3.)
- **Cache stampede / thundering herd.** When a hot key expires, thousands of concurrent misses hit the DB at once. Mitigations: per-key rebuild lock (§5.6), **probabilistic early expiration** (XFetch — recompute slightly before TTL with rising probability), or serving slightly-stale on a soft TTL while one worker refreshes.
- **No cross-key atomicity.** Memcached has CAS per key but no multi-key transactions. Don't design invariants that span keys.
- **Eventual inconsistency is inherent.** A cache can always be stale within the TTL window; design features to tolerate it (don't cache balances you'll spend, for instance).

### 6.3 Memory

- **`-m` is item data only.** Connection buffers, the hash table, item headers, and slab overhead are extra — provision the host with **headroom (e.g., size the VM 20–30% above `-m`)**.
- **Per-item overhead is ~48–80 bytes** (item header + key + suffix + CAS). For millions of tiny items, overhead can dominate — measure with `stats slabs`.
- **Tune the growth factor (`-f`)** to your item-size distribution; inspect `stats sizes` once (it briefly locks) to see the histogram, then pick `-f` to minimize internal fragmentation for your common sizes.
- **Watch for calcification** (§3.3) after workload shifts; ensure `slab_automove` is on and monitor per-class evictions.

### 6.4 Security

- **Never expose Memcached to the internet.** It has *no authentication by default* in the ASCII protocol. Bind to localhost/private subnets (`-l`), use security groups/firewalls.
- **Disable UDP (`-U 0`).** UDP Memcached was abused for massive DDoS amplification (the 2018 "memcrashed" attacks reached 1.3+ Tbps against GitHub). Modern packages ship UDP off by default; verify.
- **SASL authentication** is available with the **binary protocol** (`-S`), giving username/password — use it in shared/multi-tenant environments. TLS is supported in recent versions (1.5.13+) via `-Z`/`-o ssl_*` but adds latency; many deployments rely on network isolation instead.
- **Drop privileges (`-u`)**; don't run as root.

### 6.5 Observability

- Scrape `stats` regularly; alert on **hit ratio drop**, **rising evictions**, **rising expirations-before-fetch**, **connection count**, and **bytes vs limit**.
- Use `stats slabs` / `stats items` to spot calcification and per-class pressure.
- Use **`watch`** (1.5.6+) to live-tail fetchers/mutations/evictions during an incident.
- Export to Prometheus via `memcached_exporter`; the standard dashboard graphs hit ratio, evictions, and memory by slab class.

### 6.6 Cost

- RAM is the cost driver; **extstore** (RAM for keys/hot values, SSD for cold values) can cut cost dramatically for large-footprint caches at the price of higher cold-read latency.
- Right-size: an over-provisioned cache with near-zero evictions wastes money; a tiny one with high evictions wastes DB capacity. Target evictions near zero for hot data while keeping memory utilization high.

### 6.7 Testing

- **Local:** run `memcached -p 11211` or a Testcontainers `memcached` image in integration tests; never mock the protocol away entirely — bugs live in serialization/flags/TTL behavior.
- **Failure injection:** kill a node mid-test to verify your client's failover and that you don't cache nulls forever.
- **Load test** with `memtier_benchmark` or `mc-crusher` to find the real ceiling and validate slab sizing under your size distribution.

### 6.8 Production hardening checklist

- `-U 0` (UDP off), `-l <private>` (bound), firewalled, `-u nobody`.
- `slab_automove=1`, `lru_maintainer`, `lru_crawler` on (modern defaults).
- Monitoring + alerts on evictions/hit-ratio/memory.
- Client: ketama hashing, sane op timeouts, node-failure handling, no "cache null forever."
- Capacity headroom on the host above `-m`.
- A documented, *deliberate* TTL strategy per key family (no implicit `0`/never-expire by accident).

### 6.9 Anti-patterns

| Anti-pattern | Why it hurts | Do instead |
|---|---|---|
| `set`-on-write invalidation | Stale-forever race | `delete`-on-write (or leases) |
| Treating cache as a database | No persistence/replication → data loss on restart | Use Redis/DB for SoT |
| No consistent hashing | Topology change = full miss storm | Ketama |
| Caching huge values | Slab waste, bandwidth, > 1 MB rejects | Compress / split / store reference |
| One `get` per key in a loop | N round-trips | Multiget (`getBulk`) |
| TTL `0` everywhere | Memory never frees by expiry; only evictions | Set deliberate TTLs |
| Mixed clients with different hashing | Cross-client misses | Standardize ketama params |
| Exposing to internet / UDP on | DDoS amplification, data theft | Bind private, `-U 0`, SASL/TLS |
| Caching nulls without bounding | Negative cache never clears | Short negative TTL + tombstones |

---

## 7. Advanced topics & deep internals

### 7.1 extstore — the SSD tier in depth

extstore keeps **all keys and item metadata in RAM** (so lookups are still O(1) and you can tell hit/miss instantly) but writes **cold item *values* to a flash file**. The RAM item holds a pointer (page/offset) into the extstore device. A `get` for a flashed item triggers an async read from SSD. Tunables: `ext_path=/data/ext:64G`, `ext_item_size` (don't flash tiny items — overhead exceeds savings), `ext_low_ttl` (don't flash short-lived items), `ext_recache_rate` (promote hot flashed items back to RAM), `ext_max_frag` (compaction threshold; the flash file fragments like any log-structured store and gets compacted). This buys you, e.g., a 1 TB effective cache on a box with 64 GB RAM — but it is **not durability**; restart still wipes everything.

### 7.2 Segmented-LRU tuning and the active/inactive heuristic

`lru tune <pct_hot> <pct_warm> <cold_age_limit> <warm_age_limit>` lets you bias how much of a class's memory the HOT/WARM segments may occupy and how aggressively items age into COLD. The maintainer thread bumps items between segments using the **fetched bit** (was it read since insertion?). The whole design exists to (a) make `get` cheap (no list relink on every read), (b) resist **scan pollution** (a one-time bulk scan parks items in HOT/COLD and never promotes them to WARM, so they don't displace your real working set), and (c) bound lock contention.

### 7.3 Facebook/Meta's "Scaling Memcache" — the canonical large-scale story

Facebook's 2013 NSDI paper *"Scaling Memcache at Facebook"* is the definitive source for operating Memcached at planet scale (millions of ops/sec, thousands of servers). Key inventions every senior engineer should know:

- **Leases.** To fight stale sets and thundering herds: on a miss, the server hands the client a **lease token**. Only a client holding a valid lease may `set` the key, and a lease is invalidated by any intervening `delete`. This (a) ensures only one client repopulates a hot missing key (herd control) and (b) prevents a client from writing a stale value it computed before a delete (stale-set control). This is a server-side primitive Facebook added; vanilla open-source Memcached approximates it with the `add`-lock trick (§5.6).
- **The "multiget hole."** Fanning a multiget across many servers makes the request as slow as the slowest server and increases incast congestion (many servers reply at once to one client, overwhelming the switch buffer). Facebook mitigated with **sliding-window flow control** on outstanding requests and careful pool design.
- **Regional pools & replication tiers.** Within a region, frequently-accessed, low-update data is held in a shared **regional pool** to avoid replicating it in every front-end cluster (saving RAM), while high-churn data is replicated per front-end cluster for latency. Cross-region, a **mcsqueal** pipeline tails MySQL replication and broadcasts invalidations so caches in remote regions delete stale keys.
- **Cold-cluster warmup.** A freshly started cluster with an empty cache would crush the DB; Facebook lets a cold cluster fill itself from a warm cluster's cache for a few minutes, with a **two-second hold-off / delete-then-add** rule to avoid the cold cluster caching a value that's already been invalidated in the warm one.
- **Gutter pools.** When a memcached server fails, instead of letting all its keys stampede the DB, clients temporarily route to a small idle **gutter** pool that absorbs the misses with short TTLs until the failed node returns. This caps the blast radius of a single node failure.

> These are *operational patterns layered on top of* Memcached, not features of the stock server (except leases, which Facebook added in their fork). Understanding them is the difference between "I can use Memcached" and "I can run Memcached at scale."

### 7.4 The meta protocol (1.6+)

The newer **meta commands** (`mg`=meta-get, `ms`=meta-set, `md`=meta-delete, `ma`=meta-arithmetic) pack many behaviors into flag tokens on one line: fetch value + CAS + TTL + last-access + hit-before flags in a single `mg`; do "get, and if miss, set a placeholder with a win/lose token" (a built-in **anti-stampede / recache** mechanism replacing the manual lease trick); atomically `gat`; conditionally store with CAS; auto-vivify counters. It's more efficient (fewer round-trips) and exposes lease-like and early-recompute features natively. Worth adopting in new Java clients that support it.

### 7.5 Hash table internals & expansion

Items live in a **chained hash table** (`hashtable[hash(key) & (size-1)]` → linked list of colliding items). When the average chain length exceeds ~1.5, a **background expansion** thread doubles the table (`hashpower++`) and **incrementally rehashes** buckets so the server doesn't stall. You can set the initial `hashpower` to skip early expansions for a known large dataset.

### 7.6 Binary vs ASCII vs meta protocol

ASCII is human-readable and ubiquitous; the **binary protocol** added compact framing, opaque request IDs (for better pipelining), and SASL auth; the **meta protocol** supersedes both for new development with flag-based extensibility. Some operations (like SASL) historically required binary. Most performance differences are marginal vs. ASCII for typical workloads; choose based on features (SASL → binary; modern features → meta).

### 7.7 Time, TTL, and clock subtleties

TTLs ≤ 2,592,000 s (30 days) are relative; larger values are absolute Unix timestamps. Memcached caches a coarse internal clock updated ~once/sec, so expiry resolution is ~1 second, not sub-second. A `flush_all <delay>` sets a future flush time; items set after the flush time survive (a subtle gotcha during cache resets).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Memcached vs Redis (the perennial comparison)

| Dimension | Memcached | Redis |
|---|---|---|
| Data model | Opaque blobs only | Strings, lists, sets, sorted sets, hashes, streams, bitmaps, HLL, geo |
| Persistence | None | Optional RDB snapshots + AOF log |
| Replication / HA | None native (mcrouter fan-out, or vendor) | Built-in replicas, Sentinel, Cluster |
| Threading | Multi-threaded (scales per core) | Single-threaded core (Redis 6+ adds I/O threads); one core for command exec |
| Max value | 1 MB default (tunable) | 512 MB |
| Eviction | Per-slab LRU (segmented) | Many policies (LRU, LFU, TTL, random, noeviction) |
| Atomic scripting | No | Lua / Functions |
| Pub/Sub, streams | No | Yes |
| Memory efficiency for tiny string blobs | Often higher (slab + low overhead) | Higher per-key overhead, but jemalloc + types |
| Operational simplicity | Very high (dumb server) | More features = more to operate |
| Sharding | Client-side (ketama) / mcrouter | Redis Cluster (server-side slots) |
| CAS | Yes (per key) | Yes (`WATCH`/`MULTI`, optimistic) |
| Multi-get efficiency | Excellent (multiget) | `MGET`, pipelining |
| Typical use | Pure look-aside cache | Cache **and** datastore, queues, leaderboards, locks |

**Rule of thumb:** *If you only need a fast, ephemeral look-aside cache of blobs and want the simplest, most horizontally scalable server — Memcached. The moment you need persistence, data structures, replication, pub/sub, or server-side logic — Redis.* Memcached can edge out Redis on raw throughput for pure GET/SET on multi-core boxes and can be more memory-efficient for many small fixed-ish-size values.

### 8.2 Memcached vs a local in-process cache (Caffeine/Guava/Ehcache)

| | Memcached (remote) | Caffeine (in-JVM) |
|---|---|---|
| Latency | ~0.1–1 ms (network) | ~nanoseconds (heap) |
| Shared across instances | Yes | No (each JVM has its own) |
| Capacity | Large, scalable across nodes | Bounded by JVM heap |
| Coherence | One copy, fewer staleness paths | N copies → N× staleness, harder invalidation |
| GC pressure | None (off-JVM) | On-heap caches stress the GC |
| Failure isolation | Separate process | Dies with the JVM |

**Common pattern: two-tier (near + far).** A small in-JVM Caffeine cache (L1) in front of Memcached (L2) gives nanosecond hits for the hottest keys and shared capacity behind it — at the cost of L1 coherence (each JVM may briefly hold a stale value).

### 8.3 Use-when / avoid-when

**Use Memcached when:** ephemeral blob cache; need horizontal scale and a simple ops model; multi-core throughput matters; values < 1 MB; no need for persistence/types/replication.

**Avoid Memcached when:** you need durability, rich types, native replication/HA, server-side atomic logic across keys, or values you can't regenerate. Then choose Redis (or a database) — or layer Memcached *with* one of them.

### 8.4 Sizing decision framework

1. **Estimate working set:** number of hot keys × (avg value size + ~60 B overhead). Round each value up to its slab chunk for realism.
2. **Pick `-m`** to hold the working set with **near-zero evictions for hot data** plus headroom; provision the host ~25% above `-m`.
3. **Choose `-f`** from your size histogram (`stats sizes`) to minimize internal fragmentation.
4. **Set TTLs** per key family from staleness tolerance, *not* from memory pressure (let eviction handle pressure).
5. **Node count:** capacity ÷ per-node RAM; more nodes = smaller blast radius per failure but more multiget fan-out. Balance.
6. **Verify under load** with memtier; watch evictions and per-class pressure.

---

## 9. Failure modes & debugging

### 9.1 High eviction rate / low hit ratio

- **Symptom:** `evictions` rising, hit ratio dropping, DB load climbing.
- **Diagnose:** `stats` → check `evictions`, `bytes` vs `limit_maxbytes`; `stats items` → which **class** is evicting (`evicted`, `evicted_time`, `outofmemory`).
- **Causes & fixes:** undersized `-m` (add memory/nodes); **slab calcification** (one class evicts while others hold free pages → enable/raise `slab_automove`, or `slabs reassign`); bad TTLs over-filling memory; a key explosion (cardinality blew up).

### 9.2 Slab calcification after a workload change

- **Symptom:** After a deploy that changed value sizes, one slab class evicts hard while overall utilization looks low.
- **Diagnose:** `stats slabs` (free vs used chunks per class) + `stats items` (evictions per class). Free pages stuck in the wrong class.
- **Fix:** `slabs automove 2` temporarily (aggressive page steal), or manual `slabs reassign <src> <dst>`; long-term tune `-f` and keep automove on.

### 9.3 Thundering herd on hot-key expiry

- **Symptom:** Periodic DB CPU spikes synchronized with a key's TTL boundary.
- **Diagnose:** correlate DB spikes with cache `expired`/miss bursts; `watch fetchers` to see the burst.
- **Fix:** per-key rebuild lock (§5.6), probabilistic early recompute, meta-protocol recache flags, or jittered TTLs (add randomness so keys don't expire simultaneously).

### 9.4 Stale data after writes

- **Symptom:** Users see old data after an update.
- **Diagnose:** check invalidation path; look for `set`-on-write races.
- **Fix:** switch to `delete`-on-write; shorten TTL; consider leases/meta-CAS; ensure the invalidation actually targets the correct (versioned) key and the correct *server* (hashing consistency).

### 9.5 Topology-change miss storm

- **Symptom:** Adding/removing a node tanks the hit ratio fleet-wide.
- **Cause:** client not using consistent hashing (plain modulo), or clients disagree on the server list / ketama params.
- **Fix:** standardize on ketama; ensure all clients share identical server ordering/weights/hash; use mcrouter or AWS Auto Discovery so the pool view is consistent.

### 9.6 The multiget hole / incast congestion (at scale)

- **Symptom:** p99 latency on big multigets spikes; switch buffers overflow when many servers reply to one client at once.
- **Diagnose:** correlate large fan-out requests with tail latency; inspect NIC/switch drop counters.
- **Fix:** limit fan-out per request, sliding-window flow control on outstanding requests, fewer/larger nodes to reduce fan-out, or replicate hot small data into a regional pool (Facebook's approach).

### 9.7 Connection exhaustion

- **Symptom:** `curr_connections` near `-c`; new clients refused (`SERVER_ERROR` / connection errors).
- **Diagnose:** `stats` → `curr_connections`, `total_connections`, `listen_disabled_num` (times the server stopped accepting due to the limit).
- **Fix:** raise `-c`, fix client connection leaks/pooling, introduce mcrouter to collapse N×M connections.

### 9.8 The memcrashed DDoS (real incident)

- **What happened (2018):** attackers spoofed source IPs and sent tiny `stats`/`get` UDP requests to internet-exposed Memcached servers, which replied with huge responses to the victim — an amplification factor of ~10,000–50,000×, peaking at **1.3+ Tbps** against GitHub.
- **Root cause:** UDP enabled + servers reachable from the internet + no auth.
- **Fix/lesson:** **`-U 0`** (UDP off — now default), bind to private interfaces, firewall port 11211. This is *the* reason "never expose Memcached" is dogma.

### 9.9 Restart = total cache loss (and the cold-start stampede)

- **Symptom:** After a deploy/restart, the DB is hammered as the empty cache refills.
- **Fix:** rolling restarts (one node at a time so ~1/N of keys miss, not all), cold-cluster warmup from a warm peer (Facebook), gutter pools to absorb misses, request coalescing during warmup.

---

## 10. Interview drill

**Q1. What is Memcached and when would you choose it over Redis?**
*Model answer:* A distributed in-memory key-value cache: blobs only, no persistence, no replication, no rich types, multi-threaded, with client-side sharding via consistent hashing. Choose it for a pure, ephemeral look-aside cache where you want maximum throughput and operational simplicity; choose Redis when you need persistence, data structures, native replication/HA, pub/sub, or server-side scripting.
- *Follow-up: Why is multi-threading an advantage over single-threaded Redis?* It scales near-linearly with cores on one box, so a single instance can serve more throughput before you must shard; Redis (pre-6) used one core for command execution.
- *Follow-up: When is Memcached more memory-efficient?* For many small, roughly fixed-size values, slab allocation + ~60 B overhead can beat Redis's per-key overhead and type machinery.
- *Follow-up: Name one thing Redis does that Memcached fundamentally cannot.* Survive a restart with its data (persistence), or offer server-side atomic multi-key logic.

**Q2. Explain the slab allocator and why Memcached uses it.**
*Model answer:* Memory is carved into 1 MB pages assigned to slab classes; each class has a fixed chunk size growing geometrically by factor `-f` (default 1.25). Items round up to the smallest fitting chunk. This gives O(1) allocation and eliminates external fragmentation, at the cost of bounded internal fragmentation (~up to 20% with `-f 1.25`).
- *Follow-up: What's the downside?* Internal fragmentation and slab calcification.
- *Follow-up: How do you tune fragmentation?* Lower `-f` for finer classes; inspect `stats sizes` and `stats slabs`.
- *Follow-up: What's the per-item overhead?* ~48–80 bytes (header + key + suffix + CAS).

**Q3. What is slab calcification and how do you fix it?**
*Model answer:* Pages assigned to a class stay there; if the workload's size distribution shifts, a now-hot class starves and evicts while another class holds free pages → low utilization + high targeted evictions. Fix with `slab_automove` (background page rebalancing, default on in modern versions), `slabs reassign` manually, and tuning `-f`.
- *Follow-up: How do you detect it?* Per-class evictions in `stats items` vs free chunks in `stats slabs`.
- *Follow-up: automove mode 1 vs 2?* 1 is conservative (only fully-free pages); 2 is aggressive (will evict to free a page faster).

**Q4. How does Memcached distribute keys across servers, and why consistent hashing?**
*Model answer:* The **client** hashes the key and maps it to a server. With plain `hash % N`, changing N remaps ~all keys → miss storm. Consistent hashing (ketama: ~160 MD5-derived virtual nodes per server on a ring; walk clockwise to the first node) remaps only ~1/N of keys on topology change.
- *Follow-up: What are virtual nodes for?* Smooth load distribution and weight support — not data replicas.
- *Follow-up: Why must all clients agree on ketama params?* Otherwise different clients map the same key to different servers → permanent misses.
- *Follow-up: Alternative to client-side hashing?* A routing proxy like mcrouter, or AWS ElastiCache Auto Discovery.

**Q5. Walk through the look-aside read and write paths. Why delete-on-write, not set-on-write?**
*Model answer:* Read: get → on miss, query DB, set cache, return. Write: update DB, then **delete** the key. Delete-on-write avoids a race where a reader's stale value overwrites the cache after the writer's update, leaving a permanently stale entry; deleting forces a fresh repopulation.
- *Follow-up: How would you fully eliminate the stale-set race?* Leases (Facebook): server issues a token on miss; only the lease holder may set, and any delete invalidates the token.
- *Follow-up: How handle cache stampede on expiry?* Per-key rebuild lock, probabilistic early recompute, jittered TTLs, or meta-protocol recache.

**Q6. What is multiget and why does it matter? What's the catch at scale?**
*Model answer:* A single request for many keys; the client groups keys by destination server and pipelines one request per server, collapsing N round-trips into ~one per server — a huge latency win. The catch is the **multiget hole**: a fan-out across many servers is as slow as the slowest, and many simultaneous replies cause **incast congestion** at the switch.
- *Follow-up: How mitigate?* Limit fan-out, sliding-window flow control, fewer larger nodes, regional pools for hot small data.

**Q7. How does eviction and expiration actually work?**
*Model answer:* Expiration is **lazy** (checked on access) plus a background **LRU crawler** that reaps expired items. Eviction happens **per slab class** from the COLD segment tail of a **segmented LRU** (HOT/WARM/COLD/TEMP) when a class needs a chunk and has none free. Evictions ≠ expirations — evictions throw out *live* items under pressure.
- *Follow-up: Why segmented LRU?* Cheaper reads (no relink per get), scan-pollution resistance, less lock contention.
- *Follow-up: Why per-class, not global LRU?* Because chunks aren't interchangeable across classes; you can only evict within the class that needs the chunk.

**Q8. (Senior signal) You added 3 nodes to a 9-node pool and the hit ratio collapsed for 20 minutes. Diagnose and prevent.**
*Model answer:* With consistent hashing, adding 3 to 9 should remap only ~25% of keys — a temporary, bounded dip, not a collapse. A *collapse* suggests either non-consistent hashing (modulo), or clients disagreeing on the new server list (rolling config rollout → split-brain mapping), causing keys to land on the wrong node fleet-wide. Prevent with ketama everywhere, atomic/coordinated pool-config updates (or mcrouter/Auto Discovery as the single source of truth), pre-warming, and rolling capacity changes one node at a time.
- *Follow-up: Why is a 25% dip even expected?* Those keys move to new (empty) nodes and must repopulate from the DB.
- *Follow-up: How protect the DB during that dip?* Rebuild locks, gutter pools, request coalescing, warmup-from-peer.

**Q9. (Senior signal) Design a caching tier for a read-heavy product catalog: 50M products, ~2 KB each, 200k QPS, p99 < 5 ms, must tolerate node failure without crushing the DB.**
*Model answer:* Working set ≈ hot subset, say 5M hot × (2 KB + 60 B) ≈ ~10 GB; size nodes with headroom and shard across several to bound failure blast radius. Use ketama; mcrouter for routing/failover with a gutter pool. Use multiget for batch reads but cap fan-out. Delete-on-write invalidation from the catalog service. Per-family TTLs with jitter; rebuild locks for hot keys. Consider an L1 Caffeine near-cache for the very hottest SKUs. Monitor evictions/hit-ratio; `slab_automove` on. On node failure, mcrouter TKO + gutter absorbs misses so the DB isn't stampeded.
- *Follow-up: Memcached or Redis here?* Memcached fits — blobs, ephemeral, throughput, simple ops; no need for types/persistence.
- *Follow-up: How keep p99 under 5 ms during a failover?* Short client timeouts + gutter pool so a dead node's keys miss into gutter (fast) instead of timing out, plus L1 absorbing the hottest keys.

**Q10. (Senior signal) Justify a two-tier (in-JVM + Memcached) cache and its risks.**
*Model answer:* L1 (Caffeine) gives nanosecond hits for the hottest keys and shields Memcached/the network; L2 (Memcached) provides shared, large capacity and a single coherent copy. The risk is **L1 coherence**: each JVM may hold a stale value past an invalidation. Bound it with short L1 TTLs, small L1 size (only the hottest keys), and optionally a broadcast invalidation channel. Watch L1's GC impact (prefer bounded, off-heap, or Caffeine's efficient on-heap with size limits).
- *Follow-up: When is L1 a net loss?* If the hot set is broad/uniform (low L1 hit ratio) or staleness is intolerable.

**Q11. How would you secure a Memcached deployment?**
*Model answer:* Bind to private interfaces (`-l`), firewall port 11211, **`-U 0`** (UDP off — recall memcrashed), run as non-root (`-u`), use SASL (binary protocol, `-S`) in shared/multi-tenant setups, optionally TLS (`-Z`) though network isolation is the common control. Never internet-exposed.
- *Follow-up: Why is UDP a special risk?* DDoS amplification (spoofed source → giant reply to victim; ~10,000×+).

**Q12. What's extstore and how does it change the persistence story?**
*Model answer:* extstore keeps keys/metadata (and hot values) in RAM but spills cold values to flash, multiplying effective capacity per dollar with async SSD reads on cold hits. It is **not** persistence — a restart still wipes everything; it's a RAM-extension tier.
- *Follow-up: Tuning?* `ext_item_size` (don't flash tiny items), `ext_low_ttl`, `ext_recache_rate`, `ext_max_frag` (compaction).

---

## 11. Glossary

- **Add:** store only if the key is absent (atomic create).
- **ASCII protocol:** human-readable text Memcached protocol.
- **AOF (Redis):** append-only log of writes for persistence (contrast: Memcached has none).
- **Auto Discovery (AWS):** ElastiCache feature where clients learn the node list from a config endpoint.
- **Binary protocol:** compact binary Memcached protocol; supports SASL.
- **CAS (compare-and-swap):** optimistic concurrency via a version token (`gets`/`cas`).
- **Calcification (slab):** pages stuck in a class that no longer needs them while another starves.
- **Chunk:** a fixed-size slot within a slab class.
- **Cold-cluster warmup:** filling a fresh cache from a warm peer to avoid DB stampede.
- **Consistent hashing:** key→node mapping that moves only ~1/N keys on topology change.
- **extstore:** optional SSD tier extending RAM capacity (not persistence).
- **Eviction:** discarding a *live* item under memory pressure (per-class, from COLD tail).
- **Event loop / libevent:** readiness-based I/O multiplexing (`epoll`/`kqueue`) so one thread serves many sockets.
- **Expiration:** item past its TTL; reclaimed lazily on access and by the LRU crawler.
- **Flags:** client-defined integer metadata stored with a value (e.g., "compressed").
- **`flush_all`:** expire all items (optionally after a delay).
- **Growth factor (`-f`):** ratio between successive slab class sizes (default 1.25).
- **Gutter pool:** small standby pool absorbing misses when a node fails (Facebook).
- **Hash table (item):** chained buckets mapping key→item; auto-expands.
- **Hit / miss / hit ratio:** found / not found / fraction found.
- **Incast congestion:** many servers replying simultaneously overwhelm a switch buffer.
- **Internal fragmentation:** wasted space inside an over-sized chunk.
- **Ketama:** the standard MD5-based consistent-hashing ring for Memcached clients.
- **Lease:** Facebook server-side token controlling who may repopulate a key (herd + stale-set control).
- **Look-aside (cache-aside):** app checks cache, falls back to DB on miss, then populates cache.
- **LRU (Least Recently Used):** evict the least-recently-accessed item.
- **LRU crawler:** background thread reaping expired items.
- **LRU maintainer:** background thread managing segmented-LRU (HOT/WARM/COLD/TEMP).
- **mcrouter:** Facebook's Memcached protocol router/proxy (pools, failover, TKO, replication).
- **memcrashed:** 2018 UDP amplification DDoS via exposed Memcached.
- **Meta protocol:** flag-rich 1.6+ commands (`mg`/`ms`/`md`/`ma`) with built-in recache/lease-like features.
- **Multiget:** one request for many keys, fanned out per server.
- **`noreply`:** suppress the server's response to a mutation.
- **NIO:** Java non-blocking I/O used by async clients (spymemcached).
- **Page (slab):** 1 MB unit of memory assigned to one slab class.
- **Persistence:** durability across restarts — Memcached has none.
- **Regional pool:** shared cache for low-churn data within a region (saves RAM).
- **SASL:** authentication mechanism for the binary protocol.
- **Scan pollution:** a one-time bulk read evicting your real working set; segmented LRU resists it.
- **Slab / slab class / slab allocator:** fixed-size memory management scheme that avoids external fragmentation.
- **Source of truth (SoT):** the authoritative store (e.g., the database).
- **Stampede / thundering herd:** many concurrent misses on a hot key flooding the DB.
- **TKO ("technical knockout"):** mcrouter marking a failing node down to reroute traffic.
- **Tombstone / negative caching:** caching "not found" briefly to absorb misses for missing keys.
- **Transcoder:** client component that serializes/compresses values and records flags.
- **TTL (time to live):** expiry duration (relative ≤ 30 days; else absolute timestamp).
- **Virtual node (vnode):** multiple ring positions per server for smooth distribution.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**What:** distributed in-memory blob cache. No persistence, no replication, no types, multi-threaded, client-side sharding.
**Limits:** key ≤ 250 B; value ≤ 1 MB (`-I` to change); per-item overhead ~48–80 B.
**Defaults:** `-m 64` MB, `-p 11211`, `-t 4`, `-f 1.25`, port 11211 TCP(+UDP). **Set `-U 0`.**
**Memory:** 1 MB pages → slab classes (× `-f`) → chunks; smallest-fitting chunk; bounded internal frag (~20% at 1.25).
**Calcification:** pages stuck in wrong class → fix with `slab_automove` (1 default, 2 aggressive), `slabs reassign`.
**LRU:** per-class, segmented HOT/WARM/COLD/TEMP; lazy expiry + LRU crawler.
**Hashing:** ketama consistent hashing (~160 vnodes/server, MD5 ring); ~1/N keys move on topology change.
**Patterns:** look-aside; **delete-on-write** (not set); multiget for batches; CAS for RMW; negative cache + rebuild lock for stampedes.
**Key stats:** `get_hits`/`get_misses`, `evictions`, `bytes` vs `limit_maxbytes`, `stats slabs`, `stats items`.
**Scale (Facebook):** leases, regional pools, gutter pools, cold-cluster warmup, mcrouter, multiget-hole control.
**vs Redis:** pick Memcached for pure ephemeral blob cache + throughput + simplicity; Redis for persistence/types/replication/scripting.
**Security:** bind private, `-U 0`, `-u nobody`, SASL (binary)/TLS; never internet-exposed (memcrashed: 1.3 Tbps).
**extstore:** RAM for keys/hot values, SSD for cold values — bigger cache, NOT persistence.

**Decision rules:**
- Need durability/types/replication/scripting? → not Memcached.
- Topology will change? → consistent hashing, mandatory.
- Batch reads? → multiget, but cap fan-out.
- Hot key + DB protection? → rebuild lock / leases / jittered TTL.
- Evictions rising? → more memory or check calcification.

### 12.2 Self-test (no answers — active recall)

1. Trace exactly what happens inside the server, step by step, when a `set` lands in a slab class that has no free chunk and `slab_automove=1`. Where does the page come from, and what gets evicted?
2. Two services (Java and PHP) share one Memcached pool but a fraction of keys consistently miss across them. List every configuration cause and how you'd confirm each.
3. Derive the worst-case internal fragmentation percentage for `-f 1.5`, and explain the throughput/memory tradeoff of lowering it to `1.08`.
4. Design a stampede-proof read path for a key read 50k times/sec with a 60 s TTL, using only stock open-source Memcached (no leases). Justify each mechanism and its failure mode.
5. You must grow a 6-node pool to 10 nodes during peak traffic with minimal hit-ratio impact and zero DB overload. Write the rollout plan and name the metrics you watch at each step.
6. Explain why Memcached uses *per-class* LRU rather than one global LRU, and what would break if it tried a single global LRU across slab classes.
7. When would extstore *hurt* rather than help, and which `ext_*` knobs would you measure before enabling it in production?

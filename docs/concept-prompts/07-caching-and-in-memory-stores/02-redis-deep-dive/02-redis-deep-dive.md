# Redis Deep Dive

> **Concept area:** Caching & In-Memory Stores
> **Subtopic:** Redis Deep Dive
> **Reader profile:** Senior Java/JVM backend developer who wants to fully master Redis — design with it, operate and debug it in production, teach it, and answer any interview question on it.

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

### What Redis is

**Redis** (REmote DImctionary Server) is an **in-memory data structure store**. The most accurate one-line description: it is a network-attached server that holds a set of **named data structures** (strings, hashes, lists, sets, sorted sets, streams, etc.) entirely in RAM, and exposes a compact text/binary protocol over TCP so that many clients can manipulate those structures with **atomic commands**. It is most commonly used as a **cache**, a **broker/queue**, a **session store**, a **rate limiter**, a **leaderboard engine**, a **distributed lock manager**, and a lightweight **message bus**.

The crucial mental distinction: Redis is **not** "memcached with extra commands" and it is **not** "a SQL database that happens to be fast." It is a *data-structure server*. The unit of value is not a row or a blob — it is a rich, server-side data structure that you mutate in place with operations whose semantics live on the server. `INCR counter` atomically increments an integer; `ZADD board 100 alice` inserts into a sorted-by-score collection; `LPUSH q job` prepends to a list. You ship *operations*, not just bytes.

> **Term — in-memory:** the primary copy of the data lives in the process's RAM (the heap of the `redis-server` process), not on disk. Disk is used only for durability/recovery (persistence) and is off the hot path. This is the single biggest reason Redis is fast: a RAM read is ~100 ns versus ~10 ms for a random disk seek on spinning media, or ~100 µs for SSD — RAM is roughly 100,000× faster than a disk seek and ~1,000× faster than an SSD random read.

### The problem it solves

Backend systems constantly need a place to put **hot, frequently-read, latency-sensitive state** that a relational database (RDBMS) serves too slowly or too expensively. Concretely:

- **Read amplification on the primary DB.** A product page might be read 10,000×/s but written once per hour. Hitting Postgres/MySQL every time wastes the DB's most precious resource (its connection pool, its buffer cache, its IOPS). Putting the rendered result in Redis turns a 5–20 ms DB query into a 0.2 ms cache hit.
- **Operations the DB is bad at.** "Give me the top-50 players by score, updated in real time" is a `ZADD`/`ZREVRANGE` in Redis (O(log N) insert, O(log N + k) range) versus an `ORDER BY ... LIMIT` over a hot, constantly-changing table in SQL (which thrashes indexes and locks).
- **Cross-process coordination.** Distributed locks, rate limiters, leader election helpers, ephemeral session state shared across a fleet of stateless app servers.
- **Decoupling via messaging.** Pub/Sub for fire-and-forget fan-out, and **Streams** for durable, consumer-group-based work queues (a lightweight Kafka-lite).

### When you reach for Redis

Reach for Redis when **all** of these hold:
1. The working set of hot data fits (or can be made to fit) in RAM, possibly across a cluster.
2. You need single-digit-millisecond (often sub-millisecond) latency.
3. You want atomic, server-side data-structure operations rather than read-modify-write round trips.
4. You can tolerate the chosen durability/consistency profile (more on this below — Redis is **AP-leaning** and durability is tunable but never as strong as a synchronously-replicated WAL DB by default).

**Avoid** Redis as your *system of record* for data you cannot afford to lose, for data sets far larger than affordable RAM, for complex multi-key transactional invariants requiring serializable isolation across many keys/nodes, and for ad-hoc analytical querying (it has no general query planner — RediSearch/modules aside).

### One-paragraph mental model

> Imagine a single, extremely disciplined librarian (one CPU thread) standing at a counter. Patrons (clients) hand in request slips over a conveyor belt (the TCP sockets, drained by an event loop). The librarian processes **one slip at a time, to completion, in arrival order**, using a giant hash table where every key maps to a typed data structure. Because there is exactly one librarian and no other librarian can touch the shelves, **every single command is atomic for free** — no locks needed. The librarian is fast because (a) everything is in RAM, (b) the data structures are hand-optimized C, and (c) it never blocks on slow I/O on the main path. The cost: any command that takes the librarian a long time (`KEYS *`, a giant `SORT`, a multi-million-element `SMEMBERS`) **stalls everyone else**, because there is only one librarian. Persistence and replication happen by occasionally cloning the shelves (`fork()`/copy-on-write) into a background helper who writes to disk or ships changes to replicas, so the librarian is never interrupted for long. That mental model — *one thread, RAM-resident typed structures, atomic-by-construction, background helpers for durability* — explains almost every Redis behavior, performance characteristic, and footgun in this document.

---

## 2. Foundations from first principles

This section builds Redis up from zero. If you already know a term, skim; every adjacent concept is defined inline as promised.

### 2.1 The keyspace: one giant dictionary

At its core a Redis database is a single **dictionary** (a hash table) mapping a **key** (an arbitrary binary-safe string, e.g. `user:1042:profile`) to a **value object**. The value object is *typed*: it is one of a fixed set of data structures. There is no schema; any key can map to any type, but a given key has exactly one type at a time (calling a list command on a key that holds a string yields a `WRONGTYPE` error).

> **Term — hash table / dictionary:** a data structure giving average O(1) lookup of a value by key, implemented as an array of "buckets" indexed by a hash of the key, with a strategy (here, chaining via linked lists) for collisions. Redis's implementation lives in `dict.c`.

> **Term — binary-safe:** Redis keys and string values can contain any bytes, including NUL (`\0`). They are not C strings; Redis stores an explicit length. So `"foo\0bar"` is a valid 7-byte key.

A single Redis instance has **16 logical databases by default** (numbered 0–15, configurable via `databases`), selected with the `SELECT n` command. These are *not* a multi-tenancy or isolation feature — they share the same process, memory budget, and single thread, and **Redis Cluster supports only DB 0**. Treat numbered DBs as a legacy namespacing crutch; in modern designs, namespace with **key prefixes** instead (`tenantA:user:1`).

### 2.2 The ten core value types

| Type | What it is (beginner framing) | Canonical use |
|---|---|---|
| **String** | A binary-safe byte blob up to 512 MB; also used as int/float for counters. | Cache values, counters, flags, raw bytes. |
| **List** | An ordered sequence (a doubly-linked-ish structure), push/pop both ends. | Queues, stacks, recent-items. |
| **Hash** | A map from field→value inside one key (a small dictionary). | Object representation (`user:1` with fields name, email). |
| **Set** | An unordered collection of unique members. | Tags, unique visitors, set algebra (union/intersect). |
| **Sorted Set (ZSet)** | A set where each member has a floating-point **score**; kept ordered by score. | Leaderboards, priority queues, time-ordered indexes. |
| **Bitmap** | A string interpreted as a bit array; set/get/count individual bits. | Per-user boolean flags at scale (daily active users). |
| **HyperLogLog (HLL)** | A probabilistic cardinality estimator stored in a string. | Approximate unique counts over huge sets in ~12 KB. |
| **Stream** | An append-only log of entries with IDs, plus consumer groups. | Durable event/message queues, event sourcing. |
| **Geospatial** | A sorted set with geohash-encoded scores. | "Find points within radius," ride-hailing. |
| **Bitfield** | Treats a string as packed integers of arbitrary bit-width. | Compact counters/structs (e.g. many small per-user gauges). |

(Modules add more — JSON, time series, vector indexes, full-text search — covered later.)

### 2.3 Why "single-threaded" and why that's fast

The **command-execution core** of Redis is single-threaded: one thread pulls a command off a socket, executes it to completion against the in-memory structures, and writes the reply. (Modern Redis ≥6 uses **I/O threads** to parallelize *reading bytes off sockets and writing bytes back*, and background threads for slow deletes and persistence — but the *logic* that mutates your data is still one thread. We dissect this in §3.)

Why is one thread fast enough to do hundreds of thousands of operations per second?

1. **No lock contention, no context switches on the hot path.** Multi-threaded data stores spend enormous effort on mutexes, atomics, and cache-line bouncing. Redis has none of that for command execution — there's nothing to synchronize because there's one executor.
2. **RAM-resident data.** Every operation touches memory, not disk. The bottleneck is CPU and memory bandwidth, not I/O wait.
3. **Hand-tuned C data structures with multiple internal encodings.** Small structures use compact, cache-friendly layouts (`listpack`, `intset`); large ones promote to hash tables / skiplists. (Detailed in §3.)
4. **An efficient event loop** (`epoll` on Linux) that multiplexes thousands of connections on the one thread without a thread-per-connection model.

> **Term — event loop:** a programming pattern where a single thread waits (via a syscall like `epoll_wait`) for any of many file descriptors (sockets) to become readable/writable, then handles each ready one in turn, then loops. This lets one thread serve thousands of concurrent connections. Redis's loop is in `ae.c` ("a(e) event").

> **Term — epoll:** a Linux kernel mechanism (a syscall family: `epoll_create`, `epoll_ctl`, `epoll_wait`) for scalable I/O readiness notification — it tells you *which* of your registered sockets are ready, in O(ready) rather than O(total). On macOS/BSD the equivalent is `kqueue`; the abstraction lets Redis pick the best per-OS.

> **Term — syscall (system call):** a request from a user-space program into the operating-system kernel to do something privileged (read a socket, allocate memory, fork a process). Syscalls have overhead (mode switch), so minimizing them on the hot path matters; this is partly why Redis batches and why pipelining helps.

The **flip side** (memorize this — it's the source of most production incidents): **any command is blocking for the entire server while it runs.** A `KEYS *` over 50M keys, a `SMEMBERS` returning 10M elements, a `SORT` of a huge list, a Lua script with a busy loop, or a synchronous `DEL` of a 5 GB hash will freeze *every other client* for the duration. There is no preemption. Operational discipline around command complexity is therefore not optional — it's the core skill of running Redis.

### 2.4 Time complexity is part of the API contract

Because of the single thread, Redis documents the **Big-O complexity of every command**, and you must treat it as a hard constraint. Examples:

- `GET`/`SET`/`INCR`/`HGET`/`LPUSH` — O(1).
- `ZADD`/`ZSCORE`/`ZRANK` — O(log N).
- `ZRANGE key a b` — O(log N + M) where M is elements returned.
- `LRANGE key 0 -1` — O(N) (returns the whole list!).
- `SMEMBERS`/`HGETALL`/`KEYS pattern` — O(N) over the collection / keyspace. **Dangerous on large collections.**
- `SINTERSTORE`/`SORT`/`SUNIONSTORE` — can be O(N·M) or O(N log N).

The rule: **avoid O(N) commands where N is unbounded or large; prefer their cursor-based or ranged cousins** (`SCAN`/`HSCAN`/`SSCAN`/`ZSCAN` instead of `KEYS`/`HGETALL`/`SMEMBERS`/`ZRANGE 0 -1`).

### 2.5 Atomicity for free, and the absence of isolation levels

Because commands execute one-at-a-time to completion, **each individual command is atomic**. There is no partial application: `INCRBY k 5` either fully applies or not at all, and no other command interleaves with it. This is why Redis is the natural home for counters, locks, and rate limiters.

For **multi-command atomicity**, Redis offers two tools (detailed in §3 and §4):
- **`MULTI`/`EXEC` transactions** — queue commands, then execute the whole batch atomically (no other client's commands interleave). But note: Redis transactions are **not** rollback-on-error transactions in the SQL sense — see §3.6.
- **Lua scripts** (`EVAL`) and **Functions** (`FUNCTION`) — your script runs atomically as one unit on the single thread; nothing interleaves. This is the most powerful primitive for read-modify-write logic.

### 2.6 Expiration / TTL — the cache foundation

Any key can carry a **TTL (time-to-live)**: an expiration time after which Redis deletes it. This is the bedrock of caching.

```
SET session:abc "...payload..." EX 3600      # expires in 3600 s
EXPIRE user:1:rate 60                          # set/refresh TTL
TTL user:1:rate                                # seconds left (-1 no TTL, -2 missing)
PERSIST user:1:rate                            # remove TTL, make permanent
```

> **Term — TTL:** time-to-live, a per-key countdown after which the key is considered expired and removed. Redis combines **lazy expiration** (it's checked & deleted when next accessed) with **active expiration** (a background sampler periodically deletes a fraction of expired keys) — detailed in §3.7. The practical upshot: an expired key may linger in RAM briefly until sampled, so `EXPIRE`-based memory bounds are *soft*.

### 2.7 Persistence vs. cache mindset

Redis can be run in two philosophically different modes, and confusing them causes outages:

- **As a cache:** durability is unimportant; if Redis restarts and data is gone, the app refetches from the source of truth. Often run with `maxmemory` + an eviction policy and minimal/no persistence.
- **As a primary store / durable queue:** you enable persistence (RDB snapshots and/or AOF) and possibly replication, and you must understand exactly what you can lose on a crash (it's never zero by default).

We cover the full persistence machinery in §3.4–§3.5.

### 2.8 The protocol: RESP

Clients talk to Redis using **RESP** (REdis Serialization Protocol) — a simple, human-readable, line-oriented binary-safe protocol over TCP (default port **6379**). RESP2 has five types (simple string `+`, error `-`, integer `:`, bulk string `$`, array `*`); RESP3 (Redis ≥6) adds maps, sets, doubles, booleans, big numbers, verbatim strings, and **push** messages (for client-side caching invalidations and pub/sub on the same connection).

> **Term — RESP:** the wire format. A `SET foo bar` is sent as `*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n`. The leading `*3` means "array of 3"; each `$3` means "next bulk string is 3 bytes." It's trivial to parse and pipeline. RESP3's biggest practical wins are typed replies (a `HGETALL` returns a real map) and out-of-band push frames enabling **client-side caching (tracking)**.

You rarely write RESP by hand; client libraries (Jedis, Lettuce, redis-py, go-redis, etc.) do it. But understanding RESP explains pipelining (§3.3) and why a single connection can carry many in-flight requests.

---

## 3. How it works internally

This is the heart of the document. We trace, in order, the lifecycle of the server, of a single command, of persistence, of replication, and of the trickier subsystems (expiration, eviction, transactions, scripting, streams).

### 3.1 Server startup and the main loop

1. **Process start.** `redis-server /etc/redis.conf` parses the config, initializes the server struct, sets up signal handlers, and creates the **event loop** (`aeCreateEventLoop`).
2. **Load data.** If persistence files exist, it loads them: prefers AOF if `appendonly yes`, else loads the RDB file. The thread is busy and not serving clients until load completes (a multi-GB dataset can take seconds to minutes — a real startup-time consideration).
3. **Bind & listen.** It binds the TCP port (6379), optionally a Unix domain socket, and (if configured) TLS.
4. **Register handlers.** It registers the **accept handler** (for new connections) and a recurring **time event** (`serverCron`, default every 100 ms = `hz 10`) on the event loop.
5. **Enter `aeMain`** — the infinite loop. Each iteration:
   - `beforeSleep()` — flush pending client output buffers, do fast expiration of a few keys, write/flush AOF buffer per policy, process client-side-caching invalidations, handle cluster bus messages.
   - `epoll_wait()` — block until a socket is readable/writable or the next timer fires.
   - For each ready connection: **read** request bytes, **parse** RESP, **dispatch** the command, **execute** it, **buffer** the reply.
   - `afterSleep()` and fire any due **time events** (`serverCron`).

> **Term — `serverCron`:** Redis's periodic housekeeping function, run `hz` times per second (default 10, i.e. every 100 ms; tunable, and `dynamic-hz yes` scales it with connection count). It does active expiration sampling, eviction if over `maxmemory`, incremental rehashing of the main dict, client timeout checks, RDB/AOF triggering decisions, replication health, stats, and memory defrag scheduling.

### 3.2 Lifecycle of a single command (the critical trace)

Let's trace `INCR page:views:home` end to end:

1. **Bytes arrive** on the client's TCP socket. `epoll_wait` returns it readable. (In Redis ≥6 with `io-threads > 1`, dedicated **I/O threads** may do the raw `read()` and RESP parsing in parallel for many clients — but they do *not* execute commands.)
2. **Read & parse.** The reader appends to the client's query buffer and parses one full RESP command: `["INCR", "page:views:home"]`.
3. **Lookup the command table.** Redis has a static command table mapping name→`redisCommand` (function pointer, arity, flags like `write`, `denyoom`, `random`, `fast`). It validates arity and flags.
4. **Pre-execution checks (all on the main thread):**
   - **OOM check:** if the command is a write and `maxmemory` is exceeded, Redis tries **eviction**; if it can't free enough and policy forbids writes, it returns an OOM error.
   - **Cluster check:** if running in cluster mode, compute the key's **hash slot** (CRC16 mod 16384). If this node doesn't own the slot, reply `MOVED`/`ASK` redirect. (See §3.9.)
   - **Auth/ACL check:** if `requirepass`/ACLs are on, verify the connection is authorized for this command and these key patterns.
5. **Execute.** Call the command's C function (`incrCommand`). It:
   - Looks up `page:views:home` in the current DB's dict.
   - If absent, treats value as 0; if present, parses the string as an integer (errors if not numeric).
   - Increments, re-encodes (an `int`-encoded string object), stores it back.
   - This is **atomic** — nothing else runs meanwhile.
6. **Propagate the effect.** If the command mutated state (a "write"), Redis:
   - Appends it to the **AOF buffer** (if AOF on) — note: written to the buffer now, `fsync`'d later per policy.
   - Appends it to the **replication backlog** and queues it to all connected **replicas** (asynchronously).
   - Notifies **keyspace notifications** subscribers (if enabled) and invalidates **client-side caches** (if tracking enabled).
   - Increments the **`dirty`** counter (used by RDB save triggers).
7. **Reply.** The integer result (`:42\r\n`) is appended to the client's output buffer; actual `write()` to the socket happens in `beforeSleep`/by I/O threads.

Two things to burn in: **(a)** AOF and replication are appended *after* execution and *flushed/sent asynchronously*, which is the root of "Redis can lose recently-acknowledged writes on crash." **(b)** Steps 4–6 are all on the single thread; a slow step here stalls everyone.

### 3.3 Pipelining (and why it's not the same as a transaction)

**Pipelining** means a client sends many commands **without waiting for each reply**, then reads all replies. RESP is designed for this: the server just executes them in order and the client reads N replies.

Why it matters: most of Redis latency for a client is **RTT (round-trip time)**, not server CPU. If each command is 0.1 ms of server time but 0.5 ms of network RTT, then 1,000 sequential commands take ~600 ms, but 1,000 pipelined commands take ~roughly server time + one RTT ≈ tens of ms. Pipelining can give **10–50× throughput** for batch workloads.

> Pipelining is **not** atomic and **not** a transaction. Other clients' commands can interleave between your pipelined commands on the server. It's purely a network-batching optimization. For atomicity, use `MULTI/EXEC` or Lua.

### 3.4 Persistence I: RDB snapshots

**RDB (Redis Database)** is a **point-in-time binary snapshot** of the entire dataset, written as a compact single file (`dump.rdb`).

How it's produced (the elegant part):
1. Trigger: a `SAVE` (synchronous, blocks the server — almost never used in prod), a `BGSAVE` (background), an automatic save rule (`save 900 1` = save if ≥1 key changed in 900 s; defaults often `save 3600 1 300 100 60 10000`), or a replica requesting a full sync.
2. **`fork()`** the process. The child gets a **copy-on-write (COW)** view of the parent's memory.

   > **Term — `fork()`:** a Unix syscall that creates a child process which is a near-exact copy of the parent. Modern kernels don't physically copy the parent's memory; instead they share pages and mark them **copy-on-write** — a page is only physically duplicated when one process *writes* to it. This lets the child snapshot a consistent, frozen view of memory while the parent keeps serving writes; only the pages the parent modifies during the snapshot get duplicated. The cost is (a) the `fork()` itself, which on huge heaps can take tens to hundreds of ms because the kernel must copy the **page tables**, and (b) extra memory from COW duplication, up to ~2× in the worst case of a write-heavy workload during the save.

3. The **child** walks the in-memory dataset and writes the RDB file, then `rename()`s it atomically over the old one and exits.
4. The **parent** keeps serving clients the whole time; only the initial `fork()` causes a brief stall.

**Properties.** RDB is compact, fast to load (it's a dense serialization, much faster to load than replaying an AOF), and great for backups/replication bootstrap. **But** it loses all writes since the last snapshot if the process dies between snapshots — RDB durability is coarse-grained (minutes).

### 3.5 Persistence II: AOF (Append-Only File)

**AOF** logs **every write command** (in RESP form) to a file, so the dataset can be reconstructed by replaying the log.

- Writes go to an in-memory **AOF buffer**, appended to the OS file, and **`fsync`'d** per the `appendfsync` policy:

  | `appendfsync` | Behavior | Durability | Performance |
  |---|---|---|---|
  | `always` | fsync after *every* write | Lose ≤1 command on crash | Slowest (fsync per write) |
  | `everysec` (default) | fsync once per second (in a background thread) | Lose ≤1 second of writes | Good balance |
  | `no` | never fsync; let OS flush (~30 s) | Lose up to OS buffer window | Fastest, weakest |

  > **Term — `fsync`:** a syscall forcing the OS to flush a file's buffered/cached writes from the page cache to the physical disk and not return until the disk confirms. Without `fsync`, a `write()` only puts data in the OS page cache (RAM); a power loss then loses it. `fsync` is expensive (waits for disk), which is why doing it per-write (`always`) is slow.

- **AOF rewrite (compaction).** The AOF grows unbounded (it logs every `INCR`, `LPUSH`, etc.). `BGREWRITEAOF` `fork()`s a child that writes a *minimal* new AOF representing the current state (e.g. 1,000 `INCR`s become a single `SET k 1000`). Triggered automatically by `auto-aof-rewrite-percentage` (default 100 — rewrite when AOF doubled since last rewrite) and `auto-aof-rewrite-min-size` (default 64 MB).
- **Multi-part AOF (Redis ≥7).** AOF is now a **manifest** + a **base file** (RDB-format snapshot) + **incremental** AOF files in an `appendonlydir/`. This is the modern default and improves rewrite efficiency.

**RDB vs AOF — the comparison you must know:**

| Dimension | RDB | AOF |
|---|---|---|
| Durability granularity | Coarse (per snapshot, minutes) | Fine (≤1 s with `everysec`, ≤1 cmd with `always`) |
| File size | Small (compact binary) | Larger (command log; compacted by rewrite) |
| Restart load time | Fast | Slower (replay), though base-file AOF helps |
| `fork()` cost | Once per snapshot | Once per rewrite |
| Data loss on crash | Up to last snapshot | Up to fsync window |
| Good for | Backups, fast restarts, replica seeding | Maximum durability |

**Hybrid persistence (recommended for durable use).** `aof-use-rdb-preamble yes` (default in ≥4.0/7.0) makes the AOF rewrite produce an **RDB-format base** plus AOF tail — you get RDB's fast loading *and* AOF's fine-grained durability. In production for a durable Redis, the common stance is: **AOF `everysec` + periodic RDB snapshots for backups**, accepting ≤1 s loss.

> **Reality check on durability:** Even with `appendfsync always`, Redis acknowledges a write to the client *before* fsync completes by default propagation order in some paths — and crucially, **replication is asynchronous**, so a write acknowledged to the client may be lost if the primary crashes before the replica receives it. There is no synchronous-quorum durability in open-source Redis (the `WAIT` command gives *bounded* synchronous replication but not a transactional guarantee). Treat Redis as "very fast, tunably-durable, but not a strict-durability database."

### 3.6 Transactions: `MULTI` / `EXEC` / `WATCH`

```
WATCH balance:acct                 # optimistic lock: abort EXEC if this key changes
MULTI                              # start queuing
DECRBY balance:acct 100
INCRBY balance:other 100
EXEC                              # execute the queued batch atomically; nil if WATCH key changed
```

Internals & semantics:
- After `MULTI`, commands are **queued** (the server replies `QUEUED`), not executed. `EXEC` runs them **all in order, atomically** — no other client interleaves (single thread + the whole block is one execution unit).
- **No rollback on logic errors.** If a queued command fails *at runtime* (e.g. `INCR` on a non-numeric string), Redis **executes the rest of the block anyway** and reports the error for that command. Only *syntax/arity errors detected at queue time* abort the whole `EXEC`. This is unlike SQL — there is no undo. The Redis philosophy: such errors are programming bugs, and supporting rollback would add complexity/cost to the common case.
- **`WATCH`** provides **optimistic concurrency control (OCC)**: you `WATCH` keys; if any watched key is modified by anyone between `WATCH` and `EXEC`, the `EXEC` returns `nil` (aborts) and you retry. This is **CAS (compare-and-set)** at the transaction level.

  > **Term — optimistic concurrency control (OCC):** instead of locking data up front (pessimistic), you proceed assuming no conflict, and at commit time check whether anyone else touched the data; if so, you abort and retry. Cheap when conflicts are rare.

In practice, **Lua scripting often replaces `WATCH/MULTI/EXEC`** because a script runs atomically and can do conditional logic server-side without retry loops.

### 3.7 Key expiration internals

How does Redis actually delete expired keys, given it can't scan everything constantly?

- **Lazy (passive) expiration:** when a key is accessed, Redis checks its expire dict; if expired, it deletes it and behaves as if it were missing. So an unaccessed expired key consumes memory until sampled.
- **Active expiration:** in `serverCron`, Redis samples keys from the per-DB **expires dict** (only keys that *have* a TTL). The algorithm (`activeExpireCycle`): repeatedly take a random sample (e.g. 20 keys) from the expires dict; delete the expired ones; if >25% of the sample were expired, repeat immediately (because there are probably many more); else stop. It bounds total CPU time per cycle (default ~25% of one tick via `ACTIVE_EXPIRE_CYCLE`). This probabilistically keeps the fraction of stale expired keys low (~under 25%).
- **Replica behavior:** replicas do **not** independently expire keys; the primary sends an explicit `DEL`/`UNLINK` when it expires a key, to keep them consistent (a replica returning a logically-expired key to a reader is a known subtlety; modern versions hide it on reads).

### 3.8 Eviction and the memory model

When memory hits `maxmemory`, Redis must free space to accept new writes. The **eviction policy** (`maxmemory-policy`) decides what to drop:

| Policy | Evicts | Scope |
|---|---|---|
| `noeviction` (default) | Nothing; writes error with OOM | — |
| `allkeys-lru` | Least-recently-used | All keys |
| `volatile-lru` | LRU among keys with a TTL | Keys with TTL |
| `allkeys-lfu` | Least-frequently-used | All keys |
| `volatile-lfu` | LFU among TTL keys | Keys with TTL |
| `allkeys-random` | Random | All keys |
| `volatile-random` | Random among TTL keys | Keys with TTL |
| `volatile-ttl` | Shortest remaining TTL first | Keys with TTL |

> **Term — LRU vs LFU:** **LRU (least-recently-used)** evicts whatever hasn't been *accessed* for longest — good when recency predicts reuse. **LFU (least-frequently-used)** evicts whatever is *accessed least often* — better when some keys are perennially hot and others are one-hit wonders (LRU can be fooled by a scan that touches everything once). Redis's LRU/LFU are **approximate**: it samples a few keys (`maxmemory-samples`, default 5) and evicts the best candidate among the sample, rather than maintaining a perfect global order (which would be expensive). LFU uses a logarithmic counter with time-based decay (`lfu-log-factor`, `lfu-decay-time`).

**Memory layout facts:**
- Redis tracks memory via its allocator, default **jemalloc** (chosen for low fragmentation and good multi-size-class behavior). `INFO memory` reports `used_memory` (logical) and `used_memory_rss` (actual OS resident set); the ratio is `mem_fragmentation_ratio`. A ratio >1.5 suggests fragmentation; <1 means swapping (bad).

  > **Term — jemalloc:** a general-purpose memory allocator that groups allocations into size classes to reduce fragmentation and contention. Redis bundles it because the default glibc `malloc` fragments badly under Redis's allocation pattern.

- **Encodings save memory.** Small structures use compact representations and only "promote" when they exceed thresholds:
  - Small lists/hashes/zsets use **`listpack`** (a packed, contiguous byte array — formerly `ziplist`), bounded by e.g. `hash-max-listpack-entries` (128) and `hash-max-listpack-value` (64 bytes); lists also use a **`quicklist`** (a linked list of listpacks).
  - Small all-integer sets use **`intset`** (a sorted packed array of ints), bounded by `set-max-intset-entries` (512); small string sets use a listpack (`set-max-listpack-entries`).
  - Larger sets/hashes promote to a **hash table**; larger zsets to a **skiplist + dict**.
  - **Strings** use `int` encoding for integers, `embstr` for short strings (≤44 bytes — value stored inline with the object header in one allocation), and `raw` for longer ones (`OBJECT ENCODING key` shows it).

  > **Term — skiplist:** a layered linked list giving O(log N) search/insert by maintaining "express lanes" of skip pointers; it's how sorted sets achieve ordered O(log N) operations without a balanced tree's complexity. Redis pairs it with a hash table (member→score) so `ZSCORE` is O(1) and ranked operations are O(log N).

### 3.9 Replication, Sentinel, and Cluster (distribution)

#### Replication (the foundation)

- **Asynchronous primary→replica.** A replica connects, requests sync, and receives a stream of write commands. The primary doesn't wait for replicas to ack before replying to clients (hence async ⇒ possible data loss on failover).
- **Initial sync (PSYNC):** the primary `BGSAVE`s an RDB (or uses **diskless sync**, `repl-diskless-sync yes`, streaming the RDB directly over the socket), sends it to the replica, then streams the buffered commands accumulated meanwhile.
- **Partial resync:** each primary has a **replication backlog** (a circular buffer) and a **replication offset**; if a replica briefly disconnects and reconnects, it asks to resume from its offset, avoiding a full resync if the data is still in the backlog (`repl-backlog-size`, default 1 MB — often too small; tune up for flaky links).
- **`WAIT numreplicas timeout`** blocks until N replicas have acknowledged the current offset — bounded synchronous replication for stronger durability on demand (not a transaction).

#### Redis Sentinel (HA for a single primary)

> **Term — high availability (HA):** the ability of a system to keep serving despite individual node failures, typically via automatic failover to a standby.

**Sentinel** is a separate process (you run ≥3 for quorum) that **monitors** a primary and its replicas, performs **automatic failover** (promotes a replica to primary when the primary is down), and acts as a **service-discovery** endpoint (clients ask Sentinel "who is the current primary?").

- Sentinels gossip and require a **quorum** (e.g. 2 of 3) to agree the primary is **subjectively/objectively down** (`+sdown`/`+odown`) before failing over.
- A Sentinel **leader** is elected (a Raft-like majority vote) to run the failover: it picks the best replica (by priority, replication offset, run-id), `REPLICAOF NO ONE` promotes it, reconfigures the others, and updates clients.

  > **Term — quorum / majority:** a quorum is the minimum number of voters that must agree to make a decision; using a strict majority (e.g. 2 of 3, 3 of 5) prevents **split-brain** (two halves of a partitioned cluster both believing they're in charge), because two disjoint majorities cannot both exist.

  > **Term — Raft:** a consensus algorithm for getting a cluster of nodes to agree on a sequence of values despite failures, via leader election and majority-replicated logs. Sentinel uses a Raft-like leader election for the failover coordinator (not for data).

- Sentinel does **not** shard data — it's HA for a *single* primary/replica group. For horizontal scale beyond one node's RAM, you need Cluster.

#### Redis Cluster (sharding + HA, no proxy)

**Redis Cluster** distributes the keyspace across multiple primaries (shards) and provides built-in failover, **without a central proxy**.

- **Hash slots.** The keyspace is divided into **16384 hash slots**. A key's slot = `CRC16(key) mod 16384`. Each primary owns a contiguous-ish subset of slots. Clients (or the cluster) route a command to the node owning the key's slot.

  > **Term — CRC16:** a 16-bit cyclic redundancy check, here used purely as a fast, well-distributing hash to map keys to one of 16384 slots. 16384 (= 2^14) was chosen as a balance: enough slots for fine-grained rebalancing across up to ~1000 nodes, while keeping the per-node slot bitmap small enough to gossip cheaply.

- **Multi-key operations must share a slot.** `MGET a b c`, `MULTI`, and Lua over multiple keys require all keys in the *same* slot, else `CROSSSLOT` error. **Hash tags** force co-location: keys `{user1}:profile` and `{user1}:cart` both hash only the substring inside `{}` → same slot.
- **Routing & redirects.** If a client sends a command to the wrong node, it replies `MOVED slot host:port` (permanent — update your slot map) or `ASK host:port` (temporary, during a slot migration). Smart clients cache the slot→node map and follow redirects.
- **Resharding / migration.** Slots can be migrated live between nodes (`CLUSTER SETSLOT ... MIGRATING/IMPORTING`, keys moved with `MIGRATE`). During migration, the source serves existing keys and `ASK`-redirects new ones. `redis-cli --cluster reshard` automates it.
- **Failover.** Each primary has replicas; cluster nodes gossip over the **cluster bus** (port 6379+10000). If a majority of primaries mark a primary as failed (`FAIL`), one of its replicas is promoted via a vote. Needs a **majority of primaries reachable** to keep operating.
- **Consistency caveat.** Cluster is still **async-replicated**, so failover can lose recent writes; and during partitions a minority side stops accepting writes (`cluster-require-full-coverage`, default `yes`, makes the *whole* cluster refuse writes if any slot is uncovered — often set to `no` for partial availability).

#### Sentinel vs Cluster — when to use which

| Need | Sentinel | Cluster |
|---|---|---|
| Data > one node's RAM (sharding) | No | **Yes** |
| Automatic failover | Yes | Yes |
| Multi-key/transactions across all keys | Yes (single primary) | Only within a slot (hash tags) |
| Operational simplicity | Simpler | More complex |
| Horizontal write scaling | No | **Yes** |

Rule: **Sentinel** when one node's RAM/throughput suffices and you just need HA; **Cluster** when you must shard across nodes. Many shops use a managed service (ElastiCache, Memorystore, Redis Enterprise/Cloud) to avoid running either by hand.

### 3.10 Pub/Sub vs. Streams

**Pub/Sub** is fire-and-forget messaging: `SUBSCRIBE channel` / `PUBLISH channel msg`. Messages are **not stored**; a subscriber that's offline or slow misses them (and slow subscribers can be disconnected). It's "at-most-once," no history, no acks. Good for live notifications, cache-invalidation fan-out, presence. (Cluster: `PUBLISH` propagates cluster-wide; `SSPUB`/`SSUBSCRIBE` give sharded pub/sub scoped to a slot for scalability.)

**Streams** (Redis ≥5) are a **durable, append-only log** with **consumer groups** — much closer to Kafka:
- `XADD key * field val …` appends an entry with an auto ID `<ms>-<seq>`.
- Entries persist (subject to `MAXLEN`/`MINID` trimming) and can be re-read.
- **Consumer groups** (`XGROUP CREATE`, `XREADGROUP`) let multiple consumers split the workload; each entry is delivered to one consumer in the group, who must **`XACK`** it. Unacked entries sit in the **PEL (Pending Entries List)** and can be **claimed** by another consumer (`XCLAIM`/`XAUTOCLAIM`) if the first dies — giving **at-least-once** delivery.

  > **Term — consumer group / PEL:** a consumer group tracks a shared cursor and a per-consumer list of delivered-but-unacked messages (the Pending Entries List). This enables load-balanced, fault-tolerant consumption: if a consumer crashes mid-processing, its pending messages can be reassigned and reprocessed, so nothing is silently dropped.

Use **Pub/Sub** for ephemeral fan-out; **Streams** for durable work queues / event sourcing where you need acks, replay, and consumer groups.

### 3.11 Lua scripting and Functions

`EVAL "<script>" numkeys key1 … arg1 …` runs a Lua script **atomically on the single thread**. Inside, `redis.call('SET', KEYS[1], ARGV[1])` invokes Redis commands. Because it's one atomic unit, scripts are the canonical way to implement **read-modify-write logic** (locks, rate limiters, conditional updates) without races or `WATCH` retry loops.

- **`SCRIPT LOAD` + `EVALSHA`** caches the script by SHA1 so you don't resend the body each call.
- **Scripts must be deterministic** (no `TIME`/`RANDOM` affecting writes inconsistently) so they replicate/persist correctly — modern Redis replicates the *effects* (the writes) rather than the script, relaxing this, but determinism is still best practice.
- **Redis Functions** (≥7.0, `FUNCTION LOAD`) are the successor: named, versioned, persisted-with-the-dataset libraries of functions, better for managing server-side logic than ad-hoc `EVAL`.
- **Beware:** a long-running or infinite-loop script **blocks the whole server**; `lua-time-limit` (default 5000 ms) lets you `SCRIPT KILL` a read-only stuck script (a script that already wrote can only be stopped via `SHUTDOWN NOSAVE`).

### 3.12 Threading model recap (Redis 6/7+)

- **Command execution:** still single-threaded (atomicity guarantee preserved).
- **I/O threads** (`io-threads N`, default 1 = off): offload reading/parsing requests and writing replies to N threads. Helps when network I/O is the bottleneck (many small ops). `io-threads-do-reads yes` extends to reads.
- **Background threads:** `lazyfree` (async free of big objects via `UNLINK`/`FLUSHALL ASYNC`/`lazyfree-lazy-*`), AOF fsync, RDB/AOF child via `fork`, and active defrag.

  > **Term — lazy freeing (`UNLINK`):** deleting a huge collection with `DEL` frees all its memory synchronously on the main thread (can stall for hundreds of ms on millions of elements). `UNLINK` instead removes the key from the keyspace instantly and frees the memory in a background thread, avoiding the stall.

---

## 4. The complete toolkit

This section enumerates the practical surface area. Tables list the most important commands, classes, and config flags with purpose, key parameters, and defaults. (Defaults are for OSS Redis 7.x; flag a version where it differs.)

### 4.1 Strings & counters

| Command | Purpose | Key params / notes | Complexity |
|---|---|---|---|
| `SET k v [EX s|PX ms|EXAT|PXAT] [NX|XX] [GET] [KEEPTTL]` | Set string, optional TTL & conditions | `NX`=only if absent (locks!), `XX`=only if exists, `GET`=return old value, `KEEPTTL`=retain TTL | O(1) |
| `GET k` / `GETDEL k` / `GETEX k …` | Read / read-and-delete / read-and-set-TTL | `GETEX` sets/clears TTL on read | O(1) |
| `MSET k v …` / `MGET k …` | Bulk set/get | Cluster: same slot for `MSET`/`MGET` | O(N) |
| `INCR k` / `INCRBY k n` / `INCRBYFLOAT k f` / `DECR` | Atomic numeric counters | Errors if value non-numeric | O(1) |
| `APPEND k v` / `STRLEN k` / `GETRANGE`/`SETRANGE` | Substring/append ops | — | O(1)/O(M) |

### 4.2 Hashes

| Command | Purpose | Notes |
|---|---|---|
| `HSET k f v [f v …]` / `HGET` / `HMGET` / `HDEL` | Field-level get/set/delete | Object modeling |
| `HGETALL k` | All field/values | **O(N)** — avoid on big hashes; use `HSCAN` |
| `HINCRBY` / `HINCRBYFLOAT` | Atomic numeric field counters | Per-field counters |
| `HSCAN k cursor [MATCH] [COUNT]` | Cursor iteration | Safe for large hashes |
| `HEXPIRE`/`HTTL` (≥7.4) | Per-field TTL | New; flag version |

### 4.3 Lists

| Command | Purpose | Notes |
|---|---|---|
| `LPUSH`/`RPUSH`/`LPOP`/`RPOP` | Push/pop both ends | Queue/stack |
| `BLPOP`/`BRPOP key… timeout` | **Blocking** pop | Worker waits for jobs; blocks the *client*, not the server |
| `LMOVE src dst LEFT|RIGHT …` / `BLMOVE` | Atomic move between lists | Reliable-queue pattern |
| `LRANGE k start stop` | Range read | `0 -1` = whole list (**O(N)**) |
| `LLEN` / `LINSERT` / `LREM` / `LTRIM` | Length/insert/remove/trim | `LTRIM` caps list size |

### 4.4 Sets

| Command | Purpose | Notes |
|---|---|---|
| `SADD`/`SREM`/`SISMEMBER`/`SMISMEMBER` | Add/remove/membership | Tags, dedupe |
| `SCARD` | Cardinality | O(1) |
| `SMEMBERS` | All members | **O(N)** — use `SSCAN` |
| `SINTER`/`SUNION`/`SDIFF` (+`…STORE`) | Set algebra | Can be O(N·M); store result |
| `SRANDMEMBER` / `SPOP [count]` | Random pick / pop | Sampling |

### 4.5 Sorted sets (ZSets)

| Command | Purpose | Notes |
|---|---|---|
| `ZADD k [NX|XX|GT|LT] [CH] [INCR] score member` | Add/update with score | `GT`/`LT` only update if greater/less (leaderboards) |
| `ZRANGE k start stop [BYSCORE|BYLEX] [REV] [LIMIT off cnt] [WITHSCORES]` | Unified range query | Replaces old `ZREVRANGE`/`ZRANGEBYSCORE` |
| `ZRANK`/`ZREVRANK` / `ZSCORE` | Position / score | O(log N) / O(1) |
| `ZINCRBY` | Atomic score bump | Real-time scoring |
| `ZPOPMIN`/`ZPOPMAX` / `BZPOPMIN` | Pop by score (priority queue) | Blocking variant |
| `ZRANGEBYLEX` / `ZADD` w/ equal scores | Lexicographic ranges | Secondary indexes |
| `ZREMRANGEBYRANK|SCORE` | Bulk trim | Cap leaderboards |

### 4.6 Bitmaps, Bitfields, HLL, Geo

| Command | Purpose | Notes |
|---|---|---|
| `SETBIT`/`GETBIT`/`BITCOUNT`/`BITPOS`/`BITOP` | Bit-level flags | DAU tracking, presence |
| `BITFIELD k GET/SET/INCRBY u8/i16 …` | Packed integer fields | Compact multi-counters |
| `PFADD`/`PFCOUNT`/`PFMERGE` | HyperLogLog cardinality | ~0.81% std error, ~12 KB max |
| `GEOADD`/`GEOSEARCH`/`GEODIST`/`GEOPOS` | Geospatial | Backed by a zset of geohashes |

> **Term — HyperLogLog error:** HLL trades exactness for space: it estimates the count of distinct items with a standard error of ~0.81% using a fixed ~12 KB, regardless of whether you added 10 or 10 billion items. Use it when "about 4.2M unique visitors" is fine and storing every visitor ID is not.

### 4.7 Streams

| Command | Purpose | Notes |
|---|---|---|
| `XADD k [MAXLEN ~ N|MINID m] * f v …` | Append entry | `~` = approximate trim (cheaper) |
| `XREAD [BLOCK ms] [COUNT n] STREAMS k id` | Read (tailing) | `$` = only new entries |
| `XGROUP CREATE k grp id [MKSTREAM]` | Create consumer group | `$`=from now, `0`=from start |
| `XREADGROUP GROUP g c [BLOCK] COUNT n STREAMS k >` | Group read | `>` = new undelivered |
| `XACK k g id …` | Acknowledge | Removes from PEL |
| `XPENDING` / `XCLAIM` / `XAUTOCLAIM` | Inspect/reassign pending | Crash recovery |
| `XLEN`/`XRANGE`/`XINFO`/`XTRIM`/`XDEL` | Length/range/inspect/trim | Maintenance |

### 4.8 Pub/Sub & keyspace notifications

| Command | Purpose |
|---|---|
| `SUBSCRIBE`/`UNSUBSCRIBE`/`PUBLISH` | Channel pub/sub |
| `PSUBSCRIBE`/`PUNSUBSCRIBE` | Pattern subscribe |
| `SSUBSCRIBE`/`SPUBLISH` | Sharded pub/sub (cluster) |
| `CONFIG SET notify-keyspace-events KEA` | Enable keyspace event notifications (subscribe to `__keyevent@0__:expired` etc.) |

### 4.9 Transactions, scripting, generic

| Command | Purpose |
|---|---|
| `MULTI`/`EXEC`/`DISCARD`/`WATCH`/`UNWATCH` | Transactions + OCC |
| `EVAL`/`EVALSHA`/`SCRIPT LOAD|EXISTS|FLUSH|KILL` | Lua scripting |
| `FUNCTION LOAD|LIST|DELETE|DUMP|RESTORE` / `FCALL` | Redis Functions (≥7) |
| `EXPIRE`/`PEXPIRE`/`EXPIREAT`/`TTL`/`PERSIST` | TTL management |
| `DEL`/`UNLINK`/`EXISTS`/`TYPE`/`RENAME`/`COPY` | Generic key ops (`UNLINK`=async free) |
| `SCAN cursor [MATCH] [COUNT] [TYPE]` | Cursor keyspace iteration (**use instead of `KEYS`**) |
| `OBJECT ENCODING|REFCOUNT|IDLETIME|FREQ` | Inspect object internals |
| `WAIT n timeout` | Bounded sync replication |

### 4.10 Operational / admin commands

| Command | Purpose |
|---|---|
| `INFO [section]` | Everything: memory, persistence, replication, stats, clients, CPU |
| `CONFIG GET|SET|REWRITE` | Live config |
| `CLIENT LIST|KILL|NO-EVICT|INFO|SETNAME` | Connection management |
| `SLOWLOG GET|RESET|LEN` | Log of slow commands (`slowlog-log-slower-than`, default 10000 µs) |
| `LATENCY DOCTOR|HISTORY|RESET` | Latency monitoring/spike analysis |
| `MEMORY USAGE key` / `MEMORY DOCTOR` / `MEMORY STATS` | Per-key & global memory analysis |
| `MONITOR` | Stream every command (debug only — **huge perf cost**) |
| `DEBUG SLEEP|OBJECT|JMAP` | Debugging |
| `BGSAVE`/`BGREWRITEAOF`/`SAVE`/`LASTSAVE` | Persistence triggers |
| `CLUSTER INFO|NODES|SLOTS|SHARDS|KEYSLOT` | Cluster introspection |
| `ACL SETUSER|GETUSER|LIST|WHOAMI|CAT` | Access control (≥6) |
| `RESET` | Reset connection state (auth, MULTI, subscriptions) |

### 4.11 Key configuration flags (`redis.conf`)

| Flag | Default | Purpose |
|---|---|---|
| `maxmemory` | 0 (unlimited) | Memory cap; **always set in production** |
| `maxmemory-policy` | `noeviction` | Eviction strategy |
| `maxmemory-samples` | 5 | LRU/LFU sampling accuracy |
| `appendonly` | `no` | Enable AOF |
| `appendfsync` | `everysec` | AOF durability |
| `save` | `3600 1 300 100 60 10000` | RDB triggers |
| `aof-use-rdb-preamble` | `yes` | Hybrid persistence |
| `repl-diskless-sync` | `yes` (≥7) | Stream RDB to replicas without disk |
| `repl-backlog-size` | 1mb | Partial-resync buffer (tune up) |
| `hz` / `dynamic-hz` | 10 / yes | `serverCron` frequency |
| `io-threads` | 1 | I/O parallelism |
| `timeout` | 0 | Idle client timeout (s) |
| `tcp-keepalive` | 300 | Detect dead peers |
| `requirepass` / `aclfile` | (none) | Auth |
| `lazyfree-lazy-eviction|expire|server-del|user-del` | varies | Async frees |
| `cluster-enabled` | no | Enable cluster mode |
| `cluster-require-full-coverage` | yes | Refuse writes if a slot is uncovered |
| `client-output-buffer-limit` | `normal 0 0 0 / slave 256mb 64mb 60 / pubsub 32mb 8mb 60` | Kill clients (esp. replicas/subscribers) whose output buffer grows |

### 4.12 Java client libraries

| Client | Model | Notes |
|---|---|---|
| **Lettuce** | Netty-based, **async + reactive**, thread-safe shared connection | Spring Boot's default; great for high-concurrency; supports cluster, pub/sub, RESP3. |
| **Jedis** | Synchronous, connection-per-thread (pooled) | Simple, mature; needs a pool (`JedisPool`/`JedisCluster`). |
| **Redisson** | High-level distributed objects (locks, maps, queues, semaphores, rate limiters) | Implements `java.util.concurrent` and Redlock-style locks over Redis; richer abstractions, heavier. |

> **Term — Netty / reactive:** Netty is a Java async networking framework (event-loop based). A *reactive* client returns `Mono`/`Flux` (Project Reactor) so calls are non-blocking — you compose pipelines and the thread isn't tied up waiting for the network. Lettuce uses one (thread-safe) connection that can multiplex many concurrent requests, unlike Jedis where each thread typically borrows its own connection from a pool.

---

## 5. Code examples by use case

All Java examples use **Lettuce** or **Jedis** as noted; Lua is inline. They're written to be adaptable, with non-obvious lines commented.

### 5.1 Cache-aside with TTL and stampede protection (Java + Lettuce)

The bread-and-butter caching pattern, plus protection against the **cache stampede** (many threads all missing the same hot key at once and hammering the DB).

```java
import io.lettuce.core.*;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.function.Supplier;

public class CacheAside {
    private final RedisCommands<String, String> redis; // sync API for clarity
    private final ObjectMapper json = new ObjectMapper();

    public CacheAside(RedisCommands<String, String> redis) { this.redis = redis; }

    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        String cached = redis.get(key);
        if (cached != null) {
            return deserialize(cached, type);            // cache HIT
        }
        // Cache MISS. Use a short-lived lock so only ONE caller refreshes the DB.
        String lockKey = "lock:" + key;
        String token = java.util.UUID.randomUUID().toString();
        // SET lock NX (only-if-absent) PX 5000 -> atomic "acquire lock for 5s"
        boolean gotLock = "OK".equals(
            redis.set(lockKey, token, SetArgs.Builder.nx().px(5000)));
        if (gotLock) {
            try {
                // Double-check: another thread may have populated it meanwhile.
                String again = redis.get(key);
                if (again != null) return deserialize(again, type);

                T value = loader.get();                  // expensive DB call
                redis.set(key, serialize(value), SetArgs.Builder.ex(ttl.getSeconds()));
                return value;
            } finally {
                releaseLock(lockKey, token);             // safe unlock (see 5.4)
            }
        } else {
            // Didn't get the lock: brief wait then re-read cache (loader is running elsewhere).
            sleep(50);
            String filled = redis.get(key);
            return filled != null ? deserialize(filled, type)
                                  : loader.get();          // last-resort fallback
        }
    }
    // serialize/deserialize/sleep/releaseLock omitted for brevity
}
```

Key points: `SET ... NX EX` is the atomic primitive for both cache-set and lock-acquire; the **double-check** after acquiring the lock avoids a redundant load; the lock has a **short TTL** so a crashed holder can't block forever. For high-traffic keys, also consider **probabilistic early expiration** (refresh slightly before TTL with a random jitter) to smooth out simultaneous expiry.

### 5.2 Atomic rate limiter (fixed window + sliding window via Lua)

**Fixed-window counter** (simple, but bursty at window edges):

```java
// INCR the per-window counter; on first hit, set the TTL so it auto-resets.
String key = "rl:" + userId + ":" + (System.currentTimeMillis() / 60000); // minute bucket
long count = redis.incr(key);
if (count == 1L) redis.expire(key, 60);     // set TTL only on creation
boolean allowed = count <= 100;             // 100 req/min
```

**Sliding-window log via an atomic Lua script** (smooth limit, no edge bursts). The whole check-and-record is one atomic unit — no race:

```lua
-- KEYS[1] = bucket key (a sorted set of request timestamps)
-- ARGV[1] = now (ms), ARGV[2] = window (ms), ARGV[3] = limit
local now    = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit  = tonumber(ARGV[3])
-- Drop timestamps older than the window.
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local count = redis.call('ZCARD', KEYS[1])
if count < limit then
  redis.call('ZADD', KEYS[1], now, now)      -- record this request
  redis.call('PEXPIRE', KEYS[1], window)     -- keep key from leaking
  return 1                                    -- allowed
else
  return 0                                    -- denied
end
```

```java
String sha = redis.scriptLoad(SLIDING_WINDOW_LUA);   // load once at startup
Long ok = redis.evalsha(sha, ScriptOutputType.INTEGER,
        new String[]{ "rl:" + userId },              // KEYS[1]
        String.valueOf(System.currentTimeMillis()),  // ARGV[1] now
        "60000",                                      // ARGV[2] window = 60s
        "100");                                       // ARGV[3] limit
boolean allowed = ok == 1L;
```

Why Lua: doing `ZREMRANGEBYSCORE` + `ZCARD` + `ZADD` as separate commands would race (two requests could both see count < limit). The script makes it atomic.

### 5.3 Real-time leaderboard (sorted set)

```java
// Update a player's score atomically; GT means "only raise the high score".
redis.zadd("leaderboard:weekly", ZAddArgs.Builder.gt(), newScore, playerId);

// Top 10, highest first, with scores.
List<ScoredValue<String>> top =
    redis.zrevrangeWithScores("leaderboard:weekly", 0, 9);

// A specific player's rank (0-based; +1 for human display).
Long rank = redis.zrevrank("leaderboard:weekly", playerId);

// Players around me (rank-1 .. rank+1) for a "you vs neighbors" view.
List<String> neighbors = redis.zrevrange("leaderboard:weekly",
        Math.max(0, rank - 1), rank + 1);

// Weekly reset: set a TTL on the key, or rotate the key name by ISO week.
redis.expire("leaderboard:weekly", Duration.ofDays(7).toSeconds());
```

All operations are O(log N); a million-player leaderboard handles top-N and rank queries in microseconds. For huge global boards, shard by region and merge top-Ks, or keep a smaller "top 1000" zset trimmed with `ZREMRANGEBYRANK`.

### 5.4 Distributed lock (single-instance Redlock-lite) — correct release

```java
// ACQUIRE: atomic set-if-absent with a TTL (the fencing window).
String token = UUID.randomUUID().toString();           // unique owner token
boolean locked = "OK".equals(
    redis.set("lock:order:" + orderId, token, SetArgs.Builder.nx().px(10000)));

// RELEASE must be atomic check-then-delete, or you might delete someone else's lock
// (if your lock expired and another owner acquired it). Lua makes it atomic:
String unlock =
  "if redis.call('GET', KEYS[1]) == ARGV[1] " +
  "then return redis.call('DEL', KEYS[1]) else return 0 end";
redis.eval(unlock, ScriptOutputType.INTEGER,
           new String[]{"lock:order:" + orderId}, token);
```

**Critical caveats (interview gold):**
- The naive `DEL` release is a bug: if your operation overran the lock TTL, the lock may now belong to *another* owner, and a plain `DEL` would release *theirs*. The Lua "delete only if token matches" fixes this.
- This single-instance lock is **not safe under failover**: if the primary dies after granting the lock but before replicating it, a promoted replica won't know about the lock and may grant it again (async replication). **Redlock** (acquire on N/2+1 independent masters) attempts to address this but is itself contested (Martin Kleppmann's critique: clock drift and GC pauses can still cause two holders). For correctness-critical locks, use **fencing tokens** (a monotonically increasing number the resource checks) or a stronger coordinator (ZooKeeper/etcd).

  > **Term — fencing token:** a strictly increasing number issued with each lock grant. The protected resource records the highest token it has seen and rejects any operation carrying a lower token. This guarantees that even if two clients believe they hold the lock (due to a pause or failover), the stale one's writes are rejected — closing the gap that TTL-based locks leave open.

### 5.5 Reliable work queue (List + reliable pattern, or Streams)

**List-based reliable queue** using `BLMOVE` (atomically move job from the pending list to a per-worker processing list, so a crash doesn't lose it):

```java
// Producer:
redis.lpush("jobs:queue", jobJson);

// Worker: atomically pop from queue tail and push to my processing list, blocking up to 5s.
String job = redis.blmove("jobs:queue", "jobs:processing:" + workerId,
                          LMoveArgs.Builder.leftRight(), 5);
if (job != null) {
    try {
        process(job);
        redis.lrem("jobs:processing:" + workerId, 1, job); // ack = remove from processing
    } catch (Exception e) {
        // leave it in processing; a reaper moves stale jobs back to the queue
    }
}
```

**Stream-based queue with consumer groups** (preferred for durability + acks):

```java
redis.xgroupCreate(XReadArgs.StreamOffset.from("orders", "0"),
                   "fulfillment", XGroupCreateArgs.Builder.mkstream()); // create group once

// Consumer loop:
List<StreamMessage<String,String>> msgs = redis.xreadgroup(
    Consumer.from("fulfillment", "worker-1"),
    XReadArgs.Builder.block(2000).count(10),
    XReadArgs.StreamOffset.lastConsumed("orders"));   // ">" = new messages
for (StreamMessage<String,String> m : msgs) {
    handle(m.getBody());
    redis.xack("orders", "fulfillment", m.getId());    // ack -> removes from PEL
}
// Periodically reclaim messages stuck > 30s in a dead consumer's PEL:
redis.xautoclaim("orders",
    XAutoClaimArgs.Builder.justid().consumer(Consumer.from("fulfillment", "worker-1"))
        .minIdleTime(30000).startId("0"));
```

### 5.6 Pipelining a bulk load (Lettuce async)

```java
import io.lettuce.core.api.async.RedisAsyncCommands;

RedisAsyncCommands<String,String> async = connection.async();
async.setAutoFlushCommands(false);                // batch on the wire
List<RedisFuture<?>> futures = new ArrayList<>();
for (int i = 0; i < 100_000; i++) {
    futures.add(async.set("k:" + i, "v" + i));    // queued, not sent yet
}
async.flushCommands();                            // one big write to the socket
LettuceFutures.awaitAll(5, TimeUnit.SECONDS,
        futures.toArray(new RedisFuture[0]));     // await all replies
async.setAutoFlushCommands(true);
```

This turns 100k round-trips into effectively one, often a 20–50× speedup. (For atomic bulk operations, wrap in `MULTI/EXEC` or a Lua script instead — but pure bulk load wants pipelining, not a transaction.)

### 5.7 Counting unique visitors with HyperLogLog & DAU with bitmaps

```java
// Approx unique visitors per day (~12 KB regardless of count, ~0.81% error):
redis.pfadd("uv:2026-06-24", userId1, userId2 /* ... */);
long approxUniques = redis.pfcount("uv:2026-06-24");
// Approx uniques across a week: merge the daily HLLs (no double counting):
redis.pfmerge("uv:week", "uv:2026-06-18", "uv:2026-06-19" /* ... */);

// Exact daily-active flag per user via a bitmap (1 bit/user => 1M users ≈ 125 KB):
redis.setbit("dau:2026-06-24", userId /* numeric */, 1);
long activeToday = redis.bitcount("dau:2026-06-24");
// Users active on BOTH days (set intersection via BITOP AND):
redis.bitop(BitFieldArgs... /* AND */, "dau:both", "dau:2026-06-23", "dau:2026-06-24");
```

Choose HLL when you only need the *count* of uniques and can tolerate ~1% error; choose a bitmap when you need per-user membership and your user IDs are dense small integers.

### 5.8 Cache invalidation via keyspace notifications

```java
// Enable once: CONFIG SET notify-keyspace-events KEA  (K=keyspace, E=keyevent, A=all)
// Subscribe to expirations to do follow-up work when a key TTLs out:
StatefulRedisPubSubConnection<String,String> pubsub = client.connectPubSub();
pubsub.addListener(new RedisPubSubAdapter<>() {
    @Override public void message(String channel, String key) {
        // channel = "__keyevent@0__:expired", key = the expired key
        onSessionExpired(key);
    }
});
pubsub.sync().psubscribe("__keyevent@0__:expired");
```

Use sparingly: notifications are best-effort (Pub/Sub semantics — missed if no subscriber), and `expired` events fire when Redis *actually* deletes the key (which, due to lazy expiration, can lag the logical TTL).

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Mind command complexity.** The number-one production sin is running O(N) commands (`KEYS`, `HGETALL`, `SMEMBERS`, `LRANGE 0 -1`, big `SORT`/`SUNIONSTORE`) on large collections — they block the single thread. Use `SCAN`/`HSCAN`/`SSCAN`/`ZSCAN` and ranged reads.
- **Pipeline batch work** to amortize RTT (§5.6). Aim to keep batch sizes bounded (a few thousand) so reply buffers don't blow up.
- **Use the right data structure.** A leaderboard is a zset, not a list you re-sort. A set membership check is `SISMEMBER` (O(1)), not `SMEMBERS` + scan.
- **Connection model:** with Jedis, pool connections (one borrow per operation); with Lettuce, share one (thread-safe) connection and use async/reactive for concurrency. Don't open a connection per request.
- **Locality:** colocate app servers and Redis in the same AZ/region; cross-region RTT dominates everything.
- **`io-threads`** help only when the bottleneck is socket I/O (lots of small ops on a fast network); they don't speed up command logic. Benchmark before enabling.
- **Avoid big values.** A single 50 MB string or a 5M-element collection makes every touch (and every replication of it) costly. Split large objects.

### 6.2 Correctness & concurrency

- **Lean on atomicity:** prefer single atomic commands (`INCR`, `SET NX`, `ZADD GT`) and Lua scripts over read-modify-write in the client.
- **`MULTI/EXEC` has no rollback** — validate inputs before queuing; use `WATCH` for CAS where needed.
- **Lua determinism:** don't let non-deterministic inputs (time, random) drive writes inconsistently across primary/replica (modern effect-based replication mostly handles this, but keep scripts deterministic for safety).
- **Distributed locks are hard:** TTL locks can be held by two clients during pauses/failover; use fencing tokens for correctness-critical sections (§5.4).
- **Cluster multi-key constraints:** keep related keys in the same slot with **hash tags** if you need `MGET`/`MULTI`/Lua across them.

### 6.3 Memory

- **Always set `maxmemory` + an eviction policy** in production. `noeviction` + no cap = OOM-kill or swap-to-death.
- **Avoid swap entirely.** If Redis pages to disk, latency explodes (RAM→disk). Set `vm.overcommit_memory=1` (so `fork()` for `BGSAVE` doesn't fail under memory pressure) and keep Redis well under physical RAM (leave headroom for COW during saves — up to ~2× transiently for write-heavy workloads).
- **Watch fragmentation** (`mem_fragmentation_ratio`); enable **active defrag** (`activedefrag yes`) if it's high.
- **Use compact encodings:** keep small hashes/zsets/sets under the listpack/intset thresholds; `OBJECT ENCODING` to verify. Many small hashes can be far cheaper than many top-level keys (less per-key overhead).
- **TTL everything cacheable** so dead data self-evicts.

### 6.4 Security

- **Never expose Redis to the internet unauthenticated.** Default until recent versions had no auth; countless breaches came from open 6379 ports. Bind to private interfaces, firewall the port.
- **Use ACLs (≥6):** create per-app users with least privilege (`ACL SETUSER app on >pw ~app:* +@read +@write -@dangerous`), restricting command categories and key patterns. Disable/rename dangerous commands (`FLUSHALL`, `CONFIG`, `KEYS`, `DEBUG`) via `rename-command` or ACL.
- **TLS** (`tls-port`, certs) for encryption in transit.
- **Protected mode** (default `yes`) refuses external connections without a bind/auth configured.
- **Lua sandbox:** scripts can't access the filesystem/network, but a malicious/buggy script can DoS via blocking — restrict who can `EVAL`.

### 6.5 Observability

- **`INFO`** sections: `memory` (used/rss/frag/evicted), `persistence` (last save, AOF status, `rdb_bgsave_in_progress`), `replication` (offsets, lag, connected replicas), `stats` (ops/sec, hits/misses, `keyspace_hits/misses` → hit ratio), `clients` (connected, blocked, output buffer), `cpu`.
- **`SLOWLOG`** captures commands exceeding `slowlog-log-slower-than` (default 10 ms) — your first stop for "Redis is slow."
- **`LATENCY DOCTOR`/`HISTORY`** explains latency spikes (fork, AOF fsync, eviction, expired-key bursts, slow commands).
- **`MEMORY DOCTOR`/`MEMORY USAGE key`** for memory diagnosis.
- **Key metrics to alert on:** hit ratio, evicted_keys/sec, used_memory vs maxmemory, replication lag (`master_repl_offset` − replica offset), blocked_clients, rejected_connections, `latest_fork_usec`, AOF rewrite/last-bgsave status, instantaneous_ops_per_sec.
- Export to Prometheus via `redis_exporter`; dashboards in Grafana.

### 6.6 Cost

- RAM is the dominant cost; right-size with `maxmemory` and TTLs, use compact encodings, and consider Redis on tiered/flash for cold data (Redis Enterprise/Stack `Auto Tiering`/Flash) when most data is cold. HLL/bitmaps drastically cut memory for analytics. Don't over-replicate (each replica is a full RAM copy).

### 6.7 Testing

- **Unit/integration:** use **Testcontainers** (`GenericContainer("redis:7-alpine")`) for real Redis in tests, or an in-process fake (`embedded-redis`) for speed (but fakes diverge from real semantics — prefer Testcontainers for anything subtle).
- **Test failure paths:** lock contention, expiry, eviction under `maxmemory`, cluster `MOVED`/`CROSSSLOT`, failover (kill a primary, assert app reconnects).
- **Chaos:** inject latency/partitions (toxiproxy) to validate timeouts and retries.

### 6.8 Production hardening checklist

- `maxmemory` + policy set; alerts on memory and evictions.
- Persistence chosen deliberately (cache → maybe none; durable → AOF everysec + RDB backups).
- `vm.overcommit_memory=1`, transparent huge pages **disabled** (THP causes latency spikes during fork — Redis warns about it on startup).
- Replicas + Sentinel/Cluster for HA; test failover regularly.
- ACLs/auth/TLS; dangerous commands renamed/disabled.
- Client timeouts + retries + circuit breakers (don't let a Redis blip cascade into app threads blocking forever).
- `repl-backlog-size` tuned for your network; `client-output-buffer-limit` for replicas/pubsub.
- Avoid `KEYS`, `FLUSHALL`, big synchronous `DEL` (use `UNLINK`), and `MONITOR` in prod.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it hurts | Fix |
|---|---|---|
| `KEYS *` in app code | O(N) blocks the server | `SCAN` |
| Giant single keys (multi-MB value, millions of elements) | Blocks on access/replication/free | Split; use `UNLINK` |
| No `maxmemory` | OOM / swap death | Set cap + eviction |
| Using Redis as the only copy of critical data | Async durability ⇒ data loss | DB is source of truth, or accept loss |
| `DEL bighash` synchronously | Stalls server | `UNLINK` / lazyfree |
| Naive lock release (`DEL`) | Deletes others' locks | Token-checked Lua release |
| Long Lua scripts / busy loops | Blocks everyone | Keep scripts short; chunk work |
| Many top-level keys vs grouping in a hash | Higher per-key overhead | Group related fields in a hash |
| Pub/Sub for jobs that must not be lost | At-most-once, no acks | Streams + consumer groups |
| Treating numbered DBs as isolation | Shared thread/memory; not in cluster | Prefix keys |

---

## 7. Advanced topics & deep internals

### 7.1 Object encodings in detail

Every value is a `robj` carrying a `type` (string/list/set/…) and an `encoding`. Encodings are chosen for size:

- **String:** `int` (integer ≤ 64-bit, shared small-int objects 0–9999 to save RAM), `embstr` (≤44 bytes, header+data in one cache-friendly allocation), `raw` (larger, separate allocation).
- **List:** `listpack` (tiny) → `quicklist` (a doubly-linked list of listpack nodes; each node compressible via `list-compress-depth`).
- **Hash:** `listpack` → `hashtable`.
- **Set:** `intset` (sorted ints) → `listpack` (small mixed) → `hashtable`.
- **ZSet:** `listpack` → `skiplist` (+ a dict for O(1) score lookup).

Tuning thresholds (`*-max-listpack-entries`/`-value`, `set-max-intset-entries`) trades memory vs. operation cost — bigger listpacks save memory but make per-op O(n-in-listpack). Inspect with `OBJECT ENCODING key`.

### 7.2 Incremental rehashing

When the main dict's load factor crosses a threshold, Redis doubly-sizes it — but a giant `dict` can't be rehashed in one stall. So it keeps **two hash tables** and **incrementally moves buckets** across many operations (a few per command, plus a slice in `serverCron`). During rehashing, lookups check both tables. This avoids a multi-hundred-ms freeze that a one-shot rehash of millions of keys would cause.

### 7.3 The fork/COW deep dive and latency

`BGSAVE`/`BGREWRITEAOF`/replica sync all `fork()`. The fork itself copies **page tables** (not data); on a 100 GB heap with 4 KB pages that's a lot of page-table entries, so `latest_fork_usec` can reach tens to hundreds of ms — a latency spike for *every* client at that instant. Mitigations: **huge pages for page tables only via THP off but `hugepages` for the kernel**, smaller instances (shard so each is smaller), diskless sync, and scheduling saves during low traffic. **Transparent Huge Pages (THP)** *enabled* makes COW copy 2 MB pages instead of 4 KB on every small write during a save — amplifying COW memory and latency — so **disable THP** (`madvise`/`never`).

> **Term — page table:** the kernel's per-process map from virtual addresses to physical RAM frames. `fork()` must duplicate it so the child has its own mapping (even though the underlying pages are shared COW); for huge heaps this duplication is the dominant fork cost.

### 7.4 Replication internals & diskless

`PSYNC` negotiates full vs partial resync. **Diskless sync** (`repl-diskless-sync yes`) has the primary's fork child stream the RDB *directly into the replica socket(s)*, avoiding writing the RDB to the primary's disk — great for fast networks/slow disks; `repl-diskless-sync-delay` batches multiple replicas joining at once into a single fork. The **replication backlog** (`repl-backlog-size`) governs partial-resync eligibility; size it for `peak_write_bytes/s × expected_disconnect_seconds`.

### 7.5 Cluster slot migration mechanics

To move slot S from A to B: mark S `IMPORTING` on B and `MIGRATING` on A; `redis-cli --cluster reshard` (or manual `CLUSTER GETKEYSINSLOT` + `MIGRATE`) moves keys in batches. While migrating, A serves keys still present and returns **`ASK`** (one-shot redirect) for keys already moved; the client sends `ASKING` then the command to B. After all keys move, `CLUSTER SETSLOT S NODE B` makes ownership permanent (clients then get `MOVED`). This is fully online — no downtime, though large-value keys block during their individual `MIGRATE` (a known gotcha).

### 7.6 Consistency model & CAP positioning

> **Term — CAP theorem:** under a network **P**artition you can guarantee either **C**onsistency (every read sees the latest write) or **A**vailability (every request gets a response), not both. Systems pick a lean.

Redis (single-primary or cluster) is effectively **AP-leaning with async replication**: it favors availability/latency and can lose recently-acknowledged writes on failover (the replica that's promoted may not have the last writes). `WAIT` adds bounded synchronous replication but is not linearizable. Cluster's `cluster-require-full-coverage` and node-majority requirements add a CP-ish flavor (a minority partition stops serving), but acknowledged-write durability across failover is **not** guaranteed. **Do not assume linearizability**; if you need it, use a CP store (etcd/ZooKeeper/Spanner) for that slice of state.

### 7.7 Client-side caching (RESP3 tracking)

Redis 6 added **server-assisted client-side caching**: a client caches values locally and registers interest; the server sends **invalidation push messages** (over RESP3 or a second connection) when those keys change, so the client evicts its local copy. This removes even the network round-trip for hot reads. Two modes: **default** (server tracks each client's read keys) and **broadcast** (`BCAST` with key prefixes; server pushes all changes under a prefix, no per-key tracking). Lettuce supports this (`CLIENT TRACKING`). Great for read-heavy, low-churn data; watch for invalidation lag and local-memory use.

### 7.8 Keyspace notifications internals

Enabling `notify-keyspace-events` makes Redis publish to `__keyspace@db__:<key>` (which event happened to this key) and `__keyevent@db__:<event>` (which key had this event) on every relevant mutation. It's plain Pub/Sub (at-most-once, no replay). The `expired` event fires on actual deletion (lazy or active), so it can lag the TTL; the `evicted` event fires on eviction. Useful for cache-tiering and TTL-driven workflows, but don't rely on it for exactly-once semantics.

### 7.9 Redis Stack / modules

Beyond core Redis, **Redis Stack** bundles modules: **RediSearch** (secondary indexes + full-text + vector search), **RedisJSON** (native JSON documents with JSONPath), **RedisTimeSeries** (downsampling, retention), **RedisBloom** (Bloom/Cuckoo filters, count-min sketch, top-k), and **vector similarity** for embeddings (a common LLM/RAG building block).

> **Term — Bloom filter:** a probabilistic set membership structure that answers "definitely not present" or "possibly present" using a bit array and several hashes, in tiny space — never a false negative, only false positives. Used to skip expensive lookups for keys that surely don't exist (cache-penetration defense).

### 7.10 Lesser-known behaviors

- **`SETRANGE`/`SETBIT` past the end** zero-fills, so `SETBIT k 1000000 1` allocates ~125 KB instantly.
- **`SORT BY`/`GET` patterns** can sort one key and fetch fields from others — powerful but O(N log N) and easy to misuse.
- **`OBJECT FREQ`** (LFU mode) and **`OBJECT IDLETIME`** (LRU mode) expose eviction metadata.
- **`COPY`/`OBJECT ENCODING`/`DUMP`+`RESTORE`** enable cross-key/cross-instance moves with the native serialization (incl. TTL).
- **Blocking commands** (`BLPOP`, `BRPOPLPUSH`, `XREAD BLOCK`, `WAIT`, `BZPOPMIN`) block the *client*, not the server thread — Redis parks the client and wakes it when data arrives.
- **`SCAN` guarantees** a full traversal of keys present for the entire scan, may return some keys multiple times, and won't return keys deleted before the scan started — but is *weakly consistent* w.r.t. concurrent mutations.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Redis vs. alternatives

| System | Model | Strengths | Weaknesses vs Redis | Use when |
|---|---|---|---|---|
| **Memcached** | Multithreaded KV cache | Simple, multi-core, low memory overhead per item, great pure-cache throughput | No rich types, no persistence, no replication/cluster (client-sharded only), no pub/sub | Pure cache, multi-core node, no data-structure needs |
| **Redis** | Single-thread data-structure store | Rich types, scripting, persistence, replication/cluster, pub/sub/streams | Single-thread per shard; async durability | Caching + structures + coordination + light messaging |
| **Hazelcast / Apache Ignite** | In-memory data grid (JVM) | Embedded in JVM, distributed compute, near-cache, SQL | Heavier, JVM GC, ops complexity | JVM-native grid, compute-with-data |
| **Kafka** | Distributed log | Durable, high-throughput, ordered, huge retention | Heavyweight, higher latency, no KV/cache | Durable high-volume streaming |
| **etcd / ZooKeeper** | CP coordination store | Linearizable, strong consensus (Raft/ZAB) | Low throughput, small data | Locks/leader election needing strict correctness |
| **DynamoDB / Cassandra** | Disk-backed wide-column/KV | Durable, huge scale, persistent | Higher latency, $ per op | Durable scale-out KV system of record |

> **Term — in-memory data grid (IMDG):** a distributed cache/compute layer (e.g. Hazelcast, Ignite) that partitions and replicates objects across a cluster of (often JVM) nodes and can run computation co-located with data. Compared to Redis, IMDGs integrate tightly with the JVM but carry GC and ops overhead.

> **Term — ZAB:** ZooKeeper Atomic Broadcast, ZooKeeper's consensus protocol (Raft-like) giving totally-ordered, durable updates with a quorum — the reason ZooKeeper is a *correct* lock service while a single-instance Redis lock is not.

### 8.2 Persistence decision

- **Pure cache, refetchable from DB:** persistence off (or RDB only for warm restarts). Set `maxmemory`+eviction.
- **Durable, can tolerate ≤1 s loss:** AOF `everysec` (+ periodic RDB backups). Most durable production Redis.
- **Maximum durability:** AOF `always` (accept big throughput hit) + replicas + `WAIT`. Still not zero-loss across failover.
- **Fast restart / backups:** RDB (hybrid preamble) for quick load and easy snapshot copying.

### 8.3 Sentinel vs Cluster vs single

- **Single node:** dev, tiny workloads, or where you accept downtime on failure.
- **Sentinel (primary+replicas):** HA without sharding; data fits one node's RAM; you want simpler ops and full multi-key/transaction semantics.
- **Cluster:** data/throughput exceeds one node; accept multi-key constraints (hash tags) and more ops complexity.
- **Managed (ElastiCache/Memorystore/Redis Cloud):** offload ops; pick when you don't want to run failover/upgrades yourself.

### 8.4 Data-structure selection cheat

| Goal | Structure | Key commands |
|---|---|---|
| Cache value / counter | String | `SET/GET/INCR` |
| Object with fields | Hash | `HSET/HGET/HINCRBY` |
| FIFO/LIFO queue | List | `LPUSH/BRPOP/BLMOVE` |
| Unique membership / tags / set algebra | Set | `SADD/SISMEMBER/SINTER` |
| Ranking / priority / time index | Sorted set | `ZADD/ZRANGE/ZPOPMIN` |
| Dense boolean flags at scale | Bitmap | `SETBIT/BITCOUNT` |
| Approx unique count | HyperLogLog | `PFADD/PFCOUNT` |
| Durable queue w/ acks & replay | Stream | `XADD/XREADGROUP/XACK` |
| Live fan-out (lossy) | Pub/Sub | `PUBLISH/SUBSCRIBE` |
| Geo radius | Geo | `GEOADD/GEOSEARCH` |
| Membership pre-filter | Bloom (module) | `BF.ADD/BF.EXISTS` |

### 8.5 Caching strategy

| Pattern | How | Use when | Caveat |
|---|---|---|---|
| **Cache-aside (lazy)** | App reads cache; miss → load DB → populate | General default | Stampede risk; first read slow |
| **Read-through** | Cache layer loads on miss | Encapsulated cache lib | Needs cache that supports it |
| **Write-through** | Write cache + DB synchronously | Read-heavy, consistency-sensitive | Slower writes |
| **Write-behind (write-back)** | Write cache now, DB async later | Write-heavy, tolerate loss window | Data loss if cache dies before flush |
| **TTL + jitter** | Expire with randomized TTL | Avoid synchronized expiry stampede | Tune jitter |

---

## 9. Failure modes & debugging

### 9.1 Latency spikes

**Symptoms:** p99 jumps, clients time out periodically.
**Common causes & diagnosis:**
- **Slow O(N) command** → `SLOWLOG GET 25`; look for `KEYS`, `SMEMBERS`, big `SORT`. Fix: replace with `SCAN`/ranged ops; kill offenders.
- **Fork stall during save/rewrite** → `INFO persistence` (`latest_fork_usec`, `rdb_bgsave_in_progress`); `LATENCY HISTORY fork`. Fix: disable THP, shard smaller, diskless sync, schedule saves off-peak.
- **AOF fsync stall** (`appendfsync always` or slow disk) → `LATENCY DOCTOR` flags `aof-fsync-always`/`aof-write`. Fix: `everysec`, faster disk.
- **Eviction churn** under `maxmemory` → `INFO stats` `evicted_keys` rising; spikes. Fix: more RAM, better TTLs, raise `maxmemory-samples` only if needed.
- **Expired-key avalanche** (many keys expiring at once) → CPU burst in active expiration. Fix: jitter TTLs.
- **Swapping** (RSS > RAM) → check OS; `mem_fragmentation_ratio < 1`. Fix: reduce memory, never overcommit physical RAM.

### 9.2 Out-of-memory / write rejections

**Symptom:** `OOM command not allowed when used memory > 'maxmemory'`.
**Diagnosis:** `INFO memory` (`used_memory` vs `maxmemory`), `MEMORY DOCTOR`, find big keys via `redis-cli --bigkeys` or `MEMORY USAGE`.
**Fixes:** raise `maxmemory`/RAM, choose an eviction policy (not `noeviction`), add TTLs, split big keys, compact encodings.

### 9.3 Hot key / hot shard

**Symptom:** one key or one cluster node is overwhelmed; single-thread saturates.
**Diagnosis:** `redis-cli --hotkeys` (LFU mode), per-node `INFO`/`instantaneous_ops_per_sec`, `CLUSTER SLOTS`.
**Fixes:** client-side caching for the hot key; shard the value (e.g. split a counter into N sub-counters summed on read); add read replicas for read-hot keys; for cluster, re-key with hash tags to rebalance.

### 9.4 Replication problems

- **Replica lag** → `INFO replication` offsets; `master_repl_offset` − replica offset. Causes: slow replica, network, big write bursts, small backlog. Fix: bigger backlog, faster replica, throttle writes.
- **Repeated full resyncs** → backlog too small or unstable link. Fix: `repl-backlog-size` up, fix network.
- **Replica buffer overrun** → `client-output-buffer-limit slave` kills it mid-sync; tune higher.

### 9.5 Failover data loss & split-brain

- **Async replication loss:** writes acked by old primary not yet on the promoted replica are lost. Mitigate with `min-replicas-to-write`/`min-replicas-max-lag` (primary refuses writes if it can't reach enough fresh replicas) and `WAIT` for critical writes.

  > **Term — `min-replicas-to-write` / `min-replicas-max-lag`:** the primary will reject writes unless at least N replicas are connected and within M seconds of lag — a guardrail trading availability for reduced data-loss-on-failover.

- **Split-brain in Sentinel/Cluster:** mitigated by majority quorum; ensure odd numbers (3/5) of Sentinels/primaries across failure domains; never run 2 Sentinels.

### 9.6 Connection storms

**Symptom:** `rejected_connections`, `maxclients` (default 10000) hit, latency from accept backlog.
**Diagnosis:** `INFO clients`, `CLIENT LIST`.
**Fixes:** connection pooling/sharing, raise `maxclients` (and OS `ulimit -n`), close idle clients (`timeout`), fix client leaks.

### 9.7 Cluster routing errors in app

- `MOVED` not followed → stale slot map in client; use a cluster-aware client and refresh topology.
- `CROSSSLOT` on multi-key ops → add **hash tags** to colocate keys.
- During reshard, transient `ASK` → ensure client handles `ASKING`.

### 9.8 Real-world incident patterns (illustrative)

- **The `KEYS *` outage:** a debug/cron job runs `KEYS user:*` against a 40M-key prod instance; the single thread blocks for seconds; every request times out; cascading failures upstream. Lesson: ban `KEYS` (rename it), use `SCAN`.
- **The big-`DEL` stall:** deleting a 6 GB hash with `DEL` froze the server for ~700 ms; replicas lagged; clients errored. Lesson: `UNLINK`/lazyfree.
- **Stampede after deploy:** a cache flush + cold restart sent all traffic to the DB simultaneously (thundering herd) and took the DB down. Lesson: warm caches, stagger TTLs, single-flight locks.
- **Lock double-grant on failover:** a primary granted a lock, crashed before replicating, a replica was promoted and granted the same lock to another client → two workers processed the same order. Lesson: fencing tokens / CP coordinator for correctness-critical locks.
- **THP latency:** default-enabled Transparent Huge Pages turned every `BGSAVE` into multi-hundred-ms p99 spikes via COW of 2 MB pages. Lesson: disable THP (Redis logs a warning at startup if it's on).

### 9.9 Diagnostic command crib

```
redis-cli INFO everything            # full snapshot
redis-cli --latency / --latency-history   # measure RTT/latency over time
redis-cli --bigkeys                  # sample largest keys per type
redis-cli --hotkeys                  # hottest keys (needs LFU)
redis-cli --memkeys                  # sample biggest memory keys
redis-cli SLOWLOG GET 50             # recent slow commands
redis-cli LATENCY DOCTOR             # human-readable latency analysis
redis-cli MEMORY DOCTOR              # memory analysis
redis-cli CLIENT LIST                # connections, buffers, idle
redis-cli CLUSTER NODES / INFO       # cluster topology/health
redis-cli --cluster check host:port  # validate cluster consistency
redis-cli MONITOR                    # (DEBUG ONLY) live command feed
```

---

## 10. Interview drill

**Q1. Why is Redis single-threaded, and how is it still so fast?**
*Model answer:* The command-execution core is one thread, so there are no locks, atomics, or context switches on the hot path, and every command is atomic by construction. It's fast because data is in RAM (~100 ns access), data structures are hand-optimized C with compact encodings, and an `epoll` event loop multiplexes thousands of connections. The bottleneck is CPU/memory bandwidth, not I/O. Modern Redis adds I/O threads (for socket read/write/parse) and background threads (fork-based persistence, lazy free), but command logic stays single-threaded to preserve atomicity.
*Probes:* (a) *What's the downside?* Any slow command (`KEYS *`, big `SMEMBERS`, long Lua) blocks every client — no preemption. (b) *What do I/O threads parallelize?* Reading/parsing requests and writing replies, not command execution. (c) *How do you scale past one core?* Shard with Redis Cluster (each shard is its own thread) or add replicas for read scaling.

**Q2. Walk me through RDB vs AOF and what you'd choose.**
*Model answer:* RDB is a point-in-time binary snapshot via `fork()`+copy-on-write — compact, fast to load, but coarse durability (lose everything since last snapshot). AOF logs every write command and fsyncs per `appendfsync` (`always`/`everysec` default/`no`), giving fine durability (≤1 s with everysec) but larger files and slower load; it compacts via `BGREWRITEAOF`. Hybrid (`aof-use-rdb-preamble`) gives RDB-fast load + AOF durability. For a cache I might disable persistence; for durable use, AOF everysec + periodic RDB backups.
*Probes:* (a) *Why fork?* COW lets a child snapshot a consistent frozen view while the parent keeps serving; only modified pages duplicate. (b) *Fork cost?* Copying page tables — tens to hundreds of ms on huge heaps (`latest_fork_usec`); disable THP to limit COW amplification. (c) *Does AOF always guarantee no loss?* No — replication is async and even `always` can lose writes across failover; use `WAIT`/`min-replicas-*` to reduce it.

**Q3. Sentinel vs Cluster — when each?**
*Model answer:* Sentinel provides HA (monitoring + automatic failover + discovery) for a single primary/replica group; it does **not** shard, and you keep full multi-key/transaction semantics. Cluster shards the keyspace across 16384 hash slots over multiple primaries (each with replicas) and gives both sharding and failover, but multi-key ops/transactions/Lua must stay within one slot (use hash tags). Choose Sentinel when one node's RAM/throughput suffices and you want simpler ops; Cluster when you must scale beyond one node.
*Probes:* (a) *How does Cluster route?* `CRC16(key) mod 16384` → slot → node; wrong node replies `MOVED`/`ASK`. (b) *How does Sentinel avoid split-brain?* Quorum + majority leader election (Raft-like); run 3/5 sentinels. (c) *Resharding?* Live slot migration with `MIGRATING/IMPORTING` and `ASK` redirects; online but big keys block during their `MIGRATE`.

**Q4. How would you implement a correct distributed lock?**
*Model answer:* Acquire with `SET lock token NX PX ttl` (atomic, unique token, TTL so a dead holder releases). Release with a Lua script that deletes only if the token matches (so you never delete someone else's lock). For correctness across failover, single-instance locks are unsafe (async replication can lose the grant); Redlock acquires on a majority of independent masters but is contested. The robust answer is **fencing tokens**: issue a monotonically increasing number and have the protected resource reject stale tokens; or use a CP store (etcd/ZooKeeper) for correctness-critical locks.
*Probes:* (a) *Why not plain `DEL` to release?* Your lock may have expired and another owner holds it; you'd delete theirs. (b) *Why is Redlock criticized?* Clock drift and GC/pause can let two clients both believe they hold it; TTL+timing isn't enough without fencing. (c) *When is a Redis TTL lock fine?* When it's a performance optimization (avoid duplicate work), not a correctness guarantee.

**Q5 (senior signal). When would you NOT use Redis, and what would you use instead?**
*Model answer:* Avoid Redis as the sole store of data you can't lose (async durability + failover loss), when the dataset vastly exceeds affordable RAM, when you need linearizable/serializable multi-key correctness (use a CP store or RDBMS), for durable high-throughput streaming at scale (Kafka), or for ad-hoc analytical querying. Use Memcached for pure multi-core caching with no structure needs; etcd/ZooKeeper for correct coordination; Kafka for durable logs; a relational/NoSQL DB as system of record.
*Probes:* (a) *Redis can do streams — why Kafka?* Kafka offers far higher retention/throughput and stronger durability/ordering guarantees; Redis Streams are great for lighter workloads. (b) *CAP position of Redis?* AP-leaning, async replication; not linearizable. (c) *How big is "too big" for RAM?* When RAM cost dominates and most data is cold — consider tiered/flash or a disk-backed store.

**Q6. Explain MULTI/EXEC and how it differs from SQL transactions.**
*Model answer:* `MULTI` queues commands, `EXEC` runs them atomically with no interleaving (single thread). But there's **no rollback**: a runtime error on one queued command doesn't undo the others; only queue-time syntax errors abort the whole block. `WATCH` adds optimistic CAS — abort `EXEC` if a watched key changed. Unlike SQL, there's no isolation-level machinery or undo log; for conditional read-modify-write, Lua scripts are usually cleaner.
*Probes:* (a) *Why no rollback?* Such errors are deemed programming bugs; rollback would burden the common case. (b) *Lua vs WATCH?* Lua runs atomically server-side with conditional logic — no retry loop. (c) *Cluster?* All keys in a transaction must share a slot.

**Q7. How does key expiration actually work, and what are the gotchas?**
*Model answer:* Two mechanisms: **lazy** (delete on access if expired) and **active** (`serverCron` samples the expires dict, deletes expired, repeats if >25% of the sample was expired). So an unaccessed expired key lingers in RAM until sampled — TTL-based memory bounds are soft. Replicas don't expire independently; the primary sends explicit `DEL`/`UNLINK`. Mass simultaneous expiry causes CPU bursts — jitter TTLs.
*Probes:* (a) *Does a replica serve a logically-expired key?* Modern versions hide it on reads. (b) *`expired` keyspace event timing?* Fires on actual deletion, so it lags the logical TTL. (c) *How to bound memory if TTLs are soft?* `maxmemory` + eviction as a backstop.

**Q8. Pub/Sub vs Streams?**
*Model answer:* Pub/Sub is fire-and-forget, at-most-once, no storage/replay; offline or slow subscribers miss messages. Streams are a durable append-only log with consumer groups, per-message acks (`XACK`), a pending entries list, and claim/reclaim for crash recovery — at-least-once, replayable, load-balanced. Use Pub/Sub for ephemeral fan-out; Streams for durable work queues / event sourcing.
*Probes:* (a) *How do consumer groups recover from a dead consumer?* `XPENDING`/`XAUTOCLAIM` reassign its unacked PEL entries. (b) *Trimming?* `MAXLEN ~ N` (approximate) keeps memory bounded. (c) *Streams vs Kafka?* Lighter, lower retention/throughput, no partitions/log compaction at Kafka scale.

**Q9 (senior signal). A hot key is saturating one CPU/shard. How do you fix it?**
*Model answer:* Diagnose with `--hotkeys` and per-node ops. Options: (1) **client-side caching** so reads skip Redis entirely; (2) **value sharding** — split a hot counter into N sub-keys, increment a random one, sum on read; (3) **read replicas** for read-hot keys; (4) re-key with **hash tags** to spread a hot slot; (5) for a hot write path, batch/coalesce updates. The single-thread constraint means you can't just "add cores" to one shard — you redistribute load.
*Probes:* (a) *Why doesn't adding RAM/CPU to the node help?* One thread per shard handles that key; you need fan-out. (b) *Downside of value sharding?* Read becomes O(N) sub-keys + slight staleness. (c) *Replica consistency?* Async lag means replicas can serve slightly stale reads.

**Q10 (senior signal). Design the durability/HA posture for a Redis that holds payment idempotency keys.**
*Model answer:* This is correctness-sensitive, so: AOF `everysec` (or `always` if the write rate allows) + periodic RDB backups; replicas + Sentinel/Cluster for HA; `min-replicas-to-write 1`/`min-replicas-max-lag 10` so the primary refuses writes when it can't reach a fresh replica (reducing failover loss); `WAIT 1 100` on the critical idempotency write to confirm replication before acking the business operation; **fencing tokens** if any locking is involved. Crucially, treat Redis as a *fast guard*, not the sole source of truth — back it with the database's own unique constraint so a Redis loss can't double-charge.
*Probes:* (a) *Why not rely on Redis alone?* Async replication/failover can lose the last writes; a DB unique constraint is the real backstop. (b) *Cost of `appendfsync always` + `WAIT`?* Big latency/throughput hit — measure and bound. (c) *What if `WAIT` times out?* Treat as not-durably-committed; fail the operation or fall back to the DB check.

**Q11. What encodings does a sorted set use and why?**
*Model answer:* Small zsets use a **listpack** (compact contiguous bytes) to save memory; once they exceed `zset-max-listpack-entries`/`-value`, they promote to a **skiplist** (O(log N) ordered ops) paired with a **dict** (O(1) `ZSCORE`). The listpack saves RAM for the common small case; the skiplist+dict gives scalable ordered operations for large sets. Inspect with `OBJECT ENCODING`.
*Probes:* (a) *Why skiplist over a balanced tree?* Simpler, good cache behavior, easy range/rank ops. (b) *Why also a dict?* O(1) member→score lookup. (c) *Tuning the threshold?* Bigger listpacks save memory but slow per-op since listpack ops are O(n).

**Q12. How do you investigate "Redis got slow"?**
*Model answer:* `SLOWLOG GET` for slow commands; `LATENCY DOCTOR`/`HISTORY` for spike sources (fork, AOF fsync, eviction, expiry, slow command); `INFO` for memory pressure (eviction/fragmentation/swap), persistence state (`rdb_bgsave_in_progress`, `latest_fork_usec`), replication lag, and client/buffer issues; `--bigkeys`/`--hotkeys` for skew. Then fix the root cause (replace O(N) commands, disable THP, tune persistence, add RAM/eviction, shard hot keys).
*Probes:* (a) *First command you run?* `SLOWLOG GET` and `LATENCY DOCTOR`. (b) *Fragmentation ratio <1 means?* Swapping — very bad. (c) *MONITOR in prod?* Avoid — it serializes and copies every command, huge overhead.

---

## 11. Glossary

- **ACL (Access Control List):** per-user permissions (commands, key patterns, channels) in Redis ≥6 for least-privilege access.
- **AOF (Append-Only File):** persistence by logging every write command; replayed on restart; fsynced per `appendfsync`.
- **AP / CP (CAP):** under a partition, choose Availability or Consistency. Redis is AP-leaning.
- **Atomicity:** an operation fully applies or not at all; in Redis, each command (and each Lua script / EXEC block) is atomic via the single thread.
- **Bitmap:** a string treated as a bit array; per-bit set/get/count.
- **Bloom filter:** probabilistic set membership; "definitely not / possibly yes," no false negatives.
- **Cache-aside:** app checks cache, on miss loads DB and populates cache.
- **Cache stampede / thundering herd:** many clients miss the same key simultaneously and overload the backend.
- **CAS (compare-and-set):** update only if the value hasn't changed; Redis `WATCH` enables it.
- **Cluster bus:** the gossip protocol/port (6379+10000) Redis Cluster nodes use to share topology/health.
- **Consumer group (Streams):** a set of consumers splitting a stream's load with shared cursor and per-consumer pending list.
- **Copy-on-write (COW):** shared memory pages physically duplicated only on first write; enables low-cost `fork()` snapshots.
- **CRC16:** the hash used to map a key to one of 16384 cluster slots.
- **Eviction:** dropping keys when `maxmemory` is hit, per `maxmemory-policy`.
- **epoll / kqueue:** OS readiness-notification mechanisms underpinning the event loop.
- **Event loop:** one thread waiting on many sockets and handling each ready one in turn.
- **Fencing token:** a monotonically increasing lock-grant number; resources reject stale tokens to prevent two-holder bugs.
- **fork():** Unix syscall creating a child process (COW-shared memory); used for background saves.
- **fsync:** syscall flushing OS-buffered file writes to physical disk.
- **Hash slot:** one of 16384 partitions of the cluster keyspace.
- **Hash tag:** `{...}` substring forcing keys into the same slot for multi-key ops.
- **HA (high availability):** staying up through node failures via automatic failover.
- **HyperLogLog (HLL):** probabilistic distinct-count estimator (~0.81% error, ~12 KB).
- **IMDG (in-memory data grid):** distributed in-memory cache/compute (Hazelcast/Ignite).
- **jemalloc:** Redis's default allocator, chosen for low fragmentation.
- **Keyspace notifications:** Pub/Sub events on key mutations/expirations/evictions.
- **Lazy free (`UNLINK`):** removing a key instantly and freeing its memory in a background thread.
- **listpack / ziplist / quicklist / intset / skiplist:** compact/internal encodings for small/large collections.
- **LRU / LFU:** least-recently-used / least-frequently-used eviction strategies (approximate in Redis).
- **maxmemory:** the memory cap triggering eviction.
- **MOVED / ASK:** cluster redirects (permanent / migration-temporary).
- **MULTI/EXEC/WATCH:** Redis transactions and optimistic locking.
- **OCC (optimistic concurrency control):** proceed assuming no conflict, abort/retry at commit if one occurred.
- **Page table:** kernel's virtual→physical address map; duplicated on `fork()`.
- **PEL (Pending Entries List):** per-consumer delivered-but-unacked stream entries.
- **Pipelining:** sending many commands without waiting for replies, to amortize RTT (not atomic).
- **Pub/Sub:** fire-and-forget channel messaging (at-most-once, no storage).
- **Quorum / majority:** minimum agreeing voters; majority prevents split-brain.
- **Raft / ZAB:** consensus algorithms (used by Sentinel election / ZooKeeper) for agreement despite failures.
- **RDB:** point-in-time binary snapshot persistence.
- **Redlock:** multi-master Redis distributed-lock algorithm (contested for correctness).
- **Replication backlog:** circular buffer enabling partial resync after brief replica disconnects.
- **RESP (RESP2/RESP3):** the Redis wire protocol; RESP3 adds typed replies and push messages.
- **RTT (round-trip time):** network latency for a request/response; what pipelining hides.
- **Sentinel:** HA monitor/failover/discovery for a single primary/replica group.
- **serverCron:** periodic housekeeping (expiration, eviction, rehash, stats) run `hz` times/s.
- **Skiplist:** layered linked list giving O(log N) ordered operations (sorted sets).
- **Slot migration / resharding:** moving hash slots between cluster nodes online.
- **Sorted set (ZSet):** members ordered by a numeric score.
- **Split-brain:** two partitioned halves both acting as primary; prevented by majority quorum.
- **Stream:** durable append-only log with consumer groups (`XADD`/`XREADGROUP`/`XACK`).
- **THP (Transparent Huge Pages):** kernel feature that worsens COW latency for Redis; disable it.
- **TTL:** per-key time-to-live before expiration.
- **WAIT:** command for bounded synchronous replication.
- **ZooKeeper / etcd:** CP coordination services for correct locks/leader election.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Model:** one thread executes commands (atomic-by-construction) over RAM-resident typed structures; I/O & persistence offloaded to helper threads/forks. Slow command = whole-server stall.

**Latency intuition:** RAM ~100 ns, SSD random ~100 µs, disk seek ~10 ms. Default port **6379**. Cluster bus **+10000**.

**Types:** string, list, hash, set, zset, bitmap, HLL, stream, geo, bitfield. Pick by access pattern (see §8.4).

**Complexity rules:** GET/SET/INCR/HGET O(1); ZADD/ZRANGE O(log N + M); avoid O(N) `KEYS`/`SMEMBERS`/`HGETALL`/`LRANGE 0 -1` — use `SCAN` family.

**Persistence:** RDB = snapshot (coarse, fast load); AOF = command log (`always`/`everysec`(default)/`no`); hybrid preamble = best of both. Async replication ⇒ possible loss on failover. `WAIT`, `min-replicas-*` reduce it.

**Memory:** set `maxmemory` + policy (`allkeys-lru`/`-lfu`, `volatile-*`, `noeviction` default). jemalloc; watch `mem_fragmentation_ratio` (>1.5 frag, <1 swap). Disable THP; `vm.overcommit_memory=1`.

**Distribution:** Sentinel = HA, no sharding, single-primary semantics. Cluster = 16384 slots (`CRC16 mod 16384`), shards + failover, multi-key needs same slot (hash tags), `MOVED`/`ASK`.

**Atomicity tools:** single commands; `MULTI/EXEC` (no rollback) + `WATCH` (CAS); Lua/`FUNCTION` (atomic RMW). Pipelining = throughput, not atomicity.

**Messaging:** Pub/Sub (lossy fan-out) vs Streams (durable, consumer groups, `XACK`, PEL, claim).

**Patterns:** cache-aside + single-flight lock + TTL jitter; rate limiter (Lua sliding window); leaderboard (zset); lock (`SET NX PX` + token-checked Lua release + fencing); queue (`BLMOVE` reliable / Streams).

**Debug:** `SLOWLOG`, `LATENCY DOCTOR`, `INFO`, `--bigkeys`/`--hotkeys`, `MEMORY DOCTOR`. Never `KEYS`/`MONITOR`/sync big `DEL` in prod (use `SCAN`/`UNLINK`).

**Decision rules:** Redis when working set fits RAM, sub-ms latency, atomic structure ops, tolerable async durability. Not as sole store of un-loseable data, not for >>RAM datasets, not for linearizable multi-key correctness (use etcd/ZooKeeper), not for big durable streaming (Kafka).

### 12.2 Self-test (no answers — recall practice)

1. Explain precisely why a `KEYS *` on a large instance causes a full-server outage, and what mechanism would have to change for it not to.
2. Trace a single `SET k v EX 60` from socket bytes to reply, naming every subsystem it touches (parsing, OOM/cluster/ACL checks, AOF, replication, notifications).
3. You need ≤1 second of acceptable data loss and fast restarts. Specify the exact persistence configuration and justify each flag.
4. Design a sliding-window rate limiter that is correct under high concurrency, and explain why separate `ZREMRANGEBYSCORE`/`ZCARD`/`ZADD` commands would be wrong.
5. A teammate's single-instance Redis lock occasionally lets two workers process the same job. Diagnose all the ways this can happen and give a correctness-preserving design.
6. Compare Sentinel and Cluster across sharding, multi-key semantics, failover, and ops complexity; pick one for a 300 GB working set and defend it.
7. Your p99 latency spikes every few minutes in sync with `BGSAVE`. List the OS- and Redis-level causes and the exact settings you'd change.
8. When would you choose Memcached, Kafka, or etcd over Redis? Give a concrete scenario for each.
```
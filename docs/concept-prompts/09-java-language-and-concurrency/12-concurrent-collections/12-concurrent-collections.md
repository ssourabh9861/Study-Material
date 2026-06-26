# Concurrent Collections

> A definitive engineering-handbook chapter on Java's concurrent collection types — from first principles to deep internals, tuning, and production debugging.

---

## 1. Overview & where it fits

### What it is

A **concurrent collection** is a data structure (map, list, set, queue) that is *designed* to be accessed and mutated by multiple threads at the same time, with a precisely-defined thread-safety contract and — crucially — *good throughput under contention*. They live in the `java.util.concurrent` package (often abbreviated **JUC**), which was introduced in Java 5 (2004) as part of JSR-166, led by Doug Lea.

Before JUC, your only thread-safe options were:

- **The "legacy synchronized" collections**: `java.util.Vector` and `java.util.Hashtable`. Every public method is `synchronized` on the object monitor, so the *entire* collection is one big lock. Two threads can never touch it at once, even for reads.
- **`Collections.synchronizedXxx(...)` wrappers**: e.g. `Collections.synchronizedMap(new HashMap<>())`. These wrap a normal collection and route every call through a single lock object. Same coarse-grained problem.

Concurrent collections fix the throughput problem by using **finer-grained locking, lock-free algorithms, or copy-on-write**, depending on the access pattern they are optimized for.

> **Mental model (one paragraph):** A `synchronized` collection is a shop with one cashier and one queue — correct, but everyone waits behind one person. A concurrent collection is a supermarket with many checkout lanes (and sometimes self-checkout that never blocks). The trick each concurrent collection uses to add "lanes" — striping the lock per bucket, copying the whole array on write, or using atomic CPU instructions instead of locks — determines exactly which workloads it makes fast and which it makes slow. Choosing the right one is mostly about matching the collection's optimization to your read/write ratio and your contention level.

### The problem it solves

When multiple threads share mutable state, you face two classes of bugs:

1. **Race conditions** — the outcome depends on the unpredictable interleaving of thread operations (e.g. two threads both read a counter as 5, both write 6, so one increment is lost — a *lost update*).
2. **Memory-visibility bugs** — without proper synchronization, a write by thread A may *never become visible* to thread B, because of CPU caches, store buffers, and compiler/JIT reordering. The **Java Memory Model (JMM)** defines exactly when one thread's writes are guaranteed visible to another (via the *happens-before* relation, explained below).

Concurrent collections give you a data structure that is correct under concurrent access *and* establishes the right happens-before edges, so you don't have to hand-roll locking around a plain `HashMap` (which is shockingly easy to get wrong — an unsynchronized `HashMap` resized concurrently in Java 7 could spin forever in an infinite loop).

> **Happens-before** is the JMM's formal "this is guaranteed visible" relationship. If action A *happens-before* action B, then A's memory effects are visible to B. Releasing a lock happens-before acquiring that same lock; writing a `volatile` field happens-before a later read of it; a thread's `start()` happens-before everything the started thread does. Concurrent collections document their happens-before guarantees: e.g. *actions in a thread prior to placing an object into a `BlockingQueue` happen-before actions subsequent to the access or removal of that element from the queue.*

### When you reach for it

- You have shared state mutated by multiple threads and you want **safe, high-throughput access** without writing your own locks.
- A **producer–consumer pipeline** (threads handing work to other threads) → reach for a `BlockingQueue`.
- A **shared cache, registry, or counter map** read and written by many threads → `ConcurrentHashMap`.
- A **rarely-changing listener/config list** read constantly, written almost never → `CopyOnWriteArrayList`.
- A **sorted, concurrently-accessed map** (leaderboard, time-ordered index, range queries) → `ConcurrentSkipListMap`.
- A **high-throughput unbounded buffer** where you never want to block → `ConcurrentLinkedQueue`.

### When you do *not* reach for it

- Single-threaded code, or state confined to one thread (thread confinement) — use plain `HashMap`/`ArrayList`; they're faster and simpler.
- State you guard with *your own* coarse lock anyway, where the collection access is a tiny fraction of the critical section.
- Cases where you need a **compound multi-step transaction** across the collection that the collection's atomic methods don't cover — a concurrent collection makes *individual operations* atomic, not arbitrary sequences (this is the #1 pitfall, covered in depth below).

---

## 2. Foundations from first principles

This section defines every term we'll lean on. Skip ahead if you already know them, but the rest of the doc assumes these.

### 2.1 Thread, race, and atomicity

- **Thread**: an independent path of execution within a process, scheduled by the OS onto CPU cores. Threads in the same process share heap memory — which is exactly why shared collections need care.
- **Critical section**: a region of code that accesses shared state and must not be run by two threads simultaneously.
- **Atomic operation**: one that appears to happen all-at-once — no other thread can observe a half-done state. `i++` is *not* atomic: it's read, add, write (three steps). A race on `i++` loses updates.
- **Race condition**: a bug where correctness depends on timing/interleaving.
- **Lost update**: a specific race where two threads read-modify-write the same value and one update vanishes.

### 2.2 The Java Memory Model (JMM), visibility, and reordering

Modern CPUs and JIT compilers reorder instructions and cache values in registers and per-core caches for speed. Without synchronization, thread B might read a *stale* value of a field thread A already updated.

- **`volatile`**: a field modifier that guarantees (a) reads/writes go to main memory (visibility), and (b) reads/writes are not reordered across the volatile access in ways that break the happens-before edge. It does *not* make compound actions atomic.
- **Happens-before**: defined above — the JMM's ordering guarantee.
- **Synchronization actions**: lock acquire/release, volatile read/write, `Thread.start`/`join`, etc. — these create happens-before edges.

Concurrent collections internally use `volatile` fields, `synchronized` blocks, and low-level atomic operations to establish these edges so that, for example, an object you `put` into a `ConcurrentHashMap` is fully visible to a thread that later `get`s it.

### 2.3 Locks, lock-free, and wait-free

- **Lock (mutex / monitor)**: a mechanism ensuring mutual exclusion. In Java, `synchronized` uses an object's *intrinsic monitor*; `java.util.concurrent.locks.ReentrantLock` is an explicit lock object. While one thread holds the lock, others block (wait, not running) until it's released.
- **Blocking**: a thread that cannot proceed is parked by the OS — it consumes no CPU but incurs a context switch (saving/restoring thread state, ~1–10 microseconds, and cache pollution).
- **Lock-free**: an algorithm where *the system as a whole* always makes progress — at least one thread completes its operation in a bounded number of steps — without using locks. Achieved with **CAS** (below). A thread may retry, but no thread can block all others by holding a lock and getting suspended.
- **Wait-free**: a stronger property where *every* thread completes in a bounded number of steps. Rarer and harder.
- **CAS (Compare-And-Swap / Compare-And-Set)**: an atomic CPU instruction (e.g. `CMPXCHG` on x86, `LDXR/STXR` on ARM): "if memory location X currently holds value `expected`, atomically set it to `new` and report success; otherwise do nothing and report failure." It's the foundational primitive of lock-free programming. In Java you reach it via `java.util.concurrent.atomic.*` classes and, internally, `sun.misc.Unsafe` / `VarHandle`.
- **ABA problem**: a subtle CAS hazard. Thread reads value A, plans to CAS A→C, but meanwhile another thread changed A→B→A. The CAS *succeeds* (value is still A) even though the world changed underneath. Solved with version stamps (`AtomicStampedReference`) or by structural guarantees. We'll see how Michael-Scott queues sidestep it.
- **Memory reclamation hazard**: in lock-free structures you must not free a node another thread might still be reading. The JVM dodges this entirely because the **garbage collector** won't reclaim a node while any thread holds a reference — a huge reason lock-free code is *easier* on the JVM than in C/C++.

### 2.4 Iterator semantics — fail-fast vs weakly consistent vs snapshot

This distinction is central to concurrent collections; you *must* internalize it.

- **Fail-fast iterator** (plain `HashMap`, `ArrayList`): detects *structural modification* during iteration (via a `modCount` field) and throws `ConcurrentModificationException` (CME) on the next `next()`/`hasNext()`. It's a *best-effort bug detector*, not a guarantee. CME can also happen single-threaded if you modify a collection while looping over it.
- **Weakly consistent iterator** (most JUC collections — `ConcurrentHashMap`, `ConcurrentLinkedQueue`, `ConcurrentSkipListMap`, `LinkedBlockingQueue`): never throws CME. It traverses elements as they existed at or since iterator creation. It *may or may not* reflect modifications made after the iterator was created. It guarantees each element is returned at most once and won't NPE on concurrent removal. This is the dominant style in JUC.
- **Snapshot iterator** (`CopyOnWriteArrayList`/`Set`): iterates over an immutable snapshot of the array taken at iterator-creation time. It *never* reflects later changes and does not support `remove()` via the iterator (throws `UnsupportedOperationException`).

### 2.5 The taxonomy of concurrent collections

| Family | Members | Core technique | Best for |
|---|---|---|---|
| Concurrent maps | `ConcurrentHashMap`, `ConcurrentSkipListMap` | Bin/bucket-level locking + CAS; lock-free skip list | High-throughput shared maps; sorted maps |
| Copy-on-write | `CopyOnWriteArrayList`, `CopyOnWriteArraySet` | Replace whole array on every write | Read-mostly, write-rarely lists/sets |
| Non-blocking queues | `ConcurrentLinkedQueue`, `ConcurrentLinkedDeque` | Lock-free Michael-Scott (deque: doubly-linked) | High-throughput unbounded buffering, no blocking |
| Blocking queues | `ArrayBlockingQueue`, `LinkedBlockingQueue`, `SynchronousQueue`, `PriorityBlockingQueue`, `DelayQueue`, `LinkedBlockingDeque`, `LinkedTransferQueue` | Locks + condition variables (or lock-free for `LinkedTransferQueue`) | Producer–consumer hand-off with backpressure / waiting |
| Concurrent sets | `ConcurrentHashMap.newKeySet()`, `ConcurrentSkipListSet`, `CopyOnWriteArraySet` | Backed by the corresponding map/list | Concurrent sets |

We'll go deep on each.

---

## 3. How it works internally

This is the heart of the document. We go structure by structure.

### 3.1 `ConcurrentHashMap` (CHM) — the crown jewel

`ConcurrentHashMap` is the most important class here and the one interviewers probe hardest. Its internals changed dramatically between Java 7 and Java 8. We cover Java 8+ (the current design) in depth, then contrast with Java 7.

#### 3.1.1 The Java 7 design (segments) — historical context

In Java 5–7, a CHM was an **array of *segments***, where each segment was essentially a small standalone hash table with its own `ReentrantLock`. The default was **16 segments** (the `concurrencyLevel`). A key's hash chose a segment; writes locked only that segment, so up to 16 threads could write concurrently to different segments. This is called **lock striping**: instead of one lock for the whole map, you have N locks, each guarding 1/N of the data.

Limitations of the segment design:
- Concurrency was capped at `concurrencyLevel` (default 16) regardless of map size.
- Each segment had memory overhead even when small.
- `size()` had to lock all segments (or do an optimistic retry loop).

#### 3.1.2 The Java 8+ design — no more segments

Java 8 rewrote CHM completely. **There are no segment locks anymore.** The structure is:

- A single `volatile Node<K,V>[] table` — the bucket array (a.k.a. *bins*). `volatile` so that publication of a newly-allocated table is visible to all threads.
- Each array slot (bin) holds either: `null` (empty), a **linked list of `Node`s** (collision chain), or — if the chain gets long — a **red-black tree** of `TreeNode`s.
- Concurrency is now at the **per-bin level**, using a mix of **CAS for the common case and `synchronized` on the bin's head node for the rare contended case.**

**A red-black tree** is a self-balancing binary search tree guaranteeing O(log n) lookup/insert/delete. CHM converts a bin from a list to a tree when the bin's length reaches `TREEIFY_THRESHOLD = 8` *and* the table capacity is at least `MIN_TREEIFY_CAPACITY = 64`. This bounds worst-case bin lookup at O(log n) instead of O(n) — a defense against **hash-collision denial-of-service** attacks (where an attacker sends keys that all hash to one bin). If a tree bin shrinks to `UNTREEIFY_THRESHOLD = 6`, it converts back to a list.

##### Step-by-step: `put(key, value)` / `putVal`

Here is the actual control flow (paraphrasing OpenJDK's `putVal`):

1. **Compute the hash.** CHM uses `spread(key.hashCode())`: `(h ^ (h >>> 16)) & 0x7fffffff`. The XOR-shift mixes high bits into low bits (because the bucket index uses low bits — `(n-1) & hash`), reducing collisions from poor hash functions. The `& 0x7fffffff` clears the sign bit so the value is non-negative (negative hashes are reserved as special "MOVED"/"TREEBIN" markers).
2. **Loop (spin) over the table:**
   - **Lazy initialization:** if `table` is null/empty, call `initTable()`, which uses a CAS on the `sizeCtl` field to ensure exactly one thread initializes it; losers spin and yield until it's done.
   - **Empty bin fast path:** compute index `i = (n-1) & hash`. If `tabAt(tab, i)` is null, attempt to place the new node with a single **CAS** (`casTabAt`). If the CAS succeeds, the put is done **with no lock at all**. If it fails (another thread won the race), loop and retry.
   - **Resize-in-progress:** if the bin's head node hash is `MOVED` (-1), this bin is being migrated to a new table; the current thread *helps* with the resize (`helpTransfer`) and then retries. (Cooperative resizing — explained below.)
   - **Contended/occupied bin:** otherwise, `synchronized (head_node_of_bin)` — lock *just this one bin's head node*. Inside the lock:
     - Walk the list (or tree) looking for an existing key. If found, optionally overwrite the value (or leave it, for `putIfAbsent`). If not found, append a new node to the list tail (or insert into the tree).
3. **Treeify check:** after a list insert, if the bin length ≥ `TREEIFY_THRESHOLD` (8), call `treeifyBin` (which treeifies only if capacity ≥ 64, else resizes instead).
4. **Update size & possibly resize:** call `addCount(1, binCount)`, which increments the *striped counter* (see §3.1.4) and checks whether the size now exceeds the resize threshold (`capacity * loadFactor`, default load factor `0.75`). If so, trigger/help a resize.

Key takeaways: **the uncontended common case (empty bin) is a single lock-free CAS; only when two writes hit the same bin does anyone take a lock, and that lock covers only that bin.** With a well-distributed hash and many bins, lock contention is rare.

##### Step-by-step: `get(key)` — fully lock-free

`get` takes **no locks at all**, ever:

1. Compute `spread(hash)`.
2. Read `table` (volatile) and the bin head via `tabAt` (a volatile-semantics array read).
3. If the head matches the key, return its value.
4. If the head hash is negative, dispatch to the special `find` (it's a `ForwardingNode` pointing to a new table during resize, or a `TreeBin`).
5. Otherwise walk the list comparing hashes then keys.

Why is this safe without locking? Because `Node.val` and `Node.next` are `volatile`, every node's value/next is read with visibility guarantees, and nodes are never mutated in place in a way that would corrupt a concurrent reader (new nodes are CAS-published; values are replaced with volatile writes). A reader might see a slightly stale view (an in-flight put may or may not be visible), which is the documented **weakly-consistent** behavior.

##### Step-by-step: resize / transfer (the clever part)

When the map grows past its threshold, it doubles the table from capacity `n` to `2n` and rehashes every bin. The clever bit: **resizing is concurrent and cooperative — many threads help migrate bins in parallel.**

1. The triggering thread sets up a new table of size `2n` and initializes `transferIndex` to `n` (bins are claimed from high index down to 0).
2. Each helping thread claims a *stride* of bins to migrate (stride ≥ `MIN_TRANSFER_STRIDE = 16`; computed from CPU count and table size) by CAS-decrementing `transferIndex`.
3. For each claimed bin, the thread locks the bin head (`synchronized`) and splits its nodes into two new bins in the new table: the **low** bin (index `i`) and the **high** bin (index `i + n`). Because capacity doubled, a node's new index is either its old index or old index + n, decided by one bit of the hash (`(hash & n) == 0`). This is the same elegant split HashMap uses.
4. After a bin is migrated, the old table slot is replaced with a `ForwardingNode` whose hash is `MOVED`. Readers hitting a `ForwardingNode` are forwarded to the new table; writers hitting it call `helpTransfer` and join the migration.
5. When all bins are migrated, the new table becomes the live `table` and `sizeCtl` is updated to the next threshold.

This means a resize never stops the world: reads and writes continue throughout, and the cost of rehashing is spread across whatever threads happen to be active.

##### `computeIfAbsent` / `compute` / `merge` atomicity

These methods are **atomic** with respect to the key — and this is a frequently-tested guarantee:

```java
map.computeIfAbsent(key, k -> expensiveCreate(k));
```

CHM holds the bin lock for the duration of the mapping function, so for a given key the function runs **at most once** even under concurrent calls, and other operations on that *same* key wait. Important caveats:

- The mapping function **must not** modify *this same* map (especially the same key) — it can deadlock or throw `IllegalStateException` ("Recursive update"). In Java 9+ CHM explicitly detects some recursive `computeIfAbsent` and throws.
- The function should be **short and side-effect-light**, because it runs under a bin lock — a slow function blocks other writers to that bin.
- `computeIfAbsent` does **not** count as a structural modification if the key is already present (in Java 9+ it won't even acquire the lock if the key exists and value is non-null — a read fast path).

Contrast with the non-atomic idiom:

```java
// WRONG: this is a compound action, NOT atomic — two threads can both
// see absent and both create the value.
V v = map.get(key);
if (v == null) {
    v = expensiveCreate(key);
    map.put(key, v);
}
```

##### `size()` is an approximation

CHM does not keep one exact size counter — that would be a contention bottleneck. Instead it uses a **striped counter** (`baseCount` plus an array of `CounterCell`s, the same idea as `LongAdder`, §3.1.4). `size()` sums them. Because increments and the sum are not done under a single global lock, `size()` returns an **estimate** that is *eventually* correct but may be momentarily off under concurrent mutation. `mappingCount()` (Java 8+) returns the same thing as a `long` (preferred when the map could exceed `Integer.MAX_VALUE`). `isEmpty()` is also best-effort. **Never** use `size()` for correctness-critical control flow under concurrency.

#### 3.1.3 CHM null policy

`ConcurrentHashMap` **forbids null keys and null values** (throws `NullPointerException`). Reason: with a concurrent map, `map.get(k) == null` is ambiguous — it could mean "key absent" or "key present with null value" — and you can't disambiguate atomically with `containsKey` in a concurrent setting. Plain `HashMap` allows one null key and null values; this is a behavioral difference that bites people porting code.

#### 3.1.4 `LongAdder` / striped counter (the size mechanism, worth knowing)

`LongAdder` (and CHM's internal counter) reduce contention on a hot counter by keeping **multiple cells**, each updated by CAS; threads hash to different cells so they rarely collide. `sum()` adds all cells. Tradeoff: faster increments under contention, but `sum()` is a snapshot, not instantaneous truth. This is exactly why CHM's `size()` is approximate.

### 3.2 `CopyOnWriteArrayList` (COWAL) and `CopyOnWriteArraySet`

**Core idea:** the backing array is **immutable once published**. Every mutating operation (`add`, `set`, `remove`) takes a lock, **copies the entire array**, applies the change to the copy, then atomically swaps the new array into a `volatile` field.

Step-by-step `add(e)`:

1. Acquire the internal lock (a `ReentrantLock` in older JDKs; `synchronized` on the COWAL instance in JDK 9+).
2. Read the current array, allocate a new array of length+1, `System.arraycopy` the old contents, place `e` at the end.
3. Volatile-write the new array into the `array` field.
4. Release the lock.

Consequences:

- **Reads (`get`, iteration) are completely lock-free and never block** — a reader just reads the current `volatile` array reference and indexes into it. Reads are O(1)/O(n) with zero synchronization cost.
- **Iterators are snapshots**: an iterator captures the array reference at creation; later writes create a *new* array, so the iterator is unaffected — it never throws CME and never reflects subsequent mutations. Iterator mutation methods (`remove`, `set`, `add`) throw `UnsupportedOperationException`.
- **Writes are O(n)** (full copy) and serialized by the lock. Memory churn: each write allocates a whole new array, generating garbage.

`CopyOnWriteArraySet` is just a COWAL with set semantics (`addIfAbsent` does a linear scan). It's O(n) per add — fine for tiny sets, terrible for large ones.

**When to use:** the list is read constantly and mutated *very* rarely, and it's small-to-moderate. The textbook case: a list of event listeners / observers iterated on every event but changed only at registration time.

### 3.3 `ConcurrentLinkedQueue` (CLQ) — lock-free Michael-Scott queue

`ConcurrentLinkedQueue` is an **unbounded, lock-free FIFO queue** based on the **Michael & Scott (1996) non-blocking queue algorithm** — one of the most famous concurrent algorithms.

**The algorithm in essence:**

- A singly-linked list of nodes with `head` and `tail` pointers (both `volatile`, updated by CAS).
- A **sentinel/dummy node**: `head` always points to a dummy; the real first element is `head.next`. This dummy trick lets enqueue and dequeue operate on disjoint ends without interfering, and avoids special-casing the empty queue.
- **Enqueue (`offer`)**: CAS the `next` of the last node from null to the new node, then (in a separate CAS) advance `tail`. Crucially, `tail` may **lag** — it isn't always the actual last node. Threads tolerate a stale tail by walking `.next` to find the real end, and *any* thread can help advance a lagging tail. This "tail may be one behind" relaxation is what makes the algorithm efficient with only single-word CAS.
- **Dequeue (`poll`)**: read `head`, get `head.next` (the first real element), CAS `head` to that node (making it the new dummy), return its value. The old head's `next` is set to itself (self-link) to help GC and signal "stale node."

**ABA avoidance:** because the JVM's GC won't recycle a node while any thread references it, and because nodes are logically single-use, the classic ABA hazard (a freed-and-reallocated node fooling a CAS) doesn't occur the way it does in C. Self-linking stale nodes also prevents traversal from following dead pointers.

**Properties:**
- `size()` is **O(n)** and **not** a constant-time field — it walks the list — and is only an estimate under concurrency. Avoid calling it in hot paths or for control flow.
- No blocking, no capacity bound. `offer` never blocks/fails (unbounded), `poll` returns `null` if empty (does not block). For blocking behavior you need a `BlockingQueue` instead.
- Weakly consistent iterator.

**When to use:** high-throughput, many-producer/many-consumer buffering where you do *not* want threads to block, and you can poll/spin or handle emptiness yourself. If you need backpressure or blocking consumers, use a `BlockingQueue`.

### 3.4 `ConcurrentSkipListMap` (CSLM) and `ConcurrentSkipListSet`

A **concurrent, sorted** map keeping keys in order (natural ordering or a supplied `Comparator`). It implements `ConcurrentNavigableMap`, so it supports range views (`subMap`, `headMap`, `tailMap`), `firstKey`/`lastKey`, `ceilingEntry`/`floorEntry`, descending views, etc.

**Why a skip list instead of a tree?** A **skip list** is a probabilistic, layered linked-list structure that gives O(log n) search/insert/delete *expected* time, like a balanced tree — but it's far easier to make **lock-free** because there is no rebalancing/rotation that would touch many nodes at once. Tree rebalancing is notoriously hard to do without global locks; skip lists avoid it entirely.

**Structure:** a bottom-level singly-linked list of all entries in sorted order, plus several sparser "express lane" levels above it. Each node is promoted to a higher level with probability ~1/2 (so level *k* has ~n/2^k nodes). To search, you start at the top-left, move right until the next key would overshoot your target, then drop down a level, and repeat — skipping over big swaths of the bottom list. Insertion CAS-links the node at the bottom level first (making it instantly visible/atomic), then lazily links it into higher index levels. Deletion marks the node then unlinks it.

**Properties:**
- Sorted iteration in O(n); lookups/insert/delete in O(log n) expected.
- Lock-free (CAS-based), weakly consistent iterators.
- `size()` is **O(n)** and an estimate (must traverse) — same caveat as CLQ.
- No null keys/values.

**When to use:** you need a thread-safe **sorted** map/set with concurrent range queries — e.g. a time-ordered index, a leaderboard, an in-memory ordered cache, expiring entries keyed by timestamp. If you don't need ordering, `ConcurrentHashMap` is faster (O(1) vs O(log n)) and lower-overhead.

### 3.5 The `BlockingQueue` family — producer/consumer backbone

A `BlockingQueue` is a queue that **blocks** producers when full and consumers when empty, providing natural **backpressure** and hand-off. It is *the* tool for producer–consumer designs and powers `ThreadPoolExecutor`.

> **Backpressure** is the mechanism by which a slow consumer slows down a fast producer, preventing unbounded memory growth. A *bounded* blocking queue gives backpressure for free: when it's full, `put()` blocks the producer until a consumer makes room.

**The four method flavors** (every `BlockingQueue` method comes in these forms):

| Operation | Throws exception | Returns special value | Blocks | Blocks with timeout |
|---|---|---|---|---|
| Insert | `add(e)` (throws `IllegalStateException` if full) | `offer(e)` (returns `false` if full) | `put(e)` (waits for space) | `offer(e, t, unit)` |
| Remove | `remove()` (throws `NoSuchElementException` if empty) | `poll()` (returns `null` if empty) | `take()` (waits for element) | `poll(t, unit)` |
| Examine | `element()` (throws if empty) | `peek()` (returns `null` if empty) | — | — |

`BlockingQueue` forbids null elements (null is the sentinel for "empty" in `poll`/`peek`).

#### 3.5.1 `ArrayBlockingQueue` (ABQ)

- **Bounded**, backed by a fixed circular array sized at construction (mandatory capacity).
- **One single `ReentrantLock`** guarding both ends, with two `Condition`s (`notEmpty`, `notFull`). So enqueue and dequeue contend on the *same* lock — producers and consumers serialize against each other.
- Optional **fairness** flag (`new ArrayBlockingQueue<>(cap, true)`): fair lock grants access in FIFO order to waiting threads (prevents starvation but lowers throughput). Default is unfair (higher throughput).
- Predictable memory (pre-allocated array). Good when you want a hard bound and bounded latency.

> **Condition variable**: an object associated with a lock on which a thread can `await()` (releasing the lock and sleeping) until another thread `signal()`s it. `notFull`/`notEmpty` let producers wait for space and consumers wait for items.

#### 3.5.2 `LinkedBlockingQueue` (LBQ)

- **Optionally bounded** (capacity defaults to `Integer.MAX_VALUE` — effectively unbounded if you don't pass a capacity, a classic memory-leak footgun).
- Backed by a **linked list** with **two separate locks** (`putLock`, `takeLock`) — so a producer and a consumer can operate *simultaneously* (the "two-lock queue" by Michael & Scott). Higher throughput than ABQ under mixed producer/consumer load.
- Uses an `AtomicInteger count` shared between the two ends.
- Allocates a node per element (more GC than ABQ's array) and slightly higher per-op overhead.

**ABQ vs LBQ rule of thumb:** ABQ for tighter memory predictability and when capacity is naturally small/fixed; LBQ for higher throughput with separate producer/consumer locks. **Always set a capacity on LBQ** in production.

#### 3.5.3 `SynchronousQueue` (SQ)

- **Zero capacity** — it holds *no* elements. Each `put` must wait for a corresponding `take` and vice versa; it's a **direct hand-off** rendezvous.
- Useful when you want to hand work directly from producer to consumer with no buffering (e.g. `Executors.newCachedThreadPool()` uses a `SynchronousQueue`: each submitted task is handed to an existing idle thread, or a new thread is spawned).
- Optional fairness flag. `peek()` always returns null; `size()` always 0; it has no internal iterable contents.

#### 3.5.4 `PriorityBlockingQueue` (PBQ)

- **Unbounded** priority queue (a binary heap) that blocks on `take` when empty (but `put` never blocks — it's unbounded, so watch memory).
- Orders elements by natural ordering or a `Comparator`. `take` always returns the current minimum.
- **Not** a stable/FIFO order for equal-priority elements (heap order is unspecified among equals).
- Its **iterator is not ordered** (it reflects heap array order, not priority order) — to drain in priority order you must `poll` repeatedly.

#### 3.5.5 `DelayQueue`

- **Unbounded** queue of elements implementing `Delayed`; an element can be `take`n only after its delay has expired. `take` blocks until the head element's delay elapses.
- Internally a `PriorityBlockingQueue` ordered by remaining delay, plus a "leader" optimization so only one waiting thread sleeps until the next expiry (avoids thundering-herd wakeups).
- Used for scheduling: `ScheduledThreadPoolExecutor` uses a similar delayed work queue. Great for "do X after T", caches with TTL expiry, retry-with-backoff scheduling.

> **`Delayed`** interface: requires `getDelay(TimeUnit)` (remaining time until ready; negative/zero means ready) and `compareTo` (ordering by delay). You compute `getDelay` from an absolute expiry timestamp minus `now`.

#### 3.5.6 `LinkedBlockingDeque` and `LinkedTransferQueue` (bonus)

- **`LinkedBlockingDeque`**: a bounded double-ended blocking queue — insert/remove at both ends. Used for **work-stealing**-style patterns.
- **`LinkedTransferQueue` (LTQ)**: an unbounded, lock-free `TransferQueue`. Beyond normal queue ops it has `transfer(e)`, which blocks until a consumer receives the element (like `SynchronousQueue` hand-off) but also allows buffering. Often the highest-throughput unbounded blocking queue; backs some modern executors.

### 3.6 Producer–consumer pattern (the canonical use of blocking queues)

```java
BlockingQueue<Task> queue = new ArrayBlockingQueue<>(1000); // bounded -> backpressure

// Producer
queue.put(task);          // blocks if full -> backpressure on the producer

// Consumer
Task t = queue.take();    // blocks if empty -> consumer sleeps, no busy-wait
```

The queue decouples producer and consumer rates, provides backpressure, and establishes happens-before (everything the producer did before `put` is visible to the consumer after `take`). The **poison pill** idiom is the standard shutdown signal (see §5).

---

## 4. The complete toolkit

### 4.1 `ConcurrentHashMap` — key methods

| Method | Purpose | Notes / defaults |
|---|---|---|
| `put(k,v)` / `get(k)` / `remove(k)` | Basic ops | No null keys/values; `get` is lock-free |
| `putIfAbsent(k,v)` | Insert only if absent | Atomic; returns previous value or null |
| `computeIfAbsent(k, fn)` | Compute & insert if absent | **Atomic per key**; fn runs at most once; don't mutate same map inside |
| `computeIfPresent(k, fn)` | Recompute if present | Atomic; return null from fn to remove |
| `compute(k, fn)` | Compute regardless | Atomic |
| `merge(k, v, fn)` | Combine new with existing | Atomic; great for counting: `merge(k, 1, Integer::sum)` |
| `getOrDefault(k, def)` | Read with default | Lock-free |
| `remove(k, v)` / `replace(k, old, new)` | Conditional CAS-style ops | Atomic compare-and-act |
| `size()` / `mappingCount()` | Approximate size | Estimate; prefer `mappingCount()` (long) |
| `keySet()` / `newKeySet()` | Key views; concurrent set | `newKeySet()` returns a `KeySetView` usable as a set |
| `forEach`, `search`, `reduce` (+ typed variants) | Bulk parallel ops | Take a `parallelismThreshold`; weakly consistent |
| `elements()` / `values()` / `entrySet()` | Views | Weakly consistent iterators |

Constructor params: `ConcurrentHashMap(initialCapacity, loadFactor=0.75f, concurrencyLevel=1)`. In Java 8+ `concurrencyLevel` is only a sizing hint (no segments). Default load factor `0.75`, default initial capacity 16.

Bulk-operation thresholds: pass `parallelismThreshold = 1` to always go parallel, `Long.MAX_VALUE` to stay sequential. Use parallel bulk ops only for large maps; they use the common ForkJoinPool.

### 4.2 Blocking queue toolkit

| Class | Bounded? | Locking | Ordering | Notable |
|---|---|---|---|---|
| `ArrayBlockingQueue` | Yes (fixed) | 1 lock, 2 conditions | FIFO | Optional fairness; pre-allocated array |
| `LinkedBlockingQueue` | Optional (default `MAX_VALUE`) | 2 locks (put/take) | FIFO | Higher mixed throughput; **set capacity!** |
| `LinkedBlockingDeque` | Yes | 1 lock | FIFO/LIFO both ends | Work-stealing patterns |
| `SynchronousQueue` | 0 (no storage) | — | Hand-off | `newCachedThreadPool` backbone |
| `PriorityBlockingQueue` | No (unbounded) | 1 lock | Priority | `put` never blocks; iterator unordered |
| `DelayQueue` | No | 1 lock | By delay | Elements must be `Delayed`; leader/follower |
| `LinkedTransferQueue` | No | Lock-free | FIFO | `transfer()` hand-off + buffering |

### 4.3 Other collections

| Class | Thread-safety technique | size() cost | Iterator | Sorted? |
|---|---|---|---|---|
| `ConcurrentHashMap` | CAS + per-bin synchronized | O(1) estimate | weakly consistent | No |
| `ConcurrentSkipListMap` | Lock-free skip list | **O(n)** estimate | weakly consistent | Yes |
| `ConcurrentLinkedQueue` | Lock-free Michael-Scott | **O(n)** estimate | weakly consistent | FIFO |
| `ConcurrentLinkedDeque` | Lock-free | O(n) | weakly consistent | Both ends |
| `CopyOnWriteArrayList` | Copy whole array on write | O(1) | snapshot | insertion order |
| `CopyOnWriteArraySet` | COWAL-backed | O(1) | snapshot | insertion order |
| `ConcurrentSkipListSet` | CSLM-backed | O(n) | weakly consistent | Yes |

### 4.4 Adjacent atomics & helpers (you'll use these alongside)

| Class | Purpose |
|---|---|
| `AtomicInteger` / `AtomicLong` / `AtomicReference` | Single CAS-updated values |
| `LongAdder` / `LongAccumulator` | High-throughput contended counters (striped) |
| `AtomicStampedReference` | CAS with a version stamp (defeats ABA) |
| `VarHandle` (Java 9+) | Modern low-level atomic field access (replaces `Unsafe`/`AtomicXxxFieldUpdater`) |
| `Collections.synchronizedMap/List` | Coarse-grained wrappers (the *old* way; avoid for hot paths) |

---

## 5. Code examples by use case

### 5.1 Thread-safe counting map (CHM `merge`)

```java
import java.util.concurrent.ConcurrentHashMap;

ConcurrentHashMap<String, Integer> wordCounts = new ConcurrentHashMap<>();

// Atomic increment — no get/put race, no extra locking.
// merge: if absent, store 1; else apply Integer::sum to (existing, 1).
String word = "java";
wordCounts.merge(word, 1, Integer::sum);

// For a long counter, prefer LongAdder values to avoid boxing churn:
ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> counts2 = new ConcurrentHashMap<>();
counts2.computeIfAbsent(word, k -> new java.util.concurrent.atomic.LongAdder()).increment();
```

Why `merge`/`computeIfAbsent+LongAdder` beats `get`+`put`: the read-modify-write happens atomically inside the bin, so concurrent increments can't lose updates. The `LongAdder` variant additionally avoids re-boxing an `Integer` on every increment and reduces contention on a single hot key.

### 5.2 Build-once cache (atomic `computeIfAbsent`)

```java
ConcurrentHashMap<URL, byte[]> cache = new ConcurrentHashMap<>();

byte[] data = cache.computeIfAbsent(url, u -> {
    // This lambda runs AT MOST ONCE per key even under concurrent calls.
    // Keep it side-effect free and do NOT touch `cache` for the same key.
    return downloadAndParse(u);
});
```

Pitfall: if `downloadAndParse` is slow, it runs under the bin lock and blocks other writers *to that bin*. For very expensive computations, a common pattern is to store a `Future`/`CompletableFuture` as the value so the lock is held only long enough to install the future:

```java
ConcurrentHashMap<URL, java.util.concurrent.CompletableFuture<byte[]>> futCache = new ConcurrentHashMap<>();

java.util.concurrent.CompletableFuture<byte[]> f =
    futCache.computeIfAbsent(url, u ->
        java.util.concurrent.CompletableFuture.supplyAsync(() -> downloadAndParse(u)));
byte[] result = f.join(); // wait outside the bin lock
```

### 5.3 Bounded producer–consumer with poison pill (BlockingQueue)

```java
import java.util.concurrent.*;

class Pipeline {
    private static final Task POISON = new Task("__POISON__");
    private final BlockingQueue<Task> queue = new ArrayBlockingQueue<>(1000); // bounded -> backpressure

    void produce(List<Task> tasks) throws InterruptedException {
        for (Task t : tasks) queue.put(t);   // blocks when full
    }

    void shutdown(int numConsumers) throws InterruptedException {
        for (int i = 0; i < numConsumers; i++) queue.put(POISON); // one pill per consumer
    }

    Runnable consumer() {
        return () -> {
            try {
                while (true) {
                    Task t = queue.take();      // blocks when empty
                    if (t == POISON) return;    // exit on poison pill
                    handle(t);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore interrupt flag, then exit
            }
        };
    }
}
```

Notes: the **poison pill** is a sentinel that tells consumers to stop; you enqueue exactly one per consumer thread. Always **restore the interrupt flag** (`Thread.currentThread().interrupt()`) when catching `InterruptedException` if you don't fully handle it, so callers/pools can observe the interrupt.

### 5.4 Read-mostly listener registry (CopyOnWriteArrayList)

```java
import java.util.concurrent.CopyOnWriteArrayList;

class EventBus {
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    void register(Listener l)   { listeners.addIfAbsent(l); } // rare write -> full array copy
    void unregister(Listener l) { listeners.remove(l); }      // rare write

    void publish(Event e) {
        // Lock-free, snapshot iteration: safe even if listeners register
        // mid-publish (the new one simply won't be notified for THIS event).
        for (Listener l : listeners) l.onEvent(e);
    }
}
```

This is the canonical COWAL use: thousands of `publish` (read) calls, a handful of `register` (write) calls. If writes were frequent or the list large, COWAL's per-write full-array copy would kill you — use a `ConcurrentHashMap.newKeySet()` instead.

### 5.5 Scheduled expiry with DelayQueue

```java
import java.util.concurrent.*;

class CacheEntry implements Delayed {
    final String key;
    private final long expiryNanos;
    CacheEntry(String key, long ttlMillis) {
        this.key = key;
        this.expiryNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis);
    }
    public long getDelay(TimeUnit unit) {
        return unit.convert(expiryNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
    }
    public int compareTo(Delayed o) {
        return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
    }
}

DelayQueue<CacheEntry> expiry = new DelayQueue<>();
expiry.put(new CacheEntry("session-42", 30_000));   // expires in 30s

// Reaper thread:
new Thread(() -> {
    try {
        while (true) {
            CacheEntry e = expiry.take();   // blocks until the soonest entry's delay elapses
            evict(e.key);
        }
    } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
}).start();
```

`take()` returns an entry only once its delay reaches zero, and blocks efficiently until then (only the "leader" thread sleeps). Perfect for TTL eviction, retry scheduling, and rate-limit windows.

### 5.6 Sorted concurrent index / leaderboard (ConcurrentSkipListMap)

```java
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.*;

// score -> player (descending leaderboard). Use a Comparator for desc order.
ConcurrentSkipListMap<Long, String> board =
        new ConcurrentSkipListMap<>(Comparator.reverseOrder());

board.put(1500L, "alice");
board.put(2300L, "bob");

// Top-3 (highest scores first because of reverseOrder comparator):
int n = 0;
for (Map.Entry<Long,String> e : board.entrySet()) {
    System.out.println(e.getValue() + " = " + e.getKey());
    if (++n == 3) break;
}

// Range query: everyone scoring between 1000 and 2000 (note desc order on bounds):
ConcurrentNavigableMap<Long,String> midTier = board.subMap(2000L, true, 1000L, true);
```

CSLM gives you O(log n) writes/reads, ordered iteration, and concurrency — what a plain `TreeMap` (not thread-safe) can't, and what `ConcurrentHashMap` (unordered) won't.

### 5.7 High-throughput non-blocking buffer (ConcurrentLinkedQueue)

```java
import java.util.concurrent.ConcurrentLinkedQueue;

ConcurrentLinkedQueue<LogRecord> buffer = new ConcurrentLinkedQueue<>();

// Many producers (request threads) — never blocks:
buffer.offer(record);

// A flusher polls in batches; poll() returns null when empty (no blocking):
LogRecord r;
List<LogRecord> batch = new ArrayList<>(256);
while ((r = buffer.poll()) != null && batch.size() < 256) batch.add(r);
if (!batch.isEmpty()) flush(batch);
```

Caution: do not call `buffer.size()` to decide when to flush — it's O(n). Track an `AtomicInteger` alongside, or batch by `poll` count as above.

### 5.8 The classic compound-action bug — and the fix

```java
ConcurrentHashMap<String, List<Order>> ordersByUser = new ConcurrentHashMap<>();

// WRONG: two operations on the map are individually atomic, but the SEQUENCE isn't.
// Two threads can both see no list, both create one, and one is lost (along with its order).
if (!ordersByUser.containsKey(user)) {            // op 1
    ordersByUser.put(user, new ArrayList<>());    // op 2 — race window between op1 and op2
}
ordersByUser.get(user).add(order);                // op 3 — and ArrayList isn't thread-safe!

// RIGHT: atomic creation + a thread-safe inner collection.
ordersByUser
    .computeIfAbsent(user, k -> new CopyOnWriteArrayList<>())  // atomic, once per key
    .add(order);  // inner list must itself be thread-safe (or synchronize)
```

Two bugs fixed: (1) `computeIfAbsent` makes the create atomic; (2) the inner list must *also* be concurrent-safe — wrapping a value in a CHM doesn't magically make the value thread-safe.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Match the collection to the access pattern.** Read-mostly small list → COWAL. Hot shared map → CHM. Sorted → CSLM. Producer/consumer → BlockingQueue. Using the wrong one (e.g. COWAL for a write-heavy list) is catastrophic (O(n) per write, GC storms).
- **CHM scales with bin count**, not a fixed concurrency level (post-Java-8). Size it with a good `initialCapacity` to avoid resizes; a poor `hashCode()` that collides defeats per-bin concurrency (all writes serialize on one bin and may treeify).
- **Avoid `size()` on CLQ/CSLM** in hot paths (O(n)). On CHM/COWAL it's cheap but on CHM it's only an estimate.
- **Prefer `LongAdder` over `AtomicLong`** for hot counters; prefer `merge`/`computeIfAbsent` over read-then-write idioms on CHM.
- **Bounded vs unbounded queues:** unbounded queues (`LinkedBlockingQueue` default, `PriorityBlockingQueue`, `DelayQueue`, `ConcurrentLinkedQueue`) have no backpressure — a fast producer + slow consumer = `OutOfMemoryError`. Bound them.

### 6.2 Correctness & concurrency

- **Compound actions are not atomic** even on a concurrent collection — *check-then-act*, *read-modify-write* across multiple calls need the collection's atomic combinators (`computeIfAbsent`, `merge`, `replace(k,old,new)`, `remove(k,v)`) or an external lock.
- **Iteration is weakly consistent or snapshot** — don't assume an iterator reflects concurrent changes (or *fails* on them).
- **Values inside a concurrent collection are not auto-protected** — a `ConcurrentHashMap<K, ArrayList<V>>` has thread-safe map ops but unsafe list mutations.
- **`computeIfAbsent`/`compute` functions must not mutate the same map** (deadlock / "Recursive update").
- **CHM rejects nulls**; porting from `HashMap` can surface latent null usage as NPEs.

### 6.3 Memory

- COWAL: each write allocates a full new array — write-heavy use produces large garbage and old arrays linger until iterators release them.
- CHM: per-node overhead, tree nodes for treeified bins, striped counter cells. Resizing temporarily holds two tables.
- LBQ/CLQ: one node object per element (allocation + GC pressure) vs ABQ's single pre-allocated array (lower GC, fixed footprint).

### 6.4 Security

- **Hash-collision DoS**: untrusted keys engineered to collide can degrade a `HashMap` to O(n). CHM mitigates with treeification (O(log n)) — but treeification requires keys to be `Comparable` for best results, else it falls back to identity/hash tiebreaks. Still, validate/limit untrusted key cardinality.
- Don't expose internal mutable collections directly to untrusted callers; return unmodifiable views.

### 6.5 Observability

- Expose queue depth/lag metrics (e.g. `queue.size()` for ABQ/LBQ is cheap; for CLQ track separately). Queue depth trending toward capacity is your backpressure/overload signal.
- For thread pools, monitor the work queue size, active count, and rejected-task count.
- Use thread dumps (`jstack`) to spot threads parked on `take()`/`put()` (look for `WAITING (parking)` on `AbstractQueuedSynchronizer$ConditionObject`).

### 6.6 Testing

- Concurrency bugs are non-deterministic. Use **stress tests** with many threads and many iterations, randomized scheduling, and assertions on invariants (e.g. final count equals expected).
- Tools: **jcstress** (the JDK concurrency stress harness for verifying memory-model behavior), thread-sanitizer-style approaches, and running on multiple CPU architectures (x86's stronger memory model can hide bugs that surface on ARM).
- Test the *empty*, *full*, *interrupt-during-block*, and *shutdown* paths of blocking queues explicitly.

### 6.7 Production hardening

- Always **bound** queues; define a **rejection/overflow policy** (drop, block, spill to disk, shed load).
- Handle `InterruptedException` correctly (restore the flag or propagate; never swallow silently).
- Use the **poison pill** or `ExecutorService.shutdown()/awaitTermination()` for clean drain-and-stop.
- Size CHM up front; choose good `hashCode`/`equals`.

### 6.8 Anti-patterns

- Using `Collections.synchronizedMap`/`Vector`/`Hashtable` on a hot path (single coarse lock).
- Iterating a `Collections.synchronizedMap` *without* holding its lock (CME / corruption) — synchronized wrappers require manual external synchronization for iteration.
- `containsKey`-then-`put` / `get`-then-`put` instead of atomic combinators.
- Unbounded `LinkedBlockingQueue` (default capacity) as a thread-pool work queue → OOM under load.
- Frequent writes to a `CopyOnWriteArrayList`.
- Relying on `size()`/`isEmpty()` of a concurrent collection for correctness.
- Putting a non-thread-safe value (plain `ArrayList`) into a CHM and mutating it concurrently.

---

## 7. Advanced topics & deep internals

### 7.1 CHM treeification details

- A bin treeifies at length ≥ 8 *and* table capacity ≥ 64 (`MIN_TREEIFY_CAPACITY`). Below 64 capacity, CHM **resizes instead of treeifying**, because at low capacity, long chains usually mean the table is just too small.
- Tree bins use a special `TreeBin` head that holds a read-write-ish lock allowing concurrent reads but exclusive structural writes; readers can traverse a list-view of the tree while a writer rebalances.
- Treeification requires comparing keys; if keys aren't `Comparable`, CHM uses a deterministic tiebreak (class name then `System.identityHashCode`) to keep tree order total.
- Un-treeify back to a list when a bin's size drops to ≤ 6 (`UNTREEIFY_THRESHOLD`). The 8/6 gap (hysteresis) prevents thrashing around the boundary.

### 7.2 The `sizeCtl` field — a multiplexed control word

CHM's `sizeCtl` (volatile int) encodes several states in one field via sign and value:
- **Positive (before init)**: the requested initial capacity.
- **Zero**: default.
- **-1**: table is being initialized (a thread CAS'd it to claim init).
- **Negative < -1 (during resize)**: high bits encode a resize stamp, low bits encode the number of active resizer threads (so newcomers know to help and the last finisher knows to publish). This compact encoding lets CHM coordinate init and concurrent resize using a single CAS-managed word.

### 7.3 Why `get` needs no lock — the publication argument

`Node.val` and `Node.next` are `volatile`. A new node is linked via `casTabAt` (an atomic, ordered store) or appended under the bin lock with a volatile write of `.next`. Either way, by the time a reader can *reach* a node, the writes that constructed it have happened-before the read (CAS and volatile establish the edges). Replacing a value is a volatile write to `Node.val`. Hence a lock-free `get` always sees a *consistent* node, just possibly a slightly stale snapshot of the bin — exactly the weakly-consistent contract.

### 7.4 Michael-Scott queue subtleties

- The **lagging tail** optimization: enqueue is two CASes (link node, then swing tail). If a thread is preempted between them, `tail` points one node behind. Any subsequent enqueuer/observer detects this (`tail.next != null`) and helps swing `tail` forward — this *helping* keeps the structure lock-free (no thread's pause blocks others).
- **Self-linking** dequeued nodes (`node.next = node`) lets the GC collect them and acts as a tombstone so traversers know to re-read `head`.
- `remove(Object)` and iterators must tolerate concurrently unlinked nodes (skip self-linked ones).

### 7.5 Skip list level distribution & lock-freedom

- Node level is chosen randomly (~geometric, p≈0.5), giving expected O(log n) height. Insertion atomically CAS-links the bottom node first — the moment that succeeds, the key is *logically present and lookups can find it* — then opportunistically links upper levels (purely for speed; a missing upper link only slows search, never breaks correctness).
- Deletion uses **logical deletion** (mark) then **physical unlink**, with CAS, so a concurrent search either sees the node or doesn't, never a corrupt half-removed state.

### 7.6 Memory model guarantees you can rely on

Each JUC collection documents happens-before edges. The two you'll cite most:
- **Maps/queues**: actions before `put`/`offer`/`add` of an element happen-before its retrieval/removal by another thread.
- **COWAL snapshot**: the array publication is via volatile, so iterating threads see a fully-constructed array.

This lets you use these collections as **safe publication** mechanisms — putting a fully-initialized object into a CHM/queue safely publishes it to consumers without extra synchronization.

### 7.7 Parallel bulk operations on CHM

`forEach`, `search`, `reduce` (and primitive-specialized variants like `reduceValuesToInt`) accept a `parallelismThreshold`. If the estimated size exceeds it, work is split across the common **ForkJoinPool** (a work-stealing pool). Use for large maps and CPU-bound element work; for small maps the parallelism overhead dominates. Beware: bulk ops are weakly consistent (concurrent mutations may or may not be reflected) and you shouldn't have side effects that depend on traversal order.

### 7.8 `LinkedTransferQueue` and dual data structures

LTQ implements a **dual queue** (a queue that can hold *either* data or *reservations* for data). When a consumer arrives at an empty queue, it enqueues a "request" node and waits; a producer matching it hands off directly. This unifies `SynchronousQueue`-style hand-off with normal buffering, lock-free, and is often the fastest unbounded blocking queue.

### 7.9 Fairness vs throughput in blocking queues

`ArrayBlockingQueue`/`SynchronousQueue` accept a fairness flag wiring a *fair* `ReentrantLock` (FIFO grant order). Fair locks eliminate starvation but cut throughput substantially (each handoff is a context switch in strict order). Default unfair is right for almost all throughput-oriented systems; choose fair only when starvation/latency-bound ordering matters.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Map decision table

| Need | Use | Avoid |
|---|---|---|
| Unordered concurrent map, high throughput | `ConcurrentHashMap` | `Hashtable`, `synchronizedMap` |
| Sorted concurrent map, range queries | `ConcurrentSkipListMap` | `synchronizedSortedMap(TreeMap)` |
| Single-threaded / confined | `HashMap` / `TreeMap` | concurrent variants (overhead) |
| Atomic per-key compute | `ConcurrentHashMap.computeIfAbsent`/`merge` | get-then-put |

**Use CHM when…** you need a fast, scalable, unordered map shared by many threads.
**Avoid CHM when…** you need ordering (use CSLM), strong cross-key transactional consistency (use explicit locking/another design), or it's single-threaded.

### 8.2 List/Set decision table

| Need | Use | Avoid |
|---|---|---|
| Read-mostly, tiny/medium, rare writes | `CopyOnWriteArrayList` / `…ArraySet` | for write-heavy or large |
| Frequent concurrent writes to a "list" | rethink — often a queue/map fits better | COWAL |
| Concurrent set, unordered | `ConcurrentHashMap.newKeySet()` | `synchronizedSet` |
| Concurrent set, sorted | `ConcurrentSkipListSet` | `synchronizedSortedSet` |

### 8.3 Queue decision table

| Need | Use |
|---|---|
| Bounded buffer, predictable memory | `ArrayBlockingQueue` |
| Bounded buffer, high mixed throughput | `LinkedBlockingQueue` (with capacity) |
| Direct hand-off, no buffering | `SynchronousQueue` |
| Priority ordering | `PriorityBlockingQueue` (bound memory!) |
| Time-delayed delivery / scheduling | `DelayQueue` |
| Non-blocking, unbounded, max throughput | `ConcurrentLinkedQueue` / `LinkedTransferQueue` |
| Both-ends / work stealing | `LinkedBlockingDeque` / `ConcurrentLinkedDeque` |

**ThreadPoolExecutor pairing:** `SynchronousQueue` → cached pool (unbounded threads); bounded `ArrayBlockingQueue` → fixed pool with backpressure + a `RejectedExecutionHandler`; unbounded `LinkedBlockingQueue` → fixed pool that *queues unboundedly* (the `Executors.newFixedThreadPool` OOM footgun).

### 8.4 Synchronized wrappers vs concurrent collections

| | `Collections.synchronizedMap` / `Hashtable` | `ConcurrentHashMap` |
|---|---|---|
| Locking | One global lock | Per-bin CAS/lock |
| Read concurrency | Serialized | Lock-free, fully concurrent |
| Iteration | Manual external sync, fail-fast | Weakly consistent, no external sync |
| Compound atomic ops | Need external sync | Built-in atomic combinators |
| Throughput under contention | Poor | Excellent |

---

## 9. Failure modes & debugging

### 9.1 Unbounded queue → `OutOfMemoryError`

**Symptom:** heap fills; GC thrashes; OOM. **Cause:** a fast producer with an unbounded queue (default `LinkedBlockingQueue`, `PriorityBlockingQueue`, `DelayQueue`, `ConcurrentLinkedQueue`) and a slow/stuck consumer. **Diagnose:** heap dump (`jmap -dump`/`jcmd <pid> GC.heap_dump`) → analyze in Eclipse MAT; the dominator tree shows millions of queue nodes. **Fix:** bound the queue and apply a rejection policy.

### 9.2 Deadlock / stuck consumers

**Symptom:** throughput drops to zero; threads parked. **Diagnose:** `jstack <pid>` (or `jcmd <pid> Thread.print`). Threads parked in `take()` show `WAITING (parking)` on `…ConditionObject`. If consumers died (uncaught exception) and stopped polling, producers block on `put()` forever. **Fix:** robust consumer loops (catch, log, continue), bounded waits (`poll(timeout)`), health checks on consumer threads.

### 9.3 `ConcurrentModificationException` where you didn't expect it

**Cause:** iterating a non-concurrent collection (or a `synchronizedMap` without holding its lock) while another thread (or the same thread) modifies it. **Fix:** use a concurrent collection (weakly consistent iterator) or hold the proper lock during iteration; for synchronized wrappers, `synchronized(map){ for(... ) ... }`.

### 9.4 Lost updates / wrong counts

**Cause:** compound `get`+`put` race, or mutating a non-thread-safe value object stored in a CHM. **Diagnose:** counts off by a nondeterministic amount; reproduce with a high-thread stress test. **Fix:** atomic combinators (`merge`, `computeIfAbsent`, `replace(k,old,new)`); thread-safe value types.

### 9.5 Performance cliff from poor hashing or COWAL misuse

**Symptom:** CHM ops slow / all contention on one bin (profiler shows one `synchronized` hot). **Cause:** bad `hashCode`, or keys all colliding. **Fix:** better hash, ensure `Comparable` keys (so treeified bins stay O(log n)). For COWAL: a CPU/alloc profile showing constant `Arrays.copyOf` means write-heavy misuse → switch structure.

### 9.6 `computeIfAbsent` self-deadlock / "Recursive update"

**Cause:** the mapping function calls back into the same map (same key). **Fix:** restructure so the function is pure; compute dependencies before calling `computeIfAbsent`.

### 9.7 Real-world incident patterns

- **The cached-pool surprise:** `Executors.newCachedThreadPool()` (uses `SynchronousQueue`) spawns unbounded threads under a burst → thread explosion / OOM of native threads. Use a bounded pool + bounded queue.
- **The fixed-pool OOM:** `Executors.newFixedThreadPool(n)` uses an *unbounded* `LinkedBlockingQueue`; tasks pile up invisibly until OOM, with no backpressure. Construct `ThreadPoolExecutor` explicitly with a bounded queue and a rejection handler.
- **Java 7 `HashMap` infinite loop:** concurrent resize of a non-thread-safe `HashMap` corrupted the bucket list into a cycle, pinning a CPU at 100% forever. The lesson that drove CHM adoption: never share a plain `HashMap` across threads.

Tools cheat list: `jstack`/`jcmd Thread.print` (thread states), `jcmd GC.heap_dump` + MAT (memory), async-profiler / JFR (Java Flight Recorder) for lock contention and allocation, **jcstress** for memory-model correctness.

---

## 10. Interview drill

**Q1. How does `ConcurrentHashMap` achieve thread safety in Java 8+, and how does it differ from Java 7?**
*Model answer:* Java 7 used an array of segments, each a small hash table with its own `ReentrantLock` (lock striping, default 16 → max 16 concurrent writers). Java 8 removed segments: it locks at the **per-bin** level using CAS for the common (empty-bin) case and `synchronized` on the bin head node only when a bin is contended. Reads are fully lock-free via volatile nodes. Concurrency now scales with the number of bins, not a fixed level.
- *Probe: What happens on a hash collision long chain?* The bin treeifies into a red-black tree at length ≥ 8 (if capacity ≥ 64), giving O(log n) worst case and DoS resistance; un-treeifies at ≤ 6.
- *Probe: How does resize work concurrently?* Cooperative: threads claim strides of bins, migrate them under the bin lock into a double-size table, leave `ForwardingNode`s; other threads help. No stop-the-world.
- *Probe: Why is `size()` approximate?* It sums a striped counter (`LongAdder`-style cells) rather than one contended counter, so it's an estimate under concurrency.

**Q2. Is `computeIfAbsent` atomic? What rules must the mapping function follow?**
*Model answer:* Yes — atomic per key; the function runs at most once even under concurrency, with the bin locked. The function must be short, side-effect-light, and must not modify the same map (especially the same key), or it risks deadlock / "Recursive update."
- *Probe: Why prefer it over get-then-put?* get-then-put is a compound action — two threads can both see absent and both create, losing one. `computeIfAbsent` closes that window.
- *Probe: What if the computation is very expensive?* Store a `Future`/`CompletableFuture` as the value so the lock is held only to install the future; callers `join` outside the lock.

**Q3. When would you use `CopyOnWriteArrayList`, and when is it a terrible choice?**
*Model answer:* Use it for read-mostly, write-rarely, small/medium lists (e.g. listener registries) — reads are lock-free and iterators are CME-free snapshots. It's terrible for write-heavy or large lists: every write copies the whole array (O(n) + garbage).
- *Probe: What do its iterators reflect?* A snapshot at iterator creation; later writes aren't seen; iterator mutation throws `UnsupportedOperationException`.
- *Probe: Alternative for a concurrent set with frequent writes?* `ConcurrentHashMap.newKeySet()`.

**Q4. Compare `ArrayBlockingQueue`, `LinkedBlockingQueue`, and `SynchronousQueue`.**
*Model answer:* ABQ: bounded, array-backed, single lock + two conditions, optional fairness, predictable memory. LBQ: optionally bounded (default `MAX_VALUE` — set capacity!), linked, two locks (put/take) so producer and consumer run concurrently → higher mixed throughput, more GC. SQ: zero capacity, direct hand-off rendezvous; each put waits for a take.
- *Probe: Which does `newCachedThreadPool` use and why?* `SynchronousQueue` — tasks hand directly to an idle thread or spawn a new one; no buffering.
- *Probe: Why is unbounded LBQ dangerous in a fixed thread pool?* No backpressure; tasks queue until OOM.

**Q5. Explain the Michael-Scott algorithm behind `ConcurrentLinkedQueue`.**
*Model answer:* A lock-free FIFO singly-linked list with a dummy head node and CAS-updated head/tail. Enqueue CASes the last node's next then swings tail (which may lag; threads help advance it). Dequeue CASes head forward past the dummy. GC + self-linking avoid the C-style ABA/reclamation hazards.
- *Probe: Why a dummy node?* Decouples enqueue and dequeue ends and avoids special-casing empty.
- *Probe: Why is size() O(n)?* No maintained count field; it walks the list and is only an estimate.

**Q6. Why does `ConcurrentHashMap` forbid null keys/values when `HashMap` allows them?**
*Model answer:* In a concurrent map, `get(k)==null` would be ambiguous (absent vs present-with-null), and you can't disambiguate atomically with `containsKey`. Forbidding null removes the ambiguity.
- *Probe: Migration risk?* Latent null usage from `HashMap` becomes NPEs.

**Q7. (Senior signal) You have a write-heavy shared cache where many threads update overlapping keys with read-modify-write. How do you design it?**
*Model answer:* Use `ConcurrentHashMap` with atomic combinators (`merge`/`compute`) instead of get-then-put; store thread-safe value types or use `compute` to mutate atomically under the bin lock; for hot counters store `LongAdder` values; size the map and ensure good `hashCode`/`Comparable` keys so contended bins treeify gracefully. If a *single* key is extremely hot, consider sharding that key's value across cells (LongAdder pattern). Avoid CSLM unless ordering is needed (it's slower). Add eviction (size/TTL) — perhaps a `DelayQueue` reaper.
- *Probe: How avoid a single hot bin?* Better hash distribution; sharded counters; or partition the cache.
- *Probe: How to make value updates atomic without locking the whole map?* `compute(k, (key, old) -> ...)` holds only the bin lock.

**Q8. (Senior signal) Justify bounded vs unbounded queues in a service ingestion pipeline.**
*Model answer:* Bounded queues give backpressure: when full, producers block (`put`) or you reject/shed load, keeping memory and latency bounded — essential for stability under overload. Unbounded queues hide overload until OOM and inflate latency unboundedly (queue grows). Choose bounded with an explicit overflow policy (block, drop-oldest, reject + 429, spill). The bound is a capacity-planning decision: sized to absorb bursts within latency SLOs.
- *Probe: What overflow policies exist?* Block producer, drop newest/oldest, caller-runs, reject with error, spill to disk/secondary store.
- *Probe: How does this interact with thread pools?* The work queue *is* the buffer; bound it and set a `RejectedExecutionHandler` (`AbortPolicy`, `CallerRunsPolicy`, `DiscardPolicy`, `DiscardOldestPolicy`).

**Q9. (Senior signal) When is `ConcurrentSkipListMap` worth its cost over `ConcurrentHashMap`?**
*Model answer:* Only when you need **ordering / navigation**: range queries (`subMap`), nearest-key (`ceiling`/`floor`), ordered iteration, or min/max. CSLM is O(log n) and higher constant overhead; CHM is O(1) and faster. So: sorted concurrent index, time-ordered structures, leaderboards → CSLM; everything unordered → CHM.
- *Probe: Why a skip list rather than a concurrent balanced tree?* Skip lists avoid global-rebalancing rotations, making lock-free CAS implementation tractable.
- *Probe: size() cost?* O(n) estimate (must traverse).

**Q10. What iterator consistency guarantees do these collections provide?**
*Model answer:* Three families: fail-fast (plain `HashMap`/`ArrayList`, throws CME, best-effort), weakly consistent (most JUC: never CME, may or may not reflect concurrent changes, each element once), and snapshot (COWAL: fixed array at creation, no later changes, no iterator mutation).
- *Probe: Is fail-fast a guarantee?* No — best-effort bug detector; don't rely on it for correctness.

**Q11. How does a `BlockingQueue` establish memory visibility between producer and consumer?**
*Model answer:* It documents a happens-before edge: actions before `put`/`offer` happen-before the consumer's `take`/`poll` of that element. So putting a fully-constructed object into the queue safely publishes it; the consumer sees it fully initialized without extra synchronization.
- *Probe: Can I use a queue as a safe-publication mechanism for shared objects?* Yes, that's a primary benefit.

**Q12. Walk through a `put` in CHM step by step.**
*Model answer:* spread the hash; lazily init the table via CAS on `sizeCtl`; compute bin index; if bin empty, CAS the node in (lock-free done); if bin head is `MOVED`, help resize then retry; else `synchronized` on the bin head, search the list/tree, insert/overwrite; treeify if chain ≥ 8 and cap ≥ 64; `addCount` (striped) and resize if over threshold.
- *Probe: Where's the only lock?* On a single bin's head node, only in the contended case.
- *Probe: How is the resize threshold computed?* capacity × load factor (default 0.75).

---

## 11. Glossary

- **ABA problem** — A CAS hazard where a value changes A→B→A so a stale CAS wrongly succeeds. Defeated by version stamps or structural/GC guarantees.
- **Atomic operation** — One that appears indivisible to other threads; no half-done state is observable.
- **AbstractQueuedSynchronizer (AQS)** — The framework underlying `ReentrantLock`, `Semaphore`, blocking-queue conditions; manages a FIFO wait queue of threads.
- **Backpressure** — A slow consumer throttling a fast producer, preventing unbounded growth; a bounded blocking queue provides it.
- **Bin / bucket** — A slot in a hash table's array holding a chain or tree of entries that hash there.
- **BlockingQueue** — A queue whose ops can block until space/elements are available.
- **CAS (Compare-And-Swap)** — Atomic "if memory==expected then set to new" CPU instruction; the basis of lock-free code.
- **Condition variable** — An object on which threads `await`/`signal` while holding a lock (e.g. `notFull`/`notEmpty`).
- **ConcurrentModificationException (CME)** — Thrown by fail-fast iterators on structural modification during iteration.
- **Context switch** — The OS saving one thread's state and loading another's; costly (~µs + cache effects).
- **Copy-on-write** — Mutation strategy that copies the whole structure on each write so reads are lock-free.
- **Delayed** — Interface for elements that become available after a delay (used by `DelayQueue`).
- **Fail-fast iterator** — Best-effort detector that throws CME on concurrent modification.
- **Fairness (lock/queue)** — FIFO grant order to waiting threads; prevents starvation, lowers throughput.
- **ForkJoinPool** — A work-stealing thread pool; backs CHM parallel bulk ops and parallel streams.
- **ForwardingNode** — A marker node in CHM's old table during resize that forwards readers to the new table.
- **Garbage collector (GC)** — The JVM subsystem reclaiming unreachable objects; it makes lock-free memory reclamation safe on the JVM.
- **Happens-before** — The JMM relation guaranteeing one action's memory effects are visible to another.
- **Hash-collision DoS** — Attack feeding colliding keys to degrade a hash map to O(n); mitigated by treeification.
- **Java Memory Model (JMM)** — The spec defining visibility/ordering of memory operations across threads.
- **JSR-166 / java.util.concurrent (JUC)** — The concurrency utilities (Doug Lea) added in Java 5.
- **Lock striping** — Splitting one lock into N locks each guarding a partition (Java 7 CHM segments).
- **Lock-free** — Algorithm guaranteeing system-wide progress without locks (some thread always advances).
- **LongAdder** — A striped, low-contention counter; `sum()` aggregates cells.
- **Lost update** — A race where concurrent read-modify-write loses one update.
- **Michael-Scott queue** — The classic lock-free FIFO queue algorithm behind `ConcurrentLinkedQueue`.
- **Monitor / intrinsic lock** — The lock associated with every Java object, used by `synchronized`.
- **Poison pill** — A sentinel value enqueued to signal consumers to stop.
- **Race condition** — A bug where correctness depends on thread interleaving.
- **Red-black tree** — A self-balancing BST giving O(log n) ops; used for treeified CHM bins.
- **ReentrantLock** — An explicit, reentrant lock (a thread can re-acquire it) with optional fairness and `Condition`s.
- **Sentinel / dummy node** — A placeholder node simplifying linked-structure edge cases.
- **Skip list** — A probabilistic layered linked list giving O(log n) expected ops; lock-free-friendly.
- **Snapshot iterator** — Iterates an immutable copy taken at creation (COWAL).
- **Striped counter** — Multiple CAS cells summed to reduce contention on a hot counter.
- **Treeify / untreeify** — Converting a CHM bin between linked list and red-black tree.
- **VarHandle** — Java 9+ low-level typed access for atomic/volatile field operations (replaces `Unsafe`).
- **volatile** — Field modifier giving visibility and limited ordering, but not compound atomicity.
- **Weakly consistent iterator** — Never throws CME; may or may not reflect concurrent changes; each element once.
- **Work stealing** — Idle threads taking tasks from others' deques (ForkJoinPool).

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Pick the collection:**
- Unordered concurrent map → **ConcurrentHashMap** (O(1), no nulls, approximate `size()`).
- Sorted concurrent map → **ConcurrentSkipListMap** (O(log n), range queries, O(n) `size()`).
- Read-mostly tiny list/set → **CopyOnWriteArrayList/Set** (lock-free reads, O(n) writes, snapshot iterator).
- Concurrent set, unordered → **ConcurrentHashMap.newKeySet()**.
- Non-blocking unbounded buffer → **ConcurrentLinkedQueue / LinkedTransferQueue** (O(n) `size()`).
- Producer/consumer with backpressure → **ArrayBlockingQueue** (bounded, 1 lock) or **LinkedBlockingQueue** (set capacity!, 2 locks).
- Direct hand-off → **SynchronousQueue**. Priority → **PriorityBlockingQueue** (bound memory). Time-delay → **DelayQueue**.

**Key numbers (CHM, current OpenJDK):** default capacity 16, load factor 0.75; TREEIFY_THRESHOLD 8; UNTREEIFY_THRESHOLD 6; MIN_TREEIFY_CAPACITY 64; resize doubles capacity; min transfer stride 16.

**BlockingQueue method matrix:** insert = add/offer/put/offer(timeout); remove = remove/poll/take/poll(timeout); examine = element/peek. No nulls allowed.

**Iterator types:** fail-fast (HashMap/ArrayList, CME), weakly consistent (most JUC), snapshot (COWAL).

**Golden rules:**
1. Individual ops are atomic; **compound actions are not** — use `merge`/`computeIfAbsent`/`replace(k,old,new)`.
2. Values inside a concurrent map are **not** auto-thread-safe.
3. **Bound your queues**; define an overflow policy.
4. `size()`/`isEmpty()` are estimates — never use for correctness; O(n) on CLQ/CSLM.
5. CHM rejects null keys/values.
6. COWAL is for read-mostly only.
7. Restore the interrupt flag on `InterruptedException`.

### Self-test (no answers — recall and explain aloud)

1. Trace a `put` into `ConcurrentHashMap` where the target bin is empty vs already occupied vs being resized. Where, if anywhere, is a lock taken?
2. Two threads run `map.computeIfAbsent(k, expensiveFn)` simultaneously on the same key. How many times does `expensiveFn` run, why, and what hazard arises if it's slow or recursive?
3. You need a thread-safe list of 50,000 elements updated ~1,000 times/second and read ~10 times/second. Is `CopyOnWriteArrayList` appropriate? What would you use and why?
4. Explain why `ConcurrentLinkedQueue.size()` is O(n) and an estimate, and how you'd track queue depth cheaply for metrics.
5. Your fixed thread pool silently OOMs under load with no rejected-task errors. What's the most likely cause, and how do you fix it with an explicit `ThreadPoolExecutor`?
6. Describe the three iterator consistency models and give one collection for each. Which can throw `ConcurrentModificationException`?
7. When would you choose `ConcurrentSkipListMap` over `ConcurrentHashMap`, and what do you pay for it?
8. Implement an atomic per-key counter and an atomic per-key list-append using only CHM combinators. What's the inner-collection pitfall?
```

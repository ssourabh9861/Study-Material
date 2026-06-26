# Atomics & CAS

> A definitive engineering-handbook chapter on lock-free atomic operations on the JVM: the hardware compare-and-swap primitive, the `java.util.concurrent.atomic` package, the ABA problem, `LongAdder`/`Striped64`, `VarHandle`, and how to build correct lock-free data structures.

---

## 1. Overview & where it fits

### 1.1 What it is

An **atomic operation** is an operation that appears to the rest of the system to happen *all at once* — it is indivisible. No other thread can observe it half-finished, and no other thread's operation can interleave inside it. The classic motivating example is `count++`. That single line of Java is actually three machine steps:

1. **Read** the current value of `count` from memory into a register.
2. **Add** one to the register.
3. **Write** the register back to memory.

This sequence is called **read-modify-write (RMW)**. If two threads run `count++` concurrently on `count == 5`, they can both read `5`, both compute `6`, and both write `6` — so two increments produce `6` instead of `7`. One update was *lost*. This is a **lost update** / **race condition**: the result depends on the unpredictable timing (the "race") of the threads.

The traditional fix is a **lock** (a `synchronized` block or a `Lock`), which forces threads to take turns. Locks are correct but carry costs: context switches, blocking, priority inversion, deadlock risk, and contention overhead.

**Atomics** offer a different fix. They are classes (`AtomicInteger`, `AtomicLong`, `AtomicReference`, …) that make RMW operations indivisible *without a lock*, by leaning on a single hardware instruction: **compare-and-swap (CAS)**. CAS lets a thread say: "*Set this memory location to a new value, but only if it still holds the value I last saw. Tell me whether you succeeded.*" Because the comparison and the write are one indivisible CPU instruction, no update can be lost. If the value changed underneath you, the CAS fails and you retry. This is the foundation of **lock-free programming**.

### 1.2 The problem it solves

- **Cheap, fine-grained mutation of shared state** — counters, sequence generators, flags, accumulators, references to immutable snapshots — without paying for a lock.
- **Progress guarantees under contention** — lock-free algorithms guarantee that *some* thread always makes progress, so the whole system can't stall just because one thread was descheduled (de-scheduled = the OS paused that thread to run another) while holding a lock.
- **Building blocks for higher-level concurrency** — almost everything in `java.util.concurrent` (queues, the executor framework's worker counts, `ConcurrentHashMap`'s size counters, `AbstractQueuedSynchronizer` which backs `ReentrantLock`/`Semaphore`/`CountDownLatch`) is built on CAS.

### 1.3 When you reach for it

| Situation | Reach for atomics? |
|---|---|
| A single shared counter / sequence number | Yes — `AtomicLong`, or `LongAdder` if write-heavy |
| A single reference you want to swap atomically (config snapshot, head of a stack) | Yes — `AtomicReference` |
| Updating *several* related fields together so they stay consistent | No — use a lock (or CAS on a single immutable object holding all fields) |
| Hot statistics counter updated by many threads, read rarely | Yes — `LongAdder` / `DoubleAdder` |
| Complex invariant across a data structure | Usually a lock; lock-free is possible but very hard to get right |

### 1.4 One-paragraph mental model

Think of CAS as an **optimistic, retry-based** alternative to locking. A lock is *pessimistic*: "I assume conflict, so I'll exclude everyone before I touch the data." CAS is *optimistic*: "I'll read the value, compute my new value, then attempt to install it only if nobody changed it in the meantime; if someone did, I'll re-read and try again." The atomic classes wrap this read-compute-CAS-retry loop in a clean API. Under low contention this is dramatically faster than locking because there is no blocking, no system call, no context switch — just a single CPU instruction. Under *high* contention it can degrade (many threads spinning and retrying), which is exactly the problem `LongAdder`/`Striped64` solve by spreading the contention across multiple memory cells.

---

## 2. Foundations from first principles

We will build the entire stack from the silicon up. Every term is defined the first time it appears.

### 2.1 The memory hierarchy and why concurrency is hard

A modern CPU does **not** read directly from main memory (DRAM) for every operation — that would be far too slow (a DRAM access is ~60–100 nanoseconds; a single CPU clock is ~0.3 ns). Instead each core has small, fast **caches**:

- **L1 cache** — tiny (~32–64 KB per core), ~1 ns access.
- **L2 cache** — larger (~256 KB–1 MB per core), ~4 ns.
- **L3 cache** — shared across cores (~8–64 MB), ~10–20 ns.
- **Main memory (DRAM)** — gigabytes, ~60–100 ns.

A **cache line** is the unit of transfer between memory and cache — almost always **64 bytes** on x86 and ARM. When you read one `int`, the CPU actually pulls in the surrounding 64-byte line.

Because each core has its own cache, two cores can hold *different copies* of the same variable. For shared-memory concurrency to work, the hardware runs a **cache coherence protocol** (commonly **MESI**: every cache line is in one of states **M**odified, **E**xclusive, **S**hared, **I**nvalid). When a core wants to write a line, it must first get the line in **Exclusive/Modified** state, which means **invalidating** (telling other cores to discard) all other copies. This invalidation traffic — bouncing a hot line between cores — is the hidden cost behind almost every concurrency performance problem.

> **Why this matters for atomics:** CAS, locks, and `volatile` all ultimately work by coordinating ownership of cache lines. The cost of an atomic operation is dominated not by the instruction itself but by the cache-coherence traffic it triggers when the line is contended.

### 2.2 Atomicity, visibility, ordering — the three concurrency guarantees

There are three distinct properties a concurrent program needs. Beginners conflate them; experts keep them separate.

1. **Atomicity** — an operation completes indivisibly (no lost updates, no torn reads). `count++` is *not* atomic. `AtomicInteger.incrementAndGet()` *is*.
2. **Visibility** — when one thread writes a value, other threads can *see* it. Without a memory barrier a write may sit in a core's store buffer / cache and never become visible to other cores. This is why a plain shared `boolean stop` flag can loop forever even after another thread sets it.
3. **Ordering** — the order in which a thread's reads and writes become visible to others. Both the **compiler** and the **CPU** are allowed to reorder instructions for performance, as long as a *single thread's* observable behavior is unchanged. Across threads, reordering can produce surprising results.

The **Java Memory Model (JMM)** is the specification (JSR-133, baked into the language since Java 5) that defines exactly which reorderings are allowed and what guarantees synchronization actions provide. Its central abstraction is **happens-before**: if action A *happens-before* action B, then A's effects are visible to and ordered before B. Key happens-before edges:

- Everything a thread does before `Thread.start()` happens-before the started thread's first action.
- A write to a `volatile` field happens-before every subsequent read of that field.
- Unlocking a monitor happens-before any subsequent lock of the same monitor.
- **A successful CAS / atomic update has the memory semantics of both a `volatile` write and read** — it establishes happens-before edges in both directions.

### 2.3 `volatile` — visibility and ordering, but not atomicity

A `volatile` field guarantees:
- **Visibility:** every read sees the most recent write; the value is never cached stale.
- **Ordering:** reads/writes of the volatile field are not reordered with surrounding memory operations (the JMM inserts memory barriers / "fences").

A `volatile` field does **not** provide atomicity for compound operations. `volatile int x; x++;` is still a racy read-modify-write — `volatile` makes each *read* and each *write* visible, but the increment is three steps with a gap in between. This is precisely the gap atomics close. **Mental model:** `volatile` = atomic *single* reads/writes + ordering; atomics = `volatile` semantics + atomic *read-modify-write*.

### 2.4 Compare-and-swap (CAS) — the core primitive

CAS is a single instruction with three operands:

```
CAS(address, expected, newValue):
    atomically:
        if *address == expected:
            *address = newValue
            return true      # (or return the old value)
        else:
            return false     # (or return the actual current value)
```

The entire `if`-compare-and-conditional-write is one indivisible step. The hardware guarantees no other core can sneak a write to `address` between the compare and the store.

**The CAS retry loop** is the canonical pattern built on top of it:

```java
long oldValue, newValue;
do {
    oldValue = atomic.get();          // 1. read current value
    newValue = compute(oldValue);     // 2. compute desired value (pure, side-effect-free!)
} while (!atomic.compareAndSet(oldValue, newValue));  // 3. install if unchanged, else retry
```

Step 2 **must be free of side effects and idempotent**, because it can run many times before the CAS succeeds. This is the single most important rule of CAS programming.

### 2.5 CAS at the CPU level: `LOCK CMPXCHG`

On **x86/x86-64**, CAS compiles to the **`CMPXCHG`** instruction (compare and exchange), prefixed with **`LOCK`**:

```asm
lock cmpxchg [mem], reg
```

Semantics of `CMPXCHG dest, src`: it compares the accumulator register (`EAX`/`RAX`) with `dest`. If equal, it loads `src` into `dest` and sets the **ZF** (zero flag). If not equal, it loads `dest` into the accumulator and clears ZF. The Java/JVM CAS reads the ZF to know success/failure.

- The **`LOCK` prefix** is what makes it atomic across cores. On older CPUs it asserted a physical bus lock (locking the entire memory bus — very expensive). On all modern CPUs, when the data fits in a single cache line, the processor uses **cache locking** instead: it acquires the cache line in **Exclusive/Modified** (MESI) state and holds coherence for the duration of the instruction. This is far cheaper than a bus lock but still requires owning the line, which means invalidating other cores' copies.
- `LOCK`-prefixed instructions on x86 also act as a **full memory barrier** (they have total-store-order fencing semantics), which is why a successful CAS gives you both acquire and release semantics for free.

On **ARM/AArch64**, there is historically no single CAS instruction. Instead CAS is built from **LL/SC**: **Load-Linked / Store-Conditional** (`LDREX`/`STREX`, or `LDXR`/`STXR` on AArch64):
- `LDXR` (load-exclusive) reads a value and sets a hardware "exclusive monitor" on that address.
- `STXR` (store-exclusive) writes *only if* nothing has touched the address since the `LDXR`; it returns a status bit indicating success.
- The JVM loops: `LDXR` → compare → `STXR`; retry if `STXR` failed.

ARMv8.1-A added **`CAS`/`CASA`/`CASL`** instructions (the LSE — Large System Extensions) that provide a true single-instruction atomic compare-and-swap, which the JVM uses on capable hardware. **Version/vendor note:** whether you get LL/SC loops or native `CAS` depends on the CPU revision and the JVM's runtime feature detection.

> **Spurious failures:** LL/SC can fail "spuriously" — a context switch, an interrupt, or even an unrelated cache event between the load and the store can clear the exclusive monitor and make `STXR` fail even though the value didn't change. This is why on ARM, `compareAndSet` may internally retry, and why the JMM exposes a separate `weakCompareAndSet` (see §4) that is *allowed* to fail spuriously and is cheaper because it can map directly to a single LL/SC attempt.

### 2.6 Lock-free, wait-free, obstruction-free — the progress guarantees

These terms describe *progress* properties of concurrent algorithms. They are precise; don't use them loosely.

- **Blocking** — a thread can be prevented from making progress indefinitely by another thread (e.g., one holding a lock that got descheduled). Locks are blocking.
- **Obstruction-free** — the weakest non-blocking guarantee: a thread makes progress *if it runs in isolation* (no contention). Contending threads might repeatedly abort each other (livelock) but a single thread always finishes.
- **Lock-free** — *system-wide* progress is guaranteed: out of all contending threads, **at least one** always makes progress in a bounded number of steps. Individual threads may starve (retry forever), but the system as a whole never stalls. A CAS retry loop is lock-free: if my CAS fails, it's because *someone else's* CAS succeeded — so progress happened.
- **Wait-free** — the strongest guarantee: **every** thread makes progress in a **bounded** number of its own steps, regardless of contention. No starvation, ever. Wait-free algorithms are much harder to design and usually slower in the common case, so they're reserved for hard-real-time systems.

| Property | Guarantee | Example |
|---|---|---|
| Blocking | None if lock holder stalls | `synchronized`, `ReentrantLock` |
| Obstruction-free | Progress alone | Some STM designs |
| Lock-free | Some thread always progresses | `AtomicInteger` CAS loop, Treiber stack, Michael-Scott queue |
| Wait-free | Every thread progresses, bounded | `AtomicLong.getAndIncrement` on x86 (single `LOCK XADD`!), wait-free queues |

> **Subtle point:** `AtomicLong.incrementAndGet()` is implemented as a CAS *loop* in `java.lang` source, which is lock-free, **but** on x86 the JIT can compile `getAndAdd`/`getAndIncrement` to a single `LOCK XADD` (fetch-and-add) instruction, which is genuinely **wait-free**. So the same Java method can be lock-free or wait-free depending on the platform and what the JIT does. (See §7.)

### 2.7 The ABA problem (intro; full treatment in §7)

CAS checks *value equality*, not *"was this untouched"*. Suppose a thread reads value **A**, then before it does its CAS, other threads change the value **A → B → A**. The original thread's CAS sees **A**, thinks nothing changed, and succeeds — but the world *did* change. For a plain counter this is usually harmless. For pointer-based structures (e.g., reusing freed nodes in a lock-free stack) it can corrupt the structure. The fix is to attach a **version/stamp** to the value so A-with-stamp-1 ≠ A-with-stamp-3 — which is exactly what `AtomicStampedReference` does.

---

## 3. How it works internally

This is the heart of the document. We trace, step by step, what happens from `AtomicInteger.incrementAndGet()` down to the cache line.

### 3.1 The layered architecture

```
  Your code:  counter.incrementAndGet()
        │
        ▼
  java.util.concurrent.atomic.AtomicInteger
        │  (delegates to VarHandle / Unsafe)
        ▼
  jdk.internal.misc.Unsafe.getAndAddInt(...)   ← CAS retry loop in Java
        │  (intrinsic candidate)
        ▼
  HotSpot JIT recognizes the intrinsic
        │
        ▼
  Emitted machine code:  lock xadd / lock cmpxchg
        │
        ▼
  CPU cache-coherence (MESI): acquire line in M state, perform RMW, fence
```

### 3.2 Inside `AtomicInteger` (modern JDK, post-9)

The field is a plain `private volatile int value;`. The `volatile` provides visibility/ordering for *simple* reads (`get()`) and writes (`set()`). The atomic RMW operations route through a `VarHandle` (Java 9+) or `Unsafe` (Java 8 and the internal implementation). A simplified version of the historical implementation:

```java
public class AtomicInteger {
    private volatile int value;

    // Java 8 era: a static Unsafe handle and the field offset
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long VALUE = U.objectFieldOffset(...); // byte offset of `value` in the object

    public final int incrementAndGet() {
        return U.getAndAddInt(this, VALUE, 1) + 1; // returns NEW value
    }
    public final int getAndIncrement() {
        return U.getAndAddInt(this, VALUE, 1);     // returns OLD value
    }
    public final boolean compareAndSet(int expect, int update) {
        return U.compareAndSetInt(this, VALUE, expect, update);
    }
}
```

And `Unsafe.getAndAddInt` is itself a CAS loop written in Java:

```java
public final int getAndAddInt(Object o, long offset, int delta) {
    int v;
    do {
        v = getIntVolatile(o, offset);                 // read current value (volatile read)
    } while (!weakCompareAndSetInt(o, offset, v, v + delta)); // CAS; retry on failure
    return v;                                          // return the value we observed (old value)
}
```

### 3.3 Intrinsics — why the loop above isn't really a loop on x86

A **JIT intrinsic** is a method the Just-In-Time compiler recognizes by name/signature and replaces with hand-written, optimal machine code instead of compiling the Java bytecode literally. `Unsafe.compareAndSetInt`, `getAndAddInt`, `getIntVolatile`, etc. are all **intrinsic candidates** in HotSpot (`@IntrinsicCandidate` annotation in modern JDK; `@HotSpotIntrinsicCandidate` earlier).

When the JIT (C2, the server compiler) sees `getAndAddInt`, it does **not** emit the literal Java loop. On x86 it emits a single:

```asm
lock xadd [rax], ecx     ; atomic fetch-and-add: returns old value in ecx, adds to memory
```

`XADD` (exchange-and-add) atomically returns the old value *and* adds — no loop, no retry, **wait-free**. For `compareAndSet` it emits `lock cmpxchg`. For pure CAS-loop operations like `getAndUpdate(fn)` with an arbitrary function, the loop *does* remain (the function can't be folded into a single instruction).

> **Interpreter vs JIT:** Before the JIT warms up, the method runs interpreted as the actual Java CAS loop calling into native `Unsafe`. After enough invocations (default tiered-compilation thresholds), it's recompiled with the intrinsic. This is why microbenchmarks must *warm up* (use JMH) before measuring atomics — cold numbers reflect the interpreter, not production behavior.

### 3.4 Step-by-step control & data flow of one `incrementAndGet()` under contention

Scenario: cores **C0** and **C1** both call `incrementAndGet()` on a counter currently `= 5`, line resident in C0's L1 in state **M** (Modified) or **E**.

1. **C1** wants to do `lock xadd`. To execute a `LOCK`-prefixed RMW, C1's core must own the cache line in **M/E** state.
2. C1 issues a **Read-For-Ownership (RFO)** on the bus/interconnect: "I want exclusive ownership of line X."
3. The coherence fabric finds the line in C0's cache. It sends an **invalidate** to C0. C0 writes back the current value (5) if dirty, transitions its copy to **I** (Invalid).
4. C1 receives the line in **M** state with value 5.
5. C1 executes `lock xadd`: atomically reads 5, writes 6, returns 5. During this instruction the line is held exclusively; no other core can read or write it.
6. The `LOCK` prefix also drains C1's store buffer and acts as a full fence, so the new value and the ordering are globally visible.
7. Now **C0** wants its `xadd`. Its copy is **I**. It issues an RFO, invalidates C1, gets the line (value 6) in **M**, performs `xadd`: reads 6, writes 7, returns 6.
8. Final value: **7**. Both increments counted. No lost update.

The expensive part is steps 2–4 and 7: the **line bouncing** between cores (sometimes called **cache-line ping-pong**). Each transfer is tens to hundreds of cycles. Under heavy contention this dominates: throughput collapses not because of the instruction but because the single hot line is constantly being shuttled across the interconnect.

### 3.5 The state machine of a CAS retry loop

For `getAndUpdate(IntUnaryOperator fn)`:

```
        ┌────────────┐
        │   READ v   │◄──────────────┐
        └─────┬──────┘               │
              ▼                       │
        ┌────────────┐               │
        │ next=fn(v) │ (pure compute)│
        └─────┬──────┘               │
              ▼                       │
        ┌────────────┐  CAS fails    │
        │  CAS(v,next)├───────────────┘  (someone else won; re-read)
        └─────┬──────┘
              │ CAS succeeds
              ▼
        ┌────────────┐
        │  RETURN v  │  (or next, depending on method)
        └────────────┘
```

Invariants:
- The loop **cannot deadlock** (no lock held).
- The loop **can livelock-ish-starve** an unlucky thread (it may retry many times), but the system is **lock-free** because every failed CAS implies someone else's CAS succeeded.
- `fn` may be invoked multiple times, so it must be **side-effect-free and idempotent**.

### 3.6 Memory-ordering semantics of each atomic operation

Modern `VarHandle` exposes a *spectrum* of memory orderings (this is the deep part):

| Mode | Visibility/ordering | Cost | Methods |
|---|---|---|---|
| **Plain** | No ordering, no atomicity guarantees beyond word-tearing freedom for ≤32-bit | Cheapest (like a normal field) | `VarHandle.get/set` (plain) |
| **Opaque** | Atomic + per-variable coherence (writes to *this* variable are ordered), but no cross-variable ordering | Cheap | `getOpaque/setOpaque` |
| **Acquire/Release** | Release write happens-before subsequent Acquire read of same var; one-directional fences | Medium | `getAcquire/setRelease`, `compareAndExchangeAcquire/Release` |
| **Volatile (sequentially consistent)** | Full bidirectional ordering; same as `volatile` field | Most expensive | `getVolatile/setVolatile`, `compareAndSet` |

- A plain `AtomicInteger.get()` is a **volatile read**; `set()` is a **volatile write**.
- `lazySet()` (legacy) ≡ `setRelease` — a **release-store**: the write will become visible eventually but is *not* immediately ordered as a full volatile store, so it's cheaper (it omits the expensive store-load fence on x86). Used when you want to publish a value without paying for full sequential consistency, e.g., nulling out a reference for GC, or a producer setting a value a consumer polls.
- `compareAndSet` has **full volatile** (sequentially consistent) semantics on success.
- `weakCompareAndSetPlain` (formerly `weakCompareAndSet`) may **fail spuriously** and has **plain** memory effects — cheapest, used inside tight retry loops where you re-read anyway.

---

## 4. The complete toolkit

### 4.1 Scalar atomic classes

| Class | Wraps | Notable methods |
|---|---|---|
| `AtomicInteger` | `int` | `get/set`, `getAndIncrement`, `incrementAndGet`, `getAndAdd`, `addAndGet`, `compareAndSet`, `getAndSet`, `getAndUpdate`, `updateAndGet`, `getAndAccumulate`, `accumulateAndGet`, `lazySet` |
| `AtomicLong` | `long` | same shape as `AtomicInteger` |
| `AtomicBoolean` | `boolean` | `get/set`, `compareAndSet`, `getAndSet`, `lazySet` |
| `AtomicReference<V>` | object ref | `get/set`, `compareAndSet`, `getAndSet`, `getAndUpdate`, `updateAndGet`, `getAndAccumulate`, `accumulateAndGet`, `lazySet` |

**Key method semantics (using `AtomicInteger`):**

| Method | Returns | What it does |
|---|---|---|
| `get()` | current | volatile read |
| `set(v)` | void | volatile write |
| `lazySet(v)` | void | release-store (cheaper than `set`); visible eventually |
| `getAndSet(v)` | old | atomically set and return previous |
| `incrementAndGet()` | new | `+1`, return new |
| `getAndIncrement()` | old | `+1`, return old |
| `addAndGet(d)` / `getAndAdd(d)` | new / old | atomic add |
| `compareAndSet(e,u)` | boolean | CAS, full volatile semantics |
| `weakCompareAndSetPlain(e,u)` | boolean | CAS allowed to fail spuriously, plain memory effects |
| `getAndUpdate(fn)` | old | CAS loop applying `fn` (1-arg) |
| `updateAndGet(fn)` | new | CAS loop applying `fn` |
| `getAndAccumulate(x,fn)` | old | CAS loop applying `fn(current, x)` (2-arg) |
| `accumulateAndGet(x,fn)` | new | CAS loop applying `fn(current, x)` |

> **`getAndUpdate` vs `getAndAccumulate`:** use `getAndUpdate(v -> v*2)` for a unary transform; use `getAndAccumulate(10, Math::max)` when you have an external argument and want to combine it with the current value (here: "set to max of current and 10"). The accumulator function must be side-effect-free.

### 4.2 ABA-safe references

| Class | What it adds | Methods |
|---|---|---|
| `AtomicStampedReference<V>` | a (reference, **int stamp**) pair updated together | `getReference`, `getStamp`, `get(int[] stampHolder)`, `compareAndSet(expRef, newRef, expStamp, newStamp)`, `attemptStamp`, `set` |
| `AtomicMarkableReference<V>` | a (reference, **boolean mark**) pair | `getReference`, `isMarked`, `get(boolean[] markHolder)`, `compareAndSet(expRef, newRef, expMark, newMark)`, `attemptMark`, `set` |

`AtomicStampedReference` defeats ABA by incrementing the stamp on every update — even if the reference returns to a prior value, the stamp won't, so a stale CAS fails. `AtomicMarkableReference` is for *logical deletion* (e.g., marking a node "deleted" before physically unlinking it in lock-free lists).

### 4.3 Field updaters (reflection-based, retrofit existing fields)

| Class | Use |
|---|---|
| `AtomicIntegerFieldUpdater<T>` | atomic ops on an existing `volatile int` field of class `T` without wrapping it in an object |
| `AtomicLongFieldUpdater<T>` | same for `volatile long` |
| `AtomicReferenceFieldUpdater<T,V>` | same for `volatile V` ref field |

These let you save the per-instance memory of an `AtomicX` object when you have millions of objects. The target field **must** be `volatile` and non-`static`. They are largely superseded by `VarHandle` (§4.6) in Java 9+ but still appear in legacy code and JDK internals.

```java
class Node {
    volatile int status; // must be volatile, non-static
    static final AtomicIntegerFieldUpdater<Node> ST =
        AtomicIntegerFieldUpdater.newUpdater(Node.class, "status");
}
// ST.compareAndSet(nodeInstance, 0, 1);
```

### 4.4 Atomic arrays

| Class | Use |
|---|---|
| `AtomicIntegerArray` | each element atomically updatable; `get/set/compareAndSet/getAndAdd(i, ...)` |
| `AtomicLongArray` | same for `long[]` |
| `AtomicReferenceArray<E>` | same for object array |

These provide *element-wise* atomicity with proper volatile semantics per element — a plain `volatile int[]` only makes the *array reference* volatile, not the elements.

### 4.5 High-contention accumulators (`Striped64` family)

| Class | Purpose |
|---|---|
| `LongAdder` | high-throughput `add`/`increment`; read total via `sum()`/`longValue()` |
| `DoubleAdder` | floating-point accumulation |
| `LongAccumulator` | generalized: combine with a custom `LongBinaryOperator` and identity |
| `DoubleAccumulator` | floating-point generalized |

`Striped64` is the (package-private) base class implementing **cell striping** (see §7). Key trait: writes scale almost linearly with cores; `sum()` is **not** atomic with concurrent updates (it reads cells one at a time, so it's an approximate snapshot — fine for stats, not for a value you CAS on).

| Method (LongAdder) | Notes |
|---|---|
| `add(x)` | the hot path; touches one striped cell |
| `increment()` / `decrement()` | `add(1)` / `add(-1)` |
| `sum()` | sum of base + all cells; not atomic, racy snapshot |
| `reset()` | reset to zero; only safe with no concurrent updates |
| `sumThenReset()` | sum then reset; used in periodic sampling |
| `longValue()/intValue()/doubleValue()` | call `sum()` |

### 4.6 `VarHandle` (Java 9+, the modern `Unsafe` replacement)

`VarHandle` (JEP 193, Java 9) is a **typed, safe, standard** way to perform atomic and ordered operations on fields, array elements, and even off-heap buffers — replacing the unsupported, internal `sun.misc.Unsafe`. A `VarHandle` is a *handle* (like a `MethodHandle`) to a variable, obtained via `MethodHandles.lookup()`.

```java
class Counter {
    private volatile long value;
    private static final VarHandle VALUE;
    static {
        try {
            VALUE = MethodHandles.lookup()
                       .findVarHandle(Counter.class, "value", long.class);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    long incrementAndGet() {
        return (long) VALUE.getAndAdd(this, 1L) + 1; // wait-free on x86
    }
    boolean cas(long expect, long update) {
        return VALUE.compareAndSet(this, expect, update);
    }
}
```

**Access-mode method families on `VarHandle`:**

| Family | Methods | Memory order |
|---|---|---|
| Reads | `get`, `getOpaque`, `getAcquire`, `getVolatile` | plain → opaque → acquire → volatile |
| Writes | `set`, `setOpaque`, `setRelease`, `setVolatile` | plain → opaque → release → volatile |
| CAS | `compareAndSet` (vol), `weakCompareAndSet`, `weakCompareAndSetPlain/Acquire/Release` | various |
| compareAndExchange | `compareAndExchange`, `...Acquire`, `...Release` | returns *witness* (actual current) value, not boolean |
| Numeric atomic | `getAndAdd`, `getAndAddAcquire/Release` | atomic add |
| Bitwise atomic | `getAndBitwiseOr/And/Xor` (+Acquire/Release variants) | atomic bit ops |
| Exchange | `getAndSet`, `getAndSetAcquire/Release` | atomic swap |

> **`compareAndExchange` vs `compareAndSet`:** `compareAndSet` returns a boolean. `compareAndExchange` returns the **witness value** — the value actually present at the time of the attempt. On failure you get the current value *for free*, saving a separate re-read in the retry loop:
> ```java
> long cur = (long) VALUE.getVolatile(this);
> while (true) {
>     long next = compute(cur);
>     long witness = (long) VALUE.compareAndExchange(this, cur, next);
>     if (witness == cur) return next; // success
>     cur = witness;                   // failed; witness IS the fresh value, no re-read
> }
> ```

### 4.7 `sun.misc.Unsafe` / `jdk.internal.misc.Unsafe` (legacy / internal)

The original mechanism. `sun.misc.Unsafe` is **not** a supported public API, is **strongly encapsulated** since Java 9 (and increasingly restricted; deprecated-for-removal memory-access methods in recent JDKs per JEP 471), and should not be used in application code. Know it because tons of legacy code and frameworks (pre-`VarHandle` Netty, Disruptor, etc.) use it. Relevant methods: `compareAndSwapInt/Long/Object` (Java 8), renamed `compareAndSetInt/...` internally, `getAndAddInt/Long`, `getAndSetObject`, `putOrderedInt/Object` (= `lazySet`), `objectFieldOffset`, `arrayBaseOffset`, `arrayIndexScale`.

### 4.8 JVM flags relevant to atomics & contention

| Flag | Default | Effect |
|---|---|---|
| `-XX:-UseBiasedLocking` | biased locking removed by default in JDK 15+ (JEP 374), fully removed later | historically reduced uncontended lock cost; irrelevant to CAS but relevant when comparing locks vs atomics |
| `-XX:+UseCondCardMark` | off | conditional card marking — reduces false sharing on the GC card table; matters for reference-writing benchmarks |
| `-XX:ContendedPaddingWidth=N` | 128 (bytes) | padding width used by `@Contended` |
| `-XX:-RestrictContended` | restricted by default | must pass `-XX:-RestrictContended` to use `@Contended` outside the JDK |
| `-XX:+UseParallelGC` / G1 / ZGC | platform-dependent | GC choice affects reference-CAS cost via write barriers |
| `-XX:LoopUnrollLimit`, tiered thresholds | various | influence whether CAS loops are JIT-compiled to intrinsics |

`@jdk.internal.vm.annotation.Contended` (application-facing `@Contended` was `sun.misc.Contended` in Java 8) tells the JVM to **pad** a field onto its own cache line to prevent **false sharing** (see §6).

---

## 5. Code examples by use case

### 5.1 A simple atomic counter (correct vs broken)

```java
import java.util.concurrent.atomic.AtomicLong;

final class HitCounter {
    private final AtomicLong count = new AtomicLong();

    void recordHit()      { count.incrementAndGet(); } // atomic, no lost updates
    long total()          { return count.get(); }      // volatile read
}

// BROKEN version for contrast:
final class BrokenCounter {
    private volatile long count; // volatile gives visibility but NOT atomic increment
    void recordHit() { count++; } // read-modify-write race -> lost updates under contention
    long total()     { return count; }
}
```

### 5.2 A unique ID / sequence generator

```java
final class IdGenerator {
    private final AtomicLong seq = new AtomicLong(0);
    /** Wait-free on x86 (LOCK XADD). Monotonic, gap-free, thread-safe. */
    long nextId() { return seq.incrementAndGet(); }
}
```

If you need a *block* of ids (to amortize contention), grab a range:

```java
long start = seq.getAndAdd(1000); // reserve [start, start+1000)
// hand out start, start+1, ... locally without further synchronization
```

### 5.3 Atomically swapping an immutable configuration snapshot

A very common production pattern: keep mutable shared state as an **immutable object** referenced by an `AtomicReference`. Readers get a consistent snapshot for free; writers swap the whole thing.

```java
import java.util.concurrent.atomic.AtomicReference;

final class Config { // immutable
    final int maxConns; final String endpoint;
    Config(int maxConns, String endpoint) { this.maxConns = maxConns; this.endpoint = endpoint; }
    Config withMaxConns(int n) { return new Config(n, endpoint); }
}

final class ConfigHolder {
    private final AtomicReference<Config> ref =
        new AtomicReference<>(new Config(100, "https://api.example.com"));

    Config current() { return ref.get(); } // always a consistent snapshot

    /** Atomically update maxConns even under concurrent writers. */
    void setMaxConns(int n) {
        ref.updateAndGet(cur -> cur.withMaxConns(n)); // CAS loop; fn must be pure
    }
}
```

This avoids torn reads where one reader sees the new `maxConns` but the old `endpoint`.

### 5.4 `getAndUpdate` / `accumulateAndGet` for compound logic

```java
AtomicInteger highWaterMark = new AtomicInteger();

// Record a new maximum atomically:
void observe(int sample) {
    highWaterMark.accumulateAndGet(sample, Math::max); // CAS loop: set to max(current, sample)
}

// Bounded counter that never exceeds CAP:
final int CAP = 1000;
AtomicInteger permits = new AtomicInteger();
boolean tryAcquire() {
    return permits.getAndUpdate(v -> v < CAP ? v + 1 : v) < CAP; // returns true iff we incremented
}
```

### 5.5 A lock-free Treiber stack (classic CAS data structure)

The **Treiber stack** (R. Kent Treiber, 1986) is the "hello world" of lock-free structures: a singly linked list whose head is swapped via CAS.

```java
import java.util.concurrent.atomic.AtomicReference;

public final class TreiberStack<E> {
    private static final class Node<E> {
        final E item; Node<E> next;
        Node(E item) { this.item = item; }
    }
    private final AtomicReference<Node<E>> head = new AtomicReference<>();

    public void push(E item) {
        Node<E> n = new Node<>(item);
        Node<E> cur;
        do {
            cur = head.get();   // read current head
            n.next = cur;       // link new node ahead of it
        } while (!head.compareAndSet(cur, n)); // install; retry if head changed
    }

    public E pop() {
        Node<E> cur, next;
        do {
            cur = head.get();
            if (cur == null) return null; // empty
            next = cur.next;
        } while (!head.compareAndSet(cur, next)); // swing head to next
        return cur.item;
    }
}
```

This is lock-free and correct **for garbage-collected Java**. In a manual-memory language it would be vulnerable to ABA (a popped node could be freed and reallocated). The GC saves us here (a node can't be reused while any thread still references it), which is a deep and important point — see §7.4.

### 5.6 A lock-free counter that *defeats* ABA explicitly

To demonstrate `AtomicStampedReference` (you'd use this when the value type is pointer-like and reuse is possible):

```java
import java.util.concurrent.atomic.AtomicStampedReference;

final class StampedBox {
    private final AtomicStampedReference<Integer> ref =
        new AtomicStampedReference<>(0, 0); // (value, stamp)

    boolean tryIncrement() {
        int[] stampHolder = new int[1];
        Integer cur = ref.get(stampHolder); // reads value AND stamp atomically
        int stamp = stampHolder[0];
        return ref.compareAndSet(cur, cur + 1, stamp, stamp + 1); // bump stamp every time
    }
}
```

Even if another thread does `0 → 1 → 0`, the stamp advanced `0 → 1 → 2`, so a stale CAS with `(0, stamp=0)` fails.

### 5.7 High-contention statistics with `LongAdder`

```java
import java.util.concurrent.atomic.LongAdder;

final class RequestMetrics {
    private final LongAdder requests = new LongAdder();
    private final LongAdder bytes    = new LongAdder();

    void onRequest(int payloadBytes) {
        requests.increment();   // touches a per-thread-ish striped cell -> scales with cores
        bytes.add(payloadBytes);
    }
    // Read periodically (e.g., metrics scrape). sum() is a racy snapshot — fine for stats.
    long requestCount() { return requests.sum(); }
    long byteCount()    { return bytes.sum(); }
}
```

### 5.8 `LongAccumulator` for a custom combine (running max across threads)

```java
import java.util.concurrent.atomic.LongAccumulator;

// identity = Long.MIN_VALUE so any real sample wins
LongAccumulator maxLatency =
    new LongAccumulator(Long::max, Long.MIN_VALUE);

void record(long latencyMicros) { maxLatency.accumulate(latencyMicros); }
long peak() { return maxLatency.get(); }
```

### 5.9 `VarHandle`-based lock-free linked node (no per-field wrapper objects)

```java
import java.lang.invoke.*;

final class MpscNode<E> {
    final E item;
    volatile MpscNode<E> next; // updated via VarHandle, saves an AtomicReference per node

    private static final VarHandle NEXT;
    static {
        try {
            NEXT = MethodHandles.lookup()
                     .findVarHandle(MpscNode.class, "next", MpscNode.class);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    MpscNode(E item) { this.item = item; }

    boolean casNext(MpscNode<E> expect, MpscNode<E> update) {
        return NEXT.compareAndSet(this, expect, update);
    }
    void lazySetNext(MpscNode<E> n) { NEXT.setRelease(this, n); } // cheap publish
}
```

### 5.10 Spin lock built on CAS (to show atomics underpin locks too)

```java
import java.lang.invoke.*;

final class CasSpinLock {
    private volatile int state; // 0 = free, 1 = held
    private static final VarHandle S;
    static {
        try { S = MethodHandles.lookup().findVarHandle(CasSpinLock.class, "state", int.class); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    void lock() {
        // spin until we CAS 0 -> 1
        while (!S.compareAndSet(this, 0, 1)) {
            Thread.onSpinWait(); // JDK 9+: PAUSE instruction hint, reduces power/contention
        }
    }
    void unlock() { S.setRelease(this, 0); } // release-store is enough to publish the unlock
}
```

`Thread.onSpinWait()` emits the x86 `PAUSE` instruction (or ARM `YIELD`), which hints the CPU you're in a spin loop — it reduces speculative memory-order violations and power draw. Use it in any busy-wait.

### 5.11 Double-width CAS via a single `AtomicReference` to an immutable pair

When you need to CAS two values together (e.g., a (value, version) without `AtomicStampedReference`'s boxing), pack them into an immutable record and CAS the reference:

```java
record Versioned<T>(T value, long version) {}

AtomicReference<Versioned<String>> cell =
    new AtomicReference<>(new Versioned<>("init", 0));

boolean update(String newValue) {
    Versioned<String> cur = cell.get();
    return cell.compareAndSet(cur, new Versioned<>(newValue, cur.version() + 1));
}
```

This is the idiomatic Java way to get "wide" CAS without `Unsafe` double-word tricks, at the cost of an allocation per update.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Uncontended CAS is fast** but **not free**: a `LOCK`-prefixed instruction on modern x86 is roughly **10–25 ns** even uncontended (it still fences and may stall the store buffer), vs ~0.3 ns for a plain write. An uncontended biased/thin lock used to be comparable; an *uncontended* `synchronized` after JIT is also cheap.
- **Contended CAS degrades sharply.** With N threads hammering one `AtomicLong`, throughput can *fall* as N rises because of cache-line ping-pong and growing retry counts. This is the single biggest reason to reach for `LongAdder` on hot counters.
- **`LongAdder` trades read cost for write throughput.** Writes scale near-linearly with cores; `sum()` costs O(number of cells) and is racy. Use it when writes ≫ reads.
- **Prefer `getAndAdd`/`incrementAndGet` over manual CAS loops** for plain arithmetic — the JIT can lower them to wait-free `LOCK XADD`. A hand-rolled `compareAndSet` loop for `+1` *cannot* become `XADD`.
- **Use `compareAndExchange`** to avoid a redundant re-read in retry loops (you get the witness value for free).
- **Use weaker memory modes when correct.** `setRelease`/`getAcquire`/`getOpaque` skip fences you don't need. But only do this if you can *prove* the ordering is sufficient — it's a frequent source of subtle bugs.

### 6.2 False sharing — the silent killer

**False sharing** occurs when two *independent* variables sit on the **same 64-byte cache line**, and two cores update them. Even though the variables are logically unrelated, every update by one core invalidates the line in the other core's cache, causing constant ping-pong — as if they were the same contended variable. Symptoms: two atomics that "should" scale independently both slow down when used together.

Fixes:
- **`@Contended`** (`jdk.internal.vm.annotation.Contended`; needs `-XX:-RestrictContended` outside the JDK) pads the field to its own cache line. `LongAdder`'s `Cell` is annotated `@Contended`.
- **Manual padding** (legacy): add unused `long p1..p7;` fields around the hot field (fragile; JIT may remove unused fields, so this is discouraged in favor of `@Contended`).
- **Array element spacing**: in `AtomicLongArray`, adjacent elements *do* share lines — pad indices if elements are independently hot.

### 6.3 Correctness pitfalls

- **Side effects in the CAS function.** `getAndUpdate(v -> { sendEmail(); return v+1; })` may send many emails. The function is invoked once per retry.
- **Compound multi-variable invariants.** Atomics make a *single* variable atomic. If your invariant spans two atomics, you can still see inconsistent intermediate states. Use a lock or pack the fields into one CAS'd immutable object.
- **`sum()` is not a consistent snapshot** in `LongAdder` — never CAS on or branch on equality of a `LongAdder.sum()`.
- **ABA** when CASing pointers with reuse — use `AtomicStampedReference` or rely on GC + immutability.
- **Mixing plain and volatile access** to the same field defeats the JMM guarantees in non-obvious ways.

### 6.4 Memory & footprint

- Each `AtomicX` is a separate heap object (12–16 byte header + the field). With millions of entities, prefer `AtomicXFieldUpdater` or `VarHandle` on a `volatile` field to avoid the wrapper.
- `@Contended` padding costs ~128 bytes per padded field — great for a handful of hot counters, wasteful if applied broadly.
- `LongAdder` grows its `Cell[]` array up to the number of CPUs; bounded but larger than a single `AtomicLong`.

### 6.5 Security

- Atomics themselves aren't a security boundary, but **`Unsafe`** is dangerous: arbitrary memory access can corrupt the heap, bypass type safety, and crash the JVM. Prefer `VarHandle`. In hardened deployments, strong encapsulation (`--illegal-access=deny`, the default since Java 16) blocks reflective `Unsafe` access.
- Time-of-check/time-of-use issues: never use a racy `LongAdder.sum()` for a security decision (rate limiting must use atomic ops, not `sum()` comparisons).

### 6.6 Observability

- There's no built-in JFR (Java Flight Recorder) event for CAS contention specifically, but **`jdk.JavaMonitorEnter`/`Wait`** events cover lock contention — useful when deciding lock vs atomic.
- **`perf stat`** on Linux: watch `cache-misses`, `LLC-load-misses`, and `cycles` to spot cache-line ping-pong from contended atomics.
- **`perf c2c`** (cache-to-cache) is *the* tool for diagnosing false sharing — it pinpoints which cache lines bounce between cores and which source lines touch them.
- Async-profiler / `perf record` will show hot frames stuck in CAS loops (`getAndAddLong`, `Striped64.longAccumulate`).

### 6.7 Testing

- **`jcstress`** (the OpenJDK Java Concurrency Stress test harness) is the gold standard for verifying lock-free code against the JMM — it runs billions of interleavings and checks for forbidden outcomes. Use it for any custom lock-free structure.
- **JMH** (Java Microbenchmark Harness) for throughput/latency numbers; never trust hand-rolled `System.nanoTime()` loops for atomics (warmup, dead-code elimination, and on-stack replacement will lie to you).
- Stress-test with thread counts ≫ cores to surface livelock-ish starvation and retry storms.

### 6.8 Production hardening checklist

- Pick `LongAdder` for write-hot, read-cold counters; `AtomicLong` for read-hot or when you need exact CAS.
- Pad hot independent counters with `@Contended` if `perf c2c` shows false sharing.
- Keep CAS update functions pure and cheap.
- Bound retries only if you can fall back safely; unbounded CAS loops are normal and correct for lock-free code, but if your "compute" step is expensive, high contention can waste enormous CPU.
- Document the memory-ordering mode you rely on if you drop below volatile.

### 6.9 Anti-patterns

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| `volatile x; x++` | not atomic | `AtomicInteger.incrementAndGet()` |
| Hand CAS loop for `+1` | can't become `XADD` | `getAndAdd` |
| Side effects in `updateAndGet` fn | runs many times | pure function |
| `LongAdder.sum()` in a CAS/equality | racy snapshot | `AtomicLong` if you need exact |
| One `AtomicLong` for a hot global counter under 64 threads | ping-pong collapse | `LongAdder` |
| CAS-ing two atomics for a joint invariant | inconsistent intermediates | one immutable object, or lock |
| Using `Unsafe` in app code | unsupported, encapsulated, unsafe | `VarHandle` |
| Lock-free everything | hard to get right, often slower | use a lock unless profiling justifies lock-free |

---

## 7. Advanced topics & deep internals

### 7.1 `LongAdder` / `Striped64` — cell striping in depth

`AtomicLong` funnels every update onto one memory location, so under contention all cores fight for one cache line. `Striped64` (the base of `LongAdder`, `DoubleAdder`, `LongAccumulator`, `DoubleAccumulator`) breaks that bottleneck with **striping**: it spreads updates across an array of independent **`Cell`s**, each on its own cache line.

Internal structure:
- A `volatile long base` — used in the uncontended fast path (just like an `AtomicLong`).
- A `volatile Cell[] cells` — created lazily *only when contention is detected*.
- Each `Cell` holds a `volatile long value`, annotated `@Contended` so it occupies its own cache line (no false sharing between cells).
- A per-thread probe hash (`Thread.threadLocalRandomProbe`) maps each thread to a cell index.

**Hot-path algorithm (`add(x)`):**
1. If `cells == null`, try `casBase(base, base + x)`. If it succeeds (no contention) — done. This is the cheap common case.
2. If `casBase` fails (contention!) or cells already exist, compute the thread's probe → index into `cells`.
3. CAS into that thread's cell: `cell.cas(v, v + x)`.
4. If *that* cell CAS also fails (the table is too small or threads collide), call `longAccumulate(...)` which:
   - **Grows** the `cells` array (doubling, capped at the next power of two ≥ number of CPUs).
   - **Rehashes** the thread's probe (so colliding threads spread out).
   - Retries.

**Read (`sum()`):** `base + Σ cells[i].value`, reading each independently. Because updates continue during the sum, the result is a **racy snapshot** — accurate enough for monitoring, not a linearization point.

**Why it beats `AtomicLong`:** under N-core contention, `AtomicLong` does O(N) serialized cache-line transfers per "round" of updates; `LongAdder` lets each core mostly own its own cell line, so updates proceed in parallel with little coherence traffic. The classic result: `AtomicLong` throughput is roughly flat or *declining* past a few threads; `LongAdder` scales near-linearly to the core count. Cost: more memory and a non-atomic, slightly more expensive `sum()`.

> **`LongAccumulator` generalization:** instead of `+`, you supply a `LongBinaryOperator` and an identity. The same striping applies, but the combine function **must be associative and commutative** for the result to be order-independent (because cells are combined in arbitrary order). `Long::max`, `+`, `*` qualify; subtraction does not.

### 7.2 The ABA problem — full treatment

**Definition:** a thread reads value A; between its read and its CAS, other threads change A→B→A; the thread's CAS(A→C) succeeds though the underlying state changed.

**When it's harmless:** monotonic counters, or any case where you only care about the *current value* and not the history (A truly is A).

**When it's dangerous:** when the value is a *pointer* and nodes can be **reused** (freed and reallocated). Canonical bug in a manual-memory Treiber stack:
1. Thread T1 reads `head = A`, sees `A.next = B`. T1 is about to CAS head from A to B.
2. T1 is preempted.
3. T2 pops A (head→B), pops B (head→C), pushes A back (head→A). Now A is back at head but **`A.next` is now C, not B**.
4. T1 resumes, does `CAS(head, A, B)` — succeeds because head == A — but B was already removed; head now points at a freed/wrong node. **Corruption.**

**Solutions:**
- **Tagged pointers / version stamps** — `AtomicStampedReference` (Java) or a packed (pointer, counter) double-word CAS in native code. Every mutation bumps the counter; ABA becomes detectable.
- **Hazard pointers** — threads publish the pointers they're using; memory isn't reclaimed while hazarded. Used in C++ lock-free libraries; not in the JDK.
- **Epoch / RCU (Read-Copy-Update)** — reclaim memory only after a grace period when no reader can hold an old reference. The Linux kernel's RCU is the famous example.
- **Garbage collection** — in Java, the GC won't reclaim a node while *any* thread holds a reference, so the dangerous "reuse" step can't happen. This is why the §5.5 Treiber stack is correct in Java without stamps. **Caveat:** GC defeats *pointer-identity* ABA, but **value ABA still happens** (e.g., a counter going 0→1→0). For value semantics where history matters, you still need a stamp.

### 7.3 Memory-ordering spectrum (`VarHandle`) — when each is correct

- **Plain**: use only for thread-confined or already-published-by-other-means data.
- **Opaque**: guarantees the variable's own updates are coherent and progress is visible eventually; useful for a "stop" flag polled in a loop where you don't need it ordered against other variables. Cheaper than volatile.
- **Acquire/Release**: the workhorse for producer/consumer publishing. A `setRelease` of a "ready" flag, paired with a `getAcquire` read, ensures everything the producer wrote *before* the release is visible to the consumer *after* the acquire — without full sequential consistency. This is the Java analog of C++11 `memory_order_acquire`/`release`.
- **Volatile (seq-cst)**: total order across all sequentially-consistent operations. The safe default; the expensive one (needs a store-load fence on x86).

> On **x86**, loads have acquire and stores have release semantics *by default* (it's a relatively strong TSO — Total Store Order — model), so `getAcquire`/`setRelease` are nearly free, and only the **store-load** fence (the `mfence`/`lock`-prefix in a volatile store) is expensive. On **ARM/PowerPC** (weaker models) the distinction matters much more — release/acquire need explicit barriers but are still cheaper than full seq-cst. This is why portable lock-free code should pick the *weakest correct* mode rather than assuming x86 freebies.

### 7.4 Why `weakCompareAndSet` exists

On LL/SC architectures, a strong `compareAndSet` must loop internally to mask spurious `STXR` failures, costing extra instructions. `weakCompareAndSet` is *allowed* to return `false` spuriously (even when the value matched), letting the JIT emit a single LL/SC attempt with no internal retry. In an *outer* retry loop you re-read anyway, so spurious failure is harmless and you get cheaper codegen. Rule of thumb: use `weakCompareAndSet*` **only inside a loop that already re-reads and retries**; use `compareAndSet` for a one-shot conditional update.

### 7.5 Fetch-and-add vs CAS, and wait-freedom

`getAndAdd`/`getAndIncrement` are special: on x86 they lower to `LOCK XADD`, a single atomic instruction that is **wait-free** (every thread completes in a bounded number of its own steps — no retry loop). A CAS loop is only **lock-free**. So when your operation is pure addition, prefer the `*Add*` methods to get the stronger progress guarantee and better codegen. Arbitrary `updateAndGet(fn)` cannot be a single instruction and remains a lock-free CAS loop.

### 7.6 Double-word and wider CAS

Hardware offers **`CMPXCHG8B`** (64-bit on 32-bit CPUs) and **`CMPXCHG16B`** (128-bit on x86-64) for double-width CAS — useful for (pointer, counter) tagged pointers in native code. Java does **not** expose 128-bit CAS directly; you emulate "wide" CAS by CASing an `AtomicReference` to an immutable two-field object (§5.11), trading an allocation for the wide update. Project Valhalla's value types may eventually make this allocation-free, but that's not GA as of this writing.

### 7.7 Elimination and back-off (advanced lock-free tuning)

Real high-performance lock-free stacks (e.g., the **elimination-backoff stack**) add an **elimination array**: when a `push` and a `pop` collide under contention, they "cancel out" by exchanging the value directly through a side channel, avoiding the contended head CAS entirely. Combined with **exponential backoff** (waiting a randomized, growing interval after each failed CAS), this dramatically improves scalability past the point where naive CAS loops collapse. The JDK's `Exchanger` and parts of `ConcurrentLinkedQueue`/`ForkJoinPool` use related ideas.

### 7.8 `@Contended` internals

`@Contended` instructs the JVM's field-layout pass to insert padding (default `-XX:ContendedPaddingWidth=128`, two cache lines to also defeat adjacent-line prefetcher false sharing) before and/or after the annotated field or class. Grouping: `@Contended("group")` co-locates fields with the same group tag on a shared line while isolating different groups. `LongAdder.Cell` uses it to guarantee each cell owns a line.

### 7.9 How `AbstractQueuedSynchronizer` (AQS) uses CAS

`ReentrantLock`, `Semaphore`, `CountDownLatch`, `ReentrantReadWriteLock`, and `CompletableFuture`'s waiters are built on **AQS**, whose core is a single `volatile int state` mutated via CAS, plus a CLH-style lock-free wait queue whose nodes are linked via CAS. So even "blocking locks" in Java sit on top of atomic CAS for their fast path — acquiring an uncontended `ReentrantLock` is essentially one successful `compareAndSet(0,1)` on the state, no OS call. This is why the lock-vs-atomic comparison is nuanced: an uncontended lock and a CAS are similarly cheap; the divergence shows under contention and in the blocking/parking machinery.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Atomics vs locks

| Dimension | CAS / atomics | Lock (`synchronized` / `ReentrantLock`) |
|---|---|---|
| Progress | Lock-free (some thread progresses) | Blocking (stalls if holder descheduled) |
| Uncontended cost | ~1 CAS (10–25 ns) | ~1 CAS for fast path + bookkeeping; similar |
| Contended cost | spin/retry, cache ping-pong | park/unpark, context switches |
| Scope | single variable (or one immutable object) | arbitrary critical section, many variables |
| Composability | poor (can't atomically combine two atomics) | good (one lock guards many fields) |
| Deadlock risk | none | yes (lock ordering) |
| Fairness | none (can starve) | optional fair mode in `ReentrantLock` |
| Ease of correctness | hard for nontrivial structures | easier to reason about |
| Best for | counters, flags, single-ref swaps, hot stats | multi-field invariants, complex critical sections |

**Use atomics when:** the shared state is a single value (or a single immutable object), updates are short, and you want non-blocking progress.
**Avoid atomics (use a lock) when:** you must update multiple fields together, the critical section is long, or correctness is subtle and a lock is clearly simpler.

### 8.2 `AtomicLong` vs `LongAdder`

| | `AtomicLong` | `LongAdder` |
|---|---|---|
| Write throughput under contention | degrades | scales near-linearly |
| Exact CAS / `compareAndSet` | yes | no |
| Read (`get`/`sum`) | exact, cheap | racy snapshot, O(cells) |
| Memory | one field | base + `Cell[]` |
| Use when | reads frequent, need exact value/CAS | writes ≫ reads, value is a statistic |

**Rule:** counter you only `increment` and occasionally read → `LongAdder`. Value you CAS on or read for exact decisions → `AtomicLong`.

### 8.3 `AtomicReference` vs `AtomicStampedReference` vs `synchronized`

| | `AtomicReference` | `AtomicStampedReference` | lock |
|---|---|---|---|
| ABA-safe | only via GC/immutability for pointer ABA; **not** value ABA | yes (stamp) | yes |
| Overhead | low | extra `Pair` allocation per update | blocking |
| Use when | swap immutable snapshots, GC handles reuse | value can recur and history matters | complex updates |

### 8.4 `Unsafe` vs `VarHandle` vs field updaters

| | `Unsafe` | `VarHandle` | `AtomicXFieldUpdater` |
|---|---|---|---|
| Supported API | no (internal) | yes (Java 9+) | yes (Java 5+) |
| Memory modes | limited | full spectrum (plain→volatile) | volatile only |
| Type safety | none | yes | yes (reflective) |
| Perf | optimal | optimal (intrinsic) | slightly more overhead |
| Use when | never in app code | modern lock-free code | legacy / pre-9 |

### 8.5 Decision flowchart (text)

```
Need atomic update of shared state?
 ├─ Multiple fields must change together? ──► Lock, or CAS one immutable object holding all fields.
 ├─ Single counter, write-heavy, read-rare? ──► LongAdder / LongAccumulator.
 ├─ Single counter, need exact value or CAS? ──► AtomicLong / AtomicInteger.
 ├─ Single reference swap (snapshot)? ──► AtomicReference (rely on GC+immutability).
 ├─ Reference can recur & history matters (ABA)? ──► AtomicStampedReference.
 ├─ Millions of objects, want to avoid wrapper? ──► VarHandle on a volatile field.
 └─ Building a custom lock-free structure? ──► VarHandle + jcstress tests; consider backoff/elimination.
```

---

## 9. Failure modes & debugging

### 9.1 Lost updates (the classic)

**Symptom:** a counter undercounts under load.
**Cause:** non-atomic `x++` on a shared field, even if `volatile`.
**Diagnose:** code review; reproduce with many threads; the deficit grows with contention.
**Fix:** `AtomicLong`/`LongAdder`.

### 9.2 Contention collapse / throughput cliff

**Symptom:** adding threads *reduces* throughput; CPU is busy but work isn't getting done.
**Cause:** one hot `AtomicLong`/`AtomicReference` — cache-line ping-pong + growing CAS retries.
**Diagnose:**
- `perf stat -e cache-misses,LLC-load-misses,cycles ./app` shows soaring last-level cache misses.
- async-profiler flamegraph shows time in `getAndAddLong` / `Striped64.longAccumulate` / a CAS loop.
**Fix:** `LongAdder`, sharding the counter, or batching updates (reserve ranges).

### 9.3 False sharing

**Symptom:** two unrelated atomics each slow down when used together; padding one makes both fast.
**Cause:** the two fields landed on the same 64-byte line.
**Diagnose:** **`perf c2c record`** then `perf c2c report` — it names the contended cache lines and the offending source lines (HITM = hit-modified events). This is the definitive tool.
**Fix:** `@Contended` (with `-XX:-RestrictContended`), reorder fields, or pad.

### 9.4 ABA corruption

**Symptom:** intermittent data-structure corruption (lost nodes, cycles, NPEs) in a custom lock-free structure — only under high concurrency, hard to reproduce.
**Cause:** value-recurrence ABA on a CAS, or pointer reuse (rare in pure Java thanks to GC, common when interoping with off-heap/`Unsafe` memory).
**Diagnose:** stress test with `jcstress`; add stamps temporarily and see if corruption disappears.
**Fix:** `AtomicStampedReference`, hazard pointers, epoch reclamation, or ensure GC-managed nodes + immutable payloads.

### 9.5 Livelock / starvation under extreme contention

**Symptom:** threads spin endlessly, near-100% CPU, little progress; one thread perpetually loses its CAS.
**Cause:** retry storm with no backoff; a "compute" step that's expensive so each retry wastes a lot.
**Diagnose:** thread dump shows many threads in the CAS loop; high CPU, low throughput.
**Fix:** exponential backoff (`Thread.onSpinWait()` + randomized waits), elimination, or fall back to a lock.

### 9.6 Visibility bug from dropping below volatile

**Symptom:** a flag set by one thread is never seen by another (infinite loop), or a published object is seen partially constructed.
**Cause:** misuse of `lazySet`/`setOpaque`/plain mode where full ordering was actually required.
**Diagnose:** `jcstress` reveals forbidden interleavings; on x86 it may *never* reproduce (TSO hides it) but break on ARM — a notorious "works on my laptop, fails in prod on Graviton" bug.
**Fix:** use the correct (stronger) memory mode; default to volatile unless you've proven weaker is safe.

### 9.7 Real-world incident patterns

- **The single global counter:** a metrics library used one `AtomicLong` for total requests; at high QPS on a 64-core box the counter itself became the bottleneck. Switching to `LongAdder` restored throughput. (This is exactly why `LongAdder` was added in Java 8 and why `ConcurrentHashMap`'s `size`/`mappingCount` uses a `Striped64`-style counter internally.)
- **ARM migration ordering bug:** lock-free code tuned on x86 using plain/opaque access "worked" because x86 TSO is strong; the same code corrupted state on AArch64 (e.g., AWS Graviton) where reordering is permitted. Fixed by using acquire/release modes.
- **False sharing in a thread-local stats array:** per-shard counters in adjacent array slots ping-ponged; padding to one-per-line gave a multi-x speedup.

---

## 10. Interview drill

**Q1. What is compare-and-swap and why is it the basis of lock-free programming?**
Model answer: CAS is a hardware instruction that atomically sets a memory location to a new value only if it currently equals an expected value, returning success/failure. It lets you do read-modify-write without a lock: read, compute, then CAS; if it fails (someone changed the value), retry. It's the basis of lock-free code because a failed CAS implies another thread succeeded, so the system always makes progress.
- *Follow-up: What instruction does it compile to on x86?* `LOCK CMPXCHG`; the `LOCK` prefix makes it atomic via cache locking and acts as a full fence.
- *Follow-up: Is a CAS loop lock-free or wait-free?* Lock-free (some thread progresses each round), not wait-free (an individual thread can retry indefinitely). But `getAndAdd` can compile to wait-free `LOCK XADD`.
- *Follow-up: What's the danger of the compute step?* It can run multiple times, so it must be side-effect-free and idempotent.

**Q2. Difference between atomicity, visibility, and ordering?**
Model answer: Atomicity = indivisible operation (no lost updates). Visibility = one thread's write becomes visible to others. Ordering = the order writes/reads become visible across threads. `volatile` gives visibility + ordering for single reads/writes but not atomicity for `x++`; atomics give all three for RMW.
- *Follow-up: What's happens-before?* A JMM relation: if A happens-before B, A's effects are visible to and ordered before B; volatile writes/reads, lock release/acquire, and successful CAS create these edges.
- *Follow-up: Does `volatile x; x++` lose updates?* Yes — the increment is a non-atomic read-modify-write with a gap.

**Q3. Explain the ABA problem and how to fix it.**
Model answer: A thread reads A, others change A→B→A, the thread's CAS still sees A and succeeds though state changed. Harmless for plain counters; dangerous for reused pointers. Fix with version stamps (`AtomicStampedReference`), hazard pointers, epoch/RCU reclamation, or rely on GC + immutability in Java.
- *Follow-up: Why is the Java Treiber stack ABA-safe without stamps?* GC won't reclaim a node while any thread references it, so the dangerous reuse can't happen.
- *Follow-up: Does GC eliminate ABA entirely?* No — value ABA (0→1→0 on a counter) still occurs; GC only prevents pointer-reuse ABA.

**Q4. Why does `LongAdder` beat `AtomicLong` under contention?**
Model answer: `AtomicLong` funnels all updates to one cache line, causing ping-pong and CAS retries that serialize threads. `LongAdder` (Striped64) spreads updates across multiple `@Contended` cells, each on its own line, so cores update in parallel; `sum()` adds them up. Cost: more memory and a racy, non-atomic `sum()`.
- *Follow-up: When would you NOT use `LongAdder`?* When you need exact reads/CAS, or reads ≫ writes (sum cost).
- *Follow-up: How does Striped64 decide to grow cells?* It starts with `base`; on CAS contention it creates/expands the `Cell[]` (doubling up to ~#CPUs) and rehashes the thread probe to spread collisions.

**Q5. (Senior signal) When would you choose a lock over atomics, and vice versa?**
Model answer: Use atomics for a single variable / single immutable object with short updates and non-blocking progress (counters, flags, snapshot swaps). Use a lock when you must update multiple fields under one invariant, the critical section is long, or correctness is subtle enough that a lock is clearly simpler and the contention is low. Atomics can't atomically combine two variables; an uncontended lock is nearly as cheap as a CAS (it's built on one), so the real divergence is under contention and complexity.
- *Follow-up: Can lock-free be slower than locks?* Yes — under extreme contention without backoff, retry storms can waste more CPU than a well-parked lock.
- *Follow-up: Are Java locks built on CAS?* Yes — AQS uses a `volatile int state` mutated via CAS for the fast path; uncontended `ReentrantLock` is essentially one CAS.

**Q6. (Senior signal) You profile a 64-core service and adding threads reduces throughput on a counter. Walk me through diagnosis and fix.**
Model answer: Suspect contention collapse / false sharing. Confirm with `perf stat` (rising LLC misses) and async-profiler (time in `getAndAddLong`/`Striped64`). Use `perf c2c` to check false sharing. If it's a single hot counter, switch to `LongAdder` or shard it; if it's false sharing between independent counters, apply `@Contended`/padding. Validate with JMH before/after.
- *Follow-up: Why does throughput fall, not just plateau?* Each added thread increases cache-line transfers and CAS retries, so per-operation cost rises with N.
- *Follow-up: How would batching help?* Reserve ranges via `getAndAdd(N)` and consume locally, amortizing the contended op.

**Q7. (Senior signal) Explain `VarHandle` memory-access modes and when you'd use weaker-than-volatile ones.**
Model answer: `VarHandle` exposes plain, opaque, acquire/release, and volatile modes. Use release/acquire for producer/consumer publishing (everything before a release-store is visible after a matching acquire-load) — cheaper than full seq-cst on weak hardware. Use opaque for a polled flag needing eventual visibility but no cross-variable ordering. Only drop below volatile when you can prove the weaker ordering is sufficient; default to volatile. On x86 these are nearly free; on ARM the difference is real and the place where insufficient ordering bugs surface.
- *Follow-up: What does `compareAndExchange` give over `compareAndSet`?* The witness (actual current) value on failure, saving a re-read in the retry loop.
- *Follow-up: Why does `weakCompareAndSet` exist?* On LL/SC CPUs it allows a single attempt that may fail spuriously, cheaper inside a loop that re-reads anyway.

**Q8. How is CAS implemented on ARM vs x86?**
Model answer: x86 has a native `LOCK CMPXCHG` (and `LOCK XADD` for fetch-and-add). Classic ARM has no single CAS; it uses LL/SC (`LDXR`/`STXR`) in a loop — load-exclusive sets a monitor, store-exclusive succeeds only if untouched, else retry. ARMv8.1 LSE added a true `CAS` instruction. LL/SC can fail spuriously, which is why `weakCompareAndSet` and internal retries exist.
- *Follow-up: What's a spurious failure?* `STXR` failing despite the value being unchanged, due to a context switch/interrupt/cache event clearing the exclusive monitor.
- *Follow-up: Why does x86 give acquire/release nearly free?* Its TSO model makes loads acquire and stores release by default; only the store-load fence (volatile store) is expensive.

**Q9. What does `lazySet` do and when is it useful?**
Model answer: `lazySet` is a release-store (`setRelease`): the write becomes visible eventually but skips the expensive store-load fence of a full volatile store, so it's cheaper. Useful for nulling references for GC, or a producer publishing a value a consumer polls, where you don't need immediate global ordering.
- *Follow-up: Risk?* A reader may briefly not see the value; only use where eventual visibility is acceptable.
- *Follow-up: Modern equivalent?* `VarHandle.setRelease`.

**Q10. Build a lock-free stack and argue its correctness.**
Model answer: Treiber stack — `AtomicReference<Node> head`; push CAS-links a new node ahead of the current head, retrying on failure; pop CAS-swings head to `head.next`. It's lock-free: every failed CAS means another op succeeded. In Java it's ABA-safe because GC prevents node reuse while referenced; in manual memory you'd need stamps/hazard pointers.
- *Follow-up: Where can it livelock?* Under extreme contention with no backoff; add `Thread.onSpinWait()` + exponential backoff or an elimination array.
- *Follow-up: How would you test it?* `jcstress` for JMM correctness, JMH for throughput, thread counts ≫ cores.

**Q11. (Senior signal) When is lock-free NOT worth it?**
Model answer: When updates span multiple variables (composition is hard), when contention is low (a lock is just as cheap and simpler), when the structure is complex (correctness risk + maintenance cost), or when extreme contention causes retry storms that a parking lock would handle better. Lock-free pays off for simple hot single-variable updates and for guaranteeing system progress when threads can be descheduled.
- *Follow-up: What progress guarantee do you actually get?* Lock-free (system progress), not wait-free (per-thread), unless you specifically engineered wait-freedom or use `XADD`.
- *Follow-up: Give a case where a lock outperforms CAS.* High contention on a long critical section: parking avoids burning CPU on retries.

**Q12. Difference between `getAndUpdate`, `accumulateAndGet`, `getAndAdd`?**
Model answer: `getAndAdd(delta)` is a fixed arithmetic add (can become wait-free `XADD`). `getAndUpdate(fn)` applies a unary pure function in a CAS loop. `accumulateAndGet(x, fn)` applies a binary `fn(current, x)` in a CAS loop and returns the new value. The `update`/`accumulate` forms are lock-free loops; `getAndAdd` may be a single instruction.
- *Follow-up: Constraints on the functions?* Side-effect-free, idempotent; for accumulators associative+commutative if used in `LongAccumulator`.
- *Follow-up: Which returns old vs new?* `getAnd*` returns old; `*AndGet` returns new.

---

## 11. Glossary

- **ABA problem** — a CAS succeeds because a value returned to its original (A→B→A) though state changed in between.
- **Acquire (memory mode)** — a load that prevents subsequent operations from being reordered before it; pairs with release.
- **AQS (AbstractQueuedSynchronizer)** — JDK framework backing `ReentrantLock` etc., built on a CAS'd `volatile int state` and a lock-free wait queue.
- **Atomicity** — an operation appears indivisible to other threads.
- **Atomic\* classes** — `AtomicInteger/Long/Boolean/Reference`, etc., providing lock-free RMW.
- **Backoff (exponential)** — waiting a randomized, growing interval after a failed CAS to reduce contention.
- **Bus lock** — old x86 mechanism locking the whole memory bus for an atomic op; superseded by cache locking.
- **Cache coherence** — protocol (e.g., MESI) keeping per-core cache copies consistent.
- **Cache line** — unit of cache transfer, typically 64 bytes.
- **CAS (compare-and-swap)** — atomic conditional update primitive.
- **`CMPXCHG`** — x86 compare-and-exchange instruction; `LOCK`-prefixed for atomicity.
- **`compareAndExchange`** — CAS variant returning the witness (current) value instead of a boolean.
- **`@Contended`** — JVM annotation padding a field/class onto its own cache line to prevent false sharing.
- **Context switch** — OS saving one thread's state and loading another's; expensive (µs scale).
- **DRAM / main memory** — slow (~60–100 ns) large memory behind the caches.
- **Elimination array** — a side channel letting complementary ops (push/pop) cancel without touching the contended head.
- **Epoch reclamation / RCU** — defer freeing memory until no reader can hold an old reference.
- **False sharing** — independent variables on one cache line causing coherence ping-pong.
- **Fence / memory barrier** — instruction restricting reordering of memory operations.
- **Fetch-and-add (`XADD`)** — atomic add returning the old value; wait-free on x86.
- **Happens-before** — JMM ordering relation guaranteeing visibility and order.
- **Hazard pointers** — per-thread published pointers protecting nodes from reclamation.
- **Intrinsic (JIT)** — a method the JIT replaces with optimized machine code instead of compiling its bytecode.
- **JIT (Just-In-Time compiler)** — HotSpot's runtime compiler (C1/C2) turning hot bytecode into machine code.
- **JMH (Java Microbenchmark Harness)** — the standard tool for measuring JVM micro-performance.
- **JMM (Java Memory Model)** — spec (JSR-133) defining concurrency visibility/ordering guarantees.
- **jcstress** — OpenJDK harness for stress-testing concurrent code against the JMM.
- **lazySet** — release-store (`setRelease`); cheaper publish without full volatile ordering.
- **LL/SC (Load-Linked/Store-Conditional)** — ARM/PowerPC primitive pair building CAS; can fail spuriously.
- **Lock-free** — guarantees some thread always makes progress (system-wide).
- **`LOCK` prefix** — x86 prefix making an instruction atomic across cores and a full fence.
- **LongAdder / Striped64** — high-contention accumulator spreading updates across striped cells.
- **Lost update** — two RMWs interleave so one update is overwritten.
- **MESI** — cache-coherence states: Modified, Exclusive, Shared, Invalid.
- **Memory hierarchy** — registers → L1 → L2 → L3 → DRAM, increasing size and latency.
- **Opaque (memory mode)** — atomic, per-variable coherent, no cross-variable ordering.
- **Obstruction-free** — progress guaranteed only when running in isolation.
- **`PAUSE` / `Thread.onSpinWait()`** — CPU hint for spin loops, reducing power and memory-order penalties.
- **`perf c2c`** — Linux tool diagnosing cache-to-cache transfers / false sharing.
- **Ping-pong (cache-line)** — a hot line bouncing between cores' caches under contention.
- **Read-modify-write (RMW)** — read a value, compute, write back; non-atomic by default.
- **Release (memory mode)** — a store ensuring prior writes are visible before it; pairs with acquire.
- **RFO (Read-For-Ownership)** — a core requesting exclusive ownership of a cache line to write.
- **Sequential consistency (seq-cst)** — strongest ordering; all seq-cst ops appear in one global order (volatile mode).
- **Spurious failure** — LL/SC store-conditional failing despite unchanged value.
- **Striping (cell)** — spreading updates over multiple memory cells to reduce contention.
- **TSO (Total Store Order)** — x86's relatively strong memory model (loads acquire, stores release by default).
- **Treiber stack** — classic lock-free stack using CAS on the head pointer.
- **`Unsafe`** — internal/unsupported API for low-level memory and atomic ops; superseded by `VarHandle`.
- **`VarHandle`** — Java 9+ typed, safe handle exposing the full memory-access mode spectrum.
- **Visibility** — whether one thread's write can be seen by another.
- **`volatile`** — field modifier giving visibility + ordering (not atomic RMW).
- **Wait-free** — every thread makes progress in a bounded number of its own steps.
- **Witness value** — the actual current value returned by `compareAndExchange`.
- **Word tearing** — a read/write observing a partially-updated multi-word value (not possible for ≤32-bit aligned, possible for non-volatile `long`/`double` historically).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

```
CAS = atomically: if *p==expected then *p=new (return success). Foundation of lock-free.
x86: LOCK CMPXCHG (CAS), LOCK XADD (fetch-and-add, wait-free). LOCK = cache-lock + full fence.
ARM: LL/SC (LDXR/STXR loop) or ARMv8.1 native CAS; STXR can fail spuriously -> weakCompareAndSet.

3 guarantees: ATOMICITY (indivisible) | VISIBILITY (others see write) | ORDERING (reorder limits).
volatile = visibility + ordering, NOT atomic RMW.  Atomics = volatile + atomic RMW.
Successful CAS has full volatile (seq-cst) semantics.

Progress: blocking < obstruction-free < LOCK-FREE (some thread) < WAIT-FREE (every thread, bounded).
CAS loop = lock-free. getAndAdd -> XADD = wait-free on x86.

Toolkit:
  AtomicInteger/Long/Boolean/Reference : get,set,getAndAdd,incrementAndGet,compareAndSet,
                                         getAndUpdate(fn),accumulateAndGet(x,fn),lazySet
  AtomicStampedReference : (ref,stamp) -> defeats ABA.   AtomicMarkableReference : (ref,bool).
  AtomicXFieldUpdater / VarHandle : atomic ops on existing volatile field (no wrapper).
  LongAdder/LongAccumulator (Striped64) : write-hot counters; sum() is RACY snapshot.
  VarHandle modes: plain < opaque < acquire/release < volatile(seq-cst).
                   compareAndExchange returns witness; weakCompareAndSet may fail spuriously.

ABA: A->B->A fools CAS. Fix: stamps / hazard ptrs / epoch-RCU / GC+immutability (Java).
     GC kills POINTER-reuse ABA, not VALUE ABA.

Perf: uncontended CAS ~10-25ns. Contended single AtomicLong -> ping-pong COLLAPSE.
      Hot write-only counter -> LongAdder. Independent counters slow together -> FALSE SHARING -> @Contended.
      Diagnose: perf stat (LLC misses), perf c2c (false sharing), async-profiler (Striped64/CAS frames).

Decisions:
  multi-field invariant -> LOCK (or one immutable CAS'd object)
  write-hot counter      -> LongAdder
  exact value / need CAS -> AtomicLong
  snapshot swap          -> AtomicReference (GC handles reuse)
  recurring value + ABA  -> AtomicStampedReference
  millions of objects    -> VarHandle on volatile field
  custom lock-free        -> VarHandle + jcstress + backoff/elimination

Rules: CAS compute fn must be PURE & idempotent. Don't CAS on LongAdder.sum(). Default to volatile
       mode unless you proved weaker is safe (x86 hides bugs that bite on ARM/Graviton).
```

### 12.2 Self-test (no answers — recall practice)

1. Why can `volatile int x; x++;` still lose updates, and what exactly are the three machine steps that race?
2. Trace, in MESI states, what happens to a hot cache line when two cores each execute `LOCK XADD` on the same `AtomicLong`. Where is the time spent?
3. Construct a concrete ABA scenario that corrupts a Treiber stack in a manual-memory language, then explain precisely why Java's GC prevents it — and give a case where Java is *still* vulnerable to ABA.
4. You have one `AtomicLong` request counter that collapses at 48 threads. Walk through the tools you'd use to confirm it's contention vs false sharing, and the two different fixes those two diagnoses imply.
5. When would you deliberately choose `setRelease`/`getAcquire` over volatile mode, and what bug class are you risking — and on which CPU architectures would that bug actually surface?
6. Explain why `getAndAdd` can be wait-free while `getAndUpdate(fn)` can only be lock-free, down to the instruction the JIT emits for each.
7. Give the decision rule for `AtomicLong` vs `LongAdder` and explain why `LongAdder.sum()` must never be used inside a CAS or equality check.
```
```

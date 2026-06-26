# Synchronization & Locks (Java/JVM)

> A definitive engineering-handbook chapter for senior Java/JVM backend developers. From the mutual-exclusion problem and first principles, through the object monitor, `synchronized`, `wait`/`notify`, `ReentrantLock`, `ReadWriteLock`, `StampedLock`, lock-evolution internals (biased/lightweight/heavyweight), lock elision and coarsening, deadlock avoidance, and production debugging — exhaustively.

---

## 1. Overview & where it fits

### 1.1 What this is

**Synchronization** is the set of mechanisms that coordinate access to shared, mutable state across multiple threads so that the program behaves *as if* operations happened in some sensible order, even though threads run concurrently (interleaved on one core, or truly in parallel across many cores). A **lock** (also called a **mutex**, short for *mutual exclusion*) is the most common synchronization primitive: it is a token that at most one thread can "hold" at a time. While one thread holds the lock, others that want it must wait. This guarantees **mutual exclusion** — only one thread executes the protected region (the **critical section**) at a time.

Two distinct guarantees are bundled into Java's locks, and you must keep them separate in your head:

1. **Mutual exclusion (atomicity of the critical section):** only one thread is inside the guarded region at a time. This prevents *race conditions* — bugs where the result depends on the unpredictable interleaving of threads.
2. **Visibility / memory ordering (happens-before):** when a thread releases a lock and another thread later acquires the *same* lock, everything the first thread wrote before releasing is guaranteed to be visible to the second thread after acquiring. Without this, one thread's writes can sit invisibly in a CPU's store buffer or register and never be seen by another thread, or be seen out of order.

A lock that gave you only #1 without #2 would be almost useless on modern hardware, because CPUs and the JVM aggressively reorder and cache memory operations. The **Java Memory Model (JMM)** — the formal spec (JLS Chapter 17) defining what reads are allowed to see what writes — is what ties locking to visibility.

> **Beginner aside — race condition:** A race condition occurs when two or more threads access shared data and the outcome depends on the timing/order of their execution. The classic example is `count++`, which is *not* one operation; it's read `count`, add 1, write `count` back. Two threads can both read `5`, both compute `6`, both write `6` — and you've lost an increment.

> **Beginner aside — critical section:** the span of code that touches shared mutable state and must not be executed by two threads simultaneously. Locks make critical sections mutually exclusive.

### 1.2 The problem it solves

Without synchronization, concurrent programs suffer from three families of bugs:

- **Atomicity violations (race conditions):** compound operations (check-then-act, read-modify-write) interleave and corrupt invariants. Example: two threads both pass an `if (instance == null)` check and both create a singleton.
- **Visibility failures:** Thread A writes `done = true`; Thread B loops `while (!done) {}` forever because B never sees A's write. The write is legal to keep in a register or core-local cache indefinitely without synchronization.
- **Ordering surprises:** Because of compiler optimizations and CPU out-of-order execution, statements you wrote in one order can appear to execute in another order to other threads. Synchronization establishes *happens-before* edges that forbid the harmful reorderings.

> **Beginner aside — happens-before:** the JMM's core relation. If action X *happens-before* action Y, then X's effects (memory writes) are visible to Y, and X appears to occur before Y. Lock release *happens-before* a subsequent lock acquire on the same lock; a `volatile` write *happens-before* a subsequent read of that same `volatile`; `Thread.start()` happens-before everything the started thread does; everything a thread does happens-before another thread's successful `join()` on it. If two conflicting accesses (at least one a write) are *not* ordered by happens-before, you have a **data race**, and the program's behavior is undefined under the JMM.

### 1.3 When you reach for it

- You have mutable state shared by more than one thread and at least one thread writes it.
- A compound invariant spans multiple fields (e.g., `lowerBound <= upperBound`), so a single `volatile` or `Atomic*` is insufficient — you need a lock to make the multi-field update atomic.
- You need threads to *coordinate* (wait for a condition, hand off work) — `wait`/`notify` or `Condition`.
- You need read/write asymmetry (many readers, few writers) — `ReadWriteLock` / `StampedLock`.

When you *don't* need a lock: immutable data (never changes after construction — inherently thread-safe), thread-confined data (touched by only one thread, e.g., a local variable or a `ThreadLocal`), or single independent variables where an `AtomicInteger`/`AtomicReference` or `volatile` suffices.

### 1.4 The one-paragraph mental model

Think of a lock as a single physical key to a small room (the critical section). Threads that want in queue at the door. The thread holding the key works inside; when it leaves it hands the key to the next thread in line. Crucially, the act of handing over the key also flushes a written "log of everything I changed" to the next holder (the memory-visibility guarantee). Java gives you several key designs: a built-in key welded to every object (`synchronized`/intrinsic monitor, with one waiting room and one "condition" lounge), and several fancier keys in `java.util.concurrent.locks` that you create explicitly — some fair (strict FIFO line), some that let you try-and-give-up, some with multiple condition lounges, some that distinguish "I only want to read" from "I want to write," and one (`StampedLock`) that lets readers proceed without a key at all and validate afterward (optimistic reading).

---

## 2. Foundations from first principles

### 2.1 Threads, shared memory, and why concurrency is hard

A **thread** is an independent path of execution within a process. Threads in the same process share the heap (objects, static fields) but each has its own stack (local variables, call frames) and its own program counter. Because the heap is shared, two threads can touch the same object field at the "same time."

Modern CPUs add three complications:

1. **Caches.** Each core has its own L1/L2 cache. A write by core 0 may live in core 0's cache and not yet be visible to core 1. Hardware *cache coherence* protocols (e.g., MESI) eventually propagate it, but "eventually" is not "now," and the compiler may have hoisted the value into a register so the cache is never even consulted.
2. **Store buffers & out-of-order execution.** A core may buffer writes and execute independent instructions out of program order for speed. The *appearance* of order to other cores requires memory barriers.
3. **Compiler/JIT reordering.** The JIT compiler reorders, hoists, eliminates, and merges memory operations whenever doing so is invisible to a *single* thread. It does not, by default, consider other threads.

> **Beginner aside — MESI / cache coherence:** MESI (Modified, Exclusive, Shared, Invalid) is a common cache-coherence protocol. Each cache line is tagged with one of those states so cores agree on who has the latest copy. Coherence guarantees that *if* you read a memory location you'll eventually get the latest written value — but it says nothing about *ordering* across multiple locations, which is what memory barriers and the JMM govern.

> **Beginner aside — memory barrier (fence):** a CPU/JIT instruction that prevents certain reorderings and forces buffered writes to become visible. Locks and `volatile` are compiled down to barriers. Common kinds: `LoadLoad`, `LoadStore`, `StoreStore`, `StoreLoad` (the most expensive). A lock release emits a release barrier (roughly StoreStore+LoadStore so prior writes are published); a lock acquire emits an acquire barrier (roughly LoadLoad+LoadStore so subsequent reads see published writes).

### 2.2 The mutual-exclusion problem, formally

We want a protocol so that two or more threads can enter a critical section but never simultaneously. A correct mutual-exclusion solution must provide:

- **Mutual exclusion:** at most one thread in the critical section at a time.
- **Progress (deadlock-freedom):** if no one is in the critical section and someone wants in, the choice of who enters cannot be postponed indefinitely; the system as a whole makes progress.
- **Bounded waiting (starvation-freedom, stronger):** a thread that wants in will eventually get in; it cannot be overtaken unboundedly.

Historically, software-only solutions exist — **Peterson's algorithm** (two threads, using two flags and a turn variable) and **Lamport's bakery algorithm** (n threads, each takes a numbered ticket). They are correct *in theory* but rely on sequential consistency that real hardware doesn't provide without barriers, and they busy-wait. In practice we use hardware atomic instructions.

> **Beginner aside — Peterson's algorithm:** a classic two-thread mutual-exclusion algorithm using shared boolean `flag[2]` ("I want in") and an int `turn` ("it's your turn"). A thread sets its flag, sets turn to the other, then spins while the other wants in and it's the other's turn. Elegant, but needs memory fences to work on real CPUs and doesn't scale past two threads cleanly.

> **Beginner aside — busy-waiting / spinning:** repeatedly checking a condition in a tight loop (`while (!canEnter()) {}`) instead of sleeping. Cheap if the wait is microseconds (no context-switch cost); wasteful and CPU-burning if the wait is long.

### 2.3 Hardware atomics: CAS and the building block of all Java locks

Real locks are built on **atomic read-modify-write instructions** the CPU provides:

- **Compare-And-Swap (CAS):** `CAS(addr, expected, new)` atomically: if `*addr == expected`, set `*addr = new` and return true; else return false. On x86 this is `LOCK CMPXCHG`; on ARM it's a `LDXR/STXR` (load-exclusive/store-exclusive) loop. CAS is the foundation of `java.util.concurrent` — every lock and atomic is built on it, exposed in Java via `sun.misc.Unsafe`/`VarHandle` `compareAndSet`.
- **Fetch-and-add, swap (XCHG):** other atomic primitives, e.g., `getAndAdd`.

> **Beginner aside — CAS (compare-and-swap):** the single most important concurrency primitive. It lets a thread say "change X from old to new, but only if nobody changed it since I last looked." If it fails (someone else changed it), the thread retries. This is **lock-free** progress: no thread can block another by holding a lock, though a thread can spin retrying. CAS suffers the **ABA problem** (value goes A→B→A and CAS wrongly thinks nothing changed); Java addresses it with `AtomicStampedReference` (attach a version stamp).

A spinlock is just: `while (!CAS(lockWord, 0, 1)) { /* spin */ }` to acquire, and `lockWord = 0` to release. Java's high-level locks are far more sophisticated (they park threads instead of spinning forever, manage fairness, handle reentrancy), but CAS on a state word is the kernel of it.

### 2.4 The Java Memory Model in one section

The JMM (JSR-133, folded into JLS 17) defines the *happens-before* relation and tells you the minimum synchronization needed for correctness. Key happens-before edges:

| Edge | Rule |
|---|---|
| Program order | Within a single thread, each action happens-before the next (as written). |
| Monitor lock | An `unlock` (monitor exit) happens-before every subsequent `lock` (monitor enter) on the **same** monitor. |
| Volatile | A write to a `volatile` field happens-before every subsequent read of that field. |
| Thread start | `t.start()` happens-before any action in thread `t`. |
| Thread join | Any action in thread `t` happens-before another thread's successful return from `t.join()`. |
| Final fields | An object's properly constructed `final` fields are visible without synchronization (the JSR-133 final-field guarantee), provided `this` didn't escape during construction. |
| Transitivity | If A hb B and B hb C, then A hb C. |

The practical upshot: **locking gives you both mutual exclusion and the happens-before edge.** Reading a value under the *same* lock that the writer used to write it guarantees you see the latest value. This is why you must guard *both* reads and writes of shared mutable state with the *same* lock — a write under lock and a read outside it is a data race.

> **Beginner aside — volatile:** a field modifier that guarantees (a) reads/writes are atomic even for `long`/`double`, and (b) the happens-before/visibility edge above. It does *not* give mutual exclusion or make `count++` atomic. Use it for flags (`volatile boolean shutdown`) and the double-checked-locking pattern, not for compound updates.

### 2.5 Reentrancy

A lock is **reentrant** if a thread that already holds it can acquire it again without deadlocking; the lock counts how many times the owner has acquired it and only truly releases when the count returns to zero. Java's intrinsic locks (`synchronized`) and `ReentrantLock` are reentrant. This matters because a `synchronized` method can call another `synchronized` method on the same object, or a recursive synchronized method, without self-deadlock.

```java
public class Reentrant {
    public synchronized void outer() {
        inner();              // re-acquires the same monitor — fine because reentrant
    }
    public synchronized void inner() {
        // hold count is now 2; only released after both methods return
    }
}
```

If intrinsic locks were *not* reentrant, `outer()` calling `inner()` would deadlock the thread against itself.

---

## 3. How it works internally

This is the heart of the document. We trace, step by step, what the JVM does under the hood for intrinsic locks and for AQS-based locks.

### 3.1 The object header and the Mark Word

Every Java object on the heap has an **object header**. In HotSpot (the reference JVM) the header has two parts: the **Mark Word** and a **klass pointer** (pointer to the class metadata). On a 64-bit JVM the Mark Word is 64 bits. Its layout is *polymorphic* — the same bits mean different things depending on the object's lock state, encoded in the low **tag bits**.

Conceptual 64-bit Mark Word layouts (HotSpot, pre-JDK 15 biased-locking era; exact bit counts vary by build):

| State | Contents (high → low) | Tag bits (low 2) |
|---|---|---|
| Unlocked (normal) | unused : identity hashcode (31) : unused : age (4) : `0` (biased flag) | `01` |
| Biased | thread ID (54) : epoch (2) : unused : age (4) : `1` (biased) | `01` |
| Lightweight locked | pointer to lock record on the locking thread's stack | `00` |
| Heavyweight (inflated) | pointer to the ObjectMonitor (the "monitor" / "mutex") | `10` |
| GC marked | (used by the garbage collector during marking) | `11` |

> **Beginner aside — object header / Mark Word:** metadata the JVM stores at the front of every object. The Mark Word holds the identity hash code, GC age bits, and — critically for us — the lock state. Because the same 64 bits are reused for the hashcode *or* a thread ID *or* a monitor pointer, computing `System.identityHashCode()` on a biased object forces the JVM to abandon biased locking for it (the hashcode needs those bits).

### 3.2 Lock evolution: biased → lightweight → heavyweight

HotSpot uses a tiered scheme to make uncontended locking cheap and only pay for OS-level blocking when there's real contention. A lock can be **inflated** (promoted to heavyweight) but in classic HotSpot it does not deflate back during normal running (deflation happens at safepoints / certain GC events; idle-monitor deflation was added later).

> **Important version note:** **Biased locking was deprecated and disabled by default in JDK 15 (JEP 374) and is slated for removal.** On JDK 15+ the default path is lightweight → heavyweight. The biased path below is still worth understanding because it explains older systems, microbenchmark artifacts, and why some legacy advice (`-XX:-UseBiasedLocking`, `-XX:BiasedLockingStartupDelay`) exists. Flag anything biased-related as **version-specific (≤ JDK 14 default)**.

**Step-by-step lifecycle of a `synchronized (obj)` acquisition:**

1. **Biased locking (≤ JDK 14 default).** The first thread to lock the object biases it toward itself: the JVM CAS-es that thread's ID into the Mark Word. From then on, *that same thread* re-entering the lock costs essentially nothing — just a check "is the bias mine?" — no atomic instruction at all. The premise: most locks are only ever touched by one thread (e.g., `Vector`, `StringBuffer`, `Hashtable` used single-threaded). The cost appears when *another* thread touches it: a **bias revocation** occurs at a safepoint (expensive — it stops the biased thread to fix up the Mark Word), and the object falls back to lightweight locking.

2. **Lightweight locking (thin lock).** When uncontended but accessed by multiple threads over time (or always, on JDK 15+), the JVM uses a **lock record** on the acquiring thread's stack. To lock: it copies the object's current Mark Word into the on-stack lock record (the "displaced mark word"), then **CAS**es a pointer to that lock record into the object's Mark Word. If the CAS succeeds, the thread owns the lock cheaply (one atomic op, no OS involvement). To unlock: CAS the displaced mark word back. Reentrant lightweight locks push additional lock records (some with null displaced marks) and just count.

3. **Heavyweight locking (inflation).** If the lightweight CAS fails because *another thread already holds it* (real contention), the lock **inflates**: the JVM allocates an **ObjectMonitor** (a C++ object), points the Mark Word at it (tag `10`), and from now on contended threads block at the OS level. The ObjectMonitor contains the owner, a recursion count, and the wait/entry queues described below. Heavyweight locking can involve a brief **adaptive spin** before parking, because parking/unparking a thread costs a syscall and a context switch (microseconds), and if the holder will release very soon, spinning is cheaper.

> **Beginner aside — safepoint:** a point where all application threads are stopped at a known-good state so the JVM can do bookkeeping (GC, bias revocation, deoptimization, stack walks). Reaching a safepoint can take time because each thread must poll the safepoint flag and pause; bias revocations that require global safepoints are part of why biased locking was eventually deemed not worth it.

> **Beginner aside — park/unpark:** `LockSupport.park()` blocks the current thread at the OS level (e.g., via a futex on Linux) until `unpark(thread)` is called or it's interrupted. This is how Java threads sleep without burning CPU. A context switch + syscall is on the order of 1–10 microseconds.

### 3.3 The ObjectMonitor: entry set and wait set

When a lock is heavyweight, the `ObjectMonitor` maintains the queues that implement `synchronized` + `wait`/`notify`:

- **Owner:** the thread currently holding the monitor (or null).
- **Recursions:** reentrancy count.
- **Entry Set (a.k.a. `_EntryList` / `_cxq`):** threads blocked trying to *acquire* the monitor (they failed to enter the critical section). HotSpot actually uses two structures: `_cxq` (a LIFO contention queue threads push onto via CAS) and `_EntryList`; on unlock the owner picks a successor to unpark.
- **Wait Set (`_WaitSet`):** threads that *held* the monitor and then called `wait()`, releasing the monitor and parking until notified.

**Step-by-step: `synchronized` block with `wait`/`notify`:**

```java
synchronized (lock) {           // (A) enter monitor
    while (!condition) {        // (B) ALWAYS loop, never plain if
        lock.wait();            // (C) release monitor, go to Wait Set, park
    }
    // (D) use the resource (condition is true and we hold the monitor)
}                               // (E) exit monitor
```

1. **(A) Enter.** Thread CASes ownership. If contended, it joins the **entry set** and parks. When it wins, it becomes owner.
2. **(B)/(C) wait().** The thread must *already own* the monitor (else `IllegalMonitorStateException`). `wait()` atomically: records the recursion count, **fully releases** the monitor (so others can enter), moves the thread into the **wait set**, and parks it. (It releases *all* reentrant holds, restoring them on wakeup.)
3. **notify()/notifyAll().** Another thread (owning the monitor) calls `notify()` (moves *one* waiting thread from the wait set back to the entry set) or `notifyAll()` (moves *all*). Crucially, the notified thread does **not** run immediately — it must re-acquire the monitor first (which the notifier still holds until it exits the synchronized block).
4. **Reacquire & re-check.** The awakened thread re-enters from the entry set, re-acquires the monitor (restoring its recursion count), then **re-evaluates the `while` condition** — because it may have been woken spuriously, or another thread may have already consumed the condition. This is why `wait()` must always be in a `while` loop, never an `if`.
5. **(E) Exit.** Monitor exit emits the release barrier and hands off to a successor in the entry set.

> **Beginner aside — spurious wakeup:** `wait()` is permitted by the spec to return *without* a corresponding `notify()`. Real OSes (POSIX condition variables) document this. Therefore you must always re-check your condition in a loop after waking.

> **Beginner aside — `notify()` vs `notifyAll()`:** `notify()` wakes one arbitrary waiter; `notifyAll()` wakes them all (who then contend to re-acquire). Use `notifyAll()` unless you can prove all waiters are interchangeable and exactly one needs to wake — otherwise `notify()` can wake the "wrong" waiter and lose a signal (a *missed wakeup* / *lost notification* bug, the source of many production hangs).

### 3.4 AbstractQueuedSynchronizer (AQS): the engine behind explicit locks

`ReentrantLock`, `ReentrantReadWriteLock`, `Semaphore`, `CountDownLatch`, and `Condition` are all built on **`AbstractQueuedSynchronizer` (AQS)** in `java.util.concurrent.locks`. AQS is a framework for building locks/synchronizers around:

- A single **`volatile int state`** word, manipulated only via `getState()`, `setState()`, and `compareAndSetState()` (CAS). Subclasses define what `state` *means* (for `ReentrantLock`: hold count; for `Semaphore`: permits; for `CountDownLatch`: remaining count; for read-write: read count packed in the high 16 bits, write count in the low 16 bits).
- A **CLH-based FIFO wait queue** of nodes (a variant of the Craig–Landin–Hagersten queue lock). Each blocked thread is a node; nodes are linked and threads park/unpark based on their predecessor's state. This queue is what makes AQS scalable and what enables fairness, cancellation, and timeouts.

> **Beginner aside — CLH queue lock:** a queue-based spinlock where each thread spins on a flag in its *predecessor's* node rather than on a single shared variable. This avoids cache-line contention (each thread polls a different cache line) and gives FIFO fairness. AQS uses a modified CLH queue but threads *park* instead of spin.

**Step-by-step: `ReentrantLock.lock()` (non-fair, the default) via AQS:**

1. **Fast path:** CAS `state` from 0 → 1. If it succeeds, set the exclusive owner to the current thread and return immediately. (Non-fair locks *barge*: a freshly arriving thread can grab a just-released lock ahead of long-queued threads — better throughput, possible starvation.)
2. **Reentry:** If `state != 0` but the owner is the current thread, increment `state` by 1 (no CAS needed; only the owner writes it) and return. This implements reentrancy.
3. **Slow path (`acquire`):** Try the fast path once more (`tryAcquire`). If still failing, create a node for the current thread and **enqueue** it at the tail of the CLH queue (via CAS on the tail pointer).
4. **Park loop:** The node checks its predecessor. If the predecessor is the head and `tryAcquire` now succeeds, it takes over as head and returns. Otherwise it sets the predecessor's `waitStatus` to `SIGNAL` (meaning "wake me when you're done") and calls `LockSupport.park()`. It loops on wakeup.
5. **Cancellation/interrupt:** If interrupted (and using `lockInterruptibly`) or timed out (`tryLock(timeout)`), the node marks itself `CANCELLED` and unlinks; AQS skips cancelled nodes when choosing successors.

**`unlock()`:** `tryRelease` decrements `state`; when it reaches 0, clears the owner and, if there is a successor, **unparks** the next non-cancelled node's thread. Releasing emits the JMM release edge (write to the `volatile state`), and the acquiring thread's read of `state` is the matching acquire edge — that's how AQS provides happens-before without `synchronized`.

**Fair mode** changes step 1/2: before acquiring, `hasQueuedPredecessors()` is checked; a thread won't barge ahead of an already-waiting thread. This gives FIFO ordering at the cost of throughput (more context switches, less locality).

### 3.5 `Condition` internals

A `Condition` (from `lock.newCondition()`) is AQS's analog of `wait`/`notify`. Each `Condition` has its **own** wait queue (separate from the lock's entry queue). `await()`: fully releases the lock (saving the hold count), adds the node to the condition queue, parks; on signal, the node is **transferred** from the condition queue to the lock's main acquire queue, where it re-contends for the lock and, on success, restores the hold count and returns. `signal()` moves the first waiter from the condition queue to the acquire queue; `signalAll()` moves them all. The key advantage over `wait`/`notify`: **one lock can have many conditions**, so you can signal exactly the right group (e.g., "buffer not full" vs "buffer not empty") without spurious cross-wakeups.

---

## 4. The complete toolkit

### 4.1 Intrinsic (built-in) synchronization

| Construct | Purpose | Notes / defaults |
|---|---|---|
| `synchronized` method (instance) | Locks `this` for the method body | Lock object is the instance; reentrant |
| `synchronized` method (static) | Locks the `Class` object | Lock object is `ClassName.class`; reentrant |
| `synchronized (obj) { ... }` block | Locks `obj` for the block | Finer-grained than method-level; reentrant |
| `Object.wait()` / `wait(timeout)` / `wait(ms,ns)` | Release monitor and wait for notify | Must hold the monitor; throws `IllegalMonitorStateException` otherwise; throws `InterruptedException` |
| `Object.notify()` | Wake one waiter | Must hold the monitor; choice of waiter is arbitrary |
| `Object.notifyAll()` | Wake all waiters | Preferred default for correctness |

### 4.2 `java.util.concurrent.locks` — the `Lock` interface

| Method | Purpose | Blocking? | Interruptible? | Returns |
|---|---|---|---|---|
| `lock()` | Acquire, wait indefinitely | Yes | No (uninterruptible) | void |
| `lockInterruptibly()` | Acquire, but abort if interrupted while waiting | Yes | Yes (throws `InterruptedException`) | void |
| `tryLock()` | Acquire only if free *right now* | No | n/a | `boolean` |
| `tryLock(time, unit)` | Acquire within a timeout | Yes (bounded) | Yes | `boolean` |
| `unlock()` | Release one hold | No | n/a | void |
| `newCondition()` | Create a `Condition` bound to this lock | n/a | n/a | `Condition` |

### 4.3 `ReentrantLock`

| Aspect | Detail |
|---|---|
| Constructor | `new ReentrantLock()` (non-fair, default) or `new ReentrantLock(boolean fair)` |
| Fairness default | **Non-fair** (barging allowed; higher throughput) |
| Reentrancy | Yes; up to 2,147,483,647 holds (Integer.MAX) then throws `Error` |
| Introspection | `getHoldCount()`, `isHeldByCurrentThread()`, `isLocked()`, `getQueueLength()`, `hasQueuedThreads()`, `getOwner()` (protected) |
| Idiom | Always `lock()` *before* `try`, `unlock()` in `finally` |

### 4.4 `ReadWriteLock` / `ReentrantReadWriteLock`

| Aspect | Detail |
|---|---|
| Two locks | `readLock()` (shared) and `writeLock()` (exclusive) |
| Concurrency | Many readers OR one writer; never both |
| Fairness | `new ReentrantReadWriteLock(boolean fair)`; non-fair default |
| Reentrancy | Yes |
| **Lock downgrading** | Allowed: acquire write, then acquire read, then release write — you keep the read lock |
| **Lock upgrading** | **Not allowed** (read → write) — deadlocks; you must release read first |
| Condition support | Only the write lock supports `newCondition()`; the read lock does not |

### 4.5 `StampedLock` (Java 8+)

| Method | Purpose | Returns |
|---|---|---|
| `writeLock()` | Exclusive lock | `long stamp` (0 if fail variants) |
| `readLock()` | Shared (pessimistic) read lock | `long stamp` |
| `tryOptimisticRead()` | **Optimistic** read — no lock acquired | `long stamp` (0 if a write lock is held) |
| `validate(stamp)` | Check no write occurred since the stamp | `boolean` |
| `unlockWrite(stamp)` / `unlockRead(stamp)` / `unlock(stamp)` | Release using the stamp | void |
| `tryConvertToWriteLock(stamp)` | Attempt upgrade | `long` (0 on failure) |
| `tryConvertToReadLock` / `tryConvertToOptimisticRead` | Other conversions | `long` |
| Caveats | **Not reentrant**, **no `Condition`s**, stamps must be passed back to unlock, not directly `Lock`-interface |

### 4.6 `Condition`

| Method | Purpose |
|---|---|
| `await()` | Release lock, wait until signalled/interrupted |
| `await(time, unit)` / `awaitNanos` / `awaitUntil(Date)` | Timed waits |
| `awaitUninterruptibly()` | Wait without responding to interrupt |
| `signal()` | Wake one waiter on this condition |
| `signalAll()` | Wake all waiters on this condition |

### 4.7 `LockSupport` (low-level)

| Method | Purpose |
|---|---|
| `park()` / `parkNanos(ns)` / `parkUntil(deadline)` | Block the current thread |
| `park(blocker)` variants | Same, with a "blocker" object recorded for diagnostics |
| `unpark(Thread)` | Permit a parked (or to-be-parked) thread to proceed |
| Semantics | Permit-based: `unpark` before `park` makes the next `park` return immediately (one permit max) |

### 4.8 Relevant JVM flags (HotSpot)

| Flag | Effect | Default / version note |
|---|---|---|
| `-XX:+UseBiasedLocking` / `-XX:-UseBiasedLocking` | Enable/disable biased locking | **On ≤ JDK 14, off/deprecated JDK 15+** |
| `-XX:BiasedLockingStartupDelay=N` | Delay (ms) before biasing kicks in at startup | 4000ms default (≤14); avoids early revocations |
| `-XX:+DoEscapeAnalysis` | Enables escape analysis → lock elision/scalar replacement | On by default |
| `-XX:+EliminateLocks` | Lock elision + coarsening | On by default (requires escape analysis) |
| `-XX:+PrintFlagsFinal` | Dump all flags and their resolved values | diagnostic |
| `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` | See JIT decisions | diagnostic |

---

## 5. Code examples by use case

### 5.1 Thread-safe counter — `synchronized` vs `ReentrantLock` vs `AtomicLong`

```java
// (1) synchronized — simplest correct version
public final class SyncCounter {
    private long count;                       // guarded by 'this'
    public synchronized void increment() { count++; }   // atomic read-mod-write
    public synchronized long get() { return count; }    // read under SAME lock for visibility
}

// (2) ReentrantLock — equivalent, but explicit (note try/finally)
import java.util.concurrent.locks.ReentrantLock;
public final class LockCounter {
    private final ReentrantLock lock = new ReentrantLock();
    private long count;
    public void increment() {
        lock.lock();                          // acquire BEFORE the try
        try { count++; }
        finally { lock.unlock(); }            // ALWAYS release, even on exception
    }
    public long get() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }
}

// (3) AtomicLong — no lock at all; CAS-based, best for a single independent variable
import java.util.concurrent.atomic.AtomicLong;
public final class AtomicCounter {
    private final AtomicLong count = new AtomicLong();
    public void increment() { count.incrementAndGet(); }  // lock-free CAS loop inside
    public long get() { return count.get(); }
}
// Under heavy contention prefer java.util.concurrent.atomic.LongAdder, which
// stripes counters across cells to reduce CAS contention, summing on read.
```

**Takeaway:** for a single counter, prefer `AtomicLong`/`LongAdder` — no blocking. Reach for a lock when *multiple* fields must update atomically together.

### 5.2 Compound invariant that needs a lock (not just an atomic)

```java
// A numeric range with the invariant lower <= upper. Two atomics CANNOT enforce
// this jointly: between updating one and the other, the invariant can be violated.
import java.util.concurrent.locks.ReentrantLock;
public final class NumberRange {
    private double lower, upper;               // both guarded by 'lock'
    private final ReentrantLock lock = new ReentrantLock();

    public void setLower(double v) {
        lock.lock();
        try {
            if (v > upper) throw new IllegalArgumentException("lower > upper");
            lower = v;                         // single atomic critical section
        } finally { lock.unlock(); }
    }
    public void setUpper(double v) {
        lock.lock();
        try {
            if (v < lower) throw new IllegalArgumentException("upper < lower");
            upper = v;
        } finally { lock.unlock(); }
    }
    public boolean contains(double x) {
        lock.lock();
        try { return x >= lower && x <= upper; } // read both fields atomically
        finally { lock.unlock(); }
    }
}
```

### 5.3 Bounded buffer with `wait`/`notifyAll` (intrinsic)

```java
// Classic producer/consumer. Single monitor; must use notifyAll + while loops.
public final class IntrinsicBoundedBuffer<E> {
    private final Object[] items;
    private int head, tail, count;
    public IntrinsicBoundedBuffer(int capacity) { items = new Object[capacity]; }

    public synchronized void put(E x) throws InterruptedException {
        while (count == items.length) wait();     // wait while FULL (loop, not if)
        items[tail] = x;
        tail = (tail + 1) % items.length;
        count++;
        notifyAll();                              // wake potential consumers (and producers)
    }
    @SuppressWarnings("unchecked")
    public synchronized E take() throws InterruptedException {
        while (count == 0) wait();                // wait while EMPTY
        E x = (E) items[head];
        items[head] = null;                       // avoid loitering (let GC reclaim)
        head = (head + 1) % items.length;
        count--;
        notifyAll();                              // wake potential producers (and consumers)
        return x;
    }
}
```

**Why `notifyAll` not `notify`:** with a single monitor, producers and consumers wait on the *same* condition. `notify()` might wake another producer when only a consumer can make progress — a lost-wakeup deadlock. `notifyAll()` is safe but wakes everyone (a "thundering herd").

### 5.4 Bounded buffer with two `Condition`s (the better way)

```java
// Same buffer using ReentrantLock + TWO conditions: precise signalling, no herd.
import java.util.concurrent.locks.*;
public final class ConditionBoundedBuffer<E> {
    private final Object[] items;
    private int head, tail, count;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();   // producers wait here
    private final Condition notEmpty = lock.newCondition();   // consumers wait here

    public ConditionBoundedBuffer(int capacity) { items = new Object[capacity]; }

    public void put(E x) throws InterruptedException {
        lock.lock();
        try {
            while (count == items.length) notFull.await();    // wait until not full
            items[tail] = x;
            tail = (tail + 1) % items.length;
            count++;
            notEmpty.signal();                                // wake exactly ONE consumer
        } finally { lock.unlock(); }
    }
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) notEmpty.await();              // wait until not empty
            E x = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            notFull.signal();                                 // wake exactly ONE producer
            return x;
        } finally { lock.unlock(); }
    }
}
// In real code, prefer java.util.concurrent.ArrayBlockingQueue / LinkedBlockingQueue,
// which implement exactly this. Hand-rolling is for learning or special needs.
```

### 5.5 `ReadWriteLock` for a read-heavy cache, with downgrading

```java
import java.util.*;
import java.util.concurrent.locks.*;
public final class RwLockCache<K, V> {
    private final Map<K, V> map = new HashMap<>();
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final Lock r = rw.readLock();
    private final Lock w = rw.writeLock();

    public V get(K key) {
        r.lock();                              // many readers run concurrently
        try { return map.get(key); }
        finally { r.unlock(); }
    }
    public void put(K key, V val) {
        w.lock();                              // exclusive: blocks all readers & writers
        try { map.put(key, val); }
        finally { w.unlock(); }
    }
    // Compute-if-absent with DOWNGRADING (write -> read), avoiding a window where
    // another writer could sneak in between releasing write and reading.
    public V computeIfAbsent(K key, java.util.function.Function<K, V> fn) {
        r.lock();
        try {
            V v = map.get(key);
            if (v != null) return v;
        } finally { r.unlock(); }             // must drop read before taking write (no upgrade!)
        w.lock();
        try {
            V v = map.get(key);               // re-check under write lock
            if (v == null) { v = fn.apply(key); map.put(key, v); }
            r.lock();                         // DOWNGRADE: acquire read before releasing write
            return v;
        } finally {
            w.unlock();                       // now holding only the read lock
            r.unlock();
        }
    }
}
// NOTE: For a concurrent map you'd normally just use ConcurrentHashMap. This shows
// the RW-lock pattern, including the legal downgrade and the illegal upgrade pitfall.
```

### 5.6 `StampedLock` optimistic read (the canonical example: a 2D point)

```java
import java.util.concurrent.locks.StampedLock;
public final class Point {
    private double x, y;
    private final StampedLock sl = new StampedLock();

    public void move(double dx, double dy) {            // writer
        long stamp = sl.writeLock();                    // exclusive
        try { x += dx; y += dy; }
        finally { sl.unlockWrite(stamp); }
    }

    public double distanceFromOrigin() {                // OPTIMISTIC reader
        long stamp = sl.tryOptimisticRead();            // no lock; just a stamp
        double cx = x, cy = y;                          // read fields into locals
        if (!sl.validate(stamp)) {                      // was there a write meanwhile?
            stamp = sl.readLock();                      // fall back to a real read lock
            try { cx = x; cy = y; }
            finally { sl.unlockRead(stamp); }
        }
        return Math.sqrt(cx * cx + cy * cy);            // compute outside the lock
    }
}
// Optimistic reads are the fastest path when writes are rare: no CAS, no cache-line
// write, no contention. validate() is essentially a volatile read of the lock state.
// You MUST copy fields to locals BEFORE validate(), and you must NOT dereference
// possibly-inconsistent data (e.g., follow a half-updated pointer) in the optimistic block.
```

### 5.7 `tryLock` to avoid deadlock via lock ordering / backoff

```java
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
// Transfer between two accounts. Two threads transferring in opposite directions
// can deadlock if each grabs one lock and waits for the other. Two defenses shown.
public final class Account {
    final ReentrantLock lock = new ReentrantLock();
    long balance;
    Account(long b) { balance = b; }
}

class Bank {
    // Defense 1: consistent global lock ORDER (by identity hash) -> no cycle possible.
    void transferOrdered(Account a, Account b, long amt) {
        Account first  = System.identityHashCode(a) <= System.identityHashCode(b) ? a : b;
        Account second = (first == a) ? b : a;
        first.lock.lock();
        try {
            second.lock.lock();
            try { a.balance -= amt; b.balance += amt; }
            finally { second.lock.unlock(); }
        } finally { first.lock.unlock(); }
    }

    // Defense 2: tryLock with backoff -> break potential deadlock by giving up & retrying.
    boolean transferTry(Account a, Account b, long amt) throws InterruptedException {
        while (true) {
            if (a.lock.tryLock()) {
                try {
                    if (b.lock.tryLock()) {
                        try { a.balance -= amt; b.balance += amt; return true; }
                        finally { b.lock.unlock(); }
                    }
                } finally { a.lock.unlock(); }
            }
            // couldn't get both — back off a random short time and retry (avoid livelock)
            TimeUnit.NANOSECONDS.sleep(ThreadLocalRandom.current().nextInt(1000));
        }
    }
}
```

### 5.8 Correct double-checked locking (DCL) for lazy initialization

```java
// DCL is the canonical place 'volatile' is non-negotiable: without it, another thread
// can see a non-null 'instance' that is not yet fully constructed (reordering of the
// write to 'instance' before the object's fields are published).
public final class Singleton {
    private static volatile Singleton instance;     // MUST be volatile
    private Singleton() { /* expensive init */ }
    public static Singleton get() {
        Singleton local = instance;                 // read volatile once into a local
        if (local == null) {                        // first check (no lock, fast path)
            synchronized (Singleton.class) {
                local = instance;
                if (local == null) {                // second check (under lock)
                    local = new Singleton();
                    instance = local;               // volatile write publishes safely
                }
            }
        }
        return local;
    }
}
// Often simpler/clearer alternatives: the holder idiom (a static nested class loaded
// lazily by the classloader, which is itself thread-safe), or an enum singleton.
```

### 5.9 `lockInterruptibly` for a cancellable acquire

```java
import java.util.concurrent.locks.ReentrantLock;
public final class CancellableWork {
    private final ReentrantLock lock = new ReentrantLock();
    public void doWork() throws InterruptedException {
        lock.lockInterruptibly();          // if interrupted while WAITING, throw and bail
        try {
            // ... long critical section; the thread can be cancelled before acquiring
        } finally { lock.unlock(); }
    }
}
// Contrast: plain lock() ignores interrupts while waiting (the interrupt status is
// preserved but you keep blocking). Use lockInterruptibly() in tasks that must respond
// to cancellation/shutdown.
```

---

## 6. Implementation concerns & best practices

### 6.1 Correctness rules (non-negotiable)

1. **Guard every access (read *and* write) of shared mutable state with the *same* lock.** A write under lock + a read outside it is a data race; the read may never see the write.
2. **Document the locking policy.** State, per field, *which* lock guards it (Javadoc `@GuardedBy("lock")` from JCIP / JSR-305 / Error Prone). The compiler can check it with Error Prone's `@GuardedBy`.
3. **Always release in `finally`.** `lock(); try { ... } finally { unlock(); }`. For `synchronized` the JVM auto-releases on any exit (including exceptions) — one of its safety advantages.
4. **`wait()`/`await()` always in a `while` loop**, never `if` — spurious wakeups and stolen conditions.
5. **Prefer `notifyAll`/`signalAll`** unless you can prove all waiters are interchangeable; for distinct conditions, use separate `Condition` objects and `signal()`.
6. **Don't hold a lock during a blocking or long operation** (I/O, network call, calling alien/unknown code). Holding a lock across a callback that you don't control can deadlock (re-entrant call into your locked code) or stall everyone.
7. **Never lock on a value that may be interned/shared** (`String` literals, boxed `Integer` from autoboxing cache, `Boolean`) — distant code may lock the same instance. Use a `private final Object lock = new Object();`.
8. **Don't lock on a field you reassign** — locking on `this.foo` and then changing `foo` means subsequent locks use a different monitor. Use a `final` lock field.

### 6.2 Performance

- **Uncontended locks are cheap.** A lightweight lock is ~a single CAS; modern JVMs make uncontended `synchronized` and `ReentrantLock` roughly comparable. Contention is the expense — that's when threads park (syscall + context switch, microseconds) and serialize.
- **Reduce lock scope** (hold the lock for the *minimum* code) and **reduce lock duration**; do expensive computation *outside* the lock when possible (compute, then briefly lock to publish).
- **Reduce contention via:** lock *splitting* (separate locks for independent state), lock *striping* (an array of locks keyed by hash, as `ConcurrentHashMap` historically did with segments), or replacing locks with `Atomic*`/`LongAdder`/`ConcurrentHashMap`/immutable+copy-on-write.
- **`synchronized` vs `ReentrantLock`:** prefer `synchronized` for simplicity (auto-release, JVM-optimized, shows in thread dumps clearly). Use `ReentrantLock` only when you need: `tryLock`, timed/interruptible acquire, fairness, or multiple `Condition`s.
- **Fairness is expensive.** A fair `ReentrantLock` can be an order of magnitude slower in throughput than non-fair under contention because it forfeits barging and locality. Default to non-fair; choose fair only to bound latency/avoid starvation when measured.
- **`ReadWriteLock` only wins when reads truly dominate and are non-trivial.** Its bookkeeping is heavier than a plain lock; for short critical sections the read/write distinction can be net-negative. Measure.
- **`StampedLock` optimistic reads** are the fastest read path when writes are rare, but are tricky (non-reentrant, easy to misuse). Use for hot read paths after profiling.
- **False sharing:** two independent variables on the same 64-byte cache line cause cores to invalidate each other's caches even with no real contention. Mitigate with `@Contended` (JDK, needs `-XX:-RestrictContended`) or padding. Relevant when striping locks/counters.

### 6.3 Memory & resources

- Inflated monitors allocate native `ObjectMonitor` structures; a process with millions of contended objects can accumulate monitor memory. JDK improved this with concurrent monitor deflation (JDK 14+ via `MonitorDeflationInterval`).
- `wait()`/`Condition.await()` parked threads consume a thread each (stack ~512KB–1MB default). Thousands of blocked threads = gigabytes of stacks; prefer bounded pools or async/`virtual threads` (JDK 21+) for high fan-out.
- Avoid object **loitering**: null out array slots in buffers (as in 5.3/5.4) so released elements can be GC'd.

### 6.4 Virtual threads (JDK 21+) and `synchronized` pinning — important

> **Beginner aside — virtual thread:** a lightweight thread (Project Loom, JDK 21) scheduled by the JVM onto a small pool of OS "carrier" threads. Millions can exist. When a virtual thread blocks (e.g., on I/O), it *unmounts* from its carrier, freeing it for other virtual threads.

A critical interaction: in **JDK 21–22, a virtual thread that blocks *inside a `synchronized` block/method* **pins** its carrier** (cannot unmount), which can starve the carrier pool and cause throughput collapse or even deadlock under load. `ReentrantLock` does **not** pin. Guidance for JDK 21/22: replace hot `synchronized` regions that block (especially around I/O) with `ReentrantLock`. Diagnose with `-Djdk.tracePinnedThreads=full`. **JDK 24 (JEP 491) removed this pinning** for most `synchronized` cases, making `synchronized` virtual-thread-friendly again — so this is **version-specific**: it's a real concern on 21–23, largely resolved on 24+.

### 6.5 Observability

- **Thread dumps** (`jstack <pid>`, `jcmd <pid> Thread.print`, `kill -3`) show `BLOCKED` threads, the monitor they're waiting on, and **automatic deadlock detection** ("Found one Java-level deadlock"). `synchronized` shows as "- waiting to lock <0x...> (a com.x.Foo)"; AQS locks show as `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject`/`...NonfairSync` parked at `LockSupport.park`.
- **JFR (Java Flight Recorder)** events: `jdk.JavaMonitorEnter` (contended monitor enter, with duration and the monitor class), `jdk.JavaMonitorWait`, `jdk.ThreadPark`. Record with `-XX:StartFlightRecording` or `jcmd JFR.start`, analyze in JDK Mission Control. This is the best way to find *which* lock is hot in production with low overhead.
- **`ReentrantLock` introspection:** `getQueueLength()`, `isLocked()`, `getHoldCount()` for live metrics/health endpoints.
- **`-XX:+PrintConcurrentLocks`** in thread dumps lists `java.util.concurrent` locks held by each thread (helps with AQS-lock deadlocks that the basic detector may miss when they involve `Condition`/`Lock`).

### 6.6 Testing

- **Determinism is hard.** Stress tests + many iterations + many threads + randomized timing. Use `CountDownLatch` to release all threads simultaneously (maximize interleaving), and `CyclicBarrier` to coordinate rounds.
- **jcstress** (the OpenJDK Java Concurrency Stress tool) is the gold standard for verifying JMM-level correctness — it explores interleavings and reports illegal outcomes. Use it for lock-free/`volatile`/DCL-style code.
- **Static analysis:** Error Prone `@GuardedBy`, SpotBugs (`IS2_INCONSISTENT_SYNC`, `LI_LAZY_INIT_STATIC`, `WA_NOT_IN_LOOP`, `NN_NAKED_NOTIFY`), and FindBugs/IntelliJ inspections catch common mistakes (synchronizing inconsistently, naked notify, `wait` not in loop).
- **Thread-safety annotations** (`@ThreadSafe`, `@NotThreadSafe`, `@Immutable` from JCIP) document intent.

### 6.7 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Locking on `this` and exposing `this` | Outside code can lock your monitor → deadlock/contention | Private `final Object lock` |
| Locking on `String`/`Integer`/`Boolean` | Interned/cached instances shared across the JVM | Dedicated lock object |
| `wait`/`await` in an `if` | Spurious wakeup / stolen condition | Use `while` |
| Naked `notify()` with mixed waiters | Lost wakeup / hang | `notifyAll` or separate `Condition`s |
| `unlock()` not in `finally` | Lock leaked on exception → permanent deadlock | `try/finally` |
| Holding a lock during I/O / alien calls | Stalls or re-entrant deadlock | Copy state out, release, then call |
| Read-lock upgrade to write-lock | Deadlocks (RRWL doesn't allow it) | Release read, take write, re-check |
| Nested locks acquired in inconsistent order | Deadlock | Global lock ordering or `tryLock` |
| Double-checked locking without `volatile` | Publishes partially-constructed object | `volatile` field or holder idiom |
| Over-synchronizing (one giant lock) | Serializes everything; no scalability | Split/stripe; finer granularity |

---

## 7. Advanced topics & deep internals

### 7.1 Lock granularity: coarse vs fine

- **Coarse-grained:** one lock protects a large region/whole data structure. Simple, easy to reason about, but limits concurrency (a single bottleneck).
- **Fine-grained:** many locks protect small independent parts (e.g., per-bucket locks in a hash map, per-node locks in a linked list "hand-over-hand"/lock coupling). Higher concurrency, but more complex, more overhead, and far more deadlock-prone.
- **Lock splitting:** when one lock guards two *independent* invariants, split it into two locks. Example: a class tracking independent `connectionCount` and `requestCount` shouldn't serialize them under one lock.
- **Lock striping:** generalize splitting to a fixed array of N locks, selecting by `key.hashCode() % N`. Trades exact granularity for bounded lock-object overhead. `ConcurrentHashMap` (pre-Java 8) used 16 segments by default; Java 8+ switched to per-bin CAS + `synchronized` on the first node of a bin.

> **Beginner aside — hand-over-hand (lock coupling):** traversing a linked structure by holding the current node's lock, acquiring the next node's lock, *then* releasing the current — so you "walk" your locks along the list, allowing other threads to operate on parts you've passed. Enables concurrent traversal/modification without one global lock.

### 7.2 Biased / lightweight / heavyweight — deeper

- **Bias revocation** is the hidden cost: it requires a safepoint (global or, in later HotSpot, a handshake) to walk the biased thread's stack and convert the lock. A workload that biases then frequently hands objects to other threads (e.g., objects produced by one thread and consumed by another) hits *bulk rebiasing/revocation* — HotSpot tracks an **epoch** per class to bulk-rebias rather than revoke each object individually. The accumulation of revocation safepoints in such workloads is a major reason **JEP 374 (JDK 15)** disabled biased locking: the maintenance complexity and revocation costs outweighed gains as the typical workload shifted away from single-threaded legacy collections.
- **Adaptive spinning:** before a contended thread parks on an inflated monitor, HotSpot may spin for a duration tuned by recent success rates (it learns whether spinning tends to pay off for that monitor). This blends spinlock (good for very short holds) with blocking (good for long holds).
- **Monitor inflation triggers:** contention on the lightweight CAS, *or* calling `wait()`/`hashCode()` on the object (the latter because identity hashcode needs the Mark Word bits, forcing the lock state out of biased/lightweight form).

### 7.3 Lock elision, coarsening, and roach motel ordering (JIT)

- **Lock elision:** if escape analysis proves a lock object never escapes a thread (e.g., a `StringBuffer` local to one method), the JIT removes the locking entirely — the lock can't be contended because no other thread can see the object. Controlled by `-XX:+EliminateLocks` + `-XX:+DoEscapeAnalysis` (on by default).

> **Beginner aside — escape analysis:** a JIT optimization that determines whether an object "escapes" the method/thread that created it. If it doesn't escape, the JIT can allocate it on the stack (scalar replacement), elide its locks, and avoid heap churn.

- **Lock coarsening:** if the JIT sees repeated lock/unlock on the *same* lock in adjacent code (e.g., a loop appending to a `StringBuffer`), it may merge them into one larger critical section to amortize the lock overhead — fewer enter/exit operations.
- **"Roach motel" semantics:** the JMM allows the JIT/CPU to move memory operations *into* a critical section (across the acquire downward or across the release upward) but not *out* of it. So code can sink into the locked region but never leak out — preserving correctness while allowing optimization. The mnemonic: "instructions can check in but they can't check out."

### 7.4 `StampedLock` edge cases

- **Not reentrant:** a thread re-acquiring its own write lock deadlocks. Never call a method that re-locks while holding a `StampedLock`.
- **No `Condition` support:** can't replace `wait`/`notify`.
- **Interrupt behavior:** by default `StampedLock`'s blocking methods are *not* responsive to interrupts in the same way; there are `readLockInterruptibly`/`writeLockInterruptibly` variants.
- **Stamp invalidation:** every successful `writeLock` returns a *new* stamp; you must unlock with the exact stamp returned. Mixing stamps throws or corrupts state.
- **Optimistic read pitfalls:** never dereference a reference field read optimistically without validating first, and never perform side effects (I/O, mutation) in the optimistic block — it may run on inconsistent data. Always copy primitives/refs to locals, `validate()`, and only then use them; on failure, fall back to a real read lock.
- **Writer starvation:** `StampedLock` is non-fair and read-biased on the optimistic path; a constant stream of readers can be fine, but heavy pessimistic readers can starve writers — measure.

### 7.5 AQS: shared mode, conditions, and propagation

AQS supports **exclusive** mode (`ReentrantLock`, write lock) and **shared** mode (`Semaphore`, `CountDownLatch`, read lock). In shared mode, when a thread acquires, it may **propagate** the signal to the next queued node if more permits remain (so multiple readers wake in a cascade). The `tryAcquireShared` return value encodes "how many remaining," driving propagation. This is how a `ReadWriteLock` lets a batch of readers proceed together after a writer releases.

### 7.6 Memory ordering of `volatile` vs lock

A `volatile` write/read provides the same happens-before edge as unlock/lock but **only for that single field's access** and **without mutual exclusion**. A lock provides happens-before for *all* memory plus exclusion. Knowing this lets you use the cheapest sufficient tool: `volatile` flag for a one-shot signal; `Atomic*` for a single CAS-able variable; a lock for multi-field invariants.

### 7.7 Interruption semantics summary

| Operation | On interrupt while waiting |
|---|---|
| `synchronized` entry | Not interruptible; you keep blocking (status preserved) |
| `Object.wait()` | Throws `InterruptedException` |
| `ReentrantLock.lock()` | Not interruptible (status preserved) |
| `ReentrantLock.lockInterruptibly()` | Throws `InterruptedException` |
| `ReentrantLock.tryLock(t, u)` | Throws `InterruptedException` |
| `Condition.await()` | Throws `InterruptedException` |
| `Condition.awaitUninterruptibly()` | Returns normally; status preserved |
| `LockSupport.park()` | Returns (does NOT throw); check `Thread.interrupted()` |

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which primitive?

| Need | Use | Avoid / why not |
|---|---|---|
| Single counter/flag | `AtomicLong`/`LongAdder`/`volatile` | Lock = overkill |
| Multi-field atomic invariant | `synchronized` or `ReentrantLock` | Multiple atomics can't span fields |
| Simple mutual exclusion, no special features | `synchronized` | `ReentrantLock` adds complexity for no gain |
| Need `tryLock`, timeout, interruptible, fairness, or many conditions | `ReentrantLock` (+ `Condition`) | `synchronized` lacks these |
| Read-mostly, non-trivial critical section | `ReentrantReadWriteLock` | Plain lock serializes readers |
| Read-mostly, ultra-hot, rare writes | `StampedLock` (optimistic) | RW-lock still does CAS on reads |
| Producer/consumer queue | `ArrayBlockingQueue`/`LinkedBlockingQueue` | Hand-rolled `wait/notify` is error-prone |
| Bounded resource permits | `Semaphore` | Lock counts only to 1 |
| One-shot "all done" signal | `CountDownLatch` | Reusable? use `CyclicBarrier`/`Phaser` |
| Concurrent map | `ConcurrentHashMap` | RW-lock + HashMap reinvents it worse |

### 8.2 `synchronized` vs `ReentrantLock` (detailed)

| Dimension | `synchronized` | `ReentrantLock` |
|---|---|---|
| Acquire/release | Implicit (block scope), auto-release on exception | Explicit `lock()/unlock()`, manual `finally` |
| Reentrant | Yes | Yes |
| Fairness option | No (JVM-managed) | Yes (`new ReentrantLock(true)`) |
| tryLock / timeout | No | Yes |
| Interruptible acquire | No | Yes (`lockInterruptibly`) |
| Multiple conditions | No (one wait set per monitor) | Yes (many `Condition`s) |
| Thread-dump clarity | Excellent (monitor shown) | Good (`PrintConcurrentLocks`) |
| Virtual-thread pinning (JDK 21–23) | Pins carrier when blocking | Does not pin |
| JIT optimizations | Bias/elision/coarsening | AQS-level, not bias/elision |
| Performance (uncontended) | Excellent | Excellent (comparable) |
| Performance (contended) | Good | Good; fair mode slower |
| Verdict | Default choice | When you need its extra features |

### 8.3 RW-lock vs `StampedLock` vs single lock

| Property | `ReentrantLock` | `ReentrantReadWriteLock` | `StampedLock` |
|---|---|---|---|
| Concurrent readers | No | Yes | Yes (+ optimistic, lock-free reads) |
| Reentrant | Yes | Yes | **No** |
| Conditions | Yes | Write lock only | **No** |
| Fairness option | Yes | Yes | No |
| Upgrade read→write | n/a | No (deadlocks) | `tryConvertToWriteLock` (may fail) |
| Best when | Balanced read/write, simplicity | Reads dominate, non-trivial sections | Reads vastly dominate, hot path, careful code |
| Complexity/risk | Low | Medium | High |

### 8.4 Fair vs non-fair

- **Non-fair (default):** higher throughput, better cache locality (a thread that just released may re-acquire and keep its data hot), risk of *starvation* (a thread might be perpetually overtaken).
- **Fair:** FIFO, bounded waiting, predictable latency tails; significantly lower throughput under contention. **Use when** SLA on tail latency / starvation-freedom matters more than raw throughput, and you've measured the cost.

---

## 9. Failure modes & debugging

### 9.1 Deadlock

Four Coffman conditions must all hold for deadlock: **mutual exclusion**, **hold-and-wait**, **no preemption**, **circular wait**. Break any one — most practically, **circular wait** via consistent global lock ordering, or **hold-and-wait** via `tryLock` with backoff.

**Diagnose:** `jstack <pid>` or `jcmd <pid> Thread.print`. The JVM auto-detects intrinsic-lock deadlocks:

```
Found one Java-level deadlock:
=============================
"Thread-1":
  waiting to lock monitor 0x... (object 0x..., a Account),
  which is held by "Thread-0"
"Thread-0":
  waiting to lock monitor 0x... (object 0x..., a Account),
  which is held by "Thread-1"
```

For `ReentrantLock`/AQS deadlocks, use `jstack` (modern JDKs detect `java.util.concurrent` ownable synchronizers too) and look for threads `parking to wait for <0x...> (a ...Sync)`. `-XX:+PrintConcurrentLocks` lists held j.u.c locks.

### 9.2 Livelock

Threads keep responding to each other and never make progress (e.g., two `tryLock` loops that always release and retry in lockstep). **Fix:** randomized backoff (jitter) before retry — exactly why example 5.7 sleeps a random nanosecond range.

### 9.3 Starvation

A thread never gets the lock (non-fair barging, or a greedy holder). **Diagnose:** thread dumps over time show the same thread perpetually `BLOCKED`; metrics show one consumer never advancing. **Fix:** fairness, reduce hold time, or restructure.

### 9.4 Missed/lost wakeups

A `notify()` fires before the waiter calls `wait()`, or wakes the wrong waiter; the waiter then blocks forever. **Symptom:** a producer/consumer hangs with full/empty buffer. **Diagnose:** thread dump shows threads stuck in `Object.wait` with a state that should have been signalled. **Fix:** condition predicate in a `while` loop checked *under the lock*; `notifyAll`/dedicated `Condition`s; never lose the signal by checking the condition outside the lock.

### 9.5 Data races / visibility hangs

`while (!flag) {}` spins forever because `flag` isn't `volatile`/locked; the JIT hoisted the read. **Symptom:** a worker never sees a shutdown flag; CPU pegged at 100%. **Diagnose:** thread dump shows a `RUNNABLE` thread spinning in your loop; reproduces only with JIT (`-Xint` "fixes" it, confirming a memory-model bug). **Fix:** make the flag `volatile` or access it under the same lock as the writer.

### 9.6 Lock contention / throughput collapse

**Symptom:** CPU underutilized but latency high; many threads `BLOCKED` on one monitor. **Diagnose:** JFR `jdk.JavaMonitorEnter` events ranked by total duration point to the hot lock; `async-profiler --lock` produces a lock-contention flame graph. **Fix:** split/stripe the lock, shrink the critical section, switch to `ConcurrentHashMap`/`LongAdder`, or move work outside the lock.

### 9.7 Virtual-thread pinning (JDK 21–23)

**Symptom:** under load with virtual threads, throughput collapses, carrier pool (default = #CPUs) all stuck. **Diagnose:** `-Djdk.tracePinnedThreads=full` prints stack traces where a virtual thread pinned its carrier inside `synchronized`. **Fix:** swap that `synchronized` for `ReentrantLock`, or upgrade to JDK 24+ (JEP 491).

### 9.8 Real-world incident archetypes

- **The `Hashtable`/`Vector` legacy bottleneck:** a high-QPS service synchronizing every access to a shared `Hashtable`; thread dumps show dozens of threads `BLOCKED` on the same monitor. Fix: `ConcurrentHashMap`.
- **The connection-pool deadlock:** thread A holds pool lock and waits for a connection; thread B holds a connection and waits for the pool lock — circular wait via a resource and a lock. Fix: never hold the lock while waiting for the resource; consistent ordering.
- **The `String.intern()` lock collision:** two unrelated subsystems both `synchronized` on `"CONFIG".intern()` / a string constant, mysteriously contending. Fix: dedicated lock objects.
- **The logging-under-lock stall:** holding a lock while writing to a slow/blocking log appender serialized the entire request path. Fix: log outside the critical section / async appender.

---

## 10. Interview drill

**Q1. What two guarantees does a Java lock provide, and why do you need both?**
*Model answer:* Mutual exclusion (only one thread in the critical section) and memory visibility/ordering via happens-before (an unlock happens-before a subsequent lock on the same monitor, so writes before release are visible after acquire). You need visibility because CPUs/JIT cache and reorder memory; without the happens-before edge, mutual exclusion alone wouldn't guarantee the second thread sees the first's writes.
- *Follow-up: Does `volatile` give mutual exclusion?* No — only visibility/ordering and atomic access for that one field; `count++` on a volatile is still racy.
- *Follow-up: Must you lock reads too?* Yes — a read of shared state must use the same lock as the writer, or it's a data race and may not see the latest value.
- *Follow-up: What's a happens-before edge besides lock/volatile?* `Thread.start`, `Thread.join`, final-field publication, program order, transitivity.

**Q2. Walk me through biased → lightweight → heavyweight lock evolution.**
*Model answer:* (As §3.2.) Biased: first thread CASes its ID into the Mark Word; re-entry is free; another thread triggers a safepoint revocation. Lightweight: a stack lock record + CAS of a pointer into the Mark Word for uncontended multi-thread use. Heavyweight: real contention inflates the lock into a native `ObjectMonitor` with entry/wait sets; threads park at the OS level (with adaptive spinning first).
- *Follow-up: Why was biased locking removed (JDK 15)?* Revocation safepoint costs and maintenance complexity outweighed benefits as workloads moved away from single-threaded legacy collections.
- *Follow-up: What forces a biased/lightweight lock to inflate?* Contention on the CAS, or calling `wait()`/`hashCode()` (needs the Mark Word bits).
- *Follow-up: What's in the Mark Word during heavyweight locking?* A pointer to the `ObjectMonitor` and the `10` tag.

**Q3. Why must `wait()` be in a `while` loop, not an `if`?**
*Model answer:* Because of spurious wakeups (a wait can return with no notify) and stolen conditions (another thread may consume the condition between your wakeup and your re-acquiring the monitor). You must re-check the predicate after waking.
- *Follow-up: When is the monitor held during `wait()`?* You must hold it to call `wait()`; `wait()` releases it while parked and re-acquires it before returning.
- *Follow-up: `notify` vs `notifyAll`?* `notify` wakes one arbitrary waiter (risk of lost wakeup with mixed waiters); `notifyAll` is the safe default; or use separate `Condition`s with `signal`.

**Q4. Explain how `ReentrantLock` works internally.**
*Model answer:* It's an AQS subclass. AQS holds a `volatile int state` (the hold count) and a CLH FIFO queue. `lock()` CASes state 0→1 (fast path), increments for reentry by the owner, otherwise enqueues a node and parks. `unlock()` decrements; at 0 it unparks the next non-cancelled node. The volatile state read/write provides the happens-before edge.
- *Follow-up: Fair vs non-fair difference?* Non-fair allows barging (CAS before checking the queue); fair checks `hasQueuedPredecessors()` first.
- *Follow-up: How does `Condition.await()` work?* Fully releases the lock, moves the node to a per-condition queue, parks; `signal` transfers it back to the main acquire queue.

**Q5. How do you prevent deadlock between two locks?**
*Model answer:* Break a Coffman condition — most practically circular wait, by imposing a global lock-acquisition order (e.g., by `System.identityHashCode`, or a tie-breaker lock when hashes collide). Alternatively use `tryLock` with randomized backoff to break hold-and-wait.
- *Follow-up: Downside of `tryLock` approach?* Livelock; mitigate with jitter.
- *Follow-up: How do you detect deadlock in prod?* `jstack`/`jcmd Thread.print` — the JVM reports "Found one Java-level deadlock"; for j.u.c locks use `-XX:+PrintConcurrentLocks`.

**Q6. When would you choose `StampedLock` over `ReentrantReadWriteLock`? (senior-signal)**
*Model answer:* When reads vastly dominate writes and the read path is hot enough that even a CAS per read matters — `StampedLock`'s optimistic read avoids any write to shared state, so readers don't invalidate each other's cache lines. The tradeoff: it's non-reentrant, has no conditions, requires careful "copy locals then validate" coding, and can starve writers. I'd reach for it only after profiling showed the RW-lock's read path as a bottleneck, and I'd keep the critical section trivial.
- *Follow-up: Why can optimistic reads be wrong?* They read without locking; a concurrent write may produce inconsistent values, so you must `validate()` and fall back to a read lock; never dereference unvalidated references.
- *Follow-up: What about upgrading?* `StampedLock` offers `tryConvertToWriteLock` (may fail), whereas `RRWL` upgrade deadlocks.

**Q7. You see CPU underutilized but latency high; how do you find and fix lock contention? (senior-signal)**
*Model answer:* Confirm with thread dumps (many `BLOCKED` on one monitor). Quantify with low-overhead profiling: JFR `jdk.JavaMonitorEnter` events ranked by duration, or async-profiler's lock mode for a contention flame graph. Then reduce contention: shrink the critical section, move work out of the lock, split or stripe the lock, or replace it with `ConcurrentHashMap`/`LongAdder`/immutable+copy. Re-measure; verify scalability with increasing thread counts.
- *Follow-up: Why might fairness be the cause?* Fair locks serialize strictly and lose locality; switching to non-fair often restores throughput.
- *Follow-up: Could it be false sharing instead?* Yes — independent counters on one cache line; fix with `@Contended`/padding.

**Q8. Compare `synchronized` and `ReentrantLock`; when do you pick which? (senior-signal)**
*Model answer:* Default to `synchronized`: less code, auto-release on exception, excellent thread-dump visibility, JIT optimizations (elision/coarsening), and comparable uncontended performance. Pick `ReentrantLock` only for features `synchronized` lacks: `tryLock`, timed/interruptible acquisition, fairness, or multiple `Condition`s — and, on JDK 21–23, to avoid virtual-thread carrier pinning when blocking inside the critical section.
- *Follow-up: Risk of `ReentrantLock`?* Forgetting `unlock()` in `finally` leaks the lock permanently.
- *Follow-up: Does `synchronized` still pin virtual threads?* Was a problem in 21–23; JEP 491 (JDK 24) fixed it for most cases.

**Q9. What's double-checked locking and why does it need `volatile`?**
*Model answer:* A lazy-init pattern that checks the field without a lock (fast path), then locks and re-checks. `volatile` is required because `instance = new Singleton()` is not atomic: the write to `instance` can be reordered before the constructor's field writes are published, so another thread could see a non-null but partially constructed object. `volatile` forbids that reordering and publishes safely.
- *Follow-up: Simpler alternatives?* Holder idiom (lazy class init is thread-safe) or enum singleton.
- *Follow-up: Is DCL still worth it?* Rarely — the holder idiom is cleaner; DCL is justified for non-static lazy fields.

**Q10. What is lock elision/coarsening and roach-motel ordering?**
*Model answer:* Elision: the JIT removes locking on an object proven not to escape its thread (via escape analysis). Coarsening: merges adjacent lock/unlock on the same lock into one region to cut overhead. Roach-motel: the JMM lets memory ops move *into* a critical section but not out of it ("check in, can't check out"), preserving correctness while enabling optimization.
- *Follow-up: Flags?* `-XX:+EliminateLocks`, `-XX:+DoEscapeAnalysis` (both on by default).
- *Follow-up: How could elision surprise a microbenchmark?* It can make a contended-looking lock free, inflating numbers — use JMH and ensure the object escapes.

**Q11. Difference between `wait/notify` and `Condition`? (depth)**
*Model answer:* Both park/signal under a held lock with happens-before. `wait/notify` is tied to an object's single monitor and single wait set; `Condition` is created from an explicit `Lock` and you can have *multiple* conditions per lock, enabling precise signalling (e.g., `notFull`/`notEmpty`) instead of `notifyAll` thundering herds. `Condition` also offers timed/uninterruptible variants cleanly.
- *Follow-up: Can a read lock have a Condition?* No — only the write lock of `RRWL`; `StampedLock` has none.
- *Follow-up: Do both suffer spurious wakeups?* Yes — both require `while`-loop predicate checks.

**Q12. Explain reentrancy and why intrinsic locks are reentrant. (depth)**
*Model answer:* Reentrancy means a thread holding a lock can re-acquire it (tracked by a hold count incremented on re-entry, decremented on release; truly freed at zero). Intrinsic locks are reentrant so a `synchronized` method can call another `synchronized` method on the same object (or recurse) without self-deadlock. Non-reentrant locks (like `StampedLock`) deadlock on self re-acquire.
- *Follow-up: Where is the count stored?* For intrinsic: in the lock record / ObjectMonitor recursion field. For `ReentrantLock`: AQS `state`.
- *Follow-up: Failure if `StampedLock` is used reentrantly?* Deadlock against itself.

---

## 11. Glossary

- **ABA problem:** a CAS hazard where a value changes A→B→A and CAS wrongly assumes nothing happened. Mitigated by version stamps (`AtomicStampedReference`).
- **Acquire/Release barrier:** memory fences emitted on lock acquire/release ensuring proper publication and visibility of writes.
- **Adaptive spinning:** brief spinning before parking on a contended monitor, tuned by recent success.
- **AQS (AbstractQueuedSynchronizer):** the framework (state word + CLH queue) underlying `ReentrantLock`, `Semaphore`, `CountDownLatch`, RW-locks.
- **Atomicity:** an operation appears indivisible to other threads.
- **Barging:** a non-fair lock letting a newly arrived thread acquire ahead of queued waiters.
- **Biased locking:** legacy HotSpot optimization biasing a lock to its first owner; disabled by default in JDK 15+ (JEP 374).
- **CAS (compare-and-swap):** atomic conditional update; the basis of lock-free algorithms and AQS.
- **Cache coherence (MESI):** hardware protocol keeping per-core caches consistent.
- **Carrier thread:** an OS (platform) thread onto which virtual threads are scheduled.
- **CLH queue:** a FIFO queue-lock design where each waiter watches its predecessor; AQS uses a parking variant.
- **Coffman conditions:** the four conditions required for deadlock (mutual exclusion, hold-and-wait, no preemption, circular wait).
- **Condition:** an AQS-based wait/notify mechanism; many per `Lock`.
- **Context switch:** the OS saving one thread's state and loading another's; microsecond-scale cost.
- **Critical section:** code that must run with mutual exclusion.
- **Data race:** two conflicting accesses (≥1 write) unordered by happens-before; undefined behavior under the JMM.
- **Deadlock:** threads mutually waiting forever (circular wait).
- **Deflation:** converting an inflated heavyweight monitor back to a cheaper form.
- **Double-checked locking (DCL):** lazy-init pattern needing a `volatile` field.
- **Entry set:** threads blocked trying to acquire a monitor.
- **Escape analysis:** JIT analysis of whether an object escapes its method/thread; enables elision and stack allocation.
- **Fairness:** FIFO acquisition order (vs barging).
- **False sharing:** unrelated variables on one cache line causing spurious invalidations.
- **Fence/Barrier:** instruction restricting memory reordering.
- **Happens-before:** the JMM ordering relation guaranteeing visibility and ordering.
- **Hand-over-hand (lock coupling):** acquiring the next node's lock before releasing the current's while traversing a structure.
- **Heavyweight lock:** inflated monitor using OS-level blocking.
- **Inflation:** promoting a lock to a heavyweight `ObjectMonitor`.
- **Intrinsic lock / monitor:** the built-in lock on every Java object, used by `synchronized`.
- **JMM (Java Memory Model):** JLS 17 spec defining legal reads/writes across threads.
- **jcstress / jstack / jcmd / JFR / JMC:** OpenJDK concurrency stress tool / thread-dump tool / JVM command tool / Flight Recorder / Mission Control.
- **Lightweight lock (thin lock):** uncontended CAS-based lock using a stack lock record.
- **Livelock:** threads actively change state in response to each other but make no progress.
- **Lock coarsening:** JIT merging adjacent same-lock regions.
- **Lock elision:** JIT removing locks on non-escaping objects.
- **Lock granularity:** how much state one lock protects (coarse vs fine).
- **Lock record:** on-stack structure holding a displaced Mark Word during lightweight locking.
- **Lock splitting/striping:** dividing one lock into several (independent / hashed) to reduce contention.
- **LongAdder:** a striped, high-throughput concurrent counter.
- **Lost/missed wakeup:** a signal delivered when no thread is waiting (or to the wrong waiter), causing a hang.
- **Mark Word:** the polymorphic header word holding lock state, hashcode, GC bits.
- **Memory barrier:** see Fence.
- **Monitor / ObjectMonitor:** the native structure backing a heavyweight intrinsic lock (owner, recursion, entry/wait sets).
- **Mutual exclusion (mutex):** at most one thread in the critical section.
- **notify/notifyAll:** wake one / all waiters on an object's monitor.
- **Optimistic read:** `StampedLock` read with no lock, validated afterward.
- **park/unpark:** low-level thread blocking/unblocking via `LockSupport`.
- **Peterson's / bakery algorithm:** classic software mutual-exclusion algorithms.
- **Pinning (virtual thread):** a virtual thread unable to unmount from its carrier (e.g., inside `synchronized` on JDK 21–23).
- **Reentrancy:** a thread re-acquiring a lock it already holds.
- **Roach-motel ordering:** memory ops may move into but not out of a critical section.
- **Safepoint:** a global stop where all threads pause for JVM bookkeeping.
- **Semaphore:** a counting permit-based synchronizer.
- **Spinlock / busy-wait:** acquiring by looping on CAS rather than blocking.
- **Spurious wakeup:** `wait`/`await` returning without a signal.
- **Starvation:** a thread perpetually denied a resource/lock.
- **StampedLock:** a Java 8 lock supporting optimistic, read, and write modes via stamps; non-reentrant.
- **State (AQS):** the volatile int whose meaning is defined per synchronizer.
- **synchronized:** Java keyword for acquiring an object's intrinsic monitor.
- **Thread:** an independent execution path sharing the process heap.
- **tryLock:** non-blocking (or timed) acquisition attempt.
- **Visibility:** whether one thread's writes are seen by another.
- **volatile:** field modifier giving visibility/ordering and atomic access, but not mutual exclusion.
- **Wait set:** threads that called `wait()` and released the monitor.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **A lock gives TWO things:** mutual exclusion + happens-before (visibility). Guard reads *and* writes with the *same* lock.
- **Pick the primitive:** single var → `Atomic*`/`LongAdder`/`volatile`; multi-field invariant → `synchronized`/`ReentrantLock`; read-mostly → `RRWL`; read-mostly + hot → `StampedLock` optimistic; queue → `BlockingQueue`; map → `ConcurrentHashMap`.
- **Default to `synchronized`;** use `ReentrantLock` for `tryLock`/timeout/interruptible/fairness/multiple-conditions (and to avoid VT pinning on JDK 21–23).
- **Lock evolution (HotSpot):** biased (≤JDK14 only) → lightweight (CAS + stack lock record) → heavyweight (`ObjectMonitor`, OS park, entry/wait sets). Biased locking **off by default since JDK 15**.
- **`wait`/`await` ALWAYS in a `while` loop.** Prefer `notifyAll`/separate `Condition`s.
- **`unlock()` in `finally`; `lock()` before the `try`.**
- **Deadlock:** break circular wait via global lock order, or `tryLock` + jitter. Detect with `jstack`/`jcmd Thread.print`.
- **RW-lock:** downgrade OK (write→read), upgrade NOT OK (read→write deadlocks).
- **`StampedLock`:** not reentrant, no conditions, copy-to-locals-then-`validate()`, pass stamps to unlock.
- **`volatile` ≠ mutual exclusion;** it's visibility/ordering for one field. DCL needs `volatile`.
- **Contention diagnosis:** JFR `jdk.JavaMonitorEnter`, async-profiler lock mode; fix by shrinking/splitting/striping/replacing locks.
- **Fair locks** = predictable latency, lower throughput; default non-fair.
- **Interrupt:** `synchronized`/`lock()` not interruptible; `lockInterruptibly`/`tryLock(t,u)`/`wait`/`await` are; `park` returns without throwing.
- **JIT:** lock elision (escape analysis), lock coarsening, roach-motel ordering — on by default.
- **Virtual threads (JDK 21–23):** `synchronized` blocking pins the carrier — use `ReentrantLock`; fixed in JDK 24 (JEP 491). Diagnose with `-Djdk.tracePinnedThreads=full`.

### 12.2 Self-test (no answers — recall practice)

1. Trace exactly what `Object.wait()` does to the monitor state and the calling thread, and why the call must occur inside `synchronized`.
2. Two threads transfer money between accounts A and B in opposite directions and deadlock. Give two distinct, code-level fixes and explain which Coffman condition each one breaks.
3. Implement a bounded buffer with `ReentrantLock` and two `Condition`s; then explain precisely why `signal()` is correct here but a single-monitor `notify()` would be unsafe.
4. Why does double-checked locking require `volatile`, and what specific reordering would otherwise corrupt a reader? Give a safer alternative and explain why it's thread-safe.
5. You profile a read-heavy service and the `ReentrantReadWriteLock` read path is the bottleneck. Walk through converting the hottest reader to a `StampedLock` optimistic read, listing every correctness rule you must obey.
6. Explain biased → lightweight → heavyweight lock evolution, including what triggers each transition and why biased locking was disabled in JDK 15.
7. On JDK 21 your virtual-thread service throughput collapses under load. Explain the mechanism, how you'd confirm it, and the fix.
8. Describe how `ReentrantLock.lock()` works through AQS in both the uncontended and contended paths, and how fairness changes the behavior.

# The Java Memory Model (JMM)

> An exhaustive engineering-handbook chapter for senior Java/JVM backend developers. From first principles to deep internals, tuning, debugging, and interview mastery.

---

## 1. Overview & where it fits

### 1.1 What it is

The **Java Memory Model (JMM)** is the part of the Java Language Specification (JLS) that defines, precisely and portably, **how threads interact through memory** — specifically, **under what conditions a write performed by one thread becomes visible to a read performed by another thread**, and **what orderings of memory operations a program is allowed to observe**.

It is *not* a description of how the heap is laid out (that is the "runtime data areas" of the JVM — the heap, the stacks, the metaspace). The word "memory" here means **shared mutable state accessed concurrently**, and "model" means **a formal contract** between three parties:

- The **programmer**, who writes source code with an intuitive notion of "this happens before that."
- The **compiler** (both `javac` and, far more aggressively, the **JIT** — the Just-In-Time compiler inside the JVM that turns hot bytecode into native machine code at runtime).
- The **hardware** (CPUs with multiple cores, per-core caches, store buffers, and out-of-order execution).

The JMM is the referee that lets all three optimize independently while still giving the programmer a usable set of guarantees.

> **Jargon, explained — JIT (Just-In-Time compiler):** The JVM initially *interprets* bytecode (executes it instruction by instruction). When it notices a method or loop is "hot" (executed many times), it compiles that bytecode to optimized native machine code on the fly. HotSpot, the reference Oracle/OpenJDK JVM, ships two JIT compilers: **C1** (client, fast to compile, lighter optimization) and **C2** (server, slower to compile, aggressive optimization). The JIT reorders, inlines, and eliminates instructions — which is exactly why we need a memory model.

### 1.2 The problem it solves

On a single-core machine running one thread, intuition works: statements execute top to bottom, and a value you just wrote is the value you read back. The moment you add **multiple threads on multiple cores**, three layers conspire to break that intuition:

1. **Compiler reordering.** The JIT can reorder independent instructions to keep the CPU pipeline full or eliminate redundant loads.
2. **CPU out-of-order execution and store buffers.** Modern CPUs execute instructions out of program order and buffer writes locally before flushing them to the cache hierarchy.
3. **Per-core caches.** Each core has its own L1/L2 cache. A write may sit in one core's cache (or store buffer) and be invisible to another core for an indeterminate time.

Without a memory model, the answer to "when will thread B see thread A's write?" would be **"it depends on the JVM version, the CPU architecture, the phase of the moon, and whether the JIT decided to compile this method."** That is unusable. The JMM replaces "it depends" with a small, learnable set of rules — chiefly the **happens-before relation** — that hold on *every* conforming JVM on *every* CPU.

### 1.3 When you reach for it

You don't "use the JMM" the way you use a library. You **rely on it** every time you:

- Write any code touching shared mutable state from more than one thread.
- Decide whether a field needs `volatile`, `synchronized`, an `AtomicX`, or a lock.
- Publish an object built by one thread for consumption by others (caches, listener registries, config singletons).
- Reason about whether a clever lock-free algorithm is correct.
- Debug a "works 99.9% of the time" heisenbug that vanishes under a debugger.
- Tune for performance and want to know the *cheapest correct* synchronization.

### 1.4 The one-paragraph mental model

> **Each thread runs in its own little world where it may observe an arbitrarily reordered, arbitrarily stale view of shared memory — UNLESS you establish a happens-before edge between two threads. A happens-before edge is a one-way "publication" guarantee: everything thread A did *before* the edge is guaranteed visible and correctly ordered to thread B *after* the edge. You create these edges with specific, well-defined actions: releasing/acquiring a lock, writing/reading a `volatile`, starting/joining a thread, and a few others. No edge, no guarantee — and "no guarantee" legally includes "the optimizer tore your code apart and B saw garbage." Your entire job in concurrent Java is to place enough happens-before edges that every read of shared state is ordered after the write it needs to see — and not one edge more, because edges cost performance.**

---

## 2. Foundations from first principles

We build the model brick by brick. Every term is defined as it appears.

### 2.1 Why a memory model must exist at all

Consider this textbook example. Two fields, both initially `0`:

```java
// Shared, both initially 0
int x = 0;
int y = 0;

// Thread 1                 // Thread 2
x = 1;                      y = 1;
int r1 = y;                int r2 = x;
```

Intuitively, you might think `r1 == 0 && r2 == 0` is impossible: surely at least one thread ran its write before the other read. But on real hardware **`r1 == 0 && r2 == 0` happens**. Why?

- **Store buffers:** When Thread 1 executes `x = 1`, the CPU may place that write in a **store buffer** — a small per-core queue of pending writes — and continue executing *before* the write reaches the cache where Thread 2 can see it. So Thread 1 reads `y` (still 0) before its own `x=1` is globally visible. Thread 2 does the symmetric thing. Both read 0.
- **Compiler reordering:** Even before hardware gets involved, the JIT sees that in Thread 1, `x = 1` and `int r1 = y` touch different variables and have no dependency, so it may legally swap them.

> **Jargon, explained — store buffer:** A FIFO-ish buffer between a CPU core and its L1 cache. Writes go into it immediately so the core doesn't stall waiting for the cache; they drain into the cache later. This is why a core can "read its own writes" instantly (store-to-load forwarding) but other cores see those writes only after they drain.

This single example proves the point: **without explicit synchronization, the only orderings you can rely on are the ones the JMM grants you.** Everything else is fair game for the optimizer and the hardware.

### 2.2 Sequential consistency — the model we *wish* we had (but can't afford)

**Sequential consistency (SC)** is the strongest, most intuitive memory model: it pretends that (a) every thread's operations happen in program order, and (b) all threads' operations are interleaved into a single global order that everyone agrees on. Under SC, the `r1==0 && r2==0` outcome above is impossible.

> **Jargon, explained — sequential consistency:** Coined by Leslie Lamport (1979). "The result of any execution is the same as if the operations of all the processors were executed in some sequential order, and the operations of each individual processor appear in this sequence in the order specified by its program." It's the model most programmers *assume* by default.

The JMM does **not** give you SC for ordinary variables, because enforcing SC everywhere would require memory barriers on nearly every memory access, destroying performance. Instead, the JMM offers a **DRF-SC guarantee** (next section): if you avoid data races, you get sequential consistency. That is the central bargain of the JMM.

### 2.3 Actions, program order, and the synchronization order

The JMM reasons about **actions**. An action is an inter-thread-visible operation:

- **Read / Write** of a variable (a field or array element — local variables are thread-private and never count).
- **Volatile read / Volatile write.**
- **Lock / Unlock** of a monitor.
- **Thread start / join, and the special "first/last" actions** of a thread.
- **External actions** (I/O) and **thread divergence** actions (used to formalize infinite loops).

**Program order (PO):** Within a *single* thread, the JMM defines a total order over that thread's actions matching the source order. Crucially, **the JVM is allowed to reorder actions as long as the thread can't tell** — i.e., a single thread always *appears* to execute in program order to itself. This is the **"as-if-serial"** rule. It is the within-thread analog of SC.

> **Jargon, explained — as-if-serial semantics:** No matter how the compiler/CPU reorders, a single thread, looking only at its own actions, sees results consistent with sequential execution. `a = 1; b = a + 1;` will never see `b == 1`. Reordering is invisible *within* a thread; it only becomes observable *across* threads.

**Synchronization actions** are a special subset: volatile reads/writes, lock/unlock, thread start/join, and a few others. The JMM defines a **synchronization order (SO)** — a single total order over *all* synchronization actions in an execution, consistent with program order, on which all threads agree. This is where cross-thread ordering is born.

### 2.4 The happens-before relation (the keystone)

**Happens-before (HB)** is a partial order over actions. It is *the* central concept. If action **A happens-before action B** (written `A hb B`), then:

1. The memory effects of A are **visible** to B (A's writes are seen by B's reads), and
2. A is **ordered before** B (the optimizer may not move B before A in a way that B could observe).

Two crucial subtleties most people get wrong:

- **Happens-before does NOT mean "happens earlier in wall-clock time."** It is purely about visibility and ordering guarantees. Two actions can be unordered by HB even if one ran long before the other.
- **If A does NOT happen-before B, the JMM grants NO guarantee.** B might see A's write, or a stale value, or — in the presence of a data race — a value that *no single execution ever wrote* (e.g., a torn 64-bit value).

#### The happens-before rules (the complete list)

These are the *primitive* edges. Everything else is derived by transitivity.

| # | Rule | Edge created |
|---|------|--------------|
| 1 | **Program order** | Each action in a thread happens-before every action later in that thread's program order. |
| 2 | **Monitor lock** | An unlock of monitor M happens-before every subsequent lock of M (subsequent in synchronization order). |
| 3 | **Volatile** | A write to a volatile field happens-before every subsequent read of that same field. |
| 4 | **Thread start** | A call to `thread.start()` happens-before any action in the started thread. |
| 5 | **Thread join / termination** | Every action in a thread happens-before another thread successfully returns from `thread.join()` on it (or detects it has terminated via `isAlive()`). |
| 6 | **Thread interruption** | A thread calling `t.interrupt()` happens-before the interrupted thread detecting the interrupt. |
| 7 | **Object finalization** | The end of a constructor happens-before the start of the `finalize()` method for that object. |
| 8 | **Transitivity** | If `A hb B` and `B hb C`, then `A hb C`. |

> **Jargon, explained — monitor:** Every Java object has an associated *monitor* (an intrinsic lock). `synchronized(obj)` acquires `obj`'s monitor; exiting the block releases it. `synchronized` on an instance method locks `this`; on a static method, locks the `Class` object.

**The acquire/release framing.** Modern parlance (borrowed from C++11 and the academic literature) describes rules 2 and 3 as **release** and **acquire** operations:

- A **release** (unlock, volatile write) is a *one-way barrier downward*: nothing above it can move below it. It "publishes" everything done before it.
- An **acquire** (lock, volatile read) is a *one-way barrier upward*: nothing below it can move above it. It "imports" everything published.

A release followed by a matching acquire (same lock, or write-then-read of the same volatile) forms a **happens-before edge**, and — by transitivity with program order — *everything* the releasing thread did before the release becomes visible to *everything* the acquiring thread does after the acquire. This is the engine of all safe publication.

### 2.5 Data races, defined precisely

Two accesses to the **same variable** form a **conflicting pair** if at least one is a write. A **data race** occurs when:

> Two conflicting accesses from different threads are **not ordered by happens-before**.

That's the formal definition. Note it is purely about HB ordering, not about timing.

A program is **correctly synchronized** (a.k.a. **data-race-free, DRF**) if **all** of its sequentially consistent executions contain no data races. (The quantifier is subtle: you check for races *only* in the SC executions, then conclude the program behaves SC-ly.)

**The DRF-SC guarantee** — the headline promise of the JMM:

> If your program is data-race-free, then every execution of it is **sequentially consistent**: it behaves as if all operations happened in a single global interleaving respecting each thread's program order.

In plain terms: **eliminate data races and you may reason with simple sequential intuition.** Conversely, if you have *even one* data race, the JMM still defines *some* bounded behavior (it's not full C/C++ "undefined behavior" — see §2.6), but it is no longer guaranteed sequentially consistent, and reasoning becomes dramatically harder.

### 2.6 What a data race is NOT (Java is not C)

A vital distinction. In **C/C++**, a data race is **undefined behavior** — the compiler may assume races never happen and miscompile arbitrarily (format your disk, in principle). In **Java**, a data race is **defined but weak**: the JMM guarantees **out-of-thin-air (OOTA) safety**.

> **Jargon, explained — out-of-thin-air (OOTA) value:** A value that appears in a read but was never written by any thread in any prior action — conjured "from thin air" by a hypothetical circular dependency in speculative execution. The JMM forbids OOTA reads. So a racy `int` read returns *some* value actually written by *some* thread at *some* point (or the default 0), never a random invented number.

This OOTA guarantee is what makes Java memory-safe even under races: a racy reference field can never become a pointer to arbitrary memory. The cost: the formal definition of the JMM (the "causality" rules in JLS §17.4.8) is famously intricate, and the OOTA prohibition is the part the spec community admits is *under-specified* — there is ongoing work (JEP/academic, "do not reorder" / "OOTA" research) to fix it. For practical purposes: **don't rely on race semantics; just remove the races.**

### 2.7 Word tearing & the 64-bit non-atomicity rule

Two more primitive guarantees:

- **No word tearing:** Writing one field or array element must not disturb adjacent ones, even for `byte`/`boolean` packed in the same word. The JVM must isolate accesses.
- **64-bit non-atomic loophole:** The JMM permits a JVM to treat a **non-volatile** `long` or `double` read/write as **two separate 32-bit operations**. So a racy `long` can be read **torn** — the high 32 bits of one write combined with the low 32 bits of another. Marking the field `volatile` makes 64-bit access atomic. (In practice, every mainstream 64-bit JVM does atomic 64-bit access anyway, but the spec permits tearing, and you must not rely on the implementation's generosity. Flag: implementation-specific.)

---

## 3. How it works internally

This is the heart of the document. We trace the full path from your source code down to the silicon, and back up to the formal model.

### 3.1 The three reordering layers (control & data flow)

A memory access in your source travels through three reordering stages before it becomes a visible effect:

```
   Java source
       │  (javac — minimal reordering; just bytecode)
       ▼
   Bytecode
       │  (JIT: C1/C2 — AGGRESSIVE reordering, inlining, CSE, hoisting)
       ▼
   Native machine code (per-core instruction stream)
       │  (CPU: out-of-order execution, speculative loads)
       ▼
   Execution units + store buffer
       │  (store buffer drains asynchronously)
       ▼
   L1 / L2 cache  ──(cache coherence protocol, e.g. MESI)──►  other cores
       ▼
   L3 / main memory
```

Each layer may reorder, and the JMM's job is to insert constraints (memory barriers / fences) at the right points so that, *despite* all this freedom, happens-before edges are honored.

> **Jargon, explained — CSE (common subexpression elimination) and hoisting:** Two JIT optimizations. CSE computes a repeated expression once. *Loop hoisting* moves a loop-invariant read out of the loop — which is exactly why a non-volatile flag polled in a loop can be read *once* and cached in a register forever, causing the loop to spin infinitely after another thread sets the flag.

### 3.2 Cache coherence vs. memory ordering (a distinction that trips up experts)

People often say "volatile flushes the CPU cache." **That is wrong on modern hardware.** Here's the accurate picture:

- **Cache coherence** (e.g., the **MESI protocol**) already guarantees that all cores eventually agree on the value of each individual cache line. You do *not* need to manually "flush caches"; the hardware does it.

> **Jargon, explained — MESI:** A cache-coherence protocol. Each cache line is in one of four states: **M**odified, **E**xclusive, **S**hared, **I**nvalid. When a core wants to write a line, it broadcasts an invalidation so other cores mark their copy Invalid; the writer then owns it Modified. This keeps per-line values consistent across cores — automatically.

- **What you actually need is ordering**, not flushing. The problem isn't that core B never sees core A's value; it's *when* relative to other operations, and whether reorderings let B see A's writes in a contradictory order. **Store buffers and out-of-order execution** are the culprits, and the fix is **memory barriers (fences)** that constrain ordering, plus on x86 a store-buffer drain.

So the precise statement: **a `volatile` write/read compiles to memory barrier instructions that prevent the compiler and CPU from reordering across them, and (on x86) ensure the store buffer is drained at the right point.** The coherence protocol handles propagation; the barriers handle ordering.

### 3.3 Memory barriers: the four-fence taxonomy

The JMM is implemented (conceptually) via four barrier types. HotSpot's source uses exactly these names (`orderAccess.hpp`):

| Barrier | Meaning | Prevents |
|---------|---------|----------|
| **LoadLoad** | `Load1; LoadLoad; Load2` | Load2 (and later loads) reordered before Load1. |
| **StoreStore** | `Store1; StoreStore; Store2` | Store2 made visible before Store1. |
| **LoadStore** | `Load1; LoadStore; Store2` | Store2 made visible before Load1 completes. |
| **StoreLoad** | `Store1; StoreLoad; Load2` | Load2 executed before Store1 is globally visible. **The most expensive** — it drains the store buffer. |

**How volatile maps to barriers (the JSR-133 cookbook recipe):**

- **Volatile write** is compiled as: `StoreStore` barrier *before* the write, then the write, then a `StoreLoad` barrier *after*. The pre-`StoreStore` ensures all prior normal writes are published before the volatile write; the post-`StoreLoad` ensures the volatile write is visible before any subsequent load (this is the expensive one).
- **Volatile read** is compiled as: the read, then a `LoadLoad` barrier and a `LoadStore` barrier *after*. This ensures no subsequent load/store floats up above the volatile read.

> **Jargon, explained — JSR-133:** The Java Specification Request that **redefined the JMM in Java 5 (2004)**. The original Java 1.0–1.4 memory model was broken (volatile didn't order normal writes; final fields had no guarantees; double-checked locking was unfixable). JSR-133 gave us the happens-before model we use today. Doug Lea's "JSR-133 Cookbook for Compiler Writers" is the canonical barrier-placement reference.

**Architecture matters:**

- On **x86 / x86-64 (TSO — Total Store Order)**, the hardware already preserves load-load, load-store, and store-store ordering; only **store-load** can be reordered (because of the store buffer). So a volatile *read* on x86 is essentially free (a plain `mov` plus compiler-only barriers), and a volatile *write* needs only one real instruction (a `lock`-prefixed op or `mfence`) for the StoreLoad.
- On **ARM/AArch64 and POWER (weakly ordered)**, the hardware reorders much more aggressively, so volatile reads and writes both emit real barrier instructions (`dmb ish`, `ldar`/`stlr` on ARMv8). **Code that "works" on x86 can break on ARM** precisely because x86's strong ordering masks missing synchronization. (Real-world relevance: the migration to Apple Silicon, AWS Graviton, and Ampere ARM servers has surfaced latent races that were invisible on Intel.)

> **Jargon, explained — TSO (Total Store Order):** x86's memory model. All stores from one core hit memory in program order, and all cores see them in that order; the *only* permitted reordering is a core's own load being satisfied before its earlier store drains from the store buffer (store→load reorder). This is why x86 is "almost sequentially consistent."

### 3.4 Step-by-step: a volatile publication, traced end to end

Let's trace the canonical safe-publication pattern under the hood.

```java
class Holder {
    int data;                 // plain field
    volatile boolean ready;   // the "publication flag"
}

// Producer thread
h.data = 42;        // (1) plain write
h.ready = true;     // (2) volatile write  (RELEASE)

// Consumer thread
if (h.ready) {      // (3) volatile read   (ACQUIRE)
    use(h.data);    // (4) plain read — guaranteed to see 42
}
```

What happens, in order:

1. **Producer executes `h.data = 42`.** This is a plain store; it enters the store buffer.
2. **Producer reaches `h.ready = true`.** The JIT inserts a **StoreStore** barrier *before* it: this forbids the `data` store from being reordered *after* the `ready` store, and ensures `data=42` is published (drained toward cache) before `ready=true` becomes visible. Then the volatile write executes, followed by a **StoreLoad** barrier.
3. **The JMM rule fires:** the volatile write to `ready` happens-before any subsequent volatile read of `ready`. Combined with **program order** (`data=42` PO-before `ready=true`, and `ready` read PO-before `data` read) and **transitivity**, we get: `data=42` **hb** `data` read.
4. **Consumer executes the volatile read `h.ready`.** The JIT places **LoadLoad** + **LoadStore** barriers *after* it, forbidding the subsequent `h.data` load from floating up above the `ready` read.
5. **Consumer reads `h.data`** and is *guaranteed* to observe `42` — never the default `0` — because the happens-before edge orders the producer's write before this read.

If `ready` were a **plain** `boolean`, step 3's HB edge would not exist; the consumer could see `ready==true` but `data==0` (the writes reordered, or the `data` write not yet visible). That is the classic publication bug.

### 3.5 The lifecycle / state machine of a synchronized block

```
   Thread T wants synchronized(M) { body }
        │
        ▼
  [Try acquire monitor M]
        │            ┌──── M held by another thread ───►  [BLOCKED] ──► (woken) ──┐
        ▼            │                                                            │
  [ACQUIRED]  ◄──────┴────────────────────────────────────────────────────────────┘
        │   (ACQUIRE semantics: LoadLoad+LoadStore-like barrier;
        │    imports everything published by the previous unlock of M)
        ▼
  [Execute body — sees all writes that happened-before the prior unlock of M]
        │
        ▼
  [Release monitor M]
        │   (RELEASE semantics: StoreStore+LoadStore-like barrier;
        │    publishes everything done in the body)
        ▼
  [unlock hb next lock]  ──► next thread to lock M sees all of T's body writes
```

The same release/acquire shape underlies `Lock.lock()/unlock()`, `Semaphore`, `CountDownLatch.await()/countDown()`, `Future.get()`, blocking-queue `put/take`, and every other `java.util.concurrent` (j.u.c.) synchronizer — they all promise happens-before edges in their Javadoc ("memory consistency effects" sections).

### 3.6 Final fields: the freeze, traced

Final fields get a *special* guarantee that does **not** require any synchronization on the reader's side — but only if the object is published correctly (no data race that lets the reference escape early).

```java
class Point {
    final int x;
    final int y;
    Point(int x, int y) { this.x = x; this.y = y; }  // freeze at constructor end
}
```

Internal mechanism:

1. At the **end of the constructor**, the JMM inserts a **freeze action** for each final field. Conceptually a **StoreStore** barrier sits between the final-field writes and the publication of `this`.
2. **Rule:** if a thread reads a reference to the object *after* the object's constructor finishes (via any normal means), and that reference was not made visible through a data race *during* construction, then the reader is **guaranteed** to see the correctly initialized values of all final fields — and, transitively, anything reachable from those final fields that was set before the freeze.
3. This holds **even with no `volatile`, no lock** on the reader. It is why immutable objects (`String`, boxed `Integer`, etc.) are safe to share freely.

**The catch — the "this escape":** if the constructor leaks `this` *before* the freeze (e.g., registers a listener, starts a thread that uses `this`, stores `this` in a static field), another thread can observe the object before its final fields are frozen, and the guarantee evaporates. This is the **`this`-escape** anti-pattern.

```java
// BROKEN: this escapes before construction completes
class Listener {
    final int id;
    Listener(EventBus bus, int id) {
        bus.register(this);   // ❌ another thread can now see `this` with id == 0
        this.id = id;         // freeze happens too late
    }
}
```

### 3.7 The formal execution model (causality, briefly)

For completeness — this is the part interviewers rarely probe but spec authors obsess over. The JMM defines a *legal execution* via a **commitment/causality** procedure (JLS 17.4.8):

- Start from a **well-formed execution** (each read sees *some* write of the same variable; intra-thread consistency holds).
- Build it up by **committing actions** in a sequence of approximating executions, ensuring each committed read's value is "justified" by happens-before from already-committed writes.
- This procedure exists to **permit beneficial reorderings of race-free-looking code while forbidding out-of-thin-air values**. It's the formal teeth behind "no OOTA."

You will essentially never reason at this level in practice. The practical model is: *happens-before edges + DRF-SC.* Everything above is the JMM's machinery to make that practical model sound.

---

## 4. The complete toolkit

The JMM is enforced through language keywords, JDK classes, and JVM-level primitives. Here is the full kit.

### 4.1 Language-level constructs

| Construct | What it does (JMM-wise) | Key parameters / forms | Notes & defaults |
|-----------|------------------------|------------------------|------------------|
| `volatile` | Makes reads/writes synchronization actions; write hb subsequent read; makes 64-bit access atomic; prevents reordering across it. | Field modifier only. | Does **not** make compound ops (`i++`) atomic. No mutual exclusion. |
| `synchronized` | Acquires/releases a monitor; provides mutual exclusion **and** happens-before (unlock hb lock). | Block `synchronized(obj){}` or method modifier. | Reentrant. Locks `this` (instance) or `Class` (static). |
| `final` | Freeze semantics: safe to read without synchronization if no `this`-escape. | Field modifier. | Only fields; gives visibility of values set in constructor. |
| `Thread.start()` / `join()` | Start hb first action; last action hb return-from-join. | — | Standard thread lifecycle edges. |

### 4.2 `java.util.concurrent.atomic` — the atomics

All provide atomic read-modify-write **and** volatile-like ordering (each method's memory effects are documented).

| Class | Purpose | Key methods | Memory effect |
|-------|---------|-------------|---------------|
| `AtomicInteger` / `AtomicLong` | Atomic counters | `get`, `set`, `incrementAndGet`, `getAndAdd`, `compareAndSet`, `getAndUpdate` | `get`=volatile read, `set`=volatile write, CAS=full barrier |
| `AtomicReference<V>` | Atomic object ref | `get`, `set`, `compareAndSet`, `getAndSet`, `updateAndGet` | Same; basis for lock-free structures |
| `AtomicBoolean` | Atomic flag | `compareAndSet`, `getAndSet` | One-shot flags, latches |
| `AtomicIntegerFieldUpdater` etc. | CAS on a `volatile` field of another class without per-object overhead | reflection-based factory | Field must be `volatile` and accessible |
| `AtomicStampedReference` | Ref + version stamp | `compareAndSet(expRef,newRef,expStamp,newStamp)` | Solves the **ABA problem** |
| `AtomicMarkableReference` | Ref + boolean mark | similar | For lock-free linked structures |
| `LongAdder` / `DoubleAdder` | High-contention counter; striped cells to avoid false sharing | `add`, `increment`, `sum` | Faster than `AtomicLong` under heavy write contention; `sum` is not atomic snapshot |
| `LongAccumulator` | Generalized adder with a binary op | `accumulate`, `get` | e.g. running max |

> **Jargon, explained — CAS (compare-and-swap):** A single atomic hardware instruction (`lock cmpxchg` on x86, `LDREX/STREX` or `CAS` on ARM): "if memory location holds `expected`, set it to `new` and report success; otherwise report failure." The foundation of all lock-free algorithms. **ABA problem:** a value goes A→B→A between your read and your CAS; CAS succeeds but the world changed underneath you. `AtomicStampedReference` adds a counter so A-with-stamp-1 ≠ A-with-stamp-3.

### 4.3 `VarHandle` and `MethodHandles` (Java 9+) — the modern primitive

`VarHandle` (JEP 193, Java 9) is the **modern, supported** replacement for `sun.misc.Unsafe` low-level access. It lets you choose the **exact memory-ordering mode** per access:

| Access mode | Ordering | Use when |
|-------------|----------|----------|
| `getPlain` / `setPlain` | No ordering, no atomicity guarantees beyond word | Maximal speed, no cross-thread needs |
| `getOpaque` / `setOpaque` | Per-variable progress/coherence, **no** ordering w.r.t. other vars | Progress guarantees without full fences (e.g. cancellation flags) |
| `getAcquire` / `setRelease` | Acquire / release semantics (half fences) | Cheaper than volatile when you only need one direction |
| `getVolatile` / `setVolatile` | Full volatile semantics | Default safe choice |
| `compareAndSet`, `weakCompareAndSet`, `getAndAdd`, etc. | Atomic RMW | Lock-free algorithms |

Plus standalone fences: `VarHandle.fullFence()`, `acquireFence()`, `releaseFence()`, `loadLoadFence()`, `storeStoreFence()`.

> **Jargon, explained — opaque vs. release/acquire vs. volatile:** These are the **C++11-style memory orders** exposed in Java 9+. *Opaque* guarantees the access actually happens (no hoisting/elimination) and is coherent per-variable, but imposes **no inter-variable ordering** — the cheapest non-plain mode. *Release/acquire* add one-directional ordering. *Volatile* adds the full bidirectional ordering including the expensive StoreLoad. Picking the weakest mode that is still correct is an advanced performance lever.

> **Jargon, explained — `sun.misc.Unsafe`:** An internal JDK class exposing raw memory ops (CAS, fences, off-heap allocation). Widely (ab)used by libraries (Netty, Hazelcast, old `j.u.c.`) before Java 9. It is being phased out (`jdk.internal.misc.Unsafe` internally; `sun.misc.Unsafe` deprecated for removal). **Use `VarHandle` instead.**

### 4.4 Higher-level synchronizers (all carry HB guarantees)

| Tool | HB edge it provides |
|------|---------------------|
| `ReentrantLock` / `ReentrantReadWriteLock` | unlock hb subsequent lock |
| `StampedLock` (Java 8) | release hb acquire; optimistic read mode (validate after) |
| `Semaphore` | release of a permit hb subsequent acquire |
| `CountDownLatch` | `countDown()` hb returning `await()` |
| `CyclicBarrier` | actions before `await()` hb actions after the barrier trips |
| `Phaser` | arrival hb advance |
| `Exchanger` | each `exchange()` is a two-way HB rendezvous |
| `BlockingQueue` (`put`/`take`) | put hb corresponding take |
| `Future` / `CompletableFuture` | task actions hb `get()` returning |
| `ConcurrentHashMap` | actions before put-into-map hb actions after retrieval |
| `Thread.join()` | thread's actions hb join return |

### 4.5 JVM flags & tools relevant to the JMM

| Flag / tool | Purpose |
|-------------|---------|
| `-XX:+PrintAssembly` (needs `hsdis`) | See the actual barriers/fences the JIT emitted for a volatile/CAS. |
| `-XX:-TieredCompilation` / `-XX:TieredStopAtLevel=N` | Control C1/C2 to study compiled output deterministically. |
| `-Xint` | Pure interpreter — disables JIT reordering (useful to *contrast*, not to fix bugs). |
| `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` | See what the JIT inlined (affects reordering scope). |
| **JCStress** (`org.openjdk.jcstress`) | The official JMM concurrency-stress test harness — the *only* reliable way to test memory-ordering code. |
| **jcmd / jstack** | Thread dumps for deadlock/contention diagnosis. |
| **async-profiler / JFR** | Lock contention, allocation, false-sharing-adjacent cache effects. |
| `@Contended` (`jdk.internal.vm.annotation.Contended`) | Pads a field to its own cache line to prevent false sharing (needs `-XX:-RestrictContended`). |

---

## 5. Code examples by use case

Seven distinct scenarios, each with the broken and correct version where instructive.

### 5.1 The stop-flag (loop hoisting) — the classic visibility bug

```java
// ❌ BROKEN: the JIT may hoist the read of `running` out of the loop,
//    caching it in a register forever. The loop can spin after stop().
class Worker extends Thread {
    private boolean running = true;          // plain field
    public void run() {
        while (running) {                    // read may be hoisted once
            doWork();
        }
    }
    public void stop_() { running = false; } // write may never be seen
}
```

```java
// ✅ CORRECT: volatile establishes write-hb-read; read is re-fetched each iteration.
class Worker extends Thread {
    private volatile boolean running = true;
    public void run() {
        while (running) { doWork(); }
    }
    public void stop_() { running = false; }
}
```

Why it works: each loop iteration performs a *volatile read*, which the JIT may not hoist, and `running=false` (volatile write) happens-before the next read. This is the single most common JMM bug in real code.

### 5.2 Safe publication of an immutable config with `final`

```java
// Immutable, safely publishable WITHOUT synchronization on readers,
// thanks to final-field freeze semantics.
public final class ServerConfig {
    private final String host;
    private final int port;
    private final List<String> allowList;     // defensively copied + unmodifiable

    public ServerConfig(String host, int port, List<String> allowList) {
        this.host = host;
        this.port = port;
        // copy BEFORE the freeze so the snapshot is part of the published state
        this.allowList = List.copyOf(allowList);   // immutable copy
    }
    public String host() { return host; }
    public int port()    { return port; }
    public List<String> allowList() { return allowList; }
}

// Publication: even a plain field handoff is safe for an all-final object,
// but use volatile/AtomicReference if the reference itself is swapped at runtime.
class ConfigHolder {
    private volatile ServerConfig current;     // volatile: the *reference* is mutable
    public void reload(ServerConfig c) { current = c; }   // release
    public ServerConfig get() { return current; }         // acquire
}
```

Two layers: `final` fields make each `ServerConfig` safe to read; `volatile` on `current` makes *swapping* configurations safely visible.

### 5.3 Double-checked locking (DCL) — done correctly

DCL is the cautionary tale of the JMM. The pre-Java-5 version was **unfixable**; the Java-5+ version requires `volatile`.

```java
// ❌ BROKEN (and was unfixable before Java 5): no volatile.
class BrokenSingleton {
    private static Holder instance;          // plain
    static Holder get() {
        if (instance == null) {              // (1) racy read
            synchronized (BrokenSingleton.class) {
                if (instance == null) {
                    instance = new Holder();  // (2) construction + publication can reorder
                }
            }
        }
        return instance;
    }
}
```

The bug: `instance = new Holder()` is **not atomic**. It is roughly: (a) allocate, (b) run constructor, (c) assign reference to `instance`. The JIT may reorder to a→c→b, so another thread sees a **non-null but half-constructed** `instance` via the first (un-synchronized) check.

```java
// ✅ CORRECT (Java 5+): volatile fixes both ordering and visibility.
class Singleton {
    private static volatile Holder instance;   // volatile is mandatory
    static Holder get() {
        Holder local = instance;               // read volatile once into a local
        if (local == null) {
            synchronized (Singleton.class) {
                local = instance;
                if (local == null) {
                    local = new Holder();
                    instance = local;          // volatile write: publishes fully-built Holder
                }
            }
        }
        return local;
    }
}
```

The `volatile` write to `instance` happens-before the racy `instance` read in another thread, so a non-null read guarantees a fully-constructed object. The `local` variable is a small optimization (one volatile read instead of two).

```java
// ✅ BETTER (when lazy + simple): the Initialization-on-Demand Holder idiom.
//    No volatile, no synchronized, fully lazy, JMM-correct by class-init semantics.
class Singleton2 {
    private Singleton2() {}
    private static class Holder { static final Singleton2 INSTANCE = new Singleton2(); }
    public static Singleton2 get() { return Holder.INSTANCE; }
}
```

The JVM guarantees class initialization is thread-safe and happens-before any use of the class — so `Holder` loads lazily on first `get()`, with the JLS giving you correctness for free. **Prefer this over DCL** unless you need an instance field rather than a static.

> **Jargon, explained — class initialization safety:** The JLS (§12.4) guarantees that static initialization of a class runs **exactly once**, under an implicit lock, and its effects are visible to all threads that subsequently use the class. This is why the holder idiom needs no explicit synchronization.

### 5.4 Lock-free counter with CAS, and the contention upgrade

```java
// Lock-free increment via CAS retry loop.
import java.util.concurrent.atomic.AtomicLong;

class Counter {
    private final AtomicLong count = new AtomicLong();
    public long inc() { return count.incrementAndGet(); }   // internally a CAS loop
    public long get() { return count.get(); }
}
```

Under **high write contention**, `AtomicLong`'s single hot cache line becomes a bottleneck (every core fighting for the same line — true sharing). Upgrade to `LongAdder`:

```java
import java.util.concurrent.atomic.LongAdder;

class HotCounter {
    private final LongAdder count = new LongAdder();   // striped across cells
    public void inc()   { count.increment(); }
    public long total() { return count.sum(); }        // NOT an atomic snapshot
}
```

`LongAdder` keeps per-thread (per-stripe) cells, each on its own cache line, so threads rarely collide; `sum()` adds them up. Trade-off: `sum()` is approximate under concurrent updates and uses more memory. Use it for **counters/metrics**, not where you need an exact instantaneous read.

### 5.5 Safe one-time publication with `AtomicReference` and `compareAndSet`

```java
// Lazy, lock-free, exactly-once initialization (no synchronized).
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class LazyOnce<T> {
    private final AtomicReference<T> ref = new AtomicReference<>();
    private final Supplier<T> factory;
    LazyOnce(Supplier<T> f) { this.factory = f; }

    T get() {
        T v = ref.get();                    // acquire
        if (v == null) {
            T created = factory.get();
            if (ref.compareAndSet(null, created)) {  // release on success
                return created;
            }
            return ref.get();               // lost the race; use the winner's value
        }
        return v;
    }
}
```

The `compareAndSet` provides full-barrier publication; the loser of the race discards its instance and reads the winner's, guaranteed visible.

### 5.6 Eliminating false sharing with `@Contended` / padding

```java
// ❌ False sharing: two hot counters land on the same 64-byte cache line.
//    Threads updating different counters still ping-pong the shared line.
class Counters {
    volatile long a;   // thread 1 hammers this
    volatile long b;   // thread 2 hammers this -- but a,b share a line → contention
}
```

```java
// ✅ FIX 1: JDK-supported padding (requires -XX:-RestrictContended for the internal annotation,
//    or use it on exported classes only with care). Pads `a` to its own cache line.
import jdk.internal.vm.annotation.Contended;   // module: java.base internal

class CountersPadded {
    @Contended volatile long a;
    @Contended volatile long b;
}
```

```java
// ✅ FIX 2: manual padding (portable, no flags). 7 longs ≈ 56 bytes + the value ≈ 64-byte line.
class PaddedLong {
    public volatile long value;
    public long p1, p2, p3, p4, p5, p6, p7;   // padding to fill the cache line
}
```

> **Jargon, explained — false sharing & cache line:** CPUs move memory in fixed-size **cache lines** (almost always **64 bytes** on x86-64 and most ARM; some POWER use 128). If two independent variables sit in the *same* line, a write to one **invalidates the other's cached copy** on other cores, forcing reloads — even though the data is logically unrelated. This is **false sharing**: the variables aren't shared, but the *line* is. The fix is to push hot, independently-updated fields onto separate lines via padding.

### 5.7 Choosing the cheapest correct ordering with `VarHandle`

```java
// A cancellation flag: we need progress (the flag must actually be re-read),
// but we do NOT need it to order other variables. "Opaque" is the cheapest mode.
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

class CancellableTask {
    private boolean cancelled;     // plain field, accessed via VarHandle
    private static final VarHandle CANCELLED;
    static {
        try {
            CANCELLED = MethodHandles.lookup()
                .findVarHandle(CancellableTask.class, "cancelled", boolean.class);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    public void cancel() { CANCELLED.setOpaque(this, true); }     // cheap publish
    public void run() {
        while (!(boolean) CANCELLED.getOpaque(this)) {           // cheap re-read, no hoisting
            step();
        }
    }
}
```

Opaque mode guarantees the loop actually observes the cancellation (no register hoisting, coherence per-variable) **without** paying for the full volatile StoreLoad fence — because we don't need to order any *other* memory relative to the flag. This is a genuine, measurable optimization in tight loops, and a senior-signal pattern. (If you also published *data* alongside the flag, you'd need release/acquire or volatile, not opaque.)

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Volatile read** is cheap on x86 (≈ a plain load + compiler fence); **volatile write** is the cost center because of the trailing **StoreLoad** (store-buffer drain, tens of cycles). On ARM both directions cost real fences.
- **Uncontended `synchronized`** is cheap (biased/lightweight locking historically; biased locking was **disabled by default in JDK 15 (JEP 374) and removed in JDK 18 (JEP 442)** — flag: version-specific). Contended locks involve OS-level blocking and are far more expensive.
- **CAS** is cheap when uncontended; under contention the retry loop and cache-line bouncing dominate — hence `LongAdder`/striping.
- **Rule of thumb:** prefer the *weakest correct* tool: `final`/immutability > opaque/acquire-release > volatile > atomics/CAS > locks. But measure — correctness first, always.

### 6.2 Correctness / concurrency

- **Default to immutability.** An all-`final` immutable object sidesteps the JMM almost entirely.
- **Never reason from x86 behavior.** Test on ARM (Graviton, Apple Silicon) or use JCStress, which models weak ordering.
- **`volatile` is not a lock.** It gives visibility + ordering + 64-bit atomicity, but **no mutual exclusion**, so `volatileInt++` is still a race (read-modify-write). Use `AtomicInteger` or a lock.
- **One volatile does not publish a *mutable* object's later mutations** — only the state visible at the moment of the release. Mutating after publication re-introduces races.

### 6.3 Security

- **Improper publication is a security bug**, not just a correctness bug. If an attacker-influenced object is published unsafely, a reader can observe it **half-constructed**, bypassing constructor-enforced invariants (e.g., a partially-initialized security check object). **Always use `final` fields for security-critical immutables** — the freeze guarantee specifically protects against the `this`-escape and partial-init attacks. (This is why `String` is immutable with final fields: a racing thread can never see a half-built `String` pointing at attacker-mutable data.)
- Avoid `this`-escape in constructors of any class with invariants.

### 6.4 Observability

- Lock contention: **JFR** (`jdk.JavaMonitorEnter` events), `jstack` thread dumps (look for `BLOCKED` on monitors), async-profiler `lock` mode.
- False sharing: hard to observe directly; use perf counters (`perf stat -e cache-misses`, `LLC-load-misses`) and JMH microbenchmarks comparing padded vs. unpadded.
- Verify emitted barriers: `-XX:+PrintAssembly` + `hsdis` on the hot method; look for `lock` prefixes / `mfence` (x86) or `dmb`/`ldar`/`stlr` (ARM).

### 6.5 Cost

- Memory: padding (`@Contended`, manual) trades RAM for cache locality — typically +56–120 bytes per padded field. `LongAdder` uses more memory than `AtomicLong`.
- CPU: every unnecessary `volatile`/fence on a hot path is wasted cycles; every *missing* one is a bug. The cost is asymmetric — under-synchronizing is catastrophic, over-synchronizing is merely slow.

### 6.6 Testing

- **JCStress is mandatory** for any custom lock-free or memory-ordering code. Unit tests and even stress loops **cannot reliably surface JMM bugs** — they pass on x86, on your laptop, under low load, then fail in prod on ARM under load. JCStress systematically explores interleavings and runs on weak-memory hardware.
- Run CI on **ARM** (Graviton runners) as well as x86.
- Use `-Xint` / `-XX:TieredStopAtLevel=1` *only to contrast* behavior, never as a "fix."

### 6.7 Production hardening checklist

1. Every shared mutable field has a documented synchronization strategy.
2. No plain-field publication of objects with invariants; use `final` or a release/volatile handoff.
3. DCL uses `volatile` (or replace with the holder idiom).
4. No `this`-escape in constructors.
5. Hot, independently-updated fields are checked for false sharing.
6. Lock-free code has JCStress tests; CI runs on ARM.
7. No reliance on 64-bit non-volatile atomicity.

### 6.8 Anti-patterns

| Anti-pattern | Why it's wrong |
|--------------|----------------|
| Plain flag polled in a loop | Hoisted → infinite spin or stale read. |
| `volatile` to make `count++` thread-safe | RMW is still racy; use atomics/locks. |
| DCL without `volatile` | Publishes half-built object. |
| Synchronizing only the writer or only the reader | HB needs **both** sides on the same lock/volatile. |
| `this`-escape in constructor | Breaks final-field freeze; security risk. |
| "It works on my machine (x86)" | Masks weak-memory bugs. |
| Double-checking a non-`final`, mutated singleton field | Even with volatile, later mutations race. |
| Relying on `Thread.sleep()` for visibility | Sleep creates no HB edge. |

---

## 7. Advanced topics & deep internals

### 7.1 Acquire/release vs. sequential consistency for volatiles

A subtle and famous point: **Java `volatile` is sequentially consistent**, *stronger* than C++'s `memory_order_acquire`/`release`. The extra strength comes from the **StoreLoad** barrier after a volatile write, which gives a single total order over all volatile accesses (the "synchronization order"). The **IRIW (Independent Reads of Independent Writes)** litmus test distinguishes them: with pure release/acquire, two reader threads can disagree on the order of two independent volatile writes; with Java volatile (SC), they cannot. This is why `getAcquire`/`setRelease` on a `VarHandle` are *cheaper but weaker* than `getVolatile`/`setVolatile`.

> **Jargon, explained — IRIW:** Four threads: T1 writes x, T2 writes y, T3 reads x then y, T4 reads y then x. Can T3 see (x=1, y=0) while T4 sees (y=1, x=0)? Under SC: no. Under plain acquire/release: yes. It's the canonical test for whether a model provides a global store order.

### 7.2 Roach motel ordering

The JMM permits "**roach motel**" reordering around synchronized blocks/locks: code can move **into** a critical section but not **out** of it. A statement before a lock acquire may be moved *after* the acquire (into the section); a statement after an unlock may be moved *before* the unlock (into the section). This is sound because it only *adds* mutual exclusion, never removes happens-before. It's why lock-based code can be optimized more than you'd naively expect — and why you can't assume a statement "outside the lock" actually executed outside it.

### 7.3 Lock elision, lock coarsening, biased locking

- **Lock elision:** the JIT proves (via escape analysis) that an object never escapes a thread, so its locks are removed entirely (e.g., `synchronized` on a local `StringBuffer`).
- **Lock coarsening:** adjacent `synchronized` blocks on the same lock are merged into one to amortize acquire/release cost.
- **Biased locking:** (historical) optimized the uncontended-single-thread case by "biasing" a lock to one thread, avoiding atomics. **Disabled by default in JDK 15, removed in JDK 18** — flag: version-specific. Modern lightweight locking (and JDK 24's compact object headers / Lilliput work) changes this landscape; verify per JDK version.

> **Jargon, explained — escape analysis:** A JIT analysis determining whether an object's reference "escapes" the method/thread that created it. If it doesn't escape, the JVM can stack-allocate it, scalar-replace its fields, or elide its locks.

### 7.4 Final fields and reflection / deserialization

The freeze guarantee assumes fields are set in the constructor. **Reflection** (`Field.setAccessible(true)` then `set`), **deserialization** (which bypasses constructors), and **`sun.misc.Unsafe`** can mutate `final` fields *after* construction — breaking the model. `String`'s historical hash-cache works *because* it's a benign race on a non-final field, not a final-field mutation. Mutating final fields post-construction is **unspecified behavior**; the JIT may have constant-folded the old value.

### 7.5 Memory ordering modes recap (the full ladder)

From weakest/cheapest to strongest/most expensive:

1. **Plain** — no guarantees beyond word-tearing safety; full reorder/elimination freedom.
2. **Opaque** — coherence per variable, no elimination/hoisting, no inter-variable ordering.
3. **Release (write) / Acquire (read)** — one-directional ordering; pairs to form HB.
4. **Volatile / SeqCst** — bidirectional + global total order over all such accesses.

Choosing the right rung is the expert's lever. Most code should stay at volatile or use higher-level synchronizers; the lower rungs are for proven hot paths with JCStress backing.

### 7.6 The 64-bit tearing reality

While the spec permits non-volatile `long`/`double` tearing, **all mainstream 64-bit HotSpot builds perform atomic 64-bit access** and most 32-bit-era concerns are gone. But: (a) the spec still permits it, (b) some embedded/32-bit JVMs may tear, (c) JCStress can demonstrate tearing on appropriate targets. **Mark shared 64-bit fields `volatile`** if you need guaranteed atomicity.

### 7.7 `ThreadLocal` and the JMM

`ThreadLocal` sidesteps the JMM by giving each thread its own copy — no sharing, no HB needed. But `InheritableThreadLocal` and contexts passed across thread pools re-introduce publication concerns: the value must be safely published when handed from parent to child / submitter to worker. `java.util.concurrent` executors document the necessary HB edge (submit hb execute).

### 7.8 Virtual threads (Project Loom, JDK 21) and the JMM

Virtual threads (JEP 444) are scheduled onto carrier platform threads. **The JMM applies unchanged** — happens-before is defined over actions, not OS threads. But a virtual thread may *unmount* from one carrier and *remount* on another at blocking points; the runtime ensures the necessary HB edges across mount/unmount, so user code sees consistent state. The practical caution: massive thread counts make under-synchronization bugs *more* likely to manifest, and false sharing across many cells matters more. (Version-specific: Loom finalized in JDK 21.)

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which synchronization tool?

| Need | Use | Why |
|------|-----|-----|
| Read-only shared state, set once | `final` fields / immutable object | Free reads, no fences. |
| Simple visibility flag, no compound op | `volatile` | Cheap, sufficient. |
| Cheapest progress flag, no inter-var order | `VarHandle` opaque | Avoids StoreLoad. |
| Atomic counter, low contention | `AtomicLong` | Lock-free, simple. |
| Atomic counter, high contention | `LongAdder` | Striped, scales. |
| Atomic update of object ref | `AtomicReference` + CAS | Lock-free publication. |
| Mutual exclusion over multiple fields | `synchronized` / `ReentrantLock` | Atomic critical section + HB. |
| Read-mostly, rare writes | `ReentrantReadWriteLock` / `StampedLock` / copy-on-write | Concurrent reads. |
| One-time signal | `CountDownLatch` | HB on countDown→await. |
| Producer/consumer handoff | `BlockingQueue` | Built-in HB + backpressure. |

### 8.2 `volatile` vs `synchronized` vs `Atomic`

| Property | `volatile` | `synchronized` | `AtomicX` |
|----------|-----------|----------------|-----------|
| Visibility / HB | Yes | Yes | Yes |
| Mutual exclusion | No | Yes | No (single-var CAS only) |
| Atomic RMW (`i++`) | No | Yes (in block) | Yes |
| Blocking | No | Yes (can block) | No (CAS spin) |
| Multi-field atomicity | No | Yes | No |
| Cost (uncontended) | Low | Low–moderate | Low |
| Cost (contended) | Low | High (blocking) | Moderate (spin/bounce) |

### 8.3 Immutability vs synchronization

- **Use immutability when** state is set once and read many times; you want zero reader-side cost and inherent thread safety; security invariants matter.
- **Avoid (pure) immutability when** state genuinely mutates frequently and copying is too expensive; then use locks/atomics on a mutable structure or a concurrent collection.

### 8.4 Java JMM vs other models

| Model | Default ordering | Race semantics |
|-------|------------------|----------------|
| **Java JMM** | DRF-SC; volatile = SC | Defined, weak, **no OOTA** |
| **C/C++11** | DRF-SC; configurable orders | **Undefined behavior** |
| **x86 hardware (TSO)** | Strong (store→load reorder only) | N/A |
| **ARM/POWER hardware** | Weak | N/A |

---

## 9. Failure modes & debugging

### 9.1 Symptom catalog

| Symptom | Likely JMM cause | Diagnosis |
|---------|------------------|-----------|
| Loop never exits after "stop" set | Plain flag hoisted | Add `volatile`; inspect with `-XX:+PrintAssembly` to confirm the load is in the loop. |
| Rare NPE / "impossible" partial object | Unsafe publication (DCL w/o volatile, `this`-escape) | Audit publication path; JCStress. |
| Stale reads under load, fine under debugger | Missing HB edge; debugger serializes threads, hiding it | Static review of read/write pairs; JCStress. |
| Counter off by a few | `volatile`-only RMW (lost updates) | Switch to atomic/lock. |
| Works on Intel, fails on Graviton/Apple | x86 TSO masked a missing barrier | Reproduce on ARM; JCStress weak-memory mode. |
| Throughput collapses at high core count | False sharing or hot-line contention | `perf` cache-miss counters; pad fields / use `LongAdder`. |
| Torn 64-bit value (rare) | Non-volatile `long`/`double` race on 32-bit JVM | Make `volatile`. |
| Deadlock | Lock-ordering (not strictly JMM, but adjacent) | `jstack`/`jcmd Thread.print` → "Found one Java-level deadlock". |

### 9.2 The diagnostic toolbox in action

```bash
# Thread dump — find BLOCKED threads and deadlocks
jcmd <pid> Thread.print
jstack <pid>

# JFR recording focused on locks
jcmd <pid> JFR.start name=locks settings=profile duration=60s filename=locks.jfr
jcmd <pid> JFR.dump name=locks filename=locks.jfr
# Then open in JDK Mission Control → "Lock Instances" / "Java Monitor Blocked"

# Cache-miss / false-sharing hints (Linux perf)
perf stat -e cache-references,cache-misses,LLC-load-misses -p <pid> -- sleep 30

# See the actual barriers the JIT emitted (needs hsdis library)
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly \
     -XX:CompileCommand=print,com.example.MyClass::hotMethod MyApp
```

```java
// JCStress skeleton: assert that an unsynchronized read can observe stale state,
// or prove your fix prevents it.
@JCStressTest
@Outcome(id = "1, 42", expect = ACCEPTABLE, desc = "saw published data")
@Outcome(id = "1, 0",  expect = FORBIDDEN,  desc = "saw flag but not data — BUG")
@State
public class PublicationTest {
    int data;
    volatile boolean ready;          // try removing volatile to see FORBIDDEN occur
    @Actor void producer() { data = 42; ready = true; }
    @Actor void consumer(II_Result r) {
        r.r1 = ready ? 1 : 0;
        r.r2 = data;
    }
}
```

### 9.3 Real-world incident patterns

- **The infinite-loop daemon (everywhere):** A background poller uses a plain `boolean shutdown`. Under `-server` JIT, the flag is hoisted; `shutdown()` never stops the loop. Classic; fixed with `volatile`. This is *the* most-reported JMM bug.
- **DCL across the industry (pre-2004):** Before JSR-133, countless singletons used DCL believing it correct. It produced rare half-initialized objects in production; the fix required Java 5 + `volatile`. The "Double-Checked Locking is Broken" declaration (Bacon et al.) is canonical reading.
- **x86→ARM migration surprises (2020s):** Teams moving to AWS Graviton / Apple Silicon found latent races that x86's TSO had masked for years — flags read stale, publications torn — surfacing only on weakly-ordered hardware. The remediation pattern: JCStress on ARM + audit of every shared field.
- **False sharing in queues/disruptors:** High-performance ring buffers (LMAX Disruptor) found producer/consumer cursor fields sharing a cache line, cutting throughput by 2–10×; fixed with cache-line padding (the technique that popularized `@Contended`).

---

## 10. Interview drill

### Q1. What is the Java Memory Model, in one sentence, and why does it exist?
**Model answer:** It's the JLS contract specifying when one thread's memory writes become visible/ordered to another thread, defined via the happens-before relation; it exists because compilers (JIT), CPUs (out-of-order execution, store buffers), and per-core caches all reorder and delay memory operations, so we need a portable set of guarantees that hold on every JVM and CPU.
- *Follow-up: Why not just give us sequential consistency?* Enforcing SC everywhere would require fences on nearly every access, crippling performance. The JMM instead promises DRF-SC: avoid data races and you get SC.
- *Follow-up: What's a data race?* Two conflicting accesses (≥1 write) to the same variable from different threads not ordered by happens-before.
- *Follow-up: Is a data race undefined behavior?* Not in Java — it's defined but weak, with an out-of-thin-air prohibition; unlike C/C++ where it's UB.

### Q2. State the happens-before rules.
**Model answer:** Program order (within a thread); monitor unlock hb subsequent lock; volatile write hb subsequent read; thread `start()` hb the thread's actions; thread's actions hb `join()` return; interrupt hb detection; constructor end hb `finalize()`; and transitivity.
- *Follow-up: Does happens-before mean "earlier in time"?* No — it's purely about visibility/ordering guarantees; two actions can be time-ordered yet unordered by HB.
- *Follow-up: How do these compose to publish data via a flag?* Producer's data write is PO-before its volatile flag write; flag write hb flag read; flag read PO-before data read; transitivity → data write hb data read.

### Q3. What exactly does `volatile` guarantee — and not guarantee?
**Model answer:** Guarantees: visibility + ordering (write hb subsequent read, no reordering across it), and atomic 64-bit access. Does NOT guarantee: mutual exclusion or atomic read-modify-write (`v++` is still racy).
- *Follow-up: How is it compiled?* Roughly StoreStore before + StoreLoad after a write; LoadLoad+LoadStore after a read (JSR-133 cookbook). On x86 reads are nearly free; the write's StoreLoad is the cost.
- *Follow-up: Is Java volatile the same as C++ acquire/release?* No — Java volatile is sequentially consistent (stronger), giving a global order over all volatiles (passes IRIW); release/acquire don't.

### Q4. Walk me through why double-checked locking was broken, and how to fix it.
**Model answer:** `instance = new Holder()` is allocate→construct→assign; the JIT can reorder to allocate→assign→construct, so a thread doing the first un-synchronized null check sees a non-null, half-built object. Pre-Java-5 it was unfixable. Java 5+: make the field `volatile`, so the publishing write hb the racy read and a non-null read implies full construction.
- *Follow-up: Is there a better idiom?* The initialization-on-demand holder class: lazy, thread-safe via class-init semantics, no volatile/synchronized.
- *Follow-up: When would you still use DCL?* When you need a lazily-initialized *instance* (non-static) field where the holder idiom doesn't apply.

### Q5. Explain final-field freeze semantics.
**Model answer:** At constructor end, final-field writes are "frozen" (a StoreStore-like barrier before publishing `this`). A thread that obtains the reference after construction — without a data race during construction — is guaranteed to see correctly initialized final fields (and what they transitively reference), with no synchronization on the reader.
- *Follow-up: What breaks it?* A `this`-escape: leaking `this` from the constructor before the freeze lets others see un-frozen fields.
- *Follow-up: Why is this a security property?* It prevents observing a half-constructed object that violates constructor-enforced invariants — critical for immutable security objects like `String`.

### Q6. What is false sharing and how do you fix it? (senior-signal)
**Model answer:** Two independent variables on the same 64-byte cache line cause cross-core invalidation when either is written, even though the data isn't logically shared — collapsing throughput. Fix by padding hot, independently-updated fields onto separate cache lines (`@Contended` or manual padding), or using striped structures like `LongAdder`.
- *Follow-up: How do you detect it?* JMH padded-vs-unpadded benchmark; `perf` LLC/cache-miss counters; throughput that worsens with more cores.
- *Follow-up: Cache line size assumptions?* Usually 64 bytes (x86, most ARM); some POWER 128. Don't hardcode; `@Contended` handles it.

### Q7. Your code works on x86 but fails on ARM. Why, and how do you prevent it? (senior-signal)
**Model answer:** x86 is TSO (strongly ordered — only store→load reorders), which masks missing synchronization; ARM/POWER are weakly ordered and reorder aggressively, exposing the latent race. Prevention: never reason from x86; place proper happens-before edges; test with JCStress and run CI on ARM hardware.
- *Follow-up: Which volatile direction costs more on ARM vs x86?* On x86, write (StoreLoad). On ARM both reads and writes emit real fences (`ldar`/`stlr`, `dmb`).

### Q8. When would you choose opaque/release-acquire over volatile? (senior-signal)
**Model answer:** When you need progress/visibility for *one* variable but not ordering relative to *other* variables — e.g., a cancellation flag with no associated data handoff. Opaque avoids the expensive StoreLoad, release/acquire give one-directional ordering. Only do this on proven hot paths with JCStress backing; default to volatile otherwise.
- *Follow-up: What does opaque NOT give you?* Inter-variable ordering — you can't use it to publish data alongside the flag.
- *Follow-up: API?* `VarHandle.getOpaque/setOpaque`, `getAcquire/setRelease`, plus `fullFence/acquireFence/releaseFence`.

### Q9. Is `volatile long count; count++;` thread-safe?
**Model answer:** No. `volatile` gives visibility and 64-bit atomicity of individual reads/writes, but `count++` is read-modify-write — three operations — so concurrent increments lose updates. Use `AtomicLong`/`LongAdder` or a lock.
- *Follow-up: What about a single writer, many readers?* Then `volatile` is fine — there's no concurrent RMW.

### Q10. Explain safe publication. What are the safe ways?
**Model answer:** Safe publication ensures other threads see a fully-constructed object with all fields visible. Safe mechanisms: (a) store into a `final` field (freeze); (b) store into a `volatile` field / `AtomicReference`; (c) store while holding a lock and read while holding the same lock; (d) store into a `j.u.c.` concurrent collection; (e) hand off via thread `start`/`join`. Plain-field publication of a mutable/invariant object is unsafe.
- *Follow-up: Why is `start()` a safe handoff?* `start()` hb the new thread's first action, so everything the parent did before start is visible.
- *Follow-up: Does safe publication cover later mutations?* No — only state visible at the publication point; subsequent mutations need their own synchronization.

### Q11. What guarantees do you get from a data race in Java? (senior-signal)
**Model answer:** Java is memory-safe even under races: the JMM forbids out-of-thin-air values, so a racy read returns some value actually written (or the default), never an invented one. But you lose sequential consistency and can observe stale/torn/reordered state. So races are *defined but unusable* — fix them rather than rely on them.
- *Follow-up: How does this differ from C/C++?* There a data race is undefined behavior — the whole program is meaningless.
- *Follow-up: Why does the no-OOTA rule make the formal spec hard?* It requires the causality/commitment procedure (JLS 17.4.8) to distinguish legal speculative reorderings from circular self-justifying ones — the part of the JMM the community still considers under-specified.

### Q12. How do you test memory-ordering code?
**Model answer:** With **JCStress** — the OpenJDK harness that systematically explores interleavings and runs on weak-memory hardware; ordinary unit tests and stress loops can't reliably surface JMM bugs (they pass on x86 under low load and fail in prod on ARM under load). Run CI on ARM too, and inspect emitted barriers with `-XX:+PrintAssembly`.
- *Follow-up: Why can't a tight stress loop catch these?* It usually runs on x86 (strong ordering), single architecture, and doesn't force the rare interleavings; JCStress injects the right pressure and outcome bookkeeping.

---

## 11. Glossary

- **ABA problem:** A CAS succeeds because a value returned to its original after intermediate changes; masked-out mutation. Fixed with versioned references (`AtomicStampedReference`).
- **Acquire:** A read operation (volatile read, lock) acting as a one-way upward barrier; imports published state.
- **Action:** An inter-thread-visible operation the JMM reasons about (read, write, lock, unlock, volatile access, start, join, etc.).
- **As-if-serial:** A single thread always appears to execute in program order to itself, regardless of reordering.
- **Atomic (RMW):** Read-modify-write performed as one indivisible step (e.g., `incrementAndGet`).
- **Biased locking:** Historical lock optimization for uncontended single-thread access; disabled JDK 15, removed JDK 18.
- **Cache coherence:** Hardware protocol (e.g., MESI) keeping per-line values consistent across cores.
- **Cache line:** Fixed-size block (usually 64 bytes) the CPU moves between memory and caches.
- **CAS (compare-and-swap):** Atomic hardware primitive; conditional update used for lock-free code.
- **Causality / commitment:** The JMM's formal procedure (JLS 17.4.8) for deciding legal executions and forbidding OOTA.
- **Class initialization safety:** JLS guarantee that static init runs once, thread-safely, and is visible to subsequent users.
- **Conflicting accesses:** Two accesses to the same variable where at least one is a write.
- **CSE:** Common subexpression elimination — JIT computes a repeated expression once.
- **Data race:** Conflicting accesses from different threads not ordered by happens-before.
- **DCL (double-checked locking):** Lazy-init pattern; correct only with `volatile` (Java 5+).
- **DRF-SC:** Data-race-free ⇒ sequentially consistent — the JMM's central guarantee.
- **Escape analysis:** JIT analysis of whether an object escapes its method/thread; enables lock elision, stack allocation.
- **False sharing:** Independent variables on the same cache line causing cross-core invalidation.
- **Fence / memory barrier:** Instruction constraining reordering (LoadLoad, StoreStore, LoadStore, StoreLoad).
- **Final-field freeze:** Guarantee that correctly-published final fields are visible without reader synchronization.
- **Happens-before (HB):** Partial order; if A hb B, A's effects are visible to and ordered before B.
- **Hoisting:** Moving a loop-invariant read out of a loop (cause of stale-flag spin).
- **IRIW:** Independent Reads of Independent Writes — litmus test separating SC from acquire/release.
- **JIT:** Just-In-Time compiler (C1/C2 in HotSpot) compiling hot bytecode to native code.
- **JMM:** Java Memory Model.
- **JSR-133:** Java 5 redefinition of the JMM (2004).
- **Lock coarsening:** Merging adjacent same-lock synchronized blocks.
- **Lock elision:** Removing locks on non-escaping objects.
- **MESI:** Modified/Exclusive/Shared/Invalid cache-coherence protocol.
- **Monitor:** Per-object intrinsic lock used by `synchronized`.
- **Opaque:** Memory mode guaranteeing coherence/progress per variable, no inter-variable ordering.
- **OOTA (out-of-thin-air):** A read returning a never-written value; forbidden by the JMM.
- **Program order (PO):** Source-order total order of a single thread's actions.
- **Release:** A write operation (volatile write, unlock) acting as a one-way downward barrier; publishes prior state.
- **Roach motel:** Reordering rule allowing code to move into but not out of a critical section.
- **Safe publication:** Publishing an object so others see it fully constructed (final/volatile/lock/concurrent collection/start).
- **Sequential consistency (SC):** Global interleaving respecting per-thread program order; the intuitive model.
- **Store buffer:** Per-core queue of pending writes; source of store→load reordering.
- **StoreLoad barrier:** The most expensive fence; drains the store buffer (after volatile write).
- **Synchronization action:** Volatile access, lock/unlock, start/join — actions in the synchronization order.
- **Synchronization order (SO):** Total order over all synchronization actions in an execution.
- **`this`-escape:** Leaking `this` before construction completes; breaks final-field freeze.
- **TSO (Total Store Order):** x86's strong memory model (only store→load reorders).
- **`Unsafe`:** Internal JDK class for raw memory ops; deprecated in favor of `VarHandle`.
- **VarHandle:** Java 9+ typed handle exposing per-access memory-ordering modes and fences.
- **Virtual threads:** Loom (JDK 21) lightweight threads; JMM applies unchanged.
- **Volatile:** Field modifier giving visibility, ordering, and 64-bit atomicity (no mutual exclusion).
- **Word tearing:** A write to one element disturbing adjacent ones; forbidden by the JMM.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Core bargain:** Data-race-free ⇒ sequentially consistent (DRF-SC). Remove races; then reason sequentially.

**Happens-before primitives:** program order · unlock→lock · volatile write→read · start→thread · thread→join · interrupt→detect · ctor→finalize · **transitivity**.

**Release (publish):** volatile write, unlock, `setRelease`, CAS, `final` freeze.
**Acquire (import):** volatile read, lock, `getAcquire`, CAS.

**`volatile` gives:** visibility + ordering + 64-bit atomicity. **Not:** mutual exclusion, atomic `i++`.

**Safe publication:** `final` field · `volatile`/`AtomicReference` · lock both sides · concurrent collection · thread start/join.

**DCL:** field MUST be `volatile`; or use the holder idiom (preferred).

**Barriers:** LoadLoad, StoreStore, LoadStore, **StoreLoad (expensive — drains store buffer)**. x86 = TSO (volatile read ≈ free, write costs StoreLoad). ARM/POWER weak (both cost real fences).

**Cache line ≈ 64 bytes.** Independent hot fields on one line = false sharing → pad (`@Contended`) or stripe (`LongAdder`).

**Cost ladder (cheap→strong):** plain < opaque < release/acquire < volatile/seqcst.

**Tool picks:** immutable+final (free reads) · volatile (flag) · AtomicLong (counter) · LongAdder (hot counter) · synchronized/Lock (multi-field) · BlockingQueue (handoff).

**Testing:** JCStress (mandatory for lock-free) + run CI on ARM. Inspect with `-XX:+PrintAssembly`.

**Golden rules:** (1) No HB edge ⇒ no guarantee. (2) Synchronize both reader and writer on the *same* monitor/volatile. (3) Never reason from x86. (4) Default to immutability. (5) Prefer the weakest correct ordering — after measuring.

### 12.2 Self-test (no answers — active recall)

1. A producer thread sets `data` then `ready=true` (both plain fields); a consumer reads `ready` then `data`. List *every* outcome the JMM permits, and identify which happens-before edge is missing. Now make it correct two different ways and justify each.

2. Explain precisely why double-checked locking without `volatile` can hand another thread a non-null but half-constructed object. At which step does the reordering occur, and which exact happens-before edge does `volatile` add to forbid the bad outcome?

3. You have a 16-core machine and two `AtomicLong` counters, each updated by 8 dedicated threads, that together run far slower than expected. Diagnose the likely cause, name the perf counter you'd check, and give two distinct fixes with their memory/CPU trade-offs.

4. Your service passes all tests on Intel CI but throws rare NPEs on AWS Graviton. Without seeing the code, what class of bug do you suspect, why does the architecture change expose it, and what is your full remediation plan including how you'd reproduce it deterministically?

5. Compare Java `volatile`, `VarHandle.setRelease/getAcquire`, and `VarHandle.setOpaque/getOpaque` for a single cancellation flag with no associated data. Which is cheapest, which is correct, and what extra guarantee would you need (and which mode would you switch to) if you also published a data object alongside the flag?

6. Define a data race formally, then explain what Java guarantees you *still* get in the presence of one, why that differs from C/C++, and why that very guarantee makes the JMM's formal specification notoriously difficult.

7. Describe final-field freeze semantics and construct a concrete `this`-escape that defeats them. Then explain why this is simultaneously a correctness bug and a security vulnerability.

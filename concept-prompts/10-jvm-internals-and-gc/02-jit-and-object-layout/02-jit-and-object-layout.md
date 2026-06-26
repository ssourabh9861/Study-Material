# JIT Compilation & Object Layout

> JVM Internals & Garbage Collection — Chapter: JIT Compilation & Object Layout
> A definitive, exhaustive reference for senior Java/JVM backend engineers.

---

## 1. Overview & where it fits

### What this is

The **JVM (Java Virtual Machine)** does not run your `.java` source directly. It runs **bytecode** — a compact, platform-independent instruction set produced by `javac`. The JVM is the program that executes that bytecode on a real CPU. The question this chapter answers is: *how does that bytecode actually become fast machine code, and how are your objects physically arranged in memory so the CPU can churn through them efficiently?*

Two big machines are at work:

1. **The execution engine** — which decides, instruction by instruction and method by method, whether to **interpret** bytecode (read-and-do, slow but instant) or to **JIT-compile** it (translate hot bytecode into optimized native machine code, expensive up front but fast forever after). "JIT" stands for **Just-In-Time**: compilation that happens *during* execution, using information gathered at runtime, rather than ahead of time like a C compiler.

2. **Object layout** — how the JVM lays out an object's header and fields in the heap, how it aligns and pads them, and how that physical arrangement interacts with CPU caches. Get this wrong (e.g. **false sharing**) and two threads touching unrelated fields can silently destroy each other's performance.

These two topics live together in this chapter because they are the two halves of "why is my hot path fast or slow on the JVM" — the JIT decides *what instructions* run, object layout decides *how memory feeds the CPU* those instructions act on.

### The problem it solves

- **Ahead-of-time (AOT) compilers** (C, C++, Rust, Go) compile once, before the program runs. They cannot know which branch is taken 99% of the time, which virtual call always resolves to one implementation, or which object never escapes a method. They must be conservative.
- **Pure interpreters** (early Java, CPython) are simple and start instantly, but every bytecode is decoded and dispatched on every execution — orders of magnitude slower than native code in tight loops.

The **JIT** gets the best of both: it starts by interpreting (fast startup, zero compile cost), watches the program actually run, and then compiles only the **hot** parts using *real runtime profile data* — branch frequencies, observed types, null-ness, loop trip counts. This **profile-guided optimization (PGO)** lets the JIT make speculative bets an AOT compiler cannot, like "this call site has only ever seen `ArrayList`, so inline `ArrayList.get` directly." If a bet turns out wrong later, it **deoptimizes** and falls back to the interpreter — safely.

Object layout solves a different but adjacent problem: modern CPUs are bottlenecked on memory, not arithmetic. A main-memory access can cost ~100ns (hundreds of cycles); an L1 cache hit costs ~1ns. The layout of your objects determines how many cache lines you touch and whether threads contend on the same line. Mastering layout is mastering the CPU's memory pipeline.

### When you reach for this knowledge

- You are **benchmarking** and getting nonsense numbers (warmup, deopt, on-stack replacement effects).
- You are optimizing a **hot path** — a request handler, a serialization loop, a market-data feed handler at p99 latency.
- You see **mysterious throughput cliffs** under concurrency (false sharing) or after a deploy (recompilation storms).
- You are writing **low-latency** systems where allocation, escape analysis, and cache behavior dominate.
- You are in a **systems interview** and need to explain tiered compilation, deoptimization, or `@Contended`.

### One-paragraph mental model

> The JVM starts every method as **interpreted** bytecode. It counts how often methods are called and how often loop back-edges execute. When counts cross thresholds, the method becomes **hot** and is queued for compilation by a background compiler thread. **Tiered compilation** runs the cheap, fast-compiling **C1** compiler first (with profiling instrumentation), then promotes the hottest methods to the slow, aggressive **C2** compiler, which uses the gathered profile to perform speculative optimizations (inlining, escape analysis, devirtualization). Compiled code is installed in the **code cache** and used directly. If a speculative assumption is later violated, the JVM **deoptimizes** — discards the compiled code, rebuilds the interpreter frame mid-method (via **on-stack replacement** in reverse), and continues interpreting. Separately, every object you allocate has a fixed **header** plus fields the JVM reorders and pads for alignment; how those bytes fall across CPU **cache lines** decides whether your hot loop flies or stalls.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Skip ahead if a term is already second nature, but every term used later in the chapter is defined here or inline at first use.

### 2.1 From source to running code

```
Foo.java  --javac-->  Foo.class (bytecode)  --classloader-->  JVM
                                                                 |
                                                                 v
                                            [interpreter] <---> [JIT compilers]
                                                                 |
                                                                 v
                                                          native machine code
```

- **`javac`** is the Java *source* compiler. It does almost no optimization — it just translates Java syntax into **bytecode** and does basic checks. Crucially, `javac` is *not* where your performance comes from. People are often surprised that `javac -O` does essentially nothing; optimization on the JVM happens at runtime in the JIT.
- **Bytecode** is the instruction set of the JVM: a stack-based language. For example `iadd` pops two ints off the operand stack and pushes their sum. You can read it with `javap -c Foo.class`.
- A **`.class` file** holds the bytecode for one class plus its **constant pool** (a table of literals, method names, type names referenced by the code).
- The **class loader** reads `.class` files, verifies the bytecode (the **bytecode verifier** ensures it is type-safe and cannot corrupt the JVM), and makes the class available.
- The **execution engine** runs the bytecode. It contains an **interpreter** and one or more **JIT compilers**.

### 2.2 What "interpretation" means

The **interpreter** is a loop: fetch the next bytecode, decode it (figure out what it does), execute its effect, advance the program counter, repeat. HotSpot's interpreter is a *template interpreter*: at startup it generates small machine-code snippets ("templates") for each bytecode and stitches them together, which is faster than a naive switch-based interpreter but still far slower than compiled code because:

- Every bytecode pays decode + dispatch overhead.
- There is no cross-bytecode optimization — no register allocation across a method, no constant folding, no inlining.
- The operand-stack abstraction forces lots of redundant loads/stores.

Rough intuition: interpreted code is commonly **10–100×** slower than well-JIT-compiled code for arithmetic-heavy loops. (Exact ratio is hugely workload-dependent — do not quote it as a fact in an interview without that caveat.)

### 2.3 What "JIT compilation" means

**JIT (Just-In-Time) compilation** translates bytecode into native machine code *while the program runs*. The key word is *while*: the compiler has access to runtime facts an AOT compiler never sees. HotSpot's JIT works at **method granularity** (it compiles whole methods, then inlines callees into them). The output is native code stored in the **code cache** (a special region of native memory, not the Java heap).

The economic tradeoff: compiling costs CPU time and memory *now* to save execution time *later*. So you only want to compile code that runs enough to repay that cost — the **hot** code. Code that runs once (startup, configuration parsing) should stay interpreted; compiling it would be pure waste.

### 2.4 HotSpot — the reference implementation

**HotSpot** is the standard JVM implementation shipped in OpenJDK and Oracle JDK. The name comes precisely from this strategy: find the program's *hot spots* and compile them. When we say "the JVM" in this chapter we mean HotSpot unless stated otherwise. (Other JVMs: **Eclipse OpenJ9** from IBM, **Azul Zing/Prime** with the Falcon LLVM-based JIT, **GraalVM** whose JIT is the Graal compiler written in Java. We flag where they differ.)

### 2.5 The two JIT compilers: C1 and C2

HotSpot historically had two separate JIT compilers, originally shipped as separate "client" and "server" VMs:

- **C1 — the "client" compiler.** Fast to compile, produces decent code. Does basic optimizations (method inlining of small methods, constant folding, simple register allocation). Optimized for **fast startup and low compile latency**. Historically used in desktop/GUI apps that need to feel responsive immediately.
- **C2 — the "server" compiler.** Slow to compile, produces highly optimized code. Does aggressive, speculative, profile-driven optimization (deep inlining, escape analysis, loop unrolling, vectorization, devirtualization). Optimized for **peak throughput** of long-running server processes.

Since JDK 7 (and the default since JDK 8), they work *together* via **tiered compilation** rather than as an either/or choice — covered in §3.

> **Beginner note — "client vs server VM."** Old JDKs had `-client` and `-server` flags selecting which JIT to use. On modern 64-bit JDKs the client VM is gone; you always get the server VM with tiered compilation. The flags are mostly ignored/legacy now.

### 2.6 Profiling and counters (how "hot" is measured)

To find hot spots the JVM keeps **counters**:

- **Invocation counter** — how many times a method has been called.
- **Back-edge counter** — how many times a loop in the method has "looped back" (taken a backward jump). This catches methods that are called *once* but loop a billion times.

When a counter crosses a **threshold**, the method/loop is queued for compilation. The thresholds differ per tier (see §3, §4). The combined call+back-edge logic is sometimes called the **CompileThreshold** machinery.

Beyond raw counts, C1-with-profiling and the interpreter also gather **profile data** into a per-method structure called the **MethodData / MDO (Method Data Object)**: which branch directions are taken, which concrete types appear at each virtual call site (**type profile**), whether a reference was ever null, loop trip counts, etc. C2 consumes this profile.

### 2.7 Cache lines and memory — the layout half

Modern CPUs have a **cache hierarchy**:

- **Registers** — fastest, a few dozen, on-core.
- **L1 cache** — ~32–64 KB per core, ~1 ns access.
- **L2 cache** — ~256 KB–1 MB per core, a few ns.
- **L3 cache** — several to tens of MB, shared across cores, ~10–40 ns.
- **Main memory (RAM)** — gigabytes, ~60–100+ ns.

Memory is moved between these levels in fixed-size chunks called **cache lines**, almost universally **64 bytes** on x86-64 and ARM64. You never load one byte from RAM; you load the whole 64-byte line containing it. This single fact drives all of object layout: *which fields share a cache line* determines how many lines your loop touches and whether two cores fight over the same line.

**Cache coherence:** when multiple cores cache the same line, hardware (e.g. the **MESI protocol** — Modified/Exclusive/Shared/Invalid, the standard cache-coherence state machine) keeps them consistent. If core A writes to a line, core B's copy is invalidated; B must re-fetch. This is correct but *expensive*, and it is the mechanism behind **false sharing** (§7).

### 2.8 The object header and word size

Every Java heap object begins with a **header** the JVM uses for bookkeeping. On HotSpot (pre-JDK 24 layout) it contains:

- **Mark word** — one machine word (8 bytes on 64-bit) holding identity hash code, GC age bits, lock state / lock pointer, and bias info (historically). Its meaning is *multiplexed* depending on the object's lock state.
- **Klass pointer** — a pointer to the object's class metadata (the `Klass` describing its type, vtable, etc.). With **compressed class pointers** (`-XX:+UseCompressedClassPointers`, default on heaps ≤32 GB) this is **4 bytes**, otherwise 8.

So a plain object header is typically **12 bytes** (8-byte mark + 4-byte compressed klass), and arrays add a **4-byte length** field. Objects are padded to an **8-byte alignment** boundary by default, so the smallest object is **16 bytes**. These numbers are central to §4 and §7 and we revisit them precisely there.

> **Version flag:** Project Lilliput (targeting a smaller, e.g. 4–8 byte, header) changes these numbers. JDK 24 introduced experimental compact headers (`-XX:+UseCompactObjectHeaders`). Treat the "12-byte header" as the classic, stable mental model and flag that it is shrinking.

With these foundations we can now go deep.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle of a method from "first executed" to "deeply optimized native code" and back through deoptimization, then do the same for object layout.

### 3.1 Tiered compilation: the five levels

Modern HotSpot uses **tiered compilation** (`-XX:+TieredCompilation`, **on by default** since JDK 8). It blends interpreter, C1, and C2 across **five levels** (0–4):

| Level | Engine | Profiling? | Speed of produced code | Compile cost | Purpose |
|------:|--------|-----------|------------------------|--------------|---------|
| 0 | Interpreter | Yes (basic counters + MDO) | Slowest | None | Cold start, gather initial profile |
| 1 | C1 | **No** profiling | Fast-ish | Very low | "Trivial" methods C2 won't improve; no profiling overhead |
| 2 | C1 | Limited (invocation + backedge counters only) | Fast-ish | Low | Intermediate when C2 is backlogged |
| 3 | C1 | **Full** profiling (branch, type, etc.) | Fast-ish, but slower than L1/L2 due to profiling instrumentation | Low–moderate | The main profiling tier feeding C2 |
| 4 | C2 | No (uses profile from L3) | Fastest | High | Peak optimized code |

> **Why three C1 levels?** Because profiling instrumentation isn't free. Level 3 (C1 + full profile) inserts counters and type-profile probes into the generated code — that code is *slower* than level 1 (C1, no profiling) but produces the rich profile C2 needs. The JVM picks the right C1 variant based on what it's trying to achieve.

**The canonical path** for a hot method:

```
L0 (interpret) --hot--> L3 (C1 + full profiling) --hotter--> L4 (C2, fully optimized)
```

**Common alternate paths:**

- **Trivial method** (tiny, e.g. a getter): `L0 → L1` and stays there. C2 cannot meaningfully improve it, so it skips profiling and the C2 queue entirely. Avoids profiling overhead.
- **C2 queue is full / compiler busy:** `L0 → L3`, and if C2 is backlogged the method may sit at L3 or be sent to `L2` (C1 with light profiling) as a stopgap so it isn't stuck slow while waiting. When C2 catches up it goes to `L4`.
- **Deoptimization:** `L4 → L0` (back to interpreter), re-profile, possibly recompile.

### 3.2 The compilation thresholds and the "tiered" math

With tiered compilation, the relevant flags are **not** the old `-XX:CompileThreshold` (that single flag governs *non-tiered* C2-only mode). Instead, tiered uses:

| Flag | Default (approx) | Meaning |
|------|------------------|---------|
| `-XX:Tier3InvocationThreshold` | 200 | Invocations before L3 compilation considered |
| `-XX:Tier3BackEdgeThreshold` | 60000 | Back-edges before L3 OSR considered |
| `-XX:Tier3CompileThreshold` | 2000 | Combined counter for L3 |
| `-XX:Tier4InvocationThreshold` | 5000 | Invocations before L4 (C2) considered |
| `-XX:Tier4BackEdgeThreshold` | 40000 | Back-edges before L4 OSR |
| `-XX:Tier4CompileThreshold` | 15000 | Combined counter for L4 |
| `-XX:CompileThreshold` | 10000 | **Only used in non-tiered mode** |

These numbers are version-dependent; verify with `java -XX:+PrintFlagsFinal -version | grep Tier`. The exact decision uses a more nuanced formula combining invocation count, back-edge count, and *compile queue load* — the JVM throttles based on how backed up the compiler threads are. Do not memorize the formula; memorize the *shape*: "a few hundred calls to get C1-with-profiling, a few thousand to get C2."

### 3.3 Compiler threads and the compile queue

Compilation happens **asynchronously** on dedicated background **compiler threads** (you'll see them as `C1 CompilerThread0`, `C2 CompilerThread0`, etc. in a thread dump). The flow:

1. Interpreter hits a threshold for method `m`.
2. The JVM enqueues a **compilation task** for `m` at the appropriate tier into the **compile queue**.
3. The application thread *keeps running interpreted* — it does **not** block waiting for the compile (unless you force `-Xbatch`, see §4).
4. A free compiler thread dequeues the task, compiles `m` into native code, and installs it in the **code cache**.
5. The **next** invocation of `m` (or, for OSR, the *current* loop) jumps to the compiled version via the method's entry point patching.

Number of compiler threads defaults based on CPU count (`-XX:CICompilerCount`, with a split between C1 and C2 threads in tiered mode). On a small container with limited CPUs, too few compiler threads can slow warmup; on a huge box, the default may be fine.

> **Beginner note — code cache.** The **code cache** (`-XX:ReservedCodeCacheSize`, default ~240 MB in tiered mode) is native memory holding all JIT output. If it fills up, the JVM **stops compiling**, logs `CodeCache is full. Compiler has been disabled.`, and your app silently degrades to interpreted speed. This is a real production incident (§9).

### 3.4 On-Stack Replacement (OSR)

Normally, a freshly compiled method is used on its *next call*. But consider:

```java
public static void main(String[] a) {
    long sum = 0;
    for (long i = 0; i < 10_000_000_000L; i++) {  // one call, billions of iterations
        sum += i;
    }
    System.out.println(sum);
}
```

`main` is called **once**, so the invocation counter never trips. But the loop back-edge counter explodes. The JVM must be able to swap the *currently executing* method to compiled code **mid-loop, mid-frame** — without restarting `main`. That mechanism is **On-Stack Replacement (OSR)**:

1. The back-edge counter in the interpreted loop trips the OSR threshold.
2. The JVM compiles a special **OSR version** of the method whose entry point is *the loop head* (the bytecode index of the back-edge), accepting the current interpreter state (locals, operand stack) as input.
3. At the next back-edge, the interpreter **migrates** the live state into the compiled frame layout and jumps into the OSR-compiled code, continuing the loop in native code.

OSR-compiled code is typically slightly less optimal than a normally-compiled version (it has an unusual entry shape) and is identified in `-XX:+PrintCompilation` output by the **`%`** marker and an `@ <bci>` (bytecode index) annotation. A method can have *both* a normal compilation and one or more OSR compilations.

> **Practical implication:** Microbenchmarks that do all their work in one giant `main` loop heavily exercise OSR and can mislead you — the compiled-loop code differs from what production (normal call-driven compilation) would produce. This is one of several reasons to use **JMH** (§6), which structures benchmarks so normal compilation applies.

### 3.5 The major JIT optimizations (what C2 actually does)

Once a method is at C2 (level 4), the compiler builds an intermediate representation (HotSpot's C2 uses a "**sea of nodes**" graph IR), runs many passes, and emits machine code. The headline optimizations:

#### 3.5.1 Method inlining

**Inlining** replaces a method *call* with the *body* of the callee, pasted into the caller. This is the single most important JIT optimization because it *enables all the others* — once code is inlined, constant folding, escape analysis, and dead-code elimination can see across what used to be a call boundary.

- The JIT inlines based on size (`-XX:MaxInlineSize`, default 35 bytecodes for non-hot; `-XX:FreqInlineSize`, default 325 bytecodes for hot call sites) and call frequency (from the profile).
- It will **not** inline methods that are too large, recursion that's too deep, or calls it can't resolve to a single target.
- Inlining failures are visible in `-XX:+PrintInlining` with reasons like `too large`, `hot method too big`, `not inlineable`, `callee is too large`.

Example concept: `for (T x : list) total += x.value();` — if `value()` is inlined and `list` is monomorphically `ArrayList<MyT>`, the whole loop can become a tight, branch-free sequence with no call overhead.

#### 3.5.2 Devirtualization

In Java, instance method calls are **virtual** by default (`invokevirtual`/`invokeinterface`): the actual method to run depends on the object's runtime type, requiring a **vtable** lookup. **Devirtualization** turns a virtual call into a direct (and usually inlinable) call using profile data:

- **Monomorphic** call site (profile saw exactly **one** receiver type): the JIT speculatively assumes that type, inserts a cheap **type guard** (a check that the receiver is still that type), and inlines directly. If the guard fails at runtime → deoptimize.
- **Bimorphic** (two types): the JIT may emit two guarded branches.
- **Megamorphic** (many types, profile gave up): falls back to a real vtable/itable dispatch — no inlining. This is why polymorphism on a hot path with many implementations can be slow.

This is **Class Hierarchy Analysis (CHA)** + profile-based speculation. CHA: if only one class implementing an interface is currently loaded, calls can be made direct *without even a guard* — until a second implementation loads, which triggers deoptimization of the dependent code.

#### 3.5.3 Escape analysis + scalar replacement + lock elision

**Escape analysis (EA)** determines whether an object created in a method can **escape** that method — i.e. become reachable from outside (returned, stored in a field, passed to a non-inlined method that might keep it, etc.).

- **NoEscape:** the object never leaves; nobody else can see it.
- **ArgEscape:** passed as an argument but the callee doesn't let it escape further.
- **GlobalEscape:** stored somewhere globally reachable (a static field, the heap reachable by other threads).

If an object **does not escape**, the JIT can:

- **Scalar replacement (SR):** *don't allocate the object at all.* Replace its fields with local variables (scalars) living in registers/stack. This is the real payoff of EA — it eliminates heap allocation and GC pressure entirely for that object. (Note: it is **not** "stack allocation" in the literal sense most people imagine; HotSpot decomposes the object into scalars rather than placing the object on the stack.)
- **Lock elision (a.k.a. lock coarsening's cousin):** if a `synchronized` block locks an object that doesn't escape (so no other thread could ever contend it), the lock is **elided** — removed entirely. Classic example: `StringBuffer` (synchronized) used as a local temp can have its locks removed.
- **Lock coarsening:** merge adjacent `synchronized` blocks on the same object into one larger critical section, reducing lock/unlock churn.

> **Beginner note — why escape analysis matters so much.** Allocation on the JVM is cheap (a pointer bump in the **TLAB** — Thread-Local Allocation Buffer), but GC of short-lived garbage still costs CPU and can cause pauses. Scalar replacement makes the *fastest allocation the one that never happens.* EA is enabled by default (`-XX:+DoEscapeAnalysis`) and is one reason "just allocate a small wrapper object" is often free on a hot path — *if* it doesn't escape and *if* the allocating method gets inlined into a context where EA can see the whole picture.

EA caveats: it is fragile. It only works after inlining (the object and all its uses must be visible in one compiled unit). A single `escape` (e.g. storing into a field, calling a non-inlined virtual method that the compiler must treat conservatively) disables SR for that object. EA also does not currently handle objects that escape into loops in some cases, and it can be defeated by iterators that escape.

#### 3.5.4 Loop optimizations

- **Loop unrolling:** replicate the loop body N times to reduce branch/counter overhead and expose instruction-level parallelism. Controlled internally; you rarely tune it directly.
- **Range-check elimination:** Java mandates array bounds checks (`a[i]` checks `0 <= i < a.length`). C2 proves many checks redundant (e.g. inside a `for (i = 0; i < a.length; i++)` loop the check is provably always true) and removes them.
- **Loop-invariant code motion (LICM):** hoist computations that don't change across iterations out of the loop.
- **Auto-vectorization (SuperWord):** combine scalar operations across iterations into SIMD instructions (e.g. AVX) — `-XX:+UseSuperWord` (default on). E.g. summing a `float[]` can use vector adds.
- **Loop peeling / loop predication:** specialize the first iteration or hoist guards.

#### 3.5.5 Other C2 staples

- **Constant folding & propagation:** compute constants at compile time.
- **Dead code elimination (DCE):** remove code whose result is never used. (Famous benchmark trap: if you compute something and never use it, C2 deletes it — your "benchmark" measures nothing. JMH's `Blackhole` exists to defeat this.)
- **Null-check elimination:** if the profile/CHA proves a reference non-null, drop the implicit null check (which otherwise relies on a SIGSEGV-trapping memory access).
- **Branch prediction hints from profile:** lay out hot branches fall-through, cold branches as forward jumps.
- **Intrinsics:** hand-written, hyper-optimized machine code for specific methods (e.g. `System.arraycopy`, `Math.sin`, `String.indexOf`, `Integer.bitCount`, CRC32, AES). The JIT recognizes the call and substitutes the intrinsic instead of compiling the Java body. `-XX:+PrintInlining` shows `(intrinsic)`.

### 3.6 Deoptimization — the safety net that makes speculation possible

Speculation (devirtualization guards, CHA assumptions, branch-frequency bets, type profiles) is only *safe* because the JVM can undo it. **Deoptimization** discards optimized code and resumes execution in the interpreter at exactly the right point.

**Why deopt happens:**

1. **Uncommon trap / unstable_if:** C2 bet a branch was never taken (compiled it as "this can't happen") and it *did* happen. The compiled code contains an **uncommon trap** that, when hit, deoptimizes.
2. **Class loading invalidates CHA:** C2 assumed a method had only one implementation (made a call direct without a guard). A new subclass loads. The JVM must invalidate all compiled code that relied on that assumption.
3. **Failed type guard:** a devirtualized call's receiver turned out to be a different type than profiled.
4. **Null/range/cast surprise:** a `null` appears where the profile said never, a `ClassCastException` path is reached, etc.
5. **Explicit deopt for debugging / redefinition:** attaching a debugger, `RedefineClasses` (HotSwap), or `-XX:+DeoptimizeALot` (a stress flag).

**How deopt works mechanically (reverse OSR):**

1. The compiled code reaches a deopt point (an uncommon trap or an invalidation).
2. The JVM reconstructs the **interpreter frame(s)** that the optimized frame represents. Because of inlining, *one* optimized frame may correspond to *several* interpreter frames (the inlined methods). The JVM rebuilds all of them using **debug info / scope descriptors** recorded at compile time (a map from machine state → virtual JVM state at each safepoint).
3. Objects that were **scalar-replaced** are **reallocated** (rematerialized) on the heap and their fields repopulated — because the interpreter needs real objects.
4. Execution resumes in the interpreter at the correct bytecode index, with locals and stack restored.
5. The deoptimized compiled method is marked **not entrant** (no new calls enter it) and eventually **zombie/unloaded** from the code cache once no frame is using it.

**Recompilation:** after deopt, the method re-profiles. If the new profile justifies it, C2 recompiles — often with the *uncommon* path now included (so it won't deopt again for the same reason). Repeated deopt/recompile on the same method (a **deoptimization loop**) is a real performance bug, visible as `made not entrant` / `made zombie` floods in `-XX:+PrintCompilation` and as wasted CPU on compiler threads (§9).

### 3.7 Safepoints — the prerequisite for deopt and GC

A **safepoint** is a point in execution where the JVM knows the *exact* state of every thread (all object references precisely located) and threads can be paused. Deoptimization, stop-the-world GC, biased-lock revocation, and thread dumps all require all application threads to reach a safepoint ("safepoint sync"). The JIT inserts **safepoint polls** at method returns and loop back-edges (uncounted loops). A thread spinning in a tight counted loop with no poll can cause a **time-to-safepoint (TTSP)** problem — the whole JVM waits for it. This connects JIT directly to GC pause behavior.

### 3.8 Object layout internally — the allocation and field-packing pipeline

Now the layout half. When `new Foo()` executes:

1. **Resolve `Foo`'s class** (loaded, linked, initialized).
2. **Compute instance size** — done once at class-link time. The JVM lays out the object's fields (see ordering below), adds the header, and rounds the total up to the alignment (`-XX:ObjectAlignmentInBytes`, default 8).
3. **Allocate** — fast path is a **TLAB bump**: each thread has a Thread-Local Allocation Buffer carved from the heap's Eden; allocating is just `top += size; return old_top`. No locking. If the TLAB is exhausted, get a new one (slow path, may trigger GC).
4. **Initialize header** — write the mark word and klass pointer.
5. **Zero the fields** (Java guarantees default values), then run the constructor.

**Field ordering and packing.** The JVM does **not** lay fields out in source-declaration order. By default (`-XX:FieldsAllocationStyle`, and the field reordering logic) it groups fields to minimize padding, typically largest-alignment first:

```
[ header (12 or 16 bytes) ]
[ longs / doubles  (8-byte) ]
[ ints / floats    (4-byte) ]
[ shorts / chars   (2-byte) ]
[ bytes / booleans (1-byte) ]
[ object references (oops: 4 bytes compressed, 8 uncompressed) ]
[ padding to 8-byte alignment ]
```

(The exact policy has changed across versions; the *principle* — pack by size to reduce holes, respect each type's natural alignment — is stable.)

**Alignment** means a field of size N must start at an address that's a multiple of N (so the CPU can load it in one access without crossing boundaries inefficiently). To honor this, the JVM may insert **padding** (unused bytes). For example, after the 12-byte header, a `long` field needs to start at an 8-byte boundary, so 4 bytes of padding may precede it (or a 4-byte field gets slotted in to fill the gap — which is exactly what the packing reorder achieves).

**Compressed oops (ordinary object pointers).** On 64-bit JVMs with heaps ≤ ~32 GB, `-XX:+UseCompressedOops` (default) stores object references as **4-byte** values (a 32-bit offset, scaled by the 8-byte alignment to address up to 32 GB). This halves reference field size and dramatically improves cache density. Above ~32 GB heaps, oops become 8 bytes — a real reason a 33 GB heap can perform *worse* than a 31 GB heap. (`-XX:ObjectAlignmentInBytes=16` can push the compressed-oop ceiling higher at the cost of more padding.)

**Arrays** have the layout: header + 4-byte length + (padding) + elements. Element addressing uses the known element size; reference arrays store compressed oops per element when enabled.

We make all of this concrete with measurements in §4 and §5 (JOL), and exploit it in §7 (false sharing, `@Contended`).

---

## 4. The complete toolkit

This section enumerates the flags, tools, APIs, and commands. Defaults are HotSpot/OpenJDK on a recent LTS (JDK 17/21) on 64-bit; **always verify with `-XX:+PrintFlagsFinal` for your exact build.**

### 4.1 Core compilation control flags

| Flag | Default | Purpose |
|------|---------|---------|
| `-XX:+TieredCompilation` | on (JDK 8+) | Enable C1+C2 tiered pipeline |
| `-XX:-TieredCompilation` | — | Disable tiering → C2-only (no C1, slower warmup, sometimes useful to study C2 alone) |
| `-XX:TieredStopAtLevel=N` | 4 | Cap compilation at level N. `=1` = C1-only (fast startup, e.g. short-lived CLI tools); `=4` = full |
| `-XX:CompileThreshold=N` | 10000 | Calls before C2 compiles — **only in non-tiered mode** |
| `-XX:CICompilerCount=N` | derived from CPUs | Number of compiler threads (split C1/C2 in tiered mode) |
| `-XX:ReservedCodeCacheSize=N` | ~240 MB (tiered) | Max code cache size |
| `-XX:InitialCodeCacheSize=N` | small | Starting code cache |
| `-XX:-UseCodeCacheFlushing` | on by default | Evict cold compiled methods when cache pressured |
| `-Xint` | off | **Interpret only** — disable all JIT (debugging/baseline) |
| `-Xcomp` | off | Compile **everything** on first call (no interpretation; not realistic, gives huge upfront cost) |
| `-Xbatch` / `-XX:-BackgroundCompilation` | off | Compile **synchronously** — app thread blocks for the compile. Makes `-XX:+PrintCompilation` output deterministic for analysis |

### 4.2 Optimization-specific flags

| Flag | Default | Purpose |
|------|---------|---------|
| `-XX:+DoEscapeAnalysis` | on | Enable escape analysis (and thus scalar replacement, lock elision) |
| `-XX:+EliminateAllocations` | on | Scalar replacement of non-escaping objects |
| `-XX:+EliminateLocks` | on | Lock elision/coarsening |
| `-XX:+Inline` | on | Enable inlining at all |
| `-XX:MaxInlineSize=N` | 35 | Max bytecode size of a (non-hot) method to inline |
| `-XX:FreqInlineSize=N` | 325 | Max bytecode size of a *hot* method to inline |
| `-XX:MaxInlineLevel=N` | 9–15 (version) | Max inlining depth |
| `-XX:InlineSmallCode=N` | ~1000–2000 | Don't inline a compiled callee bigger than this many bytes of native code |
| `-XX:+UseSuperWord` | on | Auto-vectorization (SIMD) |
| `-XX:+UseCountedLoopSafepoints` | off (varies) | Insert safepoint polls in counted loops (helps TTSP) |
| `-XX:+OptimizeStringConcat` | on | Optimize `+` string concatenation |
| `-XX:+AggressiveOpts` | removed in later JDKs | (Legacy) enable experimental opts — gone now |

### 4.3 Diagnostic / observability flags

| Flag | Purpose |
|------|---------|
| `-XX:+PrintCompilation` | Log every compilation event to stdout (see §4.6 for format) |
| `-XX:+UnlockDiagnosticVMOptions` | Required to unlock the next several flags |
| `-XX:+PrintInlining` | Log inlining decisions + reasons (needs diagnostic unlock) |
| `-XX:+PrintAssembly` | Disassemble JIT output to assembly (needs **hsdis** disassembler plugin) |
| `-XX:+PrintCompilation2` | More detail |
| `-XX:+LogCompilation -XX:LogFile=hotspot.log` | Emit detailed XML compilation log — the input to **JITWatch** |
| `-XX:+PrintFlagsFinal` | Dump all flags and their resolved values |
| `-XX:+CITime` | Summarize time spent in the compilers |
| `-XX:+PrintCodeCache` | Code cache usage at exit |
| `-XX:+TraceDeoptimization` (debug builds) | Trace deopt events |
| `-XX:+PrintEscapeAnalysis`, `-XX:+PrintEliminateAllocations` (debug/diagnostic) | EA decisions |

### 4.4 Object-layout flags

| Flag | Default | Purpose |
|------|---------|---------|
| `-XX:+UseCompressedOops` | on (heap ≤ ~32 GB) | 4-byte object references |
| `-XX:+UseCompressedClassPointers` | on | 4-byte klass pointer in header |
| `-XX:ObjectAlignmentInBytes=N` | 8 | Heap object alignment (must be power of 2; raising it extends compressed-oop range but wastes memory) |
| `-XX:-RestrictContended` | restricted | Allow `@Contended` on non-JDK classes |
| `-XX:ContendedPaddingWidth=N` | 128 | Padding bytes inserted around `@Contended` fields (note: 128, i.e. **2 cache lines**, to defeat adjacent-line prefetch) |
| `-XX:+UseCompactObjectHeaders` | off (experimental, JDK 24+) | Project Lilliput smaller headers |
| `+UseBiasedLocking` | **removed/disabled** by default since JDK 15 | Legacy lock optimization affecting mark word |

### 4.5 Tools

| Tool | What it does |
|------|--------------|
| **`javap -c -p`** | Disassemble `.class` bytecode (understand what `javac` produced) |
| **`jcmd <pid> Compiler.queue`** | Show current compile queue |
| **`jcmd <pid> Compiler.codecache`** | Code cache stats live |
| **`jcmd <pid> Compiler.codelist`** | List compiled methods |
| **`jcmd <pid> VM.print_compile_queue`** | (alias) |
| **JITWatch** | GUI that parses `-XX:+LogCompilation` output: shows per-method compilation timeline, inlining tree (with reasons), bytecode↔assembly mapping, deopt events, "suggestions" (e.g. "this hot method wasn't inlined because too large"). The premier JIT analysis tool |
| **JMH (Java Microbenchmark Harness)** | The *only* correct way to microbenchmark the JVM — handles warmup, dead-code elimination (`Blackhole`), fork isolation, OSR avoidance. `org.openjdk.jmh` |
| **JOL (Java Object Layout)** | `org.openjdk.jol` — prints exact object layout: header, field offsets, padding, total size, alignment. The definitive layout inspector |
| **async-profiler** | Low-overhead sampling profiler; flame graphs of where CPU goes (interpreted vs compiled frames; can show `_inlined_`) |
| **JFR (Java Flight Recorder)** | Built-in event recorder; includes compilation events, code cache stats, allocation profiling. `jcmd <pid> JFR.start` |
| **`perf` (Linux)** | OS-level profiler; with `-XX:+PreserveFramePointer` and `perf-map-agent` can attribute samples to JIT-compiled Java methods |
| **hsdis** | HotSpot disassembler plugin enabling `-XX:+PrintAssembly` |
| **GraalVM / `-XX:+UseJVMCICompiler`** | Use the Graal JIT instead of C2 (JVMCI = JVM Compiler Interface, lets a Java-written compiler plug in) |

### 4.6 Reading `-XX:+PrintCompilation`

Sample lines:

```
  task#  millis  flags   tier  method                                size(bytes)
   1234     567   n       0    java.lang.Object::<init> (1 bytes)
   1500     611  s!       3    com.acme.Service::handle (142 bytes)
   1700     640   %       4    com.acme.Hot::loop @ 12 (88 bytes)
   1900     701          4    com.acme.Hot::loop (88 bytes)
   2100     760   made not entrant  com.acme.Hot::loop
```

Column-by-column:

- **Number** — unique compilation task id.
- **millis** — time since JVM start when the event occurred.
- **flags** (the cryptic letters):
  - `%` — **OSR** compilation (note the `@ <bci>` showing the loop entry bytecode index).
  - `s` — **synchronized** method.
  - `!` — method has **exception handlers**.
  - `b` — blocking compilation (e.g. under `-Xbatch`).
  - `n` — **native** wrapper method.
  - `made not entrant` — this compiled version is **deoptimized**; no new calls enter it.
  - `made zombie` — the not-entrant code is now reclaimable (no frames use it).
- **tier** — compilation level (0–4) as in §3.1.
- **method** — fully qualified `Class::method`.
- **size** — bytecode size (not native size).

**What to look for:**
- A method appearing repeatedly with `made not entrant` → a **deopt loop** (bad — investigate why).
- Important hot methods never reaching **tier 4** → they may be too big to inline/compile, or the code cache is full.
- A flood of compilations long after startup → workload-shape change or recompilation churn.

---

## 5. Code examples by use case

These span different real scenarios. They are written to be runnable/adaptable. Dependencies: JMH and JOL are on Maven Central (`org.openjdk.jmh:jmh-core`, `org.openjdk.jmh:jmh-generator-annprocess`, `org.openjdk.jol:jol-core`).

### 5.1 Inspecting real object layout with JOL

```java
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

public class LayoutDemo {
    static class Mixed {
        boolean flag;   // 1 byte
        long id;        // 8 bytes
        int count;      // 4 bytes
        byte status;    // 1 byte
        Object ref;     // oop: 4 bytes (compressed) or 8
    }

    public static void main(String[] args) {
        // Prints the VM's memory model details: oop size, header size, alignment.
        System.out.println(VM.current().details());

        // The killer feature: exact field offsets, padding, and total size.
        System.out.println(ClassLayout.parseClass(Mixed.class).toPrintable());
    }
}
```

Typical output (compressed oops, 64-bit) shows the JVM **reordered** the fields (`long` first, then `int`, `oop`, `byte`, `boolean`) to minimize gaps, the 12-byte header, internal padding, and a final pad to a multiple of 8. Run it and *read the actual numbers for your JDK* — this single tool dissolves all confusion about object size. Use `ClassLayout.parseInstance(obj)` to inspect a live object (and see the mark word change when you lock it).

**Why this matters:** if you have millions of small objects (e.g. a graph of nodes), shaving one reference or aligning fields can cut heap by 20–40% and improve cache hit rate. JOL tells you *exactly* what you're paying.

### 5.2 A correct microbenchmark with JMH (warmup, dead-code elimination)

```java
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)      // let the JIT warm up before measuring
@Measurement(iterations = 5, time = 1)
@Fork(2)                               // fresh JVM per fork — avoids cross-bench profile pollution
public class HashBench {

    private String key;

    @Setup
    public void setup() { key = "user:" + System.nanoTime(); }

    @Benchmark
    public int baseline() {
        // Return the value so JMH can sink it; never compute-and-discard (C2 would delete it).
        return key.hashCode();
    }

    @Benchmark
    public void withBlackhole(Blackhole bh) {
        // Blackhole defeats dead-code elimination for void-style work.
        bh.consume(key.hashCode());
        bh.consume(key.length());
    }
}
```

Run with `java -jar target/benchmarks.jar HashBench -prof gc` (the `gc` profiler reports allocation rate — invaluable for spotting whether escape analysis kicked in). **Lessons embedded here:** measure after warmup; consume results; fork for isolation; profile allocations.

### 5.3 Observing escape analysis & scalar replacement

```java
public class EscapeDemo {

    // A small value-like holder.
    static final class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        int sum() { return x + y; }
    }

    // NoEscape: 'p' never leaves makeAndUse → C2 can scalar-replace it (zero allocation).
    static int makeAndUse(int a, int b) {
        Point p = new Point(a, b);     // candidate for elimination
        return p.sum();                // becomes simply a + b
    }

    // GlobalEscape: 'p' is returned → must be heap-allocated.
    static Point makeAndReturn(int a, int b) {
        return new Point(a, b);
    }

    public static void main(String[] args) {
        long acc = 0;
        for (int i = 0; i < 100_000_000; i++) {
            acc += makeAndUse(i, i + 1);          // allocation should vanish
            acc += makeAndReturn(i, i + 1).sum(); // allocation stays
        }
        System.out.println(acc);
    }
}
```

Run twice and compare GC behavior:

```
# EA on (default): makeAndUse allocates ~nothing
java EscapeDemo

# EA off: makeAndUse now allocates a Point every call → GC pressure spikes
java -XX:-DoEscapeAnalysis EscapeDemo
```

Pair with `-verbose:gc` or JFR allocation profiling; you'll see the allocation rate jump dramatically with EA disabled. This is the most convincing demonstration that "small short-lived objects are often free." **Caveat:** EA only fires once `makeAndUse` is inlined and C2-compiled; in the interpreter the object *is* allocated. Hence warmup matters.

### 5.4 Triggering and observing deoptimization

```java
public class DeoptDemo {
    interface Op { int apply(int x); }
    static final class Inc implements Op { public int apply(int x) { return x + 1; } }
    static final class Dbl implements Op { public int apply(int x) { return x * 2; } }

    static int run(Op op, int iters) {
        int acc = 0;
        for (int i = 0; i < iters; i++) acc += op.apply(i); // virtual call site
        return acc;
    }

    public static void main(String[] args) throws Exception {
        Op inc = new Inc();
        // Phase 1: only ever see Inc → call site profiles MONOMORPHIC,
        // C2 devirtualizes + inlines Inc.apply with a type guard.
        for (int r = 0; r < 50_000; r++) run(inc, 1000);

        Thread.sleep(200); // let C2 finish

        // Phase 2: introduce a NEW receiver type → type guard fails → DEOPT.
        Op dbl = new Dbl();
        for (int r = 0; r < 50_000; r++) run(dbl, 1000);

        System.out.println("done");
    }
}
```

Run with `java -XX:+PrintCompilation DeoptDemo` and watch for `run` being compiled to tier 4, then `made not entrant` around the phase transition, then recompiled (now bimorphic/megamorphic, so it won't deopt again the same way). For the full story use `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation` and open `hotspot.log` in **JITWatch** to see the exact uncommon trap and the inlining tree before/after. **Takeaway:** type-stable hot call sites are dramatically faster; introducing a second implementation late can cost a deopt + recompile.

### 5.5 Demonstrating and fixing **false sharing**

False sharing: two threads update two *different* fields that happen to live on the *same 64-byte cache line*. Each write invalidates the other core's copy of the line, ping-ponging it across the interconnect.

```java
import java.util.concurrent.atomic.AtomicLong;

public class FalseSharingDemo {

    // BAD: two counters likely on the same cache line.
    static final class Bad { volatile long a; volatile long b; }

    // GOOD: pad so 'a' and 'b' are guaranteed on different cache lines.
    // 7 longs of padding (7*8=56) + 8 (the value) = 64 bytes per slot.
    static final class PaddedLong {
        volatile long value;
        long p1, p2, p3, p4, p5, p6, p7; // padding (may be removed by dead-field elim in some cases; @Contended is safer)
    }

    static long iterations = 500_000_000L;

    public static void main(String[] args) throws Exception {
        runBad();
        runPadded();
    }

    static void runBad() throws InterruptedException {
        Bad x = new Bad();
        long t = System.nanoTime();
        Thread t1 = new Thread(() -> { for (long i=0;i<iterations;i++) x.a++; });
        Thread t2 = new Thread(() -> { for (long i=0;i<iterations;i++) x.b++; });
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.printf("Bad   : %d ms%n", (System.nanoTime()-t)/1_000_000);
    }

    static void runPadded() throws InterruptedException {
        PaddedLong a = new PaddedLong(), b = new PaddedLong();
        long t = System.nanoTime();
        Thread t1 = new Thread(() -> { for (long i=0;i<iterations;i++) a.value++; });
        Thread t2 = new Thread(() -> { for (long i=0;i<iterations;i++) b.value++; });
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.printf("Padded: %d ms%n", (System.nanoTime()-t)/1_000_000);
    }
}
```

On a multi-core box, `Bad` is commonly **2–10× slower** than `Padded`. (Reliable demonstration should really use JMH; this standalone version shows the shape.) The robust, intent-revealing fix is the next example.

### 5.6 `@Contended` — the JDK's official false-sharing fix

```java
import jdk.internal.vm.annotation.Contended; // module: jdk.internal.misc / requires --add-exports

public class ContendedCounter {
    // Each @Contended field is padded (default 128 bytes, -XX:ContendedPaddingWidth)
    // so it sits alone, free of neighbors on its cache line(s).
    @Contended volatile long producerSeq;
    @Contended volatile long consumerSeq;
}
```

Run with: `--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -XX:-RestrictContended`.

- `@Contended` is what the JDK itself uses internally (e.g. in `Thread`'s thread-local random seed, in `LongAdder`/`Striped64`, in `ForkJoinPool`).
- `@Contended` pads with **128 bytes** by default — *two* cache lines, not one — because some CPUs prefetch the **adjacent** cache line (adjacent-line prefetch), so single-line padding can still suffer false sharing.
- Prefer this over manual long-padding because the JIT/compiler can otherwise eliminate "unused" padding fields, and because layout reordering can defeat hand-rolled padding.

**Real-world:** the LMAX **Disruptor** ring buffer and `java.util.concurrent.atomic.LongAdder` are built around exactly this idea. `LongAdder` spreads a counter across multiple `@Contended` cells so threads rarely touch the same line — that's why it crushes `AtomicLong` under high contention.

### 5.7 Forcing interpreted vs compiled to see warmup cost

```java
public class WarmupDemo {
    static long compute(long n) {
        long s = 0;
        for (long i = 0; i < n; i++) s += (i ^ (i << 1)) % 7;
        return s;
    }
    public static void main(String[] args) {
        for (int round = 0; round < 20; round++) {
            long t = System.nanoTime();
            long r = compute(5_000_000);
            System.out.printf("round %2d: %6.2f ms (r=%d)%n",
                round, (System.nanoTime()-t)/1e6, r);
        }
    }
}
```

Run three ways:

```
java WarmupDemo            # rounds get faster as JIT kicks in (watch the curve)
java -Xint WarmupDemo      # interpreter only: uniformly slow, no warmup curve
java -Xcomp WarmupDemo     # compile-everything: slow first round (huge compile), then fast
```

You'll see the classic **warmup curve** in the default run: the first rounds run interpreted/C1, then C2 compiled code makes later rounds several times faster. This is *the* reason production latency SLOs care about warmup and why benchmarks must warm up.

### 5.8 Disassembling a hot method (advanced, needs hsdis)

```
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly \
     -XX:CompileCommand=print,WarmupDemo.compute WarmupDemo
```

`-XX:CompileCommand=print,Class.method` limits assembly to one method. With hsdis installed you'll see the actual x86/ARM, confirming range-check elimination, unrolling, and intrinsics. This is how you *prove* what the JIT did rather than guessing.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Warmup is real.** Long-running servers reach peak only after thousands of executions of each hot path. Plan for it: pre-warm critical paths at startup (synthetic traffic / a warmup phase) before joining a load balancer. Tools/options: application-level warmup loops; `-XX:TieredStopAtLevel`; **AppCDS / dynamic CDS** (Class Data Sharing) to speed class loading; **JEP 483 Ahead-of-Time Class Loading & Linking** (Project Leyden) and experimental AOT-cache features reduce warmup in newer JDKs.
- **Keep hot methods inlinable.** A monster 2000-bytecode method won't inline and may not even compile well. Decompose hot paths into small methods the JIT can inline freely. Counter-intuitively, *more, smaller methods* often run faster on the JVM than one giant method.
- **Keep call sites type-stable (monomorphic).** Megamorphic virtual calls on hot paths defeat inlining and devirtualization. If a hot interface has 5+ implementations all hitting one call site, consider design changes (specialization, sealed hierarchies, batching by type).
- **Don't fight escape analysis.** Avoid unnecessarily letting small temporaries escape (storing them in fields, passing to non-inlinable virtual methods). But also don't contort code for EA — verify with allocation profiling before optimizing.
- **Mind the code cache.** On large microservice fleets with huge codebases (lots of frameworks, Spring proxies, generated classes), 240 MB can fill. Monitor and raise `ReservedCodeCacheSize` if needed.

### 6.2 Correctness & concurrency

- **The JMM (Java Memory Model) governs visibility, not the JIT's whims.** The JIT may reorder, eliminate, and hoist operations aggressively — but it must preserve **as-if-serial** semantics for a single thread and honor `volatile`, `synchronized`, and `final` field semantics across threads. Bugs from "the JIT optimized away my busy-wait flag" are almost always *missing `volatile`* — the JIT legally hoisted the read out of the loop because the field wasn't declared volatile. **Fix: declare shared mutable flags `volatile`.**
- **Lock elision is safe** precisely because EA proves the lock object can't be seen by another thread. You can't "lose" correctness from it; if EA is unsure, it keeps the lock.
- **`final` fields** get special JMM treatment (safe publication) and let the JIT treat them as constants after construction — prefer `final` for both correctness and speed.

### 6.3 Memory & layout

- Use **JOL** to verify object sizes before assuming. Reorder/trim fields on hot, high-count object types.
- Keep heaps **≤ ~32 GB** to retain compressed oops unless you genuinely need more; the jump to 8-byte oops can erase the benefit of extra heap.
- For arrays of small structs, consider **Structure-of-Arrays (SoA)** instead of **Array-of-Structs (AoS)** when you iterate one field across many elements — SoA keeps the touched field contiguous in cache. (Project Valhalla's **value classes / primitive classes** aim to give flat, header-free layouts natively — flag as future/preview.)

### 6.4 Security

- `-XX:+PrintAssembly` and `LogCompilation` can leak code structure; keep diagnostic dumps out of shared/prod logs.
- The bytecode verifier and JIT are part of the trusted base; running with `-Xverify:none` (now `-noverify`, deprecated) to "speed startup" disables safety checks — don't, in any untrusted-code scenario.
- `@Contended` requires exporting internal modules; doing so broadly (`--add-exports`) widens the attack/maintenance surface — scope it narrowly.

### 6.5 Observability

- Capture **JFR** continuously in production (low overhead); it records compilation, code cache, deopt, and allocation events.
- Alert on **`CodeCache is full`** log lines and on code-cache occupancy nearing the reserved size.
- Watch for **deopt storms** (repeated `made not entrant`) via `LogCompilation` periodically or JFR's deoptimization events.
- For CPU attribution, **async-profiler** flame graphs distinguish interpreted vs compiled and show inlined frames.

### 6.6 Cost

- Compiler threads consume CPU; on tiny containers (1–2 vCPU) heavy compilation competes with app work, worsening tail latency during warmup. Consider `-XX:TieredStopAtLevel=1` for short-lived jobs, or AOT/CDS to amortize.
- The code cache and compiled code consume **native** memory (outside `-Xmx`) — account for it in container memory limits or risk OOM-kill.

### 6.7 Testing

- **Never** microbenchmark with `System.nanoTime()` around a raw loop in `main` (OSR + DCE + no warmup = lies). Use **JMH**.
- Test correctness with the JIT *on* and *off* (`-Xint`) for concurrency code — JIT-on can expose missing `volatile`/memory-model bugs that `-Xint` hides (because the interpreter happens not to reorder).
- Use `-XX:+DeoptimizeALot` / `-XX:+StressLCM` (debug builds) in stress testing to flush out deopt-related issues.

### 6.8 Production hardening checklist

- Set explicit `-Xmx`, keep heap ≤32 GB for compressed oops if possible.
- Size `ReservedCodeCacheSize` for your codebase; monitor it.
- Enable continuous JFR.
- Pre-warm before serving traffic; tie readiness probes to "warmed up."
- Pin JDK version; flag every version-specific flag in your runbook (defaults change between LTS releases).

### 6.9 Anti-patterns

| Anti-pattern | Why it hurts |
|--------------|--------------|
| Benchmarking without warmup | Measures interpreter/OSR, not steady state |
| Compute-and-discard in benchmarks | C2 deletes it (DCE) → measures nothing |
| Giant monolithic hot methods | Won't inline; poor C2 results |
| Megamorphic hot call sites | No devirtualization/inlining |
| Hand-rolled `long` padding for false sharing | Fields can be reordered/eliminated; use `@Contended` |
| Missing `volatile` on a busy-wait flag | JIT hoists the read → infinite loop |
| 33 GB heap | Loses compressed oops; can be slower than 31 GB |
| Premature EA "optimizations" | Fragile, hard to reason about; profile first |
| Ignoring `CodeCache is full` warnings | Silent fallback to interpreted → latency cliff |

---

## 7. Advanced topics & deep internals

### 7.1 The mark word in detail (and biased locking's demise)

The 64-bit **mark word** is a tagged union. Its low bits encode the lock state:

- **Unlocked / neutral:** holds identity hashcode (if computed) + GC age bits + lock tag.
- **Lightweight (thin) locked:** holds a pointer to the lock record on the locking thread's stack.
- **Heavyweight (inflated) locked:** holds a pointer to an OS-level **monitor** (an `ObjectMonitor`).
- **Biased (legacy):** historically held a thread id to make repeated locking by one thread nearly free.

**Biased locking** was **disabled by default in JDK 15 (JEP 374) and removed in JDK 18 (JEP 425)** because its bookkeeping (bias revocation requires a safepoint) became a net loss on modern hardware and complicated the codebase. *Interview-relevant:* if asked "what's in the mark word," mention biased locking is historical now.

Computing `System.identityHashCode(obj)` *stores* the hash in the mark word (lazily). This interacts with locking: an object that's been hashed can't be biased (one reason for `OBJECT.hashCode` affecting layout/locking subtly).

### 7.2 Compressed oops encoding modes

Three modes depending on heap base/size:

1. **32-bit (zero-based, unscaled):** heap ≤ 4 GB → oop is the raw 32-bit address; no decode arithmetic.
2. **Zero-based scaled:** heap ≤ 32 GB and base can be 0 → decode is `oop << 3` (shift by `log2(alignment)`), no add.
3. **Non-zero base scaled:** decode is `base + (oop << 3)` — an extra add per dereference.

The JVM tries to place the heap so it can use the cheapest mode. Encoding mode is logged at startup with `-Xlog:gc+heap+coops` (or older `-XX:+PrintCompressedOopsMode`). Raising `ObjectAlignmentInBytes` to 16 lets compressed oops address up to 64 GB at the cost of more padding per object.

### 7.3 Why scalar replacement is *not* stack allocation

A common misconception: "EA puts the object on the stack." HotSpot does **scalar replacement** — it *deletes the object* and replaces its fields with independent SSA values (which the register allocator may keep in registers or spill to the stack). There is no `Foo` object anywhere; there are just `x` and `y` locals. Consequences:

- If the method deoptimizes (e.g. an inlined callee triggers an uncommon trap), the object must be **rematerialized** on the heap so the interpreter sees a real object — the JVM does this transparently using the recorded scope info.
- Partial escape analysis (Graal does this more aggressively than C2) can keep an object scalarized on the common path and only materialize it on the rare escaping path.

### 7.4 Graal vs C2

**GraalVM's Graal compiler** is a JIT written in Java that plugs in via **JVMCI (JVM Compiler Interface)** to replace C2. It does more aggressive **partial escape analysis**, better speculative optimizations for some workloads (notably with many polymorphic/megamorphic calls, e.g. Scala, Truffle-based languages), and is the basis of **GraalVM Native Image** (AOT-compiling Java to a standalone binary with the **SubstrateVM** runtime — which has *no JIT*, hence great startup but no peak-throughput PGO unless using profile-guided AOT). Enable the JIT form with `-XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler` on a Graal-enabled JDK. Tradeoff: Graal as JIT can have higher compile-time CPU and memory (it's a big Java program itself).

### 7.5 Speculative optimizations & uncommon traps catalog

C2 plants **uncommon traps** for many speculations: `unstable_if` (branch never seen), `class_check`/`bimorphic` (type guard), `null_check`, `range_check`, `predicate` (loop guards), `class_loading` (CHA invalidation), `unloaded`/`unreached` (code paths never executed). Each trap, when sprung, deoptimizes and is recorded in the MDO so the recompile is less speculative. The per-method/per-bci trap counts feed a policy: too many traps of a kind at a bci → C2 stops speculating there (`-XX:PerMethodTrapLimit`, `-XX:PerBytecodeTrapLimit`).

### 7.6 Tiered compilation profiling overhead and "profile pollution"

Level-3 (C1+profile) code is *slower* than level-1 because of instrumentation. In a process with *many* hot methods, a lot of time is spent at L3 generating profiles. Also, **profile pollution**: if a method is used in two very different ways (e.g. a generic `Comparator.compare` called with many types), the merged profile is megamorphic and C2 can't specialize — splitting call sites or using distinct methods can help. JMH `@Fork` isolates benchmarks specifically to avoid cross-benchmark profile pollution within one JVM.

### 7.7 Inlining budget mechanics

Inlining is bounded by overlapping limits: `MaxInlineSize` (small callees always considered), `FreqInlineSize` (hot callees up to 325 bytecodes), `MaxInlineLevel` (depth), `InlineSmallCode` (don't inline already-large compiled callees), and the overall compiled-method size (`-XX:MaxNodeLimit`/`NodeCountInliningCutoff` internal limits). When a hot method is "too big to inline," consider extracting the *cold* portions into separate methods (the **"slow path out of line"** pattern) so the hot core stays small enough to inline — exactly how the JDK writes its own hot methods (e.g. `ArrayList.add` keeps `grow()` separate).

### 7.8 Array layout, header, and alignment edge cases

- Reference arrays of length `n` with compressed oops: `header(12) + length(4) + n*4`, padded to 8.
- `boolean[]` uses **1 byte per element** (not bit-packed) — a common surprise; for bitsets use `java.util.BitSet` or `long[]`.
- Multi-dimensional arrays are arrays-of-arrays (not contiguous) — `int[1000][1000]` is 1000 separate row objects, hurting cache locality vs a flat `int[1_000_000]` with manual indexing.

### 7.9 Project Lilliput & Valhalla (future-flagged)

- **Lilliput** shrinks the object header (mark+klass) toward 64 bits total (vs current ~96), saving memory across the whole heap. Experimental compact headers in JDK 24 (`-XX:+UseCompactObjectHeaders`). Will change all the "12-byte header / 16-byte minimum object" numbers — flag this in any answer.
- **Valhalla** adds **value classes** (identity-free) enabling **flat, inlined layouts** (no header, no indirection) for arrays of small structs — directly attacking AoS cache problems and EA fragility. Preview-stage; not stable as of this writing.

### 7.10 Counted vs uncounted loops & safepoints

C2 treats `int`-bounded counted loops specially (can elide per-iteration safepoint polls for performance) — which historically caused TTSP pauses when a long counted loop didn't reach a safepoint, stalling GC. `-XX:+UseCountedLoopSafepoints` inserts polls. `long`-counted loops behave differently. This is a deep but real interaction between JIT loop handling and GC pause latency.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Interpreter vs C1 vs C2 — when each wins

| Dimension | Interpreter (L0) | C1 (L1–L3) | C2 (L4) |
|-----------|------------------|------------|---------|
| Startup latency | Best | Good | Worst (compile cost) |
| Peak throughput | Worst | Medium | Best |
| Compile CPU/mem | None | Low | High |
| Code quality | None | Decent | Aggressive (EA, vectorize, devirt) |
| Best for | Cold/rarely-run code | Fast startup; medium-lived | Long-running hot paths |

### 8.2 Tiered vs non-tiered vs C1-only vs `-Xint`/`-Xcomp`

| Mode | Flag | Use when… | Avoid when… |
|------|------|-----------|-------------|
| Tiered (default) | `-XX:+TieredCompilation` | Almost always | — |
| C2-only | `-XX:-TieredCompilation` | Studying C2 in isolation; some niche throughput-only tuning | Startup-sensitive apps (slow warmup) |
| C1-only | `-XX:TieredStopAtLevel=1` | Short-lived CLIs/jobs, fast-startup serverless | Long-running throughput-critical services |
| Interpret only | `-Xint` | Debugging, deterministic baselines | Any real workload (10–100× slower) |
| Compile all | `-Xcomp` | Forcing compilation for analysis | Production (huge startup cost, unrealistic profiles) |

### 8.3 Reducing warmup pain

| Technique | Mechanism | Tradeoff |
|-----------|-----------|----------|
| App-level pre-warming | Run synthetic traffic before serving | Extra startup time; must mimic real shapes |
| AppCDS / dynamic CDS | Share parsed class metadata across runs | Setup; class list maintenance |
| Project Leyden AOT cache (newer JDKs) | Cache loaded/linked classes + profiles | Version-specific; evolving |
| GraalVM Native Image | AOT compile to binary; no JIT warmup | No peak PGO; closed-world; build complexity |
| `TieredStopAtLevel=1` | Skip slow C2 compiles | Lower peak throughput |

### 8.4 False-sharing mitigation choices

| Approach | Pros | Cons |
|----------|------|------|
| `@Contended` | Official, robust, JIT-aware, 128B (2-line) padding | Needs `--add-exports`; internal API |
| Manual `long` padding | No special flags | Fragile (reorder/eliminate); single-line may not suffice |
| `LongAdder` / striping | Built-in, scales under contention | More memory; slightly slower single-thread read (sum) |
| SoA / data restructuring | Fixes locality and sharing together | Code complexity |

### 8.5 AoS vs SoA

- **AoS (Array of Structs):** `Particle[]` — good when you use *all* fields of each element together (OOP-natural).
- **SoA (Struct of Arrays):** `float[] x, y, z` — good when you sweep *one* field across many elements (vectorizable, cache-dense). Use when the hot loop touches a subset of fields over a large collection.

---

## 9. Failure modes & debugging

### 9.1 Code cache exhaustion

**Symptom:** log line `Java HotSpot(TM) ... warning: CodeCache is full. Compiler has been disabled.` followed by `Try increasing the code cache size using -XX:ReservedCodeCacheSize=`. Throughput silently collapses (everything runs interpreted), latency p99 explodes.
**Diagnose:** `jcmd <pid> Compiler.codecache`; JFR code-cache events; `-XX:+PrintCodeCache`.
**Fix:** raise `-XX:ReservedCodeCacheSize` (e.g. 512m); ensure `UseCodeCacheFlushing` is on; reduce class/code bloat (fewer dynamic proxies, lambdas, generated classes); segmented code cache (`-XX:+SegmentedCodeCache`, default) keeps profiled/non-profiled/non-method code in separate segments to reduce fragmentation.
**Real story:** large Scala/Spring apps with tens of thousands of compiled methods routinely hit the 240 MB default; the fix is monitoring + a higher cap.

### 9.2 Deoptimization storm

**Symptom:** CPU burned on compiler threads, throughput sawtooths, `-XX:+PrintCompilation` shows a method repeatedly `made not entrant` → recompiled → `made not entrant`.
**Cause:** unstable profile — a hot call site that keeps seeing new types; a branch that the profile said never-taken but is taken in bursts; repeated class loading invalidating CHA (e.g. dynamic class generation in a hot loop).
**Diagnose:** `-XX:+LogCompilation` → **JITWatch** to see which uncommon trap fires and on which bci; JFR deoptimization events.
**Fix:** stabilize the call site (avoid late-introduced new implementations on hot paths), avoid generating classes in steady state, sometimes `-XX:PerMethodTrapLimit` tuning (rarely).

### 9.3 The "my loop never exits" / hoisted-read bug

**Symptom:** a thread spins forever after another thread set a `boolean running = false` flag.
**Cause:** `running` isn't `volatile`; the JIT legally hoisted the read out of the loop (loop-invariant) and the thread never re-reads it.
**Diagnose:** reproduce under `-Xint` — it "works" (interpreter re-reads each time), under JIT it hangs. That divergence is the tell.
**Fix:** `volatile boolean running;` (or an `AtomicBoolean`).

### 9.4 Benchmark lies

**Symptoms:** "this code runs in 0 ns" (DCE deleted it); numbers change wildly between runs (no warmup / OSR); a `final static` constant folds the whole benchmark.
**Fix:** JMH with `Blackhole`, `@State`, `@Fork`, warmup; verify with `-prof gc` and `-prof perfasm`.

### 9.5 Throughput cliff under load (false sharing)

**Symptom:** adding threads *decreases* throughput; a counter or sequence number is hammered by multiple threads.
**Diagnose:** `perf c2c` (Linux) detects cache-line contention and points at the exact line/offset; async-profiler with hardware counters; JOL to see field offsets sharing a line.
**Fix:** `@Contended` / `LongAdder` / restructure (see §8.4).

### 9.6 Sudden slowdown after a deploy/config change

**Cause candidates:** lost compressed oops (heap crossed 32 GB), `-Xint`/`TieredStopAtLevel` accidentally set, code cache shrunk, EA disabled by a flag, a new megamorphic call site introduced.
**Diagnose:** diff `-XX:+PrintFlagsFinal` between good/bad runs; check startup `coops` log; compare JFR compilation profiles.

### 9.7 Time-to-safepoint (TTSP) pauses

**Symptom:** GC logs show long "time to safepoint" even though GC work is short; a long counted loop without safepoint polls is the culprit.
**Diagnose:** `-Xlog:safepoint` (TTSP timings); look for a thread in a tight `int` loop.
**Fix:** break the loop, add a poll-y operation, or `-XX:+UseCountedLoopSafepoints`.

---

## 10. Interview drill

**Q1. Walk me through what happens from `new MyService(); service.handle(req)` the first time vs the millionth time.**
*Model answer:* First time, the class is loaded/verified/initialized; `handle` runs **interpreted** (level 0) while counters accumulate and an MDO collects profile (branch directions, receiver types). After ~hundreds of calls it's compiled by **C1 with full profiling (level 3)**; after a few thousand, the rich profile triggers **C2 (level 4)** which inlines callees, devirtualizes monomorphic calls with type guards, runs escape analysis (scalar-replacing non-escaping temporaries, eliding uncontended locks), eliminates range checks, and emits optimized native code into the code cache. The millionth call jumps straight into that native code — unless a speculative assumption was violated, triggering deoptimization.
*Follow-ups:*
- *What if `handle` is called once but loops a billion times?* → **OSR**: the back-edge counter trips, an OSR-compiled version entered at the loop head replaces the running frame mid-loop.
- *What invalidates the compiled code?* → uncommon traps (unstable branch, failed type guard, null/range surprise), CHA invalidation on class load, debugger attach / HotSwap.
- *Where does the native code live, and what if there's no room?* → the **code cache** (native memory, ~240 MB default); if full, compilation stops and the app degrades to interpreted.

**Q2. Explain tiered compilation and why there are three C1 levels.**
*Model answer:* Tiering blends interpreter + C1 + C2 across levels 0–4. C2 needs a profile, and the cheapest way to get a *good* profile while still running reasonably fast is C1-with-full-profiling (level 3). But profiling instrumentation slows code, so trivial methods C2 can't improve go to **level 1 (C1, no profiling)** to avoid that overhead, and **level 2 (C1, light profiling)** serves as a stopgap when the C2 queue is backed up. The canonical path is L0→L3→L4.
*Follow-ups:*
- *Why not always go straight to C2?* → C2 compiles are expensive and need a profile; jumping straight there hurts startup and produces worse code without runtime data.
- *What are the rough thresholds?* → ~200 invocations for L3, ~5000 for L4 (Tier3/Tier4 thresholds), modulated by compile-queue load; non-tiered C2-only uses `CompileThreshold≈10000`.

**Q3. What is escape analysis and what three things can it enable?**
*Model answer:* EA proves whether an object created in a method can become visible outside it. If **NoEscape**, the JIT can (1) **scalar-replace** it (no heap allocation — fields become registers/locals), (2) **elide locks** on it (no thread can contend an unescaped object), and (3) enable **lock coarsening**. It only works after inlining makes the object and all its uses visible in one compiled unit, and any escape (returning it, storing in a field, passing to a non-inlined virtual method) disables scalar replacement.
*Follow-ups:*
- *Is scalar replacement the same as stack allocation?* → No — HotSpot decomposes the object into independent scalars; there's no object at all. On deopt it must be **rematerialized** on the heap.
- *How do you prove it happened?* → Allocation profiling (JMH `-prof gc`, JFR) before/after `-XX:-DoEscapeAnalysis`; allocation rate should drop to near zero for the eliminated object.

**Q4. (Senior signal) When would you turn off tiered compilation or stop at C1, and why?**
*Model answer:* Stop at C1 (`TieredStopAtLevel=1`) for **short-lived processes** — CLIs, batch jobs, serverless functions — where the program never runs long enough to repay C2's expensive compiles; you trade peak throughput (which you never reach) for faster startup and lower compile-CPU. Pure C2 (`-TieredCompilation`) is mostly a *study/benchmark* tool now; it slows warmup with little upside on modern JDKs. The real production lever for startup is usually CDS/AppCDS or AOT (Leyden/Native Image), not toggling tiers.
*Follow-ups:*
- *Tradeoff of Native Image?* → great startup and low memory, but no JIT means no runtime PGO → lower peak throughput, plus closed-world constraints (reflection config, no dynamic class loading).
- *Risk of `-Xcomp`?* → unrealistic: compiles cold code, no real profiles, huge startup cost; never use in prod.

**Q5. Explain false sharing and how you'd fix it.**
*Model answer:* Two threads writing two *different* fields that share one 64-byte **cache line** cause the line to ping-pong between cores via the coherence protocol — each write invalidates the other core's copy. Throughput can drop multi-fold even though the fields are logically independent. Fix by ensuring contended fields sit on separate cache lines: `@Contended` (default 128-byte / two-line padding), `LongAdder`-style striping, or data restructuring. `@Contended` is preferred because manual padding fields can be reordered or eliminated and single-line padding can still suffer adjacent-line prefetch.
*Follow-ups:*
- *Why 128 bytes, not 64?* → some CPUs prefetch the adjacent cache line, so two lines of isolation are needed.
- *How do you detect it in prod?* → `perf c2c` on Linux pinpoints the contended line and offset; JOL confirms field layout.

**Q6. (Senior signal) Your service's p99 latency is great after an hour but terrible right after deploy. Diagnose and remediate.**
*Model answer:* Classic **warmup**: fresh JVM runs interpreted/C1 until hot paths reach C2, so early requests are slow. Remediate by (a) **pre-warming** with synthetic traffic before the instance joins the load balancer and gating readiness on warmth, (b) **AppCDS/dynamic CDS** to cut class-loading time, (c) newer-JDK **AOT cache/Leyden** to carry profiles/linkage across restarts, (d) staggered/canary rollouts so not all instances warm up simultaneously. Verify with JFR compilation events and a latency-vs-uptime curve.
*Follow-ups:*
- *Why not just `-Xcomp`?* → compiles everything cold without profiles → worse code and a massive startup stall.
- *Could it be deopt, not warmup?* → check `PrintCompilation` for `made not entrant` storms; if the slowdown recurs *mid-run*, it's deopt churn, not initial warmup.

**Q7. What's in a Java object header and how big is a small object?**
*Model answer:* Header = **mark word** (8 bytes: hashcode/GC age/lock state) + **klass pointer** (4 bytes compressed, default). So ~12-byte header; objects align to 8 bytes, making the smallest object **16 bytes**. Arrays add a 4-byte length. The JVM reorders fields (largest-alignment first) to minimize padding. Reference fields are 4 bytes with compressed oops (heap ≤ ~32 GB).
*Follow-ups:*
- *Why does a 33 GB heap sometimes perform worse than 31 GB?* → crossing ~32 GB disables compressed oops → 8-byte references → larger objects, worse cache density.
- *What changed recently?* → Project **Lilliput** (JDK 24 experimental compact headers) shrinks the header, changing these numbers; **biased locking** (mark-word state) was removed in JDK 18.

**Q8. How does deoptimization work mechanically, and why is it required for JIT speculation?**
*Model answer:* Speculation (devirtualization guards, CHA assumptions, branch bets) is only safe if it can be undone. On a violated assumption the JVM hits an **uncommon trap**, reconstructs the interpreter frame(s) — possibly several, since one optimized frame can represent multiple inlined methods — using **scope/debug info** recorded at safepoints, **rematerializes scalar-replaced objects** on the heap, marks the compiled code **not entrant**, and resumes in the interpreter at the exact bytecode index. The method re-profiles and may recompile less speculatively.
*Follow-ups:*
- *Cost?* → a single deopt is cheap-ish, but **deopt storms** waste compiler CPU and cause latency sawtooth.
- *How to observe?* → `LogCompilation` + JITWatch; JFR deoptimization events; `PrintCompilation` `made not entrant`.

**Q9. Why are megamorphic call sites slow, and how do monomorphic ones get optimized?**
*Model answer:* A **monomorphic** call site (profile saw one receiver type) gets **devirtualized**: the JIT inserts a cheap type guard and **inlines** the target, unlocking further optimization. A **megamorphic** site (many types) can't be devirtualized or inlined; it falls back to a real vtable/itable dispatch — an indirect call that also blocks downstream optimizations. So hot polymorphic dispatch with many implementations is expensive.
*Follow-ups:*
- *Bimorphic?* → two guarded branches, still inlinable.
- *Design fixes?* → keep hot interfaces effectively monomorphic (specialize, batch by type, sealed hierarchies); split a shared generic method whose profile is polluted by many types.

**Q10. (Senior signal) You must squeeze the last microsecond out of a market-data hot path. What JVM-level levers do you pull, in order?**
*Model answer:* (1) **Measure** with JMH + async-profiler/perfasm to find the real bottleneck. (2) Ensure hot methods are **small enough to inline** and call sites **monomorphic**. (3) Confirm **escape analysis** eliminates short-lived allocations (allocation profiler), restructure to help it (avoid escaping iterators/lambdas where they block EA). (4) Kill **false sharing** with `@Contended`/`LongAdder`. (5) Choose **SoA** layout and minimize object size (JOL) for cache density; keep heap ≤32 GB for compressed oops. (6) Pre-warm to steady state; pin GC choice (low-pause collector) and watch **TTSP**. (7) Only then consider exotic options (Graal, off-heap, Native Image with PGO). Justify each by its measured win, not folklore.
*Follow-ups:*
- *Why measure first?* → JVM intuition is famously wrong; DCE/EA/inlining make naive reasoning fail.
- *When is Native Image the wrong call here?* → if you need *peak* throughput, AOT's lack of runtime PGO can underperform a warmed C2/Graal JIT.

**Q11. Explain intrinsics and give examples.**
*Model answer:* **Intrinsics** are hand-written, hyper-optimized machine-code (or special IR) substitutions the JIT uses instead of compiling a method's Java body — e.g. `System.arraycopy`, `Math.sqrt/sin`, `String.indexOf`/`compareTo`, `Integer.bitCount`, `Arrays.equals`, CRC32, AES, `Thread.onSpinWait`. They exploit CPU instructions (SIMD, dedicated ops) the JIT couldn't reliably derive from bytecode. Visible as `(intrinsic)` in `-XX:+PrintInlining`.
*Follow-ups:*
- *Can I add my own?* → not portably; intrinsics are JVM-internal (`@HotSpotIntrinsicCandidate`/`@IntrinsicCandidate` on JDK methods). The **Vector API** (jdk.incubator.vector) exposes SIMD safely at the language level.

**Q12. What is OSR and why does it complicate microbenchmarks?**
*Model answer:* **On-Stack Replacement** compiles a method entered at a *loop head* and swaps the running frame to native code mid-loop, so methods called once but looping heavily still get compiled. It complicates benchmarks because a `main`-loop benchmark exercises OSR-compiled code (an unusual entry shape, sometimes slightly less optimal) rather than the normally-call-compiled code production uses — JMH structures work to avoid OSR and warm up via normal compilation.
*Follow-ups:*
- *How do you spot OSR in logs?* → the `%` flag and `@ <bci>` in `-XX:+PrintCompilation`.

---

## 11. Glossary

- **AOT (Ahead-of-Time) compilation:** compiling to native code before running (vs JIT). Native Image, Leyden caches.
- **AppCDS / CDS (Class Data Sharing):** caching parsed class metadata in a shared archive to speed startup.
- **Array-of-Structs (AoS):** array where each element holds all its fields together.
- **Back-edge counter:** counts loop iterations (backward jumps) to detect hot loops.
- **Biased locking:** legacy optimization storing a thread id in the mark word; disabled JDK 15, removed JDK 18.
- **Blackhole (JMH):** a sink that consumes values so the JIT can't dead-code-eliminate benchmark work.
- **Bytecode:** the JVM's platform-independent instruction set produced by `javac`.
- **C1 (client compiler):** fast-compiling JIT, basic optimizations, used for startup and as the profiling tier.
- **C2 (server compiler):** slow-compiling JIT, aggressive speculative optimizations, peak throughput.
- **Cache coherence (MESI):** hardware protocol keeping per-core cached copies of a memory line consistent.
- **Cache line:** fixed memory transfer unit, 64 bytes on x86-64/ARM64.
- **CHA (Class Hierarchy Analysis):** technique to devirtualize calls based on currently-loaded class hierarchy.
- **Code cache:** native-memory region holding JIT-compiled machine code.
- **Compile queue:** queue of methods awaiting compilation by compiler threads.
- **Compiler thread:** background thread that performs JIT compilation (`C1/C2 CompilerThread`).
- **Compressed class pointers:** 4-byte klass pointer in the header (heaps with compressed class metadata).
- **Compressed oops:** 4-byte object references (heaps ≤ ~32 GB).
- **`@Contended`:** JDK annotation padding a field to its own cache line(s) to prevent false sharing.
- **Counted loop:** a loop with an `int` induction variable C2 recognizes specially (unrolling, range-check elimination, safepoint handling).
- **Deoptimization:** discarding compiled code and resuming in the interpreter when a speculation is violated.
- **Devirtualization:** turning a virtual call into a direct/inlinable call using profile/CHA.
- **DCE (Dead Code Elimination):** removing code whose results are unused.
- **Escape analysis (EA):** determining whether an object can be seen outside its creating method.
- **False sharing:** performance loss from independent fields sharing a cache line written by different cores.
- **`final` field:** field that, once set in the constructor, is constant — JMM-safe to publish and treatable as constant by the JIT.
- **GlobalEscape / ArgEscape / NoEscape:** EA classifications of how far an object escapes.
- **Graal:** a JIT (and AOT) compiler written in Java, pluggable via JVMCI; powers GraalVM.
- **hsdis:** HotSpot disassembler plugin enabling `-XX:+PrintAssembly`.
- **HotSpot:** the standard OpenJDK/Oracle JVM implementation.
- **Inlining:** replacing a call with the callee's body; the enabling optimization.
- **Intrinsic:** JVM-supplied optimized implementation substituted for a known method.
- **Interpreter:** the fetch-decode-execute engine running bytecode without compilation.
- **JFR (Java Flight Recorder):** low-overhead built-in event recorder (compilation, GC, allocation, deopt).
- **JIT (Just-In-Time):** compilation performed during execution using runtime profile data.
- **JITWatch:** GUI analyzing `LogCompilation` output (timeline, inlining tree, deopts, bytecode↔asm).
- **JMH (Java Microbenchmark Harness):** the correct framework for JVM microbenchmarks.
- **JMM (Java Memory Model):** rules governing visibility/ordering of memory operations across threads.
- **JOL (Java Object Layout):** tool printing exact object layout, offsets, padding, size.
- **JVMCI (JVM Compiler Interface):** API allowing a Java-written compiler (Graal) to plug into HotSpot.
- **Klass / klass pointer:** the metadata describing an object's class; the header pointer to it.
- **Lock coarsening:** merging adjacent synchronized blocks on the same object.
- **Lock elision:** removing a lock on an object EA proves cannot be contended.
- **LongAdder:** a striped, `@Contended` counter that scales under high write contention.
- **LICM (Loop-Invariant Code Motion):** hoisting unchanging computations out of loops.
- **Mark word:** the first header word; a tagged union of hashcode/GC age/lock state.
- **MDO / MethodData:** per-method runtime profile structure feeding C2.
- **Megamorphic / Bimorphic / Monomorphic:** call site with many / two / one observed receiver type(s).
- **OSR (On-Stack Replacement):** compiling and entering a method at a loop head to optimize hot loops mid-execution.
- **oop (ordinary object pointer):** the JVM's term for a reference to a heap object.
- **PGO (Profile-Guided Optimization):** optimizing using observed runtime behavior.
- **Range-check elimination:** removing provably-redundant array bounds checks.
- **Rematerialization:** reconstructing a scalar-replaced object on the heap during deopt.
- **Safepoint:** an execution point where all threads can be paused with precise state (needed for GC/deopt).
- **Scalar replacement (SR):** decomposing a non-escaping object into local scalar values (no allocation).
- **Sea of nodes:** C2's graph-based intermediate representation.
- **Segmented code cache:** code cache split into segments (profiled/non-profiled/non-method) to reduce fragmentation.
- **Struct-of-Arrays (SoA):** storing each field in its own array for cache-dense single-field sweeps.
- **TLAB (Thread-Local Allocation Buffer):** per-thread Eden slice enabling lock-free bump allocation.
- **Tiered compilation:** the L0–L4 pipeline blending interpreter, C1, C2.
- **TTSP (Time To Safepoint):** the time for all threads to reach a safepoint; long values cause stalls.
- **Uncommon trap:** a deopt point C2 plants where a speculation might fail.
- **Vectorization (SuperWord/SIMD):** combining scalar ops across iterations into vector instructions.
- **vtable / itable:** dispatch tables for virtual / interface method calls.
- **Warmup:** the period before hot code reaches its top compilation tier and peak performance.
- **Valhalla / value classes:** future Java feature for identity-free, flat-layout types.
- **Lilliput:** project shrinking the object header.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Execution path:** L0 interpret → L3 (C1 + full profile) → L4 (C2 optimized). Trivial methods: L0 → L1. Loops via **OSR** (`%`). Compile is **async** on compiler threads into the **code cache** (~240 MB default; full = silent interpreted fallback).

**Thresholds (approx, version-dependent):** L3 ≈ 200 invocations / Tier3CompileThreshold 2000; L4 ≈ 5000 invocations / Tier4CompileThreshold 15000. Non-tiered C2 only: `CompileThreshold ≈ 10000`.

**C2 optimizations:** inlining (`MaxInlineSize 35`, `FreqInlineSize 325`), devirtualization (mono/bi/megamorphic), **escape analysis** → scalar replacement + lock elision + coarsening, loop unrolling, range-check elimination, LICM, **SuperWord SIMD**, DCE, intrinsics.

**Deopt triggers:** unstable branch, failed type guard, CHA invalidation (class load), null/range surprise, debugger/HotSwap. Mechanism: uncommon trap → rebuild interpreter frames (multiple if inlined) → rematerialize scalar-replaced objects → `made not entrant` → resume in interpreter → maybe recompile.

**Object layout:** header = mark word (8B) + klass ptr (4B compressed) = ~12B; align 8B → min object **16B**; arrays + 4B length. Fields reordered largest-first to cut padding. Compressed oops = 4B refs for heaps ≤ **~32 GB** (the magic ceiling). `boolean[]` = 1 byte/element.

**Cache:** line = **64 bytes**. **False sharing** = independent fields on one line written by different cores. Fix: **`@Contended`** (128B / 2-line padding), `LongAdder`, SoA.

**Key flags:** `-XX:+TieredCompilation` (on), `-XX:TieredStopAtLevel=N`, `-Xint`, `-Xcomp`, `-Xbatch`, `-XX:ReservedCodeCacheSize`, `-XX:+DoEscapeAnalysis` (on), `-XX:+PrintCompilation`, `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`, `-XX:+LogCompilation` (→ JITWatch), `-XX:+UseCompressedOops` (on).

**Tools:** JMH (benchmark), JOL (layout), JITWatch (compilation analysis), async-profiler/perf/JFR (CPU+alloc), `jcmd Compiler.codecache`, hsdis (`PrintAssembly`).

**PrintCompilation flags:** `%`=OSR, `s`=synchronized, `!`=exception handler, `n`=native, `b`=blocking, `made not entrant`=deopt, `made zombie`=reclaimable.

**Decision rules:** short-lived job → `TieredStopAtLevel=1` or Native Image. Long-running service → default tiered + pre-warm + CDS/AOT. Heap ≤ 32 GB for compressed oops. Hot path → small inlinable methods, monomorphic calls, help EA, no false sharing, SoA. Always **measure with JMH** before believing anything.

### 12.2 Self-test (no answers — recall actively)

1. Trace a method from first interpreted execution to C2-compiled code and back through a deoptimization, naming every tier, counter, and structure involved. Where do scalar-replaced objects go when deopt happens?
2. Why is there a level 1 *and* a level 3 in tiered compilation when both use C1? What does each optimize for, and when does the JVM choose level 2?
3. A teammate "optimized" a hot path by replacing one big method with `@Contended long[]` padding around a counter, but it got *slower* on a single thread and was eliminated by the JIT in one variant. Explain both the single-thread slowdown risk and why hand-rolled padding can be removed — and give the robust fix.
4. Your service has identical code on two clusters; one runs a 31 GB heap, the other 34 GB, and the bigger-heap cluster is slower per request. Give the most likely JVM-internal cause and how you'd confirm it from startup logs.
5. Design a microbenchmark for `String.hashCode()` that won't be fooled by dead-code elimination, OSR, constant folding, or warmup — list the specific JMH features you'd use and why each is necessary.
6. Explain how `LongAdder` beats `AtomicLong` under high write contention, referencing cache lines, the coherence protocol, and `@Contended`. Then explain the read-side tradeoff.
7. A method shows up repeatedly as `made not entrant` in `-XX:+PrintCompilation` long after startup. List three distinct root causes and the tool/command you'd use to distinguish them.

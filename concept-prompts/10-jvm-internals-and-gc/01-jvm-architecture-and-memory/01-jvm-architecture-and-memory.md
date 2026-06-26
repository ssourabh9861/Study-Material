# JVM Architecture & Memory Areas

> Concept area: **JVM Internals & Garbage Collection** · Subtopic: **JVM Architecture & Memory Areas**
>
> A definitive engineering-handbook chapter for senior Java/JVM backend developers. Everything from first principles to deep internals: runtime data areas, object layout, class loading, the execution engine, allocation, the key memory flags, and the tooling to inspect all of it in production.

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

### 1.1 What "the JVM" actually is

The **JVM (Java Virtual Machine)** is an abstract computing machine defined by a specification — *The Java Virtual Machine Specification* (JVMS). It is **not** a single program; it is a contract. A *JVM implementation* is a concrete program that honors that contract. The dominant production implementation is **HotSpot**, the JVM that ships inside OpenJDK (and downstream builds like Oracle JDK, Eclipse Temurin/Adoptium, Amazon Corretto, Azul Zulu, Red Hat builds). Other implementations exist: **Eclipse OpenJ9** (used by IBM Semeru), **GraalVM** (HotSpot-derived but with the Graal JIT and native-image), and historical ones like JRockit. Throughout this document, **unless explicitly stated otherwise, behavior, flags, and defaults refer to HotSpot on a recent OpenJDK (roughly JDK 17–21, the current LTS line)**. Where something is version- or vendor-specific, it is flagged.

> **Term — *specification* vs *implementation*.** A specification is the written rulebook (what must be true). An implementation is software that obeys the rulebook. Two implementations can behave very differently in performance, defaults, and internal structure while both being "correct" JVMs. This is why "the JVM does X" is often imprecise — better to say "HotSpot does X."

The JVM is a **stack-based** abstract machine: its instruction set (bytecode) operates by pushing and popping values on an operand stack rather than naming registers. It executes **Java bytecode**, the compiled form of `.class` files, regardless of which source language produced them (Java, Kotlin, Scala, Clojure, Groovy all compile to the same bytecode).

> **Term — *bytecode*.** The portable, compact instruction set the JVM understands. `javac` compiles `Foo.java` → `Foo.class`, which contains bytecode plus metadata (the *constant pool*, method tables, etc.). Bytecode is not machine code; the CPU cannot run it directly. The JVM interprets it or compiles it further to native machine code at runtime.

### 1.2 The problem the JVM solves

Native compiled languages (C, C++, Rust) produce machine code targeted at one CPU/OS combination. The JVM provides:

1. **Portability — "write once, run anywhere."** Bytecode is platform-neutral; the JVM is the per-platform component. You ship one artifact (`.jar`) and it runs on Linux/x86-64, Linux/ARM64, Windows, macOS, etc., as long as a JVM exists for that platform.
2. **Automatic memory management.** The programmer does not call `free()`/`delete`. A **garbage collector (GC)** reclaims unreachable memory. This eliminates whole classes of bugs (use-after-free, double-free, most leaks) at the cost of GC overhead and tuning complexity. (GC algorithms are the subject of sibling chapters; this chapter covers the *memory areas* the GC manages.)
3. **Adaptive runtime optimization.** Because the JVM compiles to native code *while the program runs* (Just-In-Time compilation), it can optimize based on actual runtime behavior — which branches are taken, which types appear, which methods are hot — often beating ahead-of-time compilation on long-running server workloads.
4. **Safety.** Bytecode is *verified* before execution to guarantee type safety and stack discipline; array accesses are bounds-checked; there is no raw pointer arithmetic in pure Java. This makes whole categories of memory-corruption attacks impossible from pure Java code.

### 1.3 Where memory areas fit in the big picture

When you write `new User()`, a chain of machinery activates: the class `User` must be **loaded** (its `.class` found, parsed, verified, linked, initialized); an object must be **allocated** somewhere in memory; the method calling `new` runs on a **stack frame**; the bytecode executes via the **interpreter** or, if hot, **JIT-compiled** native code; and eventually the object becomes garbage and is reclaimed from its memory region. "JVM Architecture & Memory Areas" is the map of *where everything lives and how it gets there*. Mastering it is the prerequisite for GC tuning, for diagnosing `OutOfMemoryError`, for reading heap dumps, and for explaining why your container got OOM-killed.

### 1.4 One-paragraph mental model

> A running JVM is a process holding several distinct memory regions. The **heap** (one shared region) holds every object and array; the GC manages it and divides it into generations (**young** and **old**). **Metaspace** (in native memory, off-heap) holds class metadata. Each **thread** owns a private **JVM stack** (a stack of *frames*, one per active method call, holding local variables and the operand stack), a tiny **PC register** (the address of the current bytecode), and a **native stack** for C/C++ calls. The JVM also touches **direct/native memory** (outside the heap) for NIO buffers, thread stacks, JIT code, GC bookkeeping, and so on. Code starts as bytecode loaded by **class loaders**, gets **verified**, runs first in the **interpreter**, and the **JIT compiler** progressively recompiles hot methods to native code. The total process memory you see in `top` is heap + Metaspace + all the off-heap pieces — which is exactly why `-Xmx` alone does not bound a container's memory.

---

## 2. Foundations from first principles

### 2.1 Process vs JVM vs heap — disambiguating "memory"

Before naming regions, fix the layering, because "Java is using too much memory" is meaningless without it:

- The **OS process** is what the kernel sees: a single `java` process with an address space, measured by **RSS (Resident Set Size)** — the physical RAM the process currently occupies.
  > **Term — *RSS (Resident Set Size)*.** The amount of a process's memory held in physical RAM right now (as opposed to swapped out or merely reserved-but-untouched). This is the number container OOM-killers and `top`'s `RES` column watch.
- Inside that process, the **JVM** carves out **runtime data areas**. Some are *heap*, most are *not*.
- The **Java heap** is just *one* of those areas. It is bounded by `-Xmx`. **Everything else is off-heap.** A 4 GB `-Xmx` JVM can easily have an RSS of 5–6 GB once you add Metaspace, thread stacks, JIT code cache, GC structures, direct buffers, and the JVM's own C++ data.

This single distinction — **heap is not the process** — is the most common source of production confusion and the root of "I set -Xmx2g but the container OOM-killed at 3g."

### 2.2 The runtime data areas (the JVMS taxonomy)

The JVMS defines the runtime data areas in two lifetimes: **per-JVM** (shared, created at startup) and **per-thread** (created/destroyed with each thread).

#### Per-JVM (shared across all threads)

| Area | Lifetime | Contents | On/off heap | OOM error if exhausted |
|---|---|---|---|---|
| **Heap** | JVM lifetime | All objects and arrays | Heap | `OutOfMemoryError: Java heap space` |
| **Method Area** (logical) | JVM lifetime | Per-class metadata, runtime constant pool, method bytecode, field/method info, static fields*, JIT data | Off-heap in HotSpot (**Metaspace**) | `OutOfMemoryError: Metaspace` |

> *In modern HotSpot, **static fields and interned strings live on the heap** (in the `java.lang.Class` mirror object / string table), not in Metaspace — see §2.6 and §7.3. The "Method Area" is a *logical* JVMS concept; HotSpot implements its class-metadata portion as **Metaspace**.

#### Per-thread (private to each thread)

| Area | Lifetime | Contents | On/off heap | Error if exhausted |
|---|---|---|---|---|
| **JVM Stack** | Thread lifetime | Stack of *frames*; each frame holds local variables, operand stack, frame data | Native memory (per-thread) | `StackOverflowError` (depth) / `OutOfMemoryError: unable to create new native thread` (can't allocate) |
| **PC Register** | Thread lifetime | Address/index of the current bytecode instruction | Tiny, in thread state | (never overflows) |
| **Native Method Stack** | Thread lifetime | Frames for native (C/C++/JNI) methods | Native memory | Native stack overflow / crash |

> **Term — *frame* (stack frame / activation record).** A block of memory created each time a method is invoked, destroyed when it returns. It holds that method's local variables, its operand stack (the scratch space for bytecode computations), and a reference to the runtime constant pool of the class. One active method call = one frame. The chain of frames is the call stack.

> **Term — *operand stack*.** A per-frame LIFO scratch area. Bytecode is stack-based: `iadd` pops two ints, pushes their sum. The compiler computes the maximum depth needed (`max_stack`, stored in the class file) so the JVM can size the frame.

> **Term — *PC (Program Counter) register*.** A per-thread pointer to the bytecode instruction currently executing. For native methods it is undefined. It is how the JVM resumes the right thread at the right instruction after a context switch.

### 2.3 The heap, and why it is "generational"

The heap is where `new` allocates. HotSpot's heap is (for most collectors) **generational**, built on the **weak generational hypothesis**:

> **Weak generational hypothesis.** Most objects die young (short-lived: temporaries, request-scoped data, intermediate collections), and few references point from old objects to young ones. Empirically true for the vast majority of workloads.

Because of this, the heap is split:

- **Young generation (Young Gen / nursery):** where new objects are born. Collected frequently and cheaply by a **minor GC** (a.k.a. young GC). Subdivided into:
  - **Eden:** the bump-pointer allocation area. Almost all `new` lands here.
  - **Two Survivor spaces (S0/S1, "from"/"to"):** copy targets that hold objects surviving one or more minor GCs. Live objects are copied between survivor spaces each minor GC; a surviving object's **age** (number of GCs survived) is tracked.
- **Old generation (Old Gen / tenured):** objects that have survived enough minor GCs are **promoted (tenured)** here. Collected less often by **major/old GC** or, in the worst case, a stop-the-world **Full GC**.

> **Term — *minor GC* / *major GC* / *Full GC*.** Minor GC collects only the young gen (fast, frequent). Major/old GC collects the old gen. **Full GC** collects the *entire* heap (young + old + sometimes class metadata) and is typically the most expensive, longest stop-the-world pause — something you actively try to avoid in production.

> **Term — *promotion / tenuring*.** Moving an object from young gen to old gen because it survived enough minor GCs (its age crossed the *tenuring threshold*) or because the survivor space couldn't hold it (*premature promotion* / *promotion failure*).

> **Term — *stop-the-world (STW)*.** A pause where the JVM halts all application threads so the GC (or other VM operation) can run safely. Pause length is the headline metric for latency-sensitive systems.

Note: **G1** (the default collector since JDK 9) and the low-pause collectors **ZGC** and **Shenandoah** still respect generational concepts but lay the heap out as **regions** rather than fixed contiguous Eden/Survivor/Old blocks. The generational *roles* persist; the physical layout differs. (Collector details belong to the GC chapters; here we care that the heap is the region all of them manage.)

### 2.4 Metaspace — class metadata in native memory

> **Term — *class metadata*.** The JVM's internal description of a loaded class: its fields, methods (and their bytecode), the runtime constant pool, the constant-pool cache, vtables/itables (method dispatch tables), and annotations. This is *the JVM's bookkeeping about your classes*, distinct from the objects your classes create.

Before JDK 8, class metadata lived in **PermGen (Permanent Generation)**, a fixed-size region *inside the heap*. PermGen was a chronic pain: its size was hard to tune (`-XX:MaxPermSize`), and apps that loaded many classes (app servers redeploying WARs, frameworks generating proxies) routinely hit `OutOfMemoryError: PermGen space`.

**JDK 8 removed PermGen and introduced Metaspace**, which lives in **native memory** (outside the Java heap), grown from the OS in chunks. By default Metaspace is **unbounded** — it grows until it exhausts native memory or hits `-XX:MaxMetaspaceSize` (which is *not set by default*). This trades one failure mode (frequent PermGen OOM) for another (a class-leaking app silently eating all native RAM). See §7.3 and §9.

### 2.5 Thread stacks, PC register, native stacks

Every Java thread (and every native thread that attaches) gets:

- A **JVM stack** sized by `-Xss` (thread stack size), allocated from native memory. Deep recursion overflows it → `StackOverflowError`. Trying to create more threads than native memory allows → `OutOfMemoryError: unable to create new native thread`.
- A **PC register** (a machine word) tracking the current bytecode index.
- A **native method stack** for JNI/native calls.

> **Term — *JNI (Java Native Interface)*.** The API that lets Java code call C/C++ functions (and vice versa). Native code runs on the native method stack and can allocate native memory the JVM does not track. JNI leaks are a classic source of "off-heap" growth.

A crucial modern wrinkle: **virtual threads** (Project Loom, finalized in JDK 21). A virtual thread does **not** hold a fixed OS-thread stack while parked; its stack lives on the heap as a continuation object and is mounted onto a carrier (platform) thread only while running. This changes the stack-memory economics dramatically — see §7.7.

### 2.6 Where things are allocated — the allocation map

A mental table of "if I write X, where does it live?":

| You write… | Lives in… |
|---|---|
| `new Foo()`, `new int[100]`, lambda capture object, autoboxed `Integer` | **Heap** (Eden first, may be scalar-replaced — see §7.1) |
| Local primitive `int i`, local reference variable `Foo f` (the *reference*, not the object) | **Stack frame** (local variable slot) of the current thread |
| Method bytecode, field/method descriptors, constant pool | **Metaspace** (class metadata) |
| `static` field *values* / the `Class` object | **Heap** (in the class mirror, since JDK 8) |
| Interned `String` (`"literal"`, `String.intern()`) | **Heap** (string table references heap strings; the table itself is a native hashtable) |
| `ByteBuffer.allocateDirect(n)` | **Native/direct memory** (off-heap), with a small on-heap `DirectByteBuffer` wrapper |
| JIT-compiled native code | **Code cache** (native memory) |
| Thread stack | **Native memory** (per thread) |
| GC's own data (card tables, remembered sets, mark bitmaps) | **Native memory** |

> **Term — *direct memory*.** Off-heap native memory the application explicitly requests, primarily via `java.nio.ByteBuffer.allocateDirect` or `MappedByteBuffer`. Used for zero-copy I/O (the OS can read/write it without copying through the heap). Bounded by `-XX:MaxDirectMemorySize` (defaults to ≈ `-Xmx` if unset). Reclaimed only when the wrapper object is GC'd (via a `Cleaner`/phantom reference), which is why direct-buffer leaks are sneaky — they depend on heap GC timing.

> **Term — *scalar replacement*.** A JIT optimization where a provably non-escaping object is never allocated at all; its fields become plain registers/stack slots. So "everything goes on the heap" is the *language model*, not always the *runtime reality* (see Escape Analysis, §7.1).

### 2.7 The execution path: source → bytecode → native

1. **`javac`** compiles `.java` → `.class` (bytecode + metadata). This is *ahead-of-time* but only to bytecode, not native code.
2. At runtime, a **class loader** finds and loads the `.class` bytes.
3. The bytecode is **verified** (type safety, stack discipline) and **linked**.
4. The class is **initialized** (static initializers run) on first active use.
5. The **interpreter** executes bytecode instruction by instruction.
6. The **JIT compiler** watches execution; once a method is "hot" it compiles it to optimized native code (stored in the code cache). Subsequent calls run native code.
7. If assumptions made during JIT compilation are violated (e.g., a previously-monomorphic call site sees a new type), the code **deoptimizes** back to the interpreter.

This is the **tiered compilation** model (interpreter + C1 + C2), detailed in §3.5–§3.6.

---

## 3. How it works internally

This is the heart of the chapter. We trace the lifecycle of code and objects through the JVM step by step.

### 3.1 The HotSpot subsystems (the architecture diagram in words)

A running HotSpot JVM is composed of:

- **Class Loader Subsystem** — loading, linking (verify/prepare/resolve), initialization.
- **Runtime Data Areas** — heap, Metaspace, per-thread stacks/PC/native stacks (§2).
- **Execution Engine** — interpreter, JIT compilers (C1/C2 or Graal), and the garbage collector.
- **Native Method Interface (JNI)** and **Native Method Libraries**.

Plus supporting machinery: the **template interpreter** (generates interpreter machine code at startup), the **VM thread** (runs safepoint operations), **compiler threads**, **GC threads**, and **service threads**.

### 3.2 Class loading — the full lifecycle (Loading → Linking → Initialization)

The JVMS defines three phases; HotSpot subdivides linking into three sub-steps.

#### Step 1 — Loading

A class loader is asked for class `C` (by binary name, e.g. `com.acme.User`). It:

1. Finds the binary representation (`.class` bytes) — from the filesystem, a jar, the network, or generated in memory.
2. Parses the bytes into HotSpot's internal `InstanceKlass` (the class metadata object in Metaspace) and constructs the `java.lang.Class` *mirror* object on the heap.
3. Records the *defining* class loader.

> **Term — *class loader*.** An object responsible for loading classes. The JVM's *uniqueness identity* for a class is `(binary name, defining class loader)` — the same bytes loaded by two different loaders produce two *distinct* runtime classes that are **not** assignment-compatible. This is the root cause of `ClassCastException: Foo cannot be cast to Foo` and `LinkageError` in app servers/plugin systems.

**The class loader hierarchy & delegation model:**

| Loader | Loads | Notes |
|---|---|---|
| **Bootstrap** (a.k.a. primordial) | Core JDK classes (`java.*`, `java.lang.Object`, etc.) | Written in C++ inside the JVM; its loader reference in Java is `null`. In JDK 9+ loads from the *module system* / `lib/modules`, not `rt.jar`. |
| **Platform** (JDK 9+; was "Extension" in JDK 8) | Certain JDK modules (e.g. crypto, SQL) | Replaced the old `ExtClassLoader`. |
| **Application** (System) | Your classpath / app modules | The default loader for `main`. Returned by `ClassLoader.getSystemClassLoader()`. |

> **Term — *parent-delegation model*.** When asked to load a class, a loader first delegates to its parent; only if the parent fails does it try to load the class itself. This guarantees core classes (`java.lang.String`) are always loaded by the bootstrap loader and cannot be spoofed by application code. You can break delegation deliberately (web containers do, to isolate apps), but the default is delegation-first.

#### Step 2 — Linking

Linking has three sub-phases:

1. **Verification.** The bytecode verifier proves the class is well-formed and safe (see §3.3). Throws `VerifyError` on failure.
2. **Preparation.** Static fields are created and set to *default zero values* (`0`, `0L`, `0.0`, `false`, `null`) — **not** their initializers yet. Memory for statics is allocated (on the heap, in the class mirror).
3. **Resolution.** Symbolic references in the constant pool (e.g., "a method named `foo` with descriptor `(I)V` in class `Bar`") are resolved to direct references (actual pointers/offsets). Resolution is **lazy** by default — done on first use of each symbol, not eagerly at link time.

> **Term — *symbolic reference* vs *direct reference*.** The constant pool stores references by *name and descriptor* (symbolic) so classes can be compiled independently. At runtime they are resolved to concrete memory locations (direct). Lazy resolution is why a missing dependency can throw `NoClassDefFoundError`/`NoSuchMethodError` mid-execution rather than at startup.

#### Step 3 — Initialization

The class's `<clinit>` method runs: static initializers and static field assignments execute, **in textual order**, exactly once. Triggered on first *active use*:

- `new C()`, accessing a static field/method of `C` (non-constant), reflective instantiation, initialization of a subclass, or `C` being the main class.
- **Not** triggered by: accessing a `static final` *compile-time constant* (inlined by `javac`), declaring an array of `C`, or accessing a field declared in a parent (only the declaring class initializes).

> **Term — *`<clinit>` and `<init>`*.** `<init>` is the instance constructor (one per constructor). `<clinit>` is the class initializer — a synthetic method the compiler generates from static blocks and static field initializers; the JVM runs it once, in a thread-safe, deadlock-detecting manner (initialization locks per class).

**Class initialization is thread-safe and lazy** — this is the basis of the *initialization-on-demand holder idiom* for lazy singletons (see §5.5).

### 3.3 Bytecode verification — what the verifier proves

The **verifier** runs at link time to guarantee that bytecode, even if hand-crafted or malicious, cannot corrupt the JVM. It proves, per method:

- **Type safety:** every instruction operates on operands of the correct types; you cannot push an `int` and use it as a reference.
- **Operand-stack discipline:** the stack never underflows/overflows; stack height and types are consistent at every instruction regardless of control-flow path (verified via a *type-inference dataflow analysis*, or in modern class files via **StackMapTable** frames the compiler embeds for faster verification).
- **Local variable types** are consistent.
- **Control flow** stays within the method; jumps land on valid instruction boundaries.
- **Access control** and **`final`** constraints are respected (partly here, partly at resolution).

> **Term — *StackMapTable*.** A class-file attribute (mandatory for class file version 50+/JDK 6+) that records the expected types at branch targets, letting the verifier do a single linear pass instead of expensive iterative dataflow. If you generate bytecode (ASM, ByteBuddy, cglib) you must produce correct StackMapTables or pass `-Xverify:none`/`-noverify` (deprecated/removed — don't).

If verification fails: `java.lang.VerifyError`. You can (historically) disable it with `-Xverify:none`, but this is **removed/deprecated** in modern JDKs and was always dangerous.

### 3.4 Object creation — what `new Foo()` actually does

Trace `Foo f = new Foo(42);`:

1. **`new` bytecode:** ensure `Foo` is loaded/linked/initialized; allocate memory for an instance of `Foo` in the heap; zero its fields; push the (uninitialized) reference onto the operand stack.
2. **`dup`:** duplicate the reference (one copy for the constructor call, one to store).
3. **`invokespecial Foo.<init>(I)V`:** run the constructor, which may call `super()` first, then assign fields.
4. **`astore`:** pop the now-initialized reference and store it into local variable slot for `f`.

**Where allocation happens — the fast path (TLAB):**

> **Term — *TLAB (Thread-Local Allocation Buffer)*.** Each thread gets a private chunk of Eden. Allocation is then a **bump-the-pointer** operation: increment a pointer by the object size — no synchronization needed because the buffer is thread-private. This makes allocation nearly as cheap as stack allocation in C. When a thread's TLAB is full, it grabs a new one (a synchronized operation, but rare relative to allocations).

Object layout written into the allocated memory:

```
+-----------------------------+
| Mark Word     (8 bytes)     |  <- header: hashcode/lock/GC age (see 3.9)
+-----------------------------+
| Klass Pointer (4 or 8 bytes)|  <- header: pointer to InstanceKlass in Metaspace
+-----------------------------+
| (array length, 4 bytes)     |  <- only for arrays
+-----------------------------+
| instance fields ...         |  <- aligned, possibly reordered for packing
+-----------------------------+
| padding to 8-byte boundary  |
+-----------------------------+
```

If Eden is full when allocation is attempted, a **minor GC** is triggered first (see §3.8). If the object is **huge** (larger than a TLAB / a region — "humongous" in G1), it may be allocated directly in old gen / humongous regions, bypassing Eden.

### 3.5 The execution engine — interpreter first

After loading, methods start in the **interpreter**. HotSpot uses a **template interpreter**: at startup it generates small machine-code templates for each bytecode and stitches them together, so even "interpreted" execution runs native dispatch code (faster than a naive switch-based interpreter). The interpreter:

- Maintains the operand stack and locals for the current frame.
- Executes one bytecode at a time, advancing the PC.
- Gathers **profiling data** into a per-method **MDO (Method Data Object)**: invocation counts, branch frequencies, observed receiver types at call sites. This profile is what makes later JIT compilation *adaptive*.

> **Why interpret at all?** Compiling everything eagerly wastes time on code that runs once (startup, rarely-taken branches). Interpreting is instant-start; compilation is reserved for code proven hot. This is the central startup-vs-throughput tradeoff (and why AOT/`native-image` and CDS exist — §7.6).

### 3.6 The JIT — tiered compilation (C1 + C2)

> **Term — *JIT (Just-In-Time) compiler*.** A compiler that translates bytecode to native machine code *at runtime*, using runtime profiling to optimize aggressively.

HotSpot ships two JIT compilers and uses them in **tiers**:

- **C1 (client compiler):** fast to compile, modest optimization. Good for quick warmup and short-lived apps.
- **C2 (server compiler):** slow to compile, heavy optimization (inlining, loop unrolling, escape analysis, vectorization). Produces the fastest code for long-running servers.

**Tiered compilation levels (default since JDK 8, `-XX:+TieredCompilation`):**

| Level | What runs | Profiling |
|---|---|---|
| 0 | Interpreter | Yes (MDO) |
| 1 | C1, no profiling | No (used for trivial methods) |
| 2 | C1 with basic counters | Light |
| 3 | C1 with full profiling | Heavy (collects profile for C2) |
| 4 | C2 (fully optimized) | No (relies on level-3 profile) |

Typical path: **0 → 3 → 4** (interpret with profiling, C1 with full profiling, then C2). Methods deemed trivial may go 0 → 1. Compilation is triggered by **counters** crossing thresholds:

- **Invocation counter** (how many times a method is called).
- **Backedge counter** (how many times a loop iterates — enables **On-Stack Replacement**).

> **Term — *OSR (On-Stack Replacement)*.** Compiling a method *while it is still running* — specifically swapping a hot loop's interpreted frame for a compiled one mid-execution. Without OSR, a program stuck in one long loop in `main` would never benefit from JIT. OSR-compiled methods are specialized to the loop entry point.

> **Term — *inlining*.** Replacing a method call with the callee's body. The single most impactful JIT optimization — it removes call overhead and, crucially, *exposes* the inlined code to further optimization across the old call boundary. Controlled by `-XX:MaxInlineSize`, `-XX:FreqInlineSize`, etc.

#### Deoptimization

C2 makes **speculative assumptions** from the profile (e.g., "this call site only ever sees `ArrayList`"). It guards them with cheap checks. If an assumption is violated at runtime (a `LinkedList` shows up), the JVM **deoptimizes**: discards the compiled code, reconstructs the interpreter frame, and continues interpreting (then possibly recompiles with the new info). Deopt is correctness-preserving but, if frequent (a *deopt storm*), a performance problem — visible with `-XX:+PrintCompilation` / JFR.

> **Term — *code cache*.** The native-memory region holding JIT-compiled methods. Bounded by `-XX:ReservedCodeCacheSize` (default 240 MB with tiered compilation). If it fills, the JIT stops compiling and you fall back to the interpreter — a silent throughput cliff. Logged as "CodeCache is full. Compiler has been disabled." Watch it via `jcmd <pid> Compiler.codecache`.

### 3.7 Safepoints — the coordination primitive

Many VM operations (GC, deoptimization, biased-lock revocation, stack dumps via `jstack`, `Thread.getAllStackTraces`, heap dumps) require all application threads to be at a known, consistent point.

> **Term — *safepoint*.** A point in execution where a thread's state (registers, stack) is fully described by the JVM's metadata (oop maps), so the JVM can safely inspect/modify it. The JVM inserts safepoint *polls* at method returns and loop back-edges. When a safepoint is requested, threads run until their next poll, then park. **Time-to-safepoint (TTSP)** — how long until the *last* thread reaches a poll — is a hidden latency source. A thread in a tight counted loop with no poll, or stuck in JNI, can delay everyone.

> **Term — *oop map*.** Metadata the JIT/interpreter records describing, at each safepoint, which stack slots and registers hold object references ("oops"). The GC reads oop maps to find live references precisely. ("oop" = *ordinary object pointer*.)

Safepoint-related flags worth knowing: `-XX:+PrintSafepointStatistics` (older), `-Xlog:safepoint` (unified logging, JDK 9+) to see TTSP and pause causes.

### 3.8 A minor GC, step by step (generational copying)

To make memory areas concrete, here is a minor (young) GC at the level this chapter needs (algorithmic depth is the GC chapters' job):

1. Eden fills; an allocation fails its TLAB/Eden bump.
2. The thread requests a GC; the JVM brings all threads to a safepoint (STW for stop-the-world young collectors; concurrent collectors differ).
3. GC scans **GC roots** (thread stacks via oop maps, static fields, JNI globals, etc.) to find reachable young objects.
4. Live objects in Eden + the "from" survivor are **copied** to the "to" survivor (or promoted to old gen if their age ≥ tenuring threshold, or if "to" overflows).
5. Surviving objects' **age** is incremented (stored in the mark word).
6. Eden and "from" survivor are now entirely free (everything live was copied out) — reclaimed in O(live), not O(garbage). This is why young GC is cheap when most objects die.
7. Survivor roles swap; threads resume.

> **Term — *GC root*.** A reference the GC treats as inherently reachable, the starting set for liveness tracing: live thread-stack locals and operands, static fields, JNI references, monitors held, etc. An object is *live* iff reachable from a root.

> **Term — *card table* / *remembered set*.** To collect young gen without scanning all of old gen for old→young references, the JVM tracks such references. A **card table** marks "dirty" regions of old gen that may contain references into young gen; the GC scans only dirty cards. G1 uses per-region **remembered sets (RSets)**. This bookkeeping lives in native memory and is itself a memory cost.

### 3.9 Object header internals — mark word & klass pointer

Every object has a **header** of two machine words (on 64-bit): the **mark word** and the **klass pointer** (arrays add a 4-byte length).

#### The mark word (8 bytes on 64-bit)

A polymorphic, bit-packed word reused for different purposes depending on the object's lock state:

| State | Contents (conceptually) |
|---|---|
| Unlocked (normal) | identity hashcode (lazily computed), GC age, biased-lock bit, lock bits `01` |
| Biased (≤ JDK 17; deprecated/removed after) | thread ID of the biasing thread, epoch, age, `101` |
| Lightweight-locked (thin) | pointer to lock record in the locking thread's stack, `00` |
| Heavyweight-locked (inflated) | pointer to the monitor (ObjectMonitor), `10` |
| GC-marked (during GC) | forwarding pointer, `11` |

Key consequences:

- The **identity hashcode** (`System.identityHashCode` / default `Object.hashCode`) is computed *lazily* on first request and then stored in the mark word. (Once stored, it pins certain optimizations — e.g., it interacts with biased locking.)
- The **GC age** (number of survivals) lives here, which is why it's limited to a few bits (max tenuring threshold is **15**: `-XX:MaxTenuringThreshold` ≤ 15).
- **Locking** (synchronized) is implemented by manipulating the mark word — biased → thin → fat lock escalation.

> **Term — *biased locking*.** An optimization assuming a lock is mostly acquired by the same thread; it biases the lock to that thread, avoiding atomic CAS on each acquisition. **Biased locking was deprecated in JDK 15 and disabled by default afterward** because modern hardware made the CAS cheap and the bias-revocation cost (and complexity) no longer paid off. (Flagged: version-specific.)

#### The klass pointer

A pointer from the object to its class metadata (`InstanceKlass`) in Metaspace. Used for virtual dispatch, `instanceof`/`checkcast`, and GC (size/layout). On 64-bit it is normally **compressed** (4 bytes) — see §3.10.

### 3.10 Compressed oops & compressed class pointers

> **Term — *oop (ordinary object pointer)* / *compressed oops*.** On a 64-bit JVM, a raw reference is 8 bytes, which doubles header and reference-field sizes vs 32-bit, bloating the heap and hurting cache locality. **Compressed oops** store references as 32-bit values that are *scaled and offset* to address up to ~32 GB of heap. Since objects are 8-byte aligned, the low 3 bits of any address are zero; the JVM shifts the 32-bit value left by 3 (×8) to recover a real address, reaching `2^32 × 8 = 32 GB`. Enabled by default (`-XX:+UseCompressedOops`) when `-Xmx ≤ ~32 GB`.

Consequences and the **"32 GB cliff":**

- Below ~32 GB heap: compressed oops are on, references are 4 bytes, header is ~12 bytes, you fit more objects per cache line — *faster and smaller*.
- At/above ~32 GB: compressed oops turn off, references become 8 bytes, every object grows. **A 33 GB heap can hold less live data than a 31 GB heap** because of the per-object overhead increase. The practical rule: either stay comfortably under ~32 GB or jump well above it (e.g., 40+ GB) so the extra capacity outweighs the per-object cost.
- The exact threshold depends on object alignment (`-XX:ObjectAlignmentInBytes`, default 8); larger alignment raises the compressed-oop ceiling (e.g., 16-byte alignment → ~64 GB) at the cost of more padding.

> **Term — *compressed class pointers*.** Separately, the klass pointer in the header can be compressed to 4 bytes (`-XX:+UseCompressedClassPointers`), addressing class metadata in a dedicated **Compressed Class Space** (default reserve 1 GB, `-XX:CompressedClassSpaceSize`) carved out of Metaspace. Enabled when compressed oops are on.

You can verify with the **JOL (Java Object Layout)** tool (§4, §5.6) which prints exact header/field offsets and whether oops are compressed.

### 3.11 Putting the lifecycle together (end-to-end trace)

`main` calls `service.handle(request)` which does `new ArrayList<>()` in a loop:

1. **Startup:** JVM launcher reserves heap (`-Xmx` reserved, `-Xms` committed), initializes Metaspace, code cache, GC threads, compiler threads, the template interpreter.
2. **Bootstrap/Platform/App loaders** load core + your classes lazily as referenced; each is verified, linked, initialized on first use.
3. `main` runs **interpreted**; the MDO accrues counts.
4. `handle` crosses the invocation threshold → tiered compilation kicks in (level 3 C1 with profiling, then level 4 C2). The loop's backedge counter may trigger **OSR**.
5. Each `new ArrayList<>()` bumps the thread's **TLAB** pointer in **Eden**; the backing array is also an Eden object.
6. Most lists die at end of iteration → next minor GC reclaims Eden cheaply. Long-lived lists age through survivors and **promote** to old gen.
7. C2 may apply **escape analysis**: if a list provably never escapes, it can be **scalar-replaced** (no allocation) or lock-elided.
8. A heap-dump request (`jcmd GC.heap_dump`) brings threads to a **safepoint**, reads **oop maps** to walk live objects, writes an `.hprof`.
9. Class metadata stays in **Metaspace**; if `handle` is later replaced by a redeploy with a new class loader, the old loader becomes unreachable and its Metaspace chunks are freed on a class-unloading GC.

---

## 4. The complete toolkit

### 4.1 Core memory-sizing flags (HotSpot)

| Flag | Sets | Default | Notes |
|---|---|---|---|
| `-Xms<size>` / `-XX:InitialHeapSize` | Initial (committed) heap | Ergonomic (≈ 1/64 of RAM, container-aware) | Set `-Xms = -Xmx` in servers to avoid resize pauses & fragmentation. |
| `-Xmx<size>` / `-XX:MaxHeapSize` | Maximum heap | Ergonomic (≈ 1/4 of RAM, capped) | The single most important flag. Does **not** bound total process memory. |
| `-Xmn<size>` | Young gen size (sets both `NewSize` & `MaxNewSize`) | Collector-dependent | Mostly for generational collectors; avoid hand-setting with G1 (it tunes regions). |
| `-XX:NewRatio=<n>` | Old:Young size ratio | e.g. 2 (old = 2× young) | Alternative to `-Xmn`. |
| `-XX:SurvivorRatio=<n>` | Eden:Survivor ratio | 8 (each survivor = 1/8 of Eden) | |
| `-XX:MaxTenuringThreshold=<n>` | Max survivals before promotion | 15 (≤ 15, limited by mark-word age bits) | |
| `-Xss<size>` / `-XX:ThreadStackSize` | Per-thread JVM stack size | Platform-dependent (~512 KB–1 MB on 64-bit Linux) | Bigger → deeper recursion but fewer threads fit; smaller → more threads but earlier `StackOverflowError`. |
| `-XX:MetaspaceSize=<size>` | Initial Metaspace high-water mark (first GC trigger) | ~20–21 MB | Misleadingly named: it's the *threshold for the first metadata GC*, not a floor. |
| `-XX:MaxMetaspaceSize=<size>` | Metaspace cap | **Unlimited by default** | Set this in containers to fail fast on class leaks instead of OOM-killing the host. |
| `-XX:CompressedClassSpaceSize=<size>` | Compressed class space reserve | 1 GB | Part of Metaspace; for compressed klass pointers. |
| `-XX:MaxDirectMemorySize=<size>` | Cap on `allocateDirect` | ≈ `-Xmx` if 0/unset | Bounds NIO direct buffers. |
| `-XX:ReservedCodeCacheSize=<size>` | JIT code cache max | 240 MB (tiered) | Fills → JIT disabled → throughput cliff. |
| `-XX:+UseCompressedOops` | 32-bit refs | On when `-Xmx ≤ ~32 GB` | Mind the 32 GB cliff. |
| `-XX:+UseCompressedClassPointers` | 32-bit klass ptrs | On with compressed oops | |
| `-XX:ObjectAlignmentInBytes=<n>` | Object alignment | 8 | Raising it extends compressed-oop ceiling at padding cost. |
| `-XX:+AlwaysPreTouch` | Touch all heap pages at startup | Off | Forces the OS to back the heap with real pages up front → predictable latency, slower startup. |
| `-XX:+UseTLAB` / `-XX:TLABSize` | Thread-local alloc buffers | On; size adaptive | Rarely tuned by hand. |

### 4.2 Container & ergonomics flags

| Flag | Purpose | Default |
|---|---|---|
| `-XX:+UseContainerSupport` | Read cgroup limits for memory/CPU ergonomics | **On** (JDK 8u191+, 10+) |
| `-XX:MaxRAMPercentage=<p>` | Cap heap as % of (container) RAM | 25% | 
| `-XX:InitialRAMPercentage` / `-XX:MinRAMPercentage` | Initial/min heap % | — | `MinRAMPercentage` confusingly applies only to *small* RAM (< ~256 MB). |
| `-XX:ActiveProcessorCount=<n>` | Override detected CPU count | cgroup-derived | Affects GC/JIT thread counts. |

> **In containers, prefer `-XX:MaxRAMPercentage` over a hard `-Xmx`** so the heap scales with the container limit — but always leave headroom for the off-heap regions (Metaspace, stacks, code cache, direct memory, native). A common starting point: heap ≤ 50–75% of the container limit depending on off-heap needs.

### 4.3 Diagnostic & inspection tools

| Tool | What it does | Example |
|---|---|---|
| **`jcmd <pid> <command>`** | Swiss-army CLI; the modern front-end for almost everything | `jcmd <pid> GC.heap_info`, `jcmd <pid> VM.flags`, `jcmd <pid> VM.native_memory summary` |
| **`jmap`** | Heap summary, histogram, heap dump | `jmap -histo:live <pid>`, `jmap -dump:live,format=b,file=heap.hprof <pid>` (largely superseded by `jcmd`) |
| **`jstack`** | Thread dump (all stacks) | `jstack -l <pid>` |
| **`jstat`** | GC/heap statistics over time | `jstat -gc <pid> 1000` (every 1 s), `jstat -gcutil <pid>` |
| **`jinfo`** | Read/set flags at runtime | `jinfo -flag MaxHeapSize <pid>` |
| **NMT (Native Memory Tracking)** | Breaks down *all* JVM native memory by category | Start with `-XX:NativeMemoryTracking=summary`; query `jcmd <pid> VM.native_memory summary` |
| **JFR (Java Flight Recorder)** | Low-overhead always-on profiler/event recorder | `jcmd <pid> JFR.start name=rec settings=profile`, `JFR.dump` |
| **JMC (JDK Mission Control)** | GUI to analyze JFR recordings | open the `.jfr` |
| **`jhsdb`** | Serviceability Agent: inspect a live process or core dump | `jhsdb jmap --heap --pid <pid>` |
| **`-Xlog` (unified logging)** | All JVM logging incl. GC, safepoints, class loading | `-Xlog:gc*,safepoint:file=gc.log:time,uptime,level,tags` |
| **JOL (Java Object Layout)** | Print exact object header/field layout, sizes | `org.openjdk.jol.info.ClassLayout.parseInstance(obj).toPrintable()` |
| **`async-profiler`** | Sampling CPU/alloc/lock profiler (low overhead, flame graphs) | `asprof -e alloc -d 30 <pid>` |
| **Eclipse MAT / VisualVM** | Heap-dump (`.hprof`) analysis: dominator tree, leak suspects | open the dump |

### 4.4 NMT categories (what `VM.native_memory` shows)

NMT breaks native memory into categories — invaluable for "RSS ≫ heap" investigations:

| Category | What it covers |
|---|---|
| **Java Heap** | The `-Xmx` heap (committed). |
| **Class** | Metaspace + compressed class space. |
| **Thread** | Thread stacks (≈ `#threads × -Xss`). |
| **Code** | JIT code cache. |
| **GC** | Card tables, remembered sets, mark bitmaps, GC thread data. |
| **Compiler** | C1/C2 working memory. |
| **Internal** | Misc VM internal (e.g., direct-buffer bookkeeping in some versions). |
| **Symbol** | Interned symbols, string-table support structures. |
| **Native Memory Tracking** | NMT's own overhead. |
| **Arena Chunk / Other** | Temporary native arenas. |

> Note: NMT shows **committed/reserved** the JVM tracks; it does **not** see memory allocated by third-party native libs via raw `malloc`/JNI (those show up as RSS but not in NMT). For those, use OS tools (`pmap -x <pid>`, `/proc/<pid>/smaps`) or `jemalloc`/`malloc` profiling.

---

## 5. Code examples by use case

### 5.1 Programmatically reading the memory areas

```java
import java.lang.management.*;
import java.util.*;

public class MemoryAreasInspector {
    public static void main(String[] args) {
        // --- Heap vs non-heap (Metaspace etc.) at a glance ---
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        System.out.println("Heap:     " + mem.getHeapMemoryUsage());     // init/used/committed/max
        System.out.println("Non-heap: " + mem.getNonHeapMemoryUsage()); // Metaspace, code cache, etc.

        // --- Per-pool breakdown: Eden, Survivor, Old, Metaspace, Code Cache ---
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            System.out.printf("%-30s type=%-8s usage=%s%n",
                pool.getName(),            // e.g. "G1 Eden Space", "Metaspace", "CodeHeap 'profiled nmethods'"
                pool.getType(),            // HEAP or NON_HEAP
                pool.getUsage());
        }

        // --- GC activity (counts/time) per collector ---
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf("GC %-20s collections=%d totalTimeMs=%d%n",
                gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
        }

        // --- Runtime: the actual flags this JVM was started with ---
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        System.out.println("JVM args: " + rt.getInputArguments());
    }
}
```
This is the canonical "where did my memory go" probe and the basis for exposing JVM memory as metrics (Micrometer/Prometheus do exactly this under the hood).

### 5.2 Triggering and observing each `OutOfMemoryError` (educational; do not run in prod)

```java
// 1) Heap OOM: "OutOfMemoryError: Java heap space"
//    Run with -Xmx64m to see it quickly.
static void heapOom() {
    List<byte[]> leak = new ArrayList<>();
    while (true) leak.add(new byte[1_000_000]); // 1 MB chunks, never released
}

// 2) Metaspace OOM: "OutOfMemoryError: Metaspace"
//    Run with -XX:MaxMetaspaceSize=32m. Generate distinct classes endlessly.
static void metaspaceOom() throws Exception {
    var pool = java.util.stream.Stream.<Class<?>>generate(() -> {
        // ByteBuddy / cglib / a custom ClassLoader defining a fresh class each time
        return defineFreshClass();   // pseudo: each call loads a NEW class
    });
    // Holding the Class objects (or their loaders) prevents class unloading -> Metaspace grows.
}

// 3) StackOverflowError: unbounded recursion overruns the thread stack (-Xss controls depth)
static int recurse(int n) { return recurse(n + 1); }

// 4) "unable to create new native thread": exhaust native memory with too many threads
static void threadExhaustion() {
    while (true) new Thread(() -> { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} }).start();
}

// 5) Direct buffer OOM: "OutOfMemoryError: Direct buffer memory"
//    Run with -XX:MaxDirectMemorySize=64m
static void directOom() {
    List<java.nio.ByteBuffer> bufs = new ArrayList<>();
    while (true) bufs.add(java.nio.ByteBuffer.allocateDirect(1 << 20)); // 1 MB direct each
}
```
Each maps to a *different region*: (1) heap, (2) Metaspace native, (3) per-thread stack depth, (4) native memory for new stacks, (5) direct/native memory. Recognizing the message tells you which region is the culprit before you touch a tool.

### 5.3 Capturing a heap dump on OOM and analyzing it

```bash
# Always-on production hygiene: dump heap automatically on the first OOM,
# then exit (or restart via supervisor) so the dump captures the failure state.
java -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/var/dumps/heap-%p.hprof \   # %p = pid
     -XX:OnOutOfMemoryError="kill -9 %p" \          # optional: hard-stop after dumping
     -Xmx2g -jar app.jar

# On a live process, dump on demand (preferred over jmap):
jcmd <pid> GC.heap_dump /var/dumps/live.hprof

# Quick histogram of live objects (what classes dominate the heap):
jmap -histo:live <pid> | head -30
```
Then open `heap.hprof` in **Eclipse MAT**: the **Dominator Tree** shows which objects retain the most memory; **Leak Suspects** auto-flags the biggest retainers; **Path to GC Roots** tells you *why* a suspected-garbage object is still reachable (the actual leak).

### 5.4 Native Memory Tracking — explaining "RSS ≫ -Xmx"

```bash
# Start the JVM with NMT (small overhead, ~5-10%):
java -XX:NativeMemoryTracking=summary -Xmx2g -jar app.jar

# Snapshot a baseline, run load, then diff to see what grew:
jcmd <pid> VM.native_memory baseline
# ... apply load ...
jcmd <pid> VM.native_memory summary.diff
```
Sample (abbreviated) output and how to read it:
```
Total: reserved=5.1GB, committed=3.4GB
-                 Java Heap (reserved=2.0GB, committed=2.0GB)   <- your -Xmx
-                     Class (reserved=1.1GB, committed=160MB)   <- Metaspace + class space
-                    Thread (reserved=820MB, committed=820MB)   <- ~800 threads * 1MB stacks!  <-- suspect
-                      Code (reserved=240MB, committed=90MB)    <- JIT code cache
-                        GC (reserved=160MB, committed=140MB)   <- card tables / RSets
```
Here the surprise is **820 MB in thread stacks** — a thread leak, not a heap leak. No heap dump would have revealed it; NMT did. Fix: bound the thread pool (or move to virtual threads), or lower `-Xss`.

### 5.5 Lazy singleton via class-initialization semantics (uses §3.2)

```java
// Initialization-on-demand holder idiom: thread-safe lazy init with ZERO synchronization,
// relying purely on JVM guarantees that <clinit> runs once, lazily, on first active use.
public final class Config {
    private Config() { /* expensive load */ }

    private static class Holder {              // not loaded/initialized until referenced
        static final Config INSTANCE = new Config();  // runs in Holder.<clinit>, exactly once
    }

    public static Config get() {
        return Holder.INSTANCE;                // first call triggers Holder initialization
    }
}
```
Why it works: the JVM guarantees a class is initialized lazily, exactly once, in a thread-safe way (initialization lock per class). `Holder` isn't initialized until `get()` is first called — true lazy init without `volatile`/`synchronized`/double-checked locking. This is a direct, practical payoff of understanding §3.2.

### 5.6 Inspecting object layout with JOL (uses §3.9–§3.10)

```java
// Dependency: org.openjdk.jol:jol-core
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

public class LayoutDemo {
    static class Point { int x; boolean flag; long id; Object ref; }

    public static void main(String[] args) {
        System.out.println(VM.current().details()); // tells you: compressed oops on? alignment?
        System.out.println(ClassLayout.parseInstance(new Point()).toPrintable());
    }
}
```
Typical output (compressed oops on, 64-bit) — note the 12-byte header (8 mark + 4 klass), field reordering for packing, and trailing padding to an 8-byte boundary:
```
LayoutDemo$Point object internals:
OFF  SZ      TYPE DESCRIPTION               VALUE
  0   8           (object header: mark)     ...
  8   4           (object header: klass)    ...      <- compressed klass pointer (4 bytes)
 12   4       int Point.x
 16   8      long Point.id                            <- longs aligned to 8
 24   4    Object Point.ref                           <- compressed oop (4 bytes)
 28   1   boolean Point.flag
 29   3           (object alignment gap)              <- padding to 32 bytes total
Instance size: 32 bytes
```
This makes §3.9/§3.10 tangible: you can *see* the header, compressed pointers, and padding. Run with `-XX:-UseCompressedOops` and watch every reference grow to 8 bytes and the instance balloon.

### 5.7 Demonstrating escape analysis / scalar replacement (uses §7.1)

```java
public class EscapeDemo {
    static long sink;
    static final class Pair { final int a, b; Pair(int a,int b){this.a=a;this.b=b;} }

    // 'p' never escapes this method -> C2 can scalar-replace it: NO heap allocation in steady state.
    static int sum(int a, int b) {
        Pair p = new Pair(a, b);   // logically allocates; physically may vanish after JIT
        return p.a + p.b;
    }

    public static void main(String[] args) {
        long acc = 0;
        for (int i = 0; i < 100_000_000; i++) acc += sum(i, i + 1); // hot loop -> C2 -> EA
        sink = acc;
        System.out.println(sink);
    }
}
```
Run twice and compare allocation profiles:
```bash
# Allocation profiling with async-profiler shows ~0 allocations in sum() once JIT'd:
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintEliminateAllocations EscapeDemo
# Disabling EA forces real allocation every iteration -> visible GC pressure:
java -XX:-DoEscapeAnalysis EscapeDemo
```
This proves "everything is on the heap" is the *language* model; the *runtime* often allocates nothing.

### 5.8 Off-heap direct buffer with explicit lifecycle (uses §2.6)

```java
import java.nio.ByteBuffer;
import java.lang.foreign.*;   // JDK 21+ Foreign Function & Memory (preview-graduated)

public class OffHeap {
    public static void main(String[] args) {
        // Classic NIO direct buffer: off-heap, reclaimed only when the wrapper is GC'd.
        ByteBuffer direct = ByteBuffer.allocateDirect(64 * 1024 * 1024); // 64 MB native
        direct.putInt(0, 42);
        // No explicit free(); relies on Cleaner. For deterministic control, prefer Arena (below).

        // Modern, deterministic off-heap via FFM API (JDK 21+):
        try (Arena arena = Arena.ofConfined()) {        // confined to this thread
            MemorySegment seg = arena.allocate(64L * 1024 * 1024); // 64 MB native, explicit scope
            seg.set(ValueLayout.JAVA_INT, 0, 42);
            // memory is freed DETERMINISTICALLY when the try-with-resources block closes
        }
    }
}
```
Direct buffers and FFM segments live **outside** `-Xmx`. The FFM `Arena` gives deterministic freeing (no GC dependency), which is the modern answer to direct-buffer leak risk.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Set `-Xms = -Xmx` on long-running servers.** Avoids the cost and pauses of growing the heap and prevents the OS from reclaiming/re-faulting pages. Pair with `-XX:+AlwaysPreTouch` for latency-critical services to pay page-faulting cost at startup, not under load.
- **Allocation is cheap; *retention* is expensive.** TLAB bump-allocation is ~a few instructions. The cost of objects is the GC pressure from *keeping them alive into old gen*. Optimize for short lifetimes (let objects die in young gen), not for "fewer allocations" blindly.
- **Mind the 32 GB compressed-oops cliff** (§3.10). Don't set `-Xmx33g`. Either ≤31 GB or ≥40-ish GB.
- **Size the code cache** (`-XX:ReservedCodeCacheSize`) for big apps; a full code cache silently disables the JIT (throughput cliff).
- **Warmup matters.** JIT compilation takes time and CPU; benchmark steady-state, not cold start. Use **CDS/AppCDS** and consider **AOT/native-image** for fast-start, short-lived workloads (§7.6).

### 6.2 Correctness / concurrency

- **Class identity = (name, loader).** In plugin/app-server/OSGi setups, the same class loaded by two loaders is incompatible. Diagnose `ClassCastException: X cannot be cast to X` and `LinkageError` by checking which loaders are involved (`obj.getClass().getClassLoader()`).
- **Don't rely on finalizers / `Object.finalize()`** (deprecated for removal) to free native resources — they run at unpredictable times, can resurrect objects, and stall GC. Use `Cleaner`, try-with-resources, or FFM `Arena`.
- **`static` fields are GC roots for the loading class loader's lifetime** — a static `Map` that only ever grows is the #1 Java memory leak. So are unbounded caches, listener lists you never deregister, and `ThreadLocal`s on pooled threads.

### 6.3 Memory hygiene & leaks

- **The classic leaks:** unbounded static/instance caches; `ThreadLocal` not removed on pooled threads (the value is retained for the thread's whole life); unclosed resources holding native memory; class-loader leaks (a redeployed WAR whose old loader is pinned by a stray reference — e.g., a JDBC driver registered in `DriverManager`, a thread-local, a shutdown hook).
- **Off-heap leaks are invisible to heap dumps.** Direct buffers, JNI allocations, Metaspace growth — use **NMT** + `pmap`/`smaps`, not MAT.

### 6.4 Security

- **Verification is your safety net** — never run with verification disabled in production.
- **The SecurityManager is deprecated for removal** (JDK 17+); do not design new isolation around it. Use OS/container isolation, modules, and least-privilege instead.
- **Deserialization** can instantiate arbitrary classes and is a major RCE vector; use allow-lists (`ObjectInputFilter`, JDK 9+) or avoid Java serialization entirely.

### 6.5 Observability

- **Always enable GC logging** in production: `-Xlog:gc*:file=...:time,uptime,level,tags` (JDK 9+) or the legacy `-Xloggc`. You cannot tune what you don't log.
- **Run JFR continuously** — overhead is ~1% with the default profile and it captures allocation, GC, safepoint, lock, and compilation events you'll wish you had after an incident.
- **Export JVM metrics** (heap by pool, GC pause time/count, Metaspace, thread count, code cache) via Micrometer/Prometheus and alert on old-gen-after-GC trending up (the true leak signal) and on Full GC frequency.

### 6.6 Cost & containers

- **Right-size the container limit *above* the heap.** Budget: `container ≈ heap + Metaspace + (threads × Xss) + code cache + direct memory + GC structures + native libs + ~headroom`. A heap that's 75% of a tightly-limited container often OOM-kills.
- **Use `-XX:MaxRAMPercentage`** in K8s so the JVM scales with the pod limit, but set it conservatively (e.g., 50–70%) to reserve off-heap headroom.
- **Set `-XX:MaxMetaspaceSize`** so a class leak fails the pod cleanly (restartable) rather than OOM-killing it unpredictably.

### 6.7 Testing

- **Benchmark with JMH**, not hand-rolled loops — it handles warmup, dead-code elimination, and fork isolation, defeating the very JIT effects (constant folding, DCE) that make naive microbenchmarks lie.
- **Test under realistic heap sizes and GC**; behavior at `-Xmx256m` (compressed oops, different GC ergonomics) can differ from production `-Xmx16g`.

### 6.8 Production hardening checklist

- `-Xms = -Xmx`; explicit `-XX:MaxMetaspaceSize`; explicit `-XX:MaxDirectMemorySize` if you use NIO direct.
- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<writable, large enough disk>`.
- `-XX:+ExitOnOutOfMemoryError` (or `+CrashOnOutOfMemoryError`) so a poisoned JVM restarts rather than limping on a corrupted-state heap.
- GC + safepoint logging on, with rotation (`filecount`, `filesize`).
- NMT at least `summary` in staging/canary.
- A known-good `jcmd` runbook on the host.

### 6.9 Anti-patterns

- Setting `-Xmx` to the *entire* container limit (no off-heap headroom).
- `-Xmx32g`–`-Xmx40g` (worst of the compressed-oops cliff).
- Disabling verification (`-Xverify:none`).
- Calling `System.gc()` in app code (use `-XX:+DisableExplicitGC` to neutralize libraries that do).
- Treating `OutOfMemoryError` as catchable-and-recoverable in normal flow (heap is exhausted; the next allocation, including the one needed to handle the error, may fail).
- Unbounded thread creation (each costs a native stack); prefer bounded pools or virtual threads.

---

## 7. Advanced topics & deep internals

### 7.1 Escape Analysis, scalar replacement, lock elision

**Escape analysis (EA)** is a C2 analysis determining an object's *escape state*:
- **NoEscape:** object never leaves the method/thread → eligible for **scalar replacement** (fields become registers/stack slots; no heap allocation) and **lock elision** (synchronization on it removed).
- **ArgEscape:** escapes via a method argument but not to the heap/other threads → partial optimizations.
- **GlobalEscape:** stored in a static, returned, or published to another thread → must be heap-allocated.

EA enables three big wins: scalar replacement (no allocation, less GC), **lock elision** (`-XX:+EliminateLocks` — removing `synchronized` on thread-confined objects, e.g., a local `StringBuffer`), and **lock coarsening** (merging adjacent locks). Flags: `-XX:+DoEscapeAnalysis` (on by default), `-XX:+PrintEliminateAllocations` (diagnostic).

> EA is **method-local and depends on inlining**: if the allocation site isn't inlined into a context where escape can be proven, EA fails. This is why "the same code allocates in one caller but not another." It also only kicks in after C2 compiles the method — cold/interpreted runs *do* allocate.

### 7.2 String interning & the string table

`String` literals and `String.intern()` go through the **string table**, a native hashtable mapping content → a canonical heap `String`. Pre-JDK 7 the interned strings lived in PermGen (a leak source); since JDK 7 they live on the **heap**, with the table being a fixed-bucket native hashtable sized by `-XX:StringTableSize` (default 65536 ≈ 60013 prime in many builds). Over-interning user-controlled strings can bloat the heap and lengthen GC; under-sizing the table causes hash collisions and slow `intern()`. Inspect with `jcmd <pid> VM.stringtable` / `jmap -histo`.

### 7.3 Metaspace internals & class unloading

Metaspace is allocated from native memory in **chunks** grouped by **class loader**: each loader gets its own metaspace arena, so when a loader becomes unreachable, *all* its class metadata can be freed at once. Key behaviors:
- **First metadata GC** is triggered when usage crosses `-XX:MetaspaceSize` (the misnamed high-water mark, ~21 MB), then the threshold grows/shrinks adaptively (`-XX:MinMetaspaceFreeRatio`, `-XX:MaxMetaspaceFreeRatio`).
- **Class unloading** happens during certain GCs (e.g., G1 unloads classes during concurrent cycles since JDK 10+; Full GC always does). A loader is unloadable only when *nothing* references it or its classes/instances.
- **Fragmentation:** Metaspace can hold committed memory it can't return because chunks are loader-scoped; long-running apps that churn loaders may show high Metaspace RSS even after unloading.

> Diagnose Metaspace growth with `jcmd <pid> VM.metaspace` (detailed chunk/loader breakdown) and `-Xlog:gc+metaspace`.

### 7.4 TLAB internals & sizing

Each thread's TLAB has a **size** (adaptively tuned per thread based on allocation rate and GC frequency) and a **refill waste** budget. When an allocation won't fit the remaining TLAB:
- If the object is small relative to the TLAB, the TLAB is **retired** (its remainder filled with a dummy object so the heap stays parseable) and a new TLAB is requested.
- If the object is large, it's allocated directly in Eden (or old gen) outside any TLAB.

Flags: `-XX:+UseTLAB` (on), `-XX:TLABSize`, `-XX:-ResizeTLAB` (disable adaptive sizing), `-XX:TLABWasteTargetPercent`. Excessive TLAB waste or huge allocations show up as "slow path" allocations in JFR's allocation events.

### 7.5 Safepoints, TTSP, and "the GC log says pause 5ms but my p99 is 200ms"

The reported GC pause is the *GC work*; the *total stall* a thread sees includes **time-to-safepoint** (waiting for the slowest thread to reach a poll) plus safepoint cleanup. A thread in a **counted loop** (e.g., `for (int i…)` over a primitive range) historically had **no safepoint poll inside the loop** (the JIT assumed it terminates quickly), so a long counted loop could delay safepoints by tens of ms. JDK 10+ added **loop-strip-mining** and the option for **uncounted/guaranteed safepoint polls** to mitigate this. Diagnose with `-Xlog:safepoint` (look at `Reaching safepoint` time) and async-profiler's `ttsp` mode. Also: a thread in JNI doesn't poll until it returns — long native calls delay safepoints.

### 7.6 Startup optimization: CDS, AppCDS, AOT, native-image

> **Term — *CDS (Class Data Sharing)*.** The JVM can pre-parse core (and, with **AppCDS**, application) classes into a memory-mappable **archive** that's `mmap`ed at startup and *shared across JVM processes*, cutting class-loading/verification time and reducing per-process footprint. Default CDS for the JDK classes is on in modern JDKs; **AppCDS** (`-XX:SharedArchiveFile`, `-XX:+AutoCreateSharedArchive` in JDK 19+) extends it to your classes. **Dynamic CDS** auto-records at exit.

> **Term — *AOT / GraalVM native-image*.** Ahead-of-time compilation to a standalone native binary with a minimal runtime ("Substrate VM"). Near-instant startup and low memory, at the cost of the closed-world assumption (reflection/dynamic class loading must be configured), longer build times, and (often) lower peak throughput than C2 JIT. Use for serverless/CLI/short-lived; keep the JIT for long-running throughput-bound services.

These exist precisely because the interpret-then-JIT model trades startup for peak performance (§3.5).

### 7.7 Virtual threads (Project Loom, JDK 21) and the stack

A **platform thread** wraps an OS thread and holds a fixed native stack (`-Xss`) for its whole life — expensive, so you pool them. A **virtual thread**:
- Has its **stack stored on the heap** as a *continuation* (a `StackChunk` object). When the virtual thread blocks (on I/O, locks adapted for Loom), it **unmounts** from its **carrier** (a platform thread) and its stack is parked on the heap; when ready, it remounts.
- Costs ~hundreds of bytes idle vs ~1 MB for a platform-thread stack, so millions are feasible.
- **Pinning:** if a virtual thread holds a `synchronized` monitor (pre-JDK 24 fix) or runs native code across a blocking point, it **pins** the carrier (can't unmount), reducing scalability. (Flagged: the `synchronized` pinning limitation was largely removed in JDK 24; in 21–23 prefer `ReentrantLock` in hot blocking paths.) Diagnose with `-Djdk.tracePinnedThreads=full`.

This fundamentally changes §2.5's stack economics: with virtual threads, "too many threads → native OOM" mostly disappears, replaced by heap pressure from many parked continuations.

### 7.8 Compressed oops edge cases & zero-based heaps

If the heap can be placed starting at address 0 (or the JVM can reserve low memory), the JVM uses **zero-based compressed oops**: decoding is just a shift, no add of a base — slightly faster. Above that, it needs a non-zero base (shift + add). Above ~32 GB, oops can't be compressed at all. The JVM logs the mode at startup with `-Xlog:gc+heap+coops` (e.g., "Heap address: ..., Compressed Oops mode: 32-bit / Zero based / Non-zero based / Non-zero disjoint"). `-XX:HeapBaseMinAddress` influences placement.

### 7.9 Interned-vs-not, ClassValue, and metadata caching

- `ClassValue<T>` associates per-class data efficiently (cached on the class, GC-aware) — a better pattern than `WeakHashMap<Class, …>` for class-keyed caches, avoiding class-loader leaks.
- The **constant pool cache** in Metaspace memoizes resolved symbolic references (so a call site resolves once). The **inline cache** at JIT-compiled call sites caches the last receiver type for fast monomorphic dispatch (megamorphic sites fall back to a vtable/itable lookup).

### 7.10 Polymorphic dispatch internals

- `invokevirtual` uses a **vtable** (method table) indexed by the receiver's klass.
- `invokeinterface` uses an **itable** (interface method table) — slower lookup, hence the JIT's reliance on inline caches.
- `invokestatic`/`invokespecial` resolve to a fixed method (no dispatch).
- `invokedynamic` (lambdas, `String` concat in JDK 9+, dynamic languages) bootstraps a **CallSite** once via a bootstrap method, then runs a `MethodHandle` — the mechanism behind lambda metafactory and indy-based optimizations.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing heap size & the 32 GB cliff

| Situation | Recommendation |
|---|---|
| Latency-sensitive service, ≤ 31 GB working set | `-Xms=-Xmx` ≤ 31 GB, compressed oops on, `+AlwaysPreTouch` |
| Need 33–40 GB | **Avoid** — either trim to ≤ 31 GB or jump to ≥ 48 GB so capacity beats the per-object cost |
| Huge heap (≥ 48 GB), low pause required | ZGC/Shenandoah; accept 8-byte oops |
| Short-lived CLI/serverless | Small heap, CDS/AppCDS, or native-image |

### 8.2 PermGen (≤7) vs Metaspace (8+)

| Aspect | PermGen (≤ JDK 7) | Metaspace (JDK 8+) |
|---|---|---|
| Location | In heap | Native memory |
| Default cap | Fixed (`MaxPermSize`, ~64–82 MB) | **Unbounded** (set `MaxMetaspaceSize`!) |
| Tuning flag | `-XX:MaxPermSize` | `-XX:MaxMetaspaceSize` |
| Failure | Frequent `PermGen space` OOM | Native exhaustion / `Metaspace` OOM if capped |
| Interned strings | In PermGen | On heap (since JDK 7) |

### 8.3 On-heap vs off-heap (direct) storage

| | On-heap | Off-heap (direct/FFM) |
|---|---|---|
| Reclamation | GC, automatic | Cleaner (nondeterministic) or `Arena` (deterministic) |
| GC pressure | Yes (large heaps → long pauses) | No (invisible to GC) |
| I/O | Copies through heap | Zero-copy with OS |
| Bounds | `-Xmx` | `-XX:MaxDirectMemorySize` / native RAM |
| Safety | Bounds-checked, type-safe | Manual; risk of corruption/leaks |
| Use when | Default for everything | Huge buffers, caches, zero-copy I/O, avoiding GC for big data |

### 8.4 Interpreter vs C1 vs C2 vs AOT

| | Interpreter | C1 | C2 | AOT/native-image |
|---|---|---|---|---|
| Startup | Instant | Fast | Slow warmup | Instant |
| Peak throughput | Low | Medium | Highest | Medium (no profile-guided runtime opt) |
| Adaptivity | n/a | Low | High (profile-guided, deopt) | None (closed world) |
| Memory | Low | Low | Higher (code cache) | Lowest runtime |
| Use when | Cold code | Warmup/short apps | Long-running servers | Serverless/CLI/fast-scale |

### 8.5 Platform threads vs virtual threads (stack memory lens)

| | Platform thread | Virtual thread |
|---|---|---|
| Stack | Native, fixed `-Xss` (~1 MB) | Heap continuation (~hundreds of bytes idle) |
| Practical count | thousands | millions |
| Best for | CPU-bound, long-running, native-heavy | High-concurrency blocking I/O |
| Pitfall | native OOM at high counts | pinning (`synchronized`/native), heap pressure |

---

## 9. Failure modes & debugging

### 9.1 `OutOfMemoryError: Java heap space`

**Meaning:** the heap can't satisfy an allocation and GC can't free enough. **Two causes:** (a) genuine leak (retained set grows unboundedly), (b) heap too small for legitimate working set / a giant allocation.
**Diagnose:**
1. Confirm with the message and GC logs: a true leak shows **old-gen-used-after-Full-GC rising monotonically** over time. A too-small heap shows GC working hard but used-after-GC stable.
2. Heap dump (`-XX:+HeapDumpOnOutOfMemoryError`), open in **MAT** → Leak Suspects → Dominator Tree → Path to GC Roots.
3. Histogram via `jmap -histo:live <pid>` for a quick "what dominates" without a full dump.
**Real-world:** a `static List<>` audit log never trimmed; a Guava/Caffeine cache without `maximumSize`; `ThreadLocal` holding request context on a pooled thread.

### 9.2 `OutOfMemoryError: Metaspace`

**Meaning:** class metadata native space exhausted (only thrown if `MaxMetaspaceSize` is set or native RAM is gone). **Cause:** class-loader leak — loaders/classes not unloaded (dynamic proxy/CGLib/bytecode-generation churn, repeated redeploys, scripting engines, frameworks generating a class per X).
**Diagnose:** `jcmd <pid> VM.metaspace` and `-Xlog:class+unload` (are classes ever unloaded?). Heap dump → look for many `ClassLoader` instances; in MAT, group by loader. `jmap -clstats <pid>` summarizes loaders.
**Real-world:** an app server WAR redeploy leaking the old loader via a JDBC driver registered in `DriverManager`, a stuck thread-local, or a JMX/logging reference pinning it. Each redeploy adds a full set of classes → eventual Metaspace OOM.

### 9.3 `StackOverflowError`

**Meaning:** a thread's JVM stack exceeded its size (`-Xss`). **Cause:** unbounded/very deep recursion, or extremely deep call chains (e.g., huge JSON/XML structures parsed recursively), or `-Xss` set too small.
**Diagnose:** the stack trace shows the repeating frame cycle. Increase `-Xss` only if the recursion is legitimately deep; otherwise fix the recursion (convert to iteration, add a base case/depth limit).

### 9.4 `OutOfMemoryError: unable to create new native thread` / `Failed to start thread`

**Meaning:** the JVM couldn't allocate a native stack for a new thread — native memory or OS limits (`ulimit -u`, `/proc/sys/kernel/threads-max`, cgroup pids limit) exhausted, often because too many threads exist.
**Diagnose:** `jstack`/thread count, NMT "Thread" category (§5.4), OS `nproc`/`ulimit -u`. **Fix:** bound thread pools, lower `-Xss`, or migrate blocking-I/O concurrency to virtual threads.

### 9.5 `OutOfMemoryError: Direct buffer memory`

**Meaning:** `MaxDirectMemorySize` exceeded. **Cause:** direct `ByteBuffer`s allocated faster than their wrappers are GC'd (Cleaner-based reclamation lags), or a genuine leak holding wrappers.
**Diagnose:** NMT/`jcmd VM.native_memory`, `-XX:NativeMemoryTracking`, JFR `jdk.DirectBufferStatistics` (or BufferPoolMXBean: `ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)`). **Fix:** pool/reuse direct buffers, prefer FFM `Arena` for deterministic free, raise the cap only if justified.

### 9.6 `RSS ≫ -Xmx` / container OOM-kill (no Java OOM in logs)

**The classic container puzzle:** the JVM never logs an `OutOfMemoryError`, but the kernel `oom-kill`s the process (exit 137 in K8s). The heap is fine; **off-heap** grew.
**Diagnose:** NMT baseline+diff (§5.4) to find the growing category — usually Thread (thread leak), Class (Metaspace), Code (code cache), or "uncategorized" RSS from native libs (then use `pmap -x`, `/proc/<pid>/smaps`, `jemalloc` profiling). Check glibc `malloc` arena fragmentation (set `MALLOC_ARENA_MAX=2` or use jemalloc/tcmalloc for fragmentation-heavy native allocators).
**Fix:** cap each off-heap region; right-size container vs heap; consider `-XX:+ExitOnOutOfMemoryError` is irrelevant here (no Java OOM) — the fix is budgeting.

### 9.7 Long pauses despite small GC times (TTSP)

Covered in §7.5. **Diagnose:** `-Xlog:safepoint` — if "Reaching safepoint" dominates over "At safepoint", you have a TTSP problem (counted loop without polls, long JNI call, page faults on a non-pre-touched heap). **Fix:** pre-touch heap, break up counted loops, avoid long JNI critical sections.

### 9.8 Throughput cliff: code cache full

**Symptom:** sudden throughput drop; log line "CodeCache is full. Compiler has been disabled." **Diagnose:** `jcmd <pid> Compiler.codecache`. **Fix:** raise `-XX:ReservedCodeCacheSize`; in extreme cases segment the code cache (`-XX:+SegmentedCodeCache`, default on).

### 9.9 Deopt storms

**Symptom:** CPU burned in compile threads, oscillating performance. **Diagnose:** `-XX:+PrintCompilation` / JFR compilation events show repeated deopt+recompile of the same method (often a megamorphic call site or a profile that keeps changing). **Fix:** stabilize types at hot call sites; sometimes `-XX:-OmitStackTraceInFastThrow` matters; investigate via JFR `jdk.Deoptimization`.

---

## 10. Interview drill

**Q1. Walk me through the JVM runtime data areas. Which are per-thread and which are shared?**
*Model answer:* Shared (per-JVM): the **heap** (all objects/arrays, GC-managed, generational into young/old) and the **method area** (logically), implemented in HotSpot as **Metaspace** in native memory for class metadata. Per-thread: the **JVM stack** (a stack of frames, each with locals + operand stack), the **PC register** (current bytecode address), and the **native method stack** (for JNI). The heap is bounded by `-Xmx`; everything else (Metaspace, thread stacks, code cache, direct memory, GC structures) is off-heap, which is why process RSS exceeds `-Xmx`.
- *Follow-up: Where do static fields and interned strings live?* On the **heap** since JDK 7/8 (in the `Class` mirror / string table referencing heap strings), not in Metaspace/PermGen.
- *Follow-up: Where does a local `int` live vs the object a local variable points to?* The `int` and the *reference* live in the frame's local-variable slots (stack); the *object* lives on the heap.
- *Follow-up: What replaced PermGen and why?* Metaspace (JDK 8), moved to native memory and made auto-growing to kill the chronic fixed-size `PermGen space` OOM; tradeoff: unbounded by default, so it can eat native RAM.

**Q2. Explain object creation from `new Foo()` down to memory.**
*Model answer:* Ensure `Foo` is loaded/linked/initialized; the `new` bytecode allocates and zeroes instance memory — fast path is **bump-the-pointer in the thread's TLAB inside Eden**; `invokespecial` runs the constructor; the reference is stored in a local slot. If Eden is full, a minor GC runs first; huge objects may skip Eden.
- *Follow-up: What's a TLAB and why does it matter?* A thread-private Eden chunk enabling lock-free bump allocation — makes Java allocation nearly as cheap as a stack push.
- *Follow-up: Is the object always heap-allocated?* No — escape analysis can scalar-replace a non-escaping object after C2 compiles the method, so no heap allocation happens at all.

**Q3. Describe the object header and compressed oops.**
*Model answer:* Two words on 64-bit: the **mark word** (identity hashcode, GC age, lock state bits — reused polymorphically for biased/thin/fat locking and GC forwarding) and the **klass pointer** (to `InstanceKlass` in Metaspace). Arrays add a length. **Compressed oops** store references as 32-bit values shifted by 3 (8-byte alignment) to address up to ~32 GB, shrinking headers/refs and improving cache locality.
- *Follow-up: Why the 32 GB cliff?* Above ~32 GB, oops can't be compressed → all refs become 8 bytes → per-object overhead jumps, so a slightly-bigger heap can hold *less* live data.
- *Follow-up: How is identity hashcode stored?* Lazily computed on first request and cached in the mark word.
- *Follow-up: What's the max tenuring threshold and why 15?* 15, because the GC age field in the mark word is 4 bits.

**Q4. Take me through class loading: phases and the delegation model.**
*Model answer:* **Loading** (find bytes, build `InstanceKlass` + `Class` mirror), **Linking** = verify (bytecode safety) → prepare (static fields to default zeros) → resolve (symbolic→direct, lazy), **Initialization** (`<clinit>` runs once, lazily, thread-safe, on first active use). Loaders form a hierarchy (Bootstrap → Platform → Application) with **parent-first delegation** so core classes can't be spoofed.
- *Follow-up: What makes two classes "the same"?* `(binary name, defining class loader)` — same bytes, different loaders = incompatible types (`ClassCastException`/`LinkageError`).
- *Follow-up: When is a class initialized?* On first active use: `new`, static method/non-constant static field access, subclass init, reflection, main class. **Not** by accessing a compile-time-constant `static final`.

**Q5. Interpreter vs JIT — explain tiered compilation and deoptimization.**
*Model answer:* Methods start **interpreted** (instant start, profiling into the MDO). **Tiered compilation** promotes hot methods: typically 0 (interpret) → 3 (C1 with full profiling) → 4 (C2, fully optimized). C2 inlines and makes speculative, profile-based assumptions guarded by cheap checks; if violated, the method **deoptimizes** back to the interpreter and may recompile. **OSR** lets a hot loop get compiled mid-execution.
- *Follow-up: What is the code cache and what happens when it fills?* Native region for compiled code; if full, JIT disables → silent throughput cliff.
- *Follow-up: Why interpret first at all?* Avoids wasting compile time on cold/once-run code; reserves expensive C2 for proven-hot methods.

**Q6 (senior-signal). You have a 64-core box and 200 GB RAM for a latency-sensitive service. How do you size the heap, and why not just `-Xmx150g`?**
*Model answer:* For latency, prefer a heap *just large enough* for the working set with a low-pause collector. Crucially, avoid the **33–40 GB compressed-oops dead zone**: either keep `-Xmx ≤ ~31 GB` (compressed oops, smaller/faster objects) or, if you genuinely need >31 GB live, jump to ≥ ~48 GB with **ZGC/Shenandoah** to keep pauses sub-millisecond and accept 8-byte oops. Set `-Xms=-Xmx` + `+AlwaysPreTouch` for predictable latency, leave RAM headroom for off-heap (page cache, direct buffers, Metaspace, stacks), and don't starve the OS page cache. `-Xmx150g` would force 8-byte oops (more memory per object, worse cache behavior), longer GC work, and steal RAM from the page cache.
- *Follow-up: What collector and why?* ZGC/Shenandoah for large heaps + low pause; G1 for balanced; avoid Parallel for latency-sensitive.
- *Follow-up: How would you verify oop mode at runtime?* `-Xlog:gc+heap+coops` at startup or JOL `VM.current().details()`.

**Q7 (senior-signal). A container with `-Xmx2g` gets OOM-killed at ~3.2 GB RSS but logs no `OutOfMemoryError`. Diagnose.**
*Model answer:* This is off-heap growth, not a heap problem (no Java OOM). Enable **NMT** (`-XX:NativeMemoryTracking=summary`), take a `VM.native_memory baseline`, apply load, then `summary.diff` to find the growing category — commonly **Thread** (a thread leak: count × `-Xss`), **Class** (Metaspace from a class leak), **Code** (code cache), **direct memory** (NIO buffers), or uncategorized RSS from native libraries / glibc malloc-arena fragmentation (check with `pmap -x`, `smaps`, try `MALLOC_ARENA_MAX=2` or jemalloc). Then cap the offending region and budget the container limit as heap + all off-heap + headroom.
- *Follow-up: Why won't a heap dump help?* Off-heap memory isn't in the heap; MAT only sees heap objects (you might see many `Thread`/`ClassLoader` instances as a hint, but the bytes are native).
- *Follow-up: What's a sensible heap-to-container ratio?* Often 50–75% depending on off-heap usage; never 100%.

**Q8 (senior-signal). Your GC logs show 4 ms pauses but p99 latency spikes to 150 ms. Where do you look?**
*Model answer:* The GC log reports *GC work*, not the full stall. Suspect **time-to-safepoint (TTSP)**: a thread in a long **counted loop** without safepoint polls, a long **JNI** call, or page faults on a non-pre-touched heap can delay *all* threads from reaching the safepoint. Turn on `-Xlog:safepoint` and compare "Reaching safepoint" vs "At safepoint"; use async-profiler's TTSP mode. Fixes: `+AlwaysPreTouch`, loop strip-mining (JDK 10+ does this), break up counted loops/JNI critical sections.
- *Follow-up: What else causes p99 stalls unrelated to GC?* Lock contention, OS scheduling/CFS throttling in containers (CPU limits), allocation stalls when Eden is full, JIT compiler threads, biased-lock revocation (older JDKs).

**Q9. What is Metaspace, how does it differ from PermGen, and how do you cap it safely?**
*Model answer:* Native-memory region for class metadata (replacing in-heap PermGen in JDK 8). It's organized per class loader so an entire loader's metadata frees on unload. It's **unbounded by default**; set `-XX:MaxMetaspaceSize` to fail fast on class leaks. `-XX:MetaspaceSize` is just the first-GC high-water mark, not a floor.
- *Follow-up: When are classes unloaded?* When their loader is unreachable; during certain GCs (G1 in concurrent cycles JDK 10+, Full GC always). Verify with `-Xlog:class+unload`.

**Q10. Explain stack frames and the operand stack with a tiny bytecode example.**
*Model answer:* Each method call creates a **frame** with local-variable slots and an **operand stack**. Bytecode is stack-based: `a + b` compiles to `iload a; iload b; iadd; ireturn` — push both, pop-add-push the sum, return it. The compiler precomputes `max_stack`/`max_locals` so the frame is sized at load time. Frames live on the per-thread JVM stack; too-deep nesting → `StackOverflowError`.
- *Follow-up: Where is `this`?* Local variable slot 0 in instance methods.
- *Follow-up: How big is a frame?* Sized from `max_locals` + `max_stack` (+ frame data) in the class file; not user-tunable per method, but the whole stack is bounded by `-Xss`.

**Q11. How do you take and read a heap dump?**
*Model answer:* Auto on OOM with `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...`, or on demand with `jcmd <pid> GC.heap_dump file.hprof`. Analyze in Eclipse MAT: Dominator Tree (biggest retainers), Leak Suspects (auto-analysis), Path to GC Roots (why it's alive). A leak shows a single dominator retaining a growing collection rooted in a static/ThreadLocal.
- *Follow-up: Why `:live`?* It runs a GC first, dumping only reachable objects — smaller, focuses on real leaks.
- *Follow-up: Risk of dumping a huge live heap in prod?* It STWs and writes a multi-GB file; can stall the service and fill disk — do it on a canary or with capacity planning.

**Q12. What's escape analysis and can you observe it?**
*Model answer:* A C2 analysis classifying objects as NoEscape/ArgEscape/GlobalEscape; NoEscape objects can be **scalar-replaced** (no allocation) and have **locks elided/coarsened**. It only applies after C2 compiles the method and depends on inlining. Observe with `-XX:+PrintEliminateAllocations` / allocation profiling (async-profiler), or compare `-XX:+/-DoEscapeAnalysis`.
- *Follow-up: Why might the same code allocate in one place but not another?* Because EA is method-local and inlining-dependent; if the allocation site isn't inlined into a provably-non-escaping context, the object is heap-allocated.

---

## 11. Glossary

- **AOT (Ahead-of-Time) compilation:** Compiling to native code before runtime (e.g., GraalVM native-image). Fast start, closed-world.
- **AppCDS:** Class Data Sharing extended to application classes; memory-mapped archive shared across JVMs to speed startup and cut footprint.
- **Backedge counter:** Counts loop iterations; crossing its threshold triggers OSR compilation.
- **Biased locking:** Optimization biasing a lock to one thread to skip CAS; deprecated/disabled in modern JDKs (15+).
- **Bytecode:** The JVM's portable instruction set; output of `javac`, input to the JVM.
- **C1 / C2:** HotSpot's client (fast, light) and server (slow, optimizing) JIT compilers.
- **Card table:** Bitmap marking old-gen regions that may hold references into young gen, so minor GC needn't scan all of old gen.
- **CDS (Class Data Sharing):** Pre-parsed, memory-mappable class archive shared across processes.
- **`<clinit>` / `<init>`:** Class initializer (static blocks/fields, runs once) / instance constructor.
- **Class loader:** Object that loads classes; part of a class's runtime identity. Hierarchy: Bootstrap → Platform → Application.
- **Code cache:** Native-memory region for JIT-compiled code; sized by `-XX:ReservedCodeCacheSize` (240 MB default tiered).
- **Compressed class pointers:** 4-byte klass pointers via a dedicated compressed class space.
- **Compressed oops:** 32-bit object references (shifted by 3 for 8-byte alignment) reaching ~32 GB; default when `-Xmx ≤ ~32 GB`.
- **Constant pool (runtime):** Per-class table of symbolic references (classes, methods, fields, constants); resolved to direct references at use.
- **Deoptimization:** Discarding JIT-compiled code and falling back to the interpreter when a speculative assumption is violated.
- **Direct memory:** Off-heap native memory (e.g., `ByteBuffer.allocateDirect`); bounded by `-XX:MaxDirectMemorySize`.
- **Eden:** Young-gen sub-region where most new objects are allocated (via TLABs).
- **Escape analysis:** JIT analysis of whether an object escapes a method/thread; enables scalar replacement and lock elision.
- **FFM (Foreign Function & Memory) API:** JDK 21+ API for off-heap memory (`MemorySegment`/`Arena`) and native calls; deterministic freeing.
- **Frame (stack frame):** Per-method-call record with local variables and operand stack; lives on the JVM stack.
- **Full GC:** Collection of the entire heap (and often class metadata); usually the longest pause.
- **G1 (Garbage-First):** Default collector since JDK 9; region-based, generational, pause-target driven.
- **GC root:** Inherently reachable reference (thread stacks, statics, JNI globals) from which liveness is traced.
- **Generational hypothesis (weak):** Most objects die young; few old→young references.
- **Heap:** Shared, GC-managed region holding all objects/arrays; bounded by `-Xmx`.
- **HotSpot:** The dominant JVM implementation, in OpenJDK.
- **Inline cache:** Per-call-site cache of the last receiver type for fast monomorphic dispatch.
- **Inlining:** Replacing a call with the callee's body; the most impactful JIT optimization.
- **InstanceKlass:** HotSpot's internal class-metadata structure (in Metaspace).
- **Interpreter:** Executes bytecode directly; HotSpot uses a template interpreter; gathers profiling.
- **itable / vtable:** Interface/virtual method dispatch tables.
- **JFR (Java Flight Recorder):** Low-overhead built-in event recorder/profiler.
- **JIT (Just-In-Time) compiler:** Compiles bytecode to native code at runtime using profiling.
- **JNI (Java Native Interface):** API to call native (C/C++) code; uses the native method stack.
- **JOL (Java Object Layout):** Tool printing exact object layout/sizes.
- **Klass pointer:** Header field pointing to the object's class metadata.
- **Mark word:** Header word holding hashcode/GC age/lock state, reused polymorphically.
- **MDO (Method Data Object):** Per-method runtime profile (counts, branch/type data) used by the JIT.
- **Metaspace:** Native-memory region for class metadata (replaced PermGen in JDK 8); unbounded by default.
- **Method area:** Logical JVMS region for class metadata; HotSpot implements it as Metaspace.
- **Minor / major GC:** Young-gen / old-gen collection.
- **NMT (Native Memory Tracking):** JVM feature breaking native memory into categories.
- **oop (ordinary object pointer):** An object reference at the VM level.
- **oop map:** Metadata describing which stack/register slots hold references at a safepoint.
- **OSR (On-Stack Replacement):** Compiling a method while it runs, swapping a hot loop into compiled code.
- **PC register:** Per-thread pointer to the current bytecode instruction.
- **PermGen:** Pre-JDK 8 in-heap region for class metadata; removed.
- **Pinning (virtual threads):** A virtual thread that can't unmount from its carrier (e.g., inside `synchronized`/native), reducing scalability.
- **Promotion / tenuring:** Moving a surviving object from young to old gen.
- **RSS (Resident Set Size):** Physical RAM a process currently occupies.
- **Safepoint:** Execution point where a thread's state is fully described, so the VM can pause/inspect it.
- **Scalar replacement:** Eliminating a non-escaping object's allocation; its fields become registers/stack slots.
- **StackMapTable:** Class-file attribute giving the verifier expected types at branch targets for fast linear verification.
- **Stop-the-world (STW):** A pause halting all application threads.
- **String table:** Native hashtable for interned strings (the strings themselves live on the heap).
- **Survivor space (S0/S1):** Young-gen copy areas holding objects that survived minor GCs.
- **Symbolic vs direct reference:** Name/descriptor reference vs resolved concrete location.
- **Template interpreter:** HotSpot interpreter built from generated machine-code templates per bytecode.
- **Tenuring threshold:** Age at which a surviving object is promoted (≤ 15).
- **Tiered compilation:** Using interpreter + C1 + C2 in stages (levels 0–4).
- **TLAB (Thread-Local Allocation Buffer):** Per-thread Eden chunk enabling lock-free bump allocation.
- **TTSP (Time-to-safepoint):** Time until the last thread reaches a safepoint poll.
- **Verification:** Proving bytecode is type-safe and stack-disciplined before execution.
- **Virtual thread:** Lightweight thread (Project Loom) whose stack is a heap-stored continuation mounted on a carrier when running.
- **Young / Old generation:** Heap regions for short-lived (Eden+Survivors) and long-lived (tenured) objects.
- **Zero-based compressed oops:** Compressed-oop mode where decoding is a pure shift (no base add) because the heap starts at/near address 0.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Regions:** Heap (objects/arrays, `-Xmx`, generational young[Eden+S0/S1]+old) · Metaspace (class metadata, native, **unbounded by default**) · per-thread JVM stack (frames=locals+operand stack, `-Xss`) · PC register · native stack (JNI) · code cache (JIT, 240 MB) · direct/native memory (`-XX:MaxDirectMemorySize`). **Heap ≠ process: RSS = heap + Metaspace + stacks + code + GC + direct + native.**

**Object header (64-bit):** mark word (8B: hashcode/GC age≤15/lock) + klass pointer (4B compressed) [+ array length 4B]. **Compressed oops** default ≤ ~32 GB; **avoid 33–40 GB** (the cliff).

**Code path:** `javac`→bytecode → load → verify → link(verify/prepare/resolve, lazy) → init(`<clinit>` once, lazy) → **interpret + profile** → tiered JIT (0→3→4, C1→C2) → deopt on bad speculation. **OSR** for hot loops. **TLAB** bump-alloc in Eden. **Escape analysis** can scalar-replace.

**Errors → region:** `Java heap space`→heap · `Metaspace`→class metadata · `StackOverflowError`→stack depth(`-Xss`) · `unable to create new native thread`→native(threads×`-Xss`) · `Direct buffer memory`→direct · **exit 137, no Java OOM**→off-heap RSS (use NMT).

**Key flags:** `-Xms=-Xmx` (servers), `-Xmx`, `-Xss`, `-XX:MaxMetaspaceSize` (set it!), `-XX:MaxDirectMemorySize`, `-XX:ReservedCodeCacheSize`, `-XX:MaxRAMPercentage` (containers), `+HeapDumpOnOutOfMemoryError`, `+ExitOnOutOfMemoryError`, `+AlwaysPreTouch`, `-Xlog:gc*,safepoint`.

**Tools:** `jcmd` (everything: `GC.heap_info/heap_dump`, `VM.flags/native_memory/metaspace`, `Compiler.codecache`, `JFR.*`) · `jstat -gcutil` · `jmap -histo:live` · `jstack` · NMT · JFR/JMC · MAT (heap dumps) · JOL (layout) · async-profiler.

**Decision rules:** heap ≤31 GB or ≥48 GB (never 33–40) · cap Metaspace in containers · `-Xms=-Xmx`+pretouch for latency · off-heap/FFM for huge buffers & GC avoidance · virtual threads for blocking-I/O concurrency · always log GC + dump on OOM.

### 12.2 Self-test (no answers — recall practice)

1. A coworker says "I set `-Xmx2g` so the JVM can't use more than 2 GB." Correct them precisely: enumerate every memory region that lives *outside* the 2 GB heap and give the flag that bounds each (where one exists).
2. Trace `Foo f = new Foo(7);` from the `new` bytecode to the moment `f` holds a usable reference — name the bytecodes, where the object's bytes land first, and the two conditions under which the object might *not* be heap-allocated at all.
3. Two classes have identical bytes and the same fully-qualified name, yet `(Foo) otherFoo` throws `ClassCastException`. Explain exactly why, and what determines runtime class identity.
4. Your service logs report 4 ms GC pauses but p99 latency is 150 ms with no lock contention. List three non-GC causes rooted in this chapter and the single log flag you'd enable to confirm the most likely one.
5. Explain the "32 GB cliff": what optimization turns off, what changes in the object header and references, and why a 33 GB heap can hold *less* live data than a 31 GB heap. Give the two safe heap-sizing choices.
6. A container exits with code 137 and there is **no** `OutOfMemoryError` in the Java logs. Lay out your diagnosis sequence (tools and commands) and name the four off-heap categories most likely responsible.
7. Describe the three linking sub-phases, what "preparation" sets static fields to, and why resolution being *lazy* can turn a missing dependency into a mid-execution `NoSuchMethodError` rather than a startup failure.
8. What is stored in the mark word, why is the maximum tenuring threshold 15, and when/how is an object's identity hashcode computed and stored?
```

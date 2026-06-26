# Profiling & Low-Latency Tuning (JVM)

> A definitive engineering-handbook chapter for senior JVM backend developers. It builds from first principles to deep internals, with many runnable examples, real defaults, real tool names, and a methodology you can apply in production.

---

## 1. Overview & where it fits

**What it is.** *Profiling* is the discipline of measuring where a running program spends its resources — CPU cycles, wall-clock time, memory allocation, lock-wait time, I/O — so you can find and fix the parts that actually matter. *Low-latency tuning* is the engineering work of making the *tail* of a latency distribution (the slow requests — p99, p999, p9999) small and predictable, which on the JVM is dominated by garbage-collection pauses, allocation pressure, lock contention, JIT warmup, and OS-level scheduling jitter.

> **Latency vs throughput (define up front).** *Latency* is how long a single operation takes (e.g. one HTTP request: 2.3 ms). *Throughput* is how many operations complete per unit time (e.g. 80,000 requests/second). They are different goals and often in tension: a throughput-optimized GC (like the JVM's Parallel collector) will happily stop the world for 300 ms to collect more efficiently, which is fine for a batch job but catastrophic for a trading system. Low-latency work is about the *distribution* of latency, not just its average.

> **Percentile (p99, p999) — define up front.** If you sort all request latencies, the *p99* (99th percentile) is the value below which 99% of requests fall. p999 = 99.9th percentile; p9999 = 99.99th. The "tail" is everything past p99. Means and medians hide tail behavior; a service with a 1 ms median can still have a 200 ms p999 because of a GC pause that hit 0.1% of requests. **You tune for percentiles, never for averages.**

**The problem it solves.** Engineers' intuitions about *where* a program is slow are wrong most of the time. Studies and decades of war stories agree: the bottleneck is almost never where you guessed. Profiling replaces intuition with measurement. Low-latency tuning then addresses the specific JVM mechanisms that create *unpredictable* slowness — chiefly the garbage collector and the safepoint mechanism — so that p99/p999 stay bounded.

**When you reach for it.**
- A service's p99/p999 latency violates an SLO (Service Level Objective — a target like "p99 < 10 ms").
- CPU is pegged and you don't know which methods are burning it.
- The heap fills and GC frequency/pause times are hurting you.
- Throughput plateaus below the hardware's apparent capacity.
- You see periodic latency spikes that correlate with GC logs.
- You're designing a system (matching engine, market-data feed, ad-bidding, low-latency RPC) where tail latency is a first-class requirement.

**One-paragraph mental model.** A JVM program's performance is governed by four resource clocks running simultaneously: the **CPU clock** (instructions executed in your code and the runtime), the **allocation clock** (bytes/sec you allocate, which directly drives GC frequency), the **lock clock** (time threads spend blocked waiting for monitors/locks), and the **pause clock** (time the whole application is frozen — GC stop-the-world pauses, safepoint syncs, JIT deoptimizations, etc.). Profiling tells you which clock is dominating. CPU and lock problems hurt throughput and median latency; the pause clock and the allocation clock (which feeds the pause clock) dominate the *tail*. Low-latency tuning is mostly the art of shrinking the allocation clock and the pause clock until the tail goes flat.

---

## 2. Foundations from first principles

### 2.1 What a profiler actually measures

A profiler answers one of a few questions:

| Profile type | Question it answers | Unit | Typical use |
|---|---|---|---|
| **CPU** | Which methods consume CPU cycles? | samples ∝ on-CPU time | Find hot code, optimize algorithms |
| **Wall-clock** | Where does wall-clock time go (including blocked/sleeping)? | samples ∝ elapsed time | Find why latency is high when CPU isn't busy (I/O, locks, sleeps) |
| **Allocation** | Where are objects allocated, and how much? | bytes / object count | Find allocation hotspots driving GC |
| **Lock / monitor** | Where do threads block on locks? | blocked-time / count | Find contention |
| **Heap (live set)** | What is alive on the heap right now? | bytes by class & retainer | Find leaks, oversized caches |
| **I/O / syscall** | Where does time go in file/socket/syscalls? | time / count | Diagnose I/O-bound stalls |

The single most important distinction is **on-CPU vs off-CPU time**. A CPU profile shows where the thread was *running on a core*. A wall-clock profile shows where wall time went *including time the thread was parked* (waiting on a lock, sleeping, blocked on I/O). If your latency is high but CPU is idle, a CPU profiler will tell you almost nothing useful — you need wall-clock or lock profiling.

### 2.2 Sampling vs instrumentation

These are the two fundamental ways to gather a profile.

**Instrumentation (a.k.a. tracing).** The profiler injects bookkeeping code at method entry and exit (via bytecode rewriting at class-load time, or an agent). Every call increments a counter and accumulates time. 

- *Pros:* exact call counts; you know a method was called exactly 4,201,118 times.
- *Cons:* **enormous overhead and observer effect** — adding entry/exit hooks to tiny, hot methods can slow them 10–100×, and worse, it *changes the program's behavior*: it inhibits inlining (the JIT can't inline a method whose body is now full of instrumentation), changes the size of methods so they cross JIT thresholds differently, and inflates the apparent cost of cheap-but-frequent methods. The numbers you get may describe the instrumented program, not your real program. This is the **observer effect** in profiling.

> **Inlining (define).** *Inlining* is a JIT optimization that replaces a method call with a copy of the callee's body, eliminating call overhead and enabling further optimizations (escape analysis, constant folding) across the boundary. The HotSpot JIT inlines aggressively (default `MaxInlineSize=35` bytes for "trivially small" methods, `FreqInlineSize=325` bytes for hot ones). Instrumentation defeats inlining, which is precisely why instrumented profiles mislead.

**Sampling (a.k.a. statistical profiling).** Instead of measuring every call, the profiler periodically (say, every 10 ms) interrupts the program, captures each thread's current stack trace, and records it. Over thousands of samples, the methods that appear most often are statistically the ones consuming the most time. 

- *Pros:* low, *tunable* overhead (overhead ∝ sampling frequency, independent of how many method calls happen); doesn't inhibit JIT optimizations if done right; scales to production.
- *Cons:* statistical, not exact — you get "method X consumed ~37% of CPU ±2%", not call counts; rare-but-expensive events can be missed if your sampling interval is coarse; and the *naive* implementation has a deadly flaw called safepoint bias (next section).

> **Rule of thumb.** For production performance work, **prefer sampling.** Instrumentation is appropriate only for narrow questions (exact call counts of a specific method, code-coverage-style metrics) where you accept the distortion.

### 2.3 Safepoints and "safepoint bias" — the central JVM-specific gotcha

This is the concept that separates JVM profiling novices from experts, so we build it carefully.

> **Safepoint (define).** A *safepoint* is a point in the program's execution where the JVM knows the exact state of every thread's stack and registers — i.e., where the GC and the runtime can safely walk stacks, move objects, and read accurate metadata. The JVM compiler emits *safepoint polls* — tiny checks — at well-defined places: method returns, the back-edges of loops (so loops can be interrupted), and at allocation/call sites. When the JVM needs all threads to stop (for GC, for a `Thread.getStackTrace`, for biased-lock revocation, etc.), it sets a flag; each thread, on reaching its next safepoint poll, sees the flag and parks itself. This coordinated halt is **"reaching a safepoint"** or **"stop-the-world."**

> **Why safepoints exist.** Walking a thread's stack to find object references (so GC can mark/move them) is only correct if the JIT-compiled code's metadata (the *oop maps* — "ordinary object pointer" maps that say which registers/stack slots hold references) is valid. That metadata is only guaranteed valid *at safepoints*. Between safepoints, the optimizer is free to keep references in scratch registers, reorder things, etc. So the JVM can only inspect a stack when the thread is parked at a safepoint.

**Now the bias.** The standard JVM API for "get me a thread's stack" is `JVMTI`'s `GetStackTrace` / `GetAllStackTraces` (used by `jstack`, `Thread.getStackTrace`, and *most* old-school Java profilers like the original hprof/JVMTI-based samplers). **These APIs only return a stack trace at a safepoint.** So when such a profiler wants to sample, it actually has to (a) request a global safepoint or wait for the thread to reach one, then (b) read the stack.

> **JVMTI (define).** *JVM Tool Interface* — a native (C/C++) API the JVM exposes for agents (debuggers, profilers) to inspect and control a running JVM: get stacks, set breakpoints, watch allocations, etc. Powerful but, for stack sampling, fundamentally safepoint-bound.

The consequence — **safepoint bias** — is that samples are not taken at random points in your code. They cluster at safepoint locations. Long stretches of code *between* safepoints (e.g., a tight counted loop the JIT decided not to put back-edge polls in, or an inlined run of arithmetic) become *invisible*: the thread is never sampled *inside* them because it's never paused there. Worse, the sample is attributed to the *next safepoint* the thread reaches, which may be a completely different method. The classic demonstration: a microbenchmark where a safepoint-biased profiler blames method `B` for the cost actually incurred in method `A`, because `A` has no safepoint poll and the thread only stops once it returns into `B`.

> **Concrete example of the distortion.** The JIT often performs *loop-strip-mining* or simply omits safepoint polls from short counted loops it can prove terminate quickly; it may also "hoist" the safepoint poll out of an inlined hot loop. A safepoint-biased profiler will then never sample inside that loop. If that loop is your real hotspot, the profiler points you at the wrong method entirely. This is not rare — it has wasted countless engineer-days.

**The fix: `AsyncGetCallTrace` and signal-based sampling.** HotSpot exposes a private, unsupported-but-de-facto-stable API called `AsyncGetCallTrace` (AGCT). It can walk a thread's stack *from an arbitrary point*, not just at a safepoint, by being called from inside an OS signal handler. async-profiler (and JFR's "method profiling" event in modern JDKs) use this. The mechanics:

1. The profiler installs a handler for a timer signal — `SIGPROF` (for CPU/`ITIMER_PROF`) or via `perf_events` (for true CPU-cycle sampling), or `SIGALRM`-style for wall-clock.
2. The OS delivers the signal to a thread *wherever it currently is* — mid-loop, mid-arithmetic, anywhere.
3. The signal handler calls `AsyncGetCallTrace`, which reconstructs the Java stack from the current frame pointer / metadata without requiring a safepoint.
4. The stack is recorded into a lock-free ring buffer; the actual writing/aggregation happens later, off the signal path.

Because the sample is taken *where the thread really is*, there is **no safepoint bias**. This is the single biggest reason async-profiler and modern JFR are trustworthy where old JVMTI samplers were not.

> **`AsyncGetCallTrace` caveats.** AGCT can occasionally fail to walk a stack (returns an error code for "unknown Java frame," GC in progress, thread in a stub, etc.), producing samples attributed to a synthetic frame like `[unknown_Java]` or showing up as `[deoptimization]`. For accurate native+Java unwinding, async-profiler can also use the JDK's `-XX:+PreserveFramePointer` and DWARF/frame-pointer unwinding, which is why that flag matters (see §7).

### 2.4 Flame graphs — how to read a profile

> **Flame graph (define).** A *flame graph* (invented by Brendan Gregg) is a visualization of aggregated stack samples. The **x-axis is *not* time** — it is the set of stacks sorted alphabetically and merged; the **width of a box is the fraction of samples in which that frame (and its callees) appeared**, i.e., proportional to time/CPU/allocation spent there. The **y-axis is stack depth**: callers below, callees stacked on top. A wide box high up the stack = a leaf method that itself burns resources. A wide box that narrows as you go up = a method whose cost is spread across many callees.

How to read one in practice:
- **Look for the widest plateaus.** A wide box near the top means a leaf consuming lots of resource directly — a prime optimization target.
- **Follow wide stacks downward** to understand the call path that leads to the cost.
- **"Icicle" graphs** (some tools, and JFR's flame view) are flame graphs flipped vertically (root at top). Same meaning.
- **Differential flame graphs** color frames by how much they changed between two profiles (red = got worse) — invaluable for before/after comparisons.
- Frames named `[unknown]`, `[interpreter]`, `[deoptimization]`, `vtable stub`, `itable stub`, JNI frames, and GC threads are all meaningful — don't filter them blindly; they tell you about JIT state, megamorphic calls, etc.

### 2.5 The cost of allocation, and why it drives latency

On the JVM, allocation is cheap *per object* (a pointer bump in a thread-local buffer) but **the aggregate allocation rate is the single biggest lever on GC frequency**, and GC is the biggest source of tail latency.

> **TLAB — Thread-Local Allocation Buffer (define).** Each thread gets a private chunk of the Eden region (the young-generation nursery). Allocating an object is normally just `top += size; if (top > end) slow_path()` — a *pointer bump*, no locking. When the TLAB is full, the thread grabs a new one (a rare, synchronized op). This is why allocation looks "free" in microbenchmarks. But every byte you allocate fills Eden, and when Eden fills, a **young (minor) GC** happens — a stop-the-world pause (in most collectors) to evacuate survivors. So: **allocation rate (MB/s) ≈ the metronome that sets young-GC frequency.** Halve your allocation rate and you roughly halve your young-GC frequency and thus your GC-induced tail latency.

> **Generational hypothesis (define).** Most objects die young. So the JVM splits the heap into a **young generation** (Eden + two survivor spaces, S0/S1) and an **old/tenured generation**. Minor GCs collect the young gen frequently and cheaply (most of it is garbage); objects that survive enough minor GCs are *promoted/tenured* to old gen, which is collected by slower major/full GCs. Understanding this is essential because *the goal of low-allocation programming is to keep the young gen turning over fast and cheap, and to keep promotion (which feeds expensive old-gen GC) low.*

We will return to all of this in the low-latency techniques section. The takeaway for now: **profiling allocation is as important as profiling CPU when you care about latency.**

### 2.6 What actually causes the tail (the pause clock)

Sources of stop-the-world / jitter on the JVM, roughly in order of how often they bite:

1. **GC pauses** — young-gen evacuation pauses, old-gen/full GC pauses, concurrent-phase failures (allocation outpacing concurrent collection → fallback to stop-the-world).
2. **Safepoint sync time** — the time to get *all* threads to a safepoint before the actual operation. A single thread stuck in a long counted loop (no safepoint poll) or in a long JNI call can hold up *everyone* ("time to safepoint," TTSP). This is invisible in GC logs unless you ask for it.
3. **JIT compilation & deoptimization** — compiler threads, and *deopts* where the JVM throws away optimized code and falls back to the interpreter (e.g., when a speculative assumption is invalidated). During warmup this is huge.
4. **Biased-lock revocation** (legacy, deprecated/removed in modern JDKs) — required a safepoint.
5. **OS scheduling jitter** — the kernel preempts your thread, migrates it to a cold core, a noisy neighbor steals CPU, an interrupt hits your core, the page fault handler runs, transparent huge pages get defragmented, etc.
6. **Page faults / swap / NUMA effects** — touching memory that's been paged out, or accessing far-NUMA memory.
7. **Lock contention** — not a "pause" per se, but produces tail latency when a request blocks behind a held lock.

Low-latency tuning systematically attacks each of these.

---

## 3. How it works internally

This section traces the actual machinery, step by step, of the two tools you'll use 95% of the time — **JFR** and **async-profiler** — plus the safepoint/sampling internals that make them trustworthy.

### 3.1 Internal workflow of a signal-based CPU sample (async-profiler / JFR `perf` mode)

Here is the full control/data flow of one CPU sample, end to end:

1. **Timer setup.** The profiler arms a periodic source. Two options:
   - **`perf_events` (Linux PMU)**: the profiler asks the kernel, via `perf_event_open(2)`, to count `cpu-cycles` (or `instructions`, `cache-misses`, etc.) per-thread, and to deliver a signal after every N events (the *period*). This samples *real CPU cycles*, so a thread that's descheduled does not accumulate samples — true on-CPU profiling. Requires `perf_event_paranoid` to permit it.
   - **`ITIMER_PROF`**: a POSIX interval timer that fires `SIGPROF` based on CPU time consumed by the process. Coarser, but needs no special perf permissions. async-profiler falls back to this if `perf_events` is unavailable.
2. **Signal delivery.** The kernel delivers the signal to a thread that is currently on-CPU, *at whatever instruction it's executing* — no waiting for a safepoint.
3. **Signal handler runs.** Inside the (async-signal-safe) handler, the profiler calls `AsyncGetCallTrace(trace, depth, ucontext)`. AGCT uses the `ucontext` (the saved register state at the moment of the signal) to find the current frame, then walks the Java stack using HotSpot's internal frame-walking logic and the compiled-method metadata.
   - For mixed native/Java stacks, async-profiler can also unwind native frames using frame pointers (hence `-XX:+PreserveFramePointer`) or DWARF unwind info.
4. **Record into ring buffer.** The captured stack (an array of `jmethodID`s + bytecode indices, or raw PCs for native) is written into a **lock-free per-thread ring buffer**. No allocation, no locks, nothing that could deadlock from a signal handler.
5. **Asynchronous aggregation.** A separate processing path drains the ring buffers, resolves `jmethodID`s to method names (deferred so the signal handler stays fast), and increments counters in a *calltrace tree* (a prefix tree of stacks → sample counts).
6. **Output.** On stop/dump, the calltrace tree is rendered to a flame graph (HTML/SVG), collapsed-stack text (the `folded` format Brendan Gregg's `flamegraph.pl` consumes), JFR file, or a plain hot-methods list.

**Overhead:** at the default ~10 ms CPU sampling interval, overhead is typically **well under 1%** — low enough to run in production continuously. async-profiler is explicitly designed for "always-on in prod."

### 3.2 Internal workflow of an allocation sample

Allocation profiling cannot use a timer — you want to sample *allocation events*, not time. There are two mechanisms:

**(a) JFR / async-profiler via TLAB hooks (the modern, low-overhead way).** The JVM emits an event each time a thread either (i) allocates an object too big to fit in the current TLAB (`ObjectAllocationOutsideTLAB`) or (ii) crosses into a fresh TLAB (`ObjectAllocationInNewTLAB`). Because these events fire roughly once per TLAB-worth of allocation (TLABs are typically tens to hundreds of KB), they're a *sampled* view of allocation — cheap, but statistical. Each event carries the allocating stack, the class, and the size. async-profiler's `-e alloc` mode and JFR's allocation events both ride this. Overhead is low because you don't intercept every `new`.

> **Why not intercept every allocation?** Because allocation is a pointer bump on the hot path; adding a callback to every `new` would obliterate performance and inhibit *scalar replacement* (see below). TLAB-boundary sampling is the right tradeoff.

**(b) JVMTI `SampledObjectAlloc` (JDK 11+).** A supported JVMTI callback that fires after roughly every N bytes allocated (configurable sampling interval, default 512 KB). Used by tools that want a JVMTI-clean API. Same statistical idea.

> **Scalar replacement & escape analysis (define, because allocation profiling can lie about it).** The JIT's *escape analysis* proves that some objects never "escape" the method that created them (no reference leaks to other threads or the heap). It can then **not allocate them at all** — it explodes the object into its scalar fields kept in registers (*scalar replacement*) or stack-allocates it. So an allocation profiler may show *zero* allocation for a `new` that your source clearly contains, because the JIT eliminated it. This is a feature, not a bug — but it means **allocation profiles only show allocations the JIT actually performed**, which is what you want for GC purposes anyway.

### 3.3 Internal workflow of lock/monitor & wall-clock profiling

**Lock profiling (async-profiler `-e lock`, JFR `jdk.JavaMonitorEnter`/`jdk.ThreadPark`).** The JVM emits events when a thread *blocks* trying to enter a `synchronized` monitor that's contended, or when it parks on a `java.util.concurrent` lock (`LockSupport.park`, which `ReentrantLock`, `ABQ`, etc. use). The event records the blocked stack and the duration of the block, and (for monitors) the *blocking* object's class. Aggregating these by stack and summing durations gives a "where do we lose time to contention" flame graph.

> **Monitor vs `java.util.concurrent` lock (define).** A *monitor* is the intrinsic lock behind `synchronized` (every Java object has one). `java.util.concurrent.locks` (e.g. `ReentrantLock`, `StampedLock`) are library locks built on `AbstractQueuedSynchronizer` and `LockSupport.park/unpark`. JFR captures both, but via different events (`JavaMonitorEnter` for the former, `ThreadPark` for the latter). Know which you're using when reading a lock profile.

**Wall-clock profiling (async-profiler `-e wall`).** Instead of sampling only on-CPU threads, async-profiler periodically samples *all* threads (or a chosen set) regardless of whether they're running or parked. This reveals time spent *off-CPU*: blocked on locks, sleeping, waiting on I/O, parked in a thread pool. Essential when latency is high but CPU is idle. The mechanism: a timer thread periodically signals each target thread and captures its stack via AGCT; parked threads show their parked stack (e.g., `Unsafe.park → ...`).

### 3.4 JFR (Java Flight Recorder) internals — the lifecycle

> **What JFR is.** *Java Flight Recorder* is a **built-in, always-available, ultra-low-overhead event recording engine inside the HotSpot JVM**. It is not a bolt-on agent — it's compiled into the JVM. It records a stream of typed *events* (GC, allocation, locks, exceptions, I/O, method samples, JIT compilations, safepoints, custom app events…) into thread-local buffers, then to disk or a memory ring. Origin: BEA JRockit's "Flight Recorder"; integrated into Oracle JDK 7u40, open-sourced in JDK 11 (so it's free in OpenJDK 11+; on JDK 8 it required a commercial flag).

The JFR data lifecycle:

1. **Event generation.** Code paths in the JVM (and your app, via the JFR API) call into JFR when an event occurs. Events are *typed* with a schema (a list of fields). Many events are extremely cheap to emit because they're just a struct written to a buffer.
2. **Thread-local buffers.** Each thread writes its events to a small *thread-local buffer* — no contention. This is why JFR overhead is so low (often quoted ~1% with the default profile; near-zero for the "continuous" settings).
3. **Global buffer / ring.** Full thread-local buffers are flushed to a *global buffer*. In a *time-fixed* or *size-fixed* recording, old data is overwritten in a ring (so you always have "the last N minutes/MB"). This is the "flight recorder" model: you keep recording, and when something bad happens you *dump* the ring to capture what just happened.
4. **Chunked disk format.** JFR writes self-describing *chunks* to a `.jfr` file. Each chunk has its own metadata (the event-type schema) and constant pool (interned strings, stack traces, class names) so a chunk is independently parseable. This makes JFR files streamable and robust to truncation.
5. **Settings / event configuration.** Which events are enabled, their thresholds (e.g., only record locks held > 10 ms), and stack-depth limits come from a *settings profile*. Two ship by default: `default.jfc` (very low overhead, safe for prod) and `profile.jfc` (more events, more detail, slightly higher overhead). You can author custom `.jfc` files.
6. **Consumption.** Dump to `.jfr`, then open in **JDK Mission Control (JMC)** or parse programmatically with the `jdk.jfr.consumer` API / `jfr` CLI, or stream live (JDK 14+ *event streaming*, `jdk.jfr.consumer.EventStream`).

> **JDK Mission Control (JMC) — define.** *JMC* is the GUI tool (separate download from the JDK since JDK 11) for analyzing `.jfr` recordings: flame graphs, GC analysis, allocation breakdowns, lock contention, the **Automated Analysis** page (rules that flag likely problems with red/yellow/green), latency/exception views, and more. It's the canonical front-end for JFR data.

### 3.5 The safepoint lifecycle (so you can debug TTSP)

When the JVM needs a global safepoint (e.g., a young GC):

1. **VM thread sets the global "safepoint requested" flag** and arms the polling page (on HotSpot, the safepoint poll is implemented as a memory read of a special *polling page*; the VM thread `mprotect`s that page to be inaccessible so the next poll *page-faults*, which the JVM's fault handler interprets as "go to safepoint" — an elegant zero-cost-when-not-needed trick).
2. **Each application (mutator) thread, at its next safepoint poll**, traps and parks itself, recording its state.
3. **The VM waits** until *all* threads have reached a safepoint. The wall time for this is **Time To SafePoint (TTSP)**. A thread in a long counted loop without back-edge polls, or in a long blocking JNI call (JNI calls are "safepoint-safe" so they don't block the safepoint, but a thread *returning* from JNI must wait), can make TTSP large.
4. **The VM operation runs** (GC, deopt, etc.) while everyone is parked.
5. **The VM clears the flag / un-`mprotect`s the page**, threads resume.

> **Why this matters for latency.** GC logs show you the *operation* time, but the *sync* time (TTSP) is separate and often overlooked. A 50 ms latency spike with a 2 ms reported GC pause is suspicious — the other 48 ms may be TTSP. You expose it with `-Xlog:safepoint` (JDK 9+) or `-XX:+PrintSafepointStatistics -XX:+PrintGCApplicationStoppedTime` (older). See §9.

### 3.6 GC internals that govern the pause clock (brief, because it's the other half of low-latency)

You don't have to be a GC author, but you must know the *shape* of each collector's pauses:

- **Serial / Parallel (throughput collectors):** stop-the-world for *all* GC work. Parallel uses multiple threads but still STW. Pauses scale with live-set/heap size. Great throughput, bad tails. Default in some configs for small heaps.
- **G1 (Garbage-First, default since JDK 9):** region-based, *mostly* concurrent marking with STW *evacuation* pauses that it tries to keep under a soft target (`-XX:MaxGCPauseMillis`, default 200 ms). Tail pauses in the tens-to-low-hundreds of ms typically. Good general-purpose default.
- **ZGC (Z Garbage Collector):** concurrent, region-based, uses **colored pointers** and **load barriers** to relocate objects concurrently. Designed for **sub-millisecond, pause-time-independent-of-heap-size** pauses. The generational ZGC (default form of ZGC since JDK 21) added a young/old split for much better throughput. The collector of choice for low-latency, large-heap services. (Production GA in JDK 15; generational GA in JDK 21.)
- **Shenandoah:** Red Hat's concurrent collector, similar goals to ZGC (concurrent evacuation via *Brooks pointers* / load-reference barriers), also targets low pauses independent of heap size. Available in many OpenJDK builds.

> **Colored pointers & load barriers (define, since ZGC is the low-latency pick).** ZGC stores metadata *in unused bits of the 64-bit object pointer* ("colors": marked, remapped, etc.). A *load barrier* is a few instructions the JIT injects on every reference load that checks the pointer's color and, if the object has been relocated, fixes the pointer ("self-healing") — *concurrently*, while the app runs. This lets ZGC move objects without stopping the world, which is how it gets sub-millisecond pauses. The cost is a small per-load overhead (a few % throughput), traded for flat tails.

We tie collector choice to latency goals in §7 and §8.

---

## 4. The complete toolkit

### 4.1 JDK command-line / diagnostic tools

| Tool | Purpose | Key usage / flags | Notes |
|---|---|---|---|
| `jcmd` | Swiss-army diagnostic command sender to a running JVM | `jcmd <pid> <command>`; e.g. `JFR.start`, `JFR.dump`, `JFR.stop`, `GC.heap_info`, `GC.class_histogram`, `Thread.print`, `VM.flags`, `VM.native_memory` | The modern entry point; replaces many older tools |
| `jstack` | Dump thread stacks | `jstack <pid>`; `-l` for lock info | Safepoint-biased for sampling, but fine for one-off "what are threads doing / deadlock?" |
| `jmap` | Heap dumps & histograms | `jmap -dump:live,format=b,file=heap.hprof <pid>`; `jmap -histo:live <pid>` | `live` forces a full GC first; prefer `jcmd <pid> GC.heap_dump` |
| `jstat` | Lightweight GC/heap stats over time | `jstat -gcutil <pid> 1000` (every 1 s) | Shows eden/survivor/old/metaspace utilization, GC counts/times — great cheap monitoring |
| `jinfo` | View/set JVM flags at runtime | `jinfo -flag MaxGCPauseMillis <pid>` | Some flags are manageable at runtime |
| `jfr` | Parse/print/summarize `.jfr` files from CLI | `jfr summary rec.jfr`, `jfr print --events jdk.GCPhasePause rec.jfr` | No GUI needed; scriptable |
| `jhsdb` | Serviceability Agent (post-mortem debugger) | `jhsdb jstack --core core.<pid> --exe <java>` | Inspect core dumps / hung JVMs |
| `jdb` | The JDK debugger | rarely used for perf | — |
| JMC (`jmc`) | GUI for JFR analysis | separate download | Automated Analysis, flame graphs, etc. |

### 4.2 JFR controls (flags & `jcmd` subcommands)

| Control | What it does | Defaults / notes |
|---|---|---|
| `-XX:+FlightRecorder` | Enable JFR (implicit/unnecessary on JDK 11+) | On JDK 8 also needs `-XX:+UnlockCommercialFeatures` |
| `-XX:StartFlightRecording=...` | Start a recording at JVM launch | e.g. `-XX:StartFlightRecording=duration=60s,filename=rec.jfr,settings=profile` |
| `settings=default` / `settings=profile` | Choose event profile | `default` ≈ very low overhead (prod-safe); `profile` ≈ more detail/overhead |
| `disk=true`, `maxage=…`, `maxsize=…` | Continuous recording with bounded ring | e.g. `maxage=10m,maxsize=200m` for "last 10 min" |
| `dumponexit=true`, `filename=…` | Auto-dump on JVM exit | useful in CI/load tests |
| `jcmd <pid> JFR.start name=r1 settings=profile` | Start at runtime | |
| `jcmd <pid> JFR.dump name=r1 filename=out.jfr` | Snapshot the ring to disk | the "pull the black box" move |
| `jcmd <pid> JFR.stop name=r1` | Stop recording | |
| `jcmd <pid> JFR.check` | List active recordings | |
| `-XX:FlightRecorderOptions=stackdepth=…` | Stack depth captured (default 64) | raise if stacks get truncated |
| Event-level: `jdk.ObjectAllocationSample`, `jdk.ExecutionSample`, `jdk.GCPhasePause`, `jdk.JavaMonitorEnter`, `jdk.ThreadPark`, `jdk.SafepointBegin` | The actual event types you'll filter on | inspect with `jfr metadata` |

> **JFR method-profiling note.** `jdk.ExecutionSample` is JFR's CPU sampling event; historically it could be *safepoint-biased* depending on JDK version because JFR used a different sampler. Modern JDKs improved this; for the most trustworthy CPU flame graphs many teams still prefer async-profiler, but JFR's allocation/lock/GC events are excellent and unique.

### 4.3 async-profiler (the de-facto open-source profiler)

> **What it is.** A low-overhead, safepoint-bias-free sampling profiler for the JVM (open source, by Andrei Pangin). Modes: CPU (`perf_events`), allocation, lock, wall-clock, plus hardware counters (cache misses, etc.). Outputs flame graphs (HTML/SVG), collapsed stacks, JFR, and a text hot-methods list. Two ways to drive it: the `asprof` CLI (formerly `profiler.sh`), or as a `-agentpath` / a JFR-recorder integration.

| async-profiler control | Meaning | Default / notes |
|---|---|---|
| `-e cpu` | CPU sampling via `perf_events` cycles | falls back to `itimer` if perf unavailable |
| `-e alloc` | Allocation profiling via TLAB events | sample size tunable |
| `-e lock` | Lock/monitor contention | |
| `-e wall` | Wall-clock (all threads, on+off CPU) | great for latency-when-idle |
| `-e cache-misses` / `-e <perf event>` | Hardware PMU events | needs perf access |
| `-i <interval>` | Sampling interval (e.g. `-i 1ms` or `-i 1m` for alloc bytes) | CPU default ~10 ms |
| `-d <sec>` | Duration | |
| `-f profile.html` | Output flame graph | `.html`/`.svg`/`.collapsed`/`.jfr` by extension |
| `-t` | Per-thread profile (split flame graph by thread) | |
| `--alloc <bytes>` | Allocation sampling threshold | |
| `-o flamegraph|collapsed|tree|jfr` | Output format | |
| `asprof start/stop/dump <pid>` | Control a running JVM | attach without restart |
| `-XX:+PreserveFramePointer` (JVM flag) | Enables reliable native-frame unwinding | **highly recommended** when using async-profiler; small (~1-2%) overhead |

Example CLI:
```bash
# Profile CPU for 30s on a running pid, write a flame graph
asprof -d 30 -e cpu -f /tmp/cpu.html <pid>

# Allocation flame graph, sampling every ~512 KB allocated
asprof -d 30 -e alloc --alloc 512k -f /tmp/alloc.html <pid>

# Wall-clock, per-thread, to find off-CPU latency
asprof -d 30 -e wall -t -f /tmp/wall.html <pid>

# Lock contention
asprof -d 30 -e lock -f /tmp/lock.html <pid>
```

### 4.4 Microbenchmarking: JMH

> **JMH — Java Microbenchmark Harness (define).** The official OpenJDK tool for writing *correct* microbenchmarks. It handles warmup (so the JIT has compiled your code), dead-code elimination (via `Blackhole`), constant folding, fork isolation, and statistical reporting. **Hand-rolled `System.nanoTime()` microbenchmarks are almost always wrong** because of JIT warmup, dead-code elimination, and on-stack replacement; JMH exists to prevent those mistakes.

Key annotations/concepts: `@Benchmark`, `@State`, `@Setup`, `@TearDown`, `@Warmup`, `@Measurement`, `@Fork`, `@BenchmarkMode` (Throughput / AverageTime / SampleTime — *SampleTime gives you percentiles*), `Blackhole.consume(...)` (defeats dead-code elimination), `@CompilerControl`.

### 4.5 Latency measurement: HdrHistogram & friends

> **HdrHistogram (define).** *High Dynamic Range Histogram* (Gil Tene) — a data structure that records values across a huge range (nanoseconds to hours) with configurable precision, in **constant time and constant memory**, with **no allocation on the recording path** and accurate percentiles. The standard tool for measuring p99/p999/p9999 correctly. Crucially it supports **coordinated-omission correction** (next section).

> **Coordinated omission (define — this is a senior-signal concept).** When a load generator sends a request, waits for the response, *then* sends the next, a long-latency response *delays the next send*, so the slow period is under-sampled — you "coordinate" your measurement with the system under test and **systematically hide the tail**. The fix: record latency against the *intended* send schedule, not the actual one (HdrHistogram's `recordValueWithExpectedInterval`, or use an open-loop/constant-throughput load model like wrk2, Gatling's closed-vs-open workload models). **A p999 measured under coordinated omission can be 10–100× too optimistic.**

### 4.6 GC logging & analysis

| Flag (JDK 9+, unified logging) | Effect |
|---|---|
| `-Xlog:gc*:file=gc.log:time,uptime,level,tags` | Full GC log with timestamps |
| `-Xlog:gc+heap=debug` | Heap region details |
| `-Xlog:safepoint` | Safepoint sync (TTSP) and operation times |
| `-Xlog:gc+cpu` | GC CPU usage |
| `-XX:+PrintGCApplicationStoppedTime` (older / still works) | Total app-stopped time incl. non-GC safepoints |

Offline analyzers: **GCeasy** (web), **GCViewer** (open source), JMC's GC pages. They compute pause distributions, throughput %, allocation rate, promotion rate.

### 4.7 OS / kernel tools (low-latency work bleeds into the OS)

| Tool | Purpose |
|---|---|
| `perf` (Linux) | CPU profiling, PMU counters, `perf sched` for scheduler latency, `perf c2c` for false sharing/cache-line contention |
| `pidstat`, `mpstat`, `vmstat` | Per-process/per-CPU utilization, context switches, run-queue |
| `taskset`, `numactl` | CPU affinity / NUMA binding (pinning, see §7) |
| `chrt` | Set real-time scheduling class/priority |
| `ftrace` / `bpftrace` / `bcc` | Kernel tracing, off-CPU analysis, scheduler latency, page faults |
| `turbostat` | CPU frequency / C-states (frequency scaling causes jitter) |
| Java Thread Affinity (OpenHFT) | Pin Java threads to cores from within the JVM |

---

## 5. Code examples by use case

### 5.1 Use case: kick off a continuous, prod-safe JFR recording and dump it when an SLO breach fires

```bash
# At launch: keep the last 10 minutes / 250 MB in a ring on disk, low-overhead profile.
java \
  -XX:StartFlightRecording=disk=true,maxage=10m,maxsize=250m,settings=profile,name=cont \
  -XX:FlightRecorderOptions=stackdepth=128 \
  -jar service.jar

# Later, when alerting detects a p99 breach, snapshot the black box:
PID=$(pgrep -f service.jar)
jcmd $PID JFR.dump name=cont filename=/var/tmp/breach-$(date +%s).jfr
# Open in JMC, or summarize from CLI:
jfr summary /var/tmp/breach-1700000000.jfr
jfr print --events jdk.GCPhasePause,jdk.SafepointBegin /var/tmp/breach-1700000000.jfr | head -50
```
*Why this shape:* continuous low-overhead recording means the data you need is *already captured* when the rare incident occurs — you don't have to reproduce it.

### 5.2 Use case: find the hottest methods on a live service (CPU flame graph), no restart

```bash
PID=$(pgrep -f service.jar)
# 60-second CPU flame graph via async-profiler, attaching to the running JVM.
asprof -d 60 -e cpu -f /tmp/cpu.html $PID
# Open /tmp/cpu.html in a browser; widest top plateaus = hot leaves.

# If you can't use the wrapper, attach the agent directly:
java -agentpath:/opt/async-profiler/lib/libasyncProfiler.so=start,event=cpu,file=/tmp/cpu.html,flat=20 ...
```
*Reading it:* a wide top box in, say, `String.format` or a JSON serializer means you're burning CPU formatting — a common, fixable hotspot.

### 5.3 Use case: hunt an allocation hotspot driving GC

```bash
PID=$(pgrep -f service.jar)
asprof -d 60 -e alloc --alloc 256k -f /tmp/alloc.html $PID
```
Then in code, the flame graph might point at autoboxing in a hot loop. Before/after:

```java
// BEFORE: allocates an Integer per iteration via autoboxing into a Map<Integer, ...>,
// plus an iterator and lambda capture. Millions/sec of garbage.
Map<Integer, Long> counts = new HashMap<>();
for (int id : ids) {
    counts.merge(id, 1L, Long::sum);   // boxes int->Integer, 1L->Long each call
}

// AFTER: primitive-keyed map (e.g. Eclipse Collections / fastutil) — zero boxing.
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
IntLongHashMap counts = new IntLongHashMap();
for (int id : ids) {
    counts.addToValue(id, 1L);         // no boxing, no per-entry object churn
}
```
*Why it matters:* boxing in hot loops is one of the most common allocation hotspots; eliminating it can cut allocation rate by an order of magnitude, directly reducing young-GC frequency and thus tail latency.

### 5.4 Use case: diagnose latency-when-CPU-is-idle with a wall-clock profile

```bash
PID=$(pgrep -f service.jar)
asprof -d 60 -e wall -t -f /tmp/wall.html $PID
```
Suppose the flame graph shows request threads parked in `PoolBase.borrowObject → Object.wait` — you're starved on a connection pool, not on CPU. Fix is pool sizing or query latency, not code optimization. *A CPU profile would have shown nothing here* — this is exactly what wall-clock mode is for.

### 5.5 Use case: find and fix lock contention

```bash
asprof -d 60 -e lock -f /tmp/lock.html $(pgrep -f service.jar)
```
Say it points at a `synchronized` counter:

```java
// BEFORE: every increment serializes all threads on the monitor.
class Stats {
    private long hits;
    synchronized void hit() { hits++; }
    synchronized long hits() { return hits; }
}

// AFTER: LongAdder spreads contention across striped cells; reads sum them.
import java.util.concurrent.atomic.LongAdder;
class Stats {
    private final LongAdder hits = new LongAdder();
    void hit() { hits.increment(); }      // contends on a per-thread cell, not one lock
    long hits() { return hits.sum(); }
}
```
> **`LongAdder` (define).** A concurrent counter that maintains multiple internal cells; threads update different cells to avoid contending on a single memory location (and avoid *false sharing*). `sum()` adds the cells. Far better than `AtomicLong` or `synchronized` under high write contention, at the cost of approximate reads and more memory.

### 5.6 Use case: measure p99/p999 correctly with HdrHistogram (with coordinated-omission correction)

```java
import org.HdrHistogram.Histogram;

public class LatencyMeter {
    // Records 1ns..60s with 3 significant digits of precision.
    private final Histogram hist = new Histogram(60_000_000_000L, 3);
    private final long expectedIntervalNanos; // your INTENDED inter-request gap

    LatencyMeter(long targetRps) {
        this.expectedIntervalNanos = 1_000_000_000L / targetRps;
    }

    void record(long startNanos, long endNanos) {
        long latency = endNanos - startNanos;
        // Coordinated-omission correction: if a slow request delayed the next send,
        // synthesize the missed samples so the tail isn't hidden.
        hist.recordValueWithExpectedInterval(latency, expectedIntervalNanos);
    }

    void report() {
        System.out.printf("p50=%.3fms p99=%.3fms p999=%.3fms p9999=%.3fms max=%.3fms%n",
            hist.getValueAtPercentile(50)   / 1e6,
            hist.getValueAtPercentile(99)   / 1e6,
            hist.getValueAtPercentile(99.9) / 1e6,
            hist.getValueAtPercentile(99.99)/ 1e6,
            hist.getMaxValue()              / 1e6);
    }
}
```
*Why:* using `recordValueWithExpectedInterval` (vs plain `recordValue`) is the difference between an honest p999 and one that lies by 10–100× under load.

### 5.7 Use case: a percentile-aware JMH microbenchmark

```java
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SampleTime)            // SampleTime => JMH reports percentiles, not just avg
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)          // let the JIT compile before measuring
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgs = {"-XX:+UseZGC"})// isolate runs; pin GC choice
@State(Scope.Thread)
public class ParseBench {
    private String payload;

    @Setup public void setup() { payload = "{\"id\":42,\"name\":\"x\"}"; }

    @Benchmark
    public void parse(Blackhole bh) {
        // Blackhole.consume prevents the JIT from deleting the result as dead code.
        bh.consume(MyJson.parse(payload));
    }
}
```
JMH will print `p0.99`, `p0.999`, etc. *Always use `Mode.SampleTime` (or `Throughput` for throughput) and a `Blackhole`* — otherwise the JIT may optimize your benchmark into nothing.

### 5.8 Use case: emit a custom JFR event so app-level latency lands in the same recording as GC/locks

```java
import jdk.jfr.*;

@Name("com.acme.OrderProcessed")
@Label("Order Processed")
@Category({"Acme", "Trading"})
@StackTrace(false)                          // skip stack capture for speed on a hot path
class OrderProcessedEvent extends Event {
    @Label("Order ID") long orderId;
    @Label("Latency (ns)") @Timespan(Timespan.NANOSECONDS) long latencyNanos;
}

void onOrder(long id, long startNanos) {
    OrderProcessedEvent e = new OrderProcessedEvent();
    e.begin();                              // captures start; pairs with commit timing
    // ... process the order ...
    e.orderId = id;
    e.latencyNanos = System.nanoTime() - startNanos;
    if (e.shouldCommit()) e.commit();       // shouldCommit() honors thresholds cheaply
}
```
*Why:* now in JMC you can correlate your `OrderProcessed` spikes *on the same timeline* as GC pauses, safepoints, and allocation — turning "we had a latency spike at 14:03:22" into "the spike coincided with a 38 ms G1 evacuation pause." This correlation is the heart of latency forensics.

### 5.9 Use case: pin a critical thread to a core (reduce scheduling jitter)

```java
// Using OpenHFT Java-Thread-Affinity to bind a hot thread to a dedicated CPU.
import net.openhft.affinity.AffinityLock;

Thread matcher = new Thread(() -> {
    try (AffinityLock lock = AffinityLock.acquireLock()) { // grabs an isolated core
        // This thread now runs on a fixed core the OS won't schedule others onto
        // (best combined with kernel `isolcpus=`), avoiding migration & cache-cold restarts.
        runMatchingEngineLoop();
    }
});
matcher.start();
```
Combine with kernel-level isolation:
```bash
# Reserve cores 2-3 from the scheduler; pin the JVM to them.
# (kernel boot param) isolcpus=2,3 nohz_full=2,3 rcu_nocbs=2,3
taskset -c 2,3 java -XX:+UseZGC -jar matcher.jar
```
*Why:* on a busy box, thread migration to a cold core (cold L1/L2 cache, possibly far NUMA node) adds microseconds-to-milliseconds of jitter exactly when you don't want it. Pinning + core isolation removes a major tail-latency source for the hottest threads.

### 5.10 Use case: object reuse on the hot path (done *carefully*)

```java
// A bounded, per-thread reusable buffer to avoid allocating on every request.
// CAUTION: object pooling is an anti-pattern for most objects (the GC is faster than you).
// It only pays for: large arrays/buffers, off-heap-backed objects, or objects whose
// construction is genuinely expensive. Misused, it causes leaks and promotes garbage to old gen.
private static final ThreadLocal<byte[]> SCRATCH =
    ThreadLocal.withInitial(() -> new byte[64 * 1024]); // reused per thread, never escapes

void handle(Request r) {
    byte[] buf = SCRATCH.get();     // no allocation on the hot path
    int n = r.readInto(buf);        // fill the reusable buffer
    process(buf, n);
    // Do NOT retain a reference to buf beyond this method, or another request will corrupt it.
}
```
> **When pooling is wrong (important).** For small, short-lived objects the modern JVM's TLAB + young GC is *faster* than any pool, and pooling them just moves them into old gen (longer lifetimes), increasing expensive old-gen GC and creating subtle bugs. Pool only big/expensive things, and prefer `ThreadLocal` scratch or off-heap over a shared concurrent pool when you can.

### 5.11 Use case: off-heap storage to keep huge data out of the GC's view

```java
import java.nio.ByteBuffer;

// Allocate 256 MB OUTSIDE the Java heap. The GC never scans/moves this memory,
// so a huge cache here adds ZERO GC pause time. You manage its lifecycle manually.
ByteBuffer offHeap = ByteBuffer.allocateDirect(256 * 1024 * 1024);

// Write/read with explicit positions, no per-element Java objects:
offHeap.putLong(0, 123456789L);
long v = offHeap.getLong(0);

// For freeing promptly (direct buffers are freed by a Cleaner at GC time, which is unreliable),
// libraries use Unsafe/FFM API. JDK 21+ Foreign Function & Memory API is the supported route:
// try (Arena arena = Arena.ofConfined()) {
//     MemorySegment seg = arena.allocate(256L * 1024 * 1024);
//     seg.set(ValueLayout.JAVA_LONG, 0, 123456789L);
// }   // deterministic free at arena close
```
> **Off-heap (define) & FFM API.** *Off-heap* memory is allocated via `ByteBuffer.allocateDirect`, `Unsafe`, or the new **Foreign Function & Memory (FFM) API** (`java.lang.foreign`, stable in JDK 22). It lives outside the GC-managed heap, so it never contributes to GC scan/pause time — ideal for large caches and message buffers in low-latency systems. The price: manual lifecycle management and no automatic safety. The FFM `Arena` gives deterministic, scoped freeing — the modern, safe replacement for `Unsafe`.

---

## 6. Implementation concerns & best practices

### 6.1 Performance (of your code, and of profiling itself)

- **Profile in (or as close as possible to) production.** Hot paths behave differently under real traffic, real heap sizes, real JIT state. A dev-laptop profile of a cold JVM is often useless.
- **Let the JIT warm up before measuring.** A freshly started JVM runs interpreted, then C1, then C2-compiled. Latency in the first seconds-to-minutes is wildly higher. Measure steady state (or explicitly study warmup if that's your concern). See §7 on warmup.
- **Keep profiling overhead known and bounded.** async-profiler CPU ~10 ms interval: typically <1%. JFR `default`: ~1%. JFR `profile`: a few %. Verify on your workload; don't assume.
- **Don't optimize what the profile doesn't flag.** The whole point is to spend effort where measurement says it pays.

### 6.2 Correctness & concurrency

- **Allocation-elimination can mask real allocation in source** (escape analysis). Trust the *profile's* allocation, not your reading of the source.
- **Object reuse introduces aliasing bugs.** A pooled/reused buffer used by two requests concurrently, or retained past its lifetime, is a classic data-corruption source. Use `ThreadLocal` or strict ownership.
- **`final` and immutability help the JIT** (constant folding, fewer barriers) and avoid concurrency bugs — usually the right default; only break immutability for measured hot paths.
- **Beware false sharing.** Two unrelated fields on the same 64-byte cache line, written by different cores, cause cache-line ping-pong. `LongAdder`, `@Contended` (`-XX:-RestrictContended` to use it freely), and padding fix it. `perf c2c` detects it.

> **False sharing & `@Contended` (define).** CPUs cache memory in 64-byte *cache lines*. If two threads write two different fields that happen to share a line, the cache-coherency protocol bounces the line between cores ("ping-pong"), silently killing performance. `jdk.internal.vm.annotation.Contended` pads a field to its own line. Diagnosable with `perf c2c`.

### 6.3 Memory

- **Right-size the heap.** Too small → frequent GC; too large → longer pauses (for non-ZGC collectors) and worse cache locality. Watch *allocation rate* and *promotion rate* in GC logs, not just heap size.
- **Avoid surprise promotion.** Objects that survive a few young GCs get tenured; if your "short-lived" objects live just slightly too long (e.g., held by a pool or a slow async pipeline), they get promoted, polluting old gen and triggering expensive old-gen GC. Track promotion rate.
- **Metaspace & code cache are heaps too.** `Metaspace` (class metadata) and the JIT *code cache* can fill and cause issues; monitor with `jstat -gcmetacapacity` and `-XX:+PrintCodeCache`. A full code cache disables JIT compilation → silent severe slowdown.

### 6.4 Security

- **Profiling agents are powerful.** `-agentpath`/JVMTI agents run native code in your process; only load trusted profiler binaries. async-profiler needs `perf_event_paranoid` relaxed for hardware sampling — understand the implications on shared hosts.
- **JFR recordings can contain sensitive data** (method args in some events, environment, system properties, thread names, file paths). Treat `.jfr`/heap dumps as sensitive artifacts; scrub before sharing. Heap dumps contain *all live data* — passwords, PII.
- **`jcmd`/JMX endpoints are remote-control surfaces.** Lock down JMX (auth/TLS) — an open JMX port is remote code execution.

### 6.5 Observability

- **Continuous JFR in production** ("always-on flight recorder," low overhead) so the black box is full when incidents hit. Many shops run it 24/7.
- **Export GC and safepoint metrics** to your TSDB (pause durations, allocation rate, promotion rate, TTSP, GC CPU%). Alert on p99 pause and on TTSP.
- **Correlate app latency with JVM events** via custom JFR events (§5.8) or by aligning timestamps between your tracing system and GC/safepoint logs.
- **RED/USE method:** instrument Rate, Errors, Duration (per endpoint) *and* Utilization, Saturation, Errors (per resource). Tail latency lives in the Duration percentiles.

### 6.6 Cost

- **Lower allocation = lower GC CPU = lower cloud bill.** GC can be 5–30% of CPU in allocation-heavy services; cutting allocation directly cuts cost.
- **Bigger heaps cost RAM**; ZGC trades some throughput (CPU) for flat latency — that's a real cost line. Model it.
- **Profiling itself is cheap** (sampling); don't skip it to "save resources" — it pays for itself many times over.

### 6.7 Testing

- **Use JMH for microbenchmarks**, never hand-rolled timers. Watch for dead-code elimination, constant folding, and not-warmed-up code.
- **Load test with open-model / constant-throughput generators** (wrk2, Gatling open workload, k6) to avoid coordinated omission. Closed-loop generators that wait for each response hide the tail.
- **Bake p99/p999 SLOs into CI/CD perf gates** with HdrHistogram so regressions are caught before prod.

### 6.8 Production hardening & anti-patterns

**Anti-patterns to avoid:**
- Measuring **averages** instead of percentiles. The mean is meaningless for tail-sensitive systems.
- **Coordinated omission** in load tests (closed-loop generators) → fake-good p999.
- **Pooling small objects** "to reduce GC" → usually *worse* (promotes garbage to old gen, adds bugs).
- **Hand-rolled microbenchmarks** that the JIT optimizes away.
- Trusting **safepoint-biased profilers** (old JVMTI samplers, naive `jstack` loops) for hotspot attribution.
- **Profiling a cold JVM** and shipping conclusions from it.
- **Optimizing CPU when the problem is allocation/locks/off-CPU** — always check which clock dominates first.
- **Ignoring TTSP** — chasing GC pause time while the real cost is safepoint sync.
- Premature off-heap/`Unsafe` — adds danger and complexity; only when measured allocation pressure demands it.

---

## 7. Advanced topics & deep internals

### 7.1 Warmup, tiered compilation, deoptimization

> **Tiered compilation (define).** HotSpot runs code first in the *interpreter*, then compiles hot methods with **C1** (the *client* compiler — fast to compile, lightly optimized, with profiling counters), and finally recompiles the hottest with **C2** (the *server* compiler — slow to compile, aggressively optimized). The progression is *tiers 0–4*. This is why a JVM is slow then fast: it's literally rewriting your code into better machine code as it learns which paths are hot.

> **On-Stack Replacement (OSR).** If a method is already running a long loop when it gets hot, the JVM can swap the *currently executing* frame from interpreted to compiled mid-loop — *on-stack replacement*. Relevant to benchmarks: OSR-compiled code is sometimes less optimized than normally-compiled code, another reason JMH `@Fork`s and warms up properly.

> **Deoptimization (define).** C2 makes *speculative* optimizations based on observed behavior (e.g., "this call site is monomorphic — only ever sees `ArrayList`," or "this branch is never taken"). If reality later violates the assumption (a `LinkedList` shows up, the branch is taken), the JVM **deoptimizes**: discards the compiled code, falls back to the interpreter, re-profiles, and recompiles. Deopts are *stop-the-thread* events and a real source of latency spikes, especially after a phase change in traffic. Diagnose with `-XX:+PrintCompilation`, `-XX:+TraceDeoptimization`, or JFR `jdk.Deoptimization` events.

**Warmup strategies for low latency:**
- **Synthetic warmup**: at startup, run representative traffic against the service (replayed requests) before taking real traffic, so C2 compiles the hot paths. Many trading systems do exactly this.
- **AOT/JIT caching:** *Class Data Sharing (CDS)* and *AppCDS* (`-Xshare`, `-XX:SharedArchiveFile`) pre-load and pre-link classes to cut startup/warmup. **Project Leyden** and **GraalVM Native Image** push further (AOT compilation), trading peak throughput/flexibility for fast, predictable startup — relevant when warmup jitter is unacceptable.
- **`-XX:+TieredCompilation` tuning, `-XX:TieredStopAtLevel`, `-XX:CompileThreshold`** — rarely worth touching, but know they exist.

### 7.2 Choosing and tuning ZGC for low latency

When tails must be sub-millisecond and the heap is large:

- **Enable:** `-XX:+UseZGC` (JDK 21+: generational ZGC is the default flavor; on JDK 15–20 add `-XX:+ZGenerational` where available, else non-generational). 
- **ZGC pause times are essentially independent of heap and live-set size** — all the heavy work (marking, relocation) is concurrent, gated by colored pointers + load barriers (§3.6). Pauses are a few short STW phases (mark start, mark end, relocate start) typically **< 1 ms**, often tens of microseconds.
- **The risk with concurrent collectors: *allocation stall*.** If the app allocates faster than ZGC can concurrently reclaim, the heap fills and threads *stall* waiting for memory — a latency spike worse than a normal pause. Mitigate by giving ZGC enough heap *headroom* and CPU for its GC threads, and by reducing allocation rate. Watch `Allocation Stall` events in the GC log / JFR.
- **Key knobs:** `-Xmx` (size generously for headroom), `-XX:ConcGCThreads` (concurrent GC threads — more if allocation-heavy), `-XX:SoftMaxHeapSize` (a soft cap to encourage earlier GC and keep footprint down), and (older non-generational) `-XX:ZAllocationSpikeTolerance`. ZGC supports **NUMA-awareness** and large pages (`-XX:+UseLargePages`/transparent huge pages) which reduce TLB-miss jitter.
- **Throughput cost:** load barriers add a few percent CPU vs G1. Generational ZGC dramatically narrowed this gap by not relocating/marking the whole heap every cycle. For latency-critical services this is a good trade.

> **Shenandoah** is the analogous choice in Red Hat builds; similar pause profile via concurrent evacuation. Choose based on your JDK distribution and what your team can support.

### 7.3 Time-To-SafePoint pathologies and fixes

- **Counted-loop bias / missing back-edge polls:** the JIT may omit safepoint polls from short *counted* loops (`for (int i=0;i<n;i++)`) it proves are bounded, and *loop-strip-mining* (`-XX:+UseCountedLoopSafepoints`, `-XX:LoopStripMiningIter`) controls how often it inserts them in long counted loops. A pathological case: a huge `for` over a giant array with a trivial body can run for many ms with *no* safepoint poll, ballooning TTSP for everyone. Diagnose with `-Xlog:safepoint` showing high "spinning"/"sync" times; the fix can be restructuring the loop or adjusting strip-mining.
- **JNI / native calls** that run long: a thread in native is "safepoint-safe," but if it's holding things up or returns into a long native-to-Java transition, it adds to TTSP.
- **Page faults during safepoint** (e.g., touching swapped-out memory while parking) extend TTSP.
- **Diagnostics:** `-Xlog:safepoint`, `-XX:+SafepointTimeout -XX:SafepointTimeoutDelay=<ms>` (logs a stack of the thread that didn't reach safepoint in time) — invaluable for catching the offending thread.

### 7.4 Allocation profiling subtleties

- **TLAB-sampling skews toward larger objects** (a 1 MB array always triggers an outside-TLAB event; a 16-byte object only gets sampled when it happens to cross a TLAB boundary). For accurate small-object accounting, use JVMTI `SampledObjectAlloc` with a tuned interval, or async-profiler's allocation mode which weights samples by size.
- **`-XX:+UseTLAB` is on by default; `-XX:TLABSize`, `-XX:+ResizeTLAB`, `-XX:MinTLABSize`** control TLAB behavior. Rarely tuned, but a workload with many threads and small heaps can waste Eden on TLAB fragmentation.
- **Humongous allocations in G1** (objects ≥ half a region) bypass normal allocation and are placed in contiguous "humongous regions"; lots of them cause fragmentation and premature full GCs. Allocation profiles + `-Xlog:gc+heap` reveal this. Fix: increase `-XX:G1HeapRegionSize` or avoid giant arrays.

### 7.5 Lesser-known JFR/async-profiler power features

- **JFR event streaming (JDK 14+, `jdk.jfr.consumer.RecordingStream`)** lets you consume events *live* in-process — build custom dashboards/alerts without dumping files.
- **async-profiler `--ttsp`** isolates time-to-safepoint regions; **`-e itimer`** for environments without perf; **`--cstack` modes** (fp/dwarf/lbr) control native unwinding; **`--all-user`/`--all-kernel`** filter sample privilege level; **`-g`** include native frames.
- **`-XX:+PreserveFramePointer`** keeps `rbp` as a frame pointer so both `perf` and async-profiler can unwind mixed Java/native stacks reliably (otherwise C2 reuses `rbp` as a general register and stacks break above JNI). ~1–2% overhead; standard in profiling-friendly prod fleets.
- **Differential flame graphs** (async-profiler can diff two `.collapsed` files) — before/after a change, instantly see what got hotter (red) or cooler (blue).
- **`perf c2c`** for cache-line contention / false sharing; **`perf sched latency`** for scheduler-induced jitter; **`bpftrace` off-CPU** for "where do threads block in the kernel."

### 7.6 GC ergonomics & the "soft" pause target

- G1's `-XX:MaxGCPauseMillis` (default **200 ms**) is a *soft goal*, not a guarantee — G1 sizes the young gen to try to meet it. Setting it too low forces tiny young gens → more frequent GC → more CPU and worse throughput, and still no guarantee. Tune by *measuring the resulting pause distribution*, not by wishing.
- **`-XX:+UseStringDeduplication`** (G1) can cut heap from duplicate `String`s in dedup-heavy workloads, at a small concurrent-thread cost.
- **`-XX:+AlwaysPreTouch`** touches all heap pages at startup so the OS commits them up front — avoids first-touch page-fault jitter mid-flight (longer startup, flatter runtime tails). Common in low-latency deployments, often with large pages.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Profiler selection

| Tool | Strengths | Weaknesses | Use when |
|---|---|---|---|
| **async-profiler** | No safepoint bias; CPU/alloc/lock/wall; HW counters; tiny overhead; attach live; flame graphs | Linux-first (macOS limited); native agent; needs perf perms for HW events | Default for CPU/alloc/wall/lock flame graphs in prod |
| **JFR + JMC** | Built into JVM; ultra-low overhead; rich GC/safepoint/JIT/IO events; continuous black-box; automated analysis | CPU sampling historically less precise than async-profiler; GUI separate | Always-on prod recording; GC/safepoint/JIT forensics; correlation timeline |
| **JMH** | Correct microbenchmarks; percentile modes | Microbench only, not whole-app | Comparing two implementations/algorithms |
| **Old JVMTI samplers (hprof, some commercial)** | Familiar, GUI | **Safepoint-biased** → can mislead on hotspots | Avoid for hotspot attribution; ok for coarse memory views |
| **Commercial APMs** (Datadog, New Relic, Dynatrace, YourKit, JProfiler) | Turnkey, distributed tracing, history; some use async-profiler/JFR under the hood | Cost; varying overhead; some use instrumentation | Org-wide observability; correlating across services |
| **`perf` (Linux)** | True PMU; kernel + user; off-CPU; c2c | Java symbol resolution needs `perf-map-agent`/`-XX:+PreserveFramePointer` | Native/kernel-level issues, cache/scheduler analysis |

### 8.2 GC selection for latency

| Collector | Pause profile | Heap scaling | Throughput | Use when | Avoid when |
|---|---|---|---|---|---|
| **Parallel** | Long STW (100s ms+) | pauses grow with heap | Highest | Batch/throughput jobs, no latency SLO | Any latency-sensitive service |
| **G1** (default) | 10s–100s ms, soft target | moderate growth | High | General services, default choice | Strict sub-ms tails |
| **ZGC (generational)** | **< 1 ms**, ~heap-independent | flat | slightly lower (a few %) | Low-latency, large heaps, strict tails | Tiny heaps where G1 is simpler; CPU-starved boxes |
| **Shenandoah** | low ms, ~heap-independent | flat | slightly lower | Same as ZGC, on RH/OpenJDK builds | Where ZGC is preferred/supported |
| **Serial** | STW, single-thread | grows | low | Tiny heaps, single-core, containers | Multi-core latency services |
| **Epsilon** (no-op GC) | none (no collection) | n/a | n/a | Short-lived jobs, GC-free benchmarking | Long-running services (OOM) |

### 8.3 Latency technique decision rules

- **Reduce allocation first** — it's the cheapest, safest lever and shrinks both the allocation clock and the pause clock. *Use when:* allocation profile shows high MB/s on the hot path. *Always start here.*
- **Object reuse / pooling** — *use when:* objects are large/expensive (buffers, off-heap-backed). *Avoid when:* small short-lived objects (the GC beats you and you risk bugs).
- **Off-heap (FFM/direct buffers)** — *use when:* large caches/buffers dominate heap and cause GC pressure. *Avoid when:* data is small or you can't accept manual lifecycle/danger.
- **ZGC** — *use when:* you need flat sub-ms tails on a big heap and can spend a few % CPU. *Avoid when:* throughput is king and pauses are acceptable.
- **Pinning + core isolation** — *use when:* a few hot threads dominate and OS jitter is in your tail budget (microsecond-scale systems). *Avoid when:* general web services where GC dominates the tail anyway.
- **Warmup/AOT (CDS, replay, Native Image)** — *use when:* cold-start jitter or fast restart matters (failover, autoscaling, serverless). *Avoid as premature complexity* for steady-state long-running services.

### 8.4 Sampling vs instrumentation (recap as a rule)

> Default to **sampling** for production performance work. Use **instrumentation** only for narrow exact-count questions where you accept distortion. Never trust a **safepoint-biased** sampler for hotspot attribution.

---

## 9. Failure modes & debugging

A practical, tool-by-tool playbook. Each entry: *symptom → diagnose → fix*.

### 9.1 Periodic latency spikes correlated with GC

- **Symptom:** p999 spikes every few seconds; CPU graph shows brief 100% blips.
- **Diagnose:** `-Xlog:gc*:file=gc.log:time,uptime` then GCViewer/GCeasy; look at pause distribution and frequency. Confirm with JFR `jdk.GCPhasePause` events on the same timeline as your custom latency events.
- **Fix:** reduce allocation rate (async-profiler `-e alloc` → kill the hotspot), enlarge young gen / heap, or switch to ZGC for flat tails. If old-gen full GCs: find what's getting promoted (leaks, oversized caches).

### 9.2 The "GC pause was 3 ms but the request took 45 ms" mystery (TTSP)

- **Symptom:** latency spike far exceeds reported GC pause.
- **Diagnose:** `-Xlog:safepoint` — look at *sync* (time to reach safepoint) vs *operation* time. Add `-XX:+SafepointTimeout -XX:SafepointTimeoutDelay=2000` to log the thread that didn't park. async-profiler `--ttsp`.
- **Fix:** find the thread stuck in a counted loop / long native call; restructure the loop or tune `-XX:LoopStripMiningIter`; avoid touching swapped memory (use `-XX:+AlwaysPreTouch`, large pages, no swap).

### 9.3 Allocation stall under ZGC/Shenandoah

- **Symptom:** rare but big latency spikes despite a "pauseless" collector.
- **Diagnose:** GC log shows `Allocation Stall` lines; JFR `jdk.ZAllocationStall`/equivalent.
- **Fix:** give more heap headroom and `-XX:ConcGCThreads`; reduce allocation rate; lower `SoftMaxHeapSize` pressure. The collector needs to reclaim faster than you allocate.

### 9.4 CPU pegged, no obvious hot method (safepoint bias trap)

- **Symptom:** profiler points at an innocuous method; fixing it does nothing.
- **Diagnose:** you may be using a safepoint-biased profiler. Re-profile with **async-profiler** (`-e cpu`) and `-XX:+PreserveFramePointer`. Compare flame graphs.
- **Fix:** trust the bias-free profile; the real hotspot is often a tight loop the biased tool couldn't sample.

### 9.5 Latency high but CPU idle (off-CPU)

- **Symptom:** threads aren't busy yet requests are slow.
- **Diagnose:** async-profiler `-e wall -t`; look for `park`/`wait`/`socketRead`. `jstack` a few times to see where threads sit. `bpftrace` off-CPU for kernel waits.
- **Fix:** size pools (DB connections, threads), reduce downstream latency, fix lock contention (`-e lock`).

### 9.6 Lock contention collapse under load

- **Symptom:** throughput stops scaling past N threads; CPU not maxed; latency climbs.
- **Diagnose:** async-profiler `-e lock`; JFR `jdk.JavaMonitorEnter`/`jdk.ThreadPark`; `jstack` shows many `BLOCKED` on the same monitor.
- **Fix:** shrink critical sections, use `LongAdder`/striped locks/`StampedLock`/lock-free structures, partition data, or remove the shared mutable state.

### 9.7 Code cache full → silent slowdown

- **Symptom:** after running a while, everything slows; logs show `CodeCache is full. Compiler has been disabled.`
- **Diagnose:** `-XX:+PrintCodeCache`, `jstat`, JFR code-cache events.
- **Fix:** raise `-XX:ReservedCodeCacheSize`; enable `-XX:+UseCodeCacheFlushing` (default on); investigate excessive method count (huge generated code, many lambdas).

### 9.8 Deopt storms after a traffic phase change

- **Symptom:** latency degrades right after a deploy or a traffic-pattern shift, then recovers.
- **Diagnose:** `-XX:+PrintCompilation`, JFR `jdk.Deoptimization`; look for repeated deopt/recompile of the same methods (megamorphic call sites, type pollution).
- **Fix:** reduce polymorphism on hot call sites, warm up with representative traffic, consider `@CompilerControl` hints.

### 9.9 OutOfMemoryError / leak

- **Symptom:** heap grows until OOM; full GCs reclaim less each time.
- **Diagnose:** `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...`; analyze with Eclipse MAT (dominator tree → biggest retainers); JFR `jdk.OldObjectSample` (sampled live-object retention — built-in leak finder!).
- **Fix:** fix the retainer (unbounded cache, `ThreadLocal` leak in a pool, listener never removed, static collection).

### 9.10 Real-world incident archetypes (illustrative)

- **The autoboxing tax:** a high-QPS service spent ~25% of CPU and most of its allocation on `Integer`/`Long` boxing in a hot map; switching to primitive collections cut allocation rate ~8× and young-GC frequency proportionally, flattening p999. *(Common, repeatedly documented across the industry.)*
- **The logging hot path:** `log.debug("..." + bigObject)` building strings even when debug is disabled (string concat happens before the level check). Fix: guard with `isDebugEnabled()` or use parameterized logging (`log.debug("{}", x)`). Allocation profile lights up `StringBuilder`.
- **The counted-loop TTSP:** a batch reindex looped over millions of array elements with no safepoint poll, freezing all request threads for tens of ms during each young GC's safepoint sync. Fixed by chunking the loop / strip-mining. Visible only in `-Xlog:safepoint`.
- **Coordinated-omission false confidence:** a team shipped on a "p999 = 4 ms" load test from a closed-loop generator; production p999 was 120 ms. Re-testing with wrk2 (open model) reproduced the real tail.

> Where specific public numbers are cited above as "common/illustrative," treat them as representative magnitudes from widely reported industry experience rather than from a single named source; measure your own.

---

## 10. Interview drill

**Q1. Why is async-profiler considered more trustworthy than older JVMTI-based samplers for finding hot methods?**
*Model answer:* Old JVMTI samplers obtain stacks via `GetStackTrace`, which only works at safepoints, so samples cluster at safepoint locations (method returns, loop back-edges) and miss code between them — **safepoint bias**. async-profiler samples from a signal handler using `AsyncGetCallTrace`, capturing the stack *wherever the thread actually is*, so it has no safepoint bias and correctly attributes time inside tight loops.
- *Probe: What's a safepoint?* A point where all thread stacks/registers are in a known state so the runtime can walk them; the JVM coordinates a stop-the-world by making threads poll a flag (via an `mprotect`'d polling page) and park at the next poll.
- *Probe: What can still make async-profiler's stacks wrong?* AGCT can fail mid-stub/mid-GC (yields `[unknown_Java]`), and native frames need `-XX:+PreserveFramePointer` or DWARF unwinding to be reliable.
- *Probe: When would you still use JFR over async-profiler?* For GC/safepoint/JIT/allocation/lock *event* richness, continuous low-overhead black-box recording, and timeline correlation.

**Q2. You have a service with great median latency but a terrible p999. Walk me through your investigation.**
*Model answer:* First confirm the measurement isn't lying (rule out coordinated omission; re-measure with an open-model generator + HdrHistogram). Then determine *which clock* dominates the tail: pull GC logs (`-Xlog:gc*`) and check pause distribution/frequency; check safepoint sync (`-Xlog:safepoint`) for TTSP; run async-profiler `-e wall` to see if the tail is off-CPU (locks/IO) vs on-CPU. Correlate app-level latency spikes (custom JFR events) on the GC/safepoint timeline. Fix the dominant cause: reduce allocation, resize/switch GC (ZGC), fix the TTSP loop, or relieve contention.
- *Probe: Why not just look at averages?* Averages hide the tail entirely; a rare 200 ms pause barely moves the mean.
- *Probe: What's coordinated omission?* A closed-loop generator's slow response delays the next send, under-sampling the slow window and hiding the tail — fixed by open-model load and `recordValueWithExpectedInterval`.
- *Probe (senior-signal): When is the tail NOT the JVM's fault?* Downstream service latency, OS scheduling jitter, network, noisy neighbors, page faults/swap — wall-clock/off-CPU profiling and OS tools distinguish these.

**Q3. (Senior-signal) When would you choose ZGC over G1, and what does it cost you?**
*Model answer:* Choose ZGC when you need sub-millisecond, heap-size-independent pauses on a large heap and your latency SLO can't tolerate G1's tens-to-hundreds-of-ms evacuation pauses. ZGC achieves this via colored pointers + load barriers doing marking/relocation concurrently. The cost: a few percent throughput (the load barrier on every reference load), more CPU/heap headroom for GC threads, and the risk of *allocation stalls* if you allocate faster than it reclaims. Generational ZGC (default JDK 21+) reduced the throughput gap substantially.
- *Probe: What's an allocation stall and how do you prevent it?* Threads block waiting for memory when allocation outpaces concurrent collection; prevent with more heap headroom, more `ConcGCThreads`, and lower allocation rate.
- *Probe: What's a load barrier?* A few JIT-injected instructions on each reference load that check the pointer color and self-heal relocated references concurrently.
- *Probe: Why is ZGC pause time independent of heap size?* Because the STW phases are bounded short operations (mark start/end, relocate start); the heavy per-object work is concurrent.

**Q4. Why is reducing allocation often the single most effective low-latency change?**
*Model answer:* Allocation rate (MB/s) sets young-GC frequency: each GB you don't allocate is a young GC you don't trigger, and young GCs are the dominant tail source in most services. Less allocation also means less promotion → fewer expensive old-gen GCs, and better cache locality. It's also low-risk compared to pooling/off-heap.
- *Probe: How does the JVM make allocation cheap, and why doesn't that solve the problem?* TLAB pointer-bump makes *per-object* allocation cheap, but aggregate rate still fills Eden and drives GC.
- *Probe: How can a `new` in your source allocate nothing?* Escape analysis + scalar replacement eliminate non-escaping objects.
- *Probe: Name three common allocation hotspots.* Autoboxing, string concatenation/formatting on hot paths, and per-call lambda/iterator/varargs allocation.

**Q5. Explain Time-To-SafePoint and how it can cause latency the GC log doesn't show.**
*Model answer:* Before any STW operation, *all* threads must reach a safepoint; the wall time to get them there is TTSP. A thread stuck in a counted loop without back-edge polls or in a long native transition delays everyone, so the real stall is sync time + operation time, but GC logs report only the operation. You expose TTSP with `-Xlog:safepoint` (sync vs operation times) and catch the offending thread with `-XX:+SafepointTimeout`.
- *Probe: Why might a loop lack safepoint polls?* The JIT omits polls from short counted loops it proves terminate; loop-strip-mining controls poll insertion in long ones.
- *Probe: How is the safepoint poll implemented cheaply?* A read of a polling page that the VM `mprotect`s to fault when a safepoint is requested — zero cost when not needed.

**Q6. Sampling vs instrumentation — tradeoffs, and when each is appropriate.**
*Model answer:* Instrumentation gives exact call counts but huge overhead and an observer effect (defeats inlining, distorts hot small methods), so it can describe the instrumented program, not yours. Sampling has tunable low overhead independent of call frequency and doesn't perturb the JIT, at the cost of being statistical. Use sampling for production perf work; instrumentation only for narrow exact-count needs.
- *Probe: How does instrumentation defeat the JIT?* Entry/exit hooks bloat method bodies past inlining thresholds and prevent inlining across the boundary.
- *Probe: What governs sampling overhead?* The sampling frequency, not the program's call rate.

**Q7. How do you measure p99/p999 correctly?**
*Model answer:* Record latencies into an HdrHistogram (constant-time, no allocation on the record path, accurate percentiles across a huge range), use an open-model/constant-throughput load generator to avoid coordinated omission, and apply `recordValueWithExpectedInterval` to correct for any coordination. Report p50/p99/p999/p9999/max, never averages.
- *Probe: Why HdrHistogram over a sorted array?* It handles huge dynamic range with bounded memory and no per-record allocation, so it doesn't perturb the very system you're measuring.
- *Probe: What's the failure mode if you ignore coordinated omission?* Reported p999 can be 10–100× too optimistic.

**Q8. (Senior-signal) Your teammate wants to pool small objects "to reduce GC." Convince them otherwise — or when would they be right?**
*Model answer:* For small short-lived objects, the JVM's TLAB allocation + young GC is usually *faster* than any pool, and pooling extends their lifetime so they get promoted to old gen, increasing expensive old-gen GC and adding aliasing/leak bugs and contention on the pool. Pooling pays only for *large or genuinely expensive* objects (big buffers, off-heap-backed, costly construction), ideally via `ThreadLocal` scratch or scoped off-heap arenas with strict ownership. So: measure allocation first; reduce it; pool only the big stuff.
- *Probe: Why does pooling increase old-gen GC?* Longer lifetimes → survive more young GCs → tenured → feed old-gen collection.
- *Probe: What bug class does pooling introduce?* Aliasing/use-after-return corruption and leaks if objects aren't reset/returned correctly.

**Q9. (Senior-signal) Profiling shows method X at 40% CPU. Do you optimize it? Walk me through the decision.**
*Model answer:* Not automatically. First, is CPU even the dominant clock for my SLO? If my problem is tail latency driven by GC, a CPU hotspot may be irrelevant. Second, is X *intrinsically* expensive or is the profile biased (re-check with async-profiler)? Third, what's the *achievable* improvement and its cost/risk vs other levers (allocation, locks, downstream)? I optimize X only if it's on the critical path for the metric I'm targeting, the profile is trustworthy, and the expected payoff beats alternatives. Optimizing the wrong 40% is wasted effort.
- *Probe: How do you decide CPU vs allocation vs off-CPU is the bottleneck?* Compare CPU, alloc, wall, and lock flame graphs + GC/safepoint logs; whichever dominates the targeted metric wins.
- *Probe: What if X is hot but not on the latency-critical path?* It may matter for cost/throughput but not for the tail SLO — prioritize by the goal.

**Q10. What's JFR and why run it continuously in production?**
*Model answer:* JFR is a built-in, ultra-low-overhead event recorder in HotSpot writing typed events (GC, allocation, locks, safepoints, JIT, I/O, custom) to thread-local buffers then a chunked file/ring. Running it continuously as a bounded ring (`maxage`/`maxsize`) gives a "black box": when a rare incident hits, you `JFR.dump` and already have the data — no reproduction needed. Default settings overhead is ~1%.
- *Probe: How does JFR keep overhead low?* Thread-local buffers (no contention), cheap struct writes, sampling/thresholds, chunked self-describing format.
- *Probe: How do you correlate app latency with JVM events?* Emit custom `jdk.jfr.Event`s so your spikes sit on the same timeline as GC/safepoints in JMC.

**Q11. Explain warmup, tiered compilation, and how they affect latency measurement.**
*Model answer:* HotSpot starts interpreting, then compiles hot methods with C1 (fast, profiled), then C2 (aggressive). During warmup, latency is far higher and variable. So measurements must be taken at steady state (or warmup must be explicitly studied); microbenchmarks must warm up (JMH does this) or they measure interpreted/OSR code. Speculative C2 optimizations can deoptimize on assumption violations, causing post-deploy spikes.
- *Probe: What's OSR and why care in benchmarks?* On-stack replacement swaps a running loop frame from interpreted to compiled; OSR code can be less optimized, skewing naive benchmarks.
- *Probe: How do you reduce warmup jitter in prod?* Synthetic/replay warmup before taking traffic, AppCDS, or AOT (Native Image/Leyden) where appropriate.

**Q12. How would you find a memory leak with built-in JDK tooling?**
*Model answer:* Enable `-XX:+HeapDumpOnOutOfMemoryError`, capture the dump, and analyze the dominator tree in Eclipse MAT to find the largest retainers and the GC roots holding them. For live diagnosis without OOM, use JFR's `jdk.OldObjectSample` event, which samples objects that survive into old gen and shows their allocation stack and retention path — effectively a built-in leak finder. `jmap -histo:live` gives a quick class histogram.
- *Probe: Common leak sources?* Unbounded caches/maps, `ThreadLocal`s in pooled threads, unremoved listeners, growing static collections, classloader leaks.
- *Probe: Why is `live` important in `jmap -histo:live`?* It forces a full GC first so you see only reachable objects, not garbage.

---

## 11. Glossary

- **AOT (Ahead-Of-Time) compilation:** compiling to machine code before run (GraalVM Native Image, Project Leyden), trading peak JIT optimization for fast, predictable startup.
- **Allocation rate:** bytes/sec your program allocates; the primary driver of young-GC frequency.
- **Allocation stall:** under a concurrent collector, a thread blocking because it allocated faster than the GC could reclaim — a latency spike.
- **`AsyncGetCallTrace` (AGCT):** HotSpot's private API to walk a thread's Java stack from an arbitrary (non-safepoint) point, usable from a signal handler; basis of bias-free sampling.
- **async-profiler:** open-source, safepoint-bias-free sampling profiler (CPU/alloc/lock/wall/HW counters), outputs flame graphs.
- **Back-edge:** the jump from the end of a loop body back to its start; a site where the JIT may insert a safepoint poll.
- **Biased locking:** a legacy optimization (now removed) biasing a monitor toward one thread; its revocation required a safepoint.
- **Blackhole (JMH):** a sink that consumes benchmark results so the JIT can't delete them as dead code.
- **C1 / C2:** HotSpot's client (fast, lightly optimized) and server (slow, aggressively optimized) JIT compilers.
- **Cache line:** the unit (typically 64 bytes) the CPU caches; sharing one between threads' fields causes false sharing.
- **CDS / AppCDS:** Class Data Sharing — pre-parsed/linked class metadata mapped at startup to cut warmup.
- **Code cache:** memory holding JIT-compiled machine code; if it fills, JIT compilation stops (silent slowdown).
- **Colored pointers:** ZGC's technique of storing GC metadata in spare bits of object pointers to enable concurrent relocation.
- **Coordinated omission:** measurement bias where slow responses delay subsequent requests, under-sampling and hiding the tail.
- **Deoptimization:** discarding JIT-compiled code and falling back to the interpreter when a speculative assumption is invalidated.
- **Eden:** the part of the young generation where new objects are first allocated.
- **Escape analysis:** JIT analysis proving an object doesn't escape its method, enabling scalar replacement / stack allocation.
- **False sharing:** performance loss when threads write different fields on the same cache line, causing coherence ping-pong.
- **FFM API (Foreign Function & Memory):** `java.lang.foreign` — supported API for off-heap memory and native calls; replaces `Unsafe`.
- **Flame graph:** stack-sample visualization; box width ∝ resource consumed, y = stack depth.
- **G1 (Garbage-First):** the default region-based, mostly-concurrent collector with a soft pause target.
- **Generational hypothesis:** "most objects die young," motivating the young/old generation split.
- **HdrHistogram:** high-dynamic-range histogram for accurate, low-overhead percentile measurement with coordinated-omission correction.
- **Humongous object (G1):** an object ≥ half a G1 region, allocated specially; many cause fragmentation/full GC.
- **Inlining:** replacing a call with the callee's body; key JIT optimization defeated by instrumentation.
- **Instrumentation:** profiling by injecting entry/exit code at every call — exact counts, high overhead, observer effect.
- **JFR (Java Flight Recorder):** built-in low-overhead event recorder in HotSpot.
- **JIT (Just-In-Time) compiler:** compiles bytecode to machine code at runtime based on profiling.
- **JMC (JDK Mission Control):** GUI for analyzing JFR recordings.
- **JMH (Java Microbenchmark Harness):** the correct way to write JVM microbenchmarks.
- **JVMTI:** native tool interface for agents (debuggers/profilers); its stack APIs are safepoint-bound.
- **Load barrier:** instructions injected on each reference load (ZGC) to check/heal relocated pointers concurrently.
- **`LongAdder`:** striped concurrent counter that avoids single-location contention and false sharing.
- **Minor / major / full GC:** young-gen collection / old-gen collection / whole-heap collection respectively.
- **Monitor:** the intrinsic lock behind `synchronized` (one per object).
- **NUMA (Non-Uniform Memory Access):** multi-socket memory where local RAM is faster than remote; affects pinning/jitter.
- **Off-heap memory:** memory outside the GC-managed heap (direct buffers, FFM/`Unsafe`); not scanned by GC.
- **OOP map:** "ordinary object pointer" map — metadata telling GC which stack slots/registers hold references at a safepoint.
- **On-CPU vs off-CPU:** time a thread spends running on a core vs parked/blocked.
- **OSR (On-Stack Replacement):** swapping a running loop frame from interpreted to compiled mid-execution.
- **Percentile (pN):** the value below which N% of samples fall; tail = beyond p99.
- **`perf_events` / PMU:** Linux performance counters / CPU Performance Monitoring Unit; basis of true CPU-cycle sampling.
- **Pinning / CPU affinity:** binding a thread to a fixed core to reduce migration jitter; pairs with kernel `isolcpus`.
- **Polling page:** a memory page the JVM `mprotect`s to implement cheap safepoint polls via page faults.
- **Promotion / tenuring:** moving objects that survive enough young GCs into the old generation.
- **Sampling:** statistical profiling by periodically capturing stacks; low, tunable overhead.
- **Safepoint:** an execution point where all thread state is known, enabling stack walking / GC.
- **Safepoint bias:** sampling distortion from only capturing stacks at safepoints.
- **Scalar replacement:** exploding a non-escaping object into register-resident fields instead of allocating it.
- **Shenandoah:** Red Hat's concurrent, low-pause collector (concurrent evacuation via load-reference barriers).
- **SLO (Service Level Objective):** a target metric, e.g. "p99 < 10 ms."
- **Stop-the-world (STW):** a phase where all application threads are paused (for GC, deopt, etc.).
- **Survivor spaces (S0/S1):** young-gen regions holding objects that survived a minor GC but aren't yet promoted.
- **TLAB (Thread-Local Allocation Buffer):** per-thread Eden chunk enabling lock-free pointer-bump allocation.
- **Tiered compilation:** the interpreter → C1 → C2 progression of HotSpot code optimization.
- **TTSP (Time To SafePoint):** the time for all threads to reach a safepoint before a STW operation.
- **Throughput collector:** a GC optimized for total work over time at the cost of pause length (Parallel).
- **Wall-clock profiling:** sampling all threads regardless of on/off-CPU, to find off-CPU latency.
- **Warmup:** the period during which the JIT compiles hot code, before steady-state performance.
- **ZGC (Z Garbage Collector):** concurrent, region-based, sub-millisecond-pause collector using colored pointers + load barriers; generational since JDK 21.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Four clocks:** CPU (hot methods) · Allocation (drives GC freq) · Lock (off-CPU contention) · Pause (GC + TTSP + deopt + jitter). Tail latency ≈ Allocation + Pause clocks.

**Always tune for percentiles (p99/p999/p9999), never averages.** Measure with **HdrHistogram** + **open-model** load to avoid **coordinated omission** (can lie 10–100×).

**Profiler choice:** async-profiler for CPU/alloc/lock/wall flame graphs (no **safepoint bias**, uses `AsyncGetCallTrace` from a signal handler). JFR+JMC for built-in, ~1% overhead, continuous black-box + GC/safepoint/JIT/IO events + timeline correlation. JMH for microbenchmarks (`Mode.SampleTime` + `Blackhole`).

**Safepoint bias:** old JVMTI samplers only sample at safepoints → miss tight loops → wrong hotspot. Use async-profiler. **TTSP** = time for all threads to park; can dwarf the reported GC pause — see with `-Xlog:safepoint`.

**Allocation is the #1 lever:** rate (MB/s) sets young-GC frequency. Kill autoboxing, string concat/format, per-call lambdas/iterators. **TLAB** = lock-free pointer-bump; **escape analysis/scalar replacement** can make a `new` allocate nothing.

**GC for latency:** Parallel (throughput, long STW) < G1 (default, soft `MaxGCPauseMillis=200`) < **ZGC** (sub-ms, heap-independent, generational default JDK 21+, costs a few % throughput, risk = **allocation stall**). Shenandoah = analogous on RH.

**Low-latency toolkit:** reduce allocation → reuse/pool only *large/expensive* objects → off-heap (FFM `Arena`) for big caches → warmup (replay/AppCDS) → pin hot threads (`taskset`/affinity + `isolcpus`) → `-XX:+AlwaysPreTouch` + large pages → choose ZGC. Enable `-XX:+PreserveFramePointer` for reliable profiling.

**Key flags:** `-Xlog:gc*`, `-Xlog:safepoint`, `-XX:StartFlightRecording=disk=true,maxage=10m,maxsize=250m,settings=profile`, `-XX:+UseZGC`, `-XX:+AlwaysPreTouch`, `-XX:+PreserveFramePointer`, `-XX:+HeapDumpOnOutOfMemoryError`, `-XX:ReservedCodeCacheSize`.

**Methodology:** (1) trust the metric (no coordinated omission); (2) find the dominant clock (GC log + safepoint log + cpu/alloc/wall/lock flame graphs); (3) correlate app spikes with JVM events (custom JFR events on the JMC timeline); (4) fix the dominant cause; (5) re-measure the percentile you targeted.

**Anti-patterns:** averages; coordinated omission; pooling small objects; hand-rolled microbenchmarks; trusting safepoint-biased profilers; profiling cold JVMs; ignoring TTSP; premature off-heap.

### 12.2 Self-test (no answers — active recall)

1. A request takes 60 ms but the GC log shows only a 4 ms pause around that time. List three distinct JVM/OS mechanisms that could account for the other 56 ms, and the exact tool/flag you'd use to confirm each.
2. Explain precisely *why* a safepoint-biased profiler can attribute the cost of method `A` to method `B`, referencing where the JIT does and doesn't insert safepoint polls.
3. Your closed-loop load test reports p999 = 5 ms; production p999 is 90 ms. Name the measurement flaw, explain the mechanism, and describe two ways to fix the test.
4. You see a `new BigDecimal(...)` in a hot method but the allocation profiler shows almost no allocation there. Give two different JVM explanations, and say how you'd verify which is happening.
5. Justify, with the underlying mechanism, why reducing allocation rate usually improves p999 more reliably than optimizing a CPU hotspot — and give one scenario where that's *false*.
6. A teammate switches the service to ZGC and tail latency mostly improves but now shows rare *worse* spikes than before. What's the likely cause, what events/log lines confirm it, and what are the three levers to fix it?
7. Design a 5-step methodology to decide whether a latency problem is CPU-bound, allocation-bound, lock-bound, off-CPU, or GC-pause-bound — naming the specific profiler mode or log for each branch.

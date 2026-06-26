# Virtual Threads & Structured Concurrency

> An exhaustive engineering-handbook chapter for senior Java/JVM backend developers. Built up from first principles to deep internals, production operations, and interview mastery. Targets **Java 21** (LTS) as the baseline, with explicit callouts for Java 19/20 (preview), Java 22, 23, 24, and 25 where behavior changed.

---

## 1. Overview & where it fits

### 1.1 What it is, in one paragraph

A **virtual thread** is a `java.lang.Thread` that is *not* tied one-to-one to an operating-system thread. Instead, many virtual threads are multiplexed onto a small pool of OS threads (called **carrier threads**) by the JVM. When a virtual thread executes blocking I/O (a network read, a `sleep`, a lock wait), the JVM *unmounts* it from its carrier thread and parks it cheaply on the Java heap, freeing the carrier to run another virtual thread. The result: you can have **millions** of virtual threads alive at once, each written in plain, blocking, sequential style, while the runtime quietly does the work an event loop would otherwise force you to do by hand. **Structured concurrency** (`StructuredTaskScope`) is the companion API that gives those threads a disciplined lifetime: when you fan out work into child threads, the scope guarantees they all finish (or are cancelled) before the parent proceeds — turning "a pile of independent threads" into a tree with clear ownership, error propagation, and cancellation.

### 1.2 The problem it solves

For two decades, server-side Java has been built on the **thread-per-request** model: each incoming request gets its own thread, which runs the whole request top to bottom, blocking whenever it waits on a database, a downstream service, or disk. This model is wonderful for developers — code is sequential, stack traces are meaningful, debuggers step through naturally, thread-locals carry context, exceptions propagate the obvious way.

The catch: a **platform thread** (a normal Java thread, backed 1:1 by an OS thread) is expensive. Each one reserves a chunk of memory for its stack (commonly ~1 MB of *virtual* address space reserved, with physical memory committed lazily) and consumes a kernel resource. The OS scheduler also pays a real cost to context-switch between thousands of them. In practice you can run *thousands* of platform threads, but not *millions*. So a single JVM running thread-per-request hits a hard ceiling on concurrent requests long before its CPU or network is saturated — especially for I/O-bound workloads where each thread spends most of its life simply *waiting*.

The industry's workaround was **asynchronous / reactive programming**: don't block a thread while waiting; instead register a callback (or compose `CompletableFuture`s, or use Reactor/RxJava/Vert.x) so a tiny number of threads can juggle huge numbers of in-flight requests. This scales beautifully but at enormous cognitive cost: the code is inverted, stack traces become useless, debugging is painful, thread-locals break, and the "color" of every method (sync vs async) infects its callers. This is often called the **"what color is your function" problem** — async-ness is viral.

Virtual threads dissolve this tradeoff. You keep the *simple* thread-per-request programming model **and** get the *scalability* of async, because blocking a virtual thread no longer blocks an OS thread. The JVM does the unmount/remount that you used to do manually with callbacks.

> **Adjacent term — I/O-bound vs CPU-bound.** A workload is *I/O-bound* when its threads spend most of their time waiting on external resources (network, disk, database) rather than computing. It is *CPU-bound* when threads spend most of their time doing arithmetic/logic on the CPU. Virtual threads help dramatically with I/O-bound concurrency (the waiting is free) and barely at all with CPU-bound work (you can't compute faster by having more threads than cores).

### 1.3 When you reach for it

- **Reach for virtual threads when:** you have a server (or batch job, or crawler) that does **lots of concurrent, blocking I/O** — HTTP handlers calling databases and other services, message consumers, fan-out aggregation, web scraping, anything where threads mostly *wait*.
- **Reach for structured concurrency when:** a single logical task **fans out** into multiple subtasks that must all complete (or be cancelled together) before you continue — e.g. "fetch the user, their orders, and their recommendations in parallel, then combine; if any fails, cancel the rest."
- **Do NOT reach for virtual threads to speed up CPU-bound work.** They give you scalability of *concurrency*, not raw *parallelism* of computation. For number-crunching, use a sized thread pool, the Fork/Join framework, or parallel streams sized to your core count.

### 1.4 The mental model (hold this in your head)

> A virtual thread is **a task plus its stack**, stored as a plain Java object on the heap. A carrier thread is **a real OS worker** that picks up a virtual thread, runs it until it blocks, then sets it aside and grabs another. Blocking = "put my stack on the heap and let someone else run." Resuming = "copy my stack back onto a carrier and continue from where I left off." You write blocking code; the JVM turns it into an efficient continuation-based state machine for free.

### 1.5 Version & vendor notes up front

| Java version | Status of virtual threads | Status of structured concurrency | Status of scoped values |
|---|---|---|---|
| 19 | Preview (JEP 425) | Incubator (JEP 428) | — (extent-local incubator in 20) |
| 20 | Second preview (JEP 436) | Incubator (JEP 437) | Extent-local incubator (JEP 429) |
| **21 (LTS)** | **Final / GA (JEP 444)** | Preview (JEP 453) | Preview (JEP 446) — renamed *ScopedValue* |
| 22 | GA | Preview again (JEP 462) | Preview again (JEP 464) |
| 23 | GA | Preview (JEP 480) — **API redesigned** | Preview (JEP 481) |
| 24 | GA — **synchronized pinning largely removed (JEP 491)** | Preview (JEP 499) | Preview |
| 25 (LTS) | GA | Final/GA expected | Final/GA expected |

The most important practical takeaways: **virtual threads are production-final in Java 21**; **`StructuredTaskScope`'s API changed shape in Java 23/25** (the `StructuredTaskScope.open(...)` factory style replaced the `new StructuredTaskScope<>()` constructor style); and **pinning on `synchronized` was a real limitation in 21 but was fixed in JEP 491 (Java 24)**. This document presents the Java 21 API as the stable baseline and flags the newer API where relevant.

---

## 2. Foundations from first principles

### 2.1 What a thread *is*, built up from zero

A **process** is a running program with its own isolated memory space. Inside a process, a **thread** is an independent path of execution: a program counter (which instruction to run next) plus a **call stack** (the chain of method calls and their local variables). Multiple threads in one process share the heap (objects) but each has its own stack.

> **Adjacent term — call stack.** Every time you call a method, the runtime pushes a *stack frame* holding that method's parameters, local variables, and the return address. When the method returns, the frame is popped. The whole chain of frames is the call stack — it *is* the "where am I and how did I get here" of a thread. A virtual thread's superpower is that this stack can be moved off the OS thread and stored on the heap.

A **platform thread** in Java is a thin wrapper over an **OS thread** (also called a *kernel thread* or *native thread*). The OS owns it: the OS scheduler decides when it runs, the OS allocates its stack, and switching between OS threads requires a **context switch** through the kernel.

> **Adjacent term — context switch.** When the CPU stops running one thread and starts another, it must save the first thread's registers and load the second's. For OS threads this involves the kernel and typically costs on the order of **1–10 microseconds** plus cache-pollution effects. It's cheap individually but adds up when you switch among thousands of threads thousands of times per second.

> **Adjacent term — kernel vs user space.** The CPU runs in two privilege levels: *kernel space* (the OS, full hardware access) and *user space* (your application). A **syscall** (system call) is the controlled doorway from user space into the kernel — e.g. `read()`, `write()`, `futex()`. Crossing that doorway has overhead, which is part of why OS-thread operations are pricier than pure user-space operations.

### 2.2 Why platform threads are expensive — concrete costs

1. **Stack memory.** Each platform thread reserves stack space. On the HotSpot JVM the default is set by `-Xss` (a.k.a. `-XX:ThreadStackSize`), commonly **512 KB–1 MB** of *reserved virtual address space* (physical pages are committed lazily as the stack grows). A million platform threads would need on the order of a terabyte of address space — infeasible.
2. **Kernel bookkeeping.** Each OS thread is a kernel object with scheduling metadata. There are practical OS limits (`ulimit -u`, `/proc/sys/kernel/threads-max` on Linux) often in the tens of thousands by default.
3. **Scheduler overhead.** The OS scheduler is general-purpose and not aware of your application's structure; with tens of thousands of runnable threads, scheduling and cache effects degrade throughput.
4. **Creation cost.** Spawning an OS thread is a syscall and takes microseconds-to-milliseconds; you generally **pool** platform threads to amortize this.

Net effect: platform threads are a **scarce, pooled resource**. The entire architecture of traditional Java servers (Tomcat's thread pool, `ExecutorService`, connection pools sized to thread counts) is built around that scarcity.

### 2.3 Where virtual threads come from — Project Loom

**Project Loom** is the OpenJDK effort (started ~2017, led by Ron Pressler and others) that delivered virtual threads, structured concurrency, and scoped values. Its thesis: *the thread-per-request model is the right programming model; the only problem is that threads are too expensive — so make threads cheap.*

The enabling primitive is the **continuation**.

> **Adjacent term — continuation.** A continuation is a saved snapshot of "the rest of a computation" — essentially the call stack at a point in time — that can be *suspended* and later *resumed*. Loom added a low-level `jdk.internal.vm.Continuation` (not public API) that can capture a virtual thread's Java stack, stash it on the heap, and later splice it back onto a carrier to continue. Virtual threads = continuations + a scheduler. When a virtual thread blocks, the JVM *yields* its continuation; when the I/O completes, the scheduler *runs* the continuation again.

### 2.4 The core vocabulary (define once, use throughout)

- **Virtual thread:** a `Thread` scheduled by the JVM, not the OS. Cheap (a few hundred bytes plus its heap stack), created per-task, never pooled.
- **Platform thread:** a `Thread` backed 1:1 by an OS thread. Expensive, pooled.
- **Carrier thread:** a platform thread that the JVM uses to *run* virtual threads. By default these live in a dedicated `ForkJoinPool`.
- **Mounting:** assigning a virtual thread to a carrier so it can execute.
- **Unmounting:** detaching a virtual thread from its carrier (typically because it blocked), saving its stack to the heap, freeing the carrier.
- **Pinning:** a state in which a virtual thread *cannot* unmount even though it's blocking, so it holds its carrier hostage. The main correctness/scalability hazard.
- **Scheduler:** the JVM component (a `ForkJoinPool` in **FIFO/asyncMode**) that decides which carrier runs which virtual thread.
- **Structured concurrency:** the discipline (and `StructuredTaskScope` API) where child tasks' lifetimes are bounded by a lexical scope.
- **ScopedValue:** an immutable, inheritable, scope-bound replacement for `ThreadLocal`, designed to work well with millions of virtual threads.

### 2.5 Virtual threads vs platform threads — the foundational table

| Property | Platform thread | Virtual thread |
|---|---|---|
| Backed by | One OS thread (1:1) | Multiplexed onto carriers (M:N) |
| Typical memory | ~1 MB stack reserved | ~hundreds of bytes + heap stack that grows/shrinks |
| Max practical count | ~thousands–tens of thousands | **millions** |
| Creation cost | High (syscall) — so they're pooled | Negligible — create one per task, never pool |
| Scheduler | OS kernel scheduler | JVM `ForkJoinPool` (FIFO) |
| Best for | CPU-bound work; long-lived dedicated threads | High-volume, I/O-bound, short-ish tasks |
| Blocking I/O | Blocks the OS thread (wastes it) | Unmounts; carrier runs other work |
| `ThreadLocal` | Fine | Works but discouraged at scale; prefer `ScopedValue` |
| Priorities / thread groups | Honored-ish | No-ops / fixed |
| `Thread.isDaemon()` | Configurable | Always daemon, always `NORM_PRIORITY` |

The single most important behavioral rule, and the slogan to memorize:

> **Don't pool virtual threads. Create a new virtual thread for every task.** Pooling exists to amortize the cost of a scarce resource; virtual threads are not scarce, so pooling them is an anti-pattern that reintroduces the very ceiling you were trying to escape.

---

## 3. How it works internally — the heart of the document

This section traces, step by step, what the JVM actually does. Where exact internals are HotSpot-specific or could change between releases, that is flagged.

### 3.1 The M:N scheduling model

Virtual threads implement **M:N scheduling**: *M* virtual threads run on *N* carrier (OS) threads, where *M* can be millions and *N* is small (by default, the number of CPU cores). Contrast:

- **1:1 (platform threads):** each Java thread = one OS thread. Scheduling is the kernel's job.
- **N:1 (old "green threads," pre-Java 1.2):** many user threads on one OS thread — couldn't use multiple cores, and one blocking call froze everyone. Java abandoned this in the late 1990s.
- **M:N (Loom):** many virtual threads across several carriers — uses all cores *and* makes blocking cheap. Loom succeeds where 1990s green threads failed because the JVM now integrates with the OS's *non-blocking* I/O facilities (epoll/kqueue/IOCP) under the hood, so one virtual thread blocking on a socket doesn't freeze its carrier.

### 3.2 The default scheduler

The default scheduler is a dedicated `java.util.concurrent.ForkJoinPool` running in **FIFO (asyncMode) mode** — meaning tasks are taken first-in-first-out rather than LIFO, which suits independent virtual-thread tasks better than the work-stealing-deque LIFO used for fork/join compute tasks.

- **Parallelism (number of carriers):** defaults to `Runtime.getRuntime().availableProcessors()`. Tunable via the system property **`jdk.virtualThreadScheduler.parallelism`**.
- **Max pool size:** the scheduler may temporarily create extra carriers (up to a cap) to compensate for pinned/blocked carriers — controlled by **`jdk.virtualThreadScheduler.maxPoolSize`** (default 256 in current builds).
- **Minimum runnable / "managed blocker" compensation:** when a carrier is about to block in a way the JVM knows about, the pool can spin up a compensating thread so parallelism is maintained — tuned via **`jdk.virtualThreadScheduler.minRunnable`**.

> **Adjacent term — ForkJoinPool / work-stealing.** `ForkJoinPool` is a thread pool where each worker has its own deque of tasks and idle workers *steal* tasks from busy workers' deques. This keeps cores busy with low contention. Loom reuses this machinery as the virtual-thread scheduler.

You can supply your *own* scheduler in some builds via internal hooks, but as of Java 21 there is **no supported public API** to replace the scheduler; treat the default as fixed and tune it with the system properties above.

### 3.3 Mounting and unmounting — the core lifecycle

Here is the precise sequence when a virtual thread runs and then blocks on, say, a socket read:

1. **Submit.** You create a virtual thread (e.g. `Thread.ofVirtual().start(task)`). The runtime wraps your `Runnable` in a `VirtualThread` object and submits a task to the scheduler `ForkJoinPool`.
2. **Mount.** A carrier (an FJP worker) picks up the virtual thread. The virtual thread's continuation is *mounted*: its stack frames are placed onto the carrier's native stack and execution begins/continues. `Thread.currentThread()` now returns the *virtual* thread, but it is physically running on a carrier.
3. **Run.** The virtual thread executes ordinary bytecode on the carrier, using the carrier's CPU time, exactly like a normal thread would.
4. **Hit a blocking point.** The code calls something blocking that Loom has *instrumented* — e.g. `SocketChannel.read`, `Thread.sleep`, `LockSupport.park`, `ReentrantLock.lock` (contended), `BlockingQueue.take`, etc. Internally these now do non-blocking I/O + park, or register with the JVM's I/O poller.
5. **Unmount (yield).** The JVM *freezes* the continuation: it copies the virtual thread's current Java stack frames from the carrier's stack into a heap-resident `StackChunk` object attached to the `VirtualThread`. The virtual thread is now *parked* — it owns no carrier.
6. **Carrier freed.** The carrier returns to the scheduler and immediately picks up another runnable virtual thread. **No OS thread is blocked** during the wait.
7. **I/O completes.** The underlying non-blocking I/O (managed by the JVM's internal poller thread using `epoll`/`kqueue`/IOCP) signals readiness, or the sleep timer fires, or the lock becomes available. The parked virtual thread is marked *runnable* and resubmitted to the scheduler.
8. **Remount (thaw).** A carrier (possibly a different one — virtual threads are not pinned to a carrier across blocking points) picks it up; the JVM *thaws* the continuation by copying the saved stack frames back onto the carrier's native stack. Execution resumes from exactly the instruction after the blocking call, with all locals intact.
9. **Repeat** until the task returns, at which point the `VirtualThread` terminates and is garbage-collected like any object.

> **Adjacent term — the I/O poller.** The JVM runs internal "poller" threads (dedicated platform threads) that use the OS's scalable readiness-notification facility — `epoll` on Linux, `kqueue` on macOS/BSD, IOCP on Windows — to learn when sockets are readable/writable. When a virtual thread "blocks" on a socket, it really registers interest with the poller and unmounts; the poller wakes it later. This is the same kernel mechanism reactive frameworks use, but hidden from you.

### 3.4 What "freezing" and "thawing" actually move

The continuation machinery does **not** copy the whole stack every time. It uses **lazy/incremental stack copying** with `StackChunk` objects: only the frames that exist between the continuation's entry point and the current top are captured, and there are optimizations to avoid recopying frames that haven't changed. Still, the key cost intuition is: *the deeper the stack at the moment of blocking, the more bytes get moved.* Very deep recursion at a blocking point is more expensive to unmount/mount than a shallow stack.

### 3.5 The state machine of a virtual thread

A `VirtualThread` cycles through internal states (names are implementation detail but conceptually):

```
NEW ──start()──▶ STARTED ──mounted──▶ RUNNING
   RUNNING ──blocking op──▶ PARKING ──stack frozen──▶ PARKED (unmounted)
   PARKED ──I/O ready / unpark──▶ RUNNABLE (resubmitted) ──mounted──▶ RUNNING
   RUNNING ──pinned blocking op──▶ PINNED (carrier held) ──unblock──▶ RUNNING
   RUNNING ──task returns / throws──▶ TERMINATED
```

`Thread.getState()` maps these onto the public `Thread.State` enum (`RUNNABLE`, `WAITING`, `TIMED_WAITING`, `TERMINATED`), so a parked virtual thread shows as `WAITING`/`TIMED_WAITING` just like a blocked platform thread would.

### 3.6 Pinning — the critical hazard, in depth

**Pinning** is when a virtual thread enters a blocking operation but **cannot be unmounted**, so its carrier is *stuck* for the duration of the block. If enough virtual threads pin simultaneously, you exhaust carriers and throughput collapses — the exact failure virtual threads were meant to prevent.

In **Java 21**, the two causes of pinning are:

1. **Synchronized blocks/methods (`synchronized`).** If a virtual thread blocks (does I/O, sleeps, waits) *while holding a monitor it entered via `synchronized`*, it is pinned. The reason is implementation-level: in Java 21 the monitor's ownership is tracked against the carrier's native frame, so the continuation can't be safely unmounted while inside the `synchronized` region. **Fix in code:** replace `synchronized` with `java.util.concurrent.locks.ReentrantLock`, which is Loom-aware and unmounts correctly.
   > **JEP 491 (Java 24) update:** the JVM was reworked so that virtual threads can unmount while holding monitors and inside `Object.wait()`. As of Java 24, `synchronized` no longer causes pinning in the common cases. On Java 21–23, treat `synchronized` around blocking calls as a real bug.
2. **Native frames / foreign functions.** If a virtual thread blocks while there is a **native method (JNI)** frame on its stack, or it's inside a foreign-function (Panama/FFM) downcall, it cannot be unmounted — the JVM cannot freeze native stack frames. This pinning cause **remains** even after JEP 491.

Pinning is *not always harmful*: if the pinned region is brief (no actual blocking happens, or it's microseconds), it's harmless. It only hurts when a pinned virtual thread blocks for a meaningful time *and* you have many of them, starving carriers.

**Detecting pinning:** run with `-Djdk.tracePinnedThreads=full` (or `=short`) on Java 21. When a virtual thread pins, the JVM prints a stack trace showing where. (Note: this flag was **removed/deprecated in Java 24** once JEP 491 made monitor pinning a non-issue; on 24+, use **JDK Flight Recorder** events `jdk.VirtualThreadPinned` instead.)

### 3.7 Carrier-thread compensation

When a virtual thread *does* pin (or otherwise blocks the carrier in a way Loom can detect as a "managed blocker"), the scheduler can spin up a **compensating carrier** so the configured parallelism is maintained — up to `jdk.virtualThreadScheduler.maxPoolSize`. This softens the impact of brief pinning, but it is a safety valve, not a license to pin freely: compensation creates real platform threads (with real memory cost), and the cap means sustained mass-pinning still throttles you.

### 3.8 How blocking calls were retrofitted

Loom required reworking large parts of the JDK so blocking calls would *yield* instead of *block the OS thread*:

- **`java.net` / `java.nio` socket and channel I/O** were rewritten to use non-blocking I/O + the poller under the covers. So `InputStream.read()` on a socket now yields a virtual thread.
- **`Thread.sleep`, `LockSupport.park/parkNanos`** yield.
- **`java.util.concurrent` locks and queues** (`ReentrantLock`, `Semaphore`, `CountDownLatch`, `BlockingQueue`, `CompletableFuture.get`, etc.) yield.
- **`Object.wait()`** yields (and, post-JEP 491, no longer pins).

Things that **don't** yield (and will block/pin the carrier): synchronous **file I/O** on many platforms (file descriptors aren't always pollable, so blocking file reads may run on a special internal pool or block the carrier — historically file I/O uses a workaround and can pin), and any **native blocking** inside JNI.

> **Adjacent term — non-blocking I/O / readiness model.** With blocking I/O, a `read()` call doesn't return until data arrives. With non-blocking I/O, `read()` returns immediately ("nothing yet") and you ask the OS to *notify* you when data is ready (via epoll/kqueue/IOCP). Loom uses non-blocking I/O internally while exposing the simple blocking API to you.

### 3.9 Structured concurrency internals

`StructuredTaskScope` builds on virtual threads to impose a **tree** structure on concurrency. The core invariant: **a scope does not exit until all threads it forked have terminated.** This is enforced by `try`-with-resources + a mandatory `join()`.

Internal flow for `ShutdownOnFailure` (Java 21 preview API):

1. **Open scope:** `new StructuredTaskScope.ShutdownOnFailure()` (or `StructuredTaskScope.open(...)` in 23+). The scope records the *owner* thread.
2. **Fork:** `scope.fork(callable)` creates a **child virtual thread** for each subtask and returns a `Subtask<T>` (in 21 it returned a `Future`-like `Supplier`). Children are linked to the scope.
3. **Run:** children run concurrently on the scheduler.
4. **Short-circuit:** the moment *any* child throws (for `ShutdownOnFailure`) or *any* succeeds (for `ShutdownOnSuccess`), the scope calls `shutdown()`, which **interrupts all still-running children** — cancellation propagates down the tree.
5. **Join:** the owner calls `scope.join()` (or `joinUntil(deadline)`), which blocks the owner virtual thread until all children finish or are cancelled.
6. **Handle outcome:** `scope.throwIfFailed()` rethrows the first failure; or you read results from the `Subtask`s.
7. **Close:** the `try`-with-resources `close()` guarantees that even on early exit or exception, all children are joined/cancelled before control leaves the block. **No child can outlive its scope.**

This gives you **propagation of errors up**, **propagation of cancellation down**, and **no thread leaks** — the three things ad-hoc executor fan-out gets wrong.

> **Adjacent term — cancellation / interruption.** Java cancels a thread cooperatively via `Thread.interrupt()`, which sets an interrupt flag and unblocks blocking calls with `InterruptedException`. Structured concurrency uses interruption to cancel siblings when one fails. Your subtask code should be *interrupt-aware* (don't swallow `InterruptedException`).

---

## 4. The complete toolkit

### 4.1 Creating virtual threads

| API | What it does | Notes |
|---|---|---|
| `Thread.ofVirtual()` | Returns a `Thread.Builder.OfVirtual` to configure & build virtual threads | Preferred builder entry point |
| `Thread.ofVirtual().start(Runnable)` | Creates and starts a virtual thread | Returns the started `Thread` |
| `Thread.ofVirtual().unstarted(Runnable)` | Creates but does not start | Start later with `.start()` |
| `Thread.ofVirtual().name("x-", 0).factory()` | Returns a `ThreadFactory` producing named virtual threads | Names auto-increment from the index |
| `Thread.startVirtualThread(Runnable)` | Convenience: build + start with defaults | Shortest form |
| `Executors.newVirtualThreadPerTaskExecutor()` | `ExecutorService` that starts **a new virtual thread per submitted task** | The idiomatic server executor; not a pool |
| `Thread.ofPlatform()` | Builder for platform threads | The parallel API for the old kind |

`Thread.Builder.OfVirtual` configuration methods: `.name(String)`, `.name(String prefix, long start)`, `.inheritInheritableThreadLocals(boolean)`, `.uncaughtExceptionHandler(...)`. Note there is **no** `.stackSize()` and **no** `.priority()`/`.daemon()` for virtual threads — they're always daemon, `NORM_PRIORITY`, and stack-unbounded-ish.

### 4.2 Inspecting / controlling

| API | Purpose |
|---|---|
| `Thread.isVirtual()` | `true` if the thread is virtual |
| `Thread.currentThread()` | Returns the *virtual* thread when running on one (not the carrier) |
| `Thread.getState()` | Maps internal state to public `Thread.State` |
| `Thread.threadId()` | Stable 64-bit id (use this, not the deprecated `getId()` semantics) |
| `Thread.join()/.join(Duration)` | Wait for completion (works fine; yields) |
| `Thread.interrupt()` | Cancel cooperatively |

### 4.3 Scheduler tuning system properties (HotSpot, Java 21)

| Property | Default | Effect |
|---|---|---|
| `jdk.virtualThreadScheduler.parallelism` | `availableProcessors()` | Number of carriers (FJP parallelism) |
| `jdk.virtualThreadScheduler.maxPoolSize` | 256 | Max carriers incl. compensation |
| `jdk.virtualThreadScheduler.minRunnable` | ~`parallelism/2` | Target minimum runnable to keep cores busy |
| `jdk.tracePinnedThreads` | off | `short`/`full` → log pinning stack traces (Java 21–23; removed in 24) |
| `jdk.unparker.maxPoolSize` | implementation | Sizes the internal unparker pool |

These are **unsupported/experimental tuning knobs** in the sense that names/defaults can change between releases — flag them as version-specific in any runbook.

### 4.4 Structured concurrency API — Java 21 (preview, JEP 453)

| Class / method | Purpose |
|---|---|
| `StructuredTaskScope<T>` | Base scope; `fork`, `join`, `close` |
| `StructuredTaskScope.ShutdownOnFailure` | Cancel all on first failure (AND semantics: need all to succeed) |
| `StructuredTaskScope.ShutdownOnSuccess<T>` | Cancel all on first success (OR/race semantics) |
| `scope.fork(Callable<U>)` | Start a child virtual thread; returns `Subtask<U>` |
| `scope.join()` | Wait for all children to finish/cancel |
| `scope.joinUntil(Instant deadline)` | Like `join` but with a deadline; throws `TimeoutException` |
| `scope.shutdown()` | Cancel remaining children now |
| `ShutdownOnFailure.throwIfFailed()` | Rethrow first child failure |
| `ShutdownOnFailure.throwIfFailed(fn)` | Rethrow mapped to a chosen exception |
| `ShutdownOnSuccess.result()` | The first successful result (or throw) |
| `Subtask.get()` | The result (only valid after a successful `join`) |
| `Subtask.state()` | `SUCCESS` / `FAILED` / `UNAVAILABLE` |
| `Subtask.exception()` | The failure, if `FAILED` |

### 4.5 Structured concurrency API — Java 25 shape (JEP changes)

In Java 23+ the API was redesigned. Key differences to know:

- Construction via static factory: `StructuredTaskScope.open(Joiner.allSuccessfulOrThrow())` instead of `new StructuredTaskScope.ShutdownOnFailure()`.
- Policies are expressed as **`Joiner`** objects: `Joiner.allSuccessfulOrThrow()`, `Joiner.anySuccessfulResultOrThrow()`, `Joiner.awaitAll()`, `Joiner.awaitAllSuccessfulOrThrow()`.
- `join()` now *returns* the joined result directly (e.g. a stream of subtasks), reducing the `throwIfFailed()` dance.

Because Java 21 is the LTS most teams run today, the examples below default to the 21 API and note the 25 form.

### 4.6 ScopedValue API (Java 21 preview, JEP 446)

| API | Purpose |
|---|---|
| `ScopedValue.newInstance()` | Create a scoped-value holder (usually `static final`) |
| `ScopedValue.where(KEY, value).run(Runnable)` | Bind `value` for the duration of `run`, then unbind |
| `ScopedValue.where(KEY, value).call(Callable)` | Same, returning a value / throwing checked exceptions |
| `ScopedValue.where(K1,v1).where(K2,v2)...` | Bind multiple values at once |
| `KEY.get()` | Read the current binding (throws if unbound) |
| `KEY.isBound()` | Test if bound in the current dynamic scope |
| `KEY.orElse(default)` | Read or fall back |
| `StructuredTaskScope` + `ScopedValue` | Child threads **inherit** bindings automatically |

### 4.7 ThreadLocal (for contrast)

| API | Purpose |
|---|---|
| `ThreadLocal.withInitial(supplier)` | Per-thread lazily-initialized value |
| `InheritableThreadLocal` | Value copied to child threads at creation |
| `tl.get()/set()/remove()` | Read/write/clear — `remove()` is mandatory hygiene in pools |

### 4.8 Observability tools

| Tool | Use |
|---|---|
| **JDK Flight Recorder (JFR)** | Events `jdk.VirtualThreadStart`, `jdk.VirtualThreadEnd`, `jdk.VirtualThreadPinned`, `jdk.VirtualThreadSubmitFailed` |
| `jcmd <pid> Thread.dump_to_file -format=json out.json` | **Thread dump that includes virtual threads** and structured-concurrency groupings |
| `jcmd <pid> JFR.start/JFR.dump` | Start/dump flight recordings at runtime |
| `-Djdk.tracePinnedThreads=full` | Print pinning stack traces (21–23) |
| `jstack` | Note: legacy `jstack` does **not** enumerate millions of virtual threads; prefer `jcmd Thread.dump_to_file` |

---

## 5. Code examples by use case

All examples target **Java 21**. Where a feature is preview (structured concurrency, scoped values), compile/run with `--release 21 --enable-preview` (and `--source 21` for `javac`). Virtual threads themselves are **not** preview in 21 and need no flag.

### 5.1 The "hello, a million threads" demo (why they're cheap)

```java
// Run: java Million.java   (no preview flag needed for virtual threads in 21)
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class Million {
    public static void main(String[] args) throws InterruptedException {
        var counter = new AtomicLong();
        // newVirtualThreadPerTaskExecutor creates ONE virtual thread per submitted task.
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1_000_000; i++) {
                exec.submit(() -> {
                    // Each thread just sleeps (pure waiting => carriers stay free).
                    Thread.sleep(Duration.ofSeconds(1));
                    counter.incrementAndGet();
                    return null;
                });
            }
        } // close() waits for ALL tasks to finish (it's an AutoCloseable ExecutorService)
        System.out.println("Completed: " + counter.get()); // ~1,000,000
    }
}
```

Why it works: a million *platform* threads would need ~1 TB of stack address space and would OOM. A million *virtual* threads sleeping are all parked on the heap; only a handful of carriers exist. This program runs on a laptop.

### 5.2 Idiomatic HTTP server handler — thread-per-request, blocking style

```java
// A minimal server using the JDK's built-in HttpServer, one virtual thread per request.
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class VtServer {
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(8080), 0);
        // The key line: handle every request on its own virtual thread.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/aggregate", exchange -> {
            // Plain, blocking, sequential code. Each downstream call unmounts the vthread.
            String a = get("https://example.com/a");   // blocks -> unmounts -> carrier freed
            String b = get("https://example.com/b");   // blocks -> unmounts -> carrier freed
            byte[] body = (a.length() + ":" + b.length()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        System.out.println("Listening on :8080");
    }

    static String get(String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        // HttpClient blocking send: on a virtual thread, this yields rather than holding an OS thread.
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
```

Compared to a reactive equivalent, there are no `flatMap`s, no schedulers to reason about, and stack traces point straight at your code.

### 5.3 Parallel fan-out with structured concurrency (AND semantics)

```java
// Java 21 preview API. Compile/run with --enable-preview --release 21.
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

record UserProfile(User user, Orders orders, Recs recs) {}

UserProfile loadProfile(long userId) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Fork three concurrent subtasks; each runs on its own child virtual thread.
        Subtask<User>   u = scope.fork(() -> userService.fetch(userId));
        Subtask<Orders> o = scope.fork(() -> orderService.fetch(userId));
        Subtask<Recs>   r = scope.fork(() -> recService.fetch(userId));

        scope.join();            // wait for all three (or for the first failure)
        scope.throwIfFailed();   // if any failed, the others were cancelled; rethrow that failure

        // Past this line, all three succeeded — safe to call get().
        return new UserProfile(u.get(), o.get(), r.get());
    } // close() guarantees no child thread leaks beyond here
}
```

If `orderService.fetch` throws, the scope immediately interrupts the user and recs subtasks (cancellation flows down), `join()` returns, and `throwIfFailed()` rethrows the order failure. No orphaned threads, no partial work continuing in the background.

**Java 25 form of the same idea:**

```java
try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.allSuccessfulOrThrow())) {
    var u = scope.fork(() -> userService.fetch(userId));
    var o = scope.fork(() -> orderService.fetch(userId));
    var r = scope.fork(() -> recService.fetch(userId));
    scope.join();  // throws if any subtask failed
    return new UserProfile(u.get(), o.get(), r.get());
}
```

### 5.4 Race / fastest-wins with structured concurrency (OR semantics)

```java
// Query several replicas; take whichever responds first, cancel the rest.
import java.util.concurrent.StructuredTaskScope;

String fetchFromFastestReplica(List<String> replicaUrls) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
        for (String url : replicaUrls) {
            scope.fork(() -> httpGet(url)); // each replica queried concurrently
        }
        scope.join();          // returns as soon as the FIRST subtask succeeds
        return scope.result();  // the winning result; losers were interrupted/cancelled
    }
}
```

This is the structured-concurrency answer to `CompletableFuture.anyOf` — but with guaranteed cancellation of the losers (no wasted downstream load).

### 5.5 Deadline-bounded fan-out

```java
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.StructuredTaskScope;

Result aggregateWithBudget() throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofMillis(800)); // strict latency budget
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var a = scope.fork(() -> serviceA());
        var b = scope.fork(() -> serviceB());
        scope.joinUntil(deadline);   // throws TimeoutException if not done in time -> cancels children
        scope.throwIfFailed();
        return combine(a.get(), b.get());
    }
}
```

This bounds tail latency: if the fan-out can't finish within the budget, everything is cancelled and you fail fast (or fall back), instead of hanging on the slowest downstream.

### 5.6 ScopedValue for request context (the ThreadLocal replacement)

```java
// Java 21 preview. Compile/run with --enable-preview --release 21.
import java.lang.ScopedValue;

public class Context {
    // Immutable, per-dynamic-scope context. Usually static final.
    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
    public static final ScopedValue<Principal> USER = ScopedValue.newInstance();
}

void handle(HttpExchange exchange) throws Exception {
    String rid = exchange.getRequestHeaders().getFirst("X-Request-Id");
    Principal user = authenticate(exchange);

    // Bind values for the dynamic extent of run()/call(); auto-unbound afterward.
    ScopedValue.where(Context.REQUEST_ID, rid)
               .where(Context.USER, user)
               .run(() -> processRequest(exchange));
}

void processRequest(HttpExchange exchange) {
    log.info("handling rid={} user={}", Context.REQUEST_ID.get(), Context.USER.get().name());
    // If processRequest forks child virtual threads via StructuredTaskScope,
    // those children automatically INHERIT these bindings. No copying, no leaks.
}
```

Why `ScopedValue` over `ThreadLocal`: it's **immutable** (no surprise mutation deep in the call tree), its lifetime is **bounded by the scope** (no `remove()` to forget, no leaks), and it's **cheap to inherit** across millions of child virtual threads (the binding is shared, not copied).

### 5.7 Avoiding pinning — replace `synchronized` with `ReentrantLock` (Java 21)

```java
// ANTI-PATTERN on Java 21: blocking I/O inside synchronized => pins the carrier.
class CacheBad {
    private final Map<String,String> map = new HashMap<>();
    synchronized String getOrLoad(String k) throws Exception {
        return map.computeIfAbsent(k, key -> blockingHttpGet(key)); // blocks while pinned!
    }
}

// FIX: use a Loom-aware ReentrantLock so the vthread can unmount while waiting/holding.
import java.util.concurrent.locks.ReentrantLock;
class CacheGood {
    private final Map<String,String> map = new java.util.concurrent.ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    String getOrLoad(String k) throws Exception {
        String v = map.get(k);
        if (v != null) return v;
        lock.lock();                 // ReentrantLock.lock() yields if contended (no pinning)
        try {
            v = map.get(k);
            if (v == null) {
                v = blockingHttpGet(k); // still inside the lock, but NOT inside a monitor -> can unmount
                map.put(k, v);
            }
            return v;
        } finally {
            lock.unlock();
        }
    }
}
```

> On **Java 24+** (JEP 491) the `synchronized` version no longer pins, so this refactor becomes a style choice rather than a correctness fix. But code that must run on 21/LTS should prefer `ReentrantLock` around blocking sections.

### 5.8 Bounding concurrency to a downstream with a Semaphore (rate limiting)

```java
// Virtual threads make it tempting to fire unlimited concurrent calls. Don't overwhelm
// a downstream that has a small connection pool. Bound concurrency with a Semaphore.
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;

class BoundedFanout {
    // Allow at most 50 concurrent calls to the fragile downstream.
    private final Semaphore permits = new Semaphore(50);

    void processAll(List<String> ids) {
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String id : ids) {
                exec.submit(() -> {
                    permits.acquire();      // yields if no permit (no pinning, no OS thread held)
                    try {
                        return callDownstream(id);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
    }
}
```

Key insight: with virtual threads you create *one thread per task* but **rate-limit the resource, not the threads**. Use `Semaphore` (or a connection pool, or a bounded queue) to protect downstreams — never a sized thread pool of virtual threads.

### 5.9 Mixing CPU-bound work correctly

```java
// CPU-bound work should NOT live on the virtual-thread scheduler (it would hog carriers).
// Use a sized platform-thread pool for compute; use vthreads only for the I/O wrapping.
import java.util.concurrent.*;

class Hybrid {
    private final ExecutorService cpuPool =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // Called from a virtual thread handling a request.
    String handle(byte[] input) throws Exception {
        byte[] fetched = blockingFetch();                 // I/O on the virtual thread: fine
        // Offload the heavy compute to the CPU pool; the vthread blocks (yields) on the Future.
        Future<String> f = cpuPool.submit(() -> heavyCompute(fetched));
        return f.get();                                   // vthread unmounts while compute runs
    }
}
```

### 5.10 Migrating a Tomcat/Spring app (config-level)

```properties
# Spring Boot 3.2+ : run web request handling on virtual threads with ONE property.
spring.threads.virtual.enabled=true
```

Spring Boot 3.2+ wires the servlet container (Tomcat/Jetty) and `@Async`/task executors to virtual threads when this is set. Verify your **JDBC driver, connection pool, and any libraries using `synchronized` around I/O** are pinning-safe (or run on Java 24+). Also: a connection pool of size 10 is still your real concurrency limit to the DB — virtual threads don't remove the need to size pools.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Throughput, not latency.** Virtual threads raise the *number of concurrent in-flight tasks* a server can sustain. A single request isn't faster; you can just have far more of them at once. The win shows up as higher throughput / better resource use under high concurrency, especially I/O-bound.
- **Carrier count = cores.** Default parallelism is core count. Raising `jdk.virtualThreadScheduler.parallelism` rarely helps for pure I/O (cores aren't the bottleneck) and can hurt by oversubscribing. Raise it only to compensate for known unavoidable pinning.
- **Stack depth matters.** Deep call stacks cost more to freeze/thaw on each unmount/mount. Avoid gratuitously deep recursion in hot blocking paths.
- **GC pressure.** Each virtual thread's stack lives on the heap as `StackChunk` objects. Millions of them create real heap usage and GC work. Profile heap and GC pauses under realistic load; virtual threads shift cost from OS memory to JVM heap.
- **Don't pool.** Pooling virtual threads serializes tasks behind a fixed pool size and defeats the purpose. Use `newVirtualThreadPerTaskExecutor()`.

### 6.2 Correctness & concurrency

- **Shared mutable state is still dangerous.** Virtual threads run truly in parallel across carriers. All the usual rules apply: synchronize/lock shared mutable data, use concurrent collections, beware races. Nothing about virtual threads makes concurrency safe by itself.
- **Interrupt-awareness.** Structured concurrency cancels via interruption. Make subtasks responsive to interruption; don't catch-and-ignore `InterruptedException`.
- **`synchronized` correctness is fine; its *scalability* was the issue.** A `synchronized` block is still mutually exclusive and correct on virtual threads; pre-24 it just pinned. Prefer `ReentrantLock` around blocking sections for scalability on 21–23.

### 6.3 Memory

- A virtual thread's resting footprint is on the order of **hundreds of bytes to a few KB** (object header + small initial stack chunk) versus ~1 MB reserved per platform thread. But a *busy* virtual thread with a deep live stack can temporarily hold tens of KB of heap stack. Budget heap accordingly when planning for millions.

### 6.4 Security

- **No thread reuse means cleaner context isolation.** Because you don't pool virtual threads, you avoid the classic bug where a `ThreadLocal` left over from a previous request leaks into the next one in a pooled thread. (Still, prefer `ScopedValue` for context.)
- **`SecurityManager` is deprecated for removal**; don't design around per-thread security contexts via the old mechanism.

### 6.5 Observability

- **Thread dumps:** use `jcmd <pid> Thread.dump_to_file -format=json dump.json`. This new dump format groups virtual threads by their `StructuredTaskScope`, so you can see the concurrency tree — invaluable for diagnosing stuck fan-outs. Legacy `jstack` won't scale to millions of vthreads.
- **JFR:** enable `jdk.VirtualThreadPinned` (with a duration threshold) to catch harmful pinning in production; `jdk.VirtualThreadSubmitFailed` flags scheduler rejection. Monitor virtual-thread start/end rates to understand churn.
- **Metrics:** there's no built-in "active virtual thread" gauge; instrument at the application/executor level if you need counts. Carrier-pool metrics aren't exposed as a standard MBean — treat carrier saturation as something you infer from CPU + pinning events.

### 6.6 Cost

- Fewer/smaller machines for the same I/O-bound concurrency (you stop being thread-count-bound), but watch heap/GC. The cost shifts, it doesn't vanish.

### 6.7 Testing

- Tests can spin up huge concurrency cheaply — good for exercising race conditions. Use deterministic synchronization (latches, barriers) rather than sleeps.
- Pin detection belongs in tests: run integration tests with `-Djdk.tracePinnedThreads=full` (21–23) or assert on JFR pinning events, and fail the build on unexpected pinning.

### 6.8 Production hardening checklist

1. Run on the latest patch of your LTS (Java 21.0.x; consider 24/25 for the `synchronized` pinning fix).
2. Audit libraries for `synchronized`-around-I/O and native blocking; replace or upgrade.
3. **Bound every downstream** with a `Semaphore`/connection pool — virtual threads remove the *thread* limit, not the *resource* limit.
4. Use `newVirtualThreadPerTaskExecutor()`; never pool virtual threads.
5. Set timeouts on every blocking call (HTTP, JDBC) — millions of threads waiting forever is a worse outage than running out of OS threads.
6. Wire JFR pinning events into alerting.
7. Use `ScopedValue` (or carefully scoped `ThreadLocal`) for context.
8. Use `StructuredTaskScope` for fan-out so failures cancel siblings and nothing leaks.
9. Load-test for **heap/GC** behavior at target concurrency, not just throughput.

### 6.9 Anti-patterns

- **Pooling virtual threads** (`Executors.newFixedThreadPool` of virtual threads) — reintroduces a ceiling.
- **`synchronized` around blocking I/O** on Java 21–23 — pins carriers.
- **Unbounded fan-out** — millions of threads hammering a downstream with a 10-connection pool just queues at the pool and may trigger cascading failures.
- **Heavy `ThreadLocal` use at scale** — memory and lifecycle hazards; prefer `ScopedValue`.
- **CPU-bound work on the virtual scheduler** — starves carriers; offload to a sized pool.
- **Caching `Thread.currentThread()` identity** as a key for pooled resources — every task is a new thread now.

---

## 7. Advanced topics & deep internals

### 7.1 Continuations under the hood

Virtual threads sit on `jdk.internal.vm.Continuation` (internal, not for app use). A continuation has a *scope* and a *body*; `run()` executes it, `yield(scope)` suspends it. `VirtualThread` calls `Continuation.yield` at blocking points and `run` to resume. The continuation's stack is represented by **`StackChunk`** objects on the heap; HotSpot has dedicated GC handling and intrinsics for freeze/thaw. This is why deep stacks cost more — there are more frames to copy.

### 7.2 Why file I/O is special

Regular files are generally **not pollable** via epoll/kqueue (you can't `epoll` a regular file usefully). Historically the JDK handles blocking file reads by either running them on a dedicated internal pool or by accepting that the carrier blocks. So **synchronous file I/O can effectively block/pin a carrier**. For high-volume file I/O, this matters; on Linux, newer mechanisms like **io_uring** could change this, but as of Java 21 don't assume file I/O unmounts like socket I/O does.

> **Adjacent term — io_uring.** A modern Linux asynchronous I/O interface that supports truly async file and network operations via shared ring buffers, reducing syscalls. The JDK does not (as of 21) use it for virtual-thread file I/O; this is an active area of evolution.

### 7.3 Thread-local inheritance and `inheritInheritableThreadLocals`

By default, virtual threads created by the per-task executor do **not** inherit `InheritableThreadLocal`s the way you might expect from platform threads, and the builder lets you disable inheritable thread-local capture with `.inheritInheritableThreadLocals(false)` to save memory. With millions of threads, copying inheritable thread-locals is exactly the cost `ScopedValue` was designed to avoid.

### 7.4 The unparker and timers

When a virtual thread sleeps or waits with a timeout, the JVM uses internal timer/unparker machinery to re-enqueue it when the deadline elapses. Sized via `jdk.unparker.maxPoolSize`. Massive numbers of timed waits create timer-wheel work; usually negligible, but a factor at extreme scale.

### 7.5 Carrier-local pitfalls: `ThreadLocal` keyed by carrier

Some libraries cache state in a `ThreadLocal` assuming a small fixed set of threads (e.g. a `ThreadLocal<SimpleDateFormat>` to reuse expensive objects). With virtual threads, *every task is a new thread*, so such caches get **no reuse** and may instead allocate per task — a silent performance regression. The fix is library-side: cache on a small pool or use a pooled resource, not a `ThreadLocal`.

### 7.6 `Thread.sleep` semantics and "yield points"

A virtual thread can only unmount at **yield points** — calls the runtime knows about. Pure CPU loops with no blocking calls **never yield**, so a CPU-bound virtual thread monopolizes its carrier exactly like a platform thread would. There is cooperative `Thread.yield()` for virtual threads, but tight compute loops should not be on the virtual scheduler at all.

### 7.7 Structured concurrency: custom policies

You can subclass `StructuredTaskScope` to implement custom join policies (e.g., "succeed when 3 of 5 subtasks succeed," quorum reads). Override `handleComplete(Subtask)` to collect results/decide on `shutdown()`. This is the building block for quorum/hedged-request patterns.

### 7.8 Hedged requests pattern

Combine `ShutdownOnSuccess` with a *staggered* fork (start a backup request after a delay if the primary hasn't returned) to cut tail latency. Structured concurrency makes cancellation of the loser automatic — critical so the backup doesn't double the load permanently.

### 7.9 Interaction with GC and safepoints

Mounting/unmounting interacts with **safepoints** (the points where the JVM can stop all threads for GC). HotSpot was extended so continuations can be frozen/thawed across GC correctly. The practical effect: very large numbers of virtual threads add roots/heap for GC to scan; G1/ZGC handle this, but ZGC's low-pause design is often a good match for high-vthread-count, large-heap servers.

> **Adjacent term — safepoint.** A JVM-wide checkpoint at which all application threads are paused so the runtime can do work (GC, deoptimization, stack walking). Operations that require stopping the world wait for all threads to reach a safepoint.

### 7.10 Limitations & no-ops

- Virtual threads ignore `setPriority`/`setDaemon` (always daemon, NORM priority).
- Thread groups are a single placeholder group.
- You cannot set a custom stack size on a virtual thread.
- There's no supported public API to plug in a custom scheduler in Java 21.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Virtual threads vs reactive/async

| Dimension | Thread-per-request on virtual threads | Reactive (Reactor/RxJava/WebFlux) |
|---|---|---|
| Programming model | Blocking, sequential, simple | Async, declarative, inverted |
| Debuggability | Excellent (real stack traces, step debugging) | Poor (broken stacks, hard to step) |
| Scalability (I/O-bound) | Excellent | Excellent |
| Backpressure | Manual (semaphores, bounded queues) | Built-in (Reactive Streams) |
| Learning curve | Low (it's just threads) | High |
| Ecosystem maturity | New but standard JDK | Mature, rich operators |
| CPU-bound work | Offload to sized pool | Offload to sized scheduler |
| Streaming/composition | Plain code + libraries | First-class operators (`flatMap`, `merge`) |

**Use virtual threads when:** you want simple blocking code, you're on Java 21+, your workload is request/response or fan-out I/O. **Prefer reactive when:** you need fine-grained backpressure across stream pipelines, complex event-stream composition, or you're already deeply invested in a reactive stack.

### 8.2 Virtual vs platform threads

| Use case | Choose |
|---|---|
| High-concurrency I/O-bound server | Virtual |
| CPU-bound compute (encoding, ML, parsing huge data) | Platform (sized pool / fork-join) |
| A handful of long-lived dedicated threads (event loop, dispatcher) | Platform |
| Per-request/per-task work that blocks | Virtual |
| Code calling lots of `synchronized` + I/O on Java 21–23 | Platform (or refactor to `ReentrantLock`) |

### 8.3 Structured concurrency vs raw `ExecutorService`/`CompletableFuture`

| Concern | `StructuredTaskScope` | Raw executor / `CompletableFuture` |
|---|---|---|
| Thread leaks | Impossible (scope joins all) | Easy to leak forgotten futures |
| Error propagation | Automatic, first failure cancels siblings | Manual, easy to get wrong |
| Cancellation | Down the tree, automatic | Manual `cancel(true)`, often forgotten |
| Observability | Tree shown in JSON thread dump | Flat, hard to correlate |
| Composition | Lexical, nests cleanly | Combinator-based, can sprawl |
| Maturity | Preview (21–24), GA ~25 | Stable since Java 5/8 |

### 8.4 ScopedValue vs ThreadLocal vs InheritableThreadLocal

| Property | `ScopedValue` | `ThreadLocal` | `InheritableThreadLocal` |
|---|---|---|---|
| Mutability | Immutable per binding | Mutable | Mutable |
| Lifetime | Bounded by `run`/`call` scope | Until `remove()` or thread death | Same |
| Inheritance to children | Automatic, cheap (shared) | None | Copied at child creation (costly at scale) |
| Leak risk | None (auto-unbind) | High (forgotten `remove()` in pools) | High |
| Fit for millions of vthreads | Excellent | Poor at scale | Poor at scale |
| Maturity | Preview (21–24), GA ~25 | Stable | Stable |

**Rule:** new code carrying request context across a virtual-thread call tree → `ScopedValue`. Legacy/library context that must mutate → `ThreadLocal` with disciplined `remove()`.

---

## 9. Failure modes & debugging

### 9.1 Carrier starvation from pinning

**Symptom:** under load, throughput collapses, latency spikes, CPU is *low* (carriers stuck waiting). **Cause:** many virtual threads pinned (blocking inside `synchronized` on 21–23, or native blocking). **Diagnose:** run with `-Djdk.tracePinnedThreads=full` (21–23) and read the printed stacks; on 24+ enable JFR `jdk.VirtualThreadPinned`. **Fix:** replace `synchronized`-around-I/O with `ReentrantLock`, upgrade offending libraries, or move to Java 24+. **Mitigation:** raise `jdk.virtualThreadScheduler.maxPoolSize` to allow more compensating carriers (band-aid, not cure).

### 9.2 Downstream overload / connection-pool exhaustion

**Symptom:** millions of virtual threads all try to hit a DB with a 20-connection pool; threads pile up waiting for connections; latency explodes; downstream may topple. **Cause:** removing the thread ceiling exposed the *resource* ceiling, and unbounded fan-out amplified it. **Diagnose:** JSON thread dump shows thousands of vthreads parked in the connection pool's `await`. **Fix:** bound concurrency with a `Semaphore` / right-size the pool / add a queue with backpressure; set acquisition timeouts.

### 9.3 Memory / GC blowup

**Symptom:** heap grows, GC pauses rise as concurrency climbs. **Cause:** millions of heap-resident virtual-thread stacks. **Diagnose:** heap histogram (`jcmd <pid> GC.class_histogram`) shows many `StackChunk`/related objects; GC logs show rising live set. **Fix:** cap in-flight concurrency, reduce stack depth, choose a low-pause GC (ZGC), add memory.

### 9.4 Hung fan-out / no timeout

**Symptom:** requests hang forever because one downstream never responds and there's no deadline. **Diagnose:** JSON thread dump shows scopes parked in `join`. **Fix:** use `joinUntil(deadline)` and per-call timeouts everywhere.

### 9.5 Silent loss of `ThreadLocal` caching

**Symptom:** mysterious throughput drop after switching a service to virtual threads. **Cause:** a hot library cached an expensive object in a `ThreadLocal` assuming thread reuse; now every task reallocates. **Diagnose:** allocation profiler (async-profiler) shows heavy allocation of the cached type. **Fix:** library-side pooling, or keep that component on platform threads.

### 9.6 Deadlock with limited carriers (historical)

A subtle early-Loom hazard: if a virtual thread holds a resource and waits (pinned) for another virtual thread that can't get a carrier, you could deadlock if carriers were exhausted. Compensation and the JEP 491 monitor fix reduce this; still, avoid blocking dependencies between virtual threads that all pin.

### 9.7 Tooling pitfalls

- `jstack` and many APM agents predating virtual threads may not enumerate or may choke on millions of vthreads. Prefer `jcmd Thread.dump_to_file -format=json`. Confirm your APM (Datadog, New Relic, Dynatrace) supports virtual threads in your version.

### 9.8 Real-world note

Multiple production migrations (reported by teams like Netflix and others in 2023–2024) found that the headline win was *operational simplicity* and that the recurring gotchas were exactly the ones above: `synchronized` pinning surfacing under load, third-party libraries blocking in native code, and forgetting that downstream pools are still the true concurrency limit. Several teams paused migrations on Java 21 specifically until the `synchronized` pinning fix (JEP 491) landed in 24. Always validate with realistic load and pin tracing before rollout. (These accounts are widely reported; treat exact numbers as team- and version-specific.)

---

## 10. Interview drill

**Q1. What problem do virtual threads solve, and how?**
*Model answer:* They preserve the simple thread-per-request blocking model while removing its scalability ceiling. A platform thread is 1:1 with an OS thread (~1 MB stack, kernel-scheduled), so you can only have thousands. Virtual threads are M:N: many are multiplexed onto a few carrier OS threads. When a virtual thread blocks on I/O, the JVM unmounts it (saves its stack to the heap) and frees the carrier, so blocking is cheap — you can run millions.
- *Probe: Why couldn't 1990s green threads do this?* Because they were N:1 and not integrated with non-blocking OS I/O — one blocking call froze everyone and they couldn't use multiple cores. Loom is M:N and uses epoll/kqueue/IOCP under the hood.
- *Probe: Do virtual threads make a single request faster?* No. They increase concurrency/throughput, not per-request latency.
- *Probe: Where do they NOT help?* CPU-bound work — you can't out-thread your core count.

**Q2. Explain mounting/unmounting.**
*Model answer:* A virtual thread runs by being *mounted* on a carrier (its stack copied onto the carrier's native stack). At a blocking yield point, the JVM *unmounts* it — freezes its continuation by copying frames to a heap `StackChunk` — freeing the carrier to run others. When the I/O completes, it's resubmitted and *remounted* (possibly on a different carrier), resuming exactly where it left off.
- *Probe: What's a carrier thread?* A platform thread in the default `ForkJoinPool` that executes virtual threads.
- *Probe: When can't it unmount?* When pinned: blocking inside `synchronized` (Java 21–23) or with a native/JNI frame on the stack.

**Q3. What is pinning, why does it matter, how do you fix it?**
*Model answer:* Pinning is when a virtual thread blocks but can't unmount, holding its carrier. Mass pinning starves carriers and collapses throughput. On Java 21 the causes are `synchronized`-while-blocking and native frames. Fix `synchronized` by using `ReentrantLock`; native pinning is intrinsic. Detect with `-Djdk.tracePinnedThreads=full` or JFR `jdk.VirtualThreadPinned`.
- *Probe: Did this change?* JEP 491 (Java 24) lets vthreads unmount while holding monitors, so `synchronized` no longer pins in common cases; native pinning remains.
- *Probe: Is all pinning bad?* No — brief pinning with no real blocking is harmless. It hurts when pinned regions actually block, at scale.

**Q4. Why must you not pool virtual threads?**
*Model answer:* Pools exist to amortize the cost of a scarce resource. Virtual threads are nearly free, so a fixed pool just reintroduces a concurrency ceiling and serializes tasks behind it — defeating the purpose. Use `Executors.newVirtualThreadPerTaskExecutor()`, one thread per task.
- *Probe: How do you then protect a downstream?* Bound the *resource*, not the threads — `Semaphore`, connection pool, bounded queue.

**Q5. ScopedValue vs ThreadLocal — when and why?**
*Model answer:* `ThreadLocal` is mutable, lives until `remove()`/thread death (leak-prone in pools), and copies inheritable values to children (costly with millions). `ScopedValue` is immutable, scoped to a `run`/`call` (auto-unbound, no leaks), and inherited cheaply by structured-concurrency children. Prefer `ScopedValue` for request context in virtual-thread code.
- *Probe: Why does immutability matter?* It prevents action-at-a-distance mutation deep in a call tree and makes inheritance safely shareable.
- *Probe: Is ThreadLocal broken on virtual threads?* It works, but it's discouraged at scale and loses its reuse benefit since every task is a new thread.

**Q6. What is structured concurrency and what does it guarantee?**
*Model answer:* It scopes child-task lifetimes to a lexical block via `StructuredTaskScope`. Guarantees: a scope can't exit until all forked children finish or are cancelled (no leaks); an error in one child can cancel siblings (error propagation up, cancellation down); the concurrency tree is observable in thread dumps. You `fork`, `join`, handle the result, and `close` (via try-with-resources).
- *Probe: ShutdownOnFailure vs ShutdownOnSuccess?* Failure = AND (need all; cancel on first failure); Success = OR/race (cancel on first success).
- *Probe: How is cancellation implemented?* Cooperative interruption of child virtual threads.

**Q7 (senior signal). When would you NOT migrate a service to virtual threads?**
*Model answer:* If the workload is CPU-bound (no I/O waiting to reclaim); if the service is built deeply on a reactive stack already delivering backpressure and you'd gain little; if critical libraries block in native code or `synchronized`-around-I/O and you're stuck on Java 21–23; or if your real bottleneck is a downstream resource (DB pool) that virtual threads won't enlarge. Migrating then adds risk (pinning, GC/heap) without throughput upside.
- *Probe: What would you measure before migrating?* Pinning events under load, heap/GC behavior at target concurrency, downstream pool saturation, and end-to-end throughput vs the current model.

**Q8 (senior signal). You migrated to virtual threads and throughput dropped under load. Walk me through diagnosis.**
*Model answer:* Check CPU — if it's low while latency is high, suspect carrier starvation from pinning: enable pin tracing/JFR and look for `synchronized`/native blocking; fix with `ReentrantLock` or upgrade. If threads are parked in a connection pool, it's downstream/resource exhaustion from unbounded fan-out — add a `Semaphore`/right-size the pool/timeouts. If heap/GC is climbing, it's too many heap stacks — cap concurrency, reduce stack depth, switch GC. Use `jcmd Thread.dump_to_file -format=json` to see the tree.
- *Probe: Why might a `ThreadLocal`-based library regress?* It cached expensive objects assuming thread reuse; with one thread per task there's no reuse, so it reallocates per task.

**Q9 (senior signal). Virtual threads vs reactive — how do you choose for a new I/O-heavy service?**
*Model answer:* If the team values debuggability and simplicity and is on Java 21+, virtual threads give reactive-like scalability with sequential code — usually the better default now. Choose reactive when you need first-class backpressure across streaming pipelines, complex event-stream composition, or you're already invested in a reactive ecosystem. The decisive factors are backpressure needs, team expertise, and whether the workload is request/response (favors vthreads) or streaming (can favor reactive).
- *Probe: Can they coexist?* Yes — e.g., a virtual-thread web layer calling reactive clients, or bridging a reactive publisher to blocking calls. But mixing adds complexity; prefer one model per layer.

**Q10. How does the default scheduler work and what can you tune?**
*Model answer:* A dedicated `ForkJoinPool` in FIFO mode with parallelism = core count, work-stealing carriers, and compensation up to a max pool size. Tunables (HotSpot, version-specific): `jdk.virtualThreadScheduler.parallelism`, `.maxPoolSize` (default 256), `.minRunnable`. Raising parallelism rarely helps pure I/O.
- *Probe: When raise parallelism?* To compensate for unavoidable pinning/native blocking.

**Q11. Why are deep stacks a (minor) cost?**
*Model answer:* Unmounting freezes live stack frames into heap `StackChunk`s and mounting thaws them back; deeper stacks mean more bytes copied per blocking event. Usually negligible, but avoid gratuitous deep recursion in hot blocking paths.

**Q12. Why does file I/O behave differently from socket I/O?**
*Model answer:* Sockets are pollable via epoll/kqueue/IOCP, so blocking socket calls unmount the virtual thread. Regular files generally aren't pollable, so synchronous file I/O may run on an internal pool or block/pin the carrier rather than unmounting. Don't assume file reads scale like socket reads on Java 21.

---

## 11. Glossary

- **Asynchronous / reactive programming:** A style where you avoid blocking threads by registering callbacks or composing streams; scales well but inverts control flow and breaks stack traces.
- **Backpressure:** A mechanism for a slow consumer to signal a fast producer to slow down, preventing unbounded buffering. Built into Reactive Streams; manual with virtual threads.
- **Carrier thread:** A platform (OS) thread used by the JVM to run virtual threads. Lives in the default `ForkJoinPool`.
- **Call stack / stack frame:** The chain of in-progress method calls; each frame holds a method's locals/params/return address.
- **Context switch:** The CPU/OS work to swap one thread for another (save/restore registers); ~1–10 μs for OS threads.
- **Continuation:** A capturable, suspendable, resumable snapshot of "the rest of a computation" (the stack). The primitive under virtual threads.
- **CPU-bound:** Work dominated by computation rather than waiting on I/O. Virtual threads don't speed it up.
- **epoll / kqueue / IOCP:** OS facilities for scalable readiness notification on many file descriptors (Linux / BSD-macOS / Windows). Used by the JVM's I/O poller.
- **Fork/Join framework:** A work-stealing thread pool for divide-and-conquer parallelism; reused as the virtual-thread scheduler.
- **Green threads:** 1990s user-space threads (N:1) Java once used; abandoned because they couldn't use multiple cores or non-blocking I/O.
- **I/O-bound:** Work dominated by waiting on external resources. The sweet spot for virtual threads.
- **InheritableThreadLocal:** A `ThreadLocal` whose value is copied to child threads at creation. Costly with many virtual threads.
- **Interruption:** Java's cooperative cancellation signal (`Thread.interrupt()`), used by structured concurrency to cancel siblings.
- **io_uring:** A modern Linux async I/O interface (ring buffers) enabling truly async file I/O; not used by Java 21 vthread file I/O.
- **JEP:** JDK Enhancement Proposal — the design document for a JDK feature (e.g., JEP 444 = virtual threads GA).
- **JFR (JDK Flight Recorder):** Low-overhead JVM event recorder; emits virtual-thread events including pinning.
- **JNI / native frame:** Java Native Interface; a native (C/C++) method frame on the stack. Cannot be frozen, so blocking with one pins the carrier.
- **Kernel / user space:** CPU privilege levels; the OS runs in kernel space, your app in user space; crossing the boundary is a syscall.
- **M:N scheduling:** Many user threads multiplexed onto fewer OS threads. The virtual-thread model.
- **Mounting / unmounting:** Attaching/detaching a virtual thread to/from a carrier (with stack thaw/freeze).
- **Monitor / `synchronized`:** Java's built-in mutual-exclusion lock acquired via `synchronized`. Blocking while holding one pinned the carrier on Java 21–23.
- **Non-blocking I/O:** I/O calls that return immediately and notify you when ready (readiness model), rather than waiting.
- **Pinning:** A virtual thread blocking but unable to unmount, holding its carrier. The main scalability hazard.
- **Platform thread:** A normal Java thread backed 1:1 by an OS thread; expensive, pooled.
- **Project Loom:** The OpenJDK project that delivered virtual threads, structured concurrency, and scoped values.
- **ReentrantLock:** A `java.util.concurrent` lock that is Loom-aware (yields, no pinning); preferred over `synchronized` around blocking on 21–23.
- **Safepoint:** A JVM-wide checkpoint where all threads pause so the runtime can do GC/stack-walking.
- **Scheduler (virtual-thread):** The JVM `ForkJoinPool` (FIFO) deciding which carrier runs which virtual thread.
- **ScopedValue:** Immutable, scope-bound, cheaply inheritable replacement for `ThreadLocal` (preview in 21).
- **Semaphore:** A counting permit primitive used to bound concurrency to a resource.
- **StackChunk:** Heap object holding a virtual thread's frozen stack frames.
- **Structured concurrency:** Discipline (and `StructuredTaskScope` API) bounding child-task lifetimes to a scope, with error/cancellation propagation.
- **`StructuredTaskScope`:** The API for structured concurrency: `fork`, `join`, `close`.
- **Syscall:** A call from user space into the kernel (e.g., `read`, `futex`).
- **Thread-per-request:** The model where each request runs top-to-bottom on its own thread; simple and the model virtual threads make scalable.
- **ThreadLocal:** Per-thread storage; discouraged at virtual-thread scale.
- **Work-stealing:** Idle pool workers steal tasks from busy workers' deques to balance load.
- **Yield point:** A call the runtime recognizes as a place a virtual thread may unmount (blocking I/O, sleep, park, lock wait).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Virtual thread = task + heap stack, scheduled by JVM (M:N), not the OS.** Millions are fine. **Never pool them.**
- **Create:** `Thread.startVirtualThread(r)`, `Thread.ofVirtual().start(r)`, `Executors.newVirtualThreadPerTaskExecutor()`.
- **Carriers:** default `ForkJoinPool`, parallelism = cores. Tune: `jdk.virtualThreadScheduler.parallelism/.maxPoolSize(=256)`.
- **Win = throughput for I/O-bound concurrency.** No help for CPU-bound — offload compute to a sized pool.
- **Pinning** (carrier held, can't unmount): on 21–23 from `synchronized`+blocking and native frames. Fix `synchronized` → `ReentrantLock`. Detect: `-Djdk.tracePinnedThreads=full` / JFR `jdk.VirtualThreadPinned`. **JEP 491 (Java 24) removes monitor pinning.**
- **File I/O may pin** (files not pollable); socket I/O unmounts cleanly.
- **Memory:** virtual threads cost heap (StackChunks) instead of OS stacks — watch GC at scale. Resting footprint ~hundreds of bytes vs ~1 MB platform stack.
- **Structured concurrency** (`StructuredTaskScope`): `fork` → `join` → handle → `close`. No leaks, errors propagate up, cancellation down. `ShutdownOnFailure` (AND) / `ShutdownOnSuccess` (OR). Java 23/25 redesigns to `open(Joiner...)`.
- **ScopedValue > ThreadLocal** for context: immutable, scope-bounded, cheaply inherited by children. No `remove()`, no leaks.
- **Protect downstreams** with `Semaphore`/connection pools + timeouts — virtual threads remove the thread limit, not the resource limit.
- **Observe:** `jcmd <pid> Thread.dump_to_file -format=json` (shows vthreads + scope tree); JFR events.
- **Versions:** GA in Java 21 (JEP 444). Structured concurrency & ScopedValue preview in 21, GA ~25. `synchronized` pinning fixed in 24.
- **Anti-patterns:** pooling vthreads; `synchronized`+I/O on 21–23; unbounded fan-out; heavy `ThreadLocal`; CPU loops on the vthread scheduler.

### 12.2 Decision rules (fast)

- I/O-bound + high concurrency + Java 21+ → **virtual threads**.
- CPU-bound → **sized platform pool / fork-join**.
- Fan-out that must all-succeed-or-cancel → **`StructuredTaskScope.ShutdownOnFailure`**.
- Fastest-wins / hedged → **`ShutdownOnSuccess`** (+ staggered fork).
- Request context across the call tree → **`ScopedValue`**.
- Around a blocking section needing mutual exclusion on 21–23 → **`ReentrantLock`**, not `synchronized`.

### 12.3 Self-test (no answers — for active recall)

1. Trace, step by step, what the JVM does from the moment a virtual thread calls a blocking socket read until it resumes — naming mount, unmount, the poller, and StackChunk.
2. You see low CPU but high latency and stalled throughput after switching to virtual threads. List the three most likely causes and the exact tool/flag you'd use to confirm each.
3. Why is `Executors.newFixedThreadPool(200)` of virtual threads worse than `newVirtualThreadPerTaskExecutor()`? What ceiling does it reintroduce, and how should you instead protect a downstream with a 20-connection pool?
4. Explain why `synchronized` caused pinning on Java 21 but `ReentrantLock` does not, and what JEP 491 changed. When does native-frame pinning still occur?
5. Contrast `ScopedValue` and `InheritableThreadLocal` across mutability, lifetime, inheritance cost, and leak risk — and justify which you'd pick for propagating a request ID across a `StructuredTaskScope` fan-out.
6. Write (from memory) a `StructuredTaskScope` that fetches three services in parallel, cancels the rest if any fails, enforces an 800 ms deadline, and never leaks a thread. Then state the Java 25 `Joiner` equivalent.
7. Your service is CPU-bound (image transcoding). Argue whether virtual threads help, and describe the correct threading architecture.
8. Why might a library that caches an expensive object in a `ThreadLocal` silently regress performance when you move to virtual threads, and how would an allocation profiler reveal it?
```

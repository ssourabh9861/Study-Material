# Concurrency Debugging (Java / JVM)

> An engineering-handbook chapter for senior Java/JVM backend developers. The goal: master concurrency debugging from first principles to deep internals — enough to design with it, operate it in production, debug it under fire, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

**Concurrency debugging** is the discipline of finding, reproducing, diagnosing, and fixing defects that exist *because* multiple threads execute and interact at the same time. These bugs are categorically different from ordinary logic bugs: they depend on **timing, scheduling, and memory-visibility** — variables that change run to run, machine to machine, and load to load. A program can be correct in single-threaded reasoning and still be deeply broken once two threads touch shared state.

**The problem it solves.** Ordinary debugging assumes determinism: same input → same execution → same output. Concurrency destroys that assumption. The CPU scheduler interleaves your threads in a different order each run; the JVM's Just-In-Time (JIT) compiler reorders instructions; CPU cores cache memory independently. The result is the most expensive class of bug in production software: **rare, non-deterministic, and often invisible in the environment where you debug.** Concurrency debugging is the toolbox and the mindset for attacking exactly that.

**When you reach for it.** You reach for these skills when you see symptoms like: a service that "hangs" with no CPU usage (likely deadlock); a counter that's slightly wrong under load (race / atomicity violation); a flag a thread sets that another thread never sees (visibility bug); throughput that collapses under contention (lock contention / convoy); a test that passes 99 times and fails the 100th (heisenbug); or a thread that spins forever making no progress (livelock/starvation). You also reach for it proactively during **code review of concurrent code**, where catching the bug before it ships is 100× cheaper.

**One-paragraph mental model.** Think of shared memory as a whiteboard in a room full of people (threads) who each also keep a private notepad (CPU cache / registers). Each person reads from the whiteboard onto their notepad, scribbles, and occasionally copies back. Without rules about *when* people must sync their notepad to the whiteboard (that's **memory visibility**, governed by the **Java Memory Model**) and rules about *who may write at once* (that's **mutual exclusion**, via locks/atomics), people will overwrite each other (**race conditions / atomicity violations**), act on stale copies (**visibility bugs**), or get stuck waiting for each other in a cycle (**deadlock**). Concurrency debugging is the forensic science of reconstructing what the people actually did, in what order, and proving where the rules were violated.

**Adjacent terms, defined immediately (we'll define more inline as they appear):**

- **Thread.** An independent path of execution within a process, sharing the process's heap memory but with its own stack and program counter. The JVM maps each `java.lang.Thread` to an OS thread (for platform threads), so the OS scheduler decides when each runs.
- **Shared mutable state.** Any data (a field, an array, a collection) that more than one thread can both read and write. This is the root cause of essentially all concurrency bugs. If state is immutable or thread-confined, most of these bugs cannot occur.
- **Scheduler.** The OS component (and, for virtual threads, the JVM) that decides which runnable thread runs on which CPU core and for how long (a "time slice"). It can preempt a thread at almost any instruction boundary.
- **Java Memory Model (JMM).** The formal specification (JSR-133, in the *Java Language Specification* chapter 17) that defines what writes by one thread are guaranteed to be visible to reads by another, and what reorderings the compiler/CPU may legally perform. It is the contract you must satisfy for concurrent code to be correct.

---

## 2. Foundations from first principles

Before debugging concurrency, you must understand precisely *why* concurrency produces bugs. The bugs are not mysterious; they are the lawful consequence of three layers of reality.

### 2.1 The three sources of non-determinism

**(a) Scheduling interleaving.** Suppose two threads each run `count = count + 1`. That single Java line is *not atomic*. It compiles to roughly three steps: read `count` into a register, add 1, write back. With two threads, the scheduler can interleave these six steps in many orders. One bad order:

```
Thread A: read count (0)
Thread B: read count (0)
Thread A: add 1 -> 1
Thread B: add 1 -> 1
Thread A: write 1
Thread B: write 1        // final value 1, not 2 — one increment lost
```

This is a **lost update**, the textbook **race condition**. Nothing is broken in the code's logic; the *interleaving* is the bug.

**(b) Compiler and CPU reordering.** The JIT compiler and the CPU may reorder memory operations as long as they preserve the appearance of correctness *for a single thread* ("as-if-serial" semantics). But another thread can *observe* the reordering. Classic example:

```java
// Thread 1                  // Thread 2
data = 42;                   if (ready) {
ready = true;                   use(data);   // may see ready==true but data==0
                             }
```

Without proper synchronization, Thread 2 may see `ready == true` while still seeing the *old* value of `data`, because the two writes in Thread 1 can be reordered or the reads in Thread 2 can be reordered, or `data`'s new value simply hasn't propagated from Thread 1's cache.

**(c) Memory visibility / caching.** Each CPU core has its own L1/L2 cache. A write by one core may sit in that core's store buffer or cache for an arbitrary time before becoming visible to other cores. So a thread can write a value and another thread can keep reading the *stale* value indefinitely — there is no guarantee of "eventual" visibility without a synchronization action. This is the **visibility bug**, and it is the one that most surprises developers because the code "looks fine."

### 2.2 The Java Memory Model (JMM) — the rulebook

The JMM defines a relation called **happens-before**. If action X *happens-before* action Y, then the effects of X (its memory writes) are guaranteed visible to Y, and X is ordered before Y. If two conflicting accesses (at least one a write) to the same variable are *not* ordered by happens-before, you have a **data race**, and the result is undefined by the spec (you can observe stale, torn, or impossible values).

Key happens-before rules (memorize these — they are the foundation of every fix):

1. **Program order.** Within a single thread, each action happens-before every later action in program order.
2. **Monitor lock.** An unlock of a monitor (exiting a `synchronized` block) happens-before every subsequent lock of that same monitor.
3. **Volatile.** A write to a `volatile` field happens-before every subsequent read of that same field. (This also creates a memory barrier preventing reordering across it.)
4. **Thread start.** A call to `thread.start()` happens-before any action in the started thread.
5. **Thread join.** All actions in a thread happen-before another thread successfully returns from `thread.join()` on it.
6. **Transitivity.** If A happens-before B and B happens-before C, then A happens-before C.
7. **Final fields.** Correctly constructed objects: final fields set in the constructor are visible to other threads without synchronization, *provided the object reference does not escape during construction.*

> **Beginner note — "memory barrier" / "fence".** A memory barrier is a CPU instruction (or JVM-inserted marker) that prevents certain reorderings and forces caches to be made consistent at that point. `volatile` and `synchronized` cause the JVM to emit the appropriate barriers for you. You rarely write barriers by hand in Java (the `VarHandle` fence methods exist, but they're advanced).

> **Beginner note — "torn read/write".** On most JVMs, reads and writes of `long` and `double` (64-bit) are *not* guaranteed atomic for non-volatile fields, because a 32-bit JVM may write them in two 32-bit halves. A concurrent reader can see one new half and one old half — a "torn" value. Marking the field `volatile` makes 64-bit access atomic.

### 2.3 The bug taxonomy

This is the conceptual map. Every concurrency bug you will ever debug is one (or a combination) of these.

| Bug type | One-line definition | Symptom in production | Root cause |
|---|---|---|---|
| **Race condition** | Correctness depends on the timing/interleaving of threads | Sporadic wrong results, corrupted state | Unsynchronized access to shared mutable state |
| **Data race** (subtype) | Two threads access same var, ≥1 write, no happens-before ordering | Stale/torn/impossible values | Missing `volatile`/lock; the JMM-level cause of many races |
| **Atomicity violation** | A logically-indivisible group of operations is interrupted mid-way | Check-then-act bugs, lost updates | Compound action not done under one lock |
| **Visibility bug** | One thread's write is never seen by another | Loop never terminates; flag never observed | Missing `volatile`/synchronization; CPU cache staleness |
| **Deadlock** | Two+ threads each wait forever for a resource the other holds | Service hangs, zero CPU, requests time out | Circular lock acquisition order |
| **Livelock** | Threads keep changing state in response to each other, no progress | High CPU, no throughput, no hang | Overly polite retry/backoff logic that keeps colliding |
| **Starvation** | A thread is perpetually denied a resource it needs | One operation never completes; latency tail | Unfair locks, priority inversion, greedy threads |
| **Lock contention** | Threads spend more time waiting for a lock than working | Throughput collapse under load, high latency | Coarse-grained / hot locks |
| **Thread leak** | Threads created but never reclaimed | OOM (native threads), `unable to create new native thread` | Unbounded thread creation, executors never shut down |
| **Heisenbug** | A bug that disappears when you try to observe it | Fails in prod, passes under debugger/logging | Timing-sensitive; observation changes timing |

> **Beginner note — race condition vs. data race.** They overlap but are not identical. A **data race** is a precise JMM term: conflicting unsynchronized accesses. A **race condition** is a broader correctness term: the program's correctness depends on timing. You can have a race condition *without* a data race (e.g., two threads both using a properly thread-safe `ConcurrentHashMap` but doing `if(!map.containsKey(k)) map.put(k,v)` — each call is safe, but the *check-then-act* sequence races). And you can have a data race that happens not to cause an observable error on a given run. In interviews, distinguishing these is a senior-signal.

> **Beginner note — atomicity vs. visibility.** Two distinct guarantees. **Atomicity** = "this operation completes as an indivisible unit; no thread sees a half-done state." **Visibility** = "after this operation, other threads will see its result." `synchronized` and explicit `Lock`s give you *both*. `volatile` gives you visibility (and ordering) but *not* compound atomicity — `volatileInt++` is still a race because `++` is read-modify-write.

---

## 3. How it works internally

This section is the heart of the chapter. To debug concurrency, you must understand the machinery: thread states, how the JVM records lock ownership, how a thread dump is produced, and how the JMM is enforced at the instruction level.

### 3.1 Thread lifecycle and states (the state machine)

Every Java thread is in exactly one `Thread.State`. Knowing the state of each thread is the first step in almost every diagnosis, because the state tells you *what the thread is waiting for*.

```
            start()
   NEW ───────────────► RUNNABLE ◄──────────────┐
                          │  ▲                   │
        enters synchronized  │  │ lock acquired      │ notify()/notifyAll()
        but lock held by other│ │                    │ or timeout
                          ▼  │                   │
                       BLOCKED                   │
                                                 │
   wait()/join()/park()  ┌──────────────────────┘
        ──────────────►  WAITING
   wait(t)/sleep(t)/      │
   join(t)/park(t)        ▼
        ──────────────► TIMED_WAITING
                                        run() returns / exception
   RUNNABLE ─────────────────────────────────────► TERMINATED
```

| State | Meaning | What it tells the debugger |
|---|---|---|
| `NEW` | Created, not started | Rarely seen in dumps |
| `RUNNABLE` | Executing or ready to execute on a CPU (may actually be blocked in a *native* I/O call) | CPU-bound work, or blocked in native I/O (socket read). **Caveat below.** |
| `BLOCKED` | Waiting to acquire a monitor lock (`synchronized`) held by another thread | Lock contention or deadlock; dump shows which lock and who owns it |
| `WAITING` | Waiting indefinitely via `Object.wait()`, `Thread.join()`, or `LockSupport.park()` | Waiting for a condition/signal; check who should signal it |
| `TIMED_WAITING` | Same as WAITING but with a timeout (`sleep`, `wait(ms)`, `park(ns)`) | Often benign (pool threads parked), or a too-long sleep |
| `TERMINATED` | `run()` finished | Thread is done |

> **Critical caveat — `RUNNABLE` lies.** A thread blocked in a *native* blocking call (e.g., a socket `read()` waiting for bytes) shows as `RUNNABLE` in the thread dump, **not** WAITING, because the JVM doesn't know the native call is blocked. This is the #1 source of confusion when reading dumps: a "RUNNABLE" thread sitting in `socketRead0` is actually idle, waiting on the network. Always read the stack, not just the state.

> **Beginner note — `LockSupport.park()`.** The low-level primitive (`java.util.concurrent.locks.LockSupport`) that the `java.util.concurrent` (j.u.c.) framework uses to block threads. `ReentrantLock`, `CountDownLatch`, `Semaphore`, blocking queues, etc., all park threads internally. A parked thread shows as WAITING/TIMED_WAITING with a stack frame in `LockSupport.park`. The dump line `parking to wait for <0x...> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)` is how you identify *which* j.u.c. construct it's blocked on.

### 3.2 How monitors (`synchronized`) work internally

Every Java object has an associated **monitor** (also called an *intrinsic lock*). Conceptually each object header contains a **mark word** that the JVM uses to track lock state.

**Lock acquisition path (HotSpot, simplified):**

1. **Biased locking** *(removed by default in JDK 15, fully removed in JDK 18; present in JDK 8–14)*. Historically, if only one thread ever locked an object, the JVM "biased" the lock to that thread so re-locking was almost free. It was deprecated because modern atomic ops made it less valuable and it complicated the runtime.
2. **Thin / lightweight locking.** Under low contention, the JVM uses a CAS (compare-and-swap) on the mark word to claim the lock with no OS involvement. **CAS** = an atomic CPU instruction "set this memory to NEW if it currently equals EXPECTED, and tell me if it worked." This is fast.
3. **Inflation to a heavyweight monitor.** Under contention, the lock "inflates": the JVM allocates an `ObjectMonitor` (C++ struct) and threads that can't get the lock are parked via the OS (a `futex` on Linux). **futex** = "fast userspace mutex," a Linux syscall that lets a thread sleep in the kernel until woken, used to implement contended locks efficiently.

**What the monitor tracks (this is what thread dumps surface):**
- The **owner** thread (currently holding the lock).
- The **entry list** (threads BLOCKED trying to enter).
- The **wait set** (threads that called `wait()` and are WAITING for `notify()`).

When you read a thread dump, the lines `- locked <0x...>` (this thread owns it), `- waiting to lock <0x...>` (this thread is BLOCKED on it), and `- waiting on <0x...>` (this thread called `wait()` on it) come directly from this monitor bookkeeping. The hex `<0x...>` is the lock's identity — matching them across threads is how you find the deadlock cycle.

### 3.3 How a thread dump is produced (control flow)

A thread dump is a snapshot of every thread's stack and lock state. Producing it:

1. A trigger arrives: `jstack <pid>`, `kill -3 <pid>` (SIGQUIT), `jcmd <pid> Thread.print`, JMX `ThreadMXBean.dumpAllThreads`, or the JVM itself on certain errors.
2. The JVM brings threads to a **safepoint** — a point where all threads are paused at a known-consistent state so their stacks can be walked safely. **Safepoint** = a globally-agreed pause where the JVM can inspect/modify the heap and stacks. Reaching a safepoint can itself take time if a thread is in a tight counted loop without a safepoint poll (rare, but a known latency source).
3. For each thread, the JVM walks the Java stack frames and reads the monitor bookkeeping (owner/entry/wait) to annotate locks.
4. The JVM's **deadlock detector** runs a cycle-finding pass over monitor ownership *and* `j.u.c` `AbstractQueuedSynchronizer` (AQS) ownership, and if it finds a cycle it appends a `Found one Java-level deadlock:` section.

> **Beginner note — AQS (`AbstractQueuedSynchronizer`).** The internal engine behind `ReentrantLock`, `Semaphore`, `CountDownLatch`, `ReentrantReadWriteLock`, etc. It maintains an integer `state` and a FIFO queue of waiting threads. Modern thread dumps (JDK 8+) *do* detect deadlocks on `ReentrantLock` (ownable synchronizers), not just `synchronized`. Older dumps detected only intrinsic-lock deadlocks — a frequent gotcha.

### 3.4 How the JMM is enforced at the instruction level

When you write `volatile`, the JIT inserts memory barriers around the access. On x86 (a relatively strong memory model), a volatile *write* becomes a normal store followed by a barrier (often realized as a locked instruction / `mfence`-like effect) that flushes the store buffer; a volatile *read* is a normal load but the compiler is forbidden from caching it in a register or reordering across it. On weaker architectures (ARM, POWER) the JIT emits explicit `dmb`/`lwsync` barriers. The practical upshot for debugging: **a missing `volatile` is invisible in the source but produces a missing barrier, which the CPU then exploits to keep stale data in cache or registers.** This is why visibility bugs are invisible to code review unless you're specifically looking for shared mutable fields lacking synchronization.

> **Beginner note — store buffer.** A small per-core hardware queue holding writes that haven't yet reached cache/memory. It makes writes fast but means other cores don't see them immediately. A memory barrier drains/orders it. This hardware detail is *the* physical reason visibility bugs exist.

### 3.5 The lifecycle of a concurrency bug (and your debugging loop)

1. **Latent.** The unsynchronized access exists in code; under light/uniform timing it never manifests.
2. **Triggered.** Load, a new CPU architecture, a JIT recompilation, GC pause, or a scheduling change creates the bad interleaving.
3. **Manifested.** Wrong result / hang / crash, often far from the root cause in space and time.
4. **Observed (or not).** Adding logs/debuggers changes timing and can mask it (heisenbug).
5. **Diagnosed.** You capture state (thread dump, JFR recording, heap), reconstruct the interleaving, and locate the unsynchronized access or lock-order violation.
6. **Fixed & verified.** You add the correct synchronization and prove it with a stress tool (jcstress) or targeted test, *not* by "it didn't repro a few times."

---

## 4. The complete toolkit

This is the arsenal. Group it mentally as: **capture state** (dumps, profilers), **detect bugs statically/dynamically** (jcstress, analyzers, sanitizers), **inspect at runtime** (JMX, jcmd), and **the language primitives** you'll use to fix things.

### 4.1 State-capture and inspection tools

| Tool | What it does | Key invocation / params | Notes / defaults |
|---|---|---|---|
| `jstack <pid>` | Prints a thread dump (all stacks + lock info + deadlock detection) | `jstack -l <pid>` (`-l` = long: shows ownable synchronizers / j.u.c locks). `jstack -F` forces dump on a hung JVM | Ships with the JDK. `-l` is essential for `ReentrantLock` deadlocks |
| `jcmd <pid> Thread.print` | Same dump via the modern, preferred `jcmd` interface | `jcmd <pid> Thread.print -l` | `jcmd` is the recommended successor to many `j*` tools |
| `kill -3 <pid>` / Ctrl-Break (Win) | Sends SIGQUIT; JVM dumps all threads to **stdout** | `kill -3 <pid>` | Goes to the process's stdout/console, not a file — find where stdout is redirected |
| `jcmd <pid> Thread.dump_to_file` | (JDK 21+) Dumps threads incl. **virtual threads** to a file (plain or JSON) | `jcmd <pid> Thread.dump_to_file -format=json dump.json` | The way to dump millions of virtual threads |
| `jconsole` / VisualVM | GUI: live thread states, CPU, "Detect Deadlock" button, sampling profiler | Attach to local/remote JVM via JMX | VisualVM bundles a thread-dump and a basic profiler |
| `jcmd <pid> VM.flags` | Shows all JVM flags in effect (incl. defaults chosen by ergonomics) | `jcmd <pid> VM.flags -all` | Confirms what GC/lock flags are actually active |
| **ThreadMXBean** (JMX) | Programmatic dump + `findDeadlockedThreads()` + per-thread CPU and contention stats | `ManagementFactory.getThreadMXBean()` | Lets a service self-diagnose; enable `setThreadContentionMonitoringEnabled(true)` for block/wait counts |
| Thread-dump analyzers | Parse dumps, cluster identical stacks, highlight deadlocks/contention | fastthread.io (web), Eclipse MAT (for heap), `jstack.review` | Indispensable when you have 2,000 threads |

### 4.2 Profilers for contention, lock waits, and thread states

| Tool | What it captures | How | Overhead / notes |
|---|---|---|---|
| **JDK Flight Recorder (JFR)** | Built-in, low-overhead event stream: lock contention (`jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait`), thread park (`jdk.ThreadPark`), thread states, allocation, GC | `jcmd <pid> JFR.start name=r1 settings=profile duration=60s filename=r.jfr` then open in JDK Mission Control (JMC) | ~1–2% overhead with `profile` settings; production-safe. Has a configurable threshold (e.g. only record monitor blocks > 10/20 ms) |
| **async-profiler** | Sampling CPU, **`lock` mode** (contended-lock profiling), **`wall` clock** mode (great for finding *where threads block/sleep*), allocation. Produces flame graphs | `asprof -e lock -d 30 -f locks.html <pid>` (lock profiling); `asprof -e wall ...` for off-CPU | Very low overhead; `wall` mode is the killer feature for "why is my thread idle?" Not bundled with the JDK |
| **JDK Mission Control (JMC)** | GUI to analyze JFR recordings: lock-contention view, thread view, latency hotspots | Open `.jfr` files | Free Oracle/Adoptium tool |
| **perf** (Linux) + `perf-map-agent` | OS-level CPU + scheduler events; can see off-CPU time, futex contention | `perf record -g -p <pid>`; `perf sched` for scheduler latency | Needs symbol maps for Java frames; powerful for native/futex-level issues |
| **bpftrace / eBPF** | Trace futex syscalls, scheduler latency, off-CPU stacks at the kernel | `bpftrace` scripts (e.g. offcputime.bt) | Advanced; for "the JVM looks idle but isn't" cases |

> **Beginner note — flame graph.** A visualization where the x-axis is the proportion of samples (not time order) and the y-axis is stack depth; the widest frames are where most samples landed. A *CPU* flame graph shows where CPU is burned; a *lock* or *wall-clock* flame graph shows where threads *wait*. For concurrency, wall-clock and lock flame graphs are what you want.

> **Beginner note — off-CPU analysis.** Standard CPU profilers only sample threads *on* the CPU, so they're blind to threads that are *blocked* (the whole point in a hang). Off-CPU / wall-clock profiling samples threads regardless of state, revealing where they're stuck waiting — essential for contention and deadlock-adjacent problems.

### 4.3 Bug-detection tools (the part most engineers under-use)

| Tool | Detects | How it works | When to use |
|---|---|---|---|
| **jcstress** (Java Concurrency Stress) | Races, visibility bugs, reordering, atomicity violations, JMM violations | OpenJDK harness that runs a tiny code snippet across many threads **billions** of times, records *every observed outcome*, and classifies them as ACCEPTABLE / FORBIDDEN / INTERESTING. It uses JVM tricks (e.g. running on different memory models, forcing reorderings) to surface rare interleavings | To *prove* a small concurrent primitive is correct, or to demonstrate a JMM subtlety. The gold standard for verifying lock-free code |
| **Java Thread Sanitizer (`-XX:+UseThreadSanitizer`-style / `jtsan`)** + **ThreadSanitizer (TSAN)** | Data races (conflicting unsynchronized accesses), via happens-before tracking | A dynamic analysis that shadows every memory access and lock op, builds the happens-before graph at runtime, and flags conflicting unordered accesses. TSAN is native (C/C++/Go/Rust); for the JVM, `jtsan` and research tools (RoadRunner, FastTrack) exist | Native code in JNI; research/CI race detection. Note: a production-ready, drop-in TSAN for pure JVM Java is **not** standard — be precise about this in interviews |
| **SpotBugs + fb-contrib / Find Security Bugs** | Static patterns: inconsistent synchronization, `volatile` misuse, double-checked locking bugs, calling `wait` outside a loop, lazy init races | Bytecode static analysis with concurrency bug patterns (`IS2_INCONSISTENT_SYNC`, `DC_DOUBLECHECK`, `LI_LAZY_INIT_STATIC`, etc.) | In CI — cheap first line of defense. High value/low cost |
| **Error Prone** (Google) + **@GuardedBy / @ThreadSafe** annotations (JSR-305 / `javax.annotation.concurrent`) | Checks at compile time that fields annotated `@GuardedBy("lock")` are only accessed while holding that lock | Compiler plugin that understands the annotations | Excellent: encodes the locking policy in code and *enforces* it |
| **IntelliJ IDEA inspections** | "Field accessed in both synchronized and unsynchronized contexts," "non-final field guards lock," etc. | IDE static inspection | Live, in-editor |
| **Lincheck** (JetBrains) | Linearizability / correctness of concurrent data structures via model checking + stress | Generates random concurrent scenarios and verifies results are explainable by *some* sequential order; can also do bounded model checking to find the exact interleaving and **replay** it | Testing concurrent collections/algorithms; gives a reproducible failing schedule — huge for heisenbugs |

> **Beginner note — linearizability.** A correctness condition for concurrent objects: each operation appears to take effect instantaneously at some point between its call and return, and the overall result is consistent with *some* sequential ordering that respects real-time order. It's the formal target jcstress/Lincheck check against.

> **Beginner note — model checking.** Instead of relying on luck to hit a bad interleaving, a model checker *systematically explores* possible interleavings (within bounds) and reports a concrete failing schedule. Lincheck does a bounded form of this for JVM code, which is why it can *replay* the exact bug — the antidote to heisenbugs.

### 4.4 Language/library primitives you fix bugs with

| Primitive | Guarantee | Use for |
|---|---|---|
| `synchronized` (block/method) | Mutual exclusion + visibility (full happens-before) | Compound atomic operations on shared state |
| `volatile` | Visibility + ordering; atomic for single reads/writes (incl. 64-bit) | Flags, status fields, double-checked-locking field, single-writer counters read by many |
| `java.util.concurrent.atomic.*` (`AtomicInteger`, `AtomicReference`, `LongAdder`, `AtomicReferenceFieldUpdater`) | Lock-free atomic read-modify-write via CAS | Counters, lock-free state machines; `LongAdder` for high-contention counters |
| `ReentrantLock` / `ReentrantReadWriteLock` / `StampedLock` | Explicit locking with `tryLock(timeout)`, fairness, condition objects, read/write separation | Need timed/interruptible lock, multiple conditions, or read-heavy sharing |
| `j.u.c` collections (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `BlockingQueue`s) | Thread-safe per-operation; atomic compound ops via `compute`, `putIfAbsent`, `merge` | Replace `synchronized` wrappers; avoid check-then-act races |
| `CountDownLatch`, `CyclicBarrier`, `Phaser`, `Semaphore` | Coordination/permits | Start/finish gating, bounded concurrency |
| `CompletableFuture` / `ExecutorService` | Structured async + bounded thread pools | Avoid raw thread creation / thread leaks |
| `ThreadLocal` | Thread confinement (each thread its own copy) | Eliminate sharing entirely (but watch leaks in pools) |
| Immutability (`final`, records, unmodifiable collections) | No shared mutable state | The strongest fix: a bug that can't exist |

---

## 5. Code examples by use case

Each example is a *different* real scenario, with the bug, the diagnosis approach, and the fix.

### 5.1 Visibility bug — the loop that never stops

```java
// BUG: 'stop' is read by the worker thread but written by main thread,
// with no happens-before relationship. The JIT may hoist the read out
// of the loop into a register; the worker can spin forever.
public class VisibilityBug {
    private boolean stop = false;            // <-- not volatile

    void start() {
        new Thread(() -> {
            long i = 0;
            while (!stop) { i++; }            // may never observe stop=true
            System.out.println("stopped after " + i);
        }).start();
    }
    void shutdown() { stop = true; }          // write never made visible
}
```

**Diagnosis.** Symptom: a thread that should stop runs forever at 100% CPU. A thread dump shows the worker `RUNNABLE` in the `while` loop. The tell: the field it loops on is plain (non-volatile) shared state written by another thread. This is the canonical visibility bug; on server JIT it reproduces readily.

**Fix.** Make `stop` `volatile` (write happens-before subsequent read; barrier prevents the hoist):

```java
private volatile boolean stop = false;        // visibility + no register-hoisting
```

> Even better for cancellation: use `Thread.interrupt()` + check `Thread.currentThread().isInterrupted()`, which is the idiomatic, library-supported cancellation mechanism.

### 5.2 Atomicity violation — check-then-act on a thread-safe map

```java
// BUG: ConcurrentHashMap is thread-safe per call, but the compound
// "check then act" is NOT atomic. Two threads can both pass the check.
ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();

void register(String id) {
    if (!accounts.containsKey(id)) {          // T1 and T2 both see 'absent'
        accounts.put(id, new Account(id));    // both create -> one is lost
    }
}
```

**Fix** — use the atomic compound API:

```java
void register(String id) {
    accounts.computeIfAbsent(id, Account::new);  // atomic check-and-insert
}
```

**General rule.** Replacing a sequence of individually-safe calls with one atomic API (`computeIfAbsent`, `putIfAbsent`, `merge`, `compute`) eliminates an entire class of races. This is one of the most common production concurrency bugs.

### 5.3 Lost update — non-atomic counter, and the fix hierarchy

```java
// BUG: ++ is read-modify-write; concurrent increments lose updates.
private int count = 0;
void hit() { count++; }                        // race
```

Fix options, from simplest to most scalable:

```java
// Option A: AtomicInteger (CAS) — correct, good for moderate contention
private final AtomicInteger count = new AtomicInteger();
void hit() { count.incrementAndGet(); }

// Option B: LongAdder — best under HIGH contention (striped cells reduce
// CAS collisions; sum() aggregates). Slightly more memory; sum is eventually
// consistent within the call.
private final LongAdder count = new LongAdder();
void hit() { count.increment(); }
long total() { return count.sum(); }

// Option C: synchronized — correct but coarse; serializes all callers
private long count = 0;
synchronized void hit() { count++; }
```

> **When to pick what.** `AtomicInteger` for low/moderate contention or when you need the *return value* of the update. `LongAdder` for write-heavy metrics where you read rarely (it trades read cost for write scalability). `synchronized` when the increment is part of a larger atomic operation.

### 5.4 Deadlock — two locks, two orders (the classic)

```java
// BUG: transfer(a,b) and transfer(b,a) acquire locks in opposite orders.
// Under concurrency they can each hold one lock and wait for the other.
void transfer(Account from, Account to, long amount) {
    synchronized (from) {
        synchronized (to) {                    // <-- order depends on args
            from.debit(amount);
            to.credit(amount);
        }
    }
}
```

**Fix 1 — global lock ordering** (impose a total order so cycles are impossible):

```java
void transfer(Account from, Account to, long amount) {
    Account first  = from.id < to.id ? from : to;   // always lock lower id first
    Account second = from.id < to.id ? to   : from;
    synchronized (first) {
        synchronized (second) {
            from.debit(amount);
            to.credit(amount);
        }
    }
    // Handle from.id == to.id separately (self-transfer) to avoid double-lock logic.
}
```

**Fix 2 — `tryLock` with timeout/backoff** (break the wait, retry):

```java
void transfer(Account from, Account to, long amount) throws InterruptedException {
    while (true) {
        if (from.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            try {
                if (to.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                    try { from.debit(amount); to.credit(amount); return; }
                    finally { to.lock.unlock(); }
                }
            } finally { from.lock.unlock(); }
        }
        Thread.sleep(ThreadLocalRandom.current().nextInt(50)); // jittered backoff
        // randomized backoff avoids LIVELOCK (both retrying in lockstep)
    }
}
```

Note the jittered backoff: without randomness, two threads can repeatedly grab-and-release in sync — a **livelock**.

### 5.5 Self-diagnosing service — detect deadlock from inside the JVM

```java
// Periodic watchdog: uses ThreadMXBean to detect deadlocks and log culprits.
// Useful in production where you can't always attach jstack in time.
ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

watchdog.scheduleAtFixedRate(() -> {
    long[] ids = tmx.findDeadlockedThreads();      // also covers ownable (j.u.c) locks
    if (ids != null) {
        ThreadInfo[] infos = tmx.getThreadInfo(ids, true, true); // locked monitors + synchronizers
        StringBuilder sb = new StringBuilder("DEADLOCK DETECTED:\n");
        for (ThreadInfo ti : infos) {
            sb.append(ti.getThreadName())
              .append(" BLOCKED on ").append(ti.getLockName())
              .append(" owned by ").append(ti.getLockOwnerName()).append('\n');
            for (StackTraceElement e : ti.getStackTrace()) sb.append("\tat ").append(e).append('\n');
        }
        log.error(sb.toString());                  // alert, then maybe fail-fast/restart
    }
}, 10, 10, TimeUnit.SECONDS);
```

### 5.6 Proving correctness with jcstress (a visibility test)

```java
// A jcstress test: does a plain (non-volatile) publish work? It does NOT —
// jcstress will record the "(1, 0)" outcome (saw flag set, data stale) as observed.
@JCStressTest
@Outcome(id = "1, 1",  expect = Expect.ACCEPTABLE,           desc = "saw both writes")
@Outcome(id = "1, 0",  expect = Expect.ACCEPTABLE_INTERESTING, desc = "reordering/visibility seen")
@Outcome(id = "0, 0",  expect = Expect.ACCEPTABLE,           desc = "flag not yet seen")
@State
public class PublishTest {
    int data;
    boolean ready;                                  // plain field

    @Actor void writer() { data = 1; ready = true; }
    @Actor void reader(II_Result r) {               // captures two ints
        r.r1 = ready ? 1 : 0;
        r.r2 = data;
    }
}
```

Run with the jcstress harness; if `1, 0` appears, you've *empirically observed* the visibility/reordering bug. Marking `ready` and `data` correctly (e.g. `volatile ready`) makes `1, 0` FORBIDDEN. This is how you graduate from "I think it's safe" to "I proved it."

### 5.7 Finding lock contention with JFR / async-profiler

```bash
# Capture 60s of contention-focused data with JFR (production-safe).
jcmd <pid> JFR.start name=cont settings=profile duration=60s filename=cont.jfr
# ... let load run ...
# Open cont.jfr in JDK Mission Control -> "Lock Instances" / "Java Monitor Blocked"
# to see which monitors threads spent the most time blocked on, and the stacks.

# Or async-profiler in LOCK mode -> flame graph of contended locks:
asprof -e lock -d 30 -f locks.html <pid>
# Wide frames in locks.html = the hottest lock-acquisition sites = your contention.

# Wall-clock mode to find WHERE threads block/sleep (off-CPU):
asprof -e wall -d 30 -f wall.html <pid>
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Minimize lock scope.** Hold locks for the shortest critical section; do I/O, logging, and allocation *outside* the lock. A lock held during a 50 ms DB call becomes a system-wide bottleneck.
- **Reduce contention before reducing lock cost.** Striping (`LongAdder`, `ConcurrentHashMap` bins), sharding, and thread-confinement beat micro-optimizing a hot lock.
- **Prefer immutable / copy-on-write for read-mostly data.** `CopyOnWriteArrayList` is great for rarely-mutated, frequently-iterated lists (config, listeners) — but terrible if writes are frequent (each write copies the whole array).
- **Avoid lock convoys.** A **lock convoy** is when many threads queue on one lock and the system spends all its time context-switching threads in and out of the queue, collapsing throughput. Symptom: high context-switch count (`vmstat` `cs` column), low useful work. Fix by reducing the critical section or sharding the lock.
- **Beware false sharing.** Two unrelated fields on the *same 64-byte cache line* cause cores to invalidate each other's caches on every write, silently destroying scalability. Diagnose with `perf c2c`; fix with `@Contended` (`jdk.internal.vm.annotation.Contended`, needs `-XX:-RestrictContended`) or padding. (This is why `LongAdder` pads its cells.)

> **Beginner note — context switch.** When the OS swaps a thread off a CPU and another on, it saves/restores registers and pollutes caches — costing ~1–10 microseconds plus cache-miss aftershocks. Excessive switching (from too many runnable threads or convoys) is a hidden performance killer.

### 6.2 Correctness / concurrency

- **Encode the locking policy.** Use `@GuardedBy("lock")` so every field declares which lock protects it; enforce with Error Prone. This single habit prevents most "accessed in both synchronized and unsynchronized contexts" bugs.
- **One lock per invariant, consistently.** All accesses to a related group of fields must use the *same* lock. Mixing locks (or sometimes-no-lock) reintroduces races.
- **Never publish `this` from a constructor.** If you register a listener or start a thread referencing `this` before the constructor finishes, another thread can see a partially-constructed object (final-field guarantees don't apply to an escaped `this`).
- **Double-checked locking must use `volatile`.** The lazy-init field *must* be `volatile`, or a thread can see a non-null but not-fully-constructed object:

```java
private volatile Helper instance;            // volatile is mandatory
Helper get() {
    Helper h = instance;
    if (h == null) {
        synchronized (this) {
            h = instance;
            if (h == null) instance = h = new Helper();
        }
    }
    return h;
}
// Simpler & always correct for statics: the holder idiom.
private static class Holder { static final Helper INSTANCE = new Helper(); }
static Helper get() { return Holder.INSTANCE; }   // JVM class-init guarantees safety
```

- **Always `wait()` in a loop, guarded by the condition.** Spurious wakeups are legal; checking the predicate in a `while` (not `if`) handles them and stale notifications.

### 6.3 Security

- **TOCTOU (time-of-check to time-of-use).** A race between checking a permission/file and using it can be a vulnerability. Treat security checks as atomic operations.
- **Avoid leaking sensitive data via shared mutable buffers.** Reusing a `byte[]` across threads without synchronization can expose stale secrets or cause torn reads of credentials.
- **DoS via thread exhaustion.** Unbounded thread or task creation lets an attacker exhaust threads/memory. Always use *bounded* pools and queues.

### 6.4 Observability

- **Name your threads.** `Thread.setName` / a custom `ThreadFactory`. Anonymous `pool-3-thread-7` names make dumps unreadable; semantic names (`order-ingest-3`) make diagnosis instant.
- **Emit lock/contention metrics.** Micrometer/JMX timers around critical sections; track queue depths of executors (`ThreadPoolExecutor.getQueue().size()`), active counts, rejected tasks.
- **Keep JFR continuously running** (`-XX:StartFlightRecording=disk=true,maxsize=512m,maxage=6h`) so you have post-mortem data after an incident — "always-on flight recorder for the JVM."
- **Tag and propagate context** (MDC / `ThreadLocal`, or `ScopedValue` in modern JDKs) so logs from concurrent work are correlatable.

### 6.5 Cost

- Threads aren't free: each *platform* thread reserves stack (default ~512 KB–1 MB; tune with `-Xss`). 10,000 platform threads ≈ multiple GB of stack reservation. **Virtual threads** (JDK 21+, Project Loom) make threads cheap (~KB) and shift the cost model — but introduce new debugging needs (see §7).

### 6.6 Testing

- **Stress + interleaving tools, not luck.** Use jcstress for primitives, Lincheck for data structures, and high-thread-count stress tests with assertions for integration. A passing single run proves nothing.
- **Inject delays deterministically.** Tools/hooks (or `Thread.yield`, instrumentation) to widen race windows in tests.
- **Run tests with `-XX:+UseThreadSanitizer`-class native tooling for JNI**, and with assertions (`-ea`) on.

### 6.7 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Better |
|---|---|---|
| Calling `Thread.sleep` to "fix" a race | Masks the bug; reintroduces it under different timing | Proper synchronization/coordination |
| `synchronized` on a `String` literal or `Integer`/`Boolean` autobox | Interned/cached objects are shared JVM-wide → accidental cross-class deadlocks | Lock on a `private final Object lock = new Object();` |
| Double-checked locking without `volatile` | Can return a partially constructed object | `volatile` field or holder idiom |
| `synchronized` collection wrappers for compound ops | Each op safe, sequences race | `ConcurrentHashMap` atomic methods |
| Catching/ignoring `InterruptedException` | Breaks cancellation; threads can't be stopped | Restore interrupt flag or propagate |
| Locking on `this` in a public class | Outsiders can lock your monitor → unexpected contention/deadlock | Private lock object |
| Unbounded `Executors.newCachedThreadPool()` under load | Creates unlimited threads → OOM / thread exhaustion | Bounded `ThreadPoolExecutor` with a sized queue and rejection policy |
| `Thread.stop()` / `suspend()` / `resume()` | Deprecated, unsafe (can leave locks/state corrupt) | Interruption + cooperative cancellation |

---

## 7. Advanced topics & deep internals

### 7.1 Reading a thread dump like a forensic analyst

A `synchronized` deadlock in a dump (the part the JVM appends):

```
Found one Java-level deadlock:
=============================
"thread-A":
  waiting to lock monitor 0x00007f... (object 0x000000076ab..., a Account),
  which is held by "thread-B"
"thread-B":
  waiting to lock monitor 0x00007f... (object 0x000000076ad..., a Account),
  which is held by "thread-A"
```

The procedure when the JVM *doesn't* auto-detect (e.g., a logical/AQS deadlock, or a "stuck" state that's not a true cycle):

1. **Group identical stacks.** Many threads stuck at the same frame → that frame is the chokepoint (a hot lock, a slow downstream).
2. **For each BLOCKED thread,** note `waiting to lock <0xADDR>` and find the thread that has `- locked <0xADDR>` — that's the owner. Follow the chain. If it forms a cycle, it's a deadlock; if it ends at a thread that's `RUNNABLE` in slow I/O, it's *contention behind a slow operation* (not a deadlock — a different fix).
3. **For WAITING threads,** read `parking to wait for <0xADDR> (a ...ConditionObject)` to know which condition/queue, then find who should signal it.
4. **For RUNNABLE-in-native threads,** ignore them as idle unless the native call itself is the problem.
5. **Take 2–3 dumps a few seconds apart.** If the same threads are stuck at the same frames across dumps, it's a real hang; if stacks move, it's slow progress / contention, not a deadlock.

### 7.2 Deadlocks the JVM detector will miss

- **Resource deadlocks not via Java monitors or AQS:** e.g., two threads each holding a DB connection from a too-small pool, each needing a second connection → a *resource* deadlock the JVM can't see (it's outside JVM lock bookkeeping). Diagnose by reading stacks (both `parking`/`BLOCKED` on the pool's `getConnection`).
- **Deadlock across the JVM and an external system** (distributed deadlock) — needs cross-system tracing.
- **Lock-ordering deadlock with `ReentrantLock` only detected with `-l` / `findDeadlockedThreads`** — make sure you use the long form.

### 7.3 Livelock and starvation, precisely

- **Livelock:** threads are not blocked — they're actively running, repeatedly reacting to each other and undoing progress (the classic two-people-dodging-in-a-hallway). Symptom: high CPU, no throughput, stacks *change* between dumps. Common cause: retry-on-conflict with synchronized backoff. Fix: randomized/exponential backoff, or a single coordinator.
- **Starvation:** a thread never gets scheduled or never wins a lock. Causes: unfair locks (default `ReentrantLock` is *unfair* — favors throughput, can starve a thread; `new ReentrantLock(true)` is fair but slower), thread-priority misuse, or a greedy holder. **Priority inversion**: a low-priority thread holds a lock a high-priority thread needs, and a medium-priority thread preempts the low one, indefinitely blocking the high one. Diagnose with per-thread CPU time (`ThreadMXBean.getThreadCpuTime`) and contention counts.

### 7.4 Heisenbugs — why observation hides them, and how to win anyway

A **heisenbug** changes behavior when observed because logging, breakpoints, or a debugger alter timing (and may add memory barriers that accidentally fix visibility bugs). Strategies:

- **Add memory-barrier-free observation.** Record into a pre-allocated ring buffer (no logging I/O, no `synchronized` print) and dump it after the fact.
- **Increase the bad-interleaving probability:** add jitter (`Thread.yield()`/tiny sleeps) at suspected points *in tests*, run with many more threads than cores, and on weak-memory hardware (ARM) where reorderings are far more visible than on x86.
- **Use deterministic tools:** Lincheck/model checkers explore interleavings systematically and *replay* the failing schedule — turning a heisenbug into a deterministic, fixable one.
- **Capture state at failure**, not after: a watchdog that dumps threads/JFR the instant an invariant assertion fails.

### 7.5 Virtual threads (Project Loom, JDK 21+) — new debugging realities

Virtual threads are JVM-scheduled lightweight threads mounted onto a small pool of **carrier** (platform) threads. Debugging changes:

- **Dumps:** a normal `jstack` won't enumerate millions of virtual threads usefully; use `jcmd <pid> Thread.dump_to_file -format=json` which groups them and shows their (un)mounted state.
- **Pinning:** a virtual thread that enters a `synchronized` block (in older Loom builds) or runs a native frame could be **pinned** to its carrier, preventing unmount and starving the carrier pool. Detect with the JFR event `jdk.VirtualThreadPinned` and the flag `-Djdk.tracePinnedThreads=full`. (Recent JDKs reduce pinning, but it remains a key diagnostic.) Fix: replace `synchronized` with `ReentrantLock` in code that blocks while holding it.
- **`ThreadLocal` cost reconsidered:** with millions of virtual threads, `ThreadLocal` memory can explode; prefer `ScopedValue` (JDK 21+ preview/stable) for context propagation.

### 7.6 Memory-ordering depth: acquire/release and `VarHandle`

Beyond `volatile` (which is sequentially-consistent-ish and the strongest, most expensive ordering), `VarHandle` (JDK 9+) exposes finer modes: **plain**, **opaque**, **acquire/release**, and **volatile**.

- **Acquire** semantics on a read: nothing after it can be reordered before it. **Release** on a write: nothing before it can be reordered after it. Acquire/release pairs are cheaper than full volatile and enough to publish data safely. Example: `varHandle.setRelease(this, value)` paired with `varHandle.getAcquire(this)`. Use only when you've proven volatile is a measured bottleneck — it's expert territory and easy to get wrong (verify with jcstress).

### 7.7 GC pauses masquerading as concurrency bugs

A long stop-the-world GC pause can look like a hang or cause timeouts and "missed" notifications. Always rule it out: check GC logs (`-Xlog:gc*`), JFR pause events, and `jstat -gcutil`. A "deadlock" that's actually a 12-second GC pause is a different problem entirely (heap/GC tuning, not locks).

### 7.8 Safepoint-related latency

If a thread is in a tight, JIT-compiled **counted loop** without a safepoint poll, the whole JVM can stall waiting for it to reach a safepoint before a GC or thread dump. Diagnose with `-Xlog:safepoint` (look at "time to safepoint" / `ttsp`). Modern HotSpot has "loop strip mining" and finer safepoint placement to mitigate this.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing a synchronization mechanism

| Need | Use | Avoid when |
|---|---|---|
| Mutual exclusion + visibility, simple | `synchronized` | You need timed/interruptible acquisition, or it pins virtual threads |
| Timed/interruptible/fair lock, multiple conditions | `ReentrantLock` | Simplicity matters and you don't need its features |
| Read-mostly shared data | `ReentrantReadWriteLock` / `StampedLock` (optimistic reads) | Writes are frequent (writer starvation, copy cost) |
| Single counter/flag, lock-free | `Atomic*` / `volatile` | Operation is part of a larger invariant (need a lock) |
| High-contention counter, infrequent reads | `LongAdder` | You need exact instantaneous reads with low read latency |
| Thread-safe map with atomic compound ops | `ConcurrentHashMap` | You need a consistent snapshot across many keys |
| Eliminate sharing | Immutability / `ThreadLocal` / confinement | Data genuinely must be shared and mutated |

### 8.2 Choosing a diagnostic tool

| Situation | First reach for | Then |
|---|---|---|
| Service hung, zero CPU | `jstack -l` (2–3 dumps) → find cycle | `findDeadlockedThreads`, fastthread.io |
| Throughput collapsed under load, high CPU in kernel | async-profiler `lock` mode / JFR contention | `perf sched`, `vmstat` (context switches) |
| Thread idle but should be working | async-profiler `wall` mode (off-CPU) | bpftrace offcputime |
| Sporadic wrong result | code review for shared mutable state; SpotBugs | jcstress / Lincheck to reproduce |
| Loop never terminates | thread dump (RUNNABLE in loop) → check for missing `volatile` | jcstress publish test |
| Prove a lock-free algo correct | jcstress | Lincheck (linearizability) |
| Virtual-thread carrier starvation | `-Djdk.tracePinnedThreads`, JFR `VirtualThreadPinned` | `Thread.dump_to_file -format=json` |

### 8.3 Fix-strategy hierarchy (prefer top → bottom)

1. **Remove the sharing** (immutability, confinement) — the bug can't exist.
2. **Use a higher-level concurrent abstraction** (`ConcurrentHashMap`, `CompletableFuture`, atomics) — less code, fewer footguns.
3. **Use explicit locking with a declared policy** (`@GuardedBy`, consistent lock per invariant, lock ordering).
4. **Hand-rolled lock-free with `VarHandle`** — last resort, must be proven with jcstress.

---

## 9. Failure modes & debugging (with commands and incidents)

### 9.1 Deadlock in production

**Symptom:** requests time out, thread pool saturates, CPU near zero. **Diagnose:**

```bash
jcmd <pid> Thread.print -l > dump1.txt; sleep 5; jcmd <pid> Thread.print -l > dump2.txt
grep -A4 "Found one Java-level deadlock" dump1.txt   # auto-detected cycles
```

If no auto-detection, manually chain `waiting to lock <X>` → `locked <X>` across threads. **Incident pattern:** a logging framework that synchronizes on an appender while a custom appender calls back into application code that locks an app object — a classic two-library lock-order inversion. **Fix:** impose global lock ordering or remove the reentrant callback.

### 9.2 Thread/connection-pool exhaustion (resource deadlock)

**Symptom:** all pool threads `BLOCKED`/`parking` in `getConnection`, throughput zero, but the JVM reports *no* deadlock. **Diagnose:** dump shows many threads stuck at the pool's acquire; pool metrics show `active == max`, `idle == 0`, growing wait queue. **Root cause:** a code path that holds one pooled resource while requesting another, with pool size < needed concurrency. **Fix:** acquire all resources up front, increase pool size, or restructure to hold one at a time. **Real-world:** HikariCP's `leakDetectionThreshold` logs the stack of connections held too long — turn it on.

### 9.3 Lock contention / convoy

**Symptom:** adding threads *decreases* throughput; high `cs` (context switches) in `vmstat 1`; latency p99 explodes. **Diagnose:**

```bash
asprof -e lock -d 30 -f locks.html <pid>     # widest frame = hottest lock
jcmd <pid> JFR.start settings=profile duration=60s filename=c.jfr  # JMC "Java Monitor Blocked"
```

**Fix:** shrink the critical section (move I/O/allocation out), shard the lock, switch a hot counter to `LongAdder`, or use a read-write/optimistic lock.

### 9.4 Visibility bug

**Symptom:** a worker ignores a shutdown flag; a cache never sees an update; works in debug, fails in prod. **Diagnose:** thread dump shows the thread `RUNNABLE` looping; code review finds a shared field written by one thread, read by another, with no `volatile`/lock. **Reproduce/prove:** a jcstress publish test (§5.6), ideally on ARM. **Fix:** `volatile` or proper synchronization.

### 9.5 The "it only fails in prod" heisenbug

**Symptom:** CI is green, prod corrupts data weekly. **Diagnose:** you cannot reproduce by re-running. Approach: (1) add a low-overhead in-memory event recorder around the suspect state and dump on the next failure; (2) run the suspect unit in jcstress/Lincheck; (3) audit for shared mutable state under load that single-threaded tests never exercise. **Incident pattern:** a "thread-safe" lazily-initialized singleton with non-`volatile` double-checked locking that only torn-publishes on multi-socket production hardware.

### 9.6 Stuck-but-not-deadlocked (slow downstream)

**Symptom:** looks like a hang, but stacks change across dumps and one thread is `RUNNABLE` in `socketRead0`. **Reality:** everyone is queued behind one slow external call. **Fix:** timeouts on the downstream, bulkheads/circuit breakers, and isolating the slow dependency to its own bounded pool so it can't drain the shared pool.

### 9.7 Virtual-thread pinning starving carriers

**Symptom (JDK 21+):** under load, work stalls though virtual threads are cheap. **Diagnose:** `-Djdk.tracePinnedThreads=full` logs pinned stacks; JFR `jdk.VirtualThreadPinned` events. **Fix:** replace `synchronized` blocks that block (e.g., around I/O) with `ReentrantLock`.

---

## 10. Interview drill

**Q1. What's the difference between a race condition and a data race?**
*Model answer:* A **data race** is a JMM-precise condition: two threads access the same variable, at least one writes, and there is no happens-before ordering between them — the result is undefined. A **race condition** is a broader correctness property: the program's correctness depends on the relative timing of operations. You can have a race condition without a data race (a check-then-act on a `ConcurrentHashMap`, where each call is safe but the sequence isn't), and a data race that doesn't manifest on a given run.
- *Follow-up: Give a race condition with no data race.* → `if(!map.containsKey(k)) map.put(k,v)` on a `ConcurrentHashMap`: both calls are individually safe (no data race), but two threads can both pass the check.
- *Follow-up: Fix it.* → `computeIfAbsent`, which makes the check-and-act atomic.
- *Follow-up: Why does the JMM call data-race results "undefined" rather than "stale"?* → Because reorderings and torn reads can produce values that no sequential execution could — not merely an old value.

**Q2. How do you find a deadlock from a thread dump?**
*Model answer:* Take 2–3 dumps a few seconds apart with `jstack -l`. The JVM appends a `Found one Java-level deadlock` section for monitor *and* AQS-based (`ReentrantLock`) cycles. If not auto-detected, for each `BLOCKED` thread read `waiting to lock <0xADDR>`, then find the thread with `- locked <0xADDR>`; follow the chain — a cycle is a deadlock. Stable stacks across dumps confirm a real hang vs. slow progress.
- *Follow-up: Why `-l`?* → It includes ownable synchronizers (j.u.c locks); without it you can miss `ReentrantLock` deadlocks.
- *Follow-up: A thread is `RUNNABLE` in `socketRead0` — is it part of the deadlock?* → Usually no; native blocking shows as RUNNABLE, it's idle on I/O, likely contention behind a slow call, not a lock cycle.

**Q3. Explain `volatile`. What does it guarantee and not guarantee?**
*Model answer:* `volatile` guarantees **visibility** (a write is seen by subsequent reads), **ordering** (memory barriers prevent reordering across it), and **atomicity of single reads/writes including 64-bit `long`/`double`**. It does **not** make compound operations atomic — `volatileCounter++` is still a race because it's read-modify-write.
- *Follow-up: When is `volatile` enough?* → Single-writer flags/status, or publishing an immutable object reference (set once, read by many).
- *Follow-up: Why does it fix the "loop never stops" bug?* → It prevents the JIT from hoisting the field read into a register and forces a fresh read each iteration, plus cross-core visibility.

**Q4. What is double-checked locking and what's the pitfall?**
*Model answer:* A lazy-init pattern that checks the field, locks only if null, re-checks, then assigns. The pitfall: without a `volatile` field, a thread can observe a non-null reference to a *not-yet-fully-constructed* object due to reordering of the constructor and the assignment. Mark the field `volatile`, or use the holder idiom for statics (class init is JVM-guaranteed thread-safe).
- *Follow-up: Why does volatile fix it?* → The release-store of the volatile write ensures the constructor's writes happen-before the field assignment becomes visible.
- *Follow-up: Cheaper alternative?* → The initialization-on-demand holder idiom; or for finer control, `VarHandle` acquire/release (proven with jcstress).

**Q5. A counter is occasionally wrong under load. Walk me through diagnosis and fix.**
*Model answer:* Likely a lost-update race on `count++`. Confirm via code review (shared field, read-modify-write, no lock/atomic). Reproduce with a high-thread stress test asserting the final value, or jcstress. Fix with `AtomicInteger.incrementAndGet()`, `LongAdder` for high contention, or `synchronized` if part of a larger invariant.
- *Follow-up: `AtomicInteger` vs `LongAdder`?* → Atomic uses a single CAS (good low contention, gives return value); LongAdder stripes across cells to cut CAS collisions (best write-heavy, read rarely; sum is aggregated).
- *Follow-up: Why is `++` not atomic?* → It's three steps (load, add, store); the scheduler can interleave them.

**Q6. How would you detect lock contention in production, with minimal overhead?**
*Model answer:* JDK Flight Recorder with `settings=profile` (~1–2% overhead) captures `JavaMonitorEnter`/`ThreadPark` events; analyze in JMC's monitor-blocked view to find the hottest lock and its stack. async-profiler `-e lock` gives a contended-lock flame graph; `-e wall` finds off-CPU waiting. Corroborate with `vmstat` context-switch counts.
- *Follow-up: Why not just a CPU profiler?* → It samples on-CPU threads only; contention is *off-CPU* waiting, which CPU profilers miss. Use wall-clock/lock modes.
- *Follow-up: How do you fix a hot lock?* → Shrink the critical section, move I/O out, shard/strip the lock, use read-write/optimistic locking, or eliminate the sharing.

**Q7 (senior-signal). When would you choose `synchronized` over `ReentrantLock`, and vice versa? Justify.**
*Model answer:* `synchronized` is simpler, auto-released on scope exit (no leak risk), JIT-optimized, and the default choice for straightforward mutual exclusion. Choose `ReentrantLock` when you need timed/interruptible acquisition (`tryLock(timeout)`), fairness, multiple condition variables, or lock acquisition that spans methods. On JDK 21+ with virtual threads, prefer `ReentrantLock` where you block while holding the lock, because `synchronized` can pin the virtual thread to its carrier and starve the pool.
- *Follow-up: Cost of fairness?* → A fair lock serializes by arrival order, reducing throughput and increasing context switches; use only when starvation is a real risk.
- *Follow-up: How do you avoid leaking a `ReentrantLock`?* → Always `lock()` then `try { } finally { unlock(); }`.

**Q8 (senior-signal). Your service intermittently corrupts data only in production, never in tests. How do you approach it?**
*Model answer:* Treat it as a concurrency heisenbug: tests are single-threaded or lightly loaded and don't hit the bad interleaving. Steps: (1) audit for shared mutable state accessed without consistent synchronization; (2) reproduce deterministically with jcstress (primitives) or Lincheck (data structures), which explore/replay interleavings; (3) if not reproducible offline, add a low-overhead in-memory event recorder + a watchdog that dumps threads/JFR the instant an invariant assertion fails; (4) prefer fixes that remove sharing entirely. Crucially, *prove* the fix with a stress/model-checking tool, not by "didn't repro."
- *Follow-up: Why might adding logging hide it?* → Logging adds I/O latency and memory barriers (synchronization inside the logger) that change timing and can accidentally satisfy visibility.
- *Follow-up: Why test on ARM?* → Weaker memory model exposes reorderings/visibility bugs that x86's stronger model hides.

**Q9 (senior-signal). Defend a choice: lock-free (CAS/`VarHandle`) vs. lock-based for a hot data structure.**
*Model answer:* Lock-free can scale better under contention (no blocking, no convoys, no priority inversion) and improves tail latency, but it's far harder to get correct (ABA problems, subtle memory ordering) and must be verified with jcstress. Lock-based is simpler, easier to reason about and maintain, and often "fast enough." I'd default to lock-based (or a battle-tested j.u.c structure), measure, and only go lock-free where profiling proves the lock is the bottleneck and the structure is small/well-understood — and then I'd back it with jcstress and Lincheck.
- *Follow-up: What's the ABA problem?* → A CAS sees a value change A→B→A and assumes nothing changed, but state did; fix with `AtomicStampedReference`/version counters.
- *Follow-up: Why is `LongAdder` a middle ground?* → It's lock-free per-thread striped CAS but with simple, proven semantics — scalability without hand-rolled complexity.

**Q10. Distinguish deadlock, livelock, and starvation.**
*Model answer:* **Deadlock** — threads block forever in a circular wait (no CPU, no progress). **Livelock** — threads run actively, reacting to each other and undoing progress (high CPU, no progress, stacks change between dumps). **Starvation** — a thread is perpetually denied a resource it needs (one operation never completes), e.g., unfair locks or priority inversion.
- *Follow-up: Fix livelock?* → Randomized/exponential backoff or a coordinator to break symmetry.
- *Follow-up: Default `ReentrantLock` fairness?* → Unfair (favors throughput); can starve a thread. `new ReentrantLock(true)` is fair but slower.

**Q11. The four Coffman conditions for deadlock — name them and how each gives a fix.**
*Model answer:* (1) **Mutual exclusion** — resources non-shareable; reduce by using shareable/immutable data. (2) **Hold-and-wait** — hold one, request another; fix by acquiring all at once. (3) **No preemption** — can't forcibly take locks; fix with `tryLock`+timeout to voluntarily release. (4) **Circular wait** — a cycle of waits; fix by global lock ordering. Breaking *any one* prevents deadlock.
- *Follow-up: Which is the most practical to break?* → Circular wait, via a consistent global lock-acquisition order.

**Q12. What does `Thread.State.RUNNABLE` actually mean in a dump, and why is it a trap?**
*Model answer:* RUNNABLE means the thread is executing or eligible to execute — *but* a thread blocked in a native call (e.g., `socketRead0`) also shows RUNNABLE because the JVM can't see the native block. The trap: assuming RUNNABLE means CPU-busy. Always read the stack to tell real CPU work from native I/O waiting.
- *Follow-up: How confirm CPU vs. idle?* → Per-thread CPU time (`ThreadMXBean.getThreadCpuTime`) or OS `top -H`; an "idle" RUNNABLE thread accrues little CPU.

---

## 11. Glossary

- **ABA problem** — A CAS sees a value change from A to B back to A and wrongly assumes nothing happened; fixed with versioned references (`AtomicStampedReference`).
- **Acquire/release semantics** — Memory-ordering modes weaker than `volatile`: acquire-read forbids later ops moving before it; release-write forbids earlier ops moving after it.
- **AQS (`AbstractQueuedSynchronizer`)** — The internal framework (state int + FIFO wait queue) behind `ReentrantLock`, `Semaphore`, `CountDownLatch`, etc.
- **Atomicity** — A group of operations completes as one indivisible unit; no thread observes a partial state.
- **Biased locking** — Legacy HotSpot optimization biasing a lock to one thread; deprecated JDK 15, removed JDK 18.
- **BLOCKED** — Thread state: waiting to acquire a `synchronized` monitor held by another thread.
- **CAS (compare-and-swap)** — Atomic CPU instruction: set memory to a new value only if it equals an expected value; basis of lock-free code.
- **Coffman conditions** — The four necessary conditions for deadlock: mutual exclusion, hold-and-wait, no preemption, circular wait.
- **Context switch** — OS swapping one thread off a CPU and another on; costly in cycles and cache effects.
- **Critical section** — The code region that must run under mutual exclusion.
- **Data race** — JMM term: conflicting unsynchronized accesses to the same variable (≥1 write) with no happens-before ordering; result undefined.
- **Deadlock** — Threads blocked forever in a circular wait for resources.
- **Double-checked locking** — Lazy-init pattern that requires a `volatile` field to be correct.
- **eBPF / bpftrace** — Kernel tracing technology used for off-CPU and futex analysis.
- **False sharing** — Unrelated fields on one cache line causing cross-core cache invalidation and lost scalability.
- **Fence / memory barrier** — Instruction that orders memory ops and forces cache/store-buffer consistency at that point.
- **Flame graph** — Stack-aggregated visualization where width = sample proportion; used for CPU, lock, and wall-clock profiling.
- **futex** — Linux "fast userspace mutex" syscall used to implement contended locks (threads sleep in the kernel).
- **`@GuardedBy`** — Annotation declaring which lock protects a field; enforceable by Error Prone.
- **Happens-before** — JMM ordering relation guaranteeing visibility and ordering between actions.
- **Heisenbug** — A bug that changes/disappears when you try to observe it (observation alters timing).
- **JFR (Java Flight Recorder)** — Built-in low-overhead JVM event recorder.
- **jcstress** — OpenJDK harness that stress-tests tiny concurrent snippets to surface and classify all observed outcomes.
- **JIT (Just-In-Time compiler)** — Compiles hot bytecode to native code at runtime; may reorder memory operations.
- **JMM (Java Memory Model)** — Spec (JSR-133) defining visibility and ordering guarantees for multithreaded Java.
- **JMC (JDK Mission Control)** — GUI for analyzing JFR recordings.
- **Lincheck** — JetBrains tool for testing concurrent data-structure correctness via stress + model checking, with replayable failures.
- **Linearizability** — Correctness condition: operations appear to take effect atomically at a point between call and return, consistent with a real-time-respecting sequential order.
- **Livelock** — Threads actively run but make no progress, reacting to each other.
- **Lock contention** — Threads spending significant time waiting to acquire a lock.
- **Lock convoy** — Throughput collapse from many threads queuing on one lock and thrashing the scheduler.
- **`LockSupport.park`** — Low-level blocking primitive underlying j.u.c synchronizers.
- **`LongAdder`** — High-contention counter using striped cells to reduce CAS collisions.
- **Mark word** — Part of an object header storing lock/identity/GC state.
- **Model checking** — Systematic exploration of interleavings to find/replay a failing schedule.
- **Monitor / intrinsic lock** — The per-object lock used by `synchronized`; tracks owner, entry list, wait set.
- **MVCC (multi-version concurrency control)** — A database technique keeping multiple versions of data so readers don't block writers (mentioned as adjacent context; relevant when DB-level concurrency interacts with app threads).
- **Off-CPU analysis** — Profiling threads while they're blocked/sleeping (not on a CPU).
- **Priority inversion** — A low-priority thread holding a lock blocks a high-priority thread, worsened by a medium-priority thread preempting the low one.
- **Pinning (virtual threads)** — A virtual thread stuck to its carrier (e.g., in `synchronized`/native), preventing unmount.
- **Race condition** — Correctness depends on thread timing/interleaving (broader than data race).
- **RUNNABLE** — Thread state: executing or eligible to run (also shown for native blocking I/O — a trap).
- **Safepoint** — A globally consistent pause point where the JVM can inspect/modify stacks and heap (e.g., for GC or thread dumps).
- **Spurious wakeup** — A waiting thread waking without a `notify`; why you must re-check the predicate in a loop.
- **Starvation** — A thread perpetually denied a needed resource.
- **Store buffer** — Per-core hardware queue holding writes not yet visible to other cores; reason visibility bugs exist.
- **TOCTOU** — Time-of-check-to-time-of-use race, often a security vulnerability.
- **Torn read/write** — Reading/writing a value in pieces so another thread sees a half-updated value (e.g., non-volatile 64-bit fields).
- **TSAN (ThreadSanitizer)** — Dynamic data-race detector (native; JVM equivalents are research-grade).
- **`VarHandle`** — JDK 9+ API exposing fine-grained atomic and memory-ordering operations on fields/arrays.
- **Virtual thread** — JDK 21+ lightweight thread scheduled by the JVM onto carrier platform threads.
- **Visibility** — Guarantee that one thread's writes become observable to another.
- **`volatile`** — Field modifier giving visibility, ordering, and single-access atomicity (incl. 64-bit), but not compound atomicity.
- **WAITING / TIMED_WAITING** — Thread states for indefinite / time-bounded waits (`wait`, `join`, `park`, `sleep`).
- **Watchdog** — A monitoring component that periodically checks health (e.g., calls `findDeadlockedThreads`) and acts.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Bug taxonomy:** race condition (timing-dependent correctness) · data race (unsynchronized conflicting access) · atomicity violation (compound op interrupted) · visibility (write never seen) · deadlock (circular wait) · livelock (active no-progress) · starvation (perpetually denied) · contention/convoy (waiting > working).

**JMM essentials:** happens-before via program order, monitor unlock→lock, volatile write→read, start/join, transitivity, final fields. No happens-before + conflicting access = data race = undefined.

**`volatile` gives:** visibility + ordering + single-access atomicity (incl. 64-bit). **Not** compound atomicity (`v++` races).

**Thread states:** RUNNABLE (incl. native I/O — trap!), BLOCKED (monitor), WAITING/TIMED_WAITING (wait/join/park/sleep). Read the *stack*, not just the state.

**Dump diagnosis:** `jstack -l <pid>` ×2–3, 5s apart. Match `waiting to lock <X>` ↔ `locked <X>` to chain owners; cycle = deadlock; stable stacks = real hang. `-l` needed for `ReentrantLock` cycles.

**Capture commands:**
- `jcmd <pid> Thread.print -l` / `kill -3 <pid>` (→ stdout)
- `jcmd <pid> JFR.start settings=profile duration=60s filename=r.jfr`
- `asprof -e lock|wall -d 30 -f out.html <pid>`
- `jcmd <pid> Thread.dump_to_file -format=json d.json` (virtual threads)
- `-Djdk.tracePinnedThreads=full` (Loom pinning)

**Fix hierarchy:** remove sharing (immutable/confine) → high-level concurrent abstraction → explicit locking with `@GuardedBy` + global lock order → hand-rolled `VarHandle` (prove with jcstress).

**Counters:** low contention → `AtomicInteger`; write-heavy/rare-read → `LongAdder`; part of bigger invariant → `synchronized`.

**Deadlock fixes (break a Coffman condition):** global lock ordering (circular wait) · `tryLock`+timeout+jittered backoff (no preemption/hold-and-wait) · acquire all at once (hold-and-wait).

**Defaults to remember:** `ReentrantLock` is **unfair** by default · biased locking removed JDK 18 · 64-bit non-volatile reads/writes may tear · `synchronized` can **pin** virtual threads · JFR `profile` ≈ 1–2% overhead · platform thread stack ≈ 512 KB–1 MB (`-Xss`).

**Anti-patterns:** `sleep` to fix a race · lock on `String`/boxed/`this` · DCL without `volatile` · check-then-act on concurrent maps · swallowing `InterruptedException` · unbounded `newCachedThreadPool` · `Thread.stop/suspend`.

**Prove, don't pray:** jcstress (primitives) · Lincheck (data structures, replayable) · SpotBugs/Error Prone (`@GuardedBy`) in CI.

### 12.2 Self-test (no answers — recall actively)

1. A thread loops forever on a non-volatile boolean another thread set to `true`. Name the bug class, explain *why* the JIT/CPU permit it (cite the JMM mechanism), and give two distinct fixes.
2. You have a thread dump where the JVM did *not* print "Found one Java-level deadlock," yet the service is hung. Describe step by step how you'd manually determine whether it's a deadlock, contention behind a slow call, or a resource (pool) deadlock — including which dump lines you'd match.
3. Compare `AtomicInteger`, `LongAdder`, and `synchronized` for a counter incremented by 64 threads and read once per second. Which do you choose and why? What changes if you must read the exact value on every increment?
4. Explain how you would turn a once-a-week production heisenbug into a deterministic, reproducible failure. Name specific tools and at least two timing/hardware tricks, and explain why adding logging might hide the bug.
5. Defend or refute: "We should rewrite our hot `ConcurrentHashMap`-based cache as a lock-free structure with `VarHandle` for performance." What evidence would you require first, what risks would you call out (name at least one concrete correctness hazard), and how would you verify the result?
6. Given virtual threads (JDK 21+), explain what "pinning" is, how it can collapse throughput, how you'd detect it, and the code-level fix.
7. List the four Coffman conditions and, for each, give one concrete Java code change that breaks it.

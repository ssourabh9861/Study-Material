# Threads & Context Switching

> A definitive engineering-handbook chapter for senior JVM/backend developers. From first principles to deep kernel and JVM internals, tuning, debugging, and interview-grade mastery.

---

## 1. Overview & where it fits

A **thread** is the smallest unit of execution that an operating system (OS) can independently schedule onto a CPU. When your Java program runs, it does not run "on the CPU" continuously — it runs in bursts, interleaved with thousands of other threads on the machine. The mechanism that lets the OS take one thread off a CPU core and put another one on is called **context switching**, and understanding it is the difference between writing concurrent code that scales and writing concurrent code that mysteriously slows down under load.

**The problem threads solve.** A single CPU core executes one instruction stream at a time. But programs need to do many things "at once": serve thousands of HTTP requests, wait for a database while still accepting new connections, run background compaction while answering queries. Threads provide the *illusion of simultaneity* on a single core (via rapid switching) and *true parallelism* on multiple cores (one thread per core, genuinely at the same instant). Concurrency is about *dealing with* many things at once (structure); parallelism is about *doing* many things at once (execution). Threads are the primitive underneath both.

**When you reach for it.** You reach for threads (directly or, more often, through higher-level abstractions like thread pools, executors, `CompletableFuture`, or reactive frameworks) whenever you need to overlap work — overlap CPU computation across cores, or overlap waiting (I/O) so the CPU isn't idle while a disk or network responds. You reach *down* to the thread-and-context-switching level of understanding when you are tuning a server's thread-pool size, diagnosing a latency regression, explaining why adding more threads made things slower, or sizing a system for a given hardware.

**The one-paragraph mental model.** Think of a CPU core as a single chef in a kitchen and threads as recipes the chef is cooking. The chef can only chop or stir one thing at a moment. To make progress on many dishes, the chef rapidly rotates: stir dish A for a few seconds, set it down, pick up dish B's instructions, find their place, work on B, set it down, pick up C. Each time the chef switches dishes, there's overhead — putting down one set of tools and ingredients, picking up another, *remembering where they were* in the new recipe. That switch cost is the **context switch**. If the chef switches too often (e.g., once per second across 500 dishes), they spend more time switching than cooking — that's **thrashing**. A thread pool is hiring exactly as many chefs as you have stove burners (cores) and keeping them busy, rather than hiring a new chef for every order and firing them after one dish (the cost of `new Thread()` per task).

On the JVM specifically, until Project Loom's **virtual threads** (Java 21, 2023), every Java thread you created was a **platform thread**: a thin wrapper around exactly one OS thread (a **1:1 mapping**). That makes threads relatively expensive (each carries a large stack and is scheduled by the kernel), which is *why* thread pools exist and *why* you can't just spawn a million of them. Virtual threads change the economics by multiplexing many Java threads onto a few OS threads — but they sit on top of, and are best understood through, the platform-thread-and-context-switching model this chapter builds. We forward-reference them throughout and treat them properly in §7.

---

## 2. Foundations from first principles

We build the vocabulary from zero. Every term is defined where it first appears.

### 2.1 Process vs thread

A **process** is a running instance of a program. The OS gives each process its own **virtual address space** — an isolated, private view of memory (the process thinks it owns all of RAM; the OS and CPU hardware translate its addresses to real physical RAM behind its back). A process owns resources: open file descriptors, sockets, memory mappings, environment variables. Processes are isolated from each other: process A cannot read process B's memory directly. That isolation is a safety boundary but makes communication between processes expensive (you need inter-process communication, IPC — pipes, sockets, shared memory).

A **thread** is an execution context *inside* a process. A process always has at least one thread (the "main" thread). All threads in a process **share the same address space** — the same heap, the same global variables, the same open files. What each thread has *privately* is:

- A **program counter (PC)** — also called the instruction pointer (IP); a register holding the address of the next instruction this thread will execute.
- A set of **CPU registers** — small, ultra-fast storage cells inside the CPU holding the values the thread is currently computing with.
- A **stack** — a region of memory holding the thread's call frames (local variables, return addresses, function arguments). Each thread has its own stack so that two threads calling the same function don't clobber each other's locals.

Because threads share memory, communication between them is cheap (just read/write a shared variable) — but that very cheapness is the source of all concurrency bugs (data races, visibility problems, deadlocks). Sharing is a double-edged sword.

> **Term — register.** A register is a tiny piece of storage physically inside the CPU, the fastest memory that exists (sub-nanosecond access). A typical x86-64 core has 16 general-purpose 64-bit registers (RAX, RBX, …), plus special ones (the PC/RIP, the stack pointer RSP, flags), plus vector registers (XMM/YMM/ZMM, up to 512 bits) for SIMD math. The full set of register values *is* the live state of a running thread. Saving and restoring this set is the core of a context switch.

> **Term — stack vs heap.** The **stack** grows and shrinks automatically as functions are called and return; it holds local variables and bookkeeping, and is per-thread. The **heap** is a large shared pool for dynamically allocated objects (everything you `new` in Java lives on the heap), shared by all threads. In Java, object *references* may sit on a thread's stack, but the objects they point to live on the shared heap — which is why two threads can see the same object.

### 2.2 What an OS thread is

An **OS thread** (also "kernel thread" or "native thread") is a thread the *kernel knows about and schedules*. The **kernel** is the core of the OS — the privileged code that manages hardware, memory, and which thread runs on which CPU at any instant. On Linux, an OS thread is internally a "task" represented by a `task_struct` and created by the `clone()` system call with flags that say "share the address space with the parent." (A Linux *process* is just `clone()` without those sharing flags — under the hood Linux treats processes and threads almost identically, as schedulable tasks distinguished mainly by what they share.)

> **Term — system call (syscall).** A request from your program (running in unprivileged "user mode") into the kernel (privileged "kernel mode") to do something only the kernel can do: read a file, send a network packet, create a thread, sleep. A syscall involves a controlled mode-switch into the kernel and back, costing on the order of tens to hundreds of nanoseconds even when it does little.

> **Term — user mode vs kernel mode.** Modern CPUs have privilege levels (x86 calls them "rings"). Your application code runs in **user mode** (ring 3), which forbids dangerous operations (direct hardware access, modifying page tables). The kernel runs in **kernel mode** (ring 0). Crossing from user to kernel mode (and back) is a *mode switch* — cheaper than a full context switch but not free.

### 2.3 What a JVM thread is, and the 1:1 mapping

A **JVM thread** is the Java-level object you manipulate via `java.lang.Thread`. The critical fact for **platform threads** (the only kind before Java 21): **each `java.lang.Thread` is backed 1:1 by exactly one OS thread.** When you call `thread.start()`, the JVM (specifically the HotSpot VM) asks the OS to create a native thread (via `pthread_create` on Linux/macOS, which itself calls `clone()`), and that native thread runs the Java bytecode of your `run()` method. Scheduling decisions — *when* your Java thread runs, on which core, for how long — are made by the **OS scheduler**, not by the JVM. The JVM is essentially a passenger; the kernel drives.

> **Term — HotSpot.** HotSpot is the standard JVM implementation shipped in OpenJDK and Oracle JDK. It's where threads, garbage collection, and the JIT (just-in-time) compiler live. When this doc says "the JVM," it means HotSpot unless stated otherwise.

This 1:1 model has consequences that motivate everything else:

1. **Threads are heavyweight.** Each carries a real OS thread with a real OS stack. Default Java stack size is around **512 KB on many platforms, 1 MB on others** (controlled by `-Xss`; the exact default is platform- and version-specific — check with `java -XX:+PrintFlagsFinal -version | grep ThreadStackSize`). A machine can hold thousands of threads, not millions, before running out of memory or scheduler capacity.
2. **Creating a thread is slow** — it's a syscall into the kernel, allocation of a stack, registration with the scheduler. Order of tens of microseconds to a millisecond. This is *why thread pools exist*: amortize creation cost by reusing threads.
3. **Blocking a Java thread blocks an OS thread.** If a platform thread does a blocking read on a socket, the underlying OS thread is parked and cannot do other work. With thousands of concurrent blocking I/O operations you'd need thousands of threads — hence the historical move to non-blocking I/O (NIO, Netty, reactive) *or*, now, to virtual threads.

> **Forward reference — virtual threads (Loom).** A **virtual thread** (Java 21+) is *not* 1:1 with an OS thread. The JVM schedules many virtual threads onto a small pool of OS "carrier" threads (an **M:N model** — M virtual threads on N OS threads). When a virtual thread blocks on I/O, the JVM unmounts it from its carrier and runs another virtual thread there, so a blocked virtual thread costs almost nothing. Virtual threads make the "thread per request, blocking style" cheap again. We cover them fully in §7; for now, just hold the contrast: *platform thread = 1 OS thread (expensive); virtual thread = scheduled by the JVM onto shared carriers (cheap).*

### 2.4 The thread lifecycle and its states

A Java thread moves through a well-defined set of **states**, exposed via `Thread.getState()` returning a `Thread.State` enum. These are the six values; learn them precisely because interviewers and profilers both use them.

| State | Meaning | How you enter it | How you leave it |
|---|---|---|---|
| `NEW` | Thread object created but `start()` not yet called. No OS thread exists yet. | `new Thread(...)` | Calling `start()` → RUNNABLE |
| `RUNNABLE` | Eligible to run: either running on a CPU *or* sitting in the OS run queue waiting for a CPU. Java does **not** distinguish "running now" from "ready to run." | `start()`, or returning from a blocked/waiting state | The OS deschedules it (still RUNNABLE), or it blocks/waits/terminates |
| `BLOCKED` | Waiting to acquire a **monitor lock** (the lock behind `synchronized`) that another thread holds. | Hitting a `synchronized` block/method whose monitor is taken | Acquiring the monitor → RUNNABLE |
| `WAITING` | Waiting indefinitely for another thread to signal it. | `Object.wait()`, `Thread.join()`, `LockSupport.park()` — all with no timeout | Another thread calls `notify()`/`notifyAll()`, the joined thread ends, or `unpark()` → RUNNABLE (or BLOCKED if it must re-acquire a monitor) |
| `TIMED_WAITING` | Like WAITING but with a deadline. | `Thread.sleep(ms)`, `wait(timeout)`, `join(timeout)`, `parkNanos`/`parkUntil` | Timeout expires, or it's signaled/interrupted → RUNNABLE |
| `TERMINATED` | `run()` has finished (returned or threw). The OS thread is gone. | `run()` completes | (final state; cannot be restarted) |

Two subtleties seniors must internalize:

- **`RUNNABLE` in Java lumps together "running on a core right now" and "ready but waiting for a free core."** Java's state model has no separate "RUNNING" state. So a thread shown as RUNNABLE in a thread dump might be actively burning CPU *or* might be sitting in the OS run queue. To tell them apart you must drop below the JVM (e.g., `top -H`, `pidstat -t`) — see §9.
- **A thread blocked in native I/O (e.g., a blocking socket read) usually shows as `RUNNABLE` in Java, not BLOCKED/WAITING.** That's because, from the JVM's perspective, it has called a native method and is "running" it; the JVM doesn't model the kernel's wait. This trips up many engineers reading thread dumps: a thread sitting idle on `socketRead0` appears RUNNABLE. BLOCKED/WAITING/TIMED_WAITING are reserved for *JVM-level* synchronization (monitors, `wait`, `park`, `sleep`, `join`).

```
            start()                 scheduler picks it
   NEW ───────────────► RUNNABLE ◄──────────────────────┐
                          │  ▲   ▲                       │
        sleep/wait/join/  │  │   │ acquire monitor       │
        park (timed)      │  │   └──────────── BLOCKED ◄─┘
                          ▼  │                    ▲ (want synchronized lock)
                  TIMED_WAITING                   │
                          ▲  │ timeout/notify     │
        wait/join/park    │  ▼                    │
        (no timeout) ──► WAITING ─────────────────┘
                          │ notify/unpark/join-done
                          ▼
   run() returns/throws ─────────► TERMINATED
```

### 2.5 The OS scheduler — built up from scratch

The **scheduler** is the part of the kernel that decides, moment to moment, which RUNNABLE thread runs on which CPU core. Imagine you have 8 cores and 2,000 RUNNABLE threads. Only 8 can physically run at any instant. The scheduler's job is to share the 8 cores among the 2,000 *fairly and efficiently*.

**Run queue.** The set of threads ready to run is kept in a **run queue** (on Linux's modern schedulers, a per-core data structure). When a core becomes free, the scheduler picks the "most deserving" runnable thread from a queue and runs it. Per-core queues avoid all cores contending on one global lock; a **load balancer** in the scheduler periodically migrates threads between cores to keep them evenly busy.

**Time slice (quantum).** The scheduler doesn't let a thread run forever. It grants a **time slice** — a maximum interval the thread may run before being interrupted so others get a turn. On Linux's CFS scheduler (the default for years), there isn't a fixed slice; instead each runnable thread accrues "virtual runtime" and CFS runs whichever has the least, targeting fairness over a tunable scheduling period (knobs like `sched_latency_ns`, default historically ~6–24 ms scaled by core count, and `sched_min_granularity_ns`, historically ~0.75–3 ms). On Linux 6.6+ (2023), CFS was replaced by **EEVDF** (Earliest Eligible Virtual Deadline First), which adds latency-sensitivity via a per-task "request latency"; the broad fairness model is similar. The number to remember: **typical effective time slices are on the order of a few milliseconds to a few tens of milliseconds.**

> **Term — CFS (Completely Fair Scheduler).** Linux's default general-purpose scheduler from 2.6.23 (2007) until 6.6. It models an idealized "perfectly fair" CPU and tries to give each runnable thread an equal share, weighted by **nice value** (priority). "Fair" means over a window each thread gets CPU time proportional to its weight. **EEVDF** (Linux 6.6+, 2023) is its successor, adding better tail-latency behavior.

**Preemption.** When the time slice expires (or a higher-priority thread becomes runnable), the kernel forcibly takes the CPU away from the running thread and gives it to another. This forced removal is **preemption**, and it triggers an **involuntary context switch** (the thread didn't ask to yield; it was kicked off). The kernel uses a hardware **timer interrupt** (the "scheduler tick," historically 100/250/1000 Hz set by `CONFIG_HZ`; many modern kernels use a "tickless"/`NO_HZ` mode to avoid waking idle cores) to periodically regain control and make scheduling decisions.

> **Term — interrupt.** A hardware signal that suspends whatever the CPU is doing and jumps to a kernel handler. The **timer interrupt** fires periodically so the kernel always gets control back even from a tight loop; **device interrupts** (a NIC received a packet, a disk finished) wake the kernel to handle I/O completion and possibly wake a waiting thread.

**Priorities and nice values.** Threads can be more or less important. On Linux, normal (non-real-time) threads have a **nice value** from −20 (highest priority, "least nice to others") to +19 (lowest). Higher priority = larger share of CPU and a tendency to preempt lower-priority threads. There are also **real-time scheduling classes** (`SCHED_FIFO`, `SCHED_RR`) with strict priority over normal threads — rarely used by JVM apps. **Java thread priorities** (`Thread.setPriority(1..10)`, default `NORM_PRIORITY = 5`) are *hints* that the JVM may map to OS nice values, but on most platforms (especially Linux) they have **little or no effect** — do not rely on them for correctness or performance.

**Cooperative points and blocking.** A thread also leaves the CPU *voluntarily* when it can't proceed: it calls `sleep()`, waits on a lock, or issues a blocking I/O syscall. The kernel marks it non-runnable (sleeping), removes it from the run queue, and switches to someone else. This is a **voluntary context switch**. Later, when the awaited event happens (timer fires, lock released, data arrived), the kernel marks the thread runnable again and eventually reschedules it.

Putting it together, the scheduler is a loop: *pick the best runnable thread → run it until its slice ends, it blocks, or it's preempted → save its state → repeat.* The "save its state and load the next thread's state" step is the context switch.

---

## 3. How it works internally (the heart of the doc)

This section traces context switching down to the metal, then back up through the JVM. Read it slowly.

### 3.1 What a context switch *is*, precisely

A **context switch** is the act of saving the complete CPU state of the currently running thread and restoring the saved CPU state of another thread, so that execution can resume the second thread exactly where it left off. The "context" is everything that defines a thread's live execution: its registers, its program counter, its stack pointer, its processor status flags, and (if switching to a thread in a *different* process) its memory-mapping context.

There are three flavors, and conflating them causes confusion:

1. **Thread-to-thread switch within the same process.** Cheapest of the real switches. The address space is shared, so the page tables don't change — no TLB flush (defined below). Only CPU registers, PC, SP, and FPU/vector state are swapped.
2. **Thread-to-thread switch across processes.** More expensive. In addition to registers, the **page table base register** (CR3 on x86-64) is reloaded to point at the new process's page tables, which historically flushes the **TLB** (translation cache) — a major hidden cost.
3. **Mode switch (not a true context switch).** Crossing user↔kernel for a syscall or interrupt *without* changing which thread runs. Cheapest. Often mislabeled a "context switch"; it isn't — the same thread continues, it just briefly runs kernel code.

> **Term — TLB (Translation Lookaside Buffer).** A small, fast cache inside the CPU that remembers recent virtual-address→physical-address translations so the CPU doesn't walk the multi-level page tables on every memory access. When you switch to a different process's address space, those cached translations are mostly invalid, so the TLB is flushed (or tagged invalid). The next thousands of memory accesses then suffer slow page-table walks until the TLB refills — this **TLB pollution** is one of the biggest *indirect* costs of context switching.

> **Term — page table.** The per-process data structure (a multi-level tree on x86-64) mapping the process's virtual pages to physical RAM frames. The CPU's memory-management unit (MMU) walks it on a TLB miss. CR3 holds the physical address of the top-level table.

### 3.2 What exactly gets saved and restored

When the kernel preempts thread T1 to run T2, on a typical Linux/x86-64 system the following happens (simplified but faithful):

1. **Entry into the kernel.** A timer interrupt (involuntary) or a syscall (voluntary) transfers control to the kernel. The CPU automatically pushes a minimal frame (return address, flags, stack pointer) onto the kernel stack and switches to kernel mode.
2. **Save T1's user register state.** The interrupt/syscall entry path saves T1's general-purpose registers into T1's kernel stack / its `task_struct`'s thread structure: RAX–R15, RIP (program counter), RSP (stack pointer), RFLAGS (status flags).
3. **Save FPU / SIMD state if needed.** The floating-point and vector registers (XMM/YMM/ZMM — up to 512 bits × 32 registers = 2 KB with AVX-512) are large. Modern kernels save them **lazily** (only if the thread used them) using `XSAVE`/`XRSTOR` instructions, because saving them unconditionally is expensive.
4. **Scheduler picks T2.** `schedule()` runs, consults the run queue (EEVDF/CFS), and selects T2.
5. **Switch kernel stacks and `thread_struct`.** The kernel switches to T2's kernel stack and updates the per-CPU "current task" pointer to T2.
6. **Switch address space *if* T2 is in a different process.** Reload CR3 with T2's page-table root. This is where the TLB cost lands. (If T1 and T2 are in the same process — common for a JVM with many threads — this step is skipped and CR3 is unchanged, so no TLB flush. This is why same-process switches are cheaper.)
7. **Restore T2's register state.** Load T2's saved GP registers, RFLAGS, RSP, and finally RIP — the moment RIP is restored, the CPU is executing T2's code exactly where T2 last stopped.
8. **Return to user mode** and T2 continues.

The kernel function at the center of steps 5–7 on Linux is `context_switch()` → `switch_to()` (architecture-specific assembly) and `switch_mm()` (address space). The whole register-save/restore of step 2/7 is the **direct cost**.

### 3.3 Direct vs indirect cost (with real numbers)

**Direct cost** = the CPU cycles spent in the kernel doing the save/restore/scheduler work itself. On modern x86-64 this is roughly **1–5 microseconds** per switch, often quoted around **1–3 µs** for the bare mechanism. It's measurable and relatively fixed.

**Indirect cost** = the slowdown *after* the switch because the new thread's working set isn't in the CPU caches/TLB anymore. The previous thread polluted:

- **L1/L2/L3 caches** — the new thread's recently-used data and instructions may have been evicted; it now suffers cache misses (an L3 miss → main memory is ~60–100 ns each, and there can be thousands).
- **TLB** — if it was a cross-process switch, address translations must be reloaded.
- **Branch predictors / prefetchers** — CPU speculation state is per-thread-ish and gets confused.

The indirect cost is workload-dependent and often *dominates*: total effective cost is commonly cited as **~1–10 µs**, but for cache-heavy workloads it can be much more. The classic study is Li, Ding & Shen, *"Quantifying the cost of context switch"* (2007), which separates the few-µs direct cost from cache-reload costs that can run into tens of µs. The practical takeaway: **a context switch is cheap in isolation (~µs) but ruinous in aggregate** — at 100,000 switches/sec/core you're spending a meaningful fraction of the core just switching, plus the cache cold-start tax on real work.

> **Why this matters for thread-pool sizing.** If you have far more runnable threads than cores, the scheduler must time-slice among them, multiplying context switches and cache pollution. This is **oversubscription**, and past a point it causes **thrashing** — throughput drops as you add threads because the machine spends its time switching, not computing. See §3.6 and §6.

### 3.4 Voluntary vs involuntary context switches

- **Voluntary context switch (`vcsw`)**: the thread gave up the CPU because it *can't* proceed — it blocked on I/O, a lock, `sleep()`, or called `yield()`. High voluntary switch counts usually indicate **blocking / waiting** (I/O-bound work, lock contention).
- **Involuntary context switch (`ivcsw`)**: the kernel forcibly preempted the thread (its time slice expired, or a higher-priority thread arrived). High involuntary counts usually indicate **CPU contention / oversubscription** — too many CPU-hungry threads fighting for too few cores.

You can read these counters per-thread on Linux from `/proc/<pid>/task/<tid>/status` (`voluntary_ctxt_switches` / `nonvoluntary_ctxt_switches`) and aggregate them with `pidstat -w`. The *ratio* is diagnostic: lots of involuntary switches → reduce thread count or CPU work; lots of voluntary switches → you're I/O/lock bound, look at blocking and contention.

### 3.5 The full lifecycle trace of a Java thread (control + data flow)

Let's trace a platform thread end to end, mapping Java actions to OS events.

1. **`new Thread(runnable)`** — allocates a Java `Thread` object on the heap. State `NEW`. **No OS thread yet.** Nothing scheduled.
2. **`thread.start()`** — the JVM calls into the OS (`pthread_create` → `clone()` with `CLONE_VM | CLONE_FS | CLONE_FILES | …`). The kernel allocates a `task_struct`, a kernel stack, sets up the user stack (size from `-Xss`), and puts the new task on a run queue. State → `RUNNABLE`. The new OS thread will eventually be scheduled and will begin executing the JVM's thread bootstrap, then your `run()`.
3. **Running** — the scheduler picks it; it runs bytecode (interpreted, then JIT-compiled to native code after it's "hot"). It shares the heap with all other threads. State `RUNNABLE` (Java doesn't distinguish "on-CPU" from "ready").
4. **It hits a `synchronized` block whose monitor is held by another thread** — the JVM tries to acquire the **monitor**. If contended, the thread is parked waiting for the monitor; Java state → `BLOCKED`; underneath, a voluntary context switch occurs (the thread is taken off the CPU). When the owner releases, this thread becomes runnable and competes to acquire.
5. **It calls `obj.wait()`** — releases the monitor and suspends until notified; Java state → `WAITING` (or `TIMED_WAITING` with a timeout). Another voluntary switch.
6. **It calls `Thread.sleep(50)`** — a `TIMED_WAITING`; the kernel sets a timer and deschedules it. After ~50 ms (plus scheduling jitter), the timer fires, the thread becomes runnable, and is eventually rescheduled.
7. **It issues a blocking socket read** — calls into native code (`read()` syscall). The kernel finds no data, marks the thread sleeping (voluntary switch), and runs others. When the NIC delivers data (device interrupt), the kernel wakes the thread (runnable again). *In a Java thread dump this thread shows `RUNNABLE`* (it's "in" a native method), even though physically it was sleeping — a key gotcha from §2.4.
8. **`run()` returns** — state → `TERMINATED`; the JVM tears down the native thread; its OS thread and stack are freed. The Java `Thread` object can be garbage-collected once unreferenced. It **cannot** be restarted (`start()` again throws `IllegalThreadStateException`).

Throughout, *every* transition out of "running" (steps 4–7) is a context switch, and every wakeup is a future scheduling decision that may incur another.

### 3.6 CPU-bound vs I/O-bound and how each meets the scheduler

- A **CPU-bound** task spends its time computing (math, parsing, compression). It will gladly use a whole core; it gets preempted (involuntary switches) when its time slice ends. **Optimal thread count ≈ number of cores** (or hardware threads). Adding more CPU-bound threads than cores only adds context-switch overhead and cache pollution — pure loss. Classic formula: `threads ≈ N_cpu` for compute, sometimes `N_cpu + 1` to cover the occasional stall.
- An **I/O-bound** task spends most of its time *waiting* (network, disk, DB). While waiting it's off-CPU, so one core can host many such tasks interleaved. **Optimal thread count > number of cores**, often much larger. The classic sizing formula (Little's Law / Brian Goetz's *Java Concurrency in Practice*):

  ```
  N_threads = N_cpu × U_cpu × (1 + W/C)
  ```

  where `N_cpu` = cores, `U_cpu` = target CPU utilization (0..1), `W` = average wait time per task, `C` = average compute time per task. If a task computes 5 ms and waits 95 ms (`W/C = 19`), and you want ~100% utilization on 8 cores: `8 × 1 × 20 = 160` threads. The intuition: you need enough threads so that while most are waiting, ~8 are always computing.

> **Term — Little's Law.** A queueing-theory result: `L = λ × W`, the average number of items in a system equals arrival rate × average time each spends in the system. For threads: to keep `N_cpu` cores busy when each task spends fraction `p` of its time on CPU, you need ~`N_cpu / p` concurrent tasks in flight.

**Mixed and the pitfall:** real services mix CPU and I/O. Over-sizing a pool for I/O-bound work and then handing it CPU-bound work causes oversubscription and thrashing; under-sizing for I/O leaves cores idle while requests queue. This is the central tuning tension §6 returns to. (Virtual threads dissolve much of this for the I/O-bound, blocking-style case — §7 — but *not* for CPU-bound work, where you still want roughly one carrier per core.)

### 3.7 Why thread creation is expensive (and the pool answer)

Creating a platform thread requires: a syscall (`clone`), kernel allocation of a `task_struct`, allocation and mapping of a stack (default ~512 KB–1 MB virtual, committed lazily), registration in scheduler structures, and JVM bookkeeping. Tearing it down has matching cost. Empirically this is **tens of microseconds to ~1 ms** per thread, plus the steady-state memory (each thread's committed stack + kernel structures). For a server handling 50,000 short tasks/sec, `new Thread()` per task would be catastrophic: creation overhead alone could swamp the actual work, and peak concurrency could exhaust memory.

A **thread pool** solves this by creating a fixed (or bounded) set of worker threads once and feeding them a queue of tasks. Workers loop: take a task, run it, take the next. Creation cost is paid once; switching among ready tasks is just normal scheduling. The pool also gives you **backpressure** (the queue bounds in-flight work) and a throttle on concurrency (bounding context-switch overhead). In Java this is `ExecutorService` / `ThreadPoolExecutor` (§4). The general rule for the platform-thread era: **don't create raw threads per task; submit tasks to a sized pool.** (For virtual threads the calculus changes — you *do* create one per task, but they're not pooled because they're cheap; §7.)

---

## 4. The complete toolkit

This is the working API and tool surface. Defaults are HotSpot/OpenJDK on Linux x86-64 unless noted; verify version-specific values with `java -XX:+PrintFlagsFinal -version`.

### 4.1 Core `java.lang.Thread` API

| Member | Purpose | Key parameters / notes |
|---|---|---|
| `new Thread(Runnable)` / `new Thread(Runnable, name)` | Create a platform thread. | No OS thread until `start()`. Always name your threads. |
| `start()` | Begin execution; spawns the OS thread; → RUNNABLE. | Throws `IllegalThreadStateException` if already started. |
| `run()` | The body. **Calling `run()` directly does NOT start a thread** — it just runs in the current thread (common beginner bug). | Override or pass a `Runnable`. |
| `Thread.sleep(millis[, nanos])` | Sleep current thread; → TIMED_WAITING. | Does **not** release monitors. Throws `InterruptedException`. |
| `join([millis])` | Wait for another thread to finish; → WAITING/TIMED_WAITING. | Returns when target TERMINATED or timeout. |
| `interrupt()` | Set the interrupt flag; wakes a thread blocked in `sleep/wait/join/park` with `InterruptedException` (or sets the flag for I/O / NIO). | The cooperative cancellation mechanism. |
| `isInterrupted()` / `Thread.interrupted()` | Check interrupt flag (instance: non-clearing; static: clears it). | Use to poll for cancellation in loops. |
| `Thread.yield()` | Hint to the scheduler to let others run. | Advisory; often a no-op. Rarely useful in production. |
| `setDaemon(true)` | Mark as **daemon**: JVM exits when only daemon threads remain. | Must be set before `start()`. Background workers are usually daemons. |
| `setPriority(1..10)` | Priority hint (`MIN`=1, `NORM`=5, `MAX`=10). | Largely ignored on Linux. Don't depend on it. |
| `getState()` | Returns the `Thread.State` enum. | For diagnostics; don't busy-poll it for logic. |
| `getId()` / `threadId()` | Unique thread id. | `threadId()` since Java 19 (preferred). |
| `setUncaughtExceptionHandler(...)` | Handle exceptions that escape `run()`. | Without it, the default handler prints to stderr and the thread dies. |
| `Thread.currentThread()` | The running thread. | Common for naming, MDC, interrupt checks. |
| `Thread.ofPlatform()` / `Thread.ofVirtual()` (Java 21+) | Builders for platform vs virtual threads. | `Thread.ofVirtual().start(runnable)` creates a virtual thread. |
| `Thread.startVirtualThread(runnable)` (Java 21+) | Shortcut to start a virtual thread. | M:N scheduled on carriers. |

> **Term — daemon thread.** A thread that does not keep the JVM alive. The JVM exits once all *non-daemon* (user) threads finish, abruptly stopping any remaining daemon threads. Pool worker threads, schedulers, and background reapers are typically daemons so they don't block shutdown.

> **Deprecated and forbidden:** `Thread.stop()`, `suspend()`, `resume()` are deprecated/removed — they could leave shared state corrupted or deadlocked (e.g., `stop()` throws `ThreadDeath` at an arbitrary point, possibly mid-mutation while holding a lock). **Never use them.** Use cooperative interruption instead.

### 4.2 `java.util.concurrent` executors and pools

| Type / factory | Purpose | Key parameters / defaults |
|---|---|---|
| `ExecutorService` | The interface you should target instead of raw `Thread`. | `submit`, `invokeAll`, `shutdown`, `close` (AutoCloseable, Java 19+). |
| `ThreadPoolExecutor` | The flexible, configurable pool. | `corePoolSize`, `maximumPoolSize`, `keepAliveTime`, `workQueue`, `ThreadFactory`, `RejectedExecutionHandler`. |
| `Executors.newFixedThreadPool(n)` | Fixed `n` threads, **unbounded** `LinkedBlockingQueue`. | ⚠ Unbounded queue → OOM risk under overload. Prefer an explicit bounded queue. |
| `Executors.newCachedThreadPool()` | 0 core, `Integer.MAX_VALUE` max, 60 s keep-alive, `SynchronousQueue`. | ⚠ Can spawn unbounded threads under load → thread explosion. |
| `Executors.newSingleThreadExecutor()` | One worker, sequential. | Unbounded queue. |
| `Executors.newScheduledThreadPool(n)` | Delayed/periodic tasks. | Backed by `ScheduledThreadPoolExecutor`. |
| `Executors.newWorkStealingPool()` / `ForkJoinPool` | Work-stealing pool for many small, recursive/parallel tasks. | Default parallelism = available processors. Underlies parallel streams. |
| `Executors.newVirtualThreadPerTaskExecutor()` (Java 21+) | One **virtual** thread per task; not pooled. | Ideal for high-concurrency blocking I/O. |
| `RejectedExecutionHandler` | What to do when the queue is full and max threads reached. | `AbortPolicy` (default, throws), `CallerRunsPolicy` (backpressure), `DiscardPolicy`, `DiscardOldestPolicy`. |

> **Term — `ThreadPoolExecutor` sizing semantics (important).** New tasks first fill up to `corePoolSize` threads; beyond that, tasks **queue**; only when the **queue is full** does the pool grow toward `maximumPoolSize`; if both queue and max are exhausted, the `RejectedExecutionHandler` fires. A subtle consequence: with an *unbounded* queue, `maximumPoolSize` is never reached (the queue never fills) — so "fixed pool with unbounded queue" can pile up memory rather than reject. This is the #1 pool-config footgun.

> **Term — work stealing / `ForkJoinPool`.** A pool where each worker has its own double-ended task queue (deque). A worker pushes/pops sub-tasks from its own end (cache-friendly); when idle, it **steals** from the *other* end of a busy worker's deque. This balances load with minimal contention and is excellent for divide-and-conquer parallelism (parallel streams, `CompletableFuture.supplyAsync` default executor).

### 4.3 Synchronization & coordination primitives (relevant to states/switches)

| Primitive | Purpose | State it induces / notes |
|---|---|---|
| `synchronized` / monitors | Mutual exclusion + visibility. | Contention → `BLOCKED`; `wait()` → `WAITING`. |
| `ReentrantLock` | Explicit lock with `tryLock`, fairness, interruptibility. | `lock()` contention parks via `LockSupport` → `WAITING`. |
| `LockSupport.park()/unpark()` | Low-level blocking primitive under all `j.u.c` locks. | `park()` → WAITING/TIMED_WAITING; doesn't need a monitor. |
| `Condition` (`lock.newCondition()`) | Like `wait/notify` but per-lock, multiple conditions. | `await()` → WAITING. |
| `CountDownLatch`, `CyclicBarrier`, `Semaphore`, `Phaser` | Coordination/throttling. | All can park threads (WAITING/TIMED_WAITING). |
| `BlockingQueue` (e.g., `ArrayBlockingQueue`, `LinkedBlockingQueue`) | Producer/consumer hand-off; the backbone of pools. | `take()`/`put()` block (WAITING/TIMED_WAITING). |
| `volatile` / `AtomicX` / VarHandle | Lock-free visibility and atomicity. | No blocking; no context switch for the operation itself. |

### 4.4 JVM flags affecting threads & switching

| Flag | Effect | Default (verify per version) |
|---|---|---|
| `-Xss<size>` / `-XX:ThreadStackSize=<KB>` | Per-thread stack size. | Platform-specific (~512 KB or 1 MB on Linux x86-64). Smaller → more threads fit but deeper recursion risks `StackOverflowError`. |
| `-XX:+UseBiasedLocking` | (Removed in JDK 15+) optimized uncontended locks. | Gone in modern JDKs. |
| `-Djdk.virtualThreadScheduler.parallelism=<n>` | Carrier-thread count for virtual-thread scheduler. | Defaults to available processors. |
| `-Djdk.virtualThreadScheduler.maxPoolSize=<n>` | Max carriers (for compensation during blocking). | Larger default to handle pinning/blocking. |
| `-XX:ActiveProcessorCount=<n>` | Override the CPU count the JVM sees (crucial in containers!). | Auto-detected; cgroup-aware in modern JDKs. |
| `-XX:+PrintFlagsFinal` | Dump all flags and their resolved values. | Diagnostic. |

> **Term — `availableProcessors()` in containers.** `Runtime.getRuntime().availableProcessors()` drives default pool sizes, GC threads, and the FJ common pool. In Kubernetes/Docker, older JDKs ignored CPU limits and saw the *host's* core count, oversizing pools and causing CPU throttling + excess context switching. Modern JDKs (8u191+, 10+) are **cgroup-aware** and respect CPU quotas; still, verify and consider setting `-XX:ActiveProcessorCount` explicitly.

### 4.5 Linux observability tools for threads & context switches

| Tool / command | What it shows | Key flags |
|---|---|---|
| `vmstat 1` | System-wide context switches/sec (`cs` column), run-queue length (`r`), CPU %. | First row is since-boot average; watch subsequent rows. |
| `pidstat -w -t -p <pid> 1` | Per-**thread** voluntary (`cswch/s`) and involuntary (`nvcswch/s`) switches. | `-t` for per-thread; `-w` for switch stats. |
| `pidstat -u -t -p <pid> 1` | Per-thread CPU usage. | Correlate with thread names. |
| `top -H -p <pid>` | Per-thread CPU%, state. | `-H` = threads; press `H` to toggle. |
| `cat /proc/<pid>/task/<tid>/status` | `voluntary_ctxt_switches`, `nonvoluntary_ctxt_switches`, `State`. | Raw per-thread counters. |
| `perf sched record` / `perf sched latency` | Detailed scheduler tracing: switch latency, run/wait times. | Heavy; use briefly. `perf stat -e context-switches,cpu-migrations` for counts. |
| `perf stat -e cs,migrations,cache-misses` | Hardware/software counters incl. context switches & cache misses. | Quantify direct vs indirect cost. |
| `cat /proc/<pid>/status \| grep Threads` | Thread count of the process. | Quick "thread explosion" check. |
| `ps -eLf` / `ps -T -p <pid>` | List all threads with their native IDs. | Map TID → Java thread. |

### 4.6 JVM/Java diagnostics for threads

| Tool | Purpose | Notes |
|---|---|---|
| `jstack <pid>` / `jcmd <pid> Thread.print` | Thread dump: every Java thread, its state, and stack trace. | The first tool for hangs/deadlocks; auto-detects deadlocks. |
| `kill -3 <pid>` (SIGQUIT) | Make the JVM print a thread dump to stdout. | No external tooling needed. |
| `jcmd <pid> Thread.dump_to_file -format=json <file>` (Java 21+) | Structured dump incl. virtual threads. | Virtual threads don't appear in classic `jstack`. |
| Java Flight Recorder (JFR) + JDK Mission Control | Low-overhead event recording: thread states, lock contention, scheduling, allocations. | `-XX:StartFlightRecording`. Best holistic tool. |
| Async-profiler | Wall-clock & CPU sampling, lock & off-CPU profiling, flame graphs. | Reveals where threads block/contend. |
| `ThreadMXBean` (`java.lang.management`) | Programmatic: deadlock detection, per-thread CPU time, contention monitoring. | `findDeadlockedThreads()`, `getThreadCpuTime()`. |

---

## 5. Code examples by use case

Each example is idiomatic and explained. They span distinct real scenarios, not variations of one.

### 5.1 The wrong vs right way to start a thread, with cooperative cancellation

```java
import java.util.concurrent.TimeUnit;

public class CooperativeCancellation {
    public static void main(String[] args) throws InterruptedException {
        Runnable work = () -> {
            // Poll the interrupt flag so the thread can be cancelled cleanly.
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    doChunk();                 // a unit of work
                    Thread.sleep(100);         // TIMED_WAITING; throws on interrupt
                } catch (InterruptedException e) {
                    // sleep/wait CLEAR the interrupt flag when they throw —
                    // restore it so the loop condition sees the cancellation.
                    Thread.currentThread().interrupt();
                    break;                     // exit promptly
                }
            }
            System.out.println("worker exiting cleanly");
        };

        Thread t = new Thread(work, "worker-1");
        // t.run();  // BUG: runs in main thread, no new thread, no concurrency!
        t.start();    // correct: spawns the OS thread

        TimeUnit.SECONDS.sleep(1);
        t.interrupt();  // request cancellation; do NOT use the removed stop()
        t.join();       // wait for it to finish (WAITING)
    }
    static void doChunk() { /* ... */ }
}
```

Key points: `run()` vs `start()`; interruption is *cooperative* — you must check the flag and/or handle `InterruptedException`; and the idiom of **restoring the interrupt flag** after catching `InterruptedException` (because the throw clears it) so callers higher up can still observe the cancellation.

### 5.2 A correctly configured `ThreadPoolExecutor` with bounded queue and backpressure

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ProductionPool {
    static ThreadPoolExecutor build() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadFactory tf = new ThreadFactory() {
            final AtomicInteger n = new AtomicInteger();
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "io-pool-" + n.incrementAndGet());
                t.setDaemon(true);                 // don't block JVM shutdown
                t.setUncaughtExceptionHandler((th, ex) ->
                    System.err.println("uncaught in " + th.getName() + ": " + ex));
                return t;
            }
        };
        return new ThreadPoolExecutor(
            cores,                                 // corePoolSize
            cores * 4,                             // maximumPoolSize (I/O-bound headroom)
            60, TimeUnit.SECONDS,                  // keepAlive for non-core threads
            new ArrayBlockingQueue<>(1_000),       // BOUNDED queue → real backpressure
            tf,
            new ThreadPoolExecutor.CallerRunsPolicy()); // backpressure: caller runs it
    }

    public static void main(String[] args) {
        ThreadPoolExecutor pool = build();
        for (int i = 0; i < 10_000; i++) {
            final int id = i;
            pool.execute(() -> handle(id));
        }
        pool.shutdown();                            // stop accepting; finish queued
    }
    static void handle(int id) { /* call DB, etc. */ }
}
```

Why this is "production-grade": a **bounded queue** prevents unbounded memory growth (the failure mode of `newFixedThreadPool`); `CallerRunsPolicy` applies **backpressure** — when saturated, the submitting thread runs the task itself, naturally slowing producers instead of dropping work or OOMing; daemon worker threads don't hang shutdown; named threads make thread dumps and `pidstat` readable; an uncaught-exception handler prevents silent task death. Recall the queueing semantics from §4.2: pool grows past `corePoolSize` only when the bounded queue is full.

### 5.3 Sizing for I/O-bound vs CPU-bound with the formula

```java
public final class PoolSizing {
    // CPU-bound: ~one thread per core (sometimes +1 for occasional stalls).
    public static int cpuBound() {
        return Runtime.getRuntime().availableProcessors();
    }

    // I/O-bound: N = cores * targetUtil * (1 + waitTime/computeTime)   [Goetz]
    public static int ioBound(double targetUtil, double waitMs, double computeMs) {
        int cores = Runtime.getRuntime().availableProcessors();
        double ratio = waitMs / computeMs;                 // e.g., 95/5 = 19
        return (int) Math.ceil(cores * targetUtil * (1 + ratio));
    }

    public static void main(String[] args) {
        System.out.println("cpu pool   = " + cpuBound());
        // 8 cores, 100% target, wait 95ms compute 5ms -> 8 * 1 * 20 = 160
        System.out.println("io  pool   = " + ioBound(1.0, 95, 5));
    }
}
```

This makes §3.6 concrete: CPU work wants ≈ `N_cpu` threads (more only adds switching); I/O work wants many more so cores stay busy while most threads wait.

### 5.4 Demonstrating and measuring context-switch overhead (oversubscription)

```java
import java.util.concurrent.*;

/** Same CPU-bound work split across increasing thread counts on a fixed-core box.
 *  Watch throughput plateau then DROP as oversubscription causes thrashing.
 *  Run alongside:  vmstat 1   and   pidstat -w -t -p <pid> 1 */
public class OversubscriptionDemo {
    static volatile long sink;
    static void burn(long iters) {              // pure CPU work
        long x = 0;
        for (long i = 0; i < iters; i++) x += (i * 31 + 7) ^ (i >> 3);
        sink = x;                                // prevent dead-code elimination
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        long totalIters = 4_000_000_000L;
        for (int threads : new int[]{1, cores, cores * 2, cores * 8, cores * 64}) {
            ExecutorService es = Executors.newFixedThreadPool(threads);
            long per = totalIters / threads;
            long t0 = System.nanoTime();
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++)
                es.execute(() -> { burn(per); done.countDown(); });
            done.await();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("threads=%-4d total=%4d ms%n", threads, ms);
            es.shutdownNow();
        }
    }
}
```

Expected shape on an N-core box: time drops from 1→N threads (parallelism helps), is roughly flat N→2N, then **increases** as you go far past N (e.g., 64×N) — that rising time is the cost of involuntary context switches and cache pollution. Correlate the `cs` column in `vmstat` spiking and `nvcswch/s` in `pidstat` climbing.

### 5.5 Detecting a deadlock programmatically (and what a thread dump shows)

```java
import java.lang.management.*;

public class DeadlockDetector {
    static final Object A = new Object(), B = new Object();

    public static void main(String[] args) throws InterruptedException {
        new Thread(() -> { synchronized (A) { sleep(); synchronized (B) {} } }, "T-AB").start();
        new Thread(() -> { synchronized (B) { sleep(); synchronized (A) {} } }, "T-BA").start();

        Thread.sleep(500);
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] deadlocked = bean.findDeadlockedThreads();   // null if none
        if (deadlocked != null) {
            for (ThreadInfo ti : bean.getThreadInfo(deadlocked, true, true)) {
                System.out.println(ti.getThreadName()
                    + " state=" + ti.getThreadState()           // BLOCKED
                    + " waiting on " + ti.getLockName()
                    + " held by " + ti.getLockOwnerName());
            }
        }
    }
    static void sleep() { try { Thread.sleep(100); } catch (InterruptedException e) {} }
}
```

`T-AB` holds A wants B; `T-BA` holds B wants A — a classic lock-ordering deadlock. Both threads are `BLOCKED`. `jstack`/`jcmd Thread.print` would print "Found one Java-level deadlock" and the cycle automatically. Fix: impose a global lock ordering (always acquire A before B) or use `tryLock` with timeout.

### 5.6 Virtual threads for massive blocking I/O concurrency (Java 21+)

```java
import java.util.concurrent.*;
import java.time.Duration;
import java.util.stream.IntStream;

public class VirtualThreadsDemo {
    public static void main(String[] args) throws InterruptedException {
        // One VIRTUAL thread per task. 1,000,000 of these is fine — they are cheap
        // and unmount from their carrier OS thread while "sleeping" (simulated I/O).
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 1_000_000).forEach(i ->
                exec.submit(() -> {
                    Thread.sleep(Duration.ofSeconds(1)); // blocking call: VT unmounts,
                    return i;                              // carrier runs other VTs
                }));
        } // close() waits for all tasks
        System.out.println("done");
    }
}
```

Same code with `newFixedThreadPool` and a million tasks would need a million platform threads (impossible) or would serialize through the pool. With virtual threads, blocking calls (`sleep`, blocking sockets, JDBC on modern drivers) **unmount** the virtual thread from its carrier, so a handful of carriers (≈ cores) serve a million logical threads. This is the payoff of the M:N model — but note: **CPU-bound** virtual threads give no speedup beyond core count (they still need carriers), and `synchronized` blocks or native calls can **pin** a virtual thread to its carrier (§7).

---

## 6. Implementation concerns & best practices

**Performance.**
- **Size pools to the workload, not to "big."** CPU-bound ≈ cores; I/O-bound via the formula in §5.3. Oversubscription buys you context switches, not throughput.
- **Minimize context switches on hot paths.** Reduce lock contention (shrink critical sections, use lock-free `Atomic`/`LongAdder`, partition data), prefer batching over fine-grained hand-offs, and keep producer/consumer queues sized so workers aren't constantly parking/unparking.
- **Pin to caches when it matters.** For latency-critical, CPU-bound work, reducing migrations (a thread bouncing between cores reloads caches) helps — `taskset`/CPU affinity, or fewer-than-cores threads pinned. Measure `cpu-migrations` via `perf stat`.
- **Mind container CPU limits.** Verify `availableProcessors()` reflects the cgroup quota; otherwise GC and pools oversize and you get CPU throttling plus extra switching.

**Correctness / concurrency.**
- **Always prefer `java.util.concurrent` over raw `wait/notify`** — `BlockingQueue`, `ConcurrentHashMap`, `ReentrantLock`, `Semaphore` are correct, tested, and clearer.
- **Establish a global lock ordering** to prevent deadlocks; or use `tryLock(timeout)` and back off.
- **Handle `InterruptedException` properly** — never swallow it silently; either propagate or restore the flag (`Thread.currentThread().interrupt()`).
- **Publish shared state safely** — use `volatile`, `final`, atomics, or locks; the Java Memory Model does not guarantee one thread sees another's writes without a happens-before edge.

**Memory.**
- Each platform thread costs its stack (~512 KB–1 MB committed lazily) plus kernel structures. Thousands of threads = gigabytes. Tune `-Xss` down if you have many shallow-stack threads (watch for `StackOverflowError`).
- Beware **thread-local leaks**: `ThreadLocal`s in pooled threads outlive tasks and can pin large objects/classloaders (a classic container/redeploy memory leak). Always `remove()` in a `finally`.

**Security.**
- Pooled threads carry context (`ThreadLocal`s, security/MDC context) across tasks unless cleared — a **data-leak/authz risk** (request A's identity reused for request B). Reset context between tasks.
- Don't expose unbounded thread/queue creation to untrusted input — a request flood becomes a resource-exhaustion DoS.

**Observability.**
- Name every thread (`io-pool-3`, not `Thread-47`) so dumps and `pidstat` are intelligible.
- Track per-pool metrics: active count, queue depth, completed tasks, rejections (Micrometer's `ExecutorServiceMetrics`).
- Capture thread dumps on hang (`jstack`/`kill -3`), and use JFR/async-profiler for off-CPU and lock-contention analysis.

**Cost.**
- Context switches and migrations are pure overhead — in cloud terms, CPU you pay for but don't compute with. Right-sizing pools directly reduces cost.

**Testing.**
- Concurrency bugs are nondeterministic. Use stress tests (`jcstress` for memory-model/atomicity tests), inject delays to widen races, and run with `-XX:+UnlockDiagnosticVMOptions` style tooling. Test cancellation paths and pool saturation/rejection behavior explicitly.

**Production hardening.**
- Bounded queues + a deliberate `RejectedExecutionHandler` (usually `CallerRunsPolicy` for backpressure).
- Graceful shutdown: `shutdown()` then `awaitTermination(timeout)` then `shutdownNow()`.
- Separate pools by workload type (don't share one pool between fast and slow tasks — a slow task starves the fast ones; this is **head-of-line blocking**).

**Anti-patterns to avoid.**
- `new Thread()` per task (creation cost, no bound). 
- `newFixedThreadPool`/`newSingleThreadExecutor` with their **unbounded queues** in latency/memory-sensitive services.
- `newCachedThreadPool` for unbounded inbound load (thread explosion).
- Relying on `Thread.setPriority` for behavior (ignored on Linux).
- Using `Thread.stop/suspend/resume` (corruption/deadlock).
- Busy-waiting (`while(!flag){}`) instead of proper blocking — burns a core and causes pointless switches.
- One giant shared pool for everything (head-of-line blocking).

---

## 7. Advanced topics & deep internals

### 7.1 Virtual threads (Project Loom) in depth

A **virtual thread** is a `java.lang.Thread` whose execution is managed by the JVM, not the kernel. The JVM runs a small **`ForkJoinPool`** of **carrier threads** (platform threads, default count = `availableProcessors()`). A virtual thread **mounts** onto a carrier to run; when it would block on a JDK-instrumented blocking operation (`sleep`, socket I/O, `BlockingQueue`, `lock`/`park`, file I/O via the right APIs), the JVM **unmounts** it — saving its **continuation** (its stack, kept on the heap) — and frees the carrier to run another virtual thread. When the blocking operation completes, the virtual thread becomes runnable and is re-mounted (possibly on a different carrier). This is the **M:N** model: M virtual threads multiplexed onto N carriers.

> **Term — continuation.** A first-class, suspendable snapshot of a computation's execution state (its stack). Loom's virtual threads are built on a delimited-continuation mechanism: unmount = capture the continuation onto the heap; mount = resume it on a carrier. Because stacks live on the heap and grow on demand, virtual threads start tiny (hundreds of bytes) and you can have millions.

**Pinning.** A virtual thread that *cannot* unmount stays glued ("pinned") to its carrier, defeating the model. Classic causes: executing inside a `synchronized` block/method during a blocking call (the monitor is tied to the carrier), or calling **native** code (JNI) that blocks. While pinned, that carrier is unavailable to others; if many VTs pin simultaneously you can starve carriers. Mitigations: replace `synchronized` with `ReentrantLock` on hot blocking paths; detect with `-Djdk.tracePinnedThreads=full` (JDK 21) or JFR `jdk.VirtualThreadPinned` events. (JDK 24 / JEP 491 substantially reduced pinning from `synchronized`, but native-call pinning remains — flag the version when you discuss this.)

**When virtual threads help / don't.** They shine for high-concurrency, **blocking, I/O-bound** "thread-per-request" code — you get the simplicity of blocking code with the scalability of async. They do **not** speed up CPU-bound work (still bounded by carriers ≈ cores) and don't replace careful design for CPU parallelism. They are **not pooled** — create one per task; pooling them is an anti-pattern.

### 7.2 Tuning knobs and lesser-known behavior

- **Carrier parallelism**: `-Djdk.virtualThreadScheduler.parallelism` (default = cores) and `maxPoolSize` (allows temporary extra carriers to *compensate* when a VT does a blocking operation that the JVM can't unmount cleanly, like certain file ops).
- **Scheduler tunables (Linux)**: `kernel.sched_latency_ns`, `sched_min_granularity_ns`, `sched_wakeup_granularity_ns` (CFS-era; reduced/renamed under EEVDF). Lower min-granularity → more preemption (lower latency, more switches); higher → fewer switches (better throughput). Real-time-ish workloads may use `SCHED_FIFO`/`SCHED_RR` or `isolcpus`/`nohz_full` to dedicate cores.
- **CPU affinity & NUMA**: pinning threads (`taskset`, `numactl`) avoids migrations and keeps memory local on **NUMA** (Non-Uniform Memory Access) systems where remote-socket RAM is slower; relevant for latency-critical JVM services on large servers.
- **Spin-then-park** locks: `j.u.c` locks and the JVM briefly **spin** (busy-wait a few iterations) before parking, betting the lock frees quickly and avoiding a context switch entirely. Under light contention this is a net win; under heavy contention spinning wastes CPU. The JIT and `LockSupport` handle this; you mostly observe its effects.
- **Thread-local handshakes / safepoints**: HotSpot occasionally needs all threads at a **safepoint** (a known-safe execution point) for GC, deoptimization, or stack walks. Threads are paused there — a JVM-level "stop" that isn't an OS context switch per se but can show up as latency (a "stop-the-world" pause). Per-thread handshakes (JDK 10+) reduce global pauses.

### 7.3 Edge cases

- **Spurious wakeups**: `wait()`/`await()`/`park()` can return without a corresponding signal. **Always wait in a loop checking a condition**, never an `if`.
- **Lost wakeups**: a `notify()` that happens before the consumer `wait()`s is lost. Use a state flag checked under the same lock, or `j.u.c` constructs that handle this.
- **Priority inversion**: a high-priority thread waits on a lock held by a low-priority thread that can't get CPU. Rare on Linux/JVM (priorities barely apply) but real on RT systems; solved by priority inheritance.
- **Thundering herd**: `notifyAll()` wakes everyone, who then contend and mostly re-block — many wasted switches. Prefer `notify()` / `Condition` with targeted signaling where correct.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Concurrency model comparison

| Model | Threads per task | Blocking I/O cost | CPU-bound fit | Code complexity | Best for |
|---|---|---|---|---|---|
| Thread-per-request, platform threads | 1 OS thread each | High (each blocked op holds an OS thread) | Good (≈ cores) | Low (simple blocking code) | Moderate concurrency, simple servers |
| Bounded thread pool + blocking | shared N threads | Pool serializes; sized by formula | Good (N≈cores) | Low–medium | The classic server default pre-Loom |
| Async / non-blocking (NIO, Netty, reactive) | few event-loop threads | Very low (no thread blocked while waiting) | Needs care (don't block the loop) | High (callback/reactive complexity) | Massive I/O concurrency, pre-Loom |
| Virtual threads (Loom) | 1 virtual thread each | Very low (unmounts on block) | Bounded by carriers (≈ cores) | Low (looks like blocking) | Massive I/O concurrency, post-JDK 21 |
| Work-stealing (`ForkJoinPool`) | recursive sub-tasks | N/A (compute) | Excellent | Medium | Divide-and-conquer CPU parallelism, parallel streams |

### 8.2 Decision rules

- **Use platform thread pools** when: you're pre-Java 21, your concurrency is moderate, and tasks are mostly CPU-bound or modestly I/O-bound. Size with §5.3.
- **Use virtual threads** when: Java 21+, high concurrency, **blocking I/O-bound** thread-per-request style, and you can avoid pinning (`synchronized`/JNI on blocking paths). One per task; no pooling.
- **Use async/reactive** when: you must support pre-21 at extreme I/O concurrency, or you need fine-grained streaming/backpressure (Reactor/RxJava). Accept the complexity cost; *avoid* it if virtual threads suffice.
- **Use `ForkJoinPool`/parallel streams** when: CPU-bound, recursively decomposable work on a multicore box.
- **Avoid** more threads than cores for pure CPU work; **avoid** unbounded queues/pools under untrusted load; **avoid** sharing one pool across fast and slow tasks.

### 8.3 Pool factory cheat comparison

| Factory | Threads | Queue | Risk |
|---|---|---|---|
| `newFixedThreadPool(n)` | n fixed | unbounded | OOM under overload |
| `newCachedThreadPool()` | 0..MAX | SynchronousQueue (no buffer) | thread explosion |
| `newSingleThreadExecutor()` | 1 | unbounded | OOM; serial bottleneck |
| `new ThreadPoolExecutor(...)` (explicit) | configurable | **bounded (your choice)** | safest; you control everything |
| `newVirtualThreadPerTaskExecutor()` | virtual, unbounded-cheap | none | pinning if `synchronized`/native blocks |

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → tool

| Symptom | Likely cause | Diagnose with |
|---|---|---|
| High CPU, low throughput | Oversubscription/thrashing: too many runnable threads | `vmstat 1` (`cs` high, `r` ≫ cores), `pidstat -w -t` (high `nvcswch/s`), `perf stat -e cs,migrations` |
| App hangs, threads idle | Deadlock | `jstack`/`jcmd Thread.print` (auto-detects), `ThreadMXBean.findDeadlockedThreads()` |
| Latency spikes, CPU not maxed | Lock contention (threads BLOCKED/parking) | thread dump showing many BLOCKED on one monitor; async-profiler lock profiling; high `cswch/s` |
| Threads stuck RUNNABLE on `socketRead0` | Slow/hung downstream (network I/O), not a JVM lock | thread dump (RUNNABLE in native read), `ss`/`netstat`, downstream metrics |
| OOM / huge memory | Unbounded queue or thread explosion | heap dump, `cat /proc/<pid>/status \| grep Threads`, pool queue-depth metric |
| Pinned virtual threads, poor VT scaling | `synchronized`/JNI on blocking path | `-Djdk.tracePinnedThreads=full`, JFR `jdk.VirtualThreadPinned` |
| CPU throttling in k8s, excess switching | `availableProcessors()` > cgroup quota → oversized pools | check `-XX:ActiveProcessorCount`, cgroup CPU stats |

### 9.2 Step-by-step: diagnosing a "added more threads, got slower" regression

1. `vmstat 1` — if `cs` (context switches/sec) is very high and `r` (run-queue length) ≫ core count while CPU is ~100% in **system** time, you're thrashing.
2. `pidstat -w -t -p <pid> 1` — find which threads have high `nvcswch/s` (involuntary → CPU contention) vs `cswch/s` (voluntary → blocking).
3. `perf stat -e context-switches,cpu-migrations,cache-misses -p <pid> sleep 5` — quantify switches, migrations, and the cache-miss tax (indirect cost).
4. Reduce pool size toward `N_cpu` for CPU-bound work; re-measure. Throughput should recover as switching drops.

### 9.3 Real-world incident patterns

- **The unbounded-queue OOM.** A service used `Executors.newFixedThreadPool(50)`. A downstream slowdown caused tasks to arrive faster than they completed; the *unbounded* `LinkedBlockingQueue` grew until the JVM OOM-killed. Fix: bounded queue + `CallerRunsPolicy`. (This is the canonical reason to never use the default fixed pool in production.)
- **The container CPU surprise.** A JVM on a 2-vCPU k8s pod (running on a 64-core node) on an old JDK saw 64 processors, created a 64-thread GC and oversized FJ pool, then got CPU-throttled by the cgroup — massive involuntary switches and latency. Fix: upgrade JDK (cgroup-aware) or set `-XX:ActiveProcessorCount=2`.
- **The `synchronized` pinning trap.** A team migrated to virtual threads but a hot path did blocking JDBC inside a `synchronized` cache method; virtual threads pinned carriers and scaling collapsed to ~core count. Fix: switch to `ReentrantLock`/concurrent map; verify with `jdk.tracePinnedThreads`.
- **The shared-pool head-of-line block.** Fast health-check tasks shared a pool with slow report-generation tasks; a report storm filled the pool and queue, and health checks timed out, triggering false unhealthy/restarts. Fix: separate pools per workload class.

---

## 10. Interview drill

**Q1. What is a thread, and how does a JVM (platform) thread relate to an OS thread?**
*Model answer:* A thread is the smallest schedulable unit of execution, sharing its process's address space (heap, file descriptors) but with its own program counter, registers, and stack. A platform `java.lang.Thread` maps **1:1** to an OS thread: `start()` triggers `pthread_create`/`clone()`, and the OS scheduler — not the JVM — decides when and where it runs.
- *Follow-up: Why does 1:1 make threads "expensive"?* Each carries a real OS thread with a ~512 KB–1 MB stack, creation is a syscall, and blocking it blocks the OS thread — limiting you to thousands, not millions, hence thread pools.
- *Follow-up: How do virtual threads differ?* M:N — many virtual threads multiplexed onto a few carrier OS threads; they unmount on blocking, so they're cheap and you can have millions.
- *Follow-up: Does the JVM schedule platform threads?* No; the kernel scheduler does. The JVM only schedules *virtual* threads onto carriers.

**Q2. Enumerate the Java thread states and the transitions between them.**
*Model answer:* NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED. `start()`→RUNNABLE; contending for a monitor→BLOCKED; `wait/join/park` (no timeout)→WAITING; with timeout or `sleep`→TIMED_WAITING; `run()` ends→TERMINATED.
- *Follow-up: A thread blocked on a socket read shows what state?* RUNNABLE — it's in a native method; the JVM doesn't model the kernel wait. This surprises people reading dumps.
- *Follow-up: Does RUNNABLE mean "on a CPU right now"?* No — Java doesn't separate "running" from "ready"; both are RUNNABLE. Use `top -H`/`pidstat` to tell apart.
- *Follow-up: BLOCKED vs WAITING?* BLOCKED = waiting for a *monitor lock*; WAITING = waiting for a *signal* (`wait/join/park`).

**Q3. Explain a context switch — what's saved, and what does it cost?**
*Model answer:* Saving the running thread's CPU state (GP registers, PC, stack pointer, flags, lazily the FPU/vector state) and restoring another's so it resumes exactly where it left off. **Direct cost** ≈ 1–5 µs (the save/restore + scheduler). **Indirect cost** ≈ cache/TLB pollution making subsequent work slower; effective total often ~1–10 µs, more for cache-heavy workloads.
- *Follow-up: Same-process vs cross-process switch?* Same-process skips reloading page tables (CR3), so **no TLB flush** — cheaper. Cross-process reloads CR3 and historically flushes the TLB.
- *Follow-up: Voluntary vs involuntary?* Voluntary = thread blocked/yielded (I/O, lock); involuntary = kernel preempted it (slice expired, higher-priority arrived). High involuntary ⇒ CPU contention; high voluntary ⇒ blocking/contention.
- *Follow-up: How do you measure switches?* `vmstat` (`cs`), `pidstat -w -t` (per-thread vol/invol), `perf stat -e context-switches`.

**Q4. How does the OS scheduler decide what runs? Explain time slices and preemption.**
*Model answer:* The scheduler keeps runnable threads in per-core run queues and picks the most-deserving one. Each runs for a time slice (a few ms to tens of ms; on Linux CFS/EEVDF it's fairness-driven via virtual runtime, not a fixed quantum). A timer interrupt lets the kernel preempt when the slice ends or a higher-priority thread appears.
- *Follow-up: Do Java thread priorities work?* Largely not on Linux — they're hints mapped weakly (or not) to nice values; don't rely on them.
- *Follow-up: What's a nice value?* −20..+19 priority for normal Linux threads; lower = higher priority/larger CPU share.

**Q5 (senior-signal). How do you size a thread pool, and why does adding threads sometimes reduce throughput?**
*Model answer:* CPU-bound ≈ `N_cpu` threads; I/O-bound via `N = N_cpu × U × (1 + W/C)`. Beyond the useful count you get **oversubscription**: more runnable threads than cores force time-slicing, multiplying context switches and cache pollution — **thrashing** — so throughput *drops* even as you add threads.
- *Follow-up: How would you confirm thrashing in prod?* `vmstat` `cs` high + `r` ≫ cores, `pidstat` high `nvcswch/s`, `perf stat` cache-misses up; reduce pool size and re-measure.
- *Follow-up: Does this change with virtual threads?* For I/O-bound, yes — you stop sizing pools and create one VT per task. For CPU-bound, no — you're still bounded by carriers ≈ cores.

**Q6 (senior-signal). When would you choose virtual threads over reactive/async, and what are the risks?**
*Model answer:* Choose virtual threads (Java 21+) for high-concurrency **blocking I/O** thread-per-request code — same simplicity as blocking, scalability near async, far less complexity than reactive. Risks: **pinning** (`synchronized`/JNI on blocking paths) defeating the model; no benefit for CPU-bound work; mistakenly pooling them. Reactive still wins where you need fine-grained streaming/backpressure or must support pre-21.
- *Follow-up: How do you detect pinning?* `-Djdk.tracePinnedThreads=full` or JFR `jdk.VirtualThreadPinned`.
- *Follow-up: How do you fix it?* Replace `synchronized` with `ReentrantLock` on hot blocking paths; isolate native calls.

**Q7 (senior-signal). Your service got slower after a deploy that increased the pool size, on a 2-vCPU container. Diagnose.**
*Model answer:* Probable oversubscription/CPU throttling. Check `availableProcessors()` vs the cgroup quota (old JDK may see host cores), inspect `vmstat`/`pidstat` for high involuntary switches, and `perf stat` for migrations/cache-misses. Likely fix: set `-XX:ActiveProcessorCount=2` (or upgrade to a cgroup-aware JDK) and size the pool to the real core budget.
- *Follow-up: Why does seeing host cores hurt?* GC threads, the FJ common pool, and your pool default to host core count, oversubscribing 2 vCPUs and triggering cgroup CPU throttling and excess switching.

**Q8. Why are thread pools used instead of creating a thread per task?**
*Model answer:* Thread creation is a syscall plus stack allocation and scheduler registration (tens of µs to ~1 ms), and each platform thread costs memory; per-task creation under load wastes CPU and risks resource exhaustion. Pools amortize creation, bound concurrency (limiting context-switch overhead), and provide a queue for backpressure.
- *Follow-up: What's wrong with `newFixedThreadPool`?* Its unbounded queue can grow to OOM under overload; prefer an explicit bounded queue + a rejection policy.
- *Follow-up: And `newCachedThreadPool`?* Unbounded thread creation under load → thread explosion.

**Q9. How does interruption work, and why is `Thread.stop()` forbidden?**
*Model answer:* Interruption is **cooperative**: `interrupt()` sets a flag (and wakes `sleep/wait/join/park` with `InterruptedException`); code must check the flag or handle the exception to cancel cleanly. `Thread.stop()` is forbidden because it throws `ThreadDeath` at an arbitrary point — possibly mid-mutation while holding a lock — corrupting shared state or deadlocking.
- *Follow-up: What should you do when catching `InterruptedException` and not exiting immediately?* Restore the flag with `Thread.currentThread().interrupt()` so callers can observe it.

**Q10. Walk through a deadlock: how to detect and prevent it.**
*Model answer:* Deadlock = a cycle of threads each holding a lock the next needs (BLOCKED forever). Detect with `jstack`/`jcmd Thread.print` (auto-reports the cycle) or `ThreadMXBean.findDeadlockedThreads()`. Prevent with a global lock-acquisition ordering, `tryLock(timeout)` with back-off, or reducing lock scope/using lock-free structures.
- *Follow-up: How does lock ordering prevent it?* If every thread always acquires locks in the same global order, no cyclic wait can form (breaks one of Coffman's four conditions).

**Q11 (deep). What is pinning in virtual threads and exactly why does `synchronized` cause it?**
*Model answer:* Pinning is when a virtual thread can't unmount from its carrier during a blocking op, so the carrier is stuck. Historically `synchronized` pins because the monitor ownership is tied to the carrier OS thread, so the JVM can't safely move the VT off it while inside the block. Result: blocked VTs hold carriers, starving others. Mitigate with `ReentrantLock`; later JDKs (24/JEP 491) reduced `synchronized` pinning.

**Q12. What's the difference between concurrency and parallelism, and how do threads relate?**
*Model answer:* Concurrency is *structuring* a program to handle many tasks that overlap in time (even on one core via switching); parallelism is *executing* multiple tasks literally simultaneously (needs multiple cores). Threads enable concurrency on any machine and parallelism on multicore machines; context switching creates the illusion of concurrency on a single core.

---

## 11. Glossary

- **Address space (virtual)** — a process's private, isolated view of memory, mapped to physical RAM by page tables.
- **Affinity (CPU)** — pinning a thread to specific core(s) to reduce migrations and keep caches warm.
- **AVX/SIMD** — wide vector instructions/registers (XMM/YMM/ZMM) for parallel math; their large state is saved lazily on switches.
- **Backpressure** — slowing producers when consumers/queues are saturated (e.g., `CallerRunsPolicy`).
- **BLOCKED** — Java state: waiting to acquire a monitor lock.
- **Carrier thread** — a platform thread that runs (carries) virtual threads in the M:N model.
- **CFS / EEVDF** — Linux's fair schedulers (CFS pre-6.6; EEVDF 6.6+).
- **cgroup** — Linux control group limiting a process's CPU/memory; basis of container limits.
- **Context switch** — saving one thread's CPU state and restoring another's.
- **Continuation** — a suspendable snapshot of a computation's stack; the basis of virtual threads.
- **Coffman conditions** — the four conditions (mutual exclusion, hold-and-wait, no preemption, circular wait) all required for deadlock; breaking any one prevents it.
- **CPU-bound** — work limited by computation; wants ≈ `N_cpu` threads.
- **CR3** — x86-64 register holding the current page-table root; reloaded on cross-process switches.
- **Daemon thread** — a thread that doesn't keep the JVM alive.
- **Deadlock** — a cyclic wait where threads block each other forever.
- **Direct cost (of a switch)** — CPU cycles for the save/restore/scheduler work (~1–5 µs).
- **ForkJoinPool / work stealing** — pool where idle workers steal tasks from busy ones' deques.
- **Heap** — shared memory region for dynamically allocated objects.
- **HotSpot** — the standard OpenJDK/Oracle JVM implementation.
- **Indirect cost (of a switch)** — slowdown from cache/TLB pollution after the switch.
- **Interrupt** — hardware signal that diverts the CPU into a kernel handler (e.g., timer, device).
- **Interruption (Java)** — cooperative cancellation via the interrupt flag and `InterruptedException`.
- **I/O-bound** — work limited by waiting on I/O; wants many threads (formula in §5.3).
- **Involuntary context switch** — kernel-forced preemption.
- **Kernel** — the privileged core of the OS managing hardware and scheduling.
- **Kernel mode / user mode** — CPU privilege levels (ring 0 vs ring 3).
- **Little's Law** — `L = λ × W`; relates concurrency, arrival rate, and latency.
- **LockSupport park/unpark** — low-level blocking primitive under `j.u.c` locks.
- **M:N model** — many virtual threads scheduled onto N carrier OS threads.
- **Mode switch** — user↔kernel transition without changing which thread runs (cheaper than a context switch).
- **Monitor** — the intrinsic lock behind `synchronized`.
- **NEW / RUNNABLE / TERMINATED / TIMED_WAITING / WAITING** — Java thread states (see §2.4).
- **nice value** — Linux normal-thread priority (−20..+19).
- **NUMA** — Non-Uniform Memory Access; remote-socket RAM is slower than local.
- **Oversubscription** — more runnable threads than cores.
- **Page table / TLB** — virtual→physical address mapping structure / its CPU cache.
- **Pinning** — a virtual thread stuck on its carrier (can't unmount), e.g., inside `synchronized`/JNI.
- **Platform thread** — a Java thread backed 1:1 by an OS thread.
- **Preemption** — forcibly taking the CPU from a running thread.
- **Priority inversion** — a high-priority thread blocked by a low-priority lock holder.
- **Process** — a running program instance with its own address space.
- **Program counter (PC)/instruction pointer** — register holding the next instruction's address.
- **Quantum / time slice** — max interval a thread runs before potential preemption.
- **Register** — fastest CPU storage; the live state of a thread.
- **RejectedExecutionHandler** — pool policy when queue+max are exhausted.
- **Run queue** — kernel structure of runnable threads awaiting a core.
- **Safepoint** — a JVM-known-safe point where threads pause for GC/etc.
- **Scheduler** — kernel component choosing which thread runs where/when.
- **Spurious wakeup** — a wait returning without a signal; wait in a loop.
- **Stack** — per-thread memory for call frames and locals.
- **Syscall** — request from user code into the kernel.
- **Thread** — smallest schedulable unit of execution within a process.
- **ThreadLocal** — per-thread storage; risky in pools (leaks/cross-task bleed).
- **ThreadPoolExecutor** — Java's configurable pool.
- **Thrashing** — throughput collapse from excessive switching/contention.
- **Time slice** — see quantum.
- **TLB pollution** — invalidated address-translation cache entries after a switch.
- **Virtual thread** — JVM-scheduled lightweight thread (Loom, Java 21+).
- **volatile / Atomic** — lock-free visibility/atomicity primitives.
- **Voluntary context switch** — a thread yielding the CPU because it can't proceed.
- **Work stealing** — see ForkJoinPool.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**States:** NEW → RUNNABLE → {BLOCKED (monitor) | WAITING (wait/join/park) | TIMED_WAITING (sleep/timed)} → RUNNABLE → TERMINATED. *Native I/O shows RUNNABLE. Java has no separate RUNNING state.*

**Mapping:** platform thread = 1 OS thread (1:1, expensive). Virtual thread = M:N on carriers (cheap, unmounts on block; CPU-bound still bounded by carriers ≈ cores).

**Context switch cost:** direct ~1–5 µs (register/stack save/restore + scheduler); indirect = cache/TLB pollution; effective ~1–10 µs+. Same-process = no TLB flush; cross-process = CR3 reload + TLB flush. Voluntary = blocked/yielded; involuntary = preempted.

**Scheduler:** per-core run queues, time slices (~ms–tens of ms), timer-interrupt preemption, CFS/EEVDF fairness. Java priorities ≈ ignored on Linux.

**Pool sizing:** CPU-bound ≈ `N_cpu`; I/O-bound `N = N_cpu × U × (1 + W/C)`. More threads than useful ⇒ oversubscription ⇒ thrashing ⇒ throughput drops.

**Pools:** prefer explicit `ThreadPoolExecutor` + **bounded queue** + `CallerRunsPolicy`. Avoid `newFixedThreadPool`/`newSingleThreadExecutor` (unbounded queue → OOM) and `newCachedThreadPool` (thread explosion) under untrusted load. Java 21+ blocking I/O ⇒ `newVirtualThreadPerTaskExecutor` (one VT/task, no pool).

**Diagnose:** `vmstat 1` (`cs`,`r`), `pidstat -w -t` (vol/invol), `perf stat -e context-switches,cpu-migrations,cache-misses`, `top -H`, `jstack`/`jcmd Thread.print` (deadlocks), `-Djdk.tracePinnedThreads=full` (VT pinning). Containers: verify `availableProcessors()` vs cgroup; `-XX:ActiveProcessorCount`.

**Anti-patterns:** `new Thread()` per task; unbounded queues/pools under load; relying on `setPriority`; `Thread.stop/suspend/resume`; busy-waiting; one shared pool for fast+slow tasks; `synchronized` on virtual-thread blocking paths (pinning); leaking `ThreadLocal`s in pools.

### 12.2 Self-test (no answers — recall actively)

1. A thread dump shows a thread RUNNABLE with `socketRead0` at the top of its stack. Is it consuming CPU? What's actually happening, and what tool tells you whether it's on-CPU?
2. Derive the I/O-bound pool size for 16 cores at 80% target utilization where each task computes 2 ms and waits 60 ms. Then explain what changes if you switch the same workload to virtual threads.
3. You observe `vmstat` `cs` climbing to 400,000/s and `r` at 120 on a 16-core box, with CPU pegged in system time. What's happening, how do you confirm per-thread, and what's the fix?
4. Explain precisely what gets saved/restored in a same-process context switch versus a cross-process one, and why one is cheaper.
5. Why does `synchronized` cause virtual-thread pinning, how do you detect it, and what's the remediation? Name the JDK behavior change that affects this.
6. Your service uses `Executors.newFixedThreadPool(100)` and OOMed during a downstream outage even though only 100 threads ran. Explain the mechanism and give the corrected configuration.
7. Distinguish voluntary from involuntary context switches, say which counter dominates for a lock-contended workload versus a CPU-oversubscribed one, and name the command that shows both per thread.

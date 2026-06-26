# Executors & Thread Pools

> A definitive engineering-handbook chapter for senior JVM backend developers. From first principles to deep internals, tuning, debugging, and interview mastery.

---

## 1. Overview & where it fits

### What it is

An **executor** is an abstraction that *decouples task submission from task execution*. Instead of writing `new Thread(runnable).start()` — which welds together *what* you want done with *how and where* it runs — you hand a unit of work (a `Runnable` or `Callable`) to an `Executor`, and the executor decides which thread runs it, when, and according to what policy.

A **thread pool** is the dominant implementation of that abstraction: a managed set of reusable worker threads plus a queue of pending tasks. Worker threads are created once and kept alive to run many tasks, amortizing the cost of thread creation and bounding the degree of concurrency.

### The problem it solves

Creating a thread is expensive and unbounded thread creation is dangerous:

- **Cost of creation.** Each Java thread maps 1:1 to an OS thread (a *platform thread*; more on virtual threads later). Creating one involves a syscall, allocating a thread stack (default ~512 KB–1 MB of reserved virtual memory on HotSpot), registering it with the OS scheduler, and JVM bookkeeping. Tens of microseconds to a millisecond each — fine occasionally, ruinous in a hot loop.
  - *Syscall (system call):* a request from your user-space program into the operating-system kernel to do something privileged, like creating a thread or doing I/O. Syscalls cross a protection boundary and are comparatively slow.
- **Unbounded concurrency = resource exhaustion.** "One thread per request" under load means 50,000 requests → 50,000 threads → tens of gigabytes of stack memory reserved, brutal context-switching overhead, and eventually `OutOfMemoryError: unable to create new native thread`. The system collapses precisely when you need it most.
  - *Context switch:* the OS saving the CPU registers/state of one thread and loading another's so a single core can time-share among many threads. Each switch costs ~1–5 microseconds plus cache-pollution effects; thousands of runnable threads on a few cores means the CPU spends its time switching instead of working.
- **No backpressure or control.** Raw threads give you no way to limit in-flight work, to queue overflow, to prioritize, or to shut down cleanly.

Thread pools solve all three: reuse threads (amortize creation), bound the number of threads (cap memory and context-switch overhead), and provide a queue + rejection policy (backpressure) plus lifecycle management (clean shutdown).

### When you reach for it

- Any server handling many short-to-medium tasks (request handlers, message consumers, batch item processing).
- Parallelizing CPU-bound work across cores (use `ForkJoinPool` / parallel streams or a sized pool).
- Scheduling deferred or periodic work (`ScheduledExecutorService` instead of `java.util.Timer`).
- Fan-out/fan-in: submit N independent subtasks, collect N futures, join.

You **avoid** a custom pool when a higher-level framework already manages execution for you (e.g., a servlet container's request thread pool, Netty's event loops, an Akka/Pekko dispatcher, Kafka consumer threads). Layering your own pool inside those can double-count concurrency and cause subtle starvation.

### One-paragraph mental model

> A `ThreadPoolExecutor` is a **bounded producer/consumer system**. Producers call `execute`/`submit`, dropping tasks into a hand-off point. A fixed band of *core* worker threads consume tasks forever. When all core threads are busy, new tasks go into a **work queue**. Only when the queue is *full* does the pool spin up *extra* threads, up to a *maximum*. When even the max is busy and the queue is full, the **rejection policy** fires. Extra (non-core) threads that sit idle longer than `keepAliveTime` are reaped. Everything about a pool's behavior under load is determined by the interaction of four knobs — `corePoolSize`, `maximumPoolSize`, the **queue**, and the **rejection handler** — and most production incidents come from getting that interaction wrong.

---

## 2. Foundations from first principles

### 2.1 Tasks: `Runnable` vs `Callable`

A *task* is a unit of work you hand to an executor.

- **`Runnable`** — `void run()`. Returns nothing, cannot throw a checked exception. The classic task type.
- **`Callable<V>`** — `V call() throws Exception`. Returns a value and may throw checked exceptions. Preferred when you need a result or want exceptions propagated.

```java
Runnable r = () -> System.out.println("side effect only");
Callable<Integer> c = () -> 41 + 1; // returns 42, may throw
```

### 2.2 `Future<V>` — a handle to a not-yet-computed result

When you `submit` a task you get back a **`Future<V>`**: a placeholder for a result that will be available later.

- `V get()` — blocks until the task completes, then returns the result (or throws `ExecutionException` wrapping the task's exception).
- `V get(long timeout, TimeUnit unit)` — blocks up to a bound, then throws `TimeoutException`.
- `boolean cancel(boolean mayInterruptIfRunning)` — attempts cancellation; if the task hasn't started it won't run; if running and `true`, the worker thread is *interrupted*.
- `isDone()`, `isCancelled()` — status checks.

```java
Future<Integer> f = pool.submit(() -> expensive());
// ... do other work ...
Integer result = f.get(2, TimeUnit.SECONDS); // bounded wait
```

A subtle but critical point: **a `Future` is also how exceptions reach you.** If a task submitted via `submit` throws, the exception is captured and re-thrown from `get()`. If you never call `get()`, that exception is **silently swallowed**. (Tasks submitted via `execute()` instead propagate to the thread's uncaught-exception handler. This asymmetry is a top-tier source of "lost exceptions" bugs — covered in §6.)

### 2.3 The `Executor` interface — the minimal contract

```java
public interface Executor {
    void execute(Runnable command);
}
```

That's the whole interface: "run this command, somehow." It says nothing about *when*, *on which thread*, or *whether sequentially or concurrently*. A conforming `Executor` could run the task inline on the caller's thread, in a new thread, or in a pool. This minimalism is the point — it's the seam between "what to run" and "the execution policy."

### 2.4 `ExecutorService` — lifecycle + result-bearing submission

`ExecutorService` extends `Executor` and adds the machinery you actually need:

- **Result-bearing submission:** `submit(Callable)`, `submit(Runnable)`, `submit(Runnable, T result)`.
- **Bulk operations:** `invokeAll(collection)` (run all, block until all done), `invokeAny(collection)` (run all, return the first successful result, cancel the rest).
- **Lifecycle:** `shutdown()`, `shutdownNow()`, `isShutdown()`, `isTerminated()`, `awaitTermination(timeout, unit)`.

### 2.5 `ScheduledExecutorService` — deferred & periodic execution

Adds time-based scheduling:

- `schedule(task, delay, unit)` — run once after a delay.
- `scheduleAtFixedRate(task, initialDelay, period, unit)` — run repeatedly; the *next start* is `period` after the *previous start* (rate-based).
- `scheduleWithFixedDelay(task, initialDelay, delay, unit)` — run repeatedly; the *next start* is `delay` after the *previous finish* (gap-based).

These return `ScheduledFuture`. This is the modern replacement for the legacy `java.util.Timer`/`TimerTask`, which used a *single* thread (one slow task delays all others) and let an uncaught exception kill the timer thread entirely.

### 2.6 The interface/class hierarchy at a glance

```
Executor (execute)
  └─ ExecutorService (submit, invokeAll/Any, shutdown...)
       ├─ ScheduledExecutorService (schedule, scheduleAtFixedRate...)
       └─ AbstractExecutorService (skeletal impl: builds submit() on execute())
            ├─ ThreadPoolExecutor  ───────────────► the workhorse
            │    └─ ScheduledThreadPoolExecutor ──► implements ScheduledExecutorService
            └─ ForkJoinPool ─────────────────────► work-stealing pool
```

`Executors` (note the **s**) is a separate **factory class** of static methods (`newFixedThreadPool`, `newCachedThreadPool`, etc.) that construct pre-configured `ThreadPoolExecutor`/`ScheduledThreadPoolExecutor`/`ForkJoinPool` instances. *Don't confuse `Executor` (interface) with `Executors` (factory).*

### 2.7 Work queues: `BlockingQueue<Runnable>`

A thread pool's queue is a **`BlockingQueue`**: a queue whose `take()` blocks when empty (so idle workers park efficiently) and whose `put()` can block when full (used in some flows). Worker threads loop forever calling `take()`; producers call `offer()`. The *choice* of queue implementation is one of the most behavior-defining decisions you make — detailed in §3 and §4.

### 2.8 Interruption — the cooperative cancellation protocol

Java does not forcibly kill threads (the deprecated `Thread.stop()` was unsafe — it could leave objects in inconsistent states with locks held). Instead it uses **cooperative interruption**:

- `thread.interrupt()` sets a boolean *interrupt flag* on the target thread.
- Blocking methods that are *interruptible* (`Object.wait`, `Thread.sleep`, `BlockingQueue.take`, `Future.get`, `Lock.lockInterruptibly`, most NIO ops) notice the flag, throw `InterruptedException`, and **clear the flag**.
- Plain CPU-bound code must *poll* `Thread.currentThread().isInterrupted()` and decide to stop.

Thread pools rely on this: `shutdownNow()` interrupts workers, and tasks that ignore interruption cannot be stopped. The correct idiom on catching `InterruptedException` is to either propagate it or **restore the flag** (`Thread.currentThread().interrupt()`) so callers up the stack can see it.

---

## 3. How it works internally — `ThreadPoolExecutor` in depth

This is the heart of the chapter. Everything else is a configuration of this machine.

### 3.1 The four (plus three) constructor knobs

```java
public ThreadPoolExecutor(
    int corePoolSize,                       // threads kept alive even when idle*
    int maximumPoolSize,                    // hard ceiling on worker threads
    long keepAliveTime,                     // idle timeout for "extra" threads
    TimeUnit unit,                          // unit for keepAliveTime
    BlockingQueue<Runnable> workQueue,      // where tasks wait
    ThreadFactory threadFactory,            // how worker threads are created
    RejectedExecutionHandler handler)       // what to do when saturated
```

- **`corePoolSize`** — the number of threads the pool tries to keep running, even when idle (unless `allowCoreThreadTimeOut(true)` is set, in which case idle core threads are also reaped after `keepAliveTime`).
- **`maximumPoolSize`** — the absolute maximum number of worker threads. Threads beyond `corePoolSize` are "extra"/transient.
- **`keepAliveTime` + `unit`** — how long an idle *extra* thread waits for new work before terminating.
- **`workQueue`** — the `BlockingQueue<Runnable>` holding submitted-but-not-yet-running tasks.
- **`threadFactory`** — creates worker threads; lets you name them, set daemon status, priority, and an uncaught-exception handler. **Always provide one.**
- **`handler`** — the `RejectedExecutionHandler` invoked when the pool can neither queue nor run a task.

### 3.2 THE handshake: new thread vs. queue vs. reject (the part everyone gets wrong)

When you call `execute(task)`, `ThreadPoolExecutor` runs this **exact decision sequence**:

1. **If the current number of running threads < `corePoolSize`** → start a **new core thread** for this task (even if other threads are idle? No — only if running < core; the check is on count, not idleness). The task runs immediately on the new thread.
2. **Else (core is full): try to enqueue the task** with `workQueue.offer(task)`. If the offer **succeeds**, the task waits in the queue. *(A second sanity recheck happens to handle a thread dying or the pool shutting down between steps, but the steady-state behavior is "queue it.")*
3. **Else (the queue rejected the offer — i.e., the queue is full): try to start a new thread up to `maximumPoolSize`.** If running threads < max, create an **extra thread** to run this task directly.
4. **Else (queue full AND at max threads): reject** — invoke the `RejectedExecutionHandler`.

**The counterintuitive consequence:** the pool **prefers queueing over creating new threads.** Extra threads (beyond core) are created **only when the queue is full**, *not* when core threads are merely busy. This is the single most misunderstood behavior of `ThreadPoolExecutor`, and it has enormous practical fallout:

> **If you use an unbounded queue (e.g., `new LinkedBlockingQueue<>()` with default `Integer.MAX_VALUE` capacity), step 2 *always* succeeds. The queue is never "full," so steps 3–4 never happen — `maximumPoolSize` is effectively ignored and the pool never grows past `corePoolSize`.**

This is exactly why `Executors.newFixedThreadPool(n)` behaves the way it does (core = max = n, unbounded queue), and why `newCachedThreadPool` behaves so differently (core = 0, max = `Integer.MAX_VALUE`, `SynchronousQueue` — which is *always* "full" because it has zero capacity, so step 2 always fails and step 3 always tries to make a thread). See §3.6.

#### A concrete trace

Pool: `core=2, max=4, queue=ArrayBlockingQueue(capacity=2)`. Tasks arrive one at a time, none finishing yet:

| Task | Running threads | Queue size | What happens |
|------|-----------------|-----------|--------------|
| T1 | 0 → 1 | 0 | running < core → new core thread #1 |
| T2 | 1 → 2 | 0 | running < core → new core thread #2 |
| T3 | 2 | 0 → 1 | core full → offer succeeds → queued |
| T4 | 2 | 1 → 2 | core full → offer succeeds → queued |
| T5 | 2 → 3 | 2 | queue full → running < max → extra thread #3 |
| T6 | 3 → 4 | 2 | queue full → running < max → extra thread #4 |
| T7 | 4 | 2 | queue full → at max → **REJECTED** |

Notice the saturation order: **fill core → fill queue → grow to max → reject.** Total capacity before rejection = `maximumPoolSize + queueCapacity` (here 4 + 2 = 6 in-flight; the 7th is rejected).

### 3.3 The internal state machine

`ThreadPoolExecutor` packs **two pieces of state into one `AtomicInteger`** called `ctl`:

- The high 3 bits encode the **run state**.
- The low 29 bits encode the **worker count** (hence the historical ~500M thread cap).

This packing lets the pool read/CAS both atomically. (*CAS = Compare-And-Swap: an atomic CPU instruction that updates a value only if it still equals an expected value; the basis of lock-free concurrency.*)

The five run states and their transitions:

| State | Meaning | Accepts new tasks? | Processes queued tasks? |
|-------|---------|--------------------|--------------------------|
| `RUNNING` | normal | yes | yes |
| `SHUTDOWN` | `shutdown()` called | no (rejects) | yes (drains queue) |
| `STOP` | `shutdownNow()` called | no | no (queue abandoned; workers interrupted) |
| `TIDYING` | all tasks done, workers = 0 | — | — |
| `TERMINATED` | `terminated()` hook ran | — | — |

Transitions:
- `RUNNING → SHUTDOWN` on `shutdown()`.
- `RUNNING/SHUTDOWN → STOP` on `shutdownNow()`.
- `SHUTDOWN → TIDYING` when queue empty and worker count 0.
- `STOP → TIDYING` when worker count 0.
- `TIDYING → TERMINATED` after the `terminated()` hook completes; this is what unblocks `awaitTermination()`.

### 3.4 The `Worker` — why it's its own lock

Each worker thread is a `Worker` object that itself extends `AbstractQueuedSynchronizer` (AQS) and acts as a non-reentrant lock.

- *AQS (AbstractQueuedSynchronizer):* the framework underlying most `java.util.concurrent` locks/latches/semaphores; provides a queue of waiting threads and an atomic state integer for building synchronizers.

Why is a worker a lock? To distinguish "this worker is idle (interruptible for shutdown)" from "this worker is mid-task (don't interrupt it just for a routine `shutdown()`)." When a worker picks up a task it `lock()`s; when idle in `getTask()` it's unlocked. `shutdown()` only interrupts *idle* workers (`interruptIdleWorkers()` tries `tryLock` and only interrupts those it can lock — i.e., not running a task). `shutdownNow()` interrupts *all* workers regardless. This is the mechanism behind "shutdown lets running tasks finish; shutdownNow tries to interrupt them."

### 3.5 The worker run loop (`runWorker` + `getTask`)

Each worker executes, in pseudocode:

```text
runWorker(w):
    task = w.firstTask          // the task it was created to run, if any
    w.firstTask = null
    w.unlock()                  // allow interrupts while idle
    while (task != null OR (task = getTask()) != null):
        w.lock()                // mark "busy" — protect from shutdown's idle-interrupt
        if (pool stopping) ensure this thread is interrupted
        try:
            beforeExecute(thread, task)   // hook
            try { task.run(); }           // <-- your code runs here
            finally { afterExecute(task, thrown); }  // hook
        finally:
            task = null
            w.completedTasks++
            w.unlock()
    processWorkerExit(w, completedAbruptly)
```

`getTask()` is where blocking, idle-timeout, and shrinking happen:

```text
getTask():
    loop:
        check run state — if SHUTDOWN+empty queue or STOP → decrement count, return null (worker exits)
        timed = allowCoreThreadTimeOut OR (workerCount > corePoolSize)
        if (workerCount > max OR (timed AND timedOut)) AND can decrement:
            return null     // this worker should die (shrink)
        try:
            r = timed ? workQueue.poll(keepAliveTime, unit)   // bounded wait → may time out
                      : workQueue.take()                       // unbounded wait (core threads)
            if (r != null) return r
            timedOut = true   // poll timed out → loop → likely exit
        catch (InterruptedException):
            timedOut = false  // retry (interruption is from shutdown or set-core-size change)
```

**Key insights from the loop:**
- **Core threads block forever** on `take()` (unless `allowCoreThreadTimeOut`), so the pool never shrinks below core.
- **Extra threads use `poll(keepAlive)`**; if they time out idle, `getTask()` returns null and the worker exits — this is how the pool shrinks back toward core.
- **Exceptions thrown by your task** propagate out of `task.run()`. They are *not* caught by the loop's main `try` (the `finally` runs `afterExecute` with the throwable, then re-throws). This sets `completedAbruptly = true`, the worker terminates, and `processWorkerExit` **replaces** it with a fresh worker so the pool maintains its thread count. The exception itself goes to the thread's `UncaughtExceptionHandler` (for `execute`) or is captured into the `Future` (for `submit`).

### 3.6 How `Executors` factory methods configure the machine

Now the factories make sense as specific parameterizations:

| Factory | core | max | keepAlive | Queue | Net behavior |
|---------|------|-----|-----------|-------|--------------|
| `newFixedThreadPool(n)` | n | n | 0 | `LinkedBlockingQueue` (unbounded) | Exactly n threads forever; **queue grows without bound** under overload → OOM risk. |
| `newSingleThreadExecutor()` | 1 | 1 | 0 | `LinkedBlockingQueue` (unbounded) | Serial execution; same unbounded-queue risk. Wrapped so you can't cast & resize. |
| `newCachedThreadPool()` | 0 | `MAX_VALUE` | 60s | `SynchronousQueue` (capacity 0) | Every task either reuses an idle thread or spawns a new one; **threads unbounded** under burst → thread/OOM risk. |
| `newScheduledThreadPool(n)` | n | `MAX_VALUE` | 0 | `DelayedWorkQueue` | Timer replacement; uses a delay-priority queue (see §4.4). |
| `newWorkStealingPool([p])` | — | — | — | — | A `ForkJoinPool` with parallelism = available processors (or `p`); work-stealing (see §3.7). |
| `newVirtualThreadPerTaskExecutor()` (JDK 21+) | — | — | — | — | Not a pool — one new **virtual thread** per task (see §7.7). |

The two dangerous ones:
- **`newFixedThreadPool` / `newSingleThreadExecutor`:** unbounded `LinkedBlockingQueue`. Tasks never get rejected; they pile up in memory. A traffic spike → millions of queued tasks → heap exhaustion (`OutOfMemoryError`), and latency for any individual task balloons because of the enormous backlog. No backpressure.
- **`newCachedThreadPool`:** `SynchronousQueue` (zero capacity, so offer always fails → always tries to make a thread) + `max = Integer.MAX_VALUE`. A burst of slow/blocking tasks → effectively unbounded thread creation → context-switch storm and `unable to create new native thread`. No concurrency cap.

**Therefore: prefer constructing `ThreadPoolExecutor` directly with a bounded queue and an explicit rejection policy.** (This is also Google's *Java Style* / `ListeningExecutorService` guidance and the basis of static-analysis rules that flag `Executors.newFixedThreadPool`/`newCachedThreadPool` in production code.)

### 3.7 `ForkJoinPool` and work-stealing

`ForkJoinPool` is a different beast, optimized for **recursive divide-and-conquer** ("fork" subtasks, "join" their results) and many small tasks.

- **Per-worker deques.** Instead of one shared queue, each worker thread owns a double-ended queue (deque) of tasks.
  - *Deque:* a queue you can push/pop from both ends.
- **Work-stealing.** A worker pushes/pops its own subtasks LIFO from the *head* of its deque (great cache locality — recently forked work is hot). When its own deque is empty, it **steals** from the *tail* of another worker's deque (FIFO from that end), minimizing contention because the owner and thief touch opposite ends. This keeps all cores busy with minimal central contention — the defining advantage over a single shared queue, which becomes a hotspot at high core counts.
- **The common pool.** There is a JVM-wide `ForkJoinPool.commonPool()` used by parallel streams (`list.parallelStream()`), `CompletableFuture`'s default async methods, and `Arrays.parallelSort`. Its parallelism defaults to `Runtime.getRuntime().availableProcessors() - 1` (so a 16-core box → parallelism 15), tunable via `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N`.
- **Managed blocking.** Because FJP sizes itself to cores assuming tasks are CPU-bound and non-blocking, doing blocking I/O in FJP tasks can starve the pool. `ForkJoinPool.ManagedBlocker` lets the pool *compensate* by temporarily spawning an extra worker while one is blocked.

`RecursiveTask<V>` (returns a value) and `RecursiveAction` (no value) are the task types; you call `fork()` to schedule asynchronously and `join()` to await/merge.

---

## 4. The complete toolkit

### 4.1 Core interfaces & methods

| Type | Method | Purpose | Notes / defaults |
|------|--------|---------|------------------|
| `Executor` | `execute(Runnable)` | fire-and-forget run | exceptions → uncaught handler |
| `ExecutorService` | `submit(Callable/Runnable)` | run, get `Future` | exceptions captured in `Future` |
| | `invokeAll(coll)` | run all, block till all done | returns `List<Future>`; overload with timeout |
| | `invokeAll(coll, t, unit)` | same, bounded | unfinished tasks cancelled on timeout |
| | `invokeAny(coll)` | first successful result | cancels the rest; throws if all fail |
| | `shutdown()` | stop accepting; drain queue | non-blocking; idempotent |
| | `shutdownNow()` | stop now; interrupt; abandon queue | returns `List<Runnable>` not-yet-run |
| | `awaitTermination(t, unit)` | block until terminated/timeout | returns `boolean`; call after shutdown |
| | `isShutdown()` / `isTerminated()` | status | terminated ⇒ shutdown |
| | `close()` (JDK 19+) | AutoCloseable: shutdown + await forever | enables try-with-resources |
| `ScheduledExecutorService` | `schedule(task, d, unit)` | one-shot delayed | returns `ScheduledFuture` |
| | `scheduleAtFixedRate(...)` | periodic by start time | overlapping if task > period? No — runs serialize but "catch up" can bunch |
| | `scheduleWithFixedDelay(...)` | periodic by gap after finish | safer for variable-duration tasks |

### 4.2 `ThreadPoolExecutor` operational/introspection methods

| Method | Purpose | Default |
|--------|---------|---------|
| `setCorePoolSize(int)` / `getCorePoolSize()` | resize core at runtime | — |
| `setMaximumPoolSize(int)` / `getMaximumPoolSize()` | resize max at runtime | — |
| `setKeepAliveTime(t, unit)` | idle timeout | from constructor |
| `allowCoreThreadTimeOut(boolean)` | let idle core threads die | `false` |
| `prestartCoreThread()` / `prestartAllCoreThreads()` | warm up before traffic | lazily started otherwise |
| `getPoolSize()` | current worker count | — |
| `getActiveCount()` | workers running a task (approx) | — |
| `getLargestPoolSize()` | high-water mark | — |
| `getTaskCount()` | total scheduled (approx) | — |
| `getCompletedTaskCount()` | completed (approx) | — |
| `getQueue()` | the live `BlockingQueue` (for monitoring; don't mutate) | — |
| `remove(Runnable)` / `purge()` | remove a queued task / sweep cancelled futures | — |
| `setRejectedExecutionHandler(...)` / `setThreadFactory(...)` | swap policy/factory | — |
| `beforeExecute` / `afterExecute` / `terminated` | protected hooks for instrumentation | no-ops |

### 4.3 Work queue choices and how they change behavior

| Queue | Capacity | Effect on the handshake | Use when |
|-------|----------|--------------------------|----------|
| `SynchronousQueue` | 0 (direct hand-off) | Every offer fails unless a worker is *immediately* waiting → forces step 3 (new thread) → pool grows aggressively to `max`. With `max = MAX_VALUE` = `newCachedThreadPool`. | You want maximum responsiveness and will cap concurrency via `maximumPoolSize`; tasks are short. |
| `LinkedBlockingQueue` (unbounded) | `MAX_VALUE` | Offer always succeeds → pool never grows past core; never rejects → backlog can OOM. | Almost never in production unbounded. Bounded (`new LinkedBlockingQueue<>(cap)`) is fine. |
| `LinkedBlockingQueue(cap)` | bounded | Queue fills, then pool grows to max, then rejects. Two-lock (head/tail) design → high throughput under contention. | General-purpose bounded buffering. |
| `ArrayBlockingQueue(cap)` | bounded (fixed array) | Same staged behavior; single lock; lower memory overhead, optional fairness. | Predictable, tight memory; smaller queues. |
| `PriorityBlockingQueue` | unbounded | Tasks dequeued by priority (Comparator), not FIFO; unbounded → same OOM caveat. | Prioritized work; wrap tasks with priority. |
| `DelayQueue` / `DelayedWorkQueue` | unbounded | Elements become available only after their delay; used internally by the scheduler. | Scheduling. |

**Rule of thumb:** *bounded queue + sane `max` + explicit rejection policy* = a pool with real backpressure. Unbounded queue = a pool that trades latency and memory for never saying "no" (until it dies).

### 4.4 Rejection policies (`RejectedExecutionHandler`)

When saturated (queue full + at max threads, or pool shut down):

| Policy | Behavior | Tradeoff |
|--------|----------|----------|
| `AbortPolicy` (**default**) | throws `RejectedExecutionException` | Caller must handle; explicit failure/backpressure signal. |
| `CallerRunsPolicy` | runs the task **on the caller's thread** | Natural throttle — the submitting thread is busy so it stops submitting; great for backpressure, but stalls the producer and can block an event loop. |
| `DiscardPolicy` | silently drops the task | Data loss, no signal. Use only for truly droppable work (e.g., metrics). |
| `DiscardOldestPolicy` | drops the *head* of the queue, retries the new task | Drops oldest pending work; surprising semantics; avoid unless intentional. |
| custom | your `rejectedExecution(Runnable, ThreadPoolExecutor)` | e.g., block with `queue.put()` for hard backpressure, log+metric, route to overflow. |

A common custom handler is **blocking put** (turn rejection into backpressure): `(r, exec) -> { try { exec.getQueue().put(r); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }`. Use with care — it blocks the producer and, combined with the wrong queue, can deadlock.

### 4.5 `ThreadFactory` — always set one

```java
import java.util.concurrent.atomic.AtomicInteger;

ThreadFactory namedFactory(String prefix) {
    AtomicInteger n = new AtomicInteger(1);
    return r -> {
        Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
        t.setDaemon(false);   // non-daemon: JVM waits for it (usually what you want for pools you shut down)
        t.setUncaughtExceptionHandler((th, ex) ->
            System.err.println("Uncaught in " + th.getName() + ": " + ex));
        return t;
    };
}
```

Why it matters: **thread names show up in every thread dump, stack trace, and profiler.** A pool of `pool-3-thread-7` threads is unidentifiable at 3 a.m.; `payment-callback-7` tells you instantly which subsystem is wedged. Guava's `ThreadFactoryBuilder` does this cleanly:

```java
ThreadFactory f = new ThreadFactoryBuilder()
    .setNameFormat("payment-callback-%d")
    .setDaemon(false)
    .setUncaughtExceptionHandler((t, e) -> log.error("uncaught", e))
    .build();
```

### 4.6 `CompletableFuture` — composition on top of executors

`CompletableFuture<T>` is the modern async-composition API. Each method has a sync variant (runs in the completing thread), an `…Async` variant (runs in `commonPool()`), and an `…Async(fn, executor)` variant (runs in *your* executor — **prefer this** so you control the pool).

```java
CompletableFuture
    .supplyAsync(() -> fetchUser(id), ioPool)         // run on YOUR pool
    .thenApplyAsync(User::enrich, cpuPool)            // transform on a CPU pool
    .thenCompose(u -> supplyAsync(() -> loadOrders(u), ioPool))
    .exceptionally(ex -> fallback(ex))
    .orTimeout(2, TimeUnit.SECONDS);                  // JDK 9+
```

Always pass an explicit executor to `…Async` methods; relying on the common pool couples unrelated workloads and is a frequent source of starvation.

---

## 5. Code examples by use case

### 5.1 The canonical production pool (bounded, named, instrumented)

```java
import java.util.concurrent.*;

public final class Pools {
    public static ThreadPoolExecutor boundedPool(
            String name, int core, int max, int queueCapacity) {
        ThreadFactory tf = new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger n =
                new java.util.concurrent.atomic.AtomicInteger(1);
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + n.getAndIncrement());
                t.setDaemon(false);
                return t;
            }
        };
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
            core, max,
            60L, TimeUnit.SECONDS,                       // reap idle extras after 60s
            new ArrayBlockingQueue<>(queueCapacity),     // bounded → backpressure
            tf,
            new ThreadPoolExecutor.CallerRunsPolicy());  // throttle the producer on overload
        ex.allowCoreThreadTimeOut(true);                 // let it shrink fully when idle (optional)
        return ex;
    }
}
```

Why these choices: bounded queue + `CallerRunsPolicy` gives natural backpressure (the submitter is forced to run the work and thus slows down), named threads aid debugging, and idle reaping reclaims memory in quiet periods.

### 5.2 Fan-out / fan-in with timeouts and partial results

```java
List<Callable<Quote>> calls = vendors.stream()
    .map(v -> (Callable<Quote>) () -> v.getQuote(req))
    .toList();

// invokeAll with a deadline: unfinished are cancelled
List<Future<Quote>> futures = pool.invokeAll(calls, 800, TimeUnit.MILLISECONDS);

List<Quote> quotes = new ArrayList<>();
for (Future<Quote> f : futures) {
    if (f.isCancelled()) continue;                 // timed out vendor — skip
    try { quotes.add(f.get()); }
    catch (ExecutionException e) { log.warn("vendor failed", e.getCause()); }
}
// proceed with whatever quotes arrived within the SLA
```

### 5.3 `invokeAny` — race to first success (e.g., redundant replicas)

```java
List<Callable<byte[]>> reads = replicas.stream()
    .map(r -> (Callable<byte[]>) () -> r.read(key))
    .toList();
byte[] data = pool.invokeAny(reads, 500, TimeUnit.MILLISECONDS); // first replica wins; others cancelled
```

### 5.4 Scheduled work: fixed-rate vs fixed-delay (and why it matters)

```java
ScheduledExecutorService sched = Executors.newScheduledThreadPool(2, namedFactory("sched"));

// Heartbeat every 5s measured from each START — beware drift/bunching if task > period
sched.scheduleAtFixedRate(this::heartbeat, 0, 5, TimeUnit.SECONDS);

// Cleanup: wait 10s AFTER each finish — self-throttling for variable-duration jobs
sched.scheduleWithFixedDelay(this::cleanup, 10, 10, TimeUnit.SECONDS);
```

Critical caveat: if a `scheduleAtFixedRate` task throws an **uncaught exception**, that schedule is **silently cancelled forever** (the returned `ScheduledFuture` is marked failed; subsequent runs never happen). Always wrap the body in try/catch:

```java
sched.scheduleAtFixedRate(() -> {
    try { heartbeat(); }
    catch (Throwable t) { log.error("heartbeat failed; keeping schedule alive", t); }
}, 0, 5, TimeUnit.SECONDS);
```

### 5.5 Graceful shutdown done correctly (the JCiP idiom)

```java
static void shutdownGracefully(ExecutorService pool, Duration grace) {
    pool.shutdown();                       // stop accepting; let queued tasks run
    try {
        if (!pool.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            pool.shutdownNow();            // interrupt running tasks, abandon queue
            if (!pool.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                log.error("pool did not terminate");
            }
        }
    } catch (InterruptedException e) {
        pool.shutdownNow();
        Thread.currentThread().interrupt(); // restore the flag
    }
}
```

JDK 19+ lets you write `try (ExecutorService pool = Executors.newFixedThreadPool(8)) { ... }` — `close()` does `shutdown()` then waits indefinitely. Convenient for batch jobs; in long-running servers prefer the explicit two-phase idiom with a bounded grace period.

### 5.6 `ForkJoinPool` divide-and-conquer

```java
class SumTask extends RecursiveTask<Long> {
    private static final int THRESHOLD = 10_000;
    private final long[] a; private final int lo, hi;
    SumTask(long[] a, int lo, int hi){ this.a=a; this.lo=lo; this.hi=hi; }
    protected Long compute() {
        if (hi - lo <= THRESHOLD) {            // small enough → compute directly
            long s = 0; for (int i = lo; i < hi; i++) s += a[i]; return s;
        }
        int mid = (lo + hi) >>> 1;
        SumTask left = new SumTask(a, lo, mid);
        left.fork();                            // schedule async on the pool
        SumTask right = new SumTask(a, mid, hi);
        long r = right.compute();               // compute one half on this thread
        long l = left.join();                   // join the forked half
        return l + r;
    }
}
long total = new ForkJoinPool().invoke(new SumTask(arr, 0, arr.length));
```

Idiomatic pattern: **fork one subtask, compute the other inline, then join** — avoids a thread sitting idle while waiting and improves locality.

### 5.7 Bounded blocking-backpressure submission helper

```java
// Turn rejection into a bounded wait so producers slow down without dropping work.
RejectedExecutionHandler blockingPut = (r, exec) -> {
    if (exec.isShutdown()) throw new RejectedExecutionException("pool shut down");
    try { exec.getQueue().put(r); }           // blocks producer until space frees
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RejectedExecutionException(e); }
};
```

### 5.8 Per-task context propagation (MDC / trace IDs)

Pools reuse threads, so thread-local context (logging MDC, security context, trace IDs) leaks across tasks unless you propagate explicitly:

```java
Runnable withContext(Runnable task) {
    Map<String,String> ctx = MDC.getCopyOfContextMap();          // capture at submit time
    return () -> {
        Map<String,String> prev = MDC.getCopyOfContextMap();
        if (ctx != null) MDC.setContextMap(ctx); else MDC.clear();
        try { task.run(); }
        finally { if (prev != null) MDC.setContextMap(prev); else MDC.clear(); }  // restore
    };
}
pool.execute(withContext(() -> log.info("has the right trace id")));
```

---

## 6. Implementation concerns & best practices

### 6.1 Pool sizing — the actual formulas

**CPU-bound tasks** (pure computation, little/no blocking): the optimal pool size is roughly the number of cores. The classic guidance is **N+1**, where N = `Runtime.getRuntime().availableProcessors()`. The "+1" keeps a thread ready to use a core that would otherwise idle during an occasional page fault or brief stall. More threads than cores just adds context-switching overhead with no throughput gain.

**I/O-bound tasks** (waiting on network/disk/DB most of the time): you want *more* threads than cores so that while some threads block on I/O, others use the CPU. Use the **Brian Goetz / Little's Law** formula from *Java Concurrency in Practice*:

```
threads = N_cpu × U_cpu × (1 + W/C)
```
- `N_cpu` = number of cores.
- `U_cpu` = target CPU utilization (0..1) — e.g., 0.8 to leave headroom.
- `W/C` = ratio of **wait time** to **compute time** per task.

Example: 8 cores, target 80% utilization, each task spends 90 ms waiting and 10 ms computing → `W/C = 9`. Threads ≈ `8 × 0.8 × (1 + 9) = 64`. (Intuition: if a task is 90% waiting, ~10 threads keep one core's worth of compute busy, scaled by cores and target utilization.)

*Little's Law connection:* in a queueing system, **L = λ × W** (average number in system = arrival rate × average time in system). The pool-sizing formula is the same idea — to sustain throughput λ where each task occupies a thread for time W, you need ~λ·W threads concurrently. Measure W (latency) and λ (throughput) in production rather than guessing.

**Practical caveats:** these are *starting points*. Real systems mix CPU and I/O, have downstream connection-pool limits (a 200-thread pool talking to a 20-connection DB pool just queues at the DB), and have memory limits (each thread ≈ stack size of reserved memory). **Always validate with load tests and metrics**, and size the pool to the *narrowest* downstream bottleneck (often the DB connection pool, not the CPU).

### 6.2 Correctness & concurrency

- **Don't share mutable state across tasks without synchronization.** Pool threads run concurrently; the same hazards (races, visibility) apply. Prefer immutable task inputs and confined state.
- **Restore the interrupt flag** when you catch `InterruptedException` and don't rethrow it. Swallowing it breaks `shutdownNow()` and cancellation.
- **Beware thread-local leaks** (MDC, `ThreadLocal` caches, security context, `SimpleDateFormat` cached in a TL). Reused threads carry stale state into the next task; clean up in `finally` or use `beforeExecute`/`afterExecute`.
- **Pool starvation / deadlock by dependency:** if a task submitted to a pool *waits on the result of another task submitted to the same pool*, and the pool is sized so all threads are occupied by waiters, you deadlock. Single-thread executors are especially prone. Rule: **tasks in a pool must not block on other tasks in the same bounded pool.**

### 6.3 Exception handling — where exceptions go

| Submission | Task throws → exception goes to |
|------------|---------------------------------|
| `execute(Runnable)` | the thread's `UncaughtExceptionHandler` (set via `ThreadFactory`); thread is replaced |
| `submit(...)` | captured in the `Future`; surfaces only on `future.get()` (as `ExecutionException`) |
| `scheduleAtFixedRate/WithFixedDelay` | **suppresses all future executions** of that schedule (silent!) |

Best practices: set an `UncaughtExceptionHandler` in your `ThreadFactory`; **always call `get()`** on futures you care about (or use `CompletableFuture` with `.exceptionally`/`.handle`); wrap scheduled-task bodies in try/catch; optionally override `afterExecute` to log the throwable (note: for `submit`, the throwable arrives wrapped in the `FutureTask`, so check the future, not the `afterExecute` `Throwable` arg).

### 6.4 Observability

Export per-pool gauges on a schedule: `getPoolSize`, `getActiveCount`, `getQueue().size()`, `getQueue().remainingCapacity()`, `getCompletedTaskCount`, `getLargestPoolSize`, and a **rejection counter** (increment in a wrapping `RejectedExecutionHandler`). Micrometer's `ExecutorServiceMetrics.monitor(registry, pool, "name")` wires most of these automatically. The two most actionable signals: **queue depth trending toward capacity** (you're falling behind → impending rejections/latency) and **rejection rate > 0** (you're shedding load).

### 6.5 Testability

- Inject the `ExecutorService` (don't `new` it inside business logic) so tests can pass a deterministic one.
- Use a **same-thread executor** (`Runnable::run` or Guava's `MoreExecutors.directExecutor()`) to make async code synchronous in unit tests.
- Test rejection paths by submitting more than `max + queueCapacity` tasks.
- For scheduled code, abstract the clock or use a deterministic scheduler so tests don't sleep.

### 6.6 Security & cost

- **Resource exhaustion is a security concern** (DoS): unbounded pools/queues let an attacker drive OOM or thread exhaustion. Bound everything.
- **Privilege/context leakage:** clear `ThreadLocal` security/credential context between tasks on pooled threads.
- **Cost:** each platform thread reserves stack memory (often ~512 KB–1 MB *reserved* virtual memory; resident grows with use). A 1,000-thread pool can reserve ~1 GB of address space. Right-sizing pools directly reduces memory and CPU (context switching) cost — relevant on cloud instances billed by vCPU/RAM.

### 6.7 Anti-patterns checklist

- `Executors.newFixedThreadPool` / `newSingleThreadExecutor` → **unbounded queue** → OOM under load.
- `Executors.newCachedThreadPool` → **unbounded threads** → thread exhaustion / context-switch storm.
- Unnamed threads (`pool-N-thread-M`) → undebuggable dumps.
- No rejection policy thought-through → either silent OOM (unbounded) or unhandled `RejectedExecutionException`.
- Blocking I/O on `ForkJoinPool.commonPool()` (incl. parallel streams) → starves shared pool for the whole JVM.
- Never shutting down pools → thread leak; with non-daemon threads, JVM won't exit.
- Calling `submit` and never `get` → swallowed exceptions.
- Catching `InterruptedException` and doing nothing → broken cancellation/shutdown.
- One giant shared pool for both fast CPU work and slow I/O → head-of-line blocking; isolate by workload ("bulkheading").
- Tasks in a pool that block on other tasks in the *same* pool → deadlock.

---

## 7. Advanced topics & deep internals

### 7.1 Why queueing is preferred over thread creation (design rationale)

The "fill queue before growing" policy reflects a deliberate bias: under steady load, a *fixed* set of threads with a queue gives stable, predictable resource use and good throughput; spinning new threads is the *emergency* response reserved for when the queue can't absorb the burst. The downside is the trap with unbounded queues (max never engages). Understanding this lets you intentionally invert it: use a `SynchronousQueue` to make the pool *thread-eager* (grow first, queue never), or a small bounded queue to get a hybrid (modest buffering, then grow, then reject).

### 7.2 The `ctl` bit-packing & lock-free count management

`ctl` = `(runState << 29) | workerCount`. Helpers `runStateOf(c)`, `workerCountOf(c)`, `ctlOf(rs, wc)`. Worker count is incremented/decremented via CAS in `compareAndIncrementWorkerCount`/`addWorker`. Reading both state and count atomically from one int avoids a lock on the hot path. The 29-bit count caps workers at `2^29 - 1` (~536M) — never a practical limit.

### 7.3 `addWorker` race handling

`addWorker(firstTask, core)` CAS-loops on `ctl`: it rechecks run state (refuse if shutting down, with nuance for SHUTDOWN+non-empty queue), checks the count against the relevant bound (`corePoolSize` if `core`, else `maximumPoolSize`), CAS-increments the count, then creates and starts the `Worker`. If thread creation fails or start throws, it rolls back via `addWorkerFailed` (decrement count, remove worker, attempt termination). This is what makes the pool robust to `OutOfMemoryError` during thread creation.

### 7.4 `allowCoreThreadTimeOut` and dynamic resizing

- `allowCoreThreadTimeOut(true)` makes core threads also use `poll(keepAlive)` and exit when idle → pool can shrink to **zero**, re-creating threads on demand. Useful for rarely-used pools to free memory; adds latency on the next burst (no warm threads). Requires `keepAliveTime > 0`.
- `setCorePoolSize`/`setMaximumPoolSize` at runtime: increasing core may start new workers if tasks are queued; decreasing interrupts idle workers so excess threads drain off. This enables adaptive/auto-tuning pools (e.g., scaling threads to load via a control loop), but mutate carefully under contention.

### 7.5 `prestartAllCoreThreads` — defeating lazy start

By default core threads are created lazily (on first task). For latency-sensitive services you can call `prestartAllCoreThreads()` at startup so the first requests don't pay thread-creation cost and so connection warm-up / JIT happens before live traffic.

### 7.6 `ScheduledThreadPoolExecutor` internals

It uses a `DelayedWorkQueue` (a binary-heap priority queue ordered by next-execution time). `take()` returns a task only when its delay has elapsed; otherwise the leader thread waits exactly until the next deadline (the "leader-follower" pattern minimizes wakeups). Periodic tasks re-enqueue themselves after running (fixed-rate computes next time from scheduled start; fixed-delay from completion). `corePoolSize` controls concurrency of *due* tasks; the queue itself is unbounded, so a flood of scheduled tasks can still grow memory.

### 7.7 Virtual threads (JDK 21+, Project Loom) — the paradigm shift

*Virtual threads* are lightweight threads managed by the JVM, not the OS. Thousands-to-millions can exist; they're cheap to create (no fixed OS stack reservation; stack lives on the heap and grows/shrinks). When a virtual thread blocks on I/O, the JVM **unmounts** it from its underlying *carrier* (a platform thread in a hidden `ForkJoinPool`) and mounts another virtual thread — so blocking is cheap and you no longer need large pools to hide I/O latency.

Implications for this chapter:
- `Executors.newVirtualThreadPerTaskExecutor()` returns an `ExecutorService` that starts **one new virtual thread per task** — *not* a pool. There's no reason to pool virtual threads (creation is nearly free); pooling them defeats the purpose.
- The classic I/O-bound sizing formula becomes largely moot for virtual threads — you can have a virtual thread per concurrent request and let them block. But **bounding still matters at the resource the work hits** (DB connections, downstream rate limits) — use a `Semaphore` to cap concurrency instead of a small thread pool.
- **Pinning caveat:** a virtual thread that blocks while holding a `synchronized` monitor (pre-JDK-24) or during a native call cannot unmount — it "pins" the carrier. Prefer `ReentrantLock` over `synchronized` around blocking sections on virtual threads (JDK 24 removed most `synchronized` pinning, but know your target version).
- CPU-bound work still wants a bounded platform-thread pool sized ~N+1; virtual threads don't add cores.

This is **version-specific:** preview in JDK 19/20, final in **JDK 21**.

### 7.8 Memory model & happens-before guarantees

`java.util.concurrent` guarantees: actions in a thread *before* submitting a task **happen-before** the task's execution; and a task's actions **happen-before** the successful return of `Future.get()`. So you can safely publish data through submit/get without extra synchronization. (*happens-before:* the JMM relation guaranteeing one action's memory effects are visible to another.)

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing a pool type

| Need | Choice | Why |
|------|--------|-----|
| Bounded concurrency, backpressure, production server | **Custom `ThreadPoolExecutor`** (bounded queue + policy) | Full control; safe defaults are yours to set |
| Many short bursty tasks, want responsiveness, capped by max | `ThreadPoolExecutor` with `SynchronousQueue` + finite max | Grows fast, hard ceiling, no queue backlog |
| Recursive divide-and-conquer / parallel compute | `ForkJoinPool` / parallel streams | Work-stealing keeps cores busy |
| Deferred / periodic | `ScheduledThreadPoolExecutor` | Delay-priority queue; replaces `Timer` |
| Massive concurrent **I/O-bound** workload, JDK 21+ | **Virtual-thread-per-task executor** + `Semaphore` for limits | Cheap threads; block freely |
| Quick prototype / tests | `Executors.*` factories or `directExecutor()` | Convenience (know the dangers) |

### 8.2 Queue + max + rejection combinations

| Goal | core/max | Queue | Rejection | Result |
|------|----------|-------|-----------|--------|
| Stable fixed concurrency, never drop, accept latency | n/n | bounded LBQ | `CallerRunsPolicy` | Backpressure via caller; no drops; producer throttled |
| Latency-first, hard cap | small/large | `SynchronousQueue` | `AbortPolicy` | Grows to max, then fails fast |
| Throughput buffer then shed | n/2n | small `ArrayBlockingQueue` | metric+`AbortPolicy` | Buffer bursts, then signal overload |
| Droppable telemetry | n/n | bounded | `DiscardPolicy` | Lose excess silently (intentional) |

### 8.3 `scheduleAtFixedRate` vs `scheduleWithFixedDelay`

- **Fixed rate:** use for clock-aligned cadence (heartbeats, polling at exact intervals) where you want N runs per period regardless of duration — but beware bunching/overlap-serialization if a run exceeds the period.
- **Fixed delay:** use for jobs of variable/unknown duration where you want a guaranteed *gap* (cleanup, retries) — naturally self-throttles, never bunches.

### 8.4 Platform-thread pool vs virtual threads (JDK 21+)

| | Platform pool | Virtual threads |
|---|---|---|
| Thread cost | high (OS stack) | tiny (heap, resizable) |
| Good for | CPU-bound; capped concurrency | massive I/O-bound concurrency |
| Sizing | must size carefully (N+1 / Little's Law) | per-task; cap at the *resource* with a Semaphore |
| Blocking I/O | wastes a precious thread | cheap (unmounts carrier) |
| Pooling | essential | unnecessary/anti-pattern |
| Pitfall | wrong size → starvation/OOM | pinning on `synchronized`/native |

---

## 9. Failure modes & debugging

### 9.1 Queue-driven `OutOfMemoryError` (the `newFixedThreadPool` classic)

**Symptom:** heap grows steadily under load; eventually `OutOfMemoryError: Java heap space`; a heap dump shows millions of queued `Runnable`/`FutureTask` instances in a `LinkedBlockingQueue`.
**Cause:** unbounded queue; tasks arrive faster than they complete; no rejection.
**Diagnose:** heap dump (Eclipse MAT / `jmap -dump`); look for the dominator tree rooted at the executor's queue. Monitor `getQueue().size()` over time.
**Fix:** bounded queue + rejection policy; address the true bottleneck (downstream capacity).

### 9.2 Thread exhaustion (`newCachedThreadPool` classic)

**Symptom:** `OutOfMemoryError: unable to create new native thread`; thread dump shows tens of thousands of pool threads; CPU pegged on context switching, throughput collapses.
**Cause:** `SynchronousQueue` + `max = MAX_VALUE` + slow/blocking tasks → unbounded thread creation.
**Diagnose:** `jstack <pid>` (or `jcmd <pid> Thread.print`) → count threads, see them all blocked on the same downstream call.
**Fix:** finite `maximumPoolSize`; bounded queue.

### 9.3 Pool deadlock / starvation by task dependency

**Symptom:** processing stalls; `getActiveCount() == maximumPoolSize` but no progress; thread dump shows all workers blocked in `Future.get()`.
**Cause:** tasks waiting on results of tasks in the same saturated pool.
**Fix:** separate pools for dependent stages, or never block on same-pool tasks; use `CompletableFuture` composition instead of nested blocking gets.

### 9.4 Silent task death

**Symptom:** scheduled job "just stopped running"; or submitted work seems to vanish with no log.
**Cause:** uncaught exception cancelling a `scheduleAtFixedRate` schedule; or `submit` exception never observed because `get()` never called.
**Diagnose:** add an `UncaughtExceptionHandler`; override `afterExecute` to log; check the `Future`.
**Fix:** wrap scheduled bodies in try/catch; always handle future results.

### 9.5 Leaked thread-local context

**Symptom:** wrong trace ID / user appears in logs across requests; cross-tenant data bleed.
**Cause:** `ThreadLocal`/MDC not cleared on pooled (reused) threads.
**Fix:** propagate-and-restore wrappers (§5.8) or clear in `afterExecute`.

### 9.6 Common-pool contention (parallel streams)

**Symptom:** unrelated parallel-stream work across the app slows together; CPU underutilized while tasks queue.
**Cause:** blocking I/O inside `parallelStream()`/`CompletableFuture.*Async` running on `ForkJoinPool.commonPool()`.
**Fix:** don't block in the common pool; pass a dedicated executor; or run that stream inside a custom `ForkJoinPool` (`customPool.submit(() -> stream.parallel()...).get()`).

### 9.7 Tools for diagnosis

| Tool | Use |
|------|-----|
| `jstack <pid>` / `jcmd <pid> Thread.print` | thread dump: states, what each pool thread is doing, deadlocks |
| `jcmd <pid> GC.heap_info` / `jmap -dump:live,format=b,file=h.hprof <pid>` + Eclipse MAT | find queue backlog / leaks |
| JFR (`-XX:StartFlightRecording`) + JDK Mission Control | thread/lock/allocation profiling over time |
| Micrometer / Prometheus (`ExecutorServiceMetrics`) | live pool gauges, rejection counters |
| `async-profiler` | low-overhead CPU/wall profiling of where pool threads spend time |
| Thread names (your `ThreadFactory`) | make all of the above legible |

### 9.8 A real-world failure pattern

A canonical incident: a service uses `Executors.newFixedThreadPool(50)` to call a downstream API; the API slows from 50 ms to 5 s during an incident. Tasks now take 100× longer; the unbounded queue absorbs the backlog (no rejection), so the service *looks* up but every request's latency climbs into minutes, queue depth hits millions, and the JVM OOMs — turning a downstream slowdown into a full local outage. The fix that would have contained it: a **bounded queue with a timeout and `AbortPolicy`/`CallerRunsPolicy`**, plus a per-call timeout (`Future.get(timeout)` or `CompletableFuture.orTimeout`), so the service sheds load fast and stays alive (fail-fast + backpressure = a bulkhead).

---

## 10. Interview drill

**Q1. Walk me through exactly what `ThreadPoolExecutor` does when `execute()` is called.**
*Model:* Check running threads vs `corePoolSize` → if below, start a new core thread. Else try `workQueue.offer()` → if it succeeds, queue. Else (queue full) try to start a thread up to `maximumPoolSize`. Else invoke the rejection handler. Key point: queueing is preferred over growing, so extra threads appear *only when the queue is full*.
- *Probe: So when does `maximumPoolSize` actually matter with an unbounded queue?* → Never; the queue never fills, so the pool never exceeds core. That's why `newFixedThreadPool`'s max is irrelevant.
- *Probe: How would you make the pool prefer creating threads over queueing?* → Use a `SynchronousQueue` (capacity 0, offer always fails) so it jumps straight to thread creation up to max.
- *Probe: What's the maximum in-flight work before rejection?* → `maximumPoolSize + queueCapacity`.

**Q2. Why is `Executors.newFixedThreadPool` considered dangerous?**
*Model:* It uses an **unbounded `LinkedBlockingQueue`**, so under sustained overload tasks accumulate without bound → memory exhaustion and unbounded latency; the pool never rejects (no backpressure). Same for `newSingleThreadExecutor`.
- *Probe: And `newCachedThreadPool`?* → Opposite failure: `SynchronousQueue` + `max=MAX_VALUE` → unbounded *thread* creation → native-thread/OOM and context-switch storm.
- *Probe: What do you use instead?* → A directly constructed `ThreadPoolExecutor` with a bounded queue, finite max, a named `ThreadFactory`, and an explicit rejection policy.

**Q3. How do you size a thread pool?**
*Model:* CPU-bound: ~N+1 (N = cores). I/O-bound: `N × U × (1 + W/C)` — cores × target utilization × (1 + wait/compute). Always validate with load tests and size to the narrowest downstream bottleneck.
- *Probe: What's the intuition behind `1 + W/C`?* → If a task waits 9× as long as it computes, you need ~10 threads to keep one core busy; it's Little's Law (L = λW) in disguise.
- *Probe: Your pool is 200 but the DB connection pool is 20 — what happens?* → 180 threads queue at the DB; you've just moved the bottleneck and wasted threads. Size to the constraint.

**Q4. `shutdown()` vs `shutdownNow()`?**
*Model:* `shutdown()` stops accepting new tasks but lets queued + running tasks finish (drains the queue). `shutdownNow()` stops accepting, **interrupts** running workers, abandons the queue, and returns the not-yet-run tasks. Neither blocks; pair with `awaitTermination`.
- *Probe: Will `shutdownNow()` always stop running tasks?* → Only if they respond to interruption; CPU loops that never check `isInterrupted()` keep running.
- *Probe: Show the graceful pattern.* → `shutdown()` → `awaitTermination(grace)` → if false, `shutdownNow()` → `awaitTermination` again; restore interrupt flag if interrupted.

**Q5. Where do exceptions go for `execute` vs `submit`?**
*Model:* `execute` → thread's `UncaughtExceptionHandler` (thread replaced). `submit` → captured in the `Future`, surfaced only at `get()` as `ExecutionException`. Forgetting to call `get()` swallows the exception.
- *Probe: What about scheduled periodic tasks?* → An uncaught exception silently cancels all future runs of that schedule; wrap the body in try/catch.

**Q6. Explain `ForkJoinPool` and work-stealing.**
*Model:* Each worker has its own deque; it works LIFO on its own (cache-hot) and steals FIFO from others' tails when idle, minimizing contention. Ideal for recursive divide-and-conquer. The common pool backs parallel streams and `CompletableFuture` async.
- *Probe: Why steal from the opposite end?* → Owner and thief touch different ends, reducing contention; the thief gets older, larger work units.
- *Probe: Risk of blocking in FJP?* → It's sized for CPU work; blocking starves it. Use `ManagedBlocker` or a different pool.

**Q7 (senior signal). You have one service doing fast CPU validation and slow third-party I/O. One pool or two? Justify.**
*Model:* **Two** (bulkhead isolation). A shared pool lets slow I/O tasks occupy all threads and starve the fast CPU work (head-of-line blocking), coupling their failure domains. Separate pools sized to each workload (CPU ≈ N+1; I/O larger via the wait/compute formula) isolate failures and let you tune/observe independently. On JDK 21+ I'd use virtual threads for the I/O path with a `Semaphore` capping downstream concurrency, and a small platform pool for CPU work.

**Q8 (senior signal). Pick a queue + rejection policy for a payment service that must never silently drop a charge but must stay alive under overload. Defend it.**
*Model:* Bounded `ArrayBlockingQueue` (real backpressure, predictable memory) + **`CallerRunsPolicy`** so overload throttles the producer instead of dropping or OOMing; combine with per-task timeouts and idempotency so retried/caller-run tasks are safe. Never `DiscardPolicy` (data loss). If the caller is an event loop that mustn't block, switch to `AbortPolicy` + explicit retry/overflow store instead of caller-runs.
- *Probe: Downside of CallerRunsPolicy?* → It blocks/slows the submitting thread; if that's a Netty/IO thread, you stall the whole event loop — then prefer abort+queue-elsewhere.

**Q9 (senior signal). When would you NOT introduce your own thread pool at all?**
*Model:* When the surrounding framework already governs execution — servlet container request threads, Netty event loops, a reactive scheduler, Kafka consumer threads, an Akka dispatcher. Adding a pool inside double-counts concurrency, fights the framework's backpressure, and risks starvation. Also, on JDK 21+, for per-request I/O you reach for virtual threads + a Semaphore rather than a tuned pool.

**Q10. What's `keepAliveTime` and which threads does it affect?**
*Model:* The idle timeout after which *extra* (beyond-core) threads terminate, shrinking the pool toward core. Core threads block forever unless `allowCoreThreadTimeOut(true)` makes them subject to it too (then the pool can shrink to zero).
- *Probe: How does this show in the run loop?* → Extra threads call `workQueue.poll(keepAlive)`; a timeout returns null from `getTask()` and the worker exits.

**Q11. How do you propagate trace/MDC context through a pool?**
*Model:* Capture the context at *submit* time and set/restore it inside a task wrapper (try/finally), because pooled threads are reused and carry stale `ThreadLocal`s. Libraries (Micrometer Context Propagation, Brave/OTel) provide wrapping executors.

**Q12. Default rejection policy and why?**
*Model:* `AbortPolicy` — throws `RejectedExecutionException`. It fails loudly so overload is visible and the caller decides (retry, shed, backpressure), rather than silently dropping or hiding the problem.

---

## 11. Glossary

- **AQS (AbstractQueuedSynchronizer):** Framework underlying JUC locks/latches; provides an atomic state int and a wait queue. `Worker` extends it to act as a per-task lock.
- **Backpressure:** A mechanism that slows or rejects producers when consumers can't keep up, preventing unbounded buildup.
- **`BlockingQueue`:** A queue whose `take()` blocks when empty and `put()` can block when full; the pool's work buffer.
- **Bulkhead:** Isolation pattern — separate resource pools per workload so one's failure doesn't sink the others (from ship compartments).
- **`Callable<V>`:** A task returning `V` and able to throw checked exceptions.
- **Carrier thread:** A platform thread that a virtual thread runs on while mounted (JDK 21+).
- **CAS (Compare-And-Swap):** Atomic instruction updating a value only if it matches an expected one; basis of lock-free code.
- **`CompletableFuture`:** Composable async result type with chaining (`thenApply`, `thenCompose`, `exceptionally`).
- **Context switch:** OS swapping one thread's CPU state for another's to time-share a core; costly in bulk.
- **Core pool size:** Threads kept alive even when idle (unless core timeout enabled).
- **`ctl`:** `ThreadPoolExecutor`'s atomic int packing run state (high bits) + worker count (low bits).
- **Daemon thread:** A thread that doesn't keep the JVM alive; the JVM exits when only daemons remain.
- **Deque:** Double-ended queue; FJP workers use one each for work-stealing.
- **Executor:** Interface with one method `execute(Runnable)`; decouples submission from execution.
- **`ExecutorService`:** Executor plus submission-with-result, bulk ops, and lifecycle.
- **`ForkJoinPool`:** Work-stealing pool for recursive parallel tasks; backs parallel streams via the *common pool*.
- **`Future<V>`:** Handle to a pending result; `get()` blocks; also surfaces task exceptions.
- **happens-before:** JMM ordering guarantee that one action's memory effects are visible to another.
- **Interrupt:** Cooperative cancellation signal (a flag); interruptible blocking methods throw `InterruptedException`.
- **keepAliveTime:** Idle timeout after which extra threads (and optionally core) terminate.
- **Little's Law (L = λW):** Average items in a system = arrival rate × time in system; underlies I/O pool sizing.
- **Maximum pool size:** Hard ceiling on worker threads.
- **MDC (Mapped Diagnostic Context):** Per-thread logging context (e.g., trace IDs); leaks across pooled threads if not handled.
- **Pinning:** A virtual thread unable to unmount its carrier (e.g., blocked inside `synchronized`/native), reducing scalability.
- **Platform thread:** A normal JVM thread mapped 1:1 to an OS thread.
- **Rejection policy (`RejectedExecutionHandler`):** What the pool does when it can neither queue nor run a task.
- **`Runnable`:** A no-result, no-checked-exception task.
- **`ScheduledExecutorService`:** Adds delayed/periodic scheduling; replaces `Timer`.
- **`SynchronousQueue`:** Zero-capacity hand-off queue; every `offer` fails unless a taker waits, forcing thread creation.
- **Syscall:** A call into the OS kernel for a privileged operation; relatively expensive.
- **`ThreadFactory`:** Creates worker threads — your hook for names, daemon status, uncaught handlers.
- **`ThreadPoolExecutor`:** The core configurable pool implementation.
- **Virtual thread:** JVM-scheduled lightweight thread (JDK 21+); cheap, blocks without wasting an OS thread.
- **Work-stealing:** Idle workers take tasks from busy workers' deques to balance load with low contention.
- **Worker:** The internal object wrapping a pool thread; also an AQS lock marking busy/idle.

---

## 12. Cheat-sheet & self-test

### Dense recap

**The handshake (per `execute`):** running < core → new core thread → else `queue.offer()` → succeeds = queued → else running < max → new extra thread → else **reject**.
**Capacity before rejection:** `maximumPoolSize + queueCapacity`.
**Unbounded queue → max is ignored** (pool stuck at core; OOM risk). **`SynchronousQueue` (cap 0) → always grows to max** (thread-exhaustion risk).

**Factories (and their danger):**
- `newFixedThreadPool(n)` / `newSingleThreadExecutor()` = core=max, **unbounded queue** → OOM.
- `newCachedThreadPool()` = core 0, max=MAX, **SynchronousQueue** → unbounded threads.
- `newScheduledThreadPool(n)` = delay-priority queue.
- `newVirtualThreadPerTaskExecutor()` (JDK 21) = one virtual thread per task (not a pool).

**Sizing:** CPU-bound ≈ **N+1**. I/O-bound ≈ **N × U × (1 + W/C)** (cores × target-util × (1 + wait/compute)). Size to the narrowest downstream bottleneck.

**Rejection policies:** `AbortPolicy` (default, throws) · `CallerRunsPolicy` (throttle producer) · `DiscardPolicy` (drop silently) · `DiscardOldestPolicy` (drop head) · custom (blocking put / route).

**Exceptions:** `execute` → UncaughtExceptionHandler; `submit` → in the Future (call `get()`!); scheduled → silently cancels the schedule (wrap in try/catch).

**Shutdown:** `shutdown()` drains; `shutdownNow()` interrupts + abandons + returns pending; always `awaitTermination`; restore interrupt flag.

**Always:** bounded queue · finite max · named `ThreadFactory` · explicit rejection policy · UncaughtExceptionHandler · monitor queue depth + rejection count · clear `ThreadLocal`/MDC · never block on same-pool tasks · don't block the `ForkJoinPool` common pool.

**Defaults to remember:** `AbortPolicy` is default; `ForkJoinPool.commonPool()` parallelism = cores − 1; `newCachedThreadPool` keepAlive = 60s; platform-thread stack ≈ 512 KB–1 MB reserved.

### Self-test (no answers)

1. You configure `core=4, max=8, queue=LinkedBlockingQueue()` (default capacity) and submit 1,000 tasks. How many threads run, and what's the failure mode under sustained overload? Why?
2. Rewrite `newCachedThreadPool` as a direct `ThreadPoolExecutor` constructor call and explain each argument's effect on the handshake.
3. A task in pool P submits another task to P and blocks on its `Future.get()`. Under what sizing does this deadlock, and how do you prove it from a thread dump?
4. Derive the I/O-bound thread count for 12 cores, 70% target utilization, tasks that wait 120 ms and compute 30 ms. Then explain why the real limit might be 25, not your number.
5. Your `scheduleAtFixedRate` heartbeat stopped firing after a transient NPE three days ago, with no further logs. Explain the mechanism and the one-line fix.
6. Choose and justify a queue + rejection-policy combination for: (a) droppable metrics, (b) a payment charge that must never be lost, (c) a latency-critical cache warmer behind a Netty event loop.
7. On JDK 21, you replace a 200-thread I/O pool with `newVirtualThreadPerTaskExecutor()`. What new bound must you add, what's the pinning pitfall, and which lock type avoids it?

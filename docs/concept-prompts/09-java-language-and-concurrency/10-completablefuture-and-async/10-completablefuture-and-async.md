# CompletableFuture & Async Pipelines

> A definitive engineering-handbook chapter for senior JVM backend developers. From first principles to deep internals: design, operate, debug, teach, and interview.

---

## 1. Overview & where it fits

`CompletableFuture<T>` (CF), introduced in **Java 8** (`java.util.concurrent` package, JEP nothing-specific — it shipped with the lambda/streams release), is the JVM's primary tool for **composable asynchronous programming**. It represents a *promise* of a value that may not exist yet: a computation that will eventually complete with a result of type `T`, complete *exceptionally* with a `Throwable`, or be cancelled.

**The problem it solves.** Before Java 8 you had `Future<T>` (Java 5). A `Future` is a read-only handle to a pending result, but it is **almost useless for composition**: the only way to get the value is `future.get()`, which **blocks the calling thread** until the result is ready. You cannot say "when this finishes, run that," you cannot chain steps, you cannot combine two futures, and you cannot register a callback. The result: code that nominally uses a thread pool still ends up with threads parked on `get()`, defeating the purpose. `CompletableFuture` fixes this by making the future **completable** (you can set its value from outside) and **composable** (you can attach continuations that run when it completes, build dependency graphs of stages, fan out and fan in, handle errors functionally, and apply timeouts).

**The mental model (one paragraph).** Think of a `CompletableFuture` as a **node in a directed acyclic graph (DAG) of computation stages**. Each node holds either "not done yet," "done with value V," or "done with exception E." You build the graph by attaching dependent stages (`thenApply`, `thenCompose`, `thenCombine`, …). When a node *completes*, the JVM walks its list of dependents and triggers them — either on the thread that completed the node, on the thread that registered the dependent, or on a thread from an `Executor` you supplied, depending on which method variant you used. Errors propagate down the graph like a "poison" value until something catches them. The whole thing is non-blocking: no thread sits idle waiting; threads only run when there is actual work (a stage's function) to execute.

**When you reach for it.**
- Orchestrating **multiple I/O-bound calls** (HTTP, DB, cache, gRPC) and combining their results — the canonical fan-out/fan-in.
- Building **sequential pipelines** where each step depends on the previous one's result, without blocking a thread between steps.
- Adding **timeouts, fallbacks, and retries** to async operations declaratively.
- Bridging a **callback-based or event-driven** API (e.g., an async HTTP client, Netty, a message listener) into a value you can `await` or compose.
- Implementing the **async leg** of a request handler so your servlet/Netty/Spring WebFlux thread is freed while downstream work proceeds.

**When NOT to reach for it.**
- For **CPU-bound parallelism over collections**, prefer parallel streams or the Fork/Join framework directly — CF adds overhead and ceremony.
- For **complex streaming / backpressure-aware** flows (infinite streams, rate control, multiple values over time), prefer **Reactive Streams** (Project Reactor `Mono`/`Flux`, RxJava) which model 0..N values and backpressure natively; CF models exactly **one** value.
- On **Java 21+**, for request-scoped fan-out you should seriously evaluate **virtual threads + structured concurrency** (`StructuredTaskScope`), which let you write straight-line blocking code that scales — often clearer than a CF graph. (Covered in §7.)

**Adjacent terms, defined up front** (we explain more as they appear):
- **Asynchronous**: the caller does not wait for the operation to finish; it continues and is notified later. Opposite of *synchronous* (blocking).
- **Non-blocking**: a thread is never parked waiting for a result; it either does work or is returned to the pool. Async is the programming model; non-blocking is the threading property you want it to have.
- **Executor / thread pool**: an object that runs `Runnable`/`Callable` tasks on a managed set of threads instead of creating a new thread per task. `ExecutorService` is the standard interface.
- **Callback / continuation**: a function you register to run *when* something completes, rather than calling it directly. CF's `thenX` methods register continuations.
- **Promise vs Future**: in many languages a *Future* is the read side (you observe the result) and a *Promise* is the write side (you fulfill it). `CompletableFuture` is **both**: it implements `Future` (read) *and* `CompletionStage` (compose) *and* exposes `complete()`/`completeExceptionally()` (write).

---

## 2. Foundations from first principles

### 2.1 Synchronous vs asynchronous, blocking vs non-blocking

Start with the simplest model. A **synchronous, blocking** call:

```java
String html = httpClient.get("https://example.com"); // thread sleeps until bytes arrive
process(html);
```

The thread that issued the call is **parked** (the OS scheduler takes it off the CPU) until the network responds — possibly tens to hundreds of milliseconds. During that time the thread consumes memory (a stack, default ~512KB–1MB on the JVM) and a slot in your pool, but does zero useful work. With C concurrent slow calls you need C threads, and threads are expensive. This is the **thread-per-request scaling wall**.

An **asynchronous, non-blocking** call instead returns immediately with a handle:

```java
CompletableFuture<String> fut = httpClient.getAsync("https://example.com");
fut.thenAccept(this::process); // runs later, when bytes arrive, on some thread
// the current thread is free NOW to do other work or return to the pool
```

No thread is parked waiting. When the response arrives, the I/O layer *completes* the future, which triggers `process`. One thread can shepherd thousands of in-flight requests.

**Key distinction, often confused:**
- *Asynchronous* describes the **API shape**: you get a future/callback instead of a value.
- *Non-blocking* describes **what threads actually do**: no parking on `get()`/`join()`.
You can have an async API that you then block on (`fut.get()`) — that's async-but-blocking and squanders the benefit. CF lets you stay non-blocking end-to-end by composing instead of getting.

### 2.2 `Future<T>` — the predecessor, and why it's not enough

`java.util.concurrent.Future<T>` (Java 5) is returned by `ExecutorService.submit(...)`. Its full API:

| Method | Meaning |
|---|---|
| `V get()` | Block until done; return value or throw `ExecutionException`/`InterruptedException`. |
| `V get(long, TimeUnit)` | As above but with a timeout (`TimeoutException`). |
| `boolean isDone()` | True if completed (normally, exceptionally, or cancelled). |
| `boolean isCancelled()` | True if cancelled before completing. |
| `boolean cancel(boolean mayInterruptIfRunning)` | Attempt cancellation. |

That's the **entire** interface. Notice what's missing:
- **No callback.** You cannot say "call me when done." You can only poll `isDone()` (busy-wait, wasteful) or block on `get()`.
- **No composition.** You cannot transform the result, chain a dependent computation, or combine with another `Future` without blocking to extract the value.
- **No manual completion.** You cannot complete a `Future` from outside; it's tied to the task you submitted.
- **No exception handling beyond catching at `get()`.**

So even though `submit` is "async," real programs end up writing:

```java
Future<A> fa = pool.submit(callA);
Future<B> fb = pool.submit(callB);
A a = fa.get();   // BLOCKS a pool thread
B b = fb.get();   // BLOCKS again
combine(a, b);
```

This holds threads hostage. `CompletableFuture` removes every one of those limitations.

### 2.3 `CompletionStage<T>` vs `CompletableFuture<T>`

`CompletableFuture` implements two interfaces:
- **`Future<T>`** — the legacy read side (`get`, `cancel`, `isDone`).
- **`CompletionStage<T>`** — the *composition* interface added in Java 8. It declares ~40 methods (`thenApply`, `thenCompose`, `thenCombine`, `exceptionally`, `whenComplete`, …) but **none** of the completion or blocking methods.

**Why the split matters in API design.** `CompletionStage` is a **read/compose-only** view: a caller can attach continuations but **cannot complete it** (no `complete()`) and ideally shouldn't block on it. When you expose an async API, returning `CompletionStage<T>` (rather than `CompletableFuture<T>`) signals "compose on this; don't complete or block it." In practice many codebases just return `CompletableFuture` for convenience, but knowing the distinction is senior-signal.

A **stage** is one node in the pipeline. Methods on a stage return a **new stage** representing "the result of applying this function after the upstream completes." This is what makes pipelines chainable.

### 2.4 The three terminal states

A CF is in exactly one of:
1. **Incomplete** (pending) — no result yet.
2. **Completed normally** — holds a value `T` (possibly `null`).
3. **Completed exceptionally** — holds a `Throwable`, wrapped in a `CompletionException` when observed downstream (or `ExecutionException` when observed via `Future.get`). Cancellation is a special case: it completes exceptionally with `CancellationException`.

Once completed, a CF is **immutable** — its value never changes. Subsequent `complete()` calls are no-ops returning `false`. (One exception: `obtrudeValue`/`obtrudeException`, intentionally forceful overrides, discussed in §7.)

### 2.5 Creating a CompletableFuture — the entry points

```java
// 1. Already-completed (useful for fallbacks / tests / seeds)
CompletableFuture<String> a = CompletableFuture.completedFuture("hi");

// 2. Run async, no return value (Runnable) -> CompletableFuture<Void>
CompletableFuture<Void> b = CompletableFuture.runAsync(() -> log("side effect"));

// 3. Supply async, returns a value (Supplier) -> CompletableFuture<T>
CompletableFuture<Integer> c = CompletableFuture.supplyAsync(() -> compute());

// 4. Manual: create incomplete, complete it later from anywhere (the "promise")
CompletableFuture<String> d = new CompletableFuture<>();
someCallbackApi.onResult(result -> d.complete(result));   // fulfill
someCallbackApi.onError(err -> d.completeExceptionally(err)); // reject
```

Variant #4 is how you **bridge callback-based libraries** into the CF world. The `runAsync`/`supplyAsync` factories without an explicit `Executor` run on the **common ForkJoinPool** (see §2.6, a critical gotcha). With an `Executor` argument they run on your pool:

```java
ExecutorService io = Executors.newFixedThreadPool(64);
CompletableFuture.supplyAsync(() -> blockingDbCall(), io);
```

### 2.6 The ForkJoinPool.commonPool() — the default executor (and the #1 gotcha)

When you call `*Async` methods **without** passing an `Executor`, the task runs on `ForkJoinPool.commonPool()`. You must understand this pool because misusing it is the most common production failure with CF.

**What is ForkJoinPool?** A specialized `ExecutorService` (Java 7) designed for **divide-and-conquer CPU-bound** work using **work-stealing**: each worker thread has its own deque of tasks; idle workers steal tasks from the tails of busy workers' deques. It excels at recursive parallel algorithms.

**What is the *common* pool?** A single, JVM-wide, lazily-initialized `ForkJoinPool` shared by parallel streams, `CompletableFuture` default async tasks, and anything else that uses it. Its default size is:

```
parallelism = Runtime.getRuntime().availableProcessors() - 1
```

So on an 8-core box the common pool has **7 worker threads** (minimum 1). It can also be tuned via system properties (see §4.6).

**Why this is dangerous.** The common pool is sized for CPU work and **shared across the whole JVM**. If you run **blocking I/O** on it (a DB query, an HTTP call that parks the thread), you tie up one of those few threads. A burst of blocking tasks can **starve** the common pool, which then stalls *unrelated* parallel streams and other CF work across your entire application. The classic incident: "our `Collectors`/`parallelStream` reports went slow and we couldn't figure out why" — because someone elsewhere ran blocking JDBC on `supplyAsync` (no executor) and exhausted the common pool.

**The rule:** for any stage that **blocks** (I/O, locks, `sleep`, `get` on another future), **always pass your own bounded `Executor`** sized for that workload. Reserve the common pool (and the default `*Async`) for **short, non-blocking, CPU-bound** transformations only — or supply an executor everywhere to be safe.

There's a subtle second gotcha: in a JVM with only **1 or 2 available processors** (common in small containers!), the common pool parallelism can be **1**, meaning your "parallel" `supplyAsync` calls actually run **serially**. We return to this in §6 and §9.

---

## 3. How it works internally

This section is the heart of the document. We trace the actual mechanics: how stages are stored, how completion propagates, which thread runs what, and how exceptions flow.

### 3.1 The dependency graph and the "Completion" stack

Internally a `CompletableFuture` has two `volatile` fields (names from the OpenJDK source):
- **`result`** — `null` while incomplete; once complete, holds either the value (boxed; `null` values are represented by a sentinel `NIL`) or an `AltResult` wrapper containing the `Throwable`.
- **`stack`** — the head of a **Treiber stack** (a lock-free, CAS-based linked stack) of `Completion` objects. Each `Completion` represents **one dependent stage** waiting on this future.

When you call, say, `upstream.thenApply(fn)`:
1. A **new** `CompletableFuture` (call it `down`) is created to represent the result.
2. A `Completion` object (specifically a `UniApply`) is created, referencing `upstream`, `down`, `fn`, and possibly an executor.
3. The runtime checks: **is `upstream` already complete?**
   - **If not complete:** the `Completion` is **pushed onto `upstream.stack`** via CAS. It will be fired when `upstream` completes.
   - **If already complete:** the continuation fires **immediately, on the current (calling) thread** (for non-async variants) — there's nothing to wait for.
4. `down` is returned to you so you can chain further.

This is why the structure is a **DAG of stages**: each `thenX` adds an edge from upstream to a new downstream node.

**What is a Treiber stack / CAS?** *CAS* = compare-and-swap, an atomic CPU instruction ("if this memory location equals X, set it to Y, atomically"). A *Treiber stack* uses CAS to push/pop without locks: to push, you read the current head, set your node's next pointer to it, then CAS the head from old to your node; retry if another thread beat you. This gives lock-free, high-throughput registration of dependents even under heavy concurrency. The trade: ordering of dependents firing is not strictly FIFO.

### 3.2 Completion propagation — the `postComplete` walk

When something completes `upstream` (via `complete`, `completeExceptionally`, the async task finishing, or an upstream's propagation), the runtime:
1. CAS-sets `upstream.result`.
2. Calls **`postComplete()`**, which **pops the entire `stack`** of `Completion`s and fires each one. Firing means: run the dependent's logic (apply `fn`, accept the value, etc.), set the dependent's `result`, and then recursively `postComplete()` *that* dependent — propagating completion down the graph.

To avoid blowing the call stack on deep pipelines, the implementation uses an **iterative/trampolining** approach (`NEXT`-chaining of completions) rather than naive recursion, so very long chains don't `StackOverflowError`. (Pre-Java-9 versions were more prone to deep recursion; this was hardened.)

**Crucial consequence — "the completing thread runs the continuation."** For **non-async** methods (`thenApply`, not `thenApplyAsync`), the dependent function executes **on whichever thread completed the upstream**. If a network thread (e.g., Netty's event loop) completes the future, your `thenApply` lambda runs **on that event loop thread**. If your lambda blocks there, you stall the event loop and everything it serves. This is a notorious source of latency bugs. We dissect the thread-assignment rules next.

### 3.3 Which thread runs each stage — the complete rules

This is the single most important mechanical detail in CF. Memorize it.

For a dependent stage created by `method`:

| Variant | Where the function runs |
|---|---|
| `thenApply(fn)` (no `Async`) | If upstream **already complete** when you register → the **caller's thread** (the thread calling `thenApply`). If upstream **not yet complete** → the **thread that later completes upstream** (the "completing thread"). |
| `thenApplyAsync(fn)` (no executor) | A thread from **`ForkJoinPool.commonPool()`**. |
| `thenApplyAsync(fn, myExecutor)` | A thread from **`myExecutor`**. |

The same three-way rule applies to **every** non-/`Async`/`Async+executor` triple (`thenAccept`, `thenRun`, `thenCompose`, `thenCombine`, `handle`, `whenComplete`, …).

**The big gotcha restated:** with the **non-async** form, *you do not control which thread runs your code, and it may even be your own calling thread or a library's I/O thread.* If the work is non-trivial or blocking, prefer an explicit `*Async(fn, executor)` so it runs on a pool you own and understand.

A subtle corollary: in a chain `a.thenApply(f).thenApply(g)`, if `a` completes on thread T, then `f` runs on T, and because `f` completing the middle future triggers `g`, **`g` also runs on T** — the whole non-async tail runs on the single completing thread, sequentially. That can be efficient (no handoff) or catastrophic (one slow I/O thread does everything).

### 3.4 Lifecycle / state machine

```
                 complete(v) / task returns v
   [INCOMPLETE] ───────────────────────────────► [COMPLETED_NORMALLY] (result = v or NIL)
        │                                                 │
        │ completeExceptionally(e) / task throws          │ fires dependents via postComplete
        │ orTimeout fires / cancel(true)                  ▼
        └───────────────────────────────► [COMPLETED_EXCEPTIONALLY] (result = AltResult(e))
                                                          │
                                                          ▼
                                            fires dependents (exceptional path)
```

State transitions:
- **INCOMPLETE → COMPLETED_NORMALLY:** `complete(v)` succeeds (CAS), or the backing `supplyAsync` task returns, or an upstream propagates a value through a transform.
- **INCOMPLETE → COMPLETED_EXCEPTIONALLY:** `completeExceptionally(e)`; or the task throws; or `cancel(true)` (→ `CancellationException`); or `orTimeout` elapses (→ `TimeoutException`).
- **Terminal & immutable:** once in either completed state, the result is fixed. Late `complete()`/`completeExceptionally()` return `false` and do nothing (except the forceful `obtrude*`).

### 3.5 Data flow & exception flow through a pipeline

Consider:

```java
CompletableFuture
  .supplyAsync(() -> fetchUser(id), pool)   // stage 1: User
  .thenApply(User::getAccountId)            // stage 2: String
  .thenCompose(acctId -> fetchBalance(acctId)) // stage 3: CF<Money> flattened
  .thenApply(Money::format)                 // stage 4: String
  .exceptionally(ex -> "N/A");              // stage 5: recover
```

**Normal path:** value flows down: `User` → `accountId` → (composed) `Money` → formatted `String`. Each stage transforms and hands off.

**Exceptional path:** suppose `fetchBalance` throws. Stage 3 completes exceptionally with `CompletionException(cause)`. Stages 4 (`thenApply`) is a **value transform** — it does **not** run its function on the exceptional path; instead it **propagates the exception** straight through, unchanged, to its downstream. Stage 5 (`exceptionally`) **does** run on the exceptional path: it catches, returns `"N/A"`, and the pipeline continues *normally* from there.

**Key rule:** transform/consume stages (`thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `thenCombine`) are **skipped on the exceptional path** and the exception flows past them. Only the **handler** stages (`exceptionally`, `handle`, `whenComplete`) observe exceptions. (We catalog these in §3.6.)

### 3.6 Exception wrapping rules (precise)

This trips up everyone. The wrapping depends on *how* you observe the failure:

- **Via `Future.get()`** → throws `ExecutionException` whose `getCause()` is your original throwable.
- **Via `join()`** → throws `CompletionException` whose `getCause()` is your original throwable (no checked exceptions).
- **Inside downstream composition** (the `Throwable` passed to `handle`/`whenComplete`/`exceptionally`) → if the failure originated several stages up, it is wrapped in `CompletionException`. So when inspecting, you often need `ex instanceof CompletionException ? ex.getCause() : ex`.
- A `CancellationException` is **not** wrapped (it's delivered directly).

Helper pattern you'll write often:

```java
static Throwable unwrap(Throwable t) {
    return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
}
```

### 3.7 How `thenCompose` differs from `thenApply` (flattening)

- `thenApply(Function<T, U>)` → `CompletableFuture<U>`. Use when your function returns a **plain value**.
- `thenCompose(Function<T, CompletionStage<U>>)` → `CompletableFuture<U>`. Use when your function returns **another future**. Without `thenCompose` you'd get `CompletableFuture<CompletableFuture<U>>` (nested), which is awkward. `thenCompose` **flattens** it — it is the **monadic bind / flatMap** of futures.

```java
// WRONG: nested future, you'd have to .join() inside or double-unwrap
CompletableFuture<CompletableFuture<Money>> bad =
    fetchUserCF(id).thenApply(u -> fetchBalanceCF(u.acctId()));

// RIGHT: flattened
CompletableFuture<Money> good =
    fetchUserCF(id).thenCompose(u -> fetchBalanceCF(u.acctId()));
```

Mental rule: **`thenApply` = `map`, `thenCompose` = `flatMap`.**

### 3.8 How `allOf` / `anyOf` work internally

- **`allOf(cf1, cf2, …)`** returns `CompletableFuture<Void>` that completes when **all** inputs complete. Internally it builds a **balanced binary tree** of `BiRelay` completions (pairing futures two at a time) so completion propagates in O(log n) depth rather than a long chain. It **does not** give you the results — you fetch each input's value afterward (typically via `join()`, which is non-blocking once `allOf` has completed). If **any** input completes exceptionally, `allOf` completes exceptionally with that exception — but note the **other inputs keep running** (CF has no automatic cancellation of siblings).
- **`anyOf(cf1, cf2, …)`** returns `CompletableFuture<Object>` that completes when the **first** input completes (normally or exceptionally), with that input's result/exception. The `Object` type is unavoidable because inputs may have different types; cast or constrain by convention.

---

## 4. The complete toolkit

Below, `T` is the upstream value type, `U` the downstream. "Three variants" means each method has a sync form and two async forms `(...Async(fn))` on the common pool and `(...Async(fn, Executor))` on a supplied pool, following the thread rules in §3.3.

### 4.1 Factory / creation methods

| Method | Signature | Purpose | Default executor |
|---|---|---|---|
| `completedFuture` | `static <U> CompletableFuture<U> completedFuture(U value)` | Already-completed CF (value may be null). | — |
| `failedFuture` (Java 9+) | `static <U> CompletableFuture<U> failedFuture(Throwable ex)` | Already-failed CF. | — |
| `completedStage` (9+) | `static <U> CompletionStage<U> completedStage(U value)` | Minimal completed stage (see §7 minimal CFs). | — |
| `failedStage` (9+) | `static <U> CompletionStage<U> failedStage(Throwable ex)` | Minimal failed stage. | — |
| `runAsync` | `static CompletableFuture<Void> runAsync(Runnable)` / `(Runnable, Executor)` | Run a side-effecting task async. | commonPool |
| `supplyAsync` | `static <U> CompletableFuture<U> supplyAsync(Supplier<U>)` / `(Supplier<U>, Executor)` | Compute a value async. | commonPool |
| `new CompletableFuture<>()` | constructor | Incomplete CF you complete manually (promise / bridge). | — |

### 4.2 Single-input transform / consume / run

| Method | Signature (sync form) | Behavior | Runs on exceptional path? |
|---|---|---|---|
| `thenApply` | `<U> CF<U> thenApply(Function<? super T,? extends U>)` | map value → value | No (propagates) |
| `thenAccept` | `CF<Void> thenAccept(Consumer<? super T>)` | consume value, no result | No |
| `thenRun` | `CF<Void> thenRun(Runnable)` | run action, ignores value | No |
| `thenCompose` | `<U> CF<U> thenCompose(Function<? super T,? extends CompletionStage<U>>)` | flatMap: value → future | No |

Each has `...Async` and `...Async(..., Executor)` variants.

### 4.3 Two-input combine / either

| Method | Combines | Result | Triggers when |
|---|---|---|---|
| `thenCombine(other, BiFunction)` | this + other values | `CF<U>` | **both** complete |
| `thenAcceptBoth(other, BiConsumer)` | both values, no result | `CF<Void>` | both complete |
| `runAfterBoth(other, Runnable)` | ignores values | `CF<Void>` | both complete |
| `applyToEither(other, Function)` | first available value | `CF<U>` | **either** completes |
| `acceptEither(other, Consumer)` | first available value | `CF<Void>` | either completes |
| `runAfterEither(other, Runnable)` | ignores values | `CF<Void>` | either completes |

All have `...Async` variants. `*Both` complete exceptionally if **either** input fails. `*Either` take whichever finishes first — including a first **exceptional** completion.

### 4.4 Multi-input combinators

| Method | Signature | Result |
|---|---|---|
| `allOf` | `static CF<Void> allOf(CompletableFuture<?>... cfs)` | completes when all complete (Void; gather results yourself) |
| `anyOf` | `static CF<Object> anyOf(CompletableFuture<?>... cfs)` | completes with first completion's result/exception |

### 4.5 Error handling & completion observation

| Method | Signature | Sees value? | Sees exception? | Can recover? |
|---|---|---|---|---|
| `exceptionally` | `CF<T> exceptionally(Function<Throwable,? extends T>)` | no | yes | yes (returns replacement value) |
| `exceptionallyAsync` (12+) | `(Function, [Executor])` | no | yes | yes |
| `exceptionallyCompose` (12+) | `CF<T> exceptionallyCompose(Function<Throwable,? extends CompletionStage<T>>)` | no | yes | yes (returns a future — async fallback) |
| `handle` | `<U> CF<U> handle(BiFunction<? super T, Throwable, ? extends U>)` | yes | yes | yes (you decide output from either) |
| `whenComplete` | `CF<T> whenComplete(BiConsumer<? super T, ? super Throwable>)` | yes | yes | **no** (observe only; passes original through) |

Distinctions:
- **`whenComplete`** is a **side-effect/observer**: it sees value-or-exception, runs an action (logging, metrics, cleanup), and **propagates the original outcome unchanged** (it cannot swallow or transform the result — if *its own* action throws, that new exception is added). Think `finally`.
- **`handle`** **transforms** both outcomes into a new value of possibly different type `U` — it can recover from an exception (return a fallback) or map a success. Think `catch`+`map` combined.
- **`exceptionally`** only fires on the **exceptional** path and returns a same-type fallback `T`. Think `catch`.

### 4.6 Timeout, completion-control, and read methods (Java 9+ for timeouts)

| Method | Since | Purpose |
|---|---|---|
| `orTimeout(long, TimeUnit)` | 9 | If not done within timeout, complete **exceptionally** with `TimeoutException`. |
| `completeOnTimeout(T value, long, TimeUnit)` | 9 | If not done within timeout, complete **normally** with `value` (a default/fallback). |
| `complete(T value)` | 8 | Manually complete normally; returns `true` if this call did it. |
| `completeExceptionally(Throwable)` | 8 | Manually complete exceptionally. |
| `completeAsync(Supplier<T>[, Executor])` | 9 | Complete using a supplier run async. |
| `cancel(boolean mayInterruptIfRunning)` | 8 | Complete with `CancellationException`. **Note:** the boolean is essentially ignored — CF does not interrupt the running task; cancellation only affects *this* CF and downstream, not the supplier thread. |
| `get()` / `get(timeout)` | 8 | **Blocking** read; throws checked `ExecutionException`/`InterruptedException`/`TimeoutException`. |
| `join()` | 8 | **Blocking** read; throws unchecked `CompletionException`. |
| `getNow(T valueIfAbsent)` | 8 | Non-blocking: return result if done, else `valueIfAbsent`. |
| `isDone()` / `isCompletedExceptionally()` / `isCancelled()` | 8 | State queries. |
| `getNumberOfDependents()` | 8 | Diagnostic: count of waiting dependents (estimate). |
| `obtrudeValue(T)` / `obtrudeException(Throwable)` | 8 | **Forcefully** override result even if already complete. Dangerous; testing/recovery only. |
| `minimalCompletionStage()` | 9 | Return a `CompletionStage` view that throws on completion/blocking methods (enforces compose-only). |
| `copy()` | 9 | A new CF completed the same way (decouples downstream). |
| `newIncompleteFuture()` | 9 | Factory hook subclasses override to control the type of dependent stages. |
| `defaultExecutor()` | 9 | The executor used by `*Async` without an explicit executor (commonPool by default; override in subclass). |

**Tuning the common pool (system properties / JVM flags):**

| Property | Effect | Default |
|---|---|---|
| `java.util.concurrent.ForkJoinPool.common.parallelism` | Worker count of the common pool. | `availableProcessors() - 1` (min 1) |
| `java.util.concurrent.ForkJoinPool.common.threadFactory` | Custom thread factory FQCN. | default |
| `java.util.concurrent.ForkJoinPool.common.exceptionHandler` | Uncaught exception handler FQCN. | none |
| `java.util.concurrent.ForkJoinPool.common.maximumSpares` | Max extra threads spawned for `ManagedBlocker` compensation. | 256 |

`-Djava.util.concurrent.ForkJoinPool.common.parallelism=N` is sometimes used to bump the common pool, but the cleaner answer is to **supply your own executors** for blocking work rather than reshaping the shared pool.

---

## 5. Code examples by use case

All examples are self-contained and idiomatic. Comments mark the non-obvious lines.

### 5.1 Parallel fan-out / fan-in (the canonical pattern)

Fetch three independent resources concurrently, then combine. Note the **dedicated executor** for blocking I/O.

```java
import java.util.concurrent.*;

public class FanOutFanIn {
    // Bounded pool sized for I/O concurrency, NOT the common pool.
    private static final ExecutorService IO =
        Executors.newFixedThreadPool(32, r -> {
            Thread t = new Thread(r, "io-pool");
            t.setDaemon(true);        // don't block JVM shutdown
            return t;
        });

    record Profile(User user, List<Order> orders, Recommendations recs) {}

    public CompletableFuture<Profile> loadProfile(long userId) {
        // Fan-out: three independent async calls, each on the IO pool.
        CompletableFuture<User> userF =
            CompletableFuture.supplyAsync(() -> userService.get(userId), IO);
        CompletableFuture<List<Order>> ordersF =
            CompletableFuture.supplyAsync(() -> orderService.recent(userId), IO);
        CompletableFuture<Recommendations> recsF =
            CompletableFuture.supplyAsync(() -> recoService.forUser(userId), IO);

        // Fan-in: combine three. thenCombine pairs two at a time.
        return userF
            .thenCombine(ordersF, AbstractMap.SimpleEntry::new)  // (User, Orders)
            .thenCombine(recsF, (entry, recs) ->
                new Profile(entry.getKey(), entry.getValue(), recs));
        // All three run concurrently; total latency ≈ max(t_user, t_orders, t_recs).
    }
}
```

**For N futures of the same type**, use `allOf` + collect:

```java
List<CompletableFuture<Quote>> futures = vendors.stream()
    .map(v -> CompletableFuture.supplyAsync(() -> v.quote(req), IO))
    .toList();

CompletableFuture<List<Quote>> all =
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenApply(ignored ->                 // allOf yields Void; gather now
            futures.stream()
                   .map(CompletableFuture::join) // non-blocking: all are done here
                   .toList());
```

### 5.2 Sequential dependency pipeline (each step needs the previous result)

```java
// userId -> account -> balance -> formatted string, no thread blocked between steps.
CompletableFuture<String> displayBalance(long userId) {
    return CompletableFuture
        .supplyAsync(() -> accountDao.findByUser(userId), IO) // CF<Account>
        .thenCompose(acct ->                                  // flatMap: returns CF
            CompletableFuture.supplyAsync(() -> ledger.balance(acct.id()), IO))
        .thenApply(money -> money.format(Locale.US))          // map: plain value
        .orTimeout(2, TimeUnit.SECONDS)                       // bound total latency
        .exceptionally(ex -> "Balance unavailable");          // graceful fallback
}
```

Why `thenCompose` for the middle step: `ledger.balance` is itself async (returns a CF), so we flatten. The first step uses `thenApply`-style mapping only because `format` returns a plain value.

### 5.3 Fallback / fail-soft with `handle` and `exceptionallyCompose`

```java
// Try primary; on failure, fall back to a (possibly async) secondary; else default.
CompletableFuture<Price> priceWithFallback(String sku) {
    return CompletableFuture.supplyAsync(() -> primaryPricer.price(sku), IO)
        .exceptionallyCompose(ex -> {                  // async fallback (Java 12+)
            log.warn("primary pricer failed: {}", unwrap(ex).toString());
            return CompletableFuture.supplyAsync(() -> backupPricer.price(sku), IO);
        })
        .handle((price, ex) -> {                       // final safety net
            if (ex != null) {
                metrics.increment("price.fallback.default");
                return Price.UNAVAILABLE;              // never propagate failure
            }
            return price;
        });
}
```

Use `handle` when you want to **observe both** value and exception and always produce a result; use `exceptionally` for the simpler same-type recovery.

### 5.4 Timeouts with fallback value vs. failure

```java
// completeOnTimeout: degrade gracefully to a cached value
CompletableFuture<Recommendations> recs =
    CompletableFuture.supplyAsync(() -> recoService.forUser(id), IO)
        .completeOnTimeout(cachedRecs(id), 150, TimeUnit.MILLISECONDS);

// orTimeout: bound latency and treat slowness as an error
CompletableFuture<Report> report =
    CompletableFuture.supplyAsync(() -> reportService.build(id), IO)
        .orTimeout(5, TimeUnit.SECONDS)               // -> TimeoutException if slow
        .exceptionally(ex -> Report.partial());
```

**Caveat (important):** `orTimeout`/`completeOnTimeout` complete the **CF**, but they do **not cancel or interrupt the underlying work**. The `supplyAsync` task keeps running on the IO pool and consumes a thread until it naturally finishes. For real cancellation you must propagate it into the client (e.g., HTTP request abort). The timeout scheduling uses an internal single-thread **`Delayer`** daemon `ScheduledThreadPoolExecutor`.

### 5.5 Bridging a callback-based API into a CompletableFuture

```java
// Wrap an async client that uses callbacks (e.g., legacy SDK).
CompletableFuture<byte[]> downloadAsync(String url) {
    CompletableFuture<byte[]> promise = new CompletableFuture<>();
    legacyClient.download(url, new Callback() {
        @Override public void onSuccess(byte[] data) { promise.complete(data); }
        @Override public void onFailure(Throwable t) { promise.completeExceptionally(t); }
    });
    return promise; // caller composes on it normally
}
```

Java 11's `HttpClient` already returns CFs natively, so you rarely wrap *it*:

```java
HttpClient client = HttpClient.newHttpClient();
CompletableFuture<String> body = client
    .sendAsync(HttpRequest.newBuilder(URI.create(url)).build(),
               HttpResponse.BodyHandlers.ofString())
    .thenApply(HttpResponse::body);   // runs on HttpClient's executor — see §6 warning
```

### 5.6 First-result-wins (hedged requests) with `anyOf`

```java
// Send to two replicas; take whichever responds first (reduces tail latency).
@SuppressWarnings("unchecked")
CompletableFuture<Response> hedged(Request req) {
    CompletableFuture<Response> a =
        CompletableFuture.supplyAsync(() -> replicaA.call(req), IO);
    CompletableFuture<Response> b =
        CompletableFuture.supplyAsync(() -> replicaB.call(req), IO);
    return CompletableFuture.anyOf(a, b)
        .thenApply(o -> (Response) o);  // anyOf returns Object
    // Note: the loser keeps running (no auto-cancel). Cancel it manually if costly.
}
```

To cancel the loser, retain references and call `cancel`/abort once one wins.

### 5.7 Retry with backoff (recursive composition)

```java
CompletableFuture<T> withRetry(Supplier<CompletableFuture<T>> action,
                               int attemptsLeft, Duration backoff) {
    return action.get().exceptionallyCompose(ex -> {
        if (attemptsLeft <= 1) return CompletableFuture.failedFuture(ex);
        // schedule a delayed retry without blocking a thread:
        CompletableFuture<Void> delay = new CompletableFuture<>();
        SCHEDULER.schedule(() -> delay.complete(null),
                           backoff.toMillis(), TimeUnit.MILLISECONDS);
        return delay.thenCompose(v ->
            withRetry(action, attemptsLeft - 1, backoff.multipliedBy(2)));
    });
}
```

`SCHEDULER` is a small `ScheduledExecutorService`. The recursion via `thenCompose` keeps everything non-blocking; backoff doubles each attempt.

### 5.8 Bounded concurrency / backpressure with a Semaphore gate

When fanning out over a large list, unbounded `supplyAsync` can overwhelm downstreams. Gate it:

```java
Semaphore gate = new Semaphore(50);   // max 50 concurrent in-flight calls

CompletableFuture<Result> guarded(Item item) {
    try {
        gate.acquire();                                  // backpressure: block submitter
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return CompletableFuture.failedFuture(e);
    }
    return CompletableFuture.supplyAsync(() -> process(item), IO)
        .whenComplete((r, ex) -> gate.release());        // always release the permit
}
```

A cleaner alternative: cap concurrency at the **executor** level (a fixed pool of size N with a bounded queue), so submission throttles naturally. See §6.4.

### 5.9 Spring controller returning a CompletableFuture (async request handling)

```java
@GetMapping("/profile/{id}")
public CompletableFuture<Profile> profile(@PathVariable long id) {
    return profileService.loadProfile(id);   // releases the servlet thread immediately
}
```

Spring MVC detects the `CompletableFuture` return type and uses async request processing: the request-handling (Tomcat) thread is freed while the CF is pending and resumes to write the response when it completes. This raises throughput under I/O-heavy load **provided your CF stages don't block Tomcat or the common pool**.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Stage overhead is small but not zero.** Each `thenX` allocates a new CF and a `Completion`. For tight CPU loops this matters; don't build CF graphs to do arithmetic. CF shines for I/O latency hiding, where the network dominates.
- **Avoid unnecessary `*Async` handoffs.** Each `*Async` hop means a task submission + a thread context switch. For a cheap, non-blocking transform, the plain `thenApply` (run on the completing thread) is cheaper than `thenApplyAsync`. Use `*Async` precisely when the work is heavy/blocking and you want to move it off the completing thread.
- **Right-size pools.** For blocking I/O the useful pool size is governed by **Little's Law**: `concurrency ≈ throughput × latency`. If each call takes 100ms and you need 500 req/s, you need ~50 concurrent slots. A common heuristic for blocking pools: `threads = cores × (1 + wait/compute)`. For CPU-bound work, `threads ≈ cores`.
- **Latency = critical path, not sum.** A correct fan-out has latency ≈ the slowest branch. If you accidentally serialize (e.g., `.get()` between submissions, or one undersized pool), latency becomes the sum.

### 6.2 Correctness & concurrency

- **Don't mutate shared state from stages without synchronization.** Continuations can run on different threads; results published through a completed CF are safely visible (CF establishes happens-before from completion to dependent execution), but *your own* shared collections are not protected.
- **Composition does not guarantee ordering across independent branches.** Only the data-dependency edges you create impose ordering.
- **`null` is a valid value.** `supplyAsync(() -> null)` completes normally with `null`; downstream `thenApply` will receive `null`. Guard if your logic can't handle it.
- **Exceptions in a `whenComplete` action** are themselves propagated (suppressed-combined with the original), which can mask the real error. Keep `whenComplete` bodies trivial and exception-free.

### 6.3 The common-pool trap (production hardening)

- **Never run blocking I/O on `ForkJoinPool.commonPool()`** (i.e., never use the no-executor `*Async` for blocking work). Supply a dedicated bounded executor.
- Beware libraries that complete CFs on their own internal threads (Netty event loops, `HttpClient`'s default executor). Attaching a **non-async** continuation runs your code on **their** thread. If your continuation is non-trivial, force it onto your pool with `*Async(fn, yourPool)`.
- In containers, check `availableProcessors()`. With CPU limits, the common pool may have parallelism **1**, silently serializing your "parallel" work. Either set `-Djava.util.concurrent.ForkJoinPool.common.parallelism` or, better, always pass executors.

### 6.4 Backpressure & resource protection

- A **bounded executor** (`ThreadPoolExecutor` with a bounded `ArrayBlockingQueue` and a sane `RejectedExecutionHandler`, e.g., `CallerRunsPolicy` to throttle submitters) gives natural backpressure: when saturated, submission slows or rejects rather than building an unbounded queue and OOMing.
- CF itself has **no backpressure** — it models a single value, not a stream. If you have a high-rate stream of work, gate submission (Semaphore, bounded pool) or switch to Reactor/RxJava which support reactive backpressure (the consumer signals demand `request(n)`).
- Always pair `orTimeout`/`completeOnTimeout` with **client-side timeouts** so the underlying call actually stops, preventing thread/connection leaks.

### 6.5 Memory

- A long-lived incomplete CF with many dependents holds references to all of them (and their captured state) — a potential **memory leak** if it never completes. Ensure every CF eventually completes (success, failure, or timeout).
- Captured lambdas can retain large objects (the whole request, big buffers). Capture only what you need.

### 6.6 Security

- Continuations may run on shared pool threads where **`ThreadLocal`** context (security principal, MDC, tenant) is **not propagated** automatically — see §6.8. Re-establish or explicitly pass context to avoid authorization bugs (acting as the wrong user) and data leakage across requests.
- Don't log full exception causes containing secrets; unwrap and sanitize.

### 6.7 Observability

- **Tracing:** distributed tracing (OpenTelemetry, etc.) relies on context in `ThreadLocal`. Across async hops you must wrap your executor (context-propagating executor) or explicitly carry the span. Without this, spans break at every `*Async` boundary.
- **Metrics:** instrument per-stage latency and the **terminal outcome** with `whenComplete` (record success/failure/timeout counts and durations). Tag by dependency.
- **Pool health:** export `ForkJoinPool.commonPool().getPoolSize()/getQueuedTaskCount()/getActiveThreadCount()` and your custom pools' queue depth and active count. Rising queue depth = saturation/backpressure problem.

### 6.8 Context propagation (MDC / SecurityContext / tracing)

`ThreadLocal`-based context (SLF4J MDC, Spring `SecurityContextHolder`, tracing spans) lives on the *thread*, so it **does not follow** work onto pool threads. Fixes:
- Wrap your executor so each submitted task captures and restores context. Libraries: Micrometer **Context Propagation**, OpenTelemetry's context-aware executor, or roll your own:

```java
static Executor contextual(Executor delegate) {
    return task -> {
        Map<String,String> mdc = MDC.getCopyOfContextMap();         // capture on submit
        delegate.execute(() -> {
            Map<String,String> prev = MDC.getCopyOfContextMap();
            if (mdc != null) MDC.setContextMap(mdc); else MDC.clear();// restore on run
            try { task.run(); }
            finally { if (prev != null) MDC.setContextMap(prev); else MDC.clear(); }
        });
    };
}
```

### 6.9 Testing

- **Determinism:** test logic synchronously by completing CFs yourself. Use `completedFuture`/`failedFuture` as stubs.
- **Avoid `get()` without a timeout** in tests — a bug can hang the suite. Use `get(2, SECONDS)` or `join` after asserting `isDone()`.
- **Same-thread executor:** inject `Runnable::run` (a direct executor) so `*Async` runs inline, making tests deterministic and ordering-stable.
- For timeout behavior, inject a controllable clock/scheduler rather than real sleeps.

### 6.10 Anti-patterns (avoid these)

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| Blocking on the common pool (`supplyAsync(blockingIO)` no executor) | Starves shared pool; cross-app stalls | Pass a dedicated bounded executor |
| `future.get()`/`join()` inside a stage | Blocks a pool thread; can deadlock if same pool | Use `thenCompose` to chain instead |
| `thenApply` returning a `CompletableFuture` | Nested `CF<CF<T>>` | Use `thenCompose` |
| Swallowing exceptions with bare `exceptionally(ex -> null)` everywhere | Hides failures; null surprises downstream | Log/metric, return meaningful fallback |
| Non-async continuation on a library's I/O thread doing heavy work | Stalls event loop | `*Async(fn, yourPool)` |
| Unbounded fan-out over huge lists | OOM / downstream overload | Bound concurrency (semaphore/bounded pool) |
| Relying on `cancel(true)` to stop work | CF doesn't interrupt the task | Propagate cancellation to the client |
| Forgetting `allOf` results are `Void` | NPE / wrong code | `join()` each input after `allOf` |
| Same single-thread executor for chained dependent stages that wait on each other | Deadlock (no free thread to run the dependency) | Separate pools or non-blocking composition |

---

## 7. Advanced topics & deep internals

### 7.1 `ManagedBlocker` — letting the FJ pool compensate for blocking

If you *must* block inside a ForkJoinPool worker, wrap it in a `ForkJoinPool.ManagedBlocker`. The pool detects the block and **spawns a compensation thread** (up to `maximumSpares`, default 256) so parallelism is maintained:

```java
ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
    volatile Object result;
    public boolean block() throws InterruptedException {
        result = blockingCall();       // the actual blocking op
        return true;
    }
    public boolean isReleasable() { return result != null; }
});
```

This is how parallel streams survive occasional blocking. It's a sharp tool: compensation threads add overhead and can balloon thread counts. Prefer a dedicated pool; reach for `ManagedBlocker` only when you're committed to the FJ pool.

### 7.2 Minimal completion stages & defensive APIs

`minimalCompletionStage()` (Java 9) returns a `CompletionStage` view that **throws `UnsupportedOperationException`** if anyone calls `toCompletableFuture()` mutation/blocking methods on it — enforcing "compose, don't complete or block." Use it when returning a stage from a library so callers can't complete *your* internal future or block on it.

### 7.3 `obtrudeValue` / `obtrudeException`

These **forcibly overwrite** the result even after completion, breaking immutability. Legitimate uses: error recovery in frameworks, resetting state in tests. In application code they're a smell — concurrent observers may have already seen the old result.

### 7.4 Subclassing & `newIncompleteFuture` / `defaultExecutor`

To make a whole pipeline default to *your* executor (so even plain `*Async` without an executor uses it), subclass and override:

```java
class MyFuture<T> extends CompletableFuture<T> {
    @Override public Executor defaultExecutor() { return APP_POOL; }
    @Override public <U> CompletableFuture<U> newIncompleteFuture() {
        return new MyFuture<>();  // dependent stages are also MyFuture
    }
}
```

Now `*Async` (no executor) routes to `APP_POOL`, and chained stages inherit the override — a clean way to avoid the common-pool trap project-wide.

### 7.5 `delayedExecutor` and the internal `Delayer`

`CompletableFuture.delayedExecutor(d, unit[, executor])` (Java 9) returns an `Executor` that runs tasks **after a delay**, backed by the internal `Delayer` (a single-thread daemon `ScheduledThreadPoolExecutor`). Combine with `completeAsync`/`runAsync` for delays without blocking:

```java
Executor in1s = CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS, IO);
CompletableFuture.supplyAsync(() -> "late", in1s);
```

This is what powers `orTimeout`/`completeOnTimeout` under the hood.

### 7.6 Stack-safety and deep chains

Pre-Java-9, deeply nested `thenCompose` recursion could `StackOverflowError`. Java 9+ reworked `postComplete`/`tryFire` to be **iterative** (chaining completions and looping), so even thousands of stages complete without exhausting the stack. Still, building tens of thousands of stages dynamically signals you want a stream/reactive model instead.

### 7.7 Memory visibility guarantees

CF establishes a **happens-before** relationship: actions in the thread that completes a CF *happen-before* actions in any thread that subsequently runs a dependent stage or observes the result via `get`/`join`. So values written before completion are visible to the continuation without extra synchronization. This does **not** extend to unrelated shared mutable state your code touches.

### 7.8 Cancellation semantics, in depth

- `cancel(b)` completes *this* CF with `CancellationException` **only if not already complete**; it returns whether it did so.
- The `mayInterruptIfRunning` flag is **effectively ignored**: CF has no reference to a running thread to interrupt (the `supplyAsync` task isn't tracked for interruption). So cancelling a CF **does not stop the work**; it only changes this CF's state and propagates the cancellation downstream.
- Downstream stages of a cancelled CF complete exceptionally with `CompletionException(CancellationException)` (or `CancellationException` directly via `join`).
- To truly cancel, you must wire cancellation into the underlying operation (e.g., abort the HTTP request, cancel the JDBC statement).

### 7.9 Virtual threads & structured concurrency (Java 21+) — the modern alternative

**Virtual threads** (JEP 444, Java 21) are lightweight threads managed by the JVM, not 1:1 with OS threads. A blocking call on a virtual thread parks the *virtual* thread and frees the underlying *carrier* OS thread, so you can have **millions** of blocking-style tasks cheaply. This **undercuts the main reason CF existed** (avoiding thread-per-blocking-call), letting you write simple synchronous-looking code that scales:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<User> u = executor.submit(() -> userService.get(id));      // blocking, cheap
    Future<List<Order>> o = executor.submit(() -> orderService.recent(id));
    return new Profile(u.get(), o.get(), null);  // get() is fine on virtual threads
}
```

**Structured concurrency** (`StructuredTaskScope`, preview in 21/22, evolving) makes fan-out a scoped, cancel-on-failure, observable unit:

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var u = scope.fork(() -> userService.get(id));
    var o = scope.fork(() -> orderService.recent(id));
    scope.join().throwIfFailed();        // waits; cancels siblings on first failure
    return new Profile(u.get(), o.get(), null);
}
```

**When CF still wins:** event-driven/callback bridges, returning a composable handle from a library API, integrating with code that already speaks `CompletionStage`, and pure non-blocking transform pipelines. **When to prefer virtual threads/SC:** request-scoped fan-out where blocking-style clarity and automatic cancellation matter. Senior teams in 2024+ increasingly choose virtual threads + structured concurrency for new fan-out code while keeping CF for its composition/bridging strengths.

### 7.10 CompletableFuture vs Reactive Streams (Reactor/RxJava)

CF = exactly **one** async value, no backpressure, eager (the task starts when you create it). `Mono` = one value but **lazy** (nothing runs until subscribed) with backpressure and a vast operator set; `Flux` = **0..N** values over time with backpressure. For pipelines of multiple values, streaming, rate control, or cancellation propagation, reactive types are the right model. Interop: `Mono.fromFuture(cf)` and `mono.toFuture()` bridge the two.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing the right async tool

| Need | Best fit | Why |
|---|---|---|
| One async value, compose/transform | `CompletableFuture` | Purpose-built; rich combinators |
| Bridge callback API to a value | `CompletableFuture` (manual `complete`) | Promise side available |
| Fan-out blocking calls, simple code (Java 21+) | Virtual threads + `StructuredTaskScope` | Blocking-style, auto-cancel, clear |
| 0..N values / streaming / backpressure | Reactor `Flux` / RxJava | Models multiple values + demand |
| CPU-bound data parallelism | Parallel streams / Fork-Join | Work-stealing over partitions |
| Legacy "submit and wait" | `ExecutorService` + `Future` | Fine if you truly just block once |

### 8.2 `thenApply` vs `thenCompose` vs `thenCombine`

| Method | Input function returns | Combines how many | Use when |
|---|---|---|---|
| `thenApply` | a plain value `U` | 1 upstream | transform a result |
| `thenCompose` | a `CompletionStage<U>` | 1 upstream → another async step | the next step is itself async (sequential dependency) |
| `thenCombine` | combine two values | 2 independent upstreams | fan-in of two parallel results |

### 8.3 `handle` vs `whenComplete` vs `exceptionally`

| Method | Sees value | Sees exception | Can change result | Analogy |
|---|---|---|---|---|
| `exceptionally` | no | yes | yes (same type) | `catch` |
| `handle` | yes | yes | yes (any type) | `catch` + `map` |
| `whenComplete` | yes | yes | no (passes through) | `finally` |

### 8.4 Sync vs `*Async` vs `*Async(executor)`

| Form | Thread | Use when |
|---|---|---|
| `thenApply` (sync) | completing thread (or caller if already done) | cheap, non-blocking transform; you accept whatever thread |
| `thenApplyAsync(fn)` | common pool | short CPU-bound work you want off the completing/I/O thread; **never blocking I/O** |
| `thenApplyAsync(fn, pool)` | your pool | blocking/heavy work, or you need a controlled/instrumented pool |

**Rule of thumb:** non-blocking & trivial → sync; blocking or heavy → `Async` with **your** executor; default `Async` (common pool) only for short CPU tasks you're sure won't starve the shared pool.

### 8.5 `allOf`/`anyOf` vs manual `thenCombine` chaining

- 2–3 futures, mixed types → `thenCombine` reads cleanly and is type-safe.
- N futures, same type, "wait for all" → `allOf` + collect via `join`.
- N futures, "first wins" → `anyOf` (cast result), or hedging.

### 8.6 `orTimeout` vs `completeOnTimeout`

| Method | On timeout | Use when |
|---|---|---|
| `orTimeout` | completes **exceptionally** (`TimeoutException`) | slowness should be treated as failure (then `exceptionally`/`handle`) |
| `completeOnTimeout` | completes **normally** with a default | you have a sensible degraded value (cache, empty result) |

---

## 9. Failure modes & debugging

### 9.1 Common-pool starvation

**Symptom:** unrelated parallel streams / CF work across the app slows or stalls; latency spikes correlate with bursts of a particular feature.
**Cause:** blocking I/O submitted via no-executor `*Async`/`supplyAsync`, exhausting the few common-pool threads.
**Diagnose:** thread dump (`jstack <pid>` or `jcmd <pid> Thread.print`); look for many `ForkJoinPool.commonPool-worker-*` threads in `WAITING`/`TIMED_WAITING` inside socket reads or JDBC. Export `ForkJoinPool.commonPool().getActiveThreadCount()/getQueuedTaskCount()`.
**Fix:** move blocking work to a dedicated bounded executor; audit all `supplyAsync`/`*Async` for a missing executor argument.

### 9.2 Silent serialization in containers

**Symptom:** "parallel" fan-out has latency ≈ sum of calls, not max; only one worker active.
**Cause:** `availableProcessors()` returns 1–2 under a CPU limit, so common-pool parallelism ≈ 1.
**Diagnose:** log `Runtime.getRuntime().availableProcessors()` and `ForkJoinPool.getCommonPoolParallelism()` at startup.
**Fix:** supply a sized executor, or set `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N`, or upgrade to a JVM/container with correct CPU detection (modern JVMs honor cgroup limits).

### 9.3 Deadlock from blocking inside the same pool

**Symptom:** pipeline hangs forever; no progress.
**Cause:** a stage on pool P calls `.get()`/`.join()` on a future whose completing task also needs a thread from P, but P is full → no thread can run the dependency → deadlock. Classic with single-thread or small pools.
**Diagnose:** thread dump shows pool workers `WAITING` on `CompletableFuture.get`/`join` while the dependency's task sits in the queue.
**Fix:** never block inside a stage; chain with `thenCompose`. If you must block, use a separate pool or `ManagedBlocker`.

### 9.4 Swallowed / lost exceptions

**Symptom:** a failure vanishes; no log, no metric, wrong default surfaces.
**Cause:** a fire-and-forget CF whose exception is never observed (no `exceptionally`/`handle`/`whenComplete`), or an `exceptionally(ex -> null)` that hides it.
**Diagnose:** add a terminal `whenComplete((r, ex) -> { if (ex != null) log.error("stage failed", unwrap(ex)); })` on every top-level pipeline; search for naked `exceptionally`.
**Fix:** always have a terminal handler on top-level futures; log+metric in `whenComplete`. Unlike threads, an unobserved CF exception produces **no** default uncaught-exception output — it's simply stored.

### 9.5 Thread/connection leak from un-cancelled work after timeout

**Symptom:** after adding `orTimeout`, pool/connection usage still grows under slow downstreams.
**Cause:** `orTimeout` completes the CF but the underlying blocking call keeps running and holding a thread/connection.
**Fix:** add **client-side** timeouts (HTTP read timeout, JDBC query timeout) so the work actually aborts; treat CF timeout and client timeout as belt-and-suspenders.

### 9.6 Broken tracing / wrong-user context across async hops

**Symptom:** traces fragment at async boundaries; logs lose correlation IDs; occasionally a request appears to act as another user.
**Cause:** `ThreadLocal` context not propagated onto pool threads.
**Fix:** context-propagating executors (Micrometer Context Propagation / OTel) or explicit context capture (§6.8).

### 9.7 Lost stack traces / unhelpful `CompletionException`

**Symptom:** stack traces point into CF internals, not your call site; cause buried.
**Cause:** async hops discard the submitting thread's stack; exceptions wrapped in `CompletionException`.
**Diagnose/fix:** always `unwrap` before logging; capture context at submission (e.g., attach the originating request id); consider a debugging executor that records submit-site stacks in dev.

### 9.8 Real-world incident pattern

A team put JDBC calls on `CompletableFuture.supplyAsync(...)` without an executor. Under load, the common pool (size 7 on 8 cores) filled with blocked JDBC threads. An unrelated nightly **parallel-stream** report — which also uses the common pool — stalled for minutes; an on-call engineer chased the *report* code for hours before a thread dump revealed dozens of `commonPool-worker` threads blocked in `socketRead0` from the *web* path. Fix: a dedicated `dbPool` for all JDBC CF work, plus a lint rule banning no-executor `*Async`/`supplyAsync`.

---

## 10. Interview drill

**Q1. What's the difference between `Future` and `CompletableFuture`?**
*Model answer:* `Future` (Java 5) is a read-only handle: you can only `get()` (blocking), poll `isDone()`, or `cancel()`. It offers no callbacks, no composition, no manual completion. `CompletableFuture` (Java 8) implements `Future` **and** `CompletionStage`, adding ~40 composition methods (`thenApply`, `thenCompose`, `thenCombine`, `allOf`, `exceptionally`, …), manual completion (`complete`/`completeExceptionally`), timeouts, and functional error handling — enabling non-blocking pipelines.
- *Probe: Why is `Future.get()` problematic in a thread pool?* It parks a pool thread doing no work; under load you need one thread per in-flight blocking call, hitting the thread-per-request scaling wall.
- *Probe: Can you complete a `Future` from outside?* No — only `CompletableFuture` exposes `complete`/`completeExceptionally`, which is what lets you bridge callback APIs.

**Q2. Explain `thenApply` vs `thenCompose`.**
*Model answer:* `thenApply` is `map`: the function returns a plain value, giving `CF<U>`. `thenCompose` is `flatMap`: the function returns another `CompletionStage`, and the result is flattened to `CF<U>` instead of `CF<CF<U>>`. Use `thenCompose` for sequential dependent **async** steps; `thenApply` for synchronous transforms.
- *Probe: What happens if you use `thenApply` where the function returns a `CompletableFuture`?* You get a nested `CF<CF<U>>`, forcing awkward double-unwrapping or blocking.
- *Probe: Is `thenCompose` lazy?* The upstream is already eager; `thenCompose`'s function runs when the upstream completes, then its returned future is awaited — no extra eagerness/laziness beyond normal CF behavior.

**Q3. Which thread runs `thenApply` vs `thenApplyAsync`?** *(senior-signal)*
*Model answer:* `thenApply` (non-async): if upstream is already complete when you register, it runs on the **calling** thread; otherwise on the **thread that completes** the upstream (which could be a library I/O thread). `thenApplyAsync(fn)` runs on the **common ForkJoinPool**. `thenApplyAsync(fn, exec)` runs on **your** executor. The gotcha: non-async continuations can run on an event-loop/I/O thread; heavy or blocking work there stalls that thread.
- *Probe: Why is running on the common pool dangerous for blocking work?* The common pool is small (`cores-1`) and JVM-wide-shared; blocking it starves parallel streams and other CF work everywhere.
- *Probe: How would you guarantee a stage runs on your pool even with plain `*Async`?* Subclass and override `defaultExecutor()`/`newIncompleteFuture()`, or simply always pass an executor.

**Q4. How do you handle errors in a CF pipeline? Compare `exceptionally`, `handle`, `whenComplete`.**
*Model answer:* `exceptionally(fn)` fires only on the exceptional path, returning a same-type fallback (`catch`). `handle(bi)` fires on **both** paths and returns a new value of any type — can recover or transform (`catch`+`map`). `whenComplete(bi)` observes both outcomes for side effects (logging/metrics) but **cannot change** the result; it passes the original through (`finally`).
- *Probe: How are exceptions wrapped?* `get()` → `ExecutionException(cause)`; `join()` and downstream handlers → `CompletionException(cause)`; `CancellationException` is unwrapped. So `unwrap` before inspecting.
- *Probe: What if a `whenComplete` action itself throws?* The new exception is propagated (combined with the original as suppressed), potentially masking the real error — keep it trivial.

**Q5. How do you wait for many futures and collect their results?**
*Model answer:* `allOf(futures...)` returns `CF<Void>` completing when all finish; then `thenApply` to `join()` each (non-blocking since all are done) and collect into a list. `allOf` itself yields no values and completes exceptionally if any input fails — but siblings keep running (no auto-cancel).
- *Probe: How is `allOf` implemented efficiently?* A balanced binary tree of relay completions, O(log n) propagation depth.
- *Probe: First-result-wins?* `anyOf` returns `CF<Object>` with the first completion (success or failure); cast the result.

**Q6. How do timeouts work, and what's the catch?**
*Model answer:* `orTimeout(t, unit)` completes the CF exceptionally with `TimeoutException` if not done in time; `completeOnTimeout(v, t, unit)` completes normally with a default. Both use an internal single-thread `Delayer` scheduler. The catch: they complete the **CF** but do **not** cancel/interrupt the underlying task, which keeps consuming a thread/connection — so pair with client-side timeouts.
- *Probe: Which version added these?* Java 9.
- *Probe: How would you truly cancel the work?* Propagate cancellation into the client (abort the HTTP request / cancel the statement); `cancel(true)` won't interrupt the task.

**Q7. Why is using the common pool for I/O an anti-pattern? How do you avoid it?** *(senior-signal)*
*Model answer:* The common pool is sized `cores-1` and shared JVM-wide (parallel streams, CF defaults). Blocking it starves unrelated work, causing cross-application latency. Avoid by passing a **dedicated bounded executor** to every `*Async`/`supplyAsync` for blocking work, sizing it by Little's Law, and optionally subclassing to override `defaultExecutor()`.
- *Probe: How do you detect starvation in prod?* Thread dump showing many `commonPool-worker` threads blocked in I/O; pool metrics (active/queued counts).
- *Probe: What about containers with CPU limits?* Parallelism may drop to 1, silently serializing; log `availableProcessors()` and set parallelism or supply executors.

**Q8. Design an async service call that fans out to 3 dependencies, with a 200ms budget and a graceful fallback.** *(senior-signal)*
*Model answer:* Submit three `supplyAsync` calls on a dedicated I/O pool; combine via two `thenCombine`s (or `allOf`+join for same-typed). Wrap with `completeOnTimeout(degraded, 200, MILLIS)` for a graceful default, plus `exceptionally`/`handle` for per-call failures, and client-side read timeouts so slow calls actually abort. Add `whenComplete` for metrics. On Java 21+, consider `StructuredTaskScope.ShutdownOnFailure` for clearer code with automatic sibling cancellation.
- *Probe: How is latency determined?* ≈ the slowest branch (critical path), not the sum — assuming true concurrency and a sufficiently sized pool.
- *Probe: Where can this silently serialize?* Undersized pool, container CPU=1 common pool, or an accidental `get()` between submissions.

**Q9. How does CF guarantee memory visibility across threads?**
*Model answer:* CF establishes happens-before: writes by the completing thread are visible to any dependent stage or `get`/`join` observer. So passing values through completion is safe without extra synchronization — but unrelated shared mutable state you touch is not protected.
- *Probe: Internals?* `result` and `stack` are `volatile`; completion uses CAS; dependents fire via `postComplete`.

**Q10. When would you choose Reactor/`Flux` or virtual threads over CompletableFuture?** *(senior-signal)*
*Model answer:* Choose **Reactor/RxJava** for 0..N values, streaming, and backpressure (CF models exactly one value with no backpressure). Choose **virtual threads + structured concurrency** (Java 21+) for request-scoped fan-out where blocking-style code is clearer and you want automatic cancellation. Keep **CF** for single-value composition, bridging callback APIs, and returning composable handles from libraries.
- *Probe: How do CF and Reactor interop?* `Mono.fromFuture(cf)` / `mono.toFuture()`.
- *Probe: Does the existence of virtual threads make CF obsolete?* No — CF remains ideal for composition and event/callback bridging; they're complementary.

**Q11. What's the difference between `whenComplete` and `whenCompleteAsync`, and when does order of dependents matter?**
*Model answer:* `whenComplete` runs on the completing thread; `whenCompleteAsync` runs on the common pool (or supplied executor). Dependent firing order is **not strictly FIFO** (Treiber stack), so never rely on the relative order of independent continuations attached to the same future.

**Q12. How do you propagate tracing/MDC context across async stages?**
*Model answer:* `ThreadLocal` context doesn't follow work onto pool threads. Wrap executors to capture context at submit and restore at run (Micrometer Context Propagation, OTel context-aware executors), or pass context explicitly. Without this, distributed traces fragment and logs lose correlation IDs.

---

## 11. Glossary

- **Asynchronous:** the caller doesn't wait; it's notified of completion later via a future/callback.
- **Blocking / non-blocking:** blocking parks a thread until a result is ready; non-blocking never parks (threads do work or return to the pool).
- **Backpressure:** a mechanism for a slow consumer to signal a fast producer to slow down, preventing unbounded buffering/OOM. CF has none; reactive streams do.
- **Callback / continuation:** a function registered to run when something completes.
- **CAS (compare-and-swap):** an atomic "if value is X set to Y" CPU instruction; basis of lock-free structures.
- **`CompletableFuture`:** a completable, composable future implementing `Future` + `CompletionStage`.
- **`CompletionException`:** unchecked wrapper thrown by `join`/downstream handlers around the original cause.
- **`CompletionStage`:** the composition interface (the `thenX`/`exceptionally`/`handle` methods) without completion/blocking methods.
- **Common pool (`ForkJoinPool.commonPool()`):** the JVM-wide default FJ pool (size `cores-1`) used by parallel streams and default CF async tasks.
- **Critical path:** the longest dependency chain through a graph; determines minimum latency.
- **DAG (directed acyclic graph):** a graph with directed edges and no cycles; the shape of a CF pipeline.
- **Deadlock:** two+ tasks each waiting on the other; with CF, often a stage blocking on a future whose task can't get a thread.
- **`Delayer`:** CF's internal single-thread scheduled executor powering timeouts/delays.
- **`Executor` / `ExecutorService`:** abstractions for running tasks on managed threads.
- **`ExecutionException`:** checked wrapper thrown by `Future.get()` around the original cause.
- **Fan-out / fan-in:** launching multiple parallel tasks (out) and combining their results (in).
- **Fork/Join framework:** Java 7 work-stealing pool for divide-and-conquer parallelism.
- **`Future`:** Java 5 read-only async result handle (`get`/`isDone`/`cancel`).
- **Happens-before:** the JMM ordering guarantee ensuring one thread's writes are visible to another.
- **Hedged request:** sending the same request to multiple backends and taking the first response to cut tail latency.
- **Little's Law:** `L = λ × W` — concurrency = arrival rate × time in system; used to size pools.
- **`ManagedBlocker`:** FJ mechanism to declare a blocking section so the pool spawns compensation threads.
- **MDC (Mapped Diagnostic Context):** SLF4J's per-thread key/value logging context (e.g., request id).
- **Minimal completion stage:** a `CompletionStage` view that forbids completion/blocking methods.
- **Monad / bind / flatMap:** a pattern for sequencing computations in a context; `thenCompose` is CF's flatMap.
- **Promise:** the write side of a future (fulfill/reject); CF is both promise and future.
- **Reactive Streams (Reactor/RxJava):** libraries modeling 0..N async values with backpressure (`Mono`/`Flux`).
- **`ScheduledThreadPoolExecutor`:** an executor that runs tasks after a delay or periodically.
- **Semaphore:** a counter-based concurrency limiter (permits); used for backpressure gates.
- **Stage:** one node in a CF pipeline representing the result of applying a function after upstream completes.
- **Structured concurrency (`StructuredTaskScope`):** Java 21+ model scoping concurrent subtasks with joint join/cancel/error handling.
- **`ThreadLocal`:** per-thread storage; not propagated automatically across async hops.
- **Thread-per-request wall:** the scaling limit when each request consumes a dedicated blocked thread.
- **Treiber stack:** a lock-free CAS-based stack; CF stores dependents in one.
- **Trampolining:** iterating instead of recursing to avoid stack overflow on deep chains.
- **Virtual thread:** JVM-managed lightweight thread (Java 21) that frees its carrier OS thread when blocked.
- **Work-stealing:** idle workers take tasks from busy workers' deques; the FJ pool's scheduling strategy.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Core types:** `Future` (read-only, blocking) → `CompletableFuture` (= `Future` + `CompletionStage` + completion). `CompletionStage` = compose-only interface.

**Create:** `completedFuture(v)`, `failedFuture(e)` (9+), `runAsync`, `supplyAsync` (commonPool by default!), `new CompletableFuture<>()` (manual promise).

**Transform:** `thenApply`=map, `thenAccept`=consume, `thenRun`=run, `thenCompose`=flatMap (async next step).
**Combine 2:** `thenCombine` (both → value), `applyToEither` (first wins). **Combine N:** `allOf` (Void; join each after), `anyOf` (first, `Object`).
**Errors:** `exceptionally`=catch (same type), `handle`=catch+map (any type, both paths), `whenComplete`=finally (observe, no change).
**Timeouts (9+):** `orTimeout`→TimeoutException; `completeOnTimeout`→default value. Neither cancels the underlying task.

**Thread rules (memorize):**
- `thenX` (sync): completing thread (or caller if already done).
- `thenXAsync(fn)`: common pool.
- `thenXAsync(fn, exec)`: your pool.

**Key numbers/defaults:** common pool size = `availableProcessors() - 1` (min 1); `maximumSpares` = 256; timeouts use single-thread `Delayer`. Exception wrappers: `get`→`ExecutionException`, `join`/downstream→`CompletionException`.

**Decision rules:**
- Blocking work → **always** pass your own bounded executor (never the common pool).
- Next step is async → `thenCompose`, not `thenApply`.
- 2 typed results → `thenCombine`; N same-typed → `allOf`+join.
- Slowness = error → `orTimeout`; slowness = degrade → `completeOnTimeout`.
- One value, compose → CF; many values/backpressure → Reactor; blocking fan-out (21+) → virtual threads + structured concurrency.
- Always add a terminal `whenComplete`/`handle` to log/metric failures (CF won't print unobserved exceptions).
- Bound fan-out concurrency (semaphore/bounded pool) — CF has no backpressure.

**Top anti-patterns:** blocking the common pool; `.get()`/`.join()` inside a stage; `thenApply` returning a CF; relying on `cancel(true)` to stop work; ignoring `ThreadLocal` propagation; forgetting `allOf` gives `Void`.

### 12.2 Self-test (no answers — recall actively)

1. Trace exactly which thread executes each stage in `supplyAsync(f).thenApply(g).thenApplyAsync(h).thenApplyAsync(k, myPool)`, assuming `f` runs on the common pool — and explain why.
2. You add `.orTimeout(1, SECONDS)` and the latency p99 improves but your DB connection pool still exhausts under load. Why, and what else must you do?
3. Write the minimal code to wait for a `List<CompletableFuture<T>>`, collect the results in order, and fail fast if any one fails — then modify it to instead collect only the successful results.
4. Explain how to make every `*Async` (even without an explicit executor) in a pipeline use your application pool, without passing the executor at every call site.
5. Your distributed traces break at each async boundary and one in a thousand requests appears to act as the wrong user. Diagnose both symptoms and give the fix.
6. Compare `handle`, `whenComplete`, and `exceptionally` by what each can see, can change, and the `try/catch/finally` keyword it most resembles — then state which one can accidentally mask the original exception and how.
7. On a 1-vCPU container your fan-out of 4 calls takes 4× a single call's latency instead of ≈1×. Explain the root cause and two distinct fixes.

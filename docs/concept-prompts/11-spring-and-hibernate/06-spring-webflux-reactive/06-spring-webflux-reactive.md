# Spring WebFlux & Reactive

> An exhaustive engineering-handbook chapter on the reactive programming model on the JVM, Reactive Streams, Project Reactor, and Spring WebFlux — from first principles to deep internals, production operation, and interview mastery.

---

## 1. Overview & where it fits

**Spring WebFlux** is Spring's *reactive*, non-blocking web framework. It is the alternative to **Spring MVC** (the classic servlet-based stack). Both let you write HTTP controllers and services in Spring, but they sit on fundamentally different I/O foundations:

- **Spring MVC** is **blocking** and **thread-per-request**: one OS thread is dedicated to a request from the moment it arrives until the response is fully written. While that thread waits on a database call or a downstream HTTP call, it is *parked* — consuming a thread (and ~1 MB of stack) but doing no work.
- **Spring WebFlux** is **non-blocking** and **event-loop-based**: a tiny pool of threads (typically `number of CPU cores`) handles thousands of concurrent connections by never blocking. When an operation must wait (I/O), the thread is released to serve other requests; a callback resumes the work when data is ready.

**The problem it solves.** The thread-per-request model breaks down under **high concurrency with high latency I/O**. If you have 10,000 simultaneous clients each waiting 200 ms on a slow downstream, the blocking model needs ~10,000 threads parked simultaneously. That is enormous memory (gigabytes of stack), heavy context-switching overhead, and thread-pool exhaustion that manifests as request queueing and cascading timeouts. The reactive model decouples *concurrency* (number of in-flight requests) from *thread count*, so a handful of threads can multiplex tens of thousands of connections.

> **Concept — context switch (beginner aside):** When the OS scheduler swaps the CPU from running one thread to another, it must save the first thread's registers/stack pointer and restore the second's. This costs roughly 1–5 microseconds and pollutes CPU caches. With thousands of threads, the scheduler spends a meaningful fraction of CPU just switching, not doing useful work.

**When you reach for it.** You reach for WebFlux (or reactive generally) when:
- You are building an **I/O-bound** service (an API gateway, BFF/aggregator, proxy, streaming endpoint) that fans out to many slow downstreams.
- You need to handle **very high connection counts** with limited threads (e.g. SSE/WebSocket fan-out, long-polling, server push).
- You want **end-to-end streaming with backpressure** (large result sets, file streams, event feeds) where you cannot afford to buffer everything in memory.
- Your entire stack is already reactive (R2DBC reactive driver, reactive Kafka/Mongo/Redis clients) so there is no blocking link in the chain.

**When you should NOT.** You should generally *avoid* reactive (this is a central theme of this doc) when:
- Your workload is **CPU-bound** (reactive does nothing for CPU work — it only helps hide I/O latency).
- Your dependencies are **blocking** (a JDBC driver, a blocking SDK) — you'll be forced to offload to a thread pool and lose the benefit.
- Your team isn't fluent in reactive — the debuggability and cognitive cost is real.
- **Virtual threads (Project Loom, JDK 21+)** are available. Virtual threads give you most of the scalability of reactive *while keeping the simple, blocking, imperative programming model*. For a large class of applications, virtual threads have made reactive a harder sell.

**One-paragraph mental model.** Think of reactive programming as building an **assembly line of data transformations** (a pipeline) that does *nothing* until someone subscribes to it (it is *lazy* and *declarative*). Data flows down the pipeline as discrete *events* — items, an error, or a completion signal — and the consumer can signal *how much* it is willing to receive (*backpressure*). The whole pipeline runs on a small set of non-blocking threads driven by an *event loop*: instead of a thread sitting and waiting on I/O, the thread registers interest in an event ("tell me when this socket has data") and goes off to do other work; a callback fires when the event is ready. WebFlux is Spring's machinery for wiring HTTP requests into such pipelines built with **Project Reactor**'s `Mono` and `Flux` types.

---

## 2. Foundations from first principles

### 2.1 Blocking vs non-blocking I/O — the root distinction

Let's define the words precisely, because casual usage conflates them.

> **Blocking call:** a method call that does not return until the operation completes. `InputStream.read()` on a socket blocks the calling thread until bytes arrive (or timeout/EOF). The thread can do nothing else meanwhile.

> **Non-blocking call:** a method that returns immediately, possibly reporting "no data yet." The classic example is a socket in non-blocking mode: `read()` returns `-1`/`EWOULDBLOCK` right away if no data is buffered, so the thread can move on.

> **Synchronous vs asynchronous:** *Synchronous* means the caller waits (in code order) for the result. *Asynchronous* means the result is delivered later, via a callback / future / event. These axes are orthogonal to blocking: you can have asynchronous-but-blocking (a future you then `.get()` on) or non-blocking-but-synchronous-feeling code (reactive operators).

> **I/O multiplexing (`select`/`poll`/`epoll`/`kqueue`):** an OS facility where one thread asks the kernel "watch these N file descriptors and wake me when *any* of them is ready to read/write." `epoll` (Linux) and `kqueue` (BSD/macOS) scale to tens of thousands of descriptors efficiently (O(ready) not O(N)). This is the kernel primitive that makes event-loop servers possible. Java exposes it via **NIO** (`java.nio.channels.Selector`).

### 2.2 The thread-per-request model and its limits

In Spring MVC on Tomcat:
1. A connector thread accepts a connection.
2. A worker thread from a fixed pool (default Tomcat `maxThreads = 200`) is assigned to the request.
3. That thread runs your controller, your service, your `repository.findById()` (JDBC), serializes the response, and writes it.
4. While JDBC waits on the DB socket, the thread is **blocked** — alive, holding ~512 KB–1 MB of stack, but idle.

Throughput in this model is bounded by **Little's Law**:

> **Little's Law:** `L = λ × W`, where `L` = average number of concurrent requests in the system, `λ` = arrival rate (req/s), `W` = average time in system (seconds). Rearranged: `λ = L / W`. If each request takes `W = 0.2 s` and you have `L = 200` threads, max throughput is `λ = 1000 req/s`. To go faster you need more threads (more memory, more context switching) or lower latency.

The blocking model wastes the parked threads. Reactive's bet: replace "200 threads, most parked" with "8 threads, never parked," and let `L` (concurrency) grow far beyond the thread count.

### 2.3 The event loop

> **Event loop:** a single thread running an infinite loop that (a) asks the OS selector "which sockets are ready?", (b) for each ready socket, runs the small piece of work associated with it (read bytes, decode, hand to a handler, write bytes), (c) repeats. Because the work per event is tiny and never blocks, one event-loop thread can service thousands of connections. Servers like **Nginx**, **Node.js**, and **Netty** are built this way.

> **Netty (beginner aside):** Netty is the high-performance, asynchronous, event-driven network framework that WebFlux uses by default (via the Reactor Netty bridge). It implements the event loop, manages a pool of `EventLoop` threads (usually `2 × cores`), and provides a pipeline of `ChannelHandler`s for codecs (HTTP, WebSocket, TLS).

The **cardinal rule of event loops**: *never block an event-loop thread.* If you do, every connection assigned to that loop stalls. This single rule is the source of most reactive pain (see §6.3, §9).

### 2.4 Callbacks, futures, and why we want something better

The naive way to do async is **callbacks**:

```java
httpClient.get("/a", responseA ->
  db.lookup(responseA.id, record ->
    httpClient.post("/b", record, responseB ->
      respond(responseB))));   // "callback hell" — nesting, no error path, no composition
```

`CompletableFuture` (Java 8) improves composition:

```java
client.getAsync("/a")
  .thenCompose(a -> db.lookupAsync(a.id))
  .thenCompose(r -> client.postAsync("/b", r))
  .thenAccept(this::respond)
  .exceptionally(ex -> { log.error("failed", ex); return null; });
```

But `CompletableFuture` represents **exactly one** future value and has **no backpressure** — it can't model a *stream* of many values where the consumer controls the rate. That is what **Reactive Streams** adds.

### 2.5 Backpressure — the key idea reactive adds

> **Backpressure:** the mechanism by which a slow *consumer* tells a fast *producer* to slow down, so the producer doesn't overwhelm the consumer's memory. In a pull-based reactive stream, the consumer *requests* N items; the producer is only allowed to emit up to N. Without backpressure, a fast producer (say, a Kafka topic firehose) feeding a slow consumer (say, a DB write) would buffer unboundedly and OOM.

Reactive Streams implements backpressure with a *request(n)* protocol on the `Subscription` (see §2.6). This is the defining feature that separates Reactive Streams from plain async/futures.

### 2.6 Reactive Streams — the specification

**Reactive Streams** is a *minimal interoperability specification* (4 interfaces, in package `org.reactivestreams`, now also `java.util.concurrent.Flow` in JDK 9+). It standardizes async stream processing with non-blocking backpressure so libraries (Reactor, RxJava, Akka Streams, Mongo driver) can interoperate.

The four interfaces:

```java
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

public interface Subscriber<T> {
    void onSubscribe(Subscription s);  // called once, gives you the control handle
    void onNext(T t);                  // 0..N times — a data item
    void onError(Throwable t);         // terminal — error
    void onComplete();                 // terminal — success, no more items
}

public interface Subscription {
    void request(long n);   // demand: "I can handle n more items" (backpressure)
    void cancel();          // "stop, I'm done"
}

public interface Processor<T, R> extends Subscriber<T>, Publisher<R> {}
// a Processor is both: it consumes one stream and produces another (a pipeline stage)
```

**The protocol (the contract you must honor):**
- A `Subscriber` receives **exactly one** `onSubscribe`, then **zero or more** `onNext`, then **at most one** of `onError` **or** `onComplete` (terminal, mutually exclusive). After a terminal signal, no more signals.
- A `Publisher` must not emit more `onNext` than the cumulative `request(n)` demand. (i.e. it must respect backpressure.)
- Signals must be **serialized** — no concurrent calls to the same `Subscriber`'s methods.
- `request(n)` with `n <= 0` is a spec violation → `onError(IllegalArgumentException)`.

**The flow, step by step:**
1. Consumer calls `publisher.subscribe(subscriber)`.
2. Publisher calls `subscriber.onSubscribe(subscription)` — handing over the control knob.
3. Subscriber calls `subscription.request(n)` — *now* the publisher is allowed to emit up to `n`.
4. Publisher emits `onNext` up to `n` times, as data becomes available.
5. Subscriber may `request` more, or `cancel`.
6. Eventually publisher signals `onComplete` or `onError`.

> **Hot vs cold (defined here, expanded in §3.5):** A **cold** publisher starts producing *per subscriber*, from the beginning, when subscribed (like a file you re-read each time). A **hot** publisher emits regardless of subscribers; late subscribers miss earlier items (like a live broadcast).

### 2.7 Project Reactor — the implementation Spring uses

Spring WebFlux is built on **Project Reactor**, a Reactive Streams implementation by VMware/Pivotal. Reactor gives you two `Publisher` types that carry semantic meaning:

> **`Mono<T>`** — a publisher of **0 or 1** items (then completes or errors). Models a single async result: "fetch one user," "save and return," "a request that returns nothing (`Mono<Void>`)."

> **`Flux<T>`** — a publisher of **0 to N** items (a stream), then completes or errors. Models a sequence: "all orders for a user," "an SSE event stream," "rows from a query."

Both are **lazy**: declaring a `Mono`/`Flux` builds a *recipe*; **nothing runs until something subscribes**. In WebFlux, the framework subscribes for you when the HTTP response is being produced. (Forgetting this — declaring a publisher and never subscribing — is the #1 beginner bug; see §6.3.)

```java
Mono<User> user = userRepo.findById(id);   // nothing has run yet — just a recipe
// ... if no one subscribes, the DB is never queried
```

---

## 3. How it works internally

This is the heart of the document. We trace the full lifecycle: assembly → subscription → request → emission → termination, then the WebFlux request lifecycle, then schedulers and hot/cold.

### 3.1 Assembly time vs subscription time vs runtime

Reactor distinguishes three phases:

1. **Assembly time:** when you call operators (`.map`, `.filter`, `.flatMap`) you are *assembling* a chain of operator objects. Each operator wraps the previous publisher. No data flows. Side effects in operator *lambdas* don't run yet, but side effects in the *assembly code itself* (e.g. calling a blocking method directly to produce the publisher) DO run — a classic bug:

   ```java
   // BUG: blockingCall() runs at assembly, on the calling thread, eagerly
   Mono<String> m = Mono.just(blockingCall());
   // FIX: defer it so it runs at subscription time, lazily
   Mono<String> m2 = Mono.fromCallable(() -> blockingCall());
   ```

2. **Subscription time:** when `.subscribe()` is called, Reactor walks *up* the chain from the final subscriber to the source, calling `onSubscribe` and wiring `Subscription`s. Subscription propagates **upstream**; data flows **downstream**.

3. **Runtime:** the source emits `onNext`/`onComplete`/`onError` downstream through each operator.

### 3.2 The subscription handshake — step by step

Take this chain:

```java
Flux.range(1, 5)          // source: emits 1,2,3,4,5
    .map(i -> i * 2)      // operator
    .filter(i -> i > 4)   // operator
    .subscribe(System.out::println);  // terminal subscriber
```

Internally, on `subscribe`:
1. The `subscribe` consumer is wrapped in a `LambdaSubscriber`.
2. `filter` creates a `FilterSubscriber`, subscribes it upstream to `map`.
3. `map` creates a `MapSubscriber`, subscribes it upstream to `range`.
4. `range` (the source) calls `mapSubscriber.onSubscribe(rangeSubscription)`.
5. `onSubscribe` propagates down: `map → filter → lambda`. The `LambdaSubscriber.onSubscribe` calls `subscription.request(Long.MAX_VALUE)` by default (unbounded demand — the lambda subscribe has no backpressure).
6. `request(Long.MAX_VALUE)` propagates **up** through filter → map → range's subscription.
7. `range` now emits `onNext(1)` → `map` doubles to 2 → `filter` drops (≤4) → range emits `onNext(2)` → 4 → dropped → 3 → 6 → passes → printed... etc.
8. After 5 emitted, `range` calls `onComplete`, propagating down to the lambda subscriber, which finishes.

> **Key insight:** **demand flows up, data flows down.** Operators in the middle are both `Subscriber` (to upstream) and `Publisher` (to downstream) — i.e. they are `Processor`-like. They can *transform demand*: `flatMap` requests its concurrency-worth from upstream; `buffer(10)` requests 10 to fill a buffer; `limitRate(n)` reshapes a `request(MAX)` into smaller chunks.

### 3.3 Operators as a chain of subscribers (data/control flow)

Every operator is implemented as a `Subscriber` that wraps a downstream `Subscriber`. When `onNext(item)` arrives:
- `map` calls the mapper, then calls `downstream.onNext(result)`.
- `filter` calls the predicate; if true `downstream.onNext(item)`, else it silently `request(1)` more from upstream (to keep demand satisfied) without emitting downstream.
- `flatMap` subscribes to an inner publisher per item and merges results (see §3.4).

Errors short-circuit: an exception in a `map` lambda is caught by the operator and turned into `onError`, which propagates downstream, **cancelling** the upstream subscription. This is why a single bad item tears down the whole stream unless you handle it with `onErrorResume`/`onErrorContinue`.

### 3.4 `flatMap` vs `map` vs `concatMap` — async composition internals

> **`map(fn)`** — synchronous 1:1 transform. `T → R`. No new publisher.

> **`flatMap(fn)`** — asynchronous 1:N transform. `T → Publisher<R>`. For each upstream item it *subscribes* to the returned inner publisher and **merges** all inner emissions into one output stream. Inner publishers run **concurrently** (default concurrency 256, configurable). Ordering is **NOT preserved** (whichever inner emits first wins). This is how you fan out parallel I/O.

> **`concatMap(fn)`** — like `flatMap` but **sequential**: subscribes to the next inner only after the previous completes. Ordering **preserved**, concurrency = 1.

> **`flatMapSequential(fn)`** — runs inners concurrently (like flatMap) but **emits results in original order** by buffering out-of-order results.

> **`switchMap(fn)`** — subscribes to the new inner and **cancels the previous** inner on each new upstream item. Used for "latest wins" (e.g. type-ahead search: cancel the in-flight query when a new keystroke arrives).

Internal mechanics of `flatMap`:
1. Requests `concurrency` items from upstream.
2. For each upstream item, calls `fn`, gets an inner `Publisher`, subscribes an `InnerSubscriber`.
3. Inner emissions are routed to a shared queue, drained to the downstream by a serialized drain loop (so the downstream never sees concurrent `onNext`).
4. When an inner completes, it requests one more from upstream to refill the concurrency window.
5. When upstream completes AND all inners complete, downstream `onComplete`.

```java
// Fan out 3 parallel HTTP calls and combine — flatMap concurrency in action
Flux.fromIterable(userIds)
    .flatMap(id -> webClient.get().uri("/users/{id}", id)
                            .retrieve().bodyToMono(User.class), 8) // concurrency=8
    .collectList()    // gather into Mono<List<User>>
    .subscribe();
```

### 3.5 Hot vs cold — internals

- **Cold:** each `subscribe` triggers a fresh execution. `Flux.range`, `WebClient` responses, `repository.findAll()` are cold — subscribe twice = two DB queries.
- **Hot:** emits independently of subscribers. Created via `share()`, `publish().connect()`, `Sinks`, or by `.cache()`.

> **`share()`:** turns a cold `Flux` into a hot one that multicasts to multiple subscribers from a single upstream subscription, ref-counted (upstream subscribed on first subscriber, cancelled when last leaves).

> **`Sinks` (Reactor 3.4+):** programmatic, thread-safe way to push items into a `Flux`/`Mono` imperatively (replacing the deprecated `Processor`s and `EmitterProcessor`). E.g. `Sinks.many().multicast().onBackpressureBuffer()`. Used to bridge imperative event sources (a callback, a listener) into a reactive stream.

```java
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
Flux<String> events = sink.asFlux();           // hot stream
// elsewhere, push events imperatively:
sink.tryEmitNext("user-logged-in");            // returns EmitResult, non-throwing
```

### 3.6 Schedulers — where work actually runs

> **Scheduler:** Reactor's abstraction over a thread pool / execution context. Operators by default run on **whatever thread emitted the signal** (often the event loop). You change threads with `subscribeOn` and `publishOn`.

The built-in schedulers (`reactor.core.scheduler.Schedulers`):

| Scheduler | Backing | Use for | Default size |
|---|---|---|---|
| `Schedulers.parallel()` | Fixed pool, daemon threads | CPU-bound work | `= number of CPU cores` |
| `Schedulers.boundedElastic()` | Elastic, bounded pool | **Blocking I/O offload** (legacy/JDBC) | `10 × cores` threads, queue `100k` tasks |
| `Schedulers.single()` | One reused thread | Low-latency one-off, serialized work | 1 |
| `Schedulers.immediate()` | Caller thread (no switch) | No-op scheduler | — |
| `Schedulers.fromExecutor(e)` | Your executor | Custom pools | — |

> **`subscribeOn(scheduler)`:** affects where the **subscription and the source emission** happen — i.e. the *whole upstream* runs on that scheduler, regardless of where in the chain you place it. There is effectively one `subscribeOn` per chain that matters (the closest to the source).

> **`publishOn(scheduler)`:** changes the thread for everything **downstream** of it, from that point on. You can use multiple `publishOn`s to move work between pools (e.g. do I/O on boundedElastic, then CPU work on parallel).

```java
Mono.fromCallable(() -> jdbcRepo.find(id))     // BLOCKING JDBC call
    .subscribeOn(Schedulers.boundedElastic())  // run it on the blocking-safe pool
    .map(this::transform)                       // runs on boundedElastic thread
    .publishOn(Schedulers.parallel())           // switch to CPU pool
    .map(this::heavyCompute);                    // CPU work, won't starve event loop
```

> **Why `boundedElastic` for blocking?** It is *bounded* (won't spawn infinite threads → won't OOM) and *elastic* (grows/shrinks with load) and its threads are *idle-timed-out*. It exists precisely so you can safely island a blocking call off the event loop. But note: this re-introduces the thread-per-blocking-call cost — if your whole app blocks, you've just rebuilt thread-per-request with extra steps.

### 3.7 The WebFlux HTTP request lifecycle (Netty path)

Step by step, what happens when a request hits a WebFlux app on Reactor Netty:

1. **Accept:** Netty's boss event loop accepts the TCP connection, assigns it to a worker `EventLoop` thread.
2. **Decode:** As bytes arrive, Netty's HTTP codec (`HttpServerCodec`) decodes them into an HTTP request *on the event-loop thread*.
3. **Adapt:** Reactor Netty bridges to Spring via `ReactorHttpHandlerAdapter`, producing a `ServerHttpRequest`/`ServerHttpResponse`.
4. **`HttpHandler` → `WebHandler`:** The request enters Spring's `WebHandler` chain: `WebFilter`s run (reactive equivalent of servlet filters), then the `DispatcherHandler` (reactive analog of `DispatcherServlet`).
5. **Mapping:** `DispatcherHandler` consults `HandlerMapping`s to find the controller method (annotation-based `@RequestMapping`) or functional `RouterFunction`.
6. **Argument resolution:** `HandlerAdapter` resolves arguments (`@RequestBody` is decoded *reactively* via `HttpMessageReader` into a `Mono`/`Flux`; the body may not be fully read yet).
7. **Invoke:** Your controller returns a `Mono<T>`/`Flux<T>` (a recipe). **It has NOT executed yet.**
8. **Subscribe:** The framework subscribes to your returned publisher. Now the pipeline runs — on event-loop threads (unless you used `subscribeOn`/`publishOn`).
9. **Encode & write:** As items emit, `HttpMessageWriter` encodes them (JSON via Jackson, or SSE frames) and writes to the response with **backpressure honored against the TCP socket's writability** — Netty only requests more from your `Flux` when the socket can accept more bytes. This is true end-to-end backpressure: a slow client throttles your producer.
10. **Complete:** `onComplete` flushes and closes; `onError` is routed to `WebExceptionHandler`s (default produces an error response).

> **Threading note:** In the happy path, *everything in steps 7–9 runs on the event-loop thread*. That's why a blocking call there is catastrophic — it stalls the loop and every connection on it. The default Reactor Netty has `2 × cores` event-loop threads (e.g. 16 on an 8-core box). Block one for 100 ms and you've stalled ~1/16 of your serving capacity for 100 ms.

### 3.8 Context propagation (no ThreadLocal)

> **The ThreadLocal problem:** Classic Spring uses `ThreadLocal`s for request-scoped state (security context, MDC for logging, transaction). In reactive, a request hops across threads (event loop → boundedElastic → back), so `ThreadLocal`s break — they're bound to a thread, not a request.

Reactor solves this with the **`Context`** (immutable key-value map carried *with the subscription*, propagated upstream):

```java
Mono.deferContextual(ctx -> {
    String userId = ctx.get("userId");
    return service.doWork(userId);
})
.contextWrite(Context.of("userId", "u-123"));  // writes flow UP to deferContextual
```

`contextWrite` is placed *downstream* but writes *upstream* (because subscription flows up). Spring Security's reactive support (`ReactiveSecurityContextHolder`) and Micrometer's context-propagation library bridge `Context` ↔ `ThreadLocal` for MDC/tracing.

---

## 4. The complete toolkit

### 4.1 Core Reactor types & factory methods

| Factory | Type | Purpose |
|---|---|---|
| `Mono.just(v)` | `Mono<T>` | Emit a known value (eager — value captured at assembly) |
| `Mono.justOrEmpty(v)` | `Mono<T>` | Emit value or empty if null |
| `Mono.empty()` | `Mono<T>` | Complete with no value |
| `Mono.error(ex)` | `Mono<T>` | Error immediately |
| `Mono.fromCallable(sup)` | `Mono<T>` | Lazy: run supplier at subscribe (wrap blocking here) |
| `Mono.fromFuture(cf)` | `Mono<T>` | Adapt a `CompletableFuture` |
| `Mono.defer(sup)` | `Mono<T>` | Lazily build a `Mono` per subscriber |
| `Mono.zip(a, b, ...)` | `Mono<Tuple>` | Combine multiple Monos when all complete |
| `Flux.just(...)` | `Flux<T>` | Emit known values |
| `Flux.fromIterable(it)` | `Flux<T>` | From a collection |
| `Flux.fromStream(s)` | `Flux<T>` | From a Java Stream |
| `Flux.range(start, n)` | `Flux<Integer>` | Integer range |
| `Flux.interval(d)` | `Flux<Long>` | Tick every duration (hot-ish, on `parallel`) |
| `Flux.generate(...)` | `Flux<T>` | Synchronous, one-by-one, backpressure-aware generator |
| `Flux.create(...)` | `Flux<T>` | Async multi-threaded emission with backpressure strategy |
| `Flux.merge(p1, p2)` | `Flux<T>` | Interleave multiple publishers (concurrent) |
| `Flux.concat(p1, p2)` | `Flux<T>` | Sequence publishers in order |

### 4.2 Transformation operators

| Operator | Effect |
|---|---|
| `map(fn)` | 1:1 sync transform |
| `flatMap(fn, [concurrency])` | 1:N async, merged, unordered |
| `concatMap(fn)` | 1:N async, sequential, ordered |
| `flatMapSequential(fn)` | concurrent execution, ordered output |
| `switchMap(fn)` | cancel-previous, latest wins |
| `filter(pred)` | drop items failing predicate |
| `cast(Class)` | type cast |
| `handle(biCons)` | map + filter + custom emit/error |
| `index()` | pair each item with its index |
| `flatMapMany(fn)` | `Mono<T>` → `Flux<R>` |

### 4.3 Combining operators

| Operator | Effect |
|---|---|
| `zip` / `zipWith` | combine items pairwise from multiple sources |
| `merge` | interleave concurrently |
| `concat` | sequence in order |
| `combineLatest` | emit on any source change with latest of each |
| `Mono.zip(a,b)` | wait for both, combine into tuple (parallel) |
| `then(other)` | ignore upstream values, run other on complete |
| `thenMany(flux)` | same but continue with a Flux |
| `startWith` / `concatWith` | prepend / append |

### 4.4 Error-handling operators

| Operator | Effect |
|---|---|
| `onErrorReturn(v)` | fall back to a static value on error |
| `onErrorResume(fn)` | fall back to another publisher on error |
| `onErrorMap(fn)` | translate the exception type |
| `onErrorContinue(biCons)` | skip the bad item, keep the stream alive (use with care; breaks operator fusion / can be surprising) |
| `retry(n)` | resubscribe up to n times on error |
| `retryWhen(spec)` | advanced retry (backoff, jitter, filter) |
| `timeout(d)` | error if no signal within d |
| `doOnError(c)` | side-effect on error (logging) without consuming it |

### 4.5 Scheduling & utility operators

| Operator | Effect |
|---|---|
| `subscribeOn(s)` | run whole upstream on scheduler s |
| `publishOn(s)` | run downstream on scheduler s |
| `delayElements(d)` | delay each emission |
| `delaySubscription(d)` | delay the subscribe |
| `limitRate(n)` | reshape demand into batches of n |
| `buffer(n)` / `bufferTimeout(n,d)` | batch items into Lists |
| `window(n)` | split into sub-Fluxes |
| `sample(d)` | emit most recent per period |
| `cache([ttl])` | turn cold into hot, replay to late subscribers |
| `share()` | hot multicast, ref-counted |
| `cancelOn(s)` / `doFinally(c)` | cleanup hooks |
| `log()` | log all signals (debug) |
| `checkpoint("label")` | add a traceable assembly marker for error stack traces |
| `tap(...)` | observation hooks (Micrometer) |
| `name("x").metrics()` | expose Micrometer metrics for the stream |

### 4.6 Terminal / consumption

| Method | Effect |
|---|---|
| `subscribe(...)` | trigger execution (various overloads for onNext/onError/onComplete) |
| `block()` / `blockOptional()` | **BLOCK** the calling thread and get the value (tests / main only, never in WebFlux thread) |
| `blockFirst()` / `blockLast()` | block for Flux |
| `toFuture()` | adapt to `CompletableFuture` |
| `collectList()` | `Flux<T>` → `Mono<List<T>>` |
| `reduce` / `collect` | aggregate |
| `as(fn)` | apply a transformation function to the whole publisher |

### 4.7 Spring WebFlux web toolkit

| Component | Purpose |
|---|---|
| `@RestController` + `@RequestMapping` | annotated controllers (same annotations as MVC; return `Mono`/`Flux`) |
| `RouterFunction` + `HandlerFunction` | **functional** endpoint style (no annotations) |
| `WebClient` | the reactive, non-blocking HTTP client (replaces `RestTemplate`) |
| `WebFilter` | reactive filter (cross-cutting concerns) |
| `WebExceptionHandler` | global reactive error handling |
| `ServerWebExchange` | reactive request/response context |
| `@RestControllerAdvice` + `@ExceptionHandler` | annotated error handling (returns `Mono`) |
| `ServerSentEvent<T>` / `Flux<ServerSentEvent>` | SSE streaming |
| `WebSocketHandler` | reactive WebSocket |
| `ReactiveCrudRepository` (Spring Data R2DBC/Mongo) | reactive data access returning `Mono`/`Flux` |
| `DatabaseClient` (R2DBC) | reactive SQL execution |
| `StepVerifier` (reactor-test) | testing reactive streams |
| `WebTestClient` | end-to-end WebFlux testing |

### 4.8 Key configuration properties (Spring Boot)

| Property | Meaning | Default |
|---|---|---|
| `spring.main.web-application-type` | `reactive` / `servlet` / `none` | auto-detected (WebFlux on classpath → reactive) |
| `server.netty.connection-timeout` | Netty connection idle timeout | (unset / platform) |
| `spring.codec.max-in-memory-size` | max buffered bytes for decoding a body | `256KB` |
| `spring.webflux.base-path` | context path | `/` |
| `reactor.netty.ioWorkerCount` (sys prop) | event-loop worker threads | `max(4, 2×cores)` |
| `reactor.schedulers.defaultBoundedElasticSize` (sys prop) | boundedElastic max threads | `10 × cores` |
| `reactor.schedulers.defaultBoundedElasticQueueSize` | boundedElastic queue | `100000` |

> **Version note:** Exact defaults are version-specific (Reactor 3.4/3.5/3.6, Spring Boot 2.x vs 3.x, Netty version). Confirm against your dependency versions; the above reflect common Reactor 3.4+/Spring Boot 2.6+ defaults.

---

## 5. Code examples by use case

### 5.1 Simple annotated reactive controller + R2DBC

```java
@RestController
@RequestMapping("/users")
class UserController {
    private final UserRepository repo;   // ReactiveCrudRepository<User, Long> (R2DBC)
    UserController(UserRepository repo) { this.repo = repo; }

    // Returns a Mono — single user or 404; framework subscribes and writes JSON
    @GetMapping("/{id}")
    Mono<ResponseEntity<User>> getUser(@PathVariable Long id) {
        return repo.findById(id)
                   .map(ResponseEntity::ok)
                   .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // Returns a Flux — streams all users; with R2DBC this streams rows as they arrive
    @GetMapping
    Flux<User> all() { return repo.findAll(); }

    // Reactive request body decoding — @RequestBody Mono<User> is decoded lazily
    @PostMapping
    Mono<User> create(@RequestBody Mono<User> body) {
        return body.flatMap(repo::save);   // save returns Mono<User>
    }
}
```

### 5.2 Functional (RouterFunction) style

```java
@Configuration
class UserRoutes {
    @Bean
    RouterFunction<ServerResponse> routes(UserHandler h) {
        return route(GET("/users/{id}"), h::getUser)
              .andRoute(POST("/users"), h::createUser);
    }
}

@Component
class UserHandler {
    private final UserRepository repo;
    UserHandler(UserRepository repo) { this.repo = repo; }

    Mono<ServerResponse> getUser(ServerRequest req) {
        Long id = Long.valueOf(req.pathVariable("id"));
        return repo.findById(id)
                   .flatMap(u -> ServerResponse.ok().bodyValue(u))
                   .switchIfEmpty(ServerResponse.notFound().build());
    }

    Mono<ServerResponse> createUser(ServerRequest req) {
        return req.bodyToMono(User.class)
                  .flatMap(repo::save)
                  .flatMap(saved -> ServerResponse
                        .created(URI.create("/users/" + saved.getId()))
                        .bodyValue(saved));
    }
}
```

### 5.3 Aggregator / BFF — parallel fan-out with WebClient

This is the *canonical* place reactive wins: gather data from several slow downstreams concurrently on a tiny thread pool.

```java
@Service
class ProfileService {
    private final WebClient web;
    ProfileService(WebClient.Builder b) {
        this.web = b.baseUrl("https://internal").build();
    }

    Mono<Profile> buildProfile(String userId) {
        // Three downstream calls, all non-blocking, all in flight concurrently
        Mono<User> user      = get("/users/" + userId, User.class);
        Mono<List<Order>> orders = getList("/orders?user=" + userId, Order.class);
        Mono<Prefs> prefs    = get("/prefs/" + userId, Prefs.class)
                                  .onErrorReturn(Prefs.defaults()); // tolerate failure

        // zip waits for all three, then combines — total latency ≈ slowest call, not sum
        return Mono.zip(user, orders, prefs)
                   .map(t -> new Profile(t.getT1(), t.getT2(), t.getT3()))
                   .timeout(Duration.ofSeconds(2))            // overall SLA
                   .onErrorResume(TimeoutException.class,
                                  e -> Mono.error(new GatewayTimeoutException()));
    }

    private <T> Mono<T> get(String uri, Class<T> type) {
        return web.get().uri(uri).retrieve().bodyToMono(type);
    }
    private <T> Mono<List<T>> getList(String uri, Class<T> type) {
        return web.get().uri(uri).retrieve().bodyToFlux(type).collectList();
    }
}
```

### 5.4 Server-Sent Events (SSE) streaming

```java
@GetMapping(value = "/prices/{symbol}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<ServerSentEvent<Price>> priceStream(@PathVariable String symbol) {
    return priceFeed.stream(symbol)              // a hot Flux<Price>
        .map(p -> ServerSentEvent.<Price>builder()
                    .id(String.valueOf(p.seq()))
                    .event("price")
                    .data(p)
                    .build())
        .doOnCancel(() -> log.info("client disconnected from {}", symbol));
}
```

Because the response is a `Flux` and the transport honors socket writability, a slow client naturally backpressures the feed (you may want `onBackpressureLatest()`/`onBackpressureDrop()` for live data where stale items are useless).

### 5.5 Bridging a blocking JDBC call safely

When you *must* use a blocking dependency inside WebFlux:

```java
@Service
class LegacyService {
    private final JdbcTemplate jdbc;   // BLOCKING
    LegacyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Mono<Account> findAccount(long id) {
        return Mono.fromCallable(() ->
                    jdbc.queryForObject("select * from acct where id=?",
                                        accountRowMapper, id))
                   // Critical: move the blocking call OFF the event loop
                   .subscribeOn(Schedulers.boundedElastic());
    }
}
```

Caveat: this is *thread-per-blocking-call* again. If most of your app is this pattern, you have not gained reactive's scalability — you've added complexity for nothing. This is exactly where **virtual threads + Spring MVC** would be simpler (see §7.4, §8).

### 5.6 Backpressure-aware streaming with `Flux.create`

```java
Flux<Trade> trades = Flux.create(sink -> {
    TradeListener listener = trade -> {
        sink.next(trade);                 // push from a callback into the stream
    };
    exchange.register(listener);
    sink.onDispose(() -> exchange.unregister(listener)); // cleanup on cancel/complete
}, FluxSink.OverflowStrategy.LATEST);     // backpressure: keep only latest if consumer is slow
```

`OverflowStrategy` options: `BUFFER` (unbounded — OOM risk), `DROP`, `LATEST`, `ERROR`, `IGNORE`.

### 5.7 Testing with `StepVerifier`

```java
@Test
void buildProfile_combinesDownstreams() {
    Mono<Profile> result = service.buildProfile("u1");

    StepVerifier.create(result)
        .assertNext(p -> {
            assertThat(p.user().id()).isEqualTo("u1");
            assertThat(p.orders()).hasSize(2);
        })
        .verifyComplete();      // asserts onComplete with no extra items/errors
}

@Test
void virtualTime_emitsEverySecond() {
    StepVerifier.withVirtualTime(() ->
            Flux.interval(Duration.ofSeconds(1)).take(3))
        .thenAwait(Duration.ofSeconds(3))   // fast-forward virtual clock — no real wait
        .expectNext(0L, 1L, 2L)
        .verifyComplete();
}
```

### 5.8 Global reactive error handling

```java
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(NotFoundException.class)
    Mono<ResponseEntity<ApiError>> notFound(NotFoundException e) {
        return Mono.just(ResponseEntity.status(404)
                   .body(new ApiError("NOT_FOUND", e.getMessage())));
    }
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Reactive does not make a single request faster.** Per-request latency is often *slightly worse* than MVC (operator overhead, more allocations, harder JIT inlining). What it improves is **throughput under high concurrency with I/O waits** and **resource efficiency** (memory, threads).
- **It only helps I/O-bound work.** For CPU-bound work, reactive is at best neutral and at worst slower — and you'll need `parallel()` schedulers anyway.
- **Allocation pressure:** each operator allocates subscriber objects per subscription. High-throughput streams generate garbage; this matters at extreme scale. Reactor uses **operator fusion** (macro/micro-fusion) to elide intermediate queues where possible — but `onErrorContinue`, `publishOn`, and some operators break fusion.
- **Tune `flatMap` concurrency** to bound parallel downstream calls (don't let an unbounded fan-out hammer a downstream / exhaust connections).
- **WebClient connection pool:** Reactor Netty's connection provider defaults to a pool sized `max(2×cores, 16)` per remote. Tune `maxConnections`, `pendingAcquireMaxCount`, and `pendingAcquireTimeout` for high fan-out.

### 6.2 Correctness & concurrency

- The Reactive Streams contract guarantees **serialized signals**, so within a single subscription your operator lambdas are never called concurrently — but **across subscriptions** (e.g. `flatMap` inners, multiple requests) they can be. Keep operator lambdas **pure and stateless**; never mutate shared state without synchronization.
- **Avoid side effects** outside `doOnNext`/`doOnError`/`doFinally`. Logic that mutates shared state belongs nowhere in an operator chain unless it's thread-safe.
- **One subscription per intent.** Subscribing twice to a cold publisher runs it twice (two DB queries!). Use `cache()` or `share()` if you need to fan out one execution.

### 6.3 The cardinal anti-patterns

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| **Blocking on the event loop** (`.block()`, JDBC, `Thread.sleep`, blocking SDK) | Stalls the loop → stalls all connections on it | Offload with `subscribeOn(boundedElastic())`, or use a reactive driver |
| **Forgetting to subscribe** | Pipeline never runs; "nothing happens" | Return the publisher to WebFlux, or `.subscribe()` explicitly |
| **Subscribing inside an operator** (`.flatMap(x -> { foo().subscribe(); return ...; })`) | Fire-and-forget, loses backpressure/errors/ordering | Return the inner publisher from `flatMap` |
| **`block()` inside reactive code** | Same as blocking on loop; can deadlock | Compose with operators instead |
| **Unbounded buffering** (`onBackpressureBuffer()` default, `Flux.create(BUFFER)`) | OOM under load | Use bounded buffer / DROP / LATEST |
| **Catching/swallowing errors silently** | Streams die without trace | `doOnError` + proper `onErrorResume` |
| **Using ThreadLocal for request state** | Breaks across thread hops | Use Reactor `Context` |

> **Detecting blocking calls:** Use **BlockHound** (`reactor-tools` / `blockhound`), a Java agent that *instruments* known-blocking JVM methods and throws if they're called on a non-blocking scheduler thread. Invaluable in tests/CI to catch accidental blocking before production.

### 6.4 Security

- Spring Security has a fully reactive stack: `@EnableWebFluxSecurity`, `SecurityWebFilterChain`, `ReactiveAuthenticationManager`, `ReactiveUserDetailsService`. The security context lives in the Reactor `Context` (`ReactiveSecurityContextHolder`), not a ThreadLocal.
- Access it reactively: `ReactiveSecurityContextHolder.getContext().map(c -> c.getAuthentication())`.
- Method security: `@PreAuthorize` works but the principal resolution is reactive.

### 6.5 Observability

- **The biggest reactive pain.** Stack traces are nearly useless by default — they show Reactor's internal scheduler/operator frames, not *your* assembly line, because the error happens at runtime far from where the chain was assembled, possibly on a different thread.
- **`Hooks.onOperatorDebug()`** (dev only — very expensive): captures assembly stack traces so errors point to the operator you wrote. For production use **`checkpoint("label")`** at strategic points — lightweight, adds a traceable marker to the error.
- **Tracing:** Use Micrometer + the **context-propagation** library to carry trace/span IDs and MDC across thread boundaries via Reactor `Context`. `Mono#tap`, `name()`, `metrics()` integrate with Micrometer Observation.
- **Metrics:** event-loop utilization, boundedElastic pool saturation, WebClient connection pool metrics, pending-acquire counts.

### 6.6 Testing

- **`StepVerifier`** (reactor-test): assert the exact sequence of signals (onNext values, error, completion), with **virtual time** to test delays/timeouts without real waiting.
- **`WebTestClient`**: end-to-end test of WebFlux endpoints (bind to controller, router, or running server).
- **`TestPublisher`**: drive custom signals for testing operators.
- Run **BlockHound** in tests to fail builds on accidental blocking.

### 6.7 Production hardening

- Always set **timeouts** (`timeout(d)` on chains, WebClient response timeout, Netty connection timeout) — without them a hung downstream leaks resources silently.
- Implement **retries with backoff + jitter** (`retryWhen(Retry.backoff(n, base).jitter(0.5))`) — never naive `retry()` that hammers a struggling downstream.
- Add **circuit breakers** (Resilience4j has reactive operators: `transformDeferred(CircuitBreakerOperator.of(cb))`).
- **Bound everything**: flatMap concurrency, connection pools, buffer sizes.
- Configure `spring.codec.max-in-memory-size` to prevent large-body DoS.
- Set up **graceful shutdown** so in-flight streams drain.

---

## 7. Advanced topics & deep internals

### 7.1 Operator fusion

Reactor optimizes chains by **fusing** adjacent operators to avoid intermediate queues and per-item overhead:
- **Macro-fusion:** combine multiple operators into one at assembly (e.g. `Mono.just().map()` collapses).
- **Micro-fusion (`QueueSubscription`):** an upstream that holds a queue exposes it directly to a downstream that can poll it, eliminating one `request/onNext` round-trip per item. Operators signal fusion capability via `Fuseable`.
- Certain operators **break fusion**: `publishOn` (thread boundary), `onErrorContinue`, `log()`. Excessive thread-hopping defeats fusion gains.

### 7.2 `onErrorContinue` vs `onErrorResume` — a subtle trap

`onErrorResume` is **conventional, scoped error handling** — it replaces the failing publisher with a fallback. `onErrorContinue` is a special **upstream-signal** operator: it tells *fusion-aware upstream operators* to drop the offending element and continue. It behaves *non-locally* — it can affect operators far upstream, and it doesn't work with operators that don't support it. The Reactor team recommends `onErrorResume`/`flatMap` with inner error handling for predictable behavior; reserve `onErrorContinue` for the specific "skip bad items in a map" case and test it carefully.

### 7.3 Backpressure strategies in depth

When a producer outpaces a consumer, the operator at the boundary applies a strategy:

| Strategy | Behavior | Use when |
|---|---|---|
| `onBackpressureBuffer()` | queue overflow (unbounded by default → OOM) | bursty but bounded total |
| `onBackpressureBuffer(n)` | bounded queue, then error/drop | bounded memory required |
| `onBackpressureDrop()` | drop newest when no demand | live data, lossy OK |
| `onBackpressureLatest()` | keep only the latest | gauges, prices, latest-wins |
| `onBackpressureError()` | error on overflow | strict contract |

`Flux.interval` and other timed sources don't respect backpressure naturally (the clock keeps ticking), so they error with `OverflowException` if the consumer can't keep up — pair them with an explicit strategy.

### 7.4 Reactive vs Virtual Threads (Project Loom) — the modern reframe

> **Virtual threads (JDK 21, Project Loom):** lightweight threads scheduled by the JVM (not the OS) onto a small pool of *carrier* OS threads. A virtual thread that blocks on I/O is *unmounted* from its carrier (the carrier is freed to run other virtual threads) and *remounted* when I/O completes. You can have millions of them. Crucially, you write **ordinary blocking, imperative code** — `var u = repo.findById(id);` — and get event-loop-like scalability for I/O-bound work, for free.

This changes the calculus dramatically:
- The *primary* historical reason to adopt reactive — "scale beyond thread-per-request without exhausting threads" — is **largely solved by virtual threads** for I/O-bound apps, *without* the debuggability/composition/ecosystem costs of reactive.
- **Spring MVC + virtual threads** (`spring.threads.virtual.enabled=true` in Boot 3.2+) gives you a blocking, easy-to-debug, JDBC-friendly stack that scales to very high concurrency.
- What virtual threads do **NOT** give you: **declarative composition** (zip/merge/flatMap pipelines), **first-class backpressure**, and **streaming operators**. If your core need is *stream processing with backpressure* (SSE fan-out, reactive Kafka, complex async orchestration), reactive still wins.
- **Pinning caveat:** a virtual thread that blocks inside a `synchronized` block (pre-JDK 24) or a native call *pins* its carrier, defeating the benefit. JDK 24 removed most `synchronized` pinning. Prefer `ReentrantLock`.

**Rule of thumb post-Loom:** Choose reactive for *streaming + backpressure + composition*. Choose virtual threads for *high-concurrency request/response with blocking dependencies and a team that wants simplicity*.

### 7.5 R2DBC vs JDBC

> **R2DBC (Reactive Relational Database Connectivity):** a spec + drivers (Postgres, MySQL, MSSQL, H2) for non-blocking SQL. Returns `Mono`/`Flux`. Required to keep a WebFlux app non-blocking end-to-end against a relational DB. Tradeoffs: **no JPA/Hibernate** (R2DBC is lower-level; you lose entity graph, lazy loading, dirty checking, declarative transactions feel different), fewer mature features, and the perennial problem that DBs are often the bottleneck anyway (connection pool, not threads, is the limit).

### 7.6 Hooks, Sinks, and ConnectableFlux

- `Hooks.onErrorDropped`, `Hooks.onNextDropped` — global handlers for signals dropped after cancellation/termination.
- `Hooks.onOperatorDebug()` / `Hooks.enableAutomaticContextPropagation()` (Reactor 3.5+ for ThreadLocal bridging).
- `ConnectableFlux` (via `.publish()`): a hot flux that doesn't start until `.connect()`, letting you line up all subscribers first.
- `Sinks.One`, `Sinks.Many` (`unicast`/`multicast`/`replay`): modern programmatic emission.

### 7.7 Event-loop sizing and pinning work

Reactor Netty uses `LoopResources` (default `2×cores` worker threads). For pure non-blocking work, more loops than cores yields little. WebClient *shares* the server's event-loop resources by default (good — avoids extra threads) but you can give it dedicated resources to isolate client and server I/O.

---

## 8. Tradeoffs & decision frameworks

### 8.1 WebFlux vs MVC vs MVC+Virtual Threads

| Dimension | Spring MVC (blocking) | MVC + Virtual Threads | Spring WebFlux (reactive) |
|---|---|---|---|
| Programming model | Imperative, simple | Imperative, simple | Declarative, steep learning curve |
| Threads | Thread-per-request (200) | 1 vthread/request, few carriers | Few event-loop threads |
| Scales with I/O concurrency | Poorly | **Very well** | **Very well** |
| Backpressure | No | No | **Yes (first-class)** |
| Streaming (SSE/WS) | Awkward | Awkward | **Native** |
| DB | JDBC/JPA/Hibernate | JDBC/JPA/Hibernate | R2DBC (no Hibernate) |
| Debuggability | Easy (normal stack traces) | Easy | **Hard** (assembly vs runtime, thread hops) |
| Blocking deps | Native fit | **Native fit** | Hazardous (must offload) |
| Ecosystem maturity | Highest | High | Good but narrower |
| Per-request latency | Low | Low | Slightly higher (overhead) |
| JDK requirement | any | **JDK 21+** | any |

### 8.2 Use when / avoid when

**Use reactive (WebFlux) when:**
- You need **end-to-end streaming with backpressure** (SSE, WebSocket fan-out, large result streaming).
- You orchestrate **many concurrent async calls** with rich composition (zip/merge/flatMap), retries, timeouts.
- Your **entire stack is non-blocking** (reactive drivers everywhere).
- You need extreme connection counts (100k+) with minimal memory and can't use Loom.

**Avoid reactive when:**
- Your workload is **CPU-bound**.
- You depend on **blocking** libraries (JDBC, blocking SDKs) with no reactive alternative.
- **Virtual threads** are available and your need is plain high-concurrency request/response.
- Your team lacks reactive expertise and the app is a standard CRUD service.
- You'd end up `subscribeOn(boundedElastic())`-ing everything — that's just thread-per-request with a worse debugger.

### 8.3 Reactor vs RxJava vs Mutiny vs Kotlin Coroutines

| Library | Notes |
|---|---|
| **Project Reactor** | Spring's default; `Mono`/`Flux`; tight Spring integration |
| **RxJava 3** | Mature, Android-popular; `Observable`/`Flowable`/`Single`; only `Flowable` has backpressure |
| **Mutiny** | Quarkus's default; `Uni`/`Multi`; designed for readability |
| **Kotlin Coroutines + Flow** | Sequential-looking suspend code; WebFlux supports it (`suspend fun` controllers, `Flow<T>`); often the nicest reactive ergonomics on JVM |

All implement Reactive Streams and interoperate at the `Publisher` boundary.

---

## 9. Failure modes & debugging

### 9.1 Blocking on the event loop (the #1 production incident)

**Symptom:** Throughput collapses, latencies spike for *all* endpoints (not just the slow one), few event-loop threads pegged, requests time out. CPU may look low (threads are blocked, not busy).

**Cause:** A blocking call (JDBC, `RestTemplate`, `Thread.sleep`, blocking SDK, `.block()`) executed on a Netty event-loop thread.

**Diagnose:**
- Thread dump (`jstack`): event-loop threads (`reactor-http-nio-N`) parked in `socketRead`/`park` inside JDBC/HTTP code.
- **BlockHound** in tests/staging throws at the exact offending call.
- Reactor's built-in detector: `block()` on a Netty thread throws `IllegalStateException: block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio-…`.

**Fix:** offload to `boundedElastic`, or replace with a reactive client/driver.

### 9.2 "Nothing happens" — forgot to subscribe

**Symptom:** Code path appears to do nothing; DB never queried; logs absent.
**Cause:** A `Mono`/`Flux` was built but never subscribed (e.g. `service.save(x);` instead of returning it or chaining it).
**Diagnose:** Add `.doOnSubscribe(s -> log.info("subscribed"))` — if it never logs, nothing subscribed.

### 9.3 Memory leak / OOM from unbounded buffering

**Symptom:** Heap grows, GC pauses, OOM under sustained load.
**Cause:** Unbounded `onBackpressureBuffer`, `Flux.create(BUFFER)`, or a fast source feeding a slow consumer with no backpressure handling.
**Diagnose:** Heap dump shows large queues inside Reactor operator subscribers (`FluxOnBackpressureBuffer`, sink queues).
**Fix:** bounded buffer + DROP/LATEST/ERROR strategy; honor backpressure end to end.

### 9.4 Useless stack traces

**Symptom:** `NullPointerException` with a 60-frame trace full of `reactor.core.publisher.*` and `reactor.netty.*`, none of which is your code.
**Cause:** Error surfaces at runtime, detached from the assembly point; thread hops further obscure it.
**Fix:** Add `.checkpoint("buildProfile->orders")` at suspect points. In dev, enable `Hooks.onOperatorDebug()` (slow) or use the `reactor-tools` `ReactorDebugAgent` (cheaper, attach as agent). Enable `Hooks.enableAutomaticContextPropagation()` so MDC/trace IDs appear in logs across threads.

### 9.5 Lost request context (security/MDC/trace)

**Symptom:** Logs missing correlation IDs after a thread hop; `SecurityContext` empty in a downstream call.
**Cause:** Relied on `ThreadLocal` across thread boundaries.
**Fix:** Use Reactor `Context` + Micrometer context-propagation; bridge with `Hooks.enableAutomaticContextPropagation()` (Reactor 3.5+).

### 9.6 Connection pool exhaustion / `PoolAcquireTimeoutException`

**Symptom:** WebClient calls fail with `PoolAcquirePendingLimitException`/timeout under load.
**Cause:** Unbounded `flatMap` fan-out exceeding the Netty connection pool; downstream slow; pool too small.
**Diagnose:** Reactor Netty pool metrics (active/idle/pending), downstream latency.
**Fix:** bound `flatMap` concurrency, size the pool, set `pendingAcquireTimeout`, add circuit breaker.

### 9.7 `block()`/`StackOverflowError`/deadlock from self-subscription

**Symptom:** Deadlock or stack overflow when calling `.block()` inside a reactive chain on the same scheduler that the chain needs.
**Fix:** never `block()` inside reactive code; compose instead.

### 9.8 Real-world incident pattern

A common postmortem: a team migrates an MVC service to WebFlux but keeps a single blocking `RestTemplate` call (or a blocking metrics/logging library) deep in a filter. Under normal load it's invisible; under a traffic spike the event loops saturate, *every* endpoint degrades simultaneously, health checks (also on event loops) start failing, the orchestrator kills "unhealthy" pods, load concentrates on survivors, and the whole fleet cascades. The fix is to find the blocking call (BlockHound would have caught it pre-prod) and either offload or make it reactive. The lesson: **reactive is all-or-nothing along a request path; one blocking link poisons the whole thing.**

---

## 10. Interview drill

**Q1. What problem does reactive/WebFlux actually solve, and what does it NOT solve?**
*Model answer:* It decouples concurrency from thread count, enabling high-concurrency, I/O-bound workloads to scale on few threads with low memory, plus end-to-end backpressure and streaming. It does **not** make individual requests faster, does **not** help CPU-bound work, and gives no benefit if the path contains blocking calls.
- *Follow-up: Why no benefit for CPU-bound?* Because reactive only hides *waiting*; CPU work occupies a thread regardless. You'd still need a parallel scheduler sized to cores; reactive adds overhead with no scalability gain.
- *Follow-up: Does latency improve?* Per-request latency is usually slightly *worse* (operator overhead). Tail latency under load can improve because you avoid thread-pool queueing.

**Q2. Explain the Reactive Streams contract and backpressure.**
*Model answer:* Four interfaces (Publisher/Subscriber/Subscription/Processor). Protocol: one `onSubscribe`, then 0..N `onNext` bounded by cumulative `request(n)` demand, then at most one terminal `onError`/`onComplete`; signals serialized. Backpressure = consumer requests N, producer may emit ≤ N — slow consumer throttles fast producer, bounding memory.
- *Follow-up: How does demand flow?* Demand flows **upstream** via `request(n)`; data flows downstream via `onNext`.
- *Follow-up: What if a source ignores backpressure (e.g. `interval`)?* It overflows; pair with `onBackpressureDrop/Latest/Buffer(n)` or it errors.

**Q3. `map` vs `flatMap` vs `concatMap` vs `switchMap`?**
*Model answer:* `map`: sync 1:1. `flatMap`: async 1:N, merged, concurrent, unordered. `concatMap`: async sequential, ordered. `switchMap`: cancel-previous, latest-wins. Choose by ordering/concurrency/cancellation needs.
- *Follow-up: When does ordering matter and which to use?* Ordered async → `concatMap` (serial) or `flatMapSequential` (concurrent but ordered output).
- *Follow-up: Type-ahead search?* `switchMap` — cancels stale in-flight queries.

**Q4. What is the single most dangerous thing you can do in WebFlux, and how do you prevent it?**
*Model answer:* Block an event-loop thread (JDBC, `.block()`, blocking SDK). It stalls the loop and every connection on it, cascading to all endpoints. Prevent by using reactive drivers, offloading unavoidable blocking to `subscribeOn(boundedElastic())`, and running **BlockHound** in CI.
- *Follow-up: Why is one blocking call so catastrophic?* Few event-loop threads multiplex thousands of connections; blocking one freezes its share of all traffic.
- *Follow-up: Detect it in prod?* Thread dump shows `reactor-http-nio-*` parked in I/O; Reactor throws on `.block()` from a Netty thread.

**Q5. Schedulers — `subscribeOn` vs `publishOn`, and `boundedElastic` vs `parallel`?**
*Model answer:* `subscribeOn` sets the thread for the whole upstream (one matters per chain, closest to source). `publishOn` switches threads for everything downstream of it. `parallel` (fixed = cores) for CPU work; `boundedElastic` (elastic, bounded, queued) for offloading **blocking** I/O.
- *Follow-up: Multiple `subscribeOn`s?* Only the one nearest the source has effect.
- *Follow-up: Why bounded, not cached/elastic-unbounded?* To prevent unbounded thread growth → OOM under load.

**Q6 (senior signal). When would you NOT choose WebFlux, given virtual threads exist?**
*Model answer:* For standard high-concurrency request/response with blocking dependencies (JDBC/JPA), choose **MVC + virtual threads (JDK 21+)**: you get comparable I/O scalability with simple imperative code, full Hibernate, normal debugging, and no blocking hazard. Reserve WebFlux for genuine streaming + backpressure + complex async composition (SSE/WS fan-out, reactive Kafka, orchestration). Adopting reactive *only* for scalability now carries cost without unique benefit.
- *Follow-up: What does Loom NOT replace?* Declarative composition, first-class backpressure, streaming operators.
- *Follow-up: Loom pitfalls?* Carrier pinning in `synchronized`/native (pre-JDK 24); CPU-bound work still needs cores; pool sizing for downstream limits.

**Q7 (senior signal). You inherit a WebFlux service whose p99 latency tripled after a deploy that added a feature flag SDK. How do you investigate?**
*Model answer:* Hypothesize a blocking call (flag SDK doing synchronous HTTP/disk). Confirm with a thread dump showing event-loop threads blocked, or run BlockHound in staging. Check that *all* endpoints degraded (signature of loop saturation) vs one. Fix by using the SDK's async API, offloading to `boundedElastic`, or caching flags. Add a regression gate (BlockHound) and circuit-breaker/timeout around the SDK.
- *Follow-up: Why do all endpoints slow down?* Shared event loops — blocking one starves unrelated requests.
- *Follow-up: How to prevent recurrence?* BlockHound in CI; review checklist banning blocking libs on the request path; SLO alerting on event-loop utilization.

**Q8 (senior signal). Justify R2DBC vs JDBC for a new reactive service backed by Postgres.**
*Model answer:* R2DBC keeps the path non-blocking end-to-end, preserving WebFlux's benefits and enabling streaming of large result sets with backpressure. Cost: no Hibernate/JPA (lose entity graph, lazy loading, dirty checking, mature tooling), lower-level API, smaller ecosystem. If the DB connection pool is the real bottleneck (it usually is), reactive threads don't help DB throughput — so the win is mainly for streaming and for not blocking the loop. If you need rich ORM, reconsider WebFlux entirely (MVC+Loom+JPA).
- *Follow-up: Does R2DBC raise DB throughput?* No — DB concurrency is bounded by the connection pool/DB, not app threads.
- *Follow-up: Transactions?* R2DBC supports them via `TransactionalOperator`/`@Transactional` (reactive), but semantics differ from JPA; no entity dirty-checking.

**Q9. Hot vs cold publishers — define and give examples; how to convert?**
*Model answer:* Cold = per-subscriber fresh execution (`repo.findAll()`, WebClient response, `Flux.range`). Hot = emits regardless of subscribers; late subscribers miss earlier items (live feeds, `Sinks`). Convert cold→hot with `share()`/`publish().connect()`/`cache()`.
- *Follow-up: Risk of subscribing twice to cold?* Side effects run twice (two DB queries / two HTTP calls).
- *Follow-up: `cache()` vs `share()`?* `cache` replays to late subscribers; `share` multicasts live, ref-counted, no replay.

**Q10. How do you handle errors, retries, and timeouts in a reactive pipeline?**
*Model answer:* `onErrorResume`/`onErrorReturn`/`onErrorMap` for fallbacks/translation; `timeout(d)` for SLAs; `retryWhen(Retry.backoff(n, base).jitter(...))` for resilient retries; Resilience4j operators for circuit breaking. Avoid naive `retry()` (thundering herd) and `onErrorContinue` unless you understand its non-local behavior.
- *Follow-up: `onErrorContinue` vs `onErrorResume`?* `onErrorResume` is scoped/predictable; `onErrorContinue` signals fusion-aware upstream to drop bad items — non-local, surprising, easy to misuse.
- *Follow-up: Where to put `timeout`?* On the outer composed chain for an overall SLA, and/or per-downstream for granular control.

**Q11. How does WebFlux propagate request-scoped context (security, MDC) without ThreadLocal?**
*Model answer:* Via Reactor `Context` — an immutable map carried with the subscription, written downstream (`contextWrite`) but visible upstream (`deferContextual`). Spring Security uses `ReactiveSecurityContextHolder`; Micrometer context-propagation bridges to MDC/tracing; `Hooks.enableAutomaticContextPropagation()` restores ThreadLocals at operator boundaries.
- *Follow-up: Why not ThreadLocal?* Requests hop threads; ThreadLocals are thread-bound.
- *Follow-up: Direction of `contextWrite`?* Placed downstream, effective upstream (subscription flows up).

**Q12. Walk through what threads handle a WebFlux request end to end.**
*Model answer:* Netty boss loop accepts; a worker event-loop thread decodes the request, runs the (non-blocking) controller pipeline, encodes and writes the response — all on that one loop thread by default. `subscribeOn`/`publishOn` can move parts onto `boundedElastic`/`parallel`. Backpressure ties emission to socket writability.
- *Follow-up: Default loop count?* ~`2×cores`.
- *Follow-up: What if you add `subscribeOn(boundedElastic())` everywhere?* You recreate thread-per-request, losing reactive's scalability while keeping its complexity.

---

## 11. Glossary

- **Assembly time:** when an operator chain is built (no data flows yet).
- **Backpressure:** consumer-driven flow control; consumer requests N, producer emits ≤ N.
- **BlockHound:** Java agent that detects blocking calls on non-blocking threads.
- **`boundedElastic`:** Reactor scheduler (bounded, elastic thread pool) for offloading blocking I/O.
- **Carrier thread:** OS (platform) thread onto which virtual threads are mounted.
- **Circuit breaker:** resilience pattern that stops calling a failing downstream after a failure threshold.
- **Cold publisher:** produces fresh per subscriber.
- **`concatMap`:** sequential, ordered async transform.
- **Context (Reactor):** immutable per-subscription key-value map replacing ThreadLocal.
- **`CompletableFuture`:** Java's single-value async result; no backpressure.
- **Context switch:** OS swapping the CPU between threads; costs microseconds + cache pollution.
- **Demand:** the `request(n)` count a subscriber has issued.
- **DispatcherHandler:** WebFlux's reactive front controller (MVC's `DispatcherServlet` analog).
- **`epoll`/`kqueue`:** OS I/O multiplexing facilities (Linux/BSD).
- **Event loop:** single thread looping over ready I/O events, doing tiny non-blocking work per event.
- **`Flux<T>`:** Reactor publisher of 0..N items.
- **`flatMap`:** async, concurrent, merged (unordered) transform.
- **Hot publisher:** emits regardless of subscribers; late subscribers miss earlier items.
- **I/O multiplexing:** one thread watching many file descriptors via `select`/`epoll`/`kqueue`.
- **JDBC:** blocking relational DB API.
- **Little's Law:** `L = λ × W`; relates concurrency, throughput, latency.
- **Loom (Project):** JVM project delivering virtual threads.
- **`Mono<T>`:** Reactor publisher of 0..1 items.
- **MVC (Spring):** blocking, servlet-based, thread-per-request web stack.
- **Netty:** async event-driven network framework; WebFlux's default server.
- **NIO:** Java's non-blocking I/O (`Selector`, channels).
- **Non-blocking I/O:** calls return immediately rather than waiting.
- **Operator:** a chainable transformation/processing stage (a `Processor`).
- **Operator fusion:** Reactor optimization eliminating intermediate queues/overhead.
- **`onBackpressure*`:** strategies (Buffer/Drop/Latest/Error) when producer outpaces consumer.
- **Pinning:** a virtual thread stuck to its carrier (e.g. inside `synchronized`/native), defeating Loom.
- **Processor:** both Subscriber and Publisher (a pipeline stage).
- **Project Reactor:** Spring's Reactive Streams implementation (`Mono`/`Flux`).
- **Publisher:** source of a reactive stream.
- **R2DBC:** Reactive Relational Database Connectivity (non-blocking SQL).
- **Reactive Streams:** the 4-interface interop spec for async streams with backpressure.
- **Resilience4j:** resilience library (retry, circuit breaker, bulkhead) with reactive operators.
- **`RouterFunction`:** WebFlux functional endpoint definition.
- **Scheduler:** Reactor's execution-context/thread-pool abstraction.
- **`share()`/`cache()`/`publish()`:** cold→hot conversion operators.
- **Sinks:** Reactor's programmatic emission API (push into a Flux/Mono).
- **SSE (Server-Sent Events):** HTTP streaming of events from server to client.
- **`StepVerifier`:** reactor-test tool for asserting reactive signal sequences.
- **`subscribeOn`:** sets the thread for the upstream source.
- **`publishOn`:** switches threads for downstream operators.
- **Subscriber:** consumer of a reactive stream.
- **Subscription:** the control handle (`request`/`cancel`) between publisher and subscriber.
- **`switchMap`:** cancel-previous, latest-wins async transform.
- **ThreadLocal:** thread-bound storage; breaks across reactive thread hops.
- **Thread-per-request:** one thread dedicated per request for its full duration (MVC model).
- **Virtual thread:** JVM-scheduled lightweight thread; blocking unmounts it from its carrier.
- **WebClient:** WebFlux's reactive, non-blocking HTTP client.
- **WebFilter:** reactive cross-cutting filter (servlet `Filter` analog).
- **WebFlux:** Spring's reactive, non-blocking web framework.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Mono = 0..1, Flux = 0..N.** Both lazy: *nothing runs until subscribe*. WebFlux subscribes for you.
- **Demand flows up (`request(n)`), data flows down (`onNext`).** Terminal = one `onError` XOR `onComplete`.
- **Reactive helps I/O-bound + high concurrency + streaming/backpressure.** Does NOT help CPU-bound or single-request latency.
- **NEVER block the event loop.** Offload blocking with `subscribeOn(boundedElastic())`; prefer reactive drivers (R2DBC, WebClient). Catch with **BlockHound**.
- **Schedulers:** `parallel` = CPU (size=cores); `boundedElastic` = blocking I/O (size=10×cores, queue=100k); `single`; `immediate`. `subscribeOn` = upstream thread; `publishOn` = downstream thread.
- **Composition:** `map` (sync 1:1), `flatMap` (async, concurrent, unordered), `concatMap` (sequential ordered), `flatMapSequential` (concurrent ordered), `switchMap` (latest wins). `Mono.zip` for parallel combine.
- **Errors:** `onErrorResume`/`onErrorReturn`/`onErrorMap`, `timeout`, `retryWhen(backoff+jitter)`, Resilience4j. Beware `onErrorContinue` (non-local).
- **Backpressure overflow:** Buffer(n)/Drop/Latest/Error. Default buffer is unbounded → OOM risk.
- **Hot vs cold:** cold re-runs per subscribe; `share()`/`cache()`/`publish().connect()` make it hot.
- **Context, not ThreadLocal:** `contextWrite` (down) / `deferContextual` (up); Micrometer bridges MDC/tracing.
- **Debug:** `checkpoint("label")`, `log()`, `Hooks.onOperatorDebug()` (dev). Useless stack traces are the norm without these.
- **Default threads:** Netty event loops ≈ `2×cores`. `spring.codec.max-in-memory-size` = 256KB.
- **Modern decision:** **MVC + virtual threads (JDK 21+)** beats WebFlux for blocking-dependency, request/response apps. Choose WebFlux for streaming + backpressure + complex async composition.
- **Test:** `StepVerifier` (+virtual time), `WebTestClient`, BlockHound in CI.

### 12.2 Self-test (no answers — recall practice)

1. Explain why a single blocking JDBC call inside a WebFlux controller can degrade *every* endpoint, and name two ways to fix it.
2. Trace the difference in thread usage and ordering guarantees between `flatMap(fn, 4)` and `concatMap(fn)` for a stream of 100 items each triggering an HTTP call.
3. You have `Flux.interval(Duration.ofMillis(1))` feeding a consumer that takes 10 ms per item. What happens, and how do you fix it with a named operator?
4. Given virtual threads (JDK 21), construct the decision rule for choosing MVC+Loom vs WebFlux for (a) a CRUD service over Postgres, and (b) a market-data SSE fan-out service. Justify each.
5. Describe, in order, the assembly→subscription→request→emission lifecycle for `Flux.range(1,3).map(x->x*2).filter(x->x>2).subscribe(...)`, including which direction demand and data travel.
6. Why are default Reactor stack traces nearly useless, and which three tools/operators recover debuggability (and their cost tradeoffs)?
7. Contrast `subscribeOn` and `publishOn`: where you place each, what scope each affects, and which scheduler you'd pick for offloading a blocking SDK call.
8. Explain how WebFlux propagates a security principal across a thread hop without ThreadLocal, including the direction `contextWrite` takes effect.
```

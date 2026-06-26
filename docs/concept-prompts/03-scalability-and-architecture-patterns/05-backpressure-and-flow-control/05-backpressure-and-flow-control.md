# Backpressure & Flow Control

> **Concept area:** Scalability & Architecture Patterns
> **Subtopic:** Backpressure & Flow Control
> **Reader profile:** A senior JVM/backend engineer who wants to fully master this subtopic — design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## 1. Overview & where it fits

### 1.1 What it is

**Backpressure** is the mechanism by which a *consumer* of work signals to a *producer* of work: "slow down — I cannot keep up." **Flow control** is the broader umbrella: any technique that regulates the *rate* at which data or requests move through a pipeline so that no stage is overwhelmed. Backpressure is the most important *kind* of flow control: instead of the producer blindly pushing as fast as it can, the rate is governed by the *slowest stage's* ability to absorb work.

The core insight is simple and almost obvious once stated: **a pipeline can only sustain the throughput of its slowest stage.** If a fast producer feeds a slow consumer and there is no feedback path, the difference between them has to go *somewhere*. That "somewhere" is a buffer (a queue, a socket buffer, a heap-allocated list). If the imbalance is sustained, the buffer grows without bound, and the system eventually fails — typically with an `OutOfMemoryError`, runaway latency, or a full-blown **metastable failure** (defined below).

Backpressure replaces unbounded buffering with an explicit, bounded *conversation* between stages: "How much can you take right now? Okay, I'll send exactly that much." That conversation is the whole game.

### 1.2 The problem it solves

Consider a data pipeline:

```
[Source]  -->  [Transform]  -->  [Sink]
 1,000/s         processes         writes
                  at 200/s         at 200/s
```

The source emits 1,000 items per second. The transform stage can only process 200 per second. Without flow control:

- 800 items per second accumulate *somewhere* — an in-memory queue, an OS socket buffer, a Kafka topic, a thread pool's work queue.
- Memory usage climbs linearly with time.
- Latency for any individual item climbs (each item waits behind a longer and longer queue — this is **bufferbloat**, defined below).
- Eventually the heap is exhausted → `OutOfMemoryError` → the process dies, possibly taking healthy in-flight work with it.

Backpressure solves this by making the *source* aware of the transform's capacity, so the source produces at 200/s (or pauses, or drops, or rejects) instead of 1,000/s. The pipeline runs at a *sustainable* rate, memory stays bounded, and latency stays predictable.

### 1.3 When you reach for it

You need explicit backpressure / flow control whenever **a producer can outpace a consumer and the rate mismatch can be sustained.** Concretely:

- **Streaming / reactive pipelines** — Kafka/Pulsar consumers, Reactive Streams, RxJava/Reactor, Akka Streams, gRPC streaming.
- **Asynchronous producer/consumer designs** — any `BlockingQueue`-based worker pool, an ingestion service writing to a slower database.
- **Service-to-service calls** — service A calls service B; B is slow or degraded; A must avoid piling up requests (this is where load shedding, rate limiting, and circuit breakers live).
- **Network protocols** — TCP itself implements flow control (the receive window) and congestion control; HTTP/2 and HTTP/3 implement stream- and connection-level flow control with credits.
- **Event-driven / message-driven systems** — anywhere events arrive faster than they can be handled.

You can *skip* explicit backpressure only when (a) the producer is intrinsically rate-limited to below the consumer's capacity, or (b) the workload is bounded and small enough that a buffer can never realistically overflow. Both assumptions are dangerous in production; verify them.

### 1.4 One-paragraph mental model

> **Think of backpressure as a water system with a feedback valve.** A pump (producer) pushes water into a pipe that flows into a tank (consumer) with a drain. If the pump is faster than the drain and the tank has no overflow control, it floods (OOM). Real plumbing solves this with a float valve: when the tank fills, the float rises and *shuts the inlet valve* — the tank tells the pump to stop. Backpressure is that float valve in software: the consumer's fill level controls the producer's rate. When you cannot control the producer (e.g., external traffic), you instead choose what to do with the overflow: **buffer** it (bounded), **reject** it (fail fast), or **shed** it (drop the least valuable work). Those three responses — buffer, reject, shed — are the entire strategic vocabulary of flow control across service boundaries.

---

## 2. Foundations from first principles

We build this up from zero. Every term gets defined the moment it appears.

### 2.1 Producer, consumer, and the rate equation

- A **producer** (a.k.a. *publisher*, *source*, *upstream*) generates work items: messages, requests, records, bytes.
- A **consumer** (a.k.a. *subscriber*, *sink*, *downstream*) processes them.
- Each has a **rate**, in items per second. Call them `λ_p` (producer rate, the Greek letter lambda, conventionally used for arrival rate) and `λ_c` (consumer rate, often called the **service rate** and written `μ`, the Greek letter mu).

The fundamental relationship:

- If `λ_p ≤ μ` *on average*, the system is **stable**: the queue between them stays bounded.
- If `λ_p > μ` *for a sustained period*, the system is **unstable**: the queue grows without bound until something gives.

This is not a heuristic; it is a theorem of **queueing theory** (the branch of applied probability that studies waiting lines). A queue whose arrival rate exceeds its service rate has no steady state — its expected length goes to infinity.

### 2.2 Little's Law (the most useful formula in this whole topic)

**Little's Law** states, for any stable system in steady state:

```
L = λ × W
```

- `L` = average number of items *in the system* (in flight + queued).
- `λ` = average arrival rate (items/second).
- `W` = average time an item spends in the system (seconds).

This is astonishingly general — it holds regardless of arrival distribution, service distribution, or scheduling discipline, as long as the system is stable. Why it matters for backpressure:

- If you **bound `L`** (cap the queue + in-flight count), then since `λ` is fixed by traffic, **`W` (latency) is bounded too**. Bounding concurrency is bounding latency.
- If `L` grows without bound (unbounded buffer under overload), `W` grows without bound — latency explodes. This is the mathematical signature of bufferbloat.

**Worked example:** A service handles `λ = 500 req/s` with `W = 20 ms = 0.02 s` average. Then `L = 500 × 0.02 = 10` requests in flight on average. If you cap concurrency at, say, 50, you have headroom for bursts up to 5× before you start queueing — and you have *guaranteed* that `W ≤ L/λ = 50/500 = 100 ms` at the cap (the queue can't make latency worse than that, because requests beyond 50 get rejected, not queued).

### 2.3 Buffers and queues

A **buffer** (or **queue**) is temporary storage between a producer and consumer. Its purpose is to **absorb short-term rate mismatches** — bursts. A producer that emits in bursts (say 1,000 items in 100 ms, then idle for 900 ms, averaging 100/s) can feed a steady 100/s consumer *only* through a buffer that holds the burst.

Buffers come in two flavors:

- **Bounded** — fixed maximum capacity. When full, the producer must do *something*: block, drop, or reject. This is safe.
- **Unbounded** — grows as needed. When the producer outpaces the consumer for long enough, it consumes all memory. This is a latent OOM bug. Java's `Executors.newFixedThreadPool(n)` uses an **unbounded** `LinkedBlockingQueue` by default — a classic foot-gun (covered in detail in §6.9).

**Key principle:** A bounded buffer is a *shock absorber*, not a *reservoir*. Size it to absorb expected bursts, not to permanently store the difference between producer and consumer rates. If your buffer is permanently growing, you have a rate problem that a bigger buffer will never fix — it only delays the failure.

### 2.4 Push vs pull (the two fundamental data-movement models)

This distinction is at the heart of backpressure design.

- **Push model:** The producer decides when to send. It "pushes" items to the consumer as soon as they are ready. The consumer reacts. Examples: a raw callback/observer, `java.util.Observable`, naive event listeners, UDP, fire-and-forget messaging.
  - *Problem:* The producer controls the rate. If it pushes faster than the consumer absorbs, the consumer must buffer (risking OOM) or drop. There is **no built-in backpressure** in a pure push model.

- **Pull model:** The consumer decides when to receive. It "pulls" items when it is ready, e.g., calling `iterator.next()` or `inputStream.read()`. The producer only does work in response to a pull.
  - *Benefit:* The consumer controls the rate, so backpressure is *automatic and implicit* — the producer simply doesn't get asked for more until the consumer is ready. Examples: `java.util.Iterator`, JDBC `ResultSet`, lazy sequences, blocking reads.
  - *Cost:* Pull is naturally synchronous/blocking, which can waste threads (a thread blocked in `read()` does nothing). It's also "chatty" for low-latency push-style workloads.

- **Hybrid / dynamic push-pull (the modern answer):** The consumer signals *how much* it can take (a **demand** or **credit**), and the producer pushes *up to that amount*, asynchronously, then waits for more demand. This is **Reactive Streams** (§3.4, §3.5) and **credit-based flow control** (§2.9, §7). It combines pull's safety (consumer controls rate) with push's efficiency (no blocked threads, batched delivery).

| Property | Pure push | Pure pull | Reactive (demand/credit) |
|---|---|---|---|
| Who controls rate | Producer | Consumer | Consumer (via demand) |
| Backpressure | None (must buffer/drop) | Implicit | Explicit, async |
| Thread efficiency | High (no blocking) | Low (blocks on pull) | High (non-blocking) |
| Latency | Low | Higher (round-trips) | Low (batched demand) |
| Complexity | Low | Low | High |

### 2.5 Synchronous blocking vs non-blocking backpressure

There are two physical ways a consumer applies backpressure:

1. **Blocking backpressure:** The producer's thread *blocks* when it cannot make progress (e.g., `BlockingQueue.put()` blocks when the queue is full). The OS scheduler parks the thread. This is simple and correct but ties up a thread per blocked producer — fine for thread-per-task designs, catastrophic for high-concurrency async designs (you'd block your few event-loop threads).

2. **Non-blocking / async backpressure:** No thread blocks. Instead, demand is communicated via messages/callbacks. The producer's thread is free to do other work while waiting for the consumer to request more. This is how Reactor, Netty, and TCP work. It's essential for event-loop architectures (a handful of threads serving thousands of connections).

### 2.6 Latency, throughput, and the tradeoff under overload

- **Throughput** = items processed per unit time (the steady-state output rate).
- **Latency** = time for a single item to traverse the system.

Under overload, you cannot have both. If you keep accepting work (unbounded buffer), throughput at the output stays at `μ` but latency climbs without bound (everything waits behind a growing queue). Eventually latency exceeds client timeouts and *all* the work you did was wasted — clients gave up. This is the path to **metastable collapse** (§2.7). The alternative — reject/shed excess — keeps latency bounded for the work you *do* accept, at the cost of serving fewer requests. **Bounded latency under overload almost always beats unbounded latency**, because work that completes after the client has timed out is worse than work never started (you spent capacity for zero value, and possibly amplified the load via retries).

### 2.7 Metastable failures (why unbounded buffering is so dangerous)

A **metastable failure** is a failure mode where a system, under a *triggering* perturbation, enters a degraded state that *sustains itself even after the trigger is removed.* The system has two stable states — healthy and collapsed — and a trigger can knock it from one to the other, where it stays stuck. The defining feature is a **sustaining feedback loop**: the degraded state generates extra load that keeps the degraded state alive.

Canonical example — the **retry storm + unbounded queue collapse**:

1. **Healthy state:** Service handles 500 req/s comfortably; latency 20 ms.
2. **Trigger:** A brief latency spike (GC pause, a dependency hiccup, a deploy) causes some requests to slow down.
3. **Buffering:** Requests pile up in an unbounded queue. Latency climbs.
4. **Timeouts:** Clients hit their timeout (say 1 s) and **retry**. Now effective load is `original + retries` — maybe 2–3×.
5. **Amplification:** Higher load → deeper queue → more timeouts → more retries. The feedback loop is closed and *self-sustaining*.
6. **Collapse:** The service spends all its capacity processing requests whose clients have *already given up* (a phenomenon called **goodput collapse** — *goodput* is useful throughput, work that actually benefits a client; under collapse, throughput stays high but goodput crashes to near zero).
7. **Stuck:** Even if you remove the original trigger, the retry-driven feedback loop keeps the system collapsed. **It does not self-heal.** You must shed load (shrink queues, reject, drop the retry backlog) to escape.

The lesson that drives this entire chapter: **unbounded buffers are the fuel for metastable collapse.** Bounded queues + load shedding + retry budgets are the fire-prevention. Google's SRE literature and the influential 2021 paper *"Metastable Failures in Distributed Systems"* (Bronson et al.) document this pattern extensively.

### 2.8 TCP flow control as the canonical analogy

**TCP** (Transmission Control Protocol — the reliable, ordered byte-stream protocol underlying most internet traffic) has solved backpressure for decades, and its design is the template everyone copies. Two distinct mechanisms, often confused:

- **Flow control** protects the *receiver* from being overwhelmed. The receiver advertises a **receive window (rwnd)** — the number of bytes it currently has buffer space for — in every ACK (acknowledgement) it sends. The sender may have at most `rwnd` unacknowledged bytes outstanding. When the receiver's application is slow to `read()`, its buffer fills, `rwnd` shrinks, and in the limit the receiver advertises **window zero**, telling the sender "stop entirely." This is *credit-based flow control* (§2.9) in its purest form: the window *is* the credit. (Defined: an **ACK** is a small packet by which the receiver confirms which bytes it has received.)

- **Congestion control** protects the *network* (the routers/links between the two hosts) from being overwhelmed. The sender maintains a **congestion window (cwnd)** that it grows when packets get through and shrinks when packets are lost (loss = inferred congestion). Algorithms: **slow start** (start small, double cwnd each round-trip), **congestion avoidance** (grow linearly near the limit), and modern schemes like **CUBIC** (the Linux default since ~2006) and **BBR** (Google's model-based algorithm that estimates bottleneck bandwidth and round-trip time instead of using loss as the signal).

The sender is limited by `min(rwnd, cwnd)` — the smaller of "what the receiver can take" and "what the network can carry." The whole arrangement gives lossless, self-throttling delivery: a fast sender automatically slows to the receiver's drain rate without any application-level code.

**The analogy to application backpressure:** `rwnd` is your bounded queue's free capacity; the receiver's `read()` rate is your consumer's service rate `μ`; window-zero is `BlockingQueue.put()` blocking on a full queue or Reactor's demand reaching zero. Application frameworks reimplement TCP's idea one layer up, because TCP only backpressures *bytes on one connection* — it cannot understand that "this message is expensive to process" or coordinate across many connections.

### 2.9 Credit-based flow control

**Credit-based flow control** generalizes TCP's window. The consumer grants the producer a number of **credits** — units of permission to send (bytes, messages, records, whatever the unit is). The producer may send only as much as it has credit for; each send consumes credit. The consumer replenishes credit as it processes work and frees capacity. When credit hits zero, the producer must stop.

This is the universal pattern underneath:

- **TCP** — credit = receive window in bytes.
- **HTTP/2 and HTTP/3** — `WINDOW_UPDATE` frames grant flow-control credit, both per-stream and per-connection.
- **Reactive Streams** — `Subscription.request(n)` grants `n` credits ("demand"); each emitted item consumes one.
- **Apache Flink** — credit-based flow control between network tasks (added in Flink 1.5) where each receiving subtask grants buffer credits to senders.
- **gRPC** — inherits HTTP/2 stream flow control.

Credit-based flow control is **non-blocking and composable**: credits can flow across async boundaries, batch naturally (grant 256 at once), and let the consumer express precise demand. It's the modern default for high-performance systems. The tradeoff is protocol complexity and the need to choose credit-replenishment policy (replenish eagerly per item, or in batches at a low-watermark — §7.6).

---

## 3. How it works internally

This is the heart of the document. We trace the actual control flow, data flow, lifecycle, and state machines for the major mechanisms.

### 3.1 Bounded blocking queue — internal workflow

A `BlockingQueue` is the simplest concrete backpressure device on the JVM. Let's trace `ArrayBlockingQueue` precisely.

**Structure:** A fixed-size circular array of capacity `N`, a single `ReentrantLock`, and two **condition variables** (a *condition* is a queue of threads waiting for a specific predicate to become true, associated with a lock): `notEmpty` (consumers wait here when the queue is empty) and `notFull` (producers wait here when the queue is full). It also tracks `count`, `putIndex`, and `takeIndex`.

**Producer calling `put(item)` — step by step:**

1. Acquire the lock (`lock.lockInterruptibly()`).
2. **While `count == N`** (queue full): call `notFull.await()`. This *atomically releases the lock and parks the thread* on the `notFull` condition. The thread sleeps, consuming no CPU. **This is the backpressure** — the producer is now blocked, applying upstream pressure naturally (its caller is stuck inside `put`).
3. When signaled and re-awoken, re-check the `while` predicate (guard against **spurious wakeups** — the JVM/OS may wake a thread without a signal, so condition waits must always be in a loop).
4. Once there is space: write the item into `items[putIndex]`, advance `putIndex` (wrapping around), increment `count`.
5. Call `notEmpty.signal()` to wake one waiting consumer.
6. Release the lock.

**Consumer calling `take()` — step by step (mirror image):**

1. Acquire the lock.
2. **While `count == 0`**: `notEmpty.await()` (release lock, park).
3. On wakeup, re-check.
4. Read `items[takeIndex]`, advance `takeIndex`, decrement `count`.
5. `notFull.signal()` to wake one blocked producer.
6. Release lock, return the item.

**The data flow / control flow at a glance:**

```
Producer thread                  Shared ArrayBlockingQueue(N)            Consumer thread
   put(x) ──► [lock] ──► full? ──yes──► await(notFull) [PARKED] ◄── full
                          │no                                         signal(notFull)
                          ▼                                              ▲
                     enqueue x, count++                                  │
                     signal(notEmpty) ───────────────────────► [unparks consumer]
                     [unlock]
                                                       take() ──► [lock] ──► empty? ──yes──► await(notEmpty) [PARKED]
                                                                              │no
                                                                              ▼
                                                                         dequeue, count--
                                                                         signal(notFull)
```

**State machine of the queue (by `count`):**

```
        put (count<N)              put (count==N-1→N)
EMPTY ───────────────► PARTIAL ───────────────────► FULL
 ▲   ◄─────────────── │   ◄──────────────────────── │
 └── take(count==1→0) └── take (count<N)             └── (producers block here)
```

- **EMPTY:** consumers block on `take()`.
- **FULL:** producers block on `put()`. This is the backpressure-active state.
- **PARTIAL:** both proceed freely.

The bounded capacity `N` is the entire backpressure budget. `N` too small → producers block constantly even on tiny bursts (false throttling, lost throughput). `N` too large → bufferbloat returns; latency climbs and you approach unbounded behavior. Sizing `N` is the central tuning decision (§7.3).

### 3.2 The producer's options when the buffer is full (the strategic trichotomy)

When a bounded buffer is full, the producer must pick one of exactly three responses. Everything in service-level flow control is a variation of these:

1. **Block / wait (apply backpressure upstream):** `put()` blocks. Pressure propagates up the chain to the ultimate source. Correct when the source *can* be slowed (e.g., reading a file, an internal pipeline). Wrong when the source is external traffic you can't pause — you'd just block your request-handling threads and stall everything.

2. **Reject / fail fast:** Refuse the item immediately, return an error (HTTP 429 Too Many Requests, HTTP 503 Service Unavailable, a `RejectedExecutionException`). The caller decides what to do (retry later, fall back, give up). Correct at service boundaries where you cannot block the caller and must protect yourself. This is **admission control / load shedding** at the edge.

3. **Drop / shed:** Silently discard the item (or the *oldest* item, or the *least valuable* item). Correct for lossy-tolerant workloads: metrics, logs, telemetry, live video frames, cache-warming. Variants: **drop-newest** (`offer()` returns false), **drop-oldest** (evict head to make room — keeps freshest data), **sample** (keep 1 in N), **priority-shed** (drop low-priority first).

| Strategy | Latency impact | Data loss | Best for | Risk |
|---|---|---|---|---|
| Block | Producer stalls (good if source pausable) | None | Internal pipelines, file/stream sources | Thread exhaustion if source is request threads |
| Reject (429/503) | Caller gets fast error | None locally (caller decides) | Service ingress, public APIs | Caller retries → must pair with retry budgets |
| Drop / shed | None | Yes (chosen items) | Metrics, logs, telemetry, real-time feeds | Silent loss; needs observability |

### 3.3 Queue-based load leveling (the architectural pattern)

**Queue-based load leveling** is a named cloud design pattern: place a durable queue (Kafka, RabbitMQ, AWS SQS, Azure Service Bus) between a bursty producer and a rate-limited consumer. The queue absorbs spikes; the consumer drains at its own sustainable pace. This *decouples* producer and consumer rates in time.

How it provides flow control:

- **Buffering in time, not memory:** The queue is durable storage (disk-backed, often replicated), so a burst doesn't have to be held in the consumer's heap. The consumer pulls at rate `μ`; the backlog drains over time.
- **Implicit pull-based backpressure:** Kafka and SQS consumers *pull* (`poll()` / `ReceiveMessage`). The consumer naturally controls its own rate. There's no way for the broker to OOM the consumer because the consumer asks for batches it can handle.
- **Bounded by retention, not by RAM:** The bound is the broker's retention policy (time or size) and disk. Backlog is visible and measurable (**consumer lag** — the gap between the latest produced offset and the consumer's committed offset). Lag is your single most important backpressure metric in queue-based systems.

**Critical caveat:** A durable queue *moves* the backpressure problem; it doesn't eliminate it. If `λ_p > μ` *forever*, lag grows without bound until you hit retention limits and start *dropping the oldest data* — or fill the disk. Queue-based load leveling fixes *bursts* (transient `λ_p > μ`); it cannot fix a *sustained* rate deficit. The cure for sustained deficit is more consumers (scale out), faster consumers, or shedding at the producer.

**Internal flow (Kafka consumer):**

1. Consumer calls `poll(timeout)` → fetches up to `max.poll.records` records (default 500).
2. Processes the batch.
3. Commits offsets (auto or manual).
4. Loops. If processing is slow, the consumer simply polls less often — the broker doesn't push; it waits. **The pull model is the backpressure.**
5. Danger: if a single `poll()`-to-`poll()` cycle takes longer than `max.poll.interval.ms` (default 300,000 ms = 5 min), the broker assumes the consumer is dead, triggers a **rebalance** (reassigns partitions to other consumers), and you may process duplicates. So even pull-based systems have an *implicit* deadline you must respect — tune `max.poll.records` down if per-record processing is slow.

### 3.4 Reactive Streams — the specification and its state machine

**Reactive Streams** is a specification (not a library) for asynchronous stream processing with **non-blocking backpressure**, standardized in JDK 9 as `java.util.concurrent.Flow` and implemented by Project Reactor, RxJava 2/3, Akka Streams, Vert.x, and others. It defines four interfaces and a strict protocol of interactions between them.

**The four interfaces:**

```java
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

public interface Subscriber<T> {
    void onSubscribe(Subscription s);   // handshake: receive the control channel
    void onNext(T item);                // a data item (may be called 0..N times, ≤ demand)
    void onError(Throwable t);          // terminal: failure
    void onComplete();                  // terminal: success, no more items
}

public interface Subscription {
    void request(long n);   // DEMAND: "I can accept n more items" (credit grant)
    void cancel();          // "stop sending; I'm done"
}

public interface Processor<T, R> extends Subscriber<T>, Publisher<R> {}  // a stage that is both
```

The key innovation is `Subscription.request(n)`: **the subscriber explicitly requests `n` items.** The publisher must never emit more than the total requested. This is credit-based flow control (§2.9) expressed as a Java API. `n` is the credit; each `onNext` consumes one unit.

**The protocol (lifecycle / state machine), step by step:**

1. Subscriber calls `publisher.subscribe(subscriber)`.
2. Publisher calls `subscriber.onSubscribe(subscription)` — handing over the `Subscription` (the control channel). **No data flows yet.** Outstanding demand is zero.
3. Subscriber decides how much it wants and calls `subscription.request(n)` — granting `n` credits.
4. Publisher emits **at most `n`** items via `onNext(item)`, decrementing outstanding demand each time. If demand reaches zero, the publisher **must stop and wait** for another `request`.
5. The subscriber requests more (`request(m)`) as it processes — this is the credit replenishment. Demand accumulates additively, capped at `Long.MAX_VALUE` (which signals "unbounded — no backpressure," used when the consumer genuinely cannot be overwhelmed).
6. Eventually the publisher signals **exactly one** terminal event: `onComplete()` (success) or `onError(t)` (failure). After a terminal signal, no further signals are allowed.
7. At any time the subscriber may call `subscription.cancel()` to tear down.

**State machine of a subscription:**

```
                subscribe()
   [unsubscribed] ──────────► [subscribed, demand=0]
                                     │  request(n)
                                     ▼
                              [active, demand=n] ──onNext──► demand--
                                     │  ▲                       │
                                     │  └───── request(m) ──────┘ (demand += m)
                       onComplete/   │  demand==0 → publisher waits
                       onError/cancel│
                                     ▼
                                 [terminated]  (no more signals ever)
```

**The Reactive Streams rules (the spec's TCK enforces ~40 rules; the load-bearing ones):**

- A `Publisher` must not signal more `onNext` than the total demand requested (Rule 1.1). *This is the backpressure guarantee.*
- `request(n)` with `n ≤ 0` must signal `onError(IllegalArgumentException)` (Rule 3.9) — except `Long.MAX_VALUE` means unbounded.
- Calls to a `Subscriber`'s methods must be **serialized** (not concurrent) — `onNext`/`onError`/`onComplete` happen-before each other (Rule 1.3). Operators rely on this to avoid locks.
- After a terminal signal, the `Subscription` is considered cancelled; further `request`/`cancel` are no-ops (Rules 1.7, 2.4).
- `request` and `cancel` may be called from within `onNext` (re-entrancy) without unbounded stack growth — implementations use **trampolining** (a technique that queues recursive work into a loop instead of recursing on the stack) to handle this.

### 3.5 Project Reactor — how demand actually propagates

**Project Reactor** is the Reactive Streams implementation that powers Spring WebFlux (Spring's non-blocking web stack). Its two core types:

- `Mono<T>` — a publisher of **0 or 1** item.
- `Flux<T>` — a publisher of **0 to N** items.

When you build a pipeline like:

```java
Flux.range(1, 1_000_000)          // source
    .map(i -> i * 2)              // operator
    .filter(i -> i % 3 == 0)      // operator
    .subscribe(/* consumer with limited demand */);
```

…nothing happens until `subscribe()`. Reactor pipelines are **lazy and cold** by default (a *cold* publisher generates data per-subscriber on subscription; a *hot* publisher emits regardless of subscribers, e.g., a live event stream). On subscribe, Reactor wires the operators into a chain and demand flows **upstream, from subscriber to source**, while data flows **downstream, from source to subscriber.** That bidirectional flow is the essence:

```
   demand (request(n)) ──────────────────────────────►  upstream
SOURCE ◄── filter ◄── map ◄── SUBSCRIBER          (control: who asks for more)
SOURCE ──► map ──► filter ──► SUBSCRIBER ──────────►  downstream
   data (onNext) ──────────────────────────────────►  (data: items flowing)
```

**Crucial subtlety — demand rewriting by operators:** Most operators pass demand straight through, but some *transform* it:

- `filter` may need to request *more* from upstream than its downstream asked for, because some items get dropped (if downstream wants 10 and filter drops half, filter must request ~20 upstream). Reactor handles this internally.
- `flatMap` has its own concurrency knob (default `Queues.SMALL_BUFFER_SIZE = 256` concurrent inner subscriptions) and prefetches.
- `buffer`, `window`, `groupBy` aggregate items, changing the demand relationship.
- **`onBackpressureBuffer` / `onBackpressureDrop` / `onBackpressureLatest` / `onBackpressureError`** explicitly choose what to do when downstream demand can't keep up with an upstream that ignores backpressure (e.g., a hot source — see §3.6).

**Prefetch and replenishment:** Reactor operators that bridge async boundaries (`publishOn`, `flatMap`, `concatMap`) **prefetch** in batches. The default prefetch is 256 (`Queues.SMALL_BUFFER_SIZE`), and they replenish at **75%** consumed (i.e., when 75% of the prefetched batch — 192 items — has been processed, request the next batch of 192). This **low-watermark replenishment** avoids a stop-start "request 1, get 1, request 1" pattern that would murder throughput. You can override prefetch per-operator: `flatMap(fn, concurrency, prefetch)`, `publishOn(scheduler, prefetch)`.

### 3.6 Bridging a non-backpressure source into a backpressured stream

A common real problem: your data source *pushes* and cannot be slowed — a sensor, a WebSocket, a callback API, mouse events, a `MulticastProcessor`. The source ignores `request(n)`. Reactor offers **backpressure operators** that sit at the boundary and decide the overflow policy:

| Operator | Behavior when downstream can't keep up | Use for |
|---|---|---|
| `onBackpressureBuffer()` | Buffer items (UNBOUNDED by default — danger!) | Bursty but bounded sources |
| `onBackpressureBuffer(maxSize)` | Bounded buffer; overflow → error/drop per policy | Safe bounded buffering |
| `onBackpressureBuffer(maxSize, onOverflow, strategy)` | Bounded with `BufferOverflowStrategy` (ERROR / DROP_LATEST / DROP_OLDEST) | Tuned overflow |
| `onBackpressureDrop()` | Drop items that arrive with no demand | Lossy feeds (telemetry) |
| `onBackpressureDrop(consumer)` | Drop + callback per dropped item (for metrics) | Lossy + observable |
| `onBackpressureLatest()` | Keep only the most recent item; drop older | "Current value" feeds (last price, latest position) |
| `onBackpressureError()` | Signal `onError` (`MissingBackpressureException`) on overflow | Fail-fast when loss is unacceptable |

`Flux.create(sink, overflowStrategy)` and the various `Sinks` builders let you choose the strategy at the source. **`Flux.create(emitter, FluxSink.OverflowStrategy.ERROR)`** will throw if the emitter outpaces demand — surfacing the bug instead of silently buffering to OOM. Default for `Flux.create` is `BUFFER` (unbounded) — be aware.

### 3.7 HTTP/2 & gRPC flow control internals

**HTTP/2** multiplexes many logical **streams** over one TCP connection. To prevent a fast sender on one stream from starving others (and to prevent receiver overload), HTTP/2 has its own credit-based flow control *on top of* TCP's:

- Two levels of window: **per-stream** and **per-connection (stream 0)**. A `DATA` frame is bounded by `min(stream window, connection window)`.
- Initial window default: **65,535 bytes (64 KiB − 1)** per stream. Tunable via `SETTINGS_INITIAL_WINDOW_SIZE`.
- The receiver sends **`WINDOW_UPDATE`** frames to grant more credit (in bytes) as its application consumes data.
- Window-zero stalls the stream until a `WINDOW_UPDATE` arrives — exactly TCP's zero-window, one layer up.

**gRPC** rides on HTTP/2, so it inherits this. In gRPC **streaming** RPCs, the application-level backpressure surfaces through the language API:

- In Java gRPC, `StreamObserver` is push-style by default and can overwhelm; for proper backpressure you use the **manual flow control** API: `CallStreamObserver.setOnReadyHandler(...)` plus `isReady()`, and `request(int)` on the server side. You send only while `isReady()` is true; when it goes false, you stop and wait for the on-ready callback. This maps `isReady()`→ "have credit," and the on-ready handler → "credit replenished."

This is why a naive gRPC streaming server that ignores `isReady()` and just calls `onNext()` in a tight loop can OOM: it's buffering in Netty's outbound queue with no flow control. (Covered as an anti-pattern in §6.9.)

### 3.8 The end-to-end backpressure chain (why it must be unbroken)

Backpressure only works if it propagates *all the way to a source that can actually slow down.* A single stage that ignores backpressure (an unbounded buffer, a fire-and-forget thread pool, a `.subscribe()` with `Long.MAX_VALUE` demand) **breaks the chain** and reintroduces unbounded growth. Trace a real Spring WebFlux request reading from a database:

```
[TCP socket] ─► [Netty rwnd] ─► [HTTP/2 window] ─► [WebFlux Flux] ─► [R2DBC driver] ─► [DB]
   bytes           credits         credits           request(n)        fetch-size
```

Each arrow is a backpressure boundary with its own credit mechanism. If the R2DBC driver (the reactive database driver) honors `request(n)` by setting the DB fetch size accordingly, then a slow HTTP client *literally slows the database query's row fetching* — backpressure all the way down. But drop a `.collectList()` in the middle (which requests `Long.MAX_VALUE` to buffer everything into a `List`) and you've broken the chain: the whole result set materializes in memory regardless of the client. **The weakest link defines the system's safety.** Audit every stage.

---

## 4. The complete toolkit

### 4.1 JDK concurrency — bounded queues and their semantics

`java.util.concurrent.BlockingQueue<E>` defines four flavors of each operation, which *is* the strategic trichotomy from §3.2:

| Operation | Throws exception | Returns special value | **Blocks** | Times out |
|---|---|---|---|---|
| Insert | `add(e)` (throws if full) | `offer(e)` (returns `false`) | `put(e)` (**waits**) | `offer(e, time, unit)` |
| Remove | `remove()` (throws if empty) | `poll()` (returns `null`) | `take()` (**waits**) | `poll(time, unit)` |
| Examine | `element()` | `peek()` | — | — |

- `put`/`take` = blocking backpressure. `offer`/`poll` = reject/drop. `offer(e, timeout)` = bounded wait then reject.

**Concrete bounded queue implementations:**

| Class | Bound | Ordering | Lock model | Notes / defaults |
|---|---|---|---|---|
| `ArrayBlockingQueue(capacity[, fair])` | **Bounded (required)** | FIFO | Single lock + 2 conditions | Backing array; optional fairness (default unfair = higher throughput) |
| `LinkedBlockingQueue([capacity])` | Optional; **`Integer.MAX_VALUE` if omitted** (effectively unbounded — foot-gun) | FIFO | Two locks (put/take) → higher throughput | Always pass a capacity in production |
| `LinkedBlockingDeque(capacity)` | Bounded | FIFO/LIFO both ends | Single lock | Work-stealing-ish patterns |
| `SynchronousQueue([fair])` | **Zero capacity** (direct handoff) | n/a | — | A `put` blocks until a `take`; pure rendezvous = strongest backpressure |
| `PriorityBlockingQueue` | **Unbounded** (grows) | Priority | Single lock | No backpressure by capacity — avoid for flow control |
| `DelayQueue` | Unbounded | By delay | — | Scheduling, not flow control |
| `LinkedTransferQueue` | Unbounded | FIFO | Lock-free (CAS) | `transfer()` blocks until consumed; high-throughput |

**Key insight:** Only `ArrayBlockingQueue`, capacity-bounded `LinkedBlockingQueue`/`LinkedBlockingDeque`, and `SynchronousQueue` give you *capacity-based backpressure.* `PriorityBlockingQueue`, `DelayQueue`, `LinkedTransferQueue` are unbounded and will not protect your heap.

### 4.2 Executors / thread pools — the backpressure-relevant knobs

`ThreadPoolExecutor` is where most accidental unbounded buffering hides. Its constructor:

```java
new ThreadPoolExecutor(
    int corePoolSize,        // threads kept alive even if idle
    int maximumPoolSize,     // hard cap on threads
    long keepAliveTime,      // idle timeout for threads above core
    TimeUnit unit,
    BlockingQueue<Runnable> workQueue,    // ◄── THE BACKPRESSURE BUFFER
    ThreadFactory threadFactory,
    RejectedExecutionHandler handler      // ◄── WHAT TO DO WHEN FULL
);
```

The interplay (and the trap): tasks go to **threads up to `corePoolSize`**, then to the **`workQueue`**, then spawn **threads up to `maximumPoolSize`**, then the **`RejectedExecutionHandler`** fires. With an *unbounded* queue, `maximumPoolSize` and the handler are **never reached** — the queue absorbs everything → OOM.

**`RejectedExecutionHandler` implementations (the reject/shed strategies):**

| Handler | Behavior | Maps to |
|---|---|---|
| `AbortPolicy` (default) | Throws `RejectedExecutionException` | Reject / fail fast |
| `CallerRunsPolicy` | The *submitting thread* runs the task itself | **Implicit backpressure** — slows the producer by making it do the work; superb for pipelines |
| `DiscardPolicy` | Silently drops the task | Shed (drop newest) |
| `DiscardOldestPolicy` | Drops the oldest queued task, retries submit | Shed (drop oldest) |
| *custom* | Anything (log, meter, fallback, 429) | Tailored |

`CallerRunsPolicy` is the unsung hero of pipeline backpressure: when the pool+queue are saturated, the producer thread is *commandeered* to execute the task, which means it can't produce more until done — automatic throttling without a separate queue mechanism.

**Virtual threads (Java 21+, Project Loom):** With virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), blocking is cheap, so blocking backpressure (`put`/`take`, blocking I/O) becomes attractive again even at high concurrency — a parked virtual thread costs ~bytes, not a full OS thread (~1 MB stack). But virtual threads **do not bound concurrency by themselves** (the executor is unbounded-task), so you still need a `Semaphore` or bounded queue to limit how much work is in flight, or you OOM on millions of cheap-but-real tasks. Loom changes the *cost* of blocking, not the *need* for bounds.

### 4.3 `Semaphore` — bounding concurrency directly

`java.util.concurrent.Semaphore(permits[, fair])` is the most direct concurrency limiter. `acquire()` blocks (or `tryAcquire([timeout])` rejects) when no permits remain; `release()` returns one. Use it to cap *in-flight* work (bounding `L` in Little's Law, hence bounding latency `W`). Classic admission control: `if (!sem.tryAcquire()) return Response.status(503)`.

### 4.4 Project Reactor — backpressure-relevant API

| API | Purpose | Key params / defaults |
|---|---|---|
| `Flux.range/just/fromIterable` | Backpressure-aware cold sources | Honor `request(n)` natively |
| `Flux.create(sink, OverflowStrategy)` | Bridge a push source | Default `BUFFER` (unbounded!); options: `BUFFER, DROP, LATEST, ERROR, IGNORE` |
| `Flux.generate(stateSupplier, gen)` | Synchronous, one-item-per-request source | Perfect backpressure (1 item per request) |
| `subscription.request(n)` / `BaseSubscriber#hookOnSubscribe` | Express explicit demand | `request(Long.MAX_VALUE)` = unbounded (no backpressure) |
| `limitRate(n)` | Cap demand to `n` and replenish at 75% | Splits a big request into `n`-sized chunks |
| `limitRate(highTide, lowTide)` | Explicit high/low watermark replenish | Tune batching vs latency |
| `onBackpressureBuffer([max][,strategy])` | Buffer overflow handling | Default unbounded; bounded strongly preferred |
| `onBackpressureDrop([onDrop])` | Drop on no demand | Lossy feeds |
| `onBackpressureLatest()` | Keep latest only | "Current value" semantics |
| `onBackpressureError()` | Error on overflow | Fail-fast |
| `publishOn(scheduler[, prefetch])` | Switch downstream thread; introduces a bounded queue | prefetch default 256, replenish 75% |
| `subscribeOn(scheduler)` | Choose subscription/source thread | Affects where source runs |
| `flatMap(fn[, concurrency][, prefetch])` | Concurrent inner subscriptions | concurrency default 256, prefetch 256 |
| `concatMap` | Sequential, prefetch=1-ish, ordered | Strong backpressure, no concurrency |
| `buffer(n)` / `window(n)` / `bufferTimeout(n, dur)` | Batch items | Changes demand relationship |
| `Sinks.many().multicast()/.unicast()/.replay()` | Programmatic publishers with backpressure config | `onBackpressureBuffer`, bounded options |
| `delayElements` / `onBackpressureBuffer` combos | Rate shaping | — |

**Schedulers** (where work runs — relevant because blocking on the wrong scheduler defeats backpressure and starves the event loop):

- `Schedulers.parallel()` — fixed pool sized to CPU cores; for CPU-bound, non-blocking work.
- `Schedulers.boundedElastic()` — bounded pool (default cap = 10 × cores, queue cap 100k) for *blocking* calls; isolates blocking I/O from the event loop.
- `Schedulers.immediate()` / `single()` — current thread / single shared thread.

### 4.5 Messaging / streaming — flow-control config

**Kafka consumer (pull = built-in backpressure):**

| Config | Default | Effect |
|---|---|---|
| `max.poll.records` | 500 | Max records per `poll()` — *the* per-batch backpressure knob |
| `max.poll.interval.ms` | 300000 (5 min) | Max gap between polls before consumer is considered dead → rebalance |
| `fetch.max.bytes` | 52428800 (50 MiB) | Max data per fetch request |
| `max.partition.fetch.bytes` | 1048576 (1 MiB) | Per-partition fetch cap |
| `receive.buffer.bytes` | 65536 (64 KiB) | TCP receive buffer |

If processing is slow, **lower `max.poll.records`** so each poll batch fits within `max.poll.interval.ms`. Monitor **consumer lag** as the backpressure signal.

**Kafka producer (push — must self-throttle):**

| Config | Default | Effect |
|---|---|---|
| `buffer.memory` | 33554432 (32 MiB) | Total memory for unsent records |
| `max.block.ms` | 60000 | How long `send()` blocks when buffer full → then throws |
| `linger.ms` | 0 | Batch delay |
| `acks` | `all` (since 3.0) | Durability vs throughput |

When the producer's `buffer.memory` fills (broker slow), `send()` **blocks up to `max.block.ms`** then throws — that's the producer-side backpressure.

**RabbitMQ:** consumer prefetch via `basic.qos(prefetchCount)` is *exactly* credit-based flow control — the broker sends at most `prefetchCount` unacked messages per consumer. Default unlimited (dangerous); set it. Broker-side **flow control** (memory/disk alarms) blocks publishers when the broker is stressed.

**Reactive Kafka (reactor-kafka):** `KafkaReceiver` integrates Kafka's pull with Reactor demand — `request(n)` controls fetching; honors backpressure end-to-end.

### 4.6 Resilience / admission control libraries (service-boundary flow control)

| Tool | Mechanism | Backpressure role |
|---|---|---|
| **Resilience4j Bulkhead** | `SemaphoreBulkhead` (cap concurrent calls) or `ThreadPoolBulkhead` (bounded pool+queue) | Limits in-flight work; rejects excess |
| **Resilience4j RateLimiter** | Token/refresh-period limiter | Caps request rate; reject or wait |
| **Resilience4j CircuitBreaker** | Open/half-open/closed state machine | Fail fast when downstream is unhealthy (stops feeding a drowning consumer) |
| **Netflix concurrency-limits** | **Adaptive** limits (Gradient/Vegas, TCP-congestion-inspired) | Auto-tunes concurrency limit from observed latency |
| **Envoy / Istio** | Connection/request limits, outlier detection, circuit breaking | Mesh-level flow control |
| **Sentinel (Alibaba)** | Flow rules, system-load adaptive shedding | Adaptive load shedding |
| **Token bucket / leaky bucket** | Classic rate-limiting algorithms | Smooth/cap rate |

**Netflix concurrency-limits** deserves emphasis: it applies TCP-style **adaptive concurrency control** to RPCs. It treats latency the way TCP treats packet loss — rising latency means "the receiver is congested," so it *shrinks* the allowed concurrency (the limit), and *grows* it when latency is healthy. This automatically finds the right concurrency limit without hand-tuning, and it's the modern best-practice for service-to-service flow control.

### 4.7 Token bucket vs leaky bucket (rate-limiting algorithms)

- **Token bucket:** Tokens drip into a bucket at rate `r`; capacity `b`. Each request consumes a token; no token → rejected/queued. Allows **bursts** up to `b`. Used by most API gateways (e.g., Guava `RateLimiter`, Bucket4j).
- **Leaky bucket:** Requests enter a queue that "leaks" (is served) at a constant rate. **Smooths** output to a steady rate; no bursts. Good for protecting a strictly rate-limited downstream.

Both are *producer-side* rate enforcement, complementary to *consumer-side* backpressure.

---

## 5. Code examples by use case

These span genuinely different scenarios, not variants of one.

### 5.1 Classic bounded producer/consumer (blocking backpressure)

The foundational pattern: a fast producer naturally throttled by a bounded queue.

```java
import java.util.concurrent.*;

public class BoundedPipeline {

    // Sentinel object to signal "no more work" to consumers (poison pill pattern).
    private static final String POISON = "__POISON__";

    public static void main(String[] args) throws InterruptedException {
        // Capacity 100 = our entire backpressure budget. When full, producers BLOCK.
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);

        int consumers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(consumers + 1);

        // Producer: tries to push 1,000,000 items as fast as possible.
        pool.submit(() -> {
            try {
                for (int i = 0; i < 1_000_000; i++) {
                    // put() BLOCKS when the queue is full → this is backpressure.
                    // The producer cannot outrun the consumers; the slowest stage governs.
                    queue.put("item-" + i);
                }
                // One poison pill per consumer so each one exits cleanly.
                for (int c = 0; c < consumers; c++) queue.put(POISON);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Consumers: slow workers (simulate 1 ms processing each).
        for (int c = 0; c < consumers; c++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        String item = queue.take();     // blocks when empty
                        if (POISON.equals(item)) break; // graceful shutdown
                        process(item);                  // the slow stage
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);
    }

    static void process(String item) {
        try { Thread.sleep(1); } catch (InterruptedException ignored) {}  // simulate work
    }
}
```

**Why it's correct:** Memory is bounded to ~100 queued items + 4 in-flight regardless of how fast the producer runs. The producer's `put()` blocks whenever consumers fall behind. No OOM is possible. The poison-pill pattern gives clean shutdown without polling a flag.

### 5.2 Thread pool with bounded queue + CallerRunsPolicy (self-throttling ingestion)

An ingestion endpoint that must not OOM under a flood, yet shouldn't drop data if it can help it.

```java
import java.util.concurrent.*;

public class SelfThrottlingExecutor {

    public static ThreadPoolExecutor build() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores,                                  // corePoolSize
            cores * 2,                              // maximumPoolSize
            60L, TimeUnit.SECONDS,                  // idle keep-alive
            new ArrayBlockingQueue<>(1_000),        // BOUNDED queue (not the default unbounded!)
            new ThreadFactory() {                   // named threads for debuggability
                private int n = 0;
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ingest-worker-" + (n++));
                    t.setDaemon(false);
                    return t;
                }
            },
            // CallerRunsPolicy: when pool+queue are full, the SUBMITTING thread runs the task.
            // This stalls the producer (it can't submit again until done) = automatic backpressure,
            // with NO data loss. The flood is throttled by physics, not by dropping.
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public static void main(String[] args) {
        ThreadPoolExecutor exec = build();
        for (int i = 0; i < 100_000; i++) {
            final int id = i;
            // When saturated, this call blocks-equivalent (runs inline) → producer throttled.
            exec.execute(() -> handle(id));
        }
        exec.shutdown();
    }

    static void handle(int id) {
        try { Thread.sleep(2); } catch (InterruptedException ignored) {}
    }
}
```

**Tradeoff:** `CallerRunsPolicy` blocks the calling thread. In a web server where the caller is a request-handling thread, this *also* slows request acceptance (the accept loop stalls) — which is *exactly* the backpressure you want (the OS socket backlog fills, the load balancer sees you as busy and routes elsewhere), but you must understand it's pushing pressure to the network layer. For an internal batch importer, it's ideal.

### 5.3 Reactor: end-to-end backpressure with explicit demand and slow consumer

Demonstrating `request(n)` and a slow subscriber that throttles a fast source.

```java
import reactor.core.publisher.Flux;
import reactor.core.publisher.BaseSubscriber;
import java.time.Duration;

public class ReactorBackpressure {

    public static void main(String[] args) throws InterruptedException {
        Flux<Integer> source = Flux.range(1, 1_000)
            .doOnRequest(n -> System.out.println(">> upstream got request for " + n));

        source.subscribe(new BaseSubscriber<Integer>() {
            // Called once at subscription: we set INITIAL demand to 10, not unbounded.
            @Override
            protected void hookOnSubscribe(reactor.core.publisher.Subscription s) {
                request(10);   // grant 10 credits — backpressure: source emits at most 10
            }

            // Called per item, never more than current demand.
            @Override
            protected void hookOnNext(Integer value) {
                slowProcess(value);
                // After every 10 items, ask for 10 more (manual credit replenishment).
                if (value % 10 == 0) {
                    System.out.println("-- processed batch up to " + value + ", requesting 10 more");
                    request(10);
                }
            }

            @Override
            protected void hookOnComplete() {
                System.out.println("done");
            }
        });

        Thread.sleep(15_000); // let it run (slowProcess is slow)
    }

    static void slowProcess(int v) {
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    }
}
```

**What you'll observe:** the source emits in chunks of 10, *only when requested*. `Flux.range` honors demand natively — it never produces ahead. Memory between source and subscriber never exceeds ~10 items. This is non-blocking backpressure: no thread is parked in a queue; demand is a number passed upstream.

### 5.4 Reactor: bridging a fast push source with a bounded overflow policy

A real-time price feed (push, can't be slowed) into a slower consumer — keep only the latest, never OOM.

```java
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import java.time.Duration;

public class PriceFeedBackpressure {

    public static void main(String[] args) throws InterruptedException {
        // Simulate an external push feed: ~1000 ticks/sec, ignores demand entirely.
        Flux<Double> rawFeed = Flux.create(sink -> startFeed(sink),
                FluxSink.OverflowStrategy.ERROR);   // surface bugs instead of silent unbounded buffer

        rawFeed
            // We only care about the CURRENT price; drop stale ticks if we fall behind.
            .onBackpressureLatest()
            // Move to a separate thread; introduces a bounded handoff queue.
            .publishOn(reactor.core.scheduler.Schedulers.single())
            .subscribe(price -> {
                slowConsume(price);   // e.g., recompute a risk metric — slow
            }, err -> System.err.println("feed error: " + err));

        Thread.sleep(10_000);
    }

    static void startFeed(FluxSink<Double> sink) {
        Thread t = new Thread(() -> {
            double p = 100.0;
            while (!sink.isCancelled()) {
                p += (Math.random() - 0.5);
                sink.next(p);                 // push, regardless of downstream demand
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    static void slowConsume(double price) {
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}  // 50/s capacity vs 1000/s feed
    }
}
```

**Why `onBackpressureLatest`:** For a price feed, an old tick is *worthless* — you want the freshest. So under overload we discard intermediate ticks and the consumer always processes the most recent price. Memory stays at ~1 item. If you'd used `onBackpressureBuffer` (unbounded), you'd OOM in seconds at 950 surplus ticks/sec. `OverflowStrategy.ERROR` on `Flux.create` is a safety net for the create-side buffer.

### 5.5 Service-boundary admission control with a Semaphore (load shedding / 429)

A web handler that protects itself by capping concurrency and rejecting (not buffering) excess.

```java
import java.util.concurrent.Semaphore;

public class AdmissionControl {

    // Cap concurrent in-flight requests. Bounds L in Little's Law → bounds latency W.
    // Sized from: maxConcurrency ≈ targetLatency × throughputCapacity (and below thread/CPU limits).
    private final Semaphore permits = new Semaphore(200, /*fair=*/false);

    public Response handle(Request req) {
        // Non-blocking acquire: if no permit, REJECT immediately (don't queue → no bufferbloat).
        if (!permits.tryAcquire()) {
            // 503 + Retry-After tells the client/LB to back off. Reject > buffer under overload.
            return Response.status(503)
                           .header("Retry-After", "1")
                           .body("overloaded");
        }
        try {
            return doWork(req);   // bounded number of these run at once
        } finally {
            permits.release();    // ALWAYS release, even on exception → no permit leak
        }
    }

    // ... doWork, Request, Response elided
    interface Request {}
    static class Response {
        static Response status(int s) { return new Response(); }
        Response header(String k, String v) { return this; }
        Response body(String b) { return this; }
    }
    Response doWork(Request r) { return new Response(); }
}
```

**Why reject, not buffer:** Under sustained overload, queuing requests only inflates latency until clients time out and retry (metastable collapse, §2.7). Fast rejection with `503 Retry-After` keeps accepted-request latency bounded and lets the load balancer route around the hot instance. The `finally` release is non-negotiable — a leaked permit permanently shrinks capacity.

### 5.6 Adaptive concurrency limit (TCP-congestion-style, self-tuning)

Hand-tuned limits go stale. This sketch mirrors what Netflix concurrency-limits does — adjust the limit from observed latency.

```java
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Simplified AIMD-style adaptive limiter (Additive Increase, Multiplicative Decrease),
 *  the same control law TCP uses for cwnd. Real libs (Gradient2/Vegas) are smarter. */
public class AdaptiveLimiter {
    private volatile int limit = 20;                 // current concurrency limit
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong minRttNanos = new AtomicLong(Long.MAX_VALUE);

    public boolean tryAcquire() {
        if (inFlight.incrementAndGet() > limit) {     // over the limit → reject (shed)
            inFlight.decrementAndGet();
            return false;
        }
        return true;
    }

    public void onSuccess(long rttNanos) {
        inFlight.decrementAndGet();
        long minRtt = Math.min(minRttNanos.get(), rttNanos);
        minRttNanos.set(minRtt);
        // If latency is near the best-ever (no queueing), grow the limit additively.
        if (rttNanos < minRtt * 2) {
            limit = Math.min(limit + 1, 1000);
        } else {
            // Latency inflated → downstream congested → shrink multiplicatively (back off hard).
            limit = Math.max((int) (limit * 0.9), 5);
        }
    }

    public void onDrop() {                            // timeout / rejection from downstream
        inFlight.decrementAndGet();
        limit = Math.max((int) (limit * 0.8), 5);     // strong backoff, like TCP packet loss
    }
}
```

**Why adaptive beats static:** A fixed limit is wrong the moment downstream capacity changes (a deploy, a GC pause, a noisy neighbor). The adaptive limiter treats *rising latency as the congestion signal* — exactly TCP's philosophy — and continuously finds the concurrency that keeps queues empty. In production, use the battle-tested library rather than this sketch.

### 5.7 Kafka consumer: pull-based backpressure with bounded in-flight processing

Pull is backpressure, but you must keep each poll cycle within `max.poll.interval.ms`.

```java
import org.apache.kafka.clients.consumer.*;
import java.time.Duration;
import java.util.*;

public class BackpressuredConsumer {
    public static void main(String[] args) {
        Properties p = new Properties();
        p.put("bootstrap.servers", "localhost:9092");
        p.put("group.id", "bp-demo");
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("enable.auto.commit", "false");          // commit manually after processing
        // BACKPRESSURE KNOB: small batches so each poll cycle finishes within max.poll.interval.ms.
        p.put("max.poll.records", "50");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of("orders"));
            while (true) {
                // poll() is the PULL: broker never pushes; we fetch when ready → natural backpressure.
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    processSlowly(r);                  // the slow stage
                }
                // Commit only what we've fully processed → at-least-once with bounded re-delivery.
                consumer.commitSync();
                // If processSlowly were too slow for 50 records within 5 min, lower max.poll.records,
                // OR call consumer.pause()/resume() to explicitly stop fetching while a backlog drains.
            }
        }
    }

    static void processSlowly(ConsumerRecord<String, String> r) {
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
    }
}
```

**The `pause()/resume()` escape hatch:** If you offload records to a bounded internal queue/executor, and that fills, call `consumer.pause(partitions)` to *stop fetching* without leaving the group, then `resume()` when the backlog drains. This is explicit backpressure layered on Kafka's pull model — vital when processing is async.

### 5.8 gRPC server-streaming with manual flow control (don't OOM the outbound buffer)

```java
import io.grpc.stub.*;

public class StreamingService extends DataServiceGrpc.DataServiceImplBase {

    @Override
    public void streamData(Request req, StreamObserver<Item> responseObserver) {
        // Downcast to the server-side flow-control-aware observer.
        ServerCallStreamObserver<Item> obs = (ServerCallStreamObserver<Item>) responseObserver;

        Iterator<Item> source = produce(req);   // potentially huge / fast source

        // onReadyHandler fires when the transport regains capacity (credit replenished).
        obs.setOnReadyHandler(() -> {
            // Send ONLY while isReady() — i.e., while we have HTTP/2 flow-control credit.
            // Skipping this check and looping onNext() = buffering in Netty = OOM under a slow client.
            while (obs.isReady() && source.hasNext()) {
                obs.onNext(source.next());
            }
            if (!source.hasNext()) {
                obs.onCompleted();
            }
            // If isReady() went false, we stop; the handler will be re-invoked when ready again.
        });
    }

    Iterator<Item> produce(Request r) { return java.util.Collections.emptyIterator(); }
    // Request, Item, DataServiceGrpc elided
    interface Request {} interface Item {}
    static class DataServiceImplBase {}
    static class DataServiceGrpc { static class DataServiceImplBase {} }
}
```

**The bug this avoids:** A naive server does `for (Item i : items) responseObserver.onNext(i);`. If the client reads slowly, each `onNext` queues bytes in the server's Netty outbound buffer faster than the (flow-controlled) socket drains → unbounded growth → OOM. Honoring `isReady()` is the gRPC way to respect HTTP/2 flow-control credit.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Bounded queues cost contention.** `ArrayBlockingQueue` has a single lock; under high producer/consumer concurrency this lock becomes the bottleneck. `LinkedBlockingQueue` uses two locks (put/take) for better throughput. For extreme throughput, consider the **LMAX Disruptor** (a ring-buffer-based, lock-free inter-thread messaging library that achieves millions of ops/sec by avoiding locks and false sharing) — it implements backpressure via the ring buffer's bounded capacity and sequence barriers.
- **Batching is the throughput lever.** Replenishing demand one item at a time (request(1), get 1, request(1)…) ping-pongs and kills throughput. Reactor's 75%-low-watermark batching and Kafka's `max.poll.records` batches amortize the per-item overhead. Tune batch size up for throughput, down for latency and memory.
- **Avoid blocking on event-loop threads.** In Reactor/Netty, blocking inside `map`/`flatMap` on a `parallel` scheduler thread stalls all connections that thread serves. Offload blocking calls to `Schedulers.boundedElastic()` — which *itself* is bounded (default cap 10× cores) and thus a backpressure point.
- **False sharing:** high-frequency counters (in-flight, demand) on adjacent cache lines cause cache-line ping-pong across cores. The Disruptor and JCTools queues pad fields to avoid this. Relevant only at extreme rates.

### 6.2 Correctness & concurrency

- **Always loop on condition waits** (`while`, not `if`) — spurious wakeups are real.
- **Honor the Reactive Streams serialization rule** — never call a subscriber's `onNext`/`onError`/`onComplete` concurrently. Use Reactor's `Sinks` (which handle this) rather than hand-rolling.
- **`Long.MAX_VALUE` demand = no backpressure.** Many bugs are a stray operator (`collectList`, `toIterable`, a `subscribe()` with no demand control) requesting unbounded. Audit pipelines for these.
- **Release permits/credits in `finally`.** A leaked semaphore permit or un-replenished credit permanently shrinks capacity and eventually deadlocks the pipeline.
- **Backpressure deadlock:** if A→B→A (cyclic dependency) and both are bounded, each can block waiting for the other to drain. Break cycles or make at least one edge sheddable.

### 6.3 Memory

- **The whole point is bounding memory.** Every buffer must have a maximum. The audit question: "If the consumer stopped entirely right now, what is the maximum memory this stage can consume?" If the answer is "unbounded," fix it.
- **Watch hidden buffers:** Netty outbound buffers, HTTP client connection-pool queues, logging frameworks' async appenders (Logback `AsyncAppender` has a `queueSize`, default 256, and a `neverBlock` flag — if true it *drops* logs, if false it *blocks* the app thread!), JDBC fetch buffers, OS socket buffers.
- **Off-heap and direct buffers** (Netty) also count — they don't trigger GC pressure but can exhaust native memory and the container's RSS limit, getting you OOM-killed by the kernel/cgroup even with a healthy heap.

### 6.4 Security

- **Backpressure is a DoS defense.** Without admission control, an attacker (or a buggy client) can exhaust your memory/threads with a flood. Bounded queues + rejection + rate limiting are baseline DoS hardening.
- **Slowloris-style attacks** exploit *slow consumers/producers* to tie up connections. Flow control + connection/idle timeouts mitigate.
- **Retry amplification** can be weaponized: ensure clients (and your own retry logic) use **retry budgets** and **exponential backoff with jitter** so failures don't amplify into a self-DoS.

### 6.5 Observability

You cannot operate backpressure you can't see. Instrument:

- **Queue depth / fill ratio** (gauge) — per bounded queue. Alert when sustained > 80%.
- **Rejection / drop counts** (counter) — every shed/reject must be metered (this is why `onBackpressureDrop(consumer)` and custom `RejectedExecutionHandler` exist). Silent drops are how you lose data invisibly.
- **In-flight count** (gauge) vs the limit — for semaphores/bulkheads/adaptive limiters.
- **Latency percentiles** (p50/p95/p99/p999) — rising tail latency is the leading indicator of backpressure/queueing.
- **Consumer lag** (Kafka) — the single best backpressure signal in streaming systems.
- **TCP/HTTP2:** zero-window events, `WINDOW_UPDATE` rates, retransmits (via `ss -i`, eBPF, or service-mesh telemetry).
- **GC and heap:** rising old-gen usage with no recovery = a buffer is growing unbounded.

### 6.6 Cost

- Larger buffers = more memory = bigger instances = more money. Backpressure that drops/rejects appropriately lets you run *smaller, cheaper* instances safely instead of over-provisioning to absorb every spike.
- Durable queues (Kafka/SQS) trade compute cost for storage cost and latency — cheaper to absorb bursts on disk than to keep idle compute hot.

### 6.7 Testability

- **Test the overload path explicitly.** Most teams test the happy path; backpressure bugs only appear under overload. Write tests that submit faster than the consumer drains and assert (a) bounded memory, (b) correct shed/reject behavior, (c) recovery after the burst.
- **Reactor:** `StepVerifier` with `.thenRequest(n)` and `.thenAwait()` lets you assert demand-driven behavior deterministically. `VirtualTimeScheduler` tests time-based operators without real waits.
- **Load tests** (k6, Gatling, wrk2) with *open-model* load (constant arrival rate, not closed-loop) reveal metastable collapse that closed-loop tests hide. (Closed-loop testers wait for a response before sending the next request — they *accidentally apply backpressure themselves*, masking the bug. Open-model testers send at a fixed rate regardless, exposing it.)
- **Chaos:** inject downstream latency/failures and verify the system sheds rather than collapses.

### 6.8 Production hardening checklist

- Every queue bounded; every bound chosen deliberately and documented.
- Every reject/drop metered and alertable.
- Retry budgets + exponential backoff + jitter everywhere clients retry.
- Timeouts at every hop, shorter as you go deeper (so inner calls fail before outer ones give up).
- Load shedding at the edge (admission control) before resources are committed.
- Circuit breakers around dependencies so you stop feeding a drowning consumer.
- Backpressure chain audited end-to-end (§3.8) — no unbounded link.
- Graceful degradation defined: what do you drop first when overloaded? (Shed low-value work, protect high-value.)

### 6.9 Anti-patterns to avoid

| Anti-pattern | Why it's dangerous | Fix |
|---|---|---|
| `Executors.newFixedThreadPool(n)` / `newCachedThreadPool()` | Unbounded `LinkedBlockingQueue` (fixed) / unbounded thread creation (cached) → OOM | Construct `ThreadPoolExecutor` with a bounded queue + reject policy |
| `new LinkedBlockingQueue<>()` (no capacity) | Effectively unbounded (`Integer.MAX_VALUE`) | Always pass a capacity |
| `onBackpressureBuffer()` (no max) in Reactor | Unbounded buffer → OOM | `onBackpressureBuffer(max, strategy)` |
| Naive gRPC/Netty `onNext()` loop ignoring `isReady()` | Unbounded outbound buffer → OOM | Honor `isReady()` / on-ready handler |
| Retry without budget/backoff | Retry storm → metastable collapse | Budget + exponential backoff + jitter |
| Buffering under sustained overload | Bufferbloat → latency explosion → collapse | Reject/shed; bound latency |
| Closed-loop-only load testing | Masks metastable collapse | Open-model load tests |
| `collectList()`/`block()` mid-pipeline | Materializes everything; breaks backpressure chain | Stream through; avoid full materialization |
| Unbounded log async appender that blocks (or silently drops) | Stalls app or loses logs invisibly | Bounded queue + explicit, metered drop policy |
| Treating a durable queue as a fix for sustained `λ_p>μ` | Lag grows forever; eventually drops oldest / fills disk | Scale consumers or shed at producer |

---

## 7. Advanced topics & deep internals

### 7.1 The bufferbloat phenomenon in depth

**Bufferbloat** (a term from networking, coined by Jim Gettys ~2011) describes high latency caused by *excessively large buffers* absorbing congestion instead of signaling it. A big buffer "helps" throughput (fewer drops) but destroys latency: packets/items sit in a deep queue for a long time before service. The fix in networking is **AQM (Active Queue Management)** — algorithms like **CoDel** (Controlled Delay) and **FQ-CoDel** that monitor the *time* a packet spends in the queue and drop/mark packets when sojourn time exceeds a target (CoDel's target is 5 ms, interval 100 ms), deliberately signaling congestion early rather than letting the buffer bloat.

The application-layer lesson: **size buffers by acceptable latency, not by "as much as we can hold."** A buffer that can hold 10 seconds of work at your service rate guarantees 10-second tail latency under load. Compute max queue depth as `acceptableLatency × serviceRate`. This is the single most useful queue-sizing formula and it comes straight from Little's Law.

### 7.2 CoDel-style time-based shedding in applications

You can apply CoDel's idea at the app layer: instead of (or in addition to) capping queue *length*, track how long each item has waited and **drop items whose wait exceeds a target** (they're probably past their client's timeout anyway — shedding them is free goodput). Facebook/Meta documented exactly this in their server load-shedding (a CoDel-inspired controller measuring queue sojourn time). This is superior to length-based shedding because it directly targets latency, the thing you actually care about, and adapts as service rate changes.

### 7.3 Sizing bounded queues — the real math

Given target tail latency `T` and service rate `μ` (items/s the consumer sustains):

```
maxQueueDepth ≈ μ × T
```

Example: consumer does `μ = 500 items/s`, you accept `T = 200 ms` worst-case queue wait → `maxQueueDepth = 500 × 0.2 = 100`. Add headroom for bursts, but not so much that you exceed `T`. For thread pools, also ensure `maximumPoolSize` matches your CPU/IO profile (CPU-bound: ~cores; IO-bound: higher, bounded by downstream connection limits). The queue is the *shock absorber*; the pool size + downstream capacity is the *drain rate*.

### 7.4 AIMD and control theory under the hood

TCP's congestion control and adaptive concurrency limiters use **AIMD — Additive Increase, Multiplicative Decrease.** Increase the limit by a constant each success period; on congestion, multiply it by a factor < 1 (e.g., 0.5–0.9). AIMD provably converges to a fair, stable share of capacity among competing flows — that's why it's chosen. More sophisticated controllers (Vegas, BBR, Gradient2) use **latency/RTT as the congestion signal** instead of loss/rejection, reacting *before* the buffer fills (proactive vs reactive). The deep point: backpressure is a **feedback control system**, and control theory (gain, damping, oscillation, hysteresis) explains why poorly-tuned limiters *oscillate* (thrash between throttling and flooding) — too-aggressive multiplicative decrease causes ringing.

### 7.5 Credit replenishment policies (the watermark question)

When do you grant more credit? Three policies:

- **Per-item (eager):** replenish 1 credit per item processed. Lowest memory, lowest latency, but maximal signaling overhead (ping-pong). Bad for throughput.
- **Batch / low-watermark:** grant a big batch (e.g., 256), and grant the *next* batch when consumption hits a low watermark (e.g., 75% consumed = Reactor's default). Amortizes overhead while keeping the pipe full — the sweet spot.
- **Full-drain:** grant a batch, wait until *all* of it is consumed, then grant the next. Simple but creates a stop-start sawtooth (the pipe empties before refill) — wastes throughput.

Reactor uses low-watermark (75%). TCP uses a sliding window that effectively replenishes continuously as ACKs arrive. Choose batch size and watermark to balance throughput (bigger/earlier) vs memory and latency (smaller/later).

### 7.6 Flink credit-based flow control (a production case study)

Apache Flink (a stream processor) historically used TCP-based backpressure: when a downstream task was slow, TCP backpressure stalled the *shared* TCP connection — but because many logical channels (subtasks) shared one TCP connection, one slow channel blocked *all* of them (head-of-line blocking). Flink 1.5 introduced **credit-based flow control** at the application layer: each receiving subtask advertises **buffer credits** (how many network buffers it has free) to each sender; senders only transmit up to available credit *per channel*. This eliminated cross-channel head-of-line blocking and gave fine-grained backpressure. The lesson generalizes: **per-stream credit beats shared-connection blocking** — exactly why HTTP/2 added per-stream windows over TCP.

### 7.7 Head-of-line blocking and why it motivates multi-level flow control

**Head-of-line (HOL) blocking:** when one stalled item at the front of a shared queue/connection blocks all items behind it, even unrelated ones. TCP suffers HOL blocking (a single lost packet stalls the whole byte stream until retransmit) — which is *why* HTTP/3 moved to **QUIC** (a UDP-based transport with independent per-stream delivery, so one stream's loss doesn't stall others). The flow-control implication: a single shared buffer/window for independent work creates HOL coupling; **per-stream/per-key/per-tenant credits** decouple them. This is also why you isolate tenants with **bulkheads** (separate bounded pools per tenant/dependency) so one tenant's overload can't starve others.

### 7.8 Reactive Streams operator fusion (Reactor internals)

Reactor implements **operator fusion** — an optimization where adjacent operators collapse to avoid per-item queueing and request signaling overhead. Two kinds:

- **Macro-fusion:** replace operator combinations with a specialized single operator (e.g., `fromArray().map()` fuses).
- **Micro-fusion (`QueueSubscription`):** when an upstream is queue-based, downstream can *poll* directly from the upstream's queue instead of going through `onNext` + `request`, eliminating the demand-signaling ping-pong across the boundary. Operators that support it implement `Fuseable`. This is invisible to you but explains why Reactor pipelines are fast despite the apparent per-item protocol overhead. Fusion is disabled across async boundaries (`publishOn`) where real queueing/threading must happen.

### 7.9 The `Long.MAX_VALUE` "no backpressure" mode and when it's correct

Requesting `Long.MAX_VALUE` means "I have unbounded demand — never backpressure me." It's *correct* when the consumer genuinely cannot be overwhelmed: e.g., it writes to an unbounded-but-cheap sink, or the source is intrinsically bounded and small. It's a *bug* when used reflexively (the default of many naive `.subscribe()` calls) on an unbounded fast source. Reactor's default `subscribe()` with a lambda requests `Long.MAX_VALUE` — fine for bounded sources, dangerous for hot/infinite ones. Know which you have.

### 7.10 Backpressure vs rate limiting vs load shedding vs circuit breaking (precise distinctions)

These overlap and are often conflated. Precisely:

- **Backpressure:** *feedback* from consumer to producer to slow the producer to the consumer's rate. Bidirectional, dynamic, demand-driven. (The float valve.)
- **Rate limiting:** *unilateral* cap on the producer's rate, set by policy (e.g., 1000 req/s per API key), independent of consumer's current capacity. (The speed limit sign.)
- **Load shedding:** *dropping* work when overloaded to protect the system, choosing what to discard. (Triage.)
- **Circuit breaking:** *stopping* calls to an unhealthy dependency entirely for a cooldown, to let it recover and to fail fast. (The fuse.)

A mature system uses all four: backpressure within a process, rate limiting at the edge per client, load shedding under overload, circuit breakers around dependencies.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Choosing a backpressure response (buffer / block / reject / shed)

| Situation | Recommended response | Rationale |
|---|---|---|
| Internal pipeline, source can pause (file, stream, batch) | **Block** (bounded queue `put`, `CallerRunsPolicy`) | Pressure propagates to a pausable source; no loss |
| Public API / service ingress under overload | **Reject** (429/503 + Retry-After) | Can't block external callers; bound accepted-request latency |
| Lossy-tolerant feed (metrics, logs, live video, telemetry) | **Shed** (drop oldest/sample/latest) | Loss acceptable; freshness/availability matters more |
| Bursty but bounded total volume | **Bounded buffer** sized by `μ×T` | Absorb burst, drain after |
| Sustained `λ_p > μ` | **Scale consumers / shed at producer** | No buffer fixes a permanent deficit |
| "Current value" semantics (latest price, position) | **`onBackpressureLatest`** | Old values worthless |
| Must never lose, can tolerate latency | **Durable queue** (Kafka/SQS) + pull | Disk-backed buffering, drain at `μ` |

### 8.2 Push vs pull vs reactive — when to use each

| Use | Choose | Why |
|---|---|---|
| Thread-per-request, modest concurrency, blocking I/O is fine | **Pull / blocking** (`BlockingQueue`, JDBC, virtual threads) | Simplest; implicit backpressure; Loom makes blocking cheap |
| High concurrency, non-blocking I/O, streaming | **Reactive (demand/credit)** | No blocked threads; explicit backpressure; composable |
| Fire-and-forget, lossy, simplest possible | **Push** + bounded drop | Lowest complexity; accept loss |
| Cross-service decoupling, durability, bursty | **Durable queue (pull)** | Time-decoupling; replay; backlog visibility |

### 8.3 Static vs adaptive concurrency limits

| | Static limit (Semaphore/Bulkhead) | Adaptive limit (concurrency-limits) |
|---|---|---|
| Setup | Pick a number | Auto-tunes from latency |
| Accuracy as conditions change | Goes stale | Tracks capacity changes |
| Complexity | Trivial | Higher (control loop, can oscillate) |
| Predictability | Very predictable | Less so; needs good defaults/bounds |
| Use when | Stable, well-understood downstream | Variable downstream, multi-tenant, autoscaled |

### 8.4 In-memory bounded queue vs durable broker

| | In-memory bounded queue | Durable broker (Kafka/SQS/Rabbit) |
|---|---|---|
| Latency | Microseconds | Milliseconds+ |
| Durability | Lost on crash | Survives crash, replayable |
| Backlog capacity | Heap-bounded (small) | Disk/retention-bounded (large) |
| Decoupling | Same process | Cross-service, cross-time |
| Backpressure on overflow | block/reject/drop | producer blocks (`buffer.memory`)/lag grows |
| Use when | Intra-process pipeline | Inter-service, durability, big bursts |

---

## 9. Failure modes & debugging

### 9.1 OutOfMemoryError from an unbounded buffer

**Symptom:** Heap climbs steadily under load, full GCs increase, eventually `java.lang.OutOfMemoryError: Java heap space` (or `GC overhead limit exceeded`); process dies or thrashes.

**Diagnosis:**
- Heap histogram: `jmap -histo:live <pid>` or `jcmd <pid> GC.class_histogram` — look for huge counts of your work-item class or `Node`/`Object[]` from a queue.
- Heap dump on OOM (always set `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...`); analyze in **Eclipse MAT** — the dominator tree reveals which queue/list retains the heap.
- Check for the usual suspects: `Executors.newFixedThreadPool` (unbounded `LinkedBlockingQueue`), `onBackpressureBuffer()` without max, Netty outbound buffers, `collectList` on a large stream.

**Fix:** Bound the buffer; add reject/shed; audit the chain (§3.8).

### 9.2 Metastable collapse / retry storm

**Symptom:** Throughput stays high but **goodput crashes**; p99 latency at the client-timeout ceiling; load doesn't recover after the trigger is gone. Retries multiply request rate (visible as 2–5× upstream traffic).

**Diagnosis:**
- Compare *served* requests vs *successful within client timeout* (goodput). A big gap = serving zombie requests.
- Look at retry rates and request-rate amplification (downstream sees more than upstream sends).
- Queue/in-flight metrics pinned at max; latency at the timeout ceiling.

**Fix (to break the loop):** Shed aggressively (shrink/clear queues), enforce retry budgets + backoff + jitter, add circuit breakers, drop work older than its deadline (CoDel-style, §7.2). You often must *manually* relieve load to escape the metastable state — it won't self-heal.

### 9.3 Latency cliff (bufferbloat)

**Symptom:** Throughput looks fine but latency is terrible and unstable; deep queues. Often a buffer sized "generously."

**Diagnosis:** Plot queue depth vs latency — they track. Compute `currentDepth/μ` = the latency you've baked in.

**Fix:** Shrink the buffer to `μ×T`; add time-based shedding.

### 9.4 Thread pool exhaustion / deadlock

**Symptom:** Requests hang; thread dump shows all pool threads blocked (e.g., in `BlockingQueue.put`, or waiting on a downstream that's waiting back). `jstack <pid>` reveals the pile-up.

**Diagnosis:**
- `jstack`/`jcmd <pid> Thread.print` — count threads `BLOCKED`/`WAITING` and on what monitor/condition.
- Look for cyclic blocking (bulkhead A waits on B which waits on A) → backpressure deadlock.

**Fix:** Use timeouts on blocking ops (`offer(timeout)`, `tryAcquire(timeout)`); break dependency cycles; size pools to avoid mutual exhaustion; isolate with separate bulkheads.

### 9.5 Reactor `MissingBackpressureException` / `OverflowException`

**Symptom:** `reactor.core.Exceptions$OverflowException: Could not emit ... due to lack of requests` or `MissingBackpressureException`.

**Cause:** A fast source (hot, `Flux.create`/`Sinks` with bounded/error strategy, `interval`) outpaced downstream demand and the chosen strategy is ERROR.

**Diagnosis:** Find the source operator and its overflow strategy; add `.log()` / `doOnRequest`/`doOnNext` to trace demand vs emission. Reactor's `onOperatorDebug()` / `checkpoint()` adds assembly traces to find the offending operator.

**Fix:** Choose an explicit strategy (`onBackpressureBuffer(max)` / `Drop` / `Latest`) appropriate to your loss tolerance; slow the source; increase downstream throughput.

### 9.6 Kafka consumer rebalance loop / lag growth

**Symptom:** Consumer repeatedly leaves the group ("rebalance storm"); lag grows; duplicate processing.

**Cause:** A poll cycle exceeded `max.poll.interval.ms` (processing too slow for the batch).

**Diagnosis:** Broker/consumer logs show rebalances; measure per-record processing time × `max.poll.records` vs `max.poll.interval.ms`. Monitor lag via `kafka-consumer-groups.sh --describe --group <g>` or Burrow/Cruise Control.

**Fix:** Lower `max.poll.records`; raise `max.poll.interval.ms` (carefully); use `pause()/resume()`; or scale out consumers / speed up processing.

### 9.7 TCP zero-window / stalled streams

**Symptom:** A connection appears stalled; sender can't send. The receiver advertised window zero (its app isn't reading fast enough).

**Diagnosis:** `ss -ti` (shows `cwnd`, `rwnd`, retransmits), `tcpdump`/Wireshark shows "TCP Zero Window" / "Window Update" frames; eBPF tools. For HTTP/2: look for stalled streams awaiting `WINDOW_UPDATE`.

**Fix:** Usually means the *application* on the receiver is slow — the real fix is speeding/parallelizing the receiver, not tuning TCP. This is backpressure working as designed, surfacing a slow consumer.

### 9.8 Real-world incident patterns (representative)

- **The unbounded executor OOM:** A team uses `Executors.newFixedThreadPool(50)` for an importer. A downstream slowdown causes tasks to queue in the default unbounded queue; heap fills; OOM crash loses all in-flight work. Fix: bounded `ThreadPoolExecutor` + `CallerRunsPolicy`.
- **The retry-storm brownout:** A dependency has a 2-second blip. Clients with aggressive retries (no backoff) triple the load; the service enters metastable collapse and stays down for 40 minutes until operators manually shed load and disable retries. Fix: retry budgets, backoff+jitter, circuit breakers, deadline-aware shedding. (This pattern is documented across Google SRE, AWS, and the metastable-failures literature.)
- **The bufferbloat latency SLO miss:** A team "fixes" occasional drops by enlarging a queue 100×; drops stop but p99 latency blows the SLO because items now wait seconds in the deep buffer. Fix: shrink buffer to `μ×T`, shed by deadline.

---

## 10. Interview drill

### Q1. What is backpressure and why does unbounded buffering cause failures?
**Model answer:** Backpressure is feedback from a consumer to a producer telling it to slow to the consumer's sustainable rate. A pipeline can only sustain its slowest stage's throughput; if a faster producer has no feedback path, the rate difference accumulates in a buffer. An *unbounded* buffer grows without limit when `λ_producer > μ_consumer` is sustained, leading to OOM and to bufferbloat (unbounded latency), which can trigger client timeouts and retries — a self-sustaining metastable collapse. Bounded buffers + a defined overflow response (block/reject/shed) prevent this.
- *Follow-up: Why is bounded latency under overload better than serving everything?* Because work completed after a client times out is wasted capacity (goodput collapse), and slow responses fuel retries that amplify load. Bounding `L` (via Little's Law) bounds `W`.
- *Follow-up: Where does the buffer actually live if you don't manage it?* In hidden places: thread-pool work queues, Netty outbound buffers, OS socket buffers, broker backlogs, async log appenders — each a latent OOM.
- *Follow-up: Give the formula relating queue size to latency.* `W ≈ L/λ`; max queue depth ≈ `μ × acceptableLatency`.

### Q2. Contrast push, pull, and reactive (demand-based) models.
**Model answer:** Push: producer controls rate, no built-in backpressure, must buffer/drop. Pull: consumer controls rate (`iterator.next()`), implicit backpressure, but typically blocks a thread. Reactive: consumer signals demand (`request(n)`) and producer pushes up to that, asynchronously — pull's safety with push's thread-efficiency. Reactive Streams standardizes this as credit-based flow control.
- *Follow-up: Why is pure pull thread-expensive?* A thread blocked in a synchronous pull does no other work; at high concurrency you exhaust threads. (Loom/virtual threads mitigate this.)
- *Follow-up: How does demand propagate in Reactor?* Demand flows upstream (subscriber→source via `request`), data flows downstream; operators may rewrite demand (filter requests more than it forwards); batching replenishes at a low watermark.

### Q3. Explain Little's Law and how it's used to size a system.
**Model answer:** `L = λ × W`: average items in system = arrival rate × time in system, for any stable system. Used to bound latency by bounding concurrency: cap `L` (semaphore/queue) and since `λ` is fixed, `W` is bounded. Used to size queues: max depth = `μ × targetLatency`.
- *Follow-up: What assumption does it require?* Stability (steady state, `λ ≤ μ`); it doesn't require any particular distribution.
- *Follow-up: If λ=2000/s and target W=50ms, what concurrency do you provision?* `L = 2000 × 0.05 = 100` in-flight; set the limit around there (plus burst headroom).

### Q4. How does TCP implement flow control, and how is it analogous to application backpressure?
**Model answer:** The receiver advertises a receive window (`rwnd`) in bytes in every ACK; the sender may have at most `rwnd` unacked bytes outstanding. A slow-reading receiver shrinks the window toward zero, stalling the sender — credit-based flow control. (Distinct from congestion control, which protects the *network* via `cwnd`.) Analogy: `rwnd` ↔ free buffer capacity; window-zero ↔ a full bounded queue / zero demand.
- *Follow-up: Difference between flow control and congestion control?* Flow control protects the receiver (`rwnd`); congestion control protects the network (`cwnd`, slow-start/CUBIC/BBR). Sender limited by `min(rwnd, cwnd)`.
- *Follow-up: What does HTTP/2 add and why?* Per-stream and per-connection windows (`WINDOW_UPDATE`) on top of TCP, because one TCP connection multiplexes many streams and you must prevent one stream from starving others / overwhelming the receiver.

### Q5. A producer outpaces a consumer at a service boundary. What are your options?
**Model answer:** Three: **block** (apply backpressure upstream — only if the source can pause), **reject** (429/503 fail-fast — for external callers; bounds accepted latency), **shed/drop** (discard the least valuable — for lossy-tolerant work). At a *service* boundary you usually can't block external callers, so reject or shed, paired with retry budgets and rate limits.
- *Follow-up: Why not just buffer in a durable queue?* That fixes bursts, not sustained deficit; lag grows forever and eventually drops oldest or fills disk. The real fix for sustained `λ>μ` is scaling consumers or shedding at the producer.
- *Follow-up: How do you decide what to shed?* By value/priority and by deadline (drop work already past its client timeout — CoDel-style).

### Q6. (Senior signal) When would you choose blocking backpressure over reactive, given Project Loom exists?
**Model answer:** Blocking is simpler, easier to reason about, and with virtual threads the per-blocked-thread cost is tiny — so for thread-per-request services with blocking I/O, blocking backpressure (bounded queues, `CallerRunsPolicy`, semaphores) is now often the *right* default; reactive's complexity isn't justified. Reactive still wins for true streaming, fan-out/fan-in composition, and when you need fine-grained demand control or integrate with reactive drivers. The tradeoff is complexity/observability (reactive stack traces and debugging are hard) vs the dwindling thread-cost argument. Note Loom changes the *cost* of blocking, not the *need* to bound concurrency — you still need a semaphore/bounded queue or millions of cheap tasks OOM you.
- *Follow-up: What does Loom NOT solve?* It doesn't bound in-flight work; doesn't make `synchronized` pinning-free in all cases (improved in later JDKs); doesn't replace explicit admission control.

### Q7. (Senior signal) Justify static vs adaptive concurrency limits for a service calling a variable-capacity dependency.
**Model answer:** A static limit (semaphore/bulkhead) is predictable and trivial but goes stale when downstream capacity changes (deploys, autoscaling, noisy neighbors), causing either under-utilization or overload. An adaptive limiter (Netflix concurrency-limits, AIMD/Gradient using latency as the congestion signal) tracks capacity changes automatically — the right choice for variable or multi-tenant downstreams — at the cost of control-loop complexity and possible oscillation if poorly damped. I'd start static with good observability, move to adaptive when capacity proves volatile, and always bound the adaptive limit's range to prevent runaway.
- *Follow-up: Why is latency a better congestion signal than rejection?* Latency rises *before* the buffer overflows (proactive); rejection is reactive (already overloaded). It mirrors TCP Vegas/BBR vs loss-based control.
- *Follow-up: How do you prevent oscillation?* Damping (gentle multiplicative decrease), hysteresis, smoothing RTT measurements, bounding the limit range.

### Q8. (Senior signal) Walk me through preventing a metastable collapse in a microservice mesh.
**Model answer:** Identify the sustaining feedback loop — usually retries + unbounded queues. Defenses, layered: (1) bound every queue, size by `μ×T`; (2) admission control / load shedding at the edge (reject early, before committing resources), preferably deadline-aware (drop work past its timeout); (3) retry budgets + exponential backoff + jitter so retries can't amplify; (4) circuit breakers so we stop feeding unhealthy dependencies; (5) timeouts at every hop, decreasing with depth; (6) adaptive concurrency limits. The key property: under overload the system *sheds* (bounded latency, reduced goodput-positive throughput) rather than *buffers* (unbounded latency → collapse). And recognize collapse won't self-heal — runbooks must include manual load relief.
- *Follow-up: How do you test for this before prod?* Open-model load tests (constant arrival rate) to expose collapse that closed-loop tests hide; chaos injection of downstream latency.
- *Follow-up: What's "goodput" and why track it?* Useful throughput (work that benefits a still-waiting client). Under collapse, throughput stays high but goodput crashes — so monitor success-within-client-timeout, not raw throughput.

### Q9. How does Reactive Streams guarantee backpressure, and what's the role of `request(n)`?
**Model answer:** The spec mandates a publisher must never emit more `onNext` than the cumulative demand the subscriber requested via `Subscription.request(n)` (Rule 1.1). `request(n)` is credit-based flow control as an API: `n` credits, each `onNext` consumes one; `request(Long.MAX_VALUE)` means unbounded (no backpressure). Signals to a subscriber must be serialized; exactly one terminal (`onComplete`/`onError`).
- *Follow-up: What breaks the guarantee in practice?* A non-backpressure source (hot/`Flux.create` with BUFFER), or an operator requesting `Long.MAX_VALUE` (`collectList`, default `subscribe`), reintroducing unbounded growth.
- *Follow-up: How do you bridge a push source safely?* `onBackpressureBuffer(max, strategy)` / `Drop` / `Latest` / `Error` per loss tolerance.

### Q10. Why is `Executors.newFixedThreadPool` dangerous, and what do you use instead?
**Model answer:** It uses an *unbounded* `LinkedBlockingQueue`, so `maximumPoolSize` and the rejection handler are never reached; under sustained overload the queue grows until OOM. Instead, construct a `ThreadPoolExecutor` with a *bounded* queue and a deliberate `RejectedExecutionHandler` — often `CallerRunsPolicy` (self-throttling, no loss) or a custom handler returning 503/metering drops.
- *Follow-up: What does `CallerRunsPolicy` do and why is it nice for pipelines?* The submitting thread runs the task itself, stalling the producer = automatic backpressure with no data loss.
- *Follow-up: How do you size the bounded queue?* `μ × acceptableLatency` plus burst headroom, balanced against latency SLO.

### Q11. Explain bufferbloat and how to combat it in an application queue.
**Model answer:** Bufferbloat is high latency from oversized buffers absorbing load instead of signaling it — throughput looks fine but items wait a long time. Networking fixes it with AQM (CoDel: drop when queue sojourn time exceeds ~5 ms target). In apps: size queues by `μ×T`, and shed by *time-in-queue* (drop items older than a deadline) rather than only by length — directly targeting latency.
- *Follow-up: Why is time-based shedding better than length-based?* Length-based bounds count but not necessarily latency as `μ` varies; time-based directly bounds wait and naturally drops work already past client timeouts (free goodput).

### Q12. What's the difference between backpressure, rate limiting, load shedding, and circuit breaking?
**Model answer:** Backpressure = dynamic feedback to slow the producer to consumer rate. Rate limiting = unilateral policy cap on producer rate, independent of consumer state. Load shedding = dropping work under overload, choosing what to discard. Circuit breaking = stopping calls to an unhealthy dependency for a cooldown. A robust system uses all four at different layers.
- *Follow-up: Give one place each lives.* Backpressure inside a Reactor pipeline / bounded queue; rate limiting at the API gateway per key; load shedding in admission control; circuit breaker (Resilience4j) around a dependency call.

---

## 11. Glossary

- **ACK (acknowledgement):** A TCP packet by which the receiver confirms received bytes; carries the receive window.
- **Admission control:** Deciding at the edge whether to accept or reject a request, to protect downstream resources.
- **AIMD (Additive Increase, Multiplicative Decrease):** Control law (used by TCP) that grows a limit slowly and cuts it sharply on congestion; converges to fair, stable sharing.
- **AQM (Active Queue Management):** Router/queue algorithms (CoDel, RED, FQ-CoDel) that proactively drop/mark to signal congestion and prevent bufferbloat.
- **Backpressure:** Feedback from a consumer to a producer to slow production to the consumer's sustainable rate.
- **BBR:** Google's model-based TCP congestion control estimating bottleneck bandwidth and RTT (vs loss-based).
- **BlockingQueue:** JDK interface for thread-safe queues whose `put`/`take` block when full/empty.
- **Bucket (token/leaky):** Rate-limiting algorithms; token allows bursts up to capacity, leaky enforces a steady output rate.
- **Bufferbloat:** Excessive latency caused by oversized buffers absorbing congestion instead of signaling it.
- **Bulkhead:** Isolation pattern giving each dependency/tenant a separate bounded resource pool so one can't sink the others.
- **`CallerRunsPolicy`:** Rejection handler that runs the task on the submitting thread, self-throttling the producer.
- **Circuit breaker:** State machine (closed/open/half-open) that stops calling an unhealthy dependency to fail fast and let it recover.
- **Congestion control:** Mechanism protecting the *network* (TCP `cwnd`, slow-start, CUBIC, BBR).
- **CoDel (Controlled Delay):** AQM algorithm that drops packets when queue sojourn time exceeds a target (~5 ms).
- **Credit-based flow control:** Consumer grants the producer credits (units of permission to send); producer sends only up to its credits; consumer replenishes as it processes.
- **`cwnd` (congestion window):** Sender's estimate of how much the network can carry.
- **Demand:** In Reactive Streams, the number of items a subscriber has requested (`request(n)`); a credit.
- **Flow control:** Any technique regulating the rate of data/requests through a pipeline; protects the receiver.
- **Goodput:** Useful throughput — work that actually benefits a still-waiting client (vs raw throughput).
- **Head-of-line (HOL) blocking:** One stalled item at the front of a shared queue/connection blocking everything behind it.
- **Hot vs cold publisher:** Hot emits regardless of subscribers (live feed); cold generates data per-subscriber on subscription.
- **Little's Law:** `L = λ × W`; items in system = arrival rate × time in system, for any stable system.
- **Load leveling (queue-based):** Placing a durable queue between a bursty producer and rate-limited consumer to absorb spikes.
- **Load shedding:** Dropping work under overload to protect the system, choosing what to discard.
- **Loom (Project):** JDK feature (Java 21 GA) providing virtual threads — cheap, blocking-friendly threads.
- **`λ` (lambda):** Arrival rate (items/second).
- **`μ` (mu):** Service rate (items/second the consumer sustains).
- **Metastable failure:** A self-sustaining degraded state entered via a trigger that persists even after the trigger is removed, fed by a feedback loop (e.g., retries).
- **MVCC:** (Adjacent term, multiversion concurrency control) — a DB technique keeping multiple row versions; mentioned only to note it's unrelated to flow control here.
- **`onBackpressure*` operators:** Reactor operators (`Buffer`/`Drop`/`Latest`/`Error`) choosing overflow behavior when downstream can't keep up.
- **Open-model vs closed-loop load testing:** Open model sends at a fixed arrival rate regardless of responses (exposes collapse); closed-loop waits for a response before the next request (accidentally applies backpressure, hiding bugs).
- **Poison pill:** A sentinel item placed in a queue to signal consumers to shut down cleanly.
- **Prefetch:** In Reactor, the batch size an operator requests across an async boundary (default 256), replenished at a low watermark (75%).
- **Processor:** A Reactive Streams stage that is both Subscriber and Publisher.
- **Pull model:** Consumer requests items when ready; backpressure is implicit.
- **Push model:** Producer sends items as soon as ready; no built-in backpressure.
- **QUIC:** UDP-based transport (basis of HTTP/3) with independent per-stream delivery, avoiding TCP's HOL blocking.
- **Rate limiting:** Unilateral cap on producer rate by policy, independent of consumer capacity.
- **Reactive Streams:** Specification (JDK `Flow`) for async streams with non-blocking backpressure via demand.
- **Rebalance (Kafka):** Reassignment of partitions among consumers, triggered when a consumer is deemed dead (missed `max.poll.interval.ms`).
- **`request(n)`:** Reactive Streams call granting `n` items of demand (credit).
- **Retry budget:** A cap on the fraction of requests that may be retries, preventing retry storms.
- **`rwnd` (receive window):** TCP receiver's advertised free buffer space in bytes; the flow-control credit.
- **Schedulers (Reactor):** Thread-pool abstractions (`parallel`, `boundedElastic`, `single`, `immediate`) controlling where work runs.
- **Semaphore:** Concurrency limiter with a fixed number of permits; `acquire`/`release` bound in-flight work.
- **Slow start:** TCP phase that exponentially grows `cwnd` from a small initial value.
- **Sojourn time:** How long an item waits in a queue (CoDel's shedding signal).
- **`SynchronousQueue`:** Zero-capacity queue requiring direct producer→consumer handoff (strongest backpressure).
- **TCP:** Reliable, ordered byte-stream transport implementing flow and congestion control.
- **Throughput:** Items processed per unit time.
- **Token bucket / leaky bucket:** See *Bucket*.
- **`WINDOW_UPDATE`:** HTTP/2 frame granting flow-control credit (bytes) per-stream or per-connection.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Core law:** A pipeline sustains only its slowest stage's rate (`μ`). If `λ > μ` is sustained, queues grow without bound → OOM / bufferbloat / metastable collapse.

**Little's Law:** `L = λ × W`. Bound `L` (concurrency/queue) → bound `W` (latency). Queue size ≈ `μ × acceptableLatency`.

**Three responses to a full buffer:** **Block** (source can pause) · **Reject** (429/503, external callers) · **Shed** (drop oldest/latest/low-priority, lossy-tolerant).

**Models:** Push (no backpressure) · Pull (implicit, blocks threads) · Reactive/credit (`request(n)`, non-blocking, the modern default).

**TCP analogy:** `rwnd` = free buffer credit; window-zero = full queue / zero demand. Flow control protects receiver; congestion control protects network. Sender limited by `min(rwnd, cwnd)`.

**Credit-based:** TCP window · HTTP/2 `WINDOW_UPDATE` (default 64 KiB-1 init) · Reactive `request(n)` · Flink buffer credits.

**JDK toolkit:** `ArrayBlockingQueue(N)` (bounded, 1 lock) · capacity-bounded `LinkedBlockingQueue` (2 locks) · `SynchronousQueue` (handoff) · `Semaphore(permits)` · `ThreadPoolExecutor(bounded queue + RejectedExecutionHandler)`.

**Foot-guns:** `Executors.newFixedThreadPool` (unbounded queue) · `new LinkedBlockingQueue<>()` (no cap) · `onBackpressureBuffer()` (no max) · gRPC `onNext` loop ignoring `isReady()` · `collectList` mid-stream · retries without budget/backoff.

**Reactor defaults:** prefetch 256, replenish at 75% · `flatMap` concurrency 256 · `Flux.create` default BUFFER (unbounded) · `boundedElastic` cap = 10×cores.

**Kafka:** pull = backpressure · `max.poll.records` 500 · `max.poll.interval.ms` 300000 · monitor consumer lag · `pause()/resume()`.

**Anti-collapse kit:** bound queues · admission control / deadline-aware shedding · retry budgets + backoff + jitter · circuit breakers · adaptive concurrency limits (AIMD, latency as signal) · open-model load tests.

**Debug tools:** `jmap -histo` / heap dump + Eclipse MAT (OOM) · `jstack`/`jcmd Thread.print` (pool exhaustion) · `ss -ti`/Wireshark (TCP windows) · queue-depth/in-flight/drop/lag/latency-p99 metrics · Reactor `checkpoint()`/`onOperatorDebug()`.

### 12.2 Self-test (no answers — active recall)

1. Derive the maximum bounded-queue depth for a consumer with service rate 800 items/s and a latency SLO of 250 ms, and explain which law you used and why bigger isn't better.
2. A teammate "fixes" intermittent `RejectedExecutionException`s by switching to `Executors.newFixedThreadPool(64)`. Explain precisely what new failure they've introduced and how you'd reproduce it in a test.
3. You have a hot, push-only sensor feed at ~5000 events/s into a consumer that sustains ~300/s, and only the *latest reading matters*. Specify the exact Reactor operators and overflow strategy you'd use, where they go in the chain, and the resulting memory bound.
4. Walk through, step by step, how a 2-second downstream blip plus default client retries can drive a healthy service into a metastable collapse that doesn't self-heal — and name three defenses that break the loop and *why* each one breaks it.
5. Explain how TCP flow control and HTTP/2 stream flow control differ, why HTTP/2 needs its own layer on top of TCP, and what the analogous concepts are inside a Project Reactor pipeline.
6. Given a gRPC server-streaming method that occasionally OOMs only when a particular client is on a slow link, state the most likely bug and the exact API mechanism that fixes it.
7. Argue both sides of "with virtual threads, we no longer need reactive programming for backpressure," then state where you land and what virtual threads still do *not* solve.
8. Design the observability for a bounded-queue worker pool such that you could detect, distinguish, and alert on (a) bufferbloat, (b) sustained rate deficit, and (c) a downstream stall — naming the specific metrics and thresholds.

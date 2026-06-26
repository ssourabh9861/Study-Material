# Streams & Functional Java

> An exhaustive engineering-handbook chapter for senior JVM developers. Covers lambdas and functional interfaces, the Stream pipeline, map/filter/reduce/collect, the full Collectors toolkit, ordering and statefulness, parallel streams and the Fork/Join machinery, `Optional` done right, method references, performance versus loops, when *not* to reach for streams, and a battery of real-world worked examples.

---

## 1. Overview & where it fits

### 1.1 What it is

"Streams & Functional Java" is the umbrella for two tightly-coupled features introduced in **Java 8 (March 2014)**:

1. **Lambda expressions and functional interfaces** — a syntax and type-system mechanism that lets you treat *behavior* (a chunk of code) as a value you can pass around, store, and compose. Before Java 8 you simulated this with anonymous inner classes; lambdas made it terse and gave the compiler/JIT room to optimize.
2. **The Stream API** (`java.util.stream`) — a declarative pipeline abstraction for processing *sequences of elements*. You describe **what** transformation you want (filter these, map those, group the rest) rather than **how** to iterate. The library owns the iteration ("internal iteration"), which unlocks laziness, fusion, and opt-in parallelism.

A **stream** here is *not* an I/O stream (`InputStream`/`OutputStream` are unrelated despite the shared word) and *not* a reactive stream (RxJava / `java.util.concurrent.Flow` / Project Reactor). It is a one-shot, pull-based, in-memory (or generator-backed) pipeline over a finite or infinite sequence.

### 1.2 The problem it solves

Classic Java data processing was **imperative and externally iterated**:

```java
// Pre-Java-8: imperative, externally iterated, mutable accumulator
List<String> result = new ArrayList<>();
for (Transaction t : transactions) {
    if (t.getAmount() > 1000) {           // filter
        result.add(t.getCurrency());      // map
    }
}
Collections.sort(result);                  // sort
```

Problems with this style:
- **Boilerplate noise** drowns the intent. You read four mechanics (loop, branch, add, sort) to recover one idea ("currencies of large transactions, sorted").
- **No fusion or laziness** — every step is eager and materializes intermediate state if you split it across helper methods.
- **Parallelism is your problem** — to use multiple cores you hand-write thread pools, partitioning, and merging, which is error-prone.
- **Composition is awkward** — combining reusable predicates/mappers means juggling `Comparator`s and helper classes.

The functional style rewrites that as:

```java
List<String> result = transactions.stream()
    .filter(t -> t.getAmount() > 1000)     // intermediate, lazy
    .map(Transaction::getCurrency)         // intermediate, lazy
    .sorted()                              // intermediate, stateful
    .collect(Collectors.toList());         // terminal, eager
```

The intent is now on the surface, the library controls iteration, and switching to `.parallelStream()` is a one-word change.

### 1.3 When you reach for it

- **Bulk data transformation**: filter → map → group → aggregate over collections, arrays, files, or generated sequences.
- **Aggregation and reporting**: sums, averages, histograms, grouping into maps, multi-level grouping.
- **Pipelines you want to read top-to-bottom** as a sequence of transformations.
- **Embarrassingly parallel CPU-bound aggregation** over large in-memory datasets (with care — see §1.6 and §7).

### 1.4 When you do *not* reach for it (preview — full treatment in §8)

- Tight, hot inner loops where you need the absolute last drop of performance and the loop is trivial.
- Logic with side effects, early exit on multiple conditions, mutation of multiple external variables, or checked exceptions.
- Small collections in hot paths (stream setup has fixed overhead).
- Anything that needs index access, multiple simultaneous cursors, or `i`/`j` two-pointer algorithms.

### 1.5 The one-paragraph mental model

A stream is a **conveyor belt with stations**. The **source** (a collection, array, generator, or I/O line reader) feeds elements onto the belt one at a time. Each **intermediate operation** (`filter`, `map`, `sorted`, …) is a station that may transform, drop, or reorder elements; crucially, intermediate stations do *nothing* until a **terminal operation** (`collect`, `forEach`, `reduce`, `count`, …) is attached at the end and pulls the belt into motion. When the belt runs, elements are (mostly) pushed *one at a time all the way through the pipeline* ("fusion" / loop fusion), so the library can short-circuit (`findFirst`, `limit`, `anyMatch`) and avoid materializing intermediate collections. A stream is **single-use** (consume it once, then it's spent) and **lazy** (no work without a terminal op).

### 1.6 Where it fits relative to alternatives

| Approach | Iteration | Laziness | Parallel | Best for |
|---|---|---|---|---|
| `for`/`for-each` loop | external | n/a | manual | hot loops, complex control flow, side effects |
| Stream API (sequential) | internal | yes | opt-in | declarative transforms, readability |
| Parallel stream | internal | yes | automatic (Fork/Join) | large CPU-bound aggregations |
| Reactive (Reactor/RxJava) | internal | yes (push) | scheduler-based | async I/O, backpressure, event streams |
| Loop + manual threads | external | no | manual | bespoke parallel algorithms |

Streams sit in the sweet spot of **declarative + opt-in parallel + synchronous**. They are *not* asynchronous and have *no backpressure* — for those you want reactive streams (a different chapter).

---

## 2. Foundations from first principles

We build the vocabulary from zero. Define each term as it appears.

### 2.1 First-class functions, simulated

Java is not a functional language and functions are **not** first-class objects the way they are in JavaScript or Python. You cannot have a bare `function` value. Instead Java fakes first-class behavior using **single-method interfaces**. A lambda is *syntactic sugar for an instance of such an interface*.

**Functional interface**: an interface with exactly **one abstract method** (the "SAM" — Single Abstract Method). It may have any number of `default` and `static` methods (those have bodies, so they don't count toward the abstract count). The `@FunctionalInterface` annotation is optional but recommended: it makes the compiler *enforce* the single-abstract-method rule and fail the build if someone adds a second abstract method.

```java
@FunctionalInterface
interface Validator<T> {
    boolean isValid(T input);            // the single abstract method (SAM)
    default Validator<T> and(Validator<T> other) {   // default method, allowed
        return x -> this.isValid(x) && other.isValid(x);
    }
}
```

### 2.2 Lambda expressions

A **lambda expression** is an anonymous function literal that the compiler converts into an instance of a target functional interface. Syntax:

```java
(parameters) -> expression
(parameters) -> { statements; }
```

Examples and what they desugar to:

```java
Runnable r = () -> System.out.println("hi");          // no args
Comparator<String> c = (a, b) -> a.length() - b.length(); // two args, expr body
Function<Integer,Integer> sq = x -> x * x;            // one arg, parens optional
Predicate<String> empty = s -> {                       // block body
    return s == null || s.isEmpty();
};
```

Key rules:
- **Parameter types are inferred** from the target type (the functional interface). You may write them explicitly: `(String a, String b) -> ...`. From Java 11 you may use `var`: `(var a, var b) -> ...` (useful only to attach annotations).
- A **single-expression** lambda implicitly returns the expression's value. A **block** lambda needs an explicit `return` (unless the SAM returns `void`).
- Lambdas **capture** variables from the enclosing scope but only **effectively final** ones (variables never reassigned after initialization). This is because the lambda may outlive the stack frame; capturing a mutable local would be a data race. You can capture `this`, fields, and effectively-final locals.

```java
int threshold = 1000;                 // effectively final — never reassigned
Predicate<Integer> big = x -> x > threshold;   // OK to capture
// threshold = 2000;                  // would break the above (no longer eff. final)
```

> **Adjacent term — "effectively final":** a local variable that is not declared `final` but is in fact never reassigned after its initial assignment. The compiler treats it *as if* it were `final` for capture purposes. Introduced precisely to make lambda capture ergonomic.

**Lambda vs anonymous class — important distinctions:**
- In a lambda, `this` refers to the **enclosing instance**, not the lambda object. In an anonymous class, `this` refers to the anonymous instance.
- Lambdas do **not** create a new scope for names — you cannot shadow an enclosing variable in a lambda's parameter list. Anonymous classes can shadow.
- Lambdas are compiled with **`invokedynamic` + `LambdaMetafactory`** (see §3.7), not as a separate `.class` file per lambda. Anonymous classes each generate a `$1.class`. This matters for class-loading footprint and startup.

### 2.3 The built-in functional interfaces (`java.util.function`)

The JDK ships ~43 functional interfaces so you rarely declare your own. The core families:

| Interface | SAM signature | Meaning | Typical use |
|---|---|---|---|
| `Supplier<T>` | `T get()` | produces a value, takes none | lazy init, factories, `orElseGet` |
| `Consumer<T>` | `void accept(T)` | consumes a value, returns none | `forEach`, side effects |
| `Function<T,R>` | `R apply(T)` | maps T to R | `map` |
| `Predicate<T>` | `boolean test(T)` | a boolean condition | `filter`, `anyMatch` |
| `UnaryOperator<T>` | `T apply(T)` | `Function<T,T>` | in-place transforms |
| `BinaryOperator<T>` | `T apply(T,T)` | `BiFunction<T,T,T>` | `reduce` combiners |
| `BiFunction<T,U,R>` | `R apply(T,U)` | two-arg function | `Map.merge`, accumulators |
| `BiConsumer<T,U>` | `void accept(T,U)` | two-arg consumer | `Map.forEach` |
| `BiPredicate<T,U>` | `boolean test(T,U)` | two-arg predicate | matching pairs |

Plus **primitive specializations** to avoid boxing: `IntFunction`, `ToIntFunction`, `IntUnaryOperator`, `IntBinaryOperator`, `IntPredicate`, `IntSupplier`, `IntConsumer`, and the `Long`/`Double` equivalents, and cross-type ones like `ToDoubleFunction`, `IntToLongFunction`, etc.

> **Adjacent term — "boxing/autoboxing":** the JVM wraps a primitive (`int`) into its object form (`Integer`) when an object is required, and unboxes back. Each boxing allocates a heap object (except small cached `Integer`s in -128..127). In tight numeric streams this allocation churn is a real cost, which is why primitive specializations and primitive streams (`IntStream`, `LongStream`, `DoubleStream`) exist.

### 2.4 Method references

A **method reference** is shorthand for a lambda that does nothing but call an existing method. Four kinds:

| Kind | Syntax | Equivalent lambda |
|---|---|---|
| Static | `Integer::parseInt` | `s -> Integer.parseInt(s)` |
| Bound instance (specific object) | `out::println` (where `out` is a `PrintStream`) | `s -> out.println(s)` |
| Unbound instance (arbitrary object of a type) | `String::toLowerCase` | `s -> s.toLowerCase()` |
| Constructor | `ArrayList::new` | `() -> new ArrayList<>()` |

The "unbound" form is the one that trips people up: `String::length` as a `Function<String,Integer>` means "take a String argument and call `.length()` on it" — the first parameter becomes the receiver. Use method references when they read clearly; do *not* contort code to avoid a perfectly clear lambda.

### 2.5 What a Stream is, precisely

A `java.util.stream.Stream<T>` is:
- **A sequence of elements** — not a data structure; it does not store elements (except transiently inside stateful ops). It carries elements *from* a source.
- **Backed by a source** — a `Collection`, array, generator function, I/O channel, or another stream.
- **Built for pipelined ops** — filter/map/reduce-style operations that can be chained.
- **Lazy** — intermediate ops build a plan; nothing runs until a terminal op.
- **Possibly unbounded** — `Stream.iterate`/`generate` produce infinite streams; you must bound them (`limit`, `takeWhile`, short-circuiting terminals).
- **Single-use** — once a terminal op runs (or the stream is otherwise consumed), reusing it throws `IllegalStateException: stream has already been operated upon or closed`.

There are four stream types: `Stream<T>` (objects) and primitive `IntStream`, `LongStream`, `DoubleStream`.

### 2.6 The three-part anatomy of a pipeline

Every stream pipeline has exactly three kinds of stages:

1. **Source** — creates the stream. `collection.stream()`, `Arrays.stream(arr)`, `Stream.of(...)`, `IntStream.range(...)`, `Files.lines(path)`, `Stream.generate(...)`, `Stream.iterate(...)`.
2. **Zero or more intermediate operations** — return a *new stream* and are **lazy**: `filter`, `map`, `flatMap`, `mapMulti`, `distinct`, `sorted`, `peek`, `limit`, `skip`, `takeWhile`, `dropWhile`, `mapToInt`, `boxed`, `parallel`, `sequential`, `unordered`.
3. **Exactly one terminal operation** — produces a result or side effect and triggers execution; it is **eager** (with short-circuit exceptions): `collect`, `reduce`, `forEach`, `forEachOrdered`, `count`, `min`, `max`, `findFirst`, `findAny`, `anyMatch`, `allMatch`, `noneMatch`, `toArray`, `toList`, `sum`/`average`/`summaryStatistics` (primitive streams), `iterator`, `spliterator`.

### 2.7 Stateless vs stateful intermediate operations

> **Adjacent term — "stateless operation":** processes each element independently of other elements (`filter`, `map`, `flatMap`, `peek`). It needs no memory of what came before.

> **Adjacent term — "stateful operation":** must observe or retain other elements to produce its output (`distinct`, `sorted`, `limit`, `skip`, sometimes `dropWhile`/`takeWhile`). `sorted` and `distinct` may need to **buffer the entire stream**. Stateful ops impose a **barrier** that complicates and constrains parallelism and pipelining.

### 2.8 Short-circuiting operations

> **Adjacent term — "short-circuiting":** an operation that may produce a result without consuming the whole stream. Intermediate: `limit`, `takeWhile`. Terminal: `findFirst`, `findAny`, `anyMatch`, `allMatch`, `noneMatch`. This is what makes processing an *infinite* stream (`Stream.iterate(1, n -> n+1).filter(...).findFirst()`) terminate.

### 2.9 Encounter order

> **Adjacent term — "encounter order":** the order in which a stream's source presents its elements. A `List` or array has a defined encounter order; a `HashSet` does not. Some operations (`sorted`) impose order; `unordered()` *relaxes* the ordering constraint, which can speed up `distinct`, `limit`, and parallel collection. Ordering is the single most subtle correctness topic in streams (see §7.3).

---

## 3. How it works internally

This is the heart of the chapter. We trace, step by step, what actually happens from `list.stream()` to the final result.

### 3.1 The big picture: a deferred plan, then a single traversal

A sequential stream pipeline is **not** a series of intermediate collections. It is:

1. A **lazily-built linked plan** of pipeline stages, each holding a reference to the previous stage and the operation it performs.
2. Executed, when the terminal op fires, by **wrapping the operations into a single `Sink` chain** and pushing each source element through the whole chain — "operation fusion" / "loop fusion."

So `stream().filter(p).map(f).collect(...)` over N elements does roughly **one pass**, applying `p` then (if it passes) `f` to each element, accumulating into the collector — *not* a filtered list, then a mapped list, then a collected list.

### 3.2 Key internal classes (package-private, but worth knowing)

| Class | Role |
|---|---|
| `AbstractPipeline` | base for all stream stages; holds the linked list of stages, flags, depth |
| `ReferencePipeline` | object-stream stages (`Stream<T>`); subclasses `Head`, `StatelessOp`, `StatefulOp` |
| `IntPipeline`/`LongPipeline`/`DoublePipeline` | primitive stream stages |
| `Sink` | a `Consumer`-like callback chain: `begin(size)`, `accept(element)`, `end()`, `cancellationRequested()` |
| `Spliterator` | the source iterator that supports splitting for parallelism (see §3.5) |
| `Node` / `Node.Builder` | tree-shaped buffers used by stateful ops and parallel collection |
| `TerminalOp` / `TerminalSink` | encapsulates the terminal operation's evaluation |
| `ForEachOps`, `ReduceOps`, `MatchOps`, `FindOps`, `SortedOps`, `SliceOps`, `DistinctOps`, `Collectors` | the implementations of each operation family |

### 3.3 Step-by-step: building the pipeline (lazy phase)

Consider:

```java
Optional<String> r = words.stream()        // (A) Head
    .filter(w -> w.length() > 3)            // (B) stateless op
    .map(String::toUpperCase)              // (C) stateless op
    .findFirst();                           // (D) terminal, short-circuit
```

1. **(A) `words.stream()`** calls `Collection.stream()`, which builds a `Spliterator` from the collection and wraps it in a `ReferencePipeline.Head`. This `Head` is the source stage. No elements are touched.
2. **(B) `.filter(...)`** creates a new `ReferencePipeline.StatelessOp` whose `opWrapSink(downstream)` returns a `Sink` that, in its `accept(element)`, evaluates the predicate and forwards to `downstream.accept` only if it passes. This new stage links back to `Head`. **Still no elements touched.**
3. **(C) `.map(...)`** creates another `StatelessOp` whose `Sink.accept(element)` calls `downstream.accept(mapper.apply(element))`. Links back to the filter stage.
4. **(D) `.findFirst()`** is a `TerminalOp`. Calling it ends the lazy phase and triggers evaluation.

During the lazy phase each stage also accumulates **stream flags** (a bitset): `SIZED`, `ORDERED`, `DISTINCT`, `SORTED`, `SHORT_CIRCUIT`. For example, `map` clears `SORTED` and `DISTINCT` (the mapper might break them) but keeps `SIZED` and `ORDERED`; `filter` clears `SIZED` (count may shrink). These flags drive optimizations (e.g., `count()` can skip traversal entirely if `SIZED` and no size-changing ops are present — see §7.6).

### 3.4 Step-by-step: execution (eager phase, sequential)

When `findFirst()` fires:

1. The terminal op builds its **terminal `Sink`** (here, a "find first" sink that records the first element and then requests cancellation).
2. The pipeline calls `wrapSink(terminalSink)` from the **last stage backward to the head**, producing the fused chain: `filterSink → mapSink → findFirstSink`. Wrapping backward is why the *source* sink is the *outermost*.
3. It obtains the source `Spliterator`.
4. It calls `copyIntoWithCancel(wrappedSink, spliterator)` (cancellable because the pipeline is short-circuiting). This:
   - calls `sink.begin(size)` to signal start,
   - repeatedly calls `spliterator.tryAdvance(sink)` (one element at a time), checking `sink.cancellationRequested()` after each,
   - stops early once `findFirst` has its answer (cancellation),
   - calls `sink.end()`.
5. Each element flows: source → `filterSink.accept` (test predicate) → if pass, `mapSink.accept` (uppercase) → `findFirstSink.accept` (record + request cancel).
6. The terminal op extracts the result (the captured element) and returns it as `Optional`.

For **non-short-circuiting** terminals (e.g., `forEach`, `collect`) the source uses `spliterator.forEachRemaining(sink)` instead of element-by-element `tryAdvance`, which is faster (less per-element overhead, better JIT inlining).

### 3.5 The Spliterator — the engine of traversal and splitting

> **Adjacent term — "Spliterator":** "splittable iterator." An interface (`java.util.Spliterator<T>`) that can (a) traverse elements one at a time (`tryAdvance`) or in bulk (`forEachRemaining`), and (b) **split itself** (`trySplit`) into two spliterators covering disjoint portions of the source — the basis for parallel work-splitting. It also reports an estimated size (`estimateSize`) and a set of **characteristics** flags (`ORDERED`, `SIZED`, `SUBSIZED`, `DISTINCT`, `SORTED`, `NONNULL`, `IMMUTABLE`, `CONCURRENT`).

Key `Spliterator` methods:

| Method | Purpose |
|---|---|
| `boolean tryAdvance(Consumer)` | process one element; return false if none left |
| `void forEachRemaining(Consumer)` | process all remaining (bulk, faster) |
| `Spliterator<T> trySplit()` | split off ~half; return null if not splittable |
| `long estimateSize()` | estimated remaining count (`Long.MAX_VALUE` if unknown/infinite) |
| `int characteristics()` | bitmask of properties |
| `Comparator getComparator()` | if `SORTED`, the comparator (or null for natural order) |

**Split quality matters enormously for parallelism.** An `ArrayList`/array spliterator splits cleanly in half (great). A `LinkedList`, `HashSet`, or `Stream.iterate` source splits poorly or not at all (terrible — parallelism degenerates to one thread doing all the work).

### 3.6 Parallel execution and the Fork/Join framework

> **Adjacent term — "Fork/Join framework":** a work-stealing thread pool (`java.util.concurrent.ForkJoinPool`) designed for **divide-and-conquer** tasks. A task can `fork()` subtasks and `join()` their results. Idle worker threads **steal** tasks from the tails of busy workers' deques, keeping cores busy. "Work-stealing" is the load-balancing strategy that makes this scale.

When you call `.parallel()` (or start from `parallelStream()`), the terminal op runs on the **common ForkJoinPool**:

> **Adjacent term — "common ForkJoinPool":** a single JVM-wide shared pool (`ForkJoinPool.commonPool()`). Its parallelism defaults to **`Runtime.getRuntime().availableProcessors() - 1`** worker threads (so on an 8-core box you get 7 workers + the calling thread can also help = effectively up to 8). You can override it with the system property `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N`. **All parallel streams in the JVM share this one pool by default** — a critical operational gotcha (see §9.4).

Parallel execution flow:

1. The pipeline obtains the source `Spliterator`.
2. It wraps the work in an `AbstractTask` (a `ForkJoinTask` subclass: `ReduceTask`, `ForEachTask`, `AbstractShortCircuitTask`, etc.).
3. The task computes a **target leaf size** ≈ `estimateSize / (parallelism * 4)` and recursively `trySplit`s the spliterator, `fork`ing one half and recursing on the other, until each leaf is at/below the target size.
4. Each leaf runs the **fused sink chain** sequentially over its portion (just like §3.4).
5. Results are **combined** on the way back up the tree (`join`): for `reduce`, via the combiner; for `collect`, via the collector's `combiner` and `Collector.Characteristics`.
6. If the result must preserve **encounter order** (`ORDERED`), the framework uses ordered combination (more bookkeeping, possible buffering); if `unordered()` was applied, it can combine results as they complete (faster).

The calling thread does **not** block idly — it participates in the computation (joins help execute pending tasks), so a parallel stream uses up to `parallelism + 1` threads of CPU.

### 3.7 How lambdas compile and link at runtime

> **Adjacent term — "invokedynamic":** a JVM bytecode instruction (Java 7) that defers the decision of *which method to call* to runtime, via a one-time "bootstrap" call that returns a `CallSite`. Lambdas use it so the JVM, not javac, decides how to materialize the lambda.

When you write a lambda:
1. javac compiles the lambda **body** into a **private static (or instance) synthetic method** of the enclosing class (e.g., `lambda$main$0`).
2. At the use site javac emits an **`invokedynamic`** instruction whose bootstrap method is `LambdaMetafactory.metafactory(...)`.
3. The **first time** that line executes, the bootstrap runs: `LambdaMetafactory` **spins a hidden class** (or, for non-capturing lambdas, reuses a cached singleton) implementing the target functional interface, whose single method calls the synthetic method. It returns a `CallSite` bound to a factory.
4. Subsequent executions just invoke the linked `CallSite` (essentially free).

Consequences:
- **No `$1.class` file per lambda** (unlike anonymous classes) → smaller jars, fewer classes to load.
- **Non-capturing lambdas are singletons** — created once and reused, so `x -> x * 2` allocates nothing on repeated calls. **Capturing lambdas** allocate a small object per call site invocation to hold the captured state (still cheap, often scalar-replaced by the JIT via escape analysis).
- First-use linkage has a tiny one-time cost; this is a (usually negligible) **startup** consideration. Projects obsessed with startup (CLIs, serverless) sometimes prefer fewer lambdas or AOT — see §7.10.

### 3.8 The lifecycle / state machine of a stream

```
   [UNCONSUMED]  --intermediate op-->  [UNCONSUMED]   (plan grows; lazy)
        |
        | terminal op invoked
        v
   [LINKED & EXECUTING]  --(spliterator traversal / fork-join)-->
        |
        v
   [CONSUMED]   (any further op  =>  IllegalStateException)
```

A `Stream` may also be **closed** (it implements `AutoCloseable`). Most streams have no resources and need no closing, but I/O-backed streams (`Files.lines`, `Files.list`, `Files.walk`, `BufferedReader.lines`) hold an open file handle and **must** be closed — use try-with-resources. `onClose(Runnable)` registers a close handler.

### 3.9 Operation fusion in detail (why intermediate ops are "free-ish")

Because every stateless intermediate op becomes a `Sink` that wraps the downstream `Sink`, the JIT sees a chain of small `accept` methods that it can **inline** into one tight loop body. After warmup, `filter(p).map(f)` over an `ArrayList` compiles to machine code very close to a hand-written `for` loop with an `if` and a transform. Stateful ops (`sorted`, `distinct`, `limit`) break this fusion: they insert a **barrier** that buffers/orders elements before the downstream can proceed, which is why ordering them early or late changes both correctness and performance.

---

## 4. The complete toolkit

### 4.1 Stream sources (factory methods)

| Source | Returns | Notes / defaults |
|---|---|---|
| `coll.stream()` / `coll.parallelStream()` | `Stream<T>` | sequential / parallel over a `Collection` |
| `Arrays.stream(T[])` | `Stream<T>` | also `(arr, from, to)` ranged overload |
| `Arrays.stream(int[]/long[]/double[])` | primitive stream | no boxing |
| `Stream.of(a, b, c)` | `Stream<T>` | varargs; `Stream.of(single)` is one element |
| `Stream.ofNullable(x)` | `Stream<T>` | 0 or 1 element (null-safe), Java 9+ |
| `Stream.empty()` | `Stream<T>` | zero elements |
| `IntStream.range(0,n)` / `rangeClosed(0,n)` | `IntStream` | half-open / closed; cheap, `SIZED` |
| `Stream.iterate(seed, next)` | infinite `Stream<T>` | unbounded — must `limit`/`takeWhile` |
| `Stream.iterate(seed, hasNext, next)` | finite `Stream<T>` | Java 9+, has a predicate to stop |
| `Stream.generate(supplier)` | infinite `Stream<T>` | unordered, unbounded |
| `Files.lines(path[, charset])` | `Stream<String>` | **must close** (try-with-resources) |
| `Files.list/walk/find(...)` | `Stream<Path>` | **must close** |
| `BufferedReader.lines()` | `Stream<String>` | reader must outlive stream |
| `Pattern.splitAsStream(seq)` | `Stream<String>` | lazy regex split |
| `String.chars()` / `codePoints()` | `IntStream` | char/codepoint streams |
| `Random.ints/longs/doubles(...)` | primitive stream | bounded/unbounded RNG sequences |
| `Stream.concat(a, b)` | `Stream<T>` | lazily concatenate two streams |
| `StreamSupport.stream(spliterator, parallel)` | `Stream<T>` | build from a custom `Spliterator` |

### 4.2 Intermediate operations

| Operation | Signature (abbrev.) | Stateless/Stateful | Short-circuit | Effect |
|---|---|---|---|---|
| `filter` | `Predicate<T>` | stateless | no | keep matching elements |
| `map` | `Function<T,R>` | stateless | no | 1:1 transform |
| `mapToInt/Long/Double` | `ToIntFunction` etc. | stateless | no | to primitive stream |
| `mapToObj` | `IntFunction<R>` (on primitive) | stateless | no | primitive → object stream |
| `boxed` | — | stateless | no | `IntStream` → `Stream<Integer>` |
| `asLongStream`/`asDoubleStream` | — | stateless | no | widen primitive stream |
| `flatMap` | `Function<T,Stream<R>>` | stateless | no | 1:many; flatten substreams |
| `flatMapToInt/Long/Double` | — | stateless | no | flatten to primitive |
| `mapMulti` (Java 16+) | `BiConsumer<T,Consumer<R>>` | stateless | no | 1:many without per-element Stream alloc |
| `distinct` | — | **stateful** (buffers seen) | no | remove duplicates (by `equals`) |
| `sorted` | natural / `Comparator<T>` | **stateful** (full buffer) | no | sort |
| `peek` | `Consumer<T>` | stateless | no | side-effect for debugging (not transformation) |
| `limit` | `long n` | **stateful** | **yes** | first n |
| `skip` | `long n` | **stateful** | no | drop first n |
| `takeWhile` (Java 9+) | `Predicate<T>` | **stateful** | **yes** | prefix while predicate true |
| `dropWhile` (Java 9+) | `Predicate<T>` | **stateful** | no | drop prefix while true, keep rest |
| `parallel` / `sequential` | — | n/a | no | switch execution mode |
| `unordered` | — | n/a | no | relax encounter-order constraint |
| `onClose` | `Runnable` | n/a | no | register close handler |

### 4.3 Terminal operations

| Operation | Returns | Short-circuit | Notes |
|---|---|---|---|
| `forEach` | `void` | no | **no order guarantee in parallel**; side effects only |
| `forEachOrdered` | `void` | no | preserves encounter order (slower in parallel) |
| `collect(Collector)` | `R` | no | mutable reduction (see §4.5) |
| `collect(supplier, accumulator, combiner)` | `R` | no | 3-arg manual mutable reduction |
| `toList()` (Java 16+) | `List<T>` | no | **unmodifiable** list; allows nulls |
| `toArray()` / `toArray(IntFunction)` | `Object[]` / `T[]` | no | `toArray(String[]::new)` for typed array |
| `reduce(identity, accumulator)` | `T` | no | fold to single value |
| `reduce(identity, accumulator, combiner)` | `U` | no | parallel-safe fold with separate combiner |
| `reduce(accumulator)` | `Optional<T>` | no | no identity → optional |
| `min(Comparator)` / `max(Comparator)` | `Optional<T>` | no | extreme element |
| `count()` | `long` | no | may skip traversal if `SIZED` (Java 9+) |
| `anyMatch/allMatch/noneMatch` | `boolean` | **yes** | predicate quantifiers |
| `findFirst()` | `Optional<T>` | **yes** | first in encounter order |
| `findAny()` | `Optional<T>` | **yes** | any element (better for parallel) |
| `iterator()` / `spliterator()` | iterator/spliterator | no | escape hatch to imperative |
| Primitive: `sum/average/min/max/summaryStatistics` | varies | no | on `IntStream`/`LongStream`/`DoubleStream` |

### 4.4 `reduce` — the three forms and the contract

```java
// Form 1: identity + associative accumulator -> T
int sum = nums.stream().reduce(0, Integer::sum);

// Form 2: no identity -> Optional<T>
Optional<Integer> max = nums.stream().reduce(Integer::max);

// Form 3: identity + accumulator + combiner -> U  (different result type)
int totalChars = words.stream()
    .reduce(0,                                  // identity (U=Integer)
            (acc, w) -> acc + w.length(),       // accumulator: U,T -> U
            Integer::sum);                       // combiner: U,U -> U (for parallel)
```

**Contract for correctness (especially parallel):**
- The **identity** must satisfy `combiner.apply(identity, x) == x` for all x.
- The **accumulator** and **combiner** must be **associative** and **stateless**, and must agree: `combiner.apply(u, accumulator.apply(identity, t)) == accumulator.apply(u, t)`.
- Violating associativity gives **correct results sequentially but wrong/non-deterministic results in parallel** — a classic subtle bug (e.g., reducing with subtraction or string-concatenation-as-accumulator).

### 4.5 The `Collector` toolkit (`java.util.stream.Collectors`)

A **`Collector<T, A, R>`** is a recipe for a **mutable reduction**: T = input element, A = mutable accumulator type, R = result. It has five parts: `supplier()` (new accumulator), `accumulator()` (fold element in), `combiner()` (merge two accumulators, for parallel), `finisher()` (A→R), and `characteristics()` (`CONCURRENT`, `UNORDERED`, `IDENTITY_FINISH`).

| Collector | Result | Purpose |
|---|---|---|
| `toList()` | `List<T>` | collect to a (modifiable, `ArrayList`) list |
| `toUnmodifiableList()` | `List<T>` | immutable list (Java 10+) |
| `toSet()` / `toUnmodifiableSet()` | `Set<T>` | dedup into a set |
| `toCollection(supplier)` | custom collection | e.g. `toCollection(TreeSet::new)` |
| `toMap(keyFn, valFn)` | `Map<K,V>` | **throws on duplicate keys** |
| `toMap(keyFn, valFn, mergeFn)` | `Map<K,V>` | resolve duplicate keys |
| `toMap(keyFn, valFn, mergeFn, mapSupplier)` | custom map | e.g. into a `TreeMap`/`LinkedHashMap` |
| `toConcurrentMap(...)` | `ConcurrentMap` | concurrent collection (parallel-friendly) |
| `groupingBy(classifier)` | `Map<K,List<T>>` | bucket by key |
| `groupingBy(classifier, downstream)` | `Map<K,D>` | bucket then reduce each bucket |
| `groupingBy(classifier, mapSupplier, downstream)` | custom map | choose map impl |
| `groupingByConcurrent(...)` | `ConcurrentMap` | parallel grouping |
| `partitioningBy(predicate)` | `Map<Boolean,List<T>>` | split into true/false buckets |
| `partitioningBy(predicate, downstream)` | `Map<Boolean,D>` | partition + reduce |
| `counting()` | `Long` | count (downstream) |
| `summingInt/Long/Double(fn)` | numeric | sum a projection |
| `averagingInt/Long/Double(fn)` | `Double` | average a projection |
| `summarizingInt/Long/Double(fn)` | `IntSummaryStatistics` etc. | count/sum/min/max/avg in one pass |
| `minBy/maxBy(comparator)` | `Optional<T>` | extreme (downstream) |
| `reducing(...)` | varies | general reduction (downstream) |
| `mapping(fn, downstream)` | varies | transform then collect (downstream) |
| `flatMapping(fn, downstream)` (Java 9+) | varies | flatMap then collect (downstream) |
| `filtering(pred, downstream)` (Java 9+) | varies | filter then collect (downstream) — keeps empty groups |
| `joining()` / `joining(sep)` / `joining(sep, pre, suf)` | `String` | concatenate strings |
| `collectingAndThen(downstream, finisher)` | varies | post-process a collector's result |
| `teeing(c1, c2, merger)` (Java 12+) | varies | feed elements to **two** collectors, merge results |

> **Adjacent term — "downstream collector":** many collectors take *another collector* as an argument to process each group/partition. E.g. `groupingBy(byDept, counting())` groups by department and counts each group. This composition is the source of streams' real expressive power.

### 4.6 `Optional<T>` toolkit

> **Adjacent term — "`Optional`":** a container that holds either one value or nothing, designed to make "no value" explicit in the type system instead of relying on `null`. It is primarily a **return type** for methods that may have no result — *not* a replacement for `null` everywhere, not a field type, not a parameter type (see §6.7).

| Method | Purpose |
|---|---|
| `Optional.of(v)` | wrap a non-null value (NPE if null) |
| `Optional.ofNullable(v)` | wrap value or empty if null |
| `Optional.empty()` | the empty optional |
| `isPresent()` / `isEmpty()` (Java 11+) | boolean checks |
| `get()` | value or `NoSuchElementException` — **avoid; prefer the alternatives** |
| `orElse(other)` | value or a default (**always evaluated**) |
| `orElseGet(supplier)` | value or lazily-computed default |
| `orElseThrow()` (Java 10+) / `orElseThrow(supplier)` | value or throw |
| `map(fn)` | transform if present |
| `flatMap(fn)` | transform to another Optional, flatten |
| `filter(pred)` | keep value only if predicate holds |
| `ifPresent(consumer)` | run if present |
| `ifPresentOrElse(consumer, runnable)` (Java 9+) | present/empty branches |
| `or(supplier)` (Java 9+) | fallback to another Optional |
| `stream()` (Java 9+) | 0/1-element stream (great with `flatMap`) |
| Primitive: `OptionalInt/Long/Double` | `getAsInt()` etc., avoid boxing |

---

## 5. Code examples by use case

Each example is self-contained and idiomatic. Non-obvious lines are commented.

### 5.1 Sales reporting: multi-level grouping and aggregation

```java
record Sale(String region, String product, String rep, long amountCents) {}

List<Sale> sales = ...;

// (1) Total revenue per region, sorted descending, as a LinkedHashMap (insertion order)
Map<String, Long> revenueByRegion = sales.stream()
    .collect(Collectors.groupingBy(
        Sale::region,
        Collectors.summingLong(Sale::amountCents)))   // downstream: sum each group
    .entrySet().stream()
    .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
    .collect(Collectors.toMap(
        Map.Entry::getKey, Map.Entry::getValue,
        (a, b) -> a,                                   // merge fn (no dups expected)
        LinkedHashMap::new));                          // preserve sorted order

// (2) Two-level grouping: region -> product -> count
Map<String, Map<String, Long>> countByRegionProduct = sales.stream()
    .collect(Collectors.groupingBy(
        Sale::region,
        Collectors.groupingBy(
            Sale::product,
            Collectors.counting())));

// (3) Per region: full stats in ONE pass (count, sum, min, max, average)
Map<String, LongSummaryStatistics> statsByRegion = sales.stream()
    .collect(Collectors.groupingBy(
        Sale::region,
        Collectors.summarizingLong(Sale::amountCents)));
statsByRegion.forEach((r, s) ->
    System.out.printf("%s: n=%d avg=%.2f max=%d%n",
        r, s.getCount(), s.getAverage(), s.getMax()));

// (4) Top rep per region (groupingBy + maxBy + unwrap Optional via collectingAndThen)
Map<String, Sale> topRepByRegion = sales.stream()
    .collect(Collectors.groupingBy(
        Sale::region,
        Collectors.collectingAndThen(
            Collectors.maxBy(Comparator.comparingLong(Sale::amountCents)),
            Optional::get)));                          // safe: groups are non-empty
```

### 5.2 Building a lookup index (`toMap`) with duplicate handling

```java
record User(long id, String email, Instant lastLogin) {}
List<User> users = ...;

// id is unique -> safe toMap
Map<Long, User> byId = users.stream()
    .collect(Collectors.toMap(User::id, Function.identity()));

// email might repeat (data quality!) -> supply a merge function to keep the most recent
Map<String, User> byEmail = users.stream()
    .collect(Collectors.toMap(
        User::email,
        Function.identity(),
        (u1, u2) -> u1.lastLogin().isAfter(u2.lastLogin()) ? u1 : u2)); // resolve clash

// Group emails per first-letter, collecting just the emails (mapping downstream)
Map<Character, List<String>> emailsByInitial = users.stream()
    .collect(Collectors.groupingBy(
        u -> Character.toLowerCase(u.email().charAt(0)),
        Collectors.mapping(User::email, Collectors.toList())));
```

> **Gotcha shown above:** the 2-arg `toMap` throws `IllegalStateException: Duplicate key` when two elements map to the same key. Always reach for the 3-arg form unless you can *prove* keys are unique.

### 5.3 Flattening nested structures with `flatMap`

```java
record Order(long id, List<LineItem> items) {}
record LineItem(String sku, int qty, long unitPriceCents) {}
List<Order> orders = ...;

// All line items across all orders, flattened into one stream
List<LineItem> allItems = orders.stream()
    .flatMap(o -> o.items().stream())     // 1 order -> many items
    .collect(Collectors.toList());

// Total units sold per SKU
Map<String, Integer> unitsBySku = orders.stream()
    .flatMap(o -> o.items().stream())
    .collect(Collectors.groupingBy(
        LineItem::sku,
        Collectors.summingInt(LineItem::qty)));

// Java 16+: mapMulti avoids creating a Stream per order (cheaper for hot paths)
List<LineItem> allItems2 = orders.stream()
    .<LineItem>mapMulti((o, downstream) ->        // push each item directly
        o.items().forEach(downstream))
    .collect(Collectors.toList());
```

### 5.4 Partitioning, joining, and report rendering

```java
record Tx(String id, long amountCents, boolean flagged) {}
List<Tx> txs = ...;

// Partition into flagged / not, counting each side
Map<Boolean, Long> flaggedCounts = txs.stream()
    .collect(Collectors.partitioningBy(Tx::flagged, Collectors.counting()));
long flagged = flaggedCounts.get(true);     // partitioningBy ALWAYS has both keys

// CSV line from selected fields
String csv = txs.stream()
    .filter(t -> t.amountCents() > 100_00)
    .map(Tx::id)
    .collect(Collectors.joining(",", "[", "]"));   // separator, prefix, suffix
```

### 5.5 Primitive streams for numeric work (no boxing)

```java
int[] data = ...;

IntSummaryStatistics stats = IntStream.of(data).summaryStatistics();
double mean = stats.getAverage();
int max = stats.getMax();

// Sum of squares of even numbers in [0, 1_000_000)
long sumSq = IntStream.range(0, 1_000_000)   // SIZED, splits perfectly
    .filter(n -> (n & 1) == 0)
    .asLongStream()                          // widen to avoid int overflow
    .map(n -> n * n)
    .sum();

// Histogram of word lengths
Map<Integer, Long> lengthHistogram = Stream.of("a","bb","cc","ddd")
    .collect(Collectors.groupingBy(String::length, Collectors.counting()));
```

### 5.6 Reading and processing a file safely (resource management)

```java
import java.nio.file.*;

// Count non-blank lines containing "ERROR", case-insensitive.
// try-with-resources is MANDATORY: Files.lines holds an open file handle.
Path log = Path.of("/var/log/app.log");
long errorLines;
try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
    errorLines = lines
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .filter(s -> s.toLowerCase().contains("error"))
        .count();
}   // file handle closed here, even on exception
```

### 5.7 Infinite streams, bounded with `limit`/`takeWhile`

```java
// First 10 Fibonacci numbers
Stream.iterate(new long[]{0, 1}, a -> new long[]{a[1], a[0] + a[1]})
      .limit(10)                            // bound the infinite stream
      .map(a -> a[0])
      .forEach(System.out::println);

// Read sensor values until the first out-of-range reading (takeWhile, Java 9+)
List<Double> stable = readings.stream()
      .takeWhile(v -> v >= 0.0 && v <= 100.0)   // stop at first bad value
      .collect(Collectors.toList());
```

### 5.8 `Optional` chaining done right

```java
record Address(String city) {}
record Company(Optional<Address> address) {}
record Person(Optional<Company> company) {}

// Get the city, or "Unknown", with NO nested null checks and NO Optional.get()
String city = person.company()                  // Optional<Company>
    .flatMap(Company::address)                  // Optional<Address>
    .map(Address::city)                         // Optional<String>
    .filter(s -> !s.isBlank())
    .orElse("Unknown");                         // default if any step empty

// Turn a stream of Optionals into a stream of present values (Java 9+)
List<Address> presentAddresses = companies.stream()
    .map(Company::address)            // Stream<Optional<Address>>
    .flatMap(Optional::stream)        // drops empties, unwraps present
    .collect(Collectors.toList());
```

### 5.9 Custom `Collector` (cap a running total, no boxing churn)

```java
// A collector that sums longs but caps the total at a max value.
Collector<Long, long[], Long> cappedSum(long cap) {
    return Collector.of(
        () -> new long[]{0},                                  // supplier: mutable box
        (acc, x) -> acc[0] = Math.min(cap, acc[0] + x),       // accumulator
        (a, b) -> { a[0] = Math.min(cap, a[0] + b[0]); return a; }, // combiner (parallel)
        acc -> acc[0]);                                       // finisher
}

long total = LongStream.rangeClosed(1, 1_000_000).boxed()
    .collect(cappedSum(500_000_000L));
```

### 5.10 Method references in practice

```java
people.stream()
    .sorted(Comparator.comparing(Person::lastName)          // unbound instance ref
                      .thenComparing(Person::firstName))
    .map(Person::toString)                                   // unbound instance ref
    .forEach(System.out::println);                          // bound instance ref

List<Integer> nums = Stream.of("1","2","3")
    .map(Integer::parseInt)                                  // static ref
    .collect(Collectors.toList());

Supplier<List<String>> factory = ArrayList::new;            // constructor ref
```

### 5.11 The `teeing` collector (Java 12+): two aggregations, one pass

```java
record Result(long count, double average) {}

Result r = sales.stream()
    .collect(Collectors.teeing(
        Collectors.counting(),                               // collector 1
        Collectors.averagingLong(Sale::amountCents),         // collector 2
        Result::new));                                       // merge the two results
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance: streams vs loops

Honest summary (benchmark on *your* hardware/JDK; numbers vary):
- For **`ArrayList`/array sources** and **stateless ops**, a warmed-up sequential stream is **competitive** with a `for` loop — often within a few percent — because fusion + JIT inlining produce similar machine code.
- **Primitive streams** (`IntStream`) on ranges are essentially as fast as `for (int i...)` and avoid boxing entirely.
- **Boxed object streams over primitives** (`Stream<Integer>`) can be **2–5× slower** than a primitive loop due to boxing allocation and pointer chasing. Always use `IntStream`/`LongStream`/`DoubleStream` for numeric work; use `mapToInt`/`mapToLong` early.
- **Stateful ops** (`sorted`, `distinct`) add buffering cost regardless of style.
- **Cold/short streams** carry fixed setup overhead (pipeline objects, spliterator, sink chain). In a hot path over tiny collections, a loop wins clearly.

**Rule of thumb:** choose streams for readability and parallelism; reach for loops when profiling shows a stream is the bottleneck or when the loop is in the hottest 1%.

### 6.2 Correctness: associativity, statelessness, no interference

- **Lambdas passed to ops must be non-interfering** (must not modify the stream's *source* during execution) and, for parallel, **stateless** (no shared mutable state). Violations cause `ConcurrentModificationException`, lost updates, or silent corruption.
- **Never accumulate into a shared mutable collection from `forEach`/`map`** — especially in parallel. This is the #1 stream bug:

```java
// WRONG: shared mutable state, data race in parallel, even risky sequentially
List<String> out = new ArrayList<>();
stream.parallel().forEach(out::add);   // ConcurrentModificationException / lost data

// RIGHT: use a collector (the library handles thread-safe accumulation)
List<String> out = stream.parallel().collect(Collectors.toList());
```

- `reduce` combiner must be **associative**; otherwise parallel results are wrong (see §4.4).

### 6.3 Concurrency & thread-safety

- Collectors are designed for safe parallel accumulation via the combiner (or `CONCURRENT` characteristic for `groupingByConcurrent`/`toConcurrentMap`).
- `forEach` in parallel runs the consumer on **multiple Fork/Join threads concurrently and in no particular order**. The consumer must be thread-safe. Use `forEachOrdered` only if you need order (it serializes the final consumption).

### 6.4 Memory

- Streams avoid intermediate collections (good), but **stateful ops materialize buffers**: `sorted` buffers all elements; `distinct` keeps a `HashSet` of seen elements; `collect(toList())` builds the result. For very large datasets these dominate memory.
- `limit(n)` after a stateful op (`sorted().limit(n)`) still sorts everything first (no top-N optimization in the JDK) — for top-N use a bounded `PriorityQueue` or a custom collector.

### 6.5 Security

- Streams have no special security surface, but **untrusted regex** in `Pattern.splitAsStream` can cause catastrophic backtracking (ReDoS). Validate/limit input.
- `Files.lines`/`walk` on attacker-controlled paths → path traversal; canonicalize and validate paths.

### 6.6 Observability & testing

- `peek` is for **debugging only**, not for side effects in production. Note the JIT may **elide `peek`** when its result is provably unneeded (e.g., a `count()` that skips traversal), so don't rely on `peek` running.
- Streams are easy to unit test: feed a known input collection and assert on the collected output. Pure (side-effect-free) pipelines are deterministic (sequential) and order-independent under `findAny`/`unordered`.
- For parallel pipelines, test both the sequential and parallel paths to catch associativity/statelessness bugs.

### 6.7 `Optional` best practices

- **Do** use `Optional` as a **return type** for "maybe no result."
- **Don't** use it as a **field**, **method parameter**, or in **collections** (`List<Optional<X>>` is an anti-pattern; filter empties out instead).
- **Don't** call `get()` without an `isPresent()` guard — prefer `orElse`/`orElseGet`/`orElseThrow`/`map`/`ifPresent`.
- **`orElse` vs `orElseGet`:** `orElse(expensiveCompute())` **always** evaluates the argument even when the Optional is present; `orElseGet(() -> expensiveCompute())` evaluates only when empty. Use `orElseGet` for anything non-trivial or side-effecting.
- Don't return `null` from a method declared to return `Optional`.

### 6.8 Anti-patterns to avoid

- Using streams purely for side effects where a loop is clearer (`stream().forEach(...)` with no transformation).
- Long stream chains that hide complex branching — refactor to a loop or extract methods.
- Reusing a consumed stream (throws `IllegalStateException`).
- `parallel()` sprinkled hopefully without measurement (often slower — see §7).
- Boxing-heavy object streams for numeric work.
- `collect(toMap(...))` without a merge function on data that can have duplicate keys.
- Mutating the source collection inside the pipeline.

---

## 7. Advanced topics & deep internals

### 7.1 Stream flags and why they matter

Each stage carries a bitmask combining `SIZED`, `ORDERED`, `DISTINCT`, `SORTED`, plus the op-level `SHORT_CIRCUIT`. The pipeline derives the **effective** flags by ANDing/clearing as ops are added. These drive optimizations:
- `count()` (Java 9+) can return the size **without traversing** if the stream is `SIZED` and contains no ops that change the count (`filter`, `flatMap`, `distinct`, `limit`). This is why a `peek` before `count()` may never execute.
- `distinct()` on a stream already flagged `DISTINCT` is a no-op.
- `sorted()` on a stream already `SORTED` (same comparator) is skipped.
- `unordered()` clears `ORDERED`, enabling faster `distinct`/`limit`/parallel-collect.

### 7.2 `findFirst` vs `findAny`

- `findFirst` must return the **first element in encounter order** — in parallel this forces ordering bookkeeping and can be slower.
- `findAny` may return **any** matching element — in parallel it returns the first one any worker finds, so it's faster. Use `findAny` when you truly don't care which match you get.

### 7.3 Ordering: the deepest subtlety

A stream may or may not be **ordered**. Order is preserved through `map`/`filter` but:
- `forEach` (parallel) does **not** preserve order; `forEachOrdered` does (at a cost).
- `Collectors.toList()` preserves encounter order even in parallel (the combiner merges in order).
- `Collectors.toSet()`/`groupingBy` into a `HashMap` make no order promise.
- `unordered()` *explicitly* relaxes order, letting the engine combine results as they finish.

**Practical rule:** if downstream code depends on order, either keep the stream sequential, use `forEachOrdered`/`toList`, or be explicit. If order doesn't matter, `unordered()` can speed up parallel pipelines.

### 7.4 When parallel streams **help**

All of these should hold:
1. **Large N** — typically tens of thousands+ elements (the threshold depends on per-element cost). Tiny streams lose to overhead.
2. **CPU-bound work per element** — meaningful computation, not trivial field access.
3. **Cheaply splittable source** — `ArrayList`, arrays, `IntStream.range`, `HashMap` values. (Splits in O(1)/O(log n).)
4. **No shared mutable state / no ordering constraints / associative combiner.**
5. **Spare cores** — you're not already saturating the common pool elsewhere.

`N * Q` heuristic (from Brian Goetz): parallelism tends to pay off when `N` (element count) × `Q` (cost per element) is large — order of magnitude **≥ 10,000** as a starting gut-check, then measure.

### 7.5 When parallel streams **hurt**

- **Poorly splittable sources**: `LinkedList`, `Stream.iterate`, `BufferedReader.lines`, most I/O sources — `trySplit` returns null or unbalanced halves → one thread does everything plus coordination overhead.
- **Cheap per-element work**: split/merge overhead dominates.
- **Ordered + stateful ops** (`sorted`, `limit`, `forEachOrdered`): force buffering/serialization.
- **Boxing** amplifies allocation across threads, stressing GC.
- **Common-pool contention**: another component (or a blocking task) is using `commonPool()`; you starve.
- **Blocking I/O inside the pipeline**: parks Fork/Join workers, which are not meant to block; can deadlock or collapse throughput. Never do blocking calls in a parallel stream on the common pool.

### 7.6 Controlling the thread pool for parallel streams

There is no public API to pass an `Executor` to `Stream.parallel()`. The documented trick is to **submit the terminal operation to your own `ForkJoinPool`**, which the parallel stream then uses:

```java
ForkJoinPool pool = new ForkJoinPool(4);   // dedicated pool, won't starve the common pool
try {
    long sum = pool.submit(() ->
        list.parallelStream().mapToLong(this::heavy).sum()
    ).get();                                // runs on THIS pool's workers
} finally {
    pool.shutdown();
}
```

This is a widely-used pattern but technically relies on an implementation detail (that the stream uses the pool of the calling Fork/Join worker). It is stable across HotSpot but flag it as not strictly contractual.

### 7.7 Custom Spliterators

For exotic sources or to control split behavior, implement `Spliterator<T>` (or extend `Spliterators.AbstractSpliterator`). Report accurate `characteristics()` (`SIZED`/`SUBSIZED`/`ORDERED`/...) and a good `trySplit` for parallel performance. Then `StreamSupport.stream(spliterator, parallel)`. Reporting `SIZED`/`SUBSIZED` lets the engine compute leaf targets and pre-size buffers.

### 7.8 `Stream.iterate` (3-arg) and `takeWhile`/`dropWhile` (Java 9)

- 3-arg `iterate(seed, hasNext, next)` gives a **finite** stream with a built-in stop condition — a clean replacement for `for (int i = start; cond; i = next)`.
- `takeWhile`/`dropWhile` are **stateful** on ordered streams: `takeWhile` is short-circuiting; `dropWhile` is not. On *unordered* parallel streams their semantics are surprising (which prefix?), so prefer them on sequential/ordered streams.

### 7.9 `mapMulti` (Java 16) vs `flatMap`

`flatMap` creates a new `Stream` per element (allocation + indirection). `mapMulti(BiConsumer<T, Consumer<R>>)` lets you push 0..n results directly into a downstream consumer with **no intermediate Stream**, which is faster when the per-element fan-out is small or you already have an imperative way to emit results. Trade-off: less declarative.

### 7.10 Lambda startup & AOT considerations

`invokedynamic` lambda linkage costs a little at **first** use. For startup-sensitive deployments (serverless cold starts, CLIs), this is usually negligible but real. Mitigations: CDS/AppCDS (class data sharing) including the spun lambda classes (`-XX:+AutoCreateSharedArchive`), or GraalVM native-image (which resolves lambdas at build time). Steady-state throughput is unaffected.

### 7.11 GC and escape analysis

Capturing lambdas and boxed elements create short-lived objects. The JIT's **escape analysis** can often **scalar-replace** capturing lambdas and small wrappers so they never hit the heap. This works best for tight, monomorphic call sites; **megamorphic** sites (the same stream op fed many different lambda shapes / many concrete types) defeat inlining and EA, hurting throughput. This is why micro-benchmarks of streams are notoriously misleading without JMH and realistic call-site diversity.

### 7.12 `collect` characteristics tuning

`Collector.Characteristics`:
- `UNORDERED` — result doesn't depend on encounter order → faster parallel combination.
- `CONCURRENT` — accumulator is thread-safe; a *single* shared accumulator can be used across threads (no per-thread accumulator + combine). Only beneficial when also `UNORDERED` (or the source is unordered).
- `IDENTITY_FINISH` — the finisher is identity (A == R), so the engine skips the finisher call.

`groupingByConcurrent` + parallel + unordered source = single `ConcurrentHashMap` shared by all workers (no merge step), often the fastest parallel grouping.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Streams vs loops — decision table

| Factor | Prefer stream | Prefer loop |
|---|---|---|
| Intent is a transform pipeline (filter/map/group) | ✓ | |
| Hot inner loop, last-drop performance | | ✓ |
| Need index, two pointers, multiple cursors | | ✓ |
| Multiple early exits / complex branching | | ✓ |
| Side effects / mutate several externals | | ✓ |
| Checked exceptions in the body | | ✓ (streams force wrapping) |
| Want opt-in parallelism | ✓ | |
| Numeric work over primitives | ✓ (`IntStream`) or loop | both fine |
| Small collection, very hot path | | ✓ |
| Readability for a transform | ✓ | |

### 8.2 Sequential vs parallel — decision rules

**Use parallel when:** large N (≥ ~10k as a starting point), expensive per-element work, splittable source (array/`ArrayList`/range), associative/stateless ops, no order dependence, spare cores, no blocking I/O. **Always measure with JMH** on production-like data.

**Avoid parallel when:** small N, cheap per-element work, `LinkedList`/iterate/I-O sources, ordering or stateful barriers, the common pool is contended, or any blocking inside the pipeline.

### 8.3 `reduce` vs `collect`

| | `reduce` | `collect` |
|---|---|---|
| Reduction style | **immutable** (combine values) | **mutable** (fold into a container) |
| Result examples | sum, min, max, single value | List, Map, String, stats |
| Parallel cost | cheap if accumulator returns small values | designed for it (combiner merges containers) |
| Anti-pattern | using `reduce` to build a `String`/`List` (O(n²) copies) | — |

Rule: building a **collection or string** → `collect`. Folding to a **single scalar** → `reduce` (or a numeric collector).

### 8.4 `Optional` vs nullable vs exceptions

| Situation | Use |
|---|---|
| Method may legitimately have no result | `Optional<T>` return |
| Field that may be absent | nullable field (not `Optional`) |
| Genuinely exceptional/never-expected absence | throw |
| Collection element absence | omit from collection (don't store `Optional`) |

### 8.5 Stream vs reactive vs parallel-loop

| Need | Tool |
|---|---|
| Sync, declarative bulk transform | Stream API |
| CPU-bound parallel aggregation | parallel Stream / Fork-Join |
| Async, non-blocking, backpressure, event streams | Reactive (Reactor/RxJava/`Flow`) |
| Bespoke parallel algorithm with custom partitioning | Fork/Join `RecursiveTask` directly |

---

## 9. Failure modes & debugging

### 9.1 `IllegalStateException: stream has already been operated upon or closed`

**Cause:** reusing a consumed stream, or attaching two terminals.
**Fix:** create a fresh stream each time; if you need to reuse a pipeline, wrap the source in a `Supplier<Stream<T>>` and call it per consumption.

### 9.2 `IllegalStateException: Duplicate key`

**Cause:** `Collectors.toMap(k, v)` (2-arg) with non-unique keys.
**Diagnose:** the exception message names the colliding values.
**Fix:** use the 3-arg `toMap(k, v, mergeFn)`.

### 9.3 Wrong results only in parallel (the silent killer)

**Symptom:** correct sequentially, wrong/non-deterministic with `.parallel()`.
**Causes:** non-associative `reduce`; shared mutable state in lambdas; order assumptions with `forEach`; stateful side effects.
**Diagnose:** run both modes on the same input and diff; search the pipeline for shared mutable captures and non-associative combiners; add assertions on associativity.
**Fix:** use collectors; make combiners associative; use `forEachOrdered`/`toList` where order matters.

### 9.4 Parallel stream starves the application (common-pool contention)

**Symptom:** unrelated requests slow down or a parallel stream that does blocking I/O hangs.
**Cause:** all parallel streams share `ForkJoinPool.commonPool()`; a long/blocking task monopolizes its (cores-1) workers.
**Diagnose:** thread dump shows `ForkJoinPool.commonPool-worker-*` threads parked/blocked; profiler shows pool saturation.
**Fix:** never block inside a parallel stream on the common pool; run CPU-bound parallel work on a **dedicated `ForkJoinPool`** (§7.6); or simply keep it sequential and parallelize at a higher level (request threads).

### 9.5 `ConcurrentModificationException`

**Cause:** mutating the source collection during stream execution, or non-thread-safe accumulation in parallel `forEach`.
**Fix:** don't mutate the source; collect instead of side-effecting.

### 9.6 Leaked file handles

**Symptom:** `Too many open files` under load.
**Cause:** `Files.lines`/`walk`/`list` streams not closed.
**Fix:** always try-with-resources around I/O-backed streams.

### 9.7 Infinite stream never terminates / OOM

**Cause:** `Stream.iterate`/`generate` without a bound, or a non-short-circuiting terminal on an infinite stream, or `sorted()`/`distinct()` (buffering) on an infinite stream.
**Fix:** bound with `limit`/`takeWhile` *before* any buffering op; use short-circuiting terminals (`findFirst`, `anyMatch`).

### 9.8 Surprising `peek`/`count` behavior

**Symptom:** `peek` side effects don't run.
**Cause:** Java 9+ `count()` skips traversal on `SIZED` streams; `peek` is then elided.
**Fix:** don't rely on `peek` for required side effects; it's a debugging aid.

### 9.9 Debugging tools and techniques

- Insert `.peek(x -> log.debug("after filter: {}", x))` temporarily to see elements flowing.
- Break a chain into named variables/methods to localize a failure.
- Use **JMH** for any performance claim — never `System.nanoTime()` micro-benchmarks (JIT warmup, dead-code elimination, and on-stack replacement make naive timing lie).
- For parallel issues, **thread dumps** (`jstack`) and async-profiler/JFR (Java Flight Recorder) to see Fork/Join worker activity and pool saturation.
- `Spliterator.characteristics()`/`estimateSize()` to verify your source splits well before trusting `.parallel()`.

### 9.10 A real-world flavor incident

A classic production pattern: a service used `list.parallelStream().map(callRemoteService).collect(...)` where `callRemoteService` did a **blocking HTTP call**. Under load this saturated the **common ForkJoinPool** with blocked workers; *every other* parallel stream in the JVM (including framework-internal ones) stalled, and latency exploded JVM-wide. The fix was to make the remote calls sequential with an explicit bounded executor (or async), and never to do blocking I/O on the common pool. Lesson: **parallel streams are for CPU-bound, non-blocking work only.**

---

## 10. Interview drill

**Q1. What makes an interface "functional," and why does it matter for lambdas?**
A: A functional interface has exactly one abstract method (SAM); `default`/`static` methods don't count. It matters because a lambda's *target type* must be a functional interface — the compiler infers parameter types and binds the lambda body to that single method. `@FunctionalInterface` enforces the rule at compile time.
- *Follow-up: Can a functional interface have multiple methods?* Yes — any number of `default`/`static` methods, but only one abstract. (Also, abstract methods overriding `Object` methods like `equals` don't count.)
- *Follow-up: What does `@FunctionalInterface` change at runtime?* Nothing — it's compile-time enforcement only.
- *Follow-up: How is a lambda implemented in bytecode?* Via `invokedynamic` + `LambdaMetafactory`, which spins a hidden class (or reuses a singleton for non-capturing lambdas) at first use — not a `.class` file per lambda like anonymous classes.

**Q2. Walk me through what happens when a stream pipeline executes.**
A: Intermediate ops build a lazy linked plan of stages with stream flags; nothing runs until a terminal op. Then the ops are fused into a `Sink` chain (wrapped last-to-first), the source `Spliterator` is obtained, and each element is pushed through the whole chain in one pass (`forEachRemaining` or `tryAdvance` for short-circuit). For parallel, the spliterator is recursively split into leaves run on the common ForkJoinPool, then combined.
- *Follow-up: What's a Spliterator?* A splittable iterator: `tryAdvance`, `forEachRemaining`, `trySplit`, `estimateSize`, `characteristics` — the traversal+splitting engine.
- *Follow-up: What is operation fusion?* Stateless ops compile into one tight loop body so the JIT inlines them; no intermediate collections.
- *Follow-up: How does short-circuiting work internally?* The sink chain checks `cancellationRequested()` between elements; once a short-circuit terminal (`findFirst`) has its answer it cancels traversal.

**Q3. Difference between `map` and `flatMap`?**
A: `map` is 1:1 (`Function<T,R>`); `flatMap` is 1:many (`Function<T,Stream<R>>`) and flattens the resulting substreams into one stream. Use `flatMap` to flatten nested collections or to drop empties (`Optional::stream`).
- *Follow-up: When prefer `mapMulti`?* Java 16+, when fan-out is small/imperative — it avoids allocating a Stream per element.
- *Follow-up: Does `flatMap` preserve order?* Yes, on ordered streams it concatenates substreams in encounter order.

**Q4. `reduce` vs `collect`?**
A: `reduce` is immutable reduction (combine values into one value, e.g. sum); `collect` is mutable reduction (fold into a container, e.g. List/Map). Use `collect` for collections/strings (avoids O(n²) copying), `reduce` for scalars.
- *Follow-up: Why does the 3-arg `reduce` need a combiner?* For parallel: the accumulator may produce a different type than the elements, so a separate associative combiner merges partial results.
- *Follow-up: What breaks if the combiner isn't associative?* Parallel results become wrong/non-deterministic while sequential stays correct — a nasty latent bug.

**Q5. When should you use a parallel stream — and when not?** *(senior-signal)*
A: Use it for large (≥ ~10k), CPU-bound, non-blocking, order-insensitive aggregations over cheaply-splittable sources (arrays/`ArrayList`/ranges) with associative ops, when spare cores exist — and only after measuring with JMH. Avoid for small N, cheap per-element work, poorly-splittable sources (`LinkedList`, `iterate`, I/O), ordered/stateful pipelines, blocking I/O, or when the common pool is contended.
- *Follow-up: Which pool runs it, and what's the gotcha?* The common ForkJoinPool (parallelism = cores−1 by default), shared JVM-wide — blocking I/O there starves the whole JVM.
- *Follow-up: How do you use a custom pool?* Submit the terminal op to your own `ForkJoinPool` via `pool.submit(() -> stream.parallel()...).get()`.
- *Follow-up: Why does source type matter so much?* `trySplit` quality: arrays split O(1) balanced; `LinkedList`/iterate split poorly, degrading to one busy thread plus overhead.

**Q6. Explain encounter order and where it bites. ** *(senior-signal)*
A: Encounter order is the source's element order. `map`/`filter` preserve it; `forEach` (parallel) doesn't (`forEachOrdered` does, at cost); `toList` preserves it even in parallel; `toSet`/`HashMap`-grouping don't. `unordered()` relaxes the constraint to speed parallel `distinct`/`limit`/collect. The bite: code assuming order from a parallel `forEach`, or assuming order from a set/map collector.
- *Follow-up: `findFirst` vs `findAny`?* `findFirst` returns the encounter-first element (ordering cost in parallel); `findAny` returns any match (faster in parallel).
- *Follow-up: How do you keep order cheaply in parallel?* Use `toList`/collectors that preserve order; avoid `forEachOrdered` unless required.

**Q7. `orElse` vs `orElseGet` — does it matter?**
A: Yes. `orElse(x)` evaluates `x` **always**, even when the Optional is present; `orElseGet(supplier)` evaluates only when empty. For expensive or side-effecting defaults, use `orElseGet`.
- *Follow-up: When is `Optional` an anti-pattern?* As a field, parameter, or collection element. It's meant as a return type.
- *Follow-up: Why avoid `Optional.get()`?* It throws on empty; prefer `orElse*`/`map`/`ifPresent` which force you to handle absence.

**Q8. Why are captured locals required to be effectively final?**
A: A lambda may outlive its enclosing stack frame, so it captures a *copy* of locals; allowing reassignment would create ambiguous/racy semantics. Effective finality guarantees a single, stable value. Capture `this`/fields freely (those are not copied).
- *Follow-up: Workaround to mutate captured state?* Use a holder (`int[]`/`AtomicInteger`) — but in streams this is usually a smell; prefer `reduce`/`collect`.
- *Follow-up: Lambda `this` vs anonymous class `this`?* Lambda's `this` is the enclosing instance; anonymous class's `this` is the anonymous object.

**Q9. Are streams faster than loops?** *(senior-signal)*
A: Not categorically. Warmed-up sequential streams over arrays/`ArrayList` with stateless ops are competitive (fusion + inlining). Primitive streams match loops. Boxed object streams over numbers are slower (boxing/GC). Cold/short streams pay fixed setup cost. Choose streams for clarity and opt-in parallelism; choose loops for the hottest paths or complex control flow — and decide with JMH, not intuition.
- *Follow-up: Why do micro-benchmarks mislead?* JIT warmup, dead-code elimination, escape analysis, megamorphic call sites — use JMH and realistic call-site diversity.
- *Follow-up: What's the cost of boxing here?* Heap allocation per boxed value (outside −128..127 cache) and pointer chasing; mitigate with `IntStream`/`mapToInt`.

**Q10. How do you parallelize without polluting the common pool?**
A: Submit the terminal op to a dedicated `ForkJoinPool`, or restructure to parallelize at a coarser level (request-level threads, executors). Never block inside a common-pool parallel stream.
- *Follow-up: Default common-pool parallelism?* `availableProcessors() − 1`; tunable via `-Djava.util.concurrent.ForkJoinPool.common.parallelism`.
- *Follow-up: How detect contention in prod?* Thread dumps showing `commonPool-worker-*` parked/blocked; JFR/async-profiler pool saturation.

**Q11. What's a `Collector` made of, and how does grouping compose?**
A: Five parts: supplier, accumulator, combiner, finisher, characteristics. Composition comes from **downstream collectors**: `groupingBy(classifier, downstream)` buckets by key then applies a downstream collector (`counting()`, `mapping(...)`, nested `groupingBy`, etc.). Characteristics (`UNORDERED`, `CONCURRENT`, `IDENTITY_FINISH`) tune parallel behavior.
- *Follow-up: `groupingBy` vs `groupingByConcurrent`?* The concurrent variant uses a shared `ConcurrentHashMap` (no per-thread merge) and shines for unordered parallel sources.
- *Follow-up: `partitioningBy` vs `groupingBy`?* Partitioning splits on a predicate into exactly two buckets (always both keys present, even if empty); grouping creates arbitrary keys.

**Q12. How would you compute top-N efficiently with streams?** *(senior-signal)*
A: `sorted().limit(n)` sorts the *entire* stream first (O(n log n) + full buffer) — wasteful for small N over huge data. Better: a bounded `PriorityQueue` of size N (a min-heap of the top N), filled via a custom collector or a loop — O(n log N) time, O(N) space. Streams have no built-in top-N optimization.
- *Follow-up: Why no JDK optimization?* `limit` after a stateful `sorted` barrier can't push the bound into the sort.
- *Follow-up: Parallel top-N?* Per-leaf bounded heaps merged in the combiner — express as a custom collector.

---

## 11. Glossary

- **Accumulator (Collector):** the mutable intermediate container a collector folds elements into; also the `BiFunction` in `reduce` that folds an element into a partial result.
- **Anonymous class:** an unnamed inline class instance; pre-lambda way to pass behavior; generates a `.class` file and has its own `this`.
- **Associativity:** `(a∘b)∘c == a∘(b∘c)`; required of `reduce`/collector combiners for correct parallel results.
- **Autoboxing / boxing:** automatic conversion between primitives and their wrapper objects (`int`↔`Integer`); allocates heap objects (except cached small ints).
- **Backpressure:** a consumer signaling a producer to slow down; a *reactive*-streams concept, **absent** from `java.util.stream`.
- **Bound vs unbound method reference:** bound captures a specific receiver (`out::println`); unbound names a type and uses the first argument as receiver (`String::length`).
- **Characteristics (Spliterator/Collector):** flags describing properties (`SIZED`, `ORDERED`, `DISTINCT`, `CONCURRENT`, `UNORDERED`, `IDENTITY_FINISH`) used for optimization.
- **Collector:** a recipe (`supplier`, `accumulator`, `combiner`, `finisher`, `characteristics`) for a mutable reduction.
- **Combiner:** merges two partial accumulators (for parallel) in `reduce`/`Collector`.
- **Common ForkJoinPool:** the JVM-wide shared `ForkJoinPool.commonPool()` used by default for parallel streams; default parallelism `cores − 1`.
- **Downstream collector:** a collector passed into another (e.g. into `groupingBy`) to process each group.
- **Effectively final:** a local never reassigned after init; required for lambda capture.
- **Encounter order:** the order in which a stream presents elements (defined for lists/arrays, not for `HashSet`).
- **Eager operation:** runs immediately and consumes the stream — all terminal ops (short-circuit ones may stop early).
- **Escape analysis:** a JIT optimization that proves an object doesn't escape a scope and can be stack-allocated / scalar-replaced.
- **Finisher:** the `A→R` final transform in a collector (skipped if `IDENTITY_FINISH`).
- **Fork/Join framework:** divide-and-conquer work-stealing pool (`ForkJoinPool`); engine for parallel streams.
- **Functional interface:** an interface with exactly one abstract method (SAM); lambda target type.
- **Fusion (operation/loop):** compiling chained stateless ops into one loop body to avoid intermediate collections and enable inlining.
- **Identity (reduce):** a value `e` with `combine(e, x) == x`; the starting/neutral element.
- **Intermediate operation:** returns a new stream, is lazy (`filter`, `map`, `sorted`, …).
- **`invokedynamic`:** JVM instruction deferring call-target resolution to runtime; used to link lambdas via `LambdaMetafactory`.
- **JMH:** Java Microbenchmark Harness; the correct tool for measuring stream/loop performance.
- **Lambda expression:** an anonymous function literal compiled to a functional-interface instance.
- **`LambdaMetafactory`:** the bootstrap that materializes lambda implementations at first use.
- **Laziness:** intermediate ops do no work until a terminal op fires.
- **Method reference:** shorthand for a lambda that only calls an existing method (`Class::method`).
- **Mutable reduction:** folding elements into a mutable container (`collect`), vs immutable reduction (`reduce`).
- **Non-interference:** lambdas must not modify the stream source during execution.
- **`Optional<T>`:** a container for zero-or-one value, making absence explicit in the type; a return type, not a field/param.
- **Parallel stream:** a stream whose terminal op runs on the Fork/Join pool by splitting the source.
- **`peek`:** a debugging intermediate op (a `Consumer`) that may be elided by the engine.
- **Predicate:** `boolean test(T)` functional interface; condition for `filter`/match ops.
- **Primitive stream:** `IntStream`/`LongStream`/`DoubleStream` — boxing-free numeric streams.
- **ReDoS:** regex denial of service via catastrophic backtracking on crafted input.
- **Reduction (fold):** combining all elements into a single result.
- **SAM:** Single Abstract Method — the lone abstract method of a functional interface.
- **Scalar replacement:** JIT replacing an object's fields with local scalars (no allocation), enabled by escape analysis.
- **Short-circuiting:** an op that may finish without consuming the whole stream (`limit`, `findFirst`, `anyMatch`).
- **Sink:** the internal `Consumer`-like callback chain (`begin`/`accept`/`end`/`cancellationRequested`) that fused ops form.
- **Source:** what creates a stream (collection, array, generator, I/O).
- **Spliterator:** splittable iterator; traverses (`tryAdvance`/`forEachRemaining`) and splits (`trySplit`) for parallelism.
- **Stateful operation:** needs other elements to act (`sorted`, `distinct`, `limit`); may buffer; barrier to fusion.
- **Stateless operation:** acts per element independently (`filter`, `map`, `flatMap`).
- **Stream flags:** internal bitmask (`SIZED`/`ORDERED`/`DISTINCT`/`SORTED`/`SHORT_CIRCUIT`) driving optimizations.
- **Terminal operation:** produces a result/side effect and triggers execution (`collect`, `reduce`, `forEach`, `count`).
- **Unordered:** a stream/op state relaxing encounter-order constraints to speed parallel work (`unordered()`).
- **Work-stealing:** idle Fork/Join workers steal tasks from busy workers' deques to balance load.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Pipeline = source → 0..n lazy intermediate ops → 1 eager terminal op.** Single-use; lazy; internal iteration; fusion.

**Lazy intermediates:** `filter map flatMap mapMulti distinct* sorted* peek limit*↯ skip* takeWhile*↯ dropWhile* mapToInt boxed parallel unordered` (`*` = stateful, `↯` = short-circuit).

**Terminals:** `collect reduce forEach forEachOrdered count min max findFirst↯ findAny↯ anyMatch↯ allMatch↯ noneMatch↯ toList toArray sum/average/summaryStatistics`.

**Collectors:** `toList toSet toMap(k,v[,merge[,supplier]]) groupingBy(c[,map][,down]) partitioningBy counting summingX averagingX summarizingX minBy maxBy mapping filtering flatMapping reducing joining collectingAndThen teeing`. Use the **3-arg `toMap`** to avoid duplicate-key crashes. `partitioningBy` always has `true` and `false` keys.

**Optional:** return type only; `orElseGet` (lazy) over `orElse` (eager); never `get()`; chain with `map`/`flatMap`/`filter`; `Optional::stream` to drop empties.

**Numbers:** common-pool parallelism default = **cores − 1**; tune `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N`. Parallel gut-check: payoff when `N × cost ≳ 10,000`, then **measure with JMH**.

**Parallel works when:** large N, CPU-bound, splittable source (array/`ArrayList`/range), associative/stateless, order-insensitive, spare cores, **no blocking I/O on the common pool**.

**Parallel hurts when:** small N, cheap work, `LinkedList`/`iterate`/I-O sources, `sorted`/`limit`/`forEachOrdered`, boxing, common-pool contention, blocking.

**Top anti-patterns:** shared mutable state in `forEach`; non-associative `reduce`; `reduce` to build collections/strings; reusing a consumed stream; boxed numeric streams; unclosed `Files.lines`; hopeful `.parallel()`.

**Always close** I/O-backed streams (`Files.lines/walk/list`, `BufferedReader.lines`) with try-with-resources.

**reduce vs collect:** scalar fold → `reduce`; build container/string → `collect`. **map vs flatMap:** 1:1 vs 1:many+flatten. **findFirst vs findAny:** ordered vs any (parallel-friendly).

### 12.2 Self-test (no answers — active recall)

1. Trace, stage by stage, what happens internally when `list.stream().filter(p).map(f).findFirst()` executes — name the classes/methods involved and explain why no intermediate lists are created.
2. You have a `List<Order>` each with `List<LineItem>`. Write a single pipeline producing `Map<String,Long>` of total quantity per SKU, and explain which operations are stateful and which can short-circuit.
3. A `reduce`-based parallel sum gives correct results, but a `reduce`-based parallel *string concatenation* gives wrong results. Explain precisely why, in terms of the combiner contract.
4. Your service's latency spikes JVM-wide whenever one endpoint runs a `parallelStream()` that calls a remote API. Diagnose the root cause and give two distinct fixes.
5. Explain `orElse` vs `orElseGet` and give a concrete case where choosing wrong causes a bug or a performance problem; then explain why `Optional` should not be a field or method parameter.
6. When is `sorted().limit(n)` the wrong way to get the top N, what does it cost, and what should you do instead (including for the parallel case)?
7. Why must captured local variables be effectively final, and how does a lambda's `this` differ from an anonymous class's `this`? How are lambdas linked at runtime, and why does that differ from anonymous classes in terms of generated classes and allocation?

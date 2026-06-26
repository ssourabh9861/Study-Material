# OOM & Memory Leak Diagnosis

> JVM Internals & Garbage Collection — a definitive engineering-handbook chapter for senior JVM backend developers.

---

## 1. Overview & where it fits

### What it is

**OOM (`OutOfMemoryError`) and memory-leak diagnosis** is the discipline of figuring out *why* a JVM process either (a) threw an `OutOfMemoryError`, or (b) is steadily consuming more memory than it should, and then *fixing the root cause* — not just papering over it by raising heap limits.

A few terms up front, defined plainly because the rest of the chapter leans on them:

- **JVM (Java Virtual Machine):** the runtime process that executes Java bytecode. It manages memory on your behalf via a **garbage collector (GC)** — a background subsystem that reclaims objects no longer reachable by your program. Because the GC frees memory automatically, "memory leaks" in Java are *logical* leaks: you still hold a reference to something you no longer need, so the GC is forbidden from reclaiming it.
- **Heap:** the region of memory where Java objects live. It is GC-managed.
- **`OutOfMemoryError` (OOME):** a `java.lang.Error` (not a checked `Exception`) the JVM throws when it cannot satisfy a memory allocation request *and* the GC cannot free enough space to make room. It is thrown from the exact site that needed the memory, which is often *not* the leaking code — an important diagnostic subtlety.
- **Reachability:** an object is **reachable** if there is a chain of references from a **GC root** (a thread stack local, a static field, a JNI handle, etc.) to that object. Reachable = cannot be collected. Unreachable = eligible for collection.
- **Memory leak (in Java):** objects that are still reachable from a GC root but will never be used again. The GC sees them as "live," so they accumulate forever.

### The problem it solves

Without systematic diagnosis you are left with two bad options: blindly bumping `-Xmx` (which only delays the crash and worsens GC pauses), or restarting the process on a cron (the "have you tried turning it off and on again" of memory). Diagnosis lets you:

1. Distinguish a **true leak** (unbounded growth) from **legitimate high usage** (you simply need more heap) from a **transient spike** (a single huge allocation) from **GC pressure** (the heap is fine but collections can't keep up).
2. Pin the leak to a **specific object graph, class, and code path** using heap dumps and dominator analysis.
3. Fix it at the source and add observability so it never silently regresses.

### When you reach for it

- A pod/instance gets OOM-killed by Kubernetes (`OOMKilled`, exit code 137) or by the Linux kernel OOM killer.
- The JVM logs `java.lang.OutOfMemoryError: Java heap space` (or one of its many siblings).
- Heap-usage graphs show a sawtooth that **trends upward over hours/days** — the classic leak signature — rather than oscillating around a stable baseline.
- GC logs show **rising "after-GC" live-set occupancy** and lengthening pauses; the app feels progressively slower until it dies.
- RSS (resident set size, the actual physical RAM the process holds) keeps climbing even though heap-after-GC is flat — pointing at *off-heap* growth.

### The one-paragraph mental model

The JVM allocates objects on the heap and lets the GC reclaim anything unreachable. An `OutOfMemoryError` means a request for memory failed and the GC couldn't help. There are several *kinds* of memory the JVM uses — Java heap, Metaspace (class metadata), thread stacks, direct/native buffers, the code cache — and each can run out independently, producing a *different* OOME message. A memory **leak** is not the GC failing; it is your code accidentally keeping objects reachable forever. Diagnosis is therefore a two-part job: first **read the OOME message** to learn *which* memory pool is exhausted, then **capture and analyze a heap dump** (for heap/Metaspace) or use **native memory tracking / OS tools** (for off-heap) to find *what* object graph or allocation is growing and *who* is holding the reference. You then fix the root cause and add a guardrail so it cannot silently return.

---

## 2. Foundations from first principles

### 2.1 The JVM's memory regions

Before you can diagnose "out of memory," you must know *which* memory. A modern HotSpot JVM (Oracle/OpenJDK) divides its address space into several pools. Knowing them is non-negotiable because each throws a *different* OOME.

| Region | Lives where | Holds | GC-managed? | Sized by |
|---|---|---|---|---|
| **Java heap** | In-process, virtual address space | All `new` objects, arrays | Yes | `-Xms` / `-Xmx` |
| **Metaspace** | Native memory (off-heap) | Class metadata: `Klass` structures, method bytecode, constant pools | Partially (unloaded with classloaders) | `-XX:MetaspaceSize` / `-XX:MaxMetaspaceSize` |
| **Thread stacks** | Native memory, one per thread | Stack frames, locals, return addresses | No (freed when thread dies) | `-Xss` per thread |
| **Code cache** | Native memory | JIT-compiled native code | Managed by JIT, can be flushed | `-XX:ReservedCodeCacheSize` |
| **Direct/native buffers** | Native memory | `ByteBuffer.allocateDirect`, memory-mapped files, NIO | No (freed via `Cleaner`/`Unsafe.freeMemory`) | `-XX:MaxDirectMemorySize` |
| **GC bookkeeping** | Native memory | Card tables, remembered sets, marking bitmaps | Internal | Derived from heap size & GC |
| **C heap / native libs** | Native memory | JNI allocations, native library state | No | OS / library specific |

**Key term — off-heap / native memory:** any memory the JVM uses that is *not* part of the Java heap. The GC does not move or scan it the way it does heap objects. Off-heap leaks are the hardest to diagnose because a normal heap dump does not show them directly.

**Key term — RSS (Resident Set Size):** the total physical RAM the OS reports the process is using. RSS ≈ heap committed + Metaspace + thread stacks + code cache + direct buffers + native libs + JVM overhead. A container's memory limit is enforced against RSS, *not* against `-Xmx`. This is why setting `-Xmx` to the full container size gets you OOM-killed: there is no room left for the *other* pools.

### 2.2 Reachability, GC roots, and what "live" means

The GC determines liveness by **tracing**: starting from a set of **GC roots**, it walks every reference and marks everything it can reach. Anything unmarked is garbage.

**GC roots** include:

- **Local variables and operands** on every live thread's stack (and JVM-internal registers).
- **Static fields** of loaded classes (held by the classloader, which is held by... see classloader leaks below).
- **JNI references** (native code holding Java objects, both local and global).
- **Active Java threads** themselves (a running thread is a root).
- **Synchronization monitors** held by threads, classes being initialized, and a few JVM-internal structures.

The single most important diagnostic idea in this whole chapter: **a memory leak is a path from a GC root to an object you no longer want.** Your job in heap-dump analysis is to find that path — the **reference chain to the GC root** — and break it.

### 2.3 Reference strengths (Strong, Soft, Weak, Phantom)

Java has four reference strengths, and they govern leak behavior:

- **Strong reference:** the normal `Object o = new Object()`. As long as a strong reference chain from a root exists, the object is never collected. *Leaks are almost always strong references you forgot about.*
- **Soft reference (`SoftReference`):** the GC *may* collect the referent, but typically only under memory pressure (just before an OOME). Intended for memory-sensitive caches. **Caveat:** soft references can *delay* OOMEs while making GC work harder; an over-large soft-referenced cache is a common cause of *latency* problems and near-OOM thrash.
- **Weak reference (`WeakReference`):** collected as soon as no strong references remain (at the next GC). Used by `WeakHashMap` and many cache implementations.
- **Phantom reference (`PhantomReference`):** enqueued *after* the object is finalized; used for precise post-mortem cleanup (e.g. freeing native memory). The JDK's `Cleaner` API is built on phantom references.

**Key term — referent:** the object a reference *points at*. For a `WeakReference<Foo>`, the `Foo` is the referent.

### 2.4 What `OutOfMemoryError` actually is

`OutOfMemoryError extends VirtualMachineError extends Error extends Throwable`. Critical properties:

- It is an **`Error`**, not an `Exception`. The convention is that you should *not* try to recover from it. By the time it's thrown, the JVM is in a degraded state — though there are nuances (a single oversized `int[]` allocation failing doesn't necessarily mean the whole JVM is doomed).
- It is thrown **at the allocation site that failed**, which is frequently innocent code. The thread that finally tips memory over the edge is rarely the culprit. *This is why stack traces alone almost never identify a leak.*
- Catching it is legal but usually wrong. The one defensible pattern is `-XX:+ExitOnOutOfMemoryError` or `-XX:+CrashOnOutOfMemoryError` — fail fast, let the orchestrator restart a clean process — combined with `-XX:+HeapDumpOnOutOfMemoryError` to capture evidence first.

### 2.5 The leak vs. high-usage vs. spike distinction

This framing prevents most wasted investigation time:

1. **True leak:** unbounded, monotonic growth of the *live set* (heap occupancy *after* a full GC). Eventually OOMEs no matter how big the heap. **Fix:** find and break the reference chain.
2. **Legitimately high usage:** stable but high live set; you're caching/holding exactly what you intend. **Fix:** size the heap correctly, or reduce working-set size (smaller caches, streaming instead of materializing).
3. **Transient spike:** one request materializes a huge object (e.g. `SELECT *` with no paging loaded into a `List`). Heap-after-GC is fine; a single allocation just exceeded headroom. **Fix:** stream/paginate; cap request sizes.
4. **GC pressure / allocation rate:** the live set fits, but the *rate* of garbage creation outpaces the collector, so pauses lengthen and `GC overhead limit exceeded` can fire. **Fix:** reduce allocation rate, tune GC, or grow young gen.

The discriminator between #1 and #2/#3 is **heap occupancy after full GC over time**. If that line climbs forever, it's a leak. If it's flat (even if high), it's not.

---

## 3. How it works internally

This is the heart of the chapter. We trace, step by step, (a) how an allocation leads to an OOME, (b) how each OOME variant is produced, and (c) how a heap dump is captured and structured.

### 3.1 The allocation → GC → OOME control flow

When your code executes `new Foo()`, here is the internal sequence in HotSpot:

1. **Fast path — TLAB bump-the-pointer.** Each thread owns a **TLAB (Thread-Local Allocation Buffer)**: a private slice of Eden (the young-generation nursery). Allocation is just `top += size; return old_top`. No locking. This is why Java allocation is famously cheap.
   - *Term — Eden:* the part of the young generation where brand-new objects are born.
2. **TLAB full → slow path.** If the object doesn't fit in the current TLAB, the JVM either (a) retires the TLAB and grabs a new one from Eden, or (b) for large objects, allocates directly in Eden (or even straight into old gen / a humongous region for very large arrays).
3. **Eden full → young GC (minor GC).** The collector pauses application threads (a **stop-the-world (STW)** pause — all mutator threads halt), traces from roots into the young gen, copies survivors to a **survivor space**, and frees Eden. Objects that survive enough cycles are **promoted (tenured)** to old generation.
   - *Term — mutator:* application threads (they "mutate" the object graph), as opposed to GC threads.
4. **Old gen full / promotion fails → full GC (major GC).** A full collection traces the entire heap. With G1 this is a mixed/full cycle; with the classic collectors it's a global mark-sweep-compact.
5. **After full GC, still no room → escalate.** The JVM retries the allocation. If it *still* cannot satisfy the request after a full collection (and after expanding the heap up to `-Xmx`), it throws **`OutOfMemoryError: Java heap space`**.
6. **GC-overhead guard.** Independently, if the JVM spends **>98% of total time in GC** while **recovering <2% of the heap** across recent collections, it gives up early and throws **`GC overhead limit exceeded`** — a mercy killing to avoid a death-spiral where the app does no useful work. (Thresholds configurable via `-XX:GCTimeLimit` and `-XX:GCHeapFreeLimit`.)

**The crucial insight:** the OOME at step 5 is thrown by *whatever thread happened to make the request that couldn't be met*. If a leak has slowly filled old gen, the OOME might surface in a completely unrelated request handler. The stack trace tells you *where the JVM ran out*, not *what filled it up*.

### 3.2 How each OOME variant is produced

| OOME message | Pool exhausted | Internal trigger | Usual root cause |
|---|---|---|---|
| `Java heap space` | Java heap | Allocation + full GC can't free space, heap at `-Xmx` | Leak in heap, undersized heap, or one giant allocation |
| `GC overhead limit exceeded` | Java heap (indirect) | >98% time in GC, <2% reclaimed | Heap nearly full / near-leak; collector thrashing |
| `Requested array size exceeds VM limit` | Java heap | Array length > `Integer.MAX_VALUE - ~2` | Code computing an absurd array size |
| `Metaspace` / `Compressed class space` | Metaspace | Class-metadata native memory exhausted at `-XX:MaxMetaspaceSize` | Classloader leak; runtime class generation (proxies, scripting); too many classes |
| `Direct buffer memory` | Native (NIO direct) | `Bits.reserveMemory` can't reserve up to `-XX:MaxDirectMemorySize` | Direct `ByteBuffer`s not freed; NIO/Netty buffer leak |
| `unable to create new native thread` | Native (per-thread stacks + OS) | `pthread_create`/OS refuses; ulimit or RAM exhausted | Thread leak (unbounded thread creation); too-large `-Xss`; OS limits |
| `Out of swap space?` | Native (C heap) | `malloc` failed in native code | Native/JNI allocation growth; OS over-committed |
| `<reason> ... Java Runtime Environment` (native OOM) / hs_err log | Native (varies) | `mmap`/`malloc` failure in the VM itself | Off-heap pressure; container limit hit |

Note: `GC overhead limit exceeded` is *not* a separate pool — it is the heap-pressure guard firing before a hard `Java heap space`. Treat both as "the heap is too full."

### 3.3 The Metaspace mechanism (and why classloader leaks live here)

**Key term — Metaspace:** since Java 8, class metadata (the internal `Klass` structures describing each loaded class, plus method bytecode, JIT-related metadata, and runtime constant pools) lives in **native memory** called Metaspace, replacing the old fixed-size "PermGen." Metaspace grows on demand up to `-XX:MaxMetaspaceSize` (default: effectively unlimited / bounded by available native memory if not set).

Class metadata is reclaimed **only when its classloader becomes unreachable and is collected.** A classloader is reachable as long as *any* of the classes it loaded is reachable, *and* a class is reachable as long as any of its instances or static fields are reachable. So:

> One leaked instance → keeps its class alive → keeps the class's classloader alive → keeps *every other class that classloader loaded* alive → Metaspace never shrinks.

This is the **classloader leak**, the canonical cause of `OutOfMemoryError: Metaspace`. It's epidemic in app servers and any system that hot-redeploys (Tomcat undeploy/redeploy), uses lots of dynamic proxies, or compiles scripts/templates into fresh classes at runtime.

### 3.4 How a heap dump is captured (internal flow)

A **heap dump** is a snapshot of the entire Java heap — every object, its class, its field values, and its references — in the **HPROF binary format** (`.hprof`). The capture flow:

1. The JVM **reaches a safepoint** and **stops the world** (all mutator threads halt) so the heap is consistent — no references change mid-dump.
   - *Term — safepoint:* a point in execution where all threads can be paused with a fully described stack, allowing the JVM to inspect/relocate objects safely.
2. It optionally runs a **full GC first** (default for `jmap -dump:live` and `HeapDumpOnOutOfMemoryError`) so the dump contains only *live* objects — much smaller and noise-free. (`jmap -dump:all` skips this.)
3. It serializes the heap: a class table, then every object instance with its field values and references, plus thread stacks (to expose GC roots), into the `.hprof` file.
4. The dump file is roughly the size of the **live heap** (often multiple GB). Writing it can take seconds to minutes and pauses the app the entire time — *do not casually trigger this on a latency-sensitive production node without planning.*

**On-OOME capture:** with `-XX:+HeapDumpOnOutOfMemoryError`, the JVM writes the dump *at the moment of the OOME*, capturing the heap in its bloated state — exactly the evidence you want. The file path is controlled by `-XX:HeapDumpPath`.

### 3.5 Native Memory Tracking (NMT) internal flow

For off-heap mysteries, HotSpot offers **NMT (Native Memory Tracking)**, enabled with `-XX:NativeMemoryTracking=summary|detail`. When on, the JVM instruments its *own* native allocations (per subsystem: heap, class, thread, code, GC, internal, etc.) with malloc-site bookkeeping. You then query it live via `jcmd <pid> VM.native_memory summary` and can take a **baseline** and **diff** to see which native category is growing. NMT does *not* track allocations made by third-party native libraries (e.g. a leaking JDBC native driver or a C library) — only the JVM's own.

---

## 4. The complete toolkit

### 4.1 JVM flags for OOM diagnosis & capture

| Flag | Purpose | Default |
|---|---|---|
| `-Xmx<size>` | Max heap | ¼ of physical RAM (ergonomic), capped; container-aware since JDK 8u191/10+ |
| `-Xms<size>` | Initial/min heap | ergonomic; set `=Xmx` in prod to avoid resize pauses |
| `-XX:+HeapDumpOnOutOfMemoryError` | Write `.hprof` on heap OOME | **off** |
| `-XX:HeapDumpPath=<path>` | Where to write the dump (dir or file) | cwd / `java_pid<pid>.hprof` |
| `-XX:+ExitOnOutOfMemoryError` | `System.exit(1)`-style halt on *any* OOME | off |
| `-XX:+CrashOnOutOfMemoryError` | Produce an `hs_err` crash log + core on OOME | off |
| `-XX:OnOutOfMemoryError="<cmd>"` | Run an arbitrary command on OOME (e.g. capture, notify) | none |
| `-XX:MaxMetaspaceSize=<size>` | Cap Metaspace (so a classloader leak fails fast instead of eating all RAM) | unlimited |
| `-XX:MaxDirectMemorySize=<size>` | Cap NIO direct buffers | ≈ `-Xmx` (if unset, derived) |
| `-XX:ReservedCodeCacheSize=<size>` | JIT code cache cap | 240 MB (tiered) |
| `-Xss<size>` | Per-thread stack size | platform-dependent (~512 KB–1 MB) |
| `-XX:NativeMemoryTracking=summary` | Enable NMT | off |
| `-XX:+PrintNMTStatistics` | Dump NMT at JVM exit | off |
| `-Xlog:gc*:file=gc.log:time,uptime,level,tags` | Unified GC logging (JDK 9+) | off |
| `-XX:+UseGCOverheadLimit` | Enable the 98%/2% guard | on |
| `-XX:GCTimeLimit` / `-XX:GCHeapFreeLimit` | Tune the overhead guard | 98 / 2 |
| `-XX:+UnlockDiagnosticVMOptions -XX:+PrintClassHistogramBeforeFullGC` | Histogram before full GCs | off |

> Container note: since JDK 10 (and backported to 8u191), the JVM is **container-aware** — it reads cgroup memory limits and sizes ergonomically off the *container* limit, not the host. `-XX:MaxRAMPercentage=<n>` (default 25%) sets heap as a fraction of detected RAM; prefer it over hard-coded `-Xmx` in containers, but leave ~25–40% headroom for non-heap pools.

### 4.2 CLI tools (JDK-bundled)

| Tool | What it does | Key invocations |
|---|---|---|
| **`jcmd`** | Swiss-army diagnostic command sender (preferred over `jmap`/`jstack` on modern JDKs) | `jcmd <pid> GC.heap_dump <file>`; `jcmd <pid> GC.class_histogram`; `jcmd <pid> VM.native_memory summary`; `jcmd <pid> Thread.print`; `jcmd <pid> GC.run`; `jcmd <pid> VM.info` |
| **`jmap`** | Heap dump & histogram | `jmap -dump:live,format=b,file=heap.hprof <pid>`; `jmap -histo:live <pid>`; `jmap -clstats <pid>` (classloader stats) |
| **`jstack`** | Thread dump (for `unable to create native thread`, deadlocks, where threads are stuck) | `jstack <pid>`; `jstack -l <pid>` (with locks) |
| **`jstat`** | Live GC/heap stats sampling | `jstat -gcutil <pid> 1000` (every 1s); `jstat -gccause <pid> 1000` |
| **`jhsdb`** | Serviceability agent — dump a dead/hung JVM or a core file | `jhsdb jmap --heap --pid <pid>`; `jhsdb jmap --binaryheap --dumpfile h.hprof --core core.<pid> --exe $JAVA_HOME/bin/java` |
| **`jinfo`** | Read/set VM flags at runtime | `jinfo -flag MaxMetaspaceSize <pid>`; `jinfo -flag +HeapDumpOnOutOfMemoryError <pid>` |
| **`jhat`** | (Deprecated) basic heap-dump browser | rarely used; prefer MAT |

> `jmap -histo:live` and `jcmd GC.class_histogram` both **force a full GC** (the `:live` variant) before printing — this is a real STW pause on prod.

### 4.3 Heap-dump analyzers (GUI/offline)

| Tool | Strengths | Notes |
|---|---|---|
| **Eclipse MAT (Memory Analyzer Tool)** | The gold standard. Dominator tree, Leak Suspects report, retained-heap, OQL, path-to-GC-roots, duplicate classes, unreachable-objects histogram | Free, Eclipse-based; needs heap (often `-Xmx` ≥ dump size; uses index files to scale beyond RAM) |
| **VisualVM** | Live monitoring + basic heap-dump browsing, sampling profiler, plus dumps | Bundled separately since JDK 9; good for quick looks |
| **JDK Mission Control (JMC) + JFR** | Flight Recorder analysis: allocation profiling, leak detection via old-object sampling | Low-overhead *continuous* profiling; great for "what allocates a lot" |
| **YourKit / JProfiler** | Commercial, polished allocation & leak profiling, live object inspection | Paid; excellent UX, low overhead modes |
| **`async-profiler`** | Allocation flame graphs (which call paths allocate the most bytes) | Free; pairs perfectly with leak hunts |
| **heaphero / GCeasy** | SaaS uploads for quick automated analysis | Caution: uploading prod dumps off-prem may violate data policy |

### 4.4 Eclipse MAT core concepts (you must know these)

- **Shallow heap:** the memory occupied by *one object itself* — its header plus its own fields (references are just pointers, counted as a word each). Small and usually uninteresting alone.
- **Retained heap (retained set):** the total memory that would be **freed if this object were garbage-collected** — i.e., the object plus everything reachable *only* through it. This is *the* number for leak hunting: the object with huge retained heap is the one "holding the bag."
- **Dominator tree:** a transformed view of the object graph where object **A dominates B** if *every* path from a GC root to B passes through A. The dominator tree makes retained sets a simple subtree-sum, so the biggest retained-heap holders bubble to the top. Reading the top of the dominator tree usually reveals the leak in minutes.
- **Leak Suspects report:** MAT's automated analysis. It finds objects/classes with anomalously large retained heap, names the likely accumulation point (e.g. "one `HashMap$Node[]` retains 1.2 GB"), and shows the accumulating object and its keep-alive path.
- **Path to GC Roots (exclude weak/soft):** right-click an object → "Path to GC Roots" → "exclude all phantom/weak/soft references." This shows the **strong reference chain** keeping the object alive — *the leak path you must break.*
- **OQL (Object Query Language):** SQL-like queries over the dump, e.g. `SELECT * FROM java.util.HashMap WHERE size > 100000`, or `SELECT s FROM java.lang.String s WHERE s.@retainedHeapSize > 1000000`.
- **Histogram:** count and shallow/retained size grouped by class. Sort by retained size or by instance count to spot "10 million `Foo` instances."
- **Unreachable objects histogram:** what *would* have been collected — useful to confirm you dumped a live-only set or to see churn.

---

## 5. Code examples by use case

These are *deliberately leaky* (or diagnostic) examples spanning distinct scenarios. Each is followed by the symptom, the diagnosis, and the fix.

### 5.1 Use case — Java heap leak via an ever-growing `static` collection

```java
public final class AuditTrail {
    // ANTI-PATTERN: a static collection that only ever grows.
    // Static fields are GC roots (held by the class -> classloader),
    // so nothing in here is EVER collected for the life of the JVM.
    private static final Map<String, List<Event>> EVENTS_BY_USER = new ConcurrentHashMap<>();

    public static void record(String userId, Event e) {
        EVENTS_BY_USER
            .computeIfAbsent(userId, k -> new ArrayList<>())  // new list per user, never removed
            .add(e);                                          // grows unboundedly per active user
    }
    // There is NO eviction, NO size cap, NO expiry. This is a textbook leak.
}
```

**Symptom:** heap-after-full-GC climbs linearly with traffic; eventually `OutOfMemoryError: Java heap space`.
**Diagnosis:** in MAT, the dominator tree shows one `ConcurrentHashMap` (or its `Node[]` table) with enormous retained heap; the Leak Suspects report names it. "Path to GC Roots (exclude soft/weak)" terminates at the `static EVENTS_BY_USER` field.
**Fix:** bound it — use a real cache with eviction:

```java
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;

public final class AuditTrail {
    private static final Cache<String, List<Event>> EVENTS_BY_USER =
        Caffeine.newBuilder()
            .maximumSize(100_000)                  // hard cap on entries
            .expireAfterWrite(Duration.ofHours(1))  // time-based eviction
            .build();

    public static void record(String userId, Event e) {
        EVENTS_BY_USER.get(userId, k -> new ArrayList<>()).add(e);
    }
}
```

> Rule of thumb: **any `static` `Map`/`List`/`Set` that things get *added* to but never *removed* from is a leak waiting to happen.** Audit every static collection for an eviction story.

### 5.2 Use case — `ThreadLocal` leak in a thread pool

```java
public class RequestContext {
    // ThreadLocal holding a heavy object, used inside a thread POOL.
    private static final ThreadLocal<HeavyContext> CTX = new ThreadLocal<>();

    public static void begin(HeavyContext c) { CTX.set(c); }
    public static HeavyContext get() { return CTX.get(); }
    // BUG: no remove(). Pool threads live forever, so each thread's
    // ThreadLocalMap keeps the last HeavyContext alive indefinitely.
}
```

**Why it leaks:** a `ThreadLocal` value is stored in the *Thread*'s internal `ThreadLocalMap`. In a pool, threads are reused and never die, so the value persists across unrelated requests. Worse, the `ThreadLocalMap` keys are weak references to the `ThreadLocal` *object*, but the **values are strong** — so even a collected `ThreadLocal` can leave a stale value reachable until the slot is reused. If `HeavyContext` transitively holds a classloader, this also causes a Metaspace/classloader leak in app servers.

**Diagnosis:** MAT shows many `Thread` objects each retaining a `ThreadLocalMap` → `Entry[]` → your `HeavyContext`. Path-to-GC-roots ends at a live pool thread.

**Fix — always `remove()` in a `finally`:**

```java
public static void handle(Request req) {
    RequestContext.begin(buildContext(req));
    try {
        process(req);
    } finally {
        RequestContext.clear();   // <-- mandatory in pooled threads
    }
}
// and in RequestContext:
public static void clear() { CTX.remove(); }   // removes the map entry entirely
```

### 5.3 Use case — unclosed resources (`Connection`/`Statement`/`InputStream`)

```java
// ANTI-PATTERN: leaks a pooled connection on every call that throws.
public List<Row> badQuery(DataSource ds, String sql) throws SQLException {
    Connection c = ds.getConnection();   // borrowed from pool
    Statement s = c.createStatement();
    ResultSet rs = s.executeQuery(sql);  // if this throws, c/s/rs never closed
    List<Row> out = new ArrayList<>();
    while (rs.next()) out.add(map(rs));
    rs.close(); s.close(); c.close();     // unreachable on exception
    return out;
}
```

**Symptom:** the connection *pool* exhausts ("connection pool timeout"), and the heap accumulates `Connection`/`Statement`/`ResultSet` graphs plus their buffered driver state — sometimes surfacing as a heap OOME, often first as pool starvation. JDBC drivers also frequently allocate **direct buffers**, so this can manifest as `Direct buffer memory`.

**Fix — try-with-resources guarantees `close()`:**

```java
public List<Row> goodQuery(DataSource ds, String sql) throws SQLException {
    try (Connection c = ds.getConnection();         // AutoCloseable
         PreparedStatement s = c.prepareStatement(sql);
         ResultSet rs = s.executeQuery()) {          // all closed in reverse order, even on throw
        List<Row> out = new ArrayList<>();
        while (rs.next()) out.add(map(rs));
        return out;
    }
}
```

### 5.4 Use case — direct (off-heap) `ByteBuffer` leak

```java
public class FrameCache {
    private final List<ByteBuffer> frames = new ArrayList<>();

    public void cacheFrame(int size) {
        // Direct buffers live OFF-HEAP. The on-heap DirectByteBuffer object
        // is tiny; its native memory is freed only when a Cleaner runs,
        // which happens only after the tiny on-heap object is GC'd.
        ByteBuffer buf = ByteBuffer.allocateDirect(size);
        frames.add(buf);   // we keep a strong ref -> Cleaner never runs -> native mem held
    }
}
```

**Symptom:** **RSS climbs but heap-after-GC is flat**, and eventually `OutOfMemoryError: Direct buffer memory` (if `-XX:MaxDirectMemorySize` is hit) or an OS/container OOM-kill.
**Diagnosis:** a normal heap dump shows only the small `DirectByteBuffer` *wrapper* objects (with a `capacity` field) — the native bytes aren't in the heap. Sum the `capacity` of all reachable `DirectByteBuffer`s (OQL: `SELECT b.capacity FROM java.nio.DirectByteBuffer b`) to confirm. Cross-check with `jcmd <pid> VM.native_memory summary` (with NMT on) and with `pmap`/RSS at the OS level.
**Fix — bound and explicitly release:** pool buffers (e.g. Netty's `PooledByteBufAllocator`) or free deterministically. Pre-JDK-9 you used the internal `Cleaner`; the supported modern way is to manage lifecycle and not retain references, or use a `MemorySegment` (JDK 21 FFM API) with an explicit `Arena`:

```java
// JDK 21+ Foreign Function & Memory API: deterministic off-heap lifecycle.
try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
    java.lang.foreign.MemorySegment seg = arena.allocate(size);
    // ... use seg ...
} // <-- native memory freed deterministically at close(), no GC dependence
```

### 5.5 Use case — classloader leak → `OutOfMemoryError: Metaspace`

```java
// In a servlet container: a web app registers a JDBC driver but never deregisters it.
// The DriverManager (loaded by the SYSTEM/parent classloader) keeps a strong ref to
// the driver instance, whose class was loaded by the WEB APP classloader.
// On undeploy, the webapp classloader cannot be collected -> ALL its classes leak.
public class LeakyServletContextListener implements ServletContextListener {
    @Override public void contextInitialized(ServletContextEvent e) {
        try { Class.forName("com.example.MyDriver"); } catch (Exception ignored) {}
        // ANTI-PATTERN: registered a driver, started threads, set ThreadLocals...
        // ...and cleans up NONE of it on shutdown.
    }
    @Override public void contextDestroyed(ServletContextEvent e) { /* nothing! */ }
}
```

**Symptom:** after a few redeploys, `OutOfMemoryError: Metaspace`. Each undeploy leaks an entire `WebappClassLoader` and its hundreds of classes.
**Diagnosis:** in MAT, group by classloader; you'll see *multiple* live `WebappClassLoader` instances when there should be one. Find the leaked classloader, "Path to GC Roots," and you'll see the keep-alive chain (DriverManager, a `ThreadLocal` in a pool thread, a JDBC `AbandonedConnectionCleanupThread`, a logging `MDC`, etc.). MAT's "Duplicate Classes" query and `jmap -clstats` also flag this.
**Fix — deregister/clean everything you registered in `contextDestroyed`:**

```java
@Override public void contextDestroyed(ServletContextEvent e) {
    // Deregister JDBC drivers loaded by THIS classloader.
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    Enumeration<Driver> drivers = DriverManager.getDrivers();
    while (drivers.hasMoreElements()) {
        Driver d = drivers.nextElement();
        if (d.getClass().getClassLoader() == cl) {
            try { DriverManager.deregisterDriver(d); } catch (SQLException ignored) {}
        }
    }
    // Also: shut down thread pools, cancel timers, clear ThreadLocals, stop driver
    // cleanup threads, remove shutdown hooks, deregister JMX MBeans, etc.
}
```

### 5.6 Use case — listener / observer (un-unregistered callback) leak

```java
public class PriceWidget {
    public PriceWidget(EventBus bus) {
        // Registers a listener; the bus now holds a STRONG ref back to this widget.
        bus.register(this::onPriceTick);   // captures `this`
        // If we never unregister, the bus keeps every PriceWidget ever created alive.
    }
    private void onPriceTick(PriceEvent ev) { /* update UI */ }
}
```

**Symptom:** widgets/handlers that should be short-lived accumulate; the `EventBus`'s listener list retains them all.
**Diagnosis:** MAT shows the `EventBus`'s internal listener `List`/`Set` with huge retained heap; the path-to-roots from a stale widget goes through the bus.
**Fix:** unregister symmetrically (and consider weak listeners):

```java
public class PriceWidget implements AutoCloseable {
    private final EventBus bus;
    private final Consumer<PriceEvent> handler = this::onPriceTick;
    public PriceWidget(EventBus bus) { this.bus = bus; bus.register(handler); }
    @Override public void close() { bus.unregister(handler); }  // symmetric cleanup
    private void onPriceTick(PriceEvent ev) { /* ... */ }
}
// Or have the bus store WeakReferences to listeners so forgetting to unregister
// degrades gracefully (caveat: lambdas may be collected unexpectedly — keep a field ref).
```

### 5.7 Use case — `unable to create new native thread` (thread leak)

```java
// ANTI-PATTERN: spinning up an unbounded number of threads.
public void handle(Request r) {
    new Thread(() -> process(r)).start();   // a NEW OS thread per request, never bounded
    // Each thread reserves ~512KB-1MB of native stack. Thousands of these exhaust
    // native memory (and OS ulimit -u / pids), throwing:
    //   java.lang.OutOfMemoryError: unable to create new native thread
}
```

**Symptom:** `unable to create new native thread`; `jstack` shows thousands of threads; `ps -eLf | grep java | wc -l` (or `cat /proc/<pid>/status | grep Threads`) confirms.
**Diagnosis:** thread *count* is the metric here, not heap. Check OS limits: `ulimit -u` (max user processes/threads), cgroup `pids.max`, and available native memory (threads consume native stacks, not heap). Reducing `-Xss` does *not* fix a true leak — it just delays it.
**Fix — use a bounded executor (or virtual threads for I/O):**

```java
// Bounded pool: backpressure instead of unbounded thread creation.
private static final ExecutorService POOL =
    new ThreadPoolExecutor(
        16, 16, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1000),                 // bounded queue
        new ThreadPoolExecutor.CallerRunsPolicy());      // backpressure when saturated

public void handle(Request r) { POOL.submit(() -> process(r)); }

// JDK 21+: for I/O-bound work, virtual threads scale to millions cheaply:
// try (var exec = Executors.newVirtualThreadPerTaskExecutor()) { exec.submit(...); }
```

### 5.8 Use case — capturing a heap dump non-disruptively + analyzing offline

```bash
# 1. Find the JVM pid
jcmd -l        # or: jps -l

# 2. Capture a LIVE heap dump (forces a full GC first -> only live objects, smaller file).
#    Modern, preferred command:
jcmd 12345 GC.heap_dump -gz=6 /var/dumps/app-$(date +%s).hprof.gz   # gz to save disk/transfer
#    Equivalent older tool:
jmap -dump:live,format=b,file=/var/dumps/app.hprof 12345

# 3. Quick triage WITHOUT a full dump: a class histogram (also forces full GC with :live)
jcmd 12345 GC.class_histogram | head -40
#    -> shows top classes by instance count & bytes; a leak often jumps out here.

# 4. If the process is HUNG/DEAD: dump from a core file
jhsdb jmap --binaryheap --dumpfile heap.hprof --exe $JAVA_HOME/bin/java --core core.12345

# 5. Analyze offline in MAT. For huge dumps, run MAT's headless parser to generate
#    the Leak Suspects report on a beefy box, then read the HTML:
./ParseHeapDump.sh /var/dumps/app.hprof \
  org.eclipse.mat.api:suspects \
  org.eclipse.mat.api:overview \
  org.eclipse.mat.api:top_components
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Heap dumps are STW and disk-heavy.** A 16 GB live heap = ~16 GB file written while the app is frozen; can take tens of seconds. On a load-balanced fleet, **drain/cordon the node first**, or dump from a canary that reproduces the leak. Use `-gz` compression with `jcmd GC.heap_dump` to cut file size/transfer time.
- **`-XX:+HeapDumpOnOutOfMemoryError` is nearly free** until it fires (then it's the expensive dump, but you're crashing anyway). **Always enable it in production.** Pair with `-XX:HeapDumpPath` pointing at a volume with enough free space (≥ `-Xmx`) — otherwise the dump fails and you lose the evidence.
- **`jmap -histo:live` / `GC.class_histogram` force a full GC.** Cheap data, real pause. Fine for triage, not for tight loops.
- **GC logging is cheap and invaluable** — enable `-Xlog:gc*` always. The "live set after full GC over time" line is your leak detector.
- **JFR (Java Flight Recorder) old-object sampling** (`-XX:StartFlightRecording=settings=profile`) can identify leaks with *low overhead in production*, recording where leaked objects were allocated and what keeps them alive — without a full heap dump.

### 6.2 Correctness & concurrency

- The leak you find in a dump must be reproduced under realistic *concurrency*; some leaks only manifest under contention (e.g. a `ConcurrentHashMap` resize path, or a race that double-registers listeners).
- **Heap dumps are consistent snapshots** (taken at a safepoint), so they don't suffer torn reads — but they're a single instant. Take **two dumps minutes apart** and diff (MAT can compare two snapshots) to see *what grew*, which is far more informative than one snapshot.

### 6.3 Memory sizing & containers

- **Never set `-Xmx` to the full container limit.** Leave headroom for Metaspace, thread stacks, code cache, direct buffers, GC structures, and native libs. A common starting split: heap ≤ 50–75% of the container limit. Use `-XX:MaxRAMPercentage=75` and verify RSS empirically.
- **Cap the off-heap pools explicitly in containers** (`-XX:MaxMetaspaceSize`, `-XX:MaxDirectMemorySize`, `-XX:ReservedCodeCacheSize`) so a leak in one pool throws a *clear* Java OOME (which you can dump) instead of getting the whole process **OOM-killed by the kernel** (exit 137, no Java-side evidence). A clean OOME with a heap dump beats a silent `OOMKilled` every time.

### 6.4 Security

- **Heap dumps contain everything in memory: passwords, tokens, PII, encryption keys, full request bodies.** Treat `.hprof` files as **secrets**. Encrypt at rest, restrict access, never commit to a repo, and be very cautious about uploading to third-party SaaS analyzers (GCeasy/heaphero) — that may breach data-handling policy. Scrub or analyze in a controlled environment.
- Restrict who can run `jcmd`/`jmap` against prod JVMs — these can leak data and cause pauses.

### 6.5 Observability (build the early-warning system)

- **Metrics:** export per-pool gauges via Micrometer/JMX: `jvm.memory.used{area=heap|nonheap,id=...}`, `jvm.gc.pause`, `jvm.gc.memory.allocated`, `jvm.classes.loaded`, `jvm.threads.live`, and **direct/mapped buffer pool** usage (`java.nio:type=BufferPool`). Alert on **heap-after-GC trend** and **rising loaded-class count** (Metaspace leak signal).
- **GC log analysis:** feed `gc.log` to GCeasy/your own parser; watch live-set growth.
- **Dashboards must show RSS alongside heap** — the gap between them is your off-heap budget and your off-heap-leak detector.
- **Synthetic soak tests:** run the app under steady load for hours in CI/staging and assert that heap-after-GC returns to baseline. This catches leaks before prod.

### 6.6 Testing & production hardening

- Write **leak regression tests** where feasible: force GCs and assert collection. Example using a `WeakReference` probe:

```java
@Test
void contextIsReleasedAfterRequest() {
    var probe = new java.lang.ref.WeakReference<>(handleAndReturnContext());
    System.gc();                       // best-effort; not guaranteed but practical here
    Awaitility.await().atMost(Duration.ofSeconds(5))
        .until(() -> probe.get() == null);   // if still non-null -> something leaks it
}
```

- **Fail fast on OOME** in services: `-XX:+ExitOnOutOfMemoryError` (or `+CrashOnOutOfMemoryError` to get an `hs_err` log) so the orchestrator restarts a clean process rather than limping in a corrupted, GC-thrashing state.
- **Cap every cache.** No unbounded `Map` caches; use Caffeine/Guava with `maximumSize`/`maximumWeight` + expiry.
- **Bound every thread pool and queue.** No unbounded `Executors.newCachedThreadPool()` in request paths.

### 6.7 Anti-patterns to avoid (quick list)

- Unbounded `static` collections (the #1 heap leak).
- `ThreadLocal.set()` without `remove()` in pooled threads.
- Forgetting `close()` (use try-with-resources for every `AutoCloseable`).
- Registering listeners/callbacks/shutdown-hooks/MBeans without symmetric unregistration.
- Caching with no eviction policy; "I'll add eviction later."
- `Executors.newCachedThreadPool()` / `new Thread().start()` per request.
- Setting `-Xmx` = container limit (no headroom → kernel OOM-kill).
- Catching `OutOfMemoryError` and continuing as if nothing happened.
- Using finalizers for cleanup (they're deprecated, unreliable, and *cause* leaks by delaying collection).
- Uploading raw prod heap dumps to public SaaS tools.

---

## 7. Advanced topics & deep internals

### 7.1 Reading the `hs_err_pid` crash log

When the JVM itself fails to get native memory (or crashes), it writes `hs_err_pid<pid>.log`. For native OOMs the top line reads something like `Out of Memory Error (...) failed to map ... bytes`. The log includes: the failing thread, all thread stacks, **heap and Metaspace summaries**, the **memory map**, environment, and command line. For `unable to create new native thread` you'll see the failing `pthread_create` and the thread count. This file is essential for off-heap and native crashes where no heap dump exists.

### 7.2 Native Memory Tracking deep dive

```bash
# Start with NMT on:  -XX:NativeMemoryTracking=summary  (or =detail, higher overhead)
jcmd 12345 VM.native_memory baseline                       # mark a baseline
# ... let the suspected leak run ...
jcmd 12345 VM.native_memory summary.diff                   # see which category GREW
```

Output buckets native memory by subsystem: **Java Heap**, **Class** (Metaspace), **Thread** (stacks), **Code** (JIT cache), **GC** (card tables/remembered sets), **Compiler**, **Internal**, **Symbol**, **Native Memory Tracking** (its own overhead), **Arena Chunk**, **Other**. If "Class" grows → classloader/Metaspace leak. If "Thread" grows → thread leak. If "Internal"/"Other" grows but none of the JVM buckets do, the leak is in a **third-party native library** (NMT can't see those — reach for `jemalloc`/`tcmalloc` profiling, `pmap`, or `valgrind`/ASan).

### 7.3 Diagnosing third-party native leaks with `jemalloc`

When the leak is in JNI/native code (NMT shows nothing growing but RSS climbs), swap the allocator and profile:

```bash
# Preload jemalloc with profiling enabled
export MALLOC_CONF=prof:true,prof_prefix:/tmp/jeprof,lg_prof_interval:30
export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libjemalloc.so
java -jar app.jar
# Later, render allocation-site flame graph from the dumped profiles:
jeprof --svg `which java` /tmp/jeprof.*.heap > nativeleak.svg
```

This reveals the *native call stack* allocating the leaked memory — the only way to nail leaks in things like compression codecs, crypto libs, or buggy JDBC native drivers.

### 7.4 G1/heap-region nuance: humongous allocations

**Term — humongous object (G1):** in the G1 collector, an object larger than half a region (`-XX:G1HeapRegionSize`) is "humongous" and allocated across contiguous old-gen regions, outside the normal Eden path. A flood of large arrays/byte buffers can cause **humongous allocation failures** and frequent full GCs even when total heap looks fine — heap *fragmentation*, not a classic leak. Symptom in GC logs: `to-space exhausted` / repeated humongous allocations. Fix: reduce large-object churn, increase region size, or stream instead of materializing.

### 7.5 The "soft reference cache that delays OOME" trap

A cache built on `SoftReference`s can mask a real leak: the GC keeps clearing soft refs to stay alive, so you see brutal GC pauses and near-100% heap usage *without* a clean OOME. Diagnosis: GC logs show soft refs being cleared aggressively (enable `-XX:+PrintReferenceGC` / `-Xlog:gc+ref=debug`). The fix is usually to replace soft-ref caching with a bounded cache (Caffeine) — soft refs are a blunt instrument and interact badly with low-pause collectors.

### 7.6 Off-heap accounting: `DirectByteBuffer` and `Cleaner`

A `DirectByteBuffer`'s native bytes are freed by a **`Cleaner`** (phantom-reference based) that runs *only after the wrapper object is GC'd*. So under low GC pressure, direct memory can pile up even with no leak — the wrappers haven't been collected yet. `-XX:MaxDirectMemorySize` enforcement (`Bits.reserveMemory`) will trigger a `System.gc()` and retry before throwing `Direct buffer memory`; if you've disabled explicit GC with `-XX:+DisableExplicitGC`, you can get spurious direct-memory OOMEs. Watch `java.nio:type=BufferPool,name=direct` via JMX for `MemoryUsed`/`Count`.

### 7.7 Compressed class space

Within Metaspace there's a separate **Compressed Class Space** (default 1 GB, `-XX:CompressedClassSpaceSize`) holding compressed `Klass` pointers when **compressed oops/klass pointers** are on (the default below the 32 GB heap threshold). You can hit `OutOfMemoryError: Compressed class space` *before* general Metaspace if you load a huge number of classes — same classloader-leak root cause, slightly different message.

### 7.8 JFR old-object sampling for low-overhead leak hunting

```bash
# Continuous, low-overhead recording with leak-relevant events:
java -XX:StartFlightRecording=name=leak,settings=profile,maxage=6h,maxsize=500m \
     -XX:FlightRecorderOptions=stackdepth=128 -jar app.jar
# Dump and inspect:
jcmd 12345 JFR.dump name=leak filename=/tmp/leak.jfr
```

Open `leak.jfr` in JMC → "Live Objects"/"Old Object Sample" → it shows objects that **survived long enough to look like leaks**, *their allocation stack trace*, and *their path to GC root*. This gives you both halves of the puzzle (what allocated it *and* what keeps it alive) without an STW heap dump — ideal for production.

### 7.9 32-bit address-space limits & compressed oops boundary

On 32-bit JVMs, max heap is ~2–4 GB regardless of RAM — you OOME at the address-space ceiling. On 64-bit, crossing **~32 GB heap** disables **compressed oops** (32-bit object pointers), so pointers become 8 bytes and effective capacity *drops* — a 31 GB heap can hold more live objects than a 33 GB one. Keep heaps under the compressed-oops threshold (use `-XX:+PrintFlagsFinal | grep UseCompressedOops` to confirm) where possible.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Which capture/analysis tool when

| Situation | Best tool | Why |
|---|---|---|
| Process already OOME'd | `-XX:+HeapDumpOnOutOfMemoryError` dump + MAT | Captures the bloated heap automatically |
| Live triage, "what's filling the heap?" | `jcmd GC.class_histogram` / `jmap -histo:live` | Fast, no full dump; top classes |
| Deep leak path analysis | Eclipse MAT (dominator tree, path-to-roots) | Retained heap + GC-root paths |
| Low-overhead prod leak hunt | JFR old-object sampling + JMC | No STW dump; alloc site + keep-alive path |
| Off-heap / RSS climbing | NMT (`jcmd VM.native_memory`) + `pmap` | Buckets native memory; finds the pool |
| Third-party native leak | `jemalloc`/`tcmalloc` profiling | Sees C-side allocation stacks |
| Thread leak | `jstack` + `/proc/<pid>/status` + OS limits | Count & state of threads |
| Hung/dead JVM | `jhsdb` on a core file | Post-mortem from core dump |

### 8.2 Bounding strategy: cache eviction policies

| Policy | Use when | Avoid when |
|---|---|---|
| `maximumSize` (LRU/W-TinyLFU) | You can cap entry count; uniform entry cost | Entries vary wildly in size |
| `maximumWeight` | Entry sizes vary a lot | You can't compute weights cheaply |
| `expireAfterWrite` | Data has a freshness TTL | Hot entries should stay regardless of age |
| `expireAfterAccess` | Idle entries should drop | You want absolute freshness bounds |
| `SoftReference` values | Truly opportunistic cache, last resort | Low-pause GC; you need predictable latency |
| `WeakReference` (canonicalizing) | Cache should follow key lifecycle | You need entries to persist independently |

**Rule:** prefer a *bounded* policy (`maximumSize`/`maximumWeight` + expiry) over reference-based caches in latency-sensitive services.

### 8.3 OOME response strategy

| Strategy | Use when | Avoid when |
|---|---|---|
| `-XX:+ExitOnOutOfMemoryError` (fail fast) | Stateless services behind an orchestrator | A restart loses irreplaceable in-memory state |
| `-XX:+HeapDumpOnOutOfMemoryError` (capture) | Always (with disk headroom) | Disk can't hold a dump (then it just fails) |
| Catch + degrade gracefully | A *single* bounded operation can fail safely | General code — JVM state is unreliable post-OOME |
| Raise `-Xmx` | Verified high *legitimate* usage (flat live set) | Live set trends up → it's a leak; this only delays |

### 8.4 Leak signature → likely cause cheat

| Signature | Likely cause |
|---|---|
| Heap-after-GC climbs linearly | Heap leak (static collection, cache, ThreadLocal) |
| `Metaspace` OOME after redeploys / many classes | Classloader leak; dynamic class generation |
| RSS climbs, heap-after-GC flat | Off-heap: direct buffers, mapped files, native lib |
| `unable to create new native thread`, huge thread count | Thread leak; OS ulimit/pids limit |
| Long GC pauses, ~100% heap, no clean OOME | Near-leak / soft-ref cache / GC overhead spiral |
| Single huge allocation OOMEs, otherwise healthy | Transient spike (unpaged query, big upload) |

---

## 9. Failure modes & debugging

### 9.1 The step-by-step investigation playbook

**Phase 0 — Always-on prep (do this *before* the incident):**
`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/dumps -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/var/log/gc.log -XX:NativeMemoryTracking=summary`, plus Micrometer JVM metrics and RSS-vs-heap dashboards.

**Phase 1 — Read the message.** Get the exact OOME string from logs. Which pool? (Heap / Metaspace / Direct buffer / native thread / native.) This alone routes the rest of the investigation.

**Phase 2 — Classify the pattern.** Look at GC logs / metrics: is **heap-after-full-GC** climbing (leak) or flat (sizing/spike)? Is RSS climbing while heap is flat (off-heap)? Is thread count climbing (thread leak)? Is loaded-class count climbing (Metaspace)?

**Phase 3 — Capture evidence.**
- Heap/Metaspace leak → heap dump (the on-OOME dump, or `jcmd GC.heap_dump` from a reproducing node). Take **two dumps minutes apart** for diffing if you can.
- Off-heap → `jcmd VM.native_memory baseline` then `summary.diff`; `pmap -x <pid>`; check `BufferPool` JMX.
- Thread → `jstack <pid>`, `/proc/<pid>/status` (Threads:), `ulimit -u`, cgroup `pids.max`.
- Native third-party → `jemalloc` profiling.

**Phase 4 — Analyze.**
- MAT: open dump → **Leak Suspects report** first. Then **dominator tree**, sort by retained heap; the top entry is usually the accumulator. **Path to GC Roots (exclude soft/weak)** on it to find the keep-alive chain. Confirm with **histogram** (instance counts). Use **OQL** for targeted questions ("how many entries in that map?"). For two dumps, **compare snapshots** to see what grew.
- JFR: open `.jfr` in JMC → Old Object Sample → allocation stack + GC-root path.

**Phase 5 — Root-cause & fix.** Identify the exact field/collection/registration holding the references. Apply the structural fix (bound the cache, `remove()` the ThreadLocal, close the resource, unregister the listener, deregister the driver, bound the pool).

**Phase 6 — Verify & guardrail.** Reproduce under soak load; assert heap-after-GC returns to baseline. Add a metric alert on the relevant gauge (heap trend, class count, thread count, buffer pool). Add a regression test where feasible.

### 9.2 Real-world incident patterns

- **The Tomcat redeploy death (Metaspace):** a web app started a background thread / set a `ThreadLocal` / registered a JDBC driver and never cleaned up. After ~5–10 redeploys, `OutOfMemoryError: Metaspace`. MAT showed N live `WebappClassLoader`s. Fix: full cleanup in `contextDestroyed`; modern containers (Tomcat) ship "memory leak prevention/detection" listeners that *warn* about exactly these (un-deregistered drivers, lingering ThreadLocals, RMI/JDBC threads).
- **The log-context leak:** an MDC/`ThreadLocal` holding a per-request context (with attached objects) never cleared on pooled threads; slow heap creep over days. Fix: clear MDC in a servlet filter `finally`.
- **The Netty direct-buffer leak:** un-released `ByteBuf`s (missing `release()` / wrong ref-counting). RSS climbed, heap flat, then `Direct buffer memory`. Netty's **leak detector** (`-Dio.netty.leakDetection.level=paranoid`) prints the allocation stack of leaked buffers — turn it up in staging.
- **The unbounded cache "works in dev":** an in-memory `HashMap` cache with no eviction grew fine under low dev cardinality, OOME'd in prod where key cardinality was 1000×. Fix: Caffeine with `maximumSize`.
- **The connection-pool leak:** exceptions skipped `close()`; pool exhausted, driver buffers accumulated. Fix: try-with-resources everywhere; enable pool **leak detection** (HikariCP `leakDetectionThreshold`).

### 9.3 Diagnostic command crib

```bash
jcmd <pid> VM.info                         # full VM state, flags, memory summary
jstat -gcutil <pid> 1000                   # live GC %: E S0 S1 O M(metaspace) CCS YGC FGC GCT
jstat -gccause <pid> 1000                  # + reason for last/current GC
jcmd <pid> GC.class_histogram | head -30   # top classes by bytes/count (forces full GC)
jcmd <pid> VM.native_memory summary        # native pools (NMT must be on)
jcmd <pid> GC.heap_dump -gz=6 /tmp/h.hprof.gz   # compressed heap dump
jstack -l <pid> > threads.txt              # thread dump + lock info
cat /proc/<pid>/status | egrep 'VmRSS|Threads'  # RSS & thread count
pmap -x <pid> | sort -k3 -n | tail         # largest native mappings
jmap -clstats <pid>                        # classloader statistics (loaded class counts)
```

---

## 10. Interview drill

**Q1. What's the difference between a memory leak in Java and in C/C++?**
*Model answer:* In C/C++ a leak is memory you `malloc`'d and never `free`'d — the pointer is lost. In Java the GC reclaims any *unreachable* object, so a "leak" is the opposite problem: objects that are **still reachable** from a GC root but will never be used again. The GC sees them as live and can't collect them. So Java leaks are *logical* — you forgot to drop a reference.
- *Follow-up: What's a GC root?* A starting point the GC traces from: thread-stack locals, static fields, JNI references, active threads, held monitors. Anything reachable from a root is live.
- *Follow-up: Why doesn't the GC just collect "unused" objects?* It can't know intent; it only knows reachability. If you hold a reference, it's reachable, full stop.
- *Follow-up: Give the canonical example.* A `static` collection things get added to but never removed from.

**Q2. Enumerate the `OutOfMemoryError` variants and what each means.**
*Model answer:* `Java heap space` (heap full, GC can't free room); `GC overhead limit exceeded` (>98% time in GC, <2% reclaimed — heap-pressure guard); `Metaspace` / `Compressed class space` (class-metadata native memory exhausted — usually a classloader leak); `Direct buffer memory` (NIO direct buffers exceed `MaxDirectMemorySize`); `unable to create new native thread` (OS/native can't make another thread — thread leak or ulimit); `Requested array size exceeds VM limit` (absurd array length); plus native OOMs (`Out of swap space?`, hs_err) when the JVM's own `malloc`/`mmap` fails.
- *Follow-up: Which are NOT about the Java heap?* Metaspace, Direct buffer memory, native thread, and native/swap — those are off-heap; a heap dump won't directly show them.
- *Follow-up: Why is the stack trace on a heap OOME usually useless?* It's thrown at the allocation that happened to fail, not at the code that filled the heap. You need a heap dump.

**Q3. Walk me through diagnosing `OutOfMemoryError: Java heap space` in production.**
*Model answer:* Confirm the message and that `-XX:+HeapDumpOnOutOfMemoryError` captured a dump (or capture one from a reproducing node). Check GC logs: is heap-after-full-GC trending up (leak) or just high (sizing)? Open the dump in MAT → Leak Suspects → dominator tree sorted by retained heap → the top accumulator → "Path to GC Roots (exclude soft/weak)" to find the keep-alive chain → fix the structural cause → soak-test and add a metric alert.
- *Follow-up: What's retained heap vs shallow heap?* Shallow = the object's own memory; retained = everything freed if it were collected (the object plus all it exclusively keeps alive). Retained heap finds leaks.
- *Follow-up: What's a dominator tree?* A graph transform where A dominates B if every root→B path goes through A; it makes the biggest retained-set holders bubble to the top.
- *Follow-up: How do you tell a leak from legitimate high usage?* Heap occupancy *after a full GC* over time — climbing forever = leak; flat = not.

**Q4. RSS keeps growing but heap-after-GC is flat. What's happening and how do you diagnose it?**
*Model answer:* It's an **off-heap** growth: direct `ByteBuffer`s/mapped files, thread stacks, Metaspace, code cache, or a native library. A heap dump won't show the native bytes. Enable NMT (`-XX:NativeMemoryTracking=summary`), take a baseline, and `summary.diff` to see which JVM bucket grows. If it's "Class" → Metaspace/classloader leak; "Thread" → thread leak; if no JVM bucket grows, it's a third-party native lib — profile with `jemalloc`. Cross-check with `pmap` and the `BufferPool` JMX bean.
- *Follow-up: Why might direct memory pile up without a leak?* Direct buffers are freed by a `Cleaner` only after the small wrapper object is GC'd; under low GC pressure wrappers linger.
- *Follow-up: How do you bound direct memory?* `-XX:MaxDirectMemorySize`; pool buffers (Netty pooled allocator); or use the FFM `Arena` API for deterministic free.

**Q5. Explain classloader leaks and why they cause Metaspace OOMEs.**
*Model answer:* Class metadata lives in Metaspace and is freed only when its classloader becomes unreachable. A classloader is reachable while any class it loaded is reachable, and a class is reachable while any instance or static field of it is. So one leaked instance pins its class, which pins the classloader, which pins *every* class that loader loaded — Metaspace never shrinks. Classic in app servers that redeploy: each undeploy leaks a whole `WebappClassLoader`.
- *Follow-up: Common keep-alive culprits?* Un-deregistered JDBC drivers (held by `DriverManager` in the parent loader), `ThreadLocal`s on pooled threads, background threads, JMX MBeans, shutdown hooks.
- *Follow-up: How do you find it in MAT?* Group by classloader, find duplicate live `WebappClassLoader`s, path-to-roots on the leaked one.

**Q6. (Senior-signal) You're told to "just bump `-Xmx`." When is that right and when is it wrong?**
*Model answer:* Right only when the **live set after full GC is stable but legitimately high** — you genuinely need to hold that much (e.g. a correctly-sized cache) — and you have RAM headroom for the other pools. Wrong when the live set is **trending up** (a leak): more heap only delays the OOME and lengthens GC pauses. Also wrong in containers if it eats the headroom needed by Metaspace/stacks/direct buffers, inviting a kernel OOM-kill. The discriminator is the heap-after-GC trend, not the peak.
- *Follow-up: Downsides of a too-large heap even with no leak?* Longer full-GC pauses, crossing the ~32 GB compressed-oops cliff (pointers double in size), worse cache locality, and bigger heap dumps.
- *Follow-up: How would you size it instead?* Measure live set under realistic load, add ~25–50% headroom, set `-Xms=-Xmx`, leave 25–40% of container for off-heap, verify RSS.

**Q7. (Senior-signal) Design the production memory-observability and OOM-response strategy for a fleet of stateless services.**
*Model answer:* Always-on: `-XX:+HeapDumpOnOutOfMemoryError` to a sized volume, `-XX:+ExitOnOutOfMemoryError` for fail-fast-restart, GC logging, NMT summary, and explicit caps on off-heap pools so leaks throw clean Java OOMEs instead of kernel OOM-kills. Metrics: heap/non-heap usage, GC pause, allocation rate, loaded-class count, live threads, buffer pools, and RSS-vs-heap. Alert on *trends* (heap-after-GC, class count, thread count). On OOME, the orchestrator restarts a clean process and the captured dump goes to a secure store for offline MAT analysis. Soak tests in CI guard against regressions.
- *Follow-up: Why fail fast instead of catching OOME?* Post-OOME the JVM is in an unreliable, GC-thrashing state; a clean restart is faster and safer than limping.
- *Follow-up: Security concern with dumps?* They contain secrets/PII — encrypt, restrict, don't ship to public SaaS analyzers.

**Q8. (Senior-signal) A service shows long GC pauses and ~100% heap usage but never throws an OOME. What's likely, and is `GC overhead limit exceeded` involved?**
*Model answer:* Likely a near-leak or a `SoftReference`-based cache that the GC keeps clearing to stay alive, plus possibly high allocation rate. The collector spends most of its time reclaiming just enough to avoid OOME, so latency tanks. `GC overhead limit exceeded` is exactly the guard for this (>98% time in GC, <2% reclaimed) and *would* fire if it gets bad enough — but soft-ref clearing can keep it just under the threshold indefinitely. Diagnose with GC logs (`-Xlog:gc+ref=debug` to see soft-ref clearing) and a heap dump; fix by replacing soft-ref caches with bounded caches and reducing allocation churn.
- *Follow-up: What thresholds control the guard?* `-XX:GCTimeLimit=98`, `-XX:GCHeapFreeLimit=2`; disable via `-XX:-UseGCOverheadLimit` (rarely advisable).
- *Follow-up: Why are soft refs problematic with low-pause GCs?* They force extra work and unpredictable clearing, undermining the latency guarantees those collectors aim for.

**Q9. What's a `ThreadLocal` leak and why is the thread pool the aggravating factor?**
*Model answer:* A `ThreadLocal` value is stored in the *Thread*'s `ThreadLocalMap`. In a pool, threads never die and are reused, so a value set during one request persists across unrelated requests until overwritten or removed. The map's *keys* are weak (the `ThreadLocal` object), but *values* are strong, so stale values can linger even after the `ThreadLocal` is collected. Fix: always `remove()` in a `finally`.
- *Follow-up: How does this become a Metaspace leak?* If the value transitively references a webapp classloader, it pins that loader → classloader leak.
- *Follow-up: How do you spot it in a dump?* Many live `Thread`s each retaining a `ThreadLocalMap$Entry[]` pointing at your object.

**Q10. How do you capture a heap dump without taking down a latency-sensitive prod node?**
*Model answer:* Drain/cordon the node from the load balancer first (or use a canary that reproduces the leak), then `jcmd <pid> GC.heap_dump -gz=6 <file>` — accepting the STW pause while it's out of rotation. Ensure the target volume has ≥ live-heap free space. For low-overhead *online* leak hunting, prefer JFR old-object sampling instead of an STW dump. Always rely on the automatic on-OOME dump for the crash case.
- *Follow-up: Why `:live`?* It forces a full GC first so the dump contains only live objects — smaller and cleaner.
- *Follow-up: Dump of a hung/dead JVM?* Use `jhsdb jmap --binaryheap` against a core file.

**Q11. Distinguish leak vs spike vs GC-pressure in one sentence each, with the metric that separates them.**
*Model answer:* Leak = heap-after-full-GC trends up (metric: live set over time). Spike = one big allocation OOMEs while heap-after-GC is fine (metric: allocation size of the failing op). GC-pressure = live set fits but allocation rate outpaces collection, pauses grow (metric: GC time % and allocation rate). The separating metric in all cases anchors on *heap occupancy after full GC*.
- *Follow-up: Tool for allocation rate?* `jstat -gcutil`, GC logs, or async-profiler/JFR allocation flame graphs.

**Q12. What evidence does a heap dump NOT contain, and how do you cover those gaps?**
*Model answer:* It doesn't contain native/off-heap bytes (direct buffers' native memory, Metaspace contents beyond class structure, thread stack memory, native-lib allocations) — only on-heap Java objects and references. Cover the gaps with NMT (JVM native pools), `pmap`/RSS (OS view), `BufferPool` JMX (direct buffers), `jstack`/`/proc` (threads), and `jemalloc` profiling (third-party native). It's also a single instant — diff two dumps to see growth.
- *Follow-up: How do you size direct-buffer usage from a dump anyway?* OQL-sum the `capacity` of reachable `DirectByteBuffer` wrappers — that approximates the native bytes held.

---

## 11. Glossary

- **Allocation rate:** bytes of new objects created per second; high rates drive GC frequency.
- **Card table / remembered set:** GC bookkeeping structures (native memory) tracking cross-region/cross-generation references so collections don't have to scan the whole heap.
- **Cleaner:** a phantom-reference-based mechanism that runs cleanup (e.g. freeing native memory) *after* an object is unreachable; used for `DirectByteBuffer`.
- **Compressed oops / klass pointers:** 32-bit encodings of object/class pointers used below ~32 GB heap to save memory; disabled above that boundary.
- **Compressed Class Space:** a sub-region of Metaspace (default 1 GB) holding compressed klass pointers.
- **Dominator tree:** graph transform where A dominates B if all root→B paths cross A; makes retained sets easy to read.
- **Direct buffer:** off-heap memory allocated via `ByteBuffer.allocateDirect`; bytes live in native memory, not the Java heap.
- **Eden:** young-generation nursery where new objects are first allocated.
- **`Error`:** a `Throwable` subclass for serious problems (like OOME) you generally shouldn't catch/recover from.
- **Full GC (major GC):** a stop-the-world collection of the entire heap.
- **GC (Garbage Collector):** subsystem that reclaims unreachable objects automatically.
- **GC root:** a starting point for reachability tracing (stack locals, static fields, JNI refs, active threads, monitors).
- **`GC overhead limit exceeded`:** guard OOME when the JVM spends >98% of time in GC reclaiming <2% of heap.
- **Heap:** GC-managed region holding all Java objects.
- **Heap dump:** binary `.hprof` snapshot of all heap objects, fields, and references at a safepoint.
- **HPROF:** the standard binary heap-dump/profiling format.
- **`hs_err_pid<pid>.log`:** the JVM's fatal-error log written on a crash or native OOM.
- **Humongous object (G1):** an object larger than half a G1 region, allocated specially across contiguous old regions.
- **JFR (Java Flight Recorder):** low-overhead, built-in event recorder; supports old-object sampling for leak hunts.
- **JMC (JDK Mission Control):** GUI for analyzing JFR recordings.
- **JNI (Java Native Interface):** the bridge between Java and native (C/C++) code; native code can hold Java objects as GC roots.
- **`jcmd` / `jmap` / `jstack` / `jstat` / `jhsdb` / `jinfo`:** JDK diagnostic CLIs (commands sender / heap & histogram / thread dump / GC stats / serviceability post-mortem / flag reader).
- **`Klass`:** internal JVM structure describing a loaded class; lives in Metaspace.
- **MAT (Eclipse Memory Analyzer Tool):** the standard offline heap-dump analyzer.
- **Metaspace:** native-memory region holding class metadata since Java 8 (replaced PermGen).
- **Mutator:** application thread (mutates the object graph), vs. GC threads.
- **NMT (Native Memory Tracking):** JVM feature bucketing its *own* native allocations by subsystem.
- **OOM-kill (`OOMKilled`, exit 137):** the kernel/orchestrator killing a process for exceeding its memory limit — no Java-side OOME.
- **Old generation (tenured):** heap region for long-lived objects promoted from young gen.
- **`OutOfMemoryError` (OOME):** error thrown when an allocation fails and GC can't make room.
- **PermGen:** pre-Java-8 fixed-size class-metadata region; replaced by Metaspace.
- **Phantom reference:** weakest reference strength; enqueued after finalization for post-mortem cleanup.
- **Promotion / tenuring:** moving objects that survive enough young-GC cycles into old gen.
- **Reachability:** whether a reference chain exists from a GC root to an object.
- **Referent:** the object a (soft/weak/phantom) reference points at.
- **Retained heap:** total memory freed if an object were collected (it plus all it exclusively keeps alive).
- **RSS (Resident Set Size):** physical RAM the OS reports the process uses; the figure containers limit against.
- **Safepoint:** an execution point where all threads can pause with fully described stacks for safe GC/inspection.
- **Shallow heap:** an object's own memory (header + its fields), excluding referenced objects.
- **Soft reference:** reference the GC may clear under memory pressure; for memory-sensitive caches.
- **Stop-the-world (STW):** a pause where all mutator threads halt (for GC or heap dump).
- **Survivor space:** young-gen region holding objects that survived a young GC, between Eden and promotion.
- **TLAB (Thread-Local Allocation Buffer):** a thread-private slice of Eden enabling lock-free bump-pointer allocation.
- **Weak reference:** reference cleared as soon as no strong references remain (used by `WeakHashMap`).
- **Young generation:** heap region (Eden + survivors) where most objects are born and die.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**OOME variants → meaning**
- `Java heap space` → heap full + GC can't free (leak / undersized / giant alloc)
- `GC overhead limit exceeded` → >98% time in GC, <2% reclaimed (heap pressure)
- `Metaspace` / `Compressed class space` → class metadata full (classloader leak / class generation)
- `Direct buffer memory` → NIO direct buffers > `MaxDirectMemorySize`
- `unable to create new native thread` → OS/native can't make a thread (thread leak / ulimit)
- native / `Out of swap space?` → JVM's own malloc/mmap failed (off-heap / container limit)

**The leak detector:** heap occupancy **after full GC**, over time. Climbs → leak. Flat → not.

**RSS ≈** heap + Metaspace + thread stacks + code cache + direct buffers + GC structs + native libs. **Never set `-Xmx` = container limit.**

**Always-on prod flags:** `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=… -XX:+ExitOnOutOfMemoryError -Xlog:gc* -XX:NativeMemoryTracking=summary` + explicit caps on Metaspace/Direct/CodeCache.

**Capture:** `jcmd <pid> GC.heap_dump -gz=6 f.hprof.gz` (live, STW) · `jcmd <pid> GC.class_histogram` (triage) · `jcmd <pid> VM.native_memory summary[.diff]` (off-heap) · `jstack` (threads) · `jhsdb` (dead JVM/core).

**MAT workflow:** Leak Suspects → dominator tree (sort by retained heap) → top accumulator → Path to GC Roots (exclude soft/weak) → break the chain. Diff two dumps to see growth.

**Top fixes:** bound caches (Caffeine `maximumSize` + expiry) · `ThreadLocal.remove()` in `finally` · try-with-resources for every `AutoCloseable` · unregister listeners/drivers/MBeans/hooks symmetrically · bounded thread pools/queues · free direct memory (pool / FFM `Arena`).

**Key numbers/defaults:** GC-overhead guard 98% / 2%; compressed-oops cliff ~32 GB heap; Compressed Class Space default 1 GB; code cache default 240 MB (tiered); `MaxRAMPercentage` default 25%; `-Xss` ~512 KB–1 MB.

**Off-heap reality:** a heap dump shows the small `DirectByteBuffer` wrappers, not their native bytes — use NMT + `pmap` + `BufferPool` JMX. Heap dumps contain secrets/PII — treat as confidential.

### 12.2 Self-test (no answers — recall practice)

1. You see `OutOfMemoryError: Metaspace` only after several application redeploys. Explain the full reference chain that makes this happen and name three concrete keep-alive culprits.
2. Your container is getting `OOMKilled` (exit 137) but the JVM never logs an OOME. What's the likely cause, and what flags would you add so you get a clean Java OOME plus a heap dump instead?
3. Given a 12 GB heap dump and a host with 8 GB RAM, how do you analyze it in MAT, and what does "retained heap" tell you that "shallow heap" does not?
4. RSS has grown from 2 GB to 6 GB over a week while heap-after-GC has stayed at ~1.5 GB. List the off-heap pools you'd investigate, the exact commands you'd run, and how you'd distinguish a JVM-internal leak from a third-party native-library leak.
5. Write (from memory) the correct `ThreadLocal` usage pattern for a pooled-thread web request, and explain precisely why omitting one line causes a leak that a heap dump would reveal as `Thread → ThreadLocalMap$Entry[] → yourObject`.
6. Distinguish, with the single discriminating metric for each, a true heap leak vs. legitimate high usage vs. a transient allocation spike vs. GC pressure — and state the correct remediation for each.
7. Your service shows ~100% heap usage and multi-second GC pauses but has not thrown an OOME in days. Form a hypothesis involving reference strengths, name the GC log flag that would confirm it, and give the fix.

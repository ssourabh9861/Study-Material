# GC Tuning & Reading GC Logs

> JVM Internals & Garbage Collection — a definitive engineering-handbook chapter.

---

## 1. Overview & where it fits

**Garbage collection (GC)** is the JVM subsystem that automatically reclaims heap memory that your program is no longer using. You allocate objects with `new`; you never explicitly `free` them. Instead a background machinery decides which objects are still **reachable** (transitively referenced from a set of *GC roots* — live thread stacks, static fields, JNI handles, etc.) and which are **garbage** (unreachable), then recycles the garbage's memory. *Garbage collection* in the JVM means *automatic memory reclamation*; the collector is the component that does it.

**The problem GC solves vs. the problem GC creates.** GC solves manual-memory-management bugs: use-after-free, double-free, leaks from forgotten `free()`. The price is that reclamation costs CPU and, crucially, sometimes requires **pausing your application threads** so the collector can move or scan objects safely. Those pauses — historically called **stop-the-world (STW)** pauses — are the dominant operational concern. A *stop-the-world pause* is an interval during which every application (a.k.a. **mutator**) thread is frozen at a **safepoint** while the collector works. The word *mutator* is GC jargon for "the application," because from the collector's point of view your code is the thing that *mutates* the object graph.

**GC tuning** is the discipline of configuring the collector — heap size, generation sizes, pause-time goals, the collector algorithm itself, and dozens of flags — so that the application meets its **latency** (pause) and **throughput** (useful-work fraction) requirements at acceptable **footprint** (memory) and CPU cost. **Reading GC logs** is the primary, ground-truth observability tool for that discipline: the log is the collector narrating, in microsecond detail, every cycle it runs, why, how long it paused, and how much it reclaimed.

**When you reach for this.** You reach for GC tuning and log reading when:
- A service has **latency SLOs** (e.g. p99 < 50 ms) and you see periodic spikes that correlate with GC.
- A service throws `OutOfMemoryError` or restarts under load and you need to know whether it's a leak, undersized heap, or fragmentation.
- You're sizing containers/pods and need to pick `-Xmx`, a collector, and CPU shares that won't get the pod OOM-killed.
- You're doing capacity planning and need allocation/promotion rates to predict behavior at 2× traffic.
- You're choosing between G1, ZGC, Shenandoah, Parallel, or Serial for a new workload.

**One-paragraph mental model.** Think of the heap as a workshop bench. Your program continuously makes objects (allocation) and abandons most of them almost immediately (the **weak generational hypothesis**: most objects die young). The collector periodically sweeps the bench. A cheap **minor/young collection** sweeps only the small "new arrivals" area very often and very fast, moving the few survivors aside. Expensive **major/old/full collections** sweep the whole bench and are rare but can be slow. Tuning is the act of sizing the areas and choosing the sweeping strategy so that sweeps are frequent-but-tiny (low latency) or rare-but-thorough (high throughput) — whichever your SLO needs — without running out of bench space. The GC log is the bench's logbook: every sweep, timestamped, with before/after measurements. You tune by reading the log, forming a hypothesis, changing one knob, and re-reading.

A non-negotiable framing for the rest of this chapter: **you cannot tune what you cannot measure, and the GC log is the measurement.** Most "GC problems" dissolve once the log is read correctly. Most GC *tuning disasters* come from changing flags before reading the log. So we treat log literacy as the core skill and flags as the secondary one.

---

## 2. Foundations from first principles

### 2.1 The heap, generations, and the generational hypothesis

The **heap** is the region of JVM-managed memory where objects live. (Contrast with the **stack**, which holds per-thread frames and local primitives/references, and **Metaspace**, which holds class metadata — neither is collected by the object GC the way the heap is.) Modern mainstream collectors are **generational**: they physically or logically divide the heap by object *age*.

- **Young generation (a.k.a. new generation, nursery):** where new objects are allocated. Subdivided (in HotSpot's classic layout) into one **Eden** space (where allocation happens) and two **Survivor** spaces (S0/S1, a.k.a. *from*/*to*). A *survivor space* holds objects that survived at least one young collection but aren't old enough to be promoted yet.
- **Old generation (a.k.a. tenured generation):** where long-lived objects end up after surviving enough young collections.
- **Metaspace (Java 8+):** off-heap native memory storing class metadata (the old "PermGen" was removed in Java 8). It's GC-managed but separate; we'll touch it because it has its own OOM and its own collection triggers.

The **weak generational hypothesis** is the empirical observation that *most objects die young* — they become unreachable shortly after allocation (think: the temporary objects in a request handler). A corollary, the **weak strong-generational / inter-generational hypothesis**, is that *references from old objects to young objects are rare*. These two facts justify the whole design: if most garbage is young, collect the young area often and cheaply, and only occasionally pay for the whole heap.

### 2.2 Minor, major, mixed, and full collections — define each precisely

These words are overloaded and people misuse them. Precise definitions for HotSpot:

- **Minor GC (young collection):** collects *only* the young generation. Always **stop-the-world** in mainstream HotSpot collectors (even the "concurrent" ones do young collection STW). Fast and frequent. Survivors are moved (copied/evacuated) into a survivor space or promoted to old.
- **Major GC:** an overloaded term — usually means a collection of the **old generation**. Often used loosely as a synonym for "full GC," but technically a major collection might be concurrent and partial.
- **Full GC:** collects the **entire heap** (young + old) and usually compacts it, almost always **stop-the-world**, and in G1/CMS it's a *fallback* path that signals the concurrent machinery failed to keep up. **Full GCs on G1/ZGC/Shenandoah are usually a bug or misconfiguration, not normal operation.**
- **Mixed GC (G1-specific):** collects all of young plus a *subset* of old regions chosen for high garbage density ("garbage first"). This is how G1 reclaims old-gen space incrementally instead of doing a full GC.
- **Concurrent cycle / concurrent marking:** phases that run *alongside* the mutator (mostly not STW), used by G1/CMS/ZGC/Shenandoah to find live old-gen objects without a long pause.

### 2.3 Allocation, the TLAB, and why young collection is cheap

When you write `new Foo()`, the JVM allocates in **Eden**. To avoid threads contending on a global allocation pointer, each thread gets a **Thread-Local Allocation Buffer (TLAB)** — a private chunk of Eden it bump-allocates into with no synchronization. A *TLAB* is a per-thread slice of Eden; allocation is just "increment a pointer," which is why object creation is nearly free. When a thread's TLAB fills, it grabs another (a lightweight operation); when Eden as a whole fills, a **minor GC** is triggered.

Young collection is cheap because of **copying/evacuation**: the collector finds the *live* objects in Eden+one survivor (usually a small fraction, by the generational hypothesis), copies them to the other survivor (or to old gen if old enough), and then declares the entire source region empty in O(1). The cost is proportional to the *surviving* (live) data, **not** to the amount of garbage — so a young gen full of dead objects collects almost for free. This is the single most important performance fact in GC.

### 2.4 Promotion, tenuring, and the survivor dance

Each object carries an **age** (number of young collections survived). On each minor GC, survivors are copied from Eden + the "from" survivor into the "to" survivor, ages incremented. When an object's age reaches the **tenuring threshold** (`-XX:MaxTenuringThreshold`, default commonly 15 for G1/Parallel, capped at 15 because age is stored in 4 bits of the object header's mark word), it is **promoted** (tenured) into old gen. The JVM also computes a **dynamic tenuring threshold** per GC so that survivor space is no more than `TargetSurvivorRatio` (default 50%) full — if survivors overflow, objects are promoted early ("**premature promotion**"), which pollutes old gen and triggers more expensive old/full collections. **Premature promotion** is one of the most common and most damaging tuning problems; you diagnose it by reading tenuring distributions in the log.

### 2.5 Throughput vs. latency vs. footprint — the GC trilemma

Three goals, you can't max all three:

- **Throughput:** fraction of wall-clock time spent doing application work rather than GC. Throughput-oriented collectors (Parallel GC) accept longer, rarer pauses to minimize total GC CPU.
- **Latency (pause time):** length of individual STW pauses. Latency-oriented collectors (G1 to a degree; ZGC/Shenandoah aggressively) do more work concurrently to keep pauses sub-millisecond to low-millisecond, paying CPU and footprint overhead.
- **Footprint:** total memory used. A bigger heap reduces GC frequency (more room before Eden/old fill) but uses more RAM and can lengthen pauses for non-concurrent collectors. Some collectors (ZGC) need extra headroom for concurrent work.

Every tuning decision trades among these. The log lets you see exactly where you sit on the triangle.

### 2.6 Safepoints — the precondition for any STW work

A **safepoint** is a point in execution where a thread's state (stack, registers) is fully known to the JVM, so the GC can safely inspect/move objects. Before any STW pause, the JVM requests all threads to reach a safepoint ("**safepoint synchronization**" / "bringing threads to a safepoint"); threads poll for the request at method returns, loop back-edges, etc. The wall-clock from "request" to "all stopped" is **time-to-safepoint (TTSP)**. A thread stuck in a tight counted loop, a long JNI call, or page-faulting can delay TTSP, making a "1 ms GC" actually freeze the app for 200 ms. **TTSP is invisible in naive GC reasoning but visible with `-Xlog:safepoint`**, and it is a frequent cause of "the GC log says pauses are tiny but users see freezes."

### 2.7 The mainstream HotSpot collectors (one-line each, expanded later)

- **Serial GC** (`-XX:+UseSerialGC`): single-threaded, young+old, STW. Tiny heaps, single-CPU, containers with one core. Default for `-client`-class / small environments.
- **Parallel GC / "Throughput collector"** (`-XX:+UseParallelGC`): multi-threaded STW for both young and old (compacting). Maximizes throughput; pauses can be long. Default in Java 8.
- **G1 GC** (`-XX:+UseG1GC`): **region-based**, mostly-concurrent marking with incremental "mixed" evacuation, pause-time goal driven. **Default since Java 9.** The workhorse for general server apps.
- **ZGC** (`-XX:+UseZGC`): concurrent, region/page-based, **colored pointers** + load barriers, sub-millisecond pauses, scales to multi-TB heaps. Production-ready and non-experimental since JDK 15; **generational ZGC** since JDK 21 (`-XX:+ZGenerational`, and the default ZGC mode in JDK 23+).
- **Shenandoah** (`-XX:+UseShenandoahGC`): concurrent, **Brooks/load-reference-barrier** based compaction, low pause, similar niche to ZGC. Available in OpenJDK builds (Red Hat–led); generational mode added more recently.
- **Epsilon** (`-XX:+UseEpsilonGC`): a *no-op* collector that never reclaims — for benchmarking allocation and for short-lived jobs that never fill the heap.

The rest of this chapter assumes **G1 as the default** (since it's what most JDK 11/17/21 server apps run) and calls out ZGC/Parallel where it matters.

---

## 3. How it works internally

This is the heart of the chapter. We'll trace the **full internal workflow** of allocation → minor GC → concurrent cycle → mixed GC → full-GC fallback, primarily for **G1**, then contrast with Parallel and ZGC. Understanding this control/data flow is what makes a GC log *legible*.

### 3.1 G1's heap layout: regions

G1 divides the heap into **equal-sized regions** (power of two between 1 MB and 32 MB; chosen at startup so there are roughly 2048 regions — `-XX:G1HeapRegionSize` overrides). A *region* is the unit of allocation and reclamation. Each region is dynamically tagged as **Eden**, **Survivor**, **Old**, **Humongous**, or **Free**. There is no fixed physical young/old boundary; G1 just changes how many regions are young vs old over time. This region model is why G1 can collect "some old regions" (mixed GC) instead of all-or-nothing.

- **Humongous objects/regions:** an object ≥ **50% of one region size** is "humongous" and allocated directly into one or more *contiguous* Humongous regions in old gen, bypassing Eden. Example: with 4 MB regions, any object ≥ 2 MB is humongous. *Humongous allocations* are special because they can't be moved as cheaply, they can fragment the heap (need contiguous free regions), and historically triggered concurrent cycles / full GCs. Large `byte[]`/`char[]` (big buffers, deserialized blobs) are the usual culprits. **Tracking humongous allocations in the log is a key signal.**

### 3.2 Key G1 data structures (define each)

- **Remembered Sets (RSets):** per-region data structures recording *which other regions hold references into this region* ("who points at me"). RSets let G1 collect a region without scanning the entire heap for inbound pointers — it only scans the recorded sources. *Remembered set* = an index of incoming cross-region references.
- **Card Table:** a coarse bitmap dividing the heap into 512-byte "**cards**." When the mutator writes a reference (`a.field = b`), a **write barrier** (a tiny piece of code injected by the JIT after each reference store) marks the card "dirty," meaning "this card may contain a cross-generation/cross-region pointer." A *write barrier* is bookkeeping code that runs on every pointer write; a *card* is a small heap chunk tracked for "has it been written." Dirty cards feed RSet maintenance.
- **Collection Set (CSet):** the set of regions chosen to be collected in a given pause (all young regions for a minor GC; young + selected old for a mixed GC).
- **SATB (Snapshot-At-The-Beginning):** G1's concurrent-marking correctness algorithm. At marking start, it logically takes a *snapshot* of the object graph; the write barrier records overwritten references so objects that were live at snapshot time aren't lost even if the mutator changes pointers during marking. *SATB* guarantees concurrent marking doesn't miss objects, at the cost of keeping some already-dead objects alive for one cycle ("**floating garbage**").

### 3.3 Step-by-step: a minor (young) GC in G1

1. **Allocation pressure builds.** Threads bump-allocate into TLABs carved from Eden regions. As Eden regions fill, G1 may add more Eden regions up to the current young-gen target.
2. **Young occupancy hits the threshold.** When Eden is full (G1 sizes young gen adaptively to meet `MaxGCPauseMillis`), G1 schedules a **young collection** (STW).
3. **Bring threads to a safepoint.** The VM requests a safepoint; all mutators stop (TTSP elapses).
4. **Root scanning.** GC threads scan **GC roots**: thread stacks, registers, JNI handles, static fields, and — critically — the **RSets/dirty cards** that record old→young references (so G1 knows which old objects point into the young CSet *without* scanning all of old gen).
5. **Evacuation (copying).** Live objects in the CSet (all Eden + Survivor regions) are **copied/evacuated** to new Survivor regions, or **promoted** to Old regions if their age ≥ tenuring threshold. Multiple parallel GC worker threads do this; this is the bulk of pause time.
6. **Reference processing & cleanup.** Process `Reference` objects (soft/weak/phantom/final), update RSets, fix up pointers, and recycle the now-empty source regions to **Free**.
7. **Resize & resume.** G1 adjusts young-gen size for next time based on whether it hit the pause goal, then releases the safepoint; mutators resume.

The pause cost ≈ (objects copied) + (roots scanned) + (RSet/card work). Hence two levers: fewer live young objects (shrink young or reduce allocation) and less RSet work.

### 3.4 Step-by-step: G1's concurrent old-gen cycle

G1 doesn't continuously collect old gen; it runs a **concurrent marking cycle** when old-gen occupancy crosses a threshold, then reclaims via *mixed* GCs.

1. **Initiating heuristic — IHOP.** When old-gen occupancy reaches the **Initiating Heap Occupancy Percent** (`-XX:InitiatingHeapOccupancyPercent`, **IHOP**, default 45% in older JDKs; since JDK 9 G1 uses *adaptive IHOP* and computes it at runtime), G1 starts a concurrent cycle. *IHOP* = the old-gen occupancy at which G1 begins marking; set it low to start earlier (safer, more CPU), high to start later (riskier).
2. **Initial Mark (STW, piggybacked on a young GC).** Marks roots; done as part of a normal young pause so it's cheap. (Newer logs call this "Concurrent Start.")
3. **Concurrent Root Region Scan.** Scans survivor regions for references into old gen, concurrently with the mutator.
4. **Concurrent Mark.** Traverses the live object graph in old gen concurrently, using **SATB** to stay correct under mutation. This is the long phase; it runs on a few concurrent GC threads.
5. **Remark (STW).** Finalizes marking — drains SATB buffers, processes references. Short pause.
6. **Cleanup (STW + concurrent).** Computes per-region liveness, identifies completely-empty old regions and frees them immediately, and sorts the rest by garbage density to plan mixed collections.
7. **Mixed GCs follow.** Subsequent young pauses become **mixed**: they evacuate all young regions *plus* a few of the highest-garbage old regions (the "garbage first" choice that names G1), spread over several pauses (`-XX:G1MixedGCCountTarget`, default 8) to keep each pause within the goal. Old regions below `-XX:G1MixedGCLiveThresholdPercent` (default 85% live ⇒ collected) liveness are eligible; regions whose reclaimable space falls below `-XX:G1HeapWastePercent` (default 5%) cause G1 to stop mixed collecting (it's not worth it).

### 3.5 The full-GC fallback (and why you want to avoid it)

If the concurrent cycle can't keep up — old gen fills before marking+mixed reclamation finishes, or a humongous allocation can't find contiguous free regions, or promotion fails — G1 falls back to a **Full GC**: a single STW, (since JDK 10) *parallel* mark-sweep-compact of the entire heap. It's correct but slow (hundreds of ms to seconds on large heaps). Triggers to recognize in the log:
- **Evacuation Failure / "to-space exhausted":** during a young/mixed pause there were no free regions to copy survivors into. G1 has to handle objects in place; very expensive, often precedes a full GC.
- **Promotion failure:** old gen had no room for promoted objects.
- **Humongous allocation failure:** no contiguous region run.
- **Metadata GC Threshold:** Metaspace pressure (not heap) triggered a collection.

**A healthy G1 service shows minor and (periodic) mixed GCs and effectively zero Full GCs.** Seeing `Pause Full` repeatedly is the loudest alarm in the log.

### 3.6 Contrast: Parallel GC internal flow

Parallel GC has fixed **PSYoungGen** (Eden+2 survivors) and **ParOldGen**. Young collection is a STW parallel copying collection (same idea as above). Old/Full collection is a STW **parallel mark-sweep-compact**: mark live objects, compute new addresses, slide objects to one end to eliminate fragmentation, update references. There is **no concurrent phase** — every old collection is a full STW compaction, so pauses scale with *live* old-gen size and can be long (e.g., 1–5 s on a 30 GB heap), but throughput (and simplicity) is excellent. Parallel GC has an **adaptive sizing** ergonomic (`-XX:+UseAdaptiveSizePolicy`, on by default) that resizes generations to meet goals `-XX:MaxGCPauseMillis` and `-XX:GCTimeRatio` (throughput goal).

### 3.7 Contrast: ZGC internal flow (concurrent everything)

ZGC keeps **all** expensive work (marking, relocation/compaction, reference processing) concurrent; its STW pauses are only tiny synchronization points (sub-millisecond, independent of heap size). Two mechanisms:
- **Colored pointers:** ZGC stores metadata bits *inside* the 64-bit object pointer (marked, remapped, etc.) rather than in the object or a side table. A *colored pointer* encodes GC state in unused address bits.
- **Load barriers:** code injected on every object-*reference load* (read barrier) that checks the pointer color and, if the object has been relocated, fixes up the reference on the fly ("self-healing"). This lets ZGC move objects while the mutator runs.

ZGC pauses are essentially constant (~tens of microseconds to low single-digit ms) regardless of heap size, which is why it targets 10s of GB to multi-TB heaps and tight latency SLOs. The cost is per-load barrier CPU overhead and extra heap headroom. **Generational ZGC** (JDK 21+) adds young/old generations to reduce CPU cost by collecting the young gen more cheaply, matching the generational hypothesis.

### 3.8 The state machine, summarized

```
            allocate (TLAB/Eden)
                  │  Eden full
                  ▼
            ┌───────────┐  survivors copied; old++; some promoted
            │ Minor GC  │──────────────────────────────────────┐
            └───────────┘                                       │
                  │ old-gen occupancy ≥ IHOP                    │
                  ▼                                             │
        ┌──────────────────┐  concurrent marking + remark      │
        │ Concurrent Cycle │                                    │
        └──────────────────┘                                    │
                  │ regions ranked by garbage                   │
                  ▼                                             │
            ┌───────────┐  young + chosen old regions evacuated │
            │ Mixed GC  │ (×N pauses) ──────────────────────────┘
            └───────────┘
                  │ can't keep up / evac failure / humongous / promo fail
                  ▼
            ┌───────────┐  STW whole-heap compaction (BAD if frequent)
            │  Full GC  │
            └───────────┘
```

---

## 4. The complete toolkit

### 4.1 Enabling and configuring GC logs (the most important flags)

**Java 9+ uses Unified Logging (`-Xlog`).** Java 8 used the older `-XX:+PrintGCDetails` family. Know both because plenty of services still run on 8.

**Java 9+ (Unified Logging) — canonical incantations:**

| Flag | Purpose |
|---|---|
| `-Xlog:gc` | Basic one-line-per-collection log (info level). |
| `-Xlog:gc*` | All GC subsystem tags at info — the standard "give me the useful GC log." The `*` is a wildcard matching `gc`, `gc+heap`, `gc+age`, `gc+phases`, etc. |
| `-Xlog:gc*=debug` | Verbose; phase-level detail. Use when diagnosing, not in steady prod. |
| `-Xlog:gc+heap=debug` | Per-region/heap occupancy detail. |
| `-Xlog:gc+age=trace` | **Tenuring distribution** (ages of survivors) — essential for diagnosing premature promotion. |
| `-Xlog:gc+humongous=debug` | Humongous allocation detail. |
| `-Xlog:safepoint` | Safepoint sync + TTSP — catches "tiny GC, big freeze." |
| `-Xlog:gc+ergo*=trace` | G1's ergonomic decisions (why it resized, why it started a cycle). |

**The full output decorator + rotation form (recommended for production):**

```
-Xlog:gc*,gc+age=trace,safepoint:file=/var/log/app/gc.log:utctime,pid,tid,level,tags:filecount=10,filesize=50m
```

Breaking this down:
- `gc*,gc+age=trace,safepoint` — **what** to log (selectors; comma-separated tag sets, each with optional `=level`).
- `:file=/var/log/app/gc.log` — **output**: a file (vs `stdout`/`stderr`). Logging to a file avoids interleaving with app stdout and survives container log truncation.
- `:utctime,pid,tid,level,tags` — **decorators**: each line is prefixed with these. `utctime` (or `time`/`uptime`/`uptimemillis`), process id, thread id, log level, and tags. Decorators make lines machine-parseable and human-correlatable.
- `:filecount=10,filesize=50m` — **rotation**: keep 10 files of 50 MB each (500 MB ceiling). Without this, a busy service can fill a disk with GC logs.

> Version note: in JDK 8, the equivalent is `-XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCTimeStamps -XX:+PrintTenuringDistribution -Xloggc:/var/log/app/gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=50M`. The flag names and log *format* differ entirely from `-Xlog`. Tools must be told which format they're parsing.

**Cost of GC logging:** negligible in practice (a few microseconds per collection to format a line; collections are far apart). **Always run GC logging on in production.** The cost of *not* having logs during an incident dwarfs the runtime cost.

### 4.2 Heap and generation sizing flags

| Flag | Purpose | Default / Notes |
|---|---|---|
| `-Xms<size>` | Initial heap size. | Ergonomic (often 1/64 of RAM). Set `-Xms == -Xmx` in servers to avoid heap-resize pauses and commit memory up front. |
| `-Xmx<size>` | Maximum heap size. | Ergonomic ≈ 1/4 of physical/container RAM (capped at 25% by `MaxRAMPercentage` default). |
| `-XX:MaxRAMPercentage=<pct>` | Cap heap as % of *available* (container-aware) RAM. | Default 25.0. **Preferred over `-Xmx` in containers** so the JVM adapts to the cgroup limit. |
| `-XX:InitialRAMPercentage`, `-XX:MinRAMPercentage` | Initial / small-RAM caps. | `MinRAMPercentage` (default 50) applies only when RAM ≤ ~250 MB — a common gotcha. |
| `-XX:NewRatio=<n>` | Old/young size ratio. | Default 2 (young = 1/3 heap) for Parallel; ignored/advisory under G1. |
| `-XX:NewSize` / `-XX:MaxNewSize` / `-Xmn` | Fix young-gen size. | **Avoid under G1** — it disables G1's adaptive young sizing and breaks the pause-time goal. |
| `-XX:SurvivorRatio=<n>` | Eden/survivor ratio. | Default 8 (each survivor = 1/8 of Eden). |
| `-XX:MaxTenuringThreshold=<n>` | Max age before promotion. | Default 15 (G1/Parallel). Lower = promote sooner. |
| `-XX:TargetSurvivorRatio=<pct>` | Target survivor fill before lowering dynamic tenuring threshold. | Default 50. |
| `-XX:MetaspaceSize` / `-XX:MaxMetaspaceSize` | Metaspace initial trigger / cap. | `MaxMetaspaceSize` unlimited by default — **set it** to avoid native OOM from classloader leaks. |

### 4.3 Collector selection & pause-goal flags

| Flag | Collector / Purpose | Default / Notes |
|---|---|---|
| `-XX:+UseG1GC` | Select G1. | Default since JDK 9. |
| `-XX:+UseParallelGC` | Select Parallel (throughput). | Default in JDK 8. |
| `-XX:+UseZGC` | Select ZGC. | Add `-XX:+ZGenerational` for generational ZGC on JDK 21/22; default ZGC mode is generational in JDK 23+. |
| `-XX:+UseShenandoahGC` | Select Shenandoah. | OpenJDK builds. |
| `-XX:+UseSerialGC` | Select Serial. | Small/single-core/container default. |
| `-XX:+UseEpsilonGC` | No-op collector. | Requires `-XX:+UnlockExperimentalVMOptions`. |
| `-XX:MaxGCPauseMillis=<ms>` | **Soft** pause-time goal (G1, Parallel). | **G1 default 200.** A *target*, not a guarantee; G1 sizes young gen and CSet to try to hit it. |
| `-XX:GCTimeRatio=<n>` | Throughput goal: app:GC time = n:1. | Default 99 (Parallel) ⇒ ≤1% in GC; G1 default 12 ⇒ ~8%. |
| `-XX:G1HeapRegionSize=<size>` | G1 region size. | Auto (1–32 MB, ~2048 regions). Raise to reduce humongous objects. |
| `-XX:InitiatingHeapOccupancyPercent=<pct>` | IHOP — when to start concurrent cycle. | Default 45; adaptive since JDK 9 (this becomes the *initial* value). |
| `-XX:G1NewSizePercent` / `-XX:G1MaxNewSizePercent` | Young-gen size bounds (% of heap). | Defaults 5 and 60. |
| `-XX:G1ReservePercent` | Heap kept in reserve to avoid evac failure. | Default 10. Raise if you see to-space exhausted. |
| `-XX:G1MixedGCCountTarget` | Spread mixed collections over N pauses. | Default 8. |
| `-XX:G1HeapWastePercent` | Stop mixed GC when reclaimable < this %. | Default 5. |
| `-XX:G1MixedGCLiveThresholdPercent` | Old regions above this liveness are skipped in mixed GC. | Default 85. |
| `-XX:ParallelGCThreads=<n>` | STW GC worker threads. | Ergonomic from CPU count (≈ 5/8 of CPUs above 8). **Set explicitly in containers** — JVM may miscount CPUs. |
| `-XX:ConcGCThreads=<n>` | Concurrent GC threads (G1/ZGC/Shenandoah). | ≈ 1/4 of `ParallelGCThreads`. |

### 4.4 Diagnostic / heap-dump flags

| Flag | Purpose |
|---|---|
| `-XX:+HeapDumpOnOutOfMemoryError` | Write an `.hprof` heap dump on OOM. **Always set in prod.** |
| `-XX:HeapDumpPath=<dir>` | Where the dump goes (default cwd). Point to a volume with space. |
| `-XX:+ExitOnOutOfMemoryError` / `-XX:+CrashOnOutOfMemoryError` | Fail fast on OOM instead of limping. |
| `-XX:OnOutOfMemoryError="<cmd>"` | Run a command (e.g., notify/snapshot) on OOM. |
| `-XX:+PrintFlagsFinal` | Dump every flag's effective value at startup (find ergonomic defaults). |
| `-XX:NativeMemoryTracking=summary` | Enable NMT to see off-heap usage (`jcmd <pid> VM.native_memory`). |

### 4.5 Runtime / CLI tools

| Tool | What it does |
|---|---|
| `jcmd <pid> GC.heap_info` | One-shot heap occupancy. |
| `jcmd <pid> GC.run` | Force a full GC (diagnostic only — never in a hot path). |
| `jcmd <pid> GC.heap_dump <file>` | On-demand heap dump (better than waiting for OOM). |
| `jcmd <pid> GC.class_histogram` | Live object histogram by class (cheap leak triage). |
| `jcmd <pid> VM.flags` / `VM.system_properties` | Effective flags / properties. |
| `jstat -gcutil <pid> 1000` | Per-second GC utilization (% of each space used, GC counts/times). The fastest "is GC the problem?" check. |
| `jstat -gc <pid> 1000` | Absolute capacities/usages per space. |
| `jmap -histo:live <pid>` | Class histogram (forces a GC with `:live`). |
| `jhsdb jmap` / `jhsdb` | Serviceability-agent based deep inspection. |
| `async-profiler` (`-e alloc`) | **Allocation profiler** — pinpoints *which code* allocates the most, the real fix for high allocation rate. |
| JFR (`-XX:StartFlightRecording`) | **Java Flight Recorder** — low-overhead always-on event stream incl. GC, allocation, TLAB, safepoint events. The modern way to capture GC + allocation context. |
| JMC (JDK Mission Control) | GUI to analyze JFR recordings, incl. GC and allocation views. |

### 4.6 GC-log analysis tools

| Tool | Notes |
|---|---|
| **GCeasy** (gceasy.io) | SaaS (and on-prem) GC-log analyzer; upload a log, get pause-time percentiles, throughput, allocation/promotion rate, heap-after-GC trends, and problem detection. The de-facto quick analyzer. Privacy: don't upload logs with sensitive paths to SaaS — use the API/on-prem build. |
| **GCViewer** (open source) | Local Swing app; parses many formats; charts pauses, throughput, heap. Good offline option. |
| **JClarity Censum** | Historically the gold-standard automated GC analysis (now part of Microsoft after the JClarity acquisition; "JClarity-style analysis" = automated diagnosis with prescriptive recommendations). |
| **gceasy/garbagecat** (Red Hat `garbagecat`) | CLI/jar that parses logs and emits diagnoses + recommendations; good for CI/automation. |
| **JFR + JMC** | Not a log parser, but the richest source: ties GC events to allocation stacks. |

---

## 5. Code examples by use case

These span different real scenarios. Java where language-relevant; otherwise CLI/config. Comments explain the non-obvious lines.

### 5.1 Use case A — Standard low-latency web service (G1) launch config

```bash
java \
  -XX:+UseG1GC \                         # default on 11/17/21, explicit for clarity
  -XX:MaxRAMPercentage=70.0 \            # container-aware sizing; leave RAM for off-heap/Metaspace/threads
  -XX:InitialRAMPercentage=70.0 \        # commit up front; avoids heap-grow pauses
  -XX:MaxGCPauseMillis=100 \             # soft p~pause goal; G1 sizes young gen toward this
  -XX:+ParallelRefProcEnabled \          # parallelize Reference processing (helps if you use many WeakRefs)
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/app/ \
  -XX:MaxMetaspaceSize=256m \            # cap Metaspace so a classloader leak fails loudly, not the host
  -Xlog:gc*,gc+age=trace,safepoint:file=/var/log/app/gc.log:utctime,pid,tid,level,tags:filecount=10,filesize=50m \
  -jar app.jar
```

Why `MaxRAMPercentage=70` not `-Xmx`: in Kubernetes the cgroup memory limit can change between environments; a percentage adapts. The remaining 30% covers thread stacks, Metaspace, direct buffers (`ByteBuffer.allocateDirect`), JIT code cache, and the container's own headroom — set this with knowledge of your off-heap usage, not by ritual.

### 5.2 Use case B — Maximize batch throughput (Parallel GC), pauses don't matter

```bash
java \
  -XX:+UseParallelGC \                   # throughput collector: long rare pauses OK in batch
  -Xms24g -Xmx24g \                      # fixed large heap; fewer collections
  -XX:GCTimeRatio=99 \                   # aim for <=1% time in GC (default)
  -XX:+UseAdaptiveSizePolicy \           # let it resize generations to maximize throughput
  -Xlog:gc*:file=/var/log/batch/gc.log:uptime,level,tags:filecount=5,filesize=100m \
  -jar etl-job.jar
```

For a nightly ETL with no latency SLO, a 2-second pause every few minutes is fine if total GC overhead is minimized. Parallel GC usually wins on raw throughput vs G1 here.

### 5.3 Use case C — Ultra-low-latency, large heap (ZGC)

```bash
java \
  -XX:+UseZGC \                          # sub-ms pauses, heap-size-independent
  -XX:+ZGenerational \                   # generational ZGC (JDK 21/22; default in 23+, flag harmless)
  -Xms64g -Xmx64g \                      # ZGC likes fixed, generous heap (needs headroom for concurrency)
  -XX:SoftMaxHeapSize=56g \              # soft cap: keep ~8g headroom for concurrent relocation
  -XX:ConcGCThreads=4 \                  # tune if GC can't keep up with allocation (see logs)
  -Xlog:gc*:file=/var/log/app/gc.log:utctime,level,tags:filecount=8,filesize=64m \
  -jar latency-critical.jar
```

`SoftMaxHeapSize` tells ZGC to *try* to stay below 56 GB, leaving headroom so concurrent relocation never loses the race with allocation (an "**allocation stall**," ZGC's equivalent of an evac failure).

### 5.4 Use case D — Tiny sidecar/CLI in a 1-core, 256 MB container

```bash
java \
  -XX:+UseSerialGC \                     # G1's concurrency overhead is wasted on 1 core / tiny heap
  -XX:MaxRAMPercentage=75.0 \
  -XX:MaxMetaspaceSize=64m \
  -Xss512k \                             # smaller thread stacks; many small threads otherwise eat RAM
  -Xlog:gc:file=/proc/1/fd/1:uptime,tags \   # log to container stdout (PID 1) for collection
  -jar sidecar.jar
```

On one CPU, Serial GC often beats G1 because there are no spare cores to run G1's parallel/concurrent threads, and Serial has the smallest footprint and code path.

### 5.5 Use case E — Reproduce a leak deterministically (Epsilon)

```bash
java \
  -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC \  # never collects
  -Xms2g -Xmx2g \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=. \
  -Xlog:gc \
  -jar suspected-leak.jar
```

With Epsilon, *any* unbounded retention fills the heap monotonically and OOMs predictably — you get a clean heap dump at the moment of exhaustion, uncontaminated by collection. Great for confirming and capturing a leak in a controlled run (never in prod).

### 5.6 Use case F — Programmatic GC monitoring inside the app (JMX `GarbageCollectorMXBean`)

```java
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import com.sun.management.GarbageCollectionNotificationInfo; // requires the com.sun.management module
import com.sun.management.GcInfo;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

public final class GcMonitor {
    public static void install() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // Each bean represents one collector (e.g. "G1 Young Generation", "G1 Old Generation").
            NotificationEmitter emitter = (NotificationEmitter) bean;
            emitter.addNotificationListener((Notification n, Object handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                        .equals(n.getType())) return;
                GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                GcInfo gc = info.getGcInfo();
                long pauseMs = gc.getDuration();             // pause duration for this collection
                String cause = info.getGcCause();            // e.g. "G1 Evacuation Pause", "Metadata GC Threshold"
                String action = info.getGcAction();          // "end of minor GC" / "end of major GC"
                // Emit to your metrics system; here we just demonstrate the key signals:
                long usedAfter = gc.getMemoryUsageAfterGc().values().stream()
                                   .mapToLong(u -> u.getUsed()).sum();
                // Heap-occupancy-after-GC trend (usedAfter over time) is THE leak signal.
                Metrics.timer("jvm.gc.pause", "action", action, "cause", cause).record(pauseMs);
                Metrics.gauge("jvm.gc.heap.used_after", usedAfter);
                if ("end of major GC".equals(action) || cause.contains("Full")) {
                    Metrics.counter("jvm.gc.full").increment(); // alert on this != 0 for G1/ZGC
                }
            }, null, null);
        }
    }
}
```

This is how you ship GC signals to Prometheus/Datadog without scraping logs. (Micrometer's `JvmGcMetrics` does essentially this for you — prefer it in real systems; the above shows the mechanism.) The **single most valuable derived metric** is *heap used immediately after each old/full GC over time*: flat ⇒ healthy; monotonically rising ⇒ leak.

### 5.7 Use case G — Compute allocation & promotion rate from `jstat` (the field tool)

```bash
# -gcutil prints percent-utilization + cumulative counts/times, once per 1000 ms, 600 samples.
jstat -gcutil <pid> 1000 600
#  S0    S1     E      O      M     CCS    YGC   YGCT    FGC  FGCT    GCT
#  0.00 71.42  43.10  62.30 95.1  91.0   1820  41.300    3   2.110  43.410
```

Reading it: `E` = Eden %used, `O` = Old %used, `M` = Metaspace %used, `YGC`/`YGCT` = young GC count/total-seconds, `FGC`/`FGCT` = full GC count/total-seconds. **Allocation rate** ≈ (Eden capacity × number of young GCs in interval) / interval. **Promotion rate** ≈ (rise in `O` used per young GC) × young-GC frequency. If `O` climbs steadily and `FGC` increments, you have promotion pressure or a leak. `jstat -gc` (no `util`) gives absolute KB so you can do the arithmetic precisely.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Allocation rate is the master variable.** Most "GC problems" are really "the app allocates too much." Halving allocation roughly halves young-GC frequency. The fix is usually in *code* (object reuse, primitive collections, avoiding boxing, streaming instead of materializing), found with an **allocation profiler** (async-profiler `-e alloc`, JFR allocation events), not in GC flags. **Profile allocation before touching a flag.**
- **Pause time ∝ live data copied (young) or live data marked/compacted (old).** Reducing *retained* live set (smaller caches, shorter object lifetimes) shrinks pauses more reliably than fiddling with thread counts.
- **Bigger heap ⇒ fewer collections but (for STW collectors) longer ones.** For G1 a bigger heap mostly means rarer cycles; for Parallel a bigger heap means longer full GCs. ZGC is heap-size-insensitive on pause but needs *headroom*.
- **Right-size GC threads in containers.** The JVM is container-aware for memory (since JDK 8u191/10) and CPU (cgroup quota), but quota→CPU rounding can mislead `ParallelGCThreads`/`ConcGCThreads`. Set them explicitly when you pin CPU shares; too many GC threads on a quota-limited pod causes throttling and *longer* pauses.

### 6.2 Correctness & concurrency

- **GC itself is correct by construction** — your job is not to break its assumptions. Avoid relying on finalizers (`finalize()` is deprecated, runs at unpredictable times on a single finalizer thread, and can resurrect objects); use `Cleaner`/try-with-resources.
- **`System.gc()`** requests a Full GC and can wreck latency. Disable its STW effect with `-XX:+ExplicitGCInvokesConcurrent` (G1) or ignore it entirely with `-XX:+DisableExplicitGC` — but beware: some libraries (e.g., NIO direct-buffer reclamation, RMI DGC) lean on `System.gc()`; disabling it can leak direct memory. Know your dependencies before disabling.
- **Reference types:** `SoftReference` is cleared only under memory pressure (governed by `-XX:SoftRefLRUPolicyMSPerMB`, default 1000 ms/MB) — soft-ref caches can *cause* full GCs by surviving until the heap is nearly full. Prefer bounded caches (Caffeine) over soft-ref caches.

### 6.3 Memory & footprint

- Heap is not the whole RSS. **RSS = heap + Metaspace + thread stacks (`-Xss` × threads) + JIT code cache + direct/mapped ByteBuffers + GC structures (RSets, card table, mark bitmaps) + native libs.** Containers OOM-kill on RSS, not heap. A common failure: `-Xmx` set to the full pod limit ⇒ kernel kills the process when off-heap pushes RSS over the cgroup limit, with no Java OOM and no heap dump — just a `137` exit. **Leave 25–40% of the pod limit for non-heap.** Use `-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory` to see the breakdown.
- G1's RSets can consume noticeable memory under heavy cross-region pointer mutation; ZGC/Shenandoah pay other overheads. Account for it in sizing.

### 6.4 Security

- **Heap dumps contain everything** — passwords, tokens, PII in memory. Treat `.hprof` files as secrets: restrict `HeapDumpPath` permissions, scrub before sharing, never upload to a SaaS analyzer.
- **GC logs can leak file paths, hostnames, and timing oracles.** Lower-sensitivity than heap dumps but still don't paste raw prod logs into public tools without review; prefer on-prem/local analyzers (GCViewer, garbagecat) for sensitive environments.

### 6.5 Observability (do this regardless of whether you have a problem)

- **Always-on GC logging** with rotation (Section 4.1). It's nearly free and irreplaceable post-incident.
- **Export GC metrics** (Micrometer `JvmGcMetrics`, or the MXBean approach in 5.6): pause time histogram, GC count by cause, allocation rate, **heap-used-after-old-GC**, Metaspace used, and **Full-GC count (alert if > 0 on G1/ZGC)**.
- **Always-on JFR** (`-XX:StartFlightRecording=settings=profile,maxsize=512m,maxage=6h,dumponexit=true,filename=/var/log/app/app.jfr`) gives you allocation stacks + GC events when an incident hits — the difference between "GC is high" and "*this method* is the cause."
- **Monitor TTSP** with `-Xlog:safepoint`; alert if time-to-safepoint regularly exceeds a few ms.

### 6.6 Cost

- GC CPU is real money in the cloud. A service spending 8% of CPU in GC needs ~8% more cores. Reducing allocation rate is a direct cost saving. Conversely, ZGC's barrier overhead (single-digit % throughput cost) buys latency — pay for it only where the SLO requires.
- Over-large heaps waste RAM you pay for; right-sizing from log-derived occupancy can shrink instances.

### 6.7 Testing

- **Load-test with production-representative allocation and object lifetimes**, and the *production GC config*. GC behavior is workload-dependent; a microbenchmark won't reveal a promotion problem that only appears under sustained traffic.
- **Soak tests (hours)** are the only way to catch slow leaks and "death by a thousand mixed GCs."
- Compare collectors empirically (A/B the same load) rather than from folklore. Use the same log format and a fixed analyzer.

### 6.8 Production hardening checklist

- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<volume-with-space>`
- `-XX:MaxMetaspaceSize=<cap>` (prevents classloader-leak native OOM taking the host)
- `-XX:+ExitOnOutOfMemoryError` (fail fast → orchestrator restarts; a JVM limping after OOM corrupts state)
- Always-on `-Xlog:gc*` with rotation to a real volume (not container ephemeral that vanishes on crash)
- `-XX:NativeMemoryTracking=summary` (off-heap visibility for RSS triage)
- Explicit `ParallelGCThreads`/`ConcGCThreads` when CPU-limited
- Leave RSS headroom below the cgroup limit

### 6.9 Anti-patterns (the ones that actually hurt)

| Anti-pattern | Why it's bad |
|---|---|
| **Copy-pasting flags from a blog** | Flags are workload- and version-specific; a 2014 CMS tuning post is poison on JDK 17 G1. |
| **Setting `-Xmn`/`NewSize` under G1** | Disables adaptive young sizing; breaks the pause-time goal; usually *worsens* pauses. |
| **Tuning before reading the log** | You can't know which knob matters. 80% of "tuning" is undoing prior blind tuning. |
| **Adding flags one per Stack Overflow answer** | Flags interact; a pile of half-understood flags is unreproducible and often self-canceling. |
| **`System.gc()` in app code / RMI defaults** | Periodic forced Full GCs nuke p99. |
| **Soft-reference caches as primary cache** | They survive until near-OOM, then cause full GCs and latency cliffs. |
| **Lowering `MaxGCPauseMillis` to a tiny value** | G1 shrinks young gen → collects more often → *worse* throughput and sometimes worse total pause; it's a goal, not magic. |
| **Treating RSS = heap** | Causes container OOM-kills (exit 137) with no diagnostics. |
| **Disabling GC logging "for performance"** | Saves microseconds, costs you the only evidence in the next incident. |

---

## 7. Advanced topics & deep internals

### 7.1 Reading a real G1 unified GC log line by line

A representative healthy young pause (JDK 17, `-Xlog:gc*`):

```
[2026-06-24T10:15:32.101+0000][info][gc,start] GC(412) Pause Young (Normal) (G1 Evacuation Pause)
[2026-06-24T10:15:32.101+0000][info][gc,task ] GC(412) Using 8 workers of 8 for evacuation
[2026-06-24T10:15:32.118+0000][info][gc,phases] GC(412)   Pre Evacuate Collection Set: 0.1ms
[2026-06-24T10:15:32.118+0000][info][gc,phases] GC(412)   Evacuate Collection Set: 14.2ms
[2026-06-24T10:15:32.118+0000][info][gc,phases] GC(412)   Post Evacuate Collection Set: 1.8ms
[2026-06-24T10:15:32.118+0000][info][gc,heap  ] GC(412) Eden regions: 240->0(232)
[2026-06-24T10:15:32.118+0000][info][gc,heap  ] GC(412) Survivor regions: 12->18(30)
[2026-06-24T10:15:32.118+0000][info][gc,heap  ] GC(412) Old regions: 410->414
[2026-06-24T10:15:32.118+0000][info][gc,heap  ] GC(412) Humongous regions: 6->6
[2026-06-24T10:15:32.118+0000][info][gc,metaspace] GC(412) Metaspace: 180M->180M(1024M)
[2026-06-24T10:15:32.118+0000][info][gc       ] GC(412) Pause Young (Normal) (G1 Evacuation Pause) 2632M->1664M(4096M) 17.3ms
[2026-06-24T10:15:32.118+0000][info][gc,cpu   ] GC(412) User=0.11s Sys=0.01s Real=0.02s
```

How to read it:
- **`GC(412)`** — collection id; lets you correlate the multi-line block.
- **`Pause Young (Normal) (G1 Evacuation Pause)`** — a normal STW young copying collection. "(Concurrent Start)" would mean it also kicked off a concurrent cycle; "(Mixed)" means young+old.
- **Phases:** `Evacuate Collection Set: 14.2ms` is the dominant cost (copying survivors). If `Pre/Post Evacuate` dominate, the problem is RSet/reference processing, not copying.
- **`Eden regions: 240->0(232)`** — Eden went from 240 used to 0 (all collected), and the *target* Eden for next time is 232 (G1 resized it slightly to hold the pause goal). Eden→0 every young GC is normal.
- **`Old regions: 410->414`** — old grew by 4 regions = **promotion** this cycle. Steady, slow growth here that never gets reclaimed by mixed GC ⇒ leak or undersized old gen.
- **`Humongous regions: 6->6`** — humongous objects present and stable; if this climbs, you have humongous allocation churn (see 7.3).
- **Summary line `2632M->1664M(4096M) 17.3ms`** — heap before→after(total), and **pause = 17.3 ms**. This is the number your SLO cares about.
- **`User/Sys/Real`** — CPU vs wall time. **`Real` ≫ (User+Sys)/threads** signals the pause was waiting on something (page faults, swapping, CPU throttling, I/O) rather than computing — a huge clue that the *host*, not the GC, is the problem.

### 7.2 Reading the tenuring distribution (premature promotion diagnosis)

With `-Xlog:gc+age=trace`:

```
[..][trace][gc,age] GC(412) Desired survivor size 50331648 bytes, new threshold 4 (max 15)
[..][trace][gc,age] GC(412) - age   1:   18874368 bytes,   18874368 total
[..][trace][gc,age] GC(412) - age   2:   12582912 bytes,   31457280 total
[..][trace][gc,age] GC(412) - age   3:    8388608 bytes,   39845888 total
[..][trace][gc,age] GC(412) - age   4:    6291456 bytes,   46137344 total
```

- **`Desired survivor size`** is `TargetSurvivorRatio`% of survivor capacity. The cumulative `total` crosses it at age 4, so G1 set **`new threshold 4`** — objects reaching age 4 will be promoted *this cycle*, well below the max of 15. That means survivor space is too small for the survivor volume ⇒ objects are being **prematurely promoted** to old gen, accelerating old-gen fill and old/full collections. Fixes: enlarge survivor space (raise young-gen size or lower `SurvivorRatio`), or reduce the medium-lived allocation in code. A *high, stable* threshold (near 15) with most bytes at age 1 is the healthy picture.

### 7.3 Humongous allocations — deep dive

Humongous objects (≥ 50% of region size) bypass Eden and go straight to contiguous old-gen Humongous regions. Problems:
- They **fragment** old gen: freeing them leaves region-sized holes; a later humongous allocation needs *contiguous* free regions and can fail (→ concurrent cycle, possibly full GC), even when total free space is ample.
- Pre-JDK 8u40 they weren't reclaimed until a full GC; modern G1 reclaims dead humongous regions during young GCs ("eager reclaim"), but live ones still pin space.
- **Diagnosis:** `Humongous regions` climbing in the heap lines; `-Xlog:gc+humongous=debug` shows sizes. **Fix:** raise `-XX:G1HeapRegionSize` (e.g., from auto 2 MB to 8/16/32 MB) so the same objects are no longer "humongous," *or* reduce large-array allocation (chunk buffers, pool them). Caution: bigger regions mean coarser collection granularity and possibly more wasted space per region.

### 7.4 Adaptive IHOP and starting the cycle on time

Since JDK 9, G1 measures the *allocation rate during marking* and the *marking duration*, and sets IHOP so the concurrent cycle finishes *before* old gen fills — preventing evac failure. If you see evac failures or full GCs despite ample heap, the cycle may be starting too late: lower the *initial* `-XX:InitiatingHeapOccupancyPercent` and/or raise `-XX:G1ReservePercent`. `-XX:-G1UseAdaptiveIHOP` pins it to the static value if the adaptive logic misbehaves (rare).

### 7.5 To-space exhaustion / evacuation failure internals

During evacuation, survivors need destination regions. If `G1ReservePercent` (default 10%) of reserved free regions is exhausted, G1 can't copy and must **self-forward** objects in place and abandon the rest of the evacuation — extremely expensive, logged as `Evacuation Failure` / `to-space exhausted`, and usually followed by a Full GC. Causes: heap too small for the live+promotion volume, allocation spikes, or too-late IHOP. Fixes in order of preference: more heap, raise `G1ReservePercent`, lower IHOP, reduce allocation/promotion.

### 7.6 String dedup and other niche knobs

- **`-XX:+UseStringDeduplication`** (G1): during young GC, deduplicates the backing `char[]/byte[]` of equal `String`s. Helps apps with many duplicate strings (JSON keys, enums-as-strings); costs a little young-GC time. Logged under `gc,stringdedup`.
- **`-XX:+UseTransparentHugePages` / `-XX:+UseLargePages`:** back the heap with 2 MB OS pages to reduce TLB misses (CPU page-translation cache). Can improve throughput on large heaps; THP can cause latency jitter — prefer explicit large pages.
- **`-XX:+AlwaysPreTouch`:** at startup, touch every heap page so the OS commits physical memory eagerly. Eliminates first-touch page-fault latency during the first GCs and steady-state allocation; costs slower startup. Recommended for latency-sensitive services with `-Xms==-Xmx`.
- **`-XX:+ParallelRefProcEnabled`:** parallelize `Reference` (weak/soft/phantom/final) processing during pauses; helps apps with many references (caches, listeners).

### 7.7 ZGC/Shenandoah advanced signals

- **Allocation stall:** when the mutator out-allocates concurrent relocation, ZGC must pause the allocating thread until memory frees up — logged and a sign you need more heap/`SoftMaxHeapSize` headroom or fewer allocations or more `ConcGCThreads`.
- **ZGC log fields** differ (it logs phases like "Pause Mark Start," "Concurrent Mark," "Pause Relocate Start," and reports "Allocation Stall" events and "GC(N) Garbage Collection (Allocation Rate)"). The mental model is the same: watch pause times (should be sub-ms), allocation rate, and whether GC keeps up.

### 7.8 Metaspace and class-data internals

Metaspace OOM (`OutOfMemoryError: Metaspace`) comes from too many loaded classes — classic causes: dynamic proxy/codegen frameworks, redeploys leaking classloaders, scripting engines. Logged as `Metadata GC Threshold` GC causes and rising `Metaspace: used(committed)`. Cap with `-XX:MaxMetaspaceSize`, diagnose with a heap dump's classloader histogram (`jcmd GC.class_histogram`, or MAT's "duplicate classes"/classloader leak suspects).

---

## 8. Tradeoffs & decision frameworks

### 8.1 Collector comparison

| Collector | Pause time | Throughput | Heap range | Concurrency | Best for | Avoid when |
|---|---|---|---|---|---|---|
| **Serial** | Long (single-thread) | Low on multicore | <~1 GB | None | Tiny heaps, 1 CPU, sidecars | Multicore servers, latency SLOs |
| **Parallel** | Long, rare | **Highest** | Up to ~tens of GB | None (STW only) | Batch/ETL, throughput jobs, no latency SLO | Interactive/latency-sensitive |
| **G1** | Moderate, bounded-ish (target 200 ms default) | High | ~4 GB–tens of GB | Concurrent marking, incremental old | **General server default** | Sub-ms p99 needs; very small heaps |
| **ZGC** | **Sub-ms, heap-independent** | Slightly lower (barriers) | ~MBs to multi-TB | Nearly fully concurrent | Strict low-latency, huge heaps | CPU-starved nodes; throughput-max batch |
| **Shenandoah** | **Sub-ms / very low** | Slightly lower | Small–large | Concurrent compaction | Low latency on OpenJDK builds | Same as ZGC niche; not in all builds |
| **Epsilon** | N/A (never collects) | Max (no GC) | Bounded by `-Xmx` | None | Benchmarks, ultra-short jobs, leak repro | Anything long-running |

### 8.2 Tune vs. add memory vs. change collector

| Symptom (from log/metrics) | First move |
|---|---|
| High young-GC frequency, short pauses, throughput fine | Often *fine* — leave it. If CPU-bound, **reduce allocation in code**. |
| Old gen grows and is never reclaimed; rising heap-after-full-GC | **Leak** — heap dump + MAT, not GC tuning. |
| Frequent Full GCs on G1, old gen genuinely full of live data | **Add heap** (`-Xmx`) — the live set doesn't fit. |
| Evac failures / to-space exhausted, ample total free | Lower IHOP / raise `G1ReservePercent`; if persistent, add heap. |
| Pauses too long for SLO, heap already right-sized | **Change collector** (G1 → ZGC/Shenandoah). |
| Long pauses but tiny GC times in log | **TTSP / host problem** (`-Xlog:safepoint`, check CPU throttling/swap), not GC. |
| Premature promotion (low dynamic tenuring threshold) | Enlarge young/survivor or reduce medium-lived allocation. |
| Humongous regions climbing | Raise `G1HeapRegionSize` or reduce big-array allocation. |
| Container OOM-killed (exit 137), no Java OOM | RSS > limit — **lower heap %, account for off-heap**, NMT. |

**Rule of thumb hierarchy:** (1) fix the code/allocation, (2) size the heap correctly, (3) pick the right collector, (4) *only then* tune individual flags — and change **one knob at a time**, re-measuring against the log each time.

### 8.3 When *not* to tune

If GC overhead is < ~5% of CPU and pauses meet SLO, **stop** — further tuning has negative expected value (risk > reward). "Good enough" is a valid, often optimal, end state.

---

## 9. Failure modes & debugging

### 9.1 The classic memory leak

**Symptom:** heap-used-immediately-after-each-Full/old-GC rises monotonically over hours/days; eventually `OutOfMemoryError: Java heap space`, increasingly frequent Full GCs beforehand ("GC thrashing": app spends most time in GC reclaiming almost nothing — `-XX:GCTimeLimit`/`GCHeapFreeLimit` may trigger `OutOfMemoryError: GC Overhead limit exceeded` on Parallel).
**Diagnose:** plot heap-after-old-GC (from log or MXBean). If rising → leak. Get a heap dump (`-XX:+HeapDumpOnOutOfMemoryError` or `jcmd GC.heap_dump`), open in **Eclipse MAT**, run **Leak Suspects**, inspect **dominator tree** and **GC roots path** of the biggest retained set. Common culprits: unbounded caches/`Map`s, `ThreadLocal`s not removed (esp. in thread pools), listeners never deregistered, classloader leaks on redeploy.
**Fix:** in code. No flag fixes a leak; more heap only delays it.

### 9.2 Premature promotion → old-gen pressure

**Symptom:** old gen fills fast, frequent mixed/full GCs, but the app's true long-lived set is small.
**Diagnose:** `-Xlog:gc+age=trace` shows a low dynamic tenuring threshold and most survivor bytes failing to die before promotion; `Old regions` grows each young GC then is reclaimed by mixed GC (churn).
**Fix:** enlarge young gen / survivor space; reduce medium-lifetime allocations (e.g., per-request buffers held slightly too long).

### 9.3 Humongous allocation storm

**Symptom:** `Humongous regions` climbs, occasional Full GCs from "humongous allocation" cause, fragmentation despite free heap.
**Diagnose:** `-Xlog:gc+humongous=debug`; find the code allocating big arrays (allocation profiler).
**Fix:** raise `G1HeapRegionSize`; chunk/pool large buffers; for ZGC this concern largely disappears.

### 9.4 To-space exhausted / evacuation failure

**Symptom:** `Evacuation Failure` in log, pause spikes to seconds, often a Full GC after.
**Diagnose:** check old-gen occupancy at the failing pause; check if IHOP fired late; check allocation spikes.
**Fix:** more heap, raise `G1ReservePercent` (e.g., 15–20), lower initial IHOP, reduce allocation bursts.

### 9.5 Long pause but tiny GC time (the TTSP trap)

**Symptom:** users see 200 ms freezes; GC log shows 15 ms pauses.
**Diagnose:** `-Xlog:safepoint` reveals long time-to-safepoint or non-GC safepoint operations (biased-lock revocation pre-JDK 15, deoptimization, `jstack`/`jcmd` storms). Also check `Real` ≫ `User+Sys` in the `gc,cpu` line → host-level stall (CPU throttling in cgroups, **swapping** — *never* let a JVM swap; it turns ms pauses into seconds, page-fault storms, noisy neighbor).
**Fix:** address the host (no swap, raise CPU quota, `AlwaysPreTouch`, large pages), eliminate the long-running counted loop blocking safepoints (loop strip mining helps), reduce safepoint-inducing operations.

### 9.6 Container OOM-kill with no Java OOM (exit 137)

**Symptom:** pod restarts, exit code 137 (SIGKILL by the OOM killer), no `.hprof`, no Java stack.
**Diagnose:** RSS exceeded cgroup limit from *off-heap* growth — direct ByteBuffers, thread stacks (thread leak), Metaspace, native libs (e.g., Netty pooled allocators, JNI). Enable `-XX:NativeMemoryTracking=summary`, run `jcmd <pid> VM.native_memory summary` and watch which category grows; check thread count.
**Fix:** lower heap %, cap direct memory (`-XX:MaxDirectMemorySize`), cap Metaspace, fix the thread/buffer leak, leave RSS headroom.

### 9.7 Real-world incident sketches (composite, representative)

- **"The 11pm latency cliff."** p99 spiked nightly. GC log showed soft-reference-cache entries surviving until heap pressure, then a Full GC at the daily traffic peak. Fix: replaced soft-ref cache with a size-bounded Caffeine cache; cliff gone. *Lesson: soft refs are not a cache eviction policy.*
- **"The phantom 137s."** A service OOM-killed under load with no Java OOM. NMT showed direct-buffer memory climbing — a Netty `ByteBuf` leak (missing `release()`). `-Xmx` was the full pod limit, so off-heap had no room. Fix: leak fix + 30% RSS headroom. *Lesson: RSS ≠ heap.*
- **"Tuning made it worse."** Someone set `MaxGCPauseMillis=10` to "make it faster." G1 shrank young gen drastically; young GCs went from 50/min to 600/min; CPU in GC tripled; throughput collapsed. Fix: removed the flag (back to default 200), then *reduced allocation* in code. *Lesson: the pause goal is a constraint solver input, not a wish.*
- **"It says 5 ms, users say 2 seconds."** GC pauses were tiny; `Real ≫ User+Sys`. The pod's CPU was cgroup-throttled (`nr_throttled` rising) during GC, and the node was swapping. Fix: raised CPU limit, disabled swap. *Lesson: read the cpu line; the GC was innocent.*

### 9.8 The standard debugging loop

1. **Confirm GC is implicated:** `jstat -gcutil` / metrics — is GC time/pause actually high, or is it the host (cpu line, TTSP)?
2. **Read the log:** classify the collections (young/mixed/full), measure pause distribution, allocation rate, promotion rate, heap-after-GC trend, humongous count.
3. **Form one hypothesis** (leak / undersized / premature promotion / humongous / host).
4. **Get corroborating evidence:** heap dump (leak), allocation profile (rate), NMT (off-heap), safepoint log (TTSP).
5. **Change one thing**, redeploy/load-test, re-read the log.
6. **Stop when SLO met** and GC overhead acceptable.

---

## 10. Interview drill

**Q1. What's the difference between a minor, major, mixed, and full GC?**
*Model answer:* Minor (young) collects only the young gen, STW, fast, frequent — cost is proportional to surviving objects. Major usually means an old-gen collection (often loosely "full"). Full GC collects and compacts the entire heap, STW, slow; on G1/ZGC it's a *fallback* indicating the concurrent machinery failed. Mixed (G1-specific) collects all young plus a chosen subset of old regions, incrementally reclaiming old gen.
- *Probe: Why is young collection cheap regardless of garbage volume?* Because copying collectors cost is proportional to *live* data copied; dead objects are reclaimed in O(1) by declaring the region empty.
- *Probe: Why are Full GCs bad on G1 specifically?* They mean concurrent marking + mixed GC couldn't keep up (or evac/humongous failure); it's a long whole-heap STW compaction that G1 is designed to avoid.
- *Probe: What triggers a mixed GC?* Completion of a concurrent marking cycle (started at IHOP); subsequent young pauses become mixed until `G1HeapWastePercent` is reached.

**Q2. How do you enable and read GC logs in JDK 17?**
*Model answer:* `-Xlog:gc*` (plus `gc+age=trace` for tenuring, `safepoint` for TTSP), with `:file=...:decorators:filecount/filesize` for production rotation. Read: per-collection type and cause, phase breakdown (evacuate dominates young), heap before→after(total) and pause ms on the summary line, Eden/Survivor/Old/Humongous region transitions, and the `User/Sys/Real` cpu line.
- *Probe: What does `Real ≫ User+Sys` tell you?* The pause waited on the host (page faults, swap, CPU throttling), not on GC computation.
- *Probe: How is JDK 8 logging different?* `-XX:+PrintGCDetails` family with a totally different format; tools must know which they parse.
- *Probe: Does logging hurt performance?* Negligibly; always run it in prod.

**Q3. You see old-gen heap-after-GC rising steadily over days. What is it and how do you confirm?**
*Model answer:* Almost certainly a memory leak (unbounded retention). Confirm by plotting heap-used-after-old/full-GC — monotonic rise = leak. Capture a heap dump and analyze in Eclipse MAT (Leak Suspects, dominator tree, GC-root path of the largest retained set). No GC flag fixes it.
- *Probe: Common leak sources?* Unbounded caches/maps, `ThreadLocal` in pooled threads, underegistered listeners, classloader leaks on redeploy.
- *Probe: How do you capture a dump without waiting for OOM?* `jcmd <pid> GC.heap_dump <file>` (or `jmap -dump:live`).
- *Probe: Why does adding heap not fix it?* It only delays OOM; retention is still unbounded.

**Q4. What is premature promotion and how do you detect it?**
*Model answer:* Objects promoted to old gen before they die, because survivor space is too small for the survivor volume, so G1 lowers the dynamic tenuring threshold. Detect with `-Xlog:gc+age=trace`: a low "new threshold" (≪ 15) and survivor bytes not dying before promotion; old gen grows each young GC. Fix: enlarge young/survivor or reduce medium-lived allocation.
- *Probe: What controls the dynamic threshold?* `TargetSurvivorRatio` (default 50%) — when cumulative survivor bytes exceed that fraction of survivor capacity, the threshold drops.
- *Probe: Why is it harmful?* It fills old gen, triggering more expensive old/full collections.

**Q5. (Senior signal) When would you choose Parallel GC over G1, and vice versa?**
*Model answer:* Parallel for throughput-only batch/ETL with no latency SLO — it minimizes total GC CPU at the cost of long rare pauses. G1 (or ZGC) for interactive services with latency SLOs, where bounded pauses matter more than peak throughput. The decision is governed by the throughput-latency-footprint trilemma and the SLO, not by "newer is better."
- *Probe: When would you escalate from G1 to ZGC?* When p99 pause requirements are sub-millisecond / heaps are very large, and you can afford the barrier CPU overhead and extra headroom.
- *Probe: When is Serial actually right?* Single-core or tiny-heap containers/sidecars, where G1's parallel/concurrent threads have no cores to run and just add overhead.

**Q6. (Senior signal) A service has p99 spikes. The GC log shows 10 ms pauses. Where do you look?**
*Model answer:* The GC isn't the cause as measured. Check `Real` vs `User+Sys` in the gc,cpu line (host stall), enable `-Xlog:safepoint` for time-to-safepoint, check for swapping (`vmstat`/`free`), CPU cgroup throttling (`nr_throttled`), and non-GC safepoint operations. Also verify the *measurement boundary* — TTSP and queueing (Little's law / coordinated omission) can hide latency outside the GC pause itself.
- *Probe: What causes long TTSP?* Tight counted loops without safepoint polls, long JNI calls, page faults; biased-lock revocation on older JDKs.
- *Probe: Why never let a JVM swap?* Page faults turn ms pauses into seconds; GC touches memory broadly, so swap thrashes catastrophically.

**Q7. (Senior signal) How do you decide whether to add memory, tune flags, or change collector?**
*Model answer:* Read the log first. Rising retained set with no reclamation → leak (fix code). Old gen full of genuinely live data + Full GCs → add heap. Pauses exceed SLO with right-sized heap → change collector. Evac failures with free heap → lower IHOP / raise reserve. Premature promotion → resize young. Only after code/heap/collector are right do you touch individual flags, one at a time, re-measuring. Stop when SLO is met and GC overhead < ~5%.
- *Probe: Why "one knob at a time"?* Flags interact; batched changes are unattributable and unreproducible.
- *Probe: When do you stop tuning?* When the SLO is met and overhead is low — further tuning is negative-expected-value risk.

**Q8. What are humongous objects and why do they matter?**
*Model answer:* In G1, objects ≥ 50% of region size are humongous, allocated into contiguous Humongous regions in old gen, bypassing Eden. They matter because they fragment the heap (need contiguous free regions), can fail to allocate even with free space, and historically triggered full GCs. Diagnose via `Humongous regions` climbing / `-Xlog:gc+humongous`. Fix by raising `G1HeapRegionSize` or reducing big-array allocation.
- *Probe: How does region size relate?* Doubling region size halves which objects count as humongous (threshold is 50% of region).
- *Probe: Does ZGC have this issue?* Not in the same way — it uses different page sizes (small/medium/large) without the contiguous-fragmentation pitfall.

**Q9. What is IHOP and what happens if it's set wrong?**
*Model answer:* Initiating Heap Occupancy Percent — the old-gen occupancy at which G1 starts a concurrent marking cycle (default 45%, adaptive since JDK 9). Too high/late → old gen fills before marking+mixed reclamation completes → evac failure → Full GC. Too low/early → cycles run more often, wasting CPU. Adaptive IHOP computes it from measured allocation rate and marking time.
- *Probe: How do you fix late cycles?* Lower initial IHOP, raise `G1ReservePercent`, or add heap.
- *Probe: When disable adaptive IHOP?* Rarely — when the adaptive heuristic misbehaves and you want a fixed value (`-XX:-G1UseAdaptiveIHOP`).

**Q10. How do you measure allocation rate and promotion rate, and why care?**
*Model answer:* Allocation rate ≈ (Eden size × young-GC count) / time; promotion rate ≈ (old-gen growth per young GC) × frequency — computable from `jstat -gc`, the log's region transitions, JFR allocation events, or GCeasy. Care because allocation rate drives young-GC frequency (and thus CPU) and promotion rate drives old-gen fill (and thus expensive collections); both predict behavior at higher load for capacity planning.
- *Probe: How to reduce allocation rate?* Object reuse/pooling, primitive collections, avoid boxing, stream don't materialize — found with an allocation profiler.
- *Probe: Tool to find *what* allocates?* async-profiler `-e alloc` or JFR allocation profiling + JMC.

**Q11. (Tooling) What does `System.gc()` do and should you allow it?**
*Model answer:* Requests a Full GC. Usually harmful to latency. `-XX:+ExplicitGCInvokesConcurrent` makes it concurrent under G1; `-XX:+DisableExplicitGC` ignores it — but some libraries (NIO direct-buffer cleanup, RMI DGC) rely on it, so disabling can leak native memory. Audit dependencies first.
- *Probe: Why does NIO rely on it?* Direct ByteBuffer native memory is freed when the Java wrapper is GC'd; a forced GC reclaims it under pressure.

**Q12. What's the difference between throughput and latency collectors, in one sentence each, with an example?**
*Model answer:* Throughput collector (Parallel) maximizes useful-work fraction by accepting long rare STW pauses — great for an overnight Spark/ETL job. Latency collector (ZGC/Shenandoah, G1 to a degree) minimizes individual pause length via concurrent work, paying CPU/footprint — great for a payments API with a 50 ms p99 SLO.
- *Probe: Can you have both?* Only by trading footprint/CPU — the trilemma; pick the two that matter for the workload.

---

## 11. Glossary

- **Allocation rate:** bytes/sec the app allocates; primary driver of young-GC frequency.
- **Allocation stall (ZGC):** mutator pause when allocation outruns concurrent relocation.
- **AlwaysPreTouch:** flag that commits all heap pages at startup to avoid first-touch faults.
- **Card / Card table:** small (512 B) heap chunk and the bitmap marking which cards were written (dirtied) — feeds RSet maintenance.
- **Collection Set (CSet):** regions chosen to be collected in a given pause.
- **Colored pointer (ZGC):** GC metadata bits stored inside the object pointer.
- **Compaction:** moving live objects together to eliminate fragmentation.
- **Concurrent cycle / marking:** GC work done alongside the running app (mostly non-STW).
- **Eden:** young-gen area where new objects are allocated.
- **Ergonomics:** the JVM's automatic default selection of collector/heap based on machine sizing.
- **Evacuation / copying:** moving live objects out of a region so the region can be freed.
- **Evacuation failure / to-space exhausted:** no free regions to copy survivors into; very expensive.
- **Floating garbage:** objects that became dead during concurrent marking but are kept alive that cycle (SATB).
- **Footprint:** total memory used.
- **Full GC:** STW whole-heap collection + compaction; a fallback on G1/ZGC.
- **G1 (Garbage First):** region-based, pause-goal, mostly-concurrent default server collector.
- **GC roots:** the starting references for reachability — thread stacks, statics, JNI handles, etc.
- **Generational hypothesis (weak):** most objects die young.
- **Heap:** JVM-managed region where objects live.
- **Heap dump (.hprof):** snapshot of all heap objects for offline analysis (e.g., MAT).
- **Humongous object/region (G1):** object ≥ 50% of region size, allocated specially in contiguous old-gen regions.
- **IHOP:** Initiating Heap Occupancy Percent — old-gen occupancy that starts G1's concurrent cycle.
- **JFR (Java Flight Recorder):** low-overhead event recorder incl. GC/allocation/safepoint events.
- **Latency:** length of individual pauses experienced by the app.
- **Load barrier (ZGC/Shenandoah):** code on every reference load that fixes up relocated objects.
- **Major GC:** old-gen collection (often loosely "full GC").
- **Metaspace:** off-heap native memory for class metadata (replaced PermGen in Java 8).
- **Minor GC:** young-gen-only collection.
- **Mixed GC (G1):** young + selected old regions in one pause.
- **Mutator:** the application (from the collector's viewpoint).
- **NMT (Native Memory Tracking):** JVM feature exposing off-heap memory usage breakdown.
- **Old / Tenured generation:** where long-lived objects reside.
- **Parallel GC:** multi-threaded STW throughput collector (JDK 8 default).
- **Pause-time goal (`MaxGCPauseMillis`):** soft target G1/Parallel size collections toward.
- **Premature promotion:** objects tenured before they die due to small survivor space.
- **Promotion / tenuring:** moving a survivor from young to old gen after enough collections.
- **Region (G1):** equal-sized heap unit (1–32 MB) tagged Eden/Survivor/Old/Humongous/Free.
- **Remembered Set (RSet):** per-region index of incoming cross-region references.
- **RSS (Resident Set Size):** OS-measured physical memory of the process; what container OOM-kill watches.
- **Safepoint:** execution point where thread state is fully known so GC can run safely.
- **SATB (Snapshot-At-The-Beginning):** G1's correct concurrent-marking algorithm using a write barrier.
- **Serial GC:** single-threaded STW collector for tiny/single-core environments.
- **Shenandoah:** concurrent low-pause collector (OpenJDK/Red Hat).
- **SoftMaxHeapSize (ZGC):** soft heap cap leaving concurrency headroom.
- **Stop-the-world (STW):** interval where all mutator threads are paused.
- **Survivor space (S0/S1):** young-gen areas holding objects that survived ≥1 minor GC.
- **TLAB (Thread-Local Allocation Buffer):** per-thread Eden slice for lock-free bump allocation.
- **Tenuring threshold:** object age at which it's promoted to old gen.
- **Throughput:** fraction of time doing app work rather than GC.
- **TTSP (Time-To-Safepoint):** time from a safepoint request until all threads stop.
- **Unified Logging (`-Xlog`):** JDK 9+ logging framework used for GC logs.
- **Write barrier:** bookkeeping code run on every reference store, used by RSet/SATB.
- **ZGC:** concurrent, colored-pointer, sub-ms-pause collector for low latency / huge heaps.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Enable logs (JDK 9+):**
`-Xlog:gc*,gc+age=trace,safepoint:file=gc.log:utctime,pid,tid,level,tags:filecount=10,filesize=50m`

**Read a young pause:** type+cause → phase (evacuate dominates) → `before->after(total) Xms` (the SLO number) → region lines (Eden→0 normal; Old growth = promotion; Humongous climb = bad) → `User/Sys/Real` (`Real≫User+Sys` = host stall, not GC).

**Key signals:** pause time (p99), GC frequency, **allocation rate**, **promotion rate**, **heap-used-after-old-GC trend** (rising = leak), humongous region count, **Full-GC count (should be 0 on G1/ZGC)**, TTSP.

**Defaults to remember:** G1 default since JDK 9; `MaxGCPauseMillis=200` (G1); IHOP base 45% (adaptive); `MaxTenuringThreshold=15`; `SurvivorRatio=8`; `TargetSurvivorRatio=50`; `G1ReservePercent=10`; `G1HeapWastePercent=5`; region 1–32 MB; humongous ≥ 50% region; `MaxRAMPercentage=25`. *(Flag your JDK version — these shift.)*

**Decision rules:** rising retained set → **leak (heap dump, MAT)**; old gen full of live data + Full GCs → **add heap**; pauses > SLO, heap right → **change collector (ZGC/Shenandoah)**; evac failure w/ free heap → **lower IHOP / raise reserve**; humongous climb → **bigger region size**; tiny GC but big freeze → **host/TTSP, not GC**; exit 137, no Java OOM → **RSS>limit, off-heap, NMT**. Always: **read log → one hypothesis → one knob → re-measure → stop at SLO**.

**Collector pick:** batch/throughput → Parallel; general server → G1; sub-ms/huge heap → ZGC/Shenandoah; tiny/1-core → Serial; benchmark/repro → Epsilon.

**Anti-patterns:** blog-copied flags; `-Xmn` under G1; tuning before reading logs; tiny `MaxGCPauseMillis`; soft-ref caches; `System.gc()`; RSS=heap assumption; disabling GC logs.

**Field tools:** `jstat -gcutil <pid> 1000`; `jcmd <pid> GC.heap_info|GC.heap_dump|GC.class_histogram|VM.native_memory`; async-profiler `-e alloc`; JFR+JMC; GCeasy/GCViewer/garbagecat for logs; MAT for dumps.

### 12.2 Self-test (no answers — recall actively)

1. You're handed a 20 GB-heap service with a 30 ms p99 SLO that currently shows 300 ms G1 pauses on a right-sized heap. Walk through your decision and the exact flags you'd change first, and why.
2. From a single G1 young-pause log block, name every place you'd look to distinguish (a) a leak, (b) premature promotion, (c) a host stall, and (d) a humongous problem.
3. Derive allocation rate and promotion rate from `jstat -gc` output, stating the exact arithmetic and which columns you use.
4. Explain why setting `-XX:MaxGCPauseMillis=10` can *reduce* throughput and even *worsen* total pause time, in terms of G1's young-gen sizing.
5. A pod is OOM-killed (exit 137) with no `.hprof` and no Java `OutOfMemoryError`. Enumerate the off-heap memory categories that could be responsible and the exact commands you'd run to identify the culprit.
6. Contrast how G1 and ZGC each avoid long pauses, naming the specific mechanisms (regions/RSets/SATB vs. colored pointers/load barriers) and the cost each pays.
7. Given `Real` is 5× `(User+Sys)` on the `gc,cpu` line, list at least four distinct host/environment causes and how you'd confirm each.

# GC Algorithms (G1, ZGC, Shenandoah, Parallel)

> A definitive engineering-handbook chapter on JVM garbage-collection algorithms — from first principles to the deepest internals, tuning knobs, and production failure stories. Java/JVM backend focus, current through JDK 21/23 with notes flagged for older versions.

---

## 1. Overview & where it fits

### What garbage collection is

A **garbage collector (GC)** is the part of the JVM runtime that automatically reclaims **heap** memory occupied by objects the program can no longer reach. In languages like C you call `malloc`/`free` yourself; in Java you `new` an object and the GC decides when its memory can be reused. The GC's job is to find **live** objects (reachable from a set of roots) and recycle everything else.

Two terms you must internalize from the start:

- **Heap**: the region of process memory where all Java objects and arrays live. It is shared across all threads. (Contrast with the **stack**, which is per-thread and holds method frames, local primitives, and *references* into the heap.)
- **Reachability / liveness**: an object is **live** if it can be reached by following references starting from the **GC roots** — local variables on thread stacks, static fields, JNI references, etc. An object that is unreachable is **garbage**, even if some other garbage object still points to it. (This is why GCs detect cycles of dead objects that simple reference-counting cannot.)

### The problem GC algorithms solve

Reclaiming memory automatically sounds simple, but it is a brutal engineering tradeoff space. Every collector juggles three goals that are in tension — you cannot maximize all three:

1. **Latency / pause time**: how long application threads are frozen while the GC works. A pause where *all* application threads stop is a **stop-the-world (STW)** pause. Long pauses cause request-latency spikes, missed SLAs, and timeouts.
2. **Throughput**: the fraction of total CPU time spent running *your* code rather than GC. A batch job that processes 10 TB cares about throughput, not a 200 ms pause.
3. **Footprint**: how much memory (and CPU) the collector itself consumes — extra heap headroom, metadata tables, barrier overhead, GC threads.

This is sometimes called the **GC trilemma** or the **"pick two"** problem (informally — in practice you trade smoothly, not discretely). Different algorithms are different points in this space:

- **Parallel GC**: maximizes throughput, accepts long STW pauses.
- **G1**: balanced; bounded *target* pauses with good throughput.
- **ZGC / Shenandoah**: minimize pause time (sub-millisecond / low-single-digit-ms) at some throughput and footprint cost.
- **Serial GC**: minimal footprint and CPU, single-threaded, for tiny heaps/containers.

### When you reach for each (the 10-second version)

| Workload | Reach for |
|---|---|
| Tiny heap (< ~100 MB), single CPU, CLI tools, small containers | **Serial GC** |
| Batch / throughput-bound, pauses don't matter, big iron | **Parallel GC** |
| General-purpose services, heaps ~4–64 GB, want bounded pauses | **G1** (the default) |
| Latency-critical, large heaps (tens of GB to TB), need ~1 ms pauses | **ZGC** |
| Latency-critical, similar to ZGC, OpenJDK/Red Hat ecosystems | **Shenandoah** |

### One-paragraph mental model

Think of the heap as a warehouse. **Live objects are crates someone still has a receipt for** (a reference chain back to a root); everything else is trash. A collector must (a) figure out which crates are still claimed (**marking**), (b) free the floor space of the unclaimed ones (**sweeping/reclaiming**), and often (c) slide the surviving crates together to remove gaps (**compaction**, which prevents fragmentation and enables fast bump-pointer allocation). The hard part is doing all this while the warehouse is still operating — forklifts (application threads) are moving crates around mid-cleanup. **Older collectors stop all forklifts (STW) while they work; modern collectors (ZGC, Shenandoah) keep the forklifts running and use clever tricks — colored pointers, read/write barriers — so that even if a crate is moved out from under a worker, the worker is transparently redirected to the crate's new location.** That trick — *concurrent relocation with the application running* — is the central innovation separating modern low-pause GCs from the classics.

---

## 2. Foundations from first principles

This section builds the conceptual vocabulary every GC algorithm reuses. If you already know mark-sweep, generations, and TLABs, skim — but the **tricolor invariant** and **barriers** subsections are essential for understanding ZGC/Shenandoah later.

### 2.1 Allocation: how objects get on the heap

When you write `new Foo()`, the JVM allocates space in the heap. The fast path is a **bump-the-pointer** allocation: the allocator keeps a pointer to the next free byte; allocating just returns that pointer and advances it by the object's size. This is O(1) and nearly free — *if* free memory is one contiguous region. Fragmentation breaks this, which is why compaction matters.

- **TLAB (Thread-Local Allocation Buffer)**: each application thread gets its own small chunk of the heap (the "Eden" area) to allocate from without locking. Bumping a thread-local pointer needs no synchronization, so allocation scales across cores. When a thread's TLAB is full, it grabs a new one (a synchronized operation, but rare). Flags: `-XX:+UseTLAB` (default on), `-XX:TLABSize`, `-XX:-ResizeTLAB` to fix the size.
- **Large objects** that don't fit a TLAB are allocated directly in a shared space (and in G1 become *humongous* — see §3).

### 2.2 Reachability and GC roots

The collector starts from **GC roots** and traces references transitively. Roots include:

- Local variables and operand-stack entries in every thread's active method frames.
- Static fields of loaded classes.
- JNI (Java Native Interface — the API for calling C/C++ from Java) global and local references.
- Active monitor locks, thread objects, the system class loader, interned strings, etc.

Anything reachable from a root is **live**; the rest is collected. Reachability handles cycles correctly (two dead objects pointing at each other are both unreachable from roots, so both are collected).

### 2.3 The two fundamental reclamation strategies

**Mark-Sweep**:
1. **Mark**: trace from roots, mark every reachable object.
2. **Sweep**: walk the heap, add unmarked regions to a free list.

Simple, no object movement — but leaves **fragmentation** (free memory in scattered holes), forcing slower free-list allocation and risking allocation failure even when total free bytes are sufficient.

**Mark-Compact** (or Mark-Sweep-Compact):
1. Mark (same as above).
2. **Compact**: slide all live objects to one end of the heap, updating every reference to point to the new locations.

No fragmentation, restores bump-pointer allocation — but moving objects and fixing up references is expensive and traditionally requires STW.

**Copying (scavenging)**:
- Divide space into two halves ("from" and "to"). Trace from roots, copying each live object into the "to" space, then flip. Dead objects are abandoned wholesale (no per-object sweep). Cost is proportional to *live* data, not total heap — great when most objects die young. Downside: needs 2× the space for the collected region.

### 2.4 The generational hypothesis

Empirically, **most objects die young** — request-scoped allocations, temporaries, iterators. This is the **weak generational hypothesis**. GCs exploit it by splitting the heap into:

- **Young generation**: where new objects are allocated. Collected frequently and cheaply with a copying collector. A young-gen collection is a **minor GC**. The young gen is usually subdivided into **Eden** (where allocation happens) and two **survivor spaces** (S0/S1) that hold objects surviving one or more minor GCs.
- **Old generation (tenured)**: objects that survive enough minor GCs are **promoted/tenured** here. Collected less often with mark-compact or concurrent algorithms. A collection touching the old gen is a **major GC**; a collection of the *entire* heap is a **full GC** (usually the worst-case, longest pause).

The threshold for promotion is the **tenuring threshold** (`-XX:MaxTenuringThreshold`, default up to 15): an object's "age" (number of minor GCs survived) crosses it and the object moves to old gen.

**Cross-generational references problem**: to collect the young gen alone, you must treat references *from old gen into young gen* as roots (otherwise you'd wrongly collect a young object an old object still uses). Scanning the whole old gen for such references would defeat the point. The solution:

- **Card table**: the old gen is divided into fixed-size chunks called **cards** (typically 512 bytes). A 1-byte-per-card array (the card table) marks a card "dirty" when a reference inside it is written that points into young gen. At minor GC, only dirty cards are scanned. The dirtying is done by a **write barrier** (see §2.6).
- **Remembered set (RSet)**: a more general structure (used heavily by G1) that records, per region, which other regions hold references *into* it. Lets the collector scan a region's incoming references without scanning the whole heap.

### 2.5 The tricolor abstraction (critical for concurrent GC)

Concurrent marking — marking while the application mutates the object graph — is reasoned about with the **tricolor marking** abstraction. Every object is conceptually one of three colors during a mark:

- **White**: not yet visited. At the end of marking, white = garbage.
- **Gray**: visited, but its outgoing references haven't all been scanned yet. (On the "to-do" / mark queue.)
- **Black**: visited *and* all its references scanned. Known live, done.

Marking proceeds by picking a gray object, scanning its references (turning white children gray), then coloring it black — until no gray remains. The danger when the application (the **mutator**) runs concurrently:

> **The lost-object problem**: a live white object can be wrongly collected if (1) the mutator inserts a reference from a **black** object to a **white** object, *and* (2) all paths from gray objects to that white object are destroyed before marking reaches it. The black object is "done" so the collector never re-scans it; the white object now has no other path; it stays white and is reclaimed though it is live. **Memory corruption.**

To prevent this you must break one of those two conditions. This is the **tricolor invariant**, and collectors enforce it with **barriers**:

- **Strong tricolor invariant**: no black object points to a white object. Enforced typically by a **write barrier** that, when the mutator stores a white reference into a black object, colors the target gray (so it will be scanned). This style is **incremental-update** marking. (G1's SATB is a different style — see below.)
- **Weak tricolor invariant / SATB (Snapshot-At-The-Beginning)**: the collector logically marks the object graph *as it existed when marking started*. Any reference about to be overwritten is recorded (the **pre-write barrier** logs the old value) so the object it pointed to stays live for this cycle even if the link is removed. G1 and Shenandoah use SATB. Consequence: objects that become garbage *during* the cycle are not collected until the next cycle (**floating garbage**) — a safe over-approximation.

### 2.6 Barriers (the machinery that makes concurrency possible)

A **barrier** is a small snippet of code the JIT (Just-In-Time compiler) injects around heap reads or writes. It is the tax you pay on every relevant memory operation so the GC can run concurrently.

- **Write barrier**: runs on reference *stores* (`a.field = b`). Used for card marking (generational), SATB pre-write logging (G1/Shenandoah), and incremental-update marking.
- **Read barrier (load barrier)**: runs on reference *loads* (`x = a.field`). Far more frequent than writes, so it must be extremely cheap. **ZGC and Shenandoah use load barriers to support concurrent relocation**: when you load a reference to an object that has been (or is being) moved, the barrier transparently fixes the reference to point to the new location. This is the secret to moving objects while the application runs.

The cost of barriers is a real, measurable throughput tax (often a few percent), which is why throughput-focused Parallel GC uses none on reads and the minimum on writes.

### 2.7 Safepoints and STW

A **safepoint** is a point in execution where all of a thread's references are in a known, consistent state (the JIT emits safepoint polls in loops, at method returns, etc.). To do a stop-the-world operation, the JVM requests a safepoint; each thread runs until its next poll and parks. **STW pause = time to reach the safepoint (time-to-safepoint, TTSP) + time doing the STW work + time to resume.** A thread stuck in a tight counted loop or a long native call can cause a **long time-to-safepoint**, a subtle and nasty pause source independent of the GC algorithm. Diagnose with `-XX:+PrintSafepointStatistics` (older) or `-Xlog:safepoint` (JDK 9+).

---

## 3. How it works internally

This is the heart of the chapter. We go collector by collector, tracing control flow, data flow, and state transitions.

### 3.1 Serial GC (`-XX:+UseSerialGC`)

The simplest collector: **single-threaded**, fully **stop-the-world**, generational.

- **Young gen**: copying collector. On Eden exhaustion → STW minor GC. Live Eden + live "from" survivor objects are copied into "to" survivor (or promoted to old if old enough/survivor full); Eden and "from" are then empty. Survivor roles flip.
- **Old gen**: **mark-sweep-compact** (the classic "MarkCompact"). STW. Phases: mark live, compute new addresses, update references, slide objects (compact).

Control flow of a minor GC:
1. Allocation fails (Eden full) → request safepoint, STW.
2. Scan roots (stacks, statics) + dirty cards (old→young refs).
3. Copy live young objects to survivor/old, fixing references.
4. Age survivors; promote those past tenuring threshold.
5. Resume.

When old gen fills → **full GC** (STW mark-compact of whole heap). Strengths: tiny footprint, no barriers beyond card marking, deterministic. Use for heaps under ~100 MB, single-vCPU containers, short-lived processes. It is also the **default in containers detected to have 1 CPU and < ~1792 MB heap** (the JVM's "ergonomic" selection — see §3.6).

### 3.2 Parallel GC (`-XX:+UseParallelGC`) — the throughput collector

Same generational, STW design as Serial, but uses **multiple threads** for both minor and old (full) collections. Historically the default before JDK 9.

- **Young gen**: parallel copying collector.
- **Old gen**: parallel **mark-summary-compact** (the "Parallel Old" collector, `-XX:+UseParallelOldGC`, on by default with ParallelGC). The heap is split into regions worked by multiple threads.

Key feature: **adaptive sizing / GC ergonomics** (`-XX:+UseAdaptiveSizePolicy`, on by default). You give it goals and it resizes generations and survivor spaces automatically:

- `-XX:MaxGCPauseMillis=N` — soft pause-time goal.
- `-XX:GCTimeRatio=N` — throughput goal; GC should consume at most `1/(1+N)` of time. Default `99` → GC ≤ 1% of time. This is why Parallel is the throughput champion: its *primary directive is to minimize total GC time*, with pauses as a secondary, soft concern.

Control flow is identical to Serial but multithreaded. There is **no concurrent phase** — every collection is fully STW. On a 32 GB old gen, a full GC can be **multiple seconds**. That's the price of its throughput.

Use for: offline batch, data pipelines, anything where total wall-clock/throughput matters and multi-second pauses are tolerable. It often *wins on throughput* against all other collectors because it has the least overhead (no concurrent marking, minimal barriers).

### 3.3 G1 GC (`-XX:+UseG1GC`) — the default since JDK 9

**G1 = Garbage-First.** A **region-based**, **generational**, **mostly-concurrent**, **incrementally-compacting** collector designed to meet a **soft pause-time target** on multi-GB heaps. It is the default and the one you'll operate most.

#### 3.3.1 Regions

G1 divides the heap into **equal-sized regions** (power of two, **1–32 MB**, auto-sized so there are roughly **2048 regions**; flag `-XX:G1HeapRegionSize`). Crucially, generations are **logical, not physically contiguous**: any region can be tagged **Eden**, **Survivor**, **Old**, or **Humongous** at a given time, and roles change between collections. This decoupling is what lets G1 collect *a subset of regions* per pause — "garbage first" means it preferentially collects the regions with the most garbage (best bang for the buck).

- **Humongous objects**: any object ≥ **50% of a region size** is "humongous" and allocated in one or more *contiguous* humongous regions (special-cased, allocated in old gen). Large humongous allocations are slow and historically triggered full GCs; many humongous objects fragment the heap. Watch for them (e.g., big `byte[]`/`long[]`). Reduce by increasing region size so the object fits comfortably under 50%.

#### 3.3.2 Remembered sets and the write barrier

Each region has a **remembered set (RSet)** recording which *other* regions hold references into it. This lets G1 collect a region by scanning only its RSet rather than the whole heap. The RSet is maintained by a **write barrier** that, on a cross-region reference store, enqueues the update into per-thread **dirty card queues**, drained by concurrent **refinement threads** (`-XX:G1ConcRefinementThreads`) that update RSets off the critical path. RSets are a notable **footprint cost** (can be several percent of heap, sometimes much more under adversarial reference patterns).

#### 3.3.3 The two kinds of G1 collection

1. **Young collection (minor, STW)**: collects all Eden + Survivor regions. Copying/evacuation: live objects are **evacuated** (copied) to new survivor or old regions; source regions freed entirely. Always STW but bounded because young set size is chosen to fit the pause target.

2. **Mixed collection (STW)**: collects all young regions **plus a selection of old regions** (the ones with the most garbage). This is how G1 reclaims old gen *incrementally* without a giant full-heap compaction. Mixed collections run in a series after a concurrent marking cycle identifies which old regions are worth collecting.

#### 3.3.4 The concurrent marking cycle (SATB)

When old-gen occupancy crosses the **Initiating Heap Occupancy Percent** (`-XX:InitiatingHeapOccupancyPercent`, **IHOP**, default 45%; since JDK 9 it's *adaptive* by default), G1 starts a **concurrent marking cycle** to find old-gen liveness. Phases (state machine):

1. **Initial Mark (STW)** — piggybacked on a young collection. Marks objects directly reachable from roots. (In newer JDKs the very first young-only step is just labeled within the cycle.)
2. **Root Region Scan (concurrent)** — scans survivor regions for references into old gen; must finish before the next young GC could overwrite them.
3. **Concurrent Mark (concurrent)** — traces the whole old-gen object graph using **SATB** (snapshot-at-the-beginning, §2.5). The **SATB pre-write barrier** logs overwritten references so the snapshot stays consistent. Produces floating garbage (safe).
4. **Remark (STW)** — finishes marking, drains SATB buffers, processes references (weak/soft/phantom). Short.
5. **Cleanup (STW + concurrent)** — accounts liveness per region, **reclaims completely empty old regions immediately**, and sorts remaining old regions by garbage ratio to build the **collection set (CSet)** candidates for upcoming mixed collections. Then **concurrent cleanup** frees RSets of reclaimed regions.

After the cycle, several **mixed collections** run to evacuate the chosen old regions, then G1 returns to young-only collections until IHOP triggers another cycle.

#### 3.3.5 Pause-time goal mechanics

`-XX:MaxGCPauseMillis` (**default 200 ms**) is a *soft target*. G1 builds a **prediction model** from recent collection history (cost per region scanned/copied, RSet sizes) and chooses how many regions (and how many old regions in mixed collections) to put in the CSet so the predicted pause fits the target. It is best-effort: setting an unrealistically low target just shrinks the young gen, increasing GC frequency and *hurting throughput* without truly hitting the target.

#### 3.3.6 Full GC in G1

A **full GC** in G1 is the failure mode: a single-threaded (pre-JDK 10) or parallel (JDK 10+, `-XX:+UseParallelGC`-style parallel full GC since JEP 307) **STW mark-compact of the entire heap**. Triggered by **evacuation failure** ("to-space exhausted" — no free regions to copy survivors into), humongous allocation failure, or marking not keeping up with allocation (**allocation outpaces reclamation**). Full GCs are slow and are the thing you tune to avoid.

### 3.4 ZGC (`-XX:+UseZGC`) — the sub-millisecond, concurrent-everything collector

**ZGC's design goal: pause times that do not grow with heap size or live-set size**, targeting **< 1 ms** (originally "< 10 ms," now routinely sub-ms), on heaps from a few hundred MB to **16 TB**. It achieves this by doing **all the heavy work concurrently**, including **compaction (relocation)** — the part G1 still does STW. Production-ready since **JDK 15** (`-XX:+UseZGC` without experimental flag); **generational ZGC** since **JDK 21** (`-XX:+UseZGC -XX:+ZGenerational`; non-generational deprecated for removal in later JDKs, with generational becoming the only/ default mode in JDK 23+/24).

#### 3.4.1 Colored pointers (the core trick)

ZGC stores **metadata bits inside the object reference (pointer) itself**, not in the object header. On 64-bit, only ~44 bits are needed to address 16 TB, leaving high bits free. ZGC reserves bits for GC color:

- **Marked0 / Marked1**: which mark cycle marked this reference (alternating to distinguish cycles).
- **Remapped**: whether the reference has already been fixed up to point to the relocated object.
- **Finalizable**: reachable only via finalization.
- (Original ZGC used 4 color bits + 42 address bits with **multi-mapping** — the same physical memory mapped at multiple virtual addresses so different-colored pointers dereference correctly.)

**Generational ZGC (JDK 21+) switched to a different scheme — "load barriers with store barriers" and stores color/age in a separate scheme** because the old multi-mapping approach didn't extend cleanly to generations; the *concept* of colored references remains but implementation details changed. (Flag this as version-specific — the bit layout is an implementation detail that has evolved.)

#### 3.4.2 The load barrier and concurrent relocation

Every reference **load** runs a **load barrier**. The barrier checks the color bits:

- If the reference is "good" (already remapped/marked for this cycle) → fast path, nothing to do (just a few instructions, usually a test-and-branch).
- If "bad" (object may have moved, or needs marking) → **slow path**: the barrier consults the **forwarding table** for the object's new address, **self-heals** the loaded reference (writes back the corrected, recolored pointer), and returns the good reference.

This is how ZGC relocates objects **while the application runs**: the collector can move an object and update its forwarding entry; any thread that later loads a stale reference is transparently corrected by the load barrier. No STW needed for the move itself.

#### 3.4.3 ZGC cycle (control/state flow)

Non-generational ZGC cycle (simplified) — note the only STW pauses are tiny and constant-time:

1. **Pause Mark Start (STW, ~µs–ms)**: scan **thread-stack roots only** (constant work, independent of heap size — this is why pauses don't grow with heap). Flip the marked color.
2. **Concurrent Mark**: traverse the object graph; load barrier drives marking. Multiple GC threads.
3. **Pause Mark End (STW, tiny)**: terminate marking, handle remaining roots.
4. **Concurrent Process References / weak roots**.
5. **Concurrent Reset Relocation Set + Select Relocation Set**: choose the regions ("ZPages") with the most garbage to evacuate.
6. **Pause Relocate Start (STW, tiny)**: scan roots, remap them.
7. **Concurrent Relocate**: copy live objects out of selected pages into new pages, building forwarding tables; load barriers fix up references lazily as the app touches them. Pages emptied are freed and reused immediately. References not yet touched by the app are remapped lazily *during the next cycle's mark* — ZGC piggybacks remapping onto the following mark traversal.

**Generational ZGC** adds a young and old generation, each with its own mark/relocate, plus inter-generational barriers and remembered sets, to avoid repeatedly scanning the whole (mostly-old) heap every cycle. This dramatically cuts CPU and improves allocation-rate tolerance versus single-generation ZGC.

#### 3.4.4 ZGC memory traits

- **Heap regions ("ZPages")** come in size classes: **small (2 MB), medium (32 MB), large (≥ a multiple, 2 MB-aligned)**. No fixed generations physically in non-generational mode.
- ZGC uses **multi-mapped virtual memory**; on Linux, ensure `vm.max_map_count` is high enough (and the older versions needed transparent-huge-page / `madvise` considerations).
- **Footprint**: ZGC trades memory for latency — it wants headroom to relocate concurrently. Expect higher footprint than G1.

### 3.5 Shenandoah (`-XX:+UseShenandoahGC`) — concurrent compaction, Red Hat lineage

**Shenandoah** is OpenJDK's other ultra-low-pause collector, developed primarily by Red Hat. Like ZGC it does **concurrent marking AND concurrent compaction (evacuation)**, aiming for pauses independent of heap size (low single-digit ms or sub-ms). It is region-based like G1. Available in many OpenJDK builds (Red Hat shipped backports to JDK 8/11); upstreamed; **not present in Oracle JDK builds** (a frequent gotcha — flag as vendor-specific). Originally **non-generational**; **generational Shenandoah** is being added (experimental in recent JDKs, `-XX:ShenandoahGCMode=generational`).

#### 3.5.1 Brooks pointers → load-reference barriers

Classic Shenandoah used a **Brooks forwarding pointer**: every object carries an extra word (a "forwarding pointer") that normally points to itself but, after the object is relocated, points to the new copy. All accesses indirect through it, so the app always reaches the current copy. This added a per-object word and a read indirection.

Modern Shenandoah (JDK 13+) replaced Brooks pointers with **load-reference barriers (LRB)**: a read barrier on reference loads that ensures the loaded reference points to the to-space copy (consulting forwarding info only when needed). This removed the per-object extra word and reduced overhead — conceptually similar to ZGC's load barrier, but Shenandoah stores forwarding info in the object header's mark word during evacuation rather than in pointer color bits.

#### 3.5.2 Shenandoah cycle

1. **Init Mark (STW, brief)**: scan roots, start concurrent mark.
2. **Concurrent Mark**: trace graph (SATB, with a pre-write barrier like G1).
3. **Final Mark (STW, brief)**: finish marking, choose **collection set** (most-garbage regions).
4. **Concurrent Cleanup**: reclaim regions that are entirely garbage.
5. **Concurrent Evacuation**: copy live objects from collection-set regions to new regions. App keeps running; LRB redirects accesses to relocated copies.
6. **Init Update Refs (STW, brief)** → **Concurrent Update References**: walk the heap and update all references to point to the new locations (so old copies can be freed). **Final Update Refs (STW, brief)**: update roots, retire collection-set regions.

So Shenandoah's STW pauses are only the brief root-scan/handoff points; marking, evacuation, and reference-updating are all concurrent.

#### 3.5.3 Shenandoah heuristics and modes

- **Heuristics** (`-XX:ShenandoahGCHeuristics=`): `adaptive` (default), `static`, `compact` (minimize footprint, more aggressive GC), `aggressive` (continuous GC, for testing). These decide *when* to start a cycle and *what* to put in the collection set.
- **Modes** (`-XX:ShenandoahGCMode=`): `normal`/`satb` (default), `iu` (incremental-update marking instead of SATB), `generational` (newer).

### 3.6 Defaults & ergonomic selection per JDK

The JVM picks a collector and heap sizes via **GC ergonomics** at startup based on machine class.

| JDK version | Default collector | Notes |
|---|---|---|
| JDK 8 | **Parallel GC** | G1 available; CMS available (`-XX:+UseConcMarkSweepGC`). |
| JDK 9–10 | **G1 GC** | G1 became the default (JEP 248). CMS deprecated (JEP 291, JDK 9). |
| JDK 11 | **G1 GC** | ZGC & Epsilon introduced as experimental. ZGC Linux/x64 only initially. |
| JDK 12 | **G1 GC** | Shenandoah arrives upstream (experimental); G1 abortable mixed collections (JEP 344). |
| JDK 14 | **G1 GC** | **CMS removed** (JEP 363). ZGC on macOS/Windows. Shenandoah/ZGC still experimental. |
| JDK 15 | **G1 GC** | **ZGC and Shenandoah become production/non-experimental** (JEP 377, 379). |
| JDK 17 (LTS) | **G1 GC** | All four (Serial/Parallel/G1/ZGC/Shenandoah*) production. *Shenandoah absent from Oracle builds. |
| JDK 21 (LTS) | **G1 GC** | **Generational ZGC** (JEP 439), opt-in via `-XX:+ZGenerational`. |
| JDK 23+ | **G1 GC** | Non-generational ZGC deprecated (JEP 474, JDK 23); generational becomes the ZGC default; goal to make `+ZGenerational` implicit. |

**Container/small-machine ergonomics**: if the JVM detects ~1 available CPU *and* a small max heap (historically `< 1792 MB`), it selects **Serial GC** even on modern JDKs. With ≥ 2 CPUs and ≥ ~1792 MB it selects **G1**. Default max heap is **25% of available RAM** (`-XX:MaxRAMPercentage=25`, container-aware since JDK 8u191/10+). Always set heap explicitly in production rather than relying on these.

CMS (Concurrent Mark Sweep), the *former* low-pause collector, is **removed since JDK 14** — mentioned here only because legacy systems and interviews still reference it. It was concurrent-mark + concurrent-sweep but **non-compacting**, so it suffered old-gen fragmentation leading to **promotion failure → STW full GC (single-threaded mark-compact)** — its fatal flaw, and the reason G1/ZGC/Shenandoah (which compact) replaced it.

---

## 4. The complete toolkit

### 4.1 Selecting a collector

| Flag | Collector | Available |
|---|---|---|
| `-XX:+UseSerialGC` | Serial | all |
| `-XX:+UseParallelGC` | Parallel (incl. Parallel Old) | all |
| `-XX:+UseG1GC` | G1 | JDK 7u4+ (default 9+) |
| `-XX:+UseZGC` | ZGC | JDK 11+ (prod 15+) |
| `-XX:+UseZGC -XX:+ZGenerational` | Generational ZGC | JDK 21+ |
| `-XX:+UseShenandoahGC` | Shenandoah | OpenJDK 12+ (prod 15+), backports to 8/11; not in Oracle JDK |
| `-XX:+UseEpsilonGC` | Epsilon (no-op, never collects) | JDK 11+ (experimental) — for benchmarking/short-lived jobs |

### 4.2 Universal heap & generation sizing

| Flag | Meaning | Default |
|---|---|---|
| `-Xms` / `-XX:InitialHeapSize` | Initial heap | ergonomic |
| `-Xmx` / `-XX:MaxHeapSize` | Max heap | 25% of RAM (`MaxRAMPercentage`) |
| `-XX:MaxRAMPercentage=N` | Max heap as % of RAM (container-aware) | 25 |
| `-XX:InitialRAMPercentage`, `-XX:MinRAMPercentage` | RAM-relative sizing | — |
| `-Xmn` / `-XX:NewSize`,`-XX:MaxNewSize` | Young gen size (not for ZGC) | ergonomic |
| `-XX:NewRatio=N` | old:young ratio | 2 (old = 2× young) |
| `-XX:SurvivorRatio=N` | eden:survivor ratio | 8 |
| `-XX:MaxTenuringThreshold=N` | promotion age | 15 (Parallel/Serial), G1 uses 15 max |
| `-XX:+AlwaysPreTouch` | touch all heap pages at startup (avoids first-touch page-fault latency) | off |

### 4.3 Common cross-collector goals

| Flag | Meaning | Default |
|---|---|---|
| `-XX:MaxGCPauseMillis=N` | soft pause goal | G1: 200; Parallel: none (unset) |
| `-XX:GCTimeRatio=N` | throughput goal: GC ≤ 1/(1+N) | 99 (Parallel), 12 (G1) |
| `-XX:ParallelGCThreads=N` | STW GC worker threads | ~⅝·CPU heuristic |
| `-XX:ConcGCThreads=N` | concurrent GC threads | ~¼·ParallelGCThreads |
| `-XX:+UseStringDeduplication` | dedup identical `char[]`/`byte[]` of Strings (G1/Shenandoah/ZGC) | off |

### 4.4 G1-specific

| Flag | Meaning | Default |
|---|---|---|
| `-XX:G1HeapRegionSize=N` | region size (1–32 MB, power of 2) | auto (~heap/2048) |
| `-XX:InitiatingHeapOccupancyPercent=N` (IHOP) | old-gen % to start conc. marking | 45 (adaptive by default 9+) |
| `-XX:-G1UseAdaptiveIHOP` | disable adaptive IHOP (use fixed) | adaptive on |
| `-XX:G1NewSizePercent`,`-XX:G1MaxNewSizePercent` | young gen bounds (% heap) | 5 / 60 |
| `-XX:G1ReservePercent=N` | reserve to avoid evacuation failure | 10 |
| `-XX:G1MixedGCLiveThresholdPercent=N` | only collect old regions below this liveness | 85 |
| `-XX:G1HeapWastePercent=N` | stop mixed GCs when reclaimable < this | 5 |
| `-XX:G1MixedGCCountTarget=N` | spread mixed GCs over N collections | 8 |
| `-XX:G1OldCSetRegionThresholdPercent` | max old regions per mixed GC | 10 |
| `-XX:G1ConcRefinementThreads=N` | RSet refinement threads | ~ParallelGCThreads |
| `-XX:+G1PrintRegionLivenessInfo` (diag) | per-region liveness | off |
| `-XX:G1PeriodicGCInterval=N` (ms) | periodic GC when idle to return memory | 0 (off) |

### 4.5 ZGC-specific

| Flag | Meaning | Default |
|---|---|---|
| `-XX:+UseZGC` | enable ZGC | — |
| `-XX:+ZGenerational` | generational mode (JDK 21+) | off (21), default later |
| `-XX:ZCollectionInterval=N` (s) | force a GC every N seconds | 0 |
| `-XX:ZAllocationSpikeTolerance=N` | headroom for allocation spikes | 2 |
| `-XX:+ZProactive` | proactive GC when idle | on |
| `-XX:+ZUncommit`,`-XX:ZUncommitDelay=N` | return unused heap to OS after delay | on / 300 s |
| `-XX:SoftMaxHeapSize=N` | soft heap cap ZGC tries to stay under | — |
| `-XX:ConcGCThreads=N` | concurrent GC threads (key ZGC tuning knob) | ergonomic |

ZGC ignores young-gen sizing flags (`-Xmn`, `NewRatio`) in non-generational mode and uses `SoftMaxHeapSize` / allocation-rate heuristics instead.

### 4.6 Shenandoah-specific

| Flag | Meaning | Default |
|---|---|---|
| `-XX:+UseShenandoahGC` | enable | — |
| `-XX:ShenandoahGCHeuristics=` | `adaptive`/`static`/`compact`/`aggressive` | adaptive |
| `-XX:ShenandoahGCMode=` | `satb`/`iu`/`generational` | satb |
| `-XX:ShenandoahUncommitDelay=N` (ms) | return memory after region idle | 5 min |
| `-XX:ShenandoahGuaranteedGCInterval=N` (ms) | force GC periodically | 5 min |

### 4.7 Logging & observability (unified logging, JDK 9+)

The single most important tool. **Unified logging** replaced the old `-XX:+PrintGCDetails` zoo.

| Command | Purpose |
|---|---|
| `-Xlog:gc` | basic GC lines |
| `-Xlog:gc*` | verbose, all GC subcomponents |
| `-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20m` | rotated GC log with timestamps |
| `-Xlog:gc+heap=debug` | heap region/sizing detail |
| `-Xlog:safepoint` | safepoint & time-to-safepoint |
| `-Xlog:gc+ergo*=trace` | ergonomic decisions (why G1 sized things) |
| `-Xlog:gc+phases=debug` | per-phase timings (G1) |
| (legacy JDK 8) `-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log` | old-style logs |

| Tool | Use |
|---|---|
| **`jstat -gc <pid> 1s`** | live per-generation occupancy & GC counts/times, sampled |
| **`jstat -gcutil <pid> 1s`** | same as % utilization |
| **`jcmd <pid> GC.heap_info`** | one-shot heap summary |
| **`jcmd <pid> GC.run`** | trigger a `System.gc()` |
| **`jcmd <pid> GC.heap_dump <file>`** | heap dump for analysis (MAT, VisualVM) |
| **`jcmd <pid> GC.class_histogram`** | live object histogram (find leaks) |
| **`jmap -histo:live <pid>`** | class histogram (forces a GC) |
| **JFR**: `-XX:StartFlightRecording=...` / `jcmd <pid> JFR.start` | low-overhead always-on recording incl. GC events, allocation, pauses |
| **GCeasy.io / GCViewer** | offline GC-log analyzers (pause histograms, throughput %, leaks) |
| **`-Xlog:gc+stats` (ZGC/Shenandoah)** | collector-internal statistics |

---

## 5. Code examples by use case

These are mostly *launch configurations and diagnostic snippets* (the "code" of GC tuning is mostly JVM flags + small Java to reproduce behavior). Java code is used where it illustrates GC mechanics.

### 5.1 Latency-critical microservice on a 16 GB heap (G1, then ZGC)

```bash
# G1 baseline: bounded pauses, good throughput. Set Xms=Xmx to avoid heap resizing pauses.
java -Xms16g -Xmx16g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \          # soft target; lower => smaller young gen, more frequent GC
     -XX:+AlwaysPreTouch \                # pay page-fault cost at startup, not under load
     -XX:InitiatingHeapOccupancyPercent=40 \  # start concurrent marking earlier under high alloc
     -Xlog:gc*:file=gc.log:uptime,level,tags:filecount=10,filesize=20m \
     -jar service.jar
```

If p99/p999 pauses still violate SLA, switch to ZGC (concurrent compaction → pauses independent of heap):

```bash
# ZGC for sub-millisecond pauses on the same service (JDK 21+, generational).
java -Xms16g -Xmx16g \
     -XX:+UseZGC -XX:+ZGenerational \
     -XX:+AlwaysPreTouch \
     -XX:SoftMaxHeapSize=14g \           # ZGC tries to stay under this, keeping headroom for relocation
     -Xlog:gc*:file=gc.log:uptime,level,tags \
     -jar service.jar
```

### 5.2 Throughput batch job (Parallel) — maximize total work

```bash
# Nightly ETL: pauses irrelevant, total runtime is everything.
java -Xms32g -Xmx32g \
     -XX:+UseParallelGC \
     -XX:GCTimeRatio=99 \                # allow GC at most 1% of time (i.e., 99% throughput target)
     -XX:+UseAdaptiveSizePolicy \        # let it resize generations toward the throughput goal
     -XX:ParallelGCThreads=16 \          # match to dedicated cores
     -jar etl.jar
```

### 5.3 Tiny container / sidecar (Serial)

```bash
# 1 vCPU, 256 MB heap: Serial avoids parallel/concurrent GC thread overhead.
java -Xms200m -Xmx200m \
     -XX:+UseSerialGC \
     -XX:MaxRAMPercentage=75 \           # in a memory-capped container, claim more of the cgroup limit
     -jar sidecar.jar
```

### 5.4 Reproducing & diagnosing a humongous-object problem (G1)

```java
// HumongousDemo.java — allocate arrays larger than 50% of the G1 region size to force
// humongous allocations and watch them cause early concurrent cycles / fragmentation.
public class HumongousDemo {
    public static void main(String[] args) throws InterruptedException {
        // With default ~2048 regions on a small heap, region size might be 1MB => humongous threshold 512KB.
        // A 1MB array is humongous. Allocate many to fragment old gen.
        java.util.List<byte[]> keep = new java.util.ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            byte[] big = new byte[1 * 1024 * 1024]; // 1 MB => humongous if region <= 2MB
            if (i % 3 == 0) keep.add(big);          // retain some to build pressure
            if (keep.size() > 2000) keep.subList(0, 1000).clear(); // churn
            Thread.sleep(0, 100_000);
        }
        System.out.println("retained=" + keep.size());
    }
}
```

```bash
# Run and observe "humongous" allocations and any "to-space exhausted"/Full GC events.
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:G1HeapRegionSize=2m \
     -Xlog:gc*,gc+humongous=debug:stdout:uptime,tags \
     HumongousDemo
# Fix: increase region size so the object is < 50% of a region (no longer humongous):
java -Xms1g -Xmx1g -XX:+UseG1GC -XX:G1HeapRegionSize=4m -Xlog:gc* HumongousDemo
```

### 5.5 Demonstrating the generational hypothesis & tenuring

```java
// AllocChurn.java — heavy short-lived allocation to show cheap minor GCs vs. promotion.
public class AllocChurn {
    static volatile Object sink; // prevent dead-code elimination
    public static void main(String[] args) {
        long end = System.currentTimeMillis() + 30_000;
        java.util.ArrayDeque<byte[]> survivors = new java.util.ArrayDeque<>();
        while (System.currentTimeMillis() < end) {
            sink = new byte[4096];                 // dies immediately -> collected in young gen
            if (Math.random() < 0.001) {           // 0.1% survive long enough to be promoted
                survivors.add(new byte[64 * 1024]);
                if (survivors.size() > 5000) survivors.poll();
            }
        }
        System.out.println("survivors=" + survivors.size());
    }
}
```

```bash
# Watch tenuring distribution: ages of objects in survivor space and what gets promoted.
java -Xms512m -Xmx512m -XX:+UseG1GC \
     -Xlog:gc+age=trace -Xlog:gc \
     AllocChurn
```

### 5.6 Programmatic GC monitoring via JMX (production dashboards)

```java
// GcMonitor.java — subscribe to GC notifications and log pause durations & causes.
import com.sun.management.GarbageCollectionNotificationInfo;
import javax.management.*;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

public class GcMonitor {
    public static void install() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            NotificationEmitter emitter = (NotificationEmitter) gc;
            emitter.addNotificationListener((Notification n, Object handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                        .equals(n.getType())) return;
                GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                long pauseMs = info.getGcInfo().getDuration(); // ms for STW collectors
                // Push to your metrics system (Micrometer/Prometheus) here:
                System.out.printf("GC[%s] cause=%s duration=%dms%n",
                    info.getGcName(), info.getGcCause(), pauseMs);
            }, null, null);
        }
    }
    public static void main(String[] a) throws Exception {
        install();
        // Allocate to generate GC activity:
        java.util.List<byte[]> l = new java.util.ArrayList<>();
        for (int i = 0; i < 200_000; i++) { l.add(new byte[8192]); if (l.size()>4000) l.clear(); }
        Thread.sleep(2000);
    }
}
```

> Note: for ZGC/Shenandoah the reported "duration" reflects the cycle, not user-visible STW pause; rely on `-Xlog:gc` pause lines or JFR `ZGC`/`pause` events for true pause time.

### 5.7 Epsilon GC for a known-short-lived job (no GC overhead)

```bash
# A short job that never needs to collect: Epsilon allocates until heap is full, then OOMs.
# Eliminates all GC overhead/barriers -> cleanest throughput & latency for the job's lifetime.
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC \
     -Xms8g -Xmx8g \
     -jar short_lived_job.jar
# Use only when you can guarantee total allocation < heap, or for allocation benchmarking.
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Throughput tax of concurrency**: load/write barriers cost CPU on every relevant access. Parallel GC has the least overhead and usually the **highest peak throughput**; ZGC/Shenandoah typically pay a few–~15% throughput vs. Parallel/G1 in allocation-heavy code, in exchange for tiny pauses. Generational ZGC narrowed this gap substantially.
- **Allocation rate is king.** Most GC pressure is driven by how fast you allocate. Reduce garbage (object pooling for hot paths, primitive arrays over boxed, avoid per-request allocations, reuse buffers) before tuning GC.
- **`-Xms == -Xmx`** in production: eliminates heap-resize pauses and gives predictable behavior. Pair with `-XX:+AlwaysPreTouch` to avoid first-touch page faults during traffic.
- **Right-size the young gen**: too small → frequent minor GCs and premature promotion (objects tenured before they die → old-gen pressure). Too large → longer minor pauses. For G1, prefer letting it adapt; only fix bounds if you observe pathologies.

### 6.2 Correctness / concurrency

- The GC's own correctness (the tricolor invariant via barriers, safepoints) is the JVM's job — you don't write it, but you can break performance with **bad time-to-safepoint** (tight counted loops with no safepoint poll, long JNI calls). Diagnose with `-Xlog:safepoint`.
- **`finalize()` and reference queues**: finalizers add latency and resurrect objects; avoid them. Prefer `java.lang.ref.Cleaner` (JDK 9+) or try-with-resources. Heavy **soft/weak/phantom reference** use slows reference-processing GC phases (`-XX:+ParallelRefProcEnabled` helps in G1).
- **`System.gc()`**: forces a full GC (huge pause) by default. In G1 it triggers a full GC unless `-XX:+ExplicitGCInvokesConcurrent` (run as a concurrent cycle instead). Consider `-XX:+DisableExplicitGC` to neuter library-triggered `System.gc()` — *but* this also disables the GC that frees direct `ByteBuffer` native memory, so weigh it. NIO direct buffers rely on GC + `Cleaner`; `-XX:MaxDirectMemorySize` caps them.

### 6.3 Memory & footprint

- **G1 RSets and ZGC forwarding/marking metadata cost real memory.** Budget ~5–15% overhead. ZGC/Shenandoah want extra headroom (≈ live-set + relocation slack) to relocate concurrently — undersizing the heap relative to allocation rate causes **allocation stalls** (the app blocks waiting for GC to free space).
- **Returning memory to the OS**: G1 does it at full GC or via `-XX:G1PeriodicGCInterval`; ZGC (`-XX:+ZUncommit`) and Shenandoah uncommit idle regions after a delay — valuable in containers/cloud to right-size. `-XX:SoftMaxHeapSize` lets ZGC run lean while keeping `-Xmx` as a hard ceiling.
- **Metaspace** (class metadata, off-heap) is separate from the GC algorithms here but causes its own OOM (`OutOfMemoryError: Metaspace`); cap with `-XX:MaxMetaspaceSize`.

### 6.4 Security

- **Heap dumps contain secrets** (passwords, tokens, PII in memory). Restrict `jcmd GC.heap_dump`/`jmap` access and the storage of dumps. `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...` is invaluable but the dumps are sensitive artifacts — secure and rotate them.
- **DoS via allocation**: untrusted input that drives allocation can OOM the JVM. Bound request sizes; consider per-tenant heap-pressure limits at the app layer.

### 6.5 Observability

- **Always log GC in production**: `-Xlog:gc*:file=...:filecount=N,filesize=M`. The cost is negligible and the data is irreplaceable post-incident.
- **Always-on JFR** (`-XX:StartFlightRecording=disk=true,maxsize=...,dumponexit=true`) captures GC, allocation, and pause data at < 1% overhead.
- Key metrics to dashboard: **GC pause p99/p999**, **GC throughput %** (1 − time-in-GC), **allocation rate (MB/s)**, **promotion rate**, **old-gen occupancy trend**, **humongous allocation count (G1)**, **allocation stall time (ZGC)**, **full GC count (should be ~0)**.

### 6.6 Cost

- More GC threads = more CPU = more cloud cost. In CPU-metered environments, ZGC/Shenandoah's concurrent threads consume cores continuously. Size `-XX:ConcGCThreads`/`-XX:ParallelGCThreads` against the cgroup CPU quota; the JVM is container-CPU-aware (`-XX:ActiveProcessorCount` to override if detection is wrong).

### 6.7 Testing

- **Load-test with production-like allocation patterns and heap size**, and watch the *tail* (p999), not the mean. A 99th-percentile that looks fine can hide a 2-second full GC at p9999.
- Use **`-Xlog:gc`** + **GCeasy/GCViewer** to validate throughput and pause distributions in staging before changing collectors in prod.
- Reproduce worst cases: trigger full GCs, force fragmentation, simulate allocation spikes (`-XX:ZAllocationSpikeTolerance`).

### 6.8 Anti-patterns

- Calling `System.gc()` in application code (or letting libraries do it).
- Setting an absurdly low `MaxGCPauseMillis` (e.g., 5 ms on G1) — shrinks young gen, increases GC frequency, *lowers* throughput, often doesn't even hit the target.
- Copy-pasting flag soups from blogs without understanding them; over-tuning before reducing allocation.
- Ignoring time-to-safepoint as a pause cause and blaming the collector.
- Using `-XX:+DisableExplicitGC` without accounting for direct-buffer reclamation.
- Picking ZGC/Shenandoah for a throughput batch job (you'll lose throughput for pause benefits you don't need).

---

## 7. Advanced topics & deep internals

### 7.1 G1 evacuation failure and "to-space exhausted"

When G1 has no free regions to copy survivors into during a young/mixed collection, it suffers **evacuation failure**: it must keep objects in place (self-forwarding), which is expensive and often cascades into a **full GC**. Causes: allocation rate exceeds reclamation, IHOP too high (marking starts too late), too little reserve (`-XX:G1ReservePercent`), or humongous fragmentation. Mitigations: start marking earlier (lower IHOP / keep adaptive), raise heap or reserve, reduce humongous objects, add `-XX:G1HeapWastePercent` tuning. JDK 8u40+ improved this; JDK 12 added **abortable mixed collections** (JEP 344) so G1 can bail out of a mixed collection that would overrun the pause goal.

### 7.2 G1 adaptive IHOP and prediction model

Since JDK 9, G1 adjusts the marking-start threshold based on observed marking duration and allocation rate, aiming to finish concurrent marking *just before* the heap fills. The prediction engine (`-Xlog:gc+ihop=debug`, `-Xlog:gc+ergo=trace`) tracks per-region copy/scan costs. Fixed IHOP (`-XX:-G1UseAdaptiveIHOP -XX:InitiatingHeapOccupancyPercent=N`) is sometimes needed for very bursty workloads where the predictor lags.

### 7.3 ZGC: why pauses are O(1) in heap size

The only STW work is **root scanning of thread stacks** (Mark Start, Mark End, Relocate Start), which is bounded by the number of threads and stack depth, **not** by heap or live-set size. Everything that *does* scale with heap (marking, relocation, reference updating) is concurrent. That's the architectural reason ZGC sustains sub-ms pauses on multi-TB heaps. Generational ZGC further cuts CPU by not re-marking the old gen every cycle. **Concurrent class unloading** and concurrent stack scanning (JDK 16+, JEP 376 moved ZGC thread-stack processing concurrent) shrank even those pauses.

### 7.4 ZGC self-healing & lazy remapping

The load barrier doesn't eagerly fix every reference; it **self-heals on access** and remaps the rest lazily during the *next* mark phase (ZGC overlaps the previous cycle's "remap" with the next cycle's "mark" — they're the same traversal). This amortizes relocation cost and avoids a separate STW update-refs phase that Shenandoah historically needed.

### 7.5 Shenandoah's update-references phase & passive/iu modes

Shenandoah historically needed an explicit **concurrent update-references** pass (and brief STW init/final-update-refs) because, unlike ZGC, it doesn't piggyback remapping on the next mark. The **`iu` (incremental-update) mode** uses a different marking invariant (strong tricolor via write barrier) that can collect more floating garbage per cycle than SATB at the cost of a more expensive barrier. **`compact` heuristic** trades latency/throughput for minimal footprint (aggressive uncommit) — useful in dense container deployments.

### 7.6 Colored pointers vs. forwarding word — the design fork

ZGC and Shenandoah solve the same problem (locate the current copy after concurrent relocation) differently:

- **ZGC**: metadata in the *pointer* (colored pointers) + forwarding tables + multi-mapping (legacy). No per-object overhead in the header for forwarding; load barrier reads pointer color.
- **Shenandoah**: forwarding info in the *object header* (mark word) during evacuation, accessed via load-reference barrier. Simpler memory model (no multi-mapping), works without spare pointer bits — historically why it ran on 32-bit-ish constraints and platforms where pointer coloring was awkward.

### 7.7 Compressed oops interaction

**Compressed ordinary object pointers (oops)** (`-XX:+UseCompressedOops`, default on for heaps ≤ ~32 GB) store 32-bit references that the JVM scales to reach a 32 GB heap, halving reference size and improving cache behavior. Crossing the ~32 GB boundary loses compressed oops (each reference becomes 8 bytes), so a 33 GB heap can hold *less usable data* than a 31 GB heap. **ZGC's colored pointers are incompatible with the classic compressed-oops scheme** (ZGC uses 64-bit pointers with color bits), which is part of why ZGC targets large heaps where compressed oops wouldn't apply anyway.

### 7.8 Soft/weak/phantom references and reference processing

Reference processing happens in the marking/remark phases. Heavy use (e.g., big soft-reference caches) lengthens these phases. `-XX:SoftRefLRUPolicyMSPerMB` controls how aggressively softrefs are cleared (ms of lifetime per free MB; default 1000). `-XX:+ParallelRefProcEnabled` parallelizes reference processing (recommended for G1 with many references).

### 7.9 NUMA awareness

On **NUMA** (Non-Uniform Memory Access — multi-socket machines where each CPU has faster access to its local RAM) systems, Parallel and G1 can be NUMA-aware (`-XX:+UseNUMA`) to allocate Eden close to the allocating thread's node, reducing cross-socket memory latency.

### 7.10 String deduplication

`-XX:+UseStringDeduplication` (G1, also Shenandoah/ZGC) detects `String`s with identical backing arrays during GC and points them at a single shared array, reclaiming memory in string-heavy apps. Runs as part of GC; controlled by `-XX:StringDeduplicationAgeThreshold`.

---

## 8. Tradeoffs & decision frameworks

### 8.1 The master comparison table

| Dimension | Serial | Parallel | G1 | ZGC (generational) | Shenandoah |
|---|---|---|---|---|---|
| **Threads** | single | multi (STW) | multi STW + concurrent | multi STW + concurrent | multi STW + concurrent |
| **Compaction** | yes (STW) | yes (STW) | yes (STW, incremental) | yes (**concurrent**) | yes (**concurrent**) |
| **Typical pause** | proportional to heap (can be long) | proportional to heap (seconds on big heaps) | bounded soft target (~10s–200+ ms) | **sub-millisecond** (O(1) in heap) | **low single-digit ms / sub-ms** |
| **Throughput** | low (1 thread) | **highest** | high | good (gen ZGC near G1) | good |
| **Footprint / overhead** | **lowest** | low | medium (RSets) | higher (metadata, headroom) | higher (forwarding, headroom) |
| **Heap sweet spot** | < ~100 MB | any; great for huge throughput jobs | ~**2–64 GB** general purpose | **multi-GB to 16 TB**, latency-critical | similar to ZGC, latency-critical |
| **Barriers** | card write only | minimal write | SATB pre-write + RSet write | **load barrier** (+ store, gen) | **load-reference barrier** + SATB write |
| **JDK availability** | all | all | 7u4+ (default 9+) | 11+ (prod 15+, **gen 21+**) | OpenJDK 12+ (prod 15+); **not Oracle JDK** |
| **Default in** | tiny/1-CPU containers | JDK 8 | JDK 9+ | — | — |
| **Use when** | tiny heap, CLI, minimal resources | batch/throughput, pauses OK | general services, balanced | strict latency SLA, large heap | strict latency SLA (OpenJDK/RH) |
| **Avoid when** | multicore throughput needed | pause-sensitive | sub-ms pauses required at huge heap | throughput is the only goal; tiny heap | Oracle JDK only; throughput batch |

### 8.2 Choosing a collector (decision rules)

1. **Is the heap tiny (< ~100 MB) and/or 1 CPU?** → **Serial**. Lowest overhead, no thread sprawl.
2. **Do you only care about total throughput / wall-clock, with multi-second pauses acceptable (batch, ETL, compute)?** → **Parallel**. It usually wins raw throughput.
3. **General-purpose service, heap a few–tens of GB, want good-enough pauses without fuss?** → **G1** (the default). Set `-Xms=-Xmx`, a sane `MaxGCPauseMillis`, log GC, and move on.
4. **Hard latency SLA (p99/p999 pauses must be ≤ a few ms), especially with large heaps?** → **ZGC** (generational, JDK 21+). If on Oracle JDK and you specifically want Shenandoah semantics, note Shenandoah isn't in Oracle builds.
5. **Same as 4 but on Red Hat/OpenJDK ecosystem or you prefer Shenandoah's profile / need it on JDK 8/11 backports?** → **Shenandoah**.
6. **Short-lived process where you can guarantee allocation < heap and want zero GC overhead** (benchmark, batch) → **Epsilon** (experimental).

### 8.3 ZGC vs. Shenandoah (head-to-head)

Both: concurrent mark + concurrent compaction, pauses ~independent of heap. Differences:

| | ZGC | Shenandoah |
|---|---|---|
| Forwarding mechanism | colored pointers + forwarding tables | header forwarding + load-ref barrier |
| Pauses | typically **lower** (O(1), often < 1 ms) | low, slightly higher historically (update-refs) |
| Remap timing | lazy, folded into next mark | explicit concurrent update-refs phase |
| Generational | **yes (JDK 21+, default later)** | experimental, newer |
| Availability | Oracle + OpenJDK, 11+ | OpenJDK/Red Hat, not Oracle; backports to 8/11 |
| Footprint | wants headroom; large-heap oriented | configurable (`compact` heuristic for small footprint) |

Rule of thumb: on modern JDK (21+) with large heaps and strict latency, **generational ZGC** is the default low-pause choice. Shenandoah shines on Red Hat stacks, smaller-footprint needs (`compact`), and older JDK backports.

### 8.4 G1 vs. ZGC (when to graduate from the default)

Stay on **G1** unless you measure pause-time SLA violations you can't fix by tuning (allocation reduction, IHOP, heap size). Graduate to **ZGC** when: pauses must be ≤ ~1–5 ms at p99/p999; heap is large (tens of GB+); you can spare a few % throughput and some footprint/CPU for concurrent GC. Don't switch speculatively — G1 is simpler to operate and often sufficient.

---

## 9. Failure modes & debugging

### 9.1 Symptom → cause → diagnosis playbook

| Symptom | Likely cause | Diagnose with |
|---|---|---|
| Periodic long pauses (seconds) | Full GC (Parallel/Serial big old gen; G1 evacuation failure) | `-Xlog:gc*`; look for `Full GC`, `to-space exhausted`, `Evacuation Failure` |
| Old-gen occupancy climbs, never recovers; eventual `OutOfMemoryError: Java heap space` | **Memory leak** (objects retained by a growing structure: caches, listeners, `ThreadLocal`s, static maps) | heap dump (`jcmd GC.heap_dump`) → analyze in **Eclipse MAT** (dominator tree, leak suspects) |
| Frequent minor GCs, high CPU in GC | High **allocation rate** | `jstat -gcutil 1s`, JFR allocation profiling; reduce garbage |
| Promotions spike, old gen grows fast | **Premature promotion** (young gen too small / survivor overflow) | `-Xlog:gc+age=trace`; enlarge young gen / survivor ratio |
| Pauses much longer than GC "work" time | **Time-to-safepoint** (tight loops, long JNI) | `-Xlog:safepoint` (look at "Reaching safepoint" time) |
| ZGC: latency spikes / "allocation stall" | Allocation outpaces concurrent GC; heap too small / too few `ConcGCThreads` | `-Xlog:gc*` ("Allocation Stall"), raise heap/`SoftMaxHeapSize`/`ConcGCThreads` |
| G1: many humongous allocations, early cycles | Objects ≥ 50% region size | `-Xlog:gc+humongous=debug`; increase `G1HeapRegionSize` or reduce object size |
| `OutOfMemoryError: Metaspace` | classloader leak / too many classes | `-XX:MaxMetaspaceSize`, heap dump for classloaders |
| `OutOfMemoryError: Direct buffer memory` | direct `ByteBuffer` leak / `DisableExplicitGC` | check `-XX:MaxDirectMemorySize`, NIO usage |

### 9.2 Reading a GC log line (G1, unified logging)

```
[2.345s][info][gc] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 512M->48M(2048M) 8.231ms
```
Interpretation: at 2.345 s uptime, the 12th GC was a normal young evacuation pause; heap went **512 MB → 48 MB** (used before→after) of a **2048 MB** heap, taking **8.231 ms**. Repeated `Pause Full` lines, or `Evacuation Failure` / `to-space exhausted`, are red flags.

### 9.3 Real-world incident patterns

- **"The 2 AM full-GC storm."** A service on Parallel GC with a 48 GB heap hits a multi-second full GC under a nightly batch overlap; requests time out and a load balancer evicts the node, cascading. Fix: move latency-sensitive service off Parallel to G1/ZGC, or isolate batch. Lesson: **never run a latency-sensitive service on a collector with full-heap STW.**
- **"Humongous leak."** A REST service deserializing large JSON into multi-MB `byte[]` triggered constant humongous allocations on G1, fragmenting old gen and forcing periodic full GCs. Fix: stream parse + larger region size. Lesson: watch humongous counts.
- **"It's not the GC, it's the safepoint."** Pauses of 300 ms with GC "work" reported as 5 ms. A counted `for` loop over a huge `int[]` had no safepoint poll (JIT loop optimization), so reaching the safepoint took 295 ms. Fix: JDK loop-strip-mining (`-XX:+UseCountedLoopSafepoints` / default in modern JDK) or restructure the loop. Lesson: always check `-Xlog:safepoint`.
- **"ZGC allocation stalls under burst."** A trading system on ZGC saw rare multi-ms latency spikes during allocation bursts because heap headroom was too tight. Fix: raise `-Xmx`, set `SoftMaxHeapSize` below it, increase `ConcGCThreads`, raise `ZAllocationSpikeTolerance`. Lesson: ZGC needs headroom; an undersized heap reintroduces pauses.
- **"CMS promotion failure" (legacy).** Pre-JDK-14 systems on CMS hit concurrent-mode failure / fragmentation → single-threaded STW full GC of seconds. The motivating reason for compacting collectors. Lesson on modern stacks: don't use CMS; it's removed.

### 9.4 Tools recap for an incident

1. `jstat -gcutil <pid> 1s` — quick live view (is old gen filling? full GC count rising?).
2. `-Xlog:gc*` log → **GCeasy.io** or **GCViewer** for pause/throughput distributions.
3. JFR recording → JDK Mission Control for allocation + pause correlation.
4. Heap dump (`jcmd <pid> GC.heap_dump /path/out.hprof`) → **Eclipse MAT** for leaks (dominator tree, "leak suspects" report, retained sizes).
5. `-Xlog:safepoint` to rule in/out time-to-safepoint.

---

## 10. Interview drill

### Q1. Explain the generational hypothesis and why GCs split the heap.
**Answer:** The weak generational hypothesis observes that most objects die young. GCs split the heap into young (Eden + survivors) and old generations so they can collect the young gen frequently and cheaply with a copying collector (cost ∝ live data, and most young data is dead), promoting only long-lived objects to old gen, which is collected rarely. This concentrates effort where the garbage is.
- *Follow-up: How does the collector avoid scanning the whole old gen during a minor GC?* Via a **card table** / **remembered set** maintained by a write barrier that records old→young references, so only those are treated as roots.
- *Follow-up: What is premature promotion and why is it bad?* When the young gen is too small (or survivor overflows), objects are tenured before they die, inflating old-gen pressure and causing more expensive old/full GCs. Fix by sizing young gen/survivors appropriately.
- *Follow-up: When does the hypothesis fail?* Caches, sessions, and large long-lived data structures violate it; such workloads see heavy promotion and benefit from larger old gen and concurrent collectors.

### Q2. How does G1 differ from a classic generational collector?
**Answer:** G1 divides the heap into many equal regions that are *dynamically* tagged young/old/humongous rather than physically contiguous generations. It collects regions with the most garbage first, can collect a *subset* of old regions per pause (mixed collections), uses remembered sets per region, marks the old gen concurrently (SATB), and tries to meet a soft pause-time goal by predicting how many regions fit the target.
- *Follow-up: What's a humongous object?* An object ≥ 50% of a region size, allocated in contiguous humongous regions; large/many of them cause fragmentation and full GCs.
- *Follow-up: What triggers G1 concurrent marking?* Old-gen occupancy crossing IHOP (default 45%, adaptive since JDK 9).
- *Follow-up: What's a G1 full GC and when does it happen?* A full-heap STW mark-compact (parallel since JDK 10), triggered by evacuation failure / to-space exhaustion / humongous-allocation failure — the failure mode you tune to avoid.

### Q3. How can ZGC/Shenandoah relocate objects without stopping the application?
**Answer:** With **barriers**. ZGC uses **colored pointers** + a **load barrier**: metadata bits in the reference tell the barrier whether the object may have moved; if so, the slow path consults a forwarding table, returns the new address, and **self-heals** the reference. Shenandoah uses a **load-reference barrier** with forwarding info in the object header. Because every read is intercepted, the collector can move objects concurrently and any thread loading a stale reference is transparently redirected.
- *Follow-up: Why are ZGC pauses independent of heap size?* The only STW work is scanning thread-stack roots (constant w.r.t. heap); marking, relocation, and remapping are concurrent.
- *Follow-up: What's the cost?* Barrier overhead on loads (throughput tax) and extra memory headroom for concurrent relocation; if the heap is too small, **allocation stalls** reintroduce latency.
- *Follow-up: What did generational ZGC add and why?* Young/old generations so it doesn't re-mark the mostly-old heap each cycle, cutting CPU and improving allocation-rate tolerance (JDK 21+).

### Q4. What is the tricolor invariant and how do incremental-update vs. SATB marking differ?
**Answer:** Tricolor marking labels objects white (unvisited), gray (visited, children pending), black (done). The invariant prevents losing a live white object that a black object starts pointing to after all gray paths to it vanish. **Incremental-update** (strong invariant) uses a write barrier to re-gray a target when a reference into a black object is created. **SATB** (weak invariant) records the *pre-overwrite* reference so the snapshot at cycle start stays live, tolerating floating garbage. G1 and Shenandoah(satb) use SATB; Shenandoah's `iu` mode uses incremental-update.
- *Follow-up: What is floating garbage?* Objects that became dead during the cycle but are kept alive until the next cycle because of SATB's snapshot semantics — a safe over-approximation.
- *Follow-up: Where is the barrier injected?* By the JIT around heap reference reads/writes.

### Q5. Why was CMS removed and how do its successors fix its flaw?
**Answer:** CMS did concurrent mark + concurrent sweep but **did not compact** the old gen, so it fragmented; when a promotion couldn't find a contiguous slot it fell back to a **single-threaded STW full GC** (concurrent-mode failure) — long, unpredictable pauses. G1, ZGC, and Shenandoah all **compact** (G1 incrementally and STW; ZGC/Shenandoah concurrently), eliminating fragmentation-induced full GCs. CMS was deprecated in JDK 9 and removed in JDK 14.
- *Follow-up: Was CMS generational?* Yes — concurrent collector for old gen, with a separate young collector (ParNew).
- *Follow-up: What replaced CMS as the default low-pause collector?* G1 became the general default; ZGC/Shenandoah for ultra-low pause.

### Q6. How does `-XX:MaxGCPauseMillis` work in G1, and what happens if you set it very low?
**Answer:** It's a *soft* target. G1's prediction model sizes the collection set (and young gen) so the predicted pause fits the target. Setting it very low forces a tiny young gen, causing very frequent GCs that **lower throughput** and often still miss the target — you can't get sub-ms pauses from G1 by lowering a number; you need a concurrent-compaction collector.
- *Follow-up: What's the default?* 200 ms.
- *Follow-up: How does Parallel GC's goal differ?* Parallel prioritizes `GCTimeRatio` (throughput) with `MaxGCPauseMillis` as a softer, secondary concern.

### Q7 (senior signal). You run a payments API on JDK 17, 32 GB heap, G1, and see p999 pauses of 250–400 ms violating a 50 ms SLA. Walk me through your approach.
**Answer:** First **measure, don't guess**: enable `-Xlog:gc*` and `-Xlog:safepoint`, capture JFR. Rule out time-to-safepoint (check "reaching safepoint" times). If the pauses are genuine GC: check whether they're young, mixed, or full. If full GCs / evacuation failures — fix root cause: reduce allocation, lower IHOP (start marking earlier), raise heap/reserve, eliminate humongous objects. If young/mixed pauses are inherently too long at 32 GB, **G1 can't reach 50 ms p999 reliably at that heap** — switch to **ZGC** (generational on 21+, but on JDK 17 use ZGC prod, or upgrade), set `-Xms=-Xmx`, `SoftMaxHeapSize`, `AlwaysPreTouch`, size `ConcGCThreads`, and validate under load test watching p999 and allocation-stall metrics. Justify the throughput/footprint cost against the SLA business value.
- *Follow-up: Why not just lower MaxGCPauseMillis?* It shrinks young gen and hurts throughput without delivering sub-50 ms p999 at 32 GB.
- *Follow-up: What's the risk of switching to ZGC?* Throughput drop and higher footprint/CPU; allocation stalls if heap undersized — mitigate with headroom and tuning.

### Q8 (senior signal). A nightly batch job's wall-clock time regressed 30% after someone "improved latency" by switching it to ZGC. Diagnose and recommend.
**Answer:** ZGC pays a barrier and concurrent-GC throughput tax that doesn't matter for a batch job, which cares about total throughput, not pauses. The "improvement" optimized the wrong metric. Recommend **Parallel GC** (highest throughput) — or G1 if some bounded pauses are still wanted — with `-Xms=-Xmx`, generous heap, `GCTimeRatio` tuned, and validate wall-clock. Lesson: **match the collector to the workload's dominant objective.**
- *Follow-up: Could G1 be a compromise?* Yes if the job has interactive components; pure batch favors Parallel.
- *Follow-up: When would Epsilon be appropriate?* If the job's total allocation fits in heap and is short-lived, Epsilon removes all GC overhead.

### Q9 (senior signal). Justify a collector choice for a 4 TB in-memory analytics cache with strict tail-latency requirements. What are the constraints?
**Answer:** Only **ZGC** realistically handles multi-TB heaps with low pauses (it scales to 16 TB; pauses are O(1) in heap size). G1's STW evacuation pauses grow with the work per collection and won't hold tail latency at 4 TB; Parallel/Serial full GCs would be catastrophic. Constraints: huge footprint/headroom for concurrent relocation, lots of CPU for concurrent threads, `SoftMaxHeapSize` to manage growth, `AlwaysPreTouch`, NUMA considerations on big iron, and **compressed oops are off** (64-bit colored pointers), so reference memory cost is higher. Validate allocation-stall behavior under peak ingest.
- *Follow-up: Generational vs. non-generational ZGC here?* Generational, to avoid re-marking the mostly-old 4 TB every cycle (massive CPU savings).
- *Follow-up: What kills you if you under-provision?* Allocation stalls (latency spikes) when GC can't keep up — heap headroom and `ConcGCThreads` are the levers.

### Q10. What is a safepoint and how can it cause pauses unrelated to GC?
**Answer:** A safepoint is a point where a thread's state is GC-consistent; STW operations require all threads at a safepoint. The pause includes **time-to-safepoint** — if a thread is in a tight counted loop without a poll, or a long native call, others wait for it. So total pause = TTSP + STW work + resume; a long TTSP looks like a long GC pause but isn't the collector's fault. Diagnose with `-Xlog:safepoint`.
- *Follow-up: How does the JVM mitigate counted-loop TTSP?* Loop strip-mining inserts safepoint polls (modern JDK default).
- *Follow-up: Are STW operations only GC?* No — biased-lock revocation (legacy), deoptimization, class redefinition, thread dumps, and JFR also use safepoints.

### Q11. Walk through what happens, step by step, during a G1 concurrent marking cycle.
**Answer:** (1) **Initial Mark** (STW, on a young GC) marks roots; (2) **Root Region Scan** (concurrent) scans survivors for old-gen refs; (3) **Concurrent Mark** traces old gen with SATB; (4) **Remark** (STW) drains SATB buffers and processes references; (5) **Cleanup** (STW + concurrent) accounts liveness, immediately frees empty old regions, and selects candidate old regions for mixed collections; then several **mixed collections** evacuate them.
- *Follow-up: What's the role of the SATB pre-write barrier?* It logs overwritten references so the start-of-cycle snapshot stays consistent (live), preventing lost objects.
- *Follow-up: What if marking can't keep up with allocation?* Evacuation failure → full GC; mitigate by lowering IHOP / adding heap / reducing allocation.

### Q12. Compare the footprint/throughput/latency profiles of Serial, Parallel, G1, ZGC, Shenandoah in one breath.
**Answer:** Serial: lowest footprint/overhead, single-threaded, long pauses — tiny heaps. Parallel: highest throughput, multi-second STW pauses — batch. G1: balanced, bounded soft pauses, medium footprint (RSets) — general default. ZGC: sub-ms pauses independent of heap, large-heap oriented, higher footprint/CPU, slight throughput cost — latency-critical at scale. Shenandoah: similar to ZGC (concurrent compaction), OpenJDK/Red Hat, configurable footprint — latency-critical, not in Oracle JDK.
- *Follow-up: Who wins raw throughput?* Usually Parallel (least overhead).
- *Follow-up: Who handles the biggest heaps with low pause?* ZGC (to 16 TB).

---

## 11. Glossary

- **Allocation rate**: bytes of new objects created per unit time; the primary driver of GC frequency.
- **Allocation stall**: in ZGC/Shenandoah, when the app must block because GC hasn't freed enough memory to satisfy an allocation; reintroduces latency.
- **Barrier**: JIT-injected code around heap reads (read/load barrier) or writes (write barrier) that lets the GC run concurrently (track references, redirect to moved objects).
- **Bump-the-pointer allocation**: O(1) allocation by advancing a free pointer in a contiguous region; enabled by compaction.
- **Card table**: array marking small chunks ("cards") of old gen as dirty when they hold old→young references, so minor GC scans only dirty cards.
- **CMS (Concurrent Mark Sweep)**: legacy concurrent, non-compacting old-gen collector; deprecated JDK 9, removed JDK 14.
- **Collection set (CSet)**: the set of regions a G1 collection will evacuate.
- **Colored pointers**: ZGC technique storing GC metadata bits inside the reference itself.
- **Compaction**: moving live objects together to eliminate fragmentation and restore bump allocation.
- **Compressed oops**: 32-bit object references (default for heaps ≤ ~32 GB) scaled to address more memory; saves space/cache.
- **Concurrent (GC phase)**: runs while application threads run (not STW).
- **Eden**: young-gen subspace where new objects are allocated.
- **Ergonomics (GC ergonomics)**: the JVM's automatic selection of collector and heap/generation sizes based on machine class and goals.
- **Evacuation / evacuation failure**: copying live objects out of a region; failure ("to-space exhausted") when no free region exists, often → full GC (G1).
- **Floating garbage**: objects that died during a concurrent cycle but survive to the next cycle due to snapshot (SATB) semantics.
- **Footprint**: total memory/CPU overhead of the collector (metadata, headroom, threads).
- **Forwarding pointer/table**: data structure mapping an object's old location to its relocated address (Shenandoah header word / ZGC tables).
- **Full GC**: STW collection of the entire heap (usually mark-compact); the worst-case pause.
- **Garbage**: unreachable objects, eligible for reclamation.
- **GC roots**: starting references for liveness tracing (stacks, statics, JNI, etc.).
- **Generation (young/old/tenured)**: heap partition by object age, exploiting the generational hypothesis.
- **Generational hypothesis (weak)**: most objects die young.
- **Humongous object**: G1 object ≥ 50% of a region size, allocated in contiguous humongous regions.
- **IHOP (Initiating Heap Occupancy Percent)**: old-gen occupancy that triggers G1 concurrent marking (default 45%, adaptive since JDK 9).
- **Incremental-update marking**: concurrent marking enforcing the strong tricolor invariant via a write barrier that re-grays targets.
- **JFR (Java Flight Recorder)**: low-overhead always-on event recorder including GC/allocation/pause data.
- **JIT (Just-In-Time compiler)**: compiles hot bytecode to native code; injects GC barriers and safepoint polls.
- **JNI (Java Native Interface)**: API for Java↔native code; JNI references are GC roots.
- **Load barrier / load-reference barrier**: read barrier (ZGC / Shenandoah) that redirects loaded references to relocated objects.
- **Major GC**: collection involving the old gen.
- **Mark-compact / mark-sweep / copying**: the three fundamental reclamation strategies.
- **Marking**: tracing reachable objects from roots.
- **Metaspace**: off-heap region for class metadata (separate OOM domain).
- **Minor GC**: young-gen-only collection.
- **Multi-mapping**: mapping the same physical memory at multiple virtual addresses so differently-colored pointers dereference correctly (legacy ZGC).
- **Mutator**: the application threads (as opposed to GC threads) — they "mutate" the object graph.
- **NUMA (Non-Uniform Memory Access)**: multi-socket architecture where local RAM is faster than remote; `-XX:+UseNUMA` helps.
- **oop (ordinary object pointer)**: the JVM's term for an object reference.
- **Pause-time goal**: soft target for STW pause length (`-XX:MaxGCPauseMillis`).
- **Premature promotion**: tenuring objects before they die because young gen is too small.
- **Promotion / tenuring**: moving a surviving young object to old gen after surviving enough minor GCs (tenuring threshold).
- **Reachability**: whether an object can be reached from a root; defines liveness.
- **Refinement threads (G1)**: concurrent threads that update remembered sets from dirty-card queues.
- **Region (G1/Shenandoah)**: fixed-size heap chunk that can be tagged young/old/humongous.
- **Remembered set (RSet)**: per-region record of incoming references, enabling region-local collection.
- **Safepoint**: execution point where a thread's references are GC-consistent; prerequisite for STW.
- **SATB (Snapshot-At-The-Beginning)**: concurrent marking that preserves the object graph as of cycle start via a pre-write barrier (G1, Shenandoah).
- **Self-healing (ZGC)**: load barrier writing the corrected reference back so subsequent loads hit the fast path.
- **STW (stop-the-world)**: all application threads paused for GC work.
- **Survivor space (S0/S1)**: young-gen spaces holding objects that survived minor GC(s) before promotion.
- **TLAB (Thread-Local Allocation Buffer)**: per-thread Eden chunk for lock-free allocation.
- **Tenuring threshold**: object age at which it is promoted to old gen (`-XX:MaxTenuringThreshold`, default up to 15).
- **Throughput**: fraction of time spent in application code vs. GC.
- **Time-to-safepoint (TTSP)**: time for all threads to reach a safepoint; can dominate a pause independent of GC work.
- **Tricolor (white/gray/black)**: abstraction for concurrent marking correctness.
- **Write barrier**: code on reference stores used for card marking, SATB logging, or RSet maintenance.
- **ZPage**: ZGC's heap region (small 2 MB / medium 32 MB / large size classes).

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Pick a collector:**
- Tiny heap / 1 CPU → **Serial** (`-XX:+UseSerialGC`)
- Throughput batch, pauses OK → **Parallel** (`-XX:+UseParallelGC`)
- General service, balanced → **G1** (`-XX:+UseG1GC`, default since JDK 9)
- Strict low latency, big heap → **ZGC** (`-XX:+UseZGC -XX:+ZGenerational`, JDK 21+)
- Strict low latency, OpenJDK/RH → **Shenandoah** (`-XX:+UseShenandoahGC`; not in Oracle JDK)

**Key numbers/defaults:**
- G1 `MaxGCPauseMillis` = **200 ms**; IHOP = **45%** (adaptive 9+); regions **1–32 MB**, ~**2048** of them; humongous ≥ **50%** of region.
- Default max heap = **25% of RAM** (`MaxRAMPercentage`); compressed oops up to **~32 GB**.
- `MaxTenuringThreshold` up to **15**; `NewRatio`=2; `SurvivorRatio`=8.
- ZGC: pauses **O(1)** in heap, sub-ms, up to **16 TB**; wants headroom; `SoftMaxHeapSize` + `+ZUncommit`.
- Parallel `GCTimeRatio`=99 → GC ≤ **1%** of time (throughput champion).
- Defaults: JDK 8 = Parallel; JDK 9+ = G1. CMS removed JDK 14. ZGC/Shenandoah prod JDK 15.

**Production hygiene:** `-Xms=-Xmx`, `+AlwaysPreTouch`, **always log GC** (`-Xlog:gc*:file=...`), always-on JFR, dashboard p99/p999 pauses + throughput% + allocation rate + full-GC count (≈0), `-Xlog:safepoint` to catch TTSP. Never `System.gc()`. Reduce allocation before tuning.

**Decision rules:** match collector to the *dominant* objective (latency vs. throughput vs. footprint). You can't get sub-ms pauses from G1 by lowering a flag — use ZGC/Shenandoah. You can't beat Parallel's throughput with a concurrent collector. Compaction (all modern collectors) is why fragmentation-induced full GCs disappeared with CMS.

**Concurrency mechanics:** STW collectors (Serial/Parallel) freeze everything. G1 marks concurrently (SATB) but evacuates STW (incrementally). ZGC/Shenandoah do mark *and* compaction concurrently via **load barriers** (ZGC colored pointers / Shenandoah load-ref barrier) + forwarding, with only tiny constant STW root scans.

### 12.2 Self-test (no answers — recall)

1. Trace the full lifecycle of a single `byte[]` from `new` to reclamation under G1, naming every space it passes through and every GC type that could touch it.
2. Explain precisely why ZGC's STW pauses do not grow with heap size, and what the *only* STW work is.
3. Your service on G1 (24 GB heap) shows rising old-gen occupancy and periodic 1.5 s pauses logged as `Pause Full (G1 Evacuation Pause)`. List the candidate root causes in priority order and the exact flags/logs you'd use to confirm each.
4. Contrast SATB and incremental-update marking: which invariant each enforces, which barrier each uses, and the practical consequence (floating garbage vs. barrier cost). Which collectors use which?
5. Justify, with the throughput/latency/footprint tradeoffs, why you would (or would not) move a 64 GB latency-critical service from G1 to generational ZGC on JDK 21 — and name the two metrics that would prove the decision right or wrong.
6. Describe how a long time-to-safepoint can masquerade as a GC pause, give one concrete code shape that causes it, and the JDK mechanism/flag that mitigates it.
7. Why was CMS removed, and how do its three successors each avoid its fatal flaw?
```

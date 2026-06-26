# Garbage Collection Fundamentals

> A definitive chapter on how automatic memory management works on the JVM — from first principles to deep internals, tuning, and production debugging.

---

## 1. Overview & where it fits

**Garbage collection (GC)** is the JVM subsystem that automatically reclaims heap memory occupied by objects the program can no longer use, so the application code never has to call `free()` or `delete` by hand. The *problem it solves* is twofold:

1. **Manual memory management is error-prone.** In languages like C/C++ the programmer must explicitly free memory. Get it wrong and you get **dangling pointers** (using memory after it's freed — undefined behavior, crashes, security holes), **double frees** (freeing the same block twice — heap corruption), or **memory leaks** (never freeing — the process slowly grows until it dies). These bugs are among the hardest to diagnose in production.
2. **Memory must be recycled.** A long-running server allocates billions of objects over its lifetime. Without reclamation it would exhaust the address space in seconds. Something has to find dead objects and give their space back to the allocator.

GC trades **explicit control** for **safety and productivity**. You accept that the runtime will periodically spend CPU (and sometimes pause your threads) to find and reclaim garbage, in exchange for never writing a `free()` and never suffering a use-after-free.

**Where it fits in the JVM.** The JVM divides runtime memory into regions (covered later): the **heap** (where almost all objects live and where GC operates), the **thread stacks** (local variables and frames — not GC-managed, freed when a method returns), the **metaspace** (class metadata — managed separately), and various native/off-heap regions. GC is exclusively about the heap. The **HotSpot JVM** — Oracle/OpenJDK's reference implementation, the one nearly everyone runs — ships several interchangeable collector implementations (Serial, Parallel, G1, ZGC, Shenandoah, plus the now-removed CMS) that you select with a command-line flag. They all solve the same problem but make different tradeoffs along the throughput/latency/footprint axes.

**When you reach for this knowledge.** You don't "use" GC the way you use a library — it's always on. You reach for *understanding* it when: your service has unacceptable pause times; your throughput is lower than expected and you suspect GC overhead; you're getting `OutOfMemoryError`; you're sizing a heap for a new service; you're choosing a collector for a latency-sensitive vs. batch workload; or an interviewer asks you to explain why your p99 latency has periodic spikes.

**One-paragraph mental model.** Think of the heap as a warehouse and your objects as boxes. The application (the **mutator** — the term for application threads, because from the GC's perspective they *mutate* the object graph) keeps adding boxes and shuffling which boxes point to which. Periodically a crew (the **collector**) walks in, starts from a known set of "still-needed" anchor points (the **GC roots**), follows every chain of references to find all boxes that are still reachable, and throws out everything else. The clever part is *how* the crew does this with minimal disruption: only pausing you when absolutely necessary, working mostly while you keep running, exploiting the empirical fact that **most boxes are thrown out almost immediately** (the generational hypothesis), and keeping cheap bookkeeping so it doesn't have to re-scan the whole warehouse every time.

---

## 2. Foundations from first principles

### 2.1 What is an object and where does it live?

When Java code executes `new Customer()`, the JVM:

1. Computes the object's size (header + fields, rounded up to an 8-byte boundary by default).
2. Finds free space in the **heap** — the large, shared region of memory dedicated to objects.
3. Zeroes the memory and writes the **object header**.
4. Returns a reference (effectively a pointer) to the new object.

The **object header** on 64-bit HotSpot is typically 12 bytes (with **compressed ordinary object pointers**, "compressed oops", enabled — the default for heaps under 32 GB): an 8-byte **mark word** (holds identity hash code, lock state, GC age bits, etc.) and a 4-byte **klass pointer** (points to the class metadata in metaspace). Arrays add a 4-byte length. This header is GC-relevant because the **age bits** in the mark word track how many collections an object has survived (used for tenuring — see §2.6).

> **Compressed oops** — a 64-bit JVM normally uses 8-byte pointers. For heaps ≤ 32 GB, HotSpot stores pointers as 4-byte offsets (scaled by 8) instead, halving pointer footprint and improving cache behavior. Enabled by default; disabled automatically above ~32 GB max heap. Flag: `-XX:+UseCompressedOops`.

References live in three places: **on the stack** (local variables and method arguments — each thread has its own stack of frames), **in CPU registers** (the JIT may keep references in registers), and **inside other objects** (instance fields and array elements on the heap). This matters enormously for GC because to find live objects, the collector must locate *every* reference, including ones temporarily sitting in registers.

### 2.2 Why "garbage"? The core definition

An object is **garbage** when the program can never use it again. The precise, decidable approximation the JVM uses is **reachability**: an object is **live** (keep it) if it is **reachable** from a **GC root** by following a chain of references; otherwise it is **garbage** (reclaim it).

This is an approximation of "will be used again." An object can be reachable but never actually touched again (a **logical leak** — you forgot to remove it from a cache). GC will keep it because, formally, the program *could* still reach it. GC only guarantees it won't reclaim something you *can* reach; it cannot read your intent.

### 2.3 GC roots — the anchors of liveness

**GC roots** are references that the collector treats as inherently alive — the starting points of the reachability search. The garbage collector cannot prove these are needed; it assumes they are. The principal categories in HotSpot:

| Root category | What it is | Why it's a root |
|---|---|---|
| **Local variables & operands** | References on each thread's stack frames and in registers for currently executing methods | The thread is actively using them right now |
| **Static fields** | `static` reference fields of loaded classes | Reachable from the class, which lives as long as its class loader |
| **JNI references** | Local and global references held by native (C/C++) code via JNI | Native code holds them; GC can't see into native frames |
| **Monitor objects** | Objects currently used as locks (held by `synchronized`) | A thread is blocked/waiting on them |
| **Thread objects** | Live `Thread` instances themselves | The threads are alive |
| **Class loaders & classes** | Loaded class objects and their loaders | Classes stay alive while reachable; their statics depend on this |
| **JVM-internal roots** | Things like the system class loader, certain interned strings, exception handlers | Required by the runtime |

The reachability search is a **graph traversal**: start from the root set, do a breadth-first or depth-first walk over the reference graph (objects are nodes, references are edges), mark everything reached. Everything not marked is dead.

> **Reference graph / object graph** — a directed graph where each object is a node and each reference field is an edge pointing to another object. GC liveness is exactly "reachable in this graph from the root set."

### 2.4 Two families of liveness algorithms

There are two classic strategies for determining liveness:

**Reference counting.** Each object stores a count of how many references point to it; incremented on assignment, decremented when a reference goes away; reclaim at count zero. Simple and incremental, but: (a) it cannot reclaim **cycles** (A points to B, B points to A, nothing else points to either — both have count 1 forever, yet both are garbage), and (b) maintaining counts on every pointer write is expensive and breaks cache locality. **HotSpot does NOT use reference counting** for the heap. (CPython does, with a separate cycle collector; Swift's ARC does, leaving cycles to the programmer.) Mentioned here so you can answer "why not reference counting?" — the cycle problem and write overhead are the answers.

**Tracing.** Start from roots, trace reachability, reclaim the unreachable. This is what every HotSpot collector does. The rest of this document is about tracing collectors.

### 2.5 The generational hypothesis — the single most important empirical fact

The **weak generational hypothesis** states: **most objects die young.** Empirically, across an enormous range of real programs, the vast majority of objects become garbage shortly after they're created (think: the temporary `StringBuilder` in a method, the request/response objects in a web handler, intermediate stream results). A small minority live a long time (caches, connection pools, the application's long-lived data structures).

A weaker companion observation, the **inter-generational reference hypothesis** (sometimes "strong generational hypothesis"), states that **references from old objects to young objects are relatively rare** — older data tends to point to other old data, not to freshly created objects.

Why this matters: if most objects die young, you can make collection cheap by **segregating objects by age** and collecting the young region **frequently and cheaply**, only occasionally paying to collect the old region. This is **generational garbage collection**, and it underpins almost every production JVM collector.

The heap is therefore split (logically or physically) into:

- **Young generation (young gen / nursery):** where new objects are allocated. Collected often. Subdivided into:
  - **Eden:** where almost all allocation happens.
  - **Two survivor spaces (S0 and S1, "from" and "to"):** small regions used to hold objects that survived at least one young collection while they age.
- **Old generation (old gen / tenured):** where objects that have survived enough young collections are **promoted** ("tenured"). Collected rarely and more expensively.

> **Nursery / Eden / Survivor** — Eden is the bump-pointer allocation area for brand-new objects. Survivor spaces are two equal half-spaces; a young GC copies live survivors into the empty one (see copying collection, §3). Tenuring is graduation from young to old.

### 2.6 Allocation, promotion, and tenuring — the lifecycle of an object

1. `new` allocates in **Eden** (fast — usually just bumping a pointer; see TLABs in §3.4).
2. Eden fills up → a **minor GC** (young collection) runs.
3. Live objects in Eden + the current survivor space are **copied** to the *other* survivor space; their **age** (in the mark word) is incremented.
4. Dead objects in Eden/survivors are reclaimed instantly (their space is just declared free — copying collectors don't visit the dead).
5. An object that survives enough minor GCs — its age reaches the **tenuring threshold** (`-XX:MaxTenuringThreshold`, default 15 for many collectors, dynamically adjusted) — is **promoted** to the **old generation**.
6. Objects too big to fit in a survivor space, or larger than `-XX:PretenureSizeThreshold`, may be allocated **directly in old gen** (or, in G1, as **humongous** objects — §7).
7. Old gen fills up → a **major/full GC** runs (more expensive).

This produces the cheap-young/expensive-old asymmetry that makes generational GC fast.

### 2.7 Minor vs. major vs. full GC — get the vocabulary right

These terms are widely misused. Precise definitions:

- **Minor GC (young GC):** collects **only the young generation** (Eden + survivors). Frequent, fast (typically sub-millisecond to a few ms). Triggered when Eden fills.
- **Major GC (old GC):** collects the **old generation**. Slower. The term is fuzzy — in some collectors it implies a full GC; in G1 the analogous concept is the **mixed collection** (collects all of young + *some* old regions).
- **Full GC:** collects the **entire heap** (young + old), and usually metaspace too, typically with **compaction**, almost always **stop-the-world**, and usually slow. Full GCs are the thing you want to avoid in latency-sensitive systems. Causes include: old gen full, metaspace full, explicit `System.gc()`, promotion failure, concurrent-mode failure (collector couldn't keep up).

> **Key trap:** "major GC" and "full GC" are *not* synonyms in general, though in some collectors they coincide. Be precise in interviews.

### 2.8 Stop-the-world (STW) and the mutator

A **stop-the-world pause** is an interval during which the JVM suspends **all** mutator (application) threads so the collector can work on a consistent snapshot of the heap. During STW, your application does literally nothing — requests queue up, latency spikes. Every collector has *some* STW phases; the difference between collectors is how long and how frequent those pauses are, and how much work they move *out* of STW into **concurrent** phases that run alongside the mutator.

> **Mutator** — GC literature's name for application threads, because they mutate the object graph while the collector tries to analyze it. The fundamental tension in GC design is "collector vs. mutator running concurrently on a graph that keeps changing."

### 2.9 The three core reclamation strategies

Once you know which objects are live, you must reclaim the dead and (ideally) keep the heap from fragmenting. Three classic algorithms — detailed mechanically in §3:

- **Mark-Sweep:** mark live objects, then sweep the heap freeing unmarked ones into a **free list**. Doesn't move survivors → fast but **fragments** memory.
- **Mark-Compact:** mark live, then **slide** all live objects to one end of the region, leaving a single contiguous free block. No fragmentation, fast allocation afterward, but moving objects is expensive and requires fixing up every reference.
- **Copying (mark-copy / scavenge):** divide the region in two; copy live objects from the "from" space into the "to" space, then declare "from" entirely free. Naturally compacts, touches only live objects (great when most are dead), but wastes half the space and copies survivors.

Generational collectors mix these: young gen typically uses **copying** (most objects dead → cheap), old gen uses **mark-sweep** or **mark-compact** (most objects live → can't afford to copy them all).

---

## 3. How it works internally

This is the heart of the document. We trace the mechanics step by step.

### 3.1 The tracing algorithm in detail (tri-color marking)

Modern tracing uses the **tri-color abstraction**, which colors every object one of three colors during a mark phase:

- **White:** not yet visited. At the end of marking, white = garbage.
- **Gray:** visited (reachable), but its outgoing references have **not** all been scanned yet. Gray objects sit on a **work queue / mark stack**.
- **Black:** visited **and** all its references scanned. Black objects are definitively live and done.

The algorithm:

1. Color all objects **white**.
2. Color all **roots** gray (push them on the mark stack).
3. While the mark stack is non-empty: pop a gray object, scan its reference fields. For each referent that is white, color it gray (push it). Then color the popped object **black**.
4. When the stack empties, every reachable object is black; every white object is unreachable → garbage.

This is just BFS/DFS with explicit coloring. The coloring matters because it lets the collector reason about correctness when the mutator is *also* running (concurrent marking).

#### The tri-color invariant and the lost-object problem

If the collector marks concurrently with the mutator, the mutator can break correctness. The danger is the **lost-object (missed-marking) problem**: the collector might fail to mark a live object, treat it as garbage, and free it — a catastrophic bug. This happens precisely when **both** of these occur:

1. The mutator stores a reference to a **white** object into a **black** object (black objects are "done" — the collector won't rescan them, so it never sees the new pointer), **and**
2. The mutator destroys the original path (deletes all gray→white edges that would otherwise have reached that white object).

The collector defends the **tri-color invariant**: *no black object points to a white object* (the "strong" invariant), or a weaker variant. To maintain it while the mutator runs, collectors install **barriers** (§3.6) that intercept the offending pointer writes (or reads) and re-color or re-queue objects so nothing live is lost. Two classic correctness techniques:

- **Snapshot-at-the-beginning (SATB):** logically take the object graph as it was when marking *started*; anything live then stays "live" for this cycle even if the mutator later drops the reference. Implemented with a **write barrier** that records the **old** referent before an overwrite, so the just-overwritten object is still scanned. (G1 and Shenandoah use SATB.)
- **Incremental update (IU):** when the mutator stores a white referent into a black object, the barrier records the *new* reference (or re-grays the black object) so the white object gets scanned. (CMS used this style.)

> **Barrier** — a small snippet of code the JIT injects around heap pointer operations so the collector is informed of mutations it needs to know about. A **write barrier** runs on reference *stores*; a **read barrier** (load barrier) runs on reference *loads*. Barriers cost a few instructions per operation and are central to concurrent GC correctness.

### 3.2 Walking through a minor (young) GC with a copying collector

Consider a generational collector (e.g., Parallel or G1's young phase). A minor GC proceeds roughly as:

1. **Trigger:** Eden is full; the next allocation can't fit. The allocating thread (or the JVM) initiates a young collection.
2. **Reach a safepoint (§3.5):** all mutator threads are brought to a stop at a **safepoint** so the heap is in a consistent state.
3. **Root scanning:** enumerate all GC roots (thread stacks, registers, statics, JNI, etc.). For the young collection, also treat **old→young references** as roots, found cheaply via the **remembered set / card table** (§3.3) rather than scanning all of old gen.
4. **Copy live young objects (the scavenge):**
   - Walk from roots into the young gen. For each live object in Eden or the "from" survivor space, **copy** it into the "to" survivor space (or promote to old gen if its age ≥ tenuring threshold or "to" is full).
   - **Forwarding pointers:** when an object is copied, the collector overwrites part of its old header (or mark word) with a **forwarding pointer** to its new location, so any other reference to the same object can be **redirected** to the copy (and the object isn't copied twice).
   - Update the references in the copied object's fields and in the roots to point at new locations.
5. **Age and promote:** increment survivor ages; promote those over threshold. Adjust the dynamically computed tenuring threshold based on survivor occupancy.
6. **Reclaim:** Eden and the old "from" survivor are now entirely garbage → declared free in one stroke (no per-object sweep — the dead are never visited). Swap the roles of the two survivor spaces ("from"↔"to").
7. **Resume mutators:** leave the safepoint; application threads continue. Allocation resumes by bump-pointer into the now-empty Eden.

The brilliance: because most young objects are dead, the collector touches only the *small* live set, copies it, and frees the rest in O(1). Cost is proportional to **survivors**, not to total allocation.

### 3.3 Tracking cross-generational references: remembered sets & card tables

A generational collector wants to collect young gen *without* scanning old gen (which is large). But old objects might reference young objects (e.g., a long-lived cache holding a freshly created entry). Those old→young references are **roots** for the young collection. If the collector ignored them, it would free still-referenced young objects. So it must find them — cheaply.

The solution is to **track** where cross-generational references exist, maintained incrementally by **write barriers** as the mutator runs, so that at young-GC time the collector only scans the recorded locations.

**Card table.** Divide the old generation (and in G1, the whole heap) into fixed-size **cards** — typically **512 bytes** each in HotSpot. Maintain a **card table**: a compact byte array with one byte per card. When the mutator stores a reference into an object (a pointer write), a **write barrier** marks the corresponding card **dirty** (writes a known value into that card's byte). At young-GC time, the collector scans only the **dirty cards** in old gen, looking for old→young pointers, instead of all of old gen. After processing, cards are cleaned.

- **Cost model:** the write barrier is extremely cheap — compute the card index from the object address (a shift) and write a byte. It is *unconditional* in many designs (always dirty the card, even for old→old writes) because checking "is this cross-gen?" would cost more than just marking. This means some clean cards get marked dirty needlessly (false positives), but scanning a 512-byte card is cheap.
- **Granularity tradeoff:** smaller cards → more precise (less to rescan) but a larger table; bigger cards → smaller table but coarser rescans. 512 bytes is HotSpot's chosen balance.

**Remembered set (RSet).** A more general/explicit data structure that records, for a region, *which other regions (or cards) contain references into it*. G1 keeps a **per-region remembered set** so that to collect any single region it can find all incoming references without scanning the whole heap. G1's RSets are built from card-table information and can themselves consume significant memory (a known G1 footprint cost). Card tables are essentially a global, coarse remembered set; G1's RSets are per-region and richer.

> **Card** — a small fixed chunk of heap (512 B in HotSpot) that the card table tracks at byte granularity. **Dirty card** — one that has been written to since the last scan and may contain interesting (e.g., cross-gen) references. The point of both card tables and remembered sets is the same: avoid scanning the whole old generation on every young GC.

### 3.4 Allocation fast path: Thread-Local Allocation Buffers (TLABs)

Allocation must be blisteringly fast — programs allocate constantly. The naive approach (a shared bump pointer into Eden, atomically incremented) would force every thread to synchronize (a **CAS** — compare-and-swap atomic) on every `new`, creating brutal contention on multicore machines.

> **CAS (compare-and-swap)** — an atomic CPU instruction: "if memory location X still equals A, set it to B, atomically." The building block of lock-free algorithms. Contended CAS (many threads hammering the same location) is slow due to cache-line ping-pong between cores.

**TLAB** solves this. Each mutator thread is given its own private chunk of Eden — a **Thread-Local Allocation Buffer**. Within its TLAB, a thread allocates by simply **bumping a pointer with no synchronization at all** (it's the only one touching that buffer). Only when the TLAB is exhausted does the thread go back to the shared heap (under a lock/CAS) to grab a **new** TLAB. So the expensive synchronization happens once per TLAB-refill instead of once per object.

- **The allocation fast path** (JIT-inlined): `if (tlab.top + size <= tlab.end) { obj = tlab.top; tlab.top += size; }` — a comparison, an add, done. Often just a handful of instructions, sometimes folded by escape analysis into nothing at all.
- **Slow path:** TLAB full → either **refill** (get a new TLAB) or, if the object is large or refilling isn't worth it, allocate directly in shared Eden (or old gen for very large objects).
- **Sizing:** TLAB sizes are **adaptive** — HotSpot resizes them per thread based on allocation rate to balance "fewer refills" against "wasted space at the end of each TLAB." Flags: `-XX:+UseTLAB` (on by default), `-XX:TLABSize` (initial size; 0 = auto), `-XX:-ResizeTLAB` to disable adaptation, `-XX:TLABWasteTargetPercent` (default 1 — the fraction of Eden tolerated as TLAB waste).
- **Wasted space / "TLAB waste":** the unused tail of a TLAB when a thread can't fit its next object — filled with a dummy/filler object so the heap remains parseable (see §3.5 heap parsability).

> **Escape analysis & scalar replacement** — a JIT optimization where the compiler proves an object never "escapes" a method (no reference leaks out). It can then avoid allocating it on the heap entirely — either **stack-allocate** it or **scalar-replace** it (break it into its fields kept in registers). This is the cheapest possible "GC": the object is never created. Flag `-XX:+DoEscapeAnalysis` (on by default with C2 JIT).

### 3.5 Safepoints and safepoint bias

A **safepoint** is a point in the execution of mutator threads at which the JVM knows the **complete, consistent state of every thread** — in particular, exactly where all object references live (in which stack slots and registers). The JVM can only safely walk the stack, move objects, and patch references when threads are at safepoints, because only then does it have an accurate **OopMap** (a per-location map describing which stack slots/registers currently hold object references — "oops") for each thread.

> **OopMap (oop map / stack map)** — metadata the JIT emits saying, "at this exact instruction, registers R1/R3 and stack slots 4/7 hold object references." The GC consults the OopMap at a safepoint to find roots precisely. Without it the GC would have to **conservatively** scan everything (treating any bit pattern that looks like a pointer as one) — HotSpot is a **precise** (exact) collector, so it relies on OopMaps.

**How a global safepoint ("stop the world") is reached:**

1. The JVM sets a global flag/poll requesting a safepoint.
2. Each thread, at the **next safepoint poll** in its code, notices the request and parks itself (suspends), recording its state.
3. **Safepoint polls** are inserted by the JIT at strategic locations: at **method returns**, at **loop back-edges** (so long loops poll periodically), and at **call sites / allocation slow paths**. Interpreted code checks at bytecode boundaries.
4. **Time-to-safepoint (TTSP):** the JVM must wait until the *last* thread reaches a safepoint. A thread in a tight, **counted loop without a poll**, or stuck in a long native/JNI call, can take a long time — inflating the pause even before any GC work begins.
5. Once all threads are parked, the VM operation (GC, deoptimization, biased-lock revocation, stack dump, etc.) runs; then threads are released.

**Safepoint bias** is a *measurement* pitfall, not a GC mechanism, but it's named in the mandate. Many profilers (and `jstack`-style sampling) can only capture a thread's stack **at a safepoint**. So the samples are biased toward locations where safepoint polls exist (method returns, loop back-edges) and *blind* to code between polls. This **safepoint sampling bias** makes such profilers misattribute where time is spent. **Async profilers** (e.g., `async-profiler`) avoid this by sampling via OS signals / `perf` events at arbitrary instruction boundaries, not just safepoints — giving an unbiased profile.

> **Counted loop** — a loop the JIT recognizes as iterating a bounded `int` count; HotSpot historically *omitted* safepoint polls from counted loops as an optimization, which could cause very long TTSP for big loops. The `-XX:+UseCountedLoopSafepoints` flag (and modern defaults) reintroduce polls. **Heap parsability** — the heap must be walkable object-by-object at a safepoint (every byte either an object or a filler), which is why TLAB tails are filled with dummy objects.

### 3.6 Write barriers and read barriers — the mutator's GC tax

To keep GC metadata correct while the mutator runs, the JIT injects **barriers** around heap reference operations:

- **Write (store) barrier:** runs on `obj.field = ref`. Uses:
  - **Card marking** (generational): dirty the card for `obj` so cross-gen references are found at young GC.
  - **SATB enqueue** (G1, Shenandoah): record the *old* value of `obj.field` so concurrent marking doesn't lose it.
  - **Incremental-update enqueue** (CMS-style): record the *new* reference into a black object.
- **Read (load) barrier:** runs on `ref = obj.field`. Used by **concurrent compacting** collectors (ZGC, Shenandoah) so that when the collector is **relocating** objects concurrently, a load can detect "this object has moved" and transparently follow a **forwarding pointer** (and optionally **self-heal** by updating the slot). ZGC encodes GC state in unused pointer bits (**colored pointers**) so the load barrier is a cheap masked test.

> **Forwarding pointer** — when a collector copies/relocates an object, it leaves behind a pointer (in the old object's header) to the new location, so other references can be redirected. Read barriers in concurrent compactors consult it. **Colored pointers (ZGC)** — metadata bits stored *inside* the 64-bit reference itself (marked, remapped, etc.), letting the load barrier decide in a couple of instructions whether action is needed.

The barrier you pay determines the collector's mutator overhead. Throughput collectors (Parallel) use only a cheap card-marking write barrier and **no read barrier** → highest throughput. Concurrent compactors (ZGC/Shenandoah) add a load barrier on *every* reference read → a small per-read tax in exchange for sub-millisecond pauses.

### 3.7 The full-GC state machine (old-gen reclamation)

When old gen can't satisfy promotion/allocation, a major/full collection runs. With a **mark-compact** old gen (Serial/Parallel "Old"):

1. **STW.**
2. **Mark:** trace from roots, mark all live objects across the whole heap.
3. **Compute new addresses:** in a forwarding pass, compute where each live object will land after sliding to one end.
4. **Update references:** rewrite every reference (in objects and roots) to its referent's new address.
5. **Move (compact):** slide live objects to their new contiguous positions.
6. **Resume mutators:** old gen is now compacted with one big free block at the end.

CMS (removed in JDK 14) instead did old-gen marking and sweeping **concurrently** (mostly non-STW) but **did not compact**, leading to fragmentation and the dreaded **concurrent-mode failure** fallback to a STW full GC. G1, ZGC, and Shenandoah do most marking/relocation concurrently with various STW "pause" sub-phases (initial mark, remark, cleanup).

---

## 4. The complete toolkit

### 4.1 The HotSpot collectors (choose with one flag)

| Collector | Select flag | Young algo | Old algo | Concurrency | Compacts old? | Target | Default in |
|---|---|---|---|---|---|---|---|
| **Serial** | `-XX:+UseSerialGC` | Copying | Mark-compact | Single-threaded, STW | Yes | Tiny heaps, single-core, containers | Small/1-CPU heuristics |
| **Parallel ("throughput")** | `-XX:+UseParallelGC` | Parallel copying | Parallel mark-compact | Multi-threaded, STW | Yes | Max throughput, batch | JDK 8 (and earlier) |
| **CMS** *(removed JDK 14)* | `-XX:+UseConcMarkSweepGC` | Parallel copying | Concurrent mark-sweep | Mostly concurrent | **No** (fragments) | Low pause (legacy) | — |
| **G1 (Garbage-First)** | `-XX:+UseG1GC` | Parallel copying | Concurrent mark + incremental compacting | Mostly concurrent | Yes (region evacuation) | Balanced; pause target | **JDK 9+** |
| **ZGC** | `-XX:+UseZGC` | Concurrent | Concurrent compacting | Almost fully concurrent | Yes | Ultra-low pause, huge heaps | — (since JDK 15 prod) |
| **Shenandoah** | `-XX:+UseShenandoahGC` | Concurrent | Concurrent compacting | Almost fully concurrent | Yes | Ultra-low pause | — (OpenJDK builds) |
| **Epsilon** | `-XX:+UseEpsilonGC` (needs `-XX:+UnlockExperimentalVMOptions`) | none | none (no-op) | n/a | n/a | Benchmarking, very short-lived jobs | — |

Notes: ZGC has **generational** mode (`-XX:+ZGenerational`, default in JDK 23+; the original is "non-generational"). G1 has been default since JDK 9. Defaults are chosen **ergonomically** (§4.4).

### 4.2 Heap sizing and generation flags

| Flag | Meaning | Default |
|---|---|---|
| `-Xms<size>` | Initial heap size | Ergonomic (often 1/64 of RAM) |
| `-Xmx<size>` | Maximum heap size | Ergonomic (often 1/4 of RAM, capped) |
| `-Xmn<size>` | Young generation size (fixed) | — (else ratio-based) |
| `-XX:NewRatio=N` | old:young size ratio | 2 (old = 2× young) |
| `-XX:SurvivorRatio=N` | eden:survivor ratio | 8 (each survivor = 1/8 of Eden) |
| `-XX:MaxTenuringThreshold=N` | max age before promotion | 15 (dynamically adjusted ≤ this) |
| `-XX:InitialTenuringThreshold=N` | starting tenuring age | 7 (collector-dependent) |
| `-XX:PretenureSizeThreshold=N` | objects ≥ N bytes go straight to old gen | 0 (off) for Parallel; n/a for G1 |
| `-XX:+AlwaysPreTouch` | touch all heap pages at startup (avoid lazy paging jitter) | off |
| `-XX:+UseCompressedOops` | 4-byte oops for heaps ≤ 32 GB | on |

### 4.3 G1-specific and pause-target flags

| Flag | Meaning | Default |
|---|---|---|
| `-XX:MaxGCPauseMillis=N` | **soft** pause-time goal G1 tries to meet | 200 |
| `-XX:G1HeapRegionSize=N` | region size (power of 2, 1 MB–32 MB) | ergonomic (heap/2048, clamped) |
| `-XX:G1NewSizePercent` / `-XX:G1MaxNewSizePercent` | young gen min/max % of heap | 5 / 60 |
| `-XX:InitiatingHeapOccupancyPercent` (IHOP) | old-gen occupancy that starts a concurrent marking cycle | 45 (adaptive since JDK 9) |
| `-XX:G1MixedGCLiveThresholdPercent` | only collect old regions below this liveness | 85 |
| `-XX:G1HeapWastePercent` | tolerated reclaimable waste before mixed GC | 5 |
| `-XX:G1ReservePercent` | heap held in reserve to avoid promotion failure | 10 |
| `-XX:ConcGCThreads` / `-XX:ParallelGCThreads` | concurrent / STW GC worker thread counts | ergonomic (from CPU count) |

> **Humongous objects (G1)** — any object ≥ **half a region** is "humongous," allocated directly into a contiguous run of **humongous regions** in old gen, bypassing the young path. Many humongous allocations are a known G1 pain point (see §7.4). **IHOP** — the occupancy that *initiates* concurrent marking; set it too high and G1 starts marking too late → evacuation/full-GC risk; too low → wasteful early cycles.

### 4.4 Ergonomics — when you set nothing

If you don't choose a collector or heap size, HotSpot uses **ergonomics**: it inspects available CPUs and memory and picks defaults. Historically a "server-class" machine (≥2 CPUs, ≥2 GB) got Parallel GC (pre-9) or G1 (9+). `-Xmx` defaults to ~1/4 physical memory (capped); `-Xms` to ~1/64. In containers, modern JDKs (8u191+, 10+) honor **cgroup** limits via `-XX:+UseContainerSupport` (on by default) and `-XX:MaxRAMPercentage` (default 25.0). **Always set `-Xmx`/`-Xms` explicitly in production** rather than trusting ergonomics.

### 4.5 GC logging (the #1 diagnostic)

**Unified logging (JDK 9+):** `-Xlog:gc*` controls everything via the `-Xlog` framework.

```
# Rich GC log to a rotating file:
-Xlog:gc*,gc+heap=debug,gc+age=trace:file=gc.log:time,uptime,level,tags:filecount=10,filesize=50M
```

**Legacy (JDK 8):** `-XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintTenuringDistribution -Xloggc:gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=50M`

### 4.6 JDK command-line & runtime tools

| Tool | Purpose | Example |
|---|---|---|
| `jcmd <pid> GC.heap_info` | live heap/generation summary | `jcmd 1234 GC.heap_info` |
| `jcmd <pid> GC.run` | trigger a full GC (diagnostic) | `jcmd 1234 GC.run` |
| `jcmd <pid> GC.class_histogram` | per-class instance counts & bytes | `jcmd 1234 GC.class_histogram` |
| `jcmd <pid> GC.heap_dump <file>` | write an HPROF heap dump | `jcmd 1234 GC.heap_dump /tmp/h.hprof` |
| `jcmd <pid> VM.native_memory` | Native Memory Tracking summary (needs `-XX:NativeMemoryTracking=summary`) | — |
| `jstat -gc <pid> 1000` | live GC stats (Eden/Survivor/Old occupancy, GC counts/times) every 1s | — |
| `jstat -gcutil <pid> 1000` | same as %, very handy | — |
| `jmap -histo:live <pid>` | class histogram (forces a GC with `:live`) | — |
| `jmap -dump:live,format=b,file=h.hprof <pid>` | heap dump | — |
| `jstack <pid>` | thread dump (safepoint-based) | — |
| **GC log analyzers** | GCViewer, GCeasy.io, JClarity Censum | offline log analysis |
| **Eclipse MAT / VisualVM / JProfiler / YourKit** | heap-dump & leak analysis | dominator trees, leak suspects |
| **async-profiler** | unbiased CPU/alloc profiling, `alloc` mode for allocation hot spots | `./profiler.sh -e alloc -d 30 <pid>` |
| **JFR (Java Flight Recorder)** | built-in low-overhead event recorder incl. GC, allocation, pauses | `-XX:StartFlightRecording=...`; view in **JDK Mission Control** |

### 4.7 Programmatic & reference-type APIs

| API | Purpose |
|---|---|
| `System.gc()` / `Runtime.getRuntime().gc()` | *Hint* to run a full GC (often a bad idea; can be disabled with `-XX:+DisableExplicitGC` or routed to concurrent via `-XX:+ExplicitGCInvokesConcurrent`) |
| `Runtime.totalMemory()/freeMemory()/maxMemory()` | crude heap occupancy |
| `java.lang.management.MemoryMXBean`, `GarbageCollectorMXBean`, `MemoryPoolMXBean` | JMX beans: GC counts/times, pool usage, **usage threshold notifications** |
| `java.lang.ref.SoftReference` | GC may clear when memory is tight — for memory-sensitive caches |
| `java.lang.ref.WeakReference` | cleared as soon as the referent is only weakly reachable — for canonicalizing maps (`WeakHashMap`) |
| `java.lang.ref.PhantomReference` + `ReferenceQueue` | enqueued *after* finalization; for reliable post-mortem cleanup (replacement for `finalize()`) |
| `java.lang.ref.Cleaner` (JDK 9+) | modern, safe alternative to finalizers for native-resource cleanup |
| `finalize()` *(deprecated)* | legacy finalization — avoid; unpredictable, can resurrect objects |

> **Reachability strengths** (strongest→weakest): **strong** (ordinary reference — never collected while held) → **soft** (collected only under memory pressure) → **weak** (collected at next GC if only weakly reachable) → **phantom** (already finalized; used only for cleanup notification). The GC consults reference type when deciding what to reclaim.

---

## 5. Code examples by use case

### 5.1 Observing allocation, minor GC, and promotion

```java
// Demonstrates rapid young-gen churn and promotion of survivors.
// Run with:  java -Xms256m -Xmx256m -Xlog:gc*,gc+age=trace AllocChurn
public class AllocChurn {
    // A long-lived structure that will hold survivors -> forces promotion to old gen.
    static final java.util.List<byte[]> retained = new java.util.ArrayList<>();

    public static void main(String[] args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        java.util.Random r = new java.util.Random(42);
        while (System.currentTimeMillis() < deadline) {
            // Most of these die immediately -> reclaimed by minor GC (generational hypothesis in action).
            byte[] ephemeral = new byte[16 * 1024]; // 16 KB, lives only this iteration
            ephemeral[0] = 1;                        // touch so JIT can't elide it

            // ~1% of objects are retained -> they survive minor GCs, age, then get promoted.
            if (r.nextInt(100) == 0) {
                retained.add(new byte[16 * 1024]);
                if (retained.size() > 4000) retained.clear(); // periodically drop -> later major GC work
            }
        }
        System.out.println("retained=" + retained.size());
    }
}
```

What to watch in the log: frequent `Pause Young` events (Eden filling), the **tenuring distribution** (`gc+age=trace`) showing objects aging through survivor ages, and eventually `Pause Full` or G1 mixed collections when `retained` is cleared and old-gen garbage accumulates.

### 5.2 Choosing a collector per workload (launch configs)

```bash
# Batch / ETL job — maximize throughput, pauses don't matter:
java -XX:+UseParallelGC -Xms8g -Xmx8g -XX:+AlwaysPreTouch -jar etl.jar

# Latency-sensitive request service on a moderate heap — balanced, default:
java -XX:+UseG1GC -Xms8g -Xmx8g -XX:MaxGCPauseMillis=100 \
     -Xlog:gc*:file=gc.log:uptime,level,tags:filecount=10,filesize=50M -jar api.jar

# Large heap (50–500 GB), strict sub-ms pause requirement (trading, ad-serving):
java -XX:+UseZGC -XX:+ZGenerational -Xms64g -Xmx64g -XX:+AlwaysPreTouch -jar lowlat.jar

# Tiny container / CLI / single-core — minimal footprint and overhead:
java -XX:+UseSerialGC -Xms64m -Xmx128m -jar tool.jar
```

### 5.3 Monitoring GC live with JMX (`GarbageCollectorMXBean`)

```java
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

public class GcMonitor {
    public static void main(String[] args) throws InterruptedException {
        for (;;) {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                // Each bean is a collector (e.g., "G1 Young Generation", "G1 Old Generation").
                System.out.printf("%-28s count=%-6d totalTimeMs=%d%n",
                        gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
            }
            System.out.println("----");
            Thread.sleep(2000);
        }
    }
}
```

`getCollectionTime()` is cumulative STW time per collector. The derivative (delta-time / delta-wall-clock) is your **GC overhead %** — the single most important throughput metric.

### 5.4 Reacting to memory pressure with a usage-threshold notification

```java
import javax.management.*;
import java.lang.management.*;
import java.util.List;

// Fires a callback when the OLD-gen pool crosses 80% AFTER a collection -> good early-warning signal.
public class HeapPressureAlarm {
    public static void main(String[] args) throws Exception {
        MemoryPoolMXBean oldGen = null;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            // Names vary by collector: "G1 Old Gen", "PS Old Gen", "Tenured Gen", etc.
            if (p.getType() == MemoryType.HEAP && p.isCollectionUsageThresholdSupported()
                    && p.getName().toLowerCase().contains("old")) {
                oldGen = p;
            }
        }
        if (oldGen == null) { System.out.println("no old-gen pool found"); return; }

        long max = oldGen.getUsage().getMax();
        oldGen.setCollectionUsageThreshold((long) (max * 0.80)); // 80% post-GC threshold

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        NotificationEmitter emitter = (NotificationEmitter) mem;
        emitter.addNotificationListener((Notification n, Object h) -> {
            if (MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(n.getType())) {
                System.err.println("OLD GEN >80% after GC — shed load / investigate leak!");
            }
        }, null, null);

        // keep alive...
        Thread.currentThread().join();
    }
}
```

### 5.5 Soft / weak / phantom references — caches and cleanup

```java
import java.lang.ref.*;
import java.util.*;

public class ReferenceTypes {
    // Memory-sensitive cache: entries survive until the JVM is short on memory, then GC clears them.
    static final Map<String, SoftReference<byte[]>> softCache = new HashMap<>();

    // Canonicalizing map: keys vanish automatically once nothing else references them.
    static final Map<String, ?> weakKeyed = new WeakHashMap<>();

    public static void main(String[] args) {
        softCache.put("blob", new SoftReference<>(new byte[10 * 1024 * 1024]));

        byte[] v = softCache.get("blob").get(); // may be null if GC already cleared it under pressure
        System.out.println("soft value present? " + (v != null));

        // PhantomReference + Cleaner pattern (preferred over finalize) for native resource release:
        Cleaner cleaner = Cleaner.create();
        Object resourceOwner = new Object();
        cleaner.register(resourceOwner, () -> System.out.println("native handle released safely"));
        resourceOwner = null; // now phantom-reachable; cleaner runs the action after GC determines unreachability
        System.gc();          // hint only
    }
}
```

### 5.6 Triggering & inspecting GC from the shell (operational runbook)

```bash
PID=$(pgrep -f my-service.jar)

# 1) Live, percentage view of all generations, refreshed every 1s:
jstat -gcutil "$PID" 1000
#   Columns: S0 S1 E O M CCS YGC YGCT FGC FGCT GCT (survivor/eden/old/meta %, GC counts & times)

# 2) Class histogram to spot a leak (top allocators by retained bytes):
jcmd "$PID" GC.class_histogram | head -40

# 3) Capture a heap dump for offline analysis in Eclipse MAT:
jcmd "$PID" GC.heap_dump /tmp/heap-$(date +%s).hprof

# 4) Force a full GC and watch whether old-gen occupancy actually drops
#    (if it barely drops, you have a real leak, not just cache churn):
jcmd "$PID" GC.heap_info
jcmd "$PID" GC.run
jcmd "$PID" GC.heap_info
```

### 5.7 Reproducing and reading an OutOfMemoryError + auto heap dump

```bash
# Always run production with these so the first OOM leaves you a forensic artifact:
java -Xmx512m \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/var/log/myapp/oom.hprof \
     -XX:+ExitOnOutOfMemoryError \   # don't limp along in a corrupt state
     -jar myapp.jar
```

```java
// Guaranteed OOM: an unbounded list -> "java.lang.OutOfMemoryError: Java heap space"
import java.util.*;
public class Leak {
    public static void main(String[] a) {
        List<byte[]> leak = new ArrayList<>();
        while (true) leak.add(new byte[1 << 20]); // 1 MB each, never released
    }
}
```

Open `oom.hprof` in **Eclipse MAT** → "Leak Suspects" report → the **dominator tree** shows `ArrayList` (and its backing array) retaining the most heap; that's your culprit.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **GC overhead %** is the headline throughput number: `time_in_STW / total_wall_clock`. Healthy servers run **< 2–5%**; > 10% means trouble. Compute from `jstat`/JMX or GC logs.
- **Allocation rate** (MB/s of new objects) drives minor-GC frequency. Halving allocation roughly halves young-GC frequency. The cheapest GC tuning is **allocating less** (object pooling for hot paths, primitive arrays over boxed, avoiding gratuitous `String`/`StringBuilder` churn, reusing buffers).
- **Promotion rate** (MB/s flowing young→old) drives old-gen pressure and full-GC frequency. High promotion often means the young gen is too small (objects don't get a chance to die before promotion) or survivors overflow → **premature promotion**.
- **Right-size the young gen.** Too small → frequent minor GCs + premature promotion; too large → longer minor pauses and a smaller old gen. Aim for objects to die in young gen.
- **Set `-Xms == -Xmx`** in production to avoid heap-resize pauses and use `-XX:+AlwaysPreTouch` so pages are committed up front (no first-touch page-fault jitter).

### 6.2 Correctness & concurrency

- GC itself is correct by construction (barriers preserve the tri-color invariant), but **you** can create **logical leaks**: objects reachable but never used (caches without eviction, listeners never deregistered, `ThreadLocal`s on pooled threads, static collections that only grow, `ClassLoader` leaks in app servers). GC will faithfully *keep* them.
- **`ThreadLocal` leak pattern:** a value set on a thread that lives in a pool is never cleared → leaks for the pool's lifetime. Always `remove()` in a `finally`.
- **Finalizer hazards:** `finalize()` runs on a single finalizer thread, can resurrect objects, delays reclamation by ≥1 extra GC cycle, and may never run. Use `Cleaner`/`PhantomReference` instead.

### 6.3 Memory & footprint

- The **GC metadata** has a footprint: card tables (~0.2% of heap), G1 **remembered sets** (can be several % — sometimes large with many cross-region references), mark bitmaps, etc. ZGC/Shenandoah trade extra metadata and barriers for pauses.
- **Off-heap** memory (`DirectByteBuffer`, memory-mapped files, Netty pooled buffers) is *not* on the GC heap — `-Xmx` doesn't bound it. It's reclaimed via `Cleaner`/phantom refs tied to the owning object, so it can lag. Track it with **Native Memory Tracking** (`-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory`).

### 6.4 Security

- **Heap dumps contain secrets** (passwords, tokens, PII in live objects). Protect `*.hprof` files, restrict `HeapDumpPath`, and treat them as sensitive artifacts. Avoid logging full dumps to shared storage.
- `System.gc()` / explicit-GC from untrusted code can be a DoS lever; `-XX:+DisableExplicitGC` neutralizes it.

### 6.5 Observability

- **Always enable GC logging** in production (low overhead, invaluable). Rotate files.
- Export GC metrics to your monitoring system: **young/old GC count & time, GC overhead %, allocation rate, promotion rate, heap-after-GC occupancy, pause p99/max, time-to-safepoint**. Micrometer/Prometheus exporters surface these from JMX.
- **Heap-after-full-GC trend** is the truest leak detector: if old-gen occupancy *immediately after each full GC* keeps climbing over hours/days, you have a leak (not just churn).

### 6.6 Cost

- GC consumes CPU you pay for. A 10% GC overhead is 10% of your compute bill spent collecting garbage. Reducing allocation directly cuts cost.
- Larger heaps reduce GC *frequency* but increase pause *duration* (for non-concurrent collectors) and RAM cost. Concurrent collectors decouple pause from heap size at the cost of CPU and footprint.

### 6.7 Testing

- **Load-test with production-representative allocation patterns**; synthetic benchmarks mislead (e.g., they may all-die-young and never exercise old gen).
- Use **JFR** in load tests to capture allocation hot spots and pause distributions.
- Test with the **same collector and flags** you'll run in prod; collector choice changes behavior dramatically.
- Beware **microbenchmark pitfalls**: dead-code elimination, escape analysis eliminating your allocation, and warmup. Use **JMH** (Java Microbenchmark Harness) which handles these.

### 6.8 Production hardening checklist

- `-Xms == -Xmx`, `-XX:+AlwaysPreTouch`.
- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...` and `-XX:+ExitOnOutOfMemoryError` (let the orchestrator restart a clean instance rather than run degraded).
- Explicit collector choice + GC logging with rotation.
- In containers: `-XX:+UseContainerSupport` (default) and `-XX:MaxRAMPercentage`; ensure heap + off-heap + metaspace + thread stacks + native fit the **container memory limit**, or the OOM-killer (the Linux kernel killing the process for exceeding its cgroup memory limit) will SIGKILL you with no heap dump.

### 6.9 Anti-patterns

- Calling `System.gc()` in application code (causes full STW pauses; often makes things worse).
- Object pooling *everything* (modern young-gen GC is so cheap that pooling short-lived objects usually *hurts* — pools keep objects alive longer, fill old gen, and add complexity). Pool only genuinely expensive resources (threads, connections, large buffers).
- Giant heaps "to be safe" with a non-concurrent collector (longer pauses).
- Relying on `finalize()`.
- Treating `-Xmx` as the process memory cap (it ignores off-heap, metaspace, stacks, code cache).

---

## 7. Advanced topics & deep internals

### 7.1 G1's region model and "garbage-first"

G1 partitions the heap into ~2048 equal **regions** (1–32 MB each). Regions are *roles*, not fixed locations: a region is tagged Eden, Survivor, Old, or Humongous, and roles change between cycles. G1 does **concurrent marking** to estimate the **liveness** (live bytes) of each old region, then preferentially **evacuates the regions with the least live data first** — hence "**garbage-first**": collecting the emptiest old regions yields the most reclaimed space for the least copying work. **Mixed collections** evacuate all young regions plus a selected set of old regions, chosen to meet `-XX:MaxGCPauseMillis`. G1 evacuates (copies survivors into fresh regions) so it compacts incrementally, avoiding CMS-style fragmentation.

G1 lifecycle: young-only collections → when old occupancy hits IHOP, a **concurrent marking cycle** runs (initial mark piggybacked on a young pause, concurrent mark, **remark** STW with SATB drain, cleanup) → then a series of **mixed** collections drains the most-garbage old regions → back to young-only.

### 7.2 ZGC and Shenandoah — concurrent compaction

Both achieve **sub-millisecond, heap-size-independent pauses** by doing *marking and relocation concurrently* with the mutator, leaving only tiny STW root-scan/sync points.

- **ZGC** uses **colored pointers** (metadata bits in the reference) + a **load barrier**: every reference load checks the bits; if the object has moved, the barrier relocates/remaps it and **self-heals** the slot. Supports multi-TB heaps. Generational ZGC (default JDK 23+) adds a young/old split for better throughput.
- **Shenandoah** historically used a **Brooks forwarding pointer** (an extra header word always pointing to the current copy) with a load barrier; newer versions use a load-reference barrier. Also concurrent-compacting.

Cost: a **read barrier on every reference load** (a few extra instructions) → lower peak throughput than Parallel/G1, but flat pauses regardless of heap size.

### 7.3 Tenuring distribution & dynamic threshold

With `-XX:+PrintTenuringDistribution` (or `gc+age=trace`), each minor GC prints how many bytes occupy each age 1..N in the survivor space, plus the **desired survivor size** and the **computed tenuring threshold**. HotSpot adapts the threshold downward when survivors overflow `-XX:TargetSurvivorRatio` (default 50%) so it doesn't waste survivor space. Reading this distribution tells you whether objects die at the right age or get prematurely promoted.

### 7.4 Humongous-object pitfalls (G1)

Objects ≥ ½ region are **humongous**: allocated in contiguous old-gen regions, they can't sit in young gen, are slower to allocate (need contiguous regions → can trigger collections or even full GC if fragmentation prevents finding space), and historically were only reclaimed during marking cycles. A flood of large arrays/buffers can fragment the region space. Mitigations: raise `-XX:G1HeapRegionSize` so the objects are no longer "humongous," or reduce large allocations.

### 7.5 String dedup, AOT, and class metadata GC

- **String deduplication** (`-XX:+UseStringDeduplication`, G1) finds equal `char[]`/`byte[]` backing arrays of distinct `String`s during GC and points them at one shared array — saves heap in string-heavy apps.
- **Metaspace** (class metadata) is *not* the object heap; it's reclaimed when a **class loader** dies (all its classes become unloadable). Sized by `-XX:MaxMetaspaceSize` (unbounded by default → can grow until native OOM). Classloader leaks cause `OutOfMemoryError: Metaspace`.

### 7.6 Lesser-known behaviors

- **`-XX:+UseCountedLoopSafepoints`**: forces safepoint polls inside counted loops to cut **time-to-safepoint** outliers.
- **Allocation pacing/stalls**: if the mutator out-allocates a concurrent collector, ZGC/Shenandoah/G1 may **throttle** allocating threads (allocation stalls) or fall back to STW — a sign of an undersized heap or too-low IHOP.
- **`-XX:+PerfDisableSharedMem`** / safepoint write to `hsperfdata`: GC-stat publishing can stall on a slow disk (the `/tmp/hsperfdata` mmap) — a real, surprising cause of long safepoints.
- **NUMA awareness** (`-XX:+UseNUMA`): on multi-socket machines, the allocator can keep objects on the local NUMA node for memory-bandwidth wins.

---

## 8. Tradeoffs & decision frameworks

### 8.1 The throughput / latency / footprint trilemma

Every collector optimizes at most two of three, sacrificing the third:

- **Throughput** — fraction of CPU time doing *application* work (not GC). Maximized by doing GC rarely and in bulk (large young gen, STW parallel collection). Parallel GC wins here.
- **Latency** — short, predictable pauses. Maximized by doing GC concurrently in tiny STW slivers. ZGC/Shenandoah win, at a per-read barrier CPU cost (lower throughput) and higher footprint.
- **Footprint** — memory used (heap + GC metadata). Minimized by Serial GC and small heaps; concurrent collectors need extra space (to allocate while collecting) and metadata.

You cannot have all three: concurrent low-pause collectors spend CPU on barriers and need headroom (hurting throughput/footprint); high-throughput collectors batch work into long STW pauses (hurting latency); minimal-footprint setups force frequent GC (hurting throughput) or long pauses.

### 8.2 Collector selection

| If you need… | Choose | Because |
|---|---|---|
| Max throughput, pauses irrelevant (batch, ETL, analytics) | **Parallel** | No concurrent overhead; bulk STW collection is most efficient |
| Balanced general-purpose service, heaps ~ few GB–tens of GB | **G1** (default) | Good pause control via target + incremental compaction |
| Strict low/predictable pause, large heap (tens of GB–TB) | **ZGC** (generational) or **Shenandoah** | Concurrent compaction, pauses independent of heap size |
| Tiny heap, single core, minimal footprint, fast startup | **Serial** | Lowest overhead and metadata |
| Microbenchmark / very short job where you never want to collect | **Epsilon** | No-op allocator; OOM when heap exhausts (by design) |

### 8.3 Algorithm tradeoffs

| Algorithm | Pros | Cons | Used for |
|---|---|---|---|
| **Mark-Sweep** | No object movement; simple; fast | Fragmentation; free-list allocation slower | Old gen (CMS, legacy) |
| **Mark-Compact** | No fragmentation; fast bump allocation after | Expensive moving + reference fixup; long STW | Old gen (Serial/Parallel Old, full GC) |
| **Copying** | Touches only live objects; auto-compacts; O(1) free of dead | Wastes ½ space; copies survivors | Young gen (all generational collectors) |

### 8.4 Reference-type decision rules

- **Strong:** the default; the object must live as long as you hold it.
- **Soft:** "keep if you can, drop if memory's tight" — memory-sensitive caches *only*; not a general cache (clearing is unpredictable and can cause latency cliffs).
- **Weak:** "drop as soon as nothing else needs it" — canonical maps / `WeakHashMap`, listener registries.
- **Phantom + `Cleaner`:** reliable post-mortem native cleanup; **never** use `finalize()`.

---

## 9. Failure modes & debugging

### 9.1 `OutOfMemoryError: Java heap space`

**Symptom:** exception + (with the right flag) a heap dump. **Cause:** genuine leak, undersized heap, or a spike of large live objects.
**Diagnose:** Open the auto heap dump in **Eclipse MAT** → *Leak Suspects* and the **dominator tree** (shows which object **retains** the most heap — i.e., would free the most if removed). Watch **old-gen occupancy immediately after full GC** over time (`jstat -gcutil`/GC log): a steady climb that survives full GCs = leak.
**Common leaks:** unbounded caches/`Map`s, `static` collections, un-`remove()`d `ThreadLocal`s on pooled threads, un-deregistered listeners, classloader leaks in app servers.

### 9.2 `OutOfMemoryError: GC overhead limit exceeded`

**Symptom:** thrown (Parallel GC) when **> 98% of recent time** is spent in GC while recovering **< 2%** of heap. **Cause:** the heap is effectively full of live data; GC thrashes. **Fix:** increase heap or fix the leak — this error means you're already collecting constantly to no avail. (Disable detection with `-XX:-UseGCOverheadLimit`, but that just hides the real problem.)

### 9.3 `OutOfMemoryError: Metaspace`

**Cause:** too many classes loaded / classloader leak (common with frequent redeploys, dynamic proxies, scripting, bytecode generation). **Diagnose:** `jcmd <pid> VM.metaspace`, class-loader histogram, MAT classloader leak report. **Fix:** bound with `-XX:MaxMetaspaceSize`, find the leaking loader.

### 9.4 Long / frequent pauses (latency spikes at p99/p999)

**Diagnose with GC logs / GCeasy / Censum:**
- Frequent **minor** GCs → young gen too small or allocation rate too high → enlarge young gen or reduce allocation.
- Long **full** GCs → old gen too small, promotion too high, or wrong collector → switch to G1/ZGC, enlarge old gen, raise IHOP earlier.
- G1 **`to-space exhausted`** / **evacuation failure** → not enough free regions to copy survivors into → enlarge heap, raise `-XX:G1ReservePercent`, lower IHOP.
- CMS **`concurrent mode failure`** (legacy) → CMS couldn't finish before old gen filled → falls back to STW full GC → tune IHOP / heap.

### 9.5 Long **time-to-safepoint** (pauses *not* explained by GC work)

**Symptom:** GC log shows a long total pause but tiny actual GC time; the gap is **getting all threads to the safepoint**. Enable safepoint logging: `-Xlog:safepoint` (JDK 9+) or legacy `-XX:+PrintSafepointStatistics -XX:+PrintGCApplicationStoppedTime`. Look at the *"reaching safepoint"* time. **Causes:** a thread in a long counted loop without a poll (fix: `-XX:+UseCountedLoopSafepoints`), a long JNI/native call, page faults, or slow `hsperfdata` writes (`-XX:+PerfDisableSharedMem`). **Don't profile this with a safepoint-biased sampler** — use **async-profiler**.

### 9.6 Premature promotion / promotion failure

**Symptom:** objects that should die young end up in old gen → old gen fills → frequent full GCs. **Diagnose:** tenuring distribution + rising promotion rate in GC logs. **Fix:** enlarge young gen / survivor spaces, raise `-XX:MaxTenuringThreshold`, reduce allocation bursts.

### 9.7 Container OOM-kill (SIGKILL, no heap dump)

**Symptom:** process vanishes with exit code 137, no Java exception. **Cause:** total RSS (heap + metaspace + thread stacks + code cache + off-heap/Direct buffers + native) exceeded the **cgroup memory limit**; the kernel **OOM-killer** SIGKILLed it. **Diagnose:** Native Memory Tracking, container memory metrics. **Fix:** account for *all* memory regions; set `-Xmx`/`MaxRAMPercentage` leaving headroom for non-heap; bound `MaxMetaspaceSize` and direct-buffer usage (`-XX:MaxDirectMemorySize`).

### 9.8 Real-world incident archetypes

- **"p99 spikes every few minutes"** → periodic full GCs from a slowly filling old gen (often a cache without eviction). Fix the cache or move to a concurrent collector.
- **"Latency cliff under load"** → allocation stalls when the mutator out-allocates a concurrent collector (IHOP too high / heap too small). Lower IHOP, enlarge heap.
- **"Memory grows then OOM-killed in k8s, but heap looks fine"** → off-heap `DirectByteBuffer` leak (e.g., Netty buffers not released) — invisible to `-Xmx`. Track with NMT.
- **"Pauses got worse after we doubled the heap"** → with a non-concurrent collector, bigger heap → longer STW. Switch collector or right-size.

---

## 10. Interview drill

**Q1. What makes an object eligible for garbage collection?**
*Model answer:* It becomes **unreachable** — there is no chain of references from any **GC root** (live thread stack variables/registers, static fields, JNI references, monitors, etc.) to it. GC approximates "won't be used again" with "can't be reached." Reachable-but-never-used objects (logical leaks) are *not* collected.
- *Follow-up: Why not reference counting?* It can't reclaim **cycles** (mutually referencing garbage keeps nonzero counts) and adds write overhead/cache thrash on every pointer assignment.
- *Follow-up: Is `obj = null` necessary?* Rarely — once a local goes out of scope it's no longer a root. Nulling matters mainly for long-lived fields/static collections you want released sooner.
- *Follow-up: Can `finalize()` resurrect an object?* Yes — it can store `this` somewhere reachable, which is one reason finalizers are dangerous and deprecated.

**Q2. Explain the generational hypothesis and how the heap layout exploits it.**
*Model answer:* **Most objects die young.** So the heap splits into **young gen** (Eden + two survivors) collected **frequently and cheaply** with a **copying** collector (cost ∝ survivors, which are few), and **old gen** for promoted survivors, collected **rarely** with mark-compact/concurrent marking. This makes the common case (short-lived garbage) extremely cheap.
- *Follow-up: Why two survivor spaces?* Copying collection needs an empty "to" space to copy survivors into; the two swap roles each minor GC, providing aging without fragmentation.
- *Follow-up: What's promotion/tenuring?* Surviving objects' age increments each minor GC; at the tenuring threshold they're promoted to old gen.
- *Follow-up: What's premature promotion and why is it bad?* Objects promoted before they die (young gen too small/survivors overflow) → fill old gen → more expensive full GCs.

**Q3. Walk me through a minor GC.**
*Model answer:* Eden fills → STW at a **safepoint** → scan roots (including **old→young refs** found via the **card table/remembered set**, not by scanning all old gen) → **copy** live Eden+from-survivor objects to the to-survivor (or promote if aged) leaving **forwarding pointers**, update references → reclaim Eden+from-survivor wholesale → swap survivors, resume mutators. Cost is proportional to live data, not allocation.
- *Follow-up: How are old→young references found cheaply?* **Card table:** old gen is divided into 512-byte cards; a **write barrier** dirties a card on each reference store; the minor GC scans only dirty cards.
- *Follow-up: What's a forwarding pointer?* A pointer left in a moved object's old header so other references can be redirected to its new location and it isn't copied twice.

**Q4. What is a safepoint, and what is time-to-safepoint?**
*Model answer:* A safepoint is an execution point where the JVM has a consistent, precise view of every thread's references (via **OopMaps**), enabling stack walking and object movement. A global safepoint (STW) requires *all* threads to reach a **safepoint poll** (inserted at method returns, loop back-edges, calls). **Time-to-safepoint** is how long the slowest thread takes to get there — it inflates pauses independently of GC work.
- *Follow-up: What's safepoint bias?* Safepoint-based profilers/`jstack` only sample at safepoints, so they're blind to code between polls and misattribute time. Use **async-profiler** (signal/`perf`-based) for unbiased profiles.
- *Follow-up: Cause of a long TTSP?* A counted loop without polls, a long JNI call, page faults, or slow `hsperfdata` writes.

**Q5. Compare mark-sweep, mark-compact, and copying.**
*(See §8.3 table.)* Mark-sweep: no movement, fast, but fragments (free list). Mark-compact: no fragmentation, but expensive moves + reference fixup. Copying: touches only live, auto-compacts, but wastes half the space. Generational collectors use copying for young, mark-compact/sweep for old.
- *Follow-up: Why copying for young gen?* Most young objects are dead, so copying the *few* survivors is cheap and you free everything else in O(1).
- *Follow-up: Why not copying for old gen?* Most old objects are live → copying nearly everything (and wasting half the space) is too expensive.

**Q6. Minor vs. major vs. full GC?**
*Model answer:* **Minor** = young only (frequent, fast). **Major** = old gen (slower; term is fuzzy). **Full** = entire heap + usually metaspace, with compaction, STW, slow. "Major" and "full" are *not* synonyms in general. G1's analog of incremental old-gen collection is the **mixed** collection.
- *Follow-up: What triggers a full GC?* Old gen full, metaspace full, `System.gc()`, promotion/evacuation failure, concurrent-mode failure.
- *Follow-up: How to avoid full GCs?* Right-size old gen, reduce promotion, use a concurrent collector, start concurrent marking earlier (lower IHOP).

**Q7. How does G1 work and why "garbage-first"?**
*Model answer:* Heap split into ~2048 regions with dynamic roles (Eden/Survivor/Old/Humongous). Concurrent marking estimates each old region's liveness; G1 evacuates the **emptiest old regions first** (most reclaim per unit copy work) during **mixed** collections, sized to meet `-XX:MaxGCPauseMillis`. Evacuation compacts incrementally, avoiding fragmentation.
- *Follow-up: What's IHOP?* The old-gen occupancy that initiates concurrent marking; too high risks evacuation failure/full GC, too low wastes cycles.
- *Follow-up: Humongous objects?* Objects ≥ ½ region, allocated contiguously in old gen; many of them fragment region space and slow allocation. Mitigate with a larger region size.

**Q8 (senior signal). You have a latency-critical service with a 64 GB heap and p99 pause requirements under 1 ms. Which collector, and what do you trade away?**
*Model answer:* **ZGC (generational)** or **Shenandoah** — concurrent compaction gives pauses **independent of heap size**, well under 1 ms. You trade **throughput** (a load barrier runs on every reference read, a few % CPU) and **footprint** (extra metadata + headroom to allocate while collecting). You must also provision **CPU headroom** for concurrent GC threads and avoid **allocation stalls** (size the heap and IHOP so the mutator never out-allocates the collector). Parallel GC would give better throughput but multi-hundred-ms STW pauses on that heap — disqualifying.
- *Follow-up: How would you validate?* Load-test at peak allocation with the real collector; measure pause distribution via JFR/GC logs; watch for allocation stalls and CPU saturation.
- *Follow-up: When would you NOT pick ZGC?* If it's a batch job where throughput matters and pauses don't — Parallel GC then; or a tiny service where Serial/G1 footprint wins.

**Q9 (senior signal). Your service's p99 latency spikes every ~3 minutes; CPU and request rate are flat. Diagnose.**
*Model answer:* Periodic spikes with flat load smell of **periodic full/old GCs**. Pull GC logs: confirm the spike coincides with a full GC (or G1 mixed/concurrent failure). Likely cause: old gen slowly fills (a growing cache or steady promotion) until it triggers a costly STW collection. Check **old-gen occupancy after each full GC** — if it resets fully, it's churn (tune sizing/collector); if it climbs, it's a **leak** (heap dump → dominator tree). Fix: bound the cache, right-size old gen, or move to a concurrent collector to eliminate the STW spike.
- *Follow-up: Could it be safepoint, not GC?* Yes — check `-Xlog:safepoint`; if total pause ≫ GC time, it's time-to-safepoint, investigate counted loops/native calls.
- *Follow-up: How to confirm it's not the OS?* Rule out CPU steal, swapping, and container throttling with system metrics before blaming GC.

**Q10 (senior signal). Engineers want to object-pool small request DTOs "to reduce GC." Do you approve?**
*Model answer:* Usually **no**. Modern young-gen collection is so cheap (cost ∝ survivors; dead objects cost nothing) that pooling short-lived objects typically **hurts**: pooled objects stay reachable longer, get **promoted** to old gen, increase old-gen pressure and full-GC frequency, and add concurrency bugs and complexity. Pooling pays off only for genuinely expensive-to-create or off-heap resources (threads, DB connections, large/Direct buffers). The better lever is **allocating less** on hot paths and tuning young-gen size.
- *Follow-up: When does pooling help?* Large arrays/Direct buffers, OS-level resources, or extreme zero-GC requirements (then consider Epsilon + careful sizing).
- *Follow-up: How would you prove it either way?* JMH microbenchmark + load test measuring GC overhead %, promotion rate, and p99 with and without pooling.

**Q11. Explain TLABs.**
*Model answer:* Each thread gets a private slice of Eden — a **Thread-Local Allocation Buffer** — within which it allocates by **bumping a pointer with no synchronization**. Only TLAB refills hit the shared heap (a CAS/lock), so per-object allocation is essentially free and contention-free. TLABs are adaptively sized; their unused tails are filled with dummy objects to keep the heap parsable.
- *Follow-up: What if an object is bigger than a TLAB?* It's allocated directly in shared Eden (or old gen if very large), bypassing the TLAB fast path.
- *Follow-up: How does escape analysis relate?* If the JIT proves an object doesn't escape, it may scalar-replace/stack-allocate it — no heap allocation at all, the cheapest "GC."

**Q12. What are write barriers and why are they needed for concurrent GC?**
*Model answer:* A **write barrier** is JIT-injected code on reference stores. Generationally it **card-marks** so old→young refs are found at minor GC. For **concurrent marking** it preserves the **tri-color invariant**: with the mutator changing the graph, a barrier (SATB records the *old* referent; incremental-update records the *new* one) prevents the **lost-object** bug where a live white object is freed because a black object was made to point to it after being scanned.
- *Follow-up: Read barrier?* Used by concurrent **compactors** (ZGC/Shenandoah): on each reference load it detects relocated objects and follows the **forwarding pointer** (self-healing the slot). It's why those collectors trade throughput for flat pauses.
- *Follow-up: SATB vs. incremental update?* SATB keeps everything live-at-cycle-start alive this cycle (may retain a little floating garbage); IU re-scans newly-installed references. G1/Shenandoah use SATB; CMS used IU.

---

## 11. Glossary

- **Allocation rate** — bytes of new objects created per unit time; drives minor-GC frequency.
- **AlwaysPreTouch** — flag committing/zeroing all heap pages at startup to avoid first-touch page-fault jitter.
- **Barrier** — JIT-injected code around heap pointer reads/writes that informs the GC of relevant mutations.
- **Black/Gray/White** — tri-color marking states: done/in-progress/unvisited.
- **Card / Card table** — 512-byte heap chunk and the byte-array map tracking which cards are "dirty" (written since last scan).
- **CAS (compare-and-swap)** — atomic "set X to B if it's still A" instruction; basis of lock-free allocation.
- **cgroup** — Linux kernel control group bounding a container's CPU/memory; exceeding the memory limit triggers the OOM-killer.
- **CMS (Concurrent Mark Sweep)** — legacy low-pause collector, removed in JDK 14; concurrent but non-compacting (fragments).
- **Colored pointers (ZGC)** — GC metadata bits stored inside the 64-bit reference itself.
- **Compaction** — sliding live objects together to eliminate fragmentation.
- **Compressed oops** — 4-byte object pointers for heaps ≤ 32 GB.
- **Concurrent-mode failure** — CMS couldn't finish before old gen filled → STW full-GC fallback.
- **Copying collection (scavenge)** — collect a space by copying live objects to another space, freeing the original wholesale.
- **Counted loop** — a JIT-recognized bounded loop; historically lacked safepoint polls.
- **Dominator tree** — heap-analysis structure showing which object **retains** (would free) the most memory.
- **Eden** — the region where new objects are allocated.
- **Epsilon** — no-op collector (allocate-only, never collect).
- **Ergonomics** — JVM auto-selection of collector/heap sizes from CPU/RAM.
- **Escape analysis / scalar replacement** — JIT optimization eliminating heap allocation for non-escaping objects.
- **Evacuation failure / to-space exhausted (G1)** — no free regions to copy survivors into → expensive fallback.
- **Filler object** — dummy object placed in unused TLAB tails to keep the heap parsable.
- **Finalizer / `finalize()`** — deprecated cleanup hook; unpredictable, can resurrect objects; use `Cleaner`.
- **Floating garbage** — objects that became garbage during a concurrent cycle but survive it (collected next cycle).
- **Forwarding pointer** — pointer left in a moved object's old header to its new location.
- **Footprint** — total memory used by the process (heap + GC metadata + non-heap).
- **Full GC** — collection of the entire heap (and usually metaspace), compacting, STW, slow.
- **G1 (Garbage-First)** — region-based, mostly-concurrent, pause-target collector; default since JDK 9.
- **GC overhead %** — fraction of wall-clock time spent in STW GC.
- **GC root** — a reference treated as inherently live; a starting point for reachability.
- **Generational hypothesis** — empirical observation that most objects die young.
- **Heap** — the GC-managed region where objects live.
- **Heap parsability** — property that the heap can be walked object-by-object at a safepoint.
- **Humongous object (G1)** — object ≥ ½ region, allocated in contiguous old-gen regions.
- **IHOP (InitiatingHeapOccupancyPercent)** — old-gen occupancy that starts G1 concurrent marking.
- **Incremental update (IU)** — concurrent-marking technique recording newly-installed references (CMS-style).
- **JFR (Java Flight Recorder)** — built-in low-overhead event recorder; viewed in JDK Mission Control.
- **JNI** — Java Native Interface; native code holding references (which are GC roots).
- **JMH** — Java Microbenchmark Harness; correct micro-benchmarking avoiding JIT/GC artifacts.
- **Klass pointer** — header field pointing to a class's metadata in metaspace.
- **Lost-object problem** — concurrent-marking hazard where a live object is wrongly freed; prevented by barriers.
- **Major GC** — collection of the old generation (term is fuzzy).
- **Mark word** — header field holding hash/lock/age bits.
- **Mark-Compact** — mark live, then slide them contiguous (no fragmentation).
- **Mark-Sweep** — mark live, then free unmarked into a free list (fragments).
- **Metaspace** — native-memory region for class metadata; reclaimed when classloaders die.
- **Minor GC (young GC)** — collection of the young generation only.
- **Mixed collection (G1)** — collects all young + selected old regions.
- **Mutator** — application thread (mutates the object graph).
- **NUMA** — Non-Uniform Memory Access; multi-socket memory locality.
- **Off-heap memory** — memory outside the GC heap (Direct buffers, mmap); not bounded by `-Xmx`.
- **OOM-killer** — Linux kernel mechanism that SIGKILLs a process exceeding its memory limit.
- **OopMap (stack map)** — JIT metadata listing which slots/registers hold references at a location.
- **Old gen (tenured)** — region for long-lived, promoted objects.
- **Parallel GC** — multi-threaded STW throughput collector.
- **PhantomReference** — weakest reference; enqueued after finalization for cleanup notification.
- **Premature promotion** — objects promoted to old gen before dying (young gen too small).
- **Promotion / Tenuring** — moving an aged survivor from young to old gen.
- **Reachability** — being connected to a GC root via references; the liveness criterion.
- **Reference counting** — liveness via per-object counts; can't reclaim cycles (not used by HotSpot).
- **Remembered set (RSet)** — per-region record of incoming references; G1's generalization of the card table.
- **Safepoint** — execution point with a precise, consistent thread/reference view.
- **Safepoint bias** — profiling distortion from sampling only at safepoints.
- **SATB (snapshot-at-the-beginning)** — concurrent-marking technique keeping cycle-start-live objects alive (G1/Shenandoah).
- **Serial GC** — single-threaded STW collector; minimal footprint.
- **Shenandoah** — concurrent-compacting low-pause collector.
- **SoftReference** — reference cleared only under memory pressure.
- **Stop-the-world (STW)** — interval where all mutator threads are suspended.
- **Survivor spaces (S0/S1)** — two young-gen half-spaces holding aging survivors.
- **Tenuring threshold** — survivor age at which an object is promoted.
- **Throughput** — fraction of CPU spent on application (not GC) work.
- **TLAB** — Thread-Local Allocation Buffer; per-thread lock-free allocation chunk.
- **Time-to-safepoint (TTSP)** — time for the slowest thread to reach a safepoint.
- **Tri-color invariant** — "no black object points to a white object"; correctness condition for concurrent marking.
- **WeakReference** — reference cleared once the referent is only weakly reachable.
- **Write barrier** — barrier on reference stores (card marking, SATB/IU enqueue).
- **Young gen (nursery)** — Eden + survivors; where allocation happens and minor GC operates.
- **ZGC** — concurrent-compacting, sub-ms-pause collector using colored pointers + load barrier; scales to TB heaps.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**Core idea:** Reachable from a **GC root** ⇒ live; else garbage. Tracing, not reference counting (cycles + write cost).

**Generational layout:** `Young = Eden + S0 + S1` (copying GC, frequent/cheap) → promote aged survivors → `Old` (mark-compact/concurrent, rare/expensive). Most objects die young.

**GC kinds:** Minor (young) < Major (old) < Full (whole heap + metaspace, STW, slow). Avoid full GCs in latency systems.

**Key numbers / defaults:** card = **512 B**; `MaxTenuringThreshold` = **15**; `NewRatio` = **2**; `SurvivorRatio` = **8**; G1 `MaxGCPauseMillis` = **200 ms**; G1 IHOP ≈ **45%**; compressed oops cap ≈ **32 GB**; container `MaxRAMPercentage` = **25%**; healthy GC overhead **< 2–5%**.

**Mechanisms:** TLAB = lock-free per-thread allocation; safepoint = consistent thread state (OopMaps) for GC; write barrier = card marking + SATB/IU; read barrier = concurrent relocation (ZGC/Shenandoah); card table/RSet = find old→young refs without scanning old gen.

**Collectors:** Serial (footprint) · Parallel (throughput) · G1 (balanced, default) · ZGC/Shenandoah (low pause, big heaps) · Epsilon (no-op).

**Trilemma:** Throughput vs. Latency vs. Footprint — pick two.

**Decision rules:** batch→Parallel; general service→G1; strict low-pause/huge heap→ZGC; tiny→Serial. Don't call `System.gc()`. Don't pool short-lived objects. Always: `-Xms=-Xmx`, `+AlwaysPreTouch`, `+HeapDumpOnOutOfMemoryError`, GC logging on.

**Debug map:** heap OOM→MAT dominator tree + post-full-GC occupancy trend; long pause but tiny GC time→safepoint (`-Xlog:safepoint`); SIGKILL 137→container memory (NMT, account off-heap); p99 spikes→periodic full GC.

### 12.2 Self-test (no answers)

1. Why can a reference-counting collector never reclaim a doubly-linked list that you've dropped all external references to, and how does a tracing collector handle it?
2. During a concurrent mark, the mutator stores a reference to an unmarked (white) object into an already-scanned (black) object and then deletes the only other path to that white object. What goes wrong, and which two barrier strategies prevent it?
3. You see GC log entries where the total pause is 40 ms but the reported GC work is 2 ms. What is consuming the other 38 ms, how do you confirm it, and what are three possible root causes?
4. A service running Parallel GC on a 40 GB heap meets its throughput SLA but violates a new 5 ms p99 pause requirement. What changes, and what will you give up in return?
5. Your container keeps getting OOM-killed (exit 137) even though `jstat` shows the Java heap comfortably below `-Xmx`. List the memory regions that could be responsible and the tool you'd use to attribute the usage.
6. Explain why enlarging the young generation can simultaneously *reduce* full-GC frequency and *increase* individual minor-GC pause times.
7. An engineer proposes pooling millions of short-lived request objects to "help the GC." Argue both why this usually backfires and the narrow cases where pooling is justified.

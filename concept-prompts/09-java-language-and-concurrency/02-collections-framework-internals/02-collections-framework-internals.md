# Collections Framework Internals

> A definitive engineering-handbook chapter for senior Java/JVM backend developers. From first principles to deep internals, tuning, debugging, and interview mastery.

---

## 1. Overview & where it fits

The **Java Collections Framework (JCF)** is the standard library of data structures and algorithms shipped in `java.util` (since JDK 1.2, 1998) plus concurrent variants in `java.util.concurrent` (since JDK 5, 2004). It gives you reusable, well-tested implementations of lists, sets, maps, queues, and deques behind a small set of interfaces, so you almost never hand-roll a hash table or a balanced tree again.

**The problem it solves.** Before the framework, Java had `Vector`, `Hashtable`, `Stack`, and `Enumeration` — a grab-bag of synchronized, inconsistent classes with no shared abstraction. You could not write a method that accepted "any sequence" and worked uniformly. The JCF unified everything under interfaces (`Collection`, `List`, `Set`, `Map`, `Queue`, `Deque`), provided multiple implementations per interface with different performance tradeoffs, and supplied algorithms (`Collections.sort`, `binarySearch`, etc.) that work against the interfaces. This is **separation of interface from implementation**: your code depends on `List<Order>`, and you swap `ArrayList` for `LinkedList` (or a copy-on-write list) without touching callers.

**When you reach for it.** Essentially always — any time you hold more than one of something in memory. The skill is not *whether* to use a collection but *which* one. The default trio that covers 90% of code:
- `ArrayList` for ordered sequences,
- `HashMap` for key→value lookup,
- `HashSet` for membership tests.

Reach beyond the defaults when you need ordering guarantees (`TreeMap`, `LinkedHashMap`), thread-safety (`ConcurrentHashMap`, `CopyOnWriteArrayList`), queue/stack semantics (`ArrayDeque`, `PriorityQueue`), or specialized memory/access patterns.

**One-paragraph mental model.** A *collection* is an object that holds references to other objects (the *elements*). The JCF organizes these as a hierarchy of interfaces describing *contracts* (what operations exist and what they promise) and a set of *implementations* describing *mechanisms* (arrays, linked nodes, hash tables, balanced trees). Every choice trades the same currency: **time complexity** (how fast operations are, expressed in Big-O), **space overhead** (bytes per element, cache friendliness), **ordering guarantees** (insertion order? sorted? none?), and **concurrency** (safe for multiple threads or not). Mastering collections means knowing, for each implementation, exactly *how it stores data in memory*, *what each operation costs*, and *what contract you must uphold* (notably `equals`/`hashCode`) for it to work correctly.

---

## 2. Foundations from first principles

### 2.1 What is a "collection"?

A **collection** is a single object that groups multiple elements into one unit. Elements are object *references* (Java collections cannot directly store primitives like `int`; they store boxed wrappers like `Integer` — more on autoboxing later). The framework has three structural roots:

- **`Iterable<T>`** — the supertype of `Collection`. Its single method `iterator()` returns an `Iterator<T>`. Anything `Iterable` works with the enhanced for-loop (`for (T x : coll)`).
- **`Collection<E>`** — the root of single-element groupings: lists, sets, queues. Methods like `add`, `remove`, `contains`, `size`, `iterator`.
- **`Map<K,V>`** — *not* a `Collection`. A map is a set of key→value associations. It is its own hierarchy because its element shape (a pair) differs from a plain element.

> **Beginner aside — interface vs implementation.** An *interface* in Java is a named contract: a list of method signatures with no code. A *class* that `implements` the interface supplies the actual code. `List` is an interface; `ArrayList` is a class implementing it. You program against the interface (`List<String> names = new ArrayList<>();`) so you can change the right-hand side later without breaking the rest of your code.

### 2.2 The interface hierarchy (the map of the territory)

```
                 Iterable<T>
                     │
                 Collection<E>
        ┌────────────┼──────────────┐
      List<E>      Set<E>         Queue<E>
        │            │               │
        │         SortedSet<E>     Deque<E>
        │            │
        │         NavigableSet<E>

   Map<K,V>   (separate root, NOT a Collection)
        │
   SortedMap<K,V>
        │
   NavigableMap<K,V>
```

Key contracts:

- **`List<E>`** — an *ordered* sequence allowing duplicates and positional access by index (`get(int)`, `add(int, E)`). Order is *insertion order* (the order you put elements in), preserved until you reorder.
- **`Set<E>`** — a collection with *no duplicate elements*, as defined by `equals`. At most one `null` (for `HashSet`/`LinkedHashSet`).
- **`SortedSet<E>` / `NavigableSet<E>`** — a `Set` whose iteration is in sorted order; `NavigableSet` adds navigation methods (`floor`, `ceiling`, `higher`, `lower`, `pollFirst`).
- **`Queue<E>`** — a collection designed for holding elements prior to processing, typically FIFO (first-in-first-out). Offers `offer`/`poll`/`peek` (which return special values like `false`/`null` on failure) alongside `add`/`remove`/`element` (which throw).
- **`Deque<E>`** ("deck", double-ended queue) — insertion and removal at *both* ends. Can act as a FIFO queue *or* a LIFO stack.
- **`Map<K,V>`** — associates unique keys to values. `put`, `get`, `remove`, `containsKey`, `keySet`, `values`, `entrySet`.
- **`SortedMap` / `NavigableMap`** — keys iterated in sorted order plus navigation methods.

> **Beginner aside — FIFO vs LIFO.** FIFO (first-in, first-out) is a queue at a shop: the first person in line is served first. LIFO (last-in, first-out) is a stack of plates: you take the last plate you put on top. A `Deque` can do both depending on which end you push and pop.

### 2.3 The two contracts that make collections *work*: `equals` and `hashCode`

Almost every bug in collections traces back to these two methods. They are defined on `java.lang.Object` and you override them on your element/key types.

**`boolean equals(Object o)`** — defines *logical equality*. Two objects are "equal" if `a.equals(b)` returns `true`. The contract (from the Javadoc):
1. **Reflexive:** `x.equals(x)` is `true`.
2. **Symmetric:** `x.equals(y)` iff `y.equals(x)`.
3. **Transitive:** if `x.equals(y)` and `y.equals(z)` then `x.equals(z)`.
4. **Consistent:** repeated calls return the same result if the objects don't change.
5. **Null:** `x.equals(null)` is `false`.

**`int hashCode()`** — returns an integer "bucket fingerprint." The contract:
1. **Consistent:** same object → same hash across calls (within one run, assuming no field changes).
2. **Equality implies equal hashes:** if `a.equals(b)` then `a.hashCode() == b.hashCode()`. **This is the rule everyone breaks.**
3. **Unequal objects *may* share a hash** (a *collision*); good hash functions minimize this but it is allowed.

> **Why it matters.** Hash-based collections (`HashMap`, `HashSet`) find an object by first computing `hashCode()` to pick a bucket, then using `equals()` to find the exact match within that bucket. If you override `equals` but *not* `hashCode`, two "equal" objects can land in different buckets, so `map.get(key)` returns `null` even though an equal key was inserted. **Always override both together.**

> **Beginner aside — identity vs equality.** *Identity* is `==`: do two references point to the *same object in memory*? *Equality* is `.equals()`: are two (possibly distinct) objects *logically the same value*? `new String("a") == new String("a")` is `false` (two objects) but `.equals` is `true`. Hash collections use `equals`, not `==`, unless you specifically choose an `IdentityHashMap`.

A correct, modern implementation (Java 7+):

```java
import java.util.Objects;

public final class Money {
    private final long cents;
    private final String currency;

    public Money(long cents, String currency) {
        this.cents = cents;
        this.currency = currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;                 // fast path: identity
        if (!(o instanceof Money)) return false;    // null + type check in one
        Money m = (Money) o;
        return cents == m.cents && currency.equals(m.currency);
    }

    @Override
    public int hashCode() {
        // Objects.hash boxes into an array; for hot paths compute manually:
        return Objects.hash(cents, currency);
    }
}
```

For hot paths, hand-roll to avoid the varargs array allocation in `Objects.hash`:

```java
@Override
public int hashCode() {
    int result = Long.hashCode(cents);   // 31-based polynomial accumulation
    result = 31 * result + currency.hashCode();
    return result;
}
```

> **Why 31?** It is an odd prime; `31 * i == (i << 5) - i`, which the JIT can turn into a shift and subtract. Using a prime spreads bits to reduce collisions. This is the same scheme `String.hashCode()` uses.

**Records (Java 16+)** generate correct `equals`/`hashCode`/`toString` automatically:

```java
public record Money(long cents, String currency) {}
// equals, hashCode, toString are auto-generated and contract-compliant
```

Use records for value-type keys whenever possible — they eliminate the #1 source of collection bugs.

### 2.4 Mutability warning: never mutate a key/element after insertion

If you change a field that participates in `hashCode`/`equals` *after* the object is in a `HashSet`/`HashMap`, the object's hash changes, but it stays in the old bucket. It becomes a "ghost": you can iterate to it but `contains`/`get` will look in the new bucket and miss it. **Hash-collection keys must be effectively immutable** with respect to their hash-relevant fields.

### 2.5 Big-O in 60 seconds

**Big-O** describes how an operation's cost grows with the number of elements `n`, ignoring constants:
- **O(1)** — constant: array index, hash lookup (average).
- **O(log n)** — logarithmic: balanced-tree operations (red-black tree).
- **O(n)** — linear: scanning a list, linked-list random access.
- **O(n log n)** — comparison sorting.
- **Amortized O(1)** — usually O(1), but occasionally O(n) (e.g. `ArrayList.add` when it resizes); averaged over many calls it is O(1).

> **Beginner aside — amortized cost.** "Amortized" means spreading a rare expensive operation across many cheap ones. Adding to an `ArrayList` is O(1) almost always, but every so often it copies the whole backing array (O(n)). Because the array *doubles*, those expensive copies happen exponentially less often, so the *average* per-add cost stays O(1).

---

## 3. How it works internally

This section is the heart of the document. We go implementation by implementation, tracing memory layout, control flow, and state transitions.

### 3.1 `ArrayList` internals

**Storage.** A single `Object[] elementData` plus an `int size`. The array's *capacity* (its length) is ≥ `size`. Empty new `ArrayList()` starts with a shared empty array and allocates the real backing array (default capacity **10**) lazily on first `add`.

**`add(E e)` control flow:**
1. `ensureCapacityInternal(size + 1)` — if `size + 1 > elementData.length`, **grow**.
2. **Grow:** `newCapacity = oldCapacity + (oldCapacity >> 1)` — i.e. **1.5×** (old + half old). (In Java 6 and earlier it was `oldCapacity * 3/2 + 1`.) Then `Arrays.copyOf` copies the old contents into the new, larger array. This copy is O(n) — the source of the amortized cost.
3. `elementData[size++] = e`.

**`add(int index, E e)`** — `System.arraycopy` shifts every element from `index` onward one slot right: O(n).

**`get(int index)`** — bounds-check, then `return elementData[index]`: O(1). This is `ArrayList`'s superpower.

**`remove(int index)`** — `System.arraycopy` shifts elements left to close the gap, null out the last slot (to let GC reclaim): O(n).

**`remove(Object o)`** — linear scan with `equals` to find index, then shift: O(n).

> **Beginner aside — `System.arraycopy`.** A native (C-level) intrinsic that block-copies a contiguous range of an array far faster than a Java loop. The JIT often replaces it with vectorized memory moves. Bulk shifts in `ArrayList` ride on this, which is why even "slow" O(n) shifts are fast in absolute terms — contiguous memory plus CPU cache prefetching.

**Why `ArrayList` is fast in practice.** Elements live in *contiguous memory*. Modern CPUs prefetch sequential cache lines, so iterating an `ArrayList` is extremely cache-friendly. A `LinkedList` of the same data scatters nodes across the heap, causing cache misses on every hop.

**`trimToSize()` / `ensureCapacity(int)`** — manual capacity control. Pre-size with `new ArrayList<>(expectedSize)` when you know the count to avoid repeated resizes.

### 3.2 `LinkedList` internals (and why it is usually wrong)

**Storage.** A doubly-linked list of `Node` objects, each holding `item`, `next`, and `prev` references, plus `first`/`last`/`size` fields in the list. `LinkedList` implements both `List` and `Deque`.

**Costs:**
- `addFirst`/`addLast`/`add` (at end) — O(1): allocate a node, splice it in.
- `get(int index)` — **O(n)**: walk from the nearer end (it optimizes by starting from head or tail, whichever is closer, so worst case is n/2 hops).
- `add(int index, E)` / `remove(int index)` — O(n) to *reach* the index, then O(1) to splice.
- Removal via `ListIterator` while iterating — O(1) at the cursor.

**Why `LinkedList` is usually the wrong choice:**
1. **Per-node overhead.** Each element costs an extra heap object (the `Node`) with two reference fields (16 bytes of references on a 64-bit JVM with compressed oops, plus object header ~16 bytes = ~24–40 bytes overhead *per element*). An `ArrayList` costs ~one reference slot per element (with occasional slack).
2. **Cache hostility.** Nodes are scattered; iteration suffers pointer-chasing cache misses. `ArrayList` iteration is often *several times* faster despite the same Big-O for sequential access.
3. **The supposed advantage rarely materializes.** People reach for `LinkedList` for "fast inserts in the middle," but to insert in the middle you must *first traverse* to that position — O(n). The only true O(1) mid-list insertion is via a `ListIterator` positioned there, which is a niche pattern.
4. **`get(int)` is O(n)** — so any index-based loop on a `LinkedList` is accidentally O(n²).

**Verdict:** Use `ArrayList` by default. Use `ArrayDeque` (not `LinkedList`) when you need queue/stack behavior. `LinkedList` is justified only when you genuinely need a `Deque` *and* `List` interface simultaneously, or O(1) splicing during iteration — rare.

### 3.3 `HashMap` internals (the crown jewel)

This is the most-asked interview topic and the most important to understand deeply. Numbers below are for **Java 8+** (HotSpot/OpenJDK), where treeification was introduced. Earlier versions differ; flagged where relevant.

#### 3.3.1 Storage layout

```
transient Node<K,V>[] table;   // the bucket array; length is always a power of 2
int size;                      // number of key-value mappings
int threshold;                 // resize trigger = capacity * loadFactor
final float loadFactor;        // default 0.75
```

Each bucket (`table[i]`) holds either:
- `null` (empty), or
- a **singly-linked list** of `Node<K,V>` (`hash`, `key`, `value`, `next`), or
- a **red-black tree** of `TreeNode<K,V>` (once a bucket gets too long).

**Default capacity:** 16 (so 16 buckets). **Default load factor:** 0.75. **Default initial threshold:** 16 × 0.75 = **12** — the map resizes when it reaches 12 entries.

#### 3.3.2 Hashing — the `hash()` spread function

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

**What this does and why.** The bucket index is computed as `(table.length - 1) & hash`. Because `table.length` is a power of two, `length - 1` is a bitmask of the low bits (e.g. capacity 16 → mask `0b1111`). That means *only the low 4 bits of the hash decide the bucket*. If many keys have hashes differing only in *high* bits, they'd all collide. The spread function XORs the high 16 bits into the low 16 bits (`h ^ (h >>> 16)`), mixing high-bit entropy down so it influences the bucket choice. It is a cheap one-instruction "stir." `null` keys always hash to 0, landing in bucket 0.

> **Beginner aside — `>>>` and `&` and `^`.** `>>>` is unsigned right shift (fills with zeros). `&` is bitwise AND. `^` is bitwise XOR. `n & (length-1)` is a fast modulo-by-power-of-two: equivalent to `n % length` when `length` is a power of two, but a single CPU instruction instead of a division.

#### 3.3.3 `put(K, V)` step-by-step control flow

1. Compute `h = hash(key)`.
2. If `table` is `null` (lazy init), `resize()` to allocate the initial 16-bucket array.
3. Compute index `i = (n - 1) & h` where `n = table.length`.
4. If `table[i]` is empty, place a new `Node` there. Done.
5. Otherwise there is a collision; walk the bucket:
   - If the first node's hash and key match (`p.hash == h && (p.key == key || key.equals(p.key))`), it's an update — remember it.
   - Else if the node is a `TreeNode`, delegate to the red-black tree's `putTreeVal`.
   - Else walk the linked list:
     - If a matching key is found, it's an update.
     - If the end is reached, append a new node. **If the bin length now reaches `TREEIFY_THRESHOLD` (8)**, call `treeifyBin`.
6. If it was an update, replace the value and return the old value.
7. Else `++size`; if `size > threshold`, **`resize()`**.

> **Key detail — the equals check is short-circuited by identity and hash.** `p.key == key || key.equals(p.key)` first tries `==` (cheap, catches interned/same-reference keys) and only then `equals`. And `equals` is only called when hashes already match. So a good `hashCode` keeps `equals` calls rare.

#### 3.3.4 Treeification — the "treeify at 8" rule

When a single bucket's linked list grows to **8 nodes** (`TREEIFY_THRESHOLD = 8`), `HashMap` tries to convert that bucket from a linked list into a **red-black tree**, turning that bucket's worst-case lookup from O(n) to O(log n).

**Crucial caveat:** treeification only happens if the table also has at least **`MIN_TREEIFY_CAPACITY = 64`** buckets. If the table is smaller, `HashMap` *resizes instead* (doubling capacity), on the theory that a small, dense table just needs more buckets, not trees. So in a 16-bucket map, a bucket reaching 8 triggers a resize, not treeification.

**Untreeify:** when a tree bin shrinks to **`UNTREEIFY_THRESHOLD = 6`** nodes or fewer (during a resize split), it reverts to a linked list. The gap between 8 (treeify) and 6 (untreeify) is **hysteresis** — it prevents thrashing back and forth around a single boundary value.

> **Beginner aside — red-black tree.** A red-black tree is a *self-balancing binary search tree*. Each node is colored red or black, and a set of invariants (root is black, no two reds in a row, every root-to-leaf path has the same number of black nodes) guarantees the tree height stays O(log n), so search/insert/delete are O(log n). `TreeMap` is built entirely on one; `HashMap` uses small per-bucket ones only after collisions pile up.

> **Why treeification exists — the security angle.** Before Java 8, a malicious client could send keys deliberately crafted to all collide into one bucket (a "hash flooding" / algorithmic-complexity DoS attack), degrading `HashMap` to a single O(n) linked list and turning O(1) operations into O(n²) overall. Treeification caps the damage at O(log n) per bucket. For `String` keys, the tree compares by hash then by `Comparable` ordering (Strings are `Comparable`), so even pathological inputs stay logarithmic.

#### 3.3.5 Resize / rehash — step by step

When `size` exceeds `threshold`, `resize()` runs:
1. **Double capacity:** `newCap = oldCap << 1` (16→32→64…). New `threshold = newCap * loadFactor`.
2. Allocate the new, larger `Node[]`.
3. **Rehash every entry.** Here is the Java 8 cleverness: because capacity doubles, an element's new index is *either the same index* or *the same index + oldCap*. The deciding factor is a single bit: `(hash & oldCap)`. If that bit is 0, the entry stays at index `j`; if it's 1, it moves to `j + oldCap`. So each old bucket splits into (at most) two new buckets — a "low" list and a "high" list — built by walking the old chain once, **without recomputing the hash** and **preserving relative order**.
4. Tree bins are split the same way; if a resulting half is ≤ 6 nodes it untreeifies.

> **Java 7 vs Java 8 — a famous concurrency bug.** Java 7's resize re-inserted entries at the *head* of each new bucket, *reversing* order. Under concurrent `put` from multiple threads (using a plain `HashMap` without synchronization — itself a bug), the reversal could form a *circular linked list*, causing a subsequent `get` to spin forever at 100% CPU (an infinite loop). Java 8's order-preserving split eliminated *that specific* failure, but **a plain `HashMap` is still not thread-safe** — concurrent writes can still lose data, throw, or corrupt state. Use `ConcurrentHashMap`.

#### 3.3.6 `get(K)` step by step

1. `h = hash(key)`, `i = (n-1) & h`.
2. Check the *first* node in `table[i]` (the common case, often the only node) — match on hash + (`==` or `equals`).
3. If more nodes: if it's a tree, `getTreeNode` (O(log n)); else walk the list (O(bucket length)).
4. Average case O(1); worst case O(log n) (treeified) or O(n) (degenerate, pre-treeify or tiny table).

#### 3.3.7 Why load factor 0.75?

Load factor balances **space vs time**. Lower load factor → fewer collisions → faster, but more wasted buckets (more memory) and more frequent resizes. Higher → denser table, more collisions, slower lookups, less memory. **0.75 is the empirical sweet spot**: with a good hash function, the probability that a bucket has *k* entries follows a Poisson distribution with mean 0.5 (at 0.75 load), making chains of length ≥ 8 astronomically unlikely (≈ 6 in ten million per bucket) — which is *why* 8 was chosen as the treeify threshold (the Javadoc cites these Poisson probabilities directly).

### 3.4 `LinkedHashMap` internals (insertion order & LRU)

`LinkedHashMap extends HashMap` and adds a **doubly-linked list threaded through all entries** (each entry has `before`/`after` pointers in addition to the hash bucket `next`). This preserves a predictable iteration order with only ~2 extra references per entry.

Two modes:
- **Insertion-order (default):** iteration follows the order keys were first inserted.
- **Access-order (`accessOrder = true`):** every `get`/`put` of a key moves it to the *end* of the linked list. The least-recently-used entry sits at the *head*.

This makes `LinkedHashMap` the natural building block for an **LRU (Least Recently Used) cache**. Override `removeEldestEntry`:

```java
LinkedHashMap<K,V> lru = new LinkedHashMap<>(16, 0.75f, /*accessOrder=*/true) {
    @Override protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return size() > MAX_ENTRIES;   // evict head (LRU) once over capacity
    }
};
```

> **Beginner aside — LRU cache.** A cache with a fixed capacity that, when full, discards the entry that hasn't been accessed for the longest time. It's the standard policy for memory-bounded caches because "recently used" predicts "soon used again." `LinkedHashMap` in access-order mode gives you LRU eviction in a few lines, though it is *not* thread-safe — wrap with synchronization or use Caffeine/Guava for production caches.

### 3.5 `TreeMap` internals (red-black tree)

`TreeMap` stores entries in a **red-black tree** keyed by either natural ordering (`Comparable`) or a supplied `Comparator`. It implements `NavigableMap`/`SortedMap`.

- All operations — `get`, `put`, `remove`, `containsKey` — are **O(log n)**.
- Iteration is in **sorted key order**.
- Navigation: `firstKey`/`lastKey`, `floorKey`(≤), `ceilingKey`(≥), `lowerKey`(<), `higherKey`(>), `headMap`/`tailMap`/`subMap` (range views), `pollFirstEntry`/`pollLastEntry`.
- **No null keys** (it must compare them) unless a null-tolerant `Comparator` is supplied — and even then natural ordering throws `NullPointerException`.

> **Comparator vs `Comparable` consistency.** `TreeMap` defines uniqueness by the *comparator*, not by `equals`. If your comparator says two distinct keys are "equal" (compare returns 0), the map treats them as the same key — which can violate the `Map` contract that relies on `equals`. Keep your comparator *consistent with equals* to avoid surprises.

`TreeSet` is `TreeMap` with the values ignored (a sentinel). Same characteristics.

### 3.6 `ArrayDeque` internals (the right stack/queue)

`ArrayDeque` is a **resizable circular array** (ring buffer) with `head` and `tail` indices. It implements `Deque`.

- `addFirst`/`addLast`/`pollFirst`/`pollLast`/`peek*` — **amortized O(1)**; no per-element node allocation.
- Capacity is always a power of two; index wrap-around uses `& (length-1)`. Grows by doubling when full.
- **No null elements** (null is the "empty slot" sentinel — adding null throws `NullPointerException`).
- Not thread-safe.

**Use `ArrayDeque` instead of:**
- `Stack` (a legacy synchronized `Vector` subclass — slow and discouraged). For LIFO: `push`/`pop`/`peek` on a `Deque`.
- `LinkedList` as a queue. `ArrayDeque` is faster (contiguous, no node objects) for both FIFO queues and LIFO stacks.

```java
Deque<Integer> stack = new ArrayDeque<>();
stack.push(1); stack.push(2);           // LIFO
int top = stack.pop();                   // 2

Deque<Integer> queue = new ArrayDeque<>();
queue.offer(1); queue.offer(2);          // FIFO
int first = queue.poll();                // 1
```

### 3.7 `PriorityQueue` internals (binary heap)

`PriorityQueue` is a **binary min-heap** stored in an array. The smallest element (by natural order or `Comparator`) is always at the head.
- `offer`/`poll` — O(log n) (sift-up / sift-down).
- `peek` — O(1).
- **Not sorted on iteration** — only the head is guaranteed minimal; `iterator()` order is heap order, not sorted order.
- Not thread-safe (`PriorityBlockingQueue` is the concurrent version).

### 3.8 Fail-fast iterators & `ConcurrentModificationException`

**Mechanism.** Most `java.util` collections keep an `int modCount` incremented on every structural modification (add/remove that changes size; not a plain `set`). When you create an `Iterator`, it snapshots `expectedModCount = modCount`. On each `next()`/`remove()` it checks `modCount == expectedModCount`; if the collection was structurally modified by anything *other than the iterator's own `remove()`*, it throws **`ConcurrentModificationException` (CME)**.

> **Beginner aside — "fail-fast."** A design that detects a likely bug as early and loudly as possible rather than silently producing wrong results. CME is fail-fast: it doesn't *guarantee* detection (the check is best-effort, not for correctness), but it surfaces the common "modify-while-iterating" mistake immediately.

**The classic trap:**

```java
List<String> list = new ArrayList<>(List.of("a","b","c"));
for (String s : list) {          // enhanced-for uses an Iterator under the hood
    if (s.equals("b")) list.remove(s);   // structural modify → CME on next iteration
}
```

**Correct fixes:**

```java
// 1. Iterator.remove() — the only modification the iterator tolerates
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    if (it.next().equals("b")) it.remove();
}

// 2. removeIf — concise, internally uses the iterator correctly (Java 8+)
list.removeIf(s -> s.equals("b"));

// 3. Iterate a copy if you must add while iterating
for (String s : new ArrayList<>(list)) { ... list.add(...); ... }

// 4. Use a concurrent collection whose iterators are weakly consistent
//    (CopyOnWriteArrayList, ConcurrentHashMap) — no CME, but snapshot/weak semantics
```

> **Important:** CME can be thrown even in *single-threaded* code (as above). The name is misleading — it's about *concurrent modification with respect to an iterator*, not necessarily *multiple threads*.

**Weakly-consistent iterators** (`ConcurrentHashMap`, `CopyOnWriteArrayList`, most `java.util.concurrent` collections) never throw CME. They traverse a snapshot or tolerate concurrent changes, possibly reflecting some-but-not-all modifications made during iteration. This is the right tool for concurrent traversal.

### 3.9 `ConcurrentHashMap` internals (brief, since it's adjacent)

Modern (`Java 8+`) `ConcurrentHashMap` abandoned Java 7's segment-locking for a finer-grained scheme:
- Reads are **lock-free** (volatile reads of the table).
- Writes lock only the **single bucket** (synchronizing on the bin's head node) — so unrelated buckets stay concurrent.
- It treeifies long bins like `HashMap`.
- Resizing is **cooperative**: multiple threads can help transfer buckets concurrently.
- `size()` is approximate under contention (uses a `LongAdder`-style striped counter). No null keys or values allowed (so `get` returning null unambiguously means "absent").

---

## 4. The complete toolkit

### 4.1 Core interfaces and their key methods

| Interface | Adds over parent | Signature highlights |
|---|---|---|
| `Iterable<T>` | — | `iterator()`, `forEach(Consumer)`, `spliterator()` |
| `Collection<E>` | `Iterable` | `add/remove/contains/size/isEmpty/clear/toArray/stream/removeIf` |
| `List<E>` | `Collection` | `get/set(int)`, `add(int,E)`, `indexOf`, `listIterator`, `subList`, `sort` |
| `Set<E>` | `Collection` | (no new methods; refines `add` semantics — no dups) |
| `SortedSet<E>` | `Set` | `first/last`, `headSet/tailSet/subSet`, `comparator` |
| `NavigableSet<E>` | `SortedSet` | `floor/ceiling/lower/higher`, `pollFirst/pollLast`, `descendingSet` |
| `Queue<E>` | `Collection` | `offer/poll/peek` (lenient), `add/remove/element` (throwing) |
| `Deque<E>` | `Queue` | `addFirst/Last`, `offerFirst/Last`, `pollFirst/Last`, `peekFirst/Last`, `push/pop` |
| `Map<K,V>` | — | `put/get/remove/containsKey/containsValue`, `keySet/values/entrySet`, plus defaults below |
| `SortedMap<K,V>` | `Map` | `firstKey/lastKey`, `headMap/tailMap/subMap`, `comparator` |
| `NavigableMap<K,V>` | `SortedMap` | `floorEntry/ceilingEntry/lowerEntry/higherEntry`, `pollFirst/LastEntry`, `descendingMap` |

**`Map` default methods (Java 8+) — learn these, they remove boilerplate:**

| Method | Purpose |
|---|---|
| `getOrDefault(k, def)` | value or `def` if absent |
| `putIfAbsent(k, v)` | put only if key absent; returns existing or null |
| `computeIfAbsent(k, fn)` | compute & store if absent (perfect for multimaps) |
| `computeIfPresent(k, fn)` | recompute only if present |
| `compute(k, fn)` | recompute always (fn sees current value, may be null) |
| `merge(k, v, fn)` | combine new value with existing via fn (perfect for counters) |
| `forEach(biConsumer)` | iterate entries |
| `replaceAll(biFn)` | transform every value in place |

### 4.2 Implementations at a glance

| Implementation | Interface(s) | Backing structure | Null keys/elems | Ordering | Thread-safe |
|---|---|---|---|---|---|
| `ArrayList` | `List`, `RandomAccess` | resizable array | nulls ok | insertion | no |
| `LinkedList` | `List`, `Deque` | doubly-linked nodes | nulls ok | insertion | no |
| `Vector` | `List` | resizable array | nulls ok | insertion | yes (legacy) |
| `CopyOnWriteArrayList` | `List` | array, copied on write | nulls ok | insertion | yes |
| `HashSet` | `Set` | `HashMap` | one null | none | no |
| `LinkedHashSet` | `Set` | `LinkedHashMap` | one null | insertion | no |
| `TreeSet` | `NavigableSet` | red-black tree | no null* | sorted | no |
| `HashMap` | `Map` | hash table + trees | one null key | none | no |
| `LinkedHashMap` | `Map` | hash table + linked list | one null key | insertion/access | no |
| `TreeMap` | `NavigableMap` | red-black tree | no null key* | sorted | no |
| `Hashtable` | `Map` | hash table | no nulls | none | yes (legacy) |
| `ConcurrentHashMap` | `ConcurrentMap` | hash table + trees | no nulls | none | yes |
| `ConcurrentSkipListMap` | `ConcurrentNavigableMap` | skip list | no null key | sorted | yes |
| `ArrayDeque` | `Deque` | circular array | no null | insertion (as queue/stack) | no |
| `PriorityQueue` | `Queue` | binary heap | no null | priority (head only) | no |
| `EnumMap` | `Map` | array indexed by enum ordinal | no null key | enum declaration | no |
| `EnumSet` | `Set` | bit vector | no null | enum declaration | no |
| `IdentityHashMap` | `Map` | hash table, `==` keys | null key ok | none | no |
| `WeakHashMap` | `Map` | hash table, weak keys | null key ok | none | no |

\* `TreeMap`/`TreeSet` reject null only with natural ordering; a null-tolerant comparator can allow them, but natural ordering throws NPE.

### 4.3 Capacity & tuning constants (HashMap, Java 8+)

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_INITIAL_CAPACITY` | 16 | initial bucket count |
| `DEFAULT_LOAD_FACTOR` | 0.75 | resize trigger ratio |
| `MAXIMUM_CAPACITY` | 1 << 30 | cap on bucket array length |
| `TREEIFY_THRESHOLD` | 8 | bin length to convert list→tree |
| `UNTREEIFY_THRESHOLD` | 6 | bin length to convert tree→list |
| `MIN_TREEIFY_CAPACITY` | 64 | min table size before treeifying (else resize) |

### 4.4 `Collections` and `Arrays` utility classes

| Method | Purpose |
|---|---|
| `Collections.sort(list[, cmp])` | in-place merge sort (stable), O(n log n) |
| `Collections.binarySearch(list, key)` | O(log n) on a sorted `RandomAccess` list |
| `Collections.unmodifiableList/Set/Map(c)` | read-only *view* (throws on mutation) |
| `Collections.synchronizedList/Map(c)` | coarse-grained synchronized wrapper |
| `Collections.emptyList/Map/Set()` | shared immutable empties (zero allocation) |
| `Collections.singletonList(x)` | immutable one-element collection |
| `Collections.reverse/shuffle/swap/rotate` | in-place reorderings |
| `Collections.frequency(c, o)` | count occurrences |
| `Collections.disjoint(a, b)` | true if no common elements |
| `Collections.max/min(c[, cmp])` | extremes |
| `Arrays.asList(...)` | fixed-size list *view* over an array (set ok, add/remove throw) |
| `Arrays.sort` | dual-pivot quicksort (primitives) / TimSort (objects) |

**Java 9+ factory methods** — compact immutable collections:

```java
List<Integer> l = List.of(1, 2, 3);          // immutable, rejects nulls
Set<String>  s = Set.of("a", "b");           // immutable, rejects dup & null
Map<String,Integer> m = Map.of("a", 1, "b", 2);
Map<String,Integer> big = Map.ofEntries(Map.entry("a",1), Map.entry("b",2));
```

These throw `UnsupportedOperationException` on mutation and `NullPointerException` on null elements — strictly immutable, unlike `Arrays.asList` (mutable-by-set) and `Collections.unmodifiableX` (a *view* over a still-mutable backing collection).

---

## 5. Code examples by use case

### 5.1 Frequency counting with `merge`

```java
Map<String, Integer> counts = new HashMap<>();
for (String word : words) {
    counts.merge(word, 1, Integer::sum);   // absent → 1; present → old + 1
}
// Replaces the verbose getOrDefault/put pattern in one atomic step.
```

### 5.2 Multimap (group values under a key) with `computeIfAbsent`

```java
Map<String, List<Order>> byCustomer = new HashMap<>();
for (Order o : orders) {
    byCustomer.computeIfAbsent(o.customerId(), k -> new ArrayList<>()).add(o);
}
// computeIfAbsent creates the inner list exactly once per key, then returns it.
```

### 5.3 LRU cache via `LinkedHashMap`

```java
public final class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;
    public LruCache(int capacity) {
        super(16, 0.75f, true);          // accessOrder = true
        this.capacity = capacity;
    }
    @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;        // evict LRU when over capacity
    }
}
// Not thread-safe; wrap in Collections.synchronizedMap or use Caffeine in prod.
```

### 5.4 Range queries with `TreeMap` (e.g. time-series / leaderboard)

```java
NavigableMap<Long, Event> timeline = new TreeMap<>();
timeline.put(epochMillis, event);
// All events in a half-open window [from, to):
SortedMap<Long, Event> window = timeline.subMap(from, true, to, false);
// Most recent event at or before t:
Map.Entry<Long, Event> latest = timeline.floorEntry(t);
```

### 5.5 BFS queue and DFS stack with `ArrayDeque`

```java
// Breadth-first search — FIFO queue
Deque<Node> q = new ArrayDeque<>();
q.offer(start);
Set<Node> seen = new HashSet<>(Set.of(start));
while (!q.isEmpty()) {
    Node n = q.poll();
    for (Node nbr : n.neighbors())
        if (seen.add(nbr)) q.offer(nbr);   // add returns false if already present
}

// Depth-first search — LIFO stack (same class, opposite ends)
Deque<Node> stack = new ArrayDeque<>();
stack.push(start);
while (!stack.isEmpty()) {
    Node n = stack.pop();
    // ... visit, push children
}
```

### 5.6 Top-K with a bounded `PriorityQueue`

```java
// Keep the K largest elements using a MIN-heap of size K.
PriorityQueue<Integer> topK = new PriorityQueue<>(); // min-heap
for (int x : data) {
    topK.offer(x);
    if (topK.size() > K) topK.poll();   // evict the smallest
}
// topK now holds the K largest (unordered); drain for sorted output.
```

### 5.7 EnumMap / EnumSet for enum-keyed state

```java
enum Day { MON, TUE, WED, THU, FRI, SAT, SUN }
EnumMap<Day, String> schedule = new EnumMap<>(Day.class); // array-backed, ultra fast
schedule.put(Day.MON, "standup");
EnumSet<Day> weekend = EnumSet.of(Day.SAT, Day.SUN);      // bit-vector, tiny & fast
boolean isWeekend = weekend.contains(today);
```

`EnumMap`/`EnumSet` are dramatically faster and smaller than `HashMap`/`HashSet` for enum keys — they index a flat array / set bits by the enum's `ordinal()`.

### 5.8 Safe concurrent counter with `ConcurrentHashMap`

```java
ConcurrentHashMap<String, LongAdder> hits = new ConcurrentHashMap<>();
hits.computeIfAbsent(path, k -> new LongAdder()).increment();
// computeIfAbsent is atomic per-key; LongAdder beats AtomicLong under contention.
```

### 5.9 Immutable defensive copy at an API boundary

```java
public List<String> getTags() {
    return List.copyOf(this.tags);   // immutable snapshot; caller can't mutate internals
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Pre-size when the count is known.** `new ArrayList<>(n)` and `new HashMap<>(expectedSize / 0.75 + 1)` avoid repeated O(n) resizes. For `HashMap`, sizing to hold `n` without resizing means capacity ≥ `n / 0.75`; the constructor rounds up to the next power of two.
- **Prefer `ArrayList`/`ArrayDeque` for cache locality.** Avoid `LinkedList` unless you have a measured need.
- **Avoid autoboxing in hot loops.** `Map<Integer,Integer>` boxes every key/value. For primitive-heavy work, use specialized libraries (Eclipse Collections, fastutil, HPPC) or arrays.
- **Iterate `entrySet()`, not `keySet()` + `get()`.** The latter doubles the hash lookups.
- **Stream vs loop:** streams are expressive but allocate; for tight inner loops a plain loop is often faster. Measure with JMH before optimizing.

> **Beginner aside — autoboxing.** Java auto-converts primitives (`int`) to their wrapper objects (`Integer`) when a collection demands objects. Each boxing allocates an object (except small cached `Integer`s -128..127). In a hot loop this generates garbage and indirection.

> **Beginner aside — JMH.** Java Microbenchmark Harness, the standard tool for measuring small Java code performance correctly (it handles JIT warm-up, dead-code elimination, etc.). Naive `System.nanoTime()` timing of micro-ops is almost always wrong.

### 6.2 Correctness & concurrency

- **Plain `java.util` collections are not thread-safe.** Concurrent writes corrupt them. Choose `ConcurrentHashMap`, `CopyOnWriteArrayList` (read-mostly), or `Collections.synchronizedX` (coarse lock; you must still manually synchronize during iteration).
- **`Collections.synchronizedMap` requires external sync for compound ops and iteration:**

```java
Map<K,V> m = Collections.synchronizedMap(new HashMap<>());
synchronized (m) {                 // MUST hold the map's lock while iterating
    for (var e : m.entrySet()) { ... }
}
```
- **Uphold `equals`/`hashCode`** and keep keys immutable (Section 2).
- **Comparator consistency with equals** for `TreeMap`/`TreeSet` (Section 3.5).

### 6.3 Memory

- `LinkedList` and `HashMap` carry per-element object overhead (nodes/entries with headers + reference fields). On a 64-bit JVM with compressed oops, a `HashMap.Node` is ~32 bytes plus the key/value objects.
- `ArrayList` may hold up to ~50% slack after a resize; call `trimToSize()` for long-lived, stable lists.
- Prefer `EnumMap`/`EnumSet` and primitive-collection libraries to slash overhead in hot data.
- `WeakHashMap` keys are held by *weak references*, so entries vanish when keys become GC-unreachable — useful for canonicalizing caches/metadata keyed by identity, but entries can disappear unpredictably.

### 6.4 Security

- **Hash-flooding DoS:** untrusted keys (e.g. HTTP headers, JSON fields) into a `HashMap` could historically force O(n²) collisions. Treeification (Java 8) mitigates it; still, validate/limit untrusted key counts and prefer `String` keys (which treeify cleanly because `String` is `Comparable`).
- **Unbounded growth:** a `HashMap` cache without eviction is a memory-leak / OOM vector. Bound caches (LRU, Caffeine with `maximumSize`).
- **Mutable shared collections** leaked from APIs let callers corrupt your invariants — return immutable copies/views.

### 6.5 Observability & testing

- Log collection *sizes* at boundaries; an exploding size signals a leak or missing eviction.
- Heap-dump analysis (Eclipse MAT) reveals which collection retains the most memory — look for giant `HashMap$Node[]` / `Object[]` arrays.
- Test the `equals`/`hashCode` contract (e.g. with EqualsVerifier).
- Property-test ordering and dedup invariants. For concurrent collections, stress-test with multiple threads + tools like `jcstress`.

### 6.6 Anti-patterns to avoid

| Anti-pattern | Why it's bad | Do instead |
|---|---|---|
| `LinkedList` as a list | O(n) `get`, cache-hostile, heavy | `ArrayList` |
| `LinkedList` as a queue | slower than ring buffer | `ArrayDeque` |
| `Stack` / `Vector` / `Hashtable` | legacy, fully synchronized, slow | `ArrayDeque` / `ArrayList` / `HashMap` |
| Override `equals` not `hashCode` | broken hash lookups | override both / use `record` |
| Mutating a key in a `HashSet`/`HashMap` | "ghost" entries | keep keys immutable |
| `keySet()` + `get()` loop | double hashing | `entrySet()` |
| Modify while iterating | `ConcurrentModificationException` | `Iterator.remove` / `removeIf` |
| Plain `HashMap` across threads | corruption / infinite loop (pre-8) | `ConcurrentHashMap` |
| `new HashMap<>()` then many adds | repeated resizes | pre-size |
| Unbounded cache map | OOM | bounded LRU / Caffeine |
| `Map<Integer,Integer>` in hot loop | autoboxing garbage | primitive collections |

---

## 7. Advanced topics & deep internals

### 7.1 The `RandomAccess` marker interface

`ArrayList` implements `RandomAccess` (a marker — no methods); `LinkedList` does not. Generic algorithms (e.g. `Collections.binarySearch`, `shuffle`) check `instanceof RandomAccess` to choose an index-based loop (fast on arrays) vs an iterator-based loop (fast on linked lists). Honor this in your own generic code.

### 7.2 `Spliterator` and parallel streams

`Spliterator` ("splittable iterator") underpins `Stream`. It can `trySplit()` a range in half for parallel processing and reports *characteristics* (`SIZED`, `ORDERED`, `DISTINCT`, `SORTED`, `IMMUTABLE`, `CONCURRENT`). `ArrayList`'s spliterator splits cleanly by index (great parallelism); `LinkedList`'s splits poorly. `HashMap`'s spliterator splits by bucket ranges. These characteristics let the stream framework skip redundant work (e.g. a `SORTED` source skips a `.sorted()` stage).

### 7.3 `subList` is a *view*, not a copy

`list.subList(from, to)` returns a window backed by the original list. Structural changes to the parent invalidate the sublist (CME on next use). `subList` is great for range operations (`list.subList(0, k).clear()` deletes a prefix) but dangerous if you forget it's a live view.

### 7.4 `Arrays.asList` gotchas

- Fixed size: `set` works, `add`/`remove` throw `UnsupportedOperationException`.
- It's a *view* over the array — changing the array changes the list and vice-versa.
- `Arrays.asList(primitiveArray)` (e.g. `int[]`) yields a `List<int[]>` of size 1, not a list of ints — a classic trap. Use `IntStream.of(arr).boxed().collect(...)` or `Arrays.stream`.

### 7.5 `HashMap` iteration order is unspecified — and changed across versions

Never rely on `HashMap` iteration order; it depends on capacity, hash spread, and insertion history, and has changed between JDK versions. If you need deterministic order, use `LinkedHashMap` (insertion) or `TreeMap` (sorted).

### 7.6 Treeified bin tie-breaking with non-`Comparable` keys

In a treeified bin, nodes are ordered by hash; on hash ties, if keys are `Comparable` they're compared, else `HashMap` falls back to comparing class names and finally `System.identityHashCode` as a deterministic tiebreaker (`tieBreakOrder`). This keeps the tree well-formed even for non-comparable keys, but such keys gain less benefit from treeification.

### 7.7 `IdentityHashMap` uses `==` and a different hashing scheme

`IdentityHashMap` compares keys with `==` (reference identity) and uses `System.identityHashCode`. It uses **linear probing** in a single `Object[]` (keys and values interleaved), not chaining. Used for graph traversal (visited-set by identity), serialization frameworks, and topology where logical equality is wrong.

### 7.8 `WeakHashMap`, `ReferenceQueue`, and stale-entry cleanup

`WeakHashMap` wraps keys in `WeakReference`s registered with a `ReferenceQueue`. When the GC reclaims a key, the entry's reference is enqueued; on the next map operation, `expungeStaleEntries` drains the queue and removes dead entries. Values are *not* weak — beware a value strongly referencing its key (defeats collection).

### 7.9 `ConcurrentHashMap` advanced operations

- `compute`, `merge`, `computeIfAbsent` are **atomic per key** (the bin is locked) — ideal for read-modify-write without external locks. But the mapping function must be **short and non-blocking** (it runs under the bin lock; doing I/O or acquiring other locks there can deadlock or stall other writers to that bin).
- Bulk parallel ops: `forEach`, `search`, `reduce` with a `parallelismThreshold`.
- `size()` returns `int` (saturating); use `mappingCount()` (returns `long`) for very large maps.

### 7.10 Fibonacci-style sizing: `tableSizeFor`

`HashMap` rounds requested capacity up to the next power of two via bit-smearing (`tableSizeFor`): `n |= n>>>1; n|=n>>>2; ... ; return n+1`. So `new HashMap<>(100)` actually allocates 128 buckets. Account for this when sizing.

### 7.11 Immutable collection sharing and `List.copyOf` short-circuit

`List.copyOf(c)` returns `c` itself if it's already an immutable `List.of`-style instance (no copy). The Java 9 immutable collections are also more memory-compact (specialized 0/1/2-element and array-based forms) than `Collections.unmodifiableList` wrappers.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Big-O cheat table

| Operation | `ArrayList` | `LinkedList` | `ArrayDeque` | `HashMap`/`HashSet` | `LinkedHashMap` | `TreeMap`/`TreeSet` | `PriorityQueue` |
|---|---|---|---|---|---|---|---|
| `get`/`contains` by key/index | O(1) | O(n) | — | **O(1)** avg, O(log n) worst | O(1) avg | O(log n) | peek O(1) |
| `add`/`put` (end/typical) | amortized O(1) | O(1) | amortized O(1) | O(1) avg | O(1) avg | O(log n) | O(log n) |
| `add`/`remove` at front | O(n) | O(1) | O(1) | — | — | — | — |
| `add`/`remove` at index | O(n) | O(n)* | — | — | — | — | — |
| `remove` by value | O(n) | O(n) | O(n) | O(1) avg | O(1) avg | O(log n) | O(n) |
| iterate all | O(n) | O(n) | O(n) | O(n + capacity) | O(n) | O(n) | O(n) |
| ordered iteration | insertion | insertion | queue order | **none** | insertion/access | **sorted** | head-only |
| memory/element | low | high | low | medium | medium-high | medium | low |

\* O(1) splice once positioned via `ListIterator`; O(n) to reach the position.

### 8.2 Choosing a `List`

- **Default → `ArrayList`.** Random access, iteration, append-heavy workloads.
- **Front/back insert-remove heavy, no index access → `ArrayDeque`** (as a list-like buffer) — not `LinkedList`.
- **Read-mostly, rarely written, concurrent → `CopyOnWriteArrayList`** (each write copies the whole array; reads are lock-free).
- **Need `List` + `Deque` together with O(1) iterator splicing → `LinkedList`** (rare).

### 8.3 Choosing a `Set`

- **Default → `HashSet`** (fastest membership).
- **Need insertion order → `LinkedHashSet`.**
- **Need sorted order / range queries → `TreeSet`.**
- **Enum elements → `EnumSet`** (bit vector).
- **Concurrent → `ConcurrentHashMap.newKeySet()`** or `CopyOnWriteArraySet` (small, read-mostly).

### 8.4 Choosing a `Map`

| Need | Choose |
|---|---|
| Fastest key→value, order irrelevant | `HashMap` |
| Predictable insertion-order iteration | `LinkedHashMap` |
| LRU cache | `LinkedHashMap(accessOrder)` / Caffeine |
| Sorted keys / range / floor-ceiling | `TreeMap` |
| Enum keys | `EnumMap` |
| Concurrent high-throughput | `ConcurrentHashMap` |
| Concurrent + sorted | `ConcurrentSkipListMap` |
| Identity (`==`) keys | `IdentityHashMap` |
| GC-evictable keys | `WeakHashMap` |

### 8.5 `HashMap` vs `TreeMap` vs `LinkedHashMap`

| Aspect | `HashMap` | `LinkedHashMap` | `TreeMap` |
|---|---|---|---|
| Lookup | O(1) avg | O(1) avg | O(log n) |
| Ordering | none | insertion/access | sorted |
| Extra memory | least | +2 refs/entry | tree pointers + color |
| Null key | one | one | none (natural order) |
| Use when | pure lookup | order matters / LRU | range/sorted queries |

### 8.6 `ArrayDeque` vs `LinkedList` vs `Stack`

Prefer `ArrayDeque` for both stack and queue: contiguous memory, no node allocation, faster. `LinkedList` only when you need the `List` interface too. `Stack`/`Vector` are legacy — avoid.

---

## 9. Failure modes & debugging

### 9.1 `ConcurrentModificationException`

- **Symptom:** CME thrown from `next()` during iteration.
- **Cause:** structural modification of the collection (by another thread, or your own code) during a fail-fast iteration.
- **Diagnose:** read the stack trace — it points to the `next()` call site and the modifying line. In single-threaded code it's a `remove`/`add` inside a for-each.
- **Fix:** `Iterator.remove`, `removeIf`, iterate a copy, or use a weakly-consistent concurrent collection.

### 9.2 Silent data loss / corruption from unsynchronized `HashMap`

- **Symptom (pre-Java 8):** a thread stuck at 100% CPU in `HashMap.get` (infinite loop from a corrupted resize cycle). **Symptom (any version):** lost updates, wrong `size`, spurious `null`s.
- **Cause:** concurrent writes to a plain `HashMap`.
- **Diagnose:** thread dump (`jstack <pid>`) shows a thread spinning in `HashMap.getNode`/`getEntry`. Heap dump shows the cyclic chain.
- **Fix:** `ConcurrentHashMap`.

> **Beginner aside — `jstack` / thread dump.** `jstack <pid>` prints every thread's stack. A "hot" loop shows the same frames repeatedly across dumps. `jmap`/heap dumps (analyzed in Eclipse MAT) show what's in memory.

### 9.3 `get` returns null for a key you just put

- **Cause:** broken `hashCode`/`equals` (overrode one not the other, or used mutable fields and then mutated the key).
- **Diagnose:** check whether `key.equals(insertedKey)` and `key.hashCode() == insertedKey.hashCode()` both hold; verify no field used in the hash was mutated after insertion.
- **Fix:** correct/regenerate `equals`+`hashCode` (use a `record`); keep keys immutable.

### 9.4 OutOfMemoryError from an unbounded map cache

- **Symptom:** gradually rising heap, eventual `OutOfMemoryError: Java heap space`.
- **Diagnose:** heap dump → MAT "dominator tree" shows a huge `HashMap$Node[]`. GC logs show old-gen filling.
- **Fix:** bound the cache (LRU `removeEldestEntry`, Caffeine `maximumSize`/`expireAfter`), or weak/soft references where appropriate.

### 9.5 Quadratic behavior from `LinkedList.get(i)` in a loop

- **Symptom:** an operation that should be linear is O(n²); CPU spikes as `n` grows.
- **Diagnose:** profiler (async-profiler / JFR) shows time in `LinkedList.node(int)`.
- **Fix:** switch to `ArrayList`, or iterate with an `Iterator` instead of indexed `get`.

### 9.6 `TreeMap` throws `ClassCastException` / `NullPointerException`

- **Cause:** keys not mutually `Comparable` (no comparator) or a null key with natural ordering.
- **Fix:** supply a `Comparator`; never use null keys in natural-order `TreeMap`.

### 9.7 Real-world incident pattern

A common production story: a service uses a plain `HashMap` as an in-process cache, shared across request threads without synchronization. Under load it intermittently pegs a CPU core at 100% (pre-Java 8 resize cycle) or returns stale/null entries. The fix that "makes it go away" is usually swapping to `ConcurrentHashMap` plus a bounded eviction policy — and a postmortem note that "thread-safe by accident" is never safe.

---

## 10. Interview drill

**Q1. Walk me through what happens on `HashMap.put` when there's a collision.**
*Model answer:* Compute the spread hash (`h ^ (h>>>16)`), index `= (n-1) & h`. If the bucket is non-empty, check the first node (hash + `==`/`equals`); if it matches it's an update. Otherwise, if it's a tree node, insert into the red-black tree; else walk the linked list, updating on match or appending at the end. If the bin reaches 8 nodes and the table has ≥64 buckets, treeify; otherwise resize. Then `++size`; if over threshold, resize (double + split each bin into low/high by the `hash & oldCap` bit).
- *Follow-up: Why power-of-two capacity?* So `(n-1) & hash` replaces modulo with a single AND, and resize splits each bucket into exactly two by one bit.
- *Follow-up: Why XOR the high bits in the spread function?* Because only the low bits index the bucket; mixing high entropy down reduces collisions for keys differing mainly in high bits.
- *Follow-up: What if two unequal keys have the same hash?* They collide into the same bucket; `equals` disambiguates. Many such collisions degrade to O(log n) (treeified) or O(n).

**Q2. Why is treeification at 8 and untreeify at 6, not both at 7?**
*Model answer:* Hysteresis. A single threshold would thrash list↔tree around the boundary. The gap (8 up, 6 down) prevents oscillation. 8 is chosen because, at load factor 0.75 with a good hash, the Poisson probability of a bin reaching 8 is ~6e-8 — so treeification is a safety net, not the normal path.
- *Follow-up: Why also require 64 buckets?* In a small dense table, long bins mean "too few buckets," so resizing (doubling) fixes it more cheaply than building trees.
- *Follow-up: What ordering does a tree bin use?* Hash, then `Comparable` if available, else class name + identity hash as a tiebreaker.

**Q3. Explain the difference between `==`, `equals`, and `hashCode`, and the contract linking them.**
*Model answer:* `==` is reference identity; `equals` is logical equality; `hashCode` is a bucket fingerprint. Contract: equal objects must have equal hash codes (so hash collections find them); unequal objects may share a hash. Override both together; consistent-with-equals; keep hash-relevant fields immutable.
- *Follow-up: What breaks if you override only `equals`?* Two equal keys may land in different buckets, so `get` returns null. Sets accept "duplicates."
- *Follow-up: Is the reverse (equal hash but `!equals`) allowed?* Yes — that's a legal collision, resolved by `equals`.

**Q4. When would you actually use `LinkedList`, and why is it usually wrong?**
*Model answer:* Almost never as a `List`. `get(int)` is O(n), it's cache-hostile, and per-node overhead is high. The mid-insert "advantage" requires O(n) traversal first. Legit cases: you need both `List` and `Deque` and O(1) splice via a positioned `ListIterator`. For queues/stacks use `ArrayDeque`.
- *Follow-up: Why is `ArrayList` iteration faster despite the same O(n)?* Contiguous memory → CPU cache prefetch; `LinkedList` pointer-chases random heap locations → cache misses.

**Q5. How does Java 8 `HashMap` resize avoid recomputing hashes, and what bug did it fix?**
*Model answer:* On doubling, each entry's new index is `j` or `j+oldCap`, decided by `hash & oldCap`. Each old bin splits into a low and high list, preserving order. This fixed Java 7's head-insertion order reversal that, under unsafe concurrent use, could form a cyclic list causing infinite-loop `get`. But plain `HashMap` is still not thread-safe.

**Q6. Implement an LRU cache. What collection and why?**
*Model answer:* `LinkedHashMap` with `accessOrder=true`, overriding `removeEldestEntry` to evict when `size()` exceeds capacity. The linked list threaded through entries gives O(1) reorder-on-access; access-order keeps LRU at the head. For production/concurrency use Caffeine.
- *Follow-up: Is it thread-safe?* No — wrap in `synchronizedMap` or use a concurrent cache library.
- *Follow-up: LRU vs LFU?* LRU evicts least-recently-used (recency); LFU evicts least-frequently-used (frequency). LRU is simpler and usually good enough; Caffeine's W-TinyLFU blends both.

**Q7 (senior-signal). You're caching by a composite key under high concurrency with occasional bursts that recompute the same key. Design the map and the read-modify-write.**
*Model answer:* `ConcurrentHashMap` keyed by an immutable `record` composite. Use `computeIfAbsent` so the value is computed atomically once per key — but keep the mapping function fast and non-blocking (it runs under the bin lock; long/blocking work there stalls other writers and risks deadlock). For expensive computations, store a `CompletableFuture`/memoizing supplier so concurrent callers share one in-flight computation, or use Caffeine's `LoadingCache` (which handles this and adds eviction). Bound the cache to avoid OOM.
- *Follow-up: Why not `synchronized` around a `HashMap`?* Coarse lock serializes all keys, killing throughput; `ConcurrentHashMap` locks per-bin.
- *Follow-up: Why a `record` key?* Auto-correct `equals`/`hashCode`, immutable — exactly what a hash key must be.

**Q8 (senior-signal). Justify load factor 0.75 and when you'd change it.**
*Model answer:* It balances time (collisions) vs space (wasted buckets) and yields favorable Poisson collision odds. Lower it (e.g. 0.5) for read-heavy maps where you'll trade memory for fewer collisions; raise it (toward 1.0) only for memory-constrained, lookup-rare maps. In practice the bigger win is *pre-sizing* capacity to avoid resizes; load factor is rarely worth tuning.
- *Follow-up: What's the cost of a too-low load factor?* More memory and more frequent resizes for the same element count.

**Q9 (senior-signal). A teammate proposes `Vector`/`Hashtable`/`Collections.synchronizedMap` for a hot concurrent map. Critique.**
*Model answer:* All use a single coarse lock serializing every operation — terrible under contention. `synchronizedMap` also needs *manual* synchronization during iteration (or you risk CME). `ConcurrentHashMap` is the right answer: lock-free reads, per-bin write locks, cooperative resize, atomic per-key compute. Reserve `CopyOnWriteArrayList`/`Set` for read-mostly small collections.

**Q10. What is a fail-fast iterator, and is it a correctness guarantee?**
*Model answer:* It checks `modCount` and throws `ConcurrentModificationException` if the collection is structurally modified during iteration outside the iterator's own `remove`. It is *best-effort* bug detection, **not** a correctness guarantee — never write logic that depends on CME being thrown. Concurrent collections use weakly-consistent iterators instead.
- *Follow-up: Can CME happen single-threaded?* Yes — modifying inside a for-each loop.
- *Follow-up: How do `ConcurrentHashMap` iterators behave?* Weakly consistent: no CME, may or may not reflect concurrent updates.

**Q11. Compare `HashMap`, `TreeMap`, `LinkedHashMap` and give a use case for each.**
*Model answer:* `HashMap` O(1) unordered (pure lookup). `TreeMap` O(log n) sorted (range queries, floor/ceiling, leaderboards). `LinkedHashMap` O(1) with insertion or access order (deterministic iteration, LRU). Choose by whether you need ordering and which kind.

**Q12. Why does `ArrayList.add` claim amortized O(1) when it sometimes copies the whole array?**
*Model answer:* Growth is geometric (1.5×), so the expensive O(n) copies happen exponentially less often. Summed over `n` adds, total copy work is O(n), giving O(1) per add on average. Pre-sizing eliminates the copies entirely.

---

## 11. Glossary

- **Amortized cost** — average cost per operation over a sequence, smoothing rare expensive ops (e.g. `ArrayList` resize).
- **Autoboxing** — automatic conversion between primitives (`int`) and wrappers (`Integer`); allocates objects and adds indirection.
- **Big-O** — asymptotic upper bound on how an operation's cost grows with input size `n`.
- **Bucket / bin** — a slot in a hash table's array; holds a chain (linked list) or tree of colliding entries.
- **Cache locality** — performance benefit of accessing contiguous memory the CPU has prefetched; arrays have it, linked nodes don't.
- **Collision** — two keys mapping to the same bucket; resolved by chaining or trees.
- **Comparable / Comparator** — `Comparable` defines a type's natural order (`compareTo`); `Comparator` is an external ordering strategy (`compare`).
- **`ConcurrentModificationException` (CME)** — thrown by fail-fast iterators when the collection is structurally modified during iteration.
- **Deque** — double-ended queue; insert/remove at both ends; can be FIFO queue or LIFO stack.
- **`equals` contract** — reflexive, symmetric, transitive, consistent, null-safe logical equality.
- **Fail-fast** — design that surfaces likely bugs immediately (best-effort), e.g. CME.
- **FIFO / LIFO** — first-in-first-out (queue) / last-in-first-out (stack).
- **Hash flooding** — DoS attack forcing many hash collisions to degrade `HashMap` to O(n²).
- **`hashCode` contract** — consistent; equal objects → equal hash codes; unequal may collide.
- **Hysteresis** — using two different thresholds (8 up / 6 down) to prevent oscillation.
- **Identity (`==`)** — reference equality: same object in memory.
- **JIT** — Just-In-Time compiler that compiles hot bytecode to native code at runtime.
- **JMH** — Java Microbenchmark Harness, the correct tool for micro-benchmarks.
- **Load factor** — ratio (size/capacity) at which a hash table resizes; default 0.75.
- **LRU / LFU** — least-recently-used / least-frequently-used cache eviction policies.
- **Marker interface** — an interface with no methods used as a type flag (e.g. `RandomAccess`).
- **`modCount`** — internal counter of structural modifications backing fail-fast iterators.
- **Multimap** — a map from key to a collection of values (built with `computeIfAbsent`).
- **Poisson distribution** — probability model used to justify the treeify threshold of 8.
- **Power of two** — capacity choice enabling `(n-1) & hash` as fast modulo.
- **Red-black tree** — self-balancing BST guaranteeing O(log n) height via color invariants.
- **Rehash / resize** — growing the bucket array (doubling) and redistributing entries.
- **Ring buffer (circular array)** — array with wrap-around head/tail indices, backing `ArrayDeque`.
- **`Spliterator`** — splittable iterator powering parallel streams; reports characteristics.
- **Structural modification** — a change altering a collection's size/structure (add/remove), not a plain `set`.
- **Treeification** — converting a long hash bucket from a linked list to a red-black tree (at length 8, table ≥64).
- **View** — a live wrapper over another collection (`subList`, `keySet`, `Arrays.asList`); reflects/affects the backing data.
- **Weakly-consistent iterator** — concurrent-collection iterator that never throws CME and may reflect some concurrent changes.
- **Weak reference** — a reference that doesn't prevent GC; `WeakHashMap` keys use them.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Defaults:** `ArrayList` (lists), `HashMap` (maps), `HashSet` (sets), `ArrayDeque` (stack/queue).

**HashMap numbers:** initial capacity 16, load factor 0.75, threshold 12, treeify at bin length **8** *and* table ≥ **64** (else resize), untreeify at **6**, capacity always power of two, doubles on resize, index `= (n-1) & hash`, spread `= h ^ (h>>>16)`.

**ArrayList:** array-backed, get O(1), add amortized O(1), grow **1.5×**, mid-insert/remove O(n). Pre-size with `new ArrayList<>(n)`.

**LinkedList:** avoid. get O(n), cache-hostile. Use `ArrayDeque` for queue/stack.

**Big-O:** `ArrayList.get` O(1); `LinkedList.get` O(n); `HashMap.get` O(1) avg / O(log n) worst; `TreeMap.get` O(log n); `PriorityQueue.offer/poll` O(log n).

**Ordering:** `HashMap` none; `LinkedHashMap` insertion/access; `TreeMap` sorted.

**Contracts:** override `equals` + `hashCode` together (use `record`); keep keys immutable; comparator consistent with equals for `TreeMap`.

**Concurrency:** plain collections not thread-safe → `ConcurrentHashMap` (per-bin lock, lock-free reads), `CopyOnWriteArrayList` (read-mostly). `Vector`/`Hashtable`/`Stack` legacy — avoid.

**CME:** don't modify while iterating; use `Iterator.remove`/`removeIf`/copy/concurrent collection.

**Decision rules:** ordering needed? → `LinkedHashMap`/`TreeMap`. Range/sorted? → `TreeMap`/`TreeSet`. Enum keys? → `EnumMap`/`EnumSet`. Concurrent? → `ConcurrentHashMap`. LRU? → `LinkedHashMap(accessOrder)` / Caffeine.

### Self-test (no answers — active recall)

1. Trace `HashMap.put` for a key that collides into a bin already holding 7 nodes in a 32-bucket table. What happens, exactly, and why?
2. Your `Set<Order>` accepts what should be a duplicate order. Name three distinct root causes and how you'd confirm each.
3. Explain, with the bit-level detail, how Java 8 resize moves an entry without recomputing its hash. What single bit decides its fate?
4. You must iterate a shared map across threads while it's being updated, and you cannot tolerate exceptions. What do you choose and what are the iteration semantics?
5. Justify the choice of `ArrayDeque` over both `LinkedList` and `Stack` for a DFS, addressing memory and cache behavior.
6. When is `LinkedList` genuinely the right `List`, and what specific access pattern makes it so?
7. Design a bounded, thread-safe, read-through cache keyed by a composite key. Specify the map type, the key type, the read-modify-write call, and the eviction policy — and one pitfall of doing the computation inside the map.

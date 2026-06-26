/* ============================================================================
 * LRU CACHE  —  Single-file LLD review / revision artifact
 * ----------------------------------------------------------------------------
 * This file is for READING and REVISION before an interview. It is complete and
 * correct-by-inspection; you do NOT need to compile or run it. The main() at the
 * bottom only ILLUSTRATES usage.
 *
 * ----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ----------------------------------------------------------------------------
 * Functional:
 *   1. Operations needed: just get/put, or also remove/containsKey/size/clear?
 *   2. get(miss) -> null, throw, or Optional? Are null keys/values allowed?
 *   3. Does updating an existing key refresh recency? (yes)
 *   4. Capacity fixed at construction, or resizable at runtime?
 * Eviction:
 *   5. Strict LRU, or must we be able to swap LFU/FIFO/MRU later? (-> Strategy)
 *   6. TTL / expiry? Lazy (on access) or active (background sweeper)?
 *   7. Eviction granularity: one entry per overflow, or size-weighted multi-evict?
 * Non-functional:
 *   8. Single-threaded or thread-safe? Read/write ratio & contention?
 *   9. O(1) get/put required? Capacity / memory budget (thousands vs millions)?
 *  10. Write-through / read-through to a backing store? Metrics / eviction hooks?
 *  11. Generic Cache<K,V> or concrete types?
 * Out of scope (confirm): distribution, persistence, serialization, coherence.
 *
 * ----------------------------------------------------------------------------
 * DECISIONS (this artifact)
 *   - HashMap<K,Node> + doubly-linked list with SENTINEL head/tail -> O(1) all ops.
 *   - Strategy: EvictionPolicy (LRU + FIFO shown) so the cache obeys OCP.
 *   - Observer hook: EvictionListener.onEvict (write-through / logging),
 *     fired AFTER releasing the lock in the concurrent variant.
 *   - Decorator: ConcurrentLRUCache adds a ReentrantReadWriteLock; note that
 *     get() is a WRITER (it reorders) so it takes the WRITE lock.
 *   - null keys/values rejected so get()->null means "miss" unambiguously.
 *   - Optional TTL via Node.expireAt with lazy expiry on get().
 * ==========================================================================*/

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/* ===========================================================================
 * Public contract (ISP: small, focused interface).
 * =========================================================================*/
interface Cache<K, V> {
    V get(K key);
    void put(K key, V value);
    boolean remove(K key);
    boolean containsKey(K key);
    int size();
    void clear();
}

/* ===========================================================================
 * Eviction hook (Observer / DIP). Default no-op so it is optional.
 * =========================================================================*/
interface EvictionListener<K, V> {
    void onEvict(K key, V value);

    @SuppressWarnings("rawtypes")
    EvictionListener NOOP = (k, v) -> { };

    @SuppressWarnings("unchecked")
    static <K, V> EvictionListener<K, V> noop() {
        return (EvictionListener<K, V>) NOOP;
    }
}

/* ===========================================================================
 * Node: one cache entry. Carries DLL pointers, optional TTL and weight.
 * Package-private fields keep the pointer code terse (this is an LLD artifact).
 * =========================================================================*/
final class Node<K, V> {
    final K key;
    V value;
    long expireAtNanos;          // Long.MAX_VALUE == never expires
    int weight;                  // for size-weighted caches (default 1)
    Node<K, V> prev;
    Node<K, V> next;

    Node(K key, V value, long expireAtNanos, int weight) {
        this.key = key;
        this.value = value;
        this.expireAtNanos = expireAtNanos;
        this.weight = weight;
    }
}

/* ===========================================================================
 * DoublyLinkedList with SENTINEL head & tail.
 * Sentinels remove all empty/singleton special-casing -> the main correctness
 * lever. Front (head.next) = most recently used; back (tail.prev) = LRU.
 * Every method is O(1).
 * =========================================================================*/
final class DoublyLinkedList<K, V> {
    private final Node<K, V> head; // sentinel
    private final Node<K, V> tail; // sentinel

    DoublyLinkedList() {
        head = new Node<>(null, null, Long.MAX_VALUE, 0);
        tail = new Node<>(null, null, Long.MAX_VALUE, 0);
        head.next = tail;
        tail.prev = head;
    }

    /** Insert node right after the head sentinel (becomes MRU). O(1). */
    void addFirst(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /** Unlink an arbitrary node. O(1) thanks to prev/next pointers + sentinels. */
    void remove(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null; // help GC / avoid stale links
        node.next = null;
    }

    /** Move an existing node to the front (recency refresh). O(1). */
    void moveToFront(Node<K, V> node) {
        remove(node);
        addFirst(node);
    }

    /** The LRU node (just before the tail sentinel), or null if empty. O(1). */
    Node<K, V> last() {
        return isEmpty() ? null : tail.prev;
    }

    boolean isEmpty() {
        return head.next == tail;
    }

    void clear() {
        head.next = tail;
        tail.prev = head;
    }
}

/* ===========================================================================
 * Strategy: how do we order entries and pick the eviction victim?
 *   - recordInsert: a brand new node was added.
 *   - recordAccess: an existing node was read or updated (recency event).
 *   - evictCandidate: which node should die when over capacity?
 *   - remove: forget a node that the cache removed explicitly.
 * =========================================================================*/
interface EvictionPolicy<K, V> {
    void recordInsert(Node<K, V> node);
    void recordAccess(Node<K, V> node);
    Node<K, V> evictCandidate();
    void remove(Node<K, V> node);
    void clear();
}

/* LRU: most-recently-used at the front; evict the tail. */
final class LRUEvictionPolicy<K, V> implements EvictionPolicy<K, V> {
    private final DoublyLinkedList<K, V> list = new DoublyLinkedList<>();

    @Override public void recordInsert(Node<K, V> node) { list.addFirst(node); }
    @Override public void recordAccess(Node<K, V> node) { list.moveToFront(node); }
    @Override public Node<K, V> evictCandidate()         { return list.last(); }
    @Override public void remove(Node<K, V> node)        { list.remove(node); }
    @Override public void clear()                        { list.clear(); }
}

/* FIFO: same list, but access does NOT reorder (insertion order = eviction order).
 * Shows how a new policy plugs in with ZERO changes to LRUCache (OCP). */
final class FIFOEvictionPolicy<K, V> implements EvictionPolicy<K, V> {
    private final DoublyLinkedList<K, V> list = new DoublyLinkedList<>();

    @Override public void recordInsert(Node<K, V> node) { list.addFirst(node); }
    @Override public void recordAccess(Node<K, V> node) { /* no reorder on access */ }
    @Override public Node<K, V> evictCandidate()         { return list.last(); }
    @Override public void remove(Node<K, V> node)        { list.remove(node); }
    @Override public void clear()                        { list.clear(); }
}

/* ===========================================================================
 * Core cache. NOT thread-safe by itself (wrap with ConcurrentLRUCache).
 * Delegates ordering to the EvictionPolicy (Strategy) and eviction
 * notifications to the EvictionListener (Observer).
 * =========================================================================*/
class LRUCache<K, V> implements Cache<K, V> {

    private int capacity;
    private final Map<K, Node<K, V>> map;
    private final EvictionPolicy<K, V> policy;
    private final EvictionListener<K, V> listener;
    private final long ttlNanos;          // 0 => no TTL

    // ---- Constructors / factory ----

    LRUCache(int capacity) {
        this(capacity, new LRUEvictionPolicy<>(), EvictionListener.noop(), 0L);
    }

    LRUCache(int capacity, EvictionPolicy<K, V> policy,
             EvictionListener<K, V> listener, long ttlNanos) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.listener = listener == null ? EvictionListener.noop() : listener;
        this.ttlNanos = ttlNanos;
        this.map = new HashMap<>();
    }

    /** Light factory method: a plain LRU cache of the given capacity. */
    static <K, V> LRUCache<K, V> of(int capacity) {
        return new LRUCache<>(capacity);
    }

    // ---- Cache API ----

    @Override
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> node = map.get(key);
        if (node == null) return null;                 // miss

        if (isExpired(node)) {                         // lazy TTL expiry
            removeNode(node, /*evicted=*/false);
            return null;
        }
        policy.recordAccess(node);                     // recency refresh, O(1)
        return node.value;
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");        // null values disallowed

        Node<K, V> existing = map.get(key);
        if (existing != null) {                        // update in place, no eviction
            existing.value = value;
            existing.expireAtNanos = computeExpiry();
            policy.recordAccess(existing);
            return;
        }

        Node<K, V> node = new Node<>(key, value, computeExpiry(), 1);
        map.put(key, node);
        policy.recordInsert(node);
        evictIfNeeded();
    }

    @Override
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> node = map.get(key);
        if (node == null) return false;
        removeNode(node, /*evicted=*/false);           // explicit remove -> no eviction event
        return true;
    }

    @Override
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> node = map.get(key);
        return node != null && !isExpired(node);       // do NOT bump recency here
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void clear() {
        map.clear();
        policy.clear();
    }

    /** Runtime resize: shrink evicts from the LRU end until size fits. O(k). */
    void setCapacity(int newCapacity) {
        if (newCapacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = newCapacity;
        evictIfNeeded();
    }

    // ---- internals ----

    private void evictIfNeeded() {
        while (map.size() > capacity) {
            Node<K, V> victim = policy.evictCandidate();
            if (victim == null) break;                 // defensive; shouldn't happen
            removeNode(victim, /*evicted=*/true);
        }
    }

    /** Unlink from map + policy; fire listener only for real evictions. */
    private void removeNode(Node<K, V> node, boolean evicted) {
        map.remove(node.key);
        policy.remove(node);
        if (evicted) listener.onEvict(node.key, node.value);
    }

    private long computeExpiry() {
        return ttlNanos > 0 ? System.nanoTime() + ttlNanos : Long.MAX_VALUE;
    }

    private boolean isExpired(Node<K, V> node) {
        return ttlNanos > 0 && System.nanoTime() > node.expireAtNanos;
    }
}

/* ===========================================================================
 * DECORATOR: thread-safe wrapper around any Cache.
 *
 * KEY SUBTLETY: in an LRU cache get() MUTATES the recency list, so it is a
 * WRITER. A naive design that lets get() take the read lock would let two
 * concurrent get()s corrupt the list. Hence get() takes the WRITE lock here.
 * Only genuinely read-only methods (size, containsKey) use the read lock.
 *
 * For higher throughput you would shard into striped segments (one lock + list
 * per shard, shard = hash(key) % N) — at the cost of only APPROXIMATE global
 * LRU. That is the scale-out path (see design doc §9).
 * =========================================================================*/
final class ConcurrentLRUCache<K, V> implements Cache<K, V> {

    private final Cache<K, V> delegate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    ConcurrentLRUCache(Cache<K, V> delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public V get(K key) {
        lock.writeLock().lock();          // get() reorders -> WRITE lock
        try { return delegate.get(key); }
        finally { lock.writeLock().unlock(); }
    }

    @Override
    public void put(K key, V value) {
        lock.writeLock().lock();
        try { delegate.put(key, value); }
        finally { lock.writeLock().unlock(); }
    }

    @Override
    public boolean remove(K key) {
        lock.writeLock().lock();
        try { return delegate.remove(key); }
        finally { lock.writeLock().unlock(); }
    }

    @Override
    public boolean containsKey(K key) {
        lock.readLock().lock();           // truly read-only -> READ lock
        try { return delegate.containsKey(key); }
        finally { lock.readLock().unlock(); }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try { return delegate.size(); }
        finally { lock.readLock().unlock(); }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try { delegate.clear(); }
        finally { lock.writeLock().unlock(); }
    }

    /** Atomic read-through / memoization: load + store under one write lock. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFn) {
        lock.writeLock().lock();
        try {
            V existing = delegate.get(key);
            if (existing != null) return existing;
            V computed = mappingFn.apply(key);
            if (computed != null) delegate.put(key, computed);
            return computed;
        } finally {
            lock.writeLock().unlock();
        }
    }
}

/* ===========================================================================
 * OPTIONAL DECORATOR: metrics. Shows how cross-cutting concerns stack as
 * decorators without touching LRUCache (SRP). Counts hits / misses / puts.
 * =========================================================================*/
final class MetricsCache<K, V> implements Cache<K, V> {
    private final Cache<K, V> delegate;
    private long hits, misses, puts;

    MetricsCache(Cache<K, V> delegate) { this.delegate = delegate; }

    @Override public V get(K key) {
        V v = delegate.get(key);
        if (v != null) hits++; else misses++;
        return v;
    }
    @Override public void put(K key, V value) { puts++; delegate.put(key, value); }
    @Override public boolean remove(K key) { return delegate.remove(key); }
    @Override public boolean containsKey(K key) { return delegate.containsKey(key); }
    @Override public int size() { return delegate.size(); }
    @Override public void clear() { delegate.clear(); }

    double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
    String stats() {
        return String.format("hits=%d misses=%d puts=%d hitRate=%.2f",
                hits, misses, puts, hitRate());
    }
}

/* ===========================================================================
 * Demo. ILLUSTRATIVE ONLY — exercises the key scenarios and prints output.
 * =========================================================================*/
public class Solution {

    public static void main(String[] args) {
        System.out.println("=== 1) Basic LRU eviction ===");
        Cache<Integer, String> cache = LRUCache.of(2);
        cache.put(1, "one");
        cache.put(2, "two");
        System.out.println("get(1) = " + cache.get(1));   // "one"  -> 1 becomes MRU
        cache.put(3, "three");                            // evicts key 2 (LRU)
        System.out.println("get(2) = " + cache.get(2));   // null (evicted)
        System.out.println("get(3) = " + cache.get(3));   // "three"
        System.out.println("size   = " + cache.size());   // 2

        System.out.println("\n=== 2) Update refreshes recency (no eviction) ===");
        Cache<Integer, String> c2 = LRUCache.of(2);
        c2.put(1, "a");
        c2.put(2, "b");
        c2.put(1, "A");                                   // update key 1, refreshes recency
        c2.put(3, "c");                                   // evicts key 2, not key 1
        System.out.println("get(1) = " + c2.get(1));      // "A" (survived)
        System.out.println("get(2) = " + c2.get(2));      // null (evicted)

        System.out.println("\n=== 3) Eviction listener (write-through hook) ===");
        EvictionListener<Integer, String> listener =
                (k, v) -> System.out.println("  evicted -> key=" + k + " value=" + v);
        LRUCache<Integer, String> c3 =
                new LRUCache<>(2, new LRUEvictionPolicy<>(), listener, 0L);
        c3.put(10, "x");
        c3.put(20, "y");
        c3.put(30, "z");                                  // fires onEvict(10, "x")

        System.out.println("\n=== 4) Pluggable policy: FIFO (Strategy) ===");
        Cache<Integer, String> fifo =
                new LRUCache<>(2, new FIFOEvictionPolicy<>(), EvictionListener.noop(), 0L);
        fifo.put(1, "one");
        fifo.put(2, "two");
        fifo.get(1);                                      // access does NOT save key 1 under FIFO
        fifo.put(3, "three");                             // evicts key 1 (first in)
        System.out.println("get(1) = " + fifo.get(1));    // null (FIFO evicted first-inserted)
        System.out.println("get(2) = " + fifo.get(2));    // "two"

        System.out.println("\n=== 5) Runtime resize (shrink evicts LRU) ===");
        LRUCache<Integer, String> c5 = LRUCache.of(3);
        c5.put(1, "a"); c5.put(2, "b"); c5.put(3, "c");
        System.out.println("size before shrink = " + c5.size()); // 3
        c5.setCapacity(1);                                // evicts down to 1 (keeps MRU = key 3)
        System.out.println("size after shrink  = " + c5.size()); // 1
        System.out.println("get(3) = " + c5.get(3));      // "c" (the MRU survives)

        System.out.println("\n=== 6) TTL lazy expiry ===");
        long ttl = 50_000_000L; // 50 ms in nanos
        LRUCache<Integer, String> c6 =
                new LRUCache<>(5, new LRUEvictionPolicy<>(), EvictionListener.noop(), ttl);
        c6.put(1, "soon-gone");
        System.out.println("immediately get(1) = " + c6.get(1)); // "soon-gone"
        sleep(80);
        System.out.println("after TTL  get(1) = " + c6.get(1));  // null (expired lazily)

        System.out.println("\n=== 7) Thread-safe wrapper + computeIfAbsent ===");
        ConcurrentLRUCache<Integer, String> safe =
                new ConcurrentLRUCache<>(LRUCache.of(100));
        String loaded = safe.computeIfAbsent(42, k -> "loaded-" + k);
        System.out.println("computeIfAbsent(42) = " + loaded);   // loaded-42
        System.out.println("computeIfAbsent(42) = " +            // cached, fn not re-run
                safe.computeIfAbsent(42, k -> "SHOULD-NOT-RUN"));
        runConcurrencyStress(safe);

        System.out.println("\n=== 8) Metrics decorator ===");
        MetricsCache<Integer, String> metered = new MetricsCache<>(LRUCache.of(2));
        metered.put(1, "a");
        metered.get(1);  // hit
        metered.get(9);  // miss
        System.out.println(metered.stats());

        System.out.println("\nDone.");
    }

    /** Minimal concurrency smoke test: many threads hammer the cache; we just
     *  assert it stays internally consistent and within capacity. */
    private static void runConcurrencyStress(ConcurrentLRUCache<Integer, String> cache) {
        final int threads = 8, opsPerThread = 5000, keySpace = 200;
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                java.util.Random rnd = new java.util.Random();
                for (int i = 0; i < opsPerThread; i++) {
                    int k = rnd.nextInt(keySpace);
                    if ((i & 1) == 0) cache.put(k, "v" + k);
                    else cache.get(k);
                }
            });
            pool.add(th);
            th.start();
        }
        for (Thread th : pool) {
            try { th.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        System.out.println("concurrency stress ok; size <= 100 holds: " + (cache.size() <= 100));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}

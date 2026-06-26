/*
 * LRU Cache — single-file LLD solution (REVIEW / REVISION artifact — read it, no need to compile/run).
 *
 * REQUIREMENTS / CLARIFYING QUESTIONS TO ASK BEFORE DESIGNING:
 *  1. Is capacity fixed at construction, or resizable at runtime?
 *  2. Must get AND put both be O(1)? (Yes -> HashMap + doubly-linked list.)
 *  3. Does get() count as a "use" (promote to most-recent)? (Assume yes.)
 *  4. Thread-safe? Single-threaded core or concurrent access? (Provide both.)
 *  5. TTL/expiry, or pure recency? (Pure LRU here; TTL is an extension.)
 *  6. Generic keys/values? Are null keys/values allowed? (Generic; null key rejected.)
 *  7. Need an eviction callback / hit-miss stats? (Extension.)
 *
 * Patterns: HashMap+DLL composition (O(1) both ops), Strategy-ready, Decorator for thread-safety.
 */
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class Solution {

    // ---- Cache abstraction (DIP) ----
    interface Cache<K, V> {
        V get(K key);          // returns null if absent
        void put(K key, V value);
        int size();
    }

    // ---- Core LRU: HashMap (O(1) lookup) + doubly-linked list (O(1) recency) ----
    static class LRUCache<K, V> implements Cache<K, V> {

        private static final class Node<K, V> {
            K key; V value; Node<K, V> prev, next;
            Node(K key, V value) { this.key = key; this.value = value; }
        }

        private final int capacity;
        private final Map<K, Node<K, V>> map = new HashMap<>();
        private final Node<K, V> head = new Node<>(null, null); // sentinel: MRU side
        private final Node<K, V> tail = new Node<>(null, null); // sentinel: LRU side

        LRUCache(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
            this.capacity = capacity;
            head.next = tail;
            tail.prev = head;
        }

        @Override public V get(K key) {
            Node<K, V> n = map.get(key);
            if (n == null) return null;
            moveToFront(n);            // mark most-recently-used
            return n.value;
        }

        @Override public void put(K key, V value) {
            if (key == null) throw new IllegalArgumentException("null key not allowed");
            Node<K, V> n = map.get(key);
            if (n != null) {           // update existing
                n.value = value;
                moveToFront(n);
                return;
            }
            Node<K, V> fresh = new Node<>(key, value);
            map.put(key, fresh);
            addFront(fresh);
            if (map.size() > capacity) evictLRU();
        }

        @Override public int size() { return map.size(); }

        // ---- O(1) doubly-linked-list surgery ----
        private void addFront(Node<K, V> n) {
            n.prev = head; n.next = head.next;
            head.next.prev = n; head.next = n;
        }
        private void remove(Node<K, V> n) {
            n.prev.next = n.next; n.next.prev = n.prev;
        }
        private void moveToFront(Node<K, V> n) { remove(n); addFront(n); }
        private void evictLRU() {
            Node<K, V> lru = tail.prev;     // node just before tail sentinel
            remove(lru);
            map.remove(lru.key);
            // (extension point: fire eviction callback here)
        }
    }

    // ---- Decorator: add thread-safety without touching LRUCache (SRP/OCP) ----
    static class ThreadSafeCache<K, V> implements Cache<K, V> {
        private final Cache<K, V> inner;
        private final ReentrantLock lock = new ReentrantLock();
        ThreadSafeCache(Cache<K, V> inner) { this.inner = inner; }
        @Override public V get(K k)        { lock.lock(); try { return inner.get(k); } finally { lock.unlock(); } }
        @Override public void put(K k, V v){ lock.lock(); try { inner.put(k, v); }     finally { lock.unlock(); } }
        @Override public int size()        { lock.lock(); try { return inner.size(); } finally { lock.unlock(); } }
        // For high concurrency, shard into N ThreadSafeCache segments keyed by hash(key) % N.
    }

    // ---- Demo (illustrative; for reading) ----
    public static void main(String[] args) {
        Cache<Integer, String> cache = new ThreadSafeCache<>(new LRUCache<>(2));
        cache.put(1, "one");
        cache.put(2, "two");
        System.out.println(cache.get(1));   // "one"  -> 1 becomes most-recent
        cache.put(3, "three");              // capacity 2 -> evicts LRU (key 2)
        System.out.println(cache.get(2));   // null   -> evicted
        System.out.println(cache.get(3));   // "three"
        cache.put(4, "four");               // evicts key 1 (now LRU)
        System.out.println(cache.get(1));   // null
        System.out.println(cache.get(3));   // "three"
        System.out.println(cache.get(4));   // "four"
        System.out.println("size=" + cache.size()); // 2
    }
}

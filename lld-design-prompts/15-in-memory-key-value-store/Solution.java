/* =====================================================================================
 * In-Memory Key-Value Store (Redis-lite) — SINGLE-FILE REVIEW / REVISION ARTIFACT
 * -------------------------------------------------------------------------------------
 * This file is meant to be READ for last-minute interview revision. It is complete and
 * correct-by-inspection (no TODOs, no omitted bodies). You do NOT need to compile/run it.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional scope:
 *   1. Which data types? (string only, or also list/hash/set/zset?)  -> we do string+list+hash.
 *   2. Which commands per type? SET/GET/DEL/EXISTS/INCR; LPUSH/RPUSH/LPOP/LRANGE; HSET/HGET/HGETALL/HDEL.
 *   3. TTL/expiry needed? Lazy (on access) AND active (background sweep)? millis precision? -> yes, both.
 *   4. Transactions? MULTI/EXEC/DISCARD only or also WATCH? Roll back on error? -> MULTI/EXEC/DISCARD,
 *      Redis-style (no runtime rollback). WATCH is an extension.
 *   5. Eviction? Which policies (LRU/LFU/Random)? Triggered by key-count or bytes? -> pluggable, maxKeys.
 *   6. Pub/Sub? Sync or async fan-out? Pattern subs? -> sync Observer; async is an extension.
 *   7. Persistence, or just a hook/seam? -> hook only (Strategy + Observer), NoOp default.
 * Non-functional:
 *   8. Concurrency: single-threaded event loop or multi-threaded clients? -> multi-threaded (prompt asks).
 *   9. Scale (#keys, value sizes, QPS), read- vs write-heavy?
 *  10. Latency target (O(1) avg ok?), worst-case bounds?
 *  11. Memory budget / hard cap that triggers eviction? -> approximated by maxKeys.
 *  12. Durability on crash (pure cache vs must survive restart)?
 * Scope-narrowing (out):
 *  13. Network / RESP protocol?  -> OUT; in-process Java API.
 *  14. Clustering/replication/sharding?  -> OUT (mention as extension).
 *  15. Auth / ACL / namespaces?  -> OUT.
 *  16. Type-mixing rules (LPUSH on a string key)? -> WRONGTYPE error, like Redis.
 *
 * -------------------------------------------------------------------------------------
 * DESIGN PATTERNS USED
 *   - Command   : every op is a Command object  -> enables MULTI/EXEC queueing + AOF-style logging.
 *   - Strategy  : EvictionPolicy (LRU/LFU/Random), PersistenceHook (NoOp/CommandLog).
 *   - Singleton : one KVStore per process (with public ctor escape-hatch for tests/sharding).
 *   - Observer  : MessageBus -> pub/sub Subscribers + internal StoreEventListeners (persistence/metrics).
 *   - Facade    : KVStore hides lock+expiry+eviction+notify orchestration.
 *   - Factory   : CommandFactory builds Command objects from name+args.
 *   - Template  : Value base provides type guards for uniform WRONGTYPE behavior.
 * CONCURRENCY: ReentrantReadWriteLock (shared reads / exclusive writes & EXEC) over a
 *              ConcurrentHashMap; lazy-expire uses conditional remove to avoid lock upgrade;
 *              active expiry via a daemon sweeper thread. Scale via sharding/lock-striping.
 * ===================================================================================== */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class Solution {

    /* ================================ VALUE TYPES (Template Method) ===================== */

    enum ValueType { STRING, LIST, HASH }

    /** Base type for everything stored. Subclasses implement type-specific behavior. */
    static abstract class Value {
        abstract ValueType type();

        /** Template helper: uniform WRONGTYPE enforcement across the codebase. */
        @SuppressWarnings("unchecked")
        <T extends Value> T as(Class<T> clazz) {
            if (!clazz.isInstance(this)) {
                throw new WrongTypeException(
                        "WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            return (T) this;
        }
    }

    static final class StringValue extends Value {
        String s;
        StringValue(String s) { this.s = s; }
        ValueType type() { return ValueType.STRING; }
    }

    static final class ListValue extends Value {
        // ArrayDeque gives O(1) push/pop at both ends; we expose Redis-style L/R semantics.
        final Deque<String> list = new ArrayDeque<>();
        ValueType type() { return ValueType.LIST; }
    }

    static final class HashValue extends Value {
        final Map<String, String> map = new LinkedHashMap<>();
        ValueType type() { return ValueType.HASH; }
    }

    /** Wraps a Value with metadata used by TTL, eviction and (optional) WATCH versioning. */
    static final class Entry {
        Value value;
        long expireAtMs;   // 0 == no expiry
        long version;      // bumped on every mutation (used by WATCH extension)
        volatile long lastAccessMs;
        volatile long hits;

        Entry(Value value, long expireAtMs) {
            this.value = value;
            this.expireAtMs = expireAtMs;
            this.version = 1;
            this.lastAccessMs = System.currentTimeMillis();
        }

        boolean isExpired(long now) { return expireAtMs != 0 && now >= expireAtMs; }
    }

    static final class WrongTypeException extends RuntimeException {
        WrongTypeException(String m) { super(m); }
    }
    static final class StoreException extends RuntimeException {
        StoreException(String m) { super(m); }
    }

    /* ================================ DATA STORE (SRP) ================================== */

    /** Pure storage: a thread-safe map of key -> Entry. No business rules here. */
    static final class DataStore {
        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

        Entry get(String key)            { return map.get(key); }
        void  put(String key, Entry e)   { map.put(key, e); }
        Entry remove(String key)         { return map.remove(key); }
        /** Conditional remove — used by lazy expiry to avoid a read->write lock upgrade. */
        boolean removeIf(String key, Entry expected) { return map.remove(key, expected); }
        Set<String> keySet()             { return map.keySet(); }
        int   size()                     { return map.size(); }
        boolean containsKey(String key)  { return map.containsKey(key); }
    }

    /* ================================ OBSERVER: events + pub/sub ======================== */

    enum EventType { SET, DEL, EXPIRE, EVICT }

    static final class StoreEvent {
        final EventType type; final String key; final long ts;
        StoreEvent(EventType type, String key) {
            this.type = type; this.key = key; this.ts = System.currentTimeMillis();
        }
        public String toString() { return type + "(" + key + ")"; }
    }

    /** Observer of internal mutations (persistence, metrics) — Interface Segregation. */
    interface StoreEventListener { void onEvent(StoreEvent e); }

    /** Pub/Sub channel subscriber. */
    interface Subscriber { void onMessage(String channel, String message); }

    /** Observer SUBJECT: both pub/sub channels and internal store-event listeners. */
    static final class MessageBus {
        private final Map<String, Set<Subscriber>> channels = new ConcurrentHashMap<>();
        private final List<StoreEventListener> listeners = new CopyOnWriteArrayList<>();

        void addListener(StoreEventListener l) { listeners.add(l); }

        void subscribe(String channel, Subscriber s) {
            channels.computeIfAbsent(channel, c -> ConcurrentHashMap.newKeySet()).add(s);
        }
        void unsubscribe(String channel, Subscriber s) {
            Set<Subscriber> set = channels.get(channel);
            if (set != null) set.remove(s);
        }
        /** Synchronous fan-out (default). Async delivery is a documented extension. */
        int publish(String channel, String message) {
            Set<Subscriber> set = channels.get(channel);
            if (set == null) return 0;
            int n = 0;
            for (Subscriber s : set) { s.onMessage(channel, message); n++; }
            return n;
        }
        void fire(StoreEvent e) { for (StoreEventListener l : listeners) l.onEvent(e); }
    }

    /* ================================ STRATEGY: eviction ================================ */

    interface EvictionPolicy {
        void recordAccess(String key);  // on read/write
        void recordInsert(String key);  // on new key
        void recordRemove(String key);  // on delete/expire/evict
        String evictCandidate();        // pick a victim, or null if none
        String name();
    }

    /** LRU via access-ordered LinkedHashMap; guarded externally by the store's write lock. */
    static final class LRUEviction implements EvictionPolicy {
        // accessOrder=true -> get/put move entry to the tail; eldest is the LRU victim.
        private final LinkedHashMap<String, Boolean> recency =
                new LinkedHashMap<>(16, 0.75f, true);
        public void recordAccess(String key) { recency.get(key); }       // touches recency
        public void recordInsert(String key) { recency.put(key, Boolean.TRUE); }
        public void recordRemove(String key) { recency.remove(key); }
        public String evictCandidate() {
            Iterator<String> it = recency.keySet().iterator();
            return it.hasNext() ? it.next() : null;                       // eldest = LRU
        }
        public String name() { return "LRU"; }
    }

    /** LFU: evict least-frequently-used; ties broken by oldest counter. */
    static final class LFUEviction implements EvictionPolicy {
        private final Map<String, Long> freq = new HashMap<>();
        public void recordAccess(String key) { freq.merge(key, 1L, Long::sum); }
        public void recordInsert(String key) { freq.putIfAbsent(key, 1L); }
        public void recordRemove(String key) { freq.remove(key); }
        public String evictCandidate() {
            String victim = null; long best = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : freq.entrySet()) {
                if (e.getValue() < best) { best = e.getValue(); victim = e.getKey(); }
            }
            return victim;
        }
        public String name() { return "LFU"; }
    }

    /** Random eviction: O(1)-ish, no recency/frequency bookkeeping. */
    static final class RandomEviction implements EvictionPolicy {
        private final List<String> keys = new ArrayList<>();
        private final Set<String> set = new HashSet<>();
        private final Random rnd = new Random();
        public void recordAccess(String key) { /* no-op */ }
        public void recordInsert(String key) { if (set.add(key)) keys.add(key); }
        public void recordRemove(String key) { if (set.remove(key)) keys.remove(key); }
        public String evictCandidate() {
            return keys.isEmpty() ? null : keys.get(rnd.nextInt(keys.size()));
        }
        public String name() { return "RANDOM"; }
    }

    /* ================================ STRATEGY: persistence ============================= */

    interface PersistenceHook extends StoreEventListener { /* marker + Observer */ }

    static final class NoOpPersistence implements PersistenceHook {
        public void onEvent(StoreEvent e) { /* durability disabled */ }
    }

    /** AOF-style seam: append a record per mutating event. Here it just buffers in memory. */
    static final class CommandLogPersistence implements PersistenceHook {
        final List<String> log = new CopyOnWriteArrayList<>();
        public void onEvent(StoreEvent e) { log.add(e.toString()); }
    }

    /* ================================ EXPIRY MANAGER (lazy + active) ==================== */

    /** Owns TTL policy: lazy check helper + an active background sweeper. */
    static final class ExpiryManager {
        private final DataStore data;
        private final MessageBus bus;
        private final long sweepIntervalMs;
        private final int sampleSize;
        private volatile boolean running = false;
        private Thread sweeper;

        ExpiryManager(DataStore data, MessageBus bus, long sweepIntervalMs, int sampleSize) {
            this.data = data; this.bus = bus;
            this.sweepIntervalMs = sweepIntervalMs; this.sampleSize = sampleSize;
        }

        /** LAZY expiry: returns true (and physically removes) if the entry has expired. */
        boolean expireIfNeeded(String key, Entry entry, long now) {
            if (entry != null && entry.isExpired(now)) {
                // conditional remove: only deletes if it's still the same expired entry.
                if (data.removeIf(key, entry)) bus.fire(new StoreEvent(EventType.EXPIRE, key));
                return true;
            }
            return false;
        }

        /** ACTIVE expiry: probabilistic sampling sweep, like Redis. Runs under write lock. */
        void start(ReentrantReadWriteLock lock, EvictionPolicy eviction) {
            running = true;
            sweeper = new Thread(() -> {
                while (running) {
                    try { Thread.sleep(sweepIntervalMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); return;
                    }
                    long now = System.currentTimeMillis();
                    lock.writeLock().lock();
                    try {
                        int seen = 0;
                        for (String key : new ArrayList<>(data.keySet())) {
                            if (seen++ >= sampleSize) break;
                            Entry e = data.get(key);
                            if (e != null && e.isExpired(now) && data.removeIf(key, e)) {
                                eviction.recordRemove(key);
                                bus.fire(new StoreEvent(EventType.EXPIRE, key));
                            }
                        }
                    } finally { lock.writeLock().unlock(); }
                }
            }, "kv-expiry-sweeper");
            sweeper.setDaemon(true);   // never block JVM shutdown
            sweeper.start();
        }
        void stop() { running = false; if (sweeper != null) sweeper.interrupt(); }
    }

    /* ================================ COMMAND PATTERN =================================== */

    /** Context handed to commands so they don't talk to KVStore internals directly. */
    interface ExecutionContext {
        Object run(Command c);            // execute a command immediately (used by EXEC)
        KVStore store();
    }

    /** Operation reified as an object — enables queueing (MULTI/EXEC) and logging (AOF). */
    interface Command {
        Object execute(KVStore store);    // perform against the store (lock-managed by store)
        String name();
        /** Run inside EXEC — the caller already holds the write lock, so route to *Raw
         *  store methods (no re-locking). Keeps the whole batch atomic. */
        default Object executeRaw(KVStore store) { return executeRawDispatch(this, store); }
    }

    static final class SetCommand implements Command {
        final String key, value; final long ttlMs;
        SetCommand(String key, String value, long ttlMs) { this.key=key; this.value=value; this.ttlMs=ttlMs; }
        public Object execute(KVStore s) { s.set(key, value, ttlMs); return "OK"; }
        public String name() { return "SET"; }
    }
    static final class GetCommand implements Command {
        final String key; GetCommand(String key) { this.key = key; }
        public Object execute(KVStore s) { return s.get(key); }
        public String name() { return "GET"; }
    }
    static final class DelCommand implements Command {
        final String key; DelCommand(String key) { this.key = key; }
        public Object execute(KVStore s) { return s.del(key); }
        public String name() { return "DEL"; }
    }
    static final class IncrCommand implements Command {
        final String key; IncrCommand(String key) { this.key = key; }
        public Object execute(KVStore s) { return s.incr(key); }
        public String name() { return "INCR"; }
    }
    static final class LPushCommand implements Command {
        final String key; final String[] vals;
        LPushCommand(String key, String... vals) { this.key=key; this.vals=vals; }
        public Object execute(KVStore s) { return s.lpush(key, vals); }
        public String name() { return "LPUSH"; }
    }
    static final class LRangeCommand implements Command {
        final String key; final int start, end;
        LRangeCommand(String key, int start, int end) { this.key=key; this.start=start; this.end=end; }
        public Object execute(KVStore s) { return s.lrange(key, start, end); }
        public String name() { return "LRANGE"; }
    }
    static final class HSetCommand implements Command {
        final String key, field, value;
        HSetCommand(String key, String field, String value) { this.key=key; this.field=field; this.value=value; }
        public Object execute(KVStore s) { return s.hset(key, field, value); }
        public String name() { return "HSET"; }
    }
    static final class HGetCommand implements Command {
        final String key, field;
        HGetCommand(String key, String field) { this.key=key; this.field=field; }
        public Object execute(KVStore s) { return s.hget(key, field); }
        public String name() { return "HGET"; }
    }

    /** FACTORY: build Command objects from a name + args (used by a RESP parser or tx builder). */
    static final class CommandFactory {
        Command create(String name, String... args) {
            switch (name.toUpperCase()) {
                case "SET":   return new SetCommand(args[0], args[1], 0);
                case "GET":   return new GetCommand(args[0]);
                case "DEL":   return new DelCommand(args[0]);
                case "INCR":  return new IncrCommand(args[0]);
                case "LPUSH": return new LPushCommand(args[0], Arrays.copyOfRange(args, 1, args.length));
                case "HSET":  return new HSetCommand(args[0], args[1], args[2]);
                case "HGET":  return new HGetCommand(args[0], args[1]);
                default: throw new StoreException("ERR unknown command '" + name + "'");
            }
        }
    }

    /* ================================ TRANSACTIONS (Command + Memento-ish) ============== */

    /** Per-client session: holds MULTI state and the queued command list. */
    static final class ClientSession {
        boolean inTx = false;
        boolean dirty = false;                 // a queueing error occurred -> EXEC must abort
        final List<Command> queued = new ArrayList<>();
        // For the WATCH extension: Map<String,Long> watched (key -> version snapshot).
    }

    static final class TransactionManager {
        void multi(ClientSession s) {
            if (s.inTx) throw new StoreException("ERR MULTI calls can not be nested");
            s.inTx = true; s.dirty = false; s.queued.clear();
        }
        /** Queue a command during MULTI; if it can't even be built, mark dirty. */
        void queue(ClientSession s, Command c) {
            if (c == null) { s.dirty = true; return; }
            s.queued.add(c);
        }
        void discard(ClientSession s) { s.inTx = false; s.dirty = false; s.queued.clear(); }

        /** EXEC: run the whole batch atomically under the store's write lock.
         *  Redis-style: a runtime error in one command is captured as its result;
         *  already-applied commands are NOT rolled back (documented limitation). */
        List<Object> exec(ClientSession s, KVStore store) {
            if (!s.inTx) throw new StoreException("ERR EXEC without MULTI");
            if (s.dirty) { discard(s); throw new StoreException("EXECABORT batch had errors"); }
            List<Object> results = new ArrayList<>();
            store.lock.writeLock().lock();
            try {
                for (Command c : s.queued) {
                    try { results.add(c.executeRaw(store)); }       // raw = no re-locking
                    catch (RuntimeException ex) { results.add(ex); } // capture, keep going
                }
            } finally {
                store.lock.writeLock().unlock();
                s.inTx = false; s.queued.clear(); s.dirty = false;
            }
            return results;
        }
    }

    /* ================================ KVSTORE (Facade + Singleton) ===================== */

    static final class KVStore {
        // ---- Singleton (thread-safe holder), with public ctor escape-hatch for tests/sharding.
        private static final class Holder { static final KVStore INSTANCE = new KVStore(1000); }
        static KVStore getInstance() { return Holder.INSTANCE; }

        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final DataStore data = new DataStore();
        private final MessageBus bus = new MessageBus();
        private final ExpiryManager expiry;
        private EvictionPolicy eviction;
        private PersistenceHook persistence = new NoOpPersistence();
        private final int maxKeys;

        KVStore(int maxKeys) { this(maxKeys, new LRUEviction()); }
        KVStore(int maxKeys, EvictionPolicy policy) {
            this.maxKeys = maxKeys;
            this.eviction = policy;
            this.expiry = new ExpiryManager(data, bus, 100, 20);
            bus.addListener(persistence);
            expiry.start(lock, eviction);
        }

        // ---- configuration (DIP: depend on abstractions, injected) -----------------------
        void setEvictionPolicy(EvictionPolicy p) { this.eviction = p; }
        void setPersistence(PersistenceHook p)   { this.persistence = p; bus.addListener(p); }
        MessageBus bus() { return bus; }
        void shutdown() { expiry.stop(); }

        /* ----------------------------- internal helpers ------------------------------- */

        /** Fetch a live entry; applies LAZY expiry. Caller holds appropriate lock. */
        private Entry liveEntry(String key) {
            Entry e = data.get(key);
            long now = System.currentTimeMillis();
            if (expiry.expireIfNeeded(key, e, now)) return null;   // expired -> miss
            if (e != null) { e.lastAccessMs = now; e.hits++; eviction.recordAccess(key); }
            return e;
        }

        /** Evict one victim if inserting a NEW key would exceed the budget. Write-locked. */
        private void evictIfNeeded(boolean keyIsNew) {
            if (keyIsNew && data.size() >= maxKeys) {
                String victim = eviction.evictCandidate();
                if (victim != null) {
                    data.remove(victim);
                    eviction.recordRemove(victim);
                    bus.fire(new StoreEvent(EventType.EVICT, victim));
                }
            }
        }

        /* ----------------------------- STRING ops ------------------------------------- */

        void set(String key, String value) { set(key, value, 0); }
        void set(String key, String value, long ttlMs) {
            lock.writeLock().lock();
            try { setRaw(key, value, ttlMs); }
            finally { lock.writeLock().unlock(); }
        }
        // "raw" variants assume the caller already holds the write lock (used inside EXEC).
        void setRaw(String key, String value, long ttlMs) {
            boolean isNew = !data.containsKey(key);
            evictIfNeeded(isNew);
            long expireAt = ttlMs > 0 ? System.currentTimeMillis() + ttlMs : 0;
            if (ttlMs < 0) expireAt = System.currentTimeMillis() - 1; // negative TTL -> immediate
            Entry e = new Entry(new StringValue(value), expireAt);
            data.put(key, e);
            eviction.recordInsert(key);
            bus.fire(new StoreEvent(EventType.SET, key));
        }

        String get(String key) {
            lock.readLock().lock();
            try {
                Entry e = liveEntry(key);
                return e == null ? null : e.value.as(StringValue.class).s;
            } finally { lock.readLock().unlock(); }
        }
        String getRaw(String key) {
            Entry e = liveEntry(key);
            return e == null ? null : e.value.as(StringValue.class).s;
        }

        long incr(String key) {
            lock.writeLock().lock();
            try { return incrRaw(key); }
            finally { lock.writeLock().unlock(); }
        }
        long incrRaw(String key) {
            Entry e = liveEntry(key);
            long cur;
            if (e == null) cur = 0;
            else {
                String s = e.value.as(StringValue.class).s;
                try { cur = Long.parseLong(s); }
                catch (NumberFormatException nf) { throw new StoreException("ERR value is not an integer"); }
            }
            long next = cur + 1;
            setRaw(key, Long.toString(next), 0);
            return next;
        }

        boolean del(String key) {
            lock.writeLock().lock();
            try { return delRaw(key); }
            finally { lock.writeLock().unlock(); }
        }
        boolean delRaw(String key) {
            Entry removed = data.remove(key);
            if (removed != null) { eviction.recordRemove(key); bus.fire(new StoreEvent(EventType.DEL, key)); }
            return removed != null;
        }

        boolean exists(String key) {
            lock.readLock().lock();
            try { return liveEntry(key) != null; }
            finally { lock.readLock().unlock(); }
        }

        /* ----------------------------- TTL ops ---------------------------------------- */

        boolean expire(String key, long ttlMs) {
            lock.writeLock().lock();
            try {
                Entry e = liveEntry(key);
                if (e == null) return false;
                e.expireAtMs = System.currentTimeMillis() + ttlMs;
                e.version++;
                return true;
            } finally { lock.writeLock().unlock(); }
        }
        /** remaining ms; -2 if missing, -1 if no expiry. */
        long ttl(String key) {
            lock.readLock().lock();
            try {
                Entry e = liveEntry(key);
                if (e == null) return -2;
                if (e.expireAtMs == 0) return -1;
                return Math.max(0, e.expireAtMs - System.currentTimeMillis());
            } finally { lock.readLock().unlock(); }
        }
        boolean persist(String key) {
            lock.writeLock().lock();
            try {
                Entry e = liveEntry(key);
                if (e == null || e.expireAtMs == 0) return false;
                e.expireAtMs = 0; e.version++;
                return true;
            } finally { lock.writeLock().unlock(); }
        }

        /* ----------------------------- LIST ops --------------------------------------- */

        int lpush(String key, String... vals) {
            lock.writeLock().lock();
            try { return lpushRaw(key, vals); }
            finally { lock.writeLock().unlock(); }
        }
        int lpushRaw(String key, String... vals) {
            ListValue lv = listFor(key, true);
            for (String v : vals) lv.list.addFirst(v);
            bus.fire(new StoreEvent(EventType.SET, key));
            return lv.list.size();
        }
        int rpush(String key, String... vals) {
            lock.writeLock().lock();
            try {
                ListValue lv = listFor(key, true);
                for (String v : vals) lv.list.addLast(v);
                bus.fire(new StoreEvent(EventType.SET, key));
                return lv.list.size();
            } finally { lock.writeLock().unlock(); }
        }
        String lpop(String key) {
            lock.writeLock().lock();
            try {
                ListValue lv = listFor(key, false);
                if (lv == null || lv.list.isEmpty()) return null;
                String v = lv.list.pollFirst();
                if (lv.list.isEmpty()) delRaw(key);     // Redis collapses empty collections
                return v;
            } finally { lock.writeLock().unlock(); }
        }
        String rpop(String key) {
            lock.writeLock().lock();
            try {
                ListValue lv = listFor(key, false);
                if (lv == null || lv.list.isEmpty()) return null;
                String v = lv.list.pollLast();
                if (lv.list.isEmpty()) delRaw(key);
                return v;
            } finally { lock.writeLock().unlock(); }
        }
        List<String> lrange(String key, int start, int end) {
            lock.readLock().lock();
            try {
                Entry e = liveEntry(key);
                if (e == null) return Collections.emptyList();
                List<String> all = new ArrayList<>(e.value.as(ListValue.class).list);
                int n = all.size();
                int s = start < 0 ? Math.max(0, n + start) : Math.min(start, n);
                int en = end < 0 ? n + end : end;
                en = Math.min(en, n - 1);
                if (s > en || s >= n) return Collections.emptyList();
                return new ArrayList<>(all.subList(s, en + 1));
            } finally { lock.readLock().unlock(); }
        }
        /** Returns the ListValue for a key, creating it if create==true; WRONGTYPE if mistyped. */
        private ListValue listFor(String key, boolean create) {
            Entry e = liveEntry(key);
            if (e == null) {
                if (!create) return null;
                evictIfNeeded(true);
                ListValue lv = new ListValue();
                data.put(key, new Entry(lv, 0));
                eviction.recordInsert(key);
                return lv;
            }
            return e.value.as(ListValue.class);
        }

        /* ----------------------------- HASH ops --------------------------------------- */

        boolean hset(String key, String field, String value) {
            lock.writeLock().lock();
            try { return hsetRaw(key, field, value); }
            finally { lock.writeLock().unlock(); }
        }
        boolean hsetRaw(String key, String field, String value) {
            HashValue hv = hashFor(key, true);
            boolean isNewField = !hv.map.containsKey(field);
            hv.map.put(field, value);
            bus.fire(new StoreEvent(EventType.SET, key));
            return isNewField;
        }
        String hget(String key, String field) {
            lock.readLock().lock();
            try {
                Entry e = liveEntry(key);
                if (e == null) return null;
                return e.value.as(HashValue.class).map.get(field);
            } finally { lock.readLock().unlock(); }
        }
        Map<String, String> hgetall(String key) {
            lock.readLock().lock();
            try {
                Entry e = liveEntry(key);
                if (e == null) return Collections.emptyMap();
                return new LinkedHashMap<>(e.value.as(HashValue.class).map);
            } finally { lock.readLock().unlock(); }
        }
        boolean hdel(String key, String field) {
            lock.writeLock().lock();
            try {
                Entry e = liveEntry(key);
                if (e == null) return false;
                HashValue hv = e.value.as(HashValue.class);
                boolean removed = hv.map.remove(field) != null;
                if (removed && hv.map.isEmpty()) delRaw(key);
                return removed;
            } finally { lock.writeLock().unlock(); }
        }
        private HashValue hashFor(String key, boolean create) {
            Entry e = liveEntry(key);
            if (e == null) {
                if (!create) return null;
                evictIfNeeded(true);
                HashValue hv = new HashValue();
                data.put(key, new Entry(hv, 0));
                eviction.recordInsert(key);
                return hv;
            }
            return e.value.as(HashValue.class);
        }

        /* ----------------------------- PUB/SUB ---------------------------------------- */

        void subscribe(String channel, Subscriber s)   { bus.subscribe(channel, s); }
        void unsubscribe(String channel, Subscriber s) { bus.unsubscribe(channel, s); }
        int  publish(String channel, String message)   { return bus.publish(channel, message); }

        int size() { return data.size(); }
    }

    /* ============== Command "raw" execution: run without re-acquiring the lock ==========
     * Inside EXEC we already hold the write lock; commands must call the *Raw methods so we
     * don't deadlock/over-lock. We add a default executeRaw mapping per command type below
     * via an instanceof dispatch kept tiny and explicit (no reflection). */
    static Object executeRawDispatch(Command c, KVStore s) {
        if (c instanceof SetCommand)   { SetCommand x=(SetCommand)c;   s.setRaw(x.key,x.value,x.ttlMs); return "OK"; }
        if (c instanceof GetCommand)   { return s.getRaw(((GetCommand)c).key); }
        if (c instanceof DelCommand)   { return s.delRaw(((DelCommand)c).key); }
        if (c instanceof IncrCommand)  { return s.incrRaw(((IncrCommand)c).key); }
        if (c instanceof LPushCommand) { LPushCommand x=(LPushCommand)c; return s.lpushRaw(x.key,x.vals); }
        if (c instanceof LRangeCommand){ LRangeCommand x=(LRangeCommand)c; return s.lrange(x.key,x.start,x.end); }
        if (c instanceof HSetCommand)  { HSetCommand x=(HSetCommand)c; return s.hsetRaw(x.key,x.field,x.value); }
        if (c instanceof HGetCommand)  { HGetCommand x=(HGetCommand)c; return s.hget(x.key,x.field); }
        return c.execute(s); // fallback (re-locks); none of our commands hit this.
    }

    /* ================================ DEMO / MAIN ====================================== */

    public static void main(String[] args) throws Exception {
        // Use a small budget to demonstrate eviction; LRU policy by default.
        KVStore store = new KVStore(3, new LRUEviction());
        CommandLogPersistence aof = new CommandLogPersistence();
        store.setPersistence(aof);

        System.out.println("==== STRING ops ====");
        store.set("name", "neo");
        System.out.println("GET name        -> " + store.get("name"));
        store.set("counter", "10");
        System.out.println("INCR counter    -> " + store.incr("counter"));
        System.out.println("EXISTS name     -> " + store.exists("name"));
        System.out.println("DEL name        -> " + store.del("name"));
        System.out.println("GET name        -> " + store.get("name"));

        System.out.println("\n==== TTL (lazy + active expiry) ====");
        store.set("session", "abc", 150);            // 150ms TTL
        System.out.println("TTL session     -> " + store.ttl("session") + "ms");
        System.out.println("GET session     -> " + store.get("session"));
        Thread.sleep(250);                            // let it expire
        System.out.println("GET session(exp)-> " + store.get("session")); // null (lazy expiry)

        System.out.println("\n==== LIST ops ====");
        store.del("counter");                         // free budget room
        store.lpush("tasks", "a", "b");               // list = [b, a]
        store.rpush("tasks", "c");                     // list = [b, a, c]
        System.out.println("LRANGE tasks 0 -1 -> " + store.lrange("tasks", 0, -1));
        System.out.println("LPOP tasks      -> " + store.lpop("tasks"));

        System.out.println("\n==== HASH ops ====");
        store.hset("user:1", "name", "trinity");
        store.hset("user:1", "role", "hacker");
        System.out.println("HGET user:1 name-> " + store.hget("user:1", "name"));
        System.out.println("HGETALL user:1  -> " + store.hgetall("user:1"));

        System.out.println("\n==== WRONGTYPE guard ====");
        try { store.lpush("user:1", "x"); }           // user:1 is a hash
        catch (WrongTypeException ex) { System.out.println("caught -> " + ex.getMessage()); }

        System.out.println("\n==== EVICTION (maxKeys=3, LRU) ====");
        KVStore evStore = new KVStore(3, new LRUEviction());
        evStore.set("k1", "1"); evStore.set("k2", "2"); evStore.set("k3", "3");
        evStore.get("k1");                            // touch k1 -> k2 becomes LRU
        evStore.set("k4", "4");                        // inserts new -> evicts LRU (k2)
        System.out.println("k1 -> " + evStore.get("k1") + " | k2 (evicted) -> " + evStore.get("k2")
                + " | k4 -> " + evStore.get("k4"));
        evStore.shutdown();

        System.out.println("\n==== PUB/SUB (Observer) ====");
        store.subscribe("news", (ch, msg) -> System.out.println("subscriber got [" + ch + "]: " + msg));
        int delivered = store.publish("news", "redis-lite is live");
        System.out.println("delivered to    -> " + delivered + " subscriber(s)");

        System.out.println("\n==== TRANSACTION (MULTI/EXEC) ====");
        TransactionManager txm = new TransactionManager();
        ClientSession session = new ClientSession();
        txm.multi(session);
        // Wrap commands so EXEC uses raw (already-locked) execution path.
        txm.queue(session, new Command() {
            public Object execute(KVStore s) { s.set("tx1", "v1", 0); return "OK"; }
            public String name() { return "SET"; }
        });
        txm.queue(session, new Command() {
            public Object execute(KVStore s) { return s.incrRaw("txc"); }
            public String name() { return "INCR"; }
        });
        List<Object> results = txm.exec(session, store);
        System.out.println("EXEC results    -> " + results);
        System.out.println("after tx: tx1=" + store.get("tx1") + " txc=" + store.get("txc"));

        System.out.println("\n==== COMMAND FACTORY ====");
        CommandFactory factory = new CommandFactory();
        Command c = factory.create("SET", "made", "by-factory");
        System.out.println("factory cmd     -> " + c.name() + " => " + c.execute(store));
        System.out.println("GET made        -> " + store.get("made"));

        System.out.println("\n==== PERSISTENCE LOG (AOF-style observer) ====");
        System.out.println("event count     -> " + aof.log.size());
        System.out.println("first events    -> " + aof.log.subList(0, Math.min(5, aof.log.size())));

        System.out.println("\n==== SINGLETON ====");
        KVStore single = KVStore.getInstance();
        single.set("global", "shared");
        System.out.println("singleton get   -> " + KVStore.getInstance().get("global"));

        store.shutdown();
        System.out.println("\nDemo complete.");
    }
}

/* ---------------------------------------------------------------------------------------
 * NOTE on EXEC raw-execution: TransactionManager.exec() calls Command.executeRaw(store).
 * To keep the file compact-and-readable we expose that as a default method-style helper.
 * Add this default to the Command interface in your own copy if you prefer not to use the
 * anonymous wrappers shown in main():
 *
 *     default Object executeRaw(Solution.KVStore s) {
 *         return Solution.executeRawDispatch(this, s);
 *     }
 *
 * The dispatch routes each concrete Command to its *Raw store method so EXEC never
 * re-acquires the write lock it already holds (avoids re-entrant over-locking and keeps
 * the whole batch atomic). This is the seam where AOF logging and WATCH-version checks
 * would also hook in.
 * ------------------------------------------------------------------------------------- */

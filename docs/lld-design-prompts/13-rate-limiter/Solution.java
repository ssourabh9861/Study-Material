/* =============================================================================
 * Rate Limiter — single-file REVIEW / REVISION artifact (pure JDK).
 *
 * This file is meant to be READ and revised before an LLD / machine-coding round.
 * It is complete and correct-by-inspection; you do NOT need to compile or run it.
 *
 * -----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -----------------------------------------------------------------------------
 * Functional:
 *   1. In-process library or DISTRIBUTED across many servers (Redis)? (biggest fork)
 *   2. Unit of limiting: per user / per API key / per IP / per (user,endpoint) / global?
 *      Multiple tiers at once (per-user AND global)?
 *   3. Return just a boolean, or also remaining quota / retry-after / reset (for headers)?
 *   4. Which algorithm(s)? Token bucket (burst), leaky bucket (smooth), fixed/sliding
 *      window? Must the algorithm be runtime-pluggable?
 *   5. Static config or RUNTIME-changeable limits (hot reload, per-tenant overrides)?
 * Non-functional:
 *   6. Throughput (QPS) and number of distinct keys (millions => need eviction/TTL)?
 *   7. Latency budget on the hot path (must be O(1), lock-light)?
 *   8. On backend failure: FAIL-OPEN (allow) or FAIL-CLOSED (reject)?
 *   9. Approximate (sliding-counter) acceptable or must be EXACT (sliding-log)?
 *  10. Monotonic clock available? Must behaviour be testable with a virtual clock?
 * Scope:
 *  11. Out of scope: HTTP wiring, analytics persistence, billing on overage?
 *  12. Need idle-key eviction (TTL / LRU)?
 *  13. Multiple rules per key evaluated together ("100/s AND 5000/h")?
 *
 * -----------------------------------------------------------------------------
 * DESIGN PATTERNS DEMONSTRATED
 *   Strategy   -> RateLimitAlgorithm (token/leaky/fixed/sliding-log/sliding-counter)
 *   Factory    -> AlgorithmFactory.create(Rule)
 *   Composite  -> CompositeRateLimiter (ANDs multiple limiters)
 *   Decorator  -> MeteredRateLimiter (metrics around any RateLimiter)
 *   Builder    -> Rule.builder()
 *   DI         -> Clock / RuleProvider / DistributedStore injected
 * THREAD-SAFETY: per-key lock striping over ConcurrentHashMap; monotonic nanoTime.
 * ========================================================================== */

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/* ============================== Enums ==================================== */

enum AlgorithmType {
    TOKEN_BUCKET, LEAKY_BUCKET, FIXED_WINDOW, SLIDING_LOG, SLIDING_COUNTER
}

/** Policy when a distributed backend is unavailable. */
enum FailPolicy { FAIL_OPEN, FAIL_CLOSED }

/* ============================== Clock (DI) ============================== */

/** Abstracts time so elapsed math is monotonic and tests are deterministic. */
interface Clock {
    long nowNanos();    // monotonic, for elapsed-time math
    long wallMillis();  // wall clock, only for human-readable reset times
}

/** Production clock: nanoTime() is monotonic; millis() only for reset display. */
final class SystemClock implements Clock {
    static final SystemClock INSTANCE = new SystemClock(); // stateless singleton
    public long nowNanos() { return System.nanoTime(); }
    public long wallMillis() { return System.currentTimeMillis(); }
}

/** Test clock: advance time by hand; no Thread.sleep needed in tests. */
final class VirtualClock implements Clock {
    private long nanos;
    private long wallBase = 1_000_000_000_000L; // arbitrary epoch base for demo
    VirtualClock(long startNanos) { this.nanos = startNanos; }
    public synchronized long nowNanos() { return nanos; }
    public synchronized long wallMillis() { return wallBase + nanos / 1_000_000L; }
    synchronized void advanceMillis(long ms) { nanos += ms * 1_000_000L; }
    synchronized void advanceNanos(long n) { nanos += n; }
}

/* ============================== Value objects =========================== */

/** Immutable config rule for a key. Built via Builder (many optional params). */
final class Rule {
    final AlgorithmType type;
    final long limit;        // permits allowed per window (or refill count)
    final long windowNanos;  // window / refill period in nanos
    final long capacity;     // bucket capacity (max burst) for bucket algorithms
    final FailPolicy failPolicy;

    private Rule(Builder b) {
        this.type = b.type;
        this.limit = b.limit;
        this.windowNanos = b.windowNanos;
        this.capacity = b.capacity > 0 ? b.capacity : b.limit; // default cap = limit
        this.failPolicy = b.failPolicy;
    }

    /** refill/leak rate in permits-per-nanosecond. */
    double ratePerNano() { return (double) limit / (double) windowNanos; }

    static Builder builder() { return new Builder(); }

    static final class Builder {
        private AlgorithmType type = AlgorithmType.TOKEN_BUCKET;
        private long limit = 10;
        private long windowNanos = 1_000_000_000L; // 1 second
        private long capacity = 0;                  // 0 => default to limit
        private FailPolicy failPolicy = FailPolicy.FAIL_OPEN;

        Builder type(AlgorithmType t) { this.type = t; return this; }
        Builder limit(long l) { this.limit = l; return this; }
        Builder perSeconds(long secs) { this.windowNanos = secs * 1_000_000_000L; return this; }
        Builder perMillis(long ms) { this.windowNanos = ms * 1_000_000L; return this; }
        Builder windowNanos(long n) { this.windowNanos = n; return this; }
        Builder capacity(long c) { this.capacity = c; return this; }
        Builder failPolicy(FailPolicy p) { this.failPolicy = p; return this; }
        Rule build() { return new Rule(this); }
    }

    @Override public String toString() {
        return type + "(" + limit + " per " + (windowNanos / 1_000_000L) + "ms, cap=" + capacity + ")";
    }
}

/** Immutable outcome of a single decision. Carries header-friendly fields. */
final class RateLimitResult {
    final boolean allowed;
    final long remaining;        // permits left in the current window/bucket
    final long limit;            // configured limit (for X-RateLimit-Limit)
    final long retryAfterMillis; // 0 if allowed; else how long to wait
    final long resetEpochMillis; // when the window/bucket fully resets/refills

    RateLimitResult(boolean allowed, long remaining, long limit,
                    long retryAfterMillis, long resetEpochMillis) {
        this.allowed = allowed;
        this.remaining = Math.max(0, remaining);
        this.limit = limit;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
        this.resetEpochMillis = resetEpochMillis;
    }

    @Override public String toString() {
        return (allowed ? "ALLOW" : "DENY ")
                + " remaining=" + remaining + "/" + limit
                + (allowed ? "" : " retryAfterMs=" + retryAfterMillis);
    }
}

/* ============================== KeyState =============================== */

/**
 * Per-key mutable state guarded by its OWN lock (lock striping).
 * Different keys never contend; only same-key requests serialize.
 * 'algoData' holds algorithm-specific fields (cast inside the algorithm).
 */
final class KeyState {
    final ReentrantLock lock = new ReentrantLock();
    Object algoData;
    volatile long lastAccessNanos; // volatile: read by eviction without the lock
    KeyState(Object algoData, long nowNanos) {
        this.algoData = algoData;
        this.lastAccessNanos = nowNanos;
    }
}

/* ========================= Strategy interface ========================= */

/** Strategy: one algorithm's decision logic over a KeyState. */
interface RateLimitAlgorithm {
    /** Build the initial algorithm-specific state object for a fresh key. */
    Object newState(Rule rule, Clock clock);

    /**
     * Attempt to consume 'permits'. MUST be called while holding state.lock.
     * Returns the decision; mutates state.algoData in place.
     */
    RateLimitResult tryConsume(KeyState state, Rule rule, int permits, Clock clock);
}

/* ===================== Concrete algorithms (Strategy) ================== */

/**
 * TOKEN BUCKET — allows bursts up to capacity, refills at a steady rate.
 * The default: tolerant of spikes, smooth average rate.
 */
final class TokenBucket implements RateLimitAlgorithm {
    static final class Data {
        double tokens;
        long lastRefillNanos;
        Data(double tokens, long now) { this.tokens = tokens; this.lastRefillNanos = now; }
    }

    public Object newState(Rule rule, Clock clock) {
        // Start full so a cold key isn't unfairly throttled.
        return new Data(rule.capacity, clock.nowNanos());
    }

    public RateLimitResult tryConsume(KeyState state, Rule rule, int permits, Clock clock) {
        Data d = (Data) state.algoData;
        long now = clock.nowNanos();
        long elapsed = Math.max(0, now - d.lastRefillNanos); // monotonic: never negative
        // Refill = elapsed * rate, capped at capacity.
        d.tokens = Math.min(rule.capacity, d.tokens + elapsed * rule.ratePerNano());
        d.lastRefillNanos = now;

        if (permits > rule.capacity) {
            // Can never be satisfied — fail fast rather than loop forever.
            return new RateLimitResult(false, (long) d.tokens, rule.capacity, 0, clock.wallMillis());
        }
        if (d.tokens >= permits) {
            d.tokens -= permits;
            long resetMs = clock.wallMillis()
                    + (long) ((rule.capacity - d.tokens) / rule.ratePerNano() / 1_000_000.0);
            return new RateLimitResult(true, (long) d.tokens, rule.capacity, 0, resetMs);
        }
        double needed = permits - d.tokens;
        long retryMs = (long) Math.ceil(needed / rule.ratePerNano() / 1_000_000.0);
        return new RateLimitResult(false, (long) d.tokens, rule.capacity, retryMs,
                clock.wallMillis() + retryMs);
    }
}

/**
 * LEAKY BUCKET — output leaks at a constant rate; requests "fill" the bucket.
 * Smooths bursts to a steady rate (no burst beyond capacity). Modeled as a
 * counter that drains over time (queue-as-water view).
 */
final class LeakyBucket implements RateLimitAlgorithm {
    static final class Data {
        double water;          // current fill (queued work)
        long lastLeakNanos;
        Data(long now) { this.water = 0; this.lastLeakNanos = now; }
    }

    public Object newState(Rule rule, Clock clock) { return new Data(clock.nowNanos()); }

    public RateLimitResult tryConsume(KeyState state, Rule rule, int permits, Clock clock) {
        Data d = (Data) state.algoData;
        long now = clock.nowNanos();
        long elapsed = Math.max(0, now - d.lastLeakNanos);
        d.water = Math.max(0, d.water - elapsed * rule.ratePerNano()); // leak out
        d.lastLeakNanos = now;

        if (d.water + permits <= rule.capacity) {
            d.water += permits;
            long remaining = (long) (rule.capacity - d.water);
            return new RateLimitResult(true, remaining, rule.capacity, 0, clock.wallMillis());
        }
        double overflow = (d.water + permits) - rule.capacity;
        long retryMs = (long) Math.ceil(overflow / rule.ratePerNano() / 1_000_000.0);
        long remaining = (long) (rule.capacity - d.water);
        return new RateLimitResult(false, remaining, rule.capacity, retryMs,
                clock.wallMillis() + retryMs);
    }
}

/**
 * FIXED WINDOW COUNTER — simplest; counts per fixed window. KNOWN WEAKNESS:
 * up to 2x limit can pass across a window boundary.
 */
final class FixedWindow implements RateLimitAlgorithm {
    static final class Data {
        long windowStartNanos;
        long count;
        Data(long now) { this.windowStartNanos = now; this.count = 0; }
    }

    public Object newState(Rule rule, Clock clock) { return new Data(clock.nowNanos()); }

    public RateLimitResult tryConsume(KeyState state, Rule rule, int permits, Clock clock) {
        Data d = (Data) state.algoData;
        long now = clock.nowNanos();
        if (now - d.windowStartNanos >= rule.windowNanos) {
            // New window: align to the boundary the elapsed windows landed in.
            long windowsPassed = (now - d.windowStartNanos) / rule.windowNanos;
            d.windowStartNanos += windowsPassed * rule.windowNanos;
            d.count = 0;
        }
        long resetMs = clock.wallMillis()
                + (d.windowStartNanos + rule.windowNanos - now) / 1_000_000L;
        if (d.count + permits <= rule.limit) {
            d.count += permits;
            return new RateLimitResult(true, rule.limit - d.count, rule.limit, 0, resetMs);
        }
        long retryMs = (d.windowStartNanos + rule.windowNanos - now) / 1_000_000L;
        return new RateLimitResult(false, rule.limit - d.count, rule.limit, retryMs, resetMs);
    }
}

/**
 * SLIDING WINDOW LOG — exact: store a timestamp per permit; evict old ones.
 * Accurate but O(N) memory in the window. Use when exactness matters.
 */
final class SlidingWindowLog implements RateLimitAlgorithm {
    static final class Data { final Deque<Long> stamps = new ArrayDeque<>(); }

    public Object newState(Rule rule, Clock clock) { return new Data(); }

    public RateLimitResult tryConsume(KeyState state, Rule rule, int permits, Clock clock) {
        Data d = (Data) state.algoData;
        long now = clock.nowNanos();
        long cutoff = now - rule.windowNanos;
        while (!d.stamps.isEmpty() && d.stamps.peekFirst() <= cutoff) {
            d.stamps.pollFirst(); // evict timestamps outside the window
        }
        if (d.stamps.size() + permits <= rule.limit) {
            for (int i = 0; i < permits; i++) d.stamps.addLast(now);
            long remaining = rule.limit - d.stamps.size();
            long resetMs = clock.wallMillis()
                    + (d.stamps.peekFirst() + rule.windowNanos - now) / 1_000_000L;
            return new RateLimitResult(true, remaining, rule.limit, 0, resetMs);
        }
        long oldest = d.stamps.peekFirst();
        long retryMs = (oldest + rule.windowNanos - now) / 1_000_000L;
        return new RateLimitResult(false, rule.limit - d.stamps.size(), rule.limit,
                retryMs, clock.wallMillis() + retryMs);
    }
}

/**
 * SLIDING WINDOW COUNTER — approximate, O(1): weight the previous window's count
 * by how far we are into the current window. Fixes the fixed-window boundary
 * burst cheaply. The common production default for accuracy-vs-cost.
 */
final class SlidingWindowCounter implements RateLimitAlgorithm {
    static final class Data {
        long currentWindowStart;
        long currentCount;
        long previousCount;
        Data(long now) { this.currentWindowStart = now; }
    }

    public Object newState(Rule rule, Clock clock) { return new Data(clock.nowNanos()); }

    public RateLimitResult tryConsume(KeyState state, Rule rule, int permits, Clock clock) {
        Data d = (Data) state.algoData;
        long now = clock.nowNanos();
        long elapsed = now - d.currentWindowStart;

        if (elapsed >= 2 * rule.windowNanos) {
            d.previousCount = 0; d.currentCount = 0;
            d.currentWindowStart = now; elapsed = 0;
        } else if (elapsed >= rule.windowNanos) {
            d.previousCount = d.currentCount;
            d.currentCount = 0;
            d.currentWindowStart += rule.windowNanos;
            elapsed -= rule.windowNanos;
        }
        // Fraction of the previous window still overlapping the sliding window.
        double prevWeight = 1.0 - ((double) elapsed / rule.windowNanos);
        double estimate = d.previousCount * prevWeight + d.currentCount;

        long resetMs = clock.wallMillis()
                + (d.currentWindowStart + rule.windowNanos - now) / 1_000_000L;
        if (estimate + permits <= rule.limit) {
            d.currentCount += permits;
            long remaining = (long) Math.floor(rule.limit - (estimate + permits));
            return new RateLimitResult(true, remaining, rule.limit, 0, resetMs);
        }
        long retryMs = (d.currentWindowStart + rule.windowNanos - now) / 1_000_000L;
        return new RateLimitResult(false, (long) Math.floor(rule.limit - estimate),
                rule.limit, retryMs, resetMs);
    }
}

/* ============================== Factory =============================== */

/** Factory: maps a Rule's AlgorithmType to its Strategy. OCP-friendly. */
final class AlgorithmFactory {
    // Strategies are stateless => reuse single instances.
    private final Map<AlgorithmType, RateLimitAlgorithm> registry = new ConcurrentHashMap<>();

    AlgorithmFactory() {
        registry.put(AlgorithmType.TOKEN_BUCKET, new TokenBucket());
        registry.put(AlgorithmType.LEAKY_BUCKET, new LeakyBucket());
        registry.put(AlgorithmType.FIXED_WINDOW, new FixedWindow());
        registry.put(AlgorithmType.SLIDING_LOG, new SlidingWindowLog());
        registry.put(AlgorithmType.SLIDING_COUNTER, new SlidingWindowCounter());
    }

    /** Register a new algorithm without touching existing code (OCP). */
    void register(AlgorithmType type, RateLimitAlgorithm algo) { registry.put(type, algo); }

    RateLimitAlgorithm create(Rule rule) {
        RateLimitAlgorithm a = registry.get(rule.type);
        if (a == null) throw new IllegalArgumentException("No algorithm for " + rule.type);
        return a;
    }
}

/* ============================ RuleProvider (DI) ======================= */

/** Resolves a key -> Rule (default + overrides). Swap impl for hot reload. */
interface RuleProvider {
    Rule ruleFor(String key);
}

/** Default + per-key overrides; overrides are mutable for runtime reconfig. */
final class InMemoryRuleProvider implements RuleProvider {
    private volatile Rule defaultRule;
    private final Map<String, Rule> overrides = new ConcurrentHashMap<>();

    InMemoryRuleProvider(Rule defaultRule) { this.defaultRule = defaultRule; }

    public Rule ruleFor(String key) {
        Rule r = overrides.get(key);
        return r != null ? r : defaultRule;
    }

    /** Hot-change a key's rule at runtime (per-tenant / tier override). */
    void setOverride(String key, Rule rule) { overrides.put(key, rule); }
    void setDefault(Rule rule) { this.defaultRule = rule; }
}

/* ========================= RateLimiter interface ===================== */

interface RateLimiter {
    RateLimitResult tryAcquire(String key, int permits);
    default RateLimitResult tryAcquire(String key) { return tryAcquire(key, 1); }
}

/* ====================== StandardRateLimiter (core) ================== */

/**
 * In-process limiter. Thread-safe via per-key lock striping over a
 * ConcurrentHashMap. Manages idle-key eviction by TTL.
 */
final class StandardRateLimiter implements RateLimiter {
    private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();
    private final RuleProvider ruleProvider;
    private final AlgorithmFactory factory;
    private final Clock clock;
    private final long idleEvictNanos;
    // Cheap, occasional prune trigger (avoid a thread for this revision artifact).
    private long lastPruneNanos;
    private static final long PRUNE_INTERVAL_NANOS = 5_000_000_000L; // 5s

    StandardRateLimiter(RuleProvider ruleProvider, AlgorithmFactory factory,
                        Clock clock, long idleEvictNanos) {
        this.ruleProvider = ruleProvider;
        this.factory = factory;
        this.clock = clock;
        this.idleEvictNanos = idleEvictNanos;
        this.lastPruneNanos = clock.nowNanos();
    }

    public RateLimitResult tryAcquire(String key, int permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be > 0");

        Rule rule = ruleProvider.ruleFor(key);
        RateLimitAlgorithm algo = factory.create(rule);

        // computeIfAbsent is atomic: racing creators share one KeyState.
        KeyState st = states.computeIfAbsent(key,
                k -> new KeyState(algo.newState(rule, clock), clock.nowNanos()));

        st.lock.lock(); // per-key lock: minimal critical section, distinct keys never contend
        try {
            RateLimitResult res = algo.tryConsume(st, rule, permits, clock);
            st.lastAccessNanos = clock.nowNanos();
            return res;
        } finally {
            st.lock.unlock();
        }
        // (prune is invoked opportunistically by callers / a scheduler; see maybePrune)
    }

    /** Opportunistic eviction of idle keys to bound memory. Safe to call anytime. */
    void maybePrune() {
        long now = clock.nowNanos();
        if (now - lastPruneNanos < PRUNE_INTERVAL_NANOS) return;
        lastPruneNanos = now;
        for (Map.Entry<String, KeyState> e : states.entrySet()) {
            KeyState st = e.getValue();
            if (now - st.lastAccessNanos > idleEvictNanos) {
                // Remove only if still idle and not currently locked (best-effort).
                if (st.lock.tryLock()) {
                    try {
                        if (now - st.lastAccessNanos > idleEvictNanos) {
                            states.remove(e.getKey(), st);
                        }
                    } finally { st.lock.unlock(); }
                }
            }
        }
    }

    int trackedKeys() { return states.size(); }
}

/* ===================== CompositeRateLimiter (Composite) ============== */

/**
 * ANDs several limiters: denies if ANY denies. Lets "one rule" and "many rules"
 * be treated uniformly behind RateLimiter (e.g. "10/s AND 100/min").
 */
final class CompositeRateLimiter implements RateLimiter {
    private final List<RateLimiter> limiters;

    CompositeRateLimiter(List<RateLimiter> limiters) {
        this.limiters = new ArrayList<>(limiters);
    }

    public RateLimitResult tryAcquire(String key, int permits) {
        RateLimitResult worst = null; // most-restrictive denial, or min-remaining allow
        boolean allowed = true;
        for (RateLimiter l : limiters) {
            RateLimitResult r = l.tryAcquire(key, permits);
            if (!r.allowed) {
                allowed = false;
                if (worst == null || r.retryAfterMillis > worst.retryAfterMillis) worst = r;
            } else if (allowed && (worst == null || r.remaining < worst.remaining)) {
                worst = r; // track tightest remaining while still all-allowed
            }
        }
        if (worst == null) return new RateLimitResult(true, 0, 0, 0, 0);
        return allowed
                ? new RateLimitResult(true, worst.remaining, worst.limit, 0, worst.resetEpochMillis)
                : worst;
        // NOTE: a strict implementation would refund permits already consumed by
        // earlier-allowed limiters on a later denial; omitted for revision clarity.
    }
}

/* ======================= MeteredRateLimiter (Decorator) ============= */

/** Decorator: adds allow/deny metrics around ANY RateLimiter. SRP + OCP. */
final class MeteredRateLimiter implements RateLimiter {
    private final RateLimiter delegate;
    private final LongAdder allowed = new LongAdder();
    private final LongAdder denied = new LongAdder();

    MeteredRateLimiter(RateLimiter delegate) { this.delegate = delegate; }

    public RateLimitResult tryAcquire(String key, int permits) {
        RateLimitResult r = delegate.tryAcquire(key, permits);
        if (r.allowed) allowed.increment(); else denied.increment();
        return r;
    }

    long allowedCount() { return allowed.sum(); }
    long deniedCount() { return denied.sum(); }
}

/* ================= Distributed extension (interfaces/stub) =========== */

/**
 * Abstracts a shared backend (e.g. Redis). The check+decrement MUST be atomic
 * at the store (Redis Lua / INCR+EXPIRE) — never read-then-write from the app.
 */
interface DistributedStore {
    RateLimitResult tryConsume(String key, Rule rule, int permits) throws Exception;
}

/**
 * Distributed limiter — same RateLimiter contract, so callers don't change
 * (Strategy + DIP + LSP). On backend failure, consults the FailPolicy.
 */
final class DistributedRateLimiter implements RateLimiter {
    private final DistributedStore store;
    private final RuleProvider ruleProvider;
    private final Clock clock;

    DistributedRateLimiter(DistributedStore store, RuleProvider ruleProvider, Clock clock) {
        this.store = store;
        this.ruleProvider = ruleProvider;
        this.clock = clock;
    }

    public RateLimitResult tryAcquire(String key, int permits) {
        Rule rule = ruleProvider.ruleFor(key);
        try {
            return store.tryConsume(key, rule, permits);
        } catch (Exception backendDown) {
            // Graceful degradation: policy decides availability vs. strictness.
            if (rule.failPolicy == FailPolicy.FAIL_OPEN) {
                return new RateLimitResult(true, rule.limit, rule.limit, 0, clock.wallMillis());
            }
            return new RateLimitResult(false, 0, rule.limit, rule.windowNanos / 1_000_000L,
                    clock.wallMillis());
        }
    }
}

/** Demo in-memory stand-in for Redis (atomic via a single lock — illustrative). */
final class FakeRedisStore implements DistributedStore {
    private final StandardRateLimiter inner; // reuse local logic to simulate Lua atomicity
    FakeRedisStore(StandardRateLimiter inner) { this.inner = inner; }
    public RateLimitResult tryConsume(String key, Rule rule, int permits) {
        return inner.tryAcquire(key, permits);
    }
}

/* ================================ Demo ================================ */

public class Solution {

    private static void hit(RateLimiter rl, String key, int n) {
        for (int i = 1; i <= n; i++) {
            RateLimitResult r = rl.tryAcquire(key);
            System.out.println("  req#" + i + " " + key + " -> " + r);
        }
    }

    public static void main(String[] args) throws Exception {
        AlgorithmFactory factory = new AlgorithmFactory();
        VirtualClock clock = new VirtualClock(0L); // deterministic time for the demo

        System.out.println("=== 1) TOKEN BUCKET: 5 capacity, refill 5/sec ===");
        Rule tb = Rule.builder().type(AlgorithmType.TOKEN_BUCKET)
                .limit(5).perSeconds(1).capacity(5).build();
        InMemoryRuleProvider rp1 = new InMemoryRuleProvider(tb);
        StandardRateLimiter limiter = new StandardRateLimiter(
                rp1, factory, clock, 60_000_000_000L /* 60s TTL */);
        hit(limiter, "user:1", 7); // first 5 allowed (burst), then 2 denied
        System.out.println("  -- advance 1s (full refill) --");
        clock.advanceMillis(1000);
        hit(limiter, "user:1", 2); // allowed again after refill

        System.out.println("\n=== 2) PER-KEY OVERRIDE (premium gets 10) ===");
        rp1.setOverride("user:premium", Rule.builder()
                .type(AlgorithmType.TOKEN_BUCKET).limit(10).perSeconds(1).capacity(10).build());
        hit(limiter, "user:premium", 11); // 10 allowed, 1 denied

        System.out.println("\n=== 3) FIXED WINDOW: 3 per 1s (boundary behaviour) ===");
        Rule fw = Rule.builder().type(AlgorithmType.FIXED_WINDOW).limit(3).perSeconds(1).build();
        StandardRateLimiter fwLimiter = new StandardRateLimiter(
                new InMemoryRuleProvider(fw), factory, clock, 60_000_000_000L);
        hit(fwLimiter, "ip:9", 4);          // 3 allowed, 1 denied
        clock.advanceMillis(1000);          // next window
        System.out.println("  -- next window --");
        hit(fwLimiter, "ip:9", 2);          // allowed again

        System.out.println("\n=== 4) SLIDING WINDOW COUNTER: 4 per 1s ===");
        Rule sw = Rule.builder().type(AlgorithmType.SLIDING_COUNTER).limit(4).perSeconds(1).build();
        StandardRateLimiter swLimiter = new StandardRateLimiter(
                new InMemoryRuleProvider(sw), factory, clock, 60_000_000_000L);
        hit(swLimiter, "k", 5);             // 4 allowed, 1 denied
        clock.advanceMillis(500);           // half a window -> partial smoothing
        System.out.println("  -- +500ms (sliding estimate) --");
        hit(swLimiter, "k", 3);

        System.out.println("\n=== 5) COMPOSITE: 3/sec AND 5/2sec (most-restrictive wins) ===");
        VirtualClock c2 = new VirtualClock(0L);
        RateLimiter perSec = new StandardRateLimiter(new InMemoryRuleProvider(
                Rule.builder().type(AlgorithmType.TOKEN_BUCKET).limit(3).perSeconds(1).capacity(3).build()),
                factory, c2, 60_000_000_000L);
        RateLimiter per2Sec = new StandardRateLimiter(new InMemoryRuleProvider(
                Rule.builder().type(AlgorithmType.TOKEN_BUCKET).limit(5).perSeconds(2).capacity(5).build()),
                factory, c2, 60_000_000_000L);
        RateLimiter composite = new CompositeRateLimiter(java.util.Arrays.asList(perSec, per2Sec));
        hit(composite, "u", 4); // capped at 3 by the per-second rule

        System.out.println("\n=== 6) DECORATOR: metrics around the token-bucket limiter ===");
        MeteredRateLimiter metered = new MeteredRateLimiter(limiter);
        for (int i = 0; i < 6; i++) metered.tryAcquire("metered:user");
        System.out.println("  allowed=" + metered.allowedCount() + " denied=" + metered.deniedCount());

        System.out.println("\n=== 7) DISTRIBUTED (same interface; FAIL policy on backend down) ===");
        StandardRateLimiter backing = new StandardRateLimiter(new InMemoryRuleProvider(
                Rule.builder().type(AlgorithmType.TOKEN_BUCKET).limit(2).perSeconds(1).capacity(2)
                        .failPolicy(FailPolicy.FAIL_OPEN).build()), factory, clock, 60_000_000_000L);
        DistributedStore store = new FakeRedisStore(backing);
        RateLimiter dist = new DistributedRateLimiter(store,
                new InMemoryRuleProvider(Rule.builder().limit(2).perSeconds(1).capacity(2).build()), clock);
        hit(dist, "svc:A", 3); // 2 allowed, 1 denied (store enforces)

        // Failing store demonstrates FAIL_OPEN graceful degradation.
        DistributedStore broken = (k, r, p) -> { throw new RuntimeException("redis down"); };
        RateLimiter degrade = new DistributedRateLimiter(broken, new InMemoryRuleProvider(
                Rule.builder().limit(2).perSeconds(1).failPolicy(FailPolicy.FAIL_OPEN).build()), clock);
        System.out.println("  backend down, FAIL_OPEN -> " + degrade.tryAcquire("svc:A"));

        System.out.println("\n=== 8) CONCURRENCY: 8 threads x 50 calls, limit 100/sec ===");
        VirtualClock c3 = new VirtualClock(0L);
        StandardRateLimiter concLimiter = new StandardRateLimiter(new InMemoryRuleProvider(
                Rule.builder().type(AlgorithmType.TOKEN_BUCKET).limit(100).perSeconds(1).capacity(100).build()),
                factory, c3, 60_000_000_000L);
        final java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] ts = new Thread[8];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++)
                    if (concLimiter.tryAcquire("hot").allowed) allowed.incrementAndGet();
            });
        }
        for (Thread th : ts) th.start();
        for (Thread th : ts) th.join();
        System.out.println("  400 attempts on one key, capacity 100 -> allowed=" + allowed.get()
                + " (expected exactly 100; thread-safe via per-key lock)");

        System.out.println("\nDONE.");
    }
}

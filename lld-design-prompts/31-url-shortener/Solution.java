/* =====================================================================================
 * URL SHORTENER — Single-file LLD review / revision artifact.
 *
 * This file is for READING and REVISION before an interview. It is complete and
 * syntactically self-contained (pure JDK, no external libs). You do NOT need to
 * compile or run it; the main() exists only to illustrate usage.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional:
 *   1. Core ops: shorten(longUrl) + resolve(shortUrl). Also delete / update / list?
 *   2. Custom aliases (user picks the code)? Behaviour on clash — reject or version?
 *   3. Idempotent shorten? Same long URL -> same code (dedup) or a fresh code each time?
 *   4. Expiry/TTL? By duration, absolute date, click-count, or never? Behaviour after expiry?
 *   5. Analytics: total click counter only, or per-time/referrer/geo?
 *   6. Are codes reused after expiry/deletion, or permanently burned?
 * Non-functional:
 *   7. Scale: shorten vs resolve QPS, total URLs over lifetime (drives code length).
 *   8. Latency: resolve is the hot path (10-100x reads); shorten may be slower.
 *   9. Persistence: in-memory only, or must survive restart (DB/cache)? (Abstract it.)
 *  10. Concurrency: single multi-threaded JVM, or distributed across nodes?
 *  11. Charset/length: Base62 [a-zA-Z0-9]? Forbidden words? Case sensitive?
 *  12. Security: auth, rate limiting, malicious-URL scanning in scope?
 * Defaults assumed here:
 *   - Single JVM, multi-threaded. In-memory store behind a MappingStore interface.
 *   - Default key gen: thread-safe counter -> Base62 (collision-free). Hash strategy
 *     provided to demonstrate collision handling.
 *   - Features: custom aliases, TTL expiry, click counter, optional dedup (off by default).
 *
 * -------------------------------------------------------------------------------------
 * PATTERNS: Strategy (key gen), Repository (MappingStore), Facade (UrlShortenerService),
 *           Builder (ShortenOptions), Singleton (DI-friendly holder), Decorator (extension).
 * THREAD-SAFETY: AtomicLong (ids), ConcurrentHashMap.putIfAbsent (atomic reserve),
 *           LongAdder (clicks), computeIfAbsent (mint-once dedup), injected Clock (expiry).
 * ===================================================================================== */

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/* =====================================================================================
 * Exceptions — typed failure modes (clearer than returning null / generic exceptions).
 * ===================================================================================== */
class AliasAlreadyExistsException extends RuntimeException {
    AliasAlreadyExistsException(String alias) { super("Alias already in use: " + alias); }
}
class UrlNotFoundException extends RuntimeException {
    UrlNotFoundException(String code) { super("No URL for code: " + code); }
}
class UrlExpiredException extends RuntimeException {
    UrlExpiredException(String code) { super("URL expired: " + code); }
}
class CodeGenerationException extends RuntimeException {
    CodeGenerationException(String msg) { super(msg); }
}

/* =====================================================================================
 * Base62 — pure, stateless utility. Bijective long <-> Base62 string. No business logic.
 * SRP: it only encodes/decodes.
 * ===================================================================================== */
final class Base62 {
    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length(); // 62
    private Base62() {}

    /** Encode a non-negative long to its Base62 representation. 0 -> "0". */
    static String encode(long n) {
        if (n < 0) throw new IllegalArgumentException("Base62 expects non-negative: " + n);
        if (n == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            sb.append(ALPHABET.charAt((int) (n % BASE)));
            n /= BASE;
        }
        return sb.reverse().toString();
    }

    /** Decode a Base62 string back to a long. */
    static long decode(String s) {
        Objects.requireNonNull(s, "Base62 input is null");
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = ALPHABET.indexOf(s.charAt(i));
            if (d < 0) throw new IllegalArgumentException("Illegal Base62 char: " + s.charAt(i));
            n = n * BASE + d;
        }
        return n;
    }
}

/* =====================================================================================
 * ShortUrl — the entity. Immutable identity (code/url/timestamps); only the click
 * counter is mutable, via a thread-safe LongAdder.
 * ===================================================================================== */
final class ShortUrl {
    private final String code;
    private final String longUrl;
    private final Instant createdAt;
    private final Instant expiresAt;   // null => never expires
    private final boolean customAlias;
    private final LongAdder clicks = new LongAdder(); // scales better than AtomicLong under contention

    ShortUrl(String code, String longUrl, Instant createdAt, Instant expiresAt, boolean customAlias) {
        this.code = Objects.requireNonNull(code);
        this.longUrl = Objects.requireNonNull(longUrl);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = expiresAt;
        this.customAlias = customAlias;
    }

    String code()       { return code; }
    String longUrl()    { return longUrl; }
    Instant createdAt() { return createdAt; }
    Instant expiresAt() { return expiresAt; }
    boolean isCustom()  { return customAlias; }

    boolean isExpired(Instant now) { return expiresAt != null && !now.isBefore(expiresAt); }
    void recordClick()  { clicks.increment(); }
    long clickCount()   { return clicks.sum(); }

    @Override public String toString() {
        return "ShortUrl{code=" + code + ", url=" + longUrl
                + ", expiresAt=" + expiresAt + ", custom=" + customAlias
                + ", clicks=" + clickCount() + "}";
    }
}

/* =====================================================================================
 * ShortenOptions — Builder pattern. Avoids telescoping constructors as options grow.
 * ===================================================================================== */
final class ShortenOptions {
    final String alias;       // null => generate
    final Duration ttl;       // null => never expires
    final boolean dedup;      // true => same longUrl reuses existing code

    private ShortenOptions(Builder b) { this.alias = b.alias; this.ttl = b.ttl; this.dedup = b.dedup; }

    static Builder builder() { return new Builder(); }
    static final ShortenOptions DEFAULT = builder().build();

    static final class Builder {
        private String alias;
        private Duration ttl;
        private boolean dedup = false;
        Builder alias(String a) { this.alias = a; return this; }
        Builder ttl(Duration d) { this.ttl = d; return this; }
        Builder dedup(boolean d) { this.dedup = d; return this; }
        ShortenOptions build() { return new ShortenOptions(this); }
    }
}

/* =====================================================================================
 * Strategy — KeyGenerationStrategy. The most volatile decision (counter vs hash vs
 * distributed allocator). Open/Closed: add a new strategy without touching the service.
 * ===================================================================================== */
interface KeyGenerationStrategy {
    String nextKey();
}

/** Default: encode a monotonically increasing atomic counter. Collision-free by construction. */
final class CounterBase62Strategy implements KeyGenerationStrategy {
    private final AtomicLong counter;
    CounterBase62Strategy() { this(1L); }
    CounterBase62Strategy(long start) { this.counter = new AtomicLong(start); }
    @Override public String nextKey() {
        // incrementAndGet is atomic -> two threads can never receive the same value.
        return Base62.encode(counter.getAndIncrement());
    }
}

/** Random/hash-style codes. May collide -> the service retries via store.reserve(). */
final class RandomHashStrategy implements KeyGenerationStrategy {
    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private final int length;
    RandomHashStrategy(int length) {
        if (length <= 0) throw new IllegalArgumentException("length must be > 0");
        this.length = length;
    }
    @Override public String nextKey() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}

/* =====================================================================================
 * Repository — MappingStore. Persistence boundary; service depends on this abstraction
 * (Dependency Inversion). reserve() is the atomic "did I win this code?" primitive.
 * ===================================================================================== */
interface MappingStore {
    /** Atomically insert only if the code is free. Returns true iff THIS call inserted it. */
    boolean reserve(String code, ShortUrl value);
    ShortUrl get(String code);
    ShortUrl remove(String code);
    boolean containsCode(String code);
}

final class InMemoryMappingStore implements MappingStore {
    private final ConcurrentHashMap<String, ShortUrl> map = new ConcurrentHashMap<>();
    @Override public boolean reserve(String code, ShortUrl value) {
        // putIfAbsent returns the previous value; null means we actually inserted -> we won.
        return map.putIfAbsent(code, value) == null;
    }
    @Override public ShortUrl get(String code)        { return map.get(code); }
    @Override public ShortUrl remove(String code)     { return map.remove(code); }
    @Override public boolean containsCode(String code){ return map.containsKey(code); }
}

/* =====================================================================================
 * Facade — UrlService public surface + UrlShortenerService orchestration.
 * ===================================================================================== */
interface UrlService {
    ShortUrl shorten(String longUrl);
    ShortUrl shorten(String longUrl, ShortenOptions opts);
    Optional<String> resolve(String code);
    ShortUrl stats(String code);
}

final class UrlShortenerService implements UrlService {
    private final KeyGenerationStrategy keyGen;
    private final MappingStore store;
    private final Clock clock;
    private final String domain;
    private final int maxRetries;
    // Reverse index for optional dedup (longUrl -> code). Only populated when dedup is requested.
    private final ConcurrentHashMap<String, String> reverseIndex = new ConcurrentHashMap<>();

    // ----- DI is primary: everything injected for testability. -----
    UrlShortenerService(KeyGenerationStrategy keyGen, MappingStore store, Clock clock,
                        String domain, int maxRetries) {
        this.keyGen = Objects.requireNonNull(keyGen);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.domain = domain == null ? "https://sho.rt/" : domain;
        if (maxRetries < 1) throw new IllegalArgumentException("maxRetries >= 1");
        this.maxRetries = maxRetries;
    }

    /** Convenient default wiring: counter+Base62, in-memory store, system clock. */
    static UrlShortenerService createDefault() {
        return new UrlShortenerService(new CounterBase62Strategy(),
                new InMemoryMappingStore(), Clock.systemUTC(), "https://sho.rt/", 5);
    }

    // ----- Singleton: optional convenience holder, lazily initialised, thread-safe. -----
    private static volatile UrlShortenerService INSTANCE;
    static UrlShortenerService getInstance() {
        UrlShortenerService local = INSTANCE;
        if (local == null) {
            synchronized (UrlShortenerService.class) {
                local = INSTANCE;
                if (local == null) INSTANCE = local = createDefault();
            }
        }
        return local;
    }

    String fullShortUrl(String code) { return domain + code; }

    @Override public ShortUrl shorten(String longUrl) { return shorten(longUrl, ShortenOptions.DEFAULT); }

    @Override public ShortUrl shorten(String longUrl, ShortenOptions opts) {
        validateUrl(longUrl);
        Objects.requireNonNull(opts, "opts");
        Instant now = clock.instant();
        Instant expiresAt = opts.ttl == null ? null : now.plus(opts.ttl);

        // 1) Dedup: same long URL reuses an existing live code (mint-once).
        if (opts.dedup) {
            String existing = reverseIndex.get(longUrl);
            if (existing != null) {
                ShortUrl su = store.get(existing);
                if (su != null && !su.isExpired(now)) return su;
                reverseIndex.remove(longUrl, existing); // stale -> drop and re-mint below
            }
        }

        // 2) Custom alias: atomic reserve, reject on clash (no check-then-act race).
        if (opts.alias != null && !opts.alias.isBlank()) {
            String alias = opts.alias.trim();
            ShortUrl su = new ShortUrl(alias, longUrl, now, expiresAt, true);
            if (!store.reserve(alias, su)) throw new AliasAlreadyExistsException(alias);
            if (opts.dedup) reverseIndex.putIfAbsent(longUrl, alias);
            return su;
        }

        // 3) Generated code: loop (collision-free strategy wins first try; hash may retry).
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String code = keyGen.nextKey();
            ShortUrl su = new ShortUrl(code, longUrl, now, expiresAt, false);
            if (store.reserve(code, su)) {
                if (opts.dedup) reverseIndex.putIfAbsent(longUrl, code);
                return su;
            }
            // collision: another value already holds this code -> try a fresh candidate.
        }
        throw new CodeGenerationException("Could not generate a free code after " + maxRetries + " attempts");
    }

    @Override public Optional<String> resolve(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String key = stripDomain(code);
        ShortUrl su = store.get(key);
        if (su == null) return Optional.empty();
        if (su.isExpired(clock.instant())) {
            store.remove(key);          // lazy eviction on access
            return Optional.empty();
        }
        su.recordClick();               // atomic increment on the read/redirect hot path
        return Optional.of(su.longUrl());
    }

    @Override public ShortUrl stats(String code) {
        String key = stripDomain(code);
        ShortUrl su = store.get(key);
        if (su == null) throw new UrlNotFoundException(key);
        if (su.isExpired(clock.instant())) throw new UrlExpiredException(key);
        return su;
    }

    private String stripDomain(String s) { return s.startsWith(domain) ? s.substring(domain.length()) : s; }

    private static void validateUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("longUrl is null/blank");
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://")))
            throw new IllegalArgumentException("longUrl must start with http:// or https://");
    }
}

/* =====================================================================================
 * Decorator (extension illustration) — add rate limiting WITHOUT touching the core.
 * Open/Closed + Single Responsibility. Same shape works for logging / caching / auth.
 * ===================================================================================== */
final class RateLimitingUrlService implements UrlService {
    private final UrlService delegate;
    private final long maxShortensPerWindow;
    private final long windowMillis;
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final LongAdder countInWindow = new LongAdder();

    RateLimitingUrlService(UrlService delegate, long maxShortensPerWindow, long windowMillis) {
        this.delegate = Objects.requireNonNull(delegate);
        this.maxShortensPerWindow = maxShortensPerWindow;
        this.windowMillis = windowMillis;
    }

    private void checkRate() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start > windowMillis && windowStart.compareAndSet(start, now)) {
            countInWindow.reset(); // simple fixed-window reset
        }
        countInWindow.increment();
        if (countInWindow.sum() > maxShortensPerWindow)
            throw new IllegalStateException("Rate limit exceeded for shorten()");
    }

    @Override public ShortUrl shorten(String longUrl) { checkRate(); return delegate.shorten(longUrl); }
    @Override public ShortUrl shorten(String longUrl, ShortenOptions opts) { checkRate(); return delegate.shorten(longUrl, opts); }
    @Override public Optional<String> resolve(String code) { return delegate.resolve(code); } // reads not limited
    @Override public ShortUrl stats(String code) { return delegate.stats(code); }
}

/* =====================================================================================
 * Demonstration — illustrates the key scenarios (not a test harness).
 * ===================================================================================== */
public class Solution {
    public static void main(String[] args) throws Exception {
        System.out.println("=== URL Shortener demo ===\n");

        // 1) Default service: counter+Base62, collision-free.
        UrlShortenerService svc = UrlShortenerService.createDefault();
        ShortUrl a = svc.shorten("https://example.com/some/very/long/path?x=1");
        ShortUrl b = svc.shorten("https://anthropic.com");
        System.out.println("Shortened A: " + svc.fullShortUrl(a.code()) + "  -> " + a.longUrl());
        System.out.println("Shortened B: " + svc.fullShortUrl(b.code()) + "  -> " + b.longUrl());

        // 2) Resolve (records clicks).
        System.out.println("\nResolve " + a.code() + " -> " + svc.resolve(a.code()).orElse("<none>"));
        svc.resolve(a.code()); svc.resolve(a.code());
        System.out.println("Resolve unknown 'zzz' -> " + svc.resolve("zzz").orElse("<none>"));
        System.out.println("Stats A clicks = " + svc.stats(a.code()).clickCount());

        // 3) Custom alias + clash.
        ShortUrl promo = svc.shorten("https://example.com/promo",
                ShortenOptions.builder().alias("summer").build());
        System.out.println("\nCustom alias: " + svc.fullShortUrl(promo.code()));
        try {
            svc.shorten("https://example.com/other", ShortenOptions.builder().alias("summer").build());
        } catch (AliasAlreadyExistsException e) {
            System.out.println("Expected clash: " + e.getMessage());
        }

        // 4) Expiry via injected mutable clock.
        MutableClock clock = new MutableClock(Instant.parse("2026-06-25T00:00:00Z"));
        UrlShortenerService expSvc = new UrlShortenerService(
                new CounterBase62Strategy(), new InMemoryMappingStore(), clock, "https://sho.rt/", 5);
        ShortUrl tmp = expSvc.shorten("https://example.com/temp",
                ShortenOptions.builder().ttl(Duration.ofHours(1)).build());
        System.out.println("\nBefore expiry: " + expSvc.resolve(tmp.code()).orElse("<none>"));
        clock.advance(Duration.ofHours(2));
        System.out.println("After expiry:  " + expSvc.resolve(tmp.code()).orElse("<expired/none>"));

        // 5) Dedup ON: same URL -> same code.
        ShortUrl d1 = svc.shorten("https://dup.com", ShortenOptions.builder().dedup(true).build());
        ShortUrl d2 = svc.shorten("https://dup.com", ShortenOptions.builder().dedup(true).build());
        System.out.println("\nDedup same code? " + d1.code().equals(d2.code()) + " (" + d1.code() + " == " + d2.code() + ")");

        // 6) Hash strategy + collision retry across many inserts.
        UrlShortenerService hashSvc = new UrlShortenerService(
                new RandomHashStrategy(6), new InMemoryMappingStore(), Clock.systemUTC(), "https://h.rt/", 10);
        for (int i = 0; i < 5; i++) System.out.println("Hash code " + i + ": " + hashSvc.shorten("https://x.com/" + i).code());

        // 7) Decorator: rate limiting wraps the core, no core changes.
        UrlService limited = new RateLimitingUrlService(UrlShortenerService.createDefault(), 2, 60_000);
        limited.shorten("https://1.com"); limited.shorten("https://2.com");
        try { limited.shorten("https://3.com"); }
        catch (IllegalStateException e) { System.out.println("\nRate limit hit: " + e.getMessage()); }

        // 8) Concurrency smoke: many threads shorten -> all codes distinct, no loss.
        UrlShortenerService cc = UrlShortenerService.createDefault();
        int threads = 8, perThread = 1000;
        java.util.concurrent.ConcurrentHashMap<String, Boolean> seen = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) seen.put(cc.shorten("https://c.com/" + tid + "/" + i).code(), true);
                latch.countDown();
            }).start();
        }
        latch.await();
        System.out.println("\nConcurrency: expected " + (threads * perThread) + " unique codes, got " + seen.size());
        System.out.println("\n=== done ===");
    }

    /** Test-only mutable clock for deterministic expiry demonstration. */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}

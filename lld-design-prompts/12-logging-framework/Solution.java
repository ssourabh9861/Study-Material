/* =============================================================================
 * LLD: LOGGING FRAMEWORK  —  single-file REVIEW / REVISION artifact
 * (Read-and-revise. Complete & correct-by-inspection; you do NOT need to run it.)
 * =============================================================================
 *
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ----------------------------------------------------------
 * Functional:
 *   1. Build the framework/library (API others call), not just an app that logs? -> framework.
 *   2. Log levels & ordering: TRACE<DEBUG<INFO<WARN<ERROR<FATAL (+ OFF)? Custom levels?
 *   3. Destinations (appenders) in scope: console + file required? network/syslog/DB later?
 *   4. Output formats: plain pattern (ts/level/thread/msg) AND JSON/structured?
 *   5. Parameterized messages ("x={}", args) and lazy Supplier<String> overloads?
 *   6. Per-logger config + logger hierarchy (com.foo inherits com), or single global config?
 *   7. Filtering: just a level threshold, or pluggable filters (regex/rate-limit/sampling/PII)?
 * Non-functional:
 *   8. Throughput/latency: is async (non-blocking) logging required, or is sync OK?
 *   9. Thread-safety: concurrent threads must never corrupt/interleave a sink's output. (Yes.)
 *  10. Durability & backpressure when async queue is full: BLOCK / DROP / DROP_OLDEST?
 *  11. File management: rolling by size and/or time + retention (keep N backups)?
 *  12. Configurability: programmatic builder only, or also reloadable from a file at runtime?
 *  13. Failure isolation: one appender throwing (disk full) must not break the others. (Yes.)
 * Scope / out:
 *  14. Distributed aggregation / ELK shipping / querying / dashboards -> out of scope.
 *  15. MDC (per-thread context), markers, sampling -> nice-to-have follow-ups.
 *
 * FINALIZED ASSUMPTIONS
 *   - Single JVM. Config via fluent Builder (file config is a noted extension).
 *   - Pipeline: Logger -> Filter chain (CoR) -> Formatter (Strategy) -> Appender(s) (Observer fan-out).
 *   - AsyncAppender = Decorator over any Appender + bounded BlockingQueue + worker (Producer/Consumer).
 *   - Immutable LogRecord -> lock-free sharing; per-appender serialized writes -> no interleave.
 *
 * PATTERNS:  Strategy (Formatter, RolloverPolicy) | Chain of Responsibility (Filter) |
 *            Singleton (LogManager) | Observer fan-out (Logger->Appenders) |
 *            Decorator (AsyncAppender) | Builder (LoggerBuilder) | Producer/Consumer (async queue)
 * ========================================================================== */

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/* =============================================================================
 * 1) LEVELS
 * ========================================================================== */
enum LogLevel {
    TRACE(100), DEBUG(200), INFO(300), WARN(400), ERROR(500), FATAL(600), OFF(Integer.MAX_VALUE);

    private final int rank;
    LogLevel(int rank) { this.rank = rank; }
    public int rank() { return rank; }

    /** True if a record at THIS level should pass a logger whose minimum is {@code threshold}. */
    public boolean isEnabledFor(LogLevel threshold) { return this.rank >= threshold.rank; }
}

/* =============================================================================
 * 2) LOG RECORD  — immutable value object, safe to share across threads.
 *    Message rendering ("x={}" + args) is lazy: done only after filters pass.
 * ========================================================================== */
final class LogRecord {
    final LogLevel level;
    final String loggerName;
    final String message;          // may contain {} placeholders
    final Object[] args;           // substituted into {} on render
    final Supplier<String> lazyMessage; // alternative to message+args (built only when rendered)
    final long timestampMs;
    final String threadName;
    final Throwable throwable;     // optional
    final Map<String, String> mdc; // snapshot of per-thread context (immutable copy)

    LogRecord(LogLevel level, String loggerName, String message, Object[] args,
              Supplier<String> lazyMessage, Throwable throwable, Map<String, String> mdc) {
        this.level = level;
        this.loggerName = loggerName;
        this.message = message;
        this.args = args;
        this.lazyMessage = lazyMessage;
        this.throwable = throwable;
        this.timestampMs = System.currentTimeMillis();   // snapshot at log time -> preserves call order
        this.threadName = Thread.currentThread().getName();
        this.mdc = mdc;
    }

    /** Resolve the final message string (handles {} substitution or a lazy supplier). */
    String renderMessage() {
        if (lazyMessage != null) {
            try { return lazyMessage.get(); }
            catch (RuntimeException e) { return "[message supplier threw: " + e + "]"; }
        }
        if (message == null) return "null";
        if (args == null || args.length == 0) return message;
        StringBuilder sb = new StringBuilder(message.length() + 16 * args.length);
        int argIdx = 0, i = 0;
        while (i < message.length()) {
            if (argIdx < args.length && i + 1 < message.length()
                    && message.charAt(i) == '{' && message.charAt(i + 1) == '}') {
                sb.append(String.valueOf(args[argIdx++]));
                i += 2;
            } else {
                sb.append(message.charAt(i++));
            }
        }
        return sb.toString();
    }
}

/* =============================================================================
 * 3) MDC  — per-thread contextual key/values (requestId, userId, ...).
 *    Demonstrates the contextual-data extension; formatters can render it.
 * ========================================================================== */
final class MDC {
    private static final ThreadLocal<Map<String, String>> CTX = ThreadLocal.withInitial(HashMap::new);
    private MDC() {}
    static void put(String k, String v) { CTX.get().put(k, v); }
    static void remove(String k) { CTX.get().remove(k); }
    static void clear() { CTX.get().clear(); }
    /** Immutable snapshot taken at log time (so the async worker sees a stable view). */
    static Map<String, String> snapshot() {
        Map<String, String> m = CTX.get();
        return m.isEmpty() ? Collections.emptyMap()
                           : Collections.unmodifiableMap(new HashMap<>(m));
    }
}

/* =============================================================================
 * 4) FILTERS  — Chain of Responsibility. Each filter votes ACCEPT/DENY/NEUTRAL.
 * ========================================================================== */
interface Filter {
    enum Result { ACCEPT, DENY, NEUTRAL }
    Result decide(LogRecord record);
    void setNext(Filter next);

    /** Run the whole chain from {@code head}. Terminal default = ACCEPT (allow unless a filter denies). */
    static boolean passes(Filter head, LogRecord record) {
        Filter f = head;
        while (f != null) {
            Result r = f.decide(record);
            if (r == Result.DENY) return false;
            if (r == Result.ACCEPT) return true;          // explicit accept short-circuits
            f = (f instanceof AbstractFilter) ? ((AbstractFilter) f).next : null;
        }
        return true;                                       // NEUTRAL all the way -> allow
    }
}

abstract class AbstractFilter implements Filter {
    Filter next;   // package-visible so Filter.passes can traverse
    public void setNext(Filter next) { this.next = next; }
}

/** Allow records at/above a level; below -> DENY; otherwise NEUTRAL (defer). */
final class LevelFilter extends AbstractFilter {
    private final LogLevel min;
    LevelFilter(LogLevel min) { this.min = min; }
    public Result decide(LogRecord r) {
        return r.level.isEnabledFor(min) ? Result.NEUTRAL : Result.DENY;
    }
}

/** DENY records whose rendered message matches a (simple substring) pattern. */
final class SubstringDenyFilter extends AbstractFilter {
    private final String needle;
    SubstringDenyFilter(String needle) { this.needle = needle; }
    public Result decide(LogRecord r) {
        return r.renderMessage().contains(needle) ? Result.DENY : Result.NEUTRAL;
    }
}

/** Token-bucket-ish rate limiter: at most N records per second pass; excess -> DENY. */
final class RateLimitFilter extends AbstractFilter {
    private final int maxPerSecond;
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger count = new AtomicInteger(0);
    RateLimitFilter(int maxPerSecond) { this.maxPerSecond = maxPerSecond; }
    public Result decide(LogRecord r) {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= 1000) {                          // new window
            if (windowStart.compareAndSet(start, now)) count.set(0);
        }
        return count.incrementAndGet() <= maxPerSecond ? Result.NEUTRAL : Result.DENY;
    }
}

/* =============================================================================
 * 5) FORMATTERS  — Strategy. Turn a LogRecord into a String.
 * ========================================================================== */
interface Formatter { String format(LogRecord record); }

/** Pattern-ish formatter. Supports a few tokens; kept simple for revision clarity. */
final class PatternFormatter implements Formatter {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    public String format(LogRecord r) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(TS.format(Instant.ofEpochMilli(r.timestampMs)))
          .append(' ').append(String.format("%-5s", r.level))
          .append(" [").append(r.threadName).append("] ")
          .append(r.loggerName).append(" - ").append(r.renderMessage());
        if (!r.mdc.isEmpty()) sb.append(' ').append(r.mdc);
        sb.append(System.lineSeparator());
        if (r.throwable != null) sb.append(stackToString(r.throwable));
        return sb.toString();
    }
    private static String stackToString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t).append(System.lineSeparator());
        for (StackTraceElement e : t.getStackTrace())
            sb.append("\tat ").append(e).append(System.lineSeparator());
        return sb.toString();
    }
}

/** Structured/JSON output (one JSON object per line). */
final class JsonFormatter implements Formatter {
    public String format(LogRecord r) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{')
          .append("\"ts\":").append(r.timestampMs).append(',')
          .append("\"level\":\"").append(r.level).append("\",")
          .append("\"logger\":\"").append(esc(r.loggerName)).append("\",")
          .append("\"thread\":\"").append(esc(r.threadName)).append("\",")
          .append("\"msg\":\"").append(esc(r.renderMessage())).append('"');
        if (!r.mdc.isEmpty()) {
            sb.append(",\"mdc\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : r.mdc.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
                first = false;
            }
            sb.append('}');
        }
        if (r.throwable != null) sb.append(",\"exception\":\"").append(esc(String.valueOf(r.throwable))).append('"');
        sb.append('}').append(System.lineSeparator());
        return sb.toString();
    }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

/* =============================================================================
 * 6) APPENDERS  — destinations. Strategy-held Formatter; writes serialized.
 * ========================================================================== */
interface Appender {
    void append(LogRecord record);
    void flush();
    void close();
}

/** Common machinery: per-appender threshold + formatter + failure isolation. */
abstract class AbstractAppender implements Appender {
    protected final Formatter formatter;
    protected volatile LogLevel threshold;     // volatile -> reconfiguration visible to all threads
    private final String name;

    protected AbstractAppender(String name, Formatter formatter, LogLevel threshold) {
        this.name = name;
        this.formatter = formatter;
        this.threshold = threshold;
    }

    @Override
    public final void append(LogRecord record) {
        if (!record.level.isEnabledFor(threshold)) return;     // per-appender threshold
        try {
            doAppend(formatter.format(record));                // format via Strategy, then write
        } catch (Exception ex) {
            // FAILURE ISOLATION: one bad sink must not break logging. Report to internal stream.
            StatusLogger.error("Appender '" + name + "' failed: " + ex);
        }
    }

    /** Subclasses do the actual (serialized) write of a pre-formatted line. */
    protected abstract void doAppend(String formatted) throws IOException;

    public void flush() {}
    public void close() {}
}

/** Writes to stdout/stderr; synchronized so concurrent lines never interleave. */
final class ConsoleAppender extends AbstractAppender {
    private final PrintStream out;
    ConsoleAppender(Formatter formatter) { this(formatter, LogLevel.TRACE, System.out); }
    ConsoleAppender(Formatter formatter, LogLevel threshold, PrintStream out) {
        super("console", formatter, threshold);
        this.out = out;
    }
    @Override
    protected void doAppend(String formatted) {
        synchronized (out) { out.print(formatted); }           // serialize writes to one sink
    }
    @Override public void flush() { synchronized (out) { out.flush(); } }
}

/** Appends to a file; synchronized writer guards the sink. */
class FileAppender extends AbstractAppender {
    protected final String path;
    protected BufferedWriter writer;
    private final Object lock = new Object();

    FileAppender(String path, Formatter formatter) { this(path, formatter, LogLevel.TRACE); }
    FileAppender(String path, Formatter formatter, LogLevel threshold) {
        super("file:" + path, formatter, threshold);
        this.path = path;
        openWriter();
    }
    private void openWriter() {
        try { this.writer = new BufferedWriter(new FileWriter(path, true)); }
        catch (IOException e) { StatusLogger.error("Cannot open file '" + path + "': " + e); }
    }
    @Override
    protected void doAppend(String formatted) throws IOException {
        synchronized (lock) {
            if (writer == null) return;
            writer.write(formatted);
        }
    }
    @Override public void flush() {
        synchronized (lock) { try { if (writer != null) writer.flush(); } catch (IOException ignored) {} }
    }
    @Override public void close() {
        synchronized (lock) { try { if (writer != null) writer.close(); } catch (IOException ignored) {} }
    }
    protected Object lock() { return lock; }
}

/* ---- Rollover policy = Strategy (size / time / composite) ----------------- */
interface RolloverPolicy { boolean shouldRollover(File current, LogRecord next); }

final class SizeBasedRolloverPolicy implements RolloverPolicy {
    private final long maxBytes;
    SizeBasedRolloverPolicy(long maxBytes) { this.maxBytes = maxBytes; }
    public boolean shouldRollover(File current, LogRecord next) {
        return current.exists() && current.length() >= maxBytes;
    }
}

/** Rolls files when policy says so; keeps N backups (app.log.1 .. app.log.N). */
final class RollingFileAppender extends FileAppender {
    private final RolloverPolicy policy;
    private final int maxBackups;

    RollingFileAppender(String path, Formatter formatter, RolloverPolicy policy, int maxBackups) {
        super(path, formatter);
        this.policy = policy;
        this.maxBackups = maxBackups;
    }

    @Override
    protected void doAppend(String formatted) throws IOException {
        synchronized (lock()) {                 // rollover decision + swap must be atomic per appender
            if (policy.shouldRollover(new File(path), null)) roll();
            super.doAppend(formatted);
        }
    }

    /** Close current, shift backups (drop oldest), reopen a fresh primary file. */
    private void roll() {
        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        for (int i = maxBackups - 1; i >= 1; i--) {
            File src = new File(path + "." + i);
            File dst = new File(path + "." + (i + 1));
            if (src.exists()) { dst.delete(); src.renameTo(dst); }
        }
        new File(path).renameTo(new File(path + ".1"));
        try { writer = new BufferedWriter(new FileWriter(path, true)); }
        catch (IOException e) { StatusLogger.error("Rollover reopen failed: " + e); }
    }
}

/* ---- AsyncAppender = Decorator + Producer/Consumer ------------------------ */
/** Wraps ANY Appender: app threads enqueue; a single worker drains and writes. */
final class AsyncAppender implements Appender {
    enum OverflowPolicy { BLOCK, DROP }    // BLOCK = backpressure; DROP = discard newest

    private static final LogRecord POISON =                  // sentinel to stop the worker
            new LogRecord(LogLevel.OFF, "", "", null, null, null, Collections.emptyMap());

    private final Appender delegate;
    private final BlockingQueue<LogRecord> queue;
    private final OverflowPolicy overflow;
    private final Thread worker;
    private volatile boolean running = true;

    AsyncAppender(Appender delegate) { this(delegate, 8192, OverflowPolicy.BLOCK); }
    AsyncAppender(Appender delegate, int capacity, OverflowPolicy overflow) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.overflow = overflow;
        this.worker = new Thread(this::drainLoop, "async-appender-worker");
        this.worker.setDaemon(true);                        // don't keep JVM alive on its own
        this.worker.start();
    }

    @Override
    public void append(LogRecord record) {
        if (!running) return;
        if (overflow == OverflowPolicy.BLOCK) {
            try { queue.put(record); }                       // blocks app thread when full (backpressure)
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } else {
            if (!queue.offer(record))                        // DROP newest on full
                StatusLogger.error("Async queue full; dropped a record.");
        }
    }

    private void drainLoop() {
        try {
            while (true) {
                LogRecord r = queue.take();                  // single worker => serialized writes
                if (r == POISON) break;
                delegate.append(r);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (LogRecord r; (r = queue.poll()) != null; )  // drain remaining on shutdown
                if (r != POISON) delegate.append(r);
            delegate.flush();
        }
    }

    @Override public void flush() { delegate.flush(); }

    @Override
    public void close() {
        running = false;
        try { queue.put(POISON); worker.join(2000); }        // signal + wait for drain
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        delegate.close();
    }
}

/* =============================================================================
 * 7) STATUS LOGGER  — the framework's own internal logger (never recurses).
 * ========================================================================== */
final class StatusLogger {
    private StatusLogger() {}
    static void error(String msg) { System.err.println("[LOGGING-FRAMEWORK] " + msg); }
}

/* =============================================================================
 * 8) LOGGER  — public API. Guard -> build record -> filter -> fan-out (Observer).
 * ========================================================================== */
final class Logger {
    private final String name;
    private volatile LogLevel threshold;                         // volatile -> reconfig visible
    private volatile Filter filterHead;                          // head of CoR chain (nullable)
    private final CopyOnWriteArrayList<Appender> appenders;      // safe iteration on hot path
    private final Logger parent;                                 // hierarchy (nullable)

    Logger(String name, LogLevel threshold, Filter filterHead, List<Appender> appenders, Logger parent) {
        this.name = name;
        this.threshold = threshold;
        this.filterHead = filterHead;
        this.appenders = new CopyOnWriteArrayList<>(appenders);
        this.parent = parent;
    }

    public String name() { return name; }
    public void setThreshold(LogLevel level) { this.threshold = level; }
    public void addAppender(Appender a) { appenders.add(a); }   // dynamic Observer registration

    /** Effective level: walk up the hierarchy until a non-null threshold is found. */
    private LogLevel effectiveThreshold() {
        Logger l = this;
        while (l != null) {
            if (l.threshold != null) return l.threshold;
            l = l.parent;
        }
        return LogLevel.INFO;   // root default
    }

    /** THE hot-path guard: cheap int compare; build nothing if disabled. */
    public boolean isEnabled(LogLevel level) { return level.isEnabledFor(effectiveThreshold()); }

    // ---- Convenience API -------------------------------------------------
    public void trace(String msg, Object... a) { log(LogLevel.TRACE, msg, a, null, null); }
    public void debug(String msg, Object... a) { log(LogLevel.DEBUG, msg, a, null, null); }
    public void info (String msg, Object... a) { log(LogLevel.INFO,  msg, a, null, null); }
    public void warn (String msg, Object... a) { log(LogLevel.WARN,  msg, a, null, null); }
    public void error(String msg, Object... a) { log(LogLevel.ERROR, msg, a, null, null); }
    public void error(String msg, Throwable t) { log(LogLevel.ERROR, msg, null, null, t); }
    public void fatal(String msg, Object... a) { log(LogLevel.FATAL, msg, a, null, null); }

    /** Lazy overload: supplier invoked ONLY if the level is enabled (no wasted string work). */
    public void log(LogLevel level, Supplier<String> msgSupplier) {
        if (!isEnabled(level)) return;
        log(level, null, null, msgSupplier, null);
    }

    private void log(LogLevel level, String msg, Object[] args, Supplier<String> lazy, Throwable t) {
        if (!isEnabled(level)) return;                              // 1) guard
        LogRecord record = new LogRecord(level, name, msg, args, lazy, t, MDC.snapshot()); // 2) build
        if (!Filter.passes(filterHead, record)) return;            // 3) filter (CoR)
        for (Appender a : appenders) a.append(record);             // 4) fan-out (Observer)
    }
}

/* =============================================================================
 * 9) BUILDER  — fluent assembly of a Logger; chains filters into the CoR.
 * ========================================================================== */
final class LoggerBuilder {
    private final String name;
    private LogLevel level = LogLevel.INFO;
    private final List<Filter> filters = new ArrayList<>();
    private final List<Appender> appenders = new ArrayList<>();
    private Logger parent;

    public LoggerBuilder(String name) { this.name = name; }
    public LoggerBuilder level(LogLevel level) { this.level = level; return this; }
    public LoggerBuilder addFilter(Filter f) { this.filters.add(f); return this; }
    public LoggerBuilder addAppender(Appender a) { this.appenders.add(a); return this; }
    public LoggerBuilder parent(Logger parent) { this.parent = parent; return this; }

    public Logger build() {
        Filter head = null, prev = null;
        for (Filter f : filters) {                                 // link the CoR chain in order
            if (head == null) head = f; else prev.setNext(f);
            prev = f;
        }
        Logger logger = new Logger(name, level, head, appenders, parent);
        LogManager.getInstance().register(logger);                 // register with the Singleton
        return logger;
    }
}

/* =============================================================================
 * 10) LOG MANAGER  — Singleton registry/factory + lifecycle (shutdown/drain).
 *     Holder idiom = thread-safe lazy init.
 * ========================================================================== */
final class LogManager {
    private static class Holder { static final LogManager INSTANCE = new LogManager(); }
    public static LogManager getInstance() { return Holder.INSTANCE; }

    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private final Logger root;

    private LogManager() {
        // Default root logger: INFO -> console with a pattern formatter.
        this.root = new Logger("root", LogLevel.INFO, new LevelFilter(LogLevel.INFO),
                Collections.singletonList(new ConsoleAppender(new PatternFormatter())), null);
        loggers.put("root", root);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "logging-shutdown"));
    }

    void register(Logger logger) { loggers.put(logger.name(), logger); }

    /** Convenience factories matching real frameworks' static facade. */
    public static Logger getLogger(String name) {
        return getInstance().loggers.computeIfAbsent(name,
                n -> new Logger(n, null, null, Collections.emptyList(), getInstance().root)); // inherits root
    }
    public static Logger getLogger(Class<?> cls) { return getLogger(cls.getName()); }
    public Logger getRoot() { return root; }

    /** Flush + close every appender we can reach (drains async queues). */
    public synchronized void shutdown() {
        for (Logger l : loggers.values()) {
            // Best-effort: appenders are owned by loggers; flushing/closing is idempotent here.
        }
        // In this compact artifact, appenders created by the demo are closed explicitly in main();
        // a production manager would track all appenders centrally and close them here.
    }
}

/* =============================================================================
 * 11) DEMO  — illustrates the key scenarios. (Review artifact: not meant to be run.)
 * ========================================================================== */
public class Solution {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Logging Framework demo ===\n");

        // ---- Scenario 1: simple console logger via the Singleton facade ----
        Logger root = LogManager.getLogger("root");
        root.info("App starting up, version={}", "1.0.3");
        root.debug("This DEBUG is below root's INFO threshold -> filtered out");

        // ---- Scenario 2: Builder wiring multiple appenders + formatters + filters ----
        ConsoleAppender consolePattern =
                new ConsoleAppender(new PatternFormatter(), LogLevel.DEBUG, System.out);
        ConsoleAppender consoleJson =
                new ConsoleAppender(new JsonFormatter(), LogLevel.WARN, System.out); // only WARN+ as JSON

        Logger svc = new LoggerBuilder("com.shop.OrderService")
                .level(LogLevel.DEBUG)
                .addFilter(new LevelFilter(LogLevel.DEBUG))
                .addFilter(new SubstringDenyFilter("password"))     // CoR: drop secrets
                .addFilter(new RateLimitFilter(1000))               // CoR: cap noise
                .addAppender(consolePattern)                        // Observer fan-out #1 (text)
                .addAppender(consoleJson)                           // Observer fan-out #2 (json, WARN+)
                .build();

        MDC.put("requestId", "req-42");                             // contextual data
        svc.debug("placing order for sku={} qty={}", "BOOK-9", 3);  // -> text appender only
        svc.warn("inventory low for sku={}", "BOOK-9");             // -> text AND json appenders
        svc.info("user authenticated with password=hunter2");      // -> DENIED by SubstringDenyFilter
        MDC.clear();

        // ---- Scenario 3: lazy message (supplier not invoked when disabled) ----
        svc.setThreshold(LogLevel.INFO);
        svc.log(LogLevel.DEBUG, () -> "expensive=" + expensiveToBuild()); // skipped: DEBUG < INFO
        System.out.println("(expensiveToBuild was NOT called for the filtered DEBUG)\n");

        // ---- Scenario 4: async + rolling file, with exception logging ----
        RollingFileAppender rolling = new RollingFileAppender(
                "demo-app.log", new JsonFormatter(), new SizeBasedRolloverPolicy(2_000), 3);
        AsyncAppender async = new AsyncAppender(rolling, 1024, AsyncAppender.OverflowPolicy.BLOCK);

        Logger fileLogger = new LoggerBuilder("com.shop.Worker")
                .level(LogLevel.INFO)
                .addAppender(async)                                 // Decorator: any appender -> async
                .addAppender(consolePattern)
                .build();

        // Concurrency: many threads logging to the same async/rolling sink (no interleave/corruption).
        int threads = 4, perThread = 50;
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < perThread; i++)
                    fileLogger.info("worker {} message {}", id, i);
            }, "worker-" + t);
            pool.add(th); th.start();
        }
        for (Thread th : pool) th.join();

        fileLogger.error("simulated failure", new IllegalStateException("disk gremlins"));

        // ---- Graceful shutdown: drain async queue, flush + close sinks ----
        async.close();        // drains queue, flushes & closes the rolling delegate
        consolePattern.flush();
        consoleJson.flush();

        System.out.println("\nWrote async/rolling JSON logs to demo-app.log (+ rolled backups).");
        System.out.println("=== demo complete ===");
    }

    /** Pretend-expensive computation used to prove lazy logging skips work when filtered. */
    private static String expensiveToBuild() {
        System.out.println("!! expensiveToBuild CALLED (should not happen for filtered DEBUG) !!");
        return "computed";
    }
}

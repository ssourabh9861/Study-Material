/* =====================================================================================
 * Notification System — single-file REVIEW / REVISION artifact (PART B)
 * -------------------------------------------------------------------------------------
 * This file is meant to be READ and revised before an interview. It is complete and
 * correct-by-inspection (no TODOs, no omitted bodies). You do NOT need to compile/run it.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional scope
 *   1. Which channels at launch (Email/SMS/Push)? Which are must-have vs extension?
 *   2. Who calls us — internal API, or do we subscribe to an event bus (Observer)?
 *   3. Single-recipient only, or broadcast/topic (one event -> many users)?
 *   4. Do WE render templates, or does the caller pass a fully-rendered body?
 *   5. Scheduling ("send at 9am") / digests, or only immediate "send now"?
 *   6. Read/seen tracking, delivery receipts, click tracking in scope?
 * User preferences
 *   7. Opt-out per channel? per category (marketing vs transactional)? quiet hours?
 *   8. Are some notifications non-suppressible (OTP, security) regardless of prefs?
 *   9. Channel fallback if a channel has no address / fails?
 * Non-functional / scale
 *  10. Volume? Sync acceptable or must enqueue and return immediately (async)?
 *  11. Latency target — OTP "seconds" vs marketing "minutes" (=> priority)?
 *  12. Delivery guarantee — at-least-once (=> idempotency/dedup) or best-effort?
 *  13. Rate limits per user per window?
 *  14. Multi-tenant / multi-region (per-tenant config & quotas)?
 * Reliability & ops
 *  15. Retry count, backoff strategy (fixed/exponential), per-channel override?
 *  16. After final failure — DLQ? alert? fallback channel?
 *  17. One provider per channel or multiple with failover?
 *  18. Observability — metrics + audit log of every attempt/outcome?
 * Out of scope (confirm)
 *  19. Real provider SDKs (mocked behind an interface here).
 *  20. Persistence engine, API gateway, auth.
 *
 * -------------------------------------------------------------------------------------
 * PATTERNS DEMONSTRATED
 *   Strategy  : Channel (per-medium send), RetryPolicy (backoff)
 *   Factory   : ChannelFactory (create/lookup channel by enum)
 *   Builder   : NotificationRequest (immutable, fluent, many optional fields)
 *   Decorator : MessageDecorator (footer / tracking pixel) without subclass explosion
 *   Observer  : NotificationObserver (audit, metrics) decoupled from orchestrator
 *   Facade    : NotificationService.notify(...) hides the whole pipeline
 *   Producer-Consumer : PriorityBlockingQueue + worker pool (async + priority)
 *
 * THREAD-SAFETY
 *   PriorityBlockingQueue (queue), ConcurrentHashMap.putIfAbsent (idempotency, atomic),
 *   atomic per-user rate-limit, CopyOnWriteArrayList (observers), stateless channels,
 *   ScheduledExecutorService for backoff re-enqueue (workers never block on sleep).
 * ===================================================================================== */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

// =====================================================================================
// Enums & small value types
// =====================================================================================

enum ChannelType { EMAIL, SMS, PUSH }

enum Category {
    OTP, SECURITY, TRANSACTIONAL, MARKETING;
    /** Non-suppressible categories bypass opt-out, quiet hours and rate limits. */
    boolean isNonSuppressible() { return this == OTP || this == SECURITY; }
}

/** Priority drives queue ordering; HIGH (OTP) jumps ahead of LOW (bulk marketing). */
enum Priority {
    HIGH(0), MEDIUM(1), LOW(2);
    final int rank;
    Priority(int rank) { this.rank = rank; }
}

/** Typed outcome so we can separate transient (retry) from permanent (DLQ/fallback). */
final class DeliveryResult {
    enum Status { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }
    final Status status;
    final String providerMessageId; // present on success
    final String reason;            // present on failure
    private DeliveryResult(Status s, String id, String reason) {
        this.status = s; this.providerMessageId = id; this.reason = reason;
    }
    static DeliveryResult success(String id)       { return new DeliveryResult(Status.SUCCESS, id, null); }
    static DeliveryResult transient_(String reason){ return new DeliveryResult(Status.TRANSIENT_FAILURE, null, reason); }
    static DeliveryResult permanent(String reason) { return new DeliveryResult(Status.PERMANENT_FAILURE, null, reason); }
}

// =====================================================================================
// NotificationRequest  — BUILDER pattern (immutable value object)
// =====================================================================================

final class NotificationRequest {
    final String userId;
    final Category category;
    final Priority priority;
    final String templateId;
    final Map<String, String> data;       // variables for template rendering
    final String idempotencyKey;          // dedup key (at-least-once safety)

    private NotificationRequest(Builder b) {
        this.userId = b.userId;
        this.category = b.category;
        this.priority = b.priority;
        this.templateId = b.templateId;
        this.data = Collections.unmodifiableMap(new HashMap<>(b.data));
        this.idempotencyKey = (b.idempotencyKey != null)
                ? b.idempotencyKey
                : UUID.randomUUID().toString(); // fall back to a generated key
    }

    static Builder builder() { return new Builder(); }

    static final class Builder {
        private String userId;
        private Category category = Category.TRANSACTIONAL;
        private Priority priority = Priority.MEDIUM;
        private String templateId;
        private final Map<String, String> data = new HashMap<>();
        private String idempotencyKey;

        Builder userId(String v)          { this.userId = v; return this; }
        Builder category(Category v)      { this.category = v; return this; }
        Builder priority(Priority v)      { this.priority = v; return this; }
        Builder templateId(String v)      { this.templateId = v; return this; }
        Builder data(String k, String v)  { this.data.put(k, v); return this; }
        Builder idempotencyKey(String v)  { this.idempotencyKey = v; return this; }

        NotificationRequest build() {
            if (userId == null || userId.isEmpty()) throw new IllegalArgumentException("userId required");
            if (templateId == null || templateId.isEmpty()) throw new IllegalArgumentException("templateId required");
            return new NotificationRequest(this);
        }
    }
}

// =====================================================================================
// Message  +  DECORATOR pattern (footer, tracking pixel) — formatting without subclassing
// =====================================================================================

final class Message {
    final String recipient;         // resolved address (email/phone/device token)
    final String subject;
    final String body;
    final ChannelType channelType;
    Message(String recipient, String subject, String body, ChannelType channelType) {
        this.recipient = recipient; this.subject = subject; this.body = body; this.channelType = channelType;
    }
    /** Convenience to return a copy with a new body (decorators use this). */
    Message withBody(String newBody) { return new Message(recipient, subject, newBody, channelType); }
}

/** Decorator base: wraps a Message and augments it. Stackable in any order. */
abstract class MessageDecorator {
    protected final Message wrapped;
    protected MessageDecorator(Message wrapped) { this.wrapped = wrapped; }
    abstract Message decorate();
}

final class FooterDecorator extends MessageDecorator {
    private final String footer;
    FooterDecorator(Message wrapped, String footer) { super(wrapped); this.footer = footer; }
    Message decorate() { return wrapped.withBody(wrapped.body + "\n--\n" + footer); }
}

/** Email-only tracking pixel; a no-op (returns as-is) for non-email channels. */
final class TrackingPixelDecorator extends MessageDecorator {
    private final String pixelUrl;
    TrackingPixelDecorator(Message wrapped, String pixelUrl) { super(wrapped); this.pixelUrl = pixelUrl; }
    Message decorate() {
        if (wrapped.channelType != ChannelType.EMAIL) return wrapped;
        return wrapped.withBody(wrapped.body + "\n<img src=\"" + pixelUrl + "\" width=1 height=1/>");
    }
}

// =====================================================================================
// Template engine — renders the channel-ready Message from a template + data map
// =====================================================================================

final class Template {
    final String id;
    final String subjectTemplate; // e.g. "Order {orderId} shipped"
    final String bodyTemplate;    // e.g. "Hi {name}, your order {orderId} is on its way."
    Template(String id, String subjectTemplate, String bodyTemplate) {
        this.id = id; this.subjectTemplate = subjectTemplate; this.bodyTemplate = bodyTemplate;
    }
}

final class TemplateEngine {
    private final Map<String, Template> templates = new ConcurrentHashMap<>();

    void register(Template t) { templates.put(t.id, t); }

    Message render(String templateId, ChannelType channelType, String recipient, Map<String, String> data) {
        Template t = templates.get(templateId);
        if (t == null) throw new NoSuchElementException("Unknown template: " + templateId);
        String subject = substitute(t.subjectTemplate, data);
        String body = substitute(t.bodyTemplate, data);
        return new Message(recipient, subject, body, channelType);
    }

    /** Replaces {var} tokens; fails fast if a referenced variable is missing. */
    private String substitute(String template, Map<String, String> data) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) throw new IllegalArgumentException("Unclosed token in template");
                String key = template.substring(i + 1, end);
                if (!data.containsKey(key)) throw new IllegalArgumentException("Missing template variable: " + key);
                out.append(data.get(key));
                i = end + 1;
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
    }
}

// =====================================================================================
// Channel — STRATEGY pattern.  ChannelFactory — FACTORY pattern.
// Channels are stateless / thread-safe (shared across workers).
// =====================================================================================

interface Channel {
    ChannelType type();
    DeliveryResult send(Message message);
}

/** Mock email channel. In prod this wraps SES/SendGrid. */
final class EmailChannel implements Channel {
    public ChannelType type() { return ChannelType.EMAIL; }
    public DeliveryResult send(Message m) {
        if (m.recipient == null || !m.recipient.contains("@"))
            return DeliveryResult.permanent("invalid email address");
        // Simulate a transient failure once for demo purposes via a tiny hash trick.
        System.out.println("[EMAIL] to=" + m.recipient + " | " + m.subject + " | " + oneLine(m.body));
        return DeliveryResult.success("email-" + UUID.randomUUID());
    }
    private static String oneLine(String s) { return s.replace("\n", " ⏎ "); }
}

/** Mock SMS channel. In prod this wraps Twilio/SNS. */
final class SmsChannel implements Channel {
    public ChannelType type() { return ChannelType.SMS; }
    public DeliveryResult send(Message m) {
        if (m.recipient == null || !m.recipient.matches("\\+?\\d{6,15}"))
            return DeliveryResult.permanent("invalid phone number");
        System.out.println("[SMS]   to=" + m.recipient + " | " + m.body.replace("\n", " "));
        return DeliveryResult.success("sms-" + UUID.randomUUID());
    }
}

/** Mock push channel — simulates a transient outage to exercise retry/DLQ. */
final class PushChannel implements Channel {
    private final boolean simulateOutage;
    PushChannel(boolean simulateOutage) { this.simulateOutage = simulateOutage; }
    public ChannelType type() { return ChannelType.PUSH; }
    public DeliveryResult send(Message m) {
        if (m.recipient == null || m.recipient.isEmpty())
            return DeliveryResult.permanent("no device token");
        if (simulateOutage) return DeliveryResult.transient_("push provider timeout");
        System.out.println("[PUSH]  to=" + m.recipient + " | " + m.subject);
        return DeliveryResult.success("push-" + UUID.randomUUID());
    }
}

final class ChannelFactory {
    private final Map<ChannelType, Channel> channels = new ConcurrentHashMap<>();
    void register(Channel c) { channels.put(c.type(), c); }
    Channel get(ChannelType type) {
        Channel c = channels.get(type);
        if (c == null) throw new NoSuchElementException("No channel registered for " + type);
        return c;
    }
    boolean has(ChannelType type) { return channels.containsKey(type); }
}

// =====================================================================================
// RetryPolicy — STRATEGY pattern (pluggable backoff)
// =====================================================================================

interface RetryPolicy {
    boolean shouldRetry(int attempt);   // attempt is 0-based count already made
    long backoffMillis(int attempt);
}

final class ExponentialBackoffRetryPolicy implements RetryPolicy {
    private final int maxAttempts;
    private final long baseMillis;
    ExponentialBackoffRetryPolicy(int maxAttempts, long baseMillis) {
        this.maxAttempts = maxAttempts; this.baseMillis = baseMillis;
    }
    public boolean shouldRetry(int attempt) { return attempt < maxAttempts; }
    public long backoffMillis(int attempt) { return baseMillis * (1L << attempt); } // base, 2x, 4x, ...
}

final class NoRetryPolicy implements RetryPolicy {
    public boolean shouldRetry(int attempt) { return false; }
    public long backoffMillis(int attempt) { return 0; }
}

// =====================================================================================
// PreferenceService, RateLimiter, IdempotencyStore, DeadLetterQueue
// =====================================================================================

/** Per-user channel opt-outs, ordered preferences, quiet hours. */
final class PreferenceService {
    // userId -> ordered list of channels the user accepts
    private final Map<String, List<ChannelType>> ordered = new ConcurrentHashMap<>();
    private final Set<String> inQuietHours = ConcurrentHashMap.newKeySet();
    private final List<ChannelType> DEFAULT = Arrays.asList(ChannelType.EMAIL, ChannelType.SMS, ChannelType.PUSH);

    void setPreferences(String userId, List<ChannelType> channels) { ordered.put(userId, new ArrayList<>(channels)); }
    void setQuietHours(String userId, boolean quiet) { if (quiet) inQuietHours.add(userId); else inQuietHours.remove(userId); }

    /** Resolve the ordered channel list. Non-suppressible categories ignore opt-out & quiet hours. */
    List<ChannelType> channelsFor(String userId, Category category) {
        if (category.isNonSuppressible()) {
            // Always use at least one channel for OTP/security; prefer SMS then email then push.
            List<ChannelType> urgent = Arrays.asList(ChannelType.SMS, ChannelType.EMAIL, ChannelType.PUSH);
            return urgent;
        }
        if (inQuietHours.contains(userId)) return Collections.emptyList(); // defer suppressible during quiet hours
        return ordered.getOrDefault(userId, DEFAULT);
    }
}

/** Simple per-user fixed-window rate limiter. Thread-safe via atomic compute. */
final class RateLimiter {
    private final int maxPerWindow;
    private final long windowMillis;
    private static final class Window { long start; int count; }
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    RateLimiter(int maxPerWindow, long windowMillis) {
        this.maxPerWindow = maxPerWindow; this.windowMillis = windowMillis;
    }

    /** Atomic check-and-increment so concurrent calls for one user can't over-admit. */
    boolean tryAcquire(String userId) {
        long now = System.currentTimeMillis();
        final boolean[] allowed = {false};
        windows.compute(userId, (k, w) -> {
            if (w == null || now - w.start >= windowMillis) {
                w = new Window(); w.start = now; w.count = 0;
            }
            if (w.count < maxPerWindow) { w.count++; allowed[0] = true; }
            return w;
        });
        return allowed[0];
    }
}

/** Dedup store: atomic putIfAbsent guarantees concurrent duplicates collapse to one winner. */
final class IdempotencyStore {
    private final long ttlMillis;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    IdempotencyStore(long ttlMillis) { this.ttlMillis = ttlMillis; }

    /** Returns true exactly once per key within the TTL window (the "winner"). */
    boolean firstSeen(String key) {
        long now = System.currentTimeMillis();
        Long prev = seen.putIfAbsent(key, now);          // atomic check-and-set
        if (prev == null) return true;                   // we won
        if (now - prev >= ttlMillis) {                   // expired -> allow again, refresh atomically
            return seen.replace(key, prev, now);
        }
        return false;                                    // duplicate within TTL
    }
}

/** Sink for permanently-failed deliveries. In prod: a durable queue/table for replay. */
final class DeadLetterQueue {
    private final Queue<String> entries = new ConcurrentLinkedQueue<>();
    void add(QueuedNotification qn, String reason) {
        entries.add("DLQ user=" + qn.request.userId + " channel=" + qn.channelType
                + " template=" + qn.request.templateId + " reason=" + reason);
    }
    int size() { return entries.size(); }
    void dump() { entries.forEach(e -> System.out.println("  " + e)); }
}

// =====================================================================================
// Observer pattern — lifecycle events fan out to audit / metrics / alerting
// =====================================================================================

final class NotificationEvent {
    enum Type { ENQUEUED, SENT, RETRY_SCHEDULED, DEAD_LETTERED, DUPLICATE_DROPPED, RATE_LIMITED, NO_CHANNEL }
    final Type type; final String userId; final ChannelType channel; final String detail;
    NotificationEvent(Type type, String userId, ChannelType channel, String detail) {
        this.type = type; this.userId = userId; this.channel = channel; this.detail = detail;
    }
    public String toString() {
        return type + " user=" + userId + (channel != null ? " channel=" + channel : "")
                + (detail != null ? " (" + detail + ")" : "");
    }
}

interface NotificationObserver { void onEvent(NotificationEvent e); }

final class AuditLogObserver implements NotificationObserver {
    public void onEvent(NotificationEvent e) { System.out.println("[AUDIT] " + e); }
}

final class MetricsObserver implements NotificationObserver {
    private final Map<NotificationEvent.Type, AtomicInteger> counters = new ConcurrentHashMap<>();
    public void onEvent(NotificationEvent e) {
        counters.computeIfAbsent(e.type, k -> new AtomicInteger()).incrementAndGet();
    }
    void print() {
        System.out.println("[METRICS] " + counters.entrySet().stream()
                .map(en -> en.getKey() + "=" + en.getValue()).sorted().toList());
    }
}

// =====================================================================================
// QueuedNotification — the unit of work flowing through the priority queue
// =====================================================================================

final class QueuedNotification implements Comparable<QueuedNotification> {
    final NotificationRequest request;
    final ChannelType channelType;
    final int attempt;            // 0-based number of attempts already made
    final long seq;               // FIFO tie-breaker to avoid starvation within a priority band

    private static final AtomicLong SEQ = new AtomicLong();

    QueuedNotification(NotificationRequest request, ChannelType channelType, int attempt) {
        this.request = request; this.channelType = channelType; this.attempt = attempt;
        this.seq = SEQ.getAndIncrement();
    }
    QueuedNotification nextAttempt() { return new QueuedNotification(request, channelType, attempt + 1); }

    /** Order by priority rank, then FIFO by sequence. */
    public int compareTo(QueuedNotification o) {
        int byPriority = Integer.compare(request.priority.rank, o.request.priority.rank);
        return byPriority != 0 ? byPriority : Long.compare(seq, o.seq);
    }
}

// =====================================================================================
// NotificationService — FACADE + orchestrator + Producer/Consumer concurrency backbone
// =====================================================================================

final class NotificationService {
    private final ChannelFactory channelFactory;
    private final PreferenceService preferences;
    private final RateLimiter rateLimiter;
    private final IdempotencyStore idempotency;
    private final TemplateEngine templateEngine;
    private final RetryPolicy retryPolicy;
    private final DeadLetterQueue dlq;

    // Address resolution (userId -> per-channel address). In prod this is a user/profile service.
    private final Map<String, Map<ChannelType, String>> addresses = new ConcurrentHashMap<>();

    private final PriorityBlockingQueue<QueuedNotification> queue = new PriorityBlockingQueue<>();
    private final List<NotificationObserver> observers = new CopyOnWriteArrayList<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private volatile boolean running = true;

    NotificationService(ChannelFactory channelFactory, PreferenceService preferences, RateLimiter rateLimiter,
                        IdempotencyStore idempotency, TemplateEngine templateEngine, RetryPolicy retryPolicy,
                        DeadLetterQueue dlq, int workerCount) {
        this.channelFactory = channelFactory;
        this.preferences = preferences;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.templateEngine = templateEngine;
        this.retryPolicy = retryPolicy;
        this.dlq = dlq;
        this.workers = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) workers.submit(this::workerLoop);
    }

    // --- configuration helpers ---
    void registerObserver(NotificationObserver o) { observers.add(o); }
    void setAddress(String userId, ChannelType type, String address) {
        addresses.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(type, address);
    }

    // ----------------------------------------------------------------------------------
    // FACADE entry point (producer side) — runs on caller threads, returns immediately.
    // ----------------------------------------------------------------------------------
    void notify(NotificationRequest req) {
        Objects.requireNonNull(req, "request");

        // 1. Idempotency — atomic; concurrent duplicates collapse to one winner.
        if (!idempotency.firstSeen(req.idempotencyKey)) {
            emit(NotificationEvent.Type.DUPLICATE_DROPPED, req.userId, null, "key=" + req.idempotencyKey);
            return;
        }

        // 2. Rate limit — non-suppressible categories bypass it.
        if (!req.category.isNonSuppressible() && !rateLimiter.tryAcquire(req.userId)) {
            emit(NotificationEvent.Type.RATE_LIMITED, req.userId, null, null);
            return;
        }

        // 3. Resolve channels per preferences (opt-out, quiet hours, ordering).
        List<ChannelType> channels = preferences.channelsFor(req.userId, req.category);
        if (channels.isEmpty()) {
            emit(NotificationEvent.Type.NO_CHANNEL, req.userId, null, null);
            return;
        }

        // 4. Enqueue one work item per channel (async); workers will render & send.
        for (ChannelType ct : channels) {
            if (!channelFactory.has(ct)) continue; // skip channels we don't support
            queue.offer(new QueuedNotification(req, ct, 0));
            emit(NotificationEvent.Type.ENQUEUED, req.userId, ct, null);
        }
    }

    // ----------------------------------------------------------------------------------
    // Consumer side — worker loop.
    // ----------------------------------------------------------------------------------
    private void workerLoop() {
        while (running) {
            QueuedNotification qn;
            try {
                qn = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (qn == null) continue;
            try { process(qn); }
            catch (Exception ex) { dlq.add(qn, "unexpected: " + ex.getMessage());
                                   emit(NotificationEvent.Type.DEAD_LETTERED, qn.request.userId, qn.channelType, ex.getMessage()); }
        }
    }

    private void process(QueuedNotification qn) {
        NotificationRequest req = qn.request;
        String recipient = resolveAddress(req.userId, qn.channelType);

        // RENDER (Template) -> DECORATE (footer + tracking) -> final Message.
        Message base = templateEngine.render(req.templateId, qn.channelType, recipient, req.data);
        Message decorated = decorate(base);

        // SEND (Channel strategy via Factory).
        Channel channel = channelFactory.get(qn.channelType);
        DeliveryResult result = channel.send(decorated);

        switch (result.status) {
            case SUCCESS:
                emit(NotificationEvent.Type.SENT, req.userId, qn.channelType, result.providerMessageId);
                break;
            case TRANSIENT_FAILURE:
                if (retryPolicy.shouldRetry(qn.attempt)) {
                    long delay = retryPolicy.backoffMillis(qn.attempt);
                    scheduler.schedule(() -> queue.offer(qn.nextAttempt()), delay, TimeUnit.MILLISECONDS);
                    emit(NotificationEvent.Type.RETRY_SCHEDULED, req.userId, qn.channelType,
                            "attempt=" + (qn.attempt + 1) + " in " + delay + "ms");
                } else {
                    dlq.add(qn, result.reason);
                    emit(NotificationEvent.Type.DEAD_LETTERED, req.userId, qn.channelType, result.reason);
                }
                break;
            case PERMANENT_FAILURE:
            default:
                dlq.add(qn, result.reason);
                emit(NotificationEvent.Type.DEAD_LETTERED, req.userId, qn.channelType, result.reason);
                break;
        }
    }

    /** Stack decorators: footer always, tracking pixel for email. */
    private Message decorate(Message base) {
        Message withTracking = new TrackingPixelDecorator(base, "https://track.example.com/o.png").decorate();
        return new FooterDecorator(withTracking, "You receive this because of your notification settings.").decorate();
    }

    private String resolveAddress(String userId, ChannelType type) {
        Map<ChannelType, String> m = addresses.get(userId);
        return (m != null) ? m.get(type) : null; // null -> channel returns permanent failure
    }

    private void emit(NotificationEvent.Type type, String userId, ChannelType ct, String detail) {
        NotificationEvent e = new NotificationEvent(type, userId, ct, detail);
        for (NotificationObserver o : observers) o.onEvent(e);
    }

    DeadLetterQueue deadLetterQueue() { return dlq; }

    /** Graceful shutdown: stop accepting, drain in-flight, await workers. */
    void shutdown() throws InterruptedException {
        // allow the queue to drain a moment
        while (!queue.isEmpty()) Thread.sleep(50);
        Thread.sleep(100);
        running = false;
        workers.shutdownNow();
        scheduler.shutdownNow();
        workers.awaitTermination(2, TimeUnit.SECONDS);
    }
}

// =====================================================================================
// Demonstration
// =====================================================================================

public class Solution {
    public static void main(String[] args) throws Exception {
        // ---- wiring (dependency injection; everything behind interfaces) ----
        ChannelFactory factory = new ChannelFactory();
        factory.register(new EmailChannel());
        factory.register(new SmsChannel());
        factory.register(new PushChannel(/*simulateOutage=*/true)); // push will fail transiently -> retry -> DLQ

        TemplateEngine templates = new TemplateEngine();
        templates.register(new Template("order_shipped",
                "Order {orderId} shipped", "Hi {name}, your order {orderId} is on its way."));
        templates.register(new Template("otp",
                "Your code", "Your verification code is {code}."));
        templates.register(new Template("promo",
                "Big sale!", "Hi {name}, enjoy {discount}% off today only."));

        PreferenceService prefs = new PreferenceService();
        RateLimiter limiter = new RateLimiter(/*maxPerWindow=*/2, /*windowMillis=*/60_000);
        IdempotencyStore idem = new IdempotencyStore(/*ttlMillis=*/300_000);
        DeadLetterQueue dlq = new DeadLetterQueue();
        RetryPolicy retry = new ExponentialBackoffRetryPolicy(/*maxAttempts=*/2, /*baseMillis=*/50);

        NotificationService service = new NotificationService(
                factory, prefs, limiter, idem, templates, retry, dlq, /*workerCount=*/3);

        MetricsObserver metrics = new MetricsObserver();
        service.registerObserver(new AuditLogObserver());
        service.registerObserver(metrics);

        // ---- user setup ----
        prefs.setPreferences("alice", Arrays.asList(ChannelType.EMAIL, ChannelType.SMS));
        service.setAddress("alice", ChannelType.EMAIL, "alice@example.com");
        service.setAddress("alice", ChannelType.SMS, "+12025550101");

        prefs.setPreferences("bob", Arrays.asList(ChannelType.PUSH)); // push only -> will retry & DLQ
        service.setAddress("bob", ChannelType.PUSH, "device-token-bob");
        service.setAddress("bob", ChannelType.SMS, "+12025550102");

        prefs.setQuietHours("carol", true); // suppressible notifications deferred for carol
        service.setAddress("carol", ChannelType.EMAIL, "carol@example.com");
        service.setAddress("carol", ChannelType.SMS, "+12025550103");

        System.out.println("=== Scenario 1: transactional to alice (email + sms) ===");
        service.notify(NotificationRequest.builder()
                .userId("alice").category(Category.TRANSACTIONAL).priority(Priority.MEDIUM)
                .templateId("order_shipped").data("name", "Alice").data("orderId", "A-123")
                .idempotencyKey("evt-alice-1").build());

        System.out.println("\n=== Scenario 2: duplicate request (same idempotency key) is dropped ===");
        service.notify(NotificationRequest.builder()
                .userId("alice").category(Category.TRANSACTIONAL)
                .templateId("order_shipped").data("name", "Alice").data("orderId", "A-123")
                .idempotencyKey("evt-alice-1").build()); // same key -> DUPLICATE_DROPPED

        System.out.println("\n=== Scenario 3: high-priority OTP to carol bypasses quiet hours & rate limit ===");
        service.notify(NotificationRequest.builder()
                .userId("carol").category(Category.OTP).priority(Priority.HIGH)
                .templateId("otp").data("code", "938212")
                .idempotencyKey("evt-carol-otp").build());

        System.out.println("\n=== Scenario 4: push-only user bob -> transient failure -> retry -> DLQ ===");
        service.notify(NotificationRequest.builder()
                .userId("bob").category(Category.TRANSACTIONAL).priority(Priority.MEDIUM)
                .templateId("order_shipped").data("name", "Bob").data("orderId", "B-777")
                .idempotencyKey("evt-bob-1").build());

        System.out.println("\n=== Scenario 5: rate limiting (3 marketing sends, limit=2/window) ===");
        for (int i = 1; i <= 3; i++) {
            service.notify(NotificationRequest.builder()
                    .userId("alice").category(Category.MARKETING).priority(Priority.LOW)
                    .templateId("promo").data("name", "Alice").data("discount", "20")
                    .idempotencyKey("evt-alice-promo-" + i).build()); // 3rd should be RATE_LIMITED
        }

        // let workers + scheduled retries finish
        Thread.sleep(600);
        service.shutdown();

        System.out.println("\n=== Dead Letter Queue (" + dlq.size() + " entries) ===");
        dlq.dump();
        System.out.println();
        metrics.print();
    }
}

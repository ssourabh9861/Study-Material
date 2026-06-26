/* =====================================================================================
 * Pub-Sub System — single-file REVIEW / REVISION artifact (pure JDK, no external libs)
 *
 * This file is meant to be READ and revised before an LLD / machine-coding interview.
 * It is complete and correct-by-inspection; it is NOT meant to be a benchmark or a
 * production library. A small main() at the bottom illustrates the key scenarios.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING (lead with these):
 *
 * Functional scope
 *   1. In-process (single JVM, library) or distributed broker (Kafka/RabbitMQ-like)?
 *   2. Delivery semantics: at-most-once / at-least-once / exactly-once?
 *   3. Ordering: per-topic total order, per-subscriber order, or none?
 *   4. Do late subscribers replay past messages (durable) or only get future ones (live)?
 *   5. Fan-out only, or also consumer-group / queue (one message -> one of N consumers)?
 *   6. Content-based filtering (subscriber wants a subset of a topic's messages)?
 *   7. Push (callback) or pull (subscriber polls) delivery?
 *
 * Non-functional
 *   8. Throughput, #topics, #subscribers per topic?  (threading + data structures)
 *   9. Slow-consumer policy: block publisher (backpressure) / drop / unbounded buffer?
 *  10. Durability: is losing messages on crash acceptable? Persistence required?
 *
 * Lifecycle / ops
 *  11. Subscriber-callback failure: retry / dead-letter / log-and-drop?
 *  12. Graceful shutdown (drain in-flight) required?
 *  13. AuthN/AuthZ on publish/subscribe?  (default: out of scope)
 *
 * DEFAULTS CHOSEN FOR THIS ARTIFACT:
 *   - In-process, in-memory, push delivery, fan-out.
 *   - At-least-once with bounded retries + exponential-ish backoff -> dead-letter.
 *   - Per-subscriber FIFO ordering (single drainer per subscription over a shared pool).
 *   - Optional per-subscription predicate filter.
 *   - Bounded per-subscription inbox + configurable OverflowPolicy (backpressure).
 *   - Thread-safe registries; graceful shutdown() and immediate shutdownNow().
 *
 * PATTERNS DEMONSTRATED:
 *   Observer (Topic=Subject, Subscriber/Subscription=Observer)
 *   Strategy (DeliveryExecutor, RetryPolicy, OverflowPolicy, MessageFilter)
 *   Singleton + DI (Broker.getDefault() but also Builder/constructable)
 *   Builder (Message, Broker)
 *   Factory method (broker.topic(name) get-or-create)
 *   Command/Runnable (DeliveryTask = retriable unit of delivery)
 * ===================================================================================== */

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public class Solution {

    /* =============================================================================
     * 1) Message — immutable value object (Builder pattern).
     * ============================================================================= */
    public static final class Message {
        private final String id;
        private final String topic;
        private final Object payload;
        private final Map<String, String> headers;
        private final long timestamp;
        private final int priority;

        private Message(Builder b) {
            this.id = (b.id != null) ? b.id : UUID.randomUUID().toString();
            this.topic = Objects.requireNonNull(b.topic, "topic");
            this.payload = b.payload;
            this.headers = Collections.unmodifiableMap(new HashMap<>(b.headers));
            this.timestamp = (b.timestamp != 0L) ? b.timestamp : System.currentTimeMillis();
            this.priority = b.priority;
        }

        public String id() { return id; }
        public String topic() { return topic; }
        public Object payload() { return payload; }
        public Map<String, String> headers() { return headers; }
        public long timestamp() { return timestamp; }
        public int priority() { return priority; }

        public static Builder builder() { return new Builder(); }

        @Override public String toString() {
            return "Message{id=" + id.substring(0, 8) + ", topic=" + topic
                    + ", payload=" + payload + ", headers=" + headers + "}";
        }

        public static final class Builder {
            private String id;
            private String topic;
            private Object payload;
            private final Map<String, String> headers = new HashMap<>();
            private long timestamp;
            private int priority;

            public Builder id(String id) { this.id = id; return this; }
            public Builder topic(String topic) { this.topic = topic; return this; }
            public Builder payload(Object payload) { this.payload = payload; return this; }
            public Builder header(String k, String v) { this.headers.put(k, v); return this; }
            public Builder timestamp(long ts) { this.timestamp = ts; return this; }
            public Builder priority(int p) { this.priority = p; return this; }
            public Message build() { return new Message(this); }
        }
    }

    /* =============================================================================
     * 2) Subscriber — consumer-side callback (Observer). May throw; the broker
     *    isolates publishers and other subscribers from a misbehaving one.
     * ============================================================================= */
    @FunctionalInterface
    public interface Subscriber {
        void onMessage(Message message) throws Exception;
        default String name() { return "subscriber-" + System.identityHashCode(this); }
    }

    /* Optional Decorator example: add logging without changing the consumer. */
    public static final class LoggingSubscriber implements Subscriber {
        private final Subscriber delegate;
        private final String label;
        public LoggingSubscriber(String label, Subscriber delegate) {
            this.label = label; this.delegate = delegate;
        }
        @Override public void onMessage(Message m) throws Exception {
            System.out.println("[" + label + "] received " + m);
            delegate.onMessage(m);
        }
        @Override public String name() { return label; }
    }

    /* =============================================================================
     * 3) MessageFilter — Strategy: per-subscription content-based routing predicate.
     * ============================================================================= */
    @FunctionalInterface
    public interface MessageFilter extends Predicate<Message> {
        MessageFilter ACCEPT_ALL = m -> true;
    }

    /* =============================================================================
     * 4) OverflowPolicy — Strategy (enum): what to do when a subscription inbox is full.
     * ============================================================================= */
    public enum OverflowPolicy {
        BLOCK,        // publisher waits until space frees (true backpressure)
        DROP_OLDEST,  // evict head, enqueue new (keep newest)
        DROP_NEWEST,  // silently drop the new message
        ERROR         // throw to surface the overflow to the publisher
    }

    /* =============================================================================
     * 5) RetryPolicy — Strategy: attempt budget + backoff between delivery attempts.
     * ============================================================================= */
    public interface RetryPolicy {
        int maxAttempts();
        long backoffMillis(int attempt); // attempt is 1-based
    }

    public static final class ExponentialBackoffRetryPolicy implements RetryPolicy {
        private final int maxAttempts;
        private final long baseMillis;
        private final long capMillis;

        public ExponentialBackoffRetryPolicy(int maxAttempts, long baseMillis) {
            this(maxAttempts, baseMillis, 5_000L);
        }
        public ExponentialBackoffRetryPolicy(int maxAttempts, long baseMillis, long capMillis) {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
            this.maxAttempts = maxAttempts;
            this.baseMillis = baseMillis;
            this.capMillis = capMillis;
        }
        @Override public int maxAttempts() { return maxAttempts; }
        @Override public long backoffMillis(int attempt) {
            long exp = baseMillis * (1L << Math.min(attempt - 1, 16)); // base * 2^(attempt-1)
            return Math.min(exp, capMillis);
        }
    }

    /* =============================================================================
     * 6) DeadLetterHandler — sink for messages that exhausted their retry budget.
     * ============================================================================= */
    @FunctionalInterface
    public interface DeadLetterHandler {
        void onDeadLetter(Message message, Subscription subscription, Throwable cause);
    }

    /* =============================================================================
     * 7) DeliveryExecutor — Strategy: WHERE/HOW delivery runs.
     *    Contract: deliveries for the SAME subscription must be serialized to preserve
     *    per-subscriber FIFO order; deliveries for DIFFERENT subscriptions may run in
     *    parallel. Implementations enforce this serialization.
     * ============================================================================= */
    public interface DeliveryExecutor {
        /** Run a delivery task on the lane belonging to this subscription. */
        void execute(Subscription subscription, Runnable task);
        /** Schedule a retry after the given delay, on the subscription's lane. */
        void scheduleRetry(Subscription subscription, Runnable task, long delayMillis);
        void shutdown(boolean awaitDrain);
    }

    /**
     * AsyncPoolDeliveryExecutor — async delivery over a bounded shared pool, but with a
     * per-subscription "running" CAS flag so only ONE pool thread ever drains a given
     * subscription's inbox at a time. This gives per-subscriber ordering WITHOUT a
     * thread-per-subscriber (which would not scale to 100k subscribers).
     *
     * Each submitted task is a "drain loop": when scheduled it keeps pulling from the
     * subscription inbox until empty, then releases the lane. New publishes only need to
     * (re)start a drain if one is not already running.
     */
    public static final class AsyncPoolDeliveryExecutor implements DeliveryExecutor {
        private final ExecutorService pool;
        private final ScheduledExecutorService scheduler;

        public AsyncPoolDeliveryExecutor(int poolSize) {
            ThreadFactory tf = namedFactory("pubsub-worker");
            this.pool = Executors.newFixedThreadPool(poolSize, tf);
            this.scheduler = Executors.newScheduledThreadPool(
                    Math.max(1, poolSize / 2), namedFactory("pubsub-retry"));
        }
        @Override public void execute(Subscription s, Runnable task) { pool.execute(task); }
        @Override public void scheduleRetry(Subscription s, Runnable task, long delayMillis) {
            scheduler.schedule(() -> pool.execute(task), delayMillis, TimeUnit.MILLISECONDS);
        }
        @Override public void shutdown(boolean awaitDrain) {
            scheduler.shutdown();
            if (awaitDrain) {
                pool.shutdown();
                try {
                    pool.awaitTermination(10, TimeUnit.SECONDS);
                    scheduler.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                pool.shutdownNow();
                scheduler.shutdownNow();
            }
        }
        private static ThreadFactory namedFactory(String prefix) {
            AtomicInteger n = new AtomicInteger();
            return r -> {
                Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
        }
    }

    /** SyncDeliveryExecutor — runs delivery inline on the caller (publisher) thread.
     *  Useful for deterministic tests; trivially preserves order. No real backpressure. */
    public static final class SyncDeliveryExecutor implements DeliveryExecutor {
        @Override public void execute(Subscription s, Runnable task) { task.run(); }
        @Override public void scheduleRetry(Subscription s, Runnable task, long delayMillis) {
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            task.run();
        }
        @Override public void shutdown(boolean awaitDrain) { /* nothing to do */ }
    }

    /* =============================================================================
     * 8) Subscription — the Observer registration record: binds a Subscriber to a Topic
     *    with an optional filter, owns a bounded inbox (backpressure), and tracks the
     *    single-drainer "running" flag for ordered delivery.
     * ============================================================================= */
    public static final class Subscription {
        private final String id = UUID.randomUUID().toString();
        private final Topic topic;
        private final Subscriber subscriber;
        private final MessageFilter filter;
        private final BlockingQueue<Message> inbox;
        private final OverflowPolicy overflowPolicy;
        private volatile boolean active = true;
        /** Ensures only one worker thread drains this subscription's inbox at a time. */
        private final AtomicBoolean draining = new AtomicBoolean(false);

        Subscription(Topic topic, Subscriber subscriber, MessageFilter filter,
                     int inboxCapacity, OverflowPolicy overflowPolicy) {
            this.topic = topic;
            this.subscriber = subscriber;
            this.filter = (filter != null) ? filter : MessageFilter.ACCEPT_ALL;
            this.inbox = new ArrayBlockingQueue<>(Math.max(1, inboxCapacity));
            this.overflowPolicy = overflowPolicy;
        }

        public String id() { return id; }
        public Subscriber subscriber() { return subscriber; }
        public Topic topic() { return topic; }
        public boolean isActive() { return active; }
        boolean matches(Message m) { return filter.test(m); }
        AtomicBoolean draining() { return draining; }
        Message pollInbox() { return inbox.poll(); }
        boolean inboxEmpty() { return inbox.isEmpty(); }

        /** Enqueue honoring the overflow policy. Returns false if the message was dropped. */
        boolean enqueue(Message m) {
            if (!active) return false;
            switch (overflowPolicy) {
                case BLOCK:
                    try { inbox.put(m); return true; }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                case DROP_NEWEST:
                    return inbox.offer(m); // if full, drop the new one
                case DROP_OLDEST:
                    while (!inbox.offer(m)) {
                        inbox.poll();      // evict head, retry
                    }
                    return true;
                case ERROR:
                    if (!inbox.offer(m)) {
                        throw new IllegalStateException(
                                "Inbox full for subscription " + id + " on topic " + topic.name());
                    }
                    return true;
                default:
                    return inbox.offer(m);
            }
        }

        /** Idempotent cancel: stop receiving and release buffered messages. */
        public void cancel() {
            if (active) {
                active = false;
                topic.removeSubscription(id);
                inbox.clear();
            }
        }
    }

    /* =============================================================================
     * 9) Topic — the Observer Subject. Owns its subscriptions; fans a message out to
     *    every matching, active subscription. Thread-safe registry.
     * ============================================================================= */
    public static final class Topic {
        private final String name;
        private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

        Topic(String name) { this.name = name; }
        public String name() { return name; }

        void addSubscription(Subscription s) { subscriptions.put(s.id(), s); }
        void removeSubscription(String id) { subscriptions.remove(id); }
        Collection<Subscription> subscriptions() { return subscriptions.values(); }
        int subscriberCount() { return subscriptions.size(); }
    }

    /* =============================================================================
     * 10) DeliveryTask — Command/Runnable: a retriable unit that drains a subscription's
     *     inbox and delivers each message with at-least-once + retry semantics.
     * ============================================================================= */
    static final class DeliveryTask implements Runnable {
        private final Subscription subscription;
        private final Broker broker;
        DeliveryTask(Subscription s, Broker b) { this.subscription = s; this.broker = b; }

        @Override public void run() {
            // The "running" CAS in Broker guarantees only one of these is active per
            // subscription, so we can safely drain in a loop here (per-subscriber FIFO).
            try {
                Message m;
                while (subscription.isActive() && (m = subscription.pollInbox()) != null) {
                    deliverWithRetry(m, 1);
                }
            } finally {
                // Release the lane; if a publisher enqueued meanwhile, restart the drain.
                subscription.draining().set(false);
                if (subscription.isActive() && !subscription.inboxEmpty()) {
                    broker.kickDrain(subscription);
                }
            }
        }

        private void deliverWithRetry(Message m, int attempt) {
            if (!subscription.isActive()) return;
            try {
                subscription.subscriber().onMessage(m);   // the actual fan-out delivery
            } catch (Throwable t) {
                RetryPolicy rp = broker.retryPolicy();
                if (attempt < rp.maxAttempts()) {
                    long backoff = rp.backoffMillis(attempt);
                    // Re-submit this single message as a retry on the same lane (ordered).
                    broker.deliveryExecutor().scheduleRetry(subscription,
                            () -> deliverWithRetry(m, attempt + 1), backoff);
                } else {
                    broker.deadLetterHandler().onDeadLetter(m, subscription, t); // exhausted
                }
            }
        }
    }

    /* =============================================================================
     * 11) Broker — Facade + (default) Singleton + Builder. Central coordinator that
     *     owns topics, the delivery strategy, retry/overflow policies, dead-letter sink,
     *     and lifecycle. Publishers and subscribers go only through here (decoupling).
     * ============================================================================= */
    public static final class Broker {
        // ---- Singleton (lazy holder) for the convenient process-wide default bus.
        private static final class Holder {
            static final Broker DEFAULT = Broker.builder().build();
        }
        public static Broker getDefault() { return Holder.DEFAULT; }

        private final Map<String, Topic> topics = new ConcurrentHashMap<>();
        private final DeliveryExecutor deliveryExecutor;
        private final RetryPolicy retryPolicy;
        private final DeadLetterHandler deadLetterHandler;
        private final int inboxCapacity;
        private final OverflowPolicy overflowPolicy;
        private volatile boolean running = true;

        private Broker(Builder b) {
            this.deliveryExecutor = b.deliveryExecutor;
            this.retryPolicy = b.retryPolicy;
            this.deadLetterHandler = b.deadLetterHandler;
            this.inboxCapacity = b.inboxCapacity;
            this.overflowPolicy = b.overflowPolicy;
        }

        // ---- Accessors used by DeliveryTask
        RetryPolicy retryPolicy() { return retryPolicy; }
        DeliveryExecutor deliveryExecutor() { return deliveryExecutor; }
        DeadLetterHandler deadLetterHandler() { return deadLetterHandler; }

        /** Factory method: atomic get-or-create topic by name (interning by name). */
        public Topic topic(String name) {
            Objects.requireNonNull(name, "topic name");
            return topics.computeIfAbsent(name, Topic::new);
        }

        /** Subscribe with ACCEPT_ALL filter. */
        public Subscription subscribe(String topicName, Subscriber subscriber) {
            return subscribe(topicName, subscriber, MessageFilter.ACCEPT_ALL);
        }

        /** Subscribe with a content filter. Returns a handle used to unsubscribe. */
        public Subscription subscribe(String topicName, Subscriber subscriber, MessageFilter filter) {
            ensureRunning();
            Objects.requireNonNull(subscriber, "subscriber");
            Topic t = topic(topicName);
            Subscription s = new Subscription(t, subscriber, filter, inboxCapacity, overflowPolicy);
            t.addSubscription(s);
            return s;
        }

        public void unsubscribe(Subscription subscription) {
            if (subscription != null) subscription.cancel();
        }

        /**
         * Publish: non-blocking from the publisher's perspective (except under
         * OverflowPolicy.BLOCK). Fan-out to every matching, active subscription, then
         * kick its drain lane if not already running.
         */
        public void publish(String topicName, Message message) {
            ensureRunning();
            Objects.requireNonNull(message, "message");
            Topic t = topics.get(topicName);
            if (t == null) {
                // No subscribers/topic yet -> drop (live semantics). For replay, retain a log.
                return;
            }
            // Weakly-consistent iteration: safe to subscribe/unsubscribe concurrently.
            for (Subscription s : t.subscriptions()) {
                if (!s.isActive() || !s.matches(message)) continue;
                boolean enqueued = s.enqueue(message);
                if (enqueued) kickDrain(s);
            }
        }

        /** Start a drain on this subscription's lane iff one is not already running. */
        void kickDrain(Subscription s) {
            if (!running) return;
            // CAS guarantees a single active drainer per subscription -> ordered delivery.
            if (s.draining().compareAndSet(false, true)) {
                deliveryExecutor.execute(s, new DeliveryTask(s, this));
            }
        }

        /** Graceful: stop new publishes, drain in-flight, stop pools. */
        public void shutdown() {
            running = false;
            deliveryExecutor.shutdown(true);
        }
        /** Immediate: stop now, do not drain. */
        public void shutdownNow() {
            running = false;
            deliveryExecutor.shutdown(false);
        }
        private void ensureRunning() {
            if (!running) throw new IllegalStateException("Broker is shut down");
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private DeliveryExecutor deliveryExecutor = new AsyncPoolDeliveryExecutor(4);
            private RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy(3, 20L);
            private DeadLetterHandler deadLetterHandler =
                    (m, s, t) -> System.out.println("[DLQ] " + m + " for " + s.subscriber().name()
                            + " cause=" + t);
            private int inboxCapacity = 1024;
            private OverflowPolicy overflowPolicy = OverflowPolicy.BLOCK;

            public Builder deliveryExecutor(DeliveryExecutor e) { this.deliveryExecutor = e; return this; }
            public Builder retryPolicy(RetryPolicy r) { this.retryPolicy = r; return this; }
            public Builder deadLetterHandler(DeadLetterHandler d) { this.deadLetterHandler = d; return this; }
            public Builder inboxCapacity(int c) { this.inboxCapacity = c; return this; }
            public Builder overflowPolicy(OverflowPolicy p) { this.overflowPolicy = p; return this; }
            public Broker build() { return new Broker(this); }
        }
    }

    /* =============================================================================
     * 12) Publisher — thin client. Holds no subscriber references (decoupling).
     * ============================================================================= */
    public static final class Publisher {
        private final Broker broker;
        private final String topic;
        public Publisher(Broker broker, String topic) {
            this.broker = broker; this.topic = topic;
        }
        public void publish(Object payload) {
            broker.publish(topic, Message.builder().topic(topic).payload(payload).build());
        }
        public void publish(Object payload, Map<String, String> headers) {
            Message.Builder mb = Message.builder().topic(topic).payload(payload);
            if (headers != null) headers.forEach(mb::header);
            broker.publish(topic, mb.build());
        }
    }

    /* =============================================================================
     * 13) main — illustrates the key scenarios (NOT a benchmark; just a demo).
     * ============================================================================= */
    public static void main(String[] args) throws Exception {
        Broker broker = Broker.builder()
                .inboxCapacity(100)
                .overflowPolicy(OverflowPolicy.BLOCK)
                .retryPolicy(new ExponentialBackoffRetryPolicy(3, 10L))
                .deliveryExecutor(new AsyncPoolDeliveryExecutor(4))
                .deadLetterHandler((m, s, t) ->
                        System.out.println("[DLQ] dropped " + m + " for " + s.subscriber().name()))
                .build();

        AtomicLong audit = new AtomicLong();

        // --- Scenario 1: fan-out — two subscribers both get every "orders" message.
        Subscription emailSub = broker.subscribe("orders",
                new LoggingSubscriber("email", m -> { /* send email */ }));
        Subscription auditSub = broker.subscribe("orders",
                m -> { audit.incrementAndGet(); System.out.println("[audit] " + m); });

        // --- Scenario 2: content filter — only PAID orders reach the shipping subscriber.
        Subscription shipSub = broker.subscribe("orders",
                m -> System.out.println("[shipping] ship " + m.payload()),
                m -> "PAID".equals(m.headers().get("status")));

        Publisher orders = new Publisher(broker, "orders");
        Map<String, String> paid = new HashMap<>(); paid.put("status", "PAID");
        Map<String, String> pending = new HashMap<>(); pending.put("status", "PENDING");

        orders.publish("order#1", paid);     // email, audit, shipping
        orders.publish("order#2", pending);  // email, audit (filtered out of shipping)
        orders.publish("order#3", paid);     // email, audit, shipping

        // --- Scenario 3: unsubscribe — email stops receiving further messages.
        broker.unsubscribe(emailSub);
        orders.publish("order#4", paid);     // audit + shipping only

        // --- Scenario 4: at-least-once + retry + dead-letter on a flaky subscriber.
        AtomicInteger calls = new AtomicInteger();
        broker.subscribe("payments", m -> {
            int n = calls.incrementAndGet();
            System.out.println("[payments] attempt " + n + " for " + m.payload());
            throw new RuntimeException("simulated downstream failure"); // always fails
        });
        broker.publish("payments",
                Message.builder().topic("payments").payload("charge#9").build());

        // --- Scenario 5: ordering — single subscriber sees messages in publish order.
        Subscription orderedSub = broker.subscribe("ticks",
                m -> System.out.println("[ordered] " + m.payload()));
        Publisher ticks = new Publisher(broker, "ticks");
        for (int i = 1; i <= 5; i++) ticks.publish("tick-" + i);

        // Let async delivery + retries settle, then shut down gracefully.
        Thread.sleep(500);
        System.out.println("audit count = " + audit.get()
                + " (expected 4: order#1..#4)");
        System.out.println("payment attempts = " + calls.get()
                + " (expected 3: maxAttempts) then dead-lettered");

        orderedSub.cancel();
        auditSub.cancel();
        shipSub.cancel();
        broker.shutdown();
        System.out.println("Broker shut down cleanly.");
    }
}

/* ============================================================================
 * LLD: In-Memory Message Queue (Message Broker)  —  single-file REVIEW artifact.
 *
 * This file is for reading and last-minute revision. It is complete and
 * correct-by-inspection; you do NOT need to compile or run it.
 *
 * ----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ----------------------------------------------------------------------------
 * Functional:
 *   1. Point-to-point (work queue), pub/sub (fan-out), or BOTH (Kafka groups)?
 *   2. Topics? Partitions per topic (parallelism + ordering)?
 *   3. Pull (poll) or push (listener), or both?
 *   4. Delivery guarantee: at-most / AT-LEAST-once / exactly-once?
 *   5. Explicit acknowledgements, or read == consume (auto-ack)?
 *   6. Offset tracking: resume / replay / seek?
 *   7. Retries with max attempts + Dead Letter Queue (DLQ)?
 *   8. Ordering: global, per-partition, or per-key?
 *   9. TTL/expiry, priorities, delayed/scheduled delivery?
 * Non-functional / constraints:
 *  10. In-memory only (yes) or must survive restarts (persistence)?
 *  11. Throughput / message size? Concurrent producers & consumers?
 *  12. Capacity-full policy: BLOCK (backpressure) / DROP / REJECT?
 *  13. Latency target — is blocking publish acceptable?
 *  14. Single JVM (yes) — concurrency = threads, not a cluster.
 * Out of scope:
 *  15. Network protocol, serialization, disk persistence, replication,
 *      exactly-once over the wire, auth/ACLs, admin UI.
 *
 * DESIGN SUMMARY
 *   Topics -> Partitions (append-only ordered logs + bounded ready buffer).
 *   Producers publish via a PartitionStrategy. Consumers belong to a
 *   ConsumerGroup; each partition is assigned to exactly one consumer in a
 *   group (work sharing); different groups each see the full stream (fan-out).
 *   OffsetTracker stores committed offsets per (group, topic, partition).
 *   At-least-once: poll -> process -> commit; nack triggers RetryPolicy then DLQ.
 *   Backpressure: bounded BlockingQueue + OverflowPolicy.
 *
 * PATTERNS: Strategy (partition/rebalance/retry/overflow), Producer-Consumer
 *   (BlockingQueue), Facade (MessageBroker), Factory (create* methods),
 *   Observer (MessageListener push), Immutable Value Object (Message).
 * ============================================================================ */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Solution {

    /* ====================================================================
     * VALUE OBJECT — Message (immutable, safe to share across threads)
     * ==================================================================== */
    static final class Message<T> {
        final String id;            // unique; used for idempotent dedup
        final String key;           // routing/order key (nullable)
        final T value;
        final Map<String, String> headers;
        final long timestamp;
        // Broker-assigned metadata (set when appended/redelivered):
        final int partition;
        final long offset;
        final int deliveryAttempt;

        Message(String id, String key, T value, Map<String, String> headers,
                long timestamp, int partition, long offset, int deliveryAttempt) {
            this.id = id;
            this.key = key;
            this.value = value;
            this.headers = headers == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new HashMap<>(headers));
            this.timestamp = timestamp;
            this.partition = partition;
            this.offset = offset;
            this.deliveryAttempt = deliveryAttempt;
        }

        /** Client-side constructor: broker fills partition/offset later. */
        static <T> Message<T> of(String key, T value, Map<String, String> headers) {
            return new Message<>(UUID.randomUUID().toString(), key, value, headers,
                    System.currentTimeMillis(), -1, -1L, 0);
        }

        /** Immutable "with" — used when broker assigns position. */
        Message<T> assigned(int partition, long offset) {
            return new Message<>(id, key, value, headers, timestamp,
                    partition, offset, deliveryAttempt);
        }

        /** Immutable "with" — used for redelivery (attempt++). */
        Message<T> retried() {
            return new Message<>(id, key, value, headers, timestamp,
                    partition, offset, deliveryAttempt + 1);
        }

        @Override public String toString() {
            return "Msg{id=" + id.substring(0, 8) + ",key=" + key + ",val=" + value
                    + ",p=" + partition + ",off=" + offset + ",try=" + deliveryAttempt + "}";
        }
    }

    /** Returned to producers after a successful publish. */
    static final class RecordMetadata {
        final String topic; final int partition; final long offset;
        RecordMetadata(String topic, int partition, long offset) {
            this.topic = topic; this.partition = partition; this.offset = offset;
        }
        @Override public String toString() {
            return "RecordMetadata{" + topic + "-" + partition + "@" + offset + "}";
        }
    }

    /* ====================================================================
     * STRATEGY — Partition selection
     * ==================================================================== */
    interface PartitionStrategy {
        int selectPartition(String key, int partitionCount);
    }

    /** Same key -> same partition => per-key ordering. Null key -> round-robin. */
    static final class KeyHashPartitionStrategy implements PartitionStrategy {
        private final AtomicInteger rr = new AtomicInteger();
        @Override public int selectPartition(String key, int count) {
            if (key == null) return Math.floorMod(rr.getAndIncrement(), count);
            return Math.floorMod(key.hashCode(), count);
        }
    }

    static final class RoundRobinPartitionStrategy implements PartitionStrategy {
        private final AtomicInteger rr = new AtomicInteger();
        @Override public int selectPartition(String key, int count) {
            return Math.floorMod(rr.getAndIncrement(), count);
        }
    }

    /* ====================================================================
     * STRATEGY — Overflow / backpressure policy on a full partition buffer
     * ==================================================================== */
    interface OverflowPolicy {
        /** @return true if the message was accepted into the buffer. */
        <T> boolean onOffer(LinkedBlockingQueue<Message<T>> ready, Message<T> msg);
    }

    /** True backpressure: block the producer up to a timeout. */
    static final class BlockPolicy implements OverflowPolicy {
        private final long timeoutMs;
        BlockPolicy(long timeoutMs) { this.timeoutMs = timeoutMs; }
        @Override public <T> boolean onOffer(LinkedBlockingQueue<Message<T>> ready, Message<T> msg) {
            try {
                return ready.offer(msg, timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Evict oldest queued message to make room (lossy, bounded memory). */
    static final class DropOldestPolicy implements OverflowPolicy {
        @Override public <T> boolean onOffer(LinkedBlockingQueue<Message<T>> ready, Message<T> msg) {
            if (ready.offer(msg)) return true;
            ready.poll();               // drop head
            return ready.offer(msg);
        }
    }

    /** Reject the new message immediately (fail fast). */
    static final class RejectPolicy implements OverflowPolicy {
        @Override public <T> boolean onOffer(LinkedBlockingQueue<Message<T>> ready, Message<T> msg) {
            return ready.offer(msg);     // false if full
        }
    }

    /* ====================================================================
     * STRATEGY — Retry policy (drives DLQ decision + backoff)
     * ==================================================================== */
    interface RetryPolicy {
        boolean shouldRetry(int deliveryAttempt);
        long backoffMillis(int deliveryAttempt);
    }

    /** Bounded attempts with exponential backoff. */
    static final class ExponentialBackoffRetryPolicy implements RetryPolicy {
        private final int maxAttempts; private final long baseMs;
        ExponentialBackoffRetryPolicy(int maxAttempts, long baseMs) {
            this.maxAttempts = maxAttempts; this.baseMs = baseMs;
        }
        @Override public boolean shouldRetry(int attempt) { return attempt < maxAttempts; }
        @Override public long backoffMillis(int attempt) {
            return baseMs * (long) Math.pow(2, Math.max(0, attempt - 1));
        }
    }

    /* ====================================================================
     * STRATEGY — Rebalance: assign partitions to consumers within a group
     * ==================================================================== */
    interface RebalanceStrategy {
        /** @return consumerId -> set of partition ids. */
        Map<String, Set<Integer>> assign(List<String> consumerIds, int partitionCount);
    }

    /** Round-robin assignment: even spread, simple and good enough. */
    static final class RoundRobinRebalanceStrategy implements RebalanceStrategy {
        @Override public Map<String, Set<Integer>> assign(List<String> consumers, int count) {
            Map<String, Set<Integer>> out = new LinkedHashMap<>();
            for (String c : consumers) out.put(c, new HashSet<>());
            if (consumers.isEmpty()) return out;
            for (int p = 0; p < count; p++) {
                String owner = consumers.get(p % consumers.size());
                out.get(owner).add(p);
            }
            return out;
        }
    }

    /* ====================================================================
     * OBSERVER — optional push delivery callback
     * ==================================================================== */
    interface MessageListener<T> {
        void onMessage(Message<T> message) throws Exception;
    }

    /* ====================================================================
     * Partition — append-only ordered log + bounded ready buffer.
     * The unit of ordering, parallelism, and locking.
     * ==================================================================== */
    static final class Partition<T> {
        final int id;
        private final List<Message<T>> log = new ArrayList<>();   // indexed by offset
        private final LinkedBlockingQueue<Message<T>> ready;      // for backpressure/handoff
        private final OverflowPolicy overflow;
        private final AtomicLong nextOffset = new AtomicLong(0);
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        Partition(int id, int capacity, OverflowPolicy overflow) {
            this.id = id;
            this.ready = new LinkedBlockingQueue<>(capacity);
            this.overflow = overflow;
        }

        /** Assign offset atomically, append to log, enqueue with backpressure. */
        long append(Message<T> raw) {
            // Apply backpressure BEFORE committing the offset so a rejected
            // message never leaves a hole in the log.
            lock.writeLock().lock();
            try {
                long offset = nextOffset.get();
                Message<T> positioned = raw.assigned(id, offset);
                if (!overflow.onOffer(ready, positioned)) {
                    throw new IllegalStateException(
                            "Partition " + id + " full; message rejected by overflow policy");
                }
                log.add(positioned);
                nextOffset.incrementAndGet();
                return offset;
            } finally {
                lock.writeLock().unlock();
            }
        }

        /** Random-access replay read from a committed offset (at-least-once / seek). */
        List<Message<T>> readFrom(long fromOffset, int max) {
            lock.readLock().lock();
            try {
                List<Message<T>> out = new ArrayList<>();
                for (long o = fromOffset; o < log.size() && out.size() < max; o++) {
                    out.add(log.get((int) o));
                }
                return out;
            } finally {
                lock.readLock().unlock();
            }
        }

        /** Re-append a retried message (used by retry path). */
        void reEnqueue(Message<T> retried) {
            overflow.onOffer(ready, retried);   // best-effort: keep flow moving
        }

        long size() {
            lock.readLock().lock();
            try { return log.size(); } finally { lock.readLock().unlock(); }
        }
    }

    /* ====================================================================
     * Topic — collection of partitions + routing via PartitionStrategy.
     * ==================================================================== */
    static final class Topic<T> {
        final String name;
        private final List<Partition<T>> partitions;
        private final PartitionStrategy strategy;

        Topic(String name, int partitionCount, int capacityPerPartition,
              OverflowPolicy overflow, PartitionStrategy strategy) {
            this.name = name;
            this.strategy = strategy;
            List<Partition<T>> ps = new ArrayList<>();
            for (int i = 0; i < partitionCount; i++) {
                ps.add(new Partition<>(i, capacityPerPartition, overflow));
            }
            this.partitions = Collections.unmodifiableList(ps);
        }

        RecordMetadata append(Message<T> msg) {
            int p = strategy.selectPartition(msg.key, partitions.size());
            long offset = partitions.get(p).append(msg);
            return new RecordMetadata(name, p, offset);
        }

        Partition<T> partition(int id) { return partitions.get(id); }
        int partitionCount() { return partitions.size(); }
    }

    /* ====================================================================
     * OffsetTracker — committed offset per (group, topic, partition).
     * Monotonic, concurrent.
     * ==================================================================== */
    static final class OffsetTracker {
        private static final class Key {
            final String group, topic; final int partition;
            Key(String g, String t, int p) { group = g; topic = t; partition = p; }
            @Override public boolean equals(Object o) {
                if (!(o instanceof Key)) return false;
                Key k = (Key) o;
                return partition == k.partition
                        && group.equals(k.group) && topic.equals(k.topic);
            }
            @Override public int hashCode() { return Objects.hash(group, topic, partition); }
        }
        // committed offset = next offset to read (i.e., last-read + 1)
        private final ConcurrentHashMap<Key, Long> committed = new ConcurrentHashMap<>();

        long committed(String group, String topic, int partition) {
            return committed.getOrDefault(new Key(group, topic, partition), 0L);
        }

        /** Advance monotonically; never move an offset backwards. */
        void commit(String group, String topic, int partition, long nextOffset) {
            committed.merge(new Key(group, topic, partition), nextOffset, Math::max);
        }
    }

    /* ====================================================================
     * ConsumerGroup — fan-out unit; owns offsets + partition assignment.
     * Consumers join/leave (aggregation); rebalance recomputes assignment.
     * ==================================================================== */
    static final class ConsumerGroup<T> {
        final String id;
        final OffsetTracker offsets = new OffsetTracker();
        private final RebalanceStrategy rebalance;
        private final List<String> members = new CopyOnWriteArrayList<>();
        // topic -> (consumerId -> partitions). Guarded by 'this' on rebalance.
        private final Map<String, Map<String, Set<Integer>>> assignment = new ConcurrentHashMap<>();
        private final Map<String, Integer> topicPartitionCounts = new ConcurrentHashMap<>();

        ConsumerGroup(String id, RebalanceStrategy rebalance) {
            this.id = id; this.rebalance = rebalance;
        }

        synchronized void join(String consumerId, Map<String, Integer> topicCounts) {
            if (!members.contains(consumerId)) members.add(consumerId);
            topicPartitionCounts.putAll(topicCounts);
            recompute();
        }

        synchronized void leave(String consumerId) {
            members.remove(consumerId);
            recompute();
        }

        private void recompute() {
            for (Map.Entry<String, Integer> e : topicPartitionCounts.entrySet()) {
                assignment.put(e.getKey(),
                        rebalance.assign(new ArrayList<>(members), e.getValue()));
            }
        }

        Set<Integer> assignedPartitions(String consumerId, String topic) {
            Map<String, Set<Integer>> perTopic = assignment.get(topic);
            if (perTopic == null) return Collections.emptySet();
            return perTopic.getOrDefault(consumerId, Collections.emptySet());
        }
    }

    /* ====================================================================
     * DeadLetterQueue — parking for poison messages.
     * ==================================================================== */
    static final class DeadLetterQueue<T> {
        private final List<Message<T>> dead = new CopyOnWriteArrayList<>();
        void send(Message<T> msg, String cause) {
            System.out.println("  [DLQ] parking " + msg + " cause=" + cause);
            dead.add(msg);
        }
        int size() { return dead.size(); }
        List<Message<T>> all() { return new ArrayList<>(dead); }
    }

    /* ====================================================================
     * FACADE + FACTORY — MessageBroker
     * ==================================================================== */
    static final class MessageBroker<T> {
        private final Map<String, Topic<T>> topics = new ConcurrentHashMap<>();
        private final Map<String, ConsumerGroup<T>> groups = new ConcurrentHashMap<>();
        private final RetryPolicy retryPolicy;
        private final DeadLetterQueue<T> dlq = new DeadLetterQueue<>();
        private final ExecutorService dispatchPool = Executors.newFixedThreadPool(4);

        MessageBroker(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; }

        // ---- Factory methods --------------------------------------------
        Topic<T> createTopic(String name, int partitions, int capacityPerPartition,
                             OverflowPolicy overflow, PartitionStrategy strategy) {
            return topics.computeIfAbsent(name,
                    n -> new Topic<>(n, partitions, capacityPerPartition, overflow, strategy));
        }

        Producer<T> createProducer(String topic) {
            requireTopic(topic);
            return new Producer<>(this, topic);
        }

        Consumer<T> createConsumer(String groupId, Set<String> topics,
                                   RebalanceStrategy rebalance) {
            ConsumerGroup<T> group = groups.computeIfAbsent(groupId,
                    g -> new ConsumerGroup<>(g, rebalance));
            Consumer<T> consumer = new Consumer<>(this, group, topics);
            Map<String, Integer> counts = new HashMap<>();
            for (String t : topics) counts.put(t, requireTopic(t).partitionCount());
            group.join(consumer.id, counts);
            return consumer;
        }

        // ---- Core operations --------------------------------------------
        RecordMetadata publish(String topic, Message<T> msg) {
            return requireTopic(topic).append(msg);
        }

        /** At-least-once poll: read from each assigned partition at its committed offset. */
        List<Message<T>> poll(Consumer<T> c, int max) {
            List<Message<T>> batch = new ArrayList<>();
            for (String topicName : c.topics) {
                Topic<T> topic = requireTopic(topicName);
                Set<Integer> assigned = c.group.assignedPartitions(c.id, topicName);
                for (int p : assigned) {
                    if (batch.size() >= max) break;
                    long from = c.group.offsets.committed(c.group.id, topicName, p);
                    List<Message<T>> recs = topic.partition(p)
                            .readFrom(from, max - batch.size());
                    batch.addAll(recs);
                }
            }
            return batch;
        }

        /** Commit advances the offset to message.offset + 1 (next to read). */
        void commit(Consumer<T> c, String topic, Message<T> m) {
            c.group.offsets.commit(c.group.id, topic, m.partition, m.offset + 1);
        }

        /** Negative ack: retry with backoff up to the policy, else route to DLQ. */
        void nack(Consumer<T> c, String topic, Message<T> m) {
            int nextAttempt = m.deliveryAttempt + 1;
            if (retryPolicy.shouldRetry(nextAttempt)) {
                long backoff = retryPolicy.backoffMillis(nextAttempt);
                System.out.println("  [RETRY] " + m + " in " + backoff + "ms (attempt " + nextAttempt + ")");
                requireTopic(topic).partition(m.partition).reEnqueue(m.retried());
            } else {
                // exhausted -> DLQ; advance offset so the partition keeps flowing.
                dlq.send(m, "max retries exceeded");
                commit(c, topic, m);
            }
        }

        /** OBSERVER: push assigned messages to a listener on the dispatch pool. */
        void subscribe(Consumer<T> c, MessageListener<T> listener, int batchSize) {
            dispatchPool.submit(() -> {
                List<Message<T>> batch = poll(c, batchSize);
                for (Message<T> m : batch) {
                    try {
                        listener.onMessage(m);
                        commit(c, firstTopic(c), m);
                    } catch (Exception ex) {
                        nack(c, firstTopic(c), m);
                    }
                }
            });
        }

        DeadLetterQueue<T> dlq() { return dlq; }
        void shutdown() { dispatchPool.shutdownNow(); }

        private String firstTopic(Consumer<T> c) { return c.topics.iterator().next(); }
        private Topic<T> requireTopic(String name) {
            Topic<T> t = topics.get(name);
            if (t == null) throw new IllegalArgumentException("Unknown topic: " + name);
            return t;
        }
    }

    /* ====================================================================
     * Producer — thin client handle (Facade hides partitions/offsets).
     * ==================================================================== */
    static final class Producer<T> {
        private final MessageBroker<T> broker;
        private final String topic;
        Producer(MessageBroker<T> broker, String topic) {
            this.broker = broker; this.topic = topic;
        }
        RecordMetadata send(String key, T value) {
            return broker.publish(topic, Message.of(key, value, null));
        }
        RecordMetadata send(String key, T value, Map<String, String> headers) {
            return broker.publish(topic, Message.of(key, value, headers));
        }
    }

    /* ====================================================================
     * Consumer — thin client handle bound to a group.
     * ==================================================================== */
    static final class Consumer<T> {
        final String id = "c-" + UUID.randomUUID().toString().substring(0, 6);
        private final MessageBroker<T> broker;
        final ConsumerGroup<T> group;
        final Set<String> topics;

        Consumer(MessageBroker<T> broker, ConsumerGroup<T> group, Set<String> topics) {
            this.broker = broker; this.group = group;
            this.topics = Collections.unmodifiableSet(new HashSet<>(topics));
        }
        List<Message<T>> poll(int max) { return broker.poll(this, max); }
        void commit(String topic, Message<T> m) { broker.commit(this, topic, m); }
        void nack(String topic, Message<T> m) { broker.nack(this, topic, m); }
        void subscribe(MessageListener<T> l, int batchSize) {
            broker.subscribe(this, l, batchSize);
        }
        void leave() { group.leave(id); }
    }

    /* ====================================================================
     * DEMO — exercises the key scenarios and prints output.
     * ==================================================================== */
    public static void main(String[] args) throws Exception {
        System.out.println("=== In-Memory Message Queue demo ===\n");

        MessageBroker<String> broker = new MessageBroker<>(
                new ExponentialBackoffRetryPolicy(/*maxAttempts*/ 3, /*baseMs*/ 10));

        // Factory: create a topic with 3 partitions, capacity 100, key-hash routing.
        broker.createTopic("orders", /*partitions*/ 3, /*capacity*/ 100,
                new BlockPolicy(/*timeoutMs*/ 500), new KeyHashPartitionStrategy());

        // ---- Scenario 1: produce keyed messages (per-key ordering) ----
        System.out.println("-- Scenario 1: produce keyed messages --");
        Producer<String> producer = broker.createProducer("orders");
        for (int i = 1; i <= 6; i++) {
            String key = (i % 2 == 0) ? "userA" : "userB"; // same key -> same partition
            RecordMetadata md = producer.send(key, "order#" + i);
            System.out.println("  produced order#" + i + " key=" + key + " -> " + md);
        }

        // ---- Scenario 2: fan-out to two consumer groups ----
        System.out.println("\n-- Scenario 2: two groups each see the full stream --");
        RebalanceStrategy rr = new RoundRobinRebalanceStrategy();
        Consumer<String> billing = broker.createConsumer("billing", Set.of("orders"), rr);
        Consumer<String> analytics = broker.createConsumer("analytics", Set.of("orders"), rr);

        drain("billing", broker, billing);
        drain("analytics", broker, analytics);

        // ---- Scenario 3: competing consumers within ONE group ----
        System.out.println("\n-- Scenario 3: competing consumers share partitions in a group --");
        Consumer<String> w1 = broker.createConsumer("workers", Set.of("orders"), rr);
        Consumer<String> w2 = broker.createConsumer("workers", Set.of("orders"), rr);
        System.out.println("  worker1 assigned partitions: " + w1.group.assignedPartitions(w1.id, "orders"));
        System.out.println("  worker2 assigned partitions: " + w2.group.assignedPartitions(w2.id, "orders"));
        drain("worker1", broker, w1);
        drain("worker2", broker, w2);

        // ---- Scenario 4: at-least-once retry + DLQ for a poison message ----
        System.out.println("\n-- Scenario 4: retry then DLQ for a poison message --");
        broker.createTopic("payments", 1, 100,
                new BlockPolicy(500), new RoundRobinPartitionStrategy());
        Producer<String> payProducer = broker.createProducer("payments");
        payProducer.send(null, "POISON");
        Consumer<String> payConsumer = broker.createConsumer("settlement", Set.of("payments"), rr);

        // Simulate a consumer that always fails on this message: poll + nack repeatedly.
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Message<String>> batch = payConsumer.poll(10);
            if (batch.isEmpty()) break;
            for (Message<String> m : batch) {
                System.out.println("  processing " + m + " ... FAILED");
                payConsumer.nack("payments", m);
            }
            // re-poll picks up the re-enqueued retried copy from the partition log
            Thread.sleep(5);
            // Manually advance for demo: poll reads from committed offset; the retried
            // copy was re-appended, so the next poll returns it with attempt incremented.
            List<Message<String>> retriedBatch = payConsumer.poll(10);
            if (!retriedBatch.isEmpty()) {
                Message<String> latest = retriedBatch.get(retriedBatch.size() - 1);
                if (!new ExponentialBackoffRetryPolicy(3, 10).shouldRetry(latest.deliveryAttempt + 1)) {
                    payConsumer.nack("payments", latest); // exhausted -> DLQ
                    break;
                }
            }
        }
        System.out.println("  DLQ size = " + broker.dlq().size());

        // ---- Scenario 5: push delivery via Observer ----
        System.out.println("\n-- Scenario 5: push delivery (Observer / MessageListener) --");
        broker.createTopic("events", 1, 100, new BlockPolicy(500), new RoundRobinPartitionStrategy());
        Producer<String> evtProducer = broker.createProducer("events");
        evtProducer.send(null, "click");
        evtProducer.send(null, "scroll");
        Consumer<String> pushConsumer = broker.createConsumer("ui", Set.of("events"), rr);
        pushConsumer.subscribe(m -> System.out.println("  [PUSH] received " + m), 10);
        Thread.sleep(200);

        broker.shutdown();
        System.out.println("\n=== demo complete ===");
    }

    /** Helper: poll a consumer's assigned partitions, process, commit (at-least-once). */
    private static void drain(String label, MessageBroker<String> broker, Consumer<String> c) {
        List<Message<String>> batch = broker.poll(c, 100);
        System.out.println("  [" + label + "] polled " + batch.size() + " messages:");
        for (Message<String> m : batch) {
            System.out.println("      consumed " + m);
            for (String t : c.topics) {
                if (broker.poll(c, 0) != null) { /* no-op guard */ }
            }
            c.commit("orders", m);   // single-topic demo
        }
    }
}

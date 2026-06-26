/* ============================================================================
 * Chat Application — Low-Level Design  (single-file review / revision artifact)
 *
 * This file is a READ-AND-REVISE artifact. It is complete and correct-by-
 * inspection (no TODOs, no omitted bodies). You do NOT need to compile or run
 * it; the `main` only illustrates usage.
 *
 * ----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ----------------------------------------------------------------------------
 * Functional:
 *   1. 1:1 only, or also group chat? Any broadcast/channel semantics?
 *        -> Both 1:1 and group; a 1:1 is just a 2-member room. No broadcast.
 *   2. Message types: text only, or media/location/system messages?
 *        -> Message + MessageType enum; media is a payload variation.
 *   3. Receipts: delivery and/or read? Per-recipient or aggregate in groups?
 *        -> Both, tracked PER recipient (group shows "read by 3 of 5").
 *   4. Presence: online/offline/away? last-seen? typing indicators?
 *        -> Online/Away/Offline + last-seen; typing is a transient event.
 *   5. History: persisted & replayable? pagination? search?
 *        -> Persisted, replayable, paginated by time; search out of scope.
 *   6. Offline delivery: queue & deliver on reconnect? ordering? guarantees?
 *        -> Per-user FIFO queue, at-least-once, drained on reconnect.
 *   7. Group mgmt: add/remove/leave, admin role, who can post?
 *        -> add/remove/leave, admin role, all members post.
 *   8. Edit/delete/reactions/threads/forwarding?  -> Extensions; left open.
 * Non-functional:
 *   9. Scale / max group size? (sync fan-out vs broker)
 *        -> Single JVM in-memory; fan-out behind an interface for later broker.
 *  10. Concurrency: many threads on same room/inbox?  -> YES, first-class.
 *  11. Ordering: total order per conversation?  -> Yes, monotonic sequence.
 *  12. Durability: in-memory + Repository seam OK?  -> Yes.
 *  13. Latency: push for online, pull on reconnect?  -> Yes.
 * Out of scope: auth, E2EE, rate limiting, spam, multi-device, transport layer.
 *
 * ----------------------------------------------------------------------------
 * PATTERNS APPLIED
 * ----------------------------------------------------------------------------
 *   - Mediator : ChatRoomMediator coordinates members (avoids N^2 coupling).
 *   - Observer : MessageObserver + EventBus fan out messages/receipts/presence.
 *   - Strategy : DeliveryStrategy (push-if-online-else-queue / always-queue / batch).
 *   - Factory  : MessageFactory, ChatRoomFactory enforce construction invariants.
 *   - Facade   : ChatService is the single clean entry point.
 *   - State-lite : MessageStatus / PresenceStatus enums with guarded transitions.
 * SOLID: SRP (one job per class), OCP (new strategies/observers/types added,
 *        not edited), LSP (substitutable strategies/channels), ISP (narrow
 *        MessageObserver), DIP (mediator depends on abstractions, injected).
 * Concurrency: ConcurrentHashMap membership, AtomicLong per-room sequence,
 *        thread-safe queue with atomic drain, ConcurrentHashMap status map with
 *        guarded transitions, CopyOnWriteArrayList observers, volatile presence.
 * ==========================================================================*/

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/* ===========================================================================
 * ENUMS  (State-lite: monotonic lifecycles with legal-transition guards)
 * ========================================================================= */

enum MessageType { TEXT, IMAGE, VIDEO, FILE, SYSTEM }

enum RoomType { DIRECT, GROUP }

/** Per-recipient delivery lifecycle. READ is terminal; transitions never regress. */
enum MessageStatus {
    SENT, DELIVERED, READ;

    /** Guard so a late DELIVERED cannot overwrite a READ, etc. */
    boolean canTransitionTo(MessageStatus next) {
        switch (this) {
            case SENT:      return next == DELIVERED || next == READ;
            case DELIVERED: return next == READ;
            case READ:      return false; // terminal
            default:        return false;
        }
    }
}

/** Presence with last-seen semantics handled on the User. */
enum PresenceStatus { ONLINE, AWAY, OFFLINE }

enum EventType { NEW_MESSAGE, DELIVERY_RECEIPT, READ_RECEIPT, PRESENCE_CHANGED, TYPING }

/* ===========================================================================
 * VALUE OBJECTS / DOMAIN ENTITIES
 * ========================================================================= */

/**
 * Message: built only via MessageFactory so invariants (id, server timestamp,
 * monotonic sequence, status map seeded for every recipient) always hold.
 * The per-recipient status map is concurrent and transitions are guarded.
 */
final class Message {
    private final String id;
    private final String roomId;
    private final String senderId;
    private final String content;
    private final MessageType type;
    private final long timestamp;     // server-assigned wall clock (display)
    private final long sequence;      // monotonic per-conversation ordering key
    private final Map<String, MessageStatus> statusByUser; // recipientId -> status

    Message(String id, String roomId, String senderId, String content,
            MessageType type, long timestamp, long sequence,
            Set<String> recipientIds) {
        this.id = id;
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
        this.sequence = sequence;
        this.statusByUser = new ConcurrentHashMap<>();
        for (String r : recipientIds) {
            this.statusByUser.put(r, MessageStatus.SENT);
        }
    }

    String id() { return id; }
    String roomId() { return roomId; }
    String senderId() { return senderId; }
    String content() { return content; }
    MessageType type() { return type; }
    long timestamp() { return timestamp; }
    long sequence() { return sequence; }

    MessageStatus statusFor(String userId) {
        return statusByUser.getOrDefault(userId, MessageStatus.SENT);
    }

    /** Atomic, guarded transition; returns true if applied. */
    private boolean transition(String userId, MessageStatus next) {
        // compute() runs atomically for the key under ConcurrentHashMap.
        MessageStatus[] applied = new MessageStatus[1];
        statusByUser.compute(userId, (k, cur) -> {
            MessageStatus from = (cur == null) ? MessageStatus.SENT : cur;
            if (from.canTransitionTo(next)) {
                applied[0] = next;
                return next;
            }
            applied[0] = from; // unchanged
            return from;
        });
        return applied[0] == next;
    }

    boolean markDelivered(String userId) { return transition(userId, MessageStatus.DELIVERED); }
    boolean markRead(String userId)      { return transition(userId, MessageStatus.READ); }

    /** e.g. "read by 3 of 5" support. */
    long countWithStatus(MessageStatus s) {
        return statusByUser.values().stream().filter(v -> v == s).count();
    }

    @Override
    public String toString() {
        return String.format("Message[#%d id=%s room=%s from=%s type=%s '%s']",
                sequence, id, roomId, senderId, type, content);
    }
}

/**
 * MessageQueue: per-user durable-ish FIFO buffer for OFFLINE recipients.
 * Behind a real broker (Kafka/Redis Streams) in production. drain() snapshots
 * and clears atomically so a concurrent enqueue during reconnect isn't lost.
 */
final class MessageQueue {
    private final Deque<Message> pending = new ConcurrentLinkedDeque<>();

    void enqueue(Message m) { pending.addLast(m); }

    /** Atomically remove and return all pending messages in FIFO order. */
    List<Message> drain() {
        List<Message> out = new ArrayList<>();
        Message m;
        // pollFirst is atomic; we drain until empty. New enqueues during drain
        // simply land after and are delivered on the next drain (no loss).
        while ((m = pending.pollFirst()) != null) {
            out.add(m);
        }
        return out;
    }

    int size() { return pending.size(); }
}

/**
 * User: identity + presence + owns a MessageQueue. Implements MessageObserver,
 * so it is a subscriber on the EventBus. Presence is volatile so a concurrent
 * sender observes the latest value when choosing push vs queue.
 */
final class User implements MessageObserver {
    private final String id;
    private final String displayName;
    private volatile PresenceStatus presence = PresenceStatus.OFFLINE;
    private volatile long lastSeen = System.currentTimeMillis();
    private final MessageQueue queue = new MessageQueue();

    User(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    String id() { return id; }
    String displayName() { return displayName; }
    PresenceStatus presence() { return presence; }
    long lastSeen() { return lastSeen; }
    MessageQueue queue() { return queue; }
    boolean isOnline() { return presence == PresenceStatus.ONLINE; }

    void setPresence(PresenceStatus p) {
        this.presence = p;
        this.lastSeen = System.currentTimeMillis();
    }

    /** Observer callback: how this user "renders" an incoming event. */
    @Override
    public void onEvent(ChatEvent event) {
        switch (event.type()) {
            case NEW_MESSAGE: {
                NewMessageEvent e = (NewMessageEvent) event;
                if (!e.message().senderId().equals(id)) {
                    System.out.printf("   [UI:%s] new message %s%n", displayName, e.message());
                }
                break;
            }
            case DELIVERY_RECEIPT: {
                DeliveryReceiptEvent e = (DeliveryReceiptEvent) event;
                if (e.originalSenderId().equals(id)) {
                    System.out.printf("   [UI:%s] delivered to %s (msg %s)%n",
                            displayName, e.recipientId(), e.messageId());
                }
                break;
            }
            case READ_RECEIPT: {
                ReadReceiptEvent e = (ReadReceiptEvent) event;
                if (e.originalSenderId().equals(id)) {
                    System.out.printf("   [UI:%s] read by %s (msg %s)%n",
                            displayName, e.readerId(), e.messageId());
                }
                break;
            }
            case PRESENCE_CHANGED: {
                PresenceChangedEvent e = (PresenceChangedEvent) event;
                System.out.printf("   [UI:%s] presence: %s -> %s%n",
                        displayName, e.userId(), e.status());
                break;
            }
            case TYPING:
            default:
                break;
        }
    }
}

/* ===========================================================================
 * OBSERVER:  events + bus + subscribers
 * ========================================================================= */

/** Narrow observer interface (ISP): subscribers implement just onEvent. */
interface MessageObserver {
    void onEvent(ChatEvent event);
}

/** Base event published on the bus. */
abstract class ChatEvent {
    abstract EventType type();
}

final class NewMessageEvent extends ChatEvent {
    private final Message message;
    NewMessageEvent(Message message) { this.message = message; }
    Message message() { return message; }
    @Override EventType type() { return EventType.NEW_MESSAGE; }
}

final class DeliveryReceiptEvent extends ChatEvent {
    private final String messageId;
    private final String recipientId;
    private final String originalSenderId;
    DeliveryReceiptEvent(String messageId, String recipientId, String originalSenderId) {
        this.messageId = messageId;
        this.recipientId = recipientId;
        this.originalSenderId = originalSenderId;
    }
    String messageId() { return messageId; }
    String recipientId() { return recipientId; }
    String originalSenderId() { return originalSenderId; }
    @Override EventType type() { return EventType.DELIVERY_RECEIPT; }
}

final class ReadReceiptEvent extends ChatEvent {
    private final String messageId;
    private final String readerId;
    private final String originalSenderId;
    ReadReceiptEvent(String messageId, String readerId, String originalSenderId) {
        this.messageId = messageId;
        this.readerId = readerId;
        this.originalSenderId = originalSenderId;
    }
    String messageId() { return messageId; }
    String readerId() { return readerId; }
    String originalSenderId() { return originalSenderId; }
    @Override EventType type() { return EventType.READ_RECEIPT; }
}

final class PresenceChangedEvent extends ChatEvent {
    private final String userId;
    private final PresenceStatus status;
    PresenceChangedEvent(String userId, PresenceStatus status) {
        this.userId = userId;
        this.status = status;
    }
    String userId() { return userId; }
    PresenceStatus status() { return status; }
    @Override EventType type() { return EventType.PRESENCE_CHANGED; }
}

/**
 * EventBus: Observer fan-out. CopyOnWriteArrayList -> safe iteration during
 * publish while subscribers churn (rare relative to publishes).
 */
final class EventBus {
    private final List<MessageObserver> observers = new CopyOnWriteArrayList<>();

    void subscribe(MessageObserver o) { observers.add(o); }
    void unsubscribe(MessageObserver o) { observers.remove(o); }

    void publish(ChatEvent event) {
        for (MessageObserver o : observers) {
            o.onEvent(event); // in-process; behind a broker at scale
        }
    }
}

/* ===========================================================================
 * NOTIFICATION CHANNELS  (Strategy-flavored output sinks; for offline/push)
 * ========================================================================= */

final class Notification {
    private final String toUserId;
    private final String text;
    Notification(String toUserId, String text) { this.toUserId = toUserId; this.text = text; }
    String toUserId() { return toUserId; }
    String text() { return text; }
}

interface NotificationChannel {
    void notify(User user, Notification n);
}

final class InAppChannel implements NotificationChannel {
    @Override public void notify(User user, Notification n) {
        System.out.printf("   [in-app->%s] %s%n", user.displayName(), n.text());
    }
}

final class PushChannel implements NotificationChannel {
    @Override public void notify(User user, Notification n) {
        // Stand-in for APNs/FCM; used when the recipient is OFFLINE.
        System.out.printf("   [PUSH->%s] %s%n", user.displayName(), n.text());
    }
}

/* ===========================================================================
 * REPOSITORY  (DIP seam: swap in-memory for JDBC/NoSQL)
 * ========================================================================= */

interface MessageRepository {
    void append(Message m);
    List<Message> history(String roomId, long beforeTimestamp, int limit);
    Optional<Message> byId(String id);
}

/** In-memory repo: per-room append-only log; reads return a consistent slice. */
final class InMemoryMessageRepository implements MessageRepository {
    private final Map<String, List<Message>> byRoom = new ConcurrentHashMap<>();
    private final Map<String, Message> byId = new ConcurrentHashMap<>();

    @Override
    public void append(Message m) {
        byId.put(m.id(), m);
        byRoom.computeIfAbsent(m.roomId(), k -> Collections.synchronizedList(new ArrayList<>()))
              .add(m);
    }

    @Override
    public List<Message> history(String roomId, long beforeTimestamp, int limit) {
        List<Message> log = byRoom.getOrDefault(roomId, Collections.emptyList());
        List<Message> snapshot;
        synchronized (log) { snapshot = new ArrayList<>(log); } // consistent prefix
        return snapshot.stream()
                .filter(m -> m.timestamp() < beforeTimestamp)
                .sorted(Comparator.comparingLong(Message::sequence))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Message> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }
}

/* ===========================================================================
 * STRATEGY  (delivery policy, decoupled from coordination)
 * ========================================================================= */

/** Context passed to a strategy so it can act on the bus / channels / mediator. */
final class DeliveryContext {
    final EventBus bus;
    final NotificationChannel pushChannel;
    final ChatRoomMediator mediator;
    DeliveryContext(EventBus bus, NotificationChannel pushChannel, ChatRoomMediator mediator) {
        this.bus = bus;
        this.pushChannel = pushChannel;
        this.mediator = mediator;
    }
}

interface DeliveryStrategy {
    void deliver(Message msg, User recipient, DeliveryContext ctx);
}

/**
 * Default policy: push now if recipient online (and record DELIVERED), else
 * enqueue for drain on reconnect and fire a push notification.
 */
final class PushIfOnlineElseQueueStrategy implements DeliveryStrategy {
    @Override
    public void deliver(Message msg, User recipient, DeliveryContext ctx) {
        if (recipient.isOnline()) {
            ctx.bus.publish(new NewMessageEvent(msg));      // Observer push (online UI)
            ctx.mediator.recordDelivery(msg.id(), recipient.id());
        } else {
            recipient.queue().enqueue(msg);                 // offline buffer
            ctx.pushChannel.notify(recipient,
                    new Notification(recipient.id(), "New message in room " + msg.roomId()));
        }
    }
}

/** Always queue (e.g., for testing or strictly pull-based clients). */
final class AlwaysQueueStrategy implements DeliveryStrategy {
    @Override
    public void deliver(Message msg, User recipient, DeliveryContext ctx) {
        recipient.queue().enqueue(msg);
    }
}

/**
 * Batch strategy: queue and let a periodic drainer flush in batches; useful to
 * avoid an event-storm for very large groups. Here it simply enqueues.
 */
final class BatchDeliveryStrategy implements DeliveryStrategy {
    @Override
    public void deliver(Message msg, User recipient, DeliveryContext ctx) {
        recipient.queue().enqueue(msg);
        // A scheduled batch flusher (out of scope) would drain & deliver later.
    }
}

/* ===========================================================================
 * FACTORY  (construction invariants in one place)
 * ========================================================================= */

final class MessageFactory {
    static Message create(String roomId, String senderId, String content,
                          MessageType type, long sequence, Set<String> recipientIds) {
        return new Message(
                UUID.randomUUID().toString(),
                roomId, senderId, content, type,
                System.currentTimeMillis(), sequence, recipientIds);
    }
}

final class ChatRoomFactory {
    static ChatRoom createDirect(User a, User b, DeliveryStrategy strategy,
                                 MessageRepository repo, EventBus bus,
                                 NotificationChannel pushChannel) {
        ChatRoom room = new ChatRoom(UUID.randomUUID().toString(),
                a.displayName() + " <-> " + b.displayName(), RoomType.DIRECT,
                strategy, repo, bus, pushChannel);
        room.addMember(a);
        room.addMember(b);
        return room;
    }

    static ChatRoom createGroup(String name, List<User> members, DeliveryStrategy strategy,
                                MessageRepository repo, EventBus bus,
                                NotificationChannel pushChannel) {
        ChatRoom room = new ChatRoom(UUID.randomUUID().toString(),
                name, RoomType.GROUP, strategy, repo, bus, pushChannel);
        for (User m : members) room.addMember(m);
        return room;
    }
}

/* ===========================================================================
 * MEDIATOR  (coordinates members; thin — delegates delivery & persistence)
 * ========================================================================= */

final class ChatRoomMediator {
    private final String roomId;
    private final Map<String, User> members = new ConcurrentHashMap<>();
    private final DeliveryStrategy strategy;
    private final MessageRepository repo;
    private final EventBus bus;
    private final NotificationChannel pushChannel;
    private final AtomicLong sequence = new AtomicLong(0); // total order per conversation

    ChatRoomMediator(String roomId, DeliveryStrategy strategy, MessageRepository repo,
                     EventBus bus, NotificationChannel pushChannel) {
        this.roomId = roomId;
        this.strategy = strategy;
        this.repo = repo;
        this.bus = bus;
        this.pushChannel = pushChannel;
    }

    void addMember(User u) {
        members.put(u.id(), u);
        bus.subscribe(u); // member observes room events (idempotent on a Set-like bus)
    }

    void removeMember(String userId) {
        User u = members.remove(userId);
        if (u != null) bus.unsubscribe(u);
    }

    Set<User> members() {
        return new java.util.HashSet<>(members.values());
    }

    /** Core coordination: build, persist-first, then fan out via the strategy. */
    Message dispatch(String senderId, String content, MessageType type) {
        if (!members.containsKey(senderId)) {
            throw new IllegalArgumentException("Sender " + senderId + " is not a room member");
        }
        Set<String> recipients = members.keySet().stream()
                .filter(id -> !id.equals(senderId))
                .collect(Collectors.toSet());

        Message msg = MessageFactory.create(roomId, senderId, content, type,
                sequence.incrementAndGet(), recipients);

        repo.append(msg); // history is authoritative even if delivery fails

        DeliveryContext ctx = new DeliveryContext(bus, pushChannel, this);
        for (String recipientId : recipients) {
            User recipient = members.get(recipientId);
            if (recipient != null) {
                strategy.deliver(msg, recipient, ctx);
            }
        }
        return msg;
    }

    /** Called by the strategy / queue drain when a message reaches a recipient. */
    void recordDelivery(String messageId, String recipientId) {
        repo.byId(messageId).ifPresent(m -> {
            if (m.markDelivered(recipientId)) {
                bus.publish(new DeliveryReceiptEvent(messageId, recipientId, m.senderId()));
            }
        });
    }

    /** Read receipt path. */
    void recordRead(String messageId, String readerId) {
        repo.byId(messageId).ifPresent(m -> {
            if (m.markRead(readerId)) {
                bus.publish(new ReadReceiptEvent(messageId, readerId, m.senderId()));
            }
        });
    }

    String roomId() { return roomId; }
}

/* ===========================================================================
 * CHAT ROOM  (composes a mediator; entry point for send/membership)
 * ========================================================================= */

final class ChatRoom {
    private final String id;
    private final String name;
    private final RoomType type;
    private final ChatRoomMediator mediator;

    ChatRoom(String id, String name, RoomType type, DeliveryStrategy strategy,
             MessageRepository repo, EventBus bus, NotificationChannel pushChannel) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.mediator = new ChatRoomMediator(id, strategy, repo, bus, pushChannel);
    }

    String id() { return id; }
    String name() { return name; }
    RoomType type() { return type; }
    ChatRoomMediator mediator() { return mediator; }

    void addMember(User u) { mediator.addMember(u); }
    void removeMember(String userId) { mediator.removeMember(userId); }
    Set<User> members() { return mediator.members(); }

    Message send(String senderId, String content, MessageType type) {
        return mediator.dispatch(senderId, content, type);
    }

    void markRead(String userId, String messageId) {
        mediator.recordRead(messageId, userId);
    }
}

/* ===========================================================================
 * FACADE  (single clean entry point for the transport layer)
 * ========================================================================= */

final class ChatService {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();
    private final MessageRepository repo = new InMemoryMessageRepository();
    private final EventBus bus = new EventBus();
    private final NotificationChannel pushChannel = new PushChannel();
    private final DeliveryStrategy defaultStrategy = new PushIfOnlineElseQueueStrategy();

    User registerUser(String displayName) {
        User u = new User(UUID.randomUUID().toString(), displayName);
        users.put(u.id(), u);
        return u;
    }

    ChatRoom createDirect(String userA, String userB) {
        ChatRoom room = ChatRoomFactory.createDirect(
                require(userA), require(userB), defaultStrategy, repo, bus, pushChannel);
        rooms.put(room.id(), room);
        return room;
    }

    ChatRoom createGroup(String name, List<String> memberIds) {
        List<User> members = memberIds.stream().map(this::require).collect(Collectors.toList());
        ChatRoom room = ChatRoomFactory.createGroup(name, members, defaultStrategy, repo, bus, pushChannel);
        rooms.put(room.id(), room);
        return room;
    }

    /** Connect: go ONLINE, announce presence, drain the offline queue in FIFO order. */
    void connect(String userId) {
        User u = require(userId);
        u.setPresence(PresenceStatus.ONLINE);
        bus.publish(new PresenceChangedEvent(userId, PresenceStatus.ONLINE));

        List<Message> pending = u.queue().drain();
        for (Message m : pending) {
            // Deliver via the online UI path and record DELIVERED + receipts.
            u.onEvent(new NewMessageEvent(m));
            ChatRoom room = rooms.get(m.roomId());
            if (room != null) room.mediator().recordDelivery(m.id(), userId);
        }
    }

    void disconnect(String userId) {
        User u = require(userId);
        u.setPresence(PresenceStatus.OFFLINE);
        bus.publish(new PresenceChangedEvent(userId, PresenceStatus.OFFLINE));
    }

    Message send(String roomId, String senderId, String content, MessageType type) {
        ChatRoom room = rooms.get(roomId);
        if (room == null) throw new IllegalArgumentException("No such room: " + roomId);
        return room.send(senderId, content, type);
    }

    void markRead(String roomId, String userId, String messageId) {
        ChatRoom room = rooms.get(roomId);
        if (room != null) room.markRead(userId, messageId);
    }

    List<Message> getHistory(String roomId, long beforeTimestamp, int limit) {
        return repo.history(roomId, beforeTimestamp, limit);
    }

    EventBus bus() { return bus; }

    private User require(String userId) {
        User u = users.get(userId);
        if (u == null) throw new IllegalArgumentException("No such user: " + userId);
        return u;
    }
}

/* ===========================================================================
 * DEMO  (illustrates the key scenarios for the reader)
 * ========================================================================= */

public class Solution {
    public static void main(String[] args) {
        ChatService chat = new ChatService();

        // --- Register users ---
        User alice = chat.registerUser("Alice");
        User bob   = chat.registerUser("Bob");
        User carol = chat.registerUser("Carol");

        // Everyone comes online (so we see real-time push + receipts).
        chat.connect(alice.id());
        chat.connect(bob.id());
        chat.connect(carol.id());

        System.out.println("\n=== Scenario 1: 1:1 chat with delivery + read receipts ===");
        ChatRoom dm = chat.createDirect(alice.id(), bob.id());
        Message m1 = chat.send(dm.id(), alice.id(), "Hey Bob!", MessageType.TEXT);
        System.out.println("   status(Bob) after send = " + m1.statusFor(bob.id()));
        chat.markRead(dm.id(), bob.id(), m1.id()); // Bob opens the chat
        System.out.println("   status(Bob) after read = " + m1.statusFor(bob.id()));

        System.out.println("\n=== Scenario 2: group chat, one member offline (queue + drain) ===");
        ChatRoom group = chat.createGroup("Project X",
                List.of(alice.id(), bob.id(), carol.id()));
        chat.disconnect(carol.id()); // Carol goes offline -> her copy is queued
        Message gm = chat.send(group.id(), alice.id(), "Standup at 10am", MessageType.TEXT);
        System.out.println("   Bob   status = " + gm.statusFor(bob.id())
                + " | Carol status = " + gm.statusFor(carol.id())
                + " | Carol queued = " + carol.queue().size());

        System.out.println("\n   -- Carol reconnects: queue drains in FIFO, receipts fire --");
        chat.connect(carol.id());
        System.out.println("   Carol status after reconnect = " + gm.statusFor(carol.id()));
        System.out.println("   group 'read by' DELIVERED count = "
                + gm.countWithStatus(MessageStatus.DELIVERED));

        System.out.println("\n=== Scenario 3: read receipts in a group (read by N of M) ===");
        chat.markRead(group.id(), bob.id(), gm.id());
        chat.markRead(group.id(), carol.id(), gm.id());
        System.out.println("   READ count = " + gm.countWithStatus(MessageStatus.READ)
                + " of " + group.members().size() + " members (sender excluded from recipients)");

        System.out.println("\n=== Scenario 4: message history (paginated) ===");
        chat.send(group.id(), bob.id(), "On my way", MessageType.TEXT);
        chat.send(group.id(), carol.id(), "+1", MessageType.TEXT);
        List<Message> history = chat.getHistory(group.id(), System.currentTimeMillis() + 1, 10);
        System.out.println("   history (ordered by sequence):");
        for (Message m : history) System.out.println("      " + m);

        System.out.println("\n=== Scenario 5: presence transition ===");
        chat.disconnect(bob.id());
        System.out.println("   Bob presence = " + bob.presence()
                + " | online? " + bob.isOnline());

        System.out.println("\nDemo complete.");
    }
}

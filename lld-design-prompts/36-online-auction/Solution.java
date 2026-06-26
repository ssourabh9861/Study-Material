/* =====================================================================================
 * ONLINE AUCTION SYSTEM  --  Single-file LLD review / revision artifact.
 *
 * This file is for READING & REVISION (correct-by-inspection). It is not meant to be
 * benchmarked; a small main() illustrates the key scenarios.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * FUNCTIONAL
 *   1. Auction format? English (ascending) default; also Dutch / sealed-bid / Vickrey?
 *   2. Reserve price (secret minimum)? Should bidders see "reserve met"?
 *   3. Proxy / auto-bidding (set a max, system bids on your behalf)?
 *   4. Anti-snipe: do late bids extend the end time? By how much? Capped?
 *   5. Buy-it-now price that ends the auction instantly?
 *   6. Can a seller cancel (before/after bids)? Can a bidder retract a bid?
 *   7. Winner = highest >= reserve. If reserve not met: no sale / relist / sell anyway?
 *   8. Is payment in scope, or just emit a "winner determined" event and hand off?
 * NON-FUNCTIONAL / SCALE
 *   9. Concurrency on a hot auction (tens vs thousands of bids/sec)?
 *  10. Single JVM (in-memory) or distributed (multi-server)?
 *  11. Notification latency: real-time push or poll/eventual?
 *  12. Durability: must bids survive a crash?
 * SCOPE-NARROWING / OUT OF SCOPE
 *  13. Auth, catalog search, ratings, shipping, fraud -> out unless asked.
 *  14. Single currency; integer minor units (cents) to avoid float drift.
 *  15. Injectable Clock for deterministic tests/demos.
 *
 * ASSUMED ANSWERS (so the design is concrete):
 *   English auction; reserve supported & hidden; proxy bidding supported; anti-snipe with
 *   per-extension window + max-extension cap; seller cancel only before first bid; no bid
 *   retraction; reserve-not-met => no sale; payment via out-of-process event hand-off;
 *   single-JVM thread-safe in-memory with a repository seam.
 *
 * -------------------------------------------------------------------------------------
 * PATTERNS:  State (lifecycle) | Strategy (validation/winner/anti-snipe) |
 *            Observer (real-time fan-out) | Facade (AuctionService) |
 *            Builder/Factory (AuctionSpec) | Value Object/Immutability (Bid, HighestBid).
 * CONCURRENCY: per-auction ReentrantLock guards the compound bid critical section;
 *            AtomicReference<HighestBid> for consistent lock-free reads.
 * ===================================================================================== */

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class Solution {

    /* =================================================================================
     * VALUE OBJECTS (immutable -> safe to publish across threads / to observers)
     * ================================================================================= */

    /** What is being auctioned. Immutable. */
    static final class Item {
        final String id, title, description;
        Item(String id, String title, String description) {
            this.id = id; this.title = title; this.description = description;
        }
    }

    /** A person. Role (seller/bidder) is expressed by usage, not a class hierarchy. */
    static final class User {
        final String id, name;
        User(String id, String name) { this.id = id; this.name = name; }
    }

    /** A placed bid. Immutable value object. Amount is in integer cents. */
    static final class Bid {
        final String bidderId;
        final long amountCents;
        final Instant time;
        Bid(String bidderId, long amountCents, Instant time) {
            this.bidderId = bidderId; this.amountCents = amountCents; this.time = time;
        }
        @Override public String toString() {
            return bidderId + "@" + money(amountCents);
        }
    }

    /** A standing proxy/auto-bid: the system will bid up to maxCents on the bidder's behalf. */
    static final class ProxyBid {
        final String bidderId;
        final long maxCents;
        final Instant time; // tie-breaker: earliest proxy leads
        ProxyBid(String bidderId, long maxCents, Instant time) {
            this.bidderId = bidderId; this.maxCents = maxCents; this.time = time;
        }
    }

    /** Immutable snapshot of the current leading bid; published via AtomicReference. */
    static final class HighestBid {
        final String bidderId;
        final long amountCents;
        HighestBid(String bidderId, long amountCents) {
            this.bidderId = bidderId; this.amountCents = amountCents;
        }
        static final HighestBid NONE = new HighestBid(null, 0L);
        boolean exists() { return bidderId != null; }
    }

    static String money(long cents) {
        return String.format("$%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /* =================================================================================
     * RESULT / OUTCOME TYPES
     * ================================================================================= */

    static final class ValidationResult {
        final boolean ok; final String reason;
        private ValidationResult(boolean ok, String reason) { this.ok = ok; this.reason = reason; }
        static ValidationResult ok() { return new ValidationResult(true, null); }
        static ValidationResult fail(String reason) { return new ValidationResult(false, reason); }
    }

    static final class BidResult {
        final boolean accepted; final String reason; final HighestBid highest;
        private BidResult(boolean accepted, String reason, HighestBid highest) {
            this.accepted = accepted; this.reason = reason; this.highest = highest;
        }
        static BidResult accepted(HighestBid h) { return new BidResult(true, "accepted", h); }
        static BidResult rejected(String reason, HighestBid h) { return new BidResult(false, reason, h); }
        @Override public String toString() {
            return accepted
                ? "ACCEPTED (highest=" + highest.bidderId + " " + money(highest.amountCents) + ")"
                : "REJECTED (" + reason + ")";
        }
    }

    enum Status { SOLD, UNSOLD, CANCELLED, OPEN }

    static final class AuctionOutcome {
        final Status status;
        final String winnerId;      // null unless SOLD
        final long winningPriceCents;
        AuctionOutcome(Status status, String winnerId, long winningPriceCents) {
            this.status = status; this.winnerId = winnerId; this.winningPriceCents = winningPriceCents;
        }
        static AuctionOutcome sold(String w, long p) { return new AuctionOutcome(Status.SOLD, w, p); }
        static AuctionOutcome unsold() { return new AuctionOutcome(Status.UNSOLD, null, 0L); }
        static AuctionOutcome cancelled() { return new AuctionOutcome(Status.CANCELLED, null, 0L); }
        @Override public String toString() {
            return status == Status.SOLD
                ? "SOLD to " + winnerId + " for " + money(winningPriceCents)
                : status.toString();
        }
    }

    /* =================================================================================
     * OBSERVER PATTERN -- real-time fan-out of auction events.
     * Auction publishes immutable events; concrete observers own delivery (push/email/
     * audit/payment). Auction has NO compile-time dependency on any channel (DIP/OCP).
     * In production, observers should enqueue to an async dispatcher so a slow channel
     * cannot stall the (locked) bid critical section -- noted, kept synchronous here.
     * ================================================================================= */

    enum EventType { BID_PLACED, OUTBID, EXTENDED, CLOSED, WON, UNSOLD }

    static final class AuctionEvent {
        final EventType type;
        final String auctionId;
        final String actorId;     // bidder of interest (e.g. who placed / who was outbid / winner)
        final long amountCents;
        final Instant time;
        final String detail;
        AuctionEvent(EventType type, String auctionId, String actorId,
                     long amountCents, Instant time, String detail) {
            this.type = type; this.auctionId = auctionId; this.actorId = actorId;
            this.amountCents = amountCents; this.time = time; this.detail = detail;
        }
    }

    interface BidObserver {                      // Observer (Subscriber)
        void onEvent(AuctionEvent event);
    }

    /** Logs everything -- an audit trail. */
    static final class ConsoleAuditObserver implements BidObserver {
        @Override public void onEvent(AuctionEvent e) {
            System.out.println("  [audit] " + e.type + " auction=" + e.auctionId
                + (e.actorId != null ? " actor=" + e.actorId : "")
                + (e.amountCents > 0 ? " amount=" + money(e.amountCents) : "")
                + (e.detail != null ? " (" + e.detail + ")" : ""));
        }
    }

    /** Notifies the bidder who just lost the lead. */
    static final class OutbidNotifier implements BidObserver {
        @Override public void onEvent(AuctionEvent e) {
            if (e.type == EventType.OUTBID) {
                System.out.println("  [notify] You (" + e.actorId + ") were OUTBID on "
                    + e.auctionId + "; new highest " + money(e.amountCents));
            }
        }
    }

    /** Reacts to WON by handing off to payment (kept as a print here). SRP: payment is
     *  out of the auction core; the auction only knows "won". */
    static final class PaymentObserver implements BidObserver {
        @Override public void onEvent(AuctionEvent e) {
            if (e.type == EventType.WON) {
                System.out.println("  [payment] Creating invoice for " + e.actorId
                    + " amount " + money(e.amountCents) + " (auction " + e.auctionId + ")");
            }
        }
    }

    /* =================================================================================
     * STRATEGY PATTERN -- bid validation, winner determination, anti-snipe.
     * These are the variation points an interviewer attacks (Vickrey, snipe vs no-snipe,
     * different increments). Swappable per auction without editing Auction (OCP).
     * ================================================================================= */

    interface BidValidationStrategy {
        ValidationResult validate(Auction a, String bidderId, long amountCents, Instant now);
    }

    /** Standard English rule: must beat highest by >= increment; no self-outbid. */
    static final class MinIncrementValidation implements BidValidationStrategy {
        @Override public ValidationResult validate(Auction a, String bidderId, long amount, Instant now) {
            if (amount <= 0) return ValidationResult.fail("amount must be positive");
            HighestBid h = a.highest();
            long floor = h.exists() ? h.amountCents + a.incrementCents : a.startPriceCents;
            if (h.exists() && bidderId.equals(h.bidderId))
                return ValidationResult.fail("you are already the highest bidder");
            if (amount < floor)
                return ValidationResult.fail("bid below minimum " + money(floor));
            return ValidationResult.ok();
        }
    }

    interface WinnerStrategy {
        AuctionOutcome determine(Auction a);
    }

    /** English: highest bid wins at its own amount, only if >= reserve. */
    static final class EnglishWinnerStrategy implements WinnerStrategy {
        @Override public AuctionOutcome determine(Auction a) {
            HighestBid h = a.highest();
            if (!h.exists() || h.amountCents < a.reserveCents) return AuctionOutcome.unsold();
            return AuctionOutcome.sold(h.bidderId, h.amountCents);
        }
    }

    /** Vickrey / second-price: highest bidder wins but PAYS the second-highest bid.
     *  Demonstrates that swapping this strategy alone changes auction semantics; State,
     *  Observer, locking, repositories all stay untouched. */
    static final class VickreyWinnerStrategy implements WinnerStrategy {
        @Override public AuctionOutcome determine(Auction a) {
            List<Bid> bids = a.historySnapshot();
            if (bids.isEmpty()) return AuctionOutcome.unsold();
            // best bid per bidder, then sort descending by amount
            Map<String, Long> bestByBidder = new java.util.HashMap<>();
            for (Bid b : bids) bestByBidder.merge(b.bidderId, b.amountCents, Math::max);
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(bestByBidder.entrySet());
            ranked.sort(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed());
            Map.Entry<String, Long> top = ranked.get(0);
            if (top.getValue() < a.reserveCents) return AuctionOutcome.unsold();
            long price = ranked.size() >= 2
                ? Math.max(ranked.get(1).getValue(), a.reserveCents)
                : Math.max(a.startPriceCents, a.reserveCents);
            return AuctionOutcome.sold(top.getKey(), price);
        }
    }

    interface AntiSnipePolicy {
        /** Return the (possibly extended) end time; called on each accepted bid. */
        Instant maybeExtend(Auction a, Instant now);
    }

    /** No extension -- fixed end time. */
    static final class NoExtensionPolicy implements AntiSnipePolicy {
        @Override public Instant maybeExtend(Auction a, Instant now) { return a.endTime(); }
    }

    /** If a bid lands within `window` of the end, push the end out by `extensionBy`,
     *  capped at `maxExtensions` total extensions. */
    static final class FixedWindowAntiSnipe implements AntiSnipePolicy {
        final Duration window, extensionBy;
        final int maxExtensions;
        FixedWindowAntiSnipe(Duration window, Duration extensionBy, int maxExtensions) {
            this.window = window; this.extensionBy = extensionBy; this.maxExtensions = maxExtensions;
        }
        @Override public Instant maybeExtend(Auction a, Instant now) {
            Instant end = a.endTime();
            boolean inWindow = !now.isBefore(end.minus(window)) && now.isBefore(end);
            if (inWindow && a.extensionsUsed() < maxExtensions) {
                a.recordExtension();
                return end.plus(extensionBy);   // caller commits this under the lock
            }
            return end;
        }
    }

    /* =================================================================================
     * PROXY-BID RESOLVER -- after a manual bid, resolve standing proxies into the
     * new highest. Highest max leads, placed at min(itsMax, secondHighestMax + increment).
     * Pure algorithm; runs inside the auction lock so the resolved highest is consistent.
     * ================================================================================= */

    static final class ProxyBidResolver {
        /** Returns the resolved highest after applying proxies; appends synthetic bids
         *  to history. Loops to a stable fixpoint. */
        BidResult resolve(Auction a, Instant now) {
            // Collect candidate maxima: current proxies, plus current highest as an implicit cap.
            // Strategy: the proxy with the greatest max should lead. Place it just enough to
            // beat the next-best contender (manual highest or second proxy), capped at its max.
            List<ProxyBid> proxies = a.proxySnapshot();
            if (proxies.isEmpty()) return BidResult.accepted(a.highest());

            // Rank proxies by max desc, then earliest time (deterministic tie-break).
            proxies.sort(Comparator
                .comparingLong((ProxyBid p) -> p.maxCents).reversed()
                .thenComparing(p -> p.time));

            ProxyBid leader = proxies.get(0);
            // The amount the leader must beat: the best of (current manual highest from a
            // non-leader, second proxy's max). We approximate "competition" as the max of
            // the second proxy and any current highest not owned by the leader.
            HighestBid cur = a.highest();
            long competition = 0L;
            if (cur.exists() && !cur.bidderId.equals(leader.bidderId)) competition = cur.amountCents;
            if (proxies.size() >= 2) competition = Math.max(competition, proxies.get(1).maxCents);

            long target;
            if (competition == 0L) {
                // Only the leader is interested; lead at the current floor (start or highest+inc),
                // but not above its own max.
                long floor = cur.exists() ? cur.amountCents : a.startPriceCents;
                if (cur.exists() && cur.bidderId.equals(leader.bidderId)) return BidResult.accepted(cur);
                target = Math.min(leader.maxCents, Math.max(floor, a.startPriceCents));
            } else {
                target = Math.min(leader.maxCents, competition + a.incrementCents);
            }

            if (cur.exists() && cur.bidderId.equals(leader.bidderId) && cur.amountCents >= target) {
                return BidResult.accepted(cur); // leader already leads adequately
            }
            if (target < a.startPriceCents) target = a.startPriceCents;

            HighestBid prev = cur;
            HighestBid resolved = new HighestBid(leader.bidderId, target);
            a.commitProxyHighest(resolved, now);
            // notify outbid (previous leader, if different)
            if (prev.exists() && !prev.bidderId.equals(resolved.bidderId)) {
                a.publish(new AuctionEvent(EventType.OUTBID, a.id(), prev.bidderId,
                        resolved.amountCents, now, "outbid by proxy"));
            }
            return BidResult.accepted(resolved);
        }
    }

    /* =================================================================================
     * STATE PATTERN -- auction lifecycle. Each state localizes "what's legal now",
     * so there is no scattered status-switch and illegal transitions are impossible.
     * ================================================================================= */

    interface AuctionState {
        String name();
        BidResult placeBid(Auction a, String bidderId, long amount, Instant now);
        BidResult placeProxyBid(Auction a, String bidderId, long maxCents, Instant now);
        AuctionOutcome close(Auction a, Instant now);
        void cancel(Auction a);
    }

    /** Created but not yet open. Can be opened or cancelled; no bids. */
    static final class DraftState implements AuctionState {
        @Override public String name() { return "DRAFT"; }
        @Override public BidResult placeBid(Auction a, String b, long amt, Instant now) {
            return BidResult.rejected("auction not open yet", a.highest());
        }
        @Override public BidResult placeProxyBid(Auction a, String b, long max, Instant now) {
            return BidResult.rejected("auction not open yet", a.highest());
        }
        @Override public AuctionOutcome close(Auction a, Instant now) {
            return AuctionOutcome.unsold();
        }
        @Override public void cancel(Auction a) { a.setState(new CancelledState()); }
    }

    /** Live. Accepts valid bids; auto-closes if now is past end. */
    static final class OpenState implements AuctionState {
        @Override public String name() { return "OPEN"; }

        @Override public BidResult placeBid(Auction a, String bidderId, long amount, Instant now) {
            if (!now.isBefore(a.endTime())) {            // time expired -> close, reject bid
                a.close(now);
                return BidResult.rejected("auction closed", a.highest());
            }
            ValidationResult v = a.validation().validate(a, bidderId, amount, now);
            if (!v.ok) return BidResult.rejected(v.reason, a.highest());

            HighestBid prev = a.highest();
            a.appendBidAndSetHighest(new Bid(bidderId, amount, now));
            // notify the bidder who just lost the lead
            if (prev.exists() && !prev.bidderId.equals(bidderId)) {
                a.publish(new AuctionEvent(EventType.OUTBID, a.id(), prev.bidderId, amount, now, "manual bid"));
            }
            a.publish(new AuctionEvent(EventType.BID_PLACED, a.id(), bidderId, amount, now, null));

            // resolve standing proxies (may immediately counter)
            BidResult resolved = a.proxyResolver().resolve(a, now);

            // anti-snipe: maybe extend end time (under the same lock, via the auction)
            a.applyAntiSnipe(now);
            return resolved;
        }

        @Override public BidResult placeProxyBid(Auction a, String bidderId, long maxCents, Instant now) {
            if (!now.isBefore(a.endTime())) {
                a.close(now);
                return BidResult.rejected("auction closed", a.highest());
            }
            if (maxCents <= 0) return BidResult.rejected("max must be positive", a.highest());
            // a proxy max must at least be able to reach the current floor
            HighestBid h = a.highest();
            long floor = h.exists() ? h.amountCents + a.incrementCents : a.startPriceCents;
            if (maxCents < floor)
                return BidResult.rejected("proxy max below minimum " + money(floor), a.highest());

            a.registerProxy(new ProxyBid(bidderId, maxCents, now));
            a.publish(new AuctionEvent(EventType.BID_PLACED, a.id(), bidderId, maxCents, now, "proxy registered"));
            BidResult resolved = a.proxyResolver().resolve(a, now);
            a.applyAntiSnipe(now);
            return resolved;
        }

        @Override public AuctionOutcome close(Auction a, Instant now) {
            a.setState(new ClosedState());
            AuctionOutcome outcome = a.winnerStrategy().determine(a);
            a.setOutcome(outcome);
            a.publish(new AuctionEvent(EventType.CLOSED, a.id(), null, 0, now, outcome.toString()));
            if (outcome.status == Status.SOLD) {
                a.publish(new AuctionEvent(EventType.WON, a.id(), outcome.winnerId,
                        outcome.winningPriceCents, now, "winner determined"));
            } else {
                a.publish(new AuctionEvent(EventType.UNSOLD, a.id(), null, 0, now, "reserve not met / no bids"));
            }
            return outcome;
        }

        @Override public void cancel(Auction a) {
            if (a.highest().exists())
                throw new IllegalStateException("cannot cancel an auction that already has bids");
            a.setState(new CancelledState());
        }
    }

    /** Auction finished. Immutable from here (in this exercise). */
    static final class ClosedState implements AuctionState {
        @Override public String name() { return "CLOSED"; }
        @Override public BidResult placeBid(Auction a, String b, long amt, Instant now) {
            return BidResult.rejected("auction closed", a.highest());
        }
        @Override public BidResult placeProxyBid(Auction a, String b, long max, Instant now) {
            return BidResult.rejected("auction closed", a.highest());
        }
        @Override public AuctionOutcome close(Auction a, Instant now) { return a.outcome(); }
        @Override public void cancel(Auction a) {
            throw new IllegalStateException("cannot cancel a closed auction");
        }
    }

    static final class CancelledState implements AuctionState {
        @Override public String name() { return "CANCELLED"; }
        @Override public BidResult placeBid(Auction a, String b, long amt, Instant now) {
            return BidResult.rejected("auction cancelled", a.highest());
        }
        @Override public BidResult placeProxyBid(Auction a, String b, long max, Instant now) {
            return BidResult.rejected("auction cancelled", a.highest());
        }
        @Override public AuctionOutcome close(Auction a, Instant now) { return AuctionOutcome.cancelled(); }
        @Override public void cancel(Auction a) { /* idempotent */ }
    }

    /* =================================================================================
     * AUCTION -- aggregate root. Holds all state and enforces invariants under a
     * per-auction ReentrantLock. The lock wraps the COMPOUND critical section
     * (validate -> append -> set highest -> resolve proxies -> extend -> notify), which
     * a single CAS could not keep consistent. highest is an AtomicReference<HighestBid>
     * so reads outside the lock see a consistent immutable snapshot.
     * ================================================================================= */

    static final class Auction {
        private final String id;
        private final Item item;
        private final String sellerId;
        final long startPriceCents;
        final long incrementCents;
        final long reserveCents;            // hidden from bidders; used only at close
        private volatile Instant startTime;
        private volatile Instant endTime;
        private int extensionsUsed = 0;

        private volatile AuctionState state;
        private final AtomicReference<HighestBid> highest = new AtomicReference<>(HighestBid.NONE);
        private final List<Bid> history = new ArrayList<>();                 // guarded by lock
        private final Map<String, ProxyBid> proxies = new java.util.LinkedHashMap<>(); // guarded by lock
        private final List<BidObserver> observers = new CopyOnWriteArrayList<>();
        private volatile AuctionOutcome outcome = new AuctionOutcome(Status.OPEN, null, 0L);

        private final BidValidationStrategy validation;
        private final WinnerStrategy winnerStrategy;
        private final AntiSnipePolicy antiSnipe;
        private final ProxyBidResolver proxyResolver;

        private final ReentrantLock lock = new ReentrantLock();

        private Auction(AuctionSpec s) {
            this.id = s.id; this.item = s.item; this.sellerId = s.sellerId;
            this.startPriceCents = s.startPriceCents; this.incrementCents = s.incrementCents;
            this.reserveCents = s.reserveCents; this.startTime = s.startTime; this.endTime = s.endTime;
            this.validation = s.validation; this.winnerStrategy = s.winnerStrategy;
            this.antiSnipe = s.antiSnipe; this.proxyResolver = new ProxyBidResolver();
            this.state = new DraftState();
        }

        // ---- accessors used by states/strategies ----
        String id() { return id; }
        Item item() { return item; }
        String sellerId() { return sellerId; }
        Instant endTime() { return endTime; }
        int extensionsUsed() { return extensionsUsed; }
        void recordExtension() { extensionsUsed++; }
        HighestBid highest() { return highest.get(); }
        AuctionState state() { return state; }
        void setState(AuctionState s) { this.state = s; }
        BidValidationStrategy validation() { return validation; }
        WinnerStrategy winnerStrategy() { return winnerStrategy; }
        ProxyBidResolver proxyResolver() { return proxyResolver; }
        AuctionOutcome outcome() { return outcome; }
        void setOutcome(AuctionOutcome o) { this.outcome = o; }

        List<Bid> historySnapshot() {
            lock.lock();
            try { return new ArrayList<>(history); } finally { lock.unlock(); }
        }
        List<ProxyBid> proxySnapshot() {
            // called inside the lock by the resolver; copy for safe sorting
            return new ArrayList<>(proxies.values());
        }

        void addObserver(BidObserver o) { observers.add(o); }

        /** Publish synchronously here; in production, dispatch async off the lock. */
        void publish(AuctionEvent e) {
            for (BidObserver o : observers) {
                try { o.onEvent(e); } catch (RuntimeException ignored) { /* one bad observer must not break bids */ }
            }
        }

        // ---- mutations (only ever called while the lock is held) ----
        void appendBidAndSetHighest(Bid b) {
            history.add(b);
            highest.set(new HighestBid(b.bidderId, b.amountCents));
        }
        void registerProxy(ProxyBid p) { proxies.put(p.bidderId, p); }
        void commitProxyHighest(HighestBid h, Instant now) {
            history.add(new Bid(h.bidderId, h.amountCents, now));
            highest.set(h);
        }
        void applyAntiSnipe(Instant now) {
            Instant newEnd = antiSnipe.maybeExtend(this, now);
            if (newEnd.isAfter(endTime)) {
                endTime = newEnd;
                publish(new AuctionEvent(EventType.EXTENDED, id, null, 0, now,
                        "extended to " + newEnd));
            }
        }

        // ---- public operations: all take the per-auction lock ----
        void open() {
            lock.lock();
            try {
                if (!(state instanceof DraftState))
                    throw new IllegalStateException("only a DRAFT auction can be opened");
                state = new OpenState();
            } finally { lock.unlock(); }
        }

        BidResult placeBid(String bidderId, long amount, Instant now) {
            lock.lock();
            try { return state.placeBid(this, bidderId, amount, now); }
            finally { lock.unlock(); }
        }

        BidResult placeProxyBid(String bidderId, long maxCents, Instant now) {
            lock.lock();
            try { return state.placeProxyBid(this, bidderId, maxCents, now); }
            finally { lock.unlock(); }
        }

        AuctionOutcome close(Instant now) {
            lock.lock();
            try { return state.close(this, now); }
            finally { lock.unlock(); }
        }

        AuctionOutcome closeIfDue(Instant now) {
            lock.lock();
            try {
                if (state instanceof OpenState && !now.isBefore(endTime)) return state.close(this, now);
                return outcome;
            } finally { lock.unlock(); }
        }

        void cancel() {
            lock.lock();
            try { state.cancel(this); }
            finally { lock.unlock(); }
        }

        boolean reserveMet() { HighestBid h = highest.get(); return h.exists() && h.amountCents >= reserveCents; }
    }

    /* =================================================================================
     * BUILDER / FACTORY -- safe construction of an Auction with many optional params,
     * sensible defaults for the strategies (avoids telescoping constructors).
     * ================================================================================= */

    static final class AuctionSpec {
        String id, sellerId;
        Item item;
        long startPriceCents, incrementCents, reserveCents;
        Instant startTime, endTime;
        BidValidationStrategy validation = new MinIncrementValidation();
        WinnerStrategy winnerStrategy = new EnglishWinnerStrategy();
        AntiSnipePolicy antiSnipe = new NoExtensionPolicy();

        AuctionSpec id(String v) { this.id = v; return this; }
        AuctionSpec seller(String v) { this.sellerId = v; return this; }
        AuctionSpec item(Item v) { this.item = v; return this; }
        AuctionSpec startPrice(long c) { this.startPriceCents = c; return this; }
        AuctionSpec increment(long c) { this.incrementCents = c; return this; }
        AuctionSpec reserve(long c) { this.reserveCents = c; return this; }
        AuctionSpec window(Instant start, Instant end) { this.startTime = start; this.endTime = end; return this; }
        AuctionSpec validation(BidValidationStrategy v) { this.validation = v; return this; }
        AuctionSpec winner(WinnerStrategy v) { this.winnerStrategy = v; return this; }
        AuctionSpec antiSnipe(AntiSnipePolicy v) { this.antiSnipe = v; return this; }

        Auction build() {
            if (id == null || item == null || sellerId == null)
                throw new IllegalArgumentException("id, item, seller required");
            if (incrementCents <= 0) incrementCents = 100; // default $1.00
            if (endTime == null || startTime == null)
                throw new IllegalArgumentException("time window required");
            return new Auction(this);
        }
    }

    /* =================================================================================
     * REPOSITORIES -- persistence seam (in-memory). Swap for DB-backed in production;
     * a distributed design would use optimistic concurrency here.
     * ================================================================================= */

    interface AuctionRepository {
        void save(Auction a);
        Optional<Auction> find(String id);
    }
    interface UserRepository {
        void save(User u);
        Optional<User> find(String id);
    }

    static final class InMemoryAuctionRepository implements AuctionRepository {
        private final Map<String, Auction> store = new ConcurrentHashMap<>();
        @Override public void save(Auction a) { store.put(a.id(), a); }
        @Override public Optional<Auction> find(String id) { return Optional.ofNullable(store.get(id)); }
    }
    static final class InMemoryUserRepository implements UserRepository {
        private final Map<String, User> store = new ConcurrentHashMap<>();
        @Override public void save(User u) { store.put(u.id, u); }
        @Override public Optional<User> find(String id) { return Optional.ofNullable(store.get(id)); }
    }

    /* =================================================================================
     * FACADE -- AuctionService. One clean entry point; hides repos, locks, wiring.
     * ================================================================================= */

    static final class AuctionService {
        private final AuctionRepository auctionRepo;
        private final UserRepository userRepo;
        private final Clock clock;

        AuctionService(AuctionRepository ar, UserRepository ur, Clock clock) {
            this.auctionRepo = ar; this.userRepo = ur; this.clock = clock;
        }

        String createAuction(AuctionSpec spec) {
            userRepo.find(spec.sellerId)
                .orElseThrow(() -> new IllegalArgumentException("unknown seller: " + spec.sellerId));
            Auction a = spec.build();
            auctionRepo.save(a);
            return a.id();
        }

        void open(String auctionId) { require(auctionId).open(); }

        void watch(String auctionId, BidObserver observer) { require(auctionId).addObserver(observer); }

        BidResult placeBid(String auctionId, String bidderId, long amountCents) {
            userRepo.find(bidderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown bidder: " + bidderId));
            return require(auctionId).placeBid(bidderId, amountCents, now());
        }

        BidResult placeProxyBid(String auctionId, String bidderId, long maxCents) {
            userRepo.find(bidderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown bidder: " + bidderId));
            return require(auctionId).placeProxyBid(bidderId, maxCents, now());
        }

        AuctionOutcome closeIfDue(String auctionId) { return require(auctionId).closeIfDue(now()); }
        AuctionOutcome forceClose(String auctionId) { return require(auctionId).close(now()); }
        void cancel(String auctionId) { require(auctionId).cancel(); }

        HighestBid highest(String auctionId) { return require(auctionId).highest(); }

        private Auction require(String id) {
            return auctionRepo.find(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown auction: " + id));
        }
        private Instant now() { return clock.instant(); }
    }

    /* =================================================================================
     * DEMO -- illustrates the key scenarios. NOTE: this file is a review artifact; the
     * main() is only to make the behavior concrete for the reader.
     * ================================================================================= */

    public static void main(String[] args) throws Exception {
        // Deterministic, advanceable clock for the demo.
        MutableClock clock = new MutableClock(Instant.parse("2026-06-25T10:00:00Z"));
        AuctionService service = new AuctionService(
                new InMemoryAuctionRepository(), new InMemoryUserRepository(), clock);

        // seed users
        UserRepository users = field(service);
        users.save(new User("seller-1", "Alice"));
        users.save(new User("alex", "Alex"));
        users.save(new User("blair", "Blair"));
        users.save(new User("cory", "Cory"));

        Instant start = clock.instant();
        Instant end   = start.plus(Duration.ofMinutes(10));

        // ----------------------------------------------------------------------------
        System.out.println("=== Scenario 1: basic English auction with reserve ===");
        String a1 = service.createAuction(new AuctionSpec()
                .id("auction-1").seller("seller-1")
                .item(new Item("item-1", "Vintage Camera", "1970s rangefinder"))
                .startPrice(10_000).increment(500).reserve(15_000)   // $100 start, $5 inc, $150 reserve
                .window(start, end)
                .antiSnipe(new FixedWindowAntiSnipe(Duration.ofMinutes(1), Duration.ofMinutes(2), 2)));
        service.watch(a1, new ConsoleAuditObserver());
        service.watch(a1, new OutbidNotifier());
        service.watch(a1, new PaymentObserver());
        service.open(a1);

        System.out.println("bid 100 -> " + service.placeBid(a1, "alex", 10_000));   // accepted
        System.out.println("bid 102 -> " + service.placeBid(a1, "blair", 10_200));  // rejected (< +inc)
        System.out.println("bid 105 -> " + service.placeBid(a1, "blair", 10_500));  // accepted, outbids alex
        System.out.println("self  -> " + service.placeBid(a1, "blair", 12_000));    // accepted (self only if... )
        // NB: above is rejected actually because blair is already highest:
        //   "self  -> REJECTED (you are already the highest bidder)"
        System.out.println("bid 160 -> " + service.placeBid(a1, "alex", 16_000));   // accepted, beats reserve
        System.out.println("reserve met? " + service.highest(a1).amountCents + " >= 15000");

        clock.advance(Duration.ofMinutes(11)); // past end
        System.out.println("close -> " + service.closeIfDue(a1));                    // SOLD to alex

        // ----------------------------------------------------------------------------
        System.out.println("\n=== Scenario 2: proxy / auto-bidding ===");
        Instant s2 = clock.instant(), e2 = s2.plus(Duration.ofMinutes(10));
        String a2 = service.createAuction(new AuctionSpec()
                .id("auction-2").seller("seller-1")
                .item(new Item("item-2", "Mechanical Keyboard", "TKL"))
                .startPrice(5_000).increment(100).reserve(0)
                .window(s2, e2));
        service.watch(a2, new ConsoleAuditObserver());
        service.open(a2);
        System.out.println("alex proxy max $80 -> " + service.placeProxyBid(a2, "alex", 8_000));
        System.out.println("blair proxy max $70 -> " + service.placeProxyBid(a2, "blair", 7_000));
        // alex should lead at blair's max + increment = $71, capped by his $80 max
        System.out.println("highest now = " + money(service.highest(a2).amountCents)
                + " by " + service.highest(a2).bidderId);
        clock.advance(Duration.ofMinutes(11));
        System.out.println("close -> " + service.closeIfDue(a2));

        // ----------------------------------------------------------------------------
        System.out.println("\n=== Scenario 3: anti-snipe extension ===");
        Instant s3 = clock.instant(), e3 = s3.plus(Duration.ofMinutes(5));
        String a3 = service.createAuction(new AuctionSpec()
                .id("auction-3").seller("seller-1")
                .item(new Item("item-3", "Art Print", "limited"))
                .startPrice(2_000).increment(100).reserve(0)
                .window(s3, e3)
                .antiSnipe(new FixedWindowAntiSnipe(Duration.ofMinutes(1), Duration.ofMinutes(2), 2)));
        service.watch(a3, new ConsoleAuditObserver());
        service.open(a3);
        service.placeBid(a3, "alex", 2_000);
        clock.advance(Duration.ofMinutes(4).plus(Duration.ofSeconds(30))); // 30s before end -> in window
        System.out.println("late snipe -> " + service.placeBid(a3, "blair", 2_100)); // should extend end
        System.out.println("close-if-due (not yet due, extended) -> " + service.closeIfDue(a3));
        clock.advance(Duration.ofMinutes(3)); // now past the extended end
        System.out.println("close -> " + service.closeIfDue(a3));

        // ----------------------------------------------------------------------------
        System.out.println("\n=== Scenario 4: reserve NOT met -> UNSOLD ===");
        Instant s4 = clock.instant(), e4 = s4.plus(Duration.ofMinutes(5));
        String a4 = service.createAuction(new AuctionSpec()
                .id("auction-4").seller("seller-1")
                .item(new Item("item-4", "Rare Coin", "1909"))
                .startPrice(1_000).increment(100).reserve(50_000) // $500 reserve, nobody reaches it
                .window(s4, e4));
        service.watch(a4, new ConsoleAuditObserver());
        service.open(a4);
        service.placeBid(a4, "cory", 1_000);
        clock.advance(Duration.ofMinutes(6));
        System.out.println("close -> " + service.closeIfDue(a4));  // UNSOLD

        // ----------------------------------------------------------------------------
        System.out.println("\n=== Scenario 5: concurrency -- many bidders racing ===");
        Instant s5 = clock.instant(), e5 = s5.plus(Duration.ofHours(1));
        String a5 = service.createAuction(new AuctionSpec()
                .id("auction-5").seller("seller-1")
                .item(new Item("item-5", "GPU", "flagship"))
                .startPrice(0).increment(100).reserve(0)
                .window(s5, e5));
        service.open(a5);
        final int threads = 8, perThread = 200;
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final String bidder = "racer-" + t;
            users.save(new User(bidder, bidder));
            Thread th = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    HighestBid h = service.highest(a5);
                    long next = (h.exists() ? h.amountCents : 0) + 100;
                    service.placeBid(a5, bidder, next); // many will be rejected as others race ahead
                }
            });
            pool.add(th);
        }
        for (Thread th : pool) th.start();
        for (Thread th : pool) th.join();
        HighestBid finalHigh = service.highest(a5);
        System.out.println("final highest after race = " + money(finalHigh.amountCents)
                + " by " + finalHigh.bidderId
                + " (consistent single winner, no torn state)");

        System.out.println("\nAll scenarios complete.");
    }

    /** Reflection helper for the demo only -- reach the UserRepository to seed users. */
    private static UserRepository field(AuctionService s) {
        try {
            java.lang.reflect.Field f = AuctionService.class.getDeclaredField("userRepo");
            f.setAccessible(true);
            return (UserRepository) f.get(s);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    /** An advanceable Clock for deterministic demos/tests. */
    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}

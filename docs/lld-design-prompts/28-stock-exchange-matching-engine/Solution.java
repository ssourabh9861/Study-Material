/* ============================================================================
 * Stock Exchange / Order Matching Engine  —  single-file LLD revision artifact
 * ----------------------------------------------------------------------------
 * REVIEW / REVISION ARTIFACT ONLY. Read-and-revise; not meant to be compiled or
 * benchmarked. It is complete (no TODOs, no omitted bodies) and correct-by-inspection.
 *
 * ----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * ----------------------------------------------------------------------------
 * Functional:
 *   1. Order types? LIMIT + MARKET only, or also STOP / STOP_LIMIT / ICEBERG?
 *   2. Time-in-force? GTC / IOC / FOK?
 *   3. Matching policy? Price-time (FIFO) only, or pro-rata / call-auction (pluggable)?
 *   4. One symbol or many? (many => one book per symbol, sharded for concurrency)
 *   5. Cancel/modify semantics? Does a price change lose time priority? (yes, standard)
 *   6. Fill price = resting (maker) price or incoming price? (maker price, standard)
 *   7. Self-trade prevention required?
 *   8. Do we validate buying power / positions (risk) before accepting?
 * Non-functional:
 *   9. Throughput/latency target? HFT single-threaded loop vs clean thread-safe engine?
 *  10. Concurrency model? Many producers concurrently? Must engine be thread-safe?
 *  11. Deterministic / reproducible matching required? (yes for an exchange)
 *  12. Persistence / crash recovery / audit journal needed?
 *  13. Price/qty precision — integer ticks/lots (chosen) or decimals?
 * Scope:
 *  IN : LIMIT+MARKET, GTC/IOC/FOK, price-time matching, partial fills, cancel/modify,
 *       BBO + trade feed, basic account balance/position updates, per-symbol thread-safety.
 *  OUT: settlement/clearing, persistence, FIX/network, auth, stop orders, auctions,
 *       circuit breakers (all discussed as extensions).
 *
 * ----------------------------------------------------------------------------
 * PATTERNS DEMONSTRATED
 *   Strategy  -> MatchingStrategy (PriceTimeMatchingStrategy is the default)
 *   Observer  -> MarketDataPublisher / MarketDataListener (trade tape, BBO, events)
 *   State     -> OrderState enum with a legal-transition table
 *   Facade    -> MatchingEngine.submit/cancel/modify (the only client surface)
 *   Factory   -> Order.limit(...)/Order.market(...) with invariant validation
 *
 * KEY DECISIONS
 *   - Book = two TreeMaps (bids desc, asks asc) of price -> PriceLevel(Deque<Order>)
 *   - Trades at maker price; market orders never rest; partial fills supported
 *   - Per-symbol ReentrantLock; events published AFTER unlock; AtomicLong ids/seq
 *   - Integer prices/quantities only (no floating point)
 * ============================================================================ */

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/* =========================== Enums (classifiers) =========================== */

enum Side { BUY, SELL }

enum OrderType { LIMIT, MARKET }

/** Time-in-force: how a residual (unfilled) quantity is treated after matching. */
enum TimeInForce {
    GTC,  // Good-Till-Cancelled: remainder of a LIMIT rests in the book
    IOC,  // Immediate-Or-Cancel: fill what you can now, cancel the rest
    FOK   // Fill-Or-Kill: fully fill immediately or reject with no side effects
}

/**
 * Order lifecycle modelled as an enum (State pattern, lightweight form).
 * The transition table forbids illegal moves (e.g. filling a cancelled order).
 */
enum OrderState {
    NEW, OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED;

    boolean canTransitionTo(OrderState next) {
        switch (this) {
            case NEW:
                return next == OPEN || next == PARTIALLY_FILLED || next == FILLED
                        || next == CANCELLED || next == REJECTED;
            case OPEN:
                return next == PARTIALLY_FILLED || next == FILLED || next == CANCELLED;
            case PARTIALLY_FILLED:
                return next == PARTIALLY_FILLED || next == FILLED || next == CANCELLED;
            case FILLED:
            case CANCELLED:
            case REJECTED:
                return false; // terminal states
            default:
                return false;
        }
    }

    boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}

/* =============================== Order ==================================== */

/**
 * An order. Identity (id, account, symbol, side, type, tif, limitPrice, seq,
 * timestamp) is immutable; remainingQty and state mutate as the order fills.
 * Construction goes through the static factory methods (validates invariants).
 */
final class Order {
    private final long id;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final TimeInForce tif;
    private final long limitPrice;   // unused (0) for MARKET orders
    private final long originalQty;
    private final long seq;          // monotonic; breaks price-time ties deterministically
    private final long timestamp;
    private long remainingQty;
    private OrderState state;

    private Order(long id, String accountId, String symbol, Side side, OrderType type,
                  TimeInForce tif, long limitPrice, long qty, long seq) {
        this.id = id;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.tif = tif;
        this.limitPrice = limitPrice;
        this.originalQty = qty;
        this.remainingQty = qty;
        this.seq = seq;
        this.timestamp = System.nanoTime();
        this.state = OrderState.NEW;
    }

    // ---- Factory methods (validate invariants in one place) ----
    static Order limit(long id, String account, String symbol, Side side, long price,
                       long qty, TimeInForce tif, long seq) {
        validate(account, symbol, qty);
        if (price <= 0) throw new IllegalArgumentException("LIMIT order needs positive price");
        return new Order(id, account, symbol, side, OrderType.LIMIT, tif, price, qty, seq);
    }

    static Order market(long id, String account, String symbol, Side side, long qty, long seq) {
        validate(account, symbol, qty);
        // MARKET orders never rest; IOC semantics for any remainder.
        return new Order(id, account, symbol, side, OrderType.MARKET, TimeInForce.IOC, 0, qty, seq);
    }

    private static void validate(String account, String symbol, long qty) {
        if (account == null || account.isEmpty()) throw new IllegalArgumentException("account required");
        if (symbol == null || symbol.isEmpty()) throw new IllegalArgumentException("symbol required");
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
    }

    // ---- State transitions guarded by the enum table ----
    void transition(OrderState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal transition " + state + " -> " + next
                    + " for order " + id);
        }
        this.state = next;
    }

    /** Reduce remaining quantity by a fill and advance state accordingly. */
    void applyFill(long qty) {
        if (qty <= 0 || qty > remainingQty) {
            throw new IllegalArgumentException("invalid fill qty " + qty + " (remaining " + remainingQty + ")");
        }
        remainingQty -= qty;
        if (remainingQty == 0) {
            transition(OrderState.FILLED);
        } else {
            // first partial moves NEW/OPEN -> PARTIALLY_FILLED; subsequent partials stay
            if (state != OrderState.PARTIALLY_FILLED) transition(OrderState.PARTIALLY_FILLED);
        }
    }

    /**
     * Shrink remaining quantity for an in-place modify (a quantity DECREASE that
     * keeps queue position). This is NOT a trade fill: state is unchanged. Caller
     * must also adjust the owning PriceLevel's aggregate quantity.
     */
    void reduceQuantityInPlace(long newRemaining) {
        if (newRemaining <= 0 || newRemaining >= remainingQty) {
            throw new IllegalArgumentException("modify must strictly decrease remaining qty");
        }
        this.remainingQty = newRemaining;
    }

    boolean isMarket() { return type == OrderType.MARKET; }
    long filledQty() { return originalQty - remainingQty; }

    long id() { return id; }
    String accountId() { return accountId; }
    String symbol() { return symbol; }
    Side side() { return side; }
    OrderType type() { return type; }
    TimeInForce tif() { return tif; }
    long limitPrice() { return limitPrice; }
    long originalQty() { return originalQty; }
    long remainingQty() { return remainingQty; }
    long seq() { return seq; }
    OrderState state() { return state; }

    @Override public String toString() {
        return String.format("Order#%d[%s %s %s qty=%d/%d price=%s tif=%s state=%s]",
                id, side, type, symbol, remainingQty, originalQty,
                isMarket() ? "MKT" : Long.toString(limitPrice), tif, state);
    }
}

/* =============================== Trade =================================== */

/** Immutable record of one execution (a fill) between a buy and a sell order. */
final class Trade {
    private final long tradeId;
    private final long buyOrderId;
    private final long sellOrderId;
    private final String symbol;
    private final long price;     // maker (resting) price
    private final long quantity;
    private final long timestamp;

    Trade(long tradeId, long buyOrderId, long sellOrderId, String symbol, long price, long quantity) {
        this.tradeId = tradeId;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = System.nanoTime();
    }

    long buyOrderId() { return buyOrderId; }
    long sellOrderId() { return sellOrderId; }
    String symbol() { return symbol; }
    long price() { return price; }
    long quantity() { return quantity; }

    @Override public String toString() {
        return String.format("Trade#%d[%s %d @ %d  buy#%d x sell#%d]",
                tradeId, symbol, quantity, price, buyOrderId, sellOrderId);
    }
}

/* ============================= PriceLevel ================================ */

/** All resting orders at a single price, in FIFO arrival order. */
final class PriceLevel {
    private final long price;
    private final Deque<Order> orders = new ArrayDeque<>();
    private long totalQty;

    PriceLevel(long price) { this.price = price; }

    void add(Order o) { orders.addLast(o); totalQty += o.remainingQty(); }

    Order peek() { return orders.peekFirst(); }

    /** Remove the head (fully-filled or cancelled) order. */
    Order poll() {
        Order o = orders.pollFirst();
        if (o != null) totalQty -= o.remainingQty();
        return o;
    }

    /** Remove a specific resting order (used by cancel/modify). */
    boolean remove(Order o) {
        boolean removed = orders.remove(o);
        if (removed) totalQty -= o.remainingQty();
        return removed;
    }

    /** Called when a resting order is partially filled in place. */
    void decreaseTotal(long qty) { totalQty -= qty; }

    boolean isEmpty() { return orders.isEmpty(); }
    long price() { return price; }
    long totalQty() { return totalQty; }
    Deque<Order> orders() { return orders; }
}

/* ============================== OrderBook ================================ */

/**
 * One order book per symbol. Two sorted maps: bids (descending so first key is the
 * highest buy) and asks (ascending so first key is the lowest sell). An id->order
 * index gives O(1) cancel. Owns the per-symbol lock (serialization domain).
 */
final class OrderBook {
    private final String symbol;
    // bids: highest price first  |  asks: lowest price first
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, Order> index = new ConcurrentHashMap<>(); // id -> resting order
    private final ReentrantLock lock = new ReentrantLock();

    OrderBook(String symbol) { this.symbol = symbol; }

    ReentrantLock lock() { return lock; }
    String symbol() { return symbol; }

    NavigableMap<Long, PriceLevel> sideFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** The book on the opposite side of the given taker side (what we match against). */
    NavigableMap<Long, PriceLevel> oppositeSide(Side takerSide) {
        return takerSide == Side.BUY ? asks : bids;
    }

    Long bestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
    Long bestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }

    /** Rest a (remaining) limit order in the book. */
    void rest(Order o) {
        NavigableMap<Long, PriceLevel> book = sideFor(o.side());
        book.computeIfAbsent(o.limitPrice(), PriceLevel::new).add(o);
        index.put(o.id(), o);
        if (o.state() == OrderState.NEW) o.transition(OrderState.OPEN);
    }

    /** Remove a resting order by id; returns the order if found and removed. */
    Order cancel(long orderId) {
        Order o = index.get(orderId);
        if (o == null) return null;
        NavigableMap<Long, PriceLevel> book = sideFor(o.side());
        PriceLevel level = book.get(o.limitPrice());
        if (level != null && level.remove(o)) {
            if (level.isEmpty()) book.remove(o.limitPrice());
            index.remove(orderId);
            return o;
        }
        return null;
    }

    Order find(long orderId) { return index.get(orderId); }

    void deindex(long orderId) { index.remove(orderId); }

    /**
     * In-place quantity decrease that keeps the order's queue position (FIFO).
     * Adjusts both the order and its price level's aggregate quantity.
     */
    void reduceInPlace(Order o, long newRemaining) {
        long delta = o.remainingQty() - newRemaining;
        o.reduceQuantityInPlace(newRemaining);
        PriceLevel level = sideFor(o.side()).get(o.limitPrice());
        if (level != null) level.decreaseTotal(delta);
    }

    String bboString() {
        Long bb = bestBid(), ba = bestAsk();
        long bbq = bb == null ? 0 : bids.get(bb).totalQty();
        long baq = ba == null ? 0 : asks.get(ba).totalQty();
        return String.format("BBO[%s] bid=%s(%d) ask=%s(%d)",
                symbol, bb == null ? "-" : bb, bbq, ba == null ? "-" : ba, baq);
    }
}

/* ============================ MatchingStrategy =========================== */

/** Strategy pattern: how an incoming order is matched against the book. */
interface MatchingStrategy {
    /**
     * Match the incoming order against the book, mutating both as fills occur.
     * Returns the list of trades produced (residual handling is the engine's job).
     * The engine holds the book lock while calling this.
     */
    List<Trade> match(Order incoming, OrderBook book, AtomicLong tradeIdGen);
}

/**
 * Default exchange behavior: PRICE-TIME (FIFO) priority. Best price first; within a
 * price level, earliest arrival first. Trades execute at the resting (maker) price.
 */
final class PriceTimeMatchingStrategy implements MatchingStrategy {

    @Override
    public List<Trade> match(Order incoming, OrderBook book, AtomicLong tradeIdGen) {
        List<Trade> trades = new ArrayList<>();
        NavigableMap<Long, PriceLevel> opposite = book.oppositeSide(incoming.side());

        while (incoming.remainingQty() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, PriceLevel> bestEntry = opposite.firstEntry();
            long restingPrice = bestEntry.getKey();
            if (!crosses(incoming, restingPrice)) break; // price no longer acceptable

            PriceLevel level = bestEntry.getValue();
            while (incoming.remainingQty() > 0 && !level.isEmpty()) {
                Order resting = level.peek();
                long fillQty = Math.min(incoming.remainingQty(), resting.remainingQty());

                // Apply the fill to both orders; trade at the maker (resting) price.
                resting.applyFill(fillQty);
                incoming.applyFill(fillQty);
                level.decreaseTotal(fillQty);

                long buyId  = incoming.side() == Side.BUY ? incoming.id() : resting.id();
                long sellId = incoming.side() == Side.BUY ? resting.id() : incoming.id();
                trades.add(new Trade(tradeIdGen.incrementAndGet(), buyId, sellId,
                        book.symbol(), restingPrice, fillQty));

                if (resting.remainingQty() == 0) {
                    level.poll();                 // remove fully-filled maker
                    book.deindex(resting.id());
                }
            }
            if (level.isEmpty()) opposite.remove(restingPrice); // clean empty level
        }
        return trades;
    }

    /** Does the incoming order's price cross the resting price (is it marketable)? */
    private boolean crosses(Order incoming, long restingPrice) {
        if (incoming.isMarket()) return true; // market crosses any price
        return incoming.side() == Side.BUY
                ? incoming.limitPrice() >= restingPrice   // willing to pay >= ask
                : incoming.limitPrice() <= restingPrice;  // willing to sell <= bid
    }
}

/* ============================ Market data (Observer) ===================== */

/** Base class for everything published on the feed. */
abstract class ExchangeEvent {
    final long timestamp = System.nanoTime();
    abstract String describe();
    @Override public String toString() { return describe(); }
}

final class OrderAcceptedEvent extends ExchangeEvent {
    final Order order;
    OrderAcceptedEvent(Order o) { this.order = o; }
    @Override String describe() { return "ACCEPTED  " + order; }
}

final class OrderRejectedEvent extends ExchangeEvent {
    final String reason; final String detail;
    OrderRejectedEvent(String reason, String detail) { this.reason = reason; this.detail = detail; }
    @Override String describe() { return "REJECTED  " + reason + " :: " + detail; }
}

final class OrderCancelledEvent extends ExchangeEvent {
    final Order order;
    OrderCancelledEvent(Order o) { this.order = o; }
    @Override String describe() { return "CANCELLED " + order; }
}

final class TradeEvent extends ExchangeEvent {
    final Trade trade;
    TradeEvent(Trade t) { this.trade = t; }
    @Override String describe() { return "TRADE     " + trade; }
}

final class BookUpdatedEvent extends ExchangeEvent {
    final String bbo;
    BookUpdatedEvent(String bbo) { this.bbo = bbo; }
    @Override String describe() { return "BOOK      " + bbo; }
}

/** Observer subscriber. Small interface (ISP) — consumers implement just this. */
interface MarketDataListener {
    void onEvent(ExchangeEvent event);
}

/** Observer hub: fans events out to all subscribers. Thread-safe subscriber list. */
final class MarketDataPublisher {
    private final List<MarketDataListener> listeners = new CopyOnWriteArrayList<>();

    void subscribe(MarketDataListener l) { listeners.add(l); }
    void unsubscribe(MarketDataListener l) { listeners.remove(l); }

    /** Called by the engine AFTER releasing the book lock (no slow work under lock). */
    void publish(ExchangeEvent event) {
        for (MarketDataListener l : listeners) {
            try { l.onEvent(event); }
            catch (RuntimeException ex) { /* a bad listener must not break the engine */ }
        }
    }
}

/* =============================== Account ================================= */

/** Tracks cash and per-symbol positions; mutations are synchronized per account. */
final class Account {
    private final String id;
    private long cash;
    private final Map<String, Long> positions = new ConcurrentHashMap<>();

    Account(String id, long openingCash) { this.id = id; this.cash = openingCash; }

    /** Apply one fill from this account's perspective. */
    synchronized void applyFill(Side side, long price, long qty, String symbol) {
        long notional = price * qty;
        if (side == Side.BUY) {
            cash -= notional;
            positions.merge(symbol, qty, Long::sum);
        } else {
            cash += notional;
            positions.merge(symbol, -qty, Long::sum);
        }
    }

    String id() { return id; }
    synchronized long cash() { return cash; }
    long position(String symbol) { return positions.getOrDefault(symbol, 0L); }

    @Override public String toString() {
        return String.format("Account[%s cash=%d positions=%s]", id, cash(), positions);
    }
}

/* ============================ OrderRequest =============================== */

/** Inbound request DTO; the engine turns it into an Order after validation. */
final class OrderRequest {
    final String accountId;
    final String symbol;
    final Side side;
    final OrderType type;
    final TimeInForce tif;
    final long price; // ignored for MARKET
    final long qty;

    private OrderRequest(String accountId, String symbol, Side side, OrderType type,
                         TimeInForce tif, long price, long qty) {
        this.accountId = accountId; this.symbol = symbol; this.side = side;
        this.type = type; this.tif = tif; this.price = price; this.qty = qty;
    }

    static OrderRequest limit(String account, String symbol, Side side, long price,
                              long qty, TimeInForce tif) {
        return new OrderRequest(account, symbol, side, OrderType.LIMIT, tif, price, qty);
    }

    static OrderRequest market(String account, String symbol, Side side, long qty) {
        return new OrderRequest(account, symbol, side, OrderType.MARKET, TimeInForce.IOC, 0, qty);
    }
}

/* ============================= MatchingEngine =========================== */

/**
 * Facade + router. The only surface clients touch: submit / cancel / modify.
 * Hides book routing, per-symbol locking, matching, account settlement, publishing.
 */
final class MatchingEngine {
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final MatchingStrategy strategy;          // injected (DIP)
    private final MarketDataPublisher publisher;      // injected (DIP)
    private final AtomicLong orderIdGen = new AtomicLong();
    private final AtomicLong seqGen = new AtomicLong();   // monotonic tie-breaker
    private final AtomicLong tradeIdGen = new AtomicLong();

    MatchingEngine(MatchingStrategy strategy, MarketDataPublisher publisher) {
        this.strategy = strategy;
        this.publisher = publisher;
    }

    void registerAccount(Account a) { accounts.put(a.id(), a); }

    private OrderBook bookFor(String symbol) {
        return books.computeIfAbsent(symbol, OrderBook::new);
    }

    /** Submit an order: validate -> match -> handle residual -> settle -> publish. */
    Order submit(OrderRequest req) {
        // ---- validation (publishes rejection, returns null on failure) ----
        if (req.qty <= 0) { publisher.publish(new OrderRejectedEvent("BAD_QTY", "qty<=0")); return null; }
        if (req.type == OrderType.LIMIT && req.price <= 0) {
            publisher.publish(new OrderRejectedEvent("BAD_PRICE", "limit price<=0")); return null;
        }
        if (!accounts.containsKey(req.accountId)) {
            publisher.publish(new OrderRejectedEvent("NO_ACCOUNT", req.accountId)); return null;
        }

        long id = orderIdGen.incrementAndGet();
        long seq = seqGen.incrementAndGet();
        Order order = (req.type == OrderType.LIMIT)
                ? Order.limit(id, req.accountId, req.symbol, req.side, req.price, req.qty, req.tif, seq)
                : Order.market(id, req.accountId, req.symbol, req.side, req.qty, seq);
        // Engine owns the id->account mapping; recorded BEFORE matching so settle()
        // can resolve fills for both the taker (this order) and any makers.
        orderAccount.put(id, req.accountId);

        OrderBook book = bookFor(req.symbol);
        List<Trade> trades;
        boolean rejected = false;

        // ---- critical section: all book mutation under the per-symbol lock ----
        book.lock().lock();
        try {
            // FOK: dry-run first; if it can't fully fill, reject with zero side effects.
            if (order.tif() == TimeInForce.FOK && !canFullyFill(order, book)) {
                rejected = true;
                trades = Collections.emptyList();
            } else {
                trades = strategy.match(order, book, tradeIdGen);

                // residual handling
                if (order.remainingQty() > 0) {
                    if (order.tif() == TimeInForce.GTC && !order.isMarket()) {
                        book.rest(order);                 // remainder rests
                    } else {
                        // IOC / MARKET / leftover -> cancel remainder
                        if (!order.state().isTerminal()) order.transition(OrderState.CANCELLED);
                    }
                }
            }
        } finally {
            book.lock().unlock();
        }

        // ---- settle accounts (each account guards its own state) ----
        for (Trade t : trades) settle(t, req.symbol);

        // ---- publish AFTER unlock (no slow listener work under the lock) ----
        if (rejected) {
            order.transition(OrderState.REJECTED);
            publisher.publish(new OrderRejectedEvent("FOK_UNFILLED", "order#" + order.id()));
            return order;
        }
        publisher.publish(new OrderAcceptedEvent(order));
        for (Trade t : trades) publisher.publish(new TradeEvent(t));
        publisher.publish(new BookUpdatedEvent(book.bboString()));
        return order;
    }

    /** Cancel a resting order. Returns true if it was found and removed. */
    boolean cancel(String symbol, long orderId) {
        OrderBook book = bookFor(symbol);
        Order cancelled;
        book.lock().lock();
        try {
            cancelled = book.cancel(orderId);
            if (cancelled != null && !cancelled.state().isTerminal()) {
                cancelled.transition(OrderState.CANCELLED);
            }
        } finally {
            book.lock().unlock();
        }
        if (cancelled == null) return false;
        publisher.publish(new OrderCancelledEvent(cancelled));
        publisher.publish(new BookUpdatedEvent(book.bboString()));
        return true;
    }

    /**
     * Modify a resting order. A price change or quantity INCREASE loses time
     * priority (cancel + re-submit with a fresh sequence). A pure quantity
     * DECREASE keeps priority (adjusted in place). Returns the new/updated order.
     */
    Order modify(String symbol, long orderId, long newPrice, long newQty) {
        if (newPrice <= 0 || newQty <= 0) {
            publisher.publish(new OrderRejectedEvent("BAD_MODIFY", "price/qty<=0")); return null;
        }
        OrderBook book = bookFor(symbol);
        Order existing;
        boolean inPlaceDecrease = false;

        book.lock().lock();
        try {
            existing = book.find(orderId);
            if (existing == null || existing.state().isTerminal()) {
                book.lock().unlock();
                publisher.publish(new OrderRejectedEvent("MODIFY_MISS", "order#" + orderId));
                return null;
            }
            boolean priceUnchanged = (existing.limitPrice() == newPrice);
            boolean qtyDecrease = (newQty < existing.remainingQty());
            if (priceUnchanged && qtyDecrease) {
                // keep queue position: shrink remaining in place (no trade, state unchanged)
                book.reduceInPlace(existing, newQty);
                inPlaceDecrease = true;
            } else {
                // lose priority: remove old, will re-submit a fresh order below
                book.cancel(orderId);
                if (!existing.state().isTerminal()) existing.transition(OrderState.CANCELLED);
            }
        } finally {
            if (book.lock().isHeldByCurrentThread()) book.lock().unlock();
        }

        if (inPlaceDecrease) {
            publisher.publish(new BookUpdatedEvent(book.bboString()));
            return existing;
        }
        // re-submit as a fresh order (new sequence -> back of the queue)
        publisher.publish(new OrderCancelledEvent(existing));
        OrderRequest re = OrderRequest.limit(existing.accountId(), symbol, existing.side(),
                newPrice, newQty, existing.tif());
        return submit(re);
    }

    /* ---------- internals ---------- */

    /** Settle one trade against both accounts (resolved via the id->account map). */
    private void settle(Trade t, String symbol) {
        Account buyer = accountOf(t.buyOrderId());
        Account seller = accountOf(t.sellOrderId());
        if (buyer != null) buyer.applyFill(Side.BUY, t.price(), t.quantity(), symbol);
        if (seller != null) seller.applyFill(Side.SELL, t.price(), t.quantity(), symbol);
    }

    // id -> account resolution: populated at submit time (before matching).
    private final Map<Long, String> orderAccount = new ConcurrentHashMap<>();
    private Account accountOf(long orderId) {
        String acc = orderAccount.get(orderId);
        return acc == null ? null : accounts.get(acc);
    }

    /** FOK dry-run: can the order be fully filled immediately without mutating? */
    private boolean canFullyFill(Order order, OrderBook book) {
        long need = order.remainingQty();
        for (Map.Entry<Long, PriceLevel> e : book.oppositeSide(order.side()).entrySet()) {
            long price = e.getKey();
            boolean ok = order.isMarket()
                    || (order.side() == Side.BUY ? order.limitPrice() >= price
                                                 : order.limitPrice() <= price);
            if (!ok) break;
            need -= e.getValue().totalQty();
            if (need <= 0) return true;
        }
        return need <= 0;
    }

    OrderBook book(String symbol) { return bookFor(symbol); }
}

/* ================================ Demo ================================== */

public class Solution {

    /** A simple console listener that prints every event (the "tape"). */
    static final class ConsoleListener implements MarketDataListener {
        @Override public void onEvent(ExchangeEvent e) { System.out.println("  feed> " + e); }
    }

    public static void main(String[] args) {
        // ---- wire up engine with the price-time strategy and a console feed (Observer) ----
        MarketDataPublisher publisher = new MarketDataPublisher();
        publisher.subscribe(new ConsoleListener());
        MatchingEngine engine = new MatchingEngine(new PriceTimeMatchingStrategy(), publisher);

        Account alice = new Account("ALICE", 1_000_000);
        Account bob   = new Account("BOB",   1_000_000);
        Account carol = new Account("CAROL", 1_000_000);
        engine.registerAccount(alice);
        engine.registerAccount(bob);
        engine.registerAccount(carol);

        System.out.println("=== Scenario 1: resting limit sells, then a crossing limit buy ===");
        // Book builds asks: 100x60 (Alice), 101x50 (Alice), 102x40 (Bob).
        engine.submit(OrderRequest.limit("ALICE", "AAPL", Side.SELL, 101, 50, TimeInForce.GTC));
        engine.submit(OrderRequest.limit("ALICE", "AAPL", Side.SELL, 100, 60, TimeInForce.GTC));
        engine.submit(OrderRequest.limit("BOB",   "AAPL", Side.SELL, 102, 40, TimeInForce.GTC));
        // Bob BUY 130 @ 101 crosses asks at 100 and 101 only: fills 60@100 + 50@101 = 110,
        // remaining 20 rests as the new best bid @ 101. (102 ask does not cross.)
        Order buy = engine.submit(OrderRequest.limit("BOB", "AAPL", Side.BUY, 101, 130, TimeInForce.GTC));
        System.out.println("  result: " + buy);
        System.out.println("  " + engine.book("AAPL").bboString());

        System.out.println("\n=== Scenario 2: MARKET buy sweeps the book ===");
        engine.submit(OrderRequest.limit("ALICE", "MSFT", Side.SELL, 200, 30, TimeInForce.GTC));
        engine.submit(OrderRequest.limit("CAROL", "MSFT", Side.SELL, 201, 30, TimeInForce.GTC));
        Order mkt = engine.submit(OrderRequest.market("BOB", "MSFT", Side.BUY, 100));
        System.out.println("  result (60 filled across two levels, 40 cancelled; market never rests): " + mkt);
        System.out.println("  " + engine.book("MSFT").bboString());

        System.out.println("\n=== Scenario 3: IOC partial then cancel remainder ===");
        engine.submit(OrderRequest.limit("ALICE", "GOOG", Side.SELL, 50, 10, TimeInForce.GTC));
        Order ioc = engine.submit(OrderRequest.limit("BOB", "GOOG", Side.BUY, 50, 40, TimeInForce.IOC));
        System.out.println("  result (10 filled, 30 cancelled): " + ioc);

        System.out.println("\n=== Scenario 4: FOK that cannot fully fill is rejected ===");
        engine.submit(OrderRequest.limit("ALICE", "TSLA", Side.SELL, 70, 5, TimeInForce.GTC));
        Order fok = engine.submit(OrderRequest.limit("BOB", "TSLA", Side.BUY, 70, 20, TimeInForce.FOK));
        System.out.println("  result (rejected, book untouched): " + fok);
        System.out.println("  " + engine.book("TSLA").bboString());

        System.out.println("\n=== Scenario 5: FOK that CAN fully fill executes ===");
        engine.submit(OrderRequest.limit("ALICE", "TSLA", Side.SELL, 70, 25, TimeInForce.GTC));
        Order fok2 = engine.submit(OrderRequest.limit("BOB", "TSLA", Side.BUY, 70, 20, TimeInForce.FOK));
        System.out.println("  result (fully filled): " + fok2);

        System.out.println("\n=== Scenario 6: cancel a resting order ===");
        Order rest = engine.submit(OrderRequest.limit("CAROL", "NFLX", Side.BUY, 30, 100, TimeInForce.GTC));
        System.out.println("  resting: " + rest);
        boolean ok = engine.cancel("NFLX", rest.id());
        System.out.println("  cancel result=" + ok + "  " + engine.book("NFLX").bboString());

        System.out.println("\n=== Scenario 7: modify (price change loses priority) ===");
        Order m = engine.submit(OrderRequest.limit("CAROL", "AMZN", Side.BUY, 40, 100, TimeInForce.GTC));
        System.out.println("  before: " + m);
        Order m2 = engine.modify("AMZN", m.id(), 41, 80);
        System.out.println("  after modify (new order, fresh seq): " + m2);
        System.out.println("  " + engine.book("AMZN").bboString());

        System.out.println("\n=== Final account states ===");
        System.out.println("  " + alice);
        System.out.println("  " + bob);
        System.out.println("  " + carol);
    }
}

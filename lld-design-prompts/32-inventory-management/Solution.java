/* =====================================================================================
 * Inventory Management System — single-file LLD review / revision artifact.
 *
 * This file is intended to be READ and REVISED before an interview, not compiled/run in
 * production. It is self-contained (pure JDK), complete (no TODOs, no omitted bodies),
 * and demonstrates the chosen design patterns. A main() illustrates the key scenarios.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING (lead with these):
 *   FUNCTIONAL
 *     1. Granularity: track stock per (SKU x warehouse) only, or down to bin/lot/serial/expiry?
 *     2. Reservations: reserve-then-commit flow? Do holds expire (cart abandonment)?
 *     3. Multi-warehouse: one or many? Who routes an order to a warehouse? Transfers needed?
 *     4. Replenishment: automatic POs or just alert a human? How many policies, per-SKU?
 *     5. Suppliers / purchase orders modeled, or external concern we only signal?
 *     6. Alerts: who is notified (email/dashboard/supplier webhook)? Other events?
 *     7. Stock lifecycle states (AVAILABLE/LOW/OUT_OF_STOCK/DISCONTINUED) gating behaviour?
 *     8. Returns/RMA: back to available, to quarantine, or out of scope?
 *   NON-FUNCTIONAL
 *     9. Concurrency / QPS on mutations? Single JVM (locks) or distributed (CAS)?
 *    10. Strong consistency on available (never oversell) vs eventual + compensation?
 *    11. Audit trail required (regulatory/reconciliation)? Append-only?
 *    12. Catalogue scale (thousands vs tens of millions of SKUs)?
 *    13. Latency/throughput targets for reserve and query.
 *   SCOPE
 *    14. Pricing/payment/shipping/order-management out of scope? (assume yes)
 *    15. UI/REST layer required, or domain + service API only?
 *    16. Forecasting/analytics needed, or operational state + audit log only?
 *
 * ASSUMED ANSWERS: SKU x warehouse granularity; reserve-then-commit with optional TTL;
 *   multi-warehouse + transfers; pluggable per-SKU replenishment that signals a supplier;
 *   observer-based alerts; explicit stock states; in-process single JVM, thread-safe,
 *   strong consistency on available; append-only audit; pricing/payment/shipping out of
 *   scope; domain + service API only.
 *
 * PATTERNS: State (StockState), Observer (InventoryEventPublisher/InventoryObserver),
 *   Strategy (ReplenishmentStrategy, FulfilmentRouter), Facade (InventoryService),
 *   Singleton (noted, DI-preferred), lightweight value-object Command (InventoryTransaction).
 *
 * CORRECTNESS CENTER: per-Stock invariant 0 <= reserved <= onHand, available = onHand-reserved,
 *   preserved inside a per-stock ReentrantLock (oversell-safe); transfers lock in id order.
 * ===================================================================================== */

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/* =================================== DOMAIN ENTITIES =================================== */

/** Catalogue item. Owns its SKUs (composition). */
class Product {
    private final String id;
    private final String name;
    private final String category;
    private final List<SKU> skus = new ArrayList<>();

    Product(String name, String category) {
        this.id = "P-" + UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.category = category;
    }
    String getId() { return id; }
    String getName() { return name; }
    String getCategory() { return category; }
    void addSku(SKU sku) { skus.add(sku); }
    List<SKU> getSkus() { return Collections.unmodifiableList(skus); }
}

/** Stock Keeping Unit — the unique sellable variant. Carries reorder policy + supplier. */
class SKU {
    private final String id;
    private final String code;
    private final Product product;
    private final int reorderPoint;
    private final ReplenishmentStrategy replenishmentStrategy;
    private final Supplier supplier;

    SKU(String code, Product product, int reorderPoint,
        ReplenishmentStrategy strategy, Supplier supplier) {
        this.id = "SKU-" + code;
        this.code = code;
        this.product = product;
        this.reorderPoint = reorderPoint;
        this.replenishmentStrategy = strategy;
        this.supplier = supplier;
    }
    String getId() { return id; }
    String getCode() { return code; }
    Product getProduct() { return product; }
    int getReorderPoint() { return reorderPoint; }
    ReplenishmentStrategy getReplenishmentStrategy() { return replenishmentStrategy; }
    Supplier getSupplier() { return supplier; }
}

/** A physical location holding stock. */
class Warehouse {
    private final String id;
    private final String name;
    private final String region;

    Warehouse(String id, String name, String region) {
        this.id = id; this.name = name; this.region = region;
    }
    String getId() { return id; }
    String getName() { return name; }
    String getRegion() { return region; }
}

class Supplier {
    private final String id;
    private final String name;
    private final int leadTimeDays;

    Supplier(String name, int leadTimeDays) {
        this.id = "SUP-" + UUID.randomUUID().toString().substring(0, 6);
        this.name = name;
        this.leadTimeDays = leadTimeDays;
    }
    String getId() { return id; }
    String getName() { return name; }
    int getLeadTimeDays() { return leadTimeDays; }
}

/** A raised replenishment order (modeled lightly). */
class PurchaseOrder {
    private final String id;
    private final SKU sku;
    private final Supplier supplier;
    private final int qty;
    private final Instant raisedAt;

    PurchaseOrder(SKU sku, Supplier supplier, int qty) {
        this.id = "PO-" + UUID.randomUUID().toString().substring(0, 8);
        this.sku = sku; this.supplier = supplier; this.qty = qty;
        this.raisedAt = Instant.now();
    }
    String getId() { return id; }
    SKU getSku() { return sku; }
    Supplier getSupplier() { return supplier; }
    int getQty() { return qty; }
    @Override public String toString() {
        return String.format("PurchaseOrder{%s sku=%s qty=%d supplier=%s}",
                id, sku.getCode(), qty, supplier == null ? "n/a" : supplier.getName());
    }
}

/* =================================== STATE PATTERN =================================== */

/**
 * State pattern: behaviour and transition rules differ by stock status.
 * Beats a scattered enum-switch because adding a state is a new class, not edits everywhere.
 */
interface StockState {
    boolean canReserve(Stock stock);
    /** Returns the state the stock SHOULD be in given its current quantities. */
    StockState recompute(Stock stock);
    String name();
}

class AvailableState implements StockState {
    public boolean canReserve(Stock stock) { return true; }
    public StockState recompute(Stock stock) {
        if (stock.isDiscontinued()) return new DiscontinuedState();
        if (stock.available() <= 0) return new OutOfStockState();
        if (stock.available() <= stock.getSku().getReorderPoint()) return new LowStockState();
        return this;
    }
    public String name() { return "AVAILABLE"; }
}

class LowStockState implements StockState {
    public boolean canReserve(Stock stock) { return stock.available() > 0; }
    public StockState recompute(Stock stock) {
        if (stock.isDiscontinued()) return new DiscontinuedState();
        if (stock.available() <= 0) return new OutOfStockState();
        if (stock.available() > stock.getSku().getReorderPoint()) return new AvailableState();
        return this;
    }
    public String name() { return "LOW"; }
}

class OutOfStockState implements StockState {
    public boolean canReserve(Stock stock) { return false; } // nothing available
    public StockState recompute(Stock stock) {
        if (stock.isDiscontinued()) return new DiscontinuedState();
        if (stock.available() > stock.getSku().getReorderPoint()) return new AvailableState();
        if (stock.available() > 0) return new LowStockState();
        return this;
    }
    public String name() { return "OUT_OF_STOCK"; }
}

class DiscontinuedState implements StockState {
    public boolean canReserve(Stock stock) { return false; } // no new holds on discontinued SKUs
    public StockState recompute(Stock stock) { return this; } // terminal-ish
    public String name() { return "DISCONTINUED"; }
}

/* =================================== STOCK (INVARIANT OWNER) =================================== */

/**
 * The protected aggregate. Invariant: 0 <= reserved <= onHand ; available = onHand - reserved.
 * All mutations are guarded by a per-stock ReentrantLock => oversell-safe, fine-grained.
 */
class Stock {
    private final String id;            // stable id used for global lock ordering
    private final SKU sku;
    private final Warehouse warehouse;
    private int onHand;
    private int reserved;
    private boolean discontinued = false;
    private StockState state;
    final ReentrantLock lock = new ReentrantLock(); // package-private: service orchestrates ordering

    Stock(SKU sku, Warehouse warehouse) {
        this.id = sku.getId() + "@" + warehouse.getId();
        this.sku = sku;
        this.warehouse = warehouse;
        this.onHand = 0;
        this.reserved = 0;
        this.state = new OutOfStockState();
    }

    String getId() { return id; }
    SKU getSku() { return sku; }
    Warehouse getWarehouse() { return warehouse; }
    boolean isDiscontinued() { return discontinued; }
    StockState getState() { return state; }

    /** available = onHand - reserved. Read under lock by callers needing consistency. */
    int available() { return onHand - reserved; }
    int getOnHand() { return onHand; }
    int getReserved() { return reserved; }

    private void recomputeState() { this.state = state.recompute(this); }

    /** Inbound goods. Returns true on success. */
    boolean receive(int qty) {
        validatePositive(qty);
        lock.lock();
        try {
            onHand += qty;
            recomputeState();
            return true;
        } finally { lock.unlock(); }
    }

    /**
     * Atomic check-then-reserve. Returns false if state forbids or available < qty.
     * The compound (check available, then reserved += qty) is atomic under the lock:
     * this is the oversell-prevention mechanism.
     */
    boolean tryReserve(int qty) {
        validatePositive(qty);
        lock.lock();
        try {
            if (!state.canReserve(this) || available() < qty) return false;
            reserved += qty;
            recomputeState();
            return true;
        } finally { lock.unlock(); }
    }

    /** Commit a previously reserved qty (goods ship): onHand and reserved both drop. */
    void commitReserved(int qty) {
        validatePositive(qty);
        lock.lock();
        try {
            if (qty > reserved) throw new IllegalStateException(
                    "commit qty " + qty + " exceeds reserved " + reserved + " on " + id);
            reserved -= qty;
            onHand -= qty;
            recomputeState();
        } finally { lock.unlock(); }
    }

    /** Release a hold (cancel): reserved drops, units return to available. */
    void releaseReserved(int qty) {
        validatePositive(qty);
        lock.lock();
        try {
            if (qty > reserved) throw new IllegalStateException(
                    "release qty " + qty + " exceeds reserved " + reserved + " on " + id);
            reserved -= qty;
            recomputeState();
        } finally { lock.unlock(); }
    }

    /** Direct adjustment (cycle count correction). May be positive or negative. */
    void adjust(int delta) {
        lock.lock();
        try {
            if (onHand + delta < reserved)
                throw new IllegalStateException("adjustment would violate invariant on " + id);
            onHand += delta;
            recomputeState();
        } finally { lock.unlock(); }
    }

    void markDiscontinued() {
        lock.lock();
        try { discontinued = true; recomputeState(); }
        finally { lock.unlock(); }
    }

    private static void validatePositive(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive, got " + qty);
    }
}

/* =================================== RESERVATIONS =================================== */

enum ReservationStatus { ACTIVE, COMMITTED, RELEASED, EXPIRED }

/** A hold across one or more warehouses. allocation maps stockId -> qty for split reservations. */
class Reservation {
    private final String id;
    private final SKU sku;
    private final Map<String, Integer> allocation; // stockId -> reserved qty
    private final int totalQty;
    private final Instant expiresAt; // null => no expiry
    private ReservationStatus status;

    Reservation(SKU sku, Map<String, Integer> allocation, Instant expiresAt) {
        this.id = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        this.sku = sku;
        this.allocation = new LinkedHashMap<>(allocation);
        this.totalQty = allocation.values().stream().mapToInt(Integer::intValue).sum();
        this.expiresAt = expiresAt;
        this.status = ReservationStatus.ACTIVE;
    }
    String getId() { return id; }
    SKU getSku() { return sku; }
    Map<String, Integer> getAllocation() { return Collections.unmodifiableMap(allocation); }
    int getTotalQty() { return totalQty; }
    Instant getExpiresAt() { return expiresAt; }
    ReservationStatus getStatus() { return status; }
    void setStatus(ReservationStatus s) { this.status = s; }
    boolean isExpired(Instant now) { return expiresAt != null && now.isAfter(expiresAt); }
}

/* =================================== AUDIT TRAIL =================================== */

enum TxnType { RECEIVE, RESERVE, COMMIT, RELEASE, ADJUST, TRANSFER_OUT, TRANSFER_IN }

/** Immutable audit record (lightweight Command/value-object). Append-only; never mutated. */
final class InventoryTransaction {
    final String id;
    final TxnType type;
    final String skuId;
    final String warehouseId;
    final int qtyDelta;
    final int onHandAfter;
    final int reservedAfter;
    final Instant at;
    final String actor;

    private InventoryTransaction(TxnType type, String skuId, String warehouseId, int qtyDelta,
                                 int onHandAfter, int reservedAfter, String actor) {
        this.id = "TXN-" + UUID.randomUUID().toString().substring(0, 10);
        this.type = type;
        this.skuId = skuId;
        this.warehouseId = warehouseId;
        this.qtyDelta = qtyDelta;
        this.onHandAfter = onHandAfter;
        this.reservedAfter = reservedAfter;
        this.at = Instant.now();
        this.actor = actor;
    }

    /** Factory: snapshot a stock's post-mutation state into an audit record. */
    static InventoryTransaction of(TxnType type, Stock s, int qtyDelta, String actor) {
        return new InventoryTransaction(type, s.getSku().getId(), s.getWarehouse().getId(),
                qtyDelta, s.getOnHand(), s.getReserved(), actor);
    }

    @Override public String toString() {
        return String.format("%s %-12s sku=%s wh=%s delta=%+d -> onHand=%d reserved=%d by=%s",
                id, type, skuId, warehouseId, qtyDelta, onHandAfter, reservedAfter, actor);
    }
}

/** Append-only audit store. Thread-safe append; reads do not block writers materially. */
class AuditLog {
    private final List<InventoryTransaction> entries = new CopyOnWriteArrayList<>();
    void append(InventoryTransaction txn) { entries.add(txn); }
    List<InventoryTransaction> forSku(String skuId) {
        List<InventoryTransaction> out = new ArrayList<>();
        for (InventoryTransaction t : entries) if (t.skuId.equals(skuId)) out.add(t);
        return out;
    }
    List<InventoryTransaction> all() { return Collections.unmodifiableList(new ArrayList<>(entries)); }
}

/* =================================== OBSERVER PATTERN =================================== */

enum EventType { LOW_STOCK, OUT_OF_STOCK, BACK_IN_STOCK }

final class InventoryEvent {
    final EventType type;
    final Stock stock;
    final int availableSnapshot;
    final Instant at;
    InventoryEvent(EventType type, Stock stock, int availableSnapshot) {
        this.type = type; this.stock = stock;
        this.availableSnapshot = availableSnapshot; this.at = Instant.now();
    }
}

/** Observer: single-method interface (ISP). New reactions => new implementations (OCP). */
interface InventoryObserver {
    void onEvent(InventoryEvent event);
}

/** Subject. Publishes events to observers; isolates failures so one bad observer can't break others. */
class InventoryEventPublisher {
    private final List<InventoryObserver> observers = new CopyOnWriteArrayList<>();
    void subscribe(InventoryObserver o) { observers.add(o); }
    void unsubscribe(InventoryObserver o) { observers.remove(o); }
    void publish(InventoryEvent event) {
        for (InventoryObserver o : observers) {
            try { o.onEvent(event); }
            catch (RuntimeException ex) {
                System.out.println("[publisher] observer failed, continuing: " + ex.getMessage());
            }
        }
    }
}

class EmailAlertObserver implements InventoryObserver {
    public void onEvent(InventoryEvent e) {
        if (e.type == EventType.LOW_STOCK || e.type == EventType.OUT_OF_STOCK) {
            System.out.printf("[email] %s for %s@%s (available=%d)%n",
                    e.type, e.stock.getSku().getCode(), e.stock.getWarehouse().getId(), e.availableSnapshot);
        }
    }
}

class DashboardObserver implements InventoryObserver {
    public void onEvent(InventoryEvent e) {
        System.out.printf("[dashboard] %s : %s@%s now available=%d%n",
                e.type, e.stock.getSku().getCode(), e.stock.getWarehouse().getId(), e.availableSnapshot);
    }
}

/** Observer that ALSO uses Strategy (E3 + E4 compose): on low stock, compute reorder & raise PO. */
class AutoReplenishObserver implements InventoryObserver {
    private final List<PurchaseOrder> raised = new CopyOnWriteArrayList<>();
    public void onEvent(InventoryEvent e) {
        if (e.type != EventType.LOW_STOCK && e.type != EventType.OUT_OF_STOCK) return;
        SKU sku = e.stock.getSku();
        ReplenishmentStrategy strategy = sku.getReplenishmentStrategy();
        if (strategy == null) return;
        int qty = strategy.computeOrderQty(e.stock);
        if (qty <= 0) return;
        PurchaseOrder po = new PurchaseOrder(sku, sku.getSupplier(), qty);
        raised.add(po);
        System.out.printf("[replenish] raised %s%n", po);
    }
    List<PurchaseOrder> getRaisedOrders() { return Collections.unmodifiableList(new ArrayList<>(raised)); }
}

/* =================================== STRATEGY PATTERN =================================== */

/** Replenishment policy. Single-method interface (ISP); swap per SKU at runtime (OCP/DIP). */
interface ReplenishmentStrategy {
    int computeOrderQty(Stock stock);
}

/** Reorder up to a target level. */
class ReorderPointStrategy implements ReplenishmentStrategy {
    private final int targetLevel;
    ReorderPointStrategy(int targetLevel) { this.targetLevel = targetLevel; }
    public int computeOrderQty(Stock stock) {
        return Math.max(0, targetLevel - stock.available());
    }
}

/** Economic Order Quantity (simplified): fixed economic lot size. */
class EconomicOrderQtyStrategy implements ReplenishmentStrategy {
    private final int eoq;
    EconomicOrderQtyStrategy(int eoq) { this.eoq = eoq; }
    public int computeOrderQty(Stock stock) { return eoq; }
}

/** Just-in-time: order exactly the shortfall below the reorder point. */
class JustInTimeStrategy implements ReplenishmentStrategy {
    public int computeOrderQty(Stock stock) {
        int point = stock.getSku().getReorderPoint();
        return Math.max(0, point - stock.available());
    }
}

/** Fulfilment routing across warehouses (Strategy). Picks which stock cells to draw from. */
interface FulfilmentRouter {
    /** Returns ordered map stockId -> allocated qty summing to requested qty, or empty if impossible. */
    Map<String, Integer> selectWarehouses(SKU sku, int qty, List<Stock> candidates);
}

/** Default router: draw from the warehouses with most available first, splitting if needed. */
class MostStockFirstRouter implements FulfilmentRouter {
    public Map<String, Integer> selectWarehouses(SKU sku, int qty, List<Stock> candidates) {
        List<Stock> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(Stock::available).reversed());
        Map<String, Integer> plan = new LinkedHashMap<>();
        int remaining = qty;
        for (Stock s : sorted) {
            if (remaining <= 0) break;
            int take = Math.min(remaining, Math.max(0, s.available()));
            if (take > 0) { plan.put(s.getId(), take); remaining -= take; }
        }
        if (remaining > 0) return Collections.emptyMap(); // cannot fully satisfy
        return plan;
    }
}

/* =================================== EXCEPTIONS =================================== */

class InsufficientStockException extends RuntimeException {
    InsufficientStockException(String msg) { super(msg); }
}

/* =================================== FACADE: INVENTORY SERVICE =================================== */

/**
 * Facade / orchestrator. The single public API. Routes every mutation through the audit log
 * and the event publisher (the choke-point), and owns concurrency orchestration including
 * deadlock-safe multi-stock locking by ascending stock id.
 *
 * Singleton note: there is conceptually one of these per process, but we inject a single
 * instance (DI) rather than exposing a global getInstance(), for testability.
 */
class InventoryService {
    private final Map<String, Stock> stocks = new ConcurrentHashMap<>();      // stockId -> Stock
    private final Map<String, SKU> skus = new ConcurrentHashMap<>();          // skuId -> SKU
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final AuditLog auditLog = new AuditLog();
    private final InventoryEventPublisher publisher;
    private final FulfilmentRouter router;

    InventoryService(InventoryEventPublisher publisher, FulfilmentRouter router) {
        this.publisher = publisher;
        this.router = router;
    }

    AuditLog getAuditLog() { return auditLog; }
    InventoryEventPublisher getPublisher() { return publisher; }

    /* ---- registration ---- */

    Product registerProduct(String name, String category) { return new Product(name, category); }

    SKU registerSku(Product product, String code, int reorderPoint,
                    ReplenishmentStrategy strategy, Supplier supplier) {
        SKU sku = new SKU(code, product, reorderPoint, strategy, supplier);
        product.addSku(sku);
        skus.put(sku.getId(), sku);
        return sku;
    }

    /** Open a (sku, warehouse) stock cell. */
    Stock openStock(SKU sku, Warehouse wh) {
        Stock stock = new Stock(sku, wh);
        stocks.put(stock.getId(), stock);
        return stock;
    }

    /* ---- inbound ---- */

    void receiveStock(SKU sku, Warehouse wh, int qty, String actor) {
        Stock stock = requireStock(sku, wh);
        int beforeAvail = lockedAvailable(stock);
        stock.receive(qty);
        auditLog.append(InventoryTransaction.of(TxnType.RECEIVE, stock, +qty, actor));
        maybePublishOnIncrease(stock, beforeAvail);
    }

    /* ---- reserve / commit / release (oversell-safe) ---- */

    /**
     * Reserve qty for a SKU across warehouses without overselling. Splits via the router,
     * locks chosen stocks in ascending id order, and rolls back fully if any cell fails.
     */
    Reservation reserve(SKU sku, int qty, Instant expiresAt, String actor) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        List<Stock> candidates = stocksFor(sku);
        Map<String, Integer> plan = router.selectWarehouses(sku, qty, candidates);
        if (plan.isEmpty())
            throw new InsufficientStockException("cannot satisfy " + qty + " of " + sku.getCode());

        // Deadlock-safe: acquire locks in ascending stock id order.
        List<String> orderedIds = new ArrayList<>(plan.keySet());
        Collections.sort(orderedIds);
        List<Stock> locked = new ArrayList<>();
        List<String> reservedSoFar = new ArrayList<>();
        try {
            for (String sid : orderedIds) { stocks.get(sid).lock.lock(); locked.add(stocks.get(sid)); }
            // First verify all can satisfy, then apply (all-or-nothing).
            for (String sid : orderedIds) {
                Stock s = stocks.get(sid);
                int need = plan.get(sid);
                if (!s.getState().canReserve(s) || s.available() < need)
                    throw new InsufficientStockException(
                            "stock changed; cannot reserve " + need + " on " + sid);
            }
            for (String sid : orderedIds) {
                Stock s = stocks.get(sid);
                int need = plan.get(sid);
                int beforeAvail = s.available();
                boolean ok = s.tryReserve(need); // atomic under its own lock (we already hold it)
                if (!ok) throw new InsufficientStockException("reserve failed on " + sid);
                reservedSoFar.add(sid);
                auditLog.append(InventoryTransaction.of(TxnType.RESERVE, s, -need, actor));
                snapshotAndQueueLowStock(s, beforeAvail);
            }
            Reservation res = new Reservation(sku, plan, expiresAt);
            reservations.put(res.getId(), res);
            // Publish AFTER releasing locks (done in finally), using snapshots captured above.
            flushQueuedEvents();
            return res;
        } catch (RuntimeException ex) {
            // rollback any partial reservations while still holding the locks
            for (String sid : reservedSoFar) {
                Stock s = stocks.get(sid);
                s.releaseReserved(plan.get(sid));
            }
            queuedEvents.get().clear();
            throw ex;
        } finally {
            for (Stock s : locked) s.lock.unlock();
        }
    }

    void commit(String reservationId, String actor) {
        Reservation res = requireReservation(reservationId);
        synchronized (res) {
            if (res.getStatus() != ReservationStatus.ACTIVE)
                throw new IllegalStateException("reservation not active: " + reservationId);
            for (Map.Entry<String, Integer> e : res.getAllocation().entrySet()) {
                Stock s = stocks.get(e.getKey());
                s.commitReserved(e.getValue());
                auditLog.append(InventoryTransaction.of(TxnType.COMMIT, s, -e.getValue(), actor));
            }
            res.setStatus(ReservationStatus.COMMITTED);
        }
    }

    void release(String reservationId, String actor) {
        Reservation res = requireReservation(reservationId);
        synchronized (res) {
            if (res.getStatus() != ReservationStatus.ACTIVE) return; // idempotent
            for (Map.Entry<String, Integer> e : res.getAllocation().entrySet()) {
                Stock s = stocks.get(e.getKey());
                int beforeAvail = lockedAvailable(s);
                s.releaseReserved(e.getValue());
                auditLog.append(InventoryTransaction.of(TxnType.RELEASE, s, +e.getValue(), actor));
                maybePublishOnIncrease(s, beforeAvail);
            }
            res.setStatus(ReservationStatus.RELEASED);
        }
    }

    /** Reaper hook: release a reservation that has expired. */
    void expireIfDue(String reservationId, Instant now, String actor) {
        Reservation res = reservations.get(reservationId);
        if (res == null) return;
        synchronized (res) {
            if (res.getStatus() == ReservationStatus.ACTIVE && res.isExpired(now)) {
                for (Map.Entry<String, Integer> e : res.getAllocation().entrySet()) {
                    Stock s = stocks.get(e.getKey());
                    int beforeAvail = lockedAvailable(s);
                    s.releaseReserved(e.getValue());
                    auditLog.append(InventoryTransaction.of(TxnType.RELEASE, s, +e.getValue(), actor));
                    maybePublishOnIncrease(s, beforeAvail);
                }
                res.setStatus(ReservationStatus.EXPIRED);
            }
        }
    }

    /* ---- transfer (deadlock-safe, atomic) ---- */

    void transfer(SKU sku, Warehouse src, Warehouse dst, int qty, String actor) {
        if (src.getId().equals(dst.getId()))
            throw new IllegalArgumentException("source and destination warehouse are the same");
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        Stock from = requireStock(sku, src);
        Stock to = requireStock(sku, dst);

        // lock both in ascending id order to avoid deadlock with concurrent reverse transfers
        Stock first = from.getId().compareTo(to.getId()) <= 0 ? from : to;
        Stock second = first == from ? to : from;
        first.lock.lock();
        second.lock.lock();
        try {
            if (from.available() < qty)
                throw new InsufficientStockException("transfer: source available "
                        + from.available() + " < " + qty + " for " + sku.getCode());
            from.adjust(-qty);
            to.adjust(+qty);
            auditLog.append(InventoryTransaction.of(TxnType.TRANSFER_OUT, from, -qty, actor));
            auditLog.append(InventoryTransaction.of(TxnType.TRANSFER_IN, to, +qty, actor));
        } finally {
            second.lock.unlock();
            first.lock.unlock();
        }
        // events outside the locks
        maybePublishCurrent(from);
        maybePublishCurrent(to);
    }

    /* ---- discontinue ---- */

    void discontinue(SKU sku, Warehouse wh) {
        Stock s = requireStock(sku, wh);
        s.markDiscontinued();
    }

    /* ---- queries ---- */

    int availableAcross(SKU sku) {
        int total = 0;
        for (Stock s : stocksFor(sku)) total += lockedAvailable(s);
        return total;
    }

    List<InventoryTransaction> audit(SKU sku) { return auditLog.forSku(sku.getId()); }

    /* ---- internals: event publishing helpers ---- */

    // Per-thread queue so reserve can capture events under lock and publish after unlocking.
    private final ThreadLocal<List<InventoryEvent>> queuedEvents =
            ThreadLocal.withInitial(ArrayList::new);

    private void snapshotAndQueueLowStock(Stock s, int beforeAvail) {
        int now = s.available();
        EventType type = eventForDrop(s, beforeAvail, now);
        if (type != null) queuedEvents.get().add(new InventoryEvent(type, s, now));
    }

    private void flushQueuedEvents() {
        List<InventoryEvent> q = queuedEvents.get();
        for (InventoryEvent e : q) publisher.publish(e);
        q.clear();
    }

    private EventType eventForDrop(Stock s, int beforeAvail, int afterAvail) {
        if (afterAvail >= beforeAvail) return null;
        if (afterAvail <= 0) return EventType.OUT_OF_STOCK;
        if (afterAvail <= s.getSku().getReorderPoint()
                && beforeAvail > s.getSku().getReorderPoint()) return EventType.LOW_STOCK;
        if (afterAvail <= s.getSku().getReorderPoint()) return EventType.LOW_STOCK;
        return null;
    }

    private void maybePublishOnIncrease(Stock s, int beforeAvail) {
        int now = lockedAvailable(s);
        if (beforeAvail <= 0 && now > 0)
            publisher.publish(new InventoryEvent(EventType.BACK_IN_STOCK, s, now));
    }

    private void maybePublishCurrent(Stock s) {
        int now = lockedAvailable(s);
        if (now <= 0) publisher.publish(new InventoryEvent(EventType.OUT_OF_STOCK, s, now));
        else if (now <= s.getSku().getReorderPoint())
            publisher.publish(new InventoryEvent(EventType.LOW_STOCK, s, now));
    }

    private int lockedAvailable(Stock s) {
        s.lock.lock();
        try { return s.available(); } finally { s.lock.unlock(); }
    }

    /* ---- internals: lookups ---- */

    private Stock requireStock(SKU sku, Warehouse wh) {
        Stock s = stocks.get(sku.getId() + "@" + wh.getId());
        if (s == null) throw new IllegalArgumentException(
                "no stock cell for " + sku.getCode() + " @ " + wh.getId() + " (open it first)");
        return s;
    }
    private Reservation requireReservation(String id) {
        Reservation r = reservations.get(id);
        if (r == null) throw new IllegalArgumentException("unknown reservation " + id);
        return r;
    }
    private List<Stock> stocksFor(SKU sku) {
        List<Stock> out = new ArrayList<>();
        for (Stock s : stocks.values()) if (s.getSku().getId().equals(sku.getId())) out.add(s);
        return out;
    }
}

/* =================================== DEMO =================================== */

public class Solution {
    public static void main(String[] args) throws InterruptedException {
        // Wire dependencies (single injected service instance — DI over global Singleton).
        InventoryEventPublisher publisher = new InventoryEventPublisher();
        AutoReplenishObserver replenisher = new AutoReplenishObserver();
        publisher.subscribe(new EmailAlertObserver());
        publisher.subscribe(new DashboardObserver());
        publisher.subscribe(replenisher);

        InventoryService service = new InventoryService(publisher, new MostStockFirstRouter());

        // Catalogue + warehouses + supplier
        Warehouse blr = new Warehouse("WH-BLR", "Bangalore DC", "South");
        Warehouse del = new Warehouse("WH-DEL", "Delhi DC", "North");
        Supplier acme = new Supplier("Acme Apparel", 5);

        Product tshirt = service.registerProduct("Cotton T-Shirt", "Apparel");
        SKU red_m = service.registerSku(tshirt, "TSHIRT-RED-M", /*reorderPoint*/ 5,
                new ReorderPointStrategy(/*targetLevel*/ 30), acme);

        service.openStock(red_m, blr);
        service.openStock(red_m, del);

        System.out.println("=== 1. Receive inbound stock ===");
        service.receiveStock(red_m, blr, 10, "receiver-1");
        service.receiveStock(red_m, del, 4, "receiver-1");
        System.out.println("available across warehouses = " + service.availableAcross(red_m)); // 14

        System.out.println("\n=== 2. Reserve (split across warehouses, oversell-safe) ===");
        // Request 12: router draws 10 from BLR (most stock) + 2 from DEL. DEL then hits LOW (<=5).
        Reservation r1 = service.reserve(red_m, 12, null, "order-1001");
        System.out.println("reservation " + r1.getId() + " alloc=" + r1.getAllocation());
        System.out.println("available across = " + service.availableAcross(red_m)); // 2

        System.out.println("\n=== 3. Commit part / release part ===");
        service.commit(r1.getId(), "shipper-1"); // ships everything reserved in r1
        System.out.println("available across after commit = " + service.availableAcross(red_m)); // 2

        System.out.println("\n=== 4. Oversell attempt is rejected ===");
        try {
            service.reserve(red_m, 100, null, "order-1002");
        } catch (InsufficientStockException ex) {
            System.out.println("rejected as expected: " + ex.getMessage());
        }

        System.out.println("\n=== 5. Transfer between warehouses (deadlock-safe) ===");
        service.receiveStock(red_m, blr, 20, "receiver-2"); // top up BLR
        service.transfer(red_m, blr, del, 8, "ops-1");
        System.out.println("available across after transfer = " + service.availableAcross(red_m));

        System.out.println("\n=== 6. Reservation with TTL + expiry release ===");
        Reservation r2 = service.reserve(red_m, 3, Instant.now().minusSeconds(1), "order-1003");
        System.out.println("before expiry available = " + service.availableAcross(red_m));
        service.expireIfDue(r2.getId(), Instant.now(), "reaper");
        System.out.println("after expiry available = " + service.availableAcross(red_m)
                + " status=" + r2.getStatus());

        System.out.println("\n=== 7. Concurrency: many threads race for limited stock (no oversell) ===");
        demoConcurrency();

        System.out.println("\n=== 8. Replenishment POs auto-raised on low stock ===");
        System.out.println("raised purchase orders: " + replenisher.getRaisedOrders());

        System.out.println("\n=== 9. Audit trail (append-only) ===");
        for (InventoryTransaction t : service.audit(red_m)) System.out.println("  " + t);
    }

    /** Spins up N threads each trying to reserve 1 unit when only K are available. */
    private static void demoConcurrency() throws InterruptedException {
        InventoryEventPublisher pub = new InventoryEventPublisher();
        InventoryService svc = new InventoryService(pub, new MostStockFirstRouter());

        Product p = svc.registerProduct("Limited Edition Mug", "Home");
        SKU sku = svc.registerSku(p, "MUG-LTD", 2, new EconomicOrderQtyStrategy(50), null);
        Warehouse wh = new Warehouse("WH-SINGLE", "Single DC", "Central");
        svc.openStock(sku, wh);

        final int CAPACITY = 5;
        final int THREADS = 50;
        svc.receiveStock(sku, wh, CAPACITY, "seed");

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Thread t = new Thread(() -> {
                try { svc.reserve(sku, 1, null, "racer"); success.incrementAndGet(); }
                catch (InsufficientStockException ex) { failure.incrementAndGet(); }
            });
            threads.add(t);
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.printf("capacity=%d threads=%d => successes=%d failures=%d (no oversell: successes==capacity? %b)%n",
                CAPACITY, THREADS, success.get(), failure.get(), success.get() == CAPACITY);
        System.out.println("final available = " + svc.availableAcross(sku) + " (expected 0)");
    }
}

/* =====================================================================================
 * Restaurant Management System — Single-file LLD review/revision artifact.
 *
 * This file is for READING and REVISION, not for production. It is complete and
 * correct-by-inspection (no TODOs, no omitted bodies). A main() at the bottom
 * illustrates the key scenarios for the reader.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional scope:
 *   1. Single restaurant or a chain (multi-location)?
 *   2. Reservations, walk-ins, or both? Do reservations have a time slot + duration?
 *   3. Waitlist when full (with quoted wait), or reject?
 *   4. Who edits the menu? Need item availability toggling ("86") / time-of-day menus?
 *   5. Exact order lifecycle? Is "serve then add more" (re-fire to kitchen) allowed?
 *   6. Billing: split bills (equal / by item / by guest)? Taxes flat or per-category?
 *      Discounts coupon / percentage / loyalty? Stackable?
 *   7. Payments: record only, or gateway integration (usually out of scope)?
 *   8. Kitchen: single queue or multiple stations (grill/fry/bar) with routing/priority?
 * Non-functional:
 *   9. Concurrency: many waiters/terminals in one process (need thread-safety) or sharded?
 *  10. Consistency: must table seating be strongly consistent (never double-seat)? (yes)
 *  11. Persistence: in-memory for the exercise, but repository abstraction to swap later?
 *  12. Scale: #tables, menu size, orders/day.
 * Scope-narrowing / out of scope:
 *  13. Inventory/stock, staff scheduling/payroll, supplier ordering, analytics — out?
 *  14. Auth/roles modeled, or trusted caller?
 *  15. Guest notifications (SMS "table ready") — model the Observer hook or skip?
 *
 * ASSUMED ANSWERS (so the design is concrete):
 *   single restaurant; reservations + walk-ins + waitlist; manager-editable menu w/ 86;
 *   full lifecycle with re-fire; split bills in all 3 modes; pluggable tax + discount;
 *   payment recorded only; single kitchen queue with Observer-driven KDS;
 *   multi-threaded single process (seating + order mutation thread-safe);
 *   in-memory with Repository interfaces; roles modeled lightly.
 *
 * PATTERNS: State (order lifecycle), Strategy (discount/tax/split/table-assignment),
 *           Observer (kitchen display + notifications), Factory (kitchen tickets),
 *           Repository (storage seam), Composition root (Restaurant).
 * ===================================================================================== */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

public class Solution {

    /* ==========================================================================
     * VALUE OBJECTS & ENUMS
     * ========================================================================== */

    /** Immutable money on BigDecimal to avoid double-rounding bugs. Scale 2, HALF_UP. */
    static final class Money {
        static final Money ZERO = new Money(BigDecimal.ZERO);
        private final BigDecimal amount;

        Money(BigDecimal amount) {
            this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        }
        static Money of(double v) { return new Money(BigDecimal.valueOf(v)); }

        Money add(Money o)        { return new Money(this.amount.add(o.amount)); }
        Money subtract(Money o)   { return new Money(this.amount.subtract(o.amount)); }
        Money multiply(int qty)   { return new Money(this.amount.multiply(BigDecimal.valueOf(qty))); }
        /** percentage of this amount, e.g. percent(20) -> 20% of value. */
        Money percent(double pct) { return new Money(this.amount.multiply(BigDecimal.valueOf(pct / 100.0))); }
        BigDecimal raw()          { return amount; }
        boolean isZeroOrLess()    { return amount.compareTo(BigDecimal.ZERO) <= 0; }

        @Override public String toString() { return "$" + amount.toPlainString(); }
        @Override public boolean equals(Object o) {
            return (o instanceof Money) && ((Money) o).amount.compareTo(amount) == 0;
        }
        @Override public int hashCode() { return amount.stripTrailingZeros().hashCode(); }
    }

    enum TableStatus { FREE, RESERVED, OCCUPIED, DIRTY }
    enum OrderStatus { CREATED, PLACED, PREPARING, READY, SERVED, CLOSED, CANCELLED }
    enum LineStatus { NEW, FIRED, READY, SERVED, CANCELLED }
    enum KitchenTicketStatus { QUEUED, PREPARING, READY, PICKED_UP, CANCELLED }
    enum OrderType { DINE_IN, TAKEAWAY, DELIVERY }
    enum SplitType { EQUAL, BY_ITEM, BY_GUEST }
    enum ReservationStatus { BOOKED, SEATED, CANCELLED, NO_SHOW }
    enum PaymentMethod { CASH, CARD, UPI }

    static class IllegalStateTransitionException extends RuntimeException {
        IllegalStateTransitionException(String m) { super(m); }
    }
    static class DomainException extends RuntimeException {
        DomainException(String m) { super(m); }
    }

    /* ==========================================================================
     * ACTORS
     * ========================================================================== */

    static final class Customer {
        final String id; final String name; final String phone;
        Customer(String name, String phone) { this.id = UUID.randomUUID().toString(); this.name = name; this.phone = phone; }
        @Override public String toString() { return name; }
    }

    static final class Waiter {
        final String id; final String name;
        Waiter(String name) { this.id = UUID.randomUUID().toString(); this.name = name; }
        @Override public String toString() { return name; }
    }

    /* ==========================================================================
     * MENU
     * ========================================================================== */

    static final class MenuItem {
        final String id; final String name; final String category; final Money price;
        private volatile boolean available = true;
        MenuItem(String name, String category, Money price) {
            this.id = UUID.randomUUID().toString(); this.name = name; this.category = category; this.price = price;
        }
        boolean isAvailable() { return available; }
        void setAvailable(boolean v) { this.available = v; }
        @Override public String toString() { return name + " (" + price + ")"; }
    }

    /** Manager-editable menu. 86 = mark unavailable. */
    static final class Menu {
        private final Map<String, MenuItem> items = new ConcurrentHashMap<>();
        void add(MenuItem item) { items.put(item.id, item); }
        void remove(String id) { items.remove(id); }
        void setAvailable(String id, boolean v) {
            MenuItem i = items.get(id);
            if (i == null) throw new DomainException("No such menu item: " + id);
            i.setAvailable(v);
        }
        Optional<MenuItem> get(String id) { return Optional.ofNullable(items.get(id)); }
        List<MenuItem> availableItems() {
            List<MenuItem> out = new ArrayList<>();
            for (MenuItem i : items.values()) if (i.isAvailable()) out.add(i);
            return out;
        }
    }

    /* ==========================================================================
     * TABLE — thread-safe state transitions (CAS via synchronized) to avoid
     * double-seating under concurrency.
     * ========================================================================== */

    static final class Table {
        final String id; final int capacity;
        private TableStatus status = TableStatus.FREE;
        private Order currentOrder;

        Table(String id, int capacity) { this.id = id; this.capacity = capacity; }

        synchronized TableStatus status() { return status; }
        synchronized Order currentOrder() { return currentOrder; }

        /** Atomic FREE/RESERVED -> OCCUPIED. Returns false if the CAS lost the race. */
        synchronized boolean occupy() {
            if (status == TableStatus.FREE || status == TableStatus.RESERVED) {
                status = TableStatus.OCCUPIED; return true;
            }
            return false;
        }
        /** Atomic FREE -> RESERVED. */
        synchronized boolean reserve() {
            if (status == TableStatus.FREE) { status = TableStatus.RESERVED; return true; }
            return false;
        }
        synchronized void attachOrder(Order o) { this.currentOrder = o; }
        synchronized void markDirty() { this.status = TableStatus.DIRTY; this.currentOrder = null; }
        synchronized void free() { this.status = TableStatus.FREE; this.currentOrder = null; }

        @Override public String toString() { return "Table#" + id + "(cap=" + capacity + "," + status + ")"; }
    }

    /* ==========================================================================
     * RESERVATION & WAITLIST
     * ========================================================================== */

    static final class Reservation {
        final String id; final Customer customer; final int partySize; final Instant time;
        Table table; ReservationStatus status = ReservationStatus.BOOKED;
        Reservation(Customer c, int partySize, Instant time) {
            this.id = UUID.randomUUID().toString(); this.customer = c; this.partySize = partySize; this.time = time;
        }
    }

    static final class WaitlistEntry {
        final Customer customer; final int partySize; final Instant since;
        WaitlistEntry(Customer c, int partySize) { this.customer = c; this.partySize = partySize; this.since = Instant.now(); }
    }

    /* ==========================================================================
     * ORDER + STATE PATTERN
     * ========================================================================== */

    static final class OrderLineItem {
        final MenuItem item; final int qty; final String guestTag; // guestTag used for BY_GUEST split
        LineStatus status = LineStatus.NEW;
        OrderLineItem(MenuItem item, int qty, String guestTag) {
            this.item = item; this.qty = qty; this.guestTag = guestTag;
        }
        Money lineTotal() { return item.price.multiply(qty); }
        @Override public String toString() { return qty + "x " + item.name + (guestTag != null ? " [" + guestTag + "]" : ""); }
    }

    /** State pattern: each state encapsulates which transitions are legal. */
    interface OrderState {
        default void place(Order o)      { illegal("place"); }
        default void prepare(Order o)    { illegal("prepare"); }
        default void markReady(Order o)  { illegal("markReady"); }
        default void serve(Order o)      { illegal("serve"); }
        default void close(Order o)      { illegal("close"); }
        default void cancel(Order o)     { illegal("cancel"); }
        OrderStatus status();
        default void illegal(String op) {
            throw new IllegalStateTransitionException("Cannot '" + op + "' in state " + status());
        }
    }

    static final class CreatedState implements OrderState {
        public OrderStatus status() { return OrderStatus.CREATED; }
        public void place(Order o)  { o.setState(new PlacedState()); }
        public void cancel(Order o) { o.setState(new CancelledState()); }
    }
    static final class PlacedState implements OrderState {
        public OrderStatus status()  { return OrderStatus.PLACED; }
        public void prepare(Order o) { o.setState(new PreparingState()); }
        public void cancel(Order o)  { o.setState(new CancelledState()); }
    }
    static final class PreparingState implements OrderState {
        public OrderStatus status()    { return OrderStatus.PREPARING; }
        public void markReady(Order o) { o.setState(new ReadyState()); }
        public void cancel(Order o)    { o.setState(new CancelledState()); }
    }
    static final class ReadyState implements OrderState {
        public OrderStatus status() { return OrderStatus.READY; }
        public void serve(Order o)  { o.setState(new ServedState()); }
    }
    static final class ServedState implements OrderState {
        public OrderStatus status() { return OrderStatus.SERVED; }
        public void close(Order o)  { o.setState(new ClosedState()); }
    }
    static final class ClosedState implements OrderState {
        public OrderStatus status() { return OrderStatus.CLOSED; }
    }
    static final class CancelledState implements OrderState {
        public OrderStatus status() { return OrderStatus.CANCELLED; }
    }

    /** Order: composes line items, delegates lifecycle to its OrderState. Thread-safe. */
    static final class Order {
        final String id; final Table table; final OrderType type;
        private final List<OrderLineItem> items = new ArrayList<>();
        private OrderState state = new CreatedState();

        Order(Table table, OrderType type) {
            this.id = UUID.randomUUID().toString(); this.table = table; this.type = type;
        }

        synchronized void setState(OrderState s) { this.state = s; }
        synchronized OrderStatus status() { return state.status(); }
        synchronized List<OrderLineItem> items() { return new ArrayList<>(items); }

        /** Add a line. Allowed pre-serve (and re-fire after serve) but not on closed/cancelled. */
        synchronized void addItem(OrderLineItem li) {
            OrderStatus s = state.status();
            if (s == OrderStatus.CLOSED || s == OrderStatus.CANCELLED)
                throw new IllegalStateTransitionException("Cannot add items to a " + s + " order");
            if (!li.item.isAvailable())
                throw new DomainException("Item 86'd / unavailable: " + li.item.name);
            items.add(li);
        }

        synchronized void place()     { state.place(this); markLines(LineStatus.FIRED); }
        synchronized void prepare()   { state.prepare(this); }
        synchronized void markReady() { state.markReady(this); markLines(LineStatus.READY); }
        synchronized void serve()     { state.serve(this); markLines(LineStatus.SERVED); }
        synchronized void close()     { state.close(this); }
        synchronized void cancel()    { state.cancel(this); markLines(LineStatus.CANCELLED); }

        private void markLines(LineStatus ls) { for (OrderLineItem li : items) if (li.status != LineStatus.CANCELLED) li.status = ls; }

        synchronized Money subtotal() {
            Money sum = Money.ZERO;
            for (OrderLineItem li : items) if (li.status != LineStatus.CANCELLED) sum = sum.add(li.lineTotal());
            return sum;
        }
        @Override public String toString() { return "Order#" + id.substring(0, 8) + "(" + status() + "," + items.size() + " lines)"; }
    }

    /* ==========================================================================
     * KITCHEN — Observer subject + Factory for tickets
     * ========================================================================== */

    static final class KitchenTicket {
        final String id; final String orderId; final List<OrderLineItem> lines;
        private volatile KitchenTicketStatus status = KitchenTicketStatus.QUEUED;
        KitchenTicket(String orderId, List<OrderLineItem> lines) {
            this.id = UUID.randomUUID().toString(); this.orderId = orderId; this.lines = lines;
        }
        KitchenTicketStatus status() { return status; }
        void setStatus(KitchenTicketStatus s) { this.status = s; }
        @Override public String toString() { return "Ticket#" + id.substring(0, 8) + "(" + status + "," + lines + ")"; }
    }

    /** Factory: centralizes how a ticket is built from an order (future: split by station). */
    static final class KitchenTicketFactory {
        static KitchenTicket from(Order order) {
            List<OrderLineItem> fired = new ArrayList<>();
            for (OrderLineItem li : order.items()) if (li.status == LineStatus.FIRED) fired.add(li);
            if (fired.isEmpty()) throw new DomainException("No fired lines to send to kitchen");
            return new KitchenTicket(order.id, fired);
        }
    }

    /** Observer interface for the KDS / notifiers. */
    interface KitchenObserver { void onTicketEvent(KitchenTicket ticket); }

    /** Concrete observer: the kitchen display screen. */
    static final class KitchenDisplay implements KitchenObserver {
        final String name;
        KitchenDisplay(String name) { this.name = name; }
        public void onTicketEvent(KitchenTicket t) {
            System.out.println("  [KDS:" + name + "] " + t.status() + " -> " + t);
        }
    }

    /** Subject: holds the queue and fans out events to observers (notify outside the lock). */
    static final class Kitchen {
        private final ConcurrentLinkedDeque<KitchenTicket> queue = new ConcurrentLinkedDeque<>();
        private final Map<String, KitchenTicket> byId = new ConcurrentHashMap<>();
        private final List<KitchenObserver> observers = new CopyOnWriteArrayList<>();

        void register(KitchenObserver o) { observers.add(o); }

        void submit(KitchenTicket ticket) {
            queue.addLast(ticket);
            byId.put(ticket.id, ticket);
            notifyObservers(ticket);
        }
        /** Advance a ticket's status and notify. */
        void advance(String ticketId, KitchenTicketStatus newStatus) {
            KitchenTicket t = byId.get(ticketId);
            if (t == null) throw new DomainException("No such ticket: " + ticketId);
            t.setStatus(newStatus);
            if (newStatus == KitchenTicketStatus.PICKED_UP || newStatus == KitchenTicketStatus.CANCELLED) queue.remove(t);
            notifyObservers(t);
        }
        Optional<KitchenTicket> peekNext() { return Optional.ofNullable(queue.peekFirst()); }

        private void notifyObservers(KitchenTicket t) {
            // Snapshot via CopyOnWriteArrayList iteration; in prod hand off to an executor.
            for (KitchenObserver o : observers) o.onTicketEvent(t);
        }
    }

    /* ==========================================================================
     * BILLING — Strategy (discount, tax, split)
     * ========================================================================== */

    interface DiscountStrategy { Money apply(Money subtotal); }
    interface TaxStrategy { Money tax(Money taxable); }

    static final class NoDiscount implements DiscountStrategy {
        public Money apply(Money subtotal) { return Money.ZERO; }
    }
    static final class PercentageDiscount implements DiscountStrategy {
        private final double pct;
        PercentageDiscount(double pct) { this.pct = pct; }
        public Money apply(Money subtotal) { return subtotal.percent(pct); }
    }
    /** Composite: stack discounts (e.g., happy-hour + loyalty). */
    static final class CompositeDiscount implements DiscountStrategy {
        private final List<DiscountStrategy> parts;
        CompositeDiscount(List<DiscountStrategy> parts) { this.parts = parts; }
        public Money apply(Money subtotal) {
            Money total = Money.ZERO;
            for (DiscountStrategy d : parts) total = total.add(d.apply(subtotal));
            return total;
        }
    }
    /** Flat-rate tax (e.g., 5% GST) plus a service charge. */
    static final class FlatTaxStrategy implements TaxStrategy {
        private final double taxPct; private final double serviceChargePct;
        FlatTaxStrategy(double taxPct, double serviceChargePct) { this.taxPct = taxPct; this.serviceChargePct = serviceChargePct; }
        public Money tax(Money taxable) { return taxable.percent(taxPct).add(taxable.percent(serviceChargePct)); }
    }

    static final class Bill {
        final String orderId; final Money subtotal; final Money discount; final Money tax; final Money total;
        final List<OrderLineItem> lines;
        Bill(String orderId, Money subtotal, Money discount, Money tax, Money total, List<OrderLineItem> lines) {
            this.orderId = orderId; this.subtotal = subtotal; this.discount = discount;
            this.tax = tax; this.total = total; this.lines = lines;
        }
        @Override public String toString() {
            return "Bill[subtotal=" + subtotal + ", discount=" + discount + ", tax=" + tax + ", TOTAL=" + total + "]";
        }
    }

    static final class SubBill {
        final String label; final Money amount;
        SubBill(String label, Money amount) { this.label = label; this.amount = amount; }
        @Override public String toString() { return label + " => " + amount; }
    }

    interface SplitStrategy { List<SubBill> split(Bill bill, int guests); }

    static final class EqualSplit implements SplitStrategy {
        public List<SubBill> split(Bill bill, int guests) {
            if (guests <= 0) guests = 1;
            // Divide total into N parts; assign rounding remainder to the last part.
            BigDecimal per = bill.total.raw().divide(BigDecimal.valueOf(guests), 2, RoundingMode.DOWN);
            List<SubBill> out = new ArrayList<>();
            Money running = Money.ZERO;
            for (int i = 0; i < guests; i++) {
                Money share;
                if (i == guests - 1) share = bill.total.subtract(running); // remainder to last
                else { share = new Money(per); running = running.add(share); }
                out.add(new SubBill("Guest " + (i + 1), share));
            }
            return out;
        }
    }
    /** BY_ITEM/BY_GUEST: group lines by guestTag, allocate discount+tax proportionally. */
    static final class ProportionalSplit implements SplitStrategy {
        public List<SubBill> split(Bill bill, int guestsIgnored) {
            Map<String, Money> bySub = new LinkedHashMap<>();
            for (OrderLineItem li : bill.lines) {
                if (li.status == LineStatus.CANCELLED) continue;
                String key = (li.guestTag != null) ? li.guestTag : "Shared";
                bySub.merge(key, li.lineTotal(), Money::add);
            }
            if (bySub.isEmpty()) return new EqualSplit().split(bill, 1);
            List<SubBill> out = new ArrayList<>();
            Money allocated = Money.ZERO;
            int idx = 0, n = bySub.size();
            for (Map.Entry<String, Money> e : bySub.entrySet()) {
                Money lineSub = e.getValue();
                Money share;
                if (idx == n - 1) {
                    share = bill.total.subtract(allocated); // remainder to last so parts sum exactly
                } else {
                    // proportional: lineSub/subtotal * total
                    double frac = lineSub.raw().doubleValue() / Math.max(1e-9, bill.subtotal.raw().doubleValue());
                    share = bill.total.percent(frac * 100.0);
                    allocated = allocated.add(share);
                }
                out.add(new SubBill(e.getKey(), share));
                idx++;
            }
            return out;
        }
    }

    static final class Payment {
        final String id; final PaymentMethod method; final Money amount; final boolean success;
        Payment(PaymentMethod method, Money amount, boolean success) {
            this.id = UUID.randomUUID().toString(); this.method = method; this.amount = amount; this.success = success;
        }
        @Override public String toString() { return "Payment[" + method + "," + amount + "," + (success ? "OK" : "FAIL") + "]"; }
    }

    /** Billing service: depends on Strategy interfaces (DIP). Discount applied before tax. */
    static final class BillingService {
        private final DiscountStrategy discount;
        private final TaxStrategy tax;
        BillingService(DiscountStrategy discount, TaxStrategy tax) { this.discount = discount; this.tax = tax; }

        Bill generateBill(Order order) {
            Money subtotal = order.subtotal();
            Money disc = discount.apply(subtotal);
            Money taxable = subtotal.subtract(disc);
            if (taxable.isZeroOrLess()) taxable = Money.ZERO;
            Money t = tax.tax(taxable);
            Money total = taxable.add(t);
            return new Bill(order.id, subtotal, disc, t, total, order.items());
        }
        List<SubBill> split(Bill bill, SplitType type, int guests) {
            SplitStrategy s = (type == SplitType.EQUAL) ? new EqualSplit() : new ProportionalSplit();
            return s.split(bill, guests);
        }
        Payment pay(Bill bill, PaymentMethod method, Money amount) {
            boolean ok = amount.raw().compareTo(bill.total.raw()) >= 0;
            return new Payment(method, amount, ok);
        }
    }

    /* ==========================================================================
     * REPOSITORIES (storage seam, DIP) — in-memory now, DB later.
     * ========================================================================== */

    interface Repository<T> { void save(String id, T entity); Optional<T> find(String id); List<T> all(); }

    static class InMemoryRepository<T> implements Repository<T> {
        private final Map<String, T> store = new ConcurrentHashMap<>();
        public void save(String id, T e) { store.put(id, e); }
        public Optional<T> find(String id) { return Optional.ofNullable(store.get(id)); }
        public List<T> all() { return new ArrayList<>(store.values()); }
    }

    /* ==========================================================================
     * TABLE ASSIGNMENT — Strategy
     * ========================================================================== */

    interface TableAssignmentStrategy { Optional<Table> pick(List<Table> tables, int partySize); }

    /** Best-fit: smallest free table that still seats the party (minimizes wasted seats). */
    static final class BestFitAssignment implements TableAssignmentStrategy {
        public Optional<Table> pick(List<Table> tables, int partySize) {
            Table best = null;
            for (Table t : tables) {
                if (t.status() == TableStatus.FREE && t.capacity >= partySize) {
                    if (best == null || t.capacity < best.capacity) best = t;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    /* ==========================================================================
     * SERVICES
     * ========================================================================== */

    static final class TableService {
        private final Repository<Table> tables;
        private final TableAssignmentStrategy strategy;
        TableService(Repository<Table> tables, TableAssignmentStrategy strategy) { this.tables = tables; this.strategy = strategy; }

        void addTable(Table t) { tables.save(t.id, t); }
        List<Table> all() { return tables.all(); }

        /** Thread-safe: pick a candidate, then CAS occupy(); on race-loss try the next. */
        Optional<Table> assignTable(int partySize) {
            for (int attempt = 0; attempt < tables.all().size() + 1; attempt++) {
                Optional<Table> candidate = strategy.pick(tables.all(), partySize);
                if (!candidate.isPresent()) return Optional.empty();
                if (candidate.get().occupy()) return candidate; // CAS won
                // else lost race -> loop and re-pick
            }
            return Optional.empty();
        }
    }

    static final class ReservationService {
        private final Repository<Reservation> reservations;
        private final TableService tableService;
        private final List<WaitlistEntry> waitlist = Collections.synchronizedList(new ArrayList<>());

        ReservationService(Repository<Reservation> reservations, TableService tableService) {
            this.reservations = reservations; this.tableService = tableService;
        }

        Reservation book(Customer c, int partySize, Instant time) {
            Reservation r = new Reservation(c, partySize, time);
            // optimistically hold a suitable table
            Optional<Table> t = tableService.assignTable(partySize);
            if (t.isPresent()) {
                // convert OCCUPIED back to RESERVED hold (we occupied to win the race, now mark reserved)
                t.get().markDirty(); t.get().free(); t.get().reserve();
                r.table = t.get();
            }
            reservations.save(r.id, r);
            return r;
        }
        Optional<Table> seat(String reservationId) {
            Reservation r = reservations.find(reservationId).orElseThrow(() -> new DomainException("No reservation"));
            if (r.status != ReservationStatus.BOOKED) throw new DomainException("Reservation not bookable: " + r.status);
            Table t = r.table;
            if (t != null && t.occupy()) { r.status = ReservationStatus.SEATED; return Optional.of(t); }
            // held table gone — try a fresh assignment
            Optional<Table> alt = tableService.assignTable(r.partySize);
            if (alt.isPresent()) { r.table = alt.get(); r.status = ReservationStatus.SEATED; }
            return alt;
        }
        WaitlistEntry addToWaitlist(Customer c, int partySize) {
            WaitlistEntry e = new WaitlistEntry(c, partySize);
            waitlist.add(e);
            return e;
        }
        /** FIFO scan for first entry whose party fits a now-free table; seat it atomically. */
        Optional<Table> seatNextFromWaitlist() {
            synchronized (waitlist) {
                for (int i = 0; i < waitlist.size(); i++) {
                    WaitlistEntry e = waitlist.get(i);
                    Optional<Table> t = tableService.assignTable(e.partySize);
                    if (t.isPresent()) { waitlist.remove(i); return t; }
                }
            }
            return Optional.empty();
        }
        int waitlistSize() { return waitlist.size(); }
    }

    static final class OrderService {
        private final Repository<Order> orders;
        private final Menu menu;
        private final Kitchen kitchen;
        OrderService(Repository<Order> orders, Menu menu, Kitchen kitchen) {
            this.orders = orders; this.menu = menu; this.kitchen = kitchen;
        }

        Order openOrder(Table table, OrderType type) {
            Order o = new Order(table, type);
            if (table != null) table.attachOrder(o);
            orders.save(o.id, o);
            return o;
        }
        void addItem(String orderId, String menuItemId, int qty, String guestTag) {
            Order o = orders.find(orderId).orElseThrow(() -> new DomainException("No order"));
            MenuItem mi = menu.get(menuItemId).orElseThrow(() -> new DomainException("No menu item"));
            o.addItem(new OrderLineItem(mi, qty, guestTag));
        }
        /** Fire: place the order (State CREATED->PLACED) and push a ticket to the kitchen. */
        KitchenTicket fire(String orderId) {
            Order o = orders.find(orderId).orElseThrow(() -> new DomainException("No order"));
            o.place();
            KitchenTicket ticket = KitchenTicketFactory.from(o);
            kitchen.submit(ticket);
            return ticket;
        }
        void prepare(String orderId)   { orders.find(orderId).ifPresent(Order::prepare); }
        void markReady(String orderId) { orders.find(orderId).ifPresent(Order::markReady); }
        void serve(String orderId)     { orders.find(orderId).ifPresent(Order::serve); }
        void close(String orderId)     { orders.find(orderId).ifPresent(Order::close); }
        Order get(String orderId)      { return orders.find(orderId).orElseThrow(() -> new DomainException("No order")); }
    }

    /* ==========================================================================
     * RESTAURANT — composition root wiring repositories + services (DI, not Singleton)
     * ========================================================================== */

    static final class Restaurant {
        final String name;
        final Menu menu = new Menu();
        final Kitchen kitchen = new Kitchen();
        final TableService tableService;
        final ReservationService reservationService;
        final OrderService orderService;
        final BillingService billingService;

        Restaurant(String name, DiscountStrategy discount, TaxStrategy tax) {
            this.name = name;
            Repository<Table> tableRepo = new InMemoryRepository<>();
            Repository<Reservation> resRepo = new InMemoryRepository<>();
            Repository<Order> orderRepo = new InMemoryRepository<>();
            this.tableService = new TableService(tableRepo, new BestFitAssignment());
            this.reservationService = new ReservationService(resRepo, tableService);
            this.orderService = new OrderService(orderRepo, menu, kitchen);
            this.billingService = new BillingService(discount, tax);
        }
    }

    /* ==========================================================================
     * DEMO — illustrates the key scenarios (review aid only).
     * ========================================================================== */

    public static void main(String[] args) {
        System.out.println("=== Restaurant Management System demo ===\n");

        // Build restaurant: 20% loyalty discount stacked with happy-hour 10%; 5% tax + 8% service charge.
        DiscountStrategy discount = new CompositeDiscount(List.of(new PercentageDiscount(10), new PercentageDiscount(20)));
        TaxStrategy tax = new FlatTaxStrategy(5, 8);
        Restaurant r = new Restaurant("The Test Kitchen", discount, tax);

        // KDS observer
        r.kitchen.register(new KitchenDisplay("Main"));

        // Tables
        for (int i = 1; i <= 4; i++) r.tableService.addTable(new Table(String.valueOf(i), i == 4 ? 6 : 4));

        // Menu
        MenuItem pasta = new MenuItem("Pasta", "Main", Money.of(12.00));
        MenuItem pizza = new MenuItem("Pizza", "Main", Money.of(15.50));
        MenuItem cola  = new MenuItem("Cola", "Drink", Money.of(3.00));
        MenuItem cake  = new MenuItem("Cake", "Dessert", Money.of(6.00));
        r.menu.add(pasta); r.menu.add(pizza); r.menu.add(cola); r.menu.add(cake);

        // --- Scenario 1: walk-in seated, ordered, fired, served, billed ---
        System.out.println("-- Scenario 1: walk-in dine-in --");
        Table t = r.tableService.assignTable(4).orElseThrow();
        System.out.println("Seated party of 4 at " + t);
        Order o = r.orderService.openOrder(t, OrderType.DINE_IN);
        r.orderService.addItem(o.id, pasta.id, 2, "Alice");
        r.orderService.addItem(o.id, cola.id, 1, "Alice");
        r.orderService.addItem(o.id, pizza.id, 1, "Bob");
        r.orderService.addItem(o.id, cake.id, 2, "Bob");
        System.out.println("Order before fire: " + o);
        KitchenTicket ticket = r.orderService.fire(o.id);
        System.out.println("Order after fire:  " + o);
        r.orderService.prepare(o.id);
        r.kitchen.advance(ticket.id, KitchenTicketStatus.PREPARING);
        r.orderService.markReady(o.id);
        r.kitchen.advance(ticket.id, KitchenTicketStatus.READY);
        r.orderService.serve(o.id);
        r.kitchen.advance(ticket.id, KitchenTicketStatus.PICKED_UP);
        System.out.println("Order after serve: " + o);

        Bill bill = r.billingService.generateBill(o);
        System.out.println(bill);
        System.out.println("Split EQUAL (2 guests):    " + r.billingService.split(bill, SplitType.EQUAL, 2));
        System.out.println("Split BY_GUEST:            " + r.billingService.split(bill, SplitType.BY_GUEST, 2));
        Payment p = r.billingService.pay(bill, PaymentMethod.CARD, bill.total);
        System.out.println("Payment: " + p);
        r.orderService.close(o.id);
        t.markDirty(); t.free();
        System.out.println("Table after close: " + t + "\n");

        // --- Scenario 2: illegal state transition is rejected ---
        System.out.println("-- Scenario 2: illegal transition --");
        Order o2 = r.orderService.openOrder(r.tableService.assignTable(2).orElseThrow(), OrderType.DINE_IN);
        try { o2.serve(); } catch (IllegalStateTransitionException ex) { System.out.println("Rejected: " + ex.getMessage()); }
        System.out.println();

        // --- Scenario 3: 86 an item ---
        System.out.println("-- Scenario 3: 86'd item --");
        r.menu.setAvailable(pizza.id, false);
        try { r.orderService.addItem(o2.id, pizza.id, 1, null); }
        catch (DomainException ex) { System.out.println("Rejected: " + ex.getMessage()); }
        r.menu.setAvailable(pizza.id, true);
        System.out.println();

        // --- Scenario 4: full house -> waitlist -> seat next when a table frees ---
        System.out.println("-- Scenario 4: waitlist --");
        // occupy remaining tables
        while (r.tableService.assignTable(4).isPresent()) { /* fill up */ }
        Optional<Table> none = r.tableService.assignTable(4);
        System.out.println("Table available now? " + none.isPresent());
        Customer carol = new Customer("Carol", "555-1");
        r.reservationService.addToWaitlist(carol, 4);
        System.out.println("Waitlist size: " + r.reservationService.waitlistSize());
        // free one table
        r.tableService.all().stream().filter(x -> x.status() == TableStatus.OCCUPIED).findFirst().ifPresent(Table::free);
        Optional<Table> seated = r.reservationService.seatNextFromWaitlist();
        System.out.println("Seated from waitlist at: " + seated.map(Object::toString).orElse("none")
                + "; remaining waitlist=" + r.reservationService.waitlistSize());
        System.out.println();

        // --- Scenario 5: concurrent seating never double-seats one table ---
        System.out.println("-- Scenario 5: concurrent seating safety --");
        Restaurant r2 = new Restaurant("Concurrency Test", new NoDiscount(), new FlatTaxStrategy(0, 0));
        Table shared = new Table("X", 4);
        r2.tableService.addTable(shared);
        int threads = 8;
        List<Thread> ts = new ArrayList<>();
        final int[] wins = {0};
        Object winLock = new Object();
        for (int i = 0; i < threads; i++) {
            Thread th = new Thread(() -> {
                if (r2.tableService.assignTable(4).isPresent()) {
                    synchronized (winLock) { wins[0]++; }
                }
            });
            ts.add(th);
        }
        ts.forEach(Thread::start);
        ts.forEach(th -> { try { th.join(); } catch (InterruptedException ignored) {} });
        System.out.println(threads + " threads raced for 1 table; winners = " + wins[0] + " (expected exactly 1)");

        System.out.println("\n=== demo complete ===");
    }
}

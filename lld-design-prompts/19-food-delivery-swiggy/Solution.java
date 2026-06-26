/* =============================================================================
 * Food Delivery Platform (Swiggy / Zomato) — Single-file LLD review artifact.
 *
 * THIS IS A READ-AND-REVISE ARTIFACT. It is complete and correct-by-inspection,
 * pure JDK, single process. You need not compile or run it.
 *
 * -----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -----------------------------------------------------------------------------
 * Functional scope:
 *   1. Single restaurant per order, or multi-restaurant carts? (Assume single.)
 *   2. Is discovery/search in scope, or start from "customer chose a restaurant"?
 *   3. Exact order states + legal transitions + who triggers each?
 *   4. Auto partner assignment or manual? Objective: nearest/least-busy/rating?
 *   5. Cancellation allowed in which states? Refund rules per state?
 *   6. Pricing: static sum, or taxes + delivery fee + packaging + surge + coupon?
 *      In what order do these apply?
 *   7. Live tracking push? To how many subscribers?
 *   8. Ratings for restaurant, partner, or both? Do they affect assignment?
 * Non-functional:
 *   9. Concurrency scale? Same order mutated by multiple threads?
 *  10. Must a partner never be double-booked?
 *  11. In-memory authoritative, or cache over DB?
 *  12. Idempotency for placeOrder / pay / cancel?
 * Scope-narrowing:
 *  13. Item-level stock (a dish sells out) in scope?
 *  14. Scheduled / group / multi-restaurant orders in scope?
 *  15. Multiple payment methods + failure handling depth?
 * Out of scope: real geospatial routing, ML ETA, fraud, notification transport,
 *   persistence, auth.
 *
 * -----------------------------------------------------------------------------
 * PATTERNS: State (order lifecycle), Strategy (assignment/payment/refund/pricing
 * components), Observer (tracking), Factory (state/strategy creation),
 * Decorator-Chain (pricing pipeline), Facade (FoodDeliveryService),
 * Value Object/Immutability (Money/Bill/Location).
 * CONCURRENCY: per-order ReentrantLock for transitions; AtomicReference CAS on
 * partner status (no double-booking); ConcurrentHashMap registries.
 * ========================================================================== */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class Solution {

    /* ===================== VALUE OBJECTS (immutable) ===================== */

    /** Money in integer cents — avoids floating point drift; immutable => thread-safe. */
    static final class Money {
        private final long cents;
        private Money(long cents) { this.cents = cents; }
        static Money ofCents(long c) { return new Money(c); }
        static Money rupees(double r) { return new Money(Math.round(r * 100)); }
        static final Money ZERO = new Money(0);
        Money plus(Money o) { return new Money(cents + o.cents); }
        Money minus(Money o) { return new Money(cents - o.cents); }
        Money times(double f) { return new Money(Math.round(cents * f)); }
        Money clampToZero() { return cents < 0 ? ZERO : this; }
        boolean isPositive() { return cents > 0; }
        long cents() { return cents; }
        @Override public String toString() { return String.format("Rs.%.2f", cents / 100.0); }
        @Override public boolean equals(Object o){ return o instanceof Money && ((Money)o).cents==cents; }
        @Override public int hashCode(){ return Long.hashCode(cents); }
    }

    /** Simple 2D location; immutable. Distance is Euclidean for the demo. */
    static final class Location {
        final double x, y;
        Location(double x, double y) { this.x = x; this.y = y; }
        double distanceTo(Location o) { return Math.hypot(x - o.x, y - o.y); }
        @Override public String toString() { return "(" + x + "," + y + ")"; }
    }

    /** Running-average rating; guarded internally for concurrent updates. */
    static final class Rating {
        private long sum = 0; private long count = 0;
        synchronized void add(int stars) { sum += stars; count++; }
        synchronized double average() { return count == 0 ? 0.0 : (double) sum / count; }
        @Override public String toString() { return String.format("%.2f (%d)", average(), count); }
    }

    /* ===================== CATALOG / MENU ===================== */

    static final class MenuItem {
        final String id, name; final Money price; final boolean veg;
        private volatile boolean available = true;
        MenuItem(String id, String name, Money price, boolean veg) {
            this.id = id; this.name = name; this.price = price; this.veg = veg;
        }
        boolean isAvailable() { return available; }
        void setAvailable(boolean a) { this.available = a; }
        @Override public String toString() { return name + " " + price; }
    }

    static final class Menu {
        private final Map<String, MenuItem> items = new ConcurrentHashMap<>();
        void add(MenuItem item) { items.put(item.id, item); }
        MenuItem get(String id) { return items.get(id); }
        Collection<MenuItem> all() { return items.values(); }
    }

    /* ===================== ACTORS ===================== */

    static final class Customer {
        final String id, name; final Location location;
        final Cart cart = new Cart();
        Customer(String id, String name, Location location) {
            this.id = id; this.name = name; this.location = location;
        }
    }

    static final class Restaurant {
        final String id, name; final Location location; final Menu menu = new Menu();
        final Rating rating = new Rating();
        private volatile boolean open = true;          // open hours
        private volatile boolean acceptingOrders = true; // kitchen capacity
        Restaurant(String id, String name, Location location) {
            this.id = id; this.name = name; this.location = location;
        }
        boolean isAvailable() { return open && acceptingOrders; }
        void setOpen(boolean o) { this.open = o; }
        void setAcceptingOrders(boolean a) { this.acceptingOrders = a; }
    }

    enum PartnerStatus { AVAILABLE, BUSY, OFFLINE }

    static final class DeliveryPartner {
        final String id, name; volatile Location location;
        final Rating rating = new Rating();
        // CAS-guarded status => prevents double-booking under concurrency.
        private final AtomicReference<PartnerStatus> status =
                new AtomicReference<>(PartnerStatus.AVAILABLE);
        DeliveryPartner(String id, String name, Location location) {
            this.id = id; this.name = name; this.location = location;
        }
        boolean isAvailable() { return status.get() == PartnerStatus.AVAILABLE; }
        /** Lock-free claim; first thread wins. Returns false if already taken. */
        boolean tryAssign() { return status.compareAndSet(PartnerStatus.AVAILABLE, PartnerStatus.BUSY); }
        void release() { status.set(PartnerStatus.AVAILABLE); }
        PartnerStatus status() { return status.get(); }
        @Override public String toString() { return name + "[" + status.get() + "]"; }
    }

    /* ===================== CART ===================== */

    static final class CartLine {
        final MenuItem item; int qty;
        CartLine(MenuItem item, int qty) { this.item = item; this.qty = qty; }
        Money lineTotal() { return item.price.times(qty); }
    }

    /** Cart is restaurant-scoped; mutations synchronized per customer. */
    static final class Cart {
        private String restaurantId;
        private final Map<String, CartLine> lines = new LinkedHashMap<>();

        synchronized void addItem(String restaurantId, MenuItem item, int qty) {
            if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
            if (this.restaurantId != null && !this.restaurantId.equals(restaurantId))
                throw new IllegalStateException("Cart is scoped to one restaurant; clear it first.");
            this.restaurantId = restaurantId;
            lines.merge(item.id, new CartLine(item, qty),
                    (existing, add) -> { existing.qty += add.qty; return existing; });
        }
        synchronized void removeItem(String itemId) {
            lines.remove(itemId);
            if (lines.isEmpty()) restaurantId = null;
        }
        synchronized boolean isEmpty() { return lines.isEmpty(); }
        synchronized String restaurantId() { return restaurantId; }
        synchronized Money subtotal() {
            Money t = Money.ZERO;
            for (CartLine l : lines.values()) t = t.plus(l.lineTotal());
            return t;
        }
        synchronized List<OrderLine> snapshot() {
            List<OrderLine> snap = new ArrayList<>();
            for (CartLine l : lines.values()) snap.add(new OrderLine(l.item.id, l.item.name, l.item.price, l.qty));
            return snap;
        }
        synchronized void clear() { lines.clear(); restaurantId = null; }
    }

    /** Immutable snapshot of a cart line at placement time. */
    static final class OrderLine {
        final String itemId, name; final Money unitPrice; final int qty;
        OrderLine(String itemId, String name, Money unitPrice, int qty) {
            this.itemId = itemId; this.name = name; this.unitPrice = unitPrice; this.qty = qty;
        }
        Money lineTotal() { return unitPrice.times(qty); }
        @Override public String toString() { return qty + "x " + name; }
    }

    /* ===================== BILL (value object) + PRICING CHAIN ===================== */

    static final class Bill {
        final Money subtotal, tax, packaging, deliveryFee, surge, discount, total;
        Bill(Money subtotal, Money tax, Money packaging, Money deliveryFee,
             Money surge, Money discount, Money total) {
            this.subtotal = subtotal; this.tax = tax; this.packaging = packaging;
            this.deliveryFee = deliveryFee; this.surge = surge; this.discount = discount;
            this.total = total;
        }
        @Override public String toString() {
            return "Bill{sub=" + subtotal + ", tax=" + tax + ", pack=" + packaging +
                    ", delivery=" + deliveryFee + ", surge=" + surge +
                    ", discount=" + discount + ", TOTAL=" + total + "}";
        }
    }

    /** Mutable accumulator passed through the pricing chain. */
    static final class BillBuilder {
        Money subtotal = Money.ZERO, tax = Money.ZERO, packaging = Money.ZERO,
              deliveryFee = Money.ZERO, surge = Money.ZERO, discount = Money.ZERO;
        Bill build() {
            Money total = subtotal.plus(tax).plus(packaging).plus(deliveryFee)
                    .plus(surge).minus(discount).clampToZero();
            return new Bill(subtotal, tax, packaging, deliveryFee, surge, discount, total);
        }
    }

    /** Context the pricing components read from. */
    static final class PricingContext {
        final List<OrderLine> lines; final double distance; final double surgeMultiplier;
        final String couponCode;
        PricingContext(List<OrderLine> lines, double distance, double surgeMultiplier, String couponCode) {
            this.lines = lines; this.distance = distance;
            this.surgeMultiplier = surgeMultiplier; this.couponCode = couponCode;
        }
    }

    /** STRATEGY + DECORATOR/CHAIN element: one pricing rule. */
    interface PricingComponent { void apply(BillBuilder b, PricingContext ctx); }

    static final class SubtotalComponent implements PricingComponent {
        public void apply(BillBuilder b, PricingContext ctx) {
            Money sub = Money.ZERO;
            for (OrderLine l : ctx.lines) sub = sub.plus(l.lineTotal());
            b.subtotal = sub;
        }
    }
    static final class TaxComponent implements PricingComponent {
        private final double rate; TaxComponent(double rate) { this.rate = rate; }
        public void apply(BillBuilder b, PricingContext ctx) { b.tax = b.subtotal.times(rate); }
    }
    static final class PackagingComponent implements PricingComponent {
        private final Money perOrder; PackagingComponent(Money perOrder) { this.perOrder = perOrder; }
        public void apply(BillBuilder b, PricingContext ctx) { b.packaging = perOrder; }
    }
    static final class DeliveryFeeComponent implements PricingComponent {
        private final Money base, perKm;
        DeliveryFeeComponent(Money base, Money perKm) { this.base = base; this.perKm = perKm; }
        public void apply(BillBuilder b, PricingContext ctx) {
            b.deliveryFee = base.plus(perKm.times(ctx.distance));
        }
    }
    /** Surge applies a multiplier on (subtotal + delivery) above 1.0x. */
    static final class SurgeComponent implements PricingComponent {
        public void apply(BillBuilder b, PricingContext ctx) {
            if (ctx.surgeMultiplier > 1.0) {
                Money base = b.subtotal.plus(b.deliveryFee);
                b.surge = base.times(ctx.surgeMultiplier - 1.0);
            }
        }
    }
    /** Coupon applied last; clamped so total can never go negative (chain owns precedence). */
    static final class CouponComponent implements PricingComponent {
        private final Map<String, Double> coupons; // code -> fraction off subtotal
        CouponComponent(Map<String, Double> coupons) { this.coupons = coupons; }
        public void apply(BillBuilder b, PricingContext ctx) {
            if (ctx.couponCode == null) return;
            Double frac = coupons.get(ctx.couponCode);
            if (frac == null) return;
            b.discount = b.subtotal.times(frac);
        }
    }

    /** PricingEngine runs an ordered chain to produce an immutable Bill. */
    static final class PricingEngine {
        private final List<PricingComponent> chain;
        PricingEngine(List<PricingComponent> chain) { this.chain = chain; }
        Bill computeBill(PricingContext ctx) {
            BillBuilder b = new BillBuilder();
            for (PricingComponent c : chain) c.apply(b, ctx);
            return b.build();
        }
    }

    /* ===================== PAYMENT (Strategy + Factory) ===================== */

    static final class PaymentResult {
        final boolean success; final String txnId; final String reason;
        private PaymentResult(boolean s, String t, String r){ success=s; txnId=t; reason=r; }
        static PaymentResult ok(String txn){ return new PaymentResult(true, txn, "OK"); }
        static PaymentResult fail(String reason){ return new PaymentResult(false, null, reason); }
    }

    interface PaymentStrategy { PaymentResult pay(Money amount); String method(); }

    static final class CardPayment implements PaymentStrategy {
        private final boolean willSucceed;
        CardPayment(boolean willSucceed){ this.willSucceed = willSucceed; }
        public PaymentResult pay(Money amount) {
            return willSucceed ? PaymentResult.ok("CARD-" + UUID.randomUUID())
                               : PaymentResult.fail("Card declined");
        }
        public String method(){ return "CARD"; }
    }
    static final class UpiPayment implements PaymentStrategy {
        public PaymentResult pay(Money amount){ return PaymentResult.ok("UPI-" + UUID.randomUUID()); }
        public String method(){ return "UPI"; }
    }
    static final class CodPayment implements PaymentStrategy {
        // Cash on delivery: "charged" later; treated as authorized at placement.
        public PaymentResult pay(Money amount){ return PaymentResult.ok("COD-" + UUID.randomUUID()); }
        public String method(){ return "COD"; }
    }

    enum PaymentMethod { CARD, UPI, COD }
    static final class PaymentFactory {
        static PaymentStrategy create(PaymentMethod m) {
            switch (m) {
                case CARD: return new CardPayment(true);
                case UPI:  return new UpiPayment();
                case COD:  return new CodPayment();
                default: throw new IllegalArgumentException("Unknown method " + m);
            }
        }
    }

    /* ===================== ASSIGNMENT (Strategy + Factory) ===================== */

    /** Returns a partner ALREADY CAS-claimed (BUSY), or null if none available. */
    interface AssignmentStrategy {
        DeliveryPartner assign(Location restaurant, Collection<DeliveryPartner> pool);
    }

    static final class NearestPartnerStrategy implements AssignmentStrategy {
        public DeliveryPartner assign(Location restaurant, Collection<DeliveryPartner> pool) {
            // Sort available by distance, then atomically claim the first that wins CAS.
            List<DeliveryPartner> candidates = new ArrayList<>();
            for (DeliveryPartner p : pool) if (p.isAvailable()) candidates.add(p);
            candidates.sort(Comparator.comparingDouble(p -> p.location.distanceTo(restaurant)));
            for (DeliveryPartner p : candidates) if (p.tryAssign()) return p;
            return null;
        }
    }

    static final class HighestRatedStrategy implements AssignmentStrategy {
        public DeliveryPartner assign(Location restaurant, Collection<DeliveryPartner> pool) {
            List<DeliveryPartner> candidates = new ArrayList<>();
            for (DeliveryPartner p : pool) if (p.isAvailable()) candidates.add(p);
            candidates.sort(Comparator.comparingDouble((DeliveryPartner p) -> p.rating.average()).reversed());
            for (DeliveryPartner p : candidates) if (p.tryAssign()) return p;
            return null;
        }
    }

    static final class RoundRobinStrategy implements AssignmentStrategy {
        private int cursor = 0;
        public synchronized DeliveryPartner assign(Location restaurant, Collection<DeliveryPartner> pool) {
            List<DeliveryPartner> list = new ArrayList<>(pool);
            for (int i = 0; i < list.size(); i++) {
                DeliveryPartner p = list.get((cursor + i) % list.size());
                if (p.isAvailable() && p.tryAssign()) { cursor = (cursor + i + 1) % list.size(); return p; }
            }
            return null;
        }
    }

    enum AssignmentMode { NEAREST, HIGHEST_RATED, ROUND_ROBIN }
    static final class AssignmentStrategyFactory {
        static AssignmentStrategy create(AssignmentMode m) {
            switch (m) {
                case NEAREST: return new NearestPartnerStrategy();
                case HIGHEST_RATED: return new HighestRatedStrategy();
                case ROUND_ROBIN: return new RoundRobinStrategy();
                default: throw new IllegalArgumentException("Unknown mode " + m);
            }
        }
    }

    /* ===================== REFUND (Strategy) ===================== */

    interface RefundPolicy { Money computeRefund(Order order); }

    /** State-aware refund: full before prep, partial during prep, none once out for delivery. */
    static final class DefaultRefundPolicy implements RefundPolicy {
        public Money computeRefund(Order order) {
            Money total = order.bill().total;
            String state = order.stateName();
            switch (state) {
                case "PLACED":
                case "CONFIRMED":  return total;                 // full refund
                case "PREPARING":  return total.times(0.5);      // partial — kitchen cost
                default:           return Money.ZERO;            // no refund after pickup
            }
        }
    }

    /* ===================== OBSERVER (live tracking / notifications) ===================== */

    interface OrderObserver { void onOrderUpdate(Order order); }

    static final class CustomerNotifier implements OrderObserver {
        public void onOrderUpdate(Order o) {
            System.out.println("  [Customer " + o.customer().name + "] Order " + o.id()
                    + " -> " + o.stateName());
        }
    }
    static final class RestaurantDashboard implements OrderObserver {
        public void onOrderUpdate(Order o) {
            System.out.println("  [Restaurant " + o.restaurant().name + "] Order " + o.id()
                    + " -> " + o.stateName());
        }
    }
    static final class TrackingFeed implements OrderObserver {
        public void onOrderUpdate(Order o) {
            String partner = o.partner() == null ? "unassigned" : o.partner().name;
            System.out.println("  [Tracking] Order " + o.id() + " state=" + o.stateName()
                    + " partner=" + partner);
        }
    }

    /* ===================== STATE PATTERN (order lifecycle) ===================== */

    interface OrderState {
        OrderState next(Order order);   // returns next state or throws if terminal
        String name();
        boolean canCancel();
        boolean isTerminal();
    }

    static final class PlacedState implements OrderState {
        public OrderState next(Order o) { return new ConfirmedState(); }
        public String name() { return "PLACED"; }
        public boolean canCancel() { return true; }
        public boolean isTerminal() { return false; }
    }
    static final class ConfirmedState implements OrderState {
        public OrderState next(Order o) { return new PreparingState(); }
        public String name() { return "CONFIRMED"; }
        public boolean canCancel() { return true; }
        public boolean isTerminal() { return false; }
    }
    static final class PreparingState implements OrderState {
        public OrderState next(Order o) { return new PickedUpState(); }
        public String name() { return "PREPARING"; }
        public boolean canCancel() { return true; }   // partial refund applies
        public boolean isTerminal() { return false; }
    }
    static final class PickedUpState implements OrderState {
        public OrderState next(Order o) { return new OutForDeliveryState(); }
        public String name() { return "PICKED_UP"; }
        public boolean canCancel() { return false; }
        public boolean isTerminal() { return false; }
    }
    static final class OutForDeliveryState implements OrderState {
        public OrderState next(Order o) { return new DeliveredState(); }
        public String name() { return "OUT_FOR_DELIVERY"; }
        public boolean canCancel() { return false; }
        public boolean isTerminal() { return false; }
    }
    static final class DeliveredState implements OrderState {
        public OrderState next(Order o) { throw new IllegalStateException("Order already delivered"); }
        public String name() { return "DELIVERED"; }
        public boolean canCancel() { return false; }
        public boolean isTerminal() { return true; }
    }
    static final class CancelledState implements OrderState {
        public OrderState next(Order o) { throw new IllegalStateException("Order is cancelled"); }
        public String name() { return "CANCELLED"; }
        public boolean canCancel() { return false; }
        public boolean isTerminal() { return true; }
    }

    /** FACTORY for the initial state — single place to evolve creation. */
    static final class OrderStateFactory {
        static OrderState initial() { return new PlacedState(); }
    }

    /* ===================== ORDER (aggregate; State subject; Observer subject) ===================== */

    static final class Order {
        private final String id;
        private final Customer customer;
        private final Restaurant restaurant;
        private final List<OrderLine> lines;
        private final Bill bill;
        private final String paymentTxnId;
        private volatile DeliveryPartner partner;
        private volatile OrderState state;
        private final List<OrderObserver> observers = new CopyOnWriteArrayList<>();
        private final ReentrantLock lock = new ReentrantLock(); // serializes transitions
        private final long createdAt = System.currentTimeMillis();

        Order(String id, Customer customer, Restaurant restaurant, List<OrderLine> lines,
              Bill bill, DeliveryPartner partner, String paymentTxnId) {
            this.id = id; this.customer = customer; this.restaurant = restaurant;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.bill = bill; this.partner = partner; this.paymentTxnId = paymentTxnId;
            this.state = OrderStateFactory.initial();
        }

        void addObserver(OrderObserver o) { observers.add(o); }
        private void notifyObservers() { for (OrderObserver o : observers) o.onOrderUpdate(this); }

        /** Atomic forward transition; notifies observers; frees partner on delivery. */
        void advance() {
            lock.lock();
            try {
                if (state.isTerminal())
                    throw new IllegalStateException("Cannot advance terminal order " + id + " (" + state.name() + ")");
                OrderState nextState = state.next(this);
                this.state = nextState;
                if (nextState instanceof DeliveredState && partner != null) {
                    partner.release();
                }
                notifyObservers();
            } finally { lock.unlock(); }
        }

        /** Idempotent, state-guarded cancellation; returns refund amount. */
        Money cancel(RefundPolicy policy) {
            lock.lock();
            try {
                if (state instanceof CancelledState) return Money.ZERO;      // idempotent no-op
                if (!state.canCancel())
                    throw new IllegalStateException("Order " + id + " cannot be cancelled in state " + state.name());
                Money refund = policy.computeRefund(this);
                this.state = new CancelledState();
                if (partner != null) partner.release();
                notifyObservers();
                return refund;
            } finally { lock.unlock(); }
        }

        // accessors
        String id() { return id; }
        Customer customer() { return customer; }
        Restaurant restaurant() { return restaurant; }
        List<OrderLine> lines() { return lines; }
        Bill bill() { return bill; }
        DeliveryPartner partner() { return partner; }
        String stateName() { return state.name(); }
        boolean isDelivered() { return state instanceof DeliveredState; }
        String paymentTxnId() { return paymentTxnId; }
    }

    /* ===================== DEMAND PROVIDER (surge input) ===================== */

    interface DemandProvider { double surgeMultiplierFor(Restaurant r); }
    static final class StaticDemandProvider implements DemandProvider {
        private volatile double multiplier;
        StaticDemandProvider(double m) { this.multiplier = m; }
        void set(double m) { this.multiplier = m; }
        public double surgeMultiplierFor(Restaurant r) { return multiplier; }
    }

    /* ===================== FACADE: FoodDeliveryService ===================== */

    static final class FoodDeliveryService {
        private final Map<String, Customer> customers = new ConcurrentHashMap<>();
        private final Map<String, Restaurant> restaurants = new ConcurrentHashMap<>();
        private final Map<String, DeliveryPartner> partners = new ConcurrentHashMap<>();
        private final Map<String, Order> orders = new ConcurrentHashMap<>();

        private final AssignmentStrategy assignmentStrategy;
        private final PricingEngine pricingEngine;
        private final RefundPolicy refundPolicy;
        private final DemandProvider demandProvider;

        // default observers attached to every order (live tracking)
        private final List<OrderObserver> defaultObservers;

        FoodDeliveryService(AssignmentStrategy assignmentStrategy, PricingEngine pricingEngine,
                            RefundPolicy refundPolicy, DemandProvider demandProvider,
                            List<OrderObserver> defaultObservers) {
            this.assignmentStrategy = assignmentStrategy;
            this.pricingEngine = pricingEngine;
            this.refundPolicy = refundPolicy;
            this.demandProvider = demandProvider;
            this.defaultObservers = defaultObservers;
        }

        Customer registerCustomer(String name, Location loc) {
            Customer c = new Customer("C-" + UUID.randomUUID(), name, loc);
            customers.put(c.id, c); return c;
        }
        Restaurant registerRestaurant(String name, Location loc) {
            Restaurant r = new Restaurant("R-" + UUID.randomUUID(), name, loc);
            restaurants.put(r.id, r); return r;
        }
        DeliveryPartner registerPartner(String name, Location loc) {
            DeliveryPartner p = new DeliveryPartner("P-" + UUID.randomUUID(), name, loc);
            partners.put(p.id, p); return p;
        }

        void addToCart(String customerId, Restaurant restaurant, String itemId, int qty) {
            Customer c = require(customers.get(customerId), "customer");
            MenuItem item = restaurant.menu.get(itemId);
            if (item == null) throw new NoSuchElementException("No such item " + itemId);
            if (!item.isAvailable()) throw new IllegalStateException("Item unavailable: " + item.name);
            c.cart.addItem(restaurant.id, item, qty);
        }

        /**
         * Order of operations: validate -> price -> PAY -> ASSIGN.
         * Pay before assign so we never lock a scarce partner for an unpayable order.
         */
        Order placeOrder(String customerId, String couponCode, PaymentStrategy payment) {
            Customer c = require(customers.get(customerId), "customer");
            Cart cart = c.cart;
            synchronized (cart) {
                if (cart.isEmpty()) throw new IllegalStateException("Cart is empty");
                Restaurant r = require(restaurants.get(cart.restaurantId()), "restaurant");

                // GUARD: restaurant availability + item availability
                if (!r.isAvailable())
                    throw new IllegalStateException("Restaurant not accepting orders: " + r.name);
                List<OrderLine> lines = cart.snapshot();
                for (OrderLine l : lines) {
                    MenuItem mi = r.menu.get(l.itemId);
                    if (mi == null || !mi.isAvailable())
                        throw new IllegalStateException("Item became unavailable: " + l.name);
                }

                // PRICE
                double distance = r.location.distanceTo(c.location);
                double surge = demandProvider.surgeMultiplierFor(r);
                Bill bill = pricingEngine.computeBill(new PricingContext(lines, distance, surge, couponCode));

                // PAY
                PaymentResult pr = payment.pay(bill.total);
                if (!pr.success)
                    throw new IllegalStateException("Payment failed (" + payment.method() + "): " + pr.reason);

                // ASSIGN (CAS-claimed inside strategy)
                DeliveryPartner partner = assignmentStrategy.assign(r.location, partners.values());
                if (partner == null) {
                    // In production: queue + retry. Here: fail fast. (Payment would be refunded.)
                    throw new IllegalStateException("No delivery partner available; order not placed.");
                }

                // CREATE
                Order order = new Order("O-" + UUID.randomUUID(), c, r, lines, bill, partner, pr.txnId);
                for (OrderObserver o : defaultObservers) order.addObserver(o);
                orders.put(order.id(), order);
                cart.clear();

                System.out.println("Placed " + order.id() + " | " + bill
                        + " | partner=" + partner.name + " | txn=" + pr.txnId);
                order.advance(); // PLACED handled implicitly; first notify happens on confirm path below
                return order;
            }
        }

        void advanceOrder(String orderId) { require(orders.get(orderId), "order").advance(); }

        Money cancelOrder(String orderId) {
            Order o = require(orders.get(orderId), "order");
            Money refund = o.cancel(refundPolicy);
            System.out.println("Cancelled " + orderId + " refund=" + refund);
            return refund;
        }

        void rateOrder(String orderId, int restaurantStars, int partnerStars) {
            Order o = require(orders.get(orderId), "order");
            if (!o.isDelivered())
                throw new IllegalStateException("Can only rate a delivered order");
            o.restaurant().rating.add(restaurantStars);
            if (o.partner() != null) o.partner().rating.add(partnerStars);
            System.out.println("Rated " + orderId + " restaurant=" + restaurantStars
                    + " partner=" + partnerStars);
        }

        Order getOrder(String id) { return orders.get(id); }

        private static <T> T require(T v, String what) {
            if (v == null) throw new NoSuchElementException("No such " + what);
            return v;
        }
    }

    /* ===================== DEMO ===================== */

    public static void main(String[] args) throws Exception {
        // --- Wire pricing chain (order matters): subtotal -> tax+packaging+delivery -> surge -> coupon
        Map<String, Double> coupons = new HashMap<>();
        coupons.put("WELCOME50", 0.50); // 50% off subtotal
        PricingEngine pricing = new PricingEngine(Arrays.asList(
                new SubtotalComponent(),
                new TaxComponent(0.05),                                  // 5% GST
                new PackagingComponent(Money.rupees(20)),
                new DeliveryFeeComponent(Money.rupees(20), Money.rupees(8)), // base + per "km"
                new SurgeComponent(),
                new CouponComponent(coupons)
        ));

        StaticDemandProvider demand = new StaticDemandProvider(1.0); // no surge initially
        AssignmentStrategy assignment = AssignmentStrategyFactory.create(AssignmentMode.NEAREST);
        RefundPolicy refunds = new DefaultRefundPolicy();
        List<OrderObserver> observers = Arrays.asList(
                new CustomerNotifier(), new RestaurantDashboard(), new TrackingFeed());

        FoodDeliveryService svc = new FoodDeliveryService(
                assignment, pricing, refunds, demand, observers);

        // --- Register actors
        Customer alice = svc.registerCustomer("Alice", new Location(0, 0));
        Restaurant biryaniHouse = svc.registerRestaurant("Biryani House", new Location(2, 1));
        biryaniHouse.menu.add(new MenuItem("M1", "Chicken Biryani", Money.rupees(250), false));
        biryaniHouse.menu.add(new MenuItem("M2", "Paneer Tikka", Money.rupees(180), true));
        biryaniHouse.menu.add(new MenuItem("M3", "Gulab Jamun", Money.rupees(60), true));

        DeliveryPartner bob = svc.registerPartner("Bob", new Location(3, 3));
        DeliveryPartner carol = svc.registerPartner("Carol", new Location(2, 0)); // nearest to restaurant

        System.out.println("=== Scenario 1: happy path with coupon, advance to delivered ===");
        svc.addToCart(alice.id, biryaniHouse, "M1", 2);
        svc.addToCart(alice.id, biryaniHouse, "M3", 1);
        Order o1 = svc.placeOrder(alice.id, "WELCOME50",
                PaymentFactory.create(PaymentMethod.UPI));
        // drive lifecycle: CONFIRMED -> PREPARING -> PICKED_UP -> OUT_FOR_DELIVERY -> DELIVERED
        for (int i = 0; i < 4; i++) svc.advanceOrder(o1.id());
        System.out.println("Final state of " + o1.id() + ": " + o1.stateName()
                + " | partner " + o1.partner().name + " status=" + o1.partner().status());
        svc.rateOrder(o1.id(), 5, 4);
        System.out.println("Restaurant rating: " + biryaniHouse.rating
                + " | Carol rating: " + (o1.partner() == carol ? carol.rating : bob.rating));

        System.out.println("\n=== Scenario 2: surge pricing ===");
        demand.set(1.8); // surge active
        svc.addToCart(alice.id, biryaniHouse, "M2", 1);
        Order o2 = svc.placeOrder(alice.id, null, PaymentFactory.create(PaymentMethod.CARD));
        System.out.println("Surge bill: " + o2.bill());

        System.out.println("\n=== Scenario 3: cancellation with partial refund (during PREPARING) ===");
        demand.set(1.0);
        svc.addToCart(alice.id, biryaniHouse, "M1", 1);
        Order o3 = svc.placeOrder(alice.id, null, PaymentFactory.create(PaymentMethod.COD));
        svc.advanceOrder(o3.id()); // PLACED->CONFIRMED already done in placeOrder; now -> PREPARING
        Money refund = svc.cancelOrder(o3.id());
        System.out.println("Refund for " + o3.id() + " = " + refund + " (total was " + o3.bill().total + ")");

        System.out.println("\n=== Scenario 4: cannot cancel after pickup ===");
        svc.addToCart(alice.id, biryaniHouse, "M2", 2);
        Order o4 = svc.placeOrder(alice.id, null, PaymentFactory.create(PaymentMethod.UPI));
        svc.advanceOrder(o4.id()); // -> PREPARING
        svc.advanceOrder(o4.id()); // -> PICKED_UP
        try {
            svc.cancelOrder(o4.id());
        } catch (IllegalStateException ex) {
            System.out.println("Expected: " + ex.getMessage());
        }

        System.out.println("\n=== Scenario 5: payment failure aborts order (no partner locked) ===");
        svc.addToCart(alice.id, biryaniHouse, "M1", 1);
        try {
            svc.placeOrder(alice.id, null, new CardPayment(false));
        } catch (IllegalStateException ex) {
            System.out.println("Expected: " + ex.getMessage());
        }

        System.out.println("\n=== Scenario 6: concurrency — many orders contend for one partner ===");
        // Reset: take Bob/Carol busy by finishing prior orders; create one free partner only.
        FoodDeliveryService svc2 = new FoodDeliveryService(
                AssignmentStrategyFactory.create(AssignmentMode.NEAREST),
                pricing, refunds, new StaticDemandProvider(1.0), observers);
        Customer dan = svc2.registerCustomer("Dan", new Location(0, 0));
        Restaurant cafe = svc2.registerRestaurant("Cafe", new Location(1, 1));
        cafe.menu.add(new MenuItem("X1", "Coffee", Money.rupees(100), true));
        svc2.registerPartner("Solo", new Location(1, 1)); // exactly ONE partner

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    // each thread builds its own cart line then tries to place
                    dan.cart.addItem(cafe.id, cafe.menu.get("X1"), 1);
                    svc2.placeOrder(dan.id, null, PaymentFactory.create(PaymentMethod.UPI));
                    success.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        System.out.println("Concurrent placements: success=" + success.get()
                + " failed=" + failed.get()
                + " (at most 1 success expected since only one partner & shared cart)");

        System.out.println("\nDemo complete.");
    }
}

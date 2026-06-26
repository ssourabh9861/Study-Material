/* ============================================================================
 * Online Shopping Cart (Amazon) — Single-file LLD REVIEW / REVISION artifact.
 *
 * This file is for READING and revision. It is complete and correct-by-inspection
 * (no TODOs, no omitted bodies). You do NOT need to compile or run it.
 *
 * ----------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING:
 *  Functional:
 *   1. Single-user session or multi-user contending for the SAME stock?
 *   2. Guest carts that merge into a user cart on login, or logged-in only?
 *   3. Product types: simple only, or variants (size/color), bundles, digital?
 *   4. Is catalog browsing (nested categories/search) in scope?
 *   5. Discount kinds: %-off, flat-off, BOGO/BuyXGetY, free-shipping,
 *      category-specific, cart-level vs item-level, stackable or exclusive?
 *   6. Payment methods: card, wallet, UPI, gift card, COD? Split payments?
 *   7. Order lifecycle + which transitions are legal?
 *   8. Wishlist / save-for-later in scope (move cart <-> wishlist)?
 *  Non-functional:
 *   9. Concurrency on inventory required? (Assume yes — multi-user.)
 *  10. Reservation semantics: decrement at add / checkout / payment? TTL? Oversell?
 *  11. Strong consistency on stock (never negative under race)? (Yes.)
 *  12. Money representation: long minor-units vs BigDecimal? Rounding/tax?
 *  Scope:
 *  13. OUT: real payment gateway, DB, network/API, auth, shipping carriers, search.
 *      IN: domain entities, pricing pipeline, coupon engine, checkout orchestration,
 *      order state machine, inventory concurrency.
 *
 * DESIGN PATTERNS USED:
 *   Strategy  -> DiscountStrategy, PaymentStrategy, PricingStage
 *   Composite -> CatalogComponent / Category / ProductLeaf
 *   State     -> OrderState lifecycle
 *   Observer  -> StockObserver / Subject (back-in-stock, price-change)
 *   Builder   -> Order
 *   Facade    -> CheckoutService
 *   Concurrency -> per-product striped locking in Inventory (atomic reserve)
 * ============================================================================ */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class Solution {

    /* ======================= Value object: Money ======================== */
    /** Immutable money in minor units (cents/paise). Avoids float drift. */
    static final class Money {
        static final Money ZERO = new Money(0, "INR");
        private final long minorUnits;
        private final String currency;

        Money(long minorUnits, String currency) {
            this.minorUnits = minorUnits;
            this.currency = currency;
        }
        static Money of(long major, long minor, String ccy) {
            return new Money(major * 100 + minor, ccy);
        }
        long minorUnits() { return minorUnits; }
        String currency() { return currency; }

        Money add(Money o) { check(o); return new Money(minorUnits + o.minorUnits, currency); }
        Money subtract(Money o) { check(o); return new Money(minorUnits - o.minorUnits, currency); }
        Money multiply(int qty) { return new Money(minorUnits * qty, currency); }

        /** Percentage of this amount, rounded half-up, in minor units. */
        Money percentage(int pct) {
            long num = minorUnits * pct;
            long rounded = (num + (num >= 0 ? 50 : -50)) / 100; // half-up
            return new Money(rounded, currency);
        }
        Money clampNonNegative() { return minorUnits < 0 ? new Money(0, currency) : this; }
        boolean isNegative() { return minorUnits < 0; }
        boolean greaterOrEqual(Money o) { check(o); return minorUnits >= o.minorUnits; }

        private void check(Money o) {
            if (!currency.equals(o.currency))
                throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + o.currency);
        }
        @Override public String toString() {
            return String.format("%s %d.%02d", currency, minorUnits / 100, Math.abs(minorUnits % 100));
        }
    }

    /* ======================= Observer pattern =========================== */
    interface StockObserver {
        void onBackInStock(Product product);
        void onPriceChange(Product product, Money newPrice);
    }
    /** Base Subject: holds observers and broadcasts events. */
    static abstract class Subject {
        private final List<StockObserver> observers = new CopyOnWriteArrayList<>();
        void subscribe(StockObserver o) { observers.add(o); }
        void unsubscribe(StockObserver o) { observers.remove(o); }
        protected void notifyBackInStock(Product p) { for (StockObserver o : observers) o.onBackInStock(p); }
        protected void notifyPriceChange(Product p, Money price) { for (StockObserver o : observers) o.onPriceChange(p, price); }
    }

    /* ======================= Product (Subject) ========================== */
    static final class Product extends Subject {
        private final String id;
        private final String name;
        private volatile Money unitPrice;
        private final String categoryId;

        Product(String id, String name, Money unitPrice, String categoryId) {
            this.id = id; this.name = name; this.unitPrice = unitPrice; this.categoryId = categoryId;
        }
        String id() { return id; }
        String name() { return name; }
        Money unitPrice() { return unitPrice; }
        String categoryId() { return categoryId; }

        void setPrice(Money newPrice) {
            this.unitPrice = newPrice;
            notifyPriceChange(this, newPrice); // Observer: price-drop alerts
        }
        /** Called by Inventory when stock returns from 0 -> positive. */
        void announceBackInStock() { notifyBackInStock(this); }
    }

    /* ======================= Composite: Catalog ========================= */
    /** Common type so categories and products are traversed uniformly. */
    static abstract class CatalogComponent {
        abstract String name();
        abstract List<Product> getProducts(); // recurse for categories, single for leaf
        void display(int depth) { System.out.println("  ".repeat(depth) + name()); }
    }
    static final class ProductLeaf extends CatalogComponent {
        private final Product product;
        ProductLeaf(Product product) { this.product = product; }
        @Override String name() { return product.name() + " (" + product.unitPrice() + ")"; }
        @Override List<Product> getProducts() { return List.of(product); }
    }
    static final class Category extends CatalogComponent {
        private final String name;
        private final List<CatalogComponent> children = new ArrayList<>();
        Category(String name) { this.name = name; }
        Category add(CatalogComponent c) { children.add(c); return this; }
        @Override String name() { return "[" + name + "]"; }
        @Override List<Product> getProducts() {
            List<Product> all = new ArrayList<>();
            for (CatalogComponent c : children) all.addAll(c.getProducts()); // uniform recursion
            return all;
        }
        @Override void display(int depth) {
            super.display(depth);
            for (CatalogComponent c : children) c.display(depth + 1);
        }
    }

    /* ======================= Cart & CartItem ============================ */
    static final class CartItem {
        private final Product product;
        private int quantity;
        CartItem(Product product, int quantity) { this.product = product; this.quantity = quantity; }
        Product product() { return product; }
        int quantity() { return quantity; }
        void setQuantity(int q) { this.quantity = q; }
        Money lineSubtotal() { return product.unitPrice().multiply(quantity); }
    }

    static final class Cart {
        private final String userId;
        // LinkedHashMap to keep insertion order stable for deterministic pricing/demo.
        private final Map<String, CartItem> items = new LinkedHashMap<>();
        private Coupon coupon; // single cart-level coupon (extension: List<Coupon>)
        private final String currency;

        Cart(String userId, String currency) { this.userId = userId; this.currency = currency; }
        String userId() { return userId; }
        String currency() { return currency; }
        Collection<CartItem> items() { return items.values(); }
        boolean isEmpty() { return items.isEmpty(); }
        Coupon coupon() { return coupon; }

        void addItem(Product p, int qty) {
            if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
            items.merge(p.id(), new CartItem(p, qty),
                    (existing, add) -> { existing.setQuantity(existing.quantity() + add.quantity()); return existing; });
        }
        void updateQuantity(String productId, int qty) {
            if (qty < 0) throw new IllegalArgumentException("qty must be >= 0");
            if (qty == 0) { items.remove(productId); return; }
            CartItem it = items.get(productId);
            if (it == null) throw new NoSuchElementException("Not in cart: " + productId);
            it.setQuantity(qty);
        }
        void removeItem(String productId) { items.remove(productId); }
        Product get(String productId) {
            CartItem it = items.get(productId);
            return it == null ? null : it.product();
        }
        void applyCoupon(Coupon c) { this.coupon = c; }
        void clearCoupon() { this.coupon = null; }
        void clear() { items.clear(); coupon = null; }

        Money rawSubtotal() {
            Money sum = new Money(0, currency);
            for (CartItem it : items.values()) sum = sum.add(it.lineSubtotal());
            return sum;
        }
        PriceBreakup priceBreakup(PricingEngine engine) { return engine.compute(this); }
    }

    /* ======================= Wishlist =================================== */
    static final class Wishlist {
        private final Set<String> productIds = new LinkedHashSet<>();
        private final Map<String, Product> products = new HashMap<>();
        void add(Product p) { if (productIds.add(p.id())) products.put(p.id(), p); }
        void remove(String productId) { productIds.remove(productId); products.remove(productId); }
        boolean contains(String productId) { return productIds.contains(productId); }
        Collection<Product> all() { return products.values(); }
        /** Atomic-ish move: remove from wishlist, add to cart. */
        void moveToCart(String productId, Cart cart, int qty) {
            Product p = products.get(productId);
            if (p == null) throw new NoSuchElementException("Not in wishlist: " + productId);
            cart.addItem(p, qty);
            remove(productId);
        }
    }

    /* ======================= Strategy: Discounts ======================== */
    /** A discount returns the amount to SUBTRACT from the running total. */
    interface DiscountStrategy {
        boolean isApplicable(Cart cart);
        Money discountAmount(Cart cart);
        String describe();
    }
    static final class PercentageOff implements DiscountStrategy {
        private final int pct; private final Money minCart;
        PercentageOff(int pct, Money minCart) { this.pct = pct; this.minCart = minCart; }
        @Override public boolean isApplicable(Cart c) { return c.rawSubtotal().greaterOrEqual(minCart); }
        @Override public Money discountAmount(Cart c) {
            return isApplicable(c) ? c.rawSubtotal().percentage(pct) : new Money(0, c.currency());
        }
        @Override public String describe() { return pct + "% off (min " + minCart + ")"; }
    }
    static final class FlatOff implements DiscountStrategy {
        private final Money flat; private final Money minCart;
        FlatOff(Money flat, Money minCart) { this.flat = flat; this.minCart = minCart; }
        @Override public boolean isApplicable(Cart c) { return c.rawSubtotal().greaterOrEqual(minCart); }
        @Override public Money discountAmount(Cart c) {
            return isApplicable(c) ? flat : new Money(0, c.currency());
        }
        @Override public String describe() { return "flat " + flat + " off (min " + minCart + ")"; }
    }
    /** Buy X get Y free on a specific product: discount = (eligible free units) * unitPrice. */
    static final class BuyXGetYFree implements DiscountStrategy {
        private final String productId; private final int x; private final int y;
        BuyXGetYFree(String productId, int x, int y) { this.productId = productId; this.x = x; this.y = y; }
        @Override public boolean isApplicable(Cart c) {
            Product p = c.get(productId);
            return p != null && qtyOf(c) >= x;
        }
        private int qtyOf(Cart c) {
            for (CartItem it : c.items()) if (it.product().id().equals(productId)) return it.quantity();
            return 0;
        }
        @Override public Money discountAmount(Cart c) {
            if (!isApplicable(c)) return new Money(0, c.currency());
            Product p = c.get(productId);
            int qty = qtyOf(c);
            int freeUnits = (qty / (x + y)) * y; // for every (x+y) bought, y are free
            return p.unitPrice().multiply(freeUnits);
        }
        @Override public String describe() { return "Buy " + x + " get " + y + " free on " + productId; }
    }
    /** Free shipping marker — consumed by ShippingStage (zeroes shipping). */
    static final class FreeShipping implements DiscountStrategy {
        private final Money minCart;
        FreeShipping(Money minCart) { this.minCart = minCart; }
        @Override public boolean isApplicable(Cart c) { return c.rawSubtotal().greaterOrEqual(minCart); }
        @Override public Money discountAmount(Cart c) { return new Money(0, c.currency()); } // handled in shipping
        @Override public String describe() { return "free shipping (min " + minCart + ")"; }
    }

    /** Coupon wraps a code + a discount strategy + optional expiry. */
    static final class Coupon {
        private final String code;
        private final DiscountStrategy strategy;
        private final long expiresAtEpochMs;
        Coupon(String code, DiscountStrategy strategy, long expiresAtEpochMs) {
            this.code = code; this.strategy = strategy; this.expiresAtEpochMs = expiresAtEpochMs;
        }
        String code() { return code; }
        DiscountStrategy strategy() { return strategy; }
        boolean isValid(Cart cart) {
            return System.currentTimeMillis() <= expiresAtEpochMs && strategy.isApplicable(cart);
        }
        boolean grantsFreeShipping() { return strategy instanceof FreeShipping; }
    }

    /* ======================= Pricing pipeline (Strategy) ================ */
    /** Mutable context threaded through ordered pricing stages. */
    static final class PriceContext {
        final Cart cart;
        Money subtotal;
        Money itemDiscount;
        Money couponDiscount;
        Money shipping;
        Money tax;
        boolean freeShipping = false;
        PriceContext(Cart cart) {
            this.cart = cart;
            Money z = new Money(0, cart.currency());
            this.subtotal = z; this.itemDiscount = z; this.couponDiscount = z;
            this.shipping = z; this.tax = z;
        }
        Money grandTotal() {
            return subtotal.subtract(itemDiscount).subtract(couponDiscount)
                    .add(shipping).add(tax).clampNonNegative();
        }
    }
    interface PricingStage { void apply(PriceContext ctx); }

    static final class SubtotalStage implements PricingStage {
        @Override public void apply(PriceContext ctx) { ctx.subtotal = ctx.cart.rawSubtotal(); }
    }
    /** Item-level auto discounts (e.g., BuyXGetY) — independent of cart coupon. */
    static final class ItemDiscountStage implements PricingStage {
        private final List<DiscountStrategy> itemDiscounts;
        ItemDiscountStage(List<DiscountStrategy> itemDiscounts) { this.itemDiscounts = itemDiscounts; }
        @Override public void apply(PriceContext ctx) {
            Money total = new Money(0, ctx.cart.currency());
            for (DiscountStrategy d : itemDiscounts)
                if (d.isApplicable(ctx.cart)) total = total.add(d.discountAmount(ctx.cart));
            ctx.itemDiscount = total;
        }
    }
    /** Cart-level coupon stage. */
    static final class CouponStage implements PricingStage {
        @Override public void apply(PriceContext ctx) {
            Coupon c = ctx.cart.coupon();
            if (c == null || !c.isValid(ctx.cart)) return;
            if (c.grantsFreeShipping()) ctx.freeShipping = true;
            ctx.couponDiscount = c.strategy().discountAmount(ctx.cart);
        }
    }
    /** Flat shipping unless free-shipping flag is set or order over threshold. */
    static final class ShippingStage implements PricingStage {
        private final Money flatShipping; private final Money freeAbove;
        ShippingStage(Money flatShipping, Money freeAbove) { this.flatShipping = flatShipping; this.freeAbove = freeAbove; }
        @Override public void apply(PriceContext ctx) {
            Money netAfterDiscounts = ctx.subtotal.subtract(ctx.itemDiscount).subtract(ctx.couponDiscount);
            if (ctx.freeShipping || netAfterDiscounts.greaterOrEqual(freeAbove)) {
                ctx.shipping = new Money(0, ctx.cart.currency());
            } else {
                ctx.shipping = flatShipping;
            }
        }
    }
    /** Tax computed on discounted goods value (not shipping), simple % model. */
    static final class TaxStage implements PricingStage {
        private final int taxPct;
        TaxStage(int taxPct) { this.taxPct = taxPct; }
        @Override public void apply(PriceContext ctx) {
            Money taxable = ctx.subtotal.subtract(ctx.itemDiscount).subtract(ctx.couponDiscount).clampNonNegative();
            ctx.tax = taxable.percentage(taxPct);
        }
    }

    /** Immutable price breakdown produced by the engine. */
    static final class PriceBreakup {
        final Money subtotal, itemDiscount, couponDiscount, shipping, tax, grandTotal;
        PriceBreakup(PriceContext ctx) {
            this.subtotal = ctx.subtotal; this.itemDiscount = ctx.itemDiscount;
            this.couponDiscount = ctx.couponDiscount; this.shipping = ctx.shipping;
            this.tax = ctx.tax; this.grandTotal = ctx.grandTotal();
        }
        @Override public String toString() {
            return "Subtotal=" + subtotal + ", ItemDisc=" + itemDiscount + ", CouponDisc=" + couponDiscount
                    + ", Shipping=" + shipping + ", Tax=" + tax + ", TOTAL=" + grandTotal;
        }
    }

    /** Pricing engine = ordered pipeline of stages. Add stages without editing it. */
    static final class PricingEngine {
        private final List<PricingStage> stages;
        PricingEngine(List<PricingStage> stages) { this.stages = List.copyOf(stages); }
        PriceBreakup compute(Cart cart) {
            PriceContext ctx = new PriceContext(cart);
            for (PricingStage s : stages) s.apply(ctx);
            return new PriceBreakup(ctx);
        }
    }

    /* ======================= Inventory (thread-safe) =================== */
    static final class ReservationToken {
        final String id; final String productId; final int qty; final long expiresAt;
        private boolean active = true;
        ReservationToken(String id, String productId, int qty, long expiresAt) {
            this.id = id; this.productId = productId; this.qty = qty; this.expiresAt = expiresAt;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /** Per-product stock guarded by its own lock => fine-grained, never oversells. */
    static final class Inventory {
        private static final class Stock {
            int available;
            int reserved;
            final ReentrantLock lock = new ReentrantLock();
            final Map<String, ReservationToken> reservations = new HashMap<>();
            Stock(int available) { this.available = available; }
        }
        private final Map<String, Stock> stockByProduct = new ConcurrentHashMap<>();
        private final Map<String, Product> productById = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        void register(Product p, int qty) {
            stockByProduct.put(p.id(), new Stock(qty));
            productById.put(p.id(), p);
        }
        int available(String productId) {
            Stock s = stockByProduct.get(productId);
            if (s == null) return 0;
            s.lock.lock();
            try { return s.available; } finally { s.lock.unlock(); }
        }

        /** Atomic check-and-decrement under the product's lock. Throws if insufficient. */
        ReservationToken reserve(String productId, int qty, long ttlMillis) {
            Stock s = stockByProduct.get(productId);
            if (s == null) throw new NoSuchElementException("Unknown product: " + productId);
            s.lock.lock();
            try {
                releaseExpiredLocked(s);
                if (s.available < qty)
                    throw new OutOfStockException("Insufficient stock for " + productId
                            + " (need " + qty + ", have " + s.available + ")");
                s.available -= qty;
                s.reserved += qty;
                ReservationToken token = new ReservationToken(
                        "RSV-" + seq.incrementAndGet(), productId, qty, System.currentTimeMillis() + ttlMillis);
                s.reservations.put(token.id, token);
                return token;
            } finally { s.lock.unlock(); }
        }
        /** Confirm: reserved units are consumed permanently. */
        void confirm(ReservationToken token) {
            Stock s = stockByProduct.get(token.productId);
            s.lock.lock();
            try {
                ReservationToken t = s.reservations.remove(token.id);
                if (t == null || !t.active) return; // idempotent
                t.active = false;
                s.reserved -= t.qty; // consumed (available already decremented at reserve)
            } finally { s.lock.unlock(); }
        }
        /** Release: reserved units return to available (rollback). */
        void release(ReservationToken token) {
            Stock s = stockByProduct.get(token.productId);
            s.lock.lock();
            try {
                ReservationToken t = s.reservations.remove(token.id);
                if (t == null || !t.active) return;
                t.active = false;
                boolean wasZero = s.available == 0;
                s.reserved -= t.qty;
                s.available += t.qty;
                if (wasZero && s.available > 0) {
                    Product p = productById.get(token.productId);
                    if (p != null) p.announceBackInStock(); // Observer event
                }
            } finally { s.lock.unlock(); }
        }
        /** Sweep all products for expired reservations (call from a scheduler). */
        void releaseExpired() {
            for (Stock s : stockByProduct.values()) {
                s.lock.lock();
                try { releaseExpiredLocked(s); } finally { s.lock.unlock(); }
            }
        }
        private void releaseExpiredLocked(Stock s) {
            Iterator<ReservationToken> it = s.reservations.values().iterator();
            while (it.hasNext()) {
                ReservationToken t = it.next();
                if (t.active && t.isExpired()) {
                    t.active = false; s.reserved -= t.qty; s.available += t.qty; it.remove();
                }
            }
        }
    }

    /* ======================= Strategy: Payment ========================= */
    static final class PaymentResult {
        final boolean success; final String reference; final String message;
        PaymentResult(boolean success, String reference, String message) {
            this.success = success; this.reference = reference; this.message = message;
        }
        static PaymentResult ok(String ref) { return new PaymentResult(true, ref, "approved"); }
        static PaymentResult fail(String msg) { return new PaymentResult(false, null, msg); }
    }
    interface PaymentStrategy {
        PaymentResult pay(Money amount);
        PaymentResult refund(Money amount);
        String name();
    }
    /** Mock gateway shared by card-like methods. */
    static final class PaymentGateway {
        PaymentResult charge(String method, Money amount) {
            if (amount.isNegative() || amount.minorUnits() == 0)
                return PaymentResult.fail("invalid amount");
            return PaymentResult.ok(method + "-TXN-" + UUID.randomUUID().toString().substring(0, 8));
        }
    }
    static final class CreditCardPayment implements PaymentStrategy {
        private final PaymentGateway gw; private final String maskedCard;
        CreditCardPayment(PaymentGateway gw, String maskedCard) { this.gw = gw; this.maskedCard = maskedCard; }
        @Override public PaymentResult pay(Money a) { return gw.charge("CARD(" + maskedCard + ")", a); }
        @Override public PaymentResult refund(Money a) { return PaymentResult.ok("CARD-REFUND"); }
        @Override public String name() { return "CreditCard"; }
    }
    static final class WalletPayment implements PaymentStrategy {
        private Money balance;
        WalletPayment(Money balance) { this.balance = balance; }
        @Override public PaymentResult pay(Money a) {
            if (!balance.greaterOrEqual(a)) return PaymentResult.fail("insufficient wallet balance");
            balance = balance.subtract(a);
            return PaymentResult.ok("WALLET-" + UUID.randomUUID().toString().substring(0, 8));
        }
        @Override public PaymentResult refund(Money a) { balance = balance.add(a); return PaymentResult.ok("WALLET-REFUND"); }
        @Override public String name() { return "Wallet"; }
    }
    static final class UpiPayment implements PaymentStrategy {
        private final PaymentGateway gw; private final String vpa;
        UpiPayment(PaymentGateway gw, String vpa) { this.gw = gw; this.vpa = vpa; }
        @Override public PaymentResult pay(Money a) { return gw.charge("UPI(" + vpa + ")", a); }
        @Override public PaymentResult refund(Money a) { return PaymentResult.ok("UPI-REFUND"); }
        @Override public String name() { return "UPI"; }
    }
    static final class CashOnDelivery implements PaymentStrategy {
        @Override public PaymentResult pay(Money a) { return PaymentResult.ok("COD-PENDING"); } // collected later
        @Override public PaymentResult refund(Money a) { return PaymentResult.ok("COD-NOREFUND"); }
        @Override public String name() { return "CashOnDelivery"; }
    }
    /** Extension demo: split payment across methods (Strategy composition). */
    static final class SplitPayment implements PaymentStrategy {
        private final List<Map.Entry<PaymentStrategy, Money>> legs;
        SplitPayment(List<Map.Entry<PaymentStrategy, Money>> legs) { this.legs = legs; }
        @Override public PaymentResult pay(Money total) {
            List<PaymentStrategy> charged = new ArrayList<>();
            StringBuilder refs = new StringBuilder();
            for (Map.Entry<PaymentStrategy, Money> leg : legs) {
                PaymentResult r = leg.getKey().pay(leg.getValue());
                if (!r.success) { // rollback already-charged legs
                    for (PaymentStrategy done : charged) done.refund(Money.ZERO);
                    return PaymentResult.fail("split leg failed: " + r.message);
                }
                charged.add(leg.getKey());
                refs.append(r.reference).append(';');
            }
            return PaymentResult.ok(refs.toString());
        }
        @Override public PaymentResult refund(Money a) {
            for (Map.Entry<PaymentStrategy, Money> leg : legs) leg.getKey().refund(leg.getValue());
            return PaymentResult.ok("SPLIT-REFUND");
        }
        @Override public String name() { return "Split"; }
    }

    /* ======================= State: Order lifecycle ===================== */
    interface OrderState {
        default void pay(Order o)     { throw illegal("pay"); }
        default void ship(Order o)    { throw illegal("ship"); }
        default void deliver(Order o) { throw illegal("deliver"); }
        default void cancel(Order o)  { throw illegal("cancel"); }
        default void refund(Order o)  { throw illegal("refund"); }
        String name();
        default IllegalStateException illegal(String op) {
            return new IllegalStateException("Cannot " + op + " in state " + name());
        }
    }
    static final class CreatedState implements OrderState {
        @Override public void pay(Order o) { o.setState(new PaidState()); }
        @Override public void cancel(Order o) { o.releaseReservations(); o.setState(new CancelledState()); }
        @Override public String name() { return "CREATED"; }
    }
    static final class PaidState implements OrderState {
        @Override public void ship(Order o) { o.setState(new ShippedState()); }
        @Override public void cancel(Order o) { o.refundPayment(); o.releaseReservations(); o.setState(new CancelledState()); }
        @Override public void refund(Order o) { o.refundPayment(); o.setState(new RefundedState()); }
        @Override public String name() { return "PAID"; }
    }
    static final class ShippedState implements OrderState {
        @Override public void deliver(Order o) { o.setState(new DeliveredState()); }
        @Override public String name() { return "SHIPPED"; }
    }
    static final class DeliveredState implements OrderState {
        @Override public void refund(Order o) { o.refundPayment(); o.setState(new RefundedState()); } // returns
        @Override public String name() { return "DELIVERED"; }
    }
    static final class CancelledState implements OrderState {
        @Override public String name() { return "CANCELLED"; }
    }
    static final class RefundedState implements OrderState {
        @Override public String name() { return "REFUNDED"; }
    }

    /* ======================= Order (Builder + State) =================== */
    static final class Order {
        private final String id;
        private final String userId;
        private final List<CartItem> lines;
        private final PriceBreakup price;
        private final PaymentStrategy payment;
        private final List<ReservationToken> reservations;
        private final Inventory inventory;
        private OrderState state;

        private Order(Builder b) {
            this.id = b.id; this.userId = b.userId; this.lines = b.lines; this.price = b.price;
            this.payment = b.payment; this.reservations = b.reservations; this.inventory = b.inventory;
            this.state = new CreatedState();
        }
        String id() { return id; }
        PriceBreakup price() { return price; }
        String status() { return state.name(); }
        void setState(OrderState s) { this.state = s; }

        synchronized void pay()     { state.pay(this); }
        synchronized void ship()    { state.ship(this); }
        synchronized void deliver() { state.deliver(this); }
        synchronized void cancel()  { state.cancel(this); }
        synchronized void refund()  { state.refund(this); }

        void releaseReservations() { for (ReservationToken t : reservations) inventory.release(t); }
        void refundPayment() { payment.refund(price.grandTotal()); }

        static final class Builder {
            String id, userId;
            List<CartItem> lines;
            PriceBreakup price;
            PaymentStrategy payment;
            List<ReservationToken> reservations;
            Inventory inventory;
            Builder id(String v) { this.id = v; return this; }
            Builder userId(String v) { this.userId = v; return this; }
            Builder lines(List<CartItem> v) { this.lines = v; return this; }
            Builder price(PriceBreakup v) { this.price = v; return this; }
            Builder payment(PaymentStrategy v) { this.payment = v; return this; }
            Builder reservations(List<ReservationToken> v) { this.reservations = v; return this; }
            Builder inventory(Inventory v) { this.inventory = v; return this; }
            Order build() { return new Order(this); }
        }
    }

    /* ======================= Exceptions ================================ */
    static final class OutOfStockException extends RuntimeException {
        OutOfStockException(String m) { super(m); }
    }
    static final class PaymentFailedException extends RuntimeException {
        PaymentFailedException(String m) { super(m); }
    }

    /* ======================= Facade: CheckoutService =================== */
    static final class CheckoutService {
        private final Inventory inventory;
        private final PricingEngine pricingEngine;
        private final long reservationTtlMs;
        private final AtomicLong orderSeq = new AtomicLong();

        CheckoutService(Inventory inventory, PricingEngine pricingEngine, long reservationTtlMs) {
            this.inventory = inventory; this.pricingEngine = pricingEngine; this.reservationTtlMs = reservationTtlMs;
        }

        Order checkout(Cart cart, PaymentStrategy payment) {
            if (cart.isEmpty()) throw new IllegalStateException("Cannot checkout an empty cart");

            List<ReservationToken> tokens = new ArrayList<>();
            try {
                // 1) Reserve every line atomically; on any failure, rollback below.
                for (CartItem it : cart.items())
                    tokens.add(inventory.reserve(it.product().id(), it.quantity(), reservationTtlMs));

                // 2) Price the cart through the pipeline.
                PriceBreakup price = pricingEngine.compute(cart);

                // 3) Charge payment.
                PaymentResult pr = payment.pay(price.grandTotal());
                if (!pr.success) throw new PaymentFailedException(pr.message);

                // 4) Confirm reservations (consume stock permanently).
                for (ReservationToken t : tokens) inventory.confirm(t);

                // 5) Build the order and move CREATED -> PAID.
                Order order = new Order.Builder()
                        .id("ORD-" + orderSeq.incrementAndGet())
                        .userId(cart.userId())
                        .lines(new ArrayList<>(cart.items()))
                        .price(price)
                        .payment(payment)
                        .reservations(tokens)
                        .inventory(inventory)
                        .build();
                order.pay();
                cart.clear();
                return order;
            } catch (RuntimeException ex) {
                for (ReservationToken t : tokens) inventory.release(t); // rollback reservations
                throw ex;
            }
        }
    }

    /* ======================= User ====================================== */
    static final class User {
        final String id; final String name; final Cart cart; final Wishlist wishlist;
        User(String id, String name, String currency) {
            this.id = id; this.name = name; this.cart = new Cart(id, currency); this.wishlist = new Wishlist();
        }
    }

    /* ======================= DEMO (main) =============================== */
    public static void main(String[] args) {
        final String CCY = "INR";

        // ---- Catalog (Composite): Electronics > Phones/Accessories ----
        Product phone = new Product("P1", "Smartphone", Money.of(20000, 0, CCY), "phones");
        Product cover = new Product("P2", "Phone Cover", Money.of(500, 0, CCY), "accessories");
        Product cable = new Product("P3", "USB-C Cable", Money.of(300, 0, CCY), "accessories");

        Category electronics = new Category("Electronics");
        Category phones = new Category("Phones");
        Category accessories = new Category("Accessories");
        phones.add(new ProductLeaf(phone));
        accessories.add(new ProductLeaf(cover)).add(new ProductLeaf(cable));
        electronics.add(phones).add(accessories);

        System.out.println("=== Catalog tree ===");
        electronics.display(0);
        System.out.println("All products under Electronics: " +
                electronics.getProducts().stream().map(Product::name).toList());

        // ---- Observer: subscribe to stock/price events on phone ----
        phone.subscribe(new StockObserver() {
            @Override public void onBackInStock(Product p) {
                System.out.println("[NOTIFY] Back in stock: " + p.name());
            }
            @Override public void onPriceChange(Product p, Money price) {
                System.out.println("[NOTIFY] Price changed for " + p.name() + " -> " + price);
            }
        });

        // ---- Inventory (thread-safe) ----
        Inventory inventory = new Inventory();
        inventory.register(phone, 2);
        inventory.register(cover, 10);
        inventory.register(cable, 5);

        // ---- Pricing engine (Strategy pipeline) ----
        List<DiscountStrategy> itemDiscounts = List.of(new BuyXGetYFree("P3", 2, 1)); // buy 2 cables get 1 free
        PricingEngine engine = new PricingEngine(List.of(
                new SubtotalStage(),
                new ItemDiscountStage(itemDiscounts),
                new CouponStage(),
                new ShippingStage(Money.of(99, 0, CCY), Money.of(25000, 0, CCY)), // free above 25000
                new TaxStage(18) // 18% GST
        ));

        CheckoutService checkout = new CheckoutService(inventory, engine, 5 * 60_000L);

        // ---- User builds a cart ----
        User alice = new User("U1", "Alice", CCY);
        alice.cart.addItem(phone, 1);
        alice.cart.addItem(cover, 2);
        alice.cart.addItem(cable, 3); // 3 cables -> 1 free via BuyXGetY

        // Wishlist: add then move to cart
        alice.wishlist.add(cable);
        System.out.println("\nWishlist has cable? " + alice.wishlist.contains("P3"));

        // Apply a 10% coupon (min cart 5000)
        Coupon tenPct = new Coupon("SAVE10",
                new PercentageOff(10, Money.of(5000, 0, CCY)),
                System.currentTimeMillis() + 3_600_000L);
        alice.cart.applyCoupon(tenPct);

        System.out.println("\n=== Price breakup (before checkout) ===");
        System.out.println(alice.cart.priceBreakup(engine));

        // ---- Checkout with wallet payment ----
        System.out.println("\n=== Checkout (Wallet) ===");
        PaymentStrategy wallet = new WalletPayment(Money.of(100000, 0, CCY));
        Order order = checkout.checkout(alice.cart, wallet);
        System.out.println("Order " + order.id() + " status=" + order.status());
        System.out.println("Order price: " + order.price());
        System.out.println("Phone available after checkout: " + inventory.available("P1"));

        // ---- Order state machine transitions ----
        System.out.println("\n=== Order lifecycle (State) ===");
        order.ship();    System.out.println("After ship:    " + order.status());
        order.deliver(); System.out.println("After deliver: " + order.status());
        try { order.cancel(); } catch (IllegalStateException e) {
            System.out.println("Illegal transition handled: " + e.getMessage());
        }
        order.refund();  System.out.println("After refund (return): " + order.status());

        // ---- Concurrency: two buyers race for the last phone ----
        System.out.println("\n=== Concurrency: race for last phone (stock=1 left) ===");
        // After Alice's order, phone stock = 1. Two threads try to buy 1 each.
        Runnable buyPhone = () -> {
            User u = new User("X" + Thread.currentThread().getId(), "racer", CCY);
            u.cart.addItem(phone, 1);
            try {
                Order o = checkout.checkout(u.cart, new WalletPayment(Money.of(50000, 0, CCY)));
                System.out.println(Thread.currentThread().getName() + " SUCCEEDED -> " + o.id());
            } catch (OutOfStockException e) {
                System.out.println(Thread.currentThread().getName() + " FAILED -> " + e.getMessage());
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(buyPhone); pool.submit(buyPhone);
        pool.shutdown();
        try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        System.out.println("Phone available now: " + inventory.available("P1") + " (never oversold)");

        // ---- Payment failure path: reservations released ----
        System.out.println("\n=== Payment failure rolls back reservations ===");
        User bob = new User("U2", "Bob", CCY);
        bob.cart.addItem(cover, 1);
        int beforeCovers = inventory.available("P2");
        try {
            checkout.checkout(bob.cart, new WalletPayment(Money.ZERO)); // insufficient -> fail
        } catch (PaymentFailedException e) {
            System.out.println("Payment failed: " + e.getMessage());
        }
        System.out.println("Covers available before=" + beforeCovers
                + ", after failed checkout=" + inventory.available("P2") + " (reservation released)");

        // ---- Observer: trigger price change ----
        System.out.println("\n=== Price change event ===");
        phone.setPrice(Money.of(19000, 0, CCY));
    }
}

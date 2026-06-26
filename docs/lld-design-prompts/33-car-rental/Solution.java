/* =====================================================================================
 * Car Rental System — Single-file LLD review / revision artifact.
 *
 * REVIEW ARTIFACT ONLY: read-and-revise. Not meant to be compiled/run in CI; a `main`
 * is included purely to illustrate the key scenarios.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING:
 *   Functional:
 *     1. One company with branches, or a peer-to-peer marketplace?
 *     2. Search by branch/location + vehicle type/class + date-time range?
 *     3. Rent a SPECIFIC physical car, or a CATEGORY (assign car at pickup)?
 *     4. One-way rentals (pickup branch != return branch)? Drop-off fee?
 *     5. Reservation lifecycle: CREATED->CONFIRMED->IN_PROGRESS->COMPLETED, plus
 *        CANCELLED and NO_SHOW?
 *     6. Add-ons (insurance tiers, GPS, child seat); per-day or flat?
 *     7. Pricing models: flat daily, weekly discount, seasonal/surge, membership, tax?
 *     8. Cancellation policy (free until cutoff, then penalty)?
 *     9. Late return: per-hour / per-day after a grace period?
 *    10. Payments: record via gateway interface? Refunds on cancel?
 *   Non-functional:
 *    11. Concurrency: single JVM (in-memory locks) or distributed (DB constraint)?
 *    12. Scale: fleet size, branches, search QPS?
 *    13. Strong consistency on bookings (never double-book)?
 *    14. Persistence: in-memory for exercise vs relational DB?
 *    15. Date granularity: calendar days vs precise timestamps; time zones?
 *   Scope:
 *    16. IN: search, availability, lifecycle, pricing strategy, add-ons, cancel,
 *        late fee, payment recording, one-way rentals.
 *    17. OUT: auth, real payment gateway, notifications transport, maps, fraud,
 *        accounting ledger, persistence details, REST/gRPC layer.
 *
 * DECISIONS COMMITTED FOR THIS ARTIFACT:
 *   Single company + branches; specific-vehicle rental; half-open [start,end) ranges;
 *   one-way allowed; Money as integer minor units; double-booking prevented by an
 *   atomic check-and-reserve under a per-vehicle lock with release-on-failure
 *   compensation. Behavior is pluggable: Strategy (pricing/policies), Decorator
 *   (discounts), State (lifecycle), Factory (vehicles), Observer (notifications),
 *   Facade (ReservationService).
 * ===================================================================================== */

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/* =========================== Value objects ============================================ */

/** Money as integer minor units (e.g. cents) to avoid floating-point drift. Immutable. */
final class Money {
    final long minorUnits;
    final String currency;

    Money(long minorUnits, String currency) {
        this.minorUnits = minorUnits;
        this.currency = currency;
    }

    static Money of(double major, String currency) {
        return new Money(Math.round(major * 100), currency);
    }

    static Money zero(String currency) { return new Money(0, currency); }

    Money add(Money o) { check(o); return new Money(minorUnits + o.minorUnits, currency); }

    Money subtract(Money o) { check(o); return new Money(minorUnits - o.minorUnits, currency); }

    /** Scale by a factor (surge/discount); round half-up exactly once at this boundary. */
    Money scale(double factor) {
        return new Money(Math.round(minorUnits * factor), currency);
    }

    boolean isNegative() { return minorUnits < 0; }

    private void check(Money o) {
        if (!currency.equals(o.currency))
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + o.currency);
    }

    @Override public String toString() {
        return String.format("%s %.2f", currency, minorUnits / 100.0);
    }
}

/** Half-open interval [start, end). Two ranges overlap iff start < other.end && other.start < end. */
final class DateRange {
    final LocalDateTime start;
    final LocalDateTime end;

    DateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) throw new IllegalArgumentException("null bound");
        if (!start.isBefore(end)) throw new IllegalArgumentException("start must be before end");
        this.start = start;
        this.end = end;
    }

    boolean overlaps(DateRange o) {
        return start.isBefore(o.end) && o.start.isBefore(end);
    }

    /** Billable days; partial day counts as a full day (ceil). Minimum 1. */
    long days() {
        long hours = ChronoUnit.HOURS.between(start, end);
        long d = (hours + 23) / 24;
        return Math.max(1, d);
    }

    boolean containsWeekend() {
        LocalDateTime cur = start;
        while (cur.isBefore(end)) {
            int dow = cur.getDayOfWeek().getValue(); // 6=Sat, 7=Sun
            if (dow == 6 || dow == 7) return true;
            cur = cur.plusDays(1);
        }
        return false;
    }

    @Override public String toString() { return "[" + start + " -> " + end + ")"; }
}

enum MembershipTier {
    NONE(0.0), SILVER(0.05), GOLD(0.10), PLATINUM(0.15);
    final double discount;
    MembershipTier(double d) { this.discount = d; }
}

enum VehicleType {
    ECONOMY(30), SEDAN(45), SUV(65), LUXURY(120), VAN(80);
    private final double baseRateMajor;
    VehicleType(double r) { this.baseRateMajor = r; }
    Money baseDailyRate(String currency) { return Money.of(baseRateMajor, currency); }
}

enum VehicleStatus { AVAILABLE, RENTED, MAINTENANCE }

enum ReservationStatus { CREATED, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW }

/* =========================== Entities ================================================= */

final class Branch {
    final String id;
    final String location;
    Branch(String id, String location) { this.id = id; this.location = location; }
    @Override public String toString() { return "Branch(" + id + "," + location + ")"; }
}

final class Customer {
    final String id;
    final String name;
    final MembershipTier tier;
    Customer(String id, String name, MembershipTier tier) {
        this.id = id; this.name = name; this.tier = tier;
    }
    @Override public String toString() { return "Customer(" + name + "," + tier + ")"; }
}

/** Per-day or flat add-on (insurance, GPS, child seat). */
final class AddOn {
    final String name;
    final Money price;
    final boolean perDay;
    AddOn(String name, Money price, boolean perDay) {
        this.name = name; this.price = price; this.perDay = perDay;
    }
    Money costFor(DateRange range) {
        return perDay ? price.scale(range.days()) : price;
    }
}

/**
 * A specific physical vehicle. SRP: it owns its own availability via a sorted set of
 * booked intervals. The lock guards the check-and-claim so a Vehicle can't be
 * double-booked. (In a distributed system this responsibility moves to the DB.)
 */
final class Vehicle {
    final String id;
    final VehicleType type;
    final String registration;
    volatile VehicleStatus status;
    volatile Branch homeBranch;
    private final TreeSet<DateRange> booked =
            new TreeSet<>(Comparator.comparing(r -> r.start));
    final ReentrantLock lock = new ReentrantLock();

    Vehicle(String id, VehicleType type, String registration, Branch homeBranch) {
        this.id = id; this.type = type; this.registration = registration;
        this.homeBranch = homeBranch; this.status = VehicleStatus.AVAILABLE;
    }

    /** Must be called under {@link #lock}. */
    boolean isAvailable(DateRange when) {
        for (DateRange r : booked) {
            if (r.start.isAfter(when.end)) break; // sorted; no later range can overlap
            if (r.overlaps(when)) return false;
        }
        return true;
    }

    /** Must be called under {@link #lock}. */
    void claim(DateRange when) { booked.add(when); }

    void release(DateRange when) {
        lock.lock();
        try { booked.remove(when); } finally { lock.unlock(); }
    }

    @Override public String toString() {
        return "Vehicle(" + id + "," + type + "," + registration + "@" + homeBranch.id + ")";
    }
}

/** Itemized quote. total = base + addOns + fees - discount + tax. */
final class RentalQuote {
    final Money base, addOns, fees, discount, tax;
    RentalQuote(Money base, Money addOns, Money fees, Money discount, Money tax) {
        this.base = base; this.addOns = addOns; this.fees = fees;
        this.discount = discount; this.tax = tax;
    }
    Money total() {
        return base.add(addOns).add(fees).subtract(discount).add(tax);
    }
    @Override public String toString() {
        return "Quote{base=" + base + ", addOns=" + addOns + ", fees=" + fees
                + ", discount=" + discount + ", tax=" + tax + ", TOTAL=" + total() + "}";
    }
}

final class Payment {
    enum Status { SUCCESS, FAILED, REFUNDED }
    final String id;
    final Money amount;
    Status status;
    Payment(String id, Money amount, Status status) {
        this.id = id; this.amount = amount; this.status = status;
    }
    @Override public String toString() { return "Payment(" + amount + "," + status + ")"; }
}

/* =========================== State pattern (lifecycle) ================================ */

/**
 * STATE pattern. Each state localizes its legal transitions. Adding NO_SHOW or any new
 * state is a new class, not edits across Reservation's methods (Open/Closed).
 */
interface ReservationState {
    ReservationStatus status();
    default void confirm(Reservation r) { illegal("confirm"); }
    default void pickUp(Reservation r) { illegal("pickUp"); }
    default void returnVehicle(Reservation r, LocalDateTime at, ReservationService svc) { illegal("returnVehicle"); }
    default void cancel(Reservation r, ReservationService svc) { illegal("cancel"); }
    default void markNoShow(Reservation r) { illegal("markNoShow"); }
    default void illegal(String action) {
        throw new IllegalStateException("Action '" + action + "' not allowed in state " + status());
    }
}

final class CreatedState implements ReservationState {
    public ReservationStatus status() { return ReservationStatus.CREATED; }
    public void confirm(Reservation r) { r.setState(new ConfirmedState()); }
    public void cancel(Reservation r, ReservationService svc) { svc.doCancel(r); }
}

final class ConfirmedState implements ReservationState {
    public ReservationStatus status() { return ReservationStatus.CONFIRMED; }
    public void pickUp(Reservation r) {
        r.getVehicle().status = VehicleStatus.RENTED;
        r.setState(new InProgressState());
    }
    public void cancel(Reservation r, ReservationService svc) { svc.doCancel(r); }
    public void markNoShow(Reservation r) {
        svc_releaseSilently(r);
        r.setState(new NoShowState());
    }
    private void svc_releaseSilently(Reservation r) {
        r.getVehicle().release(r.getPeriod());
    }
}

final class InProgressState implements ReservationState {
    public ReservationStatus status() { return ReservationStatus.IN_PROGRESS; }
    public void returnVehicle(Reservation r, LocalDateTime at, ReservationService svc) {
        svc.doReturn(r, at);
        r.setState(new CompletedState());
    }
    // cancel intentionally NOT allowed once the rental is active (default throws).
}

final class CompletedState implements ReservationState {
    public ReservationStatus status() { return ReservationStatus.COMPLETED; }
}

final class CancelledState implements ReservationState {
    public ReservationStatus status() { return ReservationStatus.CANCELLED; }
}

final class NoShowState implements ReservationState {
    public ReservationStatus status() { return ReservationStatus.NO_SHOW; }
}

/* =========================== Reservation (aggregate root) ============================= */

final class Reservation {
    private final String id;
    private final Customer customer;
    private final Vehicle vehicle;
    private final Branch pickupBranch;
    private final Branch returnBranch;
    private final DateRange period;
    private final List<AddOn> addOns;
    private RentalQuote quote;
    private final List<Payment> payments = new ArrayList<>();
    private ReservationState state;
    private LocalDateTime actualReturn;

    Reservation(String id, Customer customer, Vehicle vehicle, Branch pickupBranch,
                Branch returnBranch, DateRange period, List<AddOn> addOns) {
        this.id = id; this.customer = customer; this.vehicle = vehicle;
        this.pickupBranch = pickupBranch; this.returnBranch = returnBranch;
        this.period = period; this.addOns = addOns;
        this.state = new CreatedState();
    }

    // --- State-delegating actions ---
    void confirm() { state.confirm(this); }
    void pickUp() { state.pickUp(this); }
    void returnVehicle(LocalDateTime at, ReservationService svc) { state.returnVehicle(this, at, svc); }
    void cancel(ReservationService svc) { state.cancel(this, svc); }
    void markNoShow() { state.markNoShow(this); }

    // --- accessors used by states/services ---
    void setState(ReservationState s) { this.state = s; }
    ReservationStatus statusName() { return state.status(); }
    String getId() { return id; }
    Customer getCustomer() { return customer; }
    Vehicle getVehicle() { return vehicle; }
    Branch getPickupBranch() { return pickupBranch; }
    Branch getReturnBranch() { return returnBranch; }
    DateRange getPeriod() { return period; }
    List<AddOn> getAddOns() { return addOns; }
    RentalQuote getQuote() { return quote; }
    void setQuote(RentalQuote q) { this.quote = q; }
    void addPayment(Payment p) { payments.add(p); }
    List<Payment> getPayments() { return payments; }
    Payment lastSuccessfulPayment() {
        for (int i = payments.size() - 1; i >= 0; i--)
            if (payments.get(i).status == Payment.Status.SUCCESS) return payments.get(i);
        return null;
    }
    LocalDateTime getActualReturn() { return actualReturn; }
    void setActualReturn(LocalDateTime t) { this.actualReturn = t; }
    boolean isOneWay() { return !pickupBranch.id.equals(returnBranch.id); }

    @Override public String toString() {
        return "Reservation(" + id + ", " + customer.name + ", " + vehicle.id + ", "
                + statusName() + ", " + period + ", " + (quote == null ? "-" : quote.total()) + ")";
    }
}

/* =========================== Strategy: pricing ======================================== */

/** STRATEGY: swap the base pricing algorithm without touching booking orchestration. */
interface PricingStrategy {
    RentalQuote price(Reservation r, DropOffFeeStrategy dropOff, String currency);
}

/** Base daily rate * billable days; add-ons summed; tax applied; one-way drop-off fee. */
final class DailyPricingStrategy implements PricingStrategy {
    private final double taxRate;
    DailyPricingStrategy(double taxRate) { this.taxRate = taxRate; }

    public RentalQuote price(Reservation r, DropOffFeeStrategy dropOff, String currency) {
        DateRange period = r.getPeriod();
        Money base = r.getVehicle().type.baseDailyRate(currency).scale(period.days());
        Money addOns = Money.zero(currency);
        for (AddOn a : r.getAddOns()) addOns = addOns.add(a.costFor(period));
        Money fees = dropOff.dropOffFee(r.getPickupBranch(), r.getReturnBranch(), currency);
        Money taxable = base.add(addOns).add(fees);
        Money tax = taxable.scale(taxRate);
        return new RentalQuote(base, addOns, fees, Money.zero(currency), tax);
    }
}

/** Weekend surge: multiplies the base if the period touches a weekend. */
final class WeekendSurgePricingStrategy implements PricingStrategy {
    private final PricingStrategy delegate;
    private final double surge;
    WeekendSurgePricingStrategy(PricingStrategy delegate, double surge) {
        this.delegate = delegate; this.surge = surge;
    }
    public RentalQuote price(Reservation r, DropOffFeeStrategy dropOff, String currency) {
        RentalQuote q = delegate.price(r, dropOff, currency);
        if (!r.getPeriod().containsWeekend()) return q;
        Money newBase = q.base.scale(surge);
        // recompute tax proportionally on the surged base + existing addOns/fees
        Money taxable = newBase.add(q.addOns).add(q.fees);
        Money tax = taxable.subtract(q.base.add(q.addOns).add(q.fees)).add(q.tax); // keep simple
        return new RentalQuote(newBase, q.addOns, q.fees, q.discount, tax);
    }
}

/**
 * DECORATOR: wraps any PricingStrategy and subtracts a membership-tier discount on the
 * base. Stacks orthogonally with the base strategy without subclass explosion.
 */
final class MembershipDiscountPricing implements PricingStrategy {
    private final PricingStrategy delegate;
    MembershipDiscountPricing(PricingStrategy delegate) { this.delegate = delegate; }

    public RentalQuote price(Reservation r, DropOffFeeStrategy dropOff, String currency) {
        RentalQuote q = delegate.price(r, dropOff, currency);
        double rate = r.getCustomer().tier.discount;
        if (rate == 0.0) return q;
        Money discount = q.base.scale(rate);
        return new RentalQuote(q.base, q.addOns, q.fees, q.discount.add(discount), q.tax);
    }
}

/* =========================== Strategy: policies ======================================= */

interface DropOffFeeStrategy {
    Money dropOffFee(Branch pickup, Branch ret, String currency);
}

final class FlatDropOffFeeStrategy implements DropOffFeeStrategy {
    private final double feeMajor;
    FlatDropOffFeeStrategy(double feeMajor) { this.feeMajor = feeMajor; }
    public Money dropOffFee(Branch pickup, Branch ret, String currency) {
        return pickup.id.equals(ret.id) ? Money.zero(currency) : Money.of(feeMajor, currency);
    }
}

/** STRATEGY: cancellation refund. Free before cutoff, then a percentage penalty. */
interface CancellationPolicy {
    Money refundFor(Reservation r, LocalDateTime now);
}

final class StandardCancellationPolicy implements CancellationPolicy {
    private final long freeCutoffHours;
    private final double penaltyRate; // fraction of total retained when inside cutoff
    StandardCancellationPolicy(long freeCutoffHours, double penaltyRate) {
        this.freeCutoffHours = freeCutoffHours; this.penaltyRate = penaltyRate;
    }
    public Money refundFor(Reservation r, LocalDateTime now) {
        Payment p = r.lastSuccessfulPayment();
        if (p == null) return Money.zero("USD");
        long hoursToPickup = ChronoUnit.HOURS.between(now, r.getPeriod().start);
        if (hoursToPickup >= freeCutoffHours) return p.amount; // full refund
        Money penalty = p.amount.scale(penaltyRate);
        return p.amount.subtract(penalty);
    }
}

/** STRATEGY: late fee. Per-day overage at a multiple of the daily rate after a grace period. */
interface LateFeePolicy {
    Money lateFee(Reservation r, LocalDateTime actualReturn, String currency);
}

final class StandardLateFeePolicy implements LateFeePolicy {
    private final long graceHours;
    private final double dailyMultiplier;
    StandardLateFeePolicy(long graceHours, double dailyMultiplier) {
        this.graceHours = graceHours; this.dailyMultiplier = dailyMultiplier;
    }
    public Money lateFee(Reservation r, LocalDateTime actualReturn, String currency) {
        LocalDateTime due = r.getPeriod().end;
        if (!actualReturn.isAfter(due)) return Money.zero(currency);
        long overHours = ChronoUnit.HOURS.between(due, actualReturn);
        if (overHours <= graceHours) return Money.zero(currency);
        long overDays = Math.max(1, (overHours - graceHours + 23) / 24);
        Money daily = r.getVehicle().type.baseDailyRate(currency);
        return daily.scale(dailyMultiplier).scale(overDays);
    }
}

/* =========================== Factory: vehicle creation =============================== */

/** FACTORY: centralizes vehicle construction (type-specific defaults live here). */
final class VehicleFactory {
    static Vehicle create(VehicleType type, String registration, Branch homeBranch) {
        return new Vehicle(UUID.randomUUID().toString().substring(0, 8),
                type, registration, homeBranch);
    }
}

/* =========================== Payment processor ======================================= */

interface PaymentProcessor {
    Payment charge(Customer c, Money amount);
    Payment refund(Payment original, Money amount);
}

/** Mock gateway: always succeeds. Real impl would call an external PSP. */
final class MockPaymentProcessor implements PaymentProcessor {
    public Payment charge(Customer c, Money amount) {
        return new Payment(UUID.randomUUID().toString().substring(0, 8), amount, Payment.Status.SUCCESS);
    }
    public Payment refund(Payment original, Money amount) {
        original.status = Payment.Status.REFUNDED;
        return new Payment(UUID.randomUUID().toString().substring(0, 8), amount, Payment.Status.REFUNDED);
    }
}

/* =========================== Search criteria ========================================= */

final class SearchCriteria {
    final Branch branch;        // nullable = any
    final VehicleType type;     // nullable = any
    final DateRange period;
    SearchCriteria(Branch branch, VehicleType type, DateRange period) {
        this.branch = branch; this.type = type; this.period = period;
    }
}

/* =========================== Inventory service ======================================= */

/**
 * Holds branches & vehicles. Owns the ATOMIC check-and-reserve that prevents double
 * booking: per-vehicle lock + re-check availability inside the lock before claiming.
 * Different vehicles never block each other (striping = per-vehicle lock).
 */
final class InventoryService {
    private final Map<String, Vehicle> vehicles = new ConcurrentHashMap<>();
    private final Map<String, Branch> branches = new ConcurrentHashMap<>();

    void addBranch(Branch b) { branches.put(b.id, b); }
    void addVehicle(Vehicle v) { vehicles.put(v.id, v); }
    Vehicle vehicle(String id) { return vehicles.get(id); }
    Branch branch(String id) { return branches.get(id); }

    List<Vehicle> search(SearchCriteria c) {
        List<Vehicle> result = new ArrayList<>();
        for (Vehicle v : vehicles.values()) {
            if (v.status == VehicleStatus.MAINTENANCE) continue;
            if (c.type != null && v.type != c.type) continue;
            if (c.branch != null && !v.homeBranch.id.equals(c.branch.id)) continue;
            v.lock.lock();
            try {
                if (v.isAvailable(c.period)) result.add(v);
            } finally {
                v.lock.unlock();
            }
        }
        return result;
    }

    /** Atomic check-and-claim. Returns false if taken concurrently. */
    boolean tryReserve(Vehicle v, DateRange when) {
        v.lock.lock();
        try {
            if (!v.isAvailable(when)) return false;
            v.claim(when);
            return true;
        } finally {
            v.lock.unlock();
        }
    }

    void release(Vehicle v, DateRange when) { v.release(when); }
}

/* =========================== Observer: notifications ================================= */

/** OBSERVER: open-ended side-effects on lifecycle transitions. */
interface ReservationListener {
    default void onConfirmed(Reservation r) {}
    default void onCompleted(Reservation r) {}
    default void onCancelled(Reservation r) {}
}

final class LoggingListener implements ReservationListener {
    public void onConfirmed(Reservation r) { System.out.println("  [notify] CONFIRMED " + r.getId() + " total=" + r.getQuote().total()); }
    public void onCompleted(Reservation r) { System.out.println("  [notify] COMPLETED " + r.getId()); }
    public void onCancelled(Reservation r) { System.out.println("  [notify] CANCELLED " + r.getId()); }
}

/* =========================== Facade: reservation service ============================= */

/**
 * FACADE over inventory + pricing + payment + policies + state. Single entry point for
 * the booking use-cases. Dependencies are injected (DIP) and are interfaces, so any of
 * them can be swapped (e.g. a distributed inventory) without touching this class.
 */
final class ReservationService {
    private final InventoryService inventory;
    private final PricingStrategy pricing;
    private final PaymentProcessor payments;
    private final CancellationPolicy cancellationPolicy;
    private final LateFeePolicy lateFeePolicy;
    private final DropOffFeeStrategy dropOffFee;
    private final String currency;
    private final List<ReservationListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

    ReservationService(InventoryService inventory, PricingStrategy pricing,
                       PaymentProcessor payments, CancellationPolicy cancellationPolicy,
                       LateFeePolicy lateFeePolicy, DropOffFeeStrategy dropOffFee,
                       String currency) {
        this.inventory = inventory; this.pricing = pricing; this.payments = payments;
        this.cancellationPolicy = cancellationPolicy; this.lateFeePolicy = lateFeePolicy;
        this.dropOffFee = dropOffFee; this.currency = currency;
    }

    void addListener(ReservationListener l) { listeners.add(l); }

    /**
     * Booking saga: quote -> atomic claim -> charge -> confirm. If a later step fails we
     * compensate by releasing the inventory claim so availability is never leaked.
     */
    Reservation book(Customer c, Vehicle v, Branch pickup, Branch ret,
                     DateRange when, List<AddOn> addOns) {
        Reservation r = new Reservation(UUID.randomUUID().toString().substring(0, 8),
                c, v, pickup, ret, when, new ArrayList<>(addOns));
        RentalQuote quote = pricing.price(r, dropOffFee, currency);
        r.setQuote(quote);

        if (!inventory.tryReserve(v, when)) {
            throw new IllegalStateException("Vehicle " + v.id + " not available for " + when
                    + " (taken concurrently or overlapping booking)");
        }
        try {
            Payment p = payments.charge(c, quote.total());
            if (p.status != Payment.Status.SUCCESS) {
                throw new IllegalStateException("Payment failed");
            }
            r.addPayment(p);
            r.confirm(); // CREATED -> CONFIRMED
        } catch (RuntimeException ex) {
            inventory.release(v, when); // compensation
            throw ex;
        }
        reservations.put(r.getId(), r);
        for (ReservationListener l : listeners) l.onConfirmed(r);
        return r;
    }

    void pickUp(String reservationId) { require(reservationId).pickUp(); }

    void returnVehicle(String reservationId, LocalDateTime at) {
        Reservation r = require(reservationId);
        r.returnVehicle(at, this);
    }

    void cancel(String reservationId) {
        Reservation r = require(reservationId);
        r.cancel(this);
    }

    void markNoShow(String reservationId) { require(reservationId).markNoShow(); }

    // --- callbacks invoked by states (package-internal mechanics) ---

    /** Called by InProgressState on return: late fee, release, re-home, notify. */
    void doReturn(Reservation r, LocalDateTime at) {
        r.setActualReturn(at);
        Money fee = lateFeePolicy.lateFee(r, at, currency);
        if (fee.minorUnits > 0) {
            Payment p = payments.charge(r.getCustomer(), fee);
            r.addPayment(p);
            System.out.println("  [return] late fee charged: " + fee);
        }
        inventory.release(r.getVehicle(), r.getPeriod());
        Vehicle v = r.getVehicle();
        v.status = VehicleStatus.AVAILABLE;
        if (r.isOneWay()) {
            v.homeBranch = r.getReturnBranch(); // re-home on one-way rental
            System.out.println("  [return] re-homed " + v.id + " to " + v.homeBranch.id);
        }
        for (ReservationListener l : listeners) l.onCompleted(r);
    }

    /** Called by Created/Confirmed states on cancel: refund, release, notify. */
    void doCancel(Reservation r) {
        Money refund = cancellationPolicy.refundFor(r, LocalDateTime.now());
        Payment original = r.lastSuccessfulPayment();
        if (original != null && refund.minorUnits > 0) {
            Payment refundPayment = payments.refund(original, refund);
            r.addPayment(refundPayment);
            System.out.println("  [cancel] refunded: " + refund);
        }
        inventory.release(r.getVehicle(), r.getPeriod());
        r.setState(new CancelledState());
        for (ReservationListener l : listeners) l.onCancelled(r);
    }

    Reservation get(String id) { return reservations.get(id); }

    private Reservation require(String id) {
        Reservation r = reservations.get(id);
        if (r == null) throw new IllegalArgumentException("No reservation " + id);
        return r;
    }
}

/* =========================== Demo ==================================================== */

public class Solution {
    public static void main(String[] args) {
        final String CUR = "USD";

        // --- wire the system (dependencies injected; no static singletons) ---
        InventoryService inventory = new InventoryService();
        Branch sfo = new Branch("SFO", "San Francisco");
        Branch lax = new Branch("LAX", "Los Angeles");
        inventory.addBranch(sfo);
        inventory.addBranch(lax);

        Vehicle suv1  = VehicleFactory.create(VehicleType.SUV, "SUV-001", sfo);
        Vehicle suv2  = VehicleFactory.create(VehicleType.SUV, "SUV-002", sfo);
        Vehicle lux1  = VehicleFactory.create(VehicleType.LUXURY, "LUX-001", lax);
        inventory.addVehicle(suv1);
        inventory.addVehicle(suv2);
        inventory.addVehicle(lux1);

        // Strategy + Decorator composition: daily -> weekend surge -> membership discount.
        PricingStrategy pricing =
                new MembershipDiscountPricing(
                        new WeekendSurgePricingStrategy(
                                new DailyPricingStrategy(0.08 /* tax */), 1.25 /* surge */));

        ReservationService svc = new ReservationService(
                inventory, pricing, new MockPaymentProcessor(),
                new StandardCancellationPolicy(48, 0.30),
                new StandardLateFeePolicy(2, 1.5),
                new FlatDropOffFeeStrategy(75.0),
                CUR);
        svc.addListener(new LoggingListener());

        Customer alice = new Customer("C1", "Alice", MembershipTier.GOLD);
        Customer bob   = new Customer("C2", "Bob",   MembershipTier.NONE);

        LocalDateTime base = LocalDateTime.of(2026, 7, 3, 10, 0); // Fri -> touches weekend
        DateRange threeDays = new DateRange(base, base.plusDays(3));

        System.out.println("== Scenario 1: search SUVs at SFO for the range ==");
        List<Vehicle> found = inventory.search(new SearchCriteria(sfo, VehicleType.SUV, threeDays));
        System.out.println("  available SUVs: " + found.stream().map(v -> v.id).collect(Collectors.toList()));

        System.out.println("\n== Scenario 2: Alice books an SUV one-way SFO->LAX (gold + weekend) ==");
        Reservation r1 = svc.book(alice, suv1, sfo, lax, threeDays, List.of(
                new AddOn("Insurance", Money.of(15, CUR), true),
                new AddOn("GPS", Money.of(5, CUR), true)));
        System.out.println("  " + r1);
        System.out.println("  " + r1.getQuote());

        System.out.println("\n== Scenario 3: double-booking prevention (Bob tries the SAME car/overlap) ==");
        try {
            svc.book(bob, suv1, sfo, sfo, new DateRange(base.plusDays(1), base.plusDays(4)),
                    Collections.emptyList());
        } catch (IllegalStateException ex) {
            System.out.println("  rejected as expected: " + ex.getMessage());
        }
        // ...but a different car for the same dates succeeds:
        Reservation r2 = svc.book(bob, suv2, sfo, sfo, threeDays, Collections.emptyList());
        System.out.println("  Bob booked a different SUV: " + r2);

        System.out.println("\n== Scenario 4: lifecycle pickup -> late return (late fee) ==");
        svc.pickUp(r1.getId());
        System.out.println("  after pickUp: " + r1.statusName() + ", vehicle status=" + suv1.status);
        svc.returnVehicle(r1.getId(), threeDays.end.plusDays(1)); // 1 day late
        System.out.println("  after return: " + r1.statusName() + ", vehicle status=" + suv1.status
                + ", payments=" + r1.getPayments());

        System.out.println("\n== Scenario 5: illegal transition guarded by State pattern ==");
        try {
            svc.cancel(r1.getId()); // already COMPLETED
        } catch (IllegalStateException ex) {
            System.out.println("  rejected as expected: " + ex.getMessage());
        }

        System.out.println("\n== Scenario 6: cancel a confirmed booking (refund) ==");
        System.out.println("  before cancel: " + r2.statusName());
        svc.cancel(r2.getId());
        System.out.println("  after cancel: " + r2.statusName());
        // car is free again:
        List<Vehicle> freeAgain = inventory.search(new SearchCriteria(sfo, VehicleType.SUV, threeDays));
        System.out.println("  available SUVs now: " + freeAgain.stream().map(v -> v.id).collect(Collectors.toList()));

        System.out.println("\n== Scenario 7: concurrent booking race on one car ==");
        demoConcurrentRace(inventory, svc, lax);

        System.out.println("\nDone.");
    }

    /** Two threads race for the same luxury car; exactly one must win. */
    private static void demoConcurrentRace(InventoryService inventory, ReservationService svc, Branch lax) {
        Vehicle lux = inventory.search(new SearchCriteria(lax, VehicleType.LUXURY,
                new DateRange(LocalDateTime.of(2026, 8, 1, 9, 0), LocalDateTime.of(2026, 8, 3, 9, 0))))
                .stream().findFirst().orElse(null);
        if (lux == null) { System.out.println("  (no luxury car available)"); return; }
        DateRange when = new DateRange(LocalDateTime.of(2026, 8, 1, 9, 0), LocalDateTime.of(2026, 8, 3, 9, 0));
        Customer c1 = new Customer("R1", "Racer1", MembershipTier.NONE);
        Customer c2 = new Customer("R2", "Racer2", MembershipTier.NONE);
        final int[] wins = {0};
        Runnable attempt = () -> {
            try {
                svc.book((Math.random() < 0.5 ? c1 : c2), lux, lax, lax, when, Collections.emptyList());
                synchronized (wins) { wins[0]++; }
            } catch (IllegalStateException ignored) { /* lost the race */ }
        };
        Thread t1 = new Thread(attempt), t2 = new Thread(attempt);
        t1.start(); t2.start();
        try { t1.join(); t2.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        System.out.println("  bookings that succeeded for the single car: " + wins[0] + " (expected exactly 1)");
    }
}

/* =====================================================================================
 * Airline Reservation System — Single-file LLD review / revision artifact.
 *
 * This file is for READING and REVISION, not for production. It is self-contained,
 * complete (no TODOs, no omitted bodies), syntactically correct, and pure JDK.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * (lead a real LLD round with these; never start with classes)
 *
 * FUNCTIONAL:
 *  1.  Actors? Passenger (search/book/cancel), Airline admin (define flights/fares),
 *      Payment provider. Which are in scope?
 *  2.  Trip types: one-way only, or round-trip and multi-city too?
 *  3.  Seat selection in scope, or only sell "a seat in cabin X" and assign at check-in?
 *  4.  Layovers / connections as a single bookable itinerary, or direct flights only?
 *  5.  Fare classes / cabins (Economy/Premium/Business/First)? Do booking codes matter?
 *  6.  Pricing: fixed per class, or dynamic (demand / advance-purchase / promo)?
 *  7.  Cancellations & changes with refund/penalty rules? Date-change = cancel+rebook?
 *  8.  Overbooking (sell more than physical seats with a bumping policy)?
 *  9.  Payment: real gateway or mock success/failure with idempotency?
 *
 * NON-FUNCTIONAL / CONSTRAINTS:
 *  10. Scale: single in-memory service, or distributed? (drives locking strategy)
 *  11. Concurrency on the SAME flight? (per-flight vs per-seat lock)
 *  12. Seat-hold timeout before payment must complete (e.g., 10 min)?
 *  13. Consistency vs availability: never double-book even if we sometimes reject? (yes)
 *  14. Persistence: in-memory now, but map cleanly to a relational schema later?
 *  15. Idempotency: can clients safely retry booking/payment (network retries)?
 *
 * SCOPE-NARROWING:
 *  16. Out of scope: search ranking, baggage, meals, loyalty, check-in, boarding passes?
 *  17. Auth/roles, or assume authenticated caller?
 *  18. Multi-currency / taxes: model a Money value, or ignore?
 *
 * DEFAULT ASSUMPTION (stated aloud): in-memory single-process; one-way + round-trip +
 * multi-city via segment-based itinerary; explicit seat selection with a hold TTL;
 * pluggable pricing; cancellation with policy-driven penalty; optional overbooking per
 * cabin; thread-safe seat-hold path; payment mocked but idempotent.
 *
 * -------------------------------------------------------------------------------------
 * DESIGN PATTERNS DEMONSTRATED
 *  - State     : Booking lifecycle (CREATED/HELD/CONFIRMED/CANCELLED/EXPIRED).
 *  - Strategy  : PricingStrategy, CancellationPolicy, PaymentProcessor.
 *  - Factory/Builder : Aircraft seat-map factory; Itinerary/Booking builders.
 *  - Facade    : ReservationService orchestrates price->hold->pay->confirm/cancel.
 *  - Single injected InventoryManager = sole mutator of seat availability,
 *                per-flight ReentrantLock + atomic claim => no double-booking.
 *  - Value objects : Money, Airport.
 * ===================================================================================== */

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class Solution {

    /* =========================================================================
     * VALUE OBJECTS
     * ========================================================================= */

    /** Money as minor units (e.g., cents) to avoid floating-point money bugs. Immutable. */
    static final class Money {
        final long minorUnits;     // e.g., 12345 == 123.45
        final String currency;     // "USD", "INR", ...

        Money(long minorUnits, String currency) {
            this.minorUnits = minorUnits;
            this.currency = Objects.requireNonNull(currency);
        }

        static Money of(double major, String currency) {
            return new Money(Math.round(major * 100), currency);
        }

        static Money zero(String currency) { return new Money(0, currency); }

        Money plus(Money other) {
            requireSameCurrency(other);
            return new Money(this.minorUnits + other.minorUnits, currency);
        }

        Money minus(Money other) {
            requireSameCurrency(other);
            return new Money(this.minorUnits - other.minorUnits, currency);
        }

        /** Returns a fraction of this amount; e.g., percent(0.10) = 10%. */
        Money percent(double fraction) {
            return new Money(Math.round(this.minorUnits * fraction), currency);
        }

        Money scale(double factor) {
            return new Money(Math.round(this.minorUnits * factor), currency);
        }

        private void requireSameCurrency(Money other) {
            if (!this.currency.equals(other.currency)) {
                throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
            }
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Money)) return false;
            Money m = (Money) o;
            return minorUnits == m.minorUnits && currency.equals(m.currency);
        }
        @Override public int hashCode() { return Objects.hash(minorUnits, currency); }
        @Override public String toString() {
            return String.format("%s %.2f", currency, minorUnits / 100.0);
        }
    }

    /** Airport value object. */
    static final class Airport {
        final String iata; // 3-letter code
        final String city;
        Airport(String iata, String city) { this.iata = iata; this.city = city; }
        @Override public boolean equals(Object o) {
            return (o instanceof Airport) && iata.equals(((Airport) o).iata);
        }
        @Override public int hashCode() { return iata.hashCode(); }
        @Override public String toString() { return iata; }
    }

    /* =========================================================================
     * CABIN / SEAT / AIRCRAFT  (Factory pattern for construction)
     * ========================================================================= */

    /** Travel cabin. overbookingFactor >= 1.0 lets the airline sell beyond physical seats. */
    enum Cabin {
        ECONOMY(1.10), PREMIUM(1.05), BUSINESS(1.0), FIRST(1.0);
        final double overbookingFactor;
        Cabin(double f) { this.overbookingFactor = f; }
    }

    static final class Seat {
        final String number;   // "12A"
        final Cabin cabin;
        final boolean window;
        final boolean exitRow;
        Seat(String number, Cabin cabin, boolean window, boolean exitRow) {
            this.number = number; this.cabin = cabin; this.window = window; this.exitRow = exitRow;
        }
        @Override public String toString() { return number + "(" + cabin + ")"; }
    }

    /** A physical aircraft with a fixed seat map. Built via static factories (Factory pattern). */
    static final class Aircraft {
        final String tail;
        final String model;
        final List<Seat> seats;

        Aircraft(String tail, String model, List<Seat> seats) {
            this.tail = tail; this.model = model;
            this.seats = Collections.unmodifiableList(new ArrayList<>(seats));
        }

        List<Seat> seatsByCabin(Cabin cabin) {
            return seats.stream().filter(s -> s.cabin == cabin).collect(Collectors.toList());
        }

        int physicalCapacity(Cabin cabin) { return seatsByCabin(cabin).size(); }

        /** Factory: a standard narrow-body with a small BUSINESS and a larger ECONOMY cabin. */
        static Aircraft standardNarrowBody(String tail) {
            List<Seat> seats = new ArrayList<>();
            // Business: rows 1-2, seats A,C,D,F
            char[] bizCols = {'A', 'C', 'D', 'F'};
            for (int row = 1; row <= 2; row++) {
                for (char c : bizCols) {
                    boolean window = (c == 'A' || c == 'F');
                    seats.add(new Seat(row + "" + c, Cabin.BUSINESS, window, false));
                }
            }
            // Economy: rows 10-12, seats A,B,C,D,E,F (row 10 is exit row)
            char[] ecoCols = {'A', 'B', 'C', 'D', 'E', 'F'};
            for (int row = 10; row <= 12; row++) {
                for (char c : ecoCols) {
                    boolean window = (c == 'A' || c == 'F');
                    boolean exit = (row == 10);
                    seats.add(new Seat(row + "" + c, Cabin.ECONOMY, window, exit));
                }
            }
            return new Aircraft(tail, "NarrowBody-A1", seats);
        }
    }

    /* =========================================================================
     * FLIGHT
     * ========================================================================= */

    static final class Flight {
        final String flightNo;
        final Airport origin;
        final Airport destination;
        final Instant departure;
        final Instant arrival;
        final Aircraft aircraft;

        Flight(String flightNo, Airport origin, Airport destination,
               Instant departure, Instant arrival, Aircraft aircraft) {
            this.flightNo = flightNo; this.origin = origin; this.destination = destination;
            this.departure = departure; this.arrival = arrival; this.aircraft = aircraft;
        }
        @Override public String toString() {
            return flightNo + " " + origin + "->" + destination;
        }
    }

    /* =========================================================================
     * FLIGHT INVENTORY  (mutable booking ledger for one flight)
     * All mutation is guarded externally by InventoryManager's per-flight lock,
     * and uses ConcurrentHashMap.putIfAbsent so the single-seat claim is atomic.
     * ========================================================================= */

    static final class FlightInventory {
        final Flight flight;
        // seatNumber -> bookingId that owns it (held or confirmed). Absence = free.
        private final ConcurrentHashMap<String, String> seatToBooking = new ConcurrentHashMap<>();
        // confirmed/held count per cabin (for capacity + overbooking checks).
        private final ConcurrentHashMap<Cabin, AtomicInteger> soldByCabin = new ConcurrentHashMap<>();

        FlightInventory(Flight flight) { this.flight = flight; }

        /** Sellable capacity for a cabin honouring the overbooking factor. */
        int sellableCapacity(Cabin cabin) {
            int physical = flight.aircraft.physicalCapacity(cabin);
            return (int) Math.floor(physical * cabin.overbookingFactor);
        }

        /** Atomic single-seat claim. Returns true only if THIS booking now owns the seat. */
        boolean tryHoldSeat(Seat seat, String bookingId) {
            // putIfAbsent => first writer wins; concurrent loser gets the existing owner back.
            String existing = seatToBooking.putIfAbsent(seat.number, bookingId);
            if (existing == null) {
                soldByCabin.computeIfAbsent(seat.cabin, c -> new AtomicInteger()).incrementAndGet();
                return true;
            }
            return bookingId.equals(existing); // idempotent re-hold by same booking is OK
        }

        void releaseSeat(Seat seat, String bookingId) {
            // remove only if this booking owns it (conditional remove = atomic, no double-release)
            if (seatToBooking.remove(seat.number, bookingId)) {
                AtomicInteger c = soldByCabin.get(seat.cabin);
                if (c != null) c.decrementAndGet();
            }
        }

        /** For overbooking sales without a specific seat: capacity check only. */
        boolean hasCabinCapacity(Cabin cabin) {
            int sold = soldByCabin.getOrDefault(cabin, new AtomicInteger()).get();
            return sold < sellableCapacity(cabin);
        }

        int sold(Cabin cabin) { return soldByCabin.getOrDefault(cabin, new AtomicInteger()).get(); }
    }

    /* =========================================================================
     * SEGMENT / ITINERARY  (composition: trip = list of segments)
     * ========================================================================= */

    static final class Segment {
        final Flight flight;
        final Cabin cabin;
        Seat seat; // chosen seat (may be assigned later for overbooked sales)
        Segment(Flight flight, Cabin cabin, Seat seat) {
            this.flight = flight; this.cabin = cabin; this.seat = seat;
        }
        @Override public String toString() {
            return flight + " " + cabin + (seat != null ? " seat " + seat.number : " (seat@checkin)");
        }
    }

    static final class Itinerary {
        final List<Segment> segments;
        private Itinerary(List<Segment> segments) {
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }

        /** Builder pattern: fluent construction of one-way / round-trip / multi-city trips. */
        static final class Builder {
            private final List<Segment> segments = new ArrayList<>();
            Builder add(Flight flight, Cabin cabin, Seat seat) {
                segments.add(new Segment(flight, cabin, seat));
                return this;
            }
            Builder add(Flight flight, Cabin cabin) { return add(flight, cabin, null); }
            Itinerary build() {
                if (segments.isEmpty()) throw new IllegalStateException("Itinerary needs >=1 segment");
                return new Itinerary(segments);
            }
        }
        @Override public String toString() {
            return segments.stream().map(Object::toString).collect(Collectors.joining(" | "));
        }
    }

    /* =========================================================================
     * PASSENGER
     * ========================================================================= */

    static final class Passenger {
        final String id;
        final String name;
        Passenger(String name) { this.id = "PAX-" + UUID.randomUUID().toString().substring(0, 8); this.name = name; }
        @Override public String toString() { return name; }
    }

    /* =========================================================================
     * PAYMENT  (Strategy: PaymentProcessor; idempotent by key)
     * ========================================================================= */

    enum PaymentStatus { SUCCESS, FAILED, REFUNDED }

    static final class Payment {
        final String idempotencyKey;
        final Money amount;
        final PaymentStatus status;
        Payment(String key, Money amount, PaymentStatus status) {
            this.idempotencyKey = key; this.amount = amount; this.status = status;
        }
        @Override public String toString() { return "Payment[" + status + " " + amount + " key=" + idempotencyKey + "]"; }
    }

    interface PaymentProcessor {
        Payment charge(String idempotencyKey, Money amount);
        Payment refund(String idempotencyKey, Money amount);
    }

    /** Mock processor: deduplicates by idempotency key so retries never double-charge. */
    static final class MockPaymentProcessor implements PaymentProcessor {
        private final ConcurrentHashMap<String, Payment> charges = new ConcurrentHashMap<>();
        private final boolean failNext; // toggle to demonstrate failure path

        MockPaymentProcessor() { this(false); }
        MockPaymentProcessor(boolean failNext) { this.failNext = failNext; }

        @Override public Payment charge(String key, Money amount) {
            // Idempotent: a repeated key returns the original result, no second charge.
            return charges.computeIfAbsent(key, k ->
                new Payment(k, amount, failNext ? PaymentStatus.FAILED : PaymentStatus.SUCCESS));
        }

        @Override public Payment refund(String key, Money amount) {
            return new Payment(key + "-refund", amount, PaymentStatus.REFUNDED);
        }
    }

    /* =========================================================================
     * PRICING  (Strategy + composable chain)
     * ========================================================================= */

    /** Context passed to pricing so strategies don't reach into inventory directly. */
    static final class PricingContext {
        final Instant now;
        final double loadFactor; // 0..1, fraction of cabin sold (provided by caller)
        PricingContext(Instant now, double loadFactor) { this.now = now; this.loadFactor = loadFactor; }
    }

    interface PricingStrategy {
        Money price(Segment segment, PricingContext ctx);
    }

    /** Base fare keyed by cabin. */
    static final class BaseFarePricing implements PricingStrategy {
        private final String currency;
        BaseFarePricing(String currency) { this.currency = currency; }
        @Override public Money price(Segment seg, PricingContext ctx) {
            switch (seg.cabin) {
                case FIRST:    return Money.of(1200, currency);
                case BUSINESS: return Money.of(800, currency);
                case PREMIUM:  return Money.of(400, currency);
                case ECONOMY:
                default:       return Money.of(200, currency);
            }
        }
    }

    /** Decorator-style: bumps price as the cabin fills up (demand-based). */
    static final class LoadFactorPricing implements PricingStrategy {
        private final PricingStrategy inner;
        LoadFactorPricing(PricingStrategy inner) { this.inner = inner; }
        @Override public Money price(Segment seg, PricingContext ctx) {
            Money base = inner.price(seg, ctx);
            // up to +50% as load factor approaches 1.0
            return base.scale(1.0 + 0.5 * Math.max(0, Math.min(1, ctx.loadFactor)));
        }
    }

    /** Decorator-style: surcharge for last-minute (advance-purchase) bookings. */
    static final class AdvancePurchasePricing implements PricingStrategy {
        private final PricingStrategy inner;
        AdvancePurchasePricing(PricingStrategy inner) { this.inner = inner; }
        @Override public Money price(Segment seg, PricingContext ctx) {
            Money base = inner.price(seg, ctx);
            long daysToDep = Duration.between(ctx.now, seg.flight.departure).toDays();
            if (daysToDep < 7) return base.scale(1.30);   // <1 week: +30%
            if (daysToDep < 21) return base.scale(1.10);  // <3 weeks: +10%
            return base;
        }
    }

    /* =========================================================================
     * CANCELLATION POLICY  (Strategy)
     * ========================================================================= */

    interface CancellationPolicy {
        /** Refund amount the passenger receives on cancellation. */
        Money refund(Booking booking, Instant now);
    }

    static final class FlexiblePolicy implements CancellationPolicy {
        @Override public Money refund(Booking b, Instant now) { return b.price; } // full refund
    }

    static final class NonRefundablePolicy implements CancellationPolicy {
        @Override public Money refund(Booking b, Instant now) { return Money.zero(b.price.currency); }
    }

    /** Tiered: full refund if >7 days out, 50% if 1-7 days, nothing inside 24h / after departure. */
    static final class TieredPolicy implements CancellationPolicy {
        @Override public Money refund(Booking b, Instant now) {
            Instant earliestDeparture = b.itinerary.segments.stream()
                    .map(s -> s.flight.departure).min(Comparator.naturalOrder())
                    .orElse(now);
            long hours = Duration.between(now, earliestDeparture).toHours();
            if (hours <= 24) return Money.zero(b.price.currency);
            if (hours <= 24 * 7) return b.price.percent(0.50);
            return b.price;
        }
    }

    /* =========================================================================
     * BOOKING STATE  (State pattern)
     * ========================================================================= */

    interface BookingState {
        default void hold(Booking b)   { throw illegal("hold", this); }
        default void confirm(Booking b, Payment p) { throw illegal("confirm", this); }
        default void cancel(Booking b) { throw illegal("cancel", this); }
        default void expire(Booking b) { throw illegal("expire", this); }
        String name();
        static IllegalStateException illegal(String op, BookingState s) {
            return new IllegalStateException("Cannot " + op + " a booking in state " + s.name());
        }
    }

    static final class CreatedState implements BookingState {
        @Override public void hold(Booking b) { b.setState(new HeldState()); }
        @Override public void cancel(Booking b) { b.setState(new CancelledState()); } // cancel before hold
        @Override public String name() { return "CREATED"; }
    }

    static final class HeldState implements BookingState {
        @Override public void confirm(Booking b, Payment p) {
            b.attachPayment(p);
            b.setState(new ConfirmedState());
        }
        @Override public void cancel(Booking b) { b.setState(new CancelledState()); }
        @Override public void expire(Booking b) { b.setState(new ExpiredState()); }
        @Override public String name() { return "HELD"; }
    }

    static final class ConfirmedState implements BookingState {
        @Override public void cancel(Booking b) { b.setState(new CancelledState()); }
        @Override public String name() { return "CONFIRMED"; }
    }

    static final class CancelledState implements BookingState {
        @Override public String name() { return "CANCELLED"; }
    }

    static final class ExpiredState implements BookingState {
        @Override public String name() { return "EXPIRED"; }
    }

    /* =========================================================================
     * SEAT HOLD  (time-boxed claim)
     * ========================================================================= */

    static final class SeatHold {
        final String flightNo;
        final String seatNumber; // may be null for overbooked "seat at check-in"
        final Cabin cabin;
        final Instant expiresAt;
        SeatHold(String flightNo, String seatNumber, Cabin cabin, Instant expiresAt) {
            this.flightNo = flightNo; this.seatNumber = seatNumber; this.cabin = cabin; this.expiresAt = expiresAt;
        }
        boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
        @Override public String toString() { return flightNo + "/" + seatNumber + " exp@" + expiresAt; }
    }

    /* =========================================================================
     * BOOKING  (aggregate root; hosts the State pattern; thread-safe transitions)
     * ========================================================================= */

    static final class Booking {
        final String id;
        final Itinerary itinerary;
        final List<Passenger> passengers;
        final Money price;
        final String idempotencyKey;   // stable key for payment retries
        final List<SeatHold> holds = new ArrayList<>();

        private volatile BookingState state;
        private volatile Payment payment;

        Booking(Itinerary itinerary, List<Passenger> passengers, Money price) {
            this.id = "BKG-" + UUID.randomUUID().toString().substring(0, 8);
            this.itinerary = itinerary;
            this.passengers = Collections.unmodifiableList(new ArrayList<>(passengers));
            this.price = price;
            this.idempotencyKey = "PAY-" + this.id;
            this.state = new CreatedState();
        }

        // Synchronized transitions: concurrent confirm/cancel/expire never corrupt state.
        synchronized void hold()                  { state.hold(this); }
        synchronized void confirm(Payment p)       { state.confirm(this, p); }
        synchronized void cancel()                 { state.cancel(this); }
        synchronized void expire()                 { state.expire(this); }

        void setState(BookingState s) { this.state = s; }
        void attachPayment(Payment p) { this.payment = p; }
        BookingState state() { return state; }
        Payment payment() { return payment; }

        boolean anyHoldExpired(Instant now) {
            return holds.stream().anyMatch(h -> h.isExpired(now));
        }

        @Override public String toString() {
            return id + " [" + state.name() + "] " + price + " " + itinerary;
        }
    }

    /* =========================================================================
     * INVENTORY MANAGER  (sole mutator of seat availability; per-flight locking)
     * ========================================================================= */

    static final class InventoryManager {
        private final Map<String, FlightInventory> inventoryByFlight = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> locksByFlight = new ConcurrentHashMap<>();

        void registerFlight(Flight flight) {
            inventoryByFlight.putIfAbsent(flight.flightNo, new FlightInventory(flight));
            locksByFlight.putIfAbsent(flight.flightNo, new ReentrantLock());
        }

        FlightInventory inventory(String flightNo) { return inventoryByFlight.get(flightNo); }

        double loadFactor(Flight flight, Cabin cabin) {
            FlightInventory inv = inventoryByFlight.get(flight.flightNo);
            int cap = flight.aircraft.physicalCapacity(cabin);
            if (cap == 0) return 1.0;
            return Math.min(1.0, inv.sold(cabin) / (double) cap);
        }

        /**
         * All-or-nothing hold of every seat in an itinerary.
         * Locks are acquired in deterministic flight order to avoid deadlock.
         * If any seat (or capacity) is unavailable, every claim made is rolled back.
         */
        boolean holdSeats(Itinerary itinerary, String bookingId) {
            // Distinct flight numbers sorted => deterministic lock-acquisition order.
            List<String> flightNos = itinerary.segments.stream()
                    .map(s -> s.flight.flightNo).distinct().sorted().collect(Collectors.toList());

            List<ReentrantLock> acquired = new ArrayList<>();
            List<Segment> claimed = new ArrayList<>();
            try {
                for (String fn : flightNos) {
                    ReentrantLock lock = locksByFlight.get(fn);
                    lock.lock();
                    acquired.add(lock);
                }
                // Now holding all relevant flight locks: do the all-or-nothing claim.
                for (Segment seg : itinerary.segments) {
                    FlightInventory inv = inventoryByFlight.get(seg.flight.flightNo);
                    if (seg.seat != null) {
                        if (!inv.tryHoldSeat(seg.seat, bookingId)) {
                            return false; // seat taken -> finally{} rolls back
                        }
                    } else {
                        // overbooked sale without specific seat: capacity-only check
                        if (!inv.hasCabinCapacity(seg.cabin)) return false;
                    }
                    claimed.add(seg);
                }
                claimed.clear(); // success: nothing to roll back
                return true;
            } finally {
                // Roll back any seat claims if we did NOT complete (claimed still populated).
                for (Segment seg : claimed) {
                    if (seg.seat != null) {
                        inventoryByFlight.get(seg.flight.flightNo).releaseSeat(seg.seat, bookingId);
                    }
                }
                // Release locks in reverse order.
                for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).unlock();
            }
        }

        void releaseSeats(Itinerary itinerary, String bookingId) {
            for (Segment seg : itinerary.segments) {
                if (seg.seat != null) {
                    FlightInventory inv = inventoryByFlight.get(seg.flight.flightNo);
                    if (inv != null) inv.releaseSeat(seg.seat, bookingId);
                }
            }
        }

        /** Confirm is a no-op on the ledger here (held == reserved), but kept as a seam. */
        void confirmSeats(Itinerary itinerary, String bookingId) { /* holds already block others */ }
    }

    /* =========================================================================
     * SEARCH SERVICE
     * ========================================================================= */

    static final class SearchService {
        private final List<Flight> flights;
        SearchService(List<Flight> flights) { this.flights = flights; }

        List<Flight> oneWay(Airport from, Airport to) {
            return flights.stream()
                    .filter(f -> f.origin.equals(from) && f.destination.equals(to))
                    .collect(Collectors.toList());
        }

        /** Each leg is an (from,to) pair; returns the list of candidate flights per leg. */
        List<List<Flight>> multiCity(List<Airport[]> legs) {
            List<List<Flight>> result = new ArrayList<>();
            for (Airport[] leg : legs) result.add(oneWay(leg[0], leg[1]));
            return result;
        }
    }

    /* =========================================================================
     * RESERVATION SERVICE  (Facade)
     * ========================================================================= */

    static final class ReservationService {
        private final InventoryManager inventory;
        private final PricingStrategy pricing;
        private final CancellationPolicy cancellationPolicy;
        private final PaymentProcessor payments;
        private final Duration holdTtl;
        private final Map<String, Booking> bookings = new ConcurrentHashMap<>();

        ReservationService(InventoryManager inventory, PricingStrategy pricing,
                           CancellationPolicy cancellationPolicy, PaymentProcessor payments,
                           Duration holdTtl) {
            this.inventory = inventory;
            this.pricing = pricing;
            this.cancellationPolicy = cancellationPolicy;
            this.payments = payments;
            this.holdTtl = holdTtl;
        }

        Booking lookup(String bookingId) { return bookings.get(bookingId); }

        /** Price the itinerary, atomically hold all seats, start the hold TTL. */
        Booking holdItinerary(Itinerary itinerary, List<Passenger> passengers, Instant now) {
            // 1) Price each segment (pass per-cabin load factor so pricing stays decoupled).
            Money total = Money.zero("USD");
            boolean first = true;
            for (Segment seg : itinerary.segments) {
                double lf = inventory.loadFactor(seg.flight, seg.cabin);
                Money segPrice = pricing.price(seg, new PricingContext(now, lf));
                total = first ? segPrice : total.plus(segPrice);
                first = false;
            }
            Booking booking = new Booking(itinerary, passengers, total);

            // 2) All-or-nothing seat hold (thread-safe, no double-booking).
            boolean held = inventory.holdSeats(itinerary, booking.id);
            if (!held) {
                throw new IllegalStateException("Seat/capacity unavailable for itinerary: " + itinerary);
            }

            // 3) Record holds with TTL and move to HELD.
            Instant expiry = now.plus(holdTtl);
            for (Segment seg : itinerary.segments) {
                booking.holds.add(new SeatHold(seg.flight.flightNo,
                        seg.seat != null ? seg.seat.number : null, seg.cabin, expiry));
            }
            booking.hold(); // CREATED -> HELD
            bookings.put(booking.id, booking);
            return booking;
        }

        /** Charge (idempotent) and confirm; on failure or expired holds, release + revert. */
        Booking confirm(String bookingId, Instant now) {
            Booking b = require(bookingId);
            if (!(b.state() instanceof HeldState)) {
                throw new IllegalStateException("Only HELD bookings can be confirmed; was " + b.state().name());
            }
            if (b.anyHoldExpired(now)) {
                expireInternal(b);
                throw new IllegalStateException("Hold expired for " + bookingId);
            }
            Payment payment = payments.charge(b.idempotencyKey, b.price);
            if (payment.status != PaymentStatus.SUCCESS) {
                inventory.releaseSeats(b.itinerary, b.id);
                b.expire(); // HELD -> EXPIRED (treat failed payment like a lost hold)
                throw new IllegalStateException("Payment failed for " + bookingId);
            }
            inventory.confirmSeats(b.itinerary, b.id);
            b.confirm(payment); // HELD -> CONFIRMED
            return b;
        }

        /** Cancel: compute refund per policy, refund, release seats, move to CANCELLED. */
        Money cancel(String bookingId, Instant now) {
            Booking b = require(bookingId);
            Money refund = cancellationPolicy.refund(b, now);
            if (refund.minorUnits > 0 && b.payment() != null) {
                payments.refund(b.idempotencyKey, refund);
            }
            inventory.releaseSeats(b.itinerary, b.id);
            b.cancel();
            return refund;
        }

        /** Change = cancel-and-rebook helper (a true rebooking would reuse the same booking id). */
        Booking change(String bookingId, Itinerary newItinerary, Instant now) {
            Booking old = require(bookingId);
            cancel(bookingId, now);
            return holdItinerary(newItinerary, old.passengers, now);
        }

        /** Expire stale holds (called by a sweeper or lazily). */
        void sweepExpired(Instant now) {
            for (Booking b : bookings.values()) {
                if (b.state() instanceof HeldState && b.anyHoldExpired(now)) {
                    expireInternal(b);
                }
            }
        }

        private void expireInternal(Booking b) {
            inventory.releaseSeats(b.itinerary, b.id);
            b.expire();
        }

        private Booking require(String bookingId) {
            Booking b = bookings.get(bookingId);
            if (b == null) throw new IllegalArgumentException("No such booking: " + bookingId);
            return b;
        }
    }

    /* =========================================================================
     * DEMO  (illustrates the key scenarios; not a test harness)
     * ========================================================================= */

    public static void main(String[] args) throws Exception {
        Instant now = Instant.now();

        // ----- Build airports, aircraft, flights -----
        Airport sfo = new Airport("SFO", "San Francisco");
        Airport jfk = new Airport("JFK", "New York");
        Airport lhr = new Airport("LHR", "London");

        Aircraft ac1 = Aircraft.standardNarrowBody("N101");
        Aircraft ac2 = Aircraft.standardNarrowBody("N102");

        Flight f1 = new Flight("UA100", sfo, jfk,
                now.plus(30, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS), ac1);
        Flight f2 = new Flight("UA200", jfk, lhr,
                now.plus(30, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS),
                now.plus(31, ChronoUnit.DAYS), ac2);

        InventoryManager inventory = new InventoryManager();
        inventory.registerFlight(f1);
        inventory.registerFlight(f2);

        SearchService search = new SearchService(List.of(f1, f2));

        // Composed pricing: base -> load-factor -> advance-purchase (Strategy + decorator chain).
        PricingStrategy pricing = new AdvancePurchasePricing(
                new LoadFactorPricing(new BaseFarePricing("USD")));

        ReservationService reservations = new ReservationService(
                inventory, pricing, new TieredPolicy(), new MockPaymentProcessor(),
                Duration.ofMinutes(10));

        // ----- Scenario 1: search + one-way hold + confirm -----
        System.out.println("=== Scenario 1: one-way hold + confirm ===");
        List<Flight> sfoToJfk = search.oneWay(sfo, jfk);
        System.out.println("Search SFO->JFK found: " + sfoToJfk);

        Seat eco12A = ac1.seatsByCabin(Cabin.ECONOMY).stream()
                .filter(s -> s.number.equals("12A")).findFirst().orElseThrow();

        Itinerary itin1 = new Itinerary.Builder().add(f1, Cabin.ECONOMY, eco12A).build();
        Passenger alice = new Passenger("Alice");
        Booking b1 = reservations.holdItinerary(itin1, List.of(alice), now);
        System.out.println("Held: " + b1);
        Booking c1 = reservations.confirm(b1.id, now);
        System.out.println("Confirmed: " + c1 + " payment=" + c1.payment());

        // ----- Scenario 2: double-booking prevention (concurrent race for same seat) -----
        System.out.println("\n=== Scenario 2: double-booking prevention (race for 11A) ===");
        Seat eco11A = ac1.seatsByCabin(Cabin.ECONOMY).stream()
                .filter(s -> s.number.equals("11A")).findFirst().orElseThrow();

        final InventoryManager inv = inventory;
        final ReservationService rs = reservations;
        final Instant t = now;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        Runnable racer = () -> {
            try {
                Itinerary itin = new Itinerary.Builder().add(f1, Cabin.ECONOMY, eco11A).build();
                rs.holdItinerary(itin, List.of(new Passenger("Racer")), t);
                successes.incrementAndGet();
            } catch (RuntimeException e) {
                failures.incrementAndGet();
            }
        };
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) threads.add(new Thread(racer));
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        System.out.println("Concurrent attempts on seat 11A -> successes=" + successes.get()
                + " failures=" + failures.get() + " (exactly 1 success expected)");

        // ----- Scenario 3: multi-city itinerary (all-or-nothing across 2 flights) -----
        System.out.println("\n=== Scenario 3: multi-city SFO->JFK->LHR ===");
        Seat biz1A = ac1.seatsByCabin(Cabin.BUSINESS).get(0);
        Seat biz1Af2 = ac2.seatsByCabin(Cabin.BUSINESS).get(0);
        Itinerary multi = new Itinerary.Builder()
                .add(f1, Cabin.BUSINESS, biz1A)
                .add(f2, Cabin.BUSINESS, biz1Af2)
                .build();
        Booking b3 = reservations.holdItinerary(multi, List.of(new Passenger("Bob")), now);
        System.out.println("Multi-city held: " + b3 + "  (price reflects 2 BUSINESS segments)");
        reservations.confirm(b3.id, now);
        System.out.println("Multi-city confirmed: " + reservations.lookup(b3.id).state().name());

        // ----- Scenario 4: cancellation with tiered refund -----
        System.out.println("\n=== Scenario 4: cancellation (tiered, >7 days => full refund) ===");
        Money refund = reservations.cancel(b1.id, now);
        System.out.println("Cancelled " + b1.id + " refund=" + refund
                + " state=" + reservations.lookup(b1.id).state().name());

        // ----- Scenario 5: hold expiry releases the seat -----
        System.out.println("\n=== Scenario 5: hold expiry ===");
        Seat eco10A = ac1.seatsByCabin(Cabin.ECONOMY).stream()
                .filter(s -> s.number.equals("10A")).findFirst().orElseThrow();
        Itinerary itin5 = new Itinerary.Builder().add(f1, Cabin.ECONOMY, eco10A).build();
        Booking b5 = reservations.holdItinerary(itin5, List.of(new Passenger("Carol")), now);
        System.out.println("Held: " + b5);
        Instant later = now.plus(11, ChronoUnit.MINUTES); // past the 10-min TTL
        reservations.sweepExpired(later);
        System.out.println("After sweep at +11min: " + reservations.lookup(b5.id).state().name()
                + " (seat 10A now free for resale)");
        // Prove the seat is reusable after expiry:
        Booking b5b = reservations.holdItinerary(
                new Itinerary.Builder().add(f1, Cabin.ECONOMY, eco10A).build(),
                List.of(new Passenger("Dave")), later);
        System.out.println("Re-held expired seat 10A: " + b5b.state().name());

        // ----- Scenario 6: payment failure path -----
        System.out.println("\n=== Scenario 6: payment failure releases hold ===");
        ReservationService failingRs = new ReservationService(
                inventory, pricing, new NonRefundablePolicy(), new MockPaymentProcessor(true),
                Duration.ofMinutes(10));
        Seat eco12F = ac1.seatsByCabin(Cabin.ECONOMY).stream()
                .filter(s -> s.number.equals("12F")).findFirst().orElseThrow();
        Booking b6 = failingRs.holdItinerary(
                new Itinerary.Builder().add(f1, Cabin.ECONOMY, eco12F).build(),
                List.of(new Passenger("Erin")), now);
        try {
            failingRs.confirm(b6.id, now);
        } catch (RuntimeException e) {
            System.out.println("Expected failure: " + e.getMessage()
                    + " -> state=" + failingRs.lookup(b6.id).state().name());
        }

        System.out.println("\nDemo complete.");
    }
}

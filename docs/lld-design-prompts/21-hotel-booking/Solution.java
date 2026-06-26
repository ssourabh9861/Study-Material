/* =====================================================================================
 * Hotel Booking / Reservation — SINGLE-FILE REVIEW / REVISION ARTIFACT
 *
 * This file is for READING and REVISION before an LLD / machine-coding round.
 * It is complete and correct-by-inspection; you do NOT need to compile or run it.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional scope:
 *   1. Book a SPECIFIC physical room, or a ROOM TYPE / inventory bucket (assigned later)?
 *      -> biggest modeling fork. Real hotels sell a bucket count, not a named room.
 *   2. Can one reservation hold multiple rooms / multiple room types / multiple ranges?
 *   3. Support modification (change dates/type) or only create + cancel?
 *   4. Is search in scope (filters: price/amenities/capacity/rating/location; sorting)?
 *   5. Need a HOLD (cart TTL) while the guest pays, or is reserve atomic with payment?
 *   6. Cancellation policy (free until X hrs before check-in / partial / non-refundable)?
 *
 * Non-functional / constraints:
 *   7. Scale: rooms/hotel, hotels, peak concurrent attempts on same room+date?
 *      -> chooses pessimistic vs optimistic locking.
 *   8. Single-node in-memory or DISTRIBUTED (DB row locks / version column / Redis lease)?
 *   9. Consistency: is brief controlled overbooking ok, or must oversell be impossible?
 *  10. Latency / throughput targets for search & for booking?
 *
 * Pricing & policy:
 *  11. Static per-type pricing or DYNAMIC (seasonal, day-of-week, occupancy, LOS, promo)?
 *  12. Multiple rate plans per room type (refundable / non-refundable / B&B)?
 *  13. Currency / taxes / fees in scope?
 *
 * Lifecycle & edge:
 *  14. Reservation states (pending, confirmed, checked-in/out, cancelled, expired, no-show)?
 *  15. Overbooking allowed? By how much, and how do we handle a "walk" (relocation)?
 *  16. What happens to a hold if payment fails or times out?
 *
 * OUT OF SCOPE (confirm): auth, loyalty, OTA/channel sync, housekeeping, real persistence.
 *
 * -------------------------------------------------------------------------------------
 * DESIGN SUMMARY
 *   Inventory model : per-(hotel, roomType, LocalDate) BUCKET count. A stay [checkIn,checkOut)
 *                     is the half-open set of nights (checkout day is NOT occupied).
 *   Double-booking  : two-phase HOLD -> CONFIRM with TTL, plus ATOMIC check-and-decrement
 *                     per night, all-or-nothing across the range. Pessimistic per-key locks
 *                     acquired in sorted order (deadlock-free) by default; an optimistic
 *                     CAS variant is shown for the single-cell technique.
 *   Patterns        : State (reservation), Strategy (pricing/cancellation/sort/overbooking),
 *                     Factory (reservation), Repository (storage), Adapter/Port (payment).
 *   Money           : BigDecimal-backed Money value object — never double for currency.
 * ===================================================================================== */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Solution {

    /* ==========================================================================
     * VALUE OBJECTS
     * ========================================================================== */

    /** Currency-safe money. Never use double for money. */
    static final class Money {
        final BigDecimal amount;
        final String currency;

        Money(BigDecimal amount, String currency) {
            this.amount = amount.setScale(2, RoundingMode.HALF_UP);
            this.currency = currency;
        }
        static Money of(double v, String ccy) { return new Money(BigDecimal.valueOf(v), ccy); }
        static Money zero(String ccy) { return new Money(BigDecimal.ZERO, ccy); }

        Money plus(Money o) { requireSameCcy(o); return new Money(amount.add(o.amount), currency); }
        Money times(int n)  { return new Money(amount.multiply(BigDecimal.valueOf(n)), currency); }
        Money percent(double pct) { return new Money(amount.multiply(BigDecimal.valueOf(pct / 100.0)), currency); }
        Money minus(Money o) { requireSameCcy(o); return new Money(amount.subtract(o.amount), currency); }

        private void requireSameCcy(Money o) {
            if (!currency.equals(o.currency)) throw new IllegalArgumentException("currency mismatch");
        }
        @Override public String toString() { return currency + " " + amount; }
    }

    /** Half-open night range [checkIn, checkOut). The checkout day is not a night stayed. */
    static final class DateRange {
        final LocalDate checkIn;
        final LocalDate checkOut;

        DateRange(LocalDate checkIn, LocalDate checkOut) {
            if (checkIn == null || checkOut == null) throw new IllegalArgumentException("null dates");
            if (!checkOut.isAfter(checkIn))
                throw new IllegalArgumentException("checkOut must be strictly after checkIn (>=1 night)");
            this.checkIn = checkIn;
            this.checkOut = checkOut;
        }
        int nights() { return (int) ChronoUnit.DAYS.between(checkIn, checkOut); }

        /** Every night occupied by this stay (excludes checkOut day). */
        List<LocalDate> nightDates() {
            List<LocalDate> out = new ArrayList<>();
            for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) out.add(d);
            return out;
        }
        @Override public String toString() { return checkIn + " -> " + checkOut + " (" + nights() + "n)"; }
    }

    /* ==========================================================================
     * CATALOG ENTITIES
     * ========================================================================== */

    static final class Hotel {
        final String id;
        final String name;
        final String city;
        final double rating;
        final List<RoomType> roomTypes = new ArrayList<>();

        Hotel(String id, String name, String city, double rating) {
            this.id = id; this.name = name; this.city = city; this.rating = rating;
        }
        RoomType addRoomType(RoomType rt) { roomTypes.add(rt); return rt; }
    }

    static final class RoomType {
        final String id;
        final String hotelId;
        final String name;       // e.g. "Deluxe"
        final int capacity;      // guests per room
        final int physicalCount; // physical rooms of this type
        final List<RatePlan> ratePlans = new ArrayList<>();

        RoomType(String id, String hotelId, String name, int capacity, int physicalCount) {
            this.id = id; this.hotelId = hotelId; this.name = name;
            this.capacity = capacity; this.physicalCount = physicalCount;
        }
        RatePlan addRatePlan(RatePlan rp) { ratePlans.add(rp); return rp; }
        RatePlan defaultRatePlan() { return ratePlans.get(0); }
    }

    /** A sellable price product on a room type, carrying its pricing + cancellation strategies. */
    static final class RatePlan {
        final String id;
        final String name;
        final boolean refundable;
        final PricingStrategy pricing;
        final CancellationPolicy cancellation;

        RatePlan(String id, String name, boolean refundable,
                 PricingStrategy pricing, CancellationPolicy cancellation) {
            this.id = id; this.name = name; this.refundable = refundable;
            this.pricing = pricing; this.cancellation = cancellation;
        }
    }

    static final class Guest {
        final String id;
        final String name;
        final String email;
        Guest(String id, String name, String email) { this.id = id; this.name = name; this.email = email; }
    }

    /* ==========================================================================
     * STATE — reservation lifecycle (lightweight State via enum that knows successors)
     * ========================================================================== */

    enum ReservationStatus {
        PENDING_PAYMENT, CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, EXPIRED;

        boolean canTransitionTo(ReservationStatus next) {
            switch (this) {
                case PENDING_PAYMENT: return next == CONFIRMED || next == CANCELLED || next == EXPIRED;
                case CONFIRMED:       return next == CHECKED_IN || next == CANCELLED;
                case CHECKED_IN:      return next == CHECKED_OUT;
                default:              return false; // CHECKED_OUT, CANCELLED, EXPIRED are terminal
            }
        }
    }

    /* ==========================================================================
     * RESERVATION (aggregate) + line items
     * ========================================================================== */

    static final class ReservationLineItem {
        final RoomType roomType;
        final RatePlan ratePlan;
        final int qty;
        final Money perStayPrice; // price for the whole stay, for this line, x qty

        ReservationLineItem(RoomType roomType, RatePlan ratePlan, int qty, Money perStayPrice) {
            this.roomType = roomType; this.ratePlan = ratePlan; this.qty = qty; this.perStayPrice = perStayPrice;
        }
    }

    static final class Reservation {
        final String id;
        final Guest guest;
        final String hotelId;
        final DateRange dates;
        final List<ReservationLineItem> items;
        final Money total;
        final List<HoldToken> holds;          // inventory units reserved for this reservation

        private volatile ReservationStatus status;
        private volatile Instant holdExpiresAt; // when PENDING_PAYMENT hold lapses
        private volatile String paymentRef;

        Reservation(String id, Guest guest, String hotelId, DateRange dates,
                    List<ReservationLineItem> items, Money total,
                    List<HoldToken> holds, Instant holdExpiresAt) {
            this.id = id; this.guest = guest; this.hotelId = hotelId; this.dates = dates;
            this.items = items; this.total = total; this.holds = holds;
            this.status = ReservationStatus.PENDING_PAYMENT;
            this.holdExpiresAt = holdExpiresAt;
        }

        ReservationStatus status() { return status; }
        Instant holdExpiresAt() { return holdExpiresAt; }

        /** Guarded, atomic transition. Returns true if it won the race. */
        synchronized boolean transition(ReservationStatus next) {
            if (!status.canTransitionTo(next)) {
                throw new IllegalStateException("illegal transition " + status + " -> " + next);
            }
            this.status = next;
            return true;
        }

        synchronized boolean isHoldExpired(Instant now) {
            return status == ReservationStatus.PENDING_PAYMENT
                    && holdExpiresAt != null && now.isAfter(holdExpiresAt);
        }
        void attachPayment(String ref) { this.paymentRef = ref; }
        String paymentRef() { return paymentRef; }
    }

    /* ==========================================================================
     * STRATEGY — pricing
     * ========================================================================== */

    /** Context handed to a pricing strategy. */
    static final class PricingContext {
        final RoomType roomType;
        final RatePlan ratePlan;
        final DateRange dates;
        final int occupancy;
        final String currency;
        PricingContext(RoomType rt, RatePlan rp, DateRange dates, int occupancy, String ccy) {
            this.roomType = rt; this.ratePlan = rp; this.dates = dates; this.occupancy = occupancy; this.currency = ccy;
        }
    }

    interface PricingStrategy {
        /** Quote for ONE room for the whole stay. */
        Money quote(PricingContext ctx);
    }

    /** Flat nightly rate. */
    static final class FlatNightlyPricing implements PricingStrategy {
        final Money nightly;
        FlatNightlyPricing(Money nightly) { this.nightly = nightly; }
        @Override public Money quote(PricingContext ctx) { return nightly.times(ctx.dates.nights()); }
    }

    /**
     * Seasonal multiplier + length-of-stay discount, wrapping a base strategy.
     * (Decorator-style composition over a base PricingStrategy.)
     */
    static final class SeasonalLosPricing implements PricingStrategy {
        final PricingStrategy base;
        final double peakMultiplier;        // e.g. 1.25 on weekends
        final Predicate<LocalDate> isPeak;  // which nights are peak
        final int losThresholdNights;       // discount kicks in at/over this many nights
        final double losDiscountPct;        // e.g. 10.0

        SeasonalLosPricing(PricingStrategy base, double peakMultiplier, Predicate<LocalDate> isPeak,
                           int losThresholdNights, double losDiscountPct) {
            this.base = base; this.peakMultiplier = peakMultiplier; this.isPeak = isPeak;
            this.losThresholdNights = losThresholdNights; this.losDiscountPct = losDiscountPct;
        }
        @Override public Money quote(PricingContext ctx) {
            // Start from base, then bump peak nights, then apply LOS discount.
            Money baseTotal = base.quote(ctx);
            long peakNights = ctx.dates.nightDates().stream().filter(isPeak).count();
            // Approximate peak surcharge proportional to peak nights.
            Money perNight = new Money(baseTotal.amount.divide(
                    BigDecimal.valueOf(Math.max(1, ctx.dates.nights())), 2, RoundingMode.HALF_UP), ctx.currency);
            Money surcharge = perNight.percent((peakMultiplier - 1.0) * 100.0).times((int) peakNights);
            Money subtotal = baseTotal.plus(surcharge);
            if (ctx.dates.nights() >= losThresholdNights && losDiscountPct > 0) {
                subtotal = subtotal.minus(subtotal.percent(losDiscountPct));
            }
            return subtotal;
        }
    }

    /* ==========================================================================
     * STRATEGY — cancellation policy
     * ========================================================================== */

    interface CancellationPolicy {
        /** Refund due if the reservation is cancelled at `now`. */
        Money refund(Reservation r, Instant now);
    }

    /** Non-refundable: always 0. */
    static final class NonRefundablePolicy implements CancellationPolicy {
        @Override public Money refund(Reservation r, Instant now) { return Money.zero(r.total.currency); }
    }

    /** Free cancellation until `freeUntilHoursBeforeCheckIn`, else `lateRefundPct` of total. */
    static final class FlexibleCancellationPolicy implements CancellationPolicy {
        final long freeUntilHoursBeforeCheckIn;
        final double lateRefundPct;
        FlexibleCancellationPolicy(long hours, double lateRefundPct) {
            this.freeUntilHoursBeforeCheckIn = hours; this.lateRefundPct = lateRefundPct;
        }
        @Override public Money refund(Reservation r, Instant now) {
            Instant checkInInstant = r.dates.checkIn.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
            Instant freeUntil = checkInInstant.minus(freeUntilHoursBeforeCheckIn, ChronoUnit.HOURS);
            if (!now.isAfter(freeUntil)) return r.total;            // full refund
            return r.total.percent(lateRefundPct);                  // partial
        }
    }

    /* ==========================================================================
     * STRATEGY — overbooking & search sort
     * ========================================================================== */

    interface OverbookingStrategy { int sellable(int physicalCount); }

    static final class NoOverbooking implements OverbookingStrategy {
        @Override public int sellable(int physical) { return physical; }
    }
    static final class PercentOverbooking implements OverbookingStrategy {
        final double pct;
        PercentOverbooking(double pct) { this.pct = pct; }
        @Override public int sellable(int physical) { return physical + (int) Math.floor(physical * pct / 100.0); }
    }

    /* ==========================================================================
     * INVENTORY MANAGER — the atomicity boundary (double-booking is won here)
     * ========================================================================== */

    /** A token describing inventory units held/committed for a reservation. */
    static final class HoldToken {
        final String hotelId;
        final String roomTypeId;
        final List<LocalDate> nights;
        final int qty;
        HoldToken(String hotelId, String roomTypeId, List<LocalDate> nights, int qty) {
            this.hotelId = hotelId; this.roomTypeId = roomTypeId; this.nights = nights; this.qty = qty;
        }
    }

    /** Request to hold inventory for one room type across a date range. */
    static final class HoldRequest {
        final String hotelId;
        final String roomTypeId;
        final DateRange dates;
        final int qty;
        HoldRequest(String hotelId, String roomTypeId, DateRange dates, int qty) {
            this.hotelId = hotelId; this.roomTypeId = roomTypeId; this.dates = dates; this.qty = qty;
        }
    }

    static final class UnavailableException extends RuntimeException {
        UnavailableException(String msg) { super(msg); }
    }

    /**
     * Tracks free units per (hotel, roomType, date). Each cell is a versioned counter.
     * Two atomicity techniques are shown:
     *   - hold(...)   : PESSIMISTIC, per-key locks acquired in canonical sorted order (deadlock-free).
     *   - tryHoldCas(): OPTIMISTIC single-cell CAS demo (used by hold's inner loop comments).
     */
    static final class InventoryManager {
        /** key -> (free count, version) modeled by an AtomicInteger packing is overkill; keep two maps simple. */
        private final Map<String, AtomicInteger> free = new ConcurrentHashMap<>();
        private final Map<String, Object> locks = new ConcurrentHashMap<>();
        private final OverbookingStrategy overbooking;

        InventoryManager(OverbookingStrategy overbooking) { this.overbooking = overbooking; }

        private static String key(String hotelId, String roomTypeId, LocalDate d) {
            return hotelId + "|" + roomTypeId + "|" + d;
        }
        private Object lockFor(String key) { return locks.computeIfAbsent(key, k -> new Object()); }

        /** Seed sellable capacity for a room type across a span of dates. */
        void seed(RoomType rt, LocalDate from, LocalDate toExclusive) {
            int sellable = overbooking.sellable(rt.physicalCount);
            for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
                free.put(key(rt.hotelId, rt.id, d), new AtomicInteger(sellable));
            }
        }

        int available(String hotelId, String roomTypeId, DateRange dates) {
            int min = Integer.MAX_VALUE;
            for (LocalDate d : dates.nightDates()) {
                AtomicInteger c = free.get(key(hotelId, roomTypeId, d));
                min = Math.min(min, c == null ? 0 : c.get());
            }
            return min == Integer.MAX_VALUE ? 0 : min;
        }

        /**
         * Atomic, all-or-nothing hold across every night.
         * PESSIMISTIC: lock all the night-keys in canonical sorted order (prevents deadlock),
         * verify capacity on every night, then decrement all. Release locks in finally.
         */
        HoldToken hold(HoldRequest req) {
            List<LocalDate> nights = req.dates.nightDates();
            List<String> keys = nights.stream()
                    .map(d -> key(req.hotelId, req.roomTypeId, d))
                    .sorted()                                  // canonical order => deadlock-free
                    .collect(Collectors.toList());

            List<Object> acquired = new ArrayList<>();
            try {
                for (String k : keys) {
                    Object lk = lockFor(k);
                    // Synchronized monitors can't be "acquired into a list" cleanly across iterations,
                    // so we lock the whole critical section via nested synchronization helper.
                    acquired.add(lk);
                }
                return doHoldUnderLocks(acquired, 0, req, nights);
            } catch (UnavailableException e) {
                throw e;
            }
        }

        /** Recursively grabs each monitor (sorted) then performs the atomic check+decrement. */
        private HoldToken doHoldUnderLocks(List<Object> locksInOrder, int idx,
                                           HoldRequest req, List<LocalDate> nights) {
            if (idx == locksInOrder.size()) {
                // All monitors held: verify, then decrement every night atomically.
                for (LocalDate d : nights) {
                    AtomicInteger c = free.get(key(req.hotelId, req.roomTypeId, d));
                    if (c == null || c.get() < req.qty) {
                        throw new UnavailableException("no inventory on " + d
                                + " for " + req.roomTypeId);
                    }
                }
                for (LocalDate d : nights) {
                    free.get(key(req.hotelId, req.roomTypeId, d)).addAndGet(-req.qty);
                }
                return new HoldToken(req.hotelId, req.roomTypeId, nights, req.qty);
            }
            synchronized (locksInOrder.get(idx)) {
                return doHoldUnderLocks(locksInOrder, idx + 1, req, nights);
            }
        }

        /**
         * OPTIMISTIC single-cell technique (illustrative): read-compute-CAS with bounded retry.
         * Shown for the interview talking point; hold() above uses the pessimistic path.
         */
        boolean tryReserveOneNightOptimistic(String hotelId, String roomTypeId, LocalDate d, int qty) {
            AtomicInteger cell = free.get(key(hotelId, roomTypeId, d));
            if (cell == null) return false;
            for (int attempt = 0; attempt < 16; attempt++) {
                int cur = cell.get();
                if (cur < qty) return false;
                if (cell.compareAndSet(cur, cur - qty)) return true; // won the CAS
                // else: someone else changed it; retry
            }
            return false; // gave up after bounded retries (contention)
        }

        /** Commit is a no-op on counts here (already decremented at hold); kept for symmetry/extension. */
        void confirm(HoldToken token) { /* in a DB model: flip hold rows to committed */ }

        /** Return held/committed units to the pool (cancel or expiry). */
        void release(HoldToken token) {
            for (LocalDate d : token.nights) {
                AtomicInteger c = free.get(key(token.hotelId, token.roomTypeId, d));
                if (c != null) c.addAndGet(token.qty);
            }
        }
    }

    /* ==========================================================================
     * PORT/ADAPTER — payment gateway
     * ========================================================================== */

    static final class PaymentResult {
        final boolean ok;
        final String ref;
        PaymentResult(boolean ok, String ref) { this.ok = ok; this.ref = ref; }
    }

    interface PaymentGateway {
        PaymentResult charge(Money amount, String idempotencyKey);
        void refund(Money amount, String paymentRef);
    }

    /** Mock gateway: succeeds unless amount currency-zero; deterministic for the demo. */
    static final class MockPaymentGateway implements PaymentGateway {
        @Override public PaymentResult charge(Money amount, String idempotencyKey) {
            boolean ok = amount.amount.compareTo(BigDecimal.ZERO) > 0;
            return new PaymentResult(ok, ok ? "PAY-" + idempotencyKey : null);
        }
        @Override public void refund(Money amount, String paymentRef) {
            // no-op mock; in real life call provider refund API (idempotent by paymentRef)
        }
    }

    /* ==========================================================================
     * REPOSITORIES — storage boundary (swap to DB later without touching services)
     * ========================================================================== */

    static final class HotelRepository {
        private final Map<String, Hotel> hotels = new ConcurrentHashMap<>();
        void save(Hotel h) { hotels.put(h.id, h); }
        Hotel get(String id) { return hotels.get(id); }
        List<Hotel> all() { return new ArrayList<>(hotels.values()); }
    }

    static final class ReservationRepository {
        private final Map<String, Reservation> map = new ConcurrentHashMap<>();
        void save(Reservation r) { map.put(r.id, r); }
        Reservation get(String id) { return map.get(id); }
        List<Reservation> all() { return new ArrayList<>(map.values()); }
    }

    /* ==========================================================================
     * FACTORY — reservation construction
     * ========================================================================== */

    static final class ReservationFactory {
        static Reservation create(Guest guest, Hotel hotel, DateRange dates,
                                  List<ReservationLineItem> items, Money total,
                                  List<HoldToken> holds, long holdTtlMinutes) {
            return new Reservation(
                    "RES-" + UUID.randomUUID().toString().substring(0, 8),
                    guest, hotel.id, dates, items, total, holds,
                    Instant.now().plus(holdTtlMinutes, ChronoUnit.MINUTES));
        }
    }

    /* ==========================================================================
     * SEARCH — composable filters + sort (Specification + Strategy)
     * ========================================================================== */

    static final class SearchCriteria {
        final String city;
        final DateRange dates;
        final int occupancy;
        final Money maxNightly;            // optional filter (null = ignore)
        final double minRating;
        final Comparator<Quote> sort;       // SortStrategy
        SearchCriteria(String city, DateRange dates, int occupancy,
                       Money maxNightly, double minRating, Comparator<Quote> sort) {
            this.city = city; this.dates = dates; this.occupancy = occupancy;
            this.maxNightly = maxNightly; this.minRating = minRating; this.sort = sort;
        }
    }

    static final class Quote {
        final Hotel hotel;
        final RoomType roomType;
        final RatePlan ratePlan;
        final int available;
        final Money stayPrice;
        Quote(Hotel h, RoomType rt, RatePlan rp, int available, Money stayPrice) {
            this.hotel = h; this.roomType = rt; this.ratePlan = rp; this.available = available; this.stayPrice = stayPrice;
        }
        @Override public String toString() {
            return String.format("%s / %s [%s] avail=%d price=%s",
                    hotel.name, roomType.name, ratePlan.name, available, stayPrice);
        }
    }

    static final class SearchService {
        private final HotelRepository hotels;
        private final InventoryManager inventory;
        SearchService(HotelRepository hotels, InventoryManager inventory) {
            this.hotels = hotels; this.inventory = inventory;
        }

        List<Quote> search(SearchCriteria c) {
            // Build composable filters (Specification pattern).
            Predicate<Hotel> hotelFilter = h ->
                    (c.city == null || c.city.equalsIgnoreCase(h.city)) && h.rating >= c.minRating;

            List<Quote> results = new ArrayList<>();
            for (Hotel h : hotels.all()) {
                if (!hotelFilter.test(h)) continue;
                for (RoomType rt : h.roomTypes) {
                    if (rt.capacity < c.occupancy) continue;       // capacity filter
                    int avail = inventory.available(h.id, rt.id, c.dates);
                    if (avail <= 0) continue;                       // availability filter
                    RatePlan rp = rt.defaultRatePlan();
                    Money price = rp.pricing.quote(
                            new PricingContext(rt, rp, c.dates, c.occupancy, "USD"));
                    if (c.maxNightly != null) {
                        Money perNight = new Money(price.amount.divide(
                                BigDecimal.valueOf(c.dates.nights()), 2, RoundingMode.HALF_UP), price.currency);
                        if (perNight.amount.compareTo(c.maxNightly.amount) > 0) continue; // price filter
                    }
                    results.add(new Quote(h, rt, rp, avail, price));
                }
            }
            if (c.sort != null) results.sort(c.sort);               // SortStrategy
            return results;
        }
    }

    /* ==========================================================================
     * BOOKING SERVICE — orchestration (depends only on abstractions: DIP)
     * ========================================================================== */

    static final class BookingRequest {
        final Guest guest;
        final String hotelId;
        final String roomTypeId;
        final String ratePlanId;   // null => default
        final DateRange dates;
        final int qty;
        final int occupancy;
        BookingRequest(Guest g, String hotelId, String roomTypeId, String ratePlanId,
                       DateRange dates, int qty, int occupancy) {
            this.guest = g; this.hotelId = hotelId; this.roomTypeId = roomTypeId;
            this.ratePlanId = ratePlanId; this.dates = dates; this.qty = qty; this.occupancy = occupancy;
        }
    }

    static final class RefundResult {
        final boolean cancelled;
        final Money refund;
        RefundResult(boolean cancelled, Money refund) { this.cancelled = cancelled; this.refund = refund; }
    }

    static final class BookingService {
        private final HotelRepository hotels;
        private final ReservationRepository reservations;
        private final InventoryManager inventory;
        private final PaymentGateway payments;
        private final long holdTtlMinutes;

        BookingService(HotelRepository hotels, ReservationRepository reservations,
                       InventoryManager inventory, PaymentGateway payments, long holdTtlMinutes) {
            this.hotels = hotels; this.reservations = reservations;
            this.inventory = inventory; this.payments = payments; this.holdTtlMinutes = holdTtlMinutes;
        }

        private RoomType findRoomType(Hotel h, String roomTypeId) {
            return h.roomTypes.stream().filter(rt -> rt.id.equals(roomTypeId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no room type " + roomTypeId));
        }
        private RatePlan resolveRatePlan(RoomType rt, String ratePlanId) {
            if (ratePlanId == null) return rt.defaultRatePlan();
            return rt.ratePlans.stream().filter(rp -> rp.id.equals(ratePlanId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no rate plan " + ratePlanId));
        }

        /** Phase 1: HOLD inventory + create PENDING_PAYMENT reservation with a quote. */
        Reservation startBooking(BookingRequest req) {
            Hotel hotel = Objects.requireNonNull(hotels.get(req.hotelId), "no hotel");
            RoomType rt = findRoomType(hotel, req.roomTypeId);
            RatePlan rp = resolveRatePlan(rt, req.ratePlanId);

            // Atomic hold across every night (throws UnavailableException if any night short).
            HoldToken token = inventory.hold(new HoldRequest(req.hotelId, req.roomTypeId, req.dates, req.qty));

            Money perRoom = rp.pricing.quote(new PricingContext(rt, rp, req.dates, req.occupancy, "USD"));
            Money lineTotal = perRoom.times(req.qty);
            ReservationLineItem item = new ReservationLineItem(rt, rp, req.qty, lineTotal);

            List<HoldToken> holds = new ArrayList<>();
            holds.add(token);
            Reservation res = ReservationFactory.create(
                    req.guest, hotel, req.dates, List.of(item), lineTotal, holds, holdTtlMinutes);
            reservations.save(res);
            return res;
        }

        /** Phase 2: re-validate hold, CHARGE, then CONFIRM (idempotent by reservation id). */
        Reservation confirm(String reservationId, Instant now) {
            Reservation res = Objects.requireNonNull(reservations.get(reservationId), "no reservation");
            if (res.status() == ReservationStatus.CONFIRMED) return res; // idempotent

            if (res.isHoldExpired(now)) {
                // Hold lapsed: release and mark EXPIRED; never charge.
                for (HoldToken t : res.holds) inventory.release(t);
                res.transition(ReservationStatus.EXPIRED);
                throw new IllegalStateException("hold expired for " + reservationId);
            }

            PaymentResult pr = payments.charge(res.total, res.id); // idempotencyKey = reservation id
            if (!pr.ok) {
                for (HoldToken t : res.holds) inventory.release(t);
                res.transition(ReservationStatus.CANCELLED);
                throw new IllegalStateException("payment failed for " + reservationId);
            }
            res.attachPayment(pr.ref);
            for (HoldToken t : res.holds) inventory.confirm(t);
            res.transition(ReservationStatus.CONFIRMED);
            return res;
        }

        /** Cancel: compute policy refund, release inventory, refund payment. */
        RefundResult cancel(String reservationId, Instant now) {
            Reservation res = Objects.requireNonNull(reservations.get(reservationId), "no reservation");
            ReservationStatus s = res.status();
            if (s == ReservationStatus.CHECKED_OUT || s == ReservationStatus.CANCELLED || s == ReservationStatus.EXPIRED) {
                throw new IllegalStateException("cannot cancel reservation in state " + s);
            }
            // Refund is governed by the rate plan of the (first) line item's policy.
            CancellationPolicy policy = res.items.get(0).ratePlan.cancellation;
            Money refund = policy.refund(res, now);

            for (HoldToken t : res.holds) inventory.release(t);
            if (res.paymentRef() != null && refund.amount.compareTo(BigDecimal.ZERO) > 0) {
                payments.refund(refund, res.paymentRef());
            }
            res.transition(ReservationStatus.CANCELLED);
            return new RefundResult(true, refund);
        }

        /** Reaper: expire stale holds and reclaim inventory. */
        int reapExpiredHolds(Instant now) {
            int reaped = 0;
            for (Reservation res : reservations.all()) {
                if (res.isHoldExpired(now)) {
                    for (HoldToken t : res.holds) inventory.release(t);
                    res.transition(ReservationStatus.EXPIRED);
                    reaped++;
                }
            }
            return reaped;
        }
    }

    /* ==========================================================================
     * DEMO — illustrates the key scenarios and prints output
     * ========================================================================== */

    public static void main(String[] args) throws Exception {
        // ---- Build catalog ---------------------------------------------------
        Hotel grand = new Hotel("H1", "Grand Palace", "Bengaluru", 4.6);

        PricingStrategy deluxeBase = new FlatNightlyPricing(Money.of(120.00, "USD"));
        // Weekend (Sat/Sun) peak +25%, 10% discount for stays >= 4 nights.
        Predicate<LocalDate> weekend = d ->
                d.getDayOfWeek().getValue() >= 6;
        PricingStrategy deluxeDynamic = new SeasonalLosPricing(deluxeBase, 1.25, weekend, 4, 10.0);

        RatePlan flexible = new RatePlan("RP-FLEX", "Flexible", true,
                deluxeDynamic, new FlexibleCancellationPolicy(48, 50.0)); // free until 48h, else 50%
        RatePlan nonref = new RatePlan("RP-NR", "Non-Refundable", false,
                new FlatNightlyPricing(Money.of(100.00, "USD")), new NonRefundablePolicy());

        RoomType deluxe = new RoomType("RT-DLX", "H1", "Deluxe", 2, 2); // only 2 physical rooms
        deluxe.addRatePlan(flexible);
        deluxe.addRatePlan(nonref);
        grand.addRoomType(deluxe);

        HotelRepository hotelRepo = new HotelRepository();
        hotelRepo.save(grand);

        // ---- Inventory with no overbooking, seeded for a date window --------
        InventoryManager inventory = new InventoryManager(new NoOverbooking());
        LocalDate from = LocalDate.of(2026, 7, 1);
        inventory.seed(deluxe, from, from.plusDays(30));

        ReservationRepository resRepo = new ReservationRepository();
        PaymentGateway gateway = new MockPaymentGateway();
        BookingService booking = new BookingService(hotelRepo, resRepo, inventory, gateway, 10);

        Guest alice = new Guest("G1", "Alice", "alice@example.com");
        Guest bob = new Guest("G2", "Bob", "bob@example.com");
        Guest carol = new Guest("G3", "Carol", "carol@example.com");

        DateRange stay = new DateRange(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 13)); // 3 nights

        // ---- 1) Search ------------------------------------------------------
        System.out.println("=== SEARCH (Bengaluru, 3 nights, 2 guests, sort by price asc) ===");
        SearchService search = new SearchService(hotelRepo, inventory);
        SearchCriteria criteria = new SearchCriteria("Bengaluru", stay, 2, null, 4.0,
                Comparator.comparing(q -> q.stayPrice.amount));
        for (Quote q : search.search(criteria)) System.out.println("  " + q);

        // ---- 2) Happy path: Alice books, pays, confirmed --------------------
        System.out.println("\n=== ALICE books 1 Deluxe (Flexible) ===");
        Reservation aliceRes = booking.startBooking(
                new BookingRequest(alice, "H1", "RT-DLX", "RP-FLEX", stay, 1, 2));
        System.out.println("  created " + aliceRes.id + " status=" + aliceRes.status()
                + " total=" + aliceRes.total);
        booking.confirm(aliceRes.id, Instant.now());
        System.out.println("  after pay -> status=" + aliceRes.status());
        System.out.println("  remaining availability for stay = "
                + inventory.available("H1", "RT-DLX", stay));

        // ---- 3) Double-booking pressure: 2 threads race for the LAST room ---
        System.out.println("\n=== RACE: Bob and Carol both grab the last Deluxe room ===");
        final Reservation[] holders = new Reservation[2];
        final Throwable[] errs = new Throwable[2];
        Runnable bobR = () -> {
            try { holders[0] = booking.startBooking(
                    new BookingRequest(bob, "H1", "RT-DLX", "RP-NR", stay, 1, 2)); }
            catch (Throwable t) { errs[0] = t; }
        };
        Runnable carolR = () -> {
            try { holders[1] = booking.startBooking(
                    new BookingRequest(carol, "H1", "RT-DLX", "RP-NR", stay, 1, 2)); }
            catch (Throwable t) { errs[1] = t; }
        };
        Thread t1 = new Thread(bobR), t2 = new Thread(carolR);
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.println("  Bob   -> " + (holders[0] != null ? "HELD " + holders[0].id
                : "REJECTED (" + errs[0].getClass().getSimpleName() + ")"));
        System.out.println("  Carol -> " + (holders[1] != null ? "HELD " + holders[1].id
                : "REJECTED (" + errs[1].getClass().getSimpleName() + ")"));
        System.out.println("  availability now = " + inventory.available("H1", "RT-DLX", stay)
                + "  (must be 0; no oversell)");

        // ---- 4) Cancellation with refund policy -----------------------------
        System.out.println("\n=== ALICE cancels (Flexible, well before check-in) ===");
        RefundResult rr = booking.cancel(aliceRes.id, Instant.now());
        System.out.println("  cancelled=" + rr.cancelled + " refund=" + rr.refund
                + " status=" + aliceRes.status());
        System.out.println("  availability after cancel = " + inventory.available("H1", "RT-DLX", stay));

        // ---- 5) Hold expiry ------------------------------------------------
        System.out.println("\n=== HOLD EXPIRY ===");
        BookingService shortHold = new BookingService(hotelRepo, resRepo, inventory, gateway, 0); // 0-min TTL
        Reservation pending = shortHold.startBooking(
                new BookingRequest(carol, "H1", "RT-DLX", "RP-NR", stay, 1, 2));
        System.out.println("  created " + pending.id + " status=" + pending.status());
        int reaped = shortHold.reapExpiredHolds(Instant.now().plusSeconds(1));
        System.out.println("  reaped=" + reaped + " status now=" + pending.status()
                + " availability=" + inventory.available("H1", "RT-DLX", stay));

        System.out.println("\nDemo complete.");
    }
}

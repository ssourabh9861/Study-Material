/* =====================================================================================
 * Movie Ticket Booking (BookMyShow) — single-file LLD REVIEW / REVISION artifact.
 * This file is for READING and revision, not for compiling/running. It is complete and
 * correct-by-inspection: every class, enum, and full method body is present, no TODOs.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 *   Functional:
 *     - Browse path: city -> movie -> cinema -> show? Filter by language/format (2D/3D/IMAX)?
 *     - Seat selection: user picks exact seats from a map, or "N seats anywhere"?
 *     - Do we HOLD seats while the user pays? TTL length? What happens on expiry?
 *     - Booking lifecycle: CREATED -> SEATS_HELD -> PAYMENT_PENDING -> CONFIRMED,
 *       plus EXPIRED / CANCELLED / PAYMENT_FAILED?
 *     - Pricing: flat, or by seat type (regular/premium/recliner) x show category x demand?
 *     - Payments: card / UPI / wallet? Real gateway or stub?
 *     - Cancellation/refund: allowed until a cutoff? full / partial / fee?
 *   Non-functional:
 *     - Concurrency: how many users contend on one show? Single process or many servers?
 *     - Double-booking tolerance? (ZERO — the hard requirement.)
 *     - Seat-map: optimistic display + authoritative check at commit acceptable?
 *     - Scale of catalog; money precision (BigDecimal / integer minor units, never double).
 *   Out of scope (v1): auth, reviews, coupons engine, F&B, real gateway certification,
 *     notification transport, analytics.
 *
 * DESIGN PATTERNS
 *   State    -> Booking lifecycle (legal transitions per phase; illegal ones throw).
 *   Strategy -> PricingStrategy, PaymentStrategy, RefundPolicy.
 *   Observer -> SeatAvailabilityObserver / BookingObserver (live seat-map, notifications).
 *   Factory  -> PaymentStrategyFactory (PaymentMethod -> strategy).
 *   Facade   -> BookingService orchestrates select -> hold -> pay -> confirm / cancel.
 *   Shared authority -> SeatLockManager is the single serialization point for seat status.
 *
 * NO-DOUBLE-BOOKING: every seat-status mutation goes through SeatLockManager and a hold is
 * ALL-OR-NOTHING inside a per-Show critical section (ReentrantLock here). Distributed swap:
 * Redis SETNX+TTL (TTL = hold expiry) or DB row locks / unique constraint — only this class
 * changes (DIP). TTL holds are expired by a reaper + a lazy check on pay().
 * ===================================================================================== */

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/* ============================ Enums ============================ */
enum SeatType { REGULAR, PREMIUM, RECLINER }
enum SeatStatus { AVAILABLE, HELD, BOOKED }
enum ShowCategory { MATINEE, REGULAR, PRIMETIME }
enum PaymentMethod { CARD, UPI, WALLET }
enum PaymentStatus { SUCCESS, FAILED }
enum BookingStatus { CREATED, SEATS_HELD, PAYMENT_PENDING, CONFIRMED, CANCELLED, EXPIRED, PAYMENT_FAILED }

/* ============================ Catalog ============================ */
class User {
    final String id, name;
    User(String id, String name) { this.id = id; this.name = name; }
}

class Movie {
    final String id, title, language;
    final int durationMin;
    Movie(String id, String title, String language, int durationMin) {
        this.id = id; this.title = title; this.language = language; this.durationMin = durationMin;
    }
    public String toString() { return title + " (" + language + ")"; }
}

/** Physical seat — stateless w.r.t. booking. Booking status lives on ShowSeat (per show). */
class Seat {
    final String id;     // e.g. "A5"
    final int row, col;
    final SeatType type;
    Seat(String id, int row, int col, SeatType type) {
        this.id = id; this.row = row; this.col = col; this.type = type;
    }
}

class Screen {
    final String id;
    final List<Seat> seats;
    Screen(String id, List<Seat> seats) { this.id = id; this.seats = seats; }
}

class Cinema {
    final String id, name;
    final List<Screen> screens;
    Cinema(String id, String name, List<Screen> screens) { this.id = id; this.name = name; this.screens = screens; }
}

class City {
    final String id, name;
    final List<Cinema> cinemas = new ArrayList<>();
    City(String id, String name) { this.id = id; this.name = name; }
}

/* ============================ Observer pattern ============================ */
interface SeatAvailabilityObserver { void onSeatChange(Show show, ShowSeat seat); }
interface BookingObserver { void onBookingStateChange(Booking booking, BookingStatus from, BookingStatus to); }

/** Simple console observers used in the demo. */
class ConsoleSeatObserver implements SeatAvailabilityObserver {
    public void onSeatChange(Show show, ShowSeat seat) {
        System.out.println("   [seat-update] show=" + show.id + " seat=" + seat.seat.id + " -> " + seat.status());
    }
}
class ConsoleBookingObserver implements BookingObserver {
    public void onBookingStateChange(Booking b, BookingStatus from, BookingStatus to) {
        System.out.println("   [booking-update] " + b.id + ": " + from + " -> " + to);
    }
}

/* ============================ ShowSeat ============================ */
/** A bookable seat for ONE show = physical seat + per-show mutable status. */
class ShowSeat {
    final Seat seat;
    private SeatStatus status = SeatStatus.AVAILABLE;
    private String heldByBooking; // bookingId currently holding/owning, if any

    ShowSeat(Seat seat) { this.seat = seat; }

    synchronized SeatStatus status() { return status; }
    synchronized String heldBy() { return heldByBooking; }

    /** Compare-and-set used by SeatLockManager under the show lock. Returns false if not in expected state. */
    synchronized boolean casStatus(SeatStatus expected, SeatStatus next, String bookingId) {
        if (status != expected) return false;
        status = next;
        heldByBooking = (next == SeatStatus.AVAILABLE) ? null : bookingId;
        return true;
    }
}

/* ============================ Show (Subject + concurrency boundary) ============================ */
class Show {
    final String id;
    final Movie movie;
    final Screen screen;
    final LocalDateTime startTime;
    final ShowCategory category;
    final PricingStrategy pricingStrategy;

    private final Map<String, ShowSeat> seatMap = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock(); // per-show critical section for holds
    private final List<SeatAvailabilityObserver> observers = new ArrayList<>();

    Show(String id, Movie movie, Screen screen, LocalDateTime startTime,
         ShowCategory category, PricingStrategy pricingStrategy) {
        this.id = id; this.movie = movie; this.screen = screen; this.startTime = startTime;
        this.category = category; this.pricingStrategy = pricingStrategy;
        for (Seat s : screen.seats) seatMap.put(s.id, new ShowSeat(s));
    }

    ReentrantLock lock() { return lock; }
    ShowSeat getSeat(String seatId) { return seatMap.get(seatId); }

    List<ShowSeat> availableSeats() {
        return seatMap.values().stream()
                .filter(ss -> ss.status() == SeatStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    long bookedOrHeldCount() {
        return seatMap.values().stream().filter(ss -> ss.status() != SeatStatus.AVAILABLE).count();
    }
    int totalSeats() { return seatMap.size(); }

    void register(SeatAvailabilityObserver o) { observers.add(o); }
    void notifySeatChange(ShowSeat seat) { for (SeatAvailabilityObserver o : observers) o.onSeatChange(this, seat); }
}

/* ============================ Strategy: Pricing ============================ */
interface PricingStrategy { BigDecimal price(Show show, List<ShowSeat> seats); }

/** Base pricing: per-seat-type base price scaled by show category. */
class SeatTypePricing implements PricingStrategy {
    public BigDecimal price(Show show, List<ShowSeat> seats) {
        BigDecimal total = BigDecimal.ZERO;
        for (ShowSeat ss : seats) total = total.add(base(ss.seat.type).multiply(categoryFactor(show.category)));
        return total;
    }
    private BigDecimal base(SeatType t) {
        switch (t) {
            case RECLINER: return new BigDecimal("500");
            case PREMIUM:  return new BigDecimal("300");
            default:       return new BigDecimal("200");
        }
    }
    private BigDecimal categoryFactor(ShowCategory c) {
        switch (c) {
            case PRIMETIME: return new BigDecimal("1.2");
            case MATINEE:   return new BigDecimal("0.8");
            default:        return BigDecimal.ONE;
        }
    }
}

/** Extension example: surge pricing as the show fills (OCP — no other class changes). */
class DynamicPricing implements PricingStrategy {
    private final PricingStrategy base = new SeatTypePricing();
    public BigDecimal price(Show show, List<ShowSeat> seats) {
        double fillRatio = (double) show.bookedOrHeldCount() / Math.max(1, show.totalSeats());
        BigDecimal surge = BigDecimal.valueOf(1.0 + 0.5 * fillRatio); // up to +50%
        return base.price(show, seats).multiply(surge);
    }
}

/* ============================ Strategy + Factory: Payment ============================ */
class PaymentResult {
    final PaymentStatus status; final String reference;
    PaymentResult(PaymentStatus status, String reference) { this.status = status; this.reference = reference; }
}
interface PaymentStrategy { PaymentResult pay(BigDecimal amount); PaymentResult refund(BigDecimal amount, String reference); }

class CardPayment implements PaymentStrategy {
    public PaymentResult pay(BigDecimal a) { return new PaymentResult(PaymentStatus.SUCCESS, "CARD-" + System.nanoTime()); }
    public PaymentResult refund(BigDecimal a, String ref) { return new PaymentResult(PaymentStatus.SUCCESS, "RFND-" + ref); }
}
class UpiPayment implements PaymentStrategy {
    public PaymentResult pay(BigDecimal a) { return new PaymentResult(PaymentStatus.SUCCESS, "UPI-" + System.nanoTime()); }
    public PaymentResult refund(BigDecimal a, String ref) { return new PaymentResult(PaymentStatus.SUCCESS, "RFND-" + ref); }
}
class WalletPayment implements PaymentStrategy {
    private BigDecimal balance;
    WalletPayment(BigDecimal balance) { this.balance = balance; }
    public PaymentResult pay(BigDecimal a) {
        if (balance.compareTo(a) < 0) return new PaymentResult(PaymentStatus.FAILED, null); // insufficient funds
        balance = balance.subtract(a);
        return new PaymentResult(PaymentStatus.SUCCESS, "WAL-" + System.nanoTime());
    }
    public PaymentResult refund(BigDecimal a, String ref) { balance = balance.add(a); return new PaymentResult(PaymentStatus.SUCCESS, "RFND-" + ref); }
}

class PaymentStrategyFactory {
    /** WalletPayment normally comes from the user's wallet; demo gives a fixed balance. */
    static PaymentStrategy create(PaymentMethod method) {
        switch (method) {
            case CARD:   return new CardPayment();
            case UPI:    return new UpiPayment();
            case WALLET: return new WalletPayment(new BigDecimal("1000"));
            default:     throw new IllegalArgumentException("Unknown method: " + method);
        }
    }
}

class PaymentProcessor {
    PaymentResult charge(PaymentMethod method, BigDecimal amount) {
        return PaymentStrategyFactory.create(method).pay(amount);
    }
    PaymentResult refund(PaymentMethod method, BigDecimal amount, String reference) {
        return PaymentStrategyFactory.create(method).refund(amount, reference);
    }
}

/* ============================ Strategy: Refund policy ============================ */
interface RefundPolicy { BigDecimal refundable(Booking b, Instant now); }

/** Full refund > 2h before show; 50% within 2h; nothing after showtime. */
class DefaultRefundPolicy implements RefundPolicy {
    public BigDecimal refundable(Booking b, Instant now) {
        Instant showInstant = b.show.startTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        if (!now.isBefore(showInstant)) return BigDecimal.ZERO;
        Duration toShow = Duration.between(now, showInstant);
        if (toShow.toHours() >= 2) return b.amount;
        return b.amount.multiply(new BigDecimal("0.5"));
    }
}

/* ============================ State pattern: Booking lifecycle ============================ */
interface BookingState {
    BookingStatus status();
    default void onPay(Booking b)     { throw new IllegalStateException("Cannot pay in state " + status()); }
    default void onConfirm(Booking b) { throw new IllegalStateException("Cannot confirm in state " + status()); }
    default void onFail(Booking b)    { throw new IllegalStateException("Cannot fail in state " + status()); }
    default void onCancel(Booking b)  { throw new IllegalStateException("Cannot cancel in state " + status()); }
    default void onExpire(Booking b)  { throw new IllegalStateException("Cannot expire in state " + status()); }
}

class SeatsHeldState implements BookingState {
    public BookingStatus status() { return BookingStatus.SEATS_HELD; }
    public void onPay(Booking b)    { b.setState(new PaymentPendingState()); }
    public void onExpire(Booking b) { b.releaseSeats(); b.setState(new ExpiredState()); }
    public void onCancel(Booking b) { b.releaseSeats(); b.setState(new CancelledState()); }
}
class PaymentPendingState implements BookingState {
    public BookingStatus status() { return BookingStatus.PAYMENT_PENDING; }
    public void onConfirm(Booking b) { b.confirmSeats(); b.setState(new ConfirmedState()); }
    public void onFail(Booking b)    { b.releaseSeats(); b.setState(new PaymentFailedState()); }
}
class ConfirmedState implements BookingState {
    public BookingStatus status() { return BookingStatus.CONFIRMED; }
    public void onConfirm(Booking b) { /* idempotent: already confirmed, no-op */ }
    public void onCancel(Booking b)  { b.releaseSeats(); b.setState(new CancelledState()); }
}
class CancelledState   implements BookingState { public BookingStatus status() { return BookingStatus.CANCELLED; } }
class ExpiredState     implements BookingState { public BookingStatus status() { return BookingStatus.EXPIRED; } }
class PaymentFailedState implements BookingState { public BookingStatus status() { return BookingStatus.PAYMENT_FAILED; } }

/* ============================ Booking ============================ */
class Booking {
    final String id;
    final User user;
    final Show show;
    final List<ShowSeat> seats;
    final BigDecimal amount;
    final Instant holdExpiresAt;

    private BookingState state;
    PaymentMethod paymentMethod;
    String paymentReference;

    private final SeatLockManager lockManager;
    private final List<BookingObserver> observers;

    Booking(String id, User user, Show show, List<ShowSeat> seats, BigDecimal amount,
            Instant holdExpiresAt, SeatLockManager lockManager, List<BookingObserver> observers) {
        this.id = id; this.user = user; this.show = show; this.seats = seats;
        this.amount = amount; this.holdExpiresAt = holdExpiresAt;
        this.lockManager = lockManager; this.observers = observers;
        this.state = new SeatsHeldState(); // created already holding seats
    }

    BookingStatus status() { return state.status(); }

    void setState(BookingState next) {
        BookingStatus from = state.status();
        this.state = next;
        for (BookingObserver o : observers) o.onBookingStateChange(this, from, next.status());
    }

    // Transitions delegate to the current state (illegal ones throw).
    void pay()     { state.onPay(this); }
    void confirm() { state.onConfirm(this); }
    void fail()    { state.onFail(this); }
    void cancel()  { state.onCancel(this); }
    void expire()  { state.onExpire(this); }

    // Seat effects, invoked by states; routed through the lock manager (single authority).
    void confirmSeats() { lockManager.confirmSeats(show, seats); }
    void releaseSeats() { lockManager.releaseSeats(show, seats); }

    boolean isExpired(Instant now) { return now.isAfter(holdExpiresAt); }
}

/* ============================ SeatLockManager (no-double-book authority) ============================ */
/**
 * Single serialization point for seat-status mutation. Holds are ALL-OR-NOTHING within a
 * per-Show critical section, so two callers can never both grab the same seat.
 * Distributed swap: replace the ReentrantLock + CAS with Redis SETNX+TTL or DB row locks;
 * nothing else in the system changes (DIP).
 */
class SeatLockManager {
    /** Atomically hold every requested seat for this booking, or hold none and return false. */
    boolean holdSeats(Show show, List<ShowSeat> seats, String bookingId) {
        show.lock().lock();
        try {
            // Phase 1: verify all available.
            for (ShowSeat ss : seats) if (ss.status() != SeatStatus.AVAILABLE) return false;
            // Phase 2: flip all to HELD (cannot fail now — we hold the show lock).
            for (ShowSeat ss : seats) {
                ss.casStatus(SeatStatus.AVAILABLE, SeatStatus.HELD, bookingId);
                show.notifySeatChange(ss);
            }
            return true;
        } finally {
            show.lock().unlock();
        }
    }

    void confirmSeats(Show show, List<ShowSeat> seats) {
        show.lock().lock();
        try {
            for (ShowSeat ss : seats) {
                ss.casStatus(SeatStatus.HELD, SeatStatus.BOOKED, ss.heldBy());
                show.notifySeatChange(ss);
            }
        } finally { show.lock().unlock(); }
    }

    void releaseSeats(Show show, List<ShowSeat> seats) {
        show.lock().lock();
        try {
            for (ShowSeat ss : seats) {
                SeatStatus s = ss.status();
                if (s == SeatStatus.HELD)  ss.casStatus(SeatStatus.HELD, SeatStatus.AVAILABLE, null);
                if (s == SeatStatus.BOOKED) ss.casStatus(SeatStatus.BOOKED, SeatStatus.AVAILABLE, null);
                show.notifySeatChange(ss);
            }
        } finally { show.lock().unlock(); }
    }
}

/* ============================ Search ============================ */
class SearchService {
    private final List<City> cities;
    SearchService(List<City> cities) { this.cities = cities; }

    List<Movie> moviesIn(City city, List<Show> allShows) {
        return allShows.stream()
                .filter(s -> cityOf(s, city))
                .map(s -> s.movie).distinct().collect(Collectors.toList());
    }
    List<Show> showsFor(Movie movie, City city, List<Show> allShows) {
        return allShows.stream()
                .filter(s -> s.movie.id.equals(movie.id) && cityOf(s, city))
                .collect(Collectors.toList());
    }
    private boolean cityOf(Show s, City city) {
        for (Cinema c : city.cinemas) for (Screen sc : c.screens) if (sc.id.equals(s.screen.id)) return true;
        return false;
    }
}

/* ============================ BookingService (Facade) ============================ */
class BookingService {
    private final SeatLockManager lockManager;
    private final PaymentProcessor paymentProcessor;
    private final RefundPolicy refundPolicy;
    private final Clock clock;
    private final Duration holdTtl;
    private final List<BookingObserver> bookingObservers = new ArrayList<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    BookingService(SeatLockManager lockManager, PaymentProcessor paymentProcessor,
                   RefundPolicy refundPolicy, Clock clock, Duration holdTtl) {
        this.lockManager = lockManager; this.paymentProcessor = paymentProcessor;
        this.refundPolicy = refundPolicy; this.clock = clock; this.holdTtl = holdTtl;
    }

    void register(BookingObserver o) { bookingObservers.add(o); }

    /** Select + hold seats. Returns a Booking in SEATS_HELD, or throws if any seat is unavailable. */
    Booking createBooking(User user, Show show, List<String> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) throw new IllegalArgumentException("No seats selected");
        List<ShowSeat> seats = new ArrayList<>();
        for (String id : seatIds) {
            ShowSeat ss = show.getSeat(id);
            if (ss == null) throw new IllegalArgumentException("Seat not in this show: " + id);
            seats.add(ss);
        }
        // Sort by seat id for a stable lock/CAS order (defends against deadlock in the fine-grained variant).
        seats.sort((a, b) -> a.seat.id.compareTo(b.seat.id));

        String bookingId = "BK-" + seq.incrementAndGet();
        boolean held = lockManager.holdSeats(show, seats, bookingId);
        if (!held) throw new IllegalStateException("One or more seats are no longer available");

        BigDecimal amount = show.pricingStrategy.price(show, seats);
        Instant expiresAt = clock.instant().plus(holdTtl);
        Booking b = new Booking(bookingId, user, show, seats, amount, expiresAt, lockManager, bookingObservers);
        bookings.put(bookingId, b);
        return b;
    }

    /** Pay for a held booking: confirms on gateway success, releases seats on failure. */
    Booking pay(String bookingId, PaymentMethod method) {
        Booking b = require(bookingId);
        // Lazy expiry check: never accept payment for a stale hold even if the reaper hasn't run.
        if (b.status() == BookingStatus.SEATS_HELD && b.isExpired(clock.instant())) {
            b.expire();
            throw new IllegalStateException("Hold expired for " + bookingId);
        }
        b.paymentMethod = method;
        b.pay(); // SEATS_HELD -> PAYMENT_PENDING
        PaymentResult result = paymentProcessor.charge(method, b.amount);
        if (result.status == PaymentStatus.SUCCESS) {
            b.paymentReference = result.reference;
            b.confirm(); // -> CONFIRMED, seats BOOKED
        } else {
            b.fail();    // -> PAYMENT_FAILED, seats released
        }
        return b;
    }

    /** Cancel a confirmed booking; computes refund and frees seats. */
    Booking cancel(String bookingId) {
        Booking b = require(bookingId);
        if (b.status() == BookingStatus.CONFIRMED) {
            BigDecimal refund = refundPolicy.refundable(b, clock.instant());
            if (refund.compareTo(BigDecimal.ZERO) > 0)
                paymentProcessor.refund(b.paymentMethod, refund, b.paymentReference);
            System.out.println("   [refund] " + b.id + " amount=" + refund);
        }
        b.cancel(); // -> CANCELLED, seats released
        return b;
    }

    /** Reaper: sweep expired holds and free their seats. Called periodically (or by a scheduler). */
    void reapExpired() {
        Instant now = clock.instant();
        for (Booking b : bookings.values()) {
            if (b.status() == BookingStatus.SEATS_HELD && b.isExpired(now)) b.expire();
        }
    }

    private Booking require(String id) {
        Booking b = bookings.get(id);
        if (b == null) throw new IllegalArgumentException("Unknown booking: " + id);
        return b;
    }
}

/* ============================ Demo ============================ */
public class Solution {
    public static void main(String[] args) {
        // --- Build catalog ---
        List<Seat> seats = new ArrayList<>();
        char[] rows = {'A', 'B'};
        for (int r = 0; r < rows.length; r++)
            for (int c = 1; c <= 5; c++) {
                SeatType t = (rows[r] == 'A') ? SeatType.PREMIUM : SeatType.REGULAR;
                seats.add(new Seat("" + rows[r] + c, r, c, t));
            }
        Screen screen = new Screen("SCR-1", seats);
        Cinema cinema = new Cinema("CIN-1", "PVR Phoenix", List.of(screen));
        City city = new City("CTY-1", "Bengaluru");
        city.cinemas.add(cinema);

        Movie movie = new Movie("MOV-1", "Interstellar", "English", 169);
        Show show = new Show("SHOW-1", movie, screen,
                LocalDateTime.now().plusHours(5), ShowCategory.PRIMETIME, new SeatTypePricing());

        // Observers (live seat-map + booking-state updates)
        show.register(new ConsoleSeatObserver());

        // --- Services ---
        SeatLockManager lockManager = new SeatLockManager();
        BookingService service = new BookingService(
                lockManager, new PaymentProcessor(), new DefaultRefundPolicy(),
                Clock.systemDefaultZone(), Duration.ofMinutes(5));
        service.register(new ConsoleBookingObserver());

        User alice = new User("U1", "Alice");
        User bob   = new User("U2", "Bob");

        // --- Scenario 1: Alice holds + pays for A1, A2 (happy path) ---
        System.out.println("== Scenario 1: Alice books A1,A2 ==");
        Booking aliceBk = service.createBooking(alice, show, List.of("A1", "A2"));
        System.out.println("   Alice booking=" + aliceBk.id + " amount=" + aliceBk.amount + " status=" + aliceBk.status());
        service.pay(aliceBk.id, PaymentMethod.CARD);
        System.out.println("   Alice final status=" + aliceBk.status() + " ref=" + aliceBk.paymentReference);

        // --- Scenario 2: Bob tries the SAME seat A1 -> must fail (no double-booking) ---
        System.out.println("== Scenario 2: Bob tries A1 (already booked) ==");
        try {
            service.createBooking(bob, show, List.of("A1", "A3"));
        } catch (IllegalStateException e) {
            System.out.println("   Bob blocked: " + e.getMessage());
        }

        // --- Scenario 3: Bob books a free seat A3 with UPI ---
        System.out.println("== Scenario 3: Bob books A3 via UPI ==");
        Booking bobBk = service.createBooking(bob, show, List.of("A3"));
        service.pay(bobBk.id, PaymentMethod.UPI);
        System.out.println("   Bob final status=" + bobBk.status());

        // --- Scenario 4: Alice cancels -> refund + seats freed ---
        System.out.println("== Scenario 4: Alice cancels A1,A2 ==");
        service.cancel(aliceBk.id);
        System.out.println("   Alice status=" + aliceBk.status() + ", available seats now=" + show.availableSeats().size());

        // --- Scenario 5: illegal transition is rejected by the State machine ---
        System.out.println("== Scenario 5: paying a cancelled booking is rejected ==");
        try {
            service.pay(aliceBk.id, PaymentMethod.CARD);
        } catch (IllegalStateException e) {
            System.out.println("   Rejected: " + e.getMessage());
        }

        System.out.println("Done.");
    }
}

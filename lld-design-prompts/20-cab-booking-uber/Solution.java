/* =====================================================================================
 * Cab Booking System (Uber / Ola) — single-file LLD REVIEW / REVISION artifact.
 *
 * This file is for READING and REVISION, not for production. It is complete and
 * correct-by-inspection (no TODOs, no omitted bodies). A main() at the bottom
 * illustrates the key scenarios.
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING
 * -------------------------------------------------------------------------------------
 * Functional:
 *   - Core actions for v1: request ride, match driver, run trip lifecycle, pay, rate?
 *     On-demand only, or also scheduled-for-later rides?
 *   - Multiple ride types (Mini/Sedan/Premium/Pool)? Does type affect match + price?
 *   - Carpool/Pool (multiple riders per trip) in v1 or later?
 *   - Matching policy: strictly nearest available, or factor rating/ETA/fairness?
 *   - Can a driver decline? Fallback to next-nearest? How many tries before "no cars"?
 *   - Who can cancel and when (rider pre-pickup, driver post-accept, mid-trip)? Fees?
 * Constraints/rules:
 *   - Driver locations from GPS pings (latest known)? Freshness threshold?
 *   - Search radius for matching; expand if nothing found?
 *   - Surge pricing per-zone from demand/supply ratio as a multiplier?
 *   - Fare formula: base + per-km + per-minute, with a minimum fare? Per ride type?
 * Non-functional:
 *   - Scale (concurrent riders/drivers per city) -> linear scan vs geospatial index.
 *   - HARD invariant: a driver is assigned to at most ONE active trip (strong consistency).
 *   - Matching latency target; audit trail for disputes; failure handling
 *     (payment failure, driver offline mid-trip, app crash/reconnect).
 *
 * PATTERNS: State (Trip lifecycle), Strategy (matching + pricing), Factory (pricing
 * selection), Observer (trip notifications), Facade (RideBookingService).
 * CONCURRENCY: atomic CAS reservation on Driver (one-driver-one-trip), per-trip
 * synchronized transitions, ConcurrentHashMap registries.
 * ===================================================================================== */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class Solution {

    /* =================================================================================
     * ENUMS
     * ============================================================================== */

    enum VehicleType { HATCHBACK, SEDAN, SUV }

    /** Ride type carries the vehicle classes it accepts and a per-type rate card. */
    enum RideType {
        MINI(EnumSet.of(VehicleType.HATCHBACK), 30.0, 8.0, 1.0, 50.0),
        SEDAN(EnumSet.of(VehicleType.SEDAN), 50.0, 12.0, 1.5, 80.0),
        PREMIUM(EnumSet.of(VehicleType.SUV), 80.0, 18.0, 2.0, 120.0);

        final EnumSet<VehicleType> allowed;
        final double baseFare, perKm, perMin, minFare;

        RideType(EnumSet<VehicleType> allowed, double baseFare, double perKm,
                 double perMin, double minFare) {
            this.allowed = allowed;
            this.baseFare = baseFare;
            this.perKm = perKm;
            this.perMin = perMin;
            this.minFare = minFare;
        }
        boolean accepts(VehicleType t) { return allowed.contains(t); }
    }

    enum DriverStatus { OFFLINE, AVAILABLE, ON_TRIP }

    enum TripStatus {
        REQUESTED, DRIVER_ASSIGNED, DRIVER_ARRIVED, IN_PROGRESS,
        COMPLETED, CANCELLED, NO_DRIVERS_AVAILABLE
    }

    enum Actor { RIDER, DRIVER }

    /* =================================================================================
     * VALUE OBJECTS / ENTITIES
     * ============================================================================== */

    /** Immutable geo point. Haversine distance (km) — a stand-in for a routing service. */
    static final class Location {
        final double lat, lng;
        Location(double lat, double lng) { this.lat = lat; this.lng = lng; }

        double distanceKm(Location o) {
            final double R = 6371.0;
            double dLat = Math.toRadians(o.lat - lat);
            double dLng = Math.toRadians(o.lng - lng);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                     + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(o.lat))
                     * Math.sin(dLng / 2) * Math.sin(dLng / 2);
            return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }
        @Override public String toString() { return String.format("(%.4f,%.4f)", lat, lng); }
    }

    static final class Vehicle {
        final String plate;
        final VehicleType type;
        Vehicle(String plate, VehicleType type) { this.plate = plate; this.type = type; }
    }

    static final class Rider {
        final String id, name;
        volatile Trip activeTrip;
        Rider(String id, String name) { this.id = id; this.name = name; }
    }

    /**
     * Driver holds the contended resource: its status. Reservation is an atomic CAS
     * (AVAILABLE -> ON_TRIP); only the winning thread proceeds. release() reverts it.
     */
    static final class Driver {
        final String id, name;
        final Vehicle vehicle;
        private volatile Location location;
        private final AtomicReference<DriverStatus> status =
                new AtomicReference<>(DriverStatus.OFFLINE);

        // running average rating
        private double ratingSum = 0;
        private int ratingCount = 0;

        Driver(String id, String name, Vehicle vehicle, Location loc) {
            this.id = id; this.name = name; this.vehicle = vehicle; this.location = loc;
        }

        Location location() { return location; }
        void updateLocation(Location l) { this.location = l; }
        DriverStatus status() { return status.get(); }
        void goOnline() { status.compareAndSet(DriverStatus.OFFLINE, DriverStatus.AVAILABLE); }
        void goOffline() { status.set(DriverStatus.OFFLINE); }

        /** Atomic claim. Returns true only for the single winning caller. */
        boolean tryReserve() {
            return status.compareAndSet(DriverStatus.AVAILABLE, DriverStatus.ON_TRIP);
        }
        void release() { status.set(DriverStatus.AVAILABLE); }

        synchronized void addRating(int value) {
            ratingSum += value; ratingCount++;
        }
        synchronized double avgRating() {
            return ratingCount == 0 ? 0.0 : ratingSum / ratingCount;
        }
        @Override public String toString() {
            return name + "[" + vehicle.type + ", " + String.format("%.1f", avgRating()) + "★]";
        }
    }

    static final class Rating {
        final int value; final String comment;
        Rating(int value, String comment) {
            if (value < 1 || value > 5) throw new IllegalArgumentException("rating 1..5");
            this.value = value; this.comment = comment;
        }
    }

    /** Zone used for surge pricing. */
    static final class Zone {
        final String id;
        volatile double surgeMultiplier = 1.0;
        Zone(String id) { this.id = id; }
    }

    /* =================================================================================
     * TRIP — STATE MACHINE (State pattern, pragmatic transition-map flavour)
     * ============================================================================== */

    static final class Trip {
        final String id;
        final Rider rider;
        final Location pickup, drop;
        final RideType rideType;
        final Zone zone;

        private volatile Driver driver;            // assigned later
        private volatile TripStatus status = TripStatus.REQUESTED;
        private volatile double fare = 0.0;
        private volatile boolean paymentPending = false;
        private final List<Rating> ratings = new CopyOnWriteArrayList<>();
        private PricingStrategy pricingStrategy;   // chosen at assignment time

        // Allowed transitions — the single source of truth for legal moves.
        private static final Map<TripStatus, Set<TripStatus>> ALLOWED = new EnumMap<>(TripStatus.class);
        static {
            ALLOWED.put(TripStatus.REQUESTED,
                    EnumSet.of(TripStatus.DRIVER_ASSIGNED, TripStatus.NO_DRIVERS_AVAILABLE, TripStatus.CANCELLED));
            ALLOWED.put(TripStatus.DRIVER_ASSIGNED,
                    EnumSet.of(TripStatus.DRIVER_ARRIVED, TripStatus.CANCELLED));
            ALLOWED.put(TripStatus.DRIVER_ARRIVED,
                    EnumSet.of(TripStatus.IN_PROGRESS, TripStatus.CANCELLED));
            ALLOWED.put(TripStatus.IN_PROGRESS,
                    EnumSet.of(TripStatus.COMPLETED));
            ALLOWED.put(TripStatus.COMPLETED, EnumSet.noneOf(TripStatus.class));
            ALLOWED.put(TripStatus.CANCELLED, EnumSet.noneOf(TripStatus.class));
            ALLOWED.put(TripStatus.NO_DRIVERS_AVAILABLE, EnumSet.noneOf(TripStatus.class));
        }

        Trip(String id, Rider rider, Location pickup, Location drop, RideType rideType, Zone zone) {
            this.id = id; this.rider = rider; this.pickup = pickup;
            this.drop = drop; this.rideType = rideType; this.zone = zone;
        }

        /** Guarded transition. Throws on an illegal move so concurrent/out-of-order
         *  commands are rejected and only the first valid one wins. */
        synchronized void transitionTo(TripStatus next) {
            Set<TripStatus> ok = ALLOWED.get(status);
            if (ok == null || !ok.contains(next)) {
                throw new IllegalStateException(
                        "Illegal trip transition " + status + " -> " + next + " (trip " + id + ")");
            }
            this.status = next;
        }

        synchronized boolean canCancel() {
            return ALLOWED.get(status).contains(TripStatus.CANCELLED);
        }

        void assignDriver(Driver d, PricingStrategy ps) { this.driver = d; this.pricingStrategy = ps; }
        Driver driver() { return driver; }
        TripStatus status() { return status; }
        double fare() { return fare; }
        void setFare(double f) { this.fare = f; }
        boolean paymentPending() { return paymentPending; }
        void setPaymentPending(boolean p) { this.paymentPending = p; }
        PricingStrategy pricingStrategy() { return pricingStrategy; }

        void addRating(Rating r) {
            if (status != TripStatus.COMPLETED)
                throw new IllegalStateException("Can only rate a COMPLETED trip");
            ratings.add(r);
        }
        @Override public String toString() {
            return "Trip#" + id + "[" + rideType + ", " + status
                    + (driver != null ? ", driver=" + driver.name : "")
                    + (fare > 0 ? ", fare=" + String.format("%.2f", fare) : "") + "]";
        }
    }

    /* =================================================================================
     * DRIVER LOCATION INDEX (seam for scaling: linear scan -> geo index)
     * ============================================================================== */

    interface DriverLocationIndex {
        void register(Driver d);
        void updateLocation(Driver d, Location l);
        /** Available drivers within radiusKm whose vehicle class fits rideType. */
        List<Driver> nearbyAvailable(Location from, double radiusKm, RideType rideType);
    }

    /** v1 linear scan. Swap for quadtree / geohash / S2 at scale — nothing above changes. */
    static final class LinearScanIndex implements DriverLocationIndex {
        private final Map<String, Driver> drivers = new ConcurrentHashMap<>();

        public void register(Driver d) { drivers.put(d.id, d); }
        public void updateLocation(Driver d, Location l) { d.updateLocation(l); }

        public List<Driver> nearbyAvailable(Location from, double radiusKm, RideType rideType) {
            List<Driver> out = new ArrayList<>();
            for (Driver d : drivers.values()) {
                if (d.status() != DriverStatus.AVAILABLE) continue;
                if (!rideType.accepts(d.vehicle.type)) continue;
                if (from.distanceKm(d.location()) <= radiusKm) out.add(d);
            }
            return out;
        }
    }

    /* =================================================================================
     * MATCHING (Strategy: ranking policy)
     * ============================================================================== */

    interface MatchingStrategy {
        /** Rank candidates best-first. */
        List<Driver> rank(List<Driver> candidates, Location from);
    }

    static final class NearestDriverStrategy implements MatchingStrategy {
        public List<Driver> rank(List<Driver> candidates, Location from) {
            List<Driver> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(d -> from.distanceKm(d.location())));
            return sorted;
        }
    }

    /** Example alternative strategy (Liskov-substitutable) — highest rated first. */
    static final class HighestRatedStrategy implements MatchingStrategy {
        public List<Driver> rank(List<Driver> candidates, Location from) {
            List<Driver> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(Driver::avgRating).reversed());
            return sorted;
        }
    }

    static final class MatchingService {
        private final DriverLocationIndex index;
        private final MatchingStrategy strategy;
        private final double baseRadiusKm, maxRadiusKm;

        MatchingService(DriverLocationIndex index, MatchingStrategy strategy,
                        double baseRadiusKm, double maxRadiusKm) {
            this.index = index; this.strategy = strategy;
            this.baseRadiusKm = baseRadiusKm; this.maxRadiusKm = maxRadiusKm;
        }

        /** Ranked candidate list; expands radius until something is found or cap hit. */
        List<Driver> findCandidates(Location pickup, RideType rideType) {
            for (double r = baseRadiusKm; r <= maxRadiusKm; r *= 2) {
                List<Driver> found = index.nearbyAvailable(pickup, r, rideType);
                if (!found.isEmpty()) return strategy.rank(found, pickup);
            }
            return Collections.emptyList();
        }
    }

    /* =================================================================================
     * PRICING (Strategy + Factory) and SURGE
     * ============================================================================== */

    interface PricingStrategy {
        double price(Trip trip, double distanceKm, double durationMin);
        String name();
    }

    static final class NormalPricing implements PricingStrategy {
        public double price(Trip trip, double km, double min) {
            RideType rt = trip.rideType;
            double fare = rt.baseFare + rt.perKm * km + rt.perMin * min;
            return Math.max(fare, rt.minFare);
        }
        public String name() { return "NORMAL"; }
    }

    /** Decorates normal pricing with a zone surge multiplier. */
    static final class SurgePricing implements PricingStrategy {
        private final PricingStrategy base;
        private final double multiplier;
        SurgePricing(PricingStrategy base, double multiplier) {
            this.base = base; this.multiplier = multiplier;
        }
        public double price(Trip trip, double km, double min) {
            return base.price(trip, km, min) * multiplier;
        }
        public String name() { return "SURGE x" + multiplier; }
    }

    /** Tracks demand/supply per zone and exposes a surge multiplier. */
    static final class SurgeService {
        private final Map<String, Zone> zones = new ConcurrentHashMap<>();
        Zone zone(String id) { return zones.computeIfAbsent(id, Zone::new); }

        /** Recompute from open requests vs available drivers (simple ratio model). */
        void recompute(String zoneId, int openRequests, int availableDrivers) {
            Zone z = zone(zoneId);
            if (availableDrivers <= 0) { z.surgeMultiplier = 2.5; return; }
            double ratio = (double) openRequests / availableDrivers;
            z.surgeMultiplier = ratio <= 1.0 ? 1.0 : Math.min(1.0 + (ratio - 1.0), 3.0);
        }
        double multiplier(Zone z) { return z.surgeMultiplier; }
    }

    static final class PricingStrategyFactory {
        private final SurgeService surgeService;
        private final PricingStrategy normal = new NormalPricing();
        PricingStrategyFactory(SurgeService surgeService) { this.surgeService = surgeService; }

        /** Picks normal vs surge based on the zone's current multiplier. */
        PricingStrategy forZone(Zone zone, RideType rideType) {
            double m = surgeService.multiplier(zone);
            return m > 1.0 ? new SurgePricing(normal, m) : normal;
        }
    }

    /* =================================================================================
     * OBSERVER — trip notifications
     * ============================================================================== */

    interface TripObserver {
        void onEvent(Trip trip, TripStatus status);
    }

    static final class RiderNotifier implements TripObserver {
        public void onEvent(Trip t, TripStatus s) {
            System.out.println("  [rider:" + t.rider.name + "] trip " + t.id + " -> " + s);
        }
    }
    static final class DriverNotifier implements TripObserver {
        public void onEvent(Trip t, TripStatus s) {
            if (t.driver() != null)
                System.out.println("  [driver:" + t.driver().name + "] trip " + t.id + " -> " + s);
        }
    }
    static final class AnalyticsListener implements TripObserver {
        final List<String> log = new CopyOnWriteArrayList<>();
        public void onEvent(Trip t, TripStatus s) { log.add(t.id + ":" + s); }
    }

    /** Observer registry; fan-out is isolated from trip/booking logic. */
    static final class NotificationService {
        private final List<TripObserver> observers = new CopyOnWriteArrayList<>();
        void register(TripObserver o) { observers.add(o); }
        void unregister(TripObserver o) { observers.remove(o); }
        void publish(Trip t, TripStatus s) {
            for (TripObserver o : observers) o.onEvent(t, s);
        }
    }

    /* =================================================================================
     * PAYMENT
     * ============================================================================== */

    interface PaymentService {
        boolean charge(Trip trip, double amount);
    }
    /** Stub that always succeeds; a real impl calls a gateway. */
    static final class SimplePaymentService implements PaymentService {
        public boolean charge(Trip trip, double amount) {
            System.out.println("  [payment] charged rider " + trip.rider.name
                    + " amount " + String.format("%.2f", amount));
            return true;
        }
    }

    /* =================================================================================
     * FACADE — RideBookingService (entry point + concurrency invariant)
     * ============================================================================== */

    static final class RideBookingService {
        private final MatchingService matchingService;
        private final PricingStrategyFactory pricingFactory;
        private final NotificationService notifications;
        private final PaymentService paymentService;
        private final Map<String, Trip> trips = new ConcurrentHashMap<>();
        private int tripSeq = 0;

        RideBookingService(MatchingService m, PricingStrategyFactory p,
                           NotificationService n, PaymentService pay) {
            this.matchingService = m; this.pricingFactory = p;
            this.notifications = n; this.paymentService = pay;
        }

        private synchronized String nextTripId() { return "T" + (++tripSeq); }

        /**
         * Request a ride: match -> atomically reserve a driver from the ranked list ->
         * price -> assign -> notify. If no driver can be reserved, ends NO_DRIVERS_AVAILABLE.
         * 'accepts' simulates driver accept/decline (for the demo's fallback path).
         */
        Trip requestRide(Rider rider, Location pickup, Location drop,
                         RideType rideType, Zone zone,
                         java.util.function.Predicate<Driver> accepts) {
            Trip trip = new Trip(nextTripId(), rider, pickup, drop, rideType, zone);
            trips.put(trip.id, trip);
            rider.activeTrip = trip;

            List<Driver> candidates = matchingService.findCandidates(pickup, rideType);
            for (Driver d : candidates) {
                // Atomic claim — only the winning thread/trip gets this driver.
                if (!d.tryReserve()) continue;            // taken by a concurrent request
                if (accepts != null && !accepts.test(d)) { // driver declined -> free & try next
                    d.release();
                    continue;
                }
                PricingStrategy ps = pricingFactory.forZone(zone, rideType);
                trip.assignDriver(d, ps);
                trip.transitionTo(TripStatus.DRIVER_ASSIGNED);
                notifications.publish(trip, TripStatus.DRIVER_ASSIGNED);
                return trip;
            }
            // none available / all declined
            trip.transitionTo(TripStatus.NO_DRIVERS_AVAILABLE);
            notifications.publish(trip, TripStatus.NO_DRIVERS_AVAILABLE);
            rider.activeTrip = null;
            return trip;
        }

        void driverArrived(Trip trip) {
            trip.transitionTo(TripStatus.DRIVER_ARRIVED);
            notifications.publish(trip, TripStatus.DRIVER_ARRIVED);
        }

        void startTrip(Trip trip) {
            trip.transitionTo(TripStatus.IN_PROGRESS);
            notifications.publish(trip, TripStatus.IN_PROGRESS);
        }

        /** Complete: price the ride, charge, free the driver, notify. Payment failure
         *  does not trap the driver — the trip is flagged paymentPending and retried. */
        void completeTrip(Trip trip, double distanceKm, double durationMin) {
            double amount = trip.pricingStrategy().price(trip, distanceKm, durationMin);
            trip.setFare(amount);
            trip.transitionTo(TripStatus.COMPLETED);

            boolean paid = paymentService.charge(trip, amount);
            trip.setPaymentPending(!paid);

            Driver d = trip.driver();
            if (d != null) d.release();
            trip.rider.activeTrip = null;
            notifications.publish(trip, TripStatus.COMPLETED);
        }

        void cancelTrip(Trip trip, Actor actor) {
            if (!trip.canCancel())
                throw new IllegalStateException("Cannot cancel trip in state " + trip.status());
            Driver d = trip.driver();
            trip.transitionTo(TripStatus.CANCELLED);
            if (d != null) d.release();   // free the driver for the next request
            trip.rider.activeTrip = null;
            System.out.println("  [cancel] trip " + trip.id + " cancelled by " + actor);
            notifications.publish(trip, TripStatus.CANCELLED);
        }

        /** Post-completion ratings; updates the driver's running average. */
        void rate(Trip trip, int riderToDriver, String driverComment, int driverToRider) {
            trip.addRating(new Rating(riderToDriver, driverComment));
            if (trip.driver() != null) trip.driver().addRating(riderToDriver);
            // driverToRider would update a rider rating in a fuller model; recorded for symmetry.
            System.out.println("  [rating] trip " + trip.id + " driver got " + riderToDriver
                    + "★ (avg now " + String.format("%.2f", trip.driver().avgRating()) + ")");
        }
    }

    /* =================================================================================
     * DEMO
     * ============================================================================== */

    public static void main(String[] args) throws Exception {
        // ---- Wire up services (injected, not static singletons -> testable) ----
        DriverLocationIndex index = new LinearScanIndex();
        MatchingService matching = new MatchingService(index, new NearestDriverStrategy(), 2.0, 16.0);
        SurgeService surge = new SurgeService();
        PricingStrategyFactory pricingFactory = new PricingStrategyFactory(surge);
        NotificationService notifications = new NotificationService();
        AnalyticsListener analytics = new AnalyticsListener();
        notifications.register(new RiderNotifier());
        notifications.register(new DriverNotifier());
        notifications.register(analytics);
        RideBookingService booking = new RideBookingService(
                matching, pricingFactory, notifications, new SimplePaymentService());

        // ---- Seed drivers near a downtown pickup ----
        Location downtown = new Location(12.9716, 77.5946);   // Bangalore-ish
        Driver d1 = new Driver("D1", "Asha", new Vehicle("KA01-1", VehicleType.HATCHBACK), new Location(12.9720, 77.5950));
        Driver d2 = new Driver("D2", "Bilal", new Vehicle("KA02-2", VehicleType.SEDAN),     new Location(12.9740, 77.5960));
        Driver d3 = new Driver("D3", "Chitra", new Vehicle("KA03-3", VehicleType.HATCHBACK), new Location(12.9700, 77.5930));
        Driver d4 = new Driver("D4", "Dev", new Vehicle("KA04-4", VehicleType.SUV),         new Location(12.9760, 77.5980));
        for (Driver d : List.of(d1, d2, d3, d4)) { index.register(d); d.goOnline(); }

        Zone zone = surge.zone("Z-downtown");

        System.out.println("=== Scenario 1: happy path MINI ride ===");
        Rider r1 = new Rider("R1", "Ravi");
        Location drop1 = new Location(12.9352, 77.6245);
        Trip t1 = booking.requestRide(r1, downtown, drop1, RideType.MINI, zone, d -> true);
        System.out.println("  assigned -> " + t1);
        booking.driverArrived(t1);
        booking.startTrip(t1);
        booking.completeTrip(t1, 8.5, 22);   // 8.5 km, 22 min
        booking.rate(t1, 5, "Smooth ride", 5);
        System.out.println("  final -> " + t1);

        System.out.println("\n=== Scenario 2: surge pricing (demand > supply) ===");
        surge.recompute("Z-downtown", /*openRequests*/ 9, /*availableDrivers*/ 3); // -> ~3x capped
        System.out.println("  surge multiplier now = " + zone.surgeMultiplier);
        Rider r2 = new Rider("R2", "Sara");
        Trip t2 = booking.requestRide(r2, downtown, drop1, RideType.SEDAN, zone, d -> true);
        booking.driverArrived(t2);
        booking.startTrip(t2);
        booking.completeTrip(t2, 8.5, 22);
        System.out.println("  pricing used: " + t2.pricingStrategy().name() + " -> " + t2);
        surge.recompute("Z-downtown", 1, 5); // back to normal

        System.out.println("\n=== Scenario 3: driver declines -> fallback to next ===");
        Rider r3 = new Rider("R3", "Meena");
        // First-ranked HATCHBACK driver declines; flow offers the next candidate.
        final String declineId = "D3"; // whoever ranks first may be D1 or D3; decline D3 to show fallback
        Trip t3 = booking.requestRide(r3, downtown, drop1, RideType.MINI, zone,
                d -> !d.id.equals(declineId));
        System.out.println("  assigned despite a decline -> " + t3);
        if (t3.status() == TripStatus.DRIVER_ASSIGNED) booking.cancelTrip(t3, Actor.RIDER);

        System.out.println("\n=== Scenario 4: concurrent requests for the same nearest driver ===");
        // Make exactly ONE matching driver so two requests race for it.
        for (Driver d : List.of(d1, d2, d3, d4)) d.goOffline();
        Driver only = new Driver("D9", "Solo", new Vehicle("KA09-9", VehicleType.HATCHBACK),
                new Location(12.9718, 77.5948));
        index.register(only); only.goOnline();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Trip> task = () -> booking.requestRide(
                new Rider("Rc" + Thread.currentThread().getId(), "Racer"),
                downtown, drop1, RideType.MINI, zone, d -> true);
        Future<Trip> f1 = pool.submit(task);
        Future<Trip> f2 = pool.submit(task);
        Trip a = f1.get(), b = f2.get();
        pool.shutdown();
        long assigned = List.of(a, b).stream()
                .filter(t -> t.status() == TripStatus.DRIVER_ASSIGNED).count();
        long rejected = List.of(a, b).stream()
                .filter(t -> t.status() == TripStatus.NO_DRIVERS_AVAILABLE).count();
        System.out.println("  results: " + a.status() + " / " + b.status());
        System.out.println("  exactly one got the driver? assigned=" + assigned + " rejected=" + rejected
                + " -> " + (assigned == 1 && rejected == 1 ? "INVARIANT HELD" : "VIOLATION"));

        System.out.println("\n=== Scenario 5: illegal transition is rejected ===");
        Rider r5 = new Rider("R5", "Tariq");
        only.release();  // free the solo driver if it was taken (best-effort for demo)
        Trip t5 = new Trip("Tx", r5, downtown, drop1, RideType.MINI, zone);
        try {
            t5.transitionTo(TripStatus.IN_PROGRESS);  // REQUESTED -> IN_PROGRESS is illegal
        } catch (IllegalStateException ex) {
            System.out.println("  correctly rejected: " + ex.getMessage());
        }

        System.out.println("\n=== Analytics log ===");
        System.out.println("  " + analytics.log);
    }
}

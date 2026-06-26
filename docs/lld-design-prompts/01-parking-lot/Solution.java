/* =====================================================================================
 * PARKING LOT — single-file LLD review/revision artifact (NOT meant to be compiled/run).
 *
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING:
 *   1. How many floors and how many entry/exit gates? (multi-gate concurrency?)
 *   2. Which vehicle types (motorcycle, car, truck, EV)? Fixed or growing?
 *   3. Spot sizes and the fit matrix — can a car take a larger spot when its size is full?
 *      Do big vehicles take multiple spots?
 *   4. Spot selection: any-free, nearest-to-gate, or pre-reserved?
 *   5. Pricing: flat / hourly / daily / tiered? Per vehicle type? Changeable at runtime?
 *   6. Payment methods (cash/card/UPI)? Do we model the processor or trust an external "paid"?
 *   7. Physical or digital ticket? Lost-ticket penalty flow?
 *   8. Concurrency: simultaneous requests at multiple gates? Throughput?
 *   9. Single in-memory JVM or distributed? (assume single JVM, thread-safe)
 *  10. Persistence/restart needed? (assume in-memory)
 *  11. Scale — tens or tens of thousands of spots?
 *  12. Out of scope: ANPR, hardware/sensors, reconciliation, UI, multi-lot federation.
 *
 * PATTERNS DEMONSTRATED:
 *   Strategy  -> PricingStrategy, SpotAssignmentStrategy, PaymentStrategy
 *   Factory   -> VehicleFactory, ParkingSpotFactory
 *   Singleton -> ParkingLot (note: prefer DI in production; shown for the textbook expectation)
 *   Observer  -> DisplayBoard
 *   Decorator -> EvSurchargePricing (wraps any PricingStrategy)
 *
 * THREAD-SAFETY: a free spot is atomically popped from a per-floor, per-size deque under
 * the floor's lock, so two gates can never be handed the same spot.
 * ===================================================================================== */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/* ----------------------------- Enums & simple value types ----------------------------- */

enum SpotType {
    MOTORCYCLE, COMPACT, LARGE, EV;

    /** Fit rule: which spot types a vehicle's required type may occupy, in up-size order. */
    static List<SpotType> fittingOrder(SpotType required) {
        switch (required) {
            case MOTORCYCLE: return Arrays.asList(MOTORCYCLE, COMPACT, LARGE);
            case COMPACT:    return Arrays.asList(COMPACT, LARGE);
            case LARGE:      return Collections.singletonList(LARGE);
            case EV:         return Arrays.asList(EV, LARGE); // EV prefers a charger spot, else a large spot
            default:         return Collections.emptyList();
        }
    }
}

enum VehicleType { MOTORCYCLE, CAR, TRUCK, ELECTRIC }

/* ----------------------------------- Vehicles (Factory) ----------------------------------- */

abstract class Vehicle {
    private final String plate;
    protected Vehicle(String plate) { this.plate = plate; }
    public String getPlate() { return plate; }
    public abstract SpotType requiredSpot();
    @Override public String toString() { return getClass().getSimpleName() + "(" + plate + ")"; }
}
class Motorcycle  extends Vehicle { Motorcycle(String p)  { super(p); } public SpotType requiredSpot() { return SpotType.MOTORCYCLE; } }
class Car         extends Vehicle { Car(String p)         { super(p); } public SpotType requiredSpot() { return SpotType.COMPACT; } }
class Truck       extends Vehicle { Truck(String p)       { super(p); } public SpotType requiredSpot() { return SpotType.LARGE; } }
class ElectricCar extends Vehicle { ElectricCar(String p) { super(p); } public SpotType requiredSpot() { return SpotType.EV; } }

/** Simple Factory: centralizes construction so callers depend on the abstract Vehicle. */
final class VehicleFactory {
    static Vehicle create(VehicleType type, String plate) {
        switch (type) {
            case MOTORCYCLE: return new Motorcycle(plate);
            case CAR:        return new Car(plate);
            case TRUCK:      return new Truck(plate);
            case ELECTRIC:   return new ElectricCar(plate);
            default: throw new IllegalArgumentException("Unknown vehicle type: " + type);
        }
    }
}

/* ------------------------------------- Parking spot ------------------------------------- */

class ParkingSpot {
    private final String id;
    private final SpotType type;
    private volatile boolean occupied;
    private volatile Vehicle vehicle;

    ParkingSpot(String id, SpotType type) { this.id = id; this.type = type; }

    String getId() { return id; }
    SpotType getType() { return type; }
    boolean isOccupied() { return occupied; }
    Vehicle getVehicle() { return vehicle; }

    boolean canFit(Vehicle v) { return SpotType.fittingOrder(v.requiredSpot()).contains(type); }

    void assign(Vehicle v) { this.vehicle = v; this.occupied = true; }
    void free() { this.vehicle = null; this.occupied = false; }

    @Override public String toString() { return id + "[" + type + "]"; }
}

/** Simple Factory for spots. */
final class ParkingSpotFactory {
    static ParkingSpot create(String id, SpotType type) { return new ParkingSpot(id, type); }
}

/* ------------------------------------- Parking floor ------------------------------------- */

class ParkingFloor {
    private final int floorNumber;
    private final Map<SpotType, Deque<ParkingSpot>> freeByType = new EnumMap<>(SpotType.class);
    private final Map<String, ParkingSpot> allSpots = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock(); // per-floor lock => gates on other floors don't contend

    ParkingFloor(int floorNumber) {
        this.floorNumber = floorNumber;
        for (SpotType t : SpotType.values()) freeByType.put(t, new ArrayDeque<>());
    }

    int getFloorNumber() { return floorNumber; }

    void addSpot(ParkingSpot spot) {
        lock.lock();
        try {
            allSpots.put(spot.getId(), spot);
            freeByType.get(spot.getType()).push(spot);
        } finally { lock.unlock(); }
    }

    /** Atomically claim a free spot for the vehicle, honoring the up-size fit order. */
    ParkingSpot allocate(Vehicle v) {
        lock.lock();
        try {
            for (SpotType t : SpotType.fittingOrder(v.requiredSpot())) {
                Deque<ParkingSpot> free = freeByType.get(t);
                if (free != null && !free.isEmpty()) {
                    ParkingSpot spot = free.pop();   // popped => invisible to other threads
                    spot.assign(v);
                    return spot;
                }
            }
            return null; // nothing fits on this floor
        } finally { lock.unlock(); }
    }

    void release(ParkingSpot spot) {
        lock.lock();
        try {
            spot.free();
            freeByType.get(spot.getType()).push(spot);
        } finally { lock.unlock(); }
    }

    Map<SpotType, Integer> availability() {
        lock.lock();
        try {
            Map<SpotType, Integer> snap = new EnumMap<>(SpotType.class);
            for (Map.Entry<SpotType, Deque<ParkingSpot>> e : freeByType.entrySet())
                snap.put(e.getKey(), e.getValue().size());
            return snap;
        } finally { lock.unlock(); }
    }
}

/* --------------------------------------- Ticket / Receipt --------------------------------------- */

class Ticket {
    private static final AtomicLong SEQ = new AtomicLong(1);
    private final String id;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final long entryEpochMs;
    private final int entryGateId;

    Ticket(Vehicle vehicle, ParkingSpot spot, long entryEpochMs, int entryGateId) {
        this.id = "T-" + SEQ.getAndIncrement();
        this.vehicle = vehicle;
        this.spot = spot;
        this.entryEpochMs = entryEpochMs;
        this.entryGateId = entryGateId;
    }
    String getId() { return id; }
    Vehicle getVehicle() { return vehicle; }
    ParkingSpot getSpot() { return spot; }
    long getEntryEpochMs() { return entryEpochMs; }
    int getEntryGateId() { return entryGateId; }
}

class Receipt {
    final String ticketId; final double amount; final boolean paid;
    Receipt(String ticketId, double amount, boolean paid) { this.ticketId = ticketId; this.amount = amount; this.paid = paid; }
    @Override public String toString() { return "Receipt{" + ticketId + ", amount=" + amount + ", paid=" + paid + "}"; }
}

/* ------------------------------------- Strategy: pricing ------------------------------------- */

interface PricingStrategy { double price(Ticket ticket, long exitEpochMs); }

/** Per-hour pricing (rounds partial hours up), rate by vehicle type. */
class HourlyPricing implements PricingStrategy {
    private final Map<VehicleType, Double> ratePerHour;
    HourlyPricing(Map<VehicleType, Double> ratePerHour) { this.ratePerHour = ratePerHour; }
    public double price(Ticket t, long exitEpochMs) {
        long ms = Math.max(0, exitEpochMs - t.getEntryEpochMs());
        long hours = Math.max(1, (long) Math.ceil(ms / 3_600_000.0)); // min 1 hour
        return hours * rateFor(t.getVehicle());
    }
    private double rateFor(Vehicle v) {
        VehicleType vt = toType(v);
        return ratePerHour.getOrDefault(vt, 20.0);
    }
    static VehicleType toType(Vehicle v) {
        if (v instanceof Motorcycle) return VehicleType.MOTORCYCLE;
        if (v instanceof Truck)      return VehicleType.TRUCK;
        if (v instanceof ElectricCar) return VehicleType.ELECTRIC;
        return VehicleType.CAR;
    }
}

/** Flat fee regardless of duration. */
class FlatPricing implements PricingStrategy {
    private final double flat;
    FlatPricing(double flat) { this.flat = flat; }
    public double price(Ticket t, long exitEpochMs) { return flat; }
}

/** Decorator: adds an EV energy surcharge on top of any pricing strategy. */
class EvSurchargePricing implements PricingStrategy {
    private final PricingStrategy delegate;
    private final double surcharge;
    EvSurchargePricing(PricingStrategy delegate, double surcharge) { this.delegate = delegate; this.surcharge = surcharge; }
    public double price(Ticket t, long exitEpochMs) {
        double base = delegate.price(t, exitEpochMs);
        return (t.getVehicle() instanceof ElectricCar) ? base + surcharge : base;
    }
}

/* -------------------------------- Strategy: spot assignment -------------------------------- */

interface SpotAssignmentStrategy { ParkingSpot select(List<ParkingFloor> floors, Vehicle v); }

/** First-fit: first floor that can accommodate the vehicle (with up-sizing) wins. */
class FirstFitAssignment implements SpotAssignmentStrategy {
    public ParkingSpot select(List<ParkingFloor> floors, Vehicle v) {
        for (ParkingFloor f : floors) {
            ParkingSpot s = f.allocate(v);
            if (s != null) return s;
        }
        return null;
    }
}

/** Nearest-first stub: ranks floors by distance to the gate (here, by floor number). */
class NearestFirstAssignment implements SpotAssignmentStrategy {
    public ParkingSpot select(List<ParkingFloor> floors, Vehicle v) {
        List<ParkingFloor> ordered = new ArrayList<>(floors);
        ordered.sort(Comparator.comparingInt(ParkingFloor::getFloorNumber));
        for (ParkingFloor f : ordered) {
            ParkingSpot s = f.allocate(v);
            if (s != null) return s;
        }
        return null;
    }
}

/* ------------------------------------ Strategy: payment ------------------------------------ */

interface PaymentStrategy { boolean pay(double amount); }
class CashPayment implements PaymentStrategy { public boolean pay(double amount) { System.out.println("  [cash] collected " + amount); return true; } }
class CardPayment implements PaymentStrategy { public boolean pay(double amount) { System.out.println("  [card] charged "  + amount); return true; } }

/* --------------------------------------- Observer --------------------------------------- */

interface DisplayBoard { void update(Map<SpotType, Integer> availability); }
class ConsoleDisplayBoard implements DisplayBoard {
    private final String name;
    ConsoleDisplayBoard(String name) { this.name = name; }
    public void update(Map<SpotType, Integer> availability) {
        System.out.println("  [board:" + name + "] free spots = " + availability);
    }
}

/* ----------------------------------------- Gates ----------------------------------------- */

class EntryGate { final int id; EntryGate(int id) { this.id = id; } }
class ExitGate  { final int id; ExitGate(int id)  { this.id = id; } }

/* ----------------------------------- ParkingLot (Singleton) ----------------------------------- */

class LotFullException extends RuntimeException { LotFullException(String m) { super(m); } }

class ParkingLot {
    // Holder idiom => thread-safe lazy Singleton. (Production: prefer DI over a static getInstance.)
    private static class Holder { static final ParkingLot INSTANCE = new ParkingLot(); }
    static ParkingLot getInstance() { return Holder.INSTANCE; }

    private final List<ParkingFloor> floors = new CopyOnWriteArrayList<>();
    private final List<DisplayBoard> boards = new CopyOnWriteArrayList<>();
    private final Map<String, Ticket> activeTickets = new ConcurrentHashMap<>();

    // Strategies — injectable so behavior is swappable at runtime.
    private volatile SpotAssignmentStrategy assignment = new FirstFitAssignment();
    private volatile PricingStrategy pricing = new FlatPricing(50.0);
    private volatile PaymentStrategy payment = new CardPayment();

    private ParkingLot() {}

    void setAssignment(SpotAssignmentStrategy s) { this.assignment = s; }
    void setPricing(PricingStrategy s)           { this.pricing = s; }
    void setPayment(PaymentStrategy s)           { this.payment = s; }

    void addFloor(ParkingFloor f) { floors.add(f); }
    void addBoard(DisplayBoard b) { boards.add(b); }

    /** Entry flow: allocate a fitting spot atomically, issue a ticket, refresh boards. */
    Ticket park(Vehicle v, EntryGate gate) {
        ParkingSpot spot = assignment.select(floors, v);
        if (spot == null) {
            notifyBoards();
            throw new LotFullException("No spot available for " + v);
        }
        Ticket ticket = new Ticket(v, spot, System.currentTimeMillis(), gate.id);
        activeTickets.put(ticket.getId(), ticket);
        System.out.println("PARK  " + v + " -> " + spot + " (ticket " + ticket.getId() + ", gate " + gate.id + ")");
        notifyBoards();
        return ticket;
    }

    /** Exit flow: price -> pay -> free spot -> refresh boards. Idempotent on unknown ticket. */
    Receipt unpark(String ticketId, ExitGate gate) {
        Ticket ticket = activeTickets.remove(ticketId);
        if (ticket == null) {
            System.out.println("UNPARK unknown/duplicate ticket " + ticketId + " (no-op)");
            return new Receipt(ticketId, 0.0, false);
        }
        double amount = pricing.price(ticket, System.currentTimeMillis());
        System.out.println("UNPARK " + ticket.getVehicle() + " from " + ticket.getSpot()
                + " (ticket " + ticketId + ", gate " + gate.id + ") fee=" + amount);
        boolean paid = payment.pay(amount);
        // free regardless of payment outcome would be a policy choice; here we free only on success.
        ticket.getSpot(); // spot reference retained on the ticket
        floorOf(ticket.getSpot()).release(ticket.getSpot());
        notifyBoards();
        return new Receipt(ticketId, amount, paid);
    }

    private ParkingFloor floorOf(ParkingSpot spot) {
        // Spot ids are "F<floor>-..."; in a real model the spot would hold a back-reference to its floor.
        for (ParkingFloor f : floors) {
            for (Integer n : Collections.singletonList(f.getFloorNumber())) {
                if (spot.getId().startsWith("F" + n + "-")) return f;
            }
        }
        // Fallback: release on the first floor that can take it back (defensive).
        return floors.get(0);
    }

    private Map<SpotType, Integer> totalAvailability() {
        Map<SpotType, Integer> total = new EnumMap<>(SpotType.class);
        for (SpotType t : SpotType.values()) total.put(t, 0);
        for (ParkingFloor f : floors)
            for (Map.Entry<SpotType, Integer> e : f.availability().entrySet())
                total.merge(e.getKey(), e.getValue(), Integer::sum);
        return total;
    }

    private void notifyBoards() {
        Map<SpotType, Integer> snap = totalAvailability();
        for (DisplayBoard b : boards) b.update(snap);
    }
}

/* ------------------------------------------ Demo ------------------------------------------ */

public class Solution {
    public static void main(String[] args) {
        ParkingLot lot = ParkingLot.getInstance();

        // Build 2 floors with a mix of spots. Spot ids are prefixed with "F<floor>-".
        for (int floorNo = 1; floorNo <= 2; floorNo++) {
            ParkingFloor floor = new ParkingFloor(floorNo);
            floor.addSpot(ParkingSpotFactory.create("F" + floorNo + "-M1", SpotType.MOTORCYCLE));
            floor.addSpot(ParkingSpotFactory.create("F" + floorNo + "-C1", SpotType.COMPACT));
            floor.addSpot(ParkingSpotFactory.create("F" + floorNo + "-C2", SpotType.COMPACT));
            floor.addSpot(ParkingSpotFactory.create("F" + floorNo + "-L1", SpotType.LARGE));
            floor.addSpot(ParkingSpotFactory.create("F" + floorNo + "-E1", SpotType.EV));
            lot.addFloor(floor);
        }

        lot.addBoard(new ConsoleDisplayBoard("entrance"));

        // Strategy wiring: nearest-first assignment + hourly pricing with an EV surcharge decorator.
        lot.setAssignment(new NearestFirstAssignment());
        Map<VehicleType, Double> rates = new EnumMap<>(VehicleType.class);
        rates.put(VehicleType.MOTORCYCLE, 10.0);
        rates.put(VehicleType.CAR, 20.0);
        rates.put(VehicleType.TRUCK, 40.0);
        rates.put(VehicleType.ELECTRIC, 25.0);
        lot.setPricing(new EvSurchargePricing(new HourlyPricing(rates), 30.0));
        lot.setPayment(new CardPayment());

        EntryGate in = new EntryGate(1);
        ExitGate out = new ExitGate(1);

        // Park a variety of vehicles via the factory.
        Ticket t1 = lot.park(VehicleFactory.create(VehicleType.CAR, "KA01AB1234"), in);
        Ticket t2 = lot.park(VehicleFactory.create(VehicleType.MOTORCYCLE, "KA02CD5678"), in);
        Ticket t3 = lot.park(VehicleFactory.create(VehicleType.ELECTRIC, "KA03EV9999"), in);
        Ticket t4 = lot.park(VehicleFactory.create(VehicleType.TRUCK, "KA04TR0001"), in);

        System.out.println("--- exits ---");
        System.out.println(lot.unpark(t1.getId(), out));
        System.out.println(lot.unpark(t3.getId(), out));         // EV -> surcharge applies
        System.out.println(lot.unpark(t1.getId(), out));         // duplicate -> no-op

        // Concurrency demo: many threads compete for the few remaining spots; no double-allocation.
        System.out.println("--- concurrent rush ---");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                try {
                    Ticket t = lot.park(VehicleFactory.create(VehicleType.CAR, "RUSH-" + n), in);
                    System.out.println("  rush " + n + " parked at " + t.getSpot());
                } catch (LotFullException e) {
                    System.out.println("  rush " + n + " rejected: lot full");
                }
            }));
        }
        for (Future<?> f : futures) { try { f.get(); } catch (Exception ignore) {} }
        pool.shutdown();

        System.out.println("Demo complete.");
    }
}

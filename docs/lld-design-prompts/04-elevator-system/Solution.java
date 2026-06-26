/* =====================================================================================
 * ELEVATOR SYSTEM — single-file LLD review/revision artifact (read-and-revise; not meant
 * to be compiled/run in grading, though it is complete and correct-by-inspection).
 *
 * -------------------------------------------------------------------------------------
 * CLARIFYING / REQUIREMENTS QUESTIONS TO ASK BEFORE DESIGNING:
 *   FUNCTIONAL
 *     1. Single car or a bank of cars? Need optimal assignment or is round-robin OK for v1?
 *     2. Floor model: contiguous only, or basements / skipped floors / mezzanine?
 *     3. Hall buttons: separate Up AND Down (standard) or a single call button?
 *     4. Model doors explicitly (open/close/obstruction) or just "arrive at floor"?
 *     5. Update floor + in-car displays (direction arrows, current floor)?  -> Observer
 *     6. Model passengers/boarding/capacity, or only motion + request queue?
 *   SCHEDULING
 *     7. Dispatch objective: min avg wait / min total travel / max throughput?
 *     8. Is the scheduling policy fixed or must it be swappable at runtime?  -> Strategy
 *   NON-FUNCTIONAL
 *     9. Concurrency: many simultaneous requests; thread-safe? How many cars?
 *    10. Real-time (background threads/ticks) vs discrete simulation?
 *    11. Capacity/weight limit; behavior when full (skip hall pickups)?
 *    12. Fault handling: maintenance mode, emergency/fire-service, safe-park?
 *    13. Persistence / crash recovery? (usually out of scope)
 *
 * ASSUMPTIONS BAKED INTO THIS FILE:
 *   - Bank of M cars; floors minFloor..maxFloor.
 *   - Separate Up/Down hall requests + internal destination requests.
 *   - Pluggable DispatchStrategy (which car) + MovementStrategy (which floor next, LOOK).
 *   - Explicit Door; displays via Observer; capacity with skip-when-full.
 *   - Maintenance + Emergency modes via the State pattern.
 *   - DISCRETE simulation: a driver calls system.step(); per-car lock for thread-safety.
 *     (Production real-time model = one worker thread per car; described in the doc.)
 *
 * PATTERNS: State (ElevatorState), Strategy x2 (Dispatch + Movement), Command (Request),
 *           Observer (Display), Facade (+optional Singleton) (ElevatorSystem).
 * ===================================================================================== */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/* ============================ Enums & small value types ============================ */

enum Direction { UP, DOWN, IDLE }

/* -------- Command pattern: requests reified as objects (queueable/assignable) -------- */
abstract class Request {
    final int floor;
    Request(int floor) { this.floor = floor; }
}
final class ExternalRequest extends Request {   // hall call: floor + intended travel direction
    final Direction direction;
    ExternalRequest(int floor, Direction direction) { super(floor); this.direction = direction; }
}
final class InternalRequest extends Request {   // car call: destination floor pressed inside a car
    final int carId;
    InternalRequest(int carId, int floor) { super(floor); this.carId = carId; }
}

/* -------------------------------- Door ----------------------------------------------- */
final class Door {
    private boolean open = false;
    void open()  { open = true; }
    void close() { open = false; }
    boolean isOpen() { return open; }
}

/* -------- Observer pattern: displays react to a car's (floor, direction) updates ----- */
interface ElevatorObserver {
    void onUpdate(int carId, int floor, Direction dir, String state);
}
final class Display implements ElevatorObserver {
    private final String label;
    Display(String label) { this.label = label; }
    @Override public void onUpdate(int carId, int floor, Direction dir, String state) {
        System.out.printf("    [%s] Car#%d -> floor %d, dir %s, %s%n", label, carId, floor, dir, state);
    }
}

/* ===================== Strategy #1: which floor to go to next (LOOK/SCAN) ============= */
interface MovementStrategy {
    /** Decide the next direction of travel given the car's pending targets. */
    Direction nextDirection(ElevatorCar car);
}

/** LOOK: keep moving in the current direction while targets remain that way; otherwise
 *  reverse if the other set has targets; otherwise go IDLE. (LOOK reverses as soon as
 *  there's nothing further ahead — unlike SCAN which runs to the building extreme.) */
final class LookMovementStrategy implements MovementStrategy {
    @Override public Direction nextDirection(ElevatorCar car) {
        int f = car.getCurrentFloor();
        boolean targetsAbove = car.hasTargetAbove(f);
        boolean targetsBelow = car.hasTargetBelow(f);
        Direction cur = car.getDirection();

        if (cur == Direction.UP) {
            if (targetsAbove) return Direction.UP;
            if (targetsBelow) return Direction.DOWN;
            return Direction.IDLE;
        }
        if (cur == Direction.DOWN) {
            if (targetsBelow) return Direction.DOWN;
            if (targetsAbove) return Direction.UP;
            return Direction.IDLE;
        }
        // currently IDLE: pick whichever side has work (prefer up on tie)
        if (targetsAbove) return Direction.UP;
        if (targetsBelow) return Direction.DOWN;
        return Direction.IDLE;
    }
}

/** SCAN variant kept to show the swap point; here it behaves like LOOK at the data we
 *  track (we only store real targets, not building extremes), so it delegates the idea. */
final class ScanMovementStrategy implements MovementStrategy {
    private final LookMovementStrategy look = new LookMovementStrategy();
    @Override public Direction nextDirection(ElevatorCar car) { return look.nextDirection(car); }
}

/* ===================== Strategy #2: which car serves an external request ============== */
interface DispatchStrategy {
    /** Returns the chosen car, or null if none can currently serve. */
    ElevatorCar selectCar(List<ElevatorCar> cars, ExternalRequest req);
}

/** Simple, fair baseline. */
final class RoundRobinDispatch implements DispatchStrategy {
    private final AtomicInteger cursor = new AtomicInteger(0);
    @Override public ElevatorCar selectCar(List<ElevatorCar> cars, ExternalRequest req) {
        int n = cars.size();
        for (int i = 0; i < n; i++) {
            ElevatorCar c = cars.get(cursor.getAndIncrement() % n);
            if (c.canServe(req.floor) && c.canAccept()) return c;
        }
        return null;
    }
}

/** Heuristic: pick the lowest-cost candidate (rough ETA). Filters by zone, maintenance,
 *  and capacity. Cost = distance, halved when the car is already heading the right way
 *  toward the request; tie-break by lighter load then lowest id (deterministic). */
final class NearestCarDispatch implements DispatchStrategy {
    @Override public ElevatorCar selectCar(List<ElevatorCar> cars, ExternalRequest req) {
        ElevatorCar best = null;
        double bestCost = Double.MAX_VALUE;
        for (ElevatorCar c : cars) {
            if (!c.canServe(req.floor) || !c.canAccept()) continue;
            double cost = estimateCost(c, req);
            if (cost < bestCost
                    || (cost == bestCost && best != null && c.getLoad() < best.getLoad())) {
                bestCost = cost; best = c;
            }
        }
        return best;
    }
    private double estimateCost(ElevatorCar c, ExternalRequest req) {
        int distance = Math.abs(c.getCurrentFloor() - req.floor);
        Direction d = c.getDirection();
        boolean movingToward =
                (d == Direction.UP   && req.floor >= c.getCurrentFloor() && req.direction == Direction.UP) ||
                (d == Direction.DOWN && req.floor <= c.getCurrentFloor() && req.direction == Direction.DOWN);
        if (d == Direction.IDLE) return distance;          // free to come straight over
        if (movingToward)        return distance * 0.5;     // will pass by anyway
        return distance + 4.0;                              // must finish its sweep first (penalty)
    }
}

/* ============================== State pattern ======================================== */
interface ElevatorState {
    void step(ElevatorCar car);   // one tick of behavior
    String name();
}

/** Parked, no targets. On a tick, ask movement strategy if work appeared. */
final class IdleState implements ElevatorState {
    @Override public void step(ElevatorCar car) {
        Direction next = car.getMovement().nextDirection(car);
        if (next != Direction.IDLE) {
            car.setDirection(next);
            car.setState(new MovingState());
        } else {
            car.setDirection(Direction.IDLE);
        }
        car.notifyObservers();
    }
    @Override public String name() { return "IDLE"; }
}

/** In motion. Move one floor toward work; if current floor is a target, stop & open. */
final class MovingState implements ElevatorState {
    @Override public void step(ElevatorCar car) {
        if (car.isTarget(car.getCurrentFloor())) {   // arrived
            car.setState(new DoorsOpenState());
            car.notifyObservers();
            return;
        }
        Direction dir = car.getMovement().nextDirection(car);
        if (dir == Direction.IDLE) { car.setDirection(Direction.IDLE); car.setState(new IdleState()); }
        else {
            car.setDirection(dir);
            car.moveOneFloor(dir);
            if (car.isTarget(car.getCurrentFloor())) car.setState(new DoorsOpenState());
        }
        car.notifyObservers();
    }
    @Override public String name() { return "MOVING"; }
}

/** Doors open: serve this floor (clear its targets, board/alight), then close & continue. */
final class DoorsOpenState implements ElevatorState {
    @Override public void step(ElevatorCar car) {
        car.getDoor().open();
        car.serveCurrentFloor();          // clear targets at this floor, adjust load
        car.getDoor().close();
        Direction next = car.getMovement().nextDirection(car);
        if (next == Direction.IDLE) { car.setDirection(Direction.IDLE); car.setState(new IdleState()); }
        else { car.setDirection(next); car.setState(new MovingState()); }
        car.notifyObservers();
    }
    @Override public String name() { return "DOORS_OPEN"; }
}

/** Out of service: refuses work and is excluded from dispatch. */
final class MaintenanceState implements ElevatorState {
    @Override public void step(ElevatorCar car) { car.notifyObservers(); /* no motion */ }
    @Override public String name() { return "MAINTENANCE"; }
}

/** Emergency/fire-service: targets already flushed; sit locked at the park floor. */
final class EmergencyState implements ElevatorState {
    @Override public void step(ElevatorCar car) {
        if (car.getCurrentFloor() != car.getEmergencyFloor()) {
            Direction d = car.getCurrentFloor() < car.getEmergencyFloor() ? Direction.UP : Direction.DOWN;
            car.setDirection(d);
            car.moveOneFloor(d);
        } else {
            car.getDoor().open();
        }
        car.notifyObservers();
    }
    @Override public String name() { return "EMERGENCY"; }
}

/* ================================ ElevatorCar ======================================== */
final class ElevatorCar {
    private final int id;
    private volatile int currentFloor;
    private volatile Direction direction = Direction.IDLE;
    private volatile ElevatorState state = new IdleState();
    private final Door door = new Door();
    private final MovementStrategy movement;

    // Direction-tagged targets => idempotent dedupe + correct Up/Down hall handling.
    private final NavigableSet<Integer> upTargets = new TreeSet<>();
    private final NavigableSet<Integer> downTargets = new TreeSet<>();

    private int load = 0;
    private final int capacity;
    private final Set<Integer> serviceableFloors;   // null => serves all (zoning/express hook)
    private volatile boolean maintenance = false;
    private volatile int emergencyFloor = -1;

    private final List<ElevatorObserver> observers = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();        // per-car lock (no global lock)

    ElevatorCar(int id, int startFloor, int capacity,
                MovementStrategy movement, Set<Integer> serviceableFloors) {
        this.id = id; this.currentFloor = startFloor; this.capacity = capacity;
        this.movement = movement; this.serviceableFloors = serviceableFloors;
    }

    /* ---- public API ---- */
    void addObserver(ElevatorObserver o) { observers.add(o); }

    void addTarget(int floor, Direction servingDir) {
        synchronized (lock) {
            if (!canServe(floor)) return;
            if (servingDir == Direction.UP || (servingDir == Direction.IDLE && floor > currentFloor))
                upTargets.add(floor);
            else if (servingDir == Direction.DOWN || (servingDir == Direction.IDLE && floor < currentFloor))
                downTargets.add(floor);
            else { // same floor while idle: open here
                upTargets.add(floor);
            }
            if (state instanceof IdleState) { /* will pick up motion on next step */ }
        }
    }

    /** One tick: delegate to the current state (State pattern). Guarded per-car. */
    void step() { synchronized (lock) { state.step(this); } }

    boolean canAccept() { return load < capacity && !maintenance; }
    boolean canServe(int floor) {
        if (maintenance) return false;
        return serviceableFloors == null || serviceableFloors.contains(floor);
    }

    void enterMaintenance() {
        synchronized (lock) {
            maintenance = true; upTargets.clear(); downTargets.clear();
            setState(new MaintenanceState());
        }
    }
    void exitMaintenance() {
        synchronized (lock) { maintenance = false; setState(new IdleState()); setDirection(Direction.IDLE); }
    }
    void triggerEmergency(int parkFloor) {
        synchronized (lock) {
            upTargets.clear(); downTargets.clear();
            emergencyFloor = parkFloor;
            setState(new EmergencyState());
        }
    }

    /* ---- state-helper API used by ElevatorState impls (package-visible behavior) ---- */
    void moveOneFloor(Direction d) { currentFloor += (d == Direction.UP ? 1 : -1); }

    boolean isTarget(int floor) { return upTargets.contains(floor) || downTargets.contains(floor); }
    boolean hasTargetAbove(int f) {
        return !upTargets.tailSet(f, false).isEmpty() || !downTargets.tailSet(f, false).isEmpty();
    }
    boolean hasTargetBelow(int f) {
        return !upTargets.headSet(f, false).isEmpty() || !downTargets.headSet(f, false).isEmpty();
    }
    /** Serve current floor: clear its targets and model a boarding/alighting churn. */
    void serveCurrentFloor() {
        upTargets.remove(currentFloor);
        downTargets.remove(currentFloor);
        if (load > 0) load--;                 // someone alights
        if (load < capacity) load++;          // someone boards (bounded by capacity)
    }

    void setState(ElevatorState s) { this.state = s; }
    void setDirection(Direction d) { this.direction = d; }

    void notifyObservers() {
        for (ElevatorObserver o : observers) o.onUpdate(id, currentFloor, direction, state.name());
    }

    /* ---- getters ---- */
    int getId() { return id; }
    int getCurrentFloor() { return currentFloor; }
    Direction getDirection() { return direction; }
    Door getDoor() { return door; }
    MovementStrategy getMovement() { return movement; }
    int getLoad() { return load; }
    int getEmergencyFloor() { return emergencyFloor; }
    String stateName() { return state.name(); }
    boolean isBusy() { return !(state instanceof IdleState) || !upTargets.isEmpty() || !downTargets.isEmpty(); }
}

/* ============================ ElevatorSystem (Facade) ================================ */
final class ElevatorSystem {
    private final List<ElevatorCar> cars;
    private final DispatchStrategy dispatch;
    private final int minFloor, maxFloor;

    ElevatorSystem(List<ElevatorCar> cars, DispatchStrategy dispatch, int minFloor, int maxFloor) {
        this.cars = cars; this.dispatch = dispatch; this.minFloor = minFloor; this.maxFloor = maxFloor;
    }

    /** External hall call. Picks a car via the dispatch strategy. */
    void requestElevator(int floor, Direction dir) {
        if (floor < minFloor || floor > maxFloor) { System.out.println("  ! invalid floor " + floor); return; }
        ExternalRequest req = new ExternalRequest(floor, dir);
        ElevatorCar car = dispatch.selectCar(cars, req);
        if (car == null) { System.out.println("  ! no car available for hall call @ " + floor + " " + dir); return; }
        System.out.printf("  hall call floor %d (%s) -> assigned Car#%d%n", floor, dir, car.getId());
        car.addTarget(floor, dir);
    }

    /** Internal destination press inside a specific car. */
    void pressFloor(int carId, int destFloor) {
        if (destFloor < minFloor || destFloor > maxFloor) { System.out.println("  ! invalid dest " + destFloor); return; }
        ElevatorCar car = byId(carId);
        if (car == null) { System.out.println("  ! unknown car " + carId); return; }
        Direction dir = destFloor > car.getCurrentFloor() ? Direction.UP
                       : destFloor < car.getCurrentFloor() ? Direction.DOWN : Direction.IDLE;
        System.out.printf("  car press: Car#%d -> dest %d%n", carId, destFloor);
        car.addTarget(destFloor, dir);
    }

    /** Advance the whole simulation one tick (each car steps under its own lock). */
    void step() { for (ElevatorCar c : cars) c.step(); }

    void setMaintenance(int carId, boolean on) {
        ElevatorCar c = byId(carId); if (c == null) return;
        if (on) c.enterMaintenance(); else c.exitMaintenance();
        System.out.printf("  Car#%d maintenance=%b%n", carId, on);
    }

    void triggerEmergency(int parkFloor) {
        System.out.println("  *** EMERGENCY: all cars -> floor " + parkFloor + " ***");
        for (ElevatorCar c : cars) c.triggerEmergency(parkFloor);
    }

    boolean anyBusy() { for (ElevatorCar c : cars) if (c.isBusy()) return true; return false; }
    private ElevatorCar byId(int id) { for (ElevatorCar c : cars) if (c.getId() == id) return c; return null; }
}

/* ================================== Demo / main ====================================== */
public class Solution {
    public static void main(String[] args) {
        final int MIN = 0, MAX = 10, CAP = 8;

        // Two general cars (LOOK) + one express car that only serves lobby(0) and high floors 8-10.
        ElevatorCar c1 = new ElevatorCar(1, 0, CAP, new LookMovementStrategy(), null);
        ElevatorCar c2 = new ElevatorCar(2, 5, CAP, new LookMovementStrategy(), null);
        Set<Integer> expressZone = new HashSet<>(Arrays.asList(0, 8, 9, 10));
        ElevatorCar c3 = new ElevatorCar(3, 0, CAP, new ScanMovementStrategy(), expressZone);

        // Observer: in-car displays.
        c1.addObserver(new Display("car1-disp"));
        c2.addObserver(new Display("car2-disp"));
        c3.addObserver(new Display("expr-disp"));

        List<ElevatorCar> cars = Arrays.asList(c1, c2, c3);
        ElevatorSystem system = new ElevatorSystem(cars, new NearestCarDispatch(), MIN, MAX);

        System.out.println("=== Scenario 1: hall calls assigned by nearest-car ===");
        system.requestElevator(3, Direction.UP);   // c1 idle@0 (cost 3) vs c2 idle@5 (cost 2) -> c2
        system.requestElevator(9, Direction.DOWN);  // express c3 in-zone is a candidate too
        runUntilSettled(system, 25);

        System.out.println("\n=== Scenario 2: internal presses + LOOK sequencing ===");
        system.pressFloor(2, 8);   // already-served car continues upward
        system.requestElevator(1, Direction.DOWN);
        runUntilSettled(system, 25);

        System.out.println("\n=== Scenario 3: maintenance excludes a car from dispatch ===");
        system.setMaintenance(2, true);
        system.requestElevator(6, Direction.UP);   // must NOT pick car#2
        runUntilSettled(system, 20);
        system.setMaintenance(2, false);

        System.out.println("\n=== Scenario 4: emergency park ===");
        system.triggerEmergency(0);
        runUntilSettled(system, 15);

        System.out.println("\nDone.");
    }

    /** Drive ticks until all cars are idle or a tick budget is exhausted. */
    private static void runUntilSettled(ElevatorSystem system, int maxTicks) {
        int t = 0;
        while (t < maxTicks && system.anyBusy()) {
            System.out.println("  -- tick " + (++t) + " --");
            system.step();
        }
    }
}
